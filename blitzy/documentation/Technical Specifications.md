# Technical Specification

# 0. Agent Action Plan

## 0.1 Executive Summary

Based on the bug description, the Blitzy platform understands that the `CBTRN02C` transaction posting program (job step `POSTTRAN.STEP15`) reads each account record from `ACCTFILE` but never inspects the `ACCT-ACTIVE-STATUS` flag before posting, so daily transactions referencing accounts that are not active (closed or inactive) are silently written to `TRANSACT.VSAM.KSDS` and applied to `ACCTFILE`/`TCATBAL` exactly as if the account were active. The required behavior is that any transaction whose target account has `ACCT-ACTIVE-STATUS` other than `'Y'` (active) must skip posting, increment the reject counter, and be written to the `DALYREJS` reject file with a 4-digit reason code and human-readable description so that downstream consumers (`CBSTM03A`, `CBACT04C`) operate on a clean transaction master.

### 0.1.1 Bug Classification

- Failure mode: silent logic error (no ABEND, no file-status failure, no operator message). Program completes with `RETURN-CODE = 0` even when it has just corrupted downstream state.
- Failure category: missing input validation — a required precondition (account-active check) is absent from the validation paragraph that already enforces every other precondition (card cross-reference, account existence, credit limit, expiration date).
- Affected job step: `POSTTRAN.STEP15` executing program `CBTRN02C` [app/jcl/POSTTRAN.jcl:L23].
- Trigger condition: any record in `AWS.M2.CARDDEMO.DALYTRAN.PS` whose target account in `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` has `ACCT-ACTIVE-STATUS` ≠ `'Y'` at offset 12 of the 300-byte account record [app/cpy/CVACT01Y.cpy:L4-L17].
- Severity: high — the silent nature of the defect allows undetected corruption of `TRANSACT.VSAM.KSDS` and incorrect statement and interest calculations in subsequent batch steps.

### 0.1.2 Precise Technical Restatement

What the user describes as "CBTRN02C posts transactions to closed accounts" translates to the following exact technical failure: paragraph `1500-B-LOOKUP-ACCT` in `app/cbl/CBTRN02C.cbl` performs `READ ACCOUNT-FILE INTO ACCOUNT-RECORD` at [app/cbl/CBTRN02C.cbl:L395], and inside its `NOT INVALID KEY` branch [app/cbl/CBTRN02C.cbl:L400-L420] it evaluates only `ACCT-CREDIT-LIMIT` (reason `102`) and `ACCT-EXPIRAION-DATE` (reason `103`); it never references `ACCT-ACTIVE-STATUS`, so the main loop's downstream branch at [app/cbl/CBTRN02C.cbl:L211-L216] proceeds to `2000-POST-TRANSACTION` for inactive-account transactions instead of routing them to `2500-WRITE-REJECT-REC`. The fix is a single in-place modification of paragraph `1500-B-LOOKUP-ACCT` that inserts an `ACCT-ACTIVE-STATUS NOT = 'Y'` check, assigns the next available reason code `104` with description `'ACCOUNT NOT ACTIVE'`, and short-circuits the remaining checks for non-active accounts.

### 0.1.3 Strategic Approach

- **One paragraph, one file.** All changes are confined to paragraph `1500-B-LOOKUP-ACCT` in `app/cbl/CBTRN02C.cbl` [app/cbl/CBTRN02C.cbl:L393-L422]. No copybook, JCL, CSD, or sibling-program edit is required.
- **No new infrastructure.** The validation framework (`WS-VALIDATION-FAIL-REASON` PIC 9(04) and `WS-VALIDATION-FAIL-REASON-DESC` PIC X(76)) is already declared [app/cbl/CBTRN02C.cbl:L181-L182] and already routes failures through `2500-WRITE-REJECT-REC` [app/cbl/CBTRN02C.cbl:L213-L215, app/cbl/CBTRN02C.cbl:L446-L465]. The fix only needs to set a new reason value.
- **No new data definitions.** `ACCT-ACTIVE-STATUS PIC X(01)` is already imported via `COPY CVACT01Y` at [app/cbl/CBTRN02C.cbl:L121] and is populated by the existing `READ` at [app/cbl/CBTRN02C.cbl:L395]; the fix does not add any working-storage variable, copybook, file definition, or SELECT clause.
- **No JCL change.** The `DALYREJS` DD is already provisioned at `LRECL=430` [app/jcl/POSTTRAN.jcl:L34-L38], matching the existing 350-byte transaction payload plus 80-byte validation trailer used by `2500-WRITE-REJECT-REC`.
- **Defensive comparison `NOT = 'Y'`.** This rejects `'N'` (Inactive per the canonical convention at [app/cbl/COACTUPC.cbl:L193]), `'C'` (closed per the bug description), and any blank/`LOW-VALUES`/corrupt value, providing a fail-safe posture for unexpected data while leaving the active-account path (`'Y'`) entirely unchanged.

### 0.1.4 Reproduction Steps (Executable Form)

The user-described symptom maps to the following deterministic reproduction sequence:

- Inspect `app/data/ASCII/acctdata.txt` to confirm at least one sample account exists whose 12th character is a value other than `'Y'` (e.g., `'N'` or `'C'`).
- Place one or more records in `AWS.M2.CARDDEMO.DALYTRAN.PS` referencing that account's `ACCT-ID`.
- Submit job `POSTTRAN.jcl` so that `STEP15 EXEC PGM=CBTRN02C` runs against the loaded `ACCTFILE` and `DALYTRAN` datasets.
- Observe the failure: `WS-TRANSACTION-COUNT` includes the inactive-account transactions, `TRANSACT.VSAM.KSDS` contains newly inserted rows for them, `ACCTFILE` balances on inactive accounts have been updated, and `DALYREJS` does not contain any reject row for those transactions.
- Expected behavior after fix: the inactive-account transactions appear in `DALYREJS` with a 4-byte reason of `0104` and the description `ACCOUNT NOT ACTIVE`, while `TRANSACT.VSAM.KSDS` and the inactive accounts in `ACCTFILE` remain unchanged.

## 0.2 Root Cause Identification

Based on the repository investigation and external research, **the root cause is a single, definitive omission**: paragraph `1500-B-LOOKUP-ACCT` in `app/cbl/CBTRN02C.cbl` reads the full 300-byte `ACCOUNT-RECORD` from `ACCTFILE` but performs no validation of the `ACCT-ACTIVE-STATUS` indicator before allowing the transaction to proceed to `2000-POST-TRANSACTION`. There is no second, hidden root cause: every other validation in the program (card cross-reference at `1500-A-LOOKUP-XREF`, account existence at the `INVALID KEY` branch of the same paragraph, credit-limit check, and expiration-date check) follows the standard reason-code pattern and works correctly; only the active-status check is missing.

### 0.2.1 Defect Location

| Attribute | Value |
|---|---|
| File (repository-relative) | `app/cbl/CBTRN02C.cbl` |
| Paragraph | `1500-B-LOOKUP-ACCT` |
| Paragraph range | Lines `393`-`422` |
| Problematic block | Lines `400`-`420` (the `NOT INVALID KEY` branch) |
| Failure point | The block ends at line `420` (`END-IF` of expiration check) without any prior reference to `ACCT-ACTIVE-STATUS` |
| Calling chain | Main loop [app/cbl/CBTRN02C.cbl:L202-L219] → `1500-VALIDATE-TRAN` [app/cbl/CBTRN02C.cbl:L370-L378] → `1500-B-LOOKUP-ACCT` |
| Code that consumes the missing reason | Main loop's `IF WS-VALIDATION-FAIL-REASON = 0` decision at [app/cbl/CBTRN02C.cbl:L211] — without a non-zero reason, control falls into `2000-POST-TRANSACTION` |

### 0.2.2 Triggering Conditions

The defect manifests only when the following preconditions are all met simultaneously; otherwise existing validations correctly reject the transaction:

