package com.sboxmarket.controller

import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.service.ItemService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/api/items")
@Slf4j
class ItemController {

    @Autowired ItemService itemService
    @Autowired ItemRepository itemRepository
    @Autowired com.sboxmarket.repository.ListingRepository listingRepository
    @Autowired(required = false) com.sboxmarket.service.SboxApiService sboxApiService
    @Autowired(required = false) com.sboxmarket.service.SteamMarketPriceService steamMarketPriceService
    @Autowired(required = false) com.sboxmarket.service.ListingFloorRefreshService listingFloorRefreshService

    /** View-count dedupe cache (batch 413). Maps "ip|itemId" → last-bump
     *  epoch-ms. Prevents a single IP from inflating an item's view count
     *  by refreshing — one bump per (ip, item) pair per VIEW_DEDUPE_MS
     *  window (30 min) stays aligned with what a real user would do.
     *  Bounded at 10k entries via LRU-ish eviction on insert to prevent
     *  unbounded memory growth under scan attacks. In-memory only (per
     *  pod); across a cluster each pod caps independently but the DB
     *  write is still the same — worst case the count over-counts by
     *  one-per-pod-per-window, not a fundamental leak. */
    private static final long VIEW_DEDUPE_MS = 30L * 60L * 1000L
    private static final int  VIEW_DEDUPE_MAX_KEYS = 10_000
    private final ConcurrentHashMap<String, Long> viewBumpCache = new ConcurrentHashMap<>()

