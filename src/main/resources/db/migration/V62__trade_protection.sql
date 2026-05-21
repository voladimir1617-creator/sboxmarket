-- Trade Protection — CSFloat's signature buyer-side safety add-on.
--
-- An OPTIONAL paid extra a buyer can enable at checkout (or on an
-- already-open trade). When enabled the buyer pays a small protection
-- fee (2% of item price, floored at $0.25) into platform revenue, and
-- one row is created here, tied 1:1 to the parent trade.
--
-- If the trade later fails through no fault of the buyer — auto-cancel
-- on a seller timeout, an upheld dispute, or any seller-fault cancel —
-- the protection auto-CLAIMs and the buyer is refunded the FULL item
-- price with no support ticket. A trade that completes normally
-- (VERIFIED) leaves the protection ACTIVE → EXPIRED; the fee is kept.
--
-- Statuses: ACTIVE (live cover), CLAIMED (paid out), EXPIRED (trade
-- settled fine, cover lapsed).
--
-- ON DELETE CASCADE from trades so a hard trade delete doesn't leave
-- an orphan protection row. UNIQUE(trade_id) enforces the 1:1 tie so a
-- double-click on "Enable protection" can't stack two fee charges.
CREATE TABLE IF NOT EXISTS trade_protections (
    id              BIGSERIAL PRIMARY KEY,
    trade_id        BIGINT        NOT NULL REFERENCES trades(id) ON DELETE CASCADE,
    buyer_user_id   BIGINT,
    -- The fee the buyer paid for cover. Platform revenue, never refunded.
    fee_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    -- The amount paid back to the buyer on a claim (the item price).
    coverage_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    -- ACTIVE / CLAIMED / EXPIRED
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    -- Free-text reason a claim paid out (set when status → CLAIMED).
    claim_reason    VARCHAR(255),
    created_at      BIGINT        NOT NULL,
    updated_at      BIGINT        NOT NULL,
    -- Set when status leaves ACTIVE (claim payout or expiry).
    resolved_at     BIGINT,
    CONSTRAINT uq_trade_protections_trade UNIQUE (trade_id)
);

-- Status lookup — drives the admin "active cover" / "claims" stat
-- cards and any future claim-rate reporting.
CREATE INDEX IF NOT EXISTS idx_trade_protections_status
    ON trade_protections (status);

-- Per-buyer lookup for the "my protected trades" surface.
CREATE INDEX IF NOT EXISTS idx_trade_protections_buyer
    ON trade_protections (buyer_user_id);
