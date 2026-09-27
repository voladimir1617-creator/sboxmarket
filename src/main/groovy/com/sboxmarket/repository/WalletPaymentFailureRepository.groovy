package com.sboxmarket.repository

import com.sboxmarket.model.WalletPaymentFailure
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Cluster-wide card-testing failure ledger.
 *
 * Used by {@code StripeService.handlePaymentIntentFailed} as the multi-pod
 * SOURCE OF TRUTH for the per-wallet failure count. Each
 * {@code payment_intent.payment_failed} INSERTs one row; the windowed count
 * then aggregates across EVERY pod's rows, so an attacker spreading declines
 * across pods can no longer keep any single pod under the per-pod threshold.
 *
 * Mirrors the shape of {@link ProcessedStripeEventRepository} /
 * {@link FraudSignalClaimRepository}: a count/exists query for the hot read
 * plus a bulk-delete for a bounded-growth retention prune.
 */
@Repository
interface WalletPaymentFailureRepository extends JpaRepository<WalletPaymentFailure, Long> {

    /** Count this wallet's recorded failures strictly after {@code cutoff}
     *  (epoch millis) — the cross-pod windowed count the card-testing
     *  threshold is evaluated against. Backed by the
     *  {@code (wallet_id, failed_at)} indexing so it stays an index scan as
     *  the table grows. */
    long countByWalletIdAndFailedAtAfter(Long walletId, Long cutoff)

    /** Bulk-delete failure rows older than {@code cutoff}. Called best-effort
     *  from the failure handler (and available for a future scheduled sweep)
     *  so the table is bounded by active-attack volume × the retention
     *  window rather than by uptime. A failure here is swallowed by the
     *  caller and never breaks the webhook path. Symmetric with
     *  {@code FraudSignalClaimRepository.deleteOlderThan} /
     *  {@code ProcessedStripeEventRepository.deleteOlderThan}. */
    @Modifying
    @Query("DELETE FROM WalletPaymentFailure f WHERE f.failedAt < :cutoff")
    int deleteByFailedAtBefore(@Param("cutoff") Long cutoff)
}
