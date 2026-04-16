-- Index for SteamSyncService.findStaleForSync — orders by lastSyncedAt
-- to find users who haven't been synced in 24h. Without this the query
-- does a sequential scan + sort as the user table grows.
CREATE INDEX IF NOT EXISTS idx_steam_users_last_synced ON steam_users(last_synced_at ASC NULLS FIRST);
