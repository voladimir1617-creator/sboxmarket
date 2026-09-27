-- Follow a seller to get notified when they list a new item.
-- One row per (follower → seller) pair; unique-index-enforced so
-- clicking Follow twice upserts instead of stacking.
CREATE TABLE IF NOT EXISTS seller_follows (
    id                  BIGSERIAL PRIMARY KEY,
    follower_user_id    BIGINT NOT NULL REFERENCES steam_users(id) ON DELETE CASCADE,
    seller_user_id      BIGINT NOT NULL REFERENCES steam_users(id) ON DELETE CASCADE,
    created_at          BIGINT NOT NULL,
    UNIQUE (follower_user_id, seller_user_id)
);

-- SellService's per-new-listing fanout reads by seller (to find all
-- followers for one seller at list-time). Stall page reads by seller
-- (to compute the "N followers" chip). Profile's "sellers I follow"
-- tab reads by follower.
CREATE INDEX IF NOT EXISTS idx_seller_follows_seller  ON seller_follows(seller_user_id);
CREATE INDEX IF NOT EXISTS idx_seller_follows_follower ON seller_follows(follower_user_id, created_at DESC);
