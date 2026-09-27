package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import spock.lang.Specification

/**
 * Pins the privacy contract on Listing.endingSoonNotified: this field is
 * pure internal scheduler bookkeeping (BidService.sweepEndingSoon flips it
 * after firing the 10-minute close reminder so the next 2-minute tick
 * doesn't re-notify the same bidders + watchers) and MUST NEVER reach the
 * public listing wire payload.
 *
 * Why this is a contract: the listing entity is fanned out unchanged on
 * /api/listings/just-listed, /top-deals, /most-watched, /search,
 * /trending — i.e. every anonymous-readable surface. Leaking
 * endingSoonNotified hands a competitor's snipe tool a free "the 10-min
 * close-warning push has already fired" signal AHEAD of opening the
 * auction page, which lets them race the wave of last-minute bidders that
 * the notification was meant to summon. Cosmetically the field is also a
 * confusing "true/false on a listing that isn't even an auction" payload
 * smell — non-AUCTION rows always carry the default FALSE and never read
 * it. The @JsonIgnore on the model keeps the DB column for the sweeper
 * and removes the field from every JSON response.
 *
 * A pure-Jackson check (no SpringBoot) — fastest possible regression pin.
 */
class ListingEndingSoonNotifiedRedactionSpec extends Specification {

    def "Listing.endingSoonNotified never appears in the Jackson-serialized JSON"() {
        given:
        def listing = new Listing(
            id:                  1L,
            item:                new Item(id: 99L, name: 'Dummy', category: 'Hats',
                                          rarity: 'Limited', supply: 1, totalSold: 0,
                                          lowestPrice: BigDecimal.ZERO, iconEmoji: '🎩'),
            price:               new BigDecimal('5.00'),
            status:              'ACTIVE',
            sellerName:          'Seller',
            condition:           'Factory New',
            rarityScore:         BigDecimal.ZERO,
            listingType:         'AUCTION',
            // Force the field NOT to be its default — if @JsonIgnore is
            // ever dropped, a true value would land in the payload and
            // this assertion catches it.
            endingSoonNotified:  true
        )
        def mapper = new ObjectMapper()

        when:
        String json = mapper.writeValueAsString(listing)

        then: 'no key, no value — the scheduler signal stays server-side'
        !json.contains('endingSoonNotified')
        !json.contains('ending_soon_notified')
        // Sanity — Jackson actually serialised something, we're not
        // asserting against an empty string.
        json.contains('"listingType":"AUCTION"')
    }
}
