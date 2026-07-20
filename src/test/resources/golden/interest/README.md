# Golden fixtures — InterestCalculationJob (CBACT04C)

This folder holds the **golden** (expected-output) fixtures that the
`InterestCalculationJob` parity test asserts against. `InterestCalculationJob`
is the Java / Spring Batch migration of the legacy COBOL interest program
[`legacy/cbl/CBACT04C.cbl`](../../../../../legacy/cbl/CBACT04C.cbl). The
**input** half of this scenario lives in the sibling folder
[`src/test/resources/seed/interest/`](../../seed/interest); the parity test runs
the job over those seed inputs and compares the produced database state against
the fixtures documented here.

## Files in this folder

| File | Role |
|---|---|
| `expected-interest-transactions.csv` | The **3** expected interest `transaction` rows (stable columns only). |
| `expected-account.csv` | The expected **end-state** of the `account` row for account `90000000010` after the job runs. |
| `README.md` | This file. |

## Scenario

- Account `90000000010`, disclosure `group_id = DEFAULT`.
- Inputs come from [`src/test/resources/seed/interest/`](../../seed/interest):
  four `tran_cat_balance` rows for the account, with the applicable interest
  rates taken from the `disclosure_group` `DEFAULT` group.
- The expected outputs are **derived by applying the preserved COBOL interest
  logic** in [`legacy/cbl/CBACT04C.cbl`](../../../../../legacy/cbl/CBACT04C.cbl)
  to those inputs — no running mainframe is required.

The four seed `tran_cat_balance` rows and their `DEFAULT` rates are:

| `tran_cat_balance` (acct, type_cd, cat_cd) | bal | `DEFAULT` rate |
|---|---|---|
| (90000000010, 01, 1) | 1000.00 | 15.00 |
| (90000000010, 01, 2) | 1200.00 | 25.00 |
| (90000000010, 01, 3) | 0.24 | 25.00 |
| (90000000010, 02, 1) | 500.00 | 0.00 |

## The interest formula (preserved EXACTLY)

The monthly-interest computation is preserved verbatim from `CBACT04C`
paragraph `1300-COMPUTE-INTEREST`
([`legacy/cbl/CBACT04C.cbl` L464-465](../../../../../legacy/cbl/CBACT04C.cbl)):

```
monthlyInterest = (TRAN-CAT-BAL × DIS-INT-RATE) / 1200
```

The Java target computes this with `BigDecimal` at **scale 2** and
**`RoundingMode.HALF_UP`**, asserted **to the cent** — never floating point.

## Expected results

Applying the formula to each seed `tran_cat_balance` row (interest is computed
only when the `DEFAULT` rate is non-zero):

| `tran_cat_balance` (acct, type_cd, cat_cd) | bal | `DEFAULT` rate | (bal × rate) / 1200 raw | HALF_UP | interest txn? |
|---|---|---|---|---|---|
| (90000000010, 01, 1) | 1000.00 | 15.00 | 12.500 | 12.50 | yes |
| (90000000010, 01, 2) | 1200.00 | 25.00 | 25.000 | 25.00 | yes |
| (90000000010, 01, 3) | 0.24 | 25.00 | 0.005 | 0.01 | yes |
| (90000000010, 02, 1) | 500.00 | 0.00 | 0.000 | — | no (rate 0 ⇒ skipped) |

This yields **3 interest transactions (`12.50`, `25.00`, `0.01`), total
`37.51`**. After the run the account ends with:

- `curr_bal = 37.51` — the accumulated total interest is added to the current
  balance (`1050-UPDATE-ACCOUNT`, `ADD WS-TOTAL-INT TO ACCT-CURR-BAL`, L352).
- `curr_cyc_credit = 0.00` and `curr_cyc_debit = 0.00` — both cycle fields are
  **explicitly zeroed** by `1050-UPDATE-ACCOUNT` (`MOVE 0 TO
  ACCT-CURR-CYC-CREDIT` / `ACCT-CURR-CYC-DEBIT`, L352-354).

Two behaviors are worth calling out:

- The rate-0 category `(90000000010, 02, 1)` is **skipped**: the main loop
  guards the interest computation with `IF DIS-INT-RATE NOT = 0` (L214), so a
  zero-rate category produces **no** interest transaction.
- `1400-COMPUTE-FEES` (L518-520) is a **no-op stub** (`To be implemented` +
  `EXIT`), so **no fee transactions** are produced.

Every generated interest transaction is written by `1300-B-WRITE-TX` (L473) with
the same fixed field values — `type_cd = '01'`, `cat_cd = '05'` (the interest
posting category, stored as INTEGER `5`), `tran_source = 'System'`,
`tran_desc = 'Int. for a/c 90000000010'`, `card_num = 9000000000000010`, and
`tran_merchant_id = 0` — differing only in `tran_amt`.

## CRITICAL — documented COBOL-vs-Java rounding divergence

**This is the load-bearing reason this README exists.** The third amount
(`0.01`) and the total (`37.51`) are **not** a bit-for-bit reproduction of the
legacy COBOL result; they are an **intentional, documented improvement**. A
reviewer must read them as such — never as a parity regression.

