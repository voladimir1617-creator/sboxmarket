-- V72: Stripe Connect (Express) payout rail — real money-out for sellers.
--
-- Until now the withdrawal path (StripeService.requestWithdrawal) only
-- debited the wallet and wrote a PENDING `WITHDRAW` row with
-- stripeReference='manual'; no payout API was ever called and an
-- operator had to settle off-platform. To move REAL money to a seller
-- we onboard them onto a Stripe Connect Express account (which doubles
-- as the KYC / identity-verification step) and then create a Stripe
-- Transfer from the platform balance to that connected account.
--
-- These two columns live on the wallet (one wallet ↔ one Steam user via
-- `username = steam_<steamId64>`), mirroring where the freeze flag (V48)
-- and balance already live, so the payout code path reads a single row:
--
--   * stripe_connect_account_id — the `acct_…` id Stripe returns from
--     Account.create. NULL until the user starts onboarding. The
--     destination of every real Transfer. Indexed so the `account.updated`
--     webhook can resolve account-id → wallet without a table scan.
--
--   * payouts_enabled — mirror of Stripe's `account.payouts_enabled`
--     flag, flipped to TRUE by the `account.updated` webhook once Stripe
--     reports the connected account has cleared KYC and can receive
--     payouts. requestWithdrawal REJECTS (CONNECT_ONBOARDING_REQUIRED)
--     when this is false in live mode, so we never attempt a Transfer to
--     an un-onboarded / restricted account.
--
-- Both nullable / defaulted so the column is safe to backfill on the
-- existing wallets table (every current row gets account_id=NULL,
-- payouts_enabled=FALSE — i.e. "not onboarded", the correct starting
-- state). H2 (dev) and Postgres (prod) both accept this exact DDL
-- (`ADD COLUMN IF NOT EXISTS`, `BOOLEAN`, partial index with WHERE).

ALTER TABLE wallets
    ADD COLUMN IF NOT EXISTS stripe_connect_account_id VARCHAR(64) NULL;

ALTER TABLE wallets
    ADD COLUMN IF NOT EXISTS payouts_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index — the `account.updated` webhook looks a wallet up by its
-- connected-account id, and only a minority of wallets ever onboard, so a
-- partial index on the non-null subset keeps that lookup O(log onboarded)
-- without bloating the index for the un-onboarded majority. The id is
-- effectively unique per Stripe (one acct ↔ one wallet), but we use a
-- plain (non-UNIQUE) partial index to stay tolerant of any future
-- re-onboard edge that briefly reuses an id rather than 500-ing the
-- webhook on a constraint violation.
CREATE INDEX IF NOT EXISTS idx_wallets_stripe_connect_account_id
    ON wallets (stripe_connect_account_id)
    WHERE stripe_connect_account_id IS NOT NULL;
