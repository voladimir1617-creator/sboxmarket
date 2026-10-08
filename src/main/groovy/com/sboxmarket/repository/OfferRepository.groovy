package com.sboxmarket.repository

import com.sboxmarket.model.Offer
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface OfferRepository extends JpaRepository<Offer, Long> {

    /** Offers the user is making (outgoing). */
    @Query("SELECT o FROM Offer o WHERE o.buyerUserId = :uid ORDER BY o.createdAt DESC")
    List<Offer> findByBuyer(@Param("uid") Long buyerUserId)

    /** Paged variant — caps the Profile → Offers → Outgoing tab so
     *  a power-user with thousands of historical offers doesn't force
     *  the server to hydrate + enrich them all on every tab open. */
    @Query("SELECT o FROM Offer o WHERE o.buyerUserId = :uid ORDER BY o.createdAt DESC")
    List<Offer> findByBuyerPaged(@Param("uid") Long buyerUserId,
                                  org.springframework.data.domain.Pageable pageable)

    /** Total outgoing-offer count for the buyer — feeds X-Total-Count. */
    @Query("SELECT COUNT(o) FROM Offer o WHERE o.buyerUserId = :uid")
    long countByBuyer(@Param("uid") Long buyerUserId)

    /** Offers the user is receiving (incoming on their listings). */
    @Query("SELECT o FROM Offer o WHERE o.sellerUserId = :uid ORDER BY o.createdAt DESC")
    List<Offer> findBySeller(@Param("uid") Long sellerUserId)

    /** Paged variant — mirror of findByBuyerPaged for the Incoming tab. */
    @Query("SELECT o FROM Offer o WHERE o.sellerUserId = :uid ORDER BY o.createdAt DESC")
    List<Offer> findBySellerPaged(@Param("uid") Long sellerUserId,
                                   org.springframework.data.domain.Pageable pageable)

    /** Total incoming-offer count for the seller — feeds X-Total-Count. */
    @Query("SELECT COUNT(o) FROM Offer o WHERE o.sellerUserId = :uid")
    long countBySeller(@Param("uid") Long sellerUserId)

    @Query("SELECT o FROM Offer o WHERE o.listingId = :lid AND o.status = 'PENDING'")
    List<Offer> findPendingForListing(@Param("lid") Long listingId)

    /** Paged companion — a viral listing can attract many concurrent
     *  PENDING offers; callers that only need the top-N for display
     *  should use this overload. */
    @Query("SELECT o FROM Offer o WHERE o.listingId = :lid AND o.status = 'PENDING'")
    List<Offer> findPendingForListing(@Param("lid") Long listingId, Pageable pageable)

    /** The caller's PENDING or COUNTERED offer on a given listing, if any.
     *  Drives the "You offered $X" chip in the ItemModal (batch 368) so a
     *  buyer revisiting a listing immediately sees their live offer state.
     *  Newest-first in case old threaded offers are still attached to the
     *  same listing. */
    @Query("""
        SELECT o FROM Offer o
        WHERE o.listingId = :lid
          AND o.buyerUserId = :uid
          AND o.status IN ('PENDING', 'COUNTERED')
        ORDER BY o.createdAt DESC
    """)
    List<Offer> findLiveByBuyerAndListing(@Param("uid") Long buyerUserId,
                                          @Param("lid") Long listingId)

    /** Every offer on a listing, newest first. Uses the
     *  `idx_offers_listing` composite index so it stays O(log N). */
    @Query("SELECT o FROM Offer o WHERE o.listingId = :lid ORDER BY o.createdAt DESC")
    List<Offer> findByListingId(@Param("lid") Long listingId)

    /** Paged companion — across the full ACCEPTED/REJECTED/COUNTERED/
     *  CANCELLED/EXPIRED history a long-running listing accumulates,
     *  the row count grows without bound. */
    @Query("SELECT o FROM Offer o WHERE o.listingId = :lid ORDER BY o.createdAt DESC")
    List<Offer> findByListingId(@Param("lid") Long listingId, Pageable pageable)

    /** Direct children of a given offer in a counter thread — every Offer
     *  whose `parentOfferId` points at `:pid`. Drives the COUNTERED-parent
     *  cleanup: when a buyer withdraws a COUNTERED original, the service
     *  needs to find the still-live SELLER counter hanging off it and
     *  close that too, so a walked-away negotiation leaves no acceptable
     *  PENDING row behind. Newest-first for deterministic iteration. */
    @Query("SELECT o FROM Offer o WHERE o.parentOfferId = :pid ORDER BY o.createdAt DESC")
    List<Offer> findByParentOfferId(@Param("pid") Long parentOfferId)

    @Query("SELECT COUNT(o) FROM Offer o WHERE o.buyerUserId = :uid AND o.status = 'PENDING'")
    long countPendingByBuyer(@Param("uid") Long buyerUserId)

    /** PENDING-only offers for a buyer. Used by the ban cascade (batch
     *  1030) and cancelAllForUser (batch 291) to avoid hydrating every
     *  historical offer (thousands of ACCEPTED/REJECTED/EXPIRED) just
     *  to filter down to the 100-or-fewer PENDING rows the caller
     *  actually needs. Backed by the composite index on
     *  `(buyer_user_id, status)`. */
    @Query("SELECT o FROM Offer o WHERE o.buyerUserId = :uid AND o.status = 'PENDING' ORDER BY o.createdAt DESC")
    List<Offer> findPendingByBuyer(@Param("uid") Long buyerUserId)

    /** Paged companion — bounded by the per-buyer PENDING ceiling in
     *  normal use, but ban-cascade callers facing an adversarial buyer
     *  who queued thousands of pending offers should iterate in batches. */
    @Query("SELECT o FROM Offer o WHERE o.buyerUserId = :uid AND o.status = 'PENDING' ORDER BY o.createdAt DESC")
    List<Offer> findPendingByBuyer(@Param("uid") Long buyerUserId, Pageable pageable)

    /** Incoming-offer count for a seller — drives the nav badge so sellers
     *  see "3 offers waiting" without opening the Offers tab. PENDING only
     *  (accepted/rejected/countered are terminal from the seller's view). */
    // Excludes the seller's own counters: those wait on the buyer, so they
    // aren't offers "waiting" on the seller and must not light the badge.
    @Query("SELECT COUNT(o) FROM Offer o WHERE o.sellerUserId = :uid AND o.status = 'PENDING' AND (o.author IS NULL OR o.author <> 'SELLER')")
    long countPendingBySeller(@Param("uid") Long sellerUserId)

    /** Stale PENDING offers — drives the auto-decline sweeper. `updatedAt`
     *  captures the last seller/buyer interaction (counter, partial reply,
     *  etc.), so the 7-day window is "last meaningful activity", not
     *  "time since first created". */
    @Query("SELECT o FROM Offer o WHERE o.status = 'PENDING' AND o.updatedAt <= :cutoff")
    List<Offer> findStalePending(@Param("cutoff") Long cutoff)

    /** Paged companion — on a busy platform the stale candidate set can
     *  swell after long downtimes; batch sweeper callers should walk in
     *  chunks rather than hydrating the full set per tick. */
    @Query("SELECT o FROM Offer o WHERE o.status = 'PENDING' AND o.updatedAt <= :cutoff")
    List<Offer> findStalePending(@Param("cutoff") Long cutoff, Pageable pageable)

    /** PENDING offers that crossed the half-life mark and haven't been
     *  nudged yet (batch 499). The sweeper pushes a one-time "your offer
     *  expires soon — act before it auto-declines" notification to the
     *  seller, then stamps `sellerNudgedAt` so the next pass skips. The
     *  partial index `idx_offers_pending_unnudged` makes the filter cheap
     *  even at high offer volume.
     *  - `:halfLifeCutoff` = now - (autoDeclineDays / 2 days in millis)
     *  - `:fullLifeCutoff` = now - (autoDeclineDays days in millis); we
     *    exclude rows past full life so the sweeper doesn't spam a nudge
     *    immediately followed by the auto-decline notification. */
    @Query("""
        SELECT o FROM Offer o
        WHERE o.status = 'PENDING'
          AND o.sellerNudgedAt IS NULL
          AND o.updatedAt <= :halfLifeCutoff
          AND o.updatedAt > :fullLifeCutoff
    """)
    List<Offer> findPendingDueForNudge(@Param("halfLifeCutoff") Long halfLifeCutoff,
                                        @Param("fullLifeCutoff") Long fullLifeCutoff)

    /** Paged companion — same batched-sweeper rationale as findStalePending. */
    @Query("""
        SELECT o FROM Offer o
        WHERE o.status = 'PENDING'
          AND o.sellerNudgedAt IS NULL
          AND o.updatedAt <= :halfLifeCutoff
          AND o.updatedAt > :fullLifeCutoff
    """)
    List<Offer> findPendingDueForNudge(@Param("halfLifeCutoff") Long halfLifeCutoff,
                                        @Param("fullLifeCutoff") Long fullLifeCutoff,
                                        Pageable pageable)

    /**
     * Atomic claim for the stale-offer auto-decline sweep (wave 128).
     * Flips `status` from PENDING→EXPIRED and stamps `updatedAt` ONLY if
     * the offer is still PENDING at UPDATE time. Returns the affected-row
     * count: 1 = this pod owns the fan-out (OFFER_REJECTED bell push +
     * closeCounteredParent cascade), 0 = sibling pod already claimed it
     * OR the seller/buyer flipped status (accept/reject/cancel) between
     * sweeper read and claim — in either case the losing path bails
     * before any notify call runs.
     *
     * Multi-pod race protection. Same shape as waves 124, 125, 126, 127.
     * Without an atomic claim each pod independently fires
     * OFFER_REJECTED bell push before either pod's save() lands — the
     * buyer sees their offer auto-decline notification TWICE for one
     * timeout, AND `closeCounteredParent` cascade re-runs (also safe,
     * but wasted work). Worse: a seller / buyer late-acting on the
     * offer between sweeper read and unconditional save would have been
     * STOMPED — accept/reject lost, offer status flipped EXPIRED, both
     * parties left in inconsistent state.
     */
    @Modifying
    @Query("""
        UPDATE Offer o
           SET o.status    = 'EXPIRED',
               o.updatedAt = :now
         WHERE o.id     = :id
           AND o.status = 'PENDING'
    """)
    int claimAutoExpire(@Param('id') Long id, @Param('now') Long now)

    /**
     * Atomic claim for the half-life nudge sweep (wave 128). Stamps
     * `sellerNudgedAt` ONLY if it's still NULL. Returns the affected-row
     * count: 1 = this pod owns the OFFER_RECEIVED nudge fan-out, 0 =
     * sibling pod already nudged OR the offer transitioned out of
     * PENDING between sweeper read and claim.
     *
     * Without the atomic claim, multi-pod deploys re-fire the OFFER_RECEIVED
     * nudge once per pod-per-row — defeating the per-offer "one nudge
     * total" promise the `sellerNudgedAt` column was added to make. The
     * `sellerNudgedAt IS NULL` predicate in the UPDATE makes the second
     * pod's claim a no-op (the first pod already stamped it non-null).
     */
    @Modifying
    @Query("""
        UPDATE Offer o
           SET o.sellerNudgedAt = :now
         WHERE o.id              = :id
           AND o.status          = 'PENDING'
           AND o.sellerNudgedAt IS NULL
    """)
    int claimNudge(@Param('id') Long id, @Param('now') Long now)

    /** Root buyer offers the given seller has resolved (accepted,
     *  rejected, or countered). The delta `updatedAt - createdAt` is
     *  the seller's response time. Excludes PENDING (not yet answered),
     *  CANCELLED (buyer withdrew — nothing to measure), and EXPIRED
     *  (sweeper closed it — would skew the stat toward the 7-day cap).
     *  Pageable so the service can bound the window (most recent N). */
    @Query("""
        SELECT o FROM Offer o
        WHERE o.sellerUserId = :uid
          AND o.author = 'USER'
          AND o.status IN ('ACCEPTED','REJECTED','COUNTERED')
        ORDER BY o.updatedAt DESC
    """)
    List<Offer> findRecentSellerResponses(@Param("uid") Long sellerUserId, Pageable pageable)

    /** Offers a seller engaged with — union of resolved responses
     *  (accept / reject / counter) and expired rows (the seller never
     *  answered within the auto-decline window). Denominator for the
     *  public "response rate" chip. CANCELLED is excluded because that
     *  is a buyer-side withdrawal, not a seller-controlled outcome. */
    @Query("""
        SELECT COUNT(o) FROM Offer o
        WHERE o.sellerUserId = :uid
          AND o.author = 'USER'
          AND o.status IN ('ACCEPTED','REJECTED','COUNTERED','EXPIRED')
    """)
    long countSellerEngagedTotal(@Param("uid") Long sellerUserId)

    /** Subset of {@link #countSellerEngagedTotal} that the seller
     *  actively resolved. Numerator for the response rate chip. */
    @Query("""
        SELECT COUNT(o) FROM Offer o
        WHERE o.sellerUserId = :uid
          AND o.author = 'USER'
          AND o.status IN ('ACCEPTED','REJECTED','COUNTERED')
    """)
    long countSellerResponded(@Param("uid") Long sellerUserId)

    /** Per-listing aggregate of the PENDING buyer offers for a seller —
     *  top amount, count, and newest timestamp. Drives the MyStall
     *  "Best offer: $X · N pending" chip so sellers don't have to hop
     *  to the Offers tab to see which listings have bargainers waiting.
     *  Only PENDING so COUNTERED (seller already responded) and
     *  CANCELLED / EXPIRED rows don't resurface as actionable. */
    @Query("""
        SELECT o.listingId, MAX(o.amount), COUNT(o), MAX(o.createdAt)
        FROM Offer o
        WHERE o.sellerUserId = :uid
          AND o.status = 'PENDING'
          AND o.author = 'USER'
        GROUP BY o.listingId
    """)
    List<Object[]> aggregatePendingBySeller(@Param("uid") Long sellerUserId)
}
