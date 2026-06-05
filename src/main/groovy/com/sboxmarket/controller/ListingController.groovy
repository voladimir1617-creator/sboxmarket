package com.sboxmarket.controller

import com.sboxmarket.dto.request.SellListingRequest
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.SellService
import com.sboxmarket.util.ListingEnums
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Thin HTTP adapter for listings. All business logic lives in services:
 *   - ListingService — queries / filters
 *   - PurchaseService — buy flow
 *   - SellService — list-from-inventory / cancel
 */
@RestController
@RequestMapping("/api/listings")
@Slf4j
class ListingController {

    @Autowired ListingService listingService
    @Autowired PurchaseService purchaseService
    @Autowired SellService sellService
    @Autowired WalletRepository walletRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired com.sboxmarket.service.TextSanitizer textSanitizer
    @Autowired(required = false) com.sboxmarket.service.ReviewService reviewService
    @Autowired(required = false) com.sboxmarket.service.SellerFollowService sellerFollowService
    @Autowired(required = false) com.sboxmarket.service.OfferService offerService
    @Autowired(required = false) com.sboxmarket.service.TradeService tradeService
    @Autowired(required = false) com.sboxmarket.service.UserBlockService userBlockService
    @Autowired(required = false) com.sboxmarket.service.NotificationService notificationService
    @Autowired(required = false) com.sboxmarket.repository.CartItemRepository cartItemRepository
    @Autowired(required = false) com.sboxmarket.repository.TradeRepository tradeRepository
    @Autowired(required = false) com.sboxmarket.service.security.AdminAuthorization adminAuthorization

    // Batch 659 — canonical enum lists + normaliser live in
    // `com.sboxmarket.util.ListingEnums` (shared with ItemController
    // and DatabaseController). Previous inline copies removed in
    // favour of the single source of truth.

