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
from typing import Any, Callable, Iterable, Iterator

__all__ = [
    "NeutralizeCsvCell",
    "SafeCsvWriter",
    "AtomicWritePath",
    "AtomicVersionedWritePath",
    "ReserveVersionedGeneration",
    "SecureDirectory",
    "SECURE_DIR_MODE",
    "SECURE_FILE_MODE",
]


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

# Owner-only permission modes for batch outputs (QA finding M13). Batch jobs emit
# files that can contain sensitive material (full PANs in a restore-capable
# backup, customer statements, transaction detail), so both the containing
# directory and every published file are restricted to the owning user rather
# than left at the process umask default (which is commonly world- or
# group-readable). SECURE_DIR_MODE (0700) grants the owner rwx and nobody else;
# SECURE_FILE_MODE (0600) grants the owner rw and nobody else.
SECURE_DIR_MODE = 0o700
SECURE_FILE_MODE = 0o600

# Upper bound on consecutive generation-claim attempts made by
# AtomicVersionedWritePath before it fails loudly. Each attempt reserves one
# candidate generation number via an atomic O_EXCL create; a collision (another
# concurrent writer already reserved that number) advances to the next number.
# The ceiling is far larger than any realistic number of concurrent backup
# writers, so it never limits legitimate use, yet it guarantees the claim loop
# can never spin forever on a pathological directory.
MAX_GENERATION_CLAIM_ATTEMPTS = 10000


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


def SecureDirectory(directoryPath: str | Path, mode: int = SECURE_DIR_MODE) -> Path:
    """Create (if needed) and lock down an output directory to owner-only access.

    Ensures the batch output directory exists and is restricted to the owning
    user (QA finding M13). Missing parents are created; the leaf directory is
    then ``chmod``-ed to ``mode`` (default :data:`SECURE_DIR_MODE`, ``0700``) so
    that the sensitive files written into it (restore-capable backups, customer
    statements, transaction detail) are never exposed through a world- or
    group-readable directory left at the process umask default. Only the leaf
    directory's permissions are tightened; pre-existing parent directories are
    left untouched so shared roots (for example a test ``tmp_path`` or a mounted
    output volume) are not silently narrowed.

    Args:
        directoryPath: The directory to create and secure.
        mode: The permission bits to apply to the leaf directory. Defaults to
            :data:`SECURE_DIR_MODE` (owner rwx only).

    Returns:
        The resolved directory as a :class:`~pathlib.Path`.

    Raises:
        OSError: If the directory cannot be created or its mode cannot be set.
    """
    resolvedDirectory = Path(directoryPath)
    resolvedDirectory.mkdir(parents=True, exist_ok=True)
    os.chmod(resolvedDirectory, mode)
    return resolvedDirectory


@contextmanager
def AtomicWritePath(
    finalPath: str | Path, mode: int = SECURE_FILE_MODE
) -> Iterator[Path]:
    """Yield a temporary path that is atomically promoted to ``finalPath``.

    The caller writes its output to the yielded temporary path (a sibling of
    ``finalPath`` in the same directory, guaranteeing a same-filesystem rename).
    On clean completion the temporary file is restricted to ``mode`` and
    atomically moved onto ``finalPath`` with :func:`os.replace`; on any exception
    the temporary file is removed and the exception is re-raised, so ``finalPath``
    never holds a partially written file. Any pre-existing file at ``finalPath``
    is left untouched when the write fails.

    The published file is ``chmod``-ed to ``mode`` (default
    :data:`SECURE_FILE_MODE`, ``0600``) *before* the atomic rename (QA finding
    M13). Because :func:`os.replace` moves the staged inode onto the destination,
    the destination inherits this owner-only mode regardless of the process
    umask, so a sensitive batch output is never briefly world-readable.

    Args:
        finalPath: The destination path the output should ultimately occupy.
        mode: The permission bits to apply to the published file. Defaults to
            :data:`SECURE_FILE_MODE` (owner rw only).

    Yields:
        The temporary :class:`~pathlib.Path` to write to.

    Raises:
        OSError: Propagated unchanged if writing or the final rename fails; the
            temporary file is removed first.
    """
    destinationPath = Path(finalPath)
    temporaryName = f".{destinationPath.name}.{os.getpid()}.{uuid.uuid4().hex}{TEMP_FILE_SUFFIX}"
    temporaryPath = destinationPath.parent / temporaryName
    # No exception is caught here (Ochs "specific exceptions only", QA M-31):
    # `bodyRaised` gates a `finally`-based cleanup, so the staged temp file is
    # removed whenever the caller's write block fails for ANY reason (including
    # KeyboardInterrupt / SystemExit / GeneratorExit) while the original, specific
    # exception propagates untouched. Only a clean write reaches the publish step.
    bodyRaised = True
    try:
        yield temporaryPath
        bodyRaised = False
    finally:
        if bodyRaised:
            _RemoveTemporaryFile(temporaryPath)
    # Restrict the finished file to owner-only access before publishing it, so
    # the atomically renamed destination is never exposed at the umask default.
    os.chmod(temporaryPath, mode)
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


