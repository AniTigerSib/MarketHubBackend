# MarketHub Backend Architecture

MarketHub's backend is a set of small Spring Boot services. Clients talk only to an API
gateway. Behind it, each service owns its own database, and services share information
through Kafka events instead of calling each other. The one exception is that services
fetch auth-service's **public keys** so they can check access tokens themselves.

- **Stack:** Java 21, Spring Boot 4.0.8, Spring Cloud 2025.1.3, PostgreSQL 18, Apache Kafka 4.3 (KRaft), Gradle multi-module
- **Data access:** plain SQL through Spring's `JdbcClient` (no JPA/Hibernate); schema managed by Flyway
- **Auth:** RS256-signed JWT access tokens + rotating opaque refresh tokens; public keys published as a JWKS

---

## 1. Overview

```
                                 client
                                   │ HTTP :8080
                           ┌───────▼────────┐
                           │    gateway     │  Spring Cloud Gateway (WebMVC)
                           │  routes only   │  no auth checks of its own
                           └───┬────────┬───┘
                     /users/** │        │ /auth/**
                ┌──────────────▼─┐    ┌─▼──────────────┐
                │  user-service  │◄───┤  auth-service  │  public keys (JWKS),
                │     :8081      │    │     :8082      │  fetched once and cached
                └───┬────────▲───┘    └──┬──────────┬──┘
                    │        │ consumes  │ produces │
               ┌────▼───┐  ┌─┴───────────▼──┐  ┌────▼───┐
               │user-db │  │     Kafka      │  │auth-db │
               │Postgres│  │ user.registered│  │Postgres│
               └────────┘  │ user.logged-in │  └────────┘
                           └────────────────┘
```

Each database is used by one service only. `user.logged-in` has no consumer yet.

| Component | Port (host) | Role | Owns |
|---|---|---|---|
| **gateway** | 8080 | Single public entry point; routes by URL path | nothing |
| **auth-service** | internal 8082 | Registration, login, tokens, signing keys | `auth-db`: accounts, roles, refresh tokens, signing keys |
| **user-service** | internal 8081 | User profiles | `user-db`: profiles |
| **kafka** | 9092 | Event bus between services | topics `user.registered`, `user.logged-in` |
| **auth-db** | 5434 | PostgreSQL 18 for auth-service only | |
| **user-db** | 5433 | PostgreSQL 18 for user-service only | |

The host ports for Kafka and the databases are published only so services can be run from
an IDE. auth-service and user-service aren't published; outside Docker, the gateway is the
only way to reach them.

### Key ideas

1. **Database per service.** Neither service can read the other's tables. Each copies the data it needs from events.
2. **One identity id.** auth-service generates a UUID for each account. user-service uses the same id as its primary key, and it's the `sub` claim of every access token.
3. **Auth-service owns identity.** Username, email and password live in auth-service. user-service keeps a read-only copy of username and email, and owns the profile fields (display name, avatar, phone).
4. **Tokens are checked locally.** auth-service signs tokens with a private RSA key. Other services check them with the matching public key, so they don't call auth-service on every request.

---

## 2. Gateway

`gateway/src/main/resources/application.yml`

| Path | Forwarded to |
|---|---|
| `/auth/**` | `AUTH_SERVICE_URL` (default `http://localhost:8082`) |
| `/users/**` | `USER_SERVICE_URL` (default `http://localhost:8081`) |

The gateway is a plain router. It doesn't check tokens, rate-limit, or change requests;
each service enforces its own security. `/.well-known/jwks.json` isn't routed, so the JWKS
is only reachable inside the Docker network.

---

## 3. auth-service

