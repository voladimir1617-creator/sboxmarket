package com.sboxmarket.repository

import com.sboxmarket.model.UserBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    /** Rows the given user has created — drives the Profile → Blocked
     *  tab list. Newest-first so the most recently blocked user is
     *  on top (typical CSR-style lookup order). */
    @Query("""
        SELECT b FROM UserBlock b
        WHERE b.blockerUserId = :uid
        ORDER BY b.createdAt DESC
    """)
    List<UserBlock> findByBlocker(@Param('uid') Long blockerUserId)

    /** Paged companion — a paranoid user can amass many block rows;
     *  the Profile → Blocked tab should cap the hydration. */
    @Query("""
        SELECT b FROM UserBlock b
        WHERE b.blockerUserId = :uid
        ORDER BY b.createdAt DESC
    """)
    List<UserBlock> findByBlocker(@Param('uid') Long blockerUserId,
                                   org.springframework.data.domain.Pageable pageable)

    /** Single-pair probe — drives the write-path enforcement. Used for
     *  "does seller S have buyer B on their block list?" / symmetric. */
    @Query("""
        SELECT COUNT(b) > 0 FROM UserBlock b
        WHERE b.blockerUserId = :blocker
          AND b.blockedUserId = :blocked
    """)
    boolean existsBlock(@Param('blocker') Long blockerUserId,
                        @Param('blocked') Long blockedUserId)

    /** Blocker ids for a given target — "who has blocked user X". Drives
     *  the "filter listings hidden from me" reverse lookup if we ever
     *  need to show "N users blocked you" in staff tooling. */
    @Query("SELECT b.blockerUserId FROM UserBlock b WHERE b.blockedUserId = :uid")
    List<Long> findBlockerIdsForBlocked(@Param('uid') Long blockedUserId)

    /** Paged companion — a notorious account can collect many blockers;
     *  staff tooling should cap. */
    @Query("SELECT b.blockerUserId FROM UserBlock b WHERE b.blockedUserId = :uid")
    List<Long> findBlockerIdsForBlocked(@Param('uid') Long blockedUserId,
                                        org.springframework.data.domain.Pageable pageable)

    /** Blocked ids for a given blocker — fast one-column projection
     *  used when the caller only needs the id set, not the full rows.
     *  Drives the listing-grid filter that hides anyone the viewer has
     *  blocked. */
    @Query("SELECT b.blockedUserId FROM UserBlock b WHERE b.blockerUserId = :uid")
    List<Long> findBlockedIdsForBlocker(@Param('uid') Long blockerUserId)

    /** Paged companion — the listing-grid filter does not need an
     *  unbounded list. The cap keeps the per-request filter cost
     *  O(pageSize). */
    @Query("SELECT b.blockedUserId FROM UserBlock b WHERE b.blockerUserId = :uid")
    List<Long> findBlockedIdsForBlocker(@Param('uid') Long blockerUserId,
                                        org.springframework.data.domain.Pageable pageable)

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
        DELETE FROM UserBlock b
        WHERE b.blockerUserId = :blocker
          AND b.blockedUserId = :blocked
    """)
    int deleteByPair(@Param('blocker') Long blockerUserId,
                     @Param('blocked') Long blockedUserId)

    /** Wipe every row the given user created (their block list). Used
     *  by AdminService.finalizeDeletion when the user's account is
     *  deleted — no reason to keep their preference rows. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("DELETE FROM UserBlock b WHERE b.blockerUserId = :uid")
    int deleteByBlocker(@Param('uid') Long blockerUserId)

    /** Wipe every row that targets the given user (other people who
     *  blocked them). Also used by finalizeDeletion — when the target
     *  account is gone the blocker's rows against it become dead FKs. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("DELETE FROM UserBlock b WHERE b.blockedUserId = :uid")
    int deleteByBlocked(@Param('uid') Long blockedUserId)

    /** Count of blocks the user has created — surfaces in the Profile
     *  badge as "N blocked" without hydrating the full list. */
    @Query("SELECT COUNT(b) FROM UserBlock b WHERE b.blockerUserId = :uid")
    long countByBlocker(@Param('uid') Long blockerUserId)
}
