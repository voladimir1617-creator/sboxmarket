package com.sboxmarket

import com.sboxmarket.controller.StripeWebhookController
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ProcessedStripeEventRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import com.stripe.exception.EventDataObjectDeserializationException
import com.stripe.model.Event
import com.stripe.model.EventDataObjectDeserializer
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

/**
 * Audit 2026-10-29.
 *
 * 1. buyer-deposits H1 / seller-payouts H1: on an endpoint whose API version
 *    is not the SDK's pinned 2023-10-16, getObject() is empty. Every handled
 *    case skipped it, the event was claimed and ACKed 200, and Stripe never
 *    retried. Now deserializeUnsafe() is tried, and if that fails too the
 *    webhook returns 500 and the claim is released so the retry runs.
 * 2. seller-payouts L1: a missing wallet on transfer.reversed was thrown as
 *    IllegalState, which the controller ACKs 200. It must be a 500.
 * 3. buyer-deposits H2: the 48h sweeper expired deposits Stripe reports as
 *    paid. It must credit them through completeDeposit, once, and still
 *    expire the unpaid ones.
 */
class StripeWebhookApiVersionAndPaidSweepSpec extends Specification {

    WalletRepository               walletRepository               = Mock()
    TransactionRepository          transactionRepository          = Mock()
    ProcessedStripeEventRepository processedStripeEventRepository = Mock()

    private StripeService svc(String key) {
        new StripeService(
            walletRepository               : walletRepository,
            transactionRepository          : transactionRepository,
            processedStripeEventRepository : processedStripeEventRepository,
            environment                    : SpecEnvs.env(),
            secretKey                      : key,
            publishableKey                 : 'pk_test_replace_me',
            webhookSecret                  : 'whsec_replace_me',
            successUrl                     : 'http://localhost/ok',
            cancelUrl                      : 'http://localhost/cancel',
            currency                       : 'usd')
    }

    def cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    /** An event rendered at a different API version: getObject() is empty. */
    private Event mismatchedEvent(String id, String type, Closure unsafe) {
        def deser = Mock(EventDataObjectDeserializer) {
            getObject() >> Optional.empty()
            deserializeUnsafe() >> { unsafe.call() }
        }
        Mock(Event) {
            getId() >> id
            getType() >> type
            getApiVersion() >> '2026-09-30.clover'
            getDataObjectDeserializer() >> deser
        }
    }

    private Event matchingEvent(String id, String type, Object obj) {
        def deser = Mock(EventDataObjectDeserializer) { getObject() >> Optional.of(obj) }
        Mock(Event) {
            getId() >> id
            getType() >> type
            getDataObjectDeserializer() >> deser
        }
    }

    // ── 1. API-version mismatch ───────────────────────────────────────

    def "an event whose getObject() is empty but deserializeUnsafe() works is processed"() {
        given:
        def service = Spy(svc('sk_test_replace_me'))
        def session = new Session(id: 'cs_version_drift')
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> mismatchedEvent('evt_drift', 'checkout.session.completed', { session })
        processedStripeEventRepository.existsByEventId(_) >> false

        when:
        def resp = new StripeWebhookController(stripeService: service).webhook('{}', 'sig')

        then: 'the deposit is completed, not silently skipped'
        1 * service.completeDeposit('cs_version_drift') >> null
        resp.statusCode.value() == 200
        resp.body == 'ok'
    }

    def "a dispute event on a mismatched API version still reaches the dispute handler"() {
        given:
        def service = Spy(svc('sk_test_replace_me'))
        def dispute = new com.stripe.model.Dispute(id: 'dp_drift', charge: 'ch_1')
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> mismatchedEvent('evt_dp', 'charge.dispute.created', { dispute })
        processedStripeEventRepository.existsByEventId(_) >> false

        when:
        service.handleWebhookEvent('{}', 'sig')

        then:
        1 * service.handleChargebackOpened(dispute) >> null
    }

