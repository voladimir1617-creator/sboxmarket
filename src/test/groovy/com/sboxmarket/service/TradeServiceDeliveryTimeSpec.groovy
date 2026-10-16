package com.sboxmarket.service

import com.sboxmarket.repository.TradeRepository
import spock.lang.Specification

/**
 * The seller "Delivery time" stat: median time from sale to the buyer having
 * the item, from trades whose delivery was actually seen. Each case is a way
 * the stat could show a figure that was never measured.
 */
class TradeServiceDeliveryTimeSpec extends Specification {

    TradeRepository tradeRepository = Mock()
    TradeService svc

    def setup() {
        svc = new TradeService(tradeRepository: tradeRepository)
    }

    def "median over the seller's measured deliveries, in one query"() {
        when:
        def out = svc.typicalDeliveryMsBulk([7L, 8L])

        then:
        1 * tradeRepository.findRecentDeliveryMsForSellers(_ as Collection, _ as Long) >> [
            [7L, 1_000L] as Object[], [7L, 9_000L] as Object[], [7L, 5_000L] as Object[],
            [8L, 1_000L] as Object[], [8L, 2_000L] as Object[], [8L, 3_000L] as Object[], [8L, 4_000L] as Object[],
        ]
        out[7L] == 5_000L
        out[8L] == 2_500L
    }

    def "under three measured deliveries there is no figure, so the page shows a dash"() {
        given:
        tradeRepository.findRecentDeliveryMsForSellers(_, _) >> [
            [7L, 1_000L] as Object[], [7L, 2_000L] as Object[],
            [8L, -5L] as Object[], [8L, null] as Object[], [8L, 3_000L] as Object[], [8L, 4_000L] as Object[],
        ]

        when:
        def out = svc.typicalDeliveryMsBulk([7L, 8L])

        then: 'two samples, and two real ones once bad rows are dropped'
        !out.containsKey(7L)
        !out.containsKey(8L)
    }

    def "a failed lookup reads as not measured, never as zero"() {
        given:
        tradeRepository.findRecentDeliveryMsForSellers(_, _) >> { throw new RuntimeException('db down') }

        expect:
        svc.typicalDeliveryMsBulk([7L]) == [:]
    }

    def "no ids, no query"() {
        when:
        def a = svc.typicalDeliveryMsBulk(null)
        def b = svc.typicalDeliveryMsBulk([])

        then:
        0 * tradeRepository._
        a == [:]
        b == [:]
    }
}
