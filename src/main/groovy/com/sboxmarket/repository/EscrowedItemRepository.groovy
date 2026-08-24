package com.sboxmarket.repository

import com.sboxmarket.model.EscrowedItem
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Persistence for {@link EscrowedItem} — the custody store for bot-escrowed
 * Steam assets. Drives the deposit/confirm/deliver/return state machine in
 * {@link com.sboxmarket.service.SteamEscrowService} and the delivery asset
 * resolution in {@link com.sboxmarket.service.SteamDeliveryService}.
 */
@Repository
interface EscrowedItemRepository extends JpaRepository<EscrowedItem, Long> {

    /** The custody row for a listing, if one exists. One row per listing. */
    EscrowedItem findByListingId(Long listingId)

    /** All deposits still awaiting confirmation (bot requested the asset, the
     *  seller hasn't accepted yet / we haven't seen it in the bot inventory).
     *  Drives the deposit-confirm poller. Oldest first. */
    @Query("""
        SELECT e FROM EscrowedItem e
        WHERE e.custodyState = 'PENDING_DEPOSIT'
          AND e.depositOfferId IS NOT NULL
        ORDER BY e.updatedAt ASC
    """)
    List<EscrowedItem> findPendingDeposits(Pageable page)

    /** Custody rows that are confirmed IN_CUSTODY for a set of listings —
     *  used to mark those listings buyable. */
    @Query("""
        SELECT e FROM EscrowedItem e
        WHERE e.listingId IN :listingIds
          AND e.custodyState = 'IN_CUSTODY'
    """)
    List<EscrowedItem> findInCustodyForListings(@Param('listingIds') Collection<Long> listingIds)

    /** Stuck deposits the seller never completed — custody still
     *  PENDING_DEPOSIT well past the deposit-timeout window. Catches BOTH
     *  failure shapes the confirm-poller can't resolve on its own:
     *    - the seller never accepted the bot's deposit offer (offer sits
     *      `active` forever, so `confirmDeposit` keeps waiting), AND
     *    - the offer never got created at all (transient bot/transport
     *      failure left {@code depositOfferId} null, so `findPendingDeposits`
     *      — which requires a non-null offer id — never even returns the row).
     *  The timeout sweeper uses this; `createdAt` is the age anchor (the
     *  deposit was requested at row creation), oldest first so the batch cap
     *  drains the most-overdue rows first. */
    @Query("""
        SELECT e FROM EscrowedItem e
        WHERE e.custodyState = 'PENDING_DEPOSIT'
          AND e.createdAt < :cutoff
        ORDER BY e.createdAt ASC
    """)
    List<EscrowedItem> findStalePendingDeposits(@Param('cutoff') Long cutoff, Pageable page)

    /**
     * Atomic claim for the stale-deposit TIMEOUT sweep — flips custody
     * PENDING_DEPOSIT→FAILED and stamps the timeout reason ONLY if the row is
     * still PENDING_DEPOSIT at UPDATE time. Returns the number of rows touched:
     * 1 = this pod owns the fan-out (revert the listing + notify the seller),
     * 0 = a sibling pod already claimed it OR the deposit-confirm poller landed
     * concurrently and promoted the row to IN_CUSTODY (the seller accepted at
     * the last second) — in either case the losing path bails before reverting
     * the listing or notifying.
     *
     * Multi-pod / out-of-band race protection. Same conditional-UPDATE shape as
     * {@link com.sboxmarket.repository.ListingRepository#claimEndingSoonNotify}
     * (wave 124) and {@link com.sboxmarket.repository.TransactionRepository#claimExpirePending}
     * (wave 126): {@code findStalePendingDeposits} is read concurrently by every
     * pod's {@code SteamEscrowService.sweepStalePendingDeposits}, and both pods
     * would see the SAME PENDING_DEPOSIT row. Without an atomic claim each pod
     * independently reverts the listing AND fires the seller notification before
     * either commits — the seller gets "your deposit timed out" twice for one
     * stuck deposit. Critically it also closes the confirm-poller race: if the
     * seller accepts the deposit between the sweeper's read and this UPDATE,
     * {@code confirmDeposit} flips the row to IN_CUSTODY and this UPDATE MISSES
     * it (custodyState != PENDING_DEPOSIT) — so a just-deposited item is NOT
     * wrongly failed and its now-ACTIVE listing is NOT clawed back to CANCELLED.
     */
    @Modifying
    @Query("""
        UPDATE EscrowedItem e
           SET e.custodyState = 'FAILED',
               e.lastError    = :reason,
               e.updatedAt    = :now
         WHERE e.id           = :id
           AND e.custodyState = 'PENDING_DEPOSIT'
    """)
    int claimTimeoutPendingDeposit(@Param('id') Long id,
                                   @Param('reason') String reason,
                                   @Param('now') Long now)

