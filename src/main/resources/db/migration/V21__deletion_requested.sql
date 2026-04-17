-- GDPR / DSAR support: users can request their account be deleted.
-- Request is soft — sets deletion_requested_at, doesn't actually delete
-- rows. Admin reviews the queue, confirms no outstanding obligations
-- (pending withdrawals, active trades), and finalises via a separate
-- admin action. Cancelling the request before finalisation clears the
-- column back to NULL.
ALTER TABLE steam_users
    ADD COLUMN IF NOT EXISTS deletion_requested_at BIGINT;

-- Admin queue — "show me every user with a pending deletion request".
CREATE INDEX IF NOT EXISTS idx_steam_users_deletion_requested
    ON steam_users (deletion_requested_at)
    WHERE deletion_requested_at IS NOT NULL;
