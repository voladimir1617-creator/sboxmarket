package com.sboxmarket

import com.sboxmarket.model.Review
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
 * Regression cover for the REVIEW_REPLIED audit-subject fix.
 *
 * Before the fix `replyToReview` wrote its audit row with
 * `subjectUserId = review.fromUserId` — the buyer-author. That broke
 * CSR's `bySubject(sellerId)` rollup: a public seller reply attached to
 * a review on the seller's stall vanished from the seller-scoped audit
 * history, even though the reply is the seller's action AND lives on
 * the seller's review page.
 *
 * All four review-lifecycle events must align on `subjectUserId = sellerId`:
 *   • REVIEW_CREATED         (line 209 in ReviewService)
 *   • REVIEW_REPLIED         (line 265 — the row this spec pins)
 *   • REVIEW_DELETED         (line 352)
 *   • REVIEW_DELETED_STAFF   (line 318 — Wave 118 fix)
 *
 * Mirrors the existing "REVIEW_DELETED_STAFF audit row uses the SELLER as
 * subject (CSR-search alignment)" spec in ReviewServiceSpec.groovy.
 */
class ReviewServiceReplyAuditSubjectSpec extends Specification {

    ReviewRepository    reviewRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    TradeRepository     tradeRepository     = Mock()
    TextSanitizer       textSanitizer       = Mock()
    BanGuard            banGuard            = Mock()
    NotificationService notificationService = Mock()
    AuditService        auditService        = Mock()

    @Subject
    ReviewService service = new ReviewService(
        reviewRepository    : reviewRepository,
        steamUserRepository : steamUserRepository,
        tradeRepository     : tradeRepository,
        textSanitizer       : textSanitizer,
        banGuard            : banGuard,
        notificationService : notificationService,
        auditService        : auditService
    )

    def "replyToReview audits with the SELLER as subject so CSR bySubject(sellerId) surfaces it"() {
        given:
        // Buyer 10 left a 4★ review for seller 20. Seller now replies.
        def review = new Review(id: 7L, fromUserId: 10L, toUserId: 20L,
            tradeId: 1L, rating: 4, itemName: 'Wizard Hat')
        reviewRepository.findById(7L) >> Optional.of(review)
        textSanitizer.clean('thanks for the trade!', 300) >> 'thanks for the trade!'
        reviewRepository.save(_) >> { Review r -> r }

        when:
        service.replyToReview(20L, 7L, 'thanks for the trade!')

        then:
        1 * banGuard.assertNotBanned(20L)
        // Pre-fix: subject was review.fromUserId (10L → the buyer).
        // The fix re-anchors subject = sellerUserId (20L) so all four
        // REVIEW_* lifecycle audit rows live under the same seller-id
        // bySubject() query.
        1 * auditService.log(
            'REVIEW_REPLIED',
            20L,                             // actor: seller (the replier)
            20L,                             // subject: SELLER (was 10L pre-fix)
            7L,                              // resource: review id
            { String summary ->
                // Buyer id stays captured in the summary so counterparty
                // traceability is preserved even though the subject column
                // is now the seller.
                summary.contains('10') &&
                summary.contains('thanks for the trade!')
            }
        )
    }

    def "replyToReview audits CLEARED reply with the SELLER as subject and buyer id in the summary"() {
        given:
        // Existing reply being cleared.
        def review = new Review(id: 7L, fromUserId: 10L, toUserId: 20L,
            tradeId: 1L, rating: 4, sellerReply: 'old reply', sellerReplyAt: 123L)
        reviewRepository.findById(7L) >> Optional.of(review)
        textSanitizer.clean('', 300) >> ''
        reviewRepository.save(_) >> { Review r -> r }

        when:
        service.replyToReview(20L, 7L, '')

        then:
        // Same subject alignment on the clear path — the change is still
        // a touch on the seller's stall and must show up under bySubject(20L).
        1 * auditService.log(
            'REVIEW_REPLIED',
            20L,                             // actor: seller
            20L,                             // subject: SELLER (was 10L pre-fix)
            7L,                              // resource: review id
            { String summary -> summary.contains('Cleared') && summary.contains('10') }
        )
    }

    def "replyToReview does NOT audit a no-op re-save of identical text"() {
        // Sanity guard so a future refactor that always audits doesn't
        // regress the existing "no audit on no-op" contract.
        given:
        def review = new Review(id: 7L, fromUserId: 10L, toUserId: 20L,
            tradeId: 1L, rating: 4, sellerReply: 'cheers', sellerReplyAt: 999L)
        reviewRepository.findById(7L) >> Optional.of(review)
        textSanitizer.clean('cheers', 300) >> 'cheers'
        reviewRepository.save(_) >> { Review r -> r }

        when:
        service.replyToReview(20L, 7L, 'cheers')

        then:
        0 * auditService.log('REVIEW_REPLIED', _, _, _, _)
    }
}
