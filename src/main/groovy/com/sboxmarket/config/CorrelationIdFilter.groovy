package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Runs first on every request. Assigns a short correlation id, binds it to
 * SLF4J MDC so every log line for this request carries it, writes it back
 * as {@code X-Correlation-Id}, and applies baseline security headers.
 *
 * Named {@code CorrelationIdFilter} (not RequestContextFilter) because Spring
 * Boot auto-registers its own bean with the latter name — picking the same
 * name causes a BeanDefinitionOverrideException at startup.
 */
@Component
@Order(0)
class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Correlation-Id"
    private static final String MDC_KEY = "cid"

    // Content-Security-Policy lists every external origin the SPA talks to.
    // Keeping it here instead of in a reverse proxy means a single deploy
    // controls it. Keep the list tight — if we add a new CDN we edit this one
    // string. `'unsafe-inline'` for styles is required by the inline style
    // props React uses; no unsafe scripts.
    private static final String CSP_HEADER = String.join('; ',
        "default-src 'self'",
        // Cloudflare auto-injects a RUM beacon (https://static.cloudflareinsights.com/beacon.min.js)
        // into every HTML response when the zone has "Web Analytics" enabled.
        // Without allowlisting that origin here the browser blocks the beacon
        // and the Network tab shows a red-X entry on every page load, which a
        // tester flagged as broken. It also beacons back to cloudflareinsights.com
        // so the host needs to be in connect-src too.
        "script-src 'self' https://unpkg.com https://static.cloudflareinsights.com",
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
        "font-src 'self' https://fonts.gstatic.com",
        // img-src includes every Steam CDN variant we have seen serving
        // avatar + item art, plus api.qrserver.com for the 2FA enrollment
        // QR code. Without api.qrserver.com the browser's CSP blocks the
        // QR image and the enrollment flow shows a broken image icon.
        "img-src 'self' data: https://community.cloudflare.steamstatic.com https://steamcommunity-a.akamaihd.net https://avatars.steamstatic.com https://avatars.akamai.steamstatic.com https://avatars.fastly.steamstatic.com https://api.qrserver.com",
        "connect-src 'self' https://api.stripe.com https://cloudflareinsights.com https://static.cloudflareinsights.com",
        "frame-src https://js.stripe.com https://hooks.stripe.com https://checkout.stripe.com",
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self' https://checkout.stripe.com https://steamcommunity.com"
    )

    @Value('${security.hsts:false}') boolean enableHsts

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        String cid = req.getHeader(HEADER)
        if (!cid || cid.length() > 64) {
            cid = UUID.randomUUID().toString().substring(0, 8)
        }
        MDC.put(MDC_KEY, cid)
        resp.setHeader(HEADER, cid)

        // Baseline security headers — CSP is shipped in-app because the SPA
        // talks to a known set of CDNs. HSTS is opt-in via `security.hsts=true`
        // so local HTTP dev isn't locked out; turn it on in production.
        resp.setHeader("X-Content-Type-Options", "nosniff")
        resp.setHeader("X-Frame-Options", "DENY")
        resp.setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
        resp.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
        resp.setHeader("Cross-Origin-Opener-Policy", "same-origin")
        resp.setHeader("Cross-Origin-Resource-Policy", "same-origin")
        resp.setHeader("Content-Security-Policy", CSP_HEADER)
        // HSTS: emit whenever the request actually arrived over TLS, not
        // gated on the env var. The container itself listens on plain
        // HTTP behind Cloudflare's TLS terminator (cf sets X-Forwarded-Proto:
        // https on the tunnel hop). Without this fix, public HTTPS
        // visitors via skinbox.market never received HSTS because
        // run-local.sh sets SECURITY_HSTS=false (correct for local-dev
        // HTTP). Spec note: HSTS over HTTP is a no-op per RFC 6797 §7.2,
        // so emitting it on a true-HTTP request is technically wasted
        // bytes but harmless — checking the proxy header avoids that.
        boolean isHttpsRequest = req.isSecure() ||
            'https'.equalsIgnoreCase(req.getHeader('X-Forwarded-Proto')) ||
            'https'.equalsIgnoreCase(req.getHeader('X-Forwarded-Scheme'))
        if (enableHsts || isHttpsRequest) {
            resp.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload")
        }

        // Sensitive endpoints must never be cached by intermediaries or the
        // browser — wallet balance, auth state, profile, trades, transactions,
        // offer threads, bid history, api keys, buy orders, notifications.
        // The rule of thumb: if the response depends on who's asking
        // (session-scoped) or could leak data between users through a
        // shared cache, the path belongs on this list.
        //
        // Batch 759 — `/api/buy-orders` listed here is overly broad: the
        // public read-aggregates (`/top`, `/count/item/*`, `/for-item/*`,
        // `/count/bulk`, `/projected-position`) return viewer-agnostic
        // demand signals that a shared cache can serve safely. The
        // private surfaces (`GET /api/buy-orders` listing the caller's
        // orders, POST/DELETE for mutations) ARE per-user. So we scope
        // the no-cache to the caller's own listing + the mutation paths;
        // the read-aggregates fall through and honor their controller-
        // set `public, max-age=60`.
        String path = req.requestURI
        String method = req.method
        boolean isBuyOrderPublicRead = path != null && (
                path == '/api/buy-orders/top' || path.startsWith('/api/buy-orders/top?') ||
                path.startsWith('/api/buy-orders/count/') ||
                path.startsWith('/api/buy-orders/for-item/') ||
                path.startsWith('/api/buy-orders/projected-position'))
        boolean isBuyOrderPrivate = path != null && path.startsWith('/api/buy-orders') && !isBuyOrderPublicRead
        // Watchlist mirrors the buy-order split: `/counts` and
        // `/alerts/count/*` are viewer-agnostic aggregates safe for a
        // shared cache (controller sets `public, max-age=60`). The
        // per-viewer listing + alert mutations are session-scoped.
        boolean isWatchlistPublicRead = path != null && (
                path == '/api/watchlist/counts' || path.startsWith('/api/watchlist/counts?') ||
                path.startsWith('/api/watchlist/alerts/count/'))
        boolean isWatchlistPrivate = path != null && path.startsWith('/api/watchlist') && !isWatchlistPublicRead

        // Loadout split: `/discover` is a public browse feed; every
        // other loadout surface is viewer-dependent (even `GET /{id}`
        // filters private loadouts by owner) or a mutation. Keep
        // Discover cacheable by a shared cache, force no-store on the
        // rest. Rationale: without this, a CDN could cache one
        // viewer's owner-only loadout view and leak it.
        boolean isLoadoutPublicRead = path != null && (
                path == '/api/loadouts/discover' || path.startsWith('/api/loadouts/discover?'))
        boolean isLoadoutPrivate = path != null && path.startsWith('/api/loadouts') && !isLoadoutPublicRead

        // Review split: `/user/{id}` and `/user/{id}/summary` are
        // public seller-scoped aggregates; everything else (mine /
        // pending / eligible / mutations / helpful / reply) is
        // viewer-dependent.
        boolean isReviewPublicRead = path != null && (
                path.matches('/api/reviews/user/\\d+') ||
                path.matches('/api/reviews/user/\\d+\\?.*') ||
                path.matches('/api/reviews/user/\\d+/summary') ||
                path.matches('/api/reviews/user/\\d+/summary\\?.*'))
        boolean isReviewPrivate = path != null && path.startsWith('/api/reviews') && !isReviewPublicRead
        // /api/listings/my-stall* are the seller's per-user dashboards
        // (active listings, sold ledger, earnings rollup, per-listing
        // analytics, plus the .csv exports for each). Personal data —
        // belongs on the no-store list. Pre-fix the controller had to
        // emit Cache-Control: no-store itself on the .csv variants and
        // the JSON variants slipped through with whatever Spring's
        // default was (typically no Cache-Control = browser disk cache
        // could persist a stale response across user sessions on a
        // shared machine). Filter-side coverage means any future
        // /api/listings/my-stall/<new-feature> automatically inherits
        // the right header without per-controller plumbing.
        boolean isMyStall = path != null && path.startsWith('/api/listings/my-stall')
        // Same shape for the seller's owned-inventory feed (powers the
        // SellItemsModal grid) and for the seller's own verification-
        // progress snapshot (KYC milestone counters). Both viewer-scoped.
        boolean isOwnedInventory = path != null && path == '/api/listings/inventory'
        boolean isSellerSelf     = path != null && path.startsWith('/api/sellers/me')
        if (path != null && (
                path.startsWith('/api/wallet') ||
                path.startsWith('/api/auth') ||
                path.startsWith('/api/me') ||
                path.startsWith('/api/profile') ||
                path.startsWith('/api/trades') ||
                path.startsWith('/api/notifications') ||
                path.startsWith('/api/admin') ||
                path.startsWith('/api/csr') ||
                path.startsWith('/api/support') ||
                path.startsWith('/api/offers') ||
                path.startsWith('/api/bids') ||
                path.startsWith('/api/api-keys') ||
                path.startsWith('/api/saved-searches') ||
                path.startsWith('/api/follows') ||
                path.startsWith('/api/steam') ||
                isBuyOrderPrivate ||
                isWatchlistPrivate ||
                isLoadoutPrivate ||
                isReviewPrivate ||
                isMyStall ||
                isOwnedInventory ||
                isSellerSelf ||
                path.startsWith('/api/cart'))) {
            resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private")
            resp.setHeader("Pragma", "no-cache")
            resp.setHeader("Expires", "0")
        }

        // Static assets — split into two tiers:
        //   - JS/CSS bundles: `no-cache` so the browser MUST revalidate via
        //     the existing Last-Modified header on every request. The
        //     usual response is a cheap 304, but a fresh deploy lands
        //     instantly on the next pageview instead of being shadowed by
        //     a stale cached copy for hours. Bundle filenames are NOT
        //     content-hashed (just /js/app.js etc) so a long TTL would
        //     trap users on whichever build was current the last time
        //     their browser cached it.
        //   - Fonts / images / icons: 4-hour edge + browser cache. These
        //     change rarely, are addressed by a stable URL, and the
        //     bandwidth/round-trip savings are real on mobile.
        if (path != null && path.matches('.*\\.(js|css)$')) {
            resp.setHeader("Cache-Control", "no-cache, must-revalidate")
        } else if (path != null && path.matches('.*\\.(woff2?|svg|png|ico|jpg|webp)$')) {
            resp.setHeader("Cache-Control", "public, max-age=14400")
        } else if (path != null && method == 'GET'
                   && !path.startsWith('/api/')
                   && !path.contains('.')
                   && !path.startsWith('/item/')
                   && !path.startsWith('/stall/')
                   && !path.startsWith('/loadout/')
                   && path != '/faq') {
            // SPA shell route (e.g. `/`, `/market`, `/search`, `/watchlist`).
            // Forwards to /index.html which references non-content-hashed
            // /js/*.js bundles, so the shell must revalidate on every load
            // to pick up new bundle contents the moment a deploy lands.
            //
            // OG-rewriting routes (/item/{id}, /stall/{id}, /loadout/{id},
            // /faq) are skipped here because OpenGraphController sets its
            // own `public, max-age=300` via ResponseEntity.header(), and
            // ResponseEntity headers APPEND rather than replace — pre-
            // setting Cache-Control here would emit two conflicting
            // values like the /api/buy-orders bug fixed earlier.
            resp.setHeader("Cache-Control", "no-cache, must-revalidate")
        }

        try {
            chain.doFilter(req, resp)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }
}
