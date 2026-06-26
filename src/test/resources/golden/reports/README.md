# Golden Files — Daily Transaction Report (CBTRN03C)

This folder holds the **EXPECTED** (golden) output of the modernized
`TransactionReportService` (Spring Batch) — a **1:1 functional port** of the
legacy COBOL batch program `app/cbl/CBTRN03C.cbl` (retained read-only at
`legacy/app/cbl/CBTRN03C.cbl`). The report parity tests under `src/test/java`
run the report job against controlled input and assert the produced output
**byte-for-byte** against the files documented here.

The acceptance bar is **100% behavioral parity** with the COBOL report:
identical **page / account / grand totals** and **byte-faithful edited-field
rendering**. This traces directly to the Agent Action Plan:

- **AAP §0.4.1 — `TransactionReportJob`**: `CBTRN03C.cbl` + `TRANREPT.jcl` +
  `TRANREPT.prc` → "Transaction detail report with page/account/grand totals".
- **AAP §0.6.1 — decimal & edited-field fidelity**: every monetary amount is a
  `java.math.BigDecimal` (scale 2, `RoundingMode.DOWN`); COBOL numeric-edited
  PICs are rendered byte-faithfully — **never** `float`/`double`.
- **AAP §0.6.7 — local-only golden-file parity**: the goldens are derived
  locally from the documented COBOL logic; **no** COBOL or mainframe runtime is
  required to author them or to run the tests.

> **This file is plain documentation — no code is executed from it.** It is the
> durable, human-readable record of **what** these golden files contain,
> **why** they look the way they do, and **how** to reproduce them, because the
> byte-exact generators and the controlled-input specification otherwise live
> only inside the (now-consumed) file-creation prompts.

---

## 1. Files in this folder

| File | Case | Lines | Bytes | MD5 |
|------|------|------:|------:|-----|
| `daily-transaction-report-basic.txt`  | basic      | 15 | 2010 | `8b3d2f456a087646fe001f67034488c0` |
| `daily-transaction-report-paging.txt` | paging     | 31 | 4154 | `ed35d8f4c741db96478c9293598e85f5` |
| `README.md`                           | (this doc) | —  | —    | —                                  |

**Byte arithmetic.** Every line is exactly `133` content characters **plus one**
`\n` (LF), so `bytes = lines × 134`:

- basic:&nbsp;&nbsp;`15 × 134 = 2010`
- paging: `31 × 134 = 4154`

The two cases are deliberately complementary: **basic** exercises multi-card
totalling, a negative amount, a zero amount, and the end-of-file double-count on
a single page; **paging** drives the report past the 20-line page boundary so a
mid-run page break (page total + fresh header block) is exercised.

---

## 2. File-format contract (the byte-exact spec)

Sourced from the report copybook `app/cpy/CVTRA07Y.cpy` and the output DD in
`app/jcl/TRANREPT.jcl` (`//TRANREPT DD ... DCB=(LRECL=133,RECFM=FB,...)`).

### 2.1 Whole-file invariants

- Every line is **exactly 133 characters**, right-padded with ASCII spaces
  (`0x20`).
- Each line is terminated by a single **`\n`** (Unix **LF**, `0x0A`).
- The file is pure **ASCII**, has **no BOM**, contains **no CR** (`0x0D`), and
  **ends with a trailing newline**.
- **Trailing spaces are significant** — they are part of the fixed-width record
  and must be preserved on compare (do not strip).

### 2.2 Line layouts (0-indexed offsets)

All offsets are half-open `[start:end)`; `end - start` is the field width. Any
positions after the last listed field are spaces padding the line to 133.

**Name header** (`REPORT-NAME-HEADER`) — written once per page:

| Offset | Width | Content | Copybook field |
|--------|------:|---------|----------------|
| `[0:38]`    | 38 | `DALYREPT` (then spaces)            | `REPT-SHORT-NAME` `X(38)` |
| `[38:79]`   | 41 | `Daily Transaction Report` (spaces) | `REPT-LONG-NAME` `X(41)`  |
| `[79:91]`   | 12 | `Date Range: `                      | `REPT-DATE-HEADER` `X(12)`|
| `[91:101]`  | 10 | start date                          | `REPT-START-DATE` `X(10)` |
| `[101:105]` | 4  | ` to ` (literal)                    | `FILLER` `X(04)`          |
| `[105:115]` | 10 | end date                            | `REPT-END-DATE` `X(10)`   |
| `[115:133]` | 18 | spaces (pad to 133)                 | —                        |

**Blank line** (`WS-BLANK-LINE`): 133 spaces.

**Column header-1** (`TRANSACTION-HEADER-1`):

| Offset | Width | Content |
|--------|------:|---------|
| `[0:17]`    | 17 | `Transaction ID` |
| `[17:29]`   | 12 | `Account ID` |
| `[29:48]`   | 19 | `Transaction Type` |
| `[48:83]`   | 35 | `Tran Category` |
| `[83:97]`   | 14 | `Tran Source` |
| `[97:98]`   | 1  | space |
| `[98:114]`  | 16 | `        Amount` (8 leading spaces + `Amount`, then 2 trailing spaces) |
| `[114:133]` | 19 | spaces (pad to 133) |

**Header-2 / rule line** (`TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'`):
133 `-` characters.

**Detail line** (`TRANSACTION-DETAIL-REPORT`):

| Offset | Width | Field | PIC |
|--------|------:|-------|-----|
| `[0:16]`    | 16 | `TRAN-REPORT-TRANS-ID`   | `X(16)` |
| `[16:17]`   | 1  | space (FILLER)          | `X(01)` |
| `[17:28]`   | 11 | `TRAN-REPORT-ACCOUNT-ID` (zero-padded) | `X(11)` |
| `[28:29]`   | 1  | space                   | `X(01)` |
| `[29:31]`   | 2  | `TRAN-REPORT-TYPE-CD`    | `X(02)` |
| `[31:32]`   | 1  | `-` (literal FILLER)    | `X(01)` |
| `[32:47]`   | 15 | `TRAN-REPORT-TYPE-DESC`  | `X(15)` |
| `[47:48]`   | 1  | space                   | `X(01)` |
| `[48:52]`   | 4  | `TRAN-REPORT-CAT-CD`     | `9(04)` |
| `[52:53]`   | 1  | `-` (literal FILLER)    | `X(01)` |
| `[53:82]`   | 29 | `TRAN-REPORT-CAT-DESC`   | `X(29)` |
| `[82:83]`   | 1  | space                   | `X(01)` |
| `[83:93]`   | 10 | `TRAN-REPORT-SOURCE`     | `X(10)` |
| `[93:97]`   | 4  | spaces (FILLER)         | `X(04)` |
| `[97:112]`  | 15 | `TRAN-REPORT-AMT` (edited) | `-ZZZ,ZZZ,ZZZ.ZZ` |
| `[112:114]` | 2  | spaces (FILLER)         | `X(02)` |
| `[114:133]` | 19 | spaces (pad to 133)     | —       |

**Totals lines** — the label is a fixed `X` literal, followed by a dotted
leader of `.` characters, then the edited amount in the **same `[97:112]`
column** as the detail amount (15 wide), then spaces to 133:

| Line | Label (offset/width) | Dotted leader | Amount field | Amount PIC |
|------|----------------------|---------------|--------------|------------|
| Page Total    | `Page Total ` `[0:11]` `X(11)`  | `[11:97]` 86 `.` | `[97:112]` | `+ZZZ,ZZZ,ZZZ.ZZ` |
| Account Total | `Account Total` `[0:13]` `X(13)` | `[13:97]` 84 `.` | `[97:112]` | `+ZZZ,ZZZ,ZZZ.ZZ` |
| Grand Total   | `Grand Total` `[0:11]` `X(11)`  | `[11:97]` 86 `.` | `[97:112]` | `+ZZZ,ZZZ,ZZZ.ZZ` |