def _ReserveVersionedPath(
    candidatePathFor: Callable[[int], Path],
    startNumber: int,
    maxAttempts: int,
) -> tuple[Path, int, int]:
    """Atomically reserve the first free versioned path at or after ``startNumber``.

    Starting at ``startNumber`` the candidate destination path for each
    generation number ``n`` is computed by ``candidatePathFor(n)`` and an attempt
    is made to create it with ``O_CREAT | O_EXCL | O_WRONLY``. That flag
    combination is an atomic, all-or-nothing filesystem operation: exactly one of
    any number of concurrent processes can succeed for a given path; every other
    process receives :class:`FileExistsError` and advances to the next number.
    The first number whose file is created successfully is the reservation.

    Args:
        candidatePathFor: Maps a generation number to its destination
            :class:`~pathlib.Path`.
        startNumber: The first generation number to attempt.
        maxAttempts: Upper bound on consecutive attempts before failing loudly.

    Returns:
        A ``(reservedPath, reservedNumber, openFileDescriptor)`` tuple. The caller
        owns ``openFileDescriptor`` and must close it.

    Raises:
        OSError: If no free generation can be reserved within ``maxAttempts``
            attempts, or if a create attempt fails for any reason other than the
            candidate path already existing.
    """
    candidateNumber = startNumber
    for _ in range(maxAttempts):
        candidatePath = candidatePathFor(candidateNumber)
        try:
            fileDescriptor = os.open(
                candidatePath, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600
            )
        except FileExistsError:
            # Another concurrent writer already reserved this generation number.
            # Advance to the next candidate and try again -- this is the entire
            # point of the O_EXCL claim, so collisions are expected, not errors.
            candidateNumber += 1
            continue
        return candidatePath, candidateNumber, fileDescriptor
    raise OSError(
        f"Could not reserve a free output generation after {maxAttempts} "
        f"attempts starting at generation {startNumber}"
    )


@contextmanager
def AtomicVersionedWritePath(
    candidatePathFor: Callable[[int], Path],
    startNumber: int,
    maxAttempts: int = MAX_GENERATION_CLAIM_ATTEMPTS,
    mode: int = SECURE_FILE_MODE,
) -> Iterator[tuple[Path, int]]:
    """Race-safely reserve the next free versioned path, then publish atomically.

    This is the concurrency-hardened counterpart to :func:`AtomicWritePath` for
    *versioned* (generation-numbered) outputs such as transaction backups. It
    closes the scan-then-write time-of-check/time-of-use race in which two
    concurrent writers both computed the same "highest generation plus one" and
    then silently overwrote one another's file (QA finding F-4, AAP 0.7.5 GDG
    ``(+1)`` semantics: a new generation must never overwrite a prior one).

    Starting at ``startNumber`` (typically the highest existing generation plus
    one, supplied as a fast starting hint), the first free generation is
    RESERVED by creating its destination file with ``O_CREAT | O_EXCL`` -- an
    atomic operation that at most one process can win for a given number, so
    concurrent writers are guaranteed to receive *distinct* generation numbers.
    The reserved (initially empty) file also marks the number as taken so other
    concurrent writers scanning the directory skip past it.

    The caller writes its content to the yielded temporary sibling path. On clean
    completion that temporary file is atomically :func:`os.replace`-d onto the
    reserved path, so publication is crash-safe (a mid-write failure never leaves
    a truncated file at the destination). On any exception both the temporary
    file and the reserved (empty) destination file are removed and the exception
    is re-raised, so a failed run leaves neither a partial file nor a stray empty
    reservation behind.

    Args:
        candidatePathFor: Maps a generation number to its destination
            :class:`~pathlib.Path`.
        startNumber: The first generation number to attempt.
        maxAttempts: Upper bound on consecutive claim attempts before failing
            loudly (defaults to :data:`MAX_GENERATION_CLAIM_ATTEMPTS`), so a
            pathological directory can never cause an infinite loop.
        mode: The permission bits applied to the published file *before* the
            atomic rename (default :data:`SECURE_FILE_MODE`, ``0600`` -- owner
            rw only, QA finding M13). Because :func:`os.replace` promotes the
            staged inode onto the reserved destination, the destination inherits
            this owner-only mode regardless of the process umask.

    Yields:
        A ``(temporaryPath, reservedNumber)`` tuple. Write output to
        ``temporaryPath``; ``reservedNumber`` is the generation number that was
        atomically claimed for this write.

    Raises:
        OSError: Propagated unchanged if no generation can be reserved, if
            writing fails, or if the final rename fails; the temporary file and
            the reserved destination are removed first.
    """
    reservedPath, reservedNumber, reservedFd = _ReserveVersionedPath(
        candidatePathFor, startNumber, maxAttempts
    )
    # The reservation only needs to exist on disk as a marker; content is written
    # through the temporary sibling and promoted with os.replace, so the reserved
    # descriptor itself is not used for writing and is closed immediately.
    os.close(reservedFd)
    temporaryName = f".{reservedPath.name}.{os.getpid()}.{uuid.uuid4().hex}{TEMP_FILE_SUFFIX}"
    temporaryPath = reservedPath.parent / temporaryName
    try:
        yield temporaryPath, reservedNumber
    except BaseException:
        _RemoveTemporaryFile(temporaryPath)
        _RemoveTemporaryFile(reservedPath)
        raise
    # Publish owner-only (0600) before the rename so full-PAN backup data is
    # never group- or world-readable (QA finding M13); os.replace moves the
    # staged inode onto the reserved destination, which inherits this mode.
    os.chmod(temporaryPath, mode)
    os.replace(temporaryPath, reservedPath)


