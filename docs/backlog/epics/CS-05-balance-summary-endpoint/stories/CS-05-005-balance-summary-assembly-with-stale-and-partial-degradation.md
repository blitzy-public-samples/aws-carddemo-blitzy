# CS-05-005: Balance summary assembly with stale and partial degradation

Parent epic: [CS-05 Balance Summary Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want my balance summary even when one source is slow or down, so that I see the freshest data available with its status.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-CURR-BAL
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: Stage 3 and the cut-off of the balance summary request path. Once access is allowed, the account record (`Data Layouts · CVACT01Y`), the category balances (`Data Layouts · CVTRA01Y`) and the transaction types (`Data Layouts · CVTRA03Y`) are read concurrently, the balances are summed by type and labelled, and one outcome is fixed at the cut-off: a `FRESH`, `STALE` or `PARTIAL` summary, or an error. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/balance/BalanceSummaryAssembler.java` runs only after the account access guard (CS-12-003) allows access, so no account-data read, cache read included, happens before the allow.
>   - It takes the caller's bearer token, the validated `accountId` and the request's `RequestDeadline` (CS-11-003), the one the guard ran its Auth calls under.
>   - It launches `GetAccount`, `ListCategoryBalances` and `ListTransactionTypes` concurrently through the Card Data client (CS-09-005) and the Reference Data client (CS-13-003), under that deadline. Each client forwards the token, adds `x-api-key` from `AUTH_SERVICE_API_KEY`, and applies its own cache and degrade rule; cache keys carry no caller identity.
>   - It waits for the three reads, each of which the call policy ends within the deadline's remaining budget, and marks the cut-off when the last one completes. If the budget runs out first, the deadline records the cut-off itself, so `cutoffMs` never exceeds 900. A read still unanswered at the cut-off ends in `DEADLINE_EXCEEDED` and degrades by the degradation table inside its client: an account or category-balance read to its stale entry when one is within max stale age, otherwise to 504 `DEADLINE_EXCEEDED`, and the type read to no descriptions.
>   - It aggregates the balances with the type-group aggregator (CS-05-004) and attaches `typeDescription` by `typeCode`. With a partial type result, every description is absent.
>   - It resolves `dataStatus` and `asOf` through `DataStatusResolver`.
>   - It maps `NOT_FOUND` on `GetAccount`, or on `ListCategoryBalances` for the same unknown account, to 404 `NOT_FOUND`, ahead of every other outcome. Otherwise, when an account or category-balance read fails with no usable stale entry, the request ends with that read's final status, the account read's status when both fail: `UNAVAILABLE` (503) or `DEADLINE_EXCEEDED` (504), through the standard error mapping (CS-05-002), with the receipt `traceId`.
>   - It completes the deadline exactly once, with outcome `full` for `FRESH`, `degraded` for `STALE` or `PARTIAL`, and `error` with the response status for 404, 503 or 504, so the completion line carries `cutoffMs`, the outcome and the `traceId`.
> - `card-servicing-service/src/main/java/com/example/cardservicing/balance/BalanceSummary.java` is the assembled result.
>   - It holds every `GetBalanceSummaryResponse` field, with `groupId` omitted when the source value is empty or only spaces. `balancesByTypeGroup` is always present, empty when the account has no category balance, and amounts stay the scale-2 decimal strings the contracts carry.
>   - Each description is either present or absent.
>   - It provides both protocol renderings: the REST body with `typeDescription` as JSON `null` when absent, and the gRPC message with `type_description` unset when absent. An empty string never stands for an absent description, and `asOf` renders as RFC 3339 with microseconds and `Z`.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/support/DataStatusResolver.java` sets precedence `PARTIAL` > `STALE` > `FRESH` and sets `asOf` to the oldest read time among the sources used: an upstream result contributes its read time, a cache entry its `cachedAt`, and a partial reference result nothing. Config outcomes never reach it. The category-balance, transaction-list and transaction-detail retrieval stories (CS-06-002, CS-07-002, CS-08-002) reuse it.
> - Tests: `card-servicing-service/src/test/java/com/example/cardservicing/balance/BalanceSummaryAssemblerTest.java` and `card-servicing-service/src/test/java/com/example/cardservicing/integration/support/DataStatusResolverTest.java`. They run against in-JVM loopback stubs, with failure injection per test.
>   - `BalanceSummaryAssemblerTest` sends each test request through the account access guard and then the assembler, as the endpoints will (CS-05-006). It uses the Auth, Config, Card Data and Reference Data stubs registered on `StubHarness` (CS-11-002), per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` overrides on each stub, and the in-memory recording `EntryStore` with the controllable test clock of the CS-11-004 tests for entry ages and cache-read counts. Each test request starts its `RequestDeadline` at receipt, and the test client measures the response time.
>   - `DataStatusResolverTest` covers the precedence and `asOf` rules as table-driven cases.
> - That makes 5 files.
>
> In scope:
>
> - Concurrent Stage 3 reads.
> - Aggregation and description attachment.
> - `STALE`, `PARTIAL` and fail-hard outcomes at the cut-off.
> - `dataStatus` and `asOf`.
> - The null-description mapping.
> - 404 on an unknown account.
>
> Not in this story:
>
> - The REST controller and gRPC method (CS-05-006).
> - Parameter validation (CS-05-003).
> - Token validation and the access decision (CS-12-003).
> - Retry, timeout and deadline mechanics (CS-11-003).
>
> > SCOPE-QUESTION (Resolved): The call policy story (CS-11-003) and the access guard story (CS-12-003) leave starting the request deadline at receipt, and its cut-off after an allow, to this story. This story marks the cut-off and completes the deadline, but its five files hold no receipt stage on either protocol, so it takes the deadline the guard ran under. Until a receipt stage starts the deadline ahead of Stage 0 validation, the guard's own start at its entry applies, and `cutoffMs` leaves out the Stage 0 time, which makes no network call. Settled by the balance summary REST and gRPC endpoints story (CS-05-006), which places the balance summary route on both protocols: it extends the Stage 0 hook to start the request deadline at receipt, ahead of validation, and to attach it to the request, so the guard and this story run under that one deadline and `cutoffMs` counts from receipt.
>
> - Cache mechanics (CS-11-004).
> - The customer-details field gaps (CS-01 epic README).
>
> ### 2. Acceptance Criteria
>
> - AC1: For token `fixture-token-user0001` and account `00000000001`, with all stubs healthy and an empty cache, the result carries `accountId` "00000000001", `activeStatus` "Y", `currentBalance` "194.00", `creditLimit` "2020.00", `cashCreditLimit` "1020.00", `currentCycleCredit` "0.00", `currentCycleDebit` "0.00", `openDate` "2014-11-20", `expirationDate` "2025-05-20", `reissueDate` "2025-05-20" and no `groupId`; `balancesByTypeGroup` [{`typeCode` "01", `typeDescription` "Purchase", `balance` "0.00"}]; `dataStatus` "FRESH"; and `asOf` equal to the earliest of the three upstream read times, in RFC 3339 microsecond `Z` form.
> - AC2: For token `fixture-token-user0001` and account `00000000011`, which is outside the caller's entitlements, the request returns 403 `PERMISSION_DENIED`. For that request the Card Data and Reference Data stub request logs hold zero entries and the cache records zero reads. No account data is read from upstream or cache before access is allowed.
> - AC3: With 200 ms injected latency on the Card Data and Reference Data stubs, an empty cache and healthy Auth, the request returns `FRESH` and the logged `cutoffMs` is below 400, while any two of the reads run in sequence would take at least 400 ms. This proves the three Stage 3 reads run concurrently.
> - AC4: With fresh cache entries on the test clock for the account (aged 10 s), its category balances (aged 20 s) and the transaction types (aged 600 s), the Card Data and Reference Data stub logs hold zero calls. `dataStatus` is `FRESH`, and `asOf` equals the oldest `cachedAt` of the three entries, the transaction types' entry.
> - AC5: With the Card Data stub failing every call (`STUB_FAILURE_RATE` 1.0), the Reference Data stub healthy, and account and category-balance entries aged 120 s and 240 s, past their 60 s fresh TTL but within the 300 s max stale age, the result is 200. `dataStatus` is `STALE`, and `asOf` equals the `cachedAt` of the 240 s entry, the oldest used.
> - AC6: With the Card Data stub failing every call and no entry within max stale age, both with no entry and with entries aged 300 s, the request returns 503 `UNAVAILABLE`. With a Card Data stub latency of 1,000 ms and no usable entry, it returns 504 `DEADLINE_EXCEEDED`. Both responses carry the receipt `traceId`.
> - AC7: With the Reference Data stub failing every call and no type entry within its 86,400 s max stale age, the result is 200 with `dataStatus` `PARTIAL`. The account fields and type-group balances are returned, and `asOf` is the earliest Card Data read time. Every element's description is absent: JSON `null` in the REST rendering, unset in the gRPC rendering, and never an empty string.
> - AC8: A `PARTIAL` type read combined with a `STALE` account read resolves to `PARTIAL`. A `STALE` type entry with fresh account and balance reads resolves to `STALE`. Three fresh reads resolve to `FRESH`. In each case `asOf` is the oldest read time among the sources used.
> - AC9: When `GetAccount` returns `NOT_FOUND` for an account the caller is entitled to, the request returns 404 `NOT_FOUND`, whatever the other two reads return. The Card Data stub log shows exactly one `GetAccount` call, with no retry.
> - AC10: With a Card Data stub latency of 350 ms, an empty cache and healthy Auth, the Card Data stub request log records every `GetAccount` attempt as ending within 30 ms after the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the budget left when the attempt started. The log shows exactly 2 attempts, and the request returns 504 `DEADLINE_EXCEEDED`.
>
> SCOPE-QUESTION (Resolved): A stub request log cannot show where the client ends an attempt. Its duration runs from receipt to close, and the failure injector holds each call for the full injected 350 ms before it checks for cancellation and records `CANCELLED` (CS-11-002), so each `GetAccount` entry of an attempt the client ended at 300 ms still reads at least 350 ms. The injector also wraps the stub's whole service definition, so no interceptor inside a registration sees a call during an injected delay. AC10 therefore reads as follows. With 350 ms injected latency on the Card Data stub, an empty cache and healthy Auth, the request returns 504 `DEADLINE_EXCEEDED`, the logged `cutoffMs` is at least 650 (300 + 50 + 300) and at most 900, and the client-measured response arrives below 1,000 ms. The Card Data stub request log, read only once every 350 ms delay has elapsed, supplies the count and the outcomes only: exactly 2 `GetAccount` entries, each with outcome `CANCELLED`, because a third attempt needs its 100 ms backoff plus a full 300 ms attempt, and once the second attempt ends, at least 650 ms after receipt, at most 250 ms remain. Log durations are not the timing measure. The per-attempt bound is measured where the client ends each attempt. A second test-local Card Data `StubRegistration` under stub name `card-data`, beside the AC11 one, nested in `BalanceSummaryAssemblerTest` so the 5 files stand and never discovered by `StubHarness.allRegistered()`, holds every Card Data call 350 ms itself before it answers from the stub's fixture set, with the injected latency at 0. It records on the real clock each `GetAccount` arrival and, through a cancellation listener on the call's `io.grpc.Context`, the moment the client's cancellation of that call reaches it. With it in place the request returns 504, it records exactly 2 `GetAccount` arrivals, and each attempt's cancellation arrives no later than 30 ms after the lesser of 300 ms and the remaining budget, counted from that attempt's arrival, the remaining budget being 900 ms less the time from receipt to that arrival. The exact cut of an attempt to the remaining budget stays with the call policy's test-clock cases (CS-11-003 AC1). Apart from that cancellation bound, the 900 bound on `cutoffMs` and the 1,000 ms bound on the client-measured response, every duration in this case is asserted as a lower bound only. The request log of CS-11-002 stays unchanged.
>
> - AC11: On `UNAVAILABLE` or `DEADLINE_EXCEEDED`, a read is retried at most two times (`UPSTREAM_MAX_RETRIES`), with 50 ms then 100 ms backoff. A retry starts only if its backoff plus a full 300 ms attempt fits the remaining budget. `NOT_FOUND` and `INVALID_ARGUMENT` are never retried. With the Card Data stub failing every call, its request log shows exactly 3 `GetAccount` attempts, at least 50 ms and then at least 100 ms apart; with a latency of 400 ms it shows exactly 2.
>
> SCOPE-QUESTION (Resolved): AC11 names the statuses that are retried but not that they are the only ones. The call policy retries a read only after `UNAVAILABLE` or `DEADLINE_EXCEEDED`; every other status, `NOT_FOUND` and `INVALID_ARGUMENT` included, ends the read after its first attempt and is returned with no retry, as the single `GetAccount` call of AC9 shows.
>
> SCOPE-QUESTION (Resolved): AC11 takes the spacing of the `GetAccount` attempts from the Card Data stub request log, but a log entry of the stub hosting story (CS-11-002) holds a sequence number, the operation, the outcome and the duration from receipt to close, and no arrival time. The request log proves the count and the outcomes: exactly 3 `GetAccount` entries with outcome `UNAVAILABLE` under the injected `STUB_FAILURE_RATE` 1.0, and exactly 2 at a latency of 400 ms. Because `StubFailureInjector` answers an injected failure before the stub's service definition sees the call, the spacing is measured in `BalanceSummaryAssemblerTest` by a test-local Card Data `StubRegistration` under stub name `card-data`, which `StubHarness.allRegistered()` never discovers. Its handler answers every `GetAccount` at once with `UNAVAILABLE`, serves the other Card Data reads from the stub's fixture set, and records each `GetAccount` arrival time on the monotonic `System.nanoTime()` clock, not the controllable test clock, as it receives the call. AC11's spacing holds when there are exactly 3 arrivals, the second at least 50 ms after the first and the third at least 100 ms after the second. The request log of CS-11-002 stays unchanged, and the 5 files stand.
>
> - AC12: For every degraded 200, 503 and 504 in AC5–AC11, the completion log line's `cutoffMs` is at most 900 (`REQUEST_DEADLINE_MS`), the line carries the outcome and the receipt `traceId`, and the client-measured response arrives below 1,000 ms. The same holds on a cold path with an Auth stub latency of 250 ms beside the AC10 Card Data latency, where the Card Data stub log shows exactly one `GetAccount` attempt and the request returns 504.
>
> SCOPE-QUESTION (Resolved): AC12 states its cut-off bound as `cutoffMs` at most 900 without a unit. The bound is the 900 ms request deadline (`REQUEST_DEADLINE_MS`), measured from receipt to the cut-off, so AC12 requires the cut-off of every request it names to fall at or before 900 ms after receipt.
>
> - AC13: With the Auth stub failing every call, the request returns 503 `UNAVAILABLE`; with an Auth stub latency of 2,000 ms, it returns 504 `DEADLINE_EXCEEDED`; with `fixture-token-expired`, it returns 401 `UNAUTHENTICATED`. In each case the Card Data and Reference Data stub logs hold zero entries.
> - AC14: With the Config stub failing every call, or with a 2,000 ms Config latency and a stale snapshot, `dataStatus` equals the AC1 value `FRESH`, `cutoffMs` is at most 900, and the client-measured response arrives below 1,000 ms.
> - AC15: An entitled account whose category-balance read returns an empty list gives an empty `balancesByTypeGroup` array with `dataStatus` `FRESH`.
>
> ### 3. Dependencies
>
> - CS-05-004 — supplies the type-group aggregation the summary embeds.
> - CS-09-005 — supplies the Card Data client with account and category balance caching and its stale fallback.
> - CS-13-003 — supplies the Reference Data client with cached types and the description fallback.
> - CS-12-003 — supplies the account access guard that must allow access before any read.
> - CS-11-006 — supplies the configuration snapshot behind the per-attempt timeout and retry values.
>
> Risk: On a cold path two sequential Auth calls can leave about 300 ms for Stage 3, so a slow first data attempt leaves no retry and the summary falls to STALE or 504.

## 4. Design

- [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd)
- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Data model — account](../../../../data-model.md#account)
- [Data model — category balance](../../../../data-model.md#category_balance)
- [Data model — transaction type](../../../../data-model.md#transaction_type)
- [TRD — resilience, degradation table and cache rules](../../../../technical-requirements.md#resilience)
- [TRD — request path](../../../../technical-requirements.md#request-path)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
- [Card Data gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto)
- [Card Data OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-data-service.yaml)
- [Reference Data gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto)
- [Reference Data OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/reference-data-service.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module. Cases: AC1–AC15 against in-JVM loopback stubs with per-test `STUB_LATENCY_MS` and `STUB_FAILURE_RATE`. The AC11 spacing case takes the `GetAccount` arrival times recorded by a test-local Card Data registration that answers `UNAVAILABLE` at once, and the attempt count and outcomes from the stub request log. The AC10 timing case takes each `GetAccount` attempt's arrival and cancellation times from a test-local Card Data registration that holds each call 350 ms, and the attempt count and `CANCELLED` outcomes from the stub request log. Deadline cases assert a logged `cutoffMs` ≤ 900 and a client response below 1,000 ms.
- Integration: Not applicable — the integration suites belong to the external epics, the Card Data and Reference Data integration suites (CS-09-006, CS-13-004). Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
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

Recommended: Option A — a concurrent fan-out of the three reads under one deadline.

Rationale: only reads launched together each get one full attempt after the two authentication calls.

- Rejected: Option B, sequential reads, which overrun the cold-path budget.
- Rejected: a single combined upstream operation, absent from the contract baseline.

```mermaid
sequenceDiagram
    participant Endpoint as Balance endpoint
    participant Assembly as Balance assembly
    participant Cards as Card data client
    participant Types as Reference data client
    Endpoint->>Assembly: assemble after access allowed
    par account
        Assembly->>Cards: account read, cache first
    and balances
        Assembly->>Cards: balance read, cache first
    and types
        Assembly->>Types: type read, cache first
    end
    Note over Assembly: cut-off fixes fresh, stale, partial or error
    Assembly-->>Endpoint: summary with data status
```

### Shared by both options

- Topology: after the access guard allows, the assembly reads through the card data and reference data clients.
- Naming: the data status is fresh, stale or partial.
- Addressing: the account number comes from the validated request.
- Isolation: nothing is read before the allow, and cache keys carry no caller identity.
- Fallback: account and balance reads fall back to a stale entry, and the type read to no descriptions.
- Provisioning: loopback stubs serve unit tests, and containers serve the test stack.
- Cut-over: real services replace the stubs by client address and credential source only.
- Failures: unavailable gives 503, an expired deadline 504 and an unknown account 404.

### Design Option A — concurrent fan-out under one deadline

The assembly launches the account, balance and type reads together once access is allowed. It waits for the last read or the end of the budget, then fixes one outcome at the cut-off.

How it behaves:

- An unknown account outranks every other outcome.
- An unfinished or failed type read marks the summary partial.
- Balances are summed per type and labelled with the type description.
- The as-of time is the oldest read time among the sources used.
- One completion line records the single cut-off and its outcome.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Reads launched together (vs in sequence) | a full attempt each on a cold path | three calls in flight per request |
| 2 | One deadline for all reads (vs a budget per read) | one cut-off decides every outcome | one slow read can use the whole budget |
| 3 | One shared resolver ranking partial over stale over fresh (vs logic per endpoint) | one precedence rule for four endpoints | a shared change point |
| 4 | Absent description (vs empty text) | partial results stay distinguishable | each protocol renders absence differently |
| 5 | Unknown account ends the request (vs a summary lacking account fields) | no summary for an unknown account | the other two reads are discarded |

Effort and risk: one assembly component, result shape and shared resolver with two test suites, with the risk in one slow read using the whole budget.

### Design Option B — sequential reads

The assembly reads the account, then the balances, then the types, each inside a fixed budget share. The same result shape and resolver build the summary.

How it differs:

- A hard account failure stops the sequence before the other reads start.
- On a cold path the later reads get less than one full attempt.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Account, then balances, then types (vs together) | simpler control flow | three attempts exceed the cold-path budget |
| 2 | Stop at the first hard failure (vs reading all three) | fewer calls on failure | later stale fallbacks never run |
| 3 | A fixed budget per read (vs one deadline) | each read has a known share | unused budget is lost |

Effort and risk: the same components with simpler control flow, with high risk from overrunning the cold-path budget.

### Recommendation

- Option: Option A.
- Why: only concurrent reads fit the cold-path budget.
- Strongest argument against: three calls in flight per request raise upstream load.
- Shared items that do not decide the choice: the degradation table, status precedence, access-first rule and cache rules.
- Flip conditions: the real services cap concurrent calls per caller, or Requirements Analysis makes the account read gate the other two.

## Design Validation

### Validation of Option A

Settled assumptions:

- The cold-path budget leaves one data attempt after two authentication attempts.
- The clients apply each read's degrade rule.
- Cache entries carry their write time.
- Foreign keys keep every balance type in reference data.
- Each source degrades on its own, so a stale account entry beside fresh reads marks the summary stale.

Open assumptions:

- Open → the load of two concurrent card data calls and one reference data call, settled once real services replace the stubs.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | healthy reads give the full summary |
| AC2 | Holds | the assembly runs only after the allow |
| AC3 | Holds | the reads overlap in time |
| AC4 | Holds | fresh entries answer every read |
| AC5 | Holds | the card data client serves stale entries |
| AC6 | Holds | no usable entry leaves the final upstream status |
| AC7 | Holds | the reference data client returns no descriptions |
| AC8 | Holds | one resolver applies one precedence rule |
| AC9 | Holds | not-found outranks every other outcome |
| AC10 | Holds | the call policy cuts attempts to the remaining budget |
| AC11 | Holds | the call policy applies the retry cap and fit rule |
| AC12 | Conditional | it rests on the call policy cutting attempts to the remaining budget |
| AC13 | Holds | the guard fails closed before any read |
| AC14 | Holds | configuration refresh never joins the data reads |
| AC15 | Holds | an empty balance list gives empty groups |

### Validation of Option B

Settled assumptions:

- The five settled for Option A hold: cold-path budget, client degrade rules, cache write times, foreign keys and independent degradation.

Open assumptions:

- Open → each read's budget share, settled by measurement on the test stack.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | healthy reads in a row give the full summary |
| AC2 | Holds | the assembly runs only after the allow |
| AC3 | Breaks | sequential calls take at least 600 milliseconds |
| AC4 | Holds | fresh entries answer every read |
| AC5 | At risk | later fallbacks may miss the cut-off |
| AC6 | Holds | no usable entry leaves the final upstream status |
| AC7 | At risk | the type read runs last and may miss the cut-off |
| AC8 | Holds | one resolver applies one precedence rule |
| AC9 | Holds | the first account read ends the sequence |
| AC10 | At risk | a fixed share cuts attempts below full length |
| AC11 | At risk | retries do not fit a fixed share |
| AC12 | At risk | three reads in a row strain the deadline |
| AC13 | Holds | the guard fails closed before any read |
| AC14 | Holds | configuration refresh never joins the data reads |
| AC15 | Holds | an empty balance list gives empty groups |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-05-004, CS-09-005, CS-13-003, CS-12-003, CS-11-006 |
| Status | TBD |
| Estimated Files Touched | 5 |
| Issue Type | Story |
| Epic Name | Balance Summary Endpoint |
| Epic Link | CS-05 |
| Story Points | 5 |
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
