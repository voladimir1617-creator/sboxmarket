-- "Log out on every device" infrastructure. Each SteamUser carries an
-- `session_epoch` long; on login the current value is stashed in the
-- HttpSession. A request filter checks the stashed value against the
-- user's current value on every /api/* hit. Logout-All simply bumps
-- this to System.currentTimeMillis() — every other live session goes
-- stale on its next request and is 401'd.
ALTER TABLE steam_users ADD COLUMN session_epoch BIGINT NOT NULL DEFAULT 0;
