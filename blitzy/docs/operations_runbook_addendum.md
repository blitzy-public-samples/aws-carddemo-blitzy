# Operations Runbook Addendum — CBTRN02C Inactive-Account Rejection (Reason Code 104)

**Scope:** Batch transaction posting — job `POSTTRAN`, step `STEP15`, program `CBTRN02C`
**Change reference:** Fix commit `c3a0a32d` — *"Fix CBTRN02C: reject transactions targeting non-active accounts"*
**Audience:** Batch operations, on-call SREs, scheduling (Control‑M / CA‑7) administrators, and the application maintenance team
**Status:** Active — applies to every `POSTTRAN` run after the patched load module is deployed

---

## 1. Summary of the Change

`CBTRN02C` posts each daily transaction from `DALYTRAN` to the transaction master
(`TRANSACT.VSAM.KSDS`) and applies the amount to the account master (`ACCTFILE`)
and the transaction‑category balance file (`TCATBALF`). Prior to this change the
account‑validation paragraph `1500-B-LOOKUP-ACCT` checked only the credit limit
and the expiration date; it never inspected the account‑active flag. As a result,
transactions targeting **inactive or closed accounts were silently posted**.

The fix adds a single guard in `1500-B-LOOKUP-ACCT`: if the account's
`ACCT-ACTIVE-STATUS` byte is **not** `'Y'` (active), the transaction is rejected
with **reason code `104` / `ACCOUNT NOT ACTIVE`** and written to the reject file
`DALYREJS` instead of being posted. Active (`'Y'`) accounts are unaffected and
post exactly as before.

