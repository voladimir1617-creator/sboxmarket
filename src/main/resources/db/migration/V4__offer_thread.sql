--
-- V4 — counter-offer thread support. `parent_offer_id` chains a seller's
-- counter back to the buyer's original offer, `author` identifies whose
-- side of the conversation a row belongs to.
--

ALTER TABLE offers ADD COLUMN IF NOT EXISTS parent_offer_id BIGINT;
ALTER TABLE offers ADD COLUMN IF NOT EXISTS author VARCHAR(16) DEFAULT 'USER';

CREATE INDEX IF NOT EXISTS idx_offers_parent ON offers(parent_offer_id);
