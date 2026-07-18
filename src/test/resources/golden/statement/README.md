# Golden fixtures — StatementGenerationJob

The two **byte-exact EXPECTED outputs** that the `StatementGenerationJob` parity
test asserts its actual output against, row for row (AAP §0.9.2, §0.9.6). They
reproduce the legacy COBOL statement writer
[`legacy/cbl/CBSTM03A.CBL`](../../../../../legacy/cbl/CBSTM03A.CBL) (relocated
from the original `app/cbl/CBSTM03A.CBL`) and its called file-I/O subprogram
[`legacy/cbl/CBSTM03B.CBL`](../../../../../legacy/cbl/CBSTM03B.CBL) — the COBOL
`CALL 'CBSTM03B'` becomes an injected `StatementFileService` bean (AAP §0.5.7).
This is the **EXPECTED half** of the parity contract; the **INPUT half** lives in
the sibling [`seed/`](../../seed) folder. Together they make the statement job
independently reviewable (Explainability rule) without running the mainframe.

## Files

| File | Record layout | Records | Bytes | md5 |
|------|---------------|---------|-------|-----|
| `expected-statement.txt`  | `FD-STMTFILE-REC PIC X(80)` — every record exactly **80** bytes, space-padded  | 22 | 1760 | `7464f47963a3b2a27b4b87ea490041af` |
| `expected-statement.html` | `FD-HTMLFILE-REC PIC X(100)` — every record exactly **100** bytes, space-padded | 97 | 9700 | `8fbf26cceca7efc67c0277b3c1b38a37` |

sha256 — txt `1372a816705a1facfffe0e80123efd781b7c8c9a8bd391a9d1cacade6abdb481`;
html `bd6187bbb7a8b57effd9f5b55e7ebd5baee1294b671bbc6400a8bbcd3258ad47`.
Both files reproduce the COBOL `RECFM=FB` fixed-block contract: records are stored
**back-to-back with no in-band delimiter and no trailing newline** (no `0x0A`/`0x0D`
anywhere), so a file of *n* records is exactly *n* × width bytes (22 × 80 = 1760;
97 × 100 = 9700). Record boundaries are implied by the fixed record length alone.

## Bounded scenario (the exact input these outputs were derived from)

Any `seed/statement` fixture (or `@Sql` insert in the test) MUST reproduce this
scenario exactly, or the byte-for-byte comparison fails.

| Entity | Key | Fields |
|--------|-----|--------|
| Customer | `900000090` | first `John`, middle `Q`, last `Public` (renders `John Q Public`); addr1 `123 Main Street`, addr2 `Apt 4B`, city `Seattle`, state `WA`, country `USA`, zip `98101` (renders `Seattle WA USA 98101`); FICO `750` |
| Account | `90000000090` | current balance `+1234.56`; `group_id` must reference a valid `disclosure_group` row for the FK (e.g. `DEFAULT`) |
| Card | `9000000000000090` | `card_xref` maps card → customer `900000090` → account `90000000090` |

| `tran_id` | Description | Amount |
|-----------|-------------|--------|
| `0000000000000001` | Purchase at Store A | `+100.00` |
| `0000000000000002` | Purchase at Store B | `+250.50` |
| `0000000000000003` | Refund from Store C | `-25.75` |
| | **Total EXP** | **`324.75`** |

The identifiers are deliberately high and collision-free vs the sibling posting
(acct `90000000001`) and interest (acct `90000000010`) scenarios, consistent with
the `seed/README.md` ID convention (customers `9000000xx`, accounts
`900000000xx`, cards `90000000000000xx`).

## Record contracts & fidelity rules (AAP §0.7.1 H2/H3, §0.8.3, §0.9)

- **Byte-exact fixed-width.** Text records are 80 chars, HTML records 100 chars;
  column positions match the `CBSTM03A` `ST-LINE` (X80) and `HTML-FIXED-LN`
  (X100) layouts. A byte-count mismatch is a parity failure regardless of content.
- **Monetary edits (scale-2, compared to the cent, never floating point).**
  `ST-CURR-BAL` = PIC `9(9).99-` → `000001234.56 ` (trailing space = positive).
  `ST-TRANAMT` / `ST-TOTAL-TRAMT` = PIC `Z(9).99-` → `      100.00 `,
  `      250.50 `, `       25.75-` (trailing `-` = negative), total `      324.75 `.
- **Determinism.** The statement body carries **no run-clock timestamp**, so both
  outputs are fully deterministic and safe to assert byte-for-byte.
