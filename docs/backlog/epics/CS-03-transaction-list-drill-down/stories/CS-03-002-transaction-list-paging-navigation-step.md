# CS-03-002: Transaction list paging navigation step

Parent epic: [CS-03 Transaction List Drill-Down](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want to page forward and back through my transactions, so that I can browse a long history as the mainframe screen allowed.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Capability: the paging walk of the one ordered end-to-end flow that stands in for the deferred Card Servicing UI, run after step 3, the transaction list step (CS-03-001). Through the end-to-end client of CS-01-002 it pages the transaction list of the Compose stack `tests/docker-compose.yml` (CS-01-001) with `GET /v1/accounts/{accountId}/transactions` for account `00000000001` at `pageSize=2`, so the six sample transactions of the account fill three pages. Forward paging follows `nextPageToken`; backward paging replays a token the client kept from an earlier page, mirroring the F8=Forward and F7=Backward keys of Use Cases · COTRN00C without a server change; an absent `nextPageToken` ends the list. The 10-row page of Use Cases · COTRN00C, its Tran ID start-key search and its browse of all transactions are not carried, because the Stub Contract Baseline governs operations, keys and ordering; the UI may request `pageSize=10` to show the screen's page length, which AC7 checks. Expected values come from the CardDemo sample records: Data Layouts · CVTRA06Y for the six daily transactions whose card number Data Layouts · CVACT03Y links to account `00000000001`, which all share the original timestamp `2022-06-10 19:27:53.000000` and a blank processed timestamp that the fixture load sets to the original timestamp, so the newest-first order falls to `transactionId` descending. `./gradlew :card-servicing-service:e2eTest` runs the flow against the running stack. Target path:
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/e2e/CardServicingFlowE2eTest.java` (edit; created by CS-01-004):
>   - Paging step: the test method at order 4 of the `e2e`-tagged JUnit 5 flow class, with orders 1–3 left to steps 1 to 3 and the highest order to the transaction detail step (CS-04-001), which closes the flow, so it runs after step 3 and before the transaction detail step in the same class instance, with the client that the CS-01-002 suite base injects. The walk sits in the flow class and not in a class of its own, because JUnit fixes no order between test classes. It never re-implements steps 1 to 4, the client or the cache emptying before step 1.
>   - Precondition: at its start the step calls step 3's hand-off accessor, which fails the calling step with a message naming step 3 when step 3 did not pass, so the walk never runs behind a failed list step.
>   - Token stack: the step keeps each `nextPageToken` it receives on a stack local to this one walk. Forward sends the newest kept token as `pageToken`; backward drops it and replays the token that opened the previous page, and the first page is the request with no `pageToken`. A page without `nextPageToken` ends forward paging, and the client sends no further forward request.
>   - Call order in the step, one call at a time through the client, each with the client's default token `fixture-token-user0001` of `USER0001`: `GET /v1/accounts/00000000001/transactions?pageSize=2&pageToken=not-a-token`, then `GET /v1/accounts/00000000011/transactions?pageSize=2`, then `GET /v1/accounts/00000000001/transactions?pageSize=2` (page 1), then the same request with `pageToken=<page-1 token>` (page 2), then with `pageToken=<page-2 token>` (page 3), then again with `pageToken=<page-1 token>` (back to page 2), then with no `pageToken` (back to page 1), then `GET /v1/accounts/00000000001/transactions?pageSize=2&typeCode=01&pageToken=<page-1 token>`, then `GET /v1/accounts/00000000001/transactions?pageSize=10`. The malformed-token and denied calls come first because neither reads the cache or launches the background Config refresh, so no refresh started by an allowed call of this step can land in their request-log windows; the filter-mismatch call follows the walk because it needs the kept page-1 token.
> - Together this is the 1 file touched.
>
> In scope:
>
> - The paging walk at `pageSize=2` over the six sample transactions of account `00000000001`: forward by `nextPageToken`, backward by replaying kept tokens, and the end of the list on an absent `nextPageToken`.
> - The `pageSize=10` call at the Use Cases · COTRN00C page length.
> - The filter-mismatch and malformed page-token rejections and the access-denial check, with their error objects.
> - The stub request-log counts, `cutoffMs` and client-measured latency checks for each call of the step.
>
> Not in this story:
>
> - Steps 1 to 4 of the flow (CS-01-004, CS-02-001, CS-03-001, CS-04-001).
> - The gRPC empty-string `next_page_token` on the last page (CS-07-003).
> - Page-token encoding and fingerprint rules (CS-10-003).
> - Page-size and page-token validation logic (CS-07-001).
> - Token pass-through in the service (CS-07-002).
> - Filtered paging under volume (CS-03-003).
> - The end-to-end client and the Compose stack (CS-01-002, CS-01-001).
> - Injected-failure cases (CS-07-002 and the integration suites).
> - UI code.
>
> ### 2. Acceptance Criteria
>
> - AC1: `GET /v1/accounts/00000000001/transactions?pageSize=2` with `fixture-token-user0001` returns 200 with `accountId` `"00000000001"`, exactly two `transactions` elements, `transactionId` `0000000776200014` then `0000000755736377`, equal field for field to the two elements of the `pageSizeTwo` 200 example of `ListTransactions` in `card-servicing-api.yaml`, and a non-empty `nextPageToken` matching `^[A-Za-z0-9_-]+$`, which the client keeps as the page-1 token.
> - AC2: The same request with `pageToken` set to the page-1 token returns 200 with exactly two `transactions` elements, `0000000624335286` then `0000000555363230`, each equal field for field to the element with the same `transactionId` in the `firstPage` 200 example, and a non-empty `nextPageToken`, which the client keeps as the page-2 token.
> - AC3: The same request with `pageToken` set to the page-2 token returns 200 with exactly two `transactions` elements, `0000000532120892` then `0000000503557384`, each equal field for field to the element with the same `transactionId` in the `firstPage` example, and no `nextPageToken` property; the client sends no further forward request. Across AC1–AC3 the three pages hold each of the six `firstPage` elements exactly once, in the `firstPage` order.
> - AC4: Backward by replay, with no request parameter or response field beyond the baseline contract: after the AC3 page, resending the AC2 request with the kept page-1 token returns 200 with `transactions` identical in content and order to AC2 and a non-empty `nextPageToken`, and then the AC1 request with no `pageToken` returns 200 with `transactions` identical in content and order to AC1 and a non-empty `nextPageToken`.
> - AC5: `GET /v1/accounts/00000000001/transactions?pageSize=2&typeCode=01&pageToken=<page-1 token>`, the page-1 token sent with a filter its fingerprint was not issued for, returns 400 with `code` `INVALID_ARGUMENT`, `message` `Page token is invalid or does not match this request`, exactly one `details` entry, whose `field` is `pageToken`, and a non-empty `traceId`. The call's entries are empty on all five stubs, because validation precedes every upstream call.
> - AC6: `GET /v1/accounts/00000000001/transactions?pageSize=2&pageToken=not-a-token` returns 400 with `code` `INVALID_ARGUMENT`, `message` `Page token is invalid or does not match this request`, exactly one `details` entry, whose `field` is `pageToken`, and a non-empty `traceId`. The call's entries are empty on all five stubs.
> - AC7: `GET /v1/accounts/00000000001/transactions?pageSize=10`, the Use Cases · COTRN00C page length, returns 200 with exactly six `transactions` elements in the AC1–AC3 order and no `nextPageToken` property.
> - AC8: `GET /v1/accounts/00000000011/transactions?pageSize=2` with `fixture-token-user0001` returns 403 with `code` `PERMISSION_DENIED` and a non-empty `traceId`, because `USER0001` is entitled to `00000000001`–`00000000010` among the sample accounts and `00000000011` is a sample account outside that range. The call's entries show only `ValidateToken` and `AuthorizeAccountAccess` on the Auth stub and no entry on the Card Data, Transaction Activity, Reference Data or Config stub, so no account data is read from upstream before access is allowed; that no cache read precedes the access decision either is proven by the guard ordering of CS-12-003.
> - AC9: The step runs under the stack defaults of the upstream call policy: each attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget, and at most two retries (`UPSTREAM_MAX_RETRIES`) follow, after 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, each starting only when its backoff plus a full 300 ms attempt fits before the 900 ms cut-off. With failure injection off (`STUB_LATENCY_MS` `0` and `STUB_FAILURE_RATE` `0`, the Compose defaults), the entries of each of the six allowed calls show `cardservicing.auth.v1.AuthService/ValidateToken` and `cardservicing.auth.v1.AuthService/AuthorizeAccountAccess` on the Auth stub, each exactly once with outcome `OK`, at most one `cardservicing.transactionactivity.v1.TransactionActivityService/ListTransactions` on the Transaction Activity stub, with outcome `OK`, and no entry on the Card Data or Reference Data stub. The AC1, AC2, AC3 and AC7 calls each show exactly one `ListTransactions`, because the cache emptying before step 1 leaves no page cached at page size 2 or 10, and each AC4 replay shows at most one, none when the page cache still holds that page within its fresh TTL. Every stub request-log entry of the step records a `durationMs` of at most 300, and the client reports no retried call for any of the nine calls.
> - AC10: Each of the nine calls of this step receives its full answer below 1,000 ms of client-measured latency, and the completion log line the client matches to it shows `cutoffMs` ≤ 900, the 900 ms deadline (`REQUEST_DEADLINE_MS`) measured from receipt to the cut-off.
> - AC11: The step fails when any 200 answer of the step carries a value other than `FRESH` in the `X-Data-Status` header or the body's `dataStatus`, or when any call of the step returns 503 or 504. The degraded and failed outcomes of each upstream call this endpoint makes are proven under injection by CS-07-002 and the integration suites CS-10-007, CS-12-005 and CS-11-007, not by this step: Auth `ValidateToken` or `AuthorizeAccountAccess` failing closed with 401, 503 or 504 and no transaction data read; Transaction Activity `ListTransactions` on any page returning 503 after retries or 504 at the deadline with no `transactions` and no `nextPageToken`, never a stale page, while every token kept from earlier pages stays usable because a token is self-contained (CS-10-003); Config `GetConfig` failing with the pages and their `X-Data-Status` unaffected.
>
> ### 3. Dependencies
>
> - CS-03-001 — the transaction list step opens the list this step pages.
>
> Risk: a cached replayed page can hide a token-decoding regression, because a replay inside the page cache's fresh TTL reaches no stub; a change to the sample transactions or cross-reference of account `00000000001` changes the AC1–AC4 and AC7 values and fails the step.

## 4. Design

- [Sequence — transaction list](../../../../architecture/sequence-transaction-list.mmd)
- [Data model — transaction_record](../../../../data-model.md#transaction_record)
- [Data model — denormalization of transaction account (keyset page order)](../../../../data-model.md#denormalization-of-transaction-account)
- [Data model — fixture rules](../../../../data-model.md#fixture-rules)
- [TRD — Card Servicing UI paging](../../../../technical-requirements.md#card-servicing-ui)
- [TRD — Request path stages (page-token checks at receipt)](../../../../technical-requirements.md#stages)
- [TRD — Edge cases and error mapping](../../../../technical-requirements.md#edge-cases)
- [TRD — Deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — Resilience](../../../../technical-requirements.md#resilience)
- [TRD — Test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- Contracts:
  - [card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)
  - [card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
  - [common.proto](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)
  - [auth-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/auth-service.yaml) (fixture tokens)
  - [auth_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto) (fixture tokens)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Not applicable — the story adds an end-to-end step only, with no production code and no untagged case; page-token codec cases stay with CS-10-003. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass, with ≥80% line coverage per module.
- Integration — Not applicable — the story changes no client or stub, and the injected upstream outcomes are proven by the suites named in AC11. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — CS-03-003 measures this endpoint, filtered paging included. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end — Applies — this story is the paging walk of the four-step flow for account `00000000001`, run after step 3 and covering AC1–AC11 on the stack. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the flow returns the expected fixture values and each stub's request log shows at least one call across the flow, with every call's `cutoffMs` ≤ 900 and client-measured latency below 1,000 ms.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — the client replays kept next-page tokens to page backward.

Rationale: kept tokens give the deferred screen both mainframe paging keys over the baseline interface.

- Rejected: Design Option B, service-issued previous-page tokens — it changes the baseline contract.

### Shared by both options

- Topology: the build host's end-to-end client calls the test stack's service, backed by five stubs, a store and a cache.
- Naming: the step, the flow's fourth test, is named for the screen's paging keys.
- Addressing: the account rides in the path and the token in the query, on the published port.
- Isolation: kept tokens last one walk in the client, and sample accounts sit apart from the synthetic volume.
- Fallback: a rejected token restarts the walk at the first page.
- Provisioning: the end-to-end stage starts and removes the stack around the suite, seeded by the shared loader.
- Cut-over: the screen keeps this paging, and the real transaction service replaces its stub by address and credential alone.
- Failures: a failed page fails the step with its trace identifier and leaves kept tokens valid.

### Design Option A — client-kept token stack

The client opens the first page without a token and stacks each next-page token it receives. Forward sends the newest, and backward drops it and replays the one that opened the previous page, or none for the first page. An absent next-page token ends the list.

How it behaves:

- At page size two, the six sample transactions fill three pages, highest transaction number first.
- Backward reopens each page as first seen, and at the top of the list sends nothing.
- A mismatched filter or malformed token draws a bad request, and an unentitled account a denial, before any data read.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Backward by token replay (vs a service previous-page token) | no contract change | the client holds walk state |
| 2 | End on an absent next-page token (vs an empty page) | matches the baseline end-of-list rule | each page needs a presence check |
| 3 | Page size two on six rows (vs the default size) | yields three sample pages | differs from the screen's page length |
| 4 | One tampered and one malformed token (vs the codec cases) | proves rejection end to end | codec cases stay with their story |

Effort and risk: small, one existing file, at risk of a cached replay hiding a decoding fault.

### Design Option B — service-issued previous-page tokens

The transaction stub mints a previous-page token from each page's first row, and the service passes it through for the client to follow backward. Forward paging, the list end and the rejections match Option A.

How it differs:

- The page answer gains a previous-page field the baseline contract lacks.
- Paging work moves from the client to the service and stub.
- The client keeps no state, so a long walk costs it nothing.
- Until the contract changes, the walk proves forward paging alone.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Previous-page token from the service (vs a client token stack) | the client stays stateless | the baseline contract changes |
| 2 | Reverse page read in the stub (vs forward reads only) | one index seek per backward page | stub work beyond this story |

Effort and risk: medium, across contract, service and stub, at risk on backward paging.

### Recommendation

- Option: Design Option A.
- Why: it mirrors the screen's backward key with no service change.
- Strongest argument against: the client holds a long walk's tokens, and losing them restarts the walk.
- Shared items that do not decide the choice: the test stack, fixture order, page size two, rejection cases, request log and deadline checks.
- Flip conditions: a Requirements Analysis entry adds a previous-page field, or kept tokens gain an expiry.

## Design Validation

### Option A

Settled assumptions:

- A token holds its position, bound to account and filters but not page size.
- The data is static this iteration.
- The six sample transactions share one time, so transaction number orders them.
- The first regular fixture user is entitled to the first ten sample accounts, not the eleventh.
- The flow empties the cache before step one, and no earlier step reads pages of two or ten.
- The flow runs in the configuration snapshot's fresh window, so no refresh lands in a rejected call's log.
- The client reads each call's cut-off time from its matched completion log line.

Open assumptions:

- Cached replayed page hiding a token-decoding fault Open → the transaction client story's cache-key rules and the token codec story's unit cases.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the two highest numbers fill page one |
| AC2 | Holds | the first-page token resumes at row three |
| AC3 | Holds | nothing follows the last two rows |
| AC4 | Holds | static data replays the same page |
| AC5 | Holds | the filter differs from the token's bound filter |
| AC6 | Holds | a malformed token fails before upstream calls |
| AC7 | Holds | six rows fit in a page of ten |
| AC8 | Holds | the access guard denies before any data read |
| AC9 | Holds | with injection off no upstream operation repeats |
| AC10 | Holds | the client checks cut-off and latency per call |
| AC11 | Holds | with injection off every page is fresh |

### Option B

Settled assumptions:

- Option A's token, data, order, entitlement and completion-log assumptions hold.

Open assumptions:

- Previous-page field in the page answer Open → the Requirements Analysis author.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | holds once the contract gains a previous-page field |
| AC2 | Conditional | holds once the contract gains a previous-page field |
| AC3 | Conditional | holds once the contract gains a previous-page field |
| AC4 | Breaks | backward travel needs a field the baseline contract lacks |
| AC5 | Holds | the filter differs from the token's bound filter |
| AC6 | Holds | a malformed token fails before upstream calls |
| AC7 | Holds | six rows fit in a page of ten |
| AC8 | Holds | the access guard denies before any data read |
| AC9 | Holds | with injection off no upstream operation repeats |
| AC10 | Holds | the client checks cut-off and latency per call |
| AC11 | Holds | with injection off every page is fresh |

## Metadata

| Field | Value |
|---|---|
| Build wave | W3 |
| Depends on | CS-03-001 |
| Status | TBD |
| Estimated Files Touched | 1 |
| Issue Type | Story |
| Epic Name | Transaction List Drill-Down |
| Epic Link | CS-03 |
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
