-- Server-side watchlist (cross-device sync). Until now the watchlist was
-- localStorage-only — a buyer who starred items on desktop saw an empty
-- watchlist on mobile, and a browser-cache wipe lost everything. CSFloat
-- persists watchlists per-account; we match that.
--
-- One row per (user_id, item_id) — the unique constraint enforces
-- "starring is idempotent" at the DB level so race-y double-clicks
-- never produce duplicate rows. Per-(user,item) lookup is served by
-- the unique index; per-user list (the typical "show my watchlist"
-- query) is served by the user_id index.
--
-- ON DELETE CASCADE from both parents so a banned user's watchlist
-- doesn't leak orphan rows and a deleted catalogue item doesn't
-- linger as a phantom star.
CREATE TABLE watchlist_items (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL REFERENCES steam_users(id) ON DELETE CASCADE,
    item_id    BIGINT    NOT NULL REFERENCES items(id)       ON DELETE CASCADE,
    created_at BIGINT    NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    CONSTRAINT uq_watchlist_items_user_item UNIQUE (user_id, item_id)
);

CREATE INDEX idx_watchlist_items_user ON watchlist_items(user_id);
CREATE INDEX idx_watchlist_items_item ON watchlist_items(item_id);
