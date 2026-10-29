package com.sboxmarket

import com.sboxmarket.config.GlobalExceptionHandler
import com.sboxmarket.controller.CartController
import com.sboxmarket.controller.ListingController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ConflictException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.PurchaseService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import spock.lang.Specification

/**
 * A purchase or bid that loses an optimistic-lock race is rolled back, but
 * the row that moved is not always a sale. Seen on a live run with several
 * users acting at once:
 *   - buyer clicks Buy while the seller saves a new price: "someone else
 *     bought it first", yet the listing is still for sale at the new price;
 *   - one buyer buys two listings in two tabs at once (both debit the same
 *     versioned wallet): one is told it sold to someone else, still ACTIVE;
 *   - two bids on one auction at once: the loser is told the auction item
 *     was bought, while the auction is still live.
 */
class LostPurchaseRaceMessageSpec extends Specification {

    ListingService      listingService      = Mock()
    PurchaseService     purchaseService     = Mock()
    WalletRepository    walletRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    ListingRepository   listingRepository   = Mock()

    ListingController listings = new ListingController(listingService: listingService,
        purchaseService: purchaseService, walletRepository: walletRepository,
        steamUserRepository: steamUserRepository)
    CartController cart = new CartController(purchaseService: purchaseService,
        walletRepository: walletRepository, steamUserRepository: steamUserRepository,
        listingRepository: listingRepository)

    HttpServletRequest req = Mock()
    HttpSession ses = Mock()

    def setup() {
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 10L
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, username: 'steam_111', balance: 100G)
    }

    private static ObjectOptimisticLockingFailureException lost() {
        new ObjectOptimisticLockingFailureException("Row was updated", Listing)
    }

    def "buy that loses to a seller's price edit reports the new price, not a sale"() {
        given: 'the early read still sees $6; the edit to $9 commits before the debit flushes'
        listingService.findById(7L) >>> [new Listing(id: 7L, status: 'ACTIVE', price: 6G),
                                         new Listing(id: 7L, status: 'ACTIVE', price: 9G)]
        purchaseService.buy(500L, 10L, 7L, 6G) >> { throw lost() }

        when:
        listings.buy(7L, [expectedPrice: '6.00'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'PRICE_CHANGED'
        e.message.contains('9')
    }

    def "buy that loses to the buyer's own other purchase says the listing changed, not sold"() {
        given:
        listingService.findById(7L) >> new Listing(id: 7L, status: 'ACTIVE', price: 4G)
        purchaseService.buy(500L, 10L, 7L, 4G) >> { throw lost() }

        when:
        listings.buy(7L, [expectedPrice: '4.00'], req)

        then:
        def e = thrown(ConflictException)
        e.code == 'LISTING_CHANGED'
        !e.message.contains('bought')
    }

    def "buy that loses to a real sale still surfaces the lock failure (LISTING_NOT_AVAILABLE)"() {
        given:
        listingService.findById(7L) >>> [new Listing(id: 7L, status: 'ACTIVE', price: 4G),
                                         new Listing(id: 7L, status: 'SOLD', price: 4G)]
        purchaseService.buy(500L, 10L, 7L, 4G) >> { throw lost() }

        when:
        listings.buy(7L, [expectedPrice: '4.00'], req)

        then:
        thrown(ObjectOptimisticLockingFailureException)
    }

    def "cart row that loses to a price edit is PRICE_CHANGED with the new price"() {
        given:
        listingRepository.findById(7L) >>> [Optional.of(new Listing(id: 7L, status: 'ACTIVE', price: 6G)),
                                            Optional.of(new Listing(id: 7L, status: 'ACTIVE', price: 9G))]
        purchaseService.buy(500L, 10L, 7L, 6G) >> { throw lost() }

        when:
        def row = cart.checkout([listingIds: [7L], expectedPrices: ['7': '6.00']], req).body.results[0]

        then:
        row.code == 'PRICE_CHANGED'
        row.actual == 9G
    }

    def "cart row that loses to a sale stays LISTING_NOT_AVAILABLE"() {
        given:
        listingRepository.findById(7L) >>> [Optional.of(new Listing(id: 7L, status: 'ACTIVE', price: 6G)),
                                            Optional.of(new Listing(id: 7L, status: 'SOLD', price: 6G))]
        purchaseService.buy(500L, 10L, 7L, 6G) >> { throw lost() }

        when:
        def row = cart.checkout([listingIds: [7L], expectedPrices: ['7': '6.00']], req).body.results[0]

        then:
        row.code == 'LISTING_NOT_AVAILABLE'
    }

    def "a bid that loses a same-instant race says another bid landed, not that the item sold"() {
        given:
        def handler = new GlobalExceptionHandler(verboseErrors: false)

        when:
        def resp = handler.handleOptimisticLock(lost(), new MockHttpServletRequest('POST', '/api/bids'))

        then:
        resp.statusCode == HttpStatus.CONFLICT
        resp.body.code == 'BID_CONFLICT'
        !resp.body.message.contains('bought')
    }
}
