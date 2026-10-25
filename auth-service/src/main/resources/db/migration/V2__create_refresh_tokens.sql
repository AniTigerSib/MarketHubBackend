-- Only a SHA-256 hash of each refresh token is stored, never the token itself.
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    account_id  UUID        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    token_hash  BYTEA       NOT NULL UNIQUE,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    -- Set when the token was rotated; used to spot reuse of an old token
    replaced_by UUID        REFERENCES refresh_tokens (id)
);

CREATE INDEX refresh_tokens_account_id_idx ON refresh_tokens (account_id);