> Note the label widths: `Page Total` is 10 chars in an `X(11)` field (one
> trailing space → `Page Total `); `Grand Total` is exactly 11 chars; `Account
> Total` is exactly 13 chars. The leader length is whatever fills columns up to
> `[97]`: `11 + 86 = 97` and `13 + 84 = 97`.

### 2.3 Edited-numeric rendering rules (critical for parity)

**Detail amounts — PIC `-ZZZ,ZZZ,ZZZ.ZZ`** (15 chars):

- The leftmost position is a **sign** position: `-` for a negative value,
  a **space** for a non-negative value.
- The nine `Z` digit positions **zero-suppress**: leading zeros — *and the
  comma group-separators to their left* — render as spaces until the first
  significant digit; from the first significant digit rightward, digits and
  the surviving commas print normally.
- Then a literal `.` and the two cents digits.
- **A zero value (`0.00`) renders as 15 blanks.** With an all-`Z` integer *and*
  fraction and a space sign, there is no significant digit anywhere, so every
  position — including the decimal point — is suppressed to a space.

**Totals — PIC `+ZZZ,ZZZ,ZZZ.ZZ`** (15 chars): identical zero-suppression, except
the leading sign position prints `+` for non-negative and `-` for negative.

**Decimal discipline.** Amounts are `java.math.BigDecimal`, **scale 2**,
`RoundingMode.DOWN` (COBOL truncation) — **never** `float`/`double`.

**Worked examples** (the 15-char field is shown between `|…|` so every space is
visible):

```text
detail amount   PIC -ZZZ,ZZZ,ZZZ.ZZ
       504.77 ->  |         504.77|   sign = space; 9 leading blanks
      -919.00 ->  |-        919.00|   sign = '-' at the leading position
   1234567.89 ->  |   1,234,567.89|   2 leading zeros + their comma suppressed
         0.00 ->  |               |   all 15 blanks (decimal point suppressed)

total amount    PIC +ZZZ,ZZZ,ZZZ.ZZ
  +1384104.66 ->  |+  1,384,104.66|   sign = '+' (non-negative)
     +1600.00 ->  |+      1,600.00|
```


---

## 3. Controlled input (and why it is synthetic)

The legacy repository ships **no posted `TRANSACT` master** — it provides only
the `DALYTRAN` daily feed (`app/data/ASCII/dailytran.txt`), whose `TRAN-PROC-TS`
(processing timestamp) is **blank**. A blank processing timestamp cannot satisfy
the report's date filter, so the daily feed alone cannot drive a date-ranged
report. The Flyway migration `V2__seed_reference_data.sql` seeds the reference
tables and the master files, but it deliberately leaves the `transaction` table
**empty**.

Therefore each report parity test **inserts its own controlled `transaction`
rows** (the 350-byte `CVTRA05Y` / `CVTRA06Y` layout) with **in-window
`TRAN-PROC-TS`** values, then runs the report job with a controlled date
parameter:

- **`DATEPARM` start = `2022-07-01`, end = `2022-07-31`** (inclusive). These map
  to the legacy `DATEPARM` DD record (`WS-START-DATE X(10)` + 1-byte filler +
  `WS-END-DATE X(10)`) and surface as Spring Batch `JobParameters` in the Java
  target. They appear verbatim in every page's name-header `Date Range:` line,
  e.g. the first line of each golden file begins:

  ```text
  DALYREPT                              Daily Transaction Report                 Date Range: 2022-07-01 to 2022-07-31
  ```

- Records are **pre-sorted by `TRAN-CARD-NUM` ascending** — this mirrors the
  legacy `SORT FIELDS=(TRAN-CARD-NUM,A)` step in `app/jcl/TRANREPT.jcl`. Within a
  single card the **input order is preserved** (the legacy sort defines no
  secondary key, so a **stable** sort reproduces its order). Because the report
  groups by account and the account is resolved from the card via `CARDXREF`,
  card order **is** account order for these fixtures.

