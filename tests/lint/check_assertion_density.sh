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
# tests/lint/check_assertion_density.sh
# =====================================================================
#
# Validation gate enforcing the user's verbatim directive (Agent Action
# Plan Section 0.7.2 and Section 0.10.1):
#
#   "Test assertions must validate return values and side effects of
#    actual production code execution."
#
# This script enforces TWO complementary sub-rules per .cut testsuite.
#
# Sub-Rule A -- Every TESTCASE must contain at least one EXPECT clause
# --------------------------------------------------------------------
# A testcase without an EXPECT is a no-op test that asserts nothing
# about the production code it purportedly exercises -- it provides no
# validation value and may falsely report green.  Every TESTCASE block
# (delimited by `TESTCASE 'description'` ... `END-TESTCASE` or by the
# next TESTCASE / end-of-file) MUST contain >= 1 EXPECT line.
#
# Sub-Rule B -- TESTCASEs in MOCK-bearing files must contain VERIFY
# -----------------------------------------------------------------
# Per cobol-check convention, MOCK FILE / MOCK CALL / MOCK CICS
# directives are typically declared at FILE scope (preceding the
# TESTCASE that uses them) rather than nested inside the testcase
# block.  The user's "side effects of actual production code
# execution" rule therefore requires that any .cut file containing
# such a directive must have every TESTCASE assert -- via VERIFY --
# that the mocked boundary was actually reached.  A mocked test
# without VERIFY cannot prove the production code touched the mock;
# the mock could have been silently bypassed and the EXPECT could
# still pass.
#
# (MOCK PARAGRAPH and MOCK SECTION are intentionally NOT counted by
# this script: they target internal paragraphs which are forbidden
# anyway and are caught by check_no_business_logic.sh sub-rule B.)
#
# Word boundaries are emulated with the POSIX-portable character
# class `[A-Z0-9_-]` because `\b` is not part of POSIX awk regex.
#
# Failure exit code is 1 (any violation); CLI errors exit with 2.
# All violations are reported before the script exits so a developer
# sees the entire violation surface in a single CI run.
# =====================================================================

set -eu

SCRIPT_NAME="check_assertion_density"
LOG_PREFIX="[lint:${SCRIPT_NAME}]"
DEFAULT_TARGET_DIR="tests/cobol-check"

# ---------------------------------------------------------------------
# usage -- print the CLI specification to stdout.
# ---------------------------------------------------------------------
usage() {
    cat <<EOF
Usage: ${SCRIPT_NAME}.sh [--help|-h] [<TARGET> ...]

For each .cut testsuite under <TARGET>, verify:
  1. Every TESTCASE contains at least one EXPECT clause.
  2. Every TESTCASE contained in a file that declares any
     MOCK FILE / MOCK CALL / MOCK CICS directive ALSO contains
     at least one VERIFY clause.

Arguments:
  TARGET           Directory containing .cut files (recursive) or
                   specific .cut files.  Default: ${DEFAULT_TARGET_DIR}.

Options:
  -h, --help       Print this message and exit.

Exit status:
  0  All scanned files clean (every TESTCASE satisfies both rules)
     OR no .cut files were found (green-on-empty bootstrap mode).
  1  At least one violation, OR an explicitly-supplied target does
     not exist.
  2  CLI error (unknown option).

Failure message format (AAP-mandated, verbatim):
  BLITZY VALIDATION GATE FAILED: testcase '<name>' in <file>:<line>
  has no EXPECT (or has MOCK without VERIFY).
EOF
}

# ---------------------------------------------------------------------
# Argument parsing.  Accepts --help/-h, optional `--` end-of-options,
# and zero or more positional TARGETs.  Any other -prefixed token is
# treated as an unknown option and exits 2.
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
# scan; marker_file accumulates one "F" line per file containing one
# or more violations (final exit status is derived from `wc -l` of
# this file).
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
# behavior for explicit user-supplied paths.
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

# Deduplicate the discovered file list in place.
sort -u -o "$files_list" "$files_list"

