"""Bill-payment DTOs. Sources: app/cpy-bms/COBIL00.CPY, app/cpy/CVACT01Y.cpy. F-006: available_credit = credit_limit - curr_bal.

Pydantic v2 request/response schemas for the Bill Payment feature (CICS
transaction ``CB00``, legacy online program ``COBIL00C``). The request DTO
mirrors the unprotected input fields of the BMS bill-pay screen
``app/cpy-bms/COBIL00.CPY`` (symbolic map ``COBIL0AI``); the response DTO carries
the balances and limits sourced from the account record
``app/cpy/CVACT01Y.cpy`` together with the F-006 available-credit result.

Business rule -- F-006 available credit (AAP section 0.8.1)::

    available_credit = credit_limit - curr_bal

This bill-payment formula is DISTINCT from the batch posting over-limit rule
(AAP section 0.7.3, reject code 102), which instead uses
``ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`` compared against
``ACCT-CREDIT-LIMIT``. The two rules are intentionally kept separate and live in
different modules; this schema preserves only the F-006 formula. The
``available_credit`` value itself is computed by ``billpay_service`` (which owns
the account read and the payment posting); this DTO simply carries the
already-computed result.

Numeric fidelity (AAP section 0.7.1): every monetary field (``curr_bal``,
``credit_limit``, ``available_credit``, ``payment_amount``) is an exact
:class:`decimal.Decimal` mapped to ``NUMERIC(12,2)`` (the account money fields
are ``PIC S9(10)V99`` = 10 integer + 2 fractional digits). ``float`` is NEVER
used: each money field routes its raw input through
:func:`app.utils.decimal_utils.ToDecimal`, which rejects ``float`` (and
``bool``) so binary floating-point rounding can never contaminate a currency
value -- a regulatory correctness requirement.

Verified COBIL00 input fields (``app/cpy-bms/COBIL00.CPY``, REFERENCE only)::

    ACTIDINI PIC X(11) -> acct_id  (str, exactly 11 digits; leading zeros kept)
    CURBALI  PIC X(14) -> current balance display (the server derives the real
                          balance from the account; it is NOT trusted from the
                          client, so no request field mirrors CURBALI)
    CONFIRMI PIC X(01) -> confirm  (str, 'Y'/'N')

Naming conventions (Ochs Rule, AAP section 0.8.3): data field names stay
snake_case (the DTO / JSON / ORM-column contract shared with
``frontend/src/types/billpay.ts``), while class and validator method names are
PascalCase and module-level constants are ALL_UPPERCASE.
"""

# Ported from COBOL online program COBIL00C, BMS map COBIL00 (app/cpy-bms/
# COBIL00.CPY), and account copybook CVACT01Y (app/cpy/CVACT01Y.cpy). The F-006
# available-credit rule (credit_limit - curr_bal) is preserved verbatim per the
# Minimal Change Clause (AAP section 0.8.1); it is deliberately NOT conflated
# with the batch posting over-limit rule (reject code 102, AAP section 0.7.3).

from decimal import Decimal
from typing import Optional

from pydantic import Field, field_validator

from app.schemas.common import OrmBase, RequestBase
from app.utils import decimal_utils, validators

__all__ = ["BillPayRequest", "BillPayResponse"]


# ---------------------------------------------------------------------------
# Field constraints and labels (Ochs Rule section 0.8.2: constants are
# ALL_UPPERCASE with underscores). Lengths and the monetary precision are
# sourced from the shared util modules so this schema stays in lock-step with
# the single source of truth rather than restating magic numbers.
# ---------------------------------------------------------------------------

# ACCT-ID PIC 9(11): an 11-digit account identifier held as a fixed-width string
# so the significant leading zeros are preserved (never parsed to int).
ACCT_ID_LENGTH = validators.ACCT_ID_LENGTH
ACCT_ID_LABEL = "Account ID"

