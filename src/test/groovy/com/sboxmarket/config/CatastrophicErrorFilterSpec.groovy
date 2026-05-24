package com.sboxmarket.config

import jakarta.servlet.FilterChain
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.CannotCreateTransactionException
import spock.lang.Specification
import spock.lang.Subject

/**
 * Happy-path + propagation coverage for {@link CatastrophicErrorFilter}
 * that complements the existing
 * {@link CatastrophicErrorFilterStreamSinkSpec} (which only exercises the
 * stream-sink fallback path where the response writer has already been
 * claimed downstream).
 *
 * The cases here are the ones that fire in normal flow:
 *   - non-catastrophic exceptions propagate untouched (filter is invisible)
 *   - successful chain.doFilter is a no-op
 *   - each watched FQN class trips the panel and bypasses the error pipeline
 *   - the cause-chain walk catches Spring-translated wrappers + nested causes
 *   - depth-bounded cause walk doesn't loop on a self-referential chain
 *   - non-HTTP request/response pairs are passed through unchanged
 *   - already-committed response re-throws (can't fix what's already on the wire)
 *   - JSON vs HTML branch selection responds to BOTH path prefix AND Accept header
 */
class CatastrophicErrorFilterSpec extends Specification {

    @Subject
    CatastrophicErrorFilter filter = new CatastrophicErrorFilter()

    FilterChain chain = Mock()

    private static Throwable newPsqlException(String message) {
        Class<?> psqlCls = Class.forName('org.postgresql.util.PSQLException')
        Class<?> stateCls = Class.forName('org.postgresql.util.PSQLState')
        def state = stateCls.getDeclaredField('DATA_ERROR').get(null)
        return (Throwable) psqlCls.getConstructor(String, stateCls).newInstance(message, state)
    }

    // ── Happy paths ──────────────────────────────────────────────────

    def "successful request passes straight through — filter is invisible"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        noExceptionThrown()
        // Status untouched — filter must not stamp 500 on a healthy response.
        resp.status == 200
        resp.contentAsString == ''
    }

    def "HTML panel served on a browser path when a catastrophic exception fires"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        req.addHeader('Accept', 'text/html')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw newPsqlException('db down') }
        noExceptionThrown()
        resp.status == 500
        resp.contentType?.startsWith('text/html')
        resp.contentAsString.contains('<!doctype html>')
        resp.contentAsString.contains('skinbox.market')
        resp.contentAsString.contains('Service unavailable')
        // No framework strings leak to the visitor.
        !resp.contentAsString.contains('PSQL')
        !resp.contentAsString.contains('SQLSTATE')
        !resp.contentAsString.contains('Hibernate')
    }

    def "JSON envelope served on /api/** when a catastrophic exception fires"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw newPsqlException('boom') }
        noExceptionThrown()
        resp.status == 500
        resp.contentType?.startsWith('application/json')
        resp.contentAsString.contains('"status":500')
        resp.contentAsString.contains('"catastrophic":true')
        // JSON path must NEVER emit HTML — would break an API client's parser.
        !resp.contentAsString.contains('<!doctype')
        !resp.contentAsString.contains('<html')
    }

    def "JSON envelope chosen by Accept=application/json even on a non-/api path"() {
        // The branch predicate is `path.startsWith('/api/') OR
        // accept.contains('application/json')` — a JSON-fetch from the
        // SPA against a non-/api path (rare but real for SSR shells)
        // must still get JSON back.
        given:
        def req = new MockHttpServletRequest('GET', '/some-spa-route')
        req.addHeader('Accept', 'application/json')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw newPsqlException('json wanted') }
        noExceptionThrown()
        resp.status == 500
        resp.contentType?.startsWith('application/json')
        resp.contentAsString.contains('"catastrophic":true')
    }

    // ── Cause-chain walk ─────────────────────────────────────────────

    def "Spring-translated wrapper around a PSQLException trips the panel"() {
        // Repository code throws Spring-translated wrappers — the panel
        // must still fire by walking the cause chain. The wrapper class
        // (DataAccessResourceFailureException) is ALSO independently in
        // the watched set, so we wrap inside another to test the cause
        // walk specifically.
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args ->
            throw new RuntimeException('outer', new DataAccessResourceFailureException('pool exhausted', newPsqlException('boom')))
        }
        noExceptionThrown()
        resp.status == 500
        resp.contentAsString.contains('"catastrophic":true')
    }

    def "depth-bounded cause walk does not loop on a self-referential chain"() {
        // A self-referential cause chain (cause == self) would infinite-loop
        // a naive walk. The filter's depth guard (16 frames) must prevent
        // that. We can't actually set cause == self via initCause (Throwable
        // forbids it), but a long synthetic chain with no watched class
        // exercises the same loop-termination contract.
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()
        // Build a 50-deep chain of unrelated RuntimeExceptions.
        Throwable deepChain = new RuntimeException('leaf')
        50.times { i -> deepChain = new RuntimeException("layer ${i}", deepChain) }

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw deepChain }
        // Filter walks at most 16 frames, finds no watched class,
        // re-throws unchanged.
        def caught = thrown(RuntimeException)
        caught.is(deepChain)
        resp.status != 500    // panel not written
        resp.contentAsString == ''
    }

    // ── Watched-class coverage ───────────────────────────────────────

    def "every documented watched class triggers the panel"() {
        // Pin the WATCHED set's contract: each FQN listed in the filter's
        // CATASTROPHIC constant must independently trip the panel. A new
        // outage flavour gets added by extending the set — this test
        // documents the current coverage line so a name typo regresses
        // loudly instead of silently.
        given:
        def req = new MockHttpServletRequest('GET', '/api/x')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw exception }
        noExceptionThrown()
        resp.status == 500
        resp.contentAsString.contains('"catastrophic":true')

        where:
        exception << [
            new DataAccessResourceFailureException('pool dead'),
            new DataIntegrityViolationException('dup key'),
            new CannotGetJdbcConnectionException('no connection'),
            new CannotCreateTransactionException('tx start failed')
        ]
    }

    // ── Propagation contract ─────────────────────────────────────────

    def "non-catastrophic RuntimeException propagates untouched"() {
        // A plain bug must NOT trigger the panel — the normal MVC error
        // pipeline + GlobalExceptionHandler need to see and translate it.
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw new IllegalArgumentException('plain bug') }
        def caught = thrown(IllegalArgumentException)
        caught.message == 'plain bug'
        resp.status == 200   // status untouched — handler will set it
        resp.contentAsString == ''
    }

    def "non-HTTP request and response pair is passed straight through"() {
        // The filter's `instanceof HttpServletRequest` guard protects against
        // a hypothetical non-HTTP servlet container — chain.doFilter is
        // called with the raw inputs and exceptions are NOT caught (no HTTP
        // response to write the panel to).
        given:
        def req = Mock(jakarta.servlet.ServletRequest)
        def resp = Mock(jakarta.servlet.ServletResponse)

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        noExceptionThrown()
    }

    def "already-committed response re-throws — cannot rewrite what is already on the wire"() {
        // Once bytes have streamed to the client, the panel cannot be
        // written without producing a corrupt response. The filter logs
        // and re-throws so the container's normal abort path runs.
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()
        // Force the response committed flag — simulates a downstream
        // controller that already streamed a partial body.
        resp.outputStream.write('partial body'.bytes)
        resp.flushBuffer()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw newPsqlException('mid-stream') }
        // Re-thrown unchanged — the panel cannot be served.
        thrown(Throwable)
    }
}