> The two timestamps `TRAN-ORIG-TS` / `TRAN-PROC-TS` only need their first 10
> characters (`YYYY-MM-DD`) to fall inside the window; the report's date test is
> `TRAN-PROC-TS(1:10)` BETWEEN start AND end (inclusive).

### 3.1 Basic case — `daily-transaction-report-basic.txt` (6 input rows)

Two cards (two accounts), a negative amount, a zero amount, and a large amount —
all on a single page.

| # | TRAN-ID            | CARD-NUM           | → ACCT        | TYPE | CAT  | SOURCE | TRAN-AMT     |
|---|--------------------|--------------------|---------------|------|------|--------|--------------|
| 1 | `0000000000000001` | `0500024453765740` | `00000000050` | `01` | `0001` | `POS`    | `504.77`     |
| 2 | `0000000000000002` | `0500024453765740` | `00000000050` | `05` | `0001` | `POS`    | `-919.00`    |
| 3 | `0000000000000003` | `0500024453765740` | `00000000050` | `01` | `0002` | `POS`    | `1234567.89` |
| 4 | `0000000000000004` | `0683586198171516` | `00000000027` | `02` | `0001` | `ONLINE` | `0.00`       |
| 5 | `0000000000000005` | `0683586198171516` | `00000000027` | `03` | `0001` | `ONLINE` | `-50.00`     |
| 6 | `0000000000000006` | `0683586198171516` | `00000000027` | `01` | `0002` | `POS`    | `75000.50`   |

Rows 1–3 belong to card `0500024453765740` (account `00000000050`); rows 4–6 to
card `0683586198171516` (account `00000000027`). Card `0500…` sorts before
`0683…`, so the three account-`050` rows are emitted first.

### 3.2 Paging case — `daily-transaction-report-paging.txt` (18 input rows)

A single card, identical fields, used purely to cross the page boundary:

- All 18 rows: CARD-NUM `0500024453765740` (→ account `00000000050`),
  TYPE `01`, CAT `0001`, SOURCE `POS`, **AMOUNT `100.00`**.
- TRAN-IDs run `0000000000000001` … `0000000000000018` (input/stable order).

---

## 4. Reference-data resolution (must match the Flyway seeds)

For each transaction the report looks up three reference values; the golden
output assumes the **real seeded** reference rows loaded from
`app/data/ASCII/trantype.txt`, `trancatg.txt`, and `cardxref.txt` (mirrored under
`legacy/app/...` and into `src/test/resources/fixtures/ascii/`).

**`tran_type`** — description truncated to **15** chars in the report
(`TRAN-REPORT-TYPE-DESC X(15)`):

| TYPE-CD | Description  |
|---------|--------------|
| `01` | `Purchase`      |
| `02` | `Payment`       |
| `03` | `Credit`        |
| `04` | `Authorization` |
| `05` | `Refund`        |
| `06` | `Reversal`      |
| `07` | `Adjustment`    |

**`tran_category`** — description truncated to **29** chars in the report
(`TRAN-REPORT-CAT-DESC X(29)`):

| (TYPE-CD, CAT-CD) | Description           |
|-------------------|-----------------------|
| `(01, 0001)` | `Regular Sales Draft`      |
| `(01, 0002)` | `Regular Cash Advance`     |
| `(02, 0001)` | `Cash payment`             |
| `(03, 0001)` | `Credit to Account`        |
| `(05, 0001)` | `Refund credit`            |

**`card_xref`** — card number → account id:

| CARD-NUM           | → ACCT-ID     |
|--------------------|---------------|
| `0500024453765740` | `00000000050` |
| `0683586198171516` | `00000000027` |


---

## 5. ⚠️ Parity quirks — INTENTIONAL (do **NOT** "fix")

> These are **bug-for-bug** behaviors of the legacy COBOL program. They are
> reproduced **on purpose** so the modernized service is a faithful port. Every
> value below is asserted by a golden file; "correcting" any of them **breaks
> parity** and will fail the tests. Do not change them.

