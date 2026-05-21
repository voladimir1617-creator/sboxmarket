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
        def user = new SteamUser(id: 10L, steamId64: '111', steamInventorySize: 42)
        steamInventoryService.fetchInventory('111') >> []
        // blockedUntilMs non-null == Steam 403'd/429'd us this fetch
        steamInventoryService.blockedUntilMs('111') >> (System.currentTimeMillis() + 300_000L)
        steamUserRepository.findById(10L) >> Optional.of(user)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.syncOne(user)

        then: "the 42 is NOT clobbered to 0 — a single 429 must not wipe the UI pool"
        user.steamInventorySize == 42
        user.lastSyncedAt != null
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
}
