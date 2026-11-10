# CS-09-001: Fixture loader core and load report

Parent epic: [CS-09 Card Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want one loader that parses the fixed-width sample records and reports what it loaded, so that every stub serves known, verifiable data.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-ID
>
> Directive: in-repo fixture loader
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: one loader core reads fixed-width CardDemo sample records by their 1-based copybook positions, decodes overpunch-signed amounts and both timestamp forms, loads each entity in its own transaction under the foreign-key skip-and-count rule, and adds a load-report entry for each committed entity. Every later loader story registers its entity through this core rather than re-implementing it. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/CardDemoRecordParser.java`: fixed-width parsing by 1-based copybook offsets, overpunch sign decoding and timestamp parsing. A record layout lists its fields in order, each with its start column, length and kind: text, unsigned digits, signed zoned amount with its scale, or timestamp.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/FixtureLoader.java`: the one-shot job, with one load transaction per entity, the FK skip-and-count rule, and the entity-loader extension point that later loaders register through. Each registration declares its entity and its load order.
>   - The job runs only when the service starts with the Spring profile `fixture-load`. It runs the registered entity loaders in ascending load order, then exits: status 0 after the last loader commits, and a non-zero status at the first failed entity load, with no later-ordered loader started.
>   - It declares the store connection from the `card-servicing.db` group of `CardServicingProperties` (CS-11-001), and the stub stories' store readers share that connection. The connection opens on first use, so the application context starts with no store reachable.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/LoadReport.java`: per-entity loaded, skipped and derived-timestamp counts, with an entry added only after that entity's load transaction commits. The job logs one line per entry when it exits.
> - `card-servicing-service/build.gradle`: add Spring Data JPA and the PostgreSQL JDBC driver, versions from the Spring Boot BOM.
> - Unit tests under `card-servicing-service/src/test/java/com/example/cardservicing/integration/fixture/`: `CardDemoRecordParserTest.java` and `FixtureLoaderTest.java`. `FixtureLoaderTest` drives test entity loaders through a recording transaction manager, so the unit suite needs no store.
> - Together these are the 6 files touched.
>
> Rules:
>
> - Overpunch sign in the last position of a signed amount: `{` is +0, `A`–`I` are +1 to +9, `}` is −0 and `J`–`R` are −1 to −9. Every position before the sign position is a digit.
> - Amounts are scale-2 decimals, the two implied decimals of every S9(n)V99 field.
> - Timestamp forms `YYYY-MM-DD HH:MM:SS.ffffff` and `YYYY-MM-DD-HH.MM.SS.ffffff` are both parsed as UTC and rendered as RFC 3339 with microseconds and `Z`. A timestamp field holding only spaces parses to no value, and the entity loader decides what a blank means.
> - Unsigned digit fields keep their leading zeros and are carried as text.
> - A malformed line (shorter than the end column of its layout's last non-filler field, a bad sign character or amount digit, or a bad timestamp) fails that entity's load with an error naming the fixture file and the 1-based line number. The entity's transaction rolls back.
> - An FK-violating row, one whose foreign key names no row of its parent entity, is skipped and counted, not failed.
> - The loader connects using `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` only, writes nothing to the cache and is never invoked from a request path.
> - The extension point is used by CS-09-003, CS-13-001, CS-10-002, CS-09-007 and CS-10-008. A registration supplies its entity name, its load order, its rows (read through the parser, from a seed file or from a generator), the parent-key check for its foreign keys and its row writer, and it may count a derived timestamp for any row.
>
> In scope:
>
> - The parser and its record-layout fields.
> - Overpunch sign decoding.
> - The two timestamp forms and the blank timestamp field.
> - The short-line rule.
> - FK skip and count.
> - One transaction per entity.
> - The load report.
> - The entity-loader extension point and its load order.
> - The one-shot run under the `fixture-load` profile and its exit status.
> - The store connection from the `card-servicing.db` settings.
> - The two build dependencies.
>
> Not in this story:
>
> - Table DDL (CS-09-002, CS-13-001, CS-10-001).
> - Entity-specific loaders and fixture files (CS-09-003, CS-10-002, CS-13-001).
> - The blank-processed-timestamp derivation rule itself (CS-10-002, which only increments this story's derived-timestamp count).
> - Synthetic volume (CS-09-007, CS-10-008).
> - Any cache or request-path behaviour.
>
> ### 2. Acceptance Criteria
>
> - AC1: `0000000814D` in an S9(09)V99 field decodes to `81.44`.
> - AC2: `0000000707P` in an S9(09)V99 field decodes to `-70.77`.
> - AC3: `00000001940{` in an S9(10)V99 field decodes to `194.00`.
> - AC4: In an S9(09)V99 field, `0000000000{` decodes to `0.00` and `0000000100}` decodes to `-10.00`.
> - AC5: A sign character outside `{`, `A`–`I`, `}` and `J`–`R` (for example `0000000814Z`), or a non-digit before the sign position (for example `00000X0814D`), fails the entity load with an error naming the fixture file and the 1-based line number.
> - AC6: `2022-06-10 19:27:53.000000` and `2022-06-10-19.27.53.000000` both parse to `2022-06-10T19:27:53.000000Z`.
> - AC7: `2022-13-10 19:27:53.000000` and `2022/06/10 19:27:53.000000` each fail the entity load with an error naming the fixture file and the 1-based line number.
> - AC8: A line shorter than the end column of its layout's last non-filler field fails with an error naming the fixture file and its 1-based line number. A line that ends exactly at that column parses: a 36-byte cross-reference line, whose last non-filler field XREF-ACCT-ID ends at column 36, parses with the FILLER absent.
> - AC9: In a test entity of three rows where one row's foreign key names no parent row, that row is skipped, the entity's skipped count is 1 and its loaded count is 2.
> - AC10: After an entity's transaction commits, the load report holds exactly one entry for that entity with its loaded, skipped and derived-timestamp counts.
> - AC11: When an entity's load rolls back, the report holds no entry for that entity and the table holds no row from that load.
> - AC12: The loader opens no cache connection: with `REDIS_HOST` set to an unreachable address, the job still exits with status 0. Its store connection URL, user name and password equal the values of `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`, and a `SPRING_DATASOURCE_URL` value set alongside them is ignored.
> - AC13: Registered entity loaders run in their declared order. A loader registered with order 2 starts only after the loader with order 1 has committed.
> - AC14: When the entity load with order 1 fails, no loader with a later order starts and the job exits with a non-zero status. When every registered loader commits, the job exits with status 0.
> - AC15: A timestamp field holding 26 spaces parses to no value and does not fail the load.
> - AC16: With no store reachable and the `fixture-load` profile inactive, the context-start smoke test from CS-05-001 and the configuration tests from CS-11-001 pass with the two new dependencies on the classpath, and no entity loader runs.
>
> ### 3. Dependencies
>
> - CS-11-001 — supplies the typed configuration and the DB_URL, DB_USERNAME and DB_PASSWORD environment variables the loader connects with.
>
> Risk: A sign or offset rule that is wrong here corrupts every fixture entity at once, so the parser tests use the sample's real encoded values.

## 4. Design

- Diagram: [Logical architecture](../../../../architecture/logical-architecture.mmd)
- Data model: [Fixture mapping](../../../../data-model.md#fixture-mapping), [Fixture rules](../../../../data-model.md#fixture-rules)
- Technical requirements: [Component catalogue](../../../../technical-requirements.md#component-catalogue)

Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; pass when all tests pass with ≥80% line coverage per module. Cases: parser decoding with the sample's real encoded values, the two timestamp forms and the blank timestamp field, the short-line rule with the 36-byte cross-reference line, FK skip, report-after-commit, rollback, load order, exit status, and context start with no store.
- Integration: Not applicable — the loader core has no upstream call. The container-backed load is proven by CS-09-006. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — not a request path. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no UI step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — one parser driven by a table of field descriptors per record layout.

Rationale: one declared layout per record keeps every column position, the sign rule and every minimum line length in one place that each later loader reuses.

- Rejected: Option B, hand-written substring slicing per record, because it repeats offset arithmetic in every loader and spreads the sign rule.

Shared by both options:

- Topology: a one-shot loader job runs beside the stubs and before them, outside the request path.
- Naming: field descriptors and report entries carry the data-model field and entity names.
- Addressing: the relational store is reached through its three connection settings only, the address, the user name and the password.
- Isolation: each entity loads in its own transaction, and the loader writes nothing to the cache.
- Fallback: none, because a malformed line stops that entity and every later one.
- Provisioning: fixture files travel on the class path inside the service package.
- Cut-over: the loader retires with the stubs, with no request-path change.
- Failures: a malformed line rolls back its entity with the file and line named, and a row with a broken parent link is skipped and counted.

### Design Option A — declared field layouts

Each record layout is declared once as an ordered list of fields, each with its start column, length and kind: text, unsigned number, signed zoned amount with its scale, or timestamp. One parser reads any layout, and the minimum line length is computed from the last non-filler field. Entity loaders receive parsed rows by field name and never count columns.

How it behaves:

- The parser checks each line against its layout's minimum length before reading any field.
- A signed amount field reads its last character as sign and final digit, then applies the field's scale.
- A timestamp field accepts the space-and-colon form and the dash-and-dot form, and a field of spaces yields no value.
- Every parse failure names the fixture file and the line number, and the loader rolls back that entity and stops.
- The loader core applies each registration's parent-key check and counts each rejected row as skipped.
- A report entry for an entity appears only once its transaction has committed.
- The store connection opens on first use, so a service start without the loader profile touches no store.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Declared layouts (vs per-loader slicing) | every offset lives in one declaration | a new field kind edits the shared parser |
| 2 | Minimum length from the last real field (vs fixed record length) | short cross-reference lines without filler parse | a truncated filler goes unnoticed |
| 3 | Report entry after commit (vs during load) | the report never claims rows a rollback removed | a failed entity leaves no partial counts to inspect |
| 4 | One transaction per entity (vs one for the whole load) | a failure keeps every earlier committed entity | a failed run leaves the store part loaded |

Effort and risk: one parser with four field kinds, one loader core and one report, with the risk in a layout declaration that mistypes a start column.

### Design Option B — per-loader slicing

Each entity loader slices its own columns from the line and calls shared sign and timestamp helpers. The loader core still runs registrations in order, one transaction per entity, and keeps the load report. No layout declaration exists, so each loader states its own positions and its own minimum length.

How it differs:

- Column positions sit inside each entity loader as start and end numbers.
- Each loader computes and checks its own minimum line length, or omits the check.
- Use of the shared sign helper is a convention, so a loader can decode an amount its own way.
- A new record layout adds slicing code instead of a field list.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Per-loader slicing (vs declared layouts) | no shared parser layer to learn | offset arithmetic repeats in every loader |
| 2 | Minimum length per loader (vs from the last real field) | each loader picks its own rule | the short cross-reference line depends on one loader's choice |
| 3 | Shared helpers by convention (vs one parser that always applies them) | the helpers stay small | an unguarded loader can accept a bad sign character |
| 4 | One transaction per entity (vs one for the whole load) | a failure keeps every earlier committed entity | a failed run leaves the store part loaded |

Effort and risk: less code in the core but more in every loader, with the risk in a sign or offset rule that drifts between loaders.

### Recommendation

- Option: Option A.
- Why: one place holds every offset, so the sign, timestamp and short-line rules reach every entity without each loader repeating them.
- Strongest argument against: one extra layer of indirection for a handful of small layouts.
- Shared items that do not decide the choice: the sign rule, the timestamp rule, the transaction per entity and the report after commit.
- Flip conditions: a layout that needs a redefined area a flat field list cannot express, or a load that only ever reads a single layout.

## Design Validation

### Validation of Option A

Settled assumptions:

- The sample files are fixed-width with known line lengths: 300 bytes for accounts, 36 for cross-references, 50 for category balances and 350 for transactions.
- Sign letters appear only in the last position of an amount field.
- Both timestamp forms appear in the mainframe source application, the space-and-colon form in the sample and the dash-and-dot form in batch posting.
- The sample skips zero rows in every entity.

Open assumptions:

- Open → whether a later record layout needs a redefined area → settled by the transaction fixture load story's layout review.
- Open → whether the reference seed rows reach the loader without the parser → settled by the reference data tables story.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the amount kind decodes a positive sign letter at scale two |
| AC2 | Holds | the amount kind decodes a negative sign letter at scale two |
| AC3 | Holds | the field length sets the digit count of a twelve-position amount |
| AC4 | Holds | both zero sign characters sit in the one decode table |
| AC5 | Holds | the amount kind rejects any character outside the decode table |
| AC6 | Holds | the timestamp kind accepts both forms and reads them as UTC |
| AC7 | Holds | the timestamp kind rejects an invalid month and a wrong separator |
| AC8 | Holds | the minimum length comes from the last non-filler field |
| AC9 | Holds | the loader core applies each registration's parent-key check |
| AC10 | Holds | the report adds an entry only after commit |
| AC11 | Holds | a rollback discards the rows before any report entry exists |
| AC12 | Holds | the store connection reads only its three settings |
| AC13 | Holds | the loader core starts each registration after the previous commit |
| AC14 | Holds | the loader core stops at the first failed entity |
| AC15 | Holds | the timestamp kind maps a field of spaces to no value |
| AC16 | Holds | the store connection opens only on first use |

### Validation of Option B

Settled assumptions:

- The sample files are fixed-width with known line lengths: 300 bytes for accounts, 36 for cross-references, 50 for category balances and 350 for transactions.
- Sign letters appear only in the last position of an amount field.
- Both timestamp forms appear in the mainframe source application, the space-and-colon form in the sample and the dash-and-dot form in batch posting.
- The sample skips zero rows in every entity.

Open assumptions:

- Open → whether every later loader calls the shared sign and timestamp helpers → settled by the review of each entity loader story.
- Open → the minimum line length of the cross-reference file → settled by the card data fixture load story.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the shared sign helper decodes a positive sign letter |
| AC2 | Holds | the shared sign helper decodes a negative sign letter |
| AC3 | Conditional | the value holds only while the loader slices all twelve positions |
| AC4 | Holds | both zero sign characters sit in the shared helper |
| AC5 | At risk | a loader that bypasses the shared helper can accept a bad sign character |
| AC6 | Holds | the shared timestamp helper accepts both forms |
| AC7 | Conditional | the rejection holds only while each loader calls the shared timestamp helper |
| AC8 | At risk | each loader computes its own minimum line length |
| AC9 | Holds | the loader core applies each registration's parent-key check |
| AC10 | Holds | the report adds an entry only after commit |
| AC11 | Holds | a rollback discards the rows before any report entry exists |
| AC12 | Holds | the store connection reads only its three settings |
| AC13 | Holds | the loader core starts each registration after the previous commit |
| AC14 | Holds | the loader core stops at the first failed entity |
| AC15 | Conditional | the blank rule holds only while each loader calls the shared timestamp helper |
| AC16 | Holds | the store connection opens only on first use |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-11-001 |
| Status | TBD |
| Estimated Files Touched | 6 |
| Issue Type | Story |
| Epic Name | Card Data Integration |
| Epic Link | CS-09 |
| Story Points | 3 |
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
