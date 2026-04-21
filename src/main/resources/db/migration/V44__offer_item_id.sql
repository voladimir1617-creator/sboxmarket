-- V44 — snapshot the catalogue item id on each offer (batch 384).
--
-- Lets the OffersModal render the item name as an <a href="/item/:id">
-- without doing a per-row Listing lookup. New offers populate item_id
-- from listing.item?.id at creation; existing rows stay null and the
-- frontend falls back to the plain-text item name (no broken links).
--
-- A best-effort backfill runs once via the JOIN below so the bulk of
-- the historical offers light up immediately. Listings that have been
-- physically deleted (none yet — we soft-cancel everywhere) leave the
-- offer's item_id null, which the UI handles gracefully.
ALTER TABLE offers
    ADD COLUMN IF NOT EXISTS item_id BIGINT;

UPDATE offers o
   SET item_id = l.item_id
  FROM listings l
 WHERE o.listing_id = l.id
   AND o.item_id IS NULL;
