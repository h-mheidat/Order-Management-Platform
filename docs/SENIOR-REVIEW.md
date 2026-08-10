# Senior-Level Review

Stage 20. The questions the training document asks, answered against this codebase with file references
so each answer can be checked rather than taken on trust.

---

## Where is the transaction boundary?

Three different answers, because three different situations.

**Order creation** — [`OrderService.createOrder`](../src/main/java/com/example/orders/service/OrderService.java)
is deliberately **not** `@Transactional`. It calls the product service first, with no transaction open,
and then opens a short write-only transaction via `TransactionTemplate`.

The reason is the failure mode of doing it the obvious way. A network call inside a transaction holds a
pooled connection and its locks for the duration of that call. When the product service slows from 50 ms
to 2 s, every in-flight order creation holds a connection for 2 s; with a pool of 10, throughput
collapses at 5 orders/second and requests that only *read* orders start timing out because the pool is
empty. A slow dependency becomes a total outage.

`TransactionTemplate` rather than `@Transactional` for two reasons: the boundary is visible as a block of
code, and a `@Transactional` method called from another method of the same class is **not transactional
at all** — the Spring proxy is bypassed. That trap appears three more times in this codebase and is
called out at each one.

**Cancel and status change** — plain `@Transactional`. No external call inside, so the annotation is the
right tool.

**The consumer** — [`OrderEventConsumer.consume`](../src/main/java/com/example/orders/kafka/OrderEventConsumer.java)
is `@Transactional`, and that is the entire idempotency mechanism: the side effect and the
`processed_events` insert commit or roll back together.

---

## What happens if Kafka is down?

**Orders keep working.** That is the whole point of the outbox, and it is asserted as a test —
[`OrdersSurviveKafkaOutageIT`](../src/test/java/com/example/orders/kafka/OrdersSurviveKafkaOutageIT.java)
points Kafka at a closed port and requires `POST /api/orders` to return 201.

The order and its event commit to PostgreSQL in one local transaction.
[`OutboxPublisher`](../src/main/java/com/example/orders/kafka/OutboxPublisher.java) polls separately and
fails quietly; rows stay `PENDING` and are delivered when the broker returns. Events are delayed, never
lost.

Kafka is excluded from the readiness probe for the same reason: if order creation works, there is no
reason to pull the instance out of the load balancer. It still shows `DOWN` on the root health endpoint,
which is where a human should see it.

**What breaks:** consumers stop hearing about orders, and `orders_outbox_pending` grows. Nothing else.
That metric is the most important alert in the system precisely because the failure is otherwise
invisible.

---

## What happens if Redis is unavailable?

**Requests still succeed, slower.** Also a test —
[`CacheDegradationIT`](../src/test/java/com/example/orders/cache/CacheDegradationIT.java).

The `CacheErrorHandler` in [`CacheConfig`](../src/main/java/com/example/orders/cache/CacheConfig.java)
logs and swallows cache failures so the call falls through to PostgreSQL. Without it, Spring propagates
connection errors and every cached read becomes a 500 — the cache would make the system *less* available
than before it existed, which is the opposite of the point.

Two real bugs were found here during implementation, both silent:

- The `CacheErrorHandler` was a plain `@Bean`, which Spring's cache infrastructure **ignores** — it only
  adopts one through `CachingConfigurer`. So the graceful degradation did not exist, and the symptom
  only appeared during the outage the handler was written for.
- `initialCacheNames()` re-registers each name with `cacheDefaults`, silently overwriting the per-cache
  config and falling back to `JdkSerializationRedisSerializer` — reintroducing exactly the
  deserialization exposure the typed serializer was chosen to avoid.

Redis timeouts are 500 ms, so an outage costs milliseconds per request rather than hanging a thread.

---

## Can this code produce N+1?

Not currently, and there is a test that fails if that changes —
[`QueryCountIT`](../src/test/java/com/example/orders/service/QueryCountIT.java).

