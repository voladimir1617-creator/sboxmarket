package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.service.PriceHistoryService
import com.sboxmarket.service.SteamMarketPriceService
import spock.lang.Specification

/**
 * Regression for the loop's `priceHistoryService.record()` call: it must
 * NOT stamp `price_history` with Steam's market `bestPrice` for LISTED
 * items.
 *
 * Why this is a bug worth pinning:
 *
 *   • `applyPriceUpdate` intentionally preserves `item.lowestPrice` when
 *     `item.isListed == true` — the platform floor is owned by
 *     `ListingService.updateItemFloorPrice`, not by Steam's market sweep.
 *     The docstring on `applyPriceUpdate` is explicit: clobbering it with
 *     Steam's (usually lower) market floor drifts the grid card away from
 *     the real cheapest listing and surprises buyers at click-through.
 *
 *   • But the SAME loop, two lines after the guarded `applyPriceUpdate`,
 *     was calling `priceHistoryService.record(item, bestPrice)`
 *     UNCONDITIONALLY whenever `bestPrice > 0`. So for every listed item
 *     on every 30-min sync, a fresh price_history row was being stamped
 *     with Steam's market price — a price the grid never quoted and the
 *     platform never settled at.
 *
 *   • Downstream: the item-detail-modal sparkline reads price_history.
 *     Pre-fix it trended toward Steam's market floor while the grid card
 *     read the platform floor — buyers saw "$3 last week → $5 now" on
 *     a card that had always been $5 on the platform. Polluted history,
 *     untrustworthy chart.
 *
 *   • PurchaseService and BidService already record the true platform
 *     price into the same table for listed items (actual sales /
 *     auction settles), so suppressing the Steam-market sweep's listed-item
 *     write does NOT empty the chart — it just keeps it honest.
 *
 * The fix is a one-line `if (!item.isListed)` guard on the record() call
 * inside the sync loop. This spec pins that guard.
 */
class SteamMarketPriceServiceListedRecordGuardSpec extends Specification {

    ItemRepository itemRepository = Mock()
    PriceHistoryService priceHistoryService = Mock()

    SteamMarketPriceService service

    def setup() {
        // Spy so we can stub `fetchSteamPrice` without hitting Steam over
        // the network. The loop calls fetchSteamPrice through the spy and
        // gets our canned response; every other method on the spy runs the
        // real production code (in particular applyPriceUpdate, which we
        // explicitly want under test as part of the loop).
        service = Spy(SteamMarketPriceService) {
            // One canned priced response — the loop iterates once per item
            // in our two-item catalogue, so respond to either call.
            fetchSteamPrice(_, _) >> [
                lowest: new BigDecimal('3.00'),
                median: new BigDecimal('3.10')
            ]
        }
        service.itemRepository = itemRepository
        service.priceHistoryService = priceHistoryService
    }

    def "loop does NOT record price_history for a LISTED item even when a Steam-market bestPrice is fetched"() {
        given: "one listed item — platform floor 5.00, Steam-market floor 3.00"
        def listed = new Item(
            id: 1L,
            name:        'AK-47 | Redline (Field-Tested)',
            category:    'Workshop',
            rarity:      'Standard',
            isListed:    true,
            lowestPrice: new BigDecimal('5.00'),
            steamPrice:  new BigDecimal('4.00'),
            trendPercent: 0
        )
        itemRepository.findAll() >> [listed]

        when:
        service.syncPricesFromSteamBody()

        then: "applyPriceUpdate preserved the platform floor (sanity check, batch 954)"
        listed.lowestPrice == new BigDecimal('5.00')

        and: "the item was still persisted (steamPrice / trendPercent updates do persist)"
        1 * itemRepository.save(listed)

        and: "BUT price_history was NOT stamped with Steam's market price — the platform " +
             "floor never moved, so writing 3.00 into history would lie to the sparkline"
        0 * priceHistoryService.record(_, _)
        0 * priceHistoryService.record(_, _, _)

        and: "single-flight Thread.sleep at end of loop is harmless — the suite tolerates the 8s tick"
        // (No additional assertion needed; the spec timing is bounded by Spring Boot's
        // default test timeout, well above 8 seconds.)
        true
    }

    def "loop DOES record price_history for an UNLISTED item — the Steam-market price IS the new floor"() {
        given: "one unlisted item — no platform listing, Steam-market floor 3.00 becomes the floor"
        def unlisted = new Item(
            id: 2L,
            name:        'M4A4 | Howl (Factory New)',
            category:    'Workshop',
            rarity:      'Standard',
            isListed:    false,
            lowestPrice: new BigDecimal('4.00'),
            steamPrice:  new BigDecimal('3.50'),
            trendPercent: 0
        )
        itemRepository.findAll() >> [unlisted]

        when:
        service.syncPricesFromSteamBody()

        then: "applyPriceUpdate adopted the Steam-market bestPrice as the platform floor"
        unlisted.lowestPrice == new BigDecimal('3.00')

        and: "the item was persisted"
        1 * itemRepository.save(unlisted)

        and: "price_history was stamped — the platform floor changed, the chart should reflect it"
        1 * priceHistoryService.record(unlisted, new BigDecimal('3.00'))
    }
}