# A non-existent or empty .cut tree is "green-on-empty" so that the
# `make lint` Makefile target stays green when the repository starts
# with zero testsuites and grows them over time.  This matches the
# behavior of the peer lint scripts (check_no_business_logic.sh,
# check_no_production_redeclaration.sh) and the documented setup
# semantics: "All targets are green-on-empty; tests, fixtures, and
# lint scripts all pass with zero .cut testsuites present."
if [ ! -s "$files_list" ]; then
    printf '%s no .cut files found in: %s -- pass\n' \
        "$LOG_PREFIX" "$TARGETS_LIST"
    exit 0
fi

# ---------------------------------------------------------------------
# awk state machine -- per-file scanner.
#
# State:
#   inside_testcase  -- 1 between a TESTCASE opener and its closer.
#   tc_name          -- description string of the current testcase.
#   tc_start_line    -- line number where the current TESTCASE opened.
#   expect_count     -- EXPECT clauses seen in current testcase.
#   verify_count     -- VERIFY clauses seen in current testcase.
#   file_has_mock    -- 1 if ANY MOCK FILE/CALL/CICS directive was
#                       seen ANYWHERE in this file (file-scoped flag,
#                       set on first encounter, never cleared until
#                       awk reinitializes for the next file).
#   violations       -- per-file violation count (driving exit code).
#
# Closure rules:
#   * An explicit `END-TESTCASE` closes the current testcase.
#   * A new `TESTCASE` while inside a testcase closes the prior one
#     (some authors omit END-TESTCASE).
#   * End-of-file closes the current testcase if still open.
#
# At closure, the testcase fails if:
#   * expect_count == 0  -- no assertions made.
#   * file_has_mock && verify_count == 0  -- mocks not verified.
#
# is_comment() detects fixed-format (column 7 = `*` or `/`) and
# free-format (`*>`) COBOL comment markers.  Comment lines are
# skipped entirely so that a comment such as
#     * This testcase EXPECTs LK-M03B-RC = '00'
# does NOT artificially satisfy the EXPECT rule.
#
# `\047` is the octal escape for the single-quote character in awk
# string and regex literals -- we use it to embed `'` without needing
# to break out of the surrounding single-quoted shell string.
#
# Word-boundary emulation: `(^|[[:space:]])TOKEN([[:space:]]|$)`
# wraps the token in whitespace or string-edge so that EXPECTED,
# VERIFYING, MOCKED etc. cannot match.
# ---------------------------------------------------------------------
awk_program='
BEGIN {
    inside_testcase = 0
    tc_name         = ""
    tc_start_line   = 0
    expect_count    = 0
    verify_count    = 0
    file_has_mock   = 0
    violations      = 0
}

# Detect COBOL comment lines.
# Fixed-format COBOL: column 7 (1-indexed) holds the indicator area;
#   "*" = comment, "/" = page eject + comment.
# Free-format COBOL: "*>" anywhere on the line.
function is_comment(s) {
    if (length(s) >= 7 && (substr(s,7,1) == "*" || substr(s,7,1) == "/")) {
        return 1
    }
    if (s ~ /^[[:space:]]*\*>/) {
        return 1
    }
    return 0
}

# Close the currently-open testcase (if any) and validate it.
# Emits the AAP-mandated message to /dev/stderr on failure and
# increments the per-file violation counter.
function close_testcase(file,    fail, msg) {
    if (inside_testcase == 0) return
    fail = 0
    if (expect_count == 0) {
        fail = 1
    }
    if (file_has_mock && verify_count == 0) {
        fail = 1
    }
    if (fail) {
        msg = "BLITZY VALIDATION GATE FAILED: testcase " "\047" tc_name "\047" \
              " in " file ":" tc_start_line \
              " has no EXPECT (or has MOCK without VERIFY)."
        print msg > "/dev/stderr"
        violations++
    }
    inside_testcase = 0
    tc_name         = ""
    tc_start_line   = 0
    expect_count    = 0
    verify_count    = 0
}

