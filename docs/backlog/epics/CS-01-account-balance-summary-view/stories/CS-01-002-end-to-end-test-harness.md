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
>
> > SCOPE-QUESTION (Resolved): An entry may also carry `accountId`, the account of its request, which the request log adds only to entries whose request names one, for example a Transaction Activity `ListTransactions` entry or a Card Data or Auth entry whose request has `account_id` (CS-11-002). The client reads each entry as a JSON tree with the Jackson `ObjectMapper` of the transport bullet, takes `sequence`, `operation`, `outcome` and `durationMs` and ignores any other property, so an added property never fails a step, while an entry missing one of those four, a non-200 or an unparseable body still fails it. The request-log parsing cases of `E2eClientTest` prove it: the AC2 loopback routes answering entries 1–3 before a call and 1–5 after it, with every entry also carrying `accountId` `00000000001`, parse to the same entries, so the call still returns exactly entries 4 and 5 with the same operation, outcome and `durationMs`. This story therefore gains no file, and the 4 files touched stand.
>
>   - Retried calls: an operation that appears more than once among one stub's new entries for one call is listed in that answer's retried calls with its count. The client reports retried calls and never fails a step on them.
>   - Completion log line: the client reads the service container's log with `docker compose -f tests/docker-compose.yml logs --no-color --no-log-prefix card-servicing-service`, started through the JDK process API, and parses the structured completion line that `RequestDeadline` writes (CS-11-003) for its `traceId`, outcome and `cutoffMs`, ignoring every other line. An error answer is matched to its line by `traceId`. A 200 carries no trace identifier, so it is matched to the one completion line added since the call was sent; calls are serialized through the client, so exactly one new line belongs to each call. The client polls every 100 ms for up to 2,000 ms after the response. No matching line, more than one new line for a 200, a non-zero exit of the log command or a line without `cutoffMs` fails the step. The log source is a constructor argument that defaults to that command, so the unit test supplies lines directly.
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
> - AC3: On the stack, `GET /v1/accounts/00000000001/balance-summary` with `fixture-token-malformed` returns 401, surfaced as a typed error with `code` `UNAUTHENTICATED` and a non-empty `traceId`, and the Card Data, Transaction Activity and Reference Data entries for that call are empty lists.
> - AC4: Against the loopback server, a 400, 401, 403, 404, 503 and 504, each answered with an `ErrorObject` whose `code` pairs with its status, each surface as a typed error carrying status, `code`, `message`, `traceId` and `details`, and the 400's `details` entry `accountId` with description `Account number must be a non zero 11 digit number` arrives intact. A 503 answered with `code` `NOT_FOUND`, a 500, and a 404 whose body is not JSON each fail the step with a message naming the status and the request path. A 200 exposes `X-Data-Status` and the body's `dataStatus`: `STALE` in both passes, header `FRESH` with body `STALE` fails the step, and a 200 without the header fails the step.
> - AC5: The client returns each call's `cutoffMs` from its completion log line, matched by `traceId` for an error and, for a 200, as the one completion line added since the call was sent, and it fails the step when `cutoffMs` is above 900 or the client-measured latency is 1,000 ms or more. Against supplied lines, `cutoffMs` 900 passes and 901 fails the step, a line arriving 1,500 ms after the response is matched, no line within 2,000 ms fails the step, and two new lines for one 200 fail the step. On the stack, `GET /v1/accounts/00000000001/balance-summary` with the default token returns 200 with a matched line whose `cutoffMs` is at most 900 and a client-measured latency below 1,000 ms, and the 401 of AC3 is matched to the line carrying its `traceId`.
> - AC6: `./gradlew :card-servicing-service:e2eTest` runs only `e2e`-tagged tests, `E2eClientTest.OnStack` included, with the seven system properties set to the stack addresses of section 1, and a second run with no change runs the tests again instead of reporting the task up to date. With `cardservicing.e2e.service-url` removed, the task fails before its first test with an error naming that property. `./gradlew :card-servicing-service:test` runs the untagged `E2eClientTest` cases and no `e2e`-tagged test.
> - AC7: On every pull request, the End-to-end stage of `.github/workflows/ci.yml` runs after Integration, after Performance once CS-01-003 lands, and before Validation, which needs it. The stage runs `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`, and the stop step also runs when a step before it fails. A pull request with one failing `e2e`-tagged test fails the gate and skips Validation, and a pull request whose stack start fails also fails the gate.
> - AC8: Under the stack defaults the service runs the upstream call policy with these values: each attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget, and at most two retries (`UPSTREAM_MAX_RETRIES`) follow, after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, each starting only when its backoff plus a full 300 ms attempt fits before the 900 ms (`REQUEST_DEADLINE_MS`) cut-off. The harness reports any stub operation logged more than once for one call as a retried call with its count, and surfaces each degraded or failed outcome to the step unchanged: a 200 with `STALE`, a 200 with `PARTIAL` and every description JSON `null`, a 503 and a 504. Against the loopback server, a call whose Card Data log gains two `cardservicing.carddata.v1.CardDataService/GetAccount` entries reports one retried call with count 2, a `PARTIAL` body keeps its `null` descriptions, and a 503 and a 504 each surface as a typed error after exactly one request from the client. The outcome of each upstream call under injected latency and failure, Auth 401, 503 or 504 failing closed, Card Data `STALE` or 503 or 504, Reference Data `PARTIAL`, Transaction Activity 503 or 504, and Config leaving the request unaffected, is proven by CS-11-003 and the integration suites CS-09-006, CS-10-007, CS-11-007, CS-12-005 and CS-13-004, not by this suite, which runs with failure injection off.
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

