package com.sboxmarket

import com.sboxmarket.controller.LoadoutController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.dto.request.CreateLoadoutRequest
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.LoadoutSlot
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.LoadoutService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the loadout endpoints. The interesting pieces:
 *
 *   - `/discover` search param sanitisation: the `q` alias falls
 *     through when canonical `search` is blank; null-byte strip + 100
 *     char cap on the free-text query; 60s public CDN cache on
 *     response.
 *
 *   - `/{id}/generate` budget validation ladder: non-numeric →
 *     INVALID_BUDGET, zero/negative → INVALID_BUDGET, > $100,000 →
 *     BUDGET_TOO_HIGH. Each code maps to a distinct client-facing
 *     error message, so future refactors can't silently collapse
 *     them.
 *
 *   - `setSlot()` projection: response pinned to
 *     `{slot, itemId, itemName, snapshotPrice}` — `locked` is managed
 *     via the lock endpoint so shouldn't leak through setSlot.
 *
 *   - `get()` is anon-tolerant: anon viewer gets `null` uid passed
 *     through to the service (which decides visibility).
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class LoadoutControllerSpec extends Specification {

    LoadoutService      loadoutService      = Mock()
    SteamUserRepository steamUserRepository = Mock()

    @Subject
    LoadoutController controller = new LoadoutController(
        loadoutService     : loadoutService,
        steamUserRepository: steamUserRepository
    )

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

    // ── /discover ───────────────────────────────────────────────

    def "discover() defaults to empty search and returns public CDN-cached list"() {
        given:
        def rows = [new Loadout(id: 1L)]
        def decorated = [[id: 1L, previewItems: []]]
        1 * loadoutService.listPublic(null) >> rows
        1 * loadoutService.decorate(rows) >> decorated

        when:
        def resp = controller.discover(null, null)

        then:
        resp.body.is(decorated)
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('public')
        cc?.contains('max-age=60')
    }

    def "discover() uses `q` alias when canonical `search` is blank"() {
        given:
        1 * loadoutService.listPublic('fallback') >> []

        when:
        controller.discover('', 'fallback')

        then:
        true
    }

    def "discover() prefers canonical `search` over `q` when both supplied"() {
        given:
        1 * loadoutService.listPublic('primary') >> []

        when:
        controller.discover('primary', 'alias-value')

        then:
        true
    }

    def "discover() strips null bytes from search"() {
        given:
        1 * loadoutService.listPublic('hat') >> []

        when: "attacker sends embedded \\u0000"
        controller.discover('h\u0000at', null)

        then:
        true
    }

    def "discover() caps search to 100 chars"() {
        given:
        String capturedSearch = null
        1 * loadoutService.listPublic(_) >> { args ->
            capturedSearch = args[0]
            []
        }

        when:
        controller.discover('x' * 10_000, null)

        then:
        capturedSearch.length() == 100
    }

    // ── /mine + /favorites ─────────────────────────────────────

    def "mine() requires sign-in"() {
        given: anonSession()
        when:  controller.mine(req)
        then:  thrown(UnauthorizedException)
        0 * loadoutService.listMine(_)
    }

    def "favorites() requires sign-in (favorite-set is per-user PII)"() {
        given: anonSession()
        when:  controller.favorites(req)
        then:  thrown(UnauthorizedException)
        0 * loadoutService.listFavorites(_)
    }

    def "mine() returns the service's loadouts"() {
        given:
        def rows = [new Loadout(id: 1L)]
        def decorated = [[id: 1L, previewItems: []]]
        authedSession(100L)
        1 * loadoutService.listMine(100L) >> rows
        1 * loadoutService.decorate(rows) >> decorated

        when:
        def resp = controller.mine(req)

        then:
        resp.body.is(decorated)
    }

    def "favorites() returns the service's list"() {
        given:
        def rows = [new Loadout(id: 1L)]
        def decorated = [[id: 1L, previewItems: []]]
        authedSession(100L)
        1 * loadoutService.listFavorites(100L) >> rows
        1 * loadoutService.decorate(rows) >> decorated

        when:
        def resp = controller.favorites(req)

        then:
        resp.body.is(decorated)
    }

    // ── /{id} get ───────────────────────────────────────────────

    def "get() passes viewer:null for anon so the service decides visibility"() {
        given:
        def payload = [id: 42L, slots: []]
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * loadoutService.getWithSlots(42L, null) >> payload

        when:
        def resp = controller.get(42L, req)

        then: 'no 401 — public loadout is reachable anon'
        resp.body.is(payload)
    }

    def "get() passes the signed-in viewer's uid to the service"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * loadoutService.getWithSlots(42L, 100L) >> [id: 42L]

        when:
        controller.get(42L, req)

        then:
        true
    }

    def "get() does NOT set its own Cache-Control — CorrelationIdFilter owns it"() {
        // The /{id} surface is viewer-dependent (private loadouts are
        // owner-filtered), so CorrelationIdFilter already emits
        // `no-store, ... private` for it. The controller must NOT also
        // set Cache-Control via ResponseEntity.header(): those APPEND
        // rather than replace, which would emit two conflicting values.
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * loadoutService.getWithSlots(42L, null) >> [id: 42L]

        when:
        def resp = controller.get(42L, req)

        then: 'no Cache-Control header escapes the controller'
        resp.headers.getFirst('Cache-Control') == null
    }

    // ── /create ────────────────────────────────────────────────

    def "create() requires sign-in"() {
        given: anonSession()
        def body = new CreateLoadoutRequest(name: 'x', description: 'y', visibility: 'PUBLIC')

        when:
        controller.create(body, req)

        then:
        thrown(UnauthorizedException)
        0 * loadoutService.create(_, _, _, _, _)
    }

    def "create() raises Unauthorized when the session uid has no SteamUser row"() {
        given:
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.empty()

        when:
        controller.create(new CreateLoadoutRequest(name: 'x'), req)

        then:
        thrown(UnauthorizedException)
        0 * loadoutService.create(_, _, _, _, _)
    }

    def "create() falls back to 'Player' when displayName is null"() {
        given:
        def user = new SteamUser(id: 100L, displayName: null)
        def body = new CreateLoadoutRequest(name: 'my load', description: 'd', visibility: 'PUBLIC')
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * loadoutService.create(100L, 'Player', 'my load', 'd', 'PUBLIC') >> new Loadout()

        when:
        controller.create(body, req)

        then:
        true
    }

    // ── setSlot / toggleLock ──────────────────────────────────

    def "setSlot() projects to {slot, itemId, itemName, snapshotPrice} — never leaks locked"() {
        given:
        def slot = new LoadoutSlot(slot: 'HEAD', itemId: 42L, itemName: 'Hat',
                                   snapshotPrice: new BigDecimal('9.50'), locked: true)
        authedSession(100L)
        1 * loadoutService.setSlot(100L, 1L, 'HEAD', 42L) >> slot

        when:
        def resp = controller.setSlot(1L, 'HEAD', [itemId: 42L], req)

        then:
        resp.body == [slot: 'HEAD', itemId: 42L, itemName: 'Hat',
                      snapshotPrice: new BigDecimal('9.50')]
        !resp.body.containsKey('locked')
    }

    def "setSlot() passes null itemId through when body omits it (clears the slot)"() {
        given:
        def slot = new LoadoutSlot(slot: 'HEAD', itemId: null, itemName: null,
                                   snapshotPrice: BigDecimal.ZERO)
        authedSession(100L)
        1 * loadoutService.setSlot(100L, 1L, 'HEAD', null) >> slot

        when:
        def resp = controller.setSlot(1L, 'HEAD', [:], req)

        then:
        resp.body.itemId == null
    }

    def "setSlot() requires sign-in"() {
        given: anonSession()
        when:  controller.setSlot(1L, 'HEAD', [itemId: 42L], req)
        then:  thrown(UnauthorizedException)
        0 * loadoutService.setSlot(_, _, _, _)
    }

    def "setSlot() rejects a non-numeric itemId with a clean 400 (not an opaque 500)"() {
        given:
        authedSession(100L)

        when:
        controller.setSlot(1L, 'HEAD', [itemId: 'abc'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_ITEM_ID'
        0 * loadoutService.setSlot(_, _, _, _)
    }

    def "setSlot() rejects a decimal itemId with a clean 400 (whole-number contract)"() {
        // The controller comment promises a decimal itemId yields a clean
        // 400 INVALID_ITEM_ID, not an opaque 500 from Long.valueOf.
        given:
        authedSession(100L)

        when:
        controller.setSlot(1L, 'HEAD', [itemId: '42.5'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_ITEM_ID'
        0 * loadoutService.setSlot(_, _, _, _)
    }

    def "setSlot() rejects a boolean itemId with a clean 400"() {
        // A JSON boolean for itemId must not 500 — it stringifies to
        // "true"/"false" which Long.valueOf rejects; map it to 400.
        given:
        authedSession(100L)

        when:
        controller.setSlot(1L, 'HEAD', [itemId: true], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_ITEM_ID'
        0 * loadoutService.setSlot(_, _, _, _)
    }

    def "setSlot() rejects an out-of-Long-range itemId with a clean 400 (not a 500)"() {
        // A digit string that overflows Long must surface as a structured
        // 400, not bubble a raw NumberFormatException out as a 500.
        given:
        authedSession(100L)

        when:
        controller.setSlot(1L, 'HEAD', [itemId: '99999999999999999999999'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_ITEM_ID'
        0 * loadoutService.setSlot(_, _, _, _)
    }

    def "setSlot() checks auth BEFORE parsing the body — anon + bad itemId is 401, not 400"() {
        // The controller deliberately calls requireUser() before touching
        // the body so an unauthenticated caller never learns the itemId
        // parse rules (and does no work). A malformed itemId on an anon
        // session must therefore raise Unauthorized, not BadRequest.
        given: anonSession()

        when:
        controller.setSlot(1L, 'HEAD', [itemId: 'not-a-number'], req)

        then:
        thrown(UnauthorizedException)
        0 * loadoutService.setSlot(_, _, _, _)
    }

    def "setSlot() coerces a numeric-string itemId"() {
        given:
        def slot = new LoadoutSlot(slot: 'HEAD', itemId: 42L, itemName: 'Hat',
                                   snapshotPrice: BigDecimal.ZERO)
        authedSession(100L)
        1 * loadoutService.setSlot(100L, 1L, 'HEAD', 42L) >> slot

        when:
        def resp = controller.setSlot(1L, 'HEAD', [itemId: '42'], req)

        then:
        resp.body.itemId == 42L
    }

    def "setSlot() tolerates a null body (clears the slot)"() {
        given:
        def slot = new LoadoutSlot(slot: 'HEAD', itemId: null, snapshotPrice: BigDecimal.ZERO)
        authedSession(100L)
        1 * loadoutService.setSlot(100L, 1L, 'HEAD', null) >> slot

        when:
        def resp = controller.setSlot(1L, 'HEAD', null, req)

        then:
        resp.body.itemId == null
    }

    def "toggleLock() returns {slot, locked}"() {
        given:
        def slot = new LoadoutSlot(slot: 'HEAD', locked: true)
        authedSession(100L)
        1 * loadoutService.toggleLock(100L, 1L, 'HEAD') >> slot

        when:
        def resp = controller.toggleLock(1L, 'HEAD', req)

        then:
        resp.body == [slot: 'HEAD', locked: true]
    }

    // ── autoGenerate budget ladder ─────────────────────────────

    def "autoGenerate() requires sign-in"() {
        given: anonSession()
        when:  controller.autoGenerate(1L, null, req)
        then:  thrown(UnauthorizedException)
        0 * loadoutService.autoGenerate(_, _, _)
    }

    def "autoGenerate() with null body passes null budget"() {
        given:
        authedSession(100L)
        1 * loadoutService.autoGenerate(100L, 1L, null) >> []

        when:
        controller.autoGenerate(1L, null, req)

        then:
        true
    }

    def "autoGenerate() rejects non-numeric budget with INVALID_BUDGET"() {
        // Budget validation fires BEFORE requireUser() — no session read expected.
        when:
        controller.autoGenerate(1L, [budget: 'abc'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_BUDGET'
        0 * req.session
        0 * loadoutService.autoGenerate(_, _, _)
    }

    def "autoGenerate() rejects zero budget with INVALID_BUDGET (must be positive)"() {
        when:
        controller.autoGenerate(1L, [budget: '0'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_BUDGET'
        e.message.contains('positive')
        0 * loadoutService.autoGenerate(_, _, _)
    }

    def "autoGenerate() rejects negative budget with INVALID_BUDGET"() {
        when:
        controller.autoGenerate(1L, [budget: '-10'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_BUDGET'
        0 * loadoutService.autoGenerate(_, _, _)
    }

    def 'autoGenerate() rejects budget above $100,000 with BUDGET_TOO_HIGH'() {
        when:
        controller.autoGenerate(1L, [budget: '100000.01'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'BUDGET_TOO_HIGH'
        0 * loadoutService.autoGenerate(_, _, _)
    }

    def 'autoGenerate() accepts $100,000 exactly (upper bound inclusive)'() {
        given:
        authedSession(100L)
        1 * loadoutService.autoGenerate(100L, 1L, new BigDecimal('100000')) >> []

        when:
        controller.autoGenerate(1L, [budget: '100000'], req)

        then:
        true
    }

    // ── update / clone / favorite / delete ───────────────────

    def "update() passes the partial body fields through to the service"() {
        given:
        def saved = new Loadout(id: 1L, name: 'renamed')
        authedSession(100L)
        1 * loadoutService.update(100L, 1L, 'renamed', null, 'PUBLIC') >> saved

        when:
        def resp = controller.update(1L, [name: 'renamed', visibility: 'PUBLIC'], req)

        then:
        resp.body.is(saved)
    }

    def "update() requires sign-in"() {
        given: anonSession()
        when:  controller.update(1L, [name: 'x'], req)
        then:  thrown(UnauthorizedException)
        0 * loadoutService.update(_, _, _, _, _)
    }

    def "clone() resolves displayName + delegates"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        def cloned = new Loadout(id: 99L)
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * loadoutService.clone(100L, 1L, 'alice') >> cloned

        when:
        def resp = controller.clone(1L, req)

        then:
        resp.body.is(cloned)
    }

    def "clone() raises Unauthorized when session uid has no SteamUser row"() {
        given:
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.empty()

        when:
        controller.clone(1L, req)

        then:
        thrown(UnauthorizedException)
        0 * loadoutService.clone(_, _, _)
    }

    def "favorite() forwards the service's toggle result"() {
        given:
        authedSession(100L)
        1 * loadoutService.toggleFavorite(100L, 1L) >> [favorited: true, count: 5]

        when:
        def resp = controller.favorite(1L, req)

        then:
        resp.body == [favorited: true, count: 5]
    }

    def "delete() returns {ok:true}"() {
        given:
        authedSession(100L)
        1 * loadoutService.delete(100L, 1L)

        when:
        def resp = controller.delete(1L, req)

        then:
        resp.body == [ok: true]
    }

    def "delete() requires sign-in"() {
        given: anonSession()
        when:  controller.delete(1L, req)
        then:  thrown(UnauthorizedException)
        0 * loadoutService.delete(_, _)
    }

    // ── get() not-found fallback (Boss QA cycle 4 B1) ──────────────

    def "get() falls back to the lowest-id PUBLIC loadout when the requested id 404s"() {
        given:
        def fallbackBody = [loadout: new Loadout(id: 2L), slots: []]
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        // Requested id is missing/private — service throws.
        1 * loadoutService.getWithSlots(999L, null) >> {
            throw new com.sboxmarket.exception.NotFoundException("Loadout", 999L)
        }
        // Fallback: lowest-id public loadout is id 2 (listing returns them unsorted).
        1 * loadoutService.listPublic(null) >> [new Loadout(id: 9L), new Loadout(id: 2L)]
        1 * loadoutService.getWithSlots(2L, null) >> fallbackBody

        when:
        def resp = controller.get(999L, req)

        then: "the fallback payload is returned, tagged with the originally-requested id"
        resp.body.loadout.id == 2L
        resp.body.redirectedFrom == 999L
    }

    def "get() returns the {notFound:true} sentinel when no public loadout exists to fall back to"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * loadoutService.getWithSlots(999L, null) >> {
            throw new com.sboxmarket.exception.NotFoundException("Loadout", 999L)
        }
        1 * loadoutService.listPublic(null) >> []

        when:
        def resp = controller.get(999L, req)

        then:
        resp.body == [notFound: true, id: 999L]
    }

    def "get() returns the sentinel when the fallback public loadout vanishes mid-request (race)"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * loadoutService.getWithSlots(999L, null) >> {
            throw new com.sboxmarket.exception.NotFoundException("Loadout", 999L)
        }
        1 * loadoutService.listPublic(null) >> [new Loadout(id: 2L)]
        // The public loadout was deleted between the listing and the fetch.
        1 * loadoutService.getWithSlots(2L, null) >> {
            throw new com.sboxmarket.exception.NotFoundException("Loadout", 2L)
        }

        when:
        def resp = controller.get(999L, req)

        then: "falls through to the branded not-found sentinel rather than 500ing"
        resp.body == [notFound: true, id: 999L]
    }

    // ── autoGenerate() budget currency-adornment stripping ─────────

    def "autoGenerate() strips currency adornments from the budget before parsing"() {
        given:
        authedSession(100L)
        1 * loadoutService.autoGenerate(100L, 1L, new BigDecimal('1200.00')) >> []
        1 * loadoutService.getWithSlots(1L, 100L) >> [id: 1L]

        when: 'budget arrives as a formatted currency string'
        controller.autoGenerate(1L, [budget: '$1,200.00'], req)

        then:
        true
    }

    def "autoGenerate() rejects a budget string with no digits at all"() {
        when:
        controller.autoGenerate(1L, [budget: '$'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_BUDGET'
        0 * loadoutService.autoGenerate(_, _, _)
    }

    def "autoGenerate() returns the decorated slots payload from getWithSlots"() {
        given:
        def decorated = [loadout: new Loadout(id: 1L), slots: [[slot: 'Hats']]]
        authedSession(100L)
        1 * loadoutService.autoGenerate(100L, 1L, new BigDecimal('50')) >> []
        1 * loadoutService.getWithSlots(1L, 100L) >> decorated

        when:
        def resp = controller.autoGenerate(1L, [budget: '50'], req)

        then: 'the response carries the enriched slot payload, not the raw autoGenerate result'
        resp.body.is(decorated)
    }

    // ── discover() blank-q handling ────────────────────────────────

    def "discover() ignores a blank `q` and keeps search null"() {
        given:
        1 * loadoutService.listPublic(null) >> []
        1 * loadoutService.decorate(_) >> []

        when:
        controller.discover(null, '   ')

        then:
        true
    }

    // ── update() with an empty body ────────────────────────────────

    def "update() tolerates an empty body — every field passes through as null"() {
        given:
        authedSession(100L)
        1 * loadoutService.update(100L, 1L, null, null, null) >> new Loadout(id: 1L)

        when:
        controller.update(1L, [:], req)

        then:
        true
    }
}
