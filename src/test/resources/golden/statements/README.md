# Golden statements — `CBSTM03A` / `CBSTM03B` parity fixtures

Expected (golden) account-statement outputs asserted by the statement parity tests in
`src/test/java` against the modernized `StatementGenerationService` (Spring Batch port of
the legacy COBOL statement producer `CBSTM03A.CBL` + file-I/O subroutine `CBSTM03B.CBL`).

Legacy specification (read-only): `legacy/app/cbl/CBSTM03A.CBL`, `legacy/app/cbl/CBSTM03B.CBL`,
`legacy/app/cpy/COSTM01.CPY` (transaction extract layout), `legacy/app/jcl/CREASTMT.JCL`
(DD names; `STMTFILE` LRECL=80 RECFM=FB, `HTMLFILE` LRECL=100 RECFM=FB).

## Files

| File | Kind | LRECL | Records | Size (bytes) | MD5 |
|------|------|-------|---------|--------------|-----|
| `acct-00000000050.statement.txt`  | plain text (`FD-STMTFILE-REC PIC X(80)`)  | 80  | 22 | 1782 | `b361344c8174e5e9aac060149f28f374` |
| `acct-00000000050.statement.html` | HTML (`FD-HTMLFILE-REC PIC X(100)`)        | 100 | 97 | 9797 | `2a5812001aae2be0c1c4828dbaaceaf1` |

Naming: `acct-<11-digit-account-id>.statement.<ext>`. Load from the test classpath as
`classpath:golden/statements/acct-00000000050.statement.txt` (and `.html`).

## Input case asserted by these goldens

Self-consistent triple from the legacy ASCII masters (mirrored verbatim in the sibling
folder `src/test/resources/fixtures/ascii/`):

- **Card-xref** (`cardxref.txt` rec #1): card `0500024453765740` -> customer `000000050` -> account `00000000050`.
- **Customer** (`custdata.txt` rec #50): `000000050` — Aniya Alba Von; 1588 Nienow Cape / Suite 187 /
  New Aricchester, OR USA 04257; FICO 623.
- **Account** (`acctdata.txt` rec #50): `00000000050` — current balance 492.00 (positive), status Y.

### Transactions (INVENTED — must be seeded by the test)

There is **no legacy TRANSACT master fixture** in this repo (only `dailytran.txt`), and
`CBSTM03A` reads the `TRNXFILE` extract built by `CREASTMT.JCL`. The parity test therefore
seeds the following three `TRNX-RECORD`s (layout per `COSTM01.CPY`), all keyed to
`TRNX-CARD-NUM = 0500024453765740` so they appear on account 00000000050's statement:

| `TRNX-ID`          | `TRNX-DESC`                   | `TRNX-AMT` |
|--------------------|-------------------------------|------------|
| `0010203040506070` | `POS PURCHASE - GROCERY MART` | `+123.45`  |
| `0010203040506071` | `ONLINE PURCHASE - BOOKSTORE` | `+67.89`   |
| `0010203040506072` | `PAYMENT - THANK YOU`         | `-50.00`   |

**Total EXP = +141.34.** Only `TRNX-ID`, `TRNX-DESC` (truncated to 49 chars) and `TRNX-AMT`
are rendered; matching is by `TRNX-CARD-NUM`. Remaining `TRNX-RECORD` fields are not shown
and may be any valid filler.

## Byte-exact parity contract (bug-for-bug; do not "fix")

- **Fixed widths:** `.txt` lines are exactly 80 bytes; `.html` lines are exactly 100 bytes
  (RECFM=FB). Lines are right-padded with spaces; **trailing spaces are significant**.
  Line endings are LF (`0x0A`); the file ends with a single trailing newline; ASCII, no BOM.
- **Decimal fidelity:** amounts use `BigDecimal` (scale 2), never float/double.
  - Current Balance — COBOL `PIC 9(9).99-`: leading zeros **kept** (`000000492.00` + trailing
    sign byte; SPACE for positive, `-` for negative).
  - Transaction / total amounts — COBOL `PIC Z(9).99-`: leading zeros **suppressed** to spaces
    (e.g. `      123.45 `), trailing `-` only for negatives (e.g. `       50.00-`).
- **Name / city truncation:** plain-text `ST-NAME` / `ST-ADD3` use `STRING ... DELIMITED BY ' '`
  (first token only) -> city `New Aricchester` renders as `New` (address line `New OR USA 04257`).
  HTML name/address fragments use `DELIMITED BY '  '` (two spaces). These quirks are intentional.
- **Numeric -> `PIC X(20)`** (Account ID, FICO): left-justified, space-padded.

## How the test consumes these files

1. Seed Testcontainers PostgreSQL with the master triple (from `fixtures/ascii/*`) and the three
   transactions above (account 00000000050 / card 0500024453765740).
2. Run `StatementGenerationService` (the `CBSTM03A` port) for this account.
3. Compare the produced statement bytes to the golden file. Recommended: assert equality of the
   full fixed-width record block (`START OF STATEMENT` ... `END OF STATEMENT` for the `.txt`;
   `<!DOCTYPE html>` ... `</html>` for the `.html`) including trailing-space padding to the LRECL.
   For robustness against accidental whitespace stripping, normalize by right-padding each actual
   line to the LRECL before comparison, or compare MD5 of the produced output to the values above.

## Notes

- **No build dependency** on sibling folders. These are independent static assets; the logical
  relationship to `fixtures/` is for case realism only.
- **Whitespace/EOL guard:** root `.gitattributes` should mark `src/test/resources/golden/**` as
  `-text` (or `text eol=lf` with whitespace checks off) so trailing spaces and LF endings survive
  commits and CI. (Owned by the build/config agent.)
- Generated locally by faithfully translating the documented COBOL formatting; no COBOL/mainframe
  runtime is required (AAP §0.6.7, local-only validation).
