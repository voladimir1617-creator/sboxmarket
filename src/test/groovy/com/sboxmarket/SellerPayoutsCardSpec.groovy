package com.sboxmarket

import com.sboxmarket.controller.WalletController
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.SellerPayoutService
import com.sboxmarket.service.StripeService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification

/**
 * GET /api/wallet/payouts and the My Stall card that reads it.
 *
 * The card is read-only on purpose: cashing out must keep going through the
 * one withdraw form (2FA, daily cap, fee preview, Stripe cash-out gate), so
 * the card's button navigates there instead of posting a withdrawal itself.
 */
class SellerPayoutsCardSpec extends Specification {

    WalletRepository    walletRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    SellerPayoutService sellerPayoutService = Mock()

    WalletController controller = new WalletController(
        walletRepository:      walletRepository,
        transactionRepository: Mock(TransactionRepository),
        steamUserRepository:   steamUserRepository,
        stripeService:         Mock(StripeService),
        sellerPayoutService:   sellerPayoutService
    )

    private HttpServletRequest reqFor(Long uid) {
        def session = Mock(HttpSession)
        session.getAttribute('steamUserId') >> uid
        def req = Mock(HttpServletRequest)
        req.session >> session
        req
    }

    def "signed-out callers are refused"() {
        when:
        controller.getPayouts(reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * sellerPayoutService._
    }

    def "a seller gets the summary for their own wallet"() {
        given:
        def w = new Wallet(id: 55L, username: 'steam_111', balance: BigDecimal.TEN)
        steamUserRepository.findById(9L) >> Optional.of(new SteamUser(id: 9L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> w

        when:
        def res = controller.getPayouts(reqFor(9L))

        then:
        1 * sellerPayoutService.summary(9L, w) >> [available: BigDecimal.TEN]
        res.body.available == BigDecimal.TEN
    }

    def "the shared demo wallet never leaks into a seller's payouts"() {
        given:
        def demo = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal('250'))
        steamUserRepository.findById(9L) >> Optional.of(new SteamUser(id: 9L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> demo

        when:
        controller.getPayouts(reqFor(9L))

        then:
        1 * sellerPayoutService.summary(9L, null) >> [:]
    }

    private static String js(String name) {
        new File("src/main/resources/static/js/${name}").getText('UTF-8')
    }

    def "the card reads /api/wallet/payouts and sends Request payout to the existing withdraw form"() {
        given:
        def card = js('seller-payouts.js')

        expect:
        js('api.js').contains('/wallet/payouts')
        card.contains("navigate('/wallet/withdraw')")
        !card.contains('withdrawFunds')
        !(card =~ /method:\s*'POST'/)
    }

    def "My Stall renders the card"() {
        expect:
        js('modals.js').contains("import { SellerPayoutsCard } from './seller-payouts.js';")
        js('modals.js').contains('h(SellerPayoutsCard, {')
    }

    def "the card shows the four things a seller asked for"() {
        given:
        def card = js('seller-payouts.js')

        expect:
        ['Available', 'Pending', 'Paid out', 'Next payout: ', 'Payout history', 'Request payout'].every { card.contains(it) }
    }
}