Three specific defences:

1. `findWithItemsById` uses `@EntityGraph(attributePaths = "items")`, so an order and its items load in
   **one** query. Without it: 1 + 1.
2. List endpoints return `OrderSummaryResponse`, which has no items. Returning items in a list means
   either one query per order, or a `JOIN FETCH` with pagination — which Hibernate can only satisfy by
   loading every matching row and paginating in memory.
   `fail_on_pagination_over_collection_fetch` is enabled so that attempt fails loudly.
3. `OrderMapper` reads only `getCustomer().getId()`. On a lazy `@ManyToOne` proxy that does **not**
   trigger a query — the foreign key is already there. Touching any other customer property would add a
   query per order.

The test asserts the list endpoint issues the *same number of queries* for five orders and for ten. That
is the only assertion that actually defines "no N+1": an N+1 is not a correctness bug, every response is
right, so nothing about the output will ever fail.

`open-in-view` is disabled, so a missing fetch surfaces as `LazyInitializationException` in development
rather than as extra queries in production.

---

## Is this code thread-safe?

**Stateless singletons** — every service, controller, mapper and client holds only final collaborator
references. Nothing mutable is shared.

**The dangerous shared state is the entity.** A JPA entity is *not* thread-safe, but it is also never
shared: each transaction gets its own persistence context and its own instances.

**Deliberate concurrency handling:**

- `OrderService` — no instance state.
- `OrderCache.evictAfterCommit` — registers a `TransactionSynchronization`, which is per-thread by
  design.
- `CorrelationIdFilter` — writes MDC and removes it in a `finally` block, because request threads are
  pooled and a leftover value would be attributed to an unrelated request.
- `OutboxPublisher` — the interesting one. It runs on every instance simultaneously and is made safe by
  `FOR UPDATE SKIP LOCKED`, not by locking in Java.
- `ProductClient` — Reactor operators; `flatMap` concurrency is capped at 8.
- `KafkaHealthIndicator` — creates a short-lived `AdminClient` per check rather than sharing one, so a
  broker restart cannot leave it wedged on a stale connection.

**Where two threads genuinely collide:** two agents updating the same order. Handled by `@Version` — see
the next question.

---

## What happens if the same Kafka event arrives twice?

It is processed once, and there is a test that delivers an identical record twice and asserts a single
ledger row.

The mechanism, in order of importance:

1. **`processed_events.event_id` is the primary key.** That is the guarantee.
2. **The insert shares a transaction with the side effect.** So it is impossible to act on an event
   without recording it, or to record it without acting.
3. **`existsById` in front is only an optimisation.** A check-then-act *without* the constraint has a
   window where two consumers both see nothing and both proceed. With it, one insert wins and the
   loser's entire transaction — side effect included — rolls back.

Redelivery is normal, not exceptional: Kafka is at-least-once, and the publisher republishes any event
whose send succeeded but whose commit did not. Exactly-once delivery would require a transaction
spanning PostgreSQL and Kafka — the distributed transaction the outbox exists to avoid.

`eventId` is minted **once**, by the outbox row, so a republished event keeps the identity consumers
already recorded.

---

## How would you scale this to multiple instances?

Most of it already works. The parts that needed thought:

| Concern | How it is handled |
|---|---|
| Sessions | None. Stateless JWT, `SessionCreationPolicy.STATELESS`. Any instance can serve any request. |
| Outbox publisher | Runs on **all** instances. `FOR UPDATE SKIP LOCKED` gives each a disjoint batch. A leader-elected singleton would be simpler and stops publishing when that instance dies. |
| Cache | Redis is shared, so all instances see the same entries and one instance's eviction is visible to the rest. An in-process cache would serve stale data from the instances that did not handle the write. |
| Kafka consumers | One consumer group; partitions are distributed across instances. Three partitions caps useful parallelism at three consumers — that is why the topic is not created with one. |
| Ordering | Message key is the aggregate id, so all events for one order land on one partition and stay ordered. |
| Connection pool | The real constraint: instances × `maximum-pool-size` must stay under `max_connections`. An autoscaler that does not know this will exhaust PostgreSQL. |
| Scheduled work | Only the outbox poll, and it is concurrency-safe. Anything added later needs the same treatment or a lock. |
| Optimistic locking | Works across instances — it is enforced by the database, not in memory. |

