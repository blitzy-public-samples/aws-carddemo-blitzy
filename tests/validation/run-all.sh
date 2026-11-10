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
#   From the repository root, or through any path or symlink to this file. The
#   script resolves its own real location, symlinks included, and changes to the
#   repository root that holds it, so any working directory gives the same result.
#   When that location cannot be resolved, as when the script is read from a pipe,
#   it stops with an error before any setup step.
#
# Requires Node.js 22 and access to the npm registry. No Java, Docker or Gradle.
# Chrome, started by mmdc in check-diagrams, aborts with "Socket path too long" under
# a long TMPDIR, so run with a short TMPDIR (the default /tmp is fine).
#
# A failing check is fixed in the artifact it reports, never by weakening the check.

# No -e: a failing setup step or check must not stop the checks after it.
set -uo pipefail

# The repository root is the grandparent of the directory that really holds this
# script. Every step that finds it is checked, and any failure stops the run before
# the first setup step, so no install or check runs against another directory. The
# steps' own error output is discarded so that failure prints one line.
script_source="${BASH_SOURCE[0]-}"
fail_root_resolution() {
  echo "run-all: cannot resolve the repository root from ${script_source:-a script with no file path}; run tests/validation/run-all.sh from a checkout of the repository" >&2
  exit 1
}
# An exported CDPATH would let the relative cd below enter a directory other than
# the one named, and print it into the captured path.
unset CDPATH
[[ -n "$script_source" ]] || fail_root_resolution

