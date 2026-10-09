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
          AND i.isListed = true
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
          AND i.isListed = true
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
          AND i.isListed = true
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
          AND i.isListed = true
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

    /** Race-safe ACTIVE → FIRED claim used by the sweeper (wave 112).
     *
     *  Multi-pod prod hazard: `@Scheduled(fixedDelay)` serialises ticks
     *  WITHIN one JVM only — when sboxmarket runs on two or more pods,
     *  both schedulers fire roughly together, both call
     *  {@link #findTriggered()}, and both pull the SAME ACTIVE rows.
     *  Without an atomic claim each pod's `fireRow` would push a
     *  WATCHLIST_PRICE_DROP notification and email to the user, then
     *  blind-save FIRED — the second save just overwrites with FIRED
     *  again so the DB looks fine, but the user already got two
     *  duplicate "Price drop" pushes and (per-pod LRU-bounded email
     *  dedup ledger) up to two duplicate emails per fired alert.
     *
     *  Conditional UPDATE with affected-rows check makes the flip the
     *  authoritative gate: whichever pod wins gets `1` back and pushes;
     *  the loser gets `0` and bails BEFORE invoking notification +
     *  email. Filtering on `status = 'ACTIVE'` keeps the operation
     *  idempotent against any prior FIRED / CANCELLED flip (cancel
     *  racing the sweep is also covered — a user who cancels between
     *  `findTriggered` and the claim won't be pushed at). */
    // Own transaction: the scheduled sweep and NotificationService.push call
    // this outside one, and a bare @Modifying query then throws.
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE WatchlistAlert a
           SET a.status = 'FIRED',
               a.firedAt = :now
         WHERE a.id = :id
           AND a.status = 'ACTIVE'
    """)
    int claimForFiring(@Param("id") Long id, @Param("now") Long now)

    /** Race-safe re-arm of an existing ACTIVE alert (wave 113).
     *
     *  Sister race to {@link #claimForFiring}: `upsertAlert` reads the
     *  row via `findActiveFor` and then calls `repo.save(a)` to update
     *  `targetPrice` + `createdAt`. JPA's save() writes EVERY mapped
     *  column, including `status` — whatever value the in-memory entity
     *  carries (loaded as 'ACTIVE'). If the scheduled sweep's
     *  `claimForFiring` lands BETWEEN the `findActiveFor` and the save,
     *  the DB row has flipped to FIRED — and the unconditional
     *  `repo.save(a)` then UPDATEs `status` back to 'ACTIVE', silently
     *  reverting the FIRED claim. The user already got the
     *  WATCHLIST_PRICE_DROP push (sweep had committed it), but the row
     *  is now ACTIVE again, so the NEXT sweep tick re-fires the same
     *  alert and the user gets a duplicate "Price drop" notification +
     *  email.
     *
     *  Conditional UPDATE filtering on `status = 'ACTIVE'` makes the
     *  re-arm a no-op when the row has already FIRED / CANCELLED.
     *  Affected-rows == 0 tells the service to fall through to the
     *  create-new path (so the user's intent — "I want an active alert
     *  at $X" — still lands as a brand-new ACTIVE row) without
     *  resurrecting the spent one. */
    // Own transaction: the scheduled sweep and NotificationService.push call
    // this outside one, and a bare @Modifying query then throws.
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE WatchlistAlert a
           SET a.targetPrice = :target,
               a.createdAt = :now
         WHERE a.id = :id
           AND a.status = 'ACTIVE'
    """)
    int updateActiveTarget(@Param("id") Long id,
                           @Param("target") BigDecimal target,
                           @Param("now") Long now)

    /** Full wipe of a user's alert rows — used by GDPR account
     *  finalization so the sweeper stops scanning orphaned alerts
     *  forever after an account is deleted. Returns the count wiped. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM WatchlistAlert a WHERE a.userId = :uid")
    int deleteByUser(@Param("uid") Long uid)

    /** Cancel the user's ACTIVE alerts on the given items — used when the
     *  watchlist is cleared, so alerts on items they stopped watching
     *  don't keep pinging. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE WatchlistAlert a
           SET a.status = 'CANCELLED'
         WHERE a.userId = :uid
           AND a.status = 'ACTIVE'
           AND a.itemId IN :itemIds
    """)
    int cancelActiveForItems(@Param("uid") Long uid, @Param("itemIds") List<Long> itemIds)
}
