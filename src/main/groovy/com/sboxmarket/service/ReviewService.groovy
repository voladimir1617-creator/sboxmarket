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
                row.id, '/profile')
            auditService?.log('REVIEW_CREATED', fromUserId, trade.sellerUserId, row.id,
                "Review: ${rating}★")
        }
        row
    }

    /** Seller replies publicly to one of their received reviews. Only the
     *  seller (the review's `toUserId`) can reply. Idempotent — calling
     *  again overwrites the previous reply. Pass a blank/null body to
     *  clear an existing reply. Banned sellers cannot reply. */
    @Transactional
    Review replyToReview(Long sellerUserId, Long reviewId, String body) {
        banGuard.assertNotBanned(sellerUserId)
        def review = reviewRepository.findById(reviewId)
            .orElseThrow { new NotFoundException("Review", reviewId) }
        if (review.toUserId != sellerUserId) {
            throw new ForbiddenException("Only the seller can reply to this review")
        }
        def clean = textSanitizer.clean(body ?: '', 300)
        if (!clean || clean.trim().isEmpty()) {
            review.sellerReply = null
            review.sellerReplyAt = null
        } else {
            review.sellerReply = clean
            review.sellerReplyAt = System.currentTimeMillis()
        }
        def saved = reviewRepository.save(review)
        auditService?.log('REVIEW_REPLIED', sellerUserId, review.fromUserId, reviewId,
            review.sellerReply ? "Replied: ${review.sellerReply.take(120)}" : "Cleared reply")
        saved
    }

    /** All verified trades between a given buyer and seller, each tagged
     *  with whether the buyer has already reviewed the trade. Empty when
     *  the buyer hasn't traded with the seller yet. */
    List<Map> eligibleTradesFor(Long buyerUserId, Long sellerUserId) {
        if (tradeRepository == null || buyerUserId == null || sellerUserId == null) return []
        if (buyerUserId == sellerUserId) return []
        def trades = tradeRepository.findVerifiedBetween(buyerUserId, sellerUserId)
        if (trades.isEmpty()) return []
        def existing = reviewRepository.findByFromUserId(buyerUserId)
        def reviewedTradeIds = existing.collect { it.tradeId } as Set
        trades.collect { t ->
            [
                tradeId:   t.id,
                itemName:  t.itemName,
                price:     t.price,
                settledAt: t.settledAt ?: t.updatedAt,
                reviewed:  reviewedTradeIds.contains(t.id)
            ]
        }
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
            return [count: 0, average: null, histogram: [0,0,0,0,0]]
        }
        def count = ((rows[0][0] as Number) ?: 0).longValue()
        def avg   = rows[0][1] == null ? null : ((rows[0][1] as Number).doubleValue())
        def rounded = avg == null ? null : (Math.round(avg * 10.0) / 10.0)
        // Per-star histogram: index 0 = 5★, 1 = 4★, 2 = 3★, 3 = 2★, 4 = 1★.
        // Drives the breakdown bar chart on the stall + profile review tabs.
        def buckets = [0L, 0L, 0L, 0L, 0L]
        if (count > 0) {
            def hist = reviewRepository.histogramForUser(toUserId)
            hist?.each { row ->
                def stars = ((row[0] as Number) ?: 0).intValue()
                def n     = ((row[1] as Number) ?: 0).longValue()
                if (stars >= 1 && stars <= 5) buckets[5 - stars] = n
            }
        }
        [count: count, average: rounded, histogram: buckets]
    }
}
