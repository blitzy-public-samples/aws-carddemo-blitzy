#!/usr/bin/env bash
# =====================================================================
# check_assertion_density.sh
#
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# =====================================================================
#
# Validation gate for the "Test assertions must validate return values
# and side effects of actual production code execution" rule.
#
# For every TESTCASE in every `tests/cobol-check/*.cut` file:
#   * the testcase MUST contain at least one EXPECT clause.
#   * if the testcase contains a MOCK directive (MOCK FILE / MOCK CALL
#     / MOCK CICS), it MUST also contain at least one VERIFY clause.
#
# Exits 0 on success, 1 on any violation.
#
# Implementation note: the regular expressions below are POSIX-portable
# (no \< / \> word-boundary escapes) so they work in both mawk and
# gawk.
# =====================================================================

set -u
set -o pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SUITE_DIR="${REPO_ROOT}/tests/cobol-check"

if [ ! -d "${SUITE_DIR}" ]; then
    echo "check_assertion_density.sh: ${SUITE_DIR} does not exist; nothing to check."
    exit 0
fi

mapfile -t cut_files < <(find "${SUITE_DIR}" -type f -name '*.cut' | sort)

if [ "${#cut_files[@]}" -eq 0 ]; then
    echo "check_assertion_density.sh: No .cut files found -- pass."
    exit 0
fi

> /tmp/check_assertion_density.out

testcase_pattern='^[[:space:]]*TESTCASE([^A-Z0-9-]|$)'
testsuite_pattern='^[[:space:]]*TESTSUITE([^A-Z0-9-]|$)'
expect_pattern='(^|[^A-Z0-9-])EXPECT([^A-Z0-9-]|$)'
verify_pattern='(^|[^A-Z0-9-])VERIFY([^A-Z0-9-]|$)'
mock_pattern='(^|[^A-Z0-9-])MOCK[[:space:]]+(FILE|CALL|CICS)([^A-Z0-9-]|$)'

for f in "${cut_files[@]}"; do
    awk \
        -v TC="$testcase_pattern" \
        -v TS="$testsuite_pattern" \
        -v EX="$expect_pattern" \
        -v VR="$verify_pattern" \
        -v MK="$mock_pattern" '
        function flush() {
            if (case_line > 0) {
                if (has_expect == 0) {
                    printf "%s:%d: TESTCASE %s -- missing EXPECT\n",
                           FILENAME, case_line, case_name
                }
                if (has_mock != 0 && has_verify == 0) {
                    printf "%s:%d: TESTCASE %s -- has MOCK but no VERIFY\n",
                           FILENAME, case_line, case_name
                }
            }
        }
        function reset() {
            case_line = 0; case_name = ""
            has_expect = 0; has_mock = 0; has_verify = 0
        }
        BEGIN { reset() }
        {
            line = $0
            sub(/\r$/, "", line)
            if (length(line) >= 7 && substr(line, 7, 1) == "*") next
            upper = toupper(line)
            if (upper ~ TC) {
                flush(); reset()
                case_line = NR; case_name = line
                next
            }
            if (upper ~ TS) {
                flush(); reset()
                next
            }
            if (case_line == 0) next
            if (upper ~ EX) has_expect = 1
            if (upper ~ MK) has_mock   = 1
            if (upper ~ VR) has_verify = 1
        }
        END { flush() }
        ' "$f" >> /tmp/check_assertion_density.out
done

if [ -s /tmp/check_assertion_density.out ]; then
    cat <<'BANNER' >&2
================================================================
BLITZY VALIDATION GATE FAILED:
Assertion density violation.  Every TESTCASE must contain at least
one EXPECT clause; testcases that declare a MOCK FILE / MOCK CALL /
MOCK CICS directive must additionally contain at least one VERIFY
clause to confirm the mock was invoked the expected number of times.

The following testcases violated the rule:
================================================================
BANNER
    cat /tmp/check_assertion_density.out >&2
    rm -f /tmp/check_assertion_density.out
    exit 1
fi

rm -f /tmp/check_assertion_density.out
echo "check_assertion_density.sh: PASS - scanned ${#cut_files[@]} file(s)."
exit 0
