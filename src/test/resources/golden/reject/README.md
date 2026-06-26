# Golden reject files — DALYREJS / CBTRN02C parity fixtures

This directory holds the **expected** reject-file output that is asserted **byte-for-byte**
against the modernized `TransactionPostingService` reject/skip writer, proving 100% parity with
the legacy COBOL program `CBTRN02C` (daily transaction posting) and specifically its
`2500-WRITE-REJECT-REC` paragraph. See AAP **§0.6.6** (reject processing), **§0.6.4**
(FILE STATUS / validation), and **§0.6.7** (local-only validation).

> **Only invalid daily transactions appear here.** Valid transactions are *posted*, not rejected.
> A reject record is written **only** when `WS-VALIDATION-FAIL-REASON != 0` in the `CBTRN02C`
> main loop (`PERFORM 1500-VALIDATE-TRAN` → `IF WS-VALIDATION-FAIL-REASON = 0` then post,
> `ELSE` `PERFORM 2500-WRITE-REJECT-REC`).

## Record contract

- Every reject record is **exactly 430 bytes**, fixed-width (`RECFM=F, LRECL=430`), per the
  `DALYREJS` DD in `app/jcl/POSTTRAN.jcl`.
- Layout = **350-byte rejected `DALYTRAN-RECORD`** (an *unmodified* copy of the input
  daily-transaction record, `app/cpy/CVTRA06Y.cpy`) **+ 80-byte validation trailer**.
- Files are stored as **ASCII text with one Unix LF (`\n`, `0x0A`) after every record
  (including the last)**, mirroring how the legacy `app/data/ASCII/*` fixed-width fixtures are
  stored. **No CR (`0x0D`), no BOM.** Thus a single-record file is **431 bytes** (430 + LF) and an
  N-record file is `431 × N` bytes.

### 350-byte `DALYTRAN-RECORD` (`app/cpy/CVTRA06Y.cpy`, 0-based offsets)

| Field | Offset | Len | PIC | Padding / format |
|---|---|---|---|---|
| DALYTRAN-ID | 0 | 16 | X(16) | left-justified, space-padded |
| DALYTRAN-TYPE-CD | 16 | 2 | X(02) | left-justified |
| DALYTRAN-CAT-CD | 18 | 4 | 9(04) | right-justified, zero-padded |
| DALYTRAN-SOURCE | 22 | 10 | X(10) | left-justified, space-padded |
| DALYTRAN-DESC | 32 | 100 | X(100) | left-justified, space-padded |
| DALYTRAN-AMT | 132 | 11 | S9(09)V99 | signed zoned-decimal, **trailing overpunch** |
| DALYTRAN-MERCHANT-ID | 143 | 9 | 9(09) | right-justified, zero-padded |
| DALYTRAN-MERCHANT-NAME | 152 | 50 | X(50) | left-justified, space-padded |
| DALYTRAN-MERCHANT-CITY | 202 | 50 | X(50) | left-justified, space-padded |
| DALYTRAN-MERCHANT-ZIP | 252 | 10 | X(10) | left-justified, space-padded |
| DALYTRAN-CARD-NUM | 262 | 16 | X(16) | left-justified, space-padded |
| DALYTRAN-ORIG-TS | 278 | 26 | X(26) | `YYYY-MM-DD HH:MM:SS.ffffff` |
| DALYTRAN-PROC-TS | 304 | 26 | X(26) | blank (26 spaces) in input |
| FILLER | 330 | 20 | X(20) | blank (20 spaces) |

### 80-byte validation trailer (`CBTRN02C` `WS-VALIDATION-TRAILER`)

| Field | Offset | Len | PIC | Format |
|---|---|---|---|---|
| WS-VALIDATION-FAIL-REASON | 350 | 4 | 9(04) | reason code, zero-padded (e.g. `0100`) |
| WS-VALIDATION-FAIL-REASON-DESC | 354 | 76 | X(76) | reason text, left-justified, space-padded to 76 |

## Overpunch encoding (`DALYTRAN-AMT`)

`DALYTRAN-AMT` (`S9(09)V99`, 11 bytes) is 9 integer + 2 decimal digits with the implied decimal
removed; the **last (rightmost) digit is replaced by an overpunch character** encoding both the
final digit and the sign:

- Positive last digit `0..9` → `{ A B C D E F G H I`
- Negative last digit `0..9` → `} J K L M N O P Q R`

Examples used in these fixtures (all positive):

- `100.00` → `0000001000{`
- `250.50` → `0000002505{`
- `5000.00` → `0000050000{`

