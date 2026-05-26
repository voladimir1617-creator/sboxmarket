package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SteamAuthService
import com.sboxmarket.service.SteamInventoryService
import com.sboxmarket.service.SteamSyncService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Steam background-sync behaviour without the actual network calls.
 *
 * Asserts: syncOne persists the inventory size + lastSyncedAt, fires a
 * STEAM_INVENTORY notification only when the count grew, and syncNow
 * returns structured error when the user id is unknown.
 */
class SteamSyncServiceSpec extends Specification {

    SteamUserRepository   steamUserRepository   = Mock()
    SteamAuthService      steamAuthService      = Mock()
    SteamInventoryService steamInventoryService = Mock()
    NotificationService   notificationService   = Mock()

    @Subject
    SteamSyncService service = new SteamSyncService(
        steamUserRepository  : steamUserRepository,
        steamAuthService     : steamAuthService,
        steamInventoryService: steamInventoryService,
        notificationService  : notificationService
    )

    def "syncOne persists the new inventory size + lastSyncedAt"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 0)
        steamInventoryService.fetchInventory('111') >> [[assetId: 'a'], [assetId: 'b'], [assetId: 'c']]
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then:
        user.steamInventorySize == 3
        user.lastSyncedAt != null
    }

    def "syncOne fires STEAM_INVENTORY notification when inventory grew"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 2)
        steamInventoryService.fetchInventory('111') >> [[a: 1], [a: 2], [a: 3], [a: 4]]
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then:
        1 * notificationService.push(10L, 'STEAM_INVENTORY', _, _, _, _)
    }

    def "syncOne does NOT notify when inventory stayed the same"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 4)
        steamInventoryService.fetchInventory('111') >> [[a: 1], [a: 2], [a: 3], [a: 4]]
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then:
        0 * notificationService.push(*_)
    }

    def "syncOne does NOT notify when inventory shrank (no false positives on trades)"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 10)
        steamInventoryService.fetchInventory('111') >> [[a: 1]]
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then:
        0 * notificationService.push(*_)
    }

    def "syncOne survives a thrown upsertUser by continuing to inventory"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        steamAuthService.upsertUser('111') >> { throw new RuntimeException('network down') }
        steamInventoryService.fetchInventory('111') >> []
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then:
        // Inventory still ran even though profile refresh threw
        noExceptionThrown()
        user.lastSyncedAt != null
    }

    def "syncOne PRESERVES the previous inventory size when Steam blocked us (rate-limit / private)"() {
        given: "a user with a known-good count whose inventory fetch comes back empty + blocked"
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 42, lastSyncedAt: 1234L)
        steamInventoryService.fetchInventory('111') >> []
        // blockedUntilMs non-null == Steam 403'd/429'd us this fetch
        steamInventoryService.blockedUntilMs('111') >> (System.currentTimeMillis() + 300_000L)
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then: "the 42 is NOT clobbered to 0 — a single 429 must not wipe the UI pool"
        user.steamInventorySize == 42

        and: "lastSyncedAt is NOT bumped on a blocked fetch — see next spec for the staleness-window bug this prevents"
        user.lastSyncedAt == 1234L
    }

    def "syncOne does NOT bump lastSyncedAt on a blocked fetch — rate-limited users must re-appear stale on the next sweep tick"() {
        given: """\
            A user whose previous successful sync was ~25h ago — already past
            the 24h STALE_AFTER_MS cutoff, so they're a stale-sweep candidate.
            We pick them up; Steam rate-limits us (blockedUntilMs returns a
            future timestamp). The bug we're guarding against: doSyncOne used
            to write `fresh.lastSyncedAt = now` unconditionally, including on
            blocked fetches. Effect: the very next findStaleForSync tick would
            NOT pick this user up again until 24h later, even though Steam
            typically blocks for only ~5 minutes. A single transient 429
            silently downgraded the user from one sync per 20m (intended
            rolling retry) to one sync per 24h."""
        long staleSince = System.currentTimeMillis() - (25L * 60L * 60L * 1000L)
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 42, lastSyncedAt: staleSince)
        steamInventoryService.fetchInventory('111') >> []
        steamInventoryService.blockedUntilMs('111') >> (System.currentTimeMillis() + 300_000L)
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then: """\
            lastSyncedAt MUST remain at its prior 25h-old value — that's how
            the next findStaleForSync tick (20 min later) keeps picking this
            user up to retry. Bumping to `now` would push them out of the
            cutoff window for the full 24h STALE_AFTER_MS, contrary to the
            doSyncOne comment ("until we get a real successful fetch")."""
        user.lastSyncedAt == staleSince
        user.steamInventorySize == 42
    }

    def "syncOne DOES bump lastSyncedAt on a successful (non-blocked) fetch"() {
        given: 'a real successful sync — non-blocked, non-empty inventory'
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 3, lastSyncedAt: 1L)
        steamInventoryService.fetchInventory('111') >> [[a: 1], [a: 2], [a: 3], [a: 4]]
        steamInventoryService.blockedUntilMs('111') >> null
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }
        long beforeCallMs = System.currentTimeMillis()

        when:
        service.syncOne(user)

        then: 'success path still bumps lastSyncedAt to "now"'
        user.lastSyncedAt >= beforeCallMs
    }

    def "syncOne does NOT notify on a blocked fetch even though now(0) differs from before"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 5)
        steamInventoryService.fetchInventory('111') >> []
        steamInventoryService.blockedUntilMs('111') >> (System.currentTimeMillis() + 300_000L)
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then: "a rate-limit-induced empty result is not 'new items' — no notification"
        0 * notificationService.push(*_)
    }

    def "syncOne writes a genuine zero when the inventory is really empty (not blocked)"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 7)
        steamInventoryService.fetchInventory('111') >> []
        steamInventoryService.blockedUntilMs('111') >> null
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then: "an unblocked empty fetch is a real 'you have 0 items' result"
        user.steamInventorySize == 0
    }

    // ── syncNow ───────────────────────────────────────────────────

    def "syncNow returns ok=true with counts on success"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        steamUserRepository.findById(10L) >>> [Optional.of(user), Optional.of(user), Optional.of(user)]
        steamInventoryService.fetchInventory('111') >> [[a: 1], [b: 2]]
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.syncNow(10L)

        then:
        result.ok == true
        result.inventorySize == 2
        result.lastSyncedAt != null
    }

    def "syncNow returns ok=false when the user id is unknown"() {
        given:
        steamUserRepository.findById(_) >> Optional.empty()

        when:
        def result = service.syncNow(999L)

        then:
        result.ok == false
        result.error == 'Unknown user'
    }

    def "syncNow wraps thrown exceptions into ok=false with a generic message (bug #61)"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamInventoryService.fetchInventory(_) >> { throw new RuntimeException('ORA-01000: something internal') }

        when:
        def result = service.syncNow(10L)

        then:
        result.ok == false
        // Must NOT leak the raw exception message to the client
        !result.error.contains('ORA-01000')
        result.error.contains('try again')
    }

    def "syncNow drops cached state before retrying so an explicit click always hits Steam"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamInventoryService.fetchInventory('111') >> []
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncNow(10L)

        then: "the user's positive + negative cache is cleared before the fetch"
        1 * steamInventoryService.clearCacheFor('111')
    }

    def "syncNow surfaces a structured rate_limited payload when Steam blocked us"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 12)
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamInventoryService.fetchInventory('111') >> []
        steamUserRepository.save(_) >> { args -> args[0] }
        // blockedUntilMs is consulted twice — once inside syncOne, once by
        // syncNow itself — and must report a future block both times.
        long until = System.currentTimeMillis() + 180_000L
        steamInventoryService.blockedUntilMs('111') >> until

        when:
        def result = service.syncNow(10L)

        then: "the caller gets a real reason, not a pretend success toast"
        result.ok == false
        result.reason == 'rate_limited'
        result.retryAt == until
        result.retryInSec >= 1
        // The previous good count is echoed back, not wiped to 0
        result.inventorySize == 12
        result.error.contains('rate-limiting')
    }

    def "syncNow rate_limited message pluralises the minute count correctly"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamInventoryService.fetchInventory('111') >> []
        steamUserRepository.save(_) >> { args -> args[0] }
        // ~30s out → rounds up to "1 minute" (singular).
        steamInventoryService.blockedUntilMs('111') >> (System.currentTimeMillis() + 30_000L)

        when:
        def result = service.syncNow(10L)

        then:
        result.error.contains('1 minute')
        !result.error.contains('1 minutes')
    }

    def "syncNow notification copy reports the count of newly-appeared items"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 1)
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamInventoryService.fetchInventory('111') >> [[a: 1], [a: 2], [a: 3], [a: 4]]
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncNow(10L)

        then: "3 new items (4 now − 1 before) routed to /sell"
        1 * notificationService.push(10L, 'STEAM_INVENTORY', _, { it.contains('3') }, _, '/sell')
    }

    // ── syncAllUsers (scheduled sweep) ────────────────────────────

    def "syncAllUsers is a no-op when no users are stale"() {
        given:
        steamUserRepository.findStaleForSync(_, _) >> []

        when:
        service.syncAllUsers()

        then: "an empty candidate set means no fetches and no saves"
        0 * steamInventoryService.fetchInventory(_)
        0 * steamUserRepository.save(_)
    }

    def "syncAllUsers walks every stale user in the batch"() {
        given:
        def users = [
            new SteamUser(id: 1L, steamId64: 'a'),
            new SteamUser(id: 2L, steamId64: 'b')
        ]
        steamUserRepository.findStaleForSync(_, _) >> users
        steamUserRepository.findById(_) >> { args -> Optional.of(users.find { it.id == args[0] }) }
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncAllUsers()

        then: "each user's inventory is fetched exactly once"
        1 * steamInventoryService.fetchInventory('a') >> []
        1 * steamInventoryService.fetchInventory('b') >> []
    }

    def "syncAllUsers isolates a per-user failure — one thrown user does not halt the sweep"() {
        given: "the middle user's fetch blows up"
        def users = [
            new SteamUser(id: 1L, steamId64: 'a'),
            new SteamUser(id: 2L, steamId64: 'b'),
            new SteamUser(id: 3L, steamId64: 'c')
        ]
        steamUserRepository.findStaleForSync(_, _) >> users
        steamUserRepository.findById(_) >> { args -> Optional.of(users.find { it.id == args[0] }) }
        steamUserRepository.save(_) >> { args -> args[0] }
        steamInventoryService.fetchInventory('b') >> { throw new RuntimeException('Steam 500') }

        when:
        service.syncAllUsers()

        then: "users a and c still get processed — the RuntimeException is caught per-user"
        noExceptionThrown()
        1 * steamInventoryService.fetchInventory('a') >> []
        1 * steamInventoryService.fetchInventory('c') >> []
    }

    def "syncAllUsers ABORTS the remaining sweep when the thread is interrupted (bug: return-in-each doesn't break)"() {
        given: "three stale users; processing the first one interrupts the worker thread"
        def users = [
            new SteamUser(id: 1L, steamId64: 'a'),
            new SteamUser(id: 2L, steamId64: 'b'),
            new SteamUser(id: 3L, steamId64: 'c')
        ]
        steamUserRepository.findStaleForSync(_, _) >> users
        steamUserRepository.findById(_) >> { args -> Optional.of(users.find { it.id == args[0] }) }
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncAllUsers()

        then: "the first user's fetch sets the interrupt flag; the Thread.sleep that follows throws InterruptedException, which must break the loop — users b and c are NEVER touched"
        1 * steamInventoryService.fetchInventory('a') >> { Thread.currentThread().interrupt(); [] }
        0 * steamInventoryService.fetchInventory('b')
        0 * steamInventoryService.fetchInventory('c')

        cleanup: "clear the interrupt flag so it can't leak into later specs"
        Thread.interrupted()
    }

    def "syncAllUsers leaves the interrupt flag set after an interrupted sweep"() {
        given:
        def users = [new SteamUser(id: 1L, steamId64: 'a')]
        steamUserRepository.findStaleForSync(_, _) >> users
        steamUserRepository.findById(_) >> { args -> Optional.of(users[0]) }
        steamUserRepository.save(_) >> { args -> args[0] }
        steamInventoryService.fetchInventory('a') >> { Thread.currentThread().interrupt(); [] }

        when:
        service.syncAllUsers()
        // Thread.interrupted() both READS and CLEARS — capture it once.
        def stillInterrupted = Thread.interrupted()

        then: "the catch re-asserts the flag so the scheduler thread pool sees the interrupt"
        stillInterrupted
    }

    // ── new-user no-spam baseline (priorRecorded == null gate) ────
    //
    // Brand-new accounts have steamInventorySize == null (the column
    // is nullable Integer; no prior sync has ever written a count).
    // Before the baseline gate, the first sync after signup would fire
    // a STEAM_INVENTORY toast saying "N new item(s) ready to list" for
    // every item the user has owned since long before they joined —
    // landing immediately after the WELCOME notification from
    // upsertUser and dramatically over-counting deltas (the "delta" is
    // really the user's entire pre-existing inventory).
    //
    // Fix: when priorRecorded == null, isBaseline == true and the
    // notification is suppressed. Only sweep #2 onwards can fire a
    // real delta notification.

    def "syncOne does NOT notify on the FIRST EVER sync — the baseline write is silent (no-spam-on-signup)"() {
        given: 'a brand-new user whose steamInventorySize has never been written (null)'
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: null)
        steamInventoryService.fetchInventory('111') >> [[a:1], [a:2], [a:3], [a:4], [a:5]]
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then: "the count IS persisted as the silent baseline — sweep #2 will compare against this"
        user.steamInventorySize == 5

        and: "but ZERO notifications fire — we never tell the user about items they've always owned"
        0 * notificationService.push(*_)
    }

    def "syncOne DOES notify on the SECOND sync after a baseline write — only the first is silent"() {
        given: "the user's prior count is a real recorded value (not null), inventory grew by 2"
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 5)
        steamInventoryService.fetchInventory('111') >> [[a:1], [a:2], [a:3], [a:4], [a:5], [a:6], [a:7]]
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then: 'baseline gate only fires on null — a recorded 5 → 7 is a genuine delta'
        1 * notificationService.push(10L, 'STEAM_INVENTORY', _, { it.contains('2') }, _, '/sell')
        user.steamInventorySize == 7
    }

    // ── per-user lock (lockFor) — serialises two concurrent syncs ─
    //
    // Two browser tabs hitting POST /api/steam/sync at the same instant
    // (or the scheduler tick reaching a user at the moment they click
    // Re-sync) used to race: both threads read before=0 from the same
    // tick-start snapshot, both saw now=4, both pushed STEAM_INVENTORY
    // → user got DUPLICATE "4 new item(s)" toasts for one real delta.
    //
    // Fix: lockFor(userId) gives each user id a JVM-local monitor; the
    // second concurrent syncOne blocks until the first commits, then
    // re-reads via findById and sees before=4 from the (now-committed)
    // first save — so its delta is 0 and no notification fires. This
    // spec runs two real threads on the same user id.

    def "two concurrent syncOne calls on the SAME user fire EXACTLY ONE notification, not two (per-user lock)"() {
        given: 'a user whose prior count is 1 and whose Steam inventory now holds 4 — one real delta of 3'
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 1)

        and: """\
            findById always returns the live shared `user` object — that is
            the production behaviour (one row per id) and is what makes the
            lock-after-load semantics observable here. Without sync, both
            threads call findById, both observe before=1 from the same row,
            both save now=4, both push.  With the lock, the second thread
            blocks until the first releases the monitor — then its
            findById sees the (now-mutated) shared `user` with size 4, so
            its delta is 0 and it suppresses the push."""
        steamUserRepository.findById(10L) >> Optional.of(user)

        and: 'save mutates the shared `user` synchronously so the second thread sees the committed size'
        steamUserRepository.save(_) >> { args ->
            def saved = args[0] as SteamUser
            user.steamInventorySize = saved.steamInventorySize
            user.lastSyncedAt = saved.lastSyncedAt
            saved
        }

        and: 'both threads get the same 4-item inventory — same "now" snapshot for both'
        steamInventoryService.fetchInventory('111') >> [[a:1], [a:2], [a:3], [a:4]]

        when: 'two web threads (e.g. two tabs) hit syncOne for the SAME user simultaneously'
        def t1 = Thread.start { service.syncOne(user) }
        def t2 = Thread.start { service.syncOne(user) }
        t1.join(5000L)
        t2.join(5000L)

        then: """\
            EXACTLY one push fires.  The second thread blocks on the per-user
            monitor, re-reads after the first commits (steamInventorySize is
            now 4), and now > before is false → no duplicate toast."""
        1 * notificationService.push(10L, 'STEAM_INVENTORY', _, _, _, '/sell')
    }

    // ── per-user lock vs. transaction boundary (DUPLICATE-PUSH RACE) ──
    //
    // The previous spec relies on save() mutating the shared in-memory
    // `user` object SYNCHRONOUSLY — i.e. the second thread's findById
    // immediately sees the first thread's write. That's NOT how JPA
    // works across two transactions: the first writer's UPDATE is only
    // visible to other transactions AFTER its tx commits.
    //
    // In production, syncNow() is the `@Transactional` proxy entry
    // point. Two concurrent /api/steam/sync POSTs each open their own
    // tx, then internal-call syncOne() — which acquires synchronized()
    // INSIDE the tx and releases it BEFORE the tx commits. So:
    //   Thread A: enter tx → acquire lock → read row → save (staged) →
    //             push notif → release lock → COMMIT (later)
    //   Thread B: enter tx → wait on lock → acquire lock after A's
    //             release but BEFORE A's commit → findById sees the
    //             pre-A row (size=1) → push DUPLICATE notif
    // The monitor scope is too narrow: it covers the read-modify-write
    // window but not the commit. Two pushes fire for one real delta.
    //
    // This spec simulates real tx isolation: findById serves from a
    // "committed snapshot" map, and save() stages into a per-thread
    // pending buffer that only flushes to the snapshot AFTER the
    // calling thread leaves syncOne(). With the buggy code (lock inside
    // tx) the second thread still sees pre-commit state → 2 pushes.
    // With the fix (lock outside tx) the second thread sees the
    // committed size=4 → 1 push.

    def "two concurrent syncOne calls fire ONE notification even when save visibility is delayed past the lock (real tx isolation)"() {
        given: 'one shared "DB snapshot" the threads read through findById; save() only flushes after the calling thread completes (simulating tx commit AFTER syncOne returns to the @Transactional proxy)'
        def committed = new java.util.concurrent.atomic.AtomicReference<SteamUser>(
            new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 1))
        // Per-thread pending save buffer; flushed to `committed` only when the
        // calling thread releases the lock AND finishes the tx (i.e. when the
        // outer wrapper exits — see the runner below).
        def pending = new java.util.concurrent.ConcurrentHashMap<Long, SteamUser>()

        steamUserRepository.findById(10L) >> {
            // Each call snapshots from the committed reference, mimicking
            // a fresh JPA session reading from the DB at SELECT time.
            def src = committed.get()
            def snap = new SteamUser(
                id: src.id, steamId64: src.steamId64,
                steamInventorySize: src.steamInventorySize,
                lastSyncedAt: src.lastSyncedAt)
            Optional.of(snap)
        }
        steamUserRepository.save(_) >> { args ->
            // Stage the write keyed by the calling thread — the outer runner
            // flushes it to `committed` only AFTER syncOne returns, mirroring
            // a tx commit happening AFTER the synchronized block releases.
            pending.put(Thread.currentThread().id, args[0] as SteamUser)
            args[0]
        }

        and: 'both threads see a 4-item inventory — same "now" snapshot for both'
        steamInventoryService.fetchInventory('111') >> [[a:1], [a:2], [a:3], [a:4]]

        and: 'a runner that mimics the @Transactional proxy: open tx → call syncOne → commit AFTER syncOne returns'
        def runOneAsTx = { SteamUser u ->
            try {
                service.syncOne(u)
            } finally {
                // Tx commit: flush this thread\'s staged save to the shared
                // committed snapshot. This happens AFTER syncOne returned
                // (i.e. AFTER the synchronized block was released), which is
                // the exact window the buggy code leaves open.
                def staged = pending.remove(Thread.currentThread().id)
                if (staged != null) committed.set(staged)
            }
        }

        when: 'two /api/steam/sync requests land at the same instant'
        def u1 = committed.get()
        def u2 = committed.get()
        def t1 = Thread.start { runOneAsTx(u1) }
        def t2 = Thread.start { runOneAsTx(u2) }
        t1.join(5000L)
        t2.join(5000L)

        then: """\
            Exactly ONE notification — the second thread must wait for the
            FIRST thread\'s commit (not just its lock release) before reading
            `before`, otherwise both observe before=1 and both push."""
        1 * notificationService.push(10L, 'STEAM_INVENTORY', _, _, _, '/sell')
    }
}
