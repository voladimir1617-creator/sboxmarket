-- Server-side cart (cross-device sync). Until now the cart was
-- localStorage-only — a buyer who added items on desktop saw an empty
-- cart on mobile, and a browser-cache wipe lost the entire pile of
-- items they'd just curated. CSFloat persists carts per-account; we
-- match that.
--
-- Carts hold listing ids (specific listings, not catalogue items) —
-- a single listing is 1-of-1 from a Steam inventory so each row is
-- unique by (user_id, listing_id) at the DB level. The
-- CartController.checkout endpoint already iterates listings and
-- handles the "sold while you were thinking" failure mode per row.
--
-- ON DELETE CASCADE from steam_users so a banned user's cart doesn't
-- leak rows. Listing deletion is a soft state-change (status='SOLD'
-- etc.) rather than a row delete, so listings here are NOT cascaded;
-- the existing /cart freshness check on page-open scrubs stale rows.
CREATE TABLE cart_items (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT    NOT NULL REFERENCES steam_users(id) ON DELETE CASCADE,
    listing_id  BIGINT    NOT NULL REFERENCES listings(id)    ON DELETE CASCADE,
    added_at    BIGINT    NOT NULL DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    CONSTRAINT uq_cart_items_user_listing UNIQUE (user_id, listing_id)
);

CREATE INDEX idx_cart_items_user    ON cart_items(user_id);
CREATE INDEX idx_cart_items_listing ON cart_items(listing_id);
