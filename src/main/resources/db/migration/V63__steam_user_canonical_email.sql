-- V63: add `canonical_email` to steam_users so the email-uniqueness check
-- (batch 477 — chargeback evasion / spam ticket flood / password-reset
-- fishing mitigation) can't be defeated by Gmail's well-documented mailbox
-- aliases:
--
--   victim+a@gmail.com, victim+b@gmail.com         (RFC 5233 plus tags)
--   v.ictim@gmail.com,  vict.im@gmail.com          (Gmail dot-insensitivity)
--   victim@googlemail.com                          (Google's UK/DE alias)
--
-- All five route to the same Google inbox; without canonicalisation a
-- single Gmail account mints unlimited SkinBox identities. We now compute
-- a canonical form in `EmailNormalizer.canonicalize` and store it
-- alongside the raw email; the uniqueness check compares the canonical
-- column. The raw `email` column is preserved as-typed so the user
-- still sees their original casing in the UI.
--
-- Backfill in plain SQL so the column is populated for every existing row
-- the moment the deploy lands — otherwise the brand-new uniqueness
-- predicate would see NULL for every legacy row and let a fresh signup
-- collide with anyone who pre-dates the column. The expression below
-- mirrors EmailNormalizer.canonicalize:
--
--   * lowercase + trim the address
--   * remap googlemail.com → gmail.com
--   * for the Google domain: drop everything from the first `+`, then
--     strip every `.` from the local part
--   * everything else: identity (lowercased only)
--
-- Non-deterministic edge: rows whose `email` is NULL stay NULL in the new
-- column (no Steam-only accounts have an email by definition; the
-- uniqueness check short-circuits on null input).
--
-- Partial UNIQUE index, not a plain UNIQUE constraint: many rows are NULL
-- (Steam-only accounts that never set an email) and Postgres treats NULL
-- as distinct, but we want the cleanest possible constraint for the
-- non-null subset. Partial index is the standard idiom for "uniqueness
-- where the value is set".

ALTER TABLE steam_users
    ADD COLUMN IF NOT EXISTS canonical_email VARCHAR(255);

UPDATE steam_users
   SET canonical_email = (
        CASE
            WHEN email IS NULL OR position('@' in email) = 0 THEN NULL
            WHEN lower(split_part(email, '@', 2)) IN ('gmail.com', 'googlemail.com') THEN
                replace(split_part(split_part(lower(trim(email)), '@', 1), '+', 1), '.', '')
                    || '@gmail.com'
            ELSE lower(trim(email))
        END
   )
 WHERE canonical_email IS NULL
   AND email IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_steam_users_canonical_email
    ON steam_users (canonical_email)
 WHERE canonical_email IS NOT NULL;
