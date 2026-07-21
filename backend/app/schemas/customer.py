"""Customer DTOs. Source: app/cpy/CVCUS01Y.cpy; screen edits from app/cpy-bms/COACTVW.CPY (view) and app/cpy-bms/COACTUP.CPY (update).

Pydantic v2 request/response schemas (DTOs) for the Customer entity. Field
names, lengths, and types are ported 1:1 from the legacy COBOL copybook
``app/cpy/CVCUS01Y.cpy`` (``01 CUSTOMER-RECORD``, fixed record length 500). The
screen-level edits (mandatory-ness, SSN split ``3-2-4``, date-of-birth split
``YYYY/MM/DD``, FICO width) are taken from the BMS symbolic-map copybooks
``app/cpy-bms/COACTVW.CPY`` (the account/customer *view* screen ``COACTVW``) and
``app/cpy-bms/COACTUP.CPY`` (the account/customer *update* screen ``COACTUP``).

Traceability (legacy COBOL sources, REFERENCE only -- never modified):
    * ``app/cpy/CVCUS01Y.cpy`` -- CUSTOMER-RECORD (RECLN 500). Field mapping:
        - CUST-ID                  PIC 9(09)  -> ``cust_id``            (str, 9)
        - CUST-FIRST-NAME          PIC X(25)  -> ``first_name``         (str, <=25)
        - CUST-MIDDLE-NAME         PIC X(25)  -> ``middle_name``        (str, <=25, opt)
        - CUST-LAST-NAME           PIC X(25)  -> ``last_name``          (str, <=25)
        - CUST-ADDR-LINE-1         PIC X(50)  -> ``addr_line_1``        (str, <=50)
        - CUST-ADDR-LINE-2         PIC X(50)  -> ``addr_line_2``        (str, <=50, opt)
        - CUST-ADDR-LINE-3         PIC X(50)  -> ``addr_line_3``        (str, <=50, opt)
        - CUST-ADDR-STATE-CD       PIC X(02)  -> ``addr_state_cd``      (str, <=2)
        - CUST-ADDR-COUNTRY-CD     PIC X(03)  -> ``addr_country_cd``    (str, <=3)
        - CUST-ADDR-ZIP            PIC X(10)  -> ``addr_zip``           (str, <=10)
        - CUST-PHONE-NUM-1         PIC X(15)  -> ``phone_num_1``        (str, <=15)
        - CUST-PHONE-NUM-2         PIC X(15)  -> ``phone_num_2``        (str, <=15, opt)
        - CUST-SSN                 PIC 9(09)  -> ``ssn``                (str, 9; MASKED in Read)
        - CUST-GOVT-ISSUED-ID      PIC X(20)  -> ``govt_issued_id``     (str, <=20)
        - CUST-DOB-YYYY-MM-DD      PIC X(10)  -> ``date_of_birth``      (date, ISO)
        - CUST-EFT-ACCOUNT-ID      PIC X(10)  -> ``eft_account_id``     (str, <=10, opt)
        - CUST-PRI-CARD-HOLDER-IND PIC X(01)  -> ``pri_card_holder_ind`` (str, <=1)
        - CUST-FICO-CREDIT-SCORE   PIC 9(03)  -> ``fico_credit_score``  (int, 0..999)
        - FILLER                   PIC X(168) -> dropped (record-length padding only)

Sensitive-data handling (AAP §0.7.8): ``ssn`` is stored in full on the ORM
model but is **always masked** in the :class:`CustomerRead` response (only the
last four digits are exposed). ``cvv`` and ``card_num`` are card attributes and
are intentionally absent from this customer module.

The four exported classes cover the full REST contract for the customer entity:
:class:`CustomerBase` (the raw, full field carrier used for create/internal
input), :class:`CustomerRead` (the masked response DTO built from the ORM
``Customer`` row via ``from_attributes``), :class:`CustomerUpdate` (the partial
``COACTUP`` maintenance payload), and :class:`CustomerSummary` (a compact
``cust_id`` + name projection for embedding in browse lists).
"""

