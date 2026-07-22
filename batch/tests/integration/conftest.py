# batch/tests/integration/conftest.py
# =============================================================================
# Integration-test-local pytest conftest for the CardDemo batch test suite.
#
# Traceability (AAP 0.5.2 / 0.8.1): the helpers here support the DB-backed
# golden-master parity tests that reproduce the legacy COBOL batch posting
# program app/cbl/CBTRN02C.cbl (transaction posting; reject reason codes
# 100-103 and 109) and the interest-calculation job (app/cbl/CBACT04C.cbl).
# Record layouts derive from the copybooks app/cpy/CVACT01Y (ACCOUNT-RECORD),
# CVACT02Y (CARD-RECORD), CVACT03Y (CARD-XREF-RECORD), CVCUS01Y
# (CUSTOMER-RECORD) and CVTRA05Y (TRAN-RECORD). This file is REFERENCE-derived
# only; the legacy app/ tree is never modified.
# =============================================================================
"""Integration conftest: shared helpers for CardDemo batch DB-backed tests.

This is the folder-local ``conftest.py`` for ``batch/tests/integration/``. It
does NOT redefine the root fixtures declared in ``batch/tests/conftest.py``
(``db_session``, ``session_factory``, ``data_dir``, ``usrsec_path``,
``seeded_db``); it consumes them. It adds four reusable helpers that the sibling
golden-master tests (``test_post_transactions.py``, ``test_interest_calc.py``)
depend on:

* ``record_builder`` -- a :class:`RecordBuilder` that inserts minimal,
  foreign-key-valid ORM rows into the shared, rolled-back test session so a test
  can stage a controlled posting or interest-calculation scenario.
* ``parse_reject_row`` -- a parser for the fixed-width 430-byte posting reject
  row emitted by the ported CBTRN02C job (design-agnostic golden-master
  parsing).
* ``posting_catalog`` -- a resolver that reads the reject code -> description
  catalog from :mod:`app.core.exceptions`, tolerant of either acceptable
  exception design.
* ``relax_foreign_keys`` -- a context-manager helper that temporarily disables a
  table's foreign-key/user triggers so a test can insert an
  intentionally-dangling row and exercise the referential-integrity-guarded
  posting code 101 (cross-reference found, account missing).

The batch layer is SYNCHRONOUS: every helper works against the blocking
SQLAlchemy :class:`~sqlalchemy.orm.Session` injected by the root ``db_session``
fixture. This module deliberately imports no asynchronous machinery -- it never
pulls in the backend's async database-session engine, an async PostgreSQL
driver, the async pytest plugin, an ASGI HTTP client, the standard-library event
loop, or SQLAlchemy's async ORM extension. All monetary and rate values are
exact :class:`decimal.Decimal` instances; floating point is never used
(AAP 0.7.1).

Naming follows the Ochs Rule with the documented pytest exception: fixture
function names are snake_case (they are referenced by test arguments), while
helper classes and functions are PascalCase, local variables are camelCase, and
module constants are ALL_UPPERCASE.
"""

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Callable, Iterator, TypeVar

import pytest
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.models import (
    Account,
    Card,
    CardXref,
    Customer,
    DisclosureGroup,
    STATUS_PENDING,
    TranCategoryBalance,
    Transaction,
)

# --------------------------------------------------------------------------- #
# Module constants (Ochs ALL_UPPERCASE).
#
# The reject-row geometry mirrors the CBTRN02C ``REJECT-RECORD`` layout
# (CBTRN02C L176-182): a 350-byte transaction-data image, a 4-digit reason code
# and a 76-byte description, for a fixed total of 430 bytes. The builder-row
# defaults keep each builder method small and are obviously-fake, non-sensitive
# test values used only to satisfy NOT NULL columns.
# --------------------------------------------------------------------------- #
DEFAULT_GROUP_ID = "A000000000"
DEFAULT_ORIG_TS = datetime(2023, 6, 1, 12, 0, 0)

REJECT_ROW_LENGTH = 430
REJECT_DATA_WIDTH = 350
REJECT_REASON_WIDTH = 4
REJECT_DESC_WIDTH = 76

