package com.sboxmarket

import com.sboxmarket.config.SessionCookieRefreshFilter
import com.sboxmarket.controller.SteamAuthController
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.web.server.Cookie
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.yaml.snakeyaml.Yaml
import spock.lang.Specification

import java.time.Duration

/**
 * Security review 2026-10-15, finding 15: sign-in lasts 30 days (was 365),
 * and stays signed in while the user keeps coming back.
 */
class SessionCookieRefreshFilterSpec extends Specification {

    static final long DAY = Duration.ofDays(1).toMillis()

    SessionCookieRefreshFilter filter = new SessionCookieRefreshFilter(serverProperties: props())

    private static ServerProperties props() {
        def p = new ServerProperties()
        def c = p.servlet.session.cookie
        c.name = 'SBOX_SESSION'
        c.maxAge = Duration.ofDays(30)
        c.httpOnly = true
        c.secure = true
        c.sameSite = Cookie.SameSite.LAX
        p
    }

    private MockHttpServletRequest signedIn(MockHttpSession s) {
        s.setAttribute(SteamAuthController.SESSION_USER_ID, 7L)
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.session = s
        req
    }

    def "the first signed-in request only starts the clock"() {
        given:
        def s = new MockHttpSession()
        def resp = new MockHttpServletResponse()

        when:
        filter.refreshIfDue(signedIn(s), resp, 1000L)

        then:
        resp.getHeader('Set-Cookie') == null
        s.getAttribute(SessionCookieRefreshFilter.REFRESHED_AT) == 1000L
    }

    def "within a day nothing is re-sent"() {
        given:
        def s = new MockHttpSession()
        s.setAttribute(SessionCookieRefreshFilter.REFRESHED_AT, 1000L)
        def resp = new MockHttpServletResponse()

        when:
        filter.refreshIfDue(signedIn(s), resp, 1000L + DAY - 1)

        then:
        resp.getHeader('Set-Cookie') == null
    }

    def "after a day the same session cookie is re-sent with a fresh 30-day max-age and the same flags"() {
        given:
        def s = new MockHttpSession()
        s.setAttribute(SessionCookieRefreshFilter.REFRESHED_AT, 1000L)
        def resp = new MockHttpServletResponse()

        when:
        filter.refreshIfDue(signedIn(s), resp, 1000L + DAY)

        then:
        def h = resp.getHeader('Set-Cookie')
        h.startsWith("SBOX_SESSION=${s.id};")
        h.contains('Max-Age=2592000')
        h.contains('Path=/')
        h.contains('HttpOnly')
        h.contains('Secure')
        h.contains('SameSite=Lax')
        s.getAttribute(SessionCookieRefreshFilter.REFRESHED_AT) == 1000L + DAY
    }

    def "anonymous sessions and API-key requests are never refreshed"() {
        given:
        def anon = new MockHttpServletRequest('GET', '/')
        anon.session = new MockHttpSession()
        def api = signedIn(new MockHttpSession())
        api.session.setAttribute(SessionCookieRefreshFilter.REFRESHED_AT, 0L)
        api.setAttribute('sbox.apiAuth', true)
        def r1 = new MockHttpServletResponse()
        def r2 = new MockHttpServletResponse()

        when:
        filter.refreshIfDue(anon, r1, 10 * DAY)
        filter.refreshIfDue(api, r2, 10 * DAY)

        then:
        r1.getHeader('Set-Cookie') == null
        r2.getHeader('Set-Cookie') == null
    }

    def "the shipped config is 30 days, not 365"() {
        given:
        def base = new Yaml().load(getClass().getResourceAsStream('/application.yml')) as Map
        def prod = new Yaml().load(getClass().getResourceAsStream('/application-prod.yml')) as Map

        expect:
        base.server.servlet.session.timeout == '30d'
        base.server.servlet.session.cookie.'max-age' == '30d'
        prod.spring.session.timeout == '30d'
    }
}
