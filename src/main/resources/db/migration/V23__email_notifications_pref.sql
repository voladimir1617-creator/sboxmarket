-- User preference: receive non-essential email notifications?
-- "Non-essential" = outbid/won/ban/deletion-receipt/withdrawal-status.
-- Security + operational emails (verification token, password reset)
-- always send regardless of this flag.
--
-- Default ON so existing users keep their current experience; users
-- who don't want the noise can flip it off in Settings.
ALTER TABLE steam_users
    ADD COLUMN IF NOT EXISTS email_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE;
