-- Flag set by BidService.sweepEndingSoon when an auction fires its
-- "ends in < 10 min" push to bidders + watchers. Prevents the sweeper
-- from re-notifying on every 2-minute tick for the same listing.
-- Non-auction rows are left at the default FALSE and never read it.
ALTER TABLE listings
  ADD COLUMN IF NOT EXISTS ending_soon_notified BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index so the sweeper query (status='ACTIVE' + listing_type='AUCTION'
-- + NOT ending_soon_notified + expires_at in window) touches only the tiny
-- tail of unnotified active auctions, not the whole listings table.
CREATE INDEX IF NOT EXISTS idx_listings_ending_soon_unnotified
  ON listings (expires_at)
  WHERE status = 'ACTIVE'
    AND listing_type = 'AUCTION'
    AND ending_soon_notified = FALSE;
