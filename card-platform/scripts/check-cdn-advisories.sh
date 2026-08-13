#!/usr/bin/env bash
#
# Resolves the content-delivery pins presentation/executive-summary.html loads against an advisory
# database, and fails while any advisory stands unaccepted.
#
# Where it runs. The supply-chain stage of .github/workflows/ci.yml runs it beside the scan of the
# resolved Maven graph. That scan reads a dependency graph, and a library the deck fetches from a
# URL appears in no graph it reads, so this is the only step of this build that sees those three
# libraries. Design decisions:
# card-platform/docs/decision-log.md.
#
# What it reads
#
#   .cdn-pins.json                       the pins, the control probe and the accepted advisories
#   presentation/executive-summary.html  the deck, which is where a pin actually takes effect
#   https://api.osv.dev/v1/query         one query per pinned version, plus one for the control
#
# What it checks
#
#   1. Pin agreement, both directions. Every asset URL and every digest the manifest names occurs
#      in the deck, and every jsDelivr package URL the deck loads is named by the manifest. A pin
#      recorded in one file and not the other is the drift that makes a review describe a deck
#      nobody ships.
#   2. Advisories. Every pinned version is resolved against the database. An advisory is accepted
#      only by an entry naming the same identifier, package and version, and only until the day its
#      expired_at names. Severity is reported and does not lower the bar: any advisory the database
#      reports fails the stage unless an unexpired entry accepts it.
#   3. Accepted entries that excuse nothing. An entry the database no longer reports, or one whose
#      date has passed, fails the run rather than sitting in the file unread.
#   4. Detector sensitivity. The control version of the manifest is queried on every run, and a
#      result below its minimum_advisories fails the run. A clean verdict from a detector that has
#      gone quiet is the failure mode this step exists to refuse.
#
# What it writes
#
#   target/cdn-advisories.json  one record per pin and per advisory, with the verdict of each. The
#                               workflow keeps it with the other supply-chain reports.
#
# Usage
#
#   scripts/check-cdn-advisories.sh            resolve the pins and gate on the result (default)
#   scripts/check-cdn-advisories.sh --print    resolve the pins, print what the database says, gate
#                                              on nothing
#
# Run it from card-platform/ or from the repository root. Exit 0 when every pin is clean or
# accepted, 1 when an advisory stands unaccepted or an entry excuses nothing, 2 when the tree, the
# manifest or the database cannot be read. A database this step cannot reach is exit 2 and not a
# pass, because a check that answers clean when it asked nothing is worse than no check.

set -o errexit
set -o nounset
set -o pipefail

readonly ADVISORY_ENDPOINT="https://api.osv.dev/v1/query"
readonly MANIFEST_NAME=".cdn-pins.json"
readonly DECK_PATH="presentation/executive-summary.html"
readonly REPORT_PATH="target/cdn-advisories.json"
readonly PIN_ORIGIN="https://cdn.jsdelivr.net/npm/"
readonly REQUEST_TIMEOUT_SECONDS=30
readonly REQUEST_RETRIES=3

mode="check"
case "${1-}" in
  "") mode="check" ;;
  --check) mode="check" ;;
  --print) mode="print" ;;
  *)
    printf 'unknown argument: %s\n' "$1" >&2
    printf 'usage: %s [--check|--print]\n' "$0" >&2
    exit 2
    ;;
esac

# Resolve the platform root from this script's own location, so the caller's directory does not
# decide which tree is read.
SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
PLATFORM_ROOT="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly PLATFORM_ROOT

readonly MANIFEST="${PLATFORM_ROOT}/${MANIFEST_NAME}"
readonly DECK="${PLATFORM_ROOT}/${DECK_PATH}"
readonly REPORT="${PLATFORM_ROOT}/${REPORT_PATH}"

for tool in curl jq date; do
  if ! command -v "${tool}" > /dev/null 2>&1; then
    printf 'this check needs %s and the path does not carry it\n' "${tool}" >&2
    exit 2
  fi
done

for required in "${MANIFEST}" "${DECK}"; do
  if [[ ! -f "${required}" ]]; then
    printf 'cannot read %s\n' "${required}" >&2
    exit 2
  fi
done

if ! jq empty "${MANIFEST}" > /dev/null 2>&1; then
  printf '%s is not readable JSON\n' "${MANIFEST}" >&2
  exit 2
