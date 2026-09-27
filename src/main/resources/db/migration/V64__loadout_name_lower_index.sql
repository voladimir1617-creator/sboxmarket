-- LoadoutRepository.searchPublic filters with LOWER(l.name) LIKE LOWER(...)
-- but loadouts had no functional index on LOWER(name) — the public loadout
-- search did a sequential scan and a per-row LOWER() on every PUBLIC loadout
-- to find browse-page matches. items got an equivalent idx_items_name in V1;
-- loadouts was the missing twin.
CREATE INDEX IF NOT EXISTS idx_loadouts_name_lower ON loadouts(LOWER(name));
