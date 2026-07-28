"""0002 seed data — load CardDemo reference/master data.

Seeds the 10 tables created by 0001 from app/data/ASCII/*.txt (primary) and the
EBCDIC-only app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS (users). Signed zoned-decimal
amounts are decoded to Decimal via app.utils.decimal_utils (NEVER float); dates and
timestamps via app.utils.date_utils; user passwords are bcrypt-hashed via
app.core.security.HashPassword (legacy plaintext is NEVER stored). Inserts are
idempotent (ON CONFLICT DO NOTHING) and FK-safe (parents before children).

Traceability (AAP 0.5.1, 0.7.1 signed zoned decimal, 0.7.7 EBCDIC user seed):
  transaction_type      <- app/data/ASCII/trantype.txt   (CVTRA03Y / TRANTYPE)
  transaction_category  <- app/data/ASCII/trancatg.txt   (CVTRA04Y / TRANCATG)
  disclosure_group      <- app/data/ASCII/discgrp.txt    (CVTRA02Y / DISCGRP)
  customers             <- app/data/ASCII/custdata.txt   (CVCUS01Y / CUSTDATA)
  accounts              <- app/data/ASCII/acctdata.txt   (CVACT01Y / ACCTDATA)
  tran_category_balance <- app/data/ASCII/tcatbal.txt    (CVTRA01Y / TCATBALF)
  users                 <- app/data/EBCDIC/USRSEC.PS     (CSUSR01Y / USRSEC)
  cards                 <- app/data/ASCII/carddata.txt   (CVACT02Y / CARDDATA)
  card_xref             <- app/data/ASCII/cardxref.txt   (CVACT03Y / CARDXREF)
  transactions          <- app/data/ASCII/dailytran.txt  (CVTRA06Y daily -> CVTRA05Y posted)

USER-SEED COORDINATION (AAP 0.7.7): the PRIMARY user loader is
batch/loaders/init_users.py, which also EBCDIC-decodes USRSEC and bcrypt-hashes the
credential. This migration and that loader are mutually idempotent through the
users.user_id primary key plus ON CONFLICT DO NOTHING: whichever runs first inserts
the 10 users and the other is a no-op for existing ids. Neither ever stores plaintext.

Revision ID: 0002
Revises: 0001
"""

from collections.abc import Sequence

import os
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import insert as PgInsert

from app.utils.decimal_utils import DecodeZonedDecimal
from app.utils.date_utils import ParseLegacyDate, ParseLegacyTimestamp
from app.core.security import HashPassword

# --- Alembic revision identifiers (documented framework names; kept lowercase/
# snake exactly as Alembic requires -- the Ochs PascalCase/camelCase rules do NOT
# apply to these). This revision chains AFTER the schema, so down_revision is
# "0001". The on-disk filename slug (0002_seed_data) agrees with revision "0002"
# per alembic.ini file_template = %(rev)s_%(slug)s, giving deterministic ordering.
revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# --- Module constants (Ochs ALL_UPPERCASE). No secret or DSN is hardcoded here;
# the database bind is obtained exclusively from op.get_bind() inside upgrade().
MONEY_SCALE = 2                       # implied V99 fractional digits on every amount
POSTED_STATUS = "POSTED"              # terminal posted-ledger status (set by POSTTRAN)
PENDING_STATUS = "PENDING"            # daily-staging status (CVTRA06Y input; QA C01/C02)
RECLEN_USER = 80                      # fixed record length of the EBCDIC USRSEC file
ENCODING_EBCDIC = "cp037"             # IBM EBCDIC code page for the USRSEC dataset
ENCODING_ASCII = "latin-1"            # byte-preserving decode for the ASCII datasets
USRSEC_FILENAME = "AWS.M2.CARDDEMO.USRSEC.PS"

# Optional environment overrides for the seed data directories (Ochs "no
# hardcoding": deployments may relocate the sample data without code changes).
ENV_ASCII_DIR = "CARDDEMO_ASCII_DIR"
ENV_EBCDIC_DIR = "CARDDEMO_EBCDIC_DIR"