    def "when both getObject() and deserializeUnsafe() fail the webhook returns 500, and Stripe's retry is processed"() {
        given: 'an active transaction, as handleWebhookEvent always has'
        def service = Spy(svc('sk_test_replace_me'))
        def controller = new StripeWebhookController(stripeService: service)
        TransactionSynchronizationManager.initSynchronization()
        GroovySpy(Webhook, global: true)
        def unreadable = mismatchedEvent('evt_unreadable', 'checkout.session.completed',
            { throw new EventDataObjectDeserializationException('cannot deserialize', '{}') })
        def readable = matchingEvent('evt_unreadable', 'checkout.session.completed', new Session(id: 'cs_retry'))
        Webhook.constructEvent(_, _, _) >>> [unreadable, readable]
        // The claim row rolled back with the failed delivery, so the ledger
        // never holds it when the retry arrives.
        processedStripeEventRepository.existsByEventId('evt_unreadable') >> false

        when: 'the first delivery cannot be read'
        def first = controller.webhook('{}', 'sig')

        then: 'a 5xx (not the 200 permanent-failure ACK), so Stripe retries; nothing was credited'
        first.statusCode.value() == 500
        0 * service.completeDeposit(_)

        when: 'the transaction rolls back, then Stripe retries the same event'
        TransactionSynchronizationManager.synchronizations.each {
            it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
        }
        TransactionSynchronizationManager.clearSynchronization()
        TransactionSynchronizationManager.initSynchronization()
        def retry = controller.webhook('{}', 'sig')

        then: 'the retry is not deduped away: it claims again and completes the deposit'
        1 * processedStripeEventRepository.save({ it.eventId == 'evt_unreadable' })
        1 * service.completeDeposit('cs_retry') >> null
        retry.statusCode.value() == 200
    }

    def "an event type we do not handle is still ACKed 200 even when its object is unreadable"() {
        given:
        def service = svc('sk_test_replace_me')
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> mismatchedEvent('evt_other', 'customer.created',
            { throw new EventDataObjectDeserializationException('cannot deserialize', '{}') })
        processedStripeEventRepository.existsByEventId(_) >> false

        expect:
        new StripeWebhookController(stripeService: service).webhook('{}', 'sig').statusCode.value() == 200
    }

    // ── 2. transfer.reversed with no wallet ─────────────────────────

    def "transfer.reversed with a missing wallet returns 500 so Stripe retries (was ACKed 200)"() {
        given:
        def service = svc('sk_test_replace_me')
        def transfer = new com.stripe.model.Transfer(id: 'tr_nowallet', amount: 2500L, amountReversed: 2500L)
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> matchingEvent('evt_rev', 'transfer.reversed', transfer)
        processedStripeEventRepository.existsByEventId(_) >> false
        transactionRepository.findByStripeReference('tr_nowallet') >> new Transaction(
            id: 7L, walletId: 3L, type: 'WITHDRAW', status: 'COMPLETED',
            amount: new BigDecimal('25.00'), stripeReference: 'tr_nowallet')
        transactionRepository.claimReverseWithdrawal(7L, _) >> 1
        walletRepository.findById(3L) >> Optional.empty()

        when:
        def resp = new StripeWebhookController(stripeService: service).webhook('{}', 'sig')

        then:
        resp.statusCode.value() == 500
        0 * walletRepository.save(_)
    }

    // ── 3. sweeper and paid sessions ────────────────────────────────

    def "sweep verdict for a session Stripe reports as #status/#paymentStatus is #expected"() {
        given:
        def service = svc('sk_live_real_key')
        GroovySpy(Session, global: true)
        Session.retrieve('cs_x') >> new Session(id: 'cs_x', status: status, paymentStatus: paymentStatus)

        expect:
        service.depositSweepVerdict(new Transaction(id: 1L, stripeReference: 'cs_x')) == expected

        where:
        status     | paymentStatus || expected
        'complete' | 'paid'        || StripeService.SWEEP_PAID
        'complete' | 'unpaid'      || StripeService.SWEEP_KEEP
        'expired'  | 'unpaid'      || StripeService.SWEEP_EXPIRE
        'open'     | 'unpaid'      || StripeService.SWEEP_EXPIRE
    }

