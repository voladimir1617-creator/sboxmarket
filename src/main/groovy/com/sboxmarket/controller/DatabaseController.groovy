package com.sboxmarket.controller

import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Read-only "Database" view — every item ever indexed, ranked by lowest
 * rarity score (mirror of CSFloat's lowest-float Database). Filtering and
 * sorting run inside the SQL query now — previous implementation pulled
 * the entire catalogue into memory, filtered/sorted in Groovy, then
 * sliced with `.drop().take()` which meant every request did a full-table
 * scan regardless of pagination. That's bug #17. The repository
 * `searchCatalogue` JPQL query now does filter + sort + page in one shot.
 */
@RestController
@RequestMapping("/api/database")
@Slf4j
class DatabaseController {

    @Autowired ItemRepository itemRepository
    @Autowired ListingRepository listingRepository

    @GetMapping
    ResponseEntity<Map> list(
            @RequestParam(required = false) String q,
            // Batch 986 — `search` alias for consistency with
            // /api/listings (batch 985) and /api/items (batch 986).
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "All") String category,
            @RequestParam(required = false, defaultValue = "All") String rarity,
            @RequestParam(required = false, defaultValue = "rarest") String sort,
            @RequestParam(required = false, defaultValue = "false") Boolean listedOnly,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false, defaultValue = "60") Integer limit,
            @RequestParam(required = false, defaultValue = "0")  Integer offset
    ) {
        def safeLimit  = Math.min(Math.max(limit ?: 60, 1), 500)
        def safeOffset = Math.max(offset ?: 0, 0)
        // Fall through to alias when canonical `q` is blank.
        if ((q == null || q.isBlank()) && search != null && !search.isBlank()) {
            q = search
        }

        // Cap user-controlled free-text so a 100kB `q=AAAA…` payload can't
        // burn a full-table scan. Category and rarity are enum-ish so a
        // long string means "attacker probing".
        // Strip null bytes — Postgres rejects 0x00 in UTF-8 strings.
        if (q != null)        q = q.replace('\u0000', '')
        if (category != null) category = category.replace('\u0000', '')
        if (rarity != null)   rarity = rarity.replace('\u0000', '')
        // Trim the free-text term. The alias-fallthrough above tests with
        // `isBlank()` (whitespace-aware) but the filter-application below
        // tests with `isEmpty()` (whitespace-blind); without a trim the
        // two disagree, so a whitespace-only `q='   '` (or a padded
        // `search='  hat  '` alias) reaches the JPQL as `LIKE '%   %'` /
        // `LIKE '%  hat  %'` and returns a confusing empty grid instead
        // of the unfiltered catalogue / the obvious `hat` match. Trim
        // first so the 100-char cap and the `isEmpty()` test below both
        // see the canonical term — this is why the bound variable is
        // named `qTrimmed`.
        if (q != null) q = q.trim()
        if (q != null && q.length() > 100) q = q.substring(0, 100)
        if (category != null && category.length() > 40) category = 'All'
        if (rarity   != null && rarity.length()   > 40) rarity   = 'All'
        // Batch 658 / 659 — case-insensitive normalisation via the
        // shared `com.sboxmarket.util.ListingEnums` helper. Unknown
        // values fall through to 'All' (no filter).
        category = com.sboxmarket.util.ListingEnums.canonEnum(category, com.sboxmarket.util.ListingEnums.CATEGORIES, 'All')
        rarity   = com.sboxmarket.util.ListingEnums.canonEnum(rarity,   com.sboxmarket.util.ListingEnums.RARITIES,   'All')
        // Batch 661 — case-insensitive sort normalisation (see ItemController).
        if (sort != null) sort = sort.toLowerCase()
        if (sort != null && !(sort in ['rarest','most_traded','most_viewed','price_desc','price_asc','newest'])) sort = 'rarest'

        Sort sortOrder
        switch (sort) {
            case 'most_traded': sortOrder = Sort.by(Sort.Direction.DESC, 'totalSold');   break
            case 'most_viewed': sortOrder = Sort.by(Sort.Direction.DESC, 'viewCount');   break
            case 'price_desc':  sortOrder = Sort.by(Sort.Direction.DESC, 'lowestPrice'); break
            case 'price_asc':   sortOrder = Sort.by(Sort.Direction.ASC,  'lowestPrice'); break
            case 'newest':      sortOrder = Sort.by(Sort.Direction.DESC, 'createdAt');   break
            case 'rarest':
            default:            sortOrder = Sort.by(Sort.Direction.ASC,  'supply');      break
        }
        // Every sort key above is tie-heavy on a fresh catalogue (e.g.
        // every seeded item has supply 0, viewCount 0). Without a stable
        // tiebreaker the DB is free to return a different row order per
        // page, so rows duplicate or vanish across the offset window.
        // Appending `id ASC` makes the total order deterministic.
        sortOrder = sortOrder.and(Sort.by(Sort.Direction.ASC, 'id'))

        // Spring Data's PageRequest is page-indexed. The frontend sends
        // offset as a multiple of limit, but a hand-built / non-aligned
        // offset must still be honoured exactly — bare `offset / limit`
        // integer division silently rounds a non-aligned offset DOWN to
        // the start of the enclosing page. Snap the offset down to a
        // page boundary so `page * size` reproduces the requested offset
        // and the response `offset` echoes the value actually applied.
        int pageSize   = safeLimit
        int pageNumber = (int) (safeOffset / pageSize)
        int alignedOffset = pageNumber * pageSize
        // Sentinel empty strings mean "no filter". Real nulls would crash
        // the JPQL with a Postgres type-inference error on lower(?::bytea).
        // Escape SQL LIKE wildcards (% _ \) in the bound term so a search
        // for a literal `_` or `%` matches that character instead of every
        // catalogue row — the JPQL query carries a matching `ESCAPE '\'`.
        def qTrimmed = (q != null && !q.isEmpty()) ? escapeLike(q) : ''
        def catFilter = (category && category != 'All') ? category : ''
        def rarFilter = (rarity && rarity != 'All') ? rarity : ''

        // Batch 558 — sanitize the price bounds. Negative or
        // above-$100k values are coerced to null so a typo doesn't
        // return a mysteriously empty grid. The order-insensitive
        // swap (min > max) also normalizes for a user who fat-fingers
        // the pair.
        BigDecimal minP = (minPrice != null && minPrice >= BigDecimal.ZERO && minPrice <= new BigDecimal("100000")) ? minPrice : null
        BigDecimal maxP = (maxPrice != null && maxPrice >= BigDecimal.ZERO && maxPrice <= new BigDecimal("100000")) ? maxPrice : null
        if (minP != null && maxP != null && minP > maxP) { def swap = minP; minP = maxP; maxP = swap }

        def result = Boolean.TRUE.equals(listedOnly)
            ? itemRepository.searchListedCatalogue(
                qTrimmed, catFilter, rarFilter, minP, maxP,
                PageRequest.of(pageNumber, pageSize, sortOrder))
            : itemRepository.searchCatalogue(
                qTrimmed, catFilter, rarFilter, minP, maxP,
                PageRequest.of(pageNumber, pageSize, sortOrder))

        // Batch 807 — public cache: catalogue data is viewer-agnostic
        // (no blocklist filter runs here, unlike /api/listings). A shared
        // edge cache can safely serve the same database-page response to
        // every visitor for 60s. Typed-query permutations (q=abc + q=abd)
        // get distinct URLs → distinct cache entries; the short TTL keeps
        // the catalogue fresh post-sync while still absorbing the thundering
        // herd on the default view.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=60')
            .body([
                items:   result.content,
                total:   result.totalElements,
                limit:   safeLimit,
                // Echo the offset actually applied (snapped down to a
                // page boundary) so a client that sent a non-aligned
                // offset can see what the server paged from.
                offset:  alignedOffset,
                indexed: itemRepository.count()
            ])
    }

    /** Escape SQL LIKE special characters so a user-typed `_` / `%` / `\`
     *  is matched literally instead of as a wildcard. Backslash first so
     *  the escapes we add aren't themselves re-escaped. Pairs with the
     *  `ESCAPE '\'` clause on `ItemRepository.searchCatalogue`. */
    private static String escapeLike(String s) {
        if (s == null) return null
        s.replace('\\', '\\\\')
         .replace('%', '\\%')
         .replace('_', '\\_')
    }
}