# Repository-root data location resolved from this file's location. The file
# lives at backend/alembic/versions/0002_seed_data.py, so parents[3] is the repo
# root on a host checkout: versions (0) -> alembic (1) -> backend (2) -> root (3).
REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_ASCII_DIR = REPO_ROOT / "app" / "data" / "ASCII"
DEFAULT_EBCDIC_DIR = REPO_ROOT / "app" / "data" / "EBCDIC"

# In-container data location. The backend image's WORKDIR is /app, and the root
# docker-compose.yml mounts the repository's app/data at /app/data (read-only)
# and points CARDDEMO_ASCII_DIR / CARDDEMO_EBCDIC_DIR at it. This is listed as an
# explicit resolution candidate so a containerized `alembic upgrade head` never
# depends on this file's depth below the repo root -- the root cause of QA
# finding F3, where parents[3] resolved to "/" inside the ./backend image and the
# repo-root app/data was absent, so 0002 aborted and (transactional DDL) rolled
# 0001 back too, leaving zero tables.
CONTAINER_DATA_DIR = Path("/app/data")
CONTAINER_ASCII_DIR = CONTAINER_DATA_DIR / "ASCII"
CONTAINER_EBCDIC_DIR = CONTAINER_DATA_DIR / "EBCDIC"


def _ResolveSeedDir(envVar: str, candidateDirs: list[Path], label: str) -> Path:
    """Resolve a seed-data directory from an env override or known candidates.

    Resolution order (QA finding F3 -- the documented containerized migration must
    not depend on this file's depth below the repository root):

      1. If ``envVar`` is set it is authoritative: the directory it names is
         returned when it exists, and a clear error is raised when it does not (a
         misconfigured override must fail loudly rather than silently fall back).
      2. Otherwise the first EXISTING path in ``candidateDirs`` is returned. The
         candidate order covers both the host layout (repo-root ``app/data``) and
         the container layout (``/app/data`` mounted by docker-compose).
      3. If nothing resolves, the raised error lists every path that was tried so
         a first-time operator can see exactly where the data was expected.

    Args:
        envVar: Name of the optional environment override (for example
            ``CARDDEMO_ASCII_DIR``).
        candidateDirs: Ordered fallback directories to probe when no override is
            set; the first that exists wins.
        label: Human-readable dataset label used in the error message
            (``"ASCII"`` or ``"EBCDIC"``).

    Returns:
        The resolved, existing seed-data directory.

    Raises:
        RuntimeError: If the override is set but its directory is missing, or if
            none of the candidate directories exist.
    """
    overrideValue = os.environ.get(envVar)
    if overrideValue:
        overrideDir = Path(overrideValue)
        if overrideDir.is_dir():
            return overrideDir
        raise RuntimeError(
            f"{label} seed directory from {envVar} not found: {overrideDir}"
        )
    for candidateDir in candidateDirs:
        if candidateDir.is_dir():
            return candidateDir
    triedPaths = ", ".join(str(candidateDir) for candidateDir in candidateDirs)
    raise RuntimeError(
        f"{label} seed directory not found (set {envVar} or provide one of: {triedPaths})"
    )


def LocateAsciiDir() -> Path:
    """Resolve the ASCII seed directory (env override or known candidates).

    Returns:
        The directory holding the app/data/ASCII/*.txt datasets, resolved via
        :func:`_ResolveSeedDir` from :data:`ENV_ASCII_DIR`, then
        :data:`DEFAULT_ASCII_DIR` (host), then :data:`CONTAINER_ASCII_DIR`.

    Raises:
        RuntimeError: If the override is set but missing, or no candidate exists.
    """
    return _ResolveSeedDir(ENV_ASCII_DIR, [DEFAULT_ASCII_DIR, CONTAINER_ASCII_DIR], "ASCII")


def LocateEbcdicDir() -> Path:
    """Resolve the EBCDIC seed directory (env override or known candidates).

    Returns:
        The directory holding the EBCDIC-only USRSEC dataset, resolved via
        :func:`_ResolveSeedDir` from :data:`ENV_EBCDIC_DIR`, then
        :data:`DEFAULT_EBCDIC_DIR` (host), then :data:`CONTAINER_EBCDIC_DIR`.

    Raises:
        RuntimeError: If the override is set but missing, or no candidate exists.
    """
    return _ResolveSeedDir(ENV_EBCDIC_DIR, [DEFAULT_EBCDIC_DIR, CONTAINER_EBCDIC_DIR], "EBCDIC")


