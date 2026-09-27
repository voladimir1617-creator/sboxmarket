-- Bot-escrow RETURN retry state — so a failed return can never strand a real
-- seller's real item in the bot's inventory forever.
--
-- THE BUG THIS CLOSES
-- SteamEscrowService.returnToSeller is called exactly once from each of its
-- four exit paths (SellService.cancelListing, TradeService cancel/sweeper,
-- BidService expired-auction settle). Every one of those call sites wraps it in
-- a best-effort try/catch and ignores the boolean it returns. The method itself
-- returns FALSE — deliberately, leaving the row IN_CUSTODY — whenever the bot
-- call fails, and the bot call fails for entirely routine reasons:
-- RATE_LIMITED, NOT_READY (sidecar restarting / Steam session re-auth),
-- TRANSPORT_ERROR, TIMEOUT. Steam rate-limits trade APIs as a matter of course.
--
-- Nothing retried. There is no scheduled sweep over IN_CUSTODY rows, and there
-- cannot be a blind one: an IN_CUSTODY row is the NORMAL state of a live,
-- for-sale listing, so "retry every IN_CUSTODY row" would re-send the item out
-- from under every active listing on the market. The row had no way to say
-- "a return was asked for and did not happen" — so the failure was invisible
-- and permanent, and the asset was a real item belonging to a real user.
--
-- The pre-existing comment in TradeService's return block calls the
-- missing-CALL version of this "stranded the physical item in the bot forever".
-- That fix added the call. This one covers the call that FAILS.
--
-- WHY TWO COLUMNS, NOT A NEW custody_state
-- A `RETURN_PENDING` state would have been the smaller diff, but custody_state
-- is read as an authorisation gate elsewhere — heldAssetIdForListing() hands
-- the delivery leg an asset id only when the row reads IN_CUSTODY. Moving a row
-- out of IN_CUSTODY to mark a failed return would silently change what the
-- delivery leg is allowed to send. These columns are purely additive: they
-- annotate the row without touching any existing gate's answer.
--
--   return_requested_at — when a return was FIRST asked for. NULL means nobody
--                         has asked, which is the state of every live listing,
--                         so the retry sweep's candidate set is empty by
--                         default and can never touch a for-sale item.
--   return_attempts     — how many times we have tried. Doubles as the
--                         compare-and-swap token for the multi-pod claim, the
--                         same posture as claimTimeoutPendingDeposit: the row
--                         stays IN_CUSTODY throughout, so there is no state
--                         transition to claim on and the attempt counter is
--                         what two pods race over instead.
--
-- Both nullable / defaulted rather than backfilled. Rows written before this
-- migration were never asked to return, and stamping them with a timestamp
-- would enqueue every historic custody row into the retry sweep on first boot.
--
-- Postgres prod dialect; dev/test use H2 with ddl-auto, so the entity mapping
-- creates these columns there without Flyway.

ALTER TABLE escrowed_items ADD COLUMN return_requested_at BIGINT;
ALTER TABLE escrowed_items ADD COLUMN return_attempts INTEGER NOT NULL DEFAULT 0;

-- Retry-sweep hot path. Partial index: the sweep only ever asks for rows where
-- a return was requested, which is a vanishingly small slice of the table
-- (every other row is a live listing or already terminal), so indexing the
-- whole table on custody_state would be mostly dead weight.
CREATE INDEX idx_escrowed_items_pending_return
    ON escrowed_items(updated_at)
    WHERE return_requested_at IS NOT NULL AND custody_state = 'IN_CUSTODY';

-- Deposit RE-REQUEST sweep hot path (the sibling gap this wave also closes).
-- findPendingDeposits requires deposit_offer_id IS NOT NULL, so a deposit whose
-- offer never got created — transient bot failure at list time, or a seller
-- with no trade URL — was returned by NO poller and simply waited 24h to be
-- timed out and cancelled. This index backs the sweep that re-requests it.
CREATE INDEX idx_escrowed_items_unsent_deposit
    ON escrowed_items(updated_at)
    WHERE deposit_offer_id IS NULL AND custody_state = 'PENDING_DEPOSIT';