    /**
     * Items the bot still holds that SOMEBODY ASKED FOR BACK and did not get —
     * the retry queue for a failed return.
     *
     * {@code returnRequestedAt IS NOT NULL} is the entire safety of this query.
     * IN_CUSTODY is the normal, healthy state of every live for-sale listing on
     * the marketplace, so a sweep over IN_CUSTODY alone would re-send every
     * seller's item out from under their own active listing. Only a row whose
     * return was explicitly requested — listing cancelled, auction expired
     * unsold, trade cancelled — is ever a candidate, which means the candidate
     * set is empty on a healthy marketplace and can never contain a for-sale
     * item.
     *
     * A successful return flips custody to RETURNED, so a row leaves this set
     * the moment the retry lands. {@code updatedAt < :cutoff} spaces the
     * attempts out: every attempt touches updatedAt, so a row that just failed
     * is not retried again on the very next tick and the bot is not hammered
     * while it is rate-limiting us. Oldest first so the longest-stranded item
     * is served first under the batch cap.
     */
    @Query("""
        SELECT e FROM EscrowedItem e
        WHERE e.custodyState       = 'IN_CUSTODY'
          AND e.returnRequestedAt IS NOT NULL
          AND e.updatedAt          < :cutoff
        ORDER BY e.updatedAt ASC
    """)
    List<EscrowedItem> findPendingReturns(@Param('cutoff') Long cutoff, Pageable page)

    /**
     * Compare-and-swap claim for one return retry. Bumps the attempt counter
     * ONLY if the row is still IN_CUSTODY, still has a return outstanding, and
     * the counter still reads {@code expectedAttempts}. Returns 1 when this pod
     * owns the attempt, 0 when it lost the race.
     *
     * Why a CAS on the counter rather than a state flip like
     * {@link #claimTimeoutPendingDeposit}: a row stays IN_CUSTODY across a
     * retry — that is the whole point, the bot really does still hold the item
     * — so there is no state transition for two pods to race on. The counter is
     * the only thing that changes, so the counter is what they race on.
     *
     * Losing the claim matters: the side effect being guarded is a real Steam
     * trade offer. Two pods both sending a return offer for the same asset
     * produces a second offer for an item already committed to the first, which
     * Steam rejects noisily, and — if the seller happens to accept the first
     * between the two sends — an offer for an asset the bot no longer owns.
     */
    @Modifying
    @Query("""
        UPDATE EscrowedItem e
           SET e.returnAttempts    = :expectedAttempts + 1,
               e.updatedAt         = :now
         WHERE e.id                = :id
           AND e.custodyState      = 'IN_CUSTODY'
           AND e.returnRequestedAt IS NOT NULL
           AND e.returnAttempts    = :expectedAttempts
    """)
    int claimReturnRetry(@Param('id') Long id,
                         @Param('expectedAttempts') Integer expectedAttempts,
                         @Param('now') Long now)

    /**
     * Deposits whose trade offer NEVER GOT CREATED — the gap between the
     * confirm poller and the timeout sweeper.
     *
     * {@link #findPendingDeposits} requires {@code depositOfferId IS NOT NULL},
     * so it structurally cannot see these rows; nothing else looked for them
     * either. They arise from two ordinary situations:
     *   - the bot call at list time failed transiently (RATE_LIMITED, NOT_READY
     *     while the sidecar reconnects, TRANSPORT_ERROR), or
     *   - the seller had no Steam trade URL, so there was no address to send a
     *     deposit request to.
     * In both cases the row was parked in PENDING_DEPOSIT and the listing in
     * PENDING_ESCROW, no poller ever looked at it again, and the ONLY thing
     * that eventually happened was the 24h timeout sweeper cancelling the
     * listing. A seller who fixed the actual problem thirty seconds later —
     * pasted their trade URL — still lost the listing, because nothing was
     * watching for the fix.
     *
     * This is the re-request queue. Oldest first; {@code updatedAt < :cutoff}
     * spaces the retries so a persistently-down bot is not hammered.
     */
    @Query("""
        SELECT e FROM EscrowedItem e
        WHERE e.custodyState    = 'PENDING_DEPOSIT'
          AND e.depositOfferId IS NULL
          AND e.updatedAt       < :cutoff
        ORDER BY e.updatedAt ASC
    """)
    List<EscrowedItem> findUnsentDeposits(@Param('cutoff') Long cutoff, Pageable page)

    /**
     * Compare-and-swap claim for one deposit re-request, keyed on
     * {@code updatedAt}. Returns 1 when this pod owns the attempt, 0 when it
     * lost the race.
     *
     * {@code updatedAt} is the CAS token because — as with the return retry —
     * the row does not change STATE across a re-request; it stays
     * PENDING_DEPOSIT either way. The {@code depositOfferId IS NULL} clause
     * additionally makes the claim idempotent against a sibling pod that
     * already succeeded: once an offer id lands, no further claim can match.
     *
     * The side effect being guarded is a real Steam trade offer arriving in a
     * seller's client. Two pods racing would send one seller two identical
     * deposit requests for a single listing; only one can ever be accepted, so
     * the other becomes garbage the seller has to work out how to dismiss.
     */
    @Modifying
    @Query("""
        UPDATE EscrowedItem e
           SET e.updatedAt         = :now
         WHERE e.id                = :id
           AND e.custodyState      = 'PENDING_DEPOSIT'
           AND e.depositOfferId   IS NULL
           AND e.updatedAt         = :expectedUpdatedAt
    """)
    int claimDepositRetry(@Param('id') Long id,
                          @Param('expectedUpdatedAt') Long expectedUpdatedAt,
                          @Param('now') Long now)
}