- A daily transaction record is read successfully from `DALYTRAN-FILE` at `1000-DALYTRAN-GET-NEXT` [app/cbl/CBTRN02C.cbl:L204].
- The transaction's `DALYTRAN-CARD-NUM` is found in the cross-reference file by `1500-A-LOOKUP-XREF` [app/cbl/CBTRN02C.cbl:L380-L392] (i.e., reason `100` does **not** fire).
- The associated `XREF-ACCT-ID` matches an existing key in `ACCOUNT-FILE`, so `READ ACCOUNT-FILE INTO ACCOUNT-RECORD` [app/cbl/CBTRN02C.cbl:L395] succeeds and enters the `NOT INVALID KEY` branch (i.e., reason `101` does **not** fire).
- The `ACCT-ACTIVE-STATUS` byte at offset 12 of the loaded `ACCOUNT-RECORD` [app/cpy/CVACT01Y.cpy:L4-L17] is any value other than `'Y'` (e.g., `'N'` Inactive, `'C'` Closed, blank, `LOW-VALUES`, or any unexpected character).
- The transaction also happens to satisfy the credit-limit check at [app/cbl/CBTRN02C.cbl:L407-L413] and the expiration check at [app/cbl/CBTRN02C.cbl:L414-L420] (a closed account may still hold an unconsumed credit limit and a future expiration date, so this is the common case).

When all five conditions hold, the main loop at [app/cbl/CBTRN02C.cbl:L211] sees `WS-VALIDATION-FAIL-REASON = 0` and dispatches `2000-POST-TRANSACTION` [app/cbl/CBTRN02C.cbl:L424-L444], which calls `2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`, and `2900-WRITE-TRANSACTION-FILE` — corrupting balances and the transaction master.

### 0.2.3 Supporting Evidence

| Evidence Item | Citation | Significance |
|---|---|---|
| `ACCT-ACTIVE-STATUS PIC X(01)` is declared at offset 12 of `ACCOUNT-RECORD` | [app/cpy/CVACT01Y.cpy:L4-L17] | The field exists and is loaded into memory at every `READ ACCOUNT-FILE` |
| `COPY CVACT01Y` is already present in the program | [app/cbl/CBTRN02C.cbl:L121] | `ACCT-ACTIVE-STATUS` is in scope in `1500-B-LOOKUP-ACCT` — no new copybook needed |
| `READ ACCOUNT-FILE INTO ACCOUNT-RECORD` populates the field | [app/cbl/CBTRN02C.cbl:L395] | No second I/O call is required to obtain the status value |
| Validation framework `WS-VALIDATION-FAIL-REASON PIC 9(04)` plus `WS-VALIDATION-FAIL-REASON-DESC PIC X(76)` is already declared | [app/cbl/CBTRN02C.cbl:L181-L182] | The fix only needs to assign a new reason value; the slot is already available |
| Main-loop dispatcher routes non-zero reasons to `2500-WRITE-REJECT-REC` | [app/cbl/CBTRN02C.cbl:L211-L216] | Existing routing carries the new reason through to the reject file without modification |
| `2500-WRITE-REJECT-REC` packs `DALYTRAN-RECORD` plus the 80-byte trailer and writes to `DALYREJS` | [app/cbl/CBTRN02C.cbl:L446-L465] | The reject-file writer is fully functional and reused as-is |
| `DALYREJS` DD is provisioned at `LRECL=430` matching `350` (payload) + `80` (trailer) | [app/jcl/POSTTRAN.jcl:L34-L38] | No JCL or dataset-allocation change is required |
| Canonical valid-values for the status field are `'Y'` and `'N'` | [app/cbl/COACTUPC.cbl:L193] (`88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'.`) | Confirms `'Y'` is the only active value; any other value is non-active and must be rejected |
| Tech Spec confirms `'Y'` = Active, `'N'` = Inactive semantics | [§4.2.1.2 Online Transaction Workflows] | Documented design intent for the field |
| Sample test data carries `'Y'` at offset 12 for every active account | [app/data/ASCII/acctdata.txt] | Confirms the runtime layout; verification fixtures can be derived from this file |
| `ACCT-ACTIVE-STATUS` is only read elsewhere for display/maintenance, never for batch posting | [app/cbl/CBACT01C.cbl:L120], [app/cbl/COACTVWC.cbl:L473], [app/cbl/COACTUPC.cbl:L3810,L3819,L4115] | Confirms no other program performs this check on the posting path — the gap is unique to `CBTRN02C` |
| No external GitHub issue, AWS re:Post thread, or AWS Mainframe Modernization documentation describes a prior fix for this gap | Web research findings, Phase 4 observations | Consistent with the description of a longstanding omission rather than a regression |

### 0.2.4 Definitive Reasoning

This is the only root cause for the reported symptom because:

- The mainline read/validate/post loop [app/cbl/CBTRN02C.cbl:L202-L219] depends solely on `WS-VALIDATION-FAIL-REASON` to make its post-versus-reject routing decision; if that flag is `0` when the loop tests it at line `211`, posting proceeds. Therefore, any symptom of "inactive account transactions being posted" must originate from a code path that fails to set the reason to non-zero for inactive-status accounts.
- A complete enumeration of the reason-setting statements in the program shows `100` at [app/cbl/CBTRN02C.cbl:L385], `101` at [app/cbl/CBTRN02C.cbl:L397], `102` at [app/cbl/CBTRN02C.cbl:L410], `103` at [app/cbl/CBTRN02C.cbl:L417], and `109` at [app/cbl/CBTRN02C.cbl:L556]. None of these tests `ACCT-ACTIVE-STATUS`. Therefore, the program structurally cannot reject an inactive-status account — the symptom is fully explained by this single omission.
- The next available 3-digit reason code in the `1xx` series is `104`, which the fix will adopt for the new check.

## 0.3 Diagnostic Execution

This section captures the evidence assembled during diagnosis: the code blocks that were examined, what was found at each location, and the analysis confirming that the proposed fix eliminates the defect without regressing existing behavior.

### 0.3.1 Code Examination Results

For each known root-cause locus and each piece of code that participates in the defect's manifestation, the file, line range, failure point, and causal relationship are recorded below.

#### 0.3.1.1 Primary Defect: 1500-B-LOOKUP-ACCT Missing Active-Status Check

- File (relative to repository root): `app/cbl/CBTRN02C.cbl`
- Problematic block: lines `400`-`420` (the `NOT INVALID KEY` branch of paragraph `1500-B-LOOKUP-ACCT`)
- Failure point: end of the block at line `420` is reached for inactive accounts with the same `WS-VALIDATION-FAIL-REASON` value the paragraph was entered with (typically `0` after the per-transaction reset at line `208`)
- Current implementation (verbatim from [app/cbl/CBTRN02C.cbl:L393-L422]):

```cobol
1500-B-LOOKUP-ACCT.
    MOVE XREF-ACCT-ID TO FD-ACCT-ID
    READ ACCOUNT-FILE INTO ACCOUNT-RECORD
       INVALID KEY
         MOVE 101 TO WS-VALIDATION-FAIL-REASON
         MOVE 'ACCOUNT RECORD NOT FOUND'
           TO WS-VALIDATION-FAIL-REASON-DESC
       NOT INVALID KEY
         COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                             - ACCT-CURR-CYC-DEBIT
                             + DALYTRAN-AMT
         IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
           CONTINUE
         ELSE
           MOVE 102 TO WS-VALIDATION-FAIL-REASON
           MOVE 'OVERLIMIT TRANSACTION'
             TO WS-VALIDATION-FAIL-REASON-DESC
         END-IF
         IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
           CONTINUE
         ELSE
           MOVE 103 TO WS-VALIDATION-FAIL-REASON
           MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
             TO WS-VALIDATION-FAIL-REASON-DESC
         END-IF
    END-READ
    EXIT.
```

- How this leads to the bug: `ACCT-ACTIVE-STATUS` (declared at [app/cpy/CVACT01Y.cpy:L4-L17]) is populated by the `READ` at line `395` but is never read by any statement inside the paragraph. After exiting at line `422`, control returns to `1500-VALIDATE-TRAN` at [app/cbl/CBTRN02C.cbl:L370-L378] and then to the main loop at [app/cbl/CBTRN02C.cbl:L211]; if neither the credit-limit nor the expiration check fired, `WS-VALIDATION-FAIL-REASON` is still `0` and the loop dispatches `2000-POST-TRANSACTION`. Closed accounts that are within their credit limit and have a future expiration date are therefore posted unconditionally.

