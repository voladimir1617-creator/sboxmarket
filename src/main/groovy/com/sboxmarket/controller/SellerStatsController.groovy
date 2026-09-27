package com.sboxmarket.controller

import com.sboxmarket.repository.ReviewRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

import java.util.concurrent.atomic.AtomicReference

/**
 * Public aggregate stats about sellers. No PII — everything this
 * controller returns is already visible on /stall/{id} pages. Drives
 * the "Top Sellers this week" rail on the homepage per CSFloat
 * Manual §4.
 *
 * Response shape is deliberately lightweight — frontend merges with
 * rating summary lazily so we don't fan out 5× review lookups on
 * every homepage hit.
 */
@RestController
@RequestMapping('/api/sellers')
@Slf4j
class SellerStatsController {

    @Autowired TradeRepository    tradeRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired(required = false) ReviewRepository reviewRepository
    @Autowired(required = false) com.sboxmarket.repository.ListingRepository listingRepository
    @Autowired(required = false) com.sboxmarket.service.TradeService tradeService

    /** Tiny 60-second cache — homepage hit-rate can be high and the
     *  underlying aggregation scans a narrow-index range but still
     *  costs more than a cache probe. Atomic-ref pair so a stale read
     *  is fine (worst case we compute again). */
    private final AtomicReference<Map> topCache = new AtomicReference<>(null)
    private static final long TOP_CACHE_MS = 60_000L

    /**
     * GET /api/sellers/top — top N sellers by VERIFIED trade count
     * over the rolling `days` window. Defaults: 7 days, 5 results.
     *
     * Response:
     *   [{ sellerUserId, displayName, avatarUrl, saleCount, totalRevenue,
     *      rating: { average, count } | null }]
     *
     * Public endpoint — no auth required. Safe to call from anonymous
     * home page hits.
     */
    @GetMapping('/top')
    ResponseEntity<List<Map>> top(@RequestParam(required = false, defaultValue = '7') Integer days,
                                  @RequestParam(required = false, defaultValue = '5') Integer limit) {
        // Clamp inputs so a crafted query can't ask for every seller
        // since the Big Bang. 1-90 day window, 1-20 rows returned.
        // Explicit null-check, not Elvis — sibling Elvis-on-zero fix
        // pattern (75678e1 / 655596b). `?days=0` and `?limit=0` are
        // honoured (clamp to 1) instead of silently substituting defaults.
        int win = Math.min(Math.max(days != null ? days : 7, 1), 90)
        int lim = Math.min(Math.max(limit != null ? limit : 5, 1), 20)
        def cached = topCache.get()
        def cacheKey = "${win}:${lim}".toString()
        if (cached != null
                && cacheKey == cached.key
                && (System.currentTimeMillis() - (cached.at as Long)) < TOP_CACHE_MS) {
            return ResponseEntity.ok()
                .header('Cache-Control', 'public, max-age=120')
                .body(cached.payload as List<Map>)
        }
        def since = System.currentTimeMillis() - (win * 24L * 60L * 60L * 1000L)
        def rows  = tradeRepository.findTopSellersSince(since, PageRequest.of(0, lim))
        if (rows.isEmpty()) {
            topCache.set([ key: cacheKey, at: System.currentTimeMillis(), payload: [] ])
            return ResponseEntity.ok()
                .header('Cache-Control', 'public, max-age=120')
                .body([])
        }
        def ids = rows.collect { (it.sellerUserId as Long) }.findAll { it != null }
        def users = steamUserRepository.findAllById(ids).collectEntries { [(it.id): it] }
        // Batch 1089 — bulk rating aggregate (single GROUP BY) replaces
        // the prior N+1 loop that fanned out one `aggregateForUser` call
        // per top-N seller. At lim=20 (the clamp ceiling) the leaderboard
        // was burning up to 21 DB round-trips on every cache miss; one
        // bulk query brings it back to 3 (top-sellers + users + ratings).
        // `aggregateForUsers` returns [uid, count, avg] rows; sellers
        // without any reviews are absent from the result (treat as null
        // rating to match the prior "count > 0" gate).
        def ratings = [:]
        if (reviewRepository != null && !ids.isEmpty()) {
            try {
                reviewRepository.aggregateForUsers(ids).each { row ->
                    def uid = row[0] as Long
                    def count = (row[1] ?: 0L) as Long
                    if (count > 0) {
                        def avg = row[2] != null
                            ? (row[2] as BigDecimal).setScale(2, java.math.RoundingMode.HALF_UP)
                            : null
                        ratings[uid] = [ average: avg, count: count ]
                    }
                }
            } catch (Exception e) {
                log.debug("aggregateForUsers failed: ${e.message}")
            }
        }
        def payload = rows.collect { row ->
            def sellerId = row.sellerUserId as Long
            def u = users[sellerId]
            // Batch 352 — banned sellers shouldn't appear on the public
            // Top Sellers leaderboard. Filter at render time so a staff
            // ban immediately removes them from the list without
            // recomputing the aggregate query.
            if (u != null && Boolean.TRUE.equals(u.banned)) return null
            [
                sellerUserId: sellerId,
                displayName:  u?.displayName,
                avatarUrl:    u?.avatarUrl,
                saleCount:    (row.saleCount ?: 0L) as Long,
                totalRevenue: row.totalRevenue != null ? (row.totalRevenue as BigDecimal).setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO,
                rating:       ratings[sellerId]
            ]
        }.findAll { it != null }
        topCache.set([ key: cacheKey, at: System.currentTimeMillis(), payload: payload ])
        // 2-minute browser cache on top of the in-memory server cache
        // (batch 740/741). The server cache protects the DB; the
        // browser cache protects the network round-trip.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=120')
            .body(payload)
    }

