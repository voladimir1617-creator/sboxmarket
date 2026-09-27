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
import com.sboxmarket.service.AuditService
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
        1 * notificationService.push(20L, 'REVIEW_RECEIVED', _, _, _, _)
        row.rating == 5
        row.fromUserId == 10L
        row.toUserId == 20L
        row.comment == 'Great seller'
        row.fromDisplayName == 'Alice'
    }

    def "leaveReview updates an existing review idempotently — comment-only edit skips notification"() {
        given:
        def existing = new Review(id: 99L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 5, comment: 'meh')
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >> existing
        textSanitizer.clean('fixed typo', 500) >> 'fixed typo'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        reviewRepository.save(_) >> { Review r -> r }

        when:
        // Same rating (5), comment changed — seller should NOT be pinged.
        def row = service.leaveReview(10L, 1L, 5, 'fixed typo')

        then:
        1 * banGuard.assertNotBanned(10L)
        0 * notificationService.push(*_)
        row.rating == 5
        row.comment == 'fixed typo'
    }

    def "leaveReview notifies the seller when the rating changes on an edit (batch 310 bug fix)"() {
        // A buyer updating from 5★ to 1★ after a bad experience
        // used to land silently. REVIEW_UPDATED now fires for any
        // rating delta so sellers see the drop in the bell.
        given:
        def existing = new Review(id: 99L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 5, comment: 'great')
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >> existing
        textSanitizer.clean('bad seller after all', 500) >> 'bad seller after all'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        reviewRepository.save(_) >> { Review r -> r }

        when:
        service.leaveReview(10L, 1L, 1, 'bad seller after all')

        then:
        // REVIEW_UPDATED fires, REVIEW_RECEIVED does NOT (that's for new reviews).
        1 * notificationService.push(20L, 'REVIEW_UPDATED', { String title -> title.contains('5★') && title.contains('1★') && title.contains('↓') }, _, 99L, '/profile?tab=reviews')
        0 * notificationService.push(_, 'REVIEW_RECEIVED', _, _, _, _)
    }

    def "leaveReview notifies on an upward rating change too"() {
        given:
        def existing = new Review(id: 99L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 2, comment: 'meh')
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >> existing
        textSanitizer.clean('fixed it', 500) >> 'fixed it'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        reviewRepository.save(_) >> { Review r -> r }

        when:
        service.leaveReview(10L, 1L, 5, 'fixed it')

        then:
        // Upward arrow this time.
        1 * notificationService.push(20L, 'REVIEW_UPDATED', { String title -> title.contains('2★') && title.contains('5★') && title.contains('↑') }, _, _, _)
    }

    def "leaveReview stamps editedAt when rating changes on re-submit (batch 745)"() {
        given:
        def existing = new Review(id: 99L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 3, comment: 'ok')
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >> existing
        textSanitizer.clean('changed my mind', 500) >> 'changed my mind'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        Review saved = null
        reviewRepository.save(_) >> { Review r -> saved = r; r }

        when:
        service.leaveReview(10L, 1L, 5, 'changed my mind')

        then:
        saved.editedAt != null
        saved.editedAt > 0L
        saved.rating == 5
    }

    def "leaveReview leaves editedAt null when nothing actually changes on re-submit (batch 745)"() {
        given:
        def existing = new Review(id: 99L, fromUserId: 10L, toUserId: 20L, tradeId: 1L,
            rating: 5, comment: 'same', editedAt: null)
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >> existing
        textSanitizer.clean('same', 500) >> 'same'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        Review saved = null
        reviewRepository.save(_) >> { Review r -> saved = r; r }

        when:
        service.leaveReview(10L, 1L, 5, 'same')

        then:
        // No-op re-submit must not lie about being edited.
        saved.editedAt == null
    }

    def "leaveReview does NOT stamp editedAt on first create (batch 745)"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >> null
        textSanitizer.clean('first review', 500) >> 'first review'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        Review saved = null
        reviewRepository.save(_) >> { Review r -> saved = r; r.id = 99L; r }

        when:
        service.leaveReview(10L, 1L, 4, 'first review')

        then:
        // Fresh creates should never look pre-edited.
        saved.editedAt == null
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

    def "leaveReview rejects a null trade id with a clean 4xx instead of bubbling a Spring 500"() {
        // The controller passes through whatever tradeId is in the JSON body
        // (or null when the field is missing). Spring Data's findById(null)
        // throws InvalidDataAccessApiUsageException, which surfaces as an
        // HTTP 500 — the wrong shape for a malformed client request. Guard
        // up front so a missing tradeId looks the same as any other bad
        // input (BadRequestException → 400 INVALID_TRADE_ID).
        when:
        service.leaveReview(10L, null, 5, 'x')

        then:
        1 * banGuard.assertNotBanned(10L)
        def ex = thrown(BadRequestException)
        ex.code == 'INVALID_TRADE_ID'
        // tradeRepository is never touched — the guard short-circuits before
        // any DB call so a null id can't ride into the JPA layer at all.
        0 * tradeRepository.findById(_)
        0 * reviewRepository.save(_)
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

    def "leaveReview rejects a trade that has no seller counterparty (NO_SELLER)"() {
        given:
        // VERIFIED trade authored by the buyer but with a null seller — a
        // half-formed / legacy row. Must be rejected, not NPE on the
        // self-review comparison below it.
        def noSeller = new Trade(id: 1L, buyerUserId: 10L, sellerUserId: null,
            state: 'VERIFIED', itemName: 'Orphan Knife')
        tradeRepository.findById(1L) >> Optional.of(noSeller)

        when:
        service.leaveReview(10L, 1L, 5, 'x')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NO_SELLER'
        0 * reviewRepository.save(_)
    }

    def "leaveReview still returns the saved review when the REVIEW_RECEIVED push fails"() {
        // Regression: NotificationService.push is @Transactional and joins
        // leaveReview's transaction. An un-guarded push failure on the
        // create path would mark the tx rollback-only and silently destroy
        // the review row that was just saved. The push must be swallowed —
        // same guard the REVIEW_UPDATED edit path already has.
        given:
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >> null
        textSanitizer.clean('great', 500) >> 'great'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        // NB: the save stub lives on the `then:`-block `1 *` interaction
        // below, not here. Declaring `save(_) >> {...}` in `given:` AND a
        // bare `1 * save(_)` in `then:` makes the later then-interaction
        // win for the response — it returns null, so `row` is null and
        // the unguarded `row.id` in leaveReview's audit-log call NPEs.
        notificationService.push(20L, 'REVIEW_RECEIVED', _, _, _, _) >> {
            throw new RuntimeException('bell DB blip')
        }

        when:
        def row = service.leaveReview(10L, 1L, 5, 'great')

        then:
        // The push failure must NOT escape — the review write stands.
        noExceptionThrown()
        row != null
        row.id == 100L
        row.rating == 5
        // The row was genuinely persisted exactly once.
        1 * reviewRepository.save(_) >> { Review r -> r.id = 100L; r }
    }

    def "leaveReview collapses a concurrent double-write into the winner's row (V67 race)"() {
        // V5__reviews originally relied on a service-layer "uniqueness is
        // enforced here" check — a check-then-write that two concurrent
        // POST /api/reviews calls could both pass, double-inserting a
        // review for the same (buyer, trade) pair. V67 adds a DB-level
        // UNIQUE (from_user_id, trade_id); the loser's INSERT now fails
        // with DataIntegrityViolationException, which the service must
        // turn into "return the winner's row" instead of a 500.
        given:
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        textSanitizer.clean('great', 500) >> 'great'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        // First lookup (before save) returns null — we're the "loser" thread
        // that thought it was creating a new row. Second lookup (after
        // DataIntegrityViolationException) returns the winner's already-
        // persisted row.
        def winnerRow = new Review(id: 777L, fromUserId: 10L, toUserId: 20L,
            tradeId: 1L, rating: 5, comment: 'great')
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >>> [null, winnerRow]
        reviewRepository.save(_) >> {
            throw new org.springframework.dao.DataIntegrityViolationException(
                "duplicate key violates uq_reviews_from_user_trade")
        }

        when:
        def row = service.leaveReview(10L, 1L, 5, 'great')

        then:
        // The loser thread surfaces the winner's persisted row instead of
        // bubbling a 500. No REVIEW_RECEIVED push (the winner thread
        // already sent it on its own write).
        noExceptionThrown()
        row.is(winnerRow)
        0 * notificationService.push(_, 'REVIEW_RECEIVED', _, _, _, _)
    }

    def "leaveReview re-raises a DataIntegrityViolation that isn't the dup-race we expected"() {
        // Belt-and-braces: if save() blows up on a constraint OTHER than
        // uq_reviews_from_user_trade (e.g. a FK violation, a future
        // column-level CHECK), the second findByFromUserIdAndTradeId
        // returns null and we must let the original exception escape
        // rather than silently swallow a real bug.
        given:
        tradeRepository.findById(1L) >> Optional.of(verifiedTrade())
        textSanitizer.clean('great', 500) >> 'great'
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, displayName: 'Alice'))
        reviewRepository.findByFromUserIdAndTradeId(10L, 1L) >>> [null, null]
        reviewRepository.save(_) >> {
            throw new org.springframework.dao.DataIntegrityViolationException(
                "some other constraint")
        }

        when:
        service.leaveReview(10L, 1L, 5, 'great')

        then:
        thrown(org.springframework.dao.DataIntegrityViolationException)
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

    def "summaryForUser maps the per-star histogram into the 5★-first bucket order"() {
        given:
        // histogramForUser returns [[rating, count], ...]. Index 0 = 5★,
        // index 4 = 1★ — the bucket order the breakdown bar chart expects.
        reviewRepository.aggregateForUser(20L) >> [[10L, 4.0 as Double]]
        reviewRepository.histogramForUser(20L) >> [
            [5, 6L] as Object[],
            [4, 2L] as Object[],
            [3, 1L] as Object[],
            [1, 1L] as Object[]
        ]

        when:
        def result = service.summaryForUser(20L)

        then:
        result.count == 10
        // 5★→idx0, 4★→idx1, 3★→idx2, 2★→idx3 (absent → 0), 1★→idx4.
        result.histogram == [6L, 2L, 1L, 0L, 1L]
    }

    def "summaryForUser skips the histogram query entirely when there are no reviews"() {
        given:
        reviewRepository.aggregateForUser(20L) >> [[0L, null]]

        when:
        def result = service.summaryForUser(20L)

        then:
        result.count == 0
        result.average == null
        result.histogram == [0, 0, 0, 0, 0]
        // No point hitting the histogram query for a seller with 0 reviews.
        0 * reviewRepository.histogramForUser(_)
    }

    def "summariesForUsers maps a single GROUP BY result into [uid -> {count,average}]"() {
        given:
        // aggregateForUsers returns [uid, count, avg] rows. Sellers with
        // zero reviews are absent from the result (the WHERE r.toUserId IN
        // never matches them) and must NOT appear in the output map either.
        def rawRows = [
            [20L, 7L, 4.571 as Double],
            [21L, 3L, 5.0   as Double]
        ]
        reviewRepository.aggregateForUsers(_) >> { args -> rawRows }

        when:
        def out = service.summariesForUsers([20L, 21L, 99L])

        then:
        out.size() == 2
        out[20L].count == 7
        out[20L].average == 4.6
        out[21L].count == 3
        out[21L].average == 5.0
        !out.containsKey(99L)
    }

    def "summariesForUsers short-circuits on null + empty input without hitting the repo"() {
        when:
        def emptyResult = service.summariesForUsers([])
        def nullResult  = service.summariesForUsers(null)

        then:
        emptyResult.isEmpty()
        nullResult.isEmpty()
        0 * reviewRepository.aggregateForUsers(_)
    }

    def "summariesForUsers degrades silently when the repo throws"() {
        given:
        reviewRepository.aggregateForUsers(_) >> { throw new RuntimeException("db down") }

        when:
        def out = service.summariesForUsers([20L])

        then:
        out.isEmpty()
        notThrown(Exception)
    }

    def "deleteReview removes the row when the caller is the author"() {
        given:
        def review = new Review(id: 42L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 2, comment: 'meh', itemName: 'Wizard Hat')
        reviewRepository.findById(42L) >> Optional.of(review)

        when:
        service.deleteReview(10L, 42L)

        then:
        1 * banGuard.assertNotBanned(10L)
        1 * reviewRepository.delete(review)
        // Seller gets a REVIEW_DELETED ping so the removal doesn't
        // land silently (batch 311). Body mentions the rating + item.
        1 * notificationService.push(20L, 'REVIEW_DELETED', _,
            { String body -> body.contains('2★') && body.contains('Wizard Hat') },
            42L, '/profile?tab=reviews')
    }

    def "deleteReview mentions the lost reply when the seller had replied (batch 311)"() {
        given:
        def review = new Review(
            id: 42L, fromUserId: 10L, toUserId: 20L, tradeId: 1L,
            rating: 5, comment: 'great', itemName: 'Wizard Hat',
            sellerReply: 'thanks!', sellerReplyAt: 123L
        )
        reviewRepository.findById(42L) >> Optional.of(review)

        when:
        service.deleteReview(10L, 42L)

        then:
        1 * notificationService.push(20L, 'REVIEW_DELETED', _,
            { String body -> body.contains('Your reply is gone with it') },
            42L, '/profile?tab=reviews')
    }

    def "deleteReview swallows a push failure so the row still gets deleted"() {
        given:
        def review = new Review(id: 42L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 3)
        reviewRepository.findById(42L) >> Optional.of(review)
        notificationService.push(20L, 'REVIEW_DELETED', _, _, _, _) >> { throw new RuntimeException('push down') }

        when:
        service.deleteReview(10L, 42L)

        then:
        1 * reviewRepository.delete(review)
        noExceptionThrown()
    }

    def "deleteReview 403s when the caller is not the author (e.g. the seller)"() {
        given:
        def review = new Review(id: 42L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 1, comment: 'bad')
        reviewRepository.findById(42L) >> Optional.of(review)

        when:
        // The seller (id 20) tries to delete the review left about them.
        service.deleteReview(20L, 42L)

        then:
        thrown(ForbiddenException)
        0 * reviewRepository.delete(_)
    }

    def "deleteReview 404s for a missing review id"() {
        given:
        reviewRepository.findById(42L) >> Optional.empty()

        when:
        service.deleteReview(10L, 42L)

        then:
        thrown(NotFoundException)
        0 * reviewRepository.delete(_)
    }

    // ── adminDeleteReview (batch 479) ───────────────────────────────

    def "adminDeleteReview removes any review regardless of authorship and notifies the buyer with the reason"() {
        given:
        def review = new Review(id: 42L, fromUserId: 10L, toUserId: 20L, tradeId: 1L,
            rating: 1, comment: 'contains a phone number', itemName: 'Wizard Hat')
        reviewRepository.findById(42L) >> Optional.of(review)

        when:
        // Staff user 500 — NOT the author, NOT the seller.
        service.adminDeleteReview(500L, 42L, 'PII in comment')

        then:
        1 * reviewRepository.delete(review)
        // Buyer (the author) is told why their review vanished.
        1 * notificationService.push(10L, 'REVIEW_DELETED', _,
            { String body -> body.contains('PII in comment') }, 42L, '/profile?tab=reviews')
        // Staff override is NOT subject to the ban guard — admins act on
        // banned users' reviews routinely.
        0 * banGuard.assertNotBanned(_)
    }

    def "adminDeleteReview falls back to a generic reason when none is supplied"() {
        given:
        def review = new Review(id: 42L, fromUserId: 10L, toUserId: 20L, tradeId: 1L,
            rating: 2, itemName: 'Wizard Hat')
        reviewRepository.findById(42L) >> Optional.of(review)

        when:
        service.adminDeleteReview(500L, 42L, null)

        then:
        1 * reviewRepository.delete(review)
        1 * notificationService.push(10L, 'REVIEW_DELETED', _,
            { String body -> body.contains('policy violation') }, 42L, '/profile?tab=reviews')
    }

    def "adminDeleteReview 404s on a missing review id"() {
        given:
        reviewRepository.findById(42L) >> Optional.empty()

        when:
        service.adminDeleteReview(500L, 42L, 'whatever')

        then:
        thrown(NotFoundException)
        0 * reviewRepository.delete(_)
    }

    def "adminDeleteReview still deletes the row when the buyer-notification push fails"() {
        given:
        def review = new Review(id: 42L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 3)
        reviewRepository.findById(42L) >> Optional.of(review)
        notificationService.push(10L, 'REVIEW_DELETED', _, _, _, _) >> { throw new RuntimeException('push down') }

        when:
        service.adminDeleteReview(500L, 42L, 'spam')

        then:
        1 * reviewRepository.delete(review)
        noExceptionThrown()
    }

    def "adminDeleteReview's REVIEW_DELETED_STAFF audit row uses the SELLER as subject (CSR-search alignment)"() {
        // Wave V68 regression: the staff-delete audit row used to set
        // subjectUserId = buyerId (the review author). That broke CSR's
        // `bySubject(sellerId)` rollup — a staff override of a review on a
        // SELLER's stall vanished from the seller's audit history, even
        // though the seller's average + count were just rewritten by staff
        // fiat. Aligns with REVIEW_CREATED + REVIEW_DELETED (both use the
        // seller as subject) so all three lifecycle events show up under
        // the same seller-id query. The buyer id is still captured in the
        // summary string for authorship traceability.
        given:
        def auditService = Mock(AuditService)
        service.auditService = auditService
        def review = new Review(id: 42L, fromUserId: 10L, toUserId: 20L, tradeId: 1L,
            rating: 1, comment: 'contains a phone number', itemName: 'Wizard Hat')
        reviewRepository.findById(42L) >> Optional.of(review)

        when:
        service.adminDeleteReview(500L, 42L, 'PII in comment')

        then:
        1 * reviewRepository.delete(review)
        1 * auditService.log(
            'REVIEW_DELETED_STAFF',
            500L,                                            // actor: staff
            20L,                                             // subject: SELLER (was buyerId=10L pre-fix)
            42L,                                             // resource: review id
            { String summary ->
                summary.contains('1★') &&
                summary.contains('Wizard Hat') &&
                summary.contains('PII in comment') &&
                summary.contains('10')                       // buyer id captured in summary
            }
        )
    }

    // ── eligibleTradesFor ───────────────────────────────────────────

    def "eligibleTradesFor tags each verified trade with whether it's already reviewed"() {
        given:
        def t1 = new Trade(id: 1L, buyerUserId: 10L, sellerUserId: 20L, state: 'VERIFIED',
            itemName: 'Wizard Hat', price: new BigDecimal('5.00'), settledAt: 1_700_000_000_000L)
        def t2 = new Trade(id: 2L, buyerUserId: 10L, sellerUserId: 20L, state: 'VERIFIED',
            itemName: 'Cyber Vest', price: new BigDecimal('9.00'), settledAt: 1_700_000_100_000L)
        tradeRepository.findVerifiedBetween(10L, 20L) >> [t1, t2]
        // The buyer reviewed trade 1. The scoped lookup only returns reviews for
        // THIS pair's trades ([1,2]) — never the buyer's whole cross-seller
        // history (an unrelated trade-99 review would not come back here).
        reviewRepository.findByFromUserIdAndTradeIdIn(10L, [1L, 2L]) >> [
            new Review(id: 7L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 5)
        ]
        0 * reviewRepository.findByFromUserId(10L)

        when:
        def rows = service.eligibleTradesFor(10L, 20L)

        then:
        rows.size() == 2
        rows.find { it.tradeId == 1L }.reviewed == true
        rows.find { it.tradeId == 2L }.reviewed == false
    }

    def "eligibleTradesFor returns empty without hitting the repo when buyer == seller"() {
        when:
        def rows = service.eligibleTradesFor(10L, 10L)

        then:
        rows == []
        0 * tradeRepository.findVerifiedBetween(_, _)
    }

    def "eligibleTradesFor returns empty when the buyer has never traded with the seller"() {
        given:
        tradeRepository.findVerifiedBetween(10L, 20L) >> []

        when:
        def rows = service.eligibleTradesFor(10L, 20L)

        then:
        rows == []
        // No point loading the buyer's review history if there are no trades.
        0 * reviewRepository.findByFromUserId(_)
    }

    // ── replyToReview ───────────────────────────────────────────────

    def "replyToReview saves a fresh reply, stamps the time, and pings the buyer"() {
        given:
        def review = new Review(id: 7L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 4)
        reviewRepository.findById(7L) >> Optional.of(review)
        textSanitizer.clean('thanks for the trade!', 300) >> 'thanks for the trade!'
        reviewRepository.save(_) >> { Review r -> r }

        when:
        def saved = service.replyToReview(20L, 7L, 'thanks for the trade!')

        then:
        1 * banGuard.assertNotBanned(20L)
        1 * notificationService.push(10L, 'REVIEW_REPLIED', _, _, 7L, '/profile?tab=reviews')
        saved.sellerReply == 'thanks for the trade!'
        saved.sellerReplyAt != null
    }

    def "replyToReview 403s when the caller is not the seller"() {
        given:
        def review = new Review(id: 7L, fromUserId: 10L, toUserId: 20L, tradeId: 1L, rating: 4)
        reviewRepository.findById(7L) >> Optional.of(review)

        when:
        // The buyer (id 10) tries to reply to their own review.
        service.replyToReview(10L, 7L, 'sneaky')

        then:
        thrown(ForbiddenException)
        0 * reviewRepository.save(_)
    }

    def "replyToReview 404s on a missing review id"() {
        given:
        reviewRepository.findById(7L) >> Optional.empty()

        when:
        service.replyToReview(20L, 7L, 'hello')

        then:
        thrown(NotFoundException)
        0 * reviewRepository.save(_)
    }

    def "replyToReview clears an existing reply when passed a blank body"() {
        given:
        def review = new Review(id: 7L, fromUserId: 10L, toUserId: 20L, tradeId: 1L,
            rating: 4, sellerReply: 'old reply', sellerReplyAt: 123L)
        reviewRepository.findById(7L) >> Optional.of(review)
        textSanitizer.clean('', 300) >> ''
        Review saved = null
        reviewRepository.save(_) >> { Review r -> saved = r; r }

        when:
        service.replyToReview(20L, 7L, '')

        then:
        saved.sellerReply == null
        saved.sellerReplyAt == null
        // Clearing is not a "reply" — no buyer ping.
        0 * notificationService.push(*_)
    }

    def "replyToReview does NOT re-stamp or re-ping on a no-op re-save of identical text"() {
        given:
        // Seller re-saves the exact same reply (e.g. opened the editor,
        // hit save without changing anything). Must not bump the time or
        // spam the buyer with a fresh REVIEW_REPLIED.
        def review = new Review(id: 7L, fromUserId: 10L, toUserId: 20L, tradeId: 1L,
            rating: 4, sellerReply: 'cheers', sellerReplyAt: 999L)
        reviewRepository.findById(7L) >> Optional.of(review)
        textSanitizer.clean('cheers', 300) >> 'cheers'
        Review saved = null
        reviewRepository.save(_) >> { Review r -> saved = r; r }

        when:
        service.replyToReview(20L, 7L, 'cheers')

        then:
        // Original timestamp preserved — an identical re-save mustn't lie.
        saved.sellerReplyAt == 999L
        0 * notificationService.push(*_)
    }

    def "replyToReview re-stamps and re-pings when the seller genuinely edits the reply"() {
        given:
        def review = new Review(id: 7L, fromUserId: 10L, toUserId: 20L, tradeId: 1L,
            rating: 4, sellerReply: 'cheers', sellerReplyAt: 999L)
        reviewRepository.findById(7L) >> Optional.of(review)
        textSanitizer.clean('cheers, reached out via DM', 300) >> 'cheers, reached out via DM'
        Review saved = null
        reviewRepository.save(_) >> { Review r -> saved = r; r }

        when:
        service.replyToReview(20L, 7L, 'cheers, reached out via DM')

        then:
        saved.sellerReply == 'cheers, reached out via DM'
        saved.sellerReplyAt != 999L
        1 * notificationService.push(10L, 'REVIEW_REPLIED', _, _, 7L, '/profile?tab=reviews')
    }

    def "replyToReview refuses when the banGuard trips"() {
        given:
        banGuard.assertNotBanned(20L) >> { throw new ForbiddenException("banned") }

        when:
        service.replyToReview(20L, 7L, 'x')

        then:
        thrown(ForbiddenException)
        0 * reviewRepository.save(_)
    }

    // ── Helpful votes ───────────────────────────────────────────────

    def "toggleHelpful inserts a row and returns the new count when the user hasn't voted"() {
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        reviewRepository.findById(100L) >> Optional.of(new Review(id: 100L, fromUserId: 10L, toUserId: 20L, rating: 5))
        helpfulRepo.existsByReviewAndUser(100L, 99L) >> false
        helpfulRepo.countByReview(100L) >> 4L

        when:
        def state = service.toggleHelpful(99L, 100L)

        then:
        1 * banGuard.assertNotBanned(99L)
        1 * helpfulRepo.save({ it.reviewId == 100L && it.userId == 99L })
        0 * helpfulRepo.deleteByReviewAndUser(_, _)
        state.viewerHasVoted == true
        state.helpfulCount == 4L
    }

    def "toggleHelpful deletes the row when the user has already voted (unvote)"() {
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        reviewRepository.findById(100L) >> Optional.of(new Review(id: 100L, fromUserId: 10L, toUserId: 20L, rating: 5))
        helpfulRepo.existsByReviewAndUser(100L, 99L) >> true
        helpfulRepo.countByReview(100L) >> 2L

        when:
        def state = service.toggleHelpful(99L, 100L)

        then:
        1 * helpfulRepo.deleteByReviewAndUser(100L, 99L) >> 1
        0 * helpfulRepo.save(_)
        state.viewerHasVoted == false
        state.helpfulCount == 2L
    }

    def "toggleHelpful rejects self-votes"() {
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        // author = 10, viewer = 10 → rejected
        reviewRepository.findById(100L) >> Optional.of(new Review(id: 100L, fromUserId: 10L, toUserId: 20L, rating: 5))

        when:
        service.toggleHelpful(10L, 100L)

        then:
        thrown(BadRequestException)
        0 * helpfulRepo.save(_)
        0 * helpfulRepo.deleteByReviewAndUser(_, _)
    }

    def "toggleHelpful rejects when the helpful-vote repo is unavailable, before any ban or review lookup"() {
        given:
        // helpfulVoteRepository is @Autowired(required = false) — a build
        // without the V29 table leaves it null. The UNSUPPORTED guard must
        // fire first, so we never touch banGuard or reviewRepository.
        service.helpfulVoteRepository = null

        when:
        service.toggleHelpful(99L, 100L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'UNSUPPORTED'
        0 * banGuard.assertNotBanned(_)
        0 * reviewRepository.findById(_)
    }

    def "toggleHelpful rejects a self-vote even after the banGuard passes"() {
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        reviewRepository.findById(100L) >> Optional.of(new Review(id: 100L, fromUserId: 99L, toUserId: 20L, rating: 5))

        when:
        service.toggleHelpful(99L, 100L)

        then:
        // Ban guard runs before the self-vote check — both are pre-write.
        1 * banGuard.assertNotBanned(99L)
        def e = thrown(BadRequestException)
        e.code == 'SELF_VOTE'
        0 * helpfulRepo.existsByReviewAndUser(_, _)
    }

    def "toggleHelpful rejects the reviewed seller upvoting reviews of their own stall"() {
        given:
        // review.toUserId is the seller the review is ABOUT. The author
        // guard only blocks the review WRITER (fromUserId); the seller
        // has the same incentive to self-boost their helpful-sort, so
        // they must be blocked too. Viewer 20 == review.toUserId 20.
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        reviewRepository.findById(100L) >> Optional.of(new Review(id: 100L, fromUserId: 10L, toUserId: 20L, rating: 5))

        when:
        service.toggleHelpful(20L, 100L)

        then:
        1 * banGuard.assertNotBanned(20L)
        def e = thrown(BadRequestException)
        e.code == 'SELF_VOTE'
        0 * helpfulRepo.existsByReviewAndUser(_, _)
        0 * helpfulRepo.save(_)
    }

    def "toggleHelpful refuses when the banGuard trips"() {
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        banGuard.assertNotBanned(99L) >> { throw new ForbiddenException('banned') }

        when:
        service.toggleHelpful(99L, 100L)

        then:
        thrown(ForbiddenException)
        0 * helpfulRepo.save(_)
        0 * helpfulRepo.deleteByReviewAndUser(_, _)
    }

    def "toggleHelpful 404s on a missing review id"() {
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        reviewRepository.findById(42L) >> Optional.empty()

        when:
        service.toggleHelpful(99L, 42L)

        then:
        thrown(NotFoundException)
        0 * helpfulRepo.save(_)
    }

    def "toggleHelpful treats a UNIQUE-constraint race as a graceful no-op, not a 500"() {
        // BUG 2 regression: exists() + save() is a non-atomic read-modify-
        // write. A rapid double-tap has both concurrent requests see
        // exists=false; the first INSERT wins, the second trips the
        // uq_review_helpful_votes_review_user UNIQUE constraint. That used
        // to bubble a DataIntegrityViolationException → HTTP 500. It must
        // now be swallowed and return a normal "already voted" response.
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        reviewRepository.findById(100L) >> Optional.of(new Review(id: 100L, fromUserId: 10L, toUserId: 20L, rating: 5))
        // This request lost the race: exists() still read false (the other
        // request hadn't committed yet) but the INSERT collides.
        helpfulRepo.existsByReviewAndUser(100L, 99L) >> false
        helpfulRepo.countByReview(100L) >> 7L

        when:
        def state = service.toggleHelpful(99L, 100L)

        then:
        // save() is attempted exactly once and throws the constraint violation.
        1 * helpfulRepo.save({ it.reviewId == 100L && it.userId == 99L }) >> {
            throw new org.springframework.dao.DataIntegrityViolationException('duplicate key')
        }
        // The collision must NOT escape as an exception.
        noExceptionThrown()
        // Response matches the normal toggleHelpful shape — the row the
        // winning request inserted is already there, so the viewer is voted.
        state.viewerHasVoted == true
        state.helpfulCount == 7L
        // No unvote was attempted on the losing branch.
        0 * helpfulRepo.deleteByReviewAndUser(_, _)
    }

    def "toggleHelpful re-reads the helpful count after a swallowed constraint race"() {
        // The post-catch response must reflect the count INCLUDING the
        // row the winning request committed — so we re-query countByReview
        // rather than returning a stale number.
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        reviewRepository.findById(100L) >> Optional.of(new Review(id: 100L, fromUserId: 10L, toUserId: 20L, rating: 5))
        helpfulRepo.existsByReviewAndUser(100L, 99L) >> false
        helpfulRepo.save(_) >> { throw new org.springframework.dao.DataIntegrityViolationException('dup') }

        when:
        def state = service.toggleHelpful(99L, 100L)

        then:
        // countByReview is consulted on the catch path to build the response.
        1 * helpfulRepo.countByReview(100L) >> 3L
        state.helpfulCount == 3L
        state.viewerHasVoted == true
    }

    def "toggleHelpful still bubbles a self-vote rejection before any race window"() {
        // Sanity: the constraint-race catch must not mask the SELF_VOTE
        // guard — a self-vote is rejected up front and save() is never
        // reached, so there's no DataIntegrityViolationException to catch.
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        reviewRepository.findById(100L) >> Optional.of(new Review(id: 100L, fromUserId: 99L, toUserId: 20L, rating: 5))

        when:
        service.toggleHelpful(99L, 100L)

        then:
        thrown(BadRequestException)
        0 * helpfulRepo.save(_)
    }

    def "decorateWithHelpful returns zeroed helpful fields when the repo is unavailable"() {
        given:
        service.helpfulVoteRepository = null
        def rows = [
            new Review(id: 1L, fromUserId: 10L, toUserId: 20L, rating: 5),
            new Review(id: 2L, fromUserId: 11L, toUserId: 20L, rating: 4)
        ]

        when:
        def out = service.decorateWithHelpful(rows, 99L)

        then:
        out.size() == 2
        out.every { it.helpfulCount == 0L && it.viewerHasVoted == false }
    }

    // ── pendingReviewsFor (batch 337) ──────────────────────────────

    def "pendingReviewsFor returns one row per unreviewed trade decorated with the seller"() {
        given:
        def t1 = new Trade(id: 1L, buyerUserId: 10L, sellerUserId: 20L,
                           state: 'VERIFIED', itemName: 'Wizard Hat',
                           price: new BigDecimal('5.00'), settledAt: 1_700_000_000_000L)
        def t2 = new Trade(id: 2L, buyerUserId: 10L, sellerUserId: 30L,
                           state: 'VERIFIED', itemName: 'Cyber Vest',
                           price: new BigDecimal('12.00'), settledAt: 1_700_000_100_000L)
        tradeRepository.findUnreviewedByBuyer(10L, _) >> [t1, t2]
        steamUserRepository.findAllById(_) >> [
            new SteamUser(id: 20L, displayName: 'Alice', avatarUrl: 'a.png'),
            new SteamUser(id: 30L, displayName: 'Bob',   avatarUrl: 'b.png')
        ]

        when:
        def rows = service.pendingReviewsFor(10L)

        then:
        rows.size() == 2
        rows[0].tradeId == 1L
        rows[0].sellerUserId == 20L
        rows[0].sellerName == 'Alice'
        rows[0].sellerAvatarUrl == 'a.png'
        rows[0].itemName == 'Wizard Hat'
        rows[0].price == new BigDecimal('5.00')
        rows[0].settledAt == 1_700_000_000_000L
        rows[1].sellerName == 'Bob'
    }

    def "pendingReviewsFor returns empty list when the buyer has no unreviewed trades"() {
        given:
        tradeRepository.findUnreviewedByBuyer(10L, _) >> []

        when:
        def rows = service.pendingReviewsFor(10L)

        then:
        rows == []
        0 * steamUserRepository.findAllById(_)
    }

    def "pendingReviewsFor caps the result at 50 rows so a heavy user doesn't ship an unbounded JSON"() {
        given:
        def flood = (1..80).collect { idx ->
            new Trade(id: idx as Long, buyerUserId: 10L, sellerUserId: 20L,
                      state: 'VERIFIED', itemName: "Item ${idx}",
                      price: new BigDecimal('1.00'), settledAt: System.currentTimeMillis())
        }
        steamUserRepository.findAllById(_) >> [new SteamUser(id: 20L, displayName: 'Alice')]

        when:
        def rows = service.pendingReviewsFor(10L)

        then: 'the 50-cap is pushed into SQL (LIMIT) — service asks for a 50-row page, never hydrating the full flood'
        1 * tradeRepository.findUnreviewedByBuyer(10L, { it.pageSize == 50 }) >> flood.take(50)
        0 * tradeRepository.findUnreviewedByBuyer(10L)
        rows.size() == 50
    }

    def "countPendingReviewsFor forwards to the dedicated COUNT query, not the list query (batch 340)"() {
        given:
        tradeRepository.countUnreviewedByBuyer(10L) >> 42L

        when:
        def n = service.countPendingReviewsFor(10L)

        then:
        n == 42L
        // Critical: the count path must NOT hydrate full trade rows just
        // to call .size() on them — drives every avatar-badge refresh.
        0 * tradeRepository.findUnreviewedByBuyer(_)
    }

    def "countPendingReviewsFor short-circuits on null user id without hitting the repo"() {
        when:
        def n = service.countPendingReviewsFor(null)

        then:
        n == 0L
        0 * tradeRepository.countUnreviewedByBuyer(_)
    }

    def "pendingReviewsFor short-circuits on null user id without hitting the repo"() {
        when:
        def rows = service.pendingReviewsFor(null)

        then:
        rows == []
        0 * tradeRepository.findUnreviewedByBuyer(_)
    }

    def "decorateWithHelpful projects per-review count + viewerHasVoted from batch lookups"() {
        given:
        def helpfulRepo = Mock(com.sboxmarket.repository.ReviewHelpfulVoteRepository)
        service.helpfulVoteRepository = helpfulRepo
        def rows = [
            new Review(id: 1L, fromUserId: 10L, toUserId: 20L, rating: 5),
            new Review(id: 2L, fromUserId: 11L, toUserId: 20L, rating: 3)
        ]
        helpfulRepo.countBulk([1L, 2L]) >> [
            [1L, 3L] as Object[],
            [2L, 0L] as Object[]
        ]
        helpfulRepo.findVotedReviewIds(99L, [1L, 2L]) >> [1L]

        when:
        def out = service.decorateWithHelpful(rows, 99L)

        then:
        out.find { it.id == 1L }.helpfulCount == 3L
        out.find { it.id == 1L }.viewerHasVoted == true
        out.find { it.id == 2L }.helpfulCount == 0L
        out.find { it.id == 2L }.viewerHasVoted == false
    }

    def "decorateWithHelpful emits a server-computed 'mine' flag and never leaks the raw fromUserId"() {
        given:
        service.helpfulVoteRepository = null
        def rows = [
            new Review(id: 1L, fromUserId: 99L, toUserId: 20L, rating: 5),  // the viewer's own
            new Review(id: 2L, fromUserId: 10L, toUserId: 20L, rating: 4)   // someone else's
        ]

        when:
        def out = service.decorateWithHelpful(rows, 99L)

        then:
        // `mine` is true only for the viewer's own review...
        out.find { it.id == 1L }.mine == true
        out.find { it.id == 2L }.mine == false
        // ...and the reviewer's internal user id is NOT exposed on this
        // public projection — the enumeration-leak fix (2026-05-21).
        out.every { !it.containsKey('fromUserId') }
    }

    def "decorateWithHelpful marks every review not-mine for an anonymous viewer"() {
        given:
        service.helpfulVoteRepository = null
        def rows = [new Review(id: 1L, fromUserId: 10L, toUserId: 20L, rating: 5)]

        when:
        def out = service.decorateWithHelpful(rows, null)

        then:
        out[0].mine == false
    }
}
