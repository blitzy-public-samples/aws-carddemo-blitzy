#!/usr/bin/env bash
# =====================================================================
# parse_gcov_summary.sh
#
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# =====================================================================
#
# Reads `target/cobol-check/*.gcov` files emitted by `make coverage`
# and prints a one-line-per-program coverage summary in the form:
#
#     PROGRAM    LINES_COVERED/LINES_TOTAL    PERCENT%   TARGET%
#
# It additionally enforces the per-program targets documented in
# Section 0.7.1 of the Agent Action Plan and exits non-zero if any
# threshold is missed.
#
# Implementation notes
# --------------------
# GnuCOBOL translates COBOL into three intermediate C files when
# compiling with -save-temps:
#     <PROGRAM>.c        - generated procedure code  (the bulk)
#     <PROGRAM>.c.h      - field / group declarations
#     <PROGRAM>.c.l.h    - literal table initialiser
# Each yields a separate `.gcov` file at coverage time.  This script
# aggregates the three back into a single PROGRAM-ID row so per-program
# targets correspond to the COBOL source unit, not the C translation
# units.
#
# Stub modules (CEEDAYS, CEE3ABD, DFHEI1) are infrastructure shims
# authored under tests/stubs/ - they have no production-coverage
# requirement and are excluded from the report.
#
# Each `.gcov` file's inline annotations are scanned for executable
# line markers:
#     "      N:" (where N is one or more digits)  -> covered line
#     "  #####:"                                  -> uncovered line
# The total of covered + uncovered lines is the executable line count.
# =====================================================================

set -u
set -o pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GCOV_DIR="${REPO_ROOT}/target/cobol-check"

# Per-program minimum line coverage targets (%).
# Programs absent from this map default to $DEFAULT_TARGET.
declare -A TARGETS=(
    [CSUTLDTC]=100
    [CBSTM03B]=100
    [CBACT01C]=75
    [CBACT02C]=75
    [CBACT03C]=75
    [CBACT04C]=80
    [CBCUS01C]=75
    [CBSTM03A]=70
    [CBTRN01C]=80
    [CBTRN02C]=80
    [CBTRN03C]=80
    [COSGN00C]=75
    [COMEN01C]=75
    [COADM01C]=75
    [COACTVWC]=75
    [COACTUPC]=75
    [COCRDLIC]=75
    [COCRDSLC]=75
    [COCRDUPC]=75
    [COTRN00C]=75
    [COTRN01C]=75
    [COTRN02C]=75
    [COBIL00C]=80
    [CORPT00C]=75
    [COUSR00C]=75
    [COUSR01C]=75
    [COUSR02C]=75
    [COUSR03C]=75
)
DEFAULT_TARGET=70
OVERALL_TARGET=70

# Stubs that are deliberately excluded from coverage reporting.
SKIP_NAMES_RE='^(CEEDAYS|CEE3ABD|DFHEI1)$'

if [ ! -d "${GCOV_DIR}" ]; then
    echo "parse_gcov_summary.sh: ${GCOV_DIR} does not exist; run \`make coverage\` first." >&2
    exit 1
fi

