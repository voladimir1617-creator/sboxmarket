-- Tracks whether a buyer has been re-nudged to leave a review for a
-- verified trade. The initial `REVIEW_REMINDER` push fires inline at
-- TradeService.release() (verification time) but a buyer who walked
-- away from the bell often doesn't see it until much later. This
-- column lets a scheduled sweeper send a SECOND nudge ~48h after
-- verification if no review has been left yet — without re-pushing
-- on every tick after that.
--
-- Set once when the second nudge fires; the sweeper's WHERE clause
-- excludes already-stamped rows so it's a one-shot per trade.
ALTER TABLE trades
    ADD COLUMN review_nudge_sent_at BIGINT;

-- Partial index — the sweep wants the small subset of VERIFIED trades
-- past the 48h cutoff that haven't been re-nudged yet. Mirrors the
-- shape of the V35 slow-seller-unwarned partial index.
CREATE INDEX idx_trades_review_nudge_pending
    ON trades(settled_at)
    WHERE review_nudge_sent_at IS NULL
      AND state = 'VERIFIED';