fi

today="$(date --utc +%Y-%m-%d)"
readonly today
today_ordinal="$(date --utc --date="${today}" +%s)"
readonly today_ordinal

failures=0
report_pins="[]"
report_advisories="[]"

note() { printf '%s\n' "$1"; }

fail() {
  printf 'FAIL %s\n' "$1" >&2
  failures=$((failures + 1))
}

# One query against the database. Prints the response body, and exits 2 when the endpoint cannot be
# reached: an unanswered query is not a clean pin.
query_advisories() {
  local package="$1" ecosystem="$2" version="$3" body response
  body="$(jq --null-input --arg name "${package}" --arg ecosystem "${ecosystem}" \
    --arg version "${version}" \
    '{package: {name: $name, ecosystem: $ecosystem}, version: $version}')"
  if ! response="$(curl --fail --silent --show-error \
      --max-time "${REQUEST_TIMEOUT_SECONDS}" --retry "${REQUEST_RETRIES}" --retry-delay 2 \
      --header 'Content-Type: application/json' \
      --data "${body}" "${ADVISORY_ENDPOINT}")"; then
    printf 'the advisory database at %s did not answer for %s@%s\n' \
      "${ADVISORY_ENDPOINT}" "${package}" "${version}" >&2
    exit 2
  fi
  if ! jq empty <<< "${response}" > /dev/null 2>&1; then
    printf 'the advisory database answered for %s@%s with a body that is not JSON\n' \
      "${package}" "${version}" >&2
    exit 2
  fi
  printf '%s' "${response}"
}

# Step 1. Pin agreement between the manifest and the deck, in both directions.
note "Reading ${MANIFEST_NAME} against ${DECK_PATH}"

manifest_urls="$(jq -r '.pins[].assets[].url' "${MANIFEST}" | sort -u)"
deck_urls="$(grep -oE "${PIN_ORIGIN}[^\"' )]+" "${DECK}" | sort -u)"

while IFS= read -r url; do
  [[ -z "${url}" ]] && continue
  if ! grep -qF -- "${url}" "${DECK}"; then
    fail "the manifest names ${url} and the deck does not load it"
  fi
done <<< "${manifest_urls}"

while IFS= read -r url; do
  [[ -z "${url}" ]] && continue
  if ! grep -qxF -- "${url}" <<< "${manifest_urls}"; then
    fail "the deck loads ${url} and the manifest does not name it, so this check never resolved it"
  fi
done <<< "${deck_urls}"

while IFS= read -r digest; do
  [[ -z "${digest}" ]] && continue
  if ! grep -qF -- "${digest}" "${DECK}"; then
    fail "the manifest carries digest ${digest} and the deck does not"
  fi
done <<< "$(jq -r '.pins[].assets[].integrity' "${MANIFEST}")"

note "pins named by the manifest: $(wc -l <<< "${manifest_urls}"), asset URLs the deck loads: $(wc -l <<< "${deck_urls}")"

# Step 2. Resolve every pin, and decide each advisory against the accepted set.
accepted_seen=""