### 3.1 HTTP API

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /auth/register` | `{username, email, password, displayName?}` | `201 {userId, username, email}` | `400` validation, `409` username/email taken |
| `POST /auth/login` | `{login, password}` (`login` = username **or** email) | `200 {accessToken, refreshToken, tokenType: "Bearer", expiresIn: 900}` | `401` |
| `POST /auth/refresh` | `{refreshToken}` | `200` new token pair (same shape as login) | `401` |
| `POST /auth/logout` | `{refreshToken}` | `204` | |
| `GET /.well-known/jwks.json` | | `200` JWKS (public keys), `Cache-Control: max-age=300` | |

**Validation on register:**
- **username:** 3–32 characters from `A-Z a-z 0-9 _ . -`. It can't contain `@`, so a login value is never ambiguous.
- **email:** a valid address, at most 254 characters.
- **password:** 8–72 characters, because BCrypt only uses the first 72 bytes.
- **displayName:** optional, at most 64 characters. It defaults to the username.

### 3.2 Database (`auth-db`)

Migrations: `auth-service/src/main/resources/db/migration/`

```
V1  accounts ─┬─< account_roles >── roles
              │
V2            └─< refresh_tokens (self-reference: replaced_by)

V3  signing_keys  (standalone)
```

| Table | Columns | Notes |
|---|---|---|
| `accounts` | `id` UUID PK, `username`, `email`, `password_hash`, `enabled`, `last_login_at`, `created_at`, `updated_at` | Unique indexes on `lower(username)` and `lower(email)`, so case doesn't matter. CHECK constraints mirror the username/email rules. |
| `roles` | `name` PK, `description` | Seeded with `USER` and `ADMIN`. |
| `account_roles` | `account_id` → accounts, `role` → roles | New accounts get `USER`. |
| `refresh_tokens` | `id`, `account_id`, `token_hash` (BYTEA, unique), `issued_at`, `expires_at`, `revoked_at`, `replaced_by` | Only the SHA-256 hash of a token is stored. `replaced_by` points to the token that replaced this one when it was rotated. |
| `signing_keys` | `kid` PK, `algorithm`, `public_key` (X.509 DER), `private_key` (PKCS#8 DER), `created_at`, `retired_at` | RSA key pairs for signing access tokens. |

### 3.3 Registration

```
client ──POST /auth/register──► AuthService.register
   1. existsByUsername / existsByEmail (case-insensitive)  → 409 with a clear message
   2. hash password (BCrypt via DelegatingPasswordEncoder, stored as "{bcrypt}...")
   3. one transaction: INSERT accounts + INSERT account_roles('USER')
        (a unique-index race between step 1 and 3 → DuplicateKeyException → 409)
   4. after commit: publish UserRegistered{userId, username, email, displayName}
        to Kafka topic user.registered, key = userId
   5. 201 {userId, username, email}
```

The event is published only after the transaction commits, so user-service never sees an
account that was rolled back. The Kafka send doesn't wait for confirmation (see
[Limitations](#9-known-limitations)).

### 3.4 Login

```
1. login contains "@" ? find by lower(email) : find by lower(username)
     (one SQL query; roles collected with array_agg)
2. unknown login → still runs a BCrypt check against a dummy hash, so a wrong
   username takes as long as a wrong password → 401
