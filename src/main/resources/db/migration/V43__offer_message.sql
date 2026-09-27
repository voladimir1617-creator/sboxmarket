-- V43 — optional buyer-supplied message on offers (CSFloat parity, batch 382).
--
-- Lets a buyer attach a short note ("brand new account, fast pay") to an
-- offer so the seller has context before deciding to ACCEPT/REJECT/COUNTER.
-- 280-char cap matches the Twitter character limit — long enough for a
-- real explanation, short enough to discourage essay-mode abuse and to
-- stay readable in the seller's offer inbox row without truncation.
--
-- Sanitised through TextSanitizer.cleanShort on write so HTML / control
-- chars / runs of whitespace don't smuggle into the seller-visible row.
ALTER TABLE offers
    ADD COLUMN IF NOT EXISTS message VARCHAR(280);
