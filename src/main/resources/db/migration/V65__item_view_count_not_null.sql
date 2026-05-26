-- V65: tighten items.view_count to NOT NULL to match the JPA entity.
--
-- V46 added the column as `BIGINT DEFAULT 0` but omitted NOT NULL. The
-- Item entity declares the column with @Column(nullable = false) and a
-- primitive-ish init (`Long viewCount = 0L`), so any row that somehow
-- ends up with NULL would null-out the field on load and NPE the first
-- caller that touches it in a numeric context (e.g. atomic increment
-- in ItemController#bumpViewCount: `item.viewCount + 1L`).
--
-- Backfill any historical NULLs to 0 first (V46's null-safe backfill
-- already ran, so this is paranoia for any rows inserted between
-- deploys), then add the constraint. DEFAULT 0 stays so the column is
-- safe to omit from INSERTs.
UPDATE items SET view_count = 0 WHERE view_count IS NULL;

ALTER TABLE items
    ALTER COLUMN view_count SET NOT NULL;
