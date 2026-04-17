package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.service.SellerFollowService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping('/api/follows')
@Slf4j
class SellerFollowController {

    @Autowired SellerFollowService service

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<List<Map>> list(HttpServletRequest req) {
        def uid = requireUser(req)
        def rows = service.listFollowing(uid)
        ResponseEntity.ok(rows.collect { f ->
            [id: f.id, sellerUserId: f.sellerUserId, createdAt: f.createdAt]
        })
    }

    @PostMapping('/{sellerId}')
    ResponseEntity<Map> follow(@PathVariable Long sellerId, HttpServletRequest req) {
        def uid = requireUser(req)
        def row = service.follow(uid, sellerId)
        ResponseEntity.ok([id: row.id, sellerUserId: row.sellerUserId, following: true])
    }

    @DeleteMapping('/{sellerId}')
    ResponseEntity<Map> unfollow(@PathVariable Long sellerId, HttpServletRequest req) {
        def uid = requireUser(req)
        service.unfollow(uid, sellerId)
        ResponseEntity.ok([sellerUserId: sellerId, following: false])
    }

    /** Lightweight "do I follow this seller + how many do?" — used by
     *  the public stall page. Public endpoint (anonymous viewer gets
     *  `following: false` without a 401). */
    @GetMapping('/status/{sellerId}')
    ResponseEntity<Map> status(@PathVariable Long sellerId, HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        def count = service.countFollowers(sellerId)
        def following = uid != null && service.isFollowing(uid, sellerId)
        ResponseEntity.ok([sellerUserId: sellerId, following: following, followerCount: count])
    }

    /** "From sellers you follow" feed — recent visible active listings
     *  from every seller the signed-in user follows. Returns an empty
     *  array for anonymous viewers (and for users who follow nobody)
     *  so the home-page rail can hide itself without a 401 branch.
     *  Capped at 20 rows server-side. */
    @GetMapping('/feed')
    ResponseEntity<List<com.sboxmarket.model.Listing>> feed(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) return ResponseEntity.ok([])
        ResponseEntity.ok(service.feedForFollower(uid, 20))
    }
}
