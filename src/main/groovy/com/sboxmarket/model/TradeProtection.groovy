package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Trade Protection record — the buyer-side safety add-on (CSFloat's
 * signature feature). Created 1:1 with a {@link Trade} when the buyer
 * opts into protection. The buyer pays {@code feeAmount} (2% of item
 * price, min $0.25) into platform revenue at enable time; in exchange,
 * if the trade fails through no fault of the buyer the protection
 * auto-claims and refunds the buyer {@code coverageAmount} (the full
 * item price) — no support ticket needed.
 *
 * Lifecycle:
 *   ACTIVE   — cover is live, the trade is still in escrow
 *   CLAIMED  — the trade failed; buyer was auto-refunded the item price
 *   EXPIRED  — the trade completed (VERIFIED); cover lapsed unused
 *
 * The 1:1 tie is enforced by a UNIQUE(trade_id) constraint so a
 * double-click on "Enable protection" can't stack two fee charges.
 */
@Entity
@Table(
    name = "trade_protections",
    indexes = [
        @Index(name = "idx_trade_protections_status", columnList = "status"),
        @Index(name = "idx_trade_protections_buyer",  columnList = "buyer_user_id")
    ]
)
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class TradeProtection {

    static final String ACTIVE  = 'ACTIVE'
    static final String CLAIMED = 'CLAIMED'
    static final String EXPIRED = 'EXPIRED'

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /** The protected trade. UNIQUE — one protection per trade. */
    @Column(name = "trade_id", nullable = false)
    Long tradeId

    /** Buyer who bought the cover — the party refunded on a claim. */
    @Column(name = "buyer_user_id")
    Long buyerUserId

    /** Protection fee the buyer paid. Platform revenue, never refunded. */
    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 2)
    BigDecimal feeAmount = BigDecimal.ZERO

    /** Amount paid back to the buyer if the protection claims — the
     *  item price the buyer originally paid into escrow. */
    @Column(name = "coverage_amount", nullable = false, precision = 19, scale = 2)
    BigDecimal coverageAmount = BigDecimal.ZERO

    /** ACTIVE, CLAIMED, EXPIRED. */
    @Column(nullable = false, length = 16)
    String status = ACTIVE

    /** Why a claim paid out — set when status → CLAIMED. */
    @Column(name = "claim_reason", length = 255)
    String claimReason

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(name = "updated_at", nullable = false)
    Long updatedAt = System.currentTimeMillis()

    /** When the protection left ACTIVE (claim payout or expiry). */
    @Column(name = "resolved_at")
    Long resolvedAt
}
