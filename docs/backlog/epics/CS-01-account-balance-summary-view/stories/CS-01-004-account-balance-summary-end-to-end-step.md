# CS-01-004: Account balance summary end-to-end step

Parent epic: [CS-01 Account Balance Summary View](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want my balance summary shown from the real path, so that the account view is proven before the UI is built.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: step 1 of the one ordered end-to-end flow that stands in for the deferred Card Servicing UI. Through the end-to-end client of CS-01-002 it reads the account balance summary of the Compose stack (CS-01-001) as the UI's account view will, checks every account-section field that Use Cases · COACTVWC shows against its contract field (the TRD field map: Account Number → `accountId`, Active Y/N → `activeStatus`, Opened → `openDate`, Credit Limit → `creditLimit`, Expiry → `expirationDate`, Cash credit Limit → `cashCreditLimit`, Reissue → `reissueDate`, Current Balance → `currentBalance`, Current Cycle Credit → `currentCycleCredit`, Account Group → `groupId`, Current Cycle Debit → `currentCycleDebit`), plus the type-group balances of Data Layouts · CVTRA01Y · TRAN-CAT-BAL, reads the upstream calls behind each answer from the stub request logs, and hands the `typeCode` it yields to the next step. Expected values come from the CardDemo sample records: Data Layouts · CVACT01Y for account `00000000001`, Data Layouts · CVTRA01Y for its one category balance, and Data Layouts · CVTRA03Y for the type description. Target path:
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/e2e/CardServicingFlowE2eTest.java` (create):
>   - Flow class: the `e2e`-tagged class that registers the CS-01-002 suite base with `@ExtendWith(E2eClient.Suite.class)`, orders its steps with `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` and keeps one instance across steps with `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`. This story writes step 1 at order 1. CS-02-001, CS-03-001, CS-03-002 and CS-04-001 add the later steps to this class and never re-implement step 1.
>   - Cache start state: before step 1 the class empties the stack's cache with `docker compose -f <compose file> exec -T redis redis-cli FLUSHALL`, started through the JDK process API, where the compose file is the `cardservicing.e2e.compose-file` system property that CS-01-002 sets. A non-zero exit fails step 1. Another `e2e`-tagged class, `E2eClientTest.OnStack` (CS-01-002), also reads account `00000000001`, and JUnit fixes no order between test classes, so this emptying, not the class order, makes step 1's allowed call the first read of the account, its category balances and the transaction types.
>   - Call order in step 1, one call at a time through the client: `GET /v1/accounts/00000000000/balance-summary`, then `GET /v1/accounts/0000000001/balance-summary`, then `GET /v1/accounts/00000000001/balance-summary` with `fixture-token-malformed`, then `GET /v1/accounts/00000000011/balance-summary`, then `GET /v1/accounts/00000000001/balance-summary`. Every call except the third carries the client's default token `fixture-token-user0001` of `USER0001`. The rejected calls come first because none of them reads the cache or launches the background Config refresh, so no refresh started by the allowed call can land in a rejected call's request-log window.
>     > SCOPE-QUESTION (Resolved): The rejected-first order keeps this step's own refresh out of the rejected windows, but a refresh launched by an earlier test class, such as `E2eClientTest.OnStack`, is kept out by the client's settled windows (CS-01-002), which close each call's window only once any refresh it launched is listed, so the empty Config and data logs that AC4–AC6 assert hold whichever class ran first. No request waits for the refresh.
>   - Hand-off: step 1 stores the `typeCode` of the first `balancesByTypeGroup` element in the flow instance for the category balance step (CS-02-001). Its accessor fails the calling step with a message naming step 1 when step 1 did not pass, so a failed step 1 stops every later step instead of letting it run on no value.
> - Together this is the 1 file touched.
>
> In scope:
>
> - Step 1 of the flow: the allowed balance-summary call and its field checks.
> - The stub request-log checks for each call of the step.
> - The access-denial, invalid account number and malformed-token checks for this endpoint.
> - The cache emptying before step 1 and the `typeCode` hand-off.
>
> Not in this story:
>
> - The later flow steps (CS-02-001, CS-03-001, CS-03-002, CS-04-001).
> - The balance summary SLA scenario (CS-01-005).
> - The COACTVW Customer Details fields, carried as flagged field gaps in the epic README.
> - The endpoint itself (CS-05-003, CS-05-004, CS-05-005, CS-05-006) and the end-to-end client (CS-01-002).
>
> ### 2. Acceptance Criteria
>
> - AC1: `GET /v1/accounts/00000000001/balance-summary` with `fixture-token-user0001` returns 200 with `accountId` `"00000000001"`, `activeStatus` `"Y"`, `currentBalance` `"194.00"`, `creditLimit` `"2020.00"`, `cashCreditLimit` `"1020.00"`, `currentCycleCredit` `"0.00"`, `currentCycleDebit` `"0.00"`, `openDate` `"2014-11-20"`, `expirationDate` `"2025-05-20"` and `reissueDate` `"2025-05-20"`, and the body holds no `groupId` property, because the sample account's group ID is blank.
> - AC2: In the AC1 answer, `balancesByTypeGroup` holds exactly one element, with `typeCode` `"01"`, `typeDescription` `"Purchase"` and `balance` `"0.00"`. `dataStatus` and the `X-Data-Status` header are both `FRESH`, and `asOf` matches `^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z$`, an RFC 3339 timestamp with six fractional digits and a `Z` suffix. Every value checked in AC1 and AC2, `asOf` aside, equals the `fresh` 200 example of `GetBalanceSummary` in `card-servicing-api.yaml`. After the call the flow instance holds `typeCode` `"01"` for the next step.
> - AC3: After the cache emptying, the request-log entries the client returns for the AC1 call show `cardservicing.auth.v1.AuthService/ValidateToken` and `cardservicing.auth.v1.AuthService/AuthorizeAccountAccess` on the Auth stub, exactly `cardservicing.carddata.v1.CardDataService/GetAccount` and `cardservicing.carddata.v1.CardDataService/ListCategoryBalances` on the Card Data stub, and `cardservicing.referencedata.v1.ReferenceDataService/ListTransactionTypes` on the Reference Data stub, each exactly once with outcome `OK`, and no entry on the Transaction Activity stub.
> - AC4: `GET /v1/accounts/00000000011/balance-summary` with `fixture-token-user0001` returns 403 with `code` `PERMISSION_DENIED` and a non-empty `traceId`, because `USER0001` is entitled to `00000000001`–`00000000010` among the sample accounts and `00000000011` is a sample account outside that range. The call's entries show only `ValidateToken` and `AuthorizeAccountAccess` on the Auth stub and no entry on the Card Data, Transaction Activity, Reference Data or Config stub, so no account data is read from upstream before access is allowed. That no cache read precedes the access decision either is proven by the guard ordering of CS-12-003.
> - AC5: `GET /v1/accounts/00000000000/balance-summary` (all zeros) and `GET /v1/accounts/0000000001/balance-summary` (10 digits), each with `fixture-token-user0001`, return 400 with `code` `INVALID_ARGUMENT`, `message` `Account number must be a non zero 11 digit number`, a non-empty `traceId` and exactly one `details` entry, whose `field` is `accountId`. For each of the two calls the entries are empty on all five stubs.
> - AC6: `GET /v1/accounts/00000000001/balance-summary` with `fixture-token-malformed` returns 401 with `code` `UNAUTHENTICATED` and a non-empty `traceId`. The call's entries show only `ValidateToken` on the Auth stub, with outcome `UNAUTHENTICATED`, and no entry on the Card Data, Transaction Activity, Reference Data or Config stub.
> - AC7: Each of the five calls of this step receives its full answer below 1,000 ms of client-measured latency, and the completion log line the client matches to it shows `cutoffMs` ≤ 900.
> - AC8: The step runs under the stack defaults of the upstream call policy: each attempt times out at the lesser of 300 ms and the remaining budget, and at most two retries follow, after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, each starting only when its backoff plus a full 300 ms attempt fits before the 900 ms cut-off. With failure injection off, the client reports no retried call for any of the five calls. The degraded and failed outcomes of each upstream call this endpoint makes are proven under injection by CS-05-005 and the integration suites CS-09-006, CS-12-005, CS-13-004 and CS-11-007, not by this step: Card Data `GetAccount` or `ListCategoryBalances` served `STALE` from a cache entry within its 300 s max stale age, otherwise 503 or 504; Reference Data unusable giving 200 `PARTIAL` with every `typeDescription` JSON `null`; Auth failing closed with 401, 503 or 504; Config leaving the request and its `dataStatus` unaffected.
>
> ### 3. Dependencies
>
> - CS-01-002 — client, fixture token, stub request-log reader and the stack file location the cache emptying uses.
>
> Risk: a warm cache hides the upstream calls AC3 checks, so AC3 depends on the cache emptying before step 1, which needs the stack tool on the build host to reach the stack's Redis container.

## 4. Design

- [Sequence — balance summary](../../../../architecture/sequence-balance-summary.mmd)
- [Data model — account](../../../../data-model.md#account)
- [Data model — screen-field coverage matrix](../../../../data-model.md#screen-field-coverage-matrix)
- [TRD — Request path](../../../../technical-requirements.md#request-path)
- [TRD — Deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — Card Servicing UI field map](../../../../technical-requirements.md#card-servicing-ui)
- [TRD — Resilience](../../../../technical-requirements.md#resilience)
- Contracts:
  - [card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
  - [card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Not applicable — the story adds an end-to-end test only, with no production code and no untagged case. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
- Integration — Not applicable — the injected upstream outcomes are proven by the integration suites named in AC8. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — CS-01-005 measures this endpoint. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50/p95/p99 below 1,000 ms over successful requests with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end — Applies — this story is step 1 of the flow, covering AC1–AC8 on the stack. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call, with every call's `cutoffMs` ≤ 900 and client-measured latency below 1,000 ms.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A, named field checks.

Rationale: named field and per-call log checks prove the summary on the real request path.

- Rejected: Design Option B, a whole-answer snapshot needing masks and broken by added fields.

### Shared by both options

- Topology: from the build host, the end-to-end client reaches the service and five stubs.
- Naming: the step opens the ordered flow and hands its type code onward.
- Addressing: the first fixture user reads the first sample account and is denied the eleventh.
- Isolation: the cache is emptied, then rejected calls run first in settled log windows.
- Fallback: none; any unexpected status, field or log entry fails the step.
- Provisioning: the end-to-end gate starts and stops the stack; the step reads only sample data.
- Cut-over: a real interface replaces the client; real services replace stubs by address and credential.
- Failures: a failed step halts later steps at the hand-off; the assembly story and integration suites prove injected failures.

### Design Option A — named field checks with per-call stub log checks

Four rejected calls precede the allowed call. The step checks each answer by named field and each call's stub log.

How it behaves:

- The blank account group must be absent; the read time is checked by form.
- Each call's stub log shows only the upstream operations its outcome allows.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Field-by-field checks (vs a whole-answer snapshot) | per-call values stay outside the comparison | new contract fields stay unchecked pending assertion |
| 2 | Expected values in the step (vs from the contract example) | the step reads plainly against the sample record | contract example changes need step changes |
| 3 | Blank account group checked as absent (vs empty text accepted) | the check follows the contract's blank-field omission rule | empty text for a blank group fails |
| 4 | Read time checked by form (vs by value) | the check holds on every run | a wrong read time of the right form passes |
| 5 | Upstream operations checked per call (vs per step) | each rejected call proves its stop point | each call waits for settled stub logs |

Effort and risk: small, one file, at risk of sample-record drift.

### Design Option B — snapshot comparison of the whole answer

Each answer is compared with a stored reference after masking per-call values. Calls and log checks match Option A.

How it differs:

- Differences show in one listing, not by field name.
- An added optional field fails pending regenerated references.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Whole-answer snapshot (vs field-by-field checks) | every field is compared without naming it | each additive contract change breaks the snapshot |
| 2 | Read time and trace identifier masked (vs compared exactly) | masked per-call values no longer fail the comparison | the mask also hides a malformed read time |
| 3 | Reference answers in a file (vs in the step) | the expected answers read as whole documents | the step touches a second file |

Effort and risk: small, two files, at risk on the masked read time.

### Recommendation

- Option: Design Option A.
- Why: it names every field, needs no masks and stays in one file.
- Strongest argument against: added contract fields stay unasserted until named.
- Shared items that do not decide the choice: the client, cache emptying, call order, log checks and deadline check.
- Flip conditions: the contract outgrowing the named fields, or the read time leaving the answer body.

## Design Validation

### Option A

Settled assumptions:

- Sample records fix the expected values, and the fixture user holds the first ten accounts.
- The service logs each request's cut-off time, and stub logs number entries in receipt order.
- A rejected call reads no cache entry and launches no configuration refresh.

Open assumptions:

- Empty cache Open → an account read in the first stack run's card data log.
- Cache container access Open → the emptying command's first run.
- Log entry before stub answer Open → the stub hosting story's write order.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | each field is checked by name |
| AC2 | Holds | each value is checked by name or form |
| AC3 | Conditional | the allowed call needs an empty cache |
| AC4 | Holds | the access guard denies before any data read |
| AC5 | Holds | both numbers fail before any upstream call |
| AC6 | Holds | token validation fails before the authorization stage |
| AC7 | Holds | the client checks each deadline |
| AC8 | Holds | no call retries with injection off |

### Option B

Settled assumptions:

- Option A's sample-record and entitlement assumptions hold, and one sample-set run yields the references.

Open assumptions:

- Read-time mask Open → a mask keeping the form check.
- Reference upkeep Open → an agreed regeneration rule for additive changes.
- Empty cache Open → the first run's card data log.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the snapshot holds every field |
| AC2 | At risk | the mask skips the form check |
| AC3 | Conditional | the allowed call needs an empty cache |
| AC4 | Conditional | the denial's trace identifier needs a mask |
| AC5 | Conditional | each rejection's trace identifier needs a mask |
| AC6 | Conditional | the rejection's trace identifier needs a mask |
| AC7 | Holds | the client checks each deadline |
| AC8 | Holds | no call retries with injection off |

## Metadata

| Field | Value |
|---|---|
| Build wave | W3 |
| Depends on | CS-01-002 |
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
