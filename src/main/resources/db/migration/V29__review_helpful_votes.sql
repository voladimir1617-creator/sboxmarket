-- "Helpful" votes on reviews. Buyers reading a seller's stall want
-- high-signal reviews (detailed + verified purchase) to bubble above
-- the one-liners. CSFloat shipped this early; we've been relying on
-- raw chronological order which is the wrong sort for long-tail stalls.
--
-- One row per (reviewId, voterUserId) so:
--   a) every user can toggle exactly once per review
--   b) the helpful count is a simple COUNT over the table
--   c) staff can wipe a user's votes if they get banned for vote-rigging
--      (`DELETE ... WHERE user_id = ?`) without having to reconstruct
--      counter state on each review row.
CREATE TABLE review_helpful_votes (
    id         BIGSERIAL PRIMARY KEY,
    review_id  BIGINT    NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    user_id    BIGINT    NOT NULL REFERENCES steam_users(id) ON DELETE CASCADE,
    created_at BIGINT    NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    CONSTRAINT uq_review_helpful_votes_review_user UNIQUE (review_id, user_id)
);

-- Count aggregate is most-often called as "helpful count for review X"
-- alongside "did *I* vote on review X?", so an index on review_id keeps
-- both queries cheap on a stall with hundreds of reviews.
CREATE INDEX idx_review_helpful_votes_review ON review_helpful_votes(review_id);
CREATE INDEX idx_review_helpful_votes_user   ON review_helpful_votes(user_id);
