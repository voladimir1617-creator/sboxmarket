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
@Order(1)
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
        "script-src 'self' https://unpkg.com",
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
        "font-src 'self' https://fonts.gstatic.com",
        // img-src includes every Steam CDN variant we have seen serving
        // avatar + item art, plus api.qrserver.com for the 2FA enrollment
        // QR code. Without api.qrserver.com the browser's CSP blocks the
        // QR image and the enrollment flow shows a broken image icon.
        "img-src 'self' data: https://community.cloudflare.steamstatic.com https://steamcommunity-a.akamaihd.net https://avatars.steamstatic.com https://avatars.akamai.steamstatic.com https://avatars.fastly.steamstatic.com https://api.qrserver.com",
        "connect-src 'self' https://api.stripe.com",
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
        if (enableHsts) {
            resp.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload")
        }

        // Sensitive endpoints must never be cached by intermediaries or the
        // browser — wallet balance, auth state, profile, trades, transactions,
        // offer threads, bid history, api keys, buy orders, notifications.
        // The rule of thumb: if the response depends on who's asking
        // (session-scoped) or could leak data between users through a
        // shared cache, the path belongs on this list.
        String path = req.requestURI
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
                path.startsWith('/api/buy-orders') ||
                path.startsWith('/api/cart'))) {
            resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private")
            resp.setHeader("Pragma", "no-cache")
            resp.setHeader("Expires", "0")
        }

        // Static assets — cache at the Cloudflare edge for 4 hours so
        // EU users don't round-trip to the origin on every page load.
        // JS/CSS/fonts/images are fingerprinted by content, so a 4-hour
        // TTL is safe — a code deploy just changes the file content and
        // Cloudflare fetches the new version on the next miss.
        if (path != null && path.matches('.*\\.(js|css|woff2?|svg|png|ico|jpg|webp)$')) {
            resp.setHeader("Cache-Control", "public, max-age=14400")
        }

        try {
            chain.doFilter(req, resp)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }
}
