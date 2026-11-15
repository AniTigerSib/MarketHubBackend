# MarketHub Backend

Backend for a classifieds marketplace, built with Java 21, Spring Boot 4,
PostgreSQL, Kafka, and Redis. The Gradle project contains four applications:

| Application | Local port | Responsibility |
|---|---|---|
| `gateway` | 8080 | Public API, routing, CORS, rate limits, token revocation checks |
| `auth-service` | 8082 | Registration, login, access and refresh tokens |
| `user-service` | 8081 | Public profiles and account profile editing |
| `listing-service` | 8083 | Categories, cities, listings, search, favorites |

Use the gateway at **http://localhost:8080** for client requests. Each domain
service owns its own database; Flyway applies migrations when it starts.

## Choose a landscape

| Landscape | Applications | Infrastructure | OpenAPI |
|---|---|---|---|
| Docker, default | Docker Compose | Docker Compose | Disabled |
| Docker, development | Docker Compose with dev override | Docker Compose | Enabled |
| Local / IDE | Java processes on the host | Docker Compose | Optional `dev` profile |
| External infrastructure | Java processes or container images | Supplied separately | Disabled unless `dev` is enabled |

`dev` enables documentation; it does not select a different database or Redis
instance. The default and dev Docker landscapes share the same Compose project
and data volumes. There is no dedicated staging or production profile in this
repository.

## Prerequisites

- Docker Engine or Docker Desktop, with Docker Compose v2 and Buildx/BuildKit.
- Access to the Docker daemon for Compose and integration tests.
- JDK 21 for local Java processes and Gradle commands. Docker builds provide their
  own JDK, so host Java is optional for a fully containerized run.
- Network access on the first build to download Gradle, dependencies, and images.

Run commands from this repository's root. Use the included `./gradlew` wrapper;
a separate Gradle installation is unnecessary. On Windows, use `gradlew.bat`.

## Docker: development with Swagger UI

Build and start all applications and their dependencies:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

Open **http://localhost:8080/swagger-ui.html**, then select Auth, Users, or Listings.
Each service must finish starting before its definition and endpoints are available.

Inspect the landscape:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
docker compose -f docker-compose.yml -f docker-compose.dev.yml logs -f gateway auth-service user-service listing-service
curl -fsS http://localhost:8080/categories
```

Download the OpenAPI JSON definitions:

```bash
curl -fsS http://localhost:8080/openapi/auth -o auth.openapi.json
curl -fsS http://localhost:8080/openapi/users -o users.openapi.json
curl -fsS http://localhost:8080/openapi/listings -o listings.openapi.json
```

These are three separate definitions. For authenticated requests in Swagger UI,
log in through the Auth definition and paste the returned `accessToken` into
**Authorize**, without the `Bearer ` prefix. See [OpenAPI guidance](docs/OPENAPI.md).

Rebuild and recreate a changed application, keeping its dependencies running:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build --no-deps listing-service
```

Stop and remove the landscape while preserving PostgreSQL and Redis data:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
```

For a completely fresh database and Redis state, add `-v`. **This permanently
deletes the project's PostgreSQL and Redis volumes.**

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml down -v
```

## Docker: default configuration

Run the same applications with documentation disabled:

```bash
docker compose up -d --build
```

The API remains available at http://localhost:8080. Swagger UI and the service
document routes are disabled. To switch from dev to default, run this command
again; Compose recreates applications whose configuration changed.

```bash
docker compose ps
docker compose logs -f gateway auth-service user-service listing-service
docker compose down
```

Use `docker compose down -v` only when you intend to delete stored data.

## Local Java processes / IDE

Run infrastructure in Docker and Java applications on the host. If the full stack
is already running, stop its applications first to avoid duplicate Kafka consumers
and a gateway port conflict:

```bash
docker compose stop gateway auth-service user-service listing-service
docker compose up -d --wait kafka redis auth-db user-db listing-db
```

Start each application in a **separate terminal**:

```bash
./gradlew :auth-service:bootRun --args='--spring.profiles.active=dev'
```

```bash
./gradlew :user-service:bootRun --args='--spring.profiles.active=dev'
```

```bash
./gradlew :listing-service:bootRun --args='--spring.profiles.active=dev'
```

```bash
./gradlew :gateway:bootRun --args='--spring.profiles.active=dev'
```

Start auth-service first and the gateway last. Omit `--args` to use the default
configuration without documentation. For an IDE, import the Gradle project, use
JDK 21, and run these main classes with `SPRING_PROFILES_ACTIVE=dev`:

- `com.worfwint.markethub.auth.AuthServiceApplication`
- `com.worfwint.markethub.user.UserServiceApplication`
- `com.worfwint.markethub.listing.ListingServiceApplication`
- `com.worfwint.markethub.gateway.GatewayApplication`

The checked-in application settings already target the host ports exposed by Compose:

| Dependency | Host address | Database | Development username / password |
|---|---|---|---|
| Auth PostgreSQL | `localhost:5434` | `auth` | `auth_service` / `auth_service` |
| User PostgreSQL | `localhost:5433` | `users` | `user_service` / `user_service` |
| Listing PostgreSQL | `localhost:5435` | `listings` | `listing_service` / `listing_service` |
| Kafka | `localhost:9092` | — | — |
| Redis | `localhost:6379` | — | Per-application ACL accounts |

Redis credentials are `auth-service` / `auth_service`, `gateway` / `gateway`, and
`listing-service` / `listing_service`. See [Redis permissions](docker/redis/README.md).

Stop local Java applications with Ctrl+C or the IDE's stop action, then use
`docker compose down` to stop infrastructure and retain data.

## External infrastructure / deployment configuration

Build application jars:

```bash
./gradlew :auth-service:bootJar :user-service:bootJar :listing-service:bootJar :gateway:bootJar
```

The executable artifacts are in each module's `build/libs/` directory. Supply
configuration per application through environment variables, then run its jar.
For example, with infrastructure reachable from the host:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5435/listings \
SPRING_DATASOURCE_USERNAME=listing_service \
SPRING_DATASOURCE_PASSWORD=listing_service \
SPRING_DATA_REDIS_HOST=localhost \
SPRING_DATA_REDIS_USERNAME=listing-service \
SPRING_DATA_REDIS_PASSWORD=listing_service \
JWK_SET_URI=http://localhost:8082/.well-known/jwks.json \
java -jar listing-service/build/libs/listing-service-0.0.1-SNAPSHOT.jar
```

The shared Dockerfile can also build each application independently:

```bash
docker build --build-arg SERVICE=listing-service -t markethub/listing-service:local .
```

Use an application module name as `SERVICE`. Configure these variables for your
actual infrastructure; inside a container, `localhost` refers to that container.

| Variables | Applications | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Auth, user, listing | Each service's own PostgreSQL database |
| `KAFKA_BOOTSTRAP_SERVERS` | Auth, user | Kafka broker addresses reachable from the application |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_USERNAME`, `SPRING_DATA_REDIS_PASSWORD` | Auth, listing, gateway | Redis connection and application ACL account |
| `JWK_SET_URI` | User, listing | Auth-service's internal `/.well-known/jwks.json` URL |
| `USER_SERVICE_URL`, `AUTH_SERVICE_URL`, `LISTING_SERVICE_URL` | Gateway | Reachable backend URLs |
| `CORS_ALLOWED_ORIGINS` | Gateway | Comma-separated exact frontend origins |
| `SERVER_PORT` | All | Application listening port |
| `SPRING_PROFILES_ACTIVE` | All | Set to `dev` to enable documentation |

For the provided Docker landscapes, service connection variables are already set
in Compose. To use a different frontend origin, for example:

```bash
CORS_ALLOWED_ORIGINS=http://localhost:3000 \
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

The checked-in Compose files are for local development: they publish infrastructure
ports and use development credentials. A production deployment needs its own secrets,
network access controls, TLS, backups, and availability configuration. There is no
production deployment manifest supplied here.

## Tests and validation

```bash
./gradlew test
```

Tests use isolated PostgreSQL and Redis containers; the Compose landscape does not
need to be running. Docker integration tests automatically skip when Docker is
unavailable, so check the skipped counts before considering integration verified.
HTML reports are generated in each module's `build/reports/tests/test/index.html`.

Run only listing-service tests, or force the entire suite to run again:

```bash
./gradlew :listing-service:test
./gradlew test --rerun-tasks
```

Validate Compose configuration without starting services:

```bash
docker compose config --quiet
docker compose -f docker-compose.yml -f docker-compose.dev.yml config --quiet
```

## Troubleshooting

- **An endpoint fails just after startup:** Compose waits for infrastructure
  healthchecks, but application containers do not have readiness checks. Inspect
  the relevant service logs and retry after startup completes. Profiles are
  created asynchronously from Kafka registration events.
- **Swagger UI returns 404:** use the dev override, or enable the `dev` Spring
  profile on all four applications. Run client requests through the gateway.
- **Frontend requests fail CORS checks:** configure `CORS_ALLOWED_ORIGINS` on the
  gateway with the exact frontend origin. Defaults are `http://localhost:5173`
  and `http://localhost:4173`.
- **Category counts look stale:** the category response is cached in Redis for
  60 seconds. The next request after expiry recalculates it. Redis failures fall
  back to PostgreSQL; cache hits do not extend expiry.
- **Redis denies listing-service access after an update:** restart Redis to load
  the mounted ACL file: `docker compose restart redis`.
- **Docker fails to create `veth` interfaces:** on a host recently updated to a
  new kernel, compare `uname -r` with the installed modules under `/lib/modules`.
  Reboot into the installed kernel if the running version has no matching modules.

See [architecture and API behavior](docs/ARCHITECTURE.md) for authentication flows,
data ownership, database schemas, and known limitations.
