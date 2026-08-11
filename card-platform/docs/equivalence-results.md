# Equivalence Results

This report records the observed parity results for the CardDemo fixture suite. The comparison runs against documented Common Business Oriented Language (COBOL) rules and the checked-in fixtures, never against a mainframe. Equivalence runs throughout the Maven reactor, never deferred to the end. Every count below comes from a run of the suite, and every locator resolves in the file it names. Design rationale belongs in the [decision log](decision-log.md).

## Comparison basis

A reader needs to know what "equivalent" was measured against before reading any result, so the basis comes first.

The comparison executes the Java implementations and independent source-rule references against the fixtures under `app/data/ASCII/`, checking them against expected results held in `card-platform/equivalence-tests/src/test/resources/expected/`. Those expected files are derived from documented source semantics and are checked in, one per fixture subject.

Each expected file is loaded by the class its own provenance header names, and **every data row is compared against a value re-derived from the fixtures or the source text**. A summary count is never accepted in place of the rows it summarises. Two properties make that claim checkable rather than asserted.

Thirteen of the fourteen files are read through one reader that records every row an assertion looks up. Each consuming class ends its row-by-row comparison by requiring that no row went unread. The fourteenth, `fixture-coverage.csv`, keeps a reader of its own and asserts the row count it declares.

Removing a row from any of them fails the lookup that named it. `DocumentationContractTest` reads every file's row count off disk, so the inventory this document publishes cannot drift from the files. The [asset consumption](#asset-consumption) table below lists every file, its row count and the class that reads it.

**The suite never calls the mainframe.** Two things make an offline basis the correct reading rather than a compromise. The user forbade the new services from calling into the mainframe at runtime. The user's own equivalence requirement permits comparison against the original COBOL "or its documented business rules", and that parenthetical is what the harness relies on.

The user's wording fixes three subjects for identical results, and each maps to a source paragraph a reviewer can open:

- account balance calculations at `app/cbl/CBTRN02C.cbl:L545-L560`;
- authorization decision logic at `app/cbl/CBTRN02C.cbl:L380-L420`;
- transaction categorization at `app/cbl/CBTRN02C.cbl:L467-L542`.

Two further subjects are covered because they diverge from the batch path: online bill payment at `app/cbl/COBIL00C.cbl:L203-L250`, and interest-rate resolution at `app/cbl/CBACT04C.cbl:L415-L460`.

The baseline is observed fixture behaviour, not a theoretical reading of the language reference. A vendor advisory documents defects in COBOL rounding and truncation where tolerant numeric parsing meets floating-point intermediates under some compile options. A run of the real fixtures therefore outranks a reading of the manual. That residual risk stays recorded here.

One point of honesty belongs in the same breath. The user's requirement names "limits, status checks", and the source posting path performs the limit test but **no** card-status and **no** account-status check. The limit test is reproduced; the two status checks are recorded as absent rather than invented, because inventing them would break the identical-results requirement stated in the same sentence. [Business-rule flags](business-rule-flags.md) items 3 and 4 carry both non-additions.

## How the suite runs

Run the suite from `card-platform/`:

```bash
mvn -B -ntp -pl equivalence-tests -am verify
```

**`mvn test` is not sufficient, and the reason trips people.** Surefire excludes `**/*EquivalenceTest.java`, and Failsafe includes that same pattern, bound to `integration-test` and `verify`. So `mvn test` reports success while executing none of the equivalence classes.

The suite is a first-class Maven module rather than a test directory bolted on at the end, because the user asked that equivalence testing not be deferred. `equivalence-tests` sits last of the ten reactor projects, since it depends on both shared libraries and all six services.

The continuous-integration workflow runs equivalence as its own stage, `Run equivalence tests`, invoking the same `verify` goal. No stage in that workflow carries `continue-on-error`, and the container-build stage lists the equivalence stage among its prerequisites, so the suite cannot be skipped or allowed to fail quietly. `PostingEquivalenceTest` obtains PostgreSQL through Testcontainers, and `ThreeConsumerAuthorizationFlowIT` obtains PostgreSQL and Kafka the same way; the other eight classes need no container. [Onboarding](onboarding.md) covers machine setup.

## Observed run

**What backs the figures below, and how a reader checks them.** They come from running the command named above against the tree this document is committed in. The repository retains no report artifact, because `target/` is not committed, so no figure here should be read as quoting a stored file.

Every figure is measurable instead, by one command:

```
cd card-platform && CI=true mvn -B -ntp clean verify && scripts/check-published-test-counts.sh
```

`scripts/check-published-test-counts.sh` counts the `testcase` elements of every Surefire and Failsafe report the build wrote, including this module's own. It compares each count with the figure published here and in the [platform guide](../README.md). It fails when a figure disagrees, naming both numbers, and it fails when a module that holds an integration class wrote no Failsafe report at all. Nothing in it is derived from anything this repository published.

