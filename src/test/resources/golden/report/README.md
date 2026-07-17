# Golden Fixture — Transaction Detail Report (`TransactionReportJob` / legacy `CBTRN03C`)

## 1. Overview

This directory holds the **EXPECTED (golden) half** of the row-for-row parity
contract for the `TransactionReportJob` — the Java/Spring Batch re-platform of
the legacy COBOL batch program **`CBTRN03C`** (Daily Transaction Detail Report).
It satisfies the migration acceptance criteria in **AAP §0.9.2** (golden-file
parity: "batch outputs … are compared row-for-row against expected fixtures")
and **AAP §0.9.6** ("row-for-row match on … report outputs").

- **`expected-report.txt`** (sibling file) — the exact rendered report produced
  by applying `CBTRN03C` logic to the scenario documented here. It is the
  authoritative byte-for-byte fixture the parity test asserts against.
- **This `README.md`** — the **coordination source-of-truth** that binds three
  artifacts into a single agreed scenario:
  1. the **seed author** (`src/test/resources/seed/**`) transcribes the input
     rows in Section 3 into CSV files;
  2. the **parity-test author** (`src/test/java/com/aws/carddemo/**`) reads the
     job parameters (date range, Section 2) and the expected result;
  3. the **golden fixture** (`expected-report.txt`) is regenerated from these
     same inputs whenever the scenario changes (Section 8).

The report is a **fixed-width flat file**. Every record is **exactly 133
characters** (COBOL `FD-REPTFILE-REC PIC X(133)`), lines are terminated with a
single **`LF` (`\n`)**, and the file contains **15 records / 2010 bytes total**
(15 × (133 + 1) = 2010, i.e. a trailing newline is present on the final record).

The report is **fully deterministic**: the reporting date range is supplied as
an **INPUT** (the DATE-PARMS file / job parameters), **not** read from the
system clock. Because nothing in the output depends on wall-clock time, the
fixture is safe to assert **byte-for-byte**.

> Documentation only. This file contains **no secrets** — no CVV, no passwords,
> and no real card numbers. The 16-digit values below are synthetic wiring IDs
> chosen purely to isolate this scenario from all other seed data.

## 2. DATE-PARMS — the report date range

| Parameter | Value |
|-----------|------------|
| Start date | **2025-01-01** |
| End date   | **2025-01-31** |

### COBOL date-parms record

`CBTRN03C` reads a single control record, `FD-DATEPARM-REC`, into
`WS-DATEPARM-RECORD`, which is laid out as:

| Field | PIC | Meaning |
|-------|-----|---------|
| `WS-START-DATE` | `X(10)` | inclusive start date, `YYYY-MM-DD` |
| `FILLER`        | `X(01)` | one separator character (a space) |
| `WS-END-DATE`   | `X(10)` | inclusive end date, `YYYY-MM-DD` |

So the on-disk parameter line for this scenario is literally:

```
2025-01-01 2025-01-31
```

In the Java `TransactionReportJob` this record maps to **job parameters**
(e.g. `startDate=2025-01-01`, `endDate=2025-01-31`).

### Inclusive filter — and it uses `proc_ts`, not `orig_ts`

A transaction qualifies for the report **iff** the first 10 characters of its
**processing timestamp** fall inside the closed range
`[2025-01-01, 2025-01-31]`. This is verified in
`legacy/cbl/CBTRN03C.cbl` (original path `app/cbl/CBTRN03C.cbl`), **lines
173-174**:

```cobol
IF TRAN-PROC-TS (1:10) >= WS-START-DATE
   AND TRAN-PROC-TS (1:10) <= WS-END-DATE
```

> **CRITICAL:** the filter keys on **`proc_ts`** (processing timestamp), **not**
> `orig_ts` (origination timestamp). The base seed transactions (IDs 1–50) have
> a **blank `proc_ts`** and would therefore qualify **zero** rows. That is
> exactly why this scenario authors its **own** transactions with a populated
> `proc_ts` — see Section 3.

## 3. Scenario inputs — the authoritative rows the seed author must create

These are the rows the **seed author** transcribes into `src/test/resources/seed/**`
CSVs. They use **isolated high IDs** that never collide with the base seed
(IDs 1–50) or with the other isolated scenarios (posting account `90000000001`,
interest account `90000000010`).

**Author the rows in FK-safe order:** `customer` → `account` → `card` →
`card_xref` → `transaction`.

> `transaction_type` and `transaction_category` are **GLOBAL reference data**
> already loaded by Flyway `V2__reference_data.sql`. They are **not** re-authored
> here; this scenario only uses type/category codes that already exist (Section 4).

### 3.1 Customers (×2) — table `customer`

