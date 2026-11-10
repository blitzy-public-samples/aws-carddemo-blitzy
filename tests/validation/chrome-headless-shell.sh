#!/usr/bin/env bash
# Browser launcher for mmdc. check-diagrams.mjs passes this script to mmdc as
# the browser executable through PUPPETEER_EXECUTABLE_PATH, which replaces any
# value the host sets and outranks an executablePath in a .puppeteerrc
# configuration file, so every mmdc render launches the chrome-headless-shell
# build pinned by the locked puppeteer. That build is the one
# tests/validation/run-all.sh installs: puppeteer's pinned revision, passed
# explicitly as `chrome-headless-shell@<build id>`, in puppeteer's cache
# directory, both resolved from the modules installed in tests/validation. When
# the pinned build is missing, the script exits non-zero, names the missing build
# and points to run-all.sh, which installs it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The snippet loads puppeteer's modules from this directory's package.json, with its
# working directory in a new empty temporary directory that is removed before the
# browser starts, because puppeteer loads a configuration file as code from its
# working directory and every directory above it: run inside the repository, it
# would run any .puppeteerrc.cjs or puppeteer.config.js the repository holds. The
# directory is verified to be outside the repository before node starts: its
# physical path, symlinks resolved, must not equal or lie below the physical
# repository root, and a TMPDIR that resolves inside it stops the script. The
# comparison ends both paths with / so a sibling such as <root>x is not inside
# <root>, and a root of / holds every path. The script's own directory reaches the
# snippet as an argument, never as part of its source. It prints the pinned build id
# and the build's executable path, one per line; both must be present and non-empty.
if ! root="$(cd "$here/../.." && pwd -P)" || [[ -z "$root" ]]; then
  echo "chrome-headless-shell.sh: cannot resolve the repository root" >&2
  exit 1
fi
if ! work="$(mktemp -d)"; then
  echo "chrome-headless-shell.sh: cannot create a temporary directory" >&2
  exit 1
fi
if ! work_physical="$(cd -- "$work" && pwd -P)" || [[ -z "$work_physical" ]]; then
  rm -rf -- "$work"
  echo "chrome-headless-shell.sh: cannot resolve the temporary directory" >&2
  exit 1
fi
if [[ "${work_physical}/" == "${root%/}/"* ]]; then
  rm -rf -- "$work"
  echo "chrome-headless-shell.sh: TMPDIR resolves inside the repository; set it to a directory outside it" >&2
  exit 1
fi
if ! resolved="$(cd "$work" && node -e "
const { createRequire } = require('node:module');
const path = require('node:path');
const load = createRequire(path.join(process.argv[1], 'package.json'));
const { Browser, computeExecutablePath } = load('@puppeteer/browsers');
const { PUPPETEER_REVISIONS } = load('puppeteer-core/internal/revisions.js');
const puppeteer = load('puppeteer').default;
const buildId = PUPPETEER_REVISIONS[Browser.CHROMEHEADLESSSHELL];
if (!buildId) throw new Error('the locked puppeteer pins no chrome-headless-shell build');
process.stdout.write(buildId + '\n' + computeExecutablePath({
  cacheDir: puppeteer.configuration.cacheDirectory,
  browser: Browser.CHROMEHEADLESSSHELL,
  buildId,
}) + '\n');
" "$here")"; then
  resolved=""
fi
rm -rf -- "$work"
build_id="${resolved%%$'\n'*}"
browser="${resolved#*$'\n'}"
if [[ "$resolved" != *$'\n'* || "$browser" == *$'\n'* || -z "$build_id" || -z "$browser" ]]; then
  echo "chrome-headless-shell.sh: cannot resolve the chrome-headless-shell build pinned by the locked puppeteer in tests/validation/node_modules; rerun tests/validation/run-all.sh" >&2
  exit 1
fi

if [[ ! -f "$browser" || ! -x "$browser" ]]; then
  echo "chrome-headless-shell.sh: pinned chrome-headless-shell@${build_id} not found at $browser; rerun tests/validation/run-all.sh, which installs it" >&2
  exit 1
fi

exec "$browser" "$@"
