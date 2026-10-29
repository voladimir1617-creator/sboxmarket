package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import com.stripe.Stripe
import com.stripe.model.Refund
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.param.RefundCreateParams
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.AopTestUtils
import spock.lang.Specification

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared real-database plumbing for the refund.created specs. Stripe is never
 * contacted: isLive() is stubbed on the StripeService bean's target, and the
 * two static Stripe calls refundDeposit makes (Session.retrieve, Refund.create)
 * are replaced on their metaclasses for the duration of one feature.
 */
abstract class RefundCreatedSpecSupport extends Specification {

    @Autowired ApplicationContext ctx

    StripeService         stripeService
    WalletRepository      walletRepo
    TransactionRepository txRepo
    JdbcTemplate          jdbc
    Object                stripeTarget

    /** What the stubbed Refund.create does; set per feature. */
    Closure<Refund> onRefundCreate = { RefundCreateParams p -> refund("re_${System.nanoTime()}", p.amount, null) }

    def setup() {
        stripeService = ctx.getBean(StripeService)
        walletRepo    = ctx.getBean(WalletRepository)
        txRepo        = ctx.getBean(TransactionRepository)
        jdbc          = ctx.getBean(JdbcTemplate)
        stripeTarget  = AopTestUtils.getUltimateTargetObject(stripeService)
        stripeTarget.metaClass.isLive = { -> true }
        Session.metaClass.static.retrieve = { String id ->
            def s = new Session(); s.id = id; s.paymentIntent = "pi_${id}".toString(); s
        }
        def self = this
        Refund.metaClass.static.create = { RefundCreateParams p, RequestOptions o -> self.onRefundCreate.call(p) }
    }

    def cleanup() {
        stripeTarget.metaClass = null
        GroovySystem.metaClassRegistry.removeMetaClass(Session)
        GroovySystem.metaClassRegistry.removeMetaClass(Refund)
    }

    // ── fixtures ──────────────────────────────────────────────────

    /** A COMPLETED live deposit tagged with its payment intent, on a wallet holding {@code walletBalance}. */
    Map deposit(String tag, BigDecimal gross, BigDecimal walletBalance) {
        def w = walletRepo.save(new Wallet(username: "steam_${tag}", balance: walletBalance, currency: 'USD'))
        def sid = "cs_test_${tag}".toString()
        def pi = "pi_${sid}".toString()
        def tx = txRepo.save(new Transaction(walletId: w.id, type: 'DEPOSIT', status: 'COMPLETED',
            amount: gross, currency: 'USD', stripeReference: sid,
            description: "Deposit \$${gross} [pi:${pi}]".toString()))
        [wallet: w, tx: tx, sid: sid, pi: pi]
    }

    static Refund refund(String id, Long cents, String pi) {
        def r = new Refund(); r.id = id; r.amount = cents; r.paymentIntent = pi; r.currency = 'usd'; r.status = 'succeeded'; r
    }

    BigDecimal balance(Map d) { walletRepo.findById(d.wallet.id).get().balance }

    List<Transaction> refundRows(Map d) {
        txRepo.findByWalletIdOrderByCreatedAtDesc(d.wallet.id).findAll { it.type == 'REFUND' }
    }

    BigDecimal refundedTotal(Map d) { (txRepo.sumRefundsByDeposit(d.tx.id) ?: BigDecimal.ZERO) as BigDecimal }

    String attempt(Closure c) {
        try { c.call(); 'OK' } catch (Exception e) { e.class.simpleName }
    }

    boolean awaitBlockedSessions(int n, long timeoutMs = 10_000) {
        long deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            def c = jdbc.queryForObject(
                'SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL', Integer)
            if (c >= n) return true
            Thread.onSpinWait()
        }
        false
    }

    // ── signed webhook delivery ───────────────────────────────────

    String refundCreatedPayload(String eventId, String refundId, long cents, String pi) {
        """{"id":"${eventId}","object":"event","api_version":"${Stripe.API_VERSION}","created":${(long) (System.currentTimeMillis() / 1000)},"type":"refund.created","livemode":false,"pending_webhooks":1,"data":{"object":{"id":"${refundId}","object":"refund","amount":${cents},"currency":"usd","payment_intent":"${pi}","status":"succeeded"}}}"""
    }

    String sign(String payload) {
        long ts = (long) (System.currentTimeMillis() / 1000)
        def mac = Mac.getInstance('HmacSHA256')
        mac.init(new SecretKeySpec(stripeTarget.webhookSecret.getBytes('UTF-8'), 'HmacSHA256'))
        def hex = mac.doFinal("${ts}.${payload}".toString().getBytes('UTF-8')).encodeHex().toString()
        "t=${ts},v1=${hex}".toString()
    }

    void deliver(String payload) { stripeService.handleWebhookEvent(payload, sign(payload)) }
}