# Ported from COBOL copybook CVCUS01Y (CUSTOMER-RECORD) with screen edits from
# the BMS symbolic maps COACTVW (view) and COACTUP (update). Naming-convention
# resolution per AAP §0.8.3: data field names stay snake_case (the DTO / JSON /
# ORM-column contract, an intentional exception to the Ochs Rule), while classes
# and methods are PascalCase and module-level constants are ALL_UPPERCASE with
# underscores. Validation reuses the shared field-edit helpers in
# ``app.utils.validators`` and the legacy date logic in ``app.utils.date_utils``
# so the modern accept/reject behavior matches the mainframe exactly.

from datetime import date, datetime
from typing import Optional

from pydantic import Field, field_serializer, field_validator

from app.schemas.common import OrmBase, RequestBase
from app.utils import date_utils, validators

__all__ = [
    "CustomerBase",
    "CustomerRead",
    "CustomerUpdate",
    "CustomerSummary",
]

# ---------------------------------------------------------------------------
# Field lengths and constraints (Ochs Rule §0.8.2: constants are ALL_UPPERCASE
# with underscores), ported directly from the CVCUS01Y field pictures. Numeric
# identifiers (cust_id, ssn) preserve leading zeros and are therefore modeled as
# fixed-length digit STRINGS, never converted to int. CUST_ID_LENGTH and
# SSN_LENGTH are sourced from ``app.utils.validators`` so the schema constraint
# and the shared field edit stay in lock-step.
# ---------------------------------------------------------------------------
CUST_ID_LENGTH = validators.CUST_ID_LENGTH        # CUST-ID PIC 9(09).
SSN_LENGTH = validators.SSN_LENGTH                # CUST-SSN PIC 9(09).
NAME_MAX_LENGTH = 25                              # CUST-*-NAME PIC X(25).
ADDR_LINE_MAX_LENGTH = 50                         # CUST-ADDR-LINE-n PIC X(50).
ADDR_STATE_CD_MAX_LENGTH = 2                      # CUST-ADDR-STATE-CD PIC X(02).
ADDR_COUNTRY_CD_MAX_LENGTH = 3                    # CUST-ADDR-COUNTRY-CD PIC X(03).
ADDR_ZIP_MAX_LENGTH = 10                          # CUST-ADDR-ZIP PIC X(10).
PHONE_NUM_MAX_LENGTH = 15                         # CUST-PHONE-NUM-n PIC X(15).
GOVT_ISSUED_ID_MAX_LENGTH = 20                    # CUST-GOVT-ISSUED-ID PIC X(20).
EFT_ACCOUNT_ID_MAX_LENGTH = 10                    # CUST-EFT-ACCOUNT-ID PIC X(10).
PRI_CARD_HOLDER_IND_MAX_LENGTH = 1                # CUST-PRI-CARD-HOLDER-IND PIC X(01).

# CUST-FICO-CREDIT-SCORE PIC 9(03): a three-digit numeric field. MINIMAL CHANGE
# CLAUSE (AAP §0.8.1) -- the legacy COBOL applies NO range check to this field
# anywhere, so the ONLY constraint faithful to the mainframe is the 3-digit
# domain 0..999. A conventional "valid FICO range" of 300..850 is DELIBERATELY
# NOT imposed here: adding it would be an unauthorized behavioral improvement
# forbidden by the Minimal Change Clause.
FICO_MIN_VALUE = 0
FICO_MAX_VALUE = 999

# SSN masking (AAP §0.7.8): the response exposes only the last four digits. The
# prefix reproduces the conventional masked-SSN presentation ``***-**-1234``.
SSN_VISIBLE_DIGITS = 4
SSN_MASK_PREFIX = "***-**-"
# Fully redacted form used when there are too few digits to reveal a last-four.
SSN_FULLY_MASKED = "***-**-****"

# Human-readable field labels passed to the shared validators purely to build
# legacy-style failure messages; they are not part of the serialized contract.
CUST_ID_LABEL = "Customer ID"
SSN_LABEL = "SSN"
DATE_OF_BIRTH_LABEL = "Date of birth"


