# CS-09-003: Card Data fixture load

Parent epic: [CS-09 Card Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want the sample accounts, cross-references and category balances loaded with zero skipped rows, so that every test reads the same known records.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVACT03Y · XREF-ACCT-ID
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: three entity loaders read the CardDemo sample accounts, card cross-references and category balances from copies carried on the class path and write the `account`, `card_cross_reference` and `category_balance` tables. They use the parser, overpunch decoding, FK skip-and-count rule, per-entity transaction and load report of the CS-09-001 loader core and never re-implement them. The record structures are `Data Layouts · CVACT01Y` (account), `Data Layouts · CVACT03Y` (card cross-reference) and `Data Layouts · CVTRA01Y` (category balance), at the 1-based positions that `docs/data-model.md` lists under Fixture mapping. Target paths:
>
> - Sample file copies, each byte-identical to its CardDemo ASCII sample file, made once at implementation and never regenerated at build or run time:
>   > SCOPE-QUESTION (Resolved): Nothing above shows that a copy stays byte-identical once nothing may read `app/`, and a size or line-count check cannot show it, because a same-width change keeps both. Resolution: at the one-time copy, the SHA-256 digest of each reference sample file is computed and recorded as a constant in that entity's loader unit test: `AccountFixtureLoaderTest` for `acctdata.txt`, `CardCrossReferenceFixtureLoaderTest` for `cardxref.txt` and `CategoryBalanceFixtureLoaderTest` for `tcatbal.txt`. Each test computes the digest of its class-path copy with the JDK's `java.security.MessageDigest` and requires it to equal the recorded constant, so any changed byte, a same-width amount or identifier change included, fails the unit suite. Nothing reads `app/` after the copy, and no file or dependency is added, so the 10 files touched stand.
>   - `card-servicing-service/src/main/resources/fixtures/acctdata.txt`, a copy of `Sample data · acctdata.txt` (50 lines × 300 bytes).
>   - `card-servicing-service/src/main/resources/fixtures/cardxref.txt`, a copy of `Sample data · cardxref.txt` (50 lines × 36 bytes, FILLER absent).
>   - `card-servicing-service/src/main/resources/fixtures/tcatbal.txt`, a copy of `Sample data · tcatbal.txt` (50 lines × 50 bytes).
> - Entity loaders under `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/`. Each declares its record layout and its load order and registers through the `FixtureLoader` entity-loader extension point with its entity name, its rows read through `CardDemoRecordParser` from its class-path copy, its parent-key check and its row writer over the CS-09-002 repositories:
>   - `AccountFixtureLoader.java`: entity `account`, load order 3, layout `Data Layouts · CVACT01Y` positions 1–122, with FILLER 123–300 ignored.
>   - `CardCrossReferenceFixtureLoader.java`: entity `card_cross_reference`, load order 4, layout `Data Layouts · CVACT03Y` positions 1–36, with the parent-key check against loaded accounts.
>   - `CategoryBalanceFixtureLoader.java`: entity `category_balance`, load order 5, layout `Data Layouts · CVTRA01Y` positions 1–28, with parent-key checks against loaded accounts and loaded type and category pairs.
>   - Load orders 1 and 2 belong to the reference-data loaders of CS-13-001, so all three Card Data entities load after the reference data, and the transaction loader of CS-10-002 follows them.
> - `card-servicing-service/src/test/java/com/example/cardservicing/support/CardDataFixtureSet.java`: the small in-memory Card Data fixture set for unit suites. It is built with no store connection by the same parser and the same three layouts from the same three class-path copies, so it holds all 50 sample accounts with their cross-references and category balances. This is the set the CS-09-004 in-memory provider serves.
>   > SCOPE-QUESTION (Resolved): The same parser and the same three layouts apply the same record checks to the fixture set, so a copy failing any check fails the set's build with an error naming the fixture file and the 1-based line number, and no set is returned.
> - Unit tests under `card-servicing-service/src/test/java/com/example/cardservicing/integration/fixture/`: `AccountFixtureLoaderTest.java`, `CardCrossReferenceFixtureLoaderTest.java` and `CategoryBalanceFixtureLoaderTest.java`. They drive the loaders through the recording transaction manager of CS-09-001, so the unit suite needs no store.
> - Together these are the 10 files touched.
>
> Rules:
>
> - Cross-reference line length: 36-byte lines (the sample form, FILLER absent) and 50-byte lines (FILLER present) are both accepted, and the FILLER is ignored.
> - ACCT-ADDR-ZIP (columns 103–112) and ACCT-GROUP-ID (columns 113–122) are loaded strictly by copybook offset and never re-aligned. Trailing padding is removed, and a group of ten spaces is stored as NULL. The sample rows therefore carry `addressZip` `A000000000` and no `groupId`.
> - A row whose foreign key names no loaded parent row is skipped and counted per entity in the load report: a cross-reference or category balance naming no loaded account, and a category balance naming no seeded type and category pair.
>   > SCOPE-QUESTION (Resolved): A row's parent keys are tested only after the row passes every record check of the layouts declared beneath the short-line rule below. A malformed row is never skipped: it fails its entity's load.
> - Load order: account → cross-reference → category balance, after the reference data. Each entity loads in its own transaction, and its load-report entry is written after that transaction commits.
> - A line shorter than its layout's last non-filler end column (122 for accounts, 36 for cross-references, 28 for category balances) fails that entity's load under the loader core's short-line rule, with an error naming the fixture file and the 1-based line number.
>   > SCOPE-QUESTION (Resolved): The rule above names only a short line, while `docs/data-model.md` § Fixture rules checks every line in full before use through the record checks of the CS-09-001 loader core. Resolution: each loader's layout declares what those checks need, and the parser checks every line in full before any of its values is converted, mapped to NULL, tested for a parent key or written.
>   >
>   > - Account (`acctdata.txt`): accepted lengths 300 and 122 bytes. ACCT-ID is 9(11) unsigned digits and the key. ACCT-ACTIVE-STATUS is text with no check. The five S9(10)V99 amounts, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT, are signed amounts. ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE and ACCT-REISSUE-DATE are dates. ACCT-ADDR-ZIP is text with no check, so `A000000000` is kept. ACCT-GROUP-ID is text whose blank is accepted and stored as NULL.
>   > - Cross-reference (`cardxref.txt`): accepted lengths 50 and 36 bytes. XREF-CARD-NUM is text marked not blank and the key. XREF-CUST-ID is 9(09) and XREF-ACCT-ID 9(11) unsigned digits.
>   > - Category balance (`tcatbal.txt`): accepted lengths 50 and 28 bytes. TRANCAT-ACCT-ID is 9(11) and TRANCAT-CD 9(04) unsigned digits. TRANCAT-TYPE-CD is text marked not blank: it meets `^[\s\S]{2}$`, and the not-blank mark rejects two spaces. TRAN-CAT-BAL is an S9(09)V99 signed amount. The key is the account, type and category together.
>   > - The first malformed line in file order fails its entity's load with an error naming the fixture file and the 1-based line number. The entity's transaction rolls back, so its table holds no row from that load, and the load report holds no entry for it. No later-ordered loader starts, entities committed earlier keep their rows and report entries, and the job exits with a non-zero status.
>   > - The checks keep the 36-byte cross-reference line, the blank group, the letter-led zip and any FILLER content: spaces in `acctdata.txt` and zeros in `tcatbal.txt`. Every sample row passes every check, so AC1 stands.
> - The loaders read only the three class-path copies and write only the three tables. Nothing reads `app/`, or any file outside the class path, at build, test or run time.
>
> In scope:
>
> - The three sample file copies.
> - The three entity loaders and their record layouts.
> - The load order after the reference data.
> - The 36-byte and 50-byte cross-reference line tolerance.
> - The strict-offset rule for address zip and group ID.
> - The FK skip counts per entity.
> - The in-memory Card Data fixture set.
> - The three loader unit tests.
>
> Not in this story:
>
> - Table DDL and the entity mapping (CS-09-002).
> - Parser core, FK skip mechanism, load report and extension point (CS-09-001).
> - Transactions (CS-10-002).
> - Synthetic volume (CS-09-007).
> - Customer records (`Data Layouts · CVCUS01Y`), which the fixture set does not include; their screen fields are flagged gaps per the data model.
>
> ### 2. Acceptance Criteria
>
> - AC1: After the sample load from the three class-path copies, the load report holds exactly one entry per entity: `account` 50 loaded / 0 skipped, `card_cross_reference` 50 loaded / 0 skipped and `category_balance` 50 loaded / 0 skipped, each with 0 derived timestamps. The three tables hold 50 rows each.
> - AC2: Account `00000000001` stores active status `Y`, current balance `194.00` (encoded `00000001940{`), credit limit `2020.00`, cash credit limit `1020.00`, current cycle credit `0.00`, current cycle debit `0.00`, open date `2014-11-20`, expiration date `2025-05-20`, reissue date `2025-05-20`, address zip `A000000000` and a NULL group ID. Every amount reads back at scale 2.
> - AC3: An account line equal to the sample line of `00000000001` except that columns 103–112 hold `12345` followed by five spaces and columns 113–122 hold `GRP0000001` stores address zip `12345` and group ID `GRP0000001`. No character from columns 103–112 appears in the group ID, and no character from columns 113–122 appears in the address zip.
> - AC4: The cross-reference for account `00000000001` stores card `9680294154603697` and customer `000000001`, with the leading zeros of the customer and account identifiers kept. Its category balance stores type `01`, category `0001` and balance `0.00`.
> - AC5: Boundary: account `00000000050` stores card `0500024453765740` and customer `000000050`. The stored account identifiers run `00000000001`–`00000000050` with no gap, and no `account`, `card_cross_reference` or `category_balance` row exists for `00000000051` or `00000000000`.
> - AC6: The 36-byte line `968029415460369700000000100000000001`, and a 50-byte line holding the same 36 bytes followed by 14 spaces, each load into an empty cross-reference table as one row with equal card number, customer and account values, and each load reports 1 loaded / 0 skipped. A 35-byte cross-reference line fails the cross-reference load with an error naming the fixture file and its 1-based line number.
> - AC7: A cross-reference file holding the 50 sample lines plus one line for card `9999999999999999`, customer `000000099` and account `00000000099`, an account the account load did not store, loads with `card_cross_reference` 50 loaded / 1 skipped. No row for card `9999999999999999` exists, and the 50 sample rows are stored.
> - AC8: A category-balance file holding the 50 sample lines plus one line for account `00000000001`, type `09` and category `0001`, a pair absent from the seeded reference data, loads with `category_balance` 50 loaded / 1 skipped, and no row with type `09` exists. A line naming account `00000000099` with type `01` and category `0001` is skipped and counted the same way.
> - AC9: An account file whose second line is 121 bytes long, ending before column 122 where ACCT-GROUP-ID ends, fails the account load with an error naming `acctdata.txt` and line 2. The account transaction rolls back, so `account` holds no row from that load, the load report holds no `account` entry, and the cross-reference and category-balance loaders do not start. A line of exactly 122 bytes loads.
> - AC10: The three loaders register with load orders 3 (`account`), 4 (`card_cross_reference`) and 5 (`category_balance`), after the reference-data orders 1 and 2. The cross-reference load starts only after the account load commits, and the category-balance load starts only after the cross-reference load commits.
> - AC11: The three class-path copies under `fixtures/` measure 50 lines of 300 bytes (`acctdata.txt`), 50 lines of 36 bytes (`cardxref.txt`) and 50 lines of 50 bytes (`tcatbal.txt`), each line ended by a single line feed. With the process working directory set to an empty temporary directory, the sample load still meets AC1, and the loaders open no file outside the class path.
>   > SCOPE-QUESTION (Resolved): AC11 also requires the SHA-256 digest of each class-path copy, computed in its loader unit test, to equal the constant recorded for that file at the one-time copy. A copy with one amount digit changed, its size and line count kept, fails the unit suite.
> - AC12: The in-memory Card Data fixture set, built with no store connection, holds 50 accounts, 50 cross-references and 50 category balances. For `00000000001` it returns account, cross-reference and category-balance values equal, field for field, to the rows the three loaders write for AC2 and AC4.
> > SCOPE-QUESTION (Resolved): Which criterion covers the record checks of the three layouts? None of AC1–AC12 covers a line length other than 35, 36, 50, 121, 122 and 300 bytes, a field check, a repeated key, or a malformed row that also breaks a parent key. Resolution: AC13–AC18 below, run in the three loader tests, the loader cases through the recording transaction manager of CS-09-001, over copies built in the test that differ from the class-path copy only in the change named. Each failure names the fixture file and the 1-based line number, and no row is counted as skipped.
> >
> > - AC13: Account lines of 123, 299 and 301 bytes each fail the account load (121 bytes is AC9). Cross-reference lines of 37, 49 and 51 bytes each fail the cross-reference load (35 bytes is AC6). A category-balance copy whose line 1 is cut to 28 bytes loads with stored values equal to those of its 50-byte form, while category-balance lines of 27, 29, 49 and 51 bytes each fail.
> > - AC14: Each of these changes at line 2 fails its entity's load at line 2: ACCT-ID `0000000000A`; `?` at column 24, the current-balance sign position; open date `2022-02-30`; an expiration date of 10 spaces; a cross-reference card number of 16 spaces; a customer ID holding a space; a category-balance type code of two spaces; category `X001`; and `?` at column 28, the balance sign position.
> > - AC15: Each of these line-2 keys, repeating the key of line 1, fails its entity's load at line 2: ACCT-ID `00000000001` in the account file, card `9680294154603697` in the cross-reference file, and account `00000000001` with type `01` and category `0001` in the category-balance file.
> > - AC16: A category-balance line naming account `00000000099`, which the account load did not store, and category `X001` fails the category-balance load as malformed rather than being skipped.
> > - AC17: After any failure of AC13–AC16, the failing entity's table holds no row from that load, the load report holds no entry for it, no later-ordered loader starts, entities committed earlier keep their rows and report entries, and the job exits with a non-zero status.
> > - AC18: The in-memory Card Data fixture set built from any failing copy of AC13–AC16 fails with an error naming the fixture file and the 1-based line number, and no set is returned.
>
> ### 3. Dependencies
>
> - CS-09-002 — creates the account, card_cross_reference and category_balance tables this load writes, and the repositories its row writers use.
>
> Risk: The zip and group position quirk in the sample can tempt a reinterpretation, so the strict-offset values are asserted.

## 4. Design

- Diagram: [Logical architecture](../../../../architecture/logical-architecture.mmd)
- Data model: [Fixture mapping](../../../../data-model.md#fixture-mapping), [Fixture rules](../../../../data-model.md#fixture-rules), [Account](../../../../data-model.md#account), [Card cross reference](../../../../data-model.md#card_cross_reference), [Category balance](../../../../data-model.md#category_balance)
- Technical requirements: [Component catalogue](../../../../technical-requirements.md#component-catalogue)

Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; pass when all tests pass with ≥80% line coverage per module. The three loader tests cover AC1–AC11 through the recording transaction manager of CS-09-001, including the SHA-256 digest of each class-path copy against the constant recorded at the copy and a same-size copy with one amount digit changed failing it, and the in-memory equality test covers AC12. Cases added by AC13–AC18: the account, cross-reference and category-balance lengths outside the two accepted lengths, the 28-byte category-balance line, the digit, amount, date and not-blank field checks at line 2, the repeated key in each file, the malformed category-balance row that also names no loaded account, the rollback-and-stop disposition after each failure, and the in-memory set built from a failing copy.
- Integration: Applies, through the Card Data integration suite CS-09-006, which loads the sample into the `postgres:16.6` container and reads the stored rows back. Command `./gradlew :card-servicing-service:integrationTest`; pass when all suites pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — this story is a load step, not a request path. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable here — the sample rows are observed by the end-to-end step CS-01-004. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A, load from copies of the sample files carried on the class path.

Rationale: sample file copies carried inside the service package keep the fixture set with the service wherever it runs, and one parser builds both the relational load and the in-memory fixture set.

- Rejected: Option B, reading the reference repository's data folder at run time, because it breaks the rule that no deliverable reads the mainframe source application's folders and that the set moves unchanged.

### Shared by both options

- Topology: three loader steps inside the one-shot loader job, outside the request path.
- Naming: every stored field carries its data-model name.
- Addressing: the relational store is reached through the loader's connection settings only.
- Isolation: one transaction per entity, with each load report entry written after its commit.
- Fallback: none, because a stub has no card data to serve until the load commits.
- Provisioning: account, then cross-reference, then category balance, after the reference data.
- Cut-over: the real card data owner holds the real rows, and the sample file copies stay test-only.
- Failures: a malformed line, whether truncated, overlong or failing a field or key check, stops its entity and every later one with no report entry for it, and a broken parent link is skipped and counted only after its row passes every record check.

### Design Option A — sample file copies on the class path

The three sample files are copied once, byte for byte, into the service's resources and read from the class path. Three entity loaders declare their record layouts and register with the loader core in the order account, cross-reference, category balance, after the reference data. The in-memory fixture set for unit suites is built by the same parser from the same copies, so unit and relational values agree.

How it behaves:

- The account loader reads each line at its record positions, decodes the signed amounts and reads the zip and the group strictly at their own positions.
- A blank group becomes no value, and the zip keeps the letter-led value the sample carries in that position.
- Each loader accepts only its two line lengths, the full record and the end of its last real field, so the cross-reference loader accepts a line that ends after the account number and a line that carries the trailing filler.
- Every line passes the digit, amount, date, not-blank and repeated-key checks of its layout before any value is used, and only then is a cross-reference or category balance row whose parent link names no loaded row skipped and counted.
- A line that fails any check stops that entity with the file and the line number named, leaves no report entry for it, and no later loader starts.
- The load report gains one entry per entity after its transaction commits.
- A digest of each reference file taken at the copy is checked against the packaged copy, so any changed byte fails the unit suite.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Sample file copies on the class path (vs reading the reference folder) | the fixture set moves with the service | a later reference file change never reaches the copies |
| 2 | In-memory set from the same parser (vs hand-written values) | unit and relational values come from one source | every unit suite start parses three files |
| 3 | Strict offsets for zip and group (vs re-aligning the position quirk) | stored values match the record layout exactly | the sample zip holds a letter-led value |
| 4 | Both cross-reference line lengths accepted (vs padding the files) | the copies stay byte-identical to the sample | the loader accepts a second line length |

Effort and risk: low effort with three small layouts, with the risk confined to a later reference file change that never reaches its copy.

### Design Option B — reference folder read at run time

The loader reads the three sample files from the reference repository's data folder, found by a relative location from the working directory at run time. The parser, the record layouts, the load order and the skip rule are the same as in Option A. The in-memory fixture set reads the same folder.

How it differs:

- No copy exists inside the service package, so the reference folder must sit beside the service at run time.
- A container image of the service carries no fixture input unless the reference folder is mounted into it.
- Moving the service set into its own repository leaves the loader with no input.
- The unit suite finds its fixture set only inside the reference checkout.
- The rule that no deliverable reads the mainframe source application's folders is broken.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Reading the reference folder at run time (vs class-path copies) | no copy can drift from the reference files | the load depends on the reference checkout layout |
| 2 | In-memory set from the reference folder (vs from class-path copies) | every suite reads one physical source | the unit suite fails outside the reference checkout |
| 3 | Strict offsets for zip and group (vs re-aligning the position quirk) | stored values match the record layout exactly | the sample zip holds a letter-led value |
| 4 | Both cross-reference line lengths accepted (vs padding the files) | the reference files stay untouched | the loader accepts a second line length |

Effort and risk: lowest effort with no copy step, with high risk that the load finds no input once the set moves.

### Recommendation

- Option: Option A.
- Why: the fixture set travels with the service, so every suite and every container reads the same bytes.
- Strongest argument against: a later change to a reference file never reaches the copies, and the digests guard only the copies.
- Shared items that do not decide the choice: the load order, the offset rule, the record checks and the skip counts.
- Flip conditions: the reference data becomes a published artifact versioned with the service.

## Design Validation

### Option A validation

Settled assumptions:

- The sample holds zero parent-link violations in every entity.
- Each sample account has one card and one category balance.
- The zip position holds the same letter-led value in every sample account, and the group position is blank in every one.
- The reference data loads first, through the reference data tables story.
- Every sample row passes every record check.
- Each packaged copy equals its reference file byte for byte, as the digest recorded at the copy proves.
- A later change to a reference file never reaches the copies, because the copies are made once and never regenerated.

Open assumptions:

- Open → whether the in-memory set should shrink below the full sample → settled by the card data stub story's boundary tests.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the complete copies hold no broken parent link |
| AC2 | Holds | the account layout reads every field at its record position |
| AC3 | Holds | the zip and the group are read strictly at their own positions |
| AC4 | Holds | identifiers are carried as text with their leading zeros |
| AC5 | Holds | the copies hold exactly the fifty sample accounts |
| AC6 | Holds | the two accepted lengths end at the account number and at the full record |
| AC7 | Holds | the loader core skips a row whose account is not loaded |
| AC8 | Holds | the loader core skips a row whose category pair is not seeded |
| AC9 | Holds | the loader core rolls back an entity at its first line shorter than the last real field |
| AC10 | Holds | each loader declares its order after the reference data |
| AC11 | Holds | digest-checked copies travel inside the service package |
| AC12 | Holds | the in-memory set comes from the same parser and the same copies |
| AC13 | Holds | each layout accepts only its full record and the end of its last real field |
| AC14 | Holds | the layouts mark each digit, amount, date and not-blank field for the parser |
| AC15 | Holds | each layout names its key for the parser's repeat check |
| AC16 | Holds | the parent-key check runs only after every record check passes |
| AC17 | Holds | the loader core rolls back the entity and stops every later loader |
| AC18 | Holds | the in-memory set uses the same parser and layouts as the loaders |

### Option B validation

Settled assumptions:

- The sample holds zero parent-link violations in every entity.
- Each sample account has one card and one category balance.
- The reference repository's data folder holds the same three files.
- Every sample row passes every record check.

Open assumptions:

- Open → where the reference folder sits relative to the service at run time → settled by the test stack story.
- Open → whether the service set keeps living inside the reference repository → settled by the delivery repository handoff.

| AC | Verdict | Why |
|---|---|---|
| AC1 | At risk | the load finds its input only where the reference folder sits beside the service |
| AC2 | Holds | the same account layout reads the same bytes |
| AC3 | Holds | the zip and the group are read strictly at their own positions |
| AC4 | Holds | identifiers are carried as text with their leading zeros |
| AC5 | Holds | the reference files hold exactly the fifty sample accounts |
| AC6 | Holds | the two accepted lengths end at the account number and at the full record |
| AC7 | Holds | the loader core skips a row whose account is not loaded |
| AC8 | Holds | the loader core skips a row whose category pair is not seeded |
| AC9 | Holds | the loader core rolls back an entity at its first line shorter than the last real field |
| AC10 | Holds | each loader declares its order after the reference data |
| AC11 | Breaks | the loader opens files outside the class path by design |
| AC12 | Conditional | the unit suite finds its input only inside the reference checkout |
| AC13 | Holds | the same layouts accept only their full record and the end of their last real field |
| AC14 | Holds | the same layouts mark each digit, amount, date and not-blank field for the parser |
| AC15 | Holds | the same layouts name their keys for the parser's repeat check |
| AC16 | Holds | the parent-key check runs only after every record check passes |
| AC17 | Holds | the loader core rolls back the entity and stops every later loader |
| AC18 | Conditional | the unit suite finds its input only inside the reference checkout |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-09-002 |
| Status | TBD |
| Estimated Files Touched | 10 |
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
