-- Defense-in-depth CHECK on buy_orders.quantity (>= 0). [integrity audit]
--
-- quantity is INTEGER NOT NULL DEFAULT 1 with no CHECK. BuyOrderService writes
-- `quantity = Math.max(0, quantity - 1)` on each fill (0 is the valid
-- fully-FILLED terminal value) and create() validates a positive initial
-- quantity — so a NEGATIVE quantity is never written by the app. This guards
-- against a raw-SQL / ETL path persisting a negative, which the sweeper +
-- matcher queries don't uniformly filter out.
--
-- NOTE: the constraint is `>= 0`, NOT `> 0` — a fully-filled order legitimately
-- rests at quantity 0 (the audit's suggested `> 0` would have rejected every
-- filled order).
--
-- Postgres prod dialect; runs prod-only (dev H2 ddl-auto, Flyway disabled).
-- Existing rows are provably >= 0, so the ALTER cannot fail on legacy data.
ALTER TABLE buy_orders
    ADD CONSTRAINT chk_buy_orders_quantity_nonneg
    CHECK (quantity >= 0);
