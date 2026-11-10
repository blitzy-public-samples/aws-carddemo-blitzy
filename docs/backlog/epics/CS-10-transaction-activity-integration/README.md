# CS-10: Transaction Activity Integration

## Goal

Provide per-account, newest-first, keyset-paged transaction listing and single-transaction lookup through a mocked Transaction Activity service and one cached client.

## Business Justification

The transaction list drill-down (Use Cases · COTRN00C) and the transaction detail drill-down (Use Cases · COTRN01C) read every transaction through this integration, whose record structure Data Layouts · CVTRA05Y defines. Decision D4 (Transaction-to-account access) in the Technical Requirements document stores the account on each transaction with a composite keyset index, so every unfiltered page stays one index seek at the 1,000,000-transaction planning baseline. Decision D3 (Stub hosting and swap point) lets every later epic run against the mocked service with zero live external calls, and replacing the stub with the real Transaction Activity service changes only the client's target address and credential source.

## Requirements

- Transaction table holding every Data Layouts · CVTRA05Y field plus the denormalized account column, with the account keyset index and the baseline card-number index — CS-10-001. Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-ID
- Fixture load of the 300 sample transactions with derived processed timestamps and the account resolved through the card cross-reference — CS-10-002. Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-PROC-TS
- Opaque, versioned page token bound to the account and the type and category filters — CS-10-003. Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Stub `ListTransactions` returning one account's transactions newest first in keyset pages with optional type and category filters — CS-10-004. Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Stub `GetTransaction` returning one transaction by identifier — CS-10-005. Trace: CARD_REQ_TBD · Use Cases · COTRN01C
- Transaction Activity client, the service's only path to transaction data, with the upstream call policy and transaction caching — CS-10-006. Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-ID
- Transaction Activity integration suite against real containers — CS-10-007 (directive: testing requirements). Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-ID
- Synthetic volume of 1,000,000 transactions with a large-history account and a sparse filter pair — CS-10-008 (directive: performance planning baseline). Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-ID

## Acceptance Criteria

1. The sample load report shows `transaction_record` with 300 loaded, 0 skipped and 300 derived processed timestamps.
2. Each sample account `00000000001`–`00000000050` holds exactly six transactions, and exactly 50 sample amounts are negative.
3. `ListTransactions` for account `00000000001` returns, newest first, `0000000776200014`, `0000000755736377`, `0000000624335286`, `0000000555363230`, `0000000532120892`, `0000000503557384` (all six share the timestamp `"2022-06-10T19:27:53.000000Z"`, so order falls to transaction ID descending), and the last page carries an empty `next_page_token`.
4. A page token changed by one character, or issued for account `00000000001` and replayed on `00000000002`, is rejected with `INVALID_ARGUMENT`, which the service returns as 400 with a `details` entry for `pageToken`.
5. `ListTransactions` for account `00000000099`, which has no cross-reference record, returns `NOT_FOUND`; account `10000009002`, which has a cross-reference and no transactions, returns an empty list.
6. `GetTransaction` for `0000000776200014` returns amount `"985.22"` and card `9680294154603697`; `9999999999999999` returns `NOT_FOUND`.
7. A fresh cache hit in the client makes no stub call, and a list or detail failure after retries returns 503 or 504 with a logged `cutoffMs` of at most 900 and a client-measured response below 1,000 ms.
8. The integration suite passes against PostgreSQL 16.6 and Redis 7.4.2 containers, covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
9. The synthetic volume adds exactly 1,000,000 transactions with exact per-account counts, 20 of them on account `10000000001` carrying type `05` and category `0001`.
10. The plan of the unfiltered first-page query for account `10000000001` reads the keyset index with no sort step.

## Dependencies

- CS-05 Balance Summary Endpoint: CS-10-003 → CS-05-002
- CS-09 Card Data Integration: CS-10-001 → CS-09-002, CS-10-002 → CS-09-003, CS-10-008 → CS-09-007
- CS-11 Config Service Integration: CS-10-004 → CS-11-002, CS-10-006 → CS-11-003, CS-10-006 → CS-11-004, CS-10-007 → CS-11-007

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-10-001](stories/CS-10-001-transaction-table-with-account-keyset-index.md) | Transaction table with account keyset index | 2 | 4 | W0 |
| [CS-10-002](stories/CS-10-002-transaction-fixture-load.md) | Transaction fixture load | 3 | 4 | W0 |
| [CS-10-003](stories/CS-10-003-page-token-codec.md) | Page token codec | 3 | 3 | W1 |
| [CS-10-004](stories/CS-10-004-transaction-activity-stub-transaction-listing-with-keyset-paging.md) | Transaction Activity stub transaction listing with keyset paging | 5 | 4 | W1 |
| [CS-10-005](stories/CS-10-005-transaction-activity-stub-single-transaction-lookup.md) | Transaction Activity stub single transaction lookup | 2 | 3 | W1 |
| [CS-10-006](stories/CS-10-006-transaction-activity-client-with-transaction-caching.md) | Transaction Activity client with transaction caching | 3 | 4 | W1 |
| [CS-10-007](stories/CS-10-007-transaction-activity-integration-suite.md) | Transaction Activity integration suite | 3 | 2 | W1 |
| [CS-10-008](stories/CS-10-008-transaction-performance-volume.md) | Transaction performance volume | 2 | 3 | W1 |
| Total | 8 stories | 23 | 27 | W0, W1 |

Owner: TBD

Status: TBD
