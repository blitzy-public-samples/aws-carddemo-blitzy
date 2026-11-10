# CS-04-002: Transaction detail SLA scenario

Parent epic: [CS-04 Transaction Detail Drill-Down](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a product owner, I want transaction detail measured at full volume, so that the 1-second SLA is proven for the detail view.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN01C
>
> Directive: 1-second SLA
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: one scenario definition in the performance harness of CS-01-003 measures the transaction detail endpoint `GET /v1/accounts/{accountId}/transactions/{transactionId}` on the Compose stack (CS-01-001) at full volume, with 10 concurrent virtual users × 100 sequential calls on the runner's JDK `java.net.http` client. Use Cases · COTRN01C reads one transaction by Tran ID, and Data Layouts · CVTRA05Y gives the record shape, which the synthetic rows match. Each measured call targets a distinct transaction of a synthetic account in the caller's slice that has history. The scenario runs with `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`. Target path:
>
> - `tests/performance/src/main/java/com/example/cardservicing/performance/scenarios/TransactionDetailScenario.java` (create): the scenario class CS-01-003 places in its scenario folder, a JUnit 5 test class whose test method passes its `LoadRunner.Scenario`, named `transaction-detail`, to the runner's `run`.
>   - Virtual users: the runner's mapping, two per regular fixture identity `USER0001`–`USER0005`, users 1 and 2 as `USER0001` with `fixture-token-user0001` through users 9 and 10 as `USER0005` with `fixture-token-user0005` (CS-12-001).
>   - Prechecks, before any call to the service: the `fixture-loader` load report read from `docker compose -f tests/docker-compose.yml logs fixture-loader` shows 100,000 synthetic category balances and 1,000,000 synthetic transactions with zero skipped rows, and `docker compose -f tests/docker-compose.yml exec -T <stub service> printenv STUB_LATENCY_MS STUB_FAILURE_RATE` prints 0 and 0 for each of the five stub services. Every `docker compose` command of this scenario runs through the JDK process API with the repository root as its working directory, and a non-zero exit or any other value fails the scenario.
>   - Identifier sampling: before the measured window, an untimed setup pass lists the first page of each sampled account with `GET /v1/accounts/{accountId}/transactions?pageSize=20` and the owning identity's token. The sampled accounts are the first ten synthetic accounts of each identity's slice: `10000000001`–`10000000010` for `USER0001`, `10000002001`–`10000002010` for `USER0002`, `10000004001`–`10000004010` for `USER0003`, `10000006001`–`10000006010` for `USER0004` and `10000008001`–`10000008010` for `USER0005`. Each of them holds history, 100 transactions, except the large-history account `10000000001` in `USER0001`'s sample, which holds 100,000. The 20 `transactionId` values of each listing give 200 distinct identifiers per identity, listed as that identity's targets, and 100 per virtual user, so no measured call repeats a transaction.
>   - Cache start state: after the setup pass and before the measured window, the scenario empties the stack's cache with `docker compose -f tests/docker-compose.yml exec -T redis redis-cli FLUSHALL`, so no measured transaction is cached and each measured call reads `GetTransaction` and `GetCardCrossReference` from their stubs.
>   - Stub conditions: the scenario declares the runner's no-timeout-retry condition on `cardservicing.transactionactivity.v1.TransactionActivityService/GetTransaction` and on `cardservicing.carddata.v1.CardDataService/GetCardCrossReference`, and requires from the result's stub figures a call count of 1,000 and a retry count of 0 for each of the two operations.
>   - Completion log: the scenario reads `docker compose -f tests/docker-compose.yml logs card-servicing-service` just before and just after the runner's `run`, waiting up to 5 s for the last lines, and checks that the 1,000 completion lines added in between each carry `cutoffMs` ≤ 900.
>   - Denial check: after the measured window, each regular identity sends one call for the first sampled account of the next identity, with an identifier collected for that account: `USER0001` → `10000002001`, `USER0002` → `10000004001`, `USER0003` → `10000006001`, `USER0004` → `10000008001` and `USER0005` → `10000000001`. The scenario reads the highest `sequence` of the Transaction Activity and Card Data request logs on their published web ports before and after these five calls.
>   - Report: the scenario writes one line per stub with its `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` values to standard output and appends the same lines to the scenario's report file under `cardservicing.performance.report-dir` once the runner has written it.
> - Together this is the 1 file touched.
>
> In scope:
>
> - The scenario definition.
> - Identifier sampling.
> - Percentile thresholds.
> - The denial check outside the percentiles.
> - The volume precheck.
> - The injection-value, stub request-log and completion-log checks.
>
> Not in this story:
>
> - The runner, percentile computation and report (CS-01-003).
> - The stack and volume (CS-01-001, CS-09-007, CS-10-008).
> - Endpoint behaviour (CS-08-001–CS-08-003).
> - Injected-failure scenarios (CS-08-002 and the integration suites).
>
> ### 2. Acceptance Criteria
>
> - AC1: The scenario runs 10 concurrent virtual users, each making 100 sequential `GET /v1/accounts/{accountId}/transactions/{transactionId}` calls, for 1,000 measured calls. Every call targets a distinct transaction of a synthetic account inside the calling identity's entitlements. It runs against the Compose stack with 1,000,000 synthetic transactions and 100,000 synthetic category balances loaded. The fixture loader's load report, checked before the measured window, shows both counts with zero skipped rows.
> - AC2: The report gives p50, p95 and p99 client-measured latency over successful measured calls, and each is below 1,000 ms.
> - AC3: Every measured call returns 200 with `transaction.transactionId` equal to the requested `transactionId` and `X-Data-Status: FRESH`. Any non-200 response or other data status fails the scenario.
> - AC4: The scenario runs with failure injection off, with `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` at their Compose default of 0, and the report records both values.
> - AC5: Setup calls that collect transaction identifiers run before the measured window and are excluded from the percentiles.
> - AC6: Outside the percentile set, each regular fixture identity makes one call for an account outside its entitlements (e.g. `USER0001` → `10000002001`). Each such call returns 403 with `code` `PERMISSION_DENIED`, and the Transaction Activity and Card Data stub request logs gain no entry for it, so no account data is read from upstream before access is allowed. The cache side is proven by CS-12-003.
> - AC7: Over the measured window, the Transaction Activity and Card Data stub request logs record zero `UNAVAILABLE` or `DEADLINE_EXCEEDED` outcomes. No attempt therefore reached the per-attempt timeout (the lesser of 300 ms `UPSTREAM_TIMEOUT_MS` and the remaining budget), and no retry occurred (cap two, `UPSTREAM_MAX_RETRIES`, 50 ms then 100 ms backoff). Injected-failure timeouts and retries are proven by CS-08-002 and the integration suites.
> - AC8: Every measured call's completion log line records `cutoffMs` at most 900 (`REQUEST_DEADLINE_MS`, receipt to cut-off), and the p99 client-measured response is below 1,000 ms.
>
> SCOPE-QUESTION (Resolved): AC8 states its cut-off bound as `cutoffMs` at most 900 without a unit. The bound is the 900 ms request deadline (`REQUEST_DEADLINE_MS`), measured from receipt to the cut-off, so AC8 requires the cut-off of every request it names to fall at or before 900 ms after receipt.
>
> - AC9: Degradation and fail-hard outcomes follow the TRD degradation table:
>   - Auth fails closed with 401, 503 or 504.
>   - Transaction-detail and cross-reference failures return 503 or 504.
>   - Config leaves the response unchanged.
>
>   These outcomes fall outside this scenario's percentiles and are proven under injected failures by CS-08-002 and the integration suites. This scenario fails if any measured call returns 503 or 504.
>
> ### 3. Dependencies
>
> - CS-01-003 — the performance harness supplies the load runner on the JDK HTTP client, the percentile computation, the report and the stub request-log reader this scenario runs on.
>
> Risk: Each call on a distinct transaction pays both upstream reads, so the 99th percentile reflects the uncached path and moves toward the threshold if the cross-reference read slows at full volume.

## 4. Design

- [Sequence — transaction detail](../../../../architecture/sequence-transaction-detail.mmd)
- [Data model — synthetic volume](../../../../data-model.md#synthetic-volume)
- [TRD — Test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- Contracts:
  - [card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
  - [card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Not applicable — the story is a scenario definition only, which `performanceTest` runs and `test` never does. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
- Integration — Not applicable — injected stub exceptions, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap are covered by the integration suites CS-09-006, CS-10-007, CS-12-005 and CS-11-007. Command `./gradlew :card-servicing-service:integrationTest`.
- Performance — Applies — this story is the transaction detail scenario. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures are proven elsewhere by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end — Not applicable — the detail step is CS-04-001. Command `./gradlew :card-servicing-service:e2eTest`, which runs the four-step flow for account `00000000001`.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — distinct transactions sampled from each user's entitled synthetic accounts.

Rationale: a detail view opened from a list reaches a transaction the cache has not seen, so the scenario times that uncached lookup at full volume.

- Rejected: Design Option B, one hot transaction repeated — it measures the cached transaction read only.

No sequence diagram: the load loop adds nothing the bullets do not already say.

### Shared by both options

- Topology: one Compose stack holds sample and synthetic data, and the load runner sits outside it.
- Naming: the transaction detail scenario, one of four SLA scenarios.
- Addressing: the service's published port, with each virtual user carrying its identity's fixture token.
- Isolation: synthetic accounts only, disjoint from the sample accounts the end-to-end suite reads.
- Fallback: none, because a non-success fails the run.
- Provisioning: volume is loaded at stack start and checked in the load report first.
- Cut-over: the same scenario reruns after the real-service swap with only addresses and credentials changed.
- Failures: injection off, and denial calls sit outside the percentiles.

### Design Option A — distinct sampled transactions

Before timing starts, an untimed setup pass lists the first page of the first ten synthetic accounts of each regular identity and keeps two hundred transaction identifiers per identity. The cache is then emptied, and each virtual user opens a hundred of its identity's transactions, none twice, so every timed call reads the transaction and the account's card links from the upstream services. After the timed window, one denied call per identity confirms that access is checked before any account data is read.

How it behaves:

- Two virtual users share each regular identity and split its two hundred identifiers between them.
- The listing calls run before the cache is emptied and stay out of the timed set.
- Every timed call must succeed with the requested transaction and a fresh data status header.
- The median, 95th and 99th percentile of the thousand timed calls must each stay below one second.
- The upstream request logs must show one transaction read and one card-link read per timed call, with no retry.
- The service completion log must show a cut-off time of at most 900 ms for every timed call.
- One call per identity for an account of the next identity must end permission denied with no upstream data read.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Distinct transactions per call (vs one repeated) | exercises the upstream read path | the setup pass lengthens the run |
| 2 | Identifiers from a setup listing (vs derived from the synthetic generation rule) | no coupling to generation internals | the listing warms list cache entries |
| 3 | Large-history account inside the first identity's sample (vs excluded) | shows lookup cost independent of history | one account shapes part of one user's sample |

Effort and risk: small effort in one scenario file, low risk.

### Design Option B — one repeated transaction

Every virtual user opens one fixed transaction of its identity's first synthetic account a hundred times. After the first call every transaction read is a cache hit, and no setup pass is needed. The card-link read still reaches its upstream service on every call.

How it differs:

- No setup pass runs, because each identity's one identifier is fixed in the scenario.
- After the first calls of an identity, the cache answers every transaction read.
- The upstream transaction log holds one or two entries per identity instead of one per timed call.
- The few cold reads rank above the 99th percentile position of a thousand samples, so a slow uncached lookup never moves a threshold.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One repeated transaction per identity (vs distinct transactions) | no setup pass is needed | the cache answers nearly every transaction read |
| 2 | Identifier fixed in the scenario (vs a setup listing) | the scenario stands alone | the scenario couples to the generation rule |
| 3 | Cache left warm (vs emptied before timing) | timing starts at once | cold reads fall outside the 99th percentile rank |

Effort and risk: smallest effort, high risk, because it passes while the uncached path could breach.

### Recommendation

- Option: Design Option A.
- Why: it measures the uncached lookup path the one-second threshold governs.
- Strongest argument against: cold reads make the 99th percentile noisier.
- Shared items that do not decide the choice: stack, volume, injection off, identities, thresholds.
- Flip conditions: real-UI telemetry showing detail views are mostly repeat opens of one transaction, or a harness switch that bypasses the cache.

## Design Validation

### Design Option A

Settled assumptions:

- Each regular identity's first ten synthetic accounts hold history.
- Compose defaults keep injection off.
- Synthetic rows match sample shape.

Open assumptions:

- Open → whether cold card-link reads stay inside the threshold at full volume → settled by the first scenario run.
- Open → whether the load runner hands each of an identity's two virtual users its own half of that identity's targets → settled by the performance harness story.
- Open → whether the service container log carries one completion line per call with its cut-off time → settled by the first scenario run against the call policy story's log line.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | distinctness rests on the runner splitting an identity's targets between its two users |
| AC2 | Conditional | cold-path latency at full volume is unproven until the first run |
| AC3 | Holds | every target comes from a listing of an account the identity holds |
| AC4 | Holds | the precheck reads each stub's injection values before timing |
| AC5 | Holds | the setup pass ends before the timed window opens |
| AC6 | Holds | the denied calls run after the timed window with both upstream logs compared |
| AC7 | Holds | injection off leaves no failed or timed-out attempt |
| AC8 | Conditional | the check rests on the completion log carrying the cut-off time per call |
| AC9 | Holds | no injected failure runs, so any unavailable or deadline answer fails the scenario |

### Design Option B

Settled assumptions:

- Compose defaults keep injection off.
- The card-link read is never cached, so it reaches its upstream service on every call.
- Each repeated transaction belongs to an account its identity holds.

Open assumptions:

- Open → whether each fixed identifier matches the synthetic generation rule → settled by the first stack load.
- Open → whether the service container log carries one completion line per call with its cut-off time → settled by the first scenario run against the call policy story's log line.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | one transaction is not a distinct sample |
| AC2 | Holds | cached transaction reads keep every percentile low |
| AC3 | Holds | the repeated transaction answers with its own identifier |
| AC4 | Holds | the precheck reads each stub's injection values before timing |
| AC5 | Holds | no setup calls exist |
| AC6 | Holds | the denied calls run after the timed window with both upstream logs compared |
| AC7 | Holds | injection off leaves no failed or timed-out attempt |
| AC8 | Conditional | the check rests on the completion log carrying the cut-off time per call |
| AC9 | Holds | no injected failure runs, so any unavailable or deadline answer fails the scenario |

## Metadata

| Field | Value |
|---|---|
| Build wave | W3 |
| Depends on | CS-01-003 |
| Status | TBD |
| Estimated Files Touched | 1 |
| Issue Type | Story |
| Epic Name | Transaction Detail Drill-Down |
| Epic Link | CS-04 |
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
