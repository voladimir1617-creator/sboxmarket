package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

import java.nio.charset.StandardCharsets

/**
 * Regression coverage for the output-sink hazard in
 * {@link CatastrophicErrorFilter}.
 *
 * The catastrophic catch block can fire AFTER a downstream component
 * (a Jackson-backed JSON {@code @RestController}, the static-resource
 * handler, a file download) has already called
 * {@code response.getOutputStream()}. Per the Servlet contract the
 * writer and the output stream are mutually exclusive — once one is
 * taken, requesting the other throws {@link IllegalStateException}.
 * {@code resetBuffer()} clears the buffered body but does NOT undo that
 * selection, so a naive {@code response.getWriter()} in the panel-write
 * path throws and the last-resort filter itself fails, dropping the
 * visitor onto the raw Tomcat stub page — the exact outage this filter
 * exists to prevent.
 *
 * {@code MockHttpServletResponse} does not enforce the writer/stream
 * exclusivity (its {@code writerAccessAllowed}/{@code outputStreamAccessAllowed}
 * flags stay {@code true} and are never flipped), so these tests drive
 * the hazard explicitly with a wrapper whose {@code getWriter()} throws,
 * mirroring real Tomcat behaviour.
 */
class CatastrophicErrorFilterStreamSinkSpec extends Specification {

    @Subject
    CatastrophicErrorFilter filter = new CatastrophicErrorFilter()

    /**
     * Response wrapper that mimics a Tomcat response on which
     * {@code getOutputStream()} has already been selected downstream:
     * {@code getWriter()} throws {@link IllegalStateException}, while
     * {@code getOutputStream()} keeps working and captures bytes.
     */
    private static class StreamOnlyResponse extends HttpServletResponseWrapper {
        final ByteArrayOutputStream captured = new ByteArrayOutputStream()

        StreamOnlyResponse(HttpServletResponse delegate) { super(delegate) }

        @Override
        PrintWriter getWriter() {
            throw new IllegalStateException('getOutputStream() has already been called for this response')
        }

        @Override
        ServletOutputStream getOutputStream() {
            ByteArrayOutputStream sink = captured
            return new ServletOutputStream() {
                @Override void write(int b) { sink.write(b) }
                @Override boolean isReady() { true }
                @Override void setWriteListener(WriteListener l) { }
            }
        }

        String capturedAsString() { captured.toString(StandardCharsets.UTF_8.name()) }
    }

    private static Throwable newPsqlException(String message) {
        Class<?> psqlCls = Class.forName('org.postgresql.util.PSQLException')
        Class<?> stateCls = Class.forName('org.postgresql.util.PSQLState')
        def state = stateCls.getDeclaredField('DATA_ERROR').get(null)
        return (Throwable) psqlCls.getConstructor(String, stateCls).newInstance(message, state)
    }

    def "HTML panel still delivered when downstream already took the output stream"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        req.addHeader('Accept', 'text/html')
        def resp = new StreamOnlyResponse(new MockHttpServletResponse())
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw newPsqlException('db down mid-render') }
        // The filter must NOT itself throw IllegalStateException trying
        // to grab getWriter() — it falls back to the byte stream.
        noExceptionThrown()
        resp.status == 500
        resp.capturedAsString().contains('skinbox.market')
        resp.capturedAsString().contains('500')
        resp.capturedAsString().contains('Service unavailable')
        // No framework-internal leakage even on the fallback path.
        !resp.capturedAsString().contains('PSQL')
        !resp.capturedAsString().contains('SQLSTATE')
    }

    def "JSON envelope still delivered on /api/** when output stream already taken"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.addHeader('Accept', 'application/json')
        def resp = new StreamOnlyResponse(new MockHttpServletResponse())
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args ->
            // Shape a real repository throws: Spring-translated wrapper
            // around the raw JDBC PSQLException.
            throw new DataAccessResourceFailureException('pool exhausted', newPsqlException('boom'))
        }
        noExceptionThrown()
        resp.status == 500
        resp.capturedAsString().contains('"status":500')
        resp.capturedAsString().contains('"catastrophic":true')
        !resp.capturedAsString().contains('<html')
        !resp.capturedAsString().contains('<!doctype')
    }

    def "panel bytes on the stream-fallback path are valid UTF-8"() {
        given:
        def req = new MockHttpServletRequest('GET', '/profile')
        req.addHeader('Accept', 'text/html')
        def resp = new StreamOnlyResponse(new MockHttpServletResponse())
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw newPsqlException('encoding blip') }
        noExceptionThrown()
        // Round-trips cleanly — no mojibake from a wrong-charset write.
        byte[] raw = resp.captured.toByteArray()
        new String(raw, StandardCharsets.UTF_8).contains('Service unavailable')
        raw.length > 0
    }

    def "non-catastrophic exception still bubbles even when stream is taken"() {
        // The stream-sink fallback must not change the swallow decision:
        // a non-DB exception still propagates untouched.
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new StreamOnlyResponse(new MockHttpServletResponse())
        FilterChain chain = Mock()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw new IllegalStateException('not a DB outage') }
        // The original IllegalStateException bubbles — the filter does
        // not mistake it for the getWriter() conflict and swallow it.
        thrown(IllegalStateException)
        resp.captured.size() == 0
    }
}
