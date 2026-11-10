#!/usr/bin/env bash
# Browser launcher for mmdc. check-diagrams.mjs passes this script to mmdc as
# the browser executable through PUPPETEER_EXECUTABLE_PATH, which replaces any
# value the host sets and outranks an executablePath in a .puppeteerrc
# configuration file, so every mmdc render launches the chrome-headless-shell
# build pinned by the locked puppeteer. That build is the one
# tests/validation/run-all.sh installs: puppeteer's pinned revision, passed
# explicitly as `chrome-headless-shell@<build id>`, in puppeteer's configured
# cache directory, both resolved from tests/validation. When the pinned build is
# missing, the script exits non-zero and prints that pinned install command.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The snippet prints the pinned build id and the build's executable path, one per
# line; both must be present and non-empty.
if ! resolved="$(cd "$here" && node --input-type=module -e "
import { Browser, computeExecutablePath } from '@puppeteer/browsers';
import { PUPPETEER_REVISIONS } from 'puppeteer-core/internal/revisions.js';
import puppeteer from 'puppeteer';
const buildId = PUPPETEER_REVISIONS[Browser.CHROMEHEADLESSSHELL];
if (!buildId) throw new Error('the locked puppeteer pins no chrome-headless-shell build');
process.stdout.write(buildId + '\n' + computeExecutablePath({
  cacheDir: puppeteer.configuration.cacheDirectory,
  browser: Browser.CHROMEHEADLESSSHELL,
  buildId,
}) + '\n');
")"; then
  resolved=""
fi
build_id="${resolved%%$'\n'*}"
browser="${resolved#*$'\n'}"
if [[ "$resolved" != *$'\n'* || "$browser" == *$'\n'* || -z "$build_id" || -z "$browser" ]]; then
  echo "chrome-headless-shell.sh: cannot resolve the chrome-headless-shell build pinned by the locked puppeteer in tests/validation/node_modules; rerun tests/validation/run-all.sh" >&2
  exit 1
fi

if [[ ! -f "$browser" || ! -x "$browser" ]]; then
  echo "chrome-headless-shell.sh: pinned chrome-headless-shell not found at $browser; install it with: (cd tests/validation && npx --no-install puppeteer browsers install chrome-headless-shell@${build_id}) or rerun tests/validation/run-all.sh" >&2
  exit 1
fi

exec "$browser" "$@"
