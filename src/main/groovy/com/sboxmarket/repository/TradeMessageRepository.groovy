package com.sboxmarket.repository

import com.sboxmarket.model.TradeMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TradeMessageRepository extends JpaRepository<TradeMessage, Long> {

    /** Full thread, oldest-first — standard chat ordering. */
    @Query("SELECT m FROM TradeMessage m WHERE m.tradeId = :tid ORDER BY m.createdAt ASC")
    List<TradeMessage> findByTrade(@Param("tid") Long tradeId)

    /** Rate-limit support: how many messages has this sender posted since
     *  cutoff. Backed by idx_trade_messages_sender_created. */
    long countBySenderUserIdAndCreatedAtGreaterThan(Long senderUserId, Long since)
}
