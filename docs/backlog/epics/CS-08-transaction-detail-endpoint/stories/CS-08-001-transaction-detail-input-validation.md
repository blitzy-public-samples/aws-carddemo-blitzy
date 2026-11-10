# CS-08-001: Transaction detail input validation

Parent epic: [CS-08 Transaction Detail Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want a malformed transaction identifier rejected at once, so that I see the error before any lookup.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN01C
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: the transaction-detail rule set applied at Stage 0, at receipt and before any network call, so that a malformed account number or transaction identifier ends in 400 on the REST route `GET /v1/accounts/{accountId}/transactions/{transactionId}` and in `INVALID_ARGUMENT` on the gRPC method `CardServicingService.GetTransaction`. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/validation/RequestParameterValidator.java`, the shared validator CS-05-003 creates, is edited here.
>   - It gains the `transactionId` rule `^[A-Za-z0-9]{16}$`, copied exactly from the `transactionId` path parameter of `card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml` and repeated in the `transaction_id` field comment of `card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto`. The length is 16 characters, following `Data Layouts · CVTRA05Y · TRAN-ID` X(16).
>     > SCOPE-QUESTION (Resolved): The contract pattern now reads `^[\s\S]{16}$` (exactly 16 characters of any kind, the TRAN-ID X(16) domain), so the copied rule is that pattern. The contract also states that a `transactionId` of 16 spaces or of 16 NUL characters (LOW-VALUES) is empty and rejected, following `Use Cases · COTRN01C`, so the validator applies that empty rule beside the pattern. The Risk line's equality test still holds.
>   - Its per-route rule table gains the transaction-detail route, REST and gRPC, which points at the rule set below.
> - `card-servicing-service/src/main/java/com/example/cardservicing/drill/TransactionDetailRequestValidator.java` is created as the transaction-detail rule set.
>   - It reads `accountId` and `transactionId` from the REST path variables, or `account_id` and `transaction_id` from `GetTransactionRequest`.
>   - It combines the shared `accountId` rule, `^[0-9]{11}$` with the all-zero value rejected following `Use Cases · COACTVWC`, with the `transactionId` rule.
>   - It checks both fields, then raises one `INVALID_ARGUMENT` typed failure through the standard error object of CS-05-002, with one `details` entry naming each offending field, `accountId` before `transactionId`. The `details.field` strings are `accountId` and `transactionId` on both protocols.
>   - The Stage 0 hook of CS-05-003 applies it, because that hook applies the validator to every route the validator declares. It therefore runs after the `traceId` is issued and ahead of the account access guard, and the hook file is unchanged.
> - Test: `card-servicing-service/src/test/java/com/example/cardservicing/drill/TransactionDetailRequestValidatorTest.java`.
> - That makes 3 files: one edited and two created.
> - Context: Use Cases · COTRN01C rejects an empty Tran ID with "Tran ID can NOT be empty..." and reads the transaction by Tran ID alone. The COTRN01 "Enter Tran ID" input field is 16 characters. The baseline adds the account path, so both values are checked here.
>
> In scope:
>
> - Syntactic checks of `accountId` and `transactionId` only.
> - Both protocols, REST and gRPC.
> - Stage 0, before authentication, any cache read or any upstream call.
>
> Not in this story:
>
> - The `accountId` rule itself and the Stage 0 hook (CS-05-003).
> - The ownership check and the transaction retrieval (CS-08-002).
> - The REST controller and gRPC method exposure (CS-08-003).
> - Authentication and authorization (CS-12-003).
>
> ### 2. Acceptance Criteria
>
> - AC1: `transactionId` `0000000776200014` with `accountId` `00000000001` passes validation on REST and on gRPC, and the request proceeds to authentication: the Auth stub request log shows one `ValidateToken` call for each request.
> - AC2: `transactionId` `000000077620001` (15 characters) returns 400 with `code` `INVALID_ARGUMENT` and exactly one `details` entry whose `field` is `transactionId`.
> - AC3: `transactionId` `00000007762000140` (17 characters) returns 400 with `code` `INVALID_ARGUMENT` and exactly one `details` entry whose `field` is `transactionId`.
> - AC4: `transactionId` `000000077620001!`, holding a character the contract pattern `^[A-Za-z0-9]{16}$` disallows, returns 400 with `code` `INVALID_ARGUMENT` and exactly one `details` entry whose `field` is `transactionId`.
>   > SCOPE-QUESTION (Resolved): `000000077620001!` is a valid TRAN-ID X(16) value under the corrected pattern `^[\s\S]{16}$`, which disallows no character, so AC4 reads as follows. `transactionId` `000000077620001!` with `accountId` `00000000001` passes validation on REST and on gRPC and proceeds to authentication as in AC1, as does `transactionId` `000000077620001é`, sent percent-encoded as `000000077620001%C3%A9` on REST. Length (AC2, AC3) and the empty rule beneath AC11 are the only syntactic rejections of a `transactionId`.
> - AC5: `accountId` `0000000001` (10 digits) with `transactionId` `0000000776200014` returns 400 with `code` `INVALID_ARGUMENT`, `message` "Account number must be a non zero 11 digit number" and exactly one `details` entry whose `field` is `accountId`.
> - AC6: `accountId` `00000000000` with `transactionId` `0000000776200014` returns the AC5 status, message and `details` entry, following the zero rule of `Use Cases · COACTVWC`.
> - AC7: Each AC2–AC6 value, sent as `account_id` and `transaction_id` in a gRPC `GetTransaction` request, returns status `INVALID_ARGUMENT` with exactly one packed `cardservicing.common.v1.ErrorObject` in the `google.rpc.Status` details, whose `details` entry names the same field as on REST.
>   > SCOPE-QUESTION (Resolved): AC7 applies to the AC2, AC3, AC5 and AC6 values. The AC4 value `000000077620001!` passes validation on gRPC, as the note beneath AC4 states.
> - AC8: With no fresh configuration snapshot held, every request this story rejects at Stage 0 leaves the request logs of the Auth, Config, Transaction Activity, Card Data and Reference Data stubs with zero entries, and the cache records zero reads.
> - AC9: Every rejection in this story, REST or gRPC, carries the `traceId` generated at receipt, equal to the `traceId` on that request's completion log line, and every REST 400 body validates against `ErrorObject` in `card-servicing-api.yaml`.
> - AC10: `accountId` `0000000001` with `transactionId` `000000077620001` returns 400 with `code` `INVALID_ARGUMENT` and exactly two `details` entries, `accountId` first and `transactionId` second, on REST and on gRPC.
> - AC11: A gRPC `GetTransaction` request with `account_id` `00000000001` and an empty `transaction_id` returns `INVALID_ARGUMENT` with exactly one `details` entry whose `field` is `transactionId`.
>   > SCOPE-QUESTION (Resolved): A `transactionId` of 16 spaces or of 16 NUL characters (U+0000, LOW-VALUES), with `accountId` `00000000001`, sent percent-encoded on REST or as `transaction_id` on gRPC, returns `INVALID_ARGUMENT`, as 400 on REST, with exactly one `details` entry whose `field` is `transactionId`, because `Use Cases · COTRN01C` treats a Tran ID of SPACES or LOW-VALUES as empty.
>
> ### 3. Dependencies
>
> - CS-05-003 — shared request-parameter validator and accountId rule this story extends
>
> Risk: The validator's `transactionId` pattern can drift from the pattern `card-servicing-api.yaml` declares, which the unit test closes by asserting that the two are equal.

## 4. Design

- [Transaction detail sequence](../../../../architecture/sequence-transaction-detail.mmd)
- [Data model — transaction_record](../../../../data-model.md#transaction_record)
- [TRD — request path stages](../../../../technical-requirements.md#stages)
- [TRD — edge cases and error mapping](../../../../technical-requirements.md#edge-cases)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
- [Shared error object contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is ≥80% per module. Cases: AC1–AC11 on both protocols, `000000077620001!` and `000000077620001é` accepted, 15 and 17 characters rejected, 16 spaces and 16 NUL characters rejected as empty, the empty stub request logs and zero cache reads through the loopback stubs that `StubHarness` starts, and a contract check that reads the `transactionId` path-parameter pattern from `openapi/card-servicing-api.yaml` on the classpath and asserts it equals the validator's pattern.
- Integration: Not applicable — this story makes no upstream call. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — this story makes no upstream call, and rejected requests sit outside the success-path percentiles; the transaction detail path is measured by CS-04-002. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Applies, through the transaction detail end-to-end step (CS-04-001), whose invalid-identifier call `GET /v1/accounts/00000000001/transactions/12345` with `fixture-token-user0001` returns 400 `INVALID_ARGUMENT` with a `details` entry naming `transactionId` and a non-empty `traceId`, and adds no entry to any stub request log, Auth included. That 5-character identifier over REST is the step's only invalid-input case, so AC1–AC11, with their 15- and 17-character, empty-value, account-number and gRPC cases, stay with this story's unit cases. Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — one shared validation rule for the account number and the transaction identifier, used by both protocol adapters at receipt.

Rationale: one receipt rule set keeps the web and remote procedure checks identical and rejects a malformed transaction identifier before any authentication call, cache read or upstream call.

- Rejected: Option B, per-protocol parameter annotations, duplicates the rule and has no annotation hook on the remote procedure side.

### Shared by both options

- Topology: validation runs inside the service process, with no new component and no network hop.
- Naming: each error detail names the offending field as the web interface description names it, the account number or the transaction identifier.
- Addressing: both identifiers arrive in the web path or in the remote procedure request message.
- Isolation: the checks themselves read no stub, cache or store.
- Fallback: none, since a malformed value fails closed with a bad-request status.
- Provisioning: nothing new is deployed and no setting is added.
- Cut-over: unchanged when real services replace the stubs, because the checks make no upstream call.
- Failures: each rejection yields one standard error object carrying the trace identifier issued at receipt.

### Design Option A — one shared rule at receipt

The shared validator gains a transaction identifier rule of exactly sixteen characters of any kind, with sixteen spaces or sixteen null characters counted as empty, the same rule the interface description declares, and a transaction detail route entry for both protocols. That entry points at a transaction detail rule set, which checks the account number and the transaction identifier together. The receipt stage from the account number story already applies every declared route, so the checks run after the trace identifier is issued and before the access guard.

How it behaves:

- The web adapter reads both identifiers from the path, and the remote procedure adapter reads them from the request message.
- A transaction identifier of fifteen or seventeen characters is rejected, while any character, punctuation and accented letters included, passes.
- An empty transaction identifier in a remote procedure request is rejected as malformed.
- A transaction identifier of sixteen spaces or sixteen null characters counts as empty and is rejected on both protocols.
- A malformed or all-zero account number is rejected with the account view screen text.
- When both values are malformed, one rejection carries two field entries, account number first.
- A rejected request stops before the guard, so no Auth, Config, cache or data call follows.
- A well-formed pair passes unchanged to the guard.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One rule for both protocols (vs one copy per protocol) | one change point for a contract pattern change | one shared table that four endpoints edit |
| 2 | Check at the receipt stage ahead of the guard (vs at parameter binding) | no authentication call on malformed input | the stage order must stay explicit |
| 3 | Every offending field in one rejection (vs stop at the first) | the caller fixes both values in one attempt | both checks finish before any failure |

Effort and risk: one rule added to the shared validator, one rule set and one test, with the risk in the rule drifting from the interface description.

### Design Option B — per-protocol parameter annotations

A framework constraint annotation on the web path parameter checks the transaction identifier when the controller binds the request. The remote procedure interface has no annotation hook, so its method repeats the check by hand at its start. The account number check stays in the shared receipt stage, so the two fields are checked at different moments.

How it differs:

- The web check runs at parameter binding, after the access guard has called the Auth service.
- The remote procedure method carries its own copy of the transaction identifier rule.
- The error handler must turn the framework's binding path into the transaction identifier field name.
- With both values malformed, the receipt stage rejects the account number before the transaction identifier check runs.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Framework constraint on the web path parameter (vs shared rule) | the rule sits beside the endpoint | it runs after the access guard |
| 2 | Hand-written check in the remote procedure method (vs shared rule) | no edit to the shared validator | a second copy of the identifier rule |
| 3 | Account number left in the receipt stage (vs moved to annotations) | no change to the account number story | the two fields fail at different moments |

Effort and risk: no shared edit and two local checks, with the risk in authentication calls on malformed input and in the two copies drifting apart.

### Recommendation

- Option: Option A.
- Why: only one shared receipt rule rejects both malformed identifiers on both protocols before any network call.
- Strongest argument against: a faulty edit to the shared rule table reaches all four endpoints at once.
- Shared items that do not decide the choice: the identifier pattern, the field names in error details, the standard error object and the trace identifier.
- Flip conditions: the framework gains one annotation mechanism that runs ahead of the request filters on both protocols, or the access guard moves behind the controllers.

## Design Validation

### Option A

Settled assumptions:

- The interface description declares exactly sixteen characters of any kind for the transaction identifier, the full character range of the sixteen-character transaction record key.
- The account number rule and the receipt stage come from the account number story and run on both protocols.
- The receipt stage runs ahead of the access guard.
- The legacy transaction view screen checks only for an empty transaction identifier.

Open assumptions:

- Open → whether real transaction identifiers ever need a narrower character set than the record key allows, settled by the Requirements Analysis author.
- Open → whether a web request with an empty transaction identifier can reach this route, settled by the route tests of the endpoint exposure story.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a well-formed pair passes the receipt stage to the guard |
| AC2 | Holds | the sixteen-character rule rejects a fifteen-character value |
| AC3 | Holds | the sixteen-character rule rejects a seventeen-character value |
| AC4 | Holds | the any-character rule passes the exclamation mark |
| AC5 | Holds | the shared account number rule rejects ten digits with the screen text |
| AC6 | Holds | the shared zero rule rejects the all-zero account number |
| AC7 | Holds | one rule set reads both protocol inputs |
| AC8 | Holds | the receipt stage ends the request before any network call or cache read |
| AC9 | Holds | every rejection passes through the standard error object with the receipt trace identifier |
| AC10 | Holds | the rule set checks both fields before raising one failure |
| AC11 | Holds | the rule set rejects an empty value, sixteen spaces and sixteen null characters |

### Option B

Settled assumptions:

- The interface description declares exactly sixteen characters of any kind for the transaction identifier, the full character range of the sixteen-character transaction record key.
- The account number rule and the receipt stage come from the account number story and run on both protocols.
- Parameter binding runs after every request filter, the access guard included.
- The remote procedure interface offers no annotation hook for request fields.

Open assumptions:

- Open → whether real transaction identifiers ever need a narrower character set than the record key allows, settled by the Requirements Analysis author.
- Open → whether the framework's binding path maps to the contract field name, settled by the error handler tests in the first build.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a well-formed pair passes the guard and then the binding check |
| AC2 | Conditional | the error handler must map the binding path to the field name |
| AC3 | Conditional | the error handler must map the binding path to the field name |
| AC4 | Holds | the binding check passes the exclamation mark like any other character |
| AC5 | Holds | the receipt stage still checks the account number |
| AC6 | Holds | the receipt stage still applies the zero rule |
| AC7 | At risk | the hand-written method check can drift from the web constraint |
| AC8 | Breaks | a malformed transaction identifier reaches its check only after the Auth service call |
| AC9 | Holds | the standard error object still carries the receipt trace identifier |
| AC10 | Breaks | the receipt stage rejects the account number before the second check runs |
| AC11 | Conditional | both local checks must treat an empty value, sixteen spaces and sixteen null characters as malformed |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-05-003 |
| Status | TBD |
| Estimated Files Touched | 3 |
| Issue Type | Story |
| Epic Name | Transaction Detail Endpoint |
| Epic Link | CS-08 |
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