3. wrong password or disabled account → 401 "Invalid login or password"
4. one transaction: update accounts.last_login_at, insert a new refresh token
5. publish UserLoggedIn{userId, username, loggedInAt} to user.logged-in
6. return access token (15 min) + refresh token (30 days)
```

### 3.5 Access tokens (JWT)

Signed with **RS256** using the active RSA private key. Example contents:

```json
// header
{ "alg": "RS256", "kid": "590118c8-81aa-42b0-96aa-192c70e45ad5" }
// claims
{
  "jti": "<random uuid>",
  "iss": "markethub-auth",
  "sub": "<user id>",
  "iat": 1761400000,
  "exp": 1761400900,
  "username": "alice",
  "email": "alice@markethub.local",
  "roles": ["USER"]
}
```

`kid` tells the verifier which published public key to use. Lifetime is set by
`markethub.jwt.access-token-ttl` (default `15m`). Access tokens can't be revoked; they stay
valid until they expire.

### 3.6 Refresh tokens

- **Format:** 32 random bytes, base64url-encoded. The value is opaque: it isn't a JWT and carries no data.
- **Storage:** only `SHA-256(token)` is saved. The row id is derived from that hash.
- **Lifetime:** `markethub.jwt.refresh-token-ttl` (default `30d`).
- **Each token works once.** A successful refresh marks the old row as revoked, points its `replaced_by` at the new row, and returns a new pair.

When `POST /auth/refresh` runs, it locks the token's row (`SELECT ... FOR UPDATE`) so the same token can't be used twice at the same moment. The outcome depends on the token's state:

| State of the presented token | Result |
|---|---|
| Unknown | 401 |
| **Already rotated** (`replaced_by` set) | Probably stolen and replayed: **every active refresh token of that account is revoked**, a warning is logged, 401 |
| Revoked by logout, or expired | 401 |
| Account missing or disabled | 401 |
| Valid | Rotated; new access + refresh token returned |

The 401 is returned only after the transaction commits, so the "revoke everything" step is
saved even though the request fails.

**Logout** sets `revoked_at` on the presented refresh token. Access tokens already issued
keep working until they expire, at most 15 minutes.

### 3.7 Signing keys and JWKS

`SigningKeys` loads the key when the service starts:

```
in one transaction:
  pg_advisory_xact_lock(...)            ← instances starting together take turns
  newest signing_keys row with retired_at IS NULL ?
     yes → load it
     no  → generate RSA-2048, kid = random UUID, insert it
