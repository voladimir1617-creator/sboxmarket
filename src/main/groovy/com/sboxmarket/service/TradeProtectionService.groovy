package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeProtection
import com.sboxmarket.model.Transaction
import com.sboxmarket.repository.TradeProtectionRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Trade Protection — CSFloat's signature buyer-side safety add-on.
 *
 * Protection is an OPTIONAL paid extra. When a buyer enables it on a
 * trade they pay a small fee ({@code 2%} of the item price, floored at
 * {@code $0.25}) out of their wallet into platform revenue, and one
 * {@link TradeProtection} row is created tied 1:1 to the trade.
 *
 * If the trade later fails through no fault of the buyer, the buyer is
 * made whole the FULL item price with no support ticket. Exactly one
 * payout ever happens per failed sale:
 *
 *   - On a DISPUTE, escrow is still frozen, so {@code autoClaim} fires
 *     from TradeService.dispute() and pays the cover straight to the
 *     buyer's wallet — the protection IS the refund.
 *   - On a CANCEL / seller-timeout auto-cancel / banned-seller cancel,
 *     TradeService already refunds the escrowed item price via its own
 *     refundBuyer(), so {@code expire} fires instead and the cover
 *     lapses unused — claiming as well would double-pay the buyer.
 *     (When a cancel follows a dispute the cover is already CLAIMED;
 *     {@code expire} is a correct no-op on a non-ACTIVE protection.)
 *
 * A trade that completes normally (VERIFIED) likewise leaves the
 * protection ACTIVE → EXPIRED via {@code expire}; the fee is kept as
 * revenue.
 *
 * Money model note: the platform has no segregated revenue wallet (the
 * existing 2% trade fee is simply withheld from the seller credit), so
 * the protection fee is taken as a PURCHASE-type debit on the buyer's
 * wallet — the fee leaves circulation, which IS the revenue capture.
 * A claim payout is a REFUND-type credit back to the same wallet.
 */
@Service
@Slf4j
class TradeProtectionService {

    /** Protection fee as a fraction of the item price. 2% — deliberately
     *  the same headline rate as the trade fee so it reads as familiar,
     *  but it is a SEPARATE, buyer-paid charge and does not touch the
     *  existing seller-side 2% fee model. */
    static final BigDecimal PROTECTION_RATE = new BigDecimal('0.02')

    /** Floor on the protection fee. A 2% cut of a $1 item is a rounding
     *  artefact; $0.25 keeps cheap-item cover economically meaningful. */
    static final BigDecimal MIN_FEE = new BigDecimal('0.25')

