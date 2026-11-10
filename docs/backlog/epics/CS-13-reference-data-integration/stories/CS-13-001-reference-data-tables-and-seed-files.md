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
>     >
>     > SCOPE-QUESTION (Resolved): The row checks above give a rejected seed row only a skipped count, while `docs/data-model.md` § Fixture rules requires each rejected seed row to be logged with its seed file, its position in that file and the failed check, apart from a foreign-key skip. Resolution: for each row a row check rejects, `ReferenceDataFixtureLoader` writes one WARN log line `Seed row rejected: file=<seed file> row=<n> check=<check>`. The seed file is `transaction-types.yaml` or `transaction-categories.yaml`, `<n>` is the row's 1-based position in that file's row list, and `<check>` is the first check the row fails, taken in the order `type-code`, `category-code`, `description`, `duplicate-key`. A category skipped by the CS-09-001 foreign-key skip writes no such line, so for each entity the skipped count less its rejection lines equals its foreign-key skips. The loaded, skipped and derived-timestamp counts below stay unchanged.
>   - The category loader's parent-key check uses the CS-09-001 foreign-key skip: a category whose type code names no `transaction_type` row is skipped and counted.
>   - Writes replace each row by its code key with `insert … on conflict … do update`, changing a stored row only when its description differs, and never delete a row.
>     > SCOPE-QUESTION (Resolved): The write above names no way of passing seed values to the store, while the row checks admit a quote, a semicolon or a comment marker in a type code and in a description. Resolution: each entity's write is one fixed statement text, held as a constant in `ReferenceDataFixtureLoader` with positional `?` placeholders, and that entity's row writer passes every seed value of the row, its type code, its category code where the entity has one and its description, as a bound parameter of `JdbcTemplate.update(String, Object...)` over the CS-09-001 store connection. Spring JDBC's `JdbcTemplate` comes with the Spring Data JPA dependency that CS-09-001 adds, so `card-servicing-service/build.gradle` is not edited. No seed value is ever concatenated or formatted into the statement text, so a quote, a semicolon or a comment marker in a seed value is stored as written. The only other statements the loader executes are those of the packaged classpath `db/` scripts of the schema step, and the seed location supplies only the two YAML data files and is never read for scripts.
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
>   > SCOPE-QUESTION (Resolved): The criterion also covers seed values that carry quotes and statement fragments, which the corrected type-code pattern and the 1-to-50-character description admit. In a copy of the seed files supplied through the seed-location start argument, type `05` is changed from `Refund` to `Customer's refund`, a type row `'9` with description `x'); DROP TABLE account;--` is added, and a category row `'9`/`0001` with that same description is added. Each of these rows passes every row check, `'9` matching `^[\s\S]{2}$` and the two descriptions holding 17 and 26 characters with no leading or trailing space, and the category passes the parent-key check, so the load writes no seed-row rejection line. The in-memory row writer receives each of these codes and descriptions unchanged, character for character, as the value the store writer binds apart from its fixed statement text. The load report shows 8 loaded, 0 skipped and 0 derived timestamps for transaction types and 19 loaded, 0 skipped and 0 derived timestamps for transaction categories, and the other 6 type rows and 18 category rows equal the packaged seed rows. The container-backed proof that these values are stored as written and leave the store's tables and columns unchanged is CS-13-004.
> - AC3: A category row `09`/`0001` added to a copy of the category seed file, with no `09` type row, is skipped, not stored, and counted as 1 skipped for transaction categories, while the other 18 category rows load.
>   > SCOPE-QUESTION (Resolved): With the orphan `09`/`0001` row added, the load writes no seed-row rejection line, which shows that its 1 skipped is a foreign-key skip and not a failed row check.
> - AC4: A type row whose code does not match `^[A-Za-z0-9]{2}$` (for example `1`, `ABC`, or an unquoted `01` that YAML reads as a number), or a category row whose category code does not match `^[0-9]{4}$` (for example `001` or `00A1`), is skipped, not stored, and counted as 1 skipped for its entity. A second row repeating a code key already read from the same seed file is skipped and counted the same way.
>   > SCOPE-QUESTION (Resolved): The corrected type-code pattern `^[\s\S]{2}$` still rejects each listed type-code example (`1` and `ABC` by length, and an unquoted `01` that YAML reads as the number 1), and any two-character code such as `A-` now passes the type-code check.
>   >
>   > SCOPE-QUESTION (Resolved): Each row this criterion skips also writes exactly one seed-row rejection line naming its seed file, its 1-based row position and its failed check: `type-code` for a type code of the wrong shape, the unquoted `01` read as a number included, `category-code` for a category code of the wrong shape, and `duplicate-key` for the second row repeating a code key, while the first row carrying that key loads and writes none.
> - AC5: Descriptions are stored with leading and trailing spaces removed, so `"  Purchase  "` is stored as `Purchase`. A description of 50 characters after trimming loads. A description of 51 characters after trimming, or one that is empty after trimming, is skipped and counted.
>   > SCOPE-QUESTION (Resolved): Each description this criterion skips, of 51 characters or empty after trimming, also writes exactly one seed-row rejection line naming its seed file, its 1-based row position and the check `description`. The 50-character description writes none.
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

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; pass when all tests pass with ≥80% line coverage per module. `ReferenceDataFixtureLoaderTest` covers AC1–AC7: the packaged seed load, field-for-field values, the orphan category, code shapes and duplicate keys, trimming and the 50-character boundary, reload through the seed-location start argument and the missing seed file, and the schema step and load order. For each rejected seed row it asserts, through Spring Boot's `OutputCaptureExtension`, exactly one seed-row rejection line with that row's seed file, 1-based row position and failed check, and it asserts that the orphan category and the 50-character description write none. It also loads a seed copy carrying the description `Customer's refund`, the description `x'); DROP TABLE account;--` and the type code `'9` (AC2) and asserts that each row passes the row checks with no rejection line and reaches the row writer unchanged.
- Integration: Applies, through the Reference Data integration suite CS-13-004, which proves the seed load and the reload against the `postgres:16.6` container, and is the literal-storage proof that seed values carrying quotes and statement fragments are stored as written with the container's tables and columns unchanged. Command `./gradlew :card-servicing-service:integrationTest`; pass when all suites pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — 25 reference rows load once per stack start, and this story has no request path. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no UI step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — seed data files.

