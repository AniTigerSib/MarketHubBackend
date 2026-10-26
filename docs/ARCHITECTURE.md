# MarketHub Backend Architecture

MarketHub's backend is a set of small Spring Boot services for a classifieds marketplace.
Clients talk only to an API gateway. Behind it, each service owns its own database, and
services share information through Kafka events instead of calling each other. The one
exception is that services fetch auth-service's **public keys** so they can check access
tokens themselves.

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
                               │ routes+filters │  CORS, rate limits, token blacklist
                               │                │  (Redis); no signature checks
                               └──┬─────┬────┬──┘
                      /users/**   │     │    │  /listings/** /categories/**
                ┌─────────────────┘     │    └──────────┐  /cities/** /favorites/**
                │             /auth/**  │               │
       ┌────────▼───────┐    ┌──────────▼─────┐   ┌─────▼───────────┐
       │  user-service  │◄───┤  auth-service  ├──►│ listing-service │   public keys (JWKS),
       │     :8081      │    │     :8082      │   │      :8083      │   fetched once and cached
       └──┬─────────▲───┘    └──┬──────────┬──┘   └────────┬────────┘
          │         │consumes   │ produces │               │
     ┌────▼───┐  ┌──┴───────────▼──┐  ┌────▼───┐     ┌─────▼──────┐
     │user-db │  │      Kafka      │  │auth-db │     │ listing-db │
     │Postgres│  │ user.registered │  │Postgres│     │  Postgres  │
     └────────┘  │ user.logged-in  │  └────────┘     └────────────┘
                 └─────────────────┘
```

Each database is used by one service only. `user.logged-in` has no consumer yet.
auth-service writes the access-token blacklist to **Redis**; the gateway reads it and keeps its
rate-limit counters there (§2, §3.8).
listing-service doesn't use Kafka; it only needs a user's id, which comes from the token.

| Component | Port (host) | Role | Owns |
|---|---|---|---|
| **gateway** | 8080 | Single public entry point; routes by URL path, CORS, rate limits on auth endpoints, rejects revoked access tokens | nothing (counters in Redis) |
| **auth-service** | internal 8082 | Registration, login, tokens, signing keys | `auth-db`: accounts, roles, refresh tokens, signing keys |
| **user-service** | internal 8081 | User profiles | `user-db`: profiles |
| **listing-service** | internal 8083 | Listings, catalog, search, favorites | `listing-db`: categories, subcategories, cities, listings, favorites |
| **kafka** | 9092 | Event bus between services | topics `user.registered`, `user.logged-in` |
| **redis** | 6379 | Access-token blacklist (§3.8), gateway rate-limit counters | keys `revoked:jti:*`, `tv:*`, `rl:*` |
| **auth-db** | 5434 | PostgreSQL 18 for auth-service only | |
| **user-db** | 5433 | PostgreSQL 18 for user-service only | |
| **listing-db** | 5435 | PostgreSQL 18 for listing-service only | |

The host ports for Kafka, Redis and the databases are published only so services can be run from
an IDE. The three services aren't published; outside Docker, the gateway is the only way
to reach them.

### Key ideas

1. **Database per service.** No service can read another's tables. Each copies the data it needs from events, or uses ids.
2. **One identity id.** auth-service generates a UUID for each account. user-service uses it as its primary key, listing-service stores it as a listing's `seller_id`, and it's the `sub` claim of every access token.
3. **Auth-service owns identity.** Username, email and password live in auth-service. user-service keeps a read-only copy of username and email, and owns the profile fields (display name, avatar, phone).
4. **Tokens are checked locally.** auth-service signs tokens with a private RSA key. Other services check them with the matching public key, so they don't call auth-service on every request.

---

## 2. Gateway

`gateway/src/main/resources/application.yml`

| Path | Forwarded to |
|---|---|
| `/auth/**` | `AUTH_SERVICE_URL` (default `http://localhost:8082`) |
| `/users/**` | `USER_SERVICE_URL` (default `http://localhost:8081`) |
| `/listings/**`, `/categories/**`, `/cities/**`, `/favorites/**` | `LISTING_SERVICE_URL` (default `http://localhost:8083`) |

The gateway doesn't verify tokens or change requests; each service verifies tokens itself.
`/.well-known/jwks.json` isn't routed, so the JWKS is only reachable inside the Docker network.

It runs three servlet filters before routing, in this order: CORS (`gateway/.../cors`), rate
limiting (`gateway/.../ratelimit`), token blacklist (`gateway/.../revocation`).

**CORS.** Handled only here; the services have no CORS config. Allowed origins come from
`CORS_ALLOWED_ORIGINS` (default `http://localhost:5173,http://localhost:4173`, the Vite dev and
preview servers). Preflight `OPTIONS` requests are answered by the gateway and never forwarded;
unknown origins get `403`. Allowed headers: `Authorization`, `Content-Type`; exposed headers:
`Location`, `Retry-After`. No cookies (`allowCredentials=false`): tokens travel in the
`Authorization` header. Preflight answers are cached by the browser for 1 hour.

**Rate limiting.** Per client IP, token bucket (the allowance refills gradually over the window).
Buckets live in Redis (`rl:<rule>|<ip>`, updated atomically by a Lua script using Redis's
clock), so they survive restarts and are shared by all gateway instances. While Redis is down,
each instance counts in memory instead.

| Rule | Request | Limit |
|---|---|---|
| `login` | `POST /auth/login` | 10 per minute |
| `register` | `POST /auth/register` | 5 per 15 minutes |
| `refresh` | `POST /auth/refresh` | 30 per minute |

Over the limit the gateway answers without forwarding:

```
429 Too Many Requests
Retry-After: <seconds>
{"status":429,"error":"Too Many Requests","message":"Too many requests. Try again in <n> seconds."}
```

- The CORS filter runs first, so a browser can read the `429` and its `Retry-After`.
- Paths are matched after the servlet container normalizes them, so `//auth/login` or
  `/auth/%6Cogin` count against the `login` rule.
- The client IP is `request.getRemoteAddr()`. Behind a reverse proxy or load balancer, set
  `SERVER_FORWARD_HEADERS_STRATEGY=native` so the address comes from `X-Forwarded-For`;
  otherwise every client shares the proxy's address and limit.

**Token blacklist.** For every request with `Authorization: Bearer <jwt>`, the gateway reads
`jti`, `sub` and `tv` from the token **without verifying it** and looks up `revoked:jti:<jti>`
and `tv:<sub>` in one `MGET`. It answers itself, without forwarding, when the token was logged
out or its `tv` is lower than the stored version (see §3.8):

```
401 Unauthorized
WWW-Authenticate: Bearer error="invalid_token", error_description="The access token has been revoked"
{"status":401,"error":"Unauthorized","message":"The access token has been revoked."}
```

- Skipping signature checks is safe because the claims are only used to reject: a forged
  token can at most get its own request rejected, and the service still verifies everything.
- No route rules are needed: any request carrying a revoked token is rejected, including on
  public endpoints (the services already answer `401` for bad tokens there).
- A missing or unreadable token passes unchanged; the service decides.
- This check protects only traffic through the gateway. Direct calls to a service (none
  exist today) would need the same lookup in the service.

**When Redis is down.** Redis calls time out after 200 ms. After a failure the gateway skips
Redis for 5 seconds (`RedisAvailability`), so only one request per 5 s pays the timeout. In
that time revoked tokens are let through (they still expire within 15 minutes) and rate
limits are counted in memory. The gateway logs once when Redis goes down and once when it's back.
- Rules live in `markethub.rate-limit.rules` in `application.yml`; `RATE_LIMIT_ENABLED=false`
  turns limiting off.

---

## 3. auth-service

### 3.1 HTTP API

| Method & path | Body | Success | Errors |
|---|---|---|---|
| `POST /auth/register` | `{username, email, password, displayName?}` | `201 {userId, username, email}` | `400` validation, `409` username/email taken |
| `POST /auth/login` | `{login, password}` (`login` = username **or** email) | `200 {accessToken, refreshToken, tokenType: "Bearer", expiresIn: 900}` | `401` |
| `POST /auth/refresh` | `{refreshToken}` | `200` new token pair (same shape as login) | `401` |
| `POST /auth/logout` | `{refreshToken}`; optional `Authorization: Bearer <access token>` | `204` | `503` blacklist unavailable (refresh token is revoked anyway) |
| `POST /auth/logout-all` | none; `Authorization: Bearer <access token>` required | `204` | `401` missing/invalid/already revoked token, `503` blacklist unavailable |
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
V4  accounts.token_version
```

| Table | Columns | Notes |
|---|---|---|
| `accounts` | `id` UUID PK, `username`, `email`, `password_hash`, `enabled`, `token_version`, `last_login_at`, `created_at`, `updated_at` | Unique indexes on `lower(username)` and `lower(email)`, so case doesn't matter. CHECK constraints mirror the username/email rules. |
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
[Limitations](#10-known-limitations)).

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
  "roles": ["USER"],
  "tv": 0
}
```

`kid` tells the verifier which published public key to use. Lifetime is set by
`markethub.jwt.access-token-ttl` (default `15m`). `tv` is the account's token version when
the token was issued; tokens can be revoked early through the blacklist (§3.8).

### 3.6 Refresh tokens

- **Format:** 32 random bytes, base64url-encoded. The value is opaque: it isn't a JWT and carries no data.
- **Storage:** only `SHA-256(token)` is saved. The row id is derived from that hash.
- **Lifetime:** `markethub.jwt.refresh-token-ttl` (default `30d`).
- **Each token works once.** A successful refresh marks the old row as revoked, points its `replaced_by` at the new row, and returns a new pair.

When `POST /auth/refresh` runs, it locks the account's row and then the token's row (`FOR NO KEY UPDATE` / `FOR UPDATE`), so the same token can't be used twice at the same moment. "Log out everywhere" takes the account lock first too, so a refresh running at the same time can't leave a new session behind. The outcome depends on the token's state:

| State of the presented token | Result |
|---|---|
| Unknown | 401 |
| **Already rotated** (`replaced_by` set) | Probably stolen and replayed: **every session of that account is revoked** (refresh tokens and access tokens, as in "log out everywhere"), a warning is logged, 401 |
| Revoked by logout, or expired | 401 |
| Account missing or disabled | 401 |
| Valid | Rotated; new access + refresh token returned |

The 401 is returned only after the transaction commits, so the "revoke everything" step is
saved even though the request fails.

**Logout** sets `revoked_at` on the presented refresh token. If the request also carries a
valid access token, that token is blacklisted (§3.8).

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
restart keep working.

`GET /.well-known/jwks.json` returns the **public** halves of:
- every key that isn't retired, and
- keys retired less than one access-token lifetime ago, since tokens signed with them can still be valid.

```json
{ "keys": [ { "kty": "RSA", "kid": "…", "use": "sig", "alg": "RS256", "n": "…", "e": "AQAB" } ] }
```

The schema is ready for key rotation (`retired_at`), but nothing rotates keys automatically yet.

### 3.8 Revoking access tokens (blacklist)

Access tokens are checked locally by each service, so auth-service can't "take one back".
Instead it writes a blacklist to Redis, and the gateway rejects listed tokens (§2).

Two kinds of entries, both written only by auth-service (`TokenRevocations`):

| Key | Value | Written by | Expires | Rejects |
|---|---|---|---|---|
| `revoked:jti:<jti>` | `1` | `POST /auth/logout` with an access token | when that token expires | that one token |
| `tv:<userId>` | token version | `POST /auth/logout-all`, refresh-token reuse | after one access-token lifetime | that user's tokens with a lower `tv` claim |

**Token version.** `accounts.token_version` starts at 0 and is copied into every access token
as `tv`. Revoking all sessions (`AuthService.revokeAllSessions`, for later password change or
ban too) does, in one transaction: increase `token_version` and revoke every refresh token;
then after the commit, write the new version to `tv:<userId>`.

```
tokens carry tv=3
log out everywhere → token_version=4, refresh tokens revoked, Redis tv:<user>=4
old token  (tv=3) → 3 < 4 → rejected
new login  (tv=4) → OK
```

- A counter instead of a timestamp avoids `iat` rounding (it's whole seconds) and clock differences between instances.
- The Redis entry can expire after 15 minutes: every token with a lower version was issued before the change, so it has expired by then.
- A Lua script only ever raises the stored version, so two revocations reaching Redis out of order can't lower it.
- Tokens issued before versions existed have no `tv` claim and count as version 0.

**Verifying the caller.** auth-service has no Spring Security filter chain. `AccessTokenVerifier`
checks the access token on the logout endpoints like other services do (signature against the
published keys, issuer, expiry). `logout-all` also refuses a token whose `tv` is already lower
than the account's version, so an old stolen token can't keep logging the owner out.

**When Redis is down.** Database changes happen first, so refresh tokens are revoked anyway.
The endpoint then answers `503` (after the 2-second Redis timeout) and can safely be retried.
For refresh-token reuse, the failure is only logged; the caller gets `401` either way.

**Redis users** (`docker/redis/users.acl`, explained in `docker/redis/README.md`): the default
user is disabled; `auth-service` may only `GET`/`SET`/run scripts on `revoked:jti:*` and `tv:*`;
`gateway` may only read those keys and read/write `rl:*`.
Redis runs with append-only persistence, so a restart doesn't bring revoked tokens back.

---

## 4. user-service

### 4.1 HTTP API

| Method & path | Auth | Body | Response |
|---|---|---|---|
| `GET /users/{username}` | public | | `200 {id, username, displayName, avatarUrl}`; email and phone stay hidden. `404` if unknown. Username lookup ignores case. |
| `GET /users/id/{id}` | public | | Same public profile, looked up by user id. Used to show a listing's seller, since listings store the seller's id. |
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

`user-service/.../security/SecurityConfig.java` (listing-service uses the same setup)

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
- The service doesn't need auth-service to be up when it starts.
- Sessions are stateless, and CSRF protection is off because tokens are sent in a header, never as cookies.

Access rules: `/error` is open, `/users/me` needs a token, `GET /users/*` and `GET /users/id/*` are open, and everything else needs a token.

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

## 5. listing-service

Holds what people sell: the catalog (categories, subcategories, cities), listings, search
and favorites. Tokens are checked the same way as in user-service (section 4.2).

### 5.1 HTTP API

| Method & path | Auth | Purpose |
|---|---|---|
| `GET /categories` | public | All 10 categories with their subcategories, names in English and Russian, and the number of active listings in each |
| `GET /cities` | public | The 5 cities, names in English and Russian |
| `GET /listings` | public (token optional) | Search active listings: filters, sort, pagination (5.3) |
| `GET /listings/{id}` | public (token optional) | One listing. Archived listings are visible only to their seller; others get `404`. |
| `POST /listings` | token | Create a listing; it goes live immediately. `201` with the listing. |
| `PUT /listings/{id}` | token, seller only | Replace all editable fields. `403` for other users, `409` if archived. |
| `POST /listings/{id}/archive` | token, seller only | "Mark as sold": removes it from search. Repeating it is harmless. |
| `GET /listings/mine?status=active\|archived` | token | The caller's listings, newest first, plus `counts` per status for the account tabs |
| `GET /favorites?page&size` | token | The caller's saved listings, most recently saved first (including ones archived since) |
| `PUT /favorites/{listingId}` | token | Save a listing; repeating it is harmless. `404` if the listing isn't active. |
| `DELETE /favorites/{listingId}` | token | Remove from favorites |

When a request to a public endpoint carries a token, listings also report whether the caller
saved them (`favorite: true`). Without one, `favorite` is always `false`.

**Listing body** (create and update):

```json
{
  "categoryId": "electronics", "subcategoryId": "phones", "cityId": "msk",
  "title": "iPhone 13, 128 GB, blue",
  "description": "Battery 89%, always in a case. Box and cable included.",
  "price": 41990, "priceUnit": null,
  "condition": "used", "sellerType": "private",
  "photoUrls": ["https://…/cover.jpg"],
  "contactByMessages": true, "contactByPhone": false
}
```

| Field | Rule |
|---|---|
| `categoryId`, `subcategoryId`, `cityId` | Must exist (from `/categories`, `/cities`), and the subcategory must belong to the category. Otherwise `400`. |
| `title` | 5–100 characters |
| `description` | 30–4000 characters |
| `price` | Whole rubles, 0 or more |
| `priceUnit` | `null` for a one-off price, `"month"` (rent, salary) or `"sqm"` (per square metre) |
| `condition` | `"new"` or `"used"` |
| `sellerType` | `"private"` or `"business"` |
| `photoUrls` | Up to 10 `http(s)` URLs; the first is the cover. Stored as given; there's no upload yet. |
| `contactByMessages`, `contactByPhone` | At least one must be `true` |

**Listing response** adds `id` (a number, shown as "Listing No."), `sellerId`, `status`
(`active` or `archived`), `favoritesCount`, `favorite`, `createdAt` and `updatedAt`. It
returns ids for category, subcategory and city; the client looks up their names from
`/categories` and `/cities`. To show the seller, the client calls `GET /users/id/{sellerId}`.

### 5.2 Database (`listing-db`)

Migrations: `listing-service/src/main/resources/db/migration/`

```
V1  categories ──< subcategories          cities      (+ seed data from the design)
          │              │                   │
V2        └──────────────┴──< listings >─────┘
                                 │
V3                               └──< favorites
```

| Table | Notes |
|---|---|
| `categories` | `id` (e.g. `electronics`), `name_en`, `name_ru`, `position`. 10 rows seeded. |
| `subcategories` | `id` (e.g. `phones`), `category_id`, names, `position`. 32 rows seeded. |
| `cities` | `id` (e.g. `msk`), names, `position`. 5 rows seeded. |
| `listings` | `id` BIGINT identity, `seller_id` UUID, category/subcategory/city, `title`, `description`, `price`, `price_unit`, `item_condition`, `seller_type`, `photo_urls` TEXT[], contact flags, `status`, timestamps, `search_vector`. |
| `favorites` | `(user_id, listing_id)` primary key, `created_at`. Rows are deleted with their listing. |

The database itself enforces the field rules:
- The `(category_id, subcategory_id)` pair has a foreign key to `subcategories`, so a listing can't pair a subcategory with the wrong category.
- CHECK constraints cover title and description length, price ≥ 0, the allowed values for unit, condition, seller type and status, at most 10 photos, and at least one contact method.

`search_vector` is a generated column: `to_tsvector('simple', title || ' ' || description)`,
with a GIN index. The `simple` configuration doesn't apply language-specific word endings,
so Russian and English text are both indexed as written. There are also indexes for browsing
by category, sorting by price, and listing a seller's ads.

### 5.3 Search

`GET /listings` parameters (all optional):

| Parameter | Effect |
|---|---|
| `q` | Text search over title and description. Every word must match, and each word matches as a prefix, so `iphon` finds "iPhone" and `велосип` finds "велосипед". Up to 10 words; punctuation is ignored. |
| `category`, `subcategory`, `city` | Exact match by id |
| `minPrice`, `maxPrice` | Price range, inclusive |
| `condition` | Repeatable: `?condition=new&condition=used` |
| `sellerType` | `private` or `business` |
| `withPhoto=true` | Only listings with at least one photo |
| `sort` | `new` (default, newest first), `cheap`, `expensive` |
| `page`, `size` | 0-based page; size 1–100, default 24 |

Response: `{items: [...listings], total, page, size}`. Only active listings are returned.

The text query is built safely: only letters and digits are kept from `q`, and each word
becomes `word:*` joined with `&`, passed to `to_tsquery('simple', …)` as a parameter. All
filters are SQL parameters; nothing from the request is pasted into the SQL.

### 5.4 Listing lifecycle

```
POST /listings ──► active ──POST /archive──► archived
                     │  ▲                       (hidden from search and from other users,
                     └──┘ PUT (seller edits)     can't be edited or newly favorited)
```

There's no moderation yet, so new listings are public right away. The design's draft,
in-review and rejected states come with moderation.

---

## 6. Kafka events

Single-node Kafka 4.3 in KRaft mode (no ZooKeeper). Containers connect to `kafka:19092`; the
host uses `localhost:9092`. Messages are JSON without type headers, and each service has its
own copy of the event records. auth-service and user-service both declare both topics with
the same settings, so either can start first.

| Topic | Partitions | Cleanup | Key | Payload | Producer → Consumer |
|---|---|---|---|---|---|
| `user.registered` | 3 | **compacted** | user id | `{userId, username, email, displayName}` | auth-service → user-service |
| `user.logged-in` | 3 | delete (default retention) | user id | `{userId, username, loggedInAt}` | auth-service → *(no consumer yet)* |

Compaction makes Kafka keep the latest event for each user. The topic therefore always holds
the full user list, and a new consumer can rebuild it by reading from the start. Consumers
start from the earliest offset.

---

## 7. End-to-end request flows

**New user to posting a listing**

```
1  POST /auth/register ─► gateway ─► auth-service ─► auth-db (account + role)
                                          └─► Kafka user.registered
2                                                         └─► user-service ─► user-db (profile)
3  POST /auth/login    ─► gateway ─► auth-service ─► auth-db (last login, refresh token)
                       ◄─ {accessToken (RS256 JWT), refreshToken}
4  POST /listings + Bearer ─► gateway ─► listing-service
                                          ├─ (first time) GET auth-service/.well-known/jwks.json
                                          ├─ verify signature / exp / iss locally
                                          └─► listing-db (seller_id = token sub)
5  GET /listings?q=…      ─► gateway ─► listing-service ─► listing-db (full-text search)
6  GET /users/id/{sellerId} ─► gateway ─► user-service  (seller card on the listing page)
7  PUT /favorites/{id} + Bearer ─► listing-service ─► listing-db
8  POST /auth/refresh / logout ─► auth-service
9  POST /auth/logout-all + Bearer ─► auth-service ─► auth-db (token_version+1, revoke refresh tokens)
                                                 └─► Redis tv:<user>
```

---

## 8. Configuration

| Service | Property / env var | Default (IDE) | Docker value |
|---|---|---|---|
| auth-service | `spring.datasource.url` / `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5434/auth` | `jdbc:postgresql://auth-db:5432/auth` |
| auth-service | `markethub.jwt.issuer` | `markethub-auth` | |
| auth-service | `markethub.jwt.access-token-ttl` | `15m` | |
| auth-service | `markethub.jwt.refresh-token-ttl` | `30d` | |
| auth-service | `spring.data.redis.host` / `SPRING_DATA_REDIS_HOST` | `localhost` | `redis` |
| auth-service | `spring.data.redis.username`, `.password` / `SPRING_DATA_REDIS_USERNAME`, `_PASSWORD` | `auth-service` / `auth_service` | same |
| auth-service | `spring.data.redis.timeout`, `.connect-timeout` | `2s` | |
| user-service | `spring.datasource.url` / `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/users` | `jdbc:postgresql://user-db:5432/users` |
| listing-service | `spring.datasource.url` / `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5435/listings` | `jdbc:postgresql://listing-db:5432/listings` |
| user-, listing-service | `markethub.security.jwk-set-uri` / `JWK_SET_URI` | `http://localhost:8082/.well-known/jwks.json` | `http://auth-service:8082/.well-known/jwks.json` |
| user-, listing-service | `markethub.security.issuer` | `markethub-auth` | |
| auth-, user-service | `spring.kafka.bootstrap-servers` / `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:19092` |
| gateway | `USER_SERVICE_URL`, `AUTH_SERVICE_URL`, `LISTING_SERVICE_URL` | `http://localhost:8081` / `:8082` / `:8083` | service names |
| gateway | `markethub.cors.allowed-origins` / `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:4173` | same |
| gateway | `markethub.rate-limit.enabled` / `RATE_LIMIT_ENABLED` | `true` | |
| gateway | `markethub.rate-limit.rules` | login / register / refresh (see §2) | |
| gateway | `server.forward-headers-strategy` / `SERVER_FORWARD_HEADERS_STRATEGY` | none | set to `native` behind a reverse proxy |
| gateway | `spring.data.redis.host` / `SPRING_DATA_REDIS_HOST` | `localhost` | `redis` |
| gateway | `spring.data.redis.username`, `.password` / `SPRING_DATA_REDIS_USERNAME`, `_PASSWORD` | `gateway` / `gateway` | same |
| gateway | `spring.data.redis.timeout`, `.connect-timeout` | `200ms` | |

Database credentials are development values set in `docker-compose.yml`
(`user_service`, `auth_service`, `listing_service`, each with the same password). Redis
users and passwords are in `docker/redis/users.acl`, also development values.

The issuer must be the same string in every service, or tokens are rejected.

---

## 9. Build, run and test

### Project layout

```
build.gradle            shared plugins & dependencies (Boot, Lombok, web, test)
settings.gradle         modules: gateway, auth-service, user-service, listing-service
Dockerfile              one image recipe for all services (--build-arg SERVICE=…)
docker/maven-mirror.init.gradle   Docker builds resolve from Google's Maven Central mirror
docker/redis/           Redis users (users.acl) and their explanation
docker-compose.yml      kafka, redis, 3 databases, gateway, 3 services
<service>/src/main/resources/db/migration/   Flyway migrations
```

### Docker image

- **Build stage:** JDK 21 runs `./gradlew :<service>:bootJar`.
- **Downloads:** dependencies come from Google's Maven Central mirror first. On some networks `repo.maven.apache.org` randomly returns 403, and Gradle doesn't retry those.
- **Build cache:** the Gradle cache lives in a BuildKit cache mount, shared by all service images and kept between builds. It's locked, so parallel builds take turns on that step; Gradle's own locking doesn't work across separate build containers. This needs `docker buildx`.
- **Runtime stage:** a JRE 21 image running the jar as a non-root user.

### Startup order (compose)

`kafka` (health: broker API), `redis` (health: `PING` as `auth-service`) and the three
databases (health: `pg_isready`) start first. auth-service waits for healthy Kafka, Redis and
its database; user-service for Kafka and its database; listing-service
waits for its database. The gateway starts after the services. Flyway migrates each database
when its service starts.

### Commands

```bash
docker compose up -d --build     # build and start everything
docker compose logs -f auth-service user-service listing-service
docker compose down              # stop, keep data
docker compose down -v           # stop and wipe all databases
./gradlew test                   # all tests; DB tests need Docker
```

### Tests (65)

| Test | Kind | Covers |
|---|---|---|
| `AuthControllerTests` | Web slice, mocked service | request validation, status codes, JSON shape; logout passes the verified token on; `logout-all` needs a valid token |
| `AuthFlowIntegrationTests` | Full app + Postgres and Redis (Testcontainers) | register → login by username/email → token verified with the **public JWKS key only** → refresh rotation → reuse detection → logout; duplicate checks ignoring case; logout blacklists the `jti` with the right expiry; `logout-all` ends every session, new logins get the new `tv`, a revoked token can't call it again; reuse detection raises `tv` |
| `AuthServiceRevocationTests` | Unit, mocks | Redis down: refresh tokens revoked first, then `503` |
| `AccessTokenClaimsTests` | Unit | reading `jti`/`sub`/`tv` from a Bearer token; anything unreadable is ignored |
| `RedisAvailabilityTests` | Unit, fake clock | Redis skipped for 5 s after a failure, back after success |
| `GatewayRedisIntegrationTests` | Full gateway + Redis (Testcontainers) | logged-out token and older `tv` rejected with `401` + CORS headers, current tokens pass, rate-limit buckets stored in Redis |
| `UserControllerTests` | Web slice + security config | public vs `/me` access, lookup by id, token required, validation |
| `UserRepositoryTests` | JDBC slice + Postgres (Testcontainers) | migrations, upsert is safe to repeat and keeps edited fields, lookup ignoring case, DB phone constraint |
| `ListingControllerTests` | Web slice + security config | public search, which endpoints need a token, listing and search validation |
| `InMemoryRateLimiterTests` | Unit, fake clock | allowance, gradual refill, `Retry-After`, separate keys, idle clients forgotten |
| `RateLimitFilterTests` | Filter unit | `429` body and headers, per-IP counting, method and path matching |
| `GatewayFiltersTests` | Full gateway context, no services, Redis unreachable | CORS preflight allowed / refused, `429` (in-memory fallback) carries CORS headers, tokens pass while Redis is down |
| `ListingFlowIntegrationTests` | Full app + Postgres (Testcontainers) | create → edit (seller only) → archive → hidden from others; catalog id checks; every search filter, prefix search in English and Russian, all sorts, pagination; category counts; favorites and the per-user `favorite` flag |

The Testcontainers tests are skipped automatically when Docker isn't available.

---

## 10. Known limitations

| Area | Limitation | Usual fix |
|---|---|---|
| Events | Registration commits to the DB, then sends to Kafka without waiting. If Kafka is down, the account exists but no profile is created. | Transactional outbox table + relay |
| Keys | Private keys are stored unencrypted in `auth-db`. Keys are never rotated automatically. | Encrypt with a KMS/master key; scheduled rotation |
| Tokens | Revoked access tokens are rejected only by the gateway, and not while Redis is down (they still expire within 15 min). Expired refresh-token rows are never deleted. | Same check in services if they get other callers; cleanup job |
| Tokens | An expired or invalid token on a public endpoint (e.g. search) gets `401` instead of being ignored. The client should drop it or refresh first. | Treat bad tokens as anonymous on public endpoints |
| Listings | No moderation: listings go live immediately, with no draft, in-review or rejected states. | Moderation flow + `MODERATOR` role |
| Listings | Photos are URLs supplied by the client; nothing is uploaded, stored or checked. | Upload endpoint + object storage (S3/MinIO) |
| Listings | No view counts; seller name needs a separate call to user-service. | View counter; or copy seller names into listing-service from events |
| Gateway | Only auth endpoints are rate-limited. While Redis is down, counters are per instance and in memory. | Wider limits if needed |
| Identity changes | No endpoints to change username, email or password, so nothing publishes identity updates yet (user-service's upsert already handles them). | Add endpoints + `user.updated` event |
| Roles | `ADMIN` exists, but nothing assigns it and no endpoint requires it. | Admin tooling |
| Contracts | Event records are copied in each service and can drift apart without anyone noticing. | Shared contract module or schema registry |
| Infra | Single Kafka broker, replication factor 1, development DB passwords in compose. | Production config / secrets management |
| Health | No actuator health endpoints; the gateway waits for services to start, not to be ready. | `spring-boot-starter-actuator` + compose healthchecks |
