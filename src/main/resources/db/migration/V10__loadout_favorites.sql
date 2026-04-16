--
-- V10 — per-user loadout favorites. Before this table the loadout
-- "favorites" counter was driven by a public unauthenticated POST
-- endpoint, which let anyone inflate counts in a curl loop (bug #16).
-- Favoriting now requires a login, and the (user_id, loadout_id)
-- uniqueness is enforced at the DB level so a retry/race cannot
-- double-count.
--

CREATE TABLE IF NOT EXISTS loadout_favorites (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    loadout_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT uq_loadout_fav_user_loadout UNIQUE (user_id, loadout_id)
);

CREATE INDEX IF NOT EXISTS idx_loadout_fav_user    ON loadout_favorites(user_id);
CREATE INDEX IF NOT EXISTS idx_loadout_fav_loadout ON loadout_favorites(loadout_id);
