# CS-09: Card Data Integration

## Goal

Provide account, card cross-reference and category-balance data through a mocked Card Data service seeded from the CardDemo sample files and reachable only through one cached client.

## Business Justification

The balance summary (CS-05), the category balances endpoint (CS-06) and the transaction ownership check (CS-08) all read the account, card cross-reference and category-balance records that Data Layouts · CVACT01Y, Data Layouts · CVACT03Y and Data Layouts · CVTRA01Y define. Mocking them behind the Card Data contract keeps zero live external calls in this iteration, and decision D3 (Stub hosting and swap point) in the Technical Requirements document fixes the swap point: replacing the stub with the real Card Data service changes only the client's target address and credential source. The epic also owns the fixture-loader core, because it is the core's first consumer in build order, and the Transaction Activity (CS-10) and Reference Data (CS-13) loaders reuse it.

## Requirements

- One in-repo fixture loader core that parses fixed-width CardDemo records by 1-based copybook offset, decodes overpunch signs (`{`, `A`–`I`, `}`, `J`–`R`), reads both CardDemo timestamp forms (`YYYY-MM-DD HH:MM:SS.ffffff` and `YYYY-MM-DD-HH.MM.SS.ffffff`) as UTC, fails an entity's load on its first malformed line, skips foreign-key-violating rows with a per-entity count, loads each entity in its own transaction and writes a per-entity load report; every later entity loader registers through it — CS-09-001. Directive: in-repo fixture loader. Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-ID
- Account, card cross-reference and category-balance tables (`account`, `card_cross_reference`, `category_balance`) matching the data model exactly in columns, types, nullability, keys and index, created by a create-if-absent script that runs after the reference-data tables, with schema generation off at start-up — CS-09-002. Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-ID
- Card Data fixture load of the 50 sample accounts, 50 card cross-references and 50 category balances from byte-identical class-path copies of the CardDemo sample files, with zero skipped rows, 36-byte and 50-byte cross-reference lines both accepted, and address zip and group ID loaded strictly by copybook offset — CS-09-003. Trace: CARD_REQ_TBD · Data Layouts · CVACT03Y · XREF-ACCT-ID
- Card Data stub answering `GetAccount`, `GetCardCrossReference` and `ListCategoryBalances` from mock data per `card_data_service.proto` and `card-data-service.yaml`, with `NOT_FOUND` for an unknown account and `INVALID_ARGUMENT` for a malformed or all-zero account identifier — CS-09-004. Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-CURR-BAL
- Card Data client, the service's only path to Card Data, with account and category-balance caching (60 s fresh TTL, 300 s max stale age) and stale fallback under the upstream call policy; the cross-reference read is never cached and gets no stale fallback — CS-09-005. Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-CURR-BAL
- Card Data integration suite against real PostgreSQL and Redis containers, covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, the `UPSTREAM_MAX_RETRIES` cap, the stale fallback and its expiry, the fail-hard cross-reference read and a Redis outage — CS-09-006. Directive: testing requirements (integration suite). Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-ID
- Category-balance performance volume of 100,000 rows: 10,000 synthetic accounts with one card cross-reference and ten category balances each, generated deterministically after the sample load in an identifier range disjoint from it — CS-09-007. Directive: performance planning baseline. Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRAN-CAT-BAL

## Acceptance Criteria

1. The sample fixture load report shows `account` 50 loaded / 0 skipped, `card_cross_reference` 50 loaded / 0 skipped and `category_balance` 50 loaded / 0 skipped, and each of the three tables holds 50 rows whose account lies in the sample range `00000000001`–`00000000050`. With the synthetic rows of item 9, the full tables hold 10,050, 10,050 and 100,050 rows.
2. `GetAccount` for account `00000000001` returns `activeStatus` "Y", `currentBalance` "194.00", `creditLimit` "2020.00", `cashCreditLimit` "1020.00", `currentCycleCredit` "0.00", `currentCycleDebit` "0.00", `openDate` "2014-11-20", `expirationDate` and `reissueDate` "2025-05-20", `addressZip` "A000000000", and no `groupId`.
3. `GetCardCrossReference` for `00000000001` returns exactly one cross-reference: card `9680294154603697` and customer `000000001`.
4. `ListCategoryBalances` for `00000000001` returns exactly one entry: type `01`, category `0001`, balance "0.00".
5. A well-formed account absent from the data (for example `00000000099`) returns `NOT_FOUND` from each of the three operations. The stub request log shows exactly one attempt per operation, with no retry, and the standard error mapping turns the client's `NOT_FOUND` into 404.
6. With the Card Data stub unavailable, an account or category-balance read whose cache entry is within its 300 s max stale age is served as `STALE` with that entry's `cachedAt`. Past the max stale age, or with no entry, the read fails with 503 (`UNAVAILABLE` after retries) or 504 (`DEADLINE_EXCEEDED`).
7. A cross-reference read failure is never served from cache: it fails with 503 or 504, and the cross-reference read sends no cache command.
8. Each Card Data attempt times out at the lesser of 300 ms and the remaining request budget. Retries are at most two, with 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only. With a stub failure rate of 1.0, the integration suite logs exactly three attempts per call at the retry cap, and every failure case logs `cutoffMs` of at most 900 with a caller-measured response below 1,000 ms.
9. The synthetic volume holds 10,000 accounts `10000000001`–`10000010000`, one card each, and 100,000 category balances, with zero skipped rows and no identifier shared with the sample range `00000000001`–`00000000050`.
10. The Card Servicing service reads account, cross-reference and category-balance data only through the Card Data client and opens no connection to the Card Data store, and the Card Data stub never reads the cache: it answers with no Redis server reachable.

## Dependencies

- CS-11 Config Service Integration: CS-09-001 → CS-11-001, CS-09-004 → CS-11-002, CS-09-005 → CS-11-003, CS-09-005 → CS-11-004, CS-09-006 → CS-11-007
- CS-13 Reference Data Integration: CS-09-002 → CS-13-001

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-09-001](stories/CS-09-001-fixture-loader-core-and-load-report.md) | Fixture loader core and load report | 3 | 6 | W0 |
| [CS-09-002](stories/CS-09-002-account-cross-reference-and-category-balance-tables.md) | Account, cross-reference and category balance tables | 2 | 7 | W0 |
| [CS-09-003](stories/CS-09-003-card-data-fixture-load.md) | Card Data fixture load | 3 | 10 | W0 |
| [CS-09-004](stories/CS-09-004-card-data-stub-service.md) | Card Data stub service | 3 | 4 | W1 |
| [CS-09-005](stories/CS-09-005-card-data-client-with-account-and-category-balance-caching.md) | Card Data client with account and category balance caching | 3 | 5 | W1 |
| [CS-09-006](stories/CS-09-006-card-data-integration-suite.md) | Card Data integration suite | 3 | 2 | W1 |
| [CS-09-007](stories/CS-09-007-category-balance-performance-volume.md) | Category balance performance volume | 2 | 3 | W1 |
| Total | 7 stories | 19 | 37 | W0, W1 |

Owner: TBD

Status: TBD
