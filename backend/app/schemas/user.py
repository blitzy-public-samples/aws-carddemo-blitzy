"""User DTOs. Source: app/cpy/CSUSR01Y.cpy; screen fields from app/cpy-bms/COUSR00-03.CPY.

Pydantic v2 data-transfer objects for the security ``User`` entity that backs
the admin-only user-management screens -- legacy online programs ``COUSR00C``
through ``COUSR03C`` (CICS transactions CU00-CU03). The canonical record layout
is the ``SEC-USER-DATA`` copybook (``app/cpy/CSUSR01Y.cpy``); the per-field
lengths are cross-checked against the BMS symbolic maps for the list, add,
update, and delete screens (``app/cpy-bms/COUSR00.CPY`` .. ``COUSR03.CPY``).

Security invariant (Ochs Rule "no hardcoding secrets"; section 0.1.1 implicit
requirement):
    The legacy ``SEC-USR-PWD PIC X(08)`` is an 8-character *plaintext* password.
    The modern target never stores or returns plaintext. The ``password`` field
    therefore appears ONLY on the write DTOs (:class:`UserCreate` and
    :class:`UserUpdate`), which carry the plaintext inbound to the service
    layer; the actual hashing happens in ``app.core.security`` /
    ``user_admin_service``. Neither ``password`` nor any ``password_hash`` ever
    appears on a read/response DTO (:class:`UserBase`, :class:`UserRead`,
    :class:`UserSummary`).

Copybook mapping (CSUSR01Y -> canonical field -> type):
    * ``SEC-USR-ID     PIC X(08)`` -> ``user_id: str``    (primary key)
    * ``SEC-USR-FNAME  PIC X(20)`` -> ``first_name: str``
    * ``SEC-USR-LNAME  PIC X(20)`` -> ``last_name: str``
    * ``SEC-USR-PWD    PIC X(08)`` -> ``password: str``    (write-only)
    * ``SEC-USR-TYPE   PIC X(01)`` -> ``user_type: str``   ('A' admin / 'U' user)
    * ``SEC-USR-FILLER PIC X(23)`` -> dropped (record-length padding only)

Field-level accept/reject behavior is delegated to :mod:`app.utils.validators`
so it stays identical to the legacy PROCEDURE DIVISION edits.
"""

from typing import Optional

from pydantic import Field, field_validator

from app.schemas.common import OrmBase, RequestBase
from app.utils import validators

__all__ = [
    "UserBase",
    "UserCreate",
    "UserUpdate",
    "UserRead",
    "UserSummary",
]

# ---------------------------------------------------------------------------
# Field-width constants (Ochs Rule: constants are ALL_UPPERCASE).
#
# USER_ID_LENGTH and PASSWORD_LENGTH are reused from app.utils.validators (both
# are 8, from SEC-USR-ID / SEC-USR-PWD) so each width lives in exactly one
# place. The remaining widths come straight from the CSUSR01Y copybook and are
# cross-checked against the COUSR00-03 BMS symbolic maps.
# ---------------------------------------------------------------------------
FIRST_NAME_LENGTH = 20      # SEC-USR-FNAME PIC X(20); COUSR01/02 FNAMEI.
LAST_NAME_LENGTH = 20       # SEC-USR-LNAME PIC X(20); COUSR01/02 LNAMEI.
USER_TYPE_LENGTH = 1        # SEC-USR-TYPE  PIC X(01); COUSR01/02 USRTYPEI.

# ---------------------------------------------------------------------------
# SEC-USR-TYPE domain values. The concrete 'A'/'U' letters also live in
# app.core.dependencies (ADMIN_USER_TYPE / REGULAR_USER_TYPE); they are
# redeclared here rather than imported to keep the schema layer free of any
# dependency on the core layer (which would create a schemas -> core cycle).
# ---------------------------------------------------------------------------
ADMIN_USER_TYPE = "A"
REGULAR_USER_TYPE = "U"
VALID_USER_TYPES = frozenset({ADMIN_USER_TYPE, REGULAR_USER_TYPE})

# ---------------------------------------------------------------------------
# Human-readable field labels used to build legacy-parity validation messages,
# plus the one message template not already provided by app.utils.validators.
# ---------------------------------------------------------------------------
USER_ID_LABEL = "User ID"
FIRST_NAME_LABEL = "First name"
LAST_NAME_LABEL = "Last name"
PASSWORD_LABEL = "Password"
USER_TYPE_LABEL = "User type"

MSG_USER_TYPE_INVALID = "{field} must be 'A' (admin) or 'U' (regular user)."


# ---------------------------------------------------------------------------
# Private field-edit helpers. Each raises ``ValueError`` (which Pydantic surfaces
# as a validation error) carrying the legacy-parity message, and returns the
# unmodified value on success. The logic lives here once and is shared by the
# write DTOs below, so UserCreate and UserUpdate never duplicate a rule.
# ---------------------------------------------------------------------------