## Reason codes & exact messages

Messages are stored verbatim, left-justified and space-padded to 76 bytes in the trailer.

| Code | Message (space-padded to 76) | Trigger (`CBTRN02C` `1500-VALIDATE-TRAN`) |
|---|---|---|
| `0100` | `INVALID CARD NUMBER FOUND` | card not in card-xref (`1500-A-LOOKUP-XREF` INVALID KEY) |
| `0101` | `ACCOUNT RECORD NOT FOUND` | account not in account master (`1500-B-LOOKUP-ACCT` INVALID KEY) |
| `0102` | `OVERLIMIT TRANSACTION` | `ACCT-CREDIT-LIMIT < ACCT-CURR-CYC-CREDIT − ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT` |
| `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)` |

## Validation precedence (parity-critical)

The order in which `CBTRN02C` evaluates these checks determines which reason "wins" when a
transaction fails more than one rule. This must be reproduced exactly.

1. **`1500-A-LOOKUP-XREF` runs first.** If the card is invalid → reason `0100` and the account
   lookup is **skipped** (gate `IF WS-VALIDATION-FAIL-REASON = 0` before `PERFORM 1500-B-LOOKUP-ACCT`).
2. **Else `1500-B-LOOKUP-ACCT` runs.** If the account is missing → reason `0101` and the
   limit/expiration checks are **skipped** (they live in the `NOT INVALID KEY` branch).
3. **Else the overlimit check (→ `0102`) and the expiration check (→ `0103`) run as two
   SEQUENTIAL `IF` blocks (separate `END-IF`s, NOT `ELSE`-chained).** If a transaction is **both**
   overlimit and after-expiration, `MOVE 103` executes after `MOVE 102`, so **`0103` overwrites
   `0102` — `0103` wins**.
4. Only records with a **non-zero** reason are written, in **input order**.

## Files in this directory

Each `.dat` file pairs a reject scenario with its canonical values. Preconditions reference the
seeded test data (card-xref + account master) loaded by the parity tests.

- **`invalid-card.reject.dat`** — reason `0100`, 1 record (431 bytes). Card `9999999999999999`
  is absent from the seeded card-xref. ID `DLYREJ0100000001`, AMT `100.00`,
  ORIG-TS `2024-01-15 09:30:00.000000`.
- **`account-not-found.reject.dat`** — reason `0101`, 1 record (431 bytes). Card
  `9999999999999998` resolves via a seeded xref row to account `99999999999`, which is
  deliberately absent from the account master. ID `DLYREJ0101000001`, AMT `250.50`,
  ORIG-TS `2024-01-15 09:30:00.000000`.
- **`overlimit.reject.dat`** — reason `0102`, 1 record (431 bytes). Card `9680294154603697` →
  account `00000000001` (real seed: CREDIT-LIMIT `2020.00`, CYC-CREDIT `0.00`, CYC-DEBIT `0.00`,
  EXPIRATION `2025-05-20`). AMT `5000.00` ⇒ temp balance `5000.00` > limit `2020.00`.
  ORIG-TS `2024-01-15 09:30:00.000000` (≤ expiration so 0103 does not fire). ID `DLYREJ0102000001`.
- **`after-expiration.reject.dat`** — reason `0103`, 1 record (431 bytes). Same account
  `00000000001`; AMT `100.00` (within limit so 0102 does not fire);
  ORIG-TS `2026-06-01 09:30:00.000000` > expiration `2025-05-20`. ID `DLYREJ0103000001`.
- **`mixed-all-reasons.reject.dat`** — all four reasons + precedence, 5 records (2155 bytes), in
  input order: `0100`, `0101`, `0102`, `0103`, then a 5th record (`DLYMIX0103000002`,
  AMT `5000.00`, ORIG-TS `2027-01-01 09:30:00.000000`) that is **both** overlimit and
  after-expiration and is therefore rejected with `0103` (precedence: 103 overwrites 102).

## SHA-256 checksums

Used by the CI integrity check. Any change to a `.dat` file **must** update the matching row here.

