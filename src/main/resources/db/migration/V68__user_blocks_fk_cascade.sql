-- V68: add the missing FK + ON DELETE CASCADE on user_blocks.
--
-- V40__user_blocks.sql shipped the table with both id columns typed as
-- "BIGINT NOT NULL" and a comment that promised they referenced
-- steam_users(id), but neither column was declared with REFERENCES.
-- That's a one-off — every sibling preference table from the same era
-- (watchlist_items V30, cart_items V31, saved_searches V32, seller_follows
-- V19, review_helpful_votes V29) carries the standard
-- "REFERENCES steam_users(id) ON DELETE CASCADE" pair.
--
-- The hole has two consequences:
--   1. Orphan rows. If AdminService.finalizeDeletion (or any future
--      hard-delete codepath) hard-deletes a steam_users row without
--      first calling BOTH UserBlockRepository.deleteByBlocker AND
--      deleteByBlocked, the surviving block rows point at a nonexistent
--      user id. The listing-grid filter then walks those phantom ids
--      forever — harmless on SELECT, but a slow rot of dead rows.
--   2. No DB-level integrity. A bug that writes a typo'd user id (e.g.
--      a service mistakenly using internal id where steam id was meant)
--      INSERTs silently instead of failing fast with a 23503.
--
-- Fix: scrub historical orphans (left-join NULL check), then add the
-- FK + CASCADE pair so steam_users.deleteById cascades through the
-- block list both ways and a banned-account purge needs no companion
-- service code to keep this table consistent.

-- Defensive dedupe of orphans — any block row whose blocker_user_id or
-- blocked_user_id no longer exists in steam_users. Plain DELETE +
-- IN(...) so the predicate is index-friendly on both sides.
DELETE FROM user_blocks
 WHERE blocker_user_id NOT IN (SELECT id FROM steam_users)
    OR blocked_user_id NOT IN (SELECT id FROM steam_users);

ALTER TABLE user_blocks
    ADD CONSTRAINT fk_user_blocks_blocker
        FOREIGN KEY (blocker_user_id)
        REFERENCES steam_users(id)
        ON DELETE CASCADE;

ALTER TABLE user_blocks
    ADD CONSTRAINT fk_user_blocks_blocked
        FOREIGN KEY (blocked_user_id)
        REFERENCES steam_users(id)
        ON DELETE CASCADE;
