package com.sboxmarket

import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.SeedService
import com.sboxmarket.service.SteamAuthService
import com.sboxmarket.service.SteamInventoryService
import spock.lang.Specification

/**
 * The demo sellers used to carry ids in the middle of Steam's allocated
 * range (76561199000000001 …): real people's accounts. A profile Sync on demo
 * user 1 pulled a stranger's real Steam name onto it. The ids now sit at the
 * top of the 32-bit account-id space, and no Steam profile or inventory
 * lookup is ever made for them (or for the old block, still present in demo
 * databases seeded before the move).
 */
class DemoSteamIdsStayOfflineSpec extends Specification {

    static final long BASE = 76561197960265728L
    static final List<String> SEED_IDS = (729..734).collect { '76561202255232' + it }
    static final List<String> LEGACY_IDS = (1..6).collect { '7656119900000000' + it }

    def "every demo id is a valid account id that Steam has not handed out"() {
        expect:
        SEED_IDS.every { id ->
            long accountId = Long.parseLong(id) - BASE
            // Positive, so a demo user can still save a trade URL locally;
            // inside 32 bits; and far above anything Steam has allocated.
            accountId > 4_000_000_000L && accountId <= 0xFFFFFFFFL
        }
    }

    def "demo ids, old and new, are recognised and real-looking ids are not"() {
        expect:
        SEED_IDS.every { SeedService.isSeedSteamId(it) }
        LEGACY_IDS.every { SeedService.isSeedSteamId(it) }
        !SeedService.isSeedSteamId('76561198012345678')
        !SeedService.isSeedSteamId(null)
        !SeedService.isSeedSteamId('')
    }

    def "a profile sync never asks Steam about a demo user"() {
        given:
        def service = new SteamAuthService(
            steamUserRepository: Mock(SteamUserRepository),
            walletRepository: Mock(WalletRepository),
            adminService: Mock(AdminService),
            steamApiKey: 'k',
            realm: 'http://localhost:8080/',
            returnUrl: 'http://localhost:8080/api/auth/steam/return')
        int calls = 0
        service.metaClass.fetchViaWebApi = { String id -> calls++; [displayName: 'Stranger'] }
        service.metaClass.fetchViaPublicXml = { String id -> calls++; [displayName: 'Stranger'] }

        expect:
        (SEED_IDS + LEGACY_IDS).every { service.fetchProfileWithRetry(it) == null }
        calls == 0

        and: "a real user still goes to Steam"
        service.fetchProfileWithRetry('76561198012345678').displayName == 'Stranger'
        calls == 1
    }

    def "a demo user's inventory is never read from Steam Community"() {
        given:
        def service = new SteamInventoryService()

        expect:
        service.inventoryRoot() == SteamInventoryService.STEAM_COMMUNITY
        (SEED_IDS + LEGACY_IDS).every { service.fetchInventory(it) == [] }
    }
}
