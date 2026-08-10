#!/usr/bin/env bash
#
# Redacts cardholder data out of the test reports this build produces, then refuses to finish
# while any of it survives.
#
# WHY THIS EXISTS
#
# A test report is an artifact. .github/workflows/ci.yml uploads every Surefire and Failsafe
# report and keeps it for REPORT_RETENTION_DAYS, so whatever a test wrote to standard output
# during a run is readable by everyone who can read the run for the next fortnight. That is the
# whole of CWE-532: nobody chose to publish the value, and it is published.
#
# A review found one instance and the shape of the problem behind it. A test that provokes a
# check-constraint violation on the card table produced a log line carrying the server's own
# DETAIL, and PostgreSQL puts the entire failing row in that detail — card number, verification
# value and token together. The platform closes that at its source now, by turning the
# framework's failed-statement logger off in all six services, and this script closes it at the
# artifact: a leak from a source nobody has thought of yet is still caught before the upload.
#
# WHAT COUNTS AS CARDHOLDER DATA HERE
#
# The seeded values themselves, read out of the fixtures at scan time rather than written down.
# app/data/ASCII/carddata.txt and app/data/ASCII/cardxref.txt supply fifty card numbers,
# carddata.txt supplies fifty verification values and app/data/ASCII/custdata.txt supplies fifty
# social security numbers. No value appears in this file, which is the point: a script that
# carried the list would be the leak it looks for.
#
# A shape-based rule is deliberately not used for card numbers. A transaction identifier on this
# platform is sixteen digits, so "any sixteen-digit run" fires on ordinary traffic and a check
# that fires on everything gets switched off. The one shape rule that is applied is the failing-row
# detail, because that phrase is never legitimate in a report.
#
# WHAT IT DOES
#
#   1. Rewrites every matching report in place. A card number becomes its masked form, the twelve
#      leading digits replaced by asterisks, which is the form every external surface of this
#      platform already uses. A social security number keeps its last four digits and nothing
#      else. A failing-row detail becomes a fixed sentence.
#   2. Reads every report again and fails when any seeded value survived, naming the file and the
#      kind of value rather than the value.
#
# USAGE
#
#   scripts/redact-report-artifacts.sh              # redact, then verify. Run before an upload.
#   scripts/redact-report-artifacts.sh --check      # verify only, change nothing.
#
# Run it from card-platform/ or from the repository root. Exit 0 when the reports carry none of
# it, 1 when something survives, 2 when the tree is not one this script can read.

set -o errexit
set -o nounset
set -o pipefail

readonly MASK_CHARACTER='*'
readonly VISIBLE_CARD_DIGITS=4
readonly VISIBLE_SOCIAL_DIGITS=4
readonly FAILING_ROW_PHRASE='Failing row contains'
# The replacement must not itself carry the phrase, or the verification pass below reads its
# own work as a survivor.
readonly FAILING_ROW_REPLACEMENT='Failing row redacted by scripts/redact-report-artifacts.sh'

mode="redact"
if [ "$#" -gt 0 ]; then
  case "$1" in
    --check) mode="check" ;;
    *)
      printf 'usage: %s [--check]\n' "$0" >&2
      exit 2
      ;;
  esac
fi

# Resolve the repository root from this script's own location, so the caller's directory does not
# matter and no path below depends on it.
script_directory="$(cd "$(dirname "$0")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${script_directory}/../.." && pwd)"
readonly PLATFORM_ROOT="${REPOSITORY_ROOT}/card-platform"
readonly FIXTURE_ROOT="${REPOSITORY_ROOT}/app/data/ASCII"

for fixture in carddata.txt cardxref.txt custdata.txt; do
  if [ ! -r "${FIXTURE_ROOT}/${fixture}" ]; then
    printf 'cannot read %s. Run this from the repository that ships the CardDemo fixtures.\n' \
      "${FIXTURE_ROOT}/${fixture}" >&2
    exit 2
  fi
done

if [ ! -d "${PLATFORM_ROOT}" ]; then
  printf 'cannot read %s\n' "${PLATFORM_ROOT}" >&2
  exit 2
fi

