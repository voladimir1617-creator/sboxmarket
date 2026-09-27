-- Optional seller reply to a review. Lets a seller answer feedback
-- directly so the review doesn't sit unanswered in front of future
-- buyers. Sanitised + 300-char cap at the service layer.
ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS seller_reply VARCHAR(300);
ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS seller_reply_at BIGINT;
