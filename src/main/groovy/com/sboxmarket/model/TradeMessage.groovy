package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Message exchanged between trade participants during escrow. Only the
 * buyer + seller on the parent trade can post or read. Text is stored
 * plain (sanitised at the service boundary so HTML/script can't be
 * persisted). 2000-char cap at the column and DTO level.
 */
@Entity
@Table(name = "trade_messages")
class TradeMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = "trade_id", nullable = false)
    Long tradeId

    @Column(name = "sender_user_id", nullable = false)
    Long senderUserId

    @Column(nullable = false, length = 2000)
    String body

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
