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
    @Autowired(required = false) @Lazy SellerFollowService sellerFollowService
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository
    @Autowired(required = false) @Lazy SavedSearchService savedSearchService
    @Autowired(required = false) com.sboxmarket.repository.WatchlistItemRepository watchlistItemRepository
    @Autowired(required = false) com.sboxmarket.repository.CartItemRepository cartItemRepository
    @Autowired(required = false) NotificationService notificationService

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
                                    String search, String listingType) {
        // Push every filter down into JPQL — status=ACTIVE, hidden flag,
        // search substring, category, rarity, price range, listing type
        // — so Postgres can use the idx_listings_status + idx_items_category
        // indexes instead of pulling the whole active set and filtering
        // in Groovy.
        def q    = (search   != null && !search.isEmpty())     ? search   : ''
        def cat  = (category != null && category != 'All')     ? category : ''
        def rar  = (rarity   != null && rarity   != 'All')     ? rarity   : ''
        // Whitelist the listingType — only BUY_NOW / AUCTION are valid
        // enum values. Anything else (including 'All') falls back to
        // the empty-string sentinel that disables the filter.
        def lt   = (listingType in ['BUY_NOW', 'AUCTION'])      ? listingType : ''
        def listings = listingRepository.findActivePublic(q, cat, rar, lt, minPrice, maxPrice)

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
            case 'ending_soon':
                // Auction rows with the nearest expiresAt first; BUY_NOW
                // rows (no expiresAt) and auctions missing the field sort
                // to the bottom. Ties break by price ASC so two auctions
                // ending the same minute still have the cheaper one
                // visible first. CSFloat surfaces this as "Ending soonest"
                // and it's the main way buyers find last-call auctions.
                sorted.sort { a, b ->
                    def aEnd = (a?.listingType == 'AUCTION' && a?.expiresAt != null) ? a.expiresAt : Long.MAX_VALUE
                    def bEnd = (b?.listingType == 'AUCTION' && b?.expiresAt != null) ? b.expiresAt : Long.MAX_VALUE
                    def cmp = aEnd <=> bEnd
                    cmp != 0 ? cmp : (a.price <=> b.price)
                }
                break
            case 'discount':
                // Deepest % discount vs catalogue steamPrice first. Items
                // with no steamPrice reference (or where price >= steamPrice)
                // get a 0% discount and sort to the bottom. Breaks ties by
                // ascending price so two identical-discount rows still have
                // the cheaper one visible first. Previously the backend had
                // no case here — the whitelisted sort=discount silently fell
                // through to the JPQL price-ASC default, so "Sort by deepest
                // discount" in the UI returned items ordered by price.
                sorted.sort { a, b ->
                    def cmp = discountRatio(b) <=> discountRatio(a)
                    cmp != 0 ? cmp : (a.price <=> b.price)
                }
                break
            case 'popularity':
                // Most-traded items first (batch 405). Uses the catalogue's
                // platform-wide totalSold counter bumped on every
                // PurchaseService.buy / BidService.settle, so it reflects
                // actual buyer activity rather than any static editorial
                // ranking. Items with zero sales fall to the bottom; ties
                // break by price ASC so the cheapest hot listing bubbles
                // up on a popular item with multiple listings.
                sorted.sort { a, b ->
                    def aSold = (a?.item?.totalSold ?: 0) as long
                    def bSold = (b?.item?.totalSold ?: 0) as long
                    def cmp = bSold <=> aSold
                    cmp != 0 ? cmp : (a.price <=> b.price)
                }
                break
            case 'views':
                // Most-viewed items first (batch 410). Uses the V46
                // `items.view_count` counter bumped on every
                // `GET /api/items/{id}` hit — reflects browser interest,
                // not buy volume. Complements `popularity` (completed
                // sales) by surfacing items people are CURIOUS about
                // even if they haven't converted yet. Zero-view items
                // fall to the bottom; ties break by price ASC.
                sorted.sort { a, b ->
                    def aView = (a?.item?.viewCount ?: 0) as long
                    def bView = (b?.item?.viewCount ?: 0) as long
                    def cmp = bView <=> aView
                    cmp != 0 ? cmp : (a.price <=> b.price)
                }
                break
            default:
                break
        }
        sorted
    }

    /** Discount ratio for a listing — 0..1 where 0 means no discount (or
     *  no Steam reference price) and 1 means free. Safe against null
     *  steamPrice / price and against price >= steamPrice (returns 0 in
     *  both cases so those rows don't outrank real discounts).
     *
     *  AUCTION rows return 0: an auction's `price` is the *starting bid*,
     *  not a binding sale price, so a $0.01-opener auction would otherwise
     *  read as ~100% off and dominate the discount sort even though its
     *  live bid may be at or above the Steam reference. This matches the
     *  `findTopDeals` repo query, which is already BUY_NOW-only. */
    private static BigDecimal discountRatio(Listing l) {
        if (l == null) return BigDecimal.ZERO
        if (l.listingType == 'AUCTION') return BigDecimal.ZERO
        def steam = l.item?.steamPrice
        def price = l.price
        if (steam == null || price == null) return BigDecimal.ZERO
        if (steam <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return BigDecimal.ZERO
        if (price >= steam) return BigDecimal.ZERO
        ((steam - price) / steam).setScale(6, BigDecimal.ROUND_HALF_UP)
    }

    List<Listing> getListingsForItem(Long itemId) {
        listingRepository.findCheapestForItem(itemId)
    }

    Listing getById(Long id) {
        listingRepository.findById(id).orElseThrow { new NoSuchElementException("Listing not found: $id") }
    }

    /** Null-safe lookup for paths that want to probe without throwing —
     *  e.g. the single-buy price-match guard (batch 323) short-circuits
     *  to NotFoundException at PurchaseService if the listing is missing
     *  anyway. */
    Listing findById(Long id) {
        if (id == null) return null
        listingRepository.findById(id).orElse(null)
    }

    /** Batch lookup for the cart freshness probe. Returns only the
     *  rows that still exist; callers line up their own input list
     *  against the result to detect missing ids. */
    List<Listing> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return []
        listingRepository.findAllById(ids).toList()
    }

    @Transactional
    Listing save(Listing listing) {
        def saved = listingRepository.save(listing)
        updateItemFloorPrice(listing.item.id)
        try { buyOrderService.tryMatch(saved) } catch (Exception ignore) {}
        saved
    }

    @Transactional
    int setAwayMode(Long sellerUserId, boolean hidden, Long awayUntil = null) {
        // Persist the optional resume timestamp so the scheduled sweep
        // knows when to flip listings back. Clear it when the seller
        // toggles back manually OR when they kick off a fresh
        // indefinite away (no `awayUntil` supplied).
        if (steamUserRepository != null) {
            try {
                def user = steamUserRepository.findById(sellerUserId).orElse(null)
                if (user != null) {
                    if (hidden) {
                        // Reject impossible "until" values — past or
                        // > 90 days into the future. The frontend
                        // already validates but defence in depth.
                        if (awayUntil != null) {
                            def now = System.currentTimeMillis()
                            def maxAhead = now + (90L * 24L * 60L * 60L * 1000L)
                            if (awayUntil <= now) {
                                throw new BadRequestException('AWAY_UNTIL_PAST',
                                    "'until' must be in the future")
                            }
                            if (awayUntil > maxAhead) {
                                throw new BadRequestException('AWAY_UNTIL_TOO_FAR',
                                    "'until' must be within 90 days from now")
                            }
                        }
                        user.awayModeUntil = awayUntil
                    } else {
                        // Manual flip-back ⇒ clear any pending expiry.
                        user.awayModeUntil = null
                    }
                    steamUserRepository.save(user)
                }
            } catch (BadRequestException b) { throw b }
            catch (Exception e) {
                log.warn("setAwayMode user-row update failed for ${sellerUserId}: ${e.message}")
            }
        }
        def listings = listingRepository.findActiveBySeller(sellerUserId)
        listings.each { it.hidden = hidden }
        listingRepository.saveAll(listings)
        // Re-compute the denormalised Item.lowestPrice for every item a
        // hidden-or-unhidden listing touches. Without this, an item
        // whose public floor WAS this seller's now-hidden listing keeps
        // showing the stale price on the marketplace grid card. Best-
        // effort — a single failing item shouldn't abort the batch.
        def touched = new HashSet<Long>()
        listings.each { if (it.item?.id != null) touched.add(it.item.id) }
        touched.each { itemId ->
            try { updateItemFloorPrice(itemId) } catch (Exception ignore) {}
        }
        listings.size()
    }

    /**
     * Top-N most-watched items site-wide, projected to the cheapest
     * active listing per item. Drives the "Most watched" social-proof
     * rail on the marketplace homepage. Skips items that have zero
     * active listings — clicking "Most watched" should never land on
     * an out-of-stock item with no buy option.
     *
     * Padding strategy: pull 2× the requested rows from the watcher
     * aggregate so we have headroom to skip rows with no active
     * listing. Falls back to whatever count we found.
     */
    List<Listing> findMostWatched(int limit) {
        if (watchlistItemRepository == null) return []
        int lim = Math.min(Math.max(1, limit), 30)
        def rows = watchlistItemRepository.findTopWatchedItemIds(
            org.springframework.data.domain.PageRequest.of(0, lim * 2))
        if (rows == null || rows.isEmpty()) return []
        projectCheapestPerItem(rows.collect { it[0] as Long }, lim)
    }

    /**
     * Shared projection for the homepage rails: given an ORDERED list of
     * candidate item ids, return the cheapest active visible listing for
     * each (in the same order), skipping items with no active listing,
     * capped at `lim`. One bulk `findActiveForItemIds` round-trip instead
     * of one `findCheapestForItem` per id — the N+1 the homepage rails
     * were paying (batch 1089). Preserves the candidate order (the rail's
     * ranking) and de-dupes repeated item ids.
     */
    private List<Listing> projectCheapestPerItem(List<Long> candidateItemIds, int lim) {
        if (candidateItemIds == null) return []
        def ordered = []
        def seen = new HashSet<Long>()
        for (Long id : candidateItemIds) {
            if (id != null && seen.add(id)) ordered << id
        }
        if (ordered.isEmpty()) return []
        // Flat list ordered by (item.id, price) — first row seen per
        // item id is that item's cheapest active listing.
        def cheapestByItem = [:] as Map<Long, Listing>
        (listingRepository.findActiveForItemIds(ordered) ?: []).each { Listing l ->
            def iid = l?.item?.id
            if (iid != null && !cheapestByItem.containsKey(iid)) {
                cheapestByItem[iid] = l
            }
        }
        def out = []
        for (Long id : ordered) {
            if (out.size() >= lim) break
            def l = cheapestByItem[id]
            if (l != null) out << l
        }
        out
    }

    /**
     * Top-N items by lifetime viewCount (batch 412). Same shape contract
     * as `findMostWatched` — projects to the cheapest active listing per
     * item so clicking the rail always lands on something buyable. Skips
     * items without any active listing. Pulls 2× the requested rows from
     * the view aggregate so listing-less items don't short-change the
     * visible count.
     */
    List<Listing> findMostViewed(int limit) {
        if (itemRepository == null) return []
        int lim = Math.min(Math.max(1, limit), 30)
        def rows = itemRepository.findTopViewedItemIds(
            org.springframework.data.domain.PageRequest.of(0, lim * 2))
        if (rows == null || rows.isEmpty()) return []
        projectCheapestPerItem(rows.collect { it[0] as Long }, lim)
    }

    /** Bulk recent-sales count per item — passes through to the repo's
     *  V1+ aggregate query (batch 288). Caller filters input + cutoff. */
    List<Object[]> countRecentSalesByItemIds(List<Long> itemIds, long since) {
        if (itemIds == null || itemIds.isEmpty()) return []
        listingRepository.countRecentSalesByItemIds(itemIds, since)
    }

    /**
     * Top-N hottest items over a rolling window, projected to the
     * cheapest active listing per item. Drives the "Hot right now"
     * homepage rail (batch 289). Skips items with no active listings —
     * clicking the rail should never land on a stockless item.
     *
     * Pads the candidate pool to 2× the requested rows to compensate
     * for items that sold but have no fresh listing. Mirrors the
     * `findMostWatched` strategy from batch 274.
     *
     * Batch 955 — fallback chain. On a cold-start marketplace with no
     * sales in the window, falling straight to `[]` hid the rail on
     * the homepage — visitors landed on a site that looked dead. Now
     * if no recent sales exist we fall back to (1) most-watched, then
     * (2) most-viewed, so the rail always has curated content. The
     * heading still says "Hot right now" because every fallback signal
     * is a real engagement proxy. De-duplicates by item id across the
     * fallback chain so the same item doesn't appear twice if it's
     * both most-watched and most-viewed.
     */
    List<Listing> findHottest(int limit, int days = 7) {
        int lim = Math.min(Math.max(1, limit), 30)
        int win = Math.min(Math.max(1, days), 30)
        long since = System.currentTimeMillis() - (win * 24L * 60L * 60L * 1000L)
        def rows = listingRepository.findTopSoldItemIds(
            since, org.springframework.data.domain.PageRequest.of(0, lim * 2))
        def out = []
        def seenItemIds = new HashSet<Long>()
        if (rows != null && !rows.isEmpty()) {
            // One bulk round-trip for the cheapest active listing per
            // sold-item id (was one findCheapestForItem per id).
            projectCheapestPerItem(rows.collect { it[0] as Long }, lim).each { Listing l ->
                def itemId = l?.item?.id
                if (itemId != null && seenItemIds.add(itemId)) out << l
            }
        }
        if (out.size() >= lim) return out
        // Fallback 1: most-watched items site-wide. Same projection
        // shape (cheapest active listing per item) so the rail renders
        // without any frontend branching.
        try {
            def watched = findMostWatched(lim * 2)
            for (Listing l : (watched ?: [])) {
                if (out.size() >= lim) break
                def itemId = l?.item?.id
                if (itemId == null || !seenItemIds.add(itemId)) continue
                out << l
            }
        } catch (Exception ignore) {}
        if (out.size() >= lim) return out
        // Fallback 2: most-viewed items. Last-resort signal so the rail
        // still paints on a brand-new install with zero sales + zero
        // watchlist activity.
        try {
            def viewed = findMostViewed(lim * 2)
            for (Listing l : (viewed ?: [])) {
                if (out.size() >= lim) break
                def itemId = l?.item?.id
                if (itemId == null || !seenItemIds.add(itemId)) continue
                out << l
            }
        } catch (Exception ignore) {}
        out
    }

    /** Count of the seller's active listings that are currently hidden
     *  (away mode is on for them). Drives the My Stall "you're on
     *  vacation" indicator — derived from the rows themselves so it
     *  reflects the truth even after a manual mid-vacation un-hide of
     *  one row. */
    long countHiddenActive(Long sellerUserId) {
        // Batch 1013 — indexed COUNT instead of hydrating every
        // seller row just to filter .hidden in memory.
        if (sellerUserId == null) return 0L
        listingRepository.countHiddenActiveBySeller(sellerUserId)
    }

    /** Hourly sweep: any user with `away_mode_until <= now` gets every
     *  active listing flipped back to visible and the column cleared.
     *  Idempotent — the cleared column means a second sweep does
     *  nothing for the same user. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15L * 60L * 1000L,
                                                          initialDelay = 60_000L)
    @Transactional
    void sweepExpiredAwayMode() {
        if (steamUserRepository == null) return
        def now = System.currentTimeMillis()
        def expired = steamUserRepository.findExpiredAwayMode(now)
        if (expired.isEmpty()) return
        log.info("Vacation-mode sweep: ${expired.size()} user(s) past their resume time, un-hiding")
        expired.each { user ->
            try {
                def listings = listingRepository.findActiveBySeller(user.id)
                listings.each { it.hidden = false }
                if (!listings.isEmpty()) {
                    listingRepository.saveAll(listings)
                }
                user.awayModeUntil = null
                steamUserRepository.save(user)
            } catch (Exception e) {
                log.warn("sweepExpiredAwayMode failed for user ${user.id}: ${e.message}")
            }
        }
    }

    @Transactional
    Listing createListing(Listing listing) {
        def saved = listingRepository.save(listing)
        updateItemFloorPrice(listing.item.id)
        try { buyOrderService.tryMatch(saved) } catch (Exception e) { log.warn("buy-order match: ${e.message}") }
        // Fan out NEW_LISTING_FROM_SELLER to every follower. Without this,
        // Steam-inventory listings via /api/steam/list silently bypassed
        // the follower notification path that SellService.relist already
        // fires — so followers only saw platform relists, not fresh Steam
        // drops. Wrapped so a bad subscription never rolls back the save.
        try {
            sellerFollowService?.notifyFollowersOfNewListing(saved)
        } catch (Exception e) {
            log.warn("Follower fanout failed for listing ${saved.id}: ${e.message}")
        }
        // Fan out LISTING_MATCH to every user with a saved search that
        // matches the new listing (batch 266). Persistent presets become
        // useful — they actually notify on hits, not just sit in the
        // dropdown waiting to be re-applied. Best-effort, capped + isolated
        // so it can't roll back the save.
        try {
            savedSearchService?.notifyMatchingForListing(saved)
        } catch (Exception e) {
            log.warn("Saved-search fanout failed for listing ${saved.id}: ${e.message}")
        }
        saved
    }

    List<Listing> findActiveBySeller(Long sellerUserId) {
        listingRepository.findActiveBySeller(sellerUserId)
    }

    /** Paged variant — caps the hydrated set for the /my-stall endpoint
     *  so a prolific seller with thousands of active listings doesn't
     *  force the server to JOIN-FETCH every row. Clamped [1, 500]. */
    List<Listing> findActiveBySeller(Long sellerUserId, int limit) {
        if (sellerUserId == null) return []
        int cap = Math.max(1, Math.min(limit, 500))
        listingRepository.findActiveBySellerPaged(sellerUserId,
            org.springframework.data.domain.PageRequest.of(0, cap)) ?: []
    }

    /** Indexed COUNT — used by the public-stall controller for the
     *  away-mode derivation so we don't hydrate every Listing row just
     *  to call .size() on it. */
    long countActiveBySeller(Long sellerUserId) {
        if (sellerUserId == null) return 0L
        listingRepository.countActiveBySeller(sellerUserId)
    }

    /** Most-recent listedAt across the seller's ACTIVE listings. Null
     *  when they have none. Feeds the stall-hero "Last listed Xh ago"
     *  trust signal (batch 1045). */
    Long lastListedAtBySeller(Long sellerUserId) {
        if (sellerUserId == null) return null
        listingRepository.findLastListedAtBySeller(sellerUserId)
    }

    /** Most-recent soldAt across the seller's sold listings. Null when
     *  they have no sale history. Feeds the stall-hero "Last sold" chip
     *  (batch 1047) so buyers can read sales activity independently
     *  from listing activity. */
    Long lastSoldAtBySeller(Long sellerUserId) {
        if (sellerUserId == null) return null
        listingRepository.findLastSoldAtBySeller(sellerUserId)
    }

    /** "More from this seller" feed for the item-detail modal — visible
     *  active listings from the same seller, excluding the item the
     *  user is currently looking at. Empty list for system listings
     *  (uid null) or zero-other-listings sellers. Caller-bounded cap,
     *  clamped to [1, 30] so a crafted query can't dump the whole
     *  stall. */
    List<Listing> findOtherActiveBySeller(Long sellerUserId, Long excludeItemId, int limit) {
        if (sellerUserId == null || excludeItemId == null) return []
        int cap = Math.max(1, Math.min(limit, 30))
        listingRepository.findOtherActiveBySeller(sellerUserId, excludeItemId,
            org.springframework.data.domain.PageRequest.of(0, cap))
    }

    List<Listing> findActiveVisibleBySeller(Long sellerUserId) {
        listingRepository.findActiveVisibleBySeller(sellerUserId)
    }

    /** Paged variant — the public /api/listings/stall/{userId} endpoint
     *  uses this to cap the hydrated set so a prolific seller's stall
     *  page doesn't ship thousands of rows to every visitor. Clamped
     *  [1, 500], matching the MyStall display cap (batch 1033). */
    List<Listing> findActiveVisibleBySeller(Long sellerUserId, int limit) {
        if (sellerUserId == null) return []
        int cap = Math.max(1, Math.min(limit, 500))
        listingRepository.findActiveVisibleBySellerPaged(sellerUserId,
            org.springframework.data.domain.PageRequest.of(0, cap)) ?: []
    }

    /** Auctions ending within a window. Thin pass-through to the repo so
     *  the controller stays test-friendly (can mock the service instead of
     *  wiring a full repository). */
    List<Listing> findAuctionsEndingBefore(Long now, Long deadline) {
        listingRepository.findAuctionsEndingBefore(now, deadline)
    }

    /** Newest active listings, capped. Drives the homepage "Just listed"
     *  rail. Hidden rows are excluded at the SQL layer now (batch 307),
     *  so this is just a head-of-list take(). */
    List<Listing> findNewestActive(int cap) {
        listingRepository.findActiveOrderByNewest().take(cap)
    }

    /** Lifetime sold count for a seller — feeds the verified badge
     *  threshold and the stall-hero "sales" stat. */
    long countSoldBySeller(Long sellerUserId) {
        listingRepository.countSoldBySeller(sellerUserId)
    }

    /** Last-N-days sold count (batch 534). Drives the "N sold last 30d"
     *  activity chip on the public stall hero so buyers can tell if the
     *  seller is actively moving inventory vs. sitting on old listings. */
    long countSoldBySellerSince(Long sellerUserId, long sinceMs) {
        listingRepository.countSoldBySellerSince(sellerUserId, sinceMs)
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
     *  doesn't pull entire Listing entities into the response JSON.
     *  Includes `sellerUserId` alongside the display name so the ticker
     *  can render a clickable link to the seller's stall — previously
     *  the ticker showed the name as plain text with no way to navigate
     *  to the seller.
     *
     *  Over-fetches the raw rows and filters banned sellers out in
     *  Groovy before truncating back to `limit`, matching the Top
     *  Sellers rail's behaviour (batch 352). A banned seller's last
     *  sale dangling on the homepage ticker 30 minutes after the ban
     *  was a quiet gap — ticker IDs + stall link remained navigable
     *  even though the stall itself 404s post-ban.
     */
    List<Map> findRecentSales(int limit) {
        def lim = Math.min(Math.max(limit, 1), 30)
        // Over-fetch by 2x so banned-seller eviction doesn't undershoot.
        // With a 30-row cap and typical <5% ban rate, 60 raw rows almost
        // always yields at least `lim` visible rows.
        def raw = listingRepository.findRecentlySold(
            org.springframework.data.domain.PageRequest.of(0, Math.min(lim * 2, 60)))
        def bannedIds = new HashSet<Long>()
        if (steamUserRepository != null && !raw.isEmpty()) {
            // One batched probe — existence of a banned flag on the
            // seller. Skips anonymous `sellerUserId == null` rows.
            def uniq = raw*.sellerUserId.findAll { it != null } as Set<Long>
            if (!uniq.isEmpty()) {
                steamUserRepository.findAllById(uniq).each { u ->
                    if (Boolean.TRUE.equals(u.banned)) bannedIds << u.id
                }
            }
        }
        raw.findAll { l -> l.sellerUserId == null || !bannedIds.contains(l.sellerUserId) }
           .take(lim)
           .collect { l ->
            [
                listingId:    l.id,
                itemId:       l.item?.id,
                itemName:     l.item?.name,
                category:     l.item?.category,
                rarity:       l.item?.rarity,
                imageUrl:     l.item?.imageUrl,
                price:        l.price,
                steamPrice:   l.item?.steamPrice,
                soldAt:       l.soldAt,
                sellerName:   l.sellerName,
                sellerUserId: l.sellerUserId
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
        // Track (listingId, oldPrice, newPrice) so we can fire
        // PRICE_DROPPED pings after the save for listings that went
        // DOWN in price (batch 539).
        def priceDrops = []
        int touched = 0, skipped = 0
        active.each { l ->
            if (l.listingType == 'AUCTION') { skipped++; return }
            def newPrice = (l.price * factor).setScale(2, BigDecimal.ROUND_HALF_UP)
            // Respect the same bounds as the single-listing editor.
            if (newPrice < new BigDecimal('0.01')) newPrice = new BigDecimal('0.01')
            if (newPrice > new BigDecimal('100000')) newPrice = new BigDecimal('100000')
            if (newPrice == l.price) { skipped++; return }
            if (newPrice < l.price) {
                priceDrops << [listingId: l.id, itemId: l.item?.id,
                               itemName: l.item?.name, oldPrice: l.price, newPrice: newPrice]
            }
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
        // PRICE_DROPPED fan-out for cart-holders (batch 539). Mirrors
        // the single-listing editor in ListingController. Cap the
        // fan-out per listing so a mass -50% on 100 listings doesn't
        // spam thousands of bells. Per-row try/catch.
        if (!priceDrops.isEmpty() && cartItemRepository != null && notificationService != null) {
            priceDrops.each { drop ->
                try {
                    def others = cartItemRepository.findOtherUsersWithListing(
                        drop.listingId as Long, sellerUserId) ?: []
                    if (others.isEmpty()) return
                    def itemName = drop.itemName ?: 'an item in your cart'
                    def itemId = drop.itemId as Long
                    def oldP = drop.oldPrice as BigDecimal
                    def newP = drop.newPrice as BigDecimal
                    def pct = oldP > BigDecimal.ZERO
                        ? ((oldP - newP).divide(oldP, 2, java.math.RoundingMode.HALF_UP)
                               .multiply(new BigDecimal('100'))).intValue()
                        : 0
                    others.take(50).each { uid ->
                        try {
                            notificationService.push(uid, 'PRICE_DROPPED',
                                "Cart item price drop · ${itemName}",
                                "${itemName} dropped from \$${oldP.toPlainString()} to \$${newP.toPlainString()}" +
                                    (pct > 0 ? " (−${pct}%)" : '') + ". Check out before it sells.",
                                drop.listingId as Long,
                                itemId != null ? "/item/${itemId}" : '/cart')
                        } catch (Exception e) {
                            log.warn("PRICE_DROPPED (bulk) push failed for uid=${uid}: ${e.message}")
                        }
                    }
                } catch (Exception e) {
                    log.warn("PRICE_DROPPED (bulk) fan-out failed for listing=${drop.listingId}: ${e.message}")
                }
            }
        }
        [touched: touched, skipped: skipped, percent: percent]
    }

    List<Listing> findOwnedBy(Long buyerUserId) {
        listingRepository.findOwnedBy(buyerUserId) ?: []
    }

    /** Paged variant — caps the hydrated set so the /inventory endpoint
     *  can't accidentally serialise 10k rows for a long-tenure user. */
    List<Listing> findOwnedBy(Long buyerUserId, int limit) {
        if (buyerUserId == null) return []
        int cap = Math.max(1, Math.min(limit, 500))
        listingRepository.findOwnedByPaged(buyerUserId,
            org.springframework.data.domain.PageRequest.of(0, cap)) ?: []
    }

    /** True row count across the user's inventory — used by the
     *  /inventory endpoint's X-Total-Count header so the modal can
     *  render "Showing most recent 500 of N" when the display cap
     *  is hit. */
    long countOwnedBy(Long buyerUserId) {
        if (buyerUserId == null) return 0L
        listingRepository.countOwnedBy(buyerUserId)
    }

    // Market-stats cache (batch 561). /api/listings/stats is called on
    // every homepage hit + polled by the hero ticker; four DB round-trips
    // per call on a busy site adds up fast. 30-second TTL is fresh
    // enough that the "LIVE SALES" claim isn't a lie but coarse enough
    // that one popular-item spike doesn't flatten Postgres. volatile
    // snapshot + millis timestamp = zero lock contention on the hot path.
    private volatile Map<String, Object> marketStatsCache = null
    private volatile long                marketStatsCacheAt = 0L
    private static final long MARKET_STATS_TTL_MS = 30_000L

    Map<String, Object> getMarketStats() {
        def cached = marketStatsCache
        long cachedAt = marketStatsCacheAt
        long now = System.currentTimeMillis()
        if (cached != null && (now - cachedAt) < MARKET_STATS_TTL_MS) {
            return cached
        }
        long since24h = now - 86_400_000L
        long since7d  = now - 7L * 86_400_000L
        def volume = listingRepository.sumVolumeAfter(since24h) ?: BigDecimal.ZERO
        // Batch 1054 — 24h sale count. Paired with volume24h this reads
        // as "N sales totalling $X today" — a richer liveness chip
        // than either figure alone.
        def sold24h = listingRepository.countSoldAfter(since24h) ?: 0L
        // Batch 1056 — 7-day sale count, same shape. Paired with
        // volume7d for the longer-window chip: "$5,000 · 80 sales"
        // reads as a weekly throughput indicator.
        def sold7d  = listingRepository.countSoldAfter(since7d) ?: 0L
        // Batch 1048 — also surface 7d volume as a longer-window trust
        // signal. 24h alone can look soft on a quiet day; a 7d figure
        // smooths the weekly cycle while still reflecting "real current
        // activity". Both share the same indexed sumVolumeAfter query.
        def volume7d = listingRepository.sumVolumeAfter(since7d) ?: BigDecimal.ZERO
        def activeCount = listingRepository.countActive()
        def activeAuctions = listingRepository.countActiveAuctions(now) ?: 0L
        def activeSellers = listingRepository.countActiveSellers() ?: 0L
        def floor = listingRepository.findMinActivePrice() ?: BigDecimal.ZERO
        // Batch 1052 — ceiling + most-recent sale, two more liveness
        // signals. `ceilingPrice` lets the strip render a price RANGE
        // ("from $1 to $1,600") instead of just a floor. `lastSaleAt`
        // drives a "Last sale 5m ago" chip — strongest possible "this
        // marketplace is alive right now" evidence for anon visitors.
        def ceiling = listingRepository.findMaxActivePrice() ?: BigDecimal.ZERO
        def lastSaleAt = listingRepository.findLastSaleAt()

        def snapshot = [
            volume24h    : volume.setScale(2, BigDecimal.ROUND_HALF_UP),
            sold24h      : sold24h,
            volume7d     : volume7d.setScale(2, BigDecimal.ROUND_HALF_UP),
            sold7d       : sold7d,
            activeListings: activeCount,
            activeAuctions: activeAuctions,
            // Batch 1050 — count of DISTINCT sellers with at least one
            // ACTIVE listing. "41 listings from 12 sellers" is a stronger
            // liquidity signal than raw listing count: it tells an anon
            // visitor the marketplace has counterparty diversity, not
            // just one prolific seller stacking rows.
            activeSellers: activeSellers,
            floorPrice   : floor.setScale(2, BigDecimal.ROUND_HALF_UP),
            ceilingPrice : ceiling.setScale(2, BigDecimal.ROUND_HALF_UP),
            lastSaleAt   : lastSaleAt,
        ] as Map<String, Object>
        // Publish order matters — set the map before the timestamp so a
        // concurrent reader can't observe a fresh timestamp with a stale
        // snapshot. volatile writes give us the happens-before guarantee.
        marketStatsCache   = snapshot
        marketStatsCacheAt = now
        snapshot
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
            // Keep `isListed` in sync with reality: true iff at least one
            // active non-hidden listing exists. The flag was default-true
            // at create time and never flipped back, so the Database
            // page's "Listed only" filter (batch 235) was effectively a
            // no-op — every item looked "listed" forever. Using the
            // `floor > 0` sentinel piggybacks on the same SQL the floor
            // update just ran, so no extra query.
            item.isListed = (floor != null && floor > BigDecimal.ZERO)
            itemRepository.save(item)
        }
    }
}
