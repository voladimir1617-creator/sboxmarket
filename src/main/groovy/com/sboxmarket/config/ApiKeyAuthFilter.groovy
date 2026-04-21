package com.sboxmarket.config

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.service.ApiKeyService
import groovy.util.logging.Slf4j
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Bearer-token auth for third-party bots / extensions (batch 676).
 *
 * Pattern:
 *   Authorization: Bearer sbx_live_<32-hex-chars>
 *
 * When the header is present we hash-lookup the key via ApiKeyService,
 * refuse the request on a miss / revoked row with 401, and on a match
 * populate the session's SESSION_USER_ID so every existing controller's
 * `requireUser(req)` just works.
 *
 * Write-gate: keys issued with scope='RO' are refused on any non-safe
 * HTTP method (anything except GET / HEAD / OPTIONS / TRACE) with a
 * 403 + code `RO_KEY_WRITE_FORBIDDEN`. Full-access 'RW' keys have no
 * such gate and behave identically to a signed-in session.
 *
 * CSRF bypass: we mark the request with a `sbox.apiAuth` attribute so
 * CsrfFilter can skip the double-submit-cookie check — bearer-token
 * callers have no session cookie to pair with a CSRF header. Bypass
 * is safe because possession of the bearer token is itself the auth
 * factor (equivalent to a password in the OAuth2 sense).
 *
 * Runs at @Order(0) — BEFORE CorrelationId + CSRF + RateLimit so every
 * downstream filter sees the authenticated session context.
 */
@Component
@Order(2)
@Slf4j
class ApiKeyAuthFilter extends OncePerRequestFilter {

    @Autowired ApiKeyService apiKeyService

    private static final Set<String> SAFE_METHODS = ['GET', 'HEAD', 'OPTIONS', 'TRACE'] as Set

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        // Only intercept /api/* — the SPA shell, static assets, and
        // /sitemap.xml don't need API-key auth and shouldn't trip the
        // hash lookup on every page load.
        def path = req.requestURI
        if (path == null || !path.startsWith('/api/')) {
            chain.doFilter(req, resp)
            return
        }
        def auth = req.getHeader('Authorization')
        if (auth == null || !auth.startsWith('Bearer ')) {
            chain.doFilter(req, resp)
            return
        }
        def token = auth.substring(7).trim()
        if (!token || !token.startsWith('sbx_live_')) {
            // Not our bearer format — fall through to session-based auth
            // rather than rejecting. Lets third-party middleware (e.g.
            // Cloudflare Access) inject a `Bearer …` header that we
            // don't care about without tripping our 401 path.
            chain.doFilter(req, resp)
            return
        }
        def ctx
        try {
            ctx = apiKeyService.authenticateWithScope(token)
        } catch (Exception e) {
            log.warn("API-key authentication threw: ${e.message}")
            resp.status = 500
            resp.contentType = 'application/json'
            resp.writer.write('{"code":"INTERNAL_ERROR","message":"Authentication error"}')
            return
        }
        if (ctx == null) {
            resp.status = 401
            resp.contentType = 'application/json'
            resp.writer.write('{"code":"INVALID_API_KEY","message":"Invalid or revoked API key"}')
            return
        }
        def method = req.method?.toUpperCase()
        boolean isWrite = method != null && !SAFE_METHODS.contains(method)
        String scope = (ctx.scope ?: 'RW') as String
        if (scope == 'RO' && isWrite) {
            resp.status = 403
            resp.contentType = 'application/json'
            resp.writer.write('{"code":"RO_KEY_WRITE_FORBIDDEN","message":"This API key is read-only. Re-issue with scope=RW for write access."}')
            log.warn("RO key attempted write: ${method} ${path} uid=${ctx.userId}")
            return
        }
        // Populate the session + request attributes so every downstream
        // controller's requireUser(req) sees the authenticated user.
        // Creating a session here is cheap — it's a short-lived in-memory
        // object and the caller almost certainly has no JSESSIONID cookie
        // anyway. Also set the request attributes the CSRF filter reads
        // to skip its double-submit check.
        try {
            req.getSession(true).setAttribute(SteamAuthController.SESSION_USER_ID, ctx.userId as Long)
        } catch (Exception e) {
            log.warn("Failed to populate session for API key uid=${ctx.userId}: ${e.message}")
        }
        req.setAttribute('sbox.apiAuth', Boolean.TRUE)
        req.setAttribute('sbox.apiScope', scope)
        req.setAttribute('sbox.apiUserId', ctx.userId as Long)
        chain.doFilter(req, resp)
    }
}
