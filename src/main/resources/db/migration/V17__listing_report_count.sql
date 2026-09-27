-- User-facing "Report listing" signal. Each user report increments the
-- counter; admins see listings sorted by report count in the moderation
-- queue. We don't store individual reports (PII) — just the aggregate
-- and a "last reported at" timestamp so stale reports age out of view.
ALTER TABLE listings
    ADD COLUMN IF NOT EXISTS report_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE listings
    ADD COLUMN IF NOT EXISTS last_reported_at BIGINT;

-- Per-reporter dedupe table — stops a single user from inflating a
-- listing's report_count by clicking the button multiple times. Also
-- used to rate-limit (one report per user per listing per 24h, enforced
-- in the service layer).
CREATE TABLE IF NOT EXISTS listing_reports (
    id              BIGSERIAL PRIMARY KEY,
    listing_id      BIGINT NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
    reporter_user_id BIGINT NOT NULL REFERENCES steam_users(id) ON DELETE CASCADE,
    reason          VARCHAR(80),
    note            VARCHAR(500),
    created_at      BIGINT NOT NULL,
    UNIQUE (listing_id, reporter_user_id)
);

CREATE INDEX IF NOT EXISTS idx_listing_reports_listing ON listing_reports(listing_id);
CREATE INDEX IF NOT EXISTS idx_listing_reports_created ON listing_reports(created_at);
