#!/usr/bin/env bash
# =============================================================================
# test_cbtrn02c.sh
#
# Automated regression test for the CBTRN02C reason-code 104 ("ACCOUNT NOT
# ACTIVE") fix, using GnuCOBOL flat-file simulation.
#
# WHAT IS BEING TESTED
# --------------------
# CBTRN02C is the batch transaction-posting program executed by job step
# POSTTRAN.STEP15. The fix (app/cbl/CBTRN02C.cbl, paragraph 1500-B-LOOKUP-ACCT)
# adds a guard: when the target account's ACCT-ACTIVE-STATUS is not 'Y', the
# transaction is REJECTED with reason code 104 / "ACCOUNT NOT ACTIVE" and
# written to the DALYREJS reject file, instead of being posted to TRANSACT /
# ACCTFILE / TCATBAL. Active ('Y') accounts continue to post unchanged.
#
# STRATEGY (flat-file simulation)
# -------------------------------
# The mainframe VSAM datasets are simulated on Linux with GnuCOBOL files:
#   DALYTRAN  - sequential, 350-byte fixed records (daily transactions, input)
#   XREFFILE  - INDEXED  (card-number -> account-id cross reference, input)
#   ACCTFILE  - INDEXED  (account master; carries ACCT-ACTIVE-STATUS, input)
#   TCATBALF  - INDEXED  (transaction-category balances; starts empty, I-O)
#   TRANFILE  - INDEXED  (posted transaction master; created by CBTRN02C)
#   DALYREJS  - sequential, 430-byte fixed records (rejected transactions, out)
#
# XREFFILE / ACCTFILE / TCATBALF are INDEXED files (GnuCOBOL BDB) and therefore
# cannot be written as plain flat byte streams. A tiny COBOL loader (generated
# below) populates them using the PRODUCTION copybooks (CVTRA06Y, CVACT01Y,
# CVACT03Y) so that record layouts and numeric sign-encoding match exactly what
# CBTRN02C reads. The DALYTRAN sequential input is generated the same way so the
# 350-byte fixed-width records are byte-perfect.
#
# FOUR ACCOUNT SCENARIOS (one daily transaction each)
# ---------------------------------------------------
#   ACCT 00000000001  status 'Y' (active)    -> MUST post, MUST NOT be rejected
#   ACCT 00000000002  status 'N' (inactive)  -> MUST be rejected with 0104
#   ACCT 00000000003  status 'C' (closed)    -> MUST be rejected with 0104
#   ACCT 00000000004  status ' ' (blank)     -> MUST be rejected with 0104
# Every transaction is within the credit limit and before the account
# expiration date, so ACCT-ACTIVE-STATUS is the ONLY failing predicate for the
# non-'Y' cases (this isolates reason 104 from reasons 102/103).
#
# ASSERTIONS
# ----------
#   * DALYREJS holds exactly 3 reject records (430 bytes each = 1290 bytes).
#   * For each reject record: bytes 351-354 == "0104" and bytes 355-430 begin
#     with "ACCOUNT NOT ACTIVE".
#   * The 'Y' transaction is ABSENT from DALYREJS and PRESENT in TRANFILE.
#   * The 'N'/'C'/' ' transactions are PRESENT in DALYREJS and ABSENT from
#     TRANFILE.
#   * SYSOUT reports 4 processed / 3 rejected and the step returns RC=4
#     (RC=4 is the EXPECTED, normal signal when rejects occur - not a failure).
#
# EXIT STATUS
# -----------
#   0  all assertions passed
#   1  one or more assertions failed, or a setup/build/run error occurred
#
# All build artifacts and simulated datasets are created under a private
# temporary directory and removed on exit. Set KEEP_ARTIFACTS=1 to retain them
# for debugging. Nothing is ever written inside the repository tree.
# =============================================================================

set -u

# ----------------------------------------------------------------------------
# Locate the repository root relative to this script (tests/ is at repo root).
# ----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"

CBL_SRC="${REPO_ROOT}/app/cbl/CBTRN02C.cbl"
CPY_DIR="${REPO_ROOT}/app/cpy"
CPYBMS_DIR="${REPO_ROOT}/app/cpy-bms"

