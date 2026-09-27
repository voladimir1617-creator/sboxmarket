package com.sboxmarket.repository

import com.sboxmarket.model.ProcessedStripeEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Cluster-wide idempotency ledger for Stripe webhook events.
 *
 * Used by {@code StripeService.claimStripeEvent} as the multi-pod dedup
 * gate. The flow mirrors {@link FraudSignalClaimRepository}:
 *   1. {@link #existsByEventId} — fast existence check (skip if some pod
 *      has already claimed this event id).
 *   2. {@code save(new ProcessedStripeEvent(...))} — the AUTHORITATIVE
 *      INSERT. May still lose the race to a sibling pod that snuck in
 *      between the read and our write, in which case the UNIQUE-constraint
 *      violation on {@code uq_processed_stripe_events_event_id} surfaces
 *      as a {@code DataIntegrityViolationException} the service catches and
 *      treats as a duplicate (no throw, no re-fired side effects).
 */
@Repository
interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, Long> {

    /** Existence check on the cluster-wide Stripe event id. Returns true
     *  when the event has already been claimed by some pod (this pod or
     *  another) — the caller short-circuits the side-effecting handler. */
    boolean existsByEventId(String eventId)

    /** Bulk-delete claim rows older than the cutoff. Provided for a future
     *  retention sweep so the table stays bounded by event volume × the
     *  retention window rather than by uptime — Stripe never retries an
     *  event beyond a few days, so an older claim can never be re-delivered.
     *  Not yet scheduled (no behaviour depends on it); kept symmetric with
     *  {@code FraudSignalClaimRepository.deleteOlderThan}. */
    @Modifying
    @Query("DELETE FROM ProcessedStripeEvent e WHERE e.processedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Long cutoff)
}
