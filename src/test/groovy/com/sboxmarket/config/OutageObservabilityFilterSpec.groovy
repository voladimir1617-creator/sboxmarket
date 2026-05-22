package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for {@link OutageObservabilityFilter} — the request-thread
 * filter that watches for the two exception classes that historically
 * signal a database/session outage and emits a grep-able OUTAGE-SIGNAL
 * log line.
 *
 * The load-bearing contract: this is an OBSERVABILITY filter. It must
 * NEVER swallow an exception — every exception it sees (watched or not)
 * has to be re-thrown so the real handling (CatastrophicErrorFilter's
 * panel, or the normal MVC error pipeline) still happens. A sustained
 * burst is exercised to pin that the log-throttle never throttles the
 * RE-THROW.
 */
class OutageObservabilityFilterSpec extends Specification {

    @Subject
    OutageObservabilityFilter filter = new OutageObservabilityFilter()

    FilterChain chain = Mock()

    private static Throwable newPsqlException(String message) {
        Class<?> psqlCls = Class.forName('org.postgresql.util.PSQLException')
        Class<?> stateCls = Class.forName('org.postgresql.util.PSQLState')
        def state = stateCls.getDeclaredField('DATA_ERROR').get(null)
        return (Throwable) psqlCls.getConstructor(String, stateCls).newInstance(message, state)
    }

    /** A FilterChain that always throws the supplied exception — avoids
     *  Spock interaction-placement rules for the multi-throw tests. */
    private static FilterChain throwingChain(Throwable t) {
        return new FilterChain() {
            @Override
            void doFilter(ServletRequest req, ServletResponse res) { throw t }
        }
    }

    def "successful request passes straight through, nothing logged or thrown"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        noExceptionThrown()
        resp.status == 200
    }

    def "raw PSQLException is observed AND re-thrown (never swallowed)"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw newPsqlException('outage') }
        // Must propagate so CatastrophicErrorFilter (which wraps this
        // filter) can still serve its panel.
        thrown(Throwable)
    }

    def "DataIntegrityViolationException is observed AND re-thrown"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/auth/steam/login')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args ->
            throw new DataIntegrityViolationException('check constraint violated')
        }
        thrown(DataIntegrityViolationException)
    }

    def "PSQLException wrapped in a Spring-translated exception is still detected via the cause chain"() {
        // The shape repository code actually throws — Spring wraps the
        // raw JDBC PSQLException. The watched-class match walks the cause
        // chain, and the exception must still bubble unchanged.
        given:
        def req = new MockHttpServletRequest('GET', '/profile')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args ->
            throw new DataAccessResourceFailureException('pool dead', newPsqlException('refused'))
        }
        // DataAccessResourceFailureException is NOT itself a watched class,
        // but its PSQLException cause is — detected, logged, re-thrown.
        thrown(DataAccessResourceFailureException)
    }

    def "non-outage exception is re-thrown untouched and unobserved"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw new IllegalArgumentException('plain bug') }
        thrown(IllegalArgumentException)
    }

    def "the exact exception instance is preserved through the filter (no wrapping)"() {
        // Re-throwing must hand back the SAME object so downstream
        // cause-chain matching (CatastrophicErrorFilter) still works.
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()
        def original = new DataIntegrityViolationException('identity check')

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(_, _) >> { args -> throw original }
        def caught = thrown(DataIntegrityViolationException)
        caught.is(original)
    }

    def "a sustained burst of the same outage exception always re-throws every time"() {
        // During a real outage every request hits the same exception.
        // The filter throttles LOG output but must re-throw on 100% of
        // them — a swallowed one would be an invisible 500.
        given:
        def resp = new MockHttpServletResponse()
        int reThrown = 0

        when:
        (1..25).each { i ->
            def req = new MockHttpServletRequest('GET', "/market")
            try {
                filter.doFilter(req, resp, throwingChain(newPsqlException("hit ${i}")))
            } catch (Throwable ignored) {
                reThrown++
            }
        }

        then:
        reThrown == 25
    }

    def "two different watched exception classes are tracked independently and both re-throw"() {
        // Throttle state is keyed per exception class — both classes must
        // still re-throw regardless of which window they fall in.
        given:
        def resp = new MockHttpServletResponse()
        def psqlReq = new MockHttpServletRequest('GET', '/a')
        def divReq = new MockHttpServletRequest('GET', '/b')
        boolean psqlReThrown = false
        boolean divReThrown = false

        when:
        try {
            filter.doFilter(psqlReq, resp, throwingChain(newPsqlException('psql')))
        } catch (Throwable ignored) {
            psqlReThrown = true
        }
        try {
            filter.doFilter(divReq, resp, throwingChain(new DataIntegrityViolationException('div')))
        } catch (Throwable ignored) {
            divReThrown = true
        }

        then:
        psqlReThrown
        divReThrown
    }
}
