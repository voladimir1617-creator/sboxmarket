-- When the buyer actually had the item: the buyer pressed "Item received",
-- or the escrow bot saw its Steam offer to the buyer ACCEPTED (both go
-- through TradeService.buyerConfirm). Powers the seller "Delivery time"
-- stat: median of delivered_at - created_at over the seller's last 90 days
-- of VERIFIED trades, shown from 3 trades, "-" before that.
--
-- sent_at (V49) is when the seller SAID it was sent; this is when it
-- arrived. A trade released by the auto-release timer or by staff never
-- gets a delivered_at, because nobody saw the item arrive.
--
-- Nullable with no default and no backfill: every row written before this
-- stays NULL, which the stat reads as "not measured", so no existing row
-- changes meaning and the stat starts from real deliveries only.

ALTER TABLE trades
    ADD COLUMN IF NOT EXISTS delivered_at BIGINT;

CREATE INDEX IF NOT EXISTS idx_trades_seller_delivered
    ON trades (seller_user_id, delivered_at)
    WHERE delivered_at IS NOT NULL;
