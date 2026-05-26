package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.service.WatchlistAlertService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/watchlist/alerts')
@Slf4j
class WatchlistAlertController {

    @Autowired WatchlistAlertService service

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<List<WatchlistAlert>> list(HttpServletRequest req) {
        ResponseEntity.ok(service.listForUser(requireUser(req)))
    }

    /** Public demand-side social proof for an item — returns the count
     *  of ACTIVE price alerts pinned to it. Drives the "N watching"
     *  chip on the item detail modal. Aggregate only; no watcher
     *  identities are exposed. */
    @GetMapping('/count/item/{id}')
    ResponseEntity<Map> countForItem(@PathVariable Long id) {
        // Batch 811 — public/60s cache. Viewer-agnostic aggregate.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body([
                itemId:   id,
                watching: service.countWatchersForItem(id)
            ])
    }

    @PostMapping
    ResponseEntity<WatchlistAlert> create(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        if (body?.itemId == null) {
            throw new BadRequestException('MISSING_ITEM', 'itemId is required')
        }
        if (body?.targetPrice == null) {
            throw new BadRequestException('MISSING_TARGET', 'targetPrice is required')
        }
        Long itemId
        BigDecimal target
        try {
            itemId = Long.parseLong(body.itemId.toString().trim())
            target = new BigDecimal(body.targetPrice.toString().trim())
        } catch (NumberFormatException e) {
            // Preserve the cause chain (Throwable arg) AND use a fixed
            // safe message — never echo the JDK NumberFormatException
            // text, which leaks the raw user-supplied string back to
            // the client (`For input string: "<anything-they-typed>"`).
            throw new BadRequestException(
                'INVALID_PARAMETER',
                'itemId and targetPrice must be valid numbers',
                e)
        }
        ResponseEntity.ok(service.upsertAlert(uid, itemId, target))
    }

    /** Cancel one of the caller's price alerts.
     *
     *  Idempotent by contract: returns the same `{id, status:CANCELLED}`
     *  envelope whether or not the alert existed. Ownership is enforced
     *  service-side against the SESSION uid — cancelling an id the caller
     *  does not own is a no-op, NOT a 404/400. Collapsing both the
     *  "no such alert" (NotFoundException) and the "someone else's alert"
     *  (BadRequestException NOT_OWNER) cases into the success envelope
     *  also closes an enumeration leak: an attacker can no longer tell a
     *  valid-but-foreign alert id (was 400 NOT_OWNER) from a non-existent
     *  one (was 404), and matches the house style of every other
     *  delete-by-id endpoint (saved-searches, watchlist). Auth and any
     *  other domain error still propagate. */
    @DeleteMapping('/{id}')
    ResponseEntity<Map> cancel(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireUser(req)
        try {
            service.cancelAlert(uid, id)
        } catch (NotFoundException ignored) {
            // No such alert — idempotent no-op.
        } catch (BadRequestException e) {
            // Foreign alert — treat ownership rejection as a no-op so the
            // caller cannot probe id existence. Any other BadRequest
            // (genuine client error) still surfaces.
            if (e.code != 'NOT_OWNER') throw e
        }
        ResponseEntity.ok([id: id, status: 'CANCELLED'])
    }

    /** Bulk-delete every FIRED alert for the caller. Useful after a
     *  batch of alerts fire — tidies the watchlist UI in one click. */
    @PostMapping('/clear-fired')
    ResponseEntity<Map> clearFired(HttpServletRequest req) {
        def uid = requireUser(req)
        def deleted = service.clearFired(uid)
        ResponseEntity.ok([deleted: deleted])
    }
}
