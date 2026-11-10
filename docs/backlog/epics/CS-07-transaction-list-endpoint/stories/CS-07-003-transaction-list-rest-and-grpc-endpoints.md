# CS-07-003: Transaction list REST and gRPC endpoints

Parent epic: [CS-07 Transaction List Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a UI developer, I want the transaction list served over REST and gRPC exactly as contracted, so that the deferred UI pages with one fixed interface.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: the transaction list route on both protocols, `GET /v1/accounts/{accountId}/transactions` and `CardServicingService.ListTransactions`, as thin adapters over the validated request of CS-07-001 and the one retrieval call of CS-07-002. The columns of the `Use Cases · COTRN00C` list, Transaction ID, Date (taken from TRAN-ORIG-TS), Description and Amount, reach the UI as `transactionId`, `originalTimestamp`, `description` and `amount`. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/drill/TransactionListController.java` is created and serves `GET /v1/accounts/{accountId}/transactions` with the optional query parameters `typeCode`, `categoryCode`, `pageSize` and `pageToken`.
>   - It takes the caller's bearer token from the `Authorization` header and the request's `RequestDeadline` from the request attribute where the Stage 0 hook places it (CS-05-006).
>   - An absent `typeCode` or `categoryCode` means no filter, and a present value is passed on as Stage 0 decoded and accepted it. An absent `pageSize` takes the effective default page size. An absent `pageToken` and a `pageToken=` both request the first page.
>   - It renders the REST body from the `TransactionPage` and sets the `X-Data-Status` response header equal to the body's `dataStatus` on every 200.
>   - It writes the body so that an absent next-page token is omitted, never rendered as JSON `null` or the empty string, whatever the application's default null-inclusion setting, and so that `transactions` is always present, an empty array when nothing matches.
> - `card-servicing-service/src/main/java/com/example/cardservicing/balance/BalanceSummaryGrpcService.java`, the `CardServicingService` gRPC implementation class that the balance summary REST and gRPC endpoints story CS-05-006 creates, is edited here to add the `ListTransactions` method.
>   - One gRPC server registers each service name once, so every method of `cardservicing.api.v1.CardServicingService` sits in this one class, and this story adds no second implementation of the service.
>   - The class exists before this story starts: CS-05-006 sits at W2 position 4 and this story at W2 position 10.
>   - The method takes the bearer token and the request deadline from the gRPC `Context`, where the Stage 0 interceptor places them, and reads `account_id`, `page_token` and the presence of the proto3 `optional` fields `type_code`, `category_code` and `page_size` from `ListTransactionsRequest`.
>   - An unset `type_code` or `category_code` means no filter. An unset `page_size` means the configured `default_page_size`, 20 under the built-in defaults, as the field comment of `ListTransactionsRequest` states, while a set `page_size`, `0` included, is checked as given at Stage 0. An empty `page_token` requests the first page.
>   - It returns `ListTransactionsResponse` with every `TransactionSummary` field carrying the same string the REST body carries, and with `next_page_token` set to the empty string when the page has no next-page token.
> - Flow, the same for both adapters:
>   - The `traceId` is issued at receipt by `TraceIdFilter` on REST and `GrpcErrorInterceptor` on gRPC (CS-05-002).
>   - The Stage 0 hook `RequestValidationStage` (CS-05-003, extended by CS-05-006) starts the request deadline and applies the transaction-list rule set of `TransactionListRequestValidator` (CS-07-001) ahead of the guard.
>   - The account access guard `AccountAccessGuard` (CS-12-003), through `AccountAccessFilter` on REST and `AccountAccessGrpcInterceptor` on gRPC, validates the token and authorizes the path `accountId` before either adapter runs.
>   - The adapter obtains `TransactionListRequestValidator.ValidatedListRequest` once from that rule set with the configuration capture of the request's `RequestDeadline` (`settings()`), the capture Stage 0 used, so the effective page size equals the one Stage 0 checked.
>   - It makes one call to `TransactionListService` (CS-07-002) with the bearer token, the request deadline and that validated request, and receives one `TransactionPage`.
>   - It renders that page in its protocol's form.
> - Rules for both adapters:
>   - Neither adapter reads data, opens a cache or store connection, launches an upstream call, decodes or rewrites a page token, marks the cut-off, completes the deadline or builds an error body. The retrieval marks the cut-off and completes the deadline (CS-07-002), and Stage 0 and the guard do both for their own rejections.
>   - A present next-page token passes through byte for byte as REST `nextPageToken` and gRPC `next_page_token`.
>   - Every failure raised ahead of them or by the retrieval passes unchanged to the standard error mapping of CS-05-002, `ApiErrorHandler` on REST and `GrpcErrorInterceptor` on gRPC: REST 400, 401, 403, 404, 503 or 504 with the `ErrorObject` body, and gRPC `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND`, `UNAVAILABLE` or `DEADLINE_EXCEEDED` with exactly one packed `cardservicing.common.v1.ErrorObject` in `google.rpc.Status` details, each with the `traceId` generated at receipt.
>   - Identity: the caller supplies only the bearer token, in the `authorization` header or gRPC metadata, and the path and query parameters. The subject comes only from the token the guard validated, and an inbound `x-api-key` is ignored.
> - Tests:
>   - `card-servicing-service/src/test/java/com/example/cardservicing/drill/TransactionListControllerTest.java` covers the REST route in a Spring web test context holding `TraceIdFilter`, the Stage 0 hook, `AccountAccessFilter` and the controller. Each body is checked against the `ListTransactionsResponse` or `ErrorObject` schema read from `card-servicing-api.yaml`: required properties, no additional property, and the type and pattern of every property, adding no test library.
>   - `card-servicing-service/src/test/java/com/example/cardservicing/drill/TransactionListGrpcTest.java` covers the gRPC method over a loopback channel to the service hosted through `GrpcServerConfiguration` (CS-11-002) on port 0, behind `GrpcErrorInterceptor`, the Stage 0 interceptor and `AccountAccessGrpcInterceptor`.
>   - Both classes run against the in-JVM loopback stubs registered on `StubHarness` (CS-11-002), with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` overrides through `StubHarness.inject`, a test-local `StubRegistration` under stub name `transaction-activity` whose request-capturing server interceptor records the `page_size` and `page_token` of each call, and the recording entry store on the controllable test clock of the CS-11-004 tests. Both assert the same expected values on the same fixed test clock, so AC2 compares the two renderings of one fixture state.
> - That makes 4 files: 3 created and 1 edited.
>
> In scope:
>
> - Both protocol adapters: the REST controller and the gRPC `ListTransactions` method.
> - The `X-Data-Status` header and its agreement with the body's `dataStatus`.
> - The absence forms: an omitted REST `nextPageToken`, an empty gRPC `next_page_token` and an unset gRPC `page_size`.
> - Status mapping on both protocols.
> - The two test classes.
>
> Not in this story:
>
> - The validation rules and the effective page size (CS-07-001).
> - Retrieval, token pass-through at the client, outcome mapping, `dataStatus` resolution and the cut-off (CS-07-002).
> - The error object definition and code table (CS-05-002), the guard (CS-12-003) and the call policy and request deadline (CS-11-003), reused unchanged.
> - The UI, the transaction list end-to-end and paging navigation steps (CS-03-001, CS-03-002) and the transaction list SLA scenario (CS-03-003).
>
> ### 2. Acceptance Criteria
>
> - AC1: `GET /v1/accounts/00000000001/transactions` with `Authorization: Bearer fixture-token-user0001`, all stubs healthy and an empty cache, returns 200 and header `X-Data-Status: FRESH`. The body validates against `ListTransactionsResponse` in `card-servicing-api.yaml` and carries `accountId` "00000000001", `dataStatus` "FRESH" and six `transactions` newest first, in `transactionId` descending order because all six share `originalTimestamp` and `processedTimestamp` "2022-06-10T19:27:53.000000Z": `0000000776200014` "985.22", `0000000755736377` "906.44", `0000000624335286` "598.44", `0000000555363230` "484.99", `0000000532120892` (`typeCode` "03") "-70.77" and `0000000503557384` "81.44". It equals the `firstPage` example of that file apart from `asOf`, which is RFC 3339 with microseconds and `Z`.
> - AC2: On the fixed test clock of AC1, `ListTransactions` with `account_id` `00000000001`, `page_size` unset and `authorization` metadata `Bearer fixture-token-user0001` returns a message whose every field value, `as_of` and every `transactions` element included, equals the AC1 REST body field of the same name, with snake_case matched to camelCase. Amounts and timestamps are identical strings, and `data_status` is "FRESH".
> - AC3: On a last page, the AC1 request and the third page of AC4, the REST body has no `nextPageToken` property, neither JSON `null` nor the empty string, and the gRPC `next_page_token` is the empty string.
> - AC4: With REST `pageSize=2` and gRPC `page_size` 2, for account `00000000001` with no filters, the first page holds `0000000776200014` and `0000000755736377` and a non-empty next-page token. On both protocols that token equals the token `PageTokenCodec` (CS-10-003) mints for the cursor (`2022-06-10T19:27:53.000000Z`, `0000000755736377`) with account `00000000001` and no filters, so REST `nextPageToken` and gRPC `next_page_token` are the identical string. Following it on either protocol returns `0000000624335286` and `0000000555363230` with a non-empty next-page token, and following that returns `0000000532120892` and `0000000503557384` as the last page.
> - AC5: On every REST 200 the `X-Data-Status` header equals the body's `dataStatus`, and gRPC `data_status` carries the same value. This holds for the AC1 request on an empty cache, for the same request answered from a page held 30 s on the test clock, within its 60 s fresh TTL, and for `typeCode` `99`, which returns an empty `transactions` array. In each case the value is `FRESH`.
> - AC6: With the configuration capture at the built-in defaults, gRPC `ListTransactions` for account `00000000001` with `page_size` unset reaches the Transaction Activity stub with `page_size` 20, as the request-capturing registration records, and returns all six items with an empty `next_page_token`, the result of the REST request with no `pageSize`. With a capture holding `default_page_size` 2 and `max_page_size` 100, the same request with `page_size` unset returns the AC4 first page and its next-page token, as does the REST request with no `pageSize`.
> - AC7: Each case below returns, on REST, its status with an `ErrorObject` body that validates against `card-servicing-api.yaml` and carries the `traceId` generated at receipt. On gRPC the same case returns the matching status code with exactly one `cardservicing.common.v1.ErrorObject` packed in `google.rpc.Status` details, whose `code`, `message` and `details` equal the REST body's and whose `trace_id` is that call's receipt `traceId`. On both protocols that `traceId` equals the one on the request's completion log line.
>   - 400 `INVALID_ARGUMENT`: REST `pageSize=0` and gRPC `page_size` set to `0`, each with exactly one `details` entry whose `field` is `pageSize`, and `accountId` `00000000000`, with exactly one `details` entry whose `field` is `accountId`.
>   - 401 `UNAUTHENTICATED`: no `authorization` value, and `fixture-token-expired`.
>   - 404 `NOT_FOUND`: account `10000000002`, inside the entitlements of `USER0001` and without a cross-reference record in the small fixture set of the in-JVM Transaction Activity stub, requested with `fixture-token-user0001`.
>   - 503 `UNAVAILABLE`: the Transaction Activity stub failing every call (`STUB_FAILURE_RATE` 1.0) with an empty cache.
>   - 504 `DEADLINE_EXCEEDED`: a Transaction Activity stub latency of 400 ms (`STUB_LATENCY_MS`) with an empty cache.
> - AC8: Authorization: `accountId` `00000000011`, outside the entitlements of `USER0001` (`00000000001` to `00000000010` and `10000000001` to `10000002000`), requested with `fixture-token-user0001`, returns 403 with `code` `PERMISSION_DENIED` on REST and gRPC status `PERMISSION_DENIED` with the packed `ErrorObject`. No account data is read from upstream or cache before access is allowed: for that request the Transaction Activity stub request log holds zero entries and the recording entry store records zero cache reads.
> - AC9: Timeout and retries, on REST and gRPC: each `ListTransactions` attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the budget left before the cut-off, so with the AC7 latency of 400 ms every attempt ends with `DEADLINE_EXCEEDED` no later than 330 ms after it starts. Retries number at most two (`UPSTREAM_MAX_RETRIES`), after 50 ms then 100 ms of backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, and a retry starts only when its backoff plus a full 300 ms attempt fits the remaining budget. The AC7 503 case shows exactly 3 `ListTransactions` attempts, the AC7 504 case exactly 2 and the AC7 404 case exactly 1.
> - AC10: Fail hard and deadline, on REST and gRPC: the transaction list has no degraded result, so the AC7 503 and 504 cases return the same status with a page held under the request's key aged 120 s on the test clock, past its 60 s fresh TTL, and no held page is returned. For every 503 and 504 of AC7, of this criterion and of AC11, the completion log line carries outcome `error`, the status, the receipt `traceId` and a `cutoffMs` of at most 900 under the 900 ms request deadline (`REQUEST_DEADLINE_MS`), and the client-measured response arrives below 1,000 ms.
> - AC11: Auth fails closed, on REST and gRPC. With the Auth stub failing every call the request returns 503 `UNAVAILABLE` after exactly 3 `ValidateToken` attempts. With an Auth stub latency of 2,000 ms it returns 504 `DEADLINE_EXCEEDED` with no `AuthorizeAccountAccess` entry. With `fixture-token-expired` it returns 401 `UNAUTHENTICATED`, and a request carrying an `x-api-key` header or metadata entry and no bearer token returns 401 `UNAUTHENTICATED` with zero Auth stub entries. In each case the Transaction Activity stub request log holds zero entries and the recording entry store records zero cache reads.
> - AC12: Config: with a stale configuration snapshot, its values applied more than 300 s earlier on the test clock, and the Config stub failing every call, and separately with a 2,000 ms Config stub latency, the AC1 request on REST and the AC2 request on gRPC return the AC1 items with `dataStatus` `FRESH`, and REST returns `X-Data-Status: FRESH`. Each has a logged `cutoffMs` of at most 900 and a client-measured response below 1,000 ms.
> - AC13: On a cold cache with no injected failure, one AC1 request on REST, and separately one AC2 request on gRPC, produces exactly one `ListTransactions` entry in the Transaction Activity stub request log, no `GetTransaction` entry and zero Card Data and Reference Data entries. This shows that the adapters make no reads of their own.
>
> ### 3. Dependencies
>
> - CS-07-001 — the validated request and effective page size.
> - CS-07-002 — the retrieval result with items, next-page token and data status.
>
> Risk: the two protocols mark the end of the list differently, so one mapping slip shows on one protocol only.

## 4. Design

- [Transaction list sequence](../../../../architecture/sequence-transaction-list.mmd)
- [Data model — screen-field coverage matrix](../../../../data-model.md#screen-field-coverage-matrix)
- [Data model — transaction_record](../../../../data-model.md#transaction_record)
- [TRD — request path](../../../../technical-requirements.md#request-path)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — edge cases and error mapping](../../../../technical-requirements.md#edge-cases)
- [TRD — degradation table and cache rules](../../../../technical-requirements.md#resilience)
- [TRD — security, identity and trust](../../../../technical-requirements.md#security)
- [TRD — Card Servicing UI field map and paging](../../../../technical-requirements.md#card-servicing-ui)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
- [Shared error object contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Applies: `TransactionListControllerTest` and `TransactionListGrpcTest` cover AC1–AC13 as paired REST and gRPC assertions, against in-JVM loopback stubs with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE`, the request-capturing `transaction-activity` registration and the recording entry store on the test clock. REST bodies are checked against the `card-servicing-api.yaml` schemas, and each 503 and 504 asserts a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass; at least 80% line coverage per module.
- Integration — Not applicable — this story adds no client, and the Transaction Activity and Auth integration suites (CS-10-007, CS-12-005) cover the clients it reaches on a real channel. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Applies, through the transaction list SLA scenario (CS-03-003). Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms.
- End-to-end — Applies, through the transaction list end-to-end step (CS-03-001) and the paging navigation step (CS-03-002). Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the AC1 items in its transaction list step, an absent `nextPageToken` ends the paged list, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.


## Implementation Design

Recommended: Option A — thin HTTP and remote procedure adapters over one validated request and one retrieval call.

Rationale: one page result rendered twice is the only way both interfaces return the same items, the same next-page token and the same data status for one request.

- Rejected: Option B, a separate retrieval path per protocol, because it duplicates the outcome mapping.

### Shared by both options

- Topology: an HTTP controller and a remote procedure method in the one service process, each behind the receipt stage and the access guard.
- Naming: field names follow the interface descriptions, in camel case on the HTTP interface and in lower case with underscores on the remote procedure interface.
- Addressing: one account path per request on both interfaces, with the filters, the page size and the page token in the query or in the request message.
- Isolation: neither interface opens a store or cache connection of its own, and caller identity comes only from the bearer token the guard validated.
- Fallback: none for the list, so a failed read ends the request with an error and never with a held page.
- Provisioning: no new infrastructure, port or setting, because the existing web layer and remote procedure server host both adapters.
- Cut-over: the deferred UI binds to the HTTP interface without change, and swapping a stub for its real service changes nothing in either adapter.
- Failures: one status mapping rendered on both interfaces, with the trace identifier issued at receipt.

### Design Option A — thin adapters over one retrieval call

The HTTP controller and the remote procedure method each take the validated request from the transaction list rule set, with the configuration values the request captured at receipt, and make one call to the shared transaction list retrieval. Each renders the one page result in its own interface form. The two renderings differ only at the end of the list: an omitted next-page token on the HTTP interface and empty text on the remote procedure interface. Failures leave the adapters as typed failures for the shared error handler and interceptor.

How it behaves:

- A request rejected at receipt or by the guard never reaches either adapter.
- An unset page size on either interface takes the configured default page size from the captured configuration values.
- The HTTP controller copies the data status from the rendered body into the data status header.
- The HTTP body leaves out an absent next-page token whatever the default serializer setting.
- The remote procedure method sends empty text as the next-page token on the last page.
- A present next-page token reaches the caller on both interfaces exactly as the transaction source returned it.
- Neither adapter reads the cache or any upstream, so a cold request makes exactly one list call.
- Neither adapter marks the cut-off, because the retrieval fixes the outcome.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One shared retrieval call (vs a retrieval path per interface) | both interfaces render one page result | a retrieval defect reaches both interfaces at once |
| 2 | End of list as an omitted token on the HTTP interface and empty text on the remote procedure interface (vs one shared end marker) | each interface keeps its contract form | each adapter translates absence in its own way |
| 3 | Data status header copied from the body (vs computed separately) | header and body cannot disagree | the header waits for body rendering |
| 4 | Rendering inside each adapter (vs a shared response mapper) | no extra component for a flat page | two renderings of one result to keep in step |

Effort and risk: one new controller, one added method and two test classes, with the risk in a serializer setting that writes an absent next-page token as a null value.

### Design Option B — separate retrieval path per interface

Each interface owns its own path: the HTTP controller and the remote procedure method each call the transaction client, map the not-found, invalid-token, empty and fail-hard outcomes, resolve the data status and build their own response. Both still sit behind the receipt stage and the access guard, and both use the standard error object.

How it differs:

- Each adapter makes its own list call through the transaction client.
- Each adapter maps the not-found, invalid-token, empty and fail-hard outcomes itself.
- Each adapter resolves the data status and the as-of time itself.
- Each adapter marks the cut-off and completes the request deadline itself.
- A change to the list outcomes lands in two places.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | A retrieval path per interface (vs one shared call) | each interface tunes its own read | pages can diverge between interfaces |
| 2 | Outcome mapping in each adapter (vs inside the shared retrieval) | each interface owns its failures | two copies of the outcome rules |
| 3 | End of list decided in each adapter from its own read (vs one page result) | handling native to each interface | one interface can drift from its contract |

Effort and risk: two full read and render paths with their tests, at high risk because the two interfaces can return different pages for one account.

### Recommendation

- Option: Option A.
- Why: one page result serves both interfaces, so both return equal items, an equal next-page token and an equal data status.
- Strongest argument against: the adapters must still translate the two absence forms of the end of the list, and a slip there shows on one interface only.
- Shared items that do not decide the choice: the contracts, the receipt stage, the access guard, the error mapping and the request deadline started at receipt.
- Flip condition: the two interfaces need different page semantics, such as different page sizes or orderings.

## Design Validation

### Validation of Option A

Settled assumptions:

- Both contracts carry the same page fields, with an omitted next-page token on the HTTP interface and empty text on the remote procedure interface at the end of the list.
- An unset page size on the remote procedure interface means the configured default page size, as its interface description states.
- The shared error handler and interceptor render every failure with the trace identifier issued at receipt.
- The receipt stage starts the deadline and validates the request, and the guard runs before either adapter.
- The retrieval returns one page result per request and fixes its outcome at the cut-off.
- The page-token codec has no random part, so one cursor, account and filter set always give one token.

Open assumptions:

- Open → the remote procedure class from the balance summary endpoint story exists before this story starts, settled by wave position order.
- Open → the default serializer setting leaves an absent next-page token out of the body, settled by the schema check of the controller test.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the controller renders the one page result in contract form |
| AC2 | Holds | both renderings come from one retrieval result |
| AC3 | Conditional | the omission relies on the serializer leaving out an absent token |
| AC4 | Holds | the next-page token passes through unchanged on both interfaces |
| AC5 | Holds | the header copies the body's data status |
| AC6 | Holds | the rule set supplies the configured default page size to both adapters |
| AC7 | Holds | every failure passes to the shared handler and interceptor |
| AC8 | Holds | the adapters run only after the guard allows |
| AC9 | Holds | the call policy sits inside the shared transaction client |
| AC10 | Conditional | it rests on the call policy's remaining-budget rule |
| AC11 | Holds | the guard fails closed before either adapter |
| AC12 | Holds | the configuration refresh never sits on the request path |
| AC13 | Holds | the adapters make no reads of their own |

### Validation of Option B

Settled assumptions:

- Both contracts carry the same page fields, with an omitted next-page token on the HTTP interface and empty text on the remote procedure interface at the end of the list.
- An unset page size on the remote procedure interface means the configured default page size, as its interface description states.
- The shared error handler and interceptor render every failure with the trace identifier issued at receipt.
- The receipt stage starts the deadline and validates the request, and the guard runs before either adapter.

Open assumptions:

- Open → the remote procedure class from the balance summary endpoint story exists before this story starts, settled by wave position order.
- Open → whether both read paths hand back the source token unchanged, settled by paired token assertions on both interfaces.
- Open → whether the two read paths share cache entries, settled by measuring both paths on the test stack.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the HTTP path reads and renders the fixture page |
| AC2 | At risk | pages can diverge between the two read paths |
| AC3 | At risk | each path decides the end of the list on its own |
| AC4 | At risk | the two paths can hand back different tokens |
| AC5 | Conditional | the HTTP path must copy its own body status into the header |
| AC6 | Holds | both paths take the default page size from the shared rule set |
| AC7 | Conditional | each path must raise the same typed failures |
| AC8 | Holds | both paths run only after the guard allows |
| AC9 | Holds | the call policy sits inside the shared transaction client |
| AC10 | Conditional | each path must mark the cut-off within the remaining budget |
| AC11 | Holds | the guard fails closed before either path |
| AC12 | Holds | the configuration refresh never sits on the request path |
| AC13 | Holds | each path still makes one list call per request |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-07-001, CS-07-002 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Transaction List Endpoint |
| Epic Link | CS-07 |
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

