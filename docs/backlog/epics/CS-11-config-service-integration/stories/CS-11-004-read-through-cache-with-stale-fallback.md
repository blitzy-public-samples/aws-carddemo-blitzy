# CS-11-004: Read-through cache with stale fallback

Parent epic: [CS-11 Config Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want recent data served from cache and kept usable during an upstream outage, so that a short outage shows stale data instead of an error.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Directive: read-through cache
>
> Account-data access path: No
>
> External integration or SLA-bound: Yes
>
> Anchor basis: Use Cases · COTRN00C fills a fixed ten-row page set inside the program (PROCESS-PAGE-FORWARD); that screen behaviour is what this epic turns into configured page size and call settings. This story supplies the read-through cache that holds the configuration snapshot mirror and every other cached upstream answer under the TRD cache rules.
>
> Capability: one read-through cache is the service's only path to Redis. A caller passes a key family, the resource identifiers and an upstream read. The cache returns a fresh entry with no upstream call; otherwise it invokes the upstream read once, writes the answer back, and during an upstream outage returns an entry still within its family's max stale age. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/support/ReadThroughCache.java`:
>   - Nested record `KeyFamily` holds the family name, the fresh TTL, the max stale age and a value codec that turns the value into bytes and back. The name matches `^[a-z][a-z0-9-]*$`. The max stale age is a duration no shorter than the fresh TTL, or absent for "no stale limit" (the configuration snapshot mirror `cs:v1:config`). A max stale age equal to the fresh TTL means no stale window (the transaction-list page). A fresh TTL of zero or less, a max stale age below the fresh TTL, or a name outside the pattern is rejected with an `IllegalArgumentException` when the family is built.
>   - The key is `cs:v1:<family>` followed by `:<identifier>` for each resource identifier, for example `cs:v1:config` with none. An identifier is non-empty and holds no colon and no whitespace. The key never carries a subject, token or role.
>   - `read(family, identifiers, upstreamRead)` takes the upstream read as a `java.util.function.Supplier` that signals failure with `io.grpc.StatusRuntimeException`, and returns the nested record `CachedValue` holding the value, its `cachedAt` and a freshness of `FRESH` or `STALE`. `dataStatus` and `asOf` resolution stays with CS-05-005.
>
> > SCOPE-QUESTION (Resolved): The read also takes the time its caller has left: `read(family, identifiers, upstreamRead, timeLeft)` replaces the three-argument form, with `timeLeft` a `java.util.function.Supplier<java.time.Duration>` that the cache asks once before each Redis command it sends. Every caller supplies it: a request-path client passes the remaining budget of its request's `RequestDeadline` (CS-11-003), and the Config refresh (CS-11-006) passes the remaining budget of its own refresh deadline. Both types come from the JDK, so this story gains no dependency on CS-11-003.
>
>   - Age is the injected `java.time.Clock` time minus `cachedAt`. Age below the fresh TTL is fresh. Age at or above the fresh TTL and below the max stale age, or any age with no stale limit, is stale and usable for fallback. Age at or above the max stale age counts as no entry.
>   - A fresh entry is returned with no upstream call. A miss or a stale entry invokes the upstream read once. On success the entry is written with `cachedAt` equal to the clock time when the read returned and a Redis expiry equal to the family's max stale age (no expiry with no stale limit), so a stale entry stays readable for fallback until then, and the value is returned as `FRESH`. On `UNAVAILABLE` or `DEADLINE_EXCEEDED` with a usable stale entry, the held value is returned as `STALE` with its original `cachedAt` and nothing is written. Every other outcome rethrows the same exception.
>
> > SCOPE-QUESTION (Resolved): Each Redis command the read sends, the entry read before the upstream read and the write-back after it, waits at most the lesser of `ReadThroughCache.COMMAND_WAIT` and the time left that `timeLeft` reports when the command is sent. `COMMAND_WAIT` is a fixed `java.time.Duration` of 50 ms, a constant of this class and never an environment variable or configuration entry. 50 ms is many times a healthy in-region Redis answer, so it never cuts a working cache short, while a stalled read costs a cold-path stage 3, which has about 300 ms, at most 50 ms of it, and costs the Config refresh at most 50 ms before `GetConfig`.
>
> > SCOPE-QUESTION (Resolved): "It never cuts a working cache short" is an assumption, not a guarantee: a healthy in-region Redis is expected to answer well inside 50 ms, but a working cache can answer slower under load, thread scheduling or network delay. The bound accepts that trade-off. An entry read that a working cache answers after 50 ms ends at the wait and counts as a miss, never as a request failure: the upstream read runs once, so a fresh entry costs one extra upstream call and its latency, and during an upstream outage a stale entry missed this way gives no fallback, so the upstream status propagates. A write-back answered after its wait is abandoned and logged as described below, so a later read can find no fresh entry and go upstream again. `ReadThroughCache.COMMAND_WAIT` stays a fixed 50 ms constant, never an environment variable or configuration entry. The first performance run settles that a healthy cache answers inside the 50 ms wait limit under load.
>
>   - A Redis command failure (any Spring `DataAccessException`) or an entry that does not decode counts as a miss on read. A failed write-back is logged at warning level with the family, the key and the failure class, never the value, and the read still returns.
>
> > SCOPE-QUESTION (Resolved): A wait that expires is a command failure of the same kind: the store cancels the command and throws Spring's `QueryTimeoutException`, a `DataAccessException`. An expired entry read therefore counts as a miss and the upstream read is invoked once, and an expired write-back is logged at warning level as abandoned with the family, the key and the failure class, never the value, while the read still returns its value as `FRESH`. When `timeLeft` reports zero or less, no command is sent: the entry read counts as a miss, and the write-back is skipped and logged at warning level with the family and the key, never the value.
>
>   - The cache adds no attempt, timeout or retry of its own: each client story supplies an upstream read already wrapped in the upstream call policy (CS-11-003).
>
> > SCOPE-QUESTION (Resolved): The cache still adds no upstream attempt, timeout or retry: it puts no timer on the upstream read, and its only time limits are the bounded waits of its own Redis commands, which never shorten, repeat or cancel the upstream read.
>
>   - Nested interface `EntryStore` (read bytes for a key, write bytes for a key with an optional expiry) is the cache's one seam to Redis. `RedisConfiguration` supplies the Redis-backed store, and the unit tests supply an in-memory one.
>
> > SCOPE-QUESTION (Resolved): The `EntryStore` read and write each also take the wait the cache computed, a `java.time.Duration` above zero, and return or throw within it: when the wait expires the store cancels the command and throws `QueryTimeoutException`. The unit tests add a third in-memory store that records each command with its wait and never answers, so each command ends with that exception once its wait has elapsed.
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/support/CacheEntry.java` is the envelope record holding the value bytes and `cachedAt`. It encodes as an 8-byte big-endian count of microseconds since the epoch followed by the value bytes, so `cachedAt` keeps the microsecond precision the contracts' RFC 3339 timestamps carry. Decoding input shorter than 8 bytes fails.
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/RedisConfiguration.java`:
>   - Builds a `LettuceConnectionFactory` from the `card-servicing.redis` group that CS-11-001 binds from `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` and `REDIS_SSL_ENABLED`: host and port as bound, the password set only when non-empty, and `useSsl()` only when the TLS flag is true. Azure Managed Redis uses port `10000` with `REDIS_SSL_ENABLED=true`.
>
> > SCOPE-QUESTION (Resolved): The Lettuce client configuration also sets the command timeout to `ReadThroughCache.COMMAND_WAIT` (50 ms), with Lettuce `TimeoutOptions` enabled so that timeout also covers asynchronous commands, and a socket connect timeout equal to the CS-11-001 bound value of `REQUEST_DEADLINE_MS` (`card-servicing.upstream.request-deadline-ms`, 900 ms by default), in place of the client library's 60 s command and 10 s connect defaults. Both are backstops behind the store's own wait: no command outlives the wait limit, and no connection attempt outlives one request budget. No environment variable is added.
>
>   - Builds a `RedisTemplate<String, byte[]>` with a string key serializer and a raw byte value serializer, which writes the `CacheEntry` encoding.
>   - Builds the Redis-backed `EntryStore`, which rejects any key that does not start with `cs:v1:` with an `IllegalArgumentException` before sending a Redis command, and the `ReadThroughCache` bean on `Clock.systemUTC()`.
>
> > SCOPE-QUESTION (Resolved): The Redis-backed store sends each command as a Lettuce asynchronous command on the connection factory's shared connection, with the same key and value encoding as the template, and waits for the answer at most the given wait, cancelling the command when the wait expires. It opens that shared connection on one daemon connection thread of its own, shut down when the application context closes, and never on a caller's thread: a command that finds the connection still opening waits for it within the same wait, and a failed opening surfaces as `RedisConnectionFailureException`, after which the next command starts a new opening. A connection that drops later reconnects in the background, and every command sent meanwhile still ends at its wait.
>
> - `card-servicing-service/build.gradle` adds `implementation 'org.springframework.boot:spring-boot-starter-data-redis'` with no version, managed by the Spring Boot 3.5.7 BOM, which brings Spring Data Redis and Lettuce. `gradle/libs.versions.toml` is not edited.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/support/ReadThroughCacheTest.java` covers fresh, miss, stale refresh, stale fallback, beyond max stale age, non-fallback statuses, Redis down, undecodable entries, key rules, family shapes, the single upstream call and the deadline case. It uses an in-memory `EntryStore` that records each command and its expiry, a failing `EntryStore` that throws `RedisConnectionFailureException`, an adjustable clock, and a fake upstream read that counts its invocations and returns a value or throws a chosen status.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/support/CacheEntryTest.java` covers the envelope round trip with `cachedAt` and the short-input failure.
> - `card-servicing-service/src/test/java/com/example/cardservicing/config/RedisConfigurationTest.java` covers the TLS flag, host, port and password of the built connection factory and the key-prefix rejection, without opening a connection.
> - Together these are the 7 files touched.
>
> In scope:
>
> - The 7 files above.
> - The read-through cache wrapper with its freshness, stale-fallback and Redis-failure rules.
> - The entry envelope with `cachedAt`.
> - The Redis connection, with TLS when enabled.
> - The Spring Data Redis starter dependency line.
> - The `cs:v1:` key-prefix rule.
>
> Not in this story:
>
> - Per-client key families, their fresh TTL and max stale age values and their use: Card Data (CS-09-005), Transaction Activity (CS-10-006), the configuration snapshot mirror (CS-11-006) and Reference Data (CS-13-003).
> - The upstream call policy that wraps each upstream read (CS-11-003).
> - `dataStatus` and `asOf` resolution (CS-05-005).
> - Proof against a real `redis:7.4.2` container (CS-11-007).
> - Authorization before any cache read (CS-12-003).
> - The binding and defaults of the four Redis variables (CS-11-001).
>
> ### 2. Acceptance Criteria
>
> - AC1: Fresh hit: an entry whose age is below its family's fresh TTL is returned as `FRESH` with its stored `cachedAt`, the upstream read is invoked 0 times and no Redis write is made.
> - AC2: Miss: with no entry, the upstream read is invoked exactly once; its value is returned as `FRESH` and written under its `cs:v1:` key with `cachedAt` equal to the clock time when the read returned and a Redis expiry equal to the family's max stale age.
> - AC3: Stale refresh: an entry whose age is at or above the fresh TTL and below the max stale age, with the upstream read succeeding, causes exactly one upstream read; the new value is returned as `FRESH` and the entry is rewritten with the new `cachedAt` and a renewed expiry.
> - AC4: Stale fallback (degrade): with an entry whose age is below the max stale age and the upstream read throwing `UNAVAILABLE` or `DEADLINE_EXCEEDED`, the cached value is returned flagged `STALE` with its original `cachedAt` and the entry is not rewritten. This is the degrade path that account and category-balance reads use within their 300 s max stale age.
> - AC5: Fail-hard beyond max stale age: with no entry, or an entry whose age is at or above the max stale age, an upstream `UNAVAILABLE` or `DEADLINE_EXCEEDED` propagates as the same exception with the same status, which the standard error mapping turns into 503 or 504; no cached value is returned.
> - AC6: A `NOT_FOUND` from the upstream read propagates unchanged (404), and so do `INVALID_ARGUMENT` and every status other than `UNAVAILABLE` and `DEADLINE_EXCEEDED`; none of them falls back to a stale entry, and no entry is written.
> - AC7: Redis down: with every Redis command failing, a read is treated as a miss and the upstream value is returned as `FRESH`; the failed write-back is logged with the family and key but without the value and does not fail the read; with Redis down and the upstream read failing, the upstream status propagates. An entry that does not decode is treated as a miss in the same way.
>
> > SCOPE-QUESTION (Resolved): A Redis that accepts commands and never answers is covered the same way, with the in-memory store that records each command with its wait and never answers. With 900 ms time left, the read records a 50 ms wait on its entry read, counts it as a miss, invokes the upstream read exactly once and returns its value as `FRESH`; the write-back records a 50 ms wait and is logged as abandoned with the family, the key and the failure class but without the value; and the call returns before the 900 ms time left has run out. With 30 ms time left, below the wait limit, the recorded entry-read wait is 30 ms. With no time left, the store records no command, the upstream read is invoked exactly once, and the skipped write-back is logged without the value.
>
> - AC8: Key contents: every written key is `cs:v1:` followed by the family name and the resource identifiers joined by colons. A read made while a test bearer token, subject and role sit in the request context writes a key containing none of them. A family name outside the pattern, an empty identifier and an identifier holding a colon are each rejected with an `IllegalArgumentException` before any Redis command, and the Redis-backed store rejects a key without the `cs:v1:` prefix the same way.
> - AC9: TLS: `REDIS_SSL_ENABLED=true` builds a Lettuce client configuration with TLS on and `false` builds it with TLS off; the host follows `REDIS_HOST` and the port follows `REDIS_PORT`, `10000` included; an empty `REDIS_PASSWORD` sets no password and a non-empty one sets exactly that value.
>
> > SCOPE-QUESTION (Resolved): The built connection factory's client configuration also carries a 50 ms command timeout with command timeouts enabled, and a socket connect timeout equal to the bound `REQUEST_DEADLINE_MS` value: 900 ms by default and 250 ms with `REQUEST_DEADLINE_MS=250` bound. `RedisConfigurationTest` checks both without opening a connection.
>
> - AC10: Timeout and retry: on a miss or stale entry the cache invokes the supplied upstream read exactly once and adds no attempt, timeout or retry of its own. The attempts inside that read are the call policy's: each attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget, with at most two retries (`UPSTREAM_MAX_RETRIES`) at 50 ms then 100 ms backoff on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only. A fake upstream read counting its invocations proves the single call.
>
> > SCOPE-QUESTION (Resolved): "No attempt, timeout or retry of its own" concerns the upstream read, which the cache wraps in no timer. The cache's own Redis commands each wait at most 50 ms or the time left, whichever is less, and never shorten or repeat the upstream read.
>
> > SCOPE-QUESTION (Resolved): AC10 names the call policy's retry cap, backoffs and retried statuses but not its retry-fit rule. Inside the read, a retry starts only when its backoff plus a full 300 ms attempt fits the budget left before the cut-off (CS-11-003), and the cache neither adds nor withholds a retry.
>
> - AC11: Deadline: when the upstream read throws `DEADLINE_EXCEEDED` because the 900 ms deadline (`REQUEST_DEADLINE_MS`) reached its cut-off, the cache returns the stale value or the failure with no further Redis or upstream call. The cut-off therefore stays at or before 900 ms (logged `cutoffMs` ≤ 900) with a client-measured response below 1,000 ms, which the integration suites (CS-11-007, CS-09-006, CS-10-007, CS-13-004) time end to end.
>
> > SCOPE-QUESTION (Resolved): The cache never waits past the time its caller has left: the entry read before the upstream read and the write-back after it each end by then, and once no time is left no Redis command is sent, so the cache's own waits never move the cut-off past 900 ms.
>
> - AC12: Family shapes: a family whose max stale age equals its fresh TTL never returns `STALE`, so an upstream `UNAVAILABLE` for an entry at or past its fresh TTL propagates; a family with no stale limit is written with no Redis expiry and falls back to its entry at any age; a family built with a max stale age below its fresh TTL, or with a fresh TTL of zero or less, is rejected with an `IllegalArgumentException`.
> - AC13: Envelope: encoding a `CacheEntry` and decoding the bytes returns the same value bytes and the same `cachedAt`, truncated to microseconds; decoding input shorter than 8 bytes fails with an `IllegalArgumentException`.
>
> ### 3. Dependencies
>
> - CS-11-001 — Redis connection variables.
>
> Risk: a slow but reachable Redis adds its latency to every read before the upstream call and consumes request budget up to the cut-off, because the cache sets no command time limit of its own.
>
> > SCOPE-QUESTION (Resolved): Settled by the cache's wait limit: each Redis command waits at most 50 ms or the time left, whichever is less, so a slow or unresponsive Redis costs a read at most 50 ms before the upstream call and 50 ms after it, never past the time left, and the cut-off stays at or before 900 ms.
>

## 4. Design

- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd)
- [Data model — entity relationship diagram](../../../../data-model.md#entity-relationship-diagram)
- [TRD — resilience, degradation table and cache rules](../../../../technical-requirements.md#resilience)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — security](../../../../technical-requirements.md#security)
- [TRD — environment variables](../../../../technical-requirements.md#environment-variables)

Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: fresh hit, miss, stale refresh, stale fallback, beyond max stale age, non-fallback statuses, Redis down and undecodable entries, key rules, family shapes, the single upstream call, the deadline case, the envelope round trip, the never-answering store with 900 ms, 30 ms and no time left, and the TLS, host, port, password, command timeout and connect timeout settings.
- Integration: Applies. This story adds no integration suite; the cache runs against a Testcontainers `redis:7.4.2` container in the Config integration suite (CS-11-007) and the client suites CS-09-006, CS-10-007 and CS-13-004, and the Config suite also pauses a cache container after its connection has opened, proving on a real cache that stops answering that the refresh applies its values no sooner than the mirror read's full wait and the abandoned write-back ends with the wait-expiry failure, while this story's never-answering store case asserts the 50 ms upper bound on each wait. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — the cache serves no request path of its own; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no UI interaction step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — an explicit read-through wrapper with an entry envelope.

Rationale: a stored cache time lets one wrapper tell fresh, stale and too-old entries apart.

- Rejected: Option B, framework cache annotations, which carry no stale fallback or cache time.

Shared by both options:

- Topology: one shared cache, reached only through this component, never by a stub.
- Naming: a versioned prefix, family name and resource identifiers form each key.
- Addressing: the cache connection settings supply host, port, password and the encryption flag.
- Isolation: keys carry no caller identity, and the access guard precedes every cache read.
- Fallback: an upstream failure with no usable entry reaches the caller unchanged.
- Provisioning: each client story supplies its families' fresh period and max stale age.
- Cut-over: the managed deployed cache needs only new connection settings.
- Failures: no failed upstream read is cached, and the connection caps every cache command at 50 ms.

### Design Option A

An explicit wrapper stores each upstream answer with its cache time and checks entry age against the family's fresh period and max stale age. Cache commands wait at most 50 ms, capped by the caller's time left.

How it behaves:

- A fresh entry returns with no upstream read.
- A miss or stale entry triggers one upstream read whose answer is stored until the max stale age.
- Only an unavailable or deadline-exceeded status falls back to a usable stale entry, flagged stale.
- Failed or unanswered cache reads and unreadable entries count as misses, and failed write-backs are logged without the answer.
- The cache connection opens on its own thread.
- A family with no stale window never serves stale, and one with no stale limit never expires.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Freshness from the stored cache time (vs one expiry only) | fresh and stale entries are told apart | each entry carries a cache time |
| 2 | Stale fallback in the wrapper (vs fallback in each client) | every client gets one fallback rule | the wrapper holds the fallback status list |
| 3 | Cache outage as a miss (vs surfacing the error) | an outage never fails a request | an outage sends every read upstream |
| 4 | 50 ms command wait capped by time left (vs the client library's 60-second default) | a stalled cache never overruns a caller's budget | a slower answer counts as a miss |

Effort and risk: one wrapper, envelope, connection setup and three test classes, with the risk in a slow cache sending reads upstream.

### Design Option B

Framework caching annotations wrap each client method, keyed by its arguments. Each cache name has one expiry, and answers are stored alone.

How it differs:

- An entry is served as fresh until it expires, and an outage after expiry returns the error.
- Cache errors reach the caller without a custom error handler.
- Annotations cannot receive the caller's time left, so cache commands take up to 50 ms and a connection opening up to 900 ms.
- Caller identity in a method argument reaches the key unless each client excludes it.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One expiry per cache name (vs fresh period and max stale age) | no envelope to store | no stale flag and no cache time |
| 2 | Annotations on client methods (vs explicit wrapper calls) | one marker per client method | fallback needs code in every client |
| 3 | Framework error handling (vs a miss on outage) | no custom error handler | a cache outage fails the request |

Effort and risk: the least code, with the risk in the missing stale fallback and cache time.

### Recommendation

- Option: Option A.
- Why: only a stored cache time lets one wrapper serve fresh entries, flag stale ones and fall back.
- Strongest argument against: a client that skips the explicit wrapper call bypasses the cache.
- Shared items that do not decide the choice: key naming, caller isolation, connection settings and per-family periods.
- Flip condition: a framework cache exposing entry age and serving expired entries on upstream failure.

## Design Validation

### Option A validation

Settled assumptions:

- The cache rules require a cache time per entry and a miss per cache failure.
- Max stale ages: 300 seconds for account and category-balance entries, the fresh period for transaction-list pages, and none for the configuration snapshot mirror.
- Each upstream read arrives wrapped in the upstream call policy.
- Card cross-references are never cached.
- Every caller can report its time left.

Open assumptions:

- Open → the client stories settle the per-family values the cache rules leave open.
- Open → the Config integration suite settles expiry and encryption on a real cache container, and the refresh delay and abandoned mirror write on a paused one.
- Open → the first performance run settles that a healthy cache answers within 50 ms under load.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a fresh entry skips the upstream read |
| AC2 | Holds | a miss stores one upstream answer with expiry at the max stale age |
| AC3 | Holds | a stale entry triggers one read and a renewed envelope |
| AC4 | Holds | either fallback status yields a usable stale entry |
| AC5 | Holds | with no usable entry the status passes through unchanged |
| AC6 | Holds | only the unavailable and deadline-exceeded statuses allow fallback |
| AC7 | Holds | each failed or unanswered command ends within its wait as a miss or logged write-back |
| AC8 | Holds | keys hold only the prefix, family name and identifiers |
| AC9 | Holds | the connection setup applies the encryption flag, address, password and both time limits |
| AC10 | Holds | the supplied read gets exactly one untimed call |
| AC11 | Holds | the wrapper adds no call after a deadline-exceeded status and no wait past the time left |
| AC12 | Holds | the max stale age, equal to the fresh period or absent, sets the stale window |
| AC13 | Holds | the envelope round trip keeps the answer and cache time |

### Option B validation

Settled assumptions:

- Option A's cache-rule, call-policy and cross-reference assumptions hold, with the same connection setup.

Open assumptions:

- Open → a custom error handler settles whether a cache outage stays a miss.
- Open → a key rule in each client settles that keys carry no caller identity.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | a hit returns the answer without its cache time |
| AC2 | Conditional | a miss stores the answer without its cache time |
| AC3 | Conditional | an expired entry is fetched again without stale state |
| AC4 | Breaks | the option has no stale fallback |
| AC5 | Conditional | status pass-through depends on error-free cache access |
| AC6 | Holds | a failed method call is neither cached nor replaced |
| AC7 | At risk | cache errors and expired waits reach the caller by default |
| AC8 | Conditional | key safety depends on a per-client key rule |
| AC9 | Holds | the connection setup matches Option A |
| AC10 | Holds | the framework makes one untimed method call per miss |
| AC11 | Conditional | a wait blind to the time left can spend 50 ms of the 100 ms after the cut-off |
| AC12 | Breaks | one expiry period cannot express fallback at any age |
| AC13 | Breaks | the option stores no envelope to read back |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-11-001 |
| Status | TBD |
| Estimated Files Touched | 7 |
| Issue Type | Story |
| Epic Name | Config Service Integration |
| Epic Link | CS-11 |
| Story Points | 5 |
| Priority | High |
| Components | TBD |
| Fix Version/s | TBD |
| Team | TBD |
| Assignee | TBD |
| Reporter | TBD |
| Labels | TBD |
| Sprint | TBD |
| Due Date | TBD |
| Environment | TBD |
| Persona | TBD |
| Estimated Effort | TBD |