Rationale: descriptions change as data, outside code and schema.

- Rejected: Option B, schema-script rows, because each edit becomes a schema change.
- Rejected: code constants, because each change would need a deploy.

### Shared by both options

- Topology: two reference tables, each category pointing at its type.
- Naming: tables and seed sets named for types and categories.
- Addressing: each row is found by its code key.
- Isolation: reference rows load before any card data row.
- Fallback: none at load, as description fallback is the client story's.
- Provisioning: the loader applies the schema scripts in name order first.
- Cut-over: the real service replaces the stub by address and credential alone.
- Failures: bad rows are never stored, and a missing file stops the load.

### Design Option A — seed data files

Two packaged seed files hold the rows. The loader checks them and writes good rows by code key, optionally from an outside copy.

How it behaves:

- Types load before categories, after the schema scripts.
- Before any write, rows are checked for code shape, description length and repeated keys.
- Each rejected row is logged with its seed set, position and failed check, unlike a parent-key skip.
- Good rows are added, or rewritten when changed, never removed.
- Seed values travel as bound values apart from the fixed statement text, so a quote or statement fragment is stored as written.
- Each entity's counts reach the report after its commit.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Seed files (vs schema-script rows) | edits need no schema change | file and table differ until reload |
| 2 | Loader row checks (vs constraints alone) | bad rows are skipped and counted | the length limit is stated twice |
| 3 | Replace by code key (vs empty and reload) | referenced rows never vanish | dropped rows stay until removed by hand |

Effort and risk: low, once the loader core exists.

### Design Option B — schema-script rows

The schema script also writes the rows. Each description change ships with a build.

How it differs:

- Rows bypass the loader and never reach the load report.
- Constraints alone guard each row, so one bad row aborts the script.
- Values sit in the script text, so a quote must be escaped by hand.
- A changed row reaches an existing store only by hand.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Schema-script rows (vs seed files) | no reference loader code | each change ships with a build |
| 2 | Constraints alone (vs row checks) | each rule lives once | one bad row aborts the script |
| 3 | Rows written once (vs replace by key) | no reload logic | changed rows need a fresh store or hand fix |

Effort and risk: lowest effort, high data-change risk.

### Recommendation

- Option: Option A.
- Why: description changes stay data loads, and the load report shows every rejected row.
- Strongest argument against: seed file and table can disagree between loads.
- Shared items that do not decide the choice: the tables, keys, codes and load order.
- Flip conditions: database exports replace files, or rows must change in a store the loader never reaches.

## Design Validation

### Option A validation

Settled assumptions:

- Seed content equals the mainframe reference files: 7 and 18 rows, descriptions at most 29 characters.
- The loader core supplies the parent-key skip, counts and load order.
- Quoted codes keep their leading zeros.

Open assumptions:

- How reference entities show in the load report. Open → settled by the loader core story.
- Whether files stay the data owner's delivery form. Open → settled by the data owner's first change request.
- Whether a whole-job rerun is safe for card data. Open → settled by the card data load story.
- Table ownership after the swap, under either option. Open → settled by the plan's ownership decision.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the loaders write all rows and report counts after commit |
| AC2 | Holds | the loader binds each trimmed value and keeps codes as text |
| AC3 | Holds | the parent-key skip counts the orphan with no rejection line |
| AC4 | Holds | the shape and repeated-key checks skip and log each bad row |
| AC5 | Holds | the length check follows trimming and logs each rejection |
| AC6 | Holds | the loader reads its seed location at start and replaces by key |
| AC7 | Holds | the loader runs scripts first and orders reference loaders lowest |

### Option B validation

Settled assumptions:

- Seed content and description lengths are as in Option A.

Open assumptions:

- Whether the data owner accepts a build per change. Open → settled by the first change request.
- Whether the report can count script-written rows. Open → settled by the loader core story.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | counts need an added counting step |
| AC2 | At risk | a quote is stored as written only when escaped by hand |
| AC3 | Breaks | a missing parent aborts the script with no counted skip |
| AC4 | Breaks | no per-row check skips and counts a bad code |
| AC5 | At risk | a long description aborts the script with no trim or count |
| AC6 | Breaks | each change needs a script edit and a build |
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
