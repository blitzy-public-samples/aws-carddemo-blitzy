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
# tests/lint/check_no_business_logic.sh
# =====================================================================
#
# The flagship validation gate enforcing the user's verbatim directive
# (Agent Action Plan Section 0.10.1):
#
#   "Flag any test file where business logic appears outside of imports,
#    setup, invocation, or assertions.  A test that computes expected
#    values by reimplementing production algorithms instead of calling
#    the production function fails review."
#
# The script enforces TWO complementary sub-rules.
#
# Sub-Rule A -- No forbidden arithmetic tokens outside setup blocks
# -----------------------------------------------------------------
# For every `tests/cobol-check/*.cut` file, scan every non-comment line
# for the COBOL arithmetic verbs COMPUTE, MULTIPLY, DIVIDE, ADD, and
# SUBTRACT.  Any occurrence OUTSIDE the allow-list windows
# `BEFORE-EACH ... END-BEFORE` and `AFTER-EACH ... END-AFTER` is a
# violation -- it represents business arithmetic in test code, which
# the user's prompt forbids.
#
# Sub-Rule B -- No MOCK PARAGRAPH / MOCK SECTION targeting the PUT
# ----------------------------------------------------------------
# For each `.cut` file derive the program-under-test (PUT) from the
# filename, locate the production source under `app/cbl/`, extract the
# paragraph names declared there, and verify that no `.cut` file mocks
# any of those paragraphs.  Mocking an internal paragraph of the PUT
# is forbidden by the user's prompt ("Mocking the internal function or
# method under test").
#
# Word boundaries are emulated with the POSIX-portable character
# class `[^A-Z0-9_-]` because `\b` is not part of POSIX awk regex.
#
# Failure exit code is 1 (any violation); CLI errors exit with 2.
# All violations are reported before the script exits so a developer
# sees the entire violation surface in a single CI run.
# =====================================================================

set -eu

SCRIPT_NAME="check_no_business_logic"
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

The flagship validation gate enforcing the user's "no business logic
in test files" rule.  For each .cut testsuite under <TARGET>, this
script:

  Sub-Rule A: Forbids COMPUTE, MULTIPLY, DIVIDE, ADD, SUBTRACT tokens
              outside BEFORE-EACH/AFTER-EACH blocks.
  Sub-Rule B: Forbids MOCK PARAGRAPH / MOCK SECTION directives
              targeting any paragraph name in the program-under-test
              source.

Arguments:
  TARGET           Directory containing .cut files (recursive) or
                   specific .cut files.  Default: ${DEFAULT_TARGET_DIR}.

Options:
  --app-dir DIR    Directory containing production .cbl/.CBL files
                   (used for sub-rule B).  Default: ${DEFAULT_APP_DIR}.
  -h, --help       Print this message and exit.

Exit status:
  0  All scanned files clean (no business logic detected).
  1  At least one violation.
  2  CLI error (unknown option, missing argument).

Failure message format (AAP-mandated, verbatim):
  BLITZY VALIDATION GATE FAILED: business logic detected outside
  imports/setup/invocation/assertions in <file>:<line>. Re-author the
  test to PERFORM the production paragraph instead of computing the
  expected value yourself.
EOF
}

# ---------------------------------------------------------------------
# Argument parsing.  Accepts --help/-h, --app-dir DIR, optional `--`
# end-of-options, and zero or more positional TARGETs.  Anything not
# matching is treated as an unknown option and exits 2.
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
# Temporary working files.  files_list collects the .cut files under
# scan; marker_file accumulates one "F" line per detected violation
# (final exit status is derived from `wc -l` of this file).
# ---------------------------------------------------------------------
files_list="$(mktemp 2>/dev/null || mktemp -t lint_files)"
marker_file="$(mktemp 2>/dev/null || mktemp -t lint_marker)"
# Guarantee tempfile cleanup on any exit path (success, error, signal).
trap 'rm -f "$files_list" "$marker_file"' EXIT INT TERM

