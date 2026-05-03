package com.sboxmarket.config

import groovy.util.logging.Slf4j
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Strips embedded NUL (0x00) bytes — and any other non-printable control
 * characters that have no business being in a UUID — from inbound SESSION
 * cookies before Spring Session JDBC sees them.
 *
 * **Why this exists:**
 * On 2026-04-30 the prod log filled with
 * `org.postgresql.util.PSQLException: invalid byte sequence for encoding
 * "UTF8": 0x00` on every request that carried a malformed SESSION cookie.
 * The bound parameter for `WHERE SA.SESSION_ID = ?` contained a literal
 * NUL byte, which Postgres rejects on text columns. The user couldn't
 * recover because their browser kept replaying the bad cookie on every
 * refresh, so the site 500'd until they manually cleared cookies.
 *
 * Spring Session's own filter has no recovery path — it just hands the
 * cookie value straight into the SQL parameter. So we pre-clean here:
 * any cookie value that contains a NUL or other control char gets
 * replaced with a safe scrubbed copy. If the scrubbed value no longer
 * looks like a UUID-shaped session id, the cookie is dropped entirely
 * (the request is treated as anonymous and Spring Session mints a fresh
 * row), and the response sets an expired Set-Cookie so the browser stops
 * replaying the corrupt one.
 *
 * Runs HIGHEST_PRECEDENCE + 5 so it sits in front of Spring Session,
 * the security chain, and any controller that reads cookies. The +5 is
 * a defensive offset — leaves a tiny window for an even-earlier filter
 * (CORS, etc.) without being literally first.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@Slf4j
class SessionCookieSanitizerFilter implements Filter {

    /** Cookies that carry the session token. The original implementation
     *  hardcoded `SESSION` (Spring Session's default), but Tomcat's
     *  `server.servlet.session.cookie.name` is set to `SBOX_SESSION` in
     *  application.yml — so the cookie that actually got NUL-poisoned
     *  in production was `SBOX_SESSION` and the sanitizer never fired.
     *  Both names are checked now, and as a defence-in-depth EVERY
     *  inbound cookie is scanned for NUL/control bytes (no cookie value
     *  has any business carrying them; a control byte anywhere in the
     *  jar means the request is corrupt or hostile). */
    private static final Set<String> SESSION_COOKIE_NAMES = ['SESSION', 'SBOX_SESSION'] as Set

    /** Any control character in a UUID-shaped value is corruption. Includes
     *  NUL (0x00) which is the headline failure mode but also CR/LF/etc. */
    private static final java.util.regex.Pattern CONTROL_CHARS = ~/\p{Cntrl}/

    /** Spring Session JDBC stores the session id as the cookie value with a
     *  Base64-style encoding — broadly `[A-Za-z0-9+/=_-]+`. Anything outside
     *  that set is junk. */
    private static final java.util.regex.Pattern VALID_TOKEN = ~/^[A-Za-z0-9+\/=_\-]{1,128}$/

    @Override
    void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        if (!(req instanceof HttpServletRequest) || !(res instanceof HttpServletResponse)) {
            chain.doFilter(req, res)
            return
        }
        HttpServletRequest httpReq = (HttpServletRequest) req
        HttpServletResponse httpRes = (HttpServletResponse) res

        Cookie[] cookies = httpReq.cookies
        if (cookies == null || cookies.length == 0) {
            chain.doFilter(httpReq, httpRes)
            return
        }

        boolean dirty = false
        List<Cookie> kept = []
        for (Cookie c : cookies) {
            String raw = c.value ?: ''
            String scrubbed = CONTROL_CHARS.matcher(raw).replaceAll('')
            boolean isSessionCookie = SESSION_COOKIE_NAMES.contains(c.name)

            // Defence-in-depth: ANY cookie carrying a control byte is corrupt
            // — no legitimate value has NUL/CR/LF embedded. Strip the control
            // bytes silently for non-session cookies (preserve the value),
            // drop session cookies entirely (the token is unrecoverable).
            if (scrubbed == raw && (!isSessionCookie || VALID_TOKEN.matcher(raw).matches())) {
                kept << c
                continue
            }

            if (isSessionCookie) {
                // Session token can't be salvaged. Drop the cookie + clear
                // it on the response so the browser stops replaying it.
                dirty = true
                log.warn("Dropping malformed ${c.name} cookie (len=${raw.length()}, control-chars=${raw.length() - scrubbed.length()})")
                Cookie kill = new Cookie(c.name, '')
                kill.path = '/'
                kill.maxAge = 0
                kill.httpOnly = true
                kill.secure = httpReq.secure
                httpRes.addCookie(kill)
            } else {
                // Non-session cookie: preserve a scrubbed copy so any
                // downstream consumer reads cleaned bytes, not corrupt ones.
                dirty = true
                log.debug("Sanitised ${c.name} cookie (stripped ${raw.length() - scrubbed.length()} control chars)")
                Cookie cleanedCookie = new Cookie(c.name, scrubbed)
                cleanedCookie.path = c.path
                cleanedCookie.domain = c.domain
                cleanedCookie.maxAge = c.maxAge
                cleanedCookie.secure = c.secure
                cleanedCookie.httpOnly = c.httpOnly
                kept << cleanedCookie
            }
        }

        if (!dirty) {
            chain.doFilter(httpReq, httpRes)
            return
        }
        // Re-present the request with the cleaned cookie set so anything
        // downstream sees only valid cookies. ALSO wrap the raw Cookie
        // header — Spring Session's DefaultCookieSerializer can read the
        // header directly via getHeader('Cookie') / getHeaders('Cookie')
        // instead of getCookies(), so wrapping ONLY getCookies() lets a
        // raw-NUL byte sail through into the JDBC bind parameter.
        // Reconstruct the header from the cleaned Cookie[] so both
        // pathways see identical, sanitized data.
        Cookie[] cleaned = kept.toArray(new Cookie[0])
        String cleanedHeader = cleaned.collect { c -> "${c.name}=${c.value}" }.join('; ')
        HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(httpReq) {
            @Override
            Cookie[] getCookies() { cleaned }

            @Override
            String getHeader(String name) {
                if (name != null && name.equalsIgnoreCase('Cookie')) {
                    return cleanedHeader.isEmpty() ? null : cleanedHeader
                }
                return super.getHeader(name)
            }

            @Override
            java.util.Enumeration<String> getHeaders(String name) {
                if (name != null && name.equalsIgnoreCase('Cookie')) {
                    return cleanedHeader.isEmpty()
                        ? java.util.Collections.emptyEnumeration()
                        : java.util.Collections.enumeration([cleanedHeader])
                }
                return super.getHeaders(name)
            }
        }
        chain.doFilter(wrapped, httpRes)
    }
}