**Operational consequences (what's new for operations):**

1. A new reject **reason code `104`** now appears in `DALYREJS`. See §2.
2. Step `STEP15` will return **`RC=4`** on any day that contains one or more
   inactive‑account transactions. **`RC=4` is expected and normal — it is not a
   failure.** See §3.
3. The daily `DALYREJS` reject dataset may be non‑empty on days when it was
   previously empty, because inactive‑account transactions are now diverted there.
   See §4.

---

## 2. Reason Code 104 — `ACCOUNT NOT ACTIVE`

### 2.1 When it fires

Reason code `104` is assigned when **all** of the following are true:

- The transaction's card number is found in the cross‑reference file
  (`XREFFILE`), i.e. reason `100` did not fire.
- The cross‑referenced account exists in `ACCTFILE`, i.e. reason `101` did not fire.
- The account's `ACCT-ACTIVE-STATUS` field is **any value other than `'Y'`**.

Because the comparison is the defensive form `ACCT-ACTIVE-STATUS NOT = 'Y'`, the
following account states are all rejected with reason `104`:

| `ACCT-ACTIVE-STATUS` value | Meaning                         | Result      |
|----------------------------|---------------------------------|-------------|
| `'Y'`                      | Active                          | **Posted**  |
| `'N'`                      | Inactive (canonical convention) | Reject 104  |
| `'C'`                      | Closed                          | Reject 104  |
| `' '` (blank)              | Unset / undefined               | Reject 104  |
| `LOW-VALUES` / any other   | Corrupt / unexpected            | Reject 104  |

When reason `104` fires, the credit‑limit (`102`) and expiration (`103`) checks
are intentionally **short‑circuited** — once an account is non‑active, the more
granular over‑limit / expired reasons are immaterial, and `104` is reported as
the primary failure.

### 2.2 Complete reject reason‑code catalog (for operator reference)

| Reason | Description (as written to `DALYREJS`)        | Trigger                                                            |
|:------:|-----------------------------------------------|-------------------------------------------------------------------|
| `0100` | `INVALID CARD NUMBER FOUND`                   | Card number not present in `XREFFILE`                             |
| `0101` | `ACCOUNT RECORD NOT FOUND`                    | Cross‑referenced account not present in `ACCTFILE`               |
| `0102` | `OVERLIMIT TRANSACTION`                       | Active account, but the transaction would exceed the credit limit |
| `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`  | Active account, but transaction date is after the expiration date |
| `0104` | `ACCOUNT NOT ACTIVE` **(new)**                | Account exists but `ACCT-ACTIVE-STATUS` is not `'Y'`              |
| `0109` | `ACCOUNT RECORD NOT FOUND`                    | Account `REWRITE` failed during posting (rare; abend path)        |

### 2.3 Reject record layout (`DALYREJS`)

`DALYREJS` is a sequential dataset with fixed‑length **430‑byte** records
(`RECFM=F,LRECL=430`). Each reject record is the original 350‑byte transaction
payload followed by an 80‑byte validation trailer:

```
 Bytes   1 – 350 : Original DALYTRAN record (transaction payload, verbatim)
 Bytes 351 – 354 : Reason code      (4 numeric digits, zero-padded)  e.g. "0104"
 Bytes 355 – 430 : Reason text      (76 chars, left-justified, space-padded)
```

For an inactive‑account reject, bytes 351–354 contain `0104` and bytes 355–430
begin with `ACCOUNT NOT ACTIVE`.

### 2.4 Inspecting a reject (z/OS)

- Browse the cataloged `DALYREJS` GDG generation (see §3.2 for the DSN) in
  ISPF (option 3.4 → browse), or use `IDCAMS PRINT` / `IEBPTPCH`.
- The transaction ID is in bytes 1–16; the reason code is at bytes 351–354.
- To triage why an account was rejected with `0104`, look up the account in the
  online account‑view screen (`COACTVWC`) or via `READACCT` and confirm its
  active‑status flag. If the account *should* be active, the data‑quality issue
  is in `ACCTFILE`, not in `CBTRN02C`.

---

## 3. Return‑Code Semantics for `POSTTRAN.STEP15`

### 3.1 RC=4 is expected and normal when rejects exist — it is **not** a failure

`CBTRN02C` sets the step return code based on the reject counter:

| Step `STEP15` RC | Meaning                                                                 | Operator action                                              |
|:----------------:|-------------------------------------------------------------------------|--------------------------------------------------------------|
| `0`              | All transactions posted; **zero** rejects                               | None — normal completion                                     |
| `4`              | Run completed successfully; **one or more** transactions were rejected  | **None required.** Review `DALYREJS` during the daily check (§4). This is the normal signal that the reject file is populated — *not* a job failure. |
| `≥ 8` / abend    | A genuine I/O or processing error occurred (e.g., `Sxxx` / `U0999`)     | Treat as a real failure; follow standard abend triage (§6).  |

> **Important for schedulers (Control‑M / CA‑7):** Step `STEP15` may now return
> `RC=4` on any business day that includes inactive‑account transactions, where
> previously it may have returned `RC=0`. **Do not flag `RC=4` as an abend or a
> step failure.** Ensure the condition‑code / completion logic for
> `POSTTRAN STEP15` treats `RC ≤ 4` as a successful completion and only escalates
> on `RC ≥ 8` (or an abend). Downstream steps/jobs (`INTCALC`, `CREASTMT`) should
> be allowed to proceed normally on `RC=4`.

### 3.2 Datasets referenced by `STEP15` (from `app/jcl/POSTTRAN.jcl`)

| DD name    | Disposition / type      | Dataset                                       |
|------------|-------------------------|-----------------------------------------------|
| `STEPLIB`  | `DISP=SHR` (load lib)   | `AWS.M2.CARDDEMO.LOADLIB`                      |
| `DALYTRAN` | `DISP=SHR` (input, PS)  | `AWS.M2.CARDDEMO.DALYTRAN.PS`                  |
| `XREFFILE` | `DISP=SHR` (input, KSDS)| `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`           |
| `ACCTFILE` | `DISP=SHR` (I‑O, KSDS)  | `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`           |
| `TCATBALF` | `DISP=SHR` (I‑O, KSDS)  | `AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS`           |
| `TRANFILE` | `DISP=SHR` (output, KSDS)| `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS`          |
| `DALYREJS` | `NEW,CATLG,DELETE` (GDG)| `AWS.M2.CARDDEMO.DALYREJS(+1)` — `RECFM=F,LRECL=430` |

---

## 4. Daily SYSOUT / Operations Checklist

Perform these checks for every `POSTTRAN` run after the step completes.

**A. Confirm the program ran to completion (STEP15 SYSOUT / SYSPRINT):**

- [ ] `START OF EXECUTION OF PROGRAM CBTRN02C` is present.
- [ ] `END OF EXECUTION OF PROGRAM CBTRN02C` is present.
- [ ] `ABENDING PROGRAM` is **not** present (its presence indicates a real abend — go to §6).

**B. Read the run counters (printed near the end of SYSOUT):**

- [ ] `TRANSACTIONS PROCESSED :nnnnnnnnn` — total daily transactions read.
- [ ] `TRANSACTIONS REJECTED  :nnnnnnnnn` — total rejected (all reasons).
- [ ] Arithmetic sanity: `processed = posted + rejected`.

**C. Interpret the return code:**

- [ ] `RC=0` → no rejects; `DALYREJS` generation will be empty.
- [ ] `RC=4` → rejects occurred (**expected/normal**); continue to step D.
- [ ] `RC≥8` or abend → escalate per §6.

**D. Review the reject file (`DALYREJS`) when `REJECTED > 0`:**

- [ ] Browse the new `DALYREJS` GDG generation.
- [ ] Tally reason codes at bytes 351–354. A spike in `0104` (`ACCOUNT NOT
      ACTIVE`) means more inactive/closed accounts than usual were targeted.
- [ ] If the `0104` volume is unexpectedly high, raise a data‑quality query
      with the account‑maintenance team — the transactions are correctly being
      withheld; the question is *why* so many target non‑active accounts (e.g.,
      a stale upstream card/account feed).
- [ ] `0104` rejects require **no reprocessing** by operations. They are not
      eligible for re‑posting until/unless the underlying account is reactivated
      by the business, at which point the transaction would be resubmitted
      through the normal daily feed.

**E. Confirm downstream integrity (informational):**

- [ ] Because inactive‑account transactions no longer reach `TRANSACT.VSAM.KSDS`,
      the statement generator (`CBSTM03A`) and the interest calculator
      (`CBACT04C` / `INTCALC`) now operate on a clean transaction/account master.
      No special action is required; this is the intended business benefit.

---

## 5. Deployment — Recompile & Link‑Edit the Patched Load Module (z/OS)

The fix is confined to the single source member `CBTRN02C`. Deployment is a
standard recompile + link‑edit of that one batch program into the application
load library; **no copybook, JCL, CSD, or sibling‑program change is required.**

### 5.1 Pre‑deployment verification (off‑platform, optional but recommended)

A GnuCOBOL regression test is provided to validate the fix on Linux before the
mainframe build:

```bash
# From the repository root:
./tests/test_cbtrn02c.sh
# Expected: "RESULT: PASS - reason code 104 (ACCOUNT NOT ACTIVE) verified", exit 0
```

The script compiles `app/cbl/CBTRN02C.cbl`, simulates the VSAM/PS datasets with
flat files, runs the module, and asserts that inactive/closed/blank accounts are
rejected with `0104` while active accounts post normally.

### 5.2 Mainframe build (Enterprise COBOL)

The project's batch compile is driven by `samples/jcl/BATCMP.jcl`, which invokes
the `BUILDBAT` PROC (`samples/proc/BUILDBAT.prc`). The PROC runs the COBOL
compiler `IGYCRCTL` (`PARM=(APOST,LIST,MAP,NUMBER,NOSEQ)`) and then the binder
`HEWL` (`PARM='LIST,XREF'`, gated by `COND=(8,LT,COMPILE)`), placing the load
module into `&HLQ..CARDDEMO.LOADLIB`.

1. Promote the patched `CBTRN02C` source into the controlled source library
   (`AWS.M2.CARDDEMO.CBL(CBTRN02C)`) per your change‑management process.
2. Edit `BATCMP.jcl` and set the member name to `CBTRN02C`:
   ```
   //   SET MEMNAME=CBTRN02C
   //   SET HLQ=AWS.M2
   ```
   (Confirm `HLQ` and the `JCLLIB ORDER=&HLQ..CARDDEMO.PRC.UTIL` reference match
   your environment.)
3. Submit `BATCMP.jcl`.
4. Verify the build:
   - [ ] `COMPILE` step (`IGYCRCTL`) ends with `RC=0` (warnings are acceptable;
         note that `CBTRN02C` carries pre‑existing informational warnings — there
         must be **no new errors** introduced by the fix).
   - [ ] `LKED` step (`HEWL`) ends with `RC=0` and reports the load module
         written to `AWS.M2.CARDDEMO.LOADLIB`.
   - [ ] Confirm member `CBTRN02C` in `AWS.M2.CARDDEMO.LOADLIB` shows a refreshed
         timestamp.

### 5.3 Activation

- `POSTTRAN STEP15` STEPLIBs `AWS.M2.CARDDEMO.LOADLIB`, so the **next** scheduled
  `POSTTRAN` run automatically loads the patched module — no JCL edit is needed.
- `CBTRN02C` is a **batch** program (executed via JCL, not CICS). **No CICS
  `NEWCOPY`/`PHASEIN` and no CSD refresh is required.** If a long‑running region
  has the module cached for any reason, ensure no `POSTTRAN` instance is active
  during the load‑library update.

### 5.4 Post‑deployment confirmation

On the first production `POSTTRAN` run after deployment:

- [ ] Run the §4 daily checklist.
- [ ] If the day's input contains known inactive‑account transactions, confirm
      they appear in `DALYREJS` with reason `0104` and **do not** appear in
      `TRANSACT.VSAM.KSDS`, and that `STEP15` returned `RC=4`.

---

## 6. Troubleshooting & Rollback

### 6.1 Genuine failure indicators (escalate)

- `STEP15` ends with `RC≥8`, or an abend (e.g., a system completion code such as
  `S0C7`, or the user abend issued via `CEE3ABD` when an I/O error occurs).
- SYSOUT contains `ABENDING PROGRAM` together with an `ERROR ...` line and a
  `FILE STATUS IS: NNNN` value. The file‑status value identifies the failing DD
  (e.g., a VSAM open/read/write failure). These are **not** related to reason
  code `104`; follow standard batch‑abend triage (verify VSAM dataset
  availability, catalog, RACF access, and space).

### 6.2 "Why is `DALYREJS` suddenly non‑empty / why RC=4 now?"

This is the expected behavior of the fix. Inactive/closed‑account transactions
that were previously (incorrectly) posted are now diverted to `DALYREJS` with
reason `0104`, and the presence of any reject sets `RC=4`. No action is required
beyond the daily review in §4.

### 6.3 Rollback

If a rollback is ever required (e.g., emergency change reversal):

1. Re‑link the previous `CBTRN02C` load module into `AWS.M2.CARDDEMO.LOADLIB`
   (restore the prior member from your load‑library backup / change record), or
   recompile the pre‑fix source via §5.2.
2. **Caution:** rolling back re‑introduces the original defect — inactive‑account
   transactions will again post to `TRANSACT.VSAM.KSDS` and update `ACCTFILE` /
   `TCATBALF`. Coordinate with the business before reverting, and plan a data
   remediation for any inactive‑account postings created while reverted.

---

## 7. Quick Reference

- **Program / paragraph:** `CBTRN02C` → `1500-B-LOOKUP-ACCT`
- **Job / step:** `POSTTRAN` / `STEP15` (`app/jcl/POSTTRAN.jcl`)
- **New reason:** `0104` = `ACCOUNT NOT ACTIVE`
- **Reject file:** `AWS.M2.CARDDEMO.DALYREJS(+1)`, `RECFM=F,LRECL=430`
  (reason at bytes 351–354, text at bytes 355–430)
- **Expected RC with rejects:** `RC=4` (normal — not a failure)
- **Build:** `samples/jcl/BATCMP.jcl` (`SET MEMNAME=CBTRN02C`) → `IGYCRCTL` +
  `HEWL` into `AWS.M2.CARDDEMO.LOADLIB`
- **Off‑platform regression test:** `tests/test_cbtrn02c.sh`
