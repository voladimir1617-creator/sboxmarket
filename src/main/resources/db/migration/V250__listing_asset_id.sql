-- Steam inventory asset id on a listing, to forbid double-listing one asset.
--
-- A seller lists from their Steam inventory via POST /api/steam/list[-bulk];
-- the controller validates the numeric assetId and (with the escrow bot
-- disabled) creates the listing directly ACTIVE. Nothing recorded the assetId
-- or checked for an existing live listing of the same asset, so a seller could
-- list the SAME physical item twice -> both could sell -> paid twice for one
-- undeliverable copy (double-sell). ListingService/SteamInventoryController now
-- persist this and reject a second LIVE listing for the same (seller, asset).
--
-- Nullable: seed/system listings and legacy rows have no assetId, and a seller
-- can legitimately own two DIFFERENT copies of the same item (distinct
-- assetIds) — so the guard keys on assetId, never item id. `IF NOT EXISTS`
-- keeps this idempotent; dev (H2) syncs the column via ddl-auto:update, Flyway
-- runs only in prod.
ALTER TABLE listings ADD COLUMN IF NOT EXISTS asset_id VARCHAR(64);

-- Supports the duplicate-guard lookup (seller_user_id + asset_id, filtered by
-- live status). Not unique: legacy rows share NULL asset_id, and a CANCELLED/
-- SOLD row may share an asset_id with a later relist of the returned item.
CREATE INDEX IF NOT EXISTS idx_listings_seller_asset
    ON listings (seller_user_id, asset_id);
