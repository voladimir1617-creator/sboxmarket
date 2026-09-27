-- listings.soft_close_extensions — count of anti-snipe "soft close" end-time
-- extensions already applied to an auction listing. BidService extends an
-- auction's end time when a bid lands in the final window; this counter caps
-- how many times that can happen (the soft-close cap, batch 111).
--
-- The Listing entity has carried `Integer softCloseExtensions = 0`
-- (@Column(name = "soft_close_extensions")) since that feature shipped, but no
-- migration ever created the column. Dev/test run on H2 with
-- ddl-auto:update, which silently auto-adds the column — so the drift was
-- invisible there and to the H2-backed test suite. Prod runs Flyway +
-- Hibernate ddl-auto:validate, which refuses to start with
-- "Schema-validation: missing column [soft_close_extensions]". This migration
-- closes that dev-vs-prod schema drift so the prod profile boots clean.
--
-- `IF NOT EXISTS` keeps it idempotent (a box that already auto-synced the
-- column via H2/ddl-auto won't error). DEFAULT 0 backfills existing rows on
-- Postgres 11+; the explicit UPDATE covers any NULLs from a partial prior add.
ALTER TABLE listings ADD COLUMN IF NOT EXISTS soft_close_extensions INTEGER DEFAULT 0;
UPDATE listings SET soft_close_extensions = 0 WHERE soft_close_extensions IS NULL;
