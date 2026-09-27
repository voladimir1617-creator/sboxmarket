-- Exempt the platform treasury row from the wallets.balance >= 0 CHECK.
--
-- V254 added `CHECK (balance >= 0)` on wallets as a defense-in-depth guarantee
-- that no user wallet can ever go negative — a negative balance silently
-- defeats every `balance < price` guard in the money paths. That reasoning is
-- correct and stays in force for every USER wallet.
--
-- It does not hold for the platform's own account. PlatformLedgerService keeps
-- the platform's margin in a reserved wallet row (username
-- '__platform_treasury__'): revenue events CREDIT it, cost events DEBIT it, and
-- the balance IS the margin. That figure is expected to be negative for a while
-- — deposits credit the user the GROSS amount while the payment processor keeps
-- 2.9% + $0.30, so the platform pays real money per deposit and earns it back
-- only through trade volume. UNIT-ECONOMICS.md puts break-even at roughly 1.6 to
-- 4.3 trades per deposited dollar; until the platform clears that bar the
-- treasury is legitimately below zero, and clamping it at zero would erase
-- exactly the signal the account exists to expose.
--
-- Without this migration the FIRST live deposit fails outright: the treasury is
-- created at 0.00 and the first posting against it is a PROCESSING_COST debit,
-- so `0.00 - 3.20` violates chk_wallets_balance_nonneg, the insert throws
-- DataIntegrityViolationException, the surrounding deposit transaction is marked
-- rollback-only, and the user's wallet is never credited for a card that was
-- genuinely charged. A CHECK constraint written to protect user funds would have
-- become the thing that ate them.
--
-- The exemption is keyed on the reserved username rather than on an id or a
-- boolean flag: '__platform_treasury__' cannot be produced by any wallet-creation
-- path (user wallets are 'steam_<steamId64>', the seed wallet is 'demo'), so no
-- user row can ever fall through this clause and inherit permission to go
-- negative. That is the same reasoning that made the username reserved in the
-- first place.
--
-- Postgres prod dialect; runs prod-only (dev uses H2 ddl-auto, Flyway disabled),
-- which is precisely why this could not be caught by the test suite and is
-- pinned instead by WalletBalanceCheckExemptsTreasurySpec asserting the shipped
-- SQL. DROP ... IF EXISTS so the migration is safe on a database provisioned
-- before V254 as well as one that already carries the stricter constraint.
ALTER TABLE wallets
    DROP CONSTRAINT IF EXISTS chk_wallets_balance_nonneg;

ALTER TABLE wallets
    ADD CONSTRAINT chk_wallets_balance_nonneg
    CHECK (balance >= 0 OR username = '__platform_treasury__');
