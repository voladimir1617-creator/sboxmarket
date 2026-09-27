-- Batch 670 — scope column for API keys. Existing keys default to 'RW'
-- (the pre-scope behaviour); new keys can be issued as 'RO' so a read-only
-- bot can fetch listings / catalogue without the ability to place orders.
-- Narrow VARCHAR keeps the index light + constrains the value space.
ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS scope VARCHAR(4) NOT NULL DEFAULT 'RW';

-- Defensive CHECK so a stray UPDATE can't set an unrecognised scope.
-- Only the two values are ever emitted by the app; any future scope
-- (e.g. SELL-only) will extend this constraint at that migration.
ALTER TABLE api_keys
    DROP CONSTRAINT IF EXISTS api_keys_scope_chk;
ALTER TABLE api_keys
    ADD CONSTRAINT api_keys_scope_chk
    CHECK (scope IN ('RO', 'RW'));
