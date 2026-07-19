#!/bin/sh
# ---------------------------------------------------------------------------
# check-csd-drift.sh
# ---------------------------------------------------------------------------
# Purpose
#   Guard against configuration drift between the authoritative CICS resource
#   member  app/csd/CARDDEMOAPI.CSD  and the inline DFHCSDUP SYSIN stream that
#   is actually executed by  app/jcl/APICSDIN.jcl .
#
#   APICSDIN.jcl feeds DFHCSDUP through an INLINE //SYSIN DD *, so DFHCSDUP
#   processes the JCL stream, NOT the CSD member on disk. If the two ever
#   diverge, the region is provisioned from the JCL while reviewers approve
#   the member - the exact defect this check exists to prevent. The CSD
#   member is the single source of truth; when they differ, the member wins
#   and the JCL must be regenerated from it.
#
# Contract
#   The set of DFHCSDUP DEFINE statements embedded in APICSDIN.jcl must be
#   byte-for-byte identical to the DEFINE statements in CARDDEMOAPI.CSD.
#   Only the group-control envelope (DELETE/ADD/LIST GROUP) and comments,
#   which are legitimately job-specific, are excluded from the comparison.
#
# Exit status
#   0  no drift  - the JCL stream matches the CSD member exactly
#   1  drift      - a unified diff of the difference is printed to stderr
#   2  usage/IO   - a required input file was missing
#
# Usage
#   sh app/test/api/check-csd-drift.sh
#   (run from anywhere; paths are resolved relative to the repository root)
# ---------------------------------------------------------------------------
set -eu

# Resolve the repository root from this script's own location so the check is
# location-independent (works from a CI runner, a hook, or a shell).
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../../.." && pwd)

csd_member="$repo_root/app/csd/CARDDEMOAPI.CSD"
jcl_job="$repo_root/app/jcl/APICSDIN.jcl"

for f in "$csd_member" "$jcl_job"; do
  if [ ! -f "$f" ]; then
    echo "check-csd-drift: required file not found: $f" >&2
    exit 2
  fi
done

work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

member_defs="$work_dir/member_defs.txt"
jcl_defs="$work_dir/jcl_defs.txt"

# --- Extract the DEFINE statements from the authoritative CSD member --------
# The member is pure resource content: drop DFHCSDUP comment lines (leading
# '*') and blank lines, leaving only DEFINE statements and their attributes.
grep -vE '^\*' "$csd_member" \
  | grep -vE '^[[:space:]]*$' \
  > "$member_defs"

# --- Extract the DEFINE statements from the JCL inline SYSIN stream ---------
# 1. awk isolates the payload between the '//SYSIN DD *' card and its closing
#    '/*' delimiter.
# 2. Drop DFHCSDUP comment lines (leading '*') and blank lines.
# 3. Drop the group-control envelope (DELETE/ADD/LIST GROUP) which is
#    intentionally job-specific and has no counterpart in the member.
awk '/^\/\/SYSIN /{f=1;next} f&&/^\/\*/{f=0} f' "$jcl_job" \
  | grep -vE '^\*' \
  | grep -vE '^[[:space:]]*$' \
  | grep -vE '^[[:space:]]*(DELETE|ADD|LIST)[[:space:]]+GROUP' \
  > "$jcl_defs"

# --- Compare ----------------------------------------------------------------
if diff -u "$member_defs" "$jcl_defs" > "$work_dir/drift.diff" 2>&1; then
  member_count=$(wc -l < "$member_defs" | tr -d ' ')
  jcl_count=$(wc -l < "$jcl_defs" | tr -d ' ')
  echo "check-csd-drift: PASS - APICSDIN.jcl inline stream matches CARDDEMOAPI.CSD"
  echo "check-csd-drift: DEFINE lines compared: member=$member_count jcl=$jcl_count"
  exit 0
else
  echo "check-csd-drift: FAIL - drift detected between the CSD member and APICSDIN.jcl" >&2
  echo "check-csd-drift: the CSD member is the source of truth; regenerate the JCL stream from it" >&2
  echo "----------------------------------------------------------------------" >&2
  echo "< CARDDEMOAPI.CSD (expected)   > APICSDIN.jcl inline SYSIN (actual)" >&2
  echo "----------------------------------------------------------------------" >&2
  cat "$work_dir/drift.diff" >&2
  exit 1
fi
