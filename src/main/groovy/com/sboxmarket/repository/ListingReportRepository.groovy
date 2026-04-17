package com.sboxmarket.repository

import com.sboxmarket.model.ListingReport
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ListingReportRepository extends JpaRepository<ListingReport, Long> {

    /** Has this reporter already flagged this listing? Enforces the one-per-user
     *  rule at the service layer (the DB unique constraint is the backstop). */
    Optional<ListingReport> findByListingIdAndReporterUserId(Long listingId, Long reporterUserId)

    /** All reports on a listing, newest first. Powers the admin drill-down. */
    List<ListingReport> findByListingIdOrderByCreatedAtDesc(Long listingId)

    /** How many reports has this reporter filed in the last window? Rate-limiting
     *  hook — a single user shouldn't be able to mass-report hundreds of listings
     *  in an hour. The service caps at 20/hour. */
    long countByReporterUserIdAndCreatedAtGreaterThan(Long reporterUserId, Long since)
}
