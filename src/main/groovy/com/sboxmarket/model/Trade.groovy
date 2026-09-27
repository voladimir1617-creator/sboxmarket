package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Escrow-style trade record. Created when a buyer hits "Buy Now" or wins an
 * auction. The listing is flipped to SOLD immediately so nobody else can buy
 * it, but the funds stay escrowed on the buyer's side until the seller marks
 * the Steam trade offer sent and the buyer confirms receipt.
 *
 * Lifecycle:
 *   PENDING_SELLER_ACCEPT  — brand new, seller has to click "Accept & Send"
 *   PENDING_SELLER_SEND    — seller accepted, now owes a Steam trade offer
 *   PENDING_BUYER_CONFIRM  — seller marked sent, buyer must confirm receipt
 *   VERIFIED               — buyer confirmed; funds released to seller wallet
 *   DISPUTED               — EITHER participant (buyer OR seller) opened a
 *                            dispute. Escrow is frozen — only staff can move
 *                            the trade out of this state.
 *   CANCELLED              — seller never delivered, funds refunded to buyer
 *
 * A trade is considered "in escrow" for the entire window between PENDING_*
 * and VERIFIED. From DISPUTED only ADMIN may force-release or force-refund
 * via AdminService.forceReleaseTrade / forceCancelTrade; CSRs can read the
 * trade and its chat thread for triage but must escalate the actual money
 * decision to an admin (per the AdminService vs CsrService split — only
 * admin can move escrowed funds).
 */
@Entity
@Table(
    name = "trades",
    indexes = [
        @Index(name = "idx_trades_buyer",  columnList = "buyerUserId,createdAt"),
        @Index(name = "idx_trades_seller", columnList = "sellerUserId,createdAt"),
        @Index(name = "idx_trades_state",  columnList = "state,updatedAt")
    ]
)
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /**
     * Optimistic-lock token. Prevents a double-credit race between
     * `buyerConfirm` and `sweepPendingConfirm` — without this, both can
     * read the same PENDING_BUYER_CONFIRM row, both call `release()`, and
     * both credit the seller wallet before checking state. With @Version,
     * the second commit fails with ObjectOptimisticLockingFailureException
     * and rolls back the duplicate credit.
     */
    @JsonIgnore
    @Version
    @Column
    Integer version = 0

    @Column(nullable = false)
    Long listingId

    @Column(nullable = false)
    Long itemId

    @Column
    String itemName

    @Column
    Long buyerUserId

    @Column
    Long sellerUserId

    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal price

    /** Platform fee charged to the seller on release. */
    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal feeAmount = BigDecimal.ZERO

    /**
     * PENDING_SELLER_ACCEPT, PENDING_SELLER_SEND, PENDING_BUYER_CONFIRM,
     * VERIFIED, DISPUTED, CANCELLED
     */
    @Column(nullable = false, length = 32)
    String state = "PENDING_SELLER_ACCEPT"

    /** Buyer wallet id — we hold this so the cancel path can refund quickly. */
    @JsonIgnore
    @Column
    Long buyerWalletId

    /** Seller wallet id — credited on VERIFIED. */
    @JsonIgnore
    @Column
    Long sellerWalletId

    /** Optional note the seller / buyer / staff attach during the flow. */
    @Column(length = 500)
    String note

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(name = "updated_at", nullable = false)
    Long updatedAt = System.currentTimeMillis()

    /** When the trade moved to VERIFIED / CANCELLED. Null while in escrow. */
    @Column
    Long settledAt

    /** When the seller clicked "Mark sent" and the trade transitioned to
     *  PENDING_BUYER_CONFIRM. Powers the "Typically ships in ~N hours"
     *  seller-trust metric on the public stall page (batch 550). Null on
     *  legacy rows (pre-V49) and any trade still waiting on seller send. */
    @Column(name = 'sent_at')
    Long sentAt

    /** Set the first time the buyer is pushed a TRADE_SLOW_SELLER warning
     *  because the seller has been silent for >24h on a pending-seller
     *  state. Stops the warning sweeper from re-pinging every tick. Cleared
     *  back to null whenever the trade transitions out of the
     *  PENDING_SELLER_* states so a rare admin-forced re-entry re-arms
     *  the sweeper for the next silence window. */
    @Column(name = 'slow_seller_warned_at')
    Long slowSellerWarnedAt

    /** Set the first time the second REVIEW_REMINDER fires (≈48h after
     *  the trade verifies, only if the buyer hasn't reviewed yet). One-
     *  shot per trade — the partial index on this column drives the
     *  sweeper, and the stamp keeps it from re-nudging on subsequent
     *  ticks. (V38 / batch 284) */
    @Column(name = 'review_nudge_sent_at')
    Long reviewNudgeSentAt

    /** Optional Steam trade-offer URL the seller provides at Mark-Sent
     *  time (batch 773). Lets the buyer jump straight to the Steam
     *  offer in one click from their Trades tab, and gives staff a
     *  concrete reference when triaging a dispute ("did the seller
     *  actually send the offer?"). Validated on write to be a real
     *  `https://steamcommunity.com/tradeoffer/...` URL; anything else
     *  is silently dropped. Null for legacy trades + for sellers who
     *  skip the field. */
    @Column(name = 'trade_offer_url', length = 200)
    String tradeOfferUrl

    /** Who ended a CANCELLED trade: BUYER, SELLER, STAFF, SELLER_TIMEOUT (the
     *  seller never accepted/sent inside the response window) or
     *  SELLER_BANNED. Recorded so a seller's public completion rate counts
     *  only the failures that were theirs; before this column a buyer
     *  backing out and a seller walking away were the same row. Null on
     *  trades that are not cancelled and on rows cancelled before it
     *  existed, which the completion rate therefore leaves out rather than
     *  guesses at (V260). */
    @Column(name = 'cancelled_by', length = 24)
    String cancelledBy
}