    @GetMapping
    ResponseEntity<?> getListings(
            @RequestParam(required = false, defaultValue = "price_asc") String sort,
            @RequestParam(required = false, defaultValue = "All") String category,
            @RequestParam(required = false, defaultValue = "All") String rarity,
            // Take price bounds as strings and parse manually so a crafted
            // value like `1;SELECT 1` or `1'` becomes a 400 from us instead
            // of a Spring type-coercion 500. GlobalExceptionHandler also
            // traps MethodArgumentTypeMismatchException as a second line of
            // defence in case this parameter ever gets re-typed back to
            // BigDecimal by mistake.
            @RequestParam(required = false) String minPrice,
            @RequestParam(required = false) String maxPrice,
            @RequestParam(required = false) String search,
            // Batch 985 — accept `q` as an alias for `search`. Every
            // other search-capable endpoint (/api/database, /api/items,
            // /api/sellers/search, /api/notifications, /api/loadouts/public)
            // uses `q`; a user who copy-pastes `?q=Hat` from one of those
            // onto /api/listings got silently unfiltered results. Alias
            // preserves the existing `search` contract — only falls
            // through to `q` when `search` is null / blank.
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "All") String listingType,
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset,
            HttpServletRequest req
    ) {
        // Cap limit defensively — never let a client ask for the entire DB.
        // Cap is 100 (was 500): even once the catalogue grows, `size=99999`
        // can no longer dump every row in one shot.
        //
        // Null-check rather than the `?:` Elvis: `limit` / `offset` carry a
        // String defaultValue so Spring never hands us null, but `?limit=0`
        // arrives as a real Integer 0 — and 0 is Groovy-falsy, so `limit ?:
        // 100` would silently rewrite an explicit `?limit=0` into 100 and
        // hand back the full 100-row page instead of clamping to the
        // documented floor of 1. The same trap bit `?offset=0`, though
        // there the fallback happened to equal the floor so it was benign.
        int safeLimit = Math.min(Math.max(limit != null ? limit : 100, 1), 100)
        int safeOffset = Math.max(offset != null ? offset : 0, 0)
        // Batch 985 — fall through to `q` alias when `search` is null/
        // blank. Keeps the canonical param stable for the frontend + any
        // long-standing integrations, adds the common-convention fallback.
        if ((search == null || search.isBlank()) && q != null && !q.isBlank()) {
            search = q
        }
        // Cap search so a 100kB `search=AAAA…` can't turn into a full-table
        // LIKE scan. Whitelist sort / category / rarity so a crafted value
        // can't smuggle past the service-layer switch.
        if (search != null) {
            // Strip null bytes — Postgres rejects 0x00 in UTF-8 strings
            // with "invalid byte sequence for encoding UTF8". A crafted
            // `?search=%00` from a scanner triggers a 500 without this.
            search = search.replace('\u0000', '')
            if (search.length() > 100) search = search.substring(0, 100)
        }
        // Batch 661 — normalise sort case-insensitively so a share URL
        // carrying `sort=PRICE_ASC` doesn't fall through to the default
        // price_asc fallback on a flipped intent.
        if (sort != null) sort = sort.toLowerCase()
        if (sort != null && !(sort in ['price_asc','price_desc','newest','rarity','discount','ending_soon','popularity','views'])) sort = 'price_asc'
        if (category != null) category = category.replace('\u0000', '')
        if (rarity   != null) rarity   = rarity.replace('\u0000', '')
        if (category != null && category.length() > 40) category = 'All'
        if (rarity   != null && rarity.length()   > 40) rarity   = 'All'
        // Batch 657 / 659 — case-insensitive canonicalisation of enum
        // filters. `?category=hats` or `?rarity=standard` (lowercase)
        // matches despite the DB column being stored as 'Hats' /
        // 'Standard'. Unknown values fall through to 'All' (no filter).
        category = ListingEnums.canonEnum(category, ListingEnums.CATEGORIES, 'All')
        rarity   = ListingEnums.canonEnum(rarity,   ListingEnums.RARITIES,   'All')

        BigDecimal min = parsePriceParam(minPrice, "minPrice")
        BigDecimal max = parsePriceParam(maxPrice, "maxPrice")
        // Tolerate an inverted range — a user dragging the price slider
        // past itself, or a copy-pasted share URL with the bounds the
        // wrong way round, would otherwise hit `price >= min AND price
        // <= max` with min > max and silently get zero results with no
        // explanation. Swap so the obvious intent (a band between the
        // two numbers) is honoured instead of returning an empty grid.
        if (min != null && max != null && min > max) {
            def tmp = min; min = max; max = tmp
        }

        // Batch 656 / 659 — case-insensitive listing-type normaliser.
        // `auction`, `Auction`, `AUCTION` all map to AUCTION. Junk
        // values return null (filter disabled).
        def typeParam = ListingEnums.canonListingType(listingType)
        // Escape SQL LIKE wildcards (% _ \) so a search for a literal `_`
        // or `%` matches that character instead of every listing — the
        // findActivePublic JPQL carries a matching `ESCAPE '\'` clause.
        def searchEscaped = (search != null && !search.isEmpty()) ? escapeLike(search) : search
        def all = listingService.getActiveListings(sort, category, rarity, min, max, searchEscaped, typeParam)
        // Block-list filter (batch 345). Signed-in viewers don't see
        // listings from sellers they've blocked. Applied AFTER the
        // service query — the block set is a small per-user thing (cap
        // 100) so filtering in the controller is fine; pushing it down
        // to SQL would require threading the viewer id through every
        // service entrypoint and isn't worth the complexity for a 100-
        // id IN clause on an already-paginated result.
        def viewer = req?.session?.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (viewer != null && userBlockService != null) {
            def blockedIds = userBlockService.blockedIdsFor(viewer)
            if (!blockedIds.isEmpty()) {
                def blockedSet = new HashSet<>(blockedIds)
                all = all.findAll { l ->
                    l.sellerUserId == null || !blockedSet.contains(l.sellerUserId)
                }
            }
        }
        def page = all.drop(safeOffset).take(safeLimit)
        decorateWithSellerRating(page)
        // Batch 807 — Cache-Control on the main marketplace list. The
        // response varies by viewer (blocked-seller filter at line 119
        // above) so shared caches would leak; `private` keeps it in the
        // browser only. 10s is a compromise — long enough that rapid
        // back-and-forth between an item modal and the grid skips a
        // round-trip, short enough that a newly-posted listing shows up
        // within the next refresh tick. The 30s soft-poll in app.js
        // already triggers a fresh fetch after the cache window, so the
        // effective staleness remains identical for users who stay on
        // the page.
        def cache = 'private, max-age=10'
        // Return the array directly when no pagination params were used (back-compat with
        // existing frontend); when limit/offset are present, return a PageResponse.
        if (limit == 100 && offset == 0) {
            return ResponseEntity.ok().header('Cache-Control', cache).body(page)
        }
        ResponseEntity.ok()
            .header('Cache-Control', cache)
            .body([items: page, total: all.size(), limit: safeLimit, offset: safeOffset])
    }

    /**
     * Safe parser for a price query param. Returns null for blank, throws a
     * BadRequestException (→ 400) for anything that isn't a non-negative
     * decimal within a reasonable range. This is the chokepoint that kills
     * the SQL-probe DoS reported in the security audit.
     */
    private BigDecimal parsePriceParam(String raw, String field) {
        if (raw == null || raw.isEmpty()) return null
        if (raw.length() > 16) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_PARAMETER",
                "Invalid value for parameter '${field}'")
        }
        // Must match a plain decimal — no semicolons, quotes, SQL keywords.
        if (!raw.matches(/^\d{1,10}(\.\d{1,2})?$/)) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_PARAMETER",
                "Invalid value for parameter '${field}'")
        }
        try {
            def bd = new BigDecimal(raw)
            if (bd < BigDecimal.ZERO || bd > new BigDecimal("10000000")) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_PARAMETER",
                    "Invalid value for parameter '${field}'")
            }
            return bd
        } catch (NumberFormatException ignored) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_PARAMETER",
                "Invalid value for parameter '${field}'")
        }
    }

    @GetMapping("/item/{itemId}")
    ResponseEntity<List<Listing>> getForItem(@PathVariable Long itemId, HttpServletRequest req) {
        // Block-list filter applies here too (batch 347) — a buyer viewing
        // an item's listings panel shouldn't see the blocked seller's
        // row even though they got to the item via a catalog link. The
        // stall page itself (/listings/stall/{id}) stays unfiltered
        // because that's a direct visit — the user is actively choosing
        // to look at that seller's shop.
        // Batch 807 — `private, max-age=15`. Per-viewer (blocklist
        // filter) so shared caches would leak; 15s is tight because
        // the item modal's "live" feel depends on catching a sold-out
        // state promptly. Matches /api/listings cadence.
        def rows = filterBlocked(listingService.getListingsForItem(itemId), req)
        decorateWithSellerRating(rows)
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=15')
            .body(rows)
    }

    /** "More from this seller" rail — other visible active listings
     *  from the same seller, excluding the item the user is currently
     *  looking at. Public endpoint; caller passes the seller id + the
     *  currently-open item id. `limit` clamped to [1, 30] server-side. */
    @GetMapping("/seller/{sellerUserId}/other")
    ResponseEntity<List<Listing>> otherFromSeller(@PathVariable Long sellerUserId,
                                                  @RequestParam Long excludeItemId,
                                                  @RequestParam(required = false, defaultValue = "8") Integer limit) {
        // Explicit null-check, not Elvis — `?limit=0` is a legitimate
        // "no other listings please" request; `?: 8` treats 0 as falsy.
        // Same bug class as 0d15de2 / ccfe0b5 / 4e1a0d4 / 8224a9b.
        def rows = listingService.findOtherActiveBySeller(sellerUserId, excludeItemId,
            limit != null ? limit : 8)
        decorateWithSellerRating(rows)
        // Batch 807 — public cache: seller's own listings don't vary by
        // the viewer (no blocklist — if the viewer has blocked this
        // seller they wouldn't reach this rail). 60s matches the other
        // catalog-scoped aggregates.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(rows)
    }

    @GetMapping("/{id}")
    ResponseEntity<Listing> getById(@PathVariable Long id, HttpServletRequest req) {
        Listing listing
        try {
            listing = listingService.getById(id)
        } catch (NoSuchElementException ignored) {
            throw new NotFoundException("Listing", id)
        }
        // Hidden-listing leak guard. PurchaseService.buy, OfferService.makeOffer
        // and CartService.add all reject hidden rows so a scraped/cached id can't
        // round-trip a seller's pulled-off-market listing into a purchase. This
        // endpoint was the back door — anyone walking /api/listings/{1..N} could
        // pull a hidden listing's full payload (price, description, seller id,
        // maxDiscount, etc) even though it's deliberately invisible on every
        // grid / rail / stall surface. Treat as 404 for non-owners (and non-
        // admins): the resource may exist in the DB but it is not addressable
        // from outside. The seller themselves still sees their own row so the
        // MyStall edit path keeps working off this endpoint.
        if (Boolean.TRUE.equals(listing.hidden)) {
            def viewer = req?.session?.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
            boolean isOwner = (viewer != null && viewer == listing.sellerUserId)
            // Short-circuit on owner BEFORE the admin probe so the owner-fetch
            // path doesn't burn an isAdmin() DB round-trip on every MyStall
            // edit-modal open (the common case). Also matters for the regression
            // spec, which pins `0 * adminAuthorization.isAdmin(_)` on the owner
            // path — if the owner branch unconditionally evaluated isAdmin in
            // the same boolean expression, Spock would see one too many calls.
            if (!isOwner) {
                boolean isAdmin = (viewer != null && adminAuthorization != null
                    && adminAuthorization.isAdmin(viewer))
                if (!isAdmin) {
                    throw new NotFoundException("Listing", id)
                }
            }
        }
        // Mirror the marketplace-list cache (line 176): same 10s private window
        // so the single-listing view and the grid stay CONSISTENT — previously
        // the list was cached 10s but this was uncached, so right after a bid
        // the grid showed a stale currentBid while this endpoint showed the
        // fresh one (a jarring divergence). `private` (varies by viewer via the
        // hidden-listing owner/admin gate above), and the live auction view is
        // kept fresh by the SSE bus + the buy-path PRICE_CHANGED re-validation,
        // so a ≤10s display lag here carries no money risk.
        ResponseEntity.ok().header('Cache-Control', 'private, max-age=10').body(listing)
    }

    @GetMapping("/stats")
    ResponseEntity<Map> getMarketStats() {
        // 60-second browser cache — the homepage MarketStatsStrip polls
        // every 5 minutes, so a 60s browser cache is well under the
        // natural refetch cadence and cuts one round-trip per user open
        // the market hero within the poll window. Viewer-agnostic
        // (platform-wide aggregates only) so `public` is safe.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(listingService.getMarketStats())
    }

    /** Active listings owned by the current Steam user (= "My Stall"). */
    @GetMapping("/my-stall")
    ResponseEntity<List<Listing>> myStall(HttpServletRequest req) {
        def userId = requireUser(req)
        // Batch 1033 — display cap at 500 rows on the MyStall tab.
        // A prolific seller could otherwise force thousands of JOIN-
        // FETCHed listings over the wire on every MyStall open. 500
        // is generous — CSFloat caps active listings per seller at
        // 100, Steam at 500 — but still puts a hard ceiling on the
        // payload. X-Total-Count header feeds the overflow banner.
        def rows = listingService.findActiveBySeller(userId, 500)
        long total = listingService.countActiveBySeller(userId)
        ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .body(rows)
    }

    /** Seller earnings summary for the MyStall header (batch 605).
     *  Returns gross revenue and sold counts for lifetime, 30d, and 7d
     *  windows. "Gross" = listing price the buyer paid; the net-of-fee
     *  figure is already visible via the wallet transaction ledger, so
     *  this endpoint deliberately shows the top-line number sellers
     *  think of as "revenue". Signed-in only. */
    @GetMapping("/my-stall/earnings")
    ResponseEntity<Map> myStallEarnings(HttpServletRequest req) {
        def userId = requireUser(req)
        def now = System.currentTimeMillis()
        def since24h = now - 24L * 60L * 60L * 1000L
        def since30d = now - 30L * 24L * 60L * 60L * 1000L
        def since7d  = now - 7L  * 24L * 60L * 60L * 1000L
        def repo = listingService.listingRepository
        def lifetime = repo.sumRevenueBySeller(userId) ?: java.math.BigDecimal.ZERO
        // Batch 872 — 24h chip. Gives sellers a fresh "today's haul"
        // reading alongside the 7d/30d/lifetime windows. Uses the
        // same sum-since query shape; no new SQL.
        def rev24    = repo.sumRevenueBySellerSince(userId, since24h) ?: java.math.BigDecimal.ZERO
        def rev30    = repo.sumRevenueBySellerSince(userId, since30d) ?: java.math.BigDecimal.ZERO
        def rev7     = repo.sumRevenueBySellerSince(userId, since7d)  ?: java.math.BigDecimal.ZERO
        def soldLife = repo.countSoldBySeller(userId)
        def sold24   = repo.countSoldBySellerSince(userId, since24h)
        def sold30   = repo.countSoldBySellerSince(userId, since30d)
        def sold7    = repo.countSoldBySellerSince(userId, since7d)
        // Batch 709 — lifetime platform-fee total. Surfaces alongside
        // gross revenue so sellers see "you paid $X in fees across
        // your lifetime" for tax prep + fairness auditing. Only
        // VERIFIED (settled) trades count.
        def lifetimeFees = tradeRepository != null
            ? (tradeRepository.sumFeesBySeller(userId) ?: java.math.BigDecimal.ZERO)
            : java.math.BigDecimal.ZERO
        ResponseEntity.ok([
            lifetimeRevenue: lifetime.setScale(2, java.math.RoundingMode.HALF_UP),
            revenue30d:      rev30.setScale(2, java.math.RoundingMode.HALF_UP),
            revenue7d:       rev7.setScale(2, java.math.RoundingMode.HALF_UP),
            revenue24h:      rev24.setScale(2, java.math.RoundingMode.HALF_UP),
            soldLifetime:    soldLife,
            sold30d:         sold30,
            sold7d:          sold7,
            sold24h:         sold24,
            lifetimeFees:    lifetimeFees.setScale(2, java.math.RoundingMode.HALF_UP)
        ])
    }

    /**
     * Per-listing analytics for the seller's MyStall. Returns one row per
     * active listing with view-count, watchlist-stars, and a 30-day sales
     * count for the same item across the whole marketplace (so the seller
     * can see "this category is hot, my price is competitive"). Drives the
     * "Analytics" tab in the MyStall UI — operator gap from
     * `production_checklist.md` ("seller-stall analytics").
     *
     * Hard-cap at 500 listings (matches `/my-stall`) so a prolific seller
     * doesn't dump every row on every render. Cheap because viewCount +
     * starredCount come straight from the listing/item rows; the 30-day
     * sales count is one GROUP BY against an indexed soldAt range.
     */
    @GetMapping("/my-stall/analytics")
    ResponseEntity<List<Map>> myStallAnalytics(HttpServletRequest req) {
        def userId = requireUser(req)
        def rows = listingService.findActiveBySeller(userId, 500)
        if (rows == null || rows.isEmpty()) {
            // Cache-Control owned by CorrelationIdFilter (isMyStall predicate).
            // Pre-fix the controller emitted `private, max-age=30` here, but
            // once the filter recognised /api/listings/my-stall as private
            // the two headers stacked. Filter's no-store wins anyway because
            // ResponseEntity.header() APPENDS rather than replaces.
            return ResponseEntity.ok().body([] as List<Map>)
        }
        long since30d = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        // Bulk fetch 30-day item-level sales so we don't N+1 across
        // every listing row. Group by itemId, count sold rows since.
        def itemIds = rows.collect { it.item?.id }.findAll { it != null }.unique()
        Map<Long, Long> sales30d = [:]
        if (!itemIds.isEmpty()) {
            try {
                def soldRows = listingService.listingRepository.countSoldByItemsSince(itemIds, since30d)
                soldRows?.each { r -> sales30d[(r[0] as Long)] = (r[1] ?: 0L) as Long }
            } catch (Exception e) {
                log.debug("countSoldByItemsSince failed: ${e.message}")
            }
        }
        def out = rows.collect { l ->
            def item = l.item
            long itemSales30d = sales30d[(item?.id as Long)] ?: 0L
            BigDecimal floorPrice = item?.lowestPrice
            BigDecimal myPrice = l.price ?: BigDecimal.ZERO
            // Competitiveness chip: how many cents above the marketplace floor?
            BigDecimal floorDelta = (floorPrice != null && floorPrice > BigDecimal.ZERO)
                ? (myPrice - floorPrice).setScale(2, java.math.RoundingMode.HALF_UP)
                : null
            [
                listingId:    l.id,
                itemId:       item?.id,
                itemName:     item?.name,
                itemRarity:   item?.rarity,
                category:     item?.category,
                imageUrl:     item?.imageUrl,
                price:        myPrice.setScale(2, java.math.RoundingMode.HALF_UP),
                floorPrice:   floorPrice,
                floorDelta:   floorDelta,
                viewCount:    item?.viewCount ?: 0,
                supply:       item?.supply ?: 0,
                // Two missing-property bugs were paired here pre-fix:
                // `l.kind` (no such field; the actual column is
                // `listingType`) and `l.createdAt` (no such field;
                // listing timestamps live in `listedAt`). Either one
                // threw MissingPropertyException, which the global
                // error handler surfaced as a 500 on the analytics tab
                // for any seller with ≥1 active listing. Frontend
                // doesn't read these today but they're documented in
                // the response contract — keep them, fix the access.
                listingType:  l.listingType,
                listedAt:     l.listedAt,
                itemSales30d: itemSales30d
            ]
        }
        // Cache-Control owned by CorrelationIdFilter (isMyStall predicate).
        // Pre-fix the controller emitted `private, max-age=30` here. Once
        // the filter recognised /api/listings/my-stall as private the two
        // headers stacked. Filter's no-store wins anyway because
        // ResponseEntity.header() APPENDS rather than replaces.
        ResponseEntity.ok().body(out)
    }

    /** Sale history for the current user — drives the "Sold" tab in the
     *  MyStall UI. Hard-capped at 200 rows so a prolific seller doesn't
     *  dump their entire history on every page render. */
    @GetMapping("/my-stall/sold")
    ResponseEntity<List<Listing>> myStallSold(HttpServletRequest req) {
        def userId = requireUser(req)
        def rows = listingService.findSoldBySeller(
            userId, org.springframework.data.domain.PageRequest.of(0, 200))
        // Batch 1032 — X-Total-Count header so the MyStall Sold tab
        // can render the same "Showing most recent 200 of N" banner
        // the Trades / Buy Orders tabs use once a seller crosses
        // the cap. Counted via the existing indexed countSoldBySeller
        // query — no extra scan.
        long total = listingService.countSoldBySeller(userId)
        ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .body(rows)
    }

    /** CSV dump of the seller's sale history — one row per settled
     *  listing. Complements the wallet-transactions CSV: that one
     *  shows the money-movement ledger, this one shows "which of my
     *  listings sold, and when". Capped at 1000 rows so an extremely
     *  prolific seller gets a reasonable file; buyers are redacted
     *  to just the truncated buyer id so sellers can reconcile
     *  against their own records without leaking contact-level PII. */
    @GetMapping(value = "/my-stall/sold.csv", produces = "text/csv")
    ResponseEntity<String> myStallSoldCsv(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            HttpServletRequest req) {
        def userId = requireUser(req)
        def rows = listingService.findSoldBySeller(
            userId, org.springframework.data.domain.PageRequest.of(0, 1000))
        // Date-range filter (epoch ms, inclusive). Filters by `soldAt` since
        // this is the sold-stall export — a seller pulling Q1 sales for tax
        // wants the slice "sold between Jan 1 and Mar 31", not "listed in".
        if (from != null) rows = rows.findAll { (it.soldAt ?: 0L) >= from }
        if (to   != null) rows = rows.findAll { (it.soldAt ?: 0L) <= to   }
        // Batch 979 — switch to CsvUtil.safeCell so item names containing
        // formula-trigger first chars (=, +, -, @, \t, \r) don't run as
        // spreadsheet formulas when the seller opens their export. Item
        // names come from Steam Workshop uploads — not sboxmarket-
        // controlled input, so defence-in-depth matters.
        def esc = com.sboxmarket.util.CsvUtil.&safeCell
        def sb = new StringBuilder()
        sb.append("listing_id,item_id,item_name,category,rarity,listing_type,price,sold_at,buyer_hint\n")
        rows.each { l ->
            sb.append(l.id).append(',')
              .append(l.item?.id ?: '').append(',')
              .append(esc(l.item?.name ?: '')).append(',')
              .append(esc(l.item?.category ?: '')).append(',')
              .append(esc(l.item?.rarity ?: '')).append(',')
              .append(esc(l.listingType ?: 'BUY_NOW')).append(',')
              .append(l.price?.toPlainString() ?: '').append(',')
              .append(l.soldAt ?: '').append(',')
              .append(l.buyerUserId != null ? ("user_" + l.buyerUserId) : '')
              .append('\n')
        }
        // Cache-Control: applied by CorrelationIdFilter via the
        // isMyStall predicate (filter line ~146). Setting it here would
        // emit two conflicting Cache-Control values.
        ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"mystall-sold.csv\"")
            .header("Content-Type", "text/csv; charset=utf-8")
            .body(sb.toString())
    }

    /** CSV dump of the seller's per-listing analytics — one row per
     *  ACTIVE listing with viewCount / floor delta / 30-day demand /
     *  supply. Complements the in-app Analytics tab so a seller running
     *  >50 listings can rank-sort offline in Excel, build pivot tables
     *  on category-level demand, or feed the data into a re-pricing
     *  pipeline. Same 500-row cap as the /my-stall/analytics JSON
     *  endpoint above — the underlying `findActiveBySeller(userId,
     *  500)` query is the bound for both surfaces. */
    @GetMapping(value = "/my-stall/analytics.csv", produces = "text/csv")
    ResponseEntity<String> myStallAnalyticsCsv(HttpServletRequest req) {
        def userId = requireUser(req)
        def rows = listingService.findActiveBySeller(userId, 500)
        // Same bulk demand probe as the JSON endpoint — one indexed
        // GROUP BY on `soldAt`, NEVER per-row. Stays cheap as the seller's
        // active count grows toward the 500 cap.
        long since30d = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        Map<Long, Long> sales30d = [:]
        if (rows && !rows.isEmpty()) {
            def itemIds = rows.collect { it.item?.id }.findAll { it != null }.unique()
            if (!itemIds.isEmpty()) {
                try {
                    def soldRows = listingService.listingRepository.countSoldByItemsSince(itemIds, since30d)
                    soldRows?.each { r -> sales30d[(r[0] as Long)] = (r[1] ?: 0L) as Long }
                } catch (Exception e) {
                    log.debug("countSoldByItemsSince failed in analytics.csv: ${e.message}")
                }
            }
        }
        // Batch 979 — shared csv-safe escape so item names with =/+/-/@
        // don't run as Excel formulas when the seller opens the file.
        def esc = com.sboxmarket.util.CsvUtil.&safeCell
        def sb = new StringBuilder()
        sb.append("listing_id,item_id,item_name,category,rarity,listing_type,my_price,floor_price,floor_delta,view_count,supply,item_sales_30d,listed_at\n")
        rows?.each { l ->
            def item = l.item
            BigDecimal myPrice = l.price ?: BigDecimal.ZERO
            BigDecimal floorPrice = item?.lowestPrice
            BigDecimal floorDelta = (floorPrice != null && floorPrice > BigDecimal.ZERO)
                ? (myPrice - floorPrice).setScale(2, java.math.RoundingMode.HALF_UP)
                : null
            long itemSales30d = sales30d[(item?.id as Long)] ?: 0L
            sb.append(l.id).append(',')
              .append(item?.id ?: '').append(',')
              .append(esc(item?.name ?: '')).append(',')
              .append(esc(item?.category ?: '')).append(',')
              .append(esc(item?.rarity ?: '')).append(',')
              .append(esc(l.listingType ?: 'BUY_NOW')).append(',')
              .append(myPrice.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()).append(',')
              .append(floorPrice != null ? floorPrice.toPlainString() : '').append(',')
              .append(floorDelta != null ? floorDelta.toPlainString() : '').append(',')
              .append(item?.viewCount ?: 0).append(',')
              .append(item?.supply ?: 0).append(',')
              .append(itemSales30d).append(',')
              .append(l.listedAt ?: '')
              .append('\n')
        }
        // Cache-Control: applied by CorrelationIdFilter via isMyStall.
        ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"mystall-analytics.csv\"")
            .header("Content-Type", "text/csv; charset=utf-8")
            .body(sb.toString())
    }

    /** CSV dump of the seller's ACTIVE listings (batch 681). Complements
     *  the sold.csv export — this one's the current-state inventory
     *  manifest ("here's what I have on the market right now"), useful
     *  for sellers exporting to a spreadsheet to bulk-reprice offline
     *  or reconcile against their own inventory system. Capped at 1000
     *  rows like the sold variant so a prolific seller's file stays
     *  sane. Hidden listings included (the seller owns them) but clearly
     *  marked in the CSV via the `hidden` column. */
    @GetMapping(value = "/my-stall/active.csv", produces = "text/csv")
    ResponseEntity<String> myStallActiveCsv(HttpServletRequest req) {
        def userId = requireUser(req)
        def rows = listingService.findActiveBySeller(userId).take(1000)
        // Batch 979 — shared csv-safe escape; see CsvUtil.safeCell.
        def esc = com.sboxmarket.util.CsvUtil.&safeCell
        def sb = new StringBuilder()
        sb.append("listing_id,item_id,item_name,category,rarity,listing_type,price,listed_at,expires_at,hidden,bid_count,description\n")
        rows.each { l ->
            sb.append(l.id).append(',')
              .append(l.item?.id ?: '').append(',')
              .append(esc(l.item?.name ?: '')).append(',')
              .append(esc(l.item?.category ?: '')).append(',')
              .append(esc(l.item?.rarity ?: '')).append(',')
              .append(esc(l.listingType ?: 'BUY_NOW')).append(',')
              .append(l.price?.toPlainString() ?: '').append(',')
              .append(l.listedAt ?: '').append(',')
              .append(l.expiresAt ?: '').append(',')
              .append(Boolean.TRUE.equals(l.hidden) ? 'true' : 'false').append(',')
              .append(l.bidCount ?: 0).append(',')
              .append(esc(l.description ?: ''))
              .append('\n')
        }
        // Cache-Control: applied by CorrelationIdFilter via isMyStall.
        ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"mystall-active.csv\"")
            .header("Content-Type", "text/csv; charset=utf-8")
            .body(sb.toString())
    }

    /** Public recent sales for a seller — drives the "Recent sales" strip
     *  below the stall grid. Buyer-privacy safe: returns price + soldAt
     *  + item fields only, with no buyer identities. Capped at 10 to
     *  match the item-modal recent-sales strip. */
    @GetMapping("/stall/{userId}/recent-sales")
    ResponseEntity<List<Map>> publicStallSold(@PathVariable Long userId,
                                              @RequestParam(required = false) Integer limit) {
        // Default 10 for the existing strip. Callers wanting a richer
        // sample (e.g. the 30-day sparkline on the stall page, batch 362)
        // can request up to 200 rows. Clamped server-side so a crafted
        // ?limit=10000 can't sweep the seller's full sale history.
        int lim = Math.min(Math.max(limit ?: 10, 1), 200)
        def rows = listingService.findSoldBySeller(userId,
            org.springframework.data.domain.PageRequest.of(0, lim))
        def out = rows.collect { l ->
            [
                listingId: l.id,
                price:     l.price,
                soldAt:    l.soldAt,
                listingType: l.listingType,
                item: l.item == null ? null : [
                    id:         l.item.id,
                    name:       l.item.name,
                    category:   l.item.category,
                    rarity:     l.item.rarity,
                    imageUrl:   l.item.imageUrl,
                    iconEmoji:  l.item.iconEmoji
                ]
            ]
        }
        // Per-seller sold list is viewer-agnostic — sold rows are
        // stable and no per-user filter applies. 60s browser cache
        // on the stall-page open-close flow.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(out)
    }

    /** Bulk-adjust the user's active listings by a percentage. Positive
     *  percent = markup, negative = discount. Auction listings are skipped
     *  — changing an auction's starting price mid-auction is confusing UX
     *  and also opens a bid-fairness question, so we stay conservative.
     *  Hard-clamps the resulting price to > $0 and ≤ $100k like the
     *  single-listing editor. Returns the new total active count + the
     *  number of rows actually touched. */
    @PutMapping("/my-stall/bulk-adjust")
    ResponseEntity<Map> bulkAdjustStall(@RequestBody Map body, HttpServletRequest req) {
        def userId = requireUser(req)
        // Don't fall through `?:` to '' — Groovy treats numeric 0 as
        // falsy, so a legitimate `{"percent": 0}` (no-op bulk adjust)
        // collapsed to empty-string → NumberFormatException → 400
        // INVALID_PERCENT, while `{"percent": "0"}` (string) worked.
        // Same Elvis-on-zero class as ccfe0b5 / 4e1a0d4 / 0d15de2.
        // Explicit null check so 0 reaches BigDecimal cleanly.
        def rawPct = body?.percent
        if (rawPct == null) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_PERCENT",
                "percent is required (e.g. -5 for a 5% discount)")
        }
        BigDecimal pct
        try {
            pct = new BigDecimal(rawPct.toString())
        } catch (NumberFormatException ignored) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_PERCENT",
                "percent must be a number (e.g. -5 for a 5% discount)")
        }
        if (pct.abs() > new BigDecimal('50')) {
            throw new com.sboxmarket.exception.BadRequestException("PERCENT_TOO_LARGE",
                "Single adjustment capped at ±50% — split larger changes across multiple passes")
        }
        def result = listingService.bulkAdjustPrices(userId, pct)
        ResponseEntity.ok(result)
    }

    /** Bulk-cancel every active listing the caller owns (batch 375).
     *  Auctions with live bids are skipped by default — pass
     *  `?includeAuctions=true` to cancel those too (fans out AUCTION_CANCELLED
     *  to every bidder). Returns `{cancelled, skippedAuctions, failed}`. */
    @DeleteMapping("/my-stall")
    ResponseEntity<Map> bulkCancelStall(@RequestParam(required = false, defaultValue = "false") Boolean includeAuctions,
                                        HttpServletRequest req) {
        def userId = requireUser(req)
        ResponseEntity.ok(sellService.cancelAllActive(userId, Boolean.TRUE.equals(includeAuctions)))
    }

    /** Newest active listings — drives the homepage "Just listed" rail.
     *  Public, excludes hidden listings via the service's visible-seller
     *  filter where applicable, capped at 20 rows. Buyers love fresh
     *  inventory; this is the "what just dropped" surface. */
    @GetMapping("/just-listed")
    ResponseEntity<List<Listing>> justListed(HttpServletRequest req) {
        def rows = filterBlocked(listingService.findNewestActive(20), req)
        decorateWithSellerRating(rows)
        // `private` — filterBlocked varies by viewer. 30s cache because
        // "just listed" is a freshness surface; longer caches would
        // defeat the "just" part.
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=30')
            .body(rows)
    }

    /** Top deals rail — active BUY_NOW listings priced furthest below
     *  the catalogue steamPrice, sorted by deepest %. Public endpoint,
     *  capped at 12 rows. Drives the homepage deal-hunter surface. */
    @GetMapping("/top-deals")
    ResponseEntity<List<Listing>> topDeals(HttpServletRequest req) {
        def rows = filterBlocked(listingService.findTopDeals(12), req)
        decorateWithSellerRating(rows)
        // `private` not `public` — filterBlocked varies by viewer
        // (per-session block list). 60s is under the homepage rail's
        // refetch cadence while still catching a just-listed steal.
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=60')
            .body(rows)
    }

    /**
     * Block-filter helper (batch 345/346). Every listing-returning
     * endpoint that the grid or a homepage rail calls should run its
     * result through this so a blocked seller's rows never reach the
     * blocker's UI. Anonymous callers + callers who haven't blocked
     * anyone skip the filter entirely — a single session lookup +
     * one repo probe per request.
     */
    /** Escape SQL LIKE special characters so a user-typed `_` / `%` / `\`
     *  is matched literally instead of as a wildcard. Backslash first so
     *  the escapes we add aren't themselves re-escaped. Pairs with the
     *  `ESCAPE '\'` clause on `ListingRepository.findActivePublic`. */
    private static String escapeLike(String s) {
        if (s == null) return null
        s.replace('\\', '\\\\')
         .replace('%', '\\%')
         .replace('_', '\\_')
    }

    private List<Listing> filterBlocked(List<Listing> rows, HttpServletRequest req) {
        if (rows == null || rows.isEmpty()) return rows
        def viewer = req?.session?.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (viewer == null || userBlockService == null) return rows
        def blocked = userBlockService.blockedIdsFor(viewer)
        if (blocked.isEmpty()) return rows
        def set = new HashSet<>(blocked)
        rows.findAll { l -> l.sellerUserId == null || !set.contains(l.sellerUserId) }
    }

    /**
     * Bulk-attach seller review summary (avg rating + review count) onto
     * every listing in the given list. Single GROUP BY across all unique
     * sellerUserId values — never per-row. Csfloat-parity: every listing
     * row should show "★ 4.7 (23)" next to the seller name without a
     * follow-up API call.
     *
     * Silent no-op when reviewService is unavailable (test contexts where
     * the bean isn't registered).
     */
    private void decorateWithSellerRating(List<Listing> rows) {
        if (rows == null || rows.isEmpty() || reviewService == null) return
        def sellerIds = rows.collect { it.sellerUserId }.findAll { it != null }.toSet()
        if (sellerIds.isEmpty()) return
        def summaries = reviewService.summariesForUsers(sellerIds)
        if (summaries == null || summaries.isEmpty()) return
        rows.each { l ->
            def s = l.sellerUserId == null ? null : summaries[l.sellerUserId]
            if (s != null) {
                l.sellerRating       = s.average == null ? null : (s.average as Double)
                l.sellerReviewCount  = (s.count as Number)?.intValue()
            }
        }
        // V61 — same single-pass decoration for `sellerLastSeenAt`. Bulk
        // SELECT covers every distinct seller in this list so the whole
        // marketplace grid lights up real "Online now" presence dots
        // without a per-row hit. Null sellerUserId rows (system seed
        // listings) keep the deterministic-seed fallback on the client.
        decorateWithSellerLastSeen(rows, sellerIds)
    }

    /** V61 — bulk-attach `sellerLastSeenAt` (epoch ms) onto every listing
     *  that has a non-null sellerUserId. Single SELECT against
     *  steam_users by PK. Silent no-op on empty input or when the
     *  steamUserRepository bean isn't registered (test contexts). The
     *  ~private overload accepts the already-computed sellerIds set so
     *  decorateWithSellerRating doesn't recompute it. */
    private void decorateWithSellerLastSeen(List<Listing> rows, Set<Long> sellerIds = null) {
        if (rows == null || rows.isEmpty() || steamUserRepository == null) return
        def ids = sellerIds ?: rows.collect { it.sellerUserId }.findAll { it != null }.toSet()
        if (ids.isEmpty()) return
        def pairs = steamUserRepository.findLastSeenAtByIds(ids as Collection<Long>)
        if (pairs == null || pairs.isEmpty()) return
        Map<Long, Long> byId = [:]
        pairs.each { Object[] p ->
            if (p?.length >= 2 && p[0] != null) {
                byId[(Long) p[0]] = p[1] == null ? null : ((Number) p[1]).longValue()
            }
        }
        rows.each { l ->
            if (l.sellerUserId != null) {
                l.sellerLastSeenAt = byId[l.sellerUserId]
            }
        }
    }

    /** Top sellers rail — aggregates sold counts and surfaces the most
     *  active sellers for homepage social proof. Public, capped at 8
     *  rows so the payload stays tiny. Response includes enough data for
     *  the UI to link to /stall/:id and render an avatar + name + stats. */
    @GetMapping("/top-sellers")
    ResponseEntity<List<Map>> topSellers() {
        def rows = listingService.topSellers(5L, 8)
        // Bulk-hydrate every seller user + rating summary in two queries
        // instead of one findById + one summaryForUser per row (was ~2N
        // round-trips). steam_users by PK + the aggregate GROUP BY.
        def sellerIds = rows.collect { it.userId }.findAll { it != null }.toSet()
        def usersById = sellerIds.isEmpty() ? [:]
            : steamUserRepository.findAllById(sellerIds).collectEntries { [(it.id): it] }
        def summariesById = (reviewService != null && !sellerIds.isEmpty())
            ? (reviewService.summariesForUsers(sellerIds) ?: [:]) : [:]
        def out = rows.collect { r ->
            def user = usersById[r.userId]
            if (user == null) return null
            // Batch 352 — banned sellers shouldn't appear on the homepage
            // "Top Sellers" rail. Filter at render time so a staff ban
            // immediately removes them from the leaderboard without
            // needing to recompute the underlying aggregate.
            if (Boolean.TRUE.equals(user.banned)) return null
            def ratingSummary = summariesById[user.id] ?: [count: 0, average: null]
            [
                id:          user.id,
                displayName: user.displayName,
                avatarUrl:   user.avatarUrl,
                soldCount:   r.soldCount,
                joinedAt:    user.createdAt,
                rating:      ratingSummary,
                verified:    r.soldCount >= 10L &&
                             ((ratingSummary.count ?: 0) == 0 ||
                              ((ratingSummary.average ?: 0.0) as double) >= 4.0d)
            ]
        }.findAll { it != null }
        // Public aggregate — no viewer-specific data. 2-min cache:
        // the top-sellers ranking is driven by lifetime sold counts
        // which shift slowly, so this is generous stale tolerance.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=120')
            .body(out)
    }

    /** Auctions ending within the next hour (or custom window). Powers the
     *  homepage "Ending soon" rail — high-signal surface for the buying
     *  audience since the bid pressure is about to peak. Public endpoint,
     *  returns at most 20 rows, excludes hidden listings. */
    @GetMapping("/ending-soon")
    ResponseEntity<List<Listing>> endingSoon(@RequestParam(required = false) Long withinMs,
                                             HttpServletRequest req) {
        def now = System.currentTimeMillis()
        // Default: 60 minutes. Caller can shrink (e.g. to 15 min) to pull
        // the "hottest" few. Cap at 24 h so a malicious caller can't sweep
        // the whole auction inventory by passing a huge window.
        def window = withinMs != null ? Math.min(withinMs, 24L * 60 * 60 * 1000) : 60L * 60 * 1000
        def deadline = now + window
        def rows = filterBlocked(listingService.findAuctionsEndingBefore(now, deadline).take(20), req)
        decorateWithSellerRating(rows)
        // `private` — filterBlocked varies by viewer. 30s cap because
        // auctions ending soon change state fast (outbid, extended by
        // anti-snipe, closed on sale) — a longer cache would show
        // "ending in 2m" for a listing that just closed.
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=30')
            .body(rows)
    }

    /**
     * Public stall view — everyone can see a seller's active listings. Hidden
     * listings (stall-privacy mode) are excluded by the service-level filter.
     *
     * Public contract: returns display name + avatar + sold count +
     * response/ship stats + rating summary + blocked flag (for the
     * viewer) + steamId64 + profileUrl. `steamId64` and `profileUrl`
     * are EXPOSED on purpose (batch 726) — CSFloat-style trust signal
     * so buyers can click through to the seller's Steam profile and
     * vet account age + badges + friends before committing. They are
     * NULL'd out for banned accounts so a suspended seller's Steam
     * page doesn't keep receiving traffic from our UI.
     * URL: GET /api/listings/stall/{userId}
     */
    @GetMapping("/stall/{userId}")
    ResponseEntity<Map> publicStall(@PathVariable Long userId, HttpServletRequest req) {
        def user = steamUserRepository.findById(userId).orElse(null)
        // 200 + `{notFound: true}` sentinel for unknown user id instead
        // of a hard 404. Chrome auto-logs every fetch 404 to console
        // regardless of how the SPA handles it, so a dead /stall/{id}
        // share link spammed the console with a phantom error that
        // read as a bug. The SPA's `fetchPublicStall` maps the sentinel
        // back to null and renders the branded "Stall not found" empty
        // state. Cache-Control is no-cache so a later signup with this
        // user id isn't masked by a stale cached sentinel.
        if (user == null) {
            return ResponseEntity.ok()
                .header('Cache-Control', 'no-cache, must-revalidate')
                .body([notFound: true, id: userId])
        }
        // Batch 1043 — cap the public stall at 500 visible listings
        // so visiting a prolific seller's /stall/{id} page doesn't
        // force the server to JOIN-FETCH every active row every time.
        // Mirrors the MyStall display cap (batch 1033). Older rows
        // stay in the DB, queryable via item-specific endpoints.
        def visible = listingService.findActiveVisibleBySeller(userId, 500)
        decorateWithSellerRating(visible)
        // Derive away-mode: seller has active listings but all of them are
        // hidden. Lets the UI show "This seller is away" instead of an
        // indistinguishable "no active listings" empty state.
        // Batch 1013 — COUNT query instead of the prior findActiveBySeller.size()
        // that hydrated every listing row (with JOIN FETCH l.item) just
        // to call .size() on it.
        long totalActive = listingService.countActiveBySeller(userId)
        def away = visible.isEmpty() && totalActive > 0
        def ratingSummary = reviewService?.summaryForUser(userId) ?: [count: 0, average: null]
        def soldCount = listingService.countSoldBySeller(userId)
        // Last-24h / 7d / 30d sold counts. Batch 534 shipped 30d; batch
        // 875 extends to 24h + 7d so the stall hero can render a
        // graduated freshness indicator: "just made a sale" >
        // "active this week" > "30d-activity" > "dormant."
        def now = System.currentTimeMillis()
        def soldLast24h = listingService.countSoldBySellerSince(
            userId, now - (24L * 60L * 60L * 1000L))
        def soldLast7d  = listingService.countSoldBySellerSince(
            userId, now - (7L  * 24L * 60L * 60L * 1000L))
        def soldLast30d = listingService.countSoldBySellerSince(
            userId, now - (30L * 24L * 60L * 60L * 1000L))
        // Verified threshold — 10+ completed sales AND (no ratings OR avg >= 4.0).
        // Mirrors CSFloat's trust badge: you have to have actually traded
        // successfully to show up as verified. Opinionated defaults that
        // can be tuned without touching clients.
        def verified = soldCount >= 10L &&
            ((ratingSummary.count ?: 0) == 0 || ((ratingSummary.average ?: 0.0) as double) >= 4.0d)
        // Public follower count — cheap indexed COUNT. Silently zero
        // when the feature isn't wired in the current profile.
        def followerCount = sellerFollowService?.countFollowers(userId) ?: 0L
        // Typical response time (median ms) across the most recent
        // resolved offers on this seller's listings. Null until the
        // seller has at least 3 data points so we don't mislead a
        // buyer with a one-offer noisy read.
        def typicalResponseMs = offerService?.typicalResponseMs(userId)
        // Response rate (0–100, or null under the 5-offer noise floor)
        // — complements typicalResponseMs. Together they render as
        // "Typically responds in X · 92% response rate" in the stall
        // hero and distinguish active sellers from cherry-pickers.
        def responseRatePct   = offerService?.responseRatePct(userId)
        // Typical ship-time (median ms) across VERIFIED trades in the
        // last 90d (batch 550). Null until the seller has 3+ samples
        // so a lucky fast first trade doesn't lie to buyers. Renders
        // as "Typically ships in ~N hours" on the stall hero, giving
        // buyers a concrete "will I actually get the item?" signal.
        def typicalShipMs       = tradeService?.typicalShipMs(userId)
        def typicalShipSamples  = tradeService?.typicalShipSampleCount(userId) ?: 0
        // Viewer-relative "have I blocked this seller?" flag (batch 347)
        // so the stall page can render a "You've blocked this seller"
        // banner with a one-click Unblock button. Anonymous viewers and
        // unblocked pairs see `blockedByViewer: false`. Self-visits are
        // trivially never blocked (the CHECK constraint would reject a
        // self-block write anyway).
        def viewer = req?.session?.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        boolean blockedByViewer = (viewer != null && viewer != userId && userBlockService != null)
            ? userBlockService.isBlocked(viewer, userId)
            : false
        // Batch 364 — expose a public `banned` flag so stall viewers
        // see a clear "This account has been suspended" banner instead
        // of a mysterious "0 active listings" empty state (banUser
        // cancels all the seller's listings, so their stall looks
        // dormant rather than disabled). We deliberately DO NOT return
        // banReason — that's staff-only context.
        boolean isBanned = Boolean.TRUE.equals(user.banned)
        def body = [
            seller: [
                id:                user.id,
                // Batch 982 — steamId64 was leaking on banned accounts.
                // profileUrl was already null'd for banned but the id
                // right next to it wasn't — a scraper harvesting banned-
                // user Steam IDs could walk stall/1 … stall/N with
                // ?banned=true and still pull the 17-digit community id.
                // Symmetric null-out now applies to both fields: the
                // stall page for a banned seller exposes display name +
                // avatar (so the UI can render the suspension banner
                // with identity) but nothing that enables further
                // off-platform contact.
                steamId64:         isBanned ? null : user.steamId64,
                // Public Steam profile URL (batch 726). Lets buyers
                // click through to the seller's community profile to
                // check account age / badge count / friends list as a
                // trust signal. Hidden for banned accounts so their
                // Steam profile doesn't keep getting traffic from our
                // stall page after staff suspends.
                profileUrl:        isBanned ? null : user.profileUrl,
                displayName:       user.displayName,
                avatarUrl:         user.avatarUrl,
                joinedAt:          user.createdAt,
                // Last time we observed this account via the Steam sync
                // loop — the closest approximation of "online". Only
                // surfaces recency signal, not PII. Null for accounts we
                // haven't re-synced since they signed in.
                lastSyncedAt:      user.lastSyncedAt,
                verified:          verified && !isBanned,
                soldCount:         soldCount,
                soldLast24h:       soldLast24h,
                soldLast7d:        soldLast7d,
                soldLast30d:       soldLast30d,
                followerCount:     followerCount,
                typicalResponseMs: typicalResponseMs,
                responseRatePct:   responseRatePct,
                typicalShipMs:     typicalShipMs,
                typicalShipSamples: typicalShipSamples,
                // Batch 1045 — most-recent listedAt across the seller's
                // ACTIVE listings. Gives buyers a sharper activity signal
                // than lastSyncedAt (Steam sync, updates on any login)
                // because it reflects real marketplace action: "listed 2h
                // ago" vs "last seen 6d ago" is the difference between a
                // live seller and a dormant one. Null when they have no
                // active listings or when banned (same privacy posture
                // as the rest of the seller block).
                lastListedAt:      isBanned ? null : listingService.lastListedAtBySeller(userId),
                // Batch 1047 — complement to lastListedAt: most-recent
                // soldAt across the seller's SOLD listings. Listing
                // activity and sales activity tell different stories —
                // a seller who lists often but never sells is different
                // from one who trades routinely. Buyers want both signals.
                lastSoldAt:        isBanned ? null : listingService.lastSoldAtBySeller(userId),
                // Optional self-written bio, sanitised + capped at 500
                // chars on write. Null when the seller hasn't set one.
                // Hidden for banned accounts — we don't want the
                // banned user's marketing copy to keep greeting visitors.
                stallBio:          isBanned ? null : user.stallBio,
                banned:            isBanned
            ],
            listings:         visible,
            count:            visible.size(),
            away:             away && !isBanned,
            awayCount:        (away && !isBanned) ? totalActive : 0,
            // Scheduled "back on X" timestamp when the seller explicitly
            // set an until-date on the away toggle (batch 628). Null when
            // the seller just flipped away without a schedule. Future
            // until-dates render as "Back on <date>"; past/null renders
            // as the generic "away" banner.
            awayUntil:        (away && !isBanned && user.awayModeUntil != null &&
                               user.awayModeUntil > System.currentTimeMillis())
                               ? user.awayModeUntil : null,
            rating:           ratingSummary,
            blockedByViewer:  blockedByViewer
        ] as Map<String, Object>
        // `private` — the blockedByViewer flag is viewer-specific and
        // we don't want a shared cache to cross-contaminate. 30s cap
        // keeps typical stall re-visits fast while staying responsive
        // to seller edits (new listing, price drop, away toggle).
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=30')
            .body(body)
    }

    /** Items the user has purchased and still owns (= inventory).
     *  Hard-capped at the most recent 500 rows so a prolific collector
     *  doesn't force the server to serialise thousands of Listings on
     *  every SellItemsModal open. `X-Total-Count` header carries the
     *  true row count so the frontend can render "Showing most recent
     *  500 of N" when the cap is hit. Items sold off the inventory
     *  stop counting; this is purely a display cap. */
    @GetMapping("/inventory")
    ResponseEntity<List<Listing>> inventory(HttpServletRequest req) {
        def userId = requireUser(req)
        def rows = listingService.findOwnedBy(userId, 500)
        long total = listingService.countOwnedBy(userId)
        ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .body(rows)
    }

    /** Buy a listing using the current user's wallet balance.
     *  Accepts an optional `{expectedPrice: "9.99"}` body — when
     *  present, rejects with 409 PRICE_CHANGED if the server-side
     *  price has drifted (seller edit between modal render and Buy
     *  click). Same contract as the cart checkout guard from batch
     *  321. Body-less requests stay back-compat with older clients. */
    @PostMapping("/{id}/buy")
    ResponseEntity<Map> buy(@PathVariable Long id,
                            @RequestBody(required = false) Map body,
                            HttpServletRequest req) {
        def userId = requireUser(req)
        def user = steamUserRepository.findById(userId)
                .orElseThrow { new UnauthorizedException("Unknown user") }

        def wallet = walletRepository.findByUsername("steam_" + user.steamId64)
        if (wallet == null) {
            wallet = walletRepository.save(new Wallet(username: "steam_" + user.steamId64, balance: BigDecimal.ZERO))
        }

        // Optional price-match guard (batch 323). Match cart's
        // PRICE_CHANGED semantics: reject BEFORE the service call so
        // the wallet isn't debited at a surprise price.
        if (body?.expectedPrice != null) {
            BigDecimal expected
            try { expected = new BigDecimal(body.expectedPrice.toString()) }
            catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE",
                    "expectedPrice must be a valid number")
            }
            def lOpt = listingService.findById(id)
            if (lOpt != null && lOpt.price != null && lOpt.price.compareTo(expected) != 0) {
                throw new com.sboxmarket.exception.BadRequestException("PRICE_CHANGED",
                    "Price moved from \$${expected} to \$${lOpt.price} — refresh and retry")
            }
        }

        def result = purchaseService.buy(wallet.id, userId, id)
        // Batch 880 — include itemName + price in the response so the
        // frontend can render a personalised success toast ("Bought
        // 'Wizard Hat' for $15.95") instead of a generic "Purchase
        // complete". Pulls from the Listing returned by the service.
        def boughtListing = result.listing
        ResponseEntity.ok([
            transactionId: result.transactionId,
            newBalance   : result.newBalance,
            listingId    : id,
            itemName     : boughtListing?.item?.name,
            price        : boughtListing?.price,
            // P2P listings (real seller) open an escrow Trade; system/house
            // listings (sellerUserId == null) don't — the item is delivered
            // straight to the buyer's Platform Inventory. The frontend uses
            // this to point the success toast at the right place (Trades vs
            // the Sell page) instead of always saying "see Trades".
            tradeOpened  : (boughtListing?.sellerUserId != null)
        ])
    }

    /** List an owned item back on the market (from inventory). */
    @PostMapping("/sell")
    ResponseEntity<Map> sell(@Valid @RequestBody SellListingRequest body, HttpServletRequest req) {
        def userId = requireUser(req)
        def user = steamUserRepository.findById(userId)
                .orElseThrow { new UnauthorizedException("Unknown user") }

        def created = sellService.relist(userId, user.displayName ?: "Player",
            body.listingId, body.price, body.listingType, body.durationHours, body.description,
            body.buyNowPrice, body.maxDiscount)
        ResponseEntity.ok([
            listingId:    created.id,
            price:        created.price,
            status:       created.status,
            listingType:  created.listingType,
            expiresAt:    created.expiresAt,
            buyNowPrice:  created.buyNowPrice
        ])
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Map> cancel(@PathVariable Long id, HttpServletRequest req) {
        def userId = requireUser(req)
        sellService.cancelListing(userId, id)
        ResponseEntity.ok([ok: true])
    }

    /** Stall management — edit your own listing's price / description / hidden flag / discount cap. */
    @PutMapping("/{id}/stall")
    ResponseEntity<Map> updateStall(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def userId = requireUser(req)
        def listing = listingService.getById(id)
        if (listing.sellerUserId != userId) {
            throw new com.sboxmarket.exception.ForbiddenException("Not your listing")
        }
        // Capture the prior price so we can fire PRICE_DROPPED pings to
        // cart-holders after the save (batch 538). A drop from $50 to
        // $45 is a buy signal the cart-holder was waiting for — pushing
        // it converts abandoned carts into completed purchases.
        BigDecimal oldPrice = listing.price
        if (body.containsKey('price')) {
            def raw = body.price
            if (raw == null) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE", "Price is required")
            }
            BigDecimal p
            try { p = new BigDecimal(raw.toString()) }
            catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE", "Price must be a valid number")
            }
            if (p <= BigDecimal.ZERO) throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE", "Price must be positive")
            if (p > new BigDecimal("100000")) throw new com.sboxmarket.exception.BadRequestException("PRICE_TOO_HIGH", "Price must not exceed \$100,000")
            // Fairness guard: once a bid lands on an auction, the starting
            // price becomes binding — bidders pegged their offer to the
            // original ask. Letting the seller edit it mid-run would
            // either invalidate bids (confusing) or lock bids in against
            // a new number they never agreed to (unfair). Cancel + relist
            // is the intended escape hatch.
            if (listing.listingType == 'AUCTION' && (listing.bidCount ?: 0) > 0) {
                throw new com.sboxmarket.exception.BadRequestException("AUCTION_HAS_BIDS",
                    "Can't change the price on an auction that already has bids — cancel and relist instead.")
            }
            listing.price = p
        }
        // parseHiddenFlag (defined below for the /away endpoint) — bare
        // `body.hidden as Boolean` is unsafe because Jackson maps a JSON
        // string to String, and Groovy-truth makes every non-empty string
        // truthy → `{"hidden":"false"}` flipped the listing hidden=true
        // (seller un-hiding their listing actually re-hid it). Same bug
        // class as 692506e + 9913875.
        if (body.containsKey('hidden'))      listing.hidden      = parseHiddenFlag(body.hidden)
        if (body.containsKey('description')) {
            // HTML-strip + cap at 500 chars — matches the column size
            // (V39), the SellService.relist cap, and the sell-form
            // textarea maxLength.
            listing.description = textSanitizer.clean(body.description as String, 500)
        }
        if (body.containsKey('maxDiscount')) {
            def raw = body.maxDiscount
            if (raw == null) {
                listing.maxDiscount = null
            } else {
                BigDecimal md
                try { md = new BigDecimal(raw.toString()) }
                catch (NumberFormatException ignored) {
                    throw new com.sboxmarket.exception.BadRequestException("INVALID_DISCOUNT", "maxDiscount must be a valid number")
                }
                // Reject maxDiscount >= 1.0 — a 100%-off auto-accept would
                // let a $0.01 offer instantly buy the item. Matches
                // SellService.relist's `>= ONE` guard so the edit path is
                // no looser than listing creation (the old `> ONE` check
                // wrongly let exactly 1.0 through).
                if (md < BigDecimal.ZERO || md >= BigDecimal.ONE) {
                    throw new com.sboxmarket.exception.BadRequestException("INVALID_DISCOUNT",
                        "maxDiscount must be between 0 (no auto-accept) and 1 (100% off) exclusive")
                }
                listing.maxDiscount = md
            }
        }
        def saved = listingService.save(listing)
        // PRICE_DROPPED fan-out (batch 538). Fire when the seller
        // lowered the price — cart-holders who queued this exact
        // listing see a ping and can capture the cheaper price.
        // Capped at 50 recipients to bound fan-out cost on a listing
        // that sat in many carts.
        if (oldPrice != null && saved.price != null && saved.price < oldPrice
                && cartItemRepository != null && notificationService != null) {
            try {
                def others = cartItemRepository.findOtherUsersWithListing(saved.id, userId) ?: []
                if (!others.isEmpty()) {
                    def itemName = saved.item?.name ?: 'an item in your cart'
                    def itemId = saved.item?.id
                    def dropAmount = oldPrice - saved.price
                    def dropPct = oldPrice > BigDecimal.ZERO
                        ? (dropAmount.divide(oldPrice, 2, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal('100'))).intValue()
                        : 0
                    // Drop banned recipients (batch 316/317) — bell entry
                    // on a banned account is dead-end noise; banGuard
                    // rejects any re-shop attempt anyway.
                    def recipients = notificationService.filterActiveRecipients(others.take(50) as List<Long>)
                    recipients.each { uid ->
                        try {
                            notificationService.push(uid, 'PRICE_DROPPED',
                                "Cart item price drop · ${itemName}",
                                "${itemName} dropped from \$${oldPrice.toPlainString()} to \$${saved.price.toPlainString()}" +
                                    (dropPct > 0 ? " (−${dropPct}%)" : '') + ". Check out before it sells.",
                                saved.id,
                                itemId != null ? "/item/${itemId}" : '/cart')
                        } catch (Exception e) {
                            log.warn("PRICE_DROPPED push failed for uid=${uid}: ${e.message}")
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("PRICE_DROPPED fan-out failed for listing=${saved.id}: ${e.message}")
            }
        }
        // Batch 699 — ping buyers whose pending offer would now execute
        // at the ask. Separate from the cart fan-out above because the
        // audience is tighter (offer amount ≥ new price) and the
        // messaging is more actionable ("your offer could buy outright
        // now"). Only fires on real drops.
        if (oldPrice != null && saved.price != null && saved.price < oldPrice
                && offerService != null) {
            try {
                offerService.notifyOfferHoldersOfPriceDrop(saved.id, oldPrice, saved.price,
                    saved.item?.name, saved.item?.id)
            } catch (Exception e) {
                log.warn("Offer-holder price-drop fan-out failed for listing=${saved.id}: ${e.message}")
            }
        }
        ResponseEntity.ok([id: saved.id, price: saved.price, hidden: saved.hidden, description: saved.description, maxDiscount: saved.maxDiscount])
    }

    /** User-facing report endpoint. Body: { reason: string, note?: string }. Returns
     *  the listing's new aggregate report_count + a thanks message. */
    @PostMapping("/{id}/report")
    ResponseEntity<Map> report(@PathVariable Long id,
                               @RequestBody(required = false) Map body,
                               HttpServletRequest req) {
        def userId = requireUser(req)
        String reason = body?.reason as String
        String note = body?.note as String
        ResponseEntity.ok(listingService.reportListing(id, userId, reason, note))
    }

    /** Reasons dropdown — served so the UI matches the backend's whitelist. */
    @GetMapping("/report-reasons")
    ResponseEntity<List<String>> reportReasons() {
        ResponseEntity.ok(listingService.getReportReasons())
    }

    /**
     * Bulk freshness probe for the cart. The cart is persisted
     * client-side, so by the time a buyer opens /cart one or more
     * rows may have been bought by someone else (status != ACTIVE)
     * or had their price edited by the seller. Returns one row per
     * requested id — `active` false when the listing is no longer
     * purchasable, and the current `price` so the cart UI can flag
     * movement without the buyer being surprised at checkout.
     *
     * Capped at 50 ids per call so a crafted request can't fan out.
     * No auth needed: every field returned is already public on the
     * existing single-listing endpoint.
     */
    @PostMapping("/check-active")
    ResponseEntity<List<Map>> checkActive(@RequestBody Map body) {
        def raw = (body?.ids instanceof List) ? body.ids : []
        // Coerce each id defensively. A non-numeric string ({"ids":["abc"]}),
        // a boolean, or any other JSON value that Jackson maps to a non-numeric
        // Object used to bubble out of the bare `(it as Long)` cast as a
        // GroovyCastException — caught only by the catch-all handler and
        // surfaced as a 500 INTERNAL_ERROR on what is really a malformed-body
        // client mistake. Silently drop bad tokens so the freshness probe
        // degrades to a partial response (matches the bulkMerge / bulkCounts
        // family — WatchlistController#bulkMerge, BuyOrderController#countBulk,
        // ListingController#salesVelocity all use the same try/catch pattern).
        def ids = raw.take(50)
            .collect {
                if (it == null) return null
                try { Long.valueOf(it.toString()) } catch (Exception ignored) { null }
            }
            .findAll { it != null }
            .unique()
        if (ids.isEmpty()) return ResponseEntity.ok([])
        def rows = listingService.findByIds(ids)
        def byId = [:]
        rows.each { byId[it.id] = it }
        def out = ids.collect { id ->
            def l = byId[id]
            [
                id:     id,
                active: l != null && l.status == 'ACTIVE' && !Boolean.TRUE.equals(l.hidden),
                price:  l?.price
            ]
        }
        ResponseEntity.ok(out)
    }

    /** Platform-wide "Just sold" feed. Anonymous-friendly social-proof
     *  ticker on the homepage. Hard-capped at 30 rows; `soldAt` is
     *  indexed. Returns just the fields the card renderer needs so the
     *  payload stays small.
     *
     *  Signed-in viewers get the block-list filter applied so a blocked
     *  seller's last sale doesn't dangle on the ticker (batch 432) —
     *  parity with every other listing surface. Service-level filter
     *  already strips banned sellers for all audiences. */
    @GetMapping("/recent-sales")
    ResponseEntity<List<Map>> recentSales(@RequestParam(required = false) Integer limit,
                                          HttpServletRequest req) {
        def lim = Math.min(Math.max(limit ?: 10, 1), 30)
        def rows = listingService.findRecentSales(lim)
        def viewer = req?.session?.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (viewer != null && userBlockService != null) {
            def blocked = userBlockService.blockedIdsFor(viewer)
            if (!blocked.isEmpty()) {
                def set = new HashSet<>(blocked)
                rows = rows.findAll { r ->
                    r.sellerUserId == null || !set.contains(r.sellerUserId as Long)
                }
            }
        }
        // `private` because the viewer's block list filters rows out.
        // 30s matches the JustSoldRail's poll cadence so in-page polls
        // still hit the server on every cycle; the cache fires only for
        // cross-navigation reloads (modal open/close, route switches)
        // where the same data is re-requested inside the poll window.
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=30')
            .body(rows)
    }

    /** Top-N most-watched items site-wide, returned as the cheapest
     *  active listing per item. Drives the homepage "Most watched"
     *  social-proof rail (batch 273). Public — same audience as
     *  /api/listings/just-listed and the other rails. */
    @GetMapping("/most-watched")
    ResponseEntity<List<Listing>> mostWatched(@RequestParam(required = false) Integer limit,
                                              HttpServletRequest req) {
        def lim = Math.min(Math.max(limit ?: 8, 1), 30)
        def rows = filterBlocked(listingService.findMostWatched(lim), req)
        decorateWithSellerRating(rows)
        // Batch 811 — private cache: blocklist filter varies by viewer.
        // 60s matches the rail's client-side poll cadence; TopDealsRail
        // already caches at the same rate.
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=60')
            .body(rows)
    }

    /** Top-N most-viewed items site-wide, returned as the cheapest active
     *  listing per item. Drives the homepage "Most viewed" rail (batch
     *  412). Public — same audience as /api/listings/most-watched. */
    @GetMapping("/most-viewed")
    ResponseEntity<List<Listing>> mostViewed(@RequestParam(required = false) Integer limit,
                                             HttpServletRequest req) {
        def lim = Math.min(Math.max(limit ?: 8, 1), 30)
        def rows = filterBlocked(listingService.findMostViewed(lim), req)
        decorateWithSellerRating(rows)
        // Batch 811 — private cache (blocklist filter).
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=60')
            .body(rows)
    }

    /** Top-N hottest items over the last `days` days (default 7),
     *  returned as the cheapest active listing per item. Drives the
     *  "Hot right now" homepage rail (batch 289). Public — same
     *  audience as /api/listings/most-watched. */
    @GetMapping("/hottest")
    ResponseEntity<List<Listing>> hottest(@RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) Integer days,
                                          HttpServletRequest req) {
        def lim = Math.min(Math.max(limit ?: 8, 1), 30)
        def win = Math.min(Math.max(days ?: 7, 1), 30)
        def rows = filterBlocked(listingService.findHottest(lim, win), req)
        decorateWithSellerRating(rows)
        // Batch 811 — private cache (blocklist filter).
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=60')
            .body(rows)
    }

    /** Bulk recent-sales count per item over the rolling window
     *  (default 7 days, max 30) — drives the "🔥 N sold 7d" chip on
     *  marketplace cards (batch 288). Public, omits zero-count items
     *  to keep the JSON tight on a sparsely-traded grid. Tolerates
     *  malformed token in the comma-separated `ids` (silently drops
     *  bad entries) and caps the input list at 200 ids. */
    @GetMapping("/sales-velocity")
    ResponseEntity<Map<Long, Long>> salesVelocity(
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) Integer days) {
        if (ids == null || ids.isBlank()) {
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
        int win = Math.min(Math.max(days ?: 7, 1), 30)
        long since = System.currentTimeMillis() - (win * 24L * 60L * 60L * 1000L)
        def rows = listingService.countRecentSalesByItemIds(parsed, since)
        Map<Long, Long> out = [:]
        rows.each { row ->
            def id    = row[0] as Long
            def count = (row[1] ?: 0L) as Long
            if (count > 0L) out[id] = count
        }
        // Public aggregate — viewer-agnostic item-id → sale-count.
        // 60s cache matches the grid's useMemo key stability so
        // scroll + filter changes on the same items don't re-fetch.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body(out)
    }

    /** Toggle "Away mode" — hides ALL of the user's active listings in
     *  one shot. Optional `until` (epoch ms) schedules an automatic
     *  return: the hourly sweep flips visibility back on at that time
     *  and clears the field. Omitted / null = indefinite away mode
     *  (manual flip-back required). */
    @PostMapping("/away")
    ResponseEntity<Map> awayMode(@RequestBody Map body, HttpServletRequest req) {
        def userId = requireUser(req)
        if (body?.hidden == null) {
            throw new com.sboxmarket.exception.BadRequestException("MISSING_FIELD", "'hidden' field is required (true or false)")
        }
        // Bare `body.hidden as Boolean` is unsafe — Jackson maps
        // `{"hidden":"false"}` to the String "false", and Groovy-truth
        // makes EVERY non-empty string truthy, so the seller would
        // ENTER away-mode when they meant to leave it. Mirror the
        // SellerFollowController.parseMutedFlag pattern: accept a real
        // Boolean OR the explicit string forms; anything else is 400.
        def hidden = parseHiddenFlag(body.hidden)
        Long until = null
        if (body.until != null) {
            try {
                until = Long.valueOf(body.until.toString())
            } catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_UNTIL",
                    "'until' must be an epoch-ms timestamp")
            }
        }
        def affected = listingService.setAwayMode(userId, hidden, until)
        ResponseEntity.ok([hidden: hidden, affected: affected, until: until])
    }

    /** Read the seller's current vacation-mode state — drives the
     *  "Return on …" chip in the My Stall toolbar so a returning user
     *  sees their scheduled resume time without flipping the toggle. */
    @GetMapping("/away")
    ResponseEntity<Map> awayState(HttpServletRequest req) {
        def userId = requireUser(req)
        Long until = null
        try {
            def user = steamUserRepository.findById(userId).orElse(null)
            until = user?.awayModeUntil
        } catch (Exception ignored) {}
        // "Hidden" is derived from the listing rows themselves — a stall
        // can be all-hidden without a return timer (manual indefinite
        // away). Cheap COUNT-flag derivation.
        def hiddenCount = listingService.countHiddenActive(userId)
        ResponseEntity.ok([
            hidden: hiddenCount > 0,
            until:  until
        ])
    }

    private Long requireUser(HttpServletRequest req) {
        def userId = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (userId == null) throw new UnauthorizedException()
        userId
    }

    /** Coerce a JSON `hidden` field to a real boolean. A bare
     *  `value as Boolean` is unsafe because Jackson maps a JSON string
     *  to `String`, and Groovy-truth makes every non-empty string
     *  truthy — so `{"hidden":"false"}` coerces to `true`. Handle the
     *  real `Boolean` + the string-encoded forms explicitly; anything
     *  else is a 400. Mirrors SellerFollowController.parseMutedFlag. */
    private static boolean parseHiddenFlag(Object raw) {
        if (raw instanceof Boolean) return raw
        if (raw instanceof String) {
            def s = raw.trim().toLowerCase()
            if (s == 'true')  return true
            if (s == 'false') return false
        }
        throw new com.sboxmarket.exception.BadRequestException('INVALID_FIELD',
            "'hidden' must be a boolean (true/false)")
    }
}
