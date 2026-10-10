package com.sboxmarket.service

import com.sboxmarket.model.WatchlistItem
import com.sboxmarket.repository.WatchlistItemRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Server-side watchlist (cross-device sync). Replaces the localStorage
 * `sb_watchlist` array as the source of truth for signed-in users; the
 * client still keeps a write-through cache so an offline reload shows
 * stars without a network round trip, but the server's list wins on
 * conflict.
 *
 * Bulk-merge is the bridge for the rollout: a user signs in for the
 * first time after the feature lands, the client posts its localStorage
 * ids, and the service backfills any that aren't already starred. From
 * then on every star/unstar is a single endpoint hit.
 *
 * Hard cap of 500 items per user — generous enough that no real buyer
 * hits it, low enough that a runaway script can't blow the table out.
 */
@Service
@Slf4j
class WatchlistService {

    /** Per-user watchlist size cap. Beyond this we reject new stars
     *  with a clear error so a buggy client can't fill the table. */
    static final int MAX_PER_USER = 500

    @Autowired WatchlistItemRepository repository
    @Autowired BanGuard banGuard
    /** Catalogue repo — used by list() to drop dangling references (starred
     *  ids whose Item no longer exists). Optional so unit specs that don't
     *  exercise the filter can leave it unset (null → no filtering). */
    @Autowired(required = false) com.sboxmarket.repository.ItemRepository catalogueRepository
    @Autowired(required = false) com.sboxmarket.repository.WatchlistAlertRepository alertRepository

    /** Star an item. Idempotent — if the user already has it, no-op +
     *  return false so the caller can short-circuit a redundant write.
     *
     *  `noRollbackFor = DataIntegrityViolationException` is load-bearing
     *  for the UNIQUE-constraint race recovery below. The try/catch
     *  swallows the dup at save() time, but Spring's @Transactional
     *  proxy still marks the tx rollback-only on any RuntimeException
     *  thrown from a JPA op — without this hint, the tx commits as
     *  rollback-only and Spring surfaces UnexpectedRollbackException to
     *  the caller as a 500 even though we caught the original violation.
     *  Same posture as UserBlockService.block. */
    @Transactional(noRollbackFor = org.springframework.dao.DataIntegrityViolationException)
    boolean add(Long userId, Long itemId) {
        if (userId == null || itemId == null) return false
        // Ban guard — a watchlist row is a state-changing write that wires
        // the user into the WatchlistAlertService price-drop fanout (bell +
        // email pings forever). A banned account must not be able to plant
        // new alerts; mirrors LoadoutService.create / SavedSearchService.upsert.
        banGuard.assertNotBanned(userId)
        if (repository.existsByUserAndItem(userId, itemId)) return false
        // Cap check happens AFTER the existence probe so re-saving an
        // already-starred id never trips the limit (idempotent semantics).
        def current = repository.findItemIdsByUser(userId)
        if (current.size() >= MAX_PER_USER) {
            throw new com.sboxmarket.exception.BadRequestException('WATCHLIST_FULL',
                "Watchlist is capped at ${MAX_PER_USER} items. Remove some before adding more.")
        }
        // existsByUserAndItem + save is a non-atomic read-modify-write.
        // The same user toggling the star from two devices (or
        // double-clicking the button) fires two concurrent requests that
        // both observe exists=false and both INSERT; the
        // `uq_watchlist_items_user_item` UNIQUE constraint (V30) then
        // rejects the loser with a DataIntegrityViolationException. Treat
        // that as a benign no-op — the row already exists from the
        // winning request, so idempotent semantics are preserved without
        // bubbling a 500 to the slower client. IDENTITY id generation
        // forces the INSERT at save() time, so the violation surfaces
        // synchronously where we can catch it (not at commit). Mirrors
        // LoadoutService.toggleFavorite and ReviewService.toggleHelpful.
        try {
            repository.save(new WatchlistItem(userId: userId, itemId: itemId))
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            log.debug("Watchlist toggle race for user=${userId} item=${itemId} — already starred, treating as no-op")
            return false
        }
        true
    }

    /** Remove a star. Idempotent — returns false if the row didn't
     *  exist. */
    @Transactional
    boolean remove(Long userId, Long itemId) {
        if (userId == null || itemId == null) return false
        boolean removed = repository.deleteByUserAndItem(userId, itemId) > 0
        // Same as clear(): an unstarred item's price alert kept pinging with
        // no watchlist card left to cancel it from.
        if (removed && alertRepository != null) alertRepository.cancelActiveForItems(userId, [itemId])
        removed
    }

