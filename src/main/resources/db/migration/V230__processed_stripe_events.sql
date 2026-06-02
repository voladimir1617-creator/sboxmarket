-- V230: cluster-wide idempotency ledger for Stripe webhook events.
--
-- StripeService.handleWebhookEvent dedupes Stripe webhook deliveries by
-- event id so a retry doesn't re-fire side effects (admin bells on
-- charge.dispute.created, audit rows on deposit/refund events). The
-- pre-existing `seenEventIds` LinkedHashSet was per-JVM, so on a multi-pod
-- cluster a Stripe retry routed to a DIFFERENT pod than the original saw
-- an empty dedup set and re-ran the side-effecting handler — duplicate
-- notifications + duplicate audit rows. (The money path itself was already
-- safe: completeDeposit / failTransaction / the dispute + refund handlers
-- are each row-idempotent on the tx status / stripeReference they set, so
-- a cross-pod retry never double-credited. This ledger closes the
-- re-fired-side-effects gap only.) The exact multi-pod failure mode that
-- V70 (fraud_signal_claims) closed for the fraud sweeper.
--
-- This table is the cluster-wide gate. Per event id we INSERT one row; the
-- UNIQUE(event_id) constraint means exactly one pod's claim succeeds and
-- only that pod proceeds to run the handler. A sibling pod racing the same
-- event id gets a unique-constraint violation (surfaced as Spring's
-- DataIntegrityViolationException) and treats the event as an
-- already-processed duplicate — skip the handler, ACK 200 so Stripe stops
-- retrying.
--
-- Schema mirrors the lightest possible "I happened" ledger (same shape as
-- V70 fraud_signal_claims):
--   * event_id     — the Stripe event id (evt_...). VARCHAR(255) matches
--                    Transaction.stripeReference sizing; production ids are
--                    ~30-40 chars. Carries the UNIQUE constraint.
--   * event_type   — the Stripe event type for ops/debug visibility.
--   * processed_at — epoch millis the event was first claimed; indexed for
--                    a future retention prune (Stripe never retries beyond
--                    a few days, so old rows can be pruned safely).
--
-- Idempotent via IF NOT EXISTS so a previously-applied migration can be
-- re-run safely. Postgres-compatible; dev/CI run on H2 with ddl-auto:update
-- (Flyway is prod-only — see application.yml flyway.enabled), which keeps
-- the same table in sync from the JPA @Entity mapping.
CREATE TABLE IF NOT EXISTS processed_stripe_events (
    id            BIGSERIAL PRIMARY KEY,
    event_id      VARCHAR(255) NOT NULL,
    event_type    VARCHAR(120),
    processed_at  BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_processed_stripe_events_event_id
    ON processed_stripe_events (event_id);

CREATE INDEX IF NOT EXISTS idx_processed_stripe_events_processed_at
    ON processed_stripe_events (processed_at);
