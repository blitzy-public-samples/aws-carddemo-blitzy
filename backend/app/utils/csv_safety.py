# CSV formula-injection neutralization for the backend report CSV export.
# This module has no single legacy COBOL/JCL origin; it centralizes one
# hardening concern: CSV formula (spreadsheet) injection neutralization
# (CWE-1236), so that free-text fields (transaction type/category descriptions,
# transaction source, report labels) that begin with a spreadsheet formula
# trigger character cannot be interpreted as a live formula when the exported
# CSV is opened in Excel, LibreOffice Calc, or Google Sheets. This satisfies the
# Ochs Rule #3 directive to "sanitize/validate all data ... to prevent injection".
"""Shared CSV output-safety helpers for the backend transaction report.

The report service (:class:`app.services.report_service.ReportService`)
serializes database-sourced text into a CSV that end users download and open in
a spreadsheet application. This module provides the one defensive concern that
serialization needs, implemented once so the behavior is centralized and tested
in isolation.

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

Relationship to the batch tree:
    The top-level ``batch`` package ships an equivalent neutralizer in
    :mod:`batch.jobs.output_safety`. ``backend`` and ``batch`` are separate,
    independently installable top-level packages, so the backend cannot import
    the batch module; this module deliberately mirrors that neutralizer's
    trigger set, numeric-literal rule, and apostrophe mitigation so the two
    trees behave identically for the same input (the backend variant omits the
    batch-only atomic-file-publication helper, because the backend report CSV is
    produced in memory and streamed by the router rather than written to a file).

Coding conventions:
    Per the Ochs Rule, public callables use PascalCase and local variables use
    camelCase; module constants use ALL_UPPERCASE. The :class:`SafeCsvWriter`
    ``writerow`` / ``writerows`` method names intentionally keep the lowercase
    :class:`csv.writer` names because the class is a drop-in replacement for a
    :class:`csv.writer` and must satisfy that established external interface.
"""

from __future__ import annotations

import re
from typing import Any, Iterable

__all__ = ["NeutralizeCsvCell", "SafeCsvWriter"]


# ---------------------------------------------------------------------------
# Constants (ALL_UPPERCASE per the Ochs Rule)
# ---------------------------------------------------------------------------

# Leading characters that cause a spreadsheet application to interpret a CSV
# cell as a formula. TAB (0x09) and CR (0x0D) are included because leading
# whitespace control characters can also trigger formula evaluation in some
# spreadsheet importers (the OWASP CSV-injection trigger set). This set is kept
# identical to batch.jobs.output_safety.FORMULA_TRIGGER_CHARS.
FORMULA_TRIGGER_CHARS = ("=", "+", "-", "@", "\t", "\r")

# A cell matching this pattern is a plain (optionally signed) decimal number and
# is never neutralized, so exact monetary values -- including negative amounts
# that begin with ``-`` -- pass through unchanged (AAP 0.7.1).
NUMERIC_LITERAL_PATTERN = re.compile(r"^[+-]?\d+(\.\d+)?$")

# The mitigation prefix. A leading apostrophe forces spreadsheet applications to
# treat the remainder of the cell as literal text rather than a formula.
CSV_INJECTION_PREFIX = "'"


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
    ``csv.writer`` is used, so the report service can adopt it by wrapping its
    existing writer with no other change.

    The ``writerow`` / ``writerows`` method names deliberately mirror the
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
