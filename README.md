# Order Management Platform

Training project following [`order-management-platform-training.md`](order-management-platform-training.md).
A modular monolith for order management: JWT auth with roles, JPA/PostgreSQL, Redis cache, Kafka with
the outbox pattern, WebClient + Resilience4j, Testcontainers, Actuator.

**All 20 roadmap stages complete.** 76 tests green (`14` unit + `62` integration over Testcontainers).

- [docs/SENIOR-REVIEW.md](docs/SENIOR-REVIEW.md) — the training document's senior questions, answered
  against this code, plus the nine real bugs the tests caught during the build.
- [docs/PRODUCTION-REVIEW.md](docs/PRODUCTION-REVIEW.md) — what is hardened, and the twelve things that
  are honestly **not** production-ready.

## Stack

| | |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.16 |
| Build | Maven (via `./mvnw`, no local Maven install needed) |
| Infrastructure | PostgreSQL 17, Redis 7.4, Kafka 4.0 (KRaft) — all via Docker Compose |
| External | Product Service, stubbed by WireMock as a genuinely separate container |

## Prerequisites

- Docker Desktop running
- JDK 21. Installed here via Homebrew:

```bash
brew install openjdk@21
```

It is keg-only, so point `JAVA_HOME` at it for every Maven command:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Add that line to `~/.zshrc` if you want it permanently.

## Running

1. Create your local environment file. `.env` is gitignored — no credential ever gets committed:

```bash
cp .env.example .env
```

2. Start the infrastructure:

```bash
docker compose up -d
```

Wait until all three services report `healthy`, not merely `running`:

```bash
docker compose ps
```

3. Start the application:

```bash
./mvnw spring-boot:run
```

4. Confirm it reached all three services:

```bash
curl -s localhost:8080/actuator/health | jq
```

`status` must be `UP`, with `db`, `redis` and `kafka` all `UP` under `components`.

### Everything in containers

```bash
docker compose --profile app up -d
```

The API is on `:8080`; actuator moves to `:8091` because the `prod` profile puts it on its own port.

### Optional — Kafka UI for inspecting topics and messages

```bash
docker compose --profile tools up -d
```

## API

Interactive docs while the app is running locally: **<http://localhost:8080/swagger-ui.html>**
(raw spec at `/v3/api-docs`, YAML at `/v3/api-docs.yaml`).

Register, log in, press **Authorize**, paste the token — then every endpoint is callable from the page.

Both are **disabled under the `prod` profile**, so they are not reachable from the containerised stack.
A complete map of the API surface is free reconnaissance, and Swagger UI is a live HTML app with its own
CVE history. Consumers should take the spec from CI as a build artefact, which is also the only way to
diff it between releases.

| Method | Path | Who |
|---|---|---|
| POST | `/api/auth/register` | public — always creates a CUSTOMER |
| POST | `/api/auth/login` | public — returns a bearer JWT |
| POST | `/api/orders` | CUSTOMER |
| GET | `/api/orders?status=&page=&size=` | CUSTOMER (own only) · SUPPORT/ADMIN (all) |
| GET | `/api/orders/{id}` | owner or staff — others get 404, not 403 |
| DELETE | `/api/orders/{id}` | owner or staff — cancels, never deletes |
| PATCH | `/api/orders/{id}/status` | SUPPORT · ADMIN |
| GET | `/api/admin/statistics` | ADMIN |

Every failure returns one shape:

```json
{ "timestamp": "...", "status": 404, "error": "ORDER_NOT_FOUND", "message": "Order 100 was not found" }
```

Clients branch on `error`, never on `message`.

## Seeing the resilience work

The product service is a real container, so it can be broken on purpose:

```bash
docker compose stop product-service
```

Order creation then returns 503 `PRODUCT_SERVICE_UNAVAILABLE`, and after five failures the circuit
breaker opens and stops calling upstream entirely. `GET /products/99` on the stub delays 5s to trip the
timeout; `/products/500` returns 500 to exercise retry.

```bash
docker compose stop redis
```

Orders still work — reads fall through to PostgreSQL. Root health goes `DOWN`, readiness stays `UP`.

