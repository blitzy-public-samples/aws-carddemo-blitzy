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
# tests/lint/check_isolation.sh
# =====================================================================
#
# Validation gate enforcing the user's verbatim directives in the
# Agent Action Plan (Section 0.7.2 "Test isolation" and Section 0.10.2
# "Maintain test isolation"):
#
#   "Every testsuite must declare BEFORE-EACH that performs INITIALIZE
#    on every shared WORKING-STORAGE group it touches and resets
#    RETURN-CODE, WS-ABEND-FLAG, and END-OF-FILE flags.  Enforced by
#    tests/lint/check_isolation.sh."
#
# Without a BEFORE-EACH block the residual WORKING-STORAGE state from
# the previous TESTCASE leaks into the next, making the next testcase's
# PASS / FAIL outcome a hidden function of test ordering rather than of
# the production code's behavior alone.  This script catches the
# omission at lint time so it can never reach CI.
#
# Sub-Rule -- Every .cut testsuite must contain BEFORE-EACH
# ---------------------------------------------------------
# For every `tests/cobol-check/*.cut` file, scan for the keyword
# `BEFORE-EACH` preceded by start-of-line or whitespace, and followed
# by whitespace, end-of-line, or a period.  The pattern accepts both
# fixed-format COBOL (Area A, column 8) and free-format COBOL.
# A file lacking any BEFORE-EACH match is a violation.
#
# Comment lines containing the literal `BEFORE-EACH` technically
# satisfy this minimal gate (per AAP plan key insight: "for simplicity
# this script treats it as present").  Authors are expected to not
# author fake comments to bypass the gate; the sibling
# check_assertion_density.sh script independently requires every
# TESTCASE to contain >=1 EXPECT, which catches truly empty
# testsuites that try to short-circuit through commentary alone.
#
# This script is part of the four-script Validation Gate
# infrastructure (AAP Sections 0.5.1, 0.7.2, 0.10.1):
#   - check_no_business_logic.sh         (forbidden tokens / mocking PUT)
#   - check_no_production_redeclaration.sh (no PUT redeclaration)
#   - check_assertion_density.sh         (>=1 EXPECT per TESTCASE)
#   - check_isolation.sh                 (>=1 BEFORE-EACH per testsuite)
#
# Failure exit code is 1 (any violation); CLI errors exit with 2.
# All violations are reported before the script exits so a developer
# sees the entire violation surface in a single CI run.
# =====================================================================

set -eu

SCRIPT_NAME="check_isolation"
LOG_PREFIX="[lint:${SCRIPT_NAME}]"
DEFAULT_TARGET_DIR="tests/cobol-check"

# ---------------------------------------------------------------------
# usage -- print the CLI specification to stdout.
# ---------------------------------------------------------------------
usage() {
    cat <<EOF
Usage: ${SCRIPT_NAME}.sh [--help|-h] [<TARGET> ...]

For each .cut testsuite under <TARGET>, verify it declares at least
one BEFORE-EACH block.  The presence of BEFORE-EACH is the
AAP-mandated test-isolation rule: each testcase must reset shared
working-storage state before running so testcase outcomes do not
depend on test ordering.

Arguments:
  TARGET           Directory containing .cut files (recursive) or
                   specific .cut files.  Default: ${DEFAULT_TARGET_DIR}.

Options:
  -h, --help       Print this message and exit.

Exit status:
  0  All scanned files contain BEFORE-EACH OR no .cut files were
     found (green-on-empty bootstrap mode).
  1  At least one file is missing BEFORE-EACH, OR an explicitly-
     supplied target does not exist.
  2  CLI error (unknown option).

Failure message format (AAP-mandated, verbatim):
  BLITZY VALIDATION GATE FAILED: testsuite <file> is missing
  BEFORE-EACH (test isolation rule).
EOF
}

# ---------------------------------------------------------------------
# Argument parsing.  Accepts --help/-h, optional `--` end-of-options,
# and zero or more positional TARGETs.  Any other -prefixed token is
# treated as an unknown option and exits 2.  This mirrors the
# argument-parsing convention used by the sibling lint scripts.
# ---------------------------------------------------------------------
TARGETS_LIST=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --)
            shift
            while [ "$#" -gt 0 ]; do
                TARGETS_LIST="$TARGETS_LIST $1"
                shift
            done
            ;;
        -*)
            printf '%s ERROR: unknown option: %s\n' \
                "$LOG_PREFIX" "$1" >&2
            usage >&2
            exit 2
            ;;
        *)
            TARGETS_LIST="$TARGETS_LIST $1"
            shift
            ;;
    esac
done

if [ -z "$TARGETS_LIST" ]; then
    TARGETS_LIST="$DEFAULT_TARGET_DIR"
fi

