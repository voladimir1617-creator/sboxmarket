-- Automated Steam trade-offer delivery (bot-escrow).
--
-- Audit log + authoritative offer-tracking store for SteamDeliveryService.
-- Money/escrow state stays on the `trades` table (owned by TradeService); this
-- table never touches balances. It records every send/poll attempt AND is the
-- single source of truth for "which Steam offer id is live for trade N and what
-- was its last-seen state" — so the delivery orchestrator never has to add a
-- column to the Trade entity (owned by a parallel agent).
--
-- Numbered V200 to stay clear of the in-flight V1..V70 series and a parallel
-- agent's concurrent migration.

CREATE TABLE steam_delivery_attempts (
    id BIGSERIAL PRIMARY KEY,
    trade_id BIGINT,
    steam_offer_id VARCHAR(64),
    offer_state VARCHAR(64),
    phase VARCHAR(16),
    success BOOLEAN NOT NULL DEFAULT FALSE,
    error_message VARCHAR(500),
    created_at BIGINT
);

CREATE INDEX idx_steam_delivery_attempts_trade ON steam_delivery_attempts(trade_id);
CREATE INDEX idx_steam_delivery_attempts_offer ON steam_delivery_attempts(steam_offer_id);
-- Newest-first per-trade lookup is the orchestrator's hot path
-- (latestForTrade): "what is the current offer + state for this trade?".
CREATE INDEX idx_steam_delivery_attempts_trade_created
    ON steam_delivery_attempts(trade_id, created_at DESC);