Only `cust_id` matters to the report, but **all NOT-NULL columns must be filled**
per the customer CSV contract. The customer CSV header (18 columns) is:

```
cust_id,first_name,middle_name,last_name,addr_line_1,addr_line_2,addr_line_3,state_cd,country_cd,addr_zip,phone_num_1,phone_num_2,ssn,govt_issued_id,dob,eft_account_id,pri_card_holder_ind,fico_credit_score
```

| cust_id |
|---------|
| 900000021 |
| 900000022 |

### 3.2 Accounts (×2) — table `account`

The report prints the **ACCOUNT-ID** obtained from the XREF lookup (Section 5).
`group_id` may use the existing `DEFAULT` disclosure group. All NOT-NULL columns
must be filled per the account CSV contract. The account CSV header (12 columns)
is:

```
acct_id,active_status,curr_bal,credit_limit,cash_credit_limit,open_date,expiration_date,reissue_date,curr_cyc_credit,curr_cyc_debit,addr_zip,group_id
```

| acct_id |
|---------|
| 90000000021 |
| 90000000022 |

### 3.3 Cards (×2) — table `card`

16-character card numbers — **quote them in the CSV** to preserve any leading
digits. The CVV is synthetic and **never** appears in the report. Populate all
NOT-NULL columns per the card CSV contract; the two report-relevant columns are
`card_num` and `acct_id`.

| card_num | acct_id |
|----------|---------|
| `9000000000000021` | 90000000021 |
| `9000000000000022` | 90000000022 |

### 3.4 Card cross-references (×2) — table `card_xref`

`CBTRN03C` looks up the account id for each card via the cross-reference; this is
the value printed in the **ACCOUNT-ID** column. CSV header:

```
xref_card_num,xref_cust_id,xref_acct_id
```

| xref_card_num | xref_cust_id | xref_acct_id |
|---------------|--------------|--------------|
| `9000000000000021` | 900000021 | 90000000021 |
| `9000000000000022` | 900000022 | 90000000022 |

### 3.5 Transactions (×6) — table `transaction`

Author these **in ascending `tran_id` order**. The report reads/sorts by key, and
the rows are grouped by card so the **account break** (Section 5, step 5) fires
correctly between account A (`…0021`) and account B (`…0022`). CSV header
(13 columns):

```
tran_id,type_cd,cat_cd,source,description,amount,merchant_id,merchant_name,merchant_city,merchant_zip,card_num,orig_ts,proc_ts
```

Column conventions:

- **`type_cd`** — a 2-character string **with the leading zero** (e.g. `01`).
- **`cat_cd`** — a **plain INTEGER** in the CSV (e.g. `1`, **not** `0001`),
  because the DB column is `INTEGER`; the report re-formats it to 4 zero-padded
  digits on output (`PIC 9(04)` → `0001`).
- **`amount`** — scale-2 decimal.
- **`proc_ts`** — a full timestamp whose **date part is inside the range**
  (e.g. `2025-01-05-00.00.00.000000` or `2025-01-05T00:00:00`). Only the first 10
  characters (the date) matter to the filter.

| tran_id | card_num | type_cd | cat_cd | source | amount | proc_ts (date part) |
|---|---|---|---|---|---|---|
| `9000000000000201` | `9000000000000021` | 01 | 1 | POS TERM | 1234.56 | 2025-01-05 |
| `9000000000000202` | `9000000000000021` | 01 | 2 | POS TERM | 500.00 | 2025-01-06 |
| `9000000000000203` | `9000000000000021` | 03 | 1 | SYSTEM | -250.00 | 2025-01-07 |
| `9000000000000204` | `9000000000000022` | 02 | 1 | WEB | -1000.00 | 2025-01-10 |
| `9000000000000205` | `9000000000000022` | 01 | 4 | ATM | 75.25 | 2025-01-11 |
| `9000000000000206` | `9000000000000022` | 04 | 1 | AUTH | 0.00 | 2025-01-12 |

`description`, `merchant_*`, and `orig_ts` are **free / minor** for the report —
the report renders the type and category **descriptions looked up from the
reference tables** (Section 4), never the transaction's own `description`.
Nonetheless, **every NOT-NULL column must still be populated** to satisfy the
schema.

## 4. Reference-data descriptions used (from Flyway `V2`, must already exist)

These are the type / category codes this scenario references and the descriptions
the report renders for them, so anyone can confirm the golden text by hand. The
report truncates `TYPE-DESC` to **15** characters and `CAT-DESC` to **29**
characters (`legacy/cpy/CVTRA07Y.cpy`); every description below fits without
truncation.

**Transaction types** (`transaction_type`):

