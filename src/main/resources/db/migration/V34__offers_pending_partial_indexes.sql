-- Partial indexes for the per-user PENDING-offer count queries that
-- drive the nav badge (batch 258) and the per-stall best-offer chip.
-- The existing `(buyer_user_id, created_at)` / `(seller_user_id,
-- created_at)` indexes from V1 cover ordering, but for the count
-- queries Postgres has to scan the user's full offer history and
-- filter by status — O(N_user_offers) instead of O(N_pending).
--
-- A partial index on the PENDING subset is tiny (most offers settle
-- quickly), maintenance is cheap (only PENDING rows trigger writes),
-- and the count drops to O(N_pending_for_user) which is essentially
-- a constant for a real user. The badge polls every 60s for every
-- signed-in session — at scale this matters.
CREATE INDEX IF NOT EXISTS idx_offers_buyer_pending
    ON offers(buyer_user_id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_offers_seller_pending
    ON offers(seller_user_id)
    WHERE status = 'PENDING';
