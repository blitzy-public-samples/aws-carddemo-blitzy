# CS-01-005: Account balance summary SLA scenario

Parent epic: [CS-01 Account Balance Summary View](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a product owner, I want the balance summary measured at the planning baseline, so that the 1-second SLA is proven for the account view.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Directive: 1-second SLA
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: one performance scenario measures `GET /v1/accounts/{accountId}/balance-summary`, the request behind the COACTVWC account view, on the shared load runner of CS-01-003, against the Compose stack of CS-01-001 at the planning baseline: 10 concurrent users, 100,000 category balances (CS-09-007) and 1,000,000 transactions (CS-10-008). `./gradlew :card-servicing-service:performanceTest` runs it beside the other three SLA scenarios, and CI runs that task as the Performance stage. The scenario declares its targets and runs one access-denial check; the runner, the percentiles, the report and the 1,000 ms threshold stay with CS-01-003. Target path:
>
> - `tests/performance/src/main/java/com/example/cardservicing/performance/scenarios/BalanceSummaryScenario.java` (create):
>   - Scenario definition: a JUnit 5 test class in the performance source set whose test method builds the runner with `LoadRunner.fromSystemProperties()` and passes one `LoadRunner.Scenario` to `run`. The scenario name is `balance-summary`, the endpoint path template is `/v1/accounts/{accountId}/balance-summary` with no query parameter, and the scenario declares no per-stub-operation condition.
>   - Targets: regular identity n (1–5), `USER000n`, gets one 100-entry list that alternates sample and synthetic accounts. Odd position 2j − 1 (j = 1–50) holds sample account 10(n − 1) + ((j − 1) mod 10) + 1, so each of the identity's ten sample accounts appears five times in ID order. Even position 2j holds synthetic account 10000000001 + 2,000(n − 1) + 40(j − 1), so 50 distinct accounts are spread at a stride of 40 from the first account of the identity's 2,000-account synthetic slice to its 1,961st. Account numbers are written as 11 digits with leading zeros. For `USER0001` the list starts `00000000001`, `10000000001`, `00000000002`, `10000000041` and ends `00000000010`, `10000001961`; for `USER0005` it starts `00000000041`, `10000008001` and ends `00000000050`, `10000009961`. Every synthetic account carries ten category balances (CS-09-007).
>   - Access-denial check: before `run`, the class sends one `GET /v1/accounts/00000000011/balance-summary` with `authorization: Bearer fixture-token-user0001` through the JDK `java.net.http.HttpClient` to the `cardservicing.performance.service-url` address, and reads the Card Data stub's `GET /internal/stub/request-log` (CS-11-002) on the `cardservicing.performance.stub.card-data` address before and after that call, taking the highest `sequence` as the mark. The check runs before the runner opens its stub-log window, so its Auth calls stay out of the report's figures. No token value appears in any assertion message or exception text.
> - Together this is the 1 file touched.
>
> In scope:
>
> - The scenario's virtual users, target lists and account sets.
> - The 1,000 ms thresholds the scenario is held to.
> - The once-per-run access-denial check.
> - The report lines for the five upstream operations this endpoint calls.
>
> Not in this story:
>
> - The runner, the percentile computation and the report writer (CS-01-003).
> - The Compose stack (CS-01-001) and the synthetic volume (CS-09-007, CS-10-008).
> - Injected-failure scenarios (the integration suites named in AC7 and CS-05-005).
> - The other three SLA scenarios (CS-02-002, CS-03-003, CS-04-002).
>
> ### 2. Acceptance Criteria
>
> - AC1: The scenario runs the task's load shape, 10 concurrent virtual users × 100 sequential calls, 1,000 calls in all, to `GET /v1/accounts/{accountId}/balance-summary`, two users per regular identity `USER0001`–`USER0005`. Each user walks its identity's 100-entry target list once, in order, with sample and synthetic accounts interleaved: 50 calls on the identity's ten sample accounts, each read five times, and 50 calls on 50 distinct accounts of its 2,000-account synthetic slice. For `USER0001` the list runs `00000000001`, `10000000001`, `00000000002`, `10000000041`, … `00000000010`, `10000001961`, and the report shows 1,000 calls.
> - AC2: p50, p95 and p99, computed by the runner over the latencies of the 200 responses, are each below 1,000 ms. A percentile at 1,000 ms or above fails the scenario.
> - AC3: Every measured response is 200. Any other status, or a call that reaches the runner's 5,000 ms request timeout, fails the scenario with a message naming the status and the request path, and `./gradlew :card-servicing-service:performanceTest` exits non-zero.
> - AC4: The run keeps failure injection off on the Compose stack defaults, `STUB_LATENCY_MS` 0 and `STUB_FAILURE_RATE` 0 on each of the five stubs, and the scenario sets no per-stub override, so every percentile measures the success path.
> - AC5: Authorization: every target lies inside the ranges `fixtures/auth-identities.yaml` gives the identity that calls it, which the runner's entitlement check confirms before the first request. Once per run, before the measured calls, `GET /v1/accounts/00000000011/balance-summary` with `fixture-token-user0001` returns 403 with `code` `PERMISSION_DENIED` and a non-empty `traceId`, because `USER0001` is entitled to `00000000001`–`00000000010` among the sample accounts. The Card Data stub request log holds no entry above the mark read before that call, so no account data is read from upstream before access is allowed; that no cache read precedes the access decision either is proven by the guard ordering of CS-12-003. Any other status, any other `code` or any such Card Data entry fails the scenario before the measured calls start.
>   > SCOPE-QUESTION (Resolved): AC5 names the range `USER0001` is entitled to but not where `00000000011` lies. Account `00000000011` lies outside the entitlements of `USER0001`, which hold `00000000001`–`00000000010` among the sample accounts and the synthetic slice `10000000001`–`10000002000` (CS-12-001), so AC5 requires that a request for an account outside the caller's entitlements returns 403 with `code` `PERMISSION_DENIED`.
> - AC6: The report, in the task output and in `card-servicing-service/build/reports/performance/balance-summary.txt`, lists p50, p95, p99, max, the call count and the 200 count for each `X-Data-Status` value `FRESH`, `STALE` and `PARTIAL`, and those three counts sum to 1,000. From the stub request logs it lists the call count, p99 duration and retry count of each of `ValidateToken` and `AuthorizeAccountAccess` on the Auth stub, `GetAccount` and `ListCategoryBalances` on the Card Data stub and `ListTransactionTypes` on the Reference Data stub that has entries in the scenario window. `ValidateToken` and `AuthorizeAccountAccess` each show a call count of at least 1,000, one entry per measured call.
> - AC7: Timeouts, retries and degradation: the run uses the stack defaults of the upstream call policy. Each attempt times out at the lesser of 300 ms and the remaining budget, and at most two retries follow, after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, each starting only when its backoff plus a full 300 ms attempt fits before the 900 ms cut-off. The runner sends each call once, so a retried upstream call shows only in the report's retry counts, and a request that fails after its retries fails the scenario under AC3. The degrade and fail-closed outcomes of each upstream call this endpoint makes are excluded from the percentiles and proven under injection by CS-05-005 and the integration suites CS-09-006, CS-11-007, CS-12-005 and CS-13-004: Card Data `GetAccount` or `ListCategoryBalances` served `STALE` from an entry within its 300 s max stale age, otherwise 503 or 504; Reference Data unusable giving 200 `PARTIAL` with every `typeDescription` JSON `null`; Auth failing closed with 401, 503 or 504; Config leaving the request and its `dataStatus` unaffected. Each of those tests asserts a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
>
> ### 3. Dependencies
>
> - CS-01-003 — runner, percentile computation and report.
>
> Risk: repeated accounts hit the cache and can understate cold-path latency; mixing the ten sample accounts with 50 distinct synthetic accounts per identity limits that.

## 4. Design

- [Sequence — balance summary](../../../../architecture/sequence-balance-summary.mmd)
- [Data model — synthetic volume](../../../../data-model.md#synthetic-volume)
- [TRD — Test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- [TRD — Resilience](../../../../technical-requirements.md#resilience)
- Contracts:
  - [card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
  - [card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Not applicable — the story adds a scenario definition only, and the runner code is tested by CS-01-003. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
- Integration — Not applicable — the injected upstream outcomes are proven by the integration suites named in AC7. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Applies — this story is the balance summary scenario, covering AC1–AC7 on the stack. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50/p95/p99 below 1,000 ms over successful requests with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end — Not applicable — CS-01-004 covers this endpoint. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — each virtual user interleaves its identity's sample accounts with accounts spread across its synthetic slice.

Rationale: one scenario on the shared runner that reads both the sample accounts the account view shows and synthetic accounts drawn from the planning-baseline volume proves the one-second threshold for the balance summary on warm and cold reads in one run, with no change to the runner.

- Rejected: Design Option B, synthetic accounts only — it leaves the sample accounts the account view reads unmeasured.

### Shared by both options

- Topology: the shared runner on the build host reaches the service and the five stubs through the stack's published host ports.
- Naming: the scenario is named after the balance summary of the account view, and its output lines and its report file carry that name.
- Addressing: each call names one account number in the request path, and the build hands the runner the service address and the five stub log addresses.
- Isolation: each virtual user reads only accounts entitled to its regular fixture identity, and the scenario runs alone so its stub log window holds only its own calls.
- Fallback: the scenario has no fallback path, so a non-success answer, a failed denial check or a missed threshold fails it.
- Provisioning: the stack loads the sample set and the synthetic volume before it reports ready, and the scenario brings no data of its own.
- Cut-over: real services replace the stubs by address and credential alone, and the scenario keeps its targets and thresholds while the real entitlements match the fixture ranges.
- Failures: injected failures stay out of the scenario and are proven by the endpoint assembly story and the integration suites, and the permission-denied check runs once outside the measured calls.

### Design Option A — sample and synthetic accounts interleaved per virtual user

Each regular fixture identity gets one list of one hundred targets that alternates its ten sample accounts with fifty accounts spread evenly across its synthetic slice. Both virtual users of that identity walk the list once, in order. Before the measured calls start, the scenario requests one account outside the first identity's entitlements and checks for the permission-denied status and an empty card data log window.

How it behaves:

- Half of each virtual user's calls read the sample accounts the account view shows, each sample account five times.
- The other half read fifty distinct synthetic accounts, each with ten category balances, spread across the slice at an even stride.
- Each sample account takes the cold path on its first read and is served from the cache on its repeats within the fresh period.
- Each synthetic account takes the cold path on whichever read reaches it first, and the identity's other virtual user finds it warm only when that read has already filled the entry.
- The permission-denied check runs before the runner opens its stub log window, so its authentication calls stay out of the report.
- The report gives the median, 95th and 99th percentile, the maximum, the call count, the success count per data-status header value and the stub figures for the five upstream operations.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Interleaved sample and synthetic accounts (vs synthetic accounts only) | the accounts the account view reads are measured | repeated sample reads lower the measured latency |
| 2 | Fifty synthetic accounts spread across the slice (vs the first fifty of the slice) | the reads span the whole slice | the list needs a stride rule |
| 3 | One hundred targets walked once per virtual user (vs a longer list cycled) | the list length follows the load shape | a larger call count repeats the list |
| 4 | Denial check outside the measured calls (vs a denied target inside the load) | every measured call stays a success | the denial is proven once per run only |
| 5 | Targets written in the scenario (vs read from the stack at run time) | the scenario reads plainly against the fixture ranges | a change to the ranges needs a matching scenario change |

Effort and risk: small effort in one file, with warm sample reads understating cold-path latency as the main risk.

### Design Option B — synthetic accounts only

Each regular fixture identity gets one list of one hundred distinct synthetic accounts spread across its slice at twice the density of Option A. The sample accounts are left out of the measured calls. The denial check, the report and the thresholds match Option A.

How it differs:

- Every call of a virtual user reads a different account, and both virtual users of an identity start together on the same list, so neither has a fixed lead.
- Each account takes the cold path on whichever read reaches it first, the other virtual user finds it warm only when that read has already filled the entry, and two reads that miss at the same moment both go upstream.
- The share of warm reads is observed from the card data call count in the report, not guaranteed.
- The measured latency sits nearer the cold path than in Option A.
- The sample accounts the account view reads, with their legacy record values, are never measured.
- The scenario reads only the synthetic range that the stack's data partition gives the performance suite.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Synthetic accounts only (vs interleaved sample and synthetic accounts) | cold reads dominate the measurement | the accounts the account view reads go unmeasured |
| 2 | One hundred distinct accounts per identity (vs fifty with repeats) | no account repeats within one virtual user | the list spans twice as many synthetic accounts |
| 3 | A stride of twenty across the slice (vs the first hundred of the slice) | the reads span the whole slice | the list needs a stride rule |

Effort and risk: small effort in one file, breaking the criterion that measures the sample accounts.

### Recommendation

- Option: Design Option A.
- Why: it measures the accounts the account view shows beside cold synthetic reads, on the shared runner and in one file.
- Strongest argument against: warm cache entries for the ten sample accounts lower the measured latency.
- Shared items that do not decide the choice: the shared runner, the load shape, the identity mapping, the permission-denied check, the report, the thresholds, failure injection off and the stack start and stop.
- Flip conditions: the product owner requiring a cold-cache measurement, or a run whose percentiles pass only because of warm sample reads.

## Design Validation

### Option A

Settled assumptions:

- Each regular fixture identity holds ten sample accounts in account-number order and a two-thousand-account synthetic slice.
- The first regular fixture identity's sample range ends at the tenth sample account, so the eleventh lies outside it.
- Each synthetic account carries ten category balances.
- The stack runs with failure injection off and loads the synthetic volume before it reports ready.
- The shared runner holds the load shape, the percentiles, the thresholds and the report.

Open assumptions:

- Runner capacity on the shared continuous integration host Open → repeated runs on that host compared against a run on an idle machine.
- Share of warm reads among the measured calls Open → the card data call count in the first report compared with the thousand measured calls.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | each identity's list alternates its sample accounts with its synthetic accounts |
| AC2 | Conditional | shared runner capacity must leave the percentiles below one second |
| AC3 | Holds | with failure injection off every upstream read answers on the success path |
| AC4 | Holds | the scenario sets no stub override on the stack defaults |
| AC5 | Holds | the access guard denies the request before any card data read |
| AC6 | Holds | the shared runner reports the run figures and the stub figures per operation |
| AC7 | Holds | the degraded outcomes are proven by the assembly story and the integration suites |

### Option B

Settled assumptions:

- Each regular fixture identity holds a two-thousand-account synthetic slice.
- Each synthetic account carries ten category balances.
- The stack runs with failure injection off and loads the synthetic volume before it reports ready.

Open assumptions:

- Runner capacity on the shared continuous integration host Open → repeated runs on that host compared against a run on an idle machine.
- Cold-path latency at one hundred distinct accounts per identity Open → the first report's percentiles compared with a run of Option A.
- Share of warm reads among the measured calls Open → the card data call count in the first report compared with the thousand measured calls.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | the lists omit the sample accounts |
| AC2 | At risk | more cold reads push the percentiles toward one second |
| AC3 | Holds | with failure injection off every upstream read answers on the success path |
| AC4 | Holds | the scenario sets no stub override on the stack defaults |
| AC5 | Holds | the denial check matches Option A |
| AC6 | Holds | the shared runner reports the run figures and the stub figures per operation |
| AC7 | Holds | the degraded outcomes are proven by the assembly story and the integration suites |

## Metadata

| Field | Value |
|---|---|
| Build wave | W3 |
| Depends on | CS-01-003 |
| Status | TBD |
| Estimated Files Touched | 1 |
| Issue Type | Story |
| Epic Name | Account Balance Summary View |
| Epic Link | CS-01 |
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
