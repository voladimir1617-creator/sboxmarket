package com.sboxmarket.service

import com.sboxmarket.repository.TradeRepository
import spock.lang.Specification

/**
 * Regression spec for the N+1 in the `/api/sellers/ship-times` endpoint.
 *
 * The bulk shipping-time endpoint (SellerStatsController#bulkShipTimes)
 * accepts up to 200 seller ids and used to loop
 * `parsed.each { uid -> tradeService.typicalShipMs(uid) }`, fanning out
 * one SQL round-trip per id (via TradeRepository#findRecentShipMsForSeller).
 * The marketplace grid hits this endpoint on every page load — at the
 * 200-id cap that was up to 200 queries per request.
 *
 * The fix introduces TradeService#typicalShipMsBulk + the bulk repo
 * query TradeRepository#findRecentShipMsForSellers. This spec asserts:
 *   1. The bulk call fires EXACTLY ONE repository call regardless of
 *      how many seller ids are passed (the actual N+1 fix).
 *   2. Per-seller medians agree with the single-seller path (no
 *      semantic drift).
 *   3. Sellers below the 3-sample noise floor are absent from the
 *      result, matching the single-seller path.
 *   4. Negative and null deltas are filtered out, matching the
 *      `it != null && it >= 0L` guard in typicalShipMs.
 *   5. Null / empty input collections short-circuit without hitting
 *      the repository at all.
 */
class TradeServiceTypicalShipBulkSpec extends Specification {

    TradeRepository tradeRepository = Mock()
    TradeService svc

    def setup() {
        svc = new TradeService(tradeRepository: tradeRepository)
    }

    def "bulk path fires a single repository call for many seller ids"() {
        given:
        def ids = (1L..50L).toList()
        // Three samples per seller so every id clears the noise floor.
        def rows = [] as List<Object[]>
        ids.each { uid ->
            rows << ([uid, 1_000L] as Object[])
            rows << ([uid, 2_000L] as Object[])
            rows << ([uid, 3_000L] as Object[])
        }

        when:
        def out = svc.typicalShipMsBulk(ids)

        then: 'exactly one bulk repository call, never the per-seller variant'
        1 * tradeRepository.findRecentShipMsForSellers(_ as Collection, _ as Long) >> rows
        0 * tradeRepository.findRecentShipMsForSeller(_, _)

        and: 'every id resolves to the same median as the single-seller path'
        out.size() == 50
        out.values().every { it == 2_000L }
    }

    def "median matches the single-seller path for an odd sample count"() {
        given:
        def rows = [
            [7L, 1_000L] as Object[],
            [7L, 5_000L] as Object[],
            [7L, 9_000L] as Object[],
        ]
        tradeRepository.findRecentShipMsForSellers(_, _) >> rows

        when:
        def out = svc.typicalShipMsBulk([7L])

        then:
        out[7L] == 5_000L
    }

    def "median matches the single-seller path for an even sample count"() {
        given:
        def rows = [
            [11L, 1_000L] as Object[],
            [11L, 3_000L] as Object[],
            [11L, 5_000L] as Object[],
            [11L, 7_000L] as Object[],
        ]
        tradeRepository.findRecentShipMsForSellers(_, _) >> rows

        when:
        def out = svc.typicalShipMsBulk([11L])

        then: 'mean of the two middle samples — same as typicalShipMs'
        out[11L] == 4_000L
    }

    def "sellers below the 3-sample noise floor are absent from the result"() {
        given:
        def rows = [
            // uid=1 — only two samples, must be omitted
            [1L, 1_000L] as Object[],
            [1L, 2_000L] as Object[],
            // uid=2 — three samples, must be present
            [2L, 1_000L] as Object[],
            [2L, 2_000L] as Object[],
            [2L, 3_000L] as Object[],
        ]
        tradeRepository.findRecentShipMsForSellers(_, _) >> rows

        when:
        def out = svc.typicalShipMsBulk([1L, 2L])

        then:
        !out.containsKey(1L)
        out[2L] == 2_000L
    }

    def "negative and null deltas are filtered out before counting samples"() {
        given:
        // Two valid samples + a negative + a null — single-seller path's
        // `it != null && it >= 0L` guard drops the bad ones. After the
        // drop, only two valid samples remain, so the seller falls below
        // the 3-sample noise floor and must be absent.
        def rows = [
            [3L,   1_000L] as Object[],
            [3L,   2_000L] as Object[],
            [3L,  -1_000L] as Object[],
            [3L,  null]    as Object[],
        ]
        tradeRepository.findRecentShipMsForSellers(_, _) >> rows

        when:
        def out = svc.typicalShipMsBulk([3L])

        then:
        !out.containsKey(3L)
    }

    def "null / empty input short-circuits without hitting the repository"() {
        when:
        def a = svc.typicalShipMsBulk(null)
        def b = svc.typicalShipMsBulk([])
        def c = svc.typicalShipMsBulk([null, null] as List<Long>)

        then: 'no SQL is issued — the controller can call this freely on the empty path'
        a == [:]
        b == [:]
        c == [:]
        0 * tradeRepository.findRecentShipMsForSellers(_, _)
    }
}
