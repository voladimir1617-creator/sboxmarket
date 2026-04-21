package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.service.WatchlistService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Server-side watchlist (cross-device sync). Companion to the existing
 * /api/watchlist/alerts surface, which handles per-item price-drop
 * alerts. This controller handles the "starred set" itself.
 *
 * Anonymous callers see 401 — anonymous watchlists live in localStorage
 * only, by design. The frontend bridges the two on sign-in by POSTing
 * its localStorage ids to /api/watchlist/bulk.
 */
@RestController
@RequestMapping('/api/watchlist')
@Slf4j
class WatchlistController {

    @Autowired WatchlistService service
    @Autowired(required = false) com.sboxmarket.repository.WatchlistItemRepository itemRepository
    @Autowired(required = false) ItemRepository catalogueRepository

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    /** Item ids the signed-in user has starred. Returns just the array
     *  so it slots straight into the React `watchlist` useState without
     *  unwrapping. */
    @GetMapping
    ResponseEntity<List<Long>> list(HttpServletRequest req) {
        ResponseEntity.ok(service.list(requireUser(req)))
    }

    /** Toggle-on. Idempotent — returning the post-state list lets the
     *  client trust-but-verify its optimistic update without a follow-up
     *  GET. */
    @PostMapping('/{itemId}')
    ResponseEntity<Map> star(@PathVariable Long itemId, HttpServletRequest req) {
        def uid = requireUser(req)
        def added = service.add(uid, itemId)
        ResponseEntity.ok([
            itemId: itemId,
            added:  added,
            ids:    service.list(uid)
        ])
    }

    @DeleteMapping('/{itemId}')
    ResponseEntity<Map> unstar(@PathVariable Long itemId, HttpServletRequest req) {
        def uid = requireUser(req)
        def removed = service.remove(uid, itemId)
        ResponseEntity.ok([
            itemId:  itemId,
            removed: removed,
            ids:     service.list(uid)
        ])
    }

    /** CSV export of the user's watchlist (batch 695). Pairs with the
     *  transactions / my-stall / offer-history CSVs — completes the
     *  download-your-data surface. Columns: item_id, name, category,
     *  rarity, current_floor, steam_price, supply. Lets users track
     *  price movement on items they care about across spreadsheet
     *  snapshots. */
    @GetMapping(value = '/export.csv', produces = 'text/csv')
    ResponseEntity<String> exportCsv(HttpServletRequest req) {
        def uid = requireUser(req)
        def ids = service.list(uid)
        // Batch 979 — shared CsvUtil.safeCell (OWASP formula-injection safe).
        def esc = com.sboxmarket.util.CsvUtil.&safeCell
        def sb = new StringBuilder()
        sb.append("item_id,name,category,rarity,current_floor,steam_price,supply\n")
        if (!ids.isEmpty() && catalogueRepository != null) {
            def items = catalogueRepository.findAllById(ids)
            items.each { i ->
                sb.append(i.id).append(',')
                  .append(esc(i.name ?: '')).append(',')
                  .append(esc(i.category ?: '')).append(',')
                  .append(esc(i.rarity ?: '')).append(',')
                  .append(i.lowestPrice != null ? i.lowestPrice.toPlainString() : '').append(',')
                  .append(i.steamPrice  != null ? i.steamPrice.toPlainString()  : '').append(',')
                  .append(i.supply ?: 0)
                  .append('\n')
            }
        }
        ResponseEntity.ok()
            .header('Content-Disposition', 'attachment; filename="watchlist.csv"')
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    /** Clear every starred row in one call. Idempotent — zero-row
     *  callers get `{cleared:0}`, not a 404. Matches the bulk-clear
     *  family: POST /api/bids/auto/cancel-all, /api/buy-orders/cancel-all,
     *  /api/offers/outgoing/cancel-all. */
    @DeleteMapping
    ResponseEntity<Map> clear(HttpServletRequest req) {
        def uid = requireUser(req)
        int n = service.clear(uid)
        ResponseEntity.ok([cleared: n])
    }

    /** Public bulk watcher-count endpoint — drives the "👁 N watching"
     *  badge on marketplace cards. Pure aggregate, NO authentication
     *  required (anonymous browsers see the same number signed-in users
     *  do). `ids` is a comma-separated list of item ids; cap at 200 to
     *  bound a crafted query.
     *
     *  Response: `{itemId: count}` — items with zero watchers are
     *  omitted so the JSON stays tight on a sparsely-watched grid. */
    @GetMapping('/counts')
    ResponseEntity<Map<Long, Long>> bulkCounts(@RequestParam(required = false) String ids) {
        if (itemRepository == null || ids == null || ids.isBlank()) {
            return ResponseEntity.ok([:] as Map<Long, Long>)
        }
        List<Long> parsed = []
        for (String chunk : ids.split(',')) {
            try {
                def n = Long.valueOf(chunk.trim())
                if (n > 0L) parsed << n
            } catch (Exception ignored) {
                // Skip malformed ids rather than 400 — the marketplace
                // pre-render loop is best-effort and shouldn't crash on
                // a single bad token.
            }
        }
        if (parsed.isEmpty()) return ResponseEntity.ok([:] as Map<Long, Long>)
        if (parsed.size() > 200) parsed = parsed.take(200)
        def rows = itemRepository.countByItemIds(parsed)
        Map<Long, Long> out = [:]
        rows.each { row ->
            def id    = row[0] as Long
            def count = (row[1] ?: 0L) as Long
            if (count > 0L) out[id] = count
        }
        // Batch 811 — public/60s cache. Aggregate watcher counts per
        // itemId are viewer-agnostic. Same cadence as the other
        // bulk-lookup endpoints (/api/buy-orders/count/bulk).
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(out)
    }

    /** One-shot bulk merge — used by the client on first sign-in after
     *  the cross-device feature lands. Body: `{ ids: [..] }`. Response:
     *  the user's complete post-merge list so the client can replace
     *  its cache in one swap. */
    @PostMapping('/bulk')
    ResponseEntity<Map> bulkMerge(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def raw = body?.ids as List
        def ids = (raw ?: []).collect {
            try { it == null ? null : Long.valueOf(it.toString()) } catch (Exception ignored) { null }
        }.findAll { it != null }
        def merged = service.bulkMerge(uid, ids)
        ResponseEntity.ok([ids: merged])
    }
}
