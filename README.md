# Order Management Platform

Training project following [`order-management-platform-training.md`](order-management-platform-training.md).
A modular monolith for order management: JWT auth with roles, JPA/PostgreSQL, Redis cache, Kafka with
the outbox pattern, WebClient + Resilience4j, Testcontainers, Actuator.

**Current stage: 01 — Project Setup.** Infrastructure and configuration only. No entities, no
authentication flow, no business logic yet.

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

## Notes for the next stage

- The schema is owned by **Flyway** (`src/main/resources/db/migration`), not by Hibernate.
  `spring.jpa.hibernate.ddl-auto` is `none` and becomes `validate` once stage 2 adds entities.
- `SecurityConfig` currently ends in `anyRequest().denyAll()`. Every endpoint added from stage 3
  onward must be opened explicitly.
- `management.endpoint.health.show-details: always` is **development only** and must be tightened
  before any deployment (stage 19).