# ---------------------------------------------------------------------
# Discover .cut files.  Each TARGET entry may be a directory (recursive
# scan via find) or a specific .cut file.  Non-.cut files passed
# explicitly produce a warning but do not fail the script.  Missing
# targets produce an error.
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
# with zero testsuites and grows them over time.
if [ ! -s "$files_list" ]; then
    printf '%s no .cut files found in: %s -- pass\n' \
        "$LOG_PREFIX" "$TARGETS_LIST"
    exit 0
fi

# ---------------------------------------------------------------------
# Sub-Rule A awk program.
#
# State machine:
#   in_before -- 1 while inside a BEFORE-EACH..END-BEFORE block.
#   in_after  -- 1 while inside an AFTER-EACH..END-AFTER block.
#
# The patterns that toggle these flags are declared BEFORE the main
# check pattern so that awk's source-order pattern evaluation updates
# the state for the current line before the main check runs on it.
# This makes BEFORE-EACH "open" the allow-list on its own line, while
# END-BEFORE "closes" it on its own line (END-BEFORE itself is then
# subject to the check, but typically the END-BEFORE line contains
# only the keyword and a trailing period, so the regex will not flag).
#
# is_comment() recognizes:
#   - Fixed-format COBOL comment indicator at column 7 ('*' or '/').
#   - Free-format inline comment marker '*>' anywhere on the line.
#
# The forbidden token regex `(^|[^A-Z0-9_-])TOKEN([^A-Z0-9_-]|$)`
# wraps the token in non-identifier characters, emulating word
# boundaries portably.  Identifier characters in COBOL are A-Z, 0-9,
# underscore, and hyphen.
# ---------------------------------------------------------------------
awk_program_a='
BEGIN {
    in_before = 0
    in_after  = 0
    violations = 0
}

function is_comment(s) {
    if (length(s) >= 7 && (substr(s,7,1) == "*" || substr(s,7,1) == "/")) {
        return 1
    }
    if (s ~ /^[[:space:]]*\*>/) {
        return 1
    }
    return 0
}

# Track BEFORE-EACH / AFTER-EACH range open.  The keyword must appear
# as the first non-whitespace token on the line; this prevents the
# words BEFORE-EACH / AFTER-EACH appearing inside a string literal
# (e.g., a TESTSUITE description) from inadvertently opening the
# allow-list window and masking real violations on subsequent lines.
{
    if (!is_comment($0)) {
        upper_track = toupper($0)
        if (upper_track ~ /^[[:space:]]*BEFORE-EACH([[:space:]]|$|\.)/) {
            in_before = 1
        }
        if (upper_track ~ /^[[:space:]]*AFTER-EACH([[:space:]]|$|\.)/) {
            in_after  = 1
        }
    }
}

# Main forbidden-token check.  Skips comments and any line currently
# inside an allow-list window.  Matches a forbidden token bracketed
# by non-identifier characters anywhere on the line.  Emits the
# AAP-mandated diagnostic to stderr AND a single "F" line to stdout
# so the caller can accumulate accurate per-violation counts in the
# shared marker_file.
{
    if (is_comment($0)) {
        # Comments are unconditionally skipped.
    } else if (in_before || in_after) {
        # Inside the allow-list window.
    } else {
        upper = toupper($0)
        if (upper ~ /(^|[^A-Z0-9_-])(COMPUTE|MULTIPLY|DIVIDE|ADD|SUBTRACT)([^A-Z0-9_-]|$)/) {
            msg = "BLITZY VALIDATION GATE FAILED: business logic detected outside imports/setup/invocation/assertions in " FILENAME ":" FNR ". Re-author the test to PERFORM the production paragraph instead of computing the expected value yourself."
            print msg > "/dev/stderr"
            print "F"
            violations++
        }
    }
}

