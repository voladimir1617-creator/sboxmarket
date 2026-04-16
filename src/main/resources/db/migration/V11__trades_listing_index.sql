-- Index for TradeRepository.findByListingId, used by SellService.cancelListing
-- and TradeService.findForListing. Without this, every listing cancel/lookup
-- does a sequential scan on the trades table.
CREATE INDEX IF NOT EXISTS idx_trades_listing ON trades(listing_id);
