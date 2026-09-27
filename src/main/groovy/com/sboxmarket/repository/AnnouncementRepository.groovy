package com.sboxmarket.repository

import com.sboxmarket.model.Announcement
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Current live announcement — active=true and not expired. At most one
     *  is shown at a time; admins can swap by deactivating the previous row
     *  before creating a new one. Returns newest-first so a race between
     *  two admin posts still yields a deterministic banner. */
    @Query("""
        SELECT a FROM Announcement a
        WHERE a.active = true
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
        ORDER BY a.createdAt DESC
    """)
    List<Announcement> findCurrent(@Param("now") Long now)

    @Query("SELECT a FROM Announcement a ORDER BY a.createdAt DESC")
    List<Announcement> findAllForAdmin()

    /** Paged companion — admin announcement history grows one row per
     *  banner ever posted; cap to keep the admin UI render bounded. */
    @Query("SELECT a FROM Announcement a ORDER BY a.createdAt DESC")
    List<Announcement> findAllForAdmin(org.springframework.data.domain.Pageable page)

    /** Rows that are STILL marked active in the DB but whose expiresAt
     *  has passed (batch 582). Public `findCurrent` already filters
     *  these out at read time; the sweeper uses this to flip their
     *  `active` flag to false so the admin history tab doesn't show
     *  a stale "LIVE NOW" tag on rows that are effectively dead. */
    @Query("""
        SELECT a FROM Announcement a
        WHERE a.active = true
          AND a.expiresAt IS NOT NULL
          AND a.expiresAt <= :now
    """)
    List<Announcement> findExpiredButActive(@Param("now") Long now)
}
