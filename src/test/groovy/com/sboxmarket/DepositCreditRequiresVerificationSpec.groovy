package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import spock.lang.Specification

/**
 * <b>{@code completeDeposit} must never credit a wallet it could not verify.</b>
 *
 * <h3>The hole</h3>
 *
 * The payment verification ran under a CONDITION:
 *
 * <pre>if (isLive() &amp;&amp; sessionId.startsWith('cs_')) { ...ask Stripe... }</pre>
 *
 * with no {@code else}. So both of its failure directions fell straight through
 * to {@code wallet.balance = wallet.balance + credited}:
 *
 * <ol>
 *   <li><b>{@code !isLive()}</b> — no processor to ask, credit anyway.</li>
 *   <li><b>a reference that is not a {@code cs_} Checkout Session id</b> —
 *       nothing to ask Stripe ABOUT, credit anyway.</li>
 * </ol>
 *
 * <b>Skipping a check is not the same as passing it.</b> This is the same
 * defect {@code DevCreditGate} closed at {@code createDepositSession},
 * {@code devModeDeposit} and {@code createConnectOnboardingLink} in c269b82 —
 * {@code !isLive()} read as a licence to credit. Those three are the DOORS;
 * {@code completeDeposit} is the credit primitive behind them, and it was
 * still reading "I cannot verify" as "go ahead".
 *
 * <h3>Reachable without any code change</h3>
 *
 * {@code createDepositSession} only writes a PENDING DEPOSIT row while a real
 * key is configured, so the row and the unverified credit need different
 * moments — which a restart supplies:
 *
 * <ol>
 *   <li>Key configured. User opens a $10,000 deposit; a PENDING row is written
 *       carrying the {@code cs_…} session id, which the user's own browser is
 *       handed.</li>
 *   <li>User abandons Stripe Checkout. <b>Nothing is paid.</b> The row stays
 *       PENDING for 48h (the stale sweeper's cutoff).</li>
 *   <li>The process restarts without {@code STRIPE_SECRET_KEY} — a dropped env
 *       var, a secret-manager blip, a redeploy from a stale env file. The
 *       committed default {@code sk_test_replace_me} classifies SIMULATED, so
 *       {@code isLive()} is now false.</li>
 *   <li>User POSTs that session id to {@code /api/wallet/confirm-deposit} and
 *       is credited in full, having paid nothing.</li>
 * </ol>
 *
 * The wallet daily cap does not bound this: the cap is enforced when the
 * SESSION is created, not when the deposit is confirmed.
 */
class DepositCreditRequiresVerificationSpec extends Specification {

    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()

    private StripeService svc(String key, boolean creditOptIn) {
        new StripeService(
            walletRepository      : walletRepository,
            transactionRepository : transactionRepository,
            environment           : creditOptIn ? SpecEnvs.creditOptedIn() : SpecEnvs.env(),
            secretKey             : key,
            publishableKey        : 'pk_test_replace_me',
            webhookSecret         : 'whsec_replace_me',
            successUrl            : 'http://localhost/ok',
            cancelUrl             : 'http://localhost/cancel',
            currency              : 'usd')
    }