| File | Bytes | SHA-256 |
|---|---|---|
| `invalid-card.reject.dat` | 431 | `d3cfb4933c90454bd1efe0fb17f2967337fa114fd1df46f9048169b03e288047` |
| `account-not-found.reject.dat` | 431 | `d131ad765c46995475423bcbb6a39a499d4fdb10e613c6e7335acc619490faf7` |
| `overlimit.reject.dat` | 431 | `5c1e5e75273121a66869705f912ce4e9844b008bfefe3228b6c5dbe5209970e3` |
| `after-expiration.reject.dat` | 431 | `5673bea2ca2d19e8ac6387eaeb35d32345df82579a15a261fb64f8bc0a095515` |
| `mixed-all-reasons.reject.dat` | 2155 | `7e1298308d63b33b188718ce3b09b98c0f55463bcb55f7f811bd660eebbe049b` |

Verify all five files at once (run from this directory):

```bash
sha256sum -c <<'SUMS'
d3cfb4933c90454bd1efe0fb17f2967337fa114fd1df46f9048169b03e288047  invalid-card.reject.dat
d131ad765c46995475423bcbb6a39a499d4fdb10e613c6e7335acc619490faf7  account-not-found.reject.dat
5c1e5e75273121a66869705f912ce4e9844b008bfefe3228b6c5dbe5209970e3  overlimit.reject.dat
5673bea2ca2d19e8ac6387eaeb35d32345df82579a15a261fb64f8bc0a095515  after-expiration.reject.dat
7e1298308d63b33b188718ce3b09b98c0f55463bcb55f7f811bd660eebbe049b  mixed-all-reasons.reject.dat
SUMS
```

## Regeneration

These files are generated **deterministically** by the script below, which self-verifies both the
byte sizes and the SHA-256 of every file against the embedded `EXPECT` table. Any change to the
reject contract **must** be reflected in this generator **and** the SHA-256 table above, in
lock-step (regenerate the `.dat` files, then copy the new digests into the table).

```python
#!/usr/bin/env python3
"""Deterministic, byte-exact generator for the golden DALYREJS reject files
expected from legacy COBOL CBTRN02C (2500-WRITE-REJECT-REC).
Each reject record = 350-byte DALYTRAN-RECORD (CVTRA06Y) + 80-byte validation trailer = 430 bytes.
Files are ASCII with one LF after every record (including the last). Only reason != 0 records are written.
Run from this directory:  python3 generate_golden_reject.py"""
import hashlib, os

def x(s, n):
    assert len(s) <= n, (s, n)
    return s.ljust(n)                       # PIC X(n): left-justify, space-pad

def num(v, n):
    s = str(int(v)); assert len(s) <= n
    return s.zfill(n)                       # PIC 9(n): right-justify, zero-pad

POS = {'0':'{','1':'A','2':'B','3':'C','4':'D','5':'E','6':'F','7':'G','8':'H','9':'I'}
NEG = {'0':'}','1':'J','2':'K','3':'L','4':'M','5':'N','6':'O','7':'P','8':'Q','9':'R'}

def amt(value):                            # PIC S9(09)V99 with trailing overpunch sign
    from decimal import Decimal, ROUND_DOWN
    d = Decimal(str(value)); neg = d < 0
    d = abs(d).quantize(Decimal('0.01'), rounding=ROUND_DOWN)
    digits = str(d).replace('.', '').zfill(11); assert len(digits) == 11
    return digits[:-1] + (NEG if neg else POS)[digits[-1]]

def daily(tran_id, desc, amount, card, orig_ts):
    rec = (x(tran_id,16) + x("01",2) + num(1,4) + x("POS TERM",10) + x(desc,100)
           + amt(amount) + num(800000000,9) + x("Parity Test Merchant",50)
           + x("Test City",50) + x("12345",10) + x(card,16) + x(orig_ts,26)
           + x("",26) + x("",20))           # PROC-TS blank, FILLER blank
    assert len(rec) == 350, len(rec)
    return rec

def reject(daily_rec, reason, msg):
    trailer = num(reason,4) + x(msg,76); assert len(trailer) == 80
    r = daily_rec + trailer; assert len(r) == 430
    return r

R0100 = reject(daily("DLYREJ0100000001", "PARITY REJECT 0100 INVALID CARD NUMBER",
                     "100.00",  "9999999999999999", "2024-01-15 09:30:00.000000"),
               100, "INVALID CARD NUMBER FOUND")
R0101 = reject(daily("DLYREJ0101000001", "PARITY REJECT 0101 ACCOUNT NOT FOUND",
                     "250.50",  "9999999999999998", "2024-01-15 09:30:00.000000"),
               101, "ACCOUNT RECORD NOT FOUND")
R0102 = reject(daily("DLYREJ0102000001", "PARITY REJECT 0102 OVERLIMIT TRANSACTION",
                     "5000.00", "9680294154603697", "2024-01-15 09:30:00.000000"),
               102, "OVERLIMIT TRANSACTION")
R0103 = reject(daily("DLYREJ0103000001", "PARITY REJECT 0103 AFTER ACCT EXPIRATION",
                     "100.00",  "9680294154603697", "2026-06-01 09:30:00.000000"),
               103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION")
RPREC = reject(daily("DLYMIX0103000002", "PARITY REJECT BOTH OVERLIMIT AND EXPIRED -> 0103",
                     "5000.00", "9680294154603697", "2027-01-01 09:30:00.000000"),
               103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION")

FILES = {
    "invalid-card.reject.dat":      [R0100],
    "account-not-found.reject.dat": [R0101],
    "overlimit.reject.dat":         [R0102],
    "after-expiration.reject.dat":  [R0103],
    "mixed-all-reasons.reject.dat": [R0100, R0101, R0102, R0103, RPREC],
}
EXPECT = {
    "invalid-card.reject.dat":      "d3cfb4933c90454bd1efe0fb17f2967337fa114fd1df46f9048169b03e288047",
    "account-not-found.reject.dat": "d131ad765c46995475423bcbb6a39a499d4fdb10e613c6e7335acc619490faf7",
    "overlimit.reject.dat":         "5c1e5e75273121a66869705f912ce4e9844b008bfefe3228b6c5dbe5209970e3",
    "after-expiration.reject.dat":  "5673bea2ca2d19e8ac6387eaeb35d32345df82579a15a261fb64f8bc0a095515",
    "mixed-all-reasons.reject.dat": "7e1298308d63b33b188718ce3b09b98c0f55463bcb55f7f811bd660eebbe049b",
}

if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    for name, records in FILES.items():
        data = ("\n".join(records) + "\n").encode("ascii")   # LF after every record
        digest = hashlib.sha256(data).hexdigest()
        assert digest == EXPECT[name], f"{name}: {digest} != {EXPECT[name]}"
        with open(os.path.join(here, name), "wb") as f:
            f.write(data)
        print(f"{name}: {len(data)} bytes  sha256={digest}")
```

