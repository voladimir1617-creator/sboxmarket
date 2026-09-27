-- Batch 773 — capture the seller's Steam trade-offer URL at mark-sent
-- time so the buyer has a clickable "Open Steam offer" link in their
-- Trades tab. Null on legacy rows (before this migration) and on any
-- future trade where the seller skips the URL field. Capped at 200
-- chars — Steam trade URLs are typically 100-150; the extra headroom
-- leaves room for any future query-param additions.
ALTER TABLE trades ADD COLUMN trade_offer_url VARCHAR(200);