    /** An abandoned-but-PENDING deposit: the card was NEVER charged. */
    private Transaction unpaidPendingRow(String ref) {
        new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('10000.00'), currency: 'USD', stripeReference: ref)
    }

    // ── 1. The reported hole: no processor to ask ────────────────────────

    def "an unconfigured deployment REFUSES to credit an unpaid deposit instead of crediting it"() {
        given: 'the key went missing on a restart, so the placeholder classifies SIMULATED'
        def service = svc('sk_test_replace_me', false)
        def wallet = new Wallet(id: 500L, balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('cs_abandoned') >> unpaidPendingRow('cs_abandoned')
        walletRepository.findById(500L) >> Optional.of(wallet)

        when: 'the user confirms a Checkout session they never paid'
        service.completeDeposit('cs_abandoned')

        then: 'refused by DECISION, with a branchable code — not a fall-through credit'
        def e = thrown(BadRequestException)
        e.code == 'DEV_CREDIT_NOT_AUTHORIZED'

        and: 'and no money was invented'
        wallet.balance == BigDecimal.ZERO
        0 * walletRepository.save(_)
    }

    def "an INDETERMINATE key REFUSES too — I-cannot-tell is CLOSED, not a dev fallback"() {
        given: 'a truncated/garbled key: neither live, nor test, nor blank'
        def service = svc('sk_wat_this_is_not_a_stripe_key', false)
        def wallet = new Wallet(id: 500L, balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('cs_indet') >> unpaidPendingRow('cs_indet')
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        service.completeDeposit('cs_indet')

        then: 'refused — and NOT as DEV_CREDIT_NOT_AUTHORIZED, because it is not SIMULATED'
        def e = thrown(BadRequestException)
        e.code == 'STRIPE_MODE_INDETERMINATE'

        and:
        wallet.balance == BigDecimal.ZERO
        0 * walletRepository.save(_)
    }

    def "the opt-in does NOT rescue an INDETERMINATE key — the mode is checked first"() {
        given: 'somebody set the dev-credit opt-in AND the key is unclassifiable'
        def service = svc('sk_wat_this_is_not_a_stripe_key', true)
        def wallet = new Wallet(id: 500L, balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('cs_indet2') >> unpaidPendingRow('cs_indet2')
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        service.completeDeposit('cs_indet2')

        then: 'an opt-in authorises SIMULATED fabrication only; it can never authorise a guess'
        def e = thrown(BadRequestException)
        e.code == 'STRIPE_MODE_INDETERMINATE'
        wallet.balance == BigDecimal.ZERO
        0 * walletRepository.save(_)
    }

    // ── 2. The second leg: nothing to ask Stripe ABOUT ───────────────────

    def "a Stripe-configured deployment REFUSES a reference it cannot hand to Session.retrieve"() {
        given: 'live mode, but the PENDING row carries a reference createDepositSession never writes'
        def service = svc('sk_live_real_key', false)
        def wallet = new Wallet(id: 500L, balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('pi_not_a_session') >> unpaidPendingRow('pi_not_a_session')
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        service.completeDeposit('pi_not_a_session')

        then: 'refused rather than skipping the verification and crediting'
        thrown(IllegalStateException)

        and:
        wallet.balance == BigDecimal.ZERO
        0 * walletRepository.save(_)
    }

    // ── 3. INVERSE CONTROLS — what must still work ───────────────────────
    //
    // A guard that refuses everything would pass every case above while
    // breaking real deposits. These two prove the refusals are selective.

    def "INVERSE CONTROL: SIMULATED *with* the named opt-in still credits — the dev affordance survives"() {
        given: 'exactly what StripeServiceSpec/PassThroughFeePricingSpec configure'
        def service = svc('sk_test_replace_me', true)
        def tx = unpaidPendingRow('cs_devbox')
        def wallet = new Wallet(id: 500L, balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('cs_devbox') >> tx
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        service.completeDeposit('cs_devbox')

        then: 'credited, because somebody asked for a simulated money path by name'
        wallet.balance == new BigDecimal('10000.00')
        tx.status == 'COMPLETED'
    }

    def "INVERSE CONTROL: live mode with a verified cs_ session still credits"() {
        given: 'a real key and a session Stripe confirms as paid (seam stubbed — Session.retrieve is static)'
        def service = svc('sk_live_real_key', false)
        service.metaClass.assertDepositPaidAtStripe = { String s, Transaction t -> null }
        def tx = unpaidPendingRow('cs_paid')
        def wallet = new Wallet(id: 500L, balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('cs_paid') >> tx
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        service.completeDeposit('cs_paid')

        then: 'the ordinary paid-deposit path is untouched by the guard'
        wallet.balance == new BigDecimal('10000.00')
        tx.status == 'COMPLETED'
    }

    def "INVERSE CONTROL: a genuine dev_ row is COMPLETED already and short-circuits before any of this"() {
        given: 'devModeDeposit writes COMPLETED rows — they must not start tripping the new refusals'
        def service = svc('sk_test_replace_me', true)
        def tx = new Transaction(id: 2L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('25.00'), currency: 'USD', stripeReference: 'dev_1234')
        transactionRepository.findByStripeReference('dev_1234') >> tx

        when:
        service.completeDeposit('dev_1234')

        then: 'no throw, and no second credit'
        noExceptionThrown()
        0 * walletRepository.save(_)
    }
}
