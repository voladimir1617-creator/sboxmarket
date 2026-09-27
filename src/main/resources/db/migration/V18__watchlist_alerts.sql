-- Server-side price alerts. Until now watchlist alerts lived in
-- localStorage which meant they only fired when the user happened to
-- open the Watchlist page. Persistent alerts + a scheduled sweeper
-- move that off the client.
--
-- One row per (user, item, status=ACTIVE). When the item's
-- lowestPrice crosses at/below targetPrice, the sweeper pushes a
-- WATCHLIST_PRICE_DROP notification and flips status to FIRED so it
-- doesn't re-notify for every subsequent tick.
CREATE TABLE IF NOT EXISTS watchlist_alerts (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES steam_users(id) ON DELETE CASCADE,
    item_id         BIGINT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    target_price    NUMERIC(10,2) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      BIGINT NOT NULL,
    fired_at        BIGINT
);

-- Partial-ish unique guard: a user shouldn't accumulate ACTIVE alerts
-- on the same item. Re-setting an alert for an item should update the
-- existing row, not insert a sibling. Service layer enforces the
-- upsert; the unique constraint is the DB-level backstop.
CREATE UNIQUE INDEX IF NOT EXISTS idx_watchlist_alerts_user_item_active
    ON watchlist_alerts (user_id, item_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_watchlist_alerts_user
    ON watchlist_alerts (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_watchlist_alerts_active_item
    ON watchlist_alerts (item_id)
    WHERE status = 'ACTIVE';
