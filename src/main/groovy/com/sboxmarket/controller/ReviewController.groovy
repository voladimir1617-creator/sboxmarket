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
    ResponseEntity<List<Review>> forUser(@PathVariable Long id) {
        ResponseEntity.ok(reviewService.listForUser(id))
    }

    @GetMapping("/user/{id}/summary")
    ResponseEntity<Map> summary(@PathVariable Long id) {
        ResponseEntity.ok(reviewService.summaryForUser(id))
    }
}