# Compile flags mandated by the project (fixed-format IBM dialect + copybooks).
COBFLAGS=(-std=ibm -I "${CPY_DIR}" -I "${CPYBMS_DIR}")

# Expected text of the reject description for non-active accounts (18 chars).
EXPECT_DESC="ACCOUNT NOT ACTIVE"

# ----------------------------------------------------------------------------
# Pass / fail bookkeeping.
# ----------------------------------------------------------------------------
PASS_COUNT=0
FAIL_COUNT=0

ok()   { printf '  [PASS] %s\n' "$1"; PASS_COUNT=$((PASS_COUNT + 1)); }
bad()  { printf '  [FAIL] %s\n' "$1"; FAIL_COUNT=$((FAIL_COUNT + 1)); }

# assert_eq <description> <expected> <actual>
assert_eq() {
    if [ "$2" = "$3" ]; then
        ok "$1 (= '$2')"
    else
        bad "$1 -- expected '$2', got '$3'"
    fi
}

# assert_present <description> <file> <needle>   (binary-safe substring search)
assert_present() {
    if LC_ALL=C grep -aq -- "$3" "$2" 2>/dev/null; then
        ok "$1"
    else
        bad "$1 -- '$3' not found in $(basename "$2")"
    fi
}

# assert_absent <description> <file> <needle>
assert_absent() {
    if LC_ALL=C grep -aq -- "$3" "$2" 2>/dev/null; then
        bad "$1 -- '$3' unexpectedly found in $(basename "$2")"
    else
        ok "$1"
    fi
}

# read_bytes <file> <skip> <count>   -> raw bytes on stdout (1-based pos = skip+1)
read_bytes() {
    dd if="$1" bs=1 skip="$2" count="$3" 2>/dev/null
}

fatal() {
    printf '\nFATAL: %s\n' "$1" >&2
    printf 'RESULT: FAIL (setup/build/run error)\n' >&2
    exit 1
}

echo "============================================================"
echo " CBTRN02C reason-code 104 regression test"
echo " repo root : ${REPO_ROOT}"
echo "============================================================"

# ----------------------------------------------------------------------------
# Pre-flight checks.
# ----------------------------------------------------------------------------
command -v cobc    >/dev/null 2>&1 || fatal "cobc (GnuCOBOL) not found on PATH"
command -v cobcrun >/dev/null 2>&1 || fatal "cobcrun (GnuCOBOL) not found on PATH"
[ -f "${CBL_SRC}" ]               || fatal "source not found: ${CBL_SRC}"
[ -d "${CPY_DIR}" ]               || fatal "copybook dir not found: ${CPY_DIR}"

echo "Using: $(cobc --version 2>/dev/null | head -1)"

# ----------------------------------------------------------------------------
# Private work area (build artifacts + simulated datasets). Auto-removed.
# ----------------------------------------------------------------------------
WORK="$(mktemp -d "${TMPDIR:-/tmp}/cbtrn02c_test.XXXXXX")" || fatal "mktemp failed"
cleanup() {
    if [ "${KEEP_ARTIFACTS:-0}" = "1" ]; then
        printf '\n(KEEP_ARTIFACTS=1) artifacts retained in: %s\n' "${WORK}"
    else
        rm -rf "${WORK}"
    fi
}
trap cleanup EXIT
cd "${WORK}" || fatal "cannot cd to work dir ${WORK}"

