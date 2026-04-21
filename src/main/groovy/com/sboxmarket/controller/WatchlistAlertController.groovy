package com.sboxmarket.controller

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
            throw new com.sboxmarket.exception.BadRequestException('MISSING_ITEM', 'itemId is required')
        }
        if (body?.targetPrice == null) {
            throw new com.sboxmarket.exception.BadRequestException('MISSING_TARGET', 'targetPrice is required')
        }
        Long itemId
        BigDecimal target
        try {
            itemId = Long.parseLong(body.itemId.toString())
            target = new BigDecimal(body.targetPrice.toString())
        } catch (NumberFormatException e) {
            throw new com.sboxmarket.exception.BadRequestException('INVALID_PARAMETER', e.message)
        }
        ResponseEntity.ok(service.upsertAlert(uid, itemId, target))
    }

    @DeleteMapping('/{id}')
    ResponseEntity<Map> cancel(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireUser(req)
        service.cancelAlert(uid, id)
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
