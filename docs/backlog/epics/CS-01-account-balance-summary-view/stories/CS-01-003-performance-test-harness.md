# CS-01-003: Performance test harness

Parent epic: [CS-01 Account Balance Summary View](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a performance engineer, I want a load runner that measures p50, p95 and p99 per endpoint, so that the 1-second SLA is proven at the planning baseline.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Directive: testing requirements (performance harness)
>
> Account-data access path: No
>
> External integration or SLA-bound: Yes
>
> Capability: one load runner drives the four UI-facing REST endpoints of the Compose stack (CS-01-001) with ten concurrent virtual users, computes p50, p95 and p99 itself over the successful calls, reads every stub's request log for per-call durations, and fails the run when a scenario misses the 1,000 ms threshold. It is the shared performance component: the SLA scenarios CS-01-005 (balance summary), CS-02-002 (category balances), CS-03-003 (transaction list, including the D4 filter shapes) and CS-04-002 (transaction detail) add their scenario classes under `tests/performance/src/main/java/com/example/cardservicing/performance/scenarios/` and never re-implement the runner. `./gradlew :card-servicing-service:performanceTest` runs every scenario against the running stack, and CI runs that task as the Performance stage. Target paths:
>
> - `tests/performance/src/main/java/com/example/cardservicing/performance/LoadRunner.java` (create):
>   - Scenario definition: the nested record `LoadRunner.Scenario` carries the scenario name, the endpoint path template with its query parameters, the targets of each regular identity, and the optional per-stub-operation conditions of AC7. A target is one account with the remaining path and query values its call fills, for example a transaction identifier for transaction detail or the `typeCode` and `categoryCode` filters of a D4 shape.
>
>     SCOPE-QUESTION (Resolved): Can a scenario give each of an identity's two virtual users its own targets, or check what an answer holds, when the components above carry neither? Yes. `LoadRunner.Scenario` carries two further optional components: a target assignment, `LoadRunner.TargetAssignment.SHARED` by default or `LoadRunner.TargetAssignment.SPLIT`, resolved beneath the virtual-user bullet, and a per-response check, `LoadRunner.ResponseCheck`, none by default, resolved beneath the entry-point bullet. A scenario that sets neither runs exactly as this section states: CS-04-002 sets both, and CS-01-005, CS-02-002 and CS-03-003 set neither and keep their behaviour.
>
>   - Load shape: the runner, not the scenario, holds the number of virtual users and of calls per user, so every scenario under `performanceTest` runs the shape the task sets, 10 users × 100 calls.
>   - Virtual users: a fixed pool of platform threads, one per virtual user, released together by one start latch. User k runs as regular identity number ⌈k / 2⌉, so users 1 and 2 run as `USER0001` with `fixture-token-user0001`, users 3 and 4 as `USER0002` with `fixture-token-user0002`, and so on to users 9 and 10 as `USER0005` with `fixture-token-user0005` (CS-12-001). Each user sends its calls in sequence, the next only after the previous response body has been read in full, with no pause between calls, cycling through its identity's targets in order.
>
>     SCOPE-QUESTION (Resolved): Do both virtual users of an identity walk the same targets? Under the default shared assignment, yes, unchanged: users 2n − 1 and 2n of identity n each walk that identity's whole list from its first entry, in order, cycling. Under split assignment the runner cuts each identity's list into two contiguous halves of equal length: user 2n − 1, the identity's first user, walks the first half from its first entry, and user 2n walks the second half from its first entry, so with distinct targets in the list no target of one user is ever sent by the other. Split assignment requires every list of the scenario to hold an even number of targets whose half is at least the calls per user, so a split user never wraps around its half and never repeats a target. A split list of odd length, or with a half shorter than the calls per user, fails the run before any request reaches the service, with an error naming the scenario and the identity, beside the checks of AC3. The entitlement check covers every target of both halves as before.
>
>   - Transport: the JDK `java.net.http.HttpClient` only, one client per virtual user, over HTTP/1.1 with a 5,000 ms request timeout, so a hung call ends as a failed call instead of stalling the run. Each call carries `authorization: Bearer` with its user's fixture token and is sent once, never retried by the runner. No token value appears in any report line, assertion message or exception text.
>   - Latency: from just before the request is sent to the moment the full response body has been read, on the JVM monotonic clock (`System.nanoTime()`), held in microseconds.
>   - Entitlement check: before the first request, the runner reads the packaged Auth fixture identity file `fixtures/auth-identities.yaml` (CS-12-001) from the classpath with the SnakeYAML parser that Spring Boot already brings, and checks that every target account lies inside a range of the identity that calls it, so the stub's own file stays the one entitlement source.
>   - Stub request logs: for each of the five stubs the runner reads `GET /internal/stub/request-log` (CS-11-002) on the stub's published web port and checks that the body's `stub` value equals the expected name, `card-data`, `transaction-activity`, `config`, `reference-data` or `auth`. It records each stub's highest `sequence` at scenario start and takes the entries above it at scenario end. Scenarios run one after another, never overlapping, so every entry between the two marks belongs to that scenario. Per stub operation it derives the call count, the p99 of `durationMs` and the retry count, counted as the entries whose outcome is `UNAVAILABLE`, `DEADLINE_EXCEEDED` or `CANCELLED`: the call policy retries only after `UNAVAILABLE` or `DEADLINE_EXCEEDED`, and a stub logs an attempt whose client deadline expired as `CANCELLED`.
>   - Entry points: `LoadRunner.fromSystemProperties()` builds the runner from the `performanceTest` system properties below and fails naming the first missing or empty one. `run(scenario)` returns the scenario result, every call's status, `X-Data-Status` value and latency plus the stub figures, and throws `AssertionError` naming the scenario and the failed condition when the scenario fails. Each scenario class is a JUnit 5 test class whose test method passes its `LoadRunner.Scenario` to `run`.
>
>     SCOPE-QUESTION (Resolved): What ties each call's status, data status and latency to the user and target that sent it, and how does a scenario check what an answer holds? The result carries one immutable call record per call, `LoadRunner.CallRecord`, holding the user number (1–10), the regular identity the user runs as, the call index (1 to the calls per user, 1–100 under the task's shape), the target the call was sent for, the request path it sent with its query values, the status, the `X-Data-Status` value (empty when the answer carries none) and the latency in microseconds. Each virtual user keeps its own records, and the runner joins them once every user has finished, ordered by user number and then call index, whatever order the calls completed in. A scenario's per-response check receives, for each 200 answer, the call's target and the full response body as text. The runner calls it on the user's thread after the latency mark, so checking stays outside the measured latency, and the user's next call starts as soon as the check returns. A check that rejects an answer, by returning a reason or by throwing, fails the scenario with an `AssertionError` naming the scenario, the user number, the call index, the request path and the reason, never a token value. The runner keeps no response body once its check has returned, so a run holds 1,000 records and no bodies, and a scenario without a check runs as before.
>
> - `tests/performance/src/main/java/com/example/cardservicing/performance/PercentileCalculator.java` (create): exact nearest-rank percentiles. For n sorted samples, percentile p is the sample at 1-based rank ⌈p × n / 100⌉. It serves both the call latencies and the stub durations, and an empty sample set raises `IllegalArgumentException`.
> - `tests/performance/src/main/java/com/example/cardservicing/performance/PerformanceReportWriter.java` (create): writes each scenario's report lines (AC5) to standard output, which `performanceTest` shows in the task output, and to `card-servicing-service/build/reports/performance/<scenario name>.txt`, one file per scenario named after it. Latencies and durations are printed in milliseconds with three decimals.
> - `tests/performance/src/test/java/com/example/cardservicing/performance/PerformanceHarnessTest.java` (create): untagged unit cases run by `test`. They cover the percentile calculator, and run the runner and the report writer against the JDK `com.sun.net.httpserver` server on a loopback port, standing in for the service and the five stub log routes, with a report folder under the test's temporary directory.
> - `card-servicing-service/build.gradle` (edit; created by CS-05-001):
>   - A `performance` source set rooted at `../tests/performance/src/main/java`, compiled against the module's main output and runtime classpath and the JUnit 5 artifacts the Spring Boot BOM manages, so the build gains no dependency.
>   - The test source set gains `../tests/performance/src/test/java` and the performance output, so `test` runs `PerformanceHarnessTest`. Scenario classes sit only in the performance source set, so `test` never runs a scenario.
>   - The registered `performanceTest` task runs the performance source set's test classes one class at a time with JUnit parallel execution off, shows standard output, and is never up to date (`outputs.upToDateWhen { false }`), because its result depends on the running stack. It passes nine system properties: `cardservicing.performance.service-url` `http://localhost:8080`, `cardservicing.performance.stub.card-data` `http://localhost:8081`, `cardservicing.performance.stub.transaction-activity` `http://localhost:8082`, `cardservicing.performance.stub.config` `http://localhost:8083`, `cardservicing.performance.stub.reference-data` `http://localhost:8084`, `cardservicing.performance.stub.auth` `http://localhost:8085`, `cardservicing.performance.users` `10`, `cardservicing.performance.calls-per-user` `100` and `cardservicing.performance.report-dir`, the absolute path of `card-servicing-service/build/reports/performance`.
>   - With no scenario class present, `performanceTest` completes successfully with zero scenarios, as CS-05-001 registered it.
> - `.github/workflows/ci.yml` (edit; created by CS-05-001): fills the Performance slot with a stage that runs on every pull request: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down` in a step marked `if: always()`, so the stack stops also when a step before it fails. Performance needs Integration (CS-11-007), and End-to-end (CS-01-002) is re-pointed from Integration to need Performance, so the fixed order Unit → Integration → Performance → End-to-end → Validation holds, each stage needing the one before.
> - Together these are the 6 files touched.
>
> In scope:
>
> - The load runner, its scenario definition and the virtual users.
> - The percentile computation.
> - The per-scenario report.
> - The stub request-log reading and the per-operation figures.
> - The `performanceTest` wiring.
> - The Performance CI stage.
>
> Not in this story:
>
> - The scenario definitions (CS-01-005, CS-02-002, CS-03-003, CS-04-002).
> - The Compose stack (CS-01-001) and the synthetic volume (CS-09-007, CS-10-008).
> - Stub request-log recording and its web route (CS-11-002).
> - The completion log line and its `cutoffMs` (CS-11-003).
> - Injected-failure scenarios (the integration suites and endpoint deadline criteria named in AC8).
>
> ### 2. Acceptance Criteria
>
> - AC1: Every request the runner sends goes through the JDK `java.net.http.HttpClient` over HTTP/1.1 and carries `authorization: Bearer` with its user's fixture token, and the latency it records runs from send to the full response body. Against the unit test's loopback server, which records each request's protocol version and headers and writes the response body 300 ms after its headers, every recorded request is HTTP/1.1 with the expected token and every recorded latency is at least 300 ms. This story leaves `gradle/libs.versions.toml` unchanged, and `./gradlew :card-servicing-service:dependencies --configuration performanceRuntimeClasspath` lists no artifact outside the module's `runtimeClasspath` other than the JUnit 5 artifacts the Spring Boot BOM manages, so no load-testing tool or library is added.
> - AC2: Every scenario runs the task's load shape, 10 concurrent virtual users × 100 sequential calls, 1,000 calls: two users per regular identity `USER0001`–`USER0005`, each calling only the targets the scenario lists for its identity. Against the loopback server, a 10 × 100 run records exactly 1,000 requests, 200 per fixture token, each for an account inside the ranges that `fixtures/auth-identities.yaml` gives that token's subject, with at most 10 requests in flight at any moment and at most 2 per token.
>
> SCOPE-QUESTION (Resolved): Which unit cases prove the target assignment resolved beneath the virtual-user bullet? Two 10 × 100 runs against the loopback server. With split assignment and 200 distinct targets per identity, the server records 1,000 requests on 1,000 distinct targets, and in the result the call records of user 2n − 1 name entries 1–100 of identity n's list and those of user 2n name entries 101–200, each in list order, so no target appears under both user numbers of an identity. The two users of an identity carry one token, so the user numbers of the call records, not the tokens, show the split. With the default shared assignment and 100 targets per identity, the server records 200 requests per identity, each of its 100 targets twice, and the call records of both users name the same 100 targets in list order, as before.
>
> - AC3: Each of these fails the run before any request reaches the service, with an error naming the cause, and leaves the loopback server's request record empty: a load shape of 0 users, of 0 calls per user, or of 11 users, which exceeds two users for each of the five regular identities; a target account outside its identity's ranges, for example `00000000011` for `USER0001`; a scenario with no target for an identity the load shape uses; and a missing or empty system property, for example `cardservicing.performance.service-url`.
>
> SCOPE-QUESTION (Resolved): Does a split list that cannot give each user its own targets also fail before any request? Yes. Under split assignment with 100 calls per user, a `USER0002` list of 199 targets, an odd length, and a `USER0002` list of 198 targets, whose halves of 99 are shorter than the calls per user, each fail the run before any request reaches the service, with an error naming the scenario and `USER0002`, and leave the loopback server's request record empty.
>
> - AC4: Percentiles are exact nearest-rank values over the sorted latencies of 200 responses only. The unit test with samples 1 ms … 100 ms, supplied in shuffled order, yields p50 = 50 ms, p95 = 95 ms and p99 = 99 ms. A single sample yields that value for all three, and an empty sample set raises `IllegalArgumentException` and fails the scenario.
> - AC5: The report of each scenario lists the scenario name, calls, successes, p50, p95, p99 and max, the 200 count for each `X-Data-Status` value `FRESH`, `STALE` and `PARTIAL`, and for each stub operation its call count, p99 duration and retry count read from the stub request logs. It appears in the task output and in a file named after the scenario under the report folder, both with the same lines. Against loopback stub log routes answering Card Data entries 1–3 at scenario start and 1–8 at its end, where entries 4–8 are `cardservicing.carddata.v1.CardDataService/GetAccount` with durations 10, 20, 30, 40 and 50 ms and outcomes `OK`, `OK`, `OK`, `OK` and `UNAVAILABLE`, the report shows call count 5, p99 duration 50.000 ms and retry count 1 for that operation. A stub log route answering another stub's name, a route answering 500, and a route answering a body that is not JSON each fail the scenario.
> - AC6: A scenario fails when any of p50, p95 or p99 is 1,000 ms or more, or when any response is not 200, a call that reaches the 5,000 ms request timeout included, and `performanceTest` exits non-zero when any scenario fails. Evaluated on supplied latencies, a p99 of exactly 1,000.000 ms fails and one of 999.999 ms passes. Against the loopback server, one 503 among the calls fails the scenario with a message naming the status and the request path. `./gradlew :card-servicing-service:performanceTest` runs with the nine system properties of section 1, a second run with no change runs the scenarios again instead of reporting the task up to date, and with no scenario class present it completes successfully. `./gradlew :card-servicing-service:test` runs `PerformanceHarnessTest` and no scenario.
>
> SCOPE-QUESTION (Resolved): Which unit cases prove the call records and the per-response check resolved beneath the entry-point bullet? Four cases against the loopback server. First, a 10 × 100 shared run against a server that answers each target whose account ends in an odd digit 50 ms late with `X-Data-Status` `STALE`, and every other target at once with `FRESH`, so calls complete out of send order: the result holds 1,000 call records ordered by user number, then call index, each record's target and request path are those its user sent at that call index, and `STALE` stands on exactly the records whose account ends in an odd digit, each of them with a latency of at least 50 ms. Second, a 10 × 100 run whose lists alternate unfiltered targets and targets carrying a `typeCode` value, grouped by whether a record's target carries the filter, gives 500 records in each group, and each record's request path carries `typeCode` exactly when its target does. Third, a 10 × 100 split run with 200 distinct targets per identity and a check that compares an identifier in each body with its target's identifier, against a server that answers entry 137 of `USER0002`'s list, user 4's call 37, with another identifier, fails the scenario with a message naming the scenario, user number 4, call index 37 and that call's request path and carrying no token value, while the same run with every identifier matching passes. Fourth, in a 10 × 100 run against a server that answers at once, a check that blocks for 300 ms on each user's first answer leaves every recorded latency below 300 ms.
>
> - AC7: A scenario may declare, per stub operation, a p99 duration limit and a no-`DEADLINE_EXCEEDED`-retry condition, and CS-03-003 sets a 150 ms limit and the condition on `cardservicing.transactionactivity.v1.TransactionActivityService/ListTransactions`. Evaluated on supplied entries, a p99 of 150 ms passes the 150 ms limit and one of 151 ms fails it. The condition fails the scenario when any entry of that operation carries outcome `CANCELLED` or `DEADLINE_EXCEEDED` or lasts 300 ms or more, the marks an attempt leaves when the service abandons it at its per-attempt timeout. A declared limit or condition on an operation with no entry in the scenario's window fails the scenario, so no condition passes without evidence.
> - AC8: Every run keeps the stack defaults with failure injection off, `STUB_LATENCY_MS` 0 and `STUB_FAILURE_RATE` 0 on each stub (CS-01-001), and the service runs the upstream call policy with these values: each attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget, and at most two retries (`UPSTREAM_MAX_RETRIES`) follow, after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, each starting only when its backoff plus a full 300 ms attempt fits before the 900 ms (`REQUEST_DEADLINE_MS`) cut-off. The runner sends each call once and never retries, so the percentiles measure the service's own policy: against the loopback server, a call answered 503 is recorded once and fails the scenario. Injected-failure outcomes, Auth failing closed with 401, 503 or 504, Card Data degrading to `STALE` or failing with 503 or 504, Reference Data degrading to `PARTIAL`, Transaction Activity failing hard with 503 or 504, and Config leaving the request unaffected, are excluded from the percentiles. They are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms in the integration suites CS-09-006, CS-10-007, CS-11-007, CS-12-005 and CS-13-004 and in the endpoint deadline criteria of CS-05-005, CS-06-002, CS-07-002 and CS-08-002.
>
> SCOPE-QUESTION (Resolved): Do AC8's Card Data, Reference Data and Transaction Activity outcomes apply alike to every read of each system? No. Each read follows its own row of the TRD degradation table, applied when the call fails after retries or is unfinished at the cut-off. On Card Data, a `GetAccount` or `ListCategoryBalances` failure serves the cached entry as a 200 marked `STALE` while it is within its 300 s max stale age, otherwise 503 or 504, and a `GetCardCrossReference` failure always returns 503 or 504, because cross-references are never cached. On Reference Data, a `ListTransactionTypes` or `ListTransactionCategories` failure serves the cached list as a 200 marked `STALE` while it is within the 86,400 s max stale age that CS-13-003 sets, and with no usable list returns a 200 marked `PARTIAL` with the missing descriptions `null`, never an error. On Transaction Activity, a `ListTransactions` failure always returns 503 or 504, because a page is never served stale, and a `GetTransaction` failure serves the cached transaction as a 200 marked `STALE` while its entry is within the 300 s max stale age that CS-10-006 sets, otherwise 503 or 504. Each of these outcomes, every degraded 200 included, stays outside the percentiles and is proven by the integration suites and endpoint deadline criteria AC8 names, with a logged `cutoffMs` of at most 900 ms and a client-measured response below 1,000 ms.
>
> - AC9: On every pull request, the Performance stage of `.github/workflows/ci.yml` runs after Integration, which it needs, and before End-to-end, which now needs Performance. The stage runs `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`, and the stop step also runs when a step before it fails. A pull request with one failing scenario fails the gate and skips End-to-end and Validation, and a pull request whose stack start fails also fails the gate.
>
> ### 3. Dependencies
>
> - CS-01-001 — the Compose stack with synthetic volume the runner drives.
>
> Risk: shared CI runner capacity can inflate percentiles independently of the service.

## 4. Design

- [Physical deployment](../../../../architecture/physical-deployment.mmd)
- [Sequence — balance summary](../../../../architecture/sequence-balance-summary.mmd)
- [Sequence — category balances](../../../../architecture/sequence-category-balances.mmd)
- [Sequence — transaction list](../../../../architecture/sequence-transaction-list.mmd)
- [Sequence — transaction detail](../../../../architecture/sequence-transaction-detail.mmd)
- [Data model — synthetic volume](../../../../data-model.md#synthetic-volume)
- [TRD — Test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- [TRD — D4 Transaction-to-account access](../../../../technical-requirements.md#d4-transaction-to-account-access)
- Contracts:
  - [card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
  - [card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Applies — the untagged `PerformanceHarnessTest` cases cover the nearest-rank percentiles, the load-shape, entitlement and property checks, the shared and split target walks and the split-list checks, the request loop and its concurrency bounds, the call records under out-of-order completion and their grouping by target, the per-response check and its exclusion from the latency, the stub log split and per-operation figures, the report and the threshold and per-operation conditions, against a JDK `com.sun.net.httpserver` loopback server. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
- Integration — Not applicable — the story adds no stub client. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Applies — this story builds the suite's runner and its CI stage, and the scenarios arrive with CS-01-005, CS-02-002, CS-03-003 and CS-04-002. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50/p95/p99 below 1,000 ms over successful requests with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms in the suites AC8 names.
- End-to-end — Not applicable — CS-01-002 owns the suite. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — a fixed pool of virtual users with exact percentiles taken from sorted samples.

Rationale: one runner that every scenario story reuses, driving the stack as ten concurrent users would and reading each stub request log beside the timings, proves the one-second threshold per endpoint and gives the per-call stub figures the transaction filter test needs in one report.

- Rejected: Design Option B, fixed-width latency buckets — bucket edges lose the exact value at the one-second threshold.
- Rejected: an external load-testing tool — it adds a dependency the pinned dependency table does not list.

### Shared by both options

- Topology: the runner runs on the build host and reaches the service and the five stubs through the stack's published host ports.
- Naming: each scenario carries one name, and its output lines and its report file carry that name.
- Addressing: the build hands the runner the service address, the five stub log addresses, the load shape and the report folder, and a missing value stops the run before its first request.
- Isolation: each virtual user calls only accounts entitled to its regular fixture identity, and scenarios run one after another so each stub log window belongs to one scenario.
- Fallback: the runner has no fallback path, so a failed call, an unreadable stub request log or a missed threshold fails the scenario.
- Provisioning: the continuous integration gate starts the stack before the run and stops it afterwards, a failed run included.
- Cut-over: the real services replace the stubs by address and credential alone, and the runner keeps its timing and thresholds unchanged.
- Failures: injected failures stay out of the percentile run and are proven by the integration suites and the endpoint deadline tests.

### Design Option A — fixed pool of virtual users with exact sorted-sample percentiles

Ten worker threads, one per virtual user, start together and each send their calls one after another through the Java runtime's built-in HTTP client. Every successful call's latency is kept, and the median, 95th and 99th percentile are read from the sorted samples by nearest rank. The same calculation turns the stub request log durations into a 99th percentile per stub operation.

How it behaves:

- Two virtual users share each regular fixture identity and by default both walk its whole target list, while a scenario that asks for split targets gives each user one half of that list.
- Every call is timed from send to the last byte of the answer body.
- Each call leaves one record of its user, call position, target, status, data status header and time, and the records come back in user and call order.
- A scenario may pass an answer check, which reads each successful answer after its timing stops, and a rejected answer fails the scenario with that call named.
- The median, 95th and 99th percentile are the samples at the nearest-rank positions of the sorted latencies.
- A scenario fails when any of the three reaches one second or any answer is not a success.
- Stub request log entries recorded between the scenario's start and end are grouped by operation into a count, a 99th percentile duration and a retry count.
- A scenario may set a duration limit and a no-timeout-retry condition per stub operation, and a breach of either fails it.
- Each scenario writes its report to the task output and to one report file.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Exact sorted samples (vs latency buckets) | the value at the threshold is exact | every sample stays in memory until the scenario ends |
| 2 | One worker thread per virtual user (vs an open arrival rate) | the call count per user is fixed | the arrival rate follows the service's speed |
| 3 | One client per virtual user (vs one shared client) | each user keeps its own connections | the build host holds ten connection pools |
| 4 | Entitlements read from the authentication fixture file (vs a copied account list) | the stub's file stays the single entitlement source | the runner depends on that file's layout |
| 5 | Scenarios run one at a time (vs scenarios in parallel) | each stub log window belongs to one scenario | the run lasts as long as all scenarios together |
| 6 | Halves of one identity list on request (vs a separate list per user) | each scenario keeps one target list per identity | a split list must hold an even count |
| 7 | An answer check per call (vs answer bodies held in the result) | the result holds one record per call and no answer body | each check runs between two calls of its user |

Effort and risk: medium effort across six files, with shared runner capacity inflating the percentiles as the main risk.

### Design Option B — histogram buckets

The same ten virtual users send the same calls, but each latency lands in a fixed-width bucket of one millisecond up to a ceiling instead of being kept. The percentiles are read from the cumulative bucket counts, and stub durations use the same buckets. Everything else matches Option A.

How it differs:

- Memory stays flat whatever the call count.
- A percentile is reported as a bucket edge, so the value at the threshold carries the bucket width as error.
- A latency above the top bucket lands in an overflow bucket with no exact value.
- The bucket width and ceiling become settings that every scenario shares.
- Stub duration limits are judged against bucket edges.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Fixed-width buckets (vs exact sorted samples) | memory stays flat as calls grow | the threshold value loses its exactness |
| 2 | One-millisecond width (vs coarser buckets) | the error stays within one millisecond | a fine width needs many buckets |
| 3 | Overflow bucket above the ceiling (vs a growing bucket set) | the bucket set stays fixed | slow outliers lose their exact value |

Effort and risk: medium effort across six files, at risk on the exact nearest-rank values the unit test fixes.

### Recommendation

- Option: Design Option A.
- Why: a thousand samples per scenario are small enough to sort, and exact values decide a pass or a fail at the one-second threshold.
- Strongest argument against: every sample of a scenario is held in memory until the scenario ends.
- Shared items that do not decide the choice: the published host ports, the stub request log reading, the identity mapping, the target split, the call records, the answer check, the load shape, the report per scenario and the stack start and stop in the continuous integration gate.
- Flip conditions: call counts far beyond the planning baseline, or runs long enough that holding every sample strains the build host.

## Design Validation

### Option A

Settled assumptions:

- Each stub in container mode serves its stub request log on its published web port, with numbered entries in receipt order and a duration on every entry.
- The stack runs with failure injection off, so every call reaches the service and its stubs unaltered.
- Five regular fixture identities each hold ten sample accounts and a two-thousand-account synthetic slice.
- The synthetic volume is loaded before the stack reports ready.
- Ten worker threads and their connections fit on one build host.

Open assumptions:

- Runner capacity on the shared continuous integration host Open → repeated runs on that host compared against a run on an idle machine.
- Per-call durations in container-hosted stub logs Open → the duration field of the stub hosting story, read on the first stack run.
- Stub log outcome of a service-side attempt timeout Open → the cancelled outcome the stub hosting story records for an expired call.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | every call goes through the Java runtime's built-in HTTP client |
| AC2 | Holds | the identity mapping gives each virtual user the shared list or its own half |
| AC3 | Holds | the load shape, entitlement and split-list checks run before the first request |
| AC4 | Holds | nearest rank over sorted samples yields the exact expected values |
| AC5 | Conditional | the stub request log route must answer on each published port |
| AC6 | Holds | the threshold check compares exact values against one second, with each failed or rejected call named from its user's own records |
| AC7 | Conditional | a service-side attempt timeout must show in the stub log as a cancelled entry |
| AC8 | Holds | the run keeps the stack defaults with failure injection off |
| AC9 | Holds | the performance stage fills its reserved slot |

### Option B

Settled assumptions:

- Each stub in container mode serves its stub request log on its published web port, with numbered entries in receipt order and a duration on every entry.
- The bucket width and ceiling are fixed before the first scenario runs.
- Success-path latencies fall below the bucket ceiling.

Open assumptions:

- Bucket error at the threshold Open → a comparison of bucket edges against exact values on one recorded run.
- Runner capacity on the shared continuous integration host Open → repeated runs on that host compared against a run on an idle machine.
- Per-call durations in container-hosted stub logs Open → the duration field of the stub hosting story, read on the first stack run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | every call goes through the Java runtime's built-in HTTP client |
| AC2 | Holds | the identity mapping matches Option A |
| AC3 | Holds | the load shape, entitlement and split-list checks run before the first request |
| AC4 | Breaks | bucket edges cannot yield the exact nearest-rank values the unit test fixes |
| AC5 | Conditional | the stub request log route must answer on each published port |
| AC6 | At risk | a bucket edge can place a sub-second value at one second |
| AC7 | At risk | the duration limit is judged against a bucket edge |
| AC8 | Holds | the run keeps the stack defaults with failure injection off |
| AC9 | Holds | the performance stage fills its reserved slot |

## Metadata

| Field | Value |
|---|---|
| Build wave | W3 |
| Depends on | CS-01-001 |
| Status | TBD |
| Estimated Files Touched | 6 |
| Issue Type | Story |
| Epic Name | Account Balance Summary View |
| Epic Link | CS-01 |
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
