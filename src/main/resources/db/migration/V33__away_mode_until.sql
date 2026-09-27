-- Vacation mode scheduling. The existing /api/listings/away endpoint
-- toggles every active listing's `hidden` flag, but it's manual on
-- both ends — a seller heading off for a week's vacation sets it on,
-- forgets to flip it back, and their stall stays invisible for days
-- after they're back.
--
-- This column stores an epoch-ms expiry. When set, a scheduled sweep
-- (every 15 min) flips every active listing back to visible and
-- clears the column. Setting `away_mode_until` to NULL means
-- "indefinite away mode" — the existing manual flip-back path still
-- works.
ALTER TABLE steam_users
    ADD COLUMN away_mode_until BIGINT;

-- Tiny index — the sweep wants "users whose expiry has passed", so a
-- partial index on the not-null subset keeps it cheap regardless of
-- how many users exist.
CREATE INDEX idx_steam_users_away_mode_until
    ON steam_users(away_mode_until)
    WHERE away_mode_until IS NOT NULL;
