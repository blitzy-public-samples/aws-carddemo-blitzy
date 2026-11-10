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
>
>   > SCOPE-QUESTION (Resolved): The bullet above runs the full job in the `cardservicing` database that the CS-11-007 base shares with every suite, where CS-09-006 runs the same job, while the loader stories define no rule for a load over rows already stored and CS-09-007 AC5 proves repeatability only from an empty store. Resolution:
>   >
>   > - Reset, then load: before the job runs, the suite opens a JDBC connection to the base's PostgreSQL container with the container's own JDBC URL, user name and password, read back from the container. It reads every table of the `cardservicing` database's `public` schema from the `pg_tables` catalogue view, drops them in one `DROP TABLE … CASCADE` statement, or drops nothing when the view lists none, and asserts that the view then lists no table in that schema. It then runs the job once. The job's schema step re-creates every table from its create-if-absent scripts, so every entity load starts from an empty store, the precondition CS-09-007 AC5 states, whichever suite ran before. Rows another suite loaded are discarded, never loaded over, so no load meets a stored key and no count drifts, and because every load is deterministic, this suite and CS-09-006 leave the same rows in either class order.
>   > - One class at a time: JUnit Jupiter runs test classes one after another unless parallel execution is enabled, and no story enables it. The suite class also carries JUnit's `@Isolated`, so it never runs beside another test class even if parallel execution is enabled later. The per-test reference reload of CS-13-004 re-applies the schema scripts and replaces its seed rows by code, so it needs no row this suite leaves.
>   > - Not a suite-owned database: the CS-11-007 base binds the store URL in its own `@DynamicPropertySource` method, and under Spring Framework 6.2.12 (Spring Boot 3.5.7) the superclass registration of the same property replaces the subclass's, so a suite cannot point its context at another database without changing the shared base, which CS-11-007 says suites extend with no change.
>   > - The reset and the annotation sit in the suite file, so the 2 files touched stand.
>
>   - Registers `StubHarness.of(...)` (`card-servicing-service/src/test/java/com/example/cardservicing/support/StubHarness.java`, CS-11-002) as a static extension over two registrations, each on a `127.0.0.1` loopback port inside the test JVM. The first is a test-local anonymous `StubRegistration` under stub name `transaction-activity`, which `StubHarness.allRegistered()` never discovers, serving the CS-10-004 and CS-10-005 `TransactionActivityStub` over its relational listing provider, nested in `TransactionMockDataProvider`, and its relational primary-key lookup source. A nested `@TestConfiguration` builds both over the context's JPA `EntityManager`, as the stub's container-mode configuration builds them, and the stub reaches them through the context on each call, so the harness starts it before the context binds its port. `TransactionActivityStubRegistration` (CS-10-004) is not used, because its in-memory provider never reads the store. The second is `ConfigStubRegistration` (CS-11-005) under stub name `config`, whose port the integration profile's Config client target needs when the context starts.
>   - A `@DynamicPropertySource` method supplies `integration.stub-ports.config` and sets `card-servicing.clients.transaction-activity.target` (CS-10-006) to `127.0.0.1:<transaction-activity port>`, so the context's `TransactionActivityClient` calls the stub over a real gRPC channel.
>   - Each scenario call is one test request: it starts a `RequestDeadline` (CS-11-003) from the bound `REQUEST_DEADLINE_MS` value, places a test `traceId` in the logging context, calls `transactions` or `transaction` with a bearer token built at runtime, and marks its cut-off when the call returns, so the completion line logs `cutoffMs`. The caller measures the time from the deadline's start to the outcome.
>     > SCOPE-QUESTION (Resolved): Which step writes the completion line whose `cutoffMs` AC4, AC5 and AC7 assert, when the test request above only starts the `RequestDeadline` and marks its cut-off? CS-11-003 keeps the two steps apart: marking records the cut-off, and completion writes the one structured line carrying `traceId`, the outcome with its response status and `cutoffMs`, while these scenarios call the client directly and no endpoint takes part. The test helper, in test code only, marks the cut-off when the call returns or throws and then completes the deadline exactly once per test request, in a cleanup step that also runs when the call throws, while the test `traceId` is still in the logging context and before the test reads the completion line. It completes with outcome `full` and status 200 for a `FRESH` result, `degraded` and status 200 for a `STALE` result, and `error` with the status the standard error mapping (CS-05-002) gives the raised failure: 503 for `UNAVAILABLE`, 504 for `DEADLINE_EXCEEDED`, 404 for `NOT_FOUND` and 400 for `INVALID_ARGUMENT`. Each test request therefore yields exactly one completion line, carrying the test `traceId` and `cutoffMs`. The client writes no completion line and gains no completion step: completion stays with the endpoint stories (CS-07-002, CS-08-002) and, in this suite, with the test request.
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
>
> > SCOPE-QUESTION (Resolved): AC5 states its cut-off bound as `cutoffMs` at most 900 without a unit. The bound is the 900 ms request deadline (`REQUEST_DEADLINE_MS`), measured from the start of the test request's deadline, which stands for receipt in this suite, to the cut-off, so AC5 requires the cut-off of each call it names to fall at or before 900 ms after that start. The `cutoffMs` bounds of AC4 and AC7 read the same way.
>
> > SCOPE-QUESTION (Resolved): AC5's missing third attempt is the retry-fit rule of the call policy (CS-11-003): a retry starts only when its backoff plus a full 300 ms attempt fits the budget left before the cut-off of the 900 ms request deadline (`REQUEST_DEADLINE_MS`).
>
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
>
> > SCOPE-QUESTION (Resolved): AC12 resets per-test state only. What keeps rows another test class left in the shared database from reaching this suite? Resolution: before its job runs, the suite empties the store and asserts that the catalogue lists no table (note beneath the fixture-loader bullet), and the suite class carries JUnit's `@Isolated`, so no other test class runs beside it. Every test of the suite therefore has the same outcome whether this suite runs before or after CS-09-006 in one `integrationTest` run.
>
> - AC13: Schema in the container (the proof CS-10-001 hands to this suite): on a scratch database the suite creates in the shared PostgreSQL container, prepared as each criterion states, the suite proves CS-10-001 AC1 to AC8 as written, the catalogue read-backs of AC1, AC5 and AC6 included. It drops the scratch database afterwards, so the suite database holds only the loaded rows.
> - AC14: Sample load in the container (the proof CS-10-002 hands to this suite): the fixture loader job exits with status 0, and its load report holds `transaction_record` as 300 loaded, 0 skipped and 300 derived processed timestamps. Read back from the container database, `transaction_record` holds 300 rows for the sample accounts, exactly six for each account from `00000000001` to `00000000050`, 50 of them with a negative amount, and the six rows of `00000000001` equal the values CS-10-002 AC3 states, each with `account_id` `00000000001`.
>
> > SCOPE-QUESTION (Resolved): Does the load-report count depend on what CS-09-006 loaded before this suite in the same test JVM? No. The suite empties the store before its job runs (note beneath the fixture-loader bullet), so the `transaction_record` entry stays 300 loaded, 0 skipped and 300 derived, and the table as a whole also holds exactly the sum of the loaded counts of its load-report entries `transaction_record` and `synthetic_transaction_record`: 300 rows before CS-10-008 lands and 1,000,300 after, whichever of the two suites ran first. The 300 sample rows and their values stay as AC14 states.
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

