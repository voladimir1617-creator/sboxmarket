package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Review
import com.sboxmarket.repository.ReviewRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Trade-anchored seller reviews. Every review has to reference a specific
 * trade that:
 *   (a) was completed (state VERIFIED), and
 *   (b) has the review author as the buyer side.
 *
 * This prevents fake ratings from users who never actually traded with the
 * seller — a common failure mode on peer-to-peer marketplaces.
 *
 * Banned users cannot leave reviews (BanGuard). Self-reviews are rejected.
 * Re-reviewing the same trade returns the existing row instead of creating
 * a second one.
 */
@Service
@Slf4j
class ReviewService {

    @Autowired ReviewRepository reviewRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired(required = false) TradeRepository tradeRepository
    @Autowired TextSanitizer textSanitizer
    @Autowired BanGuard banGuard
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) AuditService auditService

    @Transactional
    Review leaveReview(Long fromUserId, Long tradeId, Integer rating, String comment) {
        banGuard.assertNotBanned(fromUserId)
        if (rating == null || rating < 1 || rating > 5) {
            throw new BadRequestException("INVALID_RATING", "Rating must be 1–5")
        }
        if (tradeRepository == null) {
            throw new BadRequestException("UNSUPPORTED", "Trade system not available")
        }
        def trade = tradeRepository.findById(tradeId)
                .orElseThrow { new NotFoundException("Trade", tradeId) }
        if (trade.state != 'VERIFIED') {
            throw new BadRequestException("TRADE_NOT_VERIFIED",
                "Can only review completed trades")
        }
        if (trade.buyerUserId == null || trade.buyerUserId != fromUserId) {
            throw new ForbiddenException("Only the buyer of this trade can review it")
        }
        if (trade.sellerUserId == null) {
            throw new BadRequestException("NO_SELLER",
                "This trade has no counterparty to review")
        }
        if (trade.sellerUserId == fromUserId) {
            throw new BadRequestException("SELF_REVIEW", "You cannot review yourself")
        }

        // Idempotent: re-reviewing the same trade updates the existing row.
        def existing = reviewRepository.findByFromUserIdAndTradeId(fromUserId, tradeId)
        def cleanComment = textSanitizer.clean(comment ?: '', 500)
        def author = steamUserRepository.findById(fromUserId).orElse(null)

        Review row
        if (existing != null) {
            existing.rating  = rating
            existing.comment = cleanComment
            row = reviewRepository.save(existing)
            log.info("Review updated: from=${fromUserId} trade=${tradeId} rating=${rating}")
        } else {
            row = reviewRepository.save(new Review(
                fromUserId:      fromUserId,
                toUserId:        trade.sellerUserId,
                tradeId:         tradeId,
                rating:          rating,
                comment:         cleanComment,
                fromDisplayName: author?.displayName,
                itemName:        trade.itemName
            ))
            log.info("Review created: from=${fromUserId} to=${trade.sellerUserId} trade=${tradeId} rating=${rating}")
            notificationService?.push(trade.sellerUserId, 'REVIEW_RECEIVED',
                "New ${rating}★ review",
                "${author?.displayName ?: 'A buyer'} left feedback on ${trade.itemName}",
                row.id)
            auditService?.log('REVIEW_CREATED', fromUserId, trade.sellerUserId, row.id,
                "Review: ${rating}★")
        }
        row
    }

    List<Review> listForUser(Long toUserId) {
        // Hard-cap at 200 most recent so a seller with 10k reviews doesn't
        // ship a 10k-row JSON to every visitor of their stall page.
        // Pagination through the rest is a future UI concern.
        reviewRepository.findByToUserIdOrderByCreatedAtDesc(
            toUserId, org.springframework.data.domain.PageRequest.of(0, 200))
    }

    /** Aggregate rating summary used by the public stall page header. */
    Map summaryForUser(Long toUserId) {
        def rows = reviewRepository.aggregateForUser(toUserId)
        if (!rows || rows[0] == null) {
            return [count: 0, average: null]
        }
        def count = ((rows[0][0] as Number) ?: 0).longValue()
        def avg   = rows[0][1] == null ? null : ((rows[0][1] as Number).doubleValue())
        def rounded = avg == null ? null : (Math.round(avg * 10.0) / 10.0)
        [count: count, average: rounded]
    }
}
