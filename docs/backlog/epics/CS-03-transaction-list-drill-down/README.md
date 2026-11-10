# CS-03: Transaction List Drill-Down

## Goal

Prove the transaction list drill-down from a category balance row, its paging and its latency under the 1-second SLA on the real request path.

## Business Justification

The transaction list of Use Cases · COTRN00C shows an account holder's transactions with Transaction ID, Date (Data Layouts · CVTRA05Y · TRAN-ORIG-TS), Description and Amount, and pages forward with F8 and backward with F7. The deferred UI's drill-down from a category balance row lands on this list, carrying the row's type code and category code with `accountId` (category row → `typeCode` + `categoryCode` → transaction rows), so the end-to-end HTTP client standing in for the UI proves that link on the real request path. The iteration must show this list answers within the 1-second SLA at full planning volume, sparse filters included, because the indexing decision D4 rests on that measurement. The epic owns verification only: it runs on the Compose stack, end-to-end harness and performance harness of CS-01, while the endpoint behaviour belongs to CS-07 and the Transaction Activity stub and client to CS-10.

## Requirements

- Transaction list end-to-end step, step 3 of the four-step flow for account `00000000001`: the drill-down from step 2's category row returns that row's transactions newest first, with `description` and `originalTimestamp`, on the real request path, and hands a transaction row to the transaction detail step — CS-03-001. Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Transaction list paging navigation step: forward paging by `nextPageToken`, the end of the list on its absence, and backward paging by replaying kept tokens, the equivalent of the F8 and F7 keys — CS-03-002. Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Transaction list SLA scenario: p50, p95 and p99 of `GET /v1/accounts/{accountId}/transactions` at the planning baseline of 10 concurrent users × 100 sequential calls and 1,000,000 transactions, over the four D4 filter shapes on the large-history account — CS-03-003. Directive: 1-second SLA. Trace: CARD_REQ_TBD · Use Cases · COTRN00C

## Acceptance Criteria

1. Step 3 of the four-step end-to-end flow sends the type code `01` and category code `0001` of step 2's category row in `GET /v1/accounts/00000000001/transactions?typeCode=01&categoryCode=0001` with `fixture-token-user0001` and receives 200 with exactly five `transactions` elements in the order `0000000776200014` `"985.22"`, `0000000755736377` `"906.44"`, `0000000624335286` `"598.44"`, `0000000555363230` `"484.99"`, `0000000503557384` `"81.44"`, each carrying `description` and `originalTimestamp` `"2022-06-10T19:27:53.000000Z"`, and no `nextPageToken` property (CS-03-001).
2. An unfiltered walk with `pageSize=2` on `00000000001` returns three pages of two transactions by following `nextPageToken`; the third page has no `nextPageToken` property, and replaying the kept page-1 token returns page 2 again, identical in content and order (CS-03-002).
3. A request for an account outside the caller's entitlements — `00000000011` with `fixture-token-user0001` in the end-to-end flow, `10000000001` with `fixture-token-user0002` before the SLA scenarios — returns 403 `PERMISSION_DENIED` with a non-empty `traceId`, and the Transaction Activity stub request log shows no call for it (CS-03-001, CS-03-002, CS-03-003).
4. Each of the four filter shapes with `USER0001`'s virtual users on the large-history account `10000000001` — unfiltered, `typeCode=05` only, `categoryCode=0001` only, and both `05`/`0001`, whose first page holds exactly the 20 transactions carrying that pair — holds p50, p95 and p99 below 1,000 ms over successful requests at 10 concurrent users × 100 sequential calls, with zero non-200 responses and failure injection off (`STUB_LATENCY_MS` 0, `STUB_FAILURE_RATE` 0 on every stub) (CS-03-003).
5. Any filtered shape whose Transaction Activity stub `ListTransactions` p99 duration, read from the stub request log, exceeds 150 ms fails the SLA scenario (CS-03-003).
6. Every end-to-end request in this epic logs `cutoffMs` at or below 900 in the completion log line matched to it and reaches the client in under 1,000 ms of client-measured latency (CS-03-001, CS-03-002).

## Dependencies

- CS-01 Account Balance Summary View: CS-03-003 → CS-01-003
- CS-02 Category Balance View: CS-03-001 → CS-02-001

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-03-001](stories/CS-03-001-transaction-list-end-to-end-step.md) | Transaction list end-to-end step | 2 | 1 | W3 |
| [CS-03-002](stories/CS-03-002-transaction-list-paging-navigation-step.md) | Transaction list paging navigation step | 3 | 1 | W3 |
| [CS-03-003](stories/CS-03-003-transaction-list-sla-scenario.md) | Transaction list SLA scenario | 2 | 1 | W3 |
| Total | 3 stories | 7 | 3 | W3 |

Owner: TBD

Status: TBD
