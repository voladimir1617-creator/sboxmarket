package com.sboxmarket.repository

import com.sboxmarket.model.Transaction
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByWalletIdOrderByCreatedAtDesc(Long walletId)

    /** Paginated variant — loads at most `pageable.pageSize` rows instead of the
     *  entire wallet history. Used by WalletController. */
    List<Transaction> findByWalletIdOrderByCreatedAtDesc(Long walletId, Pageable pageable)

    Transaction findByStripeReference(String stripeReference)

    /** Single SUM aggregate for the admin dashboard 24h volume charts.
     *  Replaces `findAll().findAll { ... }.sum()` which is O(N) over every
     *  transaction the platform has ever recorded. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.type = :type AND t.status = :status AND t.createdAt >= :since")
    BigDecimal sumByTypeSinceCompleted(
        @Param('type')   String type,
        @Param('status') String status,
        @Param('since')  Long since
    )

    /** Rolling-window SUM keyed on stripeReference — drives the per-CSR
     *  cumulative goodwill-credit cap. Every issueGoodwillCredit writes
     *  type='ADJUSTMENT_CREDIT', status='COMPLETED', stripeReference='csr_<id>',
     *  so summing those rows for one csr ref over the last 24h bounds how much a
     *  single (compromised/colluding) CSR can move per day. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.type = :type AND t.stripeReference = :ref AND t.status = 'COMPLETED' AND t.createdAt >= :since")
    BigDecimal sumByTypeReferenceSince(
        @Param('type') String type,
        @Param('ref')  String ref,
        @Param('since') Long since
    )

    /** Same shape as `sumByTypeSinceCompleted` but bounded at both ends.
     *  Used by the admin dashboard 24h delta — compare last-24h vs the
     *  prior 24h window. One query per cell instead of loading rows. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.type = :type AND t.status = :status
          AND t.createdAt >= :fromTs AND t.createdAt < :toTs
    """)
    BigDecimal sumByTypeInRange(
        @Param('type')   String type,
        @Param('status') String status,
        @Param('fromTs') Long fromTs,
        @Param('toTs')   Long toTs
    )

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.type = :type AND t.status = :status")
    long countByTypeStatus(@Param('type') String type, @Param('status') String status)

    /** Count by type + status within a rolling window (batch 685). Drives the
     *  admin dashboard's `chargebacks30d` metric — a count of deposits that
     *  were DISPUTED in the last N days, regardless of whether staff have
     *  since cleared them. Distinct from `activeChargebacks` (which only
     *  counts currently-open disputes) — this one is the trend line. */
    @Query("""
        SELECT COUNT(t) FROM Transaction t
        WHERE t.type = :type AND t.status = :status
          AND t.createdAt >= :since
    """)
    long countByTypeStatusSince(@Param('type') String type, @Param('status') String status,
                                @Param('since') Long since)

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.type = :type AND t.status = :status")
    BigDecimal sumByTypeStatus(@Param('type') String type, @Param('status') String status)

    List<Transaction> findByTypeAndStatus(String type, String status)

    /** Paged companion — Spring Data derived-name query with a Pageable
     *  param so admin/sweeper callers can cap the hydration. The
     *  unbounded variant stays for legacy callers. */
    List<Transaction> findByTypeAndStatus(String type, String status, Pageable pageable)

    @Query("SELECT t FROM Transaction t WHERE t.type = :type AND t.status = :status ORDER BY t.createdAt DESC")
    List<Transaction> findByTypeAndStatusOrderByCreatedAtDesc(@Param('type') String type, @Param('status') String status)

    /** Paged companion — see findByTypeAndStatusPaged below; this one
     *  preserves the ORDER BY method-name semantics for callers that
     *  rely on Spring Data's name-based query inference. */
    @Query("SELECT t FROM Transaction t WHERE t.type = :type AND t.status = :status ORDER BY t.createdAt DESC")
    List<Transaction> findByTypeAndStatusOrderByCreatedAtDesc(@Param('type') String type,
                                                              @Param('status') String status,
                                                              Pageable pageable)

    /** Paged variant — admin withdraw + disputed-deposit queues use
     *  this to cap the fetch at 200 rows at SQL level instead of
     *  pulling every historical row and `.take(200)`-ing in memory. */
    @Query("SELECT t FROM Transaction t WHERE t.type = :type AND t.status = :status ORDER BY t.createdAt DESC")
    List<Transaction> findByTypeAndStatusPaged(@Param('type') String type,
                                                @Param('status') String status,
                                                org.springframework.data.domain.Pageable pageable)

    /** Per-wallet per-type sum — used by `ProfileService.buildProfile` to
     *  compute purchase / sale / deposit totals without loading every
     *  transaction row for the user into Groovy memory first. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.walletId = :walletId AND t.type = :type AND (:requireCompleted = false OR t.status = 'COMPLETED')")
    BigDecimal sumByWalletAndType(
        @Param('walletId') Long walletId,
        @Param('type') String type,
        @Param('requireCompleted') boolean requireCompleted
    )

    /** Sum of COMPLETED REFUND amounts that REVERSE A PURCHASE — i.e. refunds
     *  that carry a listingId (trade cancellations via TradeService.refundBuyer
     *  and Trade-Protection payouts), as opposed to DEPOSIT refunds which carry
     *  no listingId. ProfileService subtracts this from the gross PURCHASE sum
     *  so a cancelled+refunded purchase no longer inflates "Total Purchased" or
     *  the "Net" line — the wallet balance was already made whole, the lifetime
     *  stat must reflect that too. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.walletId = :walletId AND t.type = 'REFUND' AND t.status = 'COMPLETED' AND t.listingId IS NOT NULL")
    BigDecimal sumRefundedPurchases(@Param('walletId') Long walletId)

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.walletId = :walletId AND t.type = :type")
    long countByWalletAndType(@Param('walletId') Long walletId, @Param('type') String type)

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.walletId = :walletId AND t.type = :type AND t.status = 'COMPLETED'")
    long countCompletedByWalletAndType(@Param('walletId') Long walletId, @Param('type') String type)

    /** Per-wallet per-type COMPLETED sum within a rolling window — drives
     *  the buyer-side spending summary on the wallet hero (batch 846).
     *  Excludes PENDING/FAILED so the numbers match what actually left the
     *  wallet. Same shape as `sumByTypeInRange` (admin-dashboard) but
     *  scoped to a single wallet + open-ended (since). */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.walletId = :walletId
          AND t.type = :type
          AND t.status = 'COMPLETED'
          AND t.createdAt >= :since
    """)
    BigDecimal sumCompletedByWalletTypeSince(
        @Param('walletId') Long walletId,
        @Param('type')     String type,
        @Param('since')    Long since
    )

    /** Companion count for the windowed spend summary. */
    @Query("""
        SELECT COUNT(t) FROM Transaction t
        WHERE t.walletId = :walletId
          AND t.type = :type
          AND t.status = 'COMPLETED'
          AND t.createdAt >= :since
    """)
    long countCompletedByWalletTypeSince(
        @Param('walletId') Long walletId,
        @Param('type')     String type,
        @Param('since')    Long since
    )

    /** Every COMPLETED movement on one wallet since `since` (epoch ms; 0 =
     *  all time), grouped by type: rows of `[type, count, sum]`. One GROUP BY
     *  per window instead of a sum + count pair per type, so the wallet
     *  page's activity tiles (deposits / sales / purchases / withdrawals /
     *  refunds) cost three queries, not thirty. PENDING and FAILED rows are
     *  excluded so the tiles only ever show money that actually moved. */
    @Query("""
        SELECT t.type, COUNT(t), COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.walletId = :walletId
          AND t.status = 'COMPLETED'
          AND t.createdAt >= :since
        GROUP BY t.type
    """)
    List<Object[]> summarizeCompletedByTypeSince(
        @Param('walletId') Long walletId,
        @Param('since')    Long since
    )

    /** In-flight transactions for a wallet — powers the "pending" chip on
     *  the wallet hero so a user whose balance just dropped by $X sees an
     *  explicit "WITHDRAWAL PENDING · $X" indicator instead of being left
     *  to wonder where the money went. Returns the rows so the UI can list
     *  them; caller sums as needed. */
    @Query("SELECT t FROM Transaction t WHERE t.walletId = :walletId AND t.status = 'PENDING' ORDER BY t.createdAt DESC")
    List<Transaction> findPendingByWallet(@Param('walletId') Long walletId)

    /** Paged companion — bounded by normal-user pending count (small)
     *  but an attacker can queue arbitrarily many PENDING rows; the cap
     *  guarantees the wallet-hero render stays O(pageSize) regardless. */
    @Query("SELECT t FROM Transaction t WHERE t.walletId = :walletId AND t.status = 'PENDING' ORDER BY t.createdAt DESC")
    List<Transaction> findPendingByWallet(@Param('walletId') Long walletId, Pageable pageable)

    /** Stale PENDING deposits — abandoned Stripe Checkout sessions that
     *  never converted to COMPLETED via webhook. The scheduled sweeper
     *  flips them to EXPIRED so the wallet pending-chip doesn't show
     *  ghost balances forever. Stripe Checkout sessions auto-expire at
     *  24h on their side, so we pick `cutoff = now - 48h` for a gentle
     *  margin. */
    @Query("""
        SELECT t FROM Transaction t
         WHERE t.type = :type
           AND t.status = 'PENDING'
           AND t.createdAt < :cutoff
    """)
    List<Transaction> findStalePending(
        @Param('type')   String type,
        @Param('cutoff') Long cutoff
    )

    /** Paged companion — sweeper input; on a busy platform the stale
     *  PENDING set can grow large if Stripe webhooks are delayed. New
     *  sweeper callers should batch via Pageable rather than hydrate
     *  the entire stale set in one tick. */
    @Query("""
        SELECT t FROM Transaction t
         WHERE t.type = :type
           AND t.status = 'PENDING'
           AND t.createdAt < :cutoff
    """)
    List<Transaction> findStalePending(
        @Param('type')   String type,
        @Param('cutoff') Long cutoff,
        Pageable pageable
    )

    /**
     * Atomic claim for the stale-PENDING deposit auto-expire sweep (wave 126).
     * Flips `status` from PENDING→EXPIRED and appends an audit suffix to the
     * description ONLY if the row is still PENDING at UPDATE time. Returns
     * the number of rows touched: 1 = this pod owns the fan-out
     * (DEPOSIT_EXPIRED bell push), 0 = a sibling pod already claimed it OR
     * a Stripe webhook landed concurrently and flipped the row to CONFIRMED
     * / DISPUTED / REFUNDED — in either case the losing path bails before
     * any notify call runs.
     *
     * Multi-pod race protection. Same shape as wave 112
     * (WatchlistAlertRepository.claimForFiring), wave 120
     * (FraudAnalysisService cluster claim), wave 124
     * (ListingRepository.claimEndingSoonNotify), and wave 125
     * (BuyOrderRepository.claimExpire). `findStalePending` is read
     * concurrently by every pod's 4-hourly StripeService.sweepStalePendingDeposits,
     * and both pods would see the SAME `status=PENDING` row. Without an
     * atomic claim each pod independently fires the DEPOSIT_EXPIRED bell
     * push before either pod's `saveAll` commits — user receives "your
     * deposit was auto-expired" TWICE for one expired Stripe session.
     *
     * Also closes a critical OUT-OF-BAND race: if a Stripe webhook lands
     * between the sweeper's `findStalePending` read and the row UPDATE
     * (Stripe's eventual completion of a 48h-stale session), the
     * conditional UPDATE will MISS the row (status != PENDING anymore)
     * and the user keeps their credited balance — pre-fix the sweeper
     * would have overwritten the freshly-CONFIRMED status with EXPIRED
     * AND fired a misleading "your deposit expired" notification on top
     * of the wallet credit.
     */
    @Modifying
    @Query("""
        UPDATE Transaction t
           SET t.status      = 'EXPIRED',
               t.description = CONCAT(COALESCE(t.description, ''),
                                      ' — auto-expired after 48h without completion')
         WHERE t.id     = :id
           AND t.status = 'PENDING'
    """)
    int claimExpirePending(@Param('id') Long id)

    /**
     * Atomic claim for a user-initiated withdrawal self-cancel. Flips a
     * PENDING withdrawal to CANCELLED in a single conditional UPDATE so
     * the caller can detect whether THIS transaction won the race against
     * an admin-side reject ({@link com.sboxmarket.service.AdminService#rejectWithdrawal})
     * landing at the same instant. Returns 1 = this caller owns the
     * refund (credit wallet + write audit row), 0 = the admin reject
     * (or some other terminal flip) already took the row — the caller
     * MUST bail without re-crediting the wallet.
     *
     * Pre-fix StripeService.cancelPendingWithdrawal read the tx via
     * findById, saw status=PENDING, credited wallet.balance += tx.amount,
     * then saved. AdminService.rejectWithdrawal ran the same sequence at
     * the same instant on a different connection: both saw PENDING, both
     * credited the wallet, and the wallet ended up with 2 × tx.amount
     * credited from one withdrawal — the user got their money back twice
     * while only one tx flipped to a terminal state (whichever path
     * committed last to the same tx row).
     *
     * Same conditional-UPDATE shape as claimExpirePending above, wave 124
     * (ListingRepository.claimEndingSoonNotify), wave 125
     * (BuyOrderRepository.claimExpire), and wave 126
     * (sweepStalePendingDeposits multi-pod claim).
     */
    @Modifying
    @Query("""
        UPDATE Transaction t
           SET t.status = 'CANCELLED'
         WHERE t.id     = :id
           AND t.status = 'PENDING'
    """)
    int claimCancelPendingWithdrawal(@Param('id') Long id)

    /**
     * Atomic claim for a Stripe `transfer.reversed` reconciliation. Flips a
     * COMPLETED withdrawal to REVERSED in a single conditional UPDATE so the
     * webhook handler ({@link com.sboxmarket.service.StripeService#handleTransferReversed})
     * re-credits the wallet EXACTLY ONCE. Returns 1 = this delivery owns the
     * re-credit, 0 = a sibling pod / a redelivered event already reconciled it
     * (the caller MUST bail without re-crediting).
     *
     * Without this, the bug was the inverse of every other money path: a
     * settled payout that Stripe later reverses (closed bank account, Connect
     * clawback) returned the funds to the platform balance, but nothing
     * re-credited the user's wallet — silent permanent fund loss. There was no
     * `transfer.reversed` webhook case at all. This claim is the once-only
     * guard for the new handler; same conditional-UPDATE shape as
     * claimCancelPendingWithdrawal / claimExpirePending. Matches both the
     * legacy `WITHDRAW` and canonical `WITHDRAWAL` type spellings.
     */
    @Modifying
    @Query("""
        UPDATE Transaction t
           SET t.status      = 'REVERSED',
               t.updatedAt   = :now,
               t.description = CONCAT(COALESCE(t.description, ''),
                                      ' — REVERSED (Stripe payout returned; wallet re-credited)')
         WHERE t.id     = :id
           AND t.status = 'COMPLETED'
           AND t.type   IN ('WITHDRAW', 'WITHDRAWAL')
    """)
    int claimReverseWithdrawal(@Param('id') Long id, @Param('now') long now)

    /**
     * Atomic claim for an admin withdrawal REJECTION (batch 1082). Flips a
     * PENDING withdrawal to FAILED in ONE conditional UPDATE so {@link
     * com.sboxmarket.service.AdminService#rejectWithdrawal} issues the wallet
     * refund EXACTLY ONCE. Returns 1 = this caller owns the refund, 0 = a
     * concurrent reject / the user's self-cancel / any other terminal flip
     * already resolved the row (the caller MUST bail WITHOUT crediting).
     *
     * The symmetric half of {@link #claimCancelPendingWithdrawal} (wave 127).
     * That fix made the USER-side cancel atomic but left the ADMIN-side reject
     * as the old findById-read + unconditional `balance += amount` credit — so
     * a reject racing a cancel (or two concurrent rejects, or reject racing
     * approve) STILL double-refunded the wallet: both paths read the same
     * PENDING row and both credited, while only the last status-overwrite
     * stuck. Transaction has no @Version, and the wallet @Version only catches
     * the read-before-commit interleaving, so this conditional UPDATE is the
     * authoritative gate. Matches both WITHDRAW/WITHDRAWAL spellings; stamps
     * the rejection reason + updatedAt in the same atomic write.
     */
    @Modifying
    @Query("""
        UPDATE Transaction t
           SET t.status      = 'FAILED',
               t.updatedAt   = :now,
               t.description = CONCAT(COALESCE(t.description, ''), :suffix)
         WHERE t.id     = :id
           AND t.status = 'PENDING'
           AND t.type   IN ('WITHDRAW', 'WITHDRAWAL')
    """)
    int claimRejectWithdrawal(@Param('id') Long id, @Param('now') long now, @Param('suffix') String suffix)

    /**
     * Atomic claim for an admin withdrawal APPROVAL (batch 1082). Flips a
     * PENDING withdrawal to COMPLETED in ONE conditional UPDATE so the
     * approval's terminal flip + side-effects (owner notification + payout
     * email + audit) fire EXACTLY ONCE, and so an approve racing a
     * reject/cancel can't overwrite an already-refunded FAILED/CANCELLED row
     * back to COMPLETED (which would falsely show a refunded withdrawal as
     * paid out). Returns 1 = this caller owns the approval, 0 = already
     * terminal (bail). Approve moves no money (the Stripe Transfer fired at
     * request time), so this is the consistency / duplicate-notification /
     * status-integrity sibling of claimRejectWithdrawal &
     * claimCancelPendingWithdrawal. Sets the payout reference (kept when the
     * arg is null) + updatedAt in the same write.
     */
    @Modifying
    @Query("""
        UPDATE Transaction t
           SET t.status          = 'COMPLETED',
               t.stripeReference = COALESCE(:payoutRef, t.stripeReference),
               t.updatedAt       = :now,
               t.description     = CONCAT(COALESCE(t.description, ''), ' — approved by admin')
         WHERE t.id     = :id
           AND t.status = 'PENDING'
           AND t.type   IN ('WITHDRAW', 'WITHDRAWAL')
    """)
    int claimApproveWithdrawal(@Param('id') Long id, @Param('payoutRef') String payoutRef, @Param('now') long now)

    /** Sum of withdrawal amounts the wallet has requested within a
     *  rolling window — drives the daily withdrawal cap enforced at
     *  the /api/wallet/withdraw controller (batch 357). Includes
     *  PENDING + COMPLETED statuses so a pending payout counts toward
     *  the cap too (otherwise an attacker could queue 100 pending
     *  withdrawals). Excludes REJECTED + CANCELLED + FAILED — none of
     *  those resulted in funds leaving the wallet. FAILED is the status
     *  AdminService.rejectWithdrawal stamps when staff reject a payout
     *  and refund the wallet (REJECTED is a legacy spelling no code
     *  emits); without excluding FAILED a staff-rejected withdrawal
     *  whose funds were fully returned still burned the user's 24h cap.
     *  Supports legacy `WITHDRAW` + canonical `WITHDRAWAL`. (2026-05-20) */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.walletId = :walletId
          AND t.type IN ('WITHDRAW', 'WITHDRAWAL')
          AND t.status NOT IN ('REJECTED', 'CANCELLED', 'FAILED')
          AND t.createdAt >= :since
    """)
    BigDecimal sumWithdrawalsSince(@Param('walletId') Long walletId,
                                    @Param('since') Long since)

    /** Active-chargeback gate (batch 465). Counts deposits that Stripe
     *  has flagged as DISPUTED for this wallet. Used by the withdraw
     *  controller to refuse new withdrawals while there's an unresolved
     *  chargeback — otherwise an attacker could deposit on a stolen
     *  card, dispute it via the bank, and withdraw before we notice.
     *  Cleared once staff manually flips the DISPUTED row to REFUNDED
     *  or CANCELLED in the admin panel. */
    @Query("""
        SELECT COUNT(t) FROM Transaction t
        WHERE t.walletId = :walletId
          AND t.type = 'DEPOSIT'
          AND t.status = 'DISPUTED'
    """)
    long countActiveDisputedDeposits(@Param('walletId') Long walletId)

    /** Rolling-window deposit total for the daily-deposit-cap gate
     *  (batch 497). Counts money that's already landed (COMPLETED) plus
     *  money currently in-flight (PENDING — Stripe Checkout session
     *  created but not yet confirmed) so an attacker can't sidestep the
     *  cap by queuing a dozen parallel sessions. DISPUTED also counts
     *  (it was real money that hit the wallet, even if a chargeback is
     *  open). Excludes FAILED / EXPIRED / CANCELLED — those never
     *  credited the wallet. */
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.walletId = :walletId
          AND t.type = 'DEPOSIT'
          AND t.status IN ('COMPLETED', 'PENDING', 'DISPUTED')
          AND t.createdAt >= :since
    """)
    BigDecimal sumDepositsSince(@Param('walletId') Long walletId,
                                 @Param('since') Long since)

    /** Earliest-row timestamp in the 24h withdrawal window (batch 753).
     *  When the daily cap is hit, the UI uses this to compute "resets
     *  in X hours" — `oldestAt + 24h - now` — instead of the misleading
     *  "try again in 24h" blanket message. Null when the window is empty. */
    @Query("""
        SELECT MIN(t.createdAt) FROM Transaction t
        WHERE t.walletId = :walletId
          AND t.type IN ('WITHDRAW', 'WITHDRAWAL')
          AND t.status NOT IN ('REJECTED', 'CANCELLED', 'FAILED')
          AND t.createdAt >= :since
    """)
    Long earliestWithdrawalSince(@Param('walletId') Long walletId,
                                  @Param('since') Long since)

    /** Same as above, for the deposit cap. */
    @Query("""
        SELECT MIN(t.createdAt) FROM Transaction t
        WHERE t.walletId = :walletId
          AND t.type = 'DEPOSIT'
          AND t.status IN ('COMPLETED', 'PENDING', 'DISPUTED')
          AND t.createdAt >= :since
    """)
    Long earliestDepositSince(@Param('walletId') Long walletId,
                               @Param('since') Long since)

    /** COMPLETED withdrawal count for the wallet — drives the Profile
     *  "Withdrawals" stat. Accepts both the canonical `WITHDRAWAL` and
     *  the legacy `WITHDRAW` spelling that StripeService.requestWithdraw
     *  still emits, so a user whose rows are stored under the legacy
     *  type isn't silently shown a zero count. Same dual-name posture
     *  the cap query (`sumWithdrawalsInWindow` / `earliestWithdrawalSince`)
     *  uses. */
    @Query("""
        SELECT COUNT(t) FROM Transaction t
        WHERE t.walletId = :walletId
          AND t.type IN ('WITHDRAW', 'WITHDRAWAL')
          AND t.status = 'COMPLETED'
    """)
    long countCompletedWithdrawalsByWallet(@Param('walletId') Long walletId)

    /** Sum of completed REFUND rows already issued against a given deposit
     *  (matched by "Refund of deposit #<id>" substring stamped at refund
     *  time). Used to enforce the cumulative-refund cap in
     *  {@code StripeService.refundDeposit} so multiple partial refunds
     *  against the same deposit can't exceed the original amount —
     *  critical for legacy dev-mode (dev_*) deposits and any other path
     *  that doesn't ride Stripe's own over-refund protection. */
    // Anchored match. The old trailing-'%' (`'%...#', :depositId, '%'`) over-
    // counted: `#1` also matched `#10`, `#100`, … so sumRefundsByDeposit(1)
    // folded in refunds of deposits 10/100/… The two stamped formats are
    // "Refund of deposit #<id>" (id at end, StripeService:432) and
    // "Refund of deposit #<id> (via Stripe Dashboard)" (id + space + text,
    // :942), so match (ends-with-#id) OR (#id followed by a space). Neither
    // matches #10 for depositId=1. (A real refundedDepositId FK is the proper
    // long-term fix — see AUDIT-BACKLOG.md.)
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.type = 'REFUND'
          AND t.status = 'COMPLETED'
          AND (t.description LIKE CONCAT('%Refund of deposit #', :depositId)
               OR t.description LIKE CONCAT('%Refund of deposit #', :depositId, ' %'))
    """)
    BigDecimal sumRefundsByDeposit(@Param('depositId') Long depositId)

    /** Seller payouts card — a wallet's withdrawals, newest first, any status. */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.walletId = :walletId AND t.type IN ('WITHDRAW', 'WITHDRAWAL')
        ORDER BY t.createdAt DESC
    """)
    List<Transaction> findWithdrawalsByWallet(@Param('walletId') Long walletId, Pageable pageable)
}
