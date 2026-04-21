-- Trade send timestamp (batch 550). Tracks when the seller actually
-- clicks "Mark sent" on a PENDING_SELLER_SEND trade, flipping it to
-- PENDING_BUYER_CONFIRM. Powers the "Typically ships in ~N hours"
-- metric on the public stall page so buyers see how fast a seller
-- really delivers, not just how fast they respond to offers.
--
-- Legacy rows stay null — the metric ignores nulls. Partial index on
-- the seller id + sent_at gives the metric query a cheap scan path
-- even on a million-row trade table.
ALTER TABLE trades
    ADD COLUMN sent_at BIGINT;

CREATE INDEX IF NOT EXISTS idx_trades_seller_sent
    ON trades (seller_user_id, sent_at)
    WHERE sent_at IS NOT NULL;
