package com.sboxmarket.repository

import com.sboxmarket.model.SteamDeliveryAttempt
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Persistence for {@link SteamDeliveryAttempt}. Doubles as the authoritative
 * lookup of "the live Steam offer + last-seen state for trade N" so the
 * delivery orchestrator never needs a new column on the Trade entity.
 */
@Repository
interface SteamDeliveryAttemptRepository extends JpaRepository<SteamDeliveryAttempt, Long> {

    /** All attempts for a trade, newest first. */
    @Query("SELECT a FROM SteamDeliveryAttempt a WHERE a.tradeId = :tradeId ORDER BY a.createdAt DESC")
    List<SteamDeliveryAttempt> findByTrade(@Param('tradeId') Long tradeId, Pageable pageable)

    /** Most-recent attempt that actually carries a Steam offer id for a trade —
     *  i.e. the current/last offer the bot sent. Returns an empty list when the
     *  bot has never successfully sent an offer for this trade yet. */
    @Query("""
        SELECT a FROM SteamDeliveryAttempt a
        WHERE a.tradeId = :tradeId
          AND a.steamOfferId IS NOT NULL
        ORDER BY a.createdAt DESC
    """)
    List<SteamDeliveryAttempt> findLatestWithOffer(@Param('tradeId') Long tradeId, Pageable pageable)

    List<SteamDeliveryAttempt> findBySteamOfferId(String steamOfferId)
}
