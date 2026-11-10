# CS-07: Transaction List Endpoint

## Goal

Serve one entitled account's transactions newest first in keyset pages, optionally filtered by transaction type and category, over REST and gRPC exactly as the contracts declare.

## Business Justification

The transaction list screen of the source application (Use Cases · COTRN00C) shows Transaction ID, Date, Description and Amount columns, ten rows per page, paged forward with PF8 and backward with PF7. In Card Servicing the drill-down from a category balance row (CS-06) lands on this list, carrying the row's `typeCode` and `categoryCode` as filters. The Stub Contract Baseline narrows the list to one account, newest first, with a page size, a page token and optional type and category filters, so this epic owns that endpoint behaviour and reuses, never re-implements, the shared request-parameter validator (CS-05-003), the page-token codec (CS-10-003), the configuration snapshot (CS-11-006), the account access guard (CS-12-003) and the Transaction Activity client (CS-10-006).

## Requirements

- Paging and filter validation before any upstream call or cache read: the shared `accountId` rule (11 digits, not all zero), the `typeCode` and `categoryCode` patterns as `card-servicing-api.yaml` declares them (`^[\s\S]{2}$` and `^[0-9]{4}$`), `pageSize` an integer from 1 to 100 and no larger than the configuration snapshot's `max_page_size`, an absent `pageSize` taking the snapshot's `default_page_size`, and a non-empty `pageToken` that decodes with version 1 and a fingerprint matching the account and both filters; every failure returns 400 `INVALID_ARGUMENT` with one `details` entry per failing field — CS-07-001. Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Retrieval, only after the account access guard allows access, of one page through one Transaction Activity `ListTransactions` call with the effective page size and the caller's page token passed through unchanged, newest first (`processedTimestamp` descending, then `transactionId` descending); 404 when the account has no cross-reference record, 200 with an empty `transactions` array for a well-formed unknown filter or an account with no matching transaction, and 503 or 504 on upstream failure with no stale fallback — CS-07-002. Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- REST (`GET /v1/accounts/{accountId}/transactions`, with the `X-Data-Status` header equal to the body's `dataStatus`) and gRPC (`CardServicingService.ListTransactions`) exposure per `card-servicing-api.yaml` and `card_servicing_service.proto`, carrying the additive item fields `description` and `originalTimestamp` from the COTRN00C Description and Date columns, defined by Data Layouts · CVTRA05Y · TRAN-DESC and TRAN-ORIG-TS, with the end of the list shown as an omitted REST `nextPageToken` and an empty gRPC `next_page_token` — CS-07-003. Trace: CARD_REQ_TBD · Use Cases · COTRN00C

## Acceptance Criteria