```

All auth-service instances and restarts therefore share one key. Tokens issued before a
restart keep working; this was checked with the running stack.

`GET /.well-known/jwks.json` returns the **public** halves of:
- every key that isn't retired, and
- keys retired less than one access-token lifetime ago, since tokens signed with them can still be valid.

```json
{ "keys": [ { "kty": "RSA", "kid": "…", "use": "sig", "alg": "RS256", "n": "…", "e": "AQAB" } ] }
```

The schema is ready for key rotation (`retired_at`), but nothing rotates keys automatically yet.

---

## 4. user-service

### 4.1 HTTP API

| Method & path | Auth | Body | Response |
|---|---|---|---|
| `GET /users/{username}` | public | | `200 {id, username, displayName, avatarUrl}`; email and phone stay hidden. `404` if unknown. Username lookup ignores case. |
| `GET /users/me` | Bearer token | | `200 {id, username, email, displayName, avatarUrl, phone}`. `401` without a valid token. |
| `PUT /users/me` | Bearer token | `{displayName, avatarUrl?, phone?}` | `200` updated profile. Replaces all three fields; `null` clears the avatar or phone. |

`/users/me` is resolved from the token's `sub` claim, so users can only read and edit their
own full profile. It returns `404 "Profile not found"` for a brief moment after registration,
until the Kafka event has been processed.

**Validation:**
- **displayName:** 1–64 characters.
- **avatarUrl:** an `http(s)` URL, at most 2048 characters.
- **phone:** E.164 format, e.g. `+14155550123`. The database enforces this too.

### 4.2 Token verification

`user-service/.../security/SecurityConfig.java`

```
request with "Authorization: Bearer <jwt>"
  → NimbusJwtDecoder
       • signature: RS256 against keys from markethub.security.jwk-set-uri
         (http://auth-service:8082/.well-known/jwks.json in Docker)
       • exp / nbf checked, iss must equal "markethub-auth"
  → JwtAuthenticationConverter: claim "roles": ["USER"] → authority ROLE_USER
  → controller gets the Jwt (@AuthenticationPrincipal)
```

- The key set is downloaded on first use and cached. A token with an unknown `kid` triggers a fresh download, so new keys need no restart.
- user-service doesn't need auth-service to be up when it starts.
- Sessions are stateless, and CSRF protection is off because tokens are sent in a header, never as cookies.

Access rules: `/error` is open, `/users/me` needs a token, `GET /users/*` is open, and everything else needs a token.

### 4.3 Database (`user-db`)

Migration: `user-service/src/main/resources/db/migration/V1__create_users.sql`

| Column | Type | Source |
|---|---|---|
| `id` | UUID PK | auth-service account id (from the event) |
| `username` | VARCHAR(32), unique ignoring case | from the event; auth-service owns it |
| `email` | VARCHAR(254), unique ignoring case | from the event; auth-service owns it |
| `display_name` | VARCHAR(64) | set from the event, then edited here |
| `avatar_url` | VARCHAR(2048) | edited here |
| `phone` | VARCHAR(16), CHECK E.164 | edited here |
| `created_at`, `updated_at` | TIMESTAMPTZ | |

### 4.4 Consuming `user.registered`

`UserRegisteredListener` (consumer group `user-service`) runs:

```sql
INSERT INTO users (id, username, email, display_name) VALUES (...)
ON CONFLICT (id) DO UPDATE SET username = EXCLUDED.username, email = EXCLUDED.email, ...
```

Kafka can deliver the same event more than once, so this upsert is safe to repeat. A repeat
only refreshes the identity fields; it never overwrites a display name, avatar or phone the
user edited.

---

## 5. Kafka events

Single-node Kafka 4.3 in KRaft mode (no ZooKeeper). Containers connect to `kafka:19092`; the
host uses `localhost:9092`. Messages are JSON without type headers, and each service has its
own copy of the event records. Both services declare both topics with the same settings, so
either can start first.

| Topic | Partitions | Cleanup | Key | Payload | Producer → Consumer |
|---|---|---|---|---|---|
| `user.registered` | 3 | **compacted** | user id | `{userId, username, email, displayName}` | auth-service → user-service |
| `user.logged-in` | 3 | delete (default retention) | user id | `{userId, username, loggedInAt}` | auth-service → *(no consumer yet)* |

Compaction makes Kafka keep the latest event for each user. The topic therefore always holds
the full user list, and a new consumer can rebuild it by reading from the start. Consumers
start from the earliest offset.

---

## 6. End-to-end request flows

**New user to authenticated request**

```
1  POST /auth/register ─► gateway ─► auth-service ─► auth-db (account + role)
                                          └─► Kafka user.registered
2                                                         └─► user-service ─► user-db (profile)
3  POST /auth/login    ─► gateway ─► auth-service ─► auth-db (last login, refresh token)
                       ◄─ {accessToken (RS256 JWT), refreshToken}
4  GET /users/me  + Bearer ─► gateway ─► user-service
                                          ├─ (first time) GET auth-service/.well-known/jwks.json
                                          ├─ verify signature / exp / iss locally
                                          └─► user-db (profile where id = sub)
5  POST /auth/refresh ─► auth-service: rotate refresh token, new pair
6  POST /auth/logout  ─► auth-service: revoke refresh token
```

---

## 7. Configuration

| Service | Property / env var | Default (IDE) | Docker value |
|---|---|---|---|
| auth-service | `spring.datasource.url` / `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5434/auth` | `jdbc:postgresql://auth-db:5432/auth` |
| auth-service | `markethub.jwt.issuer` | `markethub-auth` | |
| auth-service | `markethub.jwt.access-token-ttl` | `15m` | |
| auth-service | `markethub.jwt.refresh-token-ttl` | `30d` | |
| user-service | `spring.datasource.url` / `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/users` | `jdbc:postgresql://user-db:5432/users` |
| user-service | `markethub.security.jwk-set-uri` / `JWK_SET_URI` | `http://localhost:8082/.well-known/jwks.json` | `http://auth-service:8082/.well-known/jwks.json` |
| user-service | `markethub.security.issuer` | `markethub-auth` | |
| both | `spring.kafka.bootstrap-servers` / `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:19092` |
| gateway | `USER_SERVICE_URL`, `AUTH_SERVICE_URL` | `http://localhost:8081` / `:8082` | service names |

Database credentials are development values set in `docker-compose.yml`
(`user_service`/`user_service`, `auth_service`/`auth_service`).

The issuer must be the same string in both services, or user-service rejects every token.

---

## 8. Build, run and test

### Project layout

```
build.gradle            shared plugins & dependencies (Boot, Lombok, web, test)
settings.gradle         modules: gateway, auth-service, user-service
Dockerfile              one image recipe for all services (--build-arg SERVICE=…)
docker/maven-mirror.init.gradle   Docker builds resolve from Google's Maven Central mirror
docker-compose.yml      kafka, user-db, auth-db, gateway, auth-service, user-service
<service>/src/main/resources/db/migration/   Flyway migrations
```

### Docker image

- **Build stage:** JDK 21 runs `./gradlew :<service>:bootJar`.
- **Downloads:** dependencies come from Google's Maven Central mirror first. On some networks `repo.maven.apache.org` randomly returns 403, and Gradle doesn't retry those.
- **Build cache:** the Gradle cache lives in a BuildKit cache mount, shared by all three images and kept between builds. It's locked, so parallel builds take turns on that step; Gradle's own locking doesn't work across separate build containers. This needs `docker buildx`.
- **Runtime stage:** a JRE 21 image running the jar as a non-root user.

### Startup order (compose)

`kafka` (health: broker API), `user-db` and `auth-db` (health: `pg_isready`) → `auth-service`,
`user-service` (wait for healthy Kafka and their own DB) → `gateway`. Flyway migrates each
database when its service starts.

### Commands

```bash
docker compose up -d --build     # build and start everything
docker compose logs -f auth-service user-service
docker compose down              # stop, keep data
docker compose down -v           # stop and wipe both databases
./gradlew test                   # all tests; DB tests need Docker
```

### Tests (20)

| Test | Kind | Covers |
|---|---|---|
| `AuthControllerTests` | Web slice, mocked service | request validation, status codes, JSON shape |
| `AuthFlowIntegrationTests` | Full app + Postgres (Testcontainers) | register → login by username/email → token verified with the **public JWKS key only** → refresh rotation → reuse detection → logout; duplicate checks ignoring case |
| `UserControllerTests` | Web slice + security config | public vs `/me` access, token required, validation |
| `UserRepositoryTests` | JDBC slice + Postgres (Testcontainers) | migrations, upsert is safe to repeat and keeps edited fields, lookup ignoring case, DB phone constraint |

The Testcontainers tests are skipped automatically when Docker isn't available.

---

## 9. Known limitations

| Area | Limitation | Usual fix |
|---|---|---|
| Events | Registration commits to the DB, then sends to Kafka without waiting. If Kafka is down, the account exists but no profile is created. | Transactional outbox table + relay |
| Keys | Private keys are stored unencrypted in `auth-db`. Keys are never rotated automatically. | Encrypt with a KMS/master key; scheduled rotation |
| Tokens | Access tokens can't be revoked before they expire (≤ 15 min). Expired refresh-token rows are never deleted. | Short TTL (current) or a deny-list; cleanup job |
| Gateway | Doesn't validate tokens or rate-limit; each service must protect itself. | Token check / rate limiting at the gateway |
| Identity changes | No endpoints to change username, email or password, so nothing publishes identity updates yet (user-service's upsert already handles them). | Add endpoints + `user.updated` event |
| Roles | `ADMIN` exists, but nothing assigns it and no endpoint requires it. | Admin tooling |
| Contracts | Event records are copied in each service and can drift apart without anyone noticing. | Shared contract module or schema registry |
| Infra | Single Kafka broker, replication factor 1, development DB passwords in compose. | Production config / secrets management |
| Health | No actuator health endpoints; the gateway waits for services to start, not to be ready. | `spring-boot-starter-actuator` + compose healthchecks |
