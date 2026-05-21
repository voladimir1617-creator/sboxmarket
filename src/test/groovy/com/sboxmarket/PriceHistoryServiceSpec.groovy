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
 *   - dayLabel is YEAR-QUALIFIED ("MMM dd, yyyy") so same-day rows in
 *     different years can never collide — neither the chart tooltip nor
 *     the coalesce key.
 *   - A negative volume delta never seeds a negative volume on a fresh
 *     insert (it is clamped to 0, same as the update path).
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
        new SimpleDateFormat('MMM dd, yyyy').format(new Date())
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

    // ---- dayLabel is year-qualified (the P2 fix) ----------------------

    def "new-row dayLabel includes the four-digit year"() {
        given:
        priceHistoryRepository.findLatestByItem(1L) >> Optional.empty()
        PriceHistory captured = null
        priceHistoryRepository.save(_) >> { PriceHistory p -> captured = p; p }

        when:
        service.record(item(), new BigDecimal("4.00"), 1)

        then:
        // e.g. "Apr 01, 2026" — must carry the current calendar year so a
        // >365-day series can't collide same-day labels across years.
        captured.dayLabel ==~ /[A-Z][a-z]{2} \d{2}, \d{4}/
        captured.dayLabel.endsWith(', ' + new SimpleDateFormat('yyyy').format(new Date()))
    }

    def "a same-day label from a PRIOR year does not coalesce - a new row is appended"() {
        given:
        // The most-recent row is this exact month/day but a year ago: a
        // year-less "MMM dd" label would have matched today() and the new
        // price would overwrite the year-old data point in place. With the
        // year in the key the labels differ, so a fresh row is appended and
        // the stale row is left untouched.
        def lastYearLabel = priorYearSameDayLabel()
        def stale = new PriceHistory(
            id: 7L,
            price: new BigDecimal("3.00"),
            volume: 4,
            dayLabel: lastYearLabel
        )
        priceHistoryRepository.findLatestByItem(1L) >> Optional.of(stale)
        PriceHistory captured = null
        priceHistoryRepository.save(_) >> { PriceHistory p -> captured = p; p }

        when:
        service.record(item(), new BigDecimal("11.00"), 2)

        then:
        // Insert path taken: the captured row is brand new (no id), this
        // year's label, this call's price.
        captured != null
        captured.id == null
        captured.dayLabel == today()
        captured.dayLabel != lastYearLabel
        captured.price == new BigDecimal("11.00")
        // The year-old row is NOT mutated.
        stale.price == new BigDecimal("3.00")
        stale.volume == 4
    }

    // ---- negative volume delta never seeds a negative row ------------

    def "a negative volume delta on a fresh insert is clamped to zero"() {
        given:
        // Regression: the insert path used `volumeDelta ?: 0`, and in
        // Groovy a non-zero int is truthy — so a -1 delta wrote volume=-1
        // on a brand-new row even though the update path guarded `> 0`.
        priceHistoryRepository.findLatestByItem(1L) >> Optional.empty()
        PriceHistory captured = null
        priceHistoryRepository.save(_) >> { PriceHistory p -> captured = p; p }

        when:
        service.record(item(), new BigDecimal("6.00"), delta)

        then:
        captured != null
        captured.volume == 0   // clamped, never negative

        where:
        delta << [-1, -3, Integer.MIN_VALUE]
    }

    def "a positive volume delta on a fresh insert is preserved"() {
        given:
        priceHistoryRepository.findLatestByItem(1L) >> Optional.empty()
        PriceHistory captured = null
        priceHistoryRepository.save(_) >> { PriceHistory p -> captured = p; p }

        when:
        service.record(item(), new BigDecimal("6.00"), 4)

        then:
        captured.volume == 4
    }

    // ---- the volumeDelta=0 default param (two-arg call sites) --------

    def "record without a volume arg inserts a row with zero volume"() {
        given:
        // SboxApiService + SteamMarketPriceService call the two-arg form;
        // the default volumeDelta must land a 0 — never a null — on insert.
        priceHistoryRepository.findLatestByItem(1L) >> Optional.empty()
        PriceHistory captured = null
        priceHistoryRepository.save(_) >> { PriceHistory p -> captured = p; p }

        when:
        service.record(item(), new BigDecimal("7.00"))

        then:
        captured != null
        captured.volume == 0
        captured.price == new BigDecimal("7.00")
    }

    def "record without a volume arg leaves an existing same-day volume untouched"() {
        given:
        def existing = new PriceHistory(
            id: 13L, price: new BigDecimal("2.00"), volume: 6, dayLabel: today())
        priceHistoryRepository.findLatestByItem(1L) >> Optional.of(existing)
        priceHistoryRepository.save(_) >> { PriceHistory p -> p }

        when: 'a two-arg coalescing write — default delta 0, no bump'
        service.record(item(), new BigDecimal("2.50"))

        then:
        existing.price == new BigDecimal("2.50")
        existing.volume == 6   // default delta of 0 must not move volume
    }

    // ---- the coalescing update path persists via save ---------------

    def "the same-day update path writes the mutated row back through save"() {
        given:
        def existing = new PriceHistory(
            id: 8L, price: new BigDecimal("3.00"), volume: 1, dayLabel: today())
        priceHistoryRepository.findLatestByItem(1L) >> Optional.of(existing)

        when:
        service.record(item(), new BigDecimal("3.50"), 2)

        then: 'exactly one save, and it is the SAME (updated) row — not a new insert'
        1 * priceHistoryRepository.save({ PriceHistory p ->
            p.is(existing) && p.price == new BigDecimal("3.50") && p.volume == 3
        }) >> existing
    }

    def "a large positive volume delta is preserved intact on insert"() {
        given:
        priceHistoryRepository.findLatestByItem(1L) >> Optional.empty()
        PriceHistory captured = null
        priceHistoryRepository.save(_) >> { PriceHistory p -> captured = p; p }

        when:
        service.record(item(), new BigDecimal("5.00"), Integer.MAX_VALUE)

        then:
        captured.volume == Integer.MAX_VALUE
    }

    // ---- coalesce reads via findLatestByItem with the right id ------

    def "record keys the latest-row lookup off the item id"() {
        given:
        priceHistoryRepository.save(_) >> { PriceHistory p -> p }

        when:
        service.record(item(id: 99L), new BigDecimal("2.00"), 1)

        then:
        1 * priceHistoryRepository.findLatestByItem(99L) >> Optional.empty()
        0 * priceHistoryRepository.findLatestByItem({ it != 99L })
    }

    def "same-day coalesce tolerates a null volume on the existing row"() {
        given:
        // volume is non-null in the schema, but defend the accumulate
        // against a legacy/seed row that slipped through with null.
        def existing = new PriceHistory(
            id: 5L,
            price: new BigDecimal("1.00"),
            volume: null,
            dayLabel: today()
        )
        priceHistoryRepository.findLatestByItem(1L) >> Optional.of(existing)
        priceHistoryRepository.save(_) >> { PriceHistory p -> p }

        when:
        service.record(item(), new BigDecimal("1.50"), 3)

        then:
        noExceptionThrown()
        existing.price == new BigDecimal("1.50")
        existing.volume == 3   // (null ?: 0) + 3
    }

    private String priorYearSameDayLabel() {
        def cal = Calendar.getInstance()
        cal.add(Calendar.YEAR, -1)
        new SimpleDateFormat('MMM dd, yyyy').format(cal.getTime())
    }
}
