package com.sboxmarket.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
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
        // These endpoints return only catalogue/listing data that is
        // already public. They are safe to expose to any origin (Steam
        // community pages, the browser extension, curl from any
        // workstation) because they require no authentication and carry
        // no session state.
        //
        // `credentials = false` is critical here: with credentials off
        // we are allowed to return `Access-Control-Allow-Origin: *`, so
        // the extension running on https://steamcommunity.com can call
        // us in prod without the operator having to whitelist Steam in
        // CORS_ALLOWED_ORIGINS.
        //
        // IMPORTANT — do NOT use a blanket `/api/listings/**` here. The
        // ListingController exposes mutating POST/PUT/DELETE endpoints
        // under that prefix (`/api/listings/sell`, `/api/listings/{id}/buy`,
        // `/api/listings/{id}` DELETE, `/api/listings/my-stall/*`, etc.)
        // Spring's CorsRegistry resolves to the first matching mapping per
        // path, so a `/api/listings/**` entry here would shadow the
        // authenticated `/api/**` mapping below for those POST/PUT/DELETE
        // calls: the preflight would only advertise `GET, OPTIONS` in
        // `Access-Control-Allow-Methods` and the browser would block every
        // cross-origin buy / sell / delete. Enumerate the GET-only
        // sub-paths the extension actually needs instead.
        // Ant-pattern matchers (no regex placeholders — CorsRegistry uses
        // AntPathMatcher, not PathPatternParser). Each entry is a single
        // GET-only endpoint that the extension / curl callers actually need.
        [
            '/api/listings',                       // catalogue list (GET only on collection)
            '/api/listings/item/**',               // listings for a given item (floor price)
            '/api/listings/seller/*/other',        // "more from this seller"
            '/api/listings/stall/**',              // seller stall views
            '/api/listings/stats',                 // catalog-wide stats
            '/api/listings/just-listed',
            '/api/listings/top-deals',
            '/api/listings/top-sellers',
            '/api/listings/ending-soon',
            '/api/listings/recent-sales',
            '/api/listings/most-watched',
            '/api/listings/most-viewed',
            '/api/listings/hottest',
            '/api/listings/sales-velocity',
            '/api/listings/report-reasons',
            // NOTE: single-listing detail `GET /api/listings/{id}` is intentionally
            // NOT in the public mapping. A bare `/api/listings/*` Ant pattern would
            // also match the mutating `/api/listings/sell`, `/api/listings/away`,
            // `/api/listings/check-active`, `/api/listings/inventory` sub-paths and
            // re-introduce the very shadowing this refactor fixes. Same-origin
            // callers and authenticated cross-origin callers (with cookies) hit
            // the detail endpoint via the `/api/**` mapping below; the public
            // extension only needs the item-scoped floor lookup above.
            '/api/items',
            '/api/items/**',
            '/api/database/**'
        ].each { path ->
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
        // Long-cache hashed/immutable asset directories (/css, /js, /img,
        // /fonts, favicons, manifests) — 7d browser cache + last-modified
        // revalidation. These are bundle-style assets the SPA build either
        // fingerprints in the filename or rebuilds on every deploy, so a
        // stale browser cache is self-healing on next file change.
        // Without this, every page load re-hits Tomcat for every CSS/JS
        // request with an `If-Modified-Since` round-trip, burning a
        // worker thread per asset per visit instead of returning a 304
        // straight from the browser cache.
        // CRITICAL: each '/dir/**' handler MUST point at 'classpath:/static/dir/'.
        // Spring strips the handler's literal prefix ('/css/') and resolves only
        // the '**' remainder ('design.css') against the location — so a SHARED
        // 'classpath:/static/' location resolved '/css/design.css' to
        // static/design.css (wrong dir) and 404'd EVERY css/js/img (the f6f7548
        // regression; the SPA shell still 200'd via '/**', so `curl /` missed it).
        //
        // We deliberately DO NOT setCachePeriod here. CorrelationIdFilter is the
        // single source of truth for static-asset Cache-Control — `no-cache,
        // must-revalidate` for the non-content-hashed JS/CSS bundles (so a deploy
        // is picked up on the next request via a cheap 304) and `public,
        // max-age=14400` for images (PublicEndpointsHttpSpec pins both). The
        // filter stamps its header BEFORE the handler runs, so a setCachePeriod()
        // here would overwrite it with a bare `max-age` and ship stale bundles
        // after every deploy — exactly what f6f7548 would have done once its
        // 404s were fixed.
        registry.addResourceHandler('/css/**').addResourceLocations('classpath:/static/css/')
        registry.addResourceHandler('/js/**').addResourceLocations('classpath:/static/js/')
        registry.addResourceHandler('/img/**').addResourceLocations('classpath:/static/img/')
        registry.addResourceHandler('/fonts/**').addResourceLocations('classpath:/static/fonts/')
        // Everything else — the static-root files (favicon.ico, manifest.json,
        // robots.txt, opensearch.xml) and legal/*.html. The SPA routes (/, /market,
        // /item/:id, …) are served by OpenGraphController (SEO shell), not here.
        // Cache-Control is owned by the filter, so no setCachePeriod here either.
        registry.addResourceHandler('/**')
                .addResourceLocations('classpath:/static/')
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

        // SPA history-API routing — a REAL client route serves index.html so
        // the client-side router can pick it up.
        //
        // WHAT THIS USED TO BE, AND WHY IT WAS WRONG.
        //
        // These six registrations used to be generic depth-1/2/3 globs:
        //
        //     registry.addViewController("/{path:[^.]*}")        -> index.html
        //     registry.addViewController("/{a:[^.]*}/{b:[^.]*}") -> index.html
        //     ... and a trailing-slash variant of each.
        //
        // The `[^.]*` guard kept real static assets out of the fallback, and
        // that is all it did. EVERY dot-free path of depth 1-3 matched, so
        // every URL on this host answered `200 OK`. MEASURED: /nope-404,
        // /item/abc, /stall/abc, /totally/made/up, /db/nope and /zzz/ all
        // returned 200 while rendering the SPA's branded "404 · nothing here"
        // panel. The page was right and the status line lied, so a crawler,
        // an uptime monitor, a link checker and an e2e status assertion each
        // had no way to tell a dead link from a live page — the same defect
        // family as an empty list returned for a failed fetch: a missing
        // thing reported as a present, healthy one.
        //
        // The fix is to say which routes EXIST (SPA_ROUTES below, mirrored
        // from static/js/router.js) and let spaUnknownPathFallback() answer
        // everything else with a real 404 that still renders the SPA shell.
        // A glob cannot distinguish the two cases; an enumeration can.
        //
        // Sensitive paths (h2-console, swagger-ui, api-docs, actuator) still
        // get their own real 404s via BlockedPathsController — the
        // registry.addRedirectViewController API only accepts 3xx codes.
        //
        // Trailing-slash variants are registered alongside each pattern:
        // Spring Boot 3's PathPatternParser no longer auto-matches a trailing
        // slash, and router.js accepts both shapes (`/^\/db\/?$/`), so
        // `/db/`, `/help/`, `/profile/` must resolve exactly as `/db` does.
        SPA_ROUTES.each { String pattern ->
            registry.addViewController(pattern).setViewName('forward:/index.html')
            if (pattern != '/') {
                registry.addViewController(pattern + '/').setViewName('forward:/index.html')
            }
        }

        // …and the old globs stay, pointed at the 404 view instead of at
        // index.html. Everything the enumeration above did NOT claim — and
        // that no alias, no clean-URL forward and no @GetMapping claimed
        // either — is by definition a path this app does not have.
        //
        // This is one SimpleUrlHandlerMapping and it picks the MOST SPECIFIC
        // pattern, not the first registered: `/db` beats `/{path:[^.]*}`, and
        // `/changelog` beats it too, exactly as the clean-URL aliases above
        // have always relied on. So a real route is a 200 and only the
        // leftovers reach SpaNotFoundController.
        //
        // Depth 1-3 plus trailing-slash variants, unchanged from the globs
        // that were here before, so nothing that used to resolve stops
        // resolving. Deeper unknown paths keep falling through to the
        // resource handler's own bare 404, as they always did.
        ['/{path:[^.]*}',
         '/{segment:[^.]*}/{path:[^.]*}',
         '/{segment:[^.]*}/{sub:[^.]*}/{path:[^.]*}',
         '/{path:[^.]*}/',
         '/{segment:[^.]*}/{path:[^.]*}/',
         '/{segment:[^.]*}/{sub:[^.]*}/{path:[^.]*}/'].each { String glob ->
            registry.addViewController(glob).setViewName('forward:' + SPA_NOT_FOUND_PATH)
        }
    }

    /**
     * The real client routes, mirrored one-for-one from the {@code ROUTES}
     * table in {@code static/js/router.js}. Anything not here is a path the
     * SPA itself resolves to its {@code notfound} route, so the server must
     * not claim 200 for it.
     *
     * The regex constraints are the router's own: {@code /item/:id} and
     * {@code /stall/:id} are `(\d+)` there, and router.js documents why
     * (`/stall/abc` used to mount the stall modal and fire three API calls
     * that all 400'd before the 404 panel rendered). A server-side glob that
     * accepted `/stall/abc` with a 200 was disagreeing with the client about
     * what exists.
     *
     * NOT listed here, deliberately: the reflexive aliases registered above
     * as redirect view controllers (`/database`, `/home`, `/terms`, `/me`,
     * `/buy`, ...). Those resolve with a 3xx to a real route and registering
     * them twice would be an ambiguous mapping.
     */
    private static final List<String> SPA_ROUTES = [
        '/',
        '/market', '/search', '/cart', '/db', '/sell', '/loadout',
        '/profile', '/wallet', '/watchlist', '/offers', '/buy-orders',
        '/notifications', '/support', '/help', '/faq', '/settings',
        '/affiliate', '/admin', '/csr',
        '/item/{id:[0-9]+}',
        '/stall/{id:[0-9]+}',
        '/loadout/{id:[0-9]+}',
        '/profile/{tab:personal|listings|transactions|buyorders|autobids|trades|offers|reviews|support|developers}',
        '/wallet/{tab:deposit|withdraw|history}',
        '/watchlist/{tab:all|drops}',
        '/offers/{tab:incoming|outgoing}',
        '/me/stall',
        '/me/stall/{tab:active|sold|analytics}'
    ].asImmutable()

    /** Internal forward target for "this path matches no route". Reachable
     *  directly too, and honestly answers 404 when it is. */
    static final String SPA_NOT_FOUND_PATH = '/spa-404'
}

