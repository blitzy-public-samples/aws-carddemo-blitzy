# CS-10-002: Transaction fixture load

Parent epic: [CS-10 Transaction Activity Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want the 300 sample transactions loaded with derived processed timestamps and their account, so that listings order deterministically.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-PROC-TS
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: the CardDemo sample daily transaction file is copied unchanged into the service module, and one transaction entity loader on the CS-09-001 fixture-loader core writes the `transaction_record` table of CS-10-001. The loader registers through the `FixtureLoader` entity-loader extension point as entity `transaction_record` with load order 6, after the reference-data loaders (orders 1 and 2) and the Card Data loaders (orders 3, 4 and 5), and it uses `CardDemoRecordParser` and `LoadReport` without re-implementing them. It parses each 350-byte record by the 1-based offsets of `Data Layouts · CVTRA06Y`, the same layout as `Data Layouts · CVTRA05Y`: TRAN-ID 1–16, type 17–18, category 19–22, source 23–32, description 33–132, amount 133–143, merchant ID 144–152, merchant name 153–202, merchant city 203–252, merchant zip 253–262, card number 263–278, original timestamp 279–304, processed timestamp 305–330, and FILLER 331–350, which is ignored. It decodes the overpunch sign in the last amount position, column 143 (`{` = +0, `A`–`I` = +1 to +9, `}` = −0, `J`–`R` = −1 to −9), into a scale-2 decimal, and parses both `YYYY-MM-DD HH:MM:SS.ffffff` and `YYYY-MM-DD-HH.MM.SS.ffffff` as UTC into `timestamptz(6)`. A loaded row whose processed timestamp is blank takes its original timestamp as `processed_timestamp` and is counted as a derived timestamp in the load report; a non-blank processed timestamp is kept and is not counted. `account_id` is set to the account of the loaded `card_cross_reference` row for the record's card number. A row that breaks a foreign key, either a card number with no loaded cross-reference row or a type and category pair with no loaded `transaction_category` row, is skipped and counted as skipped while the load continues. The `transaction_record` load-report entry is written after the load transaction commits. A malformed line fails the load under the loader core's short-line, sign and timestamp rules, with an error naming the fixture file and the 1-based line number. The CardDemo sample set has no TRANSACT ASCII file, so the daily transaction file is the transaction source. Target paths (4 files):
>
> > SCOPE-QUESTION (Resolved): The capability names only the loader core's short-line, sign and timestamp rules and skips every row with an unknown card or type and category pair, while `docs/data-model.md` § Fixture rules checks every row in full before use and fails a malformed row instead of skipping it. Resolution: the loader validates every line before use through `CardDemoRecordParser` with the layout this loader declares and through its row check on the CS-09-001 extension point. The short-line, sign and timestamp rules named above are part of these checks, and the parser stays owned by CS-09-001.
> >
> > - Accepted lengths: 350 bytes, or 330 bytes with the FILLER wholly absent. Any other length is malformed.
> > - DALYTRAN-ID is text marked not blank and the key: it meets `^[\s\S]{16}$`, the not-blank mark rejects 16 spaces, and a line repeating the transaction ID of an earlier line is malformed.
> > - DALYTRAN-TYPE-CD is text marked not blank: it meets `^[\s\S]{2}$`, and the not-blank mark rejects two spaces. DALYTRAN-CAT-CD is 9(04) and DALYTRAN-MERCHANT-ID 9(09) unsigned digits. DALYTRAN-AMT is an S9(09)V99 signed amount. DALYTRAN-CARD-NUM is text marked not blank.
> > - DALYTRAN-ORIG-TS is a timestamp whose blank the parser reads as no value and this loader's row check rejects as malformed. DALYTRAN-PROC-TS is a timestamp whose blank takes the original timestamp as above.
> > - DALYTRAN-SOURCE, DALYTRAN-DESC, DALYTRAN-MERCHANT-NAME, DALYTRAN-MERCHANT-CITY and DALYTRAN-MERCHANT-ZIP are text with no content check.
> > - Each row passes every check before card-to-account resolution, timestamp derivation, parent-key checks or its write. Only then is a row with an unknown card or an unknown type and category pair skipped and counted, so category `X001`, a blank type code or a blank card number fails as malformed and is never skipped.
> > - The first malformed line in file order fails the load with an error naming `dailytran.txt` and the 1-based line number. The transaction rolls back, so `transaction_record` holds no row from that load, and the load report holds no `transaction_record` entry. No later-ordered loader starts, the entities committed earlier at orders 1–5 keep their rows and report entries, and the job exits with a non-zero status.
> > - Every sample row passes every check, so AC1 stands.
>
> - `card-servicing-service/src/main/resources/fixtures/dailytran.txt`: a byte-for-byte copy of the sample daily transaction file (300 lines × 350 bytes), made once at implementation and never regenerated at build or run time. Nothing reads `app/` at build, test or run time.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/TransactionFixtureLoader.java`: the transaction entity loader, holding its record layout, its load order, its card-to-account resolution from the loaded cross-reference rows, its parent-key checks against loaded cross-reference and category rows, the derived processed-timestamp rule and its row writer over the `TransactionRecordRepository` of CS-10-001.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/fixture/TransactionFixtureLoaderTest.java`: unit tests that drive the loader through the recording transaction manager of CS-09-001, so the unit suite needs no store. The non-blank processed-timestamp case of AC6 is a sample line built in the test with columns 305–330 set to `2024-03-05-14.07.09.123456`.
> - `card-servicing-service/src/test/resources/fixtures/dailytran-unknown-keys.txt`: two constructed 350-byte rows, each with a transaction ID the sample does not hold and every other field taken from a sample row. One names card `9999999999999999`, which no cross-reference row holds. The other names the type and category pair `09`/`0001`, which no category row holds.
>
> In scope:
>
> - The byte-for-byte copy of the sample daily transaction file.
> - The transaction entity loader and its record layout.
> - Amount sign decoding and both timestamp forms, through the loader core.
> - The derived processed timestamp and its count.
> - Card-to-account resolution through the loaded cross-reference rows.
> - FK skip counting for unknown cards and unknown type and category pairs.
> - The `transaction_record` load-report entry.
> - The loader unit tests and the two constructed unknown-key rows.
>
> Not in this story:
>
> - The `transaction_record` table, its entity and its repository (CS-10-001).
> - Cross-reference loading (CS-09-003).
> - The parser core, the FK skip mechanism, the load report and the extension point (CS-09-001).
> - Synthetic transaction volume (CS-10-008).
> - The Transaction Activity stub that reads the rows (CS-10-004).
> - The container-backed load proof (CS-10-007).
>
> ### 2. Acceptance Criteria
>
> - AC1: Loading the sample file records `transaction_record` in the load report as 300 loaded, 0 skipped and 300 derived processed timestamps, and the entry is written only after the load transaction commits.
> - AC2: After the load, each account from `00000000001` to `00000000050` holds exactly six rows.
> - AC3: Account `00000000001` holds exactly these six rows, all on card `9680294154603697`: `0000000776200014` type `01` category `0001` amount `985.22`; `0000000755736377` `01`/`0001` `906.44`; `0000000624335286` `01`/`0001` `598.44`; `0000000555363230` `01`/`0001` `484.99`; `0000000532120892` `03`/`0001` `-70.77`; `0000000503557384` `01`/`0001` `81.44`.
> - AC4: Exactly 50 loaded amounts are negative. Raw `0000000707P` decodes to `-70.77`, raw `0000009852B` to `985.22` and raw `0000007630}` (transaction `0000000274427056`) to `-763.00`.
> - AC5: `2022-06-10 19:27:53.000000` and `2022-06-10-19.27.53.000000` both parse to the instant `2022-06-10T19:27:53.000000Z`.
> - AC6: A row with a blank processed timestamp receives a processed timestamp equal to its original timestamp and is counted as derived, while a row with a non-blank processed timestamp keeps it and is not counted.
> - AC7: A constructed row whose card number has no cross-reference record is skipped and counted as skipped, and every other row still loads.
> - AC8: A constructed row whose type and category pair has no category reference row is skipped and counted as skipped.
> - AC9: Every loaded row's `account_id` equals the cross-reference account of its card number, so the rows on card `9680294154603697` carry `00000000001`.
> > SCOPE-QUESTION (Resolved): Which criterion covers a malformed daily transaction line? None of AC1–AC9 does. Resolution: AC10–AC14 below, run in `TransactionFixtureLoaderTest` through the recording transaction manager of CS-09-001, over copies of the sample file built in the test that differ from the class-path copy only in the change named. Each failure names `dailytran.txt` and the 1-based line number, and no row is counted as skipped.
> >
> > - AC10: A copy whose line 2 is cut to 330 bytes loads that row with the same stored values as its 350-byte form, while a line 2 of 329, 331, 349 or 351 bytes fails at line 2.
> > - AC11: Each of these changes at line 2 fails at line 2: category `X001`; a type code of two spaces; a merchant ID holding a letter; `?` at column 143; original timestamp `2022-13-10 19:27:53.000000`, `2022-06-10 24:00:00.000000` or 26 spaces; processed timestamp `2022-06-10 19:27:60.000000`; a card number of 16 spaces; and a transaction ID of 16 spaces.
> > - AC12: A copy whose line 2 repeats the transaction ID of line 1 fails at line 2.
> > - AC13: A copy whose line 2 names card `9999999999999999`, which no cross-reference row holds, and category `X001` fails at line 2 as malformed rather than being skipped.
> > - AC14: After any failure of AC10–AC13, `transaction_record` holds no row from that load, the load report holds no `transaction_record` entry while the entries of the earlier-ordered entities stay, no later-ordered loader starts and the job exits with a non-zero status.
>
> ### 3. Dependencies
>
> - CS-10-001 — creates the target table and its entity.
> - CS-09-003 — loads the cross-reference rows that give each card its account.
>
> Risk: a sign or offset decoding slip silently corrupts every downstream amount.

## 4. Design

- Diagram: [Logical architecture](../../../../architecture/logical-architecture.mmd)
- Data model: [Fixture mapping](../../../../data-model.md#fixture-mapping)
- Data model: [Fixture rules](../../../../data-model.md#fixture-rules)
- Data model: [transaction_record](../../../../data-model.md#transaction_record)
- Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Applies: `TransactionFixtureLoaderTest` covers the record layout, sign decoding, both timestamp forms, the derived processed timestamp and the skip rules behind AC1–AC9, and the malformed-row cases behind AC10–AC14 (the 330-byte line against 329, 331, 349 and 351 bytes, the field and blank checks at line 2, the repeated transaction ID, the malformed row that also names an unknown card, and the rollback-and-stop disposition), through the recording transaction manager of CS-09-001. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass; at least 80% line coverage per module.
- Integration — Applies, through the Transaction Activity integration suite CS-10-007, which loads the sample into the `postgres:16.6` container database and reads the stored rows back. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic (CS-10-007 for this epic) covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — sample load only; the volume load belongs to CS-10-008. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:performanceTest`, `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms; applies to balance-retrieval and drill-down stories.
- End-to-end — Not applicable — the end-to-end steps that read these rows are owned by CS-03-001 and CS-04-001. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:e2eTest`, `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — in-memory card lookup.

Rationale: resolving accounts before insert keeps rows complete and unknown cards counted.

- Rejected: Design Option B, a post-load account update, which the schema refuses.

### Shared by both options

- Topology: one load at stack start into the relational store, outside the request path.
- Naming: the report entry names the transaction table.
- Addressing: rows are keyed by transaction identifier and carry their account.
- Isolation: one load transaction, its report entry written after commit.
- Fallback: a valid row naming no loaded card or category is skipped and counted, never repaired.
- Provisioning: the sample file is copied into the service module, never read from the mainframe source.
- Cut-over: the loader retires with the stubs, since the real service owns its data.
- Failures: a malformed line, named by file and line number, or a failed write or commit rolls back only this entity, starts no later loader and fails the job.

### Design Option A — in-memory card lookup

The loader first builds a lookup of each committed card's account, fifty for the sample. Only a line passing every record check meets it, so unknown cards are caught before insert. Every other row is written with its account.

How it behaves:

- A blank processed timestamp takes the original timestamp and counts as derived, and a non-blank one is kept uncounted.
- Rows with equal processed timestamps, as in the sample, list by transaction identifier.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | In-memory card lookup (vs post-load update) | account known before insert | memory grows with cards |
| 2 | Skip before insert (vs foreign-key rejection) | load continues with exact counts | loader repeats key rules |
| 3 | Derived processed timestamp (vs left empty) | listings order deterministically | stored row hides the blank |
| 4 | Checks before lookup (vs skipping every miss) | malformed rows fail rather than skip | one bad byte stops the load |

Effort and risk: moderate effort, low risk with fifty sample cards.

### Design Option B — post-load account update

Rows load without an account, then one update fills it from the cross-references. Unmatched rows are removed and counted as skipped.

How it differs:

- The first insert fails, as the account column is never empty.
- Report counts wait for the update.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Account filled after load (vs in-memory lookup) | no lookup in memory | the schema refuses each insert |
| 2 | Unknown cards found by update (vs pre-insert check) | one pass over the cross-references | the card link refuses them at insert |

Effort and risk: small effort, but the load breaks under the unchanged schema.

### Recommendation

- Option: Design Option A
- Why: every insert meets the schema, with unknown cards counted first.
- Strongest argument against: the lookup grows with synthetic card volume.
- Shared items that do not decide the choice: decoding, timestamps, record checks and the load report.
- Flip conditions: more cards than memory holds and a schema accepting rows without an account.

## Design Validation

### Option A

Settled assumptions:

- All 300 sample rows name cards committed before this load and break no foreign key.
- Every sample row passes every check and has a blank processed timestamp.

Open assumptions:

- Whether real feeds carry non-blank hyphen-and-dot processed timestamps Open → a real feed sample.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | every row resolves with a derived timestamp |
| AC2 | Holds | each card's account holds six transactions |
| AC3 | Holds | fields are read by position |
| AC4 | Holds | the last amount character carries sign and digit |
| AC5 | Holds | both forms parse as universal time |
| AC6 | Holds | only blank processed timestamps are derived |
| AC7 | Holds | the lookup misses the card before insert |
| AC8 | Holds | the category check precedes insert |
| AC9 | Holds | the lookup supplies each card's account |
| AC10 | Holds | only 350 or 330 bytes pass |
| AC11 | Holds | the layout and row check reject each change |
| AC12 | Holds | the layout keys on transaction identifier |
| AC13 | Holds | checks run before any skip |
| AC14 | Holds | the loader core rolls back and stops later loaders |

### Option B

Settled assumptions:

- The schema requires a loaded account, card and category on every row.

Open assumptions:

- Whether real feeds carry non-blank hyphen-and-dot processed timestamps Open → a real feed sample.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | the first insert fails the load |
| AC2 | Breaks | no row is stored |
| AC3 | Breaks | no row is stored |
| AC4 | Breaks | no amount is stored |
| AC5 | Holds | both forms parse as universal time |
| AC6 | Breaks | no derived timestamp is stored or reported |
| AC7 | Breaks | the card link refuses the row uncounted |
| AC8 | Breaks | the category skip is never committed |
| AC9 | Breaks | no stored row carries an account |
| AC10 | Breaks | the 330-byte row is never stored |
| AC11 | Conditional | it holds only if no earlier insert failed |
| AC12 | Conditional | it holds only if no earlier insert failed |
| AC13 | Conditional | it holds only if no earlier insert failed |
| AC14 | Holds | the loader core rolls back and stops later loaders |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-10-001, CS-09-003 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Transaction Activity Integration |
| Epic Link | CS-10 |
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
