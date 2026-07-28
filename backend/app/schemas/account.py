"""Account DTOs. Source: app/cpy/CVACT01Y.cpy; screens COACTVW.CPY (view), COACTUP.CPY (update).

Pydantic v2 request/response schemas for the CardDemo Account entity. These
DTOs are the modern port of the legacy 300-byte ``ACCOUNT-RECORD`` (COBOL
copybook ``CVACT01Y``, VSAM ``ACCTDATA`` KSDS) and of the two online screens
that read and maintain it:

* ``COACTVW`` (transaction ``CAVW``) -- the read-only *view* screen, which
  displays the account together with its owning customer. That combined layout
  is why :class:`AccountDetail` embeds a :class:`app.schemas.customer.CustomerRead`.
* ``COACTUP`` (transaction ``CAUP``) -- the *update* screen, whose editable
  account fields drive :class:`AccountUpdate`.

Field-mapping rules (AAP section 0.1 type table, verified directly against
``app/cpy/CVACT01Y.cpy``):

* ``ACCT-ID PIC 9(11)`` -> ``acct_id: str`` (an 11-character digit string, not
  an ``int``, so the significant leading zeros of the VSAM key are preserved).
* ``ACCT-ACTIVE-STATUS PIC X(01)`` -> ``active_status: str`` ('Y'/'N').
* The five monetary fields ``PIC S9(10)V99`` -> :class:`decimal.Decimal` at
  ``NUMERIC(12, 2)``. Per AAP section 0.7.1 (Finding #1) these are signed
  zoned-decimal DISPLAY fields, so ``float`` is NEVER used -- binary floating
  point would violate the regulatory numeric-parity requirement. Every money
  input is routed through :func:`app.utils.decimal_utils.ToDecimal`, which
  rejects ``float`` and ``bool`` outright.
* The three ``PIC X(10)`` date fields -> :class:`datetime.date` (nullable,
  because the legacy record leaves them blank before the relevant lifecycle
  event). Note ``ACCT-EXPIRAION-DATE`` (sic) is deliberately corrected to the
  attribute name ``expiration_date``.
* ``ACCT-ADDR-ZIP PIC X(10)`` -> ``addr_zip: str``; ``ACCT-GROUP-ID PIC X(10)``
  -> ``group_id: str``.
* ``FILLER PIC X(178)`` is dropped (record padding to RECLN 300 only).

Concurrency note (AAP section 0.7.4, Minimal Change Clause): the legacy
``COACTUPC`` READ-for-UPDATE -> REWRITE optimistic check is reproduced in
``app.services.account_service`` with ``SELECT ... FOR UPDATE``; no
``version``/``updated_at`` column exists on the ORM model, so :class:`AccountUpdate`
carries no optimistic-lock echo field (there is nothing to echo).

Naming follows the Ochs Rule (AAP section 0.8.3): classes and methods use
PascalCase, local variables camelCase, and module constants ALL_UPPERCASE,
while the file/module name stays snake_case.
"""

from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Optional

from pydantic import Field, ValidationInfo, field_serializer, field_validator

from app.schemas.common import OrmBase, RequestBase
from app.schemas.customer import CustomerRead
from app.utils import date_utils, decimal_utils, validators

__all__ = [
    "AccountBase",
    "AccountRead",
    "AccountBeforeImage",
    "AccountUpdate",
    "AccountDetail",
]

# ---------------------------------------------------------------------------
# Field lengths (Ochs Rule 0.8.2: constants are ALL_UPPERCASE). ACCT_ID_LENGTH
# is sourced from app.utils.validators so the schema constraint and the shared
# field edit can never drift apart.
# ---------------------------------------------------------------------------
ACCT_ID_LENGTH = validators.ACCT_ID_LENGTH        # ACCT-ID PIC 9(11).
ACTIVE_STATUS_MAX_LENGTH = 1                       # ACCT-ACTIVE-STATUS PIC X(01).
ADDR_ZIP_MAX_LENGTH = 10                           # ACCT-ADDR-ZIP PIC X(10).
GROUP_ID_MAX_LENGTH = 10                           # ACCT-GROUP-ID PIC X(10).

