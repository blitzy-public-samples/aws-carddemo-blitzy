#!/usr/bin/env bash
#
# Holds the executed-test figures this repository publishes against the reports a completed build
# wrote. Run it after `mvn verify`, from anywhere.
#
# WHY THIS SCRIPT EXISTS, AND WHY IT IS NOT A TEST
#
# docs/equivalence-results.md and README.md publish per-module and reactor test counts. A count is
# evidence, so a count nobody measures is a claim. The obvious place to check it is a test, and a
# test cannot: a test runs inside the build whose reports it would have to read, so the report for
# its own class does not exist yet and the integration phase of its own module has not started.
# Every in-build attempt at this ends up subtracting one published figure from another, which passes
# whenever both move together.
#
# This script runs after the build instead. Every report is complete by then, including the
# equivalence module's own, so each published figure is compared against a measured one and nothing
# is derived from anything else this repository wrote.
#
# WHAT IT MEASURES
#
# Reports are counted by their `testcase` elements, which is the number Maven prints in its
# per-module summary. Summing the `tests` attribute of `testsuite` instead under-reports, because the
# report for a class holding @Nested classes lists every nested case and counts only its own.
#
# WHAT IT REFUSES
#
#   * A module that declares the Failsafe plugin and holds a class the plugin selects, and wrote no
#     Failsafe report. That is the fail-open case: the plugin selected nothing, or was never bound,
#     and the build stayed green.
#   * A module that wrote no Surefire report at all.
#   * A published figure that disagrees with the measured one, naming both.
#
# EXIT STATUS
#
#   0  every required report is present and every published figure matches
#   1  a report is missing, or a published figure disagrees
#   2  the script was given arguments it does not understand, or cannot find the tree
#
# USAGE
#
#   scripts/check-published-test-counts.sh            check the published figures (default)
#   scripts/check-published-test-counts.sh --print    print the measured figures and check nothing
#
# The print mode is how the published figures are restated after a change: run the build, run this
# with --print, and copy the numbers into the two documents. Nothing regenerates them automatically,
# because a figure a script writes into a document is a figure no human has read.

set -o errexit
set -o nounset
set -o pipefail

readonly SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PLATFORM_ROOT="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly RESULTS_DOCUMENT="${PLATFORM_ROOT}/docs/equivalence-results.md"
readonly PLATFORM_README="${PLATFORM_ROOT}/README.md"

# The nine reactor modules, in the order card-platform/pom.xml declares them.
readonly MODULES=(
  "libs/event-contracts"
  "libs/cobol-compat"
  "services/authorization-service"
  "services/ledger-posting-service"
  "services/fraud-detection-service"
  "services/notification-service"
  "services/account-service"
  "services/card-service"
  "equivalence-tests"
)

# The module whose own figures the two documents publish separately.
readonly EQUIVALENCE_MODULE="equivalence-tests"

mode="check"
case "${1-}" in
  "") mode="check" ;;
  --print) mode="print" ;;
  --check) mode="check" ;;
  *)
    printf 'unknown argument: %s\n' "$1" >&2
    printf 'usage: %s [--check|--print]\n' "$0" >&2
    exit 2
    ;;
esac

if [[ ! -f "${PLATFORM_ROOT}/pom.xml" ]]; then
  printf 'cannot find the platform root: %s holds no pom.xml\n' "${PLATFORM_ROOT}" >&2
  exit 2
fi

failures=0

# Reports one failure and keeps going, so a run names every disagreement rather than the first.
fail() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}

# Counts the testcase elements under one report directory. Prints -1 when the directory is absent.
count_cases() {
  local directory="$1"
  if [[ ! -d "${directory}" ]]; then
    printf '%s\n' "-1"
    return 0
  fi
  local total
  total="$(grep -ho '<testcase ' "${directory}"/TEST-*.xml 2>/dev/null | wc -l | tr -d ' ')"
  printf '%s\n' "${total:-0}"
}

# Answers whether a module holds a source class the Failsafe patterns select.
holds_integration_classes() {
  local module="$1"
  local tests="${PLATFORM_ROOT}/${module}/src/test/java"
  [[ -d "${tests}" ]] || return 1
  find "${tests}" -type f \( -name '*IT.java' -o -name '*EquivalenceTest.java' \) \
    -print -quit 2>/dev/null | grep -q . || return 1
  return 0
}

# Answers whether a module declares the Failsafe plugin in its own descriptor.
declares_failsafe() {
  grep -q 'maven-failsafe-plugin' "${PLATFORM_ROOT}/$1/pom.xml"
}

# Reads one published figure out of a document, or prints nothing when the sentence is absent.
published_figure() {
  local document="$1"
  local pattern="$2"
  sed -nE "s/.*${pattern}.*/\\1/p" "${document}" | head -n 1 | tr -d ','
}

# Compares one published figure with one measured figure.
compare() {
  local label="$1"
  local published="$2"
  local measured="$3"
  local where="$4"
  if [[ -z "${published}" ]]; then
    fail "${where} publishes no ${label}. This script reads it from that document, so the sentence has to be present."
    return
  fi
  if [[ "${published}" != "${measured}" ]]; then
    fail "${label}: ${where} publishes ${published} and this build's reports carry ${measured}. Restate the figure; do not edit this script."
  fi
}

surefire_total=0
failsafe_total=0
declare -A module_surefire=()
declare -A module_failsafe=()

