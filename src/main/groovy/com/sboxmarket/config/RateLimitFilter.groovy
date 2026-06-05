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
        // Broadened from `/api/steam/list` to `/api/steam` — also covers
        // `/api/steam/sync` which hits Steam's public inventory HTTP API
        // once per call. Uncapped, an attacker could fan out sync hammer
        // traffic that burns both our Tomcat threads during the round-trip
        // AND risks Steam rate-limiting our outbound IP.
        '/api/steam',
        '/api/auth/steam',
        // Batch 1040 — collapsed the three-narrow wallet prefixes
        // (/deposit, /withdraw, /transactions) into a single /api/wallet
        // prefix. Covers every wallet write AND the balance / spend /
        // transactions reads + CSV. Also pulls /wallet/confirm-deposit
        // and /wallet/withdraw/{id}/cancel under the cap, which were
        // previously uncapped write endpoints. 20/10s is fine for the
        // balance re-read cadence (wallet modal open + tab focus).
        '/api/wallet',
        // Support surface — broadened in batch 1039 from the prior
        // `/api/support/tickets` to cover `/api/support/report-user/{id}`
        // which was creating SupportTicket rows uncapped (a compromised
        // session could report-user N times in a second and flood the
        // CSR queue).
        '/api/support',
        // Profile writes — email change / verification / 2FA enrol / delete
        // request / trade URL. Without this cap an attacker could spam
        // /api/profile/email/resend to flood a target mailbox (the resend
        // triggers an outbound SMTP send on every call).
        '/api/profile',
        // Review creation + seller reply. Prevents review-bombing and
        // reply-flooding. Ownership + trade-verification checks are in
        // ReviewService, but we want the cap upstream of the DB round-trip.
        '/api/reviews',
        // Cart checkout fires a bulk purchase loop — each loop iteration
        // hits PurchaseService, WalletRepository, TradeService.open. An
        // uncapped hammer on /api/cart/checkout would drag tomcat threads
        // down even though CSRF protects it.
        '/api/cart',
        // Trade state transitions — accept / mark-sent / confirm / cancel /
        // dispute. Each emits notifications + audit logs + wallet writes,
        // so the hot path matters under concurrent abuse.
        '/api/trades',
        // Loadout create / favorite / delete. Not a money surface but the
        // favorite toggle is cheap-to-call and we already have a V10 unique
        // constraint preventing abuse — this is the belt-and-braces cap.
        '/api/loadouts',
        // API key management — create / rotate / revoke. No reason for a
        // real user to burn through 20 keys in 10 seconds.
        '/api/api-keys',
        // Watchlist add/remove + price alerts (batch 562). The POST
        // creates a row per item id; without a cap a hostile client
        // could hammer the endpoint with unique item ids until
        // MAX_PER_USER (500) fills, then churn deletes to keep
        // writing. The DB cap catches it eventually but costs one
        // INSERT per hit until then. Prefix covers both
        // /api/watchlist and /api/watchlist/alerts.
        '/api/watchlist',
        // Saved searches (batch 562). Create / update / delete. A
        // power user might save ~5 searches; 20/10s covers normal
        // editing while blocking a script walking the feature.
        '/api/saved-searches',
        // Seller follow / unfollow (batch 562). POST toggles a row;
        // spam-following would flood NEW_LISTING_FROM_SELLER push
        // targets if uncapped. Already bounded by unique constraints,
        // but cap upstream of the DB round-trip.
        '/api/follows',
        // Client-error report endpoint (batch 678). A broken frontend
        // loop or a malicious page could otherwise burn our log
        // storage by POSTing the same error 1000x/second. 20/10s per
        // caller is plenty for a real crash burst (component re-renders
        // before the ErrorBoundary catches).
        '/api/client-errors',
        // Notification writes (batch 960). POST /read-all, /read-batch,
        // /clear-read, /delete-batch all mutate every unread row in
        // the user's notifications table. An authenticated attacker
        // hammering these would thrash the DB with full-row UPDATE /
        // DELETE scans. 20/10s is plenty for a real user bulk-clearing
        // a bell dropdown; anything above is scripted.
        '/api/notifications',
        // Unsubscribe endpoint (batch 1019). Token-gated but anonymously
        // reachable — a scanner sending thousands of wrong-token POSTs
        // would force a DB lookup per hit. Batch 1017 already swapped
        // the full-table scan for an indexed `findByEmailIgnoreCase`,
        // but pounding the endpoint with 1M/s still burns Tomcat threads
        // + email-verify work. 20/10s per-IP is plenty for a legitimate
        // one-click unsubscribe from an inbox; anything above is abuse.
        '/api/unsubscribe',
        // Admin endpoints (batch 1036). Every admin mutation +
        // audit/fraud/trade CSV export goes through here. Staff
        // sessions are thin on the ground + act deliberately, so the
        // 20/10s budget is massive headroom while still bounding a
        // compromised admin session (an attacker who breaches an admin
        // account shouldn't be able to exfiltrate the full CSV catalogue
        // at 1000 req/s). Covers `/api/admin/users`, `/api/admin/*.csv`,
        // `/api/admin/audit`, `/api/admin/trades`, etc. Supersedes
        // the existing `/api/admin/users` GUARDED_READS entry but
        // that list is left intact — no harm in double-coverage.
        '/api/admin',
        // CSR endpoints (batch 1038). Same shape as /api/admin: few
        // sessions, deliberate actions, but the user-lookup endpoint
        // is a PII lookup and /tickets/{id}/reply + /listings/{id}/flag
        // are mutations. A compromised CSR session shouldn't be able to
        // scrape the user table at 1000 lookups/s. Standard 20/10s budget.
        '/api/csr'
    ]

    // Read surfaces that take free-text and can be used to enumerate or DoS.
    private static final List<String> GUARDED_READS = [
        '/api/listings',      // ?search= goes through here
        '/api/database',      // ?q= goes through here
        '/api/admin/users',   // ?search= (admin only, but still guarded)
        '/api/items',         // ?q= does a LIKE '%q%' scan
        '/api/loadouts',      // ?search= on /discover does a LIKE scan
        '/api/sellers'        // ?q= on /search does a LIKE scan (batch 666)
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
        // SSE auction stream (GET /api/bids/stream/{id}) opens a long-lived
        // SseEmitter — a held async connection. It was in no guarded list, so an
        // anonymous script could open streams unthrottled and exhaust the server's
        // async connections (DoS). Cap the open-rate per IP like the other enums.
        '/api/bids/stream/',
        '/api/reviews/user/',
        // Steam inventory fetch makes an outbound HTTP call per request.
        // Without a cap, a logged-in attacker can proxy-DoS Steam through
        // us and exhaust Tomcat threads (10s timeout each).
        '/api/steam/',
        // Steam OpenID return (batch 1067 — rate-limit audit). The /return
        // endpoint takes the user's OpenID assertion and POSTs it back to
        // Steam's `check_authentication` endpoint for verification — one
        // outbound HTTP round-trip per call, blocking a Tomcat thread for
        // the duration. Uncapped, an attacker can use us as a Steam-API
        // DoS amplifier (every replayed assertion burns one of our threads
        // AND one of Steam's verify slots, risking IP-level throttling
        // upstream that breaks real sign-ins). The 40/10s budget per IP
        // is generous for the real flow (one /login → one /return per
        // sign-in, ~seconds apart) but a cliff for a replay attack.
        '/api/auth/steam/return',
        // Steam /login is cheap on its own (just builds a redirect URL +
        // stashes a session attribute), but it's the kickoff for the
        // /return outbound call and capping it stops a script from using
        // us to spam Steam's OpenID front door at line rate. Same budget.
        '/api/auth/steam/login',
        // Admin + CSR GET surfaces (batch 1068 — admin-exfil audit). The
        // `/api/admin` + `/api/csr` entries in GUARDED_PREFIXES above only
        // catch *write* methods because the GET branch never consults
        // GUARDED_PREFIXES. That meant a hijacked admin / CSR session
        // could pull the full audit log + every user / withdrawal /
        // dispute / fraud / trade CSV at line rate — exactly the
        // scenario the `/api/admin` comment claimed to prevent. AdminController
        // alone exposes 9 unfiltered `.csv` GETs (`/audit.csv`,
        // `/withdrawals.csv`, `/users.csv`, `/tickets.csv`, `/fraud.csv`,
        // `/disputes.csv`, `/trades.csv`, `/users/{id}/listings.csv`,
        // `/users/{id}/transactions.csv`), each capped to 5000 rows but
        // un-paged — uncapped, a script can sweep a year of audit
        // history in seconds. CsrController exposes `/users/lookup` (PII).
        // 40/10s (MAX_ENUM) is generous for human-clicked CSV downloads
        // and ad-hoc lookups but a hard cliff for an exfil sweep. Combined
        // with the per-user-id bucket key, this bounds a single compromised
        // admin session to ~14k rows per minute instead of unbounded.
        '/api/admin/',
        '/api/csr/',
        // Heavy per-user GET exports (batch 1073 — rate-limit coverage audit).
        // Same GET-skips-GUARDED_PREFIXES gap that batch 1068 closed for the
        // admin CSVs: these are GETs, so the `/api/profile`, `/api/wallet`,
        // `/api/watchlist`, `/api/buy-orders` entries in GUARDED_PREFIXES never
        // see them. The GDPR JSON export is the worst — ProfileService.exportData
        // fans out ~15 repository queries across nearly EVERY table in one
        // read-only transaction (several unbounded: trades/offers/buy-orders/
        // bids/reviews-from), so an authenticated user could hammer it at line
        // rate to exhaust DB connections + Tomcat threads. The per-user CSVs are
        // lower-amplification (single-table, ≤5000 rows) but the same class.
        // 40/10s (MAX_ENUM) is generous for a human clicking "export my data"
        // (a once-in-a-while action) but a hard cliff for an abuse loop.
        '/api/profile/export',          // GDPR JSON — heaviest, multi-table fan-out
        '/api/profile/bids.csv',
        '/api/profile/offers.csv',
        '/api/profile/trades.csv',
        '/api/wallet/transactions.csv',
        '/api/listings/my-stall/',      // my-stall sold/analytics/active .csv exports
        '/api/watchlist/export.csv',
        '/api/buy-orders/export.csv'
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
        // Treat HEAD as GET (batch 862). HEAD is safe + idempotent per
        // RFC 7231 §4.2.2; it returns the same headers as GET with no
        // body. Routing HEAD through the mutation budget (MAX_REQ=20)
        // was wrong — it both surfaced a misleading X-RateLimit-Limit
        // (20 instead of the enum-path 40) and mis-categorised safe
        // probes (load balancers, health checks, link previews) as
        // writes.
        def isSafe = 'GET'.equalsIgnoreCase(method) || 'HEAD'.equalsIgnoreCase(method)
        def isGet = isSafe

        // Health + readiness probes are never rate-limited (batch 680).
        // Docker HEALTHCHECK / k8s liveness + readiness / LB health
        // checks hit these endpoints every few seconds from the same
        // internal IP — rate limiting would eventually flip the pod
        // into unhealthy even though nothing is wrong.
        if (path == '/api/health' || path == '/api/health/' ||
            path == '/api/ready'  || path == '/api/ready/') {
            chain.doFilter(req, resp)
            return
        }

        // Batch 971 — OPTIONS preflight requests are never rate-limited.
        // Browsers fire one OPTIONS before every cross-origin POST/PUT/
        // DELETE; the preflight is a non-mutating CORS probe and the
        // actual request still has to pass rate limiting downstream. If
        // we charge a write-budget token for the preflight, a user on a
        // third-party page (e.g. the SkinBox Valuer browser extension
        // running on steamcommunity.com) doing 20 rapid clicks burns
        // through the mutation budget on empty OPTIONS calls — the
        // actual POST traffic is still bounded by the real 20/10s cap
        // because each mutation fires its own budget check right after
        // the preflight.
        if ('OPTIONS'.equalsIgnoreCase(method)) {
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

        // Bucket key (batch 547). Signed-in users get their own
        // bucket keyed on userId so a power user on a shared NAT /
        // VPN can't accidentally rate-limit other legit users on the
        // same IP. Anonymous traffic still keys on the client IP —
        // same old anti-enumeration guard. Session lookup is cheap
        // (already resolved by Spring's session filter upstream).
        def key
        try {
            def sess = req.getSession(false)
            def uid = sess?.getAttribute('steamUserId') as Long
            key = (uid != null ? "u:${uid}" : "ip:${clientIp(req)}") + '|' + guarded
        } catch (Exception ignored) {
            key = 'ip:' + clientIp(req) + '|' + guarded
        }
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
        //
        // Two-phase eviction guards against a check-then-act race:
        //   1. The `min{}` scan + single-arg `remove(key)` evicted whatever
        //      sat at `oldest.key` at the moment of the remove — including
        //      a bucket whose `windowStart` had just been rolled forward by
        //      the active user (line 292 above). The very next request
        //      from that user re-created a fresh Bucket via
        //      `computeIfAbsent`, resetting their count to 0 and bypassing
        //      the rate limit they were about to trip.
        //   2. Even single-threaded, evicting the "oldest" bucket every
        //      MAX_KEYS+1th request silently wiped active counters for
        //      anyone whose window happened to start earlier than the
        //      churn — e.g. a steady poller mixed with bursty unique-IP
        //      traffic.
        // The fix: only evict buckets that are GENUINELY stale
        // (windowStart older than one window), and use the conditional
        // two-arg `remove(key, value)` so a bucket that's been re-issued
        // since the scan stays put.
        if (buckets.size() > MAX_KEYS) {
            long cutoff = now - WINDOW_MS
            buckets.entrySet().removeIf { e ->
                Bucket b = e.value
                b != null && b.windowStart < cutoff
            }
        }

        if (current > budget) {
            resp.status = 429
            resp.contentType = 'application/json'
            resp.setHeader('Retry-After', String.valueOf(Math.max(1, ((WINDOW_MS - (now - bucket.windowStart)) / 1000L) as long)))
            resp.setHeader('X-RateLimit-Limit', String.valueOf(budget))
            resp.setHeader('X-RateLimit-Remaining', '0')
            resp.setHeader('X-RateLimit-Reset', String.valueOf(Math.max(1, ((WINDOW_MS - (now - bucket.windowStart)) / 1000L) as long)))
            resp.writer.write('{"code":"RATE_LIMITED","message":"Too many requests. Please slow down."}')
            // Log only the FIRST breach in this window (count just crossed
            // budget). Logging every over-budget request flooded the WARN
            // stream during exactly the sustained-abuse scenario where the
            // log is most needed, and re-wrote the client IP (PII) on every
            // line. One line per key per window keeps the IP available for
            // ops to block the abuser without the flood.
            if (current == budget + 1) {
                log.warn("Rate limit hit: key=${key}, budget=${budget}")
            }
            return
        }
        // Surface the current rate-limit headroom on every guarded
        // response (batch 545). Mirrors Stripe / GitHub / Discord's
        // X-RateLimit-* convention. API consumers can self-throttle
        // when Remaining approaches zero instead of running into the
        // 429 wall blind.
        resp.setHeader('X-RateLimit-Limit', String.valueOf(budget))
        resp.setHeader('X-RateLimit-Remaining', String.valueOf(Math.max(0L, budget - current)))
        resp.setHeader('X-RateLimit-Reset', String.valueOf(Math.max(1, ((WINDOW_MS - (now - bucket.windowStart)) / 1000L) as long)))
        chain.doFilter(req, resp)
    }

    private static String clientIp(HttpServletRequest req) {
        // Prefer Cloudflare's CF-Connecting-IP — it's a single IP that
        // Cloudflare sets from the TCP peer, not spoofable by the client.
        // Fall back to X-Forwarded-For (first entry) for non-Cloudflare
        // deployments, then to the direct remoteAddr.
        def cf = req.getHeader('CF-Connecting-IP')
        if (cf && !cf.trim().isEmpty()) return cf.trim()
        def xff = req.getHeader('X-Forwarded-For')
        if (xff) {
            // A crafted `X-Forwarded-For: ,` (or `,,`) is non-blank, yet
            // Java's split drops trailing empty tokens → a zero-length
            // array, so the old `split(',')[0]` threw
            // ArrayIndexOutOfBoundsException. Unhandled here in the filter
            // chain it surfaces as a 500 on EVERY request carrying such a
            // header. Take the first non-empty hop; fall through to
            // remoteAddr when the header has no real address.
            for (String tok : xff.split(',')) {
                def t = tok?.trim()
                if (t) return t
            }
        }
        req.remoteAddr
    }
}