# ---------------------------------------------------------------------------
# Generic fixed-width parsing helpers
#
# The legacy datasets are fixed-width: column positions carry meaning and blanks
# are significant, so records are split by byte offset and never tokenized on
# whitespace. Only the trailing line terminator is stripped; interior/leading/
# trailing spaces inside a record are preserved for correct slicing.
# ---------------------------------------------------------------------------


def ReadRecords(dataDir: Path, fileName: str) -> list[str]:
    """Read a fixed-width ASCII dataset into a list of raw records.

    Args:
        dataDir: The directory containing the dataset.
        fileName: The dataset file name (e.g. ``"acctdata.txt"``).

    Returns:
        The records with only their line terminator removed; a trailing empty
        line (if any) is dropped so record counts match the source exactly.

    Raises:
        RuntimeError: If the dataset file is missing (re-raised with the path).
    """
    filePath = dataDir / fileName
    try:
        with filePath.open("r", encoding=ENCODING_ASCII) as sourceFile:
            rawRecords = [line.rstrip("\n").rstrip("\r") for line in sourceFile]
    except FileNotFoundError as missingFile:
        raise RuntimeError(f"Seed dataset not found: {filePath}") from missingFile
    while rawRecords and rawRecords[-1].strip() == "":
        rawRecords.pop()
    return rawRecords


def CleanText(value: str) -> str | None:
    """Trim a NULLABLE text field, mapping an all-blank field to ``None``.

    Args:
        value: The raw fixed-width slice.

    Returns:
        The trimmed text, or ``None`` when the field is entirely blank.
    """
    stripped = value.strip()
    return stripped or None


def RequiredText(value: str) -> str:
    """Trim a NOT NULL text/code field.

    Args:
        value: The raw fixed-width slice.

    Returns:
        The trimmed text (these fields are single tokens in every dataset).
    """
    return value.strip()


def ParseOptionalDate(value: str) -> date | None:
    """Parse a NULLABLE X(10) date field via the shared legacy date parser.

    Args:
        value: The raw fixed-width slice (``YYYY-MM-DD`` or blank).

    Returns:
        The parsed :class:`datetime.date`, or ``None`` when the field is blank.
    """
    stripped = value.strip()
    if not stripped:
        return None
    return ParseLegacyDate(stripped)


def ParseOptionalTimestamp(value: str) -> datetime | None:
    """Parse a NULLABLE X(26) timestamp field via the shared legacy parser.

    Args:
        value: The raw fixed-width slice (``YYYY-MM-DD HH:MM:SS.ffffff`` or blank).

    Returns:
        The parsed :class:`datetime.datetime`, or ``None`` when blank.
    """
    stripped = value.strip()
    if not stripped:
        return None
    return ParseLegacyTimestamp(stripped)


def ParseOptionalInt(value: str) -> int | None:
    """Parse a NULLABLE integer field, failing fast on a non-numeric value.

    Args:
        value: The raw fixed-width slice.

    Returns:
        The integer value, or ``None`` when the field is blank.

    Raises:
        ValueError: If a non-blank field is not a valid integer (fail-fast).
    """
    stripped = value.strip()
    if not stripped:
        return None
    return int(stripped)


def DecodeMoney(rawSlice: str) -> Decimal:
    """Decode a signed zoned-decimal V99 money field into an exact ``Decimal``.

    Delegates to :func:`app.utils.decimal_utils.DecodeZonedDecimal` with the fixed
    :data:`MONEY_SCALE` so every amount decodes identically to the batch loaders
    and runtime code. The RAW slice is passed unchanged: leading bytes are
    zero-padded digits and the final byte carries the sign overpunch, so the field
    must never be stripped. Floating point is never used anywhere in this path.

    Args:
        rawSlice: The raw fixed-width monetary field (e.g. ``"00000001940{"``).

    Returns:
        The decoded amount as a :class:`decimal.Decimal` with scale 2.
    """
    return DecodeZonedDecimal(rawSlice, MONEY_SCALE)


# ---------------------------------------------------------------------------
# Per-dataset parsers (source file + originating copybook noted on each). Every
# parser returns list[dict] keyed by the destination column names declared in
# 0001_initial_schema.py, using the empirically verified fixed-width offsets.
# ---------------------------------------------------------------------------


