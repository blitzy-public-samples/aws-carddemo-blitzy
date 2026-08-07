# Equivalence Results

This report records the observed parity results for the CardDemo fixture suite. The comparison uses documented COBOL rules and checked-in fixtures. The suite never calls a mainframe. Equivalence runs throughout the Maven reactor rather than after implementation. Design rationale belongs in the [decision log](decision-log.md).

## Comparison basis

The user permits comparison against the original COBOL “or its documented business rules.” The user also forbids runtime calls into the mainframe. The suite therefore executes Java implementations and independent source-rule references against the repository fixtures.

The comparison covers:

- account balance changes at `app/cbl/CBTRN02C.cbl:L545-L560`;
- authorization decisions at `app/cbl/CBTRN02C.cbl:L380-L422`;
- transaction category balances at `app/cbl/CBTRN02C.cbl:L467-L542`;
- online bill payment at `app/cbl/COBIL00C.cbl:L203-L250`;
- interest-rate resolution at `app/cbl/CBACT04C.cbl:L415-L460`;
- field validation from `app/cbl/COCRDUPC.cbl` and `app/cbl/COACTUPC.cbl`.

The baseline is observed fixture behavior plus documented source semantics. Compiler options could affect edge behavior in the original runtime. That residual risk remains recorded.

The authorization source tests a limit but performs no card-status or account-status check. The suite reproduces that absence. [Business-rule flags](business-rule-flags.md) items 3 and 4 record both non-additions.

## How the suite runs

Run the suite from `card-platform/`:

```bash
mvn -o -B -pl equivalence-tests -am verify
```

`mvn test` is insufficient. Surefire excludes `**/*EquivalenceTest.java`. Failsafe includes that pattern during `integration-test` and fails the build during `verify`.

The equivalence module is last in the reactor because it depends on both shared libraries and all six services. The required continuous-integration workflow must run the same `verify` stage without skipping it.

## Observed run

The full offline command completed successfully across all ten reactor projects.

| Measure | Observed result |
|---|---:|
| Equivalence unit and contract tests | 253 passed |
| Failsafe equivalence tests | 182 passed |
| Required named equivalence classes | 6 present and passing |
| Failures | 0 |
| Errors | 0 |
| Skipped tests | 0 |

The four classes added for review finding M2 contributed 47 passing tests:

- `AuthorizationDecisionEquivalenceTest`: 14
- `BillPaymentEquivalenceTest`: 9
- `InterestCalculationEquivalenceTest`: 13
- `DecimalTruncationEquivalenceTest`: 11

## Fixture inventory and results

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

### Cross-reference width

`cardxref.txt` carries 36 bytes per row. Its three populated fields occupy 16 + 9 + 11 bytes. The text fixture omits the 14-byte trailing filler declared by `app/cpy/CVACT03Y.cpy:L8`.

`CardDemoFixtureLoader` accepts the delivered width and can restore the declared 50-byte layout. A strict 50-byte reader would fail on the checked-in text fixture. Business-rule flag 25 records this mismatch.

The `app/data/EBCDIC/` directory contains 12 data artifacts, excluding `.gitkeep`. The binary cross-reference carries the full 50-byte layout. The binary set also contains a security-user fixture absent from the ASCII set.

## Results by required test class

| Test class | Subject | Assertion | Result |
|---|---|---|---|
| `PostingEquivalenceTest` | All 300 daily transactions | Posting, category balances, account balances, rejects, ordering, timestamp normalization, and idempotency match documented source semantics | PASS — 52 tests |
| `AuthorizationDecisionEquivalenceTest` | Four decline reasons and boundaries | Codes, descriptions, source ordering, equality, missing rows, and the narrowed precision boundary match | PASS — 14 tests |
| `BillPaymentEquivalenceTest` | Online payment path | The transaction stores the opening balance, the account reaches zero, and both cycle accumulators remain unchanged | PASS — 9 tests |
| `InterestCalculationEquivalenceTest` | Rate lookup and cycle-close carve-out | Primary lookup, one DEFAULT retry, failed retry, rate rows, and accumulator reset match | PASS — 13 tests |
| `ValidationEquivalenceTest` | Card and account validation | Source messages, punctuation, credit-score bounds, and fixture values match | PASS — 49 tests |
| `DecimalTruncationEquivalenceTest` | Fixed-point arithmetic | Exact add/subtract sites, interest truncation, alternate rounding modes, and Picture-field narrowing match | PASS — 11 tests |