@contextmanager
def ReserveVersionedGeneration(
    candidatePathFor: Callable[[int], Path],
    startNumber: int,
    maxAttempts: int = MAX_GENERATION_CLAIM_ATTEMPTS,
) -> Iterator[int]:
    """Atomically reserve a unique generation NUMBER for a MULTI-FILE run.

    This is the run-level counterpart to :func:`AtomicVersionedWritePath`. Where
    that helper reserves and publishes ONE versioned file, a job such as
    statement generation writes MANY files that must all share ONE run
    generation (one run == one GDG generation). This context manager reserves
    just the generation NUMBER -- not a single output file -- so every file the
    run subsequently writes can embed the reserved number.

    It closes the same scan-then-write time-of-check/time-of-use race that
    :func:`AtomicVersionedWritePath` closes (QA finding F-4 / I8, AAP 0.7.5 GDG
    ``(+1)`` semantics: a new generation must never overwrite a prior one), using
    the IDENTICAL atomic primitive: starting at ``startNumber`` the first free
    generation is claimed by creating a marker file with ``O_CREAT | O_EXCL``
    (shared :func:`_ReserveVersionedPath`). Because at most one process can win
    that create for a given number, any set of concurrent runs is guaranteed to
    receive DISTINCT generation numbers, and each is handed the next free one.

    Marker lifetime: the marker's sole job is to serialize concurrent
    generation-number claims for the lifetime of the run. Once the run's own
    published artifacts exist on disk (or, on failure, any partial artifacts),
    THOSE record the number for future ``highest generation + 1`` scans, so the
    marker is removed on exit -- on BOTH success and failure -- to keep the
    output directory free of empty reservation stubs. Distinctness across
    concurrent runs is preserved because both markers are held simultaneously
    for the entire window in which their generations overlap; a run that has
    released its marker has already published (or abandoned) its generation, so
    a later run's scan advances past it.

    Args:
        candidatePathFor: Maps a generation number to the marker
            :class:`~pathlib.Path` used to claim it. The marker name MUST NOT
            collide with the run's real output file names.
        startNumber: The first generation number to attempt (typically the
            highest existing generation plus one, a fast starting hint).
        maxAttempts: Upper bound on consecutive claim attempts before failing
            loudly (defaults to :data:`MAX_GENERATION_CLAIM_ATTEMPTS`), so a
            pathological directory can never cause an infinite loop.

    Yields:
        The generation number that was atomically reserved for this run.

    Raises:
        OSError: Propagated unchanged if no generation can be reserved within
            ``maxAttempts`` attempts (or a create fails for any reason other than
            the candidate already existing); the marker is removed first.
    """
    reservedPath, reservedNumber, reservedFd = _ReserveVersionedPath(
        candidatePathFor, startNumber, maxAttempts
    )
    # The marker only needs to EXIST on disk as an atomic claim; nothing is
    # written through it, so the reserved descriptor is closed immediately.
    os.close(reservedFd)
    try:
        yield reservedNumber
    finally:
        # Remove the marker on BOTH success and failure: the run's published
        # (or partial) files now carry the number for future scans, so the empty
        # marker stub is no longer needed. Best-effort unlink never masks an
        # in-flight exception (see _RemoveTemporaryFile).
        _RemoveTemporaryFile(reservedPath)

