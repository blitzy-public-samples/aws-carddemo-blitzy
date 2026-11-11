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

Recommended: Design Option A — sample and synthetic accounts interleaved per virtual user.

Rationale: one shared-runner run measures warm sample reads and cold synthetic reads.

- Rejected: Design Option B, synthetic accounts only — the account view's sample accounts go unmeasured.

### Shared by both options

- Topology: the runner on the build host calls the service and five stubs.
- Naming: the scenario and its report carry the balance summary name.
- Addressing: each call names one account, and the build supplies every address.
- Isolation: each virtual user reads only its identity's entitled accounts, and the scenario runs alone.
- Fallback: none, so any failed answer, failed denial check or missed threshold fails the scenario.
- Provisioning: the stack loads all test data before it reports ready.
- Cut-over: real services replace the stubs by address and credential alone, while entitlements match the fixture ranges.
- Failures: the assembly story and integration suites prove injected failures.

### Design Option A — sample and synthetic accounts interleaved per virtual user

Each regular fixture identity gets a list of one hundred targets alternating its ten sample accounts, each read five times, with fifty accounts spread across its synthetic slice. Both its virtual users walk the list once, in order.

How it behaves:

- Every account is cold on its first read and warm on later reads once that read fills the cache entry.
- Before the stub log window opens, one unentitled request must be denied with no card data read.
- The report gives percentiles, maximum, call count, per-status success counts and stub figures of five upstream operations.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Interleaved accounts (vs synthetic accounts only) | the account view's sample accounts are measured | repeated sample reads lower the latency |
| 2 | Fifty accounts across the slice (vs the first fifty) | the reads span the slice | the list needs a stride rule |
| 3 | One hundred targets walked once (vs a list cycled) | the list follows the load shape | more calls repeat the list |
| 4 | Denial check outside measured calls (vs inside) | every measured call succeeds | the denial is proven once per run |
| 5 | Targets written in the scenario (vs read at run time) | targets read plainly against fixture ranges | a range change needs a scenario change |

Effort and risk: small, one file, with warm sample reads understating cold-path latency.

### Design Option B — synthetic accounts only

Each regular fixture identity gets one hundred distinct synthetic accounts at a stride of twenty. The denial check, report and thresholds match Option A.

How it differs:

- Sample accounts and their legacy record values go unmeasured.
- Every call reads a new account, with both virtual users of an identity starting together.
- Simultaneous misses on one account both go upstream, so the warm-read share is observed, not guaranteed.
- Measured latency sits nearer the cold path than in Option A.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Synthetic accounts only (vs interleaved accounts) | cold reads dominate the measurement | the account view's sample accounts go unmeasured |
| 2 | One hundred distinct accounts (vs fifty with repeats) | no account repeats per virtual user | the list spans twice as many accounts |
| 3 | A stride of twenty (vs the first hundred) | the reads span the slice | the list needs a stride rule |

Effort and risk: small, one file, breaking the sample-account criterion.

### Recommendation

- Option: Design Option A.
- Why: it measures sample accounts beside cold synthetic reads.
- Strongest argument against: warm sample cache entries lower the measured latency.
- Shared items that do not decide the choice: the runner, load shape, identity mapping, denial check, report, thresholds and stack start, stop and defaults.
- Flip conditions: the product owner requiring a cold-cache measurement, or percentiles passing only on warm reads.

## Design Validation

### Option A

Settled assumptions:

- Each regular identity holds ten ordered sample accounts and a two-thousand-account synthetic slice.
- The first identity's sample range ends before the eleventh sample account.
- Each synthetic account carries ten category balances.
- The stack starts with failure injection off and all test data loaded.
- The shared runner holds the load shape, percentiles, thresholds and report.

Open assumptions:

- Runner capacity on the shared continuous integration host Open → repeated runs there against an idle machine.
- Warm-read share of the measured calls Open → the first report's card data call count.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | each list alternates sample and synthetic accounts |
| AC2 | Conditional | runner capacity must keep percentiles below one second |
| AC3 | Holds | upstream reads succeed with failure injection off |
| AC4 | Holds | the stack defaults run with no stub override |
| AC5 | Holds | the access guard denies the request before any card data read |
| AC6 | Holds | the runner reports run and per-operation stub figures |
| AC7 | Holds | the assembly story and integration suites prove degraded outcomes |

### Option B

Settled assumptions:

- The slice, balance and stack assumptions of Option A hold.

Open assumptions:

- Runner capacity and warm-read share Open → the Option A comparisons.
- Cold-path latency at one hundred distinct accounts Open → first-report percentiles against an Option A run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | the lists omit the sample accounts |
| AC2 | At risk | more cold reads push percentiles toward one second |
| AC3 | Holds | upstream reads succeed with failure injection off |
| AC4 | Holds | the stack defaults run with no stub override |
| AC5 | Holds | the denial check matches Option A |
| AC6 | Holds | the runner reports run and per-operation stub figures |
| AC7 | Holds | the assembly story and integration suites prove degraded outcomes |

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
