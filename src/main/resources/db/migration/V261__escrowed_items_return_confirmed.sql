-- When Steam reported the seller accepted the bot's return offer.
--
-- A custody row goes RETURNED the moment the return offer is SENT. If the
-- seller declined it, or let it expire, the item was still in the bot while
-- the row said it had gone home, and nothing ever looked again. The return
-- poller now watches RETURNED rows with this column NULL and puts a failed
-- return back IN_CUSTODY so the retry sweep sends it again.
--
-- Nullable, no backfill: older RETURNED rows get one poll of their offer.
-- Postgres prod dialect; dev/test use H2 with ddl-auto.
ALTER TABLE escrowed_items
    ADD COLUMN IF NOT EXISTS return_confirmed_at BIGINT;
