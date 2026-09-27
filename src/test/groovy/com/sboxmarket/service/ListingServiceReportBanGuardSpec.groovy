package com.sboxmarket.service

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.repository.ListingReportRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * Regression spec for the missing ban gate on
 * ListingService#reportListing.
 *
 * Pre-fix: the /api/listings/{id}/report endpoint hands the request
 * straight to ListingService.reportListing with zero ban check at the
 * controller (ListingController autowires no BanGuard, same gap that
 * batch 138/141-143 already swept across every other user-write
 * service). ReviewService.leaveReview / replyToReview / deleteReview /
 * voteHelpful and ListingService.bulkAdjustPrices all gate themselves
 * at the SERVICE layer with `banGuard.assertNotBanned` for exactly this
 * reason. reportListing was the lone user-initiated write on this
 * service that wasn't symmetric.
 *
 * Consequences of the gap:
 *   - A banned user can still mass-flag listings — the 20/hour rate
 *     limit caps the per-hour burst but a banned account can keep
 *     hitting the endpoint indefinitely, burning admin moderation
 *     cycles on bogus reports.
 *   - Every accepted report bumps `report_count` + `last_reported_at`
 *     on innocent listings, sorting them into the admin queue's
 *     "suspicious" bucket and degrading mod signal quality.
 *
 * The fix calls `banGuard.assertNotBanned(reporterUserId)` at the top
 * of `reportListing`, BEFORE any DB read / mutation. A banned caller
 * gets ForbiddenException (consistent with the other ban-gated
 * service methods); the repositories never get touched.
 */
class ListingServiceReportBanGuardSpec extends Specification {

    ListingRepository       listingRepository       = Mock()
    ListingReportRepository listingReportRepository = Mock()
    BanGuard                banGuard                = Mock()
    ListingService          svc

    def setup() {
        svc = new ListingService(
            listingRepository:       listingRepository,
            listingReportRepository: listingReportRepository,
            banGuard:                banGuard
        )
    }

    def "banned reporter cannot flag a listing — ForbiddenException before any repo call"() {
        given:
        banGuard.assertNotBanned(99L) >> {
            throw new ForbiddenException("Your account is banned: TOS breach")
        }

        when:
        svc.reportListing(7L, 99L, 'Suspicious pricing', 'spammy')

        then:
        ForbiddenException ex = thrown()
        ex.message.contains("banned")

        and: "no listing hydration, no report row written, no aggregate counter bump"
        // Critical assertion — proves the guard runs BEFORE the read,
        // not as a post-hoc filter. A guard that ran AFTER findById
        // would still pay the SELECT round-trip and (if the SELF_REPORT
        // path were ever rearranged) open a partial-write window where
        // the report row lands before the gate catches up.
        0 * listingRepository.findById(_)
        0 * listingReportRepository.save(_)
        0 * listingRepository.save(_)
    }

    def "guard runs before the listing lookup — banned wins over NotFound on a missing listing"() {
        given:
        banGuard.assertNotBanned(99L) >> {
            throw new ForbiddenException("Your account is banned")
        }

        when:
        // A banned user probing a non-existent listing id must NOT
        // be able to distinguish "banned" from "not found" by reading
        // the response code — asserting guard-first means the answer
        // is always ForbiddenException for a banned account regardless
        // of the listing id's validity.
        svc.reportListing(999_999_999L, 99L, 'Other', null)

        then:
        thrown(ForbiddenException)
        0 * listingRepository.findById(_)
    }

    def "service with no BanGuard wired (unit-spec scenario) still proceeds"() {
        given:
        // Existing specs construct ListingService field-by-field
        // without a BanGuard mock. The new gate uses `?.` so a null
        // banGuard must NOT NPE — control flow falls through to the
        // normal listing lookup, which we make return a self-owned
        // listing so we hit the SELF_REPORT short-circuit and exit
        // before the report repo gets touched. This documents the
        // null-safety contract that keeps the wider test suite green.
        def bareSvc = new ListingService(
            listingRepository:       listingRepository,
            listingReportRepository: listingReportRepository
        )
        def selfOwned = new com.sboxmarket.model.Listing(id: 7L, sellerUserId: 99L)
        listingRepository.findById(7L) >> Optional.of(selfOwned)

        when:
        bareSvc.reportListing(7L, 99L, 'Other', null)

        then:
        // SELF_REPORT fires, NOT an NPE on `banGuard.assertNotBanned`.
        def ex = thrown(com.sboxmarket.exception.BadRequestException)
        ex.code == "SELF_REPORT"
    }
}
