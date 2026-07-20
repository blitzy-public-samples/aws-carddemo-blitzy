#!/usr/bin/env bash
# CardDemo REST/JSON API - boundary/fault fixture prepare + verify + clean.
#
# QA finding MAJ-08: a clean environment must be able to DETERMINISTICALLY
# prepare every documented boundary/fault state without tribal knowledge.
# This is the off-mainframe half that runs anywhere Python 3 is available: it
# (re)generates the byte-exact flat fixtures and verifies their record counts
# and SHA-256 digests against the committed manifest. The on-mainframe half
# (loading the flat files into disposable clone VSAM datasets) is
# app/test/api/load-boundary-fixtures.jcl, with cleanup in
# app/test/api/clean-boundary-fixtures.jcl.
#
# Licensed under the Apache License, Version 2.0:
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Usage:
#   app/test/api/prepare-boundary-fixtures.sh            # generate + verify
#   app/test/api/prepare-boundary-fixtures.sh --verify   # verify only
#   app/test/api/prepare-boundary-fixtures.sh --clean    # remove generated
#                                                        # non-canonical output
# Exit codes: 0 success; 1 verification mismatch; 2 usage/environment error.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GEN="${SCRIPT_DIR}/gen-boundary-fixtures.py"
FIXTURE_DIR="${SCRIPT_DIR}/fixtures"
MANIFEST="${FIXTURE_DIR}/MANIFEST.txt"
PYTHON_BIN="${PYTHON_BIN:-python3}"

die() { echo "ERROR: $*" >&2; exit 2; }

[ -f "${GEN}" ] || die "generator not found: ${GEN}"
command -v "${PYTHON_BIN}" >/dev/null 2>&1 || die "python3 not on PATH"

MODE="prepare"
case "${1:-}" in
  --verify) MODE="verify" ;;
  --clean)  MODE="clean" ;;
  "")       MODE="prepare" ;;
  *)        die "unknown option: $1 (use --verify or --clean)" ;;
esac

if [ "${MODE}" = "clean" ]; then
  # Remove any non-canonical, disposable output while keeping the committed
  # canonical fixtures + MANIFEST that ship in the repository.
  if [ -d "${FIXTURE_DIR}" ]; then
    find "${FIXTURE_DIR}" -maxdepth 1 -type f \
      ! -name 'MANIFEST.txt' \
      ! -name 'card-boundary-50.*' \
      ! -name 'card-overflow-51.*' \
      ! -name 'tran-boundary-50.*' \
      ! -name 'tran-truncate-51.*' \
      -print -delete
  fi
  echo "clean: canonical fixtures + MANIFEST.txt retained."
  exit 0
fi

if [ "${MODE}" = "prepare" ]; then
  echo "== generating canonical boundary fixtures into ${FIXTURE_DIR} =="
  "${PYTHON_BIN}" "${GEN}" --out-dir "${FIXTURE_DIR}"
fi

echo "== verifying record counts + SHA-256 against ${MANIFEST} =="
if ! "${PYTHON_BIN}" "${GEN}" --verify --manifest "${MANIFEST}"; then
  echo "FAILED: generated fixtures do not match the committed manifest." >&2
  exit 1
fi

cat <<'NEXT'

== fixtures prepared and verified ==
Next steps to exercise a boundary/fault state in a DISPOSABLE region:
  1. Upload the chosen scenario's flat files into the APIBT staging PS
     datasets (LRECL 150 / 50 / 350; see the load JCL header).
  2. Run  app/test/api/load-boundary-fixtures.jcl  (SET SCN= to your
     scenario) to REPRO them into clone VSAM datasets + build the AIXes.
  3. Call GET /carddemo/api/v1/accounts/{acctId}/transactions and compare
     against the expected outcome in fixtures/MANIFEST.txt.
  4. Run  app/test/api/clean-boundary-fixtures.jcl  to delete the clones.
NEXT
