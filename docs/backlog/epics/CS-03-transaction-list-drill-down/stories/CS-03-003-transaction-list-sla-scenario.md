# CS-03-003: Transaction list SLA scenario

Parent epic: [CS-03 Transaction List Drill-Down](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a product owner, I want the transaction list measured at full volume including sparse filters, so that the 1-second SLA and the indexing decision are proven.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Directive: 1-second SLA
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: one scenario class measures `GET /v1/accounts/{accountId}/transactions`, the list that `Use Cases · COTRN00C` pages, through the performance runner of CS-01-003, and runs the D4 filter pressure-test. The index key `(account_id, processed_timestamp desc, transaction_id desc)` omits the type and category codes, so a filtered page scans one account's entries newest first until `pageSize + 1` match, and its cost grows with that account's history divided by the filter's selectivity. The scenarios run against the Compose stack `tests/docker-compose.yml` (CS-01-001), whose loader adds the synthetic volume: 100,000 category balances (CS-09-007) and 1,000,000 transactions (CS-10-008), of which `10000000001` holds 100,000 with exactly 20 on type `05` / category `0001`, `10000000002`–`10000009001` hold 100 each and `10000009002`–`10000010000` hold none. Type `05` has the one category `0001` in the reference data (`Reference Tables · TRANCATG file`), so a `typeCode=05` filter selects the same 20 rows, and no other account carries type `05`. `./gradlew :card-servicing-service:performanceTest` runs the class, and CI runs that task as the Performance stage. Target path:
>
> - `tests/performance/src/main/java/com/example/cardservicing/performance/scenarios/TransactionListSlaScenario.java` (create): a JUnit 5 test class in the performance source set of CS-01-003, which compiles it against the module's runtime classpath, so the build gains no dependency and no load-testing tool or library.
>   - Load and thresholds: each scenario method builds the runner with `LoadRunner.fromSystemProperties()` and passes one `LoadRunner.Scenario` to its `run` method. The runner holds the load shape, 10 concurrent virtual users × 100 sequential calls, 1,000 measured calls, sent through the JDK `java.net.http.HttpClient` with two virtual users per regular identity `USER0001`–`USER0005` and their tokens `fixture-token-user0001`–`fixture-token-user0005` (CS-12-001). It computes p50, p95 and p99 of client-measured latency over the 200 responses and fails the scenario when any of them is 1,000 ms or more. "At the baseline load" in section 2 means this shape and these thresholds.
>
> > SCOPE-QUESTION (Resolved): These thresholds cover the whole 1,000-call window, of which only the 200 calls of `USER0001` reach `10000000001`, so they do not establish the percentiles of each large-history shape on that account. For scenarios 1–4, after `run` the class therefore pairs each call record of the run result with the target it was sent for (CS-01-003), takes the records whose target account is `10000000001`, the 200 calls of `USER0001`, and fails the scenario unless there are exactly 200. It computes p50, p95 and p99 of client-measured latency over them with the nearest-rank `PercentileCalculator` of CS-01-003 and fails the scenario when any of them is 1,000 ms or more, with an `AssertionError` naming the scenario, the three values and the sample size. The runner's whole-run thresholds still apply. "At the baseline load" in AC1–AC4 therefore means both the whole-run thresholds and this large-history gate, and the end-to-end p99 of AC10 is held to both.
>
>   - Entitlements: the runner checks every target against the ranges its identity holds in `fixtures/auth-identities.yaml`. `10000000001` lies only in the slice of `USER0001`, `10000000001`–`10000002000`, so it is the target of `USER0001` alone, and every other identity calls accounts of its own slice: `USER0002` `10000002001`–`10000004000`, `USER0003` `10000004001`–`10000006000`, `USER0004` `10000006001`–`10000008000` and `USER0005` `10000008001`–`10000010000`, the only slice holding empty-history accounts.
>   - Scenarios, run in this order and each named for its shape, which also names its report file under `card-servicing-service/build/reports/performance/`:
>     1. `transaction-list-large-history-unfiltered`: `USER0001` calls `10000000001` at `pageSize=20`, the first page and the 99 pages at the tokens of the large-history walk.
>     2. `transaction-list-large-history-type-filter`: `USER0001` calls the first page of `10000000001` with `typeCode=05` at each page size from 20 to 99, 80 targets.
>     3. `transaction-list-large-history-category-filter`: `USER0001` calls `10000000001` with `categoryCode=0001` at `pageSize=20`, the first page and the 99 pages at the tokens of the large-history walk.
>     4. `transaction-list-large-history-both-filters`: `USER0001` calls the first page of `10000000001` with `typeCode=05&categoryCode=0001` at each page size from 20 to 99, 80 targets.
>     5. `transaction-list-hundred-transaction-accounts`: every identity calls the first page of 100 hundred-transaction accounts at `pageSize=20`, unfiltered: `USER0001` `10000000002`–`10000000101`, `USER0002` `10000002101`–`10000002200`, `USER0003` `10000004101`–`10000004200`, `USER0004` `10000006101`–`10000006200` and `USER0005` `10000008101`–`10000008200`.
>     6. `transaction-list-empty-history-accounts`: `USER0005` calls the first page of the empty-history accounts `10000009002`–`10000009101` at `pageSize=20`, unfiltered. `USER0001`–`USER0004` hold no empty-history account, so each calls the first page of 100 hundred-transaction accounts with `typeCode=05` at `pageSize=20`, a filter no hundred-transaction account matches: `USER0001` `10000000102`–`10000000201`, `USER0002` `10000002201`–`10000002300`, `USER0003` `10000004201`–`10000004300` and `USER0004` `10000006201`–`10000006300`.
>   - Other identities in scenarios 1–4: `USER0002`–`USER0005` call the first page at `pageSize=20` with the scenario's filters on the first 100 accounts of their slices, `10000002001`–`10000002100`, `10000004001`–`10000004100`, `10000006001`–`10000006100` and `10000008001`–`10000008100`, so ten virtual users stay concurrent while only `USER0001` reaches the large-history account.
>   - Stub conditions (CS-01-003 AC7): every scenario declares the no-`DEADLINE_EXCEEDED`-retry condition on `cardservicing.transactionactivity.v1.TransactionActivityService/ListTransactions`, and scenarios 1–4 also declare a 150 ms p99 duration limit on that operation, so the rerun of AC10 runs the class unchanged.
>
> > SCOPE-QUESTION (Resolved): The stub request log names the account of each `ListTransactions` entry (CS-11-002), while the declared 150 ms p99 limit, which the runner evaluates over every `ListTransactions` entry in the window, also covers the entries of the 800 calls of `USER0002`–`USER0005` on their hundred-transaction accounts, so it does not establish the stub p99 of `10000000001`. For scenarios 1–4 the class therefore reads the highest `sequence` of the Transaction Activity stub request log through `GET /internal/stub/request-log` immediately before `run` and, after `run`, takes the entries above it, the same window as the runner's, because scenarios never overlap and every pre-flight request precedes the mark. It keeps the window's `ListTransactions` entries whose `accountId` is `10000000001`, the large-history stub sample, so no entry of another account can enter it. The class then fails the scenario with an `AssertionError` naming the cause when a `ListTransactions` entry of the window carries no `accountId`, so its account is unknown; when the sample holds fewer entries than the shape's distinct measured pages, 100 for scenarios 1 and 3 and 80 for scenarios 2 and 4, each of which misses the cache at its first call because the walk wrote only page-size-100 keys, or more than 200, the calls of `USER0001`; when any sample entry has outcome `CANCELLED` or `DEADLINE_EXCEEDED` or a duration of 300 ms or more; or when the sample's nearest-rank p99 `durationMs`, computed with `PercentileCalculator` (CS-01-003), exceeds 150 ms. The count checks precede the percentile, so an empty sample fails on its count and never reaches `PercentileCalculator`, which rejects an empty set. The runner's declared whole-window limit and no-`DEADLINE_EXCEEDED`-retry condition still apply.
>
>   - Pre-flight: before scenario 1 the class sends the denial request of AC11. Before each scenario it sends that scenario's unmeasured requests, the large-history walk for scenarios 1–4 and the content probes for scenario 6, through its own JDK `java.net.http.HttpClient`, parses each body with the Jackson parser Spring Boot already brings, and reads the Transaction Activity stub request log through `GET /internal/stub/request-log` (CS-11-002) at the address in `cardservicing.performance.stub.transaction-activity`. Pre-flight requests precede the runner's stub-log start mark and sit outside the percentiles, and any pre-flight failure fails the scenario before its first measured call. No token value appears in any assertion message or exception text.
>   - Large-history walk: with `fixture-token-user0001` and the scenario's filters, the walk reads the first page of `10000000001` at `pageSize=100`, the largest page size, and for scenarios 1 and 3 follows `nextPageToken` through 99 further pages, keeping the 99 tokens it followed. A token binds the account and the filters only (CS-10-003), so it stays valid at `pageSize=20`. The transaction-page cache key carries the page size and the page token, with a 60 s fresh TTL (CS-10-006), so no measured target repeats a key the walk wrote, and no two of a user's measured targets share a key.
>
> > SCOPE-QUESTION (Resolved): Only the walk's keys are disjoint from the measured ones; measured calls can repeat a key. In scenarios 2 and 4 each virtual user cycles 80 targets over 100 calls, so calls 81–100 repeat the keys of calls 1–20. In every scenario the two virtual users of an identity cycle the same target list (CS-01-003). A repeat within the 60 s fresh TTL is a cache hit: it stays in the client percentiles, a `USER0001` call in the large-history client sample too, but adds no stub entry, so it never enters the large-history stub sample. The 100-call load is unchanged, and the scenarios do not claim uncached measurement: the stub-count floor of the note beneath the stub-conditions bullet rests only on each distinct measured page missing the cache at its first call.
>
>   - Content probes for scenario 6: `10000010000` with `fixture-token-user0005`, unfiltered, and `10000002000` with `fixture-token-user0001` and `typeCode=05`, both outside every measured target, so the probes leave no measured key cached.
>
> In scope:
>
> - The six scenarios, their targets per identity and their order.
> - The large-history walk, the content probes and the denial pre-flight request.
> - The per-scenario stub conditions: the 150 ms D4 trigger and the no-timeout-retry condition.
>
> > SCOPE-QUESTION (Resolved): The two items above do not name the work the notes of sections 1 and 2 add, which is also in this story's scope: the large-history client gate of the note beneath the load-and-thresholds bullet; the class's own read of the Transaction Activity stub request-log window and its account-filtered stub gate, of the note beneath the stub-conditions bullet; the sample-size, stub-count and missing-account checks of those two notes, which the class makes after `run` beside the per-scenario stub conditions; and the assertion messages of those notes and of the note beneath AC9. The runner, the percentile computation and the stub figures stay in CS-01-003, and the account on each stub request-log entry stays in CS-11-002.
>
> - The remedy rerun rule.
>
> Not in this story:
>
> - The runner, its load shape, the percentile computation, the report fields and the stub request-log reader (CS-01-003).
> - The Compose stack (CS-01-001) and the synthetic volume (CS-10-008, CS-09-007).
> - The fixture identities and their entitlement ranges (CS-12-001).
> - The filter-leading indexes themselves, a later change set out in the Recommendations section of `docs/implementation-plan.md`.
> - Service and stub behaviour: validation, token pass-through, keyset paging and the page cache (CS-07, CS-10).
> - Injected-failure scenarios (CS-07-002, CS-10-007, CS-11-007, CS-12-005).
>
> ### 2. Acceptance Criteria
>
> - AC1: Scenario 1, unfiltered on the large-history account `10000000001` with `fixture-token-user0001`: every walked page holds 100 items in `transactions` and a `nextPageToken`, each `USER0001` virtual user's calls reach the first page and the 99 pages at the walked tokens, and p50, p95 and p99 are each below 1,000 ms at the baseline load, with the Transaction Activity stub's `ListTransactions` p99 duration at or below 150 ms.
> - AC2: Scenario 2, `typeCode=05` on `10000000001`: the walked first page holds exactly 20 items, every item carrying `typeCode` `05`, and no `nextPageToken` property, and p50, p95 and p99 are each below 1,000 ms at the baseline load.
> - AC3: Scenario 3, `categoryCode=0001` on `10000000001`, with `USER0001`'s virtual users walking forward through the pages at the walked tokens: every item of every walked page carries `categoryCode` `0001`, every walked page holds 100 items and a `nextPageToken`, and p50, p95 and p99 are each below 1,000 ms at the baseline load.
> - AC4: Scenario 4, `typeCode=05&categoryCode=0001` on `10000000001`: the walked first page returns exactly 20 items, all with `typeCode` `05` and `categoryCode` `0001`, and no `nextPageToken` property, and p50, p95 and p99 are each below 1,000 ms at the baseline load.
> - AC5: Scenario 5, unfiltered requests spread over 100-transaction accounts inside each identity's slice, `10000000002`–`10000000101` with `fixture-token-user0001` among them: p50, p95 and p99 are each below 1,000 ms at the baseline load.
> - AC6: Scenario 6, requests spread over the empty-history accounts `10000009002`–`10000009101` with `fixture-token-user0005`, the user entitled to them, and over hundred-transaction accounts with `typeCode=05` for `USER0001`–`USER0004`: both content probes return 200 with the `transactions` array present and empty and no `nextPageToken` property, and p50, p95 and p99 are each below 1,000 ms at the baseline load.
> - AC7: With `STUB_LATENCY_MS=0` and `STUB_FAILURE_RATE=0` on every stub (the Compose defaults), every measured response in every scenario is 200 with `X-Data-Status` `FRESH`, so each report's `FRESH` count equals its 1,000 calls, and any non-200 response fails the scenario.
> - AC8: In every scenario's window, the Transaction Activity stub request log shows for `ListTransactions` a retry count of 0 and no entry with outcome `DEADLINE_EXCEEDED` or `CANCELLED` or a duration of 300 ms or more, the marks a service-side attempt timeout leaves.
> - AC9: D4 trigger: scenarios 2, 3 and 4, the filtered shapes, each fail when the Transaction Activity stub's `ListTransactions` p99 duration in the scenario's window, read from the stub request log, exceeds 150 ms, half the 300 ms per-attempt timeout. The failing scenario's report names the scenario and shows its end-to-end p50, p95, p99 and max and the `ListTransactions` call count, p99 duration and retry count, the stub-call count behind that percentile.
>
> SCOPE-QUESTION (Resolved): The trigger figure of AC9, like the stub p99 of AC1, is the p99 duration of the large-history stub sample of the note beneath the stub-conditions bullet of section 1, the scenario window's `ListTransactions` entries whose `accountId` is `10000000001`, so no other account's read sets it. When the class fails a scenario after `run`, its `AssertionError` message names the scenario, the stub sample's call count and, once its count checks pass, its p99 duration, and the large-history client sample's size and, for a non-empty sample, its p50, p95 and p99, beside the runner's report of the whole window.
>
> - AC10: Remedy rerun: when the filter-leading indexes set out in the Recommendations section of `docs/implementation-plan.md` are applied in answer to AC9, scenarios 1–4, the four large-history shapes, are rerun with the class unchanged before the change is accepted, and each holds the stub's `ListTransactions` p99 duration at or below 150 ms and end-to-end p99 below 1,000 ms.
>
> SCOPE-QUESTION (Resolved): The rerun applies the same large-history client gate, the same account-filtered stub gate on the large-history stub sample and the same sample-size, stub-count, missing-account, outcome and duration checks of section 1 as the first run, with the class unchanged.
>
> - AC11: Authorization: before the first scenario, one request for `10000000001` with `fixture-token-user0002`, an account outside `USER0002`'s entitlements, returns 403 with an `ErrorObject` whose `code` is `PERMISSION_DENIED` and whose `traceId` is non-empty. No account data is read from upstream or cache before access is allowed: the highest sequence number in the Transaction Activity stub request log is the same before the request and after its response. The request is excluded from the percentiles, and a status other than 403 fails the run before the first scenario.
> - AC12: Timeout, retry, deadline and degradation: each upstream attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget, and at most two retries (`UPSTREAM_MAX_RETRIES`) follow, after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, each starting only when its backoff plus a full 300 ms attempt fits. The 900 ms deadline (`REQUEST_DEADLINE_MS`) runs from receipt to the cut-off. Auth failure fails closed with 401, 503 or 504, a `ListTransactions` failure returns 503 after retries or 504 at the cut-off and never a stale page, and a `GetConfig` failure leaves the response and `X-Data-Status` unaffected. Because an unfinished `ListTransactions` call at the cut-off ends the request with 504, every measured 200 had its cut-off at or before 900 ms after receipt. In this scenario any 401, 503 or 504 fails the run (AC7), and the injected forms are proven by CS-07-002, CS-10-007, CS-12-005 and CS-11-007.
>
> ### 3. Dependencies
>
> - CS-01-003 — provides the performance runner, the percentile computation and the stub request-log reader.
>
> Risk: transaction-page caching can answer repeated identical pages and thin the stub-side sample.

## 4. Design

- [Sequence — transaction list](../../../../architecture/sequence-transaction-list.mmd)
- [Data model — synthetic volume](../../../../data-model.md#synthetic-volume)
- [Data model — denormalization of transaction account](../../../../data-model.md#denormalization-of-transaction-account)
- [TRD — D4 Transaction-to-account access](../../../../technical-requirements.md#d4-transaction-to-account-access)
- [TRD — Resilience](../../../../technical-requirements.md#resilience)
- [TRD — Test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- [Implementation plan — Recommendations](../../../../implementation-plan.md#recommendations)
- Contracts:
  - [card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
  - [card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
  - [transaction-activity-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/transaction-activity-service.yaml)
  - [transaction_activity_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/transactionactivity/v1/transaction_activity_service.proto)
  - [auth-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/auth-service.yaml)
  - [auth_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto)
  - [common.proto](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Not applicable — the story adds a scenario definition only, and the runner's own unit cases sit in CS-01-003. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
- Integration — Not applicable — the story changes no client or stub. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Applies — the six scenarios of section 1 and AC1–AC12. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50/p95/p99 below 1,000 ms over successful requests with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions, and for scenarios 1–4 also over the 200 large-history calls on `10000000001` alone; stub `ListTransactions` p99 ≤ 150 ms for each large-history scenario, over the stub request log entries for the large-history account; injected failures proven elsewhere by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end — Not applicable — CS-03-001 and CS-03-002 cover the transaction list steps. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — one run per filter shape, timed end to end and at the stub.

Rationale: the indexing decision turns on each filter shape's cost on the large-history account.

- Rejected: Design Option B, one mixed large-history run — blending hides the sparse-filter cost.
- Rejected: whole-run percentiles as large-history figures — four in five calls hit other accounts.
- Rejected: all ten virtual users on the large-history account — only one identity holds it.
- Rejected: repeating the first page — cached repeats leave the stub unmeasured.

### Shared by both options

- Topology: the runner calls the service, and the service its stubs, store and cache.
- Naming: each run and its report carry the shape's name.
- Addressing: the large-history, hundred-transaction and empty-history accounts sit in disjoint ranges above the sample accounts, and each virtual user calls only its identity's accounts.
- Isolation: no failure injection, no other suite, and no account and filter pair shared by two runs.
- Fallback: none, so a non-success answer, failed walk or missed limit fails the run.
- Provisioning: the stage starts the stack and awaits the volume load.
- Cut-over: the runs repeat unchanged once the filter-leading indexes land.
- Failures: the report names the failing run, its percentiles and stub call count.

### Design Option A — one run per filter shape

Six runs follow an unmeasured denial call: four large-history shapes, then the hundred-transaction and empty-history groups. In a large-history run the first regular fixture user's two virtual users call the large-history account, the other four identities their own hundred-transaction accounts. An unmeasured walk at the largest page size checks every item against the filter and collects next-page tokens, and measured calls use smaller page sizes, so none repeats a walked page.

How it behaves:

- Both large-history virtual users share a target list, so repeats within a minute hit the cache.
- Beside the whole-run thresholds, a large-history run holds its two hundred large-history calls' 50th, 95th and 99th percentiles below one second, failing on any other sample size.
- Its stub gate holds the 99th percentile of the large-history account's stub reads within half the attempt timeout, failing on fewer reads than distinct measured pages, more than its large-history calls, or any read naming no account.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One run per shape (vs one mixed run) | isolates sparse-filter cost | four runs instead of one |
| 2 | Stub log timing (vs end-to-end only) | measures the index trigger | depends on log accuracy |
| 3 | Pages by next-page token (vs repeating the first page) | reaches uncached pages | adds an unmeasured walk |
| 4 | Denial pre-flight (vs no access check) | proves the guard under volume | adds one unmeasured call |
| 5 | Stub sample by account (vs caching other identities' pages) | excludes other accounts' reads | needs each log entry's account |
| 6 | Large-history calls gated alone (vs the whole run) | figures describe the large-history account | needs each call's target |

Effort and risk: small, one scenario file, with cache hits as the main risk.

### Design Option B — one mixed large-history run

One run cycles all four large-history shapes, leaving three runs. Everything else matches Option A.

How it differs:

- Each sparse filter is a quarter of the large-history calls, so its tail can hide below the 99th percentile.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One mixed run (vs one run per shape) | shorter performance stage | blends the shape percentiles |
| 2 | One stub limit (vs one per shape) | one condition to maintain | a breach cannot name its shape |
| 3 | Shapes interleaved (vs separate windows) | every shape meets one load | no per-shape window |

Effort and risk: small, one scenario file, at risk on every per-shape criterion.

### Recommendation

- Option: Design Option A.
- Why: per-shape figures let a breach name the filter needing the filter-leading indexes.
- Strongest argument against: four large-history runs lengthen the performance stage.
- Shared items that do not decide the choice: the stack, volume, thresholds, walk, denial call and report fields.
- Flip conditions: the stub log cannot time calls or name accounts, the run result cannot name targets, or the indexes land before measurement.

## Design Validation

### Option A

Settled assumptions:

- The synthetic volume matches the sample row shape.
- The large-history account holds one hundred thousand rows, twenty of them refunds, whose type has one category.
- Only the fifth identity holds empty-history accounts.
- A next-page token binds only the account and filters, holding at any page size.
- The run result names each call's target, and each stub log entry its account.
- Each distinct measured page misses the cache at its first call.

Open assumptions:

- Whether repeats leave a stub sample near its floor Open → the first large-history runs' sample-size check.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the unfiltered run is gated alone |
| AC2 | Holds | the type-only run is gated alone |
| AC3 | Holds | the category-only run is gated alone |
| AC4 | Holds | the both-filter run is gated alone |
| AC5 | Holds | each identity calls its own accounts |
| AC6 | Holds | the empty-history run has its own window |
| AC7 | Holds | failure injection stays off |
| AC8 | Holds | one run per stub log window |
| AC9 | Holds | stub samples hold only large-history reads |
| AC10 | Conditional | the rerun awaits the filter-leading indexes |
| AC11 | Holds | the denial call runs first |
| AC12 | Holds | the call policy is unchanged at stack defaults |

### Option B

Settled assumptions:

- Option A's volume and refund assumptions hold.
- The four large-history shapes share one stub log window.

Open assumptions:

- Whether a mixed log yields per-shape stub times Open → the performance harness story's report fields.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | blending hides the unfiltered shape |
| AC2 | Breaks | blending hides the type-only shape |
| AC3 | Breaks | blending hides the category-only shape |
| AC4 | Breaks | blending hides the both-filter shape |
| AC5 | Holds | each identity calls its own accounts |
| AC6 | Holds | the empty-history run keeps its own window |
| AC7 | Holds | failure injection stays off |
| AC8 | Holds | the retry check needs no per-shape split |
| AC9 | Breaks | the trigger needs per-shape stub percentiles |
| AC10 | Breaks | the rerun needs per-shape results |
| AC11 | Holds | the denial call runs first |
| AC12 | Holds | the call policy is unchanged at stack defaults |

## Metadata

| Field | Value |
|---|---|
| Build wave | W3 |
| Depends on | CS-01-003 |
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
