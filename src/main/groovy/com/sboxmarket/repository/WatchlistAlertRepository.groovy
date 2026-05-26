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

    /** Paged variant — batch 1041 caps the watchlist-alerts list at
     *  300 rows so a long-tenure user with thousands of FIRED /
     *  CANCELLED alerts over the years doesn't force the server to
     *  hydrate them all on every Watchlist-Alerts tab open. ACTIVE
     *  is capped at 50 per user (WatchlistAlertService.PER_USER_LIMIT),
     *  but the terminal-state rows accumulate unbounded. */
    @Query("SELECT a FROM WatchlistAlert a WHERE a.userId = :uid ORDER BY a.createdAt DESC")
    List<WatchlistAlert> findByUserIdPaged(@Param("uid") Long uid,
                                            org.springframework.data.domain.Pageable pageable)

    /** Drives the scheduled sweeper — every ACTIVE alert joined to the
     *  current item lowestPrice. Returns the alert row alongside the
     *  current floor AND the item name so the sweeper doesn't need a
     *  second fetch.
     *
     *  Projection is [alert, lowestPrice, name]. The `i.name` column
     *  was added (batch fix) so `fireRow` reads `row[2]` instead of
     *  re-issuing `itemRepository.findById(a.itemId)` once per
     *  triggered row — a classic N+1, since this query already JOINs
     *  Item.
     *
     *  Joins the owning SteamUser and filters out banned accounts so
     *  the sweeper doesn't keep emailing / pushing "price drop" pings
     *  to users who can't buy anymore. A later unban re-includes the
     *  alert in future scans (the row stays ACTIVE — we don't
     *  destructively mutate on ban). */
    @Query("""
        SELECT a, i.lowestPrice, i.name FROM WatchlistAlert a, Item i, SteamUser u
        WHERE a.status = 'ACTIVE'
          AND a.itemId = i.id
          AND a.userId = u.id
          AND (u.banned IS NULL OR u.banned = false)
          AND i.lowestPrice IS NOT NULL
          AND i.lowestPrice > 0
          AND i.lowestPrice <= a.targetPrice
    """)
    List<Object[]> findTriggered()

    /** Paged companion — when many prices drop simultaneously the
     *  triggered set can be very large; sweepers should walk in chunks
     *  rather than fire every alert in one tick. */
    @Query("""
        SELECT a, i.lowestPrice, i.name FROM WatchlistAlert a, Item i, SteamUser u
        WHERE a.status = 'ACTIVE'
          AND a.itemId = i.id
          AND a.userId = u.id
          AND (u.banned IS NULL OR u.banned = false)
          AND i.lowestPrice IS NOT NULL
          AND i.lowestPrice > 0
          AND i.lowestPrice <= a.targetPrice
    """)
    List<Object[]> findTriggered(org.springframework.data.domain.Pageable pageable)

    /** Same shape as findTriggered() ([alert, lowestPrice, name]) but
     *  scoped to a single item — drives the synchronous sweep fired
     *  from SellService.relist so a fresh listing triggers pending
     *  alerts within seconds instead of waiting up to 5 minutes for
     *  the scheduled pass. */
    @Query("""
        SELECT a, i.lowestPrice, i.name FROM WatchlistAlert a, Item i, SteamUser u
        WHERE a.status = 'ACTIVE'
          AND a.itemId = :itemId
          AND a.itemId = i.id
          AND a.userId = u.id
          AND (u.banned IS NULL OR u.banned = false)
          AND i.lowestPrice IS NOT NULL
          AND i.lowestPrice > 0
          AND i.lowestPrice <= a.targetPrice
    """)
    List<Object[]> findTriggeredForItem(@Param("itemId") Long itemId)

    /** Paged companion to {@link #findTriggeredForItem(Long)} — a hot
     *  item can collect tens of thousands of watchers, and the
     *  synchronous sweep fired from SellService.relist hydrates the
     *  ENTIRE projection list into memory on the request thread before
     *  fan-out begins. Without a SQL LIMIT a single fresh listing on a
     *  popular item could pull 50k+ Object[] rows into the JVM, blow
     *  the heap on under-provisioned nodes, and block the sell tx for
     *  the duration of the fan-out. The paged variant lets the service
     *  cap to a sane per-tick batch (overflow rolls into the next
     *  5-minute scheduled sweep, which already paginates via the
     *  in-memory clamp).
     *
     *  Same projection + filters as the un-paged sibling so behaviour
     *  is identical up to the row cap — callers that want the legacy
     *  "fetch everything" semantics can keep calling the original. */
    @Query("""
        SELECT a, i.lowestPrice, i.name FROM WatchlistAlert a, Item i, SteamUser u
        WHERE a.status = 'ACTIVE'
          AND a.itemId = :itemId
          AND a.itemId = i.id
          AND a.userId = u.id
          AND (u.banned IS NULL OR u.banned = false)
          AND i.lowestPrice IS NOT NULL
          AND i.lowestPrice > 0
          AND i.lowestPrice <= a.targetPrice
    """)
    List<Object[]> findTriggeredForItem(@Param("itemId") Long itemId,
                                         org.springframework.data.domain.Pageable pageable)

    long countByUserIdAndStatus(Long userId, String status)

    /** Distinct user ids with an ACTIVE watchlist alert on the given
     *  item. Drives the AUCTION_ENDING fanout — watchers get pinged
     *  when an auction of an item they're watching is about to close. */
    @Query("""
        SELECT DISTINCT a.userId FROM WatchlistAlert a
        WHERE a.itemId = :itemId
          AND a.status = 'ACTIVE'
    """)
    List<Long> findActiveUserIdsForItem(@Param("itemId") Long itemId)

    /** Paged companion — a hot item can collect thousands of watchers;
     *  fan-out callers should cap to avoid spraying notifications in
     *  a single tick. */
    @Query("""
        SELECT DISTINCT a.userId FROM WatchlistAlert a
        WHERE a.itemId = :itemId
          AND a.status = 'ACTIVE'
    """)
    List<Long> findActiveUserIdsForItem(@Param("itemId") Long itemId,
                                        org.springframework.data.domain.Pageable pageable)

    /** Public social-proof: how many users currently have an ACTIVE
     *  alert on this item. Powers the "N watching" chip on item detail.
     *  Aggregate only — no user identities surfaced. */
    @Query("""
        SELECT COUNT(a) FROM WatchlistAlert a
        WHERE a.itemId = :itemId
          AND a.status = 'ACTIVE'
    """)
    long countActiveForItem(@Param("itemId") Long itemId)

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM WatchlistAlert a WHERE a.userId = :uid AND a.status = 'FIRED'")
    int deleteFiredForUser(@Param("uid") Long uid)

    /** Full wipe of a user's alert rows — used by GDPR account
     *  finalization so the sweeper stops scanning orphaned alerts
     *  forever after an account is deleted. Returns the count wiped. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM WatchlistAlert a WHERE a.userId = :uid")
    int deleteByUser(@Param("uid") Long uid)
}
