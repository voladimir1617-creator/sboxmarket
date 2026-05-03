-- V60: extend NUL-byte CHECK constraint to EVERY TEXT column on the
-- spring_session + spring_session_attributes tables.
--
-- Background:
--   2026-05-02 evening — V59 added CHECK constraint on
--     spring_session.primary_id + spring_session.session_id only.
--   2026-05-03 morning — site went down again with the SAME PSQLException
--     22021 ("invalid byte sequence for encoding UTF8: 0x00"), this time
--     because the NUL was in either:
--       • spring_session.principal_name        — varchar(100), NOT covered
--       • spring_session_attributes.attribute_name — varchar(200), NOT covered
--     Postgres rejected the SELECT row when transcoding to UTF-8, every
--     visitor 500'd, /error 500'd too (same SessionRepositoryFilter), Tomcat
--     fell through to its raw stub 500 page.
--
-- This migration:
--   1. Drops V59's narrower constraint and re-adds with principal_name covered.
--   2. Adds a sibling constraint on spring_session_attributes covering
--      attribute_name + session_primary_id.
--
-- bytea→bytea cast on each side avoids the same UTF8-decode failure
-- (E'\x00' parsed as a TEXT literal triggers exactly the SQLSTATE 22021
-- we're trying to PREVENT — the migration itself would fail to add the
-- constraint).
--
-- attribute_bytes (BYTEA) is intentionally NOT constrained — it CAN
-- legitimately hold NUL bytes (it's binary).

ALTER TABLE spring_session
  DROP CONSTRAINT IF EXISTS spring_session_no_nul_bytes;

ALTER TABLE spring_session
  ADD CONSTRAINT spring_session_no_nul_bytes
  CHECK (
    position('\x00'::bytea in primary_id::bytea) = 0
    AND position('\x00'::bytea in session_id::bytea) = 0
    AND (principal_name IS NULL OR position('\x00'::bytea in principal_name::bytea) = 0)
  );

ALTER TABLE spring_session_attributes
  DROP CONSTRAINT IF EXISTS spring_session_attrs_no_nul_bytes;

ALTER TABLE spring_session_attributes
  ADD CONSTRAINT spring_session_attrs_no_nul_bytes
  CHECK (
    position('\x00'::bytea in attribute_name::bytea) = 0
    AND position('\x00'::bytea in session_primary_id::bytea) = 0
  );
