package com.sboxmarket.service

import com.sboxmarket.model.CartItem
import com.sboxmarket.repository.CartItemRepository
import com.sboxmarket.repository.ListingRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Server-side cart (cross-device sync). The localStorage `sb_cart`
 * stays as the offline write-through cache for signed-in users; the
 * server's set wins on any conflict.
 *
 * Bulk-merge bridges the rollout: a user who already has items in
 * localStorage signs in for the first time after this lands, the
 * client posts that array, and the service backfills any rows that
 * aren't already there. From then on every add/remove is a single
 * endpoint hit.
 *
 * Same per-user cap (50) as the existing `/cart/checkout` endpoint
 * already enforces — keeps the table tiny and the UI scrollable.
 */
@Service
@Slf4j
class CartService {

    /** Per-user cart cap. Mirrors `CartController.checkout` which
     *  rejects bodies > 50 listing ids — there's no point persisting
     *  rows that can never check out. */
    static final int MAX_PER_USER = 50

    @Autowired CartItemRepository repository
    /** Optional so existing unit tests that build the service with `new
     *  CartService(repository: ...)` (no listing repo wired) keep
     *  passing — the own-listing guard becomes a no-op in that mode and
     *  the buy-path's OWN_LISTING check remains the backstop. */
    @Autowired(required = false) ListingRepository listingRepository

    @Transactional
    boolean add(Long userId, Long listingId) {
        if (userId == null || listingId == null) return false
        if (repository.existsByUserAndListing(userId, listingId)) return false
        // Own-listing guard. Without this, a seller can pad their own cart
        // with their own active listings up to MAX_PER_USER — every row
        // then fails OWN_LISTING at checkout (PurchaseService.buy line
        // 160) and the cart can't scrub the failures (only OK rows are
        // removed in CartController.checkout), so the user gets stuck
        // with a full-but-unbuyable cart that blocks any real add until
        // they hand-clear each row. Rejecting at the add-path mirrors
        // the buy-path's OWN_LISTING semantics and keeps the cart usable.
        if (listingRepository != null) {
            Long sellerUserId = null
            try { sellerUserId = listingRepository.findSellerUserIdById(listingId) }
            catch (Exception e) { log.debug("own-listing probe failed for listing=${listingId}: ${e.message}") }
            if (sellerUserId != null && sellerUserId == userId) {
                throw new com.sboxmarket.exception.BadRequestException('OWN_LISTING',
                    "You can't add your own listing to your cart")
            }
        }
        def current = repository.findListingIdsByUser(userId)
        if (current.size() >= MAX_PER_USER) {
            throw new com.sboxmarket.exception.BadRequestException('CART_FULL',
                "Cart is capped at ${MAX_PER_USER} items. Remove some before adding more.")
        }
        // existsByUserAndListing + save is a non-atomic read-modify-write.
        // The same user tapping Add-to-cart from two devices (or
        // double-clicking the button) fires two concurrent requests that
        // both observe exists=false and both INSERT; the V31
        // `uq_cart_items_user_listing` UNIQUE constraint then rejects the
        // loser with a DataIntegrityViolationException, which bubbles to
        // the slower client as a 500 even though the row was successfully
        // added by the winning request. Treat the violation as a benign
        // no-op — idempotent semantics are preserved without leaking a
        // 500 on a hot double-click. IDENTITY id generation forces the
        // INSERT at save() time, so the violation surfaces synchronously
        // where we can catch it (not at commit). Mirrors
        // WatchlistService.add and LoadoutService.toggleFavorite.
        try {
            repository.save(new CartItem(userId: userId, listingId: listingId))
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            log.debug("Cart add race for user=${userId} listing=${listingId} — already in cart, treating as no-op")
            return false
        }
        true
    }

    @Transactional
    boolean remove(Long userId, Long listingId) {
        if (userId == null || listingId == null) return false
        repository.deleteByUserAndListing(userId, listingId) > 0
    }

    @Transactional
    int clear(Long userId) {
        if (userId == null) return 0
        repository.deleteAllByUser(userId)
    }

    List<Long> list(Long userId) {
        if (userId == null) return []
        repository.findListingIdsByUser(userId)
    }

    /**
     * One-shot merge of a client-side cart into the server set —
     * the rollout bridge for users with localStorage carts at the
     * moment this feature ships. Dedupes input, skips existing rows,
     * respects the cap.
     *
     * Returns the post-merge full list so the client can replace
     * its cache in one swap.
     */
    @Transactional
    List<Long> bulkMerge(Long userId, List<Long> incoming) {
        if (userId == null) return []
        def cleaned = (incoming ?: []).findAll { it != null }.unique()
        if (cleaned.size() > MAX_PER_USER) cleaned = cleaned.take(MAX_PER_USER)
        if (cleaned.isEmpty()) return list(userId)
        // Pre-filter against the rows the user already has. This MUST be
        // the only guard against the UNIQUE (user_id, listing_id)
        // constraint (V31 `uq_cart_items_user_listing`): a JPA constraint
        // violation marks the whole transaction rollback-only, so a
        // swallowed try/catch around `save` does NOT recover — it just
        // lets the loop keep mutating a doomed @Transactional. Every
        // subsequent save and the closing `list(userId)` then run against
        // a rollback-only tx, and Spring's commit throws
        // UnexpectedRollbackException → the entire first-sign-in merge
        // 500s anyway (and worse: the failure is now masked behind a
        // debug log instead of an honest stack trace). By only saving
        // ids confirmed absent, no violation can be raised in the first
        // place. Mirrors WatchlistService.bulkMerge — see the matching
        // comment there.
        def existing = repository.findExistingListingIds(userId, cleaned).toSet()
        def toAdd = cleaned.findAll { !existing.contains(it) }
        // Truncate at the per-user cap including pre-existing rows.
        def headroom = MAX_PER_USER - repository.countByUser(userId) as int
        if (headroom <= 0) return list(userId)
        toAdd = toAdd.take(headroom)
        def now = System.currentTimeMillis()
        toAdd.each { listingId ->
            repository.save(new CartItem(userId: userId, listingId: listingId, addedAt: now))
        }
        list(userId)
    }

    /**
     * Daily sweep that deletes cart_items rows pointing at non-ACTIVE
     * listings (batch 506). Complements the inline scrubs in
     * PurchaseService.buy / SellService.cancelListing /
     * AdminService.forceCancelListing which handle the hot paths — this
     * is the safety net for auction-expired, ended-auction-no-bids,
     * bulk-cancel, and any legacy row that pre-dates the inline
     * scrubs. Bounded by a single DELETE ... IN (subquery), so the
     * cost scales with the stale-row count, not the full cart table.
     *
     * Runs daily (the cart UI's client-side stale detector hides
     * these rows in the meantime, so the sweep doesn't need to be
     * frequent — the DB just ends up carrying some dead rows until
     * it runs).
     */
    @Scheduled(fixedDelay = 24L * 60L * 60L * 1000L, initialDelay = 30L * 60L * 1000L)
    @Transactional
    void sweepStaleCartRows() {
        try {
            int deleted = repository.deleteRowsPointingAtNonActiveListings()
            if (deleted > 0) {
                log.info("Cart-stale sweeper deleted ${deleted} cart_items row(s) pointing at non-ACTIVE listings")
            }
        } catch (Exception e) {
            log.warn("Cart-stale sweeper failed: ${e.message}")
        }
    }
}
