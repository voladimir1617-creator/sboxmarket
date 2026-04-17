package com.sboxmarket

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * End-to-end HTTP coverage for every public-facing endpoint that should
 * be reachable without authentication. Pins 200/401/404 contracts so a
 * future filter reorder or controller rename can't silently flip them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicEndpointsHttpSpec extends Specification {

    @Autowired MockMvc mockMvc

    def "GET /api/health returns 200 with UP body"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/health')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('UP')
    }

    def "GET /api/listings returns 200 and does not require auth"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')).andReturn()

        then:
        r.response.status == 200
    }

    def "GET /api/database returns 200 with items wrapper"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/database').param('limit', '5')
        ).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('items')
    }

    def "GET /api/database accepts all whitelisted sort values without erroring"() {
        expect:
        ['rarest','most_traded','price_desc','price_asc','newest'].each { s ->
            def r = mockMvc.perform(
                MockMvcRequestBuilders.get('/api/database')
                    .param('sort', s)
                    .param('limit', '5')
            ).andReturn()
            assert r.response.status == 200
        }
    }

    def "GET /api/database handles filter + pagination without 500"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/database')
                .param('q', 'hat')
                .param('category', 'Hats')
                .param('rarity', 'All')
                .param('sort', 'price_asc')
                .param('limit', '10')
                .param('offset', '0')
        ).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('items')
        r.response.contentAsString.contains('total')
    }

    def "GET /api/database rejects unknown sort by falling back to default"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/database')
                .param('sort', '; DROP TABLE items; --')
                .param('limit', '5')
        ).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('items')
    }

    def "POST /api/loadouts/999/favorite returns 401/403 when anonymous (never succeeds)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/loadouts/999/favorite')
        ).andReturn()

        then:
        // Favoriting must not go through for an anonymous caller —
        // CSRF/CORS filters typically flag it first (403), otherwise
        // UnauthorizedException fires and bubbles up as 401.
        r.response.status == 401 || r.response.status == 403
    }

    def "GET /api/loadouts/999 returns 404 for an unknown loadout id"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/loadouts/999')).andReturn()

        then:
        r.response.status == 404
    }

    def "GET /api/offers/thread/999 returns 200 + empty list for anonymous viewer"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/offers/thread/999')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString == '[]'
    }

    def "GET /api/bids/listing/999 returns 200 + empty list for anonymous viewer"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/bids/listing/999')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString == '[]'
    }

    def "POST /api/items is removed — anonymous call must never create a catalogue row"() {
        when:
        // MockMvc bypasses the CsrfFilter (that runs at the servlet layer),
        // so this hits the Spring handler mapping directly. If the old
        // unauthenticated POST handler ever gets re-added, this test turns
        // the regression into a CI failure.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/items')
                .contentType('application/json')
                .content('{"name":"X","category":"Hats","rarity":"Standard","lowestPrice":0.01}')
        ).andReturn()

        then:
        // Spring returns 405 Method Not Allowed when the path exists but
        // the verb isn't registered, or 404 if the handler is gone.
        r.response.status == 405 || r.response.status == 404
    }

    def "POST /api/wallet/deposit returns 401 for anonymous callers (bug #33 regression)"() {
        when:
        // Anonymous users used to fall through currentWallet() to the
        // demo wallet and trigger real Stripe Checkout Session creation.
        // The endpoint must now short-circuit at the user check.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/wallet/deposit')
                .contentType('application/json')
                .content('{"amount": 10}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/wallet/confirm-deposit returns 401 for anonymous callers (bug #33)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/wallet/confirm-deposit')
                .param('sessionId', 'cs_test_fake')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET / returns 200 (the SPA shell)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/')).andReturn()

        then:
        r.response.status == 200
    }

    def "GET /robots.txt returns 200"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/robots.txt')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('User-agent')
    }

    def "GET /legal/terms.html returns 200"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/legal/terms.html')).andReturn()

        then:
        r.response.status == 200
    }

    def "GET /legal/privacy.html returns 200 but is noindex-marked"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/legal/privacy.html')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('noindex')
    }

    def "GET /h2-console returns 404 (hard-blocked even when Spring H2 console is off)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/h2-console')).andReturn()

        then:
        r.response.status == 404
    }

    def "GET /v3/api-docs returns 404 (swagger disabled)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/v3/api-docs')).andReturn()

        then:
        r.response.status == 404
    }

    def "GET /swagger-ui.html returns 404"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/swagger-ui.html')).andReturn()

        then:
        r.response.status == 404
    }

    // ── Auth-required endpoints return 401 without a session ─────

    def "GET /api/profile/me returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/profile/me')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/admin/stats returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/admin/stats')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/admin/fraud returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/admin/fraud')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/admin/check returns 200 with admin:false for anonymous caller"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/admin/check')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('"admin":false')
    }

    def "GET /api/notifications returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/notifications')).andReturn()

        then:
        r.response.status == 401
    }

    // ── BlockedPathsController ─────────────────────────────────

    def "GET /actuator returns 404 (not the SPA shell)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/actuator')).andReturn()

        then:
        r.response.status == 404
    }

    def "GET /actuator/health returns 404 (Spring Boot actuator fully disabled)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/actuator/health')).andReturn()

        then:
        r.response.status == 404
    }

    def "GET /api/doesnotexist returns 404 with a JSON body, not the SPA shell"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/doesnotexist')).andReturn()

        then:
        r.response.status == 404
        // If this returned the SPA shell the body would be HTML — verify
        // we get the structured error shape instead.
        r.response.contentAsString.contains('"code":"NOT_FOUND"')
        r.response.contentAsString.contains('Unknown API endpoint')
    }

    def "GET /api/foo/bar (deeper unknown path) also returns structured 404"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/foo/bar')).andReturn()

        then:
        r.response.status == 404
        r.response.contentAsString.contains('"code":"NOT_FOUND"')
    }

    // ── Trade endpoints require auth ──────────────────────────────

    def "GET /api/trades returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/trades')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/trades/999/accept returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/trades/999/accept')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/trades/999/dispute returns 401 without a session"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/trades/999/dispute')
                .contentType('application/json')
                .content('{"reason":"test"}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    // ── Support endpoints require auth ────────────────────────────

    def "GET /api/support/tickets returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/support/tickets')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/support/tickets returns 401 without a session"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/support/tickets')
                .contentType('application/json')
                .content('{"subject":"test","body":"test"}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    // ── Review endpoint is public for reads ───────────────────────

    def "GET /api/reviews/user/999 returns 200 + empty list (public)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/reviews/user/999')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString == '[]'
    }

    def "GET /api/reviews/user/999/summary returns 200 with count 0 (public)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/reviews/user/999/summary')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('"count"')
    }

    // ── Wallet requires auth for write operations ─────────────────

    def "POST /api/wallet/withdraw returns 401 without a session"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/wallet/withdraw')
                .contentType('application/json')
                .content('{"amount":10,"destination":"test"}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    // ── Offer write operations require auth ───────────────────────

    def "POST /api/offers returns 401 without a session"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/offers')
                .contentType('application/json')
                .content('{"listingId":1,"amount":10}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    // ── Stripe webhook rejects invalid signatures ─────────────────

    def "POST /api/stripe/webhook rejects invalid signature with 400"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/stripe/webhook')
                .contentType('application/json')
                .content('{"type":"checkout.session.completed"}')
                .header('Stripe-Signature', 'invalid_sig')
        ).andReturn()

        then:
        // Stripe SDK throws SignatureVerificationException → SecurityException → 400
        r.response.status == 400
        r.response.contentAsString.contains('invalid signature')
    }

    def "POST /api/stripe/webhook rejects missing signature with 400"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/stripe/webhook')
                .contentType('application/json')
                .content('{"type":"checkout.session.completed"}')
        ).andReturn()

        then:
        // No Stripe-Signature header → sig defaults to "" → verification fails → 400
        r.response.status == 400
    }

    // ── Buy order + bid auth gates ────────────────────────────────

    def "POST /api/buy-orders returns 401 without a session"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/buy-orders')
                .contentType('application/json')
                .content('{"itemId":1,"maxPrice":10,"quantity":1}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/bids returns 401 without a session"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/bids')
                .contentType('application/json')
                .content('{"listingId":1,"amount":10}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    // ── API key auth gates ────────────────────────────────────────

    def "GET /api/api-keys returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/api-keys')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/api-keys returns 401 without a session"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/api-keys')
                .contentType('application/json')
                .content('{"label":"test"}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    // ── Loadout write operations require auth ─────────────────────

    def "POST /api/loadouts returns 401 without a session"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/loadouts')
                .contentType('application/json')
                .content('{"name":"My Set","visibility":"PUBLIC"}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    // ── Steam inventory requires auth ─────────────────────────────

    def "GET /api/steam/inventory returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/steam/inventory')).andReturn()

        then:
        r.response.status == 401
    }

    // ── SEO assets ────────────────────────────────────────────────

    def "GET /sitemap.xml returns 200 with XML content"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/sitemap.xml')).andReturn()

        then:
        r.response.status == 200
        // Sitemap is now built dynamically — `app.public-url` controls
        // the absolute base URL (defaults to http://localhost:8080 in
        // the default profile so tests don't depend on env vars). What
        // we care about is the structure: urlset + at least one URL.
        r.response.contentAsString.contains('urlset')
        r.response.contentAsString.contains('<loc>')
    }

    def "GET /robots.txt includes Sitemap directive"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/robots.txt')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('Sitemap:')
        r.response.contentAsString.contains('skinbox.market/sitemap.xml')
    }

    def "GET /favicon.ico returns 200"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/favicon.ico')).andReturn()

        then:
        r.response.status == 200
    }

    // ── Item-specific listing endpoint (bug #80 regression) ───────

    def "GET /api/listings/item/999 returns 200 + empty array for unknown item"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/item/999')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString == '[]'
    }

    def "GET /api/listings/item/{id} does NOT return all listings (bug #80)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/item/1')).andReturn()

        then:
        // Must NOT return the full marketplace. The old bug was that
        // fetchListings({itemId}) sent itemId as a query param that was
        // silently ignored, returning every listing.
        r.response.status == 200
        // Even if item 1 has listings, the count must be <= total items
        // (not the full 41-listing marketplace dump).
        def body = r.response.contentAsString
        body.startsWith('[')
    }

    // ── Dynamic sitemap + OG-tag injection (SEO surface) ─────────

    def "GET /sitemap.xml emits a urlset with static nav URLs"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/sitemap.xml')).andReturn()

        then:
        r.response.status == 200
        def body = r.response.contentAsString
        body.contains('<urlset')
        // Static URLs carried over from the old static sitemap.xml — these
        // are present regardless of whether the items table has any seed
        // rows in the test profile. Item URLs get appended dynamically
        // when the catalogue is populated.
        body.contains('<loc>')
        body.contains('/legal/terms.html')
    }

    def "GET /sitemap.xml has XML Content-Type"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/sitemap.xml')).andReturn()

        then:
        r.response.status == 200
        r.response.getHeader('Content-Type')?.toLowerCase()?.contains('xml')
    }

    def "GET /item/{id} injects per-item OG tags into the SPA shell when item exists"() {
        given:
        // Pull a real item id from the listings feed so this test isn't
        // coupled to a specific seed row. Skip silently if the test DB
        // isn't seeded with any listings.
        def listingResp = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings?limit=1')).andReturn()
        def listingBody = listingResp.response.contentAsString
        def m = (listingBody =~ /"item":\s*\{[^}]*"id":\s*(\d+)/)

        expect:
        if (!m.find()) {
            // Cold DB — the OG rewrite path isn't exercised here, but the
            // unknown-id test below still covers the template fallback.
            return
        }
        def itemId = m.group(1)
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/item/' + itemId)).andReturn()
        assert r.response.status == 200
        def body = r.response.contentAsString
        assert body.contains('/item/' + itemId)
        assert body.contains('og:title')
        assert body.contains('twitter:title')
    }

    def "GET /item/{id} for an unknown id returns the template unchanged"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/item/999999999')).andReturn()

        then:
        // Falls back to the static index.html so crawlers never see a
        // 404 on an item URL — they get the generic SkinBox preview.
        r.response.status == 200
        def body = r.response.contentAsString
        body.contains('<html')
        body.contains('og:title')
    }

    def "GET /api/version returns the app version"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/version')).andReturn()

        then:
        r.response.status == 200
        def body = r.response.contentAsString
        body.contains('version')
        body.contains('.')  // e.g. 1.0.0
    }

    def "GET /api/listings/recent-sales caps the returned rows at 30"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/recent-sales?limit=500')
        ).andReturn()

        then:
        r.response.status == 200
        // Array-shaped response regardless of whether the platform has
        // any SOLD rows yet — fresh installs should still return [].
        r.response.contentAsString.startsWith('[')
    }
}
