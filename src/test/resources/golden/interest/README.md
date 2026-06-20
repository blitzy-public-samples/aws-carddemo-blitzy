# Golden Files — Interest Calculation (`CBACT04C` parity)

This directory holds the **expected** outputs that the modernized
`InterestCalculationService` (the Spring Batch port of the legacy COBOL program
`CBACT04C`) must produce for the curated **`interest-smoke`** scenario. The
`src/test/java` parity test runs the Java interest-calculation job against a
seeded database, then compares its **actual** output against the files stored
here to prove **100 % behavioral parity** with the COBOL — including COBOL's
**truncation-to-the-cent** arithmetic.

These files are the acceptance bar for the interest job: a meaningful parity diff
is only possible when the *expected* layout stored here matches the COBOL
record contract to the byte. When a parity test fails, this README is the
reference reviewers use to decide whether the failure is a **real regression** in
the Java code or a **golden-file authoring error**.

> **Local-only (AAP §0.6.7).** These golden files were derived once, locally, by
> translating the documented `CBACT04C` logic; **no running COBOL compiler,
> z/OS, CICS, VSAM, or mainframe environment is required** to author, regenerate,
> or run the parity tests. Every width, value, and formula below was verified
> against the read-only legacy sources retained in this repository under
> `legacy/app/` (the original `app/…` tree maps 1:1).

> **Authority:** AAP **§0.4.1** (InterestCalculationJob), **§0.6.1**
> (decimal / truncation fidelity), **§0.6.7** (golden-file parity, local-only).

### Legacy sources of truth

| Source (read-only) | What it defines |
|---|---|
| `legacy/app/cbl/CBACT04C.cbl` | `1300-COMPUTE-INTEREST` (L462–470), `1300-B-WRITE-TX` (L473–515), `1050-UPDATE-ACCOUNT` (L350–354) |
| `legacy/app/cpy/CVTRA05Y.cpy` | `TRAN-RECORD` — the 350-byte output record layout |
| `legacy/app/cpy/CVTRA02Y.cpy` | `DIS-INT-RATE PIC S9(04)V99` — disclosure-group interest rate |
| `legacy/app/cpy/CVACT01Y.cpy` | `ACCT-CURR-BAL PIC S9(10)V99` — account balance accrued into |
| `legacy/app/jcl/INTCALC.jcl` | `PARM='2022071800'` run date; `//TRANSACT DD … RECFM=F,LRECL=350` |

## Files in this directory

| File | Kind | Spec |
|---|---|---|
| `interest-smoke.interest-tran.dat` | Expected new interest `TRAN-RECORD`s | Fixed-width, **350 bytes/record**, LF-terminated; **3 records** |
| `interest-smoke.account-balances.txt` | Expected resulting `ACCT-CURR-BAL` | Pipe-delimited `ACCT-ID\|balance`, ascending `ACCT-ID`; **3 lines** |
| `README.md` | This contract / index | — |

**Naming convention:** `<case>.<kind>.<ext>`, echoing the input case name
(`interest-smoke`). `.dat` denotes a **fixed-width record file** (consistent with
the sibling `golden/reject/` `.dat` files); `.txt` denotes a **human-readable**
balances listing. Files are stored as **ASCII** with one Unix **LF (`\n`,
`0x0A`) after every record (including the last)** — **no CR (`0x0D`), no BOM** —
mirroring how the legacy `legacy/app/data/ASCII/*` fixtures are stored. The
`.dat` file is therefore `(350 + 1) × 3 = 1053` bytes.

## Interest decimal contract (truncation-critical)

`CBACT04C` `1300-COMPUTE-INTEREST` computes monthly interest **per
transaction-category balance** of an account:

```cobol
COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE ) / 1200
```

There is **no `ROUNDED` phrase**, and `WS-MONTHLY-INT` is `PIC S9(09)V99`
(scale 2), so COBOL **truncates** the result to two decimals. This is the single
most error-prone fact in this fixture set:

- **Java rule:** use `java.math.BigDecimal` with **`RoundingMode.DOWN`**
  (truncate — **never** round half-up) at **scale 2**, and keep the **`/1200`**
  monthly divisor (annual rate ÷ 12 months ÷ 100 percent). **Never use
  `float`/`double`** for any monetary or rate value.
- **Zero-rate skip:** interest is computed **only when `DIS-INT-RATE ≠ 0`**. The
  main loop gates it with `IF DIS-INT-RATE NOT = 0 … PERFORM 1300-COMPUTE-INTEREST`;
  a zero rate produces **no** interest transaction.
