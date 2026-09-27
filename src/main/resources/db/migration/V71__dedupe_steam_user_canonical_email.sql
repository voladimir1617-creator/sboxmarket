-- V71: de-duplicate steam_users.canonical_email and (re)assert the partial
--      UNIQUE index that V63 added without first de-duping.
--
-- PROBLEM (production-safety):
--   V63__steam_user_canonical_email.sql backfills `canonical_email` for every
--   existing row and then immediately runs:
--
--       CREATE UNIQUE INDEX IF NOT EXISTS uq_steam_users_canonical_email
--           ON steam_users (canonical_email) WHERE canonical_email IS NOT NULL;
--
--   with NO de-dup step in between. The whole REASON the canonical column
--   exists is that several distinct raw emails collapse to ONE canonical form:
--
--       v.ictim@gmail.com   →  victim@gmail.com
--       victim+spam@gmail.com → victim@gmail.com
--       victim@googlemail.com → victim@gmail.com
--
--   So on a populated prod `steam_users` table it is not merely possible but
--   EXPECTED that two pre-existing accounts share a canonical form. When they
--   do, V63's CREATE UNIQUE INDEX aborts with
--       ERROR: could not create unique index "uq_steam_users_canonical_email"
--       DETAIL: Key (canonical_email)=(victim@gmail.com) is duplicated.
--   Flyway marks the migration failed, the prod boot aborts (ddl-auto:
--   validate never even runs), and the site is hard-down on deploy. This is
--   the classic "UNIQUE constraint added without first de-duping" foot-gun.
--
-- WHY A FORWARD MIGRATION (not an edit to V63):
--   V63 is an already-numbered migration that may have a recorded checksum in
--   some environment's flyway_schema_history; editing its body would trip
--   `validate-migration-naming`/checksum validation on the next boot. The
--   project's own convention (see V67 dedupe-then-UNIQUE on reviews, and V68
--   scrub-then-FK on user_blocks) is to repair in a new forward file. This
--   migration is fully idempotent and safe to run whether or not V63 has
--   already succeeded:
--     * If V63 already created the index cleanly (no collisions in that env),
--       the DELETE/UPDATE below match nothing and the CREATE … IF NOT EXISTS
--       is a no-op.
--     * If the index is absent (e.g. it was dropped during incident recovery,
--       or a prior V63 attempt is being retried after this dedupe lands first
--       on a manually-repaired DB), this migration makes the data safe and
--       (re)creates the index.
--
-- STRATEGY:
--   Keep the OLDEST account per canonical_email (smallest created_at, ties
--   broken by smallest id) as the canonical owner; NULL out the canonical_email
--   on every newer colliding row. NULL is the correct value for the losers: the
--   partial index ignores NULLs, the app's uniqueness probe short-circuits on
--   null canonical input, and the raw `email` column is left untouched so those
--   users keep their address in the UI. (We null rather than DELETE because the
--   rows are real user accounts with wallets/listings/trades — only the
--   duplicate-detection key is surrendered, not the account.)

-- 1) Demote every non-winning row in a canonical_email collision group to NULL.
UPDATE steam_users s
   SET canonical_email = NULL
 WHERE s.canonical_email IS NOT NULL
   AND s.id <> (
        SELECT w.id
          FROM steam_users w
         WHERE w.canonical_email = s.canonical_email
         ORDER BY w.created_at ASC, w.id ASC
         LIMIT 1
   );

-- 2) (Re)assert the partial UNIQUE index. IF NOT EXISTS makes this a no-op when
--    V63 already created it; after step 1 the non-null subset is guaranteed
--    collision-free so the create cannot fail.
CREATE UNIQUE INDEX IF NOT EXISTS uq_steam_users_canonical_email
    ON steam_users (canonical_email)
 WHERE canonical_email IS NOT NULL;
