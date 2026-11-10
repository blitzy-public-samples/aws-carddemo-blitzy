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
>   > SCOPE-QUESTION (Resolved): The layout above holds no record length, no calendar-date kind, no blank rule and no key, so the parser cannot apply the validation-before-use checks that `docs/data-model.md` § Fixture rules binds on this story. Resolution: the layout and the parser carry them as follows.
>   >
>   > - A record layout declares, beside its ordered fields, the record's full length. The field kinds are text, unsigned digits, signed zoned amount with its scale, date and timestamp. A text field may be marked not blank, and the layout names its key fields.
>   > - The parser checks every line in full before it returns any field. A line's length counts its bytes before the line feed. The accepted lengths are the full record length and exactly the end column of the last non-filler field, with the FILLER wholly absent. Any other length is malformed: a line shorter than that end column, a line holding only part of the FILLER, or a line longer than the record. FILLER content is never checked. This accepted-length rule is what this story calls the short-line rule.
>   > - Unsigned digits: an n-digit field holds exactly n digits `0`–`9`, and any other character, a space included, is malformed.
>   > - Signed amount: a digit in each position before the last, and a legal overpunch byte (`{`, `A`–`I`, `}`, `J`–`R`) in the last.
>   > - Date: a real calendar date written `YYYY-MM-DD`, with February 29 valid only in a leap year.
>   > - Timestamp: either timestamp form, with a real calendar date, hours `00`–`23`, minutes and seconds `00`–`59` and six fraction digits, or exactly 26 spaces, which parses to no value, the entity loader deciding what that blank means.
>   > - Text: a field marked not blank is malformed when every position holds a space. Other text carries no content check.
>   > - Key: a line repeating the key of an earlier line of the same file is malformed.
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
>   > SCOPE-QUESTION (Resolved): The list above omits most of the failures `docs/data-model.md` § Fixture rules makes malformed, and it does not say what a failure leaves behind. Resolution: a malformed line is any line that fails a parser check of the record-layout note above (a length other than the two accepted lengths, a non-digit in an unsigned field, a bad sign or amount digit, a date that is not a real calendar date, a bad timestamp, an all-space text field marked not blank, or a repeated key), and any row that its registration's row check rejects. The first malformed line in file order fails its entity's load with an error naming the fixture file and the 1-based line number. The entity's transaction rolls back, so its table holds no row from that load, and the load report holds no entry for it. No later-ordered loader starts, entities committed earlier keep their rows and report entries, and the job exits with a non-zero status. A malformed line is never skipped.
> - An FK-violating row, one whose foreign key names no row of its parent entity, is skipped and counted, not failed.
>   > SCOPE-QUESTION (Resolved): A row's parent keys are tested only after the row passes every parser check and its registration's row check. A malformed row is never skipped, so the skipped count holds only parent-key skips.
> - The loader connects using `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` only, writes nothing to the cache and is never invoked from a request path.
> - The extension point is used by CS-09-003, CS-13-001, CS-10-002, CS-09-007 and CS-10-008. A registration supplies its entity name, its load order, its rows (read through the parser, from a seed file or from a generator), the parent-key check for its foreign keys and its row writer, and it may count a derived timestamp for any row.
>   > SCOPE-QUESTION (Resolved): The registration above has no place for an entity's own rule on a parsed row, and nothing says which rows the parser checks bind. Resolution: a registration may also supply a row check, an entity's own rule on the parsed row, which runs after the parser checks and before the parent-key check; a row it rejects is a malformed line. The parser checks and the malformed-line disposition apply to the rows the parser reads from the fixed-width fixture files. Rows a registration supplies from a seed file or a generator follow their own story's checks: the seed-row checks of CS-13-001 reject a seed row and count it as skipped, and CS-09-007 and CS-10-008 generate valid rows.
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
> > SCOPE-QUESTION (Resolved): Which criterion covers the accepted lengths, the unsigned-digit, date, not-blank and repeated-key checks, the clock ranges of a timestamp, the row check, and a malformed row that also breaks a parent key? None of AC1–AC16 does. Resolution: AC17–AC22 below, run in `CardDemoRecordParserTest` and `FixtureLoaderTest` with test layouts and test entity loaders through the recording transaction manager. Every failure below fails the entity load with an error naming the fixture file and the 1-based line number.
> >
> > - AC17: For a test layout of the cross-reference shape, whose full record is 50 bytes and whose last non-filler field ends at column 36, a 36-byte line and a 50-byte line parse, and so does a 50-byte line whose FILLER holds non-space characters. Lines of 35, 37, 49 and 51 bytes each fail.
> > - AC18: In an unsigned 9(11) field, `00000000001` parses as the text `00000000001` with its leading zeros, while `0000000000A`, `0000000001 ` (ending in a space) and 11 spaces each fail. A 16-position text field marked not blank that holds 16 spaces fails, while an unmarked 10-position text field holding 10 spaces parses.
> > - AC19: In a date field, `2024-02-29` parses, while `2023-02-29`, `2022-02-30`, `2022-13-01`, `2022/06/10` and 10 spaces each fail. In a timestamp field, `2022-06-10 24:00:00.000000`, `2022-06-10 19:60:53.000000`, `2022-06-10 19:27:60.000000` and `2022-02-30 19:27:53.000000` each fail, while AC7 keeps its month-13 and separator cases and AC15 keeps the field of 26 spaces that parses to no value.
> > - AC20: With the first field declared as the key, a test file whose line 3 repeats the key of line 1 fails at line 3, the test entity's table holds no row from that load and the load report holds no entry for it.
> > - AC21: In a three-row test entity whose row 2 both names no parent row and carries the sign character `Z` in its amount (`0000000814Z`), the load fails at line 2 and no row is counted as skipped. When instead row 2 passes every parser check, names no parent row and is rejected by the registration's row check, the load fails at line 2 the same way.
> > - AC22: With test entity loaders at orders 1, 2 and 3, where order 1 commits and the file of order 2 holds a malformed line, order 1 keeps its rows and its report entry, the report holds no entry for order 2, the order-3 loader never starts and the job exits with a non-zero status.
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

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; pass when all tests pass with ≥80% line coverage per module. Cases: parser decoding with the sample's real encoded values, the two timestamp forms and the blank timestamp field, the accepted-length rule with 36- and 50-byte cross-reference-shaped lines parsing and 35-, 37-, 49- and 51-byte lines failing, the unsigned-digit, date, timestamp clock-range and not-blank text checks, the repeated key, a malformed row that also names no parent and a row-check rejection each failing rather than being skipped, FK skip, report-after-commit, rollback, the cross-entity disposition after a malformed row, load order, exit status, and context start with no store.
- Integration: Not applicable — the loader core has no upstream call. The container-backed load is proven by CS-09-006. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — not a request path. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no UI step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — one parser over declared layouts.

