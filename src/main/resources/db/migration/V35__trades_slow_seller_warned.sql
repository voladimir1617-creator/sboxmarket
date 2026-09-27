-- Tracks when the buyer was warned that the seller has been silent
-- on a trade for >24h. Without this column the warning sweeper would
-- have to push every tick, spamming the buyer until the trade either
-- moves forward or auto-cancels at the 3-day mark.
--
-- Set once when the warning fires; cleared (back to NULL) whenever
-- the trade transitions out of the seller-pending states so a
-- re-entry to the same state (rare — the state machine is mostly
-- forward-only — but legal via admin force) re-arms the sweeper.
ALTER TABLE trades
    ADD COLUMN slow_seller_warned_at BIGINT;

-- Partial index: the sweeper looks for the un-warned PENDING rows.
-- Same shape as the V33 partial index on `away_mode_until`.
CREATE INDEX idx_trades_slow_seller_unwarned
    ON trades(updated_at)
    WHERE slow_seller_warned_at IS NULL
      AND state IN ('PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND');
