-- 2FA backup-code recovery. Without this a user who loses their
-- authenticator device is locked out of withdrawals (and every other
-- 2FA-gated action) forever — they can't even disable 2FA since
-- /2fa/disable requires a live TOTP code.
--
-- Stored as space-separated SHA-256 hex hashes of one-time codes. Each
-- code is shown to the user exactly once at enrollment (or on
-- regenerate) and then only the hash stays in the DB. On use the code
-- is consumed (its hash is removed from the set) so the same backup
-- code can't be replayed.
--
-- Codes live as a single column for simplicity — we never need to
-- query by code globally (only "does this user have this hash?"), so
-- a separate table would buy us nothing.
ALTER TABLE steam_users
    ADD COLUMN totp_recovery_codes TEXT;