# Monetary precision for every ACCT-* PIC S9(10)V99 field -> NUMERIC(12, 2).
# Sourced from decimal_utils so the Pydantic field constraint, the ORM column,
# and the zoned-decimal codec agree on 12 total digits / 2 decimal places.
MONEY_MAX_DIGITS = decimal_utils.ACCOUNT_MONEY_DIGITS      # 12.
MONEY_DECIMAL_PLACES = decimal_utils.MONEY_SCALE           # 2.

# ---------------------------------------------------------------------------
# Field labels used to build legacy-style validation failure messages.
# ---------------------------------------------------------------------------
ACCT_ID_LABEL = "Account ID"
ACTIVE_STATUS_LABEL = "Account status"
OPEN_DATE_LABEL = "Open date"
EXPIRATION_DATE_LABEL = "Expiration date"
REISSUE_DATE_LABEL = "Reissue date"

# Human-readable labels for the two account *limit* fields, used to build the
# nonnegative-bound failure message (QA finding I5). The five monetary fields
# are signed ``PIC S9(10)V99`` in the copybook, so balances and cycle
# accumulators (``curr_bal``, ``curr_cyc_credit``, ``curr_cyc_debit``) remain
# legitimately signed; only the two *limit* ceilings can never be negative and
# are therefore bounded. The wording matches
# ``app.services.account_service.MONEY_FIELD_LABELS`` so a rejection reads
# identically whether it surfaces at the schema or the service boundary.
CREDIT_LIMIT_LABEL = "Credit Limit"
CASH_CREDIT_LIMIT_LABEL = "Cash Credit Limit"
LIMIT_FIELD_LABELS = {
    "credit_limit": CREDIT_LIMIT_LABEL,
    "cash_credit_limit": CASH_CREDIT_LIMIT_LABEL,
}

# Message surfaced when a monetary field receives a non-decimal value (for
# example a binary ``float``), so Pydantic reports a clean ``ValidationError``.
MSG_MONEY_NOT_DECIMAL = (
    "Monetary value must be an exact decimal (string, integer, or Decimal); "
    "floating point is not accepted."
)


# ---------------------------------------------------------------------------
# Private helpers (module-internal; keep the schema classes small per the Ochs
# "small methods" rule and express each cross-cutting edit exactly once).
# ---------------------------------------------------------------------------
def _CoerceMoneyValue(rawValue: object) -> object:
    """Coerce a monetary input to an exact ``Decimal`` and reject ``float``.

    Shared by every account money-field validator so the float-rejection rule
    is expressed once. ``None`` is passed through untouched so Pydantic applies
    the field's own required/optional handling; any present value is routed
    through :func:`app.utils.decimal_utils.ToDecimal`, which accepts
    ``str`` / ``int`` / ``Decimal`` (including the ``Decimal`` a PostgreSQL
    ``NUMERIC`` column yields) and rejects ``float`` and ``bool``.

    Args:
        rawValue: The raw money input from a request payload or an ORM row.

    Returns:
        A :class:`decimal.Decimal` for numeric input, or the original ``None``.

    Raises:
        ValueError: If ``rawValue`` is a ``float`` / ``bool`` or an unparseable
            string. The underlying ``TypeError`` / ``ValueError`` /
            ``InvalidOperation`` from :func:`app.utils.decimal_utils.ToDecimal`
            is re-raised as a ``ValueError`` so Pydantic surfaces a clean
            ``ValidationError`` (Pydantic does not wrap a raw ``TypeError``).
    """
    if rawValue is None:
        return rawValue
    try:
        return decimal_utils.ToDecimal(rawValue)
    except (TypeError, ValueError, InvalidOperation) as conversionError:
        raise ValueError(f"{MSG_MONEY_NOT_DECIMAL} ({conversionError})") from conversionError


