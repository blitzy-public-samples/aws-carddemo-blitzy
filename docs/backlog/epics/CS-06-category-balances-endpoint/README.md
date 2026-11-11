# CS-06: Category Balances Endpoint

## Goal

Serve the category balances of one entitled account, optionally filtered by transaction type, over REST and gRPC per contract, with stale and partial degradation.

## Business Justification

The category balance view is the second drill-down step from the balance summary (CS-05): a summary row's `typeCode` opens that type's category rows, and a category row's `typeCode` and `categoryCode` open the transaction list (CS-07). The UI needs each category balance with its type and category descriptions and the freshness status of the data behind them. No CardDemo screen shows category balances, so the epic is anchored on the category balance record, Data Layouts · CVTRA01Y · TRAN-CAT-BAL, keyed by account, type code and category code. The epic owns the endpoint behaviour only, and reuses, never re-implements, the shared request-parameter validator (CS-05-003), the data-status resolver (CS-05-005), the account access guard (CS-12-003) and the Card Data and Reference Data clients (CS-09-005, CS-13-003).

## Requirements

- `accountId` and optional `typeCode` validation before any upstream call: the shared `accountId` rule (11 digits, not all zero) and the `typeCode` rule of exactly two characters; a well-formed `typeCode` that the transaction type list does not hold and no transaction category carries is rejected with 400 while both reference lists are usable (fresh or within their max stale age), and the check rejects nothing otherwise — CS-06-001. Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRANCAT-TYPE-CD
- Retrieval, only after the account access guard allows access, of the account's category balances through the Card Data client, read concurrently with the transaction categories and transaction types through the Reference Data client; the optional `typeCode` filter; ordering by `typeCode` then `categoryCode` ascending; `typeDescription` and `categoryDescription` from Reference Data; `dataStatus` and `asOf` through the shared resolver; `STALE` from a cached category-balance entry within max stale age, `PARTIAL` with each unavailable description null when reference data is unusable (both descriptions for an unusable category list, `typeDescription` alone for an unusable type list), 404 for an unknown account and 503 or 504 otherwise — CS-06-002. Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRAN-CAT-BAL
- REST (`GET /v1/accounts/{accountId}/category-balances`, with the `X-Data-Status` header equal to the body's `dataStatus`) and gRPC (`CardServicingService.ListCategoryBalances`) exposure per `card-servicing-api.yaml` and `card_servicing_service.proto`, with identical values on both protocols, a missing description carried as a present JSON `null` on REST and an unset field on gRPC, and the standard error object on every error — CS-06-003. Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRAN-CAT-BAL

## Acceptance Criteria

1. `GET /v1/accounts/00000000001/category-balances?typeCode=01` with the `USER0001` fixture token (`fixture-token-user0001`), all stubs healthy and an empty cache, returns 200 with response header `X-Data-Status: FRESH` and a body carrying `accountId` "00000000001", `categoryBalances` holding exactly one element (`typeCode` "01", `typeDescription` "Purchase", `categoryCode` "0001", `categoryDescription` "Regular Sales Draft", `balance` "0.00"), `dataStatus` "FRESH" and `asOf` in RFC 3339 form with microseconds and `Z`.
2. gRPC `ListCategoryBalances` with `account_id` `00000000001`, `type_code` `01`, the same token and the same fixture state returns a message whose every field value equals the REST body field of the same name in criterion 1, snake_case matched to camelCase, with `type_description` and `category_description` both set.
3. With `typeCode` absent (gRPC `type_code` unset), every category balance of the account is returned, ordered by `typeCode` then `categoryCode` ascending whatever order Card Data answers in; a well-formed `typeCode` that the transaction category list carries with no matching balance (`02` on `00000000001`) returns 200 with `categoryBalances` present and empty.
4. A malformed `typeCode` (`1`, `001` or the empty value) returns 400 `INVALID_ARGUMENT` with the message "Transaction type code must be exactly 2 characters" and one `details` entry for `typeCode`; a malformed or all-zero `accountId` (`0000000001`, `0000000000A` or `00000000000`) returns 400 `INVALID_ARGUMENT` with the message "Account number must be a non zero 11 digit number" and one `details` entry for `accountId`; for each request every stub request log, Config included, stays empty.
5. A well-formed `typeCode` that the transaction type list does not hold and no transaction category carries (`99` or `A1`) returns 400 `INVALID_ARGUMENT` with the message "Transaction type code is unknown" and one `details` entry for `typeCode` while both reference lists are usable (fresh or within their 86,400 s max stale age), and no category balance read for that request reaches the response.
6. With the transaction category list unusable (Reference Data failing every call, no entry within its max stale age), the unknown-type check is skipped and the `typeCode` filter still applies: REST `typeDescription` and `categoryDescription` are present as JSON `null`, gRPC `type_description` and `category_description` are unset and never an empty string, and `dataStatus` is `PARTIAL` in the body, the `X-Data-Status` header and the gRPC message.
7. With the category-balance read failing after retries and a cache entry within its 300 s max stale age, the response is 200 `STALE` with `asOf` equal to that entry's `cachedAt`; with no usable entry it is 503 `UNAVAILABLE` or 504 `DEADLINE_EXCEEDED`, and a healthy Reference Data answer never turns either into a 200.
8. Under injected upstream latency each Card Data and Reference Data attempt times out at the lesser of 300 ms and the remaining budget, at most two retries follow with 50 ms then 100 ms backoff on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, and for every degraded 200, 503 and 504 the logged `cutoffMs` is at most 900 and the client-measured response arrives below 1,000 ms.
9. Account `00000000011` requested with the `USER0001` fixture token returns 403 `PERMISSION_DENIED`, and for that request the Card Data and Reference Data stub request logs hold zero entries and the cache records zero reads, so no account data is read from upstream or cache before access is allowed.
10. A request with no token, with `fixture-token-expired` or with `fixture-token-malformed` returns 401 `UNAUTHENTICATED`, and the Card Data and Reference Data stub request logs hold zero entries for it.
11. An entitled account that Card Data reports `NOT_FOUND` returns 404 `NOT_FOUND`, and the Card Data stub request log shows exactly one `ListCategoryBalances` call, with no retry.
12. Every 400, 401, 403, 404, 503 and 504 response carries the standard error object with the `traceId` generated at receipt, and each gRPC error carries the same object, with the same `code` string, packed in its `google.rpc.Status` details.
13. With the Config stub failing every call, the criterion 1 request returns `dataStatus` `FRESH`, unchanged by the Config refresh failure.
14. With a transaction type list that also holds type code `08`, which no transaction category carries, `typeCode` `08` on `00000000001` returns 200 with `categoryBalances` present and empty (gRPC: no `category_balances` element), not 400.
15. With only the transaction type list unusable (no entry within its max stale age), the unknown-type check is skipped and the `typeCode` filter still applies: `typeDescription` is JSON `null` on REST and unset on gRPC, `categoryDescription` keeps its description ("Regular Sales Draft" for `01`/`0001`), and `dataStatus` is `PARTIAL`.

## Dependencies

- CS-05 Balance Summary Endpoint: CS-06-001 → CS-05-003, CS-06-002 → CS-05-005
- CS-09 Card Data Integration: CS-06-002 → CS-09-005
- CS-12 Auth Service Integration: CS-06-002 → CS-12-003
- CS-13 Reference Data Integration: CS-06-001 → CS-13-003, CS-06-002 → CS-13-003

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-06-001](stories/CS-06-001-category-balance-input-validation.md) | Category balance input validation | 3 | 3 | W2 |
| [CS-06-002](stories/CS-06-002-category-balance-retrieval-filter-and-degradation.md) | Category balance retrieval, filter and degradation | 5 | 3 | W2 |
| [CS-06-003](stories/CS-06-003-category-balance-rest-and-grpc-endpoints.md) | Category balance REST and gRPC endpoints | 3 | 4 | W2 |
| Total | 3 stories | 11 | 10 | W2 |

Owner: TBD

Status: TBD