| type_cd | description |
|---------|-------------|
| 01 | Purchase |
| 02 | Payment |
| 03 | Credit |
| 04 | Authorization |

**Transaction categories** (`transaction_category`, keyed by `(type_cd, cat_cd)`):

| type_cd | cat_cd | description |
|---------|--------|-------------|
| 01 | 0001 | Regular Sales Draft |
| 01 | 0002 | Regular Cash Advance |
| 01 | 0004 | ATM Cash Advance |
| 02 | 0001 | Cash payment |
| 03 | 0001 | Credit to Account |
| 04 | 0001 | Zero dollar authorization |

## 5. Derivation — how `CBTRN03C` produces the output (preserve exactly)

Source: `legacy/cbl/CBTRN03C.cbl` (original path `app/cbl/CBTRN03C.cbl`); record
layouts in `legacy/cpy/CVTRA07Y.cpy` (original `app/cpy/CVTRA07Y.cpy`). The Java
`TransactionReportJob` must reproduce this control flow exactly.

1. **Read date parms; open files; loop.** Read the DATE-PARMS record, open the
   input/output files, then `PERFORM UNTIL END-OF-FILE`.
2. **Filter.** For each transaction in key order, keep it only if
   `proc_ts[0:10]` is within `[start, end]` (Section 2).
3. **Header-first.** On the **first qualifying record**, emit the page headers —
   `REPORT-NAME-HEADER`, a blank line, `TRANSACTION-HEADER-1`, and
   `TRANSACTION-HEADER-2` (a full line of dashes) — 4 lines total, advancing the
   line counter to 4.
4. **Page break.** Before each detail, if `MOD(line_counter, 20) == 0` emit a
   Page Total and re-emit the headers (`WS-PAGE-SIZE = 20`). With only 6 detail
   rows the counter never reaches a multiple of 20 mid-report, so this scenario
   is a **single page** (no mid-report page break).
5. **Account break.** When the card number changes **and it is not the first
   record**, emit the prior card's **Account Total** line followed by a dashes
   line, then re-look-up the XREF for the new card.
6. **Detail.** Each detail adds `TRAN-AMT` to the running **page total** and
   **account total**, then writes the 133-character detail line.
7. **At EOF.** Emit the final **Page Total** + dashes + **Grand Total**. The
   grand total is fed **only** by page totals (each Page Total is added into the
   grand total when it is written).

### Exact emitted sequence (15 records)

| # | Record | Notes |
|---|--------|-------|
| 1 | NAME-HEADER | `DALYREPT` + long name + `Date Range: 2025-01-01 to 2025-01-31` |
| 2 | (blank line) | 133 spaces |
| 3 | HEADER-1 | column titles |
| 4 | dashes | `TRANSACTION-HEADER-2`, 133 `-` |
| 5 | A1 | tran `…0201`, acct `90000000021`, `01-Purchase`, `0001-Regular Sales Draft`, `POS TERM`, `1,234.56` |
| 6 | A2 | tran `…0202`, `01-Purchase`, `0002-Regular Cash Advance`, `POS TERM`, `500.00` |
| 7 | A3 | tran `…0203`, `03-Credit`, `0001-Credit to Account`, `SYSTEM`, `-250.00` |
| 8 | **Account Total** | account A: **`+      1,484.56`** |
| 9 | dashes | 133 `-` |
| 10 | B1 | tran `…0204`, acct `90000000022`, `02-Payment`, `0001-Cash payment`, `WEB`, `-1,000.00` |
| 11 | B2 | tran `…0205`, `01-Purchase`, `0004-ATM Cash Advance`, `ATM`, `75.25` |
| 12 | B3 | tran `…0206`, `04-Authorization`, `0001-Zero dollar authorization`, `AUTH`, **blank amount** (`0.00`) |
| 13 | **Page Total** | **`+        559.81`** |
| 14 | dashes | 133 `-` |
| 15 | **Grand Total** | **`+        559.81`** |

### Totals arithmetic

- **Account A total** = `1234.56 + 500.00 − 250.00` = **`1,484.56`**
- **Page total = Grand total**
  = `1234.56 + 500.00 − 250.00 − 1000.00 + 75.25 + 0.00` = **`559.81`**

(Note there is **no** Account Total for account B — see Section 6.)

## 6. Three `CBTRN03C` quirks (documented explicitly — they shape the scenario)

The legacy program has three behaviors that directly determine the golden output.
The scenario is deliberately constructed so the golden file is correct **whether
or not** the idiomatic Java job reproduces the quirks.

