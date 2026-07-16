# Common Pitfalls

These are the traps most likely to introduce a silent behavioral regression in
this migration. Every item is grounded in the legacy source; consult the cited
authority under [`legacy/`](../../legacy) before changing related code.

---

## 1. Never edit the legacy source (byte-exact authorities)

Everything under [`legacy/`](../../legacy) is retained **read-only** as the
authoritative reference. The data files in
[`legacy/data/EBCDIC/`](../../legacy/data/EBCDIC),
[`legacy/data/ASCII/`](../../legacy/data/ASCII), and
[`legacy/catlg/LISTCAT.txt`](../../legacy/catlg/LISTCAT.txt) are protected by
[`.gitattributes`](../../.gitattributes):

- EBCDIC datasets are marked `binary` — Git must never apply text/EOL
  transformation.
- ASCII fixed-width files and the catalog are marked `-text` so CRLF conversion
  can never shift columns.

Content hashes for these artifacts are catalogued in the
[traceability matrix](../traceability-matrix.md); CI verifies them. If a diff ever
appears on a `legacy/data/**` file, it is a mistake — revert it.

---

## 2. Money is `BigDecimal`, never `double`/`float`

All monetary fields are COBOL packed decimal (`COMP-3`, `PIC S9(n)V99`). Model
them as `BigDecimal` at **scale 2** with an explicit `RoundingMode`, backed by
`DECIMAL(x,2)` columns. Floating point is prohibited — it cannot represent these
values exactly and drift compounds across postings.

The interest computation must reproduce the COBOL `COMPUTE` **to the cent**
(`CBACT04C`):

```
monthlyInterest = tranCatBal.multiply(intRate)
                            .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)
```

Centralize monetary arithmetic in the `Money` value object and cover it with
golden-file tests.

---

## 3. The chronological transaction index is on `proc_ts`, not `orig_ts`

`CVTRA05Y` defines **two** 26-character timestamps: `TRAN-ORIG-TS` (origination,
offset 278) and `TRAN-PROC-TS` (**processing**, offset 304). The VSAM alternate
index `TRANSACT.VSAM.AIX` used for chronological browsing is keyed on the
**processing** timestamp — [`legacy/catlg/LISTCAT.txt`](../../legacy/catlg/LISTCAT.txt)
shows `KEYLEN=26` at `AXRKP=304`, i.e. `TRAN-PROC-TS`, mapping to the `proc_ts`
column.

**Pitfall:** ordering transactions by `orig_ts` looks plausible but is the wrong
browse order and will fail golden-file parity. The chronological index and all
"list transactions" queries must order by **`proc_ts`**.

---

## 4. `account.group_id` is NOT a foreign key to `disclosure_group`

The disclosure-group record (`CVTRA02Y`, 50 B) has a **composite** primary key:
group id (`DIS-ACCT-GROUP-ID`, 10) + transaction type (`DIS-TRAN-TYPE-CD`, 2) +
transaction category (`DIS-TRAN-CAT-CD`, 4). An account (`CVACT01Y`, 300 B)
carries only `ACCT-GROUP-ID` (10). Because a group id **alone is not unique**, it
**cannot** be a foreign-key target.

**Pitfall:** declaring `account.group_id → disclosure_group(group_id)` is
impossible against the real key and inventing a synthetic single-column parent
the legacy never had would be an unfaithful change. **Correct design:** keep
`group_id` as a plain grouping attribute on `account`, and resolve the applicable
disclosure row with a composite `(group_id, type_cd, cat_cd)` query at the
application layer. This is recorded as an intentional, source-faithful deviation
in the [decision log](../decision-log.md).

---

## 5. Seed data is fixed-width, headerless — not CSV

Files in [`legacy/data/ASCII/`](../../legacy/data/ASCII) are **fixed-width and
headerless**. Each record's width equals its copybook record length (e.g.
`acctdata` 300, `carddata` 150, `custdata` 500, `dailytran` 350, `cardxref` 36).
There are no delimiters and no header row.

**Pitfall:** parsing them as CSV/delimited silently corrupts every field. Parse
by **fixed column positions** per the copybook, and preserve the copybook's space
(alphanumeric) and zero (numeric) padding when loading and when writing external
files.

---

## 6. The posting reject record is 430 bytes; order and RC matter

