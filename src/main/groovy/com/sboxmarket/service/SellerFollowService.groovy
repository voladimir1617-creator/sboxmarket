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

    /** 200-follow cap per user — protects the fanout and keeps the
     *  profile tab scannable. */
    private static final int PER_USER_LIMIT = 200

    @Transactional
    SellerFollow follow(Long followerUserId, Long sellerUserId) {
        if (followerUserId == sellerUserId) {
            throw new BadRequestException('SELF_FOLLOW', "You can't follow yourself")
        }
        def seller = steamUserRepository.findById(sellerUserId)
            .orElseThrow { new NotFoundException('SteamUser', sellerUserId) }
        if (Boolean.TRUE.equals(seller.banned)) {
            throw new BadRequestException('SELLER_BANNED', 'This seller is banned')
        }
        def existing = repo.findByFollowerUserIdAndSellerUserId(followerUserId, sellerUserId)
        if (existing.isPresent()) return existing.get()

        def count = repo.findByFollowerUserIdOrderByCreatedAtDesc(followerUserId).size()
        if (count >= PER_USER_LIMIT) {
            throw new BadRequestException('FOLLOW_LIMIT',
                "Follow limit reached (${PER_USER_LIMIT}). Unfollow a seller before adding more.")
        }
        def row = new SellerFollow(
            followerUserId: followerUserId,
            sellerUserId:   sellerUserId,
            createdAt:      System.currentTimeMillis()
        )
        repo.save(row)
        log.info("User ${followerUserId} followed seller ${sellerUserId}")
        row
    }

    @Transactional
    void unfollow(Long followerUserId, Long sellerUserId) {
        repo.deleteByFollowerUserIdAndSellerUserId(followerUserId, sellerUserId)
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
        followers.each { f ->
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
                def follower = steamUserRepository.findById(f.followerUserId).orElse(null)
                if (emailService != null && follower != null &&
                        Boolean.TRUE.equals(follower.emailVerified) && follower.email &&
                        Boolean.TRUE.equals(follower.emailNotificationsEnabled)) {
                    def itemUrl = listing.item?.id != null
                        ? "/item/${listing.item.id}".toString()
                        : null
                    emailService.sendNewListingFromSeller(follower.email, follower.displayName,
                        sellerName, listing.item?.name, listing.price, itemUrl)
                }
            } catch (Exception e) {
                log.warn("Follower listing email failed for user ${f.followerUserId}: ${e.message}")
            }
        }
    }
}
