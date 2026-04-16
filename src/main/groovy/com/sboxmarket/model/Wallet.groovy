package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@Entity
@Table(name = "wallets")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /**
     * Optimistic-lock token. Paired with `@Version` on Listing, this
     * closes the second half of the race in PurchaseService.buy(): even
     * if one user fires two concurrent `/buy` requests from different
     * tabs, Hibernate will fail the second commit with
     * ObjectOptimisticLockingFailureException → 409 and the second
     * debit never lands. Nullable in SQL + default 0 in Groovy so the
     * column is safe to backfill on existing rows.
     */
    @Version
    @Column
    Integer version = 0

    @Column(nullable = false, unique = true)
    String username

    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal balance = BigDecimal.ZERO

    @Column(nullable = false)
    String currency = "USD"

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