The script runs after the build rather than inside it, and the reason is worth stating because the obvious alternative does not work. A test cannot measure the run it is part of. The report for its own class does not exist while it executes, and its own module's integration phase has not started. An in-build check therefore ends up comparing one published figure against another, and passes whenever both move together. The continuous-integration workflow runs the script as its own step after each `verify` stage, so a published figure that drifts fails the run.

Two further things are checkable without running anything. The class census below is static, so `ls` settles it. And `DocumentationContractTest` closes the per-class table against the subtotal it sums to, so a row cannot be edited without the subtotal following.

The workflow is where a report outlives its run: the unit, integration and equivalence stages each upload theirs as a named artifact under `if: always()`, with a fourteen-day retention and `if-no-files-found: error`.

Every count below comes from one run, and this is that run. Any other number published anywhere in this repository is either this run's or stale, so the run is identified before its results.

The date below is the date the run happened. A review found it naming the day before the run whose reports the counts were read from, which makes an identity that cannot be checked. Two things keep it checkable now. `DocumentationContractTest.thePublishedTestCountsAreTheOnesThisBuildMeasured` re-derives every count here from the reports of whatever build is running, so a count from an older run fails the build rather than ageing quietly. And `scripts/check-published-test-counts.sh --print` prints the measured numbers after a run, so the date and the numbers are restated together or not at all.

| Run identity | Value |
|---|---|
| Date | 2026-08-11 |
| Source tree | the delivered `card-platform/` tree this document is committed in, with nothing uncommitted and nothing under `app/`, `diagrams/` or `samples/` changed |
| Command | `CI=true mvn -B -ntp clean verify` from `card-platform/`, then `scripts/check-published-test-counts.sh` |
| Runtime | Eclipse Temurin OpenJDK 25.0.4+7, Apache Maven 3.9.16 |
| Container runtime | Docker Engine 29.7.0, images `postgres:18.4` and `apache/kafka:4.2.1` |
| Duration | Roughly nine minutes, all ten reactor projects reporting SUCCESS. Wall-clock time moves with the load the host is under; no count does, which is why no duration is published to the second |
| Maven output | zero `[WARNING]` lines across the whole run |
| Where the reports go | `target/surefire-reports/` and `target/failsafe-reports/` of each module, neither committed. The workflow keeps its copies as the `unit-test-reports`, `integration-test-reports` and `equivalence-test-reports` artifacts, for fourteen days |
| Counts read from | `scripts/check-published-test-counts.sh`, which counts the `testcase` elements in `target/surefire-reports/TEST-*.xml` and `target/failsafe-reports/TEST-*.xml`. That is the number Maven prints in its own per-module summary. Summing the `tests` attribute instead under-reports, because a report for a class holding `@Nested` classes lists every nested case and counts only its own |

The command completed successfully across all ten reactor projects.

The counts below describe the delivered tree rather than one moment, and the mechanism rather than a repeated run is what makes that true. `DocumentationContractTest.thePublishedTestCountsAreTheOnesThisBuildMeasured` re-derives the reactor totals from the reports of whatever build is running and compares them with the figures published here. A commit that changes the test population and leaves these tables alone fails that assertion. Wall-clock time is the one figure no assertion holds, because it moves with the load the host is under.

| Measure | Observed result |
|---|---:|
| Failsafe equivalence tests | 229 passed across the nine `*EquivalenceTest` classes |
| Failsafe end-to-end flow test in the same module | 6 passed in `ThreeConsumerAuthorizationFlowIT`, giving 235 for this module's whole Failsafe run |
| Surefire unit and contract tests in the same module | 566 passed |
| `*EquivalenceTest` classes executed | 9 |
| Required named equivalence classes | 6 present and passing |
| Checked-in expected-output files | 14, every one read by its declared consumer |
| Expected-output rows compared | 11,159 |
| Failures | 0 |
| Errors | 0 |
| Skipped tests | 0 |

The module holds nine classes matching `**/*EquivalenceTest.java`: the six the specification names, plus three added during implementation. The three added contribute 34 tests and the six named contribute the remaining 195, which is the 229 above. Failsafe here also selects `**/*IT.java`, and the one class that pattern matches contributes the remaining 6.

Every one of the fourteen files under `src/test/resources/expected/` is read row by row by the tests its comment line declares. Thirteen of them refuse to finish while any row is left unconsumed. The fourteenth holds itself to the row count it declares instead, which catches the same addition. `ExpectedOutputBindingContractTest` lists that directory from disk, so a new file is covered the moment it lands. It resolves each reader call's argument to the file that call opens, and requires the declaration and the call sites to agree in both directions.

