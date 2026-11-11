# CS-09-006: Card Data integration suite

Parent epic: [CS-09 Card Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a delivery engineer, I want the Card Data path proven against real PostgreSQL and Redis containers, so that its failure behaviour is verified before endpoints rely on it.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-ID
>
> Directive: testing requirements (integration suite)
>
> Account-data access path: No
>
> External integration or SLA-bound: Yes
>
> Anchor basis: Data Layouts · CVACT01Y · ACCT-ID 9(11) is the key of every Card Data read this suite drives: the account record, its card cross-references (`Data Layouts · CVACT03Y · XREF-ACCT-ID`) and its category balances (`Data Layouts · CVTRA01Y · TRANCAT-ACCT-ID`). This story proves the Card Data client's timeout, retry, stale fallback and fail-hard behaviour on a real gRPC channel, a real PostgreSQL container and a real Redis container before the W2 endpoints rely on it.
>
> Capability: one Card Data integration suite drives the CS-09-005 client against the CS-09-004 stub, with the stub reading the sample rows that the fixture loader writes into the PostgreSQL container and the client's account and category-balance entries held in the Redis container. It also carries the container-backed proofs that CS-09-001, CS-09-002, CS-09-003 and CS-09-004 hand to this suite. Target paths:
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/carddata/CardDataIntegrationTest.java`, the `integration`-tagged suite:
>   - Extends `IntegrationTestSupport` (`card-servicing-service/src/test/java/com/example/cardservicing/support/IntegrationTestSupport.java`, CS-11-007), so it carries `@Tag("integration")`, runs under the `integration` profile and reuses the base's `postgres:16.6` and `redis:7.4.2` containers, started once per test JVM, with the Redis container emptied by `FLUSHALL` before each test. It starts a `@SpringBootTest` context under that profile and holds one `@Nested` group per failure mode. Every group except the cache-outage group shares that container pair and that context.
>   - Before the first group, the fixture loader loads the CardDemo sample into the container database: the suite runs the CS-09-001 `FixtureLoader` job once under the `fixture-load` profile against that database, its schema step (CS-13-001) applies the `db/` scripts in file-name order, and its registered entity loaders load the reference data (CS-13-001) and the sample accounts, cross-references and category balances (CS-09-003).
>
>   > SCOPE-QUESTION (Resolved): The bullet above names only some of the loads the job runs, and it runs the full job in the `cardservicing` database that the CS-11-007 base shares with every suite, where CS-10-007 runs the same job, while the loader stories define no rule for a load over rows already stored and CS-09-007 AC5 proves repeatability only from an empty store. Resolution:
>   >
>   > - The job runs every registered entity loader, none of which a setting switches off: besides the loads named above, the sample transaction load of CS-10-002 (load order 6) and, once CS-09-007 and CS-10-008 land, their synthetic volume loads (load orders 101–104). The synthetic rows sit in the disjoint account range from `10000000001`, under their own load-report entities `synthetic_account`, `synthetic_card_cross_reference`, `synthetic_category_balance` and `synthetic_transaction_record`.
>   > - Reset, then load: before the job runs, the suite opens a JDBC connection to the base's PostgreSQL container with the container's own JDBC URL, user name and password, read back from the container. It reads every table of the `cardservicing` database's `public` schema from the `pg_tables` catalogue view, drops them in one `DROP TABLE … CASCADE` statement, or drops nothing when the view lists none, and asserts that the view then lists no table in that schema. It then runs the job once. The job's schema step re-creates every table from its create-if-absent scripts, so every entity load starts from an empty store, the precondition CS-09-007 AC5 states, whichever suite ran before. Rows another suite loaded are discarded, never loaded over, so no load meets a stored key and no count drifts, and because every load is deterministic, this suite and CS-10-007 leave the same rows in either class order.
>   > - One class at a time: JUnit Jupiter runs test classes one after another unless parallel execution is enabled, and no story enables it. The suite class also carries JUnit's `@Isolated`, so it never runs beside another test class even if parallel execution is enabled later. The per-test reference reload of CS-13-004 re-applies the schema scripts and replaces its seed rows by code, so it needs no row this suite leaves.
>   > - Not a suite-owned database: the CS-11-007 base binds the store URL in its own `@DynamicPropertySource` method, and under Spring Framework 6.2.12 (Spring Boot 3.5.7) the superclass registration of the same property replaces the subclass's, so a suite cannot point its context at another database without changing the shared base, which CS-11-007 says suites extend with no change.
>   > - The reset and the annotation sit in the suite file, so the 2 files touched stand.
>
>   - Registers `StubHarness.of(...)` (CS-11-002) as a static extension over two registrations, each on a `127.0.0.1` loopback port inside the test JVM. The first is a test-local anonymous `StubRegistration` under stub name `card-data`, which `StubHarness.allRegistered()` never discovers, serving the CS-09-004 `CardDataStubService` over its relational `CardDataMockDataProvider`. A nested `@TestConfiguration` builds that provider over the context's CS-09-002 repositories, so the stub reads the container database on every call. The second is `ConfigStubRegistration` (CS-11-005) under stub name `config`, whose port the integration profile's Config client target needs when the context starts.
>   - A `@DynamicPropertySource` method supplies `integration.stub-ports.config` and sets `card-servicing.clients.card-data.target` to `127.0.0.1:<card-data port>`, so the context's `CardDataClient` calls the test-local stub over a real gRPC channel.
>   - Each group resets injection to its own settings through `inject("card-data", latencyMs, failureRate)` and clears every `cs:v1:` cache entry before its first call, so no override or entry crosses groups.
>   - Each scenario call is one test request: it starts a `RequestDeadline` (CS-11-003) from the bound `REQUEST_DEADLINE_MS` value, calls `account`, `categoryBalances` or `cardCrossReferences` for one account with a bearer token built at runtime, then marks its cut-off when the future completes, so the completion line logs `cutoffMs`. The caller measures the time from the deadline's start to the future's completion.
>   - The suite never calls `accessAllowed(token)`, so the configuration snapshot stays on its built-in values, a 300 ms per-attempt timeout and 2 retries, and the Config stub's request log stays empty.
>   - It ages a cache entry by rewriting the 8-byte big-endian `cachedAt` prefix of the CS-11-004 entry envelope through the base's Lettuce connection, keeping the value bytes the client wrote. The 60 s fresh TTL of CS-09-005 is a fixed rule with no configuration entry, and adding one would be production code, so the suite moves the entry's age instead of the TTL.
>   - The cache-outage group is a `@Nested` class whose context binds `REDIS_HOST` and `REDIS_PORT` to one dedicated `redis:7.4.2` container that the group starts and stops. The base's suites never stop the shared containers (CS-11-007), so this group stops only its own, as the Redis-down case of CS-11-007 does.
>   - It builds its token and service-key values at runtime, so the CS-11-001 committed-secret scan finds no literal credential.
> - `card-servicing-service/src/test/resources/integration/card-data-suite.yml`, the suite fixture configuration, read from the classpath with SnakeYAML:
>   - The per-group injection settings (`STUB_LATENCY_MS`, `STUB_FAILURE_RATE`), applied as per-test overrides: failure rate 1.0 for the stub-exception, stale, stale-expiry, fail-hard and cache-outage failure cases; latency 400 ms for the timeout case and the deadline-exceeded cases of the stale and fail-hard groups; latency 0 with failure rate 0 for the fresh, not-found, warm-read, load and provider-equality cases.
>   - The entry ages used to age entries: 120 s, past the 60 s fresh TTL and within the 300 s max stale age, and 301 s, past the max stale age.
>   - The 900 ms request deadline each scenario starts, which the suite asserts equals the bound `REQUEST_DEADLINE_MS` value before the first group.
> - Together these are the 2 files touched.
>
> Test approach: every duration assertion except the bounds of `cutoffMs` and of the caller-measured response uses a lower bound only, because a loaded runner stretches measured durations. A request-log entry carries its order, outcome and duration but no receipt time, so attempt spacing is asserted through the caller-measured time, and the spacing of each single backoff stays with the test-clock cases of CS-11-003. A case with injected latency ends only after every delayed call has reached the request log, so no late entry lands in the next group's log. Every count in section 2 refers to entries of the Card Data stub's request log for one operation: `cardservicing.carddata.v1.CardDataService/GetAccount`, `cardservicing.carddata.v1.CardDataService/GetCardCrossReference` or `cardservicing.carddata.v1.CardDataService/ListCategoryBalances`. Each failure case runs under its own test request with a fresh 900 ms deadline.
>
> In scope:
>
> - The 2 files above.
> - The fresh path, the stub exception, the slow stub, the retry cap, and the stale fallback from real Redis with its expiry.
> - The fail-hard cross-reference read, the Redis outage and the deadline assertions.
> - The tag exclusion from the `test` task.
> - The container-backed load, schema and provider-equality proofs handed to this suite.
>
> Not in this story:
>
> - Production code: the loader core, tables, loaders, stub and client (CS-09-001 to CS-09-005).
> - The container base and the Integration CI stage (CS-11-007).
> - Endpoint `dataStatus` and `asOf` mapping (CS-05-005).
> - Performance volume (CS-09-007).
> - Stub hosting, failure injection and the request log (CS-11-002), the call policy and request deadline (CS-11-003), and the read-through cache (CS-11-004).
>
> ### 2. Acceptance Criteria
>
> - AC1: Fresh path: after the fixture loader has loaded the sample into the container database, `account` for `00000000001` on an empty cache completes `FRESH` with `currentBalance` "194.00" and `creditLimit` "2020.00" from exactly 1 `GetAccount` entry with outcome `OK`. The Redis container then holds `cs:v1:account:00000000001` with a Redis TTL above 290 s and at most 300 s. A repeat read within the 60 s fresh TTL is a cache hit: it completes `FRESH` with the first read's `cachedAt`, and the stub's request log still holds exactly 1 `GetAccount` entry. `categoryBalances` for `00000000001` behaves the same way, with the one balance `01`/`0001` "0.00" under `cs:v1:category-balances:00000000001` and exactly 1 `ListCategoryBalances` entry.
> - AC2: Stub exception: with failure rate 1.0 (`STUB_FAILURE_RATE`) on stub `card-data` and an empty cache, `account` for `00000000001` logs exactly 3 `GetAccount` entries, each with outcome `UNAVAILABLE`: 1 attempt and 2 retries, separated by the 50 ms and then the 100 ms backoff. The caller-measured time from the call's start to its failure is at least 150 ms, the sum of both backoffs. The future completes exceptionally with `UNAVAILABLE`, which the standard error mapping (CS-05-002) turns into 503, and the Redis container holds no `cs:v1:account:00000000001` key afterwards.
> - AC3: Timeout: with a latency of 400 ms (`STUB_LATENCY_MS`) on stub `card-data`, above the 300 ms per-attempt timeout (`UPSTREAM_TIMEOUT_MS`), an empty cache and a fresh 900 ms request deadline (`REQUEST_DEADLINE_MS`), each attempt of `account` for `00000000001` ends with `DEADLINE_EXCEEDED` at 300 ms. Exactly 2 `GetAccount` entries are logged once each 400 ms delay has elapsed, each with outcome `CANCELLED`, the stub-side record of an attempt the client ended. The attempts run 0–300 ms and 350–650 ms; a third would start at 750 ms and end at 1,050 ms, past the deadline, so it never starts. The future completes exceptionally with `DEADLINE_EXCEEDED`, which the standard error mapping turns into 504, the caller-measured time to the failure is at least 650 ms, and the logged `cutoffMs` is at least 650 and at most 900.
>
> SCOPE-QUESTION (Resolved): AC3's third attempt is withheld by the retry-fit rule of the call policy (CS-11-003): a retry starts only when its backoff plus a full 300 ms attempt fits the budget left before the cut-off. At 650 ms the second retry would need its 100 ms backoff plus a full 300 ms attempt, 400 ms, while 250 ms of the 900 ms deadline remain, so it never starts.
>
> - AC4: Retry cap: across every failure group, no call of any operation logs more than 3 entries (`UPSTREAM_MAX_RETRIES` = 2), and only `UNAVAILABLE` and `DEADLINE_EXCEEDED` are retried. Within one test request, every second or third entry of an operation follows an entry with outcome `UNAVAILABLE` or `CANCELLED`, and no entry follows an entry with outcome `OK` or `NOT_FOUND`.
> - AC5: Retry scope: a `NOT_FOUND` answer for account `00000000099`, which the sample does not hold, logs exactly 1 entry for each of `account`, `categoryBalances` and `cardCrossReferences`, with outcome `NOT_FOUND`. Each future completes exceptionally with `NOT_FOUND` and its packed `ErrorObject` unchanged, which the standard error mapping turns into 404, and the Redis container holds no `cs:v1:` key for `00000000099` afterwards.
> - AC6: Degrade, stale from real Redis: after a warm `account` read for `00000000001` at failure rate 0 has written `cs:v1:account:00000000001`, the suite ages that entry to 120 s, past its 60 s fresh TTL and within its 300 s max stale age, by moving its `cachedAt` back 120 s and setting its Redis expiry to 180 s. With failure rate 1.0, the next `account` read logs exactly 3 `GetAccount` entries and completes `STALE` with `currentBalance` "194.00" and the aged entry's `cachedAt`, which is the warm read's `cachedAt` less 120 s. The entry's bytes are unchanged afterwards. With a latency of 400 ms instead, the same aged entry is served `STALE` after exactly 2 entries. `categoryBalances` behaves the same way under `cs:v1:category-balances:00000000001`.
> - AC7: Stale expiry: with the warm entry aged to 301 s, past its 300 s max stale age, and its Redis expiry left in place so the Redis container still holds it, failure rate 1.0 makes `account` for `00000000001` log exactly 3 `GetAccount` entries and complete exceptionally with `UNAVAILABLE`, which the standard error mapping turns into 503. No cached value is returned, and the entry is not rewritten.
> - AC8: Fail hard, cross-reference: a warm `cardCrossReferences` read for `00000000001` at failure rate 0 returns the one cross-reference with `cardNumber` `9680294154603697` and `customerId` `000000001`, and the Redis container then holds no key. With failure rate 1.0 the next read completes exceptionally with `UNAVAILABLE` (503) after exactly 3 `GetCardCrossReference` entries, and with a latency of 400 ms with `DEADLINE_EXCEEDED` (504) after exactly 2. The Redis container still holds no key, so the read is never served from cache.
> - AC9: Redis outage: in the cache-outage group, after a warm `account` read for `00000000001` has written its entry into the dedicated Redis container, the group stops that container. The next `account` read is treated as a miss: it adds exactly 1 `GetAccount` entry with outcome `OK`, completes `FRESH` with `currentBalance` "194.00", and no Redis error reaches the caller. With the container still stopped and failure rate 1.0, the read completes exceptionally with the stub's `UNAVAILABLE` after exactly 3 more entries, not with a Redis error. The base's shared containers keep running with unchanged container IDs.
> - AC10: Deadline: every failure case of AC2, AC3 and AC5 to AC9 logs `cutoffMs` ≤ 900 and has a caller-measured response, from the deadline's start to the future's completion, below 1,000 ms.
>
> SCOPE-QUESTION (Resolved): AC10 states its cut-off bound as `cutoffMs` ≤ 900 without a unit. The bound is the 900 ms request deadline (`REQUEST_DEADLINE_MS`), measured from the deadline's start, which stands for receipt in this suite, to the cut-off, so AC10 requires the cut-off of every failure case it names to fall at or before 900 ms after the deadline's start.
>
> - AC11: Tagging: the suite carries the `integration` tag and runs under `./gradlew :card-servicing-service:integrationTest`, in the Integration stage of `.github/workflows/ci.yml` on every pull request. It is excluded from `./gradlew :card-servicing-service:test`: that task's report under `card-servicing-service/build/test-results/test/` lists no `CardDataIntegrationTest`. A pull request with one failing case in the suite fails the gate.
> - AC12: Sample load in the container (the proof CS-09-001 and CS-09-003 hand to this suite): the fixture loader job exits with status 0, and its load report holds `account` 50 loaded / 0 skipped, `card_cross_reference` 50 loaded / 0 skipped and `category_balance` 50 loaded / 0 skipped, each with 0 derived timestamps. Read back from the container database, the three tables hold 50 rows each, with the stored values that CS-09-003 AC2, AC4 and AC5 state for `00000000001` and `00000000050`.
>
> > SCOPE-QUESTION (Resolved): The job also runs the synthetic volume loads of CS-09-007 once that story lands (note beneath the fixture-loader bullet), and they add 10,000 accounts, 10,000 cross-references and 100,000 category balances to the same three tables, so whole-table counts of 50 hold only until then. Which rows does the read-back count? Resolution: the 50-row read-back counts only the sample account range `00000000001`–`00000000050`: `account` holds 50 rows in that range, and `card_cross_reference` and `category_balance` each hold 50 rows whose account lies in it, with the stored values that CS-09-003 AC2, AC4 and AC5 state for `00000000001` and `00000000050`. The three sample entries stay `account`, `card_cross_reference` and `category_balance` at 50 loaded / 0 skipped / 0 derived timestamps, and AC12 does not count the synthetic entries `synthetic_account`, `synthetic_card_cross_reference` and `synthetic_category_balance`. Because the load starts from the emptied store, each of the three tables also holds exactly the sum of the loaded counts of its own load-report entries: 50, 50 and 50 before CS-09-007 lands, and 10,050, 10,050 and 100,050 after, whichever integration suite ran first in the test JVM.
>
> - AC13: Schema in the container (the proof CS-09-002 hands to this suite): on scratch databases the suite creates in the shared PostgreSQL container, each prepared as the criterion states, the suite proves CS-09-002 AC1 to AC10 as written, the catalogue read-backs of AC2 and AC3 included. It drops every scratch database afterwards, so the suite database holds only the loaded rows.
> - AC14: Provider equality on stored rows (the proof CS-09-004 hands to this suite): for `00000000001`, `00000000050` and `00000000099`, a client of the generated `CardDataServiceGrpc` stub calls each of the three operations on the test-local stub over the stored rows and on a second harness serving `CardDataStubRegistration` over `CardDataFixtureSet`. The answers compare as the note beneath CS-09-004 AC14 sets it: equal status and equal response fields, presence of `group_id` included, `NOT_FOUND` `ErrorObject` values equal in `code`, `message` and `details`, and each `traceId` checked on its own as a 32-character lower-case hexadecimal value distinct from the other.
>
> ### 3. Dependencies
>
> - CS-09-005 — the Card Data client with caching is the unit under test.
> - CS-11-007 — provides the container base and the integration stage.
>
> Risk: Timing assertions on shared runners can be flaky, so each assertion uses lower bounds and the deadline ceiling rather than exact times.

## 4. Design

- Diagram: [Logical architecture](../../../../architecture/logical-architecture.mmd)
- Data model: [Fixture rules](../../../../data-model.md#fixture-rules), [Entity-to-table mapping](../../../../data-model.md#entity-to-table-mapping)
- Technical requirements: [Test and performance harness](../../../../technical-requirements.md#test-and-performance-harness), [Resilience, degradation table and cache rules](../../../../technical-requirements.md#resilience), [Deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- Contracts: [Card Data proto](../../../../../card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto), [Card Data OpenAPI](../../../../../card-servicing-service/src/main/resources/openapi/card-data-service.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Not applicable — this is a test-only story with no production code. Threshold when it applies: `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass with ≥80% line coverage per module, unchanged by this story because the `integration`-tagged suite is excluded from the `test` task.
- Integration: Applies. This story is the Card Data suite, run with `./gradlew :card-servicing-service:integrationTest`; all pass. It is the one suite for the CS-09 external epic and covers a stub exception (AC2), latency above `UPSTREAM_TIMEOUT_MS` (AC3) and the `UPSTREAM_MAX_RETRIES` cap (AC4), against Testcontainers `postgres:16.6` and `redis:7.4.2` with the stubs on loopback ports inside the test JVM. Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms (AC10).
- Performance: Not applicable — failure injection is excluded from percentiles, and no UI-facing endpoint is exercised. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
- End-to-end: Not applicable — there is no UI step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A, one test file with a group per failure mode.

Rationale: each group states its own setup over a shared container pair and sample load.

- Rejected: Option B, a parameterized table, as a row cannot warm, age or stop the cache.

Shared by both options:

- Topology: the store and cache are shared containers, and a stub reads the store per call.
- Naming: each group or row is named after the failure mode it proves.
- Addressing: the client's target address names the loopback stub.
- Isolation: no test class runs beside the suite, each group or row resets injection and clears cache entries, and only the cache-outage case stops a cache, its own dedicated one.
- Fallback: the stale path runs only against the real cache.
- Provisioning: the suite drops earlier suites' tables and checks the store empty, then the loader rebuilds the schema and loads the sample with its synthetic volume.
- Cut-over: a real service changes only the client's target address and credential source, and needs its own data setup, fault injection and call observation.
- Failures: unavailable, slow, not-found and cache outage.

### Design Option A — one file with a group per failure mode

One file holds a group per failure mode. Each group sets its own injection and cache state.

How it behaves:

- The fresh group reads on an empty cache, then within the freshness window.
- The unavailable and slow groups count attempts from an empty cache.
- The stale groups warm the cache, move the entry's timestamp back, then fail the stub.
- After a warm read, the cross-reference read still fails hard with nothing cached.
- The cache-outage group warms its own cache container, stops it and reads again.
- Each failure call records its cut-off and the caller's elapsed time under a fresh budget.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One file (vs one per mode) | two files suffice | the file grows per mode |
| 2 | Shared containers (vs per group) | one start serves all | a missed reset leaks state |
| 3 | Lower bounds (vs exact times) | loaded runners pass | small slowdowns go unseen |
| 4 | Settings file (vs inline values) | values sit together | a group spans two files |
| 5 | Timestamp ageing (vs waiting) | no stale group waits | tests rely on entry layout |

Effort and risk: two files, at risk from timing on loaded runners.

### Design Option B — one parameterized table of failure modes

One case runs over rows of delay, failure rate, attempts and outcome. Stateful cases sit apart.

How it differs:

- The unavailable, slow and not-found rows share one setup and one assertion.
- Stale, stale-expiry and cache-outage proofs need hand-written cases or a setup column.
- A new failure mode with plain setup costs one row.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One table (vs groups) | plain modes share a grid | stateful setups fall outside |
| 2 | Setup column (vs own cases) | the grid holds every mode | the column hides steps |
| 3 | Empty cache (vs row state) | rows stay independent | no row holds stale state |

Effort and risk: similar effort, with stateful proofs at risk of hidden setup.

### Recommendation

- Option: Option A.
- Why: each group spells out its own warm read, ageing or cache stop.
- Strongest argument against: the one file grows with every failure mode.
- Shared items that do not decide the choice: containers, store reset, loader and deadlines.
- Flip conditions: every mode needs the same setup, or each group outgrows two screens.

## Design Validation

### Option A

Settled assumptions:

- A 300 ms attempt is retried after 50 then 100 ms if each retry fits the 900 ms budget.
- Immediate failures give three attempts, a 400 ms delay two, ending at 650 ms.
- The first sample account holds balance 194.00 and credit limit 2020.00.
- Entries stay fresh for 60 s, with a 300 s maximum stale age.
- The shared base gives the container pair, one store address and a per-test empty cache.
- Classes run singly from an empty store, with synthetic rows outside the sample range.

Open assumptions:

- Open → runner timing headroom → settled by the first integration runs.
- Open → each wait on a stopped cache ends in time → settled by the first outage runs.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the repeat read finds the fresh entry |
| AC2 | Holds | immediate failures leave room for both retries |
| AC3 | Holds | a third attempt would overrun the budget |
| AC4 | Holds | two statuses retry, at most twice |
| AC5 | Holds | not-found ends the call at once |
| AC6 | Holds | the aged entry stays within stale age |
| AC7 | Holds | the age rule rejects held over-age entries |
| AC8 | Holds | the cross-reference read skips the cache |
| AC9 | Holds | only the dedicated cache stops |
| AC10 | Holds | each failure cuts off within the deadline |
| AC11 | Holds | the inherited tag keeps the suite integration-only |
| AC12 | Holds | one emptied-store load precedes the sample-range count |
| AC13 | Holds | scratch databases isolate the schema checks |
| AC14 | Holds | a second harness serves the in-memory set |

### Option B

Settled assumptions: every Option A settled assumption holds.

Open assumptions:

- Open → runner timing headroom → settled by the first integration runs.
- Open → a setup column can warm, age and stop the cache → settled by a stale-row trial.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | a plain row holds no repeat read |
| AC2 | Holds | a full failure-rate row gives three attempts |
| AC3 | Holds | the 400 ms row gives two attempts |
| AC4 | Holds | the attempt column caps rows at three |
| AC5 | Holds | an unknown-account row gives one attempt |
| AC6 | At risk | warming and ageing sit outside the table |
| AC7 | At risk | the expired entry needs that outside setup |
| AC8 | Conditional | the warm cross-reference read needs setup |
| AC9 | At risk | a cache stop cannot be a row |
| AC10 | Holds | each row records cut-off and elapsed time |
| AC11 | Holds | the inherited tag keeps the suite integration-only |
| AC12 | Holds | one emptied-store load precedes the sample-range count |
| AC13 | Holds | schema checks run apart on scratch databases |
| AC14 | Holds | the provider comparison runs outside the table |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-09-005, CS-11-007 |
| Status | TBD |
| Estimated Files Touched | 2 |
| Issue Type | Story |
| Epic Name | Card Data Integration |
| Epic Link | CS-09 |
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
