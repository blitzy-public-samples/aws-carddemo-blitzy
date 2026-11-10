# CS-02: Category Balance View

## Goal

Prove the category balance drill-down from a balance-summary type-group row end to end along the real request path and under the 1-second SLA.

## Business Justification

The category balance view is the drill-down the deferred UI opens from a balance-summary type-group row: the row's `typeCode` opens that type's category rows, with `accountId` carried throughout (summary row → `typeCode` → category rows). Proving this step on the real request path — authentication, per-account authorization, stubbed upstream reads, cache and the 900 ms deadline — with the end-to-end HTTP client standing in for the UI lets the deferred UI bind to a verified interface. No CardDemo screen shows category balances, so the interaction is anchored on the category balance record, Data Layouts · CVTRA01Y · TRAN-CAT-BAL, and the 1-second SLA applies to this view as to every UI-facing endpoint. The epic owns verification only: it runs on the Compose stack, end-to-end harness and performance harness of CS-01, and the endpoint behaviour belongs to CS-06.

## Requirements

- Category balance end-to-end step, step 2 of the four-step flow for account `00000000001`: `GET /v1/accounts/00000000001/category-balances` with the `typeCode` of the first type-group row from step 1, every category-balance field checked against the fixture category row, the malformed type code, unknown type code, expired-token and access-denial checks, the deadline check on every call, and the category-row hand-off to the transaction list step — CS-02-001. Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRAN-CAT-BAL
- Category balance SLA scenario: p50, p95 and p99 of `GET /v1/accounts/{accountId}/category-balances` at the planning baseline of 10 concurrent users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions, over unfiltered and `typeCode`-filtered calls — CS-02-002. Directive: 1-second SLA. Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRAN-CAT-BAL

## Acceptance Criteria

1. Step 2 of the four-step end-to-end flow, run after step 1 with the `typeCode` taken from step 1's first `balancesByTypeGroup` element (`01` for the sample account), sends `GET /v1/accounts/00000000001/category-balances?typeCode=01` with `USER0001`'s fixture token `fixture-token-user0001` and receives 200 with exactly one `categoryBalances` element — `typeCode` `"01"`, `typeDescription` `"Purchase"`, `categoryCode` `"0001"`, `categoryDescription` `"Regular Sales Draft"`, `balance` `"0.00"` — and the response header `X-Data-Status` `FRESH` (CS-02-001).
2. When step 1 did not pass, step 2 fails with a message naming step 1 before sending any call, and after criterion 1 the flow holds `typeCode` `"01"` and `categoryCode` `"0001"` from the first `categoryBalances` element for the transaction list step (CS-02-001).
3. A request with `fixture-token-user0001` for account `00000000011`, which lies outside `USER0001`'s sample-account entitlements `00000000001`–`00000000010`, returns 403 `PERMISSION_DENIED` with a non-empty `traceId`, and the Card Data and Reference Data stub request logs gain no entry for that call, so no account data is read from upstream before access is allowed (CS-02-001, CS-02-002).
4. On the Compose stack, `typeCode=1` returns 400 `INVALID_ARGUMENT` with the message "Transaction type code must be exactly 2 characters" and every stub request log empty for that call, and `typeCode=99` returns 400 `INVALID_ARGUMENT` with the message "Transaction type code is unknown" and one `details` entry for `typeCode` (CS-02-001).
5. `GET /v1/accounts/00000000001/category-balances` with `fixture-token-expired` returns 401 `UNAUTHENTICATED`, with only a `ValidateToken` entry on the Auth stub and no entry on the Card Data or Reference Data stub (CS-02-001).
6. Every call of the category balance end-to-end step receives its full answer below 1,000 ms of client-measured latency, and the completion log line matched to it shows `cutoffMs` ≤ 900 (CS-02-001).
7. The category balance SLA scenario at 10 concurrent users × 100 sequential calls on 100,000 synthetic category balances, with failure injection off (`STUB_LATENCY_MS` 0, `STUB_FAILURE_RATE` 0 on every stub), reports p50, p95 and p99 each below 1,000 ms over successful requests, for the 1,000 calls combined and for the 500 unfiltered and 500 `typeCode`-filtered calls, with zero non-200 responses (CS-02-002).
8. In the SLA scenario, every measured 200 carries `X-Data-Status` `FRESH`, every completion log line added during the measured window shows `cutoffMs` ≤ 900, and every stub operation with entries in that window shows a retry count of 0 (CS-02-002).

## Dependencies

- CS-01 Account Balance Summary View: CS-02-001 → CS-01-004, CS-02-002 → CS-01-003

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-02-001](stories/CS-02-001-category-balance-end-to-end-step.md) | Category balance end-to-end step | 2 | 1 | W3 |
| [CS-02-002](stories/CS-02-002-category-balance-sla-scenario.md) | Category balance SLA scenario | 2 | 1 | W3 |
| Total | 2 stories | 4 | 2 | W3 |

Owner: TBD

Status: TBD
