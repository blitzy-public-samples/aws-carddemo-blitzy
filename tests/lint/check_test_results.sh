#!/bin/sh
# =====================================================================
# check_test_results.sh
#
# Copyright Amazon.com, Inc. or its affiliates.  All Rights Reserved.
# Licensed under the Apache License, Version 2.0.
# =====================================================================
#
# Purpose
# -------
# Detect cobol-check 0.2.16 "silent compile failures" by inspecting
# target/cobol-check/testResults.txt after each program is run.
#
# Background
# ----------
# cobol-check 0.2.16 has a documented limitation: when the underlying
# GnuCOBOL `cobc` compile fails (missing copybook, undefined symbol,
# REDEFINES size mismatch, etc.) the test runner script exits non-zero
# but the cobol-check Java JAR catches that exit code and itself
# returns 0.  As a result, `make test` reports success while in fact
# zero testcases were executed.  This script closes the gap.
#
# Decision rules
# --------------
# Given the path to a testResults.txt file:
#
#   1. The file MUST exist and MUST be non-empty.  If absent, return
#      non-zero (silent failure: cobol-check did not produce output).
#
#   2. The file MUST contain a `N TEST CASES WERE EXECUTED` line and
#      N MUST be > 0.  If absent or zero, return non-zero (silent
#      compile failure: program failed to produce any test output).
#
#   3. The `M FAILED` line, if present, MUST report 0.  If non-zero,
#      return non-zero (real test failure that cobol-check reported
#      via output but failed to communicate via exit code in some
#      framework versions).
#
# Usage
# -----
#   check_test_results.sh <PROGRAM-ID> <path/to/testResults.txt>
#
# Exit codes
# ----------
#   0   Tests executed and all passed.
#   1   Silent failure: testResults.txt missing or empty.
#   2   Silent failure: zero testcases executed (compile failure).
#   3   Real failure: one or more testcases failed.
#   4   Invalid invocation (missing arguments).
#
# This script is intentionally written in POSIX shell + grep + awk
# so it has no Bash-specific dependencies and runs on minimal CI
# images that ship `dash` as /bin/sh.
# =====================================================================

set -u

if [ "$#" -lt 2 ]; then
    echo "[check_test_results] usage: $0 <program-id> <results-file>" >&2
    exit 4
fi

program_id="$1"
results_file="$2"

if [ ! -s "${results_file}" ]; then
    echo "[check_test_results] FAIL: ${program_id}: testResults.txt missing or empty" >&2
    echo "[check_test_results]    expected at: ${results_file}" >&2
    echo "[check_test_results]    cobol-check produced no test output --" >&2
    echo "[check_test_results]    likely a silent compile failure." >&2
    exit 1
fi

executed_line="$(grep -E '^[[:space:]]*[0-9]+[[:space:]]+TEST CASES WERE EXECUTED' \
    "${results_file}" | tail -n 1)"
executed_count="$(echo "${executed_line}" | awk '{print $1}')"

if [ -z "${executed_count}" ] || [ "${executed_count}" -eq 0 ] 2>/dev/null; then
    echo "[check_test_results] FAIL: ${program_id}: zero testcases were executed" >&2
    echo "[check_test_results]    file: ${results_file}" >&2
    echo "[check_test_results]    cobol-check reported no '* TEST CASES WERE EXECUTED'" >&2
    echo "[check_test_results]    line, indicating a silent compile failure." >&2
    if [ -s "${results_file}" ]; then
        echo "[check_test_results]    --- last 25 lines of testResults.txt ---" >&2
        tail -n 25 "${results_file}" >&2
        echo "[check_test_results]    -----------------------------------------" >&2
    fi
    exit 2
fi

failed_line="$(grep -E '^[[:space:]]*[0-9]+[[:space:]]+FAILED' \
    "${results_file}" | tail -n 1)"
failed_count="$(echo "${failed_line}" | awk '{print $1}')"

if [ -z "${failed_count}" ]; then
    failed_count=0
fi

if [ "${failed_count}" -gt 0 ] 2>/dev/null; then
    echo "[check_test_results] FAIL: ${program_id}: ${failed_count} testcase(s) failed" >&2
    echo "[check_test_results]    out of ${executed_count} executed." >&2
    echo "[check_test_results]    file: ${results_file}" >&2
    echo "[check_test_results]    --- failed lines ---" >&2
    grep -E '^\s*\*+\s*FAIL' "${results_file}" >&2 || true
    echo "[check_test_results]    ---------------------" >&2
    exit 3
fi

echo "[check_test_results] PASS: ${program_id}: ${executed_count} testcase(s), all passed."
exit 0
