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
import threading
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal

import pytest

from batch.jobs.output_safety import (
    AtomicVersionedWritePath,
    AtomicWritePath,
    NeutralizeCsvCell,
    ReserveVersionedGeneration,
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


# ---------------------------------------------------------------------------
# AtomicVersionedWritePath -- race-safe generation claim + atomic publication
# (QA finding F-4: concurrent backups must never overwrite the same generation)
# ---------------------------------------------------------------------------


def _CandidateFactory(directory):
    """Build a candidate-path callable naming files ``gen_<NNNN>.csv``."""

    def _CandidatePathFor(generationNumber):
        return directory / f"gen_{generationNumber:04d}.csv"

    return _CandidatePathFor


def test_versioned_write_reserves_start_number_when_directory_empty(tmp_path):
    candidatePathFor = _CandidateFactory(tmp_path)
    with AtomicVersionedWritePath(candidatePathFor, 1) as (stagingPath, reserved):
        assert reserved == 1
        # The reservation exists on disk during the write so concurrent writers
        # skip past it, while the content still lives in the temporary sibling.
        assert candidatePathFor(1).exists()
        assert stagingPath != candidatePathFor(1)
        stagingPath.write_text("content-1\n", encoding="utf-8")
    assert candidatePathFor(1).read_text(encoding="utf-8") == "content-1\n"
    # Clean promotion leaves exactly one file and no temporary sibling behind.
    assert sorted(p.name for p in tmp_path.glob("gen_*.csv")) == ["gen_0001.csv"]
    assert list(tmp_path.glob(".*.tmp")) == []


def test_versioned_write_skips_already_taken_generations(tmp_path):
    candidatePathFor = _CandidateFactory(tmp_path)
    # Generations 1 and 2 already exist; a claim starting at 1 must advance to 3
    # rather than overwrite either prior generation.
    candidatePathFor(1).write_text("old-1", encoding="utf-8")
    candidatePathFor(2).write_text("old-2", encoding="utf-8")
    with AtomicVersionedWritePath(candidatePathFor, 1) as (stagingPath, reserved):
        assert reserved == 3
        stagingPath.write_text("new-3", encoding="utf-8")
    assert candidatePathFor(1).read_text(encoding="utf-8") == "old-1"
    assert candidatePathFor(2).read_text(encoding="utf-8") == "old-2"
    assert candidatePathFor(3).read_text(encoding="utf-8") == "new-3"


def test_versioned_write_removes_temp_and_reservation_on_failure(tmp_path):
    candidatePathFor = _CandidateFactory(tmp_path)
    with pytest.raises(OSError) as excInfo:
        with AtomicVersionedWritePath(candidatePathFor, 1) as (stagingPath, _reserved):
            stagingPath.write_text("partial", encoding="utf-8")
            raise OSError(28, "No space left on device")  # ENOSPC mid-write
    assert excInfo.value.errno == 28
    # Neither a partial file at the reserved path nor a stray empty reservation
    # nor a temporary sibling is left behind on failure.
    assert not candidatePathFor(1).exists()
    assert list(tmp_path.glob("gen_*.csv")) == []
    assert list(tmp_path.glob(".*.tmp")) == []


def test_versioned_write_raises_when_no_free_generation_within_max_attempts(tmp_path):
    candidatePathFor = _CandidateFactory(tmp_path)
    candidatePathFor(1).write_text("taken", encoding="utf-8")
    # Only one attempt is permitted and generation 1 is already taken, so the
    # claim fails loudly (never silently overwrites) rather than looping.
    with pytest.raises(OSError):
        with AtomicVersionedWritePath(candidatePathFor, 1, maxAttempts=1):
            pass  # pragma: no cover - body never runs; claim fails first
    # The pre-existing generation is untouched.
    assert candidatePathFor(1).read_text(encoding="utf-8") == "taken"


def test_versioned_write_concurrent_claims_are_all_distinct(tmp_path):
    """Directly prove the anti-F-4 property at the primitive level.

    Many threads race to claim generations from the same starting hint into the
    same directory. Because each reservation uses an atomic ``O_CREAT | O_EXCL``
    create, every worker MUST receive a distinct generation number and every
    worker's content MUST survive -- none may be silently overwritten.
    """
    candidatePathFor = _CandidateFactory(tmp_path)
    workerCount = 32
    startBarrier = threading.Barrier(workerCount)

    def _ClaimAndWrite(workerId):
        # Maximize contention: every worker starts from the same hint (1) at the
        # same instant, so they collide and must serialize via the O_EXCL claim.
        startBarrier.wait()
        with AtomicVersionedWritePath(candidatePathFor, 1) as (stagingPath, reserved):
            stagingPath.write_text(f"worker-{workerId}\n", encoding="utf-8")
        return reserved

    with ThreadPoolExecutor(max_workers=workerCount) as executor:
        reservedNumbers = list(executor.map(_ClaimAndWrite, range(workerCount)))

    # Every worker got a unique generation -- no two claimed the same number.
    assert len(set(reservedNumbers)) == workerCount
    # Exactly one file per worker survives on disk (nothing lost to overwrite).
    writtenFiles = sorted(tmp_path.glob("gen_*.csv"))
    assert len(writtenFiles) == workerCount
    # Every worker's content is present exactly once (no lost writes).
    contents = sorted(path.read_text(encoding="utf-8").strip() for path in writtenFiles)
    assert contents == sorted(f"worker-{workerId}" for workerId in range(workerCount))
    # No temporary siblings remain after all promotions complete.
    assert list(tmp_path.glob(".*.tmp")) == []


# ---------------------------------------------------------------------------
# ReserveVersionedGeneration -- atomic run-level generation NUMBER reservation
# for MULTI-FILE runs (QA finding I8: concurrent statement runs must never
# collide on one generation and overwrite each other's statements).
# ---------------------------------------------------------------------------


def _MarkerFactory(directory):
    """Build a candidate-marker callable naming markers ``gen_<NNNN>.reserved``."""

    def _MarkerPathFor(generationNumber):
        return directory / f"gen_{generationNumber:04d}.reserved"

    return _MarkerPathFor


def test_reserve_generation_yields_start_number_and_holds_marker(tmp_path):
    markerPathFor = _MarkerFactory(tmp_path)
    with ReserveVersionedGeneration(markerPathFor, 1) as reserved:
        assert reserved == 1
        # The marker exists on disk for the lifetime of the reservation so a
        # concurrent run scanning/claiming skips past it.
        assert markerPathFor(1).exists()
    # The marker is removed on clean exit -- the caller's published files (not the
    # empty marker) record the generation for future scans, so no stub remains.
    assert list(tmp_path.glob("gen_*.reserved")) == []


def test_reserve_generation_nested_reservations_are_distinct(tmp_path):
    """Two (or more) reservations HELD SIMULTANEOUSLY receive distinct numbers.

    This is the exact anti-I8 property: while two runs overlap (both markers
    held), the atomic O_EXCL claim guarantees they hold DIFFERENT generation
    numbers, so their per-run output files can never collide.
    """
    markerPathFor = _MarkerFactory(tmp_path)
    # ``first`` and ``second`` are held simultaneously via a single multi-context
    # ``with`` (Python enters them left-to-right, both live for the body); the
    # inner ``with third`` stays nested so its INDIVIDUAL release can be asserted.
    with (
        ReserveVersionedGeneration(markerPathFor, 1) as first,
        ReserveVersionedGeneration(markerPathFor, 1) as second,
    ):
        with ReserveVersionedGeneration(markerPathFor, 1) as third:
            assert {first, second, third} == {1, 2, 3}
            # All three markers coexist while their reservations overlap.
            assert markerPathFor(1).exists()
            assert markerPathFor(2).exists()
            assert markerPathFor(3).exists()
        # Innermost released -> its marker is gone; the outer two remain.
        assert not markerPathFor(3).exists()
        assert markerPathFor(1).exists()
        assert markerPathFor(2).exists()
    # All reservations released -> the directory is free of marker stubs.
    assert list(tmp_path.glob("gen_*.reserved")) == []


def test_reserve_generation_removes_marker_on_failure(tmp_path):
    markerPathFor = _MarkerFactory(tmp_path)
    with (
        pytest.raises(ValueError, match="boom"),
        ReserveVersionedGeneration(markerPathFor, 1) as reserved,
    ):
        assert reserved == 1
        assert markerPathFor(1).exists()
        raise ValueError("boom")  # e.g. a mid-run database error
    # The marker is removed even on failure so a retry can re-use the number when
    # no output was published under it (correct: nothing was produced).
    assert list(tmp_path.glob("gen_*.reserved")) == []


def test_reserve_generation_sequential_reuses_freed_number(tmp_path):
    """Distinctness holds only WHILE reservations overlap (by design).

    Two reservations that do NOT overlap (the first fully released before the
    second starts) both get generation 1 from the same start hint, because the
    marker's only job is to serialize CONCURRENT claims. Sequential monotonicity
    is the caller's responsibility: it derives the start hint from its own
    already-published files (see statement_gen._HighestExistingGeneration).
    """
    markerPathFor = _MarkerFactory(tmp_path)
    with ReserveVersionedGeneration(markerPathFor, 1) as first:
        assert first == 1
    with ReserveVersionedGeneration(markerPathFor, 1) as second:
        assert second == 1  # marker 1 was freed on the first exit -> reusable


def test_reserve_generation_concurrent_claims_are_all_distinct(tmp_path):
    """Many threads reserving from the same hint AT ONCE all get distinct numbers.

    Unlike the sequential test, every worker HOLDS its reservation until a shared
    release barrier fires, so all reservations overlap in time. The atomic
    O_CREAT|O_EXCL marker create then forces every worker onto a distinct
    generation number -- the runtime guarantee that closes I8.
    """
    markerPathFor = _MarkerFactory(tmp_path)
    workerCount = 32
    claimedBarrier = threading.Barrier(workerCount)
    releaseBarrier = threading.Barrier(workerCount)

    def _ReserveAndHold(_workerId):
        with ReserveVersionedGeneration(markerPathFor, 1) as reserved:
            # Wait until EVERY worker has claimed, so all markers are held
            # simultaneously and the reservations genuinely overlap.
            claimedBarrier.wait()
            # Every generation in [1, workerCount] must be reserved right now.
            assert markerPathFor(reserved).exists()
            releaseBarrier.wait()
            return reserved

    with ThreadPoolExecutor(max_workers=workerCount) as executor:
        reservedNumbers = list(executor.map(_ReserveAndHold, range(workerCount)))

    # Every worker received a UNIQUE generation -- the contiguous range 1..N.
    assert sorted(reservedNumbers) == list(range(1, workerCount + 1))
    # All markers are cleaned up once every reservation has been released.
    assert list(tmp_path.glob("gen_*.reserved")) == []