def ParseTranTypes(asciiDir: Path) -> list[dict]:
    """Parse transaction_type rows from trantype.txt (CVTRA03Y / TRANTYPE).

    Layout (reclen 60): tran_type X(2), tran_type_desc X(50), FILLER X(8).
    """
    records = ReadRecords(asciiDir, "trantype.txt")
    return [
        {
            "tran_type": RequiredText(r[0:2]),
            "tran_type_desc": RequiredText(r[2:52]),
        }
        for r in records
    ]


def ParseTranCategories(asciiDir: Path) -> list[dict]:
    """Parse transaction_category rows from trancatg.txt (CVTRA04Y / TRANCATG).

    Layout (reclen 60): tran_type_cd X(2), tran_cat_cd 9(4), tran_cat_type_desc X(50).
    """
    records = ReadRecords(asciiDir, "trancatg.txt")
    return [
        {
            "tran_type_cd": RequiredText(r[0:2]),
            "tran_cat_cd": RequiredText(r[2:6]),
            "tran_cat_type_desc": RequiredText(r[6:56]),
        }
        for r in records
    ]


def ParseDisclosureGroups(asciiDir: Path) -> list[dict]:
    """Parse disclosure_group rows from discgrp.txt (CVTRA02Y / DISCGRP).

    Layout (reclen 50): group_id X(10), tran_type_cd X(2), tran_cat_cd 9(4),
    interest_rate S9(04)V99 (6-char zoned decimal -> NUMERIC(6, 2), AAP 0.7.2).
    """
    records = ReadRecords(asciiDir, "discgrp.txt")
    return [
        {
            "group_id": RequiredText(r[0:10]),
            "tran_type_cd": RequiredText(r[10:12]),
            "tran_cat_cd": RequiredText(r[12:16]),
            "interest_rate": DecodeZonedDecimal(r[16:22], MONEY_SCALE),
        }
        for r in records
    ]


def ParseTranCategoryBalances(asciiDir: Path) -> list[dict]:
    """Parse tran_category_balance rows from tcatbal.txt (CVTRA01Y / TCATBALF).

    Layout (reclen 50): acct_id 9(11), tran_type_cd X(2), tran_cat_cd 9(4),
    balance S9(09)V99 (11-char zoned decimal -> NUMERIC(11, 2)).
    """
    records = ReadRecords(asciiDir, "tcatbal.txt")
    return [
        {
            "acct_id": RequiredText(r[0:11]),
            "tran_type_cd": RequiredText(r[11:13]),
            "tran_cat_cd": RequiredText(r[13:17]),
            "balance": DecodeMoney(r[17:28]),
        }
        for r in records
    ]


def BuildCustomerRow(r: str) -> dict:
    """Map one custdata.txt record to a customers row dict (CVCUS01Y / CUSTDATA).

    Layout (reclen 500): id/name/address block then ssn, government id, date of
    birth X(10), EFT id, primary-holder indicator, fico_credit_score 9(3). ssn is
    SENSITIVE (stored in full; masked by the response layer, not here).
    """
    return {
        "cust_id": RequiredText(r[0:9]),
        "first_name": RequiredText(r[9:34]),
        "middle_name": CleanText(r[34:59]),
        "last_name": RequiredText(r[59:84]),
        "addr_line_1": RequiredText(r[84:134]),
        "addr_line_2": CleanText(r[134:184]),
        "addr_line_3": CleanText(r[184:234]),
        "addr_state_cd": CleanText(r[234:236]),
        "addr_country_cd": CleanText(r[236:239]),
        "addr_zip": CleanText(r[239:249]),
        "phone_num_1": CleanText(r[249:264]),
        "phone_num_2": CleanText(r[264:279]),
        "ssn": CleanText(r[279:288]),
        "govt_issued_id": CleanText(r[288:308]),
        "date_of_birth": ParseOptionalDate(r[308:318]),
        "eft_account_id": CleanText(r[318:328]),
        "pri_card_holder_ind": CleanText(r[328:329]),
        "fico_credit_score": ParseOptionalInt(r[329:332]),
    }


