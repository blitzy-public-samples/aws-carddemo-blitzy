# CS-01: Account Balance Summary View

## Goal

Prove the account balance summary interaction end to end and under the 1-second SLA on the shared Compose stack and the end-to-end and performance harnesses this epic owns.

## Business Justification

The account view of Use Cases · COACTVWC, reached from the main menu's Account View option, is the first of the four in-scope interactions and the entry to every drill-down. Proving its balance summary on the real request path — authentication, per-account authorization, stubbed upstream reads, cache and the 900 ms deadline — with the end-to-end HTTP client standing in for the deferred UI lets the UI run bind to a verified service. As the first consumer in build order, this epic owns the Compose test stack, the end-to-end harness and the performance harness that CS-02, CS-03 and CS-04 reuse and never re-implement.

## Requirements

- Compose test stack running the Card Servicing service, the five stub services (Card Data, Transaction Activity, Config, Reference Data, Auth), PostgreSQL, Redis and the one-shot fixture loader, started by `docker compose -f tests/docker-compose.yml up -d --wait` and shared by the end-to-end and performance suites — CS-01-001. Directive: testing requirements. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- End-to-end harness: the HTTP client standing in for the deferred UI, with a fixture bearer token, a stub request-log reader, the deadline check on every call and the End-to-end CI stage — CS-01-002. Directive: testing requirements. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Performance harness: a load runner on the JDK `java.net.http` client computing p50, p95 and p99 per endpoint, with a per-scenario report and the Performance CI stage — CS-01-003. Directive: testing requirements. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Balance summary end-to-end step, step 1 of the four-step flow for account `00000000001`: every COACTVWC account-section field checked against its contract field, the access-denial, invalid account number and malformed-token checks, and the `typeCode` hand-off to the category balance step — CS-01-004. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Balance summary SLA scenario at the planning baseline of 10 concurrent users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions — CS-01-005. Directive: 1-second SLA. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Customer Details (no story this iteration): the 17 Customer Details labels (Use Cases · COACTVW Customer Details section, filled by COACTVWC from Data Layouts · CVCUS01Y) are carried as flagged field gaps, set out in the table below and in the [screen-field coverage matrix](../../../data-model.md#screen-field-coverage-matrix). Use Cases · COACTVWC shows SSN as NNN-NN-NNNN. Customer id is also the baseline `customerId` (Data Layouts · CVACT03Y · XREF-CUST-ID, stored in `card_cross_reference.customer_id`, returned by `GetCardCrossReference`) but is not exposed by the balance summary. No contract field, message or operation is added for these labels in this run; delivery awaits a Requirements Analysis ID. Trace: CARD_REQ_TBD · Use Cases · COACTVWC

### Customer Details field gaps

| Label | Data Layouts · CVCUS01Y field | PICTURE | Status |
|---|---|---|---|
| Customer id | CUST-ID | 9(09) | Field gap — baseline field not exposed on balance summary; delivery awaits a Requirements Analysis ID |
| SSN | CUST-SSN | 9(09) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Date of birth | CUST-DOB-YYYY-MM-DD | X(10) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| FICO Score | CUST-FICO-CREDIT-SCORE | 9(03) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| First Name | CUST-FIRST-NAME | X(25) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Middle Name | CUST-MIDDLE-NAME | X(25) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Last Name | CUST-LAST-NAME | X(25) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Address | CUST-ADDR-LINE-1, CUST-ADDR-LINE-2 | X(50) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| State | CUST-ADDR-STATE-CD | X(02) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Zip | CUST-ADDR-ZIP | X(10) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| City | CUST-ADDR-LINE-3 | X(50) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Country | CUST-ADDR-COUNTRY-CD | X(03) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Phone 1 | CUST-PHONE-NUM-1 | X(15) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Government Issued Id Ref | CUST-GOVT-ISSUED-ID | X(20) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Phone 2 | CUST-PHONE-NUM-2 | X(15) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| EFT Account Id | CUST-EFT-ACCOUNT-ID | X(10) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Primary Card Holder Y/N | CUST-PRI-CARD-HOLDER-IND | X(01) | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |

## Acceptance Criteria

1. Step 1 of the four-step end-to-end flow, `GET /v1/accounts/00000000001/balance-summary` with `USER0001`'s fixture token `fixture-token-user0001`, returns 200 with `currentBalance` `"194.00"`, `creditLimit` `"2020.00"`, `cashCreditLimit` `"1020.00"`, `balancesByTypeGroup` holding exactly one element (`typeCode` `"01"`, `balance` `"0.00"`) and the response header `X-Data-Status` `FRESH` (CS-01-004).
2. The same request for account `00000000011` returns 403 `PERMISSION_DENIED` with a non-empty `traceId`, and the Card Data stub's request log shows no entry for that call, so no account data is read from upstream before access is allowed (CS-01-004).
3. The balance summary SLA scenario at 10 concurrent users × 100 sequential calls reports p50, p95 and p99 each below 1,000 ms over the successful requests, with zero non-200 responses and failure injection off (`STUB_LATENCY_MS` 0, `STUB_FAILURE_RATE` 0 on every stub) (CS-01-005).
4. `docker compose -f tests/docker-compose.yml up -d --wait` exits 0 only when the service, the five stubs, PostgreSQL and Redis report healthy and the fixture loader has exited 0, with sample accounts `00000000001`–`00000000050` and synthetic accounts `10000000001`–`10000010000` loaded and a load report showing zero skipped rows for all six entities (CS-01-001).
5. On every pull request the CI gate runs the Performance stage after Integration and the End-to-end stage after Performance and before Validation, and one failing scenario or one failing `e2e`-tagged test fails the gate (CS-01-002, CS-01-003).
6. The performance runner sends every request through the JDK `java.net.http` client only, and the build declares no added load-testing tool or library (CS-01-003).
7. Each of the 17 Customer Details labels in the table above appears, with its status, in the unmapped-field table of the [`CARD_REQ_TBD` gap list](../../../implementation-plan.md#card_req_tbd-gap-list).
8. On the Compose stack, `GET /v1/accounts/00000000000/balance-summary` with `fixture-token-user0001` returns 400 `INVALID_ARGUMENT` with the message "Account number must be a non zero 11 digit number" and every stub request log empty for that call, and `GET /v1/accounts/00000000001/balance-summary` with `fixture-token-malformed` returns 401 `UNAUTHENTICATED` with only a `ValidateToken` entry on the Auth stub (CS-01-004).
9. Every call of the balance summary end-to-end step receives its full answer below 1,000 ms of client-measured latency, and the completion log line matched to it shows `cutoffMs` at most 900 (CS-01-002, CS-01-004).

## Dependencies

- CS-05 Balance Summary Endpoint: CS-01-001 → CS-05-006
- CS-06 Category Balances Endpoint: CS-01-001 → CS-06-003
- CS-07 Transaction List Endpoint: CS-01-001 → CS-07-003
- CS-08 Transaction Detail Endpoint: CS-01-001 → CS-08-003
- CS-09 Card Data Integration: CS-01-001 → CS-09-007
- CS-10 Transaction Activity Integration: CS-01-001 → CS-10-008

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-01-001](stories/CS-01-001-compose-test-stack-with-stub-services.md) | Compose test stack with stub services | 3 | 3 | W3 |
| [CS-01-002](stories/CS-01-002-end-to-end-test-harness.md) | End-to-end test harness | 3 | 4 | W3 |
| [CS-01-003](stories/CS-01-003-performance-test-harness.md) | Performance test harness | 5 | 6 | W3 |
| [CS-01-004](stories/CS-01-004-account-balance-summary-end-to-end-step.md) | Account balance summary end-to-end step | 2 | 1 | W3 |
| [CS-01-005](stories/CS-01-005-account-balance-summary-sla-scenario.md) | Account balance summary SLA scenario | 2 | 1 | W3 |
| Total | 5 stories | 15 | 15 | W3 |

Owner: TBD

Status: TBD
