# CS-09-002: Account, cross-reference and category balance tables

Parent epic: [CS-09 Card Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a data owner, I want the account, cross-reference and category-balance tables to match the data model exactly, so that fixture data and stubs share one schema.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-ID
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: one create-if-absent schema script for the three Card Data tables `account`, `card_cross_reference` and `category_balance`, and the entity mapping through which the Card Data stub reads them and the Card Data loaders write them. The record structures are `Data Layouts · CVACT01Y` (account), `Data Layouts · CVACT03Y` (card cross-reference) and `Data Layouts · CVTRA01Y` (category balance), whose type and category key names a `Data Layouts · CVTRA04Y` row. Every column, type, nullability, primary key, foreign key and index is the one `docs/data-model.md` defines, and no ORM schema generation runs. Target paths:
>
> - `card-servicing-service/src/main/resources/db/02-card-data-tables.sql` creates the three tables, each statement create-if-absent (`create table if not exists`, `create index if not exists`) so re-applying changes nothing:
>   - `account`: `account_id` `char(11)` NOT NULL, primary key; `active_status` `char(1)` NOT NULL; `current_balance`, `credit_limit` and `cash_credit_limit` `numeric(12,2)` NOT NULL; `open_date`, `expiration_date` and `reissue_date` `date` NOT NULL; `current_cycle_credit` and `current_cycle_debit` `numeric(12,2)` NOT NULL; `address_zip` `varchar(10)` NOT NULL; `group_id` `varchar(10)` NULL.
>   - `card_cross_reference`: `card_number` `char(16)` NOT NULL, primary key; `customer_id` `char(9)` NOT NULL, which stores the baseline `customerId`; `account_id` `char(11)` NOT NULL, with the foreign key `account_id` → `account.account_id`; an index on `(account_id)` for the account-to-card lookup, which also decides "account unknown" for the Transaction Activity stub.
>   - `category_balance`: `account_id` `char(11)` NOT NULL; `type_code` `char(2)` NOT NULL; `category_code` `char(4)` NOT NULL; `balance` `numeric(11,2)` NOT NULL; primary key `(account_id, type_code, category_code)`; foreign keys `account_id` → `account.account_id` and `(type_code, category_code)` → `transaction_category (type_code, category_code)`.
>   - Columns run in the data model's order. No index beyond those the data model lists is created, and no trailing FILLER is stored.
>   - The `02-` prefix sorts this script after `01-reference-data-tables.sql` of CS-13-001, which creates `transaction_category`, and ahead of the transaction-table script of CS-10-001, whose foreign keys reference `account` and `card_cross_reference`. The fixture loader applies every `db/` script in file-name order before loading, through the schema step of CS-13-001, so no loader code changes here.
> - JPA entities for the three tables and their Spring Data repositories, under `card-servicing-service/src/main/java/com/example/cardservicing/integration/carddata/persistence/`:
>   - Each entity maps every column by its data-model name: identifiers and codes as fixed-length text that keeps its leading zeros, amounts as scale-2 decimals at the column's precision, dates as calendar dates, and `group_id` as an optional value. The category balance entity carries the three-column composite key.
>   - The repositories serve the Card Data stub (CS-09-004) and the Card Data loaders (CS-09-003, CS-09-007), over the store connection declared by CS-09-001.
>   - Stub start-up never creates or alters schema: Hibernate DDL generation is off (`spring.jpa.hibernate.ddl-auto=none`). The persistence package pins the effective Hibernate setting `hibernate.hbm2ddl.auto` to `none` itself rather than leaving it to the store-type default, so it holds whatever store a context starts against, and `application.yml` is not edited.
> - The entity-mapping test under `card-servicing-service/src/test/java/com/example/cardservicing/integration/carddata/persistence/`: with no store connection, it builds the entity mapping for the PostgreSQL 16 dialect and checks every mapped column name, length, precision, scale and nullability against the data-model tables, and checks that the effective schema-generation setting is `none`. The container-backed proof of AC1–AC10 runs in the Card Data integration suite CS-09-006, which applies this script to a `postgres:16.6` container.
>
> In scope:
>
> - The three tables with their columns, types, nullability, keys and index.
> - The create-if-absent script and its place in the `db/` name order.
> - The entities and repositories for the three tables.
> - The entity-mapping test.
> - Schema generation disabled at start-up.
>
> Not in this story:
>
> - The reference tables `transaction_type` and `transaction_category`, and the schema step that applies the `db/` scripts (CS-13-001).
> - The transaction table `transaction_record` (CS-10-001).
> - The store connection and the loader core (CS-09-001).
> - Loading data into the three tables (CS-09-003).
> - Stub reads (CS-09-004).
> - Customer-record columns from `Data Layouts · CVCUS01Y`, which are flagged gaps per the data model.
>
> ### 2. Acceptance Criteria
>
> - AC1: Applied to an empty PostgreSQL 16 database that already holds `transaction_type` and `transaction_category` from `01-reference-data-tables.sql`, the script completes without error and the database then holds `account`, `card_cross_reference` and `category_balance`. Applied inside one transaction to an empty database without `transaction_category`, the script fails with an undefined-table error naming `transaction_category`, and after the rollback the database holds none of the three tables.
> - AC2: Read back from the database catalogue, the column names, data types with length, precision and scale, and nullability of `account`, `card_cross_reference` and `category_balance` equal the data-model tables: 12, 3 and 4 columns, every column NOT NULL except `account.group_id`, `char` lengths 11, 1, 16, 9, 2 and 4 as listed there, every account amount `numeric(12,2)`, `balance` `numeric(11,2)`, and no further column.
> - AC3: Read back from the database catalogue, the primary keys are `account (account_id)`, `card_cross_reference (card_number)` and `category_balance (account_id, type_code, category_code)` in that column order. The foreign keys are `card_cross_reference.account_id` → `account`, `category_balance.account_id` → `account` and `category_balance (type_code, category_code)` → `transaction_category`, and no other foreign key exists on the three tables. The only index beyond the primary keys is `card_cross_reference (account_id)`.
> - AC4: A `card_cross_reference` row for card `9680294154603697` naming account `99999999999`, which `account` does not hold, is rejected by the database with a foreign-key violation (SQLSTATE `23503`) and no row is stored. A `category_balance` row naming the same missing account is rejected the same way.
> - AC5: With account `00000000001` stored, a `category_balance` row for type `09` and category `0001`, a pair absent from `transaction_category`, is rejected by the database with a foreign-key violation (SQLSTATE `23503`), while the row for `01`/`0001` is stored. A second `01`/`0001` row for the same account is rejected with a unique violation (SQLSTATE `23505`).
> - AC6: In each `numeric(12,2)` account amount column, `9999999999.99` and `-9999999999.99` store and read back unchanged, and `10000000000.00` is rejected with a numeric field overflow (SQLSTATE `22003`). In `category_balance.balance`, `numeric(11,2)`, `999999999.99` and `-999999999.99` store and read back unchanged, and `1000000000.00` is rejected the same way.
> - AC7: An account row whose `account_id` is the 12-character `000000000012` is rejected with a value-too-long error (SQLSTATE `22001`). An account row with `current_balance` NULL is rejected with a not-null violation (SQLSTATE `23502`). An account row with `group_id` NULL is stored.
> - AC8: Re-applying the script to a database that already holds the three tables and the rows of account `00000000001`, its card and its category balance raises no error. Every column, key and index definition still equals AC2 and AC3, and the row counts and values are unchanged.
> - AC9: An application context holding the three entities and their repositories starts against an empty PostgreSQL 16 database with no script applied, and the database still holds no table in its `public` schema afterwards. Started against a database that holds the three tables, it leaves every definition equal to AC2 and AC3. The effective schema-generation setting is `none` in both starts.
> - AC10: The entities map every column of the three tables, with no unmapped column and no mapped attribute lacking a column. A round trip through the repositories reads back equal, field for field:
>   - Account `00000000001`: active `Y`, current balance `194.00`, credit limit `2020.00`, cash credit limit `1020.00`, open `2014-11-20`, expiration and reissue `2025-05-20`, cycle credit and debit `0.00`, zip `A000000000`, no group.
>   - Its card `9680294154603697` with customer `000000001`.
>   - Its category balance `01`/`0001` at `0.00`.
>   - The read-back current balance is `194.00` at scale 2, the account identifier is the 11-character `00000000001` with its leading zeros, the customer identifier keeps its leading zeros, and the group is NULL.
>
> ### 3. Dependencies
>
> - CS-13-001 — creates transaction_category, which category_balance references by foreign key, and the reference-data script this script runs after.
>
> Risk: Column types that drift from the data model break the loader and stub reads silently, so AC2 and AC3 compare the database catalogue with the data model.

## 4. Design

- Diagram: [Logical architecture](../../../../architecture/logical-architecture.mmd)
- Data model: [Account](../../../../data-model.md#account), [Card cross reference](../../../../data-model.md#card_cross_reference), [Category balance](../../../../data-model.md#category_balance), [Entity relationship diagram](../../../../data-model.md#entity-relationship-diagram)
- Technical requirements: [D1 Data store](../../../../technical-requirements.md#d1-data-store)

Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; pass when all tests pass with ≥80% line coverage per module. The entity-mapping test builds the mapping with no store and covers the column mapping of AC10 and the schema-generation setting of AC9.
- Integration: Applies, through the Card Data integration suite CS-09-006, which applies this script to the `postgres:16.6` container and proves AC1–AC10 against the database catalogue and real rows. Command `./gradlew :card-servicing-service:integrationTest`; pass when all suites pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — this story is schema only, with no request path. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no UI step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — versioned plain schema scripts applied before the load.

Rationale: a hand-written script copied from the data model and applied by the loader before any row loads keeps the data model the single schema source for the loader and the stubs.

- Rejected: Option B, tables generated by the object mapper at stub start-up, because the object mapper derives column types that drift from the data model.

### Shared by both options

- Topology: one relational store shared by the loader and the stubs.
- Naming: lower-case names copied from the data model.
- Addressing: the store is reached only by the loader and the stubs, never by the service.
- Isolation: these three tables only, with the reference tables owned by the reference data tables story.
- Fallback: none, because a stub has nothing to read until the tables exist.
- Provisioning: schema in a fixed order of reference, card data, then transactions.
- Cut-over: the real card data owner keeps its own store, and the scripts stay test-only.
- Failures: a script error stops the loader before any row loads.

### Design Option A — hand-written schema scripts applied before the load

A hand-written script creates the account, card cross-reference and category balance tables exactly as the data model lists them, and each statement is skipped when its table or index already exists. The loader applies every schema script in name order before the first row loads, so this script runs after the reference tables and before the transaction table. The entity descriptions only describe the tables and never create them, and schema generation stays off at every start.

How it behaves:

- The loader applies the reference script, then this script, then the transaction script, inside its first load transaction.
- A table or index that already exists is left as it is, so a rerun changes no definition and no row.
- The store enforces every key, so a row whose parent is missing is refused even if it slips past the loader's skip rule.
- Each amount column carries the source precision, so an amount one digit too long is refused.
- A stub starting against an empty store finds no table and creates none.
- The mapping test compares each entity description with the data model without a store, and the card data integration suite compares the live catalogue with it.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Hand-written script (vs schema generated from the entities) | every type is copied from the data model | each column is described twice |
| 2 | Create-if-absent statements (vs drop and recreate) | a rerun keeps every loaded row | a changed column needs a hand-made change on an existing store |
| 3 | Schema applied by the loader job (vs at stub start-up) | no stub start ever changes the schema | a stub started before the loader finds no table |
| 4 | Foreign keys enforced by the store (vs checked only by the loader) | a broken parent link can never be stored | rows must arrive in key order |

Effort and risk: low effort, with the risk confined to the script and the entity descriptions drifting apart.

### Design Option B — tables generated by the object mapper at stub start-up

The object mapper creates and updates the three tables from the entity descriptions whenever a stub starts. No card data schema script exists, so the loader depends on a stub having started first. Column lengths, precision and nullability come from the mapper's defaults unless each attribute restates them.

How it differs:

- The tables appear only after a stub starts, so the loader waits for a stub.
- Identifier lengths, amount precision and nullability follow mapper defaults unless each attribute restates them.
- A start against an empty store creates the tables, and a start against an existing store may add columns.
- A foreign key exists only where an entity declares a link, and the reference category has no entity in this story.
- The order of reference, card data and transaction tables follows stub start order, not a fixed sequence.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Schema generated from the entities (vs hand-written script) | each column is described once | types follow mapper defaults instead of the data model |
| 2 | Generation at stub start-up (vs applied by the loader job) | the loader needs no schema step | every stub start can change the schema |
| 3 | Update in place (vs create-if-absent statements) | an added attribute reaches an existing store | a removed or retyped attribute stays behind in the store |
| 4 | Keys and indexes declared on entities (vs written in a script) | each key sits beside the attributes it constrains | key and index names are generated rather than chosen |

Effort and risk: lowest effort, with high risk of type drift and of schema changes at start-up.

### Recommendation

- Option: Option A.
- Why: the data model stays the single schema source, and no stub start changes the schema.
- Strongest argument against: the script and the entities describe the same columns twice, and only the mapping test keeps them in step.
- Shared items that do not decide the choice: the table set, the keys, the fixed order and the test-only scope.
- Flip conditions: the data model is generated from the entities, or a schema migration tool joins the dependency table.

## Design Validation

### Option A validation

Settled assumptions:

- The data model fixes every column, type, key and index of the three tables.
- No write path exists this iteration beyond the loader.
- The reference tables exist before this script runs, through the reference data tables story.
- The loader applies the schema scripts in name order inside its first load transaction.

Open assumptions:

- Whether a later real store needs migrations. Open → settled when the real card data owner is named.
- Whether the catalogue checks run before the first stub read depends on them. Open → settled by the card data integration suite story.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | name order runs the reference script first inside one load transaction |
| AC2 | Holds | every column type is copied from the data model into the script |
| AC3 | Holds | the script declares exactly the keys and the one index the data model lists |
| AC4 | Holds | the store enforces the account key on both child tables |
| AC5 | Holds | the store enforces the reference category key and the composite primary key |
| AC6 | Holds | each amount column carries the source precision |
| AC7 | Holds | the identifier lengths and the not-null rules come from the data model |
| AC8 | Holds | every statement is skipped when its table or index already exists |
| AC9 | Holds | schema generation is pinned off and only the loader applies scripts |
| AC10 | Conditional | the round trip holds while the mapping test keeps the entities in step with the script |

### Option B validation

Settled assumptions:

- The data model fixes every column, type, key and index of the three tables.
- No write path exists this iteration beyond the loader.
- The object mapper can restate length, precision and nullability on each attribute.

Open assumptions:

- Whether a stub always starts before the loader. Open → settled by the compose test stack story.
- Whether each mapper default matches its data-model type. Open → settled by comparing the generated catalogue with the data model.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | no card data script exists to apply |
| AC2 | Breaks | generated types follow mapper defaults instead of the data model |
| AC3 | At risk | generated key and index sets match only what each entity declares |
| AC4 | Conditional | the account foreign key exists only where an entity declares the link |
| AC5 | At risk | the reference category foreign key needs an entity this story does not own |
| AC6 | Conditional | the amount bounds hold only where each attribute restates its precision |
| AC7 | Conditional | the length and not-null bounds hold only where each attribute restates them |
| AC8 | At risk | each update start may add columns to an existing store |
| AC9 | Breaks | schema generation at stub start-up is the design itself |
| AC10 | Holds | the entities and the tables come from one description |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-13-001 |
| Status | TBD |
| Estimated Files Touched | 7 |
| Issue Type | Story |
| Epic Name | Card Data Integration |
| Epic Link | CS-09 |
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
