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

    @Transactional
    Listing buyListing(Long listingId) {
        def listing = getById(listingId)
        if (listing.status != 'ACTIVE') {
            throw new IllegalStateException("Listing is not active")
        }
        listing.status = 'SOLD'
        listing.soldAt = System.currentTimeMillis()

        def item = listing.item
        item.totalSold++
        itemRepository.save(item)

        def saved = listingRepository.save(listing)
        updateItemFloorPrice(item.id)
        saved
    }

    @Transactional
    void cancelListing(Long listingId) {
        def listing = getById(listingId)
        listing.status = 'CANCELLED'
        listingRepository.save(listing)
        updateItemFloorPrice(listing.item.id)
    }

    List<Listing> findActiveBySeller(Long sellerUserId) {
        listingRepository.findActiveBySeller(sellerUserId)
    }

    List<Listing> findActiveVisibleBySeller(Long sellerUserId) {
        listingRepository.findActiveVisibleBySeller(sellerUserId)
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
