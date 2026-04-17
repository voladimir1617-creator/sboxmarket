package com.sboxmarket.repository

import com.sboxmarket.model.WatchlistAlert
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface WatchlistAlertRepository extends JpaRepository<WatchlistAlert, Long> {

    /** Active alert for this (user, item) pair, if any. Drives the
     *  "re-set the alert" upsert path. */
    @Query("""
        SELECT a FROM WatchlistAlert a
        WHERE a.userId = :uid
          AND a.itemId = :itemId
          AND a.status = 'ACTIVE'
    """)
    Optional<WatchlistAlert> findActiveFor(@Param("uid") Long uid, @Param("itemId") Long itemId)

    /** All alerts for a user, newest first — mixed statuses so the
     *  watchlist UI can show ACTIVE + FIRED + CANCELLED history. */
    @Query("SELECT a FROM WatchlistAlert a WHERE a.userId = :uid ORDER BY a.createdAt DESC")
    List<WatchlistAlert> findByUserId(@Param("uid") Long uid)

    /** Drives the scheduled sweeper — every ACTIVE alert joined to the
     *  current item lowestPrice. Returns the alert row alongside the
     *  current floor so the sweeper doesn't need a second fetch. */
    @Query("""
        SELECT a, i.lowestPrice FROM WatchlistAlert a, Item i
        WHERE a.status = 'ACTIVE'
          AND a.itemId = i.id
          AND i.lowestPrice IS NOT NULL
          AND i.lowestPrice > 0
          AND i.lowestPrice <= a.targetPrice
    """)
    List<Object[]> findTriggered()

    long countByUserIdAndStatus(Long userId, String status)

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM WatchlistAlert a WHERE a.userId = :uid AND a.status = 'FIRED'")
    int deleteFiredForUser(@Param("uid") Long uid)
}
