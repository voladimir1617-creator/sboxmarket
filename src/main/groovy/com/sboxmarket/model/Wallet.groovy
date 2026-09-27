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

    /** Stripe Connect (Express) connected-account id — the `acct_…` token
     *  returned by Account.create when the user starts payout onboarding.
     *  This is the destination of every real money-out Transfer. NULL
     *  until the user begins onboarding via
     *  StripeService.createConnectOnboardingLink. Persisted + indexed
     *  (V72) so the `account.updated` webhook can resolve account-id →
     *  wallet without a table scan. Distinct from the Checkout-Session /
     *  PaymentIntent ids stored on Transaction rows — this is the
     *  long-lived seller payout account, not a per-transaction reference. */
    @Column(name = 'stripe_connect_account_id', length = 64)
    String stripeConnectAccountId

    /** Mirror of Stripe's `account.payouts_enabled` flag for the connected
     *  account above. Flipped to TRUE by the `account.updated` webhook once
     *  Stripe reports the account has cleared KYC / identity verification
     *  and can receive payouts. StripeService.requestWithdrawal refuses to
     *  attempt a real Transfer (CONNECT_ONBOARDING_REQUIRED) while this is
     *  false in live mode — so we never push money at an un-onboarded or
     *  restricted account. Default false = "not onboarded", the correct
     *  starting state for every existing wallet (V72 backfill). */
    @Column(name = 'payouts_enabled', nullable = false)
    Boolean payoutsEnabled = false
}
