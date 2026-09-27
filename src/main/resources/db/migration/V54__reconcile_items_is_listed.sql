-- Reconcile items.is_listed with the actual listings table. Previously the
-- flag defaulted to TRUE on every row and was only flipped back in
-- ListingService.updateItemFloorPrice, which fires on listing mutations.
-- Catalogue items that were imported from the SCMM sync but never received
-- a user listing were stuck at is_listed = TRUE forever, so the
-- /api/items/{id} response lied about listing state and Item.isListed
-- sorted as "listed" in findSimilar even though no active listings existed.
--
-- Idempotent one-shot reconcile: set the flag to whatever the listings
-- table actually says right now.
UPDATE items i
SET is_listed = EXISTS (
    SELECT 1 FROM listings l
    WHERE l.item_id = i.id
      AND l.status = 'ACTIVE'
      AND (l.hidden IS NULL OR l.hidden = FALSE)
);
