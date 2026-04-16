--
-- V3 — optional email address, verification token, and TOTP 2FA secret
-- on steam_users. See SteamUser.groovy + ProfileController.
--

ALTER TABLE steam_users ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE steam_users ADD COLUMN IF NOT EXISTS email_verification_token VARCHAR(64);
ALTER TABLE steam_users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN DEFAULT FALSE;
ALTER TABLE steam_users ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(64);
ALTER TABLE steam_users ADD COLUMN IF NOT EXISTS last_totp_step BIGINT;

CREATE INDEX IF NOT EXISTS idx_steam_users_email ON steam_users(email);