- **Truncation proof:** record 1 is `1234.56 × 12.00 ÷ 1200 = 12.3456`, which
  **truncates to `12.34`** — rounding would wrongly yield `12.35`.

**Balance accrual.** Within an account, each computed `WS-MONTHLY-INT` is summed
into `WS-TOTAL-INT` (`ADD WS-MONTHLY-INT TO WS-TOTAL-INT`). After all of the
account's category balances are processed, `1050-UPDATE-ACCOUNT` posts the total
to the balance and zeroes the cycle counters:

```cobol
ADD  WS-TOTAL-INT  TO ACCT-CURR-BAL
MOVE 0             TO ACCT-CURR-CYC-CREDIT
MOVE 0             TO ACCT-CURR-CYC-DEBIT
```

The expected balances in `interest-smoke.account-balances.txt` match **to the
cent**.

**Run date.** `INTCALC.jcl` passes `PARM='2022071800'` (10 characters) into the
COBOL `PARM-DATE PIC X(10)`. In the modernized job this becomes a Spring Batch
**`JobParameter`** (run-date `2022071800`). The expected `TRAN-ID`s are tied to
this run date (see the layout table below).

## `TRAN-RECORD` 350-byte layout (`CVTRA05Y`)

Each expected record in `interest-smoke.interest-tran.dat` is exactly **350
bytes**. Byte offsets are **1-indexed, inclusive**. The *Value* column lists what
`CBACT04C` `1300-B-WRITE-TX` writes for every interest transaction in this
scenario.

| Field | PIC | Bytes | Value |
|---|---|---|---|
| `TRAN-ID` | `X(16)` | 1–16 | `2022071800` (run date) + 6-digit global suffix |
| `TRAN-TYPE-CD` | `X(02)` | 17–18 | `01` |
| `TRAN-CAT-CD` | `9(04)` | 19–22 | `0005` (literal `'05'` into a `9(04)` field) |
| `TRAN-SOURCE` | `X(10)` | 23–32 | `System    ` (`System` + 4 spaces) |
| `TRAN-DESC` | `X(100)` | 33–132 | `Int. for a/c ` + 11-digit `ACCT-ID`, space-filled to 100 |
| `TRAN-AMT` | `S9(09)V99` | 133–143 | monthly interest, zoned with **trailing overpunch sign** |
| `TRAN-MERCHANT-ID` | `9(09)` | 144–152 | `000000000` |
| `TRAN-MERCHANT-NAME` | `X(50)` | 153–202 | spaces |
| `TRAN-MERCHANT-CITY` | `X(50)` | 203–252 | spaces |
| `TRAN-MERCHANT-ZIP` | `X(10)` | 253–262 | spaces |
| `TRAN-CARD-NUM` | `X(16)` | 263–278 | XREF card number for the account |
| `TRAN-ORIG-TS` | `X(26)` | 279–304 | sentinel `2022-07-18-00.00.00.000000` (see masking) |
| `TRAN-PROC-TS` | `X(26)` | 305–330 | sentinel `2022-07-18-00.00.00.000000` (see masking) |
| `FILLER` | `X(20)` | 331–350 | spaces |

`TRAN-ID` is built by `STRING PARM-DATE, WS-TRANID-SUFFIX … INTO TRAN-ID`, i.e.
the 10-character run date concatenated with the zero-padded 6-digit suffix
(`2022071800` + `000001` = `2022071800000001`).

## Encoding & normalization rules (CRITICAL)

### 1. Zoned-decimal trailing overpunch sign (`TRAN-AMT`)

`TRAN-AMT` (`S9(09)V99`, 11 bytes) carries 9 integer + 2 decimal digits with the
implied decimal point removed; the **last (rightmost) digit is replaced by an
overpunch character** that encodes both the final digit and the sign. This
matches the convention used throughout `legacy/app/data/ASCII/*.txt`.

| Last digit | `0` | `1` | `2` | `3` | `4` | `5` | `6` | `7` | `8` | `9` |
|---|---|---|---|---|---|---|---|---|---|---|
| **Positive** | `{` | `A` | `B` | `C` | `D` | `E` | `F` | `G` | `H` | `I` |
| **Negative** | `}` | `J` | `K` | `L` | `M` | `N` | `O` | `P` | `Q` | `R` |

Worked examples (the three amounts in this fixture, all positive):

