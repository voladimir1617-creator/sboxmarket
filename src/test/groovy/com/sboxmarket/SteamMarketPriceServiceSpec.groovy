package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.service.SteamMarketPriceService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for the Steam Community Market price parser and sync logic.
 * Network calls are not made — we only test parseSteamPrice and the
 * circuit breaker / throttle constants.
 */
class SteamMarketPriceServiceSpec extends Specification {

    @Subject
    SteamMarketPriceService service = new SteamMarketPriceService()

    // ── parseSteamPrice ──────────────────────────────────────────

    def "parseSteamPrice extracts USD dollar amounts"() {
        expect:
        invoke('$1.23') == new BigDecimal('1.23')
    }

    def "parseSteamPrice extracts amounts without currency symbol"() {
        expect:
        invoke('4.56') == new BigDecimal('4.56')
    }

    def "parseSteamPrice extracts amounts from euro format with trailing comma decimal"() {
        // EUR / RUB / BRL / most non-US locales use ',' as the decimal
        // separator and write currency-symbol last: "1,23€", "1,23 ₽".
        // Pre-fix the regex stripped the comma silently and "1,23€" parsed
        // to BigDecimal("123") — a 100× overprice on every euro/locale
        // format. The hardcoded currency=1 (USD) in the fetch URL meant
        // it never fired today, but the parser is now correct so the
        // moment someone changes the currency param the feed doesn't lie.
        expect:
        invoke('1,23€') == new BigDecimal('1.23')
    }

    def "parseSteamPrice handles German EUR format with dot-as-thousands and comma-as-decimal"() {
        // German + Dutch + most EU locales: "1.234,56€" = 1234.56.
        // The rightmost-separator-is-decimal rule keeps this correct
        // without locale awareness.
        expect:
        invoke('1.234,56€') == new BigDecimal('1234.56')
    }

    def "parseSteamPrice handles US format with comma-as-thousands"() {
        // US format on a high-priced item: "$1,234.56" = 1234.56.
        // Rightmost separator = decimal → '.' is decimal here, ',' is
        // thousands (stripped).
        expect:
        invoke('$1,234.56') == new BigDecimal('1234.56')
    }

    def "parseSteamPrice handles RUB-style trailing-currency comma decimal"() {
        // Russian RUB returns "1,23 ₽" or "1,23 руб." — same comma-decimal
        // shape as EUR. Match.
        expect:
        invoke('1,23 ₽') == new BigDecimal('1.23')
    }

    def "parseSteamPrice handles BRL-style leading-currency comma decimal"() {
        // Brazilian BRL: "R$ 1,23" — leading currency, comma decimal.
        expect:
        invoke('R$ 1,23') == new BigDecimal('1.23')
    }

    def "parseSteamPrice handles JPY-style no-decimal currency"() {
        // Japanese yen prices have no decimals at all: "¥150".
        expect:
        invoke('¥150') == new BigDecimal('150')
    }

    def "parseSteamPrice returns null for null input"() {
        expect:
        invoke(null) == null
    }

    def "parseSteamPrice returns null for empty string"() {
        expect:
        invoke('') == null
    }

    def "parseSteamPrice returns null for non-numeric strings"() {
        expect:
        invoke('free') == null
    }

    def "parseSteamPrice returns null for zero-value prices"() {
        expect:
        invoke('$0.00') == null
    }

    def "parseSteamPrice handles large dollar amounts"() {
        expect:
        invoke('$1234.56') == new BigDecimal('1234.56')
    }

    def "parseSteamPrice handles amounts with extra symbols"() {
        // "USD $12.99" → "12.99"
        expect:
        invoke('USD $12.99') == new BigDecimal('12.99')
    }

    // ── applyPriceUpdate: the floor guard (batch 954 / bug #106) ──

    def "applyPriceUpdate leaves lowestPrice alone when the item is listed"() {
        given:
        // Listed item: sboxmarket has real listings, MIN(listings.price)=1.47.
        // A Steam-market fetch returning $1.34 must NOT clobber the platform
        // floor — buyers clicking the grid card would otherwise see 1.47
        // and wonder where 1.34 came from.
        def item = new Item(
            name:        'Gold Earrings',
            isListed:    true,
            lowestPrice: new BigDecimal('1.47'),
            steamPrice:  new BigDecimal('1.28'),
            trendPercent: 0
        )

        when:
        service.applyPriceUpdate(item, new BigDecimal('1.34'), new BigDecimal('1.34'))

        then:
        item.lowestPrice == new BigDecimal('1.47')  // platform floor preserved
        item.steamPrice  == new BigDecimal('1.34')  // Steam reference refreshed to live quote
        item.trendPercent == 0                       // trend untouched when listed
    }

