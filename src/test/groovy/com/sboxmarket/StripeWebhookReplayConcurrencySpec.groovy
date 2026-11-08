package com.sboxmarket

import com.sboxmarket.controller.StripeWebhookController
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import com.stripe.Stripe
import com.stripe.model.checkout.Session
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.AopTestUtils
import spock.lang.Specification

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Stripe webhook replay on a real database, against the real UNIQUE index on
 * processed_stripe_events.event_id.
 *
 * The test profile builds the schema with ddl-auto, which does not create the
 * V230 unique index (the entity declares none), so setup() creates exactly the
 * index the prod migration creates. Without it the dedupe claim would have no
 * database backstop at all and this spec would be testing nothing.
 *
 * The same signed event is delivered concurrently from N threads through
 * StripeWebhookController (so we see the HTTP status Stripe would see), then
 * sequentially after a delay, both on the same "pod" (in-memory fast-path
 * cache warm) and on a "sibling pod" (cache cleared, so only the DB row can
 * dedupe). Every non-200 the first burst produced is then retried, the way
 * Stripe retries a 5xx.
 *
 * Stripe is never contacted: isLive() is stubbed on the StripeService bean's
 * target and Session.retrieve is replaced on its metaclass, as
 * RefundCreatedSpecSupport does.
 */
@SpringBootTest
@ActiveProfiles("test")
class StripeWebhookReplayConcurrencySpec extends Specification {

    static final int THREADS = 8

    @Autowired ApplicationContext ctx

    StripeWebhookController controller
    StripeService           stripeService
    WalletRepository        walletRepo
    TransactionRepository   txRepo
    JdbcTemplate            jdbc
    Object                  stripeTarget
    boolean                 createdIndex = false

    /** session id → [walletId, cents] the stubbed Session.retrieve reports as paid. */
    final Map<String, Map> paidSessions = Collections.synchronizedMap([:])

    def setup() {
        controller    = ctx.getBean(StripeWebhookController)
        stripeService = ctx.getBean(StripeService)
        walletRepo    = ctx.getBean(WalletRepository)
        txRepo        = ctx.getBean(TransactionRepository)
        jdbc          = ctx.getBean(JdbcTemplate)
        stripeTarget  = AopTestUtils.getUltimateTargetObject(stripeService)
        stripeTarget.metaClass.isLive = { -> true }
        def sessions = paidSessions
        Session.metaClass.static.retrieve = { String id ->
            def info = sessions[id]
            def s = new Session()
            s.id = id
            s.paymentIntent = "pi_${id}".toString()
            s.paymentStatus = info ? 'paid' : 'unpaid'
            s.currency = 'usd'
            if (info) {
                s.metadata = [walletId: info.walletId.toString()]
                s.amountTotal = info.cents as Long
            }
            s
        }
        createdIndex = ensureProdUniqueIndex()
    }

    def cleanup() {
        stripeTarget.metaClass = null
        GroovySystem.metaClassRegistry.removeMetaClass(Session)
        if (createdIndex) jdbc.execute('DROP INDEX IF EXISTS uq_processed_stripe_events_event_id')
    }

    // ── features ──────────────────────────────────────────────────

    def "checkout.session.completed delivered by eight threads at once, then replayed, credits the deposit once"() {
        given: 'a PENDING \$50.00 Checkout deposit (fee \$1.75) on an empty wallet'
        def uniq = System.nanoTime()
        def d = pendingDeposit("wrc1_${uniq}", new BigDecimal('50.00'), new BigDecimal('1.75'))
        def evtId = "evt_wrc1_${uniq}".toString()
        def payload = sessionPayload(evtId, 'checkout.session.completed', d.sid, 5000L, d.wallet.id)

        when: 'the same event lands on eight threads released by one latch'
        def burst = deliverConcurrently(payload, THREADS)
        println "[webhook-replay deposit] burst=${burst.countBy { it }}"

        and: 'Stripe re-delivers it later: same pod (warm cache), then a sibling pod (cold cache)'
        Thread.sleep(1500)
        def samePod = deliver(payload)
        clearLocalDedupeCache()
        def siblingPod = deliver(payload)

        and: 'every non-200 from the burst is retried, as Stripe retries a 5xx'
        def retries = burst.findAll { it != 200 }.collect { deliver(payload) }
        println "[webhook-replay deposit] samePod=${samePod} siblingPod=${siblingPod} retries=${retries}"

        then: 'at least one delivery handled it, and nothing other than 200/500 came back'
        burst.count { it == 200 } >= 1
        burst.every { it in [200, 500] }

        and: 'all replays and retries are clean 200 no-ops'
        samePod == 200
        siblingPod == 200
        retries.every { it == 200 }

        and: 'the wallet was credited exactly once: gross minus fee'
        balance(d.wallet) == new BigDecimal('48.25')
        txRepo.findById(d.tx.id).get().status == 'COMPLETED'
        depositRows(d.wallet).size() == 1

        and: 'exactly one claim row for the event id survived the unique index'
        claimRows(evtId) == 1

        and: 'the wallet balance is exactly what its ledger rows say'
        balance(d.wallet) == ledgerBalance(d.wallet)
    }

