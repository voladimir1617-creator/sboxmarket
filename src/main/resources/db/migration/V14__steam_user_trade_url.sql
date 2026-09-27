-- Steam trade offer URL that the user has chosen to expose to trade
-- counterparties. Shown on the other participant's trade row during an
-- active escrow so sellers can actually send the Steam offer (and buyers
-- can verify it came from the right account). Optional — users without a
-- trade URL can't open escrowed P2P trades but can still use auctions /
-- non-custodial flows that don't need a direct Steam offer.
ALTER TABLE steam_users
    ADD COLUMN IF NOT EXISTS trade_url VARCHAR(300);
