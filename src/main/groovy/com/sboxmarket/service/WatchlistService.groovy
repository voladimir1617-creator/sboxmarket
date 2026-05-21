package com.sboxmarket.service

import com.sboxmarket.model.WatchlistItem
import com.sboxmarket.repository.WatchlistItemRepository
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

    /** Star an item. Idempotent — if the user already has it, no-op +
     *  return false so the caller can short-circuit a redundant write. */
    @Transactional
    boolean add(Long userId, Long itemId) {
        if (userId == null || itemId == null) return false
        if (repository.existsByUserAndItem(userId, itemId)) return false
        // Cap check happens AFTER the existence probe so re-saving an
        // already-starred id never trips the limit (idempotent semantics).
        def current = repository.findItemIdsByUser(userId)
        if (current.size() >= MAX_PER_USER) {
            throw new com.sboxmarket.exception.BadRequestException('WATCHLIST_FULL',
                "Watchlist is capped at ${MAX_PER_USER} items. Remove some before adding more.")
        }
        repository.save(new WatchlistItem(userId: userId, itemId: itemId))
        true
    }

    /** Remove a star. Idempotent — returns false if the row didn't
     *  exist. */
    @Transactional
    boolean remove(Long userId, Long itemId) {
        if (userId == null || itemId == null) return false
        repository.deleteByUserAndItem(userId, itemId) > 0
    }

    /** Clear every starred row for the user in one DELETE. Returns
     *  the count wiped. Idempotent — a zero-row user gets 0, never
     *  a 404. Used by the "Clear watchlist" button on the Watchlist
     *  page so heavy watchers can reset without clicking through
     *  N individual X-buttons. */
    @Transactional
    int clear(Long userId) {
        if (userId == null) return 0
        int n = repository.deleteByUser(userId)
        if (n > 0) log.info("Cleared ${n} watchlist item(s) for user ${userId}")
        n
    }

    /** Item ids the user has starred, oldest-first. */
    List<Long> list(Long userId) {
        if (userId == null) return []
        repository.findItemIdsByUser(userId)
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
