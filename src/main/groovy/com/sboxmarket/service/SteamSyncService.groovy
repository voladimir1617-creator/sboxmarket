package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Background job — walks every registered Steam user every ~20 minutes and:
 *   1) refreshes their display name / avatar in case they changed it on Steam,
 *   2) re-reads their public s&box inventory (appid 590830),
 *   3) caches the inventory size and lastSyncedAt on the SteamUser row so the
 *      Profile modal can show "last synced ago" without another round-trip.
 *
 * Failures per-user are logged and isolated — one 403/429 never halts the
 * sweep. Rate-limited to one user per second to avoid Steam anti-abuse.
 */
@Service
@Slf4j
class SteamSyncService {

    // 20 minutes — explicit in ms so the value isn't hidden behind a unit string.
    static final long SYNC_INTERVAL_MS = 20L * 60L * 1000L

    /** How stale a user's Steam profile can be before the sweeper picks them
     *  up. Combined with the per-tick batch cap below, this is how we
     *  guarantee every user gets refreshed on a rolling schedule. */
    static final long STALE_AFTER_MS = 24L * 60L * 60L * 1000L

    /** Hard cap on how many users a single sweep tick will touch. At 1 req/s
     *  this gives a ~15-minute worst-case tick, comfortably inside the
     *  20-minute interval so ticks don't overlap. */
    static final int BATCH_SIZE = 900

    @Autowired SteamUserRepository steamUserRepository
    @Autowired SteamAuthService steamAuthService
    @Autowired SteamInventoryService steamInventoryService
    @Autowired NotificationService notificationService
    /** Optional in unit tests (the SpringRunner-less Spock specs don't wire a
     *  PlatformTransactionManager). When null, doSyncOne is executed inline
     *  WITHOUT a wrapping tx — which is exactly what the legacy code did
     *  anyway from internal call sites, and is fine for the mock-DB specs.
     *  In production, the auto-configured JpaTransactionManager is injected
     *  and gives us the commit-before-lock-release ordering this service
     *  needs to keep the per-user notification de-dupe honest. */
    @Autowired(required = false) PlatformTransactionManager transactionManager

    /** Per-user serialization for syncOne. Two concurrent sync attempts on
     *  the SAME user (e.g. a user clicking "Re-sync now" from two browser
     *  tabs, or the scheduler hitting a user at the same instant they
     *  trigger an on-demand sync) used to race: both threads called
     *  `findById`, both read the same `before` count, both computed
     *  `now > before`, both pushed a STEAM_INVENTORY notification — the
     *  user got duplicate "N new item(s)" toasts for a single real delta.
     *  Worse, the second `save` would also overwrite the first with a
     *  stale-from-its-perspective value of `now`.
     *
     *  Holding a per-user monitor while we read-modify-write the row
     *  serializes those attempts so the second one sees the updated
     *  steamInventorySize and either suppresses or correctly differs the
     *  notification.
     *
     *  Lock objects accumulate one-per-user-ever-seen. At ~16 bytes per
     *  lock + ~64 bytes of HashMap entry overhead, a million distinct
     *  syncOne callers cost <100MB — acceptable for the lifetime of
     *  a JVM process, and the realistic upper bound is the
     *  registered-user count, not unbounded. The previous "bounded
     *  soft-eviction at SYNC_LOCK_MAX" was a correctness bug: evicting
     *  a lock from the map while Thread A held `synchronized(lockX)`
     *  let Thread C call `lockFor(sameUserId)`, get a freshly-minted
     *  lockY via `computeIfAbsent`, and run `doSyncOne` concurrently
     *  with A on the same user — defeating the whole point of the
     *  per-user serialisation (duplicate STEAM_INVENTORY pushes, stale
     *  steamInventorySize overwrite). The eviction was guarding against
     *  a non-problem (the memory cost) at the price of re-opening the
     *  exact race the locks exist to close. */
     private final java.util.concurrent.ConcurrentHashMap<Long, Object> userSyncLocks = new java.util.concurrent.ConcurrentHashMap<>()

     private Object lockFor(Long userId) {
         userSyncLocks.computeIfAbsent(userId, { new Object() })
     }

