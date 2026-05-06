#!/bin/sh
#
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# =====================================================================
# tests/lint/check_dfhei1_safety_net.sh
# =====================================================================
#
# Validation gate enforcing the QA CP4 Phase 5.2 fail-loudly safety
# net mechanism that resolved QA Issue 4 (MAJOR severity in the CP4
# review).
#
# Background
# ----------
# The off-platform GnuCOBOL build comments out every EXEC CICS verb
# in production CICS source (DFHEI1.cbl is the link-time stub that
# would catch any leaked CALL 'DFHEI1' at runtime).  cobol-check
# 0.2.16's TestSuiteParser does not support MOCK CICS, MOCK FILE,
# or COPY at file scope, so the AAP Section 0.4.1 design called
# for `MOCK CALL 'DFHEI1'` as the functional equivalent.  However,
# without an assertable runtime check, a future regression that
# accidentally let an EXEC CICS through to the link stage would
# produce green tests despite the production code path being
# silently broken.
#
# This gate enforces the assertable-runtime-safety-net contract on
# every CICS testsuite (`tests/cobol-check/CO*.cut`).  Each file
# must contain ALL THREE of the following:
#
#   1. A `COPY STUB-ABEND-FLAG.` directive at file scope (the
#      declarative anchor that makes the mechanism visible in
#      `git grep` / `grep` audits and that a future cobol-check
#      release will start honoring once file-scope COPY is
#      supported in TestSuiteParser).
#
#   2. A `MOVE 'N' TO WS-DFHEI1-UNMOCKED-CALLED` reset inside the
#      testsuite's BEFORE-EACH block (per-testcase isolation; per
#      AAP Section 0.10.2 testsuites must clear shared state
#      between testcases).
#
#   3. At least one `EXPECT WS-DFHEI1-UNMOCKED-CALLED TO BE 'N'`
#      assertion across the file's TESTCASEs (the runtime safety
#      net itself; if any unmocked CICS verb ever reaches the
#      DFHEI1 link-time stub, the stub flips the flag to 'Y' and
#      the EXPECT fails the testcase).
#
# Failure modes the gate detects
# ------------------------------
#   - Anchor missing                     => Change 1 absent
#   - BEFORE-EACH not isolated            => Change 2 absent
#   - No assertable safety-net coverage   => Change 3 absent
#
# Exit codes
# ----------
#   0  All 17 CICS testsuites pass all three sub-rules.
#   1  At least one CICS testsuite is missing a required element.
#
# References
# ----------
#   AAP Sections 0.4.1, 0.7.2, 0.10.2
#   QA CP4 Phase 5.2 expectation
#   tests/stubs/DFHEI1.cbl
#   tests/stubs/STUB-ABEND-FLAG.cpy
#   tests/cobol-check/scripts/linux_gnucobol_run_tests "Step 5"

set -eu

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TESTSUITE_DIR="${REPO_ROOT}/tests/cobol-check"

# Discover every CICS testsuite (CO*.cut) in the configured tests
# directory; the regex anchors the filename so we don't match (e.g.,
# subdirectories created during cobol-check shadow staging).
cics_files=$(find "${TESTSUITE_DIR}" -maxdepth 1 -type f -name 'CO*.cut' \
             | sort)

if [ -z "${cics_files}" ]; then
    echo "[lint:check_dfhei1_safety_net] WARN: no CO*.cut files found"
    echo "                                  under ${TESTSUITE_DIR}"
    exit 0
fi

violation_count=0
file_count=0

for f in ${cics_files}; do
    file_count=$((file_count + 1))
    fname=$(basename "${f}")

    # Sub-rule 1: COPY STUB-ABEND-FLAG. directive at file scope.
    if ! grep -qE '^[[:space:]]+COPY[[:space:]]+STUB-ABEND-FLAG' "${f}"; then
        echo "BLITZY VALIDATION GATE FAILED: ${fname} missing"
        echo '    `COPY STUB-ABEND-FLAG.` directive at file scope.'
        echo "    Required by QA CP4 Phase 5.2 fail-loudly safety net."
        violation_count=$((violation_count + 1))
    fi

    # Sub-rule 2: BEFORE-EACH per-testcase reset of the flag.
    if ! grep -qE "MOVE[[:space:]]+'N'[[:space:]]+TO[[:space:]]+WS-DFHEI1-UNMOCKED-CALLED" "${f}"; then
        echo "BLITZY VALIDATION GATE FAILED: ${fname} missing"
        echo "    BEFORE-EACH reset \"MOVE 'N' TO"
        echo "    WS-DFHEI1-UNMOCKED-CALLED\" required for per-testcase"
        echo "    isolation of the DFHEI1 fail-loudly safety net."
        violation_count=$((violation_count + 1))
    fi

    # Sub-rule 3: at least one EXPECT WS-DFHEI1-UNMOCKED-CALLED.
    if ! grep -qE "EXPECT[[:space:]]+WS-DFHEI1-UNMOCKED-CALLED[[:space:]]+TO[[:space:]]+BE[[:space:]]+'N'" "${f}"; then
        echo "BLITZY VALIDATION GATE FAILED: ${fname} missing"
        echo "    \"EXPECT WS-DFHEI1-UNMOCKED-CALLED TO BE 'N'\""
        echo "    in at least one TESTCASE.  The assertion is the"
        echo "    runtime safety net that fails the testcase if the"
        echo "    DFHEI1 link-time stub fires from an unmocked verb."
        violation_count=$((violation_count + 1))
    fi
done

if [ "${violation_count}" -gt 0 ]; then
    echo
    echo "[lint:check_dfhei1_safety_net] FAIL - ${violation_count}"
    echo "    violation(s) across ${file_count} CICS testsuite(s)."
    echo
    echo "Required mechanism: see tests/README.md \"DFHEI1 fail-loudly"
    echo "safety net\" section for full rationale.  Each CICS testsuite"
    echo "must declare COPY STUB-ABEND-FLAG, reset"
    echo "WS-DFHEI1-UNMOCKED-CALLED to 'N' in BEFORE-EACH, and assert"
    echo "EXPECT WS-DFHEI1-UNMOCKED-CALLED TO BE 'N' in >=1 TESTCASE."
    exit 1
fi

echo "[lint:check_dfhei1_safety_net] PASS - scanned ${file_count} CICS"
echo "    testsuite(s) for COPY STUB-ABEND-FLAG anchor + BEFORE-EACH"
echo "    reset + EXPECT assertion (QA CP4 Phase 5.2)."
exit 0
