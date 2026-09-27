package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.ListingFloorRefreshService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the 60s catalogue-floor reconciliation sweep.
 * Invariants this pins down — every one of them is what keeps the
 * marketplace grid price honest:
 *
 *   - A per-item floor change (or isListed flip) writes the item;
 *     a steady-state item (floor already correct) is NOT re-saved,
 *     so the sweep doesn't churn row-versions every minute.
 *   - One item throwing does NOT abort the sweep — the loop is
 *     per-item isolated, so a single bad row can't freeze every
 *     other item's floor.
 *   - A null MIN(price) (no active listings) drives lowestPrice to
 *     ZERO and isListed to false.
 *   - The `enabled` gate fully short-circuits the sweep.
 *   - `getLastRunSummary()` reports the telemetry the admin Health
 *     tab + frontend freshness chip read.
 *
 * Added in the final depth audit to close the coverage gap (this
 * scheduled service had no spec).
 */
class ListingFloorRefreshServiceSpec extends Specification {

    ItemRepository itemRepository = Mock()
    ListingRepository listingRepository = Mock()

    @Subject
    ListingFloorRefreshService service = new ListingFloorRefreshService(
        itemRepository:    itemRepository,
        listingRepository: listingRepository,
        enabled:           true)

    def "writes an item whose floor moved"() {
        given: 'item floor is stale (10.00) — the cheapest active listing is 7.50'
        def item = new Item(id: 1L, name: 'Beanie',
            lowestPrice: new BigDecimal('10.00'), isListed: true)

        when:
        service.refreshAllFloors()

        then:
        1 * itemRepository.findAll() >> [item]
        1 * listingRepository.minPriceForItem(1L) >> new BigDecimal('7.50')
        1 * itemRepository.save({ Item it ->
            it.lowestPrice == new BigDecimal('7.50') && it.isListed
        })
        service.lastRunSummary.changed == 1
        service.lastRunSummary.checked == 1
    }

    def "does NOT re-save an item whose floor is already correct"() {
        given: 'steady state — DB floor already equals the live MIN'
        def item = new Item(id: 2L, name: 'Hoodie',
            lowestPrice: new BigDecimal('3.00'), isListed: true)

        when:
        service.refreshAllFloors()

        then:
        1 * itemRepository.findAll() >> [item]
        1 * listingRepository.minPriceForItem(2L) >> new BigDecimal('3.00')
        0 * itemRepository.save(_)            // no churn on the steady-state row
        service.lastRunSummary.changed == 0
        service.lastRunSummary.checked == 1
    }

    def "a null MIN(price) drives the item to ZERO floor + isListed=false"() {
        given: 'item still claims a floor but has no active listings left'
        def item = new Item(id: 3L, name: 'Top Hat',
            lowestPrice: new BigDecimal('8.40'), isListed: true)

        when:
        service.refreshAllFloors()

        then:
        1 * itemRepository.findAll() >> [item]
        1 * listingRepository.minPriceForItem(3L) >> null
        1 * itemRepository.save({ Item it ->
            it.lowestPrice == BigDecimal.ZERO && !it.isListed
        })
    }

    def "a zero MIN(price) is treated as not-listed"() {
        given:
        def item = new Item(id: 4L, name: 'Sunglasses',
            lowestPrice: new BigDecimal('0.65'), isListed: true)

        when:
        service.refreshAllFloors()

        then:
        1 * itemRepository.findAll() >> [item]
        // A 0.00 floor is not a real listed price — isListed must go false.
        1 * listingRepository.minPriceForItem(4L) >> BigDecimal.ZERO
        1 * itemRepository.save({ Item it -> !it.isListed && it.lowestPrice == BigDecimal.ZERO })
    }