def _ParseAccountDate(rawValue: object) -> Optional[date]:
    """Validate and parse an account date input into a native ``date``.

    Reproduces the legacy X(10) date handling for ``ACCT-OPEN-DATE``,
    ``ACCT-EXPIRAION-DATE`` and ``ACCT-REISSUE-DATE``. A genuinely absent value
    -- Python ``None`` / JSON ``null`` / an omitted optional field -- yields
    ``None`` (these columns are nullable). A *blank string* is deliberately NOT
    treated as "no date": it is an explicit-but-empty submission (for example an
    operator clearing a required date on ``COACTUP`` and saving), so it is
    rejected with the legacy ``"Date must be supplied."`` edit rather than
    silently coerced to NULL (QA finding I4). A :class:`datetime.date` (or
    :class:`datetime.datetime`) is accepted directly so an ORM row loads without
    a round-trip through text; a non-blank string is validated with the ported
    ``CSUTLDPY`` calendar edits via :func:`app.utils.date_utils.ValidateDate`
    and then parsed with :func:`app.utils.date_utils.ParseLegacyDate`.

    Args:
        rawValue: The raw date input (``str``, ``date``, ``datetime``, or
            ``None``).

    Returns:
        The parsed :class:`datetime.date`, or ``None`` only when the input is
        genuinely absent (``None``).

    Raises:
        ValueError: If a supplied string is blank (``"Date must be supplied."``)
            or fails the legacy calendar edits. Only the specific
            :class:`ValueError` is raised (never a bare ``except``) so Pydantic
            surfaces a clean ``ValidationError``.
    """
    if rawValue is None:
        return None
    if isinstance(rawValue, datetime):
        return rawValue.date()
    if isinstance(rawValue, date):
        return rawValue
    dateText = str(rawValue).strip()
    # QA finding I4: a blank string is an explicit-but-empty date submission
    # (an operator cleared a required date field on COACTUP and saved). It must
    # NOT be coerced to NULL and committed -- only a genuinely absent value
    # (already returned by the ``rawValue is None`` guard above) means "no
    # date". Letting the blank fall through to ``ValidateDate`` yields the
    # legacy ``"Date must be supplied."`` edit and a clean HTTP 422, instead of
    # the previous silent ``return None`` that persisted a NULL.
    result = date_utils.ValidateDate(dateText)
    if not result.isValid:
        raise ValueError(result.message)
    return date_utils.ParseLegacyDate(dateText)


def _ValidateAcctId(value: str) -> str:
    """Validate ``acct_id`` as exactly ``ACCT_ID_LENGTH`` ASCII digits.

    Delegates to :func:`app.utils.validators.ValidateNumericId` so the
    accept/reject behavior (digits only, exact length, leading zeros kept) and
    the failure wording match the legacy field edit. The value is returned
    unchanged on success so the zero-padded string is preserved verbatim.

    Args:
        value: The candidate account identifier.

    Returns:
        The validated account identifier, unchanged.

    Raises:
        ValueError: If the value is not exactly ``ACCT_ID_LENGTH`` digits.
    """
    result = validators.ValidateNumericId(ACCT_ID_LABEL, value, ACCT_ID_LENGTH)
    if not result.isValid:
        raise ValueError(result.message)
    return value


def _ValidateActiveStatus(value: Optional[str]) -> Optional[str]:
    """Validate ``active_status`` as the uppercase literal ``'Y'`` or ``'N'``.

    Delegates to :func:`app.utils.validators.ValidateYesNo` (the ported
    ``1220-EDIT-YESNO`` 88-level ``FLG-YES-NO-ISVALID``), which is intentionally
    case-sensitive. ``None`` passes through so an optional/partial-update caller
    that omits the field is not forced to supply it.

    Args:
        value: The candidate status flag, or ``None`` when omitted.

    Returns:
        The validated status flag, unchanged (or ``None``).

    Raises:
        ValueError: If a supplied value is not exactly ``'Y'`` or ``'N'``.
    """
    if value is None:
        return value
    result = validators.ValidateYesNo(ACTIVE_STATUS_LABEL, value)
    if not result.isValid:
        raise ValueError(result.message)
    return value


