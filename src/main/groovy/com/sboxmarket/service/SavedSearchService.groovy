package com.sboxmarket.service

import com.sboxmarket.model.SavedSearch
import com.sboxmarket.repository.SavedSearchRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Server-side saved-search persistence (cross-device sync). The
 * frontend keeps a localStorage cache for offline reads; this service
 * is the source of truth for signed-in users.
 *
 * Per-user cap mirrors the existing localStorage limit so re-applying
 * the same UX on the server doesn't surprise existing users.
 */
@Service
@Slf4j
class SavedSearchService {

    static final int MAX_PER_USER = 10
    // Batch 660 — the category/rarity whitelists live in the shared
    // `com.sboxmarket.util.ListingEnums` util (single source of truth
    // across controllers + services). Sort stays here because it's
    // service-local and has no parallel on the listings query path.
    private static final Set<String> ALLOWED_SORTS = ['price_desc','price_asc','newest','rarity','discount','ending_soon'].toSet()
    // Batch 957 — listingType whitelist. Mirrors the frontend chip row
    // (ALL / BUY_NOW / AUCTION) so a malformed share URL falls back to
    // the widest view rather than silently narrowing to an invalid bucket.
    private static final Set<String> ALLOWED_LISTING_TYPES = ['ALL','BUY_NOW','AUCTION'].toSet()

    @Autowired SavedSearchRepository repository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository
    @Autowired(required = false) EmailService emailService

    /**
     * Upsert a preset. If the same `(userId, name)` already exists, the
     * existing row's filters are overwritten and the existing id is
     * returned — no duplicate row created. Returns the persisted entity.
     */
    @Transactional
    SavedSearch upsert(Long userId, Map payload) {
        if (userId == null || payload == null) {
            throw new com.sboxmarket.exception.BadRequestException('MISSING_FIELD',
                'A saved-search payload is required')
        }
        def name = (payload.name as String ?: '').trim().take(80)
        if (!name) {
            throw new com.sboxmarket.exception.BadRequestException('MISSING_FIELD',
                "'name' is required")
        }
        // Batch 660 — category/rarity normalise through the shared
        // case-insensitive canonEnum helper, mirroring the listings
        // query fix from batches 656-659. A saved search created from
        // a share URL carrying `category=hats` now stores 'Hats' (the
        // canonical form) instead of silently collapsing to 'All'.
        def category = com.sboxmarket.util.ListingEnums.canonEnum(
            payload.category as String, com.sboxmarket.util.ListingEnums.CATEGORIES, 'All')
        def rarity   = com.sboxmarket.util.ListingEnums.canonEnum(
            payload.rarity   as String, com.sboxmarket.util.ListingEnums.RARITIES,   'All')
        // Sort whitelist stays case-sensitive — sort ids are machine-
        // generated (`price_desc` etc.) and never appear in a share URL
        // with mixed case, so the simple Set.contains check is fine.
        def sort     = sanitiseEnum(payload.sort     as String, ALLOWED_SORTS,      'price_desc')
        def q        = (payload.q ?: payload.search ?: '') as String
        def minPrice = (payload.minPrice as String ?: '').take(16)
        def maxPrice = (payload.maxPrice as String ?: '').take(16)
        // Batch 957 — the extended filter set. Clamp numerics, whitelist
        // enums. Booleans pass through with a null-safe default.
        int minDisc = 0
        try { minDisc = Math.max(0, Math.min(99, ((payload.minDiscountPct ?: 0) as Number).intValue())) }
        catch (Exception ignore) { minDisc = 0 }
        boolean dealsOnly      = Boolean.TRUE.equals(payload.dealsOnly)
        boolean newOnly        = Boolean.TRUE.equals(payload.newOnly)
        boolean affordableOnly = Boolean.TRUE.equals(payload.affordableOnly)
        String listingType = sanitiseEnum(payload.listingType as String, ALLOWED_LISTING_TYPES, 'ALL')

        def existing = repository.findByUserAndName(userId, name)
        if (existing != null) {
            existing.q               = q.take(80)
            existing.category        = category
            existing.rarity          = rarity
            existing.sort            = sort
            existing.minPrice        = minPrice
            existing.maxPrice        = maxPrice
            existing.minDiscountPct  = minDisc
            existing.dealsOnly       = dealsOnly
            existing.newOnly         = newOnly
            existing.affordableOnly  = affordableOnly
            existing.listingType     = listingType
            return repository.save(existing)
        }
        if (repository.countByUser(userId) >= MAX_PER_USER) {
            throw new com.sboxmarket.exception.BadRequestException('SAVED_SEARCHES_FULL',
                "You can save at most ${MAX_PER_USER} searches. Delete one before saving a new one.")
        }
        repository.save(new SavedSearch(
            userId:          userId,
            name:            name,
            q:               q.take(80),
            category:        category,
            rarity:          rarity,
            sort:            sort,
            minPrice:        minPrice,
            maxPrice:        maxPrice,
            minDiscountPct:  minDisc,
            dealsOnly:       dealsOnly,
            newOnly:         newOnly,
            affordableOnly:  affordableOnly,
            listingType:     listingType
        ))
    }

