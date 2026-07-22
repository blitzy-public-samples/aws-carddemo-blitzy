# Cross-cutting output-safety utilities for the batch reporting jobs (CardDemo).
# This module has no single legacy COBOL/JCL origin; it centralizes two hardening
# concerns that every file-producing batch job shares:
#   * CSV formula (spreadsheet) injection neutralization -- CWE-1236 -- so that
#     free-text fields (transaction/merchant/customer descriptions, report
#     labels) that begin with a spreadsheet formula trigger character cannot be
#     interpreted as a live formula when the exported CSV is opened in Excel,
#     LibreOffice Calc, or Google Sheets. This satisfies the Ochs Rule #3
#     directive to "sanitize/validate all data ... to prevent injection".
#   * Atomic file publication -- a mid-write I/O failure (for example ENOSPC)
#     must never leave a truncated, partially written file at the job's final
#     output path. Writes are staged to a temporary sibling and atomically
#     promoted with os.replace only on clean completion.
"""Shared output-safety helpers for the batch reporting jobs.

The reporting jobs (:mod:`batch.jobs.statement_gen`,
:mod:`batch.jobs.backup_tran`, and :mod:`batch.jobs.tran_detail_report`) all
serialize database-sourced text into files that are later opened by end users.
Two defensive concerns are common to every one of them and are implemented once
here so the behavior is identical across jobs:

CSV formula-injection neutralization (CWE-1236):
    A CSV cell whose first character is one of the spreadsheet formula trigger
    characters (``=``, ``+``, ``-``, ``@``, TAB, or CR) is treated as a live
    formula by common spreadsheet applications. Any such cell that is not a
    plain numeric literal is prefixed with a single apostrophe, the widely used
    mitigation that forces the spreadsheet to render the value as literal text.
    Legitimate numeric values -- crucially including negative monetary amounts
    such as ``-919.00`` that begin with ``-`` -- are recognized as numeric
    literals and are left byte-for-byte unchanged, preserving the exact decimal
    fidelity the AAP requires (AAP 0.7.1).

Atomic file publication:
    :class:`AtomicWritePath` yields a temporary path in the same directory as
    the requested destination. On clean completion it atomically renames the
    temporary file onto the destination with :func:`os.replace`; on any
    exception it removes the temporary file and re-raises, so the destination
    path is never left holding a partially written file. Because the temporary
    file is a sibling on the same filesystem, the rename is atomic.

Coding conventions:
    Per the Ochs Rule, public callables use PascalCase and local variables use
    camelCase; module constants use ALL_UPPERCASE. The
    :class:`SafeCsvWriter` methods ``writerow``/``writerows`` intentionally keep
    the lowercase :class:`csv.writer` names because the class is a drop-in
    replacement for a :class:`csv.writer` and must satisfy that established
    external interface (and the ``_RowSink`` protocol in
    :mod:`batch.jobs.tran_detail_report`).
"""

from __future__ import annotations

import os
import re
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterable, Iterator

__all__ = ["NeutralizeCsvCell", "SafeCsvWriter", "AtomicWritePath"]


# ---------------------------------------------------------------------------
# Constants (ALL_UPPERCASE per the Ochs Rule)
# ---------------------------------------------------------------------------

# Leading characters that cause a spreadsheet application to interpret a CSV
# cell as a formula. TAB (0x09) and CR (0x0D) are included because leading
# whitespace control characters can also trigger formula evaluation in some
# spreadsheet importers (the OWASP CSV-injection trigger set).
FORMULA_TRIGGER_CHARS = ("=", "+", "-", "@", "\t", "\r")

# A cell matching this pattern is a plain (optionally signed) decimal number and
# is never neutralized, so exact monetary values -- including negative amounts
# that begin with ``-`` -- pass through unchanged (AAP 0.7.1).
NUMERIC_LITERAL_PATTERN = re.compile(r"^[+-]?\d+(\.\d+)?$")

# The mitigation prefix. A leading apostrophe forces spreadsheet applications to
# treat the remainder of the cell as literal text rather than a formula.
CSV_INJECTION_PREFIX = "'"

# Filename marker for the in-progress temporary file used by AtomicWritePath.
TEMP_FILE_SUFFIX = ".tmp"


def _IsNumericLiteral(text: str) -> bool:
    """Report whether ``text`` is a plain, optionally signed decimal number.

    Args:
        text: The already-stringified cell value.

    Returns:
        ``True`` if the whole string is a signed/unsigned integer or fixed-point
        decimal (for example ``100``, ``100.00``, ``-919.00``, ``+5``); ``False``
        otherwise.
    """
    return NUMERIC_LITERAL_PATTERN.match(text) is not None


