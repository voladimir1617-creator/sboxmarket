package com.sboxmarket

import com.sboxmarket.controller.CartController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.CartService
import com.sboxmarket.service.PurchaseService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.orm.ObjectOptimisticLockingFailureException
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the bulk checkout endpoint + the server-side cart
 * persistence endpoints (V31). The important invariants:
 *
 *   - Partial success is allowed: row 2 failing does not roll back row 1.
 *   - Error rows carry a stable machine-readable `code`, never the raw
 *     exception message. Leaking `e.message` from an unexpected
 *     RuntimeException was bug #28 in the audit log — it could expose
 *     stack-frame detail or SQL fragments to the client.
 *   - A listing sold mid-checkout (or duplicated in the payload) fails
 *     its row cleanly — the buyer is never debited twice.
 *   - Successful rows are scrubbed from the server-side cart; failed
 *     rows stay so the user can retry.
 *   - Every cart-persistence endpoint degrades gracefully when the
 *     optional CartService bean is absent.
 */
class CartControllerSpec extends Specification {

    PurchaseService      purchaseService      = Mock()
    WalletRepository     walletRepository     = Mock()
    SteamUserRepository  steamUserRepository  = Mock()
    com.sboxmarket.repository.ListingRepository listingRepository = Mock()

    @Subject
    CartController controller = new CartController(
        purchaseService    : purchaseService,
        walletRepository   : walletRepository,
        steamUserRepository: steamUserRepository,
        listingRepository  : listingRepository
    )

    private HttpServletRequest reqFor(Long uid) {
        def session = Mock(HttpSession)
        session.getAttribute('steamUserId') >> uid
        def req = Mock(HttpServletRequest)
        req.session >> session
        req
    }

    private Wallet wallet(Long id = 500L) {
        new Wallet(id: id, username: 'steam_111', balance: new BigDecimal("500.00"), currency: 'USD')
    }

    private SteamUser user(Long id = 10L) {
        new SteamUser(id: id, steamId64: '111', displayName: 'Alice')
    }

    /** Controller wired WITH a mocked CartService — needed for the
     *  post-checkout scrub assertion and the persistence endpoints. */
    private CartController controllerWithCart(CartService cs) {
        new CartController(
            purchaseService    : purchaseService,
            walletRepository   : walletRepository,
            steamUserRepository: steamUserRepository,
            listingRepository  : listingRepository,
            cartService        : cs
        )
    }

    // ── checkout: input validation ───────────────────────────────────

