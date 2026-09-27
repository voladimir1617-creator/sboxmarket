package com.sboxmarket

import com.sboxmarket.model.ProcessedStripeEvent
import com.sboxmarket.repository.ProcessedStripeEventRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import com.stripe.model.Event
import com.stripe.net.Webhook
import org.springframework.dao.DataIntegrityViolationException
import spock.lang.Specification
import spock.lang.Subject

/**
 * Wave 147 multi-pod webhook-dedup regression pin for
 * {@link StripeService}.
 *
 * AUDIT GAP CLOSED: the webhook event-id dedup was a per-JVM
 * {@code seenEventIds} LinkedHashSet only. On a multi-pod deploy a Stripe
 * webhook RETRY (Stripe retries on any non-2xx / transient network error)
 * can be routed to a DIFFERENT pod than the original — that pod's
 * in-memory set is empty, so it re-ran the side-effecting handler and
 * re-fired the admin bells / audit rows. (The money path is independently
 * safe: completeDeposit / failTransaction / the dispute + refund handlers
 * are each row-idempotent on the tx status / stripeReference they set.)
 *
 * FIX: a DB-backed CLAIM in {@code processed_stripe_events} — the same
 * wave-112-style "INSERT-or-skip, catch DataIntegrityViolation as a
 * duplicate" pattern used by FraudAnalysisService (V70 fraud_signal_claims)
 * and the sweep-claim conditional-UPDATE pattern across the schedulers.
 * The DB row is the authoritative cross-pod gate; {@code seenEventIds}
 * survives as a per-JVM fast-path cache.
 *
 * These tests pin three claim behaviours (both at the claimStripeEvent
 * unit level AND end-to-end through handleWebhookEvent):
 *
 *   1. FIRST event id → claim wins → handler runs.
 *   2. DUPLICATE event id (existsByEventId true) → claim loses → handler
 *      is NOT re-run (cross-pod retry / re-delivery dedup).
 *   3. CONCURRENT-INSERT race (both pods pass the existence check, the
 *      INSERT trips the UNIQUE index) → DataIntegrityViolationException is
 *      caught and treated as a duplicate → NO throw, handler NOT re-run.
 */
class StripeServiceWebhookDedupClaimSpec extends Specification {

    WalletRepository              walletRepository              = Mock()
    TransactionRepository         transactionRepository         = Mock()
    ProcessedStripeEventRepository processedStripeEventRepository = Mock()

    @Subject
    StripeService service = new StripeService(
        walletRepository:               walletRepository,
        transactionRepository:          transactionRepository,
        processedStripeEventRepository: processedStripeEventRepository,
        secretKey:                      'sk_test_replace_me',   // dev-mode key (unused — Webhook is spied)
        publishableKey:                 'pk_test_replace_me',
        webhookSecret:                  'whsec_replace_me',
        successUrl:                     'http://localhost/ok',
        cancelUrl:                      'http://localhost/cancel',
        currency:                       'usd'
    )

    // ── claimStripeEvent unit-level (the dedup gate's core) ───────────────

    def "claimStripeEvent INSERTs and returns true for a brand-new event id (this pod wins → handler should run)"() {
        given: 'the cluster ledger has never seen this id'
        processedStripeEventRepository.existsByEventId('evt_new') >> false

        when:
        def claimed = service.claimStripeEvent('evt_new', 'checkout.session.completed')

        then: 'the authoritative INSERT is attempted and the caller is cleared to proceed'
        claimed == true
        1 * processedStripeEventRepository.save({ ProcessedStripeEvent e ->
            e.eventId == 'evt_new' && e.eventType == 'checkout.session.completed' && e.processedAt != null
        })
    }

    def "claimStripeEvent returns false when a prior delivery / sibling pod already claimed the id (existence check) — no second INSERT"() {
        given: 'some pod already wrote the claim row'
        processedStripeEventRepository.existsByEventId('evt_dup') >> true

        when:
        def claimed = service.claimStripeEvent('evt_dup', 'checkout.session.completed')

        then: 'caller must SKIP the handler — and we never attempt a duplicate INSERT'
        claimed == false
        0 * processedStripeEventRepository.save(_)
    }

    def "claimStripeEvent catches a DataIntegrityViolationException from a concurrent INSERT and treats it as a duplicate (NO throw)"() {
        given: 'both pods pass the existence check, then our INSERT loses the race to the UNIQUE index'
        processedStripeEventRepository.existsByEventId('evt_race') >> false
        processedStripeEventRepository.save(_) >> { throw new DataIntegrityViolationException('uq_processed_stripe_events_event_id') }

        when:
        def claimed = service.claimStripeEvent('evt_race', 'charge.dispute.created')

        then: 'the race resolves to duplicate-skip — caller must NOT run the handler, and the violation does NOT propagate'
        claimed == false
        noExceptionThrown()
    }