def _RequireUserId(value: str) -> str:
    """Validate SEC-USR-ID: mandatory and alphanumeric (COUSR01 USERIDI).

    The width bound (<= 8) is enforced declaratively by the field's
    ``max_length``; this helper reproduces the legacy required-then-alphanumeric
    edit (1215-EDIT-MANDATORY followed by 1230-EDIT-ALPHANUM-REQD).

    Args:
        value: The candidate user id, already whitespace-stripped by the model.

    Returns:
        The original ``value`` unchanged when it passes every edit.

    Raises:
        ValueError: When the value is blank or contains a disallowed character.
    """
    requiredResult = validators.ValidateRequired(USER_ID_LABEL, value)
    if not requiredResult.isValid:
        raise ValueError(requiredResult.message)
    formatResult = validators.ValidateAlphanumeric(USER_ID_LABEL, value)
    if not formatResult.isValid:
        raise ValueError(formatResult.message)
    return value


def _RequireName(fieldLabel: str, value: str) -> str:
    """Validate a mandatory name field (SEC-USR-FNAME / SEC-USR-LNAME).

    Args:
        fieldLabel: Human-readable label used to build the failure message.
        value: The candidate name, already whitespace-stripped by the model.

    Returns:
        The original ``value`` unchanged when it is present.

    Raises:
        ValueError: When the value is blank.
    """
    requiredResult = validators.ValidateRequired(fieldLabel, value)
    if not requiredResult.isValid:
        raise ValueError(requiredResult.message)
    return value


def _RequireUserType(value: str) -> str:
    """Validate SEC-USR-TYPE: mandatory, single character, 'A' or 'U'.

    The single-character width is enforced declaratively by the field's
    ``max_length``; this helper adds the required check and the 'A'/'U' domain
    check (admin vs regular user).

    Args:
        value: The candidate user-type flag, already whitespace-stripped.

    Returns:
        The original ``value`` unchanged when it is a recognized type.

    Raises:
        ValueError: When the value is blank or is neither 'A' nor 'U'.
    """
    requiredResult = validators.ValidateRequired(USER_TYPE_LABEL, value)
    if not requiredResult.isValid:
        raise ValueError(requiredResult.message)
    if value not in VALID_USER_TYPES:
        raise ValueError(MSG_USER_TYPE_INVALID.format(field=USER_TYPE_LABEL))
    return value


def _RequirePassword(value: str) -> str:
    """Validate SEC-USR-PWD on write: mandatory plaintext (never stored as-is).

    The plaintext is only carried inbound; hashing happens in the service /
    security layer. The width bound (<= 8, the legacy field width) is enforced
    by the field's ``max_length`` and is deliberately not widened here.

    Args:
        value: The candidate plaintext password, already whitespace-stripped.

    Returns:
        The original ``value`` unchanged when it is present.

    Raises:
        ValueError: When the value is blank.
    """
    requiredResult = validators.ValidateRequired(PASSWORD_LABEL, value)
    if not requiredResult.isValid:
        raise ValueError(requiredResult.message)
    return value


# ---------------------------------------------------------------------------
# Read / response DTOs (OrmBase family). These carry NO password of any kind and
# are safe to build from an ORM ``User`` row via ``model_validate(orm_obj)``.
# No strict custom validator runs on this family, so already-persisted rows
# always deserialize cleanly regardless of legacy data quirks.
# ---------------------------------------------------------------------------


class UserBase(OrmBase):
    """Shared read-safe projection of a security user (no password field).

    Defines the four non-secret columns common to every user response. The
    per-field ``max_length`` mirrors the CSUSR01Y copybook widths and doubles as
    documentation of the underlying column sizes.
    """

    # SEC-USR-ID PIC X(08): the fixed-width user id and primary key. Kept as a
    # string so any leading zero or alphanumeric code is preserved verbatim.
    user_id: str = Field(
        ...,
        max_length=validators.USER_ID_LENGTH,
        description="User id (SEC-USR-ID, X(08)); primary key.",
    )
    # SEC-USR-FNAME PIC X(20): given name.
    first_name: str = Field(
        ...,
        max_length=FIRST_NAME_LENGTH,
        description="Given name (SEC-USR-FNAME, X(20)).",
    )
    # SEC-USR-LNAME PIC X(20): family name.
    last_name: str = Field(
        ...,
        max_length=LAST_NAME_LENGTH,
        description="Family name (SEC-USR-LNAME, X(20)).",
    )
    # SEC-USR-TYPE PIC X(01): 'A' = admin, 'U' = regular user.
    user_type: str = Field(
        ...,
        max_length=USER_TYPE_LENGTH,
        description="User type (SEC-USR-TYPE, X(01)): 'A' admin, 'U' regular.",
    )


class UserRead(UserBase):
    """Single-user response DTO (GET /admin/users/{userId}; COUSR02 view load).

    Inherits exactly the four read-safe fields from :class:`UserBase`; it
    intentionally exposes neither ``password`` nor any ``password_hash``.
    """


