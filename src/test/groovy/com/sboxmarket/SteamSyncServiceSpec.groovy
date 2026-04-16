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
        1 * notificationService.push(10L, 'STEAM_INVENTORY', _, _, _)
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
}