```bash
docker compose stop kafka
```

Orders still work. Events accumulate as `PENDING` in `outbox_events` and drain when the broker returns.
Watch `orders_outbox_pending` climb at `:8091/actuator/prometheus`.

## Tests

Unit tests (fast, no Docker):

```bash
./mvnw test
```

Integration tests (`*IT`, requires Docker — runs Testcontainers for PostgreSQL, Redis and Kafka):

```bash
./mvnw verify
```

## Layout

```
src/main/java/com/example/orders/
├── config       Spring configuration and infrastructure beans
├── controller   HTTP entry points
├── service      Business logic and transaction boundaries
├── repository   Spring Data JPA repositories
├── entity       JPA entities
├── dto          API request/response payloads
├── security     Authentication and authorization
├── kafka        Producers, consumers, outbox publisher
├── cache        Redis cache configuration
├── exception    Domain exceptions and the error contract
└── mapper       Entity <-> DTO mapping
```

Each package carries a `package-info.java` documenting its responsibility.

## Health endpoints

| Endpoint | Audience | Includes |
|---|---|---|
| `/actuator/health` | humans, dashboards | everything — goes `DOWN` if any dependency is unhappy |
| `/actuator/health/readiness` | load balancer / orchestrator | `db` only |
| `/actuator/health/liveness` | orchestrator restart decisions | process state only |

Redis and Kafka are deliberately **not** in the readiness group. A cache outage has a fallback
(Postgres), and a broker outage is absorbed by the outbox — neither should pull an instance out of
the load balancer. They remain visible on the root endpoint.

## Data model

```
User 1 ──< Order 1 ──< OrderItem
```

Plus two infrastructure tables: `processed_events` (Kafka idempotency ledger, stage 12) and
`outbox_events` (outbox pattern, stage 19).

Decisions worth knowing before touching this code:

- **Flyway owns the schema.** `ddl-auto` is `validate`, so a mapping that disagrees with a migration
  fails at startup rather than on the first query. Never set it to `update`.
- **`SEQUENCE`, not `IDENTITY`.** `IDENTITY` forces an immediate insert to read the generated key
  back, which disables JDBC batching. The `INCREMENT BY 50` in each migration must stay equal to
  `allocationSize = 50` in the matching entity.
- **Every association is `LAZY`**, and `open-in-view` is off — so a missing `JOIN FETCH` shows up as
  a `LazyInitializationException` in development instead of an N+1 in production.
- **Money is `BigDecimal`** with `numeric(19,2)`; timestamps are `OffsetDateTime` with `timestamptz`.
- **Email uniqueness is case-insensitive**, enforced by a functional index on `lower(email)`.
  Look users up with `findByEmailIgnoreCase` so the query can use it.
- **Entity `hashCode()` is a per-class constant** and `equals()` compares the id only once assigned.
  A hash derived from a generated id changes at flush time, which breaks any `HashSet` the entity
  was already in.
- **Constraints are duplicated in the database**, not left to the service layer: order status and
  role check constraints, positive quantity, non-negative money, one line per product per order,
  and `PUBLISHED` outbox rows requiring a `published_at`.

## Conventions worth knowing before changing anything

- **`SecurityConfig` ends in `anyRequest().denyAll()`.** A new endpoint is unreachable until it is
  opened explicitly. That is intentional: a forgotten route fails shut.
- **The actuator has its own filter chain** (`ActuatorSecurityConfig`). Infrastructure cannot present a
  bearer token, so it cannot live under the API's rules.
- **Spring AOP proxies are bypassed by self-invocation.** `@Cacheable`, `@Transactional`, `@Retry` and
  `@CircuitBreaker` all do nothing when called from another method of the same class. That is why
  `OrderCache`, `ProductCatalog` and `ProductClient` are separate beans, and why order creation uses
  `TransactionTemplate`.
- **No network call inside a transaction.** Ever. See `OrderService.createOrder`.
- **Money is `BigDecimal` at scale 2**, normalised at the boundary in `OrderItem`.
- **`OrderStatus.canTransitionTo`** is the single definition of the legal lifecycle. Do not reimplement
  it in a service.