# CONFIRMI PIC X(01): a single-character confirmation flag ('Y' or 'N').
CONFIRM_MAX_LENGTH = 1
CONFIRM_LABEL = "Confirmation flag"

# TRAN-ID PIC X(16): identifier of the posted bill-payment transaction returned
# to the caller as the confirmation reference.
TRAN_ID_MAX_LENGTH = validators.TRAN_ID_LENGTH

# NUMERIC(12,2) for every account money field: ACCT-CURR-BAL / ACCT-CREDIT-LIMIT
# are PIC S9(10)V99 -> 10 integer + 2 fractional digits = 12 total digits, two
# decimal places. MONEY_DECIMAL_PLACES reuses decimal_utils.MONEY_SCALE so the
# fractional precision stays aligned with the shared monetary scale.
MONEY_MAX_DIGITS = decimal_utils.ACCOUNT_MONEY_DIGITS
MONEY_DECIMAL_PLACES = decimal_utils.MONEY_SCALE

# Reused failure wording for the monetary coercion guard (float rejection).
MSG_MONEY_NOT_DECIMAL = (
    "monetary fields must be an exact decimal (str, int, or Decimal); "
    "float is not accepted to preserve NUMERIC(12,2) precision"
)


# ---------------------------------------------------------------------------
# Private helper (module-internal; not part of the public API).
# ---------------------------------------------------------------------------


def _CoerceMoneyValue(rawValue: object) -> object:
    """Coerce a monetary input to an exact ``Decimal`` and reject ``float``.

    Shared by the request and response money-field validators so the
    float-rejection rule is expressed exactly once (DRY, and Ochs "small
    methods"). A ``None`` is passed through untouched so Pydantic can apply the
    field's own required/optional handling; any present value is routed through
    :func:`app.utils.decimal_utils.ToDecimal`, which accepts ``str`` / ``int`` /
    ``Decimal`` (including the ``Decimal`` a PostgreSQL ``NUMERIC`` column
    yields) and rejects ``float`` and ``bool``.

    Args:
        rawValue: The raw money input from a request payload or an ORM row.

    Returns:
        A :class:`decimal.Decimal` for numeric input, or the original ``None``.

    Raises:
        ValueError: If ``rawValue`` is a ``float`` / ``bool`` or an unparseable
            string. The underlying ``TypeError`` / ``ValueError`` from
            :func:`app.utils.decimal_utils.ToDecimal` is re-raised as a
            ``ValueError`` so Pydantic surfaces a clean ``ValidationError``
            (Pydantic does not wrap a raw ``TypeError``).
    """
    if rawValue is None:
        return rawValue
    try:
        return decimal_utils.ToDecimal(rawValue)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{MSG_MONEY_NOT_DECIMAL} ({exc})") from exc