    @Transactional
    boolean delete(Long userId, Long id) {
        if (userId == null || id == null) return false
        repository.deleteByUserAndId(userId, id) > 0
    }

    /** Wipe every saved search the user owns. Powers the Profile →
     *  Saved Searches "Clear all" shortcut — parity with the follow +
     *  watchlist bulk-delete affordances (batch 353). Returns the
     *  row count actually removed. */
    @Transactional
    int deleteAllForUser(Long userId) {
        if (userId == null) return 0
        repository.deleteByUser(userId)
    }

    List<SavedSearch> list(Long userId) {
        if (userId == null) return []
        repository.findByUser(userId)
    }

    /**
     * One-shot bridge for the rollout: client posts its localStorage
     * preset list, server upserts each one (skipping any that put the
     * user over the cap). Returns the post-merge list so the client
     * can replace its cache atomically.
     */
    @Transactional
    List<SavedSearch> bulkMerge(Long userId, List<Map> incoming) {
        if (userId == null) return []
        def cleaned = (incoming ?: []).findAll { it != null && it.name }
        // Collapse name-duplicates within the input — last write wins.
        def byName = [:]
        cleaned.each { byName[(it.name as String).trim().toLowerCase()] = it }
        def headroom = MAX_PER_USER - repository.countByUser(userId)
        def candidates = byName.values() as List
        if (headroom < candidates.size()) candidates = candidates.take(Math.max(0, (int) headroom))
        candidates.each { row ->
            try {
                upsert(userId, row as Map)
            } catch (Exception e) {
                log.debug("saved-search merge skipped '${row?.name}' for user ${userId}: ${e.message}")
            }
        }
        list(userId)
    }

    private static String sanitiseEnum(String value, Set<String> whitelist, String fallback) {
        (value && whitelist.contains(value)) ? value : fallback
    }

