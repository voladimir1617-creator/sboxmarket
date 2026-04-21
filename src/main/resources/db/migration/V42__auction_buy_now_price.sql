-- Auction Buy-Now price (batch 371). Seller optionally sets a
-- `buy_now_price` when creating an AUCTION listing. A bidder can hit
-- the "Buy now for $X" button to skip the auction entirely and settle
-- instantly at that price (buyer's wallet charged, auction closed,
-- existing bidders refunded if any pre-authorization was held).
--
-- Mirrors CSFloat's auction + BIN hybrid: impatient buyers get a
-- fast-close path, sellers get a price ceiling they can accept
-- without having to hard-cancel and relist.
--
-- Nullable — plain auctions (no Buy-Now) continue to work unchanged.
-- The column sits alongside `price` (starting bid). Constraint at the
-- service layer enforces `buy_now_price > price` (Buy-Now must be
-- above the starting bid to make sense).

ALTER TABLE listings
    ADD COLUMN buy_now_price DECIMAL(10, 2);
