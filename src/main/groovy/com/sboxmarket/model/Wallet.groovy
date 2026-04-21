package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnore
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
    @JsonIgnore
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

    /** Staff-set surgical lock (batch 509). When true, the wallet
     *  refuses deposits, withdrawals, and purchases but the user can
     *  still sign in and browse. Distinct from banning (which nukes
     *  listings + offers + trades). Used for regulatory holds, fraud
     *  investigations, or user-requested lockouts while staff verify
     *  identity. The partial index `idx_wallets_frozen` keeps the
     *  money-path check cheap even at platform scale. */
    @Column(nullable = false)
    Boolean frozen = false

    /** Human-readable freeze note shown to the user on the wallet page
     *  ("your wallet is frozen: <reason>"). Nullable; staff can set a
     *  freeze without a reason if the context is obvious. */
    @Column(name = 'frozen_reason', length = 500)
    String frozenReason

    @Column(name = 'frozen_at')
    Long frozenAt
}
