package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@Entity
@Table(name = "transactions")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false)
    Long walletId

    // DEPOSIT, WITHDRAW, PURCHASE, SALE
    @Column(nullable = false)
    String type

    // PENDING, COMPLETED, FAILED, CANCELLED
    @Column(nullable = false)
    String status = "PENDING"

    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal amount

    /**
     * Payment-processor fee passed through to the user on this row, in dollars.
     *
     * DEPOSIT: `amount` is the GROSS the card was charged and `feeAmount` is
     * what the processor kept, so the wallet was credited `amount - feeAmount`.
     * WITHDRAW: `amount` is the GROSS debited from the wallet and `feeAmount`
     * is the payout cost, so the user received `amount - feeAmount`.
     *
     * Stored rather than recomputed at credit time on purpose. The user is
     * quoted a net figure BEFORE they commit (deposit-session response / the
     * withdraw quote); recomputing later from live config would let an operator
     * changing `platform.processing-fee-percent` mid-flight credit a different
     * number than the one the user agreed to. `amount` stays GROSS because the
     * Stripe amount-match guard in completeDeposit compares it against
     * `session.amount_total`.
     *
     * Nullable: every row written before pass-through pricing, and every row
     * type that carries no processor fee (PURCHASE / SALE / FEE / …), leaves
     * it null. Null reads as zero everywhere.
     */
    @Column(name = "fee_amount", precision = 19, scale = 2)
    BigDecimal feeAmount

    @Column(nullable = false)
    String currency = "USD"

    // Stripe Checkout Session id (deposits) or PaymentIntent id
    @Column(length = 255)
    String stripeReference

    @Column(length = 500)
    String description

    /** Set when type = PURCHASE — the listing that was bought. */
    @Column
    Long listingId

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(name = "updated_at", nullable = false)
    Long updatedAt = System.currentTimeMillis()
}
