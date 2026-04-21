package com.sboxmarket.model

import jakarta.persistence.*

/**
 * One row per (blocker, blocked) pair. A user who blocks another user
 * won't see their listings in the grid, won't accept offers or chat
 * messages from them, and won't get NEW_LISTING_FROM_SELLER pings if
 * they had followed them before the block.
 *
 * Staff see the unfiltered view — blocks are a personal preference, not
 * a moderation primitive. Blocking is silent — no notification to the
 * blocked user, no audit trail. Reversible at any time from the
 * blocker's Profile → Blocked list.
 *
 * Unique constraint on the (blocker_user_id, blocked_user_id) pair
 * (V40 migration) — re-blocking the same user is a no-op, not an error.
 */
@Entity
@Table(name = "user_blocks", indexes = [
    @Index(name = "idx_user_blocks_blocker", columnList = "blocker_user_id"),
    @Index(name = "idx_user_blocks_blocked", columnList = "blocked_user_id")
])
class UserBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = 'blocker_user_id', nullable = false)
    Long blockerUserId

    @Column(name = 'blocked_user_id', nullable = false)
    Long blockedUserId

    @Column(name = 'created_at', nullable = false)
    Long createdAt = System.currentTimeMillis()
}