Recommended: Design Option A — a small typed wrapper over the Java runtime's built-in HTTP client.

Rationale: a test client that speaks the web interface the deferred user interface will use, and reads the stub request logs and the service's completion log lines beside each answer, lets every flow step check the answer, the upstream calls behind it and the cut-off in one place.

- Rejected: Design Option B, a client generated from the HTTP interface description — it adds a code generator that the dependency table does not list.
- Rejected: a browser-driven test — no user interface exists this iteration.

### Shared by both options

- Topology: the tests run on the build host and reach the service and the five stubs through the stack's published host ports.
- Naming: each stub log is read under the stub's stack name, and an answer naming another stub fails the step.
- Addressing: the build hands the suite the service address, the five stub log addresses and the stack file location, and a missing address stops the suite before its first step.
- Isolation: each call runs as the first regular fixture user unless the step names another fixture identity, each step reads only the log entries recorded since it began, and calls run one at a time so each answer pairs with one completion log line.
- Fallback: the client has no fallback path, so a missing stub log, a missing completion log line or an unreadable error body fails the step instead of being skipped.
- Provisioning: the continuous integration gate starts the stack before the suite and stops it afterwards, a failed run included.
- Cut-over: a real user interface replaces the client and not the steps, and the real services replace the stubs by address and credential alone.
- Failures: each error answer surfaces as a typed error, and stale, partial, unavailable and timed-out outcomes reach the step unchanged.

### Design Option A — the Java runtime's built-in HTTP client with a small typed wrapper

A hand-written wrapper sends each call through the Java runtime's built-in HTTP client and reads the answer with the JSON reader that the service's web layer already carries. It turns every error answer into one typed error built from the standard error object, and pairs every answer with the stub log entries and the completion log line it produced. The same wrapper is the suite base that every flow step registers.

How it behaves:

- Each call measures latency from send to the last byte of the answer body.
- A success answer exposes the data-status header beside the body's data status, and a mismatch fails the step.
- An error answer becomes a typed error carrying status, code, message, trace identifier and field details, and a code that does not match its status fails the step.
- Before each call the wrapper notes the latest entry number of every stub log, and after the answer it returns only the newer entries.
- An operation that appears more than once among one call's entries is reported as a retried call.
- An error answer finds its completion log line by trace identifier, and a success answer takes the one new line written since the call was sent.
- Every call fails its step when the cut-off came after 900 ms or the answer took 1,000 ms or more.
- The completion log source is swappable, so the unit test feeds lines without a running stack.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Hand-written wrapper (vs generated client) | no generator joins the build | the field mapping is kept by hand |
| 2 | Service log read through the stack tool (vs a log route on the service) | the service gains no test-only route | the build host needs the stack tool |
| 3 | Success answers paired with the one new completion line (vs a trace header on every answer) | the interface contract stays unchanged | calls must run one at a time |
| 4 | Stub entries split by entry number (vs by clock time) | clock skew between containers cannot move the split | a stub restart resets the numbering |
| 5 | Deadline check inside the client (vs one check per flow step) | no flow step can skip the deadline boundary | a step cannot relax the check for one call |

