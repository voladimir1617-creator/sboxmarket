package com.sboxmarket.config

import com.sboxmarket.controller.SteamAuthController
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

import java.time.Duration

/**
 * Keeps a signed-in user's 30-day session cookie sliding.
 *
 * The session lasts 30 days (server.servlet.session.timeout and the
 * cookie's max-age; security review 2026-10-15, finding 15, was 365 days).
 * On the server that timeout already counts from the last request, but the
 * browser's copy of the cookie counts from when it was SET, and Tomcat only
 * sets it when the session is created. Without this filter an active user
 * would be signed out 30 days after signing in however much they used the
 * site.
 *
 * So, at most once a day per session, a signed-in request gets the same
 * cookie re-sent with a fresh max-age: someone who visits at least once a
 * month stays signed in, and a cookie that sits unused for 30 days dies.
 * The session id is unchanged; only the expiry moves.
 *
 * Ordered after SessionEpochFilter (4), so a session that filter just
 * revoked is already gone and never refreshed.
 */
@Component
@Order(5)
class SessionCookieRefreshFilter extends OncePerRequestFilter {

    /** Session attribute: when this session's cookie was last re-sent (epoch ms). */
    static final String REFRESHED_AT = 'sboxCookieRefreshedAt'
    static final long REFRESH_EVERY_MS = Duration.ofDays(1).toMillis()
    static final Duration DEFAULT_MAX_AGE = Duration.ofDays(30)

    @Autowired(required = false) ServerProperties serverProperties

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        try { refreshIfDue(req, resp, System.currentTimeMillis()) }
        catch (Exception ignored) { /* never block a request over a cookie refresh */ }
        chain.doFilter(req, resp)
    }

    void refreshIfDue(HttpServletRequest req, HttpServletResponse resp, long now) {
        def session = req.getSession(false)
        if (session == null) return
        if (session.getAttribute(SteamAuthController.SESSION_USER_ID) == null) return
        if (req.getAttribute('sbox.apiAuth') != null) return   // API-key requests carry no browser cookie
        Long last = session.getAttribute(REFRESHED_AT) as Long
        if (last == null) {
            // First signed-in request on this session: the cookie was just
            // set at sign-in, so only start the clock.
            session.setAttribute(REFRESHED_AT, now)
            return
        }
        if (now - last < REFRESH_EVERY_MS) return
        session.setAttribute(REFRESHED_AT, now)
        resp.addHeader(HttpHeaders.SET_COOKIE, buildCookie(session.id).toString())
    }

    ResponseCookie buildCookie(String sessionId) {
        def c = serverProperties?.servlet?.session?.cookie
        def maxAge = c?.maxAge ?: DEFAULT_MAX_AGE
        def b = ResponseCookie.from(c?.name ?: 'SBOX_SESSION', sessionId)
            .path(c?.path ?: '/')
            .maxAge(maxAge)
            .httpOnly(c?.httpOnly == null ? true : c.httpOnly)
            .secure(Boolean.TRUE == c?.secure)
            .sameSite(c?.sameSite?.attributeValue() ?: 'Lax')
        if (c?.domain) b.domain(c.domain)
        b.build()
    }
}