def ParseCustomers(asciiDir: Path) -> list[dict]:
    """Parse customers rows from custdata.txt (CVCUS01Y / CUSTDATA)."""
    records = ReadRecords(asciiDir, "custdata.txt")
    return [BuildCustomerRow(r) for r in records]


def BuildAccountRow(r: str) -> dict:
    """Map one acctdata.txt record to an accounts row dict (CVACT01Y / ACCTDATA).

    Layout (reclen 300): acct_id 9(11), active_status X(1), three S9(10)V99
    money fields, three X(10) dates, two S9(10)V99 cycle money fields, addr_zip
    X(10), group_id X(10). Every money field decodes to NUMERIC(12, 2) via Decimal.
    """
    return {
        "acct_id": RequiredText(r[0:11]),
        "active_status": RequiredText(r[11:12]),
        "curr_bal": DecodeMoney(r[12:24]),
        "credit_limit": DecodeMoney(r[24:36]),
        "cash_credit_limit": DecodeMoney(r[36:48]),
        "open_date": ParseOptionalDate(r[48:58]),
        "expiration_date": ParseOptionalDate(r[58:68]),
        "reissue_date": ParseOptionalDate(r[68:78]),
        "curr_cyc_credit": DecodeMoney(r[78:90]),
        "curr_cyc_debit": DecodeMoney(r[90:102]),
        "addr_zip": CleanText(r[102:112]),
        "group_id": CleanText(r[112:122]),
    }


def ParseAccounts(asciiDir: Path) -> list[dict]:
    """Parse accounts rows from acctdata.txt (CVACT01Y / ACCTDATA)."""
    records = ReadRecords(asciiDir, "acctdata.txt")
    return [BuildAccountRow(r) for r in records]


def ParseUsers(ebcdicDir: Path) -> list[dict]:
    """Parse users rows from the EBCDIC USRSEC dataset (CSUSR01Y / USRSEC).

    USRSEC exists ONLY in EBCDIC (no ASCII equivalent), so the fixed 80-byte
    records are read as raw bytes and decoded with IBM code page cp037. The legacy
    plaintext SEC-USR-PWD X(8) is bcrypt-hashed via HashPassword and ONLY the hash
    is stored -- the plaintext is never persisted, logged, or returned
    (AAP 0.1.1, 0.7.7; Ochs no-hardcoding rule).

    Layout (reclen 80): user_id X(8), first_name X(20), last_name X(20),
    password X(8), user_type X(1), FILLER X(23).

    Raises:
        RuntimeError: If the EBCDIC USRSEC dataset is missing.
    """
    usrsecPath = ebcdicDir / USRSEC_FILENAME
    try:
        rawBytes = usrsecPath.read_bytes()
    except FileNotFoundError as missingFile:
        raise RuntimeError(f"Seed dataset not found: {usrsecPath}") from missingFile
    userRows: list[dict] = []
    for offset in range(0, len(rawBytes), RECLEN_USER):
        recordBytes = rawBytes[offset:offset + RECLEN_USER]
        if len(recordBytes) < RECLEN_USER:
            continue
        rec = recordBytes.decode(ENCODING_EBCDIC)
        plainPassword = rec[48:56].strip()
        userRows.append(
            {
                "user_id": RequiredText(rec[0:8]),
                "first_name": RequiredText(rec[8:28]),
                "last_name": RequiredText(rec[28:48]),
                "password_hash": HashPassword(plainPassword),
                "user_type": RequiredText(rec[56:57]),
            }
        )
    return userRows


def ParseCards(asciiDir: Path) -> list[dict]:
    """Parse cards rows from carddata.txt (CVACT02Y / CARDDATA).

    Layout (reclen 150): card_num X(16), acct_id 9(11), cvv_cd 9(3),
    embossed_name X(50), expiration_date X(10), active_status X(1). The 3-byte
    CVV slice [27:30] is DELIBERATELY SKIPPED and never seeded (QA finding C-03,
    AAP 0.7.8): the CVV must never be persisted, so no cvv column is populated.
    """
    records = ReadRecords(asciiDir, "carddata.txt")
    return [
        {
            "card_num": RequiredText(r[0:16]),
            "acct_id": RequiredText(r[16:27]),
            # r[27:30] is the source CVV slice -- intentionally NOT read/seeded.
            "embossed_name": RequiredText(r[30:80]),
            "expiration_date": ParseOptionalDate(r[80:90]),
            "active_status": RequiredText(r[90:91]),
        }
        for r in records
    ]


