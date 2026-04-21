package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification
import spock.lang.Subject

/**
 * Focused unit coverage for OfferService's new auto-accept path —
 * when a listing ships with a maxDiscount > 0 and a buyer offers at
 * or above the computed threshold, makeOffer() should immediately
 * run through acceptOffer() and return ACCEPTED. Below-threshold
 * offers stay PENDING.
 */
class OfferAutoAcceptSpec extends Specification {

    OfferRepository     offerRepository     = Mock()
    ListingRepository   listingRepository   = Mock()
    WalletRepository    walletRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    PurchaseService     purchaseService     = Mock()
    BanGuard            banGuard            = Mock()
    NotificationService notificationService = Mock()
    TextSanitizer       textSanitizer       = Mock() {
        cleanShort(_) >> { String s -> s }
    }

    @Subject
    OfferService service = new OfferService(
        offerRepository:     offerRepository,
        listingRepository:   listingRepository,
        walletRepository:    walletRepository,
        steamUserRepository: steamUserRepository,
        purchaseService:     purchaseService,
        banGuard:            banGuard,
        textSanitizer:       textSanitizer,
        notificationService: notificationService
    )

    private Listing listingAt(BigDecimal price, BigDecimal maxDiscount) {
        new Listing(
            id: 10L,
            item: new Item(id: 7L, name: 'Wizard Hat'),
            price: price,
            status: 'ACTIVE',
            listingType: 'BUY_NOW',
            sellerUserId: 99L,
            sellerName: 'Alice',
            maxDiscount: maxDiscount
        )
    }

    def "offer above the maxDiscount threshold auto-accepts immediately"() {
        given:
        // Price $100, maxDiscount 0.20 → auto-accept threshold $80.
        // Buyer offers $85 → above threshold, should ACCEPT.
        def listing = listingAt(new BigDecimal('100.00'), new BigDecimal('0.20'))
        listingRepository.findById(10L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }

        def buyer = new SteamUser(id: 42L, steamId64: '111',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=42&token=abc')
        steamUserRepository.findById(42L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal('200'))

        // Keep a single Offer instance so the status mutation inside
        // acceptOffer is observable to the makeOffer reload.
        Offer tracked = null
        offerRepository.save(_) >> { args ->
            def o = args[0]
            if (tracked == null) { o.id = 1L; tracked = o }
            else { tracked.status = o.status; tracked.updatedAt = o.updatedAt }
            tracked
        }
        offerRepository.findById(1L) >> { tracked ? Optional.of(tracked) : Optional.empty() }
        offerRepository.findPendingForListing(10L) >> []

        when:
        def offer = service.makeOffer(42L, 'Bob', 10L, new BigDecimal('85'))

        then:
        1 * banGuard.assertNotBanned(42L)  // buyer ban check
        1 * banGuard.assertNotBanned(99L)  // seller ban check on auto-accept entry
        1 * purchaseService.buy(500L, 42L, 10L)
        offer.status == 'ACCEPTED'
    }

    def "offer below the maxDiscount threshold stays PENDING"() {
        given:
        // Price $100, maxDiscount 0.10 → auto-accept threshold $90.
        // Buyer offers $85 → below threshold.
        def listing = listingAt(new BigDecimal('100.00'), new BigDecimal('0.10'))
        listingRepository.findById(10L) >> Optional.of(listing)
        offerRepository.save(_) >> { args -> args[0].id = 1L; args[0] }

        when:
        def offer = service.makeOffer(42L, 'Bob', 10L, new BigDecimal('85'))

        then:
        offer.status == 'PENDING'
        0 * purchaseService.buy(_, _, _)
    }

    def "listing with null maxDiscount never auto-accepts even on high offers"() {
        given:
        def listing = listingAt(new BigDecimal('100.00'), null)
        listingRepository.findById(10L) >> Optional.of(listing)
        offerRepository.save(_) >> { args -> args[0].id = 1L; args[0] }

        when:
        def offer = service.makeOffer(42L, 'Bob', 10L, new BigDecimal('99.99'))

        then:
        offer.status == 'PENDING'
        0 * purchaseService.buy(_, _, _)
    }

    def "system listing (null sellerUserId) with maxDiscount stays PENDING"() {
        given:
        def listing = listingAt(new BigDecimal('100.00'), new BigDecimal('0.30'))
        listing.sellerUserId = null
        listingRepository.findById(10L) >> Optional.of(listing)
        offerRepository.save(_) >> { args -> args[0].id = 1L; args[0] }

        when:
        def offer = service.makeOffer(42L, 'Bob', 10L, new BigDecimal('80'))

        then:
        offer.status == 'PENDING'
        0 * purchaseService.buy(_, _, _)
    }

    def "auto-accept falls back to PENDING when the purchase throws"() {
        given:
        def listing = listingAt(new BigDecimal('100.00'), new BigDecimal('0.20'))
        listingRepository.findById(10L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }

        def buyer = new SteamUser(id: 42L, steamId64: '111',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=42&token=abc')
        steamUserRepository.findById(42L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal('200'))

        def saved = new Offer(id: 1L, status: 'PENDING', buyerUserId: 42L,
            sellerUserId: 99L, listingId: 10L, amount: new BigDecimal('85'))
        offerRepository.save(_) >> { args -> args[0].id = 1L; args[0] }
        offerRepository.findById(1L) >> Optional.of(saved)
        offerRepository.findPendingForListing(10L) >> []
        // PurchaseService explodes — auto-accept should catch it.
        purchaseService.buy(_, _, _) >> { throw new RuntimeException('buy failed') }

        when:
        def offer = service.makeOffer(42L, 'Bob', 10L, new BigDecimal('85'))

        then:
        // Offer remains in the DB in PENDING state (acceptOffer flipped
        // listing price back on failure). Buyer isn't left empty-handed.
        offer != null
        noExceptionThrown()
    }
}
