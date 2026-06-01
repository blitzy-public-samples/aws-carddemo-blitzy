# PR Review Checklist — CBTRN02C Inactive-Account Rejection (Reason Code 104)

**Scope:** Batch transaction posting — job `POSTTRAN`, step `STEP15`, program `CBTRN02C`
**Change reference:** Fix commit `c3a0a32d` — *"Fix CBTRN02C: reject transactions targeting non-active accounts"*
**Source file changed:** `app/cbl/CBTRN02C.cbl` (paragraph `1500-B-LOOKUP-ACCT`)
**Audience:** Senior COBOL architect / code reviewer signing off the pull request before merge to `main`
**How to use:** Work top to bottom. Every box must be checked `[x]` before approval. Each section is
self-contained — the exact lines, commands, and expected outputs are reproduced here so the reviewer
does not need to open any other document.

---

## 0. What the change does (one-paragraph orientation)

`CBTRN02C` posts each daily transaction from `DALYTRAN` to the transaction master (`TRANSACT.VSAM.KSDS`)
and applies the amount to the account master (`ACCTFILE`) and the transaction-category balance file
(`TCATBALF`). Before the fix, the account-validation paragraph `1500-B-LOOKUP-ACCT` checked only the
credit limit (reason `102`) and the expiration date (reason `103`); it never inspected the
account-active flag, so transactions targeting **inactive or closed accounts were silently posted**.
The fix adds one guard in `1500-B-LOOKUP-ACCT`: if `ACCT-ACTIVE-STATUS` is **not** `'Y'`, the
transaction is rejected with **reason code `104` / `ACCOUNT NOT ACTIVE`** and written to `DALYREJS`
instead of being posted. The original credit-limit and expiration checks are preserved **verbatim**,
relocated unchanged into the `ELSE` (active-account) branch of the new guard.

---

## 1. Verify the `IF/ELSE` wrapper preserves the original logic inside the `ELSE` branch

The single in-scope edit wraps the pre-existing validation logic in a new outer
`IF ACCT-ACTIVE-STATUS NOT = 'Y' ... ELSE ... END-IF`. The reviewer must confirm that the original
logic was **moved into the `ELSE` branch unchanged** — not rewritten, reordered, or weakened.

Open `app/cbl/CBTRN02C.cbl` and read paragraph `1500-B-LOOKUP-ACCT` (lines `393`–`435`). Confirm each
of the following:

- [ ] **1.1** The `READ ACCOUNT-FILE INTO ACCOUNT-RECORD` (line `395`) and its `INVALID KEY` branch
  setting reason `101` (line `397`) are **unchanged** above the new guard.
- [ ] **1.2** The new guard `IF ACCT-ACTIVE-STATUS NOT = 'Y'` appears at the **top** of the
  `NOT INVALID KEY` branch (line `410`), i.e. it is evaluated **before** any credit-limit or expiration
  logic.