- **Security.** **No CVV and no password** ever appear in a statement — only
  account, customer, and transaction data. Enforced in application code and
  honored here.
- **Transaction grouping.** `CBSTM03A` groups transactions **by card** through the
  cross-reference (`WS-TRNX-TABLE` = `OCCURS 51` cards × an inner `OCCURS 10`
  transaction slots), so keep ≤ 10 transactions per card. The fixture reflects
  this card-grouped ordering.

## CRITICAL — test isolation

`CBSTM03A` emits **one statement per XREF record** it reads sequentially, so the
parity test MUST **isolate**: truncate-and-load ONLY this scenario's 1 customer /
1 account / 1 card / 1 xref / 3 transactions (per the `seed/README.md`
truncate-and-load convention) so the job produces exactly **one text statement +
one HTML statement** equal to these fixtures. If the full 50-card base seed is
present, the job emits 51 statements and the comparison fails. Static reference
tables (`transaction_type`, `transaction_category`, `disclosure_group`) are
provided by Flyway `V2`
([`V2__reference_data.sql`](../../../../main/resources/db/migration/V2__reference_data.sql)).

## Regenerate / verify

Both files are produced by a deterministic generator that simulates the
`CBSTM03A` `ST-LINE` (X80) and `HTML-FIXED-LN` (X100) layouts and the exact WRITE
order (no wall-clock inputs), so they can be re-verified at any time against the
checksums above:

```
wc -c expected-statement.txt expected-statement.html          # 1760 and 9700

# Verify fixed-width records AND the absence of any in-band delimiter (RECFM=FB).
# NOTE: line tools such as `awk '{print length}'` do NOT work here — the files carry
# no newline, so they must be sliced by fixed width, not by line terminator.
python3 - <<'PY'
for name, width in (("expected-statement.txt", 80), ("expected-statement.html", 100)):
    data = open(name, "rb").read()
    assert len(data) % width == 0, f"{name}: not a multiple of {width}"
    assert data.count(0x0A) == 0 and data.count(0x0D) == 0, f"{name}: stray delimiter byte"
    print(f"{name}: {len(data)} bytes = {len(data)//width} records of {width}")
PY

md5sum expected-statement.txt expected-statement.html          # 7464f479... / 8fbf26cc...
sha256sum expected-statement.txt expected-statement.html       # 1372a816... / bd6187bb...
```

Below are the two load-bearing COBOL numeric edits the generator applies,
reproduced with scale-2 `Decimal` (never float — matching the `Money` value
object); the full driver emits every `ST-LINE` / `HTML-FIXED-LN` template in
`CBSTM03A` WRITE order and pads each record to width to produce both files:

```python
from decimal import Decimal

def pic_9_99_minus(a):   # ST-CURR-BAL  PIC 9(9).99-  -> 13 chars: zero-filled + trailing sign
    n = a < 0; c = int((abs(a) * 100).to_integral_value())
    return f"{c // 100:09d}.{c % 100:02d}" + ("-" if n else " ")
def pic_z_99_minus(a):   # ST-TRANAMT / ST-TOTAL-TRAMT  PIC Z(9).99-  -> zero-suppressed + sign
    n = a < 0; c = int((abs(a) * 100).to_integral_value())
    return f"{c // 100:d}".rjust(9) + f".{c % 100:02d}" + ("-" if n else " ")

# pic_9_99_minus(Decimal("1234.56")) -> "000001234.56 "
# pic_z_99_minus(Decimal("100.00"))  -> "      100.00 "   (positive: trailing space)
# pic_z_99_minus(Decimal("-25.75"))  -> "       25.75-"   (negative: trailing '-')
```

## Coordination

The EXPECTED outputs here are derived by applying the preserved COBOL logic to the
INPUT fixtures; the sibling [`seed/`](../../seed) must supply the matching input
rows — a future `seed/statement/` scenario or direct entity / `@Sql` inserts in
the test — a parent-level ordering dependency (author or regenerate the seed input
first, then regenerate these fixtures from it). The
`src/test/java/com/aws/carddemo/**` parity test consumes these fixtures at runtime
for the row-for-row assertions. This subfolder has no first-order sibling
dependencies (`depends_on_folders` empty). Intentional deviations (e.g. `HALF_UP`
rounding elsewhere) are recorded in
[`docs/decision-log.md`](../../../../../docs/decision-log.md); the statement
scenario itself involves no rounding boundary.
