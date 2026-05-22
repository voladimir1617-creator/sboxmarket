package com.sboxmarket.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig implements WebMvcConfigurer {

    /**
     * Comma-separated allowed origins for CORS. Defaults to `*` for dev so
     * `localhost:*` just works. In production this MUST be set to the
     * public origin(s) — e.g. `https://skinbox.market`. Wildcard is refused
     * when `allowCredentials=true`, so we branch based on the first value.
     */
    @Value('${security.cors-allowed-origins:*}') String corsAllowedOrigins

    @Override
    void addCorsMappings(CorsRegistry registry) {
        def origins = (corsAllowedOrigins ?: '*').split(',').collect { it.trim() }.findAll { it }

        // ── Public read-only surfaces ────────────────────────────────
        // These three endpoints return only catalogue/listing data that
        // is already public. They are safe to expose to any origin
        // (Steam community pages, the browser extension, curl from any
        // workstation) because they require no authentication and carry
        // no session state.
        //
        // `credentials = false` is critical here: with credentials off
        // we are allowed to return `Access-Control-Allow-Origin: *`, so
        // the extension running on https://steamcommunity.com can call
        // us in prod without the operator having to whitelist Steam in
        // CORS_ALLOWED_ORIGINS.
        ['/api/listings', '/api/listings/**', '/api/items', '/api/items/**', '/api/database/**'].each { path ->
            registry.addMapping(path)
                    .allowedMethods('GET', 'OPTIONS')
                    .allowedHeaders('*')
                    .allowedOriginPatterns('*')
                    .allowCredentials(false)
                    .maxAge(3600)
        }

        // ── Authenticated surfaces ───────────────────────────────────
        // Everything else on /api/** only accepts the operator's configured
        // origin list and requires credentials (session cookie + CSRF).
        // PATCH is in the method list because SellerFollowController exposes
        // `PATCH /api/follows/{id}/mute` + `/api/follows/mute-all`; without
        // it a cross-origin (split SPA-origin / extension) caller's CORS
        // preflight omits PATCH and the browser blocks the mute request.
        def mapping = registry.addMapping('/api/**')
                .allowedMethods('GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS')
                .allowedHeaders('*')
                .maxAge(3600)
        // `allowedOrigins` with a literal `*` forbids credentials, while
        // `allowedOriginPatterns` permits both wildcards and credentials.
        // Use patterns for the dev wildcard, exact origins otherwise.
        if (origins == ['*']) {
            mapping.allowedOriginPatterns('*')
                   .allowCredentials(false)
        } else {
            mapping.allowedOrigins(origins as String[])
                   .allowCredentials(true)
        }
    }

    @Override
    void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
    }

    @Override
    void addViewControllers(ViewControllerRegistry registry) {
        // Batch 1064 — friendly redirects for URLs users type reflexively
        // that don't have an SPA route. `/login` most commonly — every
        // website with auth has a /login convention, and a user typing it
        // out of habit should land on the sign-in flow, not the 404 view.
        // 302 temporary redirect so the browser tab's URL bar ends up at
        // the real Steam OpenID redirector rather than /login.
        registry.addRedirectViewController('/login', '/api/auth/steam/login')
        registry.addRedirectViewController('/signin', '/api/auth/steam/login')
        registry.addRedirectViewController('/sign-in', '/api/auth/steam/login')
        // Batch 1065 — there's no separate registration flow (Steam OpenID
        // handles both first-time signup + returning login via the same
        // handshake), but users type /signup / /register reflexively. Send
        // them through the same door as /login. Also catch /home → / so
        // a "click home" muscle-memory URL doesn't hit the 404 view.
        registry.addRedirectViewController('/signup', '/api/auth/steam/login')
        registry.addRedirectViewController('/sign-up', '/api/auth/steam/login')
        registry.addRedirectViewController('/register', '/api/auth/steam/login')
        registry.addRedirectViewController('/home', '/')
        // Common reflexive URLs users type that aren't SPA route keys.
        // The canonical database route is /db — typing /database 404'd.
        // Loadouts index lives at /loadout (singular); /loadouts was 404.
        // /notification (singular) → /notifications plural.
        registry.addRedirectViewController('/database',     '/db')
        registry.addRedirectViewController('/loadouts',     '/loadout')
        registry.addRedirectViewController('/notification', '/notifications')
        registry.addRedirectViewController('/buyorders',    '/buy-orders')
        registry.addRedirectViewController('/orders',       '/buy-orders')
        registry.addRedirectViewController('/watch',        '/watchlist')
        // Logout via GET — the canonical logout is `POST /api/auth/logout`
        // (CSRF + body-less) called from the user menu's signOut() handler.
        // A typed /logout URL by an existing user should land on /, where
        // the cookie is preserved (UI shows signed-in nav). If they want
        // to actually sign out, they hit the menu item which POSTs.
        // Sending /logout to / is the friendliest interpretation.
        registry.addRedirectViewController('/logout',  '/')
        registry.addRedirectViewController('/signout', '/')
        registry.addRedirectViewController('/sign-out','/')
        // /buy is muscle-memory for "I want to shop" — point it at the
        // marketplace grid so the user lands somewhere useful instead of
        // the SPA 404 view.
        registry.addRedirectViewController('/buy',     '/market')
        registry.addRedirectViewController('/shop',    '/market')
        registry.addRedirectViewController('/browse',  '/market')
        // Profile aliases — `/me` and `/account` are common reflexive
        // URLs from other marketplaces (Steam Community, GitHub, Twitter
        // all use /me as a self-route shortcut). Send them to the
        // canonical /profile/personal sub-tab. /trades → /profile/trades
        // sub-tab so a typed URL hits the right tab.
        registry.addRedirectViewController('/me',          '/profile/personal')
        registry.addRedirectViewController('/account',     '/profile/personal')
        registry.addRedirectViewController('/preferences', '/settings')
        registry.addRedirectViewController('/trades',      '/profile/trades')
        // Bare /stall isn't a route (canonical forms are /stall/<id> for a
        // seller's public page and /me/stall for your own). Point both
        // reflexive URLs at the owner view; anons see a sign-in gate there.
        registry.addRedirectViewController('/stall',   '/me/stall')
        registry.addRedirectViewController('/mystall', '/me/stall')
        // Batch 1066 — more reflexive URLs. Users type `/terms`, `/privacy`,
        // `/cookies` expecting the legal docs; `/about` expecting a company
        // page (closest fit is the Help Center hero); `/contact` expecting
        // support. Without these redirects each URL lands on the SPA 404
        // view. Redirect to the real destinations so the browser URL bar
        // ends up correct (302 temporary).
        registry.addRedirectViewController('/terms',   '/legal/terms.html')
        registry.addRedirectViewController('/privacy', '/legal/privacy.html')
        registry.addRedirectViewController('/cookies', '/legal/cookies.html')
        registry.addRedirectViewController('/about',   '/help')
        registry.addRedirectViewController('/contact', '/support')
        // Same pattern for the rest of the legal docs - reflexive URLs that
        // a user might type without the /legal/ prefix or .html suffix.
        registry.addRedirectViewController('/refunds',                '/legal/refunds.html')
        registry.addRedirectViewController('/refund-policy',          '/legal/refunds.html')
        registry.addRedirectViewController('/trade-safety',           '/legal/trade-safety.html')
        registry.addRedirectViewController('/safety',                 '/legal/trade-safety.html')
        registry.addRedirectViewController('/disclaimer',             '/legal/disclaimer.html')
        registry.addRedirectViewController('/risk',                   '/legal/disclaimer.html')
        registry.addRedirectViewController('/acceptable-use',         '/legal/acceptable-use.html')
        registry.addRedirectViewController('/aup',                    '/legal/acceptable-use.html')
        registry.addRedirectViewController('/responsible-disclosure', '/legal/responsible-disclosure.html')
        registry.addRedirectViewController('/security',               '/legal/responsible-disclosure.html')
        registry.addRedirectViewController('/tos',                    '/legal/terms.html')
        registry.addRedirectViewController('/eula',                   '/legal/terms.html')
        // Footer "Fees & Pricing" link points to /faq?q=platform+fee.
        // A user who types /fees or /pricing in the address bar should
        // land on the same FAQ section instead of the SPA 404 view.
        registry.addRedirectViewController('/fees',                   '/faq?q=platform+fee')
        registry.addRedirectViewController('/pricing',                '/faq?q=platform+fee')
        registry.addRedirectViewController('/fees-and-pricing',       '/faq?q=platform+fee')

        // Batch 1063 — clean-URL aliases for the handful of .html static
        // pages that a human might type without the extension. These
        // documents exist in /static as .html but the SPA's generic
        // `{path:[^.]*}` forward would catch `/changelog` / `/status` /
        // `/legal/terms` first and render the 404 view because the client
        // router has no such route. Explicit forwards here resolve to the
        // real HTML file so the URL works with OR without the extension.
        // Registered BEFORE the generic SPA catchall so these win on match.
        registry.addViewController('/changelog').setViewName('forward:/changelog.html')
        registry.addViewController('/status').setViewName('forward:/status.html')
        registry.addViewController('/legal/terms').setViewName('forward:/legal/terms.html')
        registry.addViewController('/legal/privacy').setViewName('forward:/legal/privacy.html')
        registry.addViewController('/legal/refunds').setViewName('forward:/legal/refunds.html')
        registry.addViewController('/legal/trade-safety').setViewName('forward:/legal/trade-safety.html')
        registry.addViewController('/legal/disclaimer').setViewName('forward:/legal/disclaimer.html')
        registry.addViewController('/legal/acceptable-use').setViewName('forward:/legal/acceptable-use.html')
        registry.addViewController('/legal/cookies').setViewName('forward:/legal/cookies.html')
        registry.addViewController('/legal/responsible-disclosure').setViewName('forward:/legal/responsible-disclosure.html')

        // SPA history-API routing — any non-API URL without a file extension
        // should serve index.html so the client-side router can pick it up.
        // These patterns cover the full CSFloat-style URL surface:
        //   /search, /db, /item/{id}, /stall/{id}, /loadout, /loadout/{id},
        //   /profile, /wallet, /watchlist, /sell, /offers, /buy-orders,
        //   /notifications, /support, /admin, /csr, /help, /login, /logout
        //
        // The `[^.]*` guard keeps real static assets (`/css/styles.css`,
        // `/js/app.js`, favicon.ico) out of the fallback.
        //
        // Sensitive paths (h2-console, swagger-ui, api-docs) get real 404s via
        // a dedicated @RestController (BlockedPathsController) — the
        // registry.addRedirectViewController API only accepts 3xx codes.

        registry.addViewController("/{path:[^.]*}")
                .setViewName("forward:/index.html")
        registry.addViewController("/{segment:[^.]*}/{path:[^.]*}")
                .setViewName("forward:/index.html")
        registry.addViewController("/{segment:[^.]*}/{sub:[^.]*}/{path:[^.]*}")
                .setViewName("forward:/index.html")

        // Batch 969 — trailing-slash variants. Spring Boot 3's
        // PathPatternParser no longer auto-matches a trailing slash on
        // a registered pattern, so `/help/`, `/search/`, `/db/`,
        // `/loadout/`, `/profile/` etc. all 404'd even though the SPA
        // router (`router.js`) happily accepts both shapes. Re-register
        // each depth with an explicit trailing `/` so crawlers +
        // inbound-link copy/paste + old-school URL bars all resolve.
        // Keeps the `[^.]*` guard so real static assets don't fall
        // into the fallback.
        registry.addViewController("/{path:[^.]*}/")
                .setViewName("forward:/index.html")
        registry.addViewController("/{segment:[^.]*}/{path:[^.]*}/")
                .setViewName("forward:/index.html")
        registry.addViewController("/{segment:[^.]*}/{sub:[^.]*}/{path:[^.]*}/")
                .setViewName("forward:/index.html")
    }
}
