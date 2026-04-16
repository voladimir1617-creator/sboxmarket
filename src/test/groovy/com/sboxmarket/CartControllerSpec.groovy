package com.sboxmarket

import com.sboxmarket.controller.CartController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PurchaseService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.orm.ObjectOptimisticLockingFailureException
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the bulk checkout endpoint. The important invariants:
 *
 *   - Partial success is allowed: row 2 failing does not roll back row 1.
 *   - Error rows carry a stable machine-readable `code`, never the raw
 *     exception message. Leaking `e.message` from an unexpected
 *     RuntimeException was bug #28 in the audit log — it could expose
 *     stack-frame detail or SQL fragments to the client.
 */
class CartControllerSpec extends Specification {

    PurchaseService      purchaseService      = Mock()
    WalletRepository     walletRepository     = Mock()
    SteamUserRepository  steamUserRepository  = Mock()

    @Subject
    CartController controller = new CartController(
        purchaseService    : purchaseService,
        walletRepository   : walletRepository,
        steamUserRepository: steamUserRepository
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

    def "rejects empty cart with EMPTY_CART"() {
        given:
        def req = reqFor(10L)

        when:
        controller.checkout([listingIds: []], req)

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

    def "rejects anonymous callers"() {
        given:
        def req = reqFor(null)

        when:
        controller.checkout([listingIds: [1L]], req)

        then:
        thrown(UnauthorizedException)
    }

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
        body.results[2].status == 'OK'
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

}
