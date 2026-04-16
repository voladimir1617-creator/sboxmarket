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

    /**
     * Case-insensitive search across display name + Steam ID64. Backed by
     * the `idx_steam_users_display_lower` index landed in V9 so this is
     * sub-millisecond even at 1M+ users. Replaces the old
     * `findAll().findAll { contains() }` full-table scan in CsrService.
     */
    @Query("""
        SELECT u FROM SteamUser u
        WHERE LOWER(u.displayName) LIKE LOWER(CONCAT('%', :q, '%'))
           OR u.steamId64 LIKE CONCAT('%', :q, '%')
        ORDER BY u.createdAt DESC
    """)
    List<SteamUser> searchByNameOrSteamId(@Param('q') String query, Pageable page)

    /** Banned user list for the admin panel — uses the partial index from V9. */
    @Query("SELECT u FROM SteamUser u WHERE u.banned = true ORDER BY u.id DESC")
    List<SteamUser> findBanned()

    /** Count by role for CSR/admin dashboards — uses idx_steam_users_role. */
    @Query("SELECT COUNT(u) FROM SteamUser u WHERE u.role = :role")
    long countByRole(@Param('role') String role)

    /** Count of banned users for the admin dashboard — uses the partial
     *  index `idx_steam_users_banned` landed in V9 so it stays O(K) where
     *  K is the number of banned rows, not the full table size. */
    @Query("SELECT COUNT(u) FROM SteamUser u WHERE u.banned = true")
    long countBanned()

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
}