DEFAULT_CVV = "123"
DEFAULT_EMBOSSED_NAME = "TEST CARDHOLDER"
DEFAULT_ACTIVE_STATUS = "Y"
DEFAULT_EXPIRATION_DATE = date(2030, 1, 1)

# Static (non-key, non-amount) defaults for a staged PENDING daily transaction.
# The three per-call values (tran_id, card_num, tran_amt) and any caller
# overrides are merged over a copy of this mapping, so it is never mutated.
DEFAULT_PENDING_TRAN_FIELDS = {
    "tran_type_cd": "01",
    "tran_cat_cd": "0001",
    "tran_source": "POS",
    "tran_desc": "TEST TRANSACTION",
    "merchant_id": "000000001",
    "merchant_name": "TEST MERCHANT",
    "merchant_city": "TEST CITY",
    "merchant_zip": "00000",
    "orig_ts": DEFAULT_ORIG_TS,
    "proc_ts": None,
    "status": STATUS_PENDING,
}

# Generic type variable so the private persist helper returns the exact ORM type
# it was handed, keeping each public builder's declared return type precise.
_ModelT = TypeVar("_ModelT")


# --------------------------------------------------------------------------- #
# Reject-row parsing (golden-master, design-agnostic).
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class RejectRow:
    """One parsed 430-byte posting reject row (CBTRN02C ``REJECT-RECORD``).

    Attributes:
        data: The 350-byte daily-transaction record image (space-padded).
        reasonText: The raw 4-character reason field (for example ``"0100"``).
        reasonCode: The reason field parsed as an ``int`` (for example ``100``).
        description: The reject description with trailing padding stripped.
    """

    data: str
    reasonText: str
    reasonCode: int
    description: str


def ParseRejectRow(row: str) -> RejectRow:
    """Parse a 430-byte posting reject row into its fixed-width fields.

    Mirrors the CBTRN02C ``REJECT-RECORD`` layout: ``[0:350]`` is the
    daily-transaction record image, ``[350:354]`` is the zero-padded 4-digit
    reason code, and ``[354:430]`` is the left-justified UPPERCASE description.

    Args:
        row: The fixed-width 430-character reject row to parse.

    Returns:
        The parsed :class:`RejectRow`.
    """
    assert len(row) == REJECT_ROW_LENGTH, f"reject row must be {REJECT_ROW_LENGTH} bytes, got {len(row)}"
    dataImage = row[0:REJECT_DATA_WIDTH]
    reasonText = row[REJECT_DATA_WIDTH : REJECT_DATA_WIDTH + REJECT_REASON_WIDTH]
    description = row[REJECT_DATA_WIDTH + REJECT_REASON_WIDTH :]
    return RejectRow(
        data=dataImage,
        reasonText=reasonText,
        reasonCode=int(reasonText),
        description=description.rstrip(),
    )


@pytest.fixture
def parse_reject_row() -> Callable[[str], RejectRow]:
    """Return :func:`ParseRejectRow` for golden-master reject-row assertions."""
    return ParseRejectRow


