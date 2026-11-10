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
>     > SCOPE-QUESTION (Resolved): The controller does not read the `Authorization` header, which only the account access guard (CS-12-003) reads. The bearer token it hands to the retrieval call (CS-07-002) is the token without the scheme that the guard binds on an allow, the request attribute that `AccountAccessFilter` sets, as the balance summary endpoints story sets out (CS-05-006).
>   - An absent `typeCode` or `categoryCode` means no filter, and a present value is passed on as Stage 0 decoded and accepted it. An absent `pageSize` takes the effective default page size. An absent `pageToken` and a `pageToken=` both request the first page.
>   - It renders the REST body from the `TransactionPage` and sets the `X-Data-Status` response header equal to the body's `dataStatus` on every 200.
>   - It writes the body so that an absent next-page token is omitted, never rendered as JSON `null` or the empty string, whatever the application's default null-inclusion setting, and so that `transactions` is always present, an empty array when nothing matches.
> - `card-servicing-service/src/main/java/com/example/cardservicing/balance/BalanceSummaryGrpcService.java`, the `CardServicingService` gRPC implementation class that the balance summary REST and gRPC endpoints story CS-05-006 creates, is edited here to add the `ListTransactions` method.
>   - One gRPC server registers each service name once, so every method of `cardservicing.api.v1.CardServicingService` sits in this one class, and this story adds no second implementation of the service.
>   - The class exists before this story starts: CS-05-006 sits at W2 position 4 and this story at W2 position 10.
>   - The method takes the bearer token and the request deadline from the gRPC `Context`, where the Stage 0 interceptor places them, and reads `account_id`, `page_token` and the presence of the proto3 `optional` fields `type_code`, `category_code` and `page_size` from `ListTransactionsRequest`.
>     > SCOPE-QUESTION (Resolved): The bearer token this method hands to the retrieval call (CS-07-002) is the token without the scheme that the account access guard (CS-12-003) binds on an allow under the gRPC `Context` key of `AccountAccessGrpcInterceptor`, as the controller takes the guard's request attribute on REST. Neither adapter reads the raw `authorization` header or metadata value, which only the guard reads, strips a scheme, or parses or validates a credential, and the request deadline still comes from the Stage 0 hook. The Transaction Activity client adds the `Bearer ` scheme itself (CS-10-006), so every upstream call carries exactly one scheme.
>   - An unset `type_code` or `category_code` means no filter. An unset `page_size` means the configured `default_page_size`, 20 under the built-in defaults, as the field comment of `ListTransactionsRequest` states, while a set `page_size`, `0` included, is checked as given at Stage 0. An empty `page_token` requests the first page.
>   - It returns `ListTransactionsResponse` with every `TransactionSummary` field carrying the same string the REST body carries, and with `next_page_token` set to the empty string when the page has no next-page token.
> - Flow, the same for both adapters:
>   - The `traceId` is issued at receipt by `TraceIdFilter` on REST and `GrpcErrorInterceptor` on gRPC (CS-05-002).
>   - The Stage 0 hook `RequestValidationStage` (CS-05-003, extended by CS-05-006) starts the request deadline and applies the transaction-list rule set of `TransactionListRequestValidator` (CS-07-001) ahead of the guard.
>   - The account access guard `AccountAccessGuard` (CS-12-003), through `AccountAccessFilter` on REST and `AccountAccessGrpcInterceptor` on gRPC, validates the token and authorizes the path `accountId` before either adapter runs.
>   - The adapter obtains `TransactionListRequestValidator.ValidatedListRequest` once from that rule set with the configuration capture of the request's `RequestDeadline` (`settings()`), the capture Stage 0 used, so the effective page size equals the one Stage 0 checked.
>   - It makes one call to `TransactionListService` (CS-07-002) with the bearer token, the request deadline and that validated request, and receives one `TransactionPage`.
>     > SCOPE-QUESTION (Resolved): The two steps above leave open whether obtaining the record from the rule set validates the request a second time, and they pass the record to a retrieval whose declared inputs in CS-07-002 are plain values. The adapter obtains the record through the rule set's non-checking accessor `TransactionListRequestValidator.accepted` (CS-07-001), from the request's own values, the REST path variable and query or the gRPC `ListTransactionsRequest`, and from the capture its `RequestDeadline` holds (`settings()`). It calls `accepted` once, after the guard allows access, and runs no second validation: no pattern, bound, shape, decode, version or fingerprint check. The one `TransactionListService` call passes, as the separate inputs CS-07-002 declares, the bearer token, the request deadline, `accountId`, `typeCode` and `categoryCode` with an absent filter left absent, the effective page size, and `pageToken` byte for byte, the empty string for a first page, and never the record. Paired REST and gRPC proof: `TransactionListControllerTest` and `TransactionListGrpcTest` each supply a counting `PageTokenCodec` to the rule set in their context and read the request-capturing `transaction-activity` registration. For the AC4 follow-up request carrying the first page's next-page token, the counting codec records exactly one decode, the Stage 0 one, and the registration records `account_id` `00000000001`, `type_code` and `category_code` unset, `page_size` 2 and a `page_token` equal byte for byte to the presented token. For the filtered first page of account `00000000001` with `typeCode` `01` and `categoryCode` `0001`, the filters CS-07-002 AC2 uses, and no page size, the counting codec records zero decodes and the registration records `type_code` `01`, `category_code` `0001`, `page_size` 20 and an empty `page_token`.
>   - It renders that page in its protocol's form.
> - Rules for both adapters:
>   - Neither adapter reads data, opens a cache or store connection, launches an upstream call, decodes or rewrites a page token, marks the cut-off, completes the deadline or builds an error body. The retrieval marks the cut-off and completes the deadline (CS-07-002), and Stage 0 and the guard do both for their own rejections.
>     > SCOPE-QUESTION (Resolved): The retrieval's completion covers every typed failure the read raises, the 401 from an upstream `UNAUTHENTICATED` after the guard allowed access included (CS-07-002), so that 401 also gets exactly one completion line, which neither adapter nor the guard writes.
>   - A present next-page token passes through byte for byte as REST `nextPageToken` and gRPC `next_page_token`.
>   - Every failure raised ahead of them or by the retrieval passes unchanged to the standard error mapping of CS-05-002, `ApiErrorHandler` on REST and `GrpcErrorInterceptor` on gRPC: REST 400, 401, 403, 404, 503 or 504 with the `ErrorObject` body, and gRPC `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND`, `UNAVAILABLE` or `DEADLINE_EXCEEDED` with exactly one packed `cardservicing.common.v1.ErrorObject` in `google.rpc.Status` details, each with the `traceId` generated at receipt.
>   - Identity: the caller supplies only the bearer token, in the `authorization` header or gRPC metadata, and the path and query parameters. The subject comes only from the token the guard validated, and an inbound `x-api-key` is ignored.
> - Tests:
>   - `card-servicing-service/src/test/java/com/example/cardservicing/drill/TransactionListControllerTest.java` covers the REST route in a Spring web test context holding `TraceIdFilter`, the Stage 0 hook, `AccountAccessFilter` and the controller. Each body is checked against the `ListTransactionsResponse` or `ErrorObject` schema read from `card-servicing-api.yaml`: required properties, no additional property, and the type and pattern of every property, adding no test library.
>   - `card-servicing-service/src/test/java/com/example/cardservicing/drill/TransactionListGrpcTest.java` covers the gRPC method over a loopback channel to the service hosted through `GrpcServerConfiguration` (CS-11-002) on port 0, behind `GrpcErrorInterceptor`, the Stage 0 interceptor and `AccountAccessGrpcInterceptor`.
>   - Both classes run against the in-JVM loopback stubs registered on `StubHarness` (CS-11-002), with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` overrides through `StubHarness.inject`, a test-local `StubRegistration` under stub name `transaction-activity` whose request-capturing server interceptor records the `page_size` and `page_token` of each call, and the recording entry store on the controllable test clock of the CS-11-004 tests. Both assert the same expected values on the same fixed test clock, so AC2 compares the two renderings of one fixture state.
>     > SCOPE-QUESTION (Resolved): The interceptor also records the `account_id` of each call and its `type_code` and `category_code`, an unset field recorded as unset, so the hand-off assertions beneath the flow above compare every value the retrieval passed on.
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
>   > SCOPE-QUESTION (Resolved): AC1 and AC2 never check the credential the service forwards upstream, and the stubs ignore it, so a doubled scheme would pass both. The request-capturing test-local `transaction-activity` registration of `TransactionListControllerTest` and `TransactionListGrpcTest`, which `StubHarness.allRegistered()` never discovers, therefore also records the received `authorization` metadata value of every call, as the Transaction Activity client test does (CS-10-006). The retrieval's only upstream data call is `ListTransactions`, and it makes no Card Data or Reference Data call (CS-07-002), as AC13 shows, so no other stub needs a capture. For the AC1 REST request and the AC2 gRPC request, each on an empty cache, the capture holds exactly one value, for `ListTransactions`, and that value equals exactly `Bearer fixture-token-user0001`: one scheme, one space and the token, never a duplicated prefix.
> - AC3: On a last page, the AC1 request and the third page of AC4, the REST body has no `nextPageToken` property, neither JSON `null` nor the empty string, and the gRPC `next_page_token` is the empty string.
> - AC4: With REST `pageSize=2` and gRPC `page_size` 2, for account `00000000001` with no filters, the first page holds `0000000776200014` and `0000000755736377` and a non-empty next-page token. On both protocols that token equals the token `PageTokenCodec` (CS-10-003) mints for the cursor (`2022-06-10T19:27:53.000000Z`, `0000000755736377`) with account `00000000001` and no filters, so REST `nextPageToken` and gRPC `next_page_token` are the identical string. Following it on either protocol returns `0000000624335286` and `0000000555363230` with a non-empty next-page token, and following that returns `0000000532120892` and `0000000503557384` as the last page.
> - AC5: On every REST 200 the `X-Data-Status` header equals the body's `dataStatus`, and gRPC `data_status` carries the same value. This holds for the AC1 request on an empty cache, for the same request answered from a page held 30 s on the test clock, within its 60 s fresh TTL, and for `typeCode` `99`, which returns an empty `transactions` array. In each case the value is `FRESH`.
> - AC6: With the configuration capture at the built-in defaults, gRPC `ListTransactions` for account `00000000001` with `page_size` unset reaches the Transaction Activity stub with `page_size` 20, as the request-capturing registration records, and returns all six items with an empty `next_page_token`, the result of the REST request with no `pageSize`. With a capture holding `default_page_size` 2 and `max_page_size` 100, the same request with `page_size` unset returns the AC4 first page and its next-page token, as does the REST request with no `pageSize`.
> - AC7: Each case below returns, on REST, its status with an `ErrorObject` body that validates against `card-servicing-api.yaml` and carries the `traceId` generated at receipt. On gRPC the same case returns the matching status code with exactly one `cardservicing.common.v1.ErrorObject` packed in `google.rpc.Status` details, whose `code`, `message` and `details` equal the REST body's and whose `trace_id` is that call's receipt `traceId`. On both protocols that `traceId` equals the one on the request's completion log line.
>   - 400 `INVALID_ARGUMENT`: REST `pageSize=0` and gRPC `page_size` set to `0`, each with exactly one `details` entry whose `field` is `pageSize`, and `accountId` `00000000000`, with exactly one `details` entry whose `field` is `accountId`.
>   - 401 `UNAUTHENTICATED`: no `authorization` value, and `fixture-token-expired`.
>     > SCOPE-QUESTION (Resolved): Both cases above are guard rejections, so AC7 also covers the 401 the retrieval raises after the guard allowed access. With the request-capturing test-local `transaction-activity` registration of both test classes answering `UNAUTHENTICATED` with one packed `ErrorObject`, and the Auth stub validating `fixture-token-user0001` and allowing it account `00000000001`, REST returns 401 with an `ErrorObject` body whose `code` is `UNAUTHENTICATED` and that validates against `card-servicing-api.yaml`, and gRPC returns status `UNAUTHENTICATED` with exactly one packed `cardservicing.common.v1.ErrorObject` whose `code`, `message` and `details` equal the REST body's. Each carries that call's receipt `traceId`, equal to the one on the request's single completion log line, which carries outcome `error`, status 401 and a `cutoffMs` of at most 900 and is written by the retrieval (CS-07-002), not by the adapter or the guard. For each request the registration records exactly one `ListTransactions` call.
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

- Unit — Applies: `TransactionListControllerTest` and `TransactionListGrpcTest` cover AC1–AC13 as paired REST and gRPC assertions, against in-JVM loopback stubs with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE`, the request-capturing `transaction-activity` registration and the recording entry store on the test clock. Each class also supplies a counting `PageTokenCodec` to the rule set and asserts the hand-off to the retrieval on its protocol: the AC4 follow-up request records exactly one decode, the Stage 0 one, and reaches the stub with `account_id` `00000000001`, `type_code` and `category_code` unset, `page_size` 2 and the presented token byte for byte; a filtered first page with `typeCode` `01` and `categoryCode` `0001` and no page size records zero decodes and reaches the stub with those filters, `page_size` 20 and an empty `page_token`. Each class also covers the upstream 401 of AC7: with the registration answering `UNAUTHENTICATED` with one packed `ErrorObject` after the guard allows `fixture-token-user0001` for account `00000000001`, REST returns 401 and gRPC `UNAUTHENTICATED` with equal `code`, `message` and `details`, the registration records exactly one `ListTransactions` call, and the receipt `traceId` equals the one on the request's single completion log line, written by the retrieval with outcome `error` and status 401. REST bodies are checked against the `card-servicing-api.yaml` schemas, and each 503 and 504 asserts a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass; at least 80% line coverage per module.
  - Forwarded credential: the request-capturing Transaction Activity registration also records, for the AC1 REST and AC2 gRPC requests, every upstream `authorization` value as exactly `Bearer fixture-token-user0001`, one `ListTransactions` value per request, never a duplicated prefix.