| Amount | 11-byte zoned `TRAN-AMT` |
|---|---|
| `12.34` | `0000000123D` (last digit `4` → `D`) |
| `30.00` | `0000000300{` (last digit `0` → `{`) |
| `4.50` | `0000000045{` (last digit `0` → `{`) |

### 2. Space-fill normalization

`CBACT04C` `MOVE SPACES` only to `TRAN-MERCHANT-NAME`, `TRAN-MERCHANT-CITY`, and
`TRAN-MERCHANT-ZIP`; the `TRAN-RECORD` itself has no `VALUE`/`INITIALIZE`, so the
unwritten tail bytes of `TRAN-DESC` (positions 25–100 of that field, after
`Int. for a/c ` + the 11-digit `ACCT-ID`) and the trailing `FILLER (X(20))` are
**uninitialized** in the COBOL working storage. On z/OS these would typically be
**low-values (`0x00`)**. The modernized Java fixed-width writer **space-fills
(`0x20`)** these positions instead. **The golden file uses spaces, and the Java
service must too** — this is the documented, parity-preserving normalization. It
keeps every record a clean 350 printable-ASCII bytes and makes byte-for-byte
diffs meaningful.

### 3. Timestamp masking (non-deterministic fields)

`CBACT04C` fills `TRAN-ORIG-TS` and `TRAN-PROC-TS` from `FUNCTION CURRENT-DATE`
(the wall clock), which is **non-deterministic**. The golden file stores the
fixed sentinel **`2022-07-18-00.00.00.000000`** in both fields (bytes
**279–330**). The parity test **MUST** therefore do **one** of the following
before comparing:

- **mask bytes 279–330** (both 26-byte timestamps) in the actual output before
  the byte comparison, **or**
- inject a **fixed `Clock`** into the service that deterministically produces this
  exact sentinel.

All other `350 − 52 = 298` bytes of every record are compared **byte-for-byte**.

## The `interest-smoke` scenario

