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
