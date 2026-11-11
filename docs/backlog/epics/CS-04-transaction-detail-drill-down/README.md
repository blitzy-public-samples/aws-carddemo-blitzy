# CS-04: Transaction Detail Drill-Down

## Goal

Prove the transaction detail drill-down from a transaction-list row end to end and under the 1-second SLA.

## Business Justification

Use Cases · COTRN01C shows one transaction's full detail (Transaction ID, Card Number, Type CD, Category CD, Source, Description, Amount, Orig Date, Proc Date, Merchant ID, Merchant Name, Merchant City, Merchant Zip), reached from the COTRN00C list. COTRN01C reads a transaction by its identifier alone; the Card Servicing contract adds the account path and an ownership check, which this epic proves on the real request path with the end-to-end HTTP client standing in for the deferred UI. Completing step 4 closes the four-step flow and proves every mocked upstream (Auth, Config, Card Data, Reference Data, Transaction Activity) is exercised, and the SLA scenario proves the detail view meets the 1-second target at the planning baseline. The epic owns verification only: it runs on the Compose stack, end-to-end harness and performance harness of CS-01, and the endpoint behaviour belongs to CS-08.

## Requirements

- Transaction detail end-to-end step: step 4 of the four-step flow opens the first transaction of step 3's list for account 00000000001 along the real request path, checks the not-found, invalid-input and permission-denied outcomes, and asserts that each of the five stub request logs shows at least one call — CS-04-001. Trace: CARD_REQ_TBD · Use Cases · COTRN01C
- Transaction detail SLA scenario: the transaction detail endpoint is measured at 10 concurrent users × 100 sequential calls with 1,000,000 transactions loaded, p50, p95 and p99 each below 1,000 ms over successful requests — CS-04-002. Directive: 1-second SLA. Trace: CARD_REQ_TBD · Use Cases · COTRN01C

## Acceptance Criteria

1. Step 4, using the first `transactionId` returned by step 3 (`0000000776200014`), receives 200 from `GET /v1/accounts/00000000001/transactions/0000000776200014` with `typeCode` "01", `categoryCode` "0001", `amount` "985.22", `cardNumber` "9680294154603697", `originalTimestamp` and `processedTimestamp` "2022-06-10T19:27:53.000000Z", and `X-Data-Status` `FRESH` (CS-04-001).
2. Transaction `0000000776200014` requested under account `00000000002` returns 404, and transaction `9999999999999999` under account `00000000001` returns 404, each with the standard error object and a non-empty `traceId` (CS-04-001).
3. A request for account `00000000011` with `USER0001`'s token returns 403 `PERMISSION_DENIED`, and the Transaction Activity and Card Data stub request logs gain no entry for it (CS-04-001).
4. After the four steps of the end-to-end flow, the Auth, Config, Card Data, Reference Data and Transaction Activity stub request logs each show at least one call (CS-04-001).
5. The transaction detail SLA scenario reports p50, p95 and p99 each below 1,000 ms over successful requests at 10 concurrent users × 100 sequential calls, with 1,000,000 transactions loaded, failure injection off and zero non-200 responses (CS-04-002).
6. `GET /v1/accounts/00000000001/transactions/12345` with `USER0001`'s token returns 400 `INVALID_ARGUMENT` with a `details` entry naming `transactionId` and a non-empty `traceId`, and no stub request log, Auth included, gains an entry for that request (CS-04-001).

## Dependencies

- CS-01 Account Balance Summary View: CS-04-002 → CS-01-003
- CS-03 Transaction List Drill-Down: CS-04-001 → CS-03-001

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-04-001](stories/CS-04-001-transaction-detail-end-to-end-step.md) | Transaction detail end-to-end step | 2 | 1 | W3 |
| [CS-04-002](stories/CS-04-002-transaction-detail-sla-scenario.md) | Transaction detail SLA scenario | 2 | 1 | W3 |
| Total | 2 stories | 4 | 2 | W3 |

Owner: TBD

Status: TBD
