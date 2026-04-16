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
     * public origin(s) — e.g. `https://skinbox.example`. Wildcard is refused
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
        def mapping = registry.addMapping('/api/**')
                .allowedMethods('GET', 'POST', 'PUT', 'DELETE', 'OPTIONS')
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
    }
}