Supporting equivalence classes added 34 further passing tests for fixture coverage, identifier fidelity, and card seeding: `FixtureCoverageEquivalenceTest` 18, `IdentifierFidelityEquivalenceTest` 9, and `CardSeedEquivalenceTest` 7.

### Authorization decisions

The four source reasons are:

| Code | Description | Assignment |
|---|---|---|
| `0100` | `INVALID CARD NUMBER FOUND` | `app/cbl/CBTRN02C.cbl:L385-L387` |
| `0101` | `ACCOUNT RECORD NOT FOUND` | `app/cbl/CBTRN02C.cbl:L397-L399` |
| `0102` | `OVERLIMIT TRANSACTION` | `app/cbl/CBTRN02C.cbl:L410-L412` |
| `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `app/cbl/CBTRN02C.cbl:L417-L419` |

The gate at `app/cbl/CBTRN02C.cbl:L372-L373` makes reasons 100 and 101 short-circuit. No gate separates reasons 102 and 103. Reason 103 therefore overwrites reason 102 when both comparisons fail.

Under the stateless authorization projection, the 300 fixtures produced 13 declines and 287 approvals. Every fixture-reachable decline carried reason 102. Synthetic records cover reasons 100, 101, and 103.

The first fixture record resolves to account `00000000007`. Its amount is `504.77`, credit limit is `2065.00`, and expiry text is `2024-12-13`. The result is authorized.

### Bill-payment divergence

`app/cbl/COBIL00C.cbl:L224` copies the full opening balance into the transaction amount. Line 233 writes that transaction. Line 234 then subtracts the copied amount from the account balance.

All 50 fixture balances reached numeric zero. A synthetic input gave both cycle accumulators non-zero values. The online rule left both unchanged.

Batch posting differs. `app/cbl/CBTRN02C.cbl:L547-L551` changes the balance and exactly one cycle accumulator. The contrast test applies both rules to the same positive amount.

The online path also writes one timestamp into both origin and processing fields at `app/cbl/COBIL00C.cbl:L231-L232`. Batch posting retains the feed origin format and generates a different processing format.

### Interest carve-out

Interest is verified but not migrated. No interest service exists.

`app/cbl/CBACT04C.cbl:L415-L440` reads a disclosure-group rate. A miss substitutes `DEFAULT` and retries once. A second miss fails at `app/cbl/CBACT04C.cbl:L443-L458`.

`discgrp.txt` contains 51 rows: three groups with 17 type-and-category rows each. All 50 account fixtures carry ten spaces in `ACCT-GROUP-ID`. The adjacent `ACCT-ADDR-ZIP` field carries `A000000000`, so the blank group is a fixture property rather than an offset error.

The posting reference produced 100 category rows: 50 seeded type `01` rows and 50 type `03` rows created from approved refunds. Every row used the DEFAULT fallback under the fixture account group. The DEFAULT rate for type `03`, category `0001`, is `0.00`.

`BillingCycleService` reproduces only `app/cbl/CBACT04C.cbl:L353-L354`. It zeroes both cycle accumulators and leaves the current balance unchanged. It does not reproduce the interest add at line 352.

### Decimal truncation

The `ROUNDED` phrase appears zero times across all 28 programs in `app/cbl/`. Each arithmetic store therefore truncates toward zero.

The tested money-moving sites are:

| Site | Locator | Operation |
|---|---|---|
| Overlimit working balance | `app/cbl/CBTRN02C.cbl:L403-L405` | Cycle credit minus cycle debit plus amount |
| Category balance create | `app/cbl/CBTRN02C.cbl:L508` | Add amount to an initialized balance |
| Category balance update | `app/cbl/CBTRN02C.cbl:L527` | Add amount to an existing balance |
| Account balance and cycle accumulators | `app/cbl/CBTRN02C.cbl:L547-L551` | Add amount, then route by sign. Reproduced in two places, because the record is split across two services: `ledger-posting-service` writes its balance projection from the authorization event, and `account-service` writes the account record itself from the posted event |
| Online bill payment | `app/cbl/COBIL00C.cbl:L234` | Subtract the copied opening balance |
| Interest accrual | `app/cbl/CBACT04C.cbl:L464-L467` | Multiply by rate, divide by 1200, then accumulate |

Add and subtract operations over scale-two operands produce no discarded fraction. `RoundingMode.DOWN` and `RoundingMode.HALF_UP` therefore agree at those sites.

The interest divide exposes the difference. The chained fixture rows produced 28 differences between DOWN and HALF_UP. They produced zero differences between DOWN and FLOOR because every negative category row resolved to a zero rate.

The synthetic negative case uses a real 25.00 fixture rate. `-763.00 × 25.00 ÷ 1200` produces `-15.89` under DOWN and `-15.90` under HALF_UP or FLOOR. The test protects truncation toward zero on negative values.

## Timestamp tolerance

The processing timestamp is `PIC X(26)` at `app/cbl/CBTRN02C.cbl:L159`. The source stores two significant fractional digits at line 173 and four trailing characters at line 174. Lines 700-701 move the hundredths value and the literal `0000`.

The harness normalizes processing timestamps to hundredths and four trailing zeros. No equivalence assertion compares a fresh Java timestamp byte for byte without that normalization. Raw comparison would fail every record for an unrelated clock difference.

## Divergences

No unintended service-to-rule divergence was observed in the completed run.

| Reproduced divergence | Source | Result | Register item |
|---|---|---|---:|
| Credit-limit working field drops a high-order digit | `app/cbl/CBTRN02C.cbl:L187`, `L403-L407` | Synthetic boundary reproduces the source approval | 7 |
| Refund raises the next tested balance | `app/cbl/CBTRN02C.cbl:L404`, `L551` | Posting comparison reproduces the sign convention | 6 |
| Online payment leaves cycle accumulators unchanged | `app/cbl/COBIL00C.cbl`, whole-file cycle-field search | Bill-payment comparison preserves the separate rule | documented inconsistency |
| Processing timestamp carries hundredths plus four zeros | `app/cbl/CBTRN02C.cbl:L173-L174`, `L700-L701` | Timestamp comparison applies the documented normalization | 8 |

## Declared coverage gaps

The gaps are stated directly rather than hidden inside a percentage.

1. **Fixture precision boundary.** No fixture cycle balance reaches one billion. `AuthorizationDecisionEquivalenceTest` supplies an additive synthetic value and proves the `S9(09)V99` high-order truncation.
2. **Raw processing-timestamp equality.** A fresh Java timestamp cannot equal the source field byte for byte. `PostingEquivalenceTest` compares the hundredths value and four trailing zeros under the documented tolerance.

## Deliberate non-additions and security deviation

| Behavior | Equivalence or security reason | Register or decision |
|---|---|---:|
| Luhn or checksum validation | The source checks only sixteen numeric digits at `app/cbl/COCRDUPC.cbl:L193-L194` and L784 | 24 |
| Card-status check in authorization | `CBTRN02C` never opens the card file, and `POSTTRAN.jcl` never allocates it | 3 |
| Account-status check | `ACCT-ACTIVE-STATUS` exists at `app/cpy/CVACT01Y.cpy:L6` but is not tested before posting | 4 |
| Plaintext password comparison | Not reproduced. HTTP Basic identities use encoded credentials, and signon is outside the equivalence subject | Decision log |
| Correction of the narrowed precision or refund sign | Parity requires reproducing both documented outcomes | 7 and 6 |
