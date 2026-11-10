# CS-13-001: Reference data tables and seed files

Parent epic: [CS-13 Reference Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a data owner, I want transaction types and categories held as seed data in their own tables, so that I change a description by loading data instead of deploying code.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA03Y · TRAN-TYPE-DESC
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: the two reference tables, their seed files and the reference entity loaders that fill them through the fixture loader core of CS-09-001. The record structures are `Data Layouts · CVTRA03Y` (type code TRAN-TYPE X(02), description TRAN-TYPE-DESC X(50)) and `Data Layouts · CVTRA04Y` (type code TRAN-TYPE-CD X(02), category code TRAN-CAT-CD 9(04), description TRAN-CAT-TYPE-DESC X(50)). The rows come from `Reference Tables · TRANTYPE file` (7 rows) and `Reference Tables · TRANCATG file` (18 rows). Target paths:
>
> - `card-servicing-service/src/main/resources/db/01-reference-data-tables.sql` creates the two tables exactly as `docs/data-model.md` defines them, each statement create-if-absent so re-applying changes nothing:
>   - `transaction_type`: `type_code` `char(2)` NOT NULL, primary key; `type_description` `varchar(50)` NOT NULL.
>   - `transaction_category`: `type_code` `char(2)` NOT NULL; `category_code` `char(4)` NOT NULL; `category_description` `varchar(50)` NOT NULL; primary key `(type_code, category_code)`, which also serves the foreign key `type_code` → `transaction_type.type_code`.
>   - The `01-` prefix makes this script sort first among the `db/` scripts, ahead of the Card Data tables (CS-09-002) and the transaction table (CS-10-001), whose foreign keys reference `transaction_category`.
> - `card-servicing-service/src/main/resources/reference-data/transaction-types.yaml`: the 7 type rows of `Reference Tables · TRANTYPE file`, each with `typeCode` and `typeDescription`: 01 Purchase, 02 Payment, 03 Credit, 04 Authorization, 05 Refund, 06 Reversal, 07 Adjustment.
> - `card-servicing-service/src/main/resources/reference-data/transaction-categories.yaml`: the 18 category rows of `Reference Tables · TRANCATG file`, each with `typeCode`, `categoryCode` and `categoryDescription`: 01/0001 Regular Sales Draft, 01/0002 Regular Cash Advance, 01/0003 Convenience Check Debit, 01/0004 ATM Cash Advance, 01/0005 Interest Amount, 02/0001 Cash payment, 02/0002 Electronic payment, 02/0003 Check payment, 03/0001 Credit to Account, 03/0002 Credit to Purchase balance, 03/0003 Credit to Cash balance, 04/0001 Zero dollar authorization, 04/0002 Online purchase authorization, 04/0003 Travel booking authorization, 05/0001 Refund credit, 06/0001 Fraud reversal, 06/0002 Non-fraud reversal, 07/0001 Sales draft credit adjustment.
>   - In both seed files every code is a quoted string (`"01"`, `"0001"`), because YAML reads an unquoted `01` as the number 1 and drops the leading zero.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/ReferenceDataFixtureLoader.java`: two entity loaders, transaction types at load order 1 and transaction categories at load order 2, registered on the `FixtureLoader` and `LoadReport` core of CS-09-001.
>   - Schema step: before the first type row is read, inside the type load's transaction, it applies every script in the classpath folder `db/` in file-name order through the store connection of CS-09-001. Later schema stories add their scripts to that folder with names that sort after this story's script.
>   - Seed rows are read with the SnakeYAML library the Spring Boot starter already supplies, not through the fixed-width parser, so `card-servicing-service/build.gradle` is not edited.
>   - Seed location: the setting `card-servicing.fixture.reference-data-location`, declared with its default `classpath:reference-data/` in this class, so `application.yml` and `CardServicingProperties` are not edited and no environment variable is added. It is overridden at loader start with a start argument such as `--card-servicing.fixture.reference-data-location=file:/data/reference-data/`, with no rebuild of the service.
>   - Row checks, applied before any write: a type code matches the contract type-code pattern `^[A-Za-z0-9]{2}$`; a category code matches `^[0-9]{4}$`; a description is trimmed of leading and trailing spaces and then holds 1 to 50 characters; a code key appears once per seed file. A row failing a check is skipped and counted, never stored in part.
>     > SCOPE-QUESTION (Resolved): The contract type-code pattern now reads `^[\s\S]{2}$` (exactly two characters of any kind, the TRAN-TYPE X(02) domain), and the row check uses that pattern.
>   - The category loader's parent-key check uses the CS-09-001 foreign-key skip: a category whose type code names no `transaction_type` row is skipped and counted.
>   - Writes replace each row by its code key with `insert … on conflict … do update`, changing a stored row only when its description differs, and never delete a row.
>   - Counts: loaded counts every accepted row, whether inserted, updated or unchanged; skipped counts every rejected row; derived timestamps is always 0. Each entity's counts reach the load report after that entity's load transaction commits.
>   - A missing or unreadable seed file fails that entity's load with an error naming the file, and under the CS-09-001 rule no later-ordered loader starts.
>   - The two loaders run before every Card Data entity loader, which CS-09-002 and CS-09-003 rely on.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/fixture/ReferenceDataFixtureLoaderTest.java`: unit tests for AC1–AC7, driven through the recording transaction manager of CS-09-001 with an in-memory row writer and schema-step recorder, so the unit suite needs no store. The container-backed load and reload proof is CS-13-004.
> - Together these are the 5 files touched.
>
> Change path: after a reload, the Reference Data stub reads the changed row on its next request (CS-13-002), and the Card Servicing service sees it within the 3,600 s fresh TTL of `cs:v1:reftypes` and `cs:v1:refcats` (CS-13-003), with no deploy.
>
> In scope:
>
> - The two reference tables, their keys and the create-if-absent script.
> - The two seed files.
> - The two reference entity loaders and their row checks.
> - The schema step that applies the `db/` scripts in file-name order.
> - Load order ahead of every Card Data entity.
> - Reload by code key with no deletion.
> - Load-report counts for both entities.
> - The seed-location setting and its start-argument override.
>
> Not in this story:
>
> - The loader core, the fixed-width parser, the foreign-key skip mechanics and the load report itself (CS-09-001).
> - The Card Data tables (CS-09-002) and the transaction table (CS-10-001).
> - The Reference Data stub and its reads of the two tables (CS-13-002).
> - Reference-data caching and the description fallback (CS-13-003).
> - The container-backed integration suite (CS-13-004).
>
> ### 2. Acceptance Criteria
>
> - AC1: With the packaged seed files, the reference entity loaders write 7 `transaction_type` rows and 18 `transaction_category` rows, and the load report shows 7 loaded, 0 skipped and 0 derived timestamps for transaction types and 18 loaded, 0 skipped and 0 derived timestamps for transaction categories.
> - AC2: Stored codes and descriptions equal the seed rows field for field, with leading zeros kept, including `01` Purchase, `07` Adjustment, `01`/`0001` Regular Sales Draft, `03`/`0001` Credit to Account and `07`/`0001` Sales draft credit adjustment.
> - AC3: A category row `09`/`0001` added to a copy of the category seed file, with no `09` type row, is skipped, not stored, and counted as 1 skipped for transaction categories, while the other 18 category rows load.
> - AC4: A type row whose code does not match `^[A-Za-z0-9]{2}$` (for example `1`, `ABC`, or an unquoted `01` that YAML reads as a number), or a category row whose category code does not match `^[0-9]{4}$` (for example `001` or `00A1`), is skipped, not stored, and counted as 1 skipped for its entity. A second row repeating a code key already read from the same seed file is skipped and counted the same way.
>   > SCOPE-QUESTION (Resolved): The corrected type-code pattern `^[\s\S]{2}$` still rejects each listed type-code example (`1` and `ABC` by length, and an unquoted `01` that YAML reads as the number 1), and any two-character code such as `A-` now passes the type-code check.
> - AC5: Descriptions are stored with leading and trailing spaces removed, so `"  Purchase  "` is stored as `Purchase`. A description of 50 characters after trimming loads. A description of 51 characters after trimming, or one that is empty after trimming, is skipped and counted.
> - AC6: With type `05` changed from `Refund` to `Refund issued` in a copy of the seed files supplied through the seed-location start argument, rerunning the reference entity loaders updates exactly that row, leaves the other 24 rows unchanged, needs no rebuild of the service and keeps the row counts at 7 and 18. An override naming a folder without `transaction-types.yaml` fails the type load with an error naming that file, and no later-ordered loader starts.
> - AC7: The `db/` scripts are applied in file-name order, starting with `01-reference-data-tables.sql`, before the first type row is read. With a Card Data entity loader registered at a later order, the load report lists transaction types, then transaction categories, then the Card Data entity.
>
> ### 3. Dependencies
>
> - CS-09-001 — fixture loader core, foreign-key skip and load report.
>
> Risk: seed content diverging from `Reference Tables · TRANTYPE file` and `Reference Tables · TRANCATG file` mislabels every type-group and category balance, so AC2 compares the stored rows with the source rows.

## 4. Design

- Diagram: [Logical architecture](../../../../architecture/logical-architecture.mmd)
- Data model: [transaction_type](../../../../data-model.md#transaction_type), [transaction_category](../../../../data-model.md#transaction_category), [Reference data as data](../../../../data-model.md#reference-data-as-data), [Fixture mapping](../../../../data-model.md#fixture-mapping)
- Technical requirements: [Component catalogue](../../../../technical-requirements.md#component-catalogue), [External systems](../../../../technical-requirements.md#external-systems)

Contracts: N/A

Design detail: completed during implementation.

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; pass when all tests pass with ≥80% line coverage per module. `ReferenceDataFixtureLoaderTest` covers AC1–AC7: the packaged seed load, field-for-field values, the orphan category, code shapes and duplicate keys, trimming and the 50-character boundary, reload through the seed-location start argument and the missing seed file, and the schema step and load order.
- Integration: Applies, through the Reference Data integration suite CS-13-004, which proves the seed load and the reload against the `postgres:16.6` container. Command `./gradlew :card-servicing-service:integrationTest`; pass when all suites pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — 25 reference rows load once per stack start, and this story has no request path. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no UI step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — seed data files loaded by the fixture loader.

Rationale: the data owner changes a description by editing a seed data file and rerunning the load, which keeps reference data out of service code and out of the schema scripts.

- Rejected: Option B, seed rows inside the schema scripts, because every description change becomes a schema change.
- Rejected: descriptions held as constants in service code, because reference data must stay data that changes without a deploy.

### Shared by both options

- Topology: two reference tables in the relational store, each category pointing at its type.
- Naming: tables and seed sets are named for transaction types and categories, with codes as keys.
- Addressing: a type row is found by its type code, and a category row by its type code plus category code.
- Isolation: reference rows reach the store ahead of every card data row.
- Fallback: none at load time beyond rejecting a row, because the description fallback belongs to the client story.
- Provisioning: the loader applies the schema scripts in name order before the first entity loads.
- Cut-over: the real reference data service replaces the stub by a change of target address and credential source alone, and ownership of these tables and seed sets after that swap stays an open question in the plan's recommendations.
- Failures: a bad row never becomes a stored row, and a missing seed set or script stops the load.

### Design Option A — seed data files loaded by the fixture loader

Seed rows live in two data files packaged beside the service. The fixture loader reads them, checks each row and writes the good ones, replacing rows by their code key. A description change is a file edit and a loader rerun, pointed at the edited copy when it lives outside the package.

How it behaves:

- The loader applies the schema scripts first, then reads the type seed set, then the category seed set.
- Each row is checked for code shape, description length and a repeated key before any write.
- A category whose type has no stored type row is skipped and counted.
- A good row is inserted, or updated in place when its code key exists with a different description.
- A row missing from the seed set stays in the table, so no referenced row disappears.
- The seed location defaults to the packaged copy and can point at an outside folder when the loader starts.
- Each entity's counts reach the load report after its write commits.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Seed rows in data files (vs rows in schema scripts) | a description change needs no schema change | the file and the table can disagree until the next load |
| 2 | Per-row checks in the loader (vs database constraints alone) | a bad row is skipped and counted | the length limit is stated in two places |
| 3 | Replace by code key (vs empty and reload) | rows that other tables reference never vanish | a row dropped from a seed set stays until removed by hand |

Effort and risk: low effort and low risk once the loader core exists.

### Design Option B — seed rows in the schema scripts

The type and category rows are written as insert statements inside the schema script that creates the two tables. The rows reach the store when the loader applies the schema scripts, with no reference entity loader. A description change is an edit to that script, shipped with the next service build.

How it differs:

- A description change is a schema-script edit shipped with a build.
- The rows never pass through the loader, so they never appear in the load report.
- One bad row aborts the whole script, and no reference row loads.
- Code shape and description length rest on database constraints alone.
- A changed row reaches an existing store only through a hand-written update.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Rows in the schema script (vs seed data files) | no loader code for reference rows | every description change ships with a build |
| 2 | Database constraints alone (vs per-row checks) | each rule lives in one place | a bad row aborts the script instead of a counted skip |
| 3 | Rows written once with the schema (vs replace by code key) | no reload logic | a changed row needs a fresh store or a hand-written update |

Effort and risk: lowest effort, with high risk to the data-change path.

### Recommendation

- Option: Option A.
- Why: a description change stays a data load, and every rejected row shows in the load report.
- Strongest argument against: the seed file and the table can disagree between loads.
- Shared items that do not decide the choice: the two tables, their code keys, the codes themselves and the load order ahead of card data.
- Flip conditions: the data owner delivers database exports instead of files, or reference rows must change in a store the loader never runs against.

## Design Validation

### Option A validation

Settled assumptions:

- The seed content equals the mainframe type and category reference files, at 7 and 18 rows.
- The sample descriptions run at most 29 characters, inside the 50-character limit.
- The loader core supplies the parent-key skip, the per-entity counts and the load order.
- Codes travel as quoted text in the seed files, so leading zeros survive.

Open assumptions:

- The form in which the reference entities appear in the load report. Open → settled by the report format of the loader core story.
- Whether files stay the data owner's delivery form. Open → settled by the data owner's first change request.
- Whether a rerun of the whole load job is safe for the card data entities. Open → settled by the card data fixture load story.
- Who owns these tables and seed sets once the real reference data service replaces the stub. Open → settled by the data store ownership decision recorded in the plan's recommendations.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the loaders write every seed row and report the counts after commit |
| AC2 | Holds | the loader keeps codes as text and trims each description before the write |
| AC3 | Holds | the loader core's parent-key check skips the orphan category |
| AC4 | Holds | the shape and repeated-key checks run on every row before the write |
| AC5 | Holds | the length check runs on the trimmed description |
| AC6 | Holds | the loader reads its seed location at start and replaces rows by code key |
| AC7 | Holds | the loader applies the scripts first and gives the reference loaders the lowest load orders |

### Option B validation

Settled assumptions:

- The seed content equals the mainframe type and category reference files, at 7 and 18 rows.
- The sample descriptions run at most 29 characters, inside the 50-character limit.
- The loader applies the schema scripts in name order before any entity loads.

Open assumptions:

- Whether the data owner accepts a service build for each description change. Open → settled by the data owner's first change request.
- Whether the load report can count rows that a schema script wrote. Open → settled by the report format of the loader core story.
- Who owns these tables and seed sets once the real reference data service replaces the stub. Open → settled by the data store ownership decision recorded in the plan's recommendations.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | the row counts reach the load report only through an added counting step |
| AC2 | Holds | the script carries each code and description as written |
| AC3 | Breaks | a missing parent aborts the script instead of a counted skip |
| AC4 | Breaks | no per-row check exists to skip and count a bad code |
| AC5 | At risk | the column limit aborts the script on a long description with no trim or count |
| AC6 | Breaks | a change needs a schema-script edit shipped with the build |
| AC7 | At risk | script order never reaches the load report |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-09-001 |
| Status | TBD |
| Estimated Files Touched | 5 |
| Issue Type | Story |
| Epic Name | Reference Data Integration |
| Epic Link | CS-13 |
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
