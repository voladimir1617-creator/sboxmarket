-- Steam Trade Protection on bot deposits, and the bot-inventory reconciler.
--
-- Since July 2025 Steam lets the sender of a CS2 trade reverse it for 7 days,
-- and the receiver cannot move the item on in that window. Skin.Club answers
-- this by holding every skin deposit for 8 days before crediting it (their FAQ,
-- "Why is my skin deposit on hold for 8 days?"). Whether Steam extends the
-- protection to s&box (590830) is not confirmed, so SkinBox waits on both a
-- configured window (steam.escrow.trade-protection-hours) and Steam's own
-- tradable flag before a deposited skin's listing goes live.
--
--   protected_until  earliest end of the protection window, epoch millis.
--   missing_since    first successful bot-inventory read without the skin;
--                    a second one past the confirm window means the seller
--                    reversed the deposit (in protection) or the skin is gone
--                    (in custody: the listing is pulled and an operator told).
--
-- Both nullable with no default: NULL is the honest value for every row
-- written before this, and both read NULL as "nothing to do", so no existing
-- row changes meaning. New states IN_TRADE_PROTECTION (19 chars) and REVERSED
-- fit custody_state VARCHAR(24), which has no CHECK constraint.

ALTER TABLE escrowed_items
    ADD COLUMN IF NOT EXISTS protected_until BIGINT;

ALTER TABLE escrowed_items
    ADD COLUMN IF NOT EXISTS missing_since BIGINT;

CREATE INDEX IF NOT EXISTS idx_escrowed_items_trade_protection
    ON escrowed_items(protected_until)
    WHERE custody_state = 'IN_TRADE_PROTECTION';
