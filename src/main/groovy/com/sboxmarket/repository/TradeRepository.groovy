package com.sboxmarket.repository

import com.sboxmarket.model.Trade
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TradeRepository extends JpaRepository<Trade, Long> {

    @Query("SELECT t FROM Trade t WHERE t.buyerUserId = :uid ORDER BY t.createdAt DESC")
    List<Trade> findByBuyer(@Param("uid") Long uid)

    /** Paged companion — caps a power-buyer's full trade history. Mirror
     *  of findByParticipantPaged below; new callers should prefer this
     *  overload over the unbounded findByBuyer. */
    @Query("SELECT t FROM Trade t WHERE t.buyerUserId = :uid ORDER BY t.createdAt DESC")
    List<Trade> findByBuyer(@Param("uid") Long uid,
                            org.springframework.data.domain.Pageable pageable)

    @Query("SELECT t FROM Trade t WHERE t.sellerUserId = :uid ORDER BY t.createdAt DESC")
    List<Trade> findBySeller(@Param("uid") Long uid)

    /** Paged companion — see findByBuyer above. A power-seller can
     *  accumulate thousands of historical trades; the cap keeps
     *  Profile → Sales-tab open from JOIN-FETCHing the whole history. */
    @Query("SELECT t FROM Trade t WHERE t.sellerUserId = :uid ORDER BY t.createdAt DESC")
    List<Trade> findBySeller(@Param("uid") Long uid,
                             org.springframework.data.domain.Pageable pageable)

    @Query("SELECT t FROM Trade t WHERE (t.buyerUserId = :uid OR t.sellerUserId = :uid) ORDER BY t.createdAt DESC")
    List<Trade> findByParticipant(@Param("uid") Long uid)

    /** Paged variant — the /api/trades endpoint uses this to cap the
     *  hydrated set at 200 rows so a power-user with thousands of
     *  historical trades doesn't force the server to serialise them
     *  all on every Profile → Trades tab open. Older trades stay in
     *  the DB; this is purely a display cap. */
    @Query("SELECT t FROM Trade t WHERE (t.buyerUserId = :uid OR t.sellerUserId = :uid) ORDER BY t.createdAt DESC")
    List<Trade> findByParticipantPaged(@Param("uid") Long uid,
                                        org.springframework.data.domain.Pageable pageable)

    /** Total trade count for the participant — powers the X-Total-Count
     *  header on /api/trades so the frontend can render "Showing most
     *  recent 200 of N" when the display cap trims. */
    @Query("SELECT COUNT(t) FROM Trade t WHERE (t.buyerUserId = :uid OR t.sellerUserId = :uid)")
    long countByParticipant(@Param("uid") Long uid)

    /** Open (non-terminal) trades for a participant — drives the ban
     *  cascade (batch 502) so a banned user's mid-flight escrowed trades
     *  get cancelled and counterparties refunded immediately, instead
     *  of waiting up to 3 days for the slow-seller auto-cancel sweep
     *  to fire. Excludes VERIFIED + CANCELLED + DISPUTED — the first
     *  two are terminal, and DISPUTED needs human staff resolution. */
    @Query("""
        SELECT t FROM Trade t
        WHERE (t.buyerUserId = :uid OR t.sellerUserId = :uid)
          AND t.state IN ('PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM')
        ORDER BY t.createdAt ASC
    """)
    List<Trade> findOpenByParticipant(@Param("uid") Long uid)

    /** Paged companion — bounded by active-trade ceiling in normal use
     *  but an adversarial account can queue many; ban-cascade callers
     *  should batch via Pageable. */
    @Query("""
        SELECT t FROM Trade t
        WHERE (t.buyerUserId = :uid OR t.sellerUserId = :uid)
          AND t.state IN ('PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM')
        ORDER BY t.createdAt ASC
    """)
    List<Trade> findOpenByParticipant(@Param("uid") Long uid,
                                       org.springframework.data.domain.Pageable pageable)

    /** Indexed COUNT companion — used by the admin delete-user / ban-user
     *  preflight instead of hydrating every open Trade row just to
     *  call .size() on the list. */
    @Query("""
        SELECT COUNT(t) FROM Trade t
        WHERE (t.buyerUserId = :uid OR t.sellerUserId = :uid)
          AND t.state IN ('PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM')
    """)
    long countOpenByParticipant(@Param("uid") Long uid)

    /** Verified trades between a given buyer and seller — drives the
     *  "Leave a review" CTA on the public stall page so a viewer only
     *  sees the prompt for sellers they've actually transacted with. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.buyerUserId  = :buyerId
          AND t.sellerUserId = :sellerId
          AND t.state        = 'VERIFIED'
        ORDER BY t.settledAt DESC
    """)
    List<Trade> findVerifiedBetween(@Param("buyerId") Long buyerId, @Param("sellerId") Long sellerId)

    /** Paged companion — a repeat-buyer/repeat-seller pair can accumulate
     *  many VERIFIED trades over time. The "Leave a review" CTA only needs
     *  to know one exists; new callers should pass `PageRequest.of(0, 1)`
     *  or a small cap. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.buyerUserId  = :buyerId
          AND t.sellerUserId = :sellerId
          AND t.state        = 'VERIFIED'
        ORDER BY t.settledAt DESC
    """)
    List<Trade> findVerifiedBetween(@Param("buyerId") Long buyerId,
                                    @Param("sellerId") Long sellerId,
                                    org.springframework.data.domain.Pageable pageable)

    @Query("SELECT t FROM Trade t WHERE t.state IN :states ORDER BY t.updatedAt ASC")
    List<Trade> findByStateIn(@Param("states") List<String> states)

    /** Paged companion — for terminal states (VERIFIED / CANCELLED) the
     *  trade table grows unbounded; this caps the per-call hydration so
     *  a sweeper or admin tool doesn't pull the entire history. */
    @Query("SELECT t FROM Trade t WHERE t.state IN :states ORDER BY t.updatedAt ASC")
    List<Trade> findByStateIn(@Param("states") List<String> states,
                              org.springframework.data.domain.Pageable pageable)

    Trade findByListingId(Long listingId)

    /** Admin trade queue — newest-updated-first, optional state filter.
     *  Empty-string sentinel for "no filter" per the Postgres type
     *  inference rule; see ItemRepository.searchCatalogue. */
    @Query("""
        SELECT t FROM Trade t
        WHERE (:state = '' OR t.state = :state)
        ORDER BY t.updatedAt DESC
    """)
    List<Trade> findForAdmin(@Param("state") String state)

    /** Paged companion — the admin trade queue grows unbounded as the
     *  platform ages. New admin UI calls should pass a Pageable so the
     *  payload stays at most a screen-worth of rows. */
    @Query("""
        SELECT t FROM Trade t
        WHERE (:state = '' OR t.state = :state)
        ORDER BY t.updatedAt DESC
    """)
    List<Trade> findForAdmin(@Param("state") String state,
                             org.springframework.data.domain.Pageable pageable)

    /** Trade-sweeper auto-release query — pulls only the
     *  PENDING_BUYER_CONFIRM trades whose updatedAt is older than the
     *  auto-release cutoff, so the scheduled job doesn't have to fetch
     *  every pending row and filter in memory. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state = 'PENDING_BUYER_CONFIRM'
          AND t.updatedAt <= :cutoff
        ORDER BY t.updatedAt ASC
    """)
    List<Trade> findPendingConfirmOlderThan(@Param("cutoff") Long cutoff)

    /** Paged companion — sweeper input; batch through Pageable rather
     *  than auto-release every eligible trade in one tick. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state = 'PENDING_BUYER_CONFIRM'
          AND t.updatedAt <= :cutoff
        ORDER BY t.updatedAt ASC
    """)
    List<Trade> findPendingConfirmOlderThan(@Param("cutoff") Long cutoff,
                                             org.springframework.data.domain.Pageable pageable)

    /** Seller-no-response sweep — stale PENDING_SELLER_ACCEPT /
     *  PENDING_SELLER_SEND trades past the response window. Without
     *  this, a non-responsive seller could freeze buyer funds in escrow
     *  indefinitely: the PENDING_BUYER_CONFIRM sweep above only handles
     *  the tail end of the state machine. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state IN ('PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND')
          AND t.updatedAt <= :cutoff
        ORDER BY t.updatedAt ASC
    """)
    List<Trade> findStaleSellerPending(@Param("cutoff") Long cutoff)

    /** Paged companion — sweeper input; batch through Pageable. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state IN ('PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND')
          AND t.updatedAt <= :cutoff
        ORDER BY t.updatedAt ASC
    """)
    List<Trade> findStaleSellerPending(@Param("cutoff") Long cutoff,
                                        org.springframework.data.domain.Pageable pageable)

    /** Trades sitting in a seller-pending state for >24h that haven't
     *  yet received a TRADE_SLOW_SELLER warning. Drives the warning
     *  sweep that pings the buyer at the 1-day mark — gives them a
     *  heads-up before the auto-cancel sweep kicks in at day 3. Served
     *  by the V35 partial index. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state IN ('PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND')
          AND t.updatedAt <= :cutoff
          AND t.slowSellerWarnedAt IS NULL
        ORDER BY t.updatedAt ASC
    """)
    List<Trade> findSlowSellerUnwarned(@Param("cutoff") Long cutoff)

    /** Paged companion — sweeper input; batch through Pageable. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state IN ('PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND')
          AND t.updatedAt <= :cutoff
          AND t.slowSellerWarnedAt IS NULL
        ORDER BY t.updatedAt ASC
    """)
    List<Trade> findSlowSellerUnwarned(@Param("cutoff") Long cutoff,
                                        org.springframework.data.domain.Pageable pageable)

    /**
     * VERIFIED trades older than the cutoff where the buyer hasn't
     * left a review yet AND a re-nudge hasn't been sent. Drives the
     * 48h "did the trade go OK?" follow-up push (batch 284). LEFT
     * JOIN against the Review table (matched by tradeId+fromUserId)
     * to exclude trades the buyer already reviewed.
     *
     * Served by the V38 partial index on (settled_at) WHERE
     * review_nudge_sent_at IS NULL AND state = 'VERIFIED', so the
     * candidate set is bounded by the un-nudged subset, not the full
     * trades table.
     */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state = 'VERIFIED'
          AND t.settledAt IS NOT NULL
          AND t.settledAt <= :cutoff
          AND t.reviewNudgeSentAt IS NULL
          AND t.buyerUserId IS NOT NULL
          AND NOT EXISTS (
              SELECT r FROM com.sboxmarket.model.Review r
              WHERE r.tradeId = t.id AND r.fromUserId = t.buyerUserId
          )
        ORDER BY t.settledAt ASC
    """)
    List<Trade> findReviewNudgeCandidates(@Param("cutoff") Long cutoff)

    /** Paged companion — sweeper input; batch through Pageable so the
     *  48h nudge job doesn't fan out thousands of pushes per tick. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state = 'VERIFIED'
          AND t.settledAt IS NOT NULL
          AND t.settledAt <= :cutoff
          AND t.reviewNudgeSentAt IS NULL
          AND t.buyerUserId IS NOT NULL
          AND NOT EXISTS (
              SELECT r FROM com.sboxmarket.model.Review r
              WHERE r.tradeId = t.id AND r.fromUserId = t.buyerUserId
          )
        ORDER BY t.settledAt ASC
    """)
    List<Trade> findReviewNudgeCandidates(@Param("cutoff") Long cutoff,
                                           org.springframework.data.domain.Pageable pageable)

    /**
     * Atomic claim for the 48h-after-verification review-nudge sweep
     * (wave 129). Stamps `reviewNudgeSentAt` ONLY if it's still NULL.
     * Returns 1 to the winning pod / 0 to the losing pod (sibling pod
     * already nudged, or trade went elsewhere out-of-band).
     *
     * Multi-pod race protection. Same shape as waves 124-128. Without
     * an atomic claim each pod independently fires the REVIEW_REMINDER
     * push before either pod's `save()` lands — the 48h "leave a review"
     * nudge fires TWICE per trade, defeating the per-trade "one nudge
     * total" guarantee the reviewNudgeSentAt column was added to make.
     */
    @Modifying
    @Query("""
        UPDATE Trade t
           SET t.reviewNudgeSentAt = :now
         WHERE t.id                 = :id
           AND t.reviewNudgeSentAt IS NULL
    """)
    int claimReviewNudge(@Param('id') Long id, @Param('now') Long now)

    /**
     * Atomic claim for the slow-seller warning sweep (wave 129). Stamps
     * `slowSellerWarnedAt` ONLY if it's still NULL. Returns 1 to the
     * winning pod / 0 to the losing pod.
     *
     * Without the atomic claim, the multi-pod deploy fires both
     * TRADE_SLOW_SELLER (to the buyer) AND TRADE_SELLER_NUDGE (to the
     * seller) once per pod-per-row — defeating the per-trade "one
     * warning total" promise the slowSellerWarnedAt column was added to
     * make. Buyer and seller both receive the heads-up TWICE.
     */
    @Modifying
    @Query("""
        UPDATE Trade t
           SET t.slowSellerWarnedAt = :now
         WHERE t.id                  = :id
           AND t.slowSellerWarnedAt IS NULL
    """)
    int claimSlowSellerWarning(@Param('id') Long id, @Param('now') Long now)

    /**
     * Trades a buyer has settled but never reviewed — drives the Profile
     * "{N} trade(s) to review" chip + the pending-reviews list on the
     * Reviews tab. Same NOT EXISTS pattern as findReviewNudgeCandidates
     * so the query stays index-friendly; newest-first because the most
     * recent trades are the ones the buyer remembers best.
     *
     * Capped at 50 in the service layer — a user with more than 50
     * un-reviewed trades is either inactive (didn't want to review) or
     * abusing the list; either way showing everyone's full history is
     * wasted bandwidth.
     */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state = 'VERIFIED'
          AND t.buyerUserId = :uid
          AND t.settledAt IS NOT NULL
          AND t.sellerUserId IS NOT NULL
          AND NOT EXISTS (
              SELECT r FROM com.sboxmarket.model.Review r
              WHERE r.tradeId = t.id AND r.fromUserId = t.buyerUserId
          )
        ORDER BY t.settledAt DESC
    """)
    List<Trade> findUnreviewedByBuyer(@Param("uid") Long uid)

    /** Paged companion — service-layer cap of 50 today; new callers
     *  should push that cap into SQL via Pageable rather than truncate
     *  after hydration. */
    @Query("""
        SELECT t FROM Trade t
        WHERE t.state = 'VERIFIED'
          AND t.buyerUserId = :uid
          AND t.settledAt IS NOT NULL
          AND t.sellerUserId IS NOT NULL
          AND NOT EXISTS (
              SELECT r FROM com.sboxmarket.model.Review r
              WHERE r.tradeId = t.id AND r.fromUserId = t.buyerUserId
          )
        ORDER BY t.settledAt DESC
    """)
    List<Trade> findUnreviewedByBuyer(@Param("uid") Long uid,
                                       org.springframework.data.domain.Pageable pageable)

    /** VERIFIED-trade count as a given user's role (batch 847). Drives
     *  the counterparty-reputation chip on incoming offers so a seller
     *  weighing an offer can see the buyer's completed-trade history
     *  without clicking through to their profile. Indexed on
     *  `(buyer_user_id, state)`; constant-time probe. */
    @Query("""
        SELECT COUNT(t) FROM Trade t
        WHERE t.buyerUserId = :uid
          AND t.state       = 'VERIFIED'
    """)
    long countVerifiedByBuyer(@Param("uid") Long uid)

    /** Bulk variant of {@link #countVerifiedByBuyer} — returns
     *  `[buyerUserId, count]` rows for a set of buyer ids in ONE
     *  GROUP BY query. Drives the per-row counterparty-reputation chip
     *  on the seller's incoming-offers tab (batch 847) without the
     *  per-buyer N+1 that loops `countVerifiedByBuyer(bid)` per
     *  unique buyer. Buyers with zero VERIFIED trades are absent from
     *  the result (Postgres GROUP BY omits empty groups); callers
     *  treat missing as 0. Empty input is the caller's responsibility
     *  — `IN ()` is illegal SQL. */
    @Query("""
        SELECT t.buyerUserId, COUNT(t) FROM Trade t
        WHERE t.buyerUserId IN :uids
          AND t.state       = 'VERIFIED'
        GROUP BY t.buyerUserId
    """)
    List<Object[]> countVerifiedByBuyerIds(@Param("uids") Collection<Long> buyerUserIds)

    /** Count-only version of findUnreviewedByBuyer — drives the
     *  /api/profile/pending-actions avatar badge so we don't hydrate a
     *  full list of Trade rows just to count them. Same NOT EXISTS shape
     *  so the query planner hits the same indexes. */
    @Query("""
        SELECT COUNT(t) FROM Trade t
        WHERE t.state = 'VERIFIED'
          AND t.buyerUserId = :uid
          AND t.settledAt IS NOT NULL
          AND t.sellerUserId IS NOT NULL
          AND NOT EXISTS (
              SELECT r FROM com.sboxmarket.model.Review r
              WHERE r.tradeId = t.id AND r.fromUserId = t.buyerUserId
          )
    """)
    long countUnreviewedByBuyer(@Param("uid") Long uid)

    /** Platform fee collected on VERIFIED trades since a cutoff. Used by
     *  the admin dashboard "Fees 24h" stat — one indexed SUM instead of
     *  scanning the trade table. */
    @Query("""
        SELECT COALESCE(SUM(t.feeAmount), 0) FROM Trade t
        WHERE t.state = 'VERIFIED'
          AND t.settledAt IS NOT NULL
          AND t.settledAt >= :since
    """)
    BigDecimal sumFeesSince(@Param("since") Long since)

    /** Lifetime platform fees paid by a single seller (batch 709). Drives
     *  the MyStall Earnings card — sellers can see "you've paid $X in
     *  platform fees across your lifetime on SkinBox", which pairs with
     *  the existing lifetime revenue figure for tax prep + fairness
     *  auditing. Only VERIFIED (fully settled) trades count; in-flight
     *  or cancelled trades don't accumulate a fee. */
    @Query("""
        SELECT COALESCE(SUM(t.feeAmount), 0) FROM Trade t
        WHERE t.sellerUserId = :uid
          AND t.state = 'VERIFIED'
          AND t.settledAt IS NOT NULL
    """)
    BigDecimal sumFeesBySeller(@Param("uid") Long uid)

    /** Trades currently in a given state — single indexed COUNT used
     *  by the admin dashboard for the "Open disputes" and "Pending
     *  release" stat cards. The trades table already has an index on
     *  `(state, updatedAt)` so this is effectively a constant-time
     *  probe per call. */
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.state = :state")
    long countByState(@Param("state") String state)

    /** Count of trades where the seller needs to take the next action
     *  (accept the request or mark the Steam offer as sent). Drives the
     *  nav-avatar "pending actions" badge so sellers see at a glance
     *  how many trades are waiting on them. Indexed scan over
     *  `(state, updatedAt)`. */
    @Query("""
        SELECT COUNT(t) FROM Trade t
        WHERE t.sellerUserId = :uid
          AND t.state IN ('PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND')
    """)
    long countSellerPending(@Param("uid") Long sellerUserId)

    /** Count of trades waiting on the buyer's confirmation. Mirrors the
     *  seller version so the nav badge is symmetric: both sides see the
     *  number of trades that are on their court. */
    @Query("""
        SELECT COUNT(t) FROM Trade t
        WHERE t.buyerUserId = :uid
          AND t.state = 'PENDING_BUYER_CONFIRM'
    """)
    long countBuyerPending(@Param("uid") Long buyerUserId)

    /** Count of disputed trades where the user is a participant — staff
     *  acts on these but the counterparty may still want to respond with
     *  evidence / chat. */
    @Query("""
        SELECT COUNT(t) FROM Trade t
        WHERE (t.buyerUserId = :uid OR t.sellerUserId = :uid)
          AND t.state = 'DISPUTED'
    """)
    long countDisputedForUser(@Param("uid") Long userId)

    /**
     * "Top sellers this week" aggregation — drives the homepage
     * leaderboard rail per CSFloat Manual §4. Counts VERIFIED trades
     * per seller since the cutoff, returns the top N by sale count.
     * Projects (sellerUserId, saleCount, totalRevenue) so the frontend
     * can surface volume alongside count without a second round-trip.
     *
     * One indexed scan over `(state, updatedAt)` — cheap enough to call
     * uncached on every homepage hit, but the service layer caches it
     * for 60s as a belt-and-braces guard.
     */
    @Query("""
        SELECT t.sellerUserId AS sellerUserId,
               COUNT(t)       AS saleCount,
               SUM(t.price)   AS totalRevenue
          FROM Trade t
         WHERE t.state        = 'VERIFIED'
           AND t.settledAt   >= :since
           AND t.sellerUserId IS NOT NULL
         GROUP BY t.sellerUserId
         ORDER BY COUNT(t) DESC
    """)
    List<Map<String, Object>> findTopSellersSince(@Param("since") Long since,
                                                   org.springframework.data.domain.Pageable page)

    /**
     * Ship-time samples for the "Typically ships in ~N hours" stall metric
     * (batch 550). Returns the delta between each verified trade's sent_at
     * and created_at for a seller over the lookback window. The service
     * layer takes the median so one pathological 14-day trade doesn't
     * poison the headline number.
     *
     * Only considers trades where the seller actually pressed "Mark sent"
     * (sent_at IS NOT NULL) AND completed normally (state='VERIFIED'). A
     * disputed-but-eventually-force-released trade won't pollute the
     * "typical" claim.
     *
     * Served by the V49 partial index on (seller_user_id, sent_at).
     */
    @Query("""
        SELECT (t.sentAt - t.createdAt) FROM Trade t
        WHERE t.sellerUserId = :sellerId
          AND t.state        = 'VERIFIED'
          AND t.sentAt IS NOT NULL
          AND t.sentAt  >= :since
    """)
    List<Long> findRecentShipMsForSeller(@Param("sellerId") Long sellerId,
                                         @Param("since") Long since)

    /**
     *  Bulk companion to {@link #findRecentShipMsForSeller}. The
     *  per-seller variant fires one SQL query per id; the
     *  `/api/sellers/ship-times?ids=...` endpoint accepts up to 200 ids
     *  and previously looped over them calling the per-seller method,
     *  giving an N+1 with N = 200 on every marketplace grid load.
     *
     *  Returns `[sellerUserId, sentAt - createdAt]` rows. The caller
     *  groups by sellerUserId, sorts each bucket, and takes the median
     *  (same logic as the single-seller path) — no rule change.
     */
    @Query("""
        SELECT t.sellerUserId, (t.sentAt - t.createdAt) FROM Trade t
        WHERE t.sellerUserId IN :sellerIds
          AND t.state        = 'VERIFIED'
          AND t.sentAt IS NOT NULL
          AND t.sentAt  >= :since
    """)
    List<Object[]> findRecentShipMsForSellers(@Param("sellerIds") Collection<Long> sellerIds,
                                              @Param("since") Long since)

    /**
     * A seller's trade record, in bulk: rows of
     * `[sellerUserId, completed, sellerFailed]`.
     *
     * `completed` counts VERIFIED trades. `sellerFailed` counts only the
     * cancellations that were the seller's: they cancelled it, or they never
     * accepted/sent it inside the response window. A buyer backing out, a
     * staff ruling and rows cancelled before `cancelled_by` existed (NULL)
     * are not the seller's failure and are not counted either way. Served by
     * idx_trades_seller.
     */
    @Query("""
        SELECT t.sellerUserId,
               SUM(CASE WHEN t.state = 'VERIFIED' THEN 1 ELSE 0 END),
               SUM(CASE WHEN t.state = 'CANCELLED'
                         AND t.cancelledBy IN ('SELLER', 'SELLER_TIMEOUT') THEN 1 ELSE 0 END)
        FROM Trade t
        WHERE t.sellerUserId IN :sellerIds
        GROUP BY t.sellerUserId
    """)
    List<Object[]> tradeRecordForSellers(@Param("sellerIds") Collection<Long> sellerIds)
}
