package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Review
import com.sboxmarket.service.ReviewService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Thin HTTP adapter for Review CRUD. Authoring goes through
 * ReviewService.leaveReview which enforces trade ownership.
 *
 * Public endpoints:
 *   GET /api/reviews/user/{id}       — list all reviews received by a user
 *   GET /api/reviews/user/{id}/summary — count + average
 *
 * Authed endpoints:
 *   POST /api/reviews                — leave a review (requires login)
 */
@RestController
@RequestMapping("/api/reviews")
@Slf4j
class ReviewController {

    @Autowired ReviewService reviewService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @PostMapping
    ResponseEntity<Map> leaveReview(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        Long tradeId
        Integer rating
        try {
            tradeId = body?.tradeId == null ? null : Long.valueOf(body.tradeId.toString())
        } catch (NumberFormatException ignored) {
            throw new BadRequestException("INVALID_TRADE_ID", "tradeId must be a number")
        }
        try {
            rating = body?.rating == null ? null : Integer.valueOf(body.rating.toString())
        } catch (NumberFormatException ignored) {
            throw new BadRequestException("INVALID_RATING", "rating must be a number (1-5)")
        }
        // Upstream comment cap — ReviewService.leaveReview runs the
        // value through textSanitizer.clean with a 500-char truncation,
        // but accepting a megabyte of text just to drop 99.9% of it on
        // the floor is a DoS amplification. Cap at MEDIUM_TEXT (5000)
        // upstream; the service still owns the canonical sanitizer cap.
        com.sboxmarket.util.InputLimits.requireMax(body, 'comment',
            com.sboxmarket.util.InputLimits.MEDIUM_TEXT,
            'COMMENT_TOO_LONG', 'comment')
        def comment = body?.comment as String
        def review = reviewService.leaveReview(uid, tradeId, rating, comment)
        ResponseEntity.ok([
            id:        review.id,
            toUserId:  review.toUserId,
            rating:    review.rating,
            comment:   review.comment,
            createdAt: review.createdAt
        ])
    }

    @GetMapping("/user/{id}")
    ResponseEntity<List<Map>> forUser(@PathVariable Long id, HttpServletRequest req) {
        // Viewer id drives the viewerHasVoted flag on each row — optional
        // for public stall visitors, essential for signed-in buyers so
        // the "you've already marked this helpful" UI state is correct.
        def viewer = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        // ReviewService.listForUser already caps at 200 most-recent
        // (PageRequest 0,200) ordered by createdAt DESC. Optional
        // `?limit=N` lets the stall card request just the top 5 instead
        // of the full 200-row page when it only renders a preview.
        // `?limit` is read off the raw request so the method stays
        // signature-stable and Groovy can't auto-generate a default-
        // value overload that double-maps the route.
        int cap = parseLimit(req, 200, 200)
        def rows = reviewService.listForUser(id)
        if (rows.size() > cap) rows = rows.take(cap)
        // `private` — the per-row viewerHasVoted flag diverges per
        // viewer. 60s cap; reviews trickle in slowly (one per verified
        // trade), so 60s of staleness is well below any human perception
        // of "new review landed."
        ResponseEntity.ok()
            .header('Cache-Control', 'private, max-age=60')
            .body(reviewService.decorateWithHelpful(rows, viewer))
    }

    /** Reviews the signed-in user has AUTHORED (as a buyer) — powers
     *  the Profile → Reviews → Given tab so a user can see + delete
     *  feedback they've left on sellers. Authed endpoint because the
     *  review-authorship index is PII for the reviewer.
     *  Service caps at 200 (PageRequest 0,200 ordered createdAt DESC);
     *  optional `?limit=N` clamps the response into [1, 200]. */
    @GetMapping("/mine")
    ResponseEntity<List<Review>> mine(HttpServletRequest req) {
        def uid = requireUser(req)
        int cap = parseLimit(req, 200, 200)
        def rows = reviewService.listAuthoredBy(uid)
        if (rows.size() > cap) rows = rows.take(cap)
        ResponseEntity.ok(rows)
    }

    @GetMapping("/user/{id}/summary")
    ResponseEntity<Map> summary(@PathVariable Long id) {
        // Aggregate counts + average — viewer-agnostic. 2-min public
        // cache; reviews are write-rare so generous stale tolerance.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=120')
            .body(reviewService.summaryForUser(id))
    }

