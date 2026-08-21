-- Pass-through processor pricing: record what the USER paid, per row.
--
-- The operator's pricing decision is that Stripe's processing cost is passed
-- through to the user rather than absorbed by the platform. A deposit of $100
-- now credits the wallet $96.80 and a $100 withdrawal pays out $99.50. That
-- makes the fee a real, user-visible term of the transaction, and a term the
-- user is quoted BEFORE they commit — so it has to be stored, not recomputed.
--
-- Recomputing at credit time from `platform.processing-fee-percent` would let a
-- config change between session creation and webhook delivery credit a number
-- different from the one the user saw on the deposit screen. Small window, but
-- the whole point of surfacing the fee up front is that the quoted number is
-- the number that happens; a quote the system does not remember is not a quote.
--
-- `amount` deliberately stays GROSS on both types:
--   DEPOSIT  — completeDeposit compares `amount * 100` against Stripe's
--              `session.amount_total`, which is what the card was charged.
--              Storing net here would make every live deposit fail that guard.
--   WITHDRAW — the wallet is debited the full requested amount; the fee comes
--              out of the payout, so the debit and `amount` must agree.
-- The net is therefore always `amount - COALESCE(fee_amount, 0)`.
--
-- Nullable with no default. Rows written before this migration genuinely had no
-- pass-through fee (the platform absorbed it), and a DEFAULT 0 would be a lie of
-- the opposite kind — it would assert "we measured this and it was zero" about
-- rows nobody measured. NULL reads as zero in every consumer, so historic rows
-- keep meaning exactly what they meant.
--
-- Postgres prod dialect; dev/test use H2 with ddl-auto so the entity mapping
-- creates this column there without Flyway.
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS fee_amount NUMERIC(19, 2);

-- A pass-through fee can never exceed what it is taken out of, and can never be
-- negative. Both directions are money bugs with a user on the wrong end: a fee
-- above `amount` credits a NEGATIVE deposit, and a negative fee pays out more
-- than was debited. The service refuses both before writing (see
-- PlatformLedgerService.feeExceedsAmount), and this is the backstop for a code
-- path that forgets to ask.
ALTER TABLE transactions
    DROP CONSTRAINT IF EXISTS chk_transactions_fee_amount_sane;

ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_fee_amount_sane
    CHECK (fee_amount IS NULL OR (fee_amount >= 0 AND fee_amount <= amount));