- [ ] **1.3** The guard's *true* branch (lines `411`–`413`) does **only** two things: `MOVE 104 TO
  WS-VALIDATION-FAIL-REASON` and `MOVE 'ACCOUNT NOT ACTIVE' TO WS-VALIDATION-FAIL-REASON-DESC`. It does
  **not** post, REWRITE, or write any file directly.
- [ ] **1.4** The `ELSE` keyword appears at line `414`, and everything from the original logic now lives
  **inside** that `ELSE`:
  - [ ] `COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT` (line `415`)
    — operands and operators **identical** to the pre-fix version.
  - [ ] Credit-limit check `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` (line `419`) → `CONTINUE`, `ELSE`
    reason `102` `OVERLIMIT TRANSACTION` (line `422`), closed by `END-IF` (line `425`) — **identical**
    comparison operator (`>=`) and reason text.
  - [ ] Expiration check `IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)` (line `426`) → `CONTINUE`,
    `ELSE` reason `103` `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` (line `429`), closed by `END-IF`
    (line `432`) — **identical** comparison operator, reference-modification `(1:10)`, and reason text.
- [ ] **1.5** The new outer guard is closed by its own `END-IF` at line `433`, and the paragraph still
  ends with the original `END-READ` (line `434`) and `EXIT.` (line `435`). Confirm the `IF`/`END-IF`
  nesting balances (1 new outer `IF` + 2 inner `IF`s = 3 `IF`, matched by 3 `END-IF`).
- [ ] **1.6** Confirm the active path is **behaviour-preserving**: for an account with
  `ACCT-ACTIVE-STATUS = 'Y'`, control falls through the `ELSE` and executes exactly the same
  `COMPUTE` + credit-limit + expiration sequence as before the fix (no extra side effects, no skipped
  checks).
- [ ] **1.7** Confirm the defensive form `NOT = 'Y'` (not `= 'N'`). This correctly rejects `'N'`
  (inactive), `'C'` (closed), space, `LOW-VALUES`, and any corrupt byte — a fail-safe posture.

> **Quick visual reference (current line numbers):**
>
> ```cobol
> 410  IF ACCT-ACTIVE-STATUS NOT = 'Y'           <- NEW guard
> 411     MOVE 104 TO WS-VALIDATION-FAIL-REASON
> 412     MOVE 'ACCOUNT NOT ACTIVE'
> 414  ELSE                                       <- original logic, unchanged, lives here
> 415     COMPUTE WS-TEMP-BAL = ...
> 419     IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL ... reason 102 ... END-IF (425)
> 426     IF ACCT-EXPIRAION-DATE >= ...        ... reason 103 ... END-IF (432)
> 433  END-IF                                     <- closes the new guard
> ```

---

## 2. Confirm all six reason codes (100–104, 109) are intact, with exact line numbers

The fix must **add** reason `104` and leave every other reason code untouched. Verify each row below by
opening `app/cbl/CBTRN02C.cbl` at the stated line, or by running the grep in §2.1.

| Reason | Description literal | `MOVE nnn` line | Paragraph | Expectation |
|:------:|---------------------|:---------------:|-----------|-------------|
| `100` | `INVALID CARD NUMBER FOUND` | **L385** | `1500-A-LOOKUP-XREF` | unchanged (pre-existing) |
| `101` | `ACCOUNT RECORD NOT FOUND` | **L397** | `1500-B-LOOKUP-ACCT` (`INVALID KEY`) | unchanged (pre-existing) |
| `102` | `OVERLIMIT TRANSACTION` | **L422** | `1500-B-LOOKUP-ACCT` (inside new `ELSE`) | preserved, relocated into `ELSE` |
| `103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | **L429** | `1500-B-LOOKUP-ACCT` (inside new `ELSE`) | preserved, relocated into `ELSE` |
| `104` | `ACCOUNT NOT ACTIVE` | **L411** | `1500-B-LOOKUP-ACCT` (new guard) | **NEW — this is the fix** |
| `109` | `ACCOUNT RECORD NOT FOUND` | **L569** | `2800-UPDATE-ACCOUNT-REC` (`REWRITE INVALID KEY`) | unchanged (pre-existing) |

- [ ] **2.0** All six rows verified at the exact line numbers above. No reason code other than `104`
  was added, renumbered, removed, or had its description text altered.

### 2.1 One-shot grep to confirm the reason-code inventory

Run from the repository root:

```bash
grep -nE "MOVE 1(0[0-4]|09) TO WS-VALIDATION-FAIL-REASON" app/cbl/CBTRN02C.cbl
```

- [ ] **2.1** Output lists **exactly six** lines, and the line numbers match the table:

  ```text
  385:                MOVE 100 TO WS-VALIDATION-FAIL-REASON
  397:                MOVE 101 TO WS-VALIDATION-FAIL-REASON
  411:                   MOVE 104 TO WS-VALIDATION-FAIL-REASON
  422:                     MOVE 102 TO WS-VALIDATION-FAIL-REASON
  429:                     MOVE 103 TO WS-VALIDATION-FAIL-REASON
  569:                MOVE 109 TO WS-VALIDATION-FAIL-REASON
  ```

  > Note: the line numbers may shift by a few lines if the file is edited further. What must hold is
  > that **all six codes are present**, `104` lives in `1500-B-LOOKUP-ACCT` ahead of `102`/`103`, and
  > no extra reason codes appear.

---

## 3. Run the three verification commands

Run all three from the repository root. The compile flags (`-std=ibm -I app/cpy -I app/cpy-bms`) are the
project-standard flags used for every batch program.

### 3.1 Syntax check — must report **no errors** (exit code 0)

