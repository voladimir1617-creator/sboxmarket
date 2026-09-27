package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Cluster-wide idempotency ledger for Stripe webhook events
 * ({@code StripeService.handleWebhookEvent}).
 *
 * Stripe retries a webhook on any non-2xx (and on transient network
 * failures), and on a multi-pod deploy each retry can land on a DIFFERENT
 * pod than the original. Pre-fix the only dedup gate was the per-JVM
 * {@code seenEventIds} LinkedHashSet — blind to sibling pods — so a retry
 * routed to a fresh pod re-ran the side-effecting handler: a re-delivered
 * {@code charge.dispute.created} re-spammed every admin's bell and a
 * re-delivered deposit/refund event logged a duplicate audit row. (The
 * MONEY path itself is safe — completeDeposit / failTransaction / the
 * dispute + refund handlers are each row-idempotent on the tx status /
 * stripeReference they already set — so this ledger is specifically about
 * not RE-FIRING notifications + audit rows on a cross-pod retry.)
 *
 * One row per Stripe event id. The repository's INSERT is the
 * authoritative cross-pod gate: whichever pod's INSERT lands first
 * persists the row; a sibling pod racing the same event id surfaces a
 * {@code DataIntegrityViolationException} on the UNIQUE index
 * ({@code uq_processed_stripe_events_event_id}) and treats the event as
 * an already-claimed duplicate (skip the handler, ACK 200 so Stripe stops
 * retrying). Same wave-112-style claim shape as {@link FraudSignalClaim}.
 *
 * The per-JVM {@code seenEventIds} set survives as a fast-path cache that
 * short-circuits the DB round-trip on events THIS pod already handled this
 * lifetime; this row is the source of truth across pods.
 */
@Entity
@Table(name = "processed_stripe_events")
class ProcessedStripeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /** The Stripe event id ({@code evt_...}). Backed by a UNIQUE index so
     *  concurrent pods race the INSERT and exactly one wins; the loser's
     *  INSERT fails the constraint and is treated as a duplicate. */
    @Column(name = "event_id", nullable = false, length = 255)
    String eventId

    /** The Stripe event type ({@code checkout.session.completed}, etc.) —
     *  carried for ops/debug visibility; nullable so a malformed event with
     *  no type still claims cleanly. */
    @Column(name = "event_type", length = 120)
    String eventType

    /** Epoch-millis the event was first claimed. Mirrors the BIGINT
     *  timestamp convention used across the schema (Transaction.createdAt,
     *  FraudSignalClaim.claimedAt). Indexed for a future retention prune. */
    @Column(name = "processed_at", nullable = false)
    Long processedAt = System.currentTimeMillis()
}
