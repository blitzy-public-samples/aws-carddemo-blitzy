#!/usr/bin/env bash
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
# tests/lint/parse_gcov_summary.sh
# =====================================================================
#
# Per-program coverage threshold enforcer for the AWS CardDemo
# cobol-check test suite.  Implements the validation gate documented
# in AAP Sections 0.5.1, 0.7.1, and 0.10.1.
#
# Behaviour
# ---------
# 1. Find every *.gcda.summary.txt file under <COVERAGE_DIR>.  These
#    files are produced by the `make coverage` target and contain the
#    captured stdout of `gcov -b -c <gcda>`, which on gcov 9+
#    (verified against gcov 13.3.0 in the build environment) emits
#    the per-source "File '<src>' / Lines executed:NN.NN% of M" pair
#    on STDOUT rather than into the per-source <src>.gcov file.
#    Capturing stdout into a sibling .summary.txt is the canonical
#    workaround for that gcov behaviour change.
# 2. Parse each summary file as a sequence of (File, Lines executed)
#    blocks.  One .gcda may produce multiple .gcov files (e.g. for a
#    single COBOL program GnuCOBOL emits .c, .c.h, and .c.l.h
#    translation units), so each .summary.txt typically contains
#    several blocks.
# 3. Derive the COBOL program-id from each block's source filename.
#    Five filename forms are recognised:
#      * AAP-spec / hand-crafted test fixtures:  CSUTLDTC.cbl
#      * Real GnuCOBOL output (cobc -> C -> gcc -> gcov):
#          CSUTLDTC.c                 (procedure code)
#          CSUTLDTC.c.h               (field/group declarations)
#          CSUTLDTC.c.l.h             (literal table initialiser)
#    All forms collapse to the program-id `CSUTLDTC`, normalised to
#    upper case for table lookup.
# 4. Compare each program's percentage against its per-program target
#    in the AAP Section 0.7.1 table (hard-coded below).  Programs not
#    present in the table are logged as "no target -- skipped" so
#    future testsuites can be added without forcing simultaneous edits
#    to this script.
# 5. Compute an arithmetic-mean overall percentage across every block
#    that mapped to a known program-id, compare against the
#    --overall threshold (default 70%, per AAP Section 0.7.1), and
#    emit a final BLITZY-prefixed verdict line.
# 6. Exit 0 when every per-program threshold AND the overall threshold
#    are met; exit 1 when any threshold is missed (or when no summary
#    files exist or the coverage directory is missing); exit 2 on
#    CLI error (unknown option).
#
# All log lines begin with the structured prefix
# `[lint:parse_gcov_summary]` so CI consumers can filter and grep
# the output deterministically.
#
# Idempotence
# -----------
# This script has no side effects beyond stdout and stderr.  Running
# it twice produces identical output.  It does not write, modify, or
# delete any file.
#
# Strict-mode rationale
# ---------------------
# `set -euo pipefail` is enabled because:
#   * `-e` aborts the script on any unchecked non-zero exit (defensive).
#   * `-u` catches typos in variable references at the read site.
#   * `-o pipefail` ensures gcov-parsing failures inside pipelines
#     propagate rather than being silently masked by the trailing
#     command's success.  Pipefail is bash-only; POSIX sh would not
#     suffice.  This is the primary reason for the
#     #!/usr/bin/env bash shebang -- the script also relies on bash
#     associative arrays (declare -A) for the per-program threshold
#     table.
# =====================================================================

set -euo pipefail

# ---------------------------------------------------------------------
# Identity / log prefix.  Used by every printf so CI log filters can
# pin output to this script.
# ---------------------------------------------------------------------
SCRIPT_NAME="parse_gcov_summary"
LOG_PREFIX="[lint:${SCRIPT_NAME}]"

# ---------------------------------------------------------------------
# Defaults (overridable via CLI).  COVERAGE_DIR is the directory under
# which `find -type f -name '*.summary.txt'` is executed.  Threshold
# values are integer percentages in the closed range 0..100.
# ---------------------------------------------------------------------
COVERAGE_DIR="target/coverage"
THRESHOLD_OVERALL=70
THRESHOLD_BUSINESS=80
THRESHOLD_VALIDATION=90
THRESHOLD_IO=70