    def "rejects empty cart with EMPTY_CART"() {
        given:
        def req = reqFor(10L)

        when:
        controller.checkout([listingIds: []], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'EMPTY_CART'
    }

    def "rejects a missing listingIds key with EMPTY_CART"() {
        given:
        def req = reqFor(10L)

        when:
        controller.checkout([:], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'EMPTY_CART'
    }

    def "rejects a non-list listingIds value with EMPTY_CART"() {
        given:
        def req = reqFor(10L)

        when:
        controller.checkout([listingIds: 'not-a-list'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'EMPTY_CART'
    }

    def "rejects cart larger than 50 items"() {
        given:
        def req = reqFor(10L)

        when:
        controller.checkout([listingIds: (1..51).toList()], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'CART_TOO_LARGE'
    }

    def "allows a cart of exactly 50 items"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        // Every row succeeds — we only care that 50 is not rejected.
        purchaseService.buy(500L, 10L, _ as Long) >> {
            [newBalance: new BigDecimal('1'), listing: new com.sboxmarket.model.Listing(price: new BigDecimal('1'))]
        }

        when:
        def resp = controller.checkout([listingIds: (1L..50L).toList()], req)

        then:
        resp.body.total == 50
        resp.body.successful == 50
    }

    def "rejects a cart containing a null listing id with INVALID_CART"() {
        given:
        def req = reqFor(10L)

        when:
        controller.checkout([listingIds: [1L, null, 3L]], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_CART'
    }

    def "rejects a cart containing a non-numeric listing id with INVALID_CART"() {
        given:
        def req = reqFor(10L)

        when:
        controller.checkout([listingIds: ['abc']], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_CART'
    }

    def "coerces numeric-typed listing ids (Integer / String-digit) without error"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        purchaseService.buy(500L, 10L, 7L) >> [
            newBalance: new BigDecimal('490'),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal('10'))
        ]

        when:
        // Integer 7 (JSON numbers deserialize to Integer when small) must
        // coerce to the Long 7 the buy() call expects.
        def resp = controller.checkout([listingIds: [7 as Integer]], req)

        then:
        resp.body.successful == 1
    }

    def "rejects anonymous callers"() {
        given:
        def req = reqFor(null)

        when:
        controller.checkout([listingIds: [1L]], req)

        then:
        thrown(UnauthorizedException)
    }

    def "rejects checkout when the user id maps to no SteamUser row"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.empty()

        when:
        controller.checkout([listingIds: [1L]], req)

        then:
        thrown(UnauthorizedException)
    }

    // ── checkout: wallet handling ────────────────────────────────────

    def "auto-creates a zero-balance wallet when the buyer has none yet"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> null   // no wallet yet
        def created = wallet(777L)

        when:
        def resp = controller.checkout([listingIds: [1L]], req)

        then:
        // A fresh zero-balance wallet is persisted, then used for the buy.
        1 * walletRepository.save({ it.username == 'steam_111' && it.balance == BigDecimal.ZERO }) >> created
        1 * purchaseService.buy(777L, 10L, 1L) >> [
            newBalance: BigDecimal.ZERO,
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal('0'))
        ]
        resp.body.total == 1
    }

    // ── checkout: result roll-up ─────────────────────────────────────

    def "rolls up success / fail counts and totalSpent"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        purchaseService.buy(500L, 10L, 1L) >> [
            newBalance: new BigDecimal("490"),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal("10"))
        ]
        purchaseService.buy(500L, 10L, 2L) >> { throw new InsufficientBalanceException(new BigDecimal("50"), new BigDecimal("490")) }
        purchaseService.buy(500L, 10L, 3L) >> [
            newBalance: new BigDecimal("485"),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal("5"))
        ]

        when:
        def resp = controller.checkout([listingIds: [1L, 2L, 3L]], req)
        def body = resp.body

        then:
        body.total == 3
        body.successful == 2
        body.failed == 1
        body.totalSpent == new BigDecimal("15.00")
        body.results[0].status == 'OK'
        body.results[1].status == 'FAILED'
        body.results[1].code == 'INSUFFICIENT_BALANCE'
        // Batch 964 — per-row details surface the same required/available/
        // shortfall the top-level ErrorResponse carries, so the frontend
        // can sum exact top-up gaps instead of re-deriving from cart
        // totals + wallet balance (which can drift mid-checkout).
        body.results[1].details.required  == new BigDecimal('50')
        body.results[1].details.available == new BigDecimal('490')
        body.results[1].details.shortfall == new BigDecimal('-440')
        body.results[2].status == 'OK'
    }

    def "totalSpent is always scaled to 2 dp even when prices have none"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        purchaseService.buy(500L, 10L, 1L) >> [
            newBalance: new BigDecimal('490'),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal('7'))
        ]

        when:
        def resp = controller.checkout([listingIds: [1L]], req)

        then:
        resp.body.totalSpent == new BigDecimal('7.00')
        resp.body.totalSpent.scale() == 2
    }

    def "a buy result with a null listing price does not break totalSpent"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        // Defensive: buy() returns OK but no listing/price attached.
        purchaseService.buy(500L, 10L, 1L) >> [newBalance: new BigDecimal('490'), listing: null]

        when:
        def resp = controller.checkout([listingIds: [1L]], req)

        then:
        resp.body.successful == 1
        resp.body.totalSpent == new BigDecimal('0.00')
    }

    def "maps typed exceptions to stable error codes"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        purchaseService.buy(500L, 10L, 1L) >> { throw new ListingNotAvailableException(1L) }
        purchaseService.buy(500L, 10L, 2L) >> { throw new ObjectOptimisticLockingFailureException(com.sboxmarket.model.Listing, 2L) }
        purchaseService.buy(500L, 10L, 3L) >> { throw new NotFoundException("Listing", 3L) }

        when:
        def resp = controller.checkout([listingIds: [1L, 2L, 3L]], req)
        def body = resp.body

        then:
        body.results[0].code == 'LISTING_NOT_AVAILABLE'
        body.results[1].code == 'LISTING_NOT_AVAILABLE'
        body.results[2].code == 'NOT_FOUND'
    }

    def "maps a ForbiddenException row to FORBIDDEN and keeps its message"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        purchaseService.buy(500L, 10L, 1L) >> { throw new ForbiddenException('You are banned') }

        when:
        def resp = controller.checkout([listingIds: [1L]], req)

        then:
        resp.body.results[0].status == 'FAILED'
        resp.body.results[0].code == 'FORBIDDEN'
        resp.body.results[0].error == 'You are banned'
    }

    def "a BadRequestException row carries through its own code"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        // e.g. buying your own listing, or an auction via the BUY_NOW path.
        purchaseService.buy(500L, 10L, 1L) >> { throw new BadRequestException('OWN_LISTING', "You can't buy your own listing") }

        when:
        def resp = controller.checkout([listingIds: [1L]], req)

        then:
        resp.body.results[0].status == 'FAILED'
        resp.body.results[0].code == 'OWN_LISTING'
    }

    // ── checkout: sold-mid-checkout / duplicate payload ──────────────

    def "a listing sold between rows fails that row cleanly, later rows still proceed"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        // Row 1 sells, row 2 was bought by someone else a moment ago,
        // row 3 still available.
        purchaseService.buy(500L, 10L, 1L) >> [
            newBalance: new BigDecimal('490'),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal('10'))
        ]
        purchaseService.buy(500L, 10L, 2L) >> { throw new ListingNotAvailableException(2L) }
        purchaseService.buy(500L, 10L, 3L) >> [
            newBalance: new BigDecimal('485'),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal('5'))
        ]

        when:
        def resp = controller.checkout([listingIds: [1L, 2L, 3L]], req)
        def body = resp.body

        then:
        body.successful == 2
        body.failed == 1
        body.results[1].code == 'LISTING_NOT_AVAILABLE'
    }

    def "a duplicate listing id in the payload never double-charges — the second hit fails"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        // First buy of listing 1 succeeds; the SECOND buy of the same id
        // sees it already SOLD and throws — so only one debit lands. The
        // single stateful closure (attached to the `then:` interaction so
        // its cardinality and response live together) returns OK once
        // then throws on every subsequent call.
        def soldAlready = false

        when:
        def resp = controller.checkout([listingIds: [1L, 1L]], req)
        def body = resp.body

        then:
        // buy() called exactly twice — once per payload element.
        2 * purchaseService.buy(500L, 10L, 1L) >> {
            if (soldAlready) throw new ListingNotAvailableException(1L)
            soldAlready = true
            [newBalance: new BigDecimal('490'),
             listing:    new com.sboxmarket.model.Listing(price: new BigDecimal('10'))]
        }
        body.total == 2
        body.successful == 1
        body.failed == 1
        body.totalSpent == new BigDecimal('10.00')   // charged once, not twice
        body.results[0].status == 'OK'
        body.results[1].status == 'FAILED'
        body.results[1].code == 'LISTING_NOT_AVAILABLE'
    }

    // ── checkout: price-match guard (batch 321) ──────────────────────

    def "rejects rows with PRICE_CHANGED when expectedPrice differs from server price (batch 321)"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        // Listing 1's server price is $12 but client passed $10 — seller
        // raised it between cart-confirm and Confirm-click. Must reject
        // without debiting the wallet.
        def staleListing = new com.sboxmarket.model.Listing(id: 1L, price: new BigDecimal("12.00"))
        listingRepository.findById(1L) >> Optional.of(staleListing)

        when:
        def resp = controller.checkout([
            listingIds: [1L],
            expectedPrices: ['1': '10.00']
        ], req)
        def body = resp.body

        then:
        // Server rejected the row BEFORE purchaseService.buy was called.
        0 * purchaseService.buy(_, _, _)
        body.results[0].code == 'PRICE_CHANGED'
        body.results[0].expected == new BigDecimal("10.00")
        body.results[0].actual == new BigDecimal("12.00")
        body.successful == 0
        body.failed == 1
    }

    def "expectedPrice that matches the server price lets the row go through"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        def listing = new com.sboxmarket.model.Listing(id: 1L, price: new BigDecimal("10.00"))
        listingRepository.findById(1L) >> Optional.of(listing)

        when:
        def resp = controller.checkout([
            listingIds: [1L],
            expectedPrices: ['1': '10.00']
        ], req)

        then:
        // Interaction + return-value combined so the mock actually produces
        // a result when the buy runs.
        1 * purchaseService.buy(500L, 10L, 1L) >> [
            newBalance: new BigDecimal("490"),
            listing:    listing
        ]
        resp.body.successful == 1
    }

    def "expectedPrice comparison ignores scale — '10' matches a 10.00 server price"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        def listing = new com.sboxmarket.model.Listing(id: 1L, price: new BigDecimal('10.00'))
        listingRepository.findById(1L) >> Optional.of(listing)

        when:
        // Client snapshot "10" vs server "10.00" — BigDecimal.compareTo
        // treats these as equal, so the row must NOT be PRICE_CHANGED.
        def resp = controller.checkout([
            listingIds: [1L],
            expectedPrices: ['1': '10']
        ], req)

        then:
        1 * purchaseService.buy(500L, 10L, 1L) >> [newBalance: new BigDecimal('490'), listing: listing]
        resp.body.results[0].status == 'OK'
    }

    def "expectedPrice for a listing that no longer exists falls through to buy (NOT_FOUND)"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        // Price-check probe finds nothing — the guard is skipped and the
        // service-layer NotFound surfaces from buy() instead.
        listingRepository.findById(1L) >> Optional.empty()
        purchaseService.buy(500L, 10L, 1L) >> { throw new NotFoundException('Listing', 1L) }

        when:
        def resp = controller.checkout([
            listingIds: [1L],
            expectedPrices: ['1': '10.00']
        ], req)

        then:
        resp.body.results[0].code == 'NOT_FOUND'
    }

    def "rows WITHOUT an expectedPrice entry skip the price-match guard (back-compat)"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()

        when:
        // Old client calling WITHOUT expectedPrices — still works.
        def resp = controller.checkout([listingIds: [1L]], req)

        then:
        // No findById for price-check — purchase runs directly.
        0 * listingRepository.findById(_)
        1 * purchaseService.buy(500L, 10L, 1L) >> [
            newBalance: new BigDecimal("488"),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal("12"))
        ]
        resp.body.successful == 1
    }

    def "silently drops malformed expectedPrices entries instead of 400-ing the whole cart"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()

        when:
        // "abc" is not a valid number — that entry drops, row 1 still goes through.
        def resp = controller.checkout([
            listingIds: [1L],
            expectedPrices: ['1': 'abc']
        ], req)

        then:
        1 * purchaseService.buy(500L, 10L, 1L) >> [
            newBalance: new BigDecimal("490"),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal("10"))
        ]
        resp.body.results[0].status == 'OK'
    }

    def "a non-map expectedPrices value is ignored — checkout still runs"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()

        when:
        // expectedPrices arrives as a list (malformed) — must not blow up.
        def resp = controller.checkout([
            listingIds: [1L],
            expectedPrices: ['junk']
        ], req)

        then:
        0 * listingRepository.findById(_)
        1 * purchaseService.buy(500L, 10L, 1L) >> [
            newBalance: new BigDecimal('490'),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal('10'))
        ]
        resp.body.successful == 1
    }

    def "unexpected RuntimeException does NOT leak the raw message (bug #28)"() {
        given:
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        purchaseService.buy(500L, 10L, 1L) >> {
            throw new RuntimeException('ORA-01000: maximum open cursors exceeded at Something.java:42')
        }

        when:
        def resp = controller.checkout([listingIds: [1L]], req)
        def body = resp.body

        then:
        body.results[0].code == 'INTERNAL_ERROR'
        body.results[0].error == 'Could not complete this purchase'
        !body.results[0].error.contains('ORA-01000')
        !body.results[0].error.contains('Something.java')
    }

    // ── checkout: server-side cart scrub ─────────────────────────────

    def "successful rows are scrubbed from the server cart, failed rows are kept"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        purchaseService.buy(500L, 10L, 1L) >> [
            newBalance: new BigDecimal('490'),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal('10'))
        ]
        purchaseService.buy(500L, 10L, 2L) >> { throw new ListingNotAvailableException(2L) }

        when:
        ctrl.checkout([listingIds: [1L, 2L]], req)

        then:
        // Only the OK row (1) is removed from the cart; the failed row (2)
        // stays so the buyer can retry it.
        1 * cartService.remove(10L, 1L)
        0 * cartService.remove(10L, 2L)
    }

    def "a cart-scrub failure on one row does not abort the checkout response"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)
        steamUserRepository.findById(10L) >> Optional.of(user())
        walletRepository.findByUsername('steam_111') >> wallet()
        purchaseService.buy(500L, 10L, 1L) >> [
            newBalance: new BigDecimal('490'),
            listing:    new com.sboxmarket.model.Listing(price: new BigDecimal('10'))
        ]
        // The scrub throws — the checkout result must still come back 200.
        cartService.remove(10L, 1L) >> { throw new RuntimeException('cart db down') }

        when:
        def resp = ctrl.checkout([listingIds: [1L]], req)

        then:
        notThrown(Exception)
        resp.body.successful == 1
    }

    // ── cart persistence endpoints: list ─────────────────────────────

    def "GET cart returns the service list"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)
        cartService.list(10L) >> [3L, 7L]

        when:
        def resp = ctrl.list(req)

        then:
        resp.body == [3L, 7L]
    }

    def "GET cart returns an empty list when CartService is absent"() {
        given:
        def req = reqFor(10L)

        when:
        // `controller` here has no cartService wired.
        def resp = controller.list(req)

        then:
        resp.body == []
    }

    def "GET cart still requires authentication"() {
        given:
        def req = reqFor(null)

        when:
        controller.list(req)

        then:
        thrown(UnauthorizedException)
    }

    // ── cart persistence endpoints: add ──────────────────────────────

    def "POST cart/{id} adds and echoes the post-state ids"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)
        cartService.add(10L, 55L) >> true
        cartService.list(10L) >> [55L]

        when:
        def resp = ctrl.addToCart(55L, req)

        then:
        resp.body.listingId == 55L
        resp.body.added == true
        resp.body.ids == [55L]
    }

    def "POST cart/{id} reports added:false on an idempotent re-add"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)
        cartService.add(10L, 55L) >> false      // already present
        cartService.list(10L) >> [55L]

        when:
        def resp = ctrl.addToCart(55L, req)

        then:
        resp.body.added == false
    }

    def "POST cart/{id} fails with UNSUPPORTED when CartService is absent"() {
        given:
        def req = reqFor(10L)

        when:
        controller.addToCart(55L, req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'UNSUPPORTED'
    }

    def "POST cart/{id} propagates CART_FULL from the service"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)
        cartService.add(10L, 55L) >> { throw new BadRequestException('CART_FULL', 'Cart is capped at 50 items.') }

        when:
        ctrl.addToCart(55L, req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'CART_FULL'
    }

    // ── cart persistence endpoints: remove ───────────────────────────

    def "DELETE cart/{id} removes and echoes post-state"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)
        cartService.remove(10L, 55L) >> true
        cartService.list(10L) >> []

        when:
        def resp = ctrl.removeFromCart(55L, req)

        then:
        resp.body.removed == true
        resp.body.ids == []
    }

    def "DELETE cart/{id} degrades to removed:false when CartService is absent"() {
        given:
        def req = reqFor(10L)

        when:
        def resp = controller.removeFromCart(55L, req)

        then:
        resp.body.removed == false
        resp.body.ids == []
    }

    // ── cart persistence endpoints: clear ────────────────────────────

    def "DELETE cart clears and returns the wiped count"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)
        cartService.clear(10L) >> 4

        when:
        def resp = ctrl.clear(req)

        then:
        resp.body.removed == 4
    }

    def "DELETE cart degrades to removed:0 when CartService is absent"() {
        given:
        def req = reqFor(10L)

        when:
        def resp = controller.clear(req)

        then:
        resp.body.removed == 0
    }

    // ── cart persistence endpoints: bulk merge ───────────────────────

    def "POST cart/bulk forwards cleaned ids and returns the merged list"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)
        cartService.bulkMerge(10L, [1L, 2L, 3L]) >> [1L, 2L, 3L]

        when:
        def resp = ctrl.bulkMerge([ids: [1, 2, 3]], req)

        then:
        resp.body.ids == [1L, 2L, 3L]
    }

    def "POST cart/bulk drops null and non-numeric ids before calling the service"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)

        when:
        ctrl.bulkMerge([ids: [1, null, 'abc', 4]], req)

        then:
        // Only the two parseable ids reach the service — the null and the
        // non-numeric string are filtered out, never a 400.
        1 * cartService.bulkMerge(10L, [1L, 4L]) >> [1L, 4L]
    }

    def "POST cart/bulk treats a missing ids key as an empty merge"() {
        given:
        def cartService = Mock(CartService)
        def ctrl = controllerWithCart(cartService)
        def req = reqFor(10L)

        when:
        ctrl.bulkMerge([:], req)

        then:
        1 * cartService.bulkMerge(10L, []) >> []
    }

    def "POST cart/bulk degrades to an empty list when CartService is absent"() {
        given:
        def req = reqFor(10L)

        when:
        def resp = controller.bulkMerge([ids: [1, 2]], req)

        then:
        resp.body.ids == []
    }

    def "POST cart/bulk still requires authentication"() {
        given:
        def req = reqFor(null)

        when:
        controller.bulkMerge([ids: [1]], req)

        then:
        thrown(UnauthorizedException)
    }
}
