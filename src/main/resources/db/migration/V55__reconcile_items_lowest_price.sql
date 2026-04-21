-- Reconcile items.lowest_price against the real MIN of active listings.
-- SteamMarketPriceService (pre-batch-954) unconditionally overwrote
-- lowest_price with Steam's market floor on every sync tick, even for
-- items that had active listings on sboxmarket. That drove a drift
-- of up to $1.74 between what the marketplace grid card advertised
-- ("Floor $5.79") and what buyers actually saw when they clicked
-- through ("$7.65 cheapest").
--
-- For items WITH at least one active non-hidden listing, set
-- lowest_price = MIN(listings.price) — that's the true platform floor.
-- For items WITHOUT active listings, leave lowest_price alone so the
-- stale Steam-market number keeps working as a catalogue reference
-- (the Database page, search autocomplete, etc. still show a rough
-- last-known price).
UPDATE items i
SET lowest_price = sub.real_floor
FROM (
    SELECT l.item_id, MIN(l.price) AS real_floor
    FROM listings l
    WHERE l.status = 'ACTIVE'
      AND (l.hidden IS NULL OR l.hidden = FALSE)
    GROUP BY l.item_id
) sub
WHERE i.id = sub.item_id
  AND i.lowest_price IS DISTINCT FROM sub.real_floor;