# Track BEFORE-EACH/AFTER-EACH range close.  Done LAST so that
# END-BEFORE on the same line as a forbidden token still flags the
# token (defensive strictness).  Same anchoring rule as the open
# trackers: the keyword must be the first non-whitespace token on
# the line.
{
    if (!is_comment($0)) {
        upper_track2 = toupper($0)
        if (upper_track2 ~ /^[[:space:]]*END-BEFORE([[:space:]]|$|\.)/) {
            in_before = 0
        }
        if (upper_track2 ~ /^[[:space:]]*END-AFTER([[:space:]]|$|\.)/) {
            in_after  = 0
        }
    }
}

END {
    exit (violations > 0 ? 1 : 0)
}
'

# ---------------------------------------------------------------------
# Sub-Rule B helper awk programs.
#
# extract_paragraphs_awk
#   Reads a production .cbl file and emits one paragraph name per line
#   on stdout.  Recognises lines of the form
#       <leading whitespace><IDENT>.<optional trailing whitespace>
#   where IDENT begins with a letter or digit and contains only
#   uppercase letters, digits, hyphens, and underscores.  Uppercases
#   the line first so lower-/mixed-case paragraph names normalise to
#   the canonical comparison form.  Skips comment lines.
#
#   The leading-letter-or-digit allowance is essential: CardDemo
#   numbered paragraphs (e.g. 0000-TCATBALF-OPEN, 1300-COMPUTE-INTEREST,
#   9999-ABEND-PROGRAM) start with a digit.
#
#   The pattern is intentionally permissive -- statement-only lines
#   such as `EXIT.`, `GOBACK.`, `END-PERFORM.` will also emit a
#   "paragraph name", but those are COBOL reserved tokens that are
#   never legitimate MOCK PARAGRAPH targets, so the over-recognition
#   does not cause false positives in practice.
#
# extract_mocks_awk
#   Reads a .cut file and emits one line per MOCK PARAGRAPH or MOCK
#   SECTION directive in the form <file>:<line>:<TARGET>.  Uppercases
#   the line first.  Strips leading non-identifier characters before
#   the target name so that surrounding punctuation (commas, colons,
#   etc.) does not corrupt the captured target.
# ---------------------------------------------------------------------
extract_paragraphs_awk='
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
    if (is_comment($0)) next
    line = toupper($0)
    sub(/\r$/, "", line)
    sub(/^[[:space:]]+/, "", line)
    sub(/[[:space:]]+$/, "", line)
    if (line ~ /^[A-Z0-9][A-Z0-9_-]*\.$/) {
        name = line
        sub(/\.$/, "", name)
        if (length(name) > 0) print name
    }
}
'

extract_mocks_awk='
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
    if (is_comment($0)) next
    upper = toupper($0)
    # MOCK must be the first non-whitespace token on the line so that
    # the words MOCK PARAGRAPH X appearing inside a string literal
    # (e.g., a TESTSUITE description) do not trip a false positive.
    if (upper ~ /^[[:space:]]*MOCK[[:space:]]+(PARAGRAPH|SECTION)[[:space:]]+[A-Z0-9_-]+/) {
        tmp = upper
        sub(/^[[:space:]]*MOCK[[:space:]]+(PARAGRAPH|SECTION)[[:space:]]+/, "", tmp)
        n = ""
        L = length(tmp)
        for (i = 1; i <= L; i++) {
            c = substr(tmp, i, 1)
            if (c == " " || c == "\t" || c == "." || c == ",") break
            n = n c
        }
        if (n != "") print FILENAME ":" FNR ":" n
    }
}
'

# ---------------------------------------------------------------------
# Sub-Rule A execution loop.
# Runs the per-file state machine and counts files containing any
# violation in the marker_file.  Each violation message is emitted
# directly to stderr by the awk program with the AAP-mandated phrasing
# so a CI reader sees the failure inline.
# ---------------------------------------------------------------------
total_files=0

