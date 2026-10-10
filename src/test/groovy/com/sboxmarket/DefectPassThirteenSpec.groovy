package com.sboxmarket

import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import com.sboxmarket.repository.WatchlistItemRepository
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.WatchlistService
import spock.lang.Specification

/**
 * Defect pass 13: auctions listed through bot escrow, and unstarring an
 * item with a price alert on it.
 */
class DefectPassThirteenSpec extends Specification {

    static final long HOUR = 60L * 60L * 1000L

    def "an escrowed auction gets its full duration from the moment it goes live"() {
        given: "a 24h auction listed 30h ago whose deposit only just cleared"
        def now = System.currentTimeMillis()
        def listing = new Listing(id: 5L, status: 'PENDING_ESCROW', listingType: 'AUCTION',
            listedAt: now - 30 * HOUR, expiresAt: now - 6 * HOUR, price: BigDecimal.ONE)
        def repo = Mock(ListingRepository)
        repo.findById(5L) >> Optional.of(listing)
        repo.save(_) >> { Listing l -> l }
        def service = new SteamEscrowService(listingRepository: repo)

        when:
        service.activateListing(5L)

        then:
        listing.status == 'ACTIVE'
        listing.expiresAt >= now + 23 * HOUR
        listing.listedAt >= now
    }

    def "a buy-now listing's dates are left alone when its deposit clears"() {
        given:
        def listedAt = System.currentTimeMillis() - 30 * HOUR
        def listing = new Listing(id: 6L, status: 'PENDING_ESCROW', listingType: 'BUY_NOW',
            listedAt: listedAt, price: BigDecimal.ONE)
        def repo = Mock(ListingRepository)
        repo.findById(6L) >> Optional.of(listing)
        repo.save(_) >> { Listing l -> l }

        when:
        new SteamEscrowService(listingRepository: repo).activateListing(6L)

        then:
        listing.status == 'ACTIVE'
        listing.listedAt == listedAt
        listing.expiresAt == null
    }

    def "unstarring an item cancels its price alert"() {
        given:
        def items = Mock(WatchlistItemRepository)
        def alerts = Mock(WatchlistAlertRepository)
        def service = new WatchlistService(repository: items, alertRepository: alerts)

        when:
        def removed = service.remove(10L, 100L)

        then:
        1 * items.deleteByUserAndItem(10L, 100L) >> 1
        1 * alerts.cancelActiveForItems(10L, [100L])
        removed
    }

    def "unstarring an item that wasn't starred touches no alerts"() {
        given:
        def items = Mock(WatchlistItemRepository)
        def alerts = Mock(WatchlistAlertRepository)
        def service = new WatchlistService(repository: items, alertRepository: alerts)

        when:
        def removed = service.remove(10L, 100L)

        then:
        1 * items.deleteByUserAndItem(10L, 100L) >> 0
        0 * alerts._
        !removed
    }
}
