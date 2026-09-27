package com.sboxmarket

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression coverage for the BUYER-ACCEPTS-SELLER-COUNTER + seller-banned
 * hole.
 *
 * Scenario:
 *   1. Buyer makes a $40 offer on the seller's $50 listing.
 *   2. Seller writes a $45 counter (parent → COUNTERED, counter → PENDING,
 *      author='SELLER').
 *   3. Staff bans the seller (fraud, payout fraud, account compromise — any
 *      reason that triggers banGuard.assertNotBanned to throw).
 *   4. Buyer accepts the seller counter via the `buyerAcceptingCounter`
 *      path in acceptOffer.
 *
 * Pre-fix bug:
 *   The buyer-ban gate at the top of acceptOffer only checks
 *   `banGuard.isBanned(offer.buyerUserId)` — the seller's status is never
 *   inspected. PurchaseService.buy also only gates on the buyer's ban
 *   (line 106). The acceptance therefore goes through, debits the buyer's
 *   wallet, credits the banned seller's wallet, and opens a Trade with a
 *   banned seller as counterparty — exactly the state the makeOffer auto-
 *   accept path was guarded against at line 336
 *   (`banGuard.assertNotBanned(listing.sellerUserId)`), now reachable
 *   instead through the manual buyer-accept-counter route.
 *
 * Fix:
 *   Mirror the buyer-ban early-exit branch in acceptOffer for the
 *   buyer-accept-counter path: when the seller is banned, flip the counter
 *   to EXPIRED, close the buyer's COUNTERED original, push an
 *   OFFER_REJECTED notification to the buyer, and throw a
 *   ForbiddenException. The existing
 *   `noRollbackFor = [..., ForbiddenException]` keeps the EXPIRED save
 *   committed so the buyer's outgoing queue actually drains.
 */
class OfferServiceBannedSellerCounterSpec extends Specification {

    OfferRepository     offerRepository     = Mock()
    ListingRepository   listingRepository   = Mock()
    WalletRepository    walletRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    PurchaseService     purchaseService     = Mock()
    BanGuard            banGuard            = Mock()
    TextSanitizer       textSanitizer       = Mock() {
        cleanShort(_) >> { String s -> s }
        clean(_, _) >> { String s, int n -> s }
    }
    NotificationService notificationService = Mock()

    @Subject
    OfferService service = new OfferService(
        offerRepository     : offerRepository,
        listingRepository   : listingRepository,
        walletRepository    : walletRepository,
        steamUserRepository : steamUserRepository,
        purchaseService     : purchaseService,
        banGuard            : banGuard,
        textSanitizer       : textSanitizer,
        notificationService : notificationService
    )

    private Listing activeListing(Map args = [:]) {
        def item = new Item(id: 1L, name: 'Wizard Hat', imageUrl: 'https://example.com/x.png')
        new Listing(
            id:           args.id ?: 100L,
            item:         item,
            price:        args.price ?: new BigDecimal("50.00"),
            sellerUserId: args.seller ?: 99L,
            status:       args.status ?: 'ACTIVE'
        )
    }

    def "buyer accepting a SELLER counter is rejected when the seller is banned mid-negotiation"() {
        given:
        def listing = activeListing(price: new BigDecimal("50"))
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("500"))
        // The buyer's original offer parked in COUNTERED while the seller's
        // counter sits PENDING (the seller is between rounds of bargaining
        // when the ban lands).
        def original = new Offer(
            id: 1L, listingId: 100L, buyerUserId: 10L, sellerUserId: 99L,
            amount: new BigDecimal("40"), askingPrice: new BigDecimal("50"),
            status: 'COUNTERED', author: 'USER')
        def counter = new Offer(
            id: 5L, listingId: 100L, buyerUserId: 10L, sellerUserId: 99L,
            amount: new BigDecimal("45"), askingPrice: new BigDecimal("50"),
            status: 'PENDING', author: 'SELLER', parentOfferId: 1L)
        offerRepository.findById(5L) >> Optional.of(counter)
        offerRepository.findById(1L) >> Optional.of(original)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }
        // Buyer is fine; seller has been banned since writing the counter.
        banGuard.isBanned(10L) >> false
        banGuard.isBanned(99L) >> true

        when: 'the buyer (10L) tries to accept the banned seller (99L) counter'
        service.acceptOffer(10L, 5L)

        then: 'the purchase never runs — no funds move to the banned seller'
        0 * purchaseService.buy(*_)

        and: 'a ForbiddenException surfaces so the buyer sees a clean error'
        thrown(ForbiddenException)

        and: 'the counter is flipped to EXPIRED so it leaves the buyer outgoing queue'
        counter.status == 'EXPIRED'

        and: "the buyer's COUNTERED original is closed so the dup-guard frees up"
        original.status == 'CLOSED'

        and: 'the buyer is told why the offer could not close'
        1 * notificationService.push(10L, 'OFFER_REJECTED', _, _, 5L, '/offers')
    }

    def "buyer accepting a SELLER counter still works when the seller is NOT banned (regression guard)"() {
        // Sanity check that the new gate doesn't break the happy path the
        // existing acceptOffer-buyer-accepts-counter test already covers.
        // Belt-and-braces — we don't want the seller-ban check to short-
        // circuit on a healthy seller account.
        given:
        def listing = activeListing(price: new BigDecimal("50"))
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("500"))
        def counter = new Offer(
            id: 5L, listingId: 100L, buyerUserId: 10L, sellerUserId: 99L,
            amount: new BigDecimal("45"), askingPrice: new BigDecimal("50"),
            status: 'PENDING', author: 'SELLER')
        offerRepository.findById(5L) >> Optional.of(counter)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        offerRepository.findPendingForListing(100L) >> []
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }
        banGuard.isBanned(10L) >> false
        banGuard.isBanned(99L) >> false

        when: 'the buyer accepts the unbanned seller counter'
        def result = service.acceptOffer(10L, 5L)

        then:
        1 * purchaseService.buy(500L, 10L, 100L)
        result.accepted == true
        counter.status == 'ACCEPTED'
    }
}
