--
-- V8 — optimistic locking on trades.
--
-- Prevents a double-credit race between `TradeService.buyerConfirm()`
-- and the `sweepPendingConfirm()` scheduler: without @Version both can
-- read the same PENDING_BUYER_CONFIRM row, both call `release()`, and
-- both credit the seller wallet. With @Version, the second commit fails
-- with ObjectOptimisticLockingFailureException and rolls back the
-- duplicate credit (mapped to 409 LISTING_NOT_AVAILABLE by the HTTP
-- layer, though the sweeper's 409 is swallowed by its catch-and-log).
--
ALTER TABLE trades ADD COLUMN IF NOT EXISTS version INTEGER DEFAULT 0;
UPDATE trades SET version = 0 WHERE version IS NULL;
