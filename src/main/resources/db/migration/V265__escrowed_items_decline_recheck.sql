-- Declined-deposit re-check (Steam Web API key scam signal).
--
-- A scammer holding a seller's Steam Web API key can decline the bot's
-- deposit offer by script and send the seller a lookalike offer from its own
-- account. When a deposit comes back `declined`, SteamEscrowService records
-- the decline and, about ten minutes later, reads the seller's Steam
-- inventory once. If the asset has left the seller and the bot does not have
-- it, staff are alerted.
--
--   deposit_offer_sent_at  when the bot's deposit offer was sent, epoch millis.
--                          Lets the fraud rollup measure offer -> decline gaps.
--   deposit_declined_at    when the poller saw the offer `declined`.
--   decline_check          NULL = re-check pending; then CHECKING (claimed),
--                          GONE, PRESENT, BOT_HELD or UNKNOWN. Written once.
--
-- missing_since was not reused: the protection release sweep and the bot
-- inventory reconciler both read it with their own meaning.
--
-- All nullable, no default: NULL is the honest value for every older row, and
-- the sweep only selects rows with deposit_declined_at set.

ALTER TABLE escrowed_items
    ADD COLUMN IF NOT EXISTS deposit_offer_sent_at BIGINT;

ALTER TABLE escrowed_items
    ADD COLUMN IF NOT EXISTS deposit_declined_at BIGINT;

ALTER TABLE escrowed_items
    ADD COLUMN IF NOT EXISTS decline_check VARCHAR(16);

CREATE INDEX IF NOT EXISTS idx_escrowed_items_deposit_declined
    ON escrowed_items(deposit_declined_at);
