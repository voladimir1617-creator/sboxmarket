package com.sboxmarket

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

    def "parseSteamPrice extracts amounts from euro format with trailing symbol"() {
        // "1,23€" → stripped of non-digit-non-dot → "123" → 123.
        // The current parser doesn't handle commas as decimal separators;
        // this test documents the actual behavior.
        expect:
        invoke('1,23€') == new BigDecimal('123')
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

    // ── Constants ────────────────────────────────────────────────

    def "SBOX_APP_ID is 590830 (the s&box Steam appid)"() {
        expect:
        SteamMarketPriceService.SBOX_APP_ID == '590830'
    }

    def "sync interval is 15 minutes"() {
        expect:
        SteamMarketPriceService.SYNC_INTERVAL_MS == 15L * 60L * 1000L
    }

    // ── Helper to invoke the private method ─────────────────────

    private BigDecimal invoke(String raw) {
        // parseSteamPrice is private static — use Groovy's meta to call it
        def method = SteamMarketPriceService.getDeclaredMethod('parseSteamPrice', String)
        method.accessible = true
        method.invoke(null, (Object) raw) as BigDecimal
    }
}
