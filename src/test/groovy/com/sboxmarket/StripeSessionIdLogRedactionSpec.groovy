package com.sboxmarket

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.service.StripeService
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Regression spec for the production secret-leak in StripeService's
 * deposit-flow log lines.
 *
 * THE LEAK
 * --------
 * A Stripe Checkout Session id (`cs_live_<long-opaque-token>`) is a
 * capability, not a harmless reference: combined with the publishable key
 * (which IS public) a holder can call Stripe.js
 * `retrieveCheckoutSession(cs_live_…)` and read the buyer's email, the
 * amount, and the line items. Stripe hands the id to the browser only via
 * the one-time success-url redirect
 * (`…&session_id={CHECKOUT_SESSION_ID}` — see
 * StripeService.createDepositSession).
 *
 * The prod logging config (application-prod.yml) deliberately pins
 * `org.springframework.web` to WARN *specifically* so request URIs
 * carrying `&session_id=cs_live_…` never reach
 * /var/log/skinbox/skinbox.log — the inline comment there names "Stripe
 * session_id" as a value to keep out of the log. But `com.sboxmarket`
 * logs at INFO, and StripeService interpolated the RAW session id into
 * seven INFO/WARN lines (create, idempotent-reuse, unknown-session,
 * terminal short-circuit, retrieve-failed, payment-status-refused, and
 * deposit-credited). Those lines bypassed the web-logger pin entirely and
 * wrote the full lookup token to the file log at INFO — the exact secret
 * the prod config was built to suppress.
 *
 * THE FIX
 * -------
 * A static {@code redactSession()} helper keeps the non-secret
 * mode-qualified prefix (`cs_live_` / `cs_test_` + 4 chars) and drops the
 * opaque remainder — symmetric with how {@code init()} logs only the
 * secret-key prefix. Every application-log site now routes the id through
 * it.
 *
 * This spec:
 *   1. unit-tests {@code redactSession()} so the redaction SHAPE is pinned
 *      (a regression that widens the kept-prefix back toward the full
 *      token fails here);
 *   2. wires a Logback {@link ListAppender} to the StripeService logger
 *      and drives the cheapest reachable log site
 *      ({@code completeDeposit} with an unknown session id → the
 *      "confirm-deposit called with unknown sessionId=…" WARN) and asserts
 *      the raw token never reaches the log while the redacted breadcrumb
 *      does.
 */
class StripeSessionIdLogRedactionSpec extends Specification {

    /** A realistic live Checkout Session id. The long opaque tail is the
     *  capability part — it must NEVER appear verbatim in any log line. */
    static final String LIVE_SESSION =
        'cs_live_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8s9T0u1V2w3X4y5Z6'
    /** The redacted breadcrumb ops should still see: mode prefix + 4 chars. */
    static final String REDACTED_HEAD = 'cs_live_a1B2'

    Logger stripeLogger
    ListAppender<ILoggingEvent> appender

    def setup() {
        stripeLogger = (Logger) LoggerFactory.getLogger(StripeService)
        appender = new ListAppender<ILoggingEvent>()
        appender.start()
        stripeLogger.addAppender(appender)
    }

    def cleanup() {
        stripeLogger?.detachAppender(appender)
    }

    /** Every captured log line's formatted message + any throwable text,
     *  concatenated — the raw token must be absent from ALL of them. */
    private String capturedText() {
        appender.list.collect { ILoggingEvent ev ->
            def thr = ev.throwableProxy
            def thrText = thr ? "${thr.className}: ${thr.message}" : ''
            "${ev.formattedMessage}${thrText ? '\n' + thrText : ''}"
        }.join('\n')
    }

    @Unroll
    def "redactSession keeps only a non-usable breadcrumb for #desc"() {
        expect:
        StripeService.redactSession(input) == expected
        // The opaque tail of a real session id must never survive.
        if (input?.startsWith('cs_') && input.length() > 12) {
            assert !StripeService.redactSession(input).contains(input.substring(12))
        }

        where:
        desc                       | input                          || expected
        'a live session id'        | LIVE_SESSION                   || REDACTED_HEAD + '…'
        'a test session id'        | 'cs_test_ZZZZ_secret_tail_here' || 'cs_test_ZZZZ' + '…'
        'a null id'                | null                           || '<none>'
        'an empty id'              | ''                             || '<none>'
        'a legacy/manual ref'      | 'manual'                       || 'man…'
        'a dev ref'                | 'dev_1700000000000'            || 'dev…'
        'a too-short cs-ish ref'   | 'cs_x'                         || 'cs_…'
    }

    def "completeDeposit on an unknown session never logs the raw session id"() {
        given: 'a StripeService whose tx lookup misses (unknown session)'
        def txRepo = Mock(TransactionRepository)
        def svc = new StripeService(transactionRepository: txRepo)
        // No Stripe keys wired → isLive() is false, so the path never
        // touches the Stripe SDK; the unknown-session WARN fires before
        // any live-mode branch regardless.
        txRepo.findByStripeReference(LIVE_SESSION) >> null

        when: 'the deposit confirm runs against an id Stripe handed the browser'
        svc.completeDeposit(LIVE_SESSION)

        then: 'it rejects the unknown session (existing behaviour preserved)'
        def ex = thrown(IllegalStateException)
        ex.message == 'Unknown deposit session'

        and: 'exactly the unknown-session WARN was emitted'
        def warns = appender.list.findAll { it.level == Level.WARN }
        warns.size() == 1

        and: 'the raw capability token never reached the log...'
        def text = capturedText()
        !text.contains(LIVE_SESSION)
        // ...and specifically not the opaque tail past the kept prefix.
        !text.contains(LIVE_SESSION.substring(REDACTED_HEAD.length()))

        and: 'but the redacted breadcrumb is still there for ops correlation'
        text.contains(REDACTED_HEAD)
        text.contains('confirm-deposit called with unknown sessionId=')
    }
}
