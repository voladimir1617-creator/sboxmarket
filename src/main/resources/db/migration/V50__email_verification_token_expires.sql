-- Batch 647 — Time-bound email-verification tokens.
--
-- Before this migration the `email_verification_token` column had no
-- expiry. A token set once stayed valid until the user either clicked
-- the link or changed email again — meaning a token leaked via an old
-- mail archive, a compromised ex-address, or a mis-routed message
-- remained a foothold indefinitely.
--
-- We add a companion timestamp column (`_expires_at`, epoch ms). New
-- tokens set via /api/profile/email or /api/profile/email/resend get
-- a 24h window; /verify rejects tokens whose window has passed. The
-- 2FA-staging reuse of the token column doesn't set the expiry
-- (intra-session only) so this column stays null for those rows —
-- the verify logic treats null as "never expires" for backward-compat
-- with tokens that pre-date the migration AND to not break the 2FA
-- staging path.
--
-- Nullable so Hibernate can add it under ddl-auto=update without a
-- data backfill. Existing non-verified users with a token keep their
-- (now-orphan) token until their next resend.

ALTER TABLE steam_users
    ADD COLUMN IF NOT EXISTS email_verification_token_expires_at BIGINT;
