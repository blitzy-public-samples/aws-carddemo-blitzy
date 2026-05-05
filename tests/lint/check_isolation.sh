#!/usr/bin/env bash
# =====================================================================
# check_isolation.sh
#
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# =====================================================================
#
# Validation gate for the "Maintain test isolation" rule.
#
# Every `tests/cobol-check/*.cut` file MUST declare a BEFORE-EACH block
# (so that working-storage state is reset before each TESTCASE).
#
# Exits 0 on success, 1 on any violation.
# =====================================================================

set -u
set -o pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SUITE_DIR="${REPO_ROOT}/tests/cobol-check"

if [ ! -d "${SUITE_DIR}" ]; then
    echo "check_isolation.sh: ${SUITE_DIR} does not exist; nothing to check."
    exit 0
fi

mapfile -t cut_files < <(find "${SUITE_DIR}" -type f -name '*.cut' | sort)

if [ "${#cut_files[@]}" -eq 0 ]; then
    echo "check_isolation.sh: No .cut files found -- pass."
    exit 0
fi

violations=0
> /tmp/check_isolation.out

# BEFORE-EACH must be the first non-whitespace token on a line so we
# don't match the words appearing inside string literals (e.g.,
# TESTCASE "describes BEFORE-EACH behavior").
before_pattern='^[[:space:]]*(BEFORE-EACH|BEFORE[[:space:]]+EACH)([^A-Z0-9-]|$)'

for f in "${cut_files[@]}"; do
    found=$(awk -v BEFORE="$before_pattern" '
        BEGIN { matched = 0 }
        {
            line = $0
            sub(/\r$/, "", line)
            if (length(line) >= 7 && substr(line, 7, 1) == "*") next
            upper = toupper(line)
            if (upper ~ BEFORE) { matched = 1 }
        }
        END {
            if (matched == 1) print "yes"; else print "no"
        }
    ' "$f")

    if [ "$found" != "yes" ]; then
        echo "$f: missing BEFORE-EACH block" >> /tmp/check_isolation.out
        violations=1
    fi
done

if [ "$violations" -ne 0 ]; then
    cat <<'BANNER' >&2
================================================================
BLITZY VALIDATION GATE FAILED:
Test isolation violation.  Every testsuite MUST declare a
BEFORE-EACH block so that WORKING-STORAGE state is reset before
each TESTCASE.

The following files lacked a BEFORE-EACH block:
================================================================
BANNER
    cat /tmp/check_isolation.out >&2
    rm -f /tmp/check_isolation.out
    exit 1
fi

rm -f /tmp/check_isolation.out
echo "check_isolation.sh: PASS - scanned ${#cut_files[@]} file(s)."
exit 0