# ----------------------------------------------------------------------------
# Generate the COBOL fixture loader. It writes the sequential DALYTRAN input and
# the INDEXED ACCTFILE / XREFFILE, and creates an empty INDEXED TCATBALF, all via
# the production copybooks so the byte layout matches CBTRN02C exactly.
# ----------------------------------------------------------------------------
cat > "${WORK}/LOADFIX.cbl" <<'COBOL_EOF'
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LOADFIX.
      *****************************************************************
      * Test-fixture loader for the CBTRN02C reason-code 104 test.
      * Creates the input datasets CBTRN02C expects:
      *   DALYTRAN (sequential, 350-byte) - one transaction per scenario
      *   XREFFILE (indexed) - card -> account cross reference
      *   ACCTFILE (indexed) - account master w/ active status
      *   TCATBALF (indexed) - created empty (CBTRN02C opens I-O)
      * Uses the production copybooks so layouts/sign encodings match.
      *****************************************************************
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN
                  ORGANIZATION IS SEQUENTIAL
                  ACCESS MODE  IS SEQUENTIAL
                  FILE STATUS  IS WS-ST.
           SELECT XREF-FILE ASSIGN TO XREFFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-XREF-CARD-NUM
                  FILE STATUS  IS WS-ST.
           SELECT ACCOUNT-FILE ASSIGN TO ACCTFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-ACCT-ID
                  FILE STATUS  IS WS-ST.
           SELECT TCATBAL-FILE ASSIGN TO TCATBALF
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS RANDOM
                  RECORD KEY   IS FD-TRAN-CAT-KEY
                  FILE STATUS  IS WS-ST.
       DATA DIVISION.
       FILE SECTION.
       FD  DALYTRAN-FILE.
       01  FD-TRAN-RECORD.
           05 FD-TRAN-ID            PIC X(16).
           05 FD-CUST-DATA          PIC X(334).
       FD  XREF-FILE.
       01  FD-XREFFILE-REC.
           05 FD-XREF-CARD-NUM      PIC X(16).
           05 FD-XREF-DATA          PIC X(34).
       FD  ACCOUNT-FILE.
       01  FD-ACCTFILE-REC.
           05 FD-ACCT-ID            PIC 9(11).
           05 FD-ACCT-DATA          PIC X(289).
       FD  TCATBAL-FILE.
       01  FD-TRAN-CAT-BAL-RECORD.
           05 FD-TRAN-CAT-KEY.
              10 FD-TRANCAT-ACCT-ID PIC 9(11).
              10 FD-TRANCAT-TYPE-CD PIC X(02).
              10 FD-TRANCAT-CD      PIC 9(04).
           05 FD-FD-TRAN-CAT-DATA   PIC X(33).
       WORKING-STORAGE SECTION.
       01  WS-ST                    PIC XX.
       01  WS-I                     PIC 9(02).
       COPY CVTRA06Y.
       COPY CVACT01Y.
       COPY CVACT03Y.
      * Scenario table: account-id, card-number, active-status, tran-id.
       01  WS-SCN-TABLE.
           05 FILLER PIC X(11) VALUE '00000000001'.
           05 FILLER PIC X(16) VALUE '1111111111111111'.
           05 FILLER PIC X(01) VALUE 'Y'.
           05 FILLER PIC X(16) VALUE 'TRANTEST00000001'.
           05 FILLER PIC X(11) VALUE '00000000002'.
           05 FILLER PIC X(16) VALUE '2222222222222222'.
           05 FILLER PIC X(01) VALUE 'N'.
           05 FILLER PIC X(16) VALUE 'TRANTEST00000002'.
           05 FILLER PIC X(11) VALUE '00000000003'.
           05 FILLER PIC X(16) VALUE '3333333333333333'.
           05 FILLER PIC X(01) VALUE 'C'.
           05 FILLER PIC X(16) VALUE 'TRANTEST00000003'.
           05 FILLER PIC X(11) VALUE '00000000004'.
           05 FILLER PIC X(16) VALUE '4444444444444444'.
           05 FILLER PIC X(01) VALUE ' '.
           05 FILLER PIC X(16) VALUE 'TRANTEST00000004'.
       01  WS-SCN-R REDEFINES WS-SCN-TABLE.
           05 WS-SCN OCCURS 4 TIMES.
              10 WS-SCN-ACCT PIC 9(11).
              10 WS-SCN-CARD PIC X(16).
              10 WS-SCN-STAT PIC X(01).
              10 WS-SCN-TRAN PIC X(16).
       PROCEDURE DIVISION.
           OPEN OUTPUT DALYTRAN-FILE
           IF WS-ST NOT = '00'
              DISPLAY 'LOADFIX ERROR OPEN DALYTRAN ST=' WS-ST
              MOVE 16 TO RETURN-CODE
              GOBACK
           END-IF
           OPEN OUTPUT XREF-FILE
           IF WS-ST NOT = '00'
              DISPLAY 'LOADFIX ERROR OPEN XREFFILE ST=' WS-ST
              MOVE 16 TO RETURN-CODE
              GOBACK
           END-IF
           OPEN OUTPUT ACCOUNT-FILE
           IF WS-ST NOT = '00'
              DISPLAY 'LOADFIX ERROR OPEN ACCTFILE ST=' WS-ST
              MOVE 16 TO RETURN-CODE
              GOBACK
           END-IF
           OPEN OUTPUT TCATBAL-FILE
           IF WS-ST NOT = '00'
              DISPLAY 'LOADFIX ERROR OPEN TCATBALF ST=' WS-ST
              MOVE 16 TO RETURN-CODE
              GOBACK
           END-IF

           PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 4
      *       --- DALYTRAN sequential record (350 bytes) ---
              INITIALIZE DALYTRAN-RECORD
              MOVE WS-SCN-TRAN(WS-I) TO DALYTRAN-ID
              MOVE '01' TO DALYTRAN-TYPE-CD
              MOVE 0001 TO DALYTRAN-CAT-CD
              MOVE 'POS' TO DALYTRAN-SOURCE
              MOVE 'TEST TRANSACTION' TO DALYTRAN-DESC
              MOVE 100.00 TO DALYTRAN-AMT
              MOVE 000000001 TO DALYTRAN-MERCHANT-ID
              MOVE 'TEST MERCHANT' TO DALYTRAN-MERCHANT-NAME
              MOVE 'TEST CITY' TO DALYTRAN-MERCHANT-CITY
              MOVE '00000' TO DALYTRAN-MERCHANT-ZIP
              MOVE WS-SCN-CARD(WS-I) TO DALYTRAN-CARD-NUM
              MOVE '2020-01-01-12.00.00.000000' TO DALYTRAN-ORIG-TS
              MOVE SPACES TO DALYTRAN-PROC-TS
              WRITE FD-TRAN-RECORD FROM DALYTRAN-RECORD
              IF WS-ST NOT = '00'
                 DISPLAY 'LOADFIX ERROR WRITE DALYTRAN ST=' WS-ST
                 MOVE 16 TO RETURN-CODE
                 GOBACK
              END-IF
      *       --- ACCOUNT indexed record (300 bytes) ---
              INITIALIZE ACCOUNT-RECORD
              MOVE WS-SCN-ACCT(WS-I) TO ACCT-ID
              MOVE WS-SCN-STAT(WS-I) TO ACCT-ACTIVE-STATUS
              MOVE 0                 TO ACCT-CURR-BAL
              MOVE 5000000000.00     TO ACCT-CREDIT-LIMIT
              MOVE 5000000000.00     TO ACCT-CASH-CREDIT-LIMIT
              MOVE '2010-01-01'      TO ACCT-OPEN-DATE
              MOVE '2099-12-31'      TO ACCT-EXPIRAION-DATE
              MOVE '2020-01-01'      TO ACCT-REISSUE-DATE
              MOVE 0                 TO ACCT-CURR-CYC-CREDIT
              MOVE 0                 TO ACCT-CURR-CYC-DEBIT
              MOVE '00000'           TO ACCT-ADDR-ZIP
              MOVE 'TESTGRP'         TO ACCT-GROUP-ID
              WRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
              IF WS-ST NOT = '00'
                 DISPLAY 'LOADFIX ERROR WRITE ACCTFILE ST=' WS-ST
                 MOVE 16 TO RETURN-CODE
                 GOBACK
              END-IF
      *       --- XREF indexed record (50 bytes) ---
              INITIALIZE CARD-XREF-RECORD
              MOVE WS-SCN-CARD(WS-I) TO XREF-CARD-NUM
              MOVE 000000001         TO XREF-CUST-ID
              MOVE WS-SCN-ACCT(WS-I) TO XREF-ACCT-ID
              WRITE FD-XREFFILE-REC FROM CARD-XREF-RECORD
              IF WS-ST NOT = '00'
                 DISPLAY 'LOADFIX ERROR WRITE XREFFILE ST=' WS-ST
                 MOVE 16 TO RETURN-CODE
                 GOBACK
              END-IF
           END-PERFORM

           CLOSE DALYTRAN-FILE XREF-FILE ACCOUNT-FILE TCATBAL-FILE
           DISPLAY 'LOADFIX OK'
           MOVE 0 TO RETURN-CODE
           GOBACK.