    /** Clear every starred row for the user in one DELETE. Returns
     *  the count wiped. Idempotent — a zero-row user gets 0, never
     *  a 404. Used by the "Clear watchlist" button on the Watchlist
     *  page so heavy watchers can reset without clicking through
     *  N individual X-buttons. */
    @Transactional
    int clear(Long userId) {
        if (userId == null) return 0
        // "Clear all" also stops the price alerts on those items; left
        // ACTIVE they kept sending bells and emails with no card left on
        // the watchlist to cancel them from.
        if (alertRepository != null) {
            def starredIds = repository.findItemIdsByUser(userId) ?: []
            if (!starredIds.isEmpty()) alertRepository.cancelActiveForItems(userId, starredIds)
        }
        int n = repository.deleteByUser(userId)
        if (n > 0) log.info("Cleared ${n} watchlist item(s) for user ${userId}")
        n
    }

    /** Item ids the user has starred, oldest-first.
     *
     *  Dangling references are filtered out: a starred id whose catalogue
     *  Item no longer exists (deleted, or a stale/seed id) would otherwise
     *  inflate the nav badge (which counts watchlist.length) while the
     *  watchlist page silently drops it — the page can't render a card for
     *  a non-existent item — so the badge and the page disagree. This is
     *  the single source every list-returning endpoint funnels through
     *  (GET /api/watchlist, star, unstar, bulkMerge), so filtering here
     *  keeps badge == page everywhere. The createdAt-ASC order from the
     *  repo is preserved so the client's "Added" sort stays stable. The
     *  per-user cap check in add() reads the raw repository directly, so
     *  this view-level filter never lets dangling rows escape the cap. */
    List<Long> list(Long userId) {
        if (userId == null) return []
        def ids = repository.findItemIdsByUser(userId)
        if (ids.isEmpty() || catalogueRepository == null) return ids
        def existing = catalogueRepository.findAllById(ids).collect { it.id } as Set
        ids.findAll { existing.contains(it) }
    }

    /**
     * Merge a client-side list into the server set — used by the
     * one-shot localStorage migration on first sign-in after this
     * feature ships. Any id the user already has is skipped; duplicates
     * in the input are deduped. Capped per call to avoid a huge POST
     * burning the unique-constraint guard rail.
     *
     * Returns the post-merge full list so the client can replace its
     * cache in one swap.
     */
    @Transactional
    List<Long> bulkMerge(Long userId, List<Long> incoming) {
        if (userId == null) return []
        // Ban guard — bulkMerge inserts the same alert-wiring rows as add()
        // (just in batch from the first-sign-in localStorage migration).
        // Skipping the guard here would let a banned user re-seed an entire
        // watchlist via the migration endpoint and bypass the per-row check
        // on add(). Same rationale as add() above.
        banGuard.assertNotBanned(userId)
        def cleaned = (incoming ?: []).findAll { it != null }.unique()
        if (cleaned.size() > MAX_PER_USER) cleaned = cleaned.take(MAX_PER_USER)
        if (cleaned.isEmpty()) return list(userId)
        // Pre-filter against the rows the user already has. This MUST be
        // the only guard against the UNIQUE (userId, itemId) constraint:
        // a JPA constraint violation marks the whole transaction
        // rollback-only, so a swallowed try/catch around `save` would
        // still poison this @Transactional — every subsequent save and
        // the closing `list(userId)` would then throw
        // UnexpectedRollbackException and the entire first-sign-in merge
        // would fail. By only saving ids confirmed absent, no violation
        // can be raised in the first place, so no try/catch is needed.
        def existing = repository.findExistingItemIds(userId, cleaned).toSet()
        def toAdd = cleaned.findAll { !existing.contains(it) }
        // A guest's localStorage can still hold a since-deleted item id;
        // saving it would trip the items FK and fail the whole merge on
        // every sign-in. Keep only ids that are still in the catalogue.
        if (catalogueRepository != null && !toAdd.isEmpty()) {
            def live = catalogueRepository.findAllById(toAdd)*.id.toSet()
            toAdd = toAdd.findAll { live.contains(it) }
        }
        // Truncate at the per-user cap including pre-existing rows.
        def headroom = MAX_PER_USER - repository.countByUser(userId) as int
        if (headroom <= 0) return list(userId)
        toAdd = toAdd.take(headroom)
        def now = System.currentTimeMillis()
        toAdd.each { itemId ->
            repository.save(new WatchlistItem(userId: userId, itemId: itemId, createdAt: now))
        }
        list(userId)
    }
}
