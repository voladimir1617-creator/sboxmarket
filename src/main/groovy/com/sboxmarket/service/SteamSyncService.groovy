package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
                syncOne(user)
                Thread.sleep(1000L)  // 1 req/s ceiling
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
                break
            } catch (Exception e) {
                log.warn("Steam sync failed for ${user.steamId64}: ${e.message}")
            }
        }
    }

    @Transactional
    void syncOne(SteamUser user) {
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
        def before = fresh.steamInventorySize ?: 0
        fresh.lastSyncedAt = System.currentTimeMillis()
        if (!blocked) {
            fresh.steamInventorySize = now
        }
        steamUserRepository.save(fresh)

        if (!blocked && now > before) {
            notificationService?.push(user.id, 'STEAM_INVENTORY',
                "New Steam inventory items",
                "${now - before} new item(s) ready to list", null, '/sell')
        }
    }

    /** On-demand sync — wired to POST /api/steam/sync from the Profile modal.
     *  Drops cached state for this user before retrying so an explicit click
     *  ALWAYS hits Steam, then surfaces rate-limit / private-inventory state
     *  back to the caller so the UI can show a real reason instead of a
     *  pretend "0 items synced" success toast. */
    @Transactional
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
