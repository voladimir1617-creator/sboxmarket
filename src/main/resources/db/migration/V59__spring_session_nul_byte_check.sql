-- V59: defence-in-depth CHECK constraint against NUL bytes (0x00) in the
-- TEXT columns of spring_session.
--
-- Background:
--   2026-04-30 — first NUL-poisoned-cookie incident. Mitigated by adding
--                SessionCookieSanitizerFilter at HIGHEST_PRECEDENCE+5 to
--                scrub inbound NUL bytes before Spring Session sees them.
--   2026-05-02 — site went hard-down (HTTP 500 on every request) when a
--                NUL byte ended up in a spring_session row at rest in
--                Postgres. The cookie sanitizer covers the inbound surface
--                but does NOTHING for rows already poisoned in the DB.
--                V58 already TRUNCATEs both tables; this migration adds
--                a CHECK so Postgres itself REJECTS any future INSERT or
--                UPDATE that tries to land a NUL in the TEXT columns —
--                forcing the failure to surface at write-time as a clean
--                ConstraintViolation rather than silently corrupting the
--                row and detonating on the next read.
--
-- The two TEXT-typed columns Spring Session writes are:
--   • PRIMARY_ID   — character(36)        — UUID-shaped, generated server-side
--   • SESSION_ID   — character varying(180) — the cookie token
--
-- ATTRIBUTE_BYTES is BYTEA and CAN legitimately hold NUL — DON'T constrain it.
--
-- position('\x00'::bytea in col::bytea) = 0 is true when the column has
-- zero NUL bytes. Comparing as bytea literals avoids the same UTF8-decode
-- failure (E'\x00' parsed as a TEXT literal triggers exactly the SQLSTATE
-- 22021 we're trying to PREVENT). The cast on each side is bytea→bytea
-- so position() never round-trips through TEXT.

ALTER TABLE spring_session
  ADD CONSTRAINT spring_session_no_nul_bytes
  CHECK (
    position('\x00'::bytea in primary_id::bytea) = 0
    AND position('\x00'::bytea in session_id::bytea) = 0
  );