def ParseCardXrefs(asciiDir: Path) -> list[dict]:
    """Parse card_xref rows from cardxref.txt (CVACT03Y / CARDXREF).

    Layout (reclen 36): xref_card_num X(16), cust_id 9(9), acct_id 9(11). The DB
    column is xref_card_num (the ORM card_num is a synonym with no DB column).
    """
    records = ReadRecords(asciiDir, "cardxref.txt")
    return [
        {
            "xref_card_num": RequiredText(r[0:16]),
            "cust_id": RequiredText(r[16:25]),
            "acct_id": RequiredText(r[25:36]),
        }
        for r in records
    ]


def BuildTransactionRow(r: str) -> dict:
    """Map one dailytran.txt record to a transactions row dict (CVTRA06Y daily).

    Layout (reclen 350): tran_id X(16), tran_type_cd X(2), tran_cat_cd 9(4),
    tran_source X(10), tran_desc X(100), tran_amt S9(09)V99 -> NUMERIC(11, 2),
    merchant block, card_num X(16), orig_ts/proc_ts X(26) -> TIMESTAMPTZ.

    Seeded status is PENDING (QA finding C02). ``dailytran.txt`` is the legacy
    CVTRA06Y *daily-transaction* file -- the INPUT to posting, not the posted
    ledger -- and every one of its 300 rows carries a BLANK ``proc_ts`` (the
    processing timestamp that the CBTRN02C/POSTTRAN posting job stamps only when
    it posts a row). A blank ``proc_ts`` therefore means "not yet posted", so the
    faithful seeded status is PENDING and ``proc_ts`` is left NULL (AAP 0.7.5 --
    dailytran modeled as a PENDING->POSTED staging construct; AAP 0.7.3 --
    posting stamps the processing timestamp). Marking these rows POSTED with a
    NULL ``proc_ts`` was incoherent (a posted transaction always has a processing
    timestamp) and bypassed the POSTTRAN lifecycle. The batch posting job
    transitions them to POSTED and stamps ``proc_ts``; ``0007`` repairs any
    database already seeded with the old POSTED value.
    """
    return {
        "tran_id": RequiredText(r[0:16]),
        "tran_type_cd": RequiredText(r[16:18]),
        "tran_cat_cd": RequiredText(r[18:22]),
        "tran_source": CleanText(r[22:32]),
        "tran_desc": CleanText(r[32:132]),
        "tran_amt": DecodeMoney(r[132:143]),
        "merchant_id": CleanText(r[143:152]),
        "merchant_name": CleanText(r[152:202]),
        "merchant_city": CleanText(r[202:252]),
        "merchant_zip": CleanText(r[252:262]),
        "card_num": RequiredText(r[262:278]),
        "orig_ts": ParseOptionalTimestamp(r[278:304]),
        # proc_ts is blank in every dailytran.txt row (not yet posted); left NULL
        # and stamped only by the posting job (QA C02, AAP 0.7.3/0.7.5).
        "proc_ts": ParseOptionalTimestamp(r[304:330]),
        "status": PENDING_STATUS,
    }


def ParseTransactions(asciiDir: Path) -> list[dict]:
    """Parse transactions rows from dailytran.txt (CVTRA06Y daily -> CVTRA05Y posted)."""
    records = ReadRecords(asciiDir, "dailytran.txt")
    return [BuildTransactionRow(r) for r in records]


# ---------------------------------------------------------------------------
# Typed column definitions (one builder per table). Each returns a FRESH list of
# lightweight sa.column() clauses whose types mirror 0001_initial_schema.py so
# that Decimal / date / datetime / int values bind with the correct SQL types.
# A fresh list is returned on every call because sa.table() assigns each column
# clause to its table, so a column object must not be shared across tables.
# ---------------------------------------------------------------------------


def TransactionTypeColumns() -> list:
    """Typed columns for transaction_type (mirrors 0001)."""
    return [
        sa.column("tran_type", sa.CHAR(2)),
        sa.column("tran_type_desc", sa.String(50)),
    ]