class BillPayRequest(RequestBase):
    """Request DTO for the bill-payment operation (COBIL00 / transaction CB00).

    Mirrors the unprotected input fields of the legacy bill-pay screen: the
    account id (``ACTIDINI``) and the confirmation flag (``CONFIRMI``). Inherits
    :class:`app.schemas.common.RequestBase`, so unexpected fields are rejected
    (``extra="forbid"``) and surrounding whitespace is stripped from string
    inputs before validation -- the input-sanitization posture required by the
    Ochs Rule.

    The classic COBIL00 screen pays the FULL current balance and has no
    partial-payment field: its only unprotected inputs are the account id
    (``ACTIDINI PIC X(11)``) and the confirmation flag (``CONFIRMI PIC X(01)``).
    This DTO mirrors that contract exactly (Minimal Change Clause, AAP section
    0.8.1) and therefore carries NO ``payment_amount`` field -- the service
    always pays the full outstanding balance (``MOVE ACCT-CURR-BAL TO TRAN-AMT``
    in COBIL00C ``PROCESS-ENTER-KEY``). Because :class:`RequestBase` sets
    ``extra="forbid"``, a client that submits a ``payment_amount`` (or any other
    unexpected field) is rejected with an HTTP 422 rather than having the value
    silently accepted and ignored -- the honest-contract posture the Ochs Rule's
    input-sanitization directive requires.

    Attributes:
        acct_id: Account identifier (legacy ``ACTIDINI PIC X(11)`` over
            ``ACCT-ID PIC 9(11)``); exactly 11 ASCII digits, leading zeros kept.
        confirm: Confirmation flag (legacy ``CONFIRMI PIC X(01)``); the
            uppercase literal ``'Y'`` or ``'N'`` only, matching the legacy
            88-level ``FLG-YES-NO-ISVALID``.
    """

    acct_id: str = Field(
        ...,
        min_length=ACCT_ID_LENGTH,
        max_length=ACCT_ID_LENGTH,
        description=(
            "Account id to pay (ACTIDINI PIC X(11) / ACCT-ID PIC 9(11)); exactly 11 digits, leading zeros preserved."
        ),
    )
    confirm: str = Field(
        ...,
        max_length=CONFIRM_MAX_LENGTH,
        description=("Payment confirmation flag (CONFIRMI PIC X(01)); 'Y' to confirm the payment or 'N' to decline."),
    )

    @field_validator("acct_id")
    @classmethod
    def ValidateAcctId(cls, rawValue: str) -> str:
        """Validate that ``acct_id`` is exactly 11 ASCII digits.

        Delegates to :func:`app.utils.validators.ValidateNumericId` so the
        accept/reject behavior (digits only, exact length, leading zeros kept)
        and the failure wording are identical to the legacy field edit. The
        value is returned unchanged on success so the zero-padded string is
        preserved verbatim.

        Args:
            rawValue: The candidate account identifier (already whitespace
                stripped by :class:`~app.schemas.common.RequestBase`).

        Returns:
            The validated account identifier, unchanged.

        Raises:
            ValueError: If the value is not exactly ``ACCT_ID_LENGTH`` digits;
                surfaced by Pydantic as a ``ValidationError``.
        """
        result = validators.ValidateNumericId(ACCT_ID_LABEL, rawValue, ACCT_ID_LENGTH)
        if not result.isValid:
            raise ValueError(result.message)
        return rawValue

    @field_validator("confirm")
    @classmethod
    def ValidateConfirm(cls, rawValue: str) -> str:
        """Validate that ``confirm`` is the uppercase literal ``'Y'`` or ``'N'``.

        Delegates to :func:`app.utils.validators.ValidateYesNo`, which is
        intentionally case-sensitive (the legacy 88-level ``FLG-YES-NO-ISVALID``
        accepts uppercase ``'Y'``/``'N'`` only), preserving the exact legacy
        accept/reject behavior.

        Args:
            rawValue: The candidate confirmation flag (already whitespace
                stripped by :class:`~app.schemas.common.RequestBase`).

        Returns:
            The validated confirmation flag, unchanged.

        Raises:
            ValueError: If the value is not exactly ``'Y'`` or ``'N'``; surfaced
                by Pydantic as a ``ValidationError``.
        """
        result = validators.ValidateYesNo(CONFIRM_LABEL, rawValue)
        if not result.isValid:
            raise ValueError(result.message)
        return rawValue