    def "one item throwing does NOT abort the sweep (per-item isolation)"() {
        given: 'three items — the middle one blows up on the MIN query'
        def a = new Item(id: 10L, name: 'A', lowestPrice: new BigDecimal('1.00'), isListed: true)
        def b = new Item(id: 11L, name: 'B', lowestPrice: new BigDecimal('2.00'), isListed: true)
        def c = new Item(id: 12L, name: 'C', lowestPrice: new BigDecimal('3.00'), isListed: true)

        when:
        service.refreshAllFloors()

        then:
        1 * itemRepository.findAll() >> [a, b, c]
        1 * listingRepository.minPriceForItem(10L) >> new BigDecimal('0.50')   // moved
        1 * listingRepository.minPriceForItem(11L) >> { throw new RuntimeException('lock timeout') }
        1 * listingRepository.minPriceForItem(12L) >> new BigDecimal('2.50')   // moved
        // a and c still get saved — the b failure is swallowed per-item.
        1 * itemRepository.save({ Item it -> it.id == 10L })
        1 * itemRepository.save({ Item it -> it.id == 12L })
        0 * itemRepository.save({ Item it -> it.id == 11L })

        and: 'all three counted as checked, only the two healthy ones changed'
        service.lastRunSummary.checked == 3
        service.lastRunSummary.changed == 2
    }

    def "a save() failure on one item does not abort the rest"() {
        given:
        def a = new Item(id: 20L, name: 'A', lowestPrice: new BigDecimal('1.00'), isListed: true)
        def b = new Item(id: 21L, name: 'B', lowestPrice: new BigDecimal('2.00'), isListed: true)

        when:
        service.refreshAllFloors()

        then:
        1 * itemRepository.findAll() >> [a, b]
        1 * listingRepository.minPriceForItem(20L) >> new BigDecimal('0.10')
        1 * listingRepository.minPriceForItem(21L) >> new BigDecimal('0.20')
        1 * itemRepository.save({ Item it -> it.id == 20L }) >> { throw new RuntimeException('deadlock') }
        // b's save still runs even though a's threw.
        1 * itemRepository.save({ Item it -> it.id == 21L })
        notThrown(Exception)
    }

    def "the enabled gate fully short-circuits the sweep"() {
        given:
        service.enabled = false

        when:
        service.refreshAllFloors()

        then: 'no repository access at all when the feature flag is off'
        0 * itemRepository.findAll()
        0 * listingRepository.minPriceForItem(_)
        0 * itemRepository.save(_)
    }

    def "an empty catalogue completes cleanly with zero counters"() {
        when:
        service.refreshAllFloors()

        then:
        1 * itemRepository.findAll() >> []
        0 * itemRepository.save(_)
        service.lastRunSummary.checked == 0
        service.lastRunSummary.changed == 0
    }

    def "getLastRunSummary reports the telemetry shape the Health tab reads"() {
        given:
        def item = new Item(id: 30L, name: 'X', lowestPrice: BigDecimal.ZERO, isListed: false)

        when:
        service.refreshAllFloors()
        def summary = service.lastRunSummary

        then:
        1 * itemRepository.findAll() >> [item]
        1 * listingRepository.minPriceForItem(30L) >> null   // no change → no save
        0 * itemRepository.save(_)

        and: 'summary carries every field the UI freshness chip expects'
        summary.startedAt > 0
        summary.finishedAt >= summary.startedAt
        summary.durationMs >= 0
        summary.checked == 1
        summary.changed == 0
        summary.intervalMs == ListingFloorRefreshService.REFRESH_INTERVAL_MS
        summary.nextRunAt == summary.finishedAt + ListingFloorRefreshService.REFRESH_INTERVAL_MS
        summary.enabled == true
    }

    def "getLastRunSummary before any run reports a zeroed snapshot"() {
        when: 'never run — fresh service instance'
        def summary = service.lastRunSummary

        then:
        summary.startedAt == 0L
        summary.finishedAt == 0L
        summary.durationMs == 0L
        summary.nextRunAt == 0L          // no finished run → no projected next run
        summary.checked == 0
        summary.changed == 0
    }

    def "a null lowestPrice / isListed on the item is treated as ZERO / false"() {
        given: 'a pre-migration row with null denormalised fields'
        def item = new Item(id: 40L, name: 'Legacy')
        item.lowestPrice = null
        item.isListed = null

        when:
        service.refreshAllFloors()

        then:
        1 * itemRepository.findAll() >> [item]
        // Live floor is 5.00 → differs from the null-as-ZERO old value → save.
        1 * listingRepository.minPriceForItem(40L) >> new BigDecimal('5.00')
        1 * itemRepository.save({ Item it -> it.lowestPrice == new BigDecimal('5.00') && it.isListed })
    }
}
