--
-- V9 — search indexes on steam_users.
--
-- The CSR and Admin panels both have a "Look up a user" search that
-- previously did `findAll().findAll { contains() }` — a full-table scan
-- on every keystroke. Two indexes fix this: one on LOWER(display_name)
-- for case-insensitive name search, and a plain index on role for the
-- admin-bootstrap + CSR dashboard counts.
--
-- The existing UNIQUE constraint on steam_id64 already gives us the
-- Steam ID lookup for free.
--
CREATE INDEX IF NOT EXISTS idx_steam_users_display_lower ON steam_users(LOWER(display_name));
CREATE INDEX IF NOT EXISTS idx_steam_users_role          ON steam_users(role);
CREATE INDEX IF NOT EXISTS idx_steam_users_banned        ON steam_users(banned) WHERE banned = TRUE;
