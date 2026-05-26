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

import java.util.concurrent.ConcurrentHashMap

/**
 * V61 — bumps `steam_users.last_seen_at` on every authenticated request so
 * the marketplace can render real "Online now" presence dots next to
 * seller names instead of the deterministic-seed fallback (see ship #34
 * in production_checklist.md).
 *
 * Throttled to one DB write per 60 seconds per user via an in-memory
 * `userId → lastWriteMs` map. A user actively browsing 100 pages/min
 * triggers exactly one persist per minute; idle users incur zero writes.
 *
 * Container restart wipes the map — the worst case is one extra UPDATE
 * per active user immediately after restart, then the throttle resumes.
 *
 * Ordered LAST among the business filters (after SessionEpochFilter at 4
 * AND after RateLimitFilter at 5) so a revoked session doesn't get a
 * phantom presence bump on its way to a 401, AND a rate-limited request
 * (returning 429 from RateLimitFilter without continuing the chain)
 * doesn't trigger a `last_seen_at` UPDATE for the throttled user. With
 * a colliding @Order(5) on both filters the chain position was non-
 * deterministic and PresenceFilter could win the race, causing the
 * exact phantom-presence bug the comment above warns about. Skipped
 * for the Steam auth endpoints + Stripe webhook, same reasoning as
 * SessionEpochFilter.
 *
 * Designed to be silent on failure — a DB blip during the throttled
 * write must not 5xx the user's actual page load.
 */
@Component
@Order(6)
@Slf4j
class PresenceFilter extends OncePerRequestFilter {

    @Autowired SteamUserRepository steamUserRepository

    /** Throttle window — one DB write per user per minute. Active browsing
     *  costs ~1 UPDATE/minute; idle users 0. Public for test injection. */
    static final long THROTTLE_MS = 60_000L

    /** In-memory `userId → lastPersistedAtMs`. ConcurrentHashMap so the
     *  filter is thread-safe without locking. Bounded only by total user
     *  count; a million-user instance ~16 MB worst-case (Long+Long entries). */
    private final ConcurrentHashMap<Long, Long> lastWritten = new ConcurrentHashMap<>()

    /** Skip prefixes — same set as SessionEpochFilter to avoid spurious
     *  presence bumps on auth lifecycle endpoints + the Stripe webhook
     *  (which has no user session). Static probes (/api/health) also
     *  excluded so anonymous status checks don't waste cycles. */
    private static final List<String> SKIP_PREFIXES = [
        '/api/auth/steam/login',
        '/api/auth/steam/return',
        '/api/auth/steam/logout',
        '/api/stripe/webhook',
        '/api/health'
    ]

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        try {
            def path = req.requestURI ?: ''
            if (path.startsWith('/api/') && !SKIP_PREFIXES.any { path.startsWith(it) }) {
                bumpIfDue(req)
            }
        } catch (Exception e) {
            // Defence in depth: nothing this filter does should ever
            // tank a request. Log and proceed.
            log.debug("PresenceFilter bump skipped: ${e.message}")
        }
        chain.doFilter(req, resp)
    }

    private void bumpIfDue(HttpServletRequest req) {
        def session = req.getSession(false)
        if (session == null) return
        def userId = session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (userId == null) return
        long now = System.currentTimeMillis()
        Long last = lastWritten.get(userId)
        if (last != null && (now - last) < THROTTLE_MS) return
        // CAS so two concurrent requests for the same user don't both
        // fire UPDATEs. The losing thread silently skips.
        if (last == null) {
            if (lastWritten.putIfAbsent(userId, now) != null) return
        } else {
            if (!lastWritten.replace(userId, last, now)) return
        }
        try {
            steamUserRepository.updateLastSeenAt(userId, now)
        } catch (Exception e) {
            // DB blip — back the throttle off so a retry can land soon.
            lastWritten.remove(userId, now)
            log.debug("PresenceFilter persist failed for user ${userId}: ${e.message}")
        }
    }
}
