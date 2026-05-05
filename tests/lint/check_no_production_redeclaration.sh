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
# tests/lint/check_no_production_redeclaration.sh
# =====================================================================
#
# Validation gate enforcing the user's verbatim directive (Agent Action
# Plan Section 0.10.1):
#
#   "Import production functions, methods, and classes directly from
#    source modules"
#
# In COBOL there is no `import` statement; the cobol-check framework
# imports a production program by configuring `cobolcheck.test.program
# .name` (or passing `-p PROGRAMNAME`) -- the framework then reads the
# unmodified production source from `app/cbl/` and merges the testsuite
# into it.  A `.cut` testsuite must therefore NEVER declare its own
# IDENTIFICATION DIVISION nor a PROGRAM-ID that re-declares a
# production program (which would be the COBOL equivalent of pasting
# production code into a test file).
#
# The script enforces TWO complementary sub-rules.
#
# Sub-Rule A -- No IDENTIFICATION DIVISION line in any .cut file
# --------------------------------------------------------------
# The cobol-check precompiler injects test code into the production
# source's PROCEDURE DIVISION; the testsuite file must not declare a
# program of its own.  Any non-comment line containing the keyword pair
# IDENTIFICATION DIVISION (case-insensitive) is a violation.
#
# Sub-Rule B -- No PROGRAM-ID whose name matches a production program
# -------------------------------------------------------------------
# Any non-comment line of the form `PROGRAM-ID. <NAME>` (case-
# insensitive) where <NAME> is the basename (without .cbl/.CBL
# extension) of a file under `--app-dir` (default `app/cbl/`) is a
# violation.  A hypothetical `PROGRAM-ID. TEST-HELPER.` declaration is
# permitted because TEST-HELPER is not a production program name --
# the rule is targeted: it forbids redeclaring production code, not
# all PROGRAM-IDs.
#
# Word boundaries are emulated with the POSIX-portable character class
# `[A-Z0-9_-]` because `\b` is not part of POSIX awk regex.
#
# Failure exit code is 1 (any violation); CLI errors exit with 2.  All
# violations are reported before the script exits so a developer sees
# the entire violation surface in a single CI run.
# =====================================================================

set -eu

SCRIPT_NAME="check_no_production_redeclaration"
LOG_PREFIX="[lint:${SCRIPT_NAME}]"
DEFAULT_TARGET_DIR="tests/cobol-check"
DEFAULT_APP_DIR="app/cbl"
APP_DIR="$DEFAULT_APP_DIR"

# ---------------------------------------------------------------------
# usage -- print the CLI specification to stdout.
# ---------------------------------------------------------------------
usage() {
    cat <<EOF
Usage: ${SCRIPT_NAME}.sh [--help|-h] [--app-dir DIR] [<TARGET> ...]

For each .cut testsuite under <TARGET>, verify it does NOT contain
either an IDENTIFICATION DIVISION line or a PROGRAM-ID line that names
any file in --app-dir (default: ${DEFAULT_APP_DIR}).

The user's prompt mandates: "Import production functions, methods, and
classes directly from source modules".  This script enforces that
test files do not redeclare production programs.  A .cut testsuite
references its program-under-test through cobol-check's
'cobolcheck.test.program.name' configuration key (or the '-p' CLI
flag) -- the framework then reads the unmodified production source
from app/cbl/ and merges the testsuite into it.

Arguments:
  TARGET           Directory containing .cut files (recursive) or
                   specific .cut files.  Default: ${DEFAULT_TARGET_DIR}.

Options:
  --app-dir DIR    Directory containing production .cbl/.CBL files
                   whose names are forbidden in .cut PROGRAM-IDs.
                   Default: ${DEFAULT_APP_DIR}.
  -h, --help       Print this message and exit.

Exit status:
  0  All scanned files are free of production redeclarations.
  1  At least one violation found, or operational failure.
  2  CLI error (unknown option, missing argument).

Failure message format (AAP-mandated, verbatim):
  BLITZY VALIDATION GATE FAILED: production-code redeclaration
  detected in <file>:<line>. Configure cobolcheck.test.program.name
  in config.properties to import the production source.
EOF
}

# ---------------------------------------------------------------------
# Argument parsing.  Accepts --help/-h, --app-dir DIR (with both
# space-separated and `=`-glued forms), an optional `--` end-of-options
# sentinel, and zero or more positional TARGETs.  Anything not matching
# is treated as an unknown option and exits 2.
# ---------------------------------------------------------------------
TARGETS_LIST=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --app-dir)
            shift
            if [ "$#" -eq 0 ]; then
                printf '%s ERROR: --app-dir requires a value\n' \
                    "$LOG_PREFIX" >&2
                exit 2
            fi
            APP_DIR="$1"
            shift
            ;;
        --app-dir=*)
            APP_DIR="${1#--app-dir=}"
            shift
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

if [ ! -d "$APP_DIR" ]; then
    printf '%s ERROR: app-dir not found: %s\n' \
        "$LOG_PREFIX" "$APP_DIR" >&2
    exit 1
