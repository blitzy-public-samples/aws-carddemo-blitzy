#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Validation entry point for the Card Servicing backlog, documents, diagrams and
# contracts. It installs the pinned toolchain, then runs the seven checks in order:
# forbidden terms, backlog structure, plan consistency, diagrams, contracts,
# dependency order and reachability. Every check runs even after an earlier one
# fails, each check's result is reported, and the script exits 0 only when every
# setup step and every check passed.
#
# Run: tests/validation/run-all.sh
#   From the repository root; the script changes to the root itself, so any working
#   directory gives the same result.
#
# Requires Node.js 22 and access to the npm registry. No Java, Docker or Gradle.
# Chrome, started by mmdc in check-diagrams, aborts with "Socket path too long" under
# a long TMPDIR, so run with a short TMPDIR (the default /tmp is fine).
#
# A failing check is fixed in the artifact it reports, never by weakening the check.

# No -e: a failing setup step or check must not stop the checks after it.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT" || exit 1

readonly -a CHECKS=(
  check-forbidden-terms
  check-backlog-structure
  check-plan-consistency
  check-diagrams
  check-contracts
  check-dependency-order
  check-reachability
)

if ! tmp="$(mktemp)"; then
  echo "run-all: cannot create a temporary file" >&2
  exit 1
fi
trap 'rm -f "$tmp"' EXIT

setup_failures=()

echo "== setup: node version =="
if ! command -v node >/dev/null 2>&1; then
  echo "run-all: Node.js 22 required, found none" >&2
  exit 1
fi
node_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null)"
if [[ "$node_major" != "22" ]]; then
  echo "run-all: Node.js 22 required, found $(node --version 2>/dev/null || echo none)" >&2
  exit 1
fi
echo "Node.js $(node --version)"

echo "== setup: npm ci =="
if ! (cd tests/validation && npm ci --no-audit --no-fund); then
  echo "FAIL setup: npm ci"
  setup_failures+=("npm ci")
fi

# npm 11 skips puppeteer's postinstall, which would otherwise download the browser
# that tests/validation/chrome-headless-shell.sh launches for mmdc.
echo "== setup: chrome-headless-shell install =="
if ! (cd tests/validation && npx --no-install puppeteer browsers install chrome-headless-shell); then
  echo "FAIL setup: chrome-headless-shell install"
  setup_failures+=("chrome-headless-shell install")
fi

summary=()
failed=0
for check in "${CHECKS[@]}"; do
  echo "== ${check} =="
  node "tests/validation/${check}.mjs" 2>&1 | tee "$tmp"
  status="${PIPESTATUS[0]}"
  count="$(sed -nE "s/^${check}: ([0-9]+) violation\\(s\\)\$/\\1/p" "$tmp" | tail -n 1)"
  if [[ "$status" -eq 0 && "$count" == "0" ]]; then
    summary+=("PASS ${check} (${count} violations)")
  else
    failed=$((failed + 1))
    if [[ -z "$count" ]]; then
      summary+=("FAIL ${check} (error)")
    else
      summary+=("FAIL ${check} (${count} violations)")
    fi
  fi
done

echo
# Setup failures head the summary so a run whose checks all passed against a stale or
# partial toolchain is not read as green; they also make the exit status non-zero.
for step in "${setup_failures[@]+"${setup_failures[@]}"}"; do
  echo "FAIL setup: ${step}"
done
printf '%s\n' "${summary[@]}"
if [[ "$failed" -eq 0 ]]; then
  echo "Validation: all ${#CHECKS[@]} checks passed"
else
  echo "Validation: ${failed} of ${#CHECKS[@]} checks failed"
fi

if [[ "$failed" -eq 0 && "${#setup_failures[@]}" -eq 0 ]]; then
  exit 0
fi
exit 1
