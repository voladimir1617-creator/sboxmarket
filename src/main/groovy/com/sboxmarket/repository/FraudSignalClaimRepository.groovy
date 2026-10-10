package com.sboxmarket.repository

import com.sboxmarket.model.FraudSignalClaim
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface FraudSignalClaimRepository extends JpaRepository<FraudSignalClaim, Long> {

    /** Existence check on the cluster-wide signature.
     *
     *  Used by {@code FraudAnalysisService.sweepAndPushFraudSignals} as
     *  the FIRST half of the multi-pod race claim — the caller checks
     *  this, then INSERTs the row via {@code save(...)}. The INSERT may
     *  still lose the race to a sibling pod that snuck in between this
     *  read and our write, in which case the unique-constraint violation
     *  on {@code idx_fraud_signal_claims_signature} surfaces as a
     *  {@code DataIntegrityViolationException} the service catches.
     *
     *  Returns true when the signature has already been claimed by some
     *  pod (this pod or another). */
    boolean existsBySignature(String signature)

    /** Bulk-delete claim rows older than the cutoff. Called from the
     *  daily retention sweep — the fraud signal window is 24h so any
     *  claim older than that can never re-trip the same signature, and
     *  the table is bounded by the active-attack rate × 24h, not by
     *  uptime years. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("DELETE FROM FraudSignalClaim c WHERE c.claimedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Long cutoff)
}
