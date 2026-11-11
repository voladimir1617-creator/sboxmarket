package com.sboxmarket

import com.stripe.model.Refund
import com.stripe.param.RefundCreateParams
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * handleRefundCreated (~2484) dedupes on findByStripeReference(refundId) and
 * only LATER reads the wallet with a plain findById. Nothing holds a lock
 * between the two reads. If the other writer of that same refund (our admin
 * refundDeposit, or a second delivery) commits inside that window, the handler
 * has already decided "not seen" but then reads the post-commit wallet with its
 * new @Version, so the optimistic lock no longer catches it.
 *
 * The window is made deterministic with a Refund whose getPaymentIntent() —
 * the first thing the handler touches after the dedupe read — waits for the
 * admin transaction to commit. In production the same gap is the
 * findDepositByPaymentIntent scan over every COMPLETED + DISPUTED deposit, or
 * the Charge.retrieve network call when the refund has no payment_intent.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundCreatedDedupeWindowSpec extends RefundCreatedSpecSupport {

    def "an admin refund racing its own refund.created webhook debits the wallet once"() {
        given: 'a \$100 deposit; the admin refunds \$50 through our panel'
        def uniq = System.nanoTime()
        def d = deposit("rcw1_${uniq}", new BigDecimal('100.00'), new BigDecimal('100.00'))
        def reId = "re_rcw1_${uniq}".toString()
        def pastDedupe = new CountDownLatch(1)
        def adminCommitted = new CountDownLatch(1)
        def pool = Executors.newFixedThreadPool(2)
        Future<String> webhook = null
        // Stripe sends refund.created as soon as the refund exists. getPaymentIntent() is the first
        // thing the handler reads after its dedupe lookup; it parks there until the admin commits.
        boolean first = true
        Refund delivered = Spy(Refund) {
            getPaymentIntent() >> {
                if (first) { first = false; pastDedupe.countDown(); adminCommitted.await(15, TimeUnit.SECONDS) }
                d.pi
            }
        }
        delivered.id = reId; delivered.amount = 5000L; delivered.currency = 'usd'; delivered.status = 'succeeded'
        onRefundCreate = { RefundCreateParams p ->
            webhook = pool.submit({ -> attempt { stripeService.handleRefundCreated(delivered) } } as Callable<String>)
            assert pastDedupe.await(10, TimeUnit.SECONDS)   // webhook already looked for re_X and found nothing
            refund(reId, p.amount, d.pi)
        }

        when: 'the admin refund commits while the webhook is between its dedupe read and its wallet read'
        def adminOutcome = attempt { stripeService.refundDeposit(d.tx.id, new BigDecimal('50.00'), null) }
        adminCommitted.countDown()
        def webhookOutcome = webhook.get(20, TimeUnit.SECONDS)
        pool.shutdown()
        println "[refund-dedupe-window] admin=${adminOutcome} webhook=${webhookOutcome} " +
                "balance=${balance(d)} refundRows=${refundRows(d)*.stripeReference}"

        then: 'the user lost \$50, once; one REFUND row for re_X; ledger refunds == what Stripe refunded'
        adminOutcome == 'OK'
        balance(d) == new BigDecimal('50.00')
        refundRows(d).size() == 1
        refundedTotal(d) == new BigDecimal('50.00')
    }
}
