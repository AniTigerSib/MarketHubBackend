-- Public profile data. Identity fields (id, username, email) are owned by auth-service
-- and copied here from user.registered events; the rest is edited in this service.
CREATE TABLE users (
    id           UUID          PRIMARY KEY, -- same id as the auth-service account
    username     VARCHAR(32)   NOT NULL,
    email        VARCHAR(254)  NOT NULL,
    display_name VARCHAR(64)   NOT NULL,
    avatar_url   VARCHAR(2048),
    phone        VARCHAR(16)   CHECK (phone ~ '^\+[1-9][0-9]{6,14}$'), -- E.164
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX users_username_key ON users (lower(username));
CREATE UNIQUE INDEX users_email_key ON users (lower(email));
