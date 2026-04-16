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
}
