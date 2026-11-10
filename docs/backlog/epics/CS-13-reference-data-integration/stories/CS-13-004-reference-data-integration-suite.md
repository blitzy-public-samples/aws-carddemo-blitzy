# CS-13-004: Reference Data integration suite

Parent epic: [CS-13 Reference Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a delivery engineer, I want the Reference Data path proven against real containers, so that description fallback is verified before endpoints rely on it.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA04Y · TRAN-CAT-TYPE-DESC
>
> Directive: testing requirements (integration suite)
>
> Account-data access path: No
>
> External integration or SLA-bound: Yes
>
> Anchor basis: Data Layouts · CVTRA04Y · TRAN-CAT-TYPE-DESC X(50), with Data Layouts · CVTRA03Y · TRAN-TYPE-DESC X(50), is the description that labels category and type-group balances, and that this epic's client replaces with a stale or partial answer when it cannot be fetched. This story proves that fallback, and the change path of a reloaded description, against a real gRPC channel, a real PostgreSQL container and a real Redis container before the W2 endpoints rely on it.
>
> Capability: one Reference Data integration suite drives the CS-13-003 client against the CS-13-002 stub, with the stub reading the CS-13-001 rows from the PostgreSQL container and the client's two whole-list entries held in the Redis container. It proves the stub exception, latency above the timeout, the retry cap, the deadline cut-off, the fresh hit, the stale and partial fallback, and the visibility of a seed reload. Target paths:
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/referencedata/ReferenceDataIntegrationTest.java`:
>   - Extends `IntegrationTestSupport` (CS-11-007), so it carries `@Tag("integration")`, runs under the `integration` profile and reuses the Testcontainers `postgres:16.6` and `redis:7.4.2` containers started once per test JVM, with the Redis container emptied by `FLUSHALL` before each test. It starts a `@SpringBootTest` context under that profile.
>   - Registers `StubHarness.of(...)` (CS-11-002) as a static extension over two registrations, each on a `127.0.0.1` loopback port inside the test JVM. The first is a test-local anonymous `StubRegistration` under stub name `reference-data`, which `StubHarness.allRegistered()` never discovers, serving the CS-13-002 `ReferenceDataStubService` over its relational `ReferenceDataProvider`. The second is `ConfigStubRegistration` (CS-11-005) under stub name `config`, whose port the integration profile's Config client target needs when the context starts.
>   - The relational provider reads the PostgreSQL container through the context's JPA `EntityManager`: a nested `@TestConfiguration` declares it as a bean, and the test-local stub delegates each read to that bean, which the context builds before the first stub call.
>   - A `@DynamicPropertySource` method supplies `integration.stub-ports.config` and sets `card-servicing.clients.reference-data.target` to `127.0.0.1:<reference-data port>`, so the context's `ReferenceDataClient` calls the test-local stub over a real gRPC channel.
>   - Before each test, after the base's `FLUSHALL` has cleared `cs:v1:reftypes` and `cs:v1:refcats`, it reruns the CS-13-001 reference entity loaders through the CS-09-001 `FixtureLoader` with the seed location `classpath:reference-data/`, so every test starts from the 7 type rows and 18 category rows of the packaged seed files. It then applies the scenario's per-test overrides through `inject("reference-data", latencyMs, failureRate)`.
>   - Each scenario call is one test request: it starts a `RequestDeadline` (CS-11-003) from the bound `REQUEST_DEADLINE_MS` value, calls `transactionTypes(token, deadline)` or `transactionCategories(token, deadline)` with a bearer token built at runtime, then marks its cut-off when the client returns and completes, so the completion line logs `cutoffMs`. The caller measures the time from the deadline's start to the client's return.
>   - The suite never calls `accessAllowed(token)`, so the configuration snapshot stays on its built-in values, a 300 ms per-attempt timeout and 2 retries, and the Config stub's request log stays empty.
>   - It reads and writes cache entries through the base's Lettuce connection. An entry it writes uses the CS-11-004 envelope, an 8-byte big-endian `cachedAt` in microseconds since the epoch followed by the protobuf bytes of a `ListTransactionTypesResponse` or `ListTransactionCategoriesResponse` holding the seeded rows, with a Redis expiry of 86,400 s less the entry's age.
>   - It builds its token and service key values at runtime, so the CS-11-001 committed-secret scan finds no literal credential.
> - `card-servicing-service/src/test/resources/integration/reference-data-integration.yml`: the suite's scenario settings, read from the classpath with SnakeYAML:
>   - The per-test injection overrides of each scenario: failure rate 1.0 for the stub exception and the stale case, latency 400 ms for the timeout case, and latency 0 with failure rate 0 for the fresh-hit and reload cases.
>   - The 900 ms request deadline each scenario starts, which the suite asserts equals the bound `REQUEST_DEADLINE_MS` value before the first scenario.
>   - The entry ages: 60 s for the fresh entry and 7,200 s for the stale entry.
>   - The AC5 seed change, type `05` from `Refund` to `Refund issued`, from which the suite writes a changed copy of both seed files into a JUnit `@TempDir` folder and passes `file:<temp folder>/` as the seed location of the rerun.
> - Together these are the 2 files touched.
>
> Test approach: every duration assertion except the bounds of `cutoffMs` and of the caller-measured response uses a lower bound only, because a loaded runner stretches measured durations. A case with injected latency ends only after every delayed call has reached its request log, so no late entry lands in the next test's log. Every count in section 2 refers to entries of the Reference Data stub's request log, counted per operation: `cardservicing.referencedata.v1.ReferenceDataService/ListTransactionTypes` or `cardservicing.referencedata.v1.ReferenceDataService/ListTransactionCategories`. Each operation runs under its own test request with a fresh 900 ms deadline.
>
> > SCOPE-QUESTION (Resolved): A request-log entry carries its sequence, operation, outcome and duration but no receipt time, so the log cannot show the gap between two arrivals. An interceptor in the suite's test-local registration cannot record arrivals either: `StubServerHost` (CS-11-002) wraps each hosted stub's service definition with its own failure injector and request log, and the injector answers an injected failure, or ends a call cancelled during injected latency, before the call reaches the stub (CS-11-002 AC3 and AC10, CS-13-002 AC6), so such an interceptor never sees those attempts. The suite therefore asserts attempt spacing through the caller-measured time, from the deadline's start to the client's return, and the shared request-log contract stays unchanged. The exact spacing of each backoff and each attempt timeout stays with the call-policy cases of CS-11-003, AC1 and AC2 on its test clock and the AC6 deadline exhaustion on the real clock, and with the client cases of CS-13-003 AC3 and AC4.
>
> In scope:
>
> - The 2 files above.
> - The stub exception, latency above the timeout and retry cap scenarios.
> - Reload visibility through the relational store.
> - The fresh-hit, stale and partial scenarios against the real cache.
> - Caller-measured timing and the logged cut-off.
>
> Not in this story:
>
> - The container base and the Integration CI stage (CS-11-007).
> - Client, stub and seed behaviour (CS-13-003, CS-13-002, CS-13-001).
> - Endpoint-level degradation and `dataStatus` precedence (CS-05-005, CS-06-002).
> - Stub hosting, failure injection and the request log (CS-11-002), the call policy and the request deadline (CS-11-003), and the read-through cache (CS-11-004).
>
> ### 2. Acceptance Criteria
>
> - AC1: The suite carries the `integration` tag and runs under `./gradlew :card-servicing-service:integrationTest`, in the Integration stage of `.github/workflows/ci.yml` on every pull request. It is excluded from `./gradlew :card-servicing-service:test`: that task's report under `card-servicing-service/build/test-results/test/` lists no `ReferenceDataIntegrationTest`. A pull request with one failing case in the suite fails the gate.
> - AC2: Stub exception (degrade): with `STUB_FAILURE_RATE=1`, a per-test failure-rate override of 1.0 on stub `reference-data`, and an empty Redis, each operation logs exactly 3 calls, each with outcome `UNAVAILABLE`: 1 attempt plus 2 retries, the first retry at least 50 ms and the second at least 100 ms after the preceding entry. Each result has `usable` false, null descriptions, with every description lookup empty, and status `PARTIAL`, and no error reaches the caller. Afterwards the Redis container holds neither `cs:v1:reftypes` nor `cs:v1:refcats`.
>   > SCOPE-QUESTION (Resolved): The retry spacing of this criterion is asserted through the caller-measured time of each operation, from the deadline's start to the client's return: it is at least 150 ms, the 50 ms and 100 ms backoffs, plus the sum of the 3 logged durations, because each client attempt spans its stub entry's duration from receipt to close. The separate 50 ms and 100 ms gaps are proven on the test clock by CS-11-003 AC2. The 3 entries with outcome `UNAVAILABLE`, the partial results, the absence of a caller error and the absence of both cache keys afterwards stand.
> - AC3: Latency above the timeout (degrade): with `STUB_LATENCY_MS=400`, a per-test latency override of 400 ms on stub `reference-data`, above the 300 ms per-attempt timeout (`UPSTREAM_TIMEOUT_MS`), an empty Redis and a fresh 900 ms request deadline (`REQUEST_DEADLINE_MS`) for each operation, each operation logs exactly 2 attempts once each 400 ms delay has elapsed. Each entry has outcome `CANCELLED`, and the second reaches the stub at least 350 ms after the first, a 300 ms attempt plus the 50 ms backoff. A second retry would start at 750 ms and end at 1,050 ms, past the deadline, so it never starts. Each result is `PARTIAL`, and the logged `cutoffMs` is at least 650 and at most 900.
>   > SCOPE-QUESTION (Resolved): The 350 ms spacing of this criterion is asserted through the caller-measured time of each operation, from the deadline's start to the client's return: it is at least 650 ms, two 300 ms attempts and the 50 ms backoff, beside the logged `cutoffMs` of at least 650. The start of the second attempt 350 ms after the first is proven by CS-11-003 AC1 and AC2 on its test clock, by the attempt end between 300 and 330 ms of CS-13-003 AC3 and by the backoff after a timed-out attempt of CS-13-003 AC4. The 2 entries with outcome `CANCELLED`, the second retry that never starts, the `PARTIAL` results and both `cutoffMs` bounds stand.
> - AC4: Retry cap: no scenario logs more than 3 calls per operation (`UPSTREAM_MAX_RETRIES` = 2), and only `UNAVAILABLE` and `DEADLINE_EXCEEDED` are retried. Within one test request, every second or third entry of an operation follows an entry with outcome `UNAVAILABLE` or `CANCELLED`, the stub-side record of a client `DEADLINE_EXCEEDED`, and no entry follows an entry with outcome `OK`.
> - AC5: Reload: after the suite reruns the reference entity loaders over the changed seed copy, the next stub call returns `Refund issued` for type `05` and the other 6 types unchanged, with no stub restart. On the empty cache that call is the client's single `ListTransactionTypes` read: `transactionTypes` returns `Refund issued` for `05` with status `FRESH` from exactly 1 logged call, and the Redis container then holds `cs:v1:reftypes` with a Redis TTL above 86,000 s and at most 86,400 s.
> - AC6: Fresh hit: with a fresh Redis entry for each list, written by the suite with a `cachedAt` 60 s old and holding the seeded rows, the client returns status `FRESH`, `usable` true, `asOf` equal to each entry's `cachedAt`, the 7 type descriptions and the 18 category descriptions, and the stub request log records zero calls.
>   > SCOPE-QUESTION (Resolved): The criterion also covers a cold-cache read in a separate test, so the hit proof above stays its own test. With an empty Redis, the packaged seed rows loaded and the stub at latency 0 and failure rate 0, the fresh-hit overrides of the scenario settings file, `transactionTypes` and then `transactionCategories` each run under their own test request with a fresh 900 ms deadline, and each operation logs exactly 1 call with outcome `OK`. Each result has status `FRESH`, `usable` true and `asOf` equal to the written entry's `cachedAt`, which lies between the call's start and its return. `typeDescription` answers each of the 7 packaged type rows and `categoryDescription` each of the 18 packaged category rows with its seeded description, from `01` Purchase to `07` Adjustment and from `01`/`0001` Regular Sales Draft to `07`/`0001` Sales draft credit adjustment. The Redis container then holds `cs:v1:reftypes` and `cs:v1:refcats`, each with a Redis TTL above 86,000 s and at most 86,400 s. Decoded under the CS-11-004 envelope, the first holds the 7 type rows ordered by type code and the second the 18 category rows ordered by type code, then category code, each code and description equal to the rows the suite reads from `transaction_type` and `transaction_category` in the PostgreSQL container through the context's JPA `EntityManager`, which hold exactly 7 and 18 rows. This is the container-backed read of both lists behind CS-13-003 AC2 (one call on a miss, the whole list written, `asOf` and `cachedAt` equal to the read time, a Redis expiry of 86,400 s), CS-13-002 AC1 and AC2 (the ordering) and the seed proof CS-13-001 leaves to this suite.
> - AC7: Stale (degrade): with an entry for each list past the 3,600 s fresh TTL but within the 86,400 s max stale age, its `cachedAt` 7,200 s old, and a failing stub at failure rate 1.0, each operation logs exactly 3 calls and the client returns the entry's descriptions with `usable` true, status `STALE` and `asOf` equal to the entry's `cachedAt`. The entry is not rewritten: its `cachedAt` is unchanged afterwards.
> - AC8: In every failure scenario, AC2, AC3 and AC7, the caller-measured response, from the deadline's start to the client's return, is below 1,000 ms, and the logged `cutoffMs` is at most 900.
>
> ### 3. Dependencies
>
> - CS-13-003 — Reference Data client under test.
> - CS-11-007 — container base and Integration CI stage.
>
> Risk: container start time lengthening the Integration stage.

## 4. Design

- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Data model — reference data as data](../../../../data-model.md#reference-data-as-data)
- [TRD — test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- [TRD — resilience, degradation table and cache rules](../../../../technical-requirements.md#resilience)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- Contracts: [Reference Data service proto](../../../../../card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto)
- Contracts: [Reference Data service OpenAPI](../../../../../card-servicing-service/src/main/resources/openapi/reference-data-service.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Not applicable — the story adds integration test code only. Threshold when it applies: `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass with ≥80% line coverage per module, unchanged by this story because the `integration`-tagged suite is excluded from the `test` task.
- Integration: Applies. Command `./gradlew :card-servicing-service:integrationTest`; all pass. This is the one suite for the CS-13 external epic, covering a stub exception (AC2), latency above `UPSTREAM_TIMEOUT_MS` (AC3) and the `UPSTREAM_MAX_RETRIES` cap (AC4), with `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms (AC3, AC8), against Testcontainers `postgres:16.6` and `redis:7.4.2` with the stubs on loopback ports inside the test JVM. It bounds attempt spacing by the caller-measured time (AC2, AC3), with each single backoff proven by the test-clock cases of CS-11-003, and proves a successful cold-cache read of both lists from the PostgreSQL container, one `OK` call each with the ordered 7 and 18 rows written back to the cache (AC6).
- Performance: Not applicable — no UI-facing endpoint is exercised; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
- End-to-end: Not applicable — no UI step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — shared containers per test process, with cache entries cleared and seed rows reloaded before each test.

Rationale: one container pair per test process keeps the integration stage short, while per-test cleanup gives each scenario a known start.

- Rejected: Option B, fresh containers per test class, which buys stronger isolation at the cost of a container start per class.
- Rejected: the stub over the in-memory seed rows, because the reload scenario needs the stub reading the relational container.

### Shared by both options

- Topology: one relational container and one cache container, with the stub in the test process on a loopback port.
- Naming: the suite carries the integration tag and runs only in the integration stage.
- Addressing: the client points at the loopback stub address.
- Isolation: every test starts from the seed rows and an empty cache.
- Fallback: the suite asserts the client's fallback and provides none of its own.
- Provisioning: both containers run the pinned images that the container base story names.
- Cut-over: the real service replaces the stub by a change of the client's target address and credential source, and a suite against it needs its own seed data, fault injection and call observation, because the seed reload, the injected faults and the request log belong to the local stub.
- Failures: injected latency and failure rate set per test, read back from the request log.

### Design Option A — shared containers per test process

The suite reuses the relational and cache containers that the container base starts once per test process. Before each test the cache is emptied and the packaged seed rows are reloaded, so a description changed by the reload scenario never reaches the next test. Each scenario sets its injected latency and failure rate, drives the client under a fresh request budget and reads the stub's request log.

How it behaves:

- The first integration suite of the process pays the container start, and this suite starts no container of its own.
- The seed reload before each test puts back any description an earlier scenario changed.
- The stub reads the relational container on every call, so a reload is visible on the next call.
- Each scenario writes the cache entries it needs before it drives the client.
- Call counts come from the stub's request log, and timings come from the caller's own clock.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Shared containers per test process (vs fresh containers per test class) | one container start serves every integration suite | a missed cleanup carries state into the next test |
| 2 | Cleanup before each test (vs cleanup after each test) | a test that fails midway still leaves the next test a known start | the last test of a run leaves its rows and entries behind |
| 3 | Request-log counts (vs timing-only assertions) | attempt counts hold on a loaded runner | the assertions depend on the request-log format |

Effort and risk: one suite class and one scenario settings file, with the risk in timing bounds on a loaded runner.

### Design Option B — fresh containers per test class

The suite declares its own relational and cache container pair, started before its first test and removed after its last. Each test class therefore begins on empty containers, and the seed rows load once per class. The scenarios, the injection settings and the request-log reads stay as in Option A.

How it differs:

- Every test class pays a container start, which lengthens the integration stage.
- The suite carries a container lifecycle of its own beside the shared base.
- No state crosses a class boundary, but tests within one class still need a cleanup step.
- The first timing scenario of each class meets cold containers.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Containers per test class (vs shared containers per test process) | no state crosses a class boundary | each class pays a container start |
| 2 | Seed rows loaded once per class (vs reloaded before each test) | fewer loads per run | the reload scenario needs its own restore step |

Effort and risk: similar effort with a container lifecycle in the suite, with the risk of a longer integration stage.

### Recommendation

- Option: Option A.
- Why: the suite reuses containers that already run, so it adds no container start to the stage while each test still starts from known rows and an empty cache.
- Strongest argument against: state left by one test can leak into the next if cleanup is missed.
- Shared items that do not decide the choice: the scenarios, the injection settings, the request log and the pinned images.
- Flip conditions: leaked state between test classes causes unstable outcomes in repeated stage runs.

## Design Validation

### Option A validation

Settled assumptions:

- The container base supplies the pinned images and empties the cache before each test.
- The stub hosting story supplies the per-test overrides and the request log.
- The retry arithmetic gives 3 calls on immediate failure and 2 attempts at 400 ms latency.
- Each backoff gap is proven on the call policy's test clock, so the suite bounds only the caller-measured total.
- The stub sorts both lists by code itself, so a written entry holds its rows in code order.
- The seed reload replaces rows by code and never deletes one.
- The stub reads the relational store on every call and keeps no copy between calls.

Open assumptions:

- Container start time stays within the stage budget. Open → settled by the first integration stage run.
- Timing jitter on shared runners leaves the attempt counts unchanged. Open → settled by repeated stage runs.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the inherited integration tag routes the suite to the integration task alone |
| AC2 | Holds | immediate unavailable answers leave both retries and their backoffs inside the caller-measured time |
| AC3 | Holds | the caller-measured time spans two timed-out attempts and one backoff, leaving no room for a second retry |
| AC4 | Holds | the call policy caps retries at two and retries only the two retryable statuses |
| AC5 | Holds | the reload commits before the suite makes the next call |
| AC6 | Holds | the client calls the stub once per list on a miss and never on a fresh hit |
| AC7 | Holds | the cache falls back to an entry within the max stale age after the last failed attempt |
| AC8 | Holds | every failure path reaches its cut-off well before the response bound |

### Option B validation

Settled assumptions:

- The suite's containers run the pinned images that the container base story names.
- The stub hosting story supplies the per-test overrides and the request log.
- The retry arithmetic gives 3 calls on immediate failure and 2 attempts at 400 ms latency.
- Each backoff gap is proven on the call policy's test clock, so the suite bounds only the caller-measured total.
- The stub sorts both lists by code itself, so a written entry holds its rows in code order.

Open assumptions:

- Per-class container starts fit the stage budget. Open → settled by the first integration stage run.
- Cold containers leave the first timing scenario of a class unaffected. Open → settled by repeated stage runs.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the integration tag routes the suite to the integration task alone |
| AC2 | Holds | immediate unavailable answers leave both retries and their backoffs inside the caller-measured time |
| AC3 | Conditional | cold container timings may skew attempt counts |
| AC4 | Holds | the call policy caps retries at two and retries only the two retryable statuses |
| AC5 | Holds | the reload commits before the suite makes the next call |
| AC6 | Holds | the client calls the stub once per list on a miss and never on a fresh hit |
| AC7 | Holds | the cache falls back to an entry within the max stale age after the last failed attempt |
| AC8 | Conditional | cold container timings may push a first response toward the bound |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-13-003, CS-11-007 |
| Status | TBD |
| Estimated Files Touched | 2 |
| Issue Type | Story |
| Epic Name | Reference Data Integration |
| Epic Link | CS-13 |
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
