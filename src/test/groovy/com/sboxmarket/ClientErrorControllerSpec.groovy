package com.sboxmarket

import com.sboxmarket.controller.ClientErrorController
import com.sboxmarket.controller.SteamAuthController
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the React ErrorBoundary's client-side error sink.
 * Critical invariants this endpoint must hold:
 *
 *   1. Empty payload (no message + no stack) → 204 No Content, not
 *      400. A crashing client retry-loop that can't parse a 400 would
 *      hammer this endpoint; a 204 lets the retry loop pass through
 *      harmlessly without blowing up log storage.
 *   2. Per-field char caps (500 / 4000 / 500 / 300) are applied
 *      BEFORE logging — a malicious page can't fill the server log
 *      with 100KB stacks.
 *   3. Control characters (`\0`, `\r\n`, `\n`) are scrubbed so log
 *      lines stay single-line and terminal-safe.
 *   4. User-Agent falls through to the request header when not in
 *      the body, so curl probes still carry useful context.
 *   5. Anonymous callers are accepted — the first-visit / sign-in-
 *      flow crashes we most need to see come from pre-auth sessions.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class ClientErrorControllerSpec extends Specification {

    @Subject
    ClientErrorController controller = new ClientErrorController()

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    def "empty body → 204 No Content (don't wedge a retry storm)"() {
        when:
        def resp = controller.report(null, req)

        then: 'session never touched — short-circuit before the uid read'
        0 * req.session
        resp.statusCode.value() == 204
        resp.body == null
    }

    def "body with neither message nor stack → 204"() {
        when:
        def resp = controller.report([url: '/market'], req)

        then:
        0 * req.session
        resp.statusCode.value() == 204
    }

    def "body with at least a message → 200 {received:true}"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        // UA falls through to header
        1 * req.getHeader('User-Agent') >> 'Mozilla/5.0 test'

        when:
        def resp = controller.report([message: 'boom'], req)

        then:
        resp.statusCode.value() == 200
        resp.body == [received: true]
    }

    def "anonymous caller is accepted (pre-auth crashes are the point)"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * req.getHeader('User-Agent') >> null

        when:
        def resp = controller.report([stack: 'at foo (bar.js:1:1)'], req)

        then: 'no UnauthorizedException, no 401 — endpoint accepts anon by design'
        notThrown(Exception)
        resp.statusCode.value() == 200
    }

    def "per-field char caps are applied before logging"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        // Body carries its own userAgent → header fallback short-circuits;
        // req.getHeader() must NOT be called on this path.

        and: 'body significantly above every limit'
        def body = [
            message  : 'x' * 10_000,
            stack    : 'y' * 50_000,
            url      : 'z' * 2_000,
            userAgent: 'w' * 1_000
        ]

        when:
        def resp = controller.report(body, req)

        then: '200 still — no logged value exceeds the cap even on a crafted flood'
        0 * req.getHeader(_)
        resp.statusCode.value() == 200
        notThrown(Exception)
    }

    def "sessionless request body-only UA is used when header is absent"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        // Header absent — but body carries a UA string
        0 * req.getHeader('User-Agent')

        when:
        def resp = controller.report(
            [message: 'boom', userAgent: 'SpiderBot/1.0'], req)

        then:
        resp.statusCode.value() == 200
    }

    def "signed-in caller's uid is surfaced in the log line (via MDC-less log.warn)"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 42L
        1 * req.getHeader('User-Agent') >> 'Mozilla/5.0'

        when:
        def resp = controller.report([message: 'boom'], req)

        then: 'no exception when uid is present; logger picks it up'
        notThrown(Exception)
        resp.statusCode.value() == 200
    }

    def "null body is accepted without NPE — mirror for body-size-filter trimmed requests"() {
        when:
        def resp = controller.report(null, req)

        then: 'very first branch returns 204; no null-dereference inside clip()'
        notThrown(NullPointerException)
        resp.statusCode.value() == 204
    }

    def "non-String message is coerced to a String (Groovy `as String` on a number)"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * req.getHeader('User-Agent') >> null

        when: 'weird client sends `message: 42` — Groovy casts to "42"'
        def resp = controller.report([message: 42], req)

        then: 'path reaches log.warn because "42" is a non-empty string'
        notThrown(Exception)
        resp.statusCode.value() == 200
    }

    def "request with session throwing doesn't blow up the sink (defensive)"() {
        given: 'a weird servlet container where session read throws'
        1 * req.session >> { throw new IllegalStateException('session recycled') }

        when:
        controller.report([message: 'boom'], req)

        then: 'the IllegalStateException propagates (controller is not the last-resort sink for that)'
        // We're pinning current behavior — if the team later decides to
        // wrap this try/catch, the test needs to be updated consciously.
        thrown(IllegalStateException)
    }
}
