package com.sboxmarket.repository

import com.sboxmarket.model.EscrowedItem
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
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
}
