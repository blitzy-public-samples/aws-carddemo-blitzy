# CS-05-004: Type-group balance aggregation

Parent epic: [CS-05 Balance Summary Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want my category balances summed by transaction type, so that the summary shows one balance per type.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRAN-CAT-BAL
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: the type-group sums that the balance summary carries in `balancesByTypeGroup`, computed by the catalogue component `CategoryBalanceAggregator`. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/category/CategoryBalanceAggregator.java` takes the category balances read for one account (type code, category code and the balance as the scale-2 decimal string the Card Data contract carries) and returns the type-group list.
>   - It returns one element per `typeCode` with at least one balance. An element whose sum is "0.00" stays.
>   - Each sum is exact at scale 2, with no rounding and no floating point.
>   - Elements are ordered by `typeCode` ascending, whatever the input order.
>   - Empty input returns an empty list, never null.
>   - A balance that is not a scale-2 decimal is rejected with an error naming its type and category code, and no partial list is returned.
>   - The aggregator performs no I/O and attaches no description.
> - Test: `card-servicing-service/src/test/java/com/example/cardservicing/category/CategoryBalanceAggregatorTest.java`.
> - That makes 2 files.
>
> In scope:
>
> - Grouping of category balances by type code.
> - Exact summing at scale 2.
> - Ordering by type code ascending.
> - Empty input.
> - Rejection of malformed balances.
>
> Not in this story:
>
> - Reading category balances (CS-09-005).
> - Attaching `typeDescription` and the `PARTIAL` rule (CS-05-005).
> - String serialization of amounts (CS-05-006).
> - The category-balance endpoint (CS-06).
>
> ### 2. Acceptance Criteria
>
> - AC1: Input 01/0001 "10.00", 01/0002 "5.25" and 02/0001 "-3.10" returns exactly [01 "15.25", 02 "-3.10"], in that order.
> - AC2: The same three balances supplied in the order 02/0001, 01/0002, 01/0001 return the AC1 list unchanged.
> - AC3: An empty input returns an empty list.
> - AC4: The fixture balances of account `00000000001` (01/0001 "0.00") return exactly [01 "0.00"].
> - AC5: Balances 01/0001 "5.00" and 01/0002 "-5.00" return [01 "0.00"]. A type whose balances cancel stays in the list.
> - AC6: Balances "0.10" and "0.20" under type 01 return "0.30". Two balances of "999999999.99" under type 01 return "1999999999.98" with no overflow and no rounding.
> - AC7: A balance of "1.5" or "abc" under 02/0003 raises an error naming type 02 and category 0003, and no list is returned.
>
> ### 3. Dependencies
>
> - CS-05-001 — provides the service module, the build and the unit test task.
>
> Risk: A type with very many categories is bounded only by the planning baseline of under 1,000 category balances per account, so the per-call grouping cost has not been measured above that baseline.

## 4. Design

- [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd)
- [Data model — category balance](../../../../data-model.md#category_balance)
- [TRD — component catalogue](../../../../technical-requirements.md#component-catalogue)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is ≥80% per module. Cases: AC1–AC7 as table-driven inputs.
- Integration: Not applicable — the aggregator makes no upstream call. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Applies, through the account balance summary SLA scenario (CS-01-005), because aggregation runs on every balance-summary call and each synthetic account carries ten category balances.
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — the end-to-end balance-summary step belongs to CS-01-004, which reaches this component through the assembly story. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — group and sum inside the service with a map kept in type-code order.

Rationale: grouping inside the service uses only the baseline category balance read and keeps the grouping rule in one component.

- Rejected: Option B, aggregation in the card data stub, needs an operation the contract baseline does not list.
- Rejected: grouping in the relational store is unreachable, because the service never opens a database connection.

### Shared by both options

- Topology: aggregation runs after the category balance read.
- Naming: there is one element per type code.
- Addressing: input arrives for one account at a time.
- Isolation: the aggregation keeps no state between calls.
- Fallback: empty input gives an empty list.
- Provisioning: none.
- Cut-over: swapping the stub for the real card data service changes nothing in the grouping rule.
- Failures: a malformed balance rejects the whole input.

### Design Option A — in-service ordered grouping

The service receives every category balance of one account and adds each balance to the group of its type code in a map kept in type-code order. Every sum uses exact decimal arithmetic at scale two, so the output needs no rounding step and no later sort. The assembly step attaches type descriptions afterwards, so grouping needs no reference data.

How it behaves:

- Each balance is checked for a two-decimal amount before it joins a group.
- A balance joins the group of its type code, and that group's sum grows by the exact amount.
- A group whose balances cancel to zero stays in the output.
- The output lists groups by ascending type code, whatever order the input arrived in.
- An empty input gives an empty output, never a missing one.
- A malformed balance raises an error naming its type and category, and no output is returned.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Map kept in type-code order (vs sorting after grouping) | ascending order by construction | one map per call |
| 2 | Exact decimal arithmetic at scale two (vs floating point) | no rounding drift | a heavier number type |
| 3 | Zero sums kept (vs dropped) | every type with a balance appears | a zero row reaches the screen |
| 4 | Descriptions attached by the assembly step (vs during grouping) | grouping needs no reference data | two steps build one element |

Effort and risk: one small component with its test, with low risk because the rule touches no store and no network.

### Design Option B — grouping in the card data stub

The card data stub groups the stored category balances by type and answers a new grouped-balance operation, so the service receives one sum per type. The service passes the grouped list through and only attaches descriptions. The real card data service would have to offer the same operation at swap time.

How it differs:

- The grouping rule moves from the service into the stub, beside the stored rows.
- The contract gains an operation the baseline does not list.
- The service no longer orders the groups, so the order depends on each provider.
- A malformed stored balance is rejected inside the stub, before the service sees it.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | New grouped-balance operation (vs baseline list operation) | one smaller response | the operation is outside the contract baseline |
| 2 | Grouping beside the stored rows (vs in the service) | less data on the wire | the real service must add the same operation |
| 3 | Order fixed by the stub (vs by the service) | no service-side ordering | ordering depends on every provider |

Effort and risk: a new contract operation in the stub and later in the real service, with high risk because the operation sits outside the contract baseline.

### Recommendation

- Option: Option A.
- Why: it uses only baseline operations and keeps the rule in one place.
- Strongest argument against: every call carries all category rows over the wire.
- Shared items that do not decide the choice: the grouping rule, exact sums and the zero-row policy.
- Flip conditions: a Requirements Analysis entry adds a grouped operation to the baseline, or per-account category counts exceed the planning baseline.

## Design Validation

### Validation of Option A

Settled assumptions:

- A category balance carries at most eleven digits, two of them after the decimal point.
- The sample data holds one balance per account.
- The synthetic volume holds ten balances per account.

Open assumptions:

- Open → whether a zero-sum type stays visible on the screen, settled by the deferred UI run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | exact sums come out in ascending type order |
| AC2 | Holds | the ordered map ignores input order |
| AC3 | Holds | empty input gives an empty list |
| AC4 | Holds | one zero balance gives one zero group |
| AC5 | Holds | zero sums stay in the output |
| AC6 | Holds | exact decimal arithmetic avoids rounding and overflow |
| AC7 | Holds | the amount check rejects the whole input |

### Validation of Option B

Settled assumptions:

- A category balance carries at most eleven digits, two of them after the decimal point.
- The sample data holds one balance per account.
- The synthetic volume holds ten balances per account.

Open assumptions:

- Open → whether a zero-sum type stays visible on the screen, settled by the deferred UI run.
- Open → whether the contract baseline gains a grouped-balance operation, settled by a Requirements Analysis entry.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | the sums need the non-baseline grouped operation |
| AC2 | Conditional | the stub fixes the order only through the non-baseline operation |
| AC3 | Conditional | an empty answer needs the non-baseline operation |
| AC4 | Conditional | the fixture sum needs the non-baseline operation |
| AC5 | Conditional | the zero-row policy lives in an operation outside the baseline |
| AC6 | Conditional | exact stub sums need the non-baseline operation |
| AC7 | At risk | rejection happens inside the stub |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-05-001 |
| Status | TBD |
| Estimated Files Touched | 2 |
| Issue Type | Story |
| Epic Name | Balance Summary Endpoint |
| Epic Link | CS-05 |
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
