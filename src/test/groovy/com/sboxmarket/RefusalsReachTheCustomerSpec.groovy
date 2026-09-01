package com.sboxmarket

import com.sboxmarket.config.GlobalExceptionHandler
import com.sboxmarket.exception.BadRequestException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.test.util.ReflectionTestUtils
import spock.lang.Specification
import spock.lang.Unroll

/**
 * <b>A green suite is not evidence the customer is served.</b>
 *
 * <p>4,923 tests passed while every money-path refusal was being replaced with
 * "Request could not be completed", because they all asserted what the SERVICE
 * THREW. The swallow happens one layer further out, in
 * {@link GlobalExceptionHandler}, so that is where these assert.
 *
 * <p>The mechanism: {@code security.verbose-errors} defaults false, and the
 * default branch of {@code genericMessage} discards any message over 140 chars.
 * Four codes were over. {@code CONNECT_ONBOARDING_REQUIRED} missed by THREE
 * characters and left a seller with no next step at all.
 */
class RefusalsReachTheCustomerSpec extends Specification {

    static final int SWALLOW_LIMIT = 140
    static final String SWALLOWED = 'Request could not be completed'

    private GlobalExceptionHandler handler() {
        def h = new GlobalExceptionHandler()
        // The customer-facing configuration, NOT the developer one. Asserting
        // against verbose-errors=true would prove nothing about production.
        ReflectionTestUtils.setField(h, 'verboseErrors', false)
        h
    }

    private String messageSeenBy(String code, String message) {
        def req = Stub(HttpServletRequest) { getRequestURI() >> '/api/wallet/deposit' }
        handler().handleApi(new BadRequestException(code, message), req).body.message
    }

    @Unroll
    def "a #code refusal reaches the customer instead of being swallowed"() {
        given: 'a message longer than the swallow limit, as these once were'
        def longMessage = 'x' * (SWALLOW_LIMIT + 40)

        expect: 'the allowlist carries it through on its code, whatever its length'
        messageSeenBy(code, longMessage) == longMessage

        where:
        code << ['DEV_CREDIT_NOT_AUTHORIZED', 'STRIPE_MODE_INDETERMINATE',
                 'ADMIN_DAILY_CAP', 'CONNECT_ONBOARDING_REQUIRED']
    }

    def "an unlisted long message is still swallowed, so the guard is not simply gone"() {
        expect: 'this is the behaviour the allowlist is an exception TO'
        messageSeenBy('SOME_OTHER_CODE', 'y' * (SWALLOW_LIMIT + 1)) == SWALLOWED
    }

    def "the shipped deposit refusal is short enough to survive without the allowlist"() {
        given: 'belt and braces: the list states the intent, the length keeps it true'
        def shipped = "Deposits are temporarily unavailable. You haven't been charged and " +
                      'nothing was added to your balance. Please try again later.'

        expect:
        shipped.length() <= SWALLOW_LIMIT
        messageSeenBy('SOME_OTHER_CODE', shipped) == shipped
    }

    def "the refusal a customer reads names no internals"() {
        given:
        def shipped = "Deposits are temporarily unavailable. You haven't been charged and " +
                      'nothing was added to your balance. Please try again later.'

        expect: 'engineer vocabulary that a person cannot act on'
        !shipped.toLowerCase().contains('deployment')
        !shipped.toLowerCase().contains('simulated')
        !shipped.toLowerCase().contains('configuration')
        and: 'and no invitation to contact support about a platform-wide outage support cannot fix'
        !shipped.toLowerCase().contains('contact support')
        and: 'but it DOES say the reassuring thing, which is what the swallow was deleting'
        shipped.toLowerCase().contains("haven't been charged")
    }

    def "the money-IN and money-OUT doors are gated by the same rule"() {
        given: 'the source of both paths'
        def src = new File('src/main/groovy/com/sboxmarket/service/StripeService.groovy').text

        expect: 'deposit refuses a deployment that may not fabricate a credit'
        src.contains("refuseIfCreditNotAuthorized('deposit'")

        and: """withdrawal refuses on the SAME rule.

        Without this, gating money-IN alone turns a coherent two-way simulation
        into a one-way door: the simulated withdrawal debits the real recorded
        balance and writes a COMPLETED dev_payout_ row for a payout that never
        happens, while the deposit path that used to restore it is shut. A user
        could zero a wallet against nothing and have no way back.

        Asserted at the source rather than through a wired service because the
        defect was a MISSING CALL -- and a missing call is invisible to any test
        that exercises the path it is missing from."""
        src.contains("refuseIfCreditNotAuthorized('withdrawal'")
    }

    def "the admin credit stamp does not reach the customer"() {
        given: 'the per-actor stamp added for the rolling cap is load-bearing and stays in the DB'
        def js = new File('src/main/resources/static/js/modals.js').text

        expect: 'but the customer sees the collapsed form, not a staff member id'
        js.contains('function customerFacingRef(')
        js.contains('/^admin(_.*)?' + '$' + '/.test(ref)')

        and: 'and both customer-facing render sites go through it'
        js.contains("tx.description || customerFacingRef(tx.stripeReference)")
        js.contains("customerFacingRef(tx.stripeReference) + ')'")
    }
}