COBOL_EOF

# ----------------------------------------------------------------------------
# Compile the loader (executable) and CBTRN02C (dynamically callable module).
# ----------------------------------------------------------------------------
echo
echo "--- Compiling fixture loader ---"
if ! cobc -x "${COBFLAGS[@]}" -o "${WORK}/loadfix" "${WORK}/LOADFIX.cbl" \
        > "${WORK}/loadfix_compile.log" 2>&1; then
    cat "${WORK}/loadfix_compile.log" >&2
    fatal "fixture loader failed to compile"
fi
ok "fixture loader compiled"

echo
echo "--- Compiling CBTRN02C (module) ---"
if ! cobc -m "${COBFLAGS[@]}" -o "${WORK}/CBTRN02C.so" "${CBL_SRC}" \
        > "${WORK}/cbtrn02c_compile.log" 2>&1; then
    cat "${WORK}/cbtrn02c_compile.log" >&2
    fatal "CBTRN02C failed to compile"
fi
if [ ! -s "${WORK}/CBTRN02C.so" ]; then
    fatal "CBTRN02C module artifact missing or empty"
fi
ok "CBTRN02C compiled to loadable module"

# ----------------------------------------------------------------------------
# Map the logical dataset names to physical files (GnuCOBOL DD_ convention).
# ----------------------------------------------------------------------------
export DD_DALYTRAN="${WORK}/dalytran.dat"
export DD_XREFFILE="${WORK}/xreffile.dat"
export DD_ACCTFILE="${WORK}/acctfile.dat"
export DD_TCATBALF="${WORK}/tcatbalf.dat"
export DD_TRANFILE="${WORK}/tranfile.dat"
export DD_DALYREJS="${WORK}/dalyrejs.dat"
export COB_LIBRARY_PATH="${WORK}"

