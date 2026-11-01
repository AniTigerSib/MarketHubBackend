# Development API documentation

Documentation uses springdoc 3.0.3 (the Spring Boot 4.0-compatible line). It is disabled
by default and enabled with the `dev` Spring profile in all four applications.

## Start the development landscape

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

Open http://localhost:8080/swagger-ui.html. Select **Auth**, **Users**, or **Listings**
from the definition selector. No backend service ports need to be published.
The base compose file alone keeps documentation disabled.

For IDE launches, set `SPRING_PROFILES_ACTIVE=dev` on the gateway and all three
services (or use `--spring.profiles.active=dev` / `bootRun --args='--spring.profiles.active=dev'`).
The existing local PostgreSQL, Kafka, and Redis configuration still applies.
Use the same compose file arguments for logs and shutdown when using the dev override.

## Definitions

| Gateway URL | Definition |
|---|---|
| `/openapi/auth` | Registration, login, refresh, logout, logout everywhere |
| `/openapi/users` | Public profiles and the caller's profile |
| `/openapi/listings` | Listings, search, catalog, favorites |

Each service generates its own `/v3/api-docs` document in the dev profile. The gateway
proxies these documents; it does not merge their schemas. Its own `/v3/api-docs` is
not a combined backend definition. Schemas and validation constraints are generated
from the controllers and DTOs. Internal JWKS and error endpoints are excluded.
Every definition uses the relative server URL `/`, so **Try it out** calls the same
gateway origin from which Swagger UI was opened, including when using a remote dev host.

## Try authenticated requests

1. Select **Auth** and call `POST /auth/register`, then `POST /auth/login`.
2. Copy `accessToken` from the login response.
3. Select the desired definition and click **Authorize**. Paste the token alone,
   without the `Bearer ` prefix, into `bearerAuth`.
4. Execute a protected operation, such as `GET /users/me` or `POST /listings`.

Authorization is per definition; authorize again after switching definitions if needed.
Tokens are not persisted across page reloads. Refresh or log in again when the access
token expires. Logout takes a refresh token in its body; authorization is optional there
and also revokes the supplied access token. Logout everywhere requires authorization.
Public browsing needs no token, but sending an expired token still produces `401`.
Existing gateway rate limits apply to requests made through Swagger UI.

## Verification

```bash
./gradlew test
```

Tests cover dev UI configuration, documentation disabled by default, public access to
the user definition, generated auth requirements, and flattened listing search parameters.
Auth/listing integration tests require Docker and are skipped when Docker is unavailable.
