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
    @Autowired(required = false) com.sboxmarket.service.UserBlockService userBlockService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    /**
     * Coerce a JSON `muted` field to a real boolean. A bare Groovy
     * `value as Boolean` is unsafe here: Jackson maps an untyped Map's
     * JSON string to a `String`, and Groovy-truth makes EVERY non-empty
     * string truthy — so `{"muted":"false"}` would coerce to `true` and
     * mute instead of un-mute. Handle the real `Boolean` and the
     * string-encoded forms explicitly; anything else is a 400.
     */
    private static boolean parseMutedFlag(Object raw) {
        if (raw instanceof Boolean) return raw
        if (raw instanceof String) {
            def s = raw.trim().toLowerCase()
            if (s == 'true')  return true
            if (s == 'false') return false
        }
        throw new com.sboxmarket.exception.BadRequestException('INVALID_FIELD',
            "'muted' must be a boolean (true/false)")
    }

    /** Sellers the caller follows. Per-user count is naturally capped
     *  by SellerFollowService.PER_USER_LIMIT (200), but we still bound
     *  the response here. Default 50, `?limit` (1..200) overrides.
     *  Repository ordering is `createdAt DESC` so newest-first is
     *  stable across requests. `?limit` is read off the raw request
     *  so the method stays single-arg and Groovy's default-value
     *  overload generation can't double-map this route. */
    @GetMapping
    ResponseEntity<List<Map>> list(HttpServletRequest req) {
        def uid = requireUser(req)
        int cap = parseLimit(req, 50, 200)
        def rows = service.listFollowing(uid)
        if (rows.size() > cap) rows = rows.take(cap)
        ResponseEntity.ok(rows.collect { f ->
            [
                id:                  f.id,
                sellerUserId:        f.sellerUserId,
                createdAt:           f.createdAt,
                notificationsMuted:  Boolean.TRUE.equals(f.notificationsMuted)
            ]
        })
    }

    /** Parse `?limit=N` off the raw request, clamp into [1, max], fall
     *  back to `defaultCap` on missing / blank / non-numeric input. */
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

    /** Mute / un-mute the new-listing pings for a single follow without
     *  unfollowing (V36 / batch 279). Body: `{muted: true|false}`. The
     *  follower row itself stays — only the bell + email fan-out is
     *  suppressed for this seller. */
    @PatchMapping('/{sellerId}/mute')
    ResponseEntity<Map> mute(@PathVariable Long sellerId,
                             @RequestBody Map body,
                             HttpServletRequest req) {
        def uid = requireUser(req)
        if (body == null || body.muted == null) {
            throw new com.sboxmarket.exception.BadRequestException('MISSING_FIELD',
                "'muted' (boolean) is required")
        }
        def muted = parseMutedFlag(body.muted)
        def row = service.setNotificationsMuted(uid, sellerId, muted)
        ResponseEntity.ok([
            sellerUserId:        row.sellerUserId,
            notificationsMuted:  Boolean.TRUE.equals(row.notificationsMuted)
        ])
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

    /** Bulk-unfollow every seller the caller currently follows. Idempotent
     *  (zero-row callers get `{unfollowed:0}`, not 404). Mirrors the bulk-
     *  clear family: `/api/watchlist` DELETE, `/api/buy-orders/cancel-all`,
     *  `/api/offers/outgoing/cancel-all`, `/api/bids/auto/cancel-all`. */
    @DeleteMapping
    ResponseEntity<Map> unfollowAll(HttpServletRequest req) {
        int n = service.unfollowAll(requireUser(req))
        ResponseEntity.ok([unfollowed: n])
    }

    /** Bulk-flip the "notifications muted" flag on every follow row.
     *  Body: `{muted: true|false}`. Rows stay — only the bell + email
     *  fan-out is suppressed when muted. Lets a power follower silence
     *  engagement pings without losing their curated follow list. */
    @PatchMapping('/mute-all')
    ResponseEntity<Map> muteAll(@RequestBody Map body, HttpServletRequest req) {
        // Authenticate BEFORE validating the body — an anonymous caller
        // must always get a 401, never a body-validation 400 that leaks
        // the endpoint shape. Matches the auth-first order used by every
        // other write endpoint here and across the controller layer.
        def uid = requireUser(req)
        if (body == null || body.muted == null) {
            throw new com.sboxmarket.exception.BadRequestException('MISSING_FIELD',
                "'muted' (boolean) is required")
        }
        def muted = parseMutedFlag(body.muted)
        int n = service.setAllMuted(uid, muted)
        ResponseEntity.ok([touched: n, muted: muted])
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
        def rows = service.feedForFollower(uid, 20)
        // Batch 353 — if the follower has blocked any of the sellers
        // they follow (follow and block are orthogonal — you can do
        // either without the other), strip their listings from the
        // feed. Block trumps follow: if I blocked them I shouldn't see
        // their listings here regardless of my follow state.
        if (userBlockService != null && !rows.isEmpty()) {
            def blocked = userBlockService.blockedIdsFor(uid)
            if (!blocked.isEmpty()) {
                def set = new HashSet<>(blocked)
                rows = rows.findAll { l ->
                    l.sellerUserId == null || !set.contains(l.sellerUserId)
                }
            }
        }
        ResponseEntity.ok(rows)
    }
}