#### 0.3.1.2 Downstream Side Effects in 2000-POST-TRANSACTION

- File: `app/cbl/CBTRN02C.cbl`
- Problematic block: lines `424`-`444`
- Failure point: the unconditional `PERFORM 2700-UPDATE-TCATBAL`, `PERFORM 2800-UPDATE-ACCOUNT-REC`, and `PERFORM 2900-WRITE-TRANSACTION-FILE` invocations at [app/cbl/CBTRN02C.cbl:L440-L442]
- How this leads to corruption: `2800-UPDATE-ACCOUNT-REC` issues a `REWRITE` against `ACCOUNT-FILE` updating the closed account's balance fields; `2900-WRITE-TRANSACTION-FILE` inserts a record into `TRANSACT.VSAM.KSDS`; `2700-UPDATE-TCATBAL` updates per-category balances. None of these paragraphs are themselves defective — they merely execute the (incorrectly approved) instruction from the validation layer. They are listed here to document the blast radius of the bug, not because they require modification.

#### 0.3.1.3 Validation-to-Reject Dispatcher (Confirmed Correct)

- File: `app/cbl/CBTRN02C.cbl`
- Block: lines `211`-`216`

```cobol
IF WS-VALIDATION-FAIL-REASON = 0
  PERFORM 2000-POST-TRANSACTION
ELSE
  ADD 1 TO WS-REJECT-COUNT
  PERFORM 2500-WRITE-REJECT-REC
END-IF
```

- Significance: this dispatcher is the structural reason the bug manifests as silent corruption rather than an ABEND or skipped record. Once the fix sets `WS-VALIDATION-FAIL-REASON = 104` for inactive accounts, this block automatically routes them to the reject pipeline — no change is required here.

#### 0.3.1.4 Reject-File Writer (Confirmed Correct)

- File: `app/cbl/CBTRN02C.cbl`
- Block: lines `446`-`465` (paragraph `2500-WRITE-REJECT-REC`)
- Mechanism (excerpted from [app/cbl/CBTRN02C.cbl:L446-L465]):

```cobol
MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
WRITE FD-REJS-RECORD FROM REJECT-RECORD
```

- Significance: this paragraph writes the full 430-byte reject record using existing `REJECT-RECORD` (350-byte payload at [app/cbl/CBTRN02C.cbl:L176-L177] + 80-byte trailer at [app/cbl/CBTRN02C.cbl:L178]). It reuses `WS-VALIDATION-TRAILER` (defined at [app/cbl/CBTRN02C.cbl:L180-L182] as `WS-VALIDATION-FAIL-REASON PIC 9(04)` plus `WS-VALIDATION-FAIL-REASON-DESC PIC X(76)`), and writes to the `DALYREJS` DD provisioned at `LRECL=430` by [app/jcl/POSTTRAN.jcl:L34-L38]. No change is required here.

### 0.3.2 Key Findings from Repository Analysis

The table below summarizes what was discovered and where, with the conclusion each finding supports. Methodology and tool usage are not reproduced — only the findings.

| Finding | File:Line | Conclusion |
|---|---|---|
| Paragraph `1500-B-LOOKUP-ACCT` reads `ACCOUNT-RECORD` but never references `ACCT-ACTIVE-STATUS` | [app/cbl/CBTRN02C.cbl:L393-L422] | Direct evidence of the missing check — this is the defect site |
| `ACCT-ACTIVE-STATUS PIC X(01)` is at offset 12 of the 300-byte `ACCOUNT-RECORD` | [app/cpy/CVACT01Y.cpy:L4-L17] | The field is already available in memory after the `READ` — fix needs no I/O addition |
| `COPY CVACT01Y` is already in working-storage of CBTRN02C | [app/cbl/CBTRN02C.cbl:L121] | Field is in lexical scope; no new `COPY` directive required |
| Reason codes `100`, `101`, `102`, `103`, `109` are in use; `104`-`108` are free | [app/cbl/CBTRN02C.cbl:L385,L397,L410,L417,L556] | `104` is the appropriate next reason for the new active-status failure |
| Validation trailer fields `WS-VALIDATION-FAIL-REASON` and `WS-VALIDATION-FAIL-REASON-DESC` are pre-declared | [app/cbl/CBTRN02C.cbl:L180-L182] | The fix is a pure logic insertion — no working-storage edits |
| Main loop already routes non-zero reasons to `2500-WRITE-REJECT-REC` | [app/cbl/CBTRN02C.cbl:L211-L216] | New reason `104` is carried to reject pipeline automatically |
| Reject file is provisioned at `LRECL=430` | [app/jcl/POSTTRAN.jcl:L34-L38] | No JCL change required |
| Canonical convention is `'Y'` (Active) / `'N'` (Inactive) | [app/cbl/COACTUPC.cbl:L193] (`88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'`) | Defines the equality used by the fix; `NOT = 'Y'` is the defensive form |
| Tech Spec documents the same convention | [§4.2.1.2 Online Transaction Workflows] | Independent confirmation of the codebase convention |
| No other batch program validates `ACCT-ACTIVE-STATUS` on the posting path | [app/cbl/CBACT01C.cbl:L120] (display only), [app/cbl/COACTVWC.cbl:L473] (UI), [app/cbl/COACTUPC.cbl:L3810,L3819,L4115] (online maintenance) | Confirms the gap is unique to CBTRN02C; no sibling program already enforces this rule |
| Sample test data carries `'Y'` at position 12 for every active account | [app/data/ASCII/acctdata.txt] | Verification fixtures can be derived by toggling this byte to `'N'` or `'C'` |
| `2000-POST-TRANSACTION` invokes balance and master updates unconditionally | [app/cbl/CBTRN02C.cbl:L440-L442] | Confirms the blast radius — inactive-account posting corrupts `ACCTFILE`, `TCATBAL`, and `TRANSACT` |
| `RETURN-CODE` is set to `4` whenever `WS-REJECT-COUNT > 0` | [app/cbl/CBTRN02C.cbl:L229-L231] | After fix, operators see RC=4 if inactive-account rejects occurred — standard observability |
| Apache 2.0 license header occupies lines 7-21 | [app/cbl/CBTRN02C.cbl:L7-L21] | Header must be preserved; fix is below this region |
| External research returned no prior GitHub issue or PR for this gap | Phase 4 web research findings | Consistent with the description of a longstanding omission |
| Compatibility of `IF/MOVE` literal-comparison constructs is universal across z/OS COBOL, AWS Blu Age, and Micro Focus runtimes | Phase 4 web research findings | No compiler-version risk introduced by the fix |

### 0.3.3 Fix Verification Analysis

The fix is verified by combining a deterministic reproduction protocol against the unpatched program with regression checks against the patched program. The protocol exercises all relevant code paths (active, inactive, closed, not-found, over-limit, expired) using the existing test data.

#### 0.3.3.1 Reproduction Steps (Pre-Fix)

- Identify or construct an account fixture in `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` whose `ACCT-ACTIVE-STATUS` byte (offset 12) is `'N'` (or `'C'`). The structure to follow is the layout in [app/cpy/CVACT01Y.cpy:L4-L17] and the format of [app/data/ASCII/acctdata.txt].
- Construct a record in `AWS.M2.CARDDEMO.DALYTRAN.PS` whose card number cross-references to that account ID via `XREFFILE`, whose amount stays within `ACCT-CREDIT-LIMIT`, and whose `DALYTRAN-ORIG-TS (1:10)` is on or before `ACCT-EXPIRAION-DATE`.
- Submit `POSTTRAN.jcl` and observe job step `STEP15` (`PGM=CBTRN02C`) return `RC=0` and the following datasets:
    - `TRANSACT.VSAM.KSDS`: contains a row for the inactive-account transaction (defect symptom).
    - `ACCTFILE`: balance fields of the inactive account have been updated (defect symptom).
    - `DALYREJS`: no row for the inactive-account transaction (defect symptom).

#### 0.3.3.2 Confirmation Steps (Post-Fix)

