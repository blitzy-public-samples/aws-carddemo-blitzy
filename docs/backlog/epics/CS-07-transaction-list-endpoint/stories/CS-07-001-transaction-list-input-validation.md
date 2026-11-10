# CS-07-001: Transaction list input validation

Parent epic: [CS-07 Transaction List Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want invalid page sizes, tokens and filters rejected before any lookup, so that every page I see continues the list I asked for.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: the transaction-list rule set applied at Stage 0, at receipt and before any network call or cache read, so that a malformed account number, type code, category code, page size or page token ends in 400 on the REST route `GET /v1/accounts/{accountId}/transactions` and in `INVALID_ARGUMENT` on the gRPC method `CardServicingService.ListTransactions`, and a request that passes carries its effective page size. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/validation/RequestParameterValidator.java`, the shared validator CS-05-003 creates, is edited here.
>   - It gains the `categoryCode` rule `^[0-9]{4}$`, the `pageSize` rule of an integer from 1 to 100, and the `pageToken` shape rule `^[A-Za-z0-9_-]*$` with at most 166 characters. Each is copied exactly from the query parameters of `card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml` and repeated in the field comments of `ListTransactionsRequest` in `card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto`. The `categoryCode` length follows `Data Layouts · CVTRA05Y · TRAN-CAT-CD` 9(04), and every pattern applies to the decoded REST value.
>   - It reuses, unchanged, its `accountId` rule, `^[0-9]{11}$` with the all-zero value rejected following `Use Cases · COACTVWC` (CS-05-003), and its `typeCode` rule `^[\s\S]{2}$`, added by CS-06-001 earlier in W2.
>   - Its per-route rule table gains the transaction-list route, REST and gRPC, which points at the rule set below.
> - `card-servicing-service/src/main/java/com/example/cardservicing/drill/TransactionListRequestValidator.java` is created as the transaction-list rule set.
>   - It reads `accountId` from the REST path variable and `typeCode`, `categoryCode`, `pageSize` and `pageToken` from the REST query, or `account_id`, `type_code`, `category_code`, `page_size` and `page_token` from `ListTransactionsRequest`.
>   - Filters: an absent filter, meaning no REST query parameter or an unset proto3 `optional` `type_code` or `category_code`, returns every type or category and is not checked. A present but empty filter fails its rule.
>   - Page size: a REST `pageSize` must consist of decimal digits only, so a sign, a decimal point, a letter or an empty value fails the rule. A gRPC `page_size` that is set, `0` included, is checked as given. The effective page size is the request value when one is given, otherwise the `default_page_size` of the request's configuration capture, 20 under the built-in defaults. A value above the capture's `max_page_size` fails the rule.
>   - Configuration capture: it reads `default_page_size` and `max_page_size` only from the `UpstreamSettings.Capture` that the request's `RequestDeadline` (CS-11-003) took once from `ConfigSnapshot` (CS-11-006) at receipt, through `settings()`, so the maximum check here and the list's default page size come from one published record. It makes no network call, sends no Redis command and launches no snapshot refresh.
>   - Page token: an omitted `pageToken`, a REST `pageToken=` and an empty gRPC `page_token` each mean the first page and are never decoded. A non-empty token is first checked against the shape rule, then decoded through `PageTokenCodec` (CS-10-003) with the presented `accountId`, `typeCode` and `categoryCode`, which checks the version `v` = 1 and the fingerprint over the account and both filters. The token is decoded only when the account number and both filters pass, because the fingerprint is computed over them. A codec failure becomes the `pageToken` entry of this rule set's failure, and a token that passes is carried on exactly as received.
>   - Check order: the syntactic checks run first (account pattern and all-zero rule, type and category patterns, page size integer 1–100, token shape, decode, version and fingerprint), then the snapshot-dependent maximum. The rule set checks every field, then raises one `INVALID_ARGUMENT` typed failure through the standard error object of CS-05-002, with one `details` entry per failing field in the order `accountId`, `typeCode`, `categoryCode`, `pageSize`, `pageToken`, and the `message` of the first failing field.
>   - Rejection texts: `message` "Transaction category code must be exactly 4 digits" with `details.description` "must be exactly 4 digits" for `categoryCode`. `message` "Page size must be an integer from 1 to N" with `details.description` "must be an integer from 1 to N" for `pageSize`, where N is the capture's `max_page_size`, 100 under the built-in defaults. `message` "Page token is invalid or does not match this request" with `details.description` "must be a token returned for the same account and filters" for every `pageToken` failure alike, the contract's 400 example, so a response never states which token check failed. The `accountId` and `typeCode` rules keep their own texts. The `details.field` strings are `categoryCode`, `pageSize` and `pageToken` on both protocols.
>   - Result: a request that passes yields the nested record `TransactionListRequestValidator.ValidatedListRequest`, holding the account number, the filters as given, the effective page size and the page token exactly as received. The endpoint adapter of CS-07-003 obtains it from this rule set with the same capture and hands it to the retrieval of CS-07-002.
>     > SCOPE-QUESTION (Resolved): The Stage 0 hook (CS-05-003, extended by CS-05-006) only starts and attaches the request deadline, applies the rule set and raises its failure, so nothing above keeps the record until the adapter runs, and "obtains it from this rule set" can be read as a second validation. The record is also not an input of the retrieval: CS-07-002 declares plain values, because this story is not its prerequisite. Stage 0 therefore keeps no copy of the record. Its result is carried by the request itself, the REST path variable and query or the gRPC `ListTransactionsRequest` message, which no later stage changes, together with the `UpstreamSettings.Capture` that the request's `RequestDeadline` holds for the life of the request (CS-11-003), the capture Stage 0 used. Beside its Stage 0 check, the rule set publishes one non-checking accessor, `accepted`, in the two forms of its Stage 0 read: `accepted(accountId, typeCode, categoryCode, pageSize, pageToken, capture)` over the REST path variable and query values, an absent parameter passed as absent, and `accepted(ListTransactionsRequest, capture)` over the gRPC message and its field presence. It builds `ValidatedListRequest` from those values with the effective page size rule alone: the account number, each filter as given or absent, the effective page size, and the page token as received, the empty string for a first page. It runs no pattern, bound, shape, decode, version or fingerprint check and makes no `PageTokenCodec` call. It is called only after the Stage 0 hook has passed the request, which the route order of Stage 0 hook, account access guard and adapter guarantees. The rule set takes its `PageTokenCodec` through its constructor, so a test can supply a counting codec. The CS-07-003 adapters call `accepted` once, after the guard allows access, and never apply the rule set's checks a second time. They pass the record's values one by one to the inputs CS-07-002 declares: `accountId`; `typeCode` and `categoryCode`, an absent filter staying absent; the effective page size; and `pageToken` byte for byte, the empty string for a first page. The record itself is not an input of CS-07-002, so no dependency edge and no file count changes. `TransactionListRequestValidatorTest` checks, for each request that passes in AC1, AC2, AC4 and AC5, on its REST and its gRPC form, that the record `accepted` returns equals the Stage 0 record field for field and that `accepted` makes zero calls on a counting codec.
>   - The Stage 0 hook of CS-05-003 applies the rule set, because that hook applies the validator to every route the validator declares. It therefore runs after the `traceId` is issued and ahead of the account access guard on both protocols, and the hook file is unchanged.
> - Test: `card-servicing-service/src/test/java/com/example/cardservicing/drill/TransactionListRequestValidatorTest.java`.
> - That makes 3 files: one edited and two created.
> - Source note: `Use Cases · COTRN00C` fills ten rows per page and pages forward with PF8 and back with PF7, so the UI may request `pageSize=10`. Its Tran ID start-key search and its browse of all transactions in Tran ID order are not carried, because the Stub Contract Baseline governs keys and ordering: the list is per account, newest first, and its page size comes from configuration.
>
> In scope:
>
> - The transaction-list rule set over its five parameters, with the `categoryCode`, `pageSize` and `pageToken` rules and the transaction-list route entry added to the shared validator.
> - The effective page size, from the request or the configuration capture.
> - The page token shape check, decode, version check and fingerprint check at receipt.
> - The 400 with one `details` entry per failing field, on REST and gRPC.
> - The unit tests.
>
> Not in this story:
>
> - Authentication and authorization by the account access guard (CS-12-003).
> - The transaction read, the token pass-through and the empty result for a well-formed but unknown filter (CS-07-002).
> - The REST controller and gRPC method exposure (CS-07-003).
> - The token format, its encoding and its fingerprint key (CS-10-003).
> - The snapshot refresh and the checks on configured values (CS-11-006).
> - The `accountId` and `typeCode` rules themselves and the Stage 0 hook (CS-05-003, CS-06-001).
>
> ### 2. Acceptance Criteria
>
> - AC1: With the configuration capture at the built-in defaults, `accountId` `00000000001` with `pageSize` `1`, `10` and `100` each passes Stage 0 on REST (`GET /v1/accounts/00000000001/transactions?pageSize=10`) and on gRPC (`ListTransactions` with `page_size` set), with an effective page size equal to the requested value, and proceeds to authentication: the Auth stub request log shows one `ValidateToken` call for each request.
> - AC2: For `accountId` `00000000001` with no REST `pageSize` and with gRPC `page_size` unset, the effective page size is 20 with the capture at the built-in defaults, and 30 with a capture holding `default_page_size` 30 and `max_page_size` 100.
> - AC3: With the capture at the built-in defaults and `accountId` `00000000001`, `pageSize` `0`, `-1`, `101`, `abc`, `1.5` and the empty value (`pageSize=`) on REST, and `page_size` set to `0`, `-1` and `101` on gRPC, each return 400 (gRPC status `INVALID_ARGUMENT`) with `code` `INVALID_ARGUMENT`, `message` "Page size must be an integer from 1 to 100" and exactly one `details` entry whose `field` is `pageSize`.
> - AC4: With a capture holding `default_page_size` 20 and `max_page_size` 50, `pageSize` `60` for `accountId` `00000000001` returns 400 with `code` `INVALID_ARGUMENT`, `message` "Page size must be an integer from 1 to 50" and exactly one `details` entry whose `field` is `pageSize`, and `pageSize` `50` passes Stage 0 with an effective page size of 50.
> - AC5: For `accountId` `00000000001` with `typeCode` `01` and `categoryCode` `0001`, each of these passes Stage 0 on REST and on gRPC, and the validated request carries the token exactly as received: a token that `PageTokenCodec` minted for the cursor (`2022-06-10T19:27:53.000000Z`, `0000000755736377`) with that account and those filters, replayed with `pageSize` `2` and again with `pageSize` `5`, because the fingerprint does not bind the page size; an omitted `pageToken`; a REST `pageToken=`; and a gRPC `page_token` set to the empty string.
> - AC6: Each of these, presented with `accountId` `00000000001`, `typeCode` `01` and `categoryCode` `0001` unless another value is named, returns 400 with `code` `INVALID_ARGUMENT`, `message` "Page token is invalid or does not match this request" and exactly one `details` entry whose `field` is `pageToken`, on REST and on gRPC: the AC5 token with one character altered; a token minted for `00000000002` with the same filters; the AC5 token presented with `typeCode` `03`; a token minted for `00000000001` with no filters; a well-formed token carrying version 2; a value of 167 characters from the URL-safe alphabet; the AC5 token with the padding character `=` appended; and `***`.
> - AC7: Each of these, with `accountId` `00000000001` unless an `accountId` is named, returns 400 with `code` `INVALID_ARGUMENT` and exactly one `details` entry naming the field shown, on REST and on gRPC: `categoryCode` `001` (three digits), `00001` (five digits), `00A1` (a letter) and the empty value, each naming `categoryCode` with `message` "Transaction category code must be exactly 4 digits"; `typeCode` `1` (one character) and `001` (three characters), each naming `typeCode`; and `accountId` `00000000000` (all zero) and `0000000001` (10 digits), each naming `accountId` with `message` "Account number must be a non zero 11 digit number".
> - AC8: With a stale configuration snapshot held, its values applied more than 300 s earlier on the test clock, every request rejected in AC3, AC4, AC6, AC7 and AC10 leaves the request logs of the Config, Auth, Card Data, Transaction Activity and Reference Data stubs that `StubHarness.allRegistered()` starts with zero entries, the cache records zero reads, and the snapshot submits zero refreshes.
> - AC9: Every rejection in AC3, AC4, AC6, AC7 and AC10 carries the `traceId` generated at receipt, equal to the `traceId` on that request's completion log line. Every REST 400 body validates against `ErrorObject` in `card-servicing-api.yaml`, and every gRPC rejection carries exactly one packed `cardservicing.common.v1.ErrorObject` in the `google.rpc.Status` details, with the same `code`, `message` and `details` as on REST.
> - AC10: `accountId` `0000000001` with `categoryCode` `001`, `pageSize` `0` and the AC5 token returns 400 with `code` `INVALID_ARGUMENT`, `message` "Account number must be a non zero 11 digit number" and exactly three `details` entries, `accountId`, `categoryCode` and `pageSize` in that order, with no `pageToken` entry, on REST and on gRPC.
>
> ### 3. Dependencies
>
> - CS-05-003 — the shared request-parameter validator with the account identifier rule.
> - CS-10-003 — page-token decoding and the fingerprint check.
> - CS-11-006 — the in-memory configuration snapshot supplying the default and maximum page size.
>
> Risk: the type code rule comes from the category balance input validation story, which precedes this one in wave order but is not a declared prerequisite, so a reordering inside the wave would leave this rule set without its type code check.

## 4. Design

- [Transaction list sequence](../../../../architecture/sequence-transaction-list.mmd)
- [Data model — transaction_record](../../../../data-model.md#transaction_record)
- [TRD — request path stages](../../../../technical-requirements.md#stages)
- [TRD — configuration snapshot](../../../../technical-requirements.md#configuration-snapshot)
- [TRD — edge cases and error mapping](../../../../technical-requirements.md#edge-cases)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
- [Shared error object contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is ≥80% per module. Cases: AC1–AC10 on REST and gRPC; the built-in capture and test captures holding 30 and 100 and 20 and 50, built as `UpstreamSettings.Capture` values; tokens minted and altered through `PageTokenCodec`; the non-checking accessor `accepted`, in its REST and gRPC forms, for each request that passes in AC1, AC2, AC4 and AC5, returning a record equal field for field to the Stage 0 record and making zero calls on a counting `PageTokenCodec` passed to the rule set's constructor; the empty stub request logs, zero cache reads and zero refreshes through the loopback stubs that `StubHarness.allRegistered()` starts; and a contract check that reads the `categoryCode` pattern, the `pageSize` bounds and the `pageToken` pattern and maximum length from `openapi/card-servicing-api.yaml` on the classpath and asserts that each equals the validator's rule.
- Integration: Not applicable — this story adds no external client and makes no upstream call. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — validation only, with no retrieval, and rejected requests sit outside the success-path percentiles; the transaction list path is measured by CS-03-003. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — the transaction list end-to-end steps CS-03-001 and CS-03-002 exercise this rule set with valid input and replayed page tokens. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — page bounds from the in-memory configuration snapshot.

Rationale: one receipt rule set stops bad list requests before any outside call, and configured bounds take a new maximum with no deploy.

- Rejected: Option B, fixed interface bounds, ignores the configured maximum.

### Shared by both options

- Topology: the checks run in the service at receipt on both interfaces, ahead of the access guard.
- Naming: error details use the interface description's parameter names.
- Addressing: one account path per request, with the filters, page size and page token in the query or remote procedure message.
- Isolation: no check calls the network or reads the cache.
- Fallback: built-in defaults of twenty and one hundred apply when no snapshot is held.
- Provisioning: no new infrastructure or setting.
- Cut-over: the checks ship with the service with no data change, and replacing the stubs changes nothing.
- Failures: a bad-request status with one detail per failing field and the receipt trace identifier.

### Design Option A — configured page bounds

The shared validator gains category code, page size and token shape rules from the interface description and a route entry for a new transaction list rule set. That rule set reads the page bounds from the configuration captured at receipt.

How it behaves:

- A missing page size takes the captured default, and a size outside one to one hundred or above the captured maximum is rejected.
- An empty or missing token means the first page, and a non-empty token is decoded only after the account number and both filters pass.
- After the guard allows the request, the endpoint adapters read its accepted values once, with no second check, and pass them to the retrieval as separate values.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Configured page bounds captured at receipt (vs fixed bounds) | a configured maximum applies with no deploy | a stale snapshot may admit a page size over a newer maximum |
| 2 | Token fingerprint checked at receipt (vs at the transaction source) | a replayed token reaches no upstream call | service and source must both read one token format |
| 3 | Rules in the shared validator (vs endpoint-local rules) | one change point per rule | every endpoint edits one shared rule table |
| 4 | Token decoded after a valid account number and filters (vs always) | each rejection names the field at fault | a mismatched token goes unreported beside a bad account number or filter |

Effort and risk: three validator rules, a route entry, a rule set and a test class, risking a stale snapshot maximum.

### Design Option B — fixed page bounds

Option B keeps the Option A checks but fixes the page bounds at the interface values of twenty and one hundred. It never reads the configuration snapshot.

How it differs:

- A configured default or maximum never applies, so pages above a lower configured maximum reach the transaction source.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Fixed bounds from the interface description (vs configured bounds) | no tie to the configuration snapshot | a configured maximum never limits a page |
| 2 | Fixed default of twenty (vs configured default) | the default never changes between requests | a configured default never applies |

Effort and risk: two constants replace the configuration read, risking pages above the configured maximum.

### Recommendation

- Option: Option A.
- Why: only configured bounds let the configuration owner tighten the page size with no deploy.
- Strongest argument against: a stale snapshot may admit a page size over a newer maximum.
- Shared items that do not decide the choice: the receipt checks, the token check and the error details.
- Flip condition: page bounds never vary by configuration.

## Design Validation

### Option A

Settled assumptions:

- The interface description sets the three new parameter rules.
- The configuration client story accepts a maximum of one to one hundred and a default no larger.
- The page-token codec binds a token to the account and filters, not the page size.
- The balance summary endpoint story makes the receipt stage start the request deadline with its one configuration capture before this rule set runs.
- The account number and type code rules come from the shared validator and category balance input validation stories.

Open assumptions:

- Open → whether the UI shows the server message, settled by the deferred UI run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | each page size within the built-in bounds passes unchanged |
| AC2 | Holds | the captured default applies |
| AC3 | Holds | the integer rule rejects each value |
| AC4 | Holds | the captured maximum of fifty applies |
| AC5 | Holds | the codec accepts matching replays |
| AC6 | Holds | shape and codec checks reject each token |
| AC7 | Holds | each field rule rejects its malformed value |
| AC8 | Holds | only the in-memory capture is read |
| AC9 | Holds | the standard error object carries the receipt trace identifier |
| AC10 | Holds | the rule set checks every field before raising one failure |

### Option B

Settled assumptions:

- The Option A assumptions other than the configuration client limits hold.

Open assumptions:

- Open → whether the configuration owner ever sets other page bounds, settled by the Requirements Analysis author.
- Open → whether the UI shows the server message, settled by the deferred UI run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | fixed bounds equal the built-in defaults |
| AC2 | Breaks | the fixed twenty overrides a held default of thirty |
| AC3 | Holds | the same integer rule applies |
| AC4 | Breaks | a page size of sixty passes the fixed maximum |
| AC5 | Holds | the same codec check applies |
| AC6 | Holds | the same token checks apply |
| AC7 | Holds | the same field rules apply |
| AC8 | Holds | the rule set reads no configuration |
| AC9 | Holds | the same error object applies |
| AC10 | Holds | the same rule set checks every field |

## Metadata

| Field | Value |
|---|---|
| Build wave | W2 |
| Depends on | CS-05-003, CS-10-003, CS-11-006 |
| Status | TBD |
| Estimated Files Touched | 3 |
| Issue Type | Story |
| Epic Name | Transaction List Endpoint |
| Epic Link | CS-07 |
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

