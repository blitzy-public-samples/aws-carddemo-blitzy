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
# It needs no credentials and is run with no secrets in its environment, because it
# installs and renders from repository content, which in a CI pull-request stage is
# the pull request's own content.
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

# npm ci installs what tests/validation tells it to, so the step first refuses
# repository content that would choose where packages come from or what code runs:
# - tests/validation/.npmrc, which npm reads as project configuration whatever flags
#   it is given, and which can name a registry, send credentials to it and pass node
#   options to every process npm starts;
# - tests/validation/npm-shrinkwrap.json, which npm ci installs from in place of
#   package-lock.json;
# - a package.json that is a symbolic link or not a regular file, because npm ci
#   reads the manifest wherever a link points and quotes the start of a file it cannot
#   parse in its error, so a link to a host file such as ~/.npmrc would print it;
# - a package-lock.json that is a symbolic link or not a regular file, so the check
#   reads only the repository's own lockfile, never a file elsewhere on the host;
# - any package-lock.json entry other than the registry.npmjs.org tarball, at the
#   entry's version, of the package its node_modules path installs, with one sha512
#   integrity hash, because npm ci fetches whatever URL an entry resolves to and checks
#   it only against the hash written beside it. npm writes every registry entry in
#   exactly this form. An alias entry, whose name differs from the package its path
#   installs, is refused too: npm would install the named package's own registry
#   tarball under the path's name, so any published package could stand in for a
#   trusted one, such as the js-yaml the checks load. npm writes a name only for an
#   alias, and this suite's lockfile needs none.
# Lockfile values are printed as escaped JSON, so none of their characters can start
# an output line or reach a terminal or CI log as a control sequence. A lockfile that
# cannot be read or parsed is reported with a fixed reason, never with the parser's
# message, which quotes the start of the file. A refused step runs no npm ci at all.
readonly LOCKFILE_CHECK='
const fs = require("fs");
const file = process.argv[1];
const shown = 20;
const show = (value) => value === undefined ? "none" : JSON.stringify(value).replace(
  /[\u007f-\u009f\u2028\u2029]/g, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0"));
const say = (text) => process.stderr.write("run-all: refusing npm ci: " + file + " " + text + "\n");
const fail = (text) => { say(text); process.exit(1); };
const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
// A read failure is reported by its fs error code alone, and a parse failure by a
// fixed reason: the error messages name the path or quote the start of the file.
const code = (err) => err && typeof err.code === "string" && /^E[A-Z0-9]+$/.test(err.code) ? err.code : "unknown error";
// lstat does not follow a symbolic link, so a link to a file elsewhere on the host is
// refused before it is read, as is a directory, device or named pipe.
let stat;
try {
  stat = fs.lstatSync(file);
} catch (err) {
  fail("cannot be read (" + code(err) + ")");
}
if (stat.isSymbolicLink()) fail("is a symbolic link, not a regular file");
if (!stat.isFile()) fail("is not a regular file");
let text;
try {
  text = fs.readFileSync(file, "utf8");
} catch (err) {
  fail("cannot be read (" + code(err) + ")");
}
let lock;
try {
  // npm drops a leading byte-order mark before parsing, and so does this.
  lock = JSON.parse(text.replace(/^\ufeff/, ""));
} catch {
  fail("is not valid JSON");
}
if (!isObject(lock)) fail("is not a JSON object");
if (lock.lockfileVersion !== 3) fail("has lockfileVersion " + show(lock.lockfileVersion) + ", not 3");
if (!isObject(lock.packages)) fail("has no packages object");
// A name part is what npm publishes: URL-safe as it stands, and never . or ..
const part = (s) => {
  try { return s !== "" && s !== "." && s !== ".." && encodeURIComponent(s) === s; } catch { return false; }
};
const isName = (n) => {
  const scoped = /^@([^/]*)\/([^/]*)$/.exec(n);
  return scoped ? part(scoped[1]) && part(scoped[2]) : part(n);
};
// The package a node_modules/<name>[/node_modules/<name>...] key installs, or null.
const keyName = (key) => {
  const parts = key.split("/");
  let name = null;
  for (let i = 0; i < parts.length; ) {
    if (parts[i] !== "node_modules" || i + 1 >= parts.length) return null;
    const scoped = parts[i + 1].startsWith("@");
    if (scoped && i + 2 >= parts.length) return null;
    name = scoped ? parts[i + 1] + "/" + parts[i + 2] : parts[i + 1];
    if (!isName(name)) return null;
    i += scoped ? 3 : 2;
  }
  return name;
};
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$/;
const SHA512 = /^sha512-[A-Za-z0-9+/]+={0,2}$/;
const bad = [];
let total = 0;
for (const [key, entry] of Object.entries(lock.packages)) {
  if (key === "") continue;
  total += 1;
  const why = (() => {
    const fromKey = keyName(key);
    if (fromKey === null) return "is not a node_modules/<name> path";
    if (!isObject(entry)) return "is not an object";
    // npm writes a name only for an alias, which installs the named package under
    // the name its path gives, so an entry is checked against the package at its path.
    if (entry.name !== undefined && entry.name !== fromKey) {
      return "names package " + show(entry.name) + ", but its path installs " + show(fromKey) + " (aliases are refused)";
    }
    const name = fromKey;
    if (typeof entry.version !== "string" || !SEMVER.test(entry.version)) {
      return "has version " + show(entry.version) + ", not a semver version";
    }
    const tarball = "https://registry.npmjs.org/" + name + "/-/" + name.replace(/^@[^/]*\//, "") + "-" + entry.version + ".tgz";
    if (entry.resolved !== tarball) return "resolves to " + show(entry.resolved) + ", not " + show(tarball);
    if (typeof entry.integrity !== "string" || !SHA512.test(entry.integrity)) {
      return "has integrity " + show(entry.integrity) + ", not one sha512 hash";
    }
    return null;
  })();
  if (why !== null) bad.push("entry " + show(key) + " " + why);
}
if (bad.length > 0) {
  for (const line of bad.slice(0, shown)) say(line);
  say("has " + bad.length + " of " + total + " entries that are not the registry.npmjs.org tarball of the package at their path with sha512 integrity"
    + (bad.length > shown ? " (first " + shown + " listed)" : ""));
  process.exit(1);
}
'
# npm ci then runs with:
# - --ignore-scripts, so no install script runs, neither a dependency's (buf's
#   install.js, @scarf/scarf's report.js, puppeteer's install.mjs) nor the package's
#   own: no repository-controlled code runs during the install, and no browser is
#   fetched here, so a browser-host outage cannot fail npm ci and cost the run buf,
#   Spectral and the YAML parser. buf needs no install script, because its bin/buf
#   runs the binary of the installed @bufbuild/buf-<platform> package.
# - SCARF_ANALYTICS=false, scarf's opt-out of the install analytics it otherwise
#   sends, and PUPPETEER_SKIP_DOWNLOAD=true, so the command itself states that the
#   install reports nothing and downloads no browser.
# - --registry, --userconfig and --cache set to the public registry, an empty user
#   configuration and npm's default cache directory, so neither a configuration file
#   nor an npm_config_* variable for them decides where packages come from or where
#   credentials go.
echo "== setup: npm ci =="
npm_ci_allowed=1
if [[ -e tests/validation/.npmrc || -L tests/validation/.npmrc ]]; then
  echo "run-all: refusing npm ci: tests/validation/.npmrc exists, and npm would take registry, credential and node settings from it" >&2
  npm_ci_allowed=0
fi
if [[ -e tests/validation/npm-shrinkwrap.json || -L tests/validation/npm-shrinkwrap.json ]]; then
  echo "run-all: refusing npm ci: tests/validation/npm-shrinkwrap.json exists, and npm ci would install from it instead of package-lock.json" >&2
  npm_ci_allowed=0
fi
if [[ -L tests/validation/package.json || ! -f tests/validation/package.json ]]; then
  echo "run-all: refusing npm ci: tests/validation/package.json is a symbolic link or not a regular file, and npm would read it wherever it points and quote it in its errors" >&2
  npm_ci_allowed=0
fi
node -e "$LOCKFILE_CHECK" tests/validation/package-lock.json || npm_ci_allowed=0
if ! npm_cache="$(node -p 'require("path").join(require("os").homedir(), ".npm")')" \
  || [[ -z "$npm_cache" ]]; then
  echo "run-all: refusing npm ci: cannot determine npm's default cache directory" >&2
  npm_ci_allowed=0
fi
if [[ "$npm_ci_allowed" -ne 1 ]] || ! (
  cd tests/validation \
    && SCARF_ANALYTICS=false PUPPETEER_SKIP_DOWNLOAD=true npm ci --ignore-scripts --no-audit --no-fund \
      --registry=https://registry.npmjs.org/ --userconfig=/dev/null --cache="$npm_cache"
); then
  echo "FAIL setup: npm ci"
  setup_failures+=("npm ci")
fi

# This step installs exactly the chrome-headless-shell build the locked puppeteer
# pins, into puppeteer's cache directory: the build that
# tests/validation/chrome-headless-shell.sh launches for mmdc. The build id is read
# from puppeteer-core's pinned revisions, which load no configuration, and passed
# explicitly. The puppeteer CLI runs from tests/validation/node_modules with its
# working directory in a new empty temporary directory, removed afterwards, because
# puppeteer loads a configuration file as code from its working directory and every
# directory above it: run inside the repository, it would run any .puppeteerrc.cjs or
# puppeteer.config.js the repository holds, and take its cache directory and download
# host from it. The directory is verified to be outside the repository before the CLI
# starts; a TMPDIR that resolves inside it fails this step and starts no CLI. This is
# the only setup step that needs the browser download host, and only while that build
# is not yet in the cache.
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
  if ! install_cwd="$(mktemp -d)"; then
    echo "run-all: cannot create a temporary directory for the puppeteer CLI" >&2
    exit 1
  fi
  trap 'rm -rf -- "$install_cwd"' EXIT
  # The directory's physical path, symlinks resolved, must lie outside the physical
  # repository root. The comparison ends both paths with / so a sibling such as
  # <root>x is not inside <root>, and a root of / holds every path.
  if ! install_cwd_physical="$(cd -- "$install_cwd" && pwd -P)" || [[ -z "$install_cwd_physical" ]]; then
    echo "run-all: cannot resolve the temporary directory for the puppeteer CLI" >&2
    exit 1
  fi
  if [[ "${install_cwd_physical}/" == "${ROOT%/}/"* ]]; then
    echo "run-all: TMPDIR resolves inside the repository; set it to a directory outside it" >&2
    exit 1
  fi
  cd "$install_cwd" || exit 1
  "${ROOT}/tests/validation/node_modules/.bin/puppeteer" browsers install "chrome-headless-shell@${build_id}"
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