class BillPayResponse(OrmBase):
    """Response DTO describing the outcome of a bill-payment operation.

    Built by ``billpay_service`` after it reads the account, applies the
    payment, and posts the payment transaction. Inherits
    :class:`app.schemas.common.OrmBase` (``from_attributes=True``) so it can be
    produced either by direct construction with the computed values or via
    ``BillPayResponse.model_validate(orm_obj)``. The field set matches
    ``frontend/src/types/billpay.ts`` one-for-one.

    The ``available_credit`` value is the F-006 result ``credit_limit -
    curr_bal`` (AAP section 0.8.1). The subtraction itself is performed in
    ``billpay_service`` (which owns the account state); this DTO only carries the
    already-computed :class:`decimal.Decimal` so the client can display it. All
    four money fields are exact ``NUMERIC(12,2)`` decimals and are never
    ``float``.

    Attributes:
        acct_id: Account identifier the payment was applied to
            (``ACCT-ID PIC 9(11)``); 11-digit string, leading zeros kept.
        curr_bal: Account current balance AFTER the payment was applied
            (``ACCT-CURR-BAL PIC S9(10)V99``), NUMERIC(12,2). May be negative
            when the account carries a credit balance.
        credit_limit: Account credit limit (``ACCT-CREDIT-LIMIT PIC S9(10)V99``),
            NUMERIC(12,2).
        available_credit: F-006 available credit = ``credit_limit - curr_bal``,
            NUMERIC(12,2). Negative when the balance exceeds the credit limit.
        payment_amount: The amount actually applied by the payment,
            NUMERIC(12,2).
        tran_id: Identifier of the posted payment transaction
            (``TRAN-ID PIC X(16)``), returned as the confirmation reference.
            ``None`` when no transaction was posted (for example a declined
            confirmation).
        message: Optional human-readable status/confirmation message (modern
            equivalent of the ``ERRMSGO`` / thank-you screen text). ``None`` when
            there is no message to surface.
    """

    acct_id: str = Field(
        ...,
        max_length=ACCT_ID_LENGTH,
        description=("Account id the payment was applied to (ACCT-ID PIC 9(11)); leading zeros preserved."),
    )
    curr_bal: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "Current balance after the payment (ACCT-CURR-BAL PIC S9(10)V99); "
            "NUMERIC(12,2), may be negative for a credit balance."
        ),
    )
    credit_limit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=("Account credit limit (ACCT-CREDIT-LIMIT PIC S9(10)V99); NUMERIC(12,2)."),
    )
    available_credit: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description=(
            "F-006 available credit = credit_limit - curr_bal (NUMERIC(12,2)); "
            "computed by billpay_service and carried here. Negative when the "
            "balance exceeds the credit limit."
        ),
    )
    payment_amount: Decimal = Field(
        ...,
        max_digits=MONEY_MAX_DIGITS,
        decimal_places=MONEY_DECIMAL_PLACES,
        description="Amount actually applied by the payment (NUMERIC(12,2)).",
    )
    tran_id: Optional[str] = Field(
        default=None,
        max_length=TRAN_ID_MAX_LENGTH,
        description=(
            "Posted payment transaction id (TRAN-ID PIC X(16)) used as the "
            "confirmation reference; None when no transaction was posted."
        ),
    )
    message: Optional[str] = Field(
        default=None,
        description=("Optional human-readable status/confirmation message; None when there is nothing to surface."),
    )

    @field_validator(
        "curr_bal",
        "credit_limit",
        "available_credit",
        "payment_amount",
        mode="before",
    )
    @classmethod
    def CoerceMoney(cls, value: object) -> object:
        """Coerce every money field to an exact ``Decimal`` and reject float.

        Applied before Pydantic's numeric handling to each of the four monetary
        fields so a ``str`` / ``int`` / ``Decimal`` is converted through
        :func:`app.utils.decimal_utils.ToDecimal` while a ``float`` (or
        ``bool``) is rejected -- preventing the binary floating-point rounding
        that lax ``float`` -> ``Decimal`` coercion would otherwise introduce into
        a currency value (AAP section 0.7.1). The ``NUMERIC(12,2)`` field
        constraint then enforces the precision and scale.

        Args:
            value: The raw money input from the service or an ORM row.

        Returns:
            A :class:`decimal.Decimal` for numeric input, or the original
            ``None`` so Pydantic reports the standard "field required" error for
            these non-optional fields.

        Raises:
            ValueError: If the value is a ``float`` / ``bool`` or an unparseable
                string; surfaced by Pydantic as a ``ValidationError``.
        """
        return _CoerceMoneyValue(value)