/**
 * Serves the SPA shell with a real {@code 404 Not Found} for any path the
 * route table above did not claim.
 *
 * BOTH HALVES MATTER. The body is still index.html, so the client router
 * boots, resolves its {@code notfound} route and paints the same branded
 * "404 · nothing here" panel a mistyping human has always seen — nothing
 * regresses for a reader. The STATUS is now 404, so everything that reads
 * status rather than pixels (crawlers, uptime monitors, link checkers,
 * {@code expect(response.status()).toBe(404)}) is told the truth.
 *
 * WHY A FORWARD AND NOT A {@code RouterFunction}. The obvious shape — a
 * {@code RouterFunction} bean with a "no dot, not /api, GET" predicate —
 * was written first and MEASURED to be wrong: {@code RouterFunctionMapping}
 * was consulted ahead of the view-controller mapping in this application, so
 * that predicate was asked about {@code /}, {@code /db}, {@code /legal/terms}
 * and {@code /css/design.css} BEFORE the mappings that own them, and would
 * have 404'd every real route. Routing the leftovers here through the SAME
 * handler mapping that owns the real routes removes the ordering question
 * entirely: one mapping, most-specific-pattern-wins, which is the rule the
 * clean-URL aliases in WebConfig have always depended on.
 *
 * NOT changed here: {@code OpenGraphController.notFoundSpaShell()} still
 * answers 200 for a well-formed URL whose ENTITY is missing
 * ({@code /item/999999}). That is a separate, deliberate, documented decision
 * with its own rationale, and overturning it was not in scope. This class is
 * about paths that match no route at all.
 */
