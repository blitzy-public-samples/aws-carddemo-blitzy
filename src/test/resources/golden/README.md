# Golden output fixtures (`src/test/resources/golden/`)

This folder holds the **golden** (expected-output) fixtures for the batch
**row-for-row / byte-for-byte parity tests**. Each fixture is the deterministic
result of applying the preserved COBOL batch logic - relocated read-only under
[`legacy/cbl/`](../../../../legacy/cbl) - to the input fixtures in the sibling
[`seed/`](../seed) folder. The parity tests under
`src/test/java/com/aws/carddemo/**` run each Spring Batch job over the `seed/`
inputs and assert that the **actual** output equals the **golden** fixture here,
to the byte for fixed-width file outputs and to the cent for database state.

These fixtures are the human entry point that makes each batch job independently
reviewable: a reviewer can read the seed inputs, read the golden outputs, and
confirm that the transformation between them matches the legacy behavior without
running the mainframe.

## Parity contract: `seed/` is the input half, `golden/` is the expected-output half

| Half | Folder | Role |
|---|---|---|
| Input | [`seed/`](../seed) | Controlled input datasets fed to the Spring Batch jobs (daily-transaction feeds, account/card/xref/reference rows). Authored first; it is the declared dependency of this folder. |
| Expected output | `golden/` (this folder) | The expected result of running the preserved COBOL logic over the matching `seed/` scenario. |

A parity test therefore reads a `seed/` scenario, runs the corresponding job,
and compares the produced artifact against the fixture in the matching `golden/`
subfolder. If the two diverge, either the migration drifted from the legacy
behavior (a regression) or the fixtures are stale and must be regenerated from a
re-verified scenario.

## Subfolder index

| Subfolder | Batch job | Legacy program | Expected artifact |
|---|---|---|---|
| `posting/` | `DailyTransactionPostingJob` | `CBTRN02C` | Posted `transaction` row(s) and updated `tran_cat_balance` / `account` balances (DB state). |
| `reject/` | `DailyTransactionPostingJob` | `CBTRN02C` | Fixed-width posting reject records (reason codes 100/101/102/103). |
| `interest/` | `InterestCalculationJob` | `CBACT04C` | Interest transactions and updated account balance (DB state). |
| `statement/` | `StatementGenerationJob` | `CBSTM03A` + `CBSTM03B` | Two parallel files: plain-text (80-char) and HTML (100-char). |
| `report/` | `TransactionReportJob` | `CBTRN03C` | Transaction detail report (133-char print lines). |

### `posting/`

Expected database state after `DailyTransactionPostingJob` posts the
`seed/posting/` daily-transaction feed. The posting balance rules preserved from
`CBTRN02C` are:

- `2800-UPDATE-ACCOUNT-REC` adds `DALYTRAN-AMT` to `ACCT-CURR-BAL`, then adds the
  amount to `ACCT-CURR-CYC-CREDIT` when the amount is `>= 0`, otherwise to
  `ACCT-CURR-CYC-DEBIT`.
- `2700-UPDATE-TCATBAL` adds the amount to the matching `TRAN-CAT-BAL`.

Expected values for the isolated `seed/posting/` valid record:

| Table | Key | Field | Expected value |
|---|---|---|---|
| `transaction` | `tran_id = 9990000000000001` | `tran_amt` | `100.00` |
| `tran_cat_balance` | `(acct_id 90000000001, type_cd '01', cat_cd 1)` | `bal` | `100.00` |
| `account` | `acct_id 90000000001` | `curr_bal` | `100.00` |
| `account` | `acct_id 90000000001` | `curr_cyc_credit` | `100.00` |
| `account` | `acct_id 90000000001` | `curr_cyc_debit` | `0.00` |

### `reject/`

Expected posting reject records emitted by `CBTRN02C` when a daily-transaction
record fails validation. Each reject is a **430-byte** record: a 350-byte
verbatim copy of the rejected `DALYTRAN` record followed by an 80-byte trailer
(a 4-digit reason code plus a 76-character description). The reason codes are
produced in the **exact COBOL evaluation order** (see the fidelity rules below).

Reject scenario derived from `seed/posting/`:

| Daily-transaction card (synthetic, from `seed/posting/`) | Reason code | Meaning | COBOL trigger |
|---|---|---|---|
| `9999999999999999` | **100** | card cross-reference not found | `1500-A-LOOKUP-XREF` INVALID KEY |
| `...0002` | **102** | over the credit limit | `ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` is false |
| `...0003` | **103** | transaction received after account expiration | `ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10)` is false |