fi

# ---------------------------------------------------------------------
# Temporary working files:
#   prod_names_file  -- sorted, uppercase, unique production program
#                       names extracted from .cbl/.CBL files in
#                       $APP_DIR (one per line).
#   files_list       -- the .cut files under scan (one per line).
#   marker_file      -- one "F" line per detected violation; the final
#                       exit status is derived from `wc -l` of this
#                       file.
# All three temp files are unconditionally removed on any exit path
# (success, error, signal) via the trap below.
# ---------------------------------------------------------------------
prod_names_file="$(mktemp 2>/dev/null || mktemp -t lint_prod)"
files_list="$(mktemp 2>/dev/null || mktemp -t lint_files)"
marker_file="$(mktemp 2>/dev/null || mktemp -t lint_marker)"
trap 'rm -f "$prod_names_file" "$files_list" "$marker_file"' EXIT INT TERM

# ---------------------------------------------------------------------
# Build the production-program-name list.
#
# For every .cbl/.CBL file directly under $APP_DIR (no recursion --
# the production tree is flat), strip the directory part with
# basename, strip the .cbl/.CBL extension with sed -E, uppercase the
# result with tr, then sort -u to deduplicate.
#
# `find -maxdepth 1` is the POSIX way to limit recursion; the
# extension match accepts both `.cbl` (lower-case, used by most files)
# and `.CBL` (upper-case, used by CBSTM03A.CBL and CBSTM03B.CBL in
# CardDemo).  No assumption is made about other extensions.
# ---------------------------------------------------------------------
find "$APP_DIR" -maxdepth 1 -type f \
    \( -name '*.cbl' -o -name '*.CBL' \) | \
    while IFS= read -r prod_path; do
        prod_base="$(basename "$prod_path")"
        prod_stem="$(printf '%s' "$prod_base" | sed -E 's/\.(cbl|CBL)$//')"
        printf '%s\n' "$prod_stem" | tr '[:lower:]' '[:upper:]'
    done | sort -u > "$prod_names_file"

if [ ! -s "$prod_names_file" ]; then
    printf '%s ERROR: no .cbl/.CBL files found in %s\n' \
        "$LOG_PREFIX" "$APP_DIR" >&2
    exit 1
fi

prod_count="$(wc -l < "$prod_names_file" | tr -d ' \t')"
printf '%s Loaded %s production program names from %s\n' \
    "$LOG_PREFIX" "$prod_count" "$APP_DIR"

# ---------------------------------------------------------------------
# Discover .cut files.  Each TARGET entry may be a directory
# (recursive scan via find) or a specific .cut file.  Non-.cut files
# passed explicitly produce a warning but do not fail the script.
# Missing targets produce an error.  The intentional whitespace-split
# of $TARGETS_LIST below is correct because every accumulated value
# was the result of an unquoted assignment from "$1" -- whitespace
# inside an individual argument was not preserved at accumulation
# time, by design (the script's CLI does not promise to handle paths
# containing whitespace).
# ---------------------------------------------------------------------
# shellcheck disable=SC2086
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
# with zero testsuites and grows them over time.
if [ ! -s "$files_list" ]; then
    printf '%s no .cut files found in: %s -- pass\n' \
        "$LOG_PREFIX" "$TARGETS_LIST"
    exit 0
fi

# ---------------------------------------------------------------------
# Sub-Rule A awk program: emit one line per IDENTIFICATION DIVISION
# match, in the form  <file>:<line>:IDDIV  (the third field is a
# fixed tag so the consumer side does not have to re-parse the line).
#
# is_comment() recognises:
#   - Fixed-format COBOL comment indicator at column 7 ('*' or '/').
#   - Free-format inline comment marker '*>' anywhere on the line.
# Trailing CR is stripped so .cut files written under Windows or
# pulled from z/OS still parse correctly.
#
# The match regex `(^|[[:space:]])IDENTIFICATION[[:space:]]+DIVISION
# ([[:space:]]|\.|$)` requires the keyword pair to be bracketed by
# non-identifier characters (start-of-line or whitespace before, and
# whitespace, period, or end-of-line after).  This prevents tokens
# like `IDENTIFICATION-DIVISION-VAR` from accidentally matching.
# Note: the awk regex does NOT need backslash-escapes inside `\.`
# because awk's ERE is identical to grep -E in this respect.
# ---------------------------------------------------------------------
extract_iddiv_awk='
function is_comment(s) {
    if (length(s) >= 7 && (substr(s,7,1) == "*" || substr(s,7,1) == "/")) {
        return 1
    }
    if (s ~ /^[[:space:]]*\*>/) {
        return 1
    }
    return 0
}
{
    line = $0
    sub(/\r$/, "", line)
    if (is_comment(line)) next
    upper = toupper(line)
    if (upper ~ /(^|[[:space:]])IDENTIFICATION[[:space:]]+DIVISION([[:space:]]|\.|$)/) {
        print FILENAME ":" FNR ":IDDIV"
    }
}
'