    @GetMapping
    ResponseEntity<List<Item>> search(
            @RequestParam(required = false) String q,
            // Batch 986 — accept `search` as an alias for `q` to match the
            // `/api/listings` aliasing (batch 985). Symmetric param-name
            // support across every search-capable endpoint so a user
            // copy-pasting `?search=Hat` between APIs always filters.
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "All") String category,
            @RequestParam(required = false, defaultValue = "All") String rarity,
            @RequestParam(required = false, defaultValue = "price_desc") String sort,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        // Fall through to the alias when the canonical param is blank.
        if ((q == null || q.isBlank()) && search != null && !search.isBlank()) {
            q = search
        }
        if (q != null) q = q.replace('\u0000', '')
        if (category != null) category = category.replace('\u0000', '')
        if (rarity != null) rarity = rarity.replace('\u0000', '')
        if (q != null && q.length() > 100) q = q.substring(0, 100)
        // Escape SQL LIKE wildcards (% _ \) so a user-typed `_` or `%`
        // matches that character literally instead of as a wildcard —
        // ItemRepository.searchByName's JPQL carries the matching
        // `ESCAPE '\'` clause. Without this, `?q=100%` returned every
        // row whose name started with `100`, and `?q=Hat_` returned
        // every five-character name beginning with `Hat`. Brings
        // /api/items into parity with the LIKE-escape posture already
        // applied on /api/database (DatabaseController.escapeLike) and
        // /api/listings (ListingController.escapeLike). Backslash first
        // so the escapes we add aren't themselves re-escaped.
        if (q != null && !q.isEmpty()) {
            q = q.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
        }
        // Batch 658 / 659 — case-insensitive enum normalisation via
        // the shared ListingEnums helper. Unknown values fall through
        // to 'All' (no filter).
        category = com.sboxmarket.util.ListingEnums.canonEnum(category, com.sboxmarket.util.ListingEnums.CATEGORIES, 'All')
        rarity   = com.sboxmarket.util.ListingEnums.canonEnum(rarity,   com.sboxmarket.util.ListingEnums.RARITIES,   'All')
        // Batch 661 — sort ids are always lowercase machine-generated
        // tokens (`price_asc`, `newest`, etc.), but a share URL might
        // carry `sort=PRICE_ASC` — historically that fell through the
        // service switch to the default, flipping a user's intended
        // ASC view into a DESC view. Lowercase here to keep the switch
        // deterministic regardless of caller case.
        if (sort != null) sort = sort.toLowerCase()
        def items = itemService.search(q, category, rarity, sort, minPrice, maxPrice)
        // Batch 693 — server-side cap. The catalogue is currently ~80
        // items but is designed to scale to 10k+. Without a cap a
        // crafted `GET /api/items?category=All` would return every row
        // and every client would render all of them. 500 is a sane
        // default for the autocomplete-style callers; explicit
        // limit/offset lets pageable callers (the database view, bot
        // integrations) walk deeper without another round-trip.
        int safeLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 500
        int safeOffset = offset != null ? Math.max(offset, 0) : 0
        if (safeOffset > 0 || items.size() > safeLimit) {
            items = items.drop(safeOffset).take(safeLimit)
        }
        // Batch 761 — 60-second public cache when the response is a
        // viewer-agnostic catalogue slice (no search query). The autocomplete
        // suggest bar hits this on every keystroke and the database page loads
        // it on open; a short cache cuts both. When the caller supplies a
        // free-text `q`, skip the cache header — the combinatorial space of
        // queries means cache hits are rare and not worth the staleness risk.
        def builder = ResponseEntity.ok()
        if (q == null || q.isBlank()) {
            builder = builder.header('Cache-Control', 'public, max-age=60')
        }
        builder.body(items)
    }

    /** Bulk item lookup — `GET /api/items/batch?ids=1,2,3`. Resolves a
     *  CSV of catalogue ids in one round-trip via a single
     *  `findAllById`, eliminating the frontend N+1 where the
     *  recently-viewed rail/pills fired one `GET /api/items/{id}` per
     *  cached id (~18 GETs per navigation).
     *
     *  Contract:
     *   - Returns a JSON array of the same raw `Item` shape `/{id}`
     *     returns — callers reuse their existing item-field mapping.
     *   - Missing ids are simply OMITTED (no `{notFound:true}`
     *     sentinel). Order is not guaranteed; callers re-sort by their
     *     own key (the rail sorts by `viewedAt`).
     *   - Id count is capped at 50 — the rail caches at most ~18, and a
     *     hard cap stops a crafted `?ids=1,2,...,100000` from pulling
     *     the catalogue in one request.
     *   - Anon-accessible (catalogue items are public) with the same
     *     `public, max-age=30` cache posture as `/{id}`. No view-count
     *     bump here: this is a list-refresh path, not a detail view,
     *     and bumping a counter from a background validate would
     *     inflate counts on every navigation.
     *   - Blank/garbage `ids` yields an empty array, not an error. */
    private static final int BATCH_MAX_IDS = 50

    @GetMapping("/batch")
    ResponseEntity<List<Item>> getBatch(@RequestParam(required = false) String ids) {
        List<Long> parsed = []
        if (ids != null && !ids.isBlank()) {
            for (String tok : ids.split(',')) {
                String t = tok?.trim()
                if (t) {
                    try { parsed << Long.parseLong(t) } catch (NumberFormatException ignore) { /* skip junk */ }
                }
            }
        }
        // De-dupe (a caller could repeat an id) and cap before the DB hit.
        List<Long> wanted = parsed.unique(false).take(BATCH_MAX_IDS)
        List<Item> items = wanted.isEmpty() ? [] : itemRepository.findAllById(wanted).toList()
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=30')
            .body(items)
    }

    @GetMapping("/{id}")
    ResponseEntity<?> getById(@PathVariable Long id, HttpServletRequest req) {
        // 200 + `{notFound: true}` sentinel for missing ids instead of a
        // hard 404. Why: Chrome auto-logs every fetch 404 to the console
        // as "Failed to load resource" regardless of JS handling, so
        // every dead-link landing on /item/{id} fired a phantom error
        // that read as a real bug. Sentinel keeps the document-level
        // response 200, the SPA's `fetchItem` translates the sentinel
        // back to null, and the branded "Item not found" empty state
        // still renders. SEO is unaffected — the OpenGraphController on
        // /item/{id} (the HTML surface) emits its own noindex meta.
        def opt = itemRepository.findById(id)
        if (!opt.isPresent()) {
            return ResponseEntity.ok()
                .header('Cache-Control', 'no-cache, must-revalidate')
                .body([notFound: true, id: id])
        }
        def item = opt.get()
        // Fire-and-forget view-count bump (batch 409) with per-(ip, item)
        // dedupe (batch 413). Isolated in its own @Transactional write
        // via the repo's @Modifying query so a counter-bump failure
        // never breaks the item-detail response. A real user loading
        // the same item page twice in 30 minutes counts as one view —
        // closes the "refresh to inflate" abuse path while still
        // tracking genuine interest.
        try {
            if (shouldBumpView(req, id)) {
                itemRepository.incrementViewCount(id)
            }
        } catch (Exception ignore) { /* best effort */ }
        // Batch 807 — `public, max-age=30` on the item detail payload.
        // Safe: the response carries no per-viewer fields (view count is
        // a site-wide aggregate; price is the floor across all listings).
        // 30s mirrors the marketplace grid's soft-poll cadence — an item
        // whose price just dropped surfaces on the grid in the same tick.
        // Shared edge cache is fine because the payload's viewer-
        // agnostic. View-count bump is unaffected: the 30-min server-
        // side dedupe already suppresses inflation from the same IP.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=30')
            .body(item)
    }

    /** Returns true when the (client IP, item) pair hasn't been bumped
     *  inside the VIEW_DEDUPE_MS window. Also evicts the oldest entry
     *  when the cache crosses the size cap — a cheap throttle on scan
     *  attacks trying to enumerate ids. Always returns true when the
     *  IP resolver can't identify the caller (best-effort, avoids
     *  penalising legitimate proxy-stripped traffic). */
    private boolean shouldBumpView(HttpServletRequest req, Long itemId) {
        def ip = clientIp(req)
        if (!ip || !itemId) return true
        def key = ip + '|' + itemId
        long now = System.currentTimeMillis()
        // Atomic "claim or refresh" via `compute` — closes the
        // check-then-act race that defeated the 30-min dedupe under
        // concurrent load. Pre-fix this was `get` then `put`: N parallel
        // requests for the same (ip,item) all read `prev == null`, all
        // wrote, all returned true, so a refresh storm / browser
        // pre-fetch / scraper could inflate the view count by one per
        // concurrent request and the 30-min cap only kicked in for
        // serial replays. `compute` runs the remap function under the
        // CHM bin lock, so exactly one racer observes "no fresh stamp"
        // and wins; every concurrent peer sees the just-written stamp
        // and returns false. (See ItemViewBumpRaceSpec.)
        boolean[] bumpedRef = new boolean[1]
        viewBumpCache.compute(key) { _, prev ->
            if (prev != null && (now - prev) < VIEW_DEDUPE_MS) {
                bumpedRef[0] = false
                return prev
            }
            bumpedRef[0] = true
            return now
        }
        if (!bumpedRef[0]) return false
        if (viewBumpCache.size() > VIEW_DEDUPE_MAX_KEYS) {
            def oldest = viewBumpCache.entrySet().min { it.value }
            if (oldest) viewBumpCache.remove(oldest.key)
        }
        true
    }

    private static String clientIp(HttpServletRequest req) {
        def cf = req.getHeader('CF-Connecting-IP')
        if (cf && !cf.trim().isEmpty()) return cf.trim()
        def xff = req.getHeader('X-Forwarded-For')
        if (xff) {
            // A crafted `X-Forwarded-For: ,` (or `,,`, all-empty tokens)
            // is non-blank — so the old `xff.split(',')[0]` indexed into
            // a ZERO-length array (Java's split drops every trailing
            // empty), throwing ArrayIndexOutOfBoundsException. Take the
            // first NON-empty token; fall through to remoteAddr when the
            // header carries no real client address.
            for (String tok : xff.split(',')) {
                def t = tok?.trim()
                if (t) return t
            }
        }
        req.remoteAddr
    }

    @GetMapping("/{id}/history")
    ResponseEntity<List<PriceHistory>> getPriceHistory(@PathVariable Long id) {
        // Price-history samples are written once a day by the scheduler;
        // any browser opening the chart multiple times in a 5-minute
        // window sees identical data. Short browser-cache header cuts
        // the round-trip on tab-focus refetch (batch 424) without
        // risking a noticeable stale read.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=300')
            .body(itemService.getPriceHistory(id))
    }

    /** Recent sale rows for an item (price + timestamp only — no
     *  counterparty identities leaked). Default 10 matches CSFloat's
     *  "Last 10 sales" strip. Accepts `?limit=N` (capped at 50) so
     *  the frontend's "Show more" expander can pull a deeper history
     *  for items with rich sale data without hammering the endpoint
     *  with pagination. 50 is enough to give the median chip (batch
     *  729) a stable anchor without bloating the payload. */
    @GetMapping("/{id}/recent-sales")
    ResponseEntity<List<Map>> getRecentSales(@PathVariable Long id,
                                             @RequestParam(required = false) Integer limit) {
        int n = (limit == null) ? 10 : Math.max(1, Math.min(50, limit.intValue()))
        def rows = listingRepository.findRecentSalesForItem(
            id, org.springframework.data.domain.PageRequest.of(0, n))
        def out = rows.collect { l ->
            [
                listingId: l.id,
                price:     l.price,
                soldAt:    l.soldAt,
                listingType: l.listingType
            ]
        }
        // 60-second browser cache — sales data only changes when a new
        // listing settles, and the ItemModal's "Show more" expander plus
        // the tab-focus refetch pattern (batch 424) both re-hit this
        // endpoint. Short cache keeps the UX snappy without making a
        // just-sold row invisible for an inconvenient length of time.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(out)
    }

    /**
     * Similar-items feed for the item detail view. Same (category-or-
     * rarity, price-proximity) semantics as before, but the walk runs
     * inside a single indexed JPQL query with `LIMIT 12` instead of
     * loading every catalogue row and sorting in Groovy.
     */
    /** Trade velocity for an item — count of listings SOLD in the last
     *  24h + 7d + 30d. Indexed COUNTs; cheap to call on every item detail
     *  modal open. Returns zero when nothing has sold recently. Batch
     *  874 added the 24h bucket so fast-moving items can surface a
     *  "hot today" signal instead of being averaged out by the 7d. */
    @GetMapping("/{id}/velocity")
    ResponseEntity<Map> velocity(@PathVariable Long id) {
        def now = System.currentTimeMillis()
        def day   = now - 1L  * 86400_000L
        def week  = now - 7L  * 86400_000L
        def month = now - 30L * 86400_000L
        def last = listingRepository.findLastSoldForItem(id,
            PageRequest.of(0, 1))
        def lastRow = last?.isEmpty() ? null : last[0]
        // 2-minute browser cache — the 24h/7d/30d COUNTs shift only when
        // a sale closes, and the ItemModal reads this on every open.
        // Cuts the aggregate-query load on repeat visits.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=120')
            .body([
                soldLast24h:     listingRepository.countSoldForItemSince(id, day),
                soldLast7d:      listingRepository.countSoldForItemSince(id, week),
                soldLast30d:     listingRepository.countSoldForItemSince(id, month),
                volumeLast30d:   listingRepository.sumSoldVolumeForItemSince(id, month) ?: BigDecimal.ZERO,
                lastSoldPrice:   lastRow?.price,
                lastSoldAt:      lastRow?.soldAt
            ])
    }

    @GetMapping("/{id}/similar")
    ResponseEntity<List<Item>> getSimilar(@PathVariable Long id) {
        // A missing/stale id returns an empty list with a 200, NOT a 404.
        // The ItemModal fires /similar alongside /velocity, /history and
        // /recent-sales on open; those three already degrade to empty 200s
        // for a dead id, and GET /api/items/{id} itself returns the
        // `{notFound:true}` sentinel rather than a 404 (Chrome console-logs
        // every fetch 404). Routing through itemService.getById() here threw
        // NotFoundException → 404, making /similar the lone sub-resource
        // that emitted a phantom console error on a dead-link landing.
        def opt = itemRepository.findById(id)
        if (!opt.isPresent()) {
            return ResponseEntity.ok()
                .header('Cache-Control', 'public, max-age=300')
                .body([] as List<Item>)
        }
        def base = opt.get()
        def basePrice = base.lowestPrice ?: BigDecimal.ZERO
        def similar = itemRepository.findSimilar(
            base.id,
            base.category ?: '',
            base.rarity ?: '',
            basePrice,
            PageRequest.of(0, 12)
        )
        // 5-minute browser cache — the similar-items query is viewer-
        // agnostic (category + rarity + price-proximity) and similarity
        // doesn't shift across a modal session. `public` because there's
        // no session state in the response, so shared caches can share.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=300')
            .body(similar)
    }

    @GetMapping("/stats")
    ResponseEntity<Map> getStats() {
        def stats = itemService.getStats() as Map
        // Attach the latest catalog-sync timestamp so the footer can render
        // "Catalog updated X ago" as a live trust signal. Prefer the
        // Steam-Market sync (the live path — runs every 30 min in prod)
        // over the SCMM one (retired, always 0). Batch 956: the footer
        // used to be permanently stuck at "—" because only the disabled
        // SCMM value was wired. Now picks whichever sync actually ran
        // most recently; 0 only on first boot before any cycle lands.
        long scmmTs  = (sboxApiService?.lastSyncedAt ?: 0L) as long
        long steamTs = 0L
        try { steamTs = (steamMarketPriceService?.lastRunSummary?.finishedAt ?: 0L) as long }
        catch (Exception ignore) {}
        stats = (stats ?: [:]) + [lastSyncedAt: Math.max(scmmTs, steamTs)]
        // 60s cache — catalogue-wide stats (total items, rarity mix,
        // last-sync timestamp) shift at the minute granularity at most.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(stats)
    }

    /** Public freshness probe — drives the "Prices updated Xs ago" chip
     *  on the marketplace grid and the Sell modal. Combines the two
     *  refresh sources:
     *   1. ListingFloorRefreshService — recomputes lowestPrice from
     *      active listings every 60s (the dominant signal — covers
     *      cancels, sales, new listings, off-path drift).
     *   2. SteamMarketPriceService — pulls Steam Community Market
     *      prices every 30 min for unlisted items (rate-limited).
     *
     *  Bug fix: previously reported `lastUpdatedAt = max(floor, steam)`
     *  which silently MASKED a wedged floor sweep — Steam pings every
     *  30 min, so the moment the floor scheduler thread died the chip
     *  still went green on Steam's tick even though `lowestPrice` was
     *  drifting on every cancel/sale. The dominant signal is the floor
     *  sweep; if it's overdue past its own interval × 3 (180s), the
     *  chip is lying. We now compute and surface a server-side `stale`
     *  flag so the frontend can render amber regardless of which
     *  source most-recently fired. `lastUpdatedAt` stays max() so the
     *  "Just now" timestamp render keeps working; `stale` is the gate. */
    @GetMapping("/price-refresh-status")
    ResponseEntity<Map> priceRefreshStatus() {
        Map floor = null
        Map steam = null
        try { floor = listingFloorRefreshService?.lastRunSummary } catch (Exception ignore) {}
        try { steam = steamMarketPriceService?.lastRunSummary }    catch (Exception ignore) {}

        long floorAt = (floor?.finishedAt ?: 0L) as long
        long steamAt = (steam?.finishedAt ?: 0L) as long
        long lastAt  = Math.max(floorAt, steamAt)
        long now     = System.currentTimeMillis()

        // The floor sweep is the dominant signal (every 60s, covers all
        // listing mutations). If it's enabled, present, and either has
        // never reported OR is overdue past 3× its scheduled interval,
        // the freshness chip MUST surface stale — even when Steam just
        // ticked. 3× cadence is generous enough to absorb GC pauses and
        // a slow DB round-trip without flapping, tight enough that a
        // truly wedged scheduler shows within ~3 min.
        boolean floorEnabled = (floor?.enabled != null) ? (floor.enabled as boolean) : true
        long floorInterval = (floor?.intervalMs ?: 0L) as long
        boolean floorStale = floor != null && floorEnabled && floorInterval > 0 && (
                floorAt == 0L || (now - floorAt) > (floorInterval * 3L))

        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=10')
            .body([
                lastUpdatedAt: lastAt,
                stale:         floorStale,
                floor:         floor,
                steam:         steam,
                serverNow:     now
            ] as Map)
    }
}