`CBTRN02C` writes rejected daily transactions to DALYREJS. The reject record is
**430 bytes = 350-byte transaction image + 80-byte validation trailer** (the
`POSTTRAN.jcl` DALYREJS DD is `RECFM=F,LRECL=430`).

**Pitfalls:**
- Sizing the reject record at 350 bytes (the transaction image alone) drops the
  80-byte trailer — the external file contract breaks.
- Reject codes are assigned in a **fixed validation order**: **100** cross-ref not
  found → **101** account not found → **102** over credit limit → **103**
  transaction after account expiration. Reordering changes which code a record
  receives.
- The posting job returns **RC=4** when any reject occurs; preserve the running
  reject count and the write ordering.

---

## 7. Legacy source anomalies — classify, never fix

Several legacy files contain pre-existing anomalies. They are **immutable
reference**: reproduce/allow-for them in fixtures and tests, but **never edit the
legacy bytes**.

| File | Anomaly |
|------|---------|
| `legacy/jcl/CREASTMT.JCL` | malformed/garbled DD continuation line; also the only **uppercase** `.JCL` (the other 28 are lowercase `.jcl`) |
| `legacy/jcl/DEFCUST.jcl` | duplicate `//STEP05`; obsolete high-level-qualifier dataset name |
| `legacy/jcl/OPENFIL.jcl` | job name misspelled `//OEPNFIL` |
| `legacy/jcl/TRANREPT.jcl` | duplicate `//STEP05R` (PROC invocation then inline `PGM=SORT`) |
| `legacy/proc/TRANREPT.prc` | declares `//REPROC PROC` — member name differs from proc name |

When two paths disagree (e.g. TRANREPT's PROC invocation vs. its inline SORT
step), pick the authoritative behavior explicitly and record the choice in the
[traceability matrix](../traceability-matrix.md) / [decision log](../decision-log.md)
— without touching the legacy file.

---

## 8. CSD entries that are not production surfaces

[`legacy/csd/CARDDEMO.CSD`](../../legacy/csd/CARDDEMO.CSD) registers 18
transactions / 18 programs, but transaction `CDV1` → program `COCRDSEC` has **no
COBOL implementation** in `legacy/cbl/`. It is a **source-only / reference-only**
registry entry — do not build a controller for it. The online surface set is the
**17** implemented programs. `LIBRARY` and `TDQUEUE` entries in the CSD are
likewise reference-only. `legacy/jcl/CBADMCDJ.jcl` is a stale alternate-CSD
catalog loader — **historical/reference-only**, not part of the CI/CD job set.

---

## 9. EBCDIC vs ASCII, aliases, and snapshot counts

- The EBCDIC datasets are on-mainframe **binary** snapshots (`COMP-3` packed,
  EBCDIC-encoded). The Java/PostgreSQL target stores native types; external file
  exchange preserves the documented **layout**, not the on-disk encoding.
- **`ACCDATA`** in [`legacy/data/EBCDIC/`](../../legacy/data/EBCDIC) is a
  **byte-exact alias** of `ACCTDATA`; only `ACCTDATA.VSAM.KSDS` is authoritative —
  do not seed the account table twice.
- **Users** exist only in the EBCDIC `USRSEC` dataset (no `usrsec.txt` in ASCII),
  so `user_security` seeds from EBCDIC.
- ASCII (seed snapshot) and EBCDIC (live) row counts can differ (e.g. `cardxref`
  is 36 bytes/record in ASCII with trailing filler trimmed vs. 50 bytes in
  EBCDIC). Treat each artifact as a point-in-time snapshot; exact widths, counts,
  and hashes are in the [traceability matrix](../traceability-matrix.md).

---

## 10. Namespace, imports, injection, and the build gate

- **Jakarta only** (`jakarta.*`) — Spring Boot 3.x. Never `javax.*`.
- **No wildcard imports**; explicit imports keep the build warning-free.
- **Constructor injection only** — no field `@Autowired`.
- The build must stay at **zero warnings** and **≥ 80%** line coverage, and OWASP
  dependency-check must report **zero critical/high** CVEs. Do not weaken these
  gates to make a change pass — fix the root cause.

---

See also [Extending the application](./extending.md) and
[Domain context](./domain-context.md).
