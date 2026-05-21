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
    @Autowired(required = false) com.sboxmarket.repository.ReviewHelpfulVoteRepository helpfulVoteRepository

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
            def oldRating = existing.rating
            def oldComment = existing.comment
            existing.rating  = rating
            existing.comment = cleanComment
            // Batch 745 — stamp the editedAt marker so the public stall
            // can render "· edited" next to the createdAt timestamp.
            // Only set on a real change (different rating or comment);
            // a no-op re-submit with identical content shouldn't lie
            // about being edited.
            boolean changed = (oldRating != rating)
                || ((oldComment ?: '') != (cleanComment ?: ''))
            if (changed) existing.editedAt = System.currentTimeMillis()
            row = reviewRepository.save(existing)
            log.info("Review updated: from=${fromUserId} trade=${tradeId} rating=${rating} changed=${changed}")
            // Notify the seller when the rating actually changed — a
            // buyer updating from 5★ to 1★ after a bad experience
            // mustn't land silently. Comment-only edits don't trigger
            // a ping (would be noise). REVIEW_UPDATED is a distinct
            // kind so sellers can mute updates separately from creates.
            if (oldRating != null && oldRating != rating) {
                try {
                    def arrow = rating < oldRating ? '↓' : '↑'
                    notificationService?.push(trade.sellerUserId, 'REVIEW_UPDATED',
                        "Review updated · ${oldRating}★ ${arrow} ${rating}★",
                        "${author?.displayName ?: 'A buyer'} changed their rating on ${trade.itemName}",
                        row.id, '/profile?tab=reviews')
                } catch (Exception e) {
                    log.warn("REVIEW_UPDATED push failed for seller ${trade.sellerUserId}: ${e.message}")
                }
            }
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
            // Notify the seller of the new review. NotificationService.push
            // is itself @Transactional, so it joins this method's
            // transaction — an un-guarded failure here would mark the tx
            // rollback-only and silently destroy the review row that was
            // just saved successfully. Mirror the try/catch the
            // REVIEW_UPDATED path above uses so a transient bell-push
            // hiccup can't undo a valid review.
            try {
                notificationService?.push(trade.sellerUserId, 'REVIEW_RECEIVED',
                    "New ${rating}★ review",
                    "${author?.displayName ?: 'A buyer'} left feedback on ${trade.itemName}",
                    row.id, '/profile?tab=reviews')
            } catch (Exception e) {
                log.warn("REVIEW_RECEIVED push failed for seller ${trade.sellerUserId}: ${e.message}")
            }
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
        def oldReply = review.sellerReply
        boolean nowEmpty = !clean || clean.trim().isEmpty()
        // Only treat this as a real change when the reply text actually
        // differs from what's stored — a seller re-saving the identical
        // text (or clearing an already-empty reply) is a no-op. Mirrors
        // the `changed` guard in leaveReview so an idempotent re-submit
        // doesn't bump sellerReplyAt or spam the buyer with a fresh ping.
        boolean changed = (oldReply ?: '') != (nowEmpty ? '' : clean)
        if (nowEmpty) {
            review.sellerReply = null
            // Only null the timestamp when there was actually a reply to
            // clear — leaves a never-replied row untouched.
            if (changed) review.sellerReplyAt = null
        } else {
            review.sellerReply = clean
            // Re-stamp only on a genuine edit; an identical re-save keeps
            // the original reply time honest.
            if (changed) review.sellerReplyAt = System.currentTimeMillis()
        }
        def saved = reviewRepository.save(review)
        if (changed) {
            auditService?.log('REVIEW_REPLIED', sellerUserId, review.fromUserId, reviewId,
                review.sellerReply ? "Replied: ${review.sellerReply.take(120)}" : "Cleared reply")
        }
        // Notify the buyer that the seller responded — only on a genuine
        // new/changed reply (not on clear, not on a no-op re-save). A reply
        // is public so the buyer should know it happened, and for nuanced
        // cases (disputed review that gets a polite seller response) the
        // buyer often wants to update their star rating afterwards.
        if (changed && review.sellerReply && review.fromUserId != null && notificationService != null) {
            try {
                notificationService.push(review.fromUserId, 'REVIEW_REPLIED',
                    "Seller replied to your review",
                    review.sellerReply.take(140),
                    reviewId, '/profile?tab=reviews')
            } catch (Exception e) {
                log.warn("REVIEW_REPLIED push failed for buyer ${review.fromUserId}: ${e.message}")
            }
        }
        saved
    }

    /** Delete a review the caller authored. Only the `fromUserId` (the
     *  buyer who wrote it) can remove a review — sellers can't hide
     *  unflattering feedback, only reply to it. Banned users can't delete
     *  either, so a bad-actor buyer who left a libelous review then got
     *  banned can't destroy evidence on their way out (staff delete via
     *  the admin surface, not this endpoint).
     *
     *  Idempotent from the caller's POV — if the id is already gone, we
     *  404 so the UI can distinguish "never existed" from "you don't
     *  own it". */
    /** Admin/CSR override — delete any review regardless of authorship
     *  (batch 479). Used when a review contains profanity, PII, or
     *  violates TOS. Audited as REVIEW_DELETED_STAFF so the override is
     *  traceable; buyer is notified with the reason so they can dispute
     *  or rewrite cleanly. Caller must have already enforced the admin/
     *  CSR gate. */
    @Transactional
    void adminDeleteReview(Long staffUserId, Long reviewId, String reason) {
        def review = reviewRepository.findById(reviewId)
            .orElseThrow { new NotFoundException("Review", reviewId) }
        def buyerId = review.fromUserId
        def sellerId = review.toUserId
        def oldRating = review.rating
        def itemName = review.itemName
        def cleanReason = reason?.trim() ?: 'policy violation'
        reviewRepository.delete(review)
        auditService?.log('REVIEW_DELETED_STAFF', staffUserId, buyerId, reviewId,
            "Staff removed ${oldRating}★ review on ${itemName ?: 'listing'}: ${cleanReason}")
        log.warn("Staff ${staffUserId} deleted review ${reviewId} (buyer=${buyerId}, seller=${sellerId}): ${cleanReason}")
        // Notify the BUYER so they know why their review was removed —
        // without this they'd see it silently disappear on a refresh.
        // Seller doesn't get a separate push (they didn't lose anything
        // they were invested in beyond a reply, and the disappearing
        // star-count change is self-evident).
        if (buyerId != null && notificationService != null) {
            try {
                notificationService.push(buyerId, 'REVIEW_DELETED',
                    "Your review was removed by staff",
                    "Reason: ${cleanReason}. Reach out via support if you disagree.",
                    reviewId, '/profile?tab=reviews')
            } catch (Exception e) {
                log.warn("REVIEW_DELETED_STAFF push failed for buyer ${buyerId}: ${e.message}")
            }
        }
    }

    @Transactional
    void deleteReview(Long fromUserId, Long reviewId) {
        banGuard.assertNotBanned(fromUserId)
        def review = reviewRepository.findById(reviewId)
            .orElseThrow { new NotFoundException("Review", reviewId) }
        if (review.fromUserId != fromUserId) {
            throw new ForbiddenException("You can only delete your own reviews")
        }
        // Snapshot the seller-facing fields BEFORE the row is gone.
        def sellerId   = review.toUserId
        def oldRating  = review.rating
        def itemName   = review.itemName
        def hadReply   = review.sellerReply != null && !review.sellerReply.trim().isEmpty()
        reviewRepository.delete(review)
        auditService?.log('REVIEW_DELETED', fromUserId, sellerId, reviewId,
            "Deleted ${oldRating}★ review")
        log.info("Review deleted: id=${reviewId} from=${fromUserId} to=${sellerId}")
        // Let the seller know their review disappeared. Particularly
        // important when they'd invested effort in a reply — that reply
        // is gone too because it lives on the same row. REVIEW_DELETED
        // shows up in the same MATCHES bucket as the other review pings
        // so it respects per-bucket mute. Fire-and-forget.
        if (sellerId != null && notificationService != null) {
            try {
                def body = hadReply
                    ? "The buyer removed their ${oldRating}★ review on ${itemName ?: 'your listing'}. Your reply is gone with it."
                    : "The buyer removed their ${oldRating}★ review on ${itemName ?: 'your listing'}."
                notificationService.push(sellerId, 'REVIEW_DELETED',
                    "Review removed", body, reviewId, '/profile?tab=reviews')
            } catch (Exception e) {
                log.warn("REVIEW_DELETED push failed for seller ${sellerId}: ${e.message}")
            }
        }
    }

    /**
     * Un-reviewed VERIFIED trades for the given buyer, across every
     * seller. Powers the Profile → Reviews tab's "N trades to review"
     * chip + list so users don't have to hunt seller-by-seller for
     * pending reviews. Decorated with the seller's displayName/avatar
     * so the UI doesn't need a second round-trip per row.
     *
     * Hard-capped at 50 — see the repo-method comment. Stable ordering
     * (newest-first) so paginating in future is a trivial LIMIT change.
     */
    List<Map> pendingReviewsFor(Long buyerUserId) {
        if (tradeRepository == null || buyerUserId == null) return []
        def trades = tradeRepository.findUnreviewedByBuyer(buyerUserId)
        if (trades.isEmpty()) return []
        if (trades.size() > 50) trades = trades.take(50)
        def sellerIds = trades*.sellerUserId.unique()
        def sellers = steamUserRepository.findAllById(sellerIds).collectEntries { [(it.id): it] }
        trades.collect { t ->
            def seller = sellers[t.sellerUserId]
            [
                tradeId:        t.id,
                sellerUserId:   t.sellerUserId,
                sellerName:     seller?.displayName,
                sellerAvatarUrl: seller?.avatarUrl,
                itemName:       t.itemName,
                price:          t.price,
                settledAt:      t.settledAt ?: t.updatedAt
            ]
        }
    }

    /** Count-only version for the nav / avatar badge so we don't have to
     *  fetch + decorate the full list when all the caller wants is the
     *  "12" in a chip. Uses the same index-friendly NOT EXISTS query. */
    long countPendingReviewsFor(Long buyerUserId) {
        if (tradeRepository == null || buyerUserId == null) return 0L
        tradeRepository.countUnreviewedByBuyer(buyerUserId)
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

    /** Reviews a user AUTHORED — powers the Profile → Reviews → Given
     *  tab. Hard-capped at 200 for the same reason as listForUser. */
    List<Review> listAuthoredBy(Long fromUserId) {
        reviewRepository.findByFromUserIdOrderByCreatedAtDesc(
            fromUserId, org.springframework.data.domain.PageRequest.of(0, 200))
    }

    // ── Helpful votes ───────────────────────────────────────────────

    /**
     * Toggle a "helpful" upvote on a review. Idempotent from the
     * caller's POV — if the user already voted, the row is deleted
     * (unvote); otherwise a fresh row is inserted. A user cannot upvote
     * their own review (self-boost would game the sort).
     *
     * Returns the NEW state after the toggle so the caller doesn't need
     * a second roundtrip: `{ helpfulCount, viewerHasVoted }`.
     */
    @Transactional
    Map toggleHelpful(Long userId, Long reviewId) {
        if (helpfulVoteRepository == null) {
            throw new BadRequestException('UNSUPPORTED', 'Helpful votes not available')
        }
        banGuard.assertNotBanned(userId)
        def review = reviewRepository.findById(reviewId)
            .orElseThrow { new NotFoundException('Review', reviewId) }
        if (review.fromUserId == userId) {
            throw new BadRequestException('SELF_VOTE',
                "You can't upvote your own review")
        }
        boolean nowVoted
        if (helpfulVoteRepository.existsByReviewAndUser(reviewId, userId)) {
            helpfulVoteRepository.deleteByReviewAndUser(reviewId, userId)
            nowVoted = false
        } else {
            // exists() + save() is a non-atomic read-modify-write. A rapid
            // double-tap fires two concurrent requests that both observe
            // exists=false and both INSERT; the uq_review_helpful_votes_
            // review_user UNIQUE constraint then rejects the loser with a
            // DataIntegrityViolationException. Treat that as a benign
            // no-op (the vote already exists) instead of bubbling a 500 —
            // the user wanted to be "voted", and they are.
            try {
                helpfulVoteRepository.save(new com.sboxmarket.model.ReviewHelpfulVote(
                    reviewId: reviewId,
                    userId:   userId
                ))
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                log.debug("toggleHelpful race on review=${reviewId} user=${userId} — already voted, treating as no-op")
                return [ helpfulCount: helpfulVoteRepository.countByReview(reviewId),
                         viewerHasVoted: true ]
            }
            nowVoted = true
        }
        def count = helpfulVoteRepository.countByReview(reviewId)
        [ helpfulCount: count, viewerHasVoted: nowVoted ]
    }

    /** Decorate a list of reviews with helpful-vote counts + the
     *  viewer's own vote state. Pure projection — inputs are not
     *  mutated. Empty input returns empty. */
    List<Map> decorateWithHelpful(List<Review> rows, Long viewerUserId) {
        if (!rows) return []
        if (helpfulVoteRepository == null) {
            return rows.collect { toMap(it, 0L, false) }
        }
        def ids = rows.collect { it.id }.findAll { it != null }
        if (ids.isEmpty()) return rows.collect { toMap(it, 0L, false) }
        def counts = helpfulVoteRepository.countBulk(ids)
                .collectEntries { [(it[0] as Long): ((it[1] as Number) ?: 0).longValue()] }
        def viewerVoted = viewerUserId == null
            ? [] as Set
            : helpfulVoteRepository.findVotedReviewIds(viewerUserId, ids).toSet()
        rows.collect { r ->
            toMap(r, counts[r.id] ?: 0L, viewerVoted.contains(r.id))
        }
    }

    private Map toMap(Review r, Long helpfulCount, boolean viewerHasVoted) {
        [
            id:              r.id,
            fromUserId:      r.fromUserId,
            toUserId:        r.toUserId,
            tradeId:         r.tradeId,
            rating:          r.rating,
            comment:         r.comment,
            fromDisplayName: r.fromDisplayName,
            itemName:        r.itemName,
            createdAt:       r.createdAt,
            // Batch 745 — surface the editedAt marker so the public
            // stall can render "· edited Xm ago" next to the original
            // createdAt. Null = never edited (most rows).
            editedAt:        r.editedAt,
            sellerReply:     r.sellerReply,
            sellerReplyAt:   r.sellerReplyAt,
            helpfulCount:    helpfulCount ?: 0L,
            viewerHasVoted:  viewerHasVoted
        ]
    }

    /**
     * Bulk per-seller rating summary keyed by user id. One GROUP BY query
     * powers the seller-rating decoration on every visible listing row in
     * a single request — the listing controller calls this once per page
     * load and attaches `[count, average]` to each Listing.sellerRating /
     * Listing.sellerReviewCount. Sellers with zero reviews are absent from
     * the result map (caller treats missing as "no rating yet" — null).
     */
    Map<Long, Map> summariesForUsers(Collection<Long> userIds) {
        def out = [:] as Map<Long, Map>
        if (userIds == null || userIds.isEmpty()) return out
        def ids = userIds.findAll { it != null }.toSet().toList()
        if (ids.isEmpty()) return out
        try {
            def rows = reviewRepository.aggregateForUsers(ids)
            rows?.each { row ->
                def uid   = (row[0] as Number)?.longValue()
                def count = ((row[1] as Number) ?: 0).longValue()
                def avg   = row[2] == null ? null : ((row[2] as Number).doubleValue())
                def rounded = avg == null ? null : (Math.round(avg * 10.0) / 10.0)
                if (uid != null && count > 0) {
                    out[uid] = [count: count, average: rounded]
                }
            }
        } catch (Exception e) {
            log.debug("summariesForUsers failed: ${e.message}")
        }
        out
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