- Re-load `ACCTFILE` and `DALYTRAN` from the same fixtures and re-submit `POSTTRAN.jcl` against the patched `CBTRN02C` load module.
- Observe and verify:
    - `WS-VALIDATION-FAIL-REASON = 0104` and `WS-VALIDATION-FAIL-REASON-DESC = 'ACCOUNT NOT ACTIVE'` appear in `DALYREJS` for each inactive-account transaction. The reject record is 430 bytes (350-byte `DALYTRAN-RECORD` payload + 80-byte trailer).
    - `TRANSACT.VSAM.KSDS` contains no new row for the inactive-account transaction (browse by transaction ID confirms absence).
    - `ACCTFILE` balance fields of the inactive account are unchanged versus pre-job state.
    - The job log displays `TRANSACTIONS REJECTED  : nnn` per [app/cbl/CBTRN02C.cbl:L228] with `nnn` incremented by the number of inactive-account transactions.
    - Job step `STEP15` returns `RC=4` if any rejects occurred, per [app/cbl/CBTRN02C.cbl:L229-L231]. Without inactive-account transactions in the batch (only active accounts), `RC=0` is preserved.

#### 0.3.3.3 Boundary Conditions and Edge Cases

The fix uses the defensive comparison `ACCT-ACTIVE-STATUS NOT = 'Y'`. The following edge cases are explicitly covered by this form:

- `ACCT-ACTIVE-STATUS = 'Y'` (Active): not rejected — control falls into the `ELSE` branch where the existing credit-limit and expiration checks continue unchanged. Active-account behavior is bit-for-bit identical to pre-fix behavior.
- `ACCT-ACTIVE-STATUS = 'N'` (Inactive per the canonical convention at [app/cbl/COACTUPC.cbl:L193]): rejected with reason `104`.
- `ACCT-ACTIVE-STATUS = 'C'` (Closed per the bug description): rejected with reason `104` (the defensive form treats any non-`'Y'` value as not-active).
- `ACCT-ACTIVE-STATUS = ' '` (space) or `LOW-VALUES` (corrupt data): rejected with reason `104` — fail-safe posture preserves data integrity in the presence of unexpected values.
- Account not found (`INVALID KEY` at line `396`): reason `101` continues to fire as before; the new status check is in the `NOT INVALID KEY` branch only, so it cannot mask a missing-account failure.
- Upstream cross-reference miss (reason `100` at [app/cbl/CBTRN02C.cbl:L385]): guarded by `IF WS-VALIDATION-FAIL-REASON = 0` at [app/cbl/CBTRN02C.cbl:L372], so `1500-B-LOOKUP-ACCT` is never entered — the new status check is untouched in this path.
- For an inactive account where the transaction would also be over-limit or expired: the fix short-circuits the credit-limit and expiration checks for non-active accounts (they appear in the `ELSE` branch of the new `IF`). This is correct — once the account is not active, the more granular reasons (`102`, `103`) are immaterial; reason `104` is reported as the primary failure.

#### 0.3.3.4 Verification Outcome

- The fix targets the single confirmed code-level gap identified by repository evidence.
- It reuses existing infrastructure exclusively (`WS-VALIDATION-FAIL-REASON`, `WS-VALIDATION-TRAILER`, `2500-WRITE-REJECT-REC`, the main-loop dispatcher, and the JCL-provisioned `DALYREJS` DD).
- The active-account path retains exactly the same sequence of statements (`COMPUTE`, credit-limit `IF/ELSE`, expiration `IF/ELSE`), guaranteeing no regression in the dominant case.
- Confidence level: **95%**. The remaining 5% allowance is reserved for non-`'Y'`/`'N'`/`'C'`/blank status values whose business meaning is undefined in the codebase; the defensive form errs on the side of rejection in those cases.

## 0.4 Bug Fix Specification

This section gives the **exact** code-level change required to eliminate the defect. Every line number references the unpatched file `app/cbl/CBTRN02C.cbl` as it currently exists in the repository. The change is one in-place edit that replaces lines `400`-`420` (the body of the `NOT INVALID KEY` branch of paragraph `1500-B-LOOKUP-ACCT`) with a structurally equivalent block that adds the `ACCT-ACTIVE-STATUS` check ahead of the existing checks.

### 0.4.1 The Definitive Fix

- **File to modify**: `app/cbl/CBTRN02C.cbl` (one file, in place; no rename, no move).
- **Paragraph to modify**: `1500-B-LOOKUP-ACCT`, located at lines `393`-`422` per [app/cbl/CBTRN02C.cbl:L393-L422].
- **Region to modify**: the body of the `NOT INVALID KEY` clause (lines `400`-`420`). Lines `393`-`399` (paragraph header, `MOVE XREF-ACCT-ID`, `READ`, and the `INVALID KEY` branch that sets reason `101`) are unchanged. Lines `421` (`END-READ`) and `422` (`EXIT.`) are unchanged.
- **Mechanism**: introduce a new `IF ACCT-ACTIVE-STATUS NOT = 'Y'` at the top of the `NOT INVALID KEY` body that assigns reason code `104` with description `ACCOUNT NOT ACTIVE`; place the existing `COMPUTE WS-TEMP-BAL` and the two subsequent `IF/ELSE/END-IF` blocks (credit-limit and expiration) inside the `ELSE` branch of the new `IF`. This short-circuits the remaining checks for non-active accounts and preserves the exact pre-fix behavior for active accounts.
- **Technical mechanism by which this fixes the root cause**: when an inactive account is read, the new `IF` sets `WS-VALIDATION-FAIL-REASON = 104` and a description string. After `1500-B-LOOKUP-ACCT` returns, the main-loop dispatcher at [app/cbl/CBTRN02C.cbl:L211-L216] tests `WS-VALIDATION-FAIL-REASON = 0`, finds it non-zero, increments `WS-REJECT-COUNT` and `PERFORM 2500-WRITE-REJECT-REC`. Paragraph `2500-WRITE-REJECT-REC` at [app/cbl/CBTRN02C.cbl:L446-L465] then packs the 350-byte `DALYTRAN-RECORD` plus the 80-byte `WS-VALIDATION-TRAILER` (now carrying `0104` plus `ACCOUNT NOT ACTIVE`) into `REJECT-RECORD` and writes it to `DALYREJS`. The inactive-account transaction is **not** passed to `2000-POST-TRANSACTION`, so `TRANSACT.VSAM.KSDS`, `ACCTFILE`, and `TCATBAL` remain unaffected for non-active accounts.

#### 0.4.1.1 Required Code at Lines 400-420 (After Fix)

The replacement block to install at lines `400`-`420` (inside the `NOT INVALID KEY` clause) is shown below. Comments are required to explain the motive of the change in alignment with project documentation style. The block preserves the existing comment lines and existing comparison operators verbatim — only the additional outer `IF/ELSE/END-IF` and its comment header are new.

```cobol
       NOT INVALID KEY
*         DISPLAY 'ACCT-CREDIT-LIMIT:' ACCT-CREDIT-LIMIT
*         DISPLAY 'TRAN-AMT         :' DALYTRAN-AMT
*         Bug fix: reject transactions targeting accounts whose
*         ACCT-ACTIVE-STATUS is not 'Y' (active). This covers
*         'N' (Inactive per CDEMO-ACCT-STATUS convention) and
*         'C' (Closed) as well as blanks, LOW-VALUES, and any
*         corrupt value, preventing posting to non-active
*         accounts and protecting TRANSACT, ACCTFILE, and
*         TCATBAL from downstream corruption.
         IF ACCT-ACTIVE-STATUS NOT = 'Y'
            MOVE 104 TO WS-VALIDATION-FAIL-REASON
            MOVE 'ACCOUNT NOT ACTIVE'
              TO WS-VALIDATION-FAIL-REASON-DESC
         ELSE
            COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                                - ACCT-CURR-CYC-DEBIT
                                + DALYTRAN-AMT

            IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
              CONTINUE
            ELSE
              MOVE 102 TO WS-VALIDATION-FAIL-REASON
              MOVE 'OVERLIMIT TRANSACTION'
                TO WS-VALIDATION-FAIL-REASON-DESC
            END-IF
            IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
              CONTINUE
            ELSE
              MOVE 103 TO WS-VALIDATION-FAIL-REASON
              MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
                TO WS-VALIDATION-FAIL-REASON-DESC
            END-IF
         END-IF
```

The `END-READ` and `EXIT.` that follow at the original lines `421`-`422` remain unchanged. The complete paragraph `1500-B-LOOKUP-ACCT`, after the fix, reads:

- Lines `393`-`399`: unchanged paragraph header, `MOVE XREF-ACCT-ID TO FD-ACCT-ID`, `READ ACCOUNT-FILE INTO ACCOUNT-RECORD`, and the `INVALID KEY` branch (reason `101`).
- Lines `400`-onward: the replacement block above (inside `NOT INVALID KEY`), terminated by the original `END-READ` and `EXIT.`.

### 0.4.2 Change Instructions

The following operations describe the edit in line-deterministic form, preserving COBOL column conventions (columns 7-11 reserved for sequence/indicator, code starting at column 12 or beyond). All inserted lines must respect Area A / Area B placement as used by the surrounding code.

#### 0.4.2.1 DELETE — lines 401 through 420 (the contents between `NOT INVALID KEY` and `END-READ`)

Delete the following 20-line block exactly (these are the existing contents of lines `401`-`420` per [app/cbl/CBTRN02C.cbl:L401-L420]). Note that lines `401`-`402` are pre-existing `DISPLAY` comments, line `406` is blank, and lines `403`-`405`, `407`-`413`, `414`-`420` are the existing COMPUTE, credit-limit, and expiration blocks:

```cobol
*         DISPLAY 'ACCT-CREDIT-LIMIT:' ACCT-CREDIT-LIMIT
*         DISPLAY 'TRAN-AMT         :' DALYTRAN-AMT
         COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                             - ACCT-CURR-CYC-DEBIT
                             + DALYTRAN-AMT

         IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
           CONTINUE
         ELSE
           MOVE 102 TO WS-VALIDATION-FAIL-REASON
           MOVE 'OVERLIMIT TRANSACTION'
             TO WS-VALIDATION-FAIL-REASON-DESC
         END-IF
         IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
           CONTINUE
         ELSE
           MOVE 103 TO WS-VALIDATION-FAIL-REASON
           MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
             TO WS-VALIDATION-FAIL-REASON-DESC
         END-IF
```

#### 0.4.2.2 INSERT — at the position where the deleted block began (immediately after the existing `NOT INVALID KEY` keyword at line `400`)

Insert the following replacement block. The two pre-existing `DISPLAY`-comment lines are retained as the first two lines of the insert to preserve the original maintainer's debug-display intent. The new outer `IF ACCT-ACTIVE-STATUS NOT = 'Y'` wraps the original `COMPUTE` plus the two `IF/ELSE/END-IF` checks in an `ELSE` branch. All inner comparison logic is byte-for-byte the original logic, simply re-indented one level deeper to live inside the new `ELSE`.

```cobol
*         DISPLAY 'ACCT-CREDIT-LIMIT:' ACCT-CREDIT-LIMIT
*         DISPLAY 'TRAN-AMT         :' DALYTRAN-AMT
*         Bug fix: reject transactions targeting accounts whose
*         ACCT-ACTIVE-STATUS is not 'Y' (active). This covers
*         'N' (Inactive per CDEMO-ACCT-STATUS convention) and
*         'C' (Closed) as well as blanks, LOW-VALUES, and any
*         corrupt value, preventing posting to non-active
*         accounts and protecting TRANSACT, ACCTFILE, and
*         TCATBAL from downstream corruption.
         IF ACCT-ACTIVE-STATUS NOT = 'Y'
            MOVE 104 TO WS-VALIDATION-FAIL-REASON
            MOVE 'ACCOUNT NOT ACTIVE'
              TO WS-VALIDATION-FAIL-REASON-DESC
         ELSE
            COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                                - ACCT-CURR-CYC-DEBIT
                                + DALYTRAN-AMT

            IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
              CONTINUE
            ELSE
              MOVE 102 TO WS-VALIDATION-FAIL-REASON
              MOVE 'OVERLIMIT TRANSACTION'
                TO WS-VALIDATION-FAIL-REASON-DESC
            END-IF
            IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
              CONTINUE
            ELSE
              MOVE 103 TO WS-VALIDATION-FAIL-REASON
              MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
                TO WS-VALIDATION-FAIL-REASON-DESC
            END-IF
         END-IF
```

#### 0.4.2.3 MODIFY — none required outside the inserted block

- Working-Storage Section (lines `99`-`191`): no change. `WS-VALIDATION-FAIL-REASON`, `WS-VALIDATION-FAIL-REASON-DESC`, `WS-VALIDATION-TRAILER`, `WS-REJECT-COUNT`, `REJECT-RECORD`, and `REJECT-TRAN-DATA` already exist and are sized correctly.
- File Section (lines `64`-`92`): no change. `DALYREJS-FILE` and `FD-REJS-RECORD` are already declared at the correct 430-byte total length.
- `COPY` directives (lines `102`, `107`, `112`, `121`, `126`): no change. `COPY CVACT01Y` at [app/cbl/CBTRN02C.cbl:L121] already imports `ACCT-ACTIVE-STATUS`.
- Other paragraphs (`1500-VALIDATE-TRAN`, `1500-A-LOOKUP-XREF`, `2000-POST-TRANSACTION`, `2500-WRITE-REJECT-REC`, `2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`, `2900-WRITE-TRANSACTION-FILE`, and the open/close paragraphs `0000`-`9500`): no change.
- License header (lines `7`-`21`): no change.

#### 0.4.2.4 Line-Number Delta

The replacement block adds an outer `IF/ELSE/END-IF` plus seven new comment lines (one header line plus the `MOVE 104` two-line block, the `MOVE 'ACCOUNT NOT ACTIVE'` two-line block, the `ELSE` and the closing `END-IF`). Net additional lines: approximately `+13` to `+15` depending on comment formatting. Paragraphs `2000-POST-TRANSACTION` onwards (currently starting at line `424`) shift down by that delta after the edit. No external file references the moved line numbers, so this shift has no impact.

### 0.4.3 Fix Validation

- **Compile and link**:
    - Recompile `app/cbl/CBTRN02C.cbl` with the project's standard COBOL compiler invocation (the same one used for the unmodified module — no compile-option change is required because the fix uses only constructs that are already in use elsewhere in the program: `IF/ELSE/END-IF`, `MOVE` with literal, `PIC X(01)` equality comparison against a single-character literal).
    - Verify zero compile-time errors and zero new warnings on the modified paragraph. Existing warnings in other paragraphs are not in scope for this fix.
    - Link-edit `CBTRN02C` into the project's load library as a drop-in replacement; no manifest or include-path changes are required.

- **Functional validation** (executed against a controlled test bed):
    - Test command: submit the `POSTTRAN.jcl` job after ensuring `ACCTFILE` and `DALYTRAN` are loaded with fixtures that include at least one inactive-account record. Confirm step output via `STEP15` SYSOUT and dataset browse.
    - Expected output after fix:
        - SYSOUT shows `TRANSACTIONS PROCESSED :nnn` and `TRANSACTIONS REJECTED  :mmm` per [app/cbl/CBTRN02C.cbl:L227-L228], with `mmm` including the inactive-account transactions.
        - `RC=4` for the step when `mmm > 0`, per [app/cbl/CBTRN02C.cbl:L229-L231]; `RC=0` if all input transactions reference active accounts.
        - `DALYREJS` contains a 430-byte record for each inactive-account transaction. The trailer columns contain the 4-digit reason `0104` and the description `ACCOUNT NOT ACTIVE` left-justified in the 76-byte description field.
        - `TRANSACT.VSAM.KSDS` contains no row for inactive-account transactions.
        - `ACCTFILE` rows for inactive accounts have unchanged balance fields versus the pre-job snapshot.
        - `TCATBAL` rows for inactive accounts and their transaction categories have unchanged balance fields versus the pre-job snapshot.

- **Confirmation method**:
    - Browse `DALYREJS` after the run and inspect bytes 351-354 (reason code) and 355-430 (description) of each reject record. Confirm `0104` and `ACCOUNT NOT ACTIVE` for inactive-account transactions and the pre-existing `0100`/`0101`/`0102`/`0103` codes for other reject types if those inputs are included.
    - Cross-reference `TRANSACT.VSAM.KSDS` by `TRAN-ID` to confirm no insertion for inactive-account transaction IDs.
    - Compare pre-job and post-job snapshots of `ACCTFILE` and `TCATBAL` to confirm that only active-account rows changed.
    - Inspect the job-log lines `TRANSACTIONS PROCESSED` and `TRANSACTIONS REJECTED` for arithmetic consistency: `processed = posted + rejected`.