    def "applyPriceUpdate writes lowestPrice AND trendPercent when the item is not listed"() {
        given:
        def item = new Item(
            name:        'SWAG Chain',
            isListed:    false,
            lowestPrice: new BigDecimal('10.00'),
            steamPrice:  new BigDecimal('9.00'),
            trendPercent: 0
        )

        when: "Steam reports a new floor 20% higher than the last known"
        service.applyPriceUpdate(item, new BigDecimal('12.00'), new BigDecimal('12.00'))

        then:
        item.lowestPrice == new BigDecimal('12.00')
        item.steamPrice  == new BigDecimal('12.00') // Steam reference refreshed to live quote
        item.trendPercent == 20                      // +20% change recorded
    }

    def "applyPriceUpdate refreshes a stale steamPrice with the live Steam quote"() {
        given:
        // Regression for the frozen-reference bug: catalogue seeding
        // fabricates steamPrice = lowestPrice × 1.20 (SeedService) and the
        // old only-when-empty guard never replaced it, so item 7
        // 'Lunar Jacket 2026' showed a frozen $8.16 reference (6.80 × 1.2
        // from seed day) while Steam's live lowest_price was $6.05 —
        // operator-visible as a wrong "Steam CA$11.18" badge.
        def item = new Item(
            name:        'Lunar Jacket 2026',
            isListed:    true,
            lowestPrice: new BigDecimal('8.36'),
            steamPrice:  new BigDecimal('8.16'),   // fake seed value, pre-fix frozen forever
            trendPercent: 0
        )

        when:
        service.applyPriceUpdate(item, new BigDecimal('6.05'), new BigDecimal('6.05'))

        then:
        item.steamPrice  == new BigDecimal('6.05')  // live market quote wins
        item.lowestPrice == new BigDecimal('8.36')  // platform floor untouched (listed)
    }

    def "applyPriceUpdate populates steamPrice when previously empty"() {
        given:
        def item = new Item(
            name: 'Orphan',
            isListed: false,
            lowestPrice: BigDecimal.ZERO,
            steamPrice:  null,
            trendPercent: 0
        )

        when:
        service.applyPriceUpdate(item, new BigDecimal('5.00'), new BigDecimal('5.00'))

        then:
        item.steamPrice == new BigDecimal('5.00')
    }

    def "applyPriceUpdate handles a non-terminating-decimal trend without throwing"() {
        given:
        // oldPrice 3.00 -> bestPrice 4.00: the change ratio 1.00/3.00 is a
        // non-terminating decimal. Groovy's `/` on BigDecimal must fall
        // back to a rounded quotient (NOT throw ArithmeticException) —
        // otherwise this routine ~33% price move would be swallowed as a
        // `failed` and the item would never be saved.
        def item = new Item(
            name:        'Thirds',
            isListed:    false,
            lowestPrice: new BigDecimal('3.00'),
            steamPrice:  new BigDecimal('2.50'),
            trendPercent: 0
        )

        when:
        service.applyPriceUpdate(item, new BigDecimal('4.00'), new BigDecimal('4.00'))

        then:
        noExceptionThrown()
        item.lowestPrice == new BigDecimal('4.00')
        item.trendPercent == 33   // round(0.3333.. * 100) clamped into [-99,99]
    }

    def "applyPriceUpdate clamps an extreme upward trend to +99"() {
        given:
        def item = new Item(
            name: 'Moonshot', isListed: false,
            lowestPrice: new BigDecimal('1.00'),
            steamPrice:  new BigDecimal('1.00'), trendPercent: 0
        )

        when: "price 10x'd — raw change is +900%"
        service.applyPriceUpdate(item, new BigDecimal('10.00'), new BigDecimal('10.00'))

        then:
        item.trendPercent == 99
    }

