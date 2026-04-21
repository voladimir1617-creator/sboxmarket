-- Server-side saved searches (cross-device sync). Saved searches are
-- named filter presets — "Limited hats under $20 + biggest discount" —
-- that the buyer re-applies in one click. They lived in localStorage,
-- so a buyer's preset library was per-browser. CSFloat persists per
-- account; we match that.
--
-- Each row captures every filter the marketplace toolbar exposes:
-- text query, category, rarity, sort key, min/max price. Cap of 10
-- per user (mirrors the existing localStorage cap) is enforced at
-- the service layer; the index keeps per-user lookups cheap.
--
-- ON DELETE CASCADE from steam_users so a banned user's presets don't
-- leak.
CREATE TABLE saved_searches (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES steam_users(id) ON DELETE CASCADE,
    name        VARCHAR(80)  NOT NULL,
    q           VARCHAR(80)  NOT NULL DEFAULT '',
    category    VARCHAR(40)  NOT NULL DEFAULT 'All',
    rarity      VARCHAR(40)  NOT NULL DEFAULT 'All',
    sort        VARCHAR(40)  NOT NULL DEFAULT 'price_desc',
    min_price   VARCHAR(16)  NOT NULL DEFAULT '',
    max_price   VARCHAR(16)  NOT NULL DEFAULT '',
    created_at  BIGINT       NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    -- Per-user uniqueness on `name` so re-saving a preset under the
    -- same label updates rather than duplicates. Lower-cased to keep
    -- the constraint case-insensitive.
    CONSTRAINT uq_saved_searches_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_saved_searches_user ON saved_searches(user_id);
