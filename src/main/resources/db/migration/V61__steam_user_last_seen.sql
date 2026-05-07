-- V61: add `last_seen_at` to steam_users so the marketplace can render real
-- "Online now" presence next to seller names instead of a deterministic-seed
-- fallback (see ship #34 in production_checklist.md). The seed pattern was
-- the right call when no presence data existed; this column closes that gap
-- so signed-in browsing sellers actually read as Online to buyers.
--
-- Bumped by `PresenceFilter` on every authenticated request, throttled to
-- one DB write per 60 seconds per user (via in-memory last-write tracker).
-- Read by `ListingController#decorateWithSellerLastSeen` to populate the
-- existing `Listing.sellerLastSeenAt` transient field on every list response.
--
-- Backfill from `last_login_at` so accounts that haven't logged in since
-- this migration deployed don't suddenly appear "always offline" — the
-- worst case is a stale presence reading until the next page hit, then
-- the filter overwrites it.
ALTER TABLE steam_users
    ADD COLUMN IF NOT EXISTS last_seen_at BIGINT;

UPDATE steam_users
   SET last_seen_at = last_login_at
 WHERE last_seen_at IS NULL
   AND last_login_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_steam_users_last_seen_at
    ON steam_users (last_seen_at);