- Integration — Not applicable — this story adds no client, and the Transaction Activity and Auth integration suites (CS-10-007, CS-12-005) cover the clients it reaches on a real channel. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Applies, through the transaction list SLA scenario (CS-03-003). Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms.
- End-to-end — Applies, through the transaction list end-to-end step (CS-03-001) and the paging navigation step (CS-03-002). Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the AC1 items in its transaction list step, an absent `nextPageToken` ends the paged list, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.


## Implementation Design

Recommended: Option A — thin HTTP and remote procedure adapters over one retrieval call.

Rationale: one page result rendered twice gives both interfaces equal items, tokens and data status.

- Rejected: Option B, a retrieval path per protocol, because it duplicates the outcome mapping.

### Shared by both options

- Topology: an HTTP controller and a remote procedure method behind the receipt stage and access guard.
- Naming: contract field names, camel case on HTTP and underscored on remote procedure calls.
- Addressing: one account path per request, plus filters, page size and page token.
- Isolation: no adapter-owned store or cache connection, and identity only from the validated bearer token.
- Fallback: none, so a failed read ends in an error, never a held page.
- Provisioning: none, as the existing servers host both adapters.
- Cut-over: the deferred UI binds to HTTP, and a stub swap changes neither adapter.
- Failures: one status mapping for both interfaces.

