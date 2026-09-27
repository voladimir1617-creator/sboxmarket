-- V47: One-time seller nudge for pending offers approaching auto-decline.
--
-- An offer can sit PENDING for `offer.auto-decline-days` (default 7) before
-- the sweeper auto-EXPIRES it. The seller gets a notification when the
-- offer LANDS (via OfferService.makeOffer) but nothing during the wait —
-- so a busy seller often discovers the auto-decline only after the window
-- closes and the buyer is gone. The new sweeper pings the seller once
-- when the offer crosses the half-life mark, and `seller_nudged_at`
-- records the wall-clock when that ping fired so the sweeper is
-- idempotent across restarts (the next pass skips already-nudged rows).
ALTER TABLE offers ADD COLUMN IF NOT EXISTS seller_nudged_at BIGINT NULL;

-- Partial index — only PENDING + un-nudged rows are sweeper candidates,
-- so a covering index on (status='PENDING', updatedAt) keeps the half-life
-- scan cheap as the offers table grows past 100k rows.
CREATE INDEX IF NOT EXISTS idx_offers_pending_unnudged
    ON offers (updated_at)
    WHERE status = 'PENDING' AND seller_nudged_at IS NULL;
