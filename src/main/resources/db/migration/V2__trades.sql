--
-- V2 — escrow-style trade state machine (added after the security audit).
-- See `TradeService` / `Trade` entity for the full lifecycle.
--

CREATE TABLE IF NOT EXISTS trades (
    id                BIGSERIAL PRIMARY KEY,
    listing_id        BIGINT NOT NULL,
    item_id           BIGINT NOT NULL,
    item_name         VARCHAR(255),
    buyer_user_id     BIGINT,
    seller_user_id    BIGINT,
    price             NUMERIC(19,2) NOT NULL,
    fee_amount        NUMERIC(19,2) NOT NULL DEFAULT 0,
    state             VARCHAR(32) NOT NULL DEFAULT 'PENDING_SELLER_ACCEPT',
    buyer_wallet_id   BIGINT,
    seller_wallet_id  BIGINT,
    note              VARCHAR(500),
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL,
    settled_at        BIGINT
);

CREATE INDEX IF NOT EXISTS idx_trades_buyer  ON trades(buyer_user_id,  created_at);
CREATE INDEX IF NOT EXISTS idx_trades_seller ON trades(seller_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_trades_state  ON trades(state, updated_at);