    @Autowired TradeProtectionRepository tradeProtectionRepository
    @Autowired TradeRepository tradeRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) AuditService auditService
    @Autowired(required = false) BanGuard banGuard

    // ── Quote ────────────────────────────────────────────────────────

    /**
     * The protection fee for a given item price: {@code 2%} floored at
     * {@code $0.25}, rounded half-up to cents. Pure function — used by
     * the quote endpoint and by {@code enable}. A null / non-positive
     * price yields the floor fee rather than throwing, so the checkout
     * UI can render a quote before a price is settled.
     */
    BigDecimal quote(BigDecimal itemPrice) {
        if (itemPrice == null || itemPrice <= BigDecimal.ZERO) return MIN_FEE
        def pct = (itemPrice * PROTECTION_RATE).setScale(2, BigDecimal.ROUND_HALF_UP)
        pct < MIN_FEE ? MIN_FEE : pct
    }

    // ── Queries ──────────────────────────────────────────────────────

    /** The protection record for a trade, or null if the trade is
     *  unprotected. */
    TradeProtection findForTrade(Long tradeId) {
        if (tradeId == null) return null
        tradeProtectionRepository.findByTradeId(tradeId)
    }

    /** True when a trade is protected (has any protection record,
     *  regardless of status). Surfaced as the `protected` flag on the
     *  Trade payload. */
    boolean isProtected(Long tradeId) {
        tradeId != null && tradeProtectionRepository.existsByTradeId(tradeId)
    }

    // ── Enable ───────────────────────────────────────────────────────

    /**
     * Turn on protection for a trade. Only the trade's buyer may do
     * this, and only while the trade is still in escrow (a non-terminal
     * state) — protecting an already VERIFIED / CANCELLED trade is
     * meaningless. Charges the protection fee from the buyer's wallet
     * and creates the ACTIVE protection record.
     *
     * Idempotent-ish: a second call on an already-protected trade is
     * rejected with PROTECTION_EXISTS rather than double-charging.
     */
    @Transactional
    TradeProtection enable(Long buyerUserId, Long tradeId) {
        // Banned buyers must not be able to enable protection. Every sibling
        // money-path on the buyer surface (TradeService.cancel/dispute/buy)
        // gates writes behind banGuard.assertNotBanned; this call site was
        // missed when BanGuard was extracted. The hole is exploitable:
        // (a) the trade's seller-timeout sweeper auto-cancels stale trades
        //     and TradeService.cancel runs autoClaim on a protected trade,
        //     paying the buyer the FULL item price out of platform funds —
        //     a banned buyer who enables protection on a live trade gets a
        //     wallet credit path the ban was supposed to close,
        // (b) an account-takeover that flips protection on every live trade
        //     of a freshly-banned account drains MIN_FEE × N from the
        //     victim's wallet before staff can lock the account, and
        // (c) the controller has no guard either (TradeProtectionController
        //     just enforces "signed-in"), so the only place to land it is
        //     here. Throws the same ForbiddenException the rest of the
        //     codebase uses so the existing 403 handler renders the ban
        //     reason verbatim.
        banGuard?.assertNotBanned(buyerUserId)
        def trade = tradeRepository.findById(tradeId)
            .orElseThrow { new NotFoundException("Trade", tradeId) }
        if (trade.buyerUserId == null || trade.buyerUserId != buyerUserId) {
            throw new ForbiddenException("Only the buyer can protect this trade")
        }
        if (trade.state in ['VERIFIED', 'CANCELLED', 'DISPUTED']) {
            throw new BadRequestException("TRADE_NOT_PROTECTABLE",
                "Protection can only be added while the trade is still in escrow.")
        }
        if (tradeProtectionRepository.existsByTradeId(tradeId)) {
            throw new BadRequestException("PROTECTION_EXISTS",
                "This trade is already protected.")
        }

        def fee = quote(trade.price)

        // Charge the fee from the buyer's wallet. The fee leaving the
        // wallet IS the revenue capture — there is no segregated
        // platform wallet in this codebase (the 2% trade fee works the
        // same way, withheld rather than moved).
        if (trade.buyerWalletId == null) {
            throw new BadRequestException("NO_WALLET",
                "No wallet on file for this trade — cannot charge the protection fee.")
        }
        def wallet = walletRepository.findById(trade.buyerWalletId).orElse(null)
        if (wallet == null) {
            throw new BadRequestException("NO_WALLET",
                "Buyer wallet not found — cannot charge the protection fee.")
        }
        if (Boolean.TRUE.equals(wallet.frozen)) {
            throw new BadRequestException("WALLET_FROZEN",
                "Your wallet is frozen — protection can't be charged right now.")
        }
        // Deposit dispute-hold gate (fraud-control completeness audit). The
        // protection fee is a real wallet debit, and EVERY other wallet-debit
        // path — PurchaseService.buy, OfferService.makeOffer, BidService.placeBid
        // / buyNowAuction / settle, BuyOrderService.create/update,
        // WalletController.withdraw — blocks outflows while the buyer has an
        // unresolved deposit chargeback (can't segregate disputed vs clean
        // dollars). enable() was the lone omission, leaving the fraud-control
        // matrix non-uniform. Same PURCHASE_DISPUTE_HOLD code + rationale.
        if (transactionRepository != null) {
            long disputed = transactionRepository.countActiveDisputedDeposits(wallet.id)
            if (disputed > 0L) {
                throw new BadRequestException("PURCHASE_DISPUTE_HOLD",
                    "Trade Protection is paused while you have ${disputed} unresolved deposit " +
                    "dispute${disputed == 1 ? '' : 's'} on file. It'll resume once the chargeback " +
                    "closes or staff clear the hold.")
            }
        }
        if (wallet.balance < fee) {
            throw new BadRequestException("INSUFFICIENT_BALANCE",
                "Not enough wallet balance for the \$${fee} protection fee.")
        }
        wallet.balance = wallet.balance - fee
        walletRepository.save(wallet)
        transactionRepository.save(new Transaction(
            walletId:        wallet.id,
            type:            'PURCHASE',
            status:          'COMPLETED',
            amount:          fee,
            currency:        wallet.currency,
            stripeReference: 'trade_protection',
            description:     "Trade Protection on ${trade.itemName ?: ('trade #' + tradeId)}",
            listingId:       trade.listingId
        ))

        def now = System.currentTimeMillis()
        def protection = new TradeProtection(
            tradeId:        tradeId,
            buyerUserId:    buyerUserId,
            feeAmount:      fee,
            coverageAmount: trade.price,
            status:         TradeProtection.ACTIVE,
            createdAt:      now,
            updatedAt:      now
        )
        tradeProtectionRepository.save(protection)

        notificationService?.safePush(buyerUserId, 'TRADE_PROTECTED',
            "Protection added · ${trade.itemName ?: 'your trade'}",
            "If this trade fails you'll be auto-refunded \$${trade.price} — no support ticket needed.",
            tradeId, '/profile?tab=trades')
        // Subject = buyer (the wallet that just moved), NOT the seller.
        // Convention pinned by the sibling CLAIMED / REVERSED audit writes
        // a few methods down and locked in by the /security-activity feed
        // (ProfileController:267): every money-impact event on the buyer's
        // wallet uses subjectUserId = the wallet owner so bySubject(buyer)
        // returns the row. Tagging the seller here mis-routes a buyer-
        // wallet debit into the SELLER's audit-by-subject filter — admins
        // searching the buyer's audit trail for "where did this $X
        // disappear" come up empty, and an account-takeover that flipped
        // protection on every active trade to drain MIN_FEE × N out of
        // the buyer's wallet leaves no row on the victim's own audit
        // filter. The trade resource id is still on the row, so the
        // counterparty link survives the by-resource view.
        auditService?.log('TRADE_PROTECTION_ENABLED', buyerUserId, buyerUserId, tradeId,
            "Protection enabled (fee \$${fee}, cover \$${trade.price})")
        log.info("Trade #{} protected by buyer {} — fee \${}, cover \${}",
            tradeId, buyerUserId, fee, trade.price)
        protection
    }

    // ── Claim / expire (auto-resolution) ─────────────────────────────

    /**
     * Auto-claim a trade's protection — the trade failed through no
     * fault of the buyer, so refund the buyer the full covered item
     * price. Wired into TradeService's cancel / auto-cancel / dispute
     * paths so a protected buyer is made whole the moment the trade
     * dies, without filing a support ticket.
     *
     * No-op (returns null) when the trade has no protection or the
     * protection is already resolved — safe to call unconditionally
     * from every trade-failure path. Best-effort by design: callers
     * invoke it inside a try/catch so a protection hiccup never rolls
     * back the underlying trade transition.
     *
     * REQUIRES_NEW propagation is LOAD-BEARING — every caller is a
     * money-path @Transactional method (TradeService.dispute) that
     * wraps this in try/catch with explicit "must not roll back the
     * parent" intent. With default REQUIRED propagation a failing
     * wallet.save() / transactionRepository.save() / protection.save()
     * inside this method would JOIN the caller's tx and Spring's
     * transactional proxy would mark the SHARED tx rollback-only the
     * moment the inner exception escaped — the caller's try/catch
     * absorbs the throw, autoClaim returns normally, but the parent
     * commit then throws UnexpectedRollbackException and the
     * DISPUTED flip + dispute audit + admin fan-out all roll back
     * silently while the call appears to succeed. Catastrophic for a
     * money-path call. REQUIRES_NEW gives the protection payout its
     * own tx so a failure here can roll back ONLY the inner protection
     * write — the dispute itself is unaffected, exactly matching the
     * "best-effort, must not roll back the parent" contract every
     * caller relies on. Mirrors the WatchlistAlertService.sweepForItem
     * fix (d3a3df7).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    TradeProtection autoClaim(Long tradeId, String reason) {
        // Pessimistic-locked read so a concurrent cancel-path read-then-flip
        // (see lockAndExpireIfActiveOrReportClaimed) and any sibling autoClaim
        // serialise on the row. Without the lock, the cancel's unlocked
        // alreadyPaidByProtection check could read status=ACTIVE while
        // autoClaim's REQUIRES_NEW commit was still in flight, then both
        // cancel.refundBuyer AND the autoClaim payout would credit the buyer
        // wallet for the SAME trade — double payout. The lock pins the
        // happens-before so cancel either sees CLAIMED (and skips refund)
        // or autoClaim sees the EXPIRED marker cancel laid down under its
        // own lock.
        def protection = tradeProtectionRepository.findByTradeIdForUpdate(tradeId)
        if (protection == null) return null
        if (protection.status != TradeProtection.ACTIVE) {
            // Already claimed or expired — idempotent no-op. The EXPIRED
            // branch also covers the case where a concurrent cancel ran
            // first under the lock and consumed the cover via
            // lockAndExpireIfActiveOrReportClaimed: autoClaim must not pay
            // out because cancel already refunded the buyer.
            return protection
        }
        def trade = tradeRepository.findById(tradeId).orElse(null)

        // Refund the covered amount to the buyer's wallet. This is the
        // protection payout — SEPARATE from any item-price refund the
        // trade-cancel path itself issues. (cancel() refunds escrowed
        // funds; protection only ever pays out on a path where the
        // buyer did NOT already get their money back, see TradeService
        // wiring — auto-claim is fired only from the genuine-loss
        // paths, never the plain buyer-changed-mind cancel.)
        //
        // `credited` tracks whether the wallet was actually moved. The
        // status flip to CLAIMED stays unconditional (a manual-payout
        // warning is logged on the failure branches), but the buyer
        // notification body MUST honour reality: telling the buyer
        // "\$X has been refunded to your wallet" when their wallet row
        // is missing / detached from the trade is a customer-facing lie
        // that lands a support ticket — they check the wallet, see zero
        // new credit, and rightfully complain. Phrase the message to
        // match what actually happened: "credited" vs "queued for manual
        // payout while support reconciles".
        boolean credited = false
        def buyerWalletId = trade?.buyerWalletId
        if (buyerWalletId != null) {
            def wallet = walletRepository.findById(buyerWalletId).orElse(null)
            if (wallet != null) {
                wallet.balance = wallet.balance + protection.coverageAmount
                walletRepository.save(wallet)
                transactionRepository.save(new Transaction(
                    walletId:        wallet.id,
                    type:            'REFUND',
                    status:          'COMPLETED',
                    amount:          protection.coverageAmount,
                    currency:        wallet.currency,
                    stripeReference: 'trade_protection_claim',
                    description:     "Trade Protection payout — ${trade?.itemName ?: ('trade #' + tradeId)}",
                    listingId:       trade?.listingId
                ))
                credited = true
            } else {
                log.warn("Trade #{} protection claimed but buyer wallet {} not found — " +
                    "manual payout required for \${}", tradeId, buyerWalletId, protection.coverageAmount)
            }
        } else {
            log.warn("Trade #{} protection claimed but no buyer wallet on the trade — " +
                "manual payout required for \${}", tradeId, protection.coverageAmount)
        }

        protection.status      = TradeProtection.CLAIMED
        protection.claimReason = (reason ?: 'Trade failed').take(255)
        protection.resolvedAt  = System.currentTimeMillis()
        protection.updatedAt   = protection.resolvedAt
        tradeProtectionRepository.save(protection)

        if (protection.buyerUserId != null) {
            def body = credited
                ? "\$${protection.coverageAmount} has been refunded to your wallet — ${protection.claimReason}."
                : "\$${protection.coverageAmount} is owed to you and queued for manual payout by support — ${protection.claimReason}."
            notificationService?.safePush(protection.buyerUserId, 'TRADE_PROTECTION_CLAIMED',
                "Protection paid out · ${trade?.itemName ?: 'your trade'}",
                body,
                tradeId, '/wallet')
        }
        auditService?.log('TRADE_PROTECTION_CLAIMED', null, protection.buyerUserId, tradeId,
            (credited
                ? "Protection claim paid out \$${protection.coverageAmount}: ${protection.claimReason}"
                : "Protection claim approved but payout SKIPPED (no buyer wallet) \$${protection.coverageAmount}: ${protection.claimReason}"))
        // When the wallet credit was skipped the buyer is owed money the
        // platform hasn't paid — flag it as a filterable reconciliation event
        // so ops can settle it by hand, not just a buried log.warn.
        if (!credited) {
            auditService?.log(AuditService.MANUAL_PAYOUT_REQUIRED, null, protection.buyerUserId, tradeId,
                "Trade Protection payout owed but skipped on trade #${tradeId}: \$${protection.coverageAmount} " +
                "to buyer ${protection.buyerUserId} (no buyer wallet). Manual payout required.")
        }
        log.info("Trade #{} protection CLAIMED — \${} refunded to buyer {} ({})",
            tradeId, protection.coverageAmount, protection.buyerUserId, protection.claimReason)
        protection
    }

    /**
     * Reverse a CLAIMED protection payout — staff has released the trade
     * as VALID, overturning the premise the auto-claim was paid on.
     *
     * A protected buyer who disputes is auto-refunded the full item
     * price immediately (see {@code autoClaim}). If staff then force-
     * releases that disputed trade — ruling the seller actually
     * delivered — the seller is paid {@code price - fee} from escrow.
     * Without reversing the claim the buyer keeps BOTH the item and the
     * protection refund while the seller is also paid: escrow only ever
     * held the price once, so the platform would eat a full item price.
     * This is the {@code release}-path twin of the
     * {@code alreadyPaidByProtection} guard on TradeService's cancel path.
     *
     * Claws the {@code coverageAmount} back from the buyer's wallet,
     * clamped at the available balance — a buyer who already spent the
     * payout is taken to zero (never negative) and the shortfall is
     * logged for manual staff clawback. Flips the protection to EXPIRED
     * with a "claim reversed" reason (no separate REVERSED state — the
     * cover is resolved and the reason field records what happened).
     *
     * No-op when the trade is unprotected or the protection is not in
     * CLAIMED state — safe to call from the release path unconditionally.
     *
     * REQUIRES_NEW propagation is LOAD-BEARING — TradeService.release
     * wraps this call in try/catch with explicit "Best-effort: a
     * protection hiccup must not roll back the seller credit + VERIFIED
     * transition above". With default REQUIRED propagation an inner
     * wallet.save() failure would JOIN the release tx and Spring's
     * proxy would mark it rollback-only — the seller credit, the
     * VERIFIED state flip, and the auto-release email would all roll
     * back while the call appears to succeed. REQUIRES_NEW isolates
     * the protection clawback in its own tx so it can fail without
     * poisoning the parent release. Mirrors the
     * WatchlistAlertService.sweepForItem fix (d3a3df7).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    TradeProtection reverseClaim(Long tradeId, String reason) {
        // PESSIMISTIC_WRITE lock, mirroring autoClaim/lockAndExpireIfActiveOrReportClaimed.
        // reverseClaim is check-then-act (read status == CLAIMED, then debit the
        // buyer the coverageAmount). With a plain findByTradeId, two concurrent
        // reversals (admin double-click on "release", or a manual release racing
        // a sweeper path — each its own REQUIRES_NEW tx) both read CLAIMED, both
        // pass the guard, and both claw back the cover → double debit. The row
        // lock serializes them so the second observes status != CLAIMED and no-ops.
        def protection = tradeProtectionRepository.findByTradeIdForUpdate(tradeId)
        if (protection == null) return null
        if (protection.status != TradeProtection.CLAIMED) return protection
        def trade = tradeRepository.findById(tradeId).orElse(null)

        def cover = protection.coverageAmount ?: BigDecimal.ZERO
        def buyerWalletId = trade?.buyerWalletId
        if (buyerWalletId != null && cover > BigDecimal.ZERO) {
            def wallet = walletRepository.findById(buyerWalletId).orElse(null)
            if (wallet != null) {
                // Clamp at the available balance — never drive the wallet
                // negative. A buyer who already spent the payout leaves a
                // shortfall the platform must chase manually.
                def avail = (wallet.balance != null && wallet.balance > BigDecimal.ZERO) ? wallet.balance : BigDecimal.ZERO
                def debit = cover < avail ? cover : avail
                if (debit > BigDecimal.ZERO) {
                    wallet.balance = wallet.balance - debit
                    walletRepository.save(wallet)
                    transactionRepository.save(new Transaction(
                        walletId:        wallet.id,
                        type:            'PURCHASE',
                        status:          'COMPLETED',
                        amount:          debit,
                        currency:        wallet.currency,
                        stripeReference: 'trade_protection_reversal',
                        description:     "Trade Protection claim reversed — ${trade?.itemName ?: ('trade #' + tradeId)}",
                        listingId:       trade?.listingId
                    ))
                }
                def shortfall = cover - debit
                if (shortfall > BigDecimal.ZERO) {
                    log.warn("Trade #{} protection reversal short by \${} — buyer wallet {} had only \${}; " +
                        "manual clawback required", tradeId, shortfall, wallet.id, avail)
                    auditService?.log(AuditService.MANUAL_PAYOUT_REQUIRED, null, protection.buyerUserId, tradeId,
                        "Trade Protection clawback short on trade #${tradeId}: \$${shortfall} of \$${cover} " +
                        "could not be recovered (buyer wallet ${wallet.id} had only \$${avail}). Manual clawback required.")
                }
            } else {
                log.warn("Trade #{} protection reversal — buyer wallet {} not found; " +
                    "manual clawback required for \${}", tradeId, buyerWalletId, cover)
                auditService?.log(AuditService.MANUAL_PAYOUT_REQUIRED, null, protection.buyerUserId, tradeId,
                    "Trade Protection clawback skipped on trade #${tradeId}: buyer wallet ${buyerWalletId} " +
                    "not found. \$${cover} manual clawback required.")
            }
        } else if (cover > BigDecimal.ZERO) {
            log.warn("Trade #{} protection reversal — no buyer wallet on the trade; " +
                "manual clawback required for \${}", tradeId, cover)
            auditService?.log(AuditService.MANUAL_PAYOUT_REQUIRED, null, protection.buyerUserId, tradeId,
                "Trade Protection clawback skipped on trade #${tradeId}: no buyer wallet on the trade. " +
                "\$${cover} manual clawback required.")
        }

        protection.status      = TradeProtection.EXPIRED
        protection.claimReason = ("Claim reversed — ${reason ?: 'trade released as valid'}").take(255)
        protection.resolvedAt  = System.currentTimeMillis()
        protection.updatedAt   = protection.resolvedAt
        tradeProtectionRepository.save(protection)

        if (protection.buyerUserId != null) {
            notificationService?.safePush(protection.buyerUserId, 'TRADE_PROTECTION_REVERSED',
                "Protection claim reversed · ${trade?.itemName ?: 'your trade'}",
                "\$${cover} was reclaimed from your wallet — the trade was released as valid.",
                tradeId, '/wallet')
        }
        auditService?.log('TRADE_PROTECTION_REVERSED', null, protection.buyerUserId, tradeId,
            "Protection claim reversed, \$${cover} reclaimed: ${reason ?: 'trade released as valid'}")
        log.info("Trade #{} protection claim REVERSED — \${} reclaimed from buyer {} ({})",
            tradeId, cover, protection.buyerUserId, reason ?: 'trade released as valid')
        protection
    }

    /**
     * Lapse a trade's protection — the trade completed normally
     * (VERIFIED), so the cover is no longer needed and the fee is kept
     * as revenue. Wired into TradeService's release path.
     *
     * No-op when the trade has no protection or the protection has
     * already left ACTIVE. Best-effort, like {@code autoClaim}.
     *
     * REQUIRES_NEW propagation is LOAD-BEARING — every caller
     * (TradeService.release, TradeService.cancel,
     * autoCancelStaleSellerTrade, autoCancelBannedSellerTrade) wraps
     * this in try/catch with explicit "Best-effort so a protection
     * hiccup can't roll back the refund/CANCELLED/VERIFIED transition
     * above". With default REQUIRED propagation a failing
     * protection.save() would JOIN the caller's tx and Spring's
     * transactional proxy would mark it rollback-only — the parent's
     * money movement + state flip + audit + fan-out all roll back
     * while the call appears to succeed. REQUIRES_NEW gives the
     * expire its own tx so a failure here can roll back ONLY the
     * inner protection-status write, leaving the parent intact.
     * Mirrors the WatchlistAlertService.sweepForItem fix (d3a3df7).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    TradeProtection expire(Long tradeId) {
        def protection = tradeProtectionRepository.findByTradeId(tradeId)
        if (protection == null) return null
        if (protection.status != TradeProtection.ACTIVE) return protection
        protection.status     = TradeProtection.EXPIRED
        protection.resolvedAt = System.currentTimeMillis()
        protection.updatedAt  = protection.resolvedAt
        tradeProtectionRepository.save(protection)
        log.info("Trade #{} protection EXPIRED — trade completed normally, fee kept", tradeId)
        protection
    }

    /**
     * Cancel-path arbiter — runs inside the CALLER'S transaction (default
     * REQUIRED) so the pessimistic row lock acquired here is held until
     * the caller's tx commits. Atomic "decide refund vs skip, and consume
     * the cover" for {@link com.sboxmarket.service.TradeService#cancel}.
     *
     * Returns {@code true} when the protection was already CLAIMED at lock-
     * acquisition time — autoClaim has already paid the buyer, so cancel
     * MUST skip refundBuyer to avoid a double payout.
     *
     * Returns {@code false} (and atomically flips ACTIVE → EXPIRED inside
     * the caller's tx) when the cover was still ACTIVE — cancel will refund
     * the buyer from escrow, so the cover must be consumed here under the
     * lock to prevent a concurrent {@link #autoClaim} from re-paying the
     * same trade after cancel commits. Without the inline flip, autoClaim
     * would BLOCK on the row lock waiting for cancel to commit, then
     * re-read status=ACTIVE and pay out — a second buyer credit for the
     * same cancellation. The inline EXPIRED flip means autoClaim's locked
     * re-read sees status != ACTIVE and bails (its existing idempotency
     * gate).
     *
     * Returns {@code false} for an unprotected trade or a non-ACTIVE / non-
     * CLAIMED protection — cancel proceeds with its ordinary refund path.
     *
     * REQUIRED (not REQUIRES_NEW) propagation is LOAD-BEARING — the lock
     * must outlive this call and span the caller's refundBuyer +
     * transitionTo so a concurrent autoClaim cannot squeeze in between
     * "consume cover" and "refund buyer".
     */
    @Transactional
    boolean lockAndExpireIfActiveOrReportClaimed(Long tradeId) {
        if (tradeId == null) return false
        def protection = tradeProtectionRepository.findByTradeIdForUpdate(tradeId)
        if (protection == null) return false
        if (protection.status == TradeProtection.CLAIMED) {
            // autoClaim already paid — caller skips its own refund.
            return true
        }
        if (protection.status == TradeProtection.ACTIVE) {
            // Cover still live — cancel will refund from escrow, so consume
            // the cover here under the lock so a concurrent autoClaim sees
            // EXPIRED on its locked re-read and bails.
            protection.status     = TradeProtection.EXPIRED
            protection.resolvedAt = System.currentTimeMillis()
            protection.updatedAt  = protection.resolvedAt
            tradeProtectionRepository.save(protection)
            log.info("Trade #{} protection EXPIRED inline during cancel — cover consumed under lock, fee kept", tradeId)
        }
        return false
    }

    // ── Payload helper ───────────────────────────────────────────────

    /**
     * Compact protection summary for embedding on the Trade payload.
     * Null when the trade is unprotected — the Trade endpoint surfaces
     * `protected:false` + `protection:null` in that case.
     */
    Map summary(Long tradeId) {
        def p = findForTrade(tradeId)
        if (p == null) return null
        [
            id:             p.id,
            tradeId:        p.tradeId,
            status:         p.status,
            feeAmount:      p.feeAmount,
            coverageAmount: p.coverageAmount,
            claimReason:    p.claimReason,
            createdAt:      p.createdAt,
            resolvedAt:     p.resolvedAt
        ]
    }
}
