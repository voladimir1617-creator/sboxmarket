package com.sboxmarket

import com.sboxmarket.controller.SellerStatsController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.ReviewRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.TradeService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the public seller-stats endpoints that drive the
 * homepage "Top Sellers" rail, per-card verified ✓ badge, and bulk
 * ship-time chips. The bulk parsers share the same contract as
 * WatchlistController.bulkCounts (malformed skip, 200-cap, omit
 * negatives, omit zero-data entries) — we pin it independently here
 * because these endpoints live in a separate controller with their
 * own `if (repo == null)` guards + data-source mixes.
 *
 * Also covers the newer `/me/verification-progress` endpoint that
 * tells signed-in sellers exactly what they need to hit for the ✓
 * badge — the threshold constants (10 sales, 4.0 rating) are load-
 * bearing for the seller-facing CTA copy.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class SellerStatsControllerSpec extends Specification {

    TradeRepository     tradeRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    ReviewRepository    reviewRepository   = Mock()
    ListingRepository   listingRepository  = Mock()
    TradeService        tradeService       = Mock()

    @Subject
    SellerStatsController controller = new SellerStatsController(
        tradeRepository    : tradeRepository,
        steamUserRepository: steamUserRepository,
        reviewRepository   : reviewRepository,
        listingRepository  : listingRepository,
        tradeService       : tradeService
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void anonSession() {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
    }
    private void authedSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    // ── bulkShipTimes ─────────────────────────────────────────────

    def "bulkShipTimes() returns {} on null ids param"() {
        when:
        def resp = controller.bulkShipTimes(null)

        then:
        0 * tradeService.typicalShipMs(_)
        resp.body == [:]
    }

    def "bulkShipTimes() returns {} on blank ids"() {
        when:
        def resp = controller.bulkShipTimes('   ')

        then:
        0 * tradeService.typicalShipMs(_)
        resp.body == [:]
    }

    def "bulkShipTimes() skips malformed tokens and negatives"() {
        given: 'only positive Long values survive the parser'
        1 * tradeService.typicalShipMs(7L) >> 3_600_000L

        when:
        def resp = controller.bulkShipTimes('abc,-5,7,,0')

        then:
        0 * tradeService.typicalShipMs(-5L)
        0 * tradeService.typicalShipMs(0L)
        resp.body == [7L: 3_600_000L]
    }

    def "bulkShipTimes() omits sellers below the noise floor (service returns null)"() {
        given:
        1 * tradeService.typicalShipMs(1L) >> null     // <3 samples
        1 * tradeService.typicalShipMs(2L) >> 7_200_000L

        when:
        def resp = controller.bulkShipTimes('1,2')

        then: 'only sellers with enough data appear in the response'
        resp.body == [2L: 7_200_000L]
    }

    def "bulkShipTimes() caps input at 200 ids"() {
        given:
        def lots = (1..250).collect { String.valueOf(it) }.join(',')
        int seenCalls = 0
        _ * tradeService.typicalShipMs(_) >> { seenCalls++; null }

        when:
        controller.bulkShipTimes(lots)

        then: 'at most 200 uids queried, never 250'
        seenCalls <= 200
    }

    def "bulkShipTimes() dedupes repeated ids before querying"() {
        given:
        1 * tradeService.typicalShipMs(5L) >> 1_000_000L

        when:
        def resp = controller.bulkShipTimes('5,5,5,5')

        then: 'the unique() collapses to one service call'
        0 * tradeService.typicalShipMs({ it != 5L })
        resp.body == [5L: 1_000_000L]
    }

    def "bulkShipTimes() carries the 2-minute public cache header"() {
        given:
        _ * tradeService.typicalShipMs(_) >> 1_000L

        when:
        def resp = controller.bulkShipTimes('1')

        then:
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('public')
        cc?.contains('max-age=120')
    }

    // ── verifiedBulk ─────────────────────────────────────────────

    def "verifiedBulk() returns {} on blank ids"() {
        when:
        def resp = controller.verifiedBulk('')

        then:
        0 * listingRepository.countSoldByMultipleSellers(_)
        resp.body == [:]
    }

    def "verifiedBulk() omits sellers with <10 sales"() {
        given: 'seller 1 has 9 sales (below threshold), seller 2 has 10 (qualifies)'
        1 * listingRepository.countSoldByMultipleSellers(_) >> [
            ([1L, 9L]  as Object[]),
            ([2L, 10L] as Object[])
        ]
        1 * reviewRepository.aggregateForUsers(_) >> [
            ([1L, 0L, null] as Object[]),
            ([2L, 0L, null] as Object[])
        ]

        when:
        def resp = controller.verifiedBulk('1,2')

        then: 'only seller 2 crosses the 10-sale threshold'
        resp.body == [2L: true]
    }

    def "verifiedBulk() omits sellers with avg rating < 4.0 even if sales qualify"() {
        given:
        1 * listingRepository.countSoldByMultipleSellers(_) >> [
            ([1L, 20L] as Object[]),
            ([2L, 20L] as Object[])
        ]
        // 1L is a 3.5-star seller (fails threshold); 2L is 4.2 (passes)
        1 * reviewRepository.aggregateForUsers(_) >> [
            ([1L, 4L, new BigDecimal('3.50')] as Object[]),
            ([2L, 5L, new BigDecimal('4.20')] as Object[])
        ]

        when:
        def resp = controller.verifiedBulk('1,2')

        then:
        resp.body == [2L: true]
    }

    def "verifiedBulk() verifies a zero-review but high-sales seller (no reviews OR >= 4.0)"() {
        given:
        1 * listingRepository.countSoldByMultipleSellers(_) >> [([7L, 50L] as Object[])]
        1 * reviewRepository.aggregateForUsers(_) >> []   // no review aggregate row

        when:
        def resp = controller.verifiedBulk('7')

        then: 'no reviews == treat as neutral, verify if sales qualify'
        resp.body == [7L: true]
    }

    def "verifiedBulk() skips malformed tokens"() {
        given:
        List<Long> capturedIds = null
        1 * listingRepository.countSoldByMultipleSellers(_) >> { args ->
            capturedIds = args[0]
            [([5L, 20L] as Object[])]
        }
        1 * reviewRepository.aggregateForUsers(_) >> []

        when:
        controller.verifiedBulk('abc,5,,xyz,-7')

        then:
        capturedIds == [5L]
    }

    def "verifiedBulk() carries the 2-minute public cache header"() {
        given:
        1 * listingRepository.countSoldByMultipleSellers(_) >> []
        1 * reviewRepository.aggregateForUsers(_) >> []

        when:
        def resp = controller.verifiedBulk('1')

        then:
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('public')
        cc?.contains('max-age=120')
    }

    // ── myVerificationProgress ──────────────────────────────────

    def "myVerificationProgress() requires sign-in"() {
        given: anonSession()
        when:  controller.myVerificationProgress(req)
        then:  thrown(UnauthorizedException)
        0 * listingRepository.countSoldBySeller(_)
    }

    def "myVerificationProgress() reports salesNeeded + thresholds for a new seller"() {
        given:
        authedSession(100L)
        1 * listingRepository.countSoldBySeller(100L) >> 3L
        1 * reviewRepository.aggregateForUser(100L) >> []   // no reviews yet

        when:
        def resp = controller.myVerificationProgress(req)

        then:
        resp.body.verified == false
        resp.body.soldCount == 3L
        resp.body.salesNeeded == 7L     // 10 - 3
        resp.body.ratingCount == 0L
        resp.body.ratingAverage == null
        resp.body.ratingOk == true      // no reviews treated as neutral
        resp.body.thresholdSales == 10L
        resp.body.thresholdRating == 4.0d
    }

    def "myVerificationProgress() returns verified:true when both thresholds met"() {
        given:
        authedSession(100L)
        1 * listingRepository.countSoldBySeller(100L) >> 25L
        1 * reviewRepository.aggregateForUser(100L) >> [([4L, new BigDecimal('4.50')] as Object[])]

        when:
        def resp = controller.myVerificationProgress(req)

        then:
        resp.body.verified == true
        resp.body.soldCount == 25L
        resp.body.salesNeeded == 0L
        resp.body.ratingAverage == new BigDecimal('4.50')
        resp.body.ratingCount == 4L
        resp.body.ratingOk == true
    }

    def "myVerificationProgress() blocks verification on a low rating"() {
        given:
        authedSession(100L)
        1 * listingRepository.countSoldBySeller(100L) >> 20L
        1 * reviewRepository.aggregateForUser(100L) >> [([5L, new BigDecimal('3.20')] as Object[])]

        when:
        def resp = controller.myVerificationProgress(req)

        then:
        resp.body.verified == false
        resp.body.salesNeeded == 0L
        resp.body.ratingOk == false
        resp.body.ratingAverage == new BigDecimal('3.20')
    }

    def "myVerificationProgress() handles review aggregate exception (treats as zero)"() {
        given:
        authedSession(100L)
        1 * listingRepository.countSoldBySeller(100L) >> 50L
        1 * reviewRepository.aggregateForUser(100L) >> { throw new RuntimeException('db pool empty') }

        when:
        def resp = controller.myVerificationProgress(req)

        then: 'defensive — logs debug, continues with ratingCount=0 (neutral)'
        resp.body.verified == true
        resp.body.ratingCount == 0L
        resp.body.ratingAverage == null
        resp.body.ratingOk == true
    }

    // ── /sellers/avatars bulk lookup ───────────────────────────

    def "avatarsBulk() returns {} on null ids (no round-trip)"() {
        when:
        def resp = controller.avatarsBulk(null)

        then:
        resp.body == [:]
        0 * steamUserRepository.findAllById(_)
    }

    def "avatarsBulk() returns {} on blank ids"() {
        when:
        def resp = controller.avatarsBulk('   ')

        then:
        resp.body == [:]
        0 * steamUserRepository.findAllById(_)
    }

    def "avatarsBulk() emits only sellers with a non-blank avatarUrl"() {
        given: 'user 100 has avatar, 200 is blank, 300 has a real URL'
        def u100 = new com.sboxmarket.model.SteamUser(id: 100L, avatarUrl: '')
        def u200 = new com.sboxmarket.model.SteamUser(id: 200L, avatarUrl: null)
        def u300 = new com.sboxmarket.model.SteamUser(
            id: 300L, avatarUrl: 'https://avatars.fastly.steamstatic.com/abc.jpg'
        )
        1 * steamUserRepository.findAllById([100L, 200L, 300L]) >> [u100, u200, u300]

        when:
        def resp = controller.avatarsBulk('100,200,300')

        then: 'blank + null URLs fall through to the frontend monogram fallback'
        resp.body.size() == 1
        resp.body[300L] == 'https://avatars.fastly.steamstatic.com/abc.jpg'
        resp.headers.getFirst('Cache-Control') == 'public, max-age=300'
    }

    def "avatarsBulk() drops malformed tokens (grid must keep rendering)"() {
        given:
        List<Long> captured = null
        1 * steamUserRepository.findAllById(_) >> { args ->
            captured = args[0] as List<Long>
            captured.collect { id ->
                new com.sboxmarket.model.SteamUser(id: id, avatarUrl: 'https://cdn/x.jpg')
            }
        }

        when: 'garbage mixed in with a real id — same policy as /verified'
        def resp = controller.avatarsBulk('42,abc,-5,0, ,not-a-number')

        then: 'only the legit positive id survives parsing'
        captured == [42L]
        resp.body[42L] == 'https://cdn/x.jpg'
    }

    def "avatarsBulk() caps at 200 input ids"() {
        given: 'client hands 250 ids — endpoint must truncate to 200'
        def ids = (1L..250L).collect { it.toString() }.join(',')
        List<Long> captured = null
        1 * steamUserRepository.findAllById(_) >> { args ->
            captured = args[0] as List<Long>
            []
        }

        when:
        controller.avatarsBulk(ids)

        then:
        captured.size() == 200
        captured.first() == 1L
        captured.last() == 200L
    }

    // ── /sellers/names bulk lookup ─────────────────────────────

    def "namesBulk() returns {} on null/blank ids (no round-trip)"() {
        when:
        def a = controller.namesBulk(null)
        def b = controller.namesBulk('   ')

        then:
        a.body == [:]
        b.body == [:]
        0 * steamUserRepository.findAllById(_)
    }

    def "namesBulk() emits only sellers with a non-blank displayName"() {
        given:
        def u100 = new com.sboxmarket.model.SteamUser(id: 100L, displayName: null)
        def u200 = new com.sboxmarket.model.SteamUser(id: 200L, displayName: '   ')
        def u300 = new com.sboxmarket.model.SteamUser(id: 300L, displayName: 'alice')
        1 * steamUserRepository.findAllById([100L, 200L, 300L]) >> [u100, u200, u300]

        when:
        def resp = controller.namesBulk('100,200,300')

        then: 'null + blank names drop; frontend falls back to "Steam user" placeholders'
        resp.body == [300L: 'alice']
        resp.headers.getFirst('Cache-Control') == 'public, max-age=300'
    }
}