### 5.1 EOF stale-amount DOUBLE-COUNT (most important)

The COBOL main loop reads with `READ TRANSACT-FILE INTO TRAN-RECORD`. At
end-of-file (FILE STATUS `'10'`) the `READ ... INTO` **does not overwrite** the
record area, so `TRAN-AMT` still holds the **last record's amount**. The EOF
branch then executes `ADD TRAN-AMT TO WS-PAGE-TOTAL, WS-ACCOUNT-TOTAL` before
writing the closing page total and grand total — so the **last amount is counted
a second time** in the page total and (through `ADD WS-PAGE-TOTAL TO
WS-GRAND-TOTAL`) in the grand total.

- **Basic:** the true sum of the 6 amounts is `1,309,104.16`, but the
  **Page Total and Grand Total both show `+  1,384,104.66`**
  (`1,309,104.16 + 75,000.50` — the last amount, `75000.50`, double-counted).
- **Paging:** page 2 holds rows 17 + 18, whose true sum is `200.00`, but the
  **Page Total shows `+        300.00`** (`+100.00` double-count of the stale
  row-18 amount); the true grand total is `1,800.00`, but the
  **Grand Total shows `+      1,900.00`**.

### 5.2 Account Total is emitted only on a card change — never at EOF

The account-total line (`1120-WRITE-ACCOUNT-TOTALS`) is written **only** when the
card number changes mid-stream (and not on the very first record). It is **never**
written in the EOF branch. Consequences:

- **Basic:** card A (account `00000000050`) is closed by an account total
  **`Account Total....+  1,234,153.66`** (`504.77 − 919.00 + 1,234,567.89`) when
  the stream switches to card B. Card B (account `00000000027`) gets **no**
  account-total line because the run ends via EOF, not a card change →
  **exactly ONE account-total line in the basic file.**
- **Paging:** a single card means no card change ever occurs →
  **ZERO account-total lines.**

### 5.3 A zero amount renders as 15 blanks

Basic row 4 has `TRAN-AMT = 0.00`. Under PIC `-ZZZ,ZZZ,ZZZ.ZZ` an all-zero value
suppresses every digit, the sign is a space, and the decimal point is suppressed
too — the amount column `[97:112]` is **15 spaces**.

### 5.4 Page break at `MOD(line-counter, 20) == 0` (headers count)

The page size is **20** (`WS-PAGE-SIZE VALUE 20`) and the page break fires when
`FUNCTION MOD(WS-LINE-COUNTER, 20) = 0`. The line counter is incremented for the
header lines too, so headers count toward the page. In the **paging** case the
counter reaches 20 right after the 16th detail line, so before row 17 the program
emits a **Page Total + a dashes rule line + a fresh header block**:

- **Page 1 total = `+      1,600.00`** (`16 × 100.00`, **no** double-count —
  it is closed by a normal page break, not by EOF).
- **Page 2** then carries rows 17–18 and is closed at EOF, where the
  double-count of §5.1 applies (`+        300.00`).

### 5.5 Sign placement differs between detail and totals

- **Detail** amounts use a single leading sign character: `-` for negatives at
  the far-left position, a space for non-negatives — e.g. `-919.00` →
  `'-        919.00'`.
- **Totals** use PIC `+ZZZ,ZZZ,ZZZ.ZZ`: a leading `+` for non-negative and `-`
  for negative.

### 5.6 The date filter is effectively pre-applied

In the legacy job the records are filtered by `TRAN-PROC-TS(1:10)` BETWEEN start
and end (inclusive) and then card-sorted by the JCL `SORT`/`INCLUDE COND` step
**before** `CBTRN03C` runs. The program's own in-loop date check (the COBOL
`NEXT SENTENCE` skip branch) is therefore **dead code** for the shipped pipeline:
every record the program sees is already in-window. The parity test mirrors this
by inserting only in-window rows and supplying the matching `DATEPARM`.

