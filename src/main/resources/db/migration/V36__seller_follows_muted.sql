-- Per-follow mute. The existing seller_follows row makes the user a
-- follower (gets NEW_LISTING_FROM_SELLER bell + email pings on every
-- relist). Heavy sellers can fan out 10+ listings a day, drowning the
-- follower's bell. CSFloat lets you mute a single seller's listing
-- pings without unfollowing — keeps the follow relationship for the
-- public follower count + the discovery feed inclusion, just stops
-- the notification spam.
ALTER TABLE seller_follows
    ADD COLUMN notifications_muted BOOLEAN NOT NULL DEFAULT false;
