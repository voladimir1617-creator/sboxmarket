package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SellerFollow
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SellerFollowRepository
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * "Follow a seller" subscription. When a followed seller posts a new
 * listing, every follower gets a NEW_LISTING_FROM_SELLER notification.
 * Separate from the item-level watchlist alerts (those fire on price
 * drops for a specific item, not on seller activity).
 *
 * Follower count is exposed on the public stall page for trust signal.
 * Followers list lives in Profile so users can unfollow without
 * hunting for each stall.
 */
@Service
@Slf4j
class SellerFollowService {

    @Autowired SellerFollowRepository repo
    @Autowired SteamUserRepository steamUserRepository
    @Autowired ListingRepository listingRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) EmailService emailService
    @Autowired(required = false) UserBlockService userBlockService

    /** 200-follow cap per user — protects the fanout and keeps the
     *  profile tab scannable. */
    private static final int PER_USER_LIMIT = 200

    /** Hard cap on the per-listing fanout. A popular seller can have
     *  thousands of followers but a single listing-creation request
     *  shouldn't issue an unbounded burst of pushes/emails inline.
     *  500 covers realistic cases without letting one viral seller
     *  block the request thread; the overflow stays in the follower
     *  count + "From sellers you follow" rail, just doesn't get the
     *  bell/email ping for that one listing. */
    private static final int FANOUT_MAX = 500

    // noRollbackFor — same rollback-only leak as wave 136 closed for
    // UserBlockService.block. The catch (DataIntegrityViolationException)
    // recovery path below runs INSIDE the @Transactional, but Spring's
    // DIVE handler marks the shared outer tx rollback-only BEFORE the
    // catch fires. The recovery's findByFollower lookup commits nothing
    // — the next request in the same worker thread reads inconsistent
    // state, and at commit time the outer "successful" response is
    // followed by an UnexpectedRollbackException. noRollbackFor on the
    // exact exception class the catch handles tells Spring "this is a
    // benign duplicate, leave the tx alive". Identical fix shape to
    // wave 136 UserBlockService and the sibling SavedSearchService /
    // WatchlistService noRollbackFor postures.
    @Transactional(noRollbackFor = [DataIntegrityViolationException])
    SellerFollow follow(Long followerUserId, Long sellerUserId) {
        if (followerUserId == sellerUserId) {
            throw new BadRequestException('SELF_FOLLOW', "You can't follow yourself")
        }
        def seller = steamUserRepository.findById(sellerUserId)
            .orElseThrow { new NotFoundException('SteamUser', sellerUserId) }
        if (Boolean.TRUE.equals(seller.banned)) {
            throw new BadRequestException('SELLER_BANNED', 'This seller is banned')
        }
        // Bidirectional block guard. Either party blocking the other
        // breaks the follow relationship — a follower who blocked the
        // seller can't see their listings (so the engagement pings
        // would be cruel), and a seller who blocked the follower
        // shouldn't have to see the follower count tick up or get the
        // SELLER_FOLLOWED ping. Symmetric with OfferService's block
        // gate. Block service is optional — when wiring is missing the
        // guard falls through and the existing follow flow proceeds.
        if (userBlockService != null) {
            try {
                if (userBlockService.isBlocked(followerUserId, sellerUserId)) {
                    throw new BadRequestException('BLOCKED_SELLER',
                        "You can't follow a seller you've blocked")
                }
                if (userBlockService.isBlocked(sellerUserId, followerUserId)) {
                    // Same surface response as a banned-seller — telling the
                    // follower "this seller blocked you" leaks moderation
                    // state; the seller has already declared they don't
                    // want engagement from this account.
                    throw new BadRequestException('SELLER_BANNED', 'This seller is banned')
                }
            } catch (BadRequestException brex) {
                throw brex
            } catch (Exception ignore) {
                // Fail-open on a block-service outage — follow proceeds,
                // mirrors the fanout-side fail-open behavior.
            }
        }
        def existing = repo.findByFollowerUserIdAndSellerUserId(followerUserId, sellerUserId)
        if (existing.isPresent()) return existing.get()

        def count = repo.countByFollowerUserId(followerUserId)
        if (count >= PER_USER_LIMIT) {
            throw new BadRequestException('FOLLOW_LIMIT',
                "Follow limit reached (${PER_USER_LIMIT}). Unfollow a seller before adding more.")
        }
        def row = new SellerFollow(
            followerUserId: followerUserId,
            sellerUserId:   sellerUserId,
            createdAt:      System.currentTimeMillis()
        )
        // findByFollowerUserIdAndSellerUserId + save is a non-atomic
        // read-modify-write — same TOCTOU race UserBlockService.block
        // closed in 6418f56. A user double-tapping the Follow button (or
        // two devices firing simultaneously) both observe existing=empty
        // and both attempt INSERT; V19's UNIQUE(follower_user_id,
        // seller_user_id) constraint then rejects the loser with
        // DataIntegrityViolationException. Uncaught, it bubbles to the
        // catch-all as a 500 INTERNAL_ERROR — even though the end-state
        // ("user 20 follows seller 5") is exactly what the user wanted.
        // Catch the dup, treat as a benign no-op, and return the row
        // the winning request committed via a fresh lookup.
        try {
            repo.save(row)
        } catch (DataIntegrityViolationException dup) {
            log.debug("follow race on follower=${followerUserId} seller=${sellerUserId} — already following, treating as no-op")
            def winner = repo.findByFollowerUserIdAndSellerUserId(followerUserId, sellerUserId)
            if (winner.isPresent()) return winner.get()
            // Vanishingly unlikely: the winner row exists per the
            // UNIQUE-violation we just caught but the second read couldn't
            // find it (read-after-write replica lag? row was deleted
            // between violation and re-read?). Surface the original
            // throw — better than silently returning a half-built row.
            throw dup
        }
        // Notify the seller (batch 536). Before this, a new follow
        // was invisible until the seller happened to open their
        // stall and notice the count tick up. The ping is engagement
        // fuel for active sellers AND a subtle fraud signal — a stall
        // getting rapid follower growth from fresh accounts is a
        // targeting pattern staff can investigate.
        if (notificationService != null) {
            try {
                def follower = steamUserRepository.findById(followerUserId).orElse(null)
                def followerName = follower?.displayName ?: "user #${followerUserId}"
                long totalNow = repo.countBySeller(sellerUserId)
                notificationService.push(sellerUserId, 'SELLER_FOLLOWED',
                    "New follower · ${followerName}",
                    "${followerName} is now following your stall. You have ${totalNow} follower${totalNow == 1 ? '' : 's'}.",
                    followerUserId,
                    "/stall/${sellerUserId}".toString())
            } catch (Exception e) {
                log.warn("SELLER_FOLLOWED push failed for seller ${sellerUserId}: ${e.message}")
            }
        }
        log.info("User ${followerUserId} followed seller ${sellerUserId}")
        row
    }

    @Transactional
    void unfollow(Long followerUserId, Long sellerUserId) {
        repo.deleteByFollowerUserIdAndSellerUserId(followerUserId, sellerUserId)
    }

    /**
     * Bulk-flip `notificationsMuted` to `muted` for every follow the
     * user owns. Returns the count touched. Idempotent — running it
     * twice with the same value is a no-op beyond a single UPDATE.
     * Keeps the follow rows alive (the mute is a quiet signal, not a
     * removal) so the seller's follower count doesn't change.
     */
    @Transactional
    int setAllMuted(Long followerUserId, boolean muted) {
        if (followerUserId == null) return 0
        int n = repo.updateMutedForFollower(followerUserId, muted)
        if (n > 0) log.info("Bulk-${muted ? 'muted' : 'unmuted'} ${n} follow(s) for user ${followerUserId}")
        n
    }

    /**
     * Bulk-unfollow every seller the user follows in a single DELETE.
     * Returns the count wiped. Parallels the bulk-clear family:
     * auto-bids, buy-orders, outgoing offers, watchlist. Idempotent:
     * a zero-follow caller gets 0, never a 404. Mutes are wiped
     * alongside the rows (the mute flag lives on the SellerFollow
     * entity — deleting the row removes the flag with it).
     */
    @Transactional
    int unfollowAll(Long followerUserId) {
        if (followerUserId == null) return 0
        int n = repo.deleteByFollower(followerUserId)
        if (n > 0) log.info("Unfollowed all ${n} seller(s) for user ${followerUserId}")
        n
    }

    boolean isFollowing(Long followerUserId, Long sellerUserId) {
        if (followerUserId == null || sellerUserId == null) return false
        repo.findByFollowerUserIdAndSellerUserId(followerUserId, sellerUserId).isPresent()
    }

    long countFollowers(Long sellerUserId) {
        repo.countBySeller(sellerUserId)
    }

    List<SellerFollow> listFollowing(Long followerUserId) {
        repo.findByFollowerUserIdOrderByCreatedAtDesc(followerUserId)
    }

    /**
     * Toggle "mute new-listing pings" for a single follow relationship
     * (V36 / batch 279). Keeps the follower row alive so the public
     * follower count + discovery-feed inclusion don't change — only the
     * NEW_LISTING_FROM_SELLER bell/email fan-out is suppressed.
     *
     * Returns the updated SellerFollow row, or throws NotFoundException
     * when the user doesn't currently follow the seller.
     */
    @org.springframework.transaction.annotation.Transactional
    SellerFollow setNotificationsMuted(Long followerUserId, Long sellerUserId, boolean muted) {
        def row = repo.findByFollowerUserIdAndSellerUserId(followerUserId, sellerUserId)
            .orElseThrow { new com.sboxmarket.exception.NotFoundException('SellerFollow', sellerUserId) }
        row.notificationsMuted = muted
        repo.save(row)
    }

    /**
     * Home-page "From sellers you follow" rail — recent visible active
     * listings from every seller the given user follows. Returns an
     * empty list when the user follows nobody (signed-out or zero-
     * follows). Bounded to `limit` rows so a power follower with 200
     * sellers active doesn't ship a 10k-row payload on every home
     * render.
     */
    List<Listing> feedForFollower(Long followerUserId, int limit = 20) {
        if (followerUserId == null) return []
        def sellerIds = repo.findSellerIdsByFollower(followerUserId)
        if (sellerIds == null || sellerIds.isEmpty()) return []
        int cap = Math.max(1, Math.min(limit, 50))
        listingRepository.findActiveVisibleBySellerIds(
            sellerIds,
            org.springframework.data.domain.PageRequest.of(0, cap))
    }

    /**
     * Fanout called by SellService when a new listing is created.
     * Pushes one NEW_LISTING_FROM_SELLER notification per follower,
     * tolerating per-row push failures. No-op when the seller has no
     * followers yet.
     */
    void notifyFollowersOfNewListing(Listing listing) {
        if (listing?.sellerUserId == null) return
        def followers = repo.findBySellerUserId(listing.sellerUserId)
        if (followers.isEmpty()) return
        def sellerName = listing.sellerName ?: 'A seller you follow'
        def itemName = listing.item?.name ?: 'a new item'
        def priceStr = listing.price != null ? "\$${listing.price.toPlainString()}" : ''
        def body = "${itemName} · ${priceStr}".trim()
        // Filter out followers who muted this seller's listing pings
        // (V36 / batch 279). They stay in the follower count + the
        // discovery-feed inclusion, just don't get the bell + email.
        followers = followers.findAll { !Boolean.TRUE.equals(it.notificationsMuted) }
        if (followers.isEmpty()) return

        // Bulk fetch every non-muted follower's SteamUser row once so
        // both the push + email paths share the lookup AND we can skip
        // banned accounts (batch 314). Without this filter, a banned
        // user who had followed a hot seller pre-ban kept getting
        // "new listing" bell + email pings with no way to act on them.
        def followerIds = followers*.followerUserId.findAll { it != null }
        def usersById = [:]
        if (!followerIds.isEmpty()) {
            try {
                steamUserRepository.findAllById(followerIds).each { usersById[it.id] = it }
            } catch (Exception e) {
                log.warn("Follower bulk lookup failed; degrading to push-without-ban-check: ${e.message}")
            }
        }

        // Batch 345 — also skip followers who have blocked this seller.
        // A blocked follower can happen when a user follows a seller,
        // then later blocks them without unfollowing (blocking hides
        // listings, unfollowing stops pings; the two aren't the same).
        // Bulk-fetch the block set once; small per-fanout cost, O(1)
        // per-follower check afterwards.
        def blockedByFollower = [:] as Map<Long, Boolean>
        if (userBlockService != null && listing.sellerUserId != null) {
            followerIds.each { fid ->
                if (fid != null) {
                    try {
                        blockedByFollower[fid] = userBlockService.isBlocked(fid, listing.sellerUserId)
                    } catch (Exception e) {
                        // Fail-open — a block-check error shouldn't drop the fanout.
                        blockedByFollower[fid] = false
                    }
                }
            }
        }

        int sent = 0
        for (f in followers) {
            if (sent >= FANOUT_MAX) {
                log.info("Follower fanout capped at ${FANOUT_MAX} for seller ${listing.sellerUserId} (listing ${listing.id})")
                break
            }
            def follower = usersById[f.followerUserId]
            // Skip banned follower accounts entirely — no push, no email.
            if (follower != null && Boolean.TRUE.equals(follower.banned)) continue
            // Skip followers who have blocked this seller (batch 345).
            if (Boolean.TRUE.equals(blockedByFollower[f.followerUserId])) continue

            try {
                notificationService?.push(f.followerUserId, 'NEW_LISTING_FROM_SELLER',
                    "${sellerName} just listed something",
                    body, listing.id,
                    listing.item?.id != null ? "/item/${listing.item.id}" : null)
            } catch (Exception e) {
                log.warn("Follower notification failed for user ${f.followerUserId}: ${e.message}")
            }
            // Email the follower too — gated on the email-notifications
            // preference because this is an engagement email, not
            // operational.
            try {
                if (emailService != null && emailService.canSendTo(follower, 'FOLLOWS')) {
                    def itemUrl = listing.item?.id != null
                        ? "/item/${listing.item.id}".toString()
                        : null
                    emailService.sendNewListingFromSeller(follower.email, follower.displayName,
                        sellerName, listing.item?.name, listing.price, itemUrl)
                }
            } catch (Exception e) {
                log.warn("Follower listing email failed for user ${f.followerUserId}: ${e.message}")
            }
            sent++
        }
    }
}