def TransactionCategoryColumns() -> list:
    """Typed columns for transaction_category (mirrors 0001)."""
    return [
        sa.column("tran_type_cd", sa.CHAR(2)),
        sa.column("tran_cat_cd", sa.String(4)),
        sa.column("tran_cat_type_desc", sa.String(50)),
    ]


def DisclosureGroupColumns() -> list:
    """Typed columns for disclosure_group (interest_rate NUMERIC(6, 2))."""
    return [
        sa.column("group_id", sa.String(10)),
        sa.column("tran_type_cd", sa.CHAR(2)),
        sa.column("tran_cat_cd", sa.String(4)),
        sa.column("interest_rate", sa.Numeric(6, 2)),
    ]


def CustomerColumns() -> list:
    """Typed columns for customers (mirrors 0001)."""
    return [
        sa.column("cust_id", sa.String(9)),
        sa.column("first_name", sa.String(25)),
        sa.column("middle_name", sa.String(25)),
        sa.column("last_name", sa.String(25)),
        sa.column("addr_line_1", sa.String(50)),
        sa.column("addr_line_2", sa.String(50)),
        sa.column("addr_line_3", sa.String(50)),
        sa.column("addr_state_cd", sa.CHAR(2)),
        sa.column("addr_country_cd", sa.CHAR(3)),
        sa.column("addr_zip", sa.String(10)),
        sa.column("phone_num_1", sa.String(15)),
        sa.column("phone_num_2", sa.String(15)),
        sa.column("ssn", sa.String(9)),
        sa.column("govt_issued_id", sa.String(20)),
        sa.column("date_of_birth", sa.Date()),
        sa.column("eft_account_id", sa.String(10)),
        sa.column("pri_card_holder_ind", sa.CHAR(1)),
        sa.column("fico_credit_score", sa.SmallInteger()),
    ]


def AccountColumns() -> list:
    """Typed columns for accounts (five NUMERIC(12, 2) money fields)."""
    return [
        sa.column("acct_id", sa.String(11)),
        sa.column("active_status", sa.CHAR(1)),
        sa.column("curr_bal", sa.Numeric(12, 2)),
        sa.column("credit_limit", sa.Numeric(12, 2)),
        sa.column("cash_credit_limit", sa.Numeric(12, 2)),
        sa.column("open_date", sa.Date()),
        sa.column("expiration_date", sa.Date()),
        sa.column("reissue_date", sa.Date()),
        sa.column("curr_cyc_credit", sa.Numeric(12, 2)),
        sa.column("curr_cyc_debit", sa.Numeric(12, 2)),
        sa.column("addr_zip", sa.String(10)),
        sa.column("group_id", sa.String(10)),
    ]


def TranCategoryBalanceColumns() -> list:
    """Typed columns for tran_category_balance (balance NUMERIC(11, 2))."""
    return [
        sa.column("acct_id", sa.String(11)),
        sa.column("tran_type_cd", sa.CHAR(2)),
        sa.column("tran_cat_cd", sa.String(4)),
        sa.column("balance", sa.Numeric(11, 2)),
    ]


def UserColumns() -> list:
    """Typed columns for users (password_hash only; never plaintext)."""
    return [
        sa.column("user_id", sa.String(8)),
        sa.column("first_name", sa.String(20)),
        sa.column("last_name", sa.String(20)),
        sa.column("password_hash", sa.String(255)),
        sa.column("user_type", sa.CHAR(1)),
    ]


def CardColumns() -> list:
    """Typed columns for cards (mirrors 0001)."""
    return [
        sa.column("card_num", sa.String(16)),
        sa.column("acct_id", sa.String(11)),
        # cvv_cd intentionally absent (QA finding C-03, AAP 0.7.8): never seeded.
        sa.column("embossed_name", sa.String(50)),
        sa.column("expiration_date", sa.Date()),
        sa.column("active_status", sa.CHAR(1)),
    ]


def CardXrefColumns() -> list:
    """Typed columns for card_xref (real column is xref_card_num)."""
    return [
        sa.column("xref_card_num", sa.String(16)),
        sa.column("cust_id", sa.String(9)),
        sa.column("acct_id", sa.String(11)),
    ]


