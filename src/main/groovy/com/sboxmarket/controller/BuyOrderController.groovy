package com.sboxmarket.controller

import com.sboxmarket.dto.request.CreateBuyOrderRequest
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.BuyOrderService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** Standing buy orders — buyer-side reverse listings. */
@RestController
@RequestMapping("/api/buy-orders")
@Slf4j
class BuyOrderController {

    @Autowired BuyOrderService buyOrderService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired com.sboxmarket.repository.ItemRepository itemRepository

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<List<Map>> mine(HttpServletRequest req) {
        def uid = requireUser(req)
        def rows = buyOrderService.listForBuyer(uid)
        // Enrich each row with the item's current floor price + a
        // per-row "gap" (floor − maxPrice). Negative gap means the
        // order is already above floor and should have matched; zero-
        // to-small-positive gap means the order is one drop away from
        // a match. Null floor when the order spec isn't item-specific
        // (buy orders pinned to category+rarity carry no itemId).
        def itemIds = rows*.itemId.findAll { it != null }.unique()
        def byId = [:]
        if (!itemIds.isEmpty()) {
            itemRepository.findAllById(itemIds).each { byId[it.id] = it }
        }
        def out = rows.collect { o ->
            def floor = o.itemId != null ? byId[o.itemId]?.lowestPrice : null
            def gap = (floor != null && o.maxPrice != null) ? (floor - o.maxPrice) : null
            // Queue position — 1 + number of ACTIVE orders on the same
            // item that would match first under the engine's
            // `maxPrice DESC, createdAt ASC` ordering. Only meaningful
            // for ACTIVE rows pinned to a specific item; basket
            // (category/rarity-only) rows leave it null so the UI
            // hides the chip cleanly.
            Long queuePosition = null
            if (o.status == 'ACTIVE' && o.itemId != null && o.maxPrice != null) {
                long ahead = buyOrderService.countAheadInQueue(
                    o.itemId, o.maxPrice, o.createdAt ?: 0L)
                queuePosition = ahead + 1L
            }
            [
                id: o.id, itemId: o.itemId, itemName: o.itemName,
                category: o.category, rarity: o.rarity,
                maxPrice: o.maxPrice, quantity: o.quantity,
                originalQuantity: o.originalQuantity, status: o.status,
                createdAt: o.createdAt, updatedAt: o.updatedAt,
                currentFloor:  floor,
                floorGap:      gap,
                queuePosition: queuePosition
            ]
        }
        ResponseEntity.ok(out)
    }

    /** Public demand-count for an item — returns the count of ACTIVE buy
     *  orders pinned to this item id. Powers the "N buyers want this"
     *  chip on the item detail modal. Counter-party identities are NOT
     *  surfaced — this is an aggregate-only signal. */
    @GetMapping("/count/item/{id}")
    ResponseEntity<Map> countForItem(@PathVariable Long id) {
        def best = buyOrderService.bestBidForItem(id)
        ResponseEntity.ok([
            itemId:  id,
            count:   buyOrderService.countActiveForItem(id),
            bestBid: (best != null && best > BigDecimal.ZERO) ? best : null
        ])
    }

    /**
     * Projected queue position for a hypothetical buy order placed NOW.
     * Drives the "at $45 you'd be #3 in queue" preview on the create
     * form — lets buyers calibrate their max price before committing.
     * Uses the live timestamp as createdAt so ties resolve in favour of
     * every existing order, matching the real behaviour when the form
     * is actually submitted.
     */
    @GetMapping("/projected-position")
    ResponseEntity<Map> projectedPosition(
            @RequestParam Long itemId,
            @RequestParam BigDecimal maxPrice) {
        if (itemId == null || maxPrice == null || maxPrice <= BigDecimal.ZERO) {
            return ResponseEntity.ok([itemId: itemId, maxPrice: maxPrice, position: null])
        }
        long ahead = buyOrderService.countAheadInQueue(itemId, maxPrice, System.currentTimeMillis())
        ResponseEntity.ok([
            itemId:   itemId,
            maxPrice: maxPrice,
            position: ahead + 1L
        ])
    }

    @PostMapping
    ResponseEntity<BuyOrder> create(@Valid @RequestBody CreateBuyOrderRequest body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        ResponseEntity.ok(buyOrderService.create(
            uid, user.displayName ?: "Player",
            body.itemId, body.category, body.rarity,
            body.maxPrice, body.quantity ?: 1
        ))
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Map> cancel(@PathVariable Long id, HttpServletRequest req) {
        def order = buyOrderService.cancel(requireUser(req), id)
        ResponseEntity.ok([id: order.id, status: order.status])
    }

    /** Raise / lower the max price or shrink the remaining quantity on
     *  an ACTIVE buy order. Both fields optional; at least one should
     *  be present. See BuyOrderService.update for the validation ladder. */
    @PutMapping("/{id}")
    ResponseEntity<BuyOrder> update(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        BigDecimal newMax = null
        if (body?.maxPrice != null) {
            try { newMax = new BigDecimal(body.maxPrice.toString()) }
            catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE",
                    "maxPrice must be a valid number")
            }
        }
        Integer newQty = null
        if (body?.quantity != null) {
            try { newQty = Integer.parseInt(body.quantity.toString()) }
            catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_QUANTITY",
                    "quantity must be a whole number")
            }
        }
        ResponseEntity.ok(buyOrderService.update(uid, id, newMax, newQty))
    }

    /** CSV export of every buy order the caller has ever placed — the
     *  full history across ACTIVE / FILLED / CANCELLED. Mirrors the
     *  /api/wallet/transactions.csv + /api/listings/my-stall/sold.csv
     *  pattern (inline CSV-escape, attachment disposition). Row order
     *  matches the Profile tab. */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    ResponseEntity<String> exportCsv(HttpServletRequest req) {
        def uid = requireUser(req)
        def rows = buyOrderService.listForBuyer(uid)
        def esc = { String v ->
            if (v == null) return ''
            v.contains(',') || v.contains('"') || v.contains('\n')
                ? '"' + v.replace('"', '""') + '"'
                : v
        }
        def sb = new StringBuilder()
        sb.append("order_id,item_id,item_name,category,rarity,max_price,quantity,original_quantity,status,created_at,updated_at\n")
        rows.each { o ->
            sb.append(o.id).append(',')
              .append(o.itemId ?: '').append(',')
              .append(esc(o.itemName ?: '')).append(',')
              .append(esc(o.category ?: '')).append(',')
              .append(esc(o.rarity ?: '')).append(',')
              .append(o.maxPrice?.toPlainString() ?: '').append(',')
              .append(o.quantity ?: 0).append(',')
              .append(o.originalQuantity ?: 0).append(',')
              .append(esc(o.status ?: '')).append(',')
              .append(o.createdAt ?: '').append(',')
              .append(o.updatedAt ?: '')
              .append('\n')
        }
        ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"buy-orders.csv\"")
            .body(sb.toString())
    }
}