| Expected-output file | Rows | Read by |
|---|---:|---|
| `dailytran-posting-results-model-b.csv` | 3,686 | `PostingEquivalenceTest` |
| `dailytran-authorization-decisions-model-a.csv` | 3,035 | `AuthorizationDecisionEquivalenceTest` |
| `dailytran-decimal-truncation-model-b.csv` | 743 | `DecimalTruncationEquivalenceTest` |
| `dailytran-category-balances-model-b.csv` | 690 | `PostingEquivalenceTest` and `DecimalTruncationEquivalenceTest` |
| `tcatbal-interest-accrual.csv` | 616 | `InterestCalculationEquivalenceTest` |
| `bill-payment-results.csv` | 532 | `BillPaymentEquivalenceTest` |
| `acctdata-final-account-state-model-b.csv` | 492 | `PostingEquivalenceTest` |
| `dailytran-reject-records-model-b.csv` | 388 | `PostingEquivalenceTest` |
| `discgrp-interest-rates.csv` | 236 | `InterestCalculationEquivalenceTest` |
| `synthetic-boundary-cases.csv` | 232 | `AuthorizationDecisionEquivalenceTest` |
| `fixture-coverage.csv` | 190 | `FixtureCoverageEquivalenceTest` |
| `cardxref-account-resolution.csv` | 163 | `AuthorizationDecisionEquivalenceTest` |
| `validation-messages.csv` | 136 | `ValidationEquivalenceTest` |
| `posting-summary.csv` | 20 | `PostingEquivalenceTest` and `AuthorizationDecisionEquivalenceTest` |

The whole-feed comparison drives both ledger consumers rather than one. The 262 approvals reach `TransactionAuthorizedConsumer` and the 38 refusals reach `TransactionDeclinedConsumer`. The reject rows of `app/cbl/CBTRN02C.cbl:L446-L465` are produced by the shipped consumer against a real database instead of being asserted from a hand-built fixture. Each consumer acknowledges on its own counter and records its own idempotency marker against its own consumed topic, which is what keeps the two counts independently checkable.

The reject record itself is compared as bytes. `renderRejectRecord` copies the fixture's own 350 bytes verbatim, exactly as `app/cbl/CBTRN02C.cbl:L447` copies the daily record, and appends the 80-byte trailer the source builds. The masked card number this platform adds lives on the persisted row only, and a dedicated test asserts the two representations are different so neither can quietly replace the other.

| Failsafe class | Tests |
|---|---:|
| `PostingEquivalenceTest` | 58 |
| `ValidationEquivalenceTest` | 50 |
| `DecimalTruncationEquivalenceTest` | 24 |
| `InterestCalculationEquivalenceTest` | 23 |
| `BillPaymentEquivalenceTest` | 22 |
| `FixtureCoverageEquivalenceTest` | 18 |
| `AuthorizationDecisionEquivalenceTest` | 18 |
| `IdentifierFidelityEquivalenceTest` | 9 |
| `CardSeedEquivalenceTest` | 7 |
| `ThreeConsumerAuthorizationFlowIT` | 6 |

The whole reactor ran 6,505 Surefire and 599 Failsafe tests in the same run, with zero failures, zero errors and zero skips. Those two figures belong here rather than in a service guide, because one run identity is easier to keep true than seven.

Here is where they came from, module by module. `scripts/check-published-test-counts.sh` compares every cell below against the reports of a completed build, so a figure in this table is measured rather than asserted.

| Reactor module | Surefire | Failsafe |
|---|---:|---:|
| `libs/event-contracts` | 335 | 0 |
| `libs/cobol-compat` | 130 | 0 |
| `services/authorization-service` | 833 | 53 |
| `services/ledger-posting-service` | 498 | 23 |
| `services/fraud-detection-service` | 728 | 74 |
| `services/notification-service` | 868 | 39 |
| `services/account-service` | 1,773 | 51 |
| `services/card-service` | 774 | 124 |
| `equivalence-tests` | 566 | 235 |
| **Reactor total** | **6,505** | **599** |

The two library modules carry no Failsafe figure because neither holds a class the integration patterns select: `**/*IT.java` and `**/*EquivalenceTest.java` match nothing under either. Every other module holds at least one, and the script fails when one of them writes no Failsafe report. That is the fail-open case a silently empty selection would otherwise leave green.

## Fixture inventory and results

Every record count and every width below was measured from the delivered file, not read from a copybook header.

| Fixture | Records | Width | Copybook layout | Primary consuming tests | Result |
|---|---:|---:|---|---|---|
| `app/data/ASCII/acctdata.txt` | 50 | 300 | `app/cpy/CVACT01Y.cpy` | Posting, authorization, bill payment, interest | PASS |
| `app/data/ASCII/carddata.txt` | 50 | 150 | `app/cpy/CVACT02Y.cpy` | Card seed and validation | PASS |
| `app/data/ASCII/cardxref.txt` | 50 | 36 | `app/cpy/CVACT03Y.cpy` declares 50 | Posting and authorization | PASS |
| `app/data/ASCII/custdata.txt` | 50 | 500 | `app/cpy/CVCUS01Y.cpy` | Validation and projection checks | PASS |
| `app/data/ASCII/dailytran.txt` | 300 | 350 | `app/cpy/CVTRA06Y.cpy` | Posting, authorization, decimal truncation | PASS |
| `app/data/ASCII/discgrp.txt` | 51 | 50 | `app/cpy/CVTRA02Y.cpy` | Interest and decimal truncation | PASS |
| `app/data/ASCII/tcatbal.txt` | 50 | 50 | `app/cpy/CVTRA01Y.cpy` | Posting and decimal truncation | PASS |
| `app/data/ASCII/trancatg.txt` | 18 | 60 | `app/cpy/CVTRA04Y.cpy` | Fixture coverage and validation | PASS |
| `app/data/ASCII/trantype.txt` | 7 | 60 | `app/cpy/CVTRA03Y.cpy` | Fixture coverage | PASS |

