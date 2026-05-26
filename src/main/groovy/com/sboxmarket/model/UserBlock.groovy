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
 *
 * FK + ON DELETE CASCADE on both columns to steam_users(id), added in
 * V68. Mirrored on the entity via @JoinColumn(insertable=false,
 * updatable=false) ManyToOne stubs so Hibernate's create-drop schema
 * (used in the H2 test profile) reflects the same cascade behaviour
 * the Postgres migration enforces in prod. Without these stubs the
 * Hibernate-built H2 schema would silently lack the FK, hiding any
 * cascade-related regression from integration tests.
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

    // FK association stubs — read-only so the scalar id columns above
    // remain the source of truth for the service code (which deals in
    // raw Long ids, not entity refs). Hibernate sees these and emits
    // the foreign-key + ON DELETE CASCADE on its generated H2 schema,
    // matching what V68 enforces on Postgres.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = 'blocker_user_id', insertable = false, updatable = false,
                foreignKey = @ForeignKey(name = 'fk_user_blocks_blocker'))
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    SteamUser blockerRef

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = 'blocked_user_id', insertable = false, updatable = false,
                foreignKey = @ForeignKey(name = 'fk_user_blocks_blocked'))
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    SteamUser blockedRef
}
