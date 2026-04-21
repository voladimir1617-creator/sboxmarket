-- V39: widen listings.description from 64 -> 500 chars.
--
-- Batch 304 added a `description` input to the sell form (500-char cap
-- in the DTO + service layer) and surfaced the field on public ItemModal
-- listings rows. But the underlying column was still `VARCHAR(64)` —
-- any description longer than 64 chars would round-trip through the
-- service cleanly and then fail at Hibernate persist with a
-- `value too long` error.
--
-- This migration brings the column size up to 500 to match the sanitiser
-- cap + the HTML input's maxLength. Existing rows are already <= 64 so
-- the ALTER is a no-op scan — on Postgres 12+ an ALTER COLUMN that
-- shrinks or keeps the length as `TYPE varchar(N)` takes a brief access-
-- exclusive lock but completes in milliseconds on the current tiny
-- table; a widening ALTER is a metadata-only operation.
ALTER TABLE listings
    ALTER COLUMN description TYPE VARCHAR(500);
