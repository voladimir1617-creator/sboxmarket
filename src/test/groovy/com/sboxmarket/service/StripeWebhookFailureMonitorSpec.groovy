package com.sboxmarket.service

import com.sboxmarket.controller.StripeWebhookController
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import spock.lang.Specification

/**
 * Stripe webhook failure counters and the staff alert on top of them, plus the
 * rule that matters most: observing never changes what Stripe is told.
 */
class StripeWebhookFailureMonitorSpec extends Specification {

    OpsAlertService ops = Mock()
    SimpleMeterRegistry registry = new SimpleMeterRegistry()
    long clock = 1_000_000_000L
    StripeWebhookFailureMonitor monitor = new StripeWebhookFailureMonitor() {
        @Override protected long now() { clock }
    }

    def setup() {
        monitor.opsAlertService = ops
        monitor.meterRegistry = registry
        monitor.windowMs = 15L * 60_000L
        monitor.badSignatureThreshold = 3
        monitor.permanentAckThreshold = 1
    }

    private double meter(String reason) {
        registry.find(StripeWebhookFailureMonitor.METER_NAME).tag('reason', reason).counter()?.count() ?: 0d
    }

    def "bad signatures: counted, and one alert when 3 land inside the window"() {
        when:
        monitor.recordBadSignature()
        clock += 60_000L
        monitor.recordBadSignature()

        then:
        0 * ops.alertAdmins(*_)

        when:
        clock += 60_000L
        monitor.recordBadSignature()

        then:
        1 * ops.alertAdmins('OPS_STRIPE_WEBHOOK:bad_signature', OpsAlertService.STRIPE_WEBHOOK_ALERT,
                'Stripe webhook signature failures', { String b -> b.contains('signing secret') }, '/admin?tab=health', true)

        when: "more inside the same window"
        5.times { clock += 1000L; monitor.recordBadSignature() }

        then: "no spam"
        0 * ops.alertAdmins(*_)
        monitor.total(StripeWebhookFailureMonitor.BAD_SIGNATURE) == 8L
        meter(StripeWebhookFailureMonitor.BAD_SIGNATURE) == 8d
        monitor.snapshot().badSignatureInWindow == 8
    }

    def "bad signatures spread wider than the window never alert"() {
        when:
        3.times { monitor.recordBadSignature(); clock += 10L * 60_000L }

        then:
        0 * ops.alertAdmins(*_)
        monitor.total(StripeWebhookFailureMonitor.BAD_SIGNATURE) == 3L
    }

    def "a still-failing secret alerts again only after a full window"() {
        when:
        3.times { monitor.recordBadSignature() }
        clock += 16L * 60_000L
        3.times { monitor.recordBadSignature() }

        then:
        2 * ops.alertAdmins('OPS_STRIPE_WEBHOOK:bad_signature', *_)
    }

    def "a permanent-failure ACK alerts with the event id and type, read from the payload"() {
        when:
        monitor.recordPermanentAck('{"id":"evt_1AbC","type":"checkout.session.completed","data":{}}')

        then:
        1 * ops.alertAdmins('OPS_STRIPE_WEBHOOK:permanent_ack', OpsAlertService.STRIPE_WEBHOOK_ALERT,
                'Stripe webhook event dropped',
                { String b -> b.contains('evt_1AbC') && b.contains('checkout.session.completed') && b.contains('will not retry') },
                '/admin?tab=health', true)
        meter(StripeWebhookFailureMonitor.PERMANENT_ACK) == 1d
        monitor.snapshot().lastPermanentEventId == 'evt_1AbC'
    }

    def "a malformed or hostile payload is still counted, with no id echoed"() {
        when:
        monitor.recordPermanentAck('not json')
        clock += 16L * 60_000L
        monitor.recordPermanentAck('{"id":"evt_x\\nInjected: yes","type":"<script>"}')

        then:
        2 * ops.alertAdmins(_, _, _, { String b -> !b.contains('Latest') }, _, true)
        monitor.total(StripeWebhookFailureMonitor.PERMANENT_ACK) == 2L
        monitor.snapshot().lastPermanentEventId == null
    }

    def "works with no meter registry and no alert service"() {
        given:
        def bare = new StripeWebhookFailureMonitor()

        when:
        5.times { bare.recordBadSignature(); bare.recordPermanentAck('{}') }

        then:
        noExceptionThrown()
        bare.total(StripeWebhookFailureMonitor.BAD_SIGNATURE) == 5L
    }

    // ── controller: responses are byte-for-byte what they were ──────────

    def "controller: every response code is unchanged, and each failure is recorded once"() {
        given:
        def stripe = Mock(StripeService)
        def mon = Mock(StripeWebhookFailureMonitor)
        def controller = new StripeWebhookController(stripeService: stripe, failureMonitor: mon)

        when:
        def ok = controller.webhook('{"id":"evt_1"}', 's')

        then:
        1 * stripe.handleWebhookEvent(_, _)
        0 * mon._
        ok.statusCode.value() == 200
        ok.body == 'ok'

        when:
        def bad = controller.webhook('{"id":"evt_1"}', 'bogus')

        then:
        1 * stripe.handleWebhookEvent(_, _) >> { throw new SecurityException('sig') }
        1 * mon.recordBadSignature()
        bad.statusCode.value() == 400
        bad.body == 'invalid signature'

        when:
        def perm = controller.webhook('{"id":"evt_2"}', 's')

        then:
        1 * stripe.handleWebhookEvent(_, _) >> { throw new IllegalStateException('Amount mismatch') }
        1 * mon.recordPermanentAck('{"id":"evt_2"}')
        perm.statusCode.value() == 200
        perm.body == 'acknowledged'

        when:
        def transient500 = controller.webhook('{"id":"evt_3"}', 's')

        then:
        1 * stripe.handleWebhookEvent(_, _) >> { throw new RuntimeException('db blip') }
        0 * mon._
        transient500.statusCode.value() == 500
        transient500.body == 'error'
    }

    def "controller: a monitor that throws cannot change the answer to Stripe"() {
        given:
        def stripe = Mock(StripeService)
        def mon = Mock(StripeWebhookFailureMonitor) {
            recordBadSignature() >> { throw new RuntimeException('boom') }
            recordPermanentAck(_) >> { throw new OutOfMemoryError('boom') }
        }
        def controller = new StripeWebhookController(stripeService: stripe, failureMonitor: mon)
        stripe.handleWebhookEvent(_, 'bad') >> { throw new SecurityException('sig') }
        stripe.handleWebhookEvent(_, 'perm') >> { throw new IllegalArgumentException('Invalid session id') }

        expect:
        controller.webhook('{}', 'bad').statusCode.value() == 400
        controller.webhook('{}', 'perm').statusCode.value() == 200
        controller.webhook('{}', 'perm').body == 'acknowledged'
    }

    def "controller: works unchanged with no monitor wired"() {
        given:
        def stripe = Mock(StripeService)
        def controller = new StripeWebhookController(stripeService: stripe)
        stripe.handleWebhookEvent(_, _) >> { throw new SecurityException('sig') }

        expect:
        controller.webhook('{}', 'x').statusCode.value() == 400
    }
}