Rationale: one declared layout serves every loader's columns, checks and lengths.

- Rejected: Option B, per-loader slicing, which repeats offsets and checks.

Shared by both options:

- Topology: a one-shot job runs beside and before the stubs, off request paths.
- Naming: fields and report entries use the data-model names.
- Addressing: the store is reached by address, user name and password only.
- Isolation: one transaction per entity, no cache writes.
- Fallback: none, since a malformed line stops later entities too.
- Provisioning: fixture files ship inside the service package.
- Cut-over: the loader retires with the stubs, with no request-path change.
- Failures: a malformed line rolls back its entity and ends the job non-zero, while a parentless row is skipped and counted.

### Design Option A — declared field layouts

Each layout declares its length, keys and fields by start column, length and kind. One parser reads any layout, giving loaders rows by field name.

How it behaves:

- A line parses only at full record length or the last real field's end.
- Field and key checks run first, then the row check, then the parent-key check.
- An amount's scale comes from the layout, its sign from its last character.
- The store connection opens only on first use.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Declared layouts (vs per-loader slicing) | offsets live in one place | new kinds edit the parser |
| 2 | Two accepted lengths (vs full record only) | short cross-reference lines parse | each line meets two length tests |
| 3 | Report entry after commit (vs during load) | rolled-back rows go uncounted | failed entities leave no counts |
| 4 | Commit per entity (vs per load) | prior commits stay | a load can end partial |
| 5 | Parser checks every field first (vs each loader) | no row is half stored | one bad byte stops an entity |