def _MaskSsn(rawValue: Optional[str]) -> Optional[str]:
    """Mask a Social Security Number to expose only its last four digits.

    Reproduces the AAP §0.7.8 sensitive-data rule: a customer SSN must never be
    returned in full over the API. The full nine-digit value is stored on the
    ORM row, and this helper renders the masked form (for example
    ``"123456789"`` becomes ``"***-**-6789"``) for every serialized response.

    Args:
        rawValue: The stored SSN text (typically nine digits), or None when the
            customer has no SSN on file.

    Returns:
        None when ``rawValue`` is None; a fully redacted marker when there are
        fewer than four digits to reveal; otherwise the ``***-**-NNNN`` masked
        form exposing only the final four digits.
    """
    if rawValue is None:
        return None
    normalizedValue = str(rawValue).strip()
    if len(normalizedValue) < SSN_VISIBLE_DIGITS:
        return SSN_FULLY_MASKED
    lastFour = normalizedValue[-SSN_VISIBLE_DIGITS:]
    return f"{SSN_MASK_PREFIX}{lastFour}"


def _CoerceDateOfBirthText(rawValue: object) -> str:
    """Render a date-of-birth input as legacy ``YYYY-MM-DD`` text for editing.

    The shared date edits in :mod:`app.utils.date_utils` operate on the legacy
    text layouts, so any incoming ``date`` / ``datetime`` is first formatted
    back to ``YYYY-MM-DD``; every other value is stringified and trimmed.

    Args:
        rawValue: The raw date-of-birth input (``str``, ``date``, ``datetime``,
            or any value the caller supplied).

    Returns:
        The value rendered as ``YYYY-MM-DD`` text (for ``date`` / ``datetime``
        inputs) or the trimmed string form of ``rawValue`` otherwise.
    """
    if isinstance(rawValue, datetime):
        return date_utils.FormatLegacyDate(rawValue.date())
    if isinstance(rawValue, date):
        return date_utils.FormatLegacyDate(rawValue)
    return str(rawValue).strip()


def _ParseDateOfBirth(rawValue: object) -> Optional[date]:
    """Validate and parse a date-of-birth input using the legacy date edits.

    Applies the ported ``EDIT-DATE-OF-BIRTH`` rules via
    :func:`app.utils.validators.ValidateDateOfBirthField` (full CCYYMMDD
    calendar edits plus the "not in the future" backstop) and then converts the
    accepted text into a native :class:`datetime.date` with
    :func:`app.utils.date_utils.ParseLegacyDate`. A blank/None input yields
    None so optional callers can treat "not supplied" distinctly; a required
    field then rejects the None at the type layer.

    Args:
        rawValue: The raw date-of-birth input (``str``, ``date``, ``datetime``,
            or None).

    Returns:
        The parsed :class:`datetime.date`, or None when the input is blank/None.

    Raises:
        ValueError: If the value fails the legacy date-of-birth edits. Only the
            specific :class:`ValueError` is raised (never a bare ``except``) so
            Pydantic surfaces a clean ``ValidationError``.
    """
    if rawValue is None:
        return None
    if isinstance(rawValue, str) and rawValue.strip() == "":
        return None
    dobText = _CoerceDateOfBirthText(rawValue)
    result = validators.ValidateDateOfBirthField(DATE_OF_BIRTH_LABEL, dobText)
    if not result.isValid:
        raise ValueError(result.message)
    return date_utils.ParseLegacyDate(dobText)


