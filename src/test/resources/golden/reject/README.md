# Golden EXPECTED reject records — DailyTransactionPostingJob

The files in this folder are the **byte-exact EXPECTED posting-reject records** for the INVALID
records of the isolated input scenario in [`src/test/resources/seed/posting/`](../../seed/posting/).
They are the authoritative external-file contract asserted by the reject-path parity test under
`src/test/java/com/aws/carddemo/**` (Technical Specification §0.9.2 *reject-path parity*; §0.7.1
hotspot **H4** *batch posting reject-code semantics*). The valid-post side of the very same scenario
— the rows that post successfully rather than reject — lives in the sibling folder
[`src/test/resources/golden/posting/`](../posting/).

Because the sibling `.dat` fixtures are fixed-width binary payloads that cannot carry inline
comments, this document is the in-repo home of the reject-record contract, as required by the
Explainability rule (Technical Specification §0.8.2). Every statement below is verified against the
legacy COBOL source and the already-created destination Java code; nothing here is invented.

## Lineage

| Aspect | Source |
|--------|--------|
| Program | `legacy/cbl/CBTRN02C.cbl` (relocated from `app/cbl/CBTRN02C.cbl`) |
| Reject-write paragraph | `2500-WRITE-REJECT-REC` |
| Reject FD | `DALYREJS-FILE` (`FD-REJS-RECORD`) |
| Embedded body layout | `legacy/cpy/CVTRA06Y.cpy` (`DALYTRAN-RECORD`) |
| Reject-code driver (100) | `legacy/cpy/CVACT03Y.cpy` (`CARD-XREF-RECORD`) |
| Reject-code drivers (102 / 103) | `legacy/cpy/CVACT01Y.cpy` (`ACCOUNT-RECORD`) |

---

## 1. Reject record layout — 430 bytes total (NOT 350)

Each **full** reject record is **430 bytes**. The "350-byte" figure that appears in the folder brief
refers **only to the embedded input copy** (the failed `DALYTRAN` image). The complete FD record
adds an 80-byte validation trailer:

```
FD-REJS-RECORD (430 bytes) = REJECT-TRAN-DATA (350) + VALIDATION-TRAILER (80)
```

Structure of `FD-REJS-RECORD`, from the `CBTRN02C` FD `DALYREJS-FILE` (verified at
`CBTRN02C.cbl` L82–L84) and its working-storage image `REJECT-RECORD` (L176–L178):

- **`REJECT-TRAN-DATA PIC X(350)`** — the verbatim 350-byte `DALYTRAN-RECORD` that failed
  validation. Paragraph `2500-WRITE-REJECT-REC` performs `MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA`
  (L447), i.e. a **byte-for-byte copy of the input record** — it is *not* re-formatted or
  re-encoded.
- **`VALIDATION-TRAILER PIC X(80)`** — populated from `WS-VALIDATION-TRAILER` (verified at
  `CBTRN02C.cbl` L180–L182):
  - **`WS-VALIDATION-FAIL-REASON PIC 9(04)`** — the reason code, zero-padded to 4 digits
    (`0100`, `0101`, `0102`, `0103`).
  - **`WS-VALIDATION-FAIL-REASON-DESC PIC X(76)`** — the reason description, left-justified and
    space-padded to 76 bytes.

### 1.1 Embedded 350-byte body (`DALYTRAN-RECORD`, copybook `CVTRA06Y`)

Offsets are **0-based** and expressed as half-open intervals `[start:end)`.

