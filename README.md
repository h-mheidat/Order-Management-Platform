# Order Management Platform

Training project following [`order-management-platform-training.md`](order-management-platform-training.md).
A modular monolith for order management: JWT auth with roles, JPA/PostgreSQL, Redis cache, Kafka with
the outbox pattern, WebClient + Resilience4j, Testcontainers, Actuator.

**Current stage: 02 — Database + Entities.** Schema and persistence model in place. No repositories,
services, controllers or authentication flow yet — those are stages 3 and 5.

## Stack

| | |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.16 |
| Build | Maven (via `./mvnw`, no local Maven install needed) |
| Infrastructure | PostgreSQL 17, Redis 7.4, Kafka 4.0 (KRaft) — all via Docker Compose |

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

Optional — Kafka UI on <http://localhost:8081> for inspecting topics and messages:

```bash
docker compose --profile tools up -d
```

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

## Notes for the next stage

- `SecurityConfig` currently ends in `anyRequest().denyAll()`. Every endpoint added from stage 3
  onward must be opened explicitly.
- `management.endpoint.health.show-details: always` is **development only** and must be tightened
  before any deployment (stage 19).
- `OrderStatus.canTransitionTo` already defines the legal lifecycle transitions — stage 5 and the
  SUPPORT status endpoint should use it rather than writing their own rules.
