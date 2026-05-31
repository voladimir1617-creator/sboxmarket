package com.sboxmarket

import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the Stripe Connect (Express) payout rail added to
 * StripeService:
 *
 *   - createConnectOnboardingLink — creates an Express account + an
 *     onboarding AccountLink, persisting the connected-account id.
 *   - connectStatus — reports payoutsEnabled / onboardingNeeded.
 *   - handleAccountUpdated — the `account.updated` webhook that mirrors
 *     Stripe's payouts_enabled flag onto wallet.payoutsEnabled.
 *
 * Stripe's static SDK calls (Account.create / AccountLink.create /
 * Account.retrieve) are mocked with GroovySpy(global:true) — the same
 * technique StripeServiceSpec uses for Session.create in the deposit
 * idempotency test. Account has setId/setPayoutsEnabled/etc. so it can
 * be built via the Groovy map constructor; Transfer/AccountLink lack
 * those setters, so where their getters matter we use Spock Mocks.
 */
class StripeConnectSpec extends Specification {

    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()

    @Subject
    StripeService service = new StripeService(
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        secretKey             : 'sk_test_replace_me',   // dev-mode by default
        publishableKey        : 'pk_test_replace_me',
        webhookSecret         : 'whsec_replace_me',
        successUrl            : 'http://localhost/ok',
        cancelUrl             : 'http://localhost/cancel',
        currency              : 'usd',
        connectReturnUrl      : 'http://localhost/wallet?connect=done',
        connectRefreshUrl     : 'http://localhost/wallet?connect=refresh',
        connectCountry        : 'US'
    )

    // ── createConnectOnboardingLink ───────────────────────────────────

    def "createConnectOnboardingLink in DEV mode marks the wallet simulated and never calls Stripe"() {
        given:
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }

        when:
        def result = service.createConnectOnboardingLink(500L)