    /**
     * Pure-function predicate: does this listing match the preset?
     *
     *   - category: 'All' or string match against listing.item.category
     *   - rarity:   'All' or string match against listing.item.rarity
     *   - q:        empty or case-insensitive substring of listing.item.name
     *   - minPrice / maxPrice: empty or numeric bounds inclusive
     *
     * Static + null-safe so the spec can call it directly without
     * standing up a service. Returns false on any unparseable input
     * (rather than throwing) — a malformed preset row should silently
     * skip, not break the listing-creation flow.
     */
    static boolean matches(SavedSearch preset, com.sboxmarket.model.Listing listing) {
        if (preset == null || listing == null) return false
        def item = listing.item
        if (item == null) return false
        // Category
        if (preset.category && preset.category != 'All' &&
            preset.category != item.category) return false
        // Rarity
        if (preset.rarity && preset.rarity != 'All' &&
            preset.rarity != item.rarity) return false
        // Text query — substring match against the catalogue name
        def q = (preset.q ?: '').trim()
        if (q && (item.name == null || !item.name.toLowerCase().contains(q.toLowerCase()))) {
            return false
        }
        // Price bounds — both stored as strings (legacy localStorage shape)
        def price = listing.price
        if (price == null) return false
        try {
            if (preset.minPrice && !preset.minPrice.isEmpty()) {
                def floor = new BigDecimal(preset.minPrice)
                if (price < floor) return false
            }
            if (preset.maxPrice && !preset.maxPrice.isEmpty()) {
                def ceil = new BigDecimal(preset.maxPrice)
                if (price > ceil) return false
            }
        } catch (NumberFormatException ignored) {
            // Bad bounds in the preset → conservative: don't match
            return false
        }
        // Batch 957 — extended filters. Order matters: cheaper checks
        // first, discount maths last.
        if (preset.listingType && preset.listingType != 'ALL') {
            if ((listing.listingType ?: 'BUY_NOW') != preset.listingType) return false
        }
        if (Boolean.TRUE.equals(preset.newOnly)) {
            // "New" == listed within the last 24h. Matches the frontend
            // `newOnly` chip predicate so server + client agree on the
            // bucket boundary.
            def listedAt = listing.listedAt ?: 0L
            def cutoff = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)
            if (listedAt < cutoff) return false
        }
        // Discount gate — skip listings whose `steamPrice` is missing/0
        // (can't compute a meaningful percent). dealsOnly is the 1% gate,
        // minDiscountPct is the user-picked floor; both apply together.
        if (Boolean.TRUE.equals(preset.dealsOnly) || (preset.minDiscountPct ?: 0) > 0) {
            def steamPrice = listing.item?.steamPrice
            if (steamPrice == null || steamPrice <= BigDecimal.ZERO) return false
            if (price >= steamPrice) return false
            def discFloor = Math.max(
                Boolean.TRUE.equals(preset.dealsOnly) ? 1 : 0,
                (preset.minDiscountPct ?: 0) as int
            )
            def actual = (steamPrice - price) * 100G / steamPrice
            if (actual < discFloor) return false
        }
        // affordableOnly is intentionally NOT applied here: it's a wallet-
        // balance-relative filter that belongs on the viewer's render
        // path, not on the fanout predicate (no wallet in scope here, and
        // match notifications fire regardless of the user's current
        // balance — seeing "this matches, top up to grab it" is valuable).
        true
    }

    /**
     * Fan a `LISTING_MATCH` notification out to every user with a
     * saved search that matches this listing. Skips the seller's own
     * presets — they don't need to be told their own listing matches
     * their own filter. Capped at 50 notifications per call to absorb
     * a once-in-blue-moon viral preset without blowing up.
     *
     * Best-effort: per-row push failures are logged but never bubble
     * up so a flaky NotificationService doesn't block the listing
     * creation flow.
     */
    void notifyMatchingForListing(com.sboxmarket.model.Listing listing) {
        if (listing?.item == null || notificationService == null) return
        // Batch 619 — narrowed from `findAll()` to a repo query that
        // pre-filters by the listing's category + rarity. Presets that
        // don't constrain those fields (empty strings) still flow
        // through. At platform scale this cuts the candidate set from
        // "every saved search ever" to "only those targeting this
        // category/rarity", a 10-100x reduction for typical browsing.
        def all
        def cat = listing.item?.category ?: ''
        def rar = listing.item?.rarity ?: ''
        try { all = repository.findCandidatesForListing(cat, rar) }
        catch (Exception e) { log.warn("saved-search scan failed: ${e.message}"); return }
        if (all == null || all.isEmpty()) return
        int sent = 0
        for (SavedSearch preset : all) {
            if (sent >= 50) break
            if (preset.userId == null) continue
            if (preset.userId == listing.sellerUserId) continue
            if (!matches(preset, listing)) continue
            // Single user lookup — reused for both the banned-account
            // skip AND the email-prefs gate below, so we spend at most
            // one query per matching preset (batch 315).
            def user = null
            try {
                if (steamUserRepository != null) {
                    user = steamUserRepository.findById(preset.userId).orElse(null)
                }
            } catch (Exception e) {
                log.warn("saved-search user lookup failed for ${preset.userId}: ${e.message}")
            }
            // Skip banned account — ban guard blocks writes but it
            // doesn't block engagement pings. A banned buyer who'd set
            // up a saved search pre-ban would otherwise keep getting
            // LISTING_MATCH bell + email pings on every matching listing.
            if (user != null && Boolean.TRUE.equals(user.banned)) continue
            try {
                def title = "Match for \"${preset.name}\""
                def priceStr = listing.price != null ? "\$${listing.price.toPlainString()}" : ''
                def body = "${listing.item?.name ?: 'a new item'} · ${priceStr}".trim()
                def itemUrl = listing.item?.id != null ? "/item/${listing.item.id}".toString() : null
                notificationService.push(preset.userId, 'LISTING_MATCH',
                    title.toString(), body.toString(), listing.id, itemUrl)
                // Mirror to email — gated on the global toggle, verified
                // address, and the per-bucket MATCHES mute (shipped in
                // batch 257). Best-effort: failure here doesn't undo
                // the bell push or the sent counter.
                try {
                    if (emailService != null && emailService.canSendTo(user, 'MATCHES')) {
                        emailService.sendSavedSearchMatch(user.email, user.displayName,
                            preset.name, listing.item?.name, listing.price, itemUrl)
                    }
                } catch (Exception emailErr) {
                    log.warn("saved-search match email failed for user ${preset.userId}: ${emailErr.message}")
                }
                sent++
            } catch (Exception e) {
                log.warn("saved-search match push failed for user ${preset.userId}: ${e.message}")
            }
        }
        if (sent > 0) log.info("Saved-search fanout: ${sent} match notification(s) for listing ${listing.id}")
    }
}
