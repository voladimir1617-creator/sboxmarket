package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.ListingReport
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.ListingReportRepository
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
    @Autowired(required = false) ListingReportRepository listingReportRepository
    @Autowired(required = false) TextSanitizer textSanitizer

    /** Cap on user-submitted reports per hour — stops a single user from mass-flagging
     *  every listing on the platform to burn down admin moderation cycles. */
    private static final int REPORT_RATE_PER_HOUR = 20
    private static final List<String> REPORT_REASONS = [
        'Suspicious pricing',
        'Likely scam / duplicate',
        'Wrong description or photos',
        'Prohibited item',
        'Offensive content',
        'Other'
    ]

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

    /** Newest active listings, capped. Drives the homepage "Just listed"
     *  rail. We additionally filter out `hidden` rows in Groovy — the
     *  existing `findActiveOrderByNewest` doesn't discriminate on the
     *  hidden flag, so we do it here before returning the cap. The cost
     *  is bounded: worst-case we fetch the N newest hidden rows before
     *  pulling visible ones, still sub-linear. */
    List<Listing> findNewestActive(int cap) {
        def rows = listingRepository.findActiveOrderByNewest()
        rows.findAll { it.hidden == null || !it.hidden }.take(cap)
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

    /** Top sellers — [{userId, soldCount}] sorted by soldCount DESC,
     *  filtered by `minSold` at the SQL level so we don't haul every
     *  seller into Groovy memory. Callers hydrate user + rating data. */
    List<Map> topSellers(long minSold, int limit) {
        def rows = listingRepository.topSellers(
            minSold, org.springframework.data.domain.PageRequest.of(0, limit))
        rows.collect { r -> [userId: r[0] as Long, soldCount: r[1] as Long] }
    }

    /** Top deals — active BUY_NOW listings sorted by deepest %
     *  discount vs catalogue steamPrice. Caller gets a cap'd page. */
    List<Listing> findTopDeals(int limit) {
        listingRepository.findTopDeals(org.springframework.data.domain.PageRequest.of(0, limit))
    }

    /** Platform-wide "Just sold" feed — most-recent SOLD listings across
     *  every seller. Projected to a minimal map so the card renderer
     *  doesn't pull entire Listing entities into the response JSON. */
    List<Map> findRecentSales(int limit) {
        def lim = Math.min(Math.max(limit, 1), 30)
        def rows = listingRepository.findRecentlySold(org.springframework.data.domain.PageRequest.of(0, lim))
        rows.collect { l ->
            [
                listingId:   l.id,
                itemId:      l.item?.id,
                itemName:    l.item?.name,
                category:    l.item?.category,
                rarity:      l.item?.rarity,
                imageUrl:    l.item?.imageUrl,
                price:       l.price,
                steamPrice:  l.item?.steamPrice,
                soldAt:      l.soldAt,
                sellerName:  l.sellerName
            ]
        }
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

    /**
     * Record a user report against a listing. One report per (listing, user)
     * pair — repeat clicks are rejected with a clear message so the user knows
     * the first report stuck. Per-user rate limit (20/hour) keeps abuse bounded.
     * Increments the aggregate counter on the listing row so the admin queue
     * can sort by report_count without reading the detail table.
     */
    @Transactional
    Map reportListing(Long listingId, Long reporterUserId, String reason, String note) {
        def listing = listingRepository.findById(listingId)
            .orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.sellerUserId != null && listing.sellerUserId == reporterUserId) {
            throw new BadRequestException("SELF_REPORT",
                "You can't report your own listing. Cancel it from My Stall instead.")
        }
        if (listingReportRepository == null) {
            throw new BadRequestException("REPORT_UNAVAILABLE",
                "Reports are temporarily unavailable")
        }
        // One report per (listing, user). Deliberate: we don't surface how
        // many reports a listing has to the reporter, so letting them click
        // again would either inflate the counter or silently no-op — neither
        // is what the button promises. Fail loud instead.
        def existing = listingReportRepository.findByListingIdAndReporterUserId(listingId, reporterUserId)
        if (existing.isPresent()) {
            throw new BadRequestException("ALREADY_REPORTED",
                "You've already reported this listing. Thanks — an admin will review it.")
        }
        def since = System.currentTimeMillis() - 3_600_000L
        def recent = listingReportRepository.countByReporterUserIdAndCreatedAtGreaterThan(reporterUserId, since)
        if (recent >= REPORT_RATE_PER_HOUR) {
            throw new BadRequestException("REPORT_RATE_LIMITED",
                "You've reported too many listings this hour. Try again later.")
        }
        def cleanReason = (reason != null && REPORT_REASONS.contains(reason))
            ? reason : 'Other'
        def cleanNote = textSanitizer != null ? textSanitizer.clean(note, 500) : (note?.take(500))

        def report = new ListingReport(
            listingId:       listingId,
            reporterUserId:  reporterUserId,
            reason:          cleanReason,
            note:            cleanNote,
            createdAt:       System.currentTimeMillis()
        )
        listingReportRepository.save(report)

        listing.reportCount = (listing.reportCount ?: 0) + 1
        listing.lastReportedAt = System.currentTimeMillis()
        listingRepository.save(listing)
        log.warn("User ${reporterUserId} reported listing ${listingId} (${cleanReason}); total reports=${listing.reportCount}")
        [
            id:           listing.id,
            reportCount:  listing.reportCount,
            thanks:       "Report received — an admin will review it shortly."
        ]
    }

    List<String> getReportReasons() { REPORT_REASONS }

    private void updateItemFloorPrice(Long itemId) {
        def item = itemRepository.findById(itemId).orElse(null)
        if (item) {
            def floor = listingRepository.minPriceForItem(itemId)
            item.lowestPrice = floor ?: BigDecimal.ZERO
            itemRepository.save(item)
        }
    }
}
