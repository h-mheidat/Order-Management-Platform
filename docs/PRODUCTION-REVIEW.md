# Production Review

Stage 19. What has been hardened, and — more usefully — what has **not**. A checklist that only lists
what was done is a marketing document; the second half is the one worth reading before a deploy.

## Hardened

| Area | What changed |
|---|---|
| Actuator exposure | `application-prod.yml` exposes only `health` and `prometheus`, on a **separate port** (8081). `env`, `configprops`, `beans`, `loggers`, `heapdump`, `threaddump` and `metrics` all return 401 — verified. |
| Health detail | `show-details: when-authorized` in prod. The dev default (`always`) publishes every dependency's hostname and error text to anyone. |
| Actuator auth | `ActuatorSecurityConfig` gives the actuator its own filter chain — infrastructure cannot present a bearer token, and the API chain correctly ends in `denyAll()`. |
| Schema | Flyway owns it, `ddl-auto: validate`, `clean-disabled: true`, `validate-on-migrate: true`. Startup fails on drift rather than "fixing" it. |
| Secrets | Nothing committed. `JWT_SECRET` has no default, so the app refuses to start without it. `.env` is gitignored and excluded from the Docker build context. |
| Container | Non-root user, JRE not JDK, layered image, `MaxRAMPercentage` instead of a fixed heap, `ExitOnOutOfMemoryError`, `exec` so the JVM is PID 1 and receives SIGTERM. |
| Shutdown | `server.shutdown: graceful` — in-flight requests finish instead of erroring on every deploy. |
| Errors | No stack traces, messages or binding errors in responses. Constraint names and SQL never leave the process. |
| Logging | Root at WARN, `org.hibernate.SQL` and parameter binding at WARN — at DEBUG they log every statement and every bound value, including password hashes. |
| Tracing | Sampled at 10% in prod. 100% costs throughput and storage for no extra insight. |
| Pool | Hikari sized from env, with `leak-detection-threshold` so a leak is found in a log rather than an incident. |

## Not production-ready — deliberately out of scope

These are real gaps. Each one is a decision that needs a person, not a config value.

1. **Single Kafka broker, replication factor 1.** One disk failure loses every unpublished event
   permanently. Production needs 3 brokers, `replication.factor=3`, `min.insync.replicas=2` — and
   `acks=all` only actually means anything once there is more than one replica to be in sync with.
2. **Symmetric JWT signing (HS256).** Every holder of the verification key can also mint tokens. Fine
   for one application; the moment a second service validates these tokens, issuing must move to an
   RS256 private key with the others verifying via public key or JWKS.
3. **No token revocation.** A stolen token is valid until it expires. The 15-minute lifetime is the
   only mitigation. Real revocation needs a deny-list checked per request, or short access tokens plus
   refresh tokens with server-side state.
4. **No rate limiting anywhere.** `POST /api/auth/login` can be brute-forced as fast as the network
   allows, and there is no per-account lockout. This is the most serious gap on the list.
5. **`processed_events` and `outbox_events` grow forever.** Both need a retention job — published
   outbox rows older than a few days, processed events older than the maximum plausible redelivery
   window. Without it the partial index and the idempotency lookups degrade over months.
6. **No database backups, PITR, or restore rehearsal.** A backup that has never been restored is a
   hypothesis.
7. **No TLS.** Everything here is plaintext: HTTP, PostgreSQL, Redis, Kafka. Terminating TLS at an
   ingress covers the first; the other three need `sslmode=require`, Redis TLS and `SASL_SSL`.
8. **Redis has no eviction policy or memory limit.** Under memory pressure it will start refusing
   writes; the `CacheErrorHandler` degrades gracefully, so this fails quietly rather than loudly.
   `maxmemory` with `allkeys-lru` is the fix.
9. **Statistics endpoint scans the whole orders table.** `aggregateByStatus` is a full `GROUP BY` with
   no time bound. At a million orders it is a slow query on an ADMIN dashboard nobody has load-tested.
   Needs either a date range parameter or a materialised rollup.
10. **`ProcessedEvent` insert races are handled but not measured.** The `orders.events.duplicates`
    counter exists; nothing alerts on it.
11. **No authorization audit trail.** Who changed which order to which status is in the application log
    and nowhere else. Financial records usually need better than a log line.
12. **Image is 481 MB.** Mostly the Alpine JRE. A jlink'd custom runtime or a distroless base would cut
    it substantially; not worth doing before there is a reason to care.

## Alerts worth having on day one

Metrics exist for all of these; none of them are wired to anything yet.

- `orders_outbox_pending` growing steadily → the publisher is stuck or the broker is unreachable. **This
  is the most important alert in the system**, because order creation succeeds either way: nothing
  fails, no error rate moves, and consumers silently stop hearing about orders.
- `orders_outbox_failed > 0` → events parked after exhausting retries. They will never be delivered on
  their own.
- `orders_events_duplicates_total` spiking → rebalances or publisher retries.
- `resilience4j_circuitbreaker_state{name="productService"}` open → order creation is failing.
- `http_server_requests_seconds` p99, plus 5xx rate.
- Hikari `hikari_connections_pending > 0` → the pool is the bottleneck.
