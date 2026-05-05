#!/usr/bin/env bash
# =====================================================================
# check_no_production_redeclaration.sh
#
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# =====================================================================
#
# Validation gate for the "Import production functions ... directly
# from source modules" rule and for the "Creating test-local
# implementations that duplicate production behavior" forbidden
# pattern.
#
# Scans every `tests/cobol-check/*.cut` file for an
# IDENTIFICATION DIVISION or PROGRAM-ID declaration whose name matches
# any program file under `app/cbl/`.  Any such match indicates that the
# test author has copy-and-pasted (or re-declared) production code into
# the test file -- which violates the user's directive.
#
# The legitimate way for a `.cut` file to reference a production
# program is to set `cobolcheck.test.program.name` (or pass `-p` on the
# command line) and let cobol-check merge the unmodified production
# source into the test pipeline.
#
# Exits 0 on success, 1 on any violation.
# =====================================================================

set -u
set -o pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SUITE_DIR="${REPO_ROOT}/tests/cobol-check"
PROD_DIR="${REPO_ROOT}/app/cbl"

if [ ! -d "${SUITE_DIR}" ]; then
    echo "check_no_production_redeclaration.sh: ${SUITE_DIR} does not exist; nothing to check."
    exit 0
fi

if [ ! -d "${PROD_DIR}" ]; then
    echo "check_no_production_redeclaration.sh: ERROR - ${PROD_DIR} not found." >&2
    exit 1
fi

# Build the list of production program-ids by lower-casing each
# `app/cbl/<name>.<ext>` filename without its extension.
mapfile -t prod_names < <(
    find "${PROD_DIR}" -maxdepth 1 -type f \
         \( -name '*.cbl' -o -name '*.CBL' -o -name '*.cob' -o -name '*.COB' \) \
         -printf '%f\n' \
         | sed -E 's/\.(cbl|CBL|cob|COB)$//' \
         | sort -u
)

if [ "${#prod_names[@]}" -eq 0 ]; then
    echo "check_no_production_redeclaration.sh: WARN - no production COBOL files found under ${PROD_DIR}."
fi

mapfile -t cut_files < <(find "${SUITE_DIR}" -type f -name '*.cut' | sort)

if [ "${#cut_files[@]}" -eq 0 ]; then
    echo "check_no_production_redeclaration.sh: No .cut files found -- pass."
    exit 0
fi

violations=0
> /tmp/check_no_production_redeclaration.out

for f in "${cut_files[@]}"; do
    # Look for IDENTIFICATION DIVISION or PROGRAM-ID lines that are not
    # comment lines (column 7 != '*').
    while IFS= read -r line; do
        # Strip trailing CR.
        line="${line%$'\r'}"
        # Skip Area-A comments.
        if [ "${#line}" -ge 7 ] && [ "${line:6:1}" = "*" ]; then
            continue
        fi
        upper="$(printf '%s' "$line" | tr '[:lower:]' '[:upper:]')"

        # Any IDENTIFICATION DIVISION line in a .cut testsuite is a
        # violation -- production source is the only place it should
        # appear, and cobol-check merges that source in for us.
        if [[ "$upper" =~ IDENTIFICATION[[:space:]]+DIVISION ]]; then
            echo "${f}:IDENTIFICATION_DIVISION: $line" \
                >> /tmp/check_no_production_redeclaration.out
            violations=1
            continue
        fi

        # PROGRAM-ID lines are violations only if they name a file that
        # exists under app/cbl/ (i.e., the test author has copy-pasted
        # the production header).  PROGRAM-IDs naming an unrelated
        # support program are permitted in the form
        # `PROGRAM-ID. SOME-OTHER-NAME.` although discouraged.
        if [[ "$upper" =~ PROGRAM-ID[[:space:]]*\.?[[:space:]]+([A-Z0-9_-]+) ]]; then
            declared="${BASH_REMATCH[1]}"
            for name in "${prod_names[@]}"; do
                upper_name="$(printf '%s' "$name" | tr '[:lower:]' '[:upper:]')"
                if [ "$declared" = "$upper_name" ]; then
                    echo "${f}:PROGRAM_ID:${name}: $line" \
                        >> /tmp/check_no_production_redeclaration.out
                    violations=1
                    break
                fi
            done
        fi
    done < "$f"
done

if [ "$violations" -ne 0 ]; then
    cat <<'BANNER' >&2
================================================================
BLITZY VALIDATION GATE FAILED:
A .cut testsuite contains an IDENTIFICATION DIVISION or PROGRAM-ID
that re-declares a production COBOL program in app/cbl/.  Test
suites must NOT redeclare production code; they must reference the
unmodified production source via cobol-check's
`cobolcheck.test.program.name` setting (or the `-p` CLI option).

The following lines violated the rule:
================================================================
BANNER
    cat /tmp/check_no_production_redeclaration.out >&2
    rm -f /tmp/check_no_production_redeclaration.out
    exit 1
fi

rm -f /tmp/check_no_production_redeclaration.out
echo "check_no_production_redeclaration.sh: PASS - scanned ${#cut_files[@]} file(s) against ${#prod_names[@]} production program name(s)."
exit 0
