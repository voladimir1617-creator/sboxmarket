package com.sboxmarket

import com.sboxmarket.util.ListingEnums
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit coverage for the shared canonical-enum normaliser. `canonEnum` /
 * `canonListingType` front EVERY user-supplied category / rarity /
 * listing-type filter on `/api/listings`, `/api/database`, and the
 * saved-search payload validator. The load-bearing contract is:
 *
 *   - A case-insensitive match returns the CANONICAL spelling (so the
 *     value can be compared against the DB column, which is stored in a
 *     fixed case).
 *   - Anything unrecognised — junk, an un-whitelisted-but-real value
 *     like `Workshop`, null, blank — collapses to the supplied safe
 *     fallback (`All` / null) so a crafted query never returns an
 *     unfiltered or empty grid by accident.
 *
 * Added in the final depth audit to close the coverage gap (this util
 * had no spec despite gating the public filter surface).
 */
class ListingEnumsSpec extends Specification {

    @Unroll
    def "canonEnum maps '#raw' to the canonical category '#expected'"() {
        expect:
        ListingEnums.canonEnum(raw, ListingEnums.CATEGORIES, 'All') == expected

        where:
        raw           || expected
        'Hats'        || 'Hats'         // exact
        'hats'        || 'Hats'         // lowercase → canonical case
        'HATS'        || 'Hats'         // uppercase → canonical case
        'HaTs'        || 'Hats'         // mixed
        'accessories' || 'Accessories'
        'All'         || 'All'
        'all'         || 'All'
    }

    @Unroll
    def "canonEnum collapses unknown category '#raw' to the fallback"() {
        expect:
        ListingEnums.canonEnum(raw, ListingEnums.CATEGORIES, 'All') == 'All'

        where:
        raw << [
            null,                    // missing param
            '',                      // blank
            'Workshop',              // a REAL data category, deliberately
                                     // not chip-filterable — must fall to
                                     // 'All' so Workshop items still show
            'DROP TABLE items',      // injection-shaped junk
            'hat',                   // near-miss (singular)
            ' Hats ',                // surrounding whitespace — not trimmed,
                                     // so it does NOT match → fallback
            'Hats\u0000'             // NUL-suffixed
        ]
    }

    @Unroll
    def "canonEnum maps '#raw' to the canonical rarity '#expected'"() {
        expect:
        ListingEnums.canonEnum(raw, ListingEnums.RARITIES, 'All') == expected

        where:
        raw          || expected
        'Limited'    || 'Limited'
        'limited'    || 'Limited'
        'off-market' || 'Off-Market'   // hyphenated canonical preserved
        'OFF-MARKET' || 'Off-Market'
        'standard'   || 'Standard'
        'All'        || 'All'
    }

    def "canonEnum collapses unknown rarity to fallback"() {
        expect:
        ListingEnums.canonEnum('Covert', ListingEnums.RARITIES, 'All') == 'All'
        ListingEnums.canonEnum('Industrial Grade', ListingEnums.RARITIES, 'All') == 'All'
        ListingEnums.canonEnum(null, ListingEnums.RARITIES, 'All') == 'All'
    }

    def "canonEnum honours a non-default fallback argument"() {
        expect:
        ListingEnums.canonEnum('junk', ListingEnums.CATEGORIES, 'Hats') == 'Hats'
        ListingEnums.canonEnum(null,   ListingEnums.RARITIES,   'Standard') == 'Standard'
    }

    @Unroll
    def "canonListingType normalises '#raw' to '#expected'"() {
        expect:
        ListingEnums.canonListingType(raw) == expected

        where:
        raw         || expected
        'BUY_NOW'   || 'BUY_NOW'
        'buy_now'   || 'BUY_NOW'
        'Buy_Now'   || 'BUY_NOW'
        'AUCTION'   || 'AUCTION'
        'auction'   || 'AUCTION'
        'Auction'   || 'AUCTION'
    }

    @Unroll
    def "canonListingType returns null (filter disabled) for unknown input '#raw'"() {
        expect:
        ListingEnums.canonListingType(raw) == null

        where:
        raw << [null, '', 'AUCTIONS', 'buynow', 'sealed_bid', 'DROP', ' AUCTION ']
    }

    def "whitelists stay in sync with the frontend ALLOWED_* lists"() {
        // app.js:3338-3339 is the source of truth the doc comment points
        // at. If a category/rarity is added to one side and not the other,
        // a chip click silently returns an empty grid. Pin both sets.
        expect:
        ListingEnums.CATEGORIES == ['All', 'Hats', 'Jackets', 'Shirts',
                                    'Pants', 'Gloves', 'Boots', 'Accessories']
        ListingEnums.RARITIES   == ['All', 'Limited', 'Off-Market', 'Standard']
        ListingEnums.LISTING_TYPES == ['BUY_NOW', 'AUCTION']
    }
}