| Field | Offset `[start:end)` | Len | Type / encoding |
|-------|----------------------|-----|-----------------|
| dalytran_id | `[0:16]` | 16 | ALPHANUMERIC (left-justified, space-padded) |
| type_cd | `[16:18]` | 2 | ALPHANUMERIC |
| cat_cd | `[18:22]` | 4 | NUMERIC (right-justified, zero-padded) |
| source | `[22:32]` | 10 | ALPHANUMERIC |
| description | `[32:132]` | 100 | ALPHANUMERIC |
| amount | `[132:143]` | 11 | SIGNED zoned-decimal, sign OVERPUNCHED on last byte |
| merchant_id | `[143:152]` | 9 | NUMERIC |
| merchant_name | `[152:202]` | 50 | ALPHANUMERIC |
| merchant_city | `[202:252]` | 50 | ALPHANUMERIC |
| merchant_zip | `[252:262]` | 10 | ALPHANUMERIC |
| card_num | `[262:278]` | 16 | ALPHANUMERIC |
| orig_ts | `[278:304]` | 26 | ALPHANUMERIC |
| proc_ts | `[304:330]` | 26 | ALPHANUMERIC (blank / spaces in these fixtures) |
| FILLER | `[330:350]` | 20 | spaces |

The 14 fields sum to exactly 350 bytes
(`16+2+4+10+100+11+9+50+50+10+16+26+26+20 = 350`).

### 1.2 80-byte validation trailer

| Field | Offset `[start:end)` | Len | Type / encoding |
|-------|----------------------|-----|-----------------|
| reason_code | `[350:354]` | 4 | NUMERIC (`PIC 9(04)`, right-justified, zero-padded) |
| reason_desc | `[354:430]` | 76 | ALPHANUMERIC (`PIC X(76)`, left-justified, space-padded) |

---

## 2. Amount overpunch encoding

Monetary amounts are stored as COBOL **signed zoned-decimal** (`PIC S9(9)V99` DISPLAY) with the
sign **OVERPUNCHED onto the last (units) digit**. There is **no decimal point** and **no separate
sign character** anywhere in the 11-byte field: the 11 characters are the 9 integer digits followed
by the 2 fractional digits, and the final character encodes both the last digit *and* the sign.

**Positive values** (sign overpunched on the units digit):

| Digit | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|-------|---|---|---|---|---|---|---|---|---|---|
| Char  | `{` | `A` | `B` | `C` | `D` | `E` | `F` | `G` | `H` | `I` |

**Negative values** (sign overpunched on the units digit):

| Digit | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|-------|---|---|---|---|---|---|---|---|---|---|
| Char  | `}` | `J` | `K` | `L` | `M` | `N` | `O` | `P` | `Q` | `R` |

Note: `+0.00` → units digit `0` → `{` (positive table). All three loadable scenario amounts are
positive whole-dollar values, so the fractional pair is `00` and the units digit is `0`, giving a
**trailing `{`** on each embedded amount (e.g. `0000000500{` = `+50.00`, `0000005000{` = `+500.00`).

### 2.1 Encoding provenance

This exact scheme was verified against the real legacy data in
`legacy/data/ASCII/dailytran.txt` (relocated from `app/data/ASCII/dailytran.txt`) — for example
`0000005047G` = `+504.77` (units digit `7`, positive) and `0000009190}` = `−919.00` (units digit
`0`, negative). A census of the amount field's last byte across **all 300 legacy records** contains
**only** overpunch characters (`{` / `A`–`I` for positive, `}` / `J`–`R` for negative) and **never a
plain digit** — proving the sign is always overpunched, never separate.

---

## 3. Reason codes and evaluation order

The four reason codes, with their **exact** description strings (reproduced verbatim from the COBOL
literals and from `RejectCode.java`) and their trigger conditions:

