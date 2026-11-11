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

Recommended: Option A — one shared validation rule for both identifiers, applied at receipt on both protocols.

Rationale: one receipt rule set checks both protocols identically and rejects malformed input before authentication.

- Rejected: Option B, per-protocol parameter annotations, duplicates the rule and checks the web value after authentication.

### Shared by both options

- Topology: validation runs inside the service process, with no new component or network hop.
- Naming: error details name offending fields as the interface description does.
- Addressing: both identifiers arrive in the web path or the remote procedure request.
- Isolation: the checks read no stub, cache or store.
- Fallback: none, since a malformed value fails closed.
- Provisioning: nothing new is deployed or configured.
- Cut-over: unchanged when real services replace the stubs.
- Failures: each rejection yields one standard error object with the receipt trace identifier.

### Design Option A — one shared rule at receipt

The shared validator gains a transaction identifier rule of exactly sixteen characters of any kind and a route entry for both protocols. That entry points at a rule set checking both identifiers, which the receipt stage applies before the access guard.

How it behaves:

- Transaction identifiers of fifteen or seventeen characters fail, and empty, all-space or all-null values fail as empty.
- Two malformed values yield one rejection with two field entries, account number first.
- A rejected request makes no Auth, Config, cache or data call.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One rule for both protocols (vs per-protocol copies) | one change point | four endpoints share one rule table |
| 2 | Check at receipt (vs at parameter binding) | no authentication call on bad input | the stage order must stay explicit |
| 3 | All offending fields in one rejection (vs first only) | one retry fixes both values | both checks always run |

Effort and risk: one rule, one rule set and one test, with the risk of drift from the interface description.

### Design Option B — per-protocol parameter annotations

A framework constraint on the web path parameter checks the transaction identifier at request binding. The remote procedure method, lacking an annotation hook, repeats the check by hand.

How it differs:

- The web check runs after the access guard calls the Auth service.
- The error handler maps the binding path to the field name.
- With both values malformed, only the account number is reported.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Web path constraint (vs shared rule) | the rule sits beside the endpoint | it runs after the access guard |
| 2 | Hand-written remote procedure check (vs shared rule) | no shared validator edit | a second copy of the rule |
| 3 | Account number left at receipt (vs annotations) | no change to the account number story | the fields fail at different moments |

Effort and risk: two local checks, with the risk of authenticating malformed input and of copy drift.

### Recommendation

- Option: Option A.
- Why: only a shared receipt rule rejects both identifiers on both protocols before any network call.
- Strongest argument against: a faulty edit to the shared rule reaches four endpoints at once.
- Shared items that do not decide the choice: the identifier pattern, field names, error object and trace identifier.
- Flip conditions: one annotation mechanism ahead of the request filters on both protocols, or an access guard behind the controllers.

## Design Validation

### Option A

Settled assumptions:

- The interface description allows any sixteen characters, the full range of the record key.
- The receipt stage and account number rule come from the account number story and run on both protocols ahead of the guard.
- The legacy transaction view screen checks only for an empty transaction identifier.

Open assumptions:

- Open → whether real transaction identifiers need a narrower character set, settled by the Requirements Analysis author.
- Open → whether an empty web transaction identifier can reach this route, settled by the exposure story's route tests.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a well-formed pair reaches the guard |
| AC2 | Holds | the length rule rejects fifteen characters |
| AC3 | Holds | the length rule rejects seventeen characters |
| AC4 | Holds | the any-character rule passes the exclamation mark |
| AC5 | Holds | the shared account number rule returns the screen text |
| AC6 | Holds | the shared zero rule rejects all zeros |
| AC7 | Holds | one rule set reads both protocols |
| AC8 | Holds | rejection precedes every network call and cache read |
| AC9 | Holds | the error object carries the receipt trace identifier |
| AC10 | Holds | one failure lists both fields |
| AC11 | Holds | the empty rule rejects empty, all-space and all-null values |

### Option B

Settled assumptions:

- The interface description allows any sixteen characters, and the receipt stage checks account numbers on both protocols.
- Parameter binding runs after every request filter, the access guard included.
- The remote procedure interface offers no annotation hook.

Open assumptions:

- Open → whether real transaction identifiers need a narrower character set, settled by the Requirements Analysis author.
- Open → whether the binding path maps to the field name, settled by the error handler tests.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a well-formed pair passes both checks |
| AC2 | Conditional | the binding path must map to the field name |
| AC3 | Conditional | the binding path must map to the field name |
| AC4 | Holds | the binding check passes the exclamation mark |
| AC5 | Holds | the receipt stage still checks the account number |
| AC6 | Holds | the receipt stage still applies the zero rule |
| AC7 | At risk | the two rule copies can drift apart |
| AC8 | Breaks | the identifier check follows the Auth service call |
| AC9 | Holds | the error object still carries the trace identifier |
| AC10 | Breaks | the account number rejection precedes the identifier check |
| AC11 | Conditional | both copies must reject empty, all-space and all-null values |

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
