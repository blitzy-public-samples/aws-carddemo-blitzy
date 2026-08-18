# Interest Calculation — Java 21 port of COBOL `CBACT04C`

Standalone Java 21 + Maven module that reproduces the **exact observable behavior** of the
CardDemo interest-calculation batch program `app/cbl/CBACT04C.cbl` — the job driven by
`app/jcl/INTCALC.jcl` — over the in-repo fixed-width ASCII flat files. The migration is
**strictly additive**: every original mainframe artifact under `app/` (COBOL, copybooks, JCL,
and data fixtures) is left completely untouched, and all generated Java lives under this one
new directory (`modernized/interest-calculation/`) so the change set stays small and reviewable.

This is a **headless batch module** — there is no user interface. It follows a thin
*hexagonal-lite* layering: a command-line adapter (`InterestCalculator`) drives a service core
(`InterestCalculationService`) that performs **no direct file I/O of its own** and holds no file
or connection state — it is fed by file adapters (`io.*`) that frame the fixed-width records.
The service is **not** free of side effects, however: faithful to `CBACT04C`, it **mutates** the
loaded account models (posting accumulated interest to the balance and zeroing the two cycle
fields, `1050-UPDATE-ACCOUNT`) and **emits** the generated interest transactions through an
injected writer (`1300-B-WRITE-TX`). The module depends only on the **JDK** and, for
tests, **JUnit 5** — there is **no** Spring, application server, database, ORM, messaging,
network, environment variable, or configuration file of any kind. Correctness is a faithful,
line-traceable port of the source program: each migrated business rule carries a comment
citing its originating source — a `CBACT04C` paragraph/line for BR-01…BR-17, and the
copybook `PIC` layouts plus the `app/data/ASCII` fixtures for BR-18, the overpunch
zoned-decimal rule the program inherits from its copybooks rather than from a paragraph.

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| **JDK** | **21 (LTS)** | Required. The build pins `maven.compiler.release=21`. |
| **Apache Maven** | **3.9.x** | Build-tool baseline. |

The **only** third-party dependency is **JUnit 5**
(`org.junit.jupiter:junit-jupiter:5.13.4`), declared at **`test` scope**. The module therefore
has **zero compile or runtime dependencies**, and the packaged jar runs on the bare JDK — no
shade/assembly/fat-jar plugin is required.

## Build, test, and package

Run all commands from this module directory (`modernized/interest-calculation/`).

- **Test** — compile and run the full JUnit 5 suite (golden-master + focused unit tests). The
  suite is self-contained: it reads only the in-repo fixtures under `src/test/resources/`, with
  no external resources and no network access.

  ```sh
  mvn test
  ```

- **Package** — produce the executable jar `target/interest-calculation-1.0.0.jar`. Its manifest
  sets `Main-Class = com.blitzy.carddemo.interest.InterestCalculator`, so it runs with a plain
  `java -jar`.

  ```sh
  mvn package
  ```

- **Run** — execute the batch job against real input files (see the argument contract below).

  ```sh
  java -jar target/interest-calculation-1.0.0.jar <args...>
  ```

## Command-line argument contract

The entry point `com.blitzy.carddemo.interest.InterestCalculator` takes **seven positional
arguments**, in this exact order. The four inputs and the DD-to-fixture mapping are derived from
the `INTCALC` job's DD statements and the program's `FD ... ASSIGN` clauses; the trailing
`PARM-DATE` is the `INTCALC` job's `PARM` value.

| # | Argument | COBOL DD (dataset) | Direction | Description |
|---|---|---|---|---|
| `args[0]` | `tcatbal` input | `TCATBALF` (→ `app/data/ASCII/tcatbal.txt`) | in | Transaction-category-balance driver, **50-byte** records |
| `args[1]` | `cardxref` input | `XREFFILE` (→ `app/data/ASCII/cardxref.txt`) | in | Card cross-reference (account-id alternate key), **36-byte** records (36 significant bytes of `CVACT03Y`; see *Fixed-width framing* below) |
| `args[2]` | `acctdata` input | `ACCTFILE` (→ `app/data/ASCII/acctdata.txt`) | in | Account master, **300-byte** records |
| `args[3]` | `discgrp` input | `DISCGRP` (→ `app/data/ASCII/discgrp.txt`) | in | Disclosure-group interest rates, **50-byte** records |
| `args[4]` | interest-transactions output | `TRANSACT` | out | Generated interest transactions, **350-byte** records |
| `args[5]` | updated-accounts output | rewritten `ACCTFILE` | out | Account master with updated balances, **300-byte** records |
| `args[6]` | `PARM-DATE` | `INTCALC` `PARM` | in | Run date; the `INTCALC` job uses `2022071800` |

