package com.sboxmarket

import com.stripe.param.RefundCreateParams
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * refundDeposit (the admin path) refuses a refund larger than the deposit's
 * remaining refundable balance (sumRefundsByDeposit, checked twice, once under
 * the wallet lock). handleRefundCreated (~2484) has no such check: whatever
 * amount arrives is debited and recorded, so the ledger can show more refunded
 * than was ever deposited.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundCreatedOverRefundSpec extends RefundCreatedSpecSupport {

    def "a refund.created above the deposit's remaining refundable balance never takes the ledger past the deposit"() {
        given: 'a \$100 deposit, \$60 already refunded through the admin panel (and its webhook reconciled)'
        def uniq = System.nanoTime()
        def d = deposit("rco1_${uniq}", new BigDecimal('100.00'), new BigDecimal('100.00'))
        onRefundCreate = { RefundCreateParams p -> refund("re_rco1a_${uniq}", p.amount, d.pi) }
        stripeService.refundDeposit(d.tx.id, new BigDecimal('60.00'), null)
        deliver(refundCreatedPayload("evt_rco1a_${uniq}", "re_rco1a_${uniq}", 6000L, d.pi))
        assert balance(d) == new BigDecimal('40.00') && refundedTotal(d) == new BigDecimal('60.00')

        when: 'a second refund.created for \$60 arrives for the same payment intent (only \$40 is left)'
        def outcome = attempt { deliver(refundCreatedPayload("evt_rco1b_${uniq}", "re_rco1b_${uniq}", 6000L, d.pi)) }
        println "[refund-over] outcome=${outcome} balance=${balance(d)} refundedTotal=${refundedTotal(d)} rows=${refundRows(d)*.amount}"

        then: 'the ledger never records more refunded than the \$100 deposit'
        refundedTotal(d) <= new BigDecimal('100.00')
    }

    def "a refund.created the wallet cannot cover writes a MANUAL_PAYOUT_REQUIRED audit row, like the admin path"() {
        given: 'a \$100 deposit whose wallet has only \$30 left'
        def uniq = System.nanoTime()
        def d = deposit("rco2_${uniq}", new BigDecimal('100.00'), new BigDecimal('30.00'))

        when: 'a \$50 Dashboard refund arrives'
        deliver(refundCreatedPayload("evt_rco2_${uniq}", "re_rco2_${uniq}", 5000L, d.pi))

        then: 'the wallet is clamped at 0, the refund is recorded, and the \$20 shortfall is shown to ops'
        balance(d) == new BigDecimal('0.00')
        refundedTotal(d) == new BigDecimal('50.00')
        def refundRow = refundRows(d).find { it.stripeReference == "re_rco2_${uniq}".toString() }
        refundRow != null
        ctx.getBean(com.sboxmarket.repository.AuditLogRepository)
            .byEvent('MANUAL_PAYOUT_REQUIRED', org.springframework.data.domain.PageRequest.of(0, 50))
            .any { it.resourceId == refundRow.id }
    }
}