class CustomerBase(OrmBase):
    """Full, raw-value carrier for a customer master record (create/internal).

    Declares every business field of the CVCUS01Y ``CUSTOMER-RECORD`` with the
    copybook lengths as ``max_length`` bounds and reproduces the legacy screen
    edits (numeric-id, SSN, and date-of-birth rules) via the shared
    ``app.utils`` helpers. Masking is deliberately **not** applied here -- this
    base carries the raw ``ssn`` used internally and for create input; the
    masked presentation lives only on :class:`CustomerRead`. Inherits
    :class:`app.schemas.common.OrmBase`, so it can also be populated from an ORM
    row via ``from_attributes``.
    """

    cust_id: str = Field(
        ...,
        min_length=CUST_ID_LENGTH,
        max_length=CUST_ID_LENGTH,
        description=(
            "Customer identifier (CUST-ID, PIC 9(09)); a zero-padded 9-digit "
            "string preserved verbatim (never cast to int)."
        ),
    )
    first_name: str = Field(
        ...,
        min_length=1,
        max_length=NAME_MAX_LENGTH,
        description="Customer first name (CUST-FIRST-NAME, PIC X(25)).",
    )
    middle_name: Optional[str] = Field(
        default=None,
        max_length=NAME_MAX_LENGTH,
        description="Customer middle name (CUST-MIDDLE-NAME, PIC X(25)); optional.",
    )
    last_name: str = Field(
        ...,
        min_length=1,
        max_length=NAME_MAX_LENGTH,
        description="Customer last name (CUST-LAST-NAME, PIC X(25)).",
    )
    addr_line_1: str = Field(
        ...,
        min_length=1,
        max_length=ADDR_LINE_MAX_LENGTH,
        description="Address line 1 (CUST-ADDR-LINE-1, PIC X(50)).",
    )
    addr_line_2: Optional[str] = Field(
        default=None,
        max_length=ADDR_LINE_MAX_LENGTH,
        description="Address line 2 (CUST-ADDR-LINE-2, PIC X(50)); optional.",
    )
    addr_line_3: Optional[str] = Field(
        default=None,
        max_length=ADDR_LINE_MAX_LENGTH,
        description="Address line 3 (CUST-ADDR-LINE-3, PIC X(50)); optional.",
    )
    addr_state_cd: str = Field(
        ...,
        min_length=1,
        max_length=ADDR_STATE_CD_MAX_LENGTH,
        description="State code (CUST-ADDR-STATE-CD, PIC X(02)).",
    )
    addr_country_cd: str = Field(
        ...,
        min_length=1,
        max_length=ADDR_COUNTRY_CD_MAX_LENGTH,
        description="Country code (CUST-ADDR-COUNTRY-CD, PIC X(03)).",
    )
    addr_zip: str = Field(
        ...,
        min_length=1,
        max_length=ADDR_ZIP_MAX_LENGTH,
        description="ZIP/postal code (CUST-ADDR-ZIP, PIC X(10)).",
    )
    phone_num_1: str = Field(
        ...,
        min_length=1,
        max_length=PHONE_NUM_MAX_LENGTH,
        description="Primary phone number (CUST-PHONE-NUM-1, PIC X(15)).",
    )
    phone_num_2: Optional[str] = Field(
        default=None,
        max_length=PHONE_NUM_MAX_LENGTH,
        description="Secondary phone number (CUST-PHONE-NUM-2, PIC X(15)); optional.",
    )
    ssn: str = Field(
        ...,
        min_length=SSN_LENGTH,
        max_length=SSN_LENGTH,
        description=(
            "Social Security Number (CUST-SSN, PIC 9(09)); a 9-digit string. "
            "Stored raw here and masked only on CustomerRead responses."
        ),
    )
    govt_issued_id: str = Field(
        ...,
        min_length=1,
        max_length=GOVT_ISSUED_ID_MAX_LENGTH,
        description="Government-issued identifier (CUST-GOVT-ISSUED-ID, PIC X(20)).",
    )
    date_of_birth: date = Field(
        ...,
        description=(
            "Date of birth (CUST-DOB-YYYY-MM-DD, PIC X(10)) as an ISO date; "
            "validated by the legacy EDIT-DATE-OF-BIRTH rules."
        ),
    )
    eft_account_id: Optional[str] = Field(
        default=None,
        max_length=EFT_ACCOUNT_ID_MAX_LENGTH,
        description="EFT account identifier (CUST-EFT-ACCOUNT-ID, PIC X(10)); optional.",
    )
    pri_card_holder_ind: str = Field(
        ...,
        min_length=1,
        max_length=PRI_CARD_HOLDER_IND_MAX_LENGTH,
        description=(
            "Primary card-holder indicator (CUST-PRI-CARD-HOLDER-IND, "
            "PIC X(01)); typically 'Y' or 'N'."
        ),
    )
    # MINIMAL CHANGE CLAUSE (AAP §0.8.1): constrain ONLY to the 3-digit domain
    # 0..999 that PIC 9(03) can physically hold. The legacy program performs NO
    # FICO range check, so a 300..850 "valid FICO" constraint is intentionally
    # NOT added here (that would be an unauthorized behavioral change).
    fico_credit_score: int = Field(
        ...,
        ge=FICO_MIN_VALUE,
        le=FICO_MAX_VALUE,
        description=(
            "FICO credit score (CUST-FICO-CREDIT-SCORE, PIC 9(03)); constrained "
            "only to the 3-digit domain 0..999 per the Minimal Change Clause."
        ),
    )

    @field_validator("cust_id")
    @classmethod
    def ValidateCustId(cls, value: str) -> str:
        """Validate ``cust_id`` as a fixed-length numeric identifier string.

        Delegates to :func:`app.utils.validators.ValidateNumericId` so the
        9-digit, digits-only rule (leading zeros preserved, never cast to int)
        matches the legacy field edit exactly.

        Args:
            value: The candidate customer identifier string.

        Returns:
            The unchanged ``value`` when it is exactly nine ASCII digits.

        Raises:
            ValueError: If the value is not a valid 9-digit numeric identifier.
        """
        result = validators.ValidateNumericId(CUST_ID_LABEL, value, CUST_ID_LENGTH)
        if not result.isValid:
            raise ValueError(result.message)
        return value

    @field_validator("ssn")
    @classmethod
    def ValidateSsn(cls, value: str) -> str:
        """Validate the raw ``ssn`` against the legacy US-SSN edit.

        Delegates to :func:`app.utils.validators.ValidateUsSsn`, which requires
        nine digits and rejects the reserved area numbers ``000``, ``666``, and
        ``900``-``999`` (COACTUPC ``INVALID-SSN-PART1``). The value is returned
        raw; masking happens only when a :class:`CustomerRead` is serialized.

        Args:
            value: The candidate 9-digit SSN string.

        Returns:
            The unchanged raw ``value`` when it passes the SSN edit.

        Raises:
            ValueError: If the value is not a valid 9-digit SSN.
        """
        result = validators.ValidateUsSsn(SSN_LABEL, value)
        if not result.isValid:
            raise ValueError(result.message)
        return value

    @field_validator("date_of_birth", mode="before")
    @classmethod
    def ValidateDateOfBirth(cls, value: object) -> Optional[date]:
        """Validate and parse ``date_of_birth`` using the legacy date edits.

        Runs before Pydantic's own date parsing so the input passes through the
        ported ``EDIT-DATE-OF-BIRTH`` rules (calendar correctness plus the "not
        in the future" backstop) via :func:`_ParseDateOfBirth`.

        Args:
            value: The raw date-of-birth input (``str``, ``date``, or
                ``datetime``).

        Returns:
            The parsed :class:`datetime.date` (a blank input yields None, which
            the required field then rejects at the type layer).

        Raises:
            ValueError: If the value fails the legacy date-of-birth edits.
        """
        return _ParseDateOfBirth(value)


