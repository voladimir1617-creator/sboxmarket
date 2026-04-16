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
            @RequestParam(required = false, defaultValue = "All") String category,
            @RequestParam(required = false, defaultValue = "All") String rarity,
            @RequestParam(required = false, defaultValue = "rarest") String sort,
            @RequestParam(required = false, defaultValue = "60") Integer limit,
            @RequestParam(required = false, defaultValue = "0")  Integer offset
    ) {
        def safeLimit  = Math.min(Math.max(limit ?: 60, 1), 500)
        def safeOffset = Math.max(offset ?: 0, 0)

        // Cap user-controlled free-text so a 100kB `q=AAAA…` payload can't
        // burn a full-table scan. Category and rarity are enum-ish so a
        // long string means "attacker probing".
        // Strip null bytes — Postgres rejects 0x00 in UTF-8 strings.
        if (q != null)        q = q.replace('\u0000', '')
        if (category != null) category = category.replace('\u0000', '')
        if (rarity != null)   rarity = rarity.replace('\u0000', '')
        if (q != null && q.length() > 100) q = q.substring(0, 100)
        if (category != null && category.length() > 40) category = 'All'
        if (rarity   != null && rarity.length()   > 40) rarity   = 'All'
        if (sort != null && !(sort in ['rarest','most_traded','price_desc','price_asc','newest'])) sort = 'rarest'

        Sort sortOrder
        switch (sort) {
            case 'most_traded': sortOrder = Sort.by(Sort.Direction.DESC, 'totalSold');   break
            case 'price_desc':  sortOrder = Sort.by(Sort.Direction.DESC, 'lowestPrice'); break
            case 'price_asc':   sortOrder = Sort.by(Sort.Direction.ASC,  'lowestPrice'); break
            case 'newest':      sortOrder = Sort.by(Sort.Direction.DESC, 'createdAt');   break
            case 'rarest':
            default:            sortOrder = Sort.by(Sort.Direction.ASC,  'supply');      break
        }

        // Spring Data's PageRequest expects page number, not offset. The
        // frontend sends offset as a multiple of limit so integer division
        // is safe here.
        int pageNumber = safeOffset / safeLimit as int
        int pageSize   = safeLimit
        // Sentinel empty strings mean "no filter". Real nulls would crash
        // the JPQL with a Postgres type-inference error on lower(?::bytea).
        def qTrimmed = (q != null && !q.isEmpty()) ? q : ''
        def catFilter = (category && category != 'All') ? category : ''
        def rarFilter = (rarity && rarity != 'All') ? rarity : ''

        def result = itemRepository.searchCatalogue(
            qTrimmed, catFilter, rarFilter,
            PageRequest.of(pageNumber, pageSize, sortOrder)
        )

        ResponseEntity.ok([
            items:   result.content,
            total:   result.totalElements,
            limit:   safeLimit,
            offset:  safeOffset,
            indexed: itemRepository.count()
        ])
    }
}
