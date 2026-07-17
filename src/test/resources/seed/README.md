# CardDemo test seed fixtures (`seed/`)

This tree holds the **test-owned INPUT fixtures** consumed by the unit,
Testcontainers-integration, and golden-file parity tests under
[`src/test/java/com/aws/carddemo/**`](../../java/com/aws/carddemo). It is the
**INPUT half** of the parity contract; the sibling
[`src/test/resources/golden/`](../golden) tree holds the **EXPECTED-OUTPUT
half**. Every golden fixture is computed by applying the preserved COBOL logic
(relocated read-only under [`legacy/cbl/`](../../../../legacy/cbl)) to the
inputs in this folder, so a reviewer can read an input here, read its paired
golden output, and confirm the transformation matches the legacy behaviour
without running the mainframe.

This document exists so that each fixture is **independently reviewable**
(AAP §0.1.1) and fully **explained** (AAP §0.9.4): from this file alone a
reviewer can tell what each fixture is, where it came from, how to decode it,
which test consumes it, and the exact expected outcome.

> **Why `seed/` and not `db/seed/`?** The path prefix is `seed/` (**not**
> `db/seed/`) deliberately. The production bulk seed lives on the classpath at
> [`src/main/resources/db/seed/`](../../../main/resources/db/seed) and is loaded
> as `classpath:db/seed/*.csv`. Placing these test fixtures under a distinct
> `seed/` prefix avoids a classpath collision between the two trees: a test
> resource and a production resource never resolve the same `db/seed/...` path.

## Provenance

Every fixture here derives from the legacy artifacts that were relocated
read-only during the migration:

| Source of truth | Location (was `app/…`) | Role |
|---|---|---|
| Fixed-width ASCII datasets | [`legacy/data/ASCII/`](../../../../legacy/data/ASCII) (was `app/data/ASCII/`) | The raw data the fixtures are sampled from or derived from. |
| Byte-exact record layouts (copybooks) | [`legacy/cpy/`](../../../../legacy/cpy) (was `app/cpy/`) | Define how to decode each fixed-width record: field name, offset, length, and PIC type. |
| Behavioural contracts (COBOL programs) | [`legacy/cbl/`](../../../../legacy/cbl) (was `app/cbl/`) | Define how the batch/online programs read and process the data. |

The programs whose behaviour these fixtures exercise are `CBTRN02C`
(daily-transaction posting and reject-code assignment), `CBTRN01C`
(daily-transaction validation), `CBACT04C` (interest calculation), and
`COTRN02C` (online transaction add).

## Relationship to `src/main/resources/db/seed` — reuse vs. isolation

The tests use a **two-tier** data strategy. Knowing which tier a test belongs to
explains why some data is deliberately *not* duplicated here.

**Tier 1 — baseline / full-seed tests reuse the production bulk fixtures.**
Repository CRUD tests and the master-print batch jobs load the production bulk
seed directly from the classpath
([`classpath:db/seed/*.csv`](../../../main/resources/db/seed)) — customer 50,
account 50, card 50, card_xref 50, tran_cat_balance 50, user_security 10,
transaction 300, daily_transaction 300 — together with the reference data
inserted by Flyway
[`V2__reference_data.sql`](../../../main/resources/db/migration/V2__reference_data.sql).
Those datasets are **not duplicated** in this folder.

**Tier 2 — scenario parity tests use the isolated fixtures in this tree.** The
posting reject-code and interest-fidelity parity tests use the
**self-contained** fixtures in [`posting/`](posting) and [`interest/`](interest).
Isolation is required for a deterministic golden comparison: the posting job
reads the **entire** `daily_transaction` table and the interest job reads the
**entire** `tran_cat_balance` table, so a repeatable row-for-row golden
assertion must load **only** the scenario's crafted rows for those mutable
tables — never the full 300-/50-row production seed.

These scenario fixtures use **distinct high identifiers** that never collide
with the base seed (whose IDs are `1`–`50`):

| Entity | Scenario ID pattern | Example |
|---|---|---|
| customer | `9000000xx` | `900000001`, `900000010` |
| account | `900000000xx` | `90000000001`, `90000000010` |
| card | `90000000000000xx` | `9000000000000001` |

Because the identifiers never overlap with the base seed, a test may either
**truncate-and-load** just the scenario rows or **load additively** on top of
the base seed. The static reference tables (`transaction_type`,
`transaction_category`, `disclosure_group`) are **always present** via Flyway
`V2` and are therefore **not re-emitted** in these scenario folders.

