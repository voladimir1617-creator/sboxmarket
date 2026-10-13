-- A short code per custody row, shown to the seller on the site and written
-- into every trade offer the bot sends for that item, so a seller can tell
-- our bot's offer from an impersonator's before accepting it.
--
-- Nullable, no backfill: older rows simply have no code and their offers
-- carry the plain message. Postgres prod dialect; dev/test use H2 ddl-auto.
ALTER TABLE escrowed_items
    ADD COLUMN IF NOT EXISTS security_code VARCHAR(8);
