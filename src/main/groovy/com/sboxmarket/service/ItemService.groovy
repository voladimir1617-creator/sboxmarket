package com.sboxmarket.service

import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.PriceHistoryRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
@Slf4j
class ItemService {

    @Autowired ItemRepository itemRepository
    @Autowired PriceHistoryRepository priceHistoryRepository
    @Autowired(required = false) PlatformTransactionManager transactionManager

    /** Striped locks for {@link #findOrCreateByName}, keyed on the
     *  lower-cased name. Static so every ItemService instance shares them. */
    private static final int NAME_LOCK_STRIPES = 64
    private static final Object[] NAME_LOCKS = (0..<NAME_LOCK_STRIPES).collect { new Object() } as Object[]

    /**
     * The catalogue row for {@code name} (case-insensitive), creating it with
     * {@code newItem} when there is none. Two first listings of a brand-new
     * skin at the same moment (a double-clicked List, two sellers) each found
     * nothing and each created a row; items has no unique name constraint,
     * and existing data may already hold such duplicates, so the guard lives
     * here: creators of one name take turns, and each re-reads under the lock
     * before creating. The create commits in its own transaction before the
     * lock is let go, so the next one in line always sees it.
     * Per-JVM: a second app instance could still race (rare, and
     * findByNameIgnoreCase already reads the oldest row if it does).
     */
    Item findOrCreateByName(String name, Closure<Item> newItem) {
        Item found = itemRepository.findByNameIgnoreCase(name)
        if (found != null) return found
        Object lock = NAME_LOCKS[Math.floorMod((name ?: '').toLowerCase().hashCode(), NAME_LOCK_STRIPES)]
        Item result
        synchronized (lock) {
            Closure<Item> work = {
                Item again = itemRepository.findByNameIgnoreCase(name)
                if (again != null) return again
                Item created = itemRepository.save(newItem())
                log.info("Auto-created catalogue item \"${created?.name}\"")
                created
            }
            if (transactionManager == null) {
                result = work()
            } else {
                def tt = new TransactionTemplate(transactionManager)
                tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                result = tt.execute { work() } as Item
            }
        }
        // Hand the caller a row from its own persistence context when it has
        // one (the create above ran in a separate transaction).
        if (transactionManager != null && result?.id != null) {
            result = itemRepository.findById(result.id).orElse(result)
        }
        result
    }

    List<Item> getAll() {
        itemRepository.findAll()
    }

    Item getById(Long id) {
        itemRepository.findById(id).orElseThrow { new NotFoundException("Item", id) }
    }

    private static boolean priced(Item i) { i?.lowestPrice != null && i.lowestPrice > 0 }

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
        // An unlisted item carries lowestPrice 0, not null: it has no price,
        // so it is outside every band too (a "max $5" search returned every
        // unlisted item as a $0 match).
        if (minPrice != null) items = items.findAll { it.lowestPrice != null && it.lowestPrice > 0 && it.lowestPrice >= minPrice }
        if (maxPrice != null) items = items.findAll { it.lowestPrice != null && it.lowestPrice > 0 && it.lowestPrice <= maxPrice }

        // sort — classic Groovy switch on a mutable copy so we never touch a
        // repository-backed list (same reliability fix applied to ListingService)
        def sorted = new ArrayList<Item>(items)
        switch (sort) {
            case 'price_asc':
                // Unpriced (0 / null) items go last, not first: they have no
                // floor, so "cheapest first" must not open on a page of them.
                sorted.sort { a, b ->
                    (priced(a) ? 0 : 1) <=> (priced(b) ? 0 : 1) ?: a.lowestPrice <=> b.lowestPrice
                }; break
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