    /**
     * GET /api/sellers/verified?ids=1,2,3 — bulk "is this seller verified"
     * lookup for the marketplace grid. Drives the ✓ badge on GridCard
     * next to the seller name. Verified rule mirrors the stall hero:
     *   `soldCount >= 10  AND  (no reviews OR avg rating >= 4.0)`.
     *
     * Response: `{1: true, 5: true}` — only verified ids are emitted so
     * a sparsely-verified grid stays tight on the wire. Missing ids =
     * false. Public endpoint, cap at 200 input ids.
     */
    /**
     * GET /api/sellers/me/verification-progress — the signed-in seller's
     * progress toward the ✓ Verified badge. Exposes the same thresholds
     * the bulk /verified endpoint uses so a seller can see exactly what
     * they need to hit. Encourages stall owners who are close to
     * verification to push the last few sales / reviews over the line.
     *
     * Response shape:
     *   {
     *     verified: false,
     *     soldCount: 7,
     *     salesNeeded: 3,
     *     ratingAverage: 4.5, ratingCount: 2,
     *     ratingOk: true,
     *     thresholdSales: 10,
     *     thresholdRating: 4.0
     *   }
     *
     * Signed-in only (401 for anon). Cheap: one sold-count + one rating
     * aggregate, both against indexed columns.
     */
    @GetMapping('/me/verification-progress')
    ResponseEntity<Map> myVerificationProgress(jakarta.servlet.http.HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) {
            throw new com.sboxmarket.exception.UnauthorizedException()
        }
        long soldCount = listingRepository != null
            ? (listingRepository.countSoldBySeller(uid) ?: 0L)
            : 0L
        long ratingCount = 0L
        BigDecimal ratingAvg = null
        if (reviewRepository != null) {
            try {
                def agg = reviewRepository.aggregateForUser(uid)
                if (agg != null && !agg.isEmpty()) {
                    def row = agg[0]
                    ratingCount = (row[0] ?: 0L) as Long
                    if (ratingCount > 0 && row[1] != null) {
                        ratingAvg = (row[1] as BigDecimal).setScale(2, java.math.RoundingMode.HALF_UP)
                    }
                }
            } catch (Exception e) {
                log.debug("aggregateForUser(${uid}) failed: ${e.message}")
            }
        }
        boolean ratingOk = ratingCount == 0L || (ratingAvg != null && ratingAvg.doubleValue() >= 4.0d)
        boolean salesOk = soldCount >= 10L
        boolean verified = salesOk && ratingOk
        ResponseEntity.ok([
            verified:        verified,
            soldCount:       soldCount,
            salesNeeded:     Math.max(0L, 10L - soldCount),
            ratingAverage:   ratingAvg,
            ratingCount:     ratingCount,
            ratingOk:        ratingOk,
            thresholdSales:  10L,
            thresholdRating: 4.0d
        ])
    }

    /**
     * Public seller search (batch 666). Case-insensitive displayName
     * match against sellers who have at least one listing posted.
     * CSFloat-parity: the "Find a seller by name" surface on the
     * stall-discovery modal. Clamps `q` length + the result page so a
     * crafted `?q=` + `?limit=` can't become a full-user enumerator.
     *
     * Public — no auth required. Uses the same displayName index the
     * admin search leans on, but adds an EXISTS-Listing gate so a
     * brand-new user with no listings doesn't leak into the result
     * set by name alone.
     *
     * Response:
     *   [{ sellerUserId, displayName, avatarUrl, activeListings, soldCount,
     *      verified }]
     *
     * Batch 668: attached the `verified` flag so the UI can render the
     * ✓ badge directly in the search dropdown without a second round-trip
     * to `/api/sellers/verified`. Same thresholds used everywhere else:
     * `soldCount >= 10` AND (no reviews yet OR avgRating >= 4.0).
     */
    @GetMapping('/search')
    ResponseEntity<List<Map>> search(@RequestParam(required = false) String q,
                                     // Batch 987 — `search` alias to match batches 985 + 986
                                     // (/api/listings + /api/items + /api/database).
                                     @RequestParam(required = false) String search,
                                     @RequestParam(required = false) Integer limit) {
        // Batch 811 — hoist the cache header into a reusable closure so
        // every return path (too-short query, empty result, happy path)
        // carries the same `public, max-age=60`. Without this, a query
        // that hits one of the two early-return branches shipped
        // uncached and re-ran the full-text search on every keystroke.
        def cached = { body -> ResponseEntity.ok().header('Cache-Control', 'public, max-age=60').body(body) }
        if ((q == null || q.isBlank()) && search != null && !search.isBlank()) q = search
        if (q == null) q = ''
        q = q.trim().replace('\u0000', '')
        if (q.length() < 2) return cached([])   // too short — noise guard
        if (q.length() > 60) q = q.substring(0, 60)
        // Same Elvis-on-zero fix as above + sibling controllers.
        int lim = Math.min(Math.max(limit != null ? limit : 10, 1), 25)
        def rows = steamUserRepository.searchPublicSellers(q, PageRequest.of(0, lim))
        if (rows == null || rows.isEmpty()) return cached([])
        def ids = rows.collect { (it[0] as Long) }
        Map<Long, Long> soldBy = [:]
        Map<Long, Long> activeBy = [:]
        Map<Long, Map> ratingBy = [:]
        if (listingRepository != null) {
            listingRepository.countSoldByMultipleSellers(ids).each { r ->
                soldBy[(r[0] as Long)] = (r[1] ?: 0L) as Long
            }
            listingRepository.countActiveByMultipleSellers(ids).each { r ->
                activeBy[(r[0] as Long)] = (r[1] ?: 0L) as Long
            }
        }
        if (reviewRepository != null) {
            try {
                reviewRepository.aggregateForUsers(ids).each { r ->
                    def uid = r[0] as Long
                    def count = (r[1] ?: 0L) as Long
                    def avg = r[2] != null ? (r[2] as BigDecimal).doubleValue() : null
                    ratingBy[uid] = [count: count, avg: avg]
                }
            } catch (Exception e) {
                log.debug("search rating aggregate failed: ${e.message}")
            }
        }
        def out = rows.collect { r ->
            def id = r[0] as Long
            long sold = (soldBy[id] ?: 0L) as long
            def rating = ratingBy[id]
            long rCount = (rating?.count ?: 0L) as long
            double rAvg = (rating?.avg ?: 0.0d) as double
            boolean verified = sold >= 10L && (rCount == 0L || rAvg >= 4.0d)
            [
                sellerUserId:    id,
                displayName:     r[1],
                avatarUrl:       r[2],
                activeListings:  activeBy[id] ?: 0L,
                soldCount:       sold,
                verified:        verified,
                // Batch 675 — rating surface in the search dropdown.
                // Social-proof chip. Null ratingAverage when the seller
                // has zero reviews so the UI can render "No reviews yet"
                // instead of a misleading "0.00★".
                ratingCount:     rCount,
                ratingAverage:   rCount > 0 ? new BigDecimal(rAvg).setScale(2, java.math.RoundingMode.HALF_UP) : null
            ]
        }
        // Batch 671 — relevance-first sort. A user searching "bob" should
        // see the verified / highest-selling Bob at the top, not
        // whichever Bob happens to sort first alphabetically. Stable
        // secondary sort by display name keeps the ordering deterministic
        // when sold counts tie.
        out = out.sort(false) { a, b ->
            def vc = (b.verified ? 1 : 0) <=> (a.verified ? 1 : 0)
            if (vc != 0) return vc
            def sc = (b.soldCount as long) <=> (a.soldCount as long)
            if (sc != 0) return sc
            def ac = (b.activeListings as long) <=> (a.activeListings as long)
            if (ac != 0) return ac
            (a.displayName as String ?: '') <=> (b.displayName as String ?: '')
        }
        // Batch 811 — `public, max-age=60`. Search results for a given
        // query string are viewer-agnostic — seller rating/sold counts
        // don't change by who's asking. A shared cache absorbs the
        // debounced keystrokes typed within the 300ms input lag.
        cached(out)
    }

    /**
     * Bulk typical-ship-time lookup (batch 710). Drives the "⚡ ships in
     * ~2h" chip on per-item listing rows — buyers sorting through 10
     * listings of the same item want to pick a fast shipper. Pure
     * aggregate (median over the last 90 days), NO auth required —
     * same public audience as /sellers/verified + /sellers/top.
     *
     * Response: `{sellerUserId: typicalShipMs}`. Sellers below the
     * 3-sample noise floor are omitted (caller treats missing as
     * "unknown — not enough data"). Input cap matches the other bulk
     * endpoints at 200 ids.
     */
    @GetMapping('/ship-times')
    ResponseEntity<Map<Long, Long>> bulkShipTimes(@RequestParam(required = false) String ids) {
        if (tradeService == null || ids == null || ids.isBlank()) {
            return ResponseEntity.ok([:] as Map<Long, Long>)
        }
        List<Long> parsed = []
        for (String chunk : ids.split(',')) {
            try {
                def n = Long.valueOf(chunk.trim())
                if (n > 0L) parsed << n
            } catch (Exception ignored) { /* skip bad token */ }
        }
        if (parsed.isEmpty()) return ResponseEntity.ok([:] as Map<Long, Long>)
        if (parsed.size() > 200) parsed = parsed.take(200)
        // Bulk N+1 fix — the prior path looped `parsed.each { uid ->
        // tradeService.typicalShipMs(uid) }`, fanning out one SQL
        // round-trip per seller id. At the 200-id cap this endpoint —
        // hit on every marketplace grid load — was burning up to 200
        // queries per request. `typicalShipMsBulk` collapses all of
        // them into a single IN-clause query and groups in memory.
        Map<Long, Long> out
        try {
            out = tradeService.typicalShipMsBulk(parsed.unique())
        } catch (Exception ignored) {
            out = [:]
        }
        // Batch 756 — 2-minute public browser cache. Ship-time medians
        // shift slowly (one new trade per seller changes the 90-day
        // median by minutes at most). Bulk call fires on every stall
        // load so the cache is meaningful.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=120')
            .body(out)
    }

    @Autowired(required = false) com.sboxmarket.service.SellerTrustService sellerTrustService

    /**
     * GET /api/sellers/trust?ids=1,2 — each seller's public trade record
     * (completed trades, seller-caused failures, completion rate, median time
     * to send, rating, member since, last seen). Drives the "Sold by" card on
     * the item page and the trade chips on its listing rows. See
     * SellerTrustService for how each figure is counted.
     *
     * Public, like /verified and /ship-times: everything here is about a
     * seller's conduct as a seller and is shown on their stall. Sellers the
     * record could not be read for are absent, never zero-filled, so the page
     * can tell "no record" from "could not ask". Capped at 50 ids.
     */
    @GetMapping('/trust')
    ResponseEntity<Map<Long, Map>> trustBulk(@RequestParam(required = false) String ids) {
        if (sellerTrustService == null || ids == null || ids.isBlank()) {
            return ResponseEntity.ok([:] as Map<Long, Map>)
        }
        List<Long> parsed = []
        for (String chunk : ids.split(',')) {
            try {
                def n = Long.valueOf(chunk.trim())
                if (n > 0L) parsed << n
            } catch (Exception ignored) { /* skip a bad token, same as /verified */ }
        }
        if (parsed.isEmpty()) return ResponseEntity.ok([:] as Map<Long, Map>)
        def out = sellerTrustService.recordFor(parsed.unique().take(com.sboxmarket.service.SellerTrustService.MAX_IDS))
        // One minute: a completed trade should reach the item page quickly,
        // and the response carries nothing viewer-specific.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(out)
    }

    @GetMapping('/verified')
    ResponseEntity<Map<Long, Boolean>> verifiedBulk(@RequestParam(required = false) String ids) {
        if (listingRepository == null || ids == null || ids.isBlank()) {
            return ResponseEntity.ok([:] as Map<Long, Boolean>)
        }
        List<Long> parsed = []
        for (String chunk : ids.split(',')) {
            try {
                def n = Long.valueOf(chunk.trim())
                if (n > 0L) parsed << n
            } catch (Exception ignored) {
                // Silently drop malformed tokens — the marketplace pre-render
                // loop shouldn't crash on a single bad id.
            }
        }
        if (parsed.isEmpty()) return ResponseEntity.ok([:] as Map<Long, Boolean>)
        if (parsed.size() > 200) parsed = parsed.take(200)

        // Bulk sold-count for all ids in one GROUP BY.
        Map<Long, Long> soldBy = [:]
        listingRepository.countSoldByMultipleSellers(parsed).each { row ->
            soldBy[(row[0] as Long)] = (row[1] ?: 0L) as Long
        }
        // Bulk rating aggregate.
        Map<Long, Map> ratingBy = [:]
        if (reviewRepository != null) {
            try {
                reviewRepository.aggregateForUsers(parsed).each { row ->
                    def uid = row[0] as Long
                    def count = (row[1] ?: 0L) as Long
                    def avg = row[2] != null ? (row[2] as BigDecimal).doubleValue() : null
                    ratingBy[uid] = [count: count, avg: avg]
                }
            } catch (Exception e) {
                log.debug("aggregateForUsers failed: ${e.message}")
            }
        }
        Map<Long, Boolean> out = [:]
        parsed.each { uid ->
            long sold = soldBy[uid] ?: 0L
            if (sold < 10L) return   // not enough track record
            def rating = ratingBy[uid]
            long count = (rating?.count ?: 0L) as long
            double avg = (rating?.avg ?: 0.0d) as double
            if (count == 0L || avg >= 4.0d) out[uid] = true
        }
        // Batch 756 — 2-minute public browser cache. Verified status
        // changes slowly (only when a seller crosses the sold-count
        // threshold or their rating dips below 4.0), and the grid's
        // bulk-fetch fires on every page load. Public because the
        // response is purely `uid → boolean` — no per-viewer data.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=120')
            .body(out)
    }

    /**
     * GET /api/sellers/avatars?ids=1,2,3 — bulk Steam-avatar-URL lookup
     * for the marketplace grid + item detail's Active Listings table.
     *
     * Before this endpoint, every `<div class="modal-seller-av">` and
     * `<div class="seller-avatar">` rendered a two-letter monogram
     * derived from `Listing.sellerAvatar` (which we store at listing-
     * write time as `user.displayName.take(2).toUpperCase()`). That
     * monogram is accurate but uninspiring — a buyer comparing 10
     * listings of the same hat has no visual anchor for "oh, I've
     * bought from that seller before" without clicking into the stall.
     *
     * Response: `{1: "https://avatars.fastly.steamstatic.com/...", 5: "..."}`
     * — only users WITH a non-blank avatar_url are emitted so a fresh
     * account without a Steam profile photo still falls through to the
     * monogram fallback cleanly on the frontend. Missing ids = no
     * avatar → render the existing monogram. Public endpoint, cap at
     * 200 input ids (mirroring /verified).
     *
     * Public — avatars are already on public /stall/{id} pages and on
     * the /sellers/top rail, no PII risk. 5-minute browser cache
     * because avatars rarely change; the Steam CDN will be hit for the
     * actual image bytes regardless.
     */
    @GetMapping('/avatars')
    ResponseEntity<Map<Long, String>> avatarsBulk(@RequestParam(required = false) String ids) {
        if (steamUserRepository == null || ids == null || ids.isBlank()) {
            return ResponseEntity.ok([:] as Map<Long, String>)
        }
        List<Long> parsed = []
        for (String chunk : ids.split(',')) {
            try {
                def n = Long.valueOf(chunk.trim())
                if (n > 0L) parsed << n
            } catch (Exception ignored) {
                // Silently drop malformed tokens — same policy as /verified.
            }
        }
        if (parsed.isEmpty()) return ResponseEntity.ok([:] as Map<Long, String>)
        if (parsed.size() > 200) parsed = parsed.take(200)

        Map<Long, String> out = [:]
        steamUserRepository.findAllById(parsed).each { user ->
            def url = user.avatarUrl
            if (url != null && !url.isBlank()) out[user.id] = url
        }
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=300')
            .body(out)
    }

    /**
     * GET /api/sellers/names?ids=1,2,3 — bulk displayName lookup.
     *
     * Before this, the Profile → Personal → Following row hydrated every
     * followed seller's display name via a separate `/api/listings/stall/{id}`
     * round-trip per seller — N+1 calls just to get a name, each one
     * returning the whole stall snapshot (listings + reviews + aggregates).
     * A user following 30 sellers fired 30 parallel stall fetches on
     * tab open. One round-trip here replaces all of them.
     *
     * Response: `{uid: "displayName"}`. Users without a displayName on
     * file (pre-sync or privacy) are absent. Public — display names
     * are already visible on every /stall/{id} page + in every listing
     * row, so there's no new PII disclosure. 5-minute browser cache
     * because display names rarely change.
     */
    @GetMapping('/names')
    ResponseEntity<Map<Long, String>> namesBulk(@RequestParam(required = false) String ids) {
        if (steamUserRepository == null || ids == null || ids.isBlank()) {
            return ResponseEntity.ok([:] as Map<Long, String>)
        }
        List<Long> parsed = []
        for (String chunk : ids.split(',')) {
            try {
                def n = Long.valueOf(chunk.trim())
                if (n > 0L) parsed << n
            } catch (Exception ignored) {
                // Silently drop malformed tokens — same policy as /avatars.
            }
        }
        if (parsed.isEmpty()) return ResponseEntity.ok([:] as Map<Long, String>)
        if (parsed.size() > 200) parsed = parsed.take(200)

        Map<Long, String> out = [:]
        steamUserRepository.findAllById(parsed).each { user ->
            def name = user.displayName
            if (name != null && !name.isBlank()) out[user.id] = name
        }
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=300')
            .body(out)
    }
}
