-- Bot-escrow custody store for real Steam assets (app 590830).
--
-- One row per listing whose item the marketplace bot escrows on the seller's
-- behalf. Records the concrete Steam asset id, the deposit / return trade-offer
-- ids, and the custody lifecycle (PENDING_DEPOSIT -> IN_CUSTODY -> DELIVERED /
-- RETURNED / FAILED). This table NEVER touches wallet balances — money/escrow
-- release stays on the `trades` table, owned by TradeService.
--
-- Backs EscrowedItem / EscrowedItemRepository / SteamEscrowService. Numbered
-- V210 to stay clear of the in-flight V2..V72 series and the V200 delivery-
-- attempts migration.

CREATE TABLE escrowed_items (
    id BIGSERIAL PRIMARY KEY,
    listing_id BIGINT NOT NULL,
    seller_user_id BIGINT,
    asset_id VARCHAR(64),
    held_asset_id VARCHAR(64),
    market_hash_name VARCHAR(255),
    deposit_offer_id VARCHAR(64),
    return_offer_id VARCHAR(64),
    custody_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_DEPOSIT',
    last_error VARCHAR(500),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- One custody row per listing — the escrow orchestrator keys on listing_id
-- both to find "is this listing's item actually in custody yet?" (buyable
-- gate) and to resolve the held asset id for the delivery leg.
CREATE UNIQUE INDEX uq_escrowed_items_listing ON escrowed_items(listing_id);

-- Deposit-confirm poller hot path: pull PENDING_DEPOSIT rows oldest-first.
CREATE INDEX idx_escrowed_items_custody_state ON escrowed_items(custody_state, updated_at);

-- Offer-id lookups for poll/return reconciliation.
CREATE INDEX idx_escrowed_items_deposit_offer ON escrowed_items(deposit_offer_id);
