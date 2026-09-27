-- Soft-redact column for moderated trade-chat messages. When staff
-- removes a message via AdminService.redactTradeMessage the row stays
-- but `redacted_at` is stamped and `body` is cleared. Consumers render
-- a placeholder ("🛡 Message removed by moderators") in its place so
-- chat continuity is preserved — a conversation "did you send it?" /
-- "yes I sent…" / "thanks" doesn't become "did you send it?" / "thanks"
-- with a silent gap where the offending message was.
--
-- Previously: TradeService.deleteMessage hard-deleted the row. That
-- leaked the moderation action to neither party (both sides just saw
-- a gap) and made "did staff intervene here?" hard to answer without
-- querying the audit log.

ALTER TABLE trade_messages
    ADD COLUMN redacted_at BIGINT;

-- Partial index — almost every row has redacted_at IS NULL, so a
-- partial index on the redacted set keeps the index tiny while still
-- letting staff efficiently query "which trades have had messages
-- redacted?" (e.g. for compliance reports).
CREATE INDEX idx_trade_messages_redacted
    ON trade_messages(trade_id, redacted_at)
    WHERE redacted_at IS NOT NULL;