```bash
cobc -fsyntax-only -std=ibm -I app/cpy -I app/cpy-bms app/cbl/CBTRN02C.cbl
echo "exit=$?"
```

- [ ] **3.1** Command prints `exit=0` and emits no error lines.

### 3.2 Warning count — must be **0**

```bash
cobc -fsyntax-only -std=ibm -I app/cpy -I app/cpy-bms app/cbl/CBTRN02C.cbl 2>&1 \
  | grep -ci "warning"
```

- [ ] **3.2** Command prints `0` (zero new warnings introduced by the fix).

### 3.3 Changed-files scope — only the in-scope file under `app/`

```bash
git diff --name-only origin/main...HEAD
```

- [ ] **3.3** Under `app/`, the **only** changed file is `app/cbl/CBTRN02C.cbl`. Confirm that **no**
  other COBOL program (`CBSTM03A`, `CBACT04C`, the `CO*` online programs, sibling `CBTRN0xC`), **no**
  copybook (`app/cpy/*`, e.g. `CVACT01Y.cpy`), **no** JCL (`app/jcl/*`, e.g. `POSTTRAN.jcl`), and
  **no** CSD/BMS asset appears in the list. (Non-`app/` additions such as
  `.github/workflows/cbtrn02c-regression.yml`, `tests/test_cbtrn02c.sh`, and `blitzy/docs/*` are the
  CI/test/documentation artifacts for this change and are expected.)

  > If you only want to see the in-scope source tree, filter the diff:
  >
  > ```bash
  > git diff --name-only origin/main...HEAD -- app/
  > ```
  >
  > Expected output: a single line — `app/cbl/CBTRN02C.cbl`.

---

## 4. Sign off that the regression test passed in CI

The automated regression test `tests/test_cbtrn02c.sh` is wired into CI by the workflow
`.github/workflows/cbtrn02c-regression.yml`. It builds `CBTRN02C` with GnuCOBOL, runs a simulated
`POSTTRAN.STEP15` against four account scenarios (`'Y'` active, `'N'` inactive, `'C'` closed, `' '`
blank) and asserts that only the active transaction posts while the three non-active transactions are
rejected to `DALYREJS` with reason `0104` / `ACCOUNT NOT ACTIVE`. The test exits `0` only when **all 25
assertions pass**; any failure exits non-zero and **fails the PR check**.

- [ ] **4.1** Open the pull request's **Checks** tab and confirm the **`CBTRN02C Regression`** workflow
  shows a green check (status *Success*) for the latest commit on the branch.
- [ ] **4.2** In the workflow run, open the **`Run CBTRN02C regression test`** step and confirm the
  summary line reads:

  ```text
  Assertions passed: 25   failed: 0
  RESULT: PASS - reason code 104 (ACCOUNT NOT ACTIVE) verified
  ```

- [ ] **4.3** Confirm the simulated step reported `TRANSACTIONS PROCESSED :000000004` and
  `TRANSACTIONS REJECTED  :000000003`, and that the step return code was `RC=4` (the expected,
  normal signal when rejects occur — **not** a failure).
- [ ] **4.4** *(Optional local re-run)* To reproduce CI locally, run from the repository root:

  ```bash
  bash tests/test_cbtrn02c.sh; echo "exit=$?"
  ```

  Confirm it prints the same `Assertions passed: 25   failed: 0` summary and `exit=0`.

---

## 5. Reviewer sign-off

By checking the box below, the reviewer attests that **every** item in §1–§4 has been verified.

- [ ] **APPROVED** — The `IF/ELSE` wrapper preserves the original credit-limit and expiration logic
  unchanged in the `ELSE` branch (§1); all six reason codes `100`–`104` and `109` are intact at their
  documented lines (§2); the syntax check is clean with zero warnings and the change is scoped to
  `app/cbl/CBTRN02C.cbl` only (§3); and the CI regression test passed with 25/25 assertions (§4).

| Field | Value |
|-------|-------|
| Reviewer (name) | `__________________________` |
| Date | `__________________________` |
| PR / commit reviewed | `__________________________` |
| CI run URL | `__________________________` |
| Decision | ☐ Approve ☐ Request changes |

---

*This checklist is a review aid only; it changes no runtime behaviour. The authoritative behavioural
contract is the regression test `tests/test_cbtrn02c.sh` and the operational guidance in
`blitzy/docs/operations_runbook_addendum.md`.*
