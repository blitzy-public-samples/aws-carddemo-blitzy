# Golden Files — Expected Outputs for COBOL→Java Parity Tests

This folder holds the **golden files**: the *expected outputs* that the parity
tests in `src/test/java` assert the modernized Java / Spring Batch programs
against. Each golden file represents exactly what the corresponding legacy
COBOL program would have produced for one specific **input fixture**. The tests
compare the actual Java output against the matching golden file **byte‑for‑byte**
(or via a documented, parity‑preserving normalization where a field is
non‑deterministic — see [Pairing convention](#input--expected-pairing-convention)).

Golden files are the acceptance bar for **100 % behavioral parity** with the
legacy z/OS application (AAP §0.4.1, §0.6.7). A meaningful parity diff is only
possible when the *expected* layout stored here matches the COBOL record/print
contract to the byte. Every width, edited‑field mask, record length, padding
rule, and reason code documented below was verified against the read‑only legacy
sources retained in this same repository under `legacy/app/` (copybooks in
`legacy/app/cpy`, programs in `legacy/app/cbl`, JCL in `legacy/app/jcl`).

> **Local‑only generation (AAP §0.6.7).** These golden files are produced by
> translating the documented COBOL logic — a one‑time, locally recorded
> derivation. **No running COBOL compiler, z/OS, CICS, VSAM, or mainframe
> environment is required** to author, regenerate, or run the parity tests.
> All verification runs locally (JUnit 5 + Testcontainers PostgreSQL seeded from
> the legacy fixtures).

## How the parity tests use these files

1. A parity test seeds its inputs from the sibling `fixtures/` folder (which
   mirrors `legacy/app/data/ASCII/*.txt`).
2. It runs the corresponding Java program (a Spring Batch job, the statement
   generator, the report writer, the interest calculator, or the reject writer).
3. It captures the actual fixed‑width / edited output the Java program emits.
4. It loads the matching golden file from this folder via the classpath and
   asserts equality (byte‑exact, or with the documented normalization).

If a parity test fails, this README is the reference reviewers use to decide
whether the failure is a **real regression** in the Java code or a
**golden‑file authoring error** (an expected file that does not match the COBOL
contract).

## Folder structure

The subfolders below are populated with concrete `<case>` golden files by the
`src/test/java` parity‑test work (this README is the index/contract for them):

| Subfolder            | Legacy program(s)                                   | Output kind                                                    | Record / line spec                                                   |
|----------------------|-----------------------------------------------------|----------------------------------------------------------------|----------------------------------------------------------------------|
| `statements/`        | `CBSTM03A` + `CBSTM03B` (file‑I/O subroutine → `FileIoService`) | Account statements                                  | Plain text **`LRECL=80`**; optional HTML **`LRECL=100`** (`RECFM=FB`) |
| `reports/`           | `CBTRN03C`                                          | Daily Transaction Report (page / account / grand totals)        | Report line width **`133`** cols (`RECFM=FB,LRECL=133`)              |
| `reject/`            | `CBTRN02C`                                          | `DALYREJS` reject file                                          | Fixed **`LRECL=430`** = `350` (rejected daily‑tran) + `80` (trailer) |
| `interest/`          | `CBACT04C`                                          | New interest transaction records + resulting account balances   | New tran records **`LRECL=350`**                                     |
| `sorted/` (optional) | `SORT` step of `COMBTRAN` + IDCAMS `REPRO`          | Sorted / combined transaction file                              | Records **`LRECL=350`**, sorted by `TRAN-ID` ascending              |

All record lengths are **fixed** (`RECFM=F`/`FB`); short values are padded to the
declared width (`X(n)` ⇒ trailing spaces, `9(n)` ⇒ leading zeros) so that
byte offsets are stable across every record.

## Per‑output contracts

Each subsection captures the byte‑exact contract a golden file in that subfolder
must satisfy. These are reproduced faithfully from the legacy copybooks,
programs, and JCL; do not let a golden file contradict them.

### `reports/` — Daily Transaction Report (`CBTRN03C` / `CVTRA07Y`)

Report record width is **133** (`RECFM=FB,LRECL=133`), confirmed both by
`TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'` in `CVTRA07Y` and by the report
`DD` in `TRANREPT.jcl` (`DCB=(LRECL=133,RECFM=FB,...)`). Each line group below is
built in working storage and then written into the 133‑byte record (right‑padded
with spaces).

**Header block** (`REPORT-NAME-HEADER`)

| Field             | PIC / width | Value                       |
|-------------------|-------------|-----------------------------|
| `REPT-SHORT-NAME` | `X(38)`     | `DALYREPT`                  |
| `REPT-LONG-NAME`  | `X(41)`     | `Daily Transaction Report`  |
| `REPT-DATE-HEADER`| `X(12)`     | `Date Range: `              |
| `REPT-START-DATE` | `X(10)`     | report start date           |
| filler            | `X(04)`     | ` to ` (literal)            |
| `REPT-END-DATE`   | `X(10)`     | report end date             |

**Detail line** (`TRANSACTION-DETAIL-REPORT`) — fields in order, exact widths:

```text
TRAN-REPORT-TRANS-ID    X(16)
(space)                 X(01)
TRAN-REPORT-ACCOUNT-ID  X(11)
(space)                 X(01)
TRAN-REPORT-TYPE-CD     X(02)
'-'                     X(01)   literal hyphen separator
TRAN-REPORT-TYPE-DESC   X(15)
(space)                 X(01)
TRAN-REPORT-CAT-CD      9(04)   numeric, leading‑zero padded
'-'                     X(01)   literal hyphen separator
TRAN-REPORT-CAT-DESC    X(29)
(space)                 X(01)
TRAN-REPORT-SOURCE      X(10)
(spaces)                X(04)
TRAN-REPORT-AMT         -ZZZ,ZZZ,ZZZ.ZZ   edited, 15 chars wide
(spaces)                X(02)
```

The amount uses the **`-ZZZ,ZZZ,ZZZ.ZZ`** edited mask (15 characters): leading
zero‑suppression with comma grouping, a fixed two‑decimal fraction, and a
**leading** sign position that prints `-` only for negative values (space for
non‑negative).

**Column header & separator**

- `TRANSACTION-HEADER-1` carries the column captions (`Transaction ID`,
  `Account ID`, `Transaction Type`, `Tran Category`, `Tran Source`, `Amount`).
- `TRANSACTION-HEADER-2` is `PIC X(133) VALUE ALL '-'` — a full‑width rule of 133
  hyphen characters.

**Totals lines** — all use the **`+ZZZ,ZZZ,ZZZ.ZZ`** edited mask (15 chars; same
zero‑suppression and grouping, but with a leading `+`/`-` sign position) preceded
by a dotted leader of `.` characters:

| Group                   | Label (PIC)              | Dotted leader | Amount field         |
|-------------------------|--------------------------|---------------|----------------------|
| `REPORT-PAGE-TOTALS`    | `Page Total` `X(11)`     | `X(86)` ALL `.` | `REPT-PAGE-TOTAL`    |
| `REPORT-ACCOUNT-TOTALS` | `Account Total` `X(13)`  | `X(84)` ALL `.` | `REPT-ACCOUNT-TOTAL` |
| `REPORT-GRAND-TOTALS`   | `Grand Total` `X(11)`    | `X(86)` ALL `.` | `REPT-GRAND-TOTAL`   |

**Control‑flow semantics that shape the expected output:**

- **Date filter (inclusive):** a transaction is reported only when
  `TRAN-PROC-TS(1:10)` (the first 10 characters of the processing timestamp, i.e.
  the date) is `>= start` **and** `<= end`.
- **Page break:** a page total is emitted when
  `FUNCTION MOD(line‑counter, page‑size) = 0`.
- **Account total:** emitted on each account‑id change; the **page** total and the
  **grand** total are emitted at the end of the run. The page total accumulates
  into the grand total, then resets to zero on each page break.

### `reject/` — Daily reject file `DALYREJS` (`CBTRN02C` / `CVTRA06Y`)

Each reject record is **430 bytes** fixed (`POSTTRAN.jcl`:
`DALYREJS ... DCB=(RECFM=F,LRECL=430,...)`), composed of the full rejected daily
transaction followed by an 80‑byte validation trailer:

```text
Offset  Len  Field                              Notes
0       350  REJECT-TRAN-DATA                   the rejected DALYTRAN-RECORD (CVTRA06Y layout)
350      80  VALIDATION-TRAILER                 = WS-VALIDATION-TRAILER, made of:
              ├─ WS-VALIDATION-FAIL-REASON       PIC 9(04)  — 4‑byte zero‑padded numeric reason code
              └─ WS-VALIDATION-FAIL-REASON-DESC  PIC X(76)  — 76‑byte space‑padded reason text
Total   430
```

The 350‑byte `DALYTRAN-RECORD` (`CVTRA06Y`) field layout is:

```text
DALYTRAN-ID            X(16)
DALYTRAN-TYPE-CD       X(02)
DALYTRAN-CAT-CD        9(04)
DALYTRAN-SOURCE        X(10)
DALYTRAN-DESC          X(100)
DALYTRAN-AMT           S9(09)V99
DALYTRAN-MERCHANT-ID   9(09)
DALYTRAN-MERCHANT-NAME X(50)
DALYTRAN-MERCHANT-CITY X(50)
DALYTRAN-MERCHANT-ZIP  X(10)
DALYTRAN-CARD-NUM      X(16)
DALYTRAN-ORIG-TS       X(26)
DALYTRAN-PROC-TS       X(26)
FILLER                 X(20)   → 350 bytes total
```

**Reason codes / messages** — reproduce exactly (a code and its text are moved
together into the trailer):

| Code (`9(04)`) | `WS-VALIDATION-FAIL-REASON-DESC` (`X(76)`, space‑padded) |
|----------------|----------------------------------------------------------|
| `0100`         | `INVALID CARD NUMBER FOUND`                              |
| `0101`         | `ACCOUNT RECORD NOT FOUND`                               |
| `0102`         | `OVERLIMIT TRANSACTION`                                  |
| `0103`         | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`            |

- Only **rejected** transactions are written to `DALYREJS`; a record with reason
  `0000` (validation passed) is **not** emitted to the reject file.
- For full fidelity, note the posting phase can also set reason **`0109`** with
  text `ACCOUNT RECORD NOT FOUND` when the account `REWRITE` returns `INVALID KEY`
  during update (codes `0100`–`0103` are the validation‑phase reasons). Golden
  reject files must reflect whichever reason the program would actually set for
  the given input.

### `interest/` — Interest calculation (`CBACT04C` / `CVTRA05Y`, `CVTRA02Y`)

This subfolder holds two kinds of expected output: the **new interest
transaction records** the job appends, and the **resulting account balances**
after interest is posted.

**Decimal truncation (critical, AAP §0.6.1).** Monthly interest is computed as:

```text
WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

The COBOL `COMPUTE` carries **no `ROUNDED` phrase**, so the result is
**truncated** to the declared scale 2 — it is *not* rounded. The Java
implementation must reproduce this with `java.math.BigDecimal` using
`RoundingMode.DOWN` at scale 2, and must keep the `/ 1200` monthly divisor.
**Never** use `float`/`double` for this math. Interest is computed only when
`DIS-INT-RATE` is not zero (`DIS-INT-RATE` is `PIC S9(04)V99`); when the rate is
zero, no interest transaction is produced.

**New interest `TRAN-RECORD`** (`CVTRA05Y`, **350 bytes**) — field values set by
the job:

| Field             | Value                                                                 |
|-------------------|-----------------------------------------------------------------------|
| `TRAN-ID`         | run‑date (the 10‑char `PARM`, e.g. `2022071800`) + an incrementing suffix, filling the `X(16)` key |
| `TRAN-TYPE-CD`    | `01`                                                                  |
| `TRAN-CAT-CD`     | `05`                                                                  |
| `TRAN-SOURCE`     | `System`                                                              |
| `TRAN-DESC`       | `Int. for a/c ` + `ACCT-ID`                                           |
| `TRAN-AMT`        | `WS-MONTHLY-INT` (truncated, scale 2)                                 |
| `TRAN-MERCHANT-ID`| `0`                                                                   |
| `TRAN-MERCHANT-NAME` / `-CITY` / `-ZIP` | spaces                                          |
| `TRAN-CARD-NUM`   | `XREF-CARD-NUM` (from the card cross‑reference)                       |
| `TRAN-ORIG-TS` / `TRAN-PROC-TS` | generated processing timestamp                          |

**Resulting account balance.** The per‑account interest accumulates into a total
that is added to the current balance via `ADD WS-TOTAL-INT TO ACCT-CURR-BAL`;
the expected post‑run balances must match **to the cent**. The run date is
supplied to the legacy program through the JCL `PARM` (`INTCALC.jcl`:
`EXEC PGM=CBACT04C,PARM='2022071800'`) and maps to a Spring Batch `JobParameter`
in the Java target.

### `statements/` — Account statements (`CBSTM03A` + `CBSTM03B` / `COSTM01.CPY`)

`CBSTM03A` produces the statement; `CBSTM03B` is its file‑I/O subroutine
(translated to an injected `FileIoService`). Two output formats exist:

- **Plain text** statement records — **`LRECL=80`** (`FD-STMTFILE-REC PIC X(80)`).
- **HTML** statement records (optional) — **`LRECL=100`** (`FD-HTMLFILE-REC PIC X(100)`).

Both lengths are confirmed by `CREASTMT.JCL` (`STMTFILE ... LRECL=80`,
`HTMLFILE ... LRECL=100`).

**Plain‑text framing & key fields:**

- Each statement is framed by a `START OF STATEMENT` banner line and an
  `END OF STATEMENT` banner line.
- The total line is labelled `Total EXP:` (`X(10)`); the amount fields
  (`ST-TRANAMT` per line, `ST-TOTAL-TRAMT` for the total) use the edited mask
  **`Z(9).99-`** — leading zero‑suppression, a fixed two‑decimal fraction, and a
  **trailing** sign that prints `-` only for negative values.
- Statements are grouped per account / customer; the transactions come from the
  `TRNX-RECORD` extract (`COSTM01.CPY`), whose key is
  `TRNX-CARD-NUM X(16)` + `TRNX-ID X(16)`.

### `sorted/` (optional) — Combined / sorted transaction file (`COMBTRAN` / `REPROCT.ctl`)

This optional subfolder validates the Java combine‑step ordering before the
IDCAMS `REPRO` load. The legacy `COMBTRAN.jcl` sorts the combined transaction
file ascending by transaction id:

```text
SORT FIELDS=(TRAN-ID,A)        ascending
TRAN-ID,1,16,CH                bytes 1–16, character collation
```

- Records are **350 bytes** (`SORTOUT` inherits the `SORTIN` DCB, `LRECL=350`).
- The sort key is the first 16 bytes (`TRAN-ID`) under **character** collation,
  ascending.
- The legacy sort specifies **no `EQUALS`** option, so the relative order of
  records with equal keys is **unspecified** by the mainframe sort. The Java
  combine step must therefore assert ordering with a **stable** `Comparator<>` on
  `TRAN-ID`, and the golden file must document/encode the chosen tie‑order so the
  expectation is deterministic for the test.

## Input → Expected pairing convention

Each golden output corresponds to a specific **input fixture** in the sibling
folder `src/test/resources/fixtures/`, which mirrors the legacy ASCII seed data
`legacy/app/data/ASCII/*.txt`:

```text
acctdata   carddata   cardxref   custdata   dailytran
discgrp    tcatbal    trancatg   trantype
```

> **The pairing is logical only.** There is **no build dependency** between
> `golden/` and `fixtures/`; both are static test data. The wiring
> (which fixture feeds which program to produce which golden file) is asserted
> entirely by the test code in `src/test/java`, not by the build.

**Recommended classpath access & naming** (to be finalized when `src/test/java`
is authored). Tests load both inputs and expectations from the classpath:

```text
classpath:fixtures/<case>.txt              (input)
classpath:golden/reports/<case>.txt        (expected report)
classpath:golden/reject/<case>.dat         (expected reject file)
classpath:golden/interest/<case>.txt       (expected interest output)
classpath:golden/statements/<case>.txt     (expected statement)
classpath:golden/sorted/<case>.dat         (expected sorted file)
```

Let each golden filename echo its input case so the pairing is obvious, e.g.
input `fixtures/dailytran-smoke.txt` → expected
`golden/reject/dailytran-smoke.reject.dat`. Use the `.dat` extension for
fixed‑width binary‑exact records (reject, sorted) and `.txt` for the
print/statement/report text outputs.

## Parity rules (recap)

- **Byte / semantic fidelity** — fixed widths, padding, and record lengths are
  exact: report `133`; reject `430` = `350` + `80`; interest & sorted tran `350`;
  statement `80`, HTML `100`. `X(n)` is right‑padded with spaces; `9(n)` is
  left‑padded with zeros.
- **Decimal truncation** — all monetary / rate math uses `BigDecimal` with
  `RoundingMode.DOWN` at the COBOL scale (interest `(bal*rate)/1200`, **no
  rounding**). Floating‑point (`float`/`double`) is prohibited for decimal data.
- **Edited‑field rendering** — reproduce zero‑suppression, comma grouping, and
  sign placement exactly: detail amount `-ZZZ,ZZZ,ZZZ.ZZ` (leading sign); totals
  `+ZZZ,ZZZ,ZZZ.ZZ` (leading sign); statement amounts `Z(9).99-` (trailing sign).
- **Upsert semantics** — a record read that accepts `FILE STATUS '00' OR '23'`
  is a find‑or‑create (e.g. transaction‑category‑balance); the expected data must
  reflect the created row when the original was absent.
- **Sort semantics** — `SORT FIELDS=(TRAN-ID,A)`, character collation, no
  `EQUALS`; assert with a stable comparator and document tie behavior.
- **Local‑only generation** — golden files are derived locally from the
  documented COBOL logic; no COBOL/mainframe runtime is required to author or run
  the tests.

## License

These test assets are part of AWS CardDemo (modernized) and are licensed under
the Apache License, Version 2.0.

