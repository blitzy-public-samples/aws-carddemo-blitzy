# CS-12-005: Auth integration suite

Parent epic: [CS-12 Auth Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a delivery engineer, I want the authentication and authorization path proven against real containers, so that fail-closed behaviour and the Config launch point are verified before endpoints rely on them.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Directive: testing requirements (integration suite)
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Anchor basis: Use Cases · COACTVWC is the account view that this epic's access control protects. This story proves that access control end to end on a real channel, with the relational store and the cache in real containers, before the W2 endpoints rely on it.
>
> Capability: one Auth integration suite drives the account access guard of CS-12-003, the Auth client of CS-12-002 and the Auth stub of CS-12-001 through a REST request on the guarded path family, with the configuration snapshot of CS-11-006 behind the guard's access-allowed hook. It proves the fail-closed outcomes, the attempt timeout, the retry cap, the deadline cut-off and the launch point of the background Config refresh. Target paths:
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/auth/AuthIntegrationTest.java`:
>   - Extends `IntegrationTestSupport` (CS-11-007), so it inherits `@Tag("integration")`, the `integration` profile and the Testcontainers `postgres:16.6` and `redis:7.4.2` containers started once per test JVM, with the Redis container emptied before each test. It starts a `@SpringBootTest` context on a random HTTP port that imports `AuthIntegrationTestConfiguration`.
>   - Registers `StubHarness.of(new AuthStubRegistration(), new ConfigStubRegistration(), new ProbeStub())` (CS-11-002) as a static extension, so the Auth stub (CS-12-001), the Config stub (CS-11-005) and the test-only probe stub run under stub names `auth`, `config` and `probe`, each on a `127.0.0.1` loopback port inside the test JVM.
>   - A `@DynamicPropertySource` method supplies `integration.stub-ports.config`, which the integration profile's Config client target reads, `card-servicing.auth.target` as `127.0.0.1:<auth port>`, `integration.stub-ports.probe` for the probe channel of the test configuration, and `card-servicing.auth.api-key` (`AUTH_SERVICE_API_KEY`) from a value the suite builds at runtime, so the CS-11-001 committed-secret scan finds no literal credential.
>   - Marks the context dirty after each test method, as the Config integration suite does, so every test starts on a built-in configuration snapshot.
>   - Sends each request with the JDK `java.net.http` client to the random HTTP port and measures the client latency from send to the complete response. It applies per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` overrides through `inject(stubName, latencyMs, failureRate)`.
>   - Asserts on the request logs of the three stubs, on the Redis container through the base's Lettuce connection, and on the `RequestDeadline` completion line (CS-11-003) with its `traceId`, outcome and `cutoffMs`. It captures all log output with the root log level at DEBUG.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/auth/AuthIntegrationTestConfiguration.java`:
>   - A `@TestConfiguration` holding a test Stage 0 filter in the slot that CS-12-003 leaves between `TraceIdFilter` and `AccountAccessFilter`. It starts the request's `RequestDeadline` at receipt and rejects an `accountId` that does not match `^[0-9]{11}$` or is all zeros with 400 `INVALID_ARGUMENT`, through the filter error writer of `ApiErrorHandler` (CS-05-002), before the guard. It stands in for the request-parameter validator of CS-05-003, which lands in W2.
>   - A test-only minimal account endpoint, `GET /v1/accounts/{accountId}/integration-probe`, inside the `/v1/accounts/{accountId}/**` path family that `AccountAccessGuard` guards. It reads the account through `ReadThroughCache` (CS-11-004) under the test key family `probe`, key `cs:v1:probe:<accountId>`, with an upstream read of one `probe.v1.ProbeService/Echo` call through `UpstreamCallPolicy` under the request's deadline. It then marks the cut-off, completes the deadline with outcome `full` and returns 200 with the echoed text. The probe stub stands in for the Card Data stub of CS-09-004, which lands later in W1.
> - Together these are the 2 files touched.
>
> Test approach: every duration assertion except the deadline bounds of `cutoffMs` and the client latency uses a lower bound only, because a loaded runner stretches measured durations. A case with injected latency ends only after every delayed call has reached its request log, so no late entry lands in the next test's log. Auth stub counts refer to the operations `cardservicing.auth.v1.AuthService/ValidateToken` and `cardservicing.auth.v1.AuthService/AuthorizeAccountAccess`, Config stub counts to `cardservicing.config.v1.ConfigService/GetConfig`, and probe stub counts to `probe.v1.ProbeService/Echo`. Every request uses `authorization: Bearer <token>` with a CS-12-001 fixture token.
>
> In scope:
>
> - The 2 files above.
> - The Auth integration suite on a real gRPC channel with real store and cache containers.
> - Its test configuration: the Stage 0 stand-in and the minimal account endpoint.
> - The proof of the Config launch point through the guard.
>
> Not in this story:
>
> - The production endpoints and their parameter validation (W2 stories CS-05-003, CS-05-006, CS-06-001, CS-06-003, CS-07-001, CS-07-003, CS-08-001 and CS-08-003).
> - Performance scenarios (CS-01-003, CS-01-005, CS-02-002, CS-03-003 and CS-04-002).
> - Changes to the guard (CS-12-003), the Auth client (CS-12-002), the Auth stub (CS-12-001), the configuration snapshot (CS-11-006), the harness and the probe stub (CS-11-002), or the container base and the Integration stage (CS-11-007).
> - The gRPC path of the guard, proven by the CS-12-003 unit suite.
> - The Key Vault secret source (CS-12-004).
>
> ### 2. Acceptance Criteria
>
> - AC1: Stub exception: with a per-test failure-rate override of 1 (`STUB_FAILURE_RATE`) on stub `auth`, a `fixture-token-user0001` request for account `00000000001` returns 503 with `code` `UNAVAILABLE` after exactly 3 `ValidateToken` entries in the Auth stub log, each with outcome `UNAVAILABLE`: the first attempt plus the cap of two retries (`UPSTREAM_MAX_RETRIES`), the first retry at least 50 ms and the second at least 100 ms after the preceding entry. Retries happen only on `UNAVAILABLE` or `DEADLINE_EXCEEDED`, so the 401 request of AC4 receives exactly 1 `ValidateToken` attempt, with outcome `UNAUTHENTICATED`. The Auth stub log holds no `AuthorizeAccountAccess` entry and the probe stub log holds zero entries.
> - AC2: Latency above the timeout: with a per-test latency override of 400 ms (`STUB_LATENCY_MS`) on stub `auth`, each attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the budget left on the 900 ms request deadline (`REQUEST_DEADLINE_MS`), which runs from receipt to the cut-off. Exactly 2 `ValidateToken` entries reach the Auth stub log, each with outcome `CANCELLED` once its 400 ms delay has elapsed, the second at least 350 ms after the first call (a 300 ms attempt plus the 50 ms backoff). No third attempt starts, because its 100 ms backoff plus a full 300 ms attempt would not fit the remaining budget. The request returns 504 with `code` `DEADLINE_EXCEEDED`, a logged `cutoffMs` of at least 650 and at most 900, and no `AuthorizeAccountAccess` entry.
> - AC3: Authorization: a `fixture-token-user0001` request for account `00000000011`, outside the entitlements of `USER0001`, returns 403 with `code` `PERMISSION_DENIED`. No account data is read from upstream or cache before access is allowed: the probe stub log holds zero entries, the Redis container holds no key `cs:v1:probe:00000000011`, and its `INFO stats` counters `keyspace_hits` and `keyspace_misses` are unchanged across the request.
> - AC4: Config launch point: with the `cs:v1:config` mirror entry seeded in the Redis container with a `cachedAt` 400 s old, past the 300 s fresh TTL, and the snapshot holding no fresh values, three rejected requests are sent: a Stage 0 400 `INVALID_ARGUMENT` for account `00000000000` with `fixture-token-user0001`, a 401 `UNAUTHENTICATED` for account `00000000001` with `fixture-token-expired`, and the 403 request of AC3. The Auth stub log holds no entry for the 400 request, and the Config stub log is empty 1 s after the last of the three. One allowed request, `fixture-token-user0001` for account `00000000001`, then returns 200, and the Config stub log holds exactly 1 `GetConfig` entry once the refresh logs its applied line, at most 5 s after the request.
> - AC5: Config latency: with a per-test latency override of 2,000 ms on stub `config` and the seeding of AC4, the allowed request of AC4 returns 200 with a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms, and the response arrives before the Config stub log holds any entry.
> - AC6: Slow success: with a per-test latency override of 250 ms on stub `auth`, a `fixture-token-user0001` request for account `00000000001` returns 200. The Auth stub log holds one `ValidateToken` and one `AuthorizeAccountAccess` entry, each with outcome `OK`, the probe stub log holds exactly 1 `Echo` entry, and the Redis container holds the key `cs:v1:probe:00000000001`, the positive control of AC3. The completion line logs outcome `full` with `cutoffMs` ≤ 900, and the client-measured response is below 1,000 ms.
> - AC7: Deadline exhaustion: with a per-test latency override of 2,000 ms on stub `auth`, a `fixture-token-user0001` request for account `00000000001` returns 504 with `code` `DEADLINE_EXCEEDED`, a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms. The Auth stub log holds exactly 2 `ValidateToken` entries and no `AuthorizeAccountAccess` entry, the probe and Config stub logs hold zero entries, and the Redis container holds no key under `cs:v1:probe:`.
> - AC8: Fail hard: Auth has no degraded answer, so the 503 of AC1 and the 504s of AC2 and AC7 are errors, never a 200 or a degraded answer. Each carries the standard error object with the `traceId` issued at receipt, equal to the `traceId` on that request's access-log line and completion line, holds no account data and no `dataStatus`, and its completion line logs outcome `error` with the response status. Each logs `cutoffMs` ≤ 900, and each client-measured response is below 1,000 ms.
> - AC9: Secrets: with the root log level at DEBUG, the log output captured across every test of the suite contains neither the string `fixture-token-` nor the configured service key value, and at the end of each test no Redis key or value in the container holds either. The capture covers every logger except the gRPC server transports' frame loggers of the API and the stubs, which the stub hosting of CS-11-002 governs.
> - AC10: Gate: `./gradlew :card-servicing-service:integrationTest` runs `AuthIntegrationTest` in the Integration stage of `.github/workflows/ci.yml` on every pull request. `./gradlew :card-servicing-service:test` does not run it: its report under `card-servicing-service/build/test-results/test/` lists no `AuthIntegrationTest`. A pull request with one failing case in the suite fails the gate.
>
> ### 3. Dependencies
>
> - CS-12-003 — the account access guard under test.
> - CS-11-007 — container base, Integration stage and the Config stub path.
>
> Risk: the minimal endpoint could diverge from production endpoint ordering until the endpoint stories land.

## 4. Design

- [Security architecture — S5 token validation and S6 account authorization on the guard to Auth edges](../../../../architecture/security-architecture.mmd)
- [Balance summary sequence — guard, then the background Config refresh after an allow](../../../../architecture/sequence-balance-summary.mmd)
- [Data model — account](../../../../data-model.md#account)
- [TRD — test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- [TRD — security](../../../../technical-requirements.md#security)
- [TRD — security control table](../../../../technical-requirements.md#security-control-table)
- [TRD — resilience](../../../../technical-requirements.md#resilience)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — configuration snapshot](../../../../technical-requirements.md#configuration-snapshot)
- [TRD — stage budget on a cold path](../../../../technical-requirements.md#stage-budget-on-a-cold-path)
- [Contract — auth_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto)
- [Contract — auth-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/auth-service.yaml)
- [Contract — config_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/config/v1/config_service.proto)
- [Contract — config-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/config-service.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Not applicable — the story adds only an `integration`-tagged suite, which the `test` task excludes. Threshold when it applies: command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module.
- Integration: Applies. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap. This story adds the Auth suite: the stub exception and the retry cap (AC1), latency above `UPSTREAM_TIMEOUT_MS` (AC2), the denial (AC3), the Config launch point (AC4, AC5), slow success (AC6), deadline exhaustion (AC7), the fail-hard outcomes (AC8) and the log scan (AC9), against Testcontainers `postgres:16.6` and `redis:7.4.2` with the stubs on loopback ports inside the test JVM. Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- Performance: Not applicable — the suite runs no load scenario; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
- End-to-end: Not applicable — covered by the CS-01 end-to-end steps. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — one suite that drives the guard through a minimal test-only account endpoint behind a stand-in parameter check.

Rationale: the guard, the Auth client, the Auth stub and the configuration snapshot all exist in this wave, while the production endpoints do not, so a test-only endpoint proves the fail-closed path now rather than a wave later.

- Rejected: Design Option B, the suite driving each production endpoint, because those endpoints arrive in the next wave.
- Rejected: runs on the shared end-to-end container stack, because per-test failure overrides do not apply there.

Shared by both options:

- Topology: the service and the stubs run in one test runtime, with the relational store and the cache in throwaway containers.
- Naming: one suite per external epic carries the integration tag, so only the integration task runs it.
- Addressing: the stubs listen on loopback ports that the test harness reports, bound into the service settings before the application context starts.
- Isolation: per-test failure overrides, fresh request logs, an emptied cache and a new application context for every test.
- Fallback: none, because the Auth path fails closed and has no degraded answer.
- Provisioning: the shared container base starts the relational store and the cache once per test runtime.
- Cut-over: the real Auth service replaces the stub only at the address the suite binds, so the cases stay unchanged.
- Failures: unavailable answers, injected latency and deadline exhaustion, each asserted through the stub request logs and the cut-off time.

### Design Option A

One suite sends real HTTP requests to a minimal account endpoint that exists only in the test configuration. A stand-in parameter check rejects a malformed account number ahead of the guard, and the endpoint reads its data from the probe stub through the cache only after an allow. The suite reads the stub request logs, the cache contents and the completion line of each request to prove what was called, what was read and when the cut-off fell.

How it behaves:

- Each test sets the latency or the failure share of one stub, sends one or more requests and reads the logs that the stubs and the service wrote.
- A denied or failed request leaves the probe stub log and the cache untouched, and an allowed request leaves one probe call and one cache entry as the positive control.
- A configuration entry seeded past its fresh period makes the first allowed request launch one background refresh, while rejected requests launch none.
- A slow configuration service delays only the background refresh, never the response.
- The suite scans every captured log line for fixture tokens and for the service key.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Test-only account endpoint (vs the production endpoints) | the suite runs in this wave with no endpoint story | its order of checks can differ from production |
| 2 | Probe stub as the data source (vs the card data stub) | it needs no story later in the wave | the no-read proof covers a stand-in rather than account data |
| 3 | Stale configuration entry seeded in the cache (vs a controlled clock) | the timing cases keep the real clock | the snapshot starts from built-in values rather than from an expired held set |
| 4 | Per-test failure overrides (vs one fixed failure profile) | each case sets its own latency and failure share | each case waits for its own delayed calls |

Effort and risk: one suite class and one test configuration, with the risk in timing bounds on a loaded runner.

### Design Option B

The suite sends its requests to the four production endpoints on the account path instead of a test-only endpoint. The production parameter check replaces the stand-in, and the card data stub replaces the probe stub as the data source. The cases, the logs read and the stub overrides stay the same as in Option A.

How it differs:

- The suite cannot run until the endpoint stories and the card data stub land in the next wave.
- Every case runs once per endpoint, four times the cases of Option A.
- The production parameter check proves the real order of checks ahead of the guard.
- The no-read proof covers real account data in the cache and the card data stub.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Production endpoints (vs a test-only endpoint) | the real order of checks is proven | the suite waits a wave for the endpoints |
| 2 | Card data stub as the data source (vs the probe stub) | the no-read proof covers real account data | the suite depends on the card data stories |
| 3 | Every case on all four endpoints (vs one endpoint) | each account path is covered | the run time grows fourfold |

Effort and risk: no test configuration but four endpoint variants of every case, with the risk in a wave of delay before the Auth path is proven.

### Recommendation

- Option: Design Option A.
- Why: it proves the fail-closed path, the timeout, the retry cap, the cut-off and the configuration launch point in the same wave as the guard.
- Strongest argument against: the stand-in parameter check can differ from the order that the production endpoints use.
- Shared items that do not decide the choice: the in-runtime stubs, the throwaway containers, the per-test overrides, the request-log assertions and the address-only cut-over.
- Flip conditions: the endpoint stories landing in the same wave as this suite, or the production parameter check differing from the stand-in in a way the endpoint order tests cannot catch.

## Design Validation

### Validation of Option A

Settled assumptions:

- The guard, the Auth client, the Auth stub, the configuration snapshot, the test harness and the probe stub exist from earlier stories in this wave.
- The shared container base and the integration stage exist from the configuration integration suite story.
- The guard story places the parameter check slot ahead of the guard and calls the access-allowed hook only after an allow.
- The fixture tokens and entitlements are fixed by the Auth stub story.

Open assumptions:

- Open → the endpoint validation stories' order tests settle that the stand-in parameter check matches the production order.
- Open → the configuration client story's tests settle that a seeded stale configuration entry leads the background refresh to one configuration read.
- Open → the first pull-request run settles that the work ahead of the first Auth attempt stays below 250 ms on the runner.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the call policy retries an unavailable answer at most twice |
| AC2 | Conditional | the two-attempt count assumes under 250 ms of work ahead of the first attempt |
| AC3 | Holds | the guard rejection precedes any read through the cache or the probe stub |
| AC4 | Conditional | the seeded stale entry must lead the refresh to one configuration read |
| AC5 | Conditional | the under-1,000 ms response depends on thread scheduling on the runner |
| AC6 | Conditional | the under-1,000 ms response depends on thread scheduling on the runner |
| AC7 | Conditional | the under-1,000 ms response depends on thread scheduling on the runner |
| AC8 | Holds | every Auth failure ends in an error status through the standard error object |
| AC9 | Holds | the guard, the client and the stub never write a credential |
| AC10 | Holds | the integration tag routes the suite to the integration task alone |

### Validation of Option B

Settled assumptions:

- The guard, the Auth client, the Auth stub, the configuration snapshot and the test harness exist from earlier stories in this wave.
- The shared container base and the integration stage exist from the configuration integration suite story.
- The fixture tokens and entitlements are fixed by the Auth stub story.

Open assumptions:

- Open → the endpoint stories settle when the four production endpoints and their parameter check exist.
- Open → the card data stub story settles when the card data stub can serve as the data source.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | the guard rejects on the path family ahead of the missing endpoints |
| AC2 | Conditional | the guard rejects on the path family ahead of the missing endpoints |
| AC3 | Conditional | the no-read proof has no data source in this wave |
| AC4 | Breaks | the production parameter check lands in the next wave |
| AC5 | Breaks | no production endpoint returns a 200 in this wave |
| AC6 | Breaks | no production endpoint returns a 200 in this wave |
| AC7 | Conditional | the guard rejects on the path family ahead of the missing endpoints |
| AC8 | Conditional | only the guard rejections can be observed in this wave |
| AC9 | Conditional | the scan covers only the guard rejections of this wave |
| AC10 | Holds | the integration tag routes the suite to the integration task alone |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-12-003, CS-11-007 |
| Status | TBD |
| Estimated Files Touched | 2 |
| Issue Type | Story |
| Epic Name | Auth Service Integration |
| Epic Link | CS-12 |
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