python3 - "${mode}" "${REPOSITORY_ROOT}" "${MASK_CHARACTER}" "${VISIBLE_CARD_DIGITS}" \
  "${VISIBLE_SOCIAL_DIGITS}" "${FAILING_ROW_PHRASE}" "${FAILING_ROW_REPLACEMENT}" <<'PYTHON'
import pathlib
import re
import sys

mode, root, mask_character, visible_card, visible_social, phrase, replacement = sys.argv[1:8]
root = pathlib.Path(root)
visible_card = int(visible_card)
visible_social = int(visible_social)
fixtures = root / "app" / "data" / "ASCII"

# Offsets of app/cpy/CVACT02Y.cpy: CARD-NUM at 0 width 16, CARD-CVV-CD at 27 width 3.
CARD_NUMBER_WIDTH = 16
VERIFICATION_OFFSET = 27
VERIFICATION_WIDTH = 3
# Offset of CUST-SSN inside app/cpy/CVCUS01Y.cpy, summed from the members ahead of it.
SOCIAL_OFFSET = 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15
SOCIAL_WIDTH = 9


def slices(fixture, offset, width):
    """Returns the distinct values one fixture carries at one offset."""
    values = set()
    for record in (fixtures / fixture).read_text(errors="replace").splitlines():
        if len(record) >= offset + width:
            value = record[offset:offset + width]
            if value.isdigit():
                values.add(value)
    return values


card_numbers = slices("carddata.txt", 0, CARD_NUMBER_WIDTH) | slices(
    "cardxref.txt", 0, CARD_NUMBER_WIDTH)
verification_values = slices("carddata.txt", VERIFICATION_OFFSET, VERIFICATION_WIDTH)
social_numbers = slices("custdata.txt", SOCIAL_OFFSET, SOCIAL_WIDTH)

if not card_numbers or not social_numbers:
    print("the fixtures yielded no seeded value, so this scan would pass on anything",
          file=sys.stderr)
    raise SystemExit(2)


def masked(value, visible):
    """Returns one value with every digit but its last few replaced."""
    return mask_character * (len(value) - visible) + value[-visible:]


# A verification value is three digits and matches far too much on its own, so it is only
# redacted where the failing-row detail of a card table put it beside the number it belongs to.
substitutions = [(number, masked(number, visible_card)) for number in sorted(card_numbers)]
substitutions += [(number, masked(number, visible_social)) for number in sorted(social_numbers)]

reports = sorted(
    path for pattern in ("surefire-reports", "failsafe-reports")
    for path in (root / "card-platform").glob(f"**/target/{pattern}/**/*")
    if path.is_file())

if not reports:
    print("no Surefire or Failsafe report was found under card-platform/**/target.",
          "Run the build before this script.", file=sys.stderr)
    raise SystemExit(2)

rewritten = 0
if mode == "redact":
    for report in reports:
        try:
            text = report.read_text(errors="replace")
        except OSError:
            continue
        original = text
        for value, mask in substitutions:
            if value in text:
                text = text.replace(value, mask)
        if phrase in text:
            text = re.sub(re.escape(phrase) + r"[^\n]*", replacement, text)
        if text != original:
            report.write_text(text)
            rewritten += 1

failures = []
for report in reports:
    try:
        text = report.read_text(errors="replace")
    except OSError:
        continue
    relative = report.relative_to(root).as_posix()
    for kind, values in (("card number", card_numbers), ("social security number",
                                                         social_numbers)):
        present = sum(1 for value in values if value in text)
        if present:
            failures.append(f"{relative}: {present} seeded {kind}(s)")
    if phrase in text:
        failures.append(f"{relative}: a failing-row detail, which quotes a whole table row")
    for value in verification_values:
        if re.search(r"cvv[^0-9]{0,12}" + re.escape(value) + r"\b", text, re.IGNORECASE):
            failures.append(f"{relative}: a seeded verification value beside the word cvv")
            break

print(f"read {len(reports)} report file(s); rewrote {rewritten}")
if failures:
    print("cardholder data survived redaction:", file=sys.stderr)
    for failure in sorted(set(failures)):
        print("  FAIL:", failure, file=sys.stderr)
    print("No value is printed above on purpose. Find the test that wrote it and stop it "
          "writing it; do not widen this script.", file=sys.stderr)
    raise SystemExit(1)

print("No seeded card number, social security number or failing-row detail remains in the "
      "reports.")
PYTHON
