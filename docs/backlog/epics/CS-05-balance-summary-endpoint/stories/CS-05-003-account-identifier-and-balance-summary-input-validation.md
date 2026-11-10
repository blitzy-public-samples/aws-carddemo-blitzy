# CS-05-003: Account identifier and balance summary input validation

Parent epic: [CS-05 Balance Summary Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want a malformed or all-zero account number rejected at once, so that I see the error before any data lookup.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: the shared request-parameter validator and the Stage 0 hook that applies it at receipt, so that a malformed or all-zero account number ends in 400 before authentication, any cache read or any upstream call. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/validation/RequestParameterValidator.java` is created here as the shared validator.
>   - It holds a per-route rule table. This story's route is the balance summary: `GET /v1/accounts/{accountId}/balance-summary` and `CardServicingService.GetBalanceSummary`.
>   - Its `accountId` rule is `^[0-9]{11}$` and not all zeros.
>   - Later validation stories add their routes and rules to this class only.
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/validation/RequestValidationStage.java` is the Stage 0 hook.
>   - It is a REST filter and a gRPC server interceptor, each ordered after the `traceId` issue and ahead of the account access guard.
>   - It applies the validator to every route the validator declares and raises `INVALID_ARGUMENT` through the standard error object.
> - Test: `card-servicing-service/src/test/java/com/example/cardservicing/config/validation/RequestParameterValidatorTest.java`.
> - That makes 3 files.
> - Rejection details, following the account-number edit of Use Cases · COACTVWC:
>   - All-zero, non-numeric and wrong-length values all return message "Account number must be a non zero 11 digit number", with one `details` entry for `accountId`.
>   - An empty gRPC `account_id` returns "Account number not provided", with one `details` entry for `accountId`.
>   - The `details.field` string is `accountId` on both protocols.
>
> In scope:
>
> - The shared validator and its balance-summary rule.
> - The Stage 0 hook on both protocols.
> - Ordering ahead of the guard.
>
> Not in this story:
>
> - `typeCode`, `categoryCode`, `pageSize`, `pageToken` and `transactionId` rules (CS-06-001, CS-07-001, CS-08-001).
> - The snapshot-dependent `pageSize` bound (CS-07-001).
> - The balance-summary controller and gRPC method (CS-05-006).
> - Authentication and authorization (CS-12-003).
>
> ### 2. Acceptance Criteria
>
> - AC1: `GET /v1/accounts/00000000001/balance-summary`, and `GetBalanceSummary` with `account_id` `00000000001`, pass Stage 0 and proceed to authentication. The Auth stub request log shows one `ValidateToken` call.
> - AC2: `accountId` `00000000000` returns 400 with `code` `INVALID_ARGUMENT`, `message` "Account number must be a non zero 11 digit number" and exactly one `details` entry whose `field` is `accountId`. gRPC returns `INVALID_ARGUMENT` carrying the same `ErrorObject`.
> - AC3: Each of `0000000001` (10 digits), `000000000001` (12 digits), `ABCDEFGHIJK` (letters) and `0000000001A` (mixed) returns the AC2 status, message and `details` entry on both REST and gRPC.
> - AC4: A gRPC `GetBalanceSummary` with an empty `account_id` returns `INVALID_ARGUMENT` with `message` "Account number not provided" and one `details` entry for `accountId`.
> - AC5: For every request rejected in AC2–AC4, the Auth, Config, Card Data and Reference Data stub request logs hold zero entries, and the cache records zero reads.
>
> SCOPE-QUESTION (Resolved): AC5 names four of the five stub request logs and leaves out Transaction Activity. A Stage 0 400 leaves every stub request log empty, as the [TRD configuration snapshot rules](../../../../technical-requirements.md#configuration-snapshot) state for this story. AC5 therefore also requires the Transaction Activity stub request log to hold zero entries, and its assertion covers every request this story rejects at Stage 0, the AC6 request included: for each, the request logs of the Auth, Config, Card Data, Transaction Activity and Reference Data stubs all hold zero entries, and the cache records zero reads.
>
> - AC6: A malformed `accountId` sent with an expired fixture token returns 400, not 401, which proves that validation precedes the access guard.
> - AC7: Every 400 in AC2–AC6 carries the receipt `traceId` and validates against `ErrorObject` in `card-servicing-api.yaml`.
>
> ### 3. Dependencies
>
> - CS-05-002 — provides the standard error object, the filter error writer and the receipt trace identifier that every rejection uses.
>
> Risk: If a later filter is registered ahead of the Stage 0 hook, a malformed account number would reach the Auth service before rejection.

## 4. Design

- [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd)
- [Data model — account](../../../../data-model.md#account)
- [TRD — request path](../../../../technical-requirements.md#request-path)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is ≥80% per module. Cases: each AC2–AC4 value on both protocols, empty request logs for all five stubs (Auth, Config, Card Data, Transaction Activity and Reference Data) and zero cache reads using the loopback stubs, and the validation-before-guard ordering with an expired fixture token.
- Integration: Not applicable — this story makes no upstream call. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — rejected requests sit outside the success-path percentiles. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — the four-step flow sends valid input only. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — one shared parameter validator applied at receipt, ahead of the access guard, on both protocols.

Rationale: one receipt stage reading one rule set rejects a malformed account number on either protocol before any authentication call, cache read or upstream call.

- Rejected: Option B, per-protocol annotation checks, runs after the guard on the HTTP side and repeats the rules for remote procedure calls.
- Rejected: checking inside the balance summary assembly, because the Auth calls would already have run.

### Shared by both options

- Topology: validation runs inside the service process, and no check makes a network call of its own.
- Naming: field names in error details match the HTTP field names on both protocols.
- Addressing: the account number comes from the path on HTTP and from the request message on remote procedure calls.
- Isolation: the checks themselves read no cache or store.
- Fallback: none, since a value that fails a check ends the request at 400.
- Provisioning: none.
- Cut-over: unchanged when real services replace the stubs, because the checks make no upstream call.
- Failures: every rejection passes through the standard error object with its trace identifier.

### Design Option A — shared validator at receipt

One validator holds a rule table keyed by route, and the balance summary route carries the account number rule of eleven digits that are not all zeros. A receipt stage on each protocol sits after the trace identifier is issued and before the access guard, and it applies the table to every declared route. A rejection raises an invalid-argument failure that the standard error object renders with the account view screen text.

How it behaves:

- The HTTP receipt stage reads the account number from the path, and the remote procedure receipt stage reads it from the request message.
- Wrong length, non-digit characters and all zeros give the one screen message for a malformed number.
- An empty account number in a remote procedure request gives the screen message for a missing number.
- Each rejection carries one field entry that names the account number by its HTTP field name.
- A rejected request stops before the guard, so no Auth, Config, cache or data call follows.
- A route with no declared rule passes the stage unchanged.
- Later validation stories add their routes and rules to the same table.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One validator for both protocols (vs one per protocol) | one rule set for every endpoint | one shared change point for four endpoints |
| 2 | Receipt stage ahead of the access guard (vs inside the controller) | no authentication call on malformed input | stage order must stay explicit |
| 3 | Legacy message for both all-zero and malformed numbers (vs distinct messages) | matches the account view screen text | the message cannot tell the two cases apart |
| 4 | Rules held per route (vs code per endpoint) | later stories add one route entry | indirection for a single rule today |

Effort and risk: one validator, one receipt stage on two protocols and one test, with the risk in a later filter registered ahead of the receipt stage.

### Design Option B — per-protocol annotation checks

Framework annotations on the HTTP parameters check the account number when the controller binds the request, and each remote procedure method repeats the check at its start. Framework default messages report the failures, and no receipt stage exists. Both checks therefore run after every request filter, the access guard included.

How it differs:

- HTTP checks run at parameter binding, after the access guard has called the Auth service.
- Each remote procedure method carries its own copy of the account number rule.
- Failure text comes from framework defaults and differs between the two protocols.
- A malformed account number with an expired token gets 401 before the format check runs.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Framework annotations on HTTP parameters (vs shared validator) | declarative rules | they run after the guard filter |
| 2 | Separate check in each remote procedure method (vs shared validator) | local to the method | a second copy of every rule |
| 3 | Framework default messages (vs legacy text) | no message table | the text differs from the screen |
| 4 | No receipt stage (vs explicit stage) | fewer moving parts | malformed input reaches the Auth service |

Effort and risk: less shared code in this story and a repeated check in every endpoint story, with the risk in authentication calls on malformed input and in messages that drift between protocols.

### Recommendation

- Option: Option A.
- Why: only a receipt stage keeps malformed input away from every network call.
- Strongest argument against: an extra stage whose ordering must be guarded.
- Shared items that do not decide the choice: the account number rule, the field name in error details and the error object.
- Flip condition: the access guard moves behind the controllers.

## Design Validation

### Validation of Option A

Settled assumptions:

- The legacy screen uses one message for all-zero and non-numeric values.
- The legacy screen uses a separate message for a blank value.
- An eleven-digit pattern matches the account record's key.
- The access guard is a request filter and interceptor.

Open assumptions:

- Open → whether the UI shows the server message text or its own, settled by the deferred UI run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a well-formed number passes the receipt stage to the guard |
| AC2 | Holds | the zero rule returns the screen message with one field entry |
| AC3 | Holds | the eleven-digit rule rejects every wrong length and every non-digit |
| AC4 | Holds | the empty-value rule returns the missing-number message |
| AC5 | Holds | the receipt stage ends the request before any network call or cache read |
| AC6 | Holds | the receipt stage runs ahead of the access guard |
| AC7 | Holds | every rejection passes through the standard error object with the receipt trace identifier |

### Validation of Option B

Settled assumptions:

- The legacy screen uses one message for all-zero and non-numeric values.
- The legacy screen uses a separate message for a blank value.
- An eleven-digit pattern matches the account record's key.
- The access guard is a request filter and interceptor.

Open assumptions:

- Open → whether the UI shows the server message text or its own, settled by the deferred UI run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a well-formed number passes the guard and then the binding check |
| AC2 | At risk | the message differs per protocol |
| AC3 | At risk | the message differs per protocol |
| AC4 | Conditional | the method check must carry the missing-number text |
| AC5 | Breaks | the Auth service is called before the check |
| AC6 | Breaks | the guard returns 401 before the check can return 400 |
| AC7 | Holds | the standard error object still renders every rejection |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-05-002 |
| Status | TBD |
| Estimated Files Touched | 3 |
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