        then: 'a SIMULATED (non-acct_) account id is stamped, live=false, no real onboarding'
        result.live == false
        result.simulated == true
        result.onboardingUrl == 'http://localhost/wallet?connect=refresh'
        wallet.stripeConnectAccountId != null
        wallet.stripeConnectAccountId.startsWith('dev_acct_')
        // Dev mode must NOT pretend payouts are enabled.
        !Boolean.TRUE.equals(wallet.payoutsEnabled)
    }

    def "createConnectOnboardingLink in LIVE mode creates an Express account, persists the acct id, and returns the onboarding URL"() {
        given:
        service.secretKey = 'sk_live_abc'                // isLive() == true
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }

        and: 'Stripe returns a new Express account + an onboarding link'
        def fakeAccount = new com.stripe.model.Account(id: 'acct_new_1')
        GroovySpy(com.stripe.model.Account, global: true)
        com.stripe.model.Account.create(_ as com.stripe.param.AccountCreateParams) >> fakeAccount
        def fakeLink = Mock(com.stripe.model.AccountLink) { getUrl() >> 'https://connect.stripe.com/setup/acct_new_1' }
        GroovySpy(com.stripe.model.AccountLink, global: true)
        com.stripe.model.AccountLink.create(_ as com.stripe.param.AccountLinkCreateParams) >> fakeLink

        when:
        def result = service.createConnectOnboardingLink(500L)

        then: 'the connected-account id is persisted on the wallet and the hosted link is returned'
        result.live == true
        result.simulated == false
        result.onboardingUrl == 'https://connect.stripe.com/setup/acct_new_1'
        result.accountId == 'acct_new_1'
        wallet.stripeConnectAccountId == 'acct_new_1'
    }

    def "createConnectOnboardingLink reuses an existing acct id — it does NOT create a second Stripe account"() {
        given: 'a wallet that already onboarded once'
        service.secretKey = 'sk_live_abc'
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO,
            stripeConnectAccountId: 'acct_existing_9')
        walletRepository.findById(500L) >> Optional.of(wallet)

        and: 'only AccountLink.create should be hit — Account.create must NOT fire'
        GroovySpy(com.stripe.model.Account, global: true)
        def fakeLink = Mock(com.stripe.model.AccountLink) { getUrl() >> 'https://connect.stripe.com/setup/acct_existing_9' }
        GroovySpy(com.stripe.model.AccountLink, global: true)
        com.stripe.model.AccountLink.create(_ as com.stripe.param.AccountLinkCreateParams) >> fakeLink

        when:
        def result = service.createConnectOnboardingLink(500L)

        then: 'a fresh link is minted for the EXISTING account; no new account created'
        result.accountId == 'acct_existing_9'
        result.onboardingUrl == 'https://connect.stripe.com/setup/acct_existing_9'
        0 * com.stripe.model.Account.create(_)
    }

    // ── connectStatus ─────────────────────────────────────────────────

    def "connectStatus in DEV mode reports persisted flags only (never calls Stripe)"() {
        given:
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO,
            stripeConnectAccountId: 'dev_acct_500', payoutsEnabled: false)
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        def status = service.connectStatus(500L)

        then:
        status.live == false
        status.hasAccount == true
        status.payoutsEnabled == false
        status.onboardingNeeded == true
        status.simulated == true
    }

    def "connectStatus live: a never-onboarded wallet reports onboardingNeeded=true"() {
        given:
        service.secretKey = 'sk_live_abc'
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO,
            stripeConnectAccountId: null, payoutsEnabled: false)
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        def status = service.connectStatus(500L)

        then: 'no account id → no Stripe re-read, just the persisted "needs onboarding" state'
        status.live == true
        status.hasAccount == false
        status.payoutsEnabled == false
        status.onboardingNeeded == true
    }

    def "connectStatus live: self-heals payoutsEnabled from Stripe when a webhook was missed"() {
        given: 'the persisted flag says false but Stripe now reports the account is payouts-enabled'
        service.secretKey = 'sk_live_abc'
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO,
            stripeConnectAccountId: 'acct_seller_1', payoutsEnabled: false)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        def stripeAccount = new com.stripe.model.Account(
            id: 'acct_seller_1', payoutsEnabled: true, chargesEnabled: true, detailsSubmitted: true)
        GroovySpy(com.stripe.model.Account, global: true)
        com.stripe.model.Account.retrieve('acct_seller_1') >> stripeAccount

        when:
        def status = service.connectStatus(500L)

        then: 'the persisted flag is healed to true and the response reflects it'
        status.payoutsEnabled == true
        status.onboardingNeeded == false
        wallet.payoutsEnabled == true
        1 * walletRepository.save({ Wallet w -> Boolean.TRUE.equals(w.payoutsEnabled) })
    }

    def "connectStatus live: a Stripe re-read failure degrades to the persisted flag (no 500 on a status poll)"() {
        given:
        service.secretKey = 'sk_live_abc'
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO,
            stripeConnectAccountId: 'acct_seller_1', payoutsEnabled: true)
        walletRepository.findById(500L) >> Optional.of(wallet)
        GroovySpy(com.stripe.model.Account, global: true)
        com.stripe.model.Account.retrieve('acct_seller_1') >> { throw new RuntimeException('Stripe down') }

        when:
        def status = service.connectStatus(500L)

        then: 'falls back to the persisted mirror rather than throwing'
        noExceptionThrown()
        status.payoutsEnabled == true
        status.onboardingNeeded == false
    }

    // ── handleAccountUpdated (account.updated webhook) ─────────────────

    def "handleAccountUpdated flips wallet.payoutsEnabled true when Stripe reports the account can receive payouts"() {
        given:
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO,
            stripeConnectAccountId: 'acct_seller_1', payoutsEnabled: false)
        walletRepository.findByStripeConnectAccountId('acct_seller_1') >> wallet
        walletRepository.save(_) >> { Wallet w -> w }
        def account = new com.stripe.model.Account(id: 'acct_seller_1', payoutsEnabled: true)

        when:
        service.handleAccountUpdated(account)

        then: 'the false→true edge is persisted'
        wallet.payoutsEnabled == true
        1 * walletRepository.save({ Wallet w -> Boolean.TRUE.equals(w.payoutsEnabled) })
    }

    def "handleAccountUpdated is idempotent — no save when the flag is unchanged"() {
        given: 'the wallet is already payouts-enabled and Stripe reports the same'
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO,
            stripeConnectAccountId: 'acct_seller_1', payoutsEnabled: true)
        walletRepository.findByStripeConnectAccountId('acct_seller_1') >> wallet
        def account = new com.stripe.model.Account(id: 'acct_seller_1', payoutsEnabled: true)

        when:
        service.handleAccountUpdated(account)

        then: 'no redundant write on a no-change re-delivery'
        0 * walletRepository.save(_)
    }

    def "handleAccountUpdated no-ops for an account id we do not own (no matching wallet)"() {
        given:
        walletRepository.findByStripeConnectAccountId('acct_unknown') >> null
        def account = new com.stripe.model.Account(id: 'acct_unknown', payoutsEnabled: true)

        when:
        service.handleAccountUpdated(account)

        then: 'tolerated — never throws (would otherwise 500 the webhook into a retry loop)'
        noExceptionThrown()
        0 * walletRepository.save(_)
    }

    def "handleAccountUpdated can also turn payouts OFF (Stripe restricted the account)"() {
        given: 'a previously-enabled wallet that Stripe now reports as restricted'
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO,
            stripeConnectAccountId: 'acct_seller_1', payoutsEnabled: true)
        walletRepository.findByStripeConnectAccountId('acct_seller_1') >> wallet
        walletRepository.save(_) >> { Wallet w -> w }
        def account = new com.stripe.model.Account(id: 'acct_seller_1', payoutsEnabled: false)

        when:
        service.handleAccountUpdated(account)

        then: 'the true→false edge is persisted so the withdraw gate re-engages'
        wallet.payoutsEnabled == false
        1 * walletRepository.save({ Wallet w -> !Boolean.TRUE.equals(w.payoutsEnabled) })
    }

    def "handleAccountUpdated tolerates a null account / missing id"() {
        when:
        service.handleAccountUpdated(null)
        then:
        noExceptionThrown()
        0 * walletRepository.findByStripeConnectAccountId(_)
    }
}
