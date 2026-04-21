-- Per-bucket email-mute preferences. The existing
-- `email_notifications_enabled` boolean is a global kill switch — on or
-- off for every non-essential email. Heavy sellers and auction-watchers
-- want finer control: "stop the watchlist price-drop emails but keep
-- the auction-outbid and sale-completed notifications". This column
-- stores a comma-separated set of bucket names the user has silenced.
--
-- Buckets that can be muted here (transactional emails like withdrawal
-- approval / verification are NOT mutable — those are legal-adjacent):
--   TRADES      — SaleCompleted
--   AUCTIONS    — AuctionOutbid, AuctionWon
--   WATCHLIST   — PriceDrop
--   FOLLOWS     — NewListingFromSeller
--
-- Empty string / null = no buckets muted (the default, matches prior
-- behaviour for every pre-launch row).
ALTER TABLE steam_users
    ADD COLUMN muted_email_kinds VARCHAR(255) NOT NULL DEFAULT '';
