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
        long total = buyOrderService.countForBuyer(uid)
        ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .body(out)
    }

    /** Top-N active buy orders by max-price — homepage "Top buy orders"
     *  rail (batch 369). Public: no auth required. Returns item name +
     *  image so the UI can render without a second round-trip. Buyer
     *  identity is NOT surfaced (aggregate demand signal only). Clamped
     *  to [1, 20] rows. */
    @GetMapping("/top")
    ResponseEntity<List<Map>> topActive(@RequestParam(required = false) Integer limit) {
        // Explicit null-check, not Elvis — `?limit=0` is a legitimate
        // "return zero rows" request; `?: 8` treats 0 as falsy and
        // silently substitutes 8. Same Elvis-on-zero bug class as
        // 75678e1 / 0d15de2 / ccfe0b5 / 4e1a0d4 / 8224a9b.
        int lim = Math.min(Math.max(limit != null ? limit : 8, 1), 20)
        def rows = buyOrderService.listTopActive(lim)
        // Batch 759 — 60s public cache. Top-of-book shifts slowly
        // (needs a new higher-maxPrice buy order or a cancellation),
        // and the homepage rail loads this on every visit. Overrides
        // the implicit no-cache/private default so shared CDN caches
        // can serve the rail too.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(rows)
    }

    /** Public demand-count for an item — returns the count of ACTIVE buy
     *  orders pinned to this item id. Powers the "N buyers want this"
     *  chip on the item detail modal. Counter-party identities are NOT
     *  surfaced — this is an aggregate-only signal. */
    @GetMapping("/count/item/{id}")
    ResponseEntity<Map> countForItem(@PathVariable Long id) {
        def best = buyOrderService.bestBidForItem(id)
        // Batch 759 — 60s public cache (mirrors /top above). Demand
        // chip on ItemModal reads this once per modal open.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body([
                itemId:  id,
                count:   buyOrderService.countActiveForItem(id),
                bestBid: (best != null && best > BigDecimal.ZERO) ? best : null
            ])
    }

    /**
     * Batch 639 — top-N ACTIVE buy orders for an item, sorted by
     * `maxPrice DESC, createdAt ASC` (matches the auto-match engine's
     * fill order). Powers the CSFloat-style Buy Orders table on item
     * detail. Aggregate signal only: no buyer handle, no avatar.
     * Clamped to [1, 20] rows.
     */
    @GetMapping("/for-item/{id}")
    ResponseEntity<List<Map>> forItem(@PathVariable Long id,
                                      @RequestParam(required = false) Integer limit) {
        int lim = Math.min(Math.max(limit ?: 10, 1), 20)
        // Batch 807 — public cache. Aggregate queue data, no viewer-
        // specific fields. 60s matches the sibling /top + /count/item/*
        // endpoints so the whole buy-order read-aggregate surface caches
        // uniformly at the edge.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(buyOrderService.listActiveForItem(id, lim))
    }

    /** Bulk demand lookup (batch 415). Accepts a comma-separated
     *  `ids=1,2,3` param (max 200) and returns a map keyed by itemId
     *  with {count, bestBid} for items that have at least one ACTIVE
     *  buy order. Items with no orders are simply absent from the
     *  response. Powers the MyStall "N want · best $X" per-row chip. */
    @GetMapping("/count/bulk")
    ResponseEntity<Map> countBulk(@RequestParam(required = false) String ids) {
        if (!ids) return ResponseEntity.ok([:])
        def parsed = []
        ids.split(',').each { raw ->
            try {
                def n = Long.parseLong(raw.trim())
                if (n > 0 && parsed.size() < 200) parsed << n
            } catch (NumberFormatException ignore) { /* skip bad tokens */ }
        }
        if (parsed.isEmpty()) return ResponseEntity.ok([:])
        def out = buyOrderService.bulkDemandByItemIds(parsed as List<Long>)
        // Batch 807 — same public/60s cache as the other aggregate reads.
        // The per-itemId {count, bestBid} tuple depends on buy orders
        // but not on the viewer; a shared cache is safe.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(out)
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

    /** Bulk-cancel every ACTIVE buy order the caller owns. Mirrors
     *  POST /api/bids/auto/cancel-all — lets buyers clear a noisy queue
     *  in one click rather than calling DELETE /{id} N times. Returns
     *  the count actually flipped. Idempotent: a zero-row caller gets
     *  `{cancelled:0}` rather than a 404. */
    @PostMapping("/cancel-all")
    ResponseEntity<Map> cancelAll(HttpServletRequest req) {
        int n = buyOrderService.cancelAllForUser(requireUser(req))
        ResponseEntity.ok([cancelled: n])
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
    ResponseEntity<String> exportCsv(@RequestParam(required = false) String status,
                                     HttpServletRequest req) {
        def uid = requireUser(req)
        def rows = buyOrderService.listForBuyer(uid)
        // Optional `?status=ACTIVE|FILLED|CANCELLED` mirrors the UI's
        // filter chip strip — a buyer who narrows the visible list and
        // clicks ⇣ CSV expects to download what they're looking at, not
        // a full dump across every status. Same pattern as the wallet
        // `?month=YYYY-MM` filter. Case-insensitive + falls back to
        // "no filter" for unknown / blank values so a stale bookmark
        // still returns a usable file.
        if (status) {
            def want = status.trim().toUpperCase()
            if (want in ['ACTIVE', 'FILLED', 'CANCELLED']) {
                rows = rows.findAll { (it.status ?: '').toUpperCase() == want }
            }
        }
        def esc = com.sboxmarket.util.CsvUtil.&safeCell   // batch 978
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
        // Cache-Control: CorrelationIdFilter already applies
        // `no-store, no-cache, must-revalidate, private` to every
        // /api/buy-orders/* path that isn't on the public-read whitelist
        // (/top, /count/*, /for-item/*, /projected-position). Setting
        // it again here would emit two conflicting Cache-Control headers
        // (ResponseEntity.header() APPENDS, doesn't replace). The
        // mystall CSVs DO need controller-level no-store because the
        // filter doesn't recognise the /api/listings/ prefix as private.
        ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"buy-orders.csv\"")
            .header("Content-Type", "text/csv; charset=utf-8")
            .body(sb.toString())
    }
}
