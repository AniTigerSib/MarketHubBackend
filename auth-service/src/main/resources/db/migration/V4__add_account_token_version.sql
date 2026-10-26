-- Increased by "log out everywhere" (and later password change, ban). Every access token
-- carries the version it was issued with as the "tv" claim; older versions are rejected.
ALTER TABLE accounts ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
