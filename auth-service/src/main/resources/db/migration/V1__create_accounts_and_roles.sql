-- Login credentials. The account id is the user's id across all services.
CREATE TABLE accounts (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(32)  NOT NULL CHECK (username ~ '^[A-Za-z0-9_.-]{3,32}$'),
    email         VARCHAR(254) NOT NULL CHECK (position('@' IN email) > 1),
    password_hash VARCHAR(255) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness; login looks accounts up by either column.
-- Usernames can't contain '@', so a login value is never both.
CREATE UNIQUE INDEX accounts_username_key ON accounts (lower(username));
CREATE UNIQUE INDEX accounts_email_key ON accounts (lower(email));

CREATE TABLE roles (
    name        VARCHAR(32)  PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

INSERT INTO roles (name, description) VALUES
    ('USER', 'Regular marketplace user'),
    ('ADMIN', 'Platform administrator');

CREATE TABLE account_roles (
    account_id UUID        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    role       VARCHAR(32) NOT NULL REFERENCES roles (name),
    PRIMARY KEY (account_id, role)
);