## How the tests use these files

- The `src/test/java` reject-file parity tests load each file from
  `classpath:golden/reject/<case>.reject.dat` and assert the modernized
  `TransactionPostingService` reject/skip writer output matches **byte-for-byte** — same 430-byte
  records, same order, same trailing-LF framing.
- Input daily-transaction fixtures live in the sibling `fixtures/` directory; the pairing here is
  **logical only** (the test code wires inputs to expected outputs) — there is **no** build or
  sibling-directory dependency.

## Source / authority references

Legacy specification (read-only under `legacy/app/...`):

- `app/cbl/CBTRN02C.cbl` — FD `FD-REJS-RECORD` (`X(350)` + `X(80)`), `WS-VALIDATION-TRAILER`
  (`9(04)` + `X(76)`), `1500-VALIDATE-TRAN` / `1500-A-LOOKUP-XREF` / `1500-B-LOOKUP-ACCT`,
  `2500-WRITE-REJECT-REC`.
- `app/cpy/CVTRA06Y.cpy` — 350-byte `DALYTRAN-RECORD` layout.
- `app/jcl/POSTTRAN.jcl` — `DALYREJS` DD (`RECFM=F, LRECL=430`, GDG).
- `app/cpy/CVACT01Y.cpy` — account fields (credit limit, cycle credit/debit, expiration date) for
  `0102`/`0103`.
- `app/cpy/CVACT03Y.cpy` — card-xref layout for the `0100`/`0101` lookup paths.

## Validation checklist

- [x] File is valid Markdown, renders cleanly, and contains every table above with exact
  offsets / lengths / codes / messages.
- [x] All 5 filenames, byte sizes, and SHA-256 values match the checksum table exactly.
- [x] The embedded Python generator is reproduced **verbatim** inside a fenced `python` block.
- [x] Reason messages are verbatim: `INVALID CARD NUMBER FOUND`, `ACCOUNT RECORD NOT FOUND`,
  `OVERLIMIT TRANSACTION`, `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`.
- [x] Precedence rule (`0103` overwrites `0102` when both fail; `0100` gates; `0101`
  short-circuits) is clearly documented.
- [x] No secrets or credentials; documentation only.

## Key facts

- This is **documentation only** (`depends_on_files = []`). Do **not** change any technical value
  (offset, length, reason code, message, filename, canonical value, or checksum) without
  regenerating the `.dat` files and updating the checksum table in lock-step.
