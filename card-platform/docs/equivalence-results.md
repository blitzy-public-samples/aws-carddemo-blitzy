# Equivalence Results

This report records the observed parity results for the CardDemo fixture suite. The comparison runs against documented COBOL rules and the checked-in fixtures, never against a mainframe. Equivalence runs throughout the Maven reactor, never deferred to the end. Every count below comes from a run of the suite, and every locator resolves in the file it names. Design rationale belongs in the [decision log](decision-log.md).

## Comparison basis

A reader needs to know what "equivalent" was measured against before reading any result, so the basis comes first.

The comparison executes the Java implementations and independent source-rule references against the fixtures under `app/data/ASCII/`, checking them against expected results held in `card-platform/equivalence-tests/src/test/resources/expected/`. Those expected files are derived from documented source semantics and are checked in, one per fixture subject.

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

The continuous-integration workflow runs equivalence as its own stage, `Run equivalence tests`, invoking the same `verify` goal. No stage in that workflow carries `continue-on-error`, and the container-build stage lists the equivalence stage among its prerequisites, so the suite cannot be skipped or allowed to fail quietly. `PostingEquivalenceTest` obtains PostgreSQL through Testcontainers; the other eight classes need no container. [Onboarding](onboarding.md) covers machine setup.

## Observed run

The command above completed successfully across all ten reactor projects.

| Measure | Observed result |
|---|---:|
| Failsafe equivalence tests | 214 passed |
| Surefire unit and contract tests in the same module | 266 passed |
| `*EquivalenceTest` classes executed | 9 |
| Required named equivalence classes | 6 present and passing |
| Failures | 0 |
| Errors | 0 |
| Skipped tests | 0 |

The module holds nine classes matching the Failsafe pattern: the six the specification names, plus three added during implementation. The six contribute 180 tests and the three contribute 34, which is the 214 above.

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
| `PostingEquivalenceTest` | All 300 daily-transaction records | Final balances, category balances and reject reasons match the expected results record by record, with ordering, timestamp normalization and idempotency | PASS — 52 tests |
| `AuthorizationDecisionEquivalenceTest` | Each of the four decline reasons | Correct code and verbatim description, correct short-circuit ordering, equality boundaries, missing rows, and the narrowed precision boundary | PASS — 14 tests |
| `BillPaymentEquivalenceTest` | The online payment path | Balance reduced to zero and both cycle accumulators left untouched, unlike batch posting | PASS — 21 tests |
| `InterestCalculationEquivalenceTest` | Rate rules and the default-group fallback | Rates resolve identically, one `DEFAULT` retry, failed retry, and the accumulator reset. Verified, not migrated | PASS — 21 tests |
| `ValidationEquivalenceTest` | Field validation | The six card message texts and the credit-score range message reproduced character for character | PASS — 49 tests |
| `DecimalTruncationEquivalenceTest` | All four money-moving arithmetic sites | Truncation produces the expected value, and half-up rounding produces a different one | PASS — 23 tests |

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

Under the stateless authorization projection the 300 fixture records produced 13 declines and 287 approvals, and every fixture-reachable decline carried reason 102. Both cycle accumulators hold `0.00` across all 300 evaluations, so the tested value reduces to the transaction amount and only the limit comparison can fail. Constructed records cover reasons 100, 101 and 103.

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

## The arithmetic sites

The measured finding comes first: **the `ROUNDED` phrase appears zero times across all 28 programs in `app/cbl/`.** Every arithmetic store therefore truncates toward zero, which is the COBOL default when no rounding phrase is present.

The truncation claim rests on the sites below, and the set is small enough to enumerate in full. Four sites move money on the posting and payment paths, and the category balance is one of the four reached through two branches. Interest accrual appears as a fifth site because the suite verifies it, though the calculation is not migrated.

| Site | Locator | Operation |
|---|---|---|
| Overlimit working balance | `app/cbl/CBTRN02C.cbl:L403-L405` | `COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT` |
| Category balance, create branch | `app/cbl/CBTRN02C.cbl:L508` | `ADD DALYTRAN-AMT TO TRAN-CAT-BAL` before the write at `:L510` |
| Category balance, update branch | `app/cbl/CBTRN02C.cbl:L527` | `ADD DALYTRAN-AMT TO TRAN-CAT-BAL` before the rewrite at `:L528` |
| Account balance and cycle accumulators | `app/cbl/CBTRN02C.cbl:L547-L551` | `ADD DALYTRAN-AMT TO ACCT-CURR-BAL`, then to cycle credit at `:L549` when the amount is zero or positive, otherwise to cycle debit at `:L551`. Reproduced in two places, because the record is split across two services: `ledger-posting-service` writes its balance projection from the authorization event, and `account-service` writes the account record from the posted event |
| Online bill payment | `app/cbl/COBIL00C.cbl:L234` | `COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT` |
| Interest accrual | `app/cbl/CBACT04C.cbl:L464-L467` | Multiply by rate, divide by 1200, then accumulate |

Add and subtract over scale-two operands discard no fraction, so truncation and half-up rounding agree at every add and subtract site across all 300 fixture amounts. The interest divide is where they part. The chained fixture rows produced 28 differences between truncation and half-up, and zero differences against floor, because every negative category row resolved to a zero rate.

A constructed negative case uses a real fixture rate of `25.00`, where `-763.00 × 25.00 ÷ 1200` gives `-15.89` under truncation and `-15.90` under half-up or floor. **`DecimalTruncationEquivalenceTest` asserts that truncation gives the expected value and that half-up gives a different one.** That second assertion turns a silent risk into a failing test. It is the only way a future contributor who "simplifies" the rounding mode finds out.

Parsing is in scope alongside arithmetic. `app/cbl/COTRN02C.cbl` parses at four sites: `:L204` and `:L218` use plain `FUNCTION NUMVAL`, while `:L383` and `:L456` use `FUNCTION NUMVAL-C`. The currency-aware variant accepts currency symbols and thousands separators, so it is **not** equivalent to constructing a decimal from a string, and a shared parser reproduces the tolerance. The account update program gates on a validity test before converting, at `app/cbl/COACTUPC.cbl:L2201`.

## Timestamp tolerance

The processing timestamp is `DB2-FORMAT-TS PIC X(26)` at `app/cbl/CBTRN02C.cbl:L159`, redefined from `:L160`. The redefinition ends in `DB2-MIL PIC 9(002)` at `:L173` and `DB2-REST PIC X(04)` at `:L174`. The routine that fills the field moves a hundredths value at `:L700`, then writes the literal `0000` into the remainder at `:L701`. The result reaches the transaction record at `:L438`.

The field therefore carries two significant fractional digits followed by four zeros. A service stamping a fresh instant differs from it in the last four digits of every record. The harness truncates to hundredths and zero-pads before comparing.

No equivalence assertion compares a raw timestamp. Raw comparison would fail on every record for a reason unrelated to the logic under test, and that is the kind of failure that gets a suite switched off. Register item 8 records the mechanism.

## Divergences

No unintended divergence between service behaviour and source rule was observed in the completed run. The section stays because its presence is what tells a reader the question was asked.

Four divergences are deliberate reproductions of source behaviour. Each names where its explanation lives, so that none reads as a defect in the platform.

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