## Directory index

| Path | What it is | Consumed by |
|---|---|---|
| [`dailytran-fixedwidth-sample.txt`](dailytran-fixedwidth-sample.txt) | Byte-exact first 10 records (350 B each + LF, 3510 B) of [`legacy/data/ASCII/dailytran.txt`](../../../../legacy/data/ASCII/dailytran.txt) | `FixedWidthCodec` + `CombinedTransactionItemReader` decode / round-trip unit tests; §0.9.6 external-contract parity |
| [`posting/`](posting) | Isolated `CBTRN02C` posting + reject-code scenario (6 CSVs) | `DailyTransactionPostingJob` integration + golden parity tests |
| [`interest/`](interest) | Isolated `CBACT04C` interest-calculation scenario (5 CSVs) | `InterestCalculationJob` integration + golden parity tests |

## Decode and format rules

These rules are mandatory; they mirror AAP §0.6.4 and §0.9 and define exactly
how to read the fixed-width sample and how the CSV fixtures must be shaped so
they load cleanly against the Testcontainers **PostgreSQL 16** schema created by
Flyway.

### Fixed-width decoding

Field offsets are **0-based**; **slice first, then trim** trailing pad spaces on
text fields. The `dailytran` record length is **exactly 350 bytes**. The full
field map, from
[`legacy/cpy/CVTRA06Y.cpy`](../../../../legacy/cpy/CVTRA06Y.cpy), is:

| Field (CSV column) | Offset `[start:end)` | Length | PIC |
|---|---|---|---|
| `dalytran_id` | `[0:16]` | 16 | `X(16)` |
| `type_cd` | `[16:18]` | 2 | `X(02)` |
| `cat_cd` | `[18:22]` | 4 | `9(04)` |
| `source` | `[22:32]` | 10 | `X(10)` |
| `description` | `[32:132]` | 100 | `X(100)` |
| `amount` | `[132:143]` | 11 | `S9(09)V99` |
| `merchant_id` | `[143:152]` | 9 | `9(09)` |
| `merchant_name` | `[152:202]` | 50 | `X(50)` |
| `merchant_city` | `[202:252]` | 50 | `X(50)` |
| `merchant_zip` | `[252:262]` | 10 | `X(10)` |
| `card_num` | `[262:278]` | 16 | `X(16)` |
| `orig_ts` | `[278:304]` | 26 | `X(26)` |
| `proc_ts` | `[304:330]` | 26 | `X(26)` |
| *(filler)* | `[330:350]` | 20 | `X(20)` |

Signed money uses COBOL zoned-decimal **OVERPUNCH on the last byte**: the final
byte of a signed numeric field encodes both the last digit and the sign.

| Last byte | `{` | `A` | `B` | `C` | `D` | `E` | `F` | `G` | `H` | `I` |
|---|---|---|---|---|---|---|---|---|---|---|
| **Positive** digit | +0 | +1 | +2 | +3 | +4 | +5 | +6 | +7 | +8 | +9 |

| Last byte | `}` | `J` | `K` | `L` | `M` | `N` | `O` | `P` | `Q` | `R` |
|---|---|---|---|---|---|---|---|---|---|---|
| **Negative** digit | -0 | -1 | -2 | -3 | -4 | -5 | -6 | -7 | -8 | -9 |

Decode the `amount` field to a **scale-2 decimal** (implied `V99`: the last two
digits are the cents). Two verified examples taken from
[`dailytran-fixedwidth-sample.txt`](dailytran-fixedwidth-sample.txt):

- record 1 `amount` = `0000005047G` → **`504.77`** — `G` = +7, so the digit
  string is `00000050477` (positive) and `V99` places the decimal to give
  `504.77`.
- record 2 `amount` = `0000009190}` → **`-919.00`** — `}` = -0, so the digit
  string is `00000091900` (negative) and `V99` gives `-919.00`.

### CSV and value rules

- **Money is a scale-2 decimal only — never floating point.** `double` / `float`
  are prohibited for decimal values (AAP §0.6.4).
- `dalytran_id`, `tran_id`, and `card_num` are **strings with leading zeros
  preserved**; quote them in the CSV so a loader cannot coerce them to numbers
  and silently drop the leading zeros.
