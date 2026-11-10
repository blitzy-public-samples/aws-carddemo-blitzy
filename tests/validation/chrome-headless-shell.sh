#!/usr/bin/env bash
# Browser launcher for mmdc. puppeteer-config.json names this script as the
# browser executable, so every mmdc render launches the chrome-headless-shell
# build pinned by the locked puppeteer, whatever browser PUPPETEER_EXECUTABLE_PATH
# or the host provides. The build is resolved the same way
# `npx --no-install puppeteer browsers install chrome-headless-shell` (run in
# tests/validation) installs it: puppeteer's cache directory and its pinned
# revision. Puppeteer resolves the relative executablePath against mmdc's
# working directory, so mmdc runs from the repository root. When the pinned
# build is missing, the script exits non-zero and prints the install command.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

browser="$(cd "$here" && node --input-type=module -e "
import { Browser, computeExecutablePath } from '@puppeteer/browsers';
import { PUPPETEER_REVISIONS } from 'puppeteer-core/internal/revisions.js';
import puppeteer from 'puppeteer';
process.stdout.write(computeExecutablePath({
  cacheDir: puppeteer.configuration.cacheDirectory,
  browser: Browser.CHROMEHEADLESSSHELL,
  buildId: PUPPETEER_REVISIONS[Browser.CHROMEHEADLESSSHELL],
}));
")"

if [[ ! -f "$browser" || ! -x "$browser" ]]; then
  echo "chrome-headless-shell.sh: pinned chrome-headless-shell not found at $browser; install it with: (cd tests/validation && npx --no-install puppeteer browsers install chrome-headless-shell)" >&2
  exit 1
fi

exec "$browser" "$@"