### Design Option A — thin adapters over one retrieval call

Each adapter reads the request's accepted values once, with no second check, and passes them as separate values in one retrieval call. Each renders the one page result in its own form.

How it behaves:

- An unset page size takes the default captured at receipt.
- A present next-page token passes through unchanged.
- At the list end the HTTP body omits the token whatever the serializer default, and the remote procedure reply carries empty text.
- Neither adapter reads the cache or an upstream or marks the cut-off, so a cold request makes one list call.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One shared retrieval call (vs a path per interface) | both interfaces render one page | a retrieval defect reaches both |
| 2 | Data status header copied from the body (vs computed separately) | header and body cannot disagree | the header waits for the body |
| 3 | Rendering inside each adapter (vs a shared response mapper) | no extra component | two renderings to keep in step |

Effort and risk: one new controller, one added method and two test classes, at risk from a serializer writing a null token.

### Design Option B — separate retrieval path per interface

Each interface owns a full read and render path. Both still use the receipt stage and the guard.

How it differs:

- Each adapter calls the transaction client, maps the not-found, invalid-token, empty and fail-hard outcomes, resolves the data status and marks the cut-off.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | A read path per interface (vs one shared call) | each interface tunes its own read | pages can diverge |
| 2 | Outcome mapping in each adapter (vs in the retrieval) | each interface owns its failures | two copies of the outcome rules |

