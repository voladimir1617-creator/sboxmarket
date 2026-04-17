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
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset
    ) {
        // Cap limit defensively — never let a client ask for the entire DB.
        // Cap is 100 (was 500): even once the catalogue grows, `size=99999`
        // can no longer dump every row in one shot.
        int safeLimit = Math.min(Math.max(limit ?: 100, 1), 100)
        int safeOffset = Math.max(offset ?: 0, 0)
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
        if (sort != null && !(sort in ['price_asc','price_desc','newest','rarity'])) sort = 'price_asc'
        if (category != null) category = category.replace('\u0000', '')
        if (rarity   != null) rarity   = rarity.replace('\u0000', '')
        if (category != null && category.length() > 40) category = 'All'
        if (rarity   != null && rarity.length()   > 40) rarity   = 'All'

        BigDecimal min = parsePriceParam(minPrice, "minPrice")
        BigDecimal max = parsePriceParam(maxPrice, "maxPrice")

        def all = listingService.getActiveListings(sort, category, rarity, min, max, search)
        def page = all.drop(safeOffset).take(safeLimit)
        // Return the array directly when no pagination params were used (back-compat with
        // existing frontend); when limit/offset are present, return a PageResponse.
        if (limit == 100 && offset == 0) {
            return ResponseEntity.ok(page)
        }
        ResponseEntity.ok([items: page, total: all.size(), limit: safeLimit, offset: safeOffset])
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
    ResponseEntity<List<Listing>> getForItem(@PathVariable Long itemId) {
        ResponseEntity.ok(listingService.getListingsForItem(itemId))
    }

    @GetMapping("/{id}")
    ResponseEntity<Listing> getById(@PathVariable Long id) {
        try {
            ResponseEntity.ok(listingService.getById(id))
        } catch (NoSuchElementException ignored) {
            throw new NotFoundException("Listing", id)
        }
    }

    @GetMapping("/stats")
    ResponseEntity<Map> getMarketStats() {
        ResponseEntity.ok(listingService.getMarketStats())
    }

    /** Active listings owned by the current Steam user (= "My Stall"). */
    @GetMapping("/my-stall")
    ResponseEntity<List<Listing>> myStall(HttpServletRequest req) {
        def userId = requireUser(req)
        ResponseEntity.ok(listingService.findActiveBySeller(userId))
    }

    /** Sale history for the current user — drives the "Sold" tab in the
     *  MyStall UI. Hard-capped at 200 rows so a prolific seller doesn't
     *  dump their entire history on every page render. */
    @GetMapping("/my-stall/sold")
    ResponseEntity<List<Listing>> myStallSold(HttpServletRequest req) {
        def userId = requireUser(req)
        ResponseEntity.ok(listingService.findSoldBySeller(
            userId, org.springframework.data.domain.PageRequest.of(0, 200)))
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
        BigDecimal pct
        try {
            pct = new BigDecimal((body?.percent ?: '').toString())
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

    /** Newest active listings — drives the homepage "Just listed" rail.
     *  Public, excludes hidden listings via the service's visible-seller
     *  filter where applicable, capped at 20 rows. Buyers love fresh
     *  inventory; this is the "what just dropped" surface. */
    @GetMapping("/just-listed")
    ResponseEntity<List<Listing>> justListed() {
        def rows = listingService.findNewestActive(20)
        ResponseEntity.ok(rows)
    }

    /** Top sellers rail — aggregates sold counts and surfaces the most
     *  active sellers for homepage social proof. Public, capped at 8
     *  rows so the payload stays tiny. Response includes enough data for
     *  the UI to link to /stall/:id and render an avatar + name + stats. */
    @GetMapping("/top-sellers")
    ResponseEntity<List<Map>> topSellers() {
        def rows = listingService.topSellers(5L, 8)
        def out = rows.collect { r ->
            def user = steamUserRepository.findById(r.userId).orElse(null)
            if (user == null) return null
            def ratingSummary = reviewService?.summaryForUser(user.id) ?: [count: 0, average: null]
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
        ResponseEntity.ok(out)
    }

    /** Auctions ending within the next hour (or custom window). Powers the
     *  homepage "Ending soon" rail — high-signal surface for the buying
     *  audience since the bid pressure is about to peak. Public endpoint,
     *  returns at most 20 rows, excludes hidden listings. */
    @GetMapping("/ending-soon")
    ResponseEntity<List<Listing>> endingSoon(@RequestParam(required = false) Long withinMs) {
        def now = System.currentTimeMillis()
        // Default: 60 minutes. Caller can shrink (e.g. to 15 min) to pull
        // the "hottest" few. Cap at 24 h so a malicious caller can't sweep
        // the whole auction inventory by passing a huge window.
        def window = withinMs != null ? Math.min(withinMs, 24L * 60 * 60 * 1000) : 60L * 60 * 1000
        def deadline = now + window
        def rows = listingService.findAuctionsEndingBefore(now, deadline)
        ResponseEntity.ok(rows.take(20))
    }

    /**
     * Public stall view — everyone can see a seller's active listings. Hidden
     * listings (stall-privacy mode) are excluded by the service-level filter.
     *
     * Security: deliberately does NOT return `profileUrl` or `steamId64` —
     * both contain the seller's 17-digit Steam community ID, which an
     * attacker could harvest by walking stall/1 … stall/N. Only the display
     * name, avatar and stall id (our internal, non-Steam) are exposed.
     * URL: GET /api/listings/stall/{userId}
     */
    @GetMapping("/stall/{userId}")
    ResponseEntity<Map> publicStall(@PathVariable Long userId) {
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) return ResponseEntity.notFound().build()
        def visible = listingService.findActiveVisibleBySeller(userId)
        // Derive away-mode: seller has active listings but all of them are
        // hidden. Lets the UI show "This seller is away" instead of an
        // indistinguishable "no active listings" empty state.
        def totalActive = listingService.findActiveBySeller(userId).size()
        def away = visible.isEmpty() && totalActive > 0
        def ratingSummary = reviewService?.summaryForUser(userId) ?: [count: 0, average: null]
        def soldCount = listingService.countSoldBySeller(userId)
        // Verified threshold — 10+ completed sales AND (no ratings OR avg >= 4.0).
        // Mirrors CSFloat's trust badge: you have to have actually traded
        // successfully to show up as verified. Opinionated defaults that
        // can be tuned without touching clients.
        def verified = soldCount >= 10L &&
            ((ratingSummary.count ?: 0) == 0 || ((ratingSummary.average ?: 0.0) as double) >= 4.0d)
        ResponseEntity.ok([
            seller: [
                id:          user.id,
                displayName: user.displayName,
                avatarUrl:   user.avatarUrl,
                joinedAt:    user.createdAt,
                verified:    verified,
                soldCount:   soldCount
            ],
            listings:  visible,
            count:     visible.size(),
            away:      away,
            awayCount: away ? totalActive : 0,
            rating:    ratingSummary
        ])
    }

    /** Items the user has purchased and still owns (= inventory). */
    @GetMapping("/inventory")
    ResponseEntity<List<Listing>> inventory(HttpServletRequest req) {
        def userId = requireUser(req)
        ResponseEntity.ok(listingService.findOwnedBy(userId))
    }

    /** Buy a listing using the current user's wallet balance. */
    @PostMapping("/{id}/buy")
    ResponseEntity<Map> buy(@PathVariable Long id, HttpServletRequest req) {
        def userId = requireUser(req)
        def user = steamUserRepository.findById(userId)
                .orElseThrow { new UnauthorizedException("Unknown user") }

        def wallet = walletRepository.findByUsername("steam_" + user.steamId64)
        if (wallet == null) {
            wallet = walletRepository.save(new Wallet(username: "steam_" + user.steamId64, balance: BigDecimal.ZERO))
        }

        def result = purchaseService.buy(wallet.id, userId, id)
        ResponseEntity.ok([
            transactionId: result.transactionId,
            newBalance   : result.newBalance,
            listingId    : id
        ])
    }

    /** List an owned item back on the market (from inventory). */
    @PostMapping("/sell")
    ResponseEntity<Map> sell(@Valid @RequestBody SellListingRequest body, HttpServletRequest req) {
        def userId = requireUser(req)
        def user = steamUserRepository.findById(userId)
                .orElseThrow { new UnauthorizedException("Unknown user") }

        def created = sellService.relist(userId, user.displayName ?: "Player", body.listingId, body.price)
        ResponseEntity.ok([listingId: created.id, price: created.price, status: created.status])
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
            listing.price = p
        }
        if (body.containsKey('hidden'))      listing.hidden      = body.hidden as Boolean
        if (body.containsKey('description')) {
            // HTML-strip + cap at 64 chars — users can only write plain text.
            listing.description = textSanitizer.clean(body.description as String, 64)
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
                if (md < BigDecimal.ZERO || md > BigDecimal.ONE) {
                    throw new com.sboxmarket.exception.BadRequestException("INVALID_DISCOUNT", "maxDiscount must be between 0 and 1")
                }
                listing.maxDiscount = md
            }
        }
        def saved = listingService.save(listing)
        ResponseEntity.ok([id: saved.id, price: saved.price, hidden: saved.hidden, description: saved.description, maxDiscount: saved.maxDiscount])
    }

    /** Toggle "Away mode" — hides ALL of the user's active listings in one shot. */
    @PostMapping("/away")
    ResponseEntity<Map> awayMode(@RequestBody Map body, HttpServletRequest req) {
        def userId = requireUser(req)
        if (body?.hidden == null) {
            throw new com.sboxmarket.exception.BadRequestException("MISSING_FIELD", "'hidden' field is required (true or false)")
        }
        def hidden = body.hidden as Boolean
        def affected = listingService.setAwayMode(userId, hidden)
        ResponseEntity.ok([hidden: hidden, affected: affected])
    }

    private Long requireUser(HttpServletRequest req) {
        def userId = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (userId == null) throw new UnauthorizedException()
        userId
    }
}