while IFS=$'\t' read -r package ecosystem version; do
  [[ -z "${package}" ]] && continue
  response="$(query_advisories "${package}" "${ecosystem}" "${version}")"
  advisories="$(jq -r '[.vulns[]? | select(has("withdrawn") | not) | .id] | sort | .[]' \
    <<< "${response}")"
  count="$(grep -c . <<< "${advisories}" || true)"
  note "${package}@${version}: ${count} advisory record(s)"

  report_pins="$(jq --argjson pins "${report_pins}" --arg package "${package}" \
    --arg version "${version}" --argjson count "${count:-0}" --null-input \
    '$pins + [{package: $package, version: $version, advisories: $count}]')"

  while IFS= read -r advisory; do
    [[ -z "${advisory}" ]] && continue
    severity="$(jq -r --arg id "${advisory}" \
      '[.vulns[] | select(.id == $id) | .database_specific.severity // "UNSPECIFIED"][0]' \
      <<< "${response}")"
    entry="$(jq -c --arg id "${advisory}" --arg package "${package}" --arg version "${version}" \
      '[.accepted[] | select(.id == $id and .package == $package and .version == $version)][0]' \
      "${MANIFEST}")"

    verdict="unaccepted"
    if [[ "${entry}" == "null" ]]; then
      fail "${package}@${version} carries ${advisory} (${severity}) and no entry of ${MANIFEST_NAME} accepts it"
    else
      expiry="$(jq -r '.expired_at' <<< "${entry}")"
      expiry_ordinal="$(date --utc --date="${expiry}" +%s 2>/dev/null || echo "")"
      if [[ -z "${expiry_ordinal}" ]]; then
        fail "the entry for ${advisory} carries expired_at ${expiry}, which is not a date"
      elif (( expiry_ordinal < today_ordinal )); then
        fail "the entry for ${advisory} expired on ${expiry} and today is ${today}, so ${package}@${version} is unaccepted again"
      else
        verdict="accepted until ${expiry}"
        note "  ${advisory} (${severity}) accepted until ${expiry}"
      fi
      accepted_seen="${accepted_seen}${advisory}"$'\n'
    fi

    report_advisories="$(jq --argjson advisories "${report_advisories}" --arg id "${advisory}" \
      --arg package "${package}" --arg version "${version}" --arg severity "${severity}" \
      --arg verdict "${verdict}" --null-input \
      '$advisories + [{id: $id, package: $package, version: $version, severity: $severity,
        verdict: $verdict}]')"
  done <<< "${advisories}"
done <<< "$(jq -r '.pins[] | [.package, .ecosystem, .version] | @tsv' "${MANIFEST}")"

# Step 3. An accepted entry the database no longer reports excuses nothing, and an entry nobody
# reviews is how an exception file becomes decoration.
while IFS= read -r advisory; do
  [[ -z "${advisory}" ]] && continue
  if ! grep -qxF -- "${advisory}" <<< "${accepted_seen}"; then
    fail "${MANIFEST_NAME} accepts ${advisory} and no pinned version reports it, so the entry excuses nothing and belongs in neither the file nor the review"
  fi
done <<< "$(jq -r '.accepted[].id' "${MANIFEST}" | sort -u)"

# Step 4. The control probe. A detector that answers nothing for a version known to carry
# advisories would report every pin above as clean.
control_package="$(jq -r '.control.package' "${MANIFEST}")"
control_ecosystem="$(jq -r '.control.ecosystem' "${MANIFEST}")"
control_version="$(jq -r '.control.version' "${MANIFEST}")"
control_minimum="$(jq -r '.control.minimum_advisories' "${MANIFEST}")"
control_response="$(query_advisories "${control_package}" "${control_ecosystem}" "${control_version}")"
control_count="$(jq -r '[.vulns[]? | select(has("withdrawn") | not)] | length' <<< "${control_response}")"
note "control ${control_package}@${control_version}: ${control_count} advisory record(s), minimum ${control_minimum}"
if (( control_count < control_minimum )); then
  fail "the database reported ${control_count} advisories for the control ${control_package}@${control_version}, which carries at least ${control_minimum}. The verdict above examined nothing"
fi

mkdir -p "$(dirname -- "${REPORT}")"
jq --null-input --arg endpoint "${ADVISORY_ENDPOINT}" --arg resolved "${today}" \
  --argjson pins "${report_pins}" --argjson advisories "${report_advisories}" \
  --argjson control "$(jq --null-input --arg package "${control_package}" \
    --arg version "${control_version}" --argjson reported "${control_count}" \
    --argjson minimum "${control_minimum}" \
    '{package: $package, version: $version, reported: $reported, minimum: $minimum}')" \
  --argjson failures "${failures}" \
  '{endpoint: $endpoint, resolved_on: $resolved, pins: $pins, advisories: $advisories,
    control: $control, failures: $failures}' > "${REPORT}"
note "wrote ${REPORT_PATH}"

if [[ "${mode}" == "print" ]]; then
  jq '.' "${REPORT}"
  exit 0
fi

if (( failures > 0 )); then
  printf '%d finding(s). Accept an advisory with a dated entry in %s, or raise the pin the deck loads, which Rule 4 fixes and card-platform/docs/suggested-next-tasks.md carries as a task.\n' \
    "${failures}" "${MANIFEST_NAME}" >&2
  exit 1
fi

note "every content-delivery pin is clean or carries an unexpired acceptance"
exit 0
