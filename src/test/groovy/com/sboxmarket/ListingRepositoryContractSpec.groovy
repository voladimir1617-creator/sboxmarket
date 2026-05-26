package com.sboxmarket

import com.sboxmarket.repository.ListingRepository
import org.springframework.data.jpa.repository.Query
import spock.lang.Specification

/**
 * Pin for the public-stats hidden-listing exclusion fix.
 *
 * `/api/listings/stats` (the anon-visible MarketStatsStrip) reads its
 * `activeListings` and `activeSellers` figures from `countActive()` and
 * `countActiveSellers()` on this repo. Sibling queries on the same
 * endpoint (`findMinActivePrice` / `findMaxActivePrice` /
 * `findActiveOrderByNewest`) ALL excluded hidden listings — but the two
 * count queries originally did NOT, so a seller could pad the public
 * "N listings from M sellers" liveness chip just by flipping Hide on
 * inventory they didn't actually want to surface. Public chip needs to
 * mirror what an anon visitor can actually browse.
 *
 * The fix amends the JPQL with `AND (l.hidden IS NULL OR l.hidden = false)`
 * — the same clause already on every other public-facing query on this
 * repo. This spec pins that clause so a future query-rewrite can't
 * silently drop it again.
 */
class ListingRepositoryContractSpec extends Specification {

    def "countActive excludes hidden listings from the public stats chip"() {
        when:
        def m = ListingRepository.getMethod('countActive')
        def q = m.getAnnotation(Query)

        then:
        q != null
        // The hidden-exclusion clause MUST be present — without it the
        // public /api/listings/stats `activeListings` chip is inflated
        // by every seller-side-hidden ACTIVE row.
        q.value().contains('l.hidden')
        q.value().contains("l.status = 'ACTIVE'")
    }

    def "countActiveSellers excludes hidden listings from the seller-diversity chip"() {
        when:
        def m = ListingRepository.getMethod('countActiveSellers')
        def q = m.getAnnotation(Query)

        then:
        q != null
        // Same reason as countActive — a seller whose only ACTIVE
        // listing is HIDDEN should not bump the `activeSellers`
        // counter that drives the public "M sellers" trust chip.
        q.value().contains('l.hidden')
        q.value().contains('DISTINCT l.sellerUserId')
    }
}
