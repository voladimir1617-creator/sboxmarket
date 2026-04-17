package com.sboxmarket.service

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Slf4j
class ListingService {

    @Autowired ListingRepository listingRepository
    @Autowired ItemRepository itemRepository
    @Autowired @Lazy BuyOrderService buyOrderService

    List<Listing> getActiveListings(String sort, String category, String rarity,
                                    BigDecimal minPrice, BigDecimal maxPrice,
                                    String search) {
        // Push every filter down into JPQL — status=ACTIVE, hidden flag,
        // search substring, category, rarity, price range — so Postgres
        // can use the idx_listings_status + idx_items_category indexes
        // instead of pulling the whole active set and filtering in Groovy.
        def q    = (search   != null && !search.isEmpty())     ? search   : ''
        def cat  = (category != null && category != 'All')     ? category : ''
        def rar  = (rarity   != null && rarity   != 'All')     ? rarity   : ''
        def listings = listingRepository.findActivePublic(q, cat, rar, minPrice, maxPrice)

        // JPQL returns price ASC; flip/sort in-memory for the non-default
        // cases. After the WHERE filter the result set is small so the
        // O(k log k) sort is cheap.
        def sorted = new ArrayList<Listing>(listings)
        switch (sort) {
            case 'price_asc':
                // already in price ASC from the query
                break
            case 'price_desc':
                sorted.sort { a, b -> b.price <=> a.price }
                break
            case 'newest':
                sorted.sort { a, b -> b.listedAt <=> a.listedAt }
                break
            case 'rarity':
                sorted.sort { a, b -> a.item.supply <=> b.item.supply }
                break
            default:
                break
        }
        sorted
    }

    List<Listing> getListingsForItem(Long itemId) {
        listingRepository.findCheapestForItem(itemId)
    }

    Listing getById(Long id) {
        listingRepository.findById(id).orElseThrow { new NoSuchElementException("Listing not found: $id") }
    }

    @Transactional
    Listing save(Listing listing) {
        def saved = listingRepository.save(listing)
        updateItemFloorPrice(listing.item.id)
        try { buyOrderService.tryMatch(saved) } catch (Exception ignore) {}
        saved
    }

    @Transactional
    int setAwayMode(Long sellerUserId, boolean hidden) {
        def listings = listingRepository.findActiveBySeller(sellerUserId)
        listings.each { it.hidden = hidden }
        listingRepository.saveAll(listings)
        listings.size()
    }

    @Transactional
    Listing createListing(Listing listing) {
        def saved = listingRepository.save(listing)
        updateItemFloorPrice(listing.item.id)
        try { buyOrderService.tryMatch(saved) } catch (Exception e) { log.warn("buy-order match: ${e.message}") }
        saved
    }

    List<Listing> findActiveBySeller(Long sellerUserId) {
        listingRepository.findActiveBySeller(sellerUserId)
    }

    List<Listing> findActiveVisibleBySeller(Long sellerUserId) {
        listingRepository.findActiveVisibleBySeller(sellerUserId)
    }

    /** Auctions ending within a window. Thin pass-through to the repo so
     *  the controller stays test-friendly (can mock the service instead of
     *  wiring a full repository). */
    List<Listing> findAuctionsEndingBefore(Long now, Long deadline) {
        listingRepository.findAuctionsEndingBefore(now, deadline)
    }

    /** Lifetime sold count for a seller — feeds the verified badge
     *  threshold and the stall-hero "sales" stat. */
    long countSoldBySeller(Long sellerUserId) {
        listingRepository.countSoldBySeller(sellerUserId)
    }

    /** Page of recent sold listings for a seller. Drives the MyStall
     *  "Sold items" tab. Caller passes a Pageable with a hard cap. */
    List<Listing> findSoldBySeller(Long sellerUserId, org.springframework.data.domain.Pageable page) {
        listingRepository.findSoldBySeller(sellerUserId, page)
    }

    /** Apply a percent adjustment to every active non-auction listing owned
     *  by the user. +10 = markup 10%, -5 = 5% discount. Auction listings
     *  are skipped (starting price ≠ current bid once a bid lands). Result
     *  returns the new minimum floor for each touched item so the caller
     *  can recompute displayed prices. */
    @Transactional
    Map bulkAdjustPrices(Long sellerUserId, BigDecimal percent) {
        def active = listingRepository.findActiveBySeller(sellerUserId)
        def factor = BigDecimal.ONE + (percent / new BigDecimal('100'))
        def touchedItemIds = new HashSet<Long>()
        int touched = 0, skipped = 0
        active.each { l ->
            if (l.listingType == 'AUCTION') { skipped++; return }
            def newPrice = (l.price * factor).setScale(2, BigDecimal.ROUND_HALF_UP)
            // Respect the same bounds as the single-listing editor.
            if (newPrice < new BigDecimal('0.01')) newPrice = new BigDecimal('0.01')
            if (newPrice > new BigDecimal('100000')) newPrice = new BigDecimal('100000')
            if (newPrice == l.price) { skipped++; return }
            l.price = newPrice
            touched++
            if (l.item?.id != null) touchedItemIds.add(l.item.id)
        }
        if (touched > 0) {
            listingRepository.saveAll(active.findAll { it.listingType != 'AUCTION' })
            // Floor prices on touched items must be recomputed so the
            // marketplace grid picks up the new cheapest listing per item.
            touchedItemIds.each { itemId ->
                try { updateItemFloorPrice(itemId) } catch (Exception ignore) {}
            }
        }
        [touched: touched, skipped: skipped, percent: percent]
    }

    List<Listing> findOwnedBy(Long buyerUserId) {
        listingRepository.findOwnedBy(buyerUserId) ?: []
    }

    Map<String, Object> getMarketStats() {
        long since24h = System.currentTimeMillis() - 86_400_000L
        def volume = listingRepository.sumVolumeAfter(since24h) ?: BigDecimal.ZERO
        def activeCount = listingRepository.countActive()
        def floor = listingRepository.findMinActivePrice() ?: BigDecimal.ZERO

        [
            volume24h    : volume.setScale(2, BigDecimal.ROUND_HALF_UP),
            activeListings: activeCount,
            floorPrice   : floor.setScale(2, BigDecimal.ROUND_HALF_UP),
        ]
    }

    private void updateItemFloorPrice(Long itemId) {
        def item = itemRepository.findById(itemId).orElse(null)
        if (item) {
            def floor = listingRepository.minPriceForItem(itemId)
            item.lowestPrice = floor ?: BigDecimal.ZERO
            itemRepository.save(item)
        }
    }
}
