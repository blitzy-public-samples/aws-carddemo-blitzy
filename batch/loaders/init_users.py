# Ported from IDCAMS/IEBGENER job app/jcl/DUSRSECJ.jcl (defines the USRSEC file
# from an in-stream 10-user seed); record layout app/cpy/CSUSR01Y.cpy
# (SEC-USER-DATA, RECLN 80); EBCDIC source app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS.
# Plaintext SEC-USR-PWD is hashed via app.core.security.HashPassword; never stored plaintext.
"""User-security seed loader for the CardDemo ``users`` table (EBCDIC + hashing).

This module is the Python port of the legacy mainframe job
``app/jcl/DUSRSECJ.jcl``, which defined the ``USRSEC`` file from a 10-user
in-stream seed and copied it into a VSAM KSDS keyed on ``SEC-USR-ID``. In the
modern stack the ``USRSEC`` record (``app/cpy/CSUSR01Y.cpy`` — ``SEC-USER-DATA``,
80-byte fixed length) becomes the :class:`~app.models.user.User` ORM row, and
this loader idempotently upserts those rows into the ``users`` table.

Two properties of the legacy artifact drive the implementation:

1.  **EBCDIC-only source.** Unlike the other CardDemo seed datasets, ``USRSEC``
    ships *only* as an EBCDIC dataset
    (``app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS``); there is no ASCII
    equivalent. The raw bytes are therefore decoded with the single-byte IBM
    US EBCDIC codec ``cp037`` before the fixed-width fields are sliced. Because
    ``cp037`` is single-byte, each decoded character index equals its original
    byte offset, so the copybook byte offsets map directly onto string slices.

2.  **Plaintext password must be hashed.** The legacy record stores an 8-byte
    plaintext password (``SEC-USR-PWD PIC X(08)``). Persisting plaintext is
    unacceptable in the target (AAP 0.1.1 / 0.7.7 and the Ochs "no hardcoding
    secrets" rule), so every decoded password is UPPERCASED (matching the
    sign-on credential policy -- ``auth_service.Login`` uppercases the submitted
    password per ``FUNCTION UPPER-CASE`` COSGN00C L135-136 -- and the
    admin-create path) and then run through
    :func:`~app.core.security.HashPassword`; only the resulting bcrypt
    ``password_hash`` is written (QA finding F2). This keeps every
    credential-write path consistent with the single sign-on read path, so no
    seeded account can be silently locked out by a lowercase letter in its
    password. The plaintext value is never stored, logged, or returned. The seed
    credentials are non-production values used solely to populate a demo
    database.

Record layout (``CSUSR01Y`` ``SEC-USER-DATA``, 80 bytes)::

    SEC-USR-ID     PIC X(08)  -> user_id       [0:8]
    SEC-USR-FNAME  PIC X(20)  -> first_name    [8:28]
    SEC-USR-LNAME  PIC X(20)  -> last_name     [28:48]
    SEC-USR-PWD    PIC X(08)  -> password_hash [48:56]  (plaintext -> hashed)
    SEC-USR-TYPE   PIC X(01)  -> user_type     [56:57]  ('A' admin / 'U' user)
    SEC-USR-FILLER PIC X(23)  -> dropped       [57:80]  (record padding)

Transaction ownership:
    The caller owns the transaction. :func:`InitializeUsers` operates against
    the ``session`` it is handed and never calls ``commit`` or ``rollback``, so
    the orchestrator (``batch.orchestration.batch_chain``) or the CLI
    (``batch.cli``) can compose it with other loaders in one atomic unit of
    work. The upsert uses PostgreSQL ``INSERT ... ON CONFLICT DO UPDATE`` keyed
    on ``user_id`` so re-running the loader is idempotent and never creates
    schema of its own.

Public API:
    * :func:`InitializeUsers` — decode the EBCDIC seed, hash passwords, and
      upsert into ``users``; returns the number of users processed. It is
      callable simply as ``InitializeUsers(session)`` (the orchestration/CLI
      contract); the optional ``usrsecPath`` keyword lets tests point at a
      fixture dataset.
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from sqlalchemy.dialects.postgresql import insert as PgInsert
from sqlalchemy.orm import Session

from app.models.user import User

# NOTE: ``app.core.security.HashPassword`` is imported lazily inside
# ``_ParseUserRecord`` (not at module load). Importing ``app.core.security`` at
# module scope would instantiate the backend ``app.core.config.settings``
# singleton, which fails fast when the backend-only ``SECRET_KEY`` is unset --
# re-coupling the entire batch package (and therefore ``python -m batch.cli
# --help``) to an environment variable that is irrelevant to batch data
# processing (QA finding #60). Deferring the import keeps the batch CLI and all
# non-user-seed jobs/loaders importable and runnable with only
# ``SYNC_DATABASE_URL``; the backend security module is loaded only when user
# rows are actually hashed. The single hashing implementation is still reused
# (not duplicated), preserving parity with the login service.

__all__ = ["InitializeUsers"]

# ---------------------------------------------------------------------------
# Fixed-record constants for the CSUSR01Y ``SEC-USER-DATA`` layout.
#
# The EBCDIC source lives under ``app/data/EBCDIC`` (NOT the ASCII folder) —
# ``USRSEC`` is EBCDIC-only. ``DEFAULT_USRSEC_PATH`` resolves the repository's
# packaged dataset relative to this module: init_users.py -> loaders -> batch ->
# repository root, then ``app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS``.
# ---------------------------------------------------------------------------
DEFAULT_USRSEC_PATH = (
    Path(__file__).resolve().parent.parent.parent
    / "app" / "data" / "EBCDIC" / "AWS.M2.CARDDEMO.USRSEC.PS"
)

# IBM US EBCDIC single-byte codec. Verified to decode this dataset cleanly
# (``cp037`` and ``cp500`` produce identical results here). Single-byte means
# decoded character offset == original byte offset, so copybook byte positions
# translate directly to string slices below.
EBCDIC_CODEC = "cp037"

# SEC-USER-DATA is a fixed 80-byte record (IDCAMS RECORDSIZE(80,80),
# DCB RECFM=FB LRECL=80 in DUSRSECJ.jcl).
USER_RECORD_LENGTH = 80

# Field slices (character offset == byte offset under the single-byte codec).
USER_ID_SLICE = slice(0, 8)
FIRST_NAME_SLICE = slice(8, 28)
LAST_NAME_SLICE = slice(28, 48)
PASSWORD_SLICE = slice(48, 56)
USER_TYPE_SLICE = slice(56, 57)


def _ResolveUsrsecPath(usrsecPath: Optional[Path]) -> Path:
    """Return the EBCDIC USRSEC path, defaulting to the repo EBCDIC dataset.

    Args:
        usrsecPath: An explicit path to a USRSEC image, or ``None`` to use the
            repository's packaged EBCDIC dataset (:data:`DEFAULT_USRSEC_PATH`).
            A non-``Path`` value (for example a ``str``) is coerced to ``Path``.

    Returns:
        The resolved :class:`~pathlib.Path` of the USRSEC dataset to read.
    """
    if usrsecPath is None:
        return DEFAULT_USRSEC_PATH
    return Path(usrsecPath)


def _DecodeUserRecords(rawBytes: bytes) -> list[str]:
    """Decode the fixed-length EBCDIC USRSEC image into 80-byte record strings.

    The raw dataset bytes are decoded once with the single-byte
    :data:`EBCDIC_CODEC` and then split into fixed 80-character records. A
    single-byte codec guarantees character offset equals byte offset, so the
    field slices remain byte-accurate.

    Args:
        rawBytes: The complete USRSEC dataset image, read in binary mode.

    Returns:
        The list of decoded fixed-width record strings, each
        :data:`USER_RECORD_LENGTH` characters long.

    Raises:
        ValueError: If the image length is not an exact multiple of
            :data:`USER_RECORD_LENGTH`, which would indicate a truncated or
            corrupt dataset.
    """
    if len(rawBytes) % USER_RECORD_LENGTH != 0:
        raise ValueError(
            f"USRSEC length {len(rawBytes)} is not a multiple of "
            f"{USER_RECORD_LENGTH}"
        )
    decodedText = rawBytes.decode(EBCDIC_CODEC)
    records = []
    for offset in range(0, len(decodedText), USER_RECORD_LENGTH):
        records.append(decodedText[offset:offset + USER_RECORD_LENGTH])
    return records


def _ParseUserRecord(record: str) -> dict[str, str]:
    """Map one ``SEC-USER-DATA`` record to ``users`` column values.

    Each fixed-width copybook field is sliced and stripped of its trailing
    space padding. The plaintext ``SEC-USR-PWD`` is hashed with
    :func:`~app.core.security.HashPassword`; the plaintext is used only as the
    input to the hash and is never placed in the returned mapping.

    Args:
        record: A single 80-character decoded USRSEC record.

    Returns:
        A mapping of ``users`` column names to values, keyed exactly as the ORM
        model columns: ``user_id``, ``first_name``, ``last_name``,
        ``password_hash`` (the bcrypt digest), and ``user_type``.
    """
    # Lazy import (see module-level note): loading app.core.security here rather
    # than at module scope keeps the batch CLI free of the backend SECRET_KEY
    # requirement until a password is actually hashed (QA finding #60).
    from app.core.security import HashPassword

    # F2: uppercase the plaintext before hashing so the seeded credential matches
    # the sign-on policy -- auth_service.Login always uppercases the submitted
    # password (FUNCTION UPPER-CASE, COSGN00C L135-136) before verifying, and the
    # admin-create path (user_admin_service) does the same. This is a no-op for
    # the all-uppercase legacy seed password ("PASSWORD") but keeps every
    # credential-write path (seed, admin-create, admin-update) consistent with
    # the single sign-on read path, so no seeded account can be silently locked
    # out by a lowercase letter in its password.
    plainPassword = record[PASSWORD_SLICE].strip().upper()
    return {
        "user_id": record[USER_ID_SLICE].strip(),
        "first_name": record[FIRST_NAME_SLICE].strip(),
        "last_name": record[LAST_NAME_SLICE].strip(),
        "password_hash": HashPassword(plainPassword),
        "user_type": record[USER_TYPE_SLICE].strip(),
    }


def _UpsertUsers(session: Session, rowValues: list[dict[str, str]]) -> None:
    """Idempotently upsert user rows keyed on ``user_id`` (caller owns the txn).

    Emits a single PostgreSQL ``INSERT ... ON CONFLICT (user_id) DO UPDATE``
    statement so that re-running the seed refreshes existing rows in place
    rather than failing on the primary key. The conflict target and the set of
    updated columns are derived from the :class:`~app.models.user.User` table
    metadata, so the statement stays correct if the model evolves.

    Args:
        session: The open synchronous session to execute against. This function
            does not commit or roll back — the caller owns the transaction.
        rowValues: The list of column-value mappings produced by
            :func:`_ParseUserRecord`.
    """
    statement = PgInsert(User).values(rowValues)
    primaryKeyColumns = [
        column.name for column in User.__table__.primary_key.columns
    ]
    updateColumns = {
        column.name: statement.excluded[column.name]
        for column in User.__table__.columns
        if not column.primary_key
    }
    statement = statement.on_conflict_do_update(
        index_elements=primaryKeyColumns,
        set_=updateColumns,
    )
    session.execute(statement)


def InitializeUsers(session: Session, usrsecPath: Optional[Path] = None) -> int:
    """Decode the EBCDIC USRSEC seed, hash passwords, and upsert into ``users``.

    Modern replacement for ``app/jcl/DUSRSECJ.jcl``: instead of copying the
    plaintext user-security records into a VSAM KSDS, it decodes the EBCDIC
    dataset, hashes each password, and idempotently upserts the rows into the
    ``users`` table. Blank (all-space) records are skipped. The caller owns the
    transaction; this function never commits or rolls back.

    Args:
        session: An open synchronous SQLAlchemy session supplied by the
            orchestrator or CLI. The function reads and writes through it but
            leaves the transaction boundary to the caller.
        usrsecPath: Optional path to the USRSEC EBCDIC image. Defaults to
            ``None``, which resolves to the repository's packaged dataset
            (:data:`DEFAULT_USRSEC_PATH`); tests may pass a fixture path. The
            default keeps the callable compatible with ``InitializeUsers(session)``.

    Returns:
        The number of user rows processed (decoded, hashed, and upserted).

    Raises:
        FileNotFoundError: If the resolved USRSEC dataset does not exist.
        ValueError: If the dataset length is not a multiple of the 80-byte
            record length (propagated from :func:`_DecodeUserRecords`).
    """
    seedPath = _ResolveUsrsecPath(usrsecPath)
    if not seedPath.is_file():
        raise FileNotFoundError(f"USRSEC seed file not found: {seedPath}")
    rawBytes = seedPath.read_bytes()
    records = _DecodeUserRecords(rawBytes)
    rowValues = []
    for record in records:
        if not record.strip():
            continue
        rowValues.append(_ParseUserRecord(record))
    if rowValues:
        _UpsertUsers(session, rowValues)
    return len(rowValues)
