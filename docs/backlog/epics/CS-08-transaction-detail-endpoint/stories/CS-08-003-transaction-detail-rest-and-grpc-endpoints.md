# CS-08-003: Transaction detail REST and gRPC endpoints

Parent epic: [CS-08 Transaction Detail Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a UI developer, I want the transaction detail served over REST and gRPC exactly as contracted, so that the deferred UI binds to a fixed interface.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN01C
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: the transaction detail route on both protocols, `GET /v1/accounts/{accountId}/transactions/{transactionId}` and `CardServicingService.GetTransaction`, as thin adapters over the one retrieval call of CS-08-002, together with the wire mapping of its result. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/drill/TransactionDetailController.java` is created and serves `GET /v1/accounts/{accountId}/transactions/{transactionId}`.
>   - It takes the caller's bearer token, the path `accountId` and `transactionId` that Stage 0 accepted, and the request's `RequestDeadline` from the request attribute where the Stage 0 hook places it (CS-05-006).
>   - It makes one call to `TransactionDetailService` (CS-08-002) and renders the returned `TransactionDetailResult` as the `GetTransactionResponse` body of `card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml`.
>   - It sets the `X-Data-Status` response header to the body's `dataStatus`.
> - `card-servicing-service/src/main/java/com/example/cardservicing/balance/BalanceSummaryGrpcService.java`, the `CardServicingService` gRPC implementation class that the balance summary REST and gRPC endpoints story CS-05-006 creates, is edited here to add the `GetTransaction` method.
>   - One gRPC server registers each service name once, so every method of `cardservicing.api.v1.CardServicingService` sits in this one class, and this story adds no second implementation of the service.
>   - The method takes the bearer token and the request deadline from the gRPC `Context`, where the Stage 0 interceptor places them, and reads `account_id` and `transaction_id` from `GetTransactionRequest`.
>     > SCOPE-QUESTION (Resolved): The bearer token that `TransactionDetailController` and this method hand to the retrieval call (CS-08-002) is the token without the scheme that the account access guard binds on an allow (CS-12-003), as the balance summary endpoints story sets out (CS-05-006): the request attribute that `AccountAccessFilter` sets on REST and the gRPC `Context` key that `AccountAccessGrpcInterceptor` binds on gRPC. Neither adapter reads the raw `authorization` header or metadata value, which only the guard reads, strips a scheme, or parses or validates a credential, and the request deadline still comes from the Stage 0 hook. The Card Data and Transaction Activity clients each add the `Bearer ` scheme themselves (CS-09-005 and CS-10-006), so every upstream call carries exactly one scheme.
>   - It makes the same one call to `TransactionDetailService` and renders the same `TransactionDetailResult` as the `GetTransactionResponse` message of `card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto`.
> - Flow, the same for both adapters:
>   - The `traceId` is issued at receipt by `TraceIdFilter` on REST and `GrpcErrorInterceptor` on gRPC (CS-05-002).
>   - The Stage 0 hook `RequestValidationStage` (CS-05-003, extended by CS-05-006) starts the request deadline and applies `TransactionDetailRequestValidator` (CS-08-001) ahead of the guard.
>   - The account access guard `AccountAccessGuard` (CS-12-003), through `AccountAccessFilter` on REST and `AccountAccessGrpcInterceptor` on gRPC, validates the token and authorizes the path `accountId` before either adapter runs.
>   - The adapter calls `TransactionDetailService` with the token, `accountId`, `transactionId` and the request deadline. The service reads the transaction and the account's card cross-references, applies the ownership check, marks the cut-off and completes the deadline (CS-08-002).
>   - Every failure raised ahead of the adapter or by the retrieval passes unchanged to the standard error mapping of CS-05-002, `ApiErrorHandler` on REST and `GrpcErrorInterceptor` on gRPC: REST 400, 401, 403, 404, 503 or 504 with the `ErrorObject` body, and gRPC `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND`, `UNAVAILABLE` or `DEADLINE_EXCEEDED` with exactly one packed `cardservicing.common.v1.ErrorObject` in `google.rpc.Status` details, each with the `traceId` generated at receipt.
> - Rules for both adapters:
>   - Neither adapter reads data, opens a cache or store connection, launches an upstream call, marks the cut-off, completes the deadline or builds an error body.
>   - Identity: the caller supplies only the bearer token, in the `authorization` header or gRPC metadata, and the path parameters or request fields. The subject comes only from the token the guard validated, and an inbound `x-api-key` is ignored.
> - Wire rules, the same on both protocols:
>   - JSON property names are the camelCase forms of the snake_case proto fields.
>   - `amount` is the scale-2 decimal string that `TransactionDetailResult` carries, negative for returns and never a JSON number.
>   - `originalTimestamp`, `processedTimestamp` and `asOf` are RFC 3339 strings with microseconds and `Z`.
>   - `dataStatus` is a string.
>   - Absent optional scalars are omitted. `GetTransactionResponse` and `Transaction` declare every property required, so every 200 body carries all of them.
> - Tests:
>   - `card-servicing-service/src/test/java/com/example/cardservicing/drill/TransactionDetailControllerTest.java` covers the REST route in a Spring web test context holding `TraceIdFilter`, the Stage 0 hook, `AccountAccessFilter` and the controller. Each body is checked against the `GetTransactionResponse` or `ErrorObject` schema read from `card-servicing-api.yaml`: required properties, no additional property, and the type and pattern of every property, adding no test library.
>   - `card-servicing-service/src/test/java/com/example/cardservicing/drill/TransactionDetailGrpcTest.java` covers the gRPC method over a loopback channel to the service hosted through `GrpcServerConfiguration` (CS-11-002) on port 0, behind `GrpcErrorInterceptor`, the Stage 0 interceptor and `AccountAccessGrpcInterceptor`.
>   - Both classes run against the in-JVM loopback stubs registered on `StubHarness` (CS-11-002), with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` overrides, and the recording entry store on the controllable test clock of the CS-11-004 tests. Both assert the same expected values on the same fixed test clock, so AC3 compares the two renderings of one fixture state.
> - That makes 4 files: 3 created and 1 edited.
> - Context: `Use Cases · COTRN01C` displays 13 transaction fields (Transaction ID, Card Number, Type CD, Category CD, Source, Description, Amount, Orig Date, Proc Date, Merchant ID, Merchant Name, Merchant City, Merchant Zip), all from `Data Layouts · CVTRA05Y`, and the contract's `Transaction` carries each of them.
>
> In scope:
>
> - The REST controller and the `X-Data-Status` header.
> - The gRPC `GetTransaction` method.
> - The wire mapping of `TransactionDetailResult` on both protocols.
> - Header and body agreement on `dataStatus`.
> - The error envelope and status mapping on both protocols.
> - The two test classes.
>
> Not in this story:
>
> - The Stage 0 validation rules (CS-08-001).
> - The ownership check, the retrieval, degradation, `dataStatus` resolution and the deadline outcome (CS-08-002).
> - The guard logic (CS-12-003), the call policy and request deadline (CS-11-003) and the error mapping (CS-05-002), reused unchanged.
> - The transaction detail end-to-end step (CS-04-001) and SLA scenario (CS-04-002).
>
> ### 2. Acceptance Criteria
>
> - AC1: Authorization, on REST and gRPC: transaction `0000000776200014` under `accountId` `00000000011`, outside the entitlements of `USER0001` (sample accounts `00000000001` to `00000000010`), requested with `fixture-token-user0001`, returns REST 403 with an `ErrorObject` whose `code` is `PERMISSION_DENIED`, and gRPC status `PERMISSION_DENIED` with the `ErrorObject` packed in `google.rpc.Status` details. No account data is read from upstream or cache before access is allowed: for that request the Transaction Activity and Card Data stub request logs hold zero entries and the recording entry store records zero cache reads.
> - AC2: `GET /v1/accounts/00000000001/transactions/0000000776200014` with `Authorization: Bearer fixture-token-user0001`, all stubs healthy and an empty cache, returns 200. The body validates against `GetTransactionResponse` in `card-servicing-api.yaml` and carries `accountId` "00000000001" and a `transaction` holding every COTRN01C detail field the contract declares: `transactionId` "0000000776200014", `cardNumber` "9680294154603697", `typeCode` "01", `categoryCode` "0001", `source` "POS TERM", `description` "Purchase at Lebsack and Sons", `amount` "985.22", `merchantId` "800000000", `merchantName` "Lebsack and Sons", `merchantCity` "Otisbury", `merchantZip` "32545", `originalTimestamp` "2022-06-10T19:27:53.000000Z" and `processedTimestamp` "2022-06-10T19:27:53.000000Z". It also carries `dataStatus` "FRESH" and `asOf`, and equals the `fresh` example of that file apart from `asOf`. The Transaction Activity stub request log shows exactly one `GetTransaction` call and the Card Data stub request log exactly one `GetCardCrossReference` call, so the adapter makes no read of its own.
> - AC3: On the fixed test clock of AC2, gRPC `GetTransaction` with `account_id` `00000000001`, `transaction_id` `0000000776200014` and `authorization` metadata `Bearer fixture-token-user0001` returns a `GetTransactionResponse` whose every field value, `as_of` included, equals the AC2 REST body field of the same name, with snake_case matched to camelCase, and whose `transaction` sets all 13 fields.
>   > SCOPE-QUESTION (Resolved): AC2 and AC3 never check the credential the service forwards upstream, and the stubs ignore it, so a doubled scheme would pass both. `TransactionDetailControllerTest` and `TransactionDetailGrpcTest` therefore each register test-local header-capturing `StubRegistration` instances under the stub names `transaction-activity` and `card-data`, which `StubHarness.allRegistered()` never discovers, nested in each test class so the 4 files touched stand. Each wraps its stub's service definition with a server interceptor that records the received `authorization` metadata value of every call, as the Transaction Activity and Card Data client tests do (CS-10-006 and CS-09-005). The retrieval reads the transaction and the account's card cross-references and makes no Reference Data call (CS-08-002). For the AC2 REST request and the AC3 gRPC request, each on an empty cache, the capture holds exactly one value for each of `GetTransaction` and `GetCardCrossReference`, and every recorded value equals exactly `Bearer fixture-token-user0001`: one scheme, one space and the token, never a duplicated prefix.
> - AC4: On every REST 200 the `X-Data-Status` header equals the body's `dataStatus`: `FRESH` for the AC2 request, and `STALE` with the Transaction Activity stub failing every call (`STUB_FAILURE_RATE` 1.0) and a cache entry for `0000000776200014` aged 120 s on the test clock, past its 60 s fresh TTL and within its 300 s max stale age. In each case gRPC `data_status` carries the same value.
> - AC5: On both protocols, amounts and timestamps are strings. `amount` is "985.22" for `0000000776200014` and "-70.77" for the return `0000000532120892` under `00000000001`, each with exactly two decimals and never a JSON number, and `originalTimestamp`, `processedTimestamp` and `asOf` are RFC 3339 with microseconds and `Z`.
> - AC6: `transactionId` `000000077620001` (15 characters) under `accountId` `00000000001` returns REST 400 and gRPC `INVALID_ARGUMENT`, each with an `ErrorObject` whose `code` is `INVALID_ARGUMENT` and which holds exactly one `details` entry whose `field` is `transactionId`. For each request the Auth, Transaction Activity and Card Data stub request logs hold zero entries.
> - AC7: `fixture-token-expired`, and a request carrying an `x-api-key` header or metadata entry with no bearer token, each return REST 401 and gRPC `UNAUTHENTICATED` with an `ErrorObject` whose `code` is `UNAUTHENTICATED`. For each request the Transaction Activity and Card Data stub request logs hold zero entries.
> - AC8: With `fixture-token-user0001`, the unknown transaction `9999999999999999` under `00000000001`, and transaction `0000000776200014` under `00000000002`, which is entitled to `USER0001` and whose only card is `0923877193247330`, each return REST 404 and gRPC `NOT_FOUND`. Both cases carry `code` `NOT_FOUND`, `message` "Transaction not found" and no transaction field, so the response does not reveal the transaction under another account.
> - AC9: With no cache entry for the transaction, the Transaction Activity stub failing every call (`STUB_FAILURE_RATE` 1.0) returns REST 503 and gRPC `UNAVAILABLE`, the Card Data stub failing every call returns REST 503 and gRPC `UNAVAILABLE`, and a Transaction Activity stub latency of 2,000 ms (`STUB_LATENCY_MS`) returns REST 504 and gRPC `DEADLINE_EXCEEDED`. Each carries the `ErrorObject` with the `code` of its status, on gRPC packed in `google.rpc.Status` details.
> - AC10: Timeout and retry, on REST and gRPC: each `GetTransaction` and `GetCardCrossReference` attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget, and is retried at most two times (`UPSTREAM_MAX_RETRIES`), after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, and only when the backoff plus a full 300 ms attempt fits the remaining budget. In the AC9 cases the Transaction Activity stub request log shows exactly 3 `GetTransaction` attempts with that stub failing every call and exactly 2 with the 2,000 ms latency, and the Card Data stub request log shows exactly 3 `GetCardCrossReference` attempts with that stub failing every call. The AC8 unknown transaction receives exactly 1 `GetTransaction` call.
> - AC11: Deadline, on REST and gRPC: for each 503 and 504 of AC9 and the `STALE` 200 of AC4, the logged `cutoffMs` is at most 900 (`REQUEST_DEADLINE_MS`), the completion line carries the outcome and the receipt `traceId`, and the client-measured response arrives below 1,000 ms.
>
> SCOPE-QUESTION (Resolved): AC11 states its cut-off bound as `cutoffMs` at most 900 without a unit. The bound is the 900 ms request deadline (`REQUEST_DEADLINE_MS`), measured from receipt to the cut-off, so AC11 requires the cut-off of every request it names to fall at or before 900 ms after receipt.
>
> - AC12: Degradation, on REST and gRPC: the transaction read fails hard when no cache entry is within its 300 s max stale age, and degrades to the AC4 `STALE` 200 when one is. The cross-reference read always fails hard: with the Card Data stub failing every call and `0000000776200014` held in a fresh cache entry, the request returns REST 503 and gRPC `UNAVAILABLE`, never 200. A hard failure returns only the `ErrorObject`, with no `transaction` property on REST and no response message on gRPC. With the Config stub failing every call, the AC2 request returns the AC2 values with `dataStatus` `FRESH`.
> - AC13: Every error in AC1 and AC6 to AC12, on REST and gRPC, carries the `traceId` generated at receipt, on REST in the `ErrorObject` body and on gRPC as `trace_id` of the packed `ErrorObject`, equal to the `traceId` on that request's completion log line.
>
> ### 3. Dependencies
>
> - CS-08-001 — validator both adapters run at Stage 0
> - CS-08-002 — retrieval service both adapters call
>
> Risk: The gRPC method lands in the `CardServicingService` implementation class that CS-05-006 creates, which exists only because CS-05-006 sits at W2 position 4 ahead of this story's position 13, so a reordering that moves this story ahead of CS-05-006 leaves no class to edit.

## 4. Design

- [Transaction detail sequence](../../../../architecture/sequence-transaction-detail.mmd)
- [Data model — screen-field coverage matrix](../../../../data-model.md#screen-field-coverage-matrix)
- [Data model — transaction_record](../../../../data-model.md#transaction_record)
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
  - Cases: AC1–AC13 as paired REST and gRPC assertions in `TransactionDetailControllerTest` and `TransactionDetailGrpcTest`, against in-JVM loopback stubs with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE`, the recording entry store and the test clock.
  - REST bodies are checked against the `card-servicing-api.yaml` schemas, and gRPC messages are compared field by field with the REST body.
  - Forwarded credential: the header-capturing Transaction Activity and Card Data registrations record, for the AC2 REST and AC3 gRPC requests, every upstream `authorization` value as exactly `Bearer fixture-token-user0001`, never a duplicated prefix.
  - Each degraded 200, 503 and 504 asserts a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- Integration: Not applicable — this story adds no client, and the integration suites belong to the external epics, here the Transaction Activity, Card Data and Auth integration suites (CS-10-007, CS-09-006, CS-12-005). Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Applies, through the transaction detail SLA scenario (CS-04-002).
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Applies, through the transaction detail end-to-end step (CS-04-001).
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: the four-step flow for account `00000000001` returns the AC2 values in its transaction detail step, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — thin HTTP and remote procedure adapters over one shared transaction detail retrieval call.

Rationale: one retrieval result rendered twice is the only way both interfaces return the same transaction, the same data status and the same not-found answer for a card outside the account.

- Rejected: Option B, a separate retrieval per interface, because it duplicates the ownership check and lets the two interfaces disagree.

### Shared by both options

- Topology: an HTTP controller and a remote procedure method in the one service process, each behind the receipt stage and the access guard.
- Naming: field names follow the interface description, camel case on the HTTP interface and lower case with underscores on the remote procedure interface.
- Addressing: the account number and the transaction identifier arrive in the web path or in the remote procedure request message.
- Isolation: caller identity comes only from the bearer token the guard validated, and an inbound service key is ignored.
- Fallback: none beyond the retrieval outcomes, so a stale transaction returns with its data status and every other failure returns as an error.
- Provisioning: nothing new is provisioned, because the existing remote procedure server and web layer host both adapters.
- Cut-over: the deferred user interface binds to the web interface unchanged, and swapping a stub for its real service changes nothing in either adapter.
- Failures: each failure maps to one status pair through the shared error mapping, with the trace identifier issued at receipt.

### Design Option A — thin adapters over one retrieval

The HTTP controller and the remote procedure method each take the account number, the transaction identifier, the caller's token and the request deadline, and make one call to the shared transaction detail retrieval. Each renders the one result it returns in its own field casing, with amounts and times kept as the contract strings. Failures leave the adapters as typed failures for the shared error handler and interceptor.

How it behaves:

- A request rejected at receipt or by the guard never reaches either adapter.
- The retrieval reads the transaction and the account's cards, checks ownership and fixes the outcome before the adapter renders.
- The HTTP controller copies the data status from the rendered body into the data status header.
- The remote procedure method returns the message rendering of the same result, with all thirteen transaction fields set.
- An unknown transaction and a card outside the account leave both adapters as the same not-found failure.
- Neither adapter reads the cache or any upstream, so a cold request makes exactly one transaction read and one card read.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Both interfaces call one retrieval (vs one retrieval each) | identical values on both interfaces | one shared change point |
| 2 | Status header copied from the body status (vs computed separately) | header and body cannot disagree | the header waits for body rendering |
| 3 | Errors raised to the shared handler and interceptor (vs mapped inside each adapter) | one error shape across both interfaces | adapters cannot tailor messages |
| 4 | Remote procedure method added to the existing service class (vs a second service class) | one registration per service name | that class grows with every endpoint |

Effort and risk: one new controller, one added method and two test classes, with the risk in the shared service class that the other endpoint stories also edit.

### Design Option B — separate retrieval per interface

Each interface owns its own retrieval path: the HTTP controller and the remote procedure method each launch the transaction and card reads, apply the ownership check, resolve the data status and build their own response. Both still sit behind the receipt stage and the access guard, and both use the standard error object.

How it differs:

- Each adapter launches its own transaction and card reads.
- Each adapter carries its own copy of the ownership check and the not-found rule.
- Each adapter resolves the data status and the read time itself.
- A change to the transaction fields or the ownership rule lands in two places.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Each interface reads its own result (vs one shared retrieval) | each interface tunes its own reads | values can diverge between interfaces |
| 2 | Ownership checked in each adapter (vs the shared retrieval) | local control of the rule | two copies of the security check |
| 3 | Data status resolved in each adapter (vs the shared resolver) | local control of the status | two copies of the precedence rule |

Effort and risk: two full read paths and their tests, with high risk because a drift in one ownership copy exposes another account's transaction.

### Recommendation

- Option: Option A.
- Why: one retrieval and one ownership check serve both interfaces, so both return equal values and the same not-found answer.
- Strongest argument against: one shared retrieval call couples both interfaces to one result shape.
- Shared items that do not decide the choice: the contracts, the access guard, the error mapping and the request deadline started at receipt.
- Flip conditions: the two interfaces need different field sets or different ownership rules.

## Design Validation

### Validation of Option A

Settled assumptions:

- Both contracts carry the same thirteen transaction fields, all required, with amounts and times as strings.
- The retrieval returns one result per request and fixes the outcome at the cut-off.
- The receipt stage starts the deadline and runs validation, and the guard runs before either adapter.
- The shared error handler and interceptor render every failure with the receipt trace identifier.

Open assumptions:

- Open → the remote procedure class from the balance summary endpoint story exists before this story starts, settled by wave position order.
- Open → which message the deferred user interface shows per error status, settled by the deferred UI run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the adapters run only after the guard allows |
| AC2 | Holds | the one retrieval result renders the fixture transaction |
| AC3 | Holds | both renderings come from one result |
| AC4 | Holds | the header copies the body status |
| AC5 | Holds | the result keeps amounts and times as contract strings |
| AC6 | Holds | the receipt stage rejects the short identifier before any network call |
| AC7 | Holds | the guard rejects a bad or missing token before either adapter |
| AC8 | Holds | the retrieval returns one not-found failure for both cases |
| AC9 | Holds | every failure passes unchanged to the shared error mapping |
| AC10 | Holds | the call policy inside the clients sets attempts and retries |
| AC11 | Conditional | it rests on the call policy's remaining-budget rule |
| AC12 | Holds | the fallbacks sit inside the shared retrieval and its clients |
| AC13 | Holds | the shared error mapping carries the receipt trace identifier |

### Validation of Option B

Settled assumptions:

- Both contracts carry the same thirteen transaction fields, all required, with amounts and times as strings.
- Each interface builds one result per request.
- The receipt stage starts the deadline and runs validation, and the guard runs before either adapter.

Open assumptions:

- Open → the remote procedure class from the balance summary endpoint story exists before this story starts, settled by wave position order.
- Open → whether the two read paths share cache entries, settled by measuring both paths on the test stack.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | both paths run only after the guard allows |
| AC2 | Holds | the HTTP path reads and renders the fixture transaction |
| AC3 | At risk | values can diverge between the two read paths |
| AC4 | At risk | the header and the body status come from separate logic |
| AC5 | Holds | the contracts fix the amount and time forms |
| AC6 | Holds | the receipt stage is shared by both options |
| AC7 | Holds | the guard is shared by both options |
| AC8 | At risk | each path must repeat the ownership check exactly |
| AC9 | Holds | both paths use the shared error mapping |
| AC10 | Holds | the call policy inside the clients sets attempts and retries |
| AC11 | Conditional | it rests on the call policy's remaining-budget rule |
| AC12 | At risk | each path must repeat the stale and fail-hard rules |
| AC13 | Holds | both paths use the shared error mapping |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-08-001, CS-08-002 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Transaction Detail Endpoint |
| Epic Link | CS-08 |
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
