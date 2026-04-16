package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import groovy.util.logging.Slf4j
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/*
 * Dead-simple in-memory token bucket rate limiter for state-changing API
 * endpoints. Bucket is keyed by client IP + path prefix so each critical
 * surface (buy, offers, bids, buy-orders, auth, deposit, withdraw, steam,
 * support) gets its own budget.
 *
 * Hard bounds chosen to be friendly for real users but punishing for scripts:
 * 20 writes per 10 seconds per IP per surface. The map is bounded by
 * evicting the oldest entry when it grows past 10k rows so a DoS of unique
 * IPs can't exhaust memory.
 *
 * This is intentionally NOT a replacement for an edge rate limiter (Nginx /
 * Cloudflare) — it is the belt-and-braces layer on top of one.
 */
@Component
@Order(5)
@Slf4j
class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 10_000L
    private static final int  MAX_REQ   = 20
    // Reads are limited more generously — real browsing pulls listings and the
    // database on every filter click. 120/10s per IP is ~12 req/s sustained,
    // plenty for a fast clicker but a cliff for an attacker walking the entire
    // catalogue with unique query strings.
    private static final int  MAX_READ  = 120
    // Enumeration-sensitive endpoints (stall pages, user ids). Budget is
    // tight — 40/10s = 4 req/s — plenty for browsing a few sellers but a
    // hard cliff for anyone walking /stall/1 … /stall/N.
    private static final int  MAX_ENUM  = 40
    private static final int  MAX_KEYS  = 10_000

    // Write surfaces that get rate-limited hard. GETs are handled by GUARDED_READS below.
    private static final List<String> GUARDED_PREFIXES = [
        '/api/listings/',
        '/api/offers',
        '/api/bids',
        '/api/buy-orders',
        '/api/steam/list',
        '/api/auth/steam',
        '/api/wallet/deposit',
        '/api/wallet/withdraw',
        '/api/support/tickets'
    ]

    // Read surfaces that take free-text and can be used to enumerate or DoS.
    private static final List<String> GUARDED_READS = [
        '/api/listings',      // ?search= goes through here
        '/api/database',      // ?q= goes through here
        '/api/admin/users',   // ?search= (admin only, but still guarded)
        '/api/items',         // ?q= does a LIKE '%q%' scan
        '/api/loadouts'       // ?search= on /discover does a LIKE scan
    ]

    // GETs that leak per-user or per-id state. These are rate-limited even
    // without a search/q param because the attack is walking ids, not
    // crafting free-text payloads. Matches prefix-style so /stall/{n},
    // /inventory/{n}, /profile/{n} etc. all fall into the same bucket.
    private static final List<String> GUARDED_ENUMS = [
        '/api/listings/stall/',
        '/api/listings/item/',
        '/api/users/',
        '/api/items/',
        '/api/offers/thread/',
        '/api/bids/listing/',
        '/api/reviews/user/',
        // Steam inventory fetch makes an outbound HTTP call per request.
        // Without a cap, a logged-in attacker can proxy-DoS Steam through
        // us and exhaust Tomcat threads (10s timeout each).
        '/api/steam/'
    ]

    private static class Bucket {
        volatile long windowStart
        final AtomicLong count = new AtomicLong(0)
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>()

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        def method = req.method
        def path = req.requestURI
        def isGet = 'GET'.equalsIgnoreCase(method)

        // Health probe is never rate-limited. Docker/k8s health checks hit
        // this endpoint every few seconds from the same internal IP — rate
        // limiting would eventually flip the container into unhealthy even
        // though nothing is wrong. Exempt it explicitly.
        if (path == '/api/health' || path == '/api/health/') {
            chain.doFilter(req, resp)
            return
        }

        // Pick the right surface + budget based on the request shape.
        String guarded = null
        int budget = MAX_REQ
        if (isGet) {
            // Enumeration-sensitive prefixes (stall/{id}, items/{id}, …)
            // are always rate-limited because the attack is walking ids.
            guarded = GUARDED_ENUMS.find { path.startsWith(it) }
            if (guarded) {
                budget = MAX_ENUM
            } else {
                // Heavy search/text surfaces are only limited when the
                // free-text param is present — plain /api/listings with
                // no search stays unlimited for normal browsing.
                def hasSearch = (req.getParameter('search') || req.getParameter('q'))
                if (hasSearch) {
                    guarded = GUARDED_READS.find { path == it || path.startsWith(it + '?') || path.startsWith(it + '/') }
                    if (guarded) budget = MAX_READ
                }
            }
        } else {
            guarded = GUARDED_PREFIXES.find { path.startsWith(it) }
            budget = MAX_REQ
        }

        if (guarded == null) {
            chain.doFilter(req, resp)
            return
        }

        def key = clientIp(req) + '|' + guarded
        def bucket = buckets.computeIfAbsent(key) { new Bucket(windowStart: System.currentTimeMillis()) }
        def now = System.currentTimeMillis()
        if (now - bucket.windowStart > WINDOW_MS) {
            bucket.windowStart = now
            bucket.count.set(0)
        }
        long current = bucket.count.incrementAndGet()

        // Bounded eviction to keep the map from growing without limit. Cheap
        // because we only pay the cost when the map is huge *and* we're
        // already inside a rate-limit check for a guarded surface.
        if (buckets.size() > MAX_KEYS) {
            def oldest = buckets.entrySet().min { it.value.windowStart }
            if (oldest) buckets.remove(oldest.key)
        }

        if (current > budget) {
            resp.status = 429
            resp.contentType = 'application/json'
            resp.setHeader('Retry-After', String.valueOf(Math.max(1, ((WINDOW_MS - (now - bucket.windowStart)) / 1000L) as long)))
            resp.writer.write('{"code":"RATE_LIMITED","message":"Too many requests. Please slow down."}')
            log.warn("Rate limit hit: key=${key}, count=${current}, budget=${budget}")
            return
        }
        chain.doFilter(req, resp)
    }

    private static String clientIp(HttpServletRequest req) {
        def xff = req.getHeader('X-Forwarded-For')
        if (xff) return xff.split(',')[0].trim()
        req.remoteAddr
    }
}
