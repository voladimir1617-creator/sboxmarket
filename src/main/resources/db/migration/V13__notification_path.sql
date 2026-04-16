-- Add `path` column to notifications so the frontend can navigate straight to
-- the referenced item / trade / offer when the user clicks a row in the bell.
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS path VARCHAR(160);
