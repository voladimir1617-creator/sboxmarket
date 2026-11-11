package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.SellService
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * With the Steam bot on, SellService.relist refused every Platform Inventory
 * relist with RELIST_NEEDS_STEAM_DEPOSIT, so items the platform itself holds
 * (house purchases, the seller's own non-Steam listings handed back by a
 * cancel) could never be sold again: they are not in the seller's Steam tab.
 * Owner's call (2026-10-29): those relist without a bot deposit. Anything that
 * came from, or went back to, the seller's Steam inventory still needs it.
 */
class RelistPlatformHeldSpec extends Specification {

    ListingRepository  listingRepository  = Mock()
    TradeRepository    tradeRepository    = Mock()
    BuyOrderService    buyOrderService    = Mock()
    BanGuard           banGuard           = Mock()
    SteamEscrowService steamEscrowService = Mock() { escrowEnabled >> true }
    TextSanitizer      textSanitizer      = Mock() {
        cleanShort(_) >> { String s -> s }
        clean(_, _) >> { args -> args[0] as String }
    }

    @Subject
    SellService service = new SellService(
        listingRepository : listingRepository,
        tradeRepository   : tradeRepository,
        buyOrderService   : buyOrderService,
        banGuard          : banGuard,
        textSanitizer     : textSanitizer,
        steamEscrowService: steamEscrowService
    )

    private static Listing owned(Long originalSeller, String assetId) {
        new Listing(id: 50L, item: new Item(id: 1L, name: 'Wizard Hat'), price: new BigDecimal('40'),
            buyerUserId: 10L, sellerUserId: originalSeller, status: 'SOLD', assetId: assetId,
            rarityScore: new BigDecimal('0.5'))
    }

    @Unroll
    def "bot on: a platform-held item (#kind) relists as a live listing with no deposit"() {
        given:
        def source = owned(originalSeller, null)
        listingRepository.findById(50L) >> Optional.of(source)
        steamEscrowService.hasCustodyRow(50L) >> false
        listingRepository.save(_) >> { Listing l -> if (l.id == null) l.id = 77L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        fresh.status == 'ACTIVE'
        fresh.sellerUserId == 10L
        fresh.price == new BigDecimal('80.00')
        source.status == 'RELISTED'
        0 * steamEscrowService.requestDepositForListing(_, _, _)

        where:
        kind                                    | originalSeller
        'house purchase'                        | null
        "seller's own cancelled non-Steam row"  | 10L
    }

    @Unroll
    def "bot on: #kind still needs the Steam deposit"() {
        given:
        def source = owned(originalSeller, assetId)
        listingRepository.findById(50L) >> Optional.of(source)
        steamEscrowService.hasCustodyRow(50L) >> custody

        expect:
        service.relistNeedsSteamDeposit(10L, 50L)

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'RELIST_NEEDS_STEAM_DEPOSIT'
        source.status == 'SOLD'
        0 * listingRepository.save(_)

        where:
        kind                                              | originalSeller | assetId     | custody
        'an item listed from Steam (has an asset id)'     | 10L            | '111222333' | false
        'an item that went through bot custody'           | 10L            | null        | true
        'an item bought from another player via Steam'    | 99L            | null        | false
    }

    def "bot on: a platform-held row is reported as relistable from Platform Inventory"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned(null, null))
        steamEscrowService.hasCustodyRow(50L) >> false

        expect:
        !service.relistNeedsSteamDeposit(10L, 50L)
    }

    def "bot on: someone else's row is never exempt"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned(null, null))
        steamEscrowService.hasCustodyRow(50L) >> false

        when:
        service.relist(11L, 'Mallory', 50L, new BigDecimal('80'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'RELIST_NEEDS_STEAM_DEPOSIT'
    }
}