`dailytran.txt` is the primary posting fixture, and its 300 records are the largest single body of evidence in this report.

### Cross-reference width

`cardxref.txt` carries 36 characters per row where its copybook declares 50. The three populated fields account for the difference exactly. `XREF-CARD-NUM PIC X(16)` at `app/cpy/CVACT03Y.cpy:L5`, `XREF-CUST-ID PIC 9(09)` at `:L6` and `XREF-ACCT-ID PIC 9(11)` at `:L7` sum to 36. The text fixture omits the `FILLER PIC X(14)` declared at `:L8`.

`CardDemoFixtureLoader` is therefore **width-tolerant by necessity rather than by preference**: a reader that assumed the declared 50 bytes would fail on the delivered file. Business-rule flag 25 records the mismatch.

The `app/data/EBCDIC/` directory holds twelve files against the nine text fixtures. Three account for the difference: `ACCDATA` and `ACCTDATA` are both present, and the daily feed ships an extra `DALYTRAN.PS.INIT` variant. Two findings in that set matter here. The binary cross-reference measures 2500 bytes, which is 50 rows at the declared 50-byte width, so the binary set is the width authority wherever the two disagree. The binary set also carries `AWS.M2.CARDDEMO.USRSEC.PS`, and **the text set has no security-user fixture at all**, which is why signon fixtures are constructed rather than loaded.

## Results by test class

All nine classes sit in `com.carddemo.equivalence`. The six the specification requires:

| Test class | Subject | Assertion | Result |
|---|---|---|---|
| `PostingEquivalenceTest` | All 300 daily-transaction records | Final balances, category balances and reject reasons match the expected results record by record, with ordering, timestamp normalization and idempotency. Five checked-in assets read row by row, including the raw 350-character reject block | PASS — 58 tests |
| `AuthorizationDecisionEquivalenceTest` | Each of the four decline reasons | Correct code and verbatim description, correct short-circuit ordering, equality boundaries, missing rows, and the narrowed precision boundary. Four checked-in assets read row by row | PASS — 17 tests |
| `BillPaymentEquivalenceTest` | The online payment path | Balance reduced to zero and both cycle accumulators left untouched, unlike batch posting. Two checked-in assets read row by row | PASS — 22 tests |
| `InterestCalculationEquivalenceTest` | Rate rules and the default-group fallback | Rates resolve identically, one `DEFAULT` retry, failed retry, and the accumulator reset. Verified, not migrated. Two checked-in assets read row by row | PASS — 23 tests |
| `ValidationEquivalenceTest` | Field validation | The six card message texts and the credit-score range message reproduced character for character. Two checked-in assets read row by row | PASS — 50 tests |
| `DecimalTruncationEquivalenceTest` | All four money-moving arithmetic sites | Truncation produces the expected value, and half-up rounding produces a different one. Three checked-in assets read row by row | PASS — 24 tests |

Three supplementary classes cover fixture census, identifier fidelity and card seeding: `FixtureCoverageEquivalenceTest` 18 tests, `IdentifierFidelityEquivalenceTest` 9, and `CardSeedEquivalenceTest` 7. All pass.

### Authorization decisions

The source assigns exactly four reject reasons. The literal the source moves is shown first; the platform stores each as four characters.

