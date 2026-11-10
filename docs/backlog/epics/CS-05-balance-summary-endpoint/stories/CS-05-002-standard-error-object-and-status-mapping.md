# CS-05-002: Standard error object and status mapping

Parent epic: [CS-05 Balance Summary Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a UI developer, I want every service error in one standard error object carrying a trace identifier, so that each failure maps to one screen message.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Directive: standard error object
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: one code table, a central REST error handler with a writer for request filters, a gRPC error interceptor and a REST trace-id filter, which together render every service failure as the standard `ErrorObject` on both protocols. Every endpoint, guard and client story raises typed failures that name a code-table row, a message and any field at fault; none of those stories builds an error body itself. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/error/ErrorCode.java` holds the single code table. Each row pairs a code string with its HTTP status and its gRPC status code:
>   - `INVALID_ARGUMENT`: HTTP 400, gRPC `INVALID_ARGUMENT`.
>   - `UNAUTHENTICATED`: HTTP 401, gRPC `UNAUTHENTICATED`.
>   - `PERMISSION_DENIED`: HTTP 403, gRPC `PERMISSION_DENIED`.
>   - `NOT_FOUND`: HTTP 404, gRPC `NOT_FOUND`.
>   - `UNAVAILABLE`: HTTP 503, gRPC `UNAVAILABLE`.
>   - `DEADLINE_EXCEEDED`: HTTP 504, gRPC `DEADLINE_EXCEEDED`.
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/error/ApiErrorHandler.java` is the central REST controller advice. It also provides the error writer that request filters use, because Stage 0 validation and the account access guard reject requests before any controller runs. It renders `ErrorObject` JSON with `code`, `message`, `traceId` and `details`, where `details` is always present and empty when no field is at fault.
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/error/GrpcErrorInterceptor.java` is a gRPC server interceptor. It converts the same typed failures into a `google.rpc.Status` whose code matches the table and whose details hold exactly one packed `cardservicing.common.v1.ErrorObject`. It also issues the gRPC call's `traceId` at receipt.
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/error/TraceIdFilter.java` issues the REST request's `traceId` at receipt, ordered before any other filter. It places the value in the logging context so that every log line of the request carries it, and removes it when the request completes.
> - Tests in `card-servicing-service/src/test/java/com/example/cardservicing/config/error/`: `ApiErrorHandlerTest.java`, `GrpcErrorInterceptorTest.java` and `TraceIdFilterTest.java`.
> - Together these are the 7 files touched.
>
> In scope:
>
> - The code table.
> - REST rendering from controllers and filters.
> - gRPC rendering with packed details.
> - `traceId` issue and logging.
> - The paging `details` rule.
>
> Not in this story:
>
> - Which failures each endpoint raises (endpoint stories).
> - Parameter rules (CS-05-003 and the later validation stories).
> - The retry and deadline logic that decides between `UNAVAILABLE` and `DEADLINE_EXCEEDED` (CS-11-003).
> - Access decisions (CS-12-003).
> - Screen messages (the deferred UI run).
>
> ### 2. Acceptance Criteria
>
> - AC1: For each of the six rows of the code table, a REST request whose handler raises that failure returns the row's HTTP status with a body whose `code` is the row's string. The same failure over gRPC returns the row's gRPC status code. An access decision with `allowed=false` produces the `PERMISSION_DENIED` row.
> - AC2: Every REST error body validates against the `ErrorObject` schema in `card-servicing-api.yaml`. `code`, `message` and `traceId` are present, and `details` is an array, empty when no field is at fault.
> - AC3: A paging `INVALID_ARGUMENT` returns 400 with exactly one `details` entry whose `field` is `pageToken`.
> - AC4: For a gRPC error, the `google.rpc.Status` details contain exactly one packed `cardservicing.common.v1.ErrorObject`. Its `code`, `message` and `trace_id` equal the REST body produced for the same failure.
> - AC5: Each request receives its `traceId` at receipt as a 32-character lower-case hexadecimal value, the form of the contract example `4bf92f3577b34da6a3ce929d0e0e4736`. 1,000 sequential requests yield 1,000 distinct values, and the `traceId` in an error body equals the value on that request's log lines.
> - AC6: `code` is a JSON string in REST and a proto `string` in gRPC, and no error body carries a numeric code.
> - AC7: No error body or log line contains the bearer token, the `x-api-key` value or a stack trace.
> - AC8: A failure raised in a request filter ahead of any controller returns the same status and `ErrorObject` body as the same failure raised in a controller.
>
> ### 3. Dependencies
>
> - CS-05-001 — provides the build, the generated ErrorObject classes from the shared proto root and the unit test task.
>
> Risk: A response path that bypasses the central handler, such as the servlet container's default error page, would return a body outside the standard error object.

## 4. Design

- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd)
- [Data model — entity relationship diagram](../../../../data-model.md#entity-relationship-diagram)
- [TRD — request path](../../../../technical-requirements.md#request-path)
- [Shared error contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is ≥80% per module. Cases: one per code-table row on each protocol, schema validation of the error body, a filter-raised failure, `traceId` uniqueness and log presence, and token absence.
- Integration: Not applicable — this story calls no upstream service. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — no endpoint ships here, and error paths sit outside the success-path percentiles. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no endpoint ships here. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — one central HTTP error handler and one remote procedure error interceptor over a single code table.

Rationale: one code table read by one HTTP writer and one remote procedure interceptor gives every failure the same status, code text and trace identifier on both protocols and at every stage of a request.

- Rejected: Option B, translation inside each endpoint — four endpoints repeat the mapping and drift apart.
- Rejected: plain remote procedure status text with no typed details — callers would parse free text to find the code.

### Shared by both options

- Topology: one error object shared by both protocols and defined in the shared contract.
- Naming: codes are the six status names written as text.
- Addressing: each request gets its trace identifier at receipt on either protocol.
- Isolation: no token, key or stack trace reaches an error body or a log line.
- Fallback: failures outside the six codes are an open item.
- Provisioning: nothing beyond the build.
- Cut-over: later stories raise typed failures and never build bodies.
- Failures: unavailable after retries gives 503, and an expired deadline gives 504.

### Design Option A — central handler and interceptor

One code table pairs each of the six status names with its HTTP status and its remote procedure status. A central HTTP error handler renders every failure that a controller raises, and the same handler supplies the writer that request filters use when they reject a request before any controller runs. A remote procedure interceptor converts the same failures into a rich status whose details hold one packed error object. Each request receives its trace identifier at receipt on either protocol.

How it behaves:

- Later stories raise a typed failure that names one table row, a message and any field at fault.
- The HTTP handler and the filter writer both read the code table and render one error body shape.
- The interceptor reads the same table, sets the matching remote procedure status and packs one error object into its details.
- A paging rejection carries exactly one field entry, and that entry names the page token.
- The trace identifier enters the logging context at receipt, so success and error log lines of one request share it.
- The writer drops stack traces and copies no request header, so no token or key reaches a body or a log line.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One code table for both protocols (vs one per protocol) | both protocols return the same code text | the table is a shared change point |
| 2 | Central HTTP handler with a filter writer (vs per-controller handlers) | every HTTP error passes one place | messages cannot be tailored per controller |
| 3 | Error object packed into rich status details (vs plain status text) | callers unpack a typed object | callers need the shared contract to decode |
| 4 | Trace identifier issued at receipt (vs issued when an error occurs) | success and error log lines share one identifier | every request pays the issue cost |

Effort and risk: one story of four shared components and their tests, with the risk in a response path that bypasses the central handler.

### Design Option B — translation inside each endpoint

Each endpoint catches its own failures and renders its own error body, and each remote procedure method sets its status inline. Request filters that reject a request before any controller runs render their own bodies. Each endpoint issues a trace identifier when it begins its work.

How it differs:

- The status mapping is copied into four endpoints and into every request filter.
- Each remote procedure method packs its own error object into the status details.
- A rejection before any endpoint runs carries no trace identifier.
- A change to the mapping touches every copy.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Each endpoint catches and renders its own failures (vs central handler) | local control of messages | four copies of the mapping |
| 2 | Filter rejections render their own bodies (vs shared writer) | no shared writer needed | filter bodies drift from controller bodies |
| 3 | Remote procedure methods set status inline (vs interceptor) | no interceptor ordering | details packing repeated per method |
| 4 | Trace identifier issued per endpoint (vs at receipt) | issue only where used | early rejections carry no identifier |

Effort and risk: less shared code in this story and a repeated mapping in every endpoint story, with the risk in copies that drift and in early rejections without a trace identifier.

### Recommendation

- Option: Option A.
- Why: one table and one writer make every status identical across protocols and stages.
- Strongest argument against: a central handler hides endpoint context from messages.
- Shared items that do not decide the choice: the error object shape, the six codes, the trace identifier and token exclusion.
- Flip condition: the UI needs endpoint-specific messages that one table cannot express.

## Design Validation

### Validation of Option A

Settled assumptions:

- The shared contract defines the error object with a field list.
- The remote procedure runtime already carries the rich status types.
- Filters run before controllers.
- The trace identifier is a 32-character lower-case hexadecimal value, the form every contract example uses.

Open assumptions:

- Open → the status for a failure outside the six codes, settled by the Requirements Analysis author.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | one table drives both protocols for all six codes |
| AC2 | Holds | one writer renders every body to the shared field list |
| AC3 | Holds | the paging rule adds one field entry naming the page token |
| AC4 | Holds | the interceptor packs the same object the HTTP writer renders |
| AC5 | Holds | the identifier is issued at receipt and placed in the logging context |
| AC6 | Holds | the table stores each code as text |
| AC7 | Holds | the writer drops stack traces and copies no request header |
| AC8 | Holds | filters and controllers share one writer |

### Validation of Option B

Settled assumptions:

- The shared contract defines the error object with a field list.
- The remote procedure runtime already carries the rich status types.
- Filters run before controllers.
- The trace identifier is a 32-character lower-case hexadecimal value, the form every contract example uses.

Open assumptions:

- Open → the status for a failure outside the six codes, settled by the Requirements Analysis author.

| AC | Verdict | Why |
|---|---|---|
| AC1 | At risk | four copies of the mapping can drift |
| AC2 | Holds | each copy can render the shared field list |
| AC3 | Holds | the transaction list endpoint can add the page token entry |
| AC4 | At risk | details packing is repeated in each method |
| AC5 | Breaks | early rejections carry no trace identifier |
| AC6 | Holds | each copy can write the code as text |
| AC7 | Holds | each copy can drop stack traces and request headers |
| AC8 | Breaks | filter bodies differ from controller bodies |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-05-001 |
| Status | TBD |
| Estimated Files Touched | 7 |
| Issue Type | Story |
| Epic Name | Balance Summary Endpoint |
| Epic Link | CS-05 |
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

