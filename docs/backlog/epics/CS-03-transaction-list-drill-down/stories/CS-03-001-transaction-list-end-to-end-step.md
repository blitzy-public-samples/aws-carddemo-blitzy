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

Recommended: Design Option A — step three takes its filter from the category row the category balance step received and hands its first transaction row to the transaction detail step.

Rationale: a flow step that opens the transactions behind the category row step two received, as the deferred screen will, proves the category-to-transaction link along the real request path and gives the detail step the transaction it opens.

- Rejected: Design Option B, fixed filter values written into the step — it misses a broken hand-off between steps, so the drill-down link stays unproven.

### Shared by both options

- Topology: the end-to-end client on the build host calls the service in the shared test stack, which reaches the five stub services, the relational store behind them and the cache.
- Naming: the step is the third of the four interaction steps of one ordered flow, and it is named for the transaction list screen.
- Addressing: the account sits in the request path, the type code, category code and page size travel as query values on the service's published port, and the stub log addresses come from the end-to-end harness.
- Isolation: the step reads only sample accounts, so the synthetic volume in its own number range never changes an expected value, and the rejected calls run first, so no background configuration refresh falls inside their log windows.
- Fallback: the step has no fallback path, so a failed step fails the flow and the transaction detail step does not run on a missing transaction row.
- Provisioning: the end-to-end stage starts the stack before the suite and removes it after, and the shared loader seeds the sample set the step reads.
- Cut-over: the deferred screen replaces the end-to-end client with no service change, and the real services replace the stubs by address and credential alone.
- Failures: any status, item, order or header mismatch fails the step and reports the trace identifier, and degraded or failed upstream outcomes are proven under injection by the transaction list retrieval story and the integration suites.

### Design Option A — filter taken from the category balance step

The step reads the type code and category code from the row the category balance step received and sends them as the list filter. It sends the four rejected calls first, then the filtered call, the return-item filter, the unknown type code and the unfiltered call, one at a time. It checks each answer by status, rows, order, data status and error object, reads the new stub log entries of each call, and stores the first transaction row for the transaction detail step.

How it behaves:

- A failed category balance step stops the step before its first call, with a message naming that step.
- The filtered answer must hold the five purchase rows of the first sample account in descending transaction number order, with no next-page token.
- The return-item filter must hold the one return row, and the unfiltered answer must hold all six rows.
- The unknown type code must return an empty list with no reference-data read.
- Expected rows are literal fixture values, compared field by field with the interface example.
- Stub request log counts are read before and after each request, and each allowed call must show the two authentication operations and one transaction read.
- The data status header and the data status in the body are both checked and must read fresh.
- Every call passes the client's deadline check on the logged cut-off time and the client latency.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Filter taken from the previous step's row (vs fixed values) | the step proves the hand-off | step three cannot run alone |
| 2 | Expected rows as literal fixture values (vs a store read) | the step stays free of the store | a fixture change needs a step edit |
| 3 | Log counts compared before and after each call (vs a log reset) | no reset hook is needed | the counts rely on the suite running alone |
| 4 | First transaction row kept for the next step (vs the detail step choosing its own row) | the detail step drills from a returned row | a failed step three stops the detail step |

Effort and risk: small effort in one existing file, with step-order coupling inside the flow as the main risk.

### Design Option B — fixed filter values

The step writes the purchase type code and the regular sales draft category code into its filtered call instead of reading them from the category balance step. Call order, row checks, stub log checks and the transaction-row hand-off match Option A. The step can run without the category balance step, but it no longer proves that the category row leads to the transaction list.

How it differs:

- The filtered call always sends the purchase filter, whatever the category balance step returned.
- A broken hand-off from the category balance step goes unnoticed, so the category-to-transaction link stays unproven.
- The step runs alone, so a failed category balance step no longer stops it.
- A change to the sample category row needs a matching edit in two steps.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Fixed filter values (vs the row from the previous step) | the step runs without the category balance step | the drill-down link goes unchecked |
| 2 | Expected rows as literal fixture values (vs a store read) | the step stays free of the store | a fixture change needs a step edit |
| 3 | Log counts compared before and after each call (vs a log reset) | no reset hook is needed | the counts rely on the suite running alone |

Effort and risk: small effort in one existing file, at risk on the hand-off criterion, which fixed values cannot meet.

### Recommendation

- Option: Design Option A.
- Why: it exercises the drill-down the way the deferred screen will, from the row the category view returned, and meets the hand-off criterion that Option B cannot.
- Strongest argument against: the step cannot run without the category balance step, so a failure there hides every transaction-list check behind it.
- Shared items that do not decide the choice: the stack, the end-to-end client, the fixture values, the call order, the status and log assertions, the deadline check and the transaction-row hand-off.
- Flip conditions: the end-to-end harness being unable to carry state between steps, or the flow steps having to run in parallel.

## Design Validation

### Option A

Settled assumptions:

- The sample holds six transactions for the first sample account, all sharing one original time and a blank processed time.
- The category balance step returns one purchase row with the regular sales draft category.
- The default page size of twenty holds all six rows of the first sample account.
- The first regular fixture user is entitled to the first ten sample accounts, and the eleventh sample account sits outside that range.
- The flow empties the cache before step one, and no earlier step reads a transaction page.
- A rejected call reads no cache entry and launches no configuration refresh.

Open assumptions:

- Step order kept across the flow Open → the method ordering of the flow class that the balance summary step creates, confirmed when the first stack run reaches step three.
- Completion log line with the cut-off time Open → the completion log reader of the end-to-end harness story, confirmed on the first stack run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | step two must run ahead of step three in the same flow instance |
| AC2 | Holds | the first sample account holds one return row and six rows in all |
| AC3 | Holds | the transaction list makes no reference-data read |
| AC4 | Holds | page-size validation rejects the call before any upstream call |
| AC5 | Holds | token validation fails ahead of the authorization and data stages |
| AC6 | Holds | the access guard denies the call before any data read |
| AC7 | Holds | with failure injection off and an emptied cache each upstream operation runs once |
| AC8 | Conditional | the harness must expose the completion log line with the cut-off time |
| AC9 | Holds | with failure injection off every read answers fresh |

### Option B

Settled assumptions:

- The sample holds six transactions for the first sample account, all sharing one original time and a blank processed time.
- The default page size of twenty holds all six rows of the first sample account.
- The first regular fixture user is entitled to the first ten sample accounts, and the eleventh sample account sits outside that range.
- The fixed purchase filter equals the category row of the first sample account's one balance.

Open assumptions:

- Purchase row unchanged by a fixture refresh Open → the transaction fixture load report on each stack start.
- Completion log line with the cut-off time Open → the completion log reader of the end-to-end harness story, confirmed on the first stack run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | the fixed filter is never read from step two |
| AC2 | Holds | the first sample account holds one return row and six rows in all |
| AC3 | Holds | the transaction list makes no reference-data read |
| AC4 | Holds | page-size validation rejects the call before any upstream call |
| AC5 | Holds | token validation fails ahead of the authorization and data stages |
| AC6 | Holds | the access guard denies the call before any data read |
| AC7 | Holds | with failure injection off and an emptied cache each upstream operation runs once |
| AC8 | Conditional | the harness must expose the completion log line with the cut-off time |
| AC9 | Holds | with failure injection off every read answers fresh |

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
