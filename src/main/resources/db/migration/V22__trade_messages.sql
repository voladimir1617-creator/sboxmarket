-- Counterparty chat thread inside a trade. Buyers + sellers often need
-- to coordinate the Steam offer (wallet, schedule, "DM me on Discord")
-- during the escrow window. Until now the only channel was opening a
-- support ticket, which hid the message from the counterparty. This
-- gives them a private direct thread.
CREATE TABLE IF NOT EXISTS trade_messages (
    id              BIGSERIAL PRIMARY KEY,
    trade_id        BIGINT NOT NULL REFERENCES trades(id) ON DELETE CASCADE,
    sender_user_id  BIGINT NOT NULL REFERENCES steam_users(id) ON DELETE CASCADE,
    body            VARCHAR(2000) NOT NULL,
    created_at      BIGINT NOT NULL
);

-- Threading read: newest messages per trade. Every ordering use case
-- needs this composite.
CREATE INDEX IF NOT EXISTS idx_trade_messages_trade_created
    ON trade_messages (trade_id, created_at);

-- Per-user rate-limit scan: how many did this sender post in the last N ms?
CREATE INDEX IF NOT EXISTS idx_trade_messages_sender_created
    ON trade_messages (sender_user_id, created_at);
