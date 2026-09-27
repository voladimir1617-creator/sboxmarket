package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Transaction
import com.sboxmarket.repository.ProcessedStripeEventRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification
import spock.lang.Subject

/**
 * Two ways this platform loses real money on ordinary, non-adversarial use.
 *
 * <h3>1. A rolled-back webhook can never be retried</h3>
 *
 * {@code claimStripeEvent} takes the dedup claim BEFORE the handler runs: it
 * INSERTs the {@code processed_stripe_events} row and calls
 * {@code markProcessed}. The comment in {@code handleWebhookEvent} argues this
 * is safe because "the claim row is written inside THIS @Transactional, so a
 * thrown handler rolls the claim row back too".
 *
 * That is true of the DB row and FALSE of {@code seenEventIds}, a plain
 * in-memory LinkedHashSet that no rollback touches. So a handler that threw
 * left the durable claim gone and the in-memory claim standing, and Stripe's
 * retry hit the very first line of {@code claimStripeEvent} — the
 * {@code alreadyProcessed} fast path — was told "already processed", and the
 * controller ACKed 200. Stripe then stops retrying.
 *
 * On a single-pod deployment (this app's docker-compose shape) the retry
 * ALWAYS lands on the pod holding the poisoned cache, so the outcome is
 * deterministic: card charged, wallet never credited, no COMPLETED row, no
 * recovery. {@code completeDeposit} has at least one deliberately transient
 * throw path for a Stripe API blip, so this needs no bug to trigger — just a
 * bad minute at Stripe.
 *
 * <h3>2. A second withdrawal in the same minute is paid once and debited twice</h3>
 *
 * See {@code assertTransferNotAlreadyRecorded}.
 */
class StripeMoneyLossRegressionSpec extends Specification {

    WalletRepository               walletRepository               = Mock()
    TransactionRepository          transactionRepository          = Mock()
    ProcessedStripeEventRepository processedStripeEventRepository = Mock()

    @Subject
    StripeService service = new StripeService(
        walletRepository:               walletRepository,
        transactionRepository:          transactionRepository,
        processedStripeEventRepository: processedStripeEventRepository,
        secretKey:                      'sk_test_replace_me',
        publishableKey:                 'pk_test_replace_me',
        webhookSecret:                  'whsec_replace_me',
        successUrl:                     'http://localhost/ok',
        cancelUrl:                      'http://localhost/cancel',
        currency:                       'usd'
    )

    def cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    /** Fire the registered afterCompletion callbacks with the given outcome,
     *  standing in for the transaction manager. */
    private void completeTransaction(int status) {
        TransactionSynchronizationManager.synchronizations
            .each { it.afterCompletion(status) }
        TransactionSynchronizationManager.clearSynchronization()
    }

    // ── 1. Webhook claim vs rollback ────────────────────────────────

    def "a ROLLED BACK webhook claim is forgotten, so Stripe's retry genuinely re-runs the handler"() {
        given: "an active transaction, as handleWebhookEvent always has"
        TransactionSynchronizationManager.initSynchronization()
        processedStripeEventRepository.existsByEventId('evt_boom') >> false

        when: "this pod wins the claim"
        def firstClaim = service.claimStripeEvent('evt_boom', 'checkout.session.completed')

        then:
        firstClaim

        when: "the handler throws and the transaction rolls back — the claim ROW is gone with it"
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK)

        and: "Stripe retries the same event onto the same pod"
        TransactionSynchronizationManager.initSynchronization()
        def retryClaim = service.claimStripeEvent('evt_boom', 'checkout.session.completed')

