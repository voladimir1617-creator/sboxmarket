package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.ReviewService
import com.sboxmarket.service.SellerTrustService
import com.sboxmarket.service.TradeService
import spock.lang.Specification
import spock.lang.Subject

/**
 * The seller trade record a buyer sees next to "Buy now": completed trades,
 * completion rate, median time to send. Each case here is a way the record
 * could flatter a seller or blame one wrongly.
 */
class SellerTrustServiceSpec extends Specification {

    TradeRepository     tradeRepository     = Mock()
    SteamUserRepository steamUserRepository = Mock()
    TradeService        tradeService        = Mock()
    ReviewService       reviewService       = Mock()

    @Subject
    SellerTrustService service = new SellerTrustService(
        tradeRepository:     tradeRepository,
        steamUserRepository: steamUserRepository,
        tradeService:        tradeService,
        reviewService:       reviewService
    )

    private static SteamUser seller(Long id, Map extra = [:]) {
        new SteamUser([id: id, steamId64: '7656' + id, displayName: 'S' + id,
                       createdAt: 1_000L, lastSeenAt: 2_000L, banned: false] + extra)
    }

    def "completion rate is floored, so one failure in two hundred never reads 100%"() {
        given:
        steamUserRepository.findAllById(_) >> [seller(7L)]
        tradeRepository.tradeRecordForSellers(_) >> [[7L, 199L, 1L] as Object[]]

        when:
        def rec = service.recordFor([7L])[7L]

        then:
        rec.completedTrades == 199L
        rec.failedTrades == 1L
        rec.completionRate == 99
    }

    def "a seller with no decided trades has NO rate — never a clean 100%"() {
        given:
        steamUserRepository.findAllById(_) >> [seller(7L)]
        tradeRepository.tradeRecordForSellers(_) >> []

        when:
        def rec = service.recordFor([7L])[7L]

        then:
        rec.completedTrades == 0L
        rec.failedTrades == 0L
        rec.completionRate == null
    }

    def "median ship time, rating, member-since and presence ride along"() {
        given:
        steamUserRepository.findAllById(_) >> [seller(7L)]
        tradeRepository.tradeRecordForSellers(_) >> [[7L, 4L, 0L] as Object[]]
        tradeService.typicalShipMsBulk(_) >> [7L: 7_200_000L]
        reviewService.summariesForUsers(_) >> [7L: [average: 4.8, count: 5]]

        when:
        def rec = service.recordFor([7L])[7L]

        then:
        rec.completionRate == 100
        rec.medianShipMs == 7_200_000L
        rec.rating == 4.8d
        rec.reviewCount == 5
        rec.memberSince == 1_000L
        rec.lastSeenAt == 2_000L
    }

    def "a failed record lookup leaves the sellers OUT rather than reporting a spotless record"() {
        given:
        steamUserRepository.findAllById(_) >> [seller(7L)]
        tradeRepository.tradeRecordForSellers(_) >> { throw new RuntimeException('db down') }

        expect:
        service.recordFor([7L]).isEmpty()
    }

    def "banned sellers have no public record"() {
        given:
        steamUserRepository.findAllById(_) >> [seller(7L, [banned: true]), seller(8L)]
        tradeRepository.tradeRecordForSellers([8L]) >> [[8L, 1L, 0L] as Object[]]

        when:
        def out = service.recordFor([7L, 8L])

        then:
        out.keySet() == [8L] as Set
    }

    def "ids are cleaned and capped before they reach the database"() {
        when:
        service.recordFor([null, 0L, -3L] + (1L..80L).toList())

        then:
        1 * steamUserRepository.findAllById({ Collection ids -> ids.size() == SellerTrustService.MAX_IDS && !ids.contains(null) && ids.every { it > 0L } }) >> []
    }
}