# Skip comments outright -- no further pattern in this script
# should match against comment text.
{
    if (is_comment($0)) next
}

# File-scope MOCK detection.  This pattern matches MOCK followed by
# exactly one of FILE / CALL / CICS (the three external-boundary
# mock kinds permitted by the user prompt).  Once seen anywhere in
# the file, file_has_mock stays 1 for the rest of the file.
{
    if ($0 ~ /(^|[[:space:]])MOCK[[:space:]]+(FILE|CALL|CICS)([[:space:]]|$)/) {
        file_has_mock = 1
    }
}

# TESTCASE opener.  Implicitly closes any prior open testcase, then
# extracts the description string between the first and second
# single quotes after the keyword.  If the line lacks a closing
# quote (malformed but tolerated), tc_name is recorded as
# "(unknown)" so the violation message remains parseable.
/(^|[[:space:]])TESTCASE[[:space:]]*\047/ {
    if (inside_testcase) close_testcase(FILENAME)

    rest = $0
    sub(/.*TESTCASE[[:space:]]*\047/, "", rest)
    q = index(rest, "\047")
    if (q > 0) {
        tc_name = substr(rest, 1, q - 1)
    } else {
        tc_name = "(unknown)"
    }
    tc_start_line   = FNR
    inside_testcase = 1
    expect_count    = 0
    verify_count    = 0
}

# Explicit END-TESTCASE token closes the current testcase.  The
# trailing alternation `([[:space:]]|$|\.)` matches END-TESTCASE
# followed by space, end-of-line, or a period (the COBOL statement
# terminator).
/(^|[[:space:]])END-TESTCASE([[:space:]]|$|\.)/ {
    if (inside_testcase) close_testcase(FILENAME)
}

# While inside a TESTCASE, count EXPECT and VERIFY occurrences.
# These patterns require trailing whitespace so they do not match
# tokens like EXPECTED or VERIFYING.
inside_testcase {
    if ($0 ~ /(^|[[:space:]])EXPECT[[:space:]]/) {
        expect_count++
    }
    if ($0 ~ /(^|[[:space:]])VERIFY[[:space:]]/) {
        verify_count++
    }
}

END {
    # Implicit close at end-of-file.
    if (inside_testcase) close_testcase(FILENAME)
    # Exit non-zero so the shell wrapper can detect file-level
    # violations and increment its own marker count.
    exit (violations > 0 ? 1 : 0)
}
'

# ---------------------------------------------------------------------
# Per-file execution loop.
#
# For each .cut file, run the awk state machine.  The awk program
# emits one AAP-mandated message per violation to /dev/stderr and
# returns exit code 1 if any violation was found in that file.
#
# Under `set -e`, a failing command would abort the script -- but the
# `if !` construct suspends `set -e` for the test, so we can capture
# awk's failure and record it without aborting.
# ---------------------------------------------------------------------
total_files=0

while IFS= read -r file; do
    [ -z "$file" ] && continue
    total_files=$((total_files + 1))

    if ! awk "$awk_program" "$file"; then
        # awk already emitted the diagnostic(s) to /dev/stderr.
        # We only need to remember that THIS FILE had violations
        # so we can report the file-with-violations count below.
        printf 'F\n' >> "$marker_file"
    fi
done < "$files_list"

# ---------------------------------------------------------------------
# Final verdict.  marker_file has one "F" line per file containing
# any violation; we count those lines and emit the appropriate
# summary line and exit code.
# ---------------------------------------------------------------------
if [ -s "$marker_file" ]; then
    violation_files="$(wc -l < "$marker_file" | tr -d ' \t')"
else
    violation_files=0
fi

if [ "${violation_files:-0}" -eq 0 ]; then
    printf '%s PASS - scanned %s file(s)\n' \
        "$LOG_PREFIX" "$total_files"
    exit 0
else
    printf '%s FAIL - %s file(s) with violations of %s files scanned\n' \
        "$LOG_PREFIX" "$violation_files" "$total_files" >&2
    exit 1
fi