Effort and risk: one parser, core and report, risking a mistyped column or key.

### Design Option B — per-loader slicing

Each loader slices its own columns and calls shared helpers. The loader core is unchanged.

How it differs:

- Each loader sets its own line lengths, or omits the check.
- Digit, date, blank and key checks sit in each loader or shared helpers.
- A loader can skip the sign helper and decode amounts its own way.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Per-loader slicing (vs layouts) | no parser to learn | offsets repeat per loader |
| 2 | Lengths per loader (vs layout) | each loader picks | one loader owns short lines |
| 3 | Opt-in helpers (vs one parser) | helpers stay small | bad signs can slip through |
| 4 | Commit per entity (vs per load) | prior commits stay | a load can end partial |

Effort and risk: more code per loader, risking checks that drift apart.

### Recommendation

- Option: Option A.
- Why: one place holds every offset and check for all loaders.
- Strongest argument against: an extra layer for a few small layouts.
- Shared items that do not decide the choice: the sign and timestamp rules, the per-entity transaction and the report after commit.
- Flip conditions: a redefined area a flat list cannot express, or a one-layout load.

## Design Validation

### Validation of Option A

Settled assumptions:

- Sample lines are fixed-width, with 36-byte cross-references.
- Sign letters sit only in an amount's last position.
- Every sample row passes every check, with none skipped.

Open assumptions:

- Open → redefined areas in later layouts → settled by the transaction fixture load story.
- Open → whether seed rows skip the parser → settled by the reference data tables story.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the amount kind decodes positive sign letters |
| AC2 | Holds | the amount kind decodes negative sign letters |
| AC3 | Holds | the field length sets the digit count |
| AC4 | Holds | the decode table holds both zero signs |
| AC5 | Holds | the amount kind checks every position |
| AC6 | Holds | both forms parse as UTC |
| AC7 | Holds | the timestamp kind checks months and separators |
| AC8 | Holds | the last real field's end is accepted |
| AC9 | Holds | the core skips parentless rows |
| AC10 | Holds | entries follow the commit |
| AC11 | Holds | a rollback precedes any entry |
| AC12 | Holds | only three settings are read |
| AC13 | Holds | loaders wait for prior commits |
| AC14 | Holds | the core stops at a failure |
| AC15 | Holds | the timestamp kind maps spaces to no value |
| AC16 | Holds | the connection opens on first use |
| AC17 | Holds | the layout declares the only two lengths |
| AC18 | Holds | digit and not-blank checks reject bad fields |
| AC19 | Holds | calendar and clock ranges are checked |
| AC20 | Holds | the parser tracks declared keys |
| AC21 | Holds | the parent-key check runs last |
| AC22 | Holds | earlier commits survive the stop |

### Validation of Option B

Settled assumptions: those of Option A, plus:

- The data model fixes two lengths per file, 36 and 50 for cross-references.

Open assumptions:

- Open → whether each loader calls the helpers and names its key → settled by each entity loader story.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the sign helper decodes positive signs |
| AC2 | Holds | the sign helper decodes negative signs |
| AC3 | Conditional | the value needs all twelve positions sliced |
| AC4 | Holds | the sign helper holds both zero signs |
| AC5 | At risk | a loader can bypass the sign helper |
| AC6 | Holds | the timestamp helper accepts both forms |
| AC7 | Conditional | loaders must call the timestamp helper |
| AC8 | At risk | loaders compute their own lengths |
| AC9 | Holds | same core as Option A |
| AC10 | Holds | same report as Option A |
| AC11 | Holds | same rollback as Option A |
| AC12 | Holds | same connection as Option A |
| AC13 | Holds | same core as Option A |
| AC14 | Holds | same core as Option A |
| AC15 | Conditional | blank handling needs the timestamp helper |
| AC16 | Holds | same connection as Option A |
| AC17 | At risk | loaders compute their own lengths |
| AC18 | Conditional | loaders must call the digit and blank helpers |
| AC19 | Conditional | loaders must call the date and timestamp helpers |
| AC20 | Conditional | loaders must name their keys |
| AC21 | At risk | a bad-sign row can be skipped instead |
| AC22 | Holds | same core as Option A |

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
