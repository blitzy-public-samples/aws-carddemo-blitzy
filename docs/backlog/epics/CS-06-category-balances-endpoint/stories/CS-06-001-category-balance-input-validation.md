# CS-06-001: Category balance input validation

Parent epic: [CS-06 Category Balances Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want an invalid or unknown transaction type rejected with a clear error, so that I never see an empty list that hides a typing mistake.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRANCAT-TYPE-CD
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: the category-balance rule set, applied at Stage 0 before any network call and at Stage 3 against the transaction categories. A malformed account number or type code ends in 400 before authentication on the REST route `GET /v1/accounts/{accountId}/category-balances` and in `INVALID_ARGUMENT` on the gRPC method `CardServicingService.ListCategoryBalances`. A well-formed type code that no transaction category carries ends in the same rejection while reference data is usable. Target paths:
>
> > SCOPE-QUESTION (Resolved): The transaction categories alone cannot show that a type is unknown, because a stored transaction type may have no category. Stage 3 therefore checks against the transaction types and the transaction categories together: a well-formed type code ends in the rejection only when the transaction type list does not hold it and no transaction category carries it, while both lists are usable (fresh, or stale within the 86,400 s max stale age). A stored type that no category carries passes, and its request returns 200 with an empty category balance list. With either list unusable the check rejects nothing.
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/validation/RequestParameterValidator.java`, the shared validator CS-05-003 creates, is edited here.
>   - It gains the `typeCode` rule `^[\s\S]{2}$`, copied exactly from the `typeCode` query parameter of `card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml` and repeated in the `type_code` field comment of `card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto`: exactly two characters of any kind, following `Data Layouts · CVTRA01Y · TRANCAT-TYPE-CD` X(02). The pattern applies to the decoded REST value.
>   - An absent `typeCode`, meaning no REST query parameter or an unset proto3 `optional` `type_code`, returns every type and is not checked. A present but empty value fails the rule.
>   - Its per-route rule table gains the category-balance route, REST and gRPC, which points at the Stage 0 rule set below.
> - `card-servicing-service/src/main/java/com/example/cardservicing/category/CategoryBalanceRequestValidator.java` is created with two entry points.
>   - Stage 0 rule set: it reads `accountId` and `typeCode` from the REST path variable and query parameter, or `account_id` and `type_code` from `ListCategoryBalancesRequest`. It combines the shared `accountId` rule, `^[0-9]{11}$` with the all-zero value rejected following `Use Cases · COACTVWC` ("Account number must be a non zero 11 digit number"), with the `typeCode` rule when `typeCode` is present. It checks both fields, then raises one `INVALID_ARGUMENT` typed failure through the standard error object of CS-05-002, with one `details` entry per offending field, `accountId` before `typeCode`, and the `message` of the first offending field.
>   - The Stage 0 hook of CS-05-003 applies that rule set, because the hook applies the validator to every route the validator declares. It therefore runs after the `traceId` is issued and ahead of the account access guard on both protocols, and the hook file is unchanged.
>   - Stage 3 reference check: it takes the request's `typeCode` and the category-list `ReferenceDataResult` that the CS-13-003 Reference Data client returns from `transactionCategories`, as the CS-06-002 retrieval hands it on. It consults `isKnownTypeCode(typeCode)` only when `usable()` is true, which covers an upstream read, a fresh entry and a stale entry within the 86,400 s max stale age.
>   - With `usable()` true and the type code carried by no transaction category, it raises one `INVALID_ARGUMENT` typed failure with one `details` entry for `typeCode`, and the caller discards the category balances already read.
>   - With `usable()` false, it raises nothing and returns `SKIPPED`, so the request continues, the `typeCode` filter still applies, and CS-06-002 marks the result `PARTIAL`. It returns `KNOWN` for a carried type code and `NOT_REQUESTED` when `typeCode` is absent.
>   - Rejection texts: `message` "Transaction type code must be exactly 2 characters" with `details.description` "must be exactly 2 characters" for a malformed `typeCode`, and `message` "Transaction type code is unknown" with `details.description` "must be a type code carried by a transaction category" for an unknown one. The `details.field` string is `typeCode` on both protocols.
>
> > SCOPE-QUESTION (Resolved): The Stage 3 reference check above decides from the category list alone, which turns a stored type that no category carries into a 400. Resolution: the check takes the request's `typeCode` and both list results that the CS-06-002 retrieval hands on, the type-list `ReferenceDataResult` from `transactionTypes` and the category-list `ReferenceDataResult` from `transactionCategories`. With `usable()` true on both, it returns `KNOWN` when `isKnownTypeCode(typeCode)` answers true on either result, which covers a type the type list holds and no category carries, and otherwise raises the `INVALID_ARGUMENT` typed failure with one `details` entry for `typeCode`, after which the caller discards the category balances already read. With `usable()` false on either result, it raises nothing and returns `SKIPPED`, so the request continues, the `typeCode` filter still applies, and CS-06-002 marks the result `PARTIAL`. `NOT_REQUESTED` for an absent `typeCode` is unchanged. The unknown-code `message` stays "Transaction type code is unknown", and its `details.description` becomes "must be a stored transaction type code". The 3 files touched stand.
>
>   - CS-06-003 invokes the Stage 3 check after the CS-06-002 read and before the response is assembled. Every rejection uses the CS-05-002 standard error object with the `traceId` generated at receipt.
> - Test: `card-servicing-service/src/test/java/com/example/cardservicing/category/CategoryBalanceRequestValidatorTest.java`.
> - That makes 3 files: one edited and two created.
> - Context: `Data Layouts · CVTRA01Y` keys each category balance by TRANCAT-ACCT-ID 9(11), TRANCAT-TYPE-CD X(02) and TRANCAT-CD 9(04). `Reference Tables · TRANCATG file` holds 18 categories over type codes `01` to `07`, so `99` and `A1` are well-formed and unknown. No CardDemo screen shows category balances.
>
> > SCOPE-QUESTION (Resolved): Under the two-list check, `99` and `A1` stay unknown because `Reference Tables · TRANTYPE file` also holds only the seven type codes `01` to `07`.
>
> In scope:
>
> - The `typeCode` rule and the category-balance route entry in the shared validator.
> - The Stage 0 rule set and its pre-guard registration on REST and gRPC.
> - The Stage 3 unknown-type check and its skip rule.
> - The unit tests.
>
> Not in this story:
>
> - The category-balance read, the `typeCode` filter, the ordering and the `PARTIAL` marking (CS-06-002).
> - The REST controller and gRPC method wiring (CS-06-003).
> - The reference-data usability signal and the `isKnownTypeCode` answer (CS-13-003).
> - The `accountId` rule itself and the Stage 0 hook (CS-05-003).
> - Authentication and authorization (CS-12-003).
>
> ### 2. Acceptance Criteria
>
> - AC1: `accountId` `00000000001` with `typeCode` `01`, and `accountId` `00000000001` with `typeCode` absent, each pass Stage 0 on REST (`GET /v1/accounts/00000000001/category-balances?typeCode=01`, and the same path with no query) and on gRPC (`ListCategoryBalances` with `type_code` `01`, and with `type_code` unset), and proceed to authentication: the Auth stub request log shows one `ValidateToken` call for each request.
> - AC2: `typeCode` `1` (one character), `001` (three characters) and the empty value (REST `typeCode=`, gRPC `type_code` set to the empty string), each with `accountId` `00000000001`, return 400 with `code` `INVALID_ARGUMENT`, `message` "Transaction type code must be exactly 2 characters" and exactly one `details` entry whose `field` is `typeCode`. gRPC returns status `INVALID_ARGUMENT` carrying the same `ErrorObject`.
> - AC3: `accountId` `0000000001` (10 digits), `0000000000A` (a letter) and `00000000000` (all zero), each with `typeCode` `01`, return 400 with `code` `INVALID_ARGUMENT`, `message` "Account number must be a non zero 11 digit number" and exactly one `details` entry whose `field` is `accountId`, on REST and on gRPC.
> - AC4: With no fresh configuration snapshot held, every request rejected in AC2, AC3 and AC9 leaves the request logs of the Auth, Config, Card Data, Transaction Activity and Reference Data stubs that `StubHarness` starts with zero entries, and the cache records zero reads. `typeCode` `001` sent with an expired fixture token returns 400, not 401, which proves that Stage 0 runs before the account access guard.
> - AC5: With a usable category list (a `ReferenceDataResult` with `usable()` true, tested once with status `FRESH` and once with status `STALE`, the latter an entry within the 86,400 s max stale age), `typeCode` `99` and `typeCode` `A1` each make the Stage 3 check raise `INVALID_ARGUMENT` with `message` "Transaction type code is unknown" and exactly one `details` entry whose `field` is `typeCode`. No category balance read for that request reaches a response: the rendered 400 body holds only `code`, `message`, `traceId` and `details`.
> > SCOPE-QUESTION (Resolved): AC5 names only the category list, but the check decides from both lists. AC5 runs with a usable type list as well, a `ReferenceDataResult` built from the seven seed types `01` to `07` of `Reference Tables · TRANTYPE file`, with the same status as the category list in each run, so neither list holds `99` or `A1`. The rejection's `details.description` is "must be a stored transaction type code". With the type list unusable instead, AC11 applies.
> - AC6: With the AC5 usable category list, `typeCode` `01` (Purchase) and `typeCode` `02` (Payment, categories `0001` to `0003` in `Reference Tables · TRANCATG file`) each make the Stage 3 check return `KNOWN`, and an absent `typeCode` makes it return `NOT_REQUESTED`, with no failure raised. `02` passes although account `00000000001` holds no type `02` category balance.
> > SCOPE-QUESTION (Resolved): AC6 runs with the AC5 usable type list as well, which holds both `01` and `02`.
> - AC7: With an unusable category list (the `PARTIAL` result with `usable()` false that the Reference Data client returns when the stub fails with no entry held or with an entry aged 86,400 s), `typeCode` `99` raises no failure and the Stage 3 check returns `SKIPPED`.
> - AC8: Every rejection in AC2 to AC5 and AC9 carries the `traceId` generated at receipt, equal to the `traceId` on that request's completion log line. Every REST 400 body validates against `ErrorObject` in `card-servicing-api.yaml`, and every gRPC rejection carries exactly one packed `cardservicing.common.v1.ErrorObject` in the `google.rpc.Status` details, with the same `code`, `message` and `details` as on REST.
> - AC9: `accountId` `0000000001` with `typeCode` `001` returns 400 with `code` `INVALID_ARGUMENT`, the AC3 `message` and exactly two `details` entries, `accountId` first and `typeCode` second, on REST and on gRPC.
> > SCOPE-QUESTION (Resolved): Which criterion covers a stored type that no category carries, and a type list that is unusable while the category list is usable? None of AC1–AC9 does. Resolution: AC10 and AC11 below, run in `CategoryBalanceRequestValidatorTest` on results built through the `ReferenceDataResult` factories of CS-13-003.
> >
> > - AC10: With the AC5 usable category list and a usable type list holding the seven seed types and also type code `08`, which no category carries, `typeCode` `08` makes the Stage 3 check return `KNOWN` with no failure raised.
> > - AC11: With the AC5 usable category list and an unusable type list (the `PARTIAL` result with `usable()` false), `typeCode` `99` raises no failure and the Stage 3 check returns `SKIPPED`.
>
> ### 3. Dependencies
>
> - CS-05-003 — shared request-parameter validator and its pre-guard validation stage, extended here with the type code rule
> - CS-13-003 — Reference Data client supplies the category reference read and its usability signal
>
> > SCOPE-QUESTION (Resolved): CS-13-003 also supplies the type reference read and its usability signal, which the Stage 3 check consults beside the category reference read.
>
> Risk: a usability signal that cannot tell an entry within max stale age from an unusable one turns a typing mistake into an empty 200.

## 4. Design

- [Category balances sequence](../../../../architecture/sequence-category-balances.mmd)
- [Data model — category_balance](../../../../data-model.md#category_balance)
- [Data model — transaction_type](../../../../data-model.md#transaction_type)
- [Data model — transaction_category](../../../../data-model.md#transaction_category)
- [Data model — reference data as data](../../../../data-model.md#reference-data-as-data)
- [TRD — request path stages](../../../../technical-requirements.md#stages)
- [TRD — edge cases and error mapping](../../../../technical-requirements.md#edge-cases)
- [TRD — degradation table and cache rules](../../../../technical-requirements.md#resilience)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
- [Reference Data gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto)
- [Shared error object contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is ≥80% per module. Cases: AC1–AC11 on REST and gRPC; `1`, `001` and the empty value rejected at Stage 0; `99` and `A1` accepted at Stage 0 and rejected at Stage 3 with both reference lists usable; `08` known from a type list that holds it while no category carries it; usable `FRESH` and `STALE` type-list and category-list results and the unusable `PARTIAL` result of each list, built through the `ReferenceDataResult` factories of CS-13-003; the empty request logs of the five stubs and zero cache reads through the loopback stubs that `StubHarness.allRegistered()` starts; the expired-token ordering case; and a contract check that reads the `typeCode` query-parameter pattern from `openapi/card-servicing-api.yaml` on the classpath and asserts it equals the validator's pattern.
- Integration: Not applicable — this story makes no upstream call of its own, and the type-list and category-list reads it consumes are proven by the Reference Data integration suite CS-13-004. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — not a balance-retrieval or drill-down story, and rejected requests sit outside the success-path percentiles; the category balance path is measured by CS-02-002. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Applies, through the category balance end-to-end step (CS-02-001), which owns the end-to-end calls with a malformed and an unknown type code, both sent over REST for account `00000000001` with `fixture-token-user0001`. The `typeCode=1` call (CS-02-001 AC5) sends the one-character value of AC2 and asserts the empty request logs on all five stubs of AC4, both at Stage 0. The `typeCode=99` call (CS-02-001 AC4) exercises the Stage 3 rejection of AC5 with a usable type list and a `FRESH` category list, the latter read once from the Reference Data stub. The two 200 calls, with `typeCode=01` and with no `typeCode`, show the REST pass case of AC1. The step's expired-token 401 and access-denial 403 calls prove the account access guard (CS-12-003), not this story's rules. The boundaries not exercised there stay with the unit cases: `001`, the empty value and `A1`, the AC3 account numbers, both fields malformed (AC9), the `STALE` usable and the unusable category lists (AC5–AC7), the stored type with no category and the unusable type list (AC10 and AC11), the AC6 `KNOWN` and `NOT_REQUESTED` answers, the zero cache reads and the expired-token ordering of AC4, the AC8 trace and schema checks, and every gRPC case.
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call. The `typeCode=1` call returns 400 with `code` `INVALID_ARGUMENT`, `message` "Transaction type code must be exactly 2 characters", a non-empty `traceId` and exactly one `details` entry for `typeCode`, and leaves the request logs of all five stubs empty. The `typeCode=99` call returns 400 with `code` `INVALID_ARGUMENT`, `message` "Transaction type code is unknown", a non-empty `traceId` and exactly one `details` entry for `typeCode`, with exactly one `ListTransactionCategories` call on the Reference Data stub, outcome `OK`.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — a syntactic type code rule in the shared validator ahead of the access guard, plus a reference check after the concurrent reads.

Rationale: the shape check costs no network call, and the known-type check reuses the type list and the category list the request already reads beside the balances, so neither adds a step to the critical path.

- Rejected: Option B, a reference lookup before the balance read, adds a serialized upstream call to the critical path.

### Shared by both options

- Topology: validation runs inside the service process, with no new component and no network hop of its own.
- Naming: the type code is the two-character code of the category balance record, and each error detail names the offending field as the HTTP interface description names it.
- Addressing: the account number comes from the request path and the type code from the query, or both from the remote procedure request message.
- Isolation: validation reads no account data, and the known-type check reads only the type list and category list results handed to it.
- Fallback: an unusable type list or category list skips the unknown-type check, the type code filter still applies and the result is marked partial.
- Provisioning: no new infrastructure and no new setting.
- Cut-over: an unknown type changes from an empty list to a rejection only while both reference lists are usable, and nothing changes when real services replace the stubs.
- Failures: every rejection is the standard error object with an invalid-argument status and the trace number issued at receipt.

### Design Option A — shape check at receipt, known-type check after the reads

The shared validator gains a two-character type code rule and a category balance route entry that points at a category balance rule set, which the receipt stage applies before the access guard. After the guard allows access, the category balance read, the category list read and the type list read run side by side, and the known-type check reads the two list results once all three finish. A type code that the type list does not hold and no transaction category carries then ends the request with a bad-request rejection while both lists are usable, and the balances already read are dropped.

How it behaves:

- A type code of one or three characters, or a present but empty value, is rejected at receipt with one field entry naming the type code.
- A malformed or all-zero account number is rejected at receipt with the account view screen text.
- With both values malformed, one rejection carries two field entries, account number first.
- A request rejected at receipt stops before the guard, so no Auth, Config, cache or data call follows.
- After the reads, a type code that the type list does not hold and no transaction category carries is rejected while both lists are fresh or within their maximum stale age.
- A stored type that no transaction category carries passes the known-type check, and the request continues to an empty list.
- When either list is unusable, the known-type check reports itself skipped and the request continues to the filter.
- A request with no type code passes both checks unchanged.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Shape check at receipt ahead of the guard (vs after the guard) | malformed input never reaches the Auth service | the receipt stage order must stay explicit |
| 2 | Known-type check reusing the concurrent type list and category list reads (vs a separate lookup) | no extra upstream call on the request path | a rejected request still pays for a balance read |
| 3 | Skip the known-type check when either reference list is unusable (vs reject the request) | balances stay available during a Reference Data outage | an unknown type code yields an empty partial list during the outage |
| 4 | Type code rule held once in the shared validator (vs a copy in the category rule set) | one change point when the interface description changes | one shared rule table that every endpoint edits |

Effort and risk: one rule and one route entry in the shared validator, one rule set with two checks and one test class, with the risk in a usability signal that cannot tell a stale usable list from an unusable one.

### Design Option B — known-type check before the balance read

The receipt stage and the shape rule stay as in Option A, but the known-type check moves ahead of the balance read. After the guard allows access, the service first reads the type list and the category list, rejects an unknown type code, and only then reads the category balances. The reference list reads and the balance read therefore run one after the other rather than side by side.

How it differs:

- The type list and category list reads become their own step between the guard and the balance read.
- A rejected unknown type code costs no balance read.
- A valid request waits for two upstream reads in sequence inside the same deadline.
- On a cold path with two full Auth attempts, the balance read finds less than one full attempt of the deadline left.
- The category balance request no longer follows the single concurrent data stage of the request path.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Reference list reads before the balance read (vs concurrent reads) | an unknown type code never triggers a balance read | a second serial upstream call on every request |
| 2 | Known-type check on its own early results (vs the shared concurrent reads) | the check needs no balance result | the request path departs from the fixed stage order |
| 3 | Skip on unusable reference data (vs reject the request) | balances stay available during a Reference Data outage | the skipped check has already spent its wait on the list read |

Effort and risk: the same rule set and a reordered data stage in the retrieval story, with the risk in valid requests running out of deadline on a cold path.

### Recommendation

- Option: Option A.
- Why: one concurrent data stage keeps the balance read and the reference reads inside the 900 millisecond deadline, and the shape check keeps malformed input away from every network call.
- Strongest argument against: a request with an unknown type code still pays for a balance read that is then discarded.
- Shared items that do not decide the choice: the two-character type code rule, the receipt stage ahead of the guard, the two-list known-type rule, the skip rule on an unusable reference list, the standard error object and the receipt trace number.
- Flip conditions: a reference copy held in memory with no network call, which makes an early known-type check free, or a deadline budget that holds two serial data reads on a cold path.

## Design Validation

### Option A

Settled assumptions:

- A stored type code is held by the type reference list even when no category carries it.
- The receipt stage runs on both the HTTP and the remote procedure interface, ahead of the access guard.
- The HTTP interface description declares exactly two characters of any kind for the type code.
- The reference data client returns one result per list, each stating whether the list is usable and whether it holds a type code.
- The account number rule and the receipt stage come from the shared validator story.
- The category balance type labels come from the type list and the category labels from the category list, as the retrieval story settles, because a category carries no type description.
- The retrieval hands on the type list result beside the category list result to the known-type check, as the retrieval story settles.

Open assumptions:

- Open → the reference data client's usability signal distinguishes a list within its maximum stale age from an unusable one, settled by that story's unit tests.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a well-formed pair passes the receipt stage to the guard |
| AC2 | Holds | the two-character rule rejects one, three and zero characters |
| AC3 | Holds | the shared account number rule rejects each malformed value with the screen text |
| AC4 | Holds | the receipt stage ends the request before any network call or cache read |
| AC5 | Conditional | the rejection relies on a stale list within its maximum age counting as usable |
| AC6 | Holds | both type codes are held by the type list and carried by categories |
| AC7 | Holds | an unusable list makes the check report itself skipped |
| AC8 | Holds | every rejection passes through the standard error object with the receipt trace number |
| AC9 | Holds | the rule set checks both fields before raising one failure |
| AC10 | Holds | the type list answer makes a type with no category known |
| AC11 | Holds | an unusable type list makes the check report itself skipped |

### Option B

Settled assumptions:

- A stored type code is held by the type reference list even when no category carries it.
- The receipt stage runs on both the HTTP and the remote procedure interface, ahead of the access guard.
- The HTTP interface description declares exactly two characters of any kind for the type code.
- The deadline leaves room for one full data attempt after two full Auth attempts on a cold path.

Open assumptions:

- Open → the reference data client's usability signal distinguishes a list within its maximum stale age from an unusable one, settled by that story's unit tests.
- Open → whether two serial data reads fit the cold-path budget, settled by the category balance service-level scenario.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the receipt stage is the same as in Option A |
| AC2 | Holds | the same two-character rule runs at receipt |
| AC3 | Holds | the same account number rule runs at receipt |
| AC4 | Holds | the receipt stage still ends the request before any network call |
| AC5 | Conditional | the early rejection relies on a stale list within its maximum age counting as usable |
| AC6 | At risk | a valid type code waits for two serial reads that can exhaust the deadline |
| AC7 | Holds | an unusable list still makes the check report itself skipped |
| AC8 | Holds | every rejection passes through the standard error object with the receipt trace number |
| AC9 | Holds | the same rule set checks both fields at receipt |
| AC10 | Holds | the early check reads the same type list answer |
| AC11 | Holds | an unusable type list still makes the check report itself skipped |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-05-003, CS-13-003 |
| Status | TBD |
| Estimated Files Touched | 3 |
| Issue Type | Story |
| Epic Name | Category Balances Endpoint |
| Epic Link | CS-06 |
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
