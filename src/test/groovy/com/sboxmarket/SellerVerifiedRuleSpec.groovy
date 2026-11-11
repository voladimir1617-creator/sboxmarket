package com.sboxmarket

import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.SellerVerifiedRule
import spock.lang.Specification
import spock.lang.Unroll

/** The one ✓ Verified seller rule: 10+ delivered trades AND (no reviews OR raw average >= 4.0). */
class SellerVerifiedRuleSpec extends Specification {

    @Unroll
    def "delivered=#delivered reviews=#reviews avg=#avg -> #expected"() {
        expect:
        SellerVerifiedRule.isVerified(delivered, reviews, avg) == expected

        where:
        delivered | reviews | avg   || expected
        9L        | 0L      | null  || false
        10L       | 0L      | null  || true
        10L       | 5L      | 4.0d  || true
        10L       | 5L      | 3.96d || false
        50L       | 5L      | null  || false
        50L       | 3L      | 4.8d  || true
        0L        | 0L      | null  || false
    }

    def "thresholds are unchanged: 10 trades, 4.0 rating"() {
        expect:
        SellerVerifiedRule.MIN_DELIVERED_TRADES == 10L
        SellerVerifiedRule.MIN_RATING == 4.0d
        SellerVerifiedRule.tradesNeeded(3L) == 7L
        SellerVerifiedRule.tradesNeeded(25L) == 0L
    }

    def "bulk lookup skips the query on empty input and when no repository is wired"() {
        given:
        TradeRepository repo = Mock()

        when:
        def empty = SellerVerifiedRule.deliveredTradesBySeller(repo, [])
        def noRepo = SellerVerifiedRule.deliveredTradesBySeller(null, [1L])
        def single = SellerVerifiedRule.deliveredTrades(null, 1L)

        then:
        0 * repo._
        empty == [:]
        noRepo == [:]
        single == 0L
    }

    def "bulk lookup maps rows by seller id and leaves sellers with none absent"() {
        given:
        TradeRepository repo = Mock()
        repo.countVerifiedBySellerIds([1L, 2L]) >> [([1L, 12L] as Object[])]

        expect:
        SellerVerifiedRule.deliveredTradesBySeller(repo, [1L, 2L]) == [1L: 12L]
    }
}