        then: "the retry must be allowed to run — otherwise the paid-for deposit is lost forever"
        retryClaim
    }

    def "a COMMITTED webhook claim is remembered, so a re-delivery is still deduped"() {
        given:
        TransactionSynchronizationManager.initSynchronization()
        processedStripeEventRepository.existsByEventId('evt_ok') >> false

        when:
        service.claimStripeEvent('evt_ok', 'checkout.session.completed')

        and: "the handler succeeded and the transaction committed"
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED)

        and: "Stripe re-delivers the same event"
        def again = service.claimStripeEvent('evt_ok', 'checkout.session.completed')

        then: "the fix must NOT have weakened the dedup it is built on"
        !again
    }

    def "the durable claim row is still written up front (multi-pod safety is unchanged)"() {
        given:
        TransactionSynchronizationManager.initSynchronization()
        processedStripeEventRepository.existsByEventId('evt_x') >> false

        when:
        service.claimStripeEvent('evt_x', 'checkout.session.completed')

        then: "the cross-pod gate stays authoritative and stays BEFORE the handler"
        1 * processedStripeEventRepository.save({ it.eventId == 'evt_x' })
    }

    def "a claim taken with no active transaction is unaffected"() {
        given: "unit-test / non-transactional callers have no rollback to observe"
        processedStripeEventRepository.existsByEventId('evt_plain') >> false

        when:
        def first  = service.claimStripeEvent('evt_plain', 'checkout.session.completed')
        def second = service.claimStripeEvent('evt_plain', 'checkout.session.completed')

        then:
        first
        !second
        notThrown(Exception)
    }

    // ── 2. Withdrawal transfer replay ───────────────────────────────

    def "a Transfer id already on a ledger row REFUSES the withdrawal"() {
        given: "Stripe replayed an earlier Transfer rather than creating a new one"
        transactionRepository.findByStripeReference('tr_replayed') >>
            new Transaction(id: 77L, type: 'WITHDRAW', status: 'COMPLETED',
                            amount: new BigDecimal('50.00'), stripeReference: 'tr_replayed')

        when:
        service.assertTransferNotAlreadyRecorded('tr_replayed', 900L, 'wd_900_5000_1')

        then: "fail closed — one payout must never be debited twice"
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAWAL_TOO_SOON'
    }

    def "a genuinely new Transfer id passes through"() {
        given:
        transactionRepository.findByStripeReference('tr_fresh') >> null

        when:
        service.assertTransferNotAlreadyRecorded('tr_fresh', 900L, 'wd_900_5000_2')

        then: "the ordinary happy path must not be broken by the guard"
        notThrown(Exception)
    }

    def "a null Transfer id is not treated as a replay"() {
        when:
        service.assertTransferNotAlreadyRecorded(null, 900L, 'wd_900_5000_3')

        then:
        notThrown(Exception)
        0 * transactionRepository.findByStripeReference(_)
    }

    def "the withdrawal path actually calls the guard, right after Transfer.create"() {
        given: "the guard is only worth having if the money path consults it"
        def src = new File('src/main/groovy/com/sboxmarket/service/StripeService.groovy').text
        int created  = src.indexOf('Transfer.create(transferParams, reqOpts)')
        int guarded  = src.indexOf('assertTransferNotAlreadyRecorded(transfer.id')
        int recorded = src.indexOf('stripeRef = transfer.id')

        expect: "all three land in the withdrawal path"
        created  >= 0
        guarded  >= 0
        recorded >= 0

        and: "the guard runs AFTER the transfer exists and BEFORE its id is committed to a row"
        // Ordering is the whole property: checking before Transfer.create has
        // nothing to check, and checking after the row is written is too late.
        // Asserted by position rather than by a character budget so editing the
        // explanatory comment between them cannot silently void the test.
        created < guarded
        guarded < recorded

        and: "and the refusal is rethrown untouched rather than relabelled as a Stripe outage"
        src.contains('catch (com.sboxmarket.exception.BadRequestException replay)')
    }

    // ── 3. Refund idempotency ───────────────────────────────────────

    def "the refund call carries an idempotency key anchored on the ledger state"() {
        given: "Refund.create was the only money-moving Stripe call without one"
        def src = new File('src/main/groovy/com/sboxmarket/service/StripeService.groovy').text

        expect: "a timed-out refund that Stripe committed must not be re-issued on retry"
        src.contains('Refund.create(refundParams, refundOpts)')

        and: "keyed on deposit + amount + amount-already-refunded, so a retry reproduces"
        // the same key (self-repairing) while a genuine second partial gets a
        // new one.
        src.contains('rf_${tx.id}_${(amount * 100).longValue()}_${(refundedNow * 100).longValue()}')
    }
}
