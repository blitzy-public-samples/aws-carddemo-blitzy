# CS-10-001: Transaction table with account keyset index

Parent epic: [CS-10 Transaction Activity Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want my transactions stored keyed by account in newest-first order, so that each page costs one index seek whatever the total volume.
>
> > SCOPE-QUESTION (Resolved): the one-index-seek page cost holds for an unfiltered page only. A page filtered by type code or category code scans the account's entries in newest-first order, rejecting rows until page size plus one entries match, so its cost grows with the account's history divided by the filter's selectivity, not with total volume. The transaction list performance scenario CS-03-003 measures that cost against the 150 ms p99 trigger of decision D4, and the three filter-leading indexes that answer the trigger stay outside this story.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-ID
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: one create-if-absent schema script for the PostgreSQL 16 table `transaction_record`, the Spring Data JPA entity and repository through which the transaction loader writes it and the Transaction Activity stub reads it, and a schema-mapping test. The record structure is `Data Layouts · CVTRA05Y`: one column for each of its 13 data fields, TRAN-ID through TRAN-PROC-TS, with the trailing FILLER X(20) not stored, plus the denormalized `account_id` of decision D4. Every column name, type, nullability, key and index is the one `docs/data-model.md` defines: `char(n)` stays `char(n)`, a decimal becomes `numeric`, a `timestamp(6)` UTC becomes `timestamptz(6)`, and no ORM schema generation runs. Target paths (4 files):
>
> - `card-servicing-service/src/main/resources/db/03-transaction-record.sql` creates the table and its indexes, each statement create-if-absent (`create table if not exists`, `create index if not exists`) so re-applying changes nothing:
>   - Columns in the data model's order, every one NOT NULL: `transaction_id` `char(16)`, `type_code` `char(2)`, `category_code` `char(4)`, `source` `varchar(10)`, `description` `varchar(100)`, `amount` `numeric(11,2)`, `merchant_id` `char(9)`, `merchant_name` `varchar(50)`, `merchant_city` `varchar(50)`, `merchant_zip` `varchar(10)`, `card_number` `char(16)`, `original_timestamp` `timestamptz(6)`, `processed_timestamp` `timestamptz(6)` and `account_id` `char(11)`.
>   - Primary key `(transaction_id)`, which serves the single-transaction lookup.
>   - Foreign keys `account_id` → `account.account_id` (decision D4), `card_number` → `card_cross_reference.card_number` and `(type_code, category_code)` → `transaction_category (type_code, category_code)`.
>   - Keyset index `(account_id, processed_timestamp desc, transaction_id desc)` for the per-account newest-first page, and the baseline card index `(card_number)`, kept, which also serves the card foreign key. No other index is created.
>   - The `03-` prefix sorts this script after `01-reference-data-tables.sql` of CS-13-001 and `02-card-data-tables.sql` of CS-09-002, which create the `transaction_category`, `account` and `card_cross_reference` tables its foreign keys reference. The schema step of CS-13-001 applies every `db/` script in file-name order before each load, so no loader code changes here.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/transactionactivity/TransactionRecordEntity.java`: the JPA entity mapping every column by its data-model name. Identifiers, codes and the card number map as fixed-length text that keeps its leading zeros, the amount as a scale-2 decimal at precision 11, both timestamps as UTC instants at microsecond precision, and `account_id` as a plain column with no navigation to the account. Schema generation stays off through the `hibernate.hbm2ddl.auto` = `none` pin that CS-09-002 sets for the application context, so `application.yml` is not edited.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/transactionactivity/TransactionRecordRepository.java`: the Spring Data repository keyed by `transaction_id`, over the store connection declared by CS-09-001. It serves the transaction loader (CS-10-002) and the Transaction Activity stub (CS-10-004, CS-10-005), and it declares no paging query, because the keyset listing query belongs to CS-10-004.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/transactionactivity/TransactionRecordSchemaTest.java`: with no store connection, it builds the entity mapping for the PostgreSQL 16 dialect and checks every mapped column name, length, precision, scale and nullability against the data-model table, checks that both timestamps map to `timestamptz(6)`, and checks that the effective schema-generation setting is `none`. The test needs no store because the Testcontainers test dependencies reach `card-servicing-service/build.gradle` only with CS-11-007 in W1, and a Testcontainers import in this W0 story would stop the shared test sources compiling. The container-backed proof of AC1–AC8 runs in the Transaction Activity integration suite CS-10-007, which applies the `db/` scripts to a `postgres:16.6` container through `IntegrationTestSupport`.
>
> In scope:
>
> - The `transaction_record` table with its 14 columns, types and nullability.
> - Its primary key and its three foreign keys.
> - The keyset index and the kept card-number index.
> - The create-if-absent script and its place in the `db/` name order.
> - The entity and the repository.
> - The schema-mapping test.
>
> Not in this story:
>
> - Loading rows and deriving `account_id` from the cross-reference (CS-10-002).
> - The keyset listing query and the "account unknown" check (CS-10-004).
> - The single-transaction lookup (CS-10-005).
> - Synthetic transaction volume (CS-10-008).
> - The container-backed suite (CS-10-007) and the Testcontainers test dependencies (CS-11-007).
> - The three filter-leading indexes held in the plan's Recommendations behind the D4 trigger.
> - Business write operations, of which this iteration has none.
>
> ### 2. Acceptance Criteria
>
> - AC1: Applying the `db/` scripts in file-name order to an empty PostgreSQL 16 database creates `transaction_record` whose columns, read back from the database catalog with their order, types, lengths, precision, scale and nullability, equal the `transaction_record` table in `docs/data-model.md`: one column per `Data Layouts · CVTRA05Y` data field plus `account_id`, 14 in all, every column NOT NULL, primary key `(transaction_id)` and no further column. Re-applying the scripts to that database raises no error and leaves every definition unchanged.
> - AC2: With account `00000000001`, its card `9680294154603697` and category `01`/`0001` stored, a row naming account `99999999999`, which `account` does not hold, is rejected with a foreign-key violation (SQLSTATE `23503`), and the row count of `transaction_record` stays unchanged.
> - AC3: With the AC2 parent rows stored, a row naming card `0000000000000000`, which `card_cross_reference` does not hold, is rejected with a foreign-key violation (SQLSTATE `23503`), and no row is stored.
> - AC4: With the AC2 parent rows stored, a row naming the type and category pair `09`/`0001`, which `transaction_category` does not hold, is rejected with a foreign-key violation (SQLSTATE `23503`), and no row is stored.
> - AC5: Read back from the PostgreSQL catalog, `transaction_record` carries an index on `(account_id, processed_timestamp desc, transaction_id desc)` with exactly that column order and those directions.
> - AC6: Read back from the PostgreSQL catalog, `transaction_record` carries the baseline index on `(card_number)`, and the table has no index beyond its primary key, that index and the AC5 keyset index.
> - AC7: A row for transaction `0000000532120892` with type `03`, category `0001`, amount `-70.77`, card `9680294154603697`, account `00000000001`, original timestamp `2022-06-10T19:27:53.000000Z` and processed timestamp `2024-03-05T14:07:09.123456Z`, inserted with the AC2 parent rows and category `03`/`0001` stored, reads back through `TransactionRecordRepository` with every field equal to the inserted value, the amount at scale 2 and both timestamps at microsecond precision in UTC.
> - AC8: With the AC7 row stored, a second row carrying transaction ID `0000000532120892` is rejected with a primary-key violation (unique violation, SQLSTATE `23505`), and the stored row is unchanged.
>
> ### 3. Dependencies
>
> - CS-09-002 — creates the account and cross-reference tables the new foreign keys reference.
>
> Risk: drift between the script and the data model breaks every later transaction story.

## 4. Design

- Diagram: [Logical architecture](../../../../architecture/logical-architecture.mmd)
- Data model: [transaction_record](../../../../data-model.md#transaction_record)
- Data model: [Denormalization of transaction account](../../../../data-model.md#denormalization-of-transaction-account)
- TRD: [D4 Transaction-to-account access](../../../../technical-requirements.md#d4-transaction-to-account-access)
- Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass; at least 80% line coverage per module. `TransactionRecordSchemaTest` covers, with no store, the column mapping behind AC1, the scale and precision behind AC7 and the schema-generation setting.
- Integration — Applies, through the Transaction Activity integration suite CS-10-007, which applies this script to the `postgres:16.6` container and proves AC1–AC8 against the database catalog and real rows. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic (CS-10-007 for this epic) covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — schema only; page latency is proven by CS-03-003 on the CS-10-008 volume. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:performanceTest`, `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms; applies to balance-retrieval and drill-down stories.
- End-to-end — Not applicable — no request path in this story. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:e2eTest`, `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — account column with a composite keyset index.

Rationale: copying each transaction's account onto its row lets one composite keyset index serve every unfiltered newest-first page as a single seek, whatever the total volume; a filtered page still scans that one account's history, and the transaction list performance scenario measures it against the filter trigger.

- Rejected: Design Option B, the card-number index with a sort on every page — page cost grows with each account's history.
- Rejected: a join through the cross-reference record on every page — the same ordering problem, ranked second among the transaction-to-account access alternatives.

### Shared by both options

- Topology: one table in the relational store, written only by the fixture loader and read only by the transaction activity stub.
- Naming: the table name avoids the reserved word for a database transaction, and the columns follow the field order of the mainframe transaction record.
- Addressing: the transaction identifier is the primary key and serves the single-transaction lookup.
- Isolation: the card servicing service never connects to the store and reaches transactions only through the transaction activity stub.
- Fallback: none at schema level, because the fixture loader skips and counts a row whose parent is missing before it reaches the table.
- Provisioning: the script runs after the reference, account and cross-reference tables, and each statement is skipped when its object already exists.
- Cut-over: the real transaction activity service replaces the stub by a change of target address and credential source alone, and ownership of this table after that swap stays an open question in the plan's recommendations.
- Failures: a key violation rejects the insert and leaves every stored row untouched.

### Design Option A — account column with a composite keyset index

Each transaction row carries the account of its card, copied from the cross-reference record when the fixture loader writes the row. A composite keyset index orders one account's rows by processed timestamp, newest first, and then by transaction identifier, newest first. An unfiltered page is one seek to the account and the last returned position, followed by a read of page size plus one entries. The baseline card-number index stays for the card link.

How it behaves:

- The store refuses a row whose account, card, or type and category pair has no parent row.
- A page seeks to the account and the last returned position, then reads entries in index order with no sort.
- Rows that share a processed timestamp, as every sample row does, take their order from the transaction identifier.
- The one entry read past the page size tells the listing stub whether a further page exists.
- The card-number index serves the card link and any lookup by card.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Account column copied from the cross-reference record at load (vs a join on every page) | one index seek per unfiltered page | about twelve extra bytes per row |
| 2 | Processed timestamp then transaction identifier, both descending, as index order (vs the timestamp alone) | a stable order when timestamps repeat | wider index entries |
| 3 | Card-number index kept (vs dropped) | the baseline card lookup stays | extra index upkeep during the load |

Effort and risk: small effort, low risk while the iteration has no writes.

### Design Option B — card-number index with a sort on every page

The table keeps only the baseline columns and the baseline card-number index. A page query gathers the account's cards from the cross-reference records, reads every matching transaction through the card-number index and sorts the matches newest first on each request.

How it differs:

- No account column exists, so the store enforces no account key on the row.
- Every page sorts the account's matching rows before it cuts the page.
- Page cost grows with the account's history, not with the page size.
- The cursor still holds the processed timestamp and the transaction identifier, but each page repeats the sort.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Card-number index only (vs an added account column) | no copied value to keep in step | every page sorts the account's history |
| 2 | Cards gathered from the cross-reference records per request (vs the account stored on the row) | the card link stays the only path to the account | an extra lookup on every page |

Effort and risk: small effort, high latency risk at the one-million-row baseline.

### Recommendation

- Option: Design Option A
- Why: an unfiltered page reads page size plus one index entries whatever the total volume.
- Strongest argument against: the copied account column can drift from the cross-reference record once writes arrive.
- Shared items that do not decide the choice: the table, its primary key, its card and category foreign keys and the card-number index.
- Flip conditions: cards moving between accounts, or writes in a later iteration without a rule that keeps the account column in step.

## Design Validation

### Option A

Settled assumptions:

- No write operation exists this iteration beyond the fixture loader.
- Each sample card belongs to exactly one account.
- The reference and cross-reference tables exist before this script runs.
- The schema step applies the scripts in name order before each load.

Open assumptions:

- Whether a later iteration adds writes that change a card's account Open → the Requirements Analysis author fixes the write scope.
- Whether the container-backed catalog checks run before the listing stub relies on the keyset index Open → the integration suite story.
- Who owns this table once the real transaction activity service replaces the stub Open → the data store ownership decision recorded in the plan's recommendations.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the script copies every definition from the data model and skips what already exists |
| AC2 | Holds | the store enforces the account foreign key |
| AC3 | Holds | the store enforces the card foreign key |
| AC4 | Holds | the store enforces the type and category foreign key |
| AC5 | Holds | the script declares the keyset index in that column order and direction |
| AC6 | Holds | the script keeps the card-number index and adds no other |
| AC7 | Holds | the entity maps the amount at scale 2 and both timestamps at microsecond precision |
| AC8 | Holds | the transaction identifier is the primary key |

### Option B

Settled assumptions:

- No write operation exists this iteration beyond the fixture loader.
- Each sample card belongs to exactly one account.
- The cross-reference records give every account its cards.

Open assumptions:

- Whether a page sort stays within the per-attempt timeout on the large-history account Open → the transaction list performance scenario.
- Who owns this table once the real transaction activity service replaces the stub Open → the data store ownership decision recorded in the plan's recommendations.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | the table has no account column |
| AC2 | Breaks | no account key exists to violate |
| AC3 | Holds | the store enforces the card foreign key |
| AC4 | Holds | the store enforces the type and category foreign key |
| AC5 | Breaks | no composite keyset index exists |
| AC6 | Holds | the card-number index is the only index beyond the primary key |
| AC7 | Conditional | the round trip holds only for the fields the table keeps |
| AC8 | Holds | the transaction identifier is the primary key |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-09-002 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Transaction Activity Integration |
| Epic Link | CS-10 |
| Story Points | 2 |
| Priority | High |
| Components | TBD |
| Fix Version/s | TBD |
| Team | TBD |
| Assignee | TBD |
| Reporter | TBD |
| Labels | TBD |
| Sprint | TBD |
| Due Date | TBD |
| Environment | TBD |
| Persona | TBD |
| Estimated Effort | TBD |