Rationale: one shared container pair means one start-up, while per-test resets keep scenarios independent.

- Rejected: Design Option B, fresh containers for every test class — start-up multiplies per class.
- Rejected: the stub's in-memory fixture set in place of the relational store — paging would skip a real database.

### Shared by both options

- Topology: a relational store container and a cache container, with the transaction activity and configuration stubs on loopback ports.
- Naming: the suite tag limits it to the integration stage.
- Addressing: start-time ports pass as properties, and the client finds the stub by target address.
- Isolation: no class runs beside the suite, tests start with empty caches, cleared logs and injection off, and only the outage case pauses its own cache.
- Fallback: a failed container start fails the suite.
- Provisioning: earlier suites' tables are dropped and checked gone before one load of schema, sample and synthetic volume, and schema checks use a scratch database.
- Cut-over: a real service changes only target address and credential source, and needs its own data setup, fault injection and call observation.
- Failures: per-test injected latency and unavailable answers, with attempts counted in the request log.

### Design Option A — shared containers for the whole test run

The suite reuses the two containers the container base starts once per test process and loads the sample once before its first test. Each scenario gets a fresh request budget.

How it behaves:

- Paging and lookup cases share one load.
- Failure cases check the cut-off and caller's elapsed time.
- Fail-hard and stale cases warm and age a cache entry, then fail the stub.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Shared containers (vs per class) | one start-up | state leaks without resets |
| 2 | Per-test resets (vs container restarts) | cheap isolation | a suite that bypasses the base inherits another suite's entries |
| 3 | One sample load (vs per test) | fixed paging order | a row one test changed stays changed for later tests |
| 4 | Schema checks on a scratch database (vs suite database) | loaded rows untouched | checks build their parent rows |

