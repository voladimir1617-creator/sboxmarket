package com.sboxmarket

import com.sboxmarket.service.SteamMarketPriceService
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Pins the parseSteamPrice fix for no-decimal currencies with thousand-
 * separators (JPY/KRW/IDR/VND/CLP).
 *
 * Pre-fix the parser treated the RIGHTMOST separator as the decimal point
 * unconditionally — so "¥1,500" (1500 JPY) was rewritten as "1.500" and
 * round-tripped as BigDecimal("1.500") = 1.5, a 1000× underprice. The
 * existing spec only covered "¥150" (no separator at all), which masked
 * the bug. The class docstring claims JPY support and the hardcoded
 * currency=1 (USD) in the priceoverview URL means this is latent today,
 * but the moment anyone flips the currency param a thousand-separator
 * JPY/KRW value would silently fold three orders of magnitude off every
 * Steam-sourced price and then drag every later sync into the -99% trend
 * clamp.
 *
 * Rule pinned here: if the rightmost separator has EXACTLY 3 trailing
 * digits, it is a thousands separator (not a decimal). Every fractional
 * currency on Steam shows 2 decimal places; the only way to see exactly
 * 3 trailing digits past the rightmost separator is a thousands group.
 *
 * USD invariants pinned too — the fix must not regress the existing
 * USD-only production path. `"$1,234.56"` (.56 → 2 trailing) must still
 * resolve to 1234.56, NOT 1234 (which is what a too-aggressive thousands
 * rule would produce).
 */
class SteamMarketPriceNoDecimalCurrencySpec extends Specification {

    // ── No-decimal currencies with thousand-separators ──────────────

    @Unroll
    def "parseSteamPrice keeps no-decimal currency with thousand separator intact: #raw → #expected"() {
        expect:
        invoke(raw) == new BigDecimal(expected)

        where:
        raw          || expected
        // JPY (comma-thousands).
        '¥1,500'     || '1500'
        '¥15,000'    || '15000'
        '¥1,234,567' || '1234567'
        // KRW (comma-thousands).
        '₩1,500'     || '1500'
        // IDR / VND / CLP — '.' as thousands separator, no decimals.
        'Rp1.500'    || '1500'
        'Rp1.500.000' || '1500000'
        '₫1.500'     || '1500'
        // Bare digits-and-thousands without a currency symbol — same shape.
        '2,000'      || '2000'
        '1.500'      || '1500'
    }

    // ── Existing decimal-currency invariants — must not regress ─────

    @Unroll
    def "parseSteamPrice still treats 2-trailing-digit separator as decimal: #raw → #expected"() {
        // Anything with 2 trailing digits past the rightmost separator
        // is a real decimal (USD cents / EUR cents / etc.) — those rules
        // are the production path today and must not flip just because
        // the no-decimal heuristic landed.
        expect:
        invoke(raw) == new BigDecimal(expected)

        where:
        raw           || expected
        // USD with cents — the live production format.
        '$1.23'       || '1.23'
        '$1,234.56'   || '1234.56'
        '$1,234,567.89' || '1234567.89'
        // EUR / RUB / BRL comma-decimal.
        '1,23€'       || '1.23'
        '1.234,56€'   || '1234.56'
        '1,23 ₽'      || '1.23'
        'R$ 1,23'     || '1.23'
    }

    // ── No-separator integer currencies — unchanged behaviour ───────

    def "parseSteamPrice still handles JPY-style no-separator integer"() {
        // Existing test in SteamMarketPriceServiceSpec covers "¥150" too;
        // re-pinned here so this spec is a self-contained guard if the
        // sibling spec drifts.
        expect:
        invoke('¥150') == new BigDecimal('150')
    }

    // ── parseSteamPrice is private static — reflect to call it ─────

    private BigDecimal invoke(String raw) {
        def method = SteamMarketPriceService.getDeclaredMethod('parseSteamPrice', String)
        method.accessible = true
        method.invoke(null, (Object) raw) as BigDecimal
    }
}