class CustomerRead(OrmBase):
    """Masked response DTO for a single customer, built from the ORM row.

    Populated directly from a SQLAlchemy ``Customer`` instance via
    ``from_attributes`` (inherited from :class:`app.schemas.common.OrmBase`), so
    a repository or service can return ``CustomerRead.model_validate(orm_row)``.

    This is a standalone schema (it does not inherit :class:`CustomerBase`) for
    two deliberate reasons: (1) the ``ssn`` here is **always masked** on
    serialization rather than carried raw, and (2) the read side must tolerate
    the columns the ORM allows to be NULL, so every field other than the
    always-populated ``cust_id``/``first_name``/``last_name``/``addr_line_1`` is
    Optional. No strict input edits run here because the values originate from
    the trusted datastore, not from user input.

    Sensitive data (AAP §0.7.8): ``ssn`` is serialized through
    :func:`_MaskSsn`, so ``model_dump()`` / API responses expose only the last
    four digits (``***-**-NNNN``) and never the full nine-digit value.
    """

    cust_id: str = Field(
        ...,
        max_length=CUST_ID_LENGTH,
        description="Customer identifier (CUST-ID, PIC 9(09)); 9-digit string.",
    )
    first_name: str = Field(
        ...,
        max_length=NAME_MAX_LENGTH,
        description="Customer first name (CUST-FIRST-NAME, PIC X(25)).",
    )
    middle_name: Optional[str] = Field(
        default=None,
        max_length=NAME_MAX_LENGTH,
        description="Customer middle name (CUST-MIDDLE-NAME, PIC X(25)).",
    )
    last_name: str = Field(
        ...,
        max_length=NAME_MAX_LENGTH,
        description="Customer last name (CUST-LAST-NAME, PIC X(25)).",
    )
    addr_line_1: str = Field(
        ...,
        max_length=ADDR_LINE_MAX_LENGTH,
        description="Address line 1 (CUST-ADDR-LINE-1, PIC X(50)).",
    )
    addr_line_2: Optional[str] = Field(
        default=None,
        max_length=ADDR_LINE_MAX_LENGTH,
        description="Address line 2 (CUST-ADDR-LINE-2, PIC X(50)).",
    )
    addr_line_3: Optional[str] = Field(
        default=None,
        max_length=ADDR_LINE_MAX_LENGTH,
        description="Address line 3 (CUST-ADDR-LINE-3, PIC X(50)).",
    )
    addr_state_cd: Optional[str] = Field(
        default=None,
        max_length=ADDR_STATE_CD_MAX_LENGTH,
        description="State code (CUST-ADDR-STATE-CD, PIC X(02)).",
    )
    addr_country_cd: Optional[str] = Field(
        default=None,
        max_length=ADDR_COUNTRY_CD_MAX_LENGTH,
        description="Country code (CUST-ADDR-COUNTRY-CD, PIC X(03)).",
    )
    addr_zip: Optional[str] = Field(
        default=None,
        max_length=ADDR_ZIP_MAX_LENGTH,
        description="ZIP/postal code (CUST-ADDR-ZIP, PIC X(10)).",
    )
    phone_num_1: Optional[str] = Field(
        default=None,
        max_length=PHONE_NUM_MAX_LENGTH,
        description="Primary phone number (CUST-PHONE-NUM-1, PIC X(15)).",
    )
    phone_num_2: Optional[str] = Field(
        default=None,
        max_length=PHONE_NUM_MAX_LENGTH,
        description="Secondary phone number (CUST-PHONE-NUM-2, PIC X(15)).",
    )
    ssn: Optional[str] = Field(
        default=None,
        description=(
            "Social Security Number (CUST-SSN, PIC 9(09)); ALWAYS masked to "
            "'***-**-NNNN' on serialization -- the full value is never returned."
        ),
    )
    govt_issued_id: Optional[str] = Field(
        default=None,
        max_length=GOVT_ISSUED_ID_MAX_LENGTH,
        description="Government-issued identifier (CUST-GOVT-ISSUED-ID, PIC X(20)).",
    )
    date_of_birth: Optional[date] = Field(
        default=None,
        description=(
            "Date of birth (CUST-DOB-YYYY-MM-DD, PIC X(10)) serialized as an "
            "ISO date."
        ),
    )
    eft_account_id: Optional[str] = Field(
        default=None,
        max_length=EFT_ACCOUNT_ID_MAX_LENGTH,
        description="EFT account identifier (CUST-EFT-ACCOUNT-ID, PIC X(10)).",
    )
    pri_card_holder_ind: Optional[str] = Field(
        default=None,
        max_length=PRI_CARD_HOLDER_IND_MAX_LENGTH,
        description=(
            "Primary card-holder indicator (CUST-PRI-CARD-HOLDER-IND, "
            "PIC X(01))."
        ),
    )
    # See CustomerBase: constrained only to the 0..999 3-digit domain per the
    # Minimal Change Clause (no 300..850 FICO range is imposed).
    fico_credit_score: Optional[int] = Field(
        default=None,
        ge=FICO_MIN_VALUE,
        le=FICO_MAX_VALUE,
        description=(
            "FICO credit score (CUST-FICO-CREDIT-SCORE, PIC 9(03)); 3-digit "
            "domain 0..999 only."
        ),
    )

    @field_serializer("ssn")
    def SerializeSsn(self, value: Optional[str]) -> Optional[str]:
        """Serialize ``ssn`` in masked form, exposing only the last four digits.

        Applies to every serialization path (``model_dump()`` and
        ``model_dump(mode="json")``), so a customer's full SSN is never emitted
        in an API response (AAP §0.7.8). The raw value remains on the in-memory
        model instance but is redacted the moment it is serialized.

        Args:
            value: The raw ``ssn`` value stored on the model (or None).

        Returns:
            The masked ``***-**-NNNN`` form, or None when no SSN is present.
        """
        return _MaskSsn(value)