### Example invocation

Run from `modernized/interest-calculation/`. The four input paths point at the **read-only**
fixtures under `app/data/ASCII/`; the two output files are created by the run. The relative
paths below are illustrative — substitute your own locations as needed.

```sh
java -jar target/interest-calculation-1.0.0.jar \
  ../../app/data/ASCII/tcatbal.txt \
  ../../app/data/ASCII/cardxref.txt \
  ../../app/data/ASCII/acctdata.txt \
  ../../app/data/ASCII/discgrp.txt \
  target/interest-transactions.out \
  target/updated-accounts.out \
  2022071800
```

## Behavior and migration notes

The port preserves the source program's observable contract exactly. The points below are the
consequential porting decisions, each traceable to `CBACT04C`.

- **Decimal fidelity / truncation.** Every `S9(n)V99` money and rate field is modeled with
  `java.math.BigDecimal` at scale 2. The interest `COMPUTE` in the source carries **no `ROUNDED`
  clause** (`CBACT04C.cbl` L464-465), so COBOL **truncates**. The port therefore computes the
  interest as `balance.multiply(rate).divide(new BigDecimal("1200"), 2, RoundingMode.DOWN)` —
  multiplying first, then dividing by `1200` with the scaled `divide(divisor, 2, RoundingMode.DOWN)`
  form so the quotient truncates to scale 2, and **never** `HALF_EVEN`. The scaled `divide` is used
  rather than an exact `divide(...)` followed by `setScale(2, RoundingMode.DOWN)`, because the exact
  form throws `ArithmeticException` on a non-terminating quotient (amounts not evenly divisible by
  1200); the scaled `divide` truncates safely for every input.
- **Overpunch zoned decimal.** All numeric fields are `USAGE DISPLAY` zoned decimal (there is
  **no `COMP-3` and no `REDEFINES`** anywhere in the source). Signed values carry the sign as a
  trailing-byte **overpunch** — `{` = +0, `A`–`I` = +1…+9, `}` = -0, `J`–`R` = -1…-9 — and the
  `V99` is an **implied** (unstored) decimal point.
- **Fixed-width framing.** Records are framed at fixed widths on both read and write:
  **50 / 36 / 50 / 300 / 350** bytes for the
  transaction-category-balance / card-xref / disclosure-group / account / transaction records
  respectively. Four of the five match their exact copybook widths (`CVTRA01Y` 50, `CVTRA02Y` 50,
  `CVACT01Y` 300, `CVTRA05Y` 350). The **card-xref** record is the exception: the `CVACT03Y`
  copybook declares a 50-byte record (`RECLN 50`), but the in-repo ASCII fixture (`cardxref.txt`)
  stores only the **36 significant bytes** — `XREF-CARD-NUM` X(16) + `XREF-CUST-ID` 9(09) +
  `XREF-ACCT-ID` 9(11) — and omits the trailing `FILLER X(14)`, so the reader
  (`CardXrefRepository`) frames card-xref input at **36** bytes to match the fixture.
- **Determinism.** The 26-character DB2-format timestamp is built from `FUNCTION CURRENT-DATE`
  (`CBACT04C.cbl` L613-626) and is the program's **only** non-deterministic input. It is injected
  through a `Db2TimestampSupplier` seam so golden-master output is stable and reproducible under
  test. In production, `java -jar` binds the wall-clock supplier
  (`Db2TimestampSupplier.systemDefault()`), so — exactly like the mainframe program reading
  `FUNCTION CURRENT-DATE` — the `TRAN-ORIG-TS`/`TRAN-PROC-TS` fields of the generated transactions
  reflect the actual run time and therefore are **not** byte-reproducible across runs. This is the
  faithful source behavior and is deliberately preserved (AAP §0.7). Byte-exact, deterministic
  output is a property of the **test** path only: the golden-master test drives the identical
  pipeline through the public `InterestCalculator.run(String[], Db2TimestampSupplier)` seam with a
  fixed timestamp, and every other byte of the production output (interest amounts, `TRAN-ID`s,
  fixed fields, card numbers, and the entire updated-accounts file) is already fully deterministic.
- **Mutated output.** `ACCTFILE` is the **only** mutated file: it is opened I-O and rewritten
  (`REWRITE` at `CBACT04C.cbl` L356), posting accumulated interest to the current balance and
  zeroing the two cycle fields — this becomes the updated-accounts output (`args[5]`). `TCATBAL`
  is **read-only**, and `TRANSACT` is write-only (the generated interest transactions).
