package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SteamAuthService
import com.sboxmarket.service.SteamInventoryService
import com.sboxmarket.service.SteamSyncService
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Wave 131 — multi-pod STEAM_INVENTORY duplicate-push regression pin for
 * {@link SteamSyncService#syncOne} / {@code doSyncOne}.
 *
 * THE BUG. doSyncOne fires a STEAM_INVENTORY "N new item(s)" push when the
 * freshly-fetched inventory count exceeds the previously-recorded
 * {@code steamInventorySize}. Concurrent attempts are de-duped with a
 * `synchronized(lockFor(user.id))` monitor — but that lock is JVM-LOCAL.
 *
 * In a multi-pod deploy two pods can both run the scheduled `syncAllUsers`
 * tick for the SAME user (or one pod's tick races the user's on-demand
 * POST /api/steam/sync landing on another pod). Each pod has its OWN
 * `userSyncLocks` map, so neither blocks the other. Both read the same
 * `before` count, both fetch the same grown inventory, both observe
 * `before < now`, and — with the pre-fix code — both fire a STEAM_INVENTORY
 * push. The user gets the "N new items" toast TWICE for one real delta.
 *
 * This is the same multi-pod bug class already closed elsewhere with an
 * atomic conditional claim: WatchlistAlertRepository.claimForFiring (112),
 * claimEndingSoonNotify (124), TradeRepository.claimReviewNudge /
 * claimSlowSellerWarning (129), the FraudSignalClaim ledger (120).
 *
 * THE FIX. doSyncOne gates the push behind a compare-and-set —
 * {@link SteamUserRepository#claimInventoryGrowth} — that advances
 * steamInventorySize from the exact `before` this pod read up to `now`
 * ONLY while the row still holds `before`. Exactly one pod's UPDATE
 * matches (gets 1 → pushes); the other gets 0 and skips the push. The
 * count converges either way.
 *
 * Pinned four ways:
 *   1. Two real pods (two SteamSyncService instances, separate JVM-local
 *      locks) racing one shared compare-and-set DB → EXACTLY ONE push.
 *   2. Losing pod (claim returns 0) → push suppressed.
 *   3. Winning pod (claim returns 1) → push fires.
 *   4. Baseline + blocked paths never consult the claim (preserved
 *      behaviour) — first-ever sync is silent, a rate-limited fetch
 *      neither pushes nor claims.
 */
class SteamSyncMultiPodInventoryClaimSpec extends Specification {

    // ──────────────────────────────────────────────────────────────────
    // (1) Two real pods racing one shared compare-and-set DB → ONE push.
    //
    //     This is the headline regression. Two SteamSyncService instances
    //     model two pods: each has its OWN userSyncLocks, so the JVM-local
    //     monitor in syncOne CANNOT serialise them. The only thing that can
    //     keep the push single is the atomic claim against the shared row.
    // ──────────────────────────────────────────────────────────────────

    def "two pods syncing the SAME user concurrently fire EXACTLY ONE STEAM_INVENTORY push, not two"() {
        given: 'a shared "DB" row — prior recorded count 1, inventory now holds 4 (one real delta of 3)'
        def committedSize = new AtomicReference<Integer>(1 as Integer)
        def pushCount = new AtomicInteger(0)

        and: 'the shared inventory service both pods hit — same 4-item snapshot for both'
        SteamInventoryService inv = Mock()
        inv.fetchInventory('111') >> [[a: 1], [a: 2], [a: 3], [a: 4]]
        inv.blockedUntilMs('111') >> null

        and: 'one shared NotificationService — counts pushes across BOTH pods'
        NotificationService notif = Mock()
        notif.push(10L, 'STEAM_INVENTORY', _, _, _, '/sell') >> { pushCount.incrementAndGet(); null }

        and: 'profile refresh is a no-op'
        SteamAuthService auth = Mock()

        and: """\
            A SHARED repository whose findById returns a fresh snapshot of the
            committed size (mimicking each pod's own JPA session reading the
            same row) and whose claimInventoryGrowth is a real atomic CAS:
            it advances the committed size from `before` → `now` and returns 1
            ONLY if the row still held `before`, else 0. This is exactly the
            semantics of the @Modifying conditional UPDATE."""
        SteamUserRepository repo = Mock()
        repo.findById(10L) >> {
            Optional.of(new SteamUser(id: 10L, steamId64: '111',
                steamInventorySize: committedSize.get()))
        }
        repo.save(_) >> { args -> args[0] }
        repo.claimInventoryGrowth(10L, _, _, _) >> { args ->
            Integer before = args[1] as Integer
            Integer now = args[2] as Integer
            committedSize.compareAndSet(before, now) ? 1 : 0
        }

        and: 'TWO separate service instances — two pods, two independent lock maps'
        def podA = new SteamSyncService(steamUserRepository: repo, steamAuthService: auth,
            steamInventoryService: inv, notificationService: notif)
        def podB = new SteamSyncService(steamUserRepository: repo, steamAuthService: auth,
            steamInventoryService: inv, notificationService: notif)

        when: 'both pods process the same user at the same instant'
        def userA = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 1)
        def userB = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 1)
        def tA = Thread.start { podA.syncOne(userA) }
        def tB = Thread.start { podB.syncOne(userB) }
        tA.join(5000L)
        tB.join(5000L)

        then: """\
            EXACTLY ONE push total. The pre-fix code (no claim — push fires
            whenever now > before) would push from BOTH pods because their
            JVM-local locks don't see each other. The atomic claim lets only
            the pod whose CAS lands fire; the loser's CAS fails (row already
            reads 4) and it skips the push."""
        pushCount.get() == 1

        and: 'the count still converged to the real value on both pods'
        committedSize.get() == 4
    }

    // ──────────────────────────────────────────────────────────────────
    // (2) Losing pod — claim returns 0 → push suppressed.
    // ──────────────────────────────────────────────────────────────────

    def "doSyncOne does NOT push when claimInventoryGrowth returns 0 (sibling pod already fired the delta)"() {
        given:
        SteamUserRepository repo = Mock()
        SteamInventoryService inv = Mock()
        NotificationService notif = Mock()
        SteamAuthService auth = Mock()
        def service = new SteamSyncService(steamUserRepository: repo, steamAuthService: auth,
            steamInventoryService: inv, notificationService: notif)

        and: 'a genuine delta (before=1, now=4) — but a sibling pod already claimed it'
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 1)
        inv.fetchInventory('111') >> [[a: 1], [a: 2], [a: 3], [a: 4]]
        inv.blockedUntilMs('111') >> null
        repo.findById(10L) >> Optional.of(user)
        repo.save(_) >> { args -> args[0] }
        repo.claimInventoryGrowth(10L, 1, 4, _) >> 0

        when:
        service.syncOne(user)

        then: 'the losing pod must NOT fire the toast — that is the whole point of the claim'
        0 * notif.push(*_)

        and: 'the claim was still consulted exactly once for this delta'
        1 * repo.claimInventoryGrowth(10L, 1, 4, _) >> 0
    }

    // ──────────────────────────────────────────────────────────────────
    // (3) Winning pod — claim returns 1 → push fires.
    // ──────────────────────────────────────────────────────────────────

    def "doSyncOne pushes exactly once when claimInventoryGrowth returns 1 (this pod won the delta)"() {
        given:
        SteamUserRepository repo = Mock()
        SteamInventoryService inv = Mock()
        NotificationService notif = Mock()
        SteamAuthService auth = Mock()
        def service = new SteamSyncService(steamUserRepository: repo, steamAuthService: auth,
            steamInventoryService: inv, notificationService: notif)

        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 1)
        inv.fetchInventory('111') >> [[a: 1], [a: 2], [a: 3], [a: 4]]
        inv.blockedUntilMs('111') >> null
        repo.findById(10L) >> Optional.of(user)
        repo.save(_) >> { args -> args[0] }
        repo.claimInventoryGrowth(10L, 1, 4, _) >> 1

        when:
        service.syncOne(user)

        then: 'the winning pod fires the "3 new item(s)" toast routed to /sell'
        1 * notif.push(10L, 'STEAM_INVENTORY', _, { it.contains('3') }, _, '/sell')
    }

    // ──────────────────────────────────────────────────────────────────
    // (4) Baseline + blocked paths never consult the claim — preserved.
    // ──────────────────────────────────────────────────────────────────

    def "first-ever sync (priorRecorded == null) records the baseline silently and never claims"() {
        given:
        SteamUserRepository repo = Mock()
        SteamInventoryService inv = Mock()
        NotificationService notif = Mock()
        SteamAuthService auth = Mock()
        def service = new SteamSyncService(steamUserRepository: repo, steamAuthService: auth,
            steamInventoryService: inv, notificationService: notif)

        and: 'a brand-new account whose steamInventorySize has never been written (null)'
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: null)
        inv.fetchInventory('111') >> [[a: 1], [a: 2], [a: 3], [a: 4], [a: 5]]
        inv.blockedUntilMs('111') >> null
        repo.findById(10L) >> Optional.of(user)
        repo.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then: 'baseline is recorded silently — no toast, and the multi-pod claim is never consulted'
        user.steamInventorySize == 5
        0 * notif.push(*_)
        0 * repo.claimInventoryGrowth(*_)
    }

    def "a blocked (rate-limited) fetch neither pushes nor consults the claim"() {
        given:
        SteamUserRepository repo = Mock()
        SteamInventoryService inv = Mock()
        NotificationService notif = Mock()
        SteamAuthService auth = Mock()
        def service = new SteamSyncService(steamUserRepository: repo, steamAuthService: auth,
            steamInventoryService: inv, notificationService: notif)

        and: 'a known-good count of 5; Steam blocks us so fetch comes back empty'
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 5)
        inv.fetchInventory('111') >> []
        inv.blockedUntilMs('111') >> (System.currentTimeMillis() + 300_000L)
        repo.findById(10L) >> Optional.of(user)
        repo.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then: 'the prior count stands, no toast, and the claim is never touched'
        user.steamInventorySize == 5
        0 * notif.push(*_)
        0 * repo.claimInventoryGrowth(*_)
    }
}
