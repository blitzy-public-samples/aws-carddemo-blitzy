"""Unit tests for batch.jobs.output_safety (CSV formula-injection + atomic writes).

Covers the two hardening concerns shared by the file-producing batch jobs:

* CSV formula-injection neutralization (CWE-1236, Ochs Rule #3) -- cells that
  begin with a spreadsheet formula trigger character are apostrophe-prefixed,
  while plain numeric literals (including negative monetary amounts such as the
  real seed value ``-919.00`` from app/data/ASCII/dailytran.txt) are preserved
  byte-for-byte so exact Decimal fidelity is never corrupted (AAP 0.7.1).
* Atomic file publication -- a mid-write failure never leaves a partial file at
  the final output path, and any pre-existing file is preserved on failure.

DB-free, synchronous, stdlib only.
"""

import csv
import io
from decimal import Decimal

import pytest

from batch.jobs.output_safety import (
    AtomicWritePath,
    NeutralizeCsvCell,
    SafeCsvWriter,
)


# ---------------------------------------------------------------------------
# NeutralizeCsvCell -- neutralization of formula-injection vectors
# ---------------------------------------------------------------------------

# The exact attacker payloads called out in the QA finding F-3 reproduction.
FORMULA_INJECTION_VECTORS = [
    "=1+2",
    "@SUM(A1)",
    "+3+4",
    "=cmd|calc",
    '=HYPERLINK("http://evil.example/x","click")',
    "=2+5+cmd|' /C calc'!A0",
]


@pytest.mark.parametrize("payload", FORMULA_INJECTION_VECTORS)
def test_neutralize_prefixes_formula_leading_cells(payload):
    neutralized = NeutralizeCsvCell(payload)
    assert neutralized == "'" + payload
    assert neutralized.startswith("'")


def test_neutralize_prefixes_tab_leading_cell():
    # A leading TAB (0x09) is a formula trigger in some spreadsheet importers.
    assert NeutralizeCsvCell("\t=1+1") == "'\t=1+1"


def test_neutralize_prefixes_carriage_return_leading_cell():
    # A leading CR (0x0D) is likewise a trigger character.
    assert NeutralizeCsvCell("\r=1+1") == "'\r=1+1"


def test_neutralize_prefixes_lone_trigger_characters():
    # A lone trigger character is not a numeric literal, so it is neutralized.
    assert NeutralizeCsvCell("-") == "'-"
    assert NeutralizeCsvCell("@") == "'@"
    assert NeutralizeCsvCell("=") == "'="
    assert NeutralizeCsvCell("+") == "'+"


# ---------------------------------------------------------------------------
# NeutralizeCsvCell -- preservation of legitimate numeric + text values
# ---------------------------------------------------------------------------

# Numeric literals that begin with a trigger character must be preserved so that
# exact monetary amounts (crucially negative credits) are never corrupted.
NUMERIC_LITERALS_PRESERVED = [
    "-919.00",  # real negative seed amount (dailytran.txt row2, a credit)
    "-50.00",
    "-0.01",
    "+5",
    "+100.00",
    "100.00",
    "0",
    "1234567890",
]


@pytest.mark.parametrize("literal", NUMERIC_LITERALS_PRESERVED)
def test_neutralize_preserves_numeric_literals(literal):
    assert NeutralizeCsvCell(literal) == literal


def test_neutralize_leaves_ordinary_text_unchanged():
    # Text that does not begin with a trigger character is returned unchanged.
    assert NeutralizeCsvCell("HELLO WORLD") == "HELLO WORLD"
    assert NeutralizeCsvCell("2023-06-01-12.00.00.000000") == "2023-06-01-12.00.00.000000"
    assert NeutralizeCsvCell("Coffee Shop #12") == "Coffee Shop #12"


def test_neutralize_none_becomes_empty_string():
    assert NeutralizeCsvCell(None) == ""


def test_neutralize_empty_string_unchanged():
    assert NeutralizeCsvCell("") == ""


def test_neutralize_preserves_non_string_decimal_object():
    # A Decimal that does not require neutralization is returned unchanged (the
    # original object), so the csv.writer coerces it exactly as it always did and
    # exact scale is preserved.
    positiveAmount = Decimal("100.00")
    assert NeutralizeCsvCell(positiveAmount) is positiveAmount
    # A negative Decimal is a numeric literal by its string form and is preserved.
    negativeAmount = Decimal("-919.00")
    assert NeutralizeCsvCell(negativeAmount) is negativeAmount


def test_neutralize_non_string_int_preserved():
    assert NeutralizeCsvCell(42) == 42


