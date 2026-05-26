--
-- V5 — buyer-to-seller reviews. Tied to a completed trade so we can
-- guarantee the author actually transacted with the seller. One review
-- per (from_user_id, trade_id) pair is enforced by the
-- uq_reviews_from_user_trade UNIQUE constraint added in V67 — the
-- original "enforced at the service layer" approach was a TOCTOU race
-- (see V67's comment for the full diagnosis). The composite index on
-- trade_id below still serves the by-trade lookup queries.
--
-- Schema mirrors com.sboxmarket.model.Review exactly — any field change
-- there needs a new migration file, never an edit to this one.
--

CREATE TABLE IF NOT EXISTS reviews (
    id                 BIGSERIAL PRIMARY KEY,
    from_user_id       BIGINT       NOT NULL,
    to_user_id         BIGINT       NOT NULL,
    trade_id           BIGINT       NOT NULL,
    rating             INTEGER      NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment            VARCHAR(500),
    from_display_name  VARCHAR(80),
    item_name          VARCHAR(255),
    created_at         BIGINT       NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_review_to    ON reviews(to_user_id);
CREATE INDEX IF NOT EXISTS idx_review_from  ON reviews(from_user_id);
CREATE INDEX IF NOT EXISTS idx_review_trade ON reviews(trade_id);