    def "checkout.session.completed and async_payment_succeeded for one payment, each replayed concurrently, credit once"() {
        given: 'a PENDING \$80.00 deposit paid by a delayed method: Stripe sends both events for the same session'
        def uniq = System.nanoTime()
        def d = pendingDeposit("wrc2_${uniq}", new BigDecimal('80.00'), BigDecimal.ZERO.setScale(2))
        def evtA = "evt_wrc2a_${uniq}".toString()
        def evtB = "evt_wrc2b_${uniq}".toString()
        def payloadA = sessionPayload(evtA, 'checkout.session.completed', d.sid, 8000L, d.wallet.id)
        def payloadB = sessionPayload(evtB, 'checkout.session.async_payment_succeeded', d.sid, 8000L, d.wallet.id)

        when: 'both events land at once, each on four threads'
        def burst = deliverConcurrently([payloadA, payloadB], THREADS)
        println "[webhook-replay two-events] burst=${burst.collect { it.status }.countBy { it }}"
        Thread.sleep(1000)
        clearLocalDedupeCache()
        def later = [deliver(payloadA), deliver(payloadB)]
        def retries = burst.findAll { it.status != 200 }.collect { deliver(it.payload) }

        then:
        burst.every { it.status in [200, 500] }
        later == [200, 200]
        retries.every { it == 200 }

        and: 'one payment, one credit'
        balance(d.wallet) == new BigDecimal('80.00')
        depositRows(d.wallet).size() == 1
        txRepo.findById(d.tx.id).get().status == 'COMPLETED'
        claimRows(evtA) == 1
        claimRows(evtB) == 1
        balance(d.wallet) == ledgerBalance(d.wallet)
    }

    def "transfer.reversed delivered by eight threads at once, then replayed, re-credits the payout once"() {
        given: 'a wallet that deposited \$100, withdrew \$40 by Stripe transfer, and holds \$60'
        def uniq = System.nanoTime()
        def w = walletRepo.save(new Wallet(username: "steam_wrc3_${uniq}", balance: new BigDecimal('60.00'), currency: 'USD'))
        txRepo.save(new Transaction(walletId: w.id, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('100.00'), feeAmount: BigDecimal.ZERO.setScale(2), currency: 'USD',
            stripeReference: "cs_test_wrc3_${uniq}".toString(), description: 'Deposit'))
        def trId = "tr_wrc3_${uniq}".toString()
        def wd = txRepo.save(new Transaction(walletId: w.id, type: 'WITHDRAW', status: 'COMPLETED',
            amount: new BigDecimal('40.00'), feeAmount: BigDecimal.ZERO.setScale(2), currency: 'USD',
            stripeReference: trId, description: 'Withdrawal'))
        assert balance(w) == ledgerBalance(w)
        def evtId = "evt_wrc3_${uniq}".toString()
        def payload = transferReversedPayload(evtId, trId, 4000L)

        when:
        def burst = deliverConcurrently(payload, THREADS)
        println "[webhook-replay transfer.reversed] burst=${burst.countBy { it }}"
        Thread.sleep(1500)
        def samePod = deliver(payload)
        clearLocalDedupeCache()
        def siblingPod = deliver(payload)
        def retries = burst.findAll { it != 200 }.collect { deliver(payload) }
        println "[webhook-replay transfer.reversed] samePod=${samePod} siblingPod=${siblingPod} retries=${retries}"

        then:
        burst.count { it == 200 } >= 1
        burst.every { it in [200, 500] }
        samePod == 200
        siblingPod == 200
        retries.every { it == 200 }

        and: 'the \$40 came back exactly once'
        balance(w) == new BigDecimal('100.00')
        txRepo.findById(wd.id).get().status == 'REVERSED'
        claimRows(evtId) == 1

        and: 'the wallet balance is exactly what its ledger rows say'
        balance(w) == ledgerBalance(w)
    }

    // ── fixtures ──────────────────────────────────────────────────