class UserSummary(UserBase):
    """Row projection for the paginated user list (COUSR00; GET /admin/users).

    Carries the same read-safe shape as :class:`UserRead`, declared as its own
    type so the list endpoint can return ``PaginatedResponse[UserSummary]``
    distinctly from the single-record ``UserRead`` response.
    """


# ---------------------------------------------------------------------------
# Write / request DTOs (RequestBase family; extra="forbid" rejects unknown
# fields as an input-sanitization guard). ``password`` appears here ONLY,
# carrying plaintext inbound to the service layer where it is hashed.
# ---------------------------------------------------------------------------


class UserCreate(RequestBase):
    """Add-user request DTO. Mirrors the COUSR01 add-user screen (COUSR01C).

    All five screen fields are mandatory: the user id, first and last name, the
    plaintext password (hashed downstream, never persisted as-is), and the user
    type.
    """

    user_id: str = Field(
        ...,
        max_length=validators.USER_ID_LENGTH,
        description="User id (SEC-USR-ID, X(08)); primary key.",
    )
    first_name: str = Field(
        ...,
        max_length=FIRST_NAME_LENGTH,
        description="Given name (SEC-USR-FNAME, X(20)).",
    )
    last_name: str = Field(
        ...,
        max_length=LAST_NAME_LENGTH,
        description="Family name (SEC-USR-LNAME, X(20)).",
    )
    password: str = Field(
        ...,
        max_length=validators.PASSWORD_LENGTH,
        description="Plaintext password (SEC-USR-PWD, X(08)); hashed downstream.",
    )
    user_type: str = Field(
        ...,
        max_length=USER_TYPE_LENGTH,
        description="User type (SEC-USR-TYPE, X(01)): 'A' admin, 'U' regular.",
    )

    @field_validator("user_id")
    @classmethod
    def _CheckUserId(cls, value: str) -> str:
        """Apply the required + alphanumeric edit to ``user_id``."""
        return _RequireUserId(value)

    @field_validator("first_name")
    @classmethod
    def _CheckFirstName(cls, value: str) -> str:
        """Apply the mandatory edit to ``first_name``."""
        return _RequireName(FIRST_NAME_LABEL, value)

    @field_validator("last_name")
    @classmethod
    def _CheckLastName(cls, value: str) -> str:
        """Apply the mandatory edit to ``last_name``."""
        return _RequireName(LAST_NAME_LABEL, value)

    @field_validator("password")
    @classmethod
    def _CheckPassword(cls, value: str) -> str:
        """Apply the mandatory edit to the inbound plaintext ``password``."""
        return _RequirePassword(value)

    @field_validator("user_type")
    @classmethod
    def _CheckUserType(cls, value: str) -> str:
        """Apply the required + 'A'/'U' domain edit to ``user_type``."""
        return _RequireUserType(value)


class UserUpdate(RequestBase):
    """Update-user request DTO. Mirrors the COUSR02 update-user screen (COUSR02C).

    ``user_id`` is the immutable key supplied as a path parameter, so it is not
    part of the request body. First name, last name, and user type are mandatory
    (the update screen re-sends every value). ``password`` is optional: when
    omitted the stored hash is left unchanged, and only a supplied value is
    re-hashed downstream.
    """

    first_name: str = Field(
        ...,
        max_length=FIRST_NAME_LENGTH,
        description="Given name (SEC-USR-FNAME, X(20)).",
    )
    last_name: str = Field(
        ...,
        max_length=LAST_NAME_LENGTH,
        description="Family name (SEC-USR-LNAME, X(20)).",
    )
    user_type: str = Field(
        ...,
        max_length=USER_TYPE_LENGTH,
        description="User type (SEC-USR-TYPE, X(01)): 'A' admin, 'U' regular.",
    )
    password: Optional[str] = Field(
        default=None,
        max_length=validators.PASSWORD_LENGTH,
        description=(
            "Optional new plaintext password (SEC-USR-PWD, X(08)); omit to "
            "leave the current password unchanged."
        ),
    )

    @field_validator("first_name")
    @classmethod
    def _CheckFirstName(cls, value: str) -> str:
        """Apply the mandatory edit to ``first_name``."""
        return _RequireName(FIRST_NAME_LABEL, value)

    @field_validator("last_name")
    @classmethod
    def _CheckLastName(cls, value: str) -> str:
        """Apply the mandatory edit to ``last_name``."""
        return _RequireName(LAST_NAME_LABEL, value)

    @field_validator("user_type")
    @classmethod
    def _CheckUserType(cls, value: str) -> str:
        """Apply the required + 'A'/'U' domain edit to ``user_type``."""
        return _RequireUserType(value)

    @field_validator("password")
    @classmethod
    def _CheckPassword(cls, value: Optional[str]) -> Optional[str]:
        """Validate ``password`` only when supplied; ``None`` leaves it unchanged."""
        if value is None:
            return None
        return _RequirePassword(value)
