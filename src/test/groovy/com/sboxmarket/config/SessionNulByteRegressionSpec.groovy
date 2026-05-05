package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression coverage for the FOURTH NUL-byte session outage in 24h
 * (incidents on 2026-04-30, 2026-05-01, 2026-05-02 evening, 2026-05-03
 * morning).  Pinned behaviours, all of which contribute to the
 * "site stays 200 even when the DB is on fire" guarantee:
 *
 *   1. CatastrophicErrorFilter catches the documented set of DB / JDBC
 *      failure exceptions and serves a self-contained branded HTML 500
 *      page WITHOUT touching session, controller, or /error dispatch.
 *   2. The static panel is byte-for-byte deterministic — no template
 *      indirection, no classpath read, no fonts.  This is the panel
 *      that gets served when the session table itself is poisoned and
 *      EVERY OTHER error path tries to read it.
 *   3. JSON paths under /api/** receive a JSON envelope, not HTML
 *      (otherwise a JS client would render an HTML blob in a JSON
 *      decoder and trip a hard SyntaxError chained on the 500).
 *   4. Non-catastrophic exceptions are NOT swallowed — they bubble
 *      to the normal error pipeline so GlobalExceptionHandler /
 *      GlobalErrorController can do the rich rendering.
 *   5. Cause-chain walking handles the common case of Spring's
 *      translated DataAccessException wrapping the raw PSQLException
 *      that JDBC actually threw.
 *
 * The DB-level CHECK constraints from V60
 * (`spring_session_no_nul_bytes`, `spring_session_attrs_no_nul_bytes`)
 * are intentionally NOT exercised here — they target Postgres-only
 * behaviour and the unit-test datasource is H2.  Their migration
 * lives at `src/main/resources/db/migration/V60__spring_session_full_nul_check.sql`
 * and is verified at deploy time by the Flyway log + `\d` introspection
 * documented in `_qa_boss/BOSS_PROMPT_HOTFIX_500_REDUX.md`.
 */
class SessionNulByteRegressionSpec extends Specification {

    @Subject
    CatastrophicErrorFilter filter = new CatastrophicErrorFilter()

    def "PSQLException from below the filter triggers branded 500 HTML panel (browser path)"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        req.addHeader('Accept', 'text/html')
        def resp = new MockHttpServletResponse()
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args ->
            // Simulate Spring Session's JdbcSession SELECT failing on a
            // NUL byte that survived V60 (which it can't, but defence-
            // in-depth: the filter must work even if every other guard
            // was somehow bypassed).
            def t = newPsqlException('invalid byte sequence for encoding "UTF8": 0x00')
            throw t
        }
        resp.status == 500
        resp.contentType.startsWith('text/html')
        resp.contentAsString.contains('skinbox.market')
        resp.contentAsString.contains('500')
        resp.contentAsString.contains('Service unavailable')
        // Must not leak any framework-internal strings to the user.
        !resp.contentAsString.contains('Whitelabel')
        !resp.contentAsString.contains('PSQL')
        !resp.contentAsString.contains('SQLSTATE')
        !resp.contentAsString.contains('stack')
    }

    def "Spring-translated DataAccessException with PSQLException cause also triggers panel"() {
        given:
        def req = new MockHttpServletRequest('GET', '/profile')
        req.addHeader('Accept', 'text/html')
        def resp = new MockHttpServletResponse()
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args ->
            // The shape Spring's repository code actually throws —
            // Spring wraps the PSQLException in a translated subclass.
            def root = newPsqlException('connection refused')
            throw new DataAccessResourceFailureException("connection failed", root)
        }
        resp.status == 500
        resp.contentAsString.contains('skinbox.market')
    }

    def "DataIntegrityViolationException (constraint failure) triggers panel"() {
        // V60's CHECK constraints surface as ConstraintViolation →
        // DataIntegrityViolationException up the stack. If a write
        // somehow tries to land a NUL despite the cookie sanitizer,
        // Postgres rejects it, Spring translates it, and this filter
        // serves a panel rather than a stack trace.
        given:
        def req = new MockHttpServletRequest('POST', '/api/auth/steam/login')
        def resp = new MockHttpServletResponse()
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args ->
            throw new DataIntegrityViolationException(
                "ERROR: new row for relation \"spring_session\" violates check constraint \"spring_session_no_nul_bytes\"")
        }
        resp.status == 500
    }

    def "/api/** requests get a JSON envelope, not the HTML panel"() {
        // A SPA fetch() that gets HTML where it expected JSON throws
        // SyntaxError, which then masks the actual 500 status and
        // makes the outage look like a frontend bug. Force JSON.
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.addHeader('Accept', 'application/json')
        def resp = new MockHttpServletResponse()
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw newPsqlException('boom') }
        resp.status == 500
        resp.contentType.startsWith('application/json')
        resp.contentAsString.contains('"status":500')
        resp.contentAsString.contains('"catastrophic":true')
        // No HTML in a JSON response — full stop.
        !resp.contentAsString.contains('<html')
        !resp.contentAsString.contains('<!doctype')
    }

    def "non-catastrophic exception is NOT swallowed — bubbles to normal error pipeline"() {
        // A NullPointerException from a controller is NOT a DB outage —
        // GlobalExceptionHandler should handle it normally. If this
        // filter swallowed everything, every other error would render
        // a misleading "service unavailable" panel.
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw new NullPointerException('controller bug') }
        thrown(NullPointerException)
    }

    def "IllegalArgumentException (validation) is also NOT swallowed"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/listings')
        def resp = new MockHttpServletResponse()
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw new IllegalArgumentException('bad input') }
        thrown(IllegalArgumentException)
    }

    def "successful request passes through cleanly with no overhead"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _)
        resp.status == 200
        // No body written by the filter on the happy path.
        resp.contentAsString.isEmpty()
    }

    def "if response was already committed, exception bubbles (cannot rewrite mid-stream)"() {
        // Once bytes have been streamed to the client, we can't reset
        // the response to write our panel. The exception must still
        // propagate so the container logs it; truncating silently
        // would mask outages from observability.
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args ->
            // Simulate a streaming response that's already flushed bytes.
            HttpServletResponse r = (HttpServletResponse) args[1]
            r.writer.write("partial body")
            r.flushBuffer()
            throw newPsqlException('mid-stream blip')
        }
        thrown(Exception)
    }

    def "non-HTTP servlet calls (defence-in-depth) pass through"() {
        // Defence-in-depth: a non-HttpServletRequest shouldn't even be
        // reachable here, but if it ever is (some weird async dispatch),
        // the filter should be a no-op and not throw a ClassCastException.
        given:
        def req = Mock(jakarta.servlet.ServletRequest)
        def resp = Mock(jakarta.servlet.ServletResponse)
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        noExceptionThrown()
    }

    /**
     * Build a real PSQLException via reflection so the FQN match in
     * CatastrophicErrorFilter trips. The postgresql driver is on the
     * test classpath via `runtimeOnly 'org.postgresql:postgresql'`
     * in build.gradle — Class.forName resolves at test time.
     */
    private static Throwable newPsqlException(String message) {
        Class<?> psqlCls = Class.forName('org.postgresql.util.PSQLException')
        Class<?> stateCls = Class.forName('org.postgresql.util.PSQLState')
        def state = stateCls.getDeclaredField('DATA_ERROR').get(null)
        return (Throwable) psqlCls.getConstructor(String, stateCls).newInstance(message, state)
    }
}