for module in "${MODULES[@]}"; do
  surefire="$(count_cases "${PLATFORM_ROOT}/${module}/target/surefire-reports")"
  failsafe="$(count_cases "${PLATFORM_ROOT}/${module}/target/failsafe-reports")"

  if [[ "${surefire}" == "-1" ]]; then
    fail "${module} wrote no Surefire report. Run 'mvn verify' over the whole reactor before this script."
    surefire=0
  elif [[ "${surefire}" == "0" ]]; then
    fail "${module} wrote an empty Surefire report directory, so its unit tests selected nothing."
  fi

  if declares_failsafe "${module}" && holds_integration_classes "${module}"; then
    if [[ "${failsafe}" == "-1" ]]; then
      fail "${module} declares the Failsafe plugin and holds a class its patterns select, and wrote no Failsafe report. This is the fail-open case the guard exists for."
      failsafe=0
    elif [[ "${failsafe}" == "0" ]]; then
      fail "${module} wrote an empty Failsafe report directory, so its integration tests selected nothing."
    fi
  elif [[ "${failsafe}" == "-1" ]]; then
    failsafe=0
  fi

  module_surefire["${module}"]="${surefire}"
  module_failsafe["${module}"]="${failsafe}"
  surefire_total=$((surefire_total + surefire))
  failsafe_total=$((failsafe_total + failsafe))
done

if [[ "${mode}" == "print" ]]; then
  printf '%-42s %10s %10s\n' "module" "surefire" "failsafe"
  for module in "${MODULES[@]}"; do
    printf '%-42s %10s %10s\n' "${module}" "${module_surefire[${module}]}" \
      "${module_failsafe[${module}]}"
  done
  printf '%-42s %10s %10s\n' "REACTOR TOTAL" "${surefire_total}" "${failsafe_total}"
  exit 0
fi

# The reactor sentence of docs/equivalence-results.md.
reactor_surefire="$(published_figure "${RESULTS_DOCUMENT}" \
  'The whole reactor ran ([0-9,]+) Surefire and [0-9,]+ Failsafe tests')"
reactor_failsafe="$(published_figure "${RESULTS_DOCUMENT}" \
  'The whole reactor ran [0-9,]+ Surefire and ([0-9,]+) Failsafe tests')"
compare "reactor Surefire total" "${reactor_surefire}" "${surefire_total}" \
  "docs/equivalence-results.md"
compare "reactor Failsafe total" "${reactor_failsafe}" "${failsafe_total}" \
  "docs/equivalence-results.md"

# The equivalence module's own two figures, published in the observed-run table.
module_unit="$(published_figure "${RESULTS_DOCUMENT}" \
  '\| Surefire unit and contract tests in the same module \| ([0-9,]+) passed')"
compare "equivalence-tests Surefire total" "${module_unit}" \
  "${module_surefire[${EQUIVALENCE_MODULE}]}" "docs/equivalence-results.md"

module_integration="$(published_figure "${RESULTS_DOCUMENT}" \
  'giving ([0-9,]+) for this module.s whole Failsafe run')"
compare "equivalence-tests Failsafe total" "${module_integration}" \
  "${module_failsafe[${EQUIVALENCE_MODULE}]}" "docs/equivalence-results.md"

# The per-module table of docs/equivalence-results.md. One row per reactor module, so a figure
# cannot be published for one module and measured for another.
for module in "${MODULES[@]}"; do
  row="$(grep -F "| \`${module}\` |" "${RESULTS_DOCUMENT}" | head -n 1 || true)"
  if [[ -z "${row}" ]]; then
    fail "docs/equivalence-results.md publishes no per-module row for ${module}. Every reactor module needs one, or a module's figures can go unmeasured."
    continue
  fi
  published_surefire="$(printf '%s' "${row}" | awk -F '|' '{gsub(/[ ,]/, "", $3); print $3}')"
  published_failsafe="$(printf '%s' "${row}" | awk -F '|' '{gsub(/[ ,]/, "", $4); print $4}')"
  compare "${module} Surefire count" "${published_surefire}" "${module_surefire[${module}]}" \
    "the per-module table of docs/equivalence-results.md"
  compare "${module} Failsafe count" "${published_failsafe}" "${module_failsafe[${module}]}" \
    "the per-module table of docs/equivalence-results.md"
done

# The platform guide repeats the equivalence module's two figures.
readme_equivalence="$(published_figure "${PLATFORM_README}" \
  'reports ([0-9,]+) Failsafe equivalence tests and [0-9,]+ unit or contract tests')"
readme_unit="$(published_figure "${PLATFORM_README}" \
  'reports [0-9,]+ Failsafe equivalence tests and ([0-9,]+) unit or contract tests')"
equivalence_only="$(count_cases \
  "${PLATFORM_ROOT}/${EQUIVALENCE_MODULE}/target/failsafe-reports")"
if [[ "${equivalence_only}" != "-1" ]]; then
  measured_equivalence="$(grep -ho '<testcase ' \
    "${PLATFORM_ROOT}/${EQUIVALENCE_MODULE}"/target/failsafe-reports/TEST-*EquivalenceTest.xml \
    2>/dev/null | wc -l | tr -d ' ')"
  compare "README equivalence-class Failsafe count" "${readme_equivalence}" \
    "${measured_equivalence}" "README.md"
fi
compare "README equivalence-tests Surefire count" "${readme_unit}" \
  "${module_surefire[${EQUIVALENCE_MODULE}]}" "README.md"

if (( failures > 0 )); then
  printf '\n%d published figure(s) or report(s) failed the check.\n' "${failures}" >&2
  exit 1
fi

printf 'Every published test count matches this build: %d Surefire and %d Failsafe cases across %d modules.\n' \
  "${surefire_total}" "${failsafe_total}" "${#MODULES[@]}"