**What would break first:** the database. Connection count, then write contention on `orders`. Read
replicas for the list and statistics endpoints would be the first move.

---

## Why did you choose this approach?

The decisions worth defending, and what each one costs:

**`SEQUENCE` over `IDENTITY`** — `IDENTITY` forces an insert per row to read the key back, which
disables JDBC batching. Cost: the sequence `INCREMENT BY` must stay equal to `allocationSize`, and a
mismatch causes duplicate-key failures under concurrency.

**Functional unique index on `lower(email)`** — a plain `UNIQUE(email)` would let `Ahmad@test.com` and
`ahmad@test.com` both register and leave login ambiguous. Cost: lookups must go through
`findByEmailIgnoreCase` to use the index.

**404, not 403, for another customer's order** — 403 confirms the order exists, which is exactly the
fact the caller is not entitled to. Cost: a genuinely confused legitimate user gets a less helpful
error.

**`denyAll()` as the final rule** — a forgotten endpoint fails shut. Cost: every new endpoint needs an
explicit rule, and the failure looks like a bug the first time.

**Fallback that fails instead of guessing a price** — an order priced from a placeholder is worse than
an order the customer is asked to place again. Placeholder fallbacks belong where data is advisory,
never where it becomes money in a database.

**Authorization applied *after* the cache** — caching the authorized result would let the second caller
inherit the first caller's permissions. Keying by caller instead would be correct and collapse the hit
rate. So the cache holds data and never holds a decision.

**Flyway from the very first migration** — starting on `ddl-auto: update` teaches a habit that must be
unlearned before production, and stage 2 would have had to retrofit a baseline anyway.

---

## Bugs found by the tests during this build

Worth recording, because each one would have shipped and each was silent.

1. **Resilience4j fallback on the wrong annotation.** Declared on `@CircuitBreaker`, it ran *inside*
   `@Retry` — the first 500 became a non-retryable `ExternalServiceException`, so retry gave up after
   one attempt while every annotation still looked correct. Caught by asserting the upstream **request
   count**, not the outcome.
2. **`CacheErrorHandler` as a plain `@Bean`.** Ignored by Spring; graceful degradation did not exist.
3. **`initialCacheNames()` overwriting the cache config.** Silently reverted to JDK serialization.
4. **Money scale inherited from upstream JSON.** `25.5`, `25.50` and `25.500` are equal in value with
   different scales, and that propagated into API responses.
5. **`commons-pool2` missing** while Lettuce pooling was enabled — startup failure, found on first run.
6. **A flaky security test.** It flipped the last base64url character of a JWT signature, but the final
   character of a 43-character base64url string carries only 2 significant bits, so the flip often
   decoded to the same MAC bytes and the token stayed valid. It passed by luck. Replaced with a payload
   rewrite that claims ADMIN.
7. **WireMock healthcheck broken by a bind mount** over the image's `WORKDIR`, which makes `docker exec`
   fail — the service worked perfectly while reporting unhealthy.
8. **Production hardening broke its own healthcheck.** `application-prod.yml` moves actuator to port
   8081; the compose healthcheck still probed 8080.
9. **`denyAll()` blocked the Prometheus scrape** — 401. Prometheus cannot present a bearer token, which
   is why the actuator needs its own filter chain.

Two of my own test assertions were also wrong in the same way: `objectMapper.readTree` parses a JSON
float into a `DoubleNode`, so `asText()` silently drops the trailing zero. Money is now asserted against
the raw response body.
