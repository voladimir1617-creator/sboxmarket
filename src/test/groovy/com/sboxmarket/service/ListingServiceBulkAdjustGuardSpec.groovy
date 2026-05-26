package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.repository.ListingRepository
import spock.lang.Specification

/**
 * Regression spec for the unvalidated `percent` arg on
 * ListingService#bulkAdjustPrices.
 *
 * The HTTP controller (ListingController#bulkAdjustStall) already
 * null-checks and ±50%-caps the percent, but the service method itself
 * is `public` and reachable from scheduled jobs / admin scripts / future
 * internal callers without going through the controller. Pre-fix the
 * service had zero defence:
 *   - `percent.divide(...)` NPE'd on null percent (uncaught — bubbled
 *     as 500 instead of a clean 400).
 *   - An unbounded percent (e.g. -100 from an internal caller) would
 *     drive every active non-auction listing to the $0.01 clamp in one
 *     transaction, silently nuking the seller's prices AND firing a
 *     PRICE_DROPPED bell to every cart-holder.
 *
 * The fix adds two guards mirroring the controller's bounds:
 *   1. null percent → BadRequestException("INVALID_PERCENT", ...)
 *   2. |percent| > 50 → BadRequestException("PERCENT_TOO_LARGE", ...)
 *
 * Both fail BEFORE any repository call so a guard violation is a no-op
 * — no rows hydrated, no rows saved, no notification fan-out.
 */
class ListingServiceBulkAdjustGuardSpec extends Specification {

    ListingRepository listingRepository = Mock()
    ListingService svc

    def setup() {
        svc = new ListingService(listingRepository: listingRepository)
    }

    def "null percent throws BadRequestException(INVALID_PERCENT) before any repo call"() {
        when:
        svc.bulkAdjustPrices(42L, null)

        then:
        def ex = thrown(BadRequestException)
        ex.code == "INVALID_PERCENT"
        // Pre-fix: NullPointerException from `percent.divide(...)` on line 708,
        // which surfaced as a 500 to the caller. The guard short-circuits
        // before findActiveBySeller fires, so the repo is never touched.
        0 * listingRepository.findActiveBySeller(_)
        0 * listingRepository.saveAll(_)
    }

    def "percent over +50% throws BadRequestException(PERCENT_TOO_LARGE)"() {
        when:
        svc.bulkAdjustPrices(42L, new BigDecimal('50.01'))

        then:
        def ex = thrown(BadRequestException)
        ex.code == "PERCENT_TOO_LARGE"
        0 * listingRepository.findActiveBySeller(_)
    }

    def "percent below -50% throws BadRequestException(PERCENT_TOO_LARGE)"() {
        when:
        // -100 was the original danger case: factor = 0, every active
        // non-auction listing clamped to $0.01 in a single tx, then a
        // PRICE_DROPPED bell fired to every cart-holder of every row.
        svc.bulkAdjustPrices(42L, new BigDecimal('-100'))

        then:
        def ex = thrown(BadRequestException)
        ex.code == "PERCENT_TOO_LARGE"
        0 * listingRepository.findActiveBySeller(_)
    }

    def "exactly ±50% is permitted (boundary is inclusive)"() {
        given:
        // Empty active set keeps the spec focused on the guard — we just
        // need to prove the call doesn't throw at the boundary.
        listingRepository.findActiveBySeller(42L) >> []

        when:
        def out = svc.bulkAdjustPrices(42L, pct)

        then:
        noExceptionThrown()
        out.touched == 0
        out.skipped == 0
        out.percent == pct

        where:
        pct << [new BigDecimal('50'), new BigDecimal('-50'), BigDecimal.ZERO]
    }
}