1. **EOF double-add.** COBOL `READ … INTO` leaves the **last record still in the
   buffer** when `AT END` fires. That stale record passes the date filter again,
   and its amount is added **a second time** into the page/account totals just
   before the final totals are written (`legacy/cbl/CBTRN03C.cbl`, the `ELSE`
   branch at lines 197-204).
   **Mitigation:** the **last** transaction (`B3`) has amount **`0.00`**, so the
   double-add is a **no-op** — the golden totals are identical whether or not the
   idiomatic Spring Batch reader (which returns `null` at EOF and therefore does
   **not** re-add) reproduces the quirk. **Recommendation:** the Java job should
   simply **not** reproduce the bug; the `0.00` last row makes both behaviors
   agree byte-for-byte.

2. **Last account has no Account Total.** Account totals are written **only** on a
   card-number **change** (Section 5, step 5). Account **B** is the last account,
   so its Account Total is **never** emitted — only account **A**'s Account Total
   appears (record 8). At EOF only the **Page Total** + dashes + **Grand Total**
   are written (records 13-15).

3. **`NEXT SENTENCE` early-exit.** A transaction whose `proc_ts[0:10]` is
   **outside** the range triggers COBOL `NEXT SENTENCE`, which jumps past the
   loop's `END-PERFORM` period and **terminates** processing (line 177).
   **Mitigation:** **all six** scenario transactions are **inside** the range, so
   this edge is never exercised in this fixture.

## 7. Expected output and how the test consumes it

### The golden fixture

The sibling file **`expected-report.txt`** is the authoritative output:

| Property | Value |
|----------|-------|
| Records | **15** |
| Record width | **133 characters** (each line) |
| Total size | **2010 bytes** |
| Line endings | **`LF` only** (no `CR`) |
| Trailing newline | **present** (15 × 134 = 2010) |
| MD5 | `08e42531fb0c9b5786685443225d3a55` |

### Intended test wiring

1. Load this scenario (Section 3) into the test database (Testcontainers
   PostgreSQL), on top of the Flyway reference data (Section 4).
2. Run `TransactionReportJob` with `startDate=2025-01-01` and
   `endDate=2025-01-31`.
3. Capture the produced report and assert it equals `expected-report.txt`:
   - **line-by-line**, asserting **each line length == 133**, and
   - **full-file byte equality** (the whole 2010-byte payload).
4. Compare amounts as **fixed-width text** (already scale-2 rendered) — **never**
   as floating point.

### Fidelity rules

- **Byte-exact 133-character records**, `LF` line endings, trailing newline.
- **Monetary edit PICs reproduced exactly** — detail amounts use
  `-ZZZ,ZZZ,ZZZ.ZZ` and totals use `+ZZZ,ZZZ,ZZZ.ZZ`
  (`legacy/cpy/CVTRA07Y.cpy`). This includes:
  - the **all-zero → blank** amount for the `0.00` detail (record 12 / `B3`
    renders an **empty** amount field, not `0.00`);
  - the leading sign position (`-` for the negative detail amounts, `+` for the
    positive totals);
  - thousands separators inserted only within significant digits.
- **No secrets** appear in the report (no CVV, no passwords, no full PANs beyond
  the synthetic scenario IDs used for wiring).

## 8. Consistency note

This `README.md`, the seed CSV rows (`src/test/resources/seed/**`), and the golden
output (`expected-report.txt`) are **one coupled contract**. If the scenario
documented here and `expected-report.txt` ever diverge, they **must be reconciled
together**: change the inputs here, then **regenerate the golden file** from those
inputs (and update the MD5 / size in Section 7). Never edit one side in isolation.

## Validation checklist

- [x] All six transactions, both accounts / cards / customers / xrefs, and the
      date range are documented exactly as in `expected-report.txt`.
- [x] FK-safe authoring order is stated (`customer` → `account` → `card` →
      `card_xref` → `transaction`; reference tables are global via Flyway `V2`).
- [x] CSV header conventions match the destination loader (`transaction` 13-column
      header with short names; `card_xref` = `xref_card_num,xref_cust_id,xref_acct_id`;
      `cat_cd` a plain integer; `type_cd` / ids quoted strings).
- [x] The three quirks are documented, with the `0.00`-last-row mitigation called
      out.
- [x] Totals arithmetic shown (Account A = `1,484.56`; Page = Grand = `559.81`).
- [x] Markdown renders cleanly (tables well-formed); no secrets (no CVV, no
      passwords).
- [x] References cite `app/cbl/CBTRN03C.cbl` (lines 173-174 for the `proc_ts`
      filter; now at `legacy/cbl/CBTRN03C.cbl`) and `app/cpy/CVTRA07Y.cpy` (now at
      `legacy/cpy/CVTRA07Y.cpy`) for the 133-character record layout.