# Ensure a clean slate (defensive; mktemp dir is already empty of datasets).
rm -f "${DD_DALYTRAN}" "${DD_XREFFILE}" "${DD_ACCTFILE}" \
      "${DD_TCATBALF}" "${DD_TRANFILE}" "${DD_DALYREJS}"

# ----------------------------------------------------------------------------
# Build the fixtures.
# ----------------------------------------------------------------------------
echo
echo "--- Loading test fixtures ---"
if ! "${WORK}/loadfix" > "${WORK}/loadfix.out" 2>&1; then
    cat "${WORK}/loadfix.out" >&2
    fatal "fixture loader failed at runtime"
fi
if ! grep -q 'LOADFIX OK' "${WORK}/loadfix.out"; then
    cat "${WORK}/loadfix.out" >&2
    fatal "fixture loader did not report success"
fi
ok "fixtures loaded (DALYTRAN + XREFFILE + ACCTFILE + empty TCATBALF)"

# Sanity: DALYTRAN must be exactly 4 * 350 = 1400 bytes.
DT_SIZE="$(wc -c < "${DD_DALYTRAN}" | tr -d ' ')"
assert_eq "DALYTRAN input size (4 x 350 bytes)" "1400" "${DT_SIZE}"

# ----------------------------------------------------------------------------
# Run CBTRN02C. RC=4 is EXPECTED here because the batch contains rejects, so we
# capture the exit code rather than letting it abort the script.
# ----------------------------------------------------------------------------
echo
echo "--- Running CBTRN02C ---"
cobcrun CBTRN02C > "${WORK}/cbtrn02c.out" 2>&1
RUN_RC=$?
echo "----- CBTRN02C SYSOUT -----"
sed 's/^/    /' "${WORK}/cbtrn02c.out"
echo "---------------------------"

# A GnuCOBOL ABEND (e.g. CALL 'CEE3ABD') indicates a setup problem, not a pass.
if grep -q 'ABENDING PROGRAM' "${WORK}/cbtrn02c.out"; then
    fatal "CBTRN02C abended - test fixtures are invalid"
fi
[ -f "${DD_DALYREJS}" ] || fatal "DALYREJS reject file was not produced"

echo
echo "--- Assertions ---"

# (1) Return code: rejects present => RC=4.
assert_eq "CBTRN02C return code (rejects present => RC=4)" "4" "${RUN_RC}"

