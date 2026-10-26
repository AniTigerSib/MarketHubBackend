# Redis users

`users.acl` is loaded with `--aclfile` (see `docker-compose.yml`). Redis doesn't allow
comments in that file, so they live here. Passwords are development values.

| User | Keys | Commands | Why |
|---|---|---|---|
| `default` | | disabled | every client must log in |
| `auth-service` | `revoked:jti:*`, `tv:*` | `GET`, `SET`, scripts | writes the access-token blacklist |
| `gateway` | read-only `revoked:jti:*`, `tv:*`; read/write `rl:*` | `MGET`, scripts (`TIME`, `HMGET`, `HSET`, `PEXPIRE`) | checks the blacklist, keeps rate-limit counters |

Keys:

- `revoked:jti:<jti>`: one revoked access token (single logout). Expires when the token would.
- `tv:<userId>`: the user's current token version ("log out everywhere"). Expires after the
  access-token lifetime, when every older token has expired anyway.
- `rl:<rule>|<client ip>`: a rate-limit bucket (hash of `tokens`, `ts`). Expires after the
  rule's window, when it would be full again.

After editing `users.acl`, restart Redis: `docker compose restart redis`.
