-- V58: purge corrupted Spring Session rows.
--
-- During a brief window in late April 2026 some session rows ended up with
-- embedded NUL bytes (0x00) in the TEXT columns. The Postgres JDBC driver
-- rejects those bytes on read with "invalid byte sequence for encoding
-- UTF8: 0x00", so every authenticated request that touched the corrupt row
-- bubbled back as a 500.
--
-- TRUNCATE both tables: this signs every user out (acceptable cost given
-- the site was 500-ing for those whose session_id matched a bad row) and
-- guarantees we don't leave a single corrupt row behind. ON DELETE CASCADE
-- on spring_session_attributes already mirrors session deletes, but
-- TRUNCATE … CASCADE makes the intent explicit and beats a row-by-row
-- DELETE on a table the JDBC driver can't even SELECT from.

TRUNCATE TABLE spring_session_attributes, spring_session CASCADE;
