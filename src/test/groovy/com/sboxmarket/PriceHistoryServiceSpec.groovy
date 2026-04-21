package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.service.PriceHistoryService
import spock.lang.Specification
import spock.lang.Subject

import java.text.SimpleDateFormat

/**
 * Coverage for the dedupe-by-day price history writer that feeds the
 * sparkline on the item detail modal. Pins:
 *   - Null / non-positive inputs are no-ops (guard rail; writer is
 *     called from a hot sync path and must never crash).
 *   - Same-calendar-day row → update-in-place (overwrites price, adds
 *     volume delta).
 *   - No same-day row → insert new row.
 *   - Volume delta null / zero → no bump on update; new rows still
 *     get 0 (not null) to keep the column totals clean.
 */
class PriceHistoryServiceSpec extends Specification {

    PriceHistoryRepository priceHistoryRepository = Mock()

    @Subject
    PriceHistoryService service = new PriceHistoryService(
        priceHistoryRepository: priceHistoryRepository
    )

    private Item item(Map args = [:]) {
        new Item(id: args.id ?: 1L, name: args.name ?: 'Wizard Hat')
    }

    private String today() {
        new SimpleDateFormat('MMM dd').format(new Date())
    }

    def "record is a no-op when item id is null"() {
        when:
        service.record(new Item(id: null), new BigDecimal("5"), 1)

        then:
        0 * priceHistoryRepository.findLatestByItem(_)
        0 * priceHistoryRepository.save(_)
    }

    def "record is a no-op when item itself is null"() {
        when:
        service.record(null, new BigDecimal("5"), 1)

        then:
        0 * priceHistoryRepository.findLatestByItem(_)
        0 * priceHistoryRepository.save(_)
    }

    def "record is a no-op when price is null or non-positive"() {
        when:
        service.record(item(), price, 1)

        then:
        0 * priceHistoryRepository.findLatestByItem(_)
        0 * priceHistoryRepository.save(_)

        where:
        price << [null, BigDecimal.ZERO, new BigDecimal("-0.01")]
    }

    def "record inserts a new row when no same-day row exists"() {
        given:
        priceHistoryRepository.findLatestByItem(1L) >> Optional.empty()
        PriceHistory captured = null
        priceHistoryRepository.save(_) >> { PriceHistory p -> captured = p; p }

        when:
        service.record(item(), new BigDecimal("12.50"), 3)

        then:
        captured != null
        captured.item.id == 1L
        captured.price == new BigDecimal("12.50")
        captured.volume == 3
        captured.dayLabel == today()
    }

    def "record inserts a new row when yesterday's row is the latest"() {
        given:
        def stale = new PriceHistory(price: new BigDecimal("8"), volume: 2, dayLabel: 'Jan 01')
        priceHistoryRepository.findLatestByItem(1L) >> Optional.of(stale)
        PriceHistory captured = null
        priceHistoryRepository.save(_) >> { PriceHistory p -> captured = p; p }

        when:
        service.record(item(), new BigDecimal("10"), 1)

        then:
        // Different dayLabel → new row, old row untouched.
        captured != null
        captured.dayLabel == today()
        captured.price == new BigDecimal("10")
        // The stale row is not mutated.
        stale.price == new BigDecimal("8")
        stale.volume == 2
    }

    def "record updates the same-day row in place and adds to volume"() {
        given:
        def existing = new PriceHistory(
            id: 42L,
            price: new BigDecimal("9.00"),
            volume: 5,
            dayLabel: today()
        )
        priceHistoryRepository.findLatestByItem(1L) >> Optional.of(existing)
        priceHistoryRepository.save(_) >> { PriceHistory p -> p }

        when:
        service.record(item(), new BigDecimal("9.75"), 2)

        then:
        // Price is overwritten, volume accumulates.
        existing.price == new BigDecimal("9.75")
        existing.volume == 7
    }

    def "record does not bump volume when delta is null or zero"() {
        given:
        def existing = new PriceHistory(
            id: 42L,
            price: new BigDecimal("9.00"),
            volume: 5,
            dayLabel: today()
        )
        priceHistoryRepository.findLatestByItem(1L) >> Optional.of(existing)
        priceHistoryRepository.save(_) >> { PriceHistory p -> p }

        when:
        service.record(item(), new BigDecimal("9.25"), delta)

        then:
        existing.price == new BigDecimal("9.25")
        existing.volume == 5   // unchanged

        where:
        delta << [null, 0, -1]   // negative is treated as no-op too (guard clause)
    }
}