shopt -s nullglob
gcov_files=( "${GCOV_DIR}"/*.gcov )
shopt -u nullglob

if [ "${#gcov_files[@]}" -eq 0 ]; then
    echo "parse_gcov_summary.sh: WARN - no .gcov files in ${GCOV_DIR}." >&2
    echo "parse_gcov_summary.sh: PASS (no coverage data to enforce)."
    exit 0
fi

# Map from PROGRAM-ID -> "covered total" pair.
declare -A COVERED_BY_PROG=()
declare -A TOTAL_BY_PROG=()

#----------------------------------------------------------------------
# Aggregate every .gcov file's executable lines into the program-id
# bucket derived from its filename.
#----------------------------------------------------------------------
for gcov in "${gcov_files[@]}"; do
    base="$(basename "$gcov" .gcov)"

    # Strip the C-intermediate suffix to recover the COBOL program-id.
    #   CSUTLDTC.c.l.h.gcov -> CSUTLDTC
    #   CSUTLDTC.c.h.gcov   -> CSUTLDTC
    #   CSUTLDTC.c.gcov     -> CSUTLDTC
    prog="${base%.c.l.h}"
    prog="${prog%.c.h}"
    prog="${prog%.c}"
    prog_uc="$(printf '%s' "$prog" | tr '[:lower:]' '[:upper:]')"

    # Skip infrastructure stubs.
    if [[ "$prog_uc" =~ $SKIP_NAMES_RE ]]; then
        continue
    fi

    # Count executable lines vs covered lines from inline annotations.
    counts=$(awk '
        BEGIN { covered = 0; uncovered = 0 }
        # A .gcov line looks like "      N:    LL: source"
        # The execution count column is everything before the first colon.
        {
            n = index($0, ":")
            if (n == 0) next
            count = substr($0, 1, n - 1)
            gsub(/[[:space:]]/, "", count)
            if (count == "" || count == "-") next
            if (count == "#####") { uncovered++; next }
            if (count == "=====") { uncovered++; next }   # never run, no-counters
            if (count ~ /^[0-9]+\*?$/) { covered++; next }
            # Anything else (e.g. branch/call lines) is ignored by line cov.
        }
        END { printf "%d %d", covered, (covered + uncovered) }
    ' "$gcov")

    cov="${counts% *}"
    tot="${counts#* }"

    COVERED_BY_PROG[$prog_uc]=$(( ${COVERED_BY_PROG[$prog_uc]:-0} + cov ))
    TOTAL_BY_PROG[$prog_uc]=$((   ${TOTAL_BY_PROG[$prog_uc]:-0}   + tot ))
done

#----------------------------------------------------------------------
# Render the report and enforce thresholds.
#----------------------------------------------------------------------
violations=0
total_covered=0
total_lines=0

printf '%-12s %14s %10s %10s\n' "PROGRAM" "COVERED/TOTAL" "PERCENT" "TARGET"
printf '%s\n' "----------------------------------------------------"

# Sort program-ids alphabetically for stable output.
mapfile -t sorted_progs < <(printf '%s\n' "${!TOTAL_BY_PROG[@]}" | sort)

if [ "${#sorted_progs[@]}" -eq 0 ]; then
    echo "parse_gcov_summary.sh: WARN - no production programs found in coverage data." >&2
    echo "parse_gcov_summary.sh: PASS (no coverage data to enforce)."
    exit 0
fi

for prog_uc in "${sorted_progs[@]}"; do
    cov="${COVERED_BY_PROG[$prog_uc]:-0}"
    tot="${TOTAL_BY_PROG[$prog_uc]:-0}"

    if [ "$tot" -eq 0 ]; then
        pct=0
    else
        pct=$(( cov * 100 / tot ))
    fi

    target="${TARGETS[$prog_uc]:-$DEFAULT_TARGET}"
    if [ "$pct" -lt "$target" ]; then
        violations=$(( violations + 1 ))
        marker="FAIL"
    else
        marker="OK"
    fi

    printf '%-12s %7d/%-6d %9d%% %9d%% (%s)\n' \
        "$prog_uc" "$cov" "$tot" "$pct" "$target" "$marker"

    total_covered=$(( total_covered + cov ))
    total_lines=$(( total_lines + tot ))
done

if [ "$total_lines" -gt 0 ]; then
    overall_pct=$(( total_covered * 100 / total_lines ))
else
    overall_pct=0
fi

printf '%s\n' "----------------------------------------------------"
printf '%-12s %7d/%-6d %9d%% %9d%%\n' \
    "OVERALL" "$total_covered" "$total_lines" "$overall_pct" "$OVERALL_TARGET"

if [ "$overall_pct" -lt "$OVERALL_TARGET" ]; then
    echo "parse_gcov_summary.sh: FAIL - overall coverage ${overall_pct}% < target ${OVERALL_TARGET}%" >&2
    violations=$(( violations + 1 ))
fi

if [ "$violations" -ne 0 ]; then
    echo "parse_gcov_summary.sh: ${violations} program(s) missed their coverage target." >&2
    exit 1
fi

echo "parse_gcov_summary.sh: PASS - all coverage targets met."
exit 0
