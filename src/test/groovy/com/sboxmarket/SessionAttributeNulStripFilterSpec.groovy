package com.sboxmarket

import com.sboxmarket.config.SessionAttributeNulStripFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the defence-in-depth session-attribute NUL stripper.
 *
 * The filter wraps the request so {@code getSession()} hands back a
 * decorator whose {@code setAttribute} silently strips NUL (0x00) bytes
 * from String values before they can reach Spring Session's JDBC save
 * path (and the V60 Postgres CHECK constraint). A NUL inside a TEXT
 * column of {@code spring_session} detonated the site on 2026-05-02
 * (PSQLException SQLSTATE 22021 on every read).
 *
 * Exercised through raw Servlet mocks — one filter instance, a
 * MockHttpServletRequest, a stub FilterChain. The NUL char is built via
 * {@code (char) 0} (never a source literal) so editors/tools can't strip
 * it out of this file.
 */
class SessionAttributeNulStripFilterSpec extends Specification {

    @Subject
    SessionAttributeNulStripFilter filter = new SessionAttributeNulStripFilter()

    FilterChain chain = Mock()

    /** NUL built via char arithmetic — the test source never carries a 0x00. */
    private static final String NUL = String.valueOf((char) 0)

    def "non-HTTP servlet request passes straight through, no wrapping"() {
        given:
        ServletRequest req = Mock(ServletRequest)
        ServletResponse resp = Mock(ServletResponse)

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        noExceptionThrown()
    }

    def "getSession() returns a NUL-stripping decorator, not the raw session"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            wrapped.getSession()?.class?.simpleName == 'NulStrippingHttpSession'
        }, resp)
    }

    def "getSession(false) returns null when there is no session — no NPE"() {
        given:
        // No session created on the request; getSession(false) must not
        // mint one, and the decorator must not be applied to a null.
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            // getSession(false) on the wrapper must hand back a plain null,
            // not a NulStrippingHttpSession wrapping a null — and must not
            // throw an NPE.
            wrapped.getSession(false) == null
        }, resp)
    }

    def "getSession(true) creates and wraps a session"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            wrapped.getSession(true)?.class?.simpleName == 'NulStrippingHttpSession'
        }, resp)
    }

    def "setAttribute strips an embedded NUL byte from a String value before delegating"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()
        String poisoned = "good${NUL}value"

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            wrapped.getSession().setAttribute('k', poisoned)
            true
        }, resp)

        and: "the value that actually landed on the real session has no NUL"
        String stored = req.getSession(false).getAttribute('k')
        stored == 'goodvalue'
        stored.indexOf((int) ((char) 0)) < 0
    }

    def "setAttribute strips MULTIPLE NUL bytes (replace, not just first occurrence)"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()
        String poisoned = "${NUL}a${NUL}b${NUL}"

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            wrapped.getSession().setAttribute('multi', poisoned)
            true
        }, resp)

        and:
        req.getSession(false).getAttribute('multi') == 'ab'
    }

    def "setAttribute leaves a clean String value untouched"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            wrapped.getSession().setAttribute('clean', 'no-nul-here')
            true
        }, resp)

        and:
        req.getSession(false).getAttribute('clean') == 'no-nul-here'
    }

    def "setAttribute passes a non-String value (Long) straight through unchanged"() {
        // Every real setAttribute call site stashes server-side Long ids —
        // the filter must not interfere with those (no String coercion).
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            wrapped.getSession().setAttribute('userId', 42L)
            true
        }, resp)

        and:
        def stored = req.getSession(false).getAttribute('userId')
        stored instanceof Long && stored == 42L
    }

    def "setAttribute tolerates a null value without throwing"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            // null is not a String → falls to the plain delegate path.
            wrapped.getSession().setAttribute('nullable', null)
            true
        }, resp)
        noExceptionThrown()
    }

    def "decorator delegates getAttribute / removeAttribute / getId to the real session"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()
        def captured = []

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            captured << wrapped.getSession()
        }, resp)

        and: "getAttribute / setAttribute / removeAttribute / getId all delegate"
        def decorated = captured[0]
        decorated != null
        decorated.id != null
        decorated.id == req.getSession(false).id   // same underlying session

        when: "an attribute is set then removed through the decorator"
        decorated.setAttribute('x', 'v1')

        then:
        decorated.getAttribute('x') == 'v1'
        req.getSession(false).getAttribute('x') == 'v1'   // landed on the real session

        when:
        decorated.removeAttribute('x')

        then:
        decorated.getAttribute('x') == null
    }

    def "decorator delegates invalidate() to the real session"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()
        // Hold a direct reference to the real session BEFORE the filter runs
        // so we can assert on it after the decorator invalidates it.
        HttpSession realSession = req.getSession(true)

        when: "the filter runs and the decorator's invalidate() is called"
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter({ HttpServletRequest wrapped ->
            wrapped.getSession().invalidate()
            true
        }, resp)

        when: "the real underlying session is touched after invalidation"
        realSession.getAttribute('anything')

        then: "it throws — proving invalidate() was delegated through the decorator"
        // MockHttpSession throws IllegalStateException once invalidated.
        thrown(IllegalStateException)
    }
}