# ---------------------------------------------------------------------
# Temporary working files.  files_list collects the .cut files under
# scan; marker_file accumulates one "F" line per violating .cut file
# (final exit status is derived from `wc -l` of this file).
#
# The temp-marker-file pattern is the canonical POSIX workaround for
# the subshell-variable-scope problem: variable updates inside a
# `cmd | while ...; done` pipeline do not survive the pipeline because
# `while` runs in a subshell.  A redirection-driven loop
# (`while ...; done < file`) does preserve scope, but using a temp
# file for failure markers keeps the pattern uniform with the other
# lint scripts and tolerates any future refactor to a piped form.
# ---------------------------------------------------------------------
files_list="$(mktemp 2>/dev/null || mktemp -t lint_files)"
marker_file="$(mktemp 2>/dev/null || mktemp -t lint_marker)"
# Guarantee tempfile cleanup on any exit path (success, error, signal).
trap 'rm -f "$files_list" "$marker_file"' EXIT INT TERM

# ---------------------------------------------------------------------
# Discover .cut files.  Each TARGET entry may be a directory (recursive
# scan via find) or a specific .cut file.  Non-.cut files passed
# explicitly produce a warning but do not fail the script.  Missing
# targets produce an error (exit 1) -- this matches the AAP-prescribed
# behavior for explicit user-supplied paths and the convention used
# by the sibling lint scripts.
# ---------------------------------------------------------------------
for target in $TARGETS_LIST; do
    if [ -d "$target" ]; then
        find "$target" -type f -name '*.cut' >> "$files_list"
    elif [ -f "$target" ]; then
        case "$target" in
            *.cut)
                printf '%s\n' "$target" >> "$files_list"
                ;;
            *)
                printf '%s WARNING: skipping non-.cut file %s\n' \
                    "$LOG_PREFIX" "$target" >&2
                ;;
        esac
    else
        printf '%s ERROR: target not found: %s\n' \
            "$LOG_PREFIX" "$target" >&2
        exit 1
    fi
done

# Deduplicate the discovered file list in place and order it
# deterministically so that violation-report ordering is stable from
# run to run (CI diffs depend on this).
sort -u -o "$files_list" "$files_list"

# A non-existent or empty .cut tree is "green-on-empty" so that the
# `make lint` Makefile target stays green when the repository starts
# with zero testsuites and grows them over time.  This matches the
# behavior of the peer lint scripts (check_no_business_logic.sh,
# check_no_production_redeclaration.sh, check_assertion_density.sh)
# and the documented setup semantics: "All targets are green-on-empty;
# tests, fixtures, and lint scripts all pass with zero .cut testsuites
# present."
if [ ! -s "$files_list" ]; then
    printf '%s no .cut files found in: %s -- pass\n' \
        "$LOG_PREFIX" "$TARGETS_LIST"
    exit 0
fi

# ---------------------------------------------------------------------
# Per-file check loop.
#
# For each .cut file, run grep -q -E with the AAP-specified regex:
#
#   (^|[[:space:]])BEFORE-EACH([[:space:]]|$|\.)
#
# This matches BEFORE-EACH at start-of-line or preceded by whitespace
# and followed by whitespace, end-of-line, or a period.  The pattern
# accepts both fixed-format COBOL (Area A, column 8) and free-format
# COBOL.  cobol-check itself is case-sensitive on its DSL keywords so
# this script matches uppercase BEFORE-EACH only.
#
# The redirection `done < "$files_list"` runs the while-loop in the
# CURRENT shell (not a subshell), so $total_files updates correctly.
# We additionally append "F" lines to $marker_file so the final
# verdict can use a tamper-resistant on-disk count instead of an
# in-memory counter that pipe forms would clobber.
#
# Under `set -e`, a failing command would abort the script; we wrap
# the grep test in `if !` to suspend `set -e` for that single command
# and capture grep's non-zero exit (no match) cleanly without aborting
# the loop.
# ---------------------------------------------------------------------
total_files=0

while IFS= read -r file; do
    [ -z "$file" ] && continue
    total_files=$((total_files + 1))

    if ! grep -q -E '(^|[[:space:]])BEFORE-EACH([[:space:]]|$|\.)' "$file"; then
        printf 'BLITZY VALIDATION GATE FAILED: testsuite %s is missing BEFORE-EACH (test isolation rule).\n' "$file" >&2
        printf 'F\n' >> "$marker_file"
    fi
done < "$files_list"

# ---------------------------------------------------------------------
# Final verdict.  marker_file has one "F" line per violating file; we
# count those lines and emit the appropriate summary line and exit
# code.  Empty marker_file (no violations) is the success path.
# ---------------------------------------------------------------------
if [ -s "$marker_file" ]; then
    fail_count="$(wc -l < "$marker_file" | tr -d ' \t')"
else
    fail_count=0
fi

if [ "${fail_count:-0}" -eq 0 ]; then
    printf '%s check_isolation.sh: PASS (%s files scanned)\n' \
        "$LOG_PREFIX" "$total_files"
    exit 0
else
    printf '%s check_isolation.sh: %s failure(s) of %s files scanned\n' \
        "$LOG_PREFIX" "$fail_count" "$total_files" >&2
    exit 1
fi