### 0.4.4 User Interface Design

Not applicable. This is a backend batch fix to a COBOL program (`CBTRN02C`) that executes in a non-interactive POSTTRAN job. The program performs no terminal I/O, has no BMS map, and is invoked by JCL rather than by a CICS transaction. No screen, no user interaction, and no design-system mapping are involved.

## 0.5 Scope Boundaries

This section enumerates every file that requires modification, every file that must remain untouched, and the precise nature of each change. The boundary is intentionally narrow: a single paragraph of a single source file. No other artifact in the repository is in scope.

### 0.5.1 Changes Required (Exhaustive List)

| # | File (repository-relative) | Operation | Specific Change | Line Range Affected |
|---|---|---|---|---|
| 1 | `app/cbl/CBTRN02C.cbl` | MODIFY | Replace the body of the `NOT INVALID KEY` branch of paragraph `1500-B-LOOKUP-ACCT` with a block that adds `IF ACCT-ACTIVE-STATUS NOT = 'Y'` setting reason `104` and description `ACCOUNT NOT ACTIVE`, wrapping the existing `COMPUTE` + credit-limit `IF/ELSE` + expiration `IF/ELSE` in the `ELSE` branch. Add explanatory comments. | Lines `400`-`420` (replaced); paragraph header (line `393`), `MOVE` and `READ INVALID KEY` clause (lines `394`-`399`), `END-READ` and `EXIT.` (lines `421`-`422`) remain unchanged |

Files CREATED: **0**. Files DELETED: **0**. Files MOVED or RENAMED: **0**.

### 0.5.2 No Dependency Changes

No package, library, or compiler/runtime dependency is added, upgraded, or removed by this fix. The change uses only COBOL constructs already present in the same paragraph (`IF/ELSE/END-IF`, `MOVE` with literal, `PIC X(01)` equality comparison) and only data items already declared in the program (`ACCT-ACTIVE-STATUS` via `COPY CVACT01Y` at [app/cbl/CBTRN02C.cbl:L121]; `WS-VALIDATION-FAIL-REASON` and `WS-VALIDATION-FAIL-REASON-DESC` at [app/cbl/CBTRN02C.cbl:L181-L182]).

### 0.5.3 Build Artifacts to Refresh

- Recompile `app/cbl/CBTRN02C.cbl` with the project's standard COBOL compile JCL.
- Link-edit the new `CBTRN02C` load module into the application load library so `POSTTRAN.STEP15` (per [app/jcl/POSTTRAN.jcl:L23]) loads the patched module on its next run.
- No CSD/CICS resource refresh is required (`CBTRN02C` is a batch program executed via JCL, not via CICS).

### 0.5.4 Explicitly Excluded — Do NOT Modify

The user's prompt mandates that the following files remain untouched. The diagnostic investigation confirms that none of them require any change to deliver the fix.

