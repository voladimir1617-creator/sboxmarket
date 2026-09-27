-- Sitewide announcement banner. A single active row at a time; admins
-- create/deactivate via /api/admin/announcements. Users dismiss via
-- localStorage so they don't see the same banner on every page load.
CREATE TABLE IF NOT EXISTS announcements (
    id               BIGSERIAL PRIMARY KEY,
    message          VARCHAR(500) NOT NULL,
    severity         VARCHAR(12)  NOT NULL DEFAULT 'INFO',
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    expires_at       BIGINT,
    created_at       BIGINT       NOT NULL,
    created_by_user_id BIGINT
);

-- Partial index over the hot path: the public endpoint scans active rows
-- every page load.
CREATE INDEX IF NOT EXISTS idx_announcements_active
    ON announcements (created_at DESC)
    WHERE active = true;
