-- Defense-in-depth CHECK on listings.max_discount (0 <= x < 1, or NULL).
--
-- Listing.maxDiscount drives OfferService.tryAutoAccept's auto-accept threshold:
--   threshold = price - (price * maxDiscount)
-- A value >= 1 makes that threshold ZERO or NEGATIVE, so EVERY positive offer
-- clears it and the listing auto-accepts far below the seller's intended floor
-- (even a $0.01 offer). SellService.relist validates 0 <= maxDiscount < 1 at
-- every write, and OfferService.tryAutoAccept now also rejects an out-of-range
-- value at read time (abuse-audit fix). This adds the DB-layer guarantee so no
-- raw-SQL / ETL / future-code path can ever persist a value that corrupts the
-- auto-accept math. NULL is allowed (NULL = no auto-accept).
--
-- Postgres prod dialect; runs prod-only (dev uses H2 ddl-auto, Flyway disabled).
-- Existing rows are provably in range — every write path validates 0..1 and
-- there is no admin override on this column — so the ALTER cannot fail on
-- legacy data. (integrity audit, companion to commit 7b9710d)
ALTER TABLE listings
    ADD CONSTRAINT chk_listings_max_discount_range
    CHECK (max_discount IS NULL OR (max_discount >= 0 AND max_discount < 1));
