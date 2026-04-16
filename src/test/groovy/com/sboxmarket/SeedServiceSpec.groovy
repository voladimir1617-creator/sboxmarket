package com.sboxmarket

import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.LoadoutSlotRepository
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.SboxApiService
import com.sboxmarket.service.SeedService
import spock.lang.Specification
import spock.lang.Subject

/**
 * First-boot bootstrap. Two invariants:
 *   1) Creates exactly one demo wallet when none exists, never two
 *   2) Kicks SCMM sync when the catalogue is empty, not otherwise
 */
class SeedServiceSpec extends Specification {

    ItemRepository         itemRepository         = Mock()
    ListingRepository      listingRepository      = Mock()
    PriceHistoryRepository priceHistoryRepository = Mock()
    WalletRepository       walletRepository       = Mock()
    LoadoutRepository      loadoutRepository      = Mock()
    LoadoutSlotRepository  loadoutSlotRepository  = Mock()
    SboxApiService         sboxApiService         = Mock()

    @Subject
    SeedService service = new SeedService(
        itemRepository        : itemRepository,
        listingRepository     : listingRepository,
        priceHistoryRepository: priceHistoryRepository,
        walletRepository      : walletRepository,
        loadoutRepository     : loadoutRepository,
        loadoutSlotRepository : loadoutSlotRepository,
        sboxApiService        : sboxApiService
    )

    def "seed creates demo wallet when the wallet table is empty"() {
        given:
        walletRepository.count() >> 0L
        itemRepository.count()   >> 100L   // catalogue already populated

        when:
        service.seed()

        then:
        1 * walletRepository.save({ Wallet w ->
            w.username == 'demo' && w.balance == new BigDecimal("250.00") && w.currency == 'USD'
        })
        0 * sboxApiService.syncFromScmm()
    }

    def "seed does NOT recreate the demo wallet on subsequent boots"() {
        given:
        walletRepository.count() >> 5L
        itemRepository.count()   >> 100L

        when:
        service.seed()

        then:
        0 * walletRepository.save(_)
    }

    def "seed kicks SCMM sync when the catalogue is empty"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count()   >> 0L
        sboxApiService.syncFromScmm() >> [created: 80, updated: 0, skipped: 0, totalRemote: 80]

        when:
        service.seed()

        then:
        1 * sboxApiService.syncFromScmm()
    }

    def "seed does NOT kick sync when the catalogue is populated"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count()   >> 50L

        when:
        service.seed()

        then:
        0 * sboxApiService.syncFromScmm()
    }

    def "seed swallows a failing SCMM sync so startup doesn't crash"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count()   >> 0L
        sboxApiService.syncFromScmm() >> { throw new RuntimeException('network down') }

        when:
        service.seed()

        then:
        noExceptionThrown()
    }
}