- `orig_ts` is kept as `YYYY-MM-DD HH:MM:SS.ffffff`. `proc_ts` is blank in the
  source (26 spaces) and therefore maps to empty / `NULL`.
- **CSV headers and columns must match** the sibling
  [`V1__schema.sql`](../../../main/resources/db/migration/V1__schema.sql) tables
  and the [`src/main/resources/db/seed/*.csv`](../../../main/resources/db/seed)
  headers so the fixtures load cleanly through the single, header-driven loader
  against the Flyway-created schema. **Authoring rule:** read the matching
  `db/seed/<name>.csv` header at authoring time and reproduce it
  **byte-for-byte**. For reference, the headers these scenarios reuse are:

  ```
  daily_transaction : dalytran_id,type_cd,cat_cd,source,description,amount,merchant_id,merchant_name,merchant_city,merchant_zip,card_num,orig_ts,proc_ts
  tran_cat_balance  : acct_id,type_cd,cat_cd,bal
  account           : acct_id,active_status,curr_bal,credit_limit,cash_credit_limit,open_date,expiration_date,reissue_date,curr_cyc_credit,curr_cyc_debit,addr_zip,group_id
  customer          : cust_id,first_name,middle_name,last_name,addr_line_1,addr_line_2,addr_line_3,state_cd,country_cd,addr_zip,phone_num_1,phone_num_2,ssn,govt_issued_id,dob,eft_account_id,pri_card_holder_ind,fico_credit_score
  card              : card_num,acct_id,cvv_cd,embossed_name,expiration_date,active_status
  card_xref         : xref_card_num,xref_cust_id,xref_acct_id
  ```

- Files are **UTF-8** with **LF** line endings; use **RFC 4180** quoting for any
  text field that contains a comma.
- **No hardcoded secrets.** Any card CVV or user password appearing in a fixture
  is a **synthetic demo value only**; such values are never logged or echoed
  (enforced in the application code, not here).

## Posting scenario (`posting/`)

The [`posting/`](posting) fixtures exercise `CBTRN02C` posting and its reject
codes. `CBTRN02C` (`1500-VALIDATE-TRAN`) validates each daily-transaction record
in this **exact order**, and the outcome depends on that order:

1. **Cross-reference (card) lookup** (`1500-A-LOOKUP-XREF`). If the card is not
   in `card_xref`, the record is rejected **100** and the account lookup is
   **skipped entirely** (short-circuit) — a 100 record never receives
   101/102/103.
2. **Account lookup** (`1500-B-LOOKUP-ACCT`), only if the cross-reference
   succeeded. A missing account yields **101**.
3. When the account is found, `WS-TEMP-BAL = curr_cyc_credit - curr_cyc_debit +
   amount` is computed and **two separate `IF` checks** run:
   - credit-limit check → **102** when `credit_limit >= WS-TEMP-BAL` is false;
   - expiration check → **103** when `expiration_date >= orig_date` is false.

> **102 and 103 are separate `IF` statements, not an `IF/ELSE`.** Both checks
> run whenever the account is found, and both write the same reason field in
> sequence, so a record that fails **both** ends up tagged **103** (the later
> write overwrites 102). To isolate a **102** the crafted row must be
> **non-expired**; to isolate a **103** it must be **under the limit**. The
> fixtures are built accordingly.

The 4-row `daily_transaction` feed (read in file / CSV row order) and its
expected outcome:

| Feed row | `dalytran_id` | `card_num` | amount | orig date | Expected result |
|---|---|---|---|---|---|
| 1 | `9990000000000001` | `9000000000000001` | `100.00` | `2025-01-15` | **VALID → posted** — `WS-TEMP-BAL` 100 ≤ limit 5000 and not expired; updates `tran_cat_balance (90000000001, 01, 1)` → `100.00` and account `90000000001` |
| 2 | `9990000000000002` | `9999999999999999` | `50.00` | `2025-01-15` | **REJECT 100** — card not in `card_xref` |
| 3 | `9990000000000003` | `9000000000000002` | `500.00` | `2025-01-15` | **REJECT 102** — `WS-TEMP-BAL` 500 > `credit_limit` 100 (account not expired) |
| 4 | `9990000000000004` | `9000000000000003` | `10.00` | `2025-01-15` | **REJECT 103** — orig date > account expiration `2000-01-01` (amount ≤ limit, so not 102) |