| Code | Stored form | Description | Assignment |
|---|---|---|---|
| 100 | `0100` | `INVALID CARD NUMBER FOUND` | `app/cbl/CBTRN02C.cbl:L385`, text at `:L386` |
| 101 | `0101` | `ACCOUNT RECORD NOT FOUND` | `app/cbl/CBTRN02C.cbl:L397`, text at `:L398` |
| 102 | `0102` | `OVERLIMIT TRANSACTION` | `app/cbl/CBTRN02C.cbl:L410`, text at `:L411` |
| 103 | `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `app/cbl/CBTRN02C.cbl:L417`, text at `:L418` |

The short-circuit assertion matters as much as the codes. Paragraph `1500-VALIDATE-TRAN` performs the cross-reference lookup at `app/cbl/CBTRN02C.cbl:L371`, then tests the reason code at `:L372` and performs the account lookup only when it is still zero, at `:L373`. A card lookup failure must therefore report 100 and never fall through to 101.

No such gate separates reasons 102 and 103. The limit test closes at `:L413` and the expiry test opens at `:L414` unconditionally, so reason 103 overwrites reason 102 when both comparisons fail. The suite reproduces that ordering.

### Two evaluation models, and why both are published

The same 300 fixture records give two different decline counts, and neither is wrong. The count depends on whether account state carries forward between records, which is a property of the path being compared rather than of the data. Publishing one figure alone invites a reader to treat the other as an error. Both are named below, each labelled with the identifier the expected-output file carries, and each stated with the assumption that produces it.

| Model | Label in `posting-summary.csv` | Assumption | Records | Approved | Declined |
| --- | --- | --- | ---: | ---: | ---: |
| A | `A_STATELESS` | Each record is evaluated on its own against the seeded account state. Nothing a previous record did is visible, which is what a single synchronous authorization call sees | 300 | 287 | **13** |
| B | `B_CUMULATIVE_DECLINES_SKIP` | Account state carries forward, matching the rewrite at `app/cbl/CBTRN02C.cbl:L554`, and a declined record skips it, matching the gate at `:L211`. This is what the batch posting run does | 300 | **262** | **38** |

Model A is the authorization projection. Both cycle accumulators hold `0.00` across all 300 evaluations, so the tested value reduces to the transaction amount and only the limit comparison can fail. Every fixture-reachable decline therefore carries reason 102, and constructed records cover reasons 100, 101 and 103.

Model B is the ledger run, and its extra 25 declines are the accumulators doing their work. Each approved posting adds its amount to a cycle accumulator, the credit-limit rule subtracts that accumulator, and a later record on the same account is tested against a tighter figure. That is the same coupling [register item 5](business-rule-flags.md) records, observed over 300 records instead of argued about.

Model B carries nine further expected values, all checked in and all asserted:

| Expected field | Value | Derived from |
| --- | ---: | --- |
| `record_count` | 300 | `app/cbl/CBTRN02C.cbl:L202-L219` |
| `declined_event_count` | 38 | One `TransactionDeclined` per decline, never more |
| `return_code` | 4 | `app/cbl/CBTRN02C.cbl:L229-L230`, the source's own answer to a run with rejects |
| `transaction_row_count` | 262 | `app/cbl/CBTRN02C.cbl:L562-L579`. One row per approval and none per decline |
| `processed_event_count` | 262 | One idempotency marker per applied event. Additive |
| `posted_outbox_count` | 262 | One `TransactionPosted` per approval |
| `ledger_rejected_transaction_count` | 0 | No fixture record fails the feed-level checks that write a reject row |
| `category_balance` `row_count` | 100 | `app/cbl/CBTRN02C.cbl:L467-L542`, of which 99 are non-zero, 49 positive and 50 negative |
| Four `sha256` digests | See `posting-summary.csv` | Approved identifiers, declined outcomes, account projection state, category balance state. The digests cover canonical rows and contain no card numbers |

One figure deserves its own sentence, because a reader will look for it. `declined_event_count` equals `declined_count` exactly, at 38, which is what says no decline produced two events and none produced none.

### Bill-payment divergence

`app/cbl/COBIL00C.cbl:L224` copies the full opening balance into the transaction amount, `:L233` writes that transaction, and `:L234` reads `COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT`, with the account rewritten at `:L235`. The balance therefore reaches zero.

**Nothing in that path touches either cycle accumulator.** A search of the whole program for `ACCT-CURR-CYC-CREDIT` and `ACCT-CURR-CYC-DEBIT` returns no match. Batch posting does touch them, at `app/cbl/CBTRN02C.cbl:L547-L551`. Two paths, two behaviours, and the contrast test applies both rules to the same positive amount to prove the platform reproduces each.

All 50 fixture balances reached numeric zero. A constructed input gave both accumulators non-zero values, and the online rule left both unchanged.

The online path also writes one timestamp into both the origin and processing fields, at `app/cbl/COBIL00C.cbl:L231-L232`. Batch posting keeps the feed origin format and generates a different processing format.

### Interest carve-out

Interest is **verified but not migrated**, and no interest service exists.

The computation sits at `app/cbl/CBACT04C.cbl:L464-L465`, reading `COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200`, with the accumulation at `:L467`. The suite asserts the rate rules and the fallback, not a migrated calculation.

The fallback runs in two steps. Paragraph `1200-GET-INTEREST-RATE` at `app/cbl/CBACT04C.cbl:L415` reads the disclosure group, and a miss announces itself at `:L418` and `:L419`. The literal substitution follows at `:L437`, where `MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID` retries once under the default group. A second miss fails at `:L443-L458`.

`discgrp.txt` holds 51 rows: three groups carrying 17 type-and-category rows each. All 50 account fixtures carry ten spaces in `ACCT-GROUP-ID`, while the adjacent `ACCT-ADDR-ZIP` carries `A000000000`. The blank group is therefore a fixture property, not a parsing offset error. Every category row resolved through the `DEFAULT` fallback, and the `DEFAULT` rate for type `03`, category `0001`, is `0.00`.

A neighbouring paragraph in the same program is an unimplemented stub: `1400-COMPUTE-FEES` at `:L518` carries the body `To be implemented` at `:L519`.

`BillingCycleService` reproduces `app/cbl/CBACT04C.cbl:L353-L354` and nothing else. It zeroes both cycle accumulators and leaves the current balance alone, so it does not reproduce the interest add at `:L352`.

### Validation messages

The card validation band is `app/cbl/COCRDUPC.cbl:L189-L202`. Seven condition names sit in that band, and L192 repeats L190 character for character, so the band carries **six distinct texts**:

| Text | Locator |
|---|---|
| `Account number must be a non zero 11 digit number` | `:L190`, repeated verbatim at `:L192` |
| `Card number if supplied must be a 16 digit number` | `:L194` |
| `Card Active Status must be Y or N` | `:L196` |
| `Card expiry month must be between 1 and 12` | `:L198` |
| `Invalid card expiry year` | `:L200` |
| `Did not find this account in cards database` | `:L202` |

The credit-score range comes from `app/cbl/COACTUPC.cbl:L848-L849`, where `88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850` fixes both bounds. The suite reproduces the range and its message text, and it also checks the texts that sit outside the band so that a message cannot drift in unnoticed.

## Asset consumption

Every checked-in expected file, its row count and its readers are stated once, in the table under [observed run](#observed-run). They are not restated here, because they once were and the two tables drifted. This section reported one file at 18 rows with no assertion depending on it, and another as partitioned across four readers. The delivered harness reads 20 rows of the first through two classes, and gives all 215 rows of the second to one. A count is evidence, and evidence stated twice is evidence that can disagree with itself.

The binding is not editorial, and it is resolved rather than sampled. Each file's own comment line names its consuming tests. `ExpectedOutputBindingContractTest` reads the `String` constants each of those classes declares, follows every `ExpectedOutcomes.load` argument through them, and requires a call site that resolves to the file. A class that names a file and opens a different one fails, which a check for a name and a reader call in the same source could not tell apart. The same test refuses a reader argument it cannot resolve, and refuses a class that opens a file the file does not declare.

Row closure is proven per file. Thirteen files close by asserting that no row went unread, and `fixture-coverage.csv` closes by asserting the row count it declares against the rows it parsed. Those assertions end the walking tests rather than sitting in an `@AfterAll` method, so they hold however the runner orders the class. `DocumentationContractTest` reads the same comment lines and row counts off disk and requires the table above to state them. A file whose rows or readers change fails the build until the table follows.

`posting-summary.csv` is the smallest asset and it is asserted, not decorative. Its 20 rows carry the two evaluation models' run totals and the four `sha256` digests of section [two evaluation models](#two-evaluation-models-and-why-both-are-published). `PostingEquivalenceTest` answers every one of them from the run it drives through the real consumers, and refuses to finish while any row is unread. `AuthorizationDecisionEquivalenceTest` reads `model_a.declined_count` from its own instance, so the stateless decline count it derives is compared against a checked-in figure rather than against itself.

It was the one asset with neither the shared reader's per-row tracking nor a declared row count in its consumer. A row added to it went unread, and only the inventory check above noticed. It is now read through `ExpectedOutcomes`, the per-row reader the twelve other files that use it are read through. That makes thirteen of the fourteen assets — every one except `fixture-coverage.csv`, which keeps its own reader and its own row-count assertion.

One row shows why that mattered rather than being merely untidy. `posting.ledger_rejected_transaction_count` states that the posting consumer's own reject path writes nothing for this feed, and no assertion read it. The whole-feed comparison now counts reject rows after the authorized stream and before the declined stream, which is the only point in the run where the claim is measurable.

### The synthetic cases

`synthetic-boundary-cases.csv` collects every case the fixtures cannot reach, and all 232 of its rows are read by `AuthorizationDecisionEquivalenceTest`. The file says so itself: its `SUMMARY` topic carries `consuming_test_classes = 1` beside `total_cases = 20`, and the class asserts both.

The 20 cases fall into seven families, and the file's own summary rows state each count, which the class checks against the cases it read and against their sum:

| Case family | Cases | What each constructs |
|---|---:|---|
| Authorization declines | 5 | reasons 0100, 0101 and 0103, the expiry-equality boundary, and the record both the credit-limit and the expiry rule would refuse |
| Working-balance narrowing | 3 | a cycle credit at one billion, the largest value below it, and the amount that makes the narrowed balance equal the limit |
| Reason 0109 | 1 | the code `app/cbl/CBTRN02C.cbl:L556` sets and no source statement reads |
| Truncation | 1 | the negative category balance that separates truncation from both alternatives |
| Rate resolution | 5 | three matched groups including the all-zero-rate one, the default-group fallback, and the double miss that abends |
| Credit-score bounds | 4 | 299, 300, 850 and 851 against `88 FICO-RANGE-IS-VALID` |
| Picture-field ceiling | 1 | two authorized amounts whose sum passes the nine integer digits `TRAN-CAT-BAL` holds, and the store that keeps the low-order nine |

Four further topics carry no case. `COVERAGE` records how often each decline reason occurs in the 300-record feed, which is what makes a constructed case necessary at all. `SETUP` records the constructed cross-reference row the account-miss case needs. `SUMMARY` records the counts above.

`PROHIBITION` records the discipline the cases are held to. No fixture file is modified, no case is invented without the measurement that shows the fixtures cannot reach it, and the narrowing defect is reproduced rather than corrected. The ceiling case is the one addition that discipline has admitted. The `COVERAGE` row beside it reports that no seeded category balance comes within one integer digit of the field's ceiling.

Two other classes name the file without reading a row of it, which is why the readers column names one class. `ValidationEquivalenceTest` asserts the file is on the test classpath, as the evidence its own boundary rows defer to. `DecimalTruncationEquivalenceTest` names it as the place the truncation-versus-floor separation is recorded, having established the truncation-versus-half-up difference itself.

## The arithmetic sites

The measured finding comes first. **The `ROUNDED` phrase appears zero times across all 28 programs in `app/cbl/`.** Every arithmetic store therefore truncates toward zero, which is the COBOL default when no rounding phrase is present.

A second measurement sits beside it. **The `ON SIZE ERROR` phrase appears zero times across the same 28 programs.** Every store below therefore drops any digit past its field's width, holds the sign and continues, rather than reporting a condition. The `SYN-CATBAL-CEILING` case of `synthetic-boundary-cases.csv` constructs that boundary and register item 66 of [business-rule flags](business-rule-flags.md) records it.

The truncation claim rests on the sites below, and the set is small enough to enumerate in full. Four sites move money on the posting and payment paths, and the category balance is one of the four reached through two branches. Interest accrual appears as a fifth site because the suite verifies it, though the calculation is not migrated.

| Site | Locator | Operation |
|---|---|---|
| Overlimit working balance | `app/cbl/CBTRN02C.cbl:L403-L405` | `COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT` |
| Category balance, create branch | `app/cbl/CBTRN02C.cbl:L508` | `ADD DALYTRAN-AMT TO TRAN-CAT-BAL` before the write at `:L510` |
| Category balance, update branch | `app/cbl/CBTRN02C.cbl:L527` | `ADD DALYTRAN-AMT TO TRAN-CAT-BAL` before the rewrite at `:L528` |
| Account balance and cycle accumulators | `app/cbl/CBTRN02C.cbl:L547-L551` | `ADD DALYTRAN-AMT TO ACCT-CURR-BAL`, then to cycle credit at `:L549` when the amount is zero or positive, otherwise to cycle debit at `:L551`. Reproduced in two places, because the record is split across two services: `ledger-posting-service` writes its balance projection from the authorization event, and `account-service` writes the account record from the posted event |
| Online bill payment | `app/cbl/COBIL00C.cbl:L234` | `COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT` |
| Interest accrual | `app/cbl/CBACT04C.cbl:L464-L467` | Multiply by rate, divide by 1200, then accumulate |

Add and subtract over scale-two operands discard no fraction, so truncation and half-up rounding agree at every add and subtract site across all 300 fixture amounts. The interest divide is where they part. The chained fixture rows separate truncation from half-up on **28 of the 99** category-balance keys, and from floor on **0 of the 99**. The denominator is `category_balance.nonzero_row_count` in `posting-summary.csv`, which the harness measures as 99, and `dailytran-decimal-truncation-model-b.csv` carries the same 99 keys with `distinguishes_down_from_half_up` set on 28 of them. Floor separates from nothing here because every negative category row resolved to a zero rate.

A constructed negative case uses a real fixture rate of `25.00`, where `-763.00 × 25.00 ÷ 1200` gives `-15.89` under truncation and `-15.90` under half-up or floor. **`DecimalTruncationEquivalenceTest` asserts that truncation gives the expected value and that half-up gives a different one.** That second assertion turns a silent risk into a failing test. It is the only way a future contributor who "simplifies" the rounding mode finds out.

Parsing is in scope alongside arithmetic. `app/cbl/COTRN02C.cbl` parses at four sites: `:L204` and `:L218` use plain `FUNCTION NUMVAL`, while `:L383` and `:L456` use `FUNCTION NUMVAL-C`. The currency-aware variant accepts currency symbols and thousands separators, so it is **not** equivalent to constructing a decimal from a string, and a shared parser reproduces the tolerance. The account update program gates on a validity test before converting, at `app/cbl/COACTUPC.cbl:L2201`.

## Timestamp tolerance

The processing timestamp is `DB2-FORMAT-TS PIC X(26)` at `app/cbl/CBTRN02C.cbl:L159`, redefined from `:L160`. The redefinition ends in `DB2-MIL PIC 9(002)` at `:L173` and `DB2-REST PIC X(04)` at `:L174`. The routine that fills the field moves a hundredths value at `:L700`, then writes the literal `0000` into the remainder at `:L701`. The result reaches the transaction record at `:L438`.

The field therefore carries two significant fractional digits followed by four zeros. A service stamping a fresh instant differs from it in the last four digits of every record. The harness truncates to hundredths and zero-pads before comparing.

No equivalence assertion compares a raw timestamp. Raw comparison would fail on every record for a reason unrelated to the logic under test, and that is the kind of failure that gets a suite switched off. Register item 8 records the mechanism.

## Divergences

No unintended divergence between service behaviour and source rule was observed in the completed run. The section stays because its presence is what tells a reader the question was asked.

Four divergences are deliberate reproductions of source behaviour. Each names where its explanation lives, so that none reads as a defect in the platform.

One divergence runs the other way, and it is a departure from the source rather than a reproduction of it. The batch path declines a card that resolves no cross-reference row, at `app/cbl/CBTRN02C.cbl:L385-L387`, and writes the reject record of `:L446-L465` against the account `:L394` took out of that row. Where the read misses there is no such account, and the feed record at `app/cpy/CVTRA06Y.cpy` carries none of its own.

This platform therefore refuses the call with the text the synchronous ancestor uses at `app/cbl/COTRN02C.cbl:L625-L626`, and publishes no reject reason `0100` at all. `SYN-100-XREF-MISS` in `synthetic-boundary-cases.csv` still states the source outcome, because that file records what the source does. The departure is recorded in `docs/decision-log.md` under "The subject of a decision comes from a stored row".

| Reproduced divergence | Source | Result | Recorded in |
|---|---|---|---|
| Credit-limit working field drops a high-order digit | `app/cbl/CBTRN02C.cbl:L187`, compared at `:L407` | The constructed boundary reproduces the source approval | Register item 7 |
| A refund raises the next tested balance | `app/cbl/CBTRN02C.cbl:L551` accumulates, `:L404` subtracts | The posting comparison reproduces the sign convention | Register item 6 |
| Online payment leaves both cycle accumulators unchanged | `app/cbl/COBIL00C.cbl`, whole-file search for either accumulator | The bill-payment comparison preserves the separate rule | Bill-payment divergence, above. Two source paths differ, so no register item flags it |
| Processing timestamp carries hundredths plus four zeros | `app/cbl/CBTRN02C.cbl:L173-L174` and `:L700-L701` | The timestamp comparison applies the documented tolerance | Register item 8 |

## Declared coverage gaps

Two gaps are stated directly, not hidden inside a coverage percentage. Both are limits of the shipped fixtures, and both have a mitigation in the suite today.

1. **The precision boundary is unreachable from the fixtures.** Register item 7 concerns cycle balances at or above one billion, and no record among the 300 in `dailytran.txt` reaches that magnitude. `AuthorizationDecisionEquivalenceTest` therefore constructs the record instead of loading it. The constructed case proves that the `PIC S9(09)V99` working field at `app/cbl/CBTRN02C.cbl:L187` loses its high-order digit against the `PIC S9(10)V99` operands at `app/cpy/CVACT01Y.cpy:L13-L14` and the limit at `:L8`. Adding a source-derived fixture that reaches the boundary remains open, and [suggested next tasks](suggested-next-tasks.md) carries it.
2. **The processing timestamp cannot be compared byte for byte.** No assertion compares a raw timestamp; the harness truncates to hundredths and zero-pads first, for the reason given under timestamp tolerance above. Register item 8 records the field mechanism.

## Deliberate non-additions

Each behaviour below is one a competent engineer would reasonably add. Adding any of them would change outcomes and break the identical-results requirement, so each is recorded as a deliberate non-addition rather than left unexplained.

| Behaviour not added | Why adding it would break equivalence | Recorded in |
|---|---|---|
| Luhn or checksum validation | The source states the whole rule as `Card number if supplied must be a 16 digit number` at `app/cbl/COCRDUPC.cbl:L194`, and the second site at `:L784` tests only that the field is numeric | Register item 24 |
| Card-status check in the authorization path | The posting program opens six files and none is the card file, at `app/cbl/CBTRN02C.cbl:L29`, `:L34`, `:L40`, `:L46`, `:L51` and `:L57`. `app/jcl/POSTTRAN.jcl` allocates the same six datasets and never the card file | Register item 3 |
| Account-status check | `ACCT-ACTIVE-STATUS` exists at `app/cpy/CVACT01Y.cpy:L6` and no program reads it before posting, so a closed account still posts | Register item 4 |
| Password hashing in the migrated signon path | The source comparison is plaintext at `app/cbl/COSGN00C.cbl:L223`. Signon sits outside the equivalence subject, and the platform's own identities use encoded credentials | [Suggested next tasks](suggested-next-tasks.md) |
| Correction of the narrowed precision or the refund sign convention | Parity requires reproducing both documented outcomes, not repairing them | Register items 7 and 6 |
