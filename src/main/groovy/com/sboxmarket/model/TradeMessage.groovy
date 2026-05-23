package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Message exchanged between trade participants during escrow. The buyer
 * and seller on the parent trade can both post and read; staff (admin
 * + CSR) can also read the thread for dispute triage and forensic
 * review — without that, a disputed trade lands in the queue with the
 * counterparty chat invisible to the very people who have to settle
 * it. Staff cannot POST (they have their own support-ticket channel)
 * and their reads do NOT mark unread messages as read so the
 * sender-side ✓✓ indicator still reflects the actual participant's
 * read state. Text is stored plain (sanitised at the service boundary
 * so HTML/script can't be persisted). 2000-char cap at the column
 * and DTO level.
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

    /** Set the first time the OTHER trade participant pulls this row
     *  via `TradeService.listMessages`. Drives the "✓✓ read" indicator
     *  on own messages so a sender can tell when the counterparty has
     *  at least loaded the thread (V37 / batch 280). Null = unread. */
    @Column(name = "read_at")
    Long readAt

    /** Timestamp staff soft-redacted this message (V41 / batch 349).
     *  When non-null, `body` is cleared and consumers render a "Message
     *  removed by moderators" placeholder in place of the original
     *  text. Preserves chat continuity vs the previous hard-delete. */
    @Column(name = "redacted_at")
    Long redactedAt
}
