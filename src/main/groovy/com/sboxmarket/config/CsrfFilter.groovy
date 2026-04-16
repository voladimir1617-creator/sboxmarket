package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

import java.security.SecureRandom

/*
 * Double-submit-cookie CSRF protection for state-changing API requests.
 *
 * Pattern:
 *   1) On every request, the filter makes sure an `sbox_csrf` cookie exists
 *      (HttpOnly=false so JS can read it; Secure in prod).
 *   2) On GET/HEAD/OPTIONS/TRACE the filter does nothing beyond setting the
 *      cookie — reads are never CSRF-guarded.
 *   3) On POST/PUT/PATCH/DELETE to `/api/**` the filter requires the client
 *      to echo the cookie value back in the `X-CSRF-Token` header. Missing
 *      or mismatched tokens return 403 with a JSON error body.
 *
 * Exemptions:
 *   - /api/stripe/webhook  — called by Stripe, no cookie. Signature check is
 *     done inside StripeService.handleWebhookEvent and is the source of truth.
 *   - /api/auth/steam/**   — Steam OpenID redirects can't carry a header.
 *
 * The frontend reads the cookie once on page load via `document.cookie`
 * and sends it back on every fetch as `X-CSRF-Token`.
 */
@Component
@Order(3)
@Slf4j
class CsrfFilter extends OncePerRequestFilter {

    private static final String COOKIE_NAME = 'sbox_csrf'
    private static final String HEADER_NAME = 'X-CSRF-Token'
    private static final int    TOKEN_BYTES = 24
    private static final SecureRandom RNG = new SecureRandom()

    private static final Set<String> SAFE_METHODS = ['GET','HEAD','OPTIONS','TRACE'] as Set
    private static final List<String> EXEMPT_PREFIXES = [
        '/api/stripe/webhook',
        '/api/auth/steam/login',
        '/api/auth/steam/return'
    ]

    @Value('${security.csrf-enabled:true}') boolean enabled
    @Value('${server.servlet.session.cookie.secure:false}') boolean secureCookie

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        // Make sure every response carries the csrf cookie — the frontend uses
        // this on its first request to learn the token value.
        def cookieValue = readCookie(req)
        if (!cookieValue) {
            cookieValue = newToken()
            def c = new Cookie(COOKIE_NAME, cookieValue)
            c.path = '/'
            c.maxAge = 60 * 60 * 24 * 7   // 7 days
            c.secure = secureCookie
            // NOT httpOnly — the frontend JS must be able to read it.
            resp.addCookie(c)
        }

        if (!enabled) {
            chain.doFilter(req, resp)
            return
        }

        def method = req.method?.toUpperCase()
        def path   = req.requestURI
        def isWrite = method && !SAFE_METHODS.contains(method)
        def isApi   = path?.startsWith('/api/')
        def isExempt = EXEMPT_PREFIXES.any { path.startsWith(it) }

        if (isWrite && isApi && !isExempt) {
            def header = req.getHeader(HEADER_NAME)
            if (!header || header != cookieValue) {
                resp.status = 403
                resp.contentType = 'application/json'
                resp.writer.write('{"code":"CSRF_MISMATCH","message":"Missing or invalid CSRF token. Refresh the page and try again."}')
                log.warn("CSRF rejected: ${method} ${path} (header=${header?.take(8)}.., cookie=${cookieValue?.take(8)}..)")
                return
            }
        }

        chain.doFilter(req, resp)
    }

    private static String readCookie(HttpServletRequest req) {
        def cs = req.cookies
        if (cs == null) return null
        def c = cs.find { it.name == COOKIE_NAME }
        c?.value
    }

    private static String newToken() {
        def b = new byte[TOKEN_BYTES]
        RNG.nextBytes(b)
        Base64.urlEncoder.withoutPadding().encodeToString(b)
    }
}
