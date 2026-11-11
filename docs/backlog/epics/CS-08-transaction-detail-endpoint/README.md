# CS-08: Transaction Detail Endpoint

## Goal

Serve one transaction of an entitled account over REST and gRPC per contract, returning 404 for any transaction whose card number is not among that account's card cross-reference records.

## Business Justification

The COTRN01 transaction view (Use Cases · COTRN01C) shows one transaction's detail, the 13 Data Layouts · CVTRA05Y fields on that screen, read by transaction ID alone with no account check. The Stub Contract Baseline adds the account path and an ownership check of the transaction's card number against the account's card cross-reference records (Data Layouts · CVACT03Y · XREF-CARD-NUM), so a caller entitled to one account cannot read another account's transaction by its ID, and the deferred UI binds to this fixed interface. The epic owns the endpoint behaviour only, and reuses, never re-implements, the shared request-parameter validator (CS-05-003), the data-status resolver (CS-05-005), the account access guard (CS-12-003), the Transaction Activity client (CS-10-006) and the Card Data client (CS-09-005).

## Requirements

- `accountId` and `transactionId` validation before any upstream call: the shared `accountId` rule (11 digits, not all zero) and the `transactionId` rule of exactly 16 characters of any kind (Data Layouts · CVTRA05Y · TRAN-ID X(16), the contract pattern `^[\s\S]{16}$`), with a value of 16 spaces or 16 NUL characters rejected as empty; a malformed value returns 400 `INVALID_ARGUMENT` at receipt — CS-08-001. Trace: CARD_REQ_TBD · Use Cases · COTRN01C
- Ownership check and detail retrieval, only after the account access guard allows access: the transaction through the Transaction Activity client and the account's card cross-reference records through the Card Data client, read concurrently; the transaction belongs to the account only when its `cardNumber` equals the card number of one of those records; 404 `NOT_FOUND` with one identical message when the transaction is unknown or its card number is not on the account; `STALE` from a cached transaction within its 300 s max stale age, 503 or 504 when the transaction read fails with no usable cache entry, and 503 or 504 when the cross-reference read fails, even when the transaction came from cache; `dataStatus` and `asOf` through the shared resolver — CS-08-002. Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-CARD-NUM
- REST (`GET /v1/accounts/{accountId}/transactions/{transactionId}`, with the `X-Data-Status` header equal to the body's `dataStatus`) and gRPC (`CardServicingService.GetTransaction`) exposure per `card-servicing-api.yaml` and `card_servicing_service.proto`, with identical values on both protocols, all 13 transaction fields set, amounts and timestamps as strings, and the standard error object on every error — CS-08-003. Trace: CARD_REQ_TBD · Use Cases · COTRN01C

## Acceptance Criteria

1. `GET /v1/accounts/00000000001/transactions/0000000776200014` with the `USER0001` fixture token (`fixture-token-user0001`), all stubs healthy and an empty cache, returns 200 with response header `X-Data-Status: FRESH` and a body carrying `accountId` "00000000001", `dataStatus` "FRESH", `asOf` in RFC 3339 form with microseconds and `Z`, and a `transaction` holding `transactionId` "0000000776200014", `cardNumber` "9680294154603697", `typeCode` "01", `categoryCode` "0001", `source` "POS TERM", `description` "Purchase at Lebsack and Sons", `amount` "985.22", `merchantId` "800000000", `merchantName` "Lebsack and Sons", `merchantCity` "Otisbury", `merchantZip` "32545", and `originalTimestamp` and `processedTimestamp` "2022-06-10T19:27:53.000000Z".
2. gRPC `GetTransaction` with `account_id` `00000000001`, `transaction_id` `0000000776200014`, the same token and the same fixture state returns a message whose every field value equals the REST body field of the same name in criterion 1, snake_case matched to camelCase, with all 13 `transaction` fields set.
3. The same transaction under account `00000000002`, which is within the `USER0001` entitlements and whose only card is `0923877193247330`, returns 404 `NOT_FOUND` with `message` "Transaction not found", a `traceId` and no `transaction` property, the same `code` and `message` as criterion 4.
4. The unknown transaction `9999999999999999` under `00000000001` returns 404 `NOT_FOUND` with `message` "Transaction not found", and the Transaction Activity stub request log shows exactly one `GetTransaction` call, with no retry.
5. A `transactionId` of 15 characters (`000000077620001`), of 17 characters (`00000007762000140`), of 16 spaces or of 16 NUL characters under `00000000001` returns 400 `INVALID_ARGUMENT` with exactly one `details` entry whose `field` is `transactionId`, and for each request every stub request log, Config included, holds zero entries; a 16-character value holding punctuation (`000000077620001!`) passes validation, because the contract pattern disallows no character.
6. Account `00000000011` requested with the `USER0001` fixture token returns 403 `PERMISSION_DENIED`, and for that request the Card Data and Transaction Activity stub request logs hold zero entries and the cache records zero reads, so no account data is read from upstream or cache before access is allowed.
7. A request with no token, with `fixture-token-expired` or with `fixture-token-malformed` returns 401 `UNAUTHENTICATED`, and the Card Data and Transaction Activity stub request logs hold zero entries for it.
8. With `0000000776200014` held in a fresh cache entry, the Card Data stub failing every call returns 503 `UNAVAILABLE`, and a Card Data stub latency of 2,000 ms returns 504 `DEADLINE_EXCEEDED`, never 200.
9. With the Transaction Activity stub failing every call and a cache entry for `0000000776200014` aged 120 s, past its 60 s fresh TTL and within its 300 s max stale age, the request under `00000000001` returns 200 with `dataStatus` `STALE` and `asOf` equal to the entry's `cachedAt`; with no entry within the max stale age it returns 503 `UNAVAILABLE`.
10. Under injected upstream latency each `GetTransaction` and `GetCardCrossReference` attempt times out at the lesser of 300 ms and the remaining budget, at most two retries follow with 50 ms then 100 ms backoff on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, and for every degraded 200, 503 and 504 the logged `cutoffMs` is at most 900 and the client-measured response arrives below 1,000 ms.
11. On every 200 the `X-Data-Status` response header equals the body's `dataStatus`, `FRESH` for criterion 1 and `STALE` for criterion 9, and gRPC `data_status` carries the same value.
12. Every 400, 401, 403, 404, 503 and 504 response carries the standard error object (`code`, `message`, `traceId`, `details`) with the `traceId` generated at receipt, and each gRPC error carries the same object, with the same `code` string, packed in its `google.rpc.Status` details.
13. With the Config stub failing every call, the criterion 1 request returns `dataStatus` `FRESH`, unchanged by the Config refresh failure.

## Dependencies

- CS-05 Balance Summary Endpoint: CS-08-001 → CS-05-003, CS-08-002 → CS-05-005
- CS-09 Card Data Integration: CS-08-002 → CS-09-005
- CS-10 Transaction Activity Integration: CS-08-002 → CS-10-006
- CS-12 Auth Service Integration: CS-08-002 → CS-12-003

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-08-001](stories/CS-08-001-transaction-detail-input-validation.md) | Transaction detail input validation | 2 | 3 | W2 |
| [CS-08-002](stories/CS-08-002-transaction-ownership-check-and-detail-retrieval.md) | Transaction ownership check and detail retrieval | 5 | 3 | W2 |
| [CS-08-003](stories/CS-08-003-transaction-detail-rest-and-grpc-endpoints.md) | Transaction detail REST and gRPC endpoints | 3 | 4 | W2 |
| Total | 3 stories | 10 | 10 | W2 |

Owner: TBD

Status: TBD
