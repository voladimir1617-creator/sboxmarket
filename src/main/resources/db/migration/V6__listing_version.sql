--
-- V6 — optimistic locking on listings.
--
-- The `listings.version` column is Hibernate's @Version field. It starts
-- at 0 and Hibernate bumps it on every save; concurrent updates to the
-- same row will fail the loser with ObjectOptimisticLockingFailureException
-- instead of silently overwriting each other.
--
-- Nullable so existing rows back-fill cleanly.
-- Added to prevent the concurrency bug in PurchaseService.buy():
-- two buyers racing the same listing would both pass the ACTIVE check,
-- both debit their wallets, and both try to mark the row SOLD. Now the
-- second loser gets a 409 CONFLICT and their wallet is never touched.
--
ALTER TABLE listings ADD COLUMN IF NOT EXISTS version INTEGER DEFAULT 0;
UPDATE listings SET version = 0 WHERE version IS NULL;
