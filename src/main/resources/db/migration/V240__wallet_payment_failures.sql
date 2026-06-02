-- V240: cluster-wide card-testing failure ledger.
--
-- StripeService.handlePaymentIntentFailed runs a card-testing detector
-- (batch 501): it counts payment_intent.payment_failed events per wallet in
-- a trailing 1h window and, on crossing CARD_TEST_THRESHOLD, fans out a
-- CARD_TESTING_DETECTED bell to every admin so they can freeze the wallet
-- before the attacker finds a working card. Pre-fix the failure COUNT lived
-- in a per-JVM ConcurrentHashMap<Long, List<Long>> — blind to sibling pods.
-- On a multi-pod cluster Stripe's webhook deliveries are load-balanced, so an
-- attacker spreading declines across pods accumulated only a fraction of the
-- count on any single pod; the per-pod threshold was never reached and the
-- alert never fired. Distributed card-testing evaded the threshold entirely.
-- The exact multi-pod failure mode that V70 (fraud_signal_claims) and V230
-- (processed_stripe_events) closed for the fraud sweeper / webhook dedup.
--
-- This table is the cross-pod source of truth. Per observed decline we INSERT
-- one row; the windowed count (countByWalletIdAndFailedAtAfter) then sums
-- across EVERY pod's rows, so the threshold sees the full cross-pod failure
-- volume regardless of which pod each decline landed on. (Cross-pod ALERT
-- dedup — so only one pod fans out the bell per window — reuses the existing
-- fraud_signal_claims claim, V70; this table is purely the COUNT mechanism.)
--
-- Schema mirrors the lightest possible per-event ledger (same shape as V70 /
-- V230):
--   * wallet_id  — the wallet the failed PaymentIntent was tagged with (PI
--                  metadata walletId). Indexed so the windowed count is an
--                  index range scan, not a full table scan.
--   * failed_at  — epoch millis the decline was observed; indexed for both
--                  the windowed-count predicate (failed_at > cutoff) and the
--                  retention prune (failed_at < cutoff). Matches the BIGINT
--                  epoch-millis convention used across the schema
--                  (Transaction.createdAt, processed_stripe_events.processed_at).
--
-- Idempotent via IF NOT EXISTS so a previously-applied migration can be
-- re-run safely. Postgres-compatible; dev/CI run on H2 with ddl-auto:update
-- (Flyway is prod-only — see application.yml flyway.enabled), which keeps the
-- same table in sync from the JPA @Entity mapping.
CREATE TABLE IF NOT EXISTS wallet_payment_failures (
    id          BIGSERIAL PRIMARY KEY,
    wallet_id   BIGINT NOT NULL,
    failed_at   BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wallet_payment_failures_wallet_id
    ON wallet_payment_failures (wallet_id);

CREATE INDEX IF NOT EXISTS idx_wallet_payment_failures_failed_at
    ON wallet_payment_failures (failed_at);

-- Composite index for the hot windowed count
-- (countByWalletIdAndFailedAtAfter): wallet_id equality + failed_at range in
-- one index so the per-wallet 1h count never touches another wallet's rows.
CREATE INDEX IF NOT EXISTS idx_wallet_payment_failures_wallet_failed
    ON wallet_payment_failures (wallet_id, failed_at);
