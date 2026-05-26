-- V67: enforce one-review-per-(from_user_id, trade_id) at the DB layer.
--
-- V5__reviews.sql shipped the table with only a plain index on trade_id
-- and a comment that read "uniqueness is enforced at the service layer".
-- ReviewService.leaveReview implements that as a check-then-write —
--   1. findByFromUserIdAndTradeId(...)
--   2. if null → save(new Review(...))
-- which is a classic TOCTOU race. Two concurrent POST /api/reviews from
-- the same buyer on the same VERIFIED trade (a double-click on the
-- "Submit review" button, a buggy client retrying a 504, a curl loop)
-- can both observe `existing == null` in the same window and both INSERT.
-- The stall page then shows two ★★★★★ rows from the same buyer on the
-- same trade — which inflates the seller's review count and (because
-- the buyer-helpful-vote-self guard keys on `fromUserId`) gives them
-- a fresh "helpful" vote target.
--
-- The dual-write also breaks the idempotent-update branch on every
-- subsequent edit: findByFromUserIdAndTradeId returns ONE of the two
-- rows non-deterministically, so editing a comment can flip back and
-- forth between rows on each request.
--
-- A DB-level UNIQUE constraint closes the race regardless of how many
-- service replicas run, with no read-lock cost on the happy path —
-- Postgres rejects the loser's INSERT with a constraint violation,
-- which the service's catch (added alongside this migration) maps to
-- "you already reviewed this trade" instead of a 500.
--
-- Dedupe historical duplicates first, keeping the OLDEST row per
-- (from_user_id, trade_id) so the first-honest-impression wins and
-- any rage-spam follow-up rows are dropped. If two rows have an
-- identical createdAt timestamp (vanishingly unlikely with millis
-- precision, but possible), the smaller id wins — same effect.
DELETE FROM reviews
 WHERE id NOT IN (
    SELECT MIN(id)
      FROM reviews
     GROUP BY from_user_id, trade_id
 );

-- Match the existing naming convention (uq_<table>_<cols>) used by
-- uq_review_helpful_votes_review_user in V29 and uq_steam_users_canonical_email
-- in V63.
ALTER TABLE reviews
    ADD CONSTRAINT uq_reviews_from_user_trade UNIQUE (from_user_id, trade_id);
