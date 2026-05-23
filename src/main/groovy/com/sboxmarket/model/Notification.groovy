package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Server-persisted user notifications — replaces the old localStorage-only bell.
 * Events:
 *   TRADE_VERIFIED, AUCTION_ENDING, AUCTION_WON, AUCTION_LOST,
 *   ITEM_PURCHASED, OFFER_RECEIVED, OFFER_ACCEPTED, OFFER_REJECTED,
 *   BUY_ORDER_FILLED, DEPOSIT_COMPLETE, PRICE_DROPPED, WITHDRAWAL_COMPLETE
 */
@Entity
@Table(name = "notifications", indexes = [
    // `(user_id, created_at)` already exists via V1__baseline; the two
    // partial-ish indexes below close the gaps for the read-state hot
    // paths the baseline didn't cover.
    //
    // idx_notifications_user_unread → speeds up `countUnread` (bell
    // badge poll on every page render) and `markAllReadForUser`
    // (bulk-flip path); both filter on `user_id AND read = false` and
    // currently fall back to the wider (user_id, created_at) scan
    // followed by an in-memory `read = false` filter.
    //
    // idx_notifications_read_created → speeds up the daily retention
    // sweep `deleteReadOlderThan` which scans `read = true AND
    // created_at < cutoff`. Without it the sweep does a full-table scan
    // on a growing notifications table (1+ row per user per event,
    // unbounded across years of history).
    @Index(name = "idx_notifications_user_unread",  columnList = "user_id,read"),
    @Index(name = "idx_notifications_read_created", columnList = "read,created_at")
])
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false)
    Long userId

    @Column(nullable = false)
    String kind

    @Column(nullable = false, length = 200)
    String title

    @Column(length = 500)
    String body

    /** Optional id of the related entity (listing id, offer id, transaction id, etc). */
    @Column
    Long refId

    /** Optional in-app route the notification drills down to when clicked
     *  (e.g. "/item/42", "/profile", "/buy-orders"). Stored at push time so
     *  the frontend doesn't have to re-derive the target from (kind, refId). */
    @Column(length = 160)
    String path

    @Column(nullable = false)
    Boolean read = false

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