while IFS= read -r file; do
    [ -z "$file" ] && continue
    total_files=$((total_files + 1))

    # awk emits one "F" line on stdout per detected violation and the
    # AAP-mandated diagnostic on stderr.  awk also exits 1 from its
    # END block when any violation was detected, so under `set -e`
    # the redirection happens before the failure is observed; we
    # tolerate the exit code explicitly with the `|| true` idiom.
    awk "$awk_program_a" "$file" >> "$marker_file" || true
done < "$files_list"

# ---------------------------------------------------------------------
# Sub-Rule B execution loop.
# For each .cut file, derive the PUT name from the basename, locate
# the production .cbl/.CBL/.cob/.COB source, extract its paragraph
# names, then walk the .cut file's MOCK PARAGRAPH/SECTION directives
# and emit a violation for any target that matches a production
# paragraph name.
# ---------------------------------------------------------------------
while IFS= read -r file; do
    [ -z "$file" ] && continue

    # Derive the program-under-test name from the .cut filename.
    base="$(basename "$file" .cut)"
    put="$(printf '%s' "$base" | tr '[:lower:]' '[:upper:]')"

    # Search for the production source.  CardDemo uses both
    # lower-case (.cbl) and upper-case (.CBL) extensions across the
    # tree, plus occasional .cob/.COB; we accept all four.
    prod_src=""
    for ext in cbl CBL cob COB; do
        candidate="${APP_DIR}/${put}.${ext}"
        if [ -f "$candidate" ]; then
            prod_src="$candidate"
            break
        fi
    done

    # Also accept exact-case match against directory listing -- some
    # filesystems are case-sensitive and the basename may already be
    # in canonical form (e.g., CBSTM03B.CBL).  The loop above tried
    # the upper-cased name; here we additionally try the original.
    if [ -z "$prod_src" ]; then
        for ext in cbl CBL cob COB; do
            candidate="${APP_DIR}/${base}.${ext}"
            if [ -f "$candidate" ]; then
                prod_src="$candidate"
                break
            fi
        done
    fi

    # No matching production file means sub-rule B has nothing to
    # cross-check.  This is benign: helper testsuites whose name does
    # not correspond to a production program (e.g., a hypothetical
    # tests/cobol-check/HELPER.cut) are simply skipped by sub-rule B.
    if [ -z "$prod_src" ]; then
        continue
    fi

    paragraphs_file="$(mktemp 2>/dev/null || mktemp -t lint_para)"
    awk "$extract_paragraphs_awk" "$prod_src" | sort -u > "$paragraphs_file"

    # If the production source contains no recognisable paragraphs
    # (very unusual -- only happens for empty or pure-data files),
    # there is nothing to cross-check.
    if [ ! -s "$paragraphs_file" ]; then
        rm -f "$paragraphs_file"
        continue
    fi

    # Extract MOCK targets from the .cut file and check each against
    # the paragraph names.  Use grep -x -F (exact-line, fixed-string)
    # for the membership test so that a target containing regex
    # metacharacters cannot misclassify the comparison.
    awk "$extract_mocks_awk" "$file" | while IFS=: read -r mock_file mock_line mock_target; do
        [ -z "$mock_target" ] && continue
        if grep -q -x -F -- "$mock_target" "$paragraphs_file"; then
            printf 'BLITZY VALIDATION GATE FAILED: business logic detected outside imports/setup/invocation/assertions in %s:%s. Re-author the test to PERFORM the production paragraph instead of computing the expected value yourself.\n' \
                "$mock_file" "$mock_line" >&2
            printf 'F\n' >> "$marker_file"
        fi
    done

    rm -f "$paragraphs_file"
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
    printf '%s PASS - scanned %s file(s)\n' \
        "$LOG_PREFIX" "$total_files"
    exit 0
else
    printf '%s FAIL - %s violation(s) in %s file(s) scanned\n' \
        "$LOG_PREFIX" "$violation_count" "$total_files" >&2
    exit 1
fi