@Controller
class SpaNotFoundController {

    // Literals, not WebConfig.SPA_NOT_FOUND_PATH: annotation attributes need
    // compile-time constants. If that constant is ever changed, change these
    // with it — a mismatch shows up immediately as a forward loop or a 404
    // with an empty body on every unknown path.
    @RequestMapping(value = ['/spa-404', '/spa-404/'])
    ResponseEntity<String> notFound(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        // On a forward this is the URL the caller actually asked for; on a
        // direct hit it is null and the request URI is already /spa-404.
        String original = (request.getAttribute('jakarta.servlet.forward.request_uri') ?: request.requestURI) as String

        // An unknown /api/** path must not be answered with a page. Every
        // client of that prefix parses JSON, and handing it an HTML document
        // — which the old catch-all did, with a 200 on top — is the same lie
        // in a different content type.
        if (original != null && (original == '/api' || original.startsWith('/api/'))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header('Cache-Control', 'no-cache, must-revalidate')
                    .body('{"error":"not found"}')
        }

        // Never let a CDN or a browser pin a 404 for a path a later
        // release might turn into a real route. setHeader, not a
        // ResponseEntity header: CorrelationIdFilter already stamped the
        // shell header on the original path, and an entity header would
        // be appended as a second Cache-Control value.
        response.setHeader('Cache-Control', CorrelationIdFilter.HTML_CACHE_CONTROL)
        ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .header('X-Robots-Tag', 'noindex')
                .body(spaShellHtml())
    }

    /**
     * index.html as a String, re-read per request. It is a few KB and the OS
     * page cache owns it; holding it in a field would mean an edit to the
     * shell kept serving the copy taken at boot — the same "running old code"
     * trap the build/resources copy already sets in this repo.
     */
    private static String spaShellHtml() {
        try {
            return new ClassPathResource('static/index.html').inputStream.getText('UTF-8')
        } catch (Exception ignored) {
            // The shell is missing — say so plainly rather than 500ing. Still
            // a 404: the path the caller asked for still does not exist.
            return '<!doctype html><html lang="en"><head><meta charset="utf-8">' +
                   '<title>404 · nothing here</title></head><body>' +
                   '<h1>404 · nothing here</h1></body></html>'
        }
    }
}
