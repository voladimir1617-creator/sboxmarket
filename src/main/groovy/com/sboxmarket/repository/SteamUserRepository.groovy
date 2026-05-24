package com.sboxmarket.repository

import com.sboxmarket.model.SteamUser
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SteamUserRepository extends JpaRepository<SteamUser, Long> {

    SteamUser findBySteamId64(String steamId64)

    /** Email-uniqueness probe (batch 477). Used by ProfileController.setEmail
     *  to refuse multi-account email collisions. Case-insensitive — the
     *  controller lowercases on write but legacy rows might still have
     *  mixed case. Returns NULL when the email is free. */
    @Query("SELECT u FROM SteamUser u WHERE LOWER(u.email) = LOWER(:email)")
    List<SteamUser> findByEmailIgnoreCase(@Param('email') String email)

    /** Trade-URL partner-id collision probe (batch 478). The `partner=`
     *  query param inside a Steam trade URL derives from the account's
     *  Steam ID32 — two SkinBox accounts with the same partner id
     *  almost certainly represent the same human (account-stuffing).
     *  Case-insensitive LIKE on the partner fragment so minor URL
     *  variations (http vs https, trailing slash) still match. */
    @Query("SELECT u FROM SteamUser u WHERE u.tradeUrl LIKE CONCAT('%partner=', :partnerId, '%') ESCAPE '\\'")
    List<SteamUser> findByTradeUrlPartnerId(@Param('partnerId') String partnerId)

    /**
     * Case-insensitive search across display name, Steam ID64, AND email.
     * Email is added because support tickets usually reference a user's
     * email, not their Steam display name — without email search, an admin
     * triaging a "can't deposit" ticket had to ask the user for their
     * Steam ID first. Backed by the `idx_steam_users_display_lower` index
     * landed in V9; email is a minority match so the O(N) scan on that
     * column is acceptable for admin-only traffic.
     */
    @Query("""
        SELECT u FROM SteamUser u
        WHERE LOWER(u.displayName) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'
           OR u.steamId64 LIKE CONCAT('%', :q, '%') ESCAPE '\\'
           OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'
        ORDER BY u.createdAt DESC
    """)
    List<SteamUser> searchByNameOrSteamId(@Param('q') String query, Pageable page)

    /** Batch 765 — admin-only list filtered by role + banned state.
     *  `role = 'ANY'` skips the role predicate; `banned = null` skips
     *  the banned predicate. Used by the Admin Users panel to surface
     *  "all current admins" or "all suspended users" with one click,
     *  without scrolling through hundreds of normal users. */
    @Query("""
        SELECT u FROM SteamUser u
        WHERE (:role = 'ANY' OR u.role = :role)
          AND (:banned IS NULL OR u.banned = :banned)
        ORDER BY u.createdAt DESC
    """)
    List<SteamUser> listByRoleAndBanned(@Param('role') String role,
                                        @Param('banned') Boolean banned,
                                        Pageable page)

    /**
     * Public seller search (batch 666). Case-insensitive displayName LIKE
     * filtered to users who have at least one listing posted, excluding
     * banned accounts. Returns `[id, displayName, avatarUrl]` projection
     * rows so the controller can attach per-seller sold + active counts
     * in a single bulk follow-up (avoids the per-row correlated subquery
     * that PostgreSQL's planner handles poorly at scale).
     *
     * EXISTS gate on Listing is intentional — without it, this endpoint
     * becomes a "find any SkinBox user" enumerator rather than a seller
     * discovery surface. A dormant account with no listings is not a
     * public seller and should not be surfaced by name.
     */
    @Query("""
        SELECT u.id, u.displayName, u.avatarUrl FROM SteamUser u
        WHERE LOWER(u.displayName) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'
          AND (u.banned IS NULL OR u.banned = false)
          AND EXISTS (SELECT 1 FROM Listing l WHERE l.sellerUserId = u.id)
        ORDER BY u.displayName ASC
    """)
    List<Object[]> searchPublicSellers(@Param('q') String query, Pageable page)

    /** Banned user list for the admin panel — uses the partial index from V9. */
    @Query("SELECT u FROM SteamUser u WHERE u.banned = true ORDER BY u.id DESC")
    List<SteamUser> findBanned()

    /** Count by role for CSR/admin dashboards — uses idx_steam_users_role. */
    @Query("SELECT COUNT(u) FROM SteamUser u WHERE u.role = :role")
    long countByRole(@Param('role') String role)

    /** List users by role — uses idx_steam_users_role so it scales.
     *  Replaces the `findAll().findAll { role == 'ADMIN' }` full-table
     *  scan that chargeback fan-out used to fire (batch 483). */
    @Query("SELECT u FROM SteamUser u WHERE u.role = :role ORDER BY u.id ASC")
    List<SteamUser> findByRole(@Param('role') String role)

    /** Count of banned users for the admin dashboard — uses the partial
     *  index `idx_steam_users_banned` landed in V9 so it stays O(K) where
     *  K is the number of banned rows, not the full table size. */
    @Query("SELECT COUNT(u) FROM SteamUser u WHERE u.banned = true")
    long countBanned()

    /** Count of accounts created since a timestamp — drives the admin
     *  dashboard "New users 24h" stat. */
    @Query("SELECT COUNT(u) FROM SteamUser u WHERE u.createdAt >= :since")
    long countCreatedSince(@Param('since') Long since)

    /** Background Steam sync candidates — users who have either never
     *  been synced or whose last sync is older than the cutoff.
     *  `SteamSyncService.syncAllUsers` used to iterate `findAll()` every
     *  20-minute tick, which scales badly. Now it polls this query with
     *  a hard batch cap so a million-user table stays within one tick's
     *  time budget (bug #60). */
    @Query("""
        SELECT u FROM SteamUser u
        WHERE u.lastSyncedAt IS NULL OR u.lastSyncedAt < :cutoff
        ORDER BY u.lastSyncedAt ASC NULLS FIRST
    """)
    List<SteamUser> findStaleForSync(@Param("cutoff") Long cutoff, Pageable page)

    /** Users who have self-service-requested deletion and are awaiting
     *  staff review. Oldest-first so the admin queue triages the tail.
     *  Uses the partial index landed in V21. */
    @Query("""
        SELECT u FROM SteamUser u
        WHERE u.deletionRequestedAt IS NOT NULL
        ORDER BY u.deletionRequestedAt ASC
    """)
    List<SteamUser> findDeletionRequested()

    /** Sellers whose scheduled vacation-mode return time has passed.
     *  Drives the hourly `ListingService.sweepExpiredAwayMode` job;
     *  served from the partial index landed in V33 so the cost stays
     *  proportional to the number of users currently on vacation, not
     *  the full table. */
    @Query("""
        SELECT u FROM SteamUser u
        WHERE u.awayModeUntil IS NOT NULL
          AND u.awayModeUntil <= :now
    """)
    List<SteamUser> findExpiredAwayMode(@Param('now') Long now)

    /** Broadcast-notification target list (batch 566). Paginated `id ASC`
     *  scan over non-banned, non-deletion-requested users. The admin
     *  broadcast tool iterates pages and pushes one notification row
     *  per user per page, keeping each tx small enough to not swamp the
     *  DB for sites with 100k+ accounts. Banned / deletion-pending
     *  users are excluded so the broadcast doesn't flood accounts that
     *  won't / can't act on it. */
    @Query("""
        SELECT u.id FROM SteamUser u
        WHERE (u.banned IS NULL OR u.banned = false)
          AND u.deletionRequestedAt IS NULL
        ORDER BY u.id ASC
    """)
    List<Long> findActiveUserIds(Pageable page)

    /** Bulk presence lookup (V61). Returns `[userId, lastSeenAt]` pairs
     *  for the given ids — drives `Listing.sellerLastSeenAt` decoration
     *  on every list endpoint without per-row hits. Empty input → empty
     *  output (caller short-circuits). Indexed by PK. */
    @Query("SELECT u.id, u.lastSeenAt FROM SteamUser u WHERE u.id IN :ids")
    List<Object[]> findLastSeenAtByIds(@Param('ids') Collection<Long> ids)

    /** Single-user lastSeenAt persist (PresenceFilter throttle path).
     *  Bulk UPDATE so we don't load the full SteamUser row just to
     *  bump one timestamp on every authenticated request. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE SteamUser u SET u.lastSeenAt = :ts WHERE u.id = :id")
    int updateLastSeenAt(@Param('id') Long id, @Param('ts') Long timestamp)
}
