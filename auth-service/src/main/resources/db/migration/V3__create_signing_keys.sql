-- RSA key pairs for signing access tokens. Public halves are served as a JWKS so
-- other services can verify tokens themselves. The newest non-retired key signs;
-- retired keys stay published until tokens signed with them have expired.
CREATE TABLE signing_keys (
    kid         VARCHAR(64) PRIMARY KEY,
    algorithm   VARCHAR(16) NOT NULL,
    public_key  BYTEA       NOT NULL, -- X.509 SubjectPublicKeyInfo, DER
    private_key BYTEA       NOT NULL, -- PKCS#8, DER
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at  TIMESTAMPTZ
);