Effort and risk: small effort across four files, with drift between the hand-written mapping and the interface contract as the main risk.

### Design Option B — a client generated from the HTTP interface description

A code generator reads the HTTP interface description at build time and emits typed request and answer classes, and a thin layer adds the stub log reader, the completion log reader and the deadline check. Error answers map to the generated error type. Everything else matches Option A.

How it differs:

- Request and answer types follow the interface description, so a contract change shows up as a build failure.
- The build gains a generator plugin and its runtime library, neither of which the dependency table lists.
- Descriptions that are either text or null depend on how the generator handles such fields.
- The generated client brings its own transport unless it is set to use the Java runtime's built-in client.
- Latency is measured around the generated call, so it includes the generated decoding.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Generated client (vs hand-written wrapper) | contract drift breaks the build | it adds a generator absent from the dependency table |
| 2 | Generated error type (vs hand-written typed error) | error fields follow the contract | the status-to-code pairing still needs hand-written checks |
| 3 | Service log read through the stack tool (vs a log route on the service) | the service gains no test-only route | the build host needs the stack tool |

Effort and risk: medium effort across five files, at risk on the dependency table, which names no generator.

### Recommendation

- Option: Design Option A.
- Why: it needs nothing outside the pinned dependency table and keeps the client small enough that each flow step reads plainly.
- Strongest argument against: the hand-written mapping can drift from the interface contract with no build failure to flag it.
- Shared items that do not decide the choice: the published host ports, the stub log route, the completion log reader, the per-step entry split, the default fixture identity and the stack start and stop in the continuous integration gate.
- Flip conditions: a code generator joining the dependency table, or the interface contract growing past what the flow steps read.

## Design Validation

### Option A

Settled assumptions:

- Each stub in container mode serves its request log on its published web port, with numbered entries in receipt order.
- The service writes exactly one completion log line per request, carrying the trace identifier, the outcome and the cut-off time.
- Every error answer carries the standard error object with a trace identifier.
- The flow steps send one call at a time.
- The stack publishes the service and every stub on fixed host ports.
- The JSON reader already sits on the service's test classpath.

Open assumptions:

- Completion log line format Open → the line the upstream call policy story writes, read by this parser.
- Request-log reads for container-hosted stubs Open → the web route of the stub hosting story on each published port.
- Delay between an answer and its completion log line Open → the two-second wait, measured on the first stack run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the wrapper sets the default token and times every call |
| AC2 | Conditional | the stub log route must answer on each published port |
| AC3 | Holds | the malformed fixture token fails at the authentication stage before any data read |
| AC4 | Holds | the wrapper maps every listed status to one typed error |
| AC5 | Conditional | the completion log line format must match the parser |
| AC6 | Holds | the build routes the tagged suite to its own task |
| AC7 | Holds | the end-to-end stage fills its reserved slot |
| AC8 | Holds | the wrapper reports repeated operations and passes outcomes through unchanged |

### Option B

Settled assumptions:

- Each stub in container mode serves its request log on its published web port, with numbered entries in receipt order.
- The service writes exactly one completion log line per request, carrying the trace identifier, the outcome and the cut-off time.
- The HTTP interface description covers every answer the flow steps read.

Open assumptions:

- Generator choice Open → a pinned generator added to the dependency table.
- Handling of descriptions that are either text or null Open → a trial generation against the interface description.
- Completion log line format Open → the line the upstream call policy story writes, read by this parser.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a request hook adds the default token and times every call |
| AC2 | Conditional | the stub log route must answer on each published port |
| AC3 | Holds | the malformed fixture token fails at the authentication stage before any data read |
| AC4 | Conditional | the generated error type must carry every field of the standard error object |
| AC5 | Conditional | the completion log line format must match the parser |
| AC6 | At risk | the generator plugin changes the build that the tagged tasks share |
| AC7 | Holds | the end-to-end stage fills its reserved slot |
| AC8 | At risk | generated decoding of null descriptions can hide a partial answer |

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
