# CS-06-003: Category balance REST and gRPC endpoints

Parent epic: [CS-06 Category Balances Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a UI developer, I want category balances served over REST and gRPC exactly as contracted, so that the deferred UI binds to a fixed interface.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRAN-CAT-BAL
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: the category balances route on both protocols, `GET /v1/accounts/{accountId}/category-balances` and `CardServicingService.ListCategoryBalances`, as thin adapters over the one retrieval call of CS-06-002, the Stage 3 reference-dependent check of CS-06-001 and the one response mapper of CS-06-002. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/category/CategoryBalanceController.java` is created and serves `GET /v1/accounts/{accountId}/category-balances` with the optional `typeCode` query parameter.
>   - It takes the caller's bearer token, the path `accountId` that Stage 0 accepted and the request's `RequestDeadline` from the request attribute where the Stage 0 hook places it (CS-05-006).
>   - An absent `typeCode` query parameter means no filter. A present value, the empty value included, is passed on as Stage 0 decoded and accepted it.
>   - It renders the REST body from the mapper's REST rendering and sets the `X-Data-Status` response header from that rendering's value, which equals the body's `dataStatus`.
>   - It writes the body so that a present JSON `null` of `typeDescription` and `categoryDescription` survives serialization, whatever the application's default null-inclusion setting.
> - `card-servicing-service/src/main/java/com/example/cardservicing/balance/BalanceSummaryGrpcService.java`, the `CardServicingService` gRPC implementation class that the balance summary REST and gRPC endpoints story CS-05-006 creates, is edited here to add the `ListCategoryBalances` method.
>   - One gRPC server registers each service name once, so every method of `cardservicing.api.v1.CardServicingService` sits in this one class, and this story adds no second implementation of the service.
>   - The class exists before this story starts: CS-05-006 sits at W2 position 4 and this story at W2 position 7.
>   - The method takes the bearer token and the request deadline from the gRPC `Context`, where the Stage 0 interceptor places them, reads `account_id` and the presence of the proto3 `optional` `type_code` from `ListCategoryBalancesRequest`, and returns the mapper's gRPC `ListCategoryBalancesResponse`. An unset `type_code` means no filter.
> - Flow, the same for both adapters:
>   - The `traceId` is issued at receipt by `TraceIdFilter` on REST and `GrpcErrorInterceptor` on gRPC (CS-05-002).
>   - The Stage 0 hook `RequestValidationStage` (CS-05-003, extended by CS-05-006) starts the request deadline and applies the Stage 0 rule set of `CategoryBalanceRequestValidator` (CS-06-001) ahead of the guard.
>   - The account access guard `AccountAccessGuard` (CS-12-003), through `AccountAccessFilter` on REST and `AccountAccessGrpcInterceptor` on gRPC, validates the token and authorizes the path `accountId` before either adapter runs.
>   - The adapter makes one call to `CategoryBalanceRetrievalService` (CS-06-002) with the token, `accountId`, the optional `typeCode` and the request deadline, and receives one `CategoryBalanceRetrievalService.Result`.
>   - It then applies the Stage 3 check of `CategoryBalanceRequestValidator` (CS-06-001) to the request's `typeCode` and the category-list result carried in that `Result`. On a rejection it completes the request deadline once with outcome `error` and status 400, because the retrieval has already marked the cut-off, and raises the failure, so no category balance reaches the response.
>   - Otherwise it renders the `Result` through `CategoryBalanceResponseMapper` (CS-06-002), which completes the deadline with outcome `full` or `degraded`.
> - Rules for both adapters:
>   - Neither adapter reads data, opens a cache or store connection, launches an upstream call, marks the cut-off or builds an error body.
>   - Every failure raised ahead of them, by the retrieval or by the Stage 3 check passes unchanged to the standard error mapping of CS-05-002, `ApiErrorHandler` on REST and `GrpcErrorInterceptor` on gRPC: REST 400, 401, 403, 404, 503 or 504 with the `ErrorObject` body, and gRPC `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND`, `UNAVAILABLE` or `DEADLINE_EXCEEDED` with exactly one packed `cardservicing.common.v1.ErrorObject` in `google.rpc.Status` details, each with the `traceId` generated at receipt.
>   - Identity: the caller supplies only the bearer token, in the `authorization` header or gRPC metadata, and the path and query parameters. The subject comes only from the token the guard validated, and an inbound `x-api-key` is ignored.
> - Tests:
>   - `card-servicing-service/src/test/java/com/example/cardservicing/category/CategoryBalanceControllerTest.java` covers the REST route in a Spring web test context holding `TraceIdFilter`, the Stage 0 hook, `AccountAccessFilter` and the controller. Each body is checked against the `ListCategoryBalancesResponse` or `ErrorObject` schema read from `card-servicing-api.yaml`: required properties, no additional property, and the type and pattern of every property, adding no test library.
>   - `card-servicing-service/src/test/java/com/example/cardservicing/category/CategoryBalanceGrpcTest.java` covers the gRPC method over a loopback channel to the service hosted through `GrpcServerConfiguration` (CS-11-002) on port 0, behind `GrpcErrorInterceptor`, the Stage 0 interceptor and `AccountAccessGrpcInterceptor`.
>   - Both classes run against the in-JVM loopback stubs registered on `StubHarness` (CS-11-002), with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` overrides through `StubHarness.inject`, test-local `StubRegistration` instances for scripted answers, and the recording entry store on the controllable test clock of the CS-11-004 tests. Both assert the same expected values on the same fixed test clock, so AC2 compares the two renderings of one fixture state.
> - That makes 4 files: 3 created and 1 edited.
>
> In scope:
>
> - The REST controller and the `X-Data-Status` header.
> - The gRPC `ListCategoryBalances` method.
> - Header and body agreement on `dataStatus`.
> - Both null-description forms: a present JSON `null` on REST and an unset field on gRPC.
> - The call order of the Stage 3 check between retrieval and rendering, and the deadline completion on its rejection.
> - The error envelope on both protocols.
> - The two test classes.
>
> Not in this story:
>
> - The Stage 0 and Stage 3 validation rules (CS-06-001).
> - Retrieval, filter, ordering, degradation, `dataStatus` resolution and the mapper (CS-06-002).
> - The guard (CS-12-003), the call policy and request deadline (CS-11-003) and the error mapping (CS-05-002), reused unchanged.
> - The category balance end-to-end step (CS-02-001) and SLA scenario (CS-02-002).
>
> ### 2. Acceptance Criteria
>
> - AC1: `GET /v1/accounts/00000000001/category-balances?typeCode=01` with `Authorization: Bearer fixture-token-user0001`, all stubs healthy and an empty cache, returns 200 and header `X-Data-Status: FRESH`. The body validates against `ListCategoryBalancesResponse` in `card-servicing-api.yaml` and carries `accountId` "00000000001", `categoryBalances` [{`typeCode` "01", `typeDescription` "Purchase", `categoryCode` "0001", `categoryDescription` "Regular Sales Draft", `balance` "0.00"}] and `dataStatus` "FRESH". It equals the `fresh` example of that file apart from `asOf`, which is RFC 3339 with microseconds and `Z`.
> - AC2: On the fixed test clock of AC1, `ListCategoryBalances` with `account_id` `00000000001`, `type_code` `01` and `authorization` metadata `Bearer fixture-token-user0001` returns a message whose every field value, `as_of` included, equals the AC1 REST body field of the same name, with snake_case matched to camelCase, and with `type_description` and `category_description` both set. With the REST `typeCode` absent and the gRPC `type_code` unset, both protocols return the same one balance.
> - AC3: On every REST 200 the `X-Data-Status` header equals the body's `dataStatus`, for `FRESH` (the AC1 request), `STALE` (Card Data stub failing every call, `STUB_FAILURE_RATE` 1.0, with a `cs:v1:category-balances:00000000001` entry aged 120 s on the test clock) and `PARTIAL` (Reference Data stub failing every call, with no `cs:v1:refcats` or `cs:v1:reftypes` entry). In each case gRPC `data_status` carries the same value.
> - AC4: In the AC3 `PARTIAL` case, every REST `categoryBalances` element carries `"typeDescription": null` and `"categoryDescription": null` as present properties. Every gRPC `category_balances` element has `type_description` and `category_description` unset, with field presence false, and never set to an empty string.
> - AC5: Stage 0, on REST and gRPC: `typeCode` `001` and a present but empty `typeCode` (REST `typeCode=`, gRPC `type_code` set to the empty string), each with `accountId` `00000000001`, return 400 `INVALID_ARGUMENT` with `message` "Transaction type code must be exactly 2 characters" and exactly one `details` entry whose `field` is `typeCode`. `accountId` `00000000000` with `typeCode` `01` returns 400 `INVALID_ARGUMENT` with `message` "Account number must be a non zero 11 digit number" and exactly one `details` entry whose `field` is `accountId`. For each request the Auth, Config, Card Data, Transaction Activity and Reference Data stub request logs hold zero entries.
> - AC6: Stage 3, on REST and gRPC: with the Reference Data stub healthy, `typeCode` `99` and `typeCode` `A1` for `accountId` `00000000001` each return 400 `INVALID_ARGUMENT` with `message` "Transaction type code is unknown" and exactly one `details` entry whose `field` is `typeCode`. The REST body holds only `code`, `message`, `traceId` and `details`, gRPC returns no response message, and each request writes exactly one completion line with outcome `error`, status 400 and a `cutoffMs` of at most 900.
> - AC7: Authentication, on REST and gRPC: a request with no `authorization` value, one with `fixture-token-expired` and one with `fixture-token-malformed` each return 401 `UNAUTHENTICATED`. A request carrying an `x-api-key` header or metadata entry and no bearer token also returns 401 `UNAUTHENTICATED`. For it and for the request with no `authorization` value the Auth stub request log holds zero entries, and for every request in this criterion the Card Data and Reference Data stub request logs hold zero entries.
> - AC8: Authorization: `accountId` `00000000011`, outside the entitlements of `USER0001` (sample accounts `00000000001` to `00000000010`), requested with `fixture-token-user0001`, returns 403 with `code` `PERMISSION_DENIED` on REST and gRPC status `PERMISSION_DENIED` with the packed `ErrorObject`. No account data is read from upstream or cache before access is allowed: for that request the Card Data and Reference Data stub request logs hold zero entries and the recording entry store records zero cache reads.
> - AC9: `accountId` `10000000001`, entitled to `USER0001` and absent from the small fixture set of the in-JVM Card Data stub, requested with `fixture-token-user0001`, returns 404 with `code` `NOT_FOUND` on REST and gRPC status `NOT_FOUND`, and the Card Data stub request log shows exactly one `ListCategoryBalances` call.
> - AC10: Each 400, 401, 403, 404, 503 and 504 of AC5 to AC9, AC11 and AC12 returns, on REST, that status with an `ErrorObject` body that validates against `card-servicing-api.yaml`, whose `code` is `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND`, `UNAVAILABLE` or `DEADLINE_EXCEEDED` and whose `traceId` is the receipt `traceId`. On gRPC the same case returns that status code with exactly one `cardservicing.common.v1.ErrorObject` packed in `google.rpc.Status` details, whose `code`, `message` and `details` equal the REST body's and whose `trace_id` is that call's receipt `traceId`, equal to the `traceId` on its completion log line.
> - AC11: Auth fails hard and closed, on REST and gRPC. With the Auth stub failing every call (`STUB_FAILURE_RATE` 1.0) the request returns 503 `UNAVAILABLE` after exactly 3 `ValidateToken` attempts. With a test-local Auth stub registration that answers `ValidateToken` and fails every `AuthorizeAccountAccess` with `UNAVAILABLE`, the request returns 503 `UNAVAILABLE` after exactly 3 `AuthorizeAccountAccess` attempts. With an Auth stub latency of 2,000 ms (`STUB_LATENCY_MS`) the request returns 504 `DEADLINE_EXCEEDED` with no `AuthorizeAccountAccess` entry. In each case the Card Data and Reference Data stub request logs hold zero entries.
> - AC12: Timeout, retries and degradation, on REST and gRPC:
>   - Each Card Data and Reference Data attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget: with a Card Data stub latency of 400 ms and an empty cache, the Card Data request log shows exactly 2 `ListCategoryBalances` attempts and the request returns 504 `DEADLINE_EXCEEDED`; with a Reference Data stub latency of 400 ms instead, the request returns 200 `PARTIAL`.
>   - Retries number at most two (`UPSTREAM_MAX_RETRIES`), after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, and a retry starts only when its backoff plus a full 300 ms attempt fits the remaining budget. The AC9 `NOT_FOUND` receives exactly 1 call.
>   - With the Card Data stub failing every call, the request degrades to 200 `STALE` with the AC3 entry aged 120 s, within the 300 s max stale age, and fails hard with 503 `UNAVAILABLE` with no entry, each after exactly 3 `ListCategoryBalances` attempts.
>   - With the Reference Data stub failing every call and no reference entry within the 86,400 s max stale age, the request degrades to 200 `PARTIAL` with the one `01`/`0001` balance "0.00".
> - AC13: Deadline: for every degraded 200, 503 and 504 of AC11 and AC12, on both protocols, the logged `cutoffMs` is at most 900 (`REQUEST_DEADLINE_MS`), the completion line carries the outcome and the receipt `traceId`, and the client-measured response arrives below 1,000 ms.
> - AC14: Config: with the Config stub failing every call, and separately with a 2,000 ms Config stub latency and a stale configuration snapshot, the AC1 request on REST and the AC2 request on gRPC return the AC1 values with `dataStatus` `FRESH`, and REST returns `X-Data-Status: FRESH`. Each has a logged `cutoffMs` of at most 900 and a client-measured response below 1,000 ms.
> - AC15: On a cold cache with no injected failure, one AC1 request on REST, and separately one AC2 request on gRPC, produces exactly one `ListCategoryBalances` call in the Card Data stub request log, exactly one `ListTransactionCategories` and one `ListTransactionTypes` call in the Reference Data stub request log, no `GetAccount` or `GetCardCrossReference` call and zero Transaction Activity entries. This shows that the adapters make no reads of their own.
>
> ### 3. Dependencies
>
> - CS-06-001 — Stage 0 and reference-dependent validation
> - CS-06-002 — retrieval result and null-description mapping
>
> Risk: REST and gRPC carry a null description differently, so an adapter can drift from the contract without a paired assertion.

## 4. Design

- [Category balances sequence](../../../../architecture/sequence-category-balances.mmd)
- [Data model — screen-field coverage matrix](../../../../data-model.md#screen-field-coverage-matrix)
- [Data model — category_balance](../../../../data-model.md#category_balance)
- [TRD — request path](../../../../technical-requirements.md#request-path)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — edge cases and error mapping](../../../../technical-requirements.md#edge-cases)
- [TRD — degradation table and cache rules](../../../../technical-requirements.md#resilience)
- [TRD — security, identity and trust](../../../../technical-requirements.md#security)
- [TRD — Card Servicing UI field map](../../../../technical-requirements.md#card-servicing-ui)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
- [Shared error object contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
  - Cases: AC1–AC15 as paired REST and gRPC assertions in `CategoryBalanceControllerTest` and `CategoryBalanceGrpcTest`, against in-JVM loopback stubs with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE`, test-local scripted stub answers and the recording entry store on the test clock.
  - REST bodies are checked against the `card-servicing-api.yaml` schemas, and gRPC field presence is asserted for every absent description.
  - Each degraded 200, 503 and 504 asserts a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- Integration: Not applicable — the integration suites belong to the external epics, here the Card Data, Reference Data and Auth integration suites (CS-09-006, CS-13-004, CS-12-005). Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Applies, through the category balance SLA scenario (CS-02-002).
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Applies, through the category balance end-to-end step (CS-02-001).
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: the four-step flow for account `00000000001` returns the AC1 values in its category balance step, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — thin HTTP and remote procedure adapters over one shared retrieval call and one shared response mapper.

Rationale: one retrieval result rendered by one mapper is the only way both interfaces return the same balances, the same data status and the same rule for a missing description.

- Rejected: Option B, a separate retrieval and mapping path per interface, because it gives two code paths to keep in step with one contract.

### Shared by both options

- Topology: an HTTP controller and a remote procedure method in the one service process, each behind the receipt stage and the access guard.
- Naming: operation and field names follow each contract in its own casing, camel case on the HTTP interface and lower case with underscores on the remote procedure interface.
- Addressing: an HTTP path carrying the account number with an optional type code query, and a remote procedure request carrying the same two fields.
- Isolation: caller identity comes only from the bearer token the guard validated, and an inbound service key is ignored.
- Fallback: a stale or partial result is returned with its data status, and the status header always equals the body's data status.
- Provisioning: no new port, process or setting, because the existing remote procedure server and web layer host both adapters.
- Cut-over: the deferred interface binds to the contracts, and swapping a stub for its real service changes nothing in either adapter.
- Failures: one error mapping serves both interfaces, with the trace number issued at receipt.

### Design Option A — thin adapters over one retrieval and one mapper

The HTTP controller and the remote procedure method each take the account number, the optional type code, the caller's token and the request deadline, and make one call to the shared category balance retrieval. Each then runs the known-type check of the validation story on that result and renders it through the one response mapper of the retrieval story. The mapper renders a missing description as a present null value on the HTTP interface and as an unset field on the remote procedure interface. Failures leave the adapters as typed failures for the shared error handler and interceptor.

How it behaves:

- A request rejected at receipt or by the guard never reaches either adapter.
- After the retrieval returns, the known-type check runs before rendering, and an unknown type code ends the request with a bad-request rejection and no balance.
- The HTTP controller copies the data status from the rendered body into the status header.
- The HTTP body keeps a present null description whatever the default serializer setting.
- The remote procedure method returns the message rendering of the same result, with each missing description left unset.
- Neither adapter reads the cache or any upstream, so a cold request makes exactly one call per upstream read.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One shared response mapper (vs one mapper per adapter) | both interfaces follow one null rule | the mapper knows both renderings |
| 2 | Status header taken from the body's status value (vs computed separately) | header and body cannot disagree | the header waits for body rendering |
| 3 | Errors raised to the shared handler and interceptor (vs mapped inside each adapter) | one error shape across both interfaces | adapters cannot tailor messages |
| 4 | Known-type check called by the adapter between retrieval and rendering (vs inside the retrieval) | the retrieval stays free of validation rules | each adapter repeats one call in the same order |

Effort and risk: one new controller, one added method and two test classes, with the risk in a serializer setting that drops the present null.

### Design Option B — separate retrieval and mapping per interface

Each interface owns its own path: the HTTP controller and the remote procedure method each launch the balance, category and type reads, apply the filter and the order, resolve the data status and build their own response. Both still sit behind the receipt stage and the access guard, and both use the standard error object.

How it differs:

- Each adapter launches its own balance, category and type reads.
- Each adapter applies the filter, the order and the data status rule itself.
- Each adapter decides how to show a missing description.
- A change to the category balance fields lands in two places.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Each interface reads and maps its own result (vs one shared path) | each interface tunes its own reads | balances can diverge between interfaces |
| 2 | Missing description decided in each adapter (vs one mapper) | handling native to each interface | empty text can replace absence |
| 3 | Data status resolved in each adapter (vs the shared resolver) | local control of the status | two copies of the precedence rule |

Effort and risk: two full read and render paths and their tests, with high risk because the two interfaces can return different balances for the same account.

### Recommendation

- Option: Option A.
- Why: one retrieval and one null rule serve both interfaces, so both return equal balances and an equal data status.
- Strongest argument against: a defect in the shared path breaks both interfaces at once.
- Shared items that do not decide the choice: the contracts, the access guard, the error mapping and the request deadline started at receipt.
- Flip conditions: requirements that give the two interfaces different fields or behaviour.

## Design Validation

### Validation of Option A

Settled assumptions:

- Both contracts carry the same fields, with a present null on the HTTP interface and an unset field on the remote procedure interface.
- The shared error handler and interceptor exist and render every failure with the receipt trace number.
- The receipt stage starts the deadline and runs validation, and the guard runs before either adapter.
- The retrieval returns one result per request, carrying the category list it read.

Open assumptions:

- Open → the remote procedure class from the balance summary endpoint story exists before this story starts, settled by wave position order.
- Open → the generated message tracks presence for the optional descriptions, settled by the scaffold build of the contracts.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the one mapper renders the fixture balance and its labels |
| AC2 | Holds | both renderings come from one retrieval result |
| AC3 | Holds | the header copies the body status |
| AC4 | Conditional | the unset form relies on the generated message tracking presence |
| AC5 | Holds | the receipt stage rejects malformed input before any network call |
| AC6 | Holds | the adapter runs the known-type check before rendering |
| AC7 | Holds | the guard rejects a missing or bad token before either adapter |
| AC8 | Holds | the adapters run only after the guard allows |
| AC9 | Holds | the not-found status passes unchanged to the error mapping |
| AC10 | Holds | every failure passes to the shared handler and interceptor |
| AC11 | Holds | the guard fails closed before any data read |
| AC12 | Holds | the call policy and fallbacks sit inside the shared clients |
| AC13 | Conditional | it rests on the call policy's remaining-budget rule |
| AC14 | Holds | the configuration refresh never sits on the request path |
| AC15 | Holds | the adapters make no reads of their own |

### Validation of Option B

Settled assumptions:

- Both contracts carry the same fields, with a present null on the HTTP interface and an unset field on the remote procedure interface.
- The shared error handler and interceptor exist and render every failure with the receipt trace number.
- The receipt stage starts the deadline and runs validation, and the guard runs before either adapter.

Open assumptions:

- Open → the remote procedure class from the balance summary endpoint story exists before this story starts, settled by wave position order.
- Open → the generated message tracks presence for the optional descriptions, settled by the scaffold build of the contracts.
- Open → whether the two read paths share cache entries, settled by measuring both paths on the test stack.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the HTTP path reads and labels the fixture balance |
| AC2 | At risk | balances can diverge between the two read paths |
| AC3 | At risk | the header and the body status come from separate logic |
| AC4 | At risk | empty text can slip in for an absent description |
| AC5 | Holds | the receipt stage is shared by both options |
| AC6 | Conditional | each path must repeat the known-type check in the same order |
| AC7 | Holds | the guard is shared by both options |
| AC8 | Holds | both paths run only after the guard allows |
| AC9 | Holds | both paths pass the not-found status to the error mapping |
| AC10 | Holds | both paths use the shared error mapping |
| AC11 | Holds | the guard fails closed before either path |
| AC12 | Holds | the call policy and fallbacks sit inside the shared clients |
| AC13 | Conditional | it rests on the call policy's remaining-budget rule |
| AC14 | Holds | the configuration refresh never sits on the request path |
| AC15 | Breaks | each adapter makes its own reads by design |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-06-001, CS-06-002 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Category Balances Endpoint |
| Epic Link | CS-06 |
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
