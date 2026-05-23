package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ConflictException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.service.UserBlockService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Per-viewer block-list surface. Every endpoint is session-gated — no
 * public view into who has blocked whom. Kept in its own controller
 * (rather than bolted onto ProfileController) so the block-list
 * semantics stay cleanly scoped.
 *
 *   GET    /api/profile/blocks             — list users the caller has blocked
 *   POST   /api/profile/blocks/{userId}    — block userId (idempotent)
 *   DELETE /api/profile/blocks/{userId}    — unblock userId (idempotent)
 */
@RestController
@RequestMapping("/api/profile/blocks")
@Slf4j
class UserBlockController {

    @Autowired UserBlockService userBlockService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    /** Users the caller has blocked. The service itself caps at
     *  UserBlockService.MAX_PER_USER (100) and the underlying repo
     *  query orders by `createdAt DESC` so newest-blocked-first is
     *  stable. Default 50, `?limit` (1..200) overrides. The `count`
     *  field reflects the slice returned, not the total — same as
     *  the rest of the bounded-list family. `?limit` is read off the
     *  raw request so the method stays single-arg and Groovy can't
     *  auto-generate a default-value overload that double-maps the
     *  route. */
    @GetMapping
    ResponseEntity<Map> list(HttpServletRequest req) {
        def uid = requireUser(req)
        int cap = parseLimit(req, 50, 200)
        def items = userBlockService.listBlocked(uid)
        if (items.size() > cap) items = items.take(cap)
        ResponseEntity.ok([count: items.size(), items: items])
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

    @PostMapping("/{userId}")
    ResponseEntity<Map> block(@PathVariable Long userId, HttpServletRequest req) {
        def uid = requireUser(req)
        if (userId == null) {
            throw new BadRequestException('INVALID_BLOCK', 'Target user id is required')
        }
        def block = userBlockService.block(uid, userId)
        if (block == null) {
            // The service's idempotent re-block branch re-reads the row
            // after existsBlock() says it's present; a concurrent unblock
            // between those two queries leaves it returning null. That's
            // a transient conflict, not a server fault — map it to 409
            // rather than dereferencing null into a 500 INTERNAL_ERROR.
            throw new ConflictException('BLOCK_CONFLICT',
                'Block state changed concurrently — please retry')
        }
        ResponseEntity.ok([
            blockedUserId: block.blockedUserId,
            createdAt:     block.createdAt
        ])
    }

    @DeleteMapping("/{userId}")
    ResponseEntity<Map> unblock(@PathVariable Long userId, HttpServletRequest req) {
        def uid = requireUser(req)
        def n = userBlockService.unblock(uid, userId)
        ResponseEntity.ok([removed: n])
    }

    /** Bulk-unblock — wipes every block the caller owns. Batch 355. */
    @DeleteMapping
    ResponseEntity<Map> unblockAll(HttpServletRequest req) {
        def uid = requireUser(req)
        int removed = userBlockService.unblockAll(uid)
        ResponseEntity.ok([removed: removed])
    }
}