def NeutralizeCsvCell(cell: Any) -> Any:
    """Return a CSV-injection-safe version of a single cell value.

    A cell is neutralized only when its first character is a formula trigger
    character *and* the cell is not a plain numeric literal. Numeric values
    (including negative amounts that begin with ``-``) and any value that does
    not begin with a trigger character are returned unchanged, so legitimate
    output -- especially exact decimal amounts -- is preserved byte-for-byte.

    Args:
        cell: The raw cell value about to be written. ``None`` is normalized to
            an empty string (matching the empty field a :class:`csv.writer`
            would otherwise emit for ``None``); any non-string value is
            evaluated by its ``str`` form but returned unchanged when it does not
            require neutralization, so the writer coerces it exactly as before.

    Returns:
        The neutralized string when the cell must be protected, ``""`` for
        ``None``, or the original ``cell`` value otherwise.
    """
    if cell is None:
        return ""
    cellText = cell if isinstance(cell, str) else str(cell)
    if not cellText:
        return cell
    leadingChar = cellText[0]
    if leadingChar in FORMULA_TRIGGER_CHARS and not _IsNumericLiteral(cellText):
        return CSV_INJECTION_PREFIX + cellText
    return cell


class SafeCsvWriter:
    """A :class:`csv.writer` wrapper that neutralizes CSV formula injection.

    The class delegates all serialization to a wrapped ``csv.writer`` (or any
    object exposing ``writerow``) after passing every cell through
    :func:`NeutralizeCsvCell`. It is a drop-in replacement wherever a
    ``csv.writer`` is used by the reporting jobs, and it satisfies the
    ``_RowSink`` protocol consumed by :mod:`batch.jobs.tran_detail_report`.

    The ``writerow``/``writerows`` method names deliberately mirror the
    lowercase :class:`csv.writer` interface (an external contract), rather than
    the Ochs PascalCase convention used for the project's own methods.
    """

    def __init__(self, delegate: Any) -> None:
        """Wrap a row-consuming delegate.

        Args:
            delegate: The underlying sink to write neutralized rows to. Any
                object exposing a ``writerow`` method (typically the result of
                :func:`csv.writer`) is accepted.
        """
        self._delegate = delegate

    def writerow(self, row: Iterable[Any]) -> object:
        """Neutralize every cell in ``row`` and write it to the delegate.

        Args:
            row: The iterable of cell values to write.

        Returns:
            Whatever the delegate's ``writerow`` returns.
        """
        neutralizedRow = [NeutralizeCsvCell(cell) for cell in row]
        return self._delegate.writerow(neutralizedRow)

    def writerows(self, rows: Iterable[Iterable[Any]]) -> None:
        """Write every row in ``rows`` through :meth:`writerow`.

        Args:
            rows: An iterable of rows, each an iterable of cell values.
        """
        for row in rows:
            self.writerow(row)


@contextmanager
def AtomicWritePath(finalPath: str | Path) -> Iterator[Path]:
    """Yield a temporary path that is atomically promoted to ``finalPath``.

    The caller writes its output to the yielded temporary path (a sibling of
    ``finalPath`` in the same directory, guaranteeing a same-filesystem rename).
    On clean completion the temporary file is atomically moved onto
    ``finalPath`` with :func:`os.replace`; on any exception the temporary file
    is removed and the exception is re-raised, so ``finalPath`` never holds a
    partially written file. Any pre-existing file at ``finalPath`` is left
    untouched when the write fails.

    Args:
        finalPath: The destination path the output should ultimately occupy.

    Yields:
        The temporary :class:`~pathlib.Path` to write to.

    Raises:
        OSError: Propagated unchanged if writing or the final rename fails; the
            temporary file is removed first.
    """
    destinationPath = Path(finalPath)
    temporaryName = f".{destinationPath.name}.{os.getpid()}.{uuid.uuid4().hex}{TEMP_FILE_SUFFIX}"
    temporaryPath = destinationPath.parent / temporaryName
    try:
        yield temporaryPath
    except BaseException:
        _RemoveTemporaryFile(temporaryPath)
        raise
    os.replace(temporaryPath, destinationPath)


def _RemoveTemporaryFile(temporaryPath: Path) -> None:
    """Best-effort removal of a staged temporary file during cleanup.

    Any failure to remove the temporary file is suppressed so that the original
    exception being handled by :func:`AtomicWritePath` is never masked.

    Args:
        temporaryPath: The temporary file to remove.
    """
    try:
        temporaryPath.unlink(missing_ok=True)
    except OSError:
        # The write already failed; a failure to clean up the temp file must not
        # replace the original, more meaningful exception. Leave the stale temp
        # file in place rather than raising from the cleanup path.
        pass
