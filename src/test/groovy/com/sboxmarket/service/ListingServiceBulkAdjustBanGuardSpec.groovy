package com.sboxmarket.service

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * Regression spec for the missing ban gate on
 * ListingService#bulkAdjustPrices.
 *
 * Pre-fix: ListingController autowires no BanGuard, and its
 * /api/listings/my-stall/bulk-adjust handler hands the request straight
 * to ListingService.bulkAdjustPrices with zero ban check. SellService's
 * sibling stall mutators (relist, cancelAllActive) gate themselves with
 * `banGuard.assertNotBanned` at the SERVICE layer for exactly this
 * reason — controller-only gates are routinely forgotten on new HTTP
 * endpoints. bulkAdjustPrices was the last unguarded seller-mutation
 * path on this service.
 *
 * Consequences of the gap:
 *   - A banned seller could mass-discount every active listing to the
 *     $0.01 clamp in one call (banGuard only ran during the original
 *     listing-creation flow; the mutation gate was missing).
 *   - That same call fans PRICE_DROPPED notifications + emails out to
 *     every cart-holder of every touched row — banned-account dead-end
 *     pings, the exact UX the cart fan-out already filters elsewhere.
 *   - The seller's catalogue floor price (Item.lowestPrice) gets
 *     overwritten too, so the marketplace grid surfaces the banned
 *     seller's discounted row as the visible "from $X" on items they
 *     once listed.
 *
 * The fix calls `banGuard.assertNotBanned(sellerUserId)` at the top of
 * `bulkAdjustPrices`, BEFORE any DB read / mutation / fan-out. A banned
 * caller gets ForbiddenException (consistent with the other ban-gated
 * service methods); the repository never gets touched.
 */
class ListingServiceBulkAdjustBanGuardSpec extends Specification {

    ListingRepository listingRepository = Mock()
    BanGuard          banGuard          = Mock()
    ListingService    svc

    def setup() {
        svc = new ListingService(
            listingRepository: listingRepository,
            banGuard:          banGuard
        )
    }

    def "banned seller cannot bulk-adjust — ForbiddenException before any repo call"() {
        given:
        // Mirror BanGuard's real throw semantic.
        banGuard.assertNotBanned(42L) >> {
            throw new ForbiddenException("Your account is banned: TOS breach")
        }

        when:
        svc.bulkAdjustPrices(42L, new BigDecimal('-10'))

        then:
        // Pre-fix: no exception — the call sailed past, hydrated the
        // seller's active listings, and mutated their prices.
        ForbiddenException ex = thrown()
        ex.message.contains("banned")

        and: "no rows hydrated, no rows saved, no fan-out fired"
        // Critical assertion — proves the guard runs BEFORE the read,
        // not as a post-hoc filter. A guard that runs AFTER
        // findActiveBySeller would still be a fix-shaped no-op for
        // ban detection but leaves the SELECT round-trip on the wire
        // and (if the percent guard were ever moved earlier) opens a
        // partial-write window.
        0 * listingRepository.findActiveBySeller(_)
        0 * listingRepository.saveAll(_)
    }

    def "guard runs before the percent validation — banned wins over INVALID_PERCENT"() {
        given:
        banGuard.assertNotBanned(42L) >> {
            throw new ForbiddenException("Your account is banned")
        }

        when:
        // Null percent would normally fail INVALID_PERCENT (the existing
        // ListingServiceBulkAdjustGuardSpec covers that path). For a
        // banned user the answer is the same regardless of the request
        // shape — ForbiddenException. Asserting this order means a banned
        // seller can't probe their ban status by sending a deliberately
        // malformed percent and reading the response code.
        svc.bulkAdjustPrices(42L, null)

        then:
        thrown(ForbiddenException)
        0 * listingRepository.findActiveBySeller(_)
    }

    def "non-banned seller passes through cleanly (banGuard is null-safe)"() {
        given:
        // The non-throwing branch of assertNotBanned just returns void.
        banGuard.assertNotBanned(42L) >> { /* no throw */ }
        // Empty active set keeps the spec focused on the gate.
        listingRepository.findActiveBySeller(42L) >> []

        when:
        def out = svc.bulkAdjustPrices(42L, new BigDecimal('-10'))

        then:
        noExceptionThrown()
        out.touched == 0
        out.skipped == 0
    }

    def "service with no BanGuard wired (unit-spec scenario) still validates other guards"() {
        given:
        // Some existing specs construct ListingService field-by-field
        // without a BanGuard mock. The new gate uses `?.` so a null
        // banGuard must NOT NPE — the existing percent guard still has
        // to fire. This is what keeps ListingServiceBulkAdjustGuardSpec
        // green without modification.
        def bareSvc = new ListingService(listingRepository: listingRepository)

        when:
        bareSvc.bulkAdjustPrices(42L, null)

        then:
        // Percent guard fires, NOT an NPE on `banGuard.assertNotBanned`.
        def ex = thrown(com.sboxmarket.exception.BadRequestException)
        ex.code == "INVALID_PERCENT"
    }
}