# ---------------------------------------------------------------------
# Sub-Rule B awk program: emit one line per PROGRAM-ID match, in the
# form  <file>:<line>:<NAME>  where <NAME> is the upper-cased token
# extracted from immediately after `PROGRAM-ID.`.
#
# The extraction is two-step:
#   1. Verify the line contains `PROGRAM-ID.` followed by whitespace
#      followed by an identifier token.  The pre-period anchor
#      `(^|[[:space:]])` prevents `MY-PROGRAM-ID.` (i.e., a hyphen-
#      preceded false-positive) from matching.
#   2. Strip everything up to and including `PROGRAM-ID.` and any
#      following whitespace, then walk the remainder character-by-
#      character until whitespace or `.` is reached.  The accumulated
#      characters form the program name.
#
# COBOL identifier characters are A-Z, 0-9, underscore, and hyphen,
# but the explicit char-by-char loop is more robust than a regex for
# extracting just the token portion (the regex can match the *line*
# but extracting the *token* with a regex requires backreferences,
# which POSIX awk does not provide).  The loop terminates on the
# first separator and discards everything after.
# ---------------------------------------------------------------------
extract_progid_awk='
function is_comment(s) {
    if (length(s) >= 7 && (substr(s,7,1) == "*" || substr(s,7,1) == "/")) {
        return 1
    }
    if (s ~ /^[[:space:]]*\*>/) {
        return 1
    }
    return 0
}
{
    line = $0
    sub(/\r$/, "", line)
    if (is_comment(line)) next
    upper = toupper(line)
    if (upper ~ /(^|[[:space:]])PROGRAM-ID\.[[:space:]]+[A-Z0-9_-]+/) {
        tmp = upper
        sub(/^.*PROGRAM-ID\./, "", tmp)
        sub(/^[[:space:]]+/, "", tmp)
        n = ""
        L = length(tmp)
        for (i = 1; i <= L; i++) {
            c = substr(tmp, i, 1)
            if (c == " " || c == "\t" || c == ".") break
            if (c ~ /[A-Z0-9_-]/) {
                n = n c
            } else {
                break
            }
        }
        if (n != "") print FILENAME ":" FNR ":" n
    }
}
'

# ---------------------------------------------------------------------
# Per-file scan.  For each .cut file:
#   - Sub-rule A: any IDENTIFICATION DIVISION match is an immediate
#     violation; emit the AAP-mandated message and add an "F" marker.
#   - Sub-rule B: any PROGRAM-ID match whose extracted name is a
#     member of the production-name list is a violation; emit the
#     AAP-mandated message and add an "F" marker.  Membership is
#     checked with grep -q -x -F -- (exact-line, fixed-string) so
#     that any regex metacharacters in the extracted name cannot
#     misclassify the comparison.
#
# total_files counts attempted scans; the count is reported in the
# final summary so a CI reader can confirm the script saw the
# expected files.
# ---------------------------------------------------------------------
total_files=0

while IFS= read -r file; do
    [ -z "$file" ] && continue
    total_files=$((total_files + 1))

    # ----- Sub-rule A: IDENTIFICATION DIVISION -----
    awk "$extract_iddiv_awk" "$file" | \
        while IFS=: read -r vfile vline _vtag; do
            [ -z "$vfile" ] && continue
            printf 'BLITZY VALIDATION GATE FAILED: production-code redeclaration detected in %s:%s. Configure cobolcheck.test.program.name in config.properties to import the production source.\n' \
                "$vfile" "$vline" >&2
            printf 'F\n' >> "$marker_file"
        done

    # ----- Sub-rule B: PROGRAM-ID matching production name -----
    awk "$extract_progid_awk" "$file" | \
        while IFS=: read -r vfile vline pname; do
            [ -z "$pname" ] && continue
            if grep -q -x -F -- "$pname" "$prod_names_file"; then
                printf 'BLITZY VALIDATION GATE FAILED: production-code redeclaration detected in %s:%s. Configure cobolcheck.test.program.name in config.properties to import the production source.\n' \
                    "$vfile" "$vline" >&2
                printf 'F\n' >> "$marker_file"
            fi
        done
done < "$files_list"

# ---------------------------------------------------------------------
# Final verdict.  The marker_file contains one "F" line per detected
# violation across both sub-rules; we count those lines and emit the
# appropriate summary line and exit code.
# ---------------------------------------------------------------------
if [ -s "$marker_file" ]; then
    violation_count="$(wc -l < "$marker_file" | tr -d ' \t')"
else
    violation_count=0
fi

if [ "${violation_count:-0}" -eq 0 ]; then
    printf '%s PASS - scanned %s file(s) against %s production program name(s)\n' \
        "$LOG_PREFIX" "$total_files" "$prod_count"
    exit 0
else
    printf '%s FAIL - %s violation(s) in %s file(s) scanned\n' \
        "$LOG_PREFIX" "$violation_count" "$total_files" >&2
    exit 1
fi
