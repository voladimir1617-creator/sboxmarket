-- Mail-provider bounce / spam-complaint marker for the address on file.
-- One nullable column; nothing existing is rewritten or deleted.
-- 'BOUNCE' stops all mail to the address, 'COMPLAINT' stops everything
-- except security alerts. Cleared when the user enters a new address.
ALTER TABLE steam_users ADD COLUMN IF NOT EXISTS email_suppressed VARCHAR(16);
