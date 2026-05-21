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
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
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
        auditService?.log('TRADE_PROTECTION_ENABLED', buyerUserId, trade.sellerUserId, tradeId,
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
     */
    @Transactional
    TradeProtection autoClaim(Long tradeId, String reason) {
        def protection = tradeProtectionRepository.findByTradeId(tradeId)
        if (protection == null) return null
        if (protection.status != TradeProtection.ACTIVE) {
            // Already claimed or expired — idempotent no-op.
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
            notificationService?.safePush(protection.buyerUserId, 'TRADE_PROTECTION_CLAIMED',
                "Protection paid out · ${trade?.itemName ?: 'your trade'}",
                "\$${protection.coverageAmount} has been refunded to your wallet — ${protection.claimReason}.",
                tradeId, '/wallet')
        }
        auditService?.log('TRADE_PROTECTION_CLAIMED', null, protection.buyerUserId, tradeId,
            "Protection claim paid out \$${protection.coverageAmount}: ${protection.claimReason}")
        log.info("Trade #{} protection CLAIMED — \${} refunded to buyer {} ({})",
            tradeId, protection.coverageAmount, protection.buyerUserId, protection.claimReason)
        protection
    }

    /**
     * Lapse a trade's protection — the trade completed normally
     * (VERIFIED), so the cover is no longer needed and the fee is kept
     * as revenue. Wired into TradeService's release path.
     *
     * No-op when the trade has no protection or the protection has
     * already left ACTIVE. Best-effort, like {@code autoClaim}.
     */
    @Transactional
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
