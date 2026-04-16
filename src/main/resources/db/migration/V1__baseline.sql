--
-- Baseline schema for Postgres deployments. JPA entities in dev (H2) are
-- kept in sync via `ddl-auto: update`; production runs this file through
-- Flyway at startup with `baseline-on-migrate: true` so the first deploy to
-- an empty database is idempotent.
--
-- Any new entity or column must land as a new V{n}__name.sql migration next
-- to this file. NEVER edit this file after first deploy — it's the baseline
-- Flyway uses to reason about incremental changes.
--

-- ── Items + price history ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS items (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    category        VARCHAR(255) NOT NULL,
    rarity          VARCHAR(255) NOT NULL,
    image_url       VARCHAR(500),
    icon_emoji      VARCHAR(255) NOT NULL DEFAULT '👕',
    accent_color    VARCHAR(255) NOT NULL DEFAULT '#1a1a2a',
    supply          INTEGER      NOT NULL DEFAULT 0,
    total_sold      INTEGER      NOT NULL DEFAULT 0,
    lowest_price    NUMERIC(19,2) NOT NULL DEFAULT 0,
    steam_price     NUMERIC(19,2),
    trend_percent   INTEGER      NOT NULL DEFAULT 0,
    is_listed       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_items_category ON items(category);
CREATE INDEX IF NOT EXISTS idx_items_rarity   ON items(rarity);
CREATE INDEX IF NOT EXISTS idx_items_name     ON items(LOWER(name));

CREATE TABLE IF NOT EXISTS price_history (
    id           BIGSERIAL PRIMARY KEY,
    item_id      BIGINT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    price        NUMERIC(19,2) NOT NULL,
    volume       INTEGER NOT NULL DEFAULT 0,
    day_label    VARCHAR(255),
    recorded_at  BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_price_history_item ON price_history(item_id, recorded_at);

-- ── Steam users + wallets ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS steam_users (
    id                     BIGSERIAL PRIMARY KEY,
    steam_id64             VARCHAR(32) NOT NULL UNIQUE,
    display_name           VARCHAR(255),
    avatar_url             VARCHAR(500),
    profile_url            VARCHAR(500),
    created_at             BIGINT NOT NULL,
    last_login_at          BIGINT NOT NULL,
    last_synced_at         BIGINT,
    steam_inventory_size   INTEGER,
    role                   VARCHAR(16) DEFAULT 'USER',
    banned                 BOOLEAN DEFAULT FALSE,
    ban_reason             VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS wallets (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(255) NOT NULL UNIQUE,
    balance     NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency    VARCHAR(255) NOT NULL DEFAULT 'USD',
    created_at  BIGINT NOT NULL
);

-- ── Listings + offers + bids + buy orders ─────────────────────────────────
CREATE TABLE IF NOT EXISTS listings (
    id                   BIGSERIAL PRIMARY KEY,
    item_id              BIGINT NOT NULL REFERENCES items(id),
    price                NUMERIC(10,2) NOT NULL,
    seller_name          VARCHAR(255) NOT NULL,
    seller_avatar        VARCHAR(255),
    status               VARCHAR(255) NOT NULL DEFAULT 'ACTIVE',
    condition            VARCHAR(255) NOT NULL DEFAULT '',
    rarity_score         NUMERIC(19,2) NOT NULL DEFAULT 0,
    trade_link           VARCHAR(255),
    listed_at            BIGINT NOT NULL,
    sold_at              BIGINT,
    seller_user_id       BIGINT,
    buyer_user_id        BIGINT,
    listing_type         VARCHAR(16) DEFAULT 'BUY_NOW',
    expires_at           BIGINT,
    current_bid          NUMERIC(19,2),
    current_bidder_id    BIGINT,
    current_bidder_name  VARCHAR(255),
    bid_count            INTEGER DEFAULT 0,
    max_discount         NUMERIC(5,2),
    hidden               BOOLEAN DEFAULT FALSE,
    description          VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_listings_status_price   ON listings(status, price);
CREATE INDEX IF NOT EXISTS idx_listings_status_listed  ON listings(status, listed_at);
CREATE INDEX IF NOT EXISTS idx_listings_seller         ON listings(seller_user_id, status);
CREATE INDEX IF NOT EXISTS idx_listings_buyer          ON listings(buyer_user_id, status);
CREATE INDEX IF NOT EXISTS idx_listings_item_status    ON listings(item_id, status);

CREATE TABLE IF NOT EXISTS offers (
    id              BIGSERIAL PRIMARY KEY,
    listing_id      BIGINT NOT NULL,
    buyer_user_id   BIGINT NOT NULL,
    seller_user_id  BIGINT,
    amount          NUMERIC(19,2) NOT NULL,
    asking_price    NUMERIC(19,2) NOT NULL,
    status          VARCHAR(255) NOT NULL DEFAULT 'PENDING',
    buyer_name      VARCHAR(255),
    item_name       VARCHAR(255),
    item_image_url  VARCHAR(500),
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_offers_listing ON offers(listing_id, status);
CREATE INDEX IF NOT EXISTS idx_offers_buyer   ON offers(buyer_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_offers_seller  ON offers(seller_user_id, created_at);

CREATE TABLE IF NOT EXISTS bids (
    id              BIGSERIAL PRIMARY KEY,
    listing_id      BIGINT NOT NULL,
    bidder_user_id  BIGINT NOT NULL,
    bidder_name     VARCHAR(255),
    amount          NUMERIC(19,2) NOT NULL,
    max_amount      NUMERIC(19,2),
    kind            VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    status          VARCHAR(16) NOT NULL DEFAULT 'WINNING',
    created_at      BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bids_listing ON bids(listing_id, amount DESC);
CREATE INDEX IF NOT EXISTS idx_bids_user    ON bids(bidder_user_id, created_at);

CREATE TABLE IF NOT EXISTS buy_orders (
    id                 BIGSERIAL PRIMARY KEY,
    buyer_user_id      BIGINT NOT NULL,
    buyer_name         VARCHAR(255),
    item_id            BIGINT,
    item_name          VARCHAR(255),
    category           VARCHAR(255),
    rarity             VARCHAR(255),
    max_price          NUMERIC(19,2) NOT NULL,
    quantity           INTEGER NOT NULL DEFAULT 1,
    original_quantity  INTEGER NOT NULL DEFAULT 1,
    status             VARCHAR(255) NOT NULL DEFAULT 'ACTIVE',
    created_at         BIGINT NOT NULL,
    updated_at         BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_buy_orders_buyer  ON buy_orders(buyer_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_buy_orders_status ON buy_orders(status, max_price);

-- ── Transactions ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS transactions (
    id                BIGSERIAL PRIMARY KEY,
    wallet_id         BIGINT NOT NULL REFERENCES wallets(id),
    type              VARCHAR(32) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    amount            NUMERIC(19,2) NOT NULL,
    currency          VARCHAR(16) NOT NULL DEFAULT 'USD',
    stripe_reference  VARCHAR(255),
    description       VARCHAR(500),
    listing_id        BIGINT,
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tx_wallet        ON transactions(wallet_id, created_at);
CREATE INDEX IF NOT EXISTS idx_tx_type_status   ON transactions(type, status);
CREATE INDEX IF NOT EXISTS idx_tx_stripe        ON transactions(stripe_reference);

-- ── Loadouts ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS loadouts (
    id             BIGSERIAL PRIMARY KEY,
    owner_user_id  BIGINT NOT NULL,
    owner_name     VARCHAR(255),
    name           VARCHAR(100) NOT NULL,
    description    VARCHAR(500),
    visibility     VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    total_value    NUMERIC(19,2) NOT NULL DEFAULT 0,
    favorites      INTEGER NOT NULL DEFAULT 0,
    created_at     BIGINT NOT NULL,
    updated_at     BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_loadouts_owner      ON loadouts(owner_user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_loadouts_public     ON loadouts(visibility, favorites DESC);

CREATE TABLE IF NOT EXISTS loadout_slots (
    id              BIGSERIAL PRIMARY KEY,
    loadout_id      BIGINT NOT NULL REFERENCES loadouts(id) ON DELETE CASCADE,
    slot            VARCHAR(255) NOT NULL,
    item_id         BIGINT,
    item_name       VARCHAR(255),
    item_emoji      VARCHAR(255),
    snapshot_price  NUMERIC(19,2) NOT NULL DEFAULT 0,
    locked          BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_loadout_slots_parent ON loadout_slots(loadout_id);

-- ── Notifications ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    kind        VARCHAR(255) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        VARCHAR(500),
    ref_id      BIGINT,
    read        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, created_at);

-- ── Support tickets + messages ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS support_tickets (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    username    VARCHAR(255),
    subject     VARCHAR(200) NOT NULL,
    category    VARCHAR(32) NOT NULL DEFAULT 'OTHER',
    status      VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tickets_user ON support_tickets(user_id, updated_at);

CREATE TABLE IF NOT EXISTS support_messages (
    id           BIGSERIAL PRIMARY KEY,
    ticket_id    BIGINT NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    author       VARCHAR(16) NOT NULL DEFAULT 'USER',
    author_name  VARCHAR(255),
    body         VARCHAR(2000) NOT NULL,
    created_at   BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_support_msgs_ticket ON support_messages(ticket_id, created_at);

-- ── API keys ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS api_keys (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL,
    public_prefix  VARCHAR(32) NOT NULL UNIQUE,
    token_hash     VARCHAR(64) NOT NULL,
    label          VARCHAR(80),
    revoked        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     BIGINT NOT NULL,
    last_used_at   BIGINT
);
CREATE INDEX IF NOT EXISTS idx_api_keys_user ON api_keys(user_id);
CREATE INDEX IF NOT EXISTS idx_api_keys_hash ON api_keys(token_hash);

-- ── Audit log (append-only) ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    actor_user_id   BIGINT,
    actor_name      VARCHAR(80),
    subject_user_id BIGINT,
    subject_name    VARCHAR(80),
    event_type      VARCHAR(48) NOT NULL,
    resource_id     BIGINT,
    summary         VARCHAR(500),
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(120),
    created_at      BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_actor   ON audit_log(actor_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_subject ON audit_log(subject_user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_event   ON audit_log(event_type, created_at);
