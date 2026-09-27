-- Optional self-written seller bio shown on the public /stall/{id} page.
-- Capped at 500 chars at the JPA layer (same limit as the ban-reason + 2FA
-- secret columns) and sanitised via TextSanitizer.medium on write, so raw
-- HTML can never land in the DB. Nullable — sellers who don't care leave
-- it empty and the UI hides the bio section entirely.
ALTER TABLE steam_users
  ADD COLUMN IF NOT EXISTS stall_bio VARCHAR(500);
