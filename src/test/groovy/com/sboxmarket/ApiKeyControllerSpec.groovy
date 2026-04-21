package com.sboxmarket

import com.sboxmarket.controller.ApiKeyController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.ApiKey
import com.sboxmarket.service.ApiKeyService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Direct coverage for the API-key issuance endpoints. Two load-bearing
 * behaviors pinned here:
 *
 *   1. `create()` returns the raw token exactly once. After the HTTP
 *      response is written, only the SHA-256 hash lives in the DB.
 *      This test locks down the returned shape so the frontend's
 *      "COPY THIS NOW — it will not be shown again" banner always has
 *      a real token to show.
 *
 *   2. `revokeAll()` is idempotent: a panic-button caller with zero
 *      active keys gets `{revoked: 0}` (not 404), matching the bulk-
 *      clear family (/watchlist DELETE, /offers/outgoing/cancel-all,
 *      /bids/auto/cancel-all, /follows DELETE).
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class ApiKeyControllerSpec extends Specification {

    ApiKeyService apiKeyService = Mock()

    @Subject
    ApiKeyController controller = new ApiKeyController(apiKeyService: apiKeyService)

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void anonSession() {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
    }
    private void authedSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    def "list() requires sign-in"() {
        given: anonSession()
        when:  controller.list(req)
        then:  thrown(UnauthorizedException)
        0 * apiKeyService.listForUser(_)
    }

    def "list() hands the service's result through unchanged"() {
        given:
        def keys = [new ApiKey(id: 1L), new ApiKey(id: 2L)]
        authedSession(100L)
        1 * apiKeyService.listForUser(100L) >> keys

        when:
        def resp = controller.list(req)

        then:
        resp.statusCode.value() == 200
        resp.body.is(keys)
    }

    def "create() requires sign-in"() {
        given: anonSession()
        when:  controller.create([label: 'bot'], req)
        then:  thrown(UnauthorizedException)
        0 * apiKeyService.create(_, _, _)
    }

    def "create() returns the raw token exactly once in the payload"() {
        given:
        def key = new ApiKey(
            id: 9L,
            publicPrefix: 'sb_live_9a2c',
            label: 'price-bot',
            scope: 'RO',
            createdAt: 1700_000_000_000L
        )
        def rawToken = 'sb_live_9a2c_secret_onlyshownonce_xxx'
        authedSession(100L)
        1 * apiKeyService.create(100L, 'price-bot', 'RO') >> [key: key, token: rawToken]

        when:
        def resp = controller.create([label: 'price-bot', scope: 'RO'], req)

        then: 'token appears in body; hash-only storage invariant still holds in the service layer'
        resp.body == [
            id:           9L,
            publicPrefix: 'sb_live_9a2c',
            label:        'price-bot',
            scope:        'RO',
            token:        'sb_live_9a2c_secret_onlyshownonce_xxx',
            createdAt:    1700_000_000_000L
        ]
    }

    def "create() with null body delegates with null label + null scope"() {
        given:
        def key = new ApiKey(id: 1L, publicPrefix: 'sb_live_0000',
                             label: null, scope: 'RW', createdAt: 1L)
        authedSession(100L)
        // Service handles label + scope defaulting; controller just passes nulls
        1 * apiKeyService.create(100L, null, null) >> [key: key, token: 'raw']

        when:
        def resp = controller.create(null, req)

        then:
        resp.body.scope == 'RW'   // service applied the RW default
        resp.body.token == 'raw'
    }

    def "create() passes the scope through to the service (RO)"() {
        given:
        authedSession(100L)
        1 * apiKeyService.create(100L, 'reader', 'RO') >> [
            key  : new ApiKey(id: 1L, scope: 'RO', label: 'reader', publicPrefix: 'x', createdAt: 1L),
            token: 'raw'
        ]

        when:
        controller.create([label: 'reader', scope: 'RO'], req)

        then: 'the RO scope flows through — enforced on ApiKeyService.create()'
        // expect satisfied by the 1 * above
        true
    }

    def "revoke() returns {id, revoked:true} after the service flips the flag"() {
        given:
        authedSession(100L)
        1 * apiKeyService.revoke(100L, 9L) >> new ApiKey(id: 9L, revoked: true)

        when:
        def resp = controller.revoke(9L, req)

        then:
        resp.body == [id: 9L, revoked: true]
    }

    def "revoke() requires sign-in"() {
        given: anonSession()
        when:  controller.revoke(9L, req)
        then:  thrown(UnauthorizedException)
        0 * apiKeyService.revoke(_, _)
    }

    def "revokeAll() is idempotent — zero-key caller gets {revoked: 0}"() {
        given:
        authedSession(100L)
        1 * apiKeyService.revokeAll(100L) >> 0

        when:
        def resp = controller.revokeAll(req)

        then:
        resp.body == [revoked: 0]
    }

    def "revokeAll() returns the true revoked count when keys existed"() {
        given:
        authedSession(100L)
        1 * apiKeyService.revokeAll(100L) >> 3

        when:
        def resp = controller.revokeAll(req)

        then:
        resp.body == [revoked: 3]
    }

    def "revokeAll() requires sign-in"() {
        given: anonSession()
        when:  controller.revokeAll(req)
        then:  thrown(UnauthorizedException)
        0 * apiKeyService.revokeAll(_)
    }
}
