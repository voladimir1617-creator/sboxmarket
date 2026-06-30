-- Defense-in-depth CHECK on wallets.balance (>= 0). [integrity audit — CRITICAL]
--
-- A negative wallet balance would silently defeat the `balance < price` guards
-- across the money paths (e.g. -9999 is NOT < a $50 listing), letting an
-- insolvent wallet buy unlimited inventory and breaking the ledger invariant
-- (sum of balances = deposits - withdrawals - purchases + credits). Every write
-- path already keeps balance >= 0:
--   * debits check `balance >= amount` BEFORE subtracting (PurchaseService.buy,
--     StripeService.requestWithdrawal);
--   * refund clawbacks clamp the result to 0 (refundDeposit, handleRefundCreated);
--   * credits only ADD;
--   * the admin adjustment (AdminService.creditWallet, which also accepts a
--     negative "debit" amount) rejects WOULD_GO_NEGATIVE.
-- This adds the DB-layer guarantee so no raw-SQL / ETL / replication-corruption
-- / future code path can ever persist a negative balance — the constraint never
-- fires in normal operation, it only catches a bug or corruption.
--
-- Postgres prod dialect; runs prod-only (dev uses H2 ddl-auto, Flyway disabled).
-- Existing rows are provably >= 0 (every write path enforces it), so the ALTER
-- cannot fail on legacy data.
ALTER TABLE wallets
    ADD CONSTRAINT chk_wallets_balance_nonneg
    CHECK (balance >= 0);