These golden files are the expected output for one small, curated input case. The
**paired input fixture** lives in the sibling `src/test/resources/fixtures/`
folder (mirroring `legacy/app/data/ASCII/*.txt`); the pairing is **logical only —
there is NO build dependency** between this folder and `fixtures/` (see
[Test usage](#test-usage--pairing-convention)).

### Curated inputs

**Disclosure-group rates** (`DISCGRP` → `DIS-INT-RATE`):

| `DIS-ACCT-GROUP-ID` | `DIS-TRAN-TYPE-CD` | `DIS-TRAN-CAT-CD` | rate |
|---|---|---|---|
| `A000000000` | `01` | `0001` | `12.00` |
| `A000000000` | `01` | `0002` | `18.00` |
| `ZEROAPR` | `01` | `0001` | `0.00` |
| `DEFAULT` | `01` | `0001` | `9.00` |

**Accounts** (`ACCTDATA`) and their card-xref card numbers:

| `ACCT-ID` | `ACCT-CURR-BAL` | `ACCT-GROUP-ID` | XREF card |
|---|---|---|---|
| `00000000011` | `1000.00` | `A000000000` | `4111111111111111` |
| `00000000022` | `500.00` | `ZEROAPR` | `4222222222222222` |
| `00000000033` | `100.00` | `XYZ` (not a known group → falls back to `DEFAULT`) | `4333333333333333` |

**Transaction-category balances** (`TCATBALF` → `TRAN-CAT-BAL`, key ascending):

| `ACCT-ID` | `TYPE` | `CAT` | `TRAN-CAT-BAL` |
|---|---|---|---|
| `00000000011` | `01` | `0001` | `1234.56` |
| `00000000011` | `01` | `0002` | `2000.00` |
| `00000000022` | `01` | `0001` | `999.99` |
| `00000000033` | `01` | `0001` | `600.00` |

> Account `00000000033` uses group `XYZ`, which is not present in `DISCGRP`;
> `CBACT04C` then reads the **`DEFAULT`** disclosure group, so its category
> balance is charged the `DEFAULT` rate of `9.00`.

### Expected results

| Suffix → `TRAN-ID` | Account | Computation | `WS-MONTHLY-INT` | Zoned `TRAN-AMT` |
|---|---|---|---|---|
| `000001` → `2022071800000001` | `00000000011` | `1234.56 × 12.00 ÷ 1200 = 12.3456` → **truncate** | `12.34` | `0000000123D` |
| `000002` → `2022071800000002` | `00000000011` | `2000.00 × 18.00 ÷ 1200 = 30.00` | `30.00` | `0000000300{` |
| `000003` → `2022071800000003` | `00000000033` | `600.00 × 9.00 ÷ 1200 = 4.50` | `4.50` | `0000000045{` |

Account `00000000022` is **skipped**: its only category balance maps to the
`ZEROAPR` group whose rate is `0.00`, so the `IF DIS-INT-RATE NOT = 0` gate is
false — **no interest transaction is written and the global suffix is not
incremented**. This is why the three suffixes run `000001`–`000003` with no gap
even though account `…022` sits between `…011` and `…033`. The global suffix is
incremented **only** inside `1300-B-WRITE-TX` (`ADD 1 TO WS-TRANID-SUFFIX`), i.e.
only when an interest record is actually written.

**Resulting balances** (`interest-smoke.account-balances.txt`, ascending `ACCT-ID`):

| `ACCT-ID` | before | + interest | after |
|---|---|---|---|
| `00000000011` | `1000.00` | `12.34 + 30.00 = 42.34` | **`1042.34`** |
| `00000000022` | `500.00` | `0.00` (skipped) | **`500.00`** |
| `00000000033` | `100.00` | `4.50` | **`104.50`** |

> **Record 1 is the truncation proof.** `12.3456` must become `12.34`, not
> `12.35`. A parity test that passes only because it rounds is a false positive.

## Test usage & pairing convention

Load the golden files from the classpath (do **not** hard-code host paths):

```text
classpath:golden/interest/interest-smoke.interest-tran.dat
classpath:golden/interest/interest-smoke.account-balances.txt
```

A `src/test/java` parity test should:

1. **Seed** PostgreSQL (Testcontainers) from the paired input in
   `src/test/resources/fixtures/` (disclosure groups, accounts, card-xref, and
   transaction-category balances for the `interest-smoke` case).
2. **Run** the `InterestCalculationJob` with the Spring Batch `JobParameter`
   run-date **`2022071800`** and a **fixed `Clock`** (or sentinel) so the
   timestamps are deterministic.
3. **Assert** the actual interest `TRAN-RECORD`s (with bytes **279–330** masked,
   or the fixed clock applied) are **byte-equal** to
   `interest-smoke.interest-tran.dat`, and the resulting `ACCT-CURR-BAL` values
   are **decimal-equal** to `interest-smoke.account-balances.txt`.

The golden ↔ fixtures pairing is **logical only** (a shared `<case>` name,
`interest-smoke`); there is **no build dependency** between the two folders. The
wiring that connects them is asserted entirely by `src/test/java`.

## Parity rules recap

- **Byte / semantic fidelity** — fixed **350-byte** records, exact padding
  (`X(n)` ⇒ trailing spaces, `9(n)` ⇒ leading zeros), one LF per record.
- **Decimal correctness** — `BigDecimal` + **`RoundingMode.DOWN`** at scale 2;
  **never** `float`/`double`.
- **Zoned-decimal sign exactness** — trailing overpunch per the table above.
- **Zero-rate skip** — no interest transaction (and no suffix increment) when
  `DIS-INT-RATE = 0`.
- **`DEFAULT`-group fallback** — an account whose group is absent from `DISCGRP`
  is charged the `DEFAULT` group rate.
- **Local-only generation** — derived from documented COBOL logic; no mainframe
  runtime required.

## Derivation appendix (regenerate the bytes)

These golden files are produced **deterministically** by the script below. It is
the canonical record of how the bytes were derived from the documented
`CBACT04C` logic, so reviewers can regenerate and re-verify them. Any change to
the interest contract must be reflected here **and** in the checksum table below,
in lock-step.

```python
#!/usr/bin/env python3
"""Byte-exact generator for the interest-calculation golden files (CBACT04C parity).
Run from this directory:  python3 generate_golden_interest.py"""
import hashlib
from decimal import Decimal, ROUND_DOWN

# PIC X(n): left-justify, space-pad.   PIC 9(n): right-justify, zero-pad.
def x(s, n):   s = str(s); assert len(s) <= n, (s, n); return s.ljust(n)
def num(v, n): s = str(int(v)); assert len(s) <= n, (s, n); return s.zfill(n)

# COBOL COMPUTE without ROUNDED -> truncate to scale 2 (RoundingMode.DOWN).
def trunc2(value):
    return Decimal(str(value)).quantize(Decimal('0.01'), rounding=ROUND_DOWN)

# PIC S9(09)V99 with trailing overpunch sign (11 bytes).
POS = {'0': '{', '1': 'A', '2': 'B', '3': 'C', '4': 'D',
       '5': 'E', '6': 'F', '7': 'G', '8': 'H', '9': 'I'}
NEG = {'0': '}', '1': 'J', '2': 'K', '3': 'L', '4': 'M',
       '5': 'N', '6': 'O', '7': 'P', '8': 'Q', '9': 'R'}
def zoned(amount):
    d = trunc2(amount); neg = d < 0
    digits = str(abs(d)).replace('.', '').zfill(11); assert len(digits) == 11
    return digits[:-1] + (NEG if neg else POS)[digits[-1]]

RUN_DATE = '2022071800'                  # PARM='2022071800' (PIC X(10))
TS       = '2022-07-18-00.00.00.000000'  # fixed sentinel for TRAN-ORIG-TS / TRAN-PROC-TS

def interest_record(suffix, acct_id, monthly_int, card_num):
    rec = (x(RUN_DATE + num(suffix, 6), 16)              # TRAN-ID            1-16
           + x('01', 2)                                  # TRAN-TYPE-CD       17-18
           + num('05', 4)                                # TRAN-CAT-CD 9(04)  19-22 -> 0005
           + x('System', 10)                             # TRAN-SOURCE        23-32
           + x('Int. for a/c ' + num(acct_id, 11), 100)  # TRAN-DESC          33-132
           + zoned(monthly_int)                          # TRAN-AMT           133-143
           + num(0, 9)                                   # TRAN-MERCHANT-ID   144-152
           + x('', 50) + x('', 50) + x('', 10)           # NAME/CITY/ZIP      153-262 (spaces)
           + x(card_num, 16)                             # TRAN-CARD-NUM      263-278
           + x(TS, 26) + x(TS, 26)                       # ORIG-TS / PROC-TS  279-330
           + x('', 20))                                  # FILLER             331-350 (spaces)
    assert len(rec) == 350, len(rec)
    return rec

# interest-smoke expected interest transactions (suffix increments only when written):
rows = [
    (1, 11, trunc2(Decimal('1234.56') * Decimal('12.00') / 1200), '4111111111111111'),  # 12.34
    (2, 11, trunc2(Decimal('2000.00') * Decimal('18.00') / 1200), '4111111111111111'),  # 30.00
    (3, 33, trunc2(Decimal('600.00')  * Decimal('9.00')  / 1200), '4333333333333333'),  # 4.50
]
tran = ''.join(interest_record(*r) + '\n' for r in rows)

# expected resulting ACCT-CURR-BAL, ascending ACCT-ID, pipe-delimited "ACCT-ID|balance":
bal_rows = [(11, Decimal('1000.00') + Decimal('12.34') + Decimal('30.00')),  # 1042.34
            (22, Decimal('500.00')),                                          #  500.00 (skipped)
            (33, Decimal('100.00') + Decimal('4.50'))]                        #  104.50
bal = ''.join(f"{num(a, 11)}|{b:.2f}\n" for a, b in bal_rows)

with open('interest-smoke.interest-tran.dat', 'w', newline='') as f:
    f.write(tran)
with open('interest-smoke.account-balances.txt', 'w', newline='') as f:
    f.write(bal)

# Self-verify against the expected MD5 checksums.
EXPECT = {
    'interest-smoke.interest-tran.dat':    '1c164f0a637aa98c2e9db93da15fc097',
    'interest-smoke.account-balances.txt': '7574c3f4ad0d991151a40bcf292d9cf8',
}
for name, payload in (('interest-smoke.interest-tran.dat', tran),
                      ('interest-smoke.account-balances.txt', bal)):
    got = hashlib.md5(payload.encode('ascii')).hexdigest()
    assert got == EXPECT[name], (name, got, EXPECT[name])
print('OK - both golden files regenerated and MD5-verified.')
```

**Expected MD5 checksums** (update in lock-step with any contract change):

| File | Bytes | MD5 |
|---|---|---|
| `interest-smoke.interest-tran.dat` | 1053 | `1c164f0a637aa98c2e9db93da15fc097` |
| `interest-smoke.account-balances.txt` | 58 | `7574c3f4ad0d991151a40bcf292d9cf8` |

Verify in place (run from this directory):

```bash
md5sum -c <<'SUMS'
1c164f0a637aa98c2e9db93da15fc097  interest-smoke.interest-tran.dat
7574c3f4ad0d991151a40bcf292d9cf8  interest-smoke.account-balances.txt
SUMS
```

## License

These test assets are part of AWS CardDemo (modernized) and are licensed under
the Apache License, Version 2.0.
