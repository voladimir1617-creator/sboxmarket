-- Per-viewer block list. A user who blocks another user shouldn't:
--   (a) see that user's listings in the marketplace grid,
--   (b) receive new offers from that user on their own listings,
--   (c) receive new trade chat messages from that user,
--   (d) receive NEW_LISTING_FROM_SELLER pings if they had followed them.
--
-- Non-destructive: blocking is purely a viewer-side filter. The blocked
-- user isn't told they've been blocked (no notification, no audit event)
-- and the block is reversible at any time from the blocker's Profile.
-- Staff see the unfiltered view — blocks are a personal preference, not
-- a moderation primitive.
--
-- Surrogate id + unique(pair) — same user can only block another user
-- once; re-blocking is a no-op rather than an error from the API
-- client's POV.

CREATE TABLE user_blocks (
    id               BIGSERIAL PRIMARY KEY,
    blocker_user_id  BIGINT    NOT NULL,
    blocked_user_id  BIGINT    NOT NULL,
    created_at       BIGINT    NOT NULL,
    CONSTRAINT user_blocks_unique_pair UNIQUE (blocker_user_id, blocked_user_id),
    CONSTRAINT user_blocks_no_self CHECK (blocker_user_id <> blocked_user_id)
);

-- Reverse lookup: "who has blocked user X" — drives the write-path
-- enforcement (OfferService.makeOffer needs to know if the seller has
-- blocked the buyer). Covering-ish index so the predicate alone resolves it.
CREATE INDEX idx_user_blocks_blocked
    ON user_blocks(blocked_user_id);

-- Forward lookup: "every user X has blocked" — drives the listing-grid
-- filter ("don't show me listings from anyone I've blocked"). Separate
-- from the unique pair so a query filtering just by blocker can walk
-- the index in sort order.
CREATE INDEX idx_user_blocks_blocker
    ON user_blocks(blocker_user_id);
