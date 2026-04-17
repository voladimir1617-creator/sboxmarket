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
}