# --------------------------------------------------------------------------- #
# Controlled-scenario ORM row builder.
# --------------------------------------------------------------------------- #
class RecordBuilder:
    """Builds minimal FK-valid ORM rows in a shared test session.

    Each builder method sets every NOT NULL column with an obviously-fake test
    default, merges any caller ``overrides``, inserts the row and flushes it, so
    that a later builder call or a subsequently-invoked batch job observes the
    row within the same rolled-back transaction. Monetary and rate defaults are
    exact :class:`decimal.Decimal` values, never floating point (AAP 0.7.1).
    """

    def __init__(self, session: Session) -> None:
        """Bind the builder to the rolled-back test ``session``."""
        self.session = session

    def _Persist(self, row: _ModelT) -> _ModelT:
        """Add ``row`` to the session and flush so later reads can see it."""
        self.session.add(row)
        self.session.flush()
        return row

    def BuildCustomer(self, custId: str = "000000009", overrides: dict | None = None) -> Customer:
        """Insert a customer master row (``customers``) and return it."""
        values = {
            "cust_id": custId,
            "first_name": "TEST",
            "last_name": "CUSTOMER",
            "addr_line_1": "123 TEST STREET",
        }
        if overrides:
            values.update(overrides)
        return self._Persist(Customer(**values))

    def BuildAccount(self, acctId: str = "00000000009", overrides: dict | None = None) -> Account:
        """Insert an account row (``accounts``) and return it.

        Defaults set every NOT NULL monetary column to an exact ``Decimal`` and
        provide an ``expiration_date`` (for the code-103 expiration edit) and a
        ``group_id`` (for the interest-calculation disclosure-group join).
        """
        values = {
            "acct_id": acctId,
            "active_status": DEFAULT_ACTIVE_STATUS,
            "curr_bal": Decimal("0.00"),
            "credit_limit": Decimal("5000.00"),
            "cash_credit_limit": Decimal("2000.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "expiration_date": DEFAULT_EXPIRATION_DATE,
            "group_id": DEFAULT_GROUP_ID,
        }
        if overrides:
            values.update(overrides)
        return self._Persist(Account(**values))

    def BuildCard(self, cardNum: str, acctId: str, overrides: dict | None = None) -> Card:
        """Insert a card row (``cards``) linked to ``acctId`` and return it."""
        values = {
            "card_num": cardNum,
            "acct_id": acctId,
            "cvv_cd": DEFAULT_CVV,
            "embossed_name": DEFAULT_EMBOSSED_NAME,
            "active_status": DEFAULT_ACTIVE_STATUS,
            "expiration_date": DEFAULT_EXPIRATION_DATE,
        }
        if overrides:
            values.update(overrides)
        return self._Persist(Card(**values))

    def BuildXref(self, cardNum: str, custId: str, acctId: str) -> CardXref:
        """Insert a card cross-reference row (``card_xref``) and return it.

        Sets the real column ``xref_card_num`` directly (``card_num`` is only an
        ORM synonym for it) so the insert is unambiguous.
        """
        row = CardXref(xref_card_num=cardNum, cust_id=custId, acct_id=acctId)
        return self._Persist(row)

    def BuildDisclosureGroup(
        self,
        groupId: str,
        tranTypeCd: str,
        tranCatCd: str,
        interestRate: Decimal,
    ) -> DisclosureGroup:
        """Insert a disclosure-group rate row (``disclosure_group``).

        The three composite-key parts plus the rate are the four data inputs
        (the Ochs four-parameter maximum). ``interestRate`` is an exact
        ``Decimal`` mapped to ``NUMERIC(6, 2)`` (AAP 0.7.2 Finding #2).
        """
        row = DisclosureGroup(
            group_id=groupId,
            tran_type_cd=tranTypeCd,
            tran_cat_cd=tranCatCd,
            interest_rate=interestRate,
        )
        return self._Persist(row)

    def BuildTranCategoryBalance(
        self,
        acctId: str,
        tranTypeCd: str,
        tranCatCd: str,
        balance: Decimal,
    ) -> TranCategoryBalance:
        """Insert a per-account/per-category running-balance row and return it.

        The three composite-key parts plus the balance are the four data inputs
        (the Ochs four-parameter maximum). ``balance`` is an exact ``Decimal``
        mapped to ``NUMERIC(11, 2)``.
        """
        row = TranCategoryBalance(
            acct_id=acctId,
            tran_type_cd=tranTypeCd,
            tran_cat_cd=tranCatCd,
            balance=balance,
        )
        return self._Persist(row)

    def BuildPendingTransaction(
        self,
        tranId: str,
        cardNum: str,
        tranAmt: Decimal,
        overrides: dict | None = None,
    ) -> Transaction:
        """Insert a PENDING daily transaction (``transactions``) and return it.

        Static defaults come from :data:`DEFAULT_PENDING_TRAN_FIELDS`; the three
        per-call keys and any ``overrides`` are merged over a fresh copy so the
        module constant is never mutated. ``tranAmt`` is an exact ``Decimal``.
        """
        values = dict(DEFAULT_PENDING_TRAN_FIELDS)
        values.update({"tran_id": tranId, "card_num": cardNum, "tran_amt": tranAmt})
        if overrides:
            values.update(overrides)
        return self._Persist(Transaction(**values))


@pytest.fixture
def record_builder(db_session: Session) -> RecordBuilder:
    """Builder for constructing FK-valid ORM rows inside the rolled-back session."""
    return RecordBuilder(db_session)


# --------------------------------------------------------------------------- #
# Posting reject catalog resolver (tolerant of both exception designs).
# --------------------------------------------------------------------------- #
def ResolvePostingCatalog() -> dict[int, str]:
    """Return ``{reasonCode: description}`` from :mod:`app.core.exceptions`.

    Tolerates either acceptable design: a ``POSTING_REJECT_DESCRIPTIONS``
    mapping keyed by an ``IntEnum``/``int`` (preferred), or a
    ``TransactionPostingError`` base class whose concrete subclasses expose
    ``.code`` and ``.description``. Only the specific :class:`TypeError` raised
    by a subclass that cannot be default-constructed is caught (Ochs Rule:
    never a bare ``except``).

    Returns:
        A mapping of integer reject reason code to its verbatim description.
    """
    import app.core.exceptions as excMod

    catalog: dict[int, str] = {}

    descriptions = getattr(excMod, "POSTING_REJECT_DESCRIPTIONS", None)
    if descriptions:
        for key, value in descriptions.items():
            catalog[int(key)] = str(value)
        return catalog

    baseError = getattr(excMod, "TransactionPostingError", None)
    if baseError is not None:
        for name in dir(excMod):
            candidate = getattr(excMod, name)
            isPostingSubclass = (
                isinstance(candidate, type) and issubclass(candidate, baseError) and candidate is not baseError
            )
            if not isPostingSubclass:
                continue
            code = getattr(candidate, "code", None)
            desc = getattr(candidate, "description", None)
            if code is None or desc is None:
                try:
                    instance = candidate()
                except TypeError:
                    continue
                code = getattr(instance, "code", None)
                desc = getattr(instance, "description", None)
            if code is not None and desc is not None:
                catalog[int(code)] = str(desc)
    return catalog


@pytest.fixture
def posting_catalog() -> dict[int, str]:
    """Reject code -> description catalog resolved from :mod:`app.core.exceptions`."""
    return ResolvePostingCatalog()


# --------------------------------------------------------------------------- #
# Foreign-key relaxation helper (exercises the FK-guarded posting code 101).
# --------------------------------------------------------------------------- #
@contextmanager
def RelaxForeignKeys(session: Session, tableName: str) -> Iterator[None]:
    """Temporarily disable a table's FK/user triggers within the transaction.

    Lets a test insert an intentionally-dangling row (for example a
    ``card_xref`` pointing at a missing account) so it can exercise the
    referential-integrity-guarded CBTRN02C posting code 101 (cross-reference
    found, account missing). The triggers are always re-enabled on exit, even if
    the wrapped block raises.

    ``tableName`` MUST be a trusted literal supplied by test code and never user
    input, because it is interpolated directly into the DDL statement. It is
    used only against the disposable ``carddemo_test`` schema the suite owns. If
    the database user lacks the privilege to alter the table, PostgreSQL raises
    :class:`sqlalchemy.exc.ProgrammingError`, which the consuming test converts
    into :func:`pytest.skip`.

    Args:
        session: The synchronous test session on which to run the DDL.
        tableName: The trusted table name whose triggers are toggled.

    Yields:
        None. The wrapped block runs with the table's triggers disabled.
    """
    session.execute(text(f"ALTER TABLE {tableName} DISABLE TRIGGER ALL"))
    try:
        yield
    finally:
        session.execute(text(f"ALTER TABLE {tableName} ENABLE TRIGGER ALL"))


@pytest.fixture
def relax_foreign_keys() -> Callable[[Session, str], Iterator[None]]:
    """Return the :func:`RelaxForeignKeys` context-manager factory."""
    return RelaxForeignKeys
