#!/usr/bin/env bash
# =====================================================================
# check_no_business_logic.sh
#
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# =====================================================================
#
# Validation gate for the "Do not recreate algorithms, calculations, or
# business logic from production code inside test files" rule.
#
# Scans every `tests/cobol-check/*.cut` file for COBOL arithmetic verbs
# (COMPUTE, MULTIPLY, DIVIDE, ADD, SUBTRACT) that occur outside the
# allow-list windows BEFORE-EACH..END-BEFORE and AFTER-EACH..END-AFTER.
# Any match outside those windows fails the build.
#
# It additionally rejects any MOCK PARAGRAPH or MOCK SECTION directive,
# because mocking an internal paragraph of the program-under-test
# violates the "Mocking the internal function or method under test"
# forbidden pattern.
#
# Exits 0 on success, 1 on any violation.  Prints a clear diagnostic
# quoting the user's prompt back to the offender.
#
# Implementation note: the regular expressions below are written in
# POSIX-portable form (no \< / \> word-boundary escapes) so that they
# work across mawk (Ubuntu default) and gawk.
# =====================================================================

set -u
set -o pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SUITE_DIR="${REPO_ROOT}/tests/cobol-check"

if [ ! -d "${SUITE_DIR}" ]; then
    echo "check_no_business_logic.sh: ${SUITE_DIR} does not exist; nothing to check."
    exit 0
fi

# Find every .cut file (cobol-check unit-test).
mapfile -t cut_files < <(find "${SUITE_DIR}" -type f -name '*.cut' | sort)

if [ "${#cut_files[@]}" -eq 0 ]; then
    echo "check_no_business_logic.sh: No .cut files found under ${SUITE_DIR} -- pass."
    exit 0
fi

> /tmp/check_no_business_logic.out

# AWK program: tracks BEFORE-EACH / AFTER-EACH allow-list windows and
# emits a line for every COMPUTE/MULTIPLY/DIVIDE/ADD/SUBTRACT outside
# those windows, plus every MOCK PARAGRAPH or MOCK SECTION directive.
#
# The "edges" of identifier boundaries are detected by surrounding the
# pattern with character-class negations: an identifier character can
# be A-Z, 0-9, or '-'.
arith_pattern='(^|[^A-Z0-9-])(COMPUTE|MULTIPLY|DIVIDE|ADD|SUBTRACT)([^A-Z0-9-]|$)'
mock_paragraph_pattern='^[[:space:]]*MOCK[[:space:]]+(PARAGRAPH|SECTION)([^A-Z0-9-]|$)'
# BEFORE/AFTER/END-BEFORE/END-AFTER must be the first non-whitespace
# token on a line so we don't match the words appearing inside string
# literals (e.g., TESTCASE "describes BEFORE-EACH behavior").
before_pattern='^[[:space:]]*(BEFORE-EACH|BEFORE[[:space:]]+EACH)([^A-Z0-9-]|$)'
after_pattern='^[[:space:]]*(AFTER-EACH|AFTER[[:space:]]+EACH)([^A-Z0-9-]|$)'
end_block_pattern='^[[:space:]]*(END-BEFORE|END-AFTER)([^A-Z0-9-]|$)'

for f in "${cut_files[@]}"; do
    awk \
        -v ARITH="$arith_pattern" \
        -v MOCK_PARA="$mock_paragraph_pattern" \
        -v BEFORE="$before_pattern" \
        -v AFTER="$after_pattern" \
        -v END_BLK="$end_block_pattern" '
        BEGIN { in_setup = 0 }
        {
            line = $0
            sub(/\r$/, "", line)
            # Skip column-7 comments (fixed-format COBOL).
            if (length(line) >= 7 && substr(line, 7, 1) == "*") next
            upper = toupper(line)
            if (upper ~ BEFORE) { in_setup = 1 }
            if (upper ~ AFTER)  { in_setup = 1 }
            if (upper ~ END_BLK) { in_setup = 0; next }
            if (!in_setup && upper ~ ARITH) {
                printf "%s:%d:ARITH: %s\n", FILENAME, NR, line
            }
            if (upper ~ MOCK_PARA) {
                printf "%s:%d:MOCK_INTERNAL: %s\n", FILENAME, NR, line
            }
        }' "$f" >> /tmp/check_no_business_logic.out
done

if [ -s /tmp/check_no_business_logic.out ]; then
    cat <<'BANNER' >&2
================================================================
BLITZY VALIDATION GATE FAILED:
business logic detected outside imports/setup/invocation/assertions.
Re-author the test to PERFORM the production paragraph instead of
computing the expected value yourself.

The following lines violated the "no business logic in tests" rule
(COMPUTE, MULTIPLY, DIVIDE, ADD, SUBTRACT outside BEFORE-EACH /
 AFTER-EACH blocks; MOCK PARAGRAPH or MOCK SECTION on internal code):
================================================================
BANNER
    cat /tmp/check_no_business_logic.out >&2
    rm -f /tmp/check_no_business_logic.out
    exit 1
fi

rm -f /tmp/check_no_business_logic.out
echo "check_no_business_logic.sh: PASS - scanned ${#cut_files[@]} file(s)."
exit 0
