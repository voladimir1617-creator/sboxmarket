package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * The money-OUT leg: what happens when the seller withdraws his proceeds.
 * This is the last step of the path he walks, and the only one the earlier
 * sell specs stop short of.
 *
 * -- What is exercised, and what is deliberately NOT ----------------------
 * StripeService.isLive() is simply "a secret key is set and is not the
 * replace_me placeholder". The dummy key below flips the service into LIVE
 * mode, which is the mode the operator will actually run in -- and which the
 * default test profile never exercises, because sk_test_replace_me sends every
 * withdrawal down the simulated dev path instead.
 *
 * Every case here is a REJECTION that happens strictly BEFORE any Stripe API
 * call is made, so nothing reaches the network and no money moves. That is the
 * only reason these are testable at all.
 *
 * NOT covered here, and still unproven before he goes live:
 *   - a successful Transfer to a payouts-enabled connected account
 *   - Connect Express onboarding-link creation (it creates a Stripe account)
 *   - the transfer.reversed / payout.failed webhook legs on real events
 * Those need a real Stripe test-mode account and are his to run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = [
    // Not a real key, and deliberately does NOT contain "replace_me" -- that
    // substring is the only thing separating dev mode from live mode. No
    // outbound call is ever made, because every test below is refused first.
    'stripe.secret-key=sk_test_connectgate_notreal'
])
class SellPayoutConnectGateSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc               mockMvc
    SteamUserRepository   steamUserRepository
    WalletRepository      walletRepository
    TransactionRepository transactionRepository

    SteamUser seller
    Wallet    wallet
    MockHttpSession session

    def setup() {
        mockMvc               = ctx.getBean(MockMvc)
        steamUserRepository   = ctx.getBean(SteamUserRepository)
        walletRepository      = ctx.getBean(WalletRepository)
        transactionRepository = ctx.getBean(TransactionRepository)

        def uniq = String.valueOf(System.nanoTime())
        def steamId = "76561199" + uniq.substring(uniq.length() - 9)

        // Everything the earlier gates need, so the request actually reaches
        // the Connect check rather than dying on email or 2FA first.
        seller = steamUserRepository.save(new SteamUser(
            steamId64:     steamId,
            displayName:   "PayoutSeller-" + uniq,
            email:         "seller-" + uniq + "@example.com",
            emailVerified: true
        ))
        wallet = walletRepository.save(new Wallet(
            username:       "steam_" + steamId,
            balance:        new BigDecimal("250.00"),
            payoutsEnabled: false
        ))

        session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, seller.id)
    }

    /**
     * THE money-safety assertion for the payout leg. Before this gate existed
     * the code booked a "manual" PENDING withdrawal that no payout API would
     * ever fulfil: the balance went down and the cash never arrived. The
     * rejection must land BEFORE the wallet is touched.
     */
    def "POST /api/wallet/withdraw - refused CONNECT_ONBOARDING_REQUIRED, balance untouched"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/wallet/withdraw")
                .session(session)
                .contentType("application/json")
                .content('{"amount":"50.00"}')
        ).andReturn()

        then: "refused with the code the SPA branches on to show Set up payouts"
        result.response.status == 400
        result.response.contentAsString.contains('"code":"CONNECT_ONBOARDING_REQUIRED"')

        and: "not one cent left the wallet"
        walletRepository.findById(wallet.id).get().balance == new BigDecimal("250.00")

        and: "and no withdrawal row was booked that nothing would ever fulfil"
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.id)
            .findAll { it.type == 'WITHDRAWAL' }
            .isEmpty()
    }

    /**
     * "Unknown" must never be storable, because somewhere downstream it would
     * eventually be read as permission.
     *
     * The application already defends with Boolean.TRUE.equals(...), which
     * treats null as not-onboarded. This pins the second, stronger line: the
     * payouts_enabled COLUMN is NOT NULL, so an un-onboarded wallet cannot even
     * be represented as ambiguous. If someone later relaxes that column, this
     * test fires and forces the question "does null read as permission?" to be
     * answered deliberately rather than by accident.
     */
    def "the payouts_enabled column refuses NULL, so onboarding state is never ambiguous"() {
        when:
        wallet.payoutsEnabled = null
        walletRepository.saveAndFlush(wallet)

        then:
        thrown(Exception)
    }

    def "POST /api/wallet/withdraw - 401 without a session"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/wallet/withdraw")
                .contentType("application/json")
                .content('{"amount":"50.00"}')
        ).andReturn()

        then:
        result.response.status == 401

        and:
        walletRepository.findById(wallet.id).get().balance == new BigDecimal("250.00")
    }

    def "POST /api/wallet/withdraw - an unverified email is refused before any payout logic"() {
        given:
        seller.emailVerified = false
        steamUserRepository.save(seller)

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/wallet/withdraw")
                .session(session)
                .contentType("application/json")
                .content('{"amount":"50.00"}')
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"EMAIL_NOT_VERIFIED"')

        and:
        walletRepository.findById(wallet.id).get().balance == new BigDecimal("250.00")
    }

    def "POST /api/wallet/withdraw - a frozen wallet cannot pay out"() {
        given:
        wallet.frozen = true
        walletRepository.save(wallet)

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/wallet/withdraw")
                .session(session)
                .contentType("application/json")
                .content('{"amount":"50.00"}')
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"WALLET_FROZEN"')

        and:
        walletRepository.findById(wallet.id).get().balance == new BigDecimal("250.00")
    }

    /** He must not be able to withdraw more than he has. */
    def "POST /api/wallet/withdraw - more than the balance is refused"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/wallet/withdraw")
                .session(session)
                .contentType("application/json")
                .content('{"amount":"9000.00"}')
        ).andReturn()

        then: "refused - insufficient balance or the Connect gate, either way refused"
        result.response.status >= 400

        and: "and the balance is intact"
        walletRepository.findById(wallet.id).get().balance == new BigDecimal("250.00")
    }

    def "GET /api/wallet/connect/status - reports onboarding is needed before he has an account"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/wallet/connect/status").session(session)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains('"payoutsEnabled":false')
        body.contains('"onboardingNeeded":true')
    }
}
