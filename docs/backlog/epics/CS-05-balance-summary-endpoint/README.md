# CS-05: Balance Summary Endpoint

## Goal

Serve the balance summary of one entitled account over REST and gRPC per contract, on the service scaffold, CI gate, standard error object and shared request-parameter validator this epic owns.

## Business Justification

The COACTVW account view (Use Cases · COACTVWC) is the first in-scope interaction and the entry to every drill-down: category balances, transaction list and transaction detail. As the first consumer in build order, this epic owns the shared foundations that every other epic builds on and never re-implements: the service scaffold and CI gate, the standard error object, the shared request-parameter validator, the type-group aggregator and the data-status resolver.

## Requirements

- Balance summary of one account carrying the COACTVWC account-section fields — current balance, credit limit, cash credit limit, current cycle credit and debit, active status, open, expiration and reissue dates, account group — plus balances by transaction type group (`balancesByTypeGroup`), `dataStatus` and `asOf` — CS-05-005. Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-CURR-BAL
- A malformed or all-zero account number is rejected with 400 and the message "Account number must be a non zero 11 digit number" before any upstream call — CS-05-003. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Category balances are summed by transaction type into one exact-decimal balance per type code, type code ascending — CS-05-004. Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRAN-CAT-BAL
- When a source is slow or down the summary degrades to `STALE` (cached account or category-balance data within max stale age) or `PARTIAL` (type descriptions null) instead of failing, with `PARTIAL` > `STALE` > `FRESH` — CS-05-005. Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-CURR-BAL
- The summary is served over REST (`GET /v1/accounts/{accountId}/balance-summary`, `X-Data-Status` header) and gRPC (`CardServicingService.GetBalanceSummary`) with identical values — CS-05-006. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Directive (module and file organization): a two-module Gradle build with exact version pins and a pull-request CI gate — CS-05-001. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Directive (standard error object): every REST and gRPC error is one standard error object carrying a receipt `traceId` — CS-05-002. Trace: CARD_REQ_TBD · Use Cases · COACTVWC

## Acceptance Criteria

1. `GET /v1/accounts/00000000001/balance-summary` with the `USER0001` fixture token, all stubs healthy and an empty cache, returns 200 with `accountId` "00000000001", `currentBalance` "194.00", `creditLimit` "2020.00", `cashCreditLimit` "1020.00", `currentCycleCredit` "0.00", `currentCycleDebit` "0.00", `activeStatus` "Y", `openDate` "2014-11-20", `expirationDate` "2025-05-20", `reissueDate` "2025-05-20", no `groupId` property, `balancesByTypeGroup` holding exactly one element (`typeCode` "01", `typeDescription` "Purchase", `balance` "0.00"), `dataStatus` `FRESH` and response header `X-Data-Status: FRESH`.
2. gRPC `GetBalanceSummary` for the same account, token and fixture state returns a message whose every field value equals the REST body field of the same name in criterion 1, snake_case matched to camelCase, with amounts and dates as strings and `group_id` unset.
3. An `accountId` of `00000000000`, of 10 digits, of 12 digits or containing a letter returns 400 `INVALID_ARGUMENT` with the message "Account number must be a non zero 11 digit number" and one `details` entry for `accountId`, and every stub request log, Config included, stays empty.
4. An account outside the caller's entitlements (`USER0001` requesting `00000000011`) returns 403 `PERMISSION_DENIED`, and no account data is read from upstream or cache before access is allowed.
5. With Reference Data unusable (call failed, no cache entry within max stale age) the response is 200 with `dataStatus` `PARTIAL`, REST `typeDescription` present as JSON `null`, and the gRPC `type_description` field unset.
6. With the account read failing after retries and a cache entry within max stale age, the response is 200 `STALE` with `asOf` equal to that entry's `cachedAt`; with no usable entry it is 503 (`UNAVAILABLE`) or 504 (`DEADLINE_EXCEEDED`).
7. `GetAccount` returning `NOT_FOUND` for an entitled account returns 404 `NOT_FOUND`, and the Card Data stub request log shows exactly one `GetAccount` call.
8. Under injected upstream latency each attempt times out at the lesser of 300 ms and the remaining budget, at most two retries follow with 50 ms then 100 ms backoff on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, the logged `cutoffMs` is at most 900 and the client-measured response arrives below 1,000 ms.
9. With the Config stub failing every call, the criterion 1 request returns `dataStatus` `FRESH`, unchanged by the Config refresh failure.
10. Every 400, 401, 403, 404, 503 and 504 response carries the standard error object with a non-empty `traceId`, and each gRPC error carries the same object, with the same `code` string, in its status details.
11. `./gradlew :card-servicing-service:compileJava` compiles every generated message and stub from both proto roots with no annotation library added, and CI runs the Unit and Validation stages on every pull request, a failing stage failing the gate.

## Dependencies

- CS-09 Card Data Integration: CS-05-005 → CS-09-005
- CS-11 Config Service Integration: CS-05-005 → CS-11-006
- CS-12 Auth Service Integration: CS-05-005 → CS-12-003
- CS-13 Reference Data Integration: CS-05-005 → CS-13-003

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-05-001](stories/CS-05-001-service-module-scaffold-and-ci-gate.md) | Service module scaffold and CI gate | 5 | 14 | W0 |
| [CS-05-002](stories/CS-05-002-standard-error-object-and-status-mapping.md) | Standard error object and status mapping | 3 | 7 | W0 |
| [CS-05-003](stories/CS-05-003-account-identifier-and-balance-summary-input-validation.md) | Account identifier and balance summary input validation | 2 | 3 | W2 |
| [CS-05-004](stories/CS-05-004-type-group-balance-aggregation.md) | Type-group balance aggregation | 2 | 2 | W2 |
| [CS-05-005](stories/CS-05-005-balance-summary-assembly-with-stale-and-partial-degradation.md) | Balance summary assembly with stale and partial degradation | 5 | 5 | W2 |
| [CS-05-006](stories/CS-05-006-balance-summary-rest-and-grpc-endpoints.md) | Balance summary REST and gRPC endpoints | 3 | 4 | W2 |
| Total | 6 stories | 20 | 35 | W0, W2 |

Owner: TBD

Status: TBD