    def "sweep verdict keeps the row PENDING when Stripe cannot be asked"() {
        given:
        def service = svc('sk_live_real_key')
        GroovySpy(Session, global: true)
        Session.retrieve('cs_x') >> { throw new RuntimeException('stripe down') }

        expect:
        service.depositSweepVerdict(new Transaction(id: 1L, stripeReference: 'cs_x')) == StripeService.SWEEP_KEEP
    }

    def "the sweeper credits a paid PENDING deposit exactly once and still expires an unpaid one"() {
        given:
        def service = Spy(svc('sk_live_real_key'))
        def paid = new Transaction(id: 10L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('40.00'), currency: 'USD', stripeReference: 'cs_paid_no_webhook',
            description: 'Stripe Checkout deposit')
        def unpaid = new Transaction(id: 11L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('20.00'), currency: 'USD', stripeReference: 'cs_abandoned')
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('5.00'))
        transactionRepository.findStalePending('DEPOSIT', _, _) >> [paid, unpaid]
        service.depositSweepVerdict(paid) >> StripeService.SWEEP_PAID
        service.depositSweepVerdict(unpaid) >> StripeService.SWEEP_EXPIRE
        transactionRepository.findByStripeReference('cs_paid_no_webhook') >> paid
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        service.sweepStalePendingDeposits()

        then: 'the paid row goes through completeDeposit and its Stripe verification, and is never expired'
        1 * service.assertDepositPaidAtStripe('cs_paid_no_webhook', paid) >> new Session(paymentIntent: 'pi_1')
        1 * transactionRepository.claimCompletePendingDeposit(10L, _) >> 1
        1 * walletRepository.save({ Wallet w -> w.balance == new BigDecimal('45.00') })
        0 * transactionRepository.claimExpirePending(10L)

        and: 'the unpaid row is expired as before'
        1 * transactionRepository.claimExpirePending(11L) >> 1
        paid.status == 'COMPLETED'

        when: "the late webhook for the same session arrives after the sweeper credited it"
        service.completeDeposit('cs_paid_no_webhook')

        then: 'no second credit'
        0 * transactionRepository.claimCompletePendingDeposit(_, _)
        0 * walletRepository.save(_)
        wallet.balance == new BigDecimal('45.00')
    }

    def "a webhook that wins the credit claim concurrently stops the sweeper crediting a second time"() {
        given:
        def service = Spy(svc('sk_live_real_key'))
        def paid = new Transaction(id: 12L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('40.00'), currency: 'USD', stripeReference: 'cs_race')
        transactionRepository.findStalePending('DEPOSIT', _, _) >> [paid]
        service.depositSweepVerdict(paid) >> StripeService.SWEEP_PAID
        service.assertDepositPaidAtStripe(_, _) >> new Session(paymentIntent: 'pi_2')
        transactionRepository.findByStripeReference('cs_race') >> paid

        when:
        service.sweepStalePendingDeposits()

        then:
        1 * transactionRepository.claimCompletePendingDeposit(12L, _) >> 0
        0 * walletRepository.save(_)
        0 * transactionRepository.claimExpirePending(_)
    }

    def "a paid deposit that completeDeposit refuses is left PENDING, never expired"() {
        given:
        def service = Spy(svc('sk_live_real_key'))
        def paid = new Transaction(id: 13L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('40.00'), stripeReference: 'cs_mismatch')
        transactionRepository.findStalePending('DEPOSIT', _, _) >> [paid]
        service.depositSweepVerdict(paid) >> StripeService.SWEEP_PAID
        service.completeDeposit('cs_mismatch') >> { throw new IllegalStateException('Amount mismatch') }

        when:
        service.sweepStalePendingDeposits()

        then:
        0 * transactionRepository.claimExpirePending(_)
        noExceptionThrown()
    }
}
