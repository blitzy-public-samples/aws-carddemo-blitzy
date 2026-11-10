# CS-11-007: Config integration suite

Parent epic: [CS-11 Config Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a delivery engineer, I want the Config path proven against real containers, so that failure behaviour is verified before endpoints rely on it.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Directive: testing requirements (integration suite)
>
> Account-data access path: No
>
> External integration or SLA-bound: Yes
>
> Anchor basis: Use Cases · COTRN00C fills a fixed ten-row page set inside the program (PROCESS-PAGE-FORWARD); that screen behaviour is what this epic turns into configured page size and call settings. This story proves the path that delivers those settings, the Config client and its configuration snapshot, against a real gRPC channel and a real Redis container, and adds the Integration stage that every external integration suite runs in.
>
> Capability: one shared container base starts a real PostgreSQL store and a real Redis cache once per test JVM for every external integration suite. The Config suite runs the CS-11-006 client and snapshot against the CS-11-005 stub, hosted on a `127.0.0.1` loopback port by the CS-11-002 harness, with the `cs:v1:config` mirror in that Redis container. The build routes the suite to `integrationTest` only, and CI runs it as the Integration stage on every pull request. Target paths:
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/support/IntegrationTestSupport.java`:
>   - An abstract JUnit 5 base class carrying `@Tag("integration")`, which every subclass and its `@Nested` classes inherit, and `@ActiveProfiles("integration")`.
>   - Starts one Testcontainers `PostgreSQLContainer` on `postgres:16.6`, with database name `cardservicing`, and one `GenericContainer` on `redis:7.4.2` exposing port `6379`, both held in static fields and started once per test JVM in a static initializer. Every suite extending the base reuses the same two containers, and the suites never stop them; they are removed when the test JVM exits. It writes one start line per image with the image name and container ID.
>   - Does not use `@Testcontainers(disabledWithoutDocker = true)` or any other skip condition. A start failure throws `IllegalStateException` with the message `Integration containers failed to start: <image>` and the Testcontainers exception as its cause, so a missing Docker daemon fails every suite instead of skipping it.
>   - A `@DynamicPropertySource` method binds the CS-11-001 properties of `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` (`card-servicing.db.url`, `card-servicing.db.username`, `card-servicing.db.password`) to the store container's JDBC URL and credentials, read back from the container, and those of `REDIS_HOST` and `REDIS_PORT` (`card-servicing.redis.host`, `card-servicing.redis.port`) to the cache container's host and mapped port. The file holds no credential literal.
>   - Before each test it sends `FLUSHALL` to the Redis container through its own Lettuce connection, so no cache entry crosses tests. It exposes the two container IDs and that connection to subclasses for assertions.
>   - It is the shared base of the Card Data (CS-09-006), Transaction Activity (CS-10-007), Auth (CS-12-005) and Reference Data (CS-13-004) integration suites, which extend it with no change to it.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/config/ConfigIntegrationTest.java`:
>   - Extends `IntegrationTestSupport` and starts a `@SpringBootTest` context under the `integration` profile. It registers `StubHarness.of(new ConfigStubRegistration(), new ProbeStub())` as a static extension, so the Config stub runs under stub name `config` and the CS-11-002 probe under stub name `probe`, each on a `127.0.0.1` port inside the test JVM.
>   - Supplies `integration.stub-ports.config`, the port the harness reports for `config`, through a `@DynamicPropertySource` supplier read when the context starts, so the profile's Config client target resolves to that loopback port.
>   - Marks the context dirty after each test method, so every test begins with a new context: a cold `ConfigSnapshot` holding the built-in values and a new Redis connection.
>   - Drives the context's own `ConfigSnapshot` and `UpstreamCallPolicy` beans. A test request starts a `RequestDeadline` at receipt from the bound `REQUEST_DEADLINE_MS` value, calls `accessAllowed(token)` with a test bearer token, makes one `probe.v1.ProbeService/Echo` call through `UpstreamCallPolicy`, then marks its cut-off and completes, as an endpoint will from W2 on.
>   - Awaits each refresh through its applied, failed or rejected log line, waiting at most 5 s before failing the test. Before each timing case it sends one `PING` through the service's Redis connection, so connection setup does not consume a refresh budget.
>   - Holds the Redis-down case in a `@Nested` class whose context binds `REDIS_HOST` and `REDIS_PORT` to a dedicated `redis:7.4.2` container. The class starts that container, then stops it before the context's first Redis command, so the two shared containers keep running.
> - `card-servicing-service/build.gradle`: keeps the CS-05-001 `integrationTest` task on the `integration` tag only and the `test` task excluding `integration` and `e2e`. It adds the three Testcontainers test dependencies `testcontainers`, `postgresql` and `junit-jupiter` from `gradle/libs.versions.toml` (1.21.3, pinned by CS-05-001) as `testImplementation`, and leaves the version catalog unchanged.
> - `.github/workflows/ci.yml`: adds the Integration stage in the slot that CS-05-001 reserved after Unit. It runs `./gradlew :card-servicing-service:integrationTest` on every pull request, on a runner whose Docker daemon is available to test code, and needs Unit. Validation then needs Integration until the Performance (CS-01-003) and End-to-end (CS-01-002) stages fill their slots.
> - `card-servicing-service/src/test/resources/application-integration.yml`: the integration-profile configuration.
>   - It sets `card-servicing.grpc.port` to `0`, leaves `card-servicing.stub.serve` empty, and sets `card-servicing.stub.latency-ms` and `card-servicing.stub.failure-rate` to `0`, the baseline the per-test overrides replace. It also sets `card-servicing.redis.ssl-enabled` to `false`.
>   - It sets the Config client target `card-servicing.clients.config.target` (CS-11-006) to `127.0.0.1:${integration.stub-ports.config}`, a loopback address with no default port, so a suite that does not supply the harness port fails at startup.
>   - It holds no store or cache host, port or credential, so the container-bound values of the base are the only ones in effect and the CS-11-001 committed-secret scan stays green.
> - Together these are the 5 files touched.
>
> Test approach: every duration assertion except the hook-return bound of AC2 and the deadline bounds of AC5 uses a lower bound only, because a loaded runner stretches measured durations. Failure injection uses the harness's per-test `inject(stubName, latencyMs, failureRate)` override, which the harness clears after each test, and the request log of each stub, which it clears before each test. A case with injected latency ends only after every delayed call has reached its request log, so no late entry lands in the next test's log. Every count in section 2 refers to entries with operation `cardservicing.config.v1.ConfigService/GetConfig` in the Config stub's request log, unless the probe's log is named.
>
> In scope:
>
> - The 5 files above.
> - The shared container base for the five external integration suites.
> - The Config integration suite on a real gRPC channel and a real Redis container.
> - The `integrationTest` dependency wiring.
> - The Integration CI stage.
> - The integration-profile configuration.
>
> Not in this story:
>
> - The other integration suites: Card Data (CS-09-006), Transaction Activity (CS-10-007), Auth (CS-12-005) and Reference Data (CS-13-004).
> - The Performance stage (CS-01-003) and the End-to-end stage (CS-01-002).
> - The Config client and the configuration snapshot logic (CS-11-006).
> - The Config stub and its registration (CS-11-005).
> - Stub hosting, failure injection, the request log, the harness and the probe stub (CS-11-002).
> - The call policy and the request deadline (CS-11-003).
> - The read-through cache, its entry envelope and the Redis connection (CS-11-004).
> - The access guard's call of `accessAllowed(token)` after an allow (CS-12-003), proven through Auth in CS-12-005.
> - The `integrationTest` task registration and the Testcontainers version pins (CS-05-001).
>
> ### 2. Acceptance Criteria
>
> - AC1: `IntegrationTestSupport` starts one `postgres:16.6` and one `redis:7.4.2` container once per test JVM: the Config suite and its nested class read the same two container IDs, and in one `integrationTest` run the base logs exactly one start line per image. The bound `card-servicing.db.url` (`DB_URL`) equals the store container's JDBC URL, and a `PING` through the bound `card-servicing.redis.host` and `card-servicing.redis.port` (`REDIS_HOST`, `REDIS_PORT`) answers `PONG`.
> - AC2: Stub exception (degrade): with a per-test failure-rate override of 1.0 (`STUB_FAILURE_RATE`) on stub `config` and a cold snapshot, `accessAllowed(token)` returns in under 50 ms and the refresh logs its failure with status `UNAVAILABLE`. Receipt reads still return the built-in values 20, 100, 300 and 2, and the Redis container holds no `cs:v1:config` key. A test request made during that refresh ends with outcome `full`, the same as the same run at failure rate 0, so the Config failure leaves the request unaffected and `dataStatus` unchanged (TRD degradation table, Config row).
> - AC3: Latency above the timeout: with a per-test latency override of 400 ms (`STUB_LATENCY_MS`) on stub `config`, above the 300 ms per-attempt timeout (`UPSTREAM_TIMEOUT_MS`), one refresh records exactly 2 `GetConfig` entries once each 400 ms delay has elapsed, each with outcome `CANCELLED`, and each attempt ends with `DEADLINE_EXCEEDED` at the client. The retry reaches the stub at least 350 ms after the first call, a 300 ms attempt plus the 50 ms backoff. No third attempt starts within the refresh's 900 ms budget (300 + 50 + 300 = 650 ms, then 100 + 300 > 250). The refresh logs its failure with status `DEADLINE_EXCEEDED`, and receipt reads still return the held values.
> - AC4: Retry cap: with immediate `UNAVAILABLE` answers (failure rate 1.0) on stub `config`, one refresh records exactly 3 `GetConfig` entries, each with outcome `UNAVAILABLE`: one call plus at most two retries (`UPSTREAM_MAX_RETRIES` = 2). Each retry reaches the stub after the preceding entry, at least 50 ms after it for the first retry and at least 100 ms after it for the second. The call policy retries on `UNAVAILABLE` and `DEADLINE_EXCEEDED` only. A second refresh, launched by the first `accessAllowed(token)` after the first refresh has ended, records exactly 3 more entries.
> - AC5: Deadline: with a per-test latency override of 2,000 ms on stub `config` and 100 ms on stub `probe`, a test request runs on a cold snapshot. It starts its 900 ms request deadline (`RequestDeadline`, `REQUEST_DEADLINE_MS`) at receipt, calls `accessAllowed(token)`, makes one probe call through the call policy and marks its cut-off. Its completion line logs `cutoffMs` ≤ 900, the test caller receives the outcome below 1,000 ms measured from receipt, and the outcome equals that of the same run with the Config latency at 0. The refresh records exactly 2 `GetConfig` entries once each 2,000 ms delay has elapsed, and logs its failure with status `DEADLINE_EXCEEDED`.
> - AC6: Mirror write in real Redis: after a refresh against the Config stub on an empty cache, the Redis container holds exactly one key, `cs:v1:config`, and `TTL cs:v1:config` returns -1 (no stale limit). Its value decodes as the CS-11-004 entry envelope, an 8-byte big-endian `cachedAt` in microseconds since the epoch followed by a UTF-8 JSON object. The `cachedAt` lies between the refresh launch and its applied log line, and the object holds exactly `default_page_size` "20", `max_page_size` "100", `upstream_timeout_ms` "300" and `upstream_max_retries` "2". Neither the key nor the value contains the test bearer token, the snapshot reports its values as fresh, and the applied log line names `GetConfig` as their source.
> - AC7: Mirror read from real Redis: before the first refresh, the test writes an entry under `cs:v1:config` into the Redis container, encoded the same way, with `cachedAt` 100 s before the current time and the values "10", "50", "250" and "1". The first refresh applies those values with 0 `GetConfig` entries, and receipt reads return 10, 50, 250 and 1. The settings provider then hands the call policy a 250 ms timeout and 1 retry, so the next probe call through the policy at a probe failure rate of 1.0 records exactly 2 entries in the probe's request log.
> - AC8: Redis down: in the nested class, with the dedicated Redis container stopped before the context's first Redis command, a refresh treats the refused connection as a miss. It records exactly 1 `GetConfig` entry with outcome `OK`, the snapshot reports the four values as fresh, and the failed mirror write is logged without the values. No exception reaches the caller of `accessAllowed(token)` or of a receipt read, and the two shared containers keep running with unchanged container IDs.
> - AC9: Task separation: `./gradlew :card-servicing-service:integrationTest` runs `ConfigIntegrationTest` and only classes carrying the `integration` tag. `./gradlew :card-servicing-service:test` runs none of them: its report under `card-servicing-service/build/test-results/test/` lists zero `integration`-tagged classes, `ConfigIntegrationTest` included.
> - AC10: CI stage: on every pull request, `.github/workflows/ci.yml` runs the Integration stage with `./gradlew :card-servicing-service:integrationTest` after Unit and before Validation, in the fixed order Unit → Integration → Performance → End-to-end → Validation. The stage needs Unit, and Validation needs Integration. A pull request with one failing integration test fails the gate and skips Validation.
> - AC11: With no Docker daemon reachable (`DOCKER_HOST` set to `tcp://127.0.0.1:1`), `./gradlew :card-servicing-service:integrationTest` exits non-zero. Its report records the Config suite as failed with zero skipped tests, and the failure message names the image whose container start failed, so the gate never passes on skipped integration suites.
>
> ### 3. Dependencies
>
> - CS-11-006 — the Config client and configuration snapshot under test.
>
> Risk: pulling `postgres:16.6` or `redis:7.4.2` on a CI runner can fail or add start time, which fails or slows the Integration stage.

## 4. Design

- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Data model — entity relationship diagram](../../../../data-model.md#entity-relationship-diagram)
- [TRD — test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- [TRD — resilience, degradation table and cache rules](../../../../technical-requirements.md#resilience)
- [TRD — configuration snapshot](../../../../technical-requirements.md#configuration-snapshot)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — D3 stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point)
- [TRD — D5 one Compose stack for both suites](../../../../technical-requirements.md#d5-one-compose-stack-for-both-suites)
- [TRD — environment variables](../../../../technical-requirements.md#environment-variables)
- Contracts: [Config service proto](../../../../../card-servicing-service/src/main/proto/cardservicing/config/v1/config_service.proto)
- Contracts: [Config service OpenAPI](../../../../../card-servicing-service/src/main/resources/openapi/config-service.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. This story adds no unit test: the `test` task stays green with the `integration`-tagged Config suite excluded, and the shared container base and the suite sit in test sources outside the coverage count.
- Integration: Applies. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap. This story adds the Config suite and the stage: the stub exception (AC2), latency above `UPSTREAM_TIMEOUT_MS` (AC3), the `UPSTREAM_MAX_RETRIES` cap (AC4), the deadline (AC5), the real mirror (AC6, AC7) and the Redis outage (AC8), against Testcontainers `postgres:16.6` and `redis:7.4.2` with the stubs on loopback ports inside the test JVM.
- Performance: Not applicable — the Config refresh runs off every request path and no request waits for it; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — this story adds no UI interaction step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — one container base shared per test JVM.

Rationale: containers that the test code starts once and every integration suite reuses keep the integration gate self-contained, while the stubs stay inside the test JVM where per-test failure injection applies.

- Rejected: Design Option B, the relational store and cache from the shared container stack, because it ties the integration gate to the stack that the performance and end-to-end stages own.
- Rejected: the stub containers of the shared container stack, because per-test failure injection needs the stubs inside the test JVM.

Shared by both options:

- Topology: stubs on loopback ports inside the test JVM, with a real relational store and a real cache in containers.
- Naming: every integration suite carries the integration tag, so only the integration task runs it.
- Addressing: the store and cache addresses are bound into the service settings before each application context starts.
- Isolation: per-test overrides are cleared after each test, the cache is emptied before each test, and each test starts a new application context.
- Fallback: none, since a missing container runtime fails the stage instead of skipping it.
- Provisioning: the two images are pinned by tag in one checked-in definition.
- Cut-over: the other four external suites reuse the same containers with no change to the Config suite.
- Failures: each injected failure is asserted through the stub request log and the refresh log line.

### Design Option A

A shared container base in the test code starts one relational store container and one cache container when the first integration suite loads, and every later suite in the same test JVM reuses them. The base binds their addresses into the service settings and empties the cache before each test. The Config suite extends it and runs the configuration client and snapshot against the in-process Config stub over a real remote procedure channel.

How it behaves:

- The first suite to load pays the container start, and every later suite reuses the same two containers.
- The container runtime chooses the ports at start, so parallel runs on one host never collide.
- A start failure fails every suite with a message naming the image, so no suite is skipped.
- The cache-outage case starts a third container of its own and stops it, so the shared pair keeps running.
- The integration stage runs one build command after the unit stage, with no separate stack step.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Containers started once per test JVM by the base (vs once per suite) | one start serves all five suites | a container left dirty by one suite reaches the next |
| 2 | Ports chosen at start and bound into settings (vs fixed ports) | parallel runs on one host never collide | settings are known only after the containers start |
| 3 | A start failure fails the suite (vs skipping it) | the gate never passes on skipped suites | a developer without a container runtime cannot run the suites |

Effort and risk: one base, one suite, one profile and two build edits, with the risk in image pulls on a cold runner.

### Design Option B

A pipeline step starts the relational store and cache services of the shared container stack before the integration task and stops them after it. The suites read fixed addresses from the integration profile, and the stubs still run inside the test JVM. The test code holds no container lifecycle.

How it differs:

- The stack definition that the performance and end-to-end stages own also serves the integration stage.
- Fixed published ports replace ports chosen at start, so two runs on one host collide.
- A local run needs the same start and stop steps around the build command.
- The cache-outage case must stop a cache that every other suite of the run shares.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Store and cache from the shared stack (vs containers started by the test code) | one stack definition serves three stages | a change made for the performance stage can break the integration stage |
| 2 | Fixed ports published by the stack (vs ports chosen at start) | settings are known before the run | two runs on one host collide on the same ports |
| 3 | Pipeline steps start and stop the stack (vs the test code) | the test code holds no container lifecycle | a local run needs two manual steps |

Effort and risk: no base but two pipeline steps and a reduced service list, with the risk in coupling to a stack that the later stages change.

### Recommendation

- Option: Design Option A.
- Why: the integration gate depends only on the build command and a container runtime, and every suite reuses one pair of containers.
- Strongest argument against: every integration run pays the start of two containers, and a cold runner also pays two image pulls.
- Shared items that do not decide the choice: the in-JVM stubs, the request-log assertions, the per-test overrides, the pinned images and the stage order.
- Flip condition: a pull-request runner that offers no container runtime to test code but does run the shared container stack.

## Design Validation

### Option A validation

Settled assumptions:

- The scaffold story's integration task runs only suites carrying the integration tag, and its unit task excludes them.
- The configuration-client story's refresh reads the mirror entry first and runs under its own 900 ms budget.
- The test harness clears the request logs before each test and the per-test overrides after each test.
- A stub call whose client deadline expires during an injected delay is logged as cancelled.
- The read-through cache treats a refused cache connection as a miss.
- The mirror entry holds the four values with their cache time and no expiry.

Open assumptions:

- Open → the first pull-request run settles that the runner offers a container runtime to test code.
- Open → the first pull-request run settles that the mirror read and the channel setup take at most 250 ms on the runner.
- Open → the technical requirements settle the refresh budget, which this suite takes from the configuration-client story as 900 ms from launch.
- Open → the read-through cache story settles a cache command timeout that keeps an outage after connection inside the refresh budget.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the base starts each container in one static step per test JVM |
| AC2 | Holds | the snapshot keeps its held values on every failed refresh |
| AC3 | Conditional | the two-attempt count assumes a mirror read of at most 250 ms |
| AC4 | Conditional | the three-attempt count assumes a mirror read of at most 450 ms |
| AC5 | Conditional | the under-1,000 ms response depends on thread scheduling on the runner |
| AC6 | Holds | a successful refresh writes the mirror entry through the real cache |
| AC7 | Holds | the refresh reads the mirror entry before any configuration read |
| AC8 | Holds | a refused cache connection counts as a miss |
| AC9 | Holds | the integration tag routes the suite to the integration task alone |
| AC10 | Holds | the integration stage fills its reserved slot after the unit stage |
| AC11 | Holds | the base reports a start failure instead of skipping |

### Option B validation

Settled assumptions:

- The scaffold story's integration task runs only suites carrying the integration tag, and its unit task excludes them.
- The shared container stack can start its relational store and cache without the other services.
- The test harness keeps the stubs inside the test JVM.

Open assumptions:

- Open → the stack owners settle that one fixed port set stays free on every runner.
- Open → the first pull-request run settles that the mirror read and the channel setup take at most 250 ms on the runner.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | no base in the test JVM starts or reuses the containers |
| AC2 | Holds | the snapshot keeps its held values on every failed refresh |
| AC3 | Conditional | the two-attempt count assumes a mirror read of at most 250 ms |
| AC4 | Conditional | the three-attempt count assumes a mirror read of at most 450 ms |
| AC5 | Conditional | the under-1,000 ms response depends on thread scheduling on the runner |
| AC6 | Holds | a successful refresh writes the mirror entry through the stack cache |
| AC7 | Holds | the refresh reads the seeded mirror entry before any configuration read |
| AC8 | At risk | stopping the stack cache affects every other suite of the run |
| AC9 | Holds | the integration tag routes the suite to the integration task alone |
| AC10 | Conditional | the stage passes only with the stack steps placed ahead of the task |
| AC11 | Conditional | a missing container runtime fails the stack step instead of the suite |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-11-006 |
| Status | TBD |
| Estimated Files Touched | 5 |
| Issue Type | Story |
| Epic Name | Config Service Integration |
| Epic Link | CS-11 |
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
