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
     *  Drives the deposit-confirm poller. Oldest first.
     *
     *  IN_ESCROW_HOLD is in the candidate set, and that inclusion is
     *  load-bearing rather than tidy: a row sits in that state for the 7-to-15
     *  DAYS of a Steam mobile-authenticator hold, and the only event that ever
     *  ends the hold is the asset appearing in the bot's inventory. If the
     *  confirm poller stopped looking at held rows the moment they entered the
     *  hold, the item would land a week later with nothing watching for it —
     *  which is the orphaning this whole wave exists to prevent, just relocated
     *  one state to the right. */
    @Query("""
        SELECT e FROM EscrowedItem e
        WHERE e.custodyState IN ('PENDING_DEPOSIT', 'IN_ESCROW_HOLD')
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
     *  drains the most-overdue rows first.
     *
     *  ── Scoped to deposits the seller NEVER ACCEPTED ──────────────────────
     *  {@code depositAcceptedAt IS NULL AND escrowEndsAt IS NULL} is the entire
     *  safety of this query, and it is the difference between a timeout that
     *  costs a listing and one that costs an ITEM.
     *
     *  Giving up on a deposit is only harmless while the seller still holds the
     *  item: custody FAILED plus listing CANCELLED then genuinely undoes the
     *  listing and the seller has lost nothing but time. The instant the offer
     *  is accepted, the asset leaves their inventory and the platform's row is
     *  the only thing that knows where it went — so marking that row FAILED
     *  does not undo anything, it just deletes the pointer. That is exactly how
     *  a real item ends up sitting in the bot's inventory referenced by nothing.
     *
     *  A Steam mobile-authenticator hold makes this the NORMAL path, not an
     *  exotic one: the hold runs 7 to 15 days, so an accepted deposit is
     *  routinely 24h old with a fortnight still to run. Those rows belong to
     *  {@link #findOverdueEscrowHolds}, which escalates and never fails them. */
    @Query("""
        SELECT e FROM EscrowedItem e
        WHERE e.custodyState        = 'PENDING_DEPOSIT'
          AND e.depositAcceptedAt  IS NULL
          AND e.escrowEndsAt       IS NULL
          AND e.createdAt           < :cutoff
        ORDER BY e.createdAt ASC
    """)
    List<EscrowedItem> findStalePendingDeposits(@Param('cutoff') Long cutoff, Pageable page)

    /**
     * Deposits sitting inside a Steam trade hold whose hold has RUN OVER — the
     * release time (plus a grace window) has passed and the asset still has not
     * appeared in the bot's inventory.
     *
     * This is the escalation queue, NOT a give-up queue, and the distinction is
     * the whole point. Every row here has {@code depositAcceptedAt} set, which
     * means the item has already left the seller's inventory. There is no state
     * this sweep could move the row to that would improve the seller's
     * position, and exactly one that would destroy it (FAILED, which is how the
     * pointer to a real item gets lost). So the sweep logs loudly, tells the
     * seller the truth, and leaves the row exactly where it is — the same
     * no-give-up posture as {@link #findPendingReturns}, chosen for the same
     * reason: the thing at stake is a real user's real item and the platform is
     * the party holding it.
     *
     * {@code escrowEndsAt IS NULL} is included because a hold we never managed
     * to read is still a hold — an accepted deposit whose asset never arrived
     * is overdue on the strength of {@code updatedAt} alone, and omitting those
     * rows would make an unreadable hold the quietest failure of the lot.
     */
    @Query("""
        SELECT e FROM EscrowedItem e
        WHERE e.custodyState = 'IN_ESCROW_HOLD'
          AND (e.escrowEndsAt IS NULL OR e.escrowEndsAt < :releasedBefore)
          AND e.updatedAt < :backoffCutoff
        ORDER BY e.updatedAt ASC
    """)
    List<EscrowedItem> findOverdueEscrowHolds(@Param('releasedBefore') Long releasedBefore,
                                              @Param('backoffCutoff') Long backoffCutoff,
                                              Pageable page)

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
    @org.springframework.transaction.annotation.Transactional
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
    @org.springframework.transaction.annotation.Transactional
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
    @org.springframework.transaction.annotation.Transactional
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

    /**
     * Compare-and-swap claim for one overdue-hold ESCALATION, keyed on
     * {@code updatedAt}. Returns 1 when this pod owns the escalation, 0 when it
     * lost the race.
     *
     * Note what this claim does NOT do: it does not change custodyState. The
     * row stays IN_ESCROW_HOLD because that remains the true description of
     * where the item is, and because there is no better state to move it to —
     * every alternative either lies about custody (IN_CUSTODY, which would
     * authorise the delivery leg to send an asset the bot cannot touch) or
     * throws the pointer away (FAILED). The claim exists purely so two pods
     * cannot both fire the seller notification and so the ERROR line is rate-
     * limited to one per backoff window instead of one per tick, forever.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
        UPDATE EscrowedItem e
           SET e.lastError  = :reason,
               e.updatedAt  = :now
         WHERE e.id                = :id
           AND e.custodyState      = 'IN_ESCROW_HOLD'
           AND e.updatedAt         = :expectedUpdatedAt
    """)
    int claimOverdueHoldEscalation(@Param('id') Long id,
                                   @Param('reason') String reason,
                                   @Param('expectedUpdatedAt') Long expectedUpdatedAt,
                                   @Param('now') Long now)

    /** Custody rows for a set of listings regardless of state — powers the
     *  seller-facing held-listing view, which has to explain a PENDING_ESCROW
     *  listing whose custody row may be PENDING_DEPOSIT (waiting on the seller),
     *  IN_ESCROW_HOLD (waiting on Steam) or FAILED. One query for the whole
     *  page rather than one per listing. */
    @Query("""
        SELECT e FROM EscrowedItem e
        WHERE e.listingId IN :listingIds
    """)
    List<EscrowedItem> findByListingIds(@Param('listingIds') Collection<Long> listingIds)
}