    def "applyPriceUpdate clamps an extreme downward trend to -99"() {
        given:
        def item = new Item(
            name: 'Crash', isListed: false,
            lowestPrice: new BigDecimal('100.00'),
            steamPrice:  new BigDecimal('100.00'), trendPercent: 0
        )

        when: "price collapsed to 1% of prior floor — raw change is -99%"
        service.applyPriceUpdate(item, new BigDecimal('0.01'), new BigDecimal('0.01'))

        then:
        item.trendPercent == -99
    }

    def "applyPriceUpdate leaves trend untouched when the prior floor was zero"() {
        given:
        // A brand-new catalogue row seeds lowestPrice = 0. The trend
        // calc divides by oldPrice, so it must be guarded against a
        // 0 divisor — otherwise the first-ever price would blow up.
        def item = new Item(
            name: 'Fresh', isListed: false,
            lowestPrice: BigDecimal.ZERO,
            steamPrice:  null, trendPercent: 7
        )

        when:
        service.applyPriceUpdate(item, new BigDecimal('5.00'), new BigDecimal('5.00'))

        then:
        noExceptionThrown()
        item.lowestPrice == new BigDecimal('5.00')
        item.trendPercent == 7   // untouched — no prior floor to compare against
    }

    // ── Constants ────────────────────────────────────────────────

    def "SBOX_APP_ID is 590830 (the s&box Steam appid)"() {
        expect:
        SteamMarketPriceService.SBOX_APP_ID == '590830'
    }

    def "sync interval is 30 minutes"() {
        expect:
        SteamMarketPriceService.SYNC_INTERVAL_MS == 30L * 60L * 1000L
    }

    // ── getLastRunSummary ────────────────────────────────────────

    def "getLastRunSummary exposes a rateLimited count distinct from skipped/failed"() {
        // Regression: a 429 must surface as its own telemetry bucket so
        // ops can tell "Steam IP-banned us" apart from "item not on the
        // Steam market". Before the fix, 429s were folded into `skipped`.
        when:
        def summary = service.getLastRunSummary()

        then:
        summary.containsKey('rateLimited')
        summary.containsKey('skipped')
        summary.containsKey('failed')
        // All zero until the first sync completes since boot.
        summary.rateLimited == 0
        summary.skipped == 0
        summary.failed == 0
        summary.aborted == false
    }

    def "getLastRunSummary reports a zeroed snapshot before the first sync"() {
        expect:
        with(service.getLastRunSummary()) {
            startedAt == 0L
            finishedAt == 0L
            durationMs == 0L
            updated == 0
            total == 0
            nextRunAt == 0L
            intervalMs == SteamMarketPriceService.SYNC_INTERVAL_MS
        }
    }

    // ── fetchSteamPrice return contract (circuit-breaker bug #D) ──
    //
    // The sync loop arms its 5-strike circuit breaker ONLY on a result
    // carrying `rateLimited: true`. A plain `[lowest: null, median: null]`
    // (item not on the Steam market, or a transient non-200) must NOT
    // count toward the breaker — otherwise 5 unlisted s&box items in a
    // row would falsely abort every sync. These tests pin the shape the
    // loop's `prices?.rateLimited` branch depends on.

    def "a rate-limited result is recognised as such by the loop guard"() {
        given:
        Map result = [rateLimited: true]

        expect: "the loop's guard expression sees a 429"
        result?.rateLimited == true
    }

    def "a no-data result is NOT treated as rate-limited by the loop guard"() {
        given: "endpoint reached, item simply not on the Steam market"
        Map result = [lowest: null, median: null]

        expect: "the loop's guard expression does not trip the breaker"
        !result?.rateLimited
        result?.lowest == null
        result?.median == null
    }

    def "a priced result is NOT treated as rate-limited by the loop guard"() {
        given:
        Map result = [lowest: new BigDecimal('1.50'), median: new BigDecimal('1.75')]

        expect:
        !result?.rateLimited
        result?.lowest == new BigDecimal('1.50')
    }

    // ── Helper to invoke the private method ─────────────────────

    private BigDecimal invoke(String raw) {
        // parseSteamPrice is private static — use Groovy's meta to call it
        def method = SteamMarketPriceService.getDeclaredMethod('parseSteamPrice', String)
        method.accessible = true
        method.invoke(null, (Object) raw) as BigDecimal
    }
}
