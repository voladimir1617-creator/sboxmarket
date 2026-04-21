package com.sboxmarket

import com.sboxmarket.controller.StripeWebhookController
import com.sboxmarket.service.StripeService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the Stripe webhook endpoint. Small controller, but the
 * rules are load-bearing for the payment pipeline:
 *
 *   - Invalid signature → HTTP 400 "invalid signature" (SecurityException
 *     from the service is the only way Stripe's HMAC check can fail).
 *     Stripe's retry-on-failure policy means a 400 DOESN'T retry — this
 *     is intentional, since the payload-with-bad-sig is either a probe
 *     or a replay after key rotation; a 5xx would retry-loop it.
 *
 *   - Any other exception → HTTP 500 "error" (triggers Stripe's retry
 *     ladder, correct for transient DB / email issues so we don't
 *     drop a real event).
 *
 *   - Happy path → HTTP 200 "ok".
 *
 *   - Missing Stripe-Signature header → service receives "" (not null)
 *     so the downstream signature check handles the string defensively.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class StripeWebhookControllerSpec extends Specification {

    StripeService stripeService = Mock()

    @Subject
    StripeWebhookController controller = new StripeWebhookController(stripeService: stripeService)

    def "happy path: valid payload + signature returns 200 'ok'"() {
        given:
        1 * stripeService.handleWebhookEvent('{"id":"evt_1"}', 't=123,v1=sig') >> null

        when:
        def resp = controller.webhook('{"id":"evt_1"}', 't=123,v1=sig')

        then:
        resp.statusCode.value() == 200
        resp.body == 'ok'
    }

    def "invalid signature: SecurityException → 400 'invalid signature' (Stripe will NOT retry)"() {
        given:
        1 * stripeService.handleWebhookEvent(_ as String, _ as String) >>
            { throw new SecurityException('signature mismatch') }

        when:
        def resp = controller.webhook('{"id":"evt_1"}', 'bogus')

        then:
        resp.statusCode.value() == 400
        resp.body == 'invalid signature'
    }

    def "transient error: RuntimeException → 500 'error' (Stripe WILL retry)"() {
        given: 'DB pool exhausted or a downstream service hiccup'
        1 * stripeService.handleWebhookEvent(_ as String, _ as String) >>
            { throw new RuntimeException('DB connection refused') }

        when:
        def resp = controller.webhook('{"id":"evt_1"}', 't=123,v1=sig')

        then: '5xx triggers Stripe`s retry ladder so we do not drop the event'
        resp.statusCode.value() == 500
        resp.body == 'error'
    }

    def "missing Stripe-Signature header: service receives '' (empty string, not null)"() {
        given:
        String capturedSig = null
        1 * stripeService.handleWebhookEvent(_ as String, _ as String) >> { args ->
            capturedSig = args[1]
            null
        }

        when: 'Stripe-Signature header was not set on the incoming request'
        def resp = controller.webhook('{}', null)

        then: 'defensive default empties the sig to "" so downstream HMAC check handles a string'
        capturedSig == ''
        resp.statusCode.value() == 200
    }

    def "empty payload still reaches the service (service decides handling)"() {
        given:
        1 * stripeService.handleWebhookEvent('', 't=1,v1=x') >> null

        when:
        def resp = controller.webhook('', 't=1,v1=x')

        then:
        resp.statusCode.value() == 200
    }

    def "checked-exception subclasses propagate through the catch-all 500"() {
        given:
        1 * stripeService.handleWebhookEvent(_ as String, _ as String) >>
            { throw new IllegalStateException('webhook replay detected') }

        when:
        def resp = controller.webhook('{}', 't=1,v1=x')

        then:
        resp.statusCode.value() == 500
        resp.body == 'error'
    }

    def "SecurityException subclasses go to the 400 branch"() {
        given:
        // AccessControlException is a subclass of SecurityException — caught
        // by the same `catch (SecurityException)` branch.
        1 * stripeService.handleWebhookEvent(_ as String, _ as String) >>
            { throw new java.security.AccessControlException('stripe HMAC mismatch') }

        when:
        def resp = controller.webhook('{}', 'badsig')

        then:
        resp.statusCode.value() == 400
        resp.body == 'invalid signature'
    }
}