    /** Reviewable trades between the signed-in viewer (as buyer) and a given
     *  seller. Each entry carries a `reviewed` flag so the UI can disable
     *  rows the viewer has already written a review for. Backs the
     *  "Leave a review" CTA on /stall/{sellerId}.
     *  The underlying `findVerifiedBetween` is uncapped at the repo
     *  layer; bound the response here so a frequent buyer/seller pair
     *  with hundreds of trades can't ship a massive JSON. Default 50,
     *  `?limit` (1..200) overrides. */
    @GetMapping("/eligible/{sellerId}")
    ResponseEntity<List<Map>> eligible(@PathVariable Long sellerId, HttpServletRequest req) {
        def uid = requireUser(req)
        int cap = parseLimit(req, 50, 200)
        def rows = reviewService.eligibleTradesFor(uid, sellerId)
        if (rows.size() > cap) rows = rows.take(cap)
        ResponseEntity.ok(rows)
    }

    /** Every VERIFIED trade the signed-in user has as a buyer but hasn't
     *  left a review for yet — across every seller. Drives the Profile →
     *  Reviews "N trades to review" chip + list so a user doesn't have
     *  to navigate seller-by-seller to find trades that still want feedback.
     *  Service caps at 50; optional `?limit` lets the caller request
     *  even fewer (e.g. just the top 10 for a sidebar preview). */
    @GetMapping("/pending")
    ResponseEntity<Map> pending(HttpServletRequest req) {
        def uid = requireUser(req)
        int cap = parseLimit(req, 50, 200)
        def rows = reviewService.pendingReviewsFor(uid)
        if (rows.size() > cap) rows = rows.take(cap)
        ResponseEntity.ok([count: rows.size(), items: rows])
    }

    /** Parse `?limit=N` off the raw request, clamp into [1, max], fall
     *  back to `defaultCap` on missing / blank / non-numeric input.
     *  Inlined per controller so each list endpoint can stay single-arg
     *  and avoid Groovy default-value overload generation that would
     *  double-map the route at Spring startup. */
    private static int parseLimit(HttpServletRequest req, int defaultCap, int max) {
        def raw = req.getParameter('limit')
        if (raw == null || raw.isBlank()) return defaultCap
        try {
            int n = Integer.parseInt(raw.trim())
            return Math.min(Math.max(n, 1), max)
        } catch (NumberFormatException ignored) {
            return defaultCap
        }
    }

    /** Delete a review the caller authored. Forbidden for anyone else —
     *  sellers can only reply, not erase, which is why this endpoint
     *  gates on `review.fromUserId == uid` at the service layer. */
    @DeleteMapping("/{id}")
    ResponseEntity<Map> delete(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireUser(req)
        reviewService.deleteReview(uid, id)
        ResponseEntity.ok([id: id, status: 'DELETED'])
    }

    /** Toggle a helpful-vote on a review. Returns the new count + the
     *  viewer's own vote state so the UI can update optimistically with
     *  the authoritative value. Self-vote rejected at the service layer. */
    @PostMapping("/{id}/helpful")
    ResponseEntity<Map> toggleHelpful(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireUser(req)
        def state = reviewService.toggleHelpful(uid, id)
        ResponseEntity.ok([
            id:              id,
            helpfulCount:    state.helpfulCount,
            viewerHasVoted:  state.viewerHasVoted
        ])
    }

    /** Seller replies to (or clears the reply on) a review they received.
     *  Body: `{ "reply": "..." }`. Empty or missing reply clears. Only the
     *  review's `toUserId` may hit this endpoint. */
    @PostMapping("/{id}/reply")
    ResponseEntity<Map> reply(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        // Upstream cap — replyToReview sanitizes to 300 chars but never
        // checks the inbound size. Reject overlong payloads at the door.
        com.sboxmarket.util.InputLimits.requireMax(body, 'reply',
            com.sboxmarket.util.InputLimits.MEDIUM_TEXT,
            'REPLY_TOO_LONG', 'reply')
        def replyBody = body?.reply as String
        def saved = reviewService.replyToReview(uid, id, replyBody)
        ResponseEntity.ok([
            id:             saved.id,
            sellerReply:    saved.sellerReply,
            sellerReplyAt:  saved.sellerReplyAt
        ])
    }
}