# ---------------------------------------------------------------------
# Per-program coverage thresholds (AAP Section 0.7.1).
#
# 28 entries -- one per COBOL program in app/cbl/.  Programs present
# in this map are subject to per-program enforcement; programs absent
# from the map are logged as "no target -- skipped" and contribute
# nothing to the overall arithmetic mean.  This permits future
# additions without forcing this script to be edited in lockstep.
# ---------------------------------------------------------------------
declare -A PROGRAM_TARGETS=(
    [CSUTLDTC]=100
    [CBSTM03B]=100
    [CBACT01C]=75
    [CBACT02C]=75
    [CBACT03C]=75
    [CBCUS01C]=75
    [CBACT04C]=80
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

# ---------------------------------------------------------------------
# usage -- emit the help banner on stdout and return.  Callers exit
# with the appropriate status code after invoking usage.
# ---------------------------------------------------------------------
usage() {
    cat <<EOF
Usage: ${SCRIPT_NAME}.sh [OPTIONS] [COVERAGE_DIR]

Aggregate gcov per-program line-coverage percentages and enforce the
per-program thresholds defined in AAP Section 0.7.1.

Arguments:
  COVERAGE_DIR              Directory containing *.gcda.summary.txt
                            files produced by 'make coverage'
                            (default: target/coverage)

Options:
  --overall N               Overall threshold percentage (default: 70)
  --business N              Business-logic threshold (default: 80)
  --validation N            Data-validation threshold (default: 90)
  --io N                    File-I/O threshold (default: 70)
  -h, --help                Print this message and exit

Exit status:
  0  All per-program and overall thresholds met.
  1  At least one threshold not met (or no summary files found, or
     COVERAGE_DIR not found).
  2  CLI error (unknown option).

Output:
  stdout: structured [lint:parse_gcov_summary] log lines including
          per-program PASS/FAIL verdicts and the [OVERALL] aggregate;
          AAP-mandated 'BLITZY COVERAGE GATE PASSED: ...' on success.
  stderr: AAP-mandated 'BLITZY VALIDATION GATE FAILED: ...' on failure.
EOF
}

# ---------------------------------------------------------------------
# Argument parsing.  Long-option values consume two positional slots
# (e.g. `--overall 75`).  Unknown options trigger usage on stderr and
# exit 2.  A standalone `--` terminates option scanning so a positional
# COVERAGE_DIR can begin with `-` if ever needed.
# ---------------------------------------------------------------------
while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --overall)
            THRESHOLD_OVERALL="$2"
            shift 2
            ;;
        --business)
            THRESHOLD_BUSINESS="$2"
            shift 2
            ;;
        --validation)
            THRESHOLD_VALIDATION="$2"
            shift 2
            ;;
        --io)
            THRESHOLD_IO="$2"
            shift 2
            ;;
        --)
            shift
            break
            ;;
        -*)
            printf '%s ERROR: unknown option: %s\n' "$LOG_PREFIX" "$1" >&2
            usage >&2
            exit 2
            ;;
        *)
            COVERAGE_DIR="$1"
            shift
            ;;
    esac
done

# After option scanning a single positional argument is permitted as
# the COVERAGE_DIR override.  Reject any trailing arguments left after
# `--` to avoid silently dropping user input.
if [ "$#" -gt 0 ]; then
    COVERAGE_DIR="$1"
    shift
fi
if [ "$#" -gt 0 ]; then
    printf '%s ERROR: unexpected extra argument(s): %s\n' \
        "$LOG_PREFIX" "$*" >&2
    usage >&2
    exit 2
fi

# Echo the resolved configuration so CI logs document the gate being
# enforced.  This also keeps THRESHOLD_BUSINESS / THRESHOLD_VALIDATION
# / THRESHOLD_IO referenced even though only THRESHOLD_OVERALL drives
# numeric comparisons today (the per-program thresholds are the
# binding gate per AAP Section 0.7.1).
printf '%s Coverage thresholds: overall=%d%% business=%d%% validation=%d%% io=%d%%\n' \
    "$LOG_PREFIX" "$THRESHOLD_OVERALL" "$THRESHOLD_BUSINESS" \
    "$THRESHOLD_VALIDATION" "$THRESHOLD_IO"

