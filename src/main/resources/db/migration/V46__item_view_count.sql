-- V46 — per-item view counter (batch 409).
--
-- Bumped atomically on every `GET /api/items/{id}` read. Surfaces on
-- the ItemModal header as a "👁 N views" chip — a liquidity + interest
-- social-proof signal that complements the existing watcher-count,
-- buy-order-count and sales-velocity chips.
--
-- DEFAULT 0 so existing rows don't get null; the single ALTER is
-- cheap on the current-size catalogue (~80 rows).
ALTER TABLE items
    ADD COLUMN IF NOT EXISTS view_count BIGINT DEFAULT 0;

-- Null-safe back-fill in case the DEFAULT didn't apply on some older
-- Postgres path.
UPDATE items SET view_count = 0 WHERE view_count IS NULL;
