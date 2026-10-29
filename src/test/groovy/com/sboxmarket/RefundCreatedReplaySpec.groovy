package com.sboxmarket

import com.stripe.model.Refund
import com.stripe.param.RefundCreateParams
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * refund.created (StripeService.handleRefundCreated ~2484) on a real database:
 * replays, re-deliveries, the admin refund's own webhook landing while the
 * admin transaction still holds the wallet, and a refund larger than what is
 * left in the wallet. The wallet must be debited once per Stripe refund, never
 * go negative, and the ledger must record exactly what left Stripe.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundCreatedReplaySpec extends RefundCreatedSpecSupport {

    def "a replayed refund.created event (same event id) debits the wallet once"() {
        given: 'a \$100 deposit with \$100 in the wallet, refunded \$40 from the Stripe Dashboard'
        def uniq = System.nanoTime()
        def d = deposit("rcr1_${uniq}", new BigDecimal('100.00'), new BigDecimal('100.00'))
        def payload = refundCreatedPayload("evt_rcr1_${uniq}", "re_rcr1_${uniq}", 4000L, d.pi)

        when: 'Stripe delivers the event, then retries it twice'
        def outcomes = (1..3).collect { attempt { deliver(payload) } }

        then:
        outcomes == ['OK', 'OK', 'OK']
        balance(d) == new BigDecimal('60.00')
        refundRows(d).size() == 1
        refundRows(d)[0].amount == new BigDecimal('40.00')
        refundedTotal(d) == new BigDecimal('40.00')
    }

    def "the same Stripe refund re-delivered under a new event id debits the wallet once"() {
        given:
        def uniq = System.nanoTime()
        def d = deposit("rcr2_${uniq}", new BigDecimal('100.00'), new BigDecimal('100.00'))
        def reId = "re_rcr2_${uniq}".toString()

        when: 'two distinct events carry the same refund object'
        def a = attempt { deliver(refundCreatedPayload("evt_rcr2a_${uniq}", reId, 2500L, d.pi)) }
        def b = attempt { deliver(refundCreatedPayload("evt_rcr2b_${uniq}", reId, 2500L, d.pi)) }

        then:
        [a, b] == ['OK', 'OK']
        balance(d) == new BigDecimal('75.00')
        refundRows(d).size() == 1
    }

    def "an admin refund whose webhook arrives while the admin transaction still holds the wallet is debited once"() {
        given: 'a \$100 deposit; the admin refunds \$50 through our panel'
        def uniq = System.nanoTime()
        def d = deposit("rcr3_${uniq}", new BigDecimal('100.00'), new BigDecimal('100.00'))
        def reId = "re_rcr3_${uniq}".toString()
        def pool = Executors.newFixedThreadPool(2)
        Future<String> webhook = null
        onRefundCreate = { RefundCreateParams p ->
            // Stripe fires refund.created the moment the refund exists, before our admin tx commits.
            def r = refund(reId, p.amount, d.pi)
            webhook = pool.submit({ -> attempt { stripeService.handleRefundCreated(r) } } as Callable<String>)
            // return only once the webhook is parked behind the admin's wallet lock
            assert awaitBlockedSessions(1)
            r
        }

        when: 'the admin refund commits, then Stripe retries the webhook the first delivery 500-ed'
        def admin = pool.submit({ -> attempt { stripeService.refundDeposit(d.tx.id, new BigDecimal('50.00'), null) } } as Callable<String>)
        def adminOutcome = admin.get(20, TimeUnit.SECONDS)
        def firstDelivery = webhook.get(20, TimeUnit.SECONDS)
        def retry = attempt { stripeService.handleRefundCreated(refund(reId, 5000L, d.pi)) }
        pool.shutdown()
        println "[refund-admin-race-locked] admin=${adminOutcome} webhook=${firstDelivery} retry=${retry}"

        then: 'the wallet lost \$50 once and the ledger holds one REFUND row for that Stripe refund'
        adminOutcome == 'OK'
        retry == 'OK'
        balance(d) == new BigDecimal('50.00')
        refundRows(d).size() == 1
        refundRows(d)[0].stripeReference == reId
        refundedTotal(d) == new BigDecimal('50.00')
    }

    def "a Dashboard refund larger than the wallet balance clamps at zero and records the full refund"() {
        given: 'a \$100 deposit whose owner has already spent all but \$30'
        def uniq = System.nanoTime()
        def d = deposit("rcr4_${uniq}", new BigDecimal('100.00'), new BigDecimal('30.00'))

        when: 'a full \$100 refund lands'
        def outcome = attempt { deliver(refundCreatedPayload("evt_rcr4_${uniq}", "re_rcr4_${uniq}", 10000L, d.pi)) }

        then: 'the wallet never goes negative; the ledger shows what actually left Stripe'
        outcome == 'OK'
        balance(d) == BigDecimal.ZERO.setScale(2)
        refundRows(d).size() == 1
        refundRows(d)[0].amount == new BigDecimal('100.00')
    }
}