# (2) SYSOUT counters.
PROC_LINE="$(grep 'TRANSACTIONS PROCESSED' "${WORK}/cbtrn02c.out" | tr -dc '0-9')"
REJ_LINE="$(grep 'TRANSACTIONS REJECTED'  "${WORK}/cbtrn02c.out" | tr -dc '0-9')"
# Strip leading zeros for numeric comparison (values are zero-padded PIC 9(09)).
assert_eq "SYSOUT transactions processed" "4" "$(( 10#${PROC_LINE:-0} ))"
assert_eq "SYSOUT transactions rejected"  "3" "$(( 10#${REJ_LINE:-0} ))"

# (3) DALYREJS must contain exactly three 430-byte reject records.
REJ_SIZE="$(wc -c < "${DD_DALYREJS}" | tr -d ' ')"
assert_eq "DALYREJS size (3 x 430 bytes)" "1290" "${REJ_SIZE}"

# (4) Inspect each reject record at the documented byte offsets:
#       bytes   1-350 : original DALYTRAN payload (tran-id is bytes 1-16)
#       bytes 351-354 : reason code  (PIC 9(04))  -> must be "0104"
#       bytes 355-430 : description  (PIC X(76))  -> must begin "ACCOUNT NOT ACTIVE"
#     Reject records appear in DALYTRAN processing order: N, C, then blank.
EXPECTED_TRANS=("TRANTEST00000002" "TRANTEST00000003" "TRANTEST00000004")
REC_LEN=430
i=0
while [ "${i}" -lt 3 ]; do
    base=$(( i * REC_LEN ))
    tid="$(read_bytes "${DD_DALYREJS}" "${base}" 16)"
    reason="$(read_bytes "${DD_DALYREJS}" "$(( base + 350 ))" 4)"
    desc="$(read_bytes "${DD_DALYREJS}" "$(( base + 354 ))" 18)"
    assert_eq "reject #$(( i + 1 )) tran-id"               "${EXPECTED_TRANS[$i]}" "${tid}"
    assert_eq "reject #$(( i + 1 )) reason (bytes 351-354)" "0104"                  "${reason}"
    assert_eq "reject #$(( i + 1 )) desc   (bytes 355-...)" "${EXPECT_DESC}"        "${desc}"
    i=$(( i + 1 ))
done

# (5) The active ('Y') transaction must NOT be in the reject file, and the
#     three non-'Y' transactions MUST be present.
assert_absent  "active 'Y' transaction not rejected"        "${DD_DALYREJS}" "TRANTEST00000001"
assert_present "inactive 'N' transaction rejected"          "${DD_DALYREJS}" "TRANTEST00000002"
assert_present "closed   'C' transaction rejected"          "${DD_DALYREJS}" "TRANTEST00000003"
assert_present "blank    ' ' transaction rejected"          "${DD_DALYREJS}" "TRANTEST00000004"

# (6) The active ('Y') transaction MUST have posted to TRANFILE, and the three
#     non-'Y' transactions MUST NOT have posted.
if [ -f "${DD_TRANFILE}" ]; then
    assert_present "active 'Y' transaction posted to TRANFILE" "${DD_TRANFILE}" "TRANTEST00000001"
    assert_absent  "inactive 'N' transaction not posted"       "${DD_TRANFILE}" "TRANTEST00000002"
    assert_absent  "closed   'C' transaction not posted"       "${DD_TRANFILE}" "TRANTEST00000003"
    assert_absent  "blank    ' ' transaction not posted"       "${DD_TRANFILE}" "TRANTEST00000004"
else
    bad "TRANFILE (posted transactions) was not produced"
fi

# ----------------------------------------------------------------------------
# Summary.
# ----------------------------------------------------------------------------
echo
echo "============================================================"
printf ' Assertions passed: %d   failed: %d\n' "${PASS_COUNT}" "${FAIL_COUNT}"
if [ "${FAIL_COUNT}" -eq 0 ]; then
    echo " RESULT: PASS - reason code 104 (ACCOUNT NOT ACTIVE) verified"
    echo "============================================================"
    exit 0
else
    echo " RESULT: FAIL"
    echo "============================================================"
    exit 1
fi
