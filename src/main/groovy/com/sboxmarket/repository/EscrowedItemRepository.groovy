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
}
