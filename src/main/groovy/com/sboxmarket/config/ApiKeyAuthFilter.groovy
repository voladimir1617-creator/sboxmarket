package com.sboxmarket.config

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.service.ApiKeyService
import groovy.util.logging.Slf4j
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

import java.util.concurrent.ConcurrentHashMap

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
 * Session handling: a bearer-token call is stateless. The request is
 * wrapped so getSession() returns a request-scoped in-memory session
 * that the container never sees: no session cookie is issued, nothing
 * outlives the request, and any browser session riding on the request
 * is left untouched. (Before 2026-09-28 the filter stamped a real
 * container session, whose cookie turned a read-only key into a
 * year-long read-write login that survived key revocation.)
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
        // Populate a REQUEST-SCOPED session so every downstream
        // controller's requireUser(req) sees the authenticated user.
        //
        // The session is never handed to the servlet container: the
        // wrapper below answers getSession() with an in-memory object
        // that dies with this request. That closes two holes the old
        // code had when it called the real req.getSession(true):
        //   1. Scope escalation. The container set a SBOX_SESSION cookie
        //      on the response, and that cookie alone (no bearer header,
        //      so no RO write-gate) was a full read-write login for a
        //      year, surviving key revocation. A read-only key became a
        //      read-write (or admin) session.
        //   2. Heap growth. Cookie-less API clients minted a new
        //      long-lived session on every call.
        // A pre-existing browser session on the request is neither read
        // nor modified, so there is no cross-user contamination either.
        if (forbiddenForApiKey(path, isWrite)) {
            resp.status = 403
            resp.contentType = 'application/json'
            resp.writer.write('{"code":"API_KEY_FORBIDDEN","message":"API keys cannot use this endpoint. Sign in on the website instead."}')
            log.warn("API key refused on account/staff path: ${method} ${path} uid=${ctx.userId}")
            return
        }
        def apiReq = new ApiKeyRequest(req, new RequestScopedSession(req.servletContext))
        apiReq.getSession(true).setAttribute(SteamAuthController.SESSION_USER_ID, ctx.userId as Long)
        apiReq.setAttribute('sbox.apiAuth', Boolean.TRUE)
        apiReq.setAttribute('sbox.apiScope', scope)
        apiReq.setAttribute('sbox.apiUserId', ctx.userId as Long)
        chain.doFilter(apiReq, resp)
    }

    /** Request wrapper that hides the container session and serves the
     *  request-scoped one instead. */
    /** Staff tools: refused to API keys for every method, so an admin's
     *  bot key (even a read-only one) is never an admin session. */
    static final List<String> STAFF_PREFIXES = ['/api/admin', '/api/csr'].asImmutable()
    /** Account-control writes a leaked key must not reach: minting more
     *  keys (survives revoking the leaked one), and changing email, trade
     *  URL, 2FA or the payout account (redirects items and money to the
     *  thief). Reading them stays allowed. */
    // /api/auth: a leaked key could POST logout-all and sign its owner out
    // of every browser while the key itself kept working.
    static final List<String> ACCOUNT_WRITE_PREFIXES = [
        '/api/api-keys', '/api/profile', '/api/wallet/connect', '/api/auth/steam/logout-all'
    ].asImmutable()

    static boolean forbiddenForApiKey(String path, boolean isWrite) {
        if (path == null) return false
        def under = { String p -> path == p || path.startsWith(p + '/') }
        if (STAFF_PREFIXES.any(under)) return true
        isWrite && ACCOUNT_WRITE_PREFIXES.any(under)
    }

    static class ApiKeyRequest extends HttpServletRequestWrapper {
        final RequestScopedSession apiSession

        ApiKeyRequest(HttpServletRequest req, RequestScopedSession apiSession) {
            super(req)
            this.apiSession = apiSession
        }

        @Override HttpSession getSession() { apiSession.valid ? apiSession : null }

        @Override HttpSession getSession(boolean create) {
            if (!apiSession.valid && create) apiSession.reset()
            apiSession.valid ? apiSession : null
        }

        @Override String changeSessionId() { apiSession.id }

        @Override String getRequestedSessionId() { null }

        @Override boolean isRequestedSessionIdValid() { false }
    }

    /** In-memory HttpSession that lives only for one bearer request. It
     *  is never registered with the container, so no cookie is issued. */
    static class RequestScopedSession implements HttpSession {
        final String id = 'api-' + UUID.randomUUID()
        final long creationTime = System.currentTimeMillis()
        final ServletContext servletContext
        private final Map<String, Object> attrs = new ConcurrentHashMap<>()
        boolean valid = true
        int maxInactiveInterval = 0

        RequestScopedSession(ServletContext ctx) { this.servletContext = ctx }

        void reset() { attrs.clear(); valid = true }

        @Override long getCreationTime() { creationTime }
        @Override String getId() { id }
        @Override long getLastAccessedTime() { creationTime }
        @Override ServletContext getServletContext() { servletContext }
        @Override void setMaxInactiveInterval(int interval) { maxInactiveInterval = interval }
        @Override int getMaxInactiveInterval() { maxInactiveInterval }
        @Override Object getAttribute(String name) { name == null ? null : attrs.get(name) }
        @Override Enumeration<String> getAttributeNames() { Collections.enumeration(new ArrayList<>(attrs.keySet())) }
        @Override void setAttribute(String name, Object value) {
            if (name == null) return
            if (value == null) attrs.remove(name) else attrs.put(name, value)
        }
        @Override void removeAttribute(String name) { if (name != null) attrs.remove(name) }
        @Override void invalidate() { attrs.clear(); valid = false }
        @Override boolean isNew() { true }
    }
}
