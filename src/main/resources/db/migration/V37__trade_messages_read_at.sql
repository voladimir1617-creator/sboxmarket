-- Read receipts on trade chat messages. Without this a buyer sending
-- "did you ship the offer?" has no signal whether the seller has even
-- opened the chat — it's a black box that pushes both sides toward
-- the dispute escape hatch faster than necessary. CSFloat shows the
-- standard "✓✓" double-tick on own messages once the counterparty
-- has loaded the thread.
--
-- Set the first time the recipient pulls the message via
-- TradeService.listMessages — we don't track partial visibility (no
-- viewport observer), just thread-load = read. Mirrors WhatsApp's
-- early model.
ALTER TABLE trade_messages
    ADD COLUMN read_at BIGINT;

-- Partial index — the bulk-mark-read query targets only the unread
-- subset for a given (trade, recipient). Tiny index; cheap to
-- maintain since each row is updated at most once.
CREATE INDEX idx_trade_messages_unread
    ON trade_messages(trade_id, sender_user_id)
    WHERE read_at IS NULL;