| File / Asset | Why Excluded | Verification of "No Change Needed" |
|---|---|---|
| `app/cpy/CVACT01Y.cpy` | Copybook out of scope per prompt; defines `ACCT-ACTIVE-STATUS` already | Field declared at [app/cpy/CVACT01Y.cpy:L4-L17]; no schema change |
| `app/cpy/CVTRA06Y.cpy` | Input transaction record layout must remain identical | Fix does not alter the input contract; no `DALYTRAN-RECORD` field is added, removed, or repositioned |
| `app/cpy/*.cpy` (all other copybooks: `CVTRA05Y`, `CVACT03Y`, `CVTRA01Y`, etc.) | Out of scope per prompt | Fix references only fields already in the existing `COPY` set |
| `app/cbl/CBSTM03A.CBL` | Downstream statement generator — explicitly excluded by prompt | Receives clean `TRANSACT.VSAM.KSDS` after fix; no contract change |
| `app/cbl/CBSTM03B.CBL` | Adjunct of the statement generator | No upstream change reaches this program; no edit required |
| `app/cbl/CBACT04C.cbl` | Interest-calculation batch program — explicitly excluded by prompt | Receives clean `ACCTFILE` after fix; no contract change |
| `app/cbl/CBACT01C.cbl`, `app/cbl/CBACT02C.cbl`, `app/cbl/CBACT03C.cbl` | Account/card reporting batches | Fix is internal to `CBTRN02C`; no shared state altered |
| `app/cbl/CBTRN01C.cbl`, `app/cbl/CBTRN03C.cbl` | Sibling transaction programs | Not invoked by `CBTRN02C`; their validation logic is unaffected |
| `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` | Online account maintenance/view | Source of the `'Y'`/`'N'` convention (read-only reference at [app/cbl/COACTUPC.cbl:L193]); not modified |
| `app/cbl/COCRDUPC.cbl`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl` | Online card maintenance | Unrelated subsystem |
| `app/cbl/COSGN00C.cbl`, `app/cbl/COADM01C.cbl`, `app/cbl/COMEN01C.cbl` | Sign-on/menu/admin programs | Unrelated subsystem |
| `app/cbl/COTRN*.cbl` (online transaction screens) | Online transaction subsystem | Distinct from batch posting path; out of scope |
| `app/cbl/COUSR*.cbl` (user-management screens) | Unrelated subsystem | Out of scope |
| `app/jcl/POSTTRAN.jcl` | Already provisions `DALYREJS` at `LRECL=430` | No DD change required ([app/jcl/POSTTRAN.jcl:L34-L38]) |
| `app/jcl/*.jcl` (all other JCL files: `INTCALC.jcl`, `CREASTMT.jcl`, `DALYREJS.jcl`, etc.) | Out of scope per prompt | Datasets and step definitions are reused as-is |
| `app/csd/CARDDEMO.CSD` | CICS resource definitions for VSAM/program/transaction entries | Batch program; no CICS resource impacted |
| `app/bms/*.bms`, `app/cpy-bms/*` | BMS maps and BMS copybooks | No UI involvement |
| `app/data/ASCII/*.txt`, `app/data/EBCDIC/*` | Test/seed data fixtures | Format already accommodates `'Y'`/`'N'` values; no fixture change required for fix correctness (fixtures may be augmented for verification, but the production data files are not modified by the fix) |
| `app/csd/*`, `app/catlg/*` | Catalog definitions | Out of scope |
| `LICENSE`, `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` | Project metadata | Not affected by a code-level bug fix |
| Apache 2.0 license header inside `CBTRN02C.cbl` (lines `7`-`21`) | Mandatory preservation | Untouched by the edit; the edit is at lines `400`-`420` and below |

### 0.5.5 Do NOT Refactor

The following items are correct-as-is and must not be refactored opportunistically as part of this bug fix:

- The reason-code numbering scheme (`100`, `101`, `102`, `103`, `109`) — `104` is added incrementally; no renumbering.
- The `WS-VALIDATION-TRAILER` 80-byte layout (`PIC 9(04)` + `PIC X(76)`) — unchanged.
- The `2500-WRITE-REJECT-REC` paragraph — unchanged.
- The main-loop dispatcher (`IF WS-VALIDATION-FAIL-REASON = 0` at [app/cbl/CBTRN02C.cbl:L211]) — unchanged.
- The credit-limit check (`102`) and expiration check (`103`) logic — preserved verbatim inside the new `ELSE` branch.

### 0.5.6 Do NOT Add

- No new validation reason codes beyond `104`.
- No new file definitions, `SELECT` clauses, or `FD` entries.
- No new working-storage variables.
- No new copybooks; no new `COPY` directives.
- No additional tests, fixtures, documentation files, or build/CI configuration beyond what is necessary to verify the fix.
- No diagnostic `DISPLAY` statements outside the existing comment style (the two pre-existing `DISPLAY` comment lines at [app/cbl/CBTRN02C.cbl:L401-L402] are retained for parity with the original maintainer's debug intent and are not converted to active code).

## 0.6 Verification Protocol

This section specifies the exact sequence of checks that confirm the bug is eliminated and that no other behavior of `CBTRN02C` has changed. The protocol is partitioned into bug-elimination confirmation (focused on inactive-account transactions) and regression checks (focused on every other reachable code path).

### 0.6.1 Bug Elimination Confirmation

#### 0.6.1.1 Test Fixtures

- `ACCTFILE` test bed:
    - At least one account with `ACCT-ACTIVE-STATUS = 'Y'` (control / regression).
    - At least one account with `ACCT-ACTIVE-STATUS = 'N'` (Inactive — primary failure case).
    - At least one account with `ACCT-ACTIVE-STATUS = 'C'` (Closed — bug-description case; defensive coverage).
    - Optional: one account with `ACCT-ACTIVE-STATUS = ' '` (space) to exercise the defensive fail-safe.
- `XREFFILE`: a card-to-account mapping that resolves each test card number to its corresponding account ID.
- `DALYTRAN.PS`: one transaction targeting each test account, all within the credit limit and with a transaction timestamp on or before the account's expiration date — this isolates `ACCT-ACTIVE-STATUS` as the only failing predicate for the non-`'Y'` cases.

#### 0.6.1.2 Execution

- Submit the `POSTTRAN` job via JCL [app/jcl/POSTTRAN.jcl:L23], which executes `STEP15 EXEC PGM=CBTRN02C` against the loaded fixtures.
- Capture the full step SYSOUT and the post-run contents of `TRANSACT.VSAM.KSDS`, `ACCTFILE`, `TCATBAL`, and `DALYREJS`.

#### 0.6.1.3 Pass Criteria

- The SYSOUT line `TRANSACTIONS PROCESSED :nnn` [app/cbl/CBTRN02C.cbl:L227] equals the total input count.
- The SYSOUT line `TRANSACTIONS REJECTED  :mmm` [app/cbl/CBTRN02C.cbl:L228] equals the number of fixture transactions whose target accounts had `ACCT-ACTIVE-STATUS` other than `'Y'`.
- Step return code is `4` whenever `mmm > 0` per [app/cbl/CBTRN02C.cbl:L229-L231], and `0` otherwise.
- For each rejected transaction:
    - A 430-byte record exists in `DALYREJS`.
    - Bytes 1-350 contain the original `DALYTRAN-RECORD` payload verbatim (per [app/cbl/CBTRN02C.cbl:L447]).
    - Bytes 351-354 contain the ASCII characters `0104` (the new reason code).
    - Bytes 355-430 contain `ACCOUNT NOT ACTIVE` left-justified and space-padded.
- For each non-`'Y'` account fixture:
    - `TRANSACT.VSAM.KSDS` contains no row keyed by the corresponding `DALYTRAN-ID`.
    - `ACCTFILE` row for that account has unchanged `ACCT-CURR-BAL`, `ACCT-CURR-CYC-CREDIT`, and `ACCT-CURR-CYC-DEBIT` versus the pre-job snapshot.
    - `TCATBAL` row for that account's transaction category has unchanged balance versus the pre-job snapshot.
- For each `'Y'` account fixture (control / regression):
    - `TRANSACT.VSAM.KSDS` contains a new row keyed by the transaction ID.
    - `ACCTFILE` balance fields are updated correctly per [app/cbl/CBTRN02C.cbl:L424-L444, app/cbl/CBTRN02C.cbl:L2800-paragraph].

#### 0.6.1.4 Fail Criteria

- Any `'Y'`-account transaction failing to post.
- Any non-`'Y'`-account transaction posting to `TRANSACT.VSAM.KSDS` or updating `ACCTFILE`/`TCATBAL`.
- Any reject record carrying a reason code other than `0104` for an inactive-account fixture (e.g., `0102`/`0103` would indicate the new check was bypassed and credit-limit/expiration logic fired instead).
- Reject record length not equal to 430 bytes, or trailer fields mis-aligned.

### 0.6.2 Regression Check

The regression suite is designed to confirm that every other code path in `CBTRN02C` behaves identically to its pre-fix behavior.

#### 0.6.2.1 Validation Path Coverage

| Pre-fix Path | Trigger Condition | Expected Outcome (Unchanged After Fix) |
|---|---|---|
| Reason `100` — invalid card | `DALYTRAN-CARD-NUM` not found in `XREFFILE` ([app/cbl/CBTRN02C.cbl:L380-L392]) | Reject record with reason `0100`, description `INVALID CARD NUMBER FOUND`; no posting |
| Reason `101` — account not found | Cross-reference returns an `XREF-ACCT-ID` that is missing from `ACCTFILE` ([app/cbl/CBTRN02C.cbl:L396-L399]) | Reject record with reason `0101`, description `ACCOUNT RECORD NOT FOUND`; no posting; the new active-status check is in the `NOT INVALID KEY` branch only, so it cannot interfere with this path |
| Reason `102` — over limit | `ACCT-ACTIVE-STATUS = 'Y'` AND `ACCT-CREDIT-LIMIT < WS-TEMP-BAL` ([app/cbl/CBTRN02C.cbl:L407-L413] inside the new `ELSE`) | Reject record with reason `0102`, description `OVERLIMIT TRANSACTION`; no posting |
| Reason `103` — expired | `ACCT-ACTIVE-STATUS = 'Y'` AND `ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS (1:10)` ([app/cbl/CBTRN02C.cbl:L414-L420] inside the new `ELSE`) | Reject record with reason `0103`, description `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`; no posting |
| Reason `109` — REWRITE failure | `ACCT-ACTIVE-STATUS = 'Y'` AND posting succeeds but the subsequent `REWRITE` in `2800-UPDATE-ACCOUNT-REC` fails ([app/cbl/CBTRN02C.cbl:L556]) | Existing ABEND/handler behavior; no change |
| Happy path — post | `ACCT-ACTIVE-STATUS = 'Y'` AND within credit limit AND within expiration | `2000-POST-TRANSACTION` runs; transaction inserted into `TRANSACT.VSAM.KSDS`; `ACCTFILE` and `TCATBAL` updated; `WS-VALIDATION-FAIL-REASON` remains `0` |

#### 0.6.2.2 Counter and Return-Code Integrity

- `WS-TRANSACTION-COUNT` continues to be incremented once per input record at [app/cbl/CBTRN02C.cbl:L206].
- `WS-REJECT-COUNT` continues to be incremented exactly once per rejection at [app/cbl/CBTRN02C.cbl:L214].
- `RETURN-CODE` continues to be set to `4` if and only if `WS-REJECT-COUNT > 0` per [app/cbl/CBTRN02C.cbl:L229-L231], preserving the operator-visible signal for downstream Control-M / CA-7 conditional logic.
- The closing displays `TRANSACTIONS PROCESSED :` and `TRANSACTIONS REJECTED  :` continue to render correctly.

#### 0.6.2.3 I/O Contract Preservation

- `DALYTRAN-FILE`, `TRANSACT-FILE`, `XREF-FILE`, `DALYREJS-FILE`, `ACCOUNT-FILE`, and `TCATBAL-FILE` `OPEN`/`READ`/`WRITE`/`REWRITE`/`CLOSE` operations are unchanged in the patched program — confirmed by static review of all `0000`/`0100`/`0200`/`0300`/`0400`/`0500` open paragraphs and the `9000`-`9500` close paragraphs.
- `DALYREJS` record length remains 430 bytes (`350 + 80`), matching the JCL allocation at [app/jcl/POSTTRAN.jcl:L34-L38].
- `RECORDING MODE`, `BLOCK CONTAINS`, and `ORGANIZATION` clauses are unaffected.

#### 0.6.2.4 Downstream Smoke Checks

Although `CBSTM03A.CBL` and `CBACT04C.cbl` are out of scope for modification, their downstream behavior should be smoke-tested to confirm the bug-fix delivers its intended business effect:

- Run `CREASTMT.jcl` after a `POSTTRAN` execution that includes inactive-account `DALYTRAN` records. Confirm that statements for inactive accounts do **not** include the rejected transactions (since they no longer appear in `TRANSACT.VSAM.KSDS`).
- Run `INTCALC.jcl` (`PGM=CBACT04C`) and confirm that inactive-account balances continue to be the pre-`POSTTRAN` balances (since the fix prevented `2800-UPDATE-ACCOUNT-REC` from mutating them).

#### 0.6.2.5 Compile-Time Regression

- Recompile `app/cbl/CBTRN02C.cbl` and verify zero new errors and zero new warnings versus the pre-fix compile log.
- Verify that all `PERFORM` targets remain resolvable (no orphan paragraph names, no dropped references).
- Verify that line-number shifts in any compiled symbol map remain internal to `CBTRN02C` and do not affect external linkage.

### 0.6.3 Acceptance Criteria Summary

The fix is accepted if and only if:

- All Pass Criteria in §0.6.1.3 are met.
- No Fail Criteria in §0.6.1.4 is observed.
- All regression rows in §0.6.2.1 produce their pre-fix expected outcomes.
- Counter, return-code, and I/O contract checks in §0.6.2.2 and §0.6.2.3 all pass.
- The compile-time regression in §0.6.2.5 is clean.

## 0.7 Rules

This section captures the rules and constraints that govern the fix. The user attached no explicit project-level rules to this task (rules inventory is empty), so the rules below are derived exclusively from the bug-fix prompt, the project's existing conventions discovered during repository investigation, and standard COBOL maintenance discipline.

### 0.7.1 User-Specified Rules

- **No user-specified implementation rules were provided** for this task. The rules registry returned an empty list.
- Therefore, no rule-mandated files outside the bug-fix scope are included in §0.5 Scope Boundaries.

### 0.7.2 Bug-Fix Prompt Mandates (Verbatim Adherence)

- **Only modify `CBTRN02C`** — the prompt explicitly forbids edits to any other file. §0.5.1 satisfies this by limiting the change to `app/cbl/CBTRN02C.cbl` and §0.5.4 enumerates the prohibited files.
- **Reject closed/inactive accounts to the reject pipeline** — the fix routes non-`'Y'` accounts through the existing `2500-WRITE-REJECT-REC` paragraph, exactly as the prompt requires.
- **Do NOT modify downstream `CBSTM03A.CBL` or `CBACT04C.cbl`** — confirmed in §0.5.4. These programs receive the now-clean `TRANSACT.VSAM.KSDS` and `ACCTFILE` produced by the fixed `CBTRN02C`.
- **Do NOT modify copybooks (`CVACT01Y.CPY`, `CVTRA06Y`)** — confirmed in §0.5.4. The `ACCT-ACTIVE-STATUS` field is already declared by `CVACT01Y` at [app/cpy/CVACT01Y.cpy:L4-L17] and reachable via the existing `COPY` directive at [app/cbl/CBTRN02C.cbl:L121].
- **Do NOT modify JCL files** — confirmed in §0.5.4. `POSTTRAN.jcl` already provisions `DALYREJS` at `LRECL=430` per [app/jcl/POSTTRAN.jcl:L34-L38].
- **Do NOT change input/output file interfaces** — confirmed in §0.6.2.3. All `OPEN`, `READ`, `WRITE`, `REWRITE`, and `CLOSE` semantics are preserved bit-for-bit.
- **Do NOT alter return codes to JCL** — confirmed in §0.6.2.2. `RETURN-CODE = 4` continues to fire whenever `WS-REJECT-COUNT > 0`; `RETURN-CODE = 0` continues to fire when all transactions post cleanly.

### 0.7.3 Coding and Style Conventions (Inferred from Repository)

These conventions were derived by reading `CBTRN02C.cbl` and adjacent COBOL files; they are followed by the fix to maintain stylistic consistency:

- **Reason codes are 3-digit integers stored in `PIC 9(04)`**, allocated incrementally in the order they are introduced. The fix uses `104` because `100`-`103` and `109` are already in use; this is consistent with how the existing codes are sequenced.
- **Reason descriptions are uppercase, brief, and end without a period**, fitting in `PIC X(76)`. The new description `ACCOUNT NOT ACTIVE` follows the same conventions as `INVALID CARD NUMBER FOUND` ([app/cbl/CBTRN02C.cbl:L386]), `ACCOUNT RECORD NOT FOUND` ([app/cbl/CBTRN02C.cbl:L398]), `OVERLIMIT TRANSACTION` ([app/cbl/CBTRN02C.cbl:L411]), and `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` ([app/cbl/CBTRN02C.cbl:L418]).
- **Status comparisons use single-character literals** in equality form (e.g., `END-OF-FILE = 'Y'` at [app/cbl/CBTRN02C.cbl:L202]). The fix uses `ACCT-ACTIVE-STATUS NOT = 'Y'` following this convention.
- **`'Y'`/`'N'` is the canonical active/inactive convention** ([app/cbl/COACTUPC.cbl:L193] and Tech Spec §4.2.1.2). The fix uses `'Y'` as the only positive value and treats any other byte as not-active.
- **Comments precede the code they describe**, using the `*` character in column 7. The fix prefixes the new check with a multi-line `*` comment explaining the bug-fix rationale, mirroring the explanatory comments at [app/cbl/CBTRN02C.cbl:L401-L402, L468] and elsewhere.
- **Structured COBOL with `IF/ELSE/END-IF`** is used throughout the program. The fix follows this style.
- **Apache 2.0 license header at lines `7`-`21` is preserved verbatim**, consistent with every other source file in the repository.

### 0.7.4 Discipline Constraints

- **Make the exact specified change only**: §0.4 specifies a single replacement at lines `400`-`420` of one file. No other edit is permitted.
- **Zero modifications outside the bug fix**: confirmed by §0.5.5 (Do NOT Refactor) and §0.5.6 (Do NOT Add).
- **Extensive testing to prevent regressions**: §0.6.2 enumerates regression paths for every other validation code (`100`, `101`, `102`, `103`, `109`), counter integrity, return-code integrity, and I/O contract preservation. The fix is rejected if any pre-fix code path is observed to change behavior.
- **Comments must explain the motive of the change** (per prompt's Change Instructions guidance). The inserted code includes a multi-line comment header at the top of the new `IF` block describing the rationale: rejection of non-active accounts, defensive coverage of `'N'`, `'C'`, blanks, and `LOW-VALUES`, and downstream protection of `TRANSACT`, `ACCTFILE`, and `TCATBAL`.

### 0.7.5 Compatibility Constraints

- **Compile compatibility**: the fix uses only standard COBOL constructs (`IF/ELSE/END-IF`, `MOVE`, single-character literal comparison) that are universal across z/OS COBOL, AWS Blu Age, and Micro Focus Enterprise Server runtimes. No compiler flag, dialect option, or language extension is required.
- **Runtime compatibility**: the fix introduces no new file, no new VSAM operation, no new `CALL`, and no new `EXEC` SQL/CICS verb. There is therefore no runtime, transaction-server, or DBMS dependency to validate.
- **Data compatibility**: the fix consumes only `ACCT-ACTIVE-STATUS` at offset 12 of the existing 300-byte `ACCOUNT-RECORD` layout. Existing `ACCTDATA` test files in `app/data/ASCII/` and `app/data/EBCDIC/` remain valid without modification.

## 0.8 Attachments

No attachments were provided with this task.

- **PDF, image, or document attachments**: none. The project's attachment registry returned zero items.
- **Figma designs**: none. There are no Figma frames, URLs, or design references associated with this bug fix, which is consistent with the fact that `CBTRN02C` is a batch COBOL program with no UI surface.
- **Reference URLs from the user prompt**: none.
- **External references consulted during diagnosis** (for completeness, not as user-provided attachments):
    - `https://github.com/aws-samples/aws-mainframe-modernization-carddemo` — public CardDemo source repository (Apache 2.0). Confirms that `POSTTRAN` runs `CBTRN02C` as the transaction-processing job step in the batch pipeline.
    - `https://aws.amazon.com/blogs/opensource/introducing-open-source-aws-carddemo-for-mainframe-modernization/` — AWS Open Source Blog announcement. Documents the batch pipeline order: refresh files, post transactions, then calculate interest — i.e., `POSTTRAN` → `INTCALC`. Confirms the downstream consumers that depend on a clean `TRANSACT.VSAM.KSDS`.
    - `https://deepwiki.com/aws-samples/aws-mainframe-modernization-carddemo` — community-curated documentation. Independent confirmation of the three-tier mainframe architecture and the role of `CBTRN02C` within batch processing.

No content from these external sources alters the fix specification; they are listed solely to document the breadth of diagnostic research performed in Phase BF2.

