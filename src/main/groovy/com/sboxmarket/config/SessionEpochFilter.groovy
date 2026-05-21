package com.sboxmarket.config

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Enforces the "log out everywhere" invariant.
 *
 * On login, SteamAuthController stashes the user's current sessionEpoch in
 * the HttpSession. This filter, on every /api/* request that carries an
 * authenticated session, reloads the user's live sessionEpoch and compares
 * the two. A mismatch means logoutAll (or a staff-initiated session wipe)
 * has happened since this cookie was issued — we invalidate the session
 * inline and respond 401 so the frontend falls back to the anon experience.
 *
 * Cheap: a single primary-key lookup, cached inside the current JPA
 * transaction. Skipped for the auth endpoints themselves (login/return/me)
 * to avoid chicken-and-egg issues — /me re-verifies the user anyway.
 *
 * Ordered after RateLimitFilter (1) / BodySizeLimitFilter (2) / CsrfFilter
 * (3) so a CSRF-rejected write never reaches the DB lookup.
 *
 * API-key requests are exempt. ApiKeyAuthFilter (Order 2) authenticates a
 * `Bearer sbx_live_…` request by creating a session and stamping it with
 * SESSION_USER_ID — but NOT with SESSION_EPOCH, because the bearer token
 * is its own auth factor and has its own kill switch (revoke / revoke-all
 * in ApiKeyService, enforced by the `key.revoked` check in
 * authenticateWithScope). The "log out everywhere" epoch governs browser
 * sessions only; the two kill switches are deliberately decoupled (see
 * ApiKeyService.revokeAll — the Sign-Out-Everywhere API-key sibling).
 * Without this exemption an API-key request carries a null stashed epoch,
 * so once the owning user has EVER used logout-all (which sets the live
 * epoch to a wall-clock millis value) every `(null ?: 0L) < live` check
 * trips and permanently 401s every otherwise-valid API key. The
 * `sbox.apiAuth` request attribute is set server-side by ApiKeyAuthFilter
 * only — a client cannot forge it — so honouring it here is safe.
 */
@Component
@Order(4)
@Slf4j
class SessionEpochFilter extends OncePerRequestFilter {

    @Autowired SteamUserRepository steamUserRepository

    private static final List<String> SKIP_PREFIXES = [
        '/api/auth/steam/login',
        '/api/auth/steam/return',
        '/api/auth/steam/me',
        '/api/auth/steam/logout',
        '/api/stripe/webhook'
    ]

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        def path = req.requestURI ?: ''
        if (!path.startsWith('/api/') || SKIP_PREFIXES.any { path.startsWith(it) }) {
            chain.doFilter(req, resp)
            return
        }
        // API-key-authenticated requests carry no browser-session epoch and
        // are governed by API-key revocation instead — skip the epoch check
        // (see the class Javadoc). The attribute is set server-side by
        // ApiKeyAuthFilter (Order 2, runs before this filter) and cannot be
        // forged by a client.
        if (Boolean.TRUE == req.getAttribute('sbox.apiAuth')) {
            chain.doFilter(req, resp)
            return
        }
        def session = req.getSession(false)
        if (session == null) {
            chain.doFilter(req, resp)
            return
        }
        def userId = session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (userId == null) {
            chain.doFilter(req, resp)
            return
        }
        def stashed = session.getAttribute(SteamAuthController.SESSION_EPOCH) as Long
        Long live = null
        try {
            live = steamUserRepository.findById(userId).orElse(null)?.sessionEpoch
        } catch (Exception e) {
            // DB blip shouldn't 401 every request — log and fall through.
            log.warn("SessionEpochFilter DB lookup failed for user ${userId}: ${e.message}")
            chain.doFilter(req, resp)
            return
        }
        if (live != null && (stashed ?: 0L) < live) {
            log.info("Stale session epoch: user=${userId} stashed=${stashed} live=${live} path=${path}")
            try { session.invalidate() } catch (Exception ignore) {}
            resp.status = 401
            resp.contentType = 'application/json'
            resp.writer.write('{"code":"SESSION_REVOKED","message":"This session was revoked. Please sign in again."}')
            return
        }
        chain.doFilter(req, resp)
    }
}