    @Scheduled(fixedDelay = SYNC_INTERVAL_MS, initialDelay = 60_000L)
    void syncAllUsers() {
        // Only pull users who are actually stale, and cap the batch so
        // one tick can never balloon into an hours-long walk. Users get
        // processed oldest-lastSyncedAt-first so a newly registered
        // account lands on the front of the queue.
        def cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        def page = org.springframework.data.domain.PageRequest.of(0, BATCH_SIZE)
        def users = steamUserRepository.findStaleForSync(cutoff, page)
        if (users.isEmpty()) return
        log.info("Steam sync tick — ${users.size()} stale users (cutoff ${STALE_AFTER_MS / 3600000}h)")
        // A classic for-loop, not users.each {} — `return` inside an .each
        // closure only ends the current iteration, so an interrupt would
        // NOT abort the sweep: the next Thread.sleep re-throws immediately
        // (the interrupt flag is still set) and the loop spins through every
        // remaining user running a full syncOne with zero throttle, defeating
        // both shutdown and the 1 req/s ceiling. `break` in a real loop
        // actually stops the walk.
        for (def user : users) {
            try {
                try {
                    syncOne(user)
                } catch (InterruptedException ie) {
                    throw ie
                } catch (Exception e) {
                    log.warn("Steam sync failed for ${user.steamId64}: ${e.message}")
                }
                // Throttle UNCONDITIONALLY between users — the sleep used
                // to live only on the success path, so a run of failing
                // users (e.g. Steam IP-banning us mid-sweep, every fetch
                // 429s and throws) would spin through the loop at full
                // CPU speed, hammering Steam with zero rate limiting
                // exactly when we should be slowing down the most. The
                // 1 req/s ceiling has to bound failure throughput too.
                Thread.sleep(1000L)
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    void syncOne(SteamUser user) {
        // Serialise concurrent attempts for the same user — see lockFor's
        // doc for the race this closes. The lock is JVM-local: it doesn't
        // help across multiple app instances, but the only realistic
        // multi-thread collision in a single-instance deployment is
        // (a) two browser tabs hitting /api/steam/sync simultaneously, or
        // (b) the scheduler tick reaching a user at the same moment they
        // click Re-sync. Both are intra-instance and covered here.
        //
        // CRITICAL: the transaction is opened INSIDE the lock and committed
        // BEFORE the lock is released. The previous shape was
        // `@Transactional` on this method with `synchronized` in the body —
        // i.e. Spring's proxy opened the tx, the method then acquired the
        // monitor, released it BEFORE the proxy committed, and a waiting
        // second thread could enter the critical section while the first
        // writer's UPDATE was still uncommitted. Two concurrent
        // /api/steam/sync POSTs for the same user each saw `before=N`
        // from the pre-commit row, both pushed STEAM_INVENTORY for one
        // real delta → duplicate "M new item(s)" toasts. By owning the
        // tx boundary here and ending it before releasing the monitor,
        // the waiting thread\'s findById in doSyncOne reads the freshly
        // committed row and correctly observes `before == now` → no push.
        synchronized (lockFor(user.id)) {
            if (transactionManager != null) {
                new TransactionTemplate(transactionManager).executeWithoutResult { doSyncOne(user) }
            } else {
                // Tests run without a real tx manager; the buffered-DB mocks
                // in SteamSyncServiceSpec model commit visibility themselves.
                doSyncOne(user)
            }
        }
    }

    private void doSyncOne(SteamUser user) {
        // 1) profile refresh — reuse the same code path login uses so display
        // name / avatar stays in lock-step with Steam.
        try {
            steamAuthService.upsertUser(user.steamId64)
        } catch (Exception e) {
            log.debug("Profile refresh skipped for ${user.steamId64}: ${e.message}")
        }

        // 2) inventory snapshot — count items, remember previous count so we
        // can fire a notification when new items appear. If Steam blocked us
        // (rate-limit or private inventory), DON'T overwrite the cached
        // inventorySize — we want the user's previous good count to stand
        // until we get a real successful fetch. Otherwise a single 429 would
        // wipe their pool to zero in the UI for the next 5 minutes.
        def inv = steamInventoryService.fetchInventory(user.steamId64)
        def blocked = steamInventoryService.blockedUntilMs(user.steamId64) != null
        def now = inv.size()
        // Read `before` from the freshly re-fetched row, NOT the (potentially
        // stale) `user` argument. The sweep loads the user list at the start
        // of the tick and can take minutes to reach this row; in that window
        // an on-demand sync (POST /api/steam/sync) or upsertUser() above may
        // have already bumped steamInventorySize. Comparing `now` against the
        // tick-start snapshot would fire a phantom "N new item(s)" push that
        // doesn't match the user's actual inventory delta — at best a noisy
        // toast, at worst a doubled push for the same delta on the next tick.
        def fresh = steamUserRepository.findById(user.id).orElse(user)
        // Distinguish "we have never recorded a count for this user" (null)
        // from "the recorded count is 0" (Integer 0). The first-ever sync
        // for a brand-new account otherwise fires a STEAM_INVENTORY toast
        // saying "50 new item(s) ready to list" for items the user has
        // owned since long before they signed up — landing immediately
        // after the WELCOME push from upsertUser, doubling the
        // sign-up-notification load and over-counting deltas. With this
        // gate, the first successful sync silently RECORDS the baseline
        // count; only sweep #2 onwards can fire the delta notification.
        def priorRecorded = fresh.steamInventorySize
        def before = priorRecorded ?: 0
        def isBaseline = (priorRecorded == null)
        // ONLY bump lastSyncedAt on a real successful fetch. Bumping it on
        // a blocked fetch (rate-limit / private inventory) used to push the
        // user out of `findStaleForSync`'s cutoff window for the full 24h
        // STALE_AFTER_MS — defeating the rolling-staleness model. Steam
        // typically blocks us for ~5 minutes; the very next 20-minute tick
        // SHOULD re-attempt this user once the block has lifted. With the
        // unconditional bump, a single transient 429 silently downgraded the
        // user to one sync attempt every 24h, exactly contrary to the
        // doSyncOne comment above ("until we get a real successful fetch").
        long syncedAt = System.currentTimeMillis()
        if (!blocked) {
            fresh.lastSyncedAt = syncedAt
            fresh.steamInventorySize = now
        }
        steamUserRepository.save(fresh)

        if (!blocked && !isBaseline && now > before) {
            // Multi-pod de-dupe (wave 131). The synchronized(lockFor(id))
            // monitor in syncOne serialises concurrent attempts WITHIN one
            // JVM, but it does nothing across pods: in a multi-pod deploy two
            // pods can both run this tick (or one pod's tick races the user's
            // on-demand POST /api/steam/sync on another pod) for the SAME
            // user, both read the same `before`, both observe `now > before`,
            // and both fire a STEAM_INVENTORY push for one real delta. The
            // atomic compare-and-set below makes the count-advance the
            // authoritative gate: the UPDATE flips steamInventorySize from
            // the `before` this pod read to `now` ONLY while the row still
            // holds `before`. Exactly one pod gets 1 back and pushes; the
            // loser (0) skips the push, but the count still converges (the
            // winner's UPDATE — and the unconditional save above — persisted
            // `now`). Same shape as WatchlistAlertRepository.claimForFiring
            // (112), claimEndingSoonNotify (124), claimReviewNudge (129).
            int claimed = steamUserRepository.claimInventoryGrowth(user.id, before, now, syncedAt)
            if (claimed == 1) {
                notificationService?.push(user.id, 'STEAM_INVENTORY',
                    "New Steam inventory items",
                    "${now - before} new item(s) ready to list", null, '/sell')
            } else {
                log.debug("STEAM_INVENTORY claim lost for user ${user.id} (before=${before}, now=${now}) — sibling pod already fired")
            }
        }
    }

    /** On-demand sync — wired to POST /api/steam/sync from the Profile modal.
     *  Drops cached state for this user before retrying so an explicit click
     *  ALWAYS hits Steam, then surfaces rate-limit / private-inventory state
     *  back to the caller so the UI can show a real reason instead of a
     *  pretend "0 items synced" success toast.
     *
     *  NOTE: deliberately NOT @Transactional. The wrapping tx used to be the
     *  source of a duplicate-notification race — see syncOne\'s comment. The
     *  per-step operations (findById, clearCacheFor, the row save inside
     *  syncOne) each manage their own transactional boundaries; the
     *  per-user lock + tx-template-inside-lock in syncOne is what gives us
     *  notification de-dupe across concurrent /api/steam/sync POSTs. A
     *  method-level @Transactional here would reopen the same window that
     *  was just closed (it would extend the caller\'s tx around syncOne and
     *  delay the commit until AFTER the monitor is released). */
    Map syncNow(Long userId) {
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) return [ok: false, error: 'Unknown user']
        try {
            // The user is asking for a real refresh — drop both positive and
            // negative cache so fetchInventory actually round-trips Steam.
            steamInventoryService.clearCacheFor(user.steamId64)
            syncOne(user)
            // Re-read post-sync; check for the rate-limit signal.
            Long blockedUntil = steamInventoryService.blockedUntilMs(user.steamId64)
            def fresh = steamUserRepository.findById(userId).orElse(user)
            if (blockedUntil != null) {
                // NB: Groovy's `/` on longs yields a BigDecimal — use intdiv()
                // so these stay whole numbers (a fractional "~1.48 minutes"
                // would otherwise leak into the message and the payload).
                long retryInSec = Math.max(1L, (blockedUntil - System.currentTimeMillis()).intdiv(1000L))
                long retryInMin = Math.max(1L, (retryInSec + 59L).intdiv(60L))
                return [
                    ok:            false,
                    reason:        'rate_limited',
                    retryAt:       blockedUntil,
                    retryInSec:    retryInSec,
                    inventorySize: fresh.steamInventorySize ?: 0,
                    lastSyncedAt:  fresh.lastSyncedAt,
                    error:         "Steam is rate-limiting our requests — try again in ~${retryInMin} minute${retryInMin == 1L ? '' : 's'}.".toString()
                ]
            }
            return [
                ok:             true,
                lastSyncedAt:   fresh.lastSyncedAt,
                inventorySize:  fresh.steamInventorySize ?: 0
            ]
        } catch (Exception e) {
            log.warn("On-demand sync failed for ${user.steamId64}: ${e.message}")
            return [ok: false, error: 'Steam sync failed — try again in a few minutes']
        }
    }
}