Reason **101** (account record not found) is exercised by its own targeted
scenario. For the `seed/posting/` scenario above, the expected **reject count is
3** and the batch **return code is 4** (records were rejected but the job
completed).

### `interest/`

Expected interest transactions and balances from `InterestCalculationJob`. The
monthly interest formula preserved from `CBACT04C` (`1300-COMPUTE-INTEREST`) is:

```
monthlyInterest = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

computed with `BigDecimal` at scale 2 and `RoundingMode.HALF_UP`. Interest is
computed only for categories whose disclosure interest rate is non-zero
(`IF DIS-INT-RATE NOT = 0`); a rate-0 category produces **no** interest
transaction.

Expected values for the isolated `seed/interest/` scenario:

| Interest transaction | Expected amount |
|---|---|
| first non-zero-rate category | `12.50` |
| second non-zero-rate category | `25.00` |
| category `(01, 3)` (the rounding-boundary case) | `0.01` |
| **total interest** | **`37.51`** |

After the run the account ends with `curr_bal = 37.51` (the accumulated interest
is added to the current balance) and the cycle credit/debit fields zeroed. See
the interest rounding divergence note below for why the third amount is `0.01`
and the total is `37.51` rather than `0.00` / `37.50`.

### `statement/`

Expected statement outputs from `StatementGenerationJob` (`CBSTM03A` driving the
`CBSTM03B` file subprogram). Two parallel files are produced with transactions
grouped by card:

- a plain-text statement of **80-character** records (`FD-STMTFILE-REC PIC X(80)`), and
- an HTML statement of **100-character** records (`FD-HTMLFILE-REC PIC X(100)`).

### `report/`

Expected transaction detail report from `TransactionReportJob` (`CBTRN03C`):
**133-character** print records (`FD-REPTFILE-REC PIC X(133)`) with **20 detail
lines per page**, page headers, one detail line per transaction, and
page / account / grand totals. The report is filtered to the input date range.

## Byte-exact record lengths

Fixed-width file outputs are external interface contracts and must preserve the
**exact column positions and record lengths** below. A byte-count mismatch is a
parity failure regardless of content.

| Golden output | Record length | COBOL FD | Source program |
|---|---|---|---|
| statement (text) | 80 bytes | `FD-STMTFILE-REC PIC X(80)` | `legacy/cbl/CBSTM03A.CBL` |
| statement (HTML) | 100 bytes | `FD-HTMLFILE-REC PIC X(100)` | `legacy/cbl/CBSTM03A.CBL` |
| report | 133 bytes | `FD-REPTFILE-REC PIC X(133)` | `legacy/cbl/CBTRN03C.cbl` |
| posting reject | 430 bytes | `FD-REJS-RECORD` = `FD-REJECT-RECORD X(350)` + `FD-VALIDATION-TRAILER X(80)` | `legacy/cbl/CBTRN02C.cbl` |
| posted transaction | 350 bytes (or DB-state CSV) | `TRAN-RECORD` (CVTRA05Y) | `legacy/cbl/CBTRN02C.cbl` |

**Fixed-width vs. DB-state fixtures.** The reject, statement, and report golden
files are fixed-width and are compared byte-for-byte at the lengths above.
Posting and interest outcomes are expressed as **database state** and are
captured as CSV fixtures. DB-state CSV fixtures **must use the canonical
PostgreSQL column names** from
[`V1__schema.sql`](../../../main/resources/db/migration/V1__schema.sql) - for
example `tran_amt`, `tran_source`, `tran_desc`, `orig_ts`, `proc_ts` on
`transaction`; `curr_bal`, `curr_cyc_credit`, `curr_cyc_debit` on `account`; and
`bal` on `tran_cat_balance`. Do **not** use the shorter `seed/` CSV header names
(such as `amount`, `source`, `description`), which belong to the input side of
the contract and differ from the schema column names.

## Fidelity rules

These rules are load-bearing. They define what "equal" means for a golden
comparison and must not be relaxed to make a test pass.

### 1. Monetary values are scale-2 decimals, compared to the cent - never floating point

Every monetary field is a `BigDecimal` at scale 2 and is compared exactly to the
cent. Floating-point types (`double` / `float`) are prohibited for decimal
values. Interest matches the `CBACT04C` formula above.

### 2. Interest rounding divergence: COBOL truncation vs. Java `HALF_UP` (Java is authoritative)

The legacy `CBACT04C` computation uses `PIC S9(9)V99` intermediate fields
(`WS-MONTHLY-INT`, `WS-TOTAL-INT`) with **no `ROUNDED` clause**, so COBOL
**truncates** the third decimal. For category `(01, 3)` a balance of `0.24` gives
`0.24 * 25.00 / 1200 = 0.005`, which COBOL truncates to `0.00` (making its total
`37.50`). The Java target computes the same expression with
`RoundingMode.HALF_UP`, so `0.005` rounds to `0.01` (making the total `37.51`).

**The Java `HALF_UP` result is authoritative for these golden fixtures**, so the
`interest/` fixtures encode `0.01` and a total of `37.51`. This is an intentional
deviation from a literal COBOL translation and is recorded in the decision log
(decision **D31**, "Interest rounding uses HALF_UP (documented divergence from
COBOL truncation)"; see also the related **D9**, "BigDecimal scale-2 Money value
object") - [`docs/decision-log.md`](../../../../docs/decision-log.md). Tests must
assert the `HALF_UP` values, not the legacy truncated values.

### 3. Reject codes are preserved with identical trigger conditions *and* evaluation order

Reordering the validations changes which code a record receives, which is a
regression. `CBTRN02C` (`1500-VALIDATE-TRAN`) enforces this order:

- **100 short-circuit** - the card cross-reference lookup runs first. If it fails
  (reason 100), the account lookup is skipped entirely; a card-not-found record
  never receives 101/102/103.
- **101** - account lookup runs only when the cross-reference succeeded; a missing
  account yields 101.
- **102 then 103 (last-writer-wins)** - when the account is found, the
  credit-limit check (102) is evaluated first and the expiration check (103)
  second. Because both write the same reason field in sequence, a record that
  fails **both** ends up with **103** (the last write overwrites 102).

Each reason path (100, 101, 102, 103) is exercised by a dedicated fixture so the
trigger conditions and their order are individually verifiable.

### 4. Volatile (run-derived) fields are excluded from equality

Fields whose value depends on the run clock or run parameters are **not**
asserted for value equality; only stable business fields are compared (amount,
`type_cd`, `cat_cd`, `card_num`, and balances). Tests must either exclude these
columns from the comparison or assert them as non-null / format-only:

| Golden area | Volatile field(s) | Why volatile |
|---|---|---|
| `posting/` | `proc_ts` | DB2-format processing timestamp set at post time. |
| `interest/` | `tran_id`, `orig_ts`, `proc_ts` | `tran_id` is `PARM-DATE` + a running suffix; `orig_ts` / `proc_ts` are set from the run clock. |

The posted-transaction `tran_id` and `orig_ts` in `posting/` are **stable**
(copied from the input `DALYTRAN` record) and *are* asserted.

### 5. No secrets

No credentials, card verification values (CVV), or passwords appear in any golden
output. Statements and reports never render the CVV, and passwords are never
emitted; this is enforced in the application code and honored here. Card numbers
are limited to the synthetic demo identifiers already used by the `seed/`
scenarios.

## Seed dependency and determinism

- `golden/` depends on `seed/` (the input half). `posting/` and `interest/`
  derive from the **isolated** scenarios `seed/posting/` and `seed/interest/`,
  which use high, collision-free identifiers so they do not interfere with the
  general seed data or with each other.
- `statement/` and `report/` do not yet have a dedicated isolated seed scenario.
  Their outputs are nonetheless fully **deterministic** given a controlled input:
  no run-timestamp appears in the statement or report body, and the report date
  range comes from the input `DATE-PARMS`, not the wall clock. These fixtures are
  therefore derived from a bounded, controlled input (for example a single
  account over a narrow date range) coordinated with the `seed/` and
  `src/test/java` authors, always preserving the byte-exact record lengths above.

Because `seed/` is the declared dependency, author or regenerate the `seed/`
scenario first, then regenerate the matching `golden/` fixture from it.

## Why there is no `validate/` folder

`DailyTransactionValidateJob` (legacy `CBTRN01C`) reads the daily-transaction
feed and performs cross-reference and account lookups, but it opens every file as
input only and produces **no distinct output file or artifact** to compare
row-for-row (its legacy form reports via `DISPLAY` only). Its behavior is
therefore covered by unit assertions rather than a golden fixture, so no
`validate/` subfolder is provided. If a dedicated validate parity test with a
concrete output artifact is later authored, add `validate/` at that time.