- **Legacy COBOL truncates.** `1300-COMPUTE-INTEREST` runs
  `COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` into
  `WS-MONTHLY-INT PIC S9(09)V99` **without the `ROUNDED` phrase**. A COBOL
  `COMPUTE` with no `ROUNDED` **truncates** the intermediate result, so category
  `(01, 3)` computes `0.24 × 25.00 / 1200 = 0.005`, which COBOL truncates to
  `0.00`. The COBOL total is therefore **`37.50`**.
- **The migration applies `HALF_UP`.** Per the AAP construct-transformation rule
  (`COMP-3` → `BigDecimal` scale 2 + `RoundingMode`; AAP §0.2.2 and §0.4.2), the
  Java target rounds the same expression with **`RoundingMode.HALF_UP`**, so
  `0.005` rounds to **`0.01`** and the total becomes **`37.51`**.
- **The Java `HALF_UP` values (`0.01` / `37.51`) are AUTHORITATIVE for these
  golden fixtures.** The parity test asserts the `HALF_UP` result; the COBOL
  truncated values (`0.00` / `37.50`) are shown here **only** as the
  legacy-behavior contrast.

> **See [`docs/decision-log.md`](../../../../../docs/decision-log.md)** — the
> entry **D31, "Interest rounding uses HALF_UP (documented divergence from COBOL
> truncation)"** (and the related **D9, "`BigDecimal` scale-2 `Money` value
> object"**) — for the full rationale, the alternatives considered, and the risk
> assessment of this deviation, including the `0.005 → 0.01` boundary case and
> the `37.51` run total. This cross-reference makes the deviation traceable in
> both directions, as the Explainability rule requires (AAP §0.8.2).

## Comparison policy (what the parity test asserts)

- **Stable fields (asserted).** For the interest transactions the test compares
  `type_cd`, `cat_cd`, `tran_source`, `tran_desc`, `tran_amt`, `card_num`, and
  `tran_merchant_id`. For the account it compares the business columns of the
  row (`acct_id`, `acct_active_status`, `curr_bal`, `credit_limit`,
  `cash_credit_limit`, `acct_open_date`, `acct_expiration_date`,
  `acct_reissue_date`, `curr_cyc_credit`, `curr_cyc_debit`, `acct_addr_zip`,
  `group_id`).
- **Volatile fields (EXCLUDED from equality).** These depend on the run clock or
  run parameters and are **not** asserted for value:
  - `tran_id` — COBOL builds it as `STRING PARM-DATE + WS-TRANID-SUFFIX`, so it
    is run-derived.
  - `orig_ts` and `proc_ts` — run-derived DB2-format timestamps set at write
    time.
  - the account `version` column — the JPA `@Version` optimistic-lock counter,
    an integrity mechanism rather than a parity field.

  These columns are deliberately **omitted from both CSV fixtures**.
- **Multiset comparison.** All three interest rows share **identical** stable
  fields except `tran_amt` (they all carry `type_cd = 01`, `cat_cd = 5`,
  `tran_source = System`, the same `tran_desc`, `card_num`, and
  `tran_merchant_id`). The test therefore **cannot** disambiguate the rows by
  `type_cd` / `cat_cd` alone; it compares them as an **order-independent
  multiset of `tran_amt`**, sorted ascending: `0.01, 12.50, 25.00`.

## Canonical column names

The CSV fixtures in this folder use the **canonical** `transaction` / `account`
column names defined in
[`V1__schema.sql`](../../../../main/resources/db/migration/V1__schema.sql) — for
example `tran_amt`, `tran_source`, `tran_desc` on `transaction`, and `curr_bal`,
`curr_cyc_credit`, `curr_cyc_debit` on `account`. They do **not** use the
shorter seed header names (`amount`, `source`, `description`, `active_status`, …)
found under [`src/test/resources/seed/interest/`](../../seed/interest), which
belong to the input side of the contract.

Two column types matter when reading the fixtures:

- `cat_cd` is an **INTEGER** — the interest posting category is written as `5`
  (from COBOL `MOVE '05' TO TRAN-CAT-CD`, `TRAN-CAT-CD PIC 9(04)`).
- `type_cd` is **text** — the interest transaction type is `01`
  (`TRAN-TYPE-CD PIC X(02)`).

## Fidelity rules

These rules define what "equal" means for this golden comparison and must not be
relaxed to make a test pass (AAP §0.7.1 hotspot H3, §0.9):

- **Scale-2 decimals, to the cent, never floating point.** Every monetary value
  is a `BigDecimal` at scale 2 and is compared exactly to the cent; `double` /
  `float` are prohibited for decimal values.
- **Interest matches the `HALF_UP` formula exactly.** Each amount equals
  `(bal × rate) / 1200` computed with `RoundingMode.HALF_UP` at scale 2 (see the
  divergence note above — the `HALF_UP` result is authoritative).
- **Rate-0 categories produce no transaction.** A category whose `DEFAULT` rate
  is `0.00` is skipped and generates no interest row.
- **Volatile fields are excluded.** `tran_id`, `orig_ts`, `proc_ts`, and the
  account `version` column are not asserted for value.
- **No secrets.** No card verification value (CVV) and no password ever appears
  in these golden outputs. Card numbers are limited to the synthetic demo
  identifier (`9000000000000010`) already used by the `seed/interest/` scenario.