    def "claimStripeEvent fast-paths a duplicate already in this pod's in-memory cache — no DB round-trip"() {
        given: 'this pod handled the event earlier this lifetime (cache warmed via the first successful claim)'
        processedStripeEventRepository.existsByEventId('evt_cached') >> false
        // First claim wins and warms seenEventIds — this happens in given:,
        // so its DB interactions are NOT what the then: block below counts.
        assert service.claimStripeEvent('evt_cached', 'payment_intent.succeeded') == true

        when: 'the same id is claimed again on this pod'
        def claimed = service.claimStripeEvent('evt_cached', 'payment_intent.succeeded')

        then: 'short-circuits on the local cache — the SECOND claim issues NO DB calls at all'
        claimed == false
        0 * processedStripeEventRepository.existsByEventId(_)
        0 * processedStripeEventRepository.save(_)
    }

    def "claimStripeEvent degrades to the per-JVM set when no DB ledger is wired (single-process behaviour)"() {
        given: 'a context-less build with the optional repo unwired'
        service.processedStripeEventRepository = null

        expect: 'first delivery claims (true), second delivery on the same JVM dedupes (false) — no NPE'
        service.claimStripeEvent('evt_norepo', 'checkout.session.completed') == true
        service.claimStripeEvent('evt_norepo', 'checkout.session.completed') == false
    }

    def "claimStripeEvent fails OPEN on a transient existence-check DB error — still attempts the authoritative INSERT"() {
        given: 'the existence read blips, but the INSERT then succeeds'
        processedStripeEventRepository.existsByEventId('evt_readblip') >> { throw new RuntimeException('connection reset') }

        when:
        def claimed = service.claimStripeEvent('evt_readblip', 'checkout.session.completed')

        then: 'better to re-run an idempotent handler than 500 a webhook — the UNIQUE index still backstops a true dup'
        claimed == true
        1 * processedStripeEventRepository.save({ ProcessedStripeEvent e -> e.eventId == 'evt_readblip' })
    }

    // ── end-to-end through handleWebhookEvent (handler-runs-once proof) ───
    //
    // Webhook.constructEvent is a static SDK method → GroovySpy(global:true),
    // matching how StripeServiceSpec spies Session.create / Transfer.create.
    // We use payment_intent.succeeded because its handler body is a pure
    // log.info (no dataObjectDeserializer walk needed), so the test isolates
    // the dedup GATE without having to mock the Stripe event payload.

    def "handleWebhookEvent runs the handler on the FIRST delivery (claim won) then SKIPS a duplicate delivery (claim lost) — handler not re-run"() {
        given: 'a stubbed Stripe event whose signature verification + payload parse are bypassed'
        def event = Mock(Event) {
            getId()   >> 'evt_e2e_1'
            getType() >> 'payment_intent.succeeded'
        }
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> event

        and: 'first delivery: ledger empty → claim wins; duplicate delivery: ledger now reports the id'
        // Spock returns these in sequence across the two calls.
        processedStripeEventRepository.existsByEventId('evt_e2e_1') >>> [false, true]

        when: 'Stripe delivers the event the first time'
        service.handleWebhookEvent('{"id":"evt_e2e_1"}', 'sig')

        then: 'the claim is INSERTed exactly once (this pod owns the handler run)'
        1 * processedStripeEventRepository.save({ ProcessedStripeEvent e -> e.eventId == 'evt_e2e_1' })

        when: 'Stripe RETRIES the same event (e.g. routed to this same pod after a transient blip)'
        service.handleWebhookEvent('{"id":"evt_e2e_1"}', 'sig')

        then: 'no SECOND claim INSERT — the duplicate is skipped, so the handler side effects do not re-fire'
        0 * processedStripeEventRepository.save(_)
    }

    def "handleWebhookEvent SKIPS the handler when a sibling pod claimed the event first (cross-pod retry dedup) — no throw"() {
        given: 'the cross-pod retry lands here, but a sibling pod already owns the claim row'
        def event = Mock(Event) {
            getId()   >> 'evt_sibling'
            getType() >> 'payment_intent.succeeded'
        }
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> event
        processedStripeEventRepository.existsByEventId('evt_sibling') >> true

        when:
        service.handleWebhookEvent('{"id":"evt_sibling"}', 'sig')

        then: 'duplicate-skip: never INSERTs, never throws — the controller will ACK 200'
        0 * processedStripeEventRepository.save(_)
        noExceptionThrown()
    }

    def "handleWebhookEvent treats a concurrent-INSERT DataIntegrityViolation as a duplicate end-to-end (no throw, handler not double-fired)"() {
        given: 'the existence check passes here AND on a sibling pod; our INSERT then loses the UNIQUE race'
        def event = Mock(Event) {
            getId()   >> 'evt_e2e_race'
            getType() >> 'payment_intent.succeeded'
        }
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> event
        processedStripeEventRepository.existsByEventId('evt_e2e_race') >> false
        processedStripeEventRepository.save(_) >> { throw new DataIntegrityViolationException('uq_processed_stripe_events_event_id') }

        when:
        service.handleWebhookEvent('{"id":"evt_e2e_race"}', 'sig')

        then: 'the race is swallowed as a duplicate — no 500 bubbles to the webhook controller'
        noExceptionThrown()
    }
}
