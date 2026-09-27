package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.WatchlistAlertController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.service.WatchlistAlertService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the price-alert surface that powers the watchlist's
 * "notify me when this drops to $X" feature. Contracts this spec
 * pins down:
 *
 *   - Private writes (list / create / cancel / clear-fired) require
 *     a signed-in session; anon → 401.
 *   - `GET /count/item/{id}` is public demand-side social proof —
 *     returns `{itemId, watching}` ONLY (no watcher identities) with
 *     a 60s shared cache so CDN fan-out absorbs the item-modal
 *     open-storm on popular items.
 *   - `create()` requires BOTH itemId and targetPrice; each missing
 *     field carries its own stable BadRequestException code so the
 *     SPA can render a field-specific error. Non-numeric values
 *     bubble up as INVALID_PARAMETER rather than 500.
 *   - `cancel()` returns `{id, status: CANCELLED}` idempotently —
 *     the same envelope whether the alert existed or not (service
 *     handles the ownership check + 404).
 *   - `clear-fired` returns `{deleted: N}`.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class WatchlistAlertControllerSpec extends Specification {

    WatchlistAlertService service = Mock()

    @Subject
    WatchlistAlertController controller = new WatchlistAlertController(service: service)

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
        0 * service.listForUser(_)
    }

    def "list() returns the service's rows unchanged"() {
        given:
        def rows = [new WatchlistAlert(id: 1L), new WatchlistAlert(id: 2L)]
        authedSession(100L)
        1 * service.listForUser(100L) >> rows

        when:
        def resp = controller.list(req)

        then:
        resp.body.is(rows)
    }

    def "countForItem() is public and shape-pinned to {itemId, watching}"() {
        given:
        1 * service.countWatchersForItem(42L) >> 17

        when:
        def resp = controller.countForItem(42L)

        then: 'never touches session — public endpoint'
        0 * req.session

        and: 'exact envelope — no watcher identities leak'
        resp.body == [itemId: 42L, watching: 17]

        and: 'shared 60s cache for CDN fan-out on popular items'
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('public')
        cc?.contains('max-age=60')
    }

    def "countForItem() on a cold item returns {itemId, watching: 0}"() {
        given:
        1 * service.countWatchersForItem(999L) >> 0

        when:
        def resp = controller.countForItem(999L)

        then:
        resp.body == [itemId: 999L, watching: 0]
    }

    def "create() requires sign-in"() {
        given: anonSession()
        when:  controller.create([itemId: 1, targetPrice: 9], req)
        then:  thrown(UnauthorizedException)
        0 * service.upsertAlert(_, _, _)
    }

    def "create() rejects missing itemId with MISSING_ITEM"() {
        given: authedSession(100L)

        when:
        controller.create([targetPrice: 9], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'MISSING_ITEM'
        0 * service.upsertAlert(_, _, _)
    }

    def "create() rejects missing targetPrice with MISSING_TARGET"() {
        given: authedSession(100L)

        when:
        controller.create([itemId: 1L], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'MISSING_TARGET'
        0 * service.upsertAlert(_, _, _)
    }

    def "create() rejects a null body — both fields missing → MISSING_ITEM fires first"() {
        given: authedSession(100L)

        when:
        controller.create(null, req)

        then: 'MISSING_ITEM is checked first; we never reach MISSING_TARGET'
        def e = thrown(BadRequestException)
        e.code == 'MISSING_ITEM'
    }

    def "create() rejects non-numeric values with INVALID_PARAMETER (not a 500)"() {
        given: authedSession(100L)

        when:
        controller.create([itemId: 'abc', targetPrice: '1.00'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_PARAMETER'
        0 * service.upsertAlert(_, _, _)
    }

    /**
     * Regression — exception-handling fix.
     *
     * Previously the controller did:
     *   throw new BadRequestException('INVALID_PARAMETER', e.message)
     * which dropped the NumberFormatException cause AND echoed the JDK
     * exception text verbatim (e.g. `For input string: "<user-input>"`),
     * so an attacker could reflect arbitrary strings back through the
     * error body and ops lost the original parse frame in the log chain.
     *
     * Contract pinned by this test:
     *   (1) message is a fixed safe sentence — never contains the raw
     *       user-supplied payload, never starts with the JDK NFE prefix
     *   (2) the original NumberFormatException is preserved as the
     *       cause so Slf4j's `, ex` arg in GlobalExceptionHandler dumps
     *       the full chain for ops triage
     */
    def "create() — INVALID_PARAMETER preserves NFE cause and never leaks raw user input"() {
        given: authedSession(100L)

        when:
        controller.create([itemId: 'rm -rf / #pwn', targetPrice: '1.00'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_PARAMETER'

        and: 'safe message — no echo of the user-supplied token, no JDK NFE prefix'
        !e.message.contains('rm -rf / #pwn')
        !e.message.contains('For input string')

        and: 'cause chain preserved for server-side triage'
        e.cause instanceof NumberFormatException
    }

    def "create() coerces Stringified numbers (client sends JSON numbers as strings)"() {
        given:
        def alert = new WatchlistAlert(id: 9L, userId: 100L, itemId: 42L,
                                       targetPrice: new BigDecimal('9.99'))
        authedSession(100L)
        1 * service.upsertAlert(100L, 42L, new BigDecimal('9.99')) >> alert

        when:
        def resp = controller.create([itemId: '42', targetPrice: '9.99'], req)

        then:
        resp.body.is(alert)
    }

    def "cancel() returns {id, status:CANCELLED} envelope"() {
        given:
        authedSession(100L)
        1 * service.cancelAlert(100L, 9L)

        when:
        def resp = controller.cancel(9L, req)

        then:
        resp.body == [id: 9L, status: 'CANCELLED']
    }

    def "cancel() requires sign-in"() {
        given: anonSession()
        when:  controller.cancel(9L, req)
        then:  thrown(UnauthorizedException)
        0 * service.cancelAlert(_, _)
    }

    def "clearFired() returns {deleted: N}"() {
        given:
        authedSession(100L)
        1 * service.clearFired(100L) >> 4

        when:
        def resp = controller.clearFired(req)

        then:
        resp.body == [deleted: 4]
    }

    def "clearFired() requires sign-in"() {
        given: anonSession()
        when:  controller.clearFired(req)
        then:  thrown(UnauthorizedException)
        0 * service.clearFired(_)
    }

    // ── service-layer errors bubble through create() ──────────────

    def "create() lets a service BadRequestException (e.g. ALERT_LIMIT) propagate unchanged"() {
        given:
        authedSession(100L)
        1 * service.upsertAlert(100L, 42L, new BigDecimal('5')) >> {
            throw new BadRequestException('ALERT_LIMIT', 'Active alert limit reached')
        }

        when:
        controller.create([itemId: '42', targetPrice: '5'], req)

        then: 'controller does not swallow or remap the domain error'
        def e = thrown(BadRequestException)
        e.code == 'ALERT_LIMIT'
    }

    def "create() passes the resolved session user id, not the request body"() {
        given:
        def alert = new WatchlistAlert(id: 1L)
        authedSession(777L)
        // uid must come from the session (777), never from anything client-supplied.
        1 * service.upsertAlert(777L, 42L, new BigDecimal('9.99')) >> alert

        when:
        def resp = controller.create([itemId: '42', targetPrice: '9.99'], req)

        then:
        resp.body.is(alert)
    }

    def "cancel() scopes the delete to the session user id"() {
        given:
        authedSession(777L)
        // Ownership is enforced service-side using the SESSION uid.
        1 * service.cancelAlert(777L, 9L)

        when:
        def resp = controller.cancel(9L, req)

        then:
        resp.body == [id: 9L, status: 'CANCELLED']
    }

    // ── cancel() idempotency + anti-enumeration contract ──────────────

    def "cancel() of a non-existent alert is an idempotent no-op (NotFoundException swallowed)"() {
        given:
        authedSession(100L)
        // Service raises 404 for an unknown id — the controller must
        // collapse it to the standard envelope, not propagate a 404.
        1 * service.cancelAlert(100L, 12345L) >> { throw new NotFoundException('WatchlistAlert', 12345L) }

        when:
        def resp = controller.cancel(12345L, req)

        then: 'no exception escapes — same envelope as a real cancel'
        noExceptionThrown()
        resp.body == [id: 12345L, status: 'CANCELLED']
    }

    def "cancel() of another user's alert is a no-op — NOT_OWNER never leaks id existence"() {
        given:
        authedSession(100L)
        // Service rejects a foreign alert with BadRequestException NOT_OWNER.
        // The controller must treat it as a no-op so a caller cannot tell a
        // valid-but-foreign id (was 400) from a non-existent one (was 404).
        1 * service.cancelAlert(100L, 9L) >> {
            throw new BadRequestException('NOT_OWNER', 'Not your alert')
        }

        when:
        def resp = controller.cancel(9L, req)

        then: 'ownership rejection is swallowed into the success envelope'
        noExceptionThrown()
        resp.body == [id: 9L, status: 'CANCELLED']
    }

    def "cancel() still propagates a non-ownership BadRequestException from the service"() {
        given:
        authedSession(100L)
        // A genuine client error (any code other than NOT_OWNER) must NOT
        // be masked by the idempotency shim.
        1 * service.cancelAlert(100L, 9L) >> {
            throw new BadRequestException('INVALID_PARAMETER', 'bad alert id')
        }

        when:
        controller.cancel(9L, req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_PARAMETER'
    }

    def "cancel() requires sign-in before any service call — no swallow path runs for anon"() {
        given: anonSession()

        when:
        controller.cancel(9L, req)

        then: 'auth is checked first; the idempotency try-block is never entered'
        thrown(UnauthorizedException)
        0 * service.cancelAlert(_, _)
    }

    // ── create() trims whitespace-padded stringified numbers ──────────

    def "create() trims whitespace around stringified numbers before parsing"() {
        given:
        def alert = new WatchlistAlert(id: 5L, userId: 100L, itemId: 42L,
                                       targetPrice: new BigDecimal('9.99'))
        authedSession(100L)
        1 * service.upsertAlert(100L, 42L, new BigDecimal('9.99')) >> alert

        when:
        def resp = controller.create([itemId: ' 42 ', targetPrice: ' 9.99 '], req)

        then: 'padding is stripped — no spurious INVALID_PARAMETER'
        noExceptionThrown()
        resp.body.is(alert)
    }
}
