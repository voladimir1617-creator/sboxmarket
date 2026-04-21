-- Extend saved_searches to cover every filter the marketplace toolbar
-- exposes. Before this, saving a preset like "Hats · ≥20% off · AUCTIONs"
-- stored only the category and silently dropped the discount + type +
-- deals/new toggles — re-applying the preset showed a much broader view
-- than the user intended, and the server-side LISTING_MATCH notifier
-- fanout (SavedSearchService.matches) only checked category/rarity/price
-- so irrelevant match alerts fired.
--
-- All columns are NOT NULL with sensible defaults so existing rows
-- upgrade cleanly: a pre-batch-957 preset is equivalent to "no extra
-- filters on top of category/rarity/q/price".
ALTER TABLE saved_searches
    ADD COLUMN IF NOT EXISTS min_discount_pct INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS deals_only       BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS new_only         BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS affordable_only  BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS listing_type     VARCHAR(16)  NOT NULL DEFAULT 'ALL';
