-- Batch 745 — reviews can be edited by their author within a 14-day
-- window. `editedAt` is set on the first edit and bumped on each
-- subsequent save. The UI shows "· edited" next to the createdAt
-- stamp so future buyers aren't misled by a silently-rewritten
-- rating. Null = never edited (the default for every pre-existing row).
ALTER TABLE reviews ADD COLUMN edited_at BIGINT;