| Code | Description (verbatim) | Len | Trigger | Source |
|------|------------------------|-----|---------|--------|
| `0100` | `INVALID CARD NUMBER FOUND` | 25 | Card number not found in `card_xref` (XREF `INVALID KEY`) | `CBTRN02C` L386 |
| `0101` | `ACCOUNT RECORD NOT FOUND` | 24 | XREF resolves but the account is missing (ACCOUNT `INVALID KEY`) | `CBTRN02C` L398 |
| `0102` | `OVERLIMIT TRANSACTION` | 21 | `ACCT-CREDIT-LIMIT < WS-TEMP-BAL` | `CBTRN02C` L411 |
| `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | 42 | `ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)` | `CBTRN02C` L418 |

Where:

- `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT − ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT` (`CBTRN02C` L403–L405).
- `DALYTRAN-ORIG-TS(1:10)` is the **first 10 characters** of the 26-byte origination timestamp —
  i.e. the date portion (`YYYY-MM-DD`).
- The COBOL field is spelled **`ACCT-EXPIRAION-DATE`** (the legacy misspelling of "EXPIRATION" is
  preserved intentionally; do not "correct" it in any code that references the copybook).

### 3.1 Evaluation order (reordering it is a behavioral regression — §0.7.1 H4)

Paragraph `1500-VALIDATE-TRAN` (`CBTRN02C` L370–L378):

1. **`1500-A-LOOKUP-XREF` runs FIRST** and sets reason **100** on a card-not-found miss. This
   **short-circuits**: the validator only continues with `IF WS-VALIDATION-FAIL-REASON = 0`
   (L372), so when reason ≠ 0 the account lookup is **skipped entirely**. Reason **100** therefore
   *precludes* 101, 102, and 103.
2. **Only if reason is still 0**, `1500-B-LOOKUP-ACCT` runs. It sets **101** on an account miss;
   otherwise (account found) it computes `WS-TEMP-BAL` and then evaluates **two separate `IF`
   statements** — first the **102** credit-limit check, then the **103** expiration check.
3. Because the 102 and 103 checks are **separate `IF` statements** (NOT `IF/ELSE`), a record that
   fails **both** ends up with reason **103** — **last-writer-wins**: the `MOVE 103` (L416–L418)
   runs after and overwrites the earlier `MOVE 102` (L410–L411).

---

## 4. The fixture files

The fixtures are generated from the isolated scenario in `src/test/resources/seed/posting/`. In feed
order the four daily-transaction rows are `R1`…`R4`; `R1` posts successfully, and **exactly three
records (`R2`, `R3`, `R4`) are rejected**.

### `expected-reject-100.dat` — 430 bytes

- **Driver:** `R2`, `dalytran_id = 9990000000000002`, card `9999999999999999` (absent from
  `card_xref`, so the XREF lookup misses).
- **Trailer:** `0100` + `INVALID CARD NUMBER FOUND`.
- **Body:** `R2`'s verbatim 350-byte input record.
- **SHA-256:** `f029d8b7caf17522349ec61d102e4db76a80ea40a88e499e92e1eced32d3f978`

### `expected-reject-102.dat` — 430 bytes

- **Driver:** `R3`, `dalytran_id = 9990000000000003`, card `9000000000000002`, amount `500.00`.
  Account `90000000002` has `credit_limit = 100.00` and zero cycle balances, so
  `WS-TEMP-BAL = 0 − 0 + 500.00 = 500.00 > 100.00`.
- **Trailer:** `0102` + `OVERLIMIT TRANSACTION`.
- **Body:** `R3`'s verbatim 350-byte input record.
- **SHA-256:** `97439044992dc666f4fd0ed99b50e4be08d7f100a7778cad8bd9939bf3255d4b`

### `expected-reject-103.dat` — 430 bytes

- **Driver:** `R4`, `dalytran_id = 9990000000000004`, card `9000000000000003`, amount `10.00`.
  Account `90000000003` has `expiration_date = 2000-01-01`, which is `< 2025-01-15` (the value of
  `R4`'s `orig_ts(1:10)`).
- **Trailer:** `0103` + `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`.
- **Body:** `R4`'s verbatim 350-byte input record. Note that `R4` **passes** the credit-limit check
  (amount `10.00` keeps `WS-TEMP-BAL` well under the `5000.00` limit), so **only 103 fires** — this
  is not a last-writer-wins case.
- **SHA-256:** `96b82da8694345b16fbbaaad5e0844c8a57ceb154645cf06d0344d77d4f90bd5`

### `expected-reject-101.dat` — 80 bytes (trailer ONLY)

- This file is the **80-byte trailer only**, not a full 430-byte record (see §5 for why).
- **Trailer:** `0101` + `ACCOUNT RECORD NOT FOUND`.
- **SHA-256:** `2ab7a1850b267ce2a184b1b4a05ebc9025df744cd5a49f85155d396069dd3c0c`

### `expected-reject-summary.csv`

A human-readable aid listing the three **loadable** scenario rejects, with columns
`dalytran_id,reason_code,reason_desc`:

```csv
dalytran_id,reason_code,reason_desc
9990000000000002,100,INVALID CARD NUMBER FOUND
9990000000000003,102,OVERLIMIT TRANSACTION
9990000000000004,103,TRANSACTION RECEIVED AFTER ACCT EXPIRATION
```

---

## 5. Why reject code 101 is UNIT-TEST-ONLY

Reject **101** (`ACCOUNT RECORD NOT FOUND`) has **no full 430-byte load-based fixture**, and this is
deliberate. In the relational target, the `card_xref.acct_id → account` foreign key (declared
`NOT NULL`) makes an **orphan cross-reference row impossible**: a loadable seed can never produce an
xref that resolves to a missing account. The 101 branch is therefore **unreachable** through the
normal load-and-run path.

To preserve behavioral parity with the COBOL `1500-B-LOOKUP-ACCT` `INVALID KEY` branch, the 101 path
is exercised by a **unit test that mocks the account repository** to return empty for the lookup.
`expected-reject-101.dat` is supplied as an **80-byte EXPECTED trailer** (`0101` +
`ACCOUNT RECORD NOT FOUND`, space-padded to 80) for that unit test to assert against.

`expected-reject-101.dat` is **intentionally excluded** from `expected-reject-summary.csv` because it
is not part of the loadable `seed/posting/` scenario. (The parity of this defensive branch is
recorded as decision **D39** in `docs/decision-log.md`.)

---

## 6. Scenario-level assertions

For the `seed/posting/` scenario:

- **Total rejects = 3** (`R2` → 100, `R3` → 102, `R4` → 103).
- **1 record posts successfully** (`R1`).
- Because `WS-REJECT-COUNT > 0`, the batch **RETURN-CODE = 4** (`CBTRN02C` L229–L231), mapped onto
  the Spring Batch exit-status / return-code `0 / 4 / 8` model (Technical Specification §0.7.2 M1).

The parity test may assert both the **running reject count (3)** and the **exit code (4)**, in
addition to comparing each reject record byte-for-byte.

**`proc_ts` is blank in these EXPECTED reject bodies.** The posting timestamp is a volatile field,
and a reject is written *before* any posting occurs (validation fails first), so `proc_ts` is never
populated on the reject side. Do not expect a value there.

---

## 7. Coordination alignment (confirmed contract)

These fixtures are **byte-for-byte consistent** with the already-created destination code that
produces and consumes them:

- **`src/main/java/com/aws/carddemo/common/util/FixedWidthCodec.java`** — defines the encoders used
  here:
  - `writeAlphanumeric` = left-justify + space-fill + right-truncate (COBOL `PIC X(n)`);
  - `writeNumeric` = right-justify + zero-fill, never truncates (COBOL `PIC 9(n)`);
  - `SIGNED_DECIMAL` = the overpunch scheme in §2 (raw `BigDecimal` at scale 2 — **no floating
    point**), with `POSITIVE_OVERPUNCH = "{ABCDEFGHI"` and `NEGATIVE_OVERPUNCH = "}JKLMNOPQR"`.
- **`src/main/java/com/aws/carddemo/batch/writer/DailyTransactionPostingWriter.java`** — builds the
  430-byte reject record via `FixedWidthCodec.of(430)` (which returns a `RecordBuilder`) at the exact
  offsets in §1, writing the body from the failed source record and the trailer as
  `writeNumeric(rejectCode.getCode(), 4)` at offset **350** and
  `writeAlphanumeric(rejectCode.getDescription(), 76)` at offset **354**. It opens the reject file in
  **ISO-8859-1** (1 char = 1 byte) and writes each 430-byte record followed by a single line feed
  (`\n`).
- **`src/main/java/com/aws/carddemo/exception/RejectCode.java`** — the frozen enum whose
  `getCode()` / `getDescription()` supply the trailer values; its codes and descriptions match this
  document exactly. Reject code **109** (`CBTRN02C` account `REWRITE INVALID KEY`) is **deliberately
  excluded** from the reject-reason set: it is a posting-phase I/O error mapped to a
  `FileStatusException` / batch return-code **8**, **not** a validation reject row (decision **D38**
  in `docs/decision-log.md`).

### 7.1 CRITICAL framing rule — do NOT "fix" the fixtures by adding a newline

The **430-byte** (and **80-byte**) record content is the **authoritative fixed-width contract**.
Each `.dat` fixture file contains **exactly** that many bytes with **NO trailing newline**. The line
feed the writer emits after each record is a **documented Java file-framing convention**, not part
of the record: the parity test **strips it** (it splits the output on `\n` and compares the
fixed-width record). Do **not** append a newline to any `.dat` fixture to "match" the writer output —
doing so would break the byte-length and SHA-256 acceptance checks.

---

## 8. Regeneration / verification aid

Authoritative acceptance check for each fixture = its **byte length** plus its **SHA-256**:

| File | Bytes | SHA-256 |
|------|-------|---------|
| `expected-reject-100.dat` | 430 | `f029d8b7caf17522349ec61d102e4db76a80ea40a88e499e92e1eced32d3f978` |
| `expected-reject-101.dat` | 80 | `2ab7a1850b267ce2a184b1b4a05ebc9025df744cd5a49f85155d396069dd3c0c` |
| `expected-reject-102.dat` | 430 | `97439044992dc666f4fd0ed99b50e4be08d7f100a7778cad8bd9939bf3255d4b` |
| `expected-reject-103.dat` | 430 | `96b82da8694345b16fbbaaad5e0844c8a57ceb154645cf06d0344d77d4f90bd5` |
| `expected-reject-summary.csv` | 190 | `b65ecdc674db73a32c8089a7a9155efdcfc86bdb2f8cc064f9f44123454c3dfa` |

Verify locally with:

```sh
cd src/test/resources/golden/reject
wc -c expected-reject-*.dat expected-reject-summary.csv
sha256sum expected-reject-*.dat expected-reject-summary.csv
```

The `.dat` fixtures can be regenerated **deterministically** from the `seed/posting/` records using
the `FixedWidthCodec` encoders, or an equivalent recipe:

1. ALPHANUMERIC fields → left-justify, space-pad (truncate on the right if too long).
2. NUMERIC fields → right-justify, zero-pad.
3. The amount field → overpunch the last digit per the tables in §2.
4. Encode the whole record in **ISO-8859-1** (1 char = 1 byte).
5. Reason code → `writeNumeric(code, 4)` at offset 350; description → `writeAlphanumeric(desc, 76)`
   at offset 354.
6. Emit **exactly** 430 bytes (or 80 for the 101 trailer) with **no** trailing newline.

---

## 9. Fidelity checklist (MANDATORY)

- [ ] **Byte-exact** records: 430 bytes for 100 / 102 / 103, and an **80-byte trailer** for 101.
- [ ] The embedded body is the **verbatim 350-byte input record** — same overpunch encoding as the
      seed input. **Do NOT** re-encode amounts as plain decimals in the fixed-width body.
- [ ] Reason code is zero-padded `PIC 9(04)` (e.g. `0100`, `0101`, `0102`, `0103`).
- [ ] Description is **left-justified, space-padded to 76** bytes, reproduced **verbatim** (matching
      `RejectCode.java` and the `.dat` fixtures).
- [ ] Preserve the **exact evaluation order**: 100 short-circuits the account lookup; 102-then-103
      are separate `IF`s with **last-writer-wins (103 beats 102)**.
- [ ] **No floating point** in any decoded value — `BigDecimal` at scale 2 only.
- [ ] **No secrets, no card CVV, no live credentials** anywhere. Card numbers in these fixtures are
      synthetic test PANs (`9000…`, `9999…`); no CVV or credential is present or permitted.