Three of the four records are rejected (100, 102, 103) and one posts. The
account rows that make these outcomes deterministic are in
[`posting/account.csv`](posting/account.csv): `90000000001` has `credit_limit`
`5000.00` and expiration `2099-12-31` (row 1 posts); `90000000002` has
`credit_limit` `100.00` and expiration `2099-12-31` (row 3 is over-limit but not
expired → 102); `90000000003` has `credit_limit` `5000.00` and expiration
`2000-01-01` (row 4 is under-limit but expired → 103).

### Reject code 101 (account not found) is unit-test-only

Reject **101** is **not** reachable from an integration fixture. In the
relational target, `card_xref.acct_id` (column `xref_acct_id`) is a **NOT NULL
foreign key** to `account`, so a cross-reference row that points at a missing
account **cannot be inserted** — it fails the FK constraint. Because 101 only
fires when a cross-reference resolves to an **absent** account (a state that is
impossible to load), it is exercised by a `PostingService` **unit test** with a
mocked account repository that returns empty — **not** by an integration
fixture.

This follows directly from the referential-integrity formalization
(AAP §0.4.3, hotspot **H6**): relationships the COBOL enforced only in
application code are now real foreign keys. The decision is recorded in
[`docs/decision-log.md`](../../../../docs/decision-log.md).

## Interest scenario (`interest/`)

The [`interest/`](interest) fixtures exercise `CBACT04C` interest calculation.
`CBACT04C` reads `tran_cat_balance` sequentially in primary-key order, looks up
the applicable rate in `disclosure_group` by `(account.group_id, type_cd,
cat_cd)`, and for each **non-zero** rate computes `monthlyInterest = balance ×
rate / 1200` — the COBOL `COMPUTE` at
[`CBACT04C`](../../../../legacy/cbl/CBACT04C.cbl) L464–465:

```
monthlyInterest = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

It writes **one** interest transaction per non-zero-rate category (`type_cd =
'01'`, `cat_cd = '05'`, source `System`, description `Int. for a/c <acctId>`,
`card_num` = the account's cross-reference card). **Zero-rate categories are
skipped** (`IF DIS-INT-RATE NOT = 0`) and produce no transaction. At end of
account (`1050-UPDATE-ACCOUNT`) the total interest is added to `curr_bal` and the
cycle credit/debit fields are zeroed.

The scenario account is `90000000010` (`group_id` = `DEFAULT`; the `DEFAULT`
rates below are exactly the ones seeded by Flyway `V2`):

| `tran_cat_balance` row | balance | `DEFAULT` rate | monthly interest | interest txn written? |
|---|---|---|---|---|
| `(90000000010, 01, 1)` | `1000.00` | `15.00` | **`12.50`** | yes |
| `(90000000010, 01, 2)` | `1200.00` | `25.00` | **`25.00`** | yes |
| `(90000000010, 01, 3)` | `0.24` | `25.00` | **`0.01`** (HALF_UP; see note) | yes |
| `(90000000010, 02, 1)` | `500.00` | `0.00` | `0.00` | no (zero-rate skip) |

**Expected:** **3** interest transactions; total monthly interest **`37.51`**
(HALF_UP); account end state `curr_bal = 37.51`, with cycle credit/debit reset
to `0.00`.

### Rounding-divergence note (Java `HALF_UP` is authoritative)

The COBOL `COMPUTE` at [`CBACT04C`](../../../../legacy/cbl/CBACT04C.cbl)
L464–465 has **no `ROUNDED`** clause, so it **truncates**: for `(01, 3)`,
`0.24 × 25.00 / 1200 = 0.005` truncates to `0.00`, making the legacy total
`37.50`. The Java target computes the same expression with
`RoundingMode.HALF_UP` (AAP §0.4.2 / §0.7.1-H3), so `0.005` rounds to `0.01`,
making the total `37.51`. **The Java HALF_UP value is the authoritative expected
result**; the COBOL truncation difference is an **intentional, documented
divergence**, flagged for
[`docs/decision-log.md`](../../../../docs/decision-log.md) and encoded in the
paired [`golden/`](../golden) fixtures.

## Relationship to the golden fixtures

The sibling [`src/test/resources/golden/`](../golden) tree **depends on this
folder**: each expected-output fixture there is produced by applying the COBOL
logic described above to the inputs here. The dependency is one-way — **inputs
first, expected outputs second**. Consequently, **any change to a fixture in
this folder requires regenerating the paired `golden/` output(s)** from the
re-verified scenario, or the parity tests will (correctly) fail.