class CustomerUpdate(RequestBase):
    """Partial-update payload for the ``COACTUP`` maintenance screen.

    Represents the editable customer fields a client may submit to update an
    existing record. Every field is Optional so a caller can send only the
    attributes being changed (partial update); a field that is present is still
    edited with the same lengths and legacy rules as :class:`CustomerBase`,
    while an omitted field is left untouched.

    ``cust_id`` is intentionally absent: it is the immutable key supplied as the
    request path parameter, and because this schema inherits
    :class:`app.schemas.common.RequestBase` (``extra="forbid"``), any attempt to
    smuggle ``cust_id`` -- or any other unexpected field -- into the update body
    is rejected outright (input sanitization per the Ochs Rule).
    """

    first_name: Optional[str] = Field(
        default=None,
        min_length=1,
        max_length=NAME_MAX_LENGTH,
        description="Customer first name (CUST-FIRST-NAME, PIC X(25)).",
    )
    middle_name: Optional[str] = Field(
        default=None,
        max_length=NAME_MAX_LENGTH,
        description="Customer middle name (CUST-MIDDLE-NAME, PIC X(25)).",
    )
    last_name: Optional[str] = Field(
        default=None,
        min_length=1,
        max_length=NAME_MAX_LENGTH,
        description="Customer last name (CUST-LAST-NAME, PIC X(25)).",
    )
    addr_line_1: Optional[str] = Field(
        default=None,
        min_length=1,
        max_length=ADDR_LINE_MAX_LENGTH,
        description="Address line 1 (CUST-ADDR-LINE-1, PIC X(50)).",
    )
    addr_line_2: Optional[str] = Field(
        default=None,
        max_length=ADDR_LINE_MAX_LENGTH,
        description="Address line 2 (CUST-ADDR-LINE-2, PIC X(50)).",
    )
    addr_line_3: Optional[str] = Field(
        default=None,
        max_length=ADDR_LINE_MAX_LENGTH,
        description="Address line 3 (CUST-ADDR-LINE-3, PIC X(50)).",
    )
    addr_state_cd: Optional[str] = Field(
        default=None,
        min_length=1,
        max_length=ADDR_STATE_CD_MAX_LENGTH,
        description="State code (CUST-ADDR-STATE-CD, PIC X(02)).",
    )
    addr_country_cd: Optional[str] = Field(
        default=None,
        min_length=1,
        max_length=ADDR_COUNTRY_CD_MAX_LENGTH,
        description="Country code (CUST-ADDR-COUNTRY-CD, PIC X(03)).",
    )
    addr_zip: Optional[str] = Field(
        default=None,
        min_length=1,
        max_length=ADDR_ZIP_MAX_LENGTH,
        description="ZIP/postal code (CUST-ADDR-ZIP, PIC X(10)).",
    )
    phone_num_1: Optional[str] = Field(
        default=None,
        min_length=1,
        max_length=PHONE_NUM_MAX_LENGTH,
        description="Primary phone number (CUST-PHONE-NUM-1, PIC X(15)).",
    )
    phone_num_2: Optional[str] = Field(
        default=None,
        max_length=PHONE_NUM_MAX_LENGTH,
        description="Secondary phone number (CUST-PHONE-NUM-2, PIC X(15)).",
    )
    ssn: Optional[str] = Field(
        default=None,
        min_length=SSN_LENGTH,
        max_length=SSN_LENGTH,
        description="Social Security Number (CUST-SSN, PIC 9(09)); 9-digit string.",
    )
    govt_issued_id: Optional[str] = Field(
        default=None,
        min_length=1,
        max_length=GOVT_ISSUED_ID_MAX_LENGTH,
        description="Government-issued identifier (CUST-GOVT-ISSUED-ID, PIC X(20)).",
    )
    date_of_birth: Optional[date] = Field(
        default=None,
        description=(
            "Date of birth (CUST-DOB-YYYY-MM-DD, PIC X(10)) as an ISO date; "
            "validated by the legacy EDIT-DATE-OF-BIRTH rules when supplied."
        ),
    )
    eft_account_id: Optional[str] = Field(
        default=None,
        max_length=EFT_ACCOUNT_ID_MAX_LENGTH,
        description="EFT account identifier (CUST-EFT-ACCOUNT-ID, PIC X(10)).",
    )
    pri_card_holder_ind: Optional[str] = Field(
        default=None,
        min_length=1,
        max_length=PRI_CARD_HOLDER_IND_MAX_LENGTH,
        description=(
            "Primary card-holder indicator (CUST-PRI-CARD-HOLDER-IND, "
            "PIC X(01))."
        ),
    )
    # See CustomerBase: 0..999 3-digit domain only (no 300..850 FICO range),
    # per the Minimal Change Clause (AAP §0.8.1).
    fico_credit_score: Optional[int] = Field(
        default=None,
        ge=FICO_MIN_VALUE,
        le=FICO_MAX_VALUE,
        description=(
            "FICO credit score (CUST-FICO-CREDIT-SCORE, PIC 9(03)); 3-digit "
            "domain 0..999 only."
        ),
    )

    @field_validator("ssn")
    @classmethod
    def ValidateSsn(cls, value: Optional[str]) -> Optional[str]:
        """Validate ``ssn`` with the legacy US-SSN edit when it is supplied.

        A None value means "not being updated" and passes through untouched; a
        supplied value is checked by
        :func:`app.utils.validators.ValidateUsSsn` exactly as in
        :class:`CustomerBase`.

        Args:
            value: The candidate SSN string, or None when omitted.

        Returns:
            The unchanged ``value`` (None, or a valid 9-digit SSN).

        Raises:
            ValueError: If a supplied value is not a valid 9-digit SSN.
        """
        if value is None:
            return value
        result = validators.ValidateUsSsn(SSN_LABEL, value)
        if not result.isValid:
            raise ValueError(result.message)
        return value

    @field_validator("date_of_birth", mode="before")
    @classmethod
    def ValidateDateOfBirth(cls, value: object) -> Optional[date]:
        """Validate/parse ``date_of_birth`` with the legacy edits when supplied.

        Defers to :func:`_ParseDateOfBirth`, which returns None for a
        blank/omitted value (leaving the field unset for a partial update) and
        otherwise applies the full ``EDIT-DATE-OF-BIRTH`` rules before parsing to
        a native :class:`datetime.date`.

        Args:
            value: The raw date-of-birth input (``str``, ``date``, ``datetime``,
                or None).

        Returns:
            The parsed :class:`datetime.date`, or None when omitted/blank.

        Raises:
            ValueError: If a supplied value fails the legacy date-of-birth edits.
        """
        return _ParseDateOfBirth(value)


class CustomerSummary(OrmBase):
    """Compact customer projection (id + name) for embedding in browse lists.

    A lightweight read model built from the ORM row via ``from_attributes``
    (inherited from :class:`app.schemas.common.OrmBase`). It carries only the
    identity and display-name fields needed when a customer is referenced from
    another entity's list or detail response (for example, alongside an account
    or card), avoiding the transfer of address, SSN, and other sensitive detail.
    """

    cust_id: str = Field(
        ...,
        max_length=CUST_ID_LENGTH,
        description="Customer identifier (CUST-ID, PIC 9(09)); 9-digit string.",
    )
    first_name: str = Field(
        ...,
        max_length=NAME_MAX_LENGTH,
        description="Customer first name (CUST-FIRST-NAME, PIC X(25)).",
    )
    last_name: str = Field(
        ...,
        max_length=NAME_MAX_LENGTH,
        description="Customer last name (CUST-LAST-NAME, PIC X(25)).",
    )
