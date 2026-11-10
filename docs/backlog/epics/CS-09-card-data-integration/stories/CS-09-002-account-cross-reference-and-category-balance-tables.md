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

Rationale: a script copied from the data model keeps one schema source.

- Rejected: Option B, mapper-generated tables, whose types drift from the data model.

### Shared by both options

- Topology: one relational store shared by the loader and the stubs.
- Naming: lower-case names copied from the data model.
- Addressing: the loader and the stubs reach the store, never the service.
- Isolation: these three tables only, with reference tables owned by their own story.
- Fallback: none, since stubs read nothing before the tables exist.
- Provisioning: the tables exist before the first card data row loads.
- Cut-over: an address and credential swap, ownership open in the plan's recommendations.
- Failures: a refused row rolls back only its entity and stops the loader.

### Design Option A — hand-written schema scripts applied before the load

A hand-written script creates absent tables before any row loads. Schema generation is off.

How it behaves:

- Name order runs the reference, card data and transaction scripts in one transaction.
- A script error stops the loader before any row loads.
- The store refuses a row with a missing parent or an oversized amount.
- The mapping test checks entities against the data model without a store.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Hand-written script (vs mapper) | types match the model | columns appear twice |
| 2 | Create-if-absent (vs recreate) | reruns keep rows | changes are manual |
| 3 | Schema by loader (vs stubs) | no stub alters schema | early stubs find no table |
| 4 | Store-enforced keys (vs loader) | no orphan is stored | parents must load first |

Effort and risk: low effort, risking only script and entity drift.

### Design Option B — tables generated by the object mapper at stub start-up

The object mapper creates and updates the tables at each stub start. Its defaults set the types.

How it differs:

- The loader waits for a stub, and table order follows stub start order.
- Foreign keys need entity links, and no entity here covers the reference category.
- A table the mapper cannot generate fails that stub's start.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Generated schema (vs script) | columns appear once | defaults set the types |
| 2 | Generation at start (vs loader) | no loader schema step | starts can alter schema |
| 3 | Update in place (vs create-only) | additions reach stores | stale columns linger |
| 4 | Entity key and index (vs script) | keys sit by attributes | names are generated |

Effort and risk: lowest effort, with high risk of type drift and start-up schema change.

### Recommendation

- Option: Option A.
- Why: one schema source, a fixed creation order and no stub-start schema change.
- Strongest argument against: only the mapping test keeps script and entities in step.
- Shared items that do not decide the choice: the tables, names, store and test-only scope.
- Flip conditions: the data model is generated from the entities, or a migration tool is adopted.

## Design Validation

### Option A validation

Settled assumptions: the data model fixes every type and key, and reference scripts run first.

- Open → table ownership after the swap, settled by the plan's ownership recommendation.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the reference script runs first in one transaction |
| AC2 | Holds | types are copied from the data model |
| AC3 | Holds | the script declares only the listed keys and index |
| AC4 | Holds | both child tables enforce the account key |
| AC5 | Holds | the store enforces the category and composite keys |
| AC6 | Holds | each amount column carries the source precision |
| AC7 | Holds | lengths and not-null rules follow the data model |
| AC8 | Holds | every statement skips an existing table or index |
| AC9 | Holds | schema generation stays pinned off at every start |
| AC10 | Conditional | the mapping test must keep entities matching the script |

### Option B validation

Settled assumptions: the data model as in Option A, plus a mapper that can restate each type.

- Open → whether mapper defaults match the data model, settled by a catalogue comparison.
- Open → table ownership after the swap, settled by the plan's ownership recommendation.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | no card data script exists |
| AC2 | Breaks | types follow mapper defaults, not the data model |
| AC3 | At risk | keys and indexes follow entity declarations only |
| AC4 | Conditional | the account key needs a declared entity link |
| AC5 | At risk | the category key needs an entity outside this story |
| AC6 | Conditional | amount bounds hold only where precision is restated |
| AC7 | Conditional | length and not-null bounds hold only where restated |
| AC8 | At risk | each start may add columns |
| AC9 | Breaks | stub start-up generates the schema by design |
| AC10 | Conditional | the exact round trip needs every attribute restated |

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