- **Final account update at end-of-file (BR-12) — ratified decision.** Once the driver is
  exhausted, the port posts the **last** account's accumulated interest through the same
  account-update path as an account break (`ELSE PERFORM 1050-UPDATE-ACCOUNT` at `CBACT04C.cbl`
  L219-220, performing `1050-UPDATE-ACCOUNT` L350-356), guarded so that an empty driver updates
  nothing. This is a deliberate reading of the source, not an oversight: the driver loop is
  `PERFORM UNTIL END-OF-FILE = 'Y'`, which COBOL evaluates **test-before**, so once the read
  paragraph flags end-of-file the loop simply exits — that `ELSE` **never runs on the mainframe**,
  and a strict-execution port would therefore leave the last account's interest unposted. For the
  shipped fixtures the choice is **byte-neutral**: every account accumulates `0.00` and both cycle
  fields are already zero, so the updated-accounts output stays byte-identical to
  `app/data/ASCII/acctdata.txt` — `cmp`-verified, both 15,050 bytes, and both hashing to the
  canonical account-output anchor
  SHA-256 `c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260`
  (the same anchor asserted by `InterestCalculationGoldenMasterTest` and cited in the Project
  Guide's risk register). The decision has been **ratified** in PR review and is the
  accepted contract of this port: it must **not** be "corrected" to the unreachable-`ELSE`
  reading. `InterestCalculationServiceTest.br12_finalUpdateAtEof` is the regression lock that holds
  it in place — it drives non-zero interest over non-zero cycle fields, so it fails if the update is
  removed; the golden-master test documents the decision but, being byte-neutral for these fixtures,
  cannot detect its removal.
- **Error semantics (asymmetric).** Account-not-found and xref-not-found are **fatal** — the
  COBOL abend is ported to a thrown runtime exception. Disclosure-group-not-found is **not**
  fatal: it falls back to the `DEFAULT` disclosure group.
- **Write guard tests the rate, not the balance.** A transaction is written whenever the rate is
  non-zero; a zero balance with a non-zero rate still emits a `0.00` transaction.
- **Fees are a no-op.** `1400-COMPUTE-FEES` is an empty "to be implemented" stub in the source
  and remains a no-op in the port — no fee logic is invented.
- **How correctness is established.** This module ships with no access to a live mainframe.
  Correctness is verified **only** through the JUnit 5 test suite (golden-master characterization
  tests over the in-repo fixtures plus per-rule and edge-case unit tests) and static reasoning —
  never by executing against a live system.

## Project layout and source lineage

The module follows the Maven Standard Directory Layout:

```
modernized/interest-calculation/
├── pom.xml
├── README.md
└── src/
    ├── main/java/com/blitzy/carddemo/interest/
    │   ├── InterestCalculator.java     CLI adapter — main(String[])
    │   ├── model/                      one immutable record class per copybook
    │   ├── io/                          fixed-width readers/writers and repositories
    │   ├── support/                     zoned-decimal codec, truncating arithmetic, timestamp supplier
    │   └── service/                     ported business logic (pure)
    └── test/
        ├── java/com/blitzy/carddemo/interest/   golden-master + unit tests
        └── resources/
            ├── fixtures/                self-contained copies of the ASCII inputs
            └── expected/               golden expected outputs
```

**Source lineage.** The Java is reverse-engineered from the following authoritative in-repo
artifacts (all consumed read-only):

- `app/cbl/CBACT04C.cbl` — the batch program being ported.
- The five copybooks in `app/cpy/` — `CVTRA01Y` (transaction-category-balance, 50B),
  `CVACT03Y` (card-xref, 50B), `CVTRA02Y` (disclosure-group, 50B), `CVACT01Y` (account, 300B),
  and `CVTRA05Y` (transaction, 350B).
- `app/jcl/INTCALC.jcl` — the job that runs `CBACT04C`; supplies the DD-to-dataset mapping and
  the `PARM-DATE` (`2022071800`).
- The fixed-width fixtures in `app/data/ASCII/` — the golden-master inputs.

There is **no build-time or runtime dependency on `app/`**: the test inputs are self-contained
copies under `src/test/resources/fixtures/`, so `mvn test` runs anywhere without the original
mainframe tree.

## License

Licensed under the Apache License, Version 2.0, consistent with the CardDemo repository. See the
top-level [`LICENSE`](../../LICENSE) file for details.
