package com.sboxmarket

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression pin: the BuyOrder auto-fill matcher must NOT route a sale
 * to a banned SELLER (wave 141 parity with
 * {@link com.sboxmarket.service.OfferService#tryAutoAccept}).
 *
 * Pre-fix bug: PurchaseService.buy gates only the BUYER's ban state
 * via `banGuard.assertNotBanned(buyerUserId)`. It does NOT check the
 * seller. Both BuyOrderService.tryMatch (fires on listing creation /
 * relist) and BuyOrderService.tryFillFromExisting (fires on
 * create / cap-raise) iterate candidates and call purchaseService.buy
 * with no seller-ban guard. A seller who is banned AFTER posting a
 * listing — but BEFORE the ListingService sweeper takes their
 * listings down — can have their inventory auto-purchased through a
 * matching buy order:
 *
 *   - buyer's wallet drains for the listing price
 *   - listing flips SOLD with the buyer as recipient
 *   - escrow Trade row is opened with the banned seller as the
 *     payout target — money queued to a banned account
 *
 * This violates the wave 141 invariant ("the matching engine must
 * never make a new payout to a banned counterparty") that was already
 * enforced for OfferService.tryAutoAccept. Fix is symmetric:
 *
 *   - tryMatch: short-circuit if the listing's seller is banned (no
 *     candidate scan, no save).
 *   - tryFillFromExisting: skip each candidate listing whose seller
 *     is banned, walking to the next legitimate seller's listing at
 *     the same price tier.
 *
 * System listings (sellerUserId == null) bypass both checks — there
 * is no counterparty to ban.
 */
class BuyOrderServiceBannedSellerSpec extends Specification {

    BuyOrderRepository  buyOrderRepository  = Mock()
    ItemRepository      itemRepository      = Mock()
    ListingRepository   listingRepository   = Mock()
    WalletRepository    walletRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()
    TextSanitizer       textSanitizer       = Mock() {
        cleanShort(_) >> { String s -> s }
    }
    PurchaseService     purchaseService     = Mock()
    BanGuard            banGuard            = Mock()

    @Subject
    BuyOrderService service = new BuyOrderService(
        buyOrderRepository:  buyOrderRepository,
        itemRepository:      itemRepository,
        listingRepository:   listingRepository,
        walletRepository:    walletRepository,
        steamUserRepository: steamUserRepository,
        notificationService: notificationService,
        textSanitizer:       textSanitizer,
        purchaseService:     purchaseService,
        banGuard:            banGuard
    )

    private Listing listingFor(Map args = [:]) {
        def item = new Item(
            id:       args.itemId ?: 1L,
            name:     args.itemName ?: 'Wizard Hat',
            category: args.category ?: 'Hats',
            rarity:   args.rarity ?: 'Limited'
        )
        new Listing(
            id:           args.id ?: 100L,
            item:         item,
            price:        args.price ?: new BigDecimal('50.00'),
            sellerUserId: args.seller ?: 99L,
            status:       args.status ?: 'ACTIVE',
            listingType:  args.type ?: 'BUY_NOW',
            hidden:       args.hidden ?: false
        )
    }

    def "tryMatch refuses to route a sale to a banned seller (wave 141 parity)"() {
        given: "a listing whose seller has been banned AFTER the listing landed"
        def listing = listingFor(id: 100L, seller: 99L, price: new BigDecimal('50'))
        banGuard.isBanned(99L) >> true

        when:
        service.tryMatch(listing)

        then: "the candidate scan never runs — no buy order is fired against the banned seller"
        0 * buyOrderRepository.findMatching(*_)
        0 * purchaseService.buy(*_)
        0 * buyOrderRepository.save(_)
    }

    def "tryMatch still runs normally when the seller is not banned"() {
        given: "a clean seller — the existing match path is unaffected"
        def listing = listingFor(id: 100L, seller: 99L, price: new BigDecimal('50'))
        def order   = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal('60'), itemId: 1L)
        banGuard.isBanned(99L) >> false
        buyOrderRepository.findMatching(_, _, _, _, _) >> [order]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111', banned: false))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal('100.00'))

        when:
        service.tryMatch(listing)

        then: "the fill proceeds for the legitimate seller"
        1 * purchaseService.buy(500L, 10L, 100L)
        1 * buyOrderRepository.save({ BuyOrder o -> o.id == 1L && o.status == 'FILLED' })
    }

    def "tryMatch still runs for system listings (sellerUserId null) — no counterparty to ban"() {
        given: "a system listing has no seller — the ban gate must not block it"
        def listing = listingFor(id: 100L, price: new BigDecimal('50'))
        listing.sellerUserId = null
        def order = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                  maxPrice: new BigDecimal('60'), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _, _) >> [order]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111', banned: false))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal('100.00'))

        when:
        service.tryMatch(listing)

        then: "banGuard is never consulted for a null seller id"
        0 * banGuard.isBanned(null)
        and: "the system-listing fill still runs"
        1 * purchaseService.buy(500L, 10L, 100L)
    }

    def "tryFillFromExisting skips a candidate listing whose seller is banned, walking to a clean seller's listing"() {
        given: "two matching listings — the cheaper one belongs to a banned seller"
        def bannedSellerListing = listingFor(id: 100L, seller: 99L, price: new BigDecimal('40'))
        def cleanSellerListing  = listingFor(id: 101L, seller: 88L, price: new BigDecimal('45'))
        def order = new BuyOrder(id: 7L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                  maxPrice: new BigDecimal('50'), itemId: 1L)
        listingRepository.findMatchingForBuyOrder(_, _, _, _, _) >> [bannedSellerListing, cleanSellerListing]
        banGuard.isBanned(99L) >> true
        banGuard.isBanned(88L) >> false
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111', banned: false))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal('100.00'))

        when:
        service.tryFillFromExisting(order)

        then: "the banned seller's listing is skipped — never bought"
        0 * purchaseService.buy(_, _, 100L)

        and: "the clean seller's listing is filled instead"
        1 * purchaseService.buy(500L, 10L, 101L)
        1 * buyOrderRepository.save({ BuyOrder o -> o.id == 7L && o.status == 'FILLED' })
    }
}
