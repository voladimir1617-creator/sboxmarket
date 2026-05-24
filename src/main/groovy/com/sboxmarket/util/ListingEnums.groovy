package com.sboxmarket.util

/**
 * Shared canonical-enum lists + case-insensitive normaliser used by
 * `ListingController`, `ItemController`, and `DatabaseController`.
 *
 * Batch 656/657/658 introduced `canonEnum` inline in each of those
 * controllers to fix a class of bug where `?category=hats` /
 * `?rarity=standard` / `?listingType=auction` silently returned an
 * empty grid (or — worse — unfiltered results) because the DB column
 * is stored in a specific case. Three duplicate copies was enough of
 * a smell to warrant a shared home; this util holds the single source
 * of truth.
 *
 * Kept in sync with the frontend's `app.js` ALLOWED_CATEGORIES /
 * ALLOWED_RARITIES and the `SavedSearchService` whitelists. If you
 * add a new category or rarity, update all three sites.
 */
final class ListingEnums {

    private ListingEnums() { /* static-only */ }

    static final List<String> CATEGORIES  = ['All', 'Hats', 'Jackets', 'Shirts', 'Pants', 'Gloves', 'Boots', 'Accessories']
    static final List<String> RARITIES    = ['All', 'Limited', 'Off-Market', 'Standard']
    static final List<String> LISTING_TYPES = ['BUY_NOW', 'AUCTION']

    /**
     * Case-insensitive canonicalisation of an enum filter value.
     * Returns the canonical form from `canonical` if the raw value
     * matches (case-insensitive), or `fallback` otherwise. Null /
     * blank input also collapses to fallback.
     *
     * Pure function — safe to call from anywhere, no DB access, no
     * allocation beyond the returned String (the .find closure walks
     * a short fixed list).
     */
    static String canonEnum(String raw, List<String> canonical, String fallback) {
        if (raw == null || raw.isEmpty()) return fallback
        def match = canonical.find { it.equalsIgnoreCase(raw) }
        return match ?: fallback
    }

    /**
     * Convenience for the listing-type check used by the /api/listings
     * endpoint: accepts any case, returns the canonical uppercase form
     * when matched, or null (disables the filter) otherwise.
     */
    static String canonListingType(String raw) {
        if (raw == null || raw.isEmpty()) return null
        def upper = raw.toUpperCase()
        return (upper in LISTING_TYPES) ? upper : null
    }
}
