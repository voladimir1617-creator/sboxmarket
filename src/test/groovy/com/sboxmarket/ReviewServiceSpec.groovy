package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Review
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.ReviewRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.ReviewService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pure-logic coverage of the review rules.
 *
 * Every branch in ReviewService.leaveReview is exercised: rating bounds,
 * missing trade, unverified trade, non-buyer author, self-review, idempotent
 * update of an existing row. Repositories are mocked — no Spring context,
 * no DB. These specs are the source of truth for the review policy.
 */
class ReviewServiceSpec extends Specification {

    ReviewRepository    reviewRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    TradeRepository     tradeRepository = Mock()
    TextSanitizer       textSanitizer = Mock()
    BanGuard            banGuard = Mock()
    NotificationService notificationService = Mock()

    @Subject
    ReviewService service = new ReviewService(
        reviewRepository    : reviewRepository,
        steamUserRepository : steamUserRepository,
        tradeRepository     : tradeRepository,
        textSanitizer       : textSanitizer,
        banGuard            : banGuard,
        notificationService : notificationService
    )

    private Trade verifiedTrade(Map args = [:]) {
        new Trade(
            id:           args.id ?: 1L,
            buyerUserId:  args.buyer ?: 10L,
            sellerUserId: args.seller ?: 20L,
            state:        args.state ?: 'VERIFIED',
            itemName:     args.item ?: 'Wizard Hat'
        )
    }

    def "leaveReview creates a new row for a verified trade by the buyer"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >> null
        textSanitizer.clean('Great seller', 500) >> 'Great seller'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        reviewRepository.save(_) >> { Review r -> r.id = 100L; r }

        when:
        def row = service.leaveReview(10L, 1L, 5, 'Great seller')

        then:
        1 * banGuard.assertNotBanned(10L)
        1 * notificationService.push(20L, 'REVIEW_RECEIVED', _, _, _)
        row.rating == 5
        row.fromUserId == 10L
        row.toUserId == 20L
        row.comment == 'Great seller'
        row.fromDisplayName == 'Alice'
    }

    def "leaveReview updates an existing review idempotently and does NOT re-notify"() {
        given:
        def existing = new Review(id: 99L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 3, comment: 'meh')
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >> existing
        textSanitizer.clean('much better now', 500) >> 'much better now'
        // ReviewService looks up the author unconditionally, even on update
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        reviewRepository.save(_) >> { Review r -> r }

        when:
        def row = service.leaveReview(10L, 1L, 5, 'much better now')

        then:
        1 * banGuard.assertNotBanned(10L)
        0 * notificationService.push(*_)
        row.id == 99L
        row.rating == 5
        row.comment == 'much better now'
    }

    def "leaveReview rejects ratings outside 1..5"() {
        given:
        tradeRepository.findById(_) >> Optional.of(verifiedTrade())

        when:
        service.leaveReview(10L, 1L, rating, 'c')

        then:
        1 * banGuard.assertNotBanned(10L)
        thrown(BadRequestException)

        where:
        rating << [null, 0, 6, -1]
    }

    def "leaveReview 404s when the trade id is unknown"() {
        given:
        tradeRepository.findById(99L) >> Optional.empty()

        when:
        service.leaveReview(10L, 99L, 5, 'x')

        then:
        1 * banGuard.assertNotBanned(10L)
        thrown(NotFoundException)
    }

    def "leaveReview refuses trades still in progress"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade(state: 'PENDING_BUYER_CONFIRM'))

        when:
        service.leaveReview(10L, 1L, 5, 'x')

        then:
        1 * banGuard.assertNotBanned(10L)
        thrown(BadRequestException)
    }

    def "leaveReview forbids non-buyer authors"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade(buyer: 99L))

        when:
        service.leaveReview(10L, 1L, 5, 'x')

        then:
        1 * banGuard.assertNotBanned(10L)
        thrown(ForbiddenException)
    }

    def "leaveReview blocks self-review"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade(buyer: 10L, seller: 10L))

        when:
        service.leaveReview(10L, 1L, 5, 'x')

        then:
        1 * banGuard.assertNotBanned(10L)
        thrown(BadRequestException)
    }

    def "leaveReview refuses when the banGuard trips"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("banned") }

        when:
        service.leaveReview(10L, 1L, 5, 'x')

        then:
        thrown(ForbiddenException)
        0 * reviewRepository.save(_)
    }

    def "summaryForUser returns zero count when no reviews exist"() {
        given:
        reviewRepository.aggregateForUser(20L) >> []

        when:
        def result = service.summaryForUser(20L)

        then:
        result.count == 0
        result.average == null
    }

    def "summaryForUser rounds the average to one decimal"() {
        given:
        reviewRepository.aggregateForUser(20L) >> [[7L, 4.571 as Double]]

        when:
        def result = service.summaryForUser(20L)

        then:
        result.count == 7
        result.average == 4.6
    }
}
