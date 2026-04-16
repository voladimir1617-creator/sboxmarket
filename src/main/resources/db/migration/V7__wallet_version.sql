--
-- V7 — optimistic locking on wallets.
--
-- Same rationale as V6 on listings: wallets are a shared mutable resource
-- that two concurrent transactions (e.g. a deposit + a purchase racing
-- the same user's wallet) can both read-modify-write. @Version catches
-- the conflict at commit time and rolls the loser back.
--
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS version INTEGER DEFAULT 0;
UPDATE wallets SET version = 0 WHERE version IS NULL;