Effort and risk: small effort, low risk with the resets in place.

### Design Option B — fresh containers for every test class

Each test class starts its own containers and loads its own sample. Scenarios match Option A.

How it differs:

- Start-up and the full load repeat per class.
- Run time grows per class.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Per-class containers (vs shared) | no state crosses classes | start-up multiplies per class |
| 2 | Per-class sample load (vs once) | own rows per class | every class pays the full load |
| 3 | Container removal per class (vs at process exit) | no dirty container inherited | removals add run time |

Effort and risk: small effort, at risk on integration-stage run time.

### Recommendation

- Option: Design Option A
- Why: the suite keeps one start-up while the resets keep tests independent.
- Strongest argument against: a missed reset leaks state, and the store reset may repeat the card data suite's load.
- Shared items that do not decide the choice: scenarios, thresholds, stub harness, failure injector and store reset.
- Flip conditions: tests writing rows to the suite database, a suite that must run beside other classes, or a shared-state defect the resets cannot contain.

## Design Validation

### Option A

Settled assumptions:

- The base starts containers once per process, and base and harness reset cache, log and overrides per test.
- No test writes rows outside the scratch database.
- The client story keeps a one-minute freshness window, no list stale window and a five-minute detail stale age.
- No configuration refresh runs, so built-in timeout and retry cap hold.
- Synthetic volume lies outside the sample accounts.
- Classes run one at a time, the store reset precedes the load and loads are deterministic.

Open assumptions:

- The pull-request runner can start both containers Open → the first pull-request run of the integration stage.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | base supplies tag and containers |
| AC2 | Holds | stub pages loaded rows newest first |
| AC3 | Holds | key lookup reads the row |
| AC4 | Holds | policy allows two retries |
| AC5 | Holds | retry-fit rule blocks attempt three |
| AC6 | Holds | only retryable statuses retry |
| AC7 | Holds | only details degrade, within stale age |
| AC8 | Holds | no cross-reference means not found |
| AC9 | Holds | stub rejects changed tokens |
| AC10 | Holds | fresh entry skips the stub |
| AC11 | Holds | cache outage counts as a miss |
| AC12 | Holds | store reset, serial runs and flush isolate tests |
| AC13 | Holds | schema checks use a scratch database |
| AC14 | Holds | load starts from an empty store |

### Option B

Settled assumptions:

- Each test class starts and removes its own container pair.
- The other Option A assumptions hold unchanged.

Open assumptions:

- The pull-request job limit fits per-class container pairs Open → the first pull-request run of the integration stage.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | only with per-class base containers |
| AC2 | Holds | stub pages the class's rows newest first |
| AC3 | Holds | key lookup reads the row |
| AC4 | Holds | policy allows two retries |
| AC5 | Holds | retry-fit rule blocks attempt three |
| AC6 | Holds | only retryable statuses retry |
| AC7 | Holds | only details degrade, within stale age |
| AC8 | Holds | no cross-reference means not found |
| AC9 | Holds | stub rejects changed tokens |
| AC10 | Holds | fresh entry skips the stub |
| AC11 | Holds | cache outage counts as a miss |
| AC12 | Holds | no state crosses classes |
| AC13 | Holds | schema checks use a scratch database |
| AC14 | Holds | each class loads into empty containers |

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
