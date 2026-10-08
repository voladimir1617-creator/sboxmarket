package com.sboxmarket.service

import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.PriceHistoryRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Slf4j
class ItemService {

    @Autowired ItemRepository itemRepository
    @Autowired PriceHistoryRepository priceHistoryRepository

    List<Item> getAll() {
        itemRepository.findAll()
    }

    Item getById(Long id) {
        itemRepository.findById(id).orElseThrow { new NotFoundException("Item", id) }
    }

    List<Item> search(String q, String category, String rarity, String sort,
                      BigDecimal minPrice, BigDecimal maxPrice) {

        List<Item> items

        if (q) {
            items = itemRepository.searchByName(q)
        } else if (category && category != 'All' && rarity && rarity != 'All') {
            items = itemRepository.findByCategoryAndRarity(category, rarity)
        } else if (category && category != 'All') {
            items = itemRepository.findByCategory(category)
        } else if (rarity && rarity != 'All') {
            items = itemRepository.findByRarity(rarity)
        } else {
            items = itemRepository.findAll()
        }

        // When a free-text `q` drove the initial lookup, the category /
        // rarity filters were previously silently dropped on the floor —
        // `?q=hat&category=Hats` returned every name-match across every
        // category, including the wrong ones. Apply the filters in memory
        // after the name search so the combined query is honoured. Each
        // filter is a cheap O(N) walk over the already-bounded result set
        // (the controller caps results at 500 / page), nothing like a
        // full-table scan.
        if (q && category && category != 'All') {
            items = items.findAll { it.category == category }
        }
        if (q && rarity && rarity != 'All') {
            items = items.findAll { it.rarity == rarity }
        }

        // price filter — items with null lowestPrice are NEVER in the
        // band by definition (price isn't known). Without the explicit
        // null check, Groovy's `null >= bd` calls null.compareTo(bd)
        // which NPEs and 500s the entire search — a brand-new item
        // before SteamMarketPriceService populates its lowestPrice (or
        // any catalogue row created via SCMM sync where buyNowPrice
        // came back blank) would crash every minPrice/maxPrice query.
        // Treat null as "exclude" so unpriced items are silently
        // dropped from a banded search, never throw.
        if (minPrice != null) items = items.findAll { it.lowestPrice != null && it.lowestPrice >= minPrice }
        if (maxPrice != null) items = items.findAll { it.lowestPrice != null && it.lowestPrice <= maxPrice }

        // sort — classic Groovy switch on a mutable copy so we never touch a
        // repository-backed list (same reliability fix applied to ListingService)
        def sorted = new ArrayList<Item>(items)
        switch (sort) {
            case 'price_asc':
                sorted.sort { a, b -> a.lowestPrice <=> b.lowestPrice }; break
            case 'price_desc':
                sorted.sort { a, b -> b.lowestPrice <=> a.lowestPrice }; break
            case 'popular':
                sorted.sort { a, b -> b.totalSold  <=> a.totalSold  }; break
            case 'rarity':
                sorted.sort { a, b -> a.supply     <=> b.supply     }; break
            case 'newest':
                sorted.sort { a, b -> b.createdAt  <=> a.createdAt  }; break
            default:
                sorted.sort { a, b -> b.lowestPrice <=> a.lowestPrice }; break
        }

        // Relevance: when a free-text `q` drove the lookup, an item whose
        // name EXACTLY equals the query (case-insensitively) must rank
        // above mere substring matches — a search for "Sniper" should
        // surface the item literally named "Sniper" before "Golden Sniper
        // Rifle", regardless of which one is pricier. `searchByName` is a
        // bare `LIKE %q%` with no ordering, and the sort above keys purely
        // off price/popularity/etc., so without this pass the exact match
        // was buried wherever its price happened to land. Implemented as a
        // STABLE partition (Groovy's List.sort is a stable mergesort) on a
        // 0/1 exact-match rank, so the chosen `sort` order is preserved as
        // the tiebreaker WITHIN each group (exact matches stay price-asc
        // among themselves, etc.). A null name can never be exact, so the
        // `?.` guard treats it as a non-match rather than NPEing.
        if (q) {
            // `q` arrives LIKE-escaped from ItemController (\%, \_, \\), so
            // undo that before comparing, or a name with _ % or \ never got
            // its exact-match boost.
            String needle = q.replaceAll(/\\(.)/, '$1').trim().toLowerCase()
            sorted.sort(true) { a, b ->
                int ra = (a.name?.toLowerCase() == needle) ? 0 : 1
                int rb = (b.name?.toLowerCase() == needle) ? 0 : 1
                ra <=> rb
            }
        }
        sorted
    }

    /** Batch 1022 — capped at a 400-day window instead of the full
     *  history. The sparkline chart's widest range is ALL (which the
     *  frontend caps at 365 rows via `history.slice(-365)`), so
     *  anything older is just wire-weight for no UI benefit. Long-
     *  tenure items that once returned thousands of rows now ship at
     *  most ~400. */
    List<PriceHistory> getPriceHistory(Long itemId) {
        long cutoff = System.currentTimeMillis() - (400L * 24L * 60L * 60L * 1000L)
        priceHistoryRepository.findByItemIdSince(itemId, cutoff)
    }

    @Transactional
    Item save(Item item) {
        itemRepository.save(item)
    }

    /**
     * Catalogue-wide stats for the footer "browse at a glance" strip +
     * the marketplace stats panel. Used to full-scan the `items` table
     * into memory and `.count{}` / `.min{}` / `.groupBy{}` in Groovy —
     * O(N) memory + CPU per call, burned every time the homepage
     * refreshed. Batch 618 replaces it with two indexed aggregate
     * queries so the call is O(1) per aggregate.
     */
    Map<String, Object> getStats() {
        def rows = itemRepository.catalogueSummary()
        def head = (rows != null && !rows.isEmpty()) ? rows[0] : null
        long total   = (head?.getAt(0) ?: 0L) as long
        long limited = (head?.getAt(1) ?: 0L) as long
        def floorRaw = head?.getAt(2)
        def highRaw  = head?.getAt(3)
        BigDecimal floor = floorRaw != null ? (floorRaw as BigDecimal) : BigDecimal.ZERO
        BigDecimal high  = highRaw  != null ? (highRaw  as BigDecimal) : BigDecimal.ZERO
        def categories = [:] as Map<String, Long>
        try {
            itemRepository.countByCategory().each { catRow ->
                def cat = catRow[0] as String
                def cnt = (catRow[1] ?: 0L) as long
                if (cat) categories[cat] = cnt
            }
        } catch (Exception ignore) { /* defer — stats can ship without the breakdown */ }
        [
            totalItems   : total,
            limitedCount : limited,
            floorPrice   : floor,
            highestPrice : high,
            categories   : categories
        ]
    }
}