class AccountBase(OrmBase):
    """Canonical account representation with the full set of legacy edits.

    Declares every account field (in the ``CVACT01Y`` copybook order for
    traceability) and enforces the ported field edits: the fixed-length numeric
    ``acct_id`` key, the Yes/No ``active_status`` flag, exact ``Decimal`` money
    with float rejection, and calendar-validated dates. Inherits
    :class:`app.schemas.common.OrmBase` (``from_attributes=True``), so it can be
    built either from user-supplied data or directly from an ORM row.

    This is the strict base representation; :class:`AccountRead` is the trusted
    response projection (which skips the strict id/status edits because its
    values originate from the datastore).
    """

    acct_id: str = Field(
        ...,
        max_length=ACCT_ID_LENGTH,
        description="Account identifier (ACCT-ID PIC 9(11)); 11-digit string, leading zeros preserved.",
    )
    active_status: str = Field(
        ...,
        max_length=ACTIVE_STATUS_MAX_LENGTH,
        description="Account active status (ACCT-ACTIVE-STATUS PIC X(01)); 'Y' or 'N'.",
    )
    curr_bal: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Current account balance (ACCT-CURR-BAL PIC S9(10)V99); NUMERIC(12,2) "
            "exact Decimal, may be negative. Never floating point."
        ),
    )
    credit_limit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description="Total credit limit (ACCT-CREDIT-LIMIT PIC S9(10)V99); NUMERIC(12,2) exact Decimal.",
    )
    cash_credit_limit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Cash-advance sub-limit (ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    open_date: Optional[date] = Field(
        default=None,
        description="Account open date (ACCT-OPEN-DATE PIC X(10)) as an ISO date; nullable.",
    )
    expiration_date: Optional[date] = Field(
        default=None,
        description=(
            "Account expiration date (ACCT-EXPIRAION-DATE PIC X(10), copybook typo "
            "corrected) as an ISO date; nullable."
        ),
    )
    reissue_date: Optional[date] = Field(
        default=None,
        description="Account reissue date (ACCT-REISSUE-DATE PIC X(10)) as an ISO date; nullable.",
    )
    curr_cyc_credit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Current-cycle credits (ACCT-CURR-CYC-CREDIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    curr_cyc_debit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Current-cycle debits (ACCT-CURR-CYC-DEBIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    addr_zip: Optional[str] = Field(
        default=None,
        max_length=ADDR_ZIP_MAX_LENGTH,
        description="Account billing ZIP (ACCT-ADDR-ZIP PIC X(10)); nullable.",
    )
    group_id: Optional[str] = Field(
        default=None,
        max_length=GROUP_ID_MAX_LENGTH,
        description="Disclosure group id (ACCT-GROUP-ID PIC X(10)); logical ref to disclosure_group.",
    )

    @field_validator("acct_id")
    @classmethod
    def ValidateAcctId(cls, value: str) -> str:
        """Validate ``acct_id`` is exactly ``ACCT_ID_LENGTH`` ASCII digits."""
        return _ValidateAcctId(value)

    @field_validator("active_status")
    @classmethod
    def ValidateActiveStatus(cls, value: str) -> str:
        """Validate ``active_status`` is the uppercase literal ``'Y'`` or ``'N'``."""
        return _ValidateActiveStatus(value)

    @field_validator(
        "curr_bal",
        "credit_limit",
        "cash_credit_limit",
        "curr_cyc_credit",
        "curr_cyc_debit",
        mode="before",
    )
    @classmethod
    def CoerceMoney(cls, value: object) -> object:
        """Coerce every money field to an exact ``Decimal`` and reject float."""
        return _CoerceMoneyValue(value)

    @field_validator("open_date", "expiration_date", "reissue_date", mode="before")
    @classmethod
    def ParseDates(cls, value: object) -> Optional[date]:
        """Validate/parse the legacy X(10) date fields into native ``date``s."""
        return _ParseAccountDate(value)



class AccountRead(OrmBase):
    """Response DTO for a single account, built from the ORM row.

    Populated directly from a SQLAlchemy ``Account`` instance via
    ``from_attributes`` (inherited from :class:`app.schemas.common.OrmBase`), so
    a repository or service returns ``AccountRead.model_validate(orm_row)``.

    This is a standalone schema (it does not inherit :class:`AccountBase`),
    matching the sibling ``CustomerRead`` convention: the strict ``acct_id`` /
    ``active_status`` field edits are intentionally omitted because the values
    originate from the trusted datastore, while the monetary and date fields are
    still coerced so a ``float`` can never leak in and legacy date text is
    normalized to a native :class:`datetime.date`. The nullable columns
    (dates, ``addr_zip``, ``group_id``) are Optional to mirror the ORM model.
    """

    acct_id: str = Field(
        ...,
        max_length=ACCT_ID_LENGTH,
        description="Account identifier (ACCT-ID PIC 9(11)); 11-digit string, leading zeros preserved.",
    )
    active_status: str = Field(
        ...,
        max_length=ACTIVE_STATUS_MAX_LENGTH,
        description="Account active status (ACCT-ACTIVE-STATUS PIC X(01)); 'Y' or 'N'.",
    )
    curr_bal: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Current account balance (ACCT-CURR-BAL PIC S9(10)V99); NUMERIC(12,2) "
            "exact Decimal, may be negative. Never floating point."
        ),
    )
    credit_limit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description="Total credit limit (ACCT-CREDIT-LIMIT PIC S9(10)V99); NUMERIC(12,2) exact Decimal.",
    )
    cash_credit_limit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Cash-advance sub-limit (ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    open_date: Optional[date] = Field(
        default=None,
        description="Account open date (ACCT-OPEN-DATE PIC X(10)) as an ISO date; nullable.",
    )
    expiration_date: Optional[date] = Field(
        default=None,
        description=(
            "Account expiration date (ACCT-EXPIRAION-DATE PIC X(10), copybook typo "
            "corrected) as an ISO date; nullable."
        ),
    )
    reissue_date: Optional[date] = Field(
        default=None,
        description="Account reissue date (ACCT-REISSUE-DATE PIC X(10)) as an ISO date; nullable.",
    )
    curr_cyc_credit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Current-cycle credits (ACCT-CURR-CYC-CREDIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    curr_cyc_debit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Current-cycle debits (ACCT-CURR-CYC-DEBIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    addr_zip: Optional[str] = Field(
        default=None,
        max_length=ADDR_ZIP_MAX_LENGTH,
        description="Account billing ZIP (ACCT-ADDR-ZIP PIC X(10)); nullable.",
    )
    group_id: Optional[str] = Field(
        default=None,
        max_length=GROUP_ID_MAX_LENGTH,
        description="Disclosure group id (ACCT-GROUP-ID PIC X(10)); logical ref to disclosure_group.",
    )

    @field_validator(
        "curr_bal",
        "credit_limit",
        "cash_credit_limit",
        "curr_cyc_credit",
        "curr_cyc_debit",
        mode="before",
    )
    @classmethod
    def CoerceMoney(cls, value: object) -> object:
        """Coerce every money field to an exact ``Decimal`` and reject float."""
        return _CoerceMoneyValue(value)

    @field_validator("open_date", "expiration_date", "reissue_date", mode="before")
    @classmethod
    def ParseDates(cls, value: object) -> Optional[date]:
        """Normalize legacy X(10) date text (or a ``date``) into a native ``date``."""
        return _ParseAccountDate(value)

    @field_serializer(
        "curr_bal",
        "credit_limit",
        "cash_credit_limit",
        "curr_cyc_credit",
        "curr_cyc_debit",
        when_used="json",
    )
    def SerializeMoney(self, value: Decimal) -> str:
        """Serialize a money field as a canonical two-decimal string (QA w002).

        Every monetary field is emitted as an exact decimal STRING, never a
        float (AAP section 0.7.1). A value written through ``PUT`` can reach the
        response carrying the scale of the submitted input (for example
        ``"3820.0"``) rather than the canonical ``NUMERIC(12,2)`` scale the
        datastore holds, whereas a freshly-read account is already scale 2.
        Quantizing to two places yields the same ``"3820.00"`` presentation for
        both paths. Input with more than two decimal places is already rejected
        upstream by the ``decimal_places`` constraint, so this only ever pads a
        shorter scale and never rounds a real value away.
        """
        return str(value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


class AccountBeforeImage(RequestBase):
    """Client-echoed before-image of the editable account fields (``COACTUP``).

    Reproduces the ``COACTUPC`` optimistic-lock contract (AAP section 0.7.4): the
    3270 maintenance screen always carried the record image the operator had
    originally read, and the program re-read the row and compared it
    field-for-field before the ``REWRITE``, rejecting the update when another
    unit-of-work had changed the record in the interim. The ORM model
    deliberately carries no ``version``/``updated_at`` column (Minimal Change
    Clause), so the client supplies that prior image here instead -- the values
    it last read for the editable account fields.
    ``app.services.account_service`` re-reads the ``SELECT ... FOR UPDATE`` locked
    row and compares each supplied field; any difference is a concurrent
    modification and is rejected with HTTP 409.

    The five monetary fields and ``active_status`` are required because they are
    always present on a read account and anchor the lost-update check; the two
    dates and ``group_id`` are optional (they may be null on the record) and are
    compared only when supplied. Money values are echoed as strings/Decimals --
    never floats -- and are coerced with the same exact-decimal rule as every
    other account money field (AAP section 0.7.1), so an echoed ``"194.00"``
    compares equal to the stored ``NUMERIC(12,2)`` value.
    """

    active_status: str = Field(
        ...,
        max_length=ACTIVE_STATUS_MAX_LENGTH,
        description="Last-read account active status (ACCT-ACTIVE-STATUS PIC X(01)); 'Y' or 'N'.",
    )
    curr_bal: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description="Last-read current balance (ACCT-CURR-BAL PIC S9(10)V99); NUMERIC(12,2) exact Decimal.",
    )
    credit_limit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description="Last-read total credit limit (ACCT-CREDIT-LIMIT PIC S9(10)V99); NUMERIC(12,2) exact Decimal.",
    )
    cash_credit_limit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Last-read cash-advance sub-limit (ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    curr_cyc_credit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Last-read current-cycle credits (ACCT-CURR-CYC-CREDIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    curr_cyc_debit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Last-read current-cycle debits (ACCT-CURR-CYC-DEBIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    expiration_date: Optional[date] = Field(
        default=None,
        description=(
            "Last-read account expiration date (ACCT-EXPIRAION-DATE PIC X(10), "
            "copybook typo corrected) as an ISO date; compared only when supplied."
        ),
    )
    reissue_date: Optional[date] = Field(
        default=None,
        description=(
            "Last-read account reissue date (ACCT-REISSUE-DATE PIC X(10)) as an ISO "
            "date; compared only when supplied."
        ),
    )
    group_id: Optional[str] = Field(
        default=None,
        max_length=GROUP_ID_MAX_LENGTH,
        description=(
            "Last-read disclosure group id (ACCT-GROUP-ID PIC X(10)); compared only "
            "when supplied."
        ),
    )

    @field_validator("active_status")
    @classmethod
    def ValidateActiveStatus(cls, value: str) -> str:
        """Validate the echoed ``active_status`` is ``'Y'`` or ``'N'``."""
        return _ValidateActiveStatus(value)

    @field_validator(
        "curr_bal",
        "credit_limit",
        "cash_credit_limit",
        "curr_cyc_credit",
        "curr_cyc_debit",
        mode="before",
    )
    @classmethod
    def CoerceMoney(cls, value: object) -> object:
        """Coerce an echoed money field to an exact ``Decimal`` and reject float."""
        return _CoerceMoneyValue(value)

    @field_validator("expiration_date", "reissue_date", mode="before")
    @classmethod
    def ParseDates(cls, value: object) -> Optional[date]:
        """Validate/parse an echoed legacy X(10) date field into a native ``date``."""
        return _ParseAccountDate(value)


class AccountUpdate(RequestBase):
    """Partial-update payload for the ``COACTUP`` account-maintenance screen.

    Carries the editable account fields a client may submit to update an
    existing record. Every field is Optional so a caller can send only the
    attributes being changed (partial update); a field that is present is
    edited with the same legacy rules as :class:`AccountBase`, while an omitted
    field is left untouched.

    ``acct_id`` is intentionally absent: it is the immutable VSAM key supplied
    as the request path parameter, and because this schema inherits
    :class:`app.schemas.common.RequestBase` (``extra="forbid"``), any attempt to
    smuggle ``acct_id`` -- or any other unexpected field -- into the update body
    is rejected outright (input sanitization per the Ochs Rule).

    The optimistic-lock semantics of the legacy READ-for-UPDATE -> REWRITE cycle
    (AAP section 0.7.4) are enforced in ``app.services.account_service`` with a
    ``SELECT ... FOR UPDATE`` transaction. The ORM model exposes no
    ``version``/``updated_at`` column (Minimal Change Clause), so the prior
    record image is supplied by the client instead: ``before_image`` is a
    *required* echo of the editable fields the caller last read, and the service
    compares it field-for-field against the freshly locked row before applying
    the change. Any divergence means another unit-of-work modified the record in
    the interim and the update is rejected with HTTP 409 -- reproducing the
    ``COACTUPC`` lost-update guard exactly.
    """

    before_image: AccountBeforeImage = Field(
        ...,
        description=(
            "Required optimistic-lock token: the editable-field image the caller "
            "last read (see :class:`AccountBeforeImage`). Compared field-for-field "
            "against the locked row; any mismatch is a concurrent-modification "
            "conflict (HTTP 409). This is a control field, not an edited value, so "
            "it is excluded from the set of fields written to the record."
        ),
    )

    active_status: Optional[str] = Field(
        default=None,
        max_length=ACTIVE_STATUS_MAX_LENGTH,
        description="Account active status (ACCT-ACTIVE-STATUS PIC X(01)); 'Y' or 'N'.",
    )
    curr_bal: Optional[Decimal] = Field(
        default=None,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description="Current account balance (ACCT-CURR-BAL PIC S9(10)V99); NUMERIC(12,2) exact Decimal.",
    )
    credit_limit: Optional[Decimal] = Field(
        default=None,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description="Total credit limit (ACCT-CREDIT-LIMIT PIC S9(10)V99); NUMERIC(12,2) exact Decimal.",
    )
    cash_credit_limit: Optional[Decimal] = Field(
        default=None,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Cash-advance sub-limit (ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    expiration_date: Optional[date] = Field(
        default=None,
        description=(
            "Account expiration date (ACCT-EXPIRAION-DATE PIC X(10), copybook typo "
            "corrected) as an ISO date."
        ),
    )
    reissue_date: Optional[date] = Field(
        default=None,
        description="Account reissue date (ACCT-REISSUE-DATE PIC X(10)) as an ISO date.",
    )
    curr_cyc_credit: Optional[Decimal] = Field(
        default=None,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Current-cycle credits (ACCT-CURR-CYC-CREDIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    curr_cyc_debit: Optional[Decimal] = Field(
        default=None,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Current-cycle debits (ACCT-CURR-CYC-DEBIT PIC S9(10)V99); "
            "NUMERIC(12,2) exact Decimal."
        ),
    )
    group_id: Optional[str] = Field(
        default=None,
        max_length=GROUP_ID_MAX_LENGTH,
        description="Disclosure group id (ACCT-GROUP-ID PIC X(10)); logical ref to disclosure_group.",
    )

    @field_validator("active_status")
    @classmethod
    def ValidateActiveStatus(cls, value: Optional[str]) -> Optional[str]:
        """Validate ``active_status`` is ``'Y'`` or ``'N'`` when supplied."""
        return _ValidateActiveStatus(value)

    @field_validator(
        "curr_bal",
        "credit_limit",
        "cash_credit_limit",
        "curr_cyc_credit",
        "curr_cyc_debit",
        mode="before",
    )
    @classmethod
    def CoerceMoney(cls, value: object) -> object:
        """Coerce a supplied money field to an exact ``Decimal`` and reject float."""
        return _CoerceMoneyValue(value)

    @field_validator("expiration_date", "reissue_date", mode="before")
    @classmethod
    def ParseDates(cls, value: object) -> Optional[date]:
        """Validate/parse a supplied legacy X(10) date field into a native ``date``."""
        return _ParseAccountDate(value)

    @field_validator("credit_limit", "cash_credit_limit")
    @classmethod
    def CheckLimitNonNegative(
        cls, value: Optional[Decimal], info: ValidationInfo
    ) -> Optional[Decimal]:
        """Reject a negative credit or cash-advance limit (QA finding I5).

        The five monetary fields are signed ``PIC S9(10)V99`` in ``CVACT01Y``,
        but a *limit* is a business ceiling that can never be negative, so the
        two limit fields carry a nonnegative bound while balances and cycle
        accumulators stay signed. This runs after :meth:`CoerceMoney` has
        produced an exact :class:`decimal.Decimal`, and only on a supplied value
        (an omitted optional field is ``None`` and is left to the partial-update
        path). A negative limit raises the legacy-style
        ``"{field} must not be negative."`` edit via
        :func:`app.utils.validators.ValidateNonNegative`, which Pydantic
        surfaces as HTTP 422 -- closing the gap where a negative ``credit_limit``
        was previously accepted and persisted.

        Args:
            value: The coerced limit value, or ``None`` when the field was
                omitted from the partial update.
            info: Pydantic validation context; ``info.field_name`` selects the
                human-readable label so the message names the specific limit.

        Returns:
            The unmodified ``value`` when it is absent or nonnegative.

        Raises:
            ValueError: When a supplied limit value is negative.
        """
        if value is None:
            return value
        fieldLabel = LIMIT_FIELD_LABELS.get(info.field_name, info.field_name)
        result = validators.ValidateNonNegative(fieldLabel, value)
        if not result.isValid:
            raise ValueError(result.message)
        return value


class AccountDetail(AccountRead):
    """Combined account + customer view for ``COACTVW`` (transaction ``CAVW``).

    Extends :class:`AccountRead` with the owning :class:`CustomerRead`, matching
    the legacy view screen that shows the account and its customer together
    (the account is joined to its customer through the card cross-reference).
    This is the primary payload for ``GET /accounts/{acctId}``.

    Because :class:`AccountRead` inherits ``from_attributes=True``, the whole
    object -- including the nested ``customer`` -- is populated from an ORM-like
    object via ``AccountDetail.model_validate(orm_account)`` when the service
    has attached the resolved customer to the account (the ``customer``
    attribute is read the same way as every other field). ``customer`` is
    required because the view is never rendered without its customer.
    """

    customer: CustomerRead = Field(
        ...,
        description="Owning customer (joined via the card cross-reference); masked CustomerRead projection.",
    )