# ---------------------------------------------------------------------------
# SafeCsvWriter -- neutralization through a real csv.writer
# ---------------------------------------------------------------------------


def _WriteRowsToText(rows):
    """Serialize rows through SafeCsvWriter wrapping a real csv.writer.

    Args:
        rows: The rows (each an iterable of cells) to write.

    Returns:
        The produced CSV text.
    """
    buffer = io.StringIO()
    writer = SafeCsvWriter(csv.writer(buffer))
    writer.writerows(rows)
    return buffer.getvalue()


def test_safecsvwriter_neutralizes_formula_cell_in_output():
    csvText = _WriteRowsToText([["0000000000000001", "=1+2", "100.00"]])
    parsedRow = next(csv.reader(csvText.splitlines()))
    assert parsedRow == ["0000000000000001", "'=1+2", "100.00"]


def test_safecsvwriter_preserves_negative_amount_in_output():
    # The negative amount survives a full write -> parse round trip unchanged.
    csvText = _WriteRowsToText([["TRAN", "A CREDIT", "-919.00"]])
    parsedRow = next(csv.reader(csvText.splitlines()))
    assert parsedRow[2] == "-919.00"


def test_safecsvwriter_writerow_neutralizes_each_cell():
    buffer = io.StringIO()
    writer = SafeCsvWriter(csv.writer(buffer))
    writer.writerow(["@evil", "ok", "+danger", "-12.50"])
    parsedRow = next(csv.reader(buffer.getvalue().splitlines()))
    assert parsedRow == ["'@evil", "ok", "'+danger", "-12.50"]


class _RecordingSink:
    """A minimal writerow sink that records the rows it receives."""

    def __init__(self):
        self.rows = []

    def writerow(self, row):
        self.rows.append(list(row))
        return len(row)


def test_safecsvwriter_delegates_and_returns_delegate_result():
    sink = _RecordingSink()
    writer = SafeCsvWriter(sink)
    returned = writer.writerow(["=1", "plain"])
    assert sink.rows == [["'=1", "plain"]]
    assert returned == 2


# ---------------------------------------------------------------------------
# AtomicWritePath -- atomic publication and failure cleanup
# ---------------------------------------------------------------------------


def test_atomic_write_promotes_temp_on_success(tmp_path):
    finalPath = tmp_path / "report.csv"
    with AtomicWritePath(finalPath) as stagingPath:
        # Mid-write, the final path does not yet exist; content lives in the temp.
        assert stagingPath != finalPath
        stagingPath.write_text("row1\nrow2\n", encoding="utf-8")
        assert not finalPath.exists()
    assert finalPath.read_text(encoding="utf-8") == "row1\nrow2\n"
    # No temporary sibling is left behind after a clean promotion.
    assert list(tmp_path.glob(".*.tmp")) == []


def test_atomic_write_removes_temp_and_leaves_no_final_on_failure(tmp_path):
    finalPath = tmp_path / "report.csv"
    with pytest.raises(OSError) as excInfo:
        with AtomicWritePath(finalPath) as stagingPath:
            stagingPath.write_text("partial", encoding="utf-8")
            raise OSError(28, "No space left on device")  # ENOSPC mid-write
    assert excInfo.value.errno == 28
    # No partial file at the final path and no leftover temporary sibling.
    assert not finalPath.exists()
    assert list(tmp_path.glob(".*.tmp")) == []


def test_atomic_write_preserves_preexisting_final_on_failure(tmp_path):
    finalPath = tmp_path / "report.csv"
    finalPath.write_text("PREVIOUS GOOD OUTPUT", encoding="utf-8")
    with pytest.raises(OSError):
        with AtomicWritePath(finalPath) as stagingPath:
            stagingPath.write_text("half written new output", encoding="utf-8")
            raise OSError(28, "No space left on device")
    # The prior good file is intact -- never truncated or replaced by a partial.
    assert finalPath.read_text(encoding="utf-8") == "PREVIOUS GOOD OUTPUT"
    assert list(tmp_path.glob(".*.tmp")) == []


def test_atomic_write_temp_is_sibling_in_same_directory(tmp_path):
    finalPath = tmp_path / "sub" / "report.csv"
    finalPath.parent.mkdir(parents=True)
    with AtomicWritePath(finalPath) as stagingPath:
        # Same directory guarantees a same-filesystem (atomic) os.replace.
        assert stagingPath.parent == finalPath.parent
        stagingPath.write_text("x", encoding="utf-8")
    assert finalPath.exists()