def TransactionColumns() -> list:
    """Typed columns for transactions (tran_amt NUMERIC(11, 2); TIMESTAMPTZ)."""
    return [
        sa.column("tran_id", sa.String(16)),
        sa.column("tran_type_cd", sa.CHAR(2)),
        sa.column("tran_cat_cd", sa.String(4)),
        sa.column("tran_source", sa.String(10)),
        sa.column("tran_desc", sa.String(100)),
        sa.column("tran_amt", sa.Numeric(11, 2)),
        sa.column("merchant_id", sa.String(9)),
        sa.column("merchant_name", sa.String(50)),
        sa.column("merchant_city", sa.String(50)),
        sa.column("merchant_zip", sa.String(10)),
        sa.column("card_num", sa.String(16)),
        sa.column("orig_ts", sa.DateTime(timezone=True)),
        sa.column("proc_ts", sa.DateTime(timezone=True)),
        sa.column("status", sa.String(10)),
    ]


# ---------------------------------------------------------------------------
# Idempotent insert + migration entry points
# ---------------------------------------------------------------------------


def SeedTable(connection, tableName: str, columnDefs: list, rows: list[dict]) -> None:
    """Idempotently insert rows into a table, skipping any PK/unique conflict.

    Builds a PostgreSQL ``INSERT ... ON CONFLICT DO NOTHING`` so re-running the
    migration (or running alongside batch/loaders/init_users.py) never raises a
    duplicate-key error and never creates duplicate rows -- the seed is fully
    re-runnable. The call is a no-op when there are no rows.

    Args:
        connection: The Alembic migration bind returned by ``op.get_bind()``.
        tableName: The destination table name.
        columnDefs: Typed ``sa.column()`` clauses matching the row-dict keys.
        rows: The rows to insert, keyed by column name.
    """
    if not rows:
        return
    table = sa.table(tableName, *columnDefs)
    statement = PgInsert(table).values(rows).on_conflict_do_nothing()
    connection.execute(statement)


def upgrade() -> None:
    """Seed all 10 tables from the legacy datasets, FK-safe (parents first).

    Parents are seeded before children so no foreign-key constraint is violated
    on a fresh database: accounts precede cards; cards/customers/accounts precede
    card_xref; cards precede transactions. users and tran_category_balance carry
    no foreign keys and are placed logically. Every insert is idempotent, so the
    migration is safe to re-run and to interleave with the batch user loader.
    """
    connection = op.get_bind()
    asciiDir = LocateAsciiDir()
    ebcdicDir = LocateEbcdicDir()
    SeedTable(connection, "transaction_type", TransactionTypeColumns(), ParseTranTypes(asciiDir))
    SeedTable(
        connection,
        "transaction_category",
        TransactionCategoryColumns(),
        ParseTranCategories(asciiDir),
    )
    SeedTable(
        connection,
        "disclosure_group",
        DisclosureGroupColumns(),
        ParseDisclosureGroups(asciiDir),
    )
    SeedTable(connection, "customers", CustomerColumns(), ParseCustomers(asciiDir))
    SeedTable(connection, "accounts", AccountColumns(), ParseAccounts(asciiDir))
    SeedTable(
        connection,
        "tran_category_balance",
        TranCategoryBalanceColumns(),
        ParseTranCategoryBalances(asciiDir),
    )
    SeedTable(connection, "users", UserColumns(), ParseUsers(ebcdicDir))
    SeedTable(connection, "cards", CardColumns(), ParseCards(asciiDir))
    SeedTable(connection, "card_xref", CardXrefColumns(), ParseCardXrefs(asciiDir))
    SeedTable(connection, "transactions", TransactionColumns(), ParseTransactions(asciiDir))


def downgrade() -> None:
    """Delete every seeded row in REVERSE FK order; the 0001 schema is retained.

    Children are deleted before parents so no foreign-key constraint blocks a
    delete. Table names are hard-coded literals (no user input, so no SQL-injection
    surface). This reverses :func:`upgrade` without dropping any table or index.
    """
    connection = op.get_bind()
    for tableName in (
        "transactions",
        "card_xref",
        "cards",
        "users",
        "tran_category_balance",
        "accounts",
        "customers",
        "disclosure_group",
        "transaction_category",
        "transaction_type",
    ):
        connection.execute(sa.text(f"DELETE FROM {tableName}"))
