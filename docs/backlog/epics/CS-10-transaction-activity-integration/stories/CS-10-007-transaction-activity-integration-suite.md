# CS-10-007: Transaction Activity integration suite

Parent epic: [CS-10 Transaction Activity Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a delivery engineer, I want the Transaction Activity path proven against real containers, so that paging and failure behaviour are verified before endpoints rely on them.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-ID
>
> Directive: testing requirements (integration suite)
>
> Account-data access path: No
>
> External integration or SLA-bound: Yes
>
> Anchor basis: Data Layouts · CVTRA05Y · TRAN-ID X(16) is the key of every Transaction Activity read this suite drives: the single-transaction lookup reads one record by it, and the per-account listing orders and pages by it once the processed timestamps tie, as they do for every sample row. This story proves the CS-10-006 client's timeout, retry, fail-hard and stale behaviour, and the CS-10-004 and CS-10-005 stub over stored rows, on a real gRPC channel, a real PostgreSQL container and a real Redis container before the W2 endpoints rely on them.
>
> Capability: one `integration`-tagged suite, excluded from the `test` task and run by `./gradlew :card-servicing-service:integrationTest`, extends the shared container base, which starts `postgres:16.6` and `redis:7.4.2`. Before its first test it applies the schema scripts and loads the sample transactions through the CS-10-002 loader into the container database. It starts the Transaction Activity stub on a loopback port through `StubHarness`, reading those stored rows through its relational provider, and calls the CS-10-006 client under a `RequestDeadline`. Each test sets its own `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` overrides, and its cache keys and overrides are reset before it runs. Target paths:
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/transactionactivity/TransactionActivityIntegrationTest.java`, the suite:
>   - Extends `IntegrationTestSupport` (`card-servicing-service/src/test/java/com/example/cardservicing/support/IntegrationTestSupport.java`, CS-11-007), so it inherits `@Tag("integration")`, the `integration` profile and the base's `postgres:16.6` and `redis:7.4.2` containers, started once per test JVM, with the Redis container emptied by `FLUSHALL` before each test. It starts a `@SpringBootTest` context under that profile.
>   - Before the first test, it runs the CS-09-001 `FixtureLoader` job once under the `fixture-load` profile against the container database. The job's schema step (CS-13-001) applies the `db/` scripts in file-name order, `03-transaction-record.sql` of CS-10-001 included, and its registered entity loaders load the reference data, the Card Data sample and then the 300 sample transactions through `TransactionFixtureLoader` (CS-10-002, load order 6). The job then runs the synthetic volume loads of CS-09-007 and CS-10-008, which no setting switches off. Their rows sit in the disjoint account range from `10000000001` and under their own load-report entities, so no assertion of this suite, which reads only sample accounts and the `transaction_record` entry, reads them.
>   - Registers `StubHarness.of(...)` (`card-servicing-service/src/test/java/com/example/cardservicing/support/StubHarness.java`, CS-11-002) as a static extension over two registrations, each on a `127.0.0.1` loopback port inside the test JVM. The first is a test-local anonymous `StubRegistration` under stub name `transaction-activity`, which `StubHarness.allRegistered()` never discovers, serving the CS-10-004 and CS-10-005 `TransactionActivityStub` over its relational listing provider, nested in `TransactionMockDataProvider`, and its relational primary-key lookup source. A nested `@TestConfiguration` builds both over the context's JPA `EntityManager`, as the stub's container-mode configuration builds them, and the stub reaches them through the context on each call, so the harness starts it before the context binds its port. `TransactionActivityStubRegistration` (CS-10-004) is not used, because its in-memory provider never reads the store. The second is `ConfigStubRegistration` (CS-11-005) under stub name `config`, whose port the integration profile's Config client target needs when the context starts.
>   - A `@DynamicPropertySource` method supplies `integration.stub-ports.config` and sets `card-servicing.clients.transaction-activity.target` (CS-10-006) to `127.0.0.1:<transaction-activity port>`, so the context's `TransactionActivityClient` calls the stub over a real gRPC channel.
>   - Each scenario call is one test request: it starts a `RequestDeadline` (CS-11-003) from the bound `REQUEST_DEADLINE_MS` value, places a test `traceId` in the logging context, calls `transactions` or `transaction` with a bearer token built at runtime, and marks its cut-off when the call returns, so the completion line logs `cutoffMs`. The caller measures the time from the deadline's start to the outcome.
>   - The suite never calls `accessAllowed(token)`, so the configuration snapshot stays on its built-in values, a 300 ms per-attempt timeout (`UPSTREAM_TIMEOUT_MS`) and 2 retries (`UPSTREAM_MAX_RETRIES`), and the Config stub's request log stays empty.
>   - It ages a cache entry by rewriting the 8-byte big-endian `cachedAt` prefix of the CS-11-004 entry envelope through the base's Lettuce connection, keeping the value bytes the client wrote, because the CS-10-006 periods are fixed rules with no configuration entry.
>   - The cache-outage case sits in a `@Nested` class whose context binds `REDIS_HOST` and `REDIS_PORT` to one dedicated `redis:7.4.2` container that the class starts before its first case and stops after its last. The case pauses and unpauses only that container, so the base's shared containers keep running.
>   - It builds its token values at runtime, so the CS-11-001 committed-secret scan finds no literal credential.
> - `card-servicing-service/src/test/resources/transaction-activity-integration.yml`, the suite's test configuration, read from the classpath with SnakeYAML:
>   - The stub overrides of each scenario (`STUB_LATENCY_MS`, `STUB_FAILURE_RATE`), applied as per-test overrides through `inject("transaction-activity", latencyMs, failureRate)`: failure rate 1.0 for the stub-exception, fail-hard and stale cases; latency 400 ms for the timeout cases; latency 0 with failure rate 0 for the paging, lookup, not-found, invalid-token, cache-hit, cache-outage, schema and load cases.
>   - The cache key prefixes the suite inspects, `cs:v1:transaction:` and `cs:v1:transaction-page:`, and the entry ages it applies: 61 s for a list page, past its 60 s fresh TTL; 120 s for a detail entry, within its 300 s max stale age; and 301 s, past that age.
>   - The deadline values: the 900 ms request deadline each scenario starts, which the suite asserts equals the bound `REQUEST_DEADLINE_MS` value before the first test, the 900 ms ceiling on `cutoffMs` and the 1,000 ms ceiling on the caller-measured response.
> - Together these are the 2 files touched.
>
> Test approach: every duration assertion except the ceilings on `cutoffMs` and on the caller-measured response uses a lower bound only, because a loaded runner stretches measured durations. A request-log entry carries its order, outcome and duration but no receipt time, so attempt spacing is asserted through the caller-measured time. A case with injected latency ends only after every delayed call has reached the request log, so no late entry lands in the next test's log. Every count in section 2 refers to entries of the Transaction Activity stub's request log for one operation: `cardservicing.transactionactivity.v1.TransactionActivityService/ListTransactions` or `cardservicing.transactionactivity.v1.TransactionActivityService/GetTransaction`.
>
> In scope:
>
> - The 2 files above.
> - The suite and its test configuration.
> - The stub exception, the slow stub, the retry cap and the deadline bounds.
> - The fail-hard list read and the detail stale fallback with its expiry, from real Redis.
> - Paging and lookup against the container database.
> - The cache hit and the Redis-down behaviour.
> - The container-backed schema and load proofs that CS-10-001 and CS-10-002 hand to this suite.
>
> Not in this story:
>
> - The container base and the Integration CI stage (CS-11-007).
> - Production code: the table, the loader, the page token codec, the stub and the client (CS-10-001 to CS-10-006).
> - Endpoint-level deadline outcomes (CS-07-002, CS-08-002).
> - Performance percentiles (CS-03-003, CS-04-002) and the transaction volume (CS-10-008).
> - Stub hosting, failure injection and the request log (CS-11-002), the call policy and the request deadline (CS-11-003), and the read-through cache (CS-11-004).
>
> ### 2. Acceptance Criteria
>
> - AC1: The suite is tagged `integration`, runs under `./gradlew :card-servicing-service:integrationTest`, does not run under `./gradlew :card-servicing-service:test`, whose report under `card-servicing-service/build/test-results/test/` lists no `TransactionActivityIntegrationTest`, and starts `postgres:16.6` and `redis:7.4.2` through the shared integration test base.
> - AC2: Paging: listing account `00000000001` with page size 2 against the container database, each next page requested with the previous page's `next_page_token`, returns [`0000000776200014`, `0000000755736377`], then [`0000000624335286`, `0000000555363230`], then [`0000000532120892`, `0000000503557384`]. The first two pages carry a non-empty `next_page_token` and the last page an empty one, and each page comes from exactly 1 `ListTransactions` entry with outcome `OK`. Listing the same account with page size 20 and type filter `03` returns only `0000000532120892`, with amount `"-70.77"`.
> - AC3: Lookup: looking up `0000000776200014` on an empty cache returns it `FRESH` with amount `"985.22"`, card `9680294154603697` and both timestamps `"2022-06-10T19:27:53.000000Z"`, from exactly 1 `GetTransaction` entry with outcome `OK`.
> - AC4: Stub exception and retry: with `STUB_FAILURE_RATE` set to 1 on stub `transaction-activity` and an empty cache, one listing of `00000000001` at page size 2 and one lookup of `0000000776200014` each log exactly three entries with outcome `UNAVAILABLE`, one attempt and two retries, and the caller-measured time to the failure is at least 150 ms, the 50 ms and 100 ms backoffs. Each call ends in 503 (`UNAVAILABLE`) with the receipt `traceId`: the client raises the `UNAVAILABLE` failure, which the standard error mapping (CS-05-002) turns into 503, and the client's warning line carries the test request's `traceId`. Each logs a `cutoffMs` of at most 900 and has a client-measured response below 1,000 ms.
> - AC5: Timeout: with `STUB_LATENCY_MS` set to 400, above the 300 ms `UPSTREAM_TIMEOUT_MS`, an empty cache and a fresh 900 ms request deadline (`REQUEST_DEADLINE_MS`), the same listing and lookup each log exactly two entries, each with outcome `CANCELLED` once its 400 ms delay has elapsed. The attempts run 0–300 ms and 350–650 ms, the second starting at least 350 ms after the first, the 300 ms timeout plus the 50 ms backoff, and no third starts, because its 100 ms backoff plus a full 300 ms attempt exceeds the 250 ms left. Each call ends in 504 (`DEADLINE_EXCEEDED`), with a caller-measured time to the failure of at least 650 ms, a logged `cutoffMs` of at least 650 and at most 900 and a client-measured response below 1,000 ms.
> - AC6: Retry cap: across every scenario in the suite no call makes more than 1 + `UPSTREAM_MAX_RETRIES` (three) attempts, and only `UNAVAILABLE` and `DEADLINE_EXCEEDED` are retried. Within one test request, every second or third entry of an operation follows an entry with outcome `UNAVAILABLE` or `CANCELLED`, and no entry follows an entry with outcome `OK`, `NOT_FOUND` or `INVALID_ARGUMENT`.
> - AC7: Fail hard and degrade, per the `ListTransactions` and `GetTransaction` rows of the TRD degradation table: after a warm listing of `00000000001` at page size 2 has written `cs:v1:transaction-page:00000000001:2:~:~:~`, the suite ages that page to 61 s while the Redis container still holds it. A list failure after retries then fails hard even though the cache holds that stale entry for the same key: with failure rate 1 the listing ends in 503 after exactly three entries, and with latency 400 ms in 504 after exactly two, returning no page and leaving the entry unrewritten. After a warm lookup of `0000000776200014`, its detail entry aged to 120 s is served `STALE` with the aged `cachedAt` after exactly three entries at failure rate 1. Aged to 301 s, with its Redis expiry left in place, it is never returned, and the lookup fails hard in 503 after exactly three entries. Each failure logs a `cutoffMs` of at most 900 and has a client-measured response below 1,000 ms.
>
> > SCOPE-QUESTION (Resolved): Does a detail failure after retries fail hard like a list failure when a stale entry is held? No. The `GetTransaction` row of the TRD degradation table serves a cached transaction within its max stale age, marked `STALE`, and CS-10-006 sets that age at 300 s, so a detail read fails hard only with no entry or an entry past 300 s. Only the `ListTransactions` row fails hard with a stale entry held, because a list page is never served stale.
>
> - AC8: Not found: listing account `00000000099`, which has no cross-reference record, returns `NOT_FOUND` after exactly one attempt, surfaced as 404 through the standard error mapping, and the Redis container holds no `cs:v1:` key afterwards.
> - AC9: Invalid page token: the first-page `next_page_token` that an AC2 listing returns within the same test, with one character changed, returns `INVALID_ARGUMENT` after exactly one attempt, surfaced as 400 with exactly one `details` entry whose `field` is `pageToken`, and no cache entry is written for that token.
> - AC10: Cache hit: after a lookup made as in AC3 within the same test, the Redis container holds `cs:v1:transaction:0000000776200014`. A second identical lookup within the 60 s fresh TTL is served from the Redis container `FRESH` with the first read's `cachedAt`, with no new request-log entry, so the log still holds exactly 1 `GetTransaction` entry.
> - AC11: Redis down: in the cache-outage class, after one `PING` through the service's Redis connection answers `PONG`, the class pauses its dedicated Redis container through the Testcontainers Docker client. A lookup of `0000000776200014` then calls the stub, adding exactly 1 `GetTransaction` entry with outcome `OK`, and returns the record `FRESH` with amount `"985.22"` and no error reaching the caller. The case unpauses the container in a `finally` block, and the base's shared containers keep running with unchanged container IDs.
> - AC12: Isolation: each test starts with no cache keys of its own and with failure injection off: at its start the Redis container holds no `cs:v1:` key and the effective latency and failure rate of stub `transaction-activity` both read 0. A run of the suite under JUnit's random method order (`MethodOrderer.Random`) gives every test the same outcome as the declared order, so reordering the tests changes no outcome.
> - AC13: Schema in the container (the proof CS-10-001 hands to this suite): on a scratch database the suite creates in the shared PostgreSQL container, prepared as each criterion states, the suite proves CS-10-001 AC1 to AC8 as written, the catalogue read-backs of AC1, AC5 and AC6 included. It drops the scratch database afterwards, so the suite database holds only the loaded rows.
> - AC14: Sample load in the container (the proof CS-10-002 hands to this suite): the fixture loader job exits with status 0, and its load report holds `transaction_record` as 300 loaded, 0 skipped and 300 derived processed timestamps. Read back from the container database, `transaction_record` holds 300 rows for the sample accounts, exactly six for each account from `00000000001` to `00000000050`, 50 of them with a negative amount, and the six rows of `00000000001` equal the values CS-10-002 AC3 states, each with `account_id` `00000000001`.
>
> ### 3. Dependencies
>
> - CS-10-006 — provides the client under test.
> - CS-11-007 — provides the container base and the Integration CI stage.
>
> Risk: shared containers leak state between classes unless each test resets its cache keys.

## 4. Design

- Diagrams: [Sequence — transaction list](../../../../architecture/sequence-transaction-list.mmd), [Sequence — transaction detail](../../../../architecture/sequence-transaction-detail.mmd)
- Data model: [transaction_record](../../../../data-model.md#transaction_record), [Fixture rules](../../../../data-model.md#fixture-rules)
- TRD: [Test and performance harness](../../../../technical-requirements.md#test-and-performance-harness), [D3 Stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point), [Resilience, degradation table and cache rules](../../../../technical-requirements.md#resilience), [Deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- Contracts: [transaction_activity_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/transactionactivity/v1/transaction_activity_service.proto), [transaction-activity-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/transaction-activity-service.yaml), [common.proto](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Not applicable — this story is the container-backed suite; unit tests belong to CS-10-001 to CS-10-006, and the `integration`-tagged suite is excluded from the `test` task. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass; at least 80% line coverage per module.
- Integration — Applies: this story adds `TransactionActivityIntegrationTest` against Testcontainers `postgres:16.6` and `redis:7.4.2` through `IntegrationTestSupport`, with the stubs on loopback ports inside the test JVM, covering the stub exception (AC4), latency above `UPSTREAM_TIMEOUT_MS` (AC5), the `UPSTREAM_MAX_RETRIES` cap (AC6), the fail-hard and stale outcomes (AC7), the Redis outage (AC11) and the container-backed schema and load proofs (AC13, AC14). Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic (this story is the suite for CS-10) covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — injected failures are proven here and percentiles in CS-03-003 and CS-04-002. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:performanceTest`, `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms; applies to balance-retrieval and drill-down stories.
- End-to-end — Not applicable — covered by CS-03-001 and CS-04-001. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:e2eTest`, `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — shared containers for the whole test run.

Rationale: one shared container pair keeps the integration stage to one start-up, while resets around every test keep each scenario independent.

- Rejected: Design Option B, fresh containers for every test class — start-up time multiplies with class count.
- Rejected: the stub's in-memory fixture set in place of the relational store — the paging query and the sample load would never meet a real database.

### Shared by both options

- Topology: a relational store container and a cache container, with the transaction activity stub and the configuration stub on loopback ports in the test process.
- Naming: the suite tag marks the suite for the integration stage only, and the unit stage never runs it.
- Addressing: container ports are assigned at start and passed to the service as properties, and the client reaches the stub through its target address.
- Isolation: each test begins with an empty cache, cleared request logs and failure injection off, and only the cache-outage case pauses a cache, its own dedicated one.
- Fallback: a container that fails to start fails the suite at once instead of skipping it.
- Provisioning: the schema, the sample load and the synthetic volume that the loader always adds are applied once before the first test, and the schema checks run on a scratch database.
- Cut-over: the same suite can target a real service by address once the stub is swapped.
- Failures: latency and unavailable answers are injected per test through the shared failure injector, and each attempt is counted in the stub's request log.

### Design Option A — shared containers for the whole test run

The suite reuses the relational store container and the cache container that the container base starts once per test process, and it loads the sample into the relational store once before its first test. The base empties the cache before each test, and the stub harness clears the request log before each test and the per-test overrides after it. Each scenario drives the client under a fresh request budget.

How it behaves:

- The first suite of the run pays the container start, and this suite reuses both containers.
- Every paging and lookup case reads the same loaded rows in the same newest-first order.
- Each failure case sets its own injected delay or failure rate, counts attempts in the request log and checks the cut-off and the caller's elapsed time.
- The fail-hard and stale cases warm the cache, move the entry's cache time back and then fail the stub.
- The cache-outage case pauses a cache container of its own, so the shared pair keeps running.
- The schema checks write only to a scratch database, which the suite drops when they end.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Containers shared for the run (vs per test class) | one start-up cost for the suite | state can leak between suites without resets |
| 2 | Cache keys, overrides and request log reset around every test (vs a container restart) | isolation at low cost | a suite that bypasses the base inherits another suite's entries |
| 3 | Sample loaded once (vs per test) | the paging order is fixed for every test | a repeat load over rows another suite loaded must leave them unchanged |
| 4 | Schema checks on a scratch database (vs the suite database) | the loaded rows stay untouched | each check prepares its own parent rows |

Effort and risk: small effort, low risk with the resets in place.

### Design Option B — fresh containers for every test class

Every test class starts its own relational store container and cache container and loads its own copy of the sample before its first test. Nothing is shared between classes or suites. The scenarios, the injection settings and the request-log reads stay as in Option A.

How it differs:

- No state is shared between classes or suites.
- Start-up and the full load, synthetic volume included, repeat for each class.
- The integration stage runs longer with every class added.
- The cache-outage case pauses the class's own cache container.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Containers started per test class (vs shared for the run) | no state crosses classes or suites | start-up time multiplies with class count |
| 2 | Sample loaded per class (vs once per run) | each class reads rows that only it loaded | every class pays the full load with its synthetic volume |
| 3 | Containers removed after each class (vs at process exit) | a class never inherits a dirty container | each removal adds to the stage's run time |

Effort and risk: small effort, at risk on integration-stage run time.

### Recommendation

- Option: Design Option A
- Why: the suite keeps one start-up while the resets keep tests independent.
- Strongest argument against: a missed reset, or another suite that changes the shared rows, lets one test's state change another's outcome.
- Shared items that do not decide the choice: the scenarios, the thresholds, the stub harness, the failure injector and the relational stub on a loopback port.
- Flip conditions: tests that write rows to the suite database, a repeat sample load that changes the shared rows, or a shared-state defect the resets cannot contain.

## Design Validation

### Option A

Settled assumptions:

- The container base exists from the container base story, starts both containers once per test process and empties the cache before each test.
- The stub harness accepts per-test overrides, clears the request log before each test and clears the overrides after it.
- No test writes rows to the suite database, and the schema checks write only to a scratch database.
- The client story keeps a one-minute freshness window for both key families, no stale window for list pages and a five-minute maximum stale age for detail entries.
- The suite never launches a configuration refresh, so the call policy keeps its built-in attempt timeout and retry cap.
- A case with injected latency ends only after every delayed call has reached the request log.
- The synthetic volume that the loader always adds sits outside the sample account range, so no scenario reads it.

Open assumptions:

- The integration stage on the pull-request runner can start both containers Open → the first pull-request run of the integration stage.
- A repeat sample load over the rows that the card data suite loaded in the same test process leaves them and their counts unchanged Open → the first integration stage run that holds both suites.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the suite inherits the integration tag and the shared container pair from the base |
| AC2 | Holds | the relational stub reads the loaded rows in newest-first keyset order |
| AC3 | Holds | the lookup reads the loaded row by its primary key |
| AC4 | Holds | the call policy makes one attempt and two retries on immediate unavailable answers |
| AC5 | Holds | the fit check stops a third attempt before the cut-off |
| AC6 | Holds | the call policy retries only the two retryable statuses |
| AC7 | Holds | the client keeps the list fail-hard and the detail within its stale window |
| AC8 | Holds | the stub answers not found for an account with no cross-reference record |
| AC9 | Holds | the stub rejects a changed page token before any store read |
| AC10 | Holds | a fresh entry in the cache container answers with no remote call |
| AC11 | Holds | the read-through cache treats an unanswered cache command as a miss |
| AC12 | Conditional | it holds only while no other suite changes the shared rows or leaves the shared cache dirty |
| AC13 | Holds | the schema checks run on a scratch database apart from the loaded rows |
| AC14 | Conditional | it holds only if a repeat load over shared rows reports the same counts |

### Option B

Settled assumptions:

- Each test class starts and removes its own relational store container and cache container.
- No test writes rows to the class's database outside the scratch database of the schema checks.
- The synthetic volume, the client story's freshness and stale windows and the harness resets are as in Option A.

Open assumptions:

- The integration stage on the pull-request runner can start a container pair for every class within its job limit Open → the first pull-request run of the integration stage.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | it holds only if the base starts a container pair per test class |
| AC2 | Holds | the relational stub reads the class's loaded rows in newest-first keyset order |
| AC3 | Holds | the lookup reads the loaded row by its primary key |
| AC4 | Holds | the call policy makes one attempt and two retries on immediate unavailable answers |
| AC5 | Holds | the fit check stops a third attempt before the cut-off |
| AC6 | Holds | the call policy retries only the two retryable statuses |
| AC7 | Holds | the client keeps the list fail-hard and the detail within its stale window |
| AC8 | Holds | the stub answers not found for an account with no cross-reference record |
| AC9 | Holds | the stub rejects a changed page token before any store read |
| AC10 | Holds | a fresh entry in the class's cache container answers with no remote call |
| AC11 | Holds | the read-through cache treats an unanswered cache command as a miss |
| AC12 | Holds | no shared state crosses classes to leak |
| AC13 | Holds | the schema checks run on a scratch database in the class's own container |
| AC14 | Holds | each class loads its own copy into empty containers |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-10-006, CS-11-007 |
| Status | TBD |
| Estimated Files Touched | 2 |
| Issue Type | Story |
| Epic Name | Transaction Activity Integration |
| Epic Link | CS-10 |
| Story Points | 3 |
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