Effort and risk: two full read and render paths with tests, at high risk of divergent pages.

### Recommendation

- Option: Option A.
- Why: one page result keeps both interfaces equal.
- Strongest argument against: a slip in translating the two end-of-list forms shows on one interface only.
- Shared items that do not decide the choice: the contracts, receipt stage, guard, error mapping and deadline.
- Flip condition: the interfaces need different page sizes or orderings.

## Design Validation

### Validation of Option A

Settled assumptions:

- Both contracts carry the same page fields, and an unset remote procedure page size means the configured default.
- The receipt stage starts the deadline and checks each request once, ahead of the guard.
- The shared error mapping renders every failure with the receipt trace identifier.
- The retrieval returns one page result, fixed at the cut-off.
- The page-token codec has no random part, so both protocols return an equal token.

Open assumptions:

- Open → the balance summary endpoint story's remote procedure class exists before this story starts, settled by wave position order.
- Open → the serializer omits an absent token, settled by the controller test's schema check.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the controller renders the contract form |
| AC2 | Holds | both renderings share one result |
| AC3 | Conditional | the omission relies on the serializer |
| AC4 | Holds | the token passes through unchanged |
| AC5 | Holds | the header copies the body |
| AC6 | Holds | both adapters take the captured default |
| AC7 | Holds | failures reach the shared error mapping |
| AC8 | Holds | the guard rejects ahead of either adapter |
| AC9 | Holds | the shared client applies the call policy |
| AC10 | Conditional | it rests on the remaining-budget rule |
| AC11 | Holds | the guard fails closed first |
| AC12 | Holds | configuration refresh stays off the request path |
| AC13 | Holds | the adapters read nothing themselves |

### Validation of Option B

Settled assumptions:

- The Option A assumptions on the contracts, unset page size, error mapping and receipt stage hold.

Open assumptions:

- Open → the same remote procedure class exists first, settled by wave position order.
- Open → whether both paths return the source token unchanged, settled by paired token assertions.
- Open → whether the paths share cache entries, settled by measurement on the test stack.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the HTTP path renders the fixture page |
| AC2 | At risk | pages can diverge between paths |
| AC3 | At risk | each path decides the list end alone |
| AC4 | At risk | the paths can return different tokens |
| AC5 | Conditional | the HTTP path must copy its body status into the header |
| AC6 | Holds | both paths share the rule set default |
| AC7 | Conditional | each path must raise the same failures |
| AC8 | Holds | the guard rejects ahead of either path |
| AC9 | Holds | the shared client applies the call policy |
| AC10 | Conditional | each path must mark the cut-off within the remaining budget |
| AC11 | Holds | the guard fails closed first |
| AC12 | Holds | configuration refresh stays off the request path |
| AC13 | Holds | each path makes one list call |

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

