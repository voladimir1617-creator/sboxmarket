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
 * Session handling: a bearer-token call is stateless by nature, so we
 * never inherit or mutate a pre-existing browser session. If the
 * request happens to carry a `JSESSIONID` cookie we ROTATE it
 * (invalidate + fresh session) before stamping SESSION_USER_ID — the
 * same session-fixation defence SteamAuthController applies on login.
 * Without the rotation an API request carrying someone else's (or a
 * fixated) `JSESSIONID` would permanently rewrite that browser
 * session's `steamUserId` to the API key's owner — a cross-user
 * identity-confusion bug. The fresh session also guarantees no stale
 * `steamSessionEpoch` rides along (SessionEpochFilter skips API-key
 * requests via `sbox.apiAuth`, but a clean session is correct anyway).
 *
 * Runs at @Order(2) — AFTER CorrelationId (@Order(0)) + BodySizeLimit
 * (@Order(1)) but BEFORE CSRF (@Order(3)), SessionEpoch (@Order(4)) and
 * RateLimit (@Order(5)) so every downstream filter sees the
 * authenticated session context and the `sbox.apiAuth` marker.
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
        if (ctx == null || ctx.userId == null) {
            // ctx == null: unknown / revoked key. ctx.userId == null:
            // a resolved key with no owning user id is corrupt state —
            // reject rather than stamp a null SESSION_USER_ID. Both
            // fail closed with the same 401 (no oracle).
            resp.status = 401
            resp.contentType = 'application/json'
            resp.writer.write('{"code":"INVALID_API_KEY","message":"Invalid or revoked API key"}')
            return
        }
        def method = req.method?.toUpperCase()
        // Fail CLOSED on the RO write-gate: an unknown (null) method is
        // treated as a write so a read-only key can never slip a
        // mutation through on a pathological request with no method.
        boolean isWrite = method == null || !SAFE_METHODS.contains(method)
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
        //
        // Session-fixation / cross-user-contamination guard: a bearer
        // call is stateless, so we must NOT clobber whatever session the
        // request happened to arrive with. If a `JSESSIONID` cookie is
        // present, `getSession(true)` would hand back THAT session and
        // `setAttribute(SESSION_USER_ID, …)` would permanently rewrite
        // its `steamUserId` — switching a logged-in browser to the API
        // key's owner, or writing auth state onto an attacker-fixated
        // session id. So: if a session already exists and is NOT already
        // owned by this exact user, rotate it (invalidate + fresh) — the
        // same defence SteamAuthController applies on Steam login. A
        // session that already belongs to this user is reused untouched.
        try {
            def existing = req.getSession(false)
            def session
            if (existing == null) {
                session = req.getSession(true)
            } else if ((existing.getAttribute(SteamAuthController.SESSION_USER_ID) as Long) == (ctx.userId as Long)) {
                session = existing
            } else {
                try { existing.invalidate() } catch (Exception ignore) {}
                session = req.getSession(true)
            }
            session.setAttribute(SteamAuthController.SESSION_USER_ID, ctx.userId as Long)
        } catch (Exception e) {
            // A session failure here means requireUser(req) downstream
            // will throw UnauthorizedException — fail closed, no leak.
            log.warn("Failed to populate session for API key uid=${ctx.userId}: ${e.message}")
        }
        req.setAttribute('sbox.apiAuth', Boolean.TRUE)
        req.setAttribute('sbox.apiScope', scope)
        req.setAttribute('sbox.apiUserId', ctx.userId as Long)
        chain.doFilter(req, resp)
    }
}
