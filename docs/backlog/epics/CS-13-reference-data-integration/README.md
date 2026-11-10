# CS-13: Reference Data Integration

## Goal

Provide transaction type and category descriptions as independently loaded data through a mocked Reference Data service and one cached client that degrades to null descriptions.

## Business Justification

The balance summary and category balance views label each type and category code with its description, whose structure Data Layouts · CVTRA03Y and Data Layouts · CVTRA04Y define and whose rows come from Reference Tables · TRANTYPE file and Reference Tables · TRANCATG file. A type or category change must be a data load by the data owner with no deploy, and a description that cannot be fetched must never block a balance: the response degrades and never fails. Every reference read reaches a mock-data-backed stub with zero live external calls, and the real Reference Data service later replaces that stub by changing only the client's target address and credential source.

## Requirements

- Transaction type and transaction category reference tables (`transaction_type`, `transaction_category`, each category linked to its type) seeded from `card-servicing-service/src/main/resources/reference-data/transaction-types.yaml` (7 rows from Reference Tables · TRANTYPE file) and `card-servicing-service/src/main/resources/reference-data/transaction-categories.yaml` (18 rows from Reference Tables · TRANCATG file), with descriptions trimmed and at most 50 characters (X(50)), loaded before the Card Data tables; the type-to-category mapping is data, not code — CS-13-001. Trace: CARD_REQ_TBD · Data Layouts · CVTRA03Y · TRAN-TYPE-DESC
- Mocked Reference Data service answering `ListTransactionTypes` and `ListTransactionCategories` from the loaded tables on every request, per `card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto` and `card-servicing-service/src/main/resources/openapi/reference-data-service.yaml` — CS-13-002. Trace: CARD_REQ_TBD · Data Layouts · CVTRA04Y · TRAN-CAT-TYPE-DESC
- Reference Data client with read-through cache entries `cs:v1:reftypes` and `cs:v1:refcats` (3,600 s fresh TTL, 86,400 s max stale age) under the upstream call policy, returning a per-list result that states whether reference data is usable, consumed by CS-05-005, CS-06-001 and CS-06-002: a usable stale entry gives descriptions with `dataStatus` `STALE`, and nothing usable gives null descriptions with `PARTIAL`, never a request failure — CS-13-003. Trace: CARD_REQ_TBD · Data Layouts · CVTRA03Y · TRAN-TYPE-DESC
- Container-backed Reference Data integration suite covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, the `UPSTREAM_MAX_RETRIES` cap, data reload visibility and the `PARTIAL` fallback — CS-13-004. Directive: testing requirements (integration suite). Trace: CARD_REQ_TBD · Data Layouts · CVTRA04Y · TRAN-CAT-TYPE-DESC

## Acceptance Criteria

1. Loading the packaged seed files writes 7 rows to `transaction_type` and 18 rows to `transaction_category`, and the load report shows 0 skipped rows for both entities.
2. No stored type or category description exceeds 50 characters.
3. Changing one description in a seed file and rerunning the loader updates that table row with no build or deploy, and the Reference Data stub returns the new description on its next request.
4. With the Reference Data stub answering `OK`, the Card Servicing service returns the changed description no later than 3,600 s after the reload, the fresh TTL of `cs:v1:reftypes` and `cs:v1:refcats`.
5. With the stub answering `UNAVAILABLE` and no cache entry within the 86,400 s max stale age, balance summary and category balance responses return 200 with every description `null` in REST and unset in gRPC, and `dataStatus` `PARTIAL`.
6. With the stub answering `UNAVAILABLE` and a cache entry past its 3,600 s fresh TTL but within the 86,400 s max stale age, descriptions come from that entry and `dataStatus` is `STALE`.
7. Every Reference Data call the service makes reaches the stub, and zero live external calls exist.
8. `./gradlew :card-servicing-service:integrationTest` passes the Reference Data suite, which covers a stub exception, latency above `UPSTREAM_TIMEOUT_MS` (300 ms) and the `UPSTREAM_MAX_RETRIES` (2) cap.

## Dependencies

- CS-09 Card Data Integration: CS-13-001 → CS-09-001
- CS-11 Config Service Integration: CS-13-002 → CS-11-002, CS-13-003 → CS-11-003, CS-13-003 → CS-11-004, CS-13-004 → CS-11-007

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-13-001](stories/CS-13-001-reference-data-tables-and-seed-files.md) | Reference data tables and seed files | 2 | 5 | W0 |
| [CS-13-002](stories/CS-13-002-reference-data-stub-service.md) | Reference Data stub service | 3 | 4 | W1 |
| [CS-13-003](stories/CS-13-003-reference-data-client-with-cache-and-description-fallback.md) | Reference Data client with cache and description fallback | 3 | 5 | W1 |
| [CS-13-004](stories/CS-13-004-reference-data-integration-suite.md) | Reference Data integration suite | 3 | 2 | W1 |
| Total | 4 stories | 11 | 16 | W0, W1 |

Owner: TBD

Status: TBD
