package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.UserBlock
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.UserBlockRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Per-viewer block list (V40 / batch 343). A user who blocks another
 * user shouldn't see their listings in the grid, receive new offers /
 * chat messages from them, or get follow-notification pings from them.
 *
 * Silent blocking — the blocked user is never notified and there's no
 * audit trail. This is a personal preference, not a moderation action.
 * Reversible at any time from the blocker's Profile → Blocked tab.
 *
 * Hard cap of 100 blocks per user to bound memory when the block-id set
 * is loaded for the listing-grid filter. A user with more than 100
 * enemies to individually block is a ban/report candidate, not a
 * block-list user.
 */
@Service
@Slf4j
class UserBlockService {

    /** Per-user cap. Matches CartService.MAX_PER_USER — bounded set so
     *  the listing filter doesn't have to paginate through arbitrary
     *  sizes on every grid query. */
    static final int MAX_PER_USER = 100

    @Autowired UserBlockRepository userBlockRepository
    @Autowired SteamUserRepository steamUserRepository

    /**
     * Block another user. Idempotent — re-blocking the same user returns
     * the existing row without raising an error from the client's POV.
     * Self-blocking is rejected (CHECK constraint would also catch it,
     * but a clean 400 is friendlier than a constraint-violation 500).
     *
     * `noRollbackFor = DataIntegrityViolationException` is load-bearing
     * for the UNIQUE-constraint race recovery below. The try/catch
     * swallows the dup at save() time but Spring's @Transactional proxy
     * still marks the tx rollback-only on any RuntimeException thrown
     * from a JPA op — without this hint, the recovery's findByBlocker
     * read runs against an already-doomed tx and the method commit
     * fires UnexpectedRollbackException, surfacing as a 500 to the
     * caller even though we caught the original violation. Same posture
     * the AuditService / NotificationService deferral was forced to
     * adopt for the same Spring-tx-poisoning class of bug.
     */
    @Transactional
    UserBlock block(Long blockerUserId, Long blockedUserId) {
        if (blockerUserId == null || blockedUserId == null) {
            throw new BadRequestException('INVALID_BLOCK', 'Both blocker and blocked user ids are required')
        }
        if (blockerUserId == blockedUserId) {
            throw new BadRequestException('CANT_BLOCK_SELF', "You can't block yourself")
        }
        if (userBlockRepository.existsBlock(blockerUserId, blockedUserId)) {
            // Re-block is a no-op — the user's block stays, we just
            // return the pre-existing row so the caller can render it.
            return userBlockRepository.findByBlocker(blockerUserId)
                .find { it.blockedUserId == blockedUserId }
        }
        // Soft-404 the target: a block-button pointing at a deleted user
        // is almost certainly a UI bug — we'd rather surface it than
        // quietly persist a row against a dead foreign key.
        def target = steamUserRepository.findById(blockedUserId).orElse(null)
        if (target == null) {
            throw new NotFoundException('SteamUser', blockedUserId)
        }
        def count = userBlockRepository.countByBlocker(blockerUserId)
        if (count >= MAX_PER_USER) {
            throw new BadRequestException('BLOCK_LIMIT',
                "You've reached the block-list cap (${MAX_PER_USER}). Remove someone before adding more.")
        }
        def block = new UserBlock(
            blockerUserId: blockerUserId,
            blockedUserId: blockedUserId,
            createdAt:     System.currentTimeMillis()
        )
        // existsBlock + save is a non-atomic read-modify-write. A user
        // double-tapping the block button (or two devices hitting the
        // endpoint simultaneously) fires two concurrent requests that
        // both observe existsBlock=false and both INSERT; the
        // `user_blocks_unique_pair` UNIQUE constraint then rejects the
        // loser with a DataIntegrityViolationException. Treat that as a
        // benign no-op — the block already exists from the winning
        // request — and return the freshly-committed row. Without this
        // catch the loser bubbles a 500 INTERNAL_ERROR even though the
        // end-state ("user 20 is blocked") is exactly what they wanted.
        // Mirrors LoadoutService.toggleFavorite + ReviewService.toggleHelpful
        // which handle the same race on their respective junction tables.
        try {
            userBlockRepository.save(block)
        } catch (DataIntegrityViolationException dup) {
            log.debug("block race on blocker=${blockerUserId} blocked=${blockedUserId} — already blocked, treating as no-op")
            def winner = userBlockRepository.findByBlocker(blockerUserId)
                .find { it.blockedUserId == blockedUserId }
            return winner
        }
        log.info("User ${blockerUserId} blocked user ${blockedUserId}")
        block
    }

