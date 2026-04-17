package com.sboxmarket.controller

import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.service.ItemService
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/items")
@Slf4j
class ItemController {

    @Autowired ItemService itemService
    @Autowired ItemRepository itemRepository
    @Autowired com.sboxmarket.repository.ListingRepository listingRepository
    @Autowired(required = false) com.sboxmarket.service.SboxApiService sboxApiService

    @GetMapping
    ResponseEntity<List<Item>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "All") String category,
            @RequestParam(required = false, defaultValue = "All") String rarity,
            @RequestParam(required = false, defaultValue = "price_desc") String sort,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice
    ) {
        if (q != null) q = q.replace('\u0000', '')
        if (category != null) category = category.replace('\u0000', '')
        if (rarity != null) rarity = rarity.replace('\u0000', '')
        if (q != null && q.length() > 100) q = q.substring(0, 100)
        def items = itemService.search(q, category, rarity, sort, minPrice, maxPrice)
        ResponseEntity.ok(items)
    }

    @GetMapping("/{id}")
    ResponseEntity<Item> getById(@PathVariable Long id) {
        // NotFoundException is mapped to 404 by GlobalExceptionHandler
        ResponseEntity.ok(itemService.getById(id))
    }

    @GetMapping("/{id}/history")
    ResponseEntity<List<PriceHistory>> getPriceHistory(@PathVariable Long id) {
        ResponseEntity.ok(itemService.getPriceHistory(id))
    }

    /** Recent sale rows for an item (price + timestamp only — no
     *  counterparty identities leaked). Caps at 10; CSFloat shows a
     *  similar "Last 10 sales" strip on every item detail. */
    @GetMapping("/{id}/recent-sales")
    ResponseEntity<List<Map>> getRecentSales(@PathVariable Long id) {
        def rows = listingRepository.findRecentSalesForItem(
            id, org.springframework.data.domain.PageRequest.of(0, 10))
        def out = rows.collect { l ->
            [
                listingId: l.id,
                price:     l.price,
                soldAt:    l.soldAt,
                listingType: l.listingType
            ]
        }
        ResponseEntity.ok(out)
    }

    /**
     * Similar-items feed for the item detail view. Same (category-or-
     * rarity, price-proximity) semantics as before, but the walk runs
     * inside a single indexed JPQL query with `LIMIT 12` instead of
     * loading every catalogue row and sorting in Groovy.
     */
    @GetMapping("/{id}/similar")
    ResponseEntity<List<Item>> getSimilar(@PathVariable Long id) {
        def base = itemService.getById(id)
        def basePrice = base.lowestPrice ?: BigDecimal.ZERO
        def similar = itemRepository.findSimilar(
            base.id,
            base.category ?: '',
            base.rarity ?: '',
            basePrice,
            PageRequest.of(0, 12)
        )
        ResponseEntity.ok(similar)
    }

    @GetMapping("/stats")
    ResponseEntity<Map> getStats() {
        def stats = itemService.getStats() as Map
        // Attach the latest SCMM sync timestamp so the footer can render
        // "Catalog updated X ago" as a live trust signal. When the app
        // has never synced (first boot) the value is 0 and the client
        // renders a neutral "—" placeholder.
        stats = (stats ?: [:]) + [lastSyncedAt: sboxApiService?.lastSyncedAt ?: 0L]
        ResponseEntity.ok(stats)
    }
}
