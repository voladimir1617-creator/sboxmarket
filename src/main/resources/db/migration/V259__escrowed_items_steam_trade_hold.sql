-- Steam trade-HOLD awareness for bot-escrow deposits — so a 7-to-15-day
-- mobile-authenticator hold can never be mistaken for a seller who ghosted us,
-- and so a real item can never be orphaned at the bot with no row pointing at it.
--
-- THE BUG THIS CLOSES
-- SteamEscrowService.depositTimeoutHours defaults to 24. sweepStalePendingDeposits
-- flips any PENDING_DEPOSIT row older than that to custody FAILED and reverts its
-- listing PENDING_ESCROW -> CANCELLED.
--
-- Steam's mobile-authenticator trade hold runs 7 to 15 DAYS. A seller without
-- the authenticator app installed for 7+ days — which is EVERY brand-new bot
-- account and every seller who just re-installed — has every accepted trade held
-- by Steam for that entire window. The sidecar has always reported the release
-- time (`escrowEndsUnix` on GET /offers/:id, from node-steam's offer.escrowEnds)
-- and NO Groovy code read it. Not one caller. The column did not exist to read it
-- into.
--
-- So the first real listing goes like this: the seller lists, accepts the bot's
-- deposit offer, and the item LEAVES HIS INVENTORY into Steam's hold. At hour 24
-- — with 6 to 14 days still to run — the sweeper marks custody FAILED and cancels
-- the listing. That undoes nothing, because the item is already gone; it only
-- deletes the record of where the item is. Days later the asset lands in the bot's
-- inventory, and findPendingDeposits (custody_state = 'PENDING_DEPOSIT') matches
-- no row, so nothing promotes it, nothing returns it, and nothing tells the seller.
-- A real knife, in the platform's inventory, referenced by nothing.
--
-- WHY TWO COLUMNS, NOT ONE
-- They answer two different questions and only one of them is about time.
--
--   escrow_ends_at      -- WHEN the hold releases. Drives the deadline: a held
--                          deposit is not late until its hold has actually
--                          expired plus a grace window, where an unheld one is
--                          still late at 24h. Without this the timeout is a
--                          constant, and a constant is wrong for a window Steam
--                          varies between 7 and 15 days.
--
--   deposit_accepted_at -- WHETHER the item has left the seller. This is the
--                          anti-orphan anchor and it is deliberately NOT derived
--                          from escrow_ends_at, because the dangerous case has no
--                          hold at all: an offer Steam reports accepted while the
--                          asset has not yet propagated into the bot's inventory
--                          also has the item in flight, with escrow_ends_at NULL.
--                          Timing that row out orphans it just as thoroughly.
--                          Once this is set there is no give-up path — FAILED is
--                          precisely how the pointer got lost.
--
-- UNITS — THE TRAP IN THIS MIGRATION
-- The sidecar reports escrowEndsUnix in SECONDS. Every other timestamp on this
-- table is epoch MILLIS (created_at, updated_at, return_requested_at are all
-- System.currentTimeMillis()). Storing the raw seconds value here would read as
-- January 1970 — a hold that expired 56 years ago — making every held deposit
-- instantly overdue and re-creating the exact orphaning bug above, while looking
-- like a populated, healthy column. SteamEscrowService.readEscrowEndsMillis owns
-- the *1000 conversion and is pinned by a test that fails without it.
--
-- NULLABLE, NOT NOT-NULL
-- Both columns are nullable with no default and no backfill. Three reasons, in
-- descending order of how expensive getting it wrong is:
--   1. This table is populated in prod, and dev/test run ddl-auto: update, which
--      CANNOT add a NOT NULL column to a table that already has rows — it fails
--      the schema update outright and takes the application boot with it.
--   2. NULL is the honest value. A row written before this migration was never
--      observed for a hold; asserting "no hold" (0, or an epoch) about a row
--      nobody measured is a different lie in the same family as the one above.
--   3. Every consumer reads NULL as "no hold known" and falls back to the 24h
--      unheld deadline, which is exactly the pre-existing behaviour — so historic
--      rows keep meaning what they meant and the migration changes no answer for
--      any row already in the table.
--
-- The new custody_state value 'IN_ESCROW_HOLD' needs no DDL: custody_state is
-- already VARCHAR(24) with no CHECK constraint or enum ('IN_ESCROW_HOLD' is 14
-- characters), and no existing row can hold the new value.
--
-- Postgres prod dialect; dev/test use H2 with ddl-auto, so the entity mapping
-- creates these columns there without Flyway.

ALTER TABLE escrowed_items
    ADD COLUMN IF NOT EXISTS escrow_ends_at BIGINT;

ALTER TABLE escrowed_items
    ADD COLUMN IF NOT EXISTS deposit_accepted_at BIGINT;

-- Hold-release sweep hot path. Partial index because the candidate set is
-- vanishingly small: on a healthy marketplace almost every row is either a live
-- listing (IN_CUSTODY) or terminal, and only deposits actually sitting inside a
-- Steam hold are ever scanned. Ordered on escrow_ends_at so the longest-overdue
-- hold is served first under the batch cap.
CREATE INDEX IF NOT EXISTS idx_escrowed_items_escrow_hold
    ON escrowed_items(escrow_ends_at)
    WHERE custody_state = 'IN_ESCROW_HOLD';

-- Stale-deposit TIMEOUT sweep hot path, re-scoped by this wave. The sweep may
-- now only ever see deposits the seller never accepted — deposit_accepted_at IS
-- NULL — because those are the only rows where giving up costs nobody anything
-- (the seller still holds the item). This index matches that query exactly so
-- the narrowed sweep stays a cheap indexed scan rather than a filter over every
-- PENDING_DEPOSIT row.
CREATE INDEX IF NOT EXISTS idx_escrowed_items_unaccepted_deposit
    ON escrowed_items(created_at)
    WHERE deposit_accepted_at IS NULL AND custody_state = 'PENDING_DEPOSIT';
