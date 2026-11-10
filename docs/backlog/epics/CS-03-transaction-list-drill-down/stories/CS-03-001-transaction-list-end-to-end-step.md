# CS-03-001: Transaction list end-to-end step

Parent epic: [CS-03 Transaction List Drill-Down](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want to open the transactions behind a category row, so that I see the activity that makes up that balance.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: step 3 of the one ordered end-to-end flow (balance summary → category balance → transaction list → transaction detail) that stands in for the deferred Card Servicing UI. Through the end-to-end client of CS-01-002 it opens the transaction list of the Compose stack `tests/docker-compose.yml` (CS-01-001) with `GET /v1/accounts/{accountId}/transactions` for account `00000000001`, filtered by the category row that step 2 (CS-02-001) received, as the UI's drill-down from a category row will. It checks every column that Use Cases · COTRN00C shows against its contract field (the TRD field map: Transaction ID → `transactionId`, Date → `originalTimestamp` from Data Layouts · CVTRA05Y · TRAN-ORIG-TS, Description → `description`, Amount → `amount`), plus `typeCode`, `categoryCode` and `processedTimestamp` of Data Layouts · CVTRA05Y, reads the upstream calls behind each answer from the stub request logs, and hands the transaction row it yields to the transaction detail step. Expected values come from the CardDemo sample records: Data Layouts · CVTRA06Y for the six daily transactions whose card number Data Layouts · CVACT03Y links to account `00000000001`, all with the original timestamp `2022-06-10 19:27:53.000000` and a blank processed timestamp, which the fixture load sets to the original timestamp. `./gradlew :card-servicing-service:e2eTest` runs the flow against the running stack. Target path:
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/e2e/CardServicingFlowE2eTest.java` (edit; created by CS-01-004):
>   - Step 3: the test method at order 3 of the `e2e`-tagged JUnit 5 flow class, running after step 2 in the same class instance, with the client that the CS-01-002 suite base injects. It never re-implements steps 1 and 2, the client or the cache emptying before step 1.
>   - Filter: at its start the step reads `typeCode` and `categoryCode` through step 2's hand-off accessor, which holds them from the first `categoryBalances` element of step 2's filtered answer and fails the calling step with a message naming step 2 when step 2 did not pass.
>   - Call order in step 3, one call at a time through the client: `GET /v1/accounts/00000000001/transactions?pageSize=0`, then `GET /v1/accounts/00000000001/transactions?pageSize=101`, then `GET /v1/accounts/00000000001/transactions` with `fixture-token-expired`, then `GET /v1/accounts/00000000011/transactions`, then `GET /v1/accounts/00000000001/transactions?typeCode=<step 2 type code>&categoryCode=<step 2 category code>`, then `GET /v1/accounts/00000000001/transactions?typeCode=03&categoryCode=0001`, then `GET /v1/accounts/00000000001/transactions?typeCode=99`, then `GET /v1/accounts/00000000001/transactions`. Every call except the third carries the client's default token `fixture-token-user0001` of `USER0001`. The four rejected calls come first because none of them reads the cache or launches the background Config refresh, so no refresh started by an allowed call of this step can land in a rejected call's request-log window.
>   - Hand-off: the step stores the `transactionId` of the first `transactions` element of the filtered 200 answer in the flow instance for the transaction detail step (CS-04-001). Its accessor fails the calling step with a message naming step 3 when step 3 did not pass.
> - Together this is the 1 file touched.
>
> In scope:
>
> - Step 3 of the flow: the filtered, return-item, unknown-type and unfiltered transaction-list calls and their item, order, header and body status checks.
> - The page-size, expired-token and access-denial checks for this endpoint, with their error objects.
> - The stub request-log counts, `cutoffMs` and client-measured latency checks for each call of the step.
> - The category-row read from step 2 and the transaction-row hand-off to the transaction detail step.
>
> Not in this story:
>
> - Steps 1 and 2 (CS-01-004, CS-02-001), the paging walk (CS-03-002) and the transaction detail step (CS-04-001).
> - The endpoint's service behaviour (CS-07-001, CS-07-002, CS-07-003) and the gRPC form of the operation (CS-07-003).
> - The end-to-end client and the Compose stack (CS-01-002, CS-01-001).
> - Injected-failure cases (CS-07-002 and the integration suites).
> - Latency percentiles of the transaction list (CS-03-003).
> - UI code.
>
> ### 2. Acceptance Criteria
>
> - AC1: The `typeCode` and `categoryCode` sent in the fifth call are the values step 2 stored from its filtered category-balance answer in the same flow run, `"01"` and `"0001"`, and when step 2 did not pass, step 3 fails with a message naming step 2 before sending any call. `GET /v1/accounts/00000000001/transactions?typeCode=01&categoryCode=0001` with `fixture-token-user0001` returns 200 with `accountId` `"00000000001"` and exactly five `transactions` elements, in this order of `transactionId`, `amount` and `description`: `0000000776200014` `"985.22"` "Purchase at Lebsack and Sons"; `0000000755736377` `"906.44"` "Purchase at Torp-Stark"; `0000000624335286` `"598.44"` "Purchase at Crona, Turner and Hane"; `0000000555363230` `"484.99"` "Purchase at Metz, Blanda and Homenick"; `0000000503557384` `"81.44"` "Purchase at Casper Group". Each element carries `typeCode` `"01"`, `categoryCode` `"0001"`, and `originalTimestamp` and `processedTimestamp` `"2022-06-10T19:27:53.000000Z"`, and equals, field for field, the element with the same `transactionId` in the `firstPage` 200 example of `ListTransactions` in `card-servicing-api.yaml`. The order falls to `transactionId` descending because all six sample transactions of the account share one timestamp. The body holds no `nextPageToken` property, because the default page size of 20 holds all five, the `X-Data-Status` header and the body's `dataStatus` are both `FRESH`, and `asOf` matches `^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z$`. After the call the flow instance holds `transactionId` `"0000000776200014"` for the transaction detail step.
> - AC2: `GET /v1/accounts/00000000001/transactions?typeCode=03&categoryCode=0001` returns 200 with exactly one `transactions` element, `transactionId` `0000000532120892` with `typeCode` `"03"`, `categoryCode` `"0001"`, `amount` `"-70.77"` and `description` "Return item at Frami-Hyatt", and no `nextPageToken` property. `GET /v1/accounts/00000000001/transactions` with no filter returns 200 with exactly six elements in the order `0000000776200014`, `0000000755736377`, `0000000624335286`, `0000000555363230`, `0000000532120892`, `0000000503557384`, equal field for field to the six elements of the `firstPage` example, and no `nextPageToken` property. Both answers carry `FRESH` in the `X-Data-Status` header and the body's `dataStatus`.
> - AC3: `GET /v1/accounts/00000000001/transactions?typeCode=99`, a well-formed type code absent from the reference data, returns 200 with `accountId` `"00000000001"`, the `transactions` array present and empty, no `nextPageToken` property, and `FRESH` in the header and the body. The call's entries show no entry on the Reference Data stub, because the transaction list makes no reference-data call.
> - AC4: `GET /v1/accounts/00000000001/transactions?pageSize=0` and `GET /v1/accounts/00000000001/transactions?pageSize=101` each return 400 with `code` `INVALID_ARGUMENT`, `message` `Page size must be an integer from 1 to 100`, exactly one `details` entry, whose `field` is `pageSize`, and a non-empty `traceId`. For each of the two calls the entries are empty on all five stubs, because validation precedes every upstream call.
> - AC5: `GET /v1/accounts/00000000001/transactions` with `fixture-token-expired` returns 401 with `code` `UNAUTHENTICATED` and a non-empty `traceId`. The call's entries show only `ValidateToken` on the Auth stub, with outcome `UNAUTHENTICATED`, and no entry on the Card Data, Transaction Activity, Reference Data or Config stub.
> - AC6: `GET /v1/accounts/00000000011/transactions` with `fixture-token-user0001` returns 403 with `code` `PERMISSION_DENIED` and a non-empty `traceId`, because `USER0001` is entitled to `00000000001`–`00000000010` among the sample accounts and `00000000011` is a sample account outside that range, entitled to `USER0002`. The call's entries show only `ValidateToken` and `AuthorizeAccountAccess` on the Auth stub and no entry on the Card Data, Transaction Activity, Reference Data or Config stub, so no account data is read from upstream before access is allowed; that no cache read precedes the access decision either is proven by the guard ordering of CS-12-003.
> - AC7: The step runs under the stack defaults of the upstream call policy: each attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget, and at most two retries (`UPSTREAM_MAX_RETRIES`) follow, after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, each starting only when its backoff plus a full 300 ms attempt fits before the 900 ms cut-off. With failure injection off (`STUB_LATENCY_MS` `0` and `STUB_FAILURE_RATE` `0`, the Compose defaults), the entries of each of the four allowed calls show `cardservicing.auth.v1.AuthService/ValidateToken` and `cardservicing.auth.v1.AuthService/AuthorizeAccountAccess` on the Auth stub and `cardservicing.transactionactivity.v1.TransactionActivityService/ListTransactions` on the Transaction Activity stub, each exactly once with outcome `OK`, and no entry on the Card Data or Reference Data stub, because the cache emptying before step 1 leaves no transaction page cached and each allowed call carries a different filter set. Every stub request-log entry of the step records a `durationMs` of at most 300, and the client reports no retried call for any of the eight calls.
> - AC8: Each of the eight calls of this step receives its full answer below 1,000 ms of client-measured latency, and the completion log line the client matches to it shows `cutoffMs` ≤ 900, the 900 ms deadline (`REQUEST_DEADLINE_MS`) measured from receipt to the cut-off.
> - AC9: The step fails when any 200 answer of the step carries `STALE` or `PARTIAL` in the header or the body, or when any call of the step returns 503 or 504. The degraded and failed outcomes of each upstream call this endpoint makes are proven under injection by CS-07-002 and the integration suites CS-10-007, CS-12-005 and CS-11-007, not by this step: Auth `ValidateToken` or `AuthorizeAccountAccess` failing closed with 401, 503 or 504 and no transaction data read; Transaction Activity `ListTransactions` returning 503 after retries or 504 at the deadline, never a stale page; Config `GetConfig` failing with the response and its `X-Data-Status` unaffected.
>
> ### 3. Dependencies
>
> - CS-02-001 — supplies the category row (type code and category code) this step drills into; that step brings step 1, the end-to-end client and the Compose stack through its own prerequisites.
>
> Risk: a change to the category row step 2 receives changes this step's filter with no edit to this step, and a change to the sample transactions or cross-reference of account `00000000001` changes the AC1 and AC2 values; either fails the step.

## 4. Design

- [Sequence — transaction list](../../../../architecture/sequence-transaction-list.mmd)
- [Data model — transaction_record](../../../../data-model.md#transaction_record)
- [Data model — screen-field coverage matrix](../../../../data-model.md#screen-field-coverage-matrix)
- [Data model — fixture rules](../../../../data-model.md#fixture-rules)
- [TRD — Request path](../../../../technical-requirements.md#request-path)
- [TRD — Deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — Resilience](../../../../technical-requirements.md#resilience)
- [TRD — Card Servicing UI field map](../../../../technical-requirements.md#card-servicing-ui)
- [TRD — Test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- Contracts:
  - [card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
  - [card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
  - [common.proto](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)
  - [auth-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/auth-service.yaml) (fixture tokens)
  - [auth_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto) (fixture tokens)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Not applicable — the story adds an end-to-end step only, with no production code and no untagged case. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
- Integration — Not applicable — the story changes no client or stub, and the injected upstream outcomes are proven by the suites named in AC9. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — CS-03-003 measures this endpoint. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end — Applies — this story is step 3 of the four-step flow for account `00000000001`, covering AC1–AC9 on the stack. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the flow returns the expected fixture values and each stub's request log shows at least one call across the flow, with every call's `cutoffMs` ≤ 900 and client-measured latency below 1,000 ms.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — step three filters by step two's category row and hands its first transaction row to the detail step.

Rationale: drilling down as the deferred screen will proves the category-to-transaction link along the real request path.

- Rejected: Design Option B, fixed filter values — it misses a broken hand-off between steps.

### Shared by both options

- Topology: the end-to-end client on the build host calls the test stack's service, which reaches five stubs, a relational store and the cache.
- Naming: step three of the four-step flow is named for the transaction list screen.
- Addressing: the request path carries the account, query values carry the filter and page size, and the harness supplies the stub log addresses.
- Isolation: reading only sample accounts shields expected values from synthetic volume, and running rejected calls first keeps configuration refreshes out of their log windows.
- Fallback: none exists, so a failed step fails the flow.
- Provisioning: the end-to-end stage starts and removes the stack around the suite, and the shared loader seeds the samples.
- Cut-over: the deferred screen replaces the client with no service change, and real services replace stubs by address and credential.
- Failures: any status, row, order, data status, error object or deadline mismatch fails the step with its trace identifier, and the list retrieval story and integration suites prove injected upstream failures.

### Design Option A — filter from the category balance step

The step sends step two's type code and category code as the list filter. It makes eight calls one at a time, four rejected ones first.

How it behaves:

- A failed step two stops the step before any call, naming step two.
- The filtered answer holds five purchase rows by descending transaction number, the return-item answer one return row, the unknown type code none and the unfiltered answer all six.
- Rows match the interface example field by field, and each allowed call's stub logs show two authentication operations and one transaction read.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Filter from step two's row (vs fixed values) | proves the hand-off | step three cannot run alone |
| 2 | Literal fixture rows (vs a store read) | no store access | fixture changes need step edits |
| 3 | Before-and-after log counts (vs a log reset) | no reset hook | counts need the suite running alone |
| 4 | First returned row handed on (vs the detail step choosing) | the detail step opens a returned row | a failed step three blocks the detail step |

Effort and risk: small, in one existing file, at risk from step-order coupling.

### Design Option B — fixed filter values

The filtered call carries a fixed purchase type and regular sales draft category. Everything else matches Option A.

How it differs:

- The purchase filter goes out whatever step two returned.
- A sample category row change needs edits in two steps.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Fixed filter values (vs step two's row) | runs without step two | the drill-down link goes unchecked |

Effort and risk: small, in one existing file, at risk on the hand-off criterion.

### Recommendation

- Option: Design Option A.
- Why: only it meets the hand-off criterion.
- Strongest argument against: a failed step two hides every transaction-list check.
- Shared items that do not decide the choice: the stack, client, call order, literal fixture rows, before-and-after log counts, status and deadline checks and row hand-off.
- Flip conditions: a harness unable to carry state between steps, or steps that must run in parallel.

## Design Validation

### Option A

Settled assumptions:

- The first sample account's six transactions share one original time and a blank processed time.
- Step two returns one purchase row in the regular sales draft category.
- The default page size of twenty holds all six rows.
- The first regular fixture user is entitled to sample accounts one to ten only.
- The cache is emptied before step one, and no earlier step reads transactions.
- A rejected call reads no cache entry and launches no configuration refresh.

Open assumptions:

- Step order kept across the flow Open → the balance summary step's flow ordering, confirmed on the first stack run.
- Completion log line with the cut-off time Open → the harness log reader on the first stack run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | step two must run first |
| AC2 | Holds | the account has one return row among six |
| AC3 | Holds | the list reads no reference data |
| AC4 | Holds | validation precedes every upstream call |
| AC5 | Holds | token validation fails before authorization |
| AC6 | Holds | denial precedes any data read |
| AC7 | Holds | each operation runs once with injection off |
| AC8 | Conditional | the harness must expose the cut-off time |
| AC9 | Holds | injection off keeps every read fresh |

### Option B

Settled assumptions:

- Option A's sample, page-size and entitlement assumptions hold.
- The fixed purchase filter equals the first sample account's one category row.

Open assumptions:

- Purchase row unchanged by a fixture refresh Open → the transaction fixture load report on each stack start.
- Completion log line with the cut-off time Open → the harness log reader on the first stack run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | the filter never comes from step two |
| AC2 | Holds | the account has one return row among six |
| AC3 | Holds | the list reads no reference data |
| AC4 | Holds | validation precedes every upstream call |
| AC5 | Holds | token validation fails before authorization |
| AC6 | Holds | denial precedes any data read |
| AC7 | Holds | each operation runs once with injection off |
| AC8 | Conditional | the harness must expose the cut-off time |
| AC9 | Holds | injection off keeps every read fresh |

## Metadata

| Field | Value |
|---|---|
| Build wave | W3 |
| Depends on | CS-02-001 |
| Status | TBD |
| Estimated Files Touched | 1 |
| Issue Type | Story |
| Epic Name | Transaction List Drill-Down |
| Epic Link | CS-03 |
| Story Points | 2 |
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
