-- "Anonymous shop" seller setting: when true, the seller's listings show a
-- neutral label on public listing surfaces instead of their name, avatar
-- and stall link. One new column with a default; nothing existing is
-- rewritten or deleted. Staff views and trade records keep the real name.
ALTER TABLE steam_users ADD COLUMN IF NOT EXISTS anonymous_shop BOOLEAN NOT NULL DEFAULT FALSE;
