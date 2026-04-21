package com.sboxmarket.repository

import com.sboxmarket.model.TradeMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TradeMessageRepository extends JpaRepository<TradeMessage, Long> {

    /** Full thread, oldest-first — standard chat ordering. */
    @Query("SELECT m FROM TradeMessage m WHERE m.tradeId = :tid ORDER BY m.createdAt ASC")
    List<TradeMessage> findByTrade(@Param("tid") Long tradeId)

    /** Paged newest-first — batch 1028 caps the trade-chat fetch at
     *  200 rows on every open so a thread with thousands of messages
     *  doesn't ship them all. Ordered DESC so the page returns the
     *  MOST-RECENT 200 messages; the caller reverses to ASC for chat
     *  display. Backed by the `(trade_id, created_at)` composite index. */
    @Query("SELECT m FROM TradeMessage m WHERE m.tradeId = :tid ORDER BY m.createdAt DESC")
    List<TradeMessage> findByTradeRecent(@Param("tid") Long tradeId,
                                          org.springframework.data.domain.Pageable pageable)

    /** Count of messages in a trade thread — feeds the X-Total-Count
     *  header on /api/trades/{id}/messages so the UI can render
     *  "Showing last 200 of N" when a chat crosses the cap. */
    @Query("SELECT COUNT(m) FROM TradeMessage m WHERE m.tradeId = :tid")
    long countByTrade(@Param("tid") Long tradeId)

    /** Rate-limit support: how many messages has this sender posted since
     *  cutoff. Backed by idx_trade_messages_sender_created. */
    long countBySenderUserIdAndCreatedAtGreaterThan(Long senderUserId, Long since)

    /**
     * Bulk-mark every unread message in a trade thread that was sent by
     * someone OTHER than the viewing user. Drives the "✓✓ read"
     * indicator on the sender's side (V37 / batch 280). Returns the
     * number of rows updated so the service can decide whether to
     * re-fetch the thread (it always does — cheap).
     */
    @Modifying
    @Query("""
        UPDATE TradeMessage m
        SET    m.readAt = :now
        WHERE  m.tradeId = :tid
          AND  m.senderUserId <> :viewerId
          AND  m.readAt IS NULL
    """)
    int markIncomingRead(@Param('tid') Long tradeId,
                         @Param('viewerId') Long viewerUserId,
                         @Param('now') Long now)

    /**
     * Bulk unread-count per trade for a single viewer — drives the
     * "💬 N new" chip on the Profile → Trades list (batch 281). One
     * query, one map back. Empty `tradeIds` is handled by the caller
     * since `IN ()` is illegal SQL. Served by the V37 partial index.
     */
    @Query("""
        SELECT m.tradeId, COUNT(m) FROM TradeMessage m
        WHERE m.tradeId IN :tradeIds
          AND m.senderUserId <> :viewerId
          AND m.readAt IS NULL
        GROUP BY m.tradeId
    """)
    List<Object[]> countUnreadBulk(@Param('tradeIds') List<Long> tradeIds,
                                   @Param('viewerId') Long viewerUserId)

    /**
     * Total unread chat messages addressed to a user across every trade
     * they participate in. Drives the nav-avatar pending-actions badge
     * so an unread message from a counterparty bumps the same red dot
     * as a pending trade or offer (batch 282).
     *
     * Joins through Trade so the count is restricted to threads the
     * user is actually allowed to read — defense in depth on top of
     * the controller's session check.
     */
    @Query("""
        SELECT COUNT(m) FROM TradeMessage m, Trade t
        WHERE m.tradeId = t.id
          AND (t.buyerUserId = :uid OR t.sellerUserId = :uid)
          AND m.senderUserId <> :uid
          AND m.readAt IS NULL
    """)
    long countUnreadForUser(@Param('uid') Long userId)

    /**
     * The newest message per trade for the given trade ids — drives
     * the inline last-message preview on Profile → Trades rows
     * (batch 283). Returns at most one row per trade. Empty input is
     * the caller's responsibility.
     *
     * Implementation note: SQL nested aggregate via a sub-query
     * (`createdAt = MAX(...)`) — Postgres handles this in one pass
     * with the per-trade index; keeps the result small (≤ N rows for
     * N trades).
     */
    @Query("""
        SELECT m FROM TradeMessage m
        WHERE m.tradeId IN :tradeIds
          AND m.createdAt = (
              SELECT MAX(m2.createdAt) FROM TradeMessage m2
              WHERE m2.tradeId = m.tradeId
          )
    """)
    List<TradeMessage> findNewestPerTrade(@Param('tradeIds') List<Long> tradeIds)
}