    /** Creates the index V230 creates in prod, unless it already exists. Returns true if this spec created it. */
    boolean ensureProdUniqueIndex() {
        def exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(INDEX_NAME) = 'UQ_PROCESSED_STRIPE_EVENTS_EVENT_ID'",
            Integer) > 0
        if (exists) return false
        jdbc.execute('CREATE UNIQUE INDEX uq_processed_stripe_events_event_id ON processed_stripe_events (event_id)')
        true
    }

    Map pendingDeposit(String tag, BigDecimal gross, BigDecimal fee) {
        def w = walletRepo.save(new Wallet(username: "steam_${tag}", balance: BigDecimal.ZERO.setScale(2), currency: 'USD'))
        def sid = "cs_test_${tag}".toString()
        def tx = txRepo.save(new Transaction(walletId: w.id, type: 'DEPOSIT', status: 'PENDING',
            amount: gross, feeAmount: fee, currency: 'USD', stripeReference: sid,
            description: "Stripe Checkout deposit \$${gross}".toString()))
        paidSessions[sid] = [walletId: w.id, cents: (gross * 100).longValueExact()]
        [wallet: w, tx: tx, sid: sid]
    }

    BigDecimal balance(Wallet w) { walletRepo.findById(w.id).get().balance }

    List<Transaction> depositRows(Wallet w) {
        txRepo.findByWalletIdOrderByCreatedAtDesc(w.id).findAll { it.type == 'DEPOSIT' && it.status == 'COMPLETED' }
    }

    /** What the wallet should hold according to its own ledger: net credited deposits minus settled withdrawals. */
    BigDecimal ledgerBalance(Wallet w) {
        def rows = txRepo.findByWalletIdOrderByCreatedAtDesc(w.id)
        def zero = BigDecimal.ZERO.setScale(2)
        def deposits = rows.findAll { it.type == 'DEPOSIT' && it.status == 'COMPLETED' }
            .sum(zero) { (it.amount ?: zero) - (it.feeAmount ?: zero) } as BigDecimal
        def withdrawals = rows.findAll { it.type in ['WITHDRAW', 'WITHDRAWAL'] && it.status == 'COMPLETED' }
            .sum(zero) { it.amount ?: zero } as BigDecimal
        def other = rows.findAll { !(it.type in ['DEPOSIT', 'WITHDRAW', 'WITHDRAWAL']) }
        assert other.isEmpty(): "unexpected ledger rows ${other*.type}"
        deposits - withdrawals
    }

    int claimRows(String eventId) {
        jdbc.queryForObject('SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?', Integer, eventId)
    }

    /** A sibling pod has never seen the event: only the DB claim row can dedupe it there. */
    void clearLocalDedupeCache() {
        synchronized (stripeTarget) { stripeTarget.@seenEventIds.clear() }
    }

    // ── delivery ──────────────────────────────────────────────────

    int deliver(String payload) {
        controller.webhook(payload, sign(payload)).statusCode.value()
    }

    List<Integer> deliverConcurrently(String payload, int n) {
        deliverConcurrently([payload], n)*.status
    }

    /** n threads, payloads assigned round-robin, all released by one latch. */
    List<Map> deliverConcurrently(List<String> payloads, int n) {
        def pool = Executors.newFixedThreadPool(n)
        def ready = new CountDownLatch(n)
        def go = new CountDownLatch(1)
        try {
            def futures = (0..<n).collect { i ->
                def p = payloads[i % payloads.size()]
                def sig = sign(p)
                pool.submit({ ->
                    ready.countDown()
                    go.await()
                    int status
                    try { status = controller.webhook(p, sig).statusCode.value() }
                    catch (Throwable t) { status = -1; t.printStackTrace() }
                    [payload: p, status: status]
                } as Callable<Map>)
            }
            assert ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            futures.collect { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    String sessionPayload(String eventId, String type, String sid, long cents, Long walletId) {
        """{"id":"${eventId}","object":"event","api_version":"${Stripe.API_VERSION}","created":${(long) (System.currentTimeMillis() / 1000)},"type":"${type}","livemode":false,"pending_webhooks":1,"data":{"object":{"id":"${sid}","object":"checkout.session","mode":"payment","payment_status":"paid","status":"complete","amount_total":${cents},"currency":"usd","payment_intent":"pi_${sid}","metadata":{"walletId":"${walletId}"}}}}"""
    }

    String transferReversedPayload(String eventId, String trId, long cents) {
        """{"id":"${eventId}","object":"event","api_version":"${Stripe.API_VERSION}","created":${(long) (System.currentTimeMillis() / 1000)},"type":"transfer.reversed","livemode":false,"pending_webhooks":1,"data":{"object":{"id":"${trId}","object":"transfer","amount":${cents},"amount_reversed":${cents},"currency":"usd","reversed":true}}}"""
    }

    String sign(String payload) {
        long ts = (long) (System.currentTimeMillis() / 1000)
        def mac = Mac.getInstance('HmacSHA256')
        mac.init(new SecretKeySpec(stripeTarget.webhookSecret.getBytes('UTF-8'), 'HmacSHA256'))
        def hex = mac.doFinal("${ts}.${payload}".toString().getBytes('UTF-8')).encodeHex().toString()
        "t=${ts},v1=${hex}".toString()
    }
}
