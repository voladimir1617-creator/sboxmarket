-- V48: Wallet freeze flag (batch 509).
--
-- Lets staff halt money-in / money-out on a specific wallet without
-- nuking the whole account. Banning is nuclear and triggers the full
-- ban cascade (listings cancelled, offers rejected, trades aborted).
-- A freeze is the surgical alternative for regulatory holds, fraud
-- investigations, or user-requested lockouts while staff verify an
-- identity or unusual activity.
--
-- WalletController refuses POST /deposit, /withdraw, /confirm-deposit
-- and all cart/purchase writes when the flag is true. User can still
-- sign in, browse, and see their frozen-chip on the wallet page.
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS frozen BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS frozen_reason VARCHAR(500) NULL;
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS frozen_at BIGINT NULL;

-- Partial index — the freeze state is checked on every money-in/out
-- path; a partial index on frozen=true keeps that read cheap as the
-- wallets table grows (in a healthy system most wallets are unfrozen).
CREATE INDEX IF NOT EXISTS idx_wallets_frozen
    ON wallets (id)
    WHERE frozen = TRUE;