# ---------------------------------------------------------------------
# Coverage-directory existence check.  We exit 1 (not 0) when the
# directory is missing so that `make coverage` failures cascade into
# CI failures rather than being silently swallowed.
# ---------------------------------------------------------------------
if [ ! -d "$COVERAGE_DIR" ]; then
    printf '%s ERROR: coverage directory not found: %s\n' \
        "$LOG_PREFIX" "$COVERAGE_DIR" >&2
    # shellcheck disable=SC2016
    # (the backticks around `make coverage` are intentional
    # markdown-style formatting, not command substitution)
    printf '%s Run `make coverage` first to generate gcov output.\n' \
        "$LOG_PREFIX" >&2
    exit 1
fi

# ---------------------------------------------------------------------
# Discover summary files produced by the Makefile coverage target.
# `sort` produces deterministic output ordering regardless of
# filesystem traversal order.  An empty list is treated as a
# coverage-data-unavailable error so CI catches misconfigured pipelines
# (e.g., gcov never ran or its stdout was redirected away from the
# expected .gcda.summary.txt sibling files).
# ---------------------------------------------------------------------
summary_files="$(find "$COVERAGE_DIR" -type f -name '*.gcda.summary.txt' | sort)"
if [ -z "$summary_files" ]; then
    printf '%s ERROR: no *.gcda.summary.txt files found under %s\n' \
        "$LOG_PREFIX" "$COVERAGE_DIR" >&2
    # shellcheck disable=SC2016
    printf '%s Run `make coverage` first; the Makefile coverage target\n' \
        "$LOG_PREFIX" >&2
    printf '%s captures gcov stdout into <gcda>.summary.txt files for\n' \
        "$LOG_PREFIX" >&2
    printf '%s this script to consume.\n' "$LOG_PREFIX" >&2
    exit 1
fi

printf '%s Aggregating coverage from %s/...\n' "$LOG_PREFIX" "$COVERAGE_DIR"

# ---------------------------------------------------------------------
# Per-summary parse loop.
#
# For each .gcda.summary.txt file we extract the (source-file,
# percentage) pairs emitted by gcov on its STDOUT.  The format gcov
# uses is, per source file:
#
#     File '<source-name>'
#     Lines executed:NN.NN% of <total>
#     Branches executed:...
#     Taken at least once:...
#     Calls executed:...
#     Creating '<source-name>.gcov'
#
# (verified on gcov 13.3.0 in the build environment).  We pair each
# `File '...'` line with the immediately-following `Lines executed:`
# line; subsequent lines until the next `File '...'` block are
# ignored.  awk produces TAB-separated <source>\t<pct-line> tuples
# which we feed to a per-tuple processing loop.  This isolates the
# parsing complexity in awk and keeps the bash loop straightforward.
# ---------------------------------------------------------------------
total_pct_sum=0
total_pct_count=0
fail_count=0

