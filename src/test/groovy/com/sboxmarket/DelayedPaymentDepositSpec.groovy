package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ProcessedStripeEventRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import com.stripe.model.Event
import com.stripe.model.EventDataObjectDeserializer
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import spock.lang.Specification

/**
 * 2026-09-28 payments review.
 *
 * 1. Delayed payment methods (SEPA Debit, ACH, ...): `checkout.session.completed`
 *    arrives unpaid and the money settles days later. The async webhooks must
 *    credit / fail the deposit, and the 48h sweeper must not expire a deposit
 *    that is still settling, or the paid money is never credited.
 * 2. The `[pi:]` tag that links a deposit to disputes and Dashboard refunds is
 *    taken from the session completeDeposit just verified, not from a second,
 *    best-effort retrieve whose failure left the deposit untraceable.
 */
class DelayedPaymentDepositSpec extends Specification {

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

    private Event sessionEvent(String id, String type, Session session) {
        def deser = Mock(EventDataObjectDeserializer) { getObject() >> Optional.of(session) }
        Mock(Event) {
            getId() >> id
            getType() >> type
            getDataObjectDeserializer() >> deser
        }
    }

    // ── 1. async webhook cases ─────────────────────────────────────────

    def "checkout.session.async_payment_succeeded completes the deposit"() {
        given:
        def service = Spy(svc('sk_test_replace_me'))
        def session = new Session(id: 'cs_sepa_paid')
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> sessionEvent('evt_async_ok', 'checkout.session.async_payment_succeeded', session)
        processedStripeEventRepository.existsByEventId(_) >> false

        when:
        service.handleWebhookEvent('{}', 'sig')

        then:
        1 * service.completeDeposit('cs_sepa_paid') >> null
        0 * service.failTransaction(_, _)
    }

    def "checkout.session.async_payment_failed fails the deposit"() {
        given:
        def service = Spy(svc('sk_test_replace_me'))
        def session = new Session(id: 'cs_sepa_bounced')
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> sessionEvent('evt_async_fail', 'checkout.session.async_payment_failed', session)
        processedStripeEventRepository.existsByEventId(_) >> false

        when:
        service.handleWebhookEvent('{}', 'sig')

        then:
        1 * service.failTransaction('cs_sepa_bounced', 'async_payment_failed') >> null
        0 * service.completeDeposit(_)
    }

    // ── 1b. the sweeper leaves a settling deposit PENDING ─────────────

    def "the 48h sweeper does not expire a deposit whose delayed payment is still settling"() {
        given:
        def service = Spy(svc('sk_live_real_key'))
        def settling = new Transaction(id: 7L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('50.00'), stripeReference: 'cs_sepa_settling')
        def abandoned = new Transaction(id: 8L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('20.00'), stripeReference: 'cs_abandoned')
        transactionRepository.findStalePending('DEPOSIT', _, _) >> [settling, abandoned]
        service.depositAwaitingAsyncPayment(settling) >> true
        service.depositAwaitingAsyncPayment(abandoned) >> false

        when:
        service.sweepStalePendingDeposits()

        then:
        0 * transactionRepository.claimExpirePending(7L)
        1 * transactionRepository.claimExpirePending(8L) >> 1
    }

    def "outside live mode no Stripe lookup is made and nothing is treated as settling"() {
        given:
        def service = svc('sk_test_replace_me')

        expect:
        !service.depositAwaitingAsyncPayment(new Transaction(id: 9L, stripeReference: 'cs_x'))
        !service.depositAwaitingAsyncPayment(new Transaction(id: 9L, stripeReference: 'dev_x'))
    }

    // ── 2. [pi:] tag from the verified session ───────────────────────

    def "completeDeposit tags the deposit with the payment_intent of the session it verified"() {
        given:
        def service = svc('sk_live_real_key')
        def verified = new Session(id: 'cs_paid', paymentIntent: 'pi_verified_123')
        service.metaClass.assertDepositPaidAtStripe = { String s, Transaction t -> verified }
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('100.00'), currency: 'USD', stripeReference: 'cs_paid',
            description: 'Stripe Checkout deposit')
        def wallet = new Wallet(id: 500L, balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('cs_paid') >> tx
        transactionRepository.claimCompletePendingDeposit(1L, _) >> 1
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        service.completeDeposit('cs_paid')

        then:
        wallet.balance == new BigDecimal('100.00')
        tx.description.contains('[pi:pi_verified_123]')
    }
}
