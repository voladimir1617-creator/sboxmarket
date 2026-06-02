package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Cluster-wide card-testing failure ledger for
 * {@code StripeService.handlePaymentIntentFailed}.
 *
 * The card-testing detector (batch 501) counts {@code payment_intent.payment_failed}
 * events per wallet inside a trailing 1h window and, on crossing
 * {@code CARD_TEST_THRESHOLD}, fans out a {@code CARD_TESTING_DETECTED} bell to
 * every admin so they can freeze the wallet before the attacker finds a
 * working card. Pre-fix the failure COUNT lived in a per-JVM
 * {@code ConcurrentHashMap<Long, List<Long>>} — blind to sibling pods. On a
 * multi-pod deploy an attacker spreading declines across pods (Stripe webhook
 * deliveries are load-balanced) accumulated only a fraction of the count on
 * any single pod, so the per-pod threshold was never reached and the alert
 * never fired — distributed card-testing evaded detection entirely.
 *
 * One row per observed decline: {@code wallet_id} + {@code failed_at}
 * (epoch millis). The authoritative windowed count is a DB aggregate
 * ({@code countByWalletIdAndFailedAtAfter}) summed across every pod's
 * inserted rows, so the threshold now sees the FULL cross-pod failure
 * volume. The in-memory map survives only as a fast-path cache.
 *
 * Bounded-growth posture mirrors {@link ProcessedStripeEvent} /
 * {@link FraudSignalClaim}: a best-effort prune
 * ({@code deleteByFailedAtBefore}) drops rows older than a generous retention
 * margin so the table stays bounded by active-attack volume, not uptime.
 * The epoch-millis {@code failedAt} BIGINT convention matches
 * {@code Transaction.createdAt} / {@code ProcessedStripeEvent.processedAt}.
 */
@Entity
@Table(name = "wallet_payment_failures")
class WalletPaymentFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /** Wallet the failed PaymentIntent was tagged with (PI metadata
     *  {@code walletId}, stamped at Checkout-Session creation). Indexed so
     *  the windowed count is an index range scan, not a table scan. */
    @Column(name = "wallet_id", nullable = false)
    Long walletId

    /** Epoch millis the decline was observed. Indexed for the windowed
     *  count's {@code failed_at > cutoff} predicate and the retention prune's
     *  {@code failed_at < cutoff} delete. */
    @Column(name = "failed_at", nullable = false)
    Long failedAt = System.currentTimeMillis()
}