while IFS= read -r summary_file; do
    [ -z "$summary_file" ] && continue

    # Extract (filename<TAB>Lines-executed-line) pairs from the
    # summary file.  Filename is captured between the single quotes
    # in `File '<name>'`; the next `Lines executed:` line is bound to
    # it.  A subsequent `File '<name>'` line resets the capture.
    pairs="$(awk '
        /^File [^ ]/ {
            line = $0
            sub(/^File [^[:print:]]*/, "", line)
            sub(/^[[:space:]]*/, "", line)
            # Strip surrounding quotes (single or back) if present.
            gsub(/^['\''`]/, "", line)
            gsub(/['\''`]$/, "", line)
            fname = line
            next
        }
        /^Lines executed:/ {
            if (fname != "") {
                printf "%s\t%s\n", fname, $0
                fname = ""
            }
        }
    ' "$summary_file" || true)"

    [ -z "$pairs" ] && continue

    while IFS=$'\t' read -r src_file pct_line; do
        [ -z "$src_file" ] && continue
        [ -z "$pct_line" ] && continue

        # Derive the program-id from the gcov-reported source filename.
        # Strip in this order:
        #   1. .cbl / .CBL                    (AAP test fixtures, manual)
        #   2. .c.l.h                         (GnuCOBOL literal-table TU)
        #   3. .c.h                           (GnuCOBOL header TU)
        #   4. .c                             (GnuCOBOL procedure TU)
        # Then upper-case the result for associative-array lookup.
        program="$(printf '%s\n' "$src_file" \
            | sed -E 's/\.[Cc][Bb][Ll]$//; s/\.c\.l\.h$//; s/\.c\.h$//; s/\.c$//')"
        program="$(printf '%s\n' "$program" | tr '[:lower:]' '[:upper:]')"

        # Capture the numeric percentage.  `sed -nE ... /p` only emits
        # when the substitution matches, so malformed lines yield an
        # empty string rather than echoing the original.
        pct="$(printf '%s\n' "$pct_line" \
            | sed -nE 's/^Lines executed:([0-9]+\.?[0-9]*)%.*/\1/p')"
        if [ -z "$pct" ]; then
            printf '%s WARNING: cannot parse percentage from line: %s\n' \
                "$LOG_PREFIX" "$pct_line" >&2
            continue
        fi

        # Per-program threshold lookup.  `${...:-}` returns empty when
        # the key is absent under `set -u`, which we treat as a soft
        # skip rather than a hard failure (AAP key-insight #5).
        target="${PROGRAM_TARGETS[$program]:-}"
        if [ -z "$target" ]; then
            printf '%s [%s]  Lines executed: %6.2f%% (no target -- skipped)\n' \
                "$LOG_PREFIX" "$program" "$pct"
            continue
        fi

        # Floating-point comparison.  awk's BEGIN-block exit status is
        # the standard portable trick for >= comparisons of decimal
        # values because POSIX shell `[` does integer arithmetic only.
        if awk -v p="$pct" -v t="$target" 'BEGIN{exit !(p+0 >= t+0)}'; then
            verdict="PASS"
        else
            verdict="FAIL"
            fail_count=$((fail_count + 1))
        fi

        printf '%s [%s]  Lines executed: %6.2f%% (target %3d%%) -- %s\n' \
            "$LOG_PREFIX" "$program" "$pct" "$target" "$verdict"

        # Accumulate the percentage and counter into the overall-mean
        # state.  awk handles the floating-point sum because POSIX
        # shell cannot.  total_pct_count is integer.
        total_pct_sum="$(awk -v s="$total_pct_sum" -v p="$pct" \
            'BEGIN{printf "%.4f", s+p}')"
        total_pct_count=$((total_pct_count + 1))
    done <<< "$pairs"
done <<< "$summary_files"

# ---------------------------------------------------------------------
# Overall aggregation.  The overall percentage is the arithmetic mean
# of per-program percentages, NOT a line-weighted ratio.  This matches
# the AAP Section 0.7.1 spec ("Overall: >=70%").
#
# When zero programs were aggregated (e.g., every summary block mapped
# to an unknown program-id), the mean is reported as 0.00 and tested
# against THRESHOLD_OVERALL.  This makes the [OVERALL] line always
# present so downstream CI parsers can rely on its existence.
# ---------------------------------------------------------------------
if [ "$total_pct_count" -gt 0 ]; then
    overall_avg="$(awk -v s="$total_pct_sum" -v c="$total_pct_count" \
        'BEGIN{printf "%.2f", s/c}')"
else
    overall_avg="0.00"
fi

if awk -v p="$overall_avg" -v t="$THRESHOLD_OVERALL" \
        'BEGIN{exit !(p+0 >= t+0)}'; then
    overall_verdict="PASS"
else
    overall_verdict="FAIL"
    fail_count=$((fail_count + 1))
fi

printf '%s [OVERALL]   Lines executed: %6.2f%% (target %3d%%) -- %s\n' \
    "$LOG_PREFIX" "$overall_avg" "$THRESHOLD_OVERALL" "$overall_verdict"

# ---------------------------------------------------------------------
# Final verdict.
#
# Success path  (fail_count == 0): emit AAP-mandated
#   "BLITZY COVERAGE GATE PASSED: all N program(s) meet coverage targets."
# on stdout and exit 0.
#
# Failure path (fail_count > 0): emit AAP-mandated
#   "BLITZY VALIDATION GATE FAILED: N coverage threshold(s) not met."
# on stderr and exit 1.  The fail_count counts BOTH per-program
# threshold misses AND the overall-threshold miss, which means a single
# under-target program plus an under-target overall counts as 2.
# ---------------------------------------------------------------------
if [ "$fail_count" -eq 0 ]; then
    printf '%s BLITZY COVERAGE GATE PASSED: all %d program(s) meet coverage targets.\n' \
        "$LOG_PREFIX" "$total_pct_count"
    exit 0
else
    printf '%s Per-program failures: %d\n' "$LOG_PREFIX" "$fail_count"
    printf '%s BLITZY VALIDATION GATE FAILED: %d coverage threshold(s) not met.\n' \
        "$LOG_PREFIX" "$fail_count" >&2
    exit 1
fi
