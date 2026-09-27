-- Admin-only internal notes on a user. Distinct from ban_reason
-- (which is user-visible on ban screens) — these are staff-internal
-- free-text annotations for ops context that shouldn't leak.
ALTER TABLE steam_users
    ADD COLUMN IF NOT EXISTS admin_notes TEXT;
