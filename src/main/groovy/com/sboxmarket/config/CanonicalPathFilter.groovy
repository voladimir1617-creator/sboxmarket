package com.sboxmarket.config

import groovy.util.logging.Slf4j
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

import java.util.regex.Pattern

/**
 * Rejects request paths that are spelled differently from the path Spring
 * routes them to, with a 400, before any other business filter runs.
 *
 * CsrfFilter, ApiKeyAuthFilter, SessionEpochFilter, RateLimitFilter and
 * PresenceFilter all decide what to do from `req.requestURI` prefixes
 * (`/api/`, `/api/wallet`, ...). requestURI is the RAW path, but Tomcat and
 * Spring strip `;matrix` parameters and percent-decode before routing. So
 * `/api;x/wallet` and `/%61pi/wallet` both reached WalletController while
 * every one of those filters saw a non-`/api/` path and stood aside:
 *   - a session revoked by "Sign out everywhere" kept working;
 *   - write requests skipped the CSRF token check;
 *   - wallet, bid, offer and sign-in rate limits did not count them.
 *
 * No legitimate client sends either shape: browsers and fetch() never put
 * `;` in our paths and never percent-encode unreserved characters
 * (RFC 3986 §2.3 says they must not). Encoded spaces and other reserved
 * characters in item names (`%20`, `%7C`) are still allowed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 200)
@Slf4j
class CanonicalPathFilter extends OncePerRequestFilter {

    /** `%` followed by the hex code of an unreserved character: A-Z, a-z,
     *  0-9, '-', '.', '_', '~'. Also catches an encoded '%' (%25) so
     *  double-encoding can't smuggle the same trick through a proxy that
     *  decodes once. */
    private static final Pattern ENCODED_UNRESERVED = Pattern.compile(
        '%(?:[46][1-9A-Fa-f]|[57][0-9Aa]|3[0-9]|2[DdEe5]|5[Ff]|7[Ee])')

    static boolean isNonCanonical(String rawPath) {
        if (rawPath == null) return false
        rawPath.indexOf(';') >= 0 || ENCODED_UNRESERVED.matcher(rawPath).find()
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        String raw = req.requestURI
        if (isNonCanonical(raw)) {
            log.warn("Rejected non-canonical path: ${req.method} ${raw?.take(200)}")
            resp.status = 400
            resp.contentType = 'application/json'
            resp.writer.write('{"code":"BAD_PATH","message":"Malformed request path."}')
            return
        }
        chain.doFilter(req, resp)
    }
}
