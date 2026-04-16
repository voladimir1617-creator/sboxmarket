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
 *   DISPUTED               — buyer opened a dispute (admin routes to CSR)
 *   CANCELLED              — seller never delivered, funds refunded to buyer
 *
 * A trade is considered "in escrow" for the entire window between PENDING_*
 * and VERIFIED. Admin / CSR tools can force-release or force-refund any trade
 * via AdminService.
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
}
