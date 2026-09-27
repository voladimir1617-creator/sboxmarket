-- V70: cluster-wide dedup ledger for the fraud-signal sweeper.
--
-- FraudAnalysisService.sweepAndPushFraudSignals (@Scheduled) fans out
-- HIGH-severity fraud bells to every admin. The pre-existing
-- `seenSignatures` LinkedHashSet was per-JVM, so on a multi-pod cluster
-- every pod's @Scheduled tick independently computed the same signal
-- list, none of the pods saw each other's dedup state, and each pod
-- pushed its own bell to every admin. Two pods = 2x bells per HIGH
-- signal; N pods = N× the noise. The exact failure mode that wave 112
-- closed for the watchlist sweeper via `claimForFiring`.
--
-- This table is the cluster-wide gate. Per signature we INSERT one row;
-- the UNIQUE(signature) constraint means exactly one pod's claim
-- succeeds and only that pod proceeds to fan out admin pushes. Losers
-- get a "no rows affected" reply and bail BEFORE invoking
-- notificationService.push.
--
-- Bounded-growth posture: a daily retention sweep (parallel to the
-- notifications retention sweep) prunes claims older than the 24h fraud
-- window so the table never grows unbounded. Without the prune a
-- long-lived signature whose bucket never changes would otherwise sit
-- in the table forever.
--
-- Schema mirrors the lightest possible "I happened" ledger:
--   * signature  — composite key the sweeper hashes (type, userId, ip,
--                  bucketed-count, dedupKey). VARCHAR(512) sized to fit
--                  even an unusually long composite plus a JOIN-of-IPs
--                  field; production rows land in the 60-120 char band.
--   * claimed_at — for the retention prune. Indexed.
--
-- Idempotent via IF NOT EXISTS so a previously-applied migration can be
-- re-run safely.
CREATE TABLE IF NOT EXISTS fraud_signal_claims (
    id           BIGSERIAL PRIMARY KEY,
    signature    VARCHAR(512) NOT NULL,
    claimed_at   BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_fraud_signal_claims_signature
    ON fraud_signal_claims (signature);

CREATE INDEX IF NOT EXISTS idx_fraud_signal_claims_claimed_at
    ON fraud_signal_claims (claimed_at);
