# CS-01-002: End-to-end test harness

Parent epic: [CS-01 Account Balance Summary View](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want an HTTP client that stands in for the deferred UI and reads every stub's request log, so that each interaction is verified on the real path.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Directive: testing requirements (end-to-end suite)
>
> Account-data access path: No
>
> External integration or SLA-bound: Yes
>
> Capability: one end-to-end client, the HTTP client that stands in for the deferred Card Servicing UI, calls the four UI-facing REST endpoints of the Compose stack (CS-01-001) as the UI will, and returns with every answer what the request path did behind it: each stub's request-log entries, the service's completion log line with its `cutoffMs`, and the client-measured latency. It is the shared end-to-end component: CS-01-004, CS-02-001, CS-03-001, CS-03-002 and CS-04-001 call it and never re-implement it. `./gradlew :card-servicing-service:e2eTest` runs the suite against the running stack, and CI runs that task as the End-to-end stage. Target paths:
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/e2e/E2eClient.java` (create):
>   - Transport: the JDK `java.net.http` client only, over HTTP/1.1, with a 5,000 ms request timeout so a hung call fails its step instead of stalling the suite. The client sends each call once and never retries. JSON is read with the Jackson `ObjectMapper` that the service's Spring MVC web layer already puts on the test classpath, so the build gains no dependency.
>   - Caller identity: every request carries `authorization: Bearer fixture-token-user0001`, the token of `USER0001`, who is entitled to the sample accounts `00000000001`–`00000000010` (CS-12-001). A step can pass any other fixture token of the Auth stub's identity file for one call, for example `fixture-token-malformed`, `fixture-token-expired` or `fixture-token-admin001`. No token value appears in any log line, assertion message or exception text the client writes.
>   - Latency: each call's client-measured latency runs from just before the request is sent to the moment the full response body has been read, on the JVM monotonic clock (`System.nanoTime()`).
>   - Success answers: a 200 returns the parsed body, the `X-Data-Status` header and the body's `dataStatus`. A header that is absent, outside `FRESH`, `STALE` and `PARTIAL`, or different from the body value fails the step. The body passes through unchanged, JSON `null` descriptions included, so a step sees a `PARTIAL` answer exactly as the UI would.
>   - Error answers: every non-200 becomes the typed error `E2eClient.ApiError`, built from the standard `ErrorObject` and carrying the HTTP status, `code`, `message`, `traceId`, `details` (each entry's `field` and `description`) and the client-measured latency. The status must pair with its code as the status mapping fixes it: 400 `INVALID_ARGUMENT`, 401 `UNAUTHENTICATED`, 403 `PERMISSION_DENIED`, 404 `NOT_FOUND`, 503 `UNAVAILABLE` and 504 `DEADLINE_EXCEEDED`. A status outside that list, a body that is not an `ErrorObject` holding all four properties, an empty `traceId`, or a code that does not pair with its status fails the step with a message naming the status and the request path.
>   - Stub request logs: for each of the five stubs the client reads `GET /internal/stub/request-log` (CS-11-002) on the stub's published web port and parses its body, `{"stub":…,"entries":[{"sequence":…,"operation":…,"outcome":…,"durationMs":…}]}`. The `stub` value must equal the expected name, `card-data`, `transaction-activity`, `config`, `reference-data` or `auth`; a different name, a non-200 or an unparseable body fails the step. Before each call the client records each stub's highest `sequence`, and after the call it returns, per stub, the entries with a higher `sequence`, or an empty list for a stub with none. It records the same marks at each step start, so a step can also read every entry recorded since it began.
>     > SCOPE-QUESTION (Resolved): Does every entry above a call's mark belong to that call, and is every entry the call caused listed by the time the client returns it? Not when the window closes at the answer. An allowed call launches its background Config refresh before its answer, never waits for it, and the refresh runs under its own 900 ms deadline from its launch (CS-11-006), so its `GetConfig` entry can be listed after the answer, inside a later call's window. A stub assigns `sequence` at receipt, numbering its calls 1 to n with no gap (CS-11-002 AC6), but lists an entry only when its call closes (CS-11-002), so an entry can appear after a higher one, behind a mark already taken, and a received call still open when the log is read leaves no gap among the listed numbers. Resolution: after each answer, outside the client-measured latency, the client reads all five stub logs every 100 ms until both at least 1,000 ms have passed since the answer and, in one read of each stub log, every `sequence` from 1 to the `lastSequence` that read reports is listed (CS-11-002), so no call the stub has received is still open. The 1,000 ms floor holds because every attempt of a background refresh starts inside the refresh's own 900 ms deadline, which starts before the answer of the call that launched it, so by then the stub has received every attempt and given it a `sequence`. A body without `lastSequence`, or one listing a `sequence` above it, fails the step as an unparseable body does. The call's entries are those above its marks in that settled read, and that same read gives the next call's marks and a following step-start's marks, so a refresh is listed with the call that launched it, its last attempt included, and never in a later call's window, whatever test class ran before. The first call of a run takes its marks from a read settled the same way. A log not settled 5,000 ms after the answer fails the step with a message naming the stub and the lowest unlisted `sequence`. The service never waits for a refresh: only the test client waits, between calls.
>     >
>     > SCOPE-QUESTION (Resolved): Can a step read a stub's whole request log? CS-04-001 AC7 needs each stub's whole log, entries written before the flow included, while the windows above return only entries newer than a call's or a step's marks. Resolution: the client also exposes `E2eClient.wholeRequestLog(String stub)`, which returns every entry the named stub's log lists, in `sequence` order, from a read settled as above, with the same `stub`-name, status and JSON checks as every other read. A name other than `card-data`, `transaction-activity`, `config`, `reference-data` or `auth` fails the step with a message naming it. The class gains a method and no file, so the 4 files touched stand.
>
> > SCOPE-QUESTION (Resolved): An entry may also carry `accountId`, the account of its request, which the request log adds only to entries whose request names one, for example a Transaction Activity `ListTransactions` entry or a Card Data or Auth entry whose request has `account_id` (CS-11-002). The client reads each entry as a JSON tree with the Jackson `ObjectMapper` of the transport bullet, takes `sequence`, `operation`, `outcome` and `durationMs` and ignores any other property, so an added property never fails a step, while an entry missing one of those four, a non-200 or an unparseable body still fails it. The request-log parsing cases of `E2eClientTest` prove it: the AC2 loopback routes answering entries 1–3 before a call and 1–5 after it, with every entry also carrying `accountId` `00000000001`, parse to the same entries, so the call still returns exactly entries 4 and 5 with the same operation, outcome and `durationMs`. This story therefore gains no file, and the 4 files touched stand.
>
>   - Retried calls: an operation that appears more than once among one stub's new entries for one call is listed in that answer's retried calls with its count. The client reports retried calls and never fails a step on them.
>   - Completion log line: the client reads the service container's log with `docker compose -f tests/docker-compose.yml logs --no-color --no-log-prefix card-servicing-service`, started through the JDK process API, and parses the structured completion line that `RequestDeadline` writes (CS-11-003) for its `traceId`, outcome and `cutoffMs`, ignoring every other line. An error answer is matched to its line by `traceId`. A 200 carries no trace identifier, so it is matched to the one completion line added since the call was sent; calls are serialized through the client, so exactly one new line belongs to each call. The client polls every 100 ms for up to 2,000 ms after the response. No matching line, more than one new line for a 200, a non-zero exit of the log command or a line without `cutoffMs` fails the step. The log source is a constructor argument that defaults to that command, so the unit test supplies lines directly.
>     > SCOPE-QUESTION (Resolved): The command above names the stack file by a path relative to the repository root, but a test worker of `e2eTest` runs with the module directory `card-servicing-service` as its working directory, where `tests/docker-compose.yml` names no file, so the command would fail on every call. Resolution: the default log source names the stack file by the absolute path in the `cardservicing.e2e.compose-file` system property that the `build.gradle` edit below passes. The suite base reads that property, which it already requires, and builds the command `docker compose -f <compose file> logs --no-color --no-log-prefix card-servicing-service`, where the compose file is that absolute path, so the command reads the same file whatever the working directory. A property value that is not an absolute path to an existing file fails the suite before its first test, naming the property. The stack start and stop commands of the End-to-end CI stage, which run from the repository root, are unchanged. No file or dependency is added, so the 4 files touched stand.
>   - Deadline check: on every call the client fails the step when the matched `cutoffMs` is above 900 or the client-measured latency is 1,000 ms or more, so no step can skip the deadline boundary.
>   - Suite base: the nested class `E2eClient.Suite` is the JUnit 5 extension that `e2e`-tagged test classes register with `@ExtendWith(E2eClient.Suite.class)`. It builds one client per test run from the system properties below, injects it as a test-method parameter, and marks each test method's start as a step start. A missing or empty property fails the suite before its first test, naming the property.
> - `card-servicing-service/src/test/java/com/example/cardservicing/e2e/E2eClientTest.java` (create):
>   - Untagged unit cases run the client against the JDK `com.sun.net.httpserver` server on a loopback port, standing in for the service and the five stub log routes, with completion lines supplied through the log-source argument. They cover latency capture, the typed-error mapping for each of the six statuses, the `X-Data-Status` check, request-log parsing and the per-call split, retried-call reporting, completion-line matching for errors and 200s, and the deadline check.
>   - One nested class tagged `e2e`, `E2eClientTest.OnStack`, runs the client against the running stack for the checks in AC3 and AC5, so `test` skips it and `e2eTest` runs it.
> - `card-servicing-service/build.gradle` (edit; created by CS-05-001): the declared `e2eTest` task keeps running only the `e2e` tag and gains the stack addresses as system properties: `cardservicing.e2e.service-url` `http://localhost:8080`, `cardservicing.e2e.stub.card-data` `http://localhost:8081`, `cardservicing.e2e.stub.transaction-activity` `http://localhost:8082`, `cardservicing.e2e.stub.config` `http://localhost:8083`, `cardservicing.e2e.stub.reference-data` `http://localhost:8084`, `cardservicing.e2e.stub.auth` `http://localhost:8085`, and `cardservicing.e2e.compose-file`, the absolute path of `tests/docker-compose.yml`. The task is never up to date (`outputs.upToDateWhen { false }`), because its result depends on the running stack and not only on its inputs. `test` keeps excluding `e2e`.
> - `.github/workflows/ci.yml` (edit; created by CS-05-001): fills the End-to-end slot with a stage that runs on every pull request after the tool setup CS-05-001 defines: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down` in a step marked `if: always()`, so the stack stops also when a step before it fails. The fixed order stays Unit → Integration → Performance → End-to-end → Validation, each stage needing the one before. This story lands before CS-01-003, so End-to-end needs Integration until CS-01-003 fills the Performance slot and re-points End-to-end to need Performance. Validation needs End-to-end from this story on.
> - Together these are the 4 files touched.
>
> In scope:
>
> - The end-to-end client and its typed errors.
> - The stub request-log and completion-log readers.
> - The deadline check applied to every call.
> - The suite base extension.
> - The `e2eTest` wiring.
> - The End-to-end CI stage.
>
> Not in this story:
>
> - The flow steps (CS-01-004, CS-02-001, CS-03-001, CS-03-002, CS-04-001).
> - The Compose stack (CS-01-001).
> - The performance runner (CS-01-003).
> - Stub request-log recording and its web route (CS-11-002).
> - The completion log line itself (CS-11-003).
>
> ### 2. Acceptance Criteria
>
> - AC1: Every request the client sends carries `authorization: Bearer fixture-token-user0001` unless the step supplies another fixture token for that call, and every answer carries the client-measured latency from send to the full response body. Against the unit test's loopback server, which records each request's headers and writes the response body 300 ms after its headers, the recorded header equals that value, a call given `fixture-token-user0002` carries that token instead, and the reported latency is at least 300 ms. No assertion message or exception text the client produces contains a token value.
> - AC2: For each call, and for each step since it began, the client returns, for all five stubs, every request-log entry (operation, outcome, `durationMs`) whose `sequence` is above the mark recorded at that call's send or at the step's start. Against loopback stub log routes that answer entries 1–3 before a call and entries 1–5 after it, the call returns exactly entries 4 and 5. A route answering an empty `entries` array yields an empty list, not an error. A route answering stub name `config` where `auth` is expected, a route answering 500, and a route answering a body that is not JSON each fail the step.
>   > SCOPE-QUESTION (Resolved): The criterion also covers the settled windows and the whole-log read, against the loopback stub log routes: a `GetConfig` entry the Config route starts listing 600 ms after an allowed call's answer is returned among that call's Config entries, and the next call, a 401, returns an empty Config list; a Config route listing entries 1–3 with `lastSequence` 4 until 1,200 ms after the answer of an allowed call marked at 3, and 1–4 from then on, makes that call return Config entry 4, and the next call, a 403, returns an empty Config list; a Card Data route listing entries 1–3 and 5 with `lastSequence` 5 after a call marked at 3, and 1–5 from 400 ms later, makes that call return entries 4 and 5 and gives the next call mark 5; a Card Data route that keeps listing 1–3 with `lastSequence` 4 fails the step 5,000 ms after the answer with a message naming `card-data` and `sequence` 4; a route body without `lastSequence`, and one listing `sequence` 5 with `lastSequence` 4, each fail the step as a body that is not JSON does; a call answered at once by the loopback service reports a latency below 100 ms although its window closes at least 1,000 ms after the answer; `wholeRequestLog("config")` against a route listing entries 1–4 before the run's first call returns all four, and `wholeRequestLog("billing")` fails the step naming `billing`.
> - AC3: On the stack, `GET /v1/accounts/00000000001/balance-summary` with `fixture-token-malformed` returns 401, surfaced as a typed error with `code` `UNAUTHENTICATED` and a non-empty `traceId`, and the Card Data, Transaction Activity and Reference Data entries for that call are empty lists.
> - AC4: Against the loopback server, a 400, 401, 403, 404, 503 and 504, each answered with an `ErrorObject` whose `code` pairs with its status, each surface as a typed error carrying status, `code`, `message`, `traceId` and `details`, and the 400's `details` entry `accountId` with description `Account number must be a non zero 11 digit number` arrives intact. A 503 answered with `code` `NOT_FOUND`, a 500, and a 404 whose body is not JSON each fail the step with a message naming the status and the request path. A 200 exposes `X-Data-Status` and the body's `dataStatus`: `STALE` in both passes, header `FRESH` with body `STALE` fails the step, and a 200 without the header fails the step.
> - AC5: The client returns each call's `cutoffMs` from its completion log line, matched by `traceId` for an error and, for a 200, as the one completion line added since the call was sent, and it fails the step when `cutoffMs` is above 900 or the client-measured latency is 1,000 ms or more. Against supplied lines, `cutoffMs` 900 passes and 901 fails the step, a line arriving 1,500 ms after the response is matched, no line within 2,000 ms fails the step, and two new lines for one 200 fail the step. On the stack, `GET /v1/accounts/00000000001/balance-summary` with the default token returns 200 with a matched line whose `cutoffMs` is at most 900 and a client-measured latency below 1,000 ms, and the 401 of AC3 is matched to the line carrying its `traceId`.
> - AC6: `./gradlew :card-servicing-service:e2eTest` runs only `e2e`-tagged tests, `E2eClientTest.OnStack` included, with the seven system properties set to the stack addresses of section 1, and a second run with no change runs the tests again instead of reporting the task up to date. With `cardservicing.e2e.service-url` removed, the task fails before its first test with an error naming that property. `./gradlew :card-servicing-service:test` runs the untagged `E2eClientTest` cases and no `e2e`-tagged test.
>   > SCOPE-QUESTION (Resolved): The criterion also covers the log command: an untagged `E2eClientTest` case, run by `test` with the module directory as its working directory, builds the default log source from a `cardservicing.e2e.compose-file` value naming a stack file the case writes under its temporary directory, and the command list that source holds is exactly `docker`, `compose`, `-f`, that absolute path, `logs`, `--no-color`, `--no-log-prefix`, `card-servicing-service`, with no command run. Given the value `tests/docker-compose.yml`, or an absolute path to no file, the suite base fails before it builds a client, with an error naming `cardservicing.e2e.compose-file`, so an `e2eTest` run with either value fails before its first test.
> - AC7: On every pull request, the End-to-end stage of `.github/workflows/ci.yml` runs after Integration, after Performance once CS-01-003 lands, and before Validation, which needs it. The stage runs `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`, and the stop step also runs when a step before it fails. A pull request with one failing `e2e`-tagged test fails the gate and skips Validation, and a pull request whose stack start fails also fails the gate.
> - AC8: Under the stack defaults the service runs the upstream call policy with these values: each attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget, and at most two retries (`UPSTREAM_MAX_RETRIES`) follow, after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, each starting only when its backoff plus a full 300 ms attempt fits before the 900 ms (`REQUEST_DEADLINE_MS`) cut-off. The harness reports any stub operation logged more than once for one call as a retried call with its count, and surfaces each degraded or failed outcome to the step unchanged: a 200 with `STALE`, a 200 with `PARTIAL` and every description JSON `null`, a 503 and a 504. Against the loopback server, a call whose Card Data log gains two `cardservicing.carddata.v1.CardDataService/GetAccount` entries reports one retried call with count 2, a `PARTIAL` body keeps its `null` descriptions, and a 503 and a 504 each surface as a typed error after exactly one request from the client. The outcome of each upstream call under injected latency and failure, Auth 401, 503 or 504 failing closed, Card Data `STALE` or 503 or 504, Reference Data `PARTIAL`, Transaction Activity 503 or 504, and Config leaving the request unaffected, is proven by CS-11-003 and the integration suites CS-09-006, CS-10-007, CS-11-007, CS-12-005 and CS-13-004, not by this suite, which runs with failure injection off.
>   > SCOPE-QUESTION (Resolved): AC8's closing list names one outcome per service, while each outcome the harness surfaces follows the row of one upstream call in the degradation table in [TRD — Resilience](../../../../technical-requirements.md#resilience). `ValidateToken` gives 401 for a missing, invalid, expired or malformed token, otherwise 503 or 504. `AuthorizeAccountAccess` gives 403 for `allowed=false` and 503 or 504 for a failed call. Both fail closed, with no account data read from upstream or cache. `GetAccount` and `ListCategoryBalances` serve a cached entry within its 300 s max stale age as `STALE`, otherwise 503 or 504. `GetCardCrossReference` gives 503 or 504 even when the transaction came from cache. `ListTransactionTypes` and `ListTransactionCategories` serve a cached entry within its max stale age, otherwise the response is 200 `PARTIAL` with their descriptions JSON `null`. `ListTransactions` fails hard with 503 or 504, and a page is never served stale. `GetTransaction` serves a usable cached transaction, one within its 300 s max stale age (CS-10-006), as `STALE`, otherwise 503 or 504. `GetConfig` leaves the request unaffected. The injected proofs stay with CS-11-003 and the five integration suites AC8 names, with CS-08-002 for the `GetTransaction` and `GetCardCrossReference` outcomes of the transaction detail. This suite runs with failure injection off and surfaces each outcome unchanged, and AC8's loopback cases are unchanged.
>
> ### 3. Dependencies
>
> - CS-01-001 — the Compose stack the suite runs against.
>
> Risk: reading container-hosted stub logs depends on the log access CS-11-002 provides, and reading completion lines depends on the line format CS-11-003 writes.

## 4. Design

- [Sequence — balance summary](../../../../architecture/sequence-balance-summary.mmd)
- [Sequence — category balances](../../../../architecture/sequence-category-balances.mmd)
- [Sequence — transaction list](../../../../architecture/sequence-transaction-list.mmd)
- [Sequence — transaction detail](../../../../architecture/sequence-transaction-detail.mmd)
- [Physical deployment](../../../../architecture/physical-deployment.mmd)
- [Data model — fixture rules](../../../../data-model.md#fixture-rules)
- [TRD — Test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- [TRD — Deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- Contracts:
  - [card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
  - [card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
  - [common.proto](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)
  - [auth-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/auth-service.yaml) (fixture tokens)
  - [auth_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto) (fixture tokens)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Applies — the untagged `E2eClientTest` cases cover latency capture, the typed-error mapping for the six statuses, the `X-Data-Status` check, request-log parsing and the per-call split, retried-call reporting, completion-line matching and the deadline check against a loopback server. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
- Integration — Not applicable — the story adds no stub client. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — CS-01-003 owns the runner. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50/p95/p99 below 1,000 ms over successful requests with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end — Applies — the suite base and `E2eClientTest.OnStack` run here. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call, with every call's `cutoffMs` ≤ 900 and client-measured latency below 1,000 ms.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A, a typed wrapper over the built-in HTTP client.

Rationale: one client checks each answer, its stub log entries and completion line.

- Rejected: Design Option B — no code generator is pinned.
- Rejected: a browser-driven test — no user interface exists yet.

### Shared by both options

- Topology: tests use the stack's published ports.
- Naming: logs are read by stub name; mismatches fail.
- Addressing: the build passes every address and the stack file.
- Isolation: serial calls, as the default fixture user unless a step names another.
- Fallback: none; a missing log, line or error body fails.
- Provisioning: the gate starts and always stops the stack.
- Cut-over: a real interface replaces the client; real services swap in by address and credential.
- Failures: typed errors, reported retries and unchanged degraded outcomes.

### Design Option A — the Java runtime's built-in HTTP client with a small typed wrapper

A hand-written wrapper sends calls through the built-in client and parses answers with the existing JSON reader. It is also every flow step's suite base.

How it behaves:

- Latency spans send to the last body byte.
- Per-call split: a call owns log entries above its send-time mark.
- Settled window: closes after at least one second, once every log lists each numbered call.
- Completion-line pairing: errors by trace identifier, successes by the one new line.
- Deadline check: a cut-off above 900 ms or latency of 1,000 ms or more fails.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Hand-written wrapper (vs generated client) | the build gains no generator | the mapping stays manual |
| 2 | Stack-tool log read (vs a service log route) | the service stays unchanged | the host needs the stack tool |
| 3 | Success pairing by new completion line (vs a trace header) | the contract stays unchanged | calls must run serially |
| 4 | Split by entry number (vs by clock time) | clock skew cannot move the split | a stub restart resets numbering |
| 5 | Deadline check in the client (vs per step) | no step can skip it | no step can relax it |
| 6 | Log windows closed at log settlement (vs closed at the answer) | a background refresh stays in its originating call window | each call waits at least one second |

Effort and risk: small, four files; the risk is mapping drift.

### Design Option B — a client generated from the HTTP interface description

A code generator builds typed classes from the interface description. A thin layer adds the rest of Option A.

How it differs:

- Contract changes fail the build.
- Null descriptions, transport and latency follow generated code.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Generated client (vs hand-written wrapper) | contract drift breaks the build | no generator is pinned |
| 2 | Generated error type (vs hand-written typed error) | error fields follow the contract | status-to-code pairing stays manual |
| 3 | Stack-tool log read (vs a service log route) | the service stays unchanged | the host needs the stack tool |

Effort and risk: medium, five files; the risk is the dependency table.

### Recommendation

- Option: Design Option A.
- Why: it adds no unpinned dependency.
- Strongest argument against: silent drift between the mapping and the contract.
- Shared items that do not decide the choice: the shared block, log readers, entry split and deadline check.
- Flip conditions: a pinned generator, or a contract outgrowing the steps' reads.

## Design Validation

### Option A

Settled assumptions:

- Each stub numbers calls on receipt, lists each on close and reports its latest number.
- Error answers and each request's one completion line, with its cut-off, carry the trace identifier.
- Each refresh attempt starts inside its 900 ms deadline, begun before the launching answer.

Open assumptions:

- Completion line format Open → the upstream call policy story's line.
- Stub log reads Open → the stub hosting story's route per port.
- Answer-to-line delay Open → the first stack run measures the two-second wait.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the wrapper adds the default token and timing |
| AC2 | Conditional | stub ports must serve the logs |
| AC3 | Holds | authentication rejects the malformed token first |
| AC4 | Holds | each listed status maps to a typed error |
| AC5 | Conditional | the parser must match the line format |
| AC6 | Holds | a dedicated task runs the tagged suite |
| AC7 | Holds | the end-to-end stage fills its reserved slot |
| AC8 | Holds | the wrapper reports retries beside unchanged outcomes |

### Option B

Settled assumptions:

- Option A's log assumptions hold; the interface description covers every read answer.

Open assumptions:

- Generator choice Open → a pinned dependency table entry.
- Null description handling Open → a trial generation.
- Completion line format Open → as under Option A.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a request hook adds the default token and timing |
| AC2 | Conditional | stub ports must serve the logs |
| AC3 | Holds | authentication rejects the malformed token first |
| AC4 | Conditional | the generated error type needs every field |
| AC5 | Conditional | the parser must match the line format |
| AC6 | At risk | the generator plugin changes the shared tagged-test build |
| AC7 | Holds | the end-to-end stage fills its reserved slot |
| AC8 | At risk | generated null decoding can hide a partial answer |

## Metadata

| Field | Value |
|---|---|
| Build wave | W3 |
| Depends on | CS-01-001 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Account Balance Summary View |
| Epic Link | CS-01 |
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
