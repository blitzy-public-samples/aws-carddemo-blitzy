# Unit tests for app.utils.csv_safety (CSV formula-injection neutralization).
# Traceability: QA finding M-07 (report CSV injection); mirrors the batch
# adversarial coverage in batch/tests/unit/test_output_safety.py so both trees
# are proven to behave identically for the same input.
"""Unit tests for the backend CSV output-safety helpers.

Proves the CWE-1236 (CSV formula injection) neutralizer that the transaction
report CSV export relies on (M-07): a free-text cell beginning with a
spreadsheet formula trigger character (``=`` ``+`` ``-`` ``@`` TAB CR) that is
not a plain numeric literal is prefixed with a single apostrophe, while numeric
values -- including negative monetary amounts that begin with ``-`` -- and
ordinary text pass through byte-for-byte unchanged (AAP 0.7.1 exact decimals).

These are pure, synchronous tests: they touch no database, no filesystem, and no
network. In-code identifiers follow the Ochs Rule (snake_case test functions,
camelCase locals, ALL_UPPERCASE module constants, four-space indentation, one
behavior asserted per test).
"""

import csv
import io

import pytest

from app.utils.csv_safety import (
    CSV_INJECTION_PREFIX,
    FORMULA_TRIGGER_CHARS,
    NeutralizeCsvCell,
    SafeCsvWriter,
)

# ---------------------------------------------------------------------------
# Adversarial formula payloads that MUST be neutralized (each begins with a
# formula trigger character and is not a plain number).
# ---------------------------------------------------------------------------
FORMULA_PAYLOADS = (
    "=1+2",
    "+1+2",
    "=SUM(A1:A9)",
    '=cmd|" /c calc"!A1',
    "@SUM(1)",
    "=HYPERLINK(\"http://evil\")",
)

# Ordinary text that must NOT be altered.
ORDINARY_TEXT = ("HELLO WORLD", "Coffee Shop #12", "2024-02-15-12.00.00.000000")

# Plain numeric literals that must pass through unchanged (including a negative
# monetary amount, which begins with the '-' trigger char but is a number).
NUMERIC_LITERALS = ("100", "100.00", "-919.00", "+5", "0")


# ===========================================================================
# NeutralizeCsvCell -- neutralization of formula-injection vectors.
# ===========================================================================


@pytest.mark.parametrize("payload", FORMULA_PAYLOADS)
def test_neutralize_prefixes_formula_leading_cells(payload):
    """A formula-leading, non-numeric cell is prefixed with a single quote."""
    neutralized = NeutralizeCsvCell(payload)
    assert neutralized == CSV_INJECTION_PREFIX + payload
    assert neutralized.startswith(CSV_INJECTION_PREFIX)


def test_neutralize_prefixes_tab_leading_cell():
    """A cell beginning with a TAB control character is neutralized."""
    assert NeutralizeCsvCell("\t=1+1") == CSV_INJECTION_PREFIX + "\t=1+1"


def test_neutralize_prefixes_carriage_return_leading_cell():
    """A cell beginning with a CR control character is neutralized."""
    assert NeutralizeCsvCell("\r=1+1") == CSV_INJECTION_PREFIX + "\r=1+1"


def test_neutralize_prefixes_lone_trigger_characters():
    """Each bare trigger character (not a number) is neutralized on its own."""
    assert NeutralizeCsvCell("-") == CSV_INJECTION_PREFIX + "-"
    assert NeutralizeCsvCell("@") == CSV_INJECTION_PREFIX + "@"
    assert NeutralizeCsvCell("=") == CSV_INJECTION_PREFIX + "="
    assert NeutralizeCsvCell("+") == CSV_INJECTION_PREFIX + "+"


def test_trigger_set_matches_owasp_set():
    """The trigger set is exactly the documented OWASP CSV-injection set."""
    assert FORMULA_TRIGGER_CHARS == ("=", "+", "-", "@", "\t", "\r")


# ===========================================================================
# NeutralizeCsvCell -- preservation of legitimate numeric and text values.
# ===========================================================================


@pytest.mark.parametrize("literal", NUMERIC_LITERALS)
def test_neutralize_preserves_numeric_literals(literal):
    """A plain numeric literal (incl. a negative amount) is left unchanged."""
    assert NeutralizeCsvCell(literal) == literal


@pytest.mark.parametrize("text", ORDINARY_TEXT)
def test_neutralize_leaves_ordinary_text_unchanged(text):
    """Ordinary text not beginning with a trigger character is unchanged."""
    assert NeutralizeCsvCell(text) == text


def test_neutralize_none_becomes_empty_string():
    """A ``None`` cell is normalized to an empty string (csv.writer parity)."""
    assert NeutralizeCsvCell(None) == ""


def test_neutralize_empty_string_unchanged():
    """An empty string is returned unchanged (nothing to neutralize)."""
    assert NeutralizeCsvCell("") == ""


def test_neutralize_non_string_int_preserved():
    """A non-string value that needs no neutralization is returned as-is."""
    assert NeutralizeCsvCell(42) == 42


# ===========================================================================
# SafeCsvWriter -- neutralization through the csv.writer drop-in.
# ===========================================================================


def _WriteOneRow(row):
    """Write ``row`` through a SafeCsvWriter over a StringIO and parse it back.

    Args:
        row: The iterable of cell values to serialize.

    Returns:
        The single parsed CSV row as a list of strings.
    """
    buffer = io.StringIO()
    writer = SafeCsvWriter(csv.writer(buffer))
    writer.writerow(row)
    buffer.seek(0)
    return next(csv.reader(buffer))


def test_safecsvwriter_neutralizes_formula_cell_in_output():
    """A formula cell is neutralized in the serialized CSV output."""
    parsedRow = _WriteOneRow(["0000000000000001", "=1+2", "100.00"])
    assert parsedRow == ["0000000000000001", "'=1+2", "100.00"]


def test_safecsvwriter_preserves_negative_amount_in_output():
    """A negative monetary amount survives serialization byte-for-byte."""
    parsedRow = _WriteOneRow(["0000000000000002", "REFUND", "-919.00"])
    assert parsedRow[2] == "-919.00"


def test_safecsvwriter_writerow_neutralizes_each_cell():
    """Every cell in a mixed row is individually evaluated and neutralized."""
    parsedRow = _WriteOneRow(["@evil", "ok", "+danger", "-12.50"])
    assert parsedRow == ["'@evil", "ok", "'+danger", "-12.50"]


def test_safecsvwriter_writerows_neutralizes_all_rows():
    """``writerows`` neutralizes every cell across multiple rows."""
    buffer = io.StringIO()
    writer = SafeCsvWriter(csv.writer(buffer))
    writer.writerows([["=a", "1"], ["b", "@c"]])
    buffer.seek(0)
    rows = list(csv.reader(buffer))
    assert rows == [["'=a", "1"], ["b", "'@c"]]


def test_safecsvwriter_delegates_and_returns_delegate_result():
    """``writerow`` forwards the neutralized row and returns the delegate value."""

    class _RecordingSink:
        """A minimal writerow sink that records rows and returns a sentinel."""

        def __init__(self):
            self.rows = []

        def writerow(self, row):
            self.rows.append(list(row))
            return len(self.rows)

    sink = _RecordingSink()
    writer = SafeCsvWriter(sink)
    returned = writer.writerow(["=1", "plain"])
    assert sink.rows == [["'=1", "plain"]]
    assert returned == 1
