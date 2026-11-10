# CS-05-006: Balance summary REST and gRPC endpoints

Parent epic: [CS-05 Balance Summary Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a UI developer, I want the balance summary served over REST and gRPC exactly as contracted, so that the deferred UI binds to a fixed interface.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: the balance summary route on both protocols, `GET /v1/accounts/{accountId}/balance-summary` and `CardServicingService.GetBalanceSummary`, as thin adapters over the one assembly call of CS-05-005, with the request deadline started at receipt ahead of Stage 0 validation. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/balance/BalanceSummaryController.java` serves `GET /v1/accounts/{accountId}/balance-summary`.
>   - It makes one assembly call (CS-05-005) with the caller's bearer token, the path `accountId` that Stage 0 accepted and the request's `RequestDeadline`, and renders the REST body from the result's REST rendering.
>   - It sets `X-Data-Status` from the body's `dataStatus`.
>   - It writes the body so that the present JSON `null` of `typeDescription` survives serialization and an unset `groupId` stays omitted, whatever the application's default null-inclusion setting.
> - `card-servicing-service/src/main/java/com/example/cardservicing/balance/BalanceSummaryGrpcService.java` implements `CardServicingService.GetBalanceSummary` over the same assembly call and renders `GetBalanceSummaryResponse` from the result's gRPC rendering. It takes the bearer token and the request deadline from the gRPC `Context`, where the Stage 0 interceptor places them.
> - Rules for both adapters:
>   - Neither adapter reads data, opens a cache or store connection, or builds error bodies itself. Each failure raised ahead of them or by the assembly passes unchanged to the standard error mapping (CS-05-002): REST 400, 401, 403, 404, 503 or 504 with the `ErrorObject` body, and gRPC `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND`, `UNAVAILABLE` or `DEADLINE_EXCEEDED` with exactly one packed `ErrorObject` in `google.rpc.Status` details, each with the receipt `traceId`.
>   - Neither adapter marks the cut-off or completes the deadline. The assembly does both (CS-05-005).
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/validation/RequestValidationStage.java`, the Stage 0 hook of CS-05-003, is extended to start the request deadline at receipt. The adapters run after the account access guard, so the deadline cannot start in them.
>   - Before it applies the validator, on every route the validator declares and on both protocols, it starts the request's `RequestDeadline` (CS-11-003) with `REQUEST_DEADLINE_MS`, which captures the upstream settings once, and attaches it to the request: a request attribute on REST, and the gRPC `Context` on gRPC together with the call's `authorization` value.
>     > SCOPE-QUESTION (Resolved): The `authorization` value this hook attaches to the gRPC `Context` at receipt is the raw metadata value, scheme included, such as `Bearer fixture-token-user0001`, and only the account access guard (CS-12-003) reads it, as only the guard reads the REST `Authorization` header. The bearer token that `BalanceSummaryController` and `BalanceSummaryGrpcService` hand to the assembly call (CS-05-005) is the token without the scheme that the guard binds on an allow: the request attribute that `AccountAccessFilter` sets on REST and the gRPC `Context` key that `AccountAccessGrpcInterceptor` binds on gRPC. The request deadline still comes from this hook. Each client adds the `Bearer ` scheme itself (CS-09-005 and CS-13-003), so every upstream call carries exactly one scheme. Neither adapter reads the `authorization` header or metadata, strips a scheme, or parses or validates a credential. This holds on every route the hook declares, so the category balance, transaction list and transaction detail adapters (CS-06-003, CS-07-003 and CS-08-003) take the same bound token.
>   - The account access guard (CS-12-003), the assembly and both adapters use that one deadline, so the guard never starts one of its own on these routes.
>   - A Stage 0 rejection marks the cut-off at the rejection and completes the deadline with outcome `error` and status 400, so its completion line carries `cutoffMs` and the receipt `traceId`.
> - Test: `card-servicing-service/src/test/java/com/example/cardservicing/balance/BalanceSummaryEndpointsTest.java` covers both protocols in one class, so AC2 compares the two renderings of one fixture state. It runs against in-JVM loopback stubs registered on `StubHarness` (CS-11-002), with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` overrides and the controllable test clock of the CS-11-004 tests.
>   - REST runs in a Spring web test context holding `TraceIdFilter`, the Stage 0 hook, `AccountAccessFilter` and the controller. Each body is checked against the `GetBalanceSummaryResponse` or `ErrorObject` schema read from `card-servicing-api.yaml`: required properties, no additional property, and the type and pattern of every property, adding no test library.
>   - gRPC runs over a loopback channel to the service hosted through `GrpcServerConfiguration` (CS-11-002) on port 0, behind `GrpcErrorInterceptor`, the Stage 0 interceptor and `AccountAccessGrpcInterceptor`.
> - That makes 4 files: 3 created and 1 edited.
>
> In scope:
>
> - The REST route and the `X-Data-Status` header.
> - The gRPC method.
> - The request deadline started at receipt for every Stage 0 route.
> - Wire forms of amounts, timestamps, the null description and the omitted `groupId`.
> - Error statuses on both protocols.
>
> Not in this story:
>
> - Assembly and degradation logic (CS-05-005).
> - Parameter rules (CS-05-003).
> - The access decision (CS-12-003).
> - The end-to-end step (CS-01-004) and the SLA scenario (CS-01-005).
> - Customer Details fields (field gaps in the CS-01 epic README).
>
> ### 2. Acceptance Criteria
>
> - AC1: `GET /v1/accounts/00000000001/balance-summary` with `Authorization: Bearer fixture-token-user0001`, all stubs healthy and an empty cache, returns 200 and header `X-Data-Status: FRESH`. The body validates against `GetBalanceSummaryResponse` in `card-servicing-api.yaml` and carries the fixture values of account `00000000001`: `accountId` "00000000001", `activeStatus` "Y", `currentBalance` "194.00", `creditLimit` "2020.00", `cashCreditLimit` "1020.00", `currentCycleCredit` "0.00", `currentCycleDebit` "0.00", `openDate` "2014-11-20", `expirationDate` "2025-05-20", `reissueDate` "2025-05-20", no `groupId`, `balancesByTypeGroup` [{`typeCode` "01", `typeDescription` "Purchase", `balance` "0.00"}] and `dataStatus` "FRESH".
> - AC2: On a fixed test clock, `GetBalanceSummary` with `account_id` `00000000001` and `authorization` metadata `Bearer fixture-token-user0001` returns a message whose every field value, `as_of` included, equals the AC1 REST body field of the same name, with snake_case matched to camelCase. `group_id` is unset where the body omits `groupId`.
>   > SCOPE-QUESTION (Resolved): AC1 and AC2 never check the credential the service forwards upstream, and the stubs ignore it, so a doubled scheme would pass both. `BalanceSummaryEndpointsTest` therefore registers test-local header-capturing `StubRegistration` instances under the stub names `card-data` and `reference-data`, which `StubHarness.allRegistered()` never discovers. Each wraps its stub's service definition with a server interceptor that records the received `authorization` metadata value of every call, as the Card Data client test does (CS-09-005). For the AC1 REST request and the AC2 gRPC request, each on an empty cache, the capture holds exactly one value for each of `GetAccount`, `ListCategoryBalances` and `ListTransactionTypes`, and every recorded value equals exactly `Bearer fixture-token-user0001`: one scheme, one space and the token, never a duplicated prefix.
> - AC3: The `X-Data-Status` header equals the body's `dataStatus` for each of `FRESH` (the AC1 request), `STALE` (Card Data stub failing every call, with account and category-balance entries past their fresh TTL and within max stale age) and `PARTIAL` (Reference Data stub failing every call, with no usable type entry).
> - AC4: On `PARTIAL`, every REST `balancesByTypeGroup` element carries `"typeDescription": null` as a present property. Every gRPC `balances_by_type_group` element has `type_description` unset, never an empty string.
> - AC5: On both protocols, amounts are strings with exactly two decimals ("194.00", "2020.00"), never JSON numbers, and `asOf` is RFC 3339 with microseconds and `Z`. When the source value is blank, `groupId` is absent from the REST body and `group_id` is unset. `balancesByTypeGroup` is present, as an empty array, when the account has no category balances.
>
> SCOPE-QUESTION (Resolved): AC5 shows its amount form only through fixture values. That form is the one the amount example `"1234.56"` fixes: a scale-2 decimal rendered as a JSON string with exactly two decimals in the REST body, and as the same string in the gRPC message. So `"1234.56"` passes, while the JSON number `1234.56`, `"1234.5"` and `"1234.560"` fail. The example is illustrative: no fixture account holds that value, and the AC5 fixture assertions `"194.00"` and `"2020.00"` stay as written.
>
> - AC6: Each case below returns its status with an `ErrorObject` body that validates against `card-servicing-api.yaml` and carries the receipt `traceId`. Over gRPC, each returns the matching status code with exactly one packed `ErrorObject`, whose `code` and `message` equal the REST body's for the same case and whose `trace_id` is that call's receipt `traceId`.
>   - 400 `INVALID_ARGUMENT`: `accountId` `00000000000`.
>   - 401 `UNAUTHENTICATED`: `fixture-token-expired`, or no authorization at all.
>   - 403 `PERMISSION_DENIED`: account `00000000011` for `fixture-token-user0001`.
>   - 404 `NOT_FOUND`: account `10000000001`, entitled to `USER0001` and absent from the small fixture set of the in-JVM Card Data stub.
>   - 503 `UNAVAILABLE`: Card Data stub failing every call, with no usable cache entry.
>   - 504 `DEADLINE_EXCEEDED`: Card Data stub latency of 1,000 ms, with no usable cache entry.
> - AC7: For the 403 case on both protocols, the Card Data and Reference Data stub request logs hold zero entries and the cache records zero reads. No account data is read from upstream or cache before access is allowed.
> - AC8: On both protocols, each upstream attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget: with a Card Data stub latency of 350 ms and an empty cache, the stub request log shows exactly 2 `GetAccount` attempts and the request returns 504. Retries number at most two (`UPSTREAM_MAX_RETRIES`), with 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, and a retry starts only if its backoff plus a full 300 ms attempt fits: with the Card Data stub failing every call, the log shows exactly 3 `GetAccount` attempts, and the 404 case of AC6 shows exactly 1.
> - AC9: For each degraded 200, 503 and 504 on both protocols, the logged `cutoffMs` is at most 900 (`REQUEST_DEADLINE_MS`) and the client-measured response arrives below 1,000 ms. The outcomes are:
>   - Card Data failure: `STALE` with a usable entry, otherwise 503 or 504.
>   - Reference Data failure: `PARTIAL`.
>   - Config failure: `dataStatus` unchanged from the AC1 value `FRESH`, with the Config stub failing every call.
>   - Auth failure: 401 for `fixture-token-expired`, 503 with the Auth stub failing every call, and 504 with an Auth stub latency of 2,000 ms.
>
> SCOPE-QUESTION (Resolved): AC9 states its cut-off bound as `cutoffMs` at most 900 without a unit. The bound is the 900 ms request deadline (`REQUEST_DEADLINE_MS`), measured from receipt to the cut-off, so AC9 requires the cut-off of every request it names to fall at or before 900 ms after receipt.
>
> - AC10: On both protocols, a request carrying `x-api-key` but no bearer token returns 401 `UNAUTHENTICATED`, and the Auth stub request log holds zero entries. An inbound `x-api-key` never grants access.
> - AC11: A cold request on either protocol produces exactly one `GetAccount`, one `ListCategoryBalances` and one `ListTransactionTypes` call in the stub request logs, no `GetCardCrossReference` call, and zero Transaction Activity entries. This shows that the adapters make no reads of their own.
> - AC12: On both protocols, the Stage 0 hook starts exactly one request deadline per request, at receipt and ahead of validation. The deadline the account access guard and the assembly receive is the one the hook attached, and each request writes exactly one completion line carrying the receipt `traceId`. The AC6 400 case marks the cut-off at the rejection, and its completion line carries outcome `error` with status 400.
>
> ### 3. Dependencies
>
> - CS-05-003 — supplies Stage 0 account-number validation that runs ahead of both adapters, and the Stage 0 hook this story extends to start the request deadline.
> - CS-05-005 — supplies the single assembly call, its degradation outcomes and the null-description mapping.
>
> Risk: A serializer setting that drops null properties would remove typeDescription on PARTIAL and break the REST contract.

## 4. Design

- [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd)
- [Data model — screen-field coverage matrix](../../../../data-model.md#screen-field-coverage-matrix)
- [TRD — request path](../../../../technical-requirements.md#request-path)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
- [Shared error contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
  - Cases: AC1–AC12 on both protocols, against in-JVM loopback stubs with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE`.
  - REST bodies are checked against the `card-servicing-api.yaml` schemas.
  - Amount assertions on both protocols check the form the amount example `"1234.56"` fixes, a string with exactly two decimals.
  - Forwarded credential: the header-capturing Card Data and Reference Data registrations record, for the AC1 REST and AC2 gRPC requests, every upstream `authorization` value as exactly `Bearer fixture-token-user0001`, never a duplicated prefix.
  - Deadline cases assert a logged `cutoffMs` ≤ 900 and a client response below 1,000 ms.
- Integration: Not applicable — the integration suites belong to the external epics. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Applies, through the account balance summary SLA scenario (CS-01-005).
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Applies, through the account balance summary end-to-end step (CS-01-004).
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: the four-step flow for account `00000000001` returns the AC1 values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — thin protocol adapters over one shared assembly call.

Rationale: one assembly result rendered twice is the only way the HTTP route and the remote procedure method return the same values and the same data status for one account.

- Rejected: Option B, a separate assembly per protocol, because values and data status can diverge between protocols.
- Rejected: exposing only HTTP and translating remote procedure calls through a gateway, because it adds a hop inside the 1,000 millisecond budget.
- Rejected: starting the request deadline inside each adapter, because the adapters run after the access guard and the deadline would leave out the authentication time.

### Shared by both options

- Topology: an HTTP controller and a remote procedure method in the service process, behind one receipt stage and one access guard.
- Naming: HTTP field names are the remote procedure field names written in camel case.
- Addressing: one HTTP path per account and one remote method that takes the account number.
- Isolation: the receipt stage starts the request deadline, then receipt validation and the access guard run ahead of both protocols.
- Fallback: degraded results carry their data status in the body and in the data status response header.
- Provisioning: the service serves both protocols on its own ports.
- Cut-over: the deferred UI binds to this fixed interface.
- Failures: every error leaves through the standard error object with the receipt trace identifier.

### Design Option A — thin adapters over one assembly

The HTTP controller and the remote procedure method each take the account number, the caller's token and the request deadline, make one call to the shared balance assembly, and render the one result it returns. The result model carries both renderings, so a missing description becomes a present null on HTTP and an unset field on the remote procedure. Failures leave the adapters as typed failures, and the standard error mapping renders them on each protocol.

How it behaves:

- The receipt stage starts the request deadline before the account number check, and both adapters hand that same deadline to the assembly.
- The HTTP controller copies the data status from the rendered body into the data status response header.
- The HTTP body keeps a present null description and leaves out a blank account group, whatever the default serializer setting.
- The remote procedure method returns the message rendering of the same result.
- A rejection from validation or the guard, and a failure from the assembly, reach the caller with their status and the standard error object.
- Neither adapter reads the cache or any upstream, so a cold request makes exactly one call per upstream read.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Both protocols call one assembly (vs one assembly each) | identical values on both protocols | one shared change point |
| 2 | Response header copied from the body status (vs computed separately) | header and body cannot disagree | the header waits for body rendering |
| 3 | Adapters raise typed failures (vs building error bodies) | one error shape across protocols | adapters cannot tailor messages |
| 4 | Absence rendered by the result model (vs per adapter) | null and unset follow one rule | the model knows both protocols |

Effort and risk: two small adapters, one receipt stage change and one test class, with the risk in a serializer setting that drops the present null.

### Design Option B — separate assembly per protocol

Each protocol owns its own assembly path: the HTTP controller and the remote procedure method each launch the account, balance and type reads, resolve the data status and build their own response. Both still sit behind the receipt stage and the access guard, and both use the standard error object.

How it differs:

- Each adapter launches its own account, balance and type reads.
- Each adapter applies the data status precedence rule itself.
- Each adapter decides how to show a missing description.
- A change to the summary fields lands in two places.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Each protocol assembles its own summary (vs shared assembly) | each protocol tunes its reads | values can diverge |
| 2 | Status resolved in each adapter (vs shared resolver) | local control | two copies of the precedence rule |
| 3 | Absence decided in each adapter (vs result model) | protocol-native handling | empty text can replace absence |

Effort and risk: two full assembly paths and their tests, with high risk because the two protocols can return different values for the same account.

### Recommendation

- Option: Option A.
- Why: one assembly is the only way both protocols return equal values.
- Strongest argument against: the result model is coupled to two renderings.
- Shared items that do not decide the choice: the contract shapes, the error object, the guard order and the deadline started at receipt.
- Flip conditions: the two protocols need different field sets.

## Design Validation

### Validation of Option A

Settled assumptions:

- The contracts fix field names and the null and unset rule.
- The assembly returns one result per request.
- The receipt stage starts the deadline, and validation and the guard run before either adapter.

Open assumptions:

- Open → which messages the UI shows per error status, settled by the deferred UI run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the one assembly result renders the fixture values |
| AC2 | Holds | both renderings come from one result |
| AC3 | Holds | the header copies the body status |
| AC4 | Holds | the result model renders absence once per protocol |
| AC5 | Holds | the result model keeps amounts and times as contract strings |
| AC6 | Holds | every failure passes to the standard error mapping |
| AC7 | Holds | the adapters run only after the guard allows |
| AC8 | Holds | the call policy inside the clients sets attempts and retries |
| AC9 | Conditional | it rests on the call policy's residual-budget rule |
| AC10 | Holds | the guard takes identity only from the bearer token |
| AC11 | Holds | the adapters make no reads of their own |
| AC12 | Holds | the receipt stage starts the one deadline before validation |

### Validation of Option B

Settled assumptions:

- The contracts fix field names and the null and unset rule.
- Each protocol builds one result per request.
- The receipt stage starts the deadline, and validation and the guard run before either adapter.

Open assumptions:

- Open → which messages the UI shows per error status, settled by the deferred UI run.
- Open → whether the two read paths share cache entries, settled by measuring both paths on the test stack.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the HTTP path assembles the fixture values |
| AC2 | At risk | values can diverge between the two assemblies |
| AC3 | At risk | the header and the body status come from separate logic |
| AC4 | At risk | empty text can slip in for an absent description |
| AC5 | Holds | the contracts fix the amount and time forms |
| AC6 | Holds | both paths use the standard error mapping |
| AC7 | Holds | both paths run only after the guard allows |
| AC8 | Holds | the call policy inside the clients sets attempts and retries |
| AC9 | Conditional | it rests on the call policy's residual-budget rule |
| AC10 | Holds | the guard takes identity only from the bearer token |
| AC11 | Breaks | each adapter makes its own reads by design |
| AC12 | Holds | the receipt stage is shared by both options |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-05-003, CS-05-005 |
| Status | TBD |
| Estimated Files Touched | 4 |
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