### 5.7 Resulting line composition (for reference)

```text
basic (15 lines)                            paging (31 lines)
 1  name header                              1  name header           (page 1)
 2  blank                                    2  blank
 3  column header-1                          3  column header-1
 4  rule (-----)                             4  rule (-----)
 5  detail row 1   504.77                    5  detail row 1   100.00
 6  detail row 2  -919.00                    …  (rows 2..15)
 7  detail row 3   1234567.89               20  detail row 16  100.00
 8  Account Total  +  1,234,153.66          21  Page Total     +      1,600.00
 9  rule (-----)                            22  rule (-----)
10  detail row 4   0.00 -> 15 blanks        23  name header           (page 2)
11  detail row 5  -50.00                    24  blank
12  detail row 6   75000.50                 25  column header-1
13  Page Total     +  1,384,104.66          26  rule (-----)
14  rule (-----)                            27  detail row 17  100.00
15  Grand Total    +  1,384,104.66          28  detail row 18  100.00
                                            29  Page Total     +        300.00
                                            30  rule (-----)
                                            31  Grand Total    +      1,900.00
```

---

## 6. How to regenerate (reproducibility)

Each golden `.txt` file was produced by a **self-contained, deterministic
Python 3 generator** (no external dependencies) embedded in that file's
own file-creation prompt. The generator emulates `CBTRN03C` and writes the exact
bytes, then asserts its own output's MD5 against the value recorded in §1 before
the file is accepted (it writes to a scratch path under `/tmp/...` and compares
`hashlib.md5(...)`).

The generator encodes:

- the **record layout** from `app/cpy/CVTRA07Y.cpy` (the field offsets and the
  edited PICs documented in §2), and
- the **control flow** from `app/cbl/CBTRN03C.cbl`: page size **20**, the header
  line-counter deltas, the **EOF double-count**, and **account-total-on-card-
  change**.

Because the bytes are fully determined by the documented input (§3), reference
data (§4), and quirks (§5), the goldens can be regenerated at any time without a
COBOL or mainframe runtime — consistent with **AAP §0.6.7** (local-only parity).
If a golden must legitimately change, update both the bytes **and** the §1
line/byte/MD5 row in the same change.

---

## 7. How the tests consume these files (classpath pairing)

- The report parity tests load each golden file from the **test classpath** as
  **`classpath:golden/reports/<case>.txt`**, e.g.
  `classpath:golden/reports/daily-transaction-report-basic.txt` and
  `classpath:golden/reports/daily-transaction-report-paging.txt`.
- The generated report is compared **byte-for-byte** against the golden —
  **nothing is normalized**: trailing spaces, the fixed 133-column width, and the
  final trailing newline are all part of the comparison (comparing raw bytes, or
  equivalently the MD5s in §1, is sufficient).
- The input fixtures conceptually live in the sibling `fixtures/` tree
  (`src/test/resources/fixtures/ascii/*.txt`), but the relationship is
  **logical only — there is NO build/compile dependency** between this folder and
  `fixtures/`. Both are independent static test assets; the wiring (which input
  produces which golden) is asserted entirely by the test code in
  `src/test/java`, not by the build.

---

## 8. Source lineage & license

These golden files are derived from the following legacy artifacts, all retained
read-only under `legacy/app/...` in the destination repository:

- `app/cbl/CBTRN03C.cbl` — the report program (control flow, totals, paging).
- `app/cpy/CVTRA07Y.cpy` — the report record layout (offsets and edited PICs).
- `app/jcl/TRANREPT.jcl` — output DD `RECFM=FB,LRECL=133`; the card-number
  `SORT` and date `INCLUDE COND` pre-filter.
- `app/proc/TRANREPT.prc` — the report PROC (unload + sort + `CBTRN03C` steps).
- Reference data: `app/data/ASCII/trantype.txt`, `app/data/ASCII/trancatg.txt`,
  `app/data/ASCII/cardxref.txt`.

This project is licensed under the **Apache License, Version 2.0**.