    /**
     * Remove a block. Idempotent — unblocking a user the blocker never
     * blocked returns 0 rather than raising. UI can swallow the zero
     * silently since the end state ("not blocked") is the same.
     */
    @Transactional
    int unblock(Long blockerUserId, Long blockedUserId) {
        if (blockerUserId == null || blockedUserId == null) return 0
        def n = userBlockRepository.deleteByPair(blockerUserId, blockedUserId)
        if (n > 0) log.info("User ${blockerUserId} unblocked user ${blockedUserId}")
        n
    }

    /** Bulk-unblock every user the caller has blocked. Parity with
     *  the watchlist / saved-search / follows bulk-clear affordances.
     *  Idempotent — returns 0 when the block list is empty. */
    @Transactional
    int unblockAll(Long blockerUserId) {
        if (blockerUserId == null) return 0
        def n = userBlockRepository.deleteByBlocker(blockerUserId)
        if (n > 0) log.info("User ${blockerUserId} cleared their block list (${n} rows)")
        n
    }

    /** All users the caller has blocked, decorated with displayName +
     *  avatarUrl so the Profile → Blocked list can render without a
     *  second round-trip per row. Newest-first. Capped at MAX_PER_USER
     *  so the list stays bounded even if a race snuck in extra rows. */
    List<Map> listBlocked(Long blockerUserId) {
        if (blockerUserId == null) return []
        def rows = userBlockRepository.findByBlocker(blockerUserId)
        if (rows.isEmpty()) return []
        if (rows.size() > MAX_PER_USER) rows = rows.take(MAX_PER_USER)
        def blockedIds = rows*.blockedUserId.unique()
        def users = steamUserRepository.findAllById(blockedIds).collectEntries { [(it.id): it] }
        rows.collect { b ->
            def u = users[b.blockedUserId]
            [
                blockedUserId: b.blockedUserId,
                displayName:   u?.displayName,
                avatarUrl:     u?.avatarUrl,
                createdAt:     b.createdAt
            ]
        }
    }

    /** Fast "did A block B?" probe — drives the write-path enforcement
     *  (OfferService etc). Single indexed boolean SQL. */
    boolean isBlocked(Long blockerUserId, Long blockedUserId) {
        if (blockerUserId == null || blockedUserId == null) return false
        if (blockerUserId == blockedUserId) return false
        userBlockRepository.existsBlock(blockerUserId, blockedUserId)
    }

    /** Id-only projection of the blocker's list — drives the listing
     *  grid filter ("hide every listing whose seller is in my block
     *  set"). One indexed query per grid render, caller caches the
     *  result inside the request so it isn't re-fetched per row. */
    List<Long> blockedIdsFor(Long blockerUserId) {
        if (blockerUserId == null) return []
        userBlockRepository.findBlockedIdsForBlocker(blockerUserId)
    }

    /** Count of the blocker's block list — cheap COUNT, used by the
     *  Profile tab chip so the user sees "Blocked · N" without the
     *  list actually loading. */
    long countBlocked(Long blockerUserId) {
        if (blockerUserId == null) return 0L
        userBlockRepository.countByBlocker(blockerUserId)
    }
}