# Follow symlinks to the script file. A relative link target is relative to the
# directory that holds the link. Forty hops is the kernel's own limit, so a link
# cycle stops with the resolution error.
script_path="$script_source"
link_hops=0
while [[ -L "$script_path" ]]; do
  link_hops=$((link_hops + 1))
  if ((link_hops > 40)) || ! link_target="$(readlink -- "$script_path" 2>/dev/null)" \
    || [[ -z "$link_target" ]]; then
    fail_root_resolution
  fi
  if [[ "$link_target" == /* ]]; then
    script_path="$link_target"
  else
    if ! link_dir="$(dirname -- "$script_path" 2>/dev/null)" || [[ -z "$link_dir" ]]; then
      fail_root_resolution
    fi
    script_path="${link_dir}/${link_target}"
  fi
done

# cd -P resolves symlinked directories on the way, so SCRIPT_DIR and ROOT are
# physical paths. The root must hold this same file at tests/validation/run-all.sh.
if ! script_dir_named="$(dirname -- "$script_path" 2>/dev/null)" || [[ -z "$script_dir_named" ]] \
  || ! SCRIPT_DIR="$(cd -P -- "$script_dir_named" 2>/dev/null && pwd -P)" || [[ -z "$SCRIPT_DIR" ]] \
  || ! ROOT="$(cd -P -- "${SCRIPT_DIR}/../.." 2>/dev/null && pwd -P)" || [[ -z "$ROOT" ]] \
  || [[ ! "${ROOT}/tests/validation/run-all.sh" -ef "${SCRIPT_DIR}/${script_path##*/}" ]]; then
  fail_root_resolution
fi
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
# One probe decides the gate and supplies every version this step prints. It runs
# under the caller's NODE_OPTIONS, as the tools and checks will, so a node that
# cannot start stops here with its own error instead of a version from another call.
node_version="$(node -p 'process.versions.node' 2>"$tmp")"
node_status=$?
if [[ "$node_status" -ne 0 ]]; then
  echo "run-all: Node.js 22 required, but node failed to run (exit ${node_status}):" >&2
  if [[ -s "$tmp" ]]; then
    head -n 20 "$tmp" >&2
  else
    echo "(node printed no error output)" >&2
  fi
  exit 1
fi
if [[ ! "$node_version" =~ ^([0-9]+)\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "run-all: Node.js 22 required, but node reported an unrecognised version:" >&2
  printf '%s\n' "$node_version" | head -n 20 >&2
  exit 1
fi
if [[ "${BASH_REMATCH[1]}" != "22" ]]; then
  echo "run-all: Node.js 22 required, found v${node_version}" >&2
  exit 1
fi
echo "Node.js v${node_version}"

# npm ci runs the dependencies' install scripts, puppeteer's postinstall included,
# and that script downloads browsers unless told not to. PUPPETEER_SKIP_DOWNLOAD,
# set for npm ci alone, stops it, so no browser is fetched here and a browser-host
# outage cannot fail npm ci and cost the run buf, Spectral and the YAML parser.
echo "== setup: npm ci =="
if ! (cd tests/validation && PUPPETEER_SKIP_DOWNLOAD=true npm ci --no-audit --no-fund); then
  echo "FAIL setup: npm ci"
  setup_failures+=("npm ci")
fi

# This step installs exactly the chrome-headless-shell build the locked puppeteer
# pins, into puppeteer's configured cache: the build that
# tests/validation/chrome-headless-shell.sh launches for mmdc. The build id is read
# from puppeteer's pinned revisions and passed explicitly, because without one
# puppeteer installs whatever version a puppeteer config file in tests/validation or
# any directory above it names. This is the only setup step that needs the browser
# download host, and only while that build is not yet in the cache.
echo "== setup: chrome-headless-shell install =="
if ! (
  cd tests/validation || exit 1
  if ! build_id="$(node --input-type=module -e "
import { PUPPETEER_REVISIONS } from 'puppeteer-core/internal/revisions.js';
process.stdout.write(PUPPETEER_REVISIONS['chrome-headless-shell']);
")" || [[ -z "$build_id" ]]; then
    echo "run-all: cannot read the chrome-headless-shell build pinned by the locked puppeteer" >&2
    exit 1
  fi
  npx --no-install puppeteer browsers install "chrome-headless-shell@${build_id}"
); then
  echo "FAIL setup: chrome-headless-shell install"
  setup_failures+=("chrome-headless-shell install")
fi

summary=()
failed=0
# Check output carries repository text: file names, link targets, document excerpts and
# tool messages. A GitHub Actions runner executes any output line that starts with `::`
# as a workflow command, so under GITHUB_ACTIONS=true command processing is stopped for
# the checks and resumed only by a random token made here, which no repository content
# can know. When no token can be made or announced, the run stops before any check
# rather than run them unprotected.
stop_token=""
if [[ "${GITHUB_ACTIONS-}" == "true" ]]; then
  stop_token="$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
  if [[ ! "$stop_token" =~ ^[0-9a-f]{32}$ ]] || ! echo "::stop-commands::${stop_token}"; then
    echo "run-all: cannot stop workflow commands for the check output; no check was run" >&2
    exit 1
  fi
fi
for check in "${CHECKS[@]}"; do
  # A check passes only when its header and output were written, node exited 0, and
  # the capture's final line is its count line reading 0. It fails with its count
  # only when node exited 1 and that final line reads a count above 0. Every other
  # outcome fails as an error: an exit status other than 0 or 1, no count line as the
  # final line (a count line anywhere earlier, such as one inside quoted repository
  # text, is never read), a count that disagrees with the exit status, or a failed
  # write or count read, since a broken stdout or capture file must not turn into PASS.
  output_ok=1
  echo "== ${check} ==" || output_ok=0
  node "tests/validation/${check}.mjs" 2>&1 | tee "$tmp"
  pipe_status=("${PIPESTATUS[@]}")
  status="${pipe_status[0]}"
  [[ "${pipe_status[1]}" -eq 0 ]] || output_ok=0
  if ! count="$(tail -n 1 "$tmp" | sed -nE "s/^${check}: ([0-9]+) violation\\(s\\)\$/\\1/p")"; then
    output_ok=0
  fi
  if [[ "$output_ok" -eq 1 && "$status" -eq 0 && "$count" == "0" ]]; then
    summary+=("PASS ${check} (${count} violations)")
  else
    failed=$((failed + 1))
    if [[ "$output_ok" -eq 1 && "$status" -eq 1 && "$count" =~ ^[1-9][0-9]*$ ]]; then
      summary+=("FAIL ${check} (${count} violations)")
    else
      summary+=("FAIL ${check} (error)")
    fi
  fi
done
if [[ -n "$stop_token" ]] && ! echo "::${stop_token}::"; then
  echo "run-all: cannot resume workflow commands after the check output" >&2
  exit 1
fi

# The summary opens with a blank line and is written in one checked write, so a run
# whose summary cannot be written exits non-zero whatever its checks found.
summary_lines=("")
# Setup failures head the summary so a run whose checks all passed against a stale or
# partial toolchain is not read as green; they also make the exit status non-zero.
for step in "${setup_failures[@]+"${setup_failures[@]}"}"; do
  summary_lines+=("FAIL setup: ${step}")
done
summary_lines+=("${summary[@]}")
if [[ "$failed" -eq 0 ]]; then
  summary_lines+=("Validation: all ${#CHECKS[@]} checks passed")
else
  summary_lines+=("Validation: ${failed} of ${#CHECKS[@]} checks failed")
fi
if ! printf '%s\n' "${summary_lines[@]}"; then
  echo "run-all: cannot write the validation summary" >&2
  exit 1
fi

if [[ "$failed" -eq 0 && "${#setup_failures[@]}" -eq 0 ]]; then
  exit 0
fi
exit 1
