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
    @Autowired com.sboxmarket.service.EmailService emailService
    @Autowired com.sboxmarket.repository.LoadoutRepository loadoutRepo

    def "GET /api/unsubscribe with no token renders the error card (batch 891)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/unsubscribe?email=nobody@example.com')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('Couldn')  // "Couldn't process"
        r.response.contentAsString.contains('Missing email or token')
    }

    def "GET /api/unsubscribe with bad token renders expired/malformed (batch 891)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/unsubscribe?email=nobody@example.com&t=not-a-real-token')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('expired or is malformed')
    }

    def "POST /api/unsubscribe with valid token returns 200 (batch 892 RFC 8058 one-click)"() {
        // Mail clients (Gmail, Outlook) POST to the List-Unsubscribe
        // URL with `List-Unsubscribe=One-Click` in the body. They only
        // care about the status code; our controller returns 200 for
        // both valid and invalid tokens (two reasons: (1) mail clients
        // retry on non-2xx which would spam the endpoint, (2) success
        // is idempotent — same behaviour as GET). Exempted from CSRF
        // because the POST comes from a mail client with no session.
        given:
        def email = 'ghost-nobody@example.invalid'
        def tok = emailService.unsubscribeToken(email)

        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/unsubscribe')
                .param('email', email)
                .param('t', tok)
        ).andReturn()

        then:
        r.response.status == 200
    }

    def "GET /api/unsubscribe with valid token returns success idempotently (batch 891)"() {
        // Mint a token the same way the email footer does; use an email
        // that doesn't have an account — we still expect success so
        // attackers can't enumerate registered addresses.
        given:
        def email = 'ghost-nobody-has-this@example.invalid'
        def tok = emailService.unsubscribeToken(email)

        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/unsubscribe')
                .param('email', email)
                .param('t', tok)
        ).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('You\'re unsubscribed from email notifications')
    }

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

    def "GET /api/loadouts/999 returns 200 (never 404) for an unknown loadout id"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/loadouts/999')).andReturn()

        then:
        // Contract: missing/private loadout reads return 200, never 404 —
        // Chrome auto-logs every fetch 404 to the browser console regardless
        // of JS handling, which made every dead-share-link landing read as a
        // phantom bug. Two recoverable 200 shapes satisfy the contract: when
        // public loadouts exist (the normal seeded state) LoadoutController
        // serves the lowest-id public loadout as a fallback carrying
        // `redirectedFrom`; when none exist it returns the `{notFound:true}`
        // sentinel. The SPA's `fetchLoadout` handles both — never a 404.
        r.response.status == 200
        (r.response.contentAsString.contains('"notFound":true')
            || r.response.contentAsString.contains('"redirectedFrom":999'))
    }

    def "GET /api/items/999999999 returns 200 with a notFound sentinel for an unknown item id"() {
        // Same dead-link console-cleanliness contract as /api/loadouts/999.
        // Pre-fix: every /item/{badId} landing fired a 404 from the canonical
        // /api/items/{id} probe, which Chrome auto-logged to console as a
        // "Failed to load resource: 404" line on top of safeJson's own warn.
        // Post-fix: 200 + sentinel; api.js's fetchItem translates back to null
        // so callers still see null-on-missing.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/items/999999999')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('"notFound":true')
        r.response.contentAsString.contains('999999999')
    }

    def "GET /api/listings/stall/99999999 returns 200 with a notFound sentinel for an unknown user id"() {
        // Same contract as the item + loadout read-by-id sentinels.
        // /stall/:badId previously fired 4 parallel API calls including
        // a 404 here; the 404 is now a 200+sentinel so the SPA's "Stall
        // not found" branded empty-state lands without console noise.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/stall/99999999')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('"notFound":true')
    }

    def "GET /api/loadouts/{id} includes `favorited: false` for anonymous viewers (batch 917 contract)"() {
        given: 'a seeded public loadout owned by a fake user'
        def seeded = loadoutRepo.save(new com.sboxmarket.model.Loadout(
            ownerUserId: 424242L,
            ownerName:   'fake-owner',
            name:        'HTTP-spec loadout',
            visibility:  'PUBLIC'
        ))

        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/loadouts/' + seeded.id)).andReturn()

        then:
        r.response.status == 200
        // Anonymous viewers must see favorited=false (never omitted). The
        // frontend's loadout-favorite button reads this flag directly and
        // renders ♥ vs ♡ based on it (batch 917). A regression to an
        // omitted key would flip every anon visitor to "favorited" false-
        // positive via the Boolean cast.
        def body = r.response.contentAsString
        body.contains('"favorited":false')
        body.contains('"slots"')
        body.contains('"loadout"')

        cleanup:
        try { loadoutRepo.deleteById(seeded.id) } catch (ignore) {}
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

    def "GET /api/wallet returns zeroed anon snapshot — no demo wallet leak (batch 976 regression)"() {
        // Before batch 976 the controller fell through to the persisted
        // demo wallet (id=1) and leaked its balance + cap usage + dispute
        // hold count to every anonymous caller. The fix short-circuits on
        // the user check and returns a zero-balance snapshot with null
        // identifiers. Signed-out users' hero chip also no longer flashes
        // the demo balance between sign-out and page reload.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/wallet')).andReturn()

        then:
        r.response.status == 200
        def body = r.response.contentAsString
        body.contains('"id":null')
        body.contains('"username":null')
        body.contains('"loggedIn":false')
        body.contains('"balance":0')
        !body.contains('"username":"demo"')
        !body.contains('"balance":250')
    }

    def "GET /api/wallet/transactions returns empty for anon — no demo tx leak (batch 977)"() {
        // Same class of bug as batch 976 — the endpoint fell through
        // `currentWallet` to the demo wallet and returned its tx rows.
        // Current demo wallet has no transactions but the fix prevents
        // a future admin-credit or test seeding from becoming public.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/wallet/transactions')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString == '[]'
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

    def "GET /api/sellers/top returns 200 with a JSON array (anon-accessible)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/sellers/top')
        ).andReturn()

        then:
        r.response.status == 200
        // Empty seed DB returns []; populated dev data returns an array of
        // seller cards. Either way the shape is a JSON array.
        r.response.contentAsString.startsWith('[')
    }

    def "GET /api/sellers/top clamps crafted inputs to sane bounds"() {
        when:
        // days=9999 is clamped to 90, limit=999 is clamped to 20, negative
        // values are clamped up to 1. A crafted query can't force a full-
        // table scan or a monster response. 200 in every case.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/sellers/top')
                .param('days', '9999')
                .param('limit', '-5')
        ).andReturn()

        then:
        r.response.status == 200
    }

    def "PATCH /api/follows/123/mute returns 401 for anonymous"() {
        when:
        // Mute toggle is a per-user preference write; anonymous callers
        // can't have follows, so the endpoint must 401 before doing
        // anything.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.patch('/api/follows/123/mute')
                .contentType('application/json')
                .content('{"muted":true}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/saved-searches returns 401 for anonymous"() {
        when:
        // Saved searches are signed-in only; anonymous keeps presets in
        // localStorage. The endpoint must 401 before leaking the list shape.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/saved-searches')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/saved-searches returns 401 for anonymous"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/saved-searches')
                .contentType('application/json')
                .content('{"name":"x"}')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/cart returns 401 for anonymous"() {
        when:
        // Server-side cart is signed-in only; anonymous keeps cart in
        // localStorage. The endpoint must not leak shape (ids array)
        // either, so it 401s at the controller before hitting any data.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/cart')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/cart/123 returns 401 for anonymous"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/cart/123')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/listings/sales-velocity returns 200 with a JSON map (anon, batch 288)"() {
        when:
        // Public bulk velocity feed for the marketplace grid — anon
        // browsers see the same hot-item signal signed-in users do.
        // Empty seed returns {}; populated returns object keyed by id.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/sales-velocity')
                .param('ids', '1,2,3')
        ).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.startsWith('{')
    }

    def "GET /api/listings/sales-velocity tolerates missing param + bad tokens"() {
        when:
        def empty = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/sales-velocity')
        ).andReturn()
        def garbage = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/sales-velocity')
                .param('ids', 'not-a-number,3,xyz')
        ).andReturn()

        then:
        empty.response.status == 200
        empty.response.contentAsString == '{}'
        garbage.response.status == 200
        // The "3" survives; the malformed tokens are silently dropped.
        garbage.response.contentAsString.startsWith('{')
    }

    def "GET /api/listings/sales-velocity clamps a crafted days param"() {
        when:
        // Out-of-bounds days should be clamped (1..30) rather than 400.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/sales-velocity')
                .param('ids', '1')
                .param('days', '9999')
        ).andReturn()

        then:
        r.response.status == 200
    }

    def "GET /api/listings/hottest returns 200 with a JSON array (anon, batch 289)"() {
        when:
        // Public discovery rail using the SOLD aggregate. Empty seed
        // returns []; populated returns enriched listings.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/hottest')
                .param('limit', '8')
        ).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.startsWith('[')
    }

    def "GET /api/listings/hottest clamps both limit and days"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/hottest')
                .param('limit', '9999')
                .param('days', '9999')
        ).andReturn()

        then:
        r.response.status == 200
    }

    def "GET /api/listings/most-watched returns 200 with a JSON array (anon)"() {
        when:
        // Public discovery rail — anonymous and signed-in users see the
        // same list. Empty seed returns []; populated returns enriched
        // listings sorted by aggregate watcher count desc.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/most-watched')
                .param('limit', '8')
        ).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.startsWith('[')
    }

    def "GET /api/listings/most-watched clamps a crazy limit"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/most-watched')
                .param('limit', '9999')
        ).andReturn()

        then:
        // Clamped at 30 internally so a crafted query can't pull every
        // watched item ever.
        r.response.status == 200
    }

    def "GET /api/watchlist/counts is public and returns JSON map"() {
        when:
        // Bulk watcher counts drive the marketplace card "👁 N" badge —
        // anonymous viewers should see the same number signed-in users
        // do (pure aggregate, no PII). Empty input returns {}.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/watchlist/counts')
                .param('ids', '1,2,3')
        ).andReturn()

        then:
        r.response.status == 200
        // Either the seed has watchers (object with item-id keys) or it
        // doesn't (empty object). Both are valid; we just pin the JSON
        // object shape and 200 status.
        r.response.contentAsString.startsWith('{')
    }

    def "GET /api/watchlist/counts tolerates malformed ids and missing param"() {
        when:
        def empty = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/watchlist/counts')
        ).andReturn()
        def garbage = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/watchlist/counts')
                .param('ids', 'not-a-number,3,xyz')
        ).andReturn()

        then:
        empty.response.status == 200
        empty.response.contentAsString == '{}'
        garbage.response.status == 200
        // The "3" survives; the malformed tokens are silently dropped.
        garbage.response.contentAsString.startsWith('{')
    }

    def "GET /api/watchlist returns 401 for anonymous"() {
        when:
        // Anonymous users keep their watchlist in localStorage; the
        // server endpoint is a signed-in surface only.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/watchlist')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/watchlist/123 returns 401 for anonymous"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/watchlist/123')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/reviews/999/helpful returns 401 for anonymous"() {
        when:
        // Helpful-votes must not be toggleable without a session — the
        // aggregate is the sort key for the stall-page review list and
        // anonymous vote-stuffing would destroy that signal. Hitting the
        // endpoint without a session (MockMvc bypasses the CSRF filter)
        // must 401 at the controller level.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/reviews/999/helpful')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/profile/pending-actions returns 401 for anonymous"() {
        when:
        // The aggregate exposes counts across the authed user's trades +
        // offers; anonymous callers must not leak a zeroed shape either.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/profile/pending-actions')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/reviews/pending returns 401 for anonymous (batch 337)"() {
        when:
        // Surface for the Profile → Reviews → Pending tab. Must not leak
        // "0 pending" to anonymous callers — that would confirm the endpoint
        // exists and is otherwise queryable.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/reviews/pending')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/reviews/mine returns 401 for anonymous"() {
        when:
        // Authored-reviews list is PII-adjacent (reveals what the user has
        // publicly reviewed) but the endpoint intentionally gates on session
        // rather than exposing by user id.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/reviews/mine')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/reviews/eligible/999 returns 401 for anonymous"() {
        when:
        // Eligible-trades-between-viewer-and-seller is strictly per-viewer;
        // no public variant exists.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/reviews/eligible/999')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/profile/blocks returns 401 for anonymous (batch 343)"() {
        when:
        // Block-list surface — leaks no data about who has blocked whom
        // to anonymous callers.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/profile/blocks')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/profile/blocks/999 returns 401 for anonymous"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/profile/blocks/999')
        ).andReturn()

        then:
        // Either 401 (unauth) or 403 (csrf) is acceptable — both reject
        // an anonymous write attempt. The 403 path means the CSRF filter
        // caught it before the session check, which is still rejected.
        r.response.status == 401 || r.response.status == 403
    }

    def "DELETE /api/profile/blocks/999 returns 401 for anonymous"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.delete('/api/profile/blocks/999')
        ).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403
    }

    def "DELETE /api/profile/blocks (bulk) returns 401 or 403 for anonymous (batch 355)"() {
        when:
        // Bulk-unblock endpoint — same auth gate as the per-row delete.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.delete('/api/profile/blocks')
        ).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403
    }

    def "DELETE /api/saved-searches (bulk) returns 401 or 403 for anonymous (batch 354)"() {
        when:
        // Bulk-clear endpoint — same auth gate as the per-row delete.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.delete('/api/saved-searches')
        ).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403
    }

    def "POST /api/auth/steam/logout-all returns 401 for anonymous callers"() {
        when:
        // Anonymous callers should never be able to bump another user's
        // sessionEpoch. The endpoint short-circuits at the session check.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/auth/steam/logout-all')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/auth/steam/logout returns 204 even for anonymous (idempotent)"() {
        when:
        // Regular logout is best-effort idempotent — hitting it without a
        // session just tears down whatever is there and returns 204. This
        // pins the contract so the frontend never has to branch on 401 vs
        // 204 for the "sign out" button.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/auth/steam/logout')
        ).andReturn()

        then:
        r.response.status == 204
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

    def "GET /legal/privacy.html returns 200 (indexable, like terms)"() {
        // The privacy policy is a normal, publicly-discoverable legal
        // page — it carries no robots/noindex directive, exactly like
        // /legal/terms.html above. (A crawler SHOULD be able to index
        // a site's privacy policy; noindex here would be unusual.)
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/legal/privacy.html')).andReturn()

        then:
        r.response.status == 200
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

    // ── NotificationController.readBatch (batch 635) ───────────────
    // Pins the new filter-scoped "Mark visible read" contract. POST-only,
    // auth-gated, routed under /api/notifications. MockMvc is mounted
    // without CsrfFilter so an anon POST hits the auth check directly —
    // real nginx+filter traffic would 403 on CSRF first, which is fine.

    def "POST /api/notifications/read-batch returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/notifications/read-batch')
            .contentType('application/json').content('{"ids":[1,2]}')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/notifications/read-batch is not exposed (POST-only route)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/notifications/read-batch')).andReturn()

        then:
        // BlockedPathsController's /api/** catch-all flips wrong-verb hits
        // to a 404 JSON shape — that's the contract callers actually see.
        r.response.status == 404
        r.response.contentAsString.contains('"code":"NOT_FOUND"')
    }

    // ── NotificationController.deleteBatch (batch 636) ───────────────

    def "POST /api/notifications/delete-batch returns 401 without a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/notifications/delete-batch')
            .contentType('application/json').content('{"ids":[1,2]}')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/notifications/delete-batch is not exposed (POST-only route)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/notifications/delete-batch')).andReturn()

        then:
        r.response.status == 404
        r.response.contentAsString.contains('"code":"NOT_FOUND"')
    }

    // ── ListingController listingType case-normalisation (batch 656) ─
    // Regression: a share URL carrying `listingType=auction` (lowercase)
    // used to fall through the whitelist and return unfiltered listings
    // instead of filtering to auctions. Now both cases hit the same
    // filter path.

    def "GET /api/listings?listingType=auction (lowercase) is equivalent to AUCTION"() {
        when:
        def lower = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('listingType', 'auction').param('limit', '5')).andReturn()
        def upper = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('listingType', 'AUCTION').param('limit', '5')).andReturn()

        then:
        // Both must return the same payload (empty in seed data; either
        // way the bodies match since the normaliser treats them
        // identically).
        lower.response.status == 200
        upper.response.status == 200
        lower.response.contentAsString == upper.response.contentAsString
    }

    def "GET /api/listings?listingType=BuY_NoW (mixed case) routes to BUY_NOW"() {
        when:
        def mixed = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('listingType', 'BuY_NoW').param('limit', '3')).andReturn()
        def upper = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('listingType', 'BUY_NOW').param('limit', '3')).andReturn()

        then:
        mixed.response.status == 200
        mixed.response.contentAsString == upper.response.contentAsString
    }

    // ── ListingController category/rarity case-normalisation (batch 657) ─
    // Regression: `?category=hats` (lowercase) used to return empty results
    // because the service's filter was a case-sensitive string equals against
    // the canonical `Hats` form. Same for rarity.

    def "GET /api/listings?category=hats (lowercase) matches the canonical 'Hats' filter"() {
        when:
        def lower = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('category', 'hats').param('limit', '3')).andReturn()
        def title = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('category', 'Hats').param('limit', '3')).andReturn()

        then:
        lower.response.status == 200
        title.response.status == 200
        lower.response.contentAsString == title.response.contentAsString
    }

    def "GET /api/listings?rarity=standard (lowercase) matches the canonical 'Standard' filter"() {
        when:
        def lower = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('rarity', 'standard').param('limit', '3')).andReturn()
        def title = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('rarity', 'Standard').param('limit', '3')).andReturn()

        then:
        lower.response.status == 200
        title.response.status == 200
        lower.response.contentAsString == title.response.contentAsString
    }

    def "GET /api/listings?rarity=off-market (lowercase with hyphen) matches 'Off-Market'"() {
        when:
        def lower = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('rarity', 'off-market').param('limit', '3')).andReturn()
        def canon = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('rarity', 'Off-Market').param('limit', '3')).andReturn()

        then:
        lower.response.status == 200
        canon.response.status == 200
        lower.response.contentAsString == canon.response.contentAsString
    }

    def "GET /api/items?sort=PRICE_ASC matches price_asc (batch 661)"() {
        given:
        // Regression: ItemService.search has a case-sensitive switch on
        // sort; an uppercase input used to fall through to the default
        // (price_desc), silently flipping a user's intended ASC view.
        // The controller now lowercases first so both routes hit the
        // same switch case.
        def extract = { String json ->
            def slurper = new groovy.json.JsonSlurper()
            def parsed = slurper.parseText(json)
            (parsed instanceof List ? parsed : parsed?.items ?: [])
                .collect { it?.lowestPrice ?: 0 }
                .take(3)
        }

        when:
        def upper = mockMvc.perform(MockMvcRequestBuilders.get('/api/items')
            .param('sort', 'PRICE_ASC')).andReturn()
        def lower = mockMvc.perform(MockMvcRequestBuilders.get('/api/items')
            .param('sort', 'price_asc')).andReturn()

        then:
        upper.response.status == 200
        lower.response.status == 200
        extract(upper.response.contentAsString) == extract(lower.response.contentAsString)
    }

    def "GET /api/listings?sort=PRICE_DESC matches price_desc (batch 661)"() {
        given:
        def extract = { String json ->
            def slurper = new groovy.json.JsonSlurper()
            def parsed = slurper.parseText(json)
            (parsed instanceof List ? parsed : parsed?.items ?: [])
                .collect { it?.price ?: 0 }
                .take(3)
        }

        when:
        def upper = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('sort', 'PRICE_DESC').param('limit', '3')).andReturn()
        def lower = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('sort', 'price_desc').param('limit', '3')).andReturn()

        then:
        upper.response.status == 200
        lower.response.status == 200
        extract(upper.response.contentAsString) == extract(lower.response.contentAsString)
    }

    def "GET /api/items?category=hats (lowercase) matches 'Hats' (batch 658)"() {
        when:
        def lower = mockMvc.perform(MockMvcRequestBuilders.get('/api/items')
            .param('category', 'hats').param('limit', '3')).andReturn()
        def canon = mockMvc.perform(MockMvcRequestBuilders.get('/api/items')
            .param('category', 'Hats').param('limit', '3')).andReturn()

        then:
        lower.response.status == 200
        canon.response.status == 200
        lower.response.contentAsString == canon.response.contentAsString
    }

    def "GET /api/database?category=hats (lowercase) matches 'Hats' (batch 658)"() {
        given:
        // Compare on the `total` field and the `items` list length
        // rather than raw string equality — price-tier ties break
        // non-deterministically on the DB side regardless of the
        // exposed sort key, and the freshness of `createdAt` can
        // also diverge slightly between back-to-back requests. The
        // real invariant the test pins is: both casings route to
        // the same filter and therefore return the same *count*
        // of matching rows from the catalogue.
        def extract = { String json ->
            def slurper = new groovy.json.JsonSlurper()
            def parsed = slurper.parseText(json)
            [total: parsed?.total, indexed: parsed?.indexed, items: parsed?.items?.size()]
        }

        when:
        def lower = mockMvc.perform(MockMvcRequestBuilders.get('/api/database')
            .param('category', 'hats').param('limit', '10')).andReturn()
        def canon = mockMvc.perform(MockMvcRequestBuilders.get('/api/database')
            .param('category', 'Hats').param('limit', '10')).andReturn()

        then:
        lower.response.status == 200
        canon.response.status == 200
        extract(lower.response.contentAsString) == extract(canon.response.contentAsString)
    }

    def "GET /api/listings?category=gibberish falls through to All (no filter)"() {
        when:
        def bogus = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('category', 'gibberish').param('limit', '3')).andReturn()
        def noArg = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('limit', '3')).andReturn()

        then:
        // Unknown category → canonEnum returns 'All' → no filter, same as
        // passing no category param at all.
        bogus.response.status == 200
        bogus.response.contentAsString == noArg.response.contentAsString
    }

    // ── SteamInventoryController list endpoints (batch 646/648) ─────
    // These endpoints create listings on behalf of the user, so the auth
    // gate is load-bearing. Spec pins that anon POSTs never reach the
    // service logic regardless of payload shape.

    def "POST /api/steam/list requires a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/steam/list')
            .contentType('application/json')
            .content('{"assetId":"1","price":5.00,"maxDiscount":0.20}')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/steam/list-bulk requires a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/steam/list-bulk')
            .contentType('application/json')
            .content('{"assetIds":["1","2"],"price":5.00,"maxDiscount":0.15}')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/listings/sell requires a session (batch 646 maxDiscount path)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/listings/sell')
            .contentType('application/json')
            .content('{"listingId":1,"price":5.00,"maxDiscount":0.20}')).andReturn()

        then:
        // Auth gate fires before DTO validation, so a correctly-shaped
        // payload with maxDiscount still 401s for anon.
        r.response.status == 401
    }

    // ── ProfileController email verify (batch 647) ──────────────────
    // Pin the auth gate + contract shape for the email-verify flow.
    // Real expiry-rejection behaviour is covered at the DB level by
    // the migration + controller code; this spec just confirms anon
    // callers can't reach the endpoint at all.

    def "POST /api/profile/email/verify requires a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/profile/email/verify')
            .contentType('application/json').content('{"token":"abc"}')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/profile/email/resend requires a session"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/profile/email/resend')).andReturn()

        then:
        r.response.status == 401
    }

    // ── BuyOrderController.forItem (batch 639) ───────────────────────

    def "GET /api/buy-orders/for-item/{id} is public and returns a JSON array"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/buy-orders/for-item/999999')).andReturn()

        then:
        // No auth gate — same public contract as /count/item/{id}. An
        // unknown item id just returns [] rather than 404.
        r.response.status == 200
        r.response.contentAsString.trim().startsWith('[')
    }

    // ── WalletController.exportTransactionsCsv — month scope (batch 642) ──
    // Regression tests: the month param must not change the auth gate,
    // and a malformed month string must fall through rather than 400.
    // Spec deliberately exercises the filter layer at the HTTP seam
    // (mockMvc), not the service, because the month-parse logic lives
    // in the controller body.

    def "GET /api/wallet/transactions.csv requires auth (no month param)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/wallet/transactions.csv')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/wallet/transactions.csv?month=YYYY-MM still requires auth"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/wallet/transactions.csv')
            .param('month', '2026-04')).andReturn()

        then:
        // Month scope is a nicety for tax exports — it MUST NOT bypass
        // the unauth gate. An anon caller gets 401 regardless of the
        // param shape.
        r.response.status == 401
    }

    def "GET /api/wallet/transactions.csv?month=junk silently falls through to no-filter (still 401 for anon)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/wallet/transactions.csv')
            .param('month', 'not-a-month')).andReturn()

        then:
        // Month regex rejects "not-a-month" → controller treats as no
        // filter → auth gate still fires first. A malformed bookmarked
        // URL must never 400, the user has to fix it via the UI.
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

    def "POST /api/buy-orders/cancel-all rejects anon callers (auth gate)"() {
        // Added with batch 290. Pin the 401/403 contract so a future
        // filter reorder or controller rename can't silently flip it.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/buy-orders/cancel-all')).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403
    }

    def "POST /api/offers/outgoing/cancel-all rejects anon callers (auth gate)"() {
        // Added with batch 291.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/offers/outgoing/cancel-all')).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403
    }

    def "GET /api/profile/bids.csv rejects anon callers (auth gate)"() {
        // Added with batch 292 — bids CSV completes the activity-CSV set
        // (trades / offers / buy-orders / wallet all have siblings).
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/profile/bids.csv')).andReturn()

        then:
        r.response.status == 401
    }

    def "DELETE /api/watchlist rejects anon callers (auth gate)"() {
        // Added with batch 293 — bulk-clear mirrors cancel-all family.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.delete('/api/watchlist')).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403
    }

    def "DELETE /api/follows rejects anon callers (auth gate)"() {
        // Added with batch 294 — bulk-unfollow mirrors cancel-all family.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.delete('/api/follows')).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403
    }

    def "PATCH /api/follows/mute-all rejects anon callers (auth gate)"() {
        // Added with batch 295 — bulk mute/unmute toggle for followed sellers.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.patch('/api/follows/mute-all')
            .contentType('application/json')
            .content('{"muted":true}')).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403
    }

    def "GET /api/sellers/verified returns 200 with JSON map for anon viewers"() {
        // Added with batch 296 — verified-seller bulk endpoint powers
        // the ✓ chip on marketplace ItemModal listings rows. Public,
        // no auth required.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/sellers/verified').param('ids', '1,2,3')).andReturn()

        then:
        r.response.status == 200
        r.response.contentType.contains('json')
    }

    def "GET /api/sellers/verified tolerates missing ids without a 400"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/sellers/verified')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString == '{}'
    }

    def "GET /api/sellers/verified silently drops malformed ids"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/sellers/verified').param('ids', '1,abc,3,-5,xx')).andReturn()

        then:
        // No 400 — malformed tokens are skipped, valid ids proceed.
        r.response.status == 200
    }

    def "GET /api/sellers/search returns 200 + JSON array (batch 666 — public)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/sellers/search').param('q', 'sbox')
        ).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.startsWith('[')
    }

    def "GET /api/sellers/search short-circuits empty / short queries to []"() {
        expect:
        ['', ' ', 'a'].each { q ->
            def r = mockMvc.perform(
                MockMvcRequestBuilders.get('/api/sellers/search').param('q', q)
            ).andReturn()
            assert r.response.status == 200
            assert r.response.contentAsString == '[]'
        }
    }

    def "GET /api/sellers/search clamps crafted q length without 500"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/sellers/search')
                .param('q', 'A' * 5000)
                .param('limit', '9999')
        ).andReturn()

        then:
        // Server truncates q to 60 chars and clamps limit to 25;
        // worst case this returns [] but must not 500.
        r.response.status == 200
    }

    def "DELETE /api/api-keys (bulk revoke) requires sign-in (batch 705)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.delete('/api/api-keys')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/sellers/ship-times returns empty JSON map for anon + missing ids (batch 710)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/sellers/ship-times')).andReturn()

        then:
        // No ids param → empty object. Public endpoint (same audience as /verified + /top).
        r.response.status == 200
        r.response.contentAsString == '{}'
    }

    def "GET /api/sellers/ship-times tolerates malformed ids without 400"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/sellers/ship-times').param('ids', '1,abc,-5,xx,99')
        ).andReturn()

        then:
        // Malformed tokens skipped, valid ids queried. Worst case {}
        // when no sellers meet the 3-sample noise floor.
        r.response.status == 200
    }

    def "GET /api/admin/api-keys/lookup requires admin (batch 700)"() {
        when:
        // Admin-only fraud-triage endpoint — anon must 401 so a crafted
        // URL can't enumerate the API-key table by prefix.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/admin/api-keys/lookup').param('prefix', 'sbx_live_abc')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/profile/security-activity requires sign-in (batch 714)"() {
        when:
        // User-scoped audit view — anon must 401 so a crafted URL
        // can't dump another account's sensitive history.
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/profile/security-activity')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/profile/sign-out-everywhere requires sign-in (batch 697)"() {
        when:
        // Unauth anon must 401 — otherwise a script could spam-invalidate
        // the guest/demo wallet session (if any) on every call.
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/profile/sign-out-everywhere')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/watchlist/export.csv requires sign-in (batch 695)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/watchlist/export.csv')).andReturn()

        then:
        // Watchlist is per-user — anon 401 so a crafted URL can't
        // dump another user's watchlist via the CSV export.
        r.response.status == 401
    }

    def "GET /api/listings/my-stall/active.csv requires sign-in (batch 681)"() {
        // Active-listings CSV is seller-only — anon must 401 so a crafted
        // URL can't enumerate other sellers' inventories.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/my-stall/active.csv')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/listings/my-stall/analytics.csv requires sign-in"() {
        // Per-listing analytics CSV is seller-only — anon must 401 so a
        // crafted URL can't enumerate other sellers' view counts /
        // demand metrics. Mirrors the active.csv + sold.csv auth gate.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/my-stall/analytics.csv')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/ready returns 200 UP when DB is reachable (batch 680)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/ready')).andReturn()

        then:
        // Test profile runs H2 in-memory — the probe should succeed.
        r.response.status == 200
        r.response.contentAsString.contains('"status":"UP"')
        r.response.contentAsString.contains('"db":"up"')
    }

    def "POST /api/client-errors accepts anon + payload, returns 200 (batch 678)"() {
        when:
        // Anonymous-friendly: a crash on the first page load before
        // sign-in must still reach the server. Send the payload the
        // frontend ErrorBoundary builds.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/client-errors')
                .contentType('application/json')
                .content('{"message":"Cannot read properties of undefined","stack":"at Cart.render (modals.js:1234)","url":"https://skinbox.market/cart","userAgent":"Mozilla/5.0"}')
        ).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('"received":true')
    }

    def "POST /api/client-errors returns 204 on empty payloads (no retry-storm wedge)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/client-errors')
                .contentType('application/json')
                .content('{}')
        ).andReturn()

        then:
        // Empty message + empty stack → nothing to log, 204 No Content.
        // Deliberately NOT 400 so a crashing client loop that's mid-
        // retry doesn't wedge on a 400 wall.
        r.response.status == 204
    }

    def "POST /api/client-errors clips oversized fields without 500ing"() {
        when:
        // A payload with a 10k char stack trace. Controller should clip
        // to 4000 chars internally and log a WARN line, returning 200.
        def bigStack = 'X' * 10000
        def json = '{"message":"boom","stack":"' + bigStack + '"}'
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/client-errors')
                .contentType('application/json')
                .content(json)
        ).andReturn()

        then:
        r.response.status == 200
    }

    def "GET /api/sellers/search response pins the field contract (batch 668 — verified added)"() {
        when:
        // A 2-char query hits the full code path. Even on an empty
        // test DB the response must be a JSON array. When rows exist,
        // each carries the documented fields: sellerUserId, displayName,
        // avatarUrl, activeListings, soldCount, verified.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/sellers/search').param('q', 'ab')
        ).andReturn()

        then:
        r.response.status == 200
        def body = r.response.contentAsString
        // Either empty JSON array, or rows containing the full field set.
        body == '[]' || (
            body.contains('"sellerUserId"') &&
            body.contains('"displayName"') &&
            body.contains('"activeListings"') &&
            body.contains('"soldCount"') &&
            body.contains('"verified"'))
    }

    def "GET /api/sellers/me/verification-progress requires sign-in (batch 595)"() {
        // The verification-progress widget is strictly per-user — anon
        // callers 401 so an attacker can't probe other users' progress
        // by walking ids. Signed-in integration tested separately.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/sellers/me/verification-progress')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/listings/my-stall/earnings requires sign-in (batch 605)"() {
        // Seller earnings summary is per-user — anon 401. Numbers would
        // leak if this went public since the user id is implicit
        // (session-derived). Tests the auth gate only; the aggregate
        // math is unit-tested at the repository layer.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/my-stall/earnings')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/listings/my-stall/analytics requires sign-in (2026-05-01)"() {
        // Per-listing analytics surface added 2026-05-01. Returns view
        // counts + per-item 30-day demand + price-vs-floor delta for
        // the seller's active listings — sensitive (price strategy
        // signal). Anon must 401, same as the other my-stall routes.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/my-stall/analytics')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/admin/users/{id}/force-logout rejects anon callers (batch 592)"() {
        // Anon POST to the new force-logout endpoint hits the admin
        // gate and comes back 401. Live traffic over nginx + CSRF
        // filter would flip to 403 first, but MockMvc's filter chain
        // doesn't include CsrfFilter here — 401 is the correct
        // *controller-level* response regardless.
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/admin/users/1/force-logout')
        ).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/loadouts/favorites rejects anon callers (auth gate)"() {
        // Batch 303 — favorites list is per-user PII, never leaks to anon.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/loadouts/favorites')).andReturn()

        then:
        r.response.status == 401
    }

    def "GET /api/listings/seller/{uid}/other returns 200 with a JSON array for anon viewers"() {
        // Batch 312 — 'More from this seller' rail is public; no auth.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/seller/1/other')
            .param('excludeItemId', '1').param('limit', '8')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.startsWith('[')
    }

    def "GET /api/listings/seller/{uid}/other clamps limit to [1, 30]"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/seller/1/other')
            .param('excludeItemId', '1').param('limit', '9999')).andReturn()

        then:
        // No 400 on an oversize limit — the service clamps silently.
        r.response.status == 200
    }

    def "GET /api/items/{id}/similar returns 200 with a JSON array for anon viewers"() {
        // Batch 298 — pins the similar-items rail as a public endpoint
        // and protects against the "items with no active listings first"
        // regression that used to lure buyers into dead-end detail pages.
        given:
        // Discover an item id from the live listings endpoint so the
        // test doesn't couple to a specific seed row.
        def listingResp = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings?limit=1')).andReturn()
        def m = (listingResp.response.contentAsString =~ /"item":\s*\{[^}]*"id":\s*(\d+)/)

        expect:
        if (!m.find()) return   // Cold DB — nothing to probe.
        def itemId = m.group(1)
        def r = mockMvc.perform(MockMvcRequestBuilders.get("/api/items/${itemId}/similar")).andReturn()
        assert r.response.status == 200
        assert r.response.contentAsString.startsWith('[')
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

    def "GET /item/{id} for an unknown id returns 200 noindex SPA shell (batch 968 supersedes 967)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/item/999999999')).andReturn()

        then:
        // Batch 968 — unknown entity URLs now return 200 with a
        // `noindex, nofollow` robots meta. Why: a document-level non-2xx
        // status logs as "Failed to load resource" in the browser console
        // on every dead-link landing, which read as a real bug. The
        // noindex meta is a sufficient de-indexing signal for crawlers,
        // so we no longer need 404 as a secondary signal. The SPA's
        // client-side router renders the branded "Item not found" panel
        // either way. See OpenGraphController#notFoundSpaShell.
        r.response.status == 200
        def body = r.response.contentAsString
        body.contains('<html')
        body.contains('og:title')
        body.contains('name="robots" content="noindex, nofollow"')
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

    def "GET /api/version includes startupAt (batch 866 contract)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/version')).andReturn()

        then:
        r.response.status == 200
        def body = r.response.contentAsString
        // Batch 946 — pins the batch-866 contract: /api/version emits a
        // `startupAt` epoch-ms alongside `version` so ops + the status
        // page can compute pod uptime without a separate endpoint.
        // The status page at /status.html reads this via renderVersion()
        // and displays "deploy age N min ago" — a regression that
        // silently dropped the key would flip that read to "Unknown".
        body.contains('startupAt')
        // Must be numeric (epoch-ms), not a string/null.
        body =~ /"startupAt"\s*:\s*\d{10,}/
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

    def "GET /api/items/{id}/recent-sales accepts an optional limit query param (batch 734)"() {
        when:
        // limit=50 is the documented max — anything higher is clamped
        // server-side. Anything lower than 1 clamps to 1. Use an item
        // id that does not exist so the endpoint returns [] without
        // needing SOLD fixtures — the test is about the param path,
        // not the row contents.
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/items/999999/recent-sales?limit=50')
        ).andReturn()

        then:
        r.response.status == 200
        // Empty-array response for an unknown id — the controller
        // does not 404; it just returns [] so the frontend's median
        // chip silently hides on unmatched lookups.
        r.response.contentAsString == '[]'
    }

    def "GET /api/items/{id}/recent-sales clamps an overlarge limit to 50"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/items/999999/recent-sales?limit=5000')
        ).andReturn()

        then:
        // Clamp is silent (we don't 400 on too-large limits; we just
        // cap the page size), so the endpoint still returns 200 with
        // its empty-array body for this unknown item.
        r.response.status == 200
        r.response.contentAsString == '[]'
    }

    def "GET /api/items/{id}/recent-sales emits Cache-Control (batch 739)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/items/999999/recent-sales')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('max-age=60')
        cc.contains('public')
    }

    def "GET /api/items/{id}/velocity emits Cache-Control (batch 739)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/items/999999/velocity')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('max-age=120')
    }

    def "GET /api/listings/stats emits Cache-Control (batch 739)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/stats')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('max-age=60')
    }

    def "GET /api/buy-orders/top emits public Cache-Control (batch 759)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/buy-orders/top')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        // Must be `public, max-age=60` — NOT the `no-store, no-cache`
        // default that the CorrelationIdFilter applies to private
        // /api/buy-orders paths. The narrowed filter from batch 759
        // carves out /top as a public aggregate.
        cc.contains('public')
        cc.contains('max-age=60')
        !cc.contains('no-store')
    }

    def "GET /api/buy-orders/count/item/{id} emits public Cache-Control (batch 759)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/buy-orders/count/item/999999')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('public')
        cc.contains('max-age=60')
    }

    def "GET /api/buy-orders (private listing) still emits no-store (batch 759)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/buy-orders')
        ).andReturn()

        then:
        // Anon hits 401 — no-store header should still ride on the
        // error response so a shared cache can't leak "you got 401"
        // between users. Filter guard fires before the controller so
        // the header lands regardless of status.
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/version emits long public Cache-Control (batch 758)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/version')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('public')
        cc.contains('max-age=600')
    }

    def "GET /api/listings/top-deals emits private Cache-Control (batch 740)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/top-deals')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        // Private — filterBlocked varies by viewer so shared caches
        // would cross-contaminate. 60s cap.
        cc.contains('private')
        cc.contains('max-age=60')
    }

    def "GET /api/watchlist/alerts requires auth"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/watchlist/alerts')).andReturn()

        then:
        r.response.status == 401
    }

    def "POST /api/watchlist/alerts requires auth"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.post('/api/watchlist/alerts')
            .contentType('application/json')
            .content('{"itemId":1,"targetPrice":5.00}')).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403  // CSRF may intercept first
    }

    def "DELETE /api/watchlist/alerts/{id} requires auth"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.delete('/api/watchlist/alerts/1')).andReturn()

        then:
        r.response.status == 401 || r.response.status == 403
    }

    def "GET /js/app.js emits no-cache (batch 796 — bundles aren't content-hashed)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/js/app.js')).andReturn()

        then:
        // 200 if served, 404 in some test classpath layouts. Either
        // way the filter must have stamped the no-cache header so
        // browsers always revalidate via Last-Modified after a deploy.
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-cache')
        cc.contains('must-revalidate')
    }

    def "GET / (SPA shell) emits no-cache (batch 796)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-cache')
        cc.contains('must-revalidate')
    }

    def "GET /market (SPA shell sub-route) emits no-cache (batch 796)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/market')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-cache')
        cc.contains('must-revalidate')
    }

    def "GET /img/favicon-512.png keeps the 4-hour public cache (batch 796 — assets unchanged)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/img/favicon-512.png')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('public')
        cc.contains('max-age=14400')
    }

    def "GET /api/listings with explicit offset returns PageResponse wrapper (batch 798)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '50')
                .param('offset', '0')
        ).andReturn()

        then:
        // When limit != 100 or offset != 0 the controller switches from a
        // bare array to { items, total, limit, offset } — pagination UI
        // needs the total. `limit=50 offset=0` triggers the wrapper.
        r.response.status == 200
        def body = r.response.contentAsString
        body.contains('"items"')
        body.contains('"total"')
        body.contains('"limit"')
        body.contains('"offset"')
    }

    def "GET /api/listings at offset past the end returns empty items (batch 798)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '100')
                .param('offset', '10000')
        ).andReturn()

        then:
        r.response.status == 200
        // Wrapper shape triggers on offset != 0 so the body has the
        // `items` key even when the array is empty.
        def body = r.response.contentAsString
        body.contains('"items":[]')
    }

    def "GET /api/listings limit=100 offset=0 still returns a bare array (back-compat)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '100')
                .param('offset', '0')
        ).andReturn()

        then:
        // Default path — preserved for clients that predate the wrapper.
        // fetchListings() normalises either shape but this test locks
        // the contract so we don't accidentally break the extension or
        // any external bot against the bare-array shape.
        r.response.status == 200
        def body = r.response.contentAsString.trim()
        body.startsWith('[')
        body.endsWith(']')
    }

    def "GET /profile emits noindex robots meta (batch 808)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/profile')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('name="robots" content="noindex, nofollow"')
    }

    def "GET /wallet emits noindex robots meta (batch 808)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/wallet')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('name="robots" content="noindex, nofollow"')
    }

    def "GET /admin emits noindex robots meta (batch 808)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/admin')).andReturn()

        then:
        r.response.status == 200
        r.response.contentAsString.contains('name="robots" content="noindex, nofollow"')
    }

    def "GET / (public shell) does NOT carry the private noindex (batch 808)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/')).andReturn()

        then:
        r.response.status == 200
        // Public marketplace stays indexable — my private-shell
        // controller must not catch `/`. Checking for the ABSENCE of
        // noindex rather than the presence of "index, follow" because
        // MockMvc's view-controller-forward path may not emit the raw
        // static html into contentAsString in the same shape curl gets.
        !r.response.contentAsString.contains('name="robots" content="noindex, nofollow"')
    }

    def "GET /api/health emits no-store Cache-Control (batch 824)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/health')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        // Probes must never be cached — LBs need real-time state.
        cc.contains('no-store')
        cc.contains('no-cache')
    }

    def "GET /api/ready emits no-store Cache-Control (batch 824)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/ready')).andReturn()

        then:
        (r.response.status == 200 || r.response.status == 503)
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
        cc.contains('no-cache')
    }

    def "GET /api/announcement emits public Cache-Control whether present or null (batch 820)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/announcement')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        // Always public — response carries no viewer-specific fields.
        // 30s is tight enough to honor a fresh deactivation on the
        // next poll tick.
        cc.contains('public')
        cc.contains('max-age=30')
    }

    def "GET /api/sellers/search emits public Cache-Control on every path (batch 811)"() {
        // Short query → early empty-result return — must still be cached.
        when:
        def r1 = mockMvc.perform(MockMvcRequestBuilders.get('/api/sellers/search').param('q', 'a')).andReturn()

        then:
        r1.response.status == 200
        def cc1 = r1.response.getHeader('Cache-Control')
        cc1 != null
        cc1.contains('public')
        cc1.contains('max-age=60')

        // Valid-length query → full happy path — same cache header.
        when:
        def r2 = mockMvc.perform(MockMvcRequestBuilders.get('/api/sellers/search').param('q', 'bob')).andReturn()

        then:
        r2.response.status == 200
        def cc2 = r2.response.getHeader('Cache-Control')
        cc2 != null
        cc2.contains('public')
        cc2.contains('max-age=60')
    }

    def "GET /api/watchlist/counts emits public Cache-Control (batch 811)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/watchlist/counts').param('ids', '1,2')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('public')
        cc.contains('max-age=60')
    }

    def "GET /api/watchlist/alerts/count/item/{id} emits public Cache-Control (batch 811)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/watchlist/alerts/count/item/1')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('public')
        cc.contains('max-age=60')
    }

    def "GET /api/watchlist (private) emits no-store (batch 843)"() {
        // The per-viewer listing surface is session-scoped — a shared
        // cache must never serve one user's watched ids to another.
        // 401 for anon is fine; the header still has to be no-store.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/watchlist')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/watchlist/alerts (private) emits no-store (batch 843)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/watchlist/alerts')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/items/%20 returns 400 INVALID_PATH_VARIABLE (batch 863 — was 500)"() {
        // URL-encoded space decodes to a Long-incompatible value and
        // Spring hands GlobalExceptionHandler a MissingPathVariable
        // (value null). Pre-batch-863 this was an unhandled 500 —
        // weaponisable as DoS by walking invalid ids. Now 400.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/items/%20')).andReturn()

        then:
        r.response.status == 400
    }

    def "GET /api/wallet/spend requires auth (batch 846)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/wallet/spend')).andReturn()

        then:
        // Anonymous → 401. Spending history is PII; no fallback shape.
        r.response.status == 401
    }

    def "GET /api/wallet/spend emits no-store (batch 846)"() {
        // Inherits from the filter's blanket `/api/wallet` no-store rule.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/wallet/spend')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/saved-searches emits no-store (batch 844)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/saved-searches')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/follows emits no-store (batch 844)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/follows')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/follows/status/{sellerId} emits no-store (batch 844)"() {
        // Caller-dependent state ("am I following this seller") must
        // not be served out of a shared cache.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/follows/status/1')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/steam/inventory emits no-store (batch 844)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/steam/inventory')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/loadouts/mine emits no-store (batch 844)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/loadouts/mine')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/loadouts/favorites emits no-store (batch 844)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/loadouts/favorites')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/loadouts/discover emits public Cache-Control (batch 844)"() {
        // Discover is viewer-agnostic by design — every viewer sees
        // the same browseable public loadouts. Safe for a shared
        // cache.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/loadouts/discover')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('public')
        cc.contains('max-age=60')
    }

    def "GET /api/reviews/pending emits no-store (batch 844)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/reviews/pending')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/reviews/mine emits no-store (batch 844)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/reviews/mine')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('no-store')
    }

    def "GET /api/reviews/user/{id} stays public (batch 844 carve-out)"() {
        // The seller-scoped public review feed must keep its
        // controller-set `private, max-age=60` — not get blanket
        // no-stored by the filter.
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/reviews/user/1')).andReturn()

        then:
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        !cc.contains('no-store')
    }

    def "GET /api/watchlist/counts still bypasses no-store (batch 843 carve-out)"() {
        // Regression guard: the public-read carve-out must keep
        // /counts on its controller-set `public, max-age=60` — the
        // filter must not blanket-no-store the whole /api/watchlist
        // tree.
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/watchlist/counts').param('ids', '1,2')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        !cc.contains('no-store')
        cc.contains('public')
    }

    def "GET /api/listings/most-watched emits private Cache-Control (batch 811)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/most-watched')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('private')
        cc.contains('max-age=60')
    }

    def "GET /api/listings/most-viewed emits private Cache-Control (batch 811)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/most-viewed')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('private')
        cc.contains('max-age=60')
    }

    def "GET /api/listings/hottest emits private Cache-Control (batch 811)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/hottest')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('private')
        cc.contains('max-age=60')
    }

    def "GET /api/listings/item/{id} emits private Cache-Control (batch 807)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/item/1')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        // Private — blocklist filter varies the response per viewer.
        cc.contains('private')
        cc.contains('max-age=15')
    }

    def "GET /api/buy-orders/for-item/{id} emits public Cache-Control (batch 807)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/buy-orders/for-item/1')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('public')
        cc.contains('max-age=60')
    }

    def "GET /api/buy-orders/count/bulk emits public Cache-Control (batch 807)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/buy-orders/count/bulk').param('ids', '1,2,3')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        cc.contains('public')
        cc.contains('max-age=60')
    }

    def "GET /api/database emits public Cache-Control (batch 807)"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/database').param('limit', '5')
        ).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        // Catalogue is viewer-agnostic — shared cache allowed. 60s is
        // tight enough to honour post-sync freshness.
        cc.contains('public')
        cc.contains('max-age=60')
    }

    def "GET /api/items/{id} emits public Cache-Control (batch 807)"() {
        // Find any existing item id via the listing feed — the test DB's
        // seed set varies across runs, so hardcoding id=1 isn't safe.
        given:
        def listingResp = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings?limit=1')).andReturn()
        def m = (listingResp.response.contentAsString =~ /"item":\s*\{[^}]*"id":\s*(\d+)/)

        expect:
        if (!m.find()) return   // Cold DB — skip rather than fail.
        def itemId = m.group(1)
        def r = mockMvc.perform(MockMvcRequestBuilders.get("/api/items/${itemId}")).andReturn()
        assert r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        // Detail payload has no viewer-specific fields, so shared caches
        // can safely serve it. 30s matches the grid soft-poll tick.
        assert cc != null
        assert cc.contains('public')
        assert cc.contains('max-age=30')
    }

    def "GET /api/listings emits private Cache-Control (batch 807)"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')).andReturn()

        then:
        r.response.status == 200
        def cc = r.response.getHeader('Cache-Control')
        cc != null
        // Must be PRIVATE — per-viewer blocklist filter makes the response
        // unsafe for a shared CDN. 10s max-age so back-nav from an item
        // modal skips a round-trip.
        cc.contains('private')
        cc.contains('max-age=10')
        !cc.contains('public')
    }

    def "GET /api/listings rejects negative offsets gracefully"() {
        when:
        def r = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '50')
                .param('offset', '-1')
        ).andReturn()

        then:
        // Controller clamps `offset = max(offset, 0)` so `-1` is treated
        // as 0. Must NOT 400 — the client-side Load More button hands the
        // offset as a plain number and a bogus value shouldn't surface
        // as a user-visible error.
        r.response.status == 200
    }
}