1. `GET /v1/accounts/00000000001/transactions?typeCode=01&categoryCode=0001` with the `USER0001` fixture token (`fixture-token-user0001`), all stubs healthy and an empty cache, returns 200 with exactly five `transactions` in the order `0000000776200014`, `0000000755736377`, `0000000624335286`, `0000000555363230`, `0000000503557384`, and the body has no `nextPageToken` property.
2. The same request with no filters and no `pageSize` returns 200 with all six `transactions`, first `0000000776200014` (`amount` `"985.22"`) and last `0000000503557384` (`amount` `"81.44"`), the fifth `0000000532120892` with `typeCode` `"03"` and `amount` `"-70.77"`, each with `originalTimestamp` and `processedTimestamp` `"2022-06-10T19:27:53.000000Z"`, so the newest-first order falls to `transactionId` descending.
3. With `pageSize=2` and no filters for `00000000001`, following each `nextPageToken` yields three pages of two `transactions`: `0000000776200014` and `0000000755736377`, then `0000000624335286` and `0000000555363230`, then `0000000532120892` and `0000000503557384`; the third page has no `nextPageToken`, and the six concatenated items equal the criterion 2 order with no duplicate.
4. With a stale configuration snapshot holding `default_page_size` 20 and `max_page_size` 100, `pageSize=0`, `pageSize=101` and `pageSize=abc` for `00000000001` each return 400 `INVALID_ARGUMENT` with the message "Page size must be an integer from 1 to 100" and exactly one `details` entry whose `field` is `pageSize`; with a stale snapshot holding `max_page_size` 50, `pageSize=60` returns 400 with the message "Page size must be an integer from 1 to 50"; each of these 400 responses leaves every stub request log empty, Config included, and launches no snapshot refresh.
5. A `pageToken` minted for account `00000000002` and replayed on `00000000001` with the same filters, and a token minted for `00000000001` with `typeCode` `01` and `categoryCode` `0001` and replayed with `typeCode` `03`, each return 400 `INVALID_ARGUMENT` with the message "Page token is invalid or does not match this request" and exactly one `details` entry whose `field` is `pageToken`.
6. An entitled account for which Transaction Activity answers `NOT_FOUND` because it has no cross-reference record (account `10000000002` in the unit suite's small fixture set) returns 404 `NOT_FOUND` after exactly one `ListTransactions` call, with no retry.
7. For `00000000001`, a well-formed but unknown filter (`typeCode=99`) returns 200 with an empty `transactions` array and no `nextPageToken`, and the Reference Data stub request log holds zero entries for that request.
8. `USER0001` requesting `00000000011`, outside its entitlements, returns 403 `PERMISSION_DENIED`, and no account data is read from upstream or cache before access is allowed: for that request the Transaction Activity stub request log holds zero entries and the cache records zero reads.
9. On the last page REST omits `nextPageToken`, rendering it neither as JSON `null` nor as the empty string, and gRPC returns `next_page_token` as the empty string; on every 200 the `X-Data-Status` header equals the body's `dataStatus` and the gRPC `data_status`, which is `FRESH` in criteria 1, 2, 3 and 7.
10. Each `ListTransactions` attempt times out at the lesser of 300 ms and the remaining budget, and at most two retries follow, after 50 ms then 100 ms of backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only. With the Transaction Activity stub failing every call the request returns 503 `UNAVAILABLE` after exactly three attempts, and with a 400 ms stub latency it returns 504 `DEADLINE_EXCEEDED`, both also with a page held past its fresh TTL, which is never returned; in each case the logged `cutoffMs` is at most 900 and the client receives the response below 1,000 ms.
11. On the same fixed test clock, gRPC `ListTransactions` with `account_id` `00000000001`, `page_size` unset and `authorization` metadata `Bearer fixture-token-user0001` returns a message whose every field value, each `transactions` element included, equals the criterion 2 REST body field of the same name, snake_case matched to camelCase, with amounts and timestamps as identical strings, and the unset `page_size` reaches Transaction Activity as 20.
12. A request with no token or with `fixture-token-expired` returns 401 `UNAUTHENTICATED`, and with the Auth stub failing every call the request returns 503 `UNAVAILABLE`; in each case the Transaction Activity stub request log holds zero entries.
13. Every 400, 401, 403, 404, 503 and 504 response carries the standard error object (`code`, `message`, `traceId`, `details`) with the `traceId` generated at receipt, and each gRPC error carries the same object, with the same `code` string, packed in its `google.rpc.Status` details.
14. With a stale configuration snapshot and the Config stub failing every call, or answering after 2,000 ms, the criterion 2 request returns the same six `transactions` with `dataStatus` `FRESH`, a logged `cutoffMs` of at most 900 and a client-measured response below 1,000 ms.

## Dependencies

- CS-05 Balance Summary Endpoint: CS-07-001 → CS-05-003
- CS-10 Transaction Activity Integration: CS-07-001 → CS-10-003, CS-07-002 → CS-10-006
- CS-11 Config Service Integration: CS-07-001 → CS-11-006, CS-07-002 → CS-11-006
- CS-12 Auth Service Integration: CS-07-002 → CS-12-003

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-07-001](stories/CS-07-001-transaction-list-input-validation.md) | Transaction list input validation | 3 | 3 | W2 |
| [CS-07-002](stories/CS-07-002-transaction-list-retrieval-with-token-pass-through.md) | Transaction list retrieval with token pass-through | 3 | 3 | W2 |
| [CS-07-003](stories/CS-07-003-transaction-list-rest-and-grpc-endpoints.md) | Transaction list REST and gRPC endpoints | 3 | 4 | W2 |
| Total | 3 stories | 9 | 10 | W2 |

Owner: TBD

Status: TBD
