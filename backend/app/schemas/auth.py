"""Authentication and identity DTOs for the CardDemo sign-on and session flow.

Pydantic v2 data-transfer objects for the sign-on request/response and for the
stateless identity that replaces the legacy CICS ``COCOM01Y`` COMMAREA. In the
mainframe design the sign-on program ``COSGN00C`` read the ``USRSEC`` VSAM file
and then propagated the caller's id and role from program to program by copying
them into the ``CARDDEMO-COMMAREA`` (copybook ``COCOM01Y``). The modern target
is stateless (technical specification sections 0.1.1 and 0.5.5): the login
endpoint returns an identity payload and the id/role travel in a server-side
session cookie or a signed JWT, never in a COMMAREA.

Legacy record layouts (REFERENCE only):
    * ``app/cpy-bms/COSGN00.CPY`` -- the signon mapset (transaction CC00). The
      two user-supplied input fields are ``USERIDI PIC X(8)`` and
      ``PASSWDI PIC X(8)``; they map to :attr:`LoginRequest.user_id` and
      :attr:`LoginRequest.password`.
    * ``app/cpy/COCOM01Y.cpy`` -- CARDDEMO-COMMAREA. ``CDEMO-USER-ID PIC X(08)``
      maps to ``user_id`` and ``CDEMO-USER-TYPE PIC X(01)`` (88-level
      CDEMO-USRTYP-ADMIN VALUE 'A' / CDEMO-USRTYP-USER VALUE 'U') maps to
      ``user_type``. The COMMAREA also carries customer/account/card context
      across screens; that context is not part of the auth identity and is
      intentionally excluded here.
    * ``app/cpy/CSUSR01Y.cpy`` -- SEC-USER-DATA. ``SEC-USR-FNAME PIC X(20)`` and
      ``SEC-USR-LNAME PIC X(20)`` supply the display name returned after
      sign-on, and ``SEC-USR-TYPE PIC X(01)`` supplies the role. The legacy
      ``SEC-USR-PWD PIC X(08)`` plaintext password is NEVER represented on any
      response DTO in this module.

Security (technical specification sections 0.1.1, 0.7.7; Ochs Rule #3):
    * The plaintext ``password`` appears ONLY as inbound input on
      :class:`LoginRequest`. No response schema (:class:`Token`,
      :class:`LoginResponse`, :class:`CurrentUser`) declares a password (or
      password-hash) field, so a credential can never be echoed back. Because
      :class:`app.schemas.common.OrmBase` reads only declared fields when built
      from an ORM object, populating a response from the ``User`` model silently
      ignores its ``password_hash`` attribute.
    * The sample seed credentials (ADMIN001 / USER0001, password PASSWORD) are
      seed-only and are never hardcoded anywhere in this module.

Design constraints honored here:
    * Pydantic v2 only; each class inherits its ``model_config`` from the shared
      base (:class:`~app.schemas.common.RequestBase` for input,
      :class:`~app.schemas.common.OrmBase` for output) rather than redefining it.
    * This module imports only from :mod:`app.schemas.common` and
      :mod:`app.utils.validators`; it never imports from :mod:`app.core`, which
      would introduce a schemas -> core dependency cycle. The identity field
      names are nonetheless aligned with the JWT claims minted in
      ``app.core.security`` (the ``sub`` claim is the ``user_id`` and the
      ``user_type`` claim carries the role), so a decoded token maps cleanly
      onto :class:`CurrentUser`.
    * Field-level edits are delegated to :mod:`app.utils.validators` so the
      accept/reject behavior matches the rest of the backend, and only specific
      exceptions are surfaced (a failed edit becomes a ``ValueError`` that
      Pydantic reports as a clean ``ValidationError``).

Naming follows the Ochs resolution (technical specification section 0.8.3):
class and method names are PascalCase (for example :class:`LoginRequest` and
:meth:`CurrentUser.IsAdmin`), data field names are snake_case (the JSON wire
contract shared with ``frontend/src/types``), and module-level constants are
ALL_UPPERCASE.
"""

from typing import Optional

from pydantic import Field, field_validator

from app.schemas.common import OrmBase, RequestBase
from app.utils import validators

__all__ = [
    "LoginRequest",
    "Token",
    "LoginResponse",
    "CurrentUser",
]


# ---------------------------------------------------------------------------
# Fixed-width field lengths (Ochs Rule section 0.8.2: constants are
# ALL_UPPERCASE with underscores), ported from the signon mapset and the user
# record copybook. USER_ID_LENGTH / PASSWORD_LENGTH have their canonical home in
# app.utils.validators, so they are aliased here (single source of truth) rather
# than re-hardcoded; the name and role widths come from CSUSR01Y.
# ---------------------------------------------------------------------------
USER_ID_LENGTH = validators.USER_ID_LENGTH  # SEC-USR-ID / COSGN00 USERIDI, X(08).
PASSWORD_LENGTH = validators.PASSWORD_LENGTH  # SEC-USR-PWD / COSGN00 PASSWDI, X(08).
FIRST_NAME_LENGTH = 20  # SEC-USR-FNAME, X(20).
LAST_NAME_LENGTH = 20  # SEC-USR-LNAME, X(20).
USER_TYPE_LENGTH = 1  # SEC-USR-TYPE / CDEMO-USER-TYPE, X(01).

# ---------------------------------------------------------------------------
# Role codes. These mirror the COCOM01Y 88-level values (CDEMO-USRTYP-ADMIN
# VALUE 'A' / CDEMO-USRTYP-USER VALUE 'U') and intentionally duplicate the
# ADMIN_USER_TYPE / REGULAR_USER_TYPE constants that app.core.dependencies also
# defines: they are declared locally so this schema module never imports from
# app.core (which would create a schemas -> core dependency cycle).
# ---------------------------------------------------------------------------
ADMIN_USER_TYPE = "A"  # CDEMO-USRTYP-ADMIN VALUE 'A': administrator role.
REGULAR_USER_TYPE = "U"  # CDEMO-USRTYP-USER  VALUE 'U': regular-user role.
VALID_USER_TYPES = frozenset({ADMIN_USER_TYPE, REGULAR_USER_TYPE})

# Default OAuth2 bearer token scheme reported in JWT mode. Kept as an
# ALL_UPPERCASE constant (Ochs Rule) so the literal is not repeated inline.
DEFAULT_TOKEN_TYPE = "bearer"

# Session-generation baseline (M-02). Mirrors ``app.models.user`` /
# ``INITIAL_SESSION_VERSION``: it is duplicated locally -- with an identical
# value -- rather than imported so this schema module stays model-independent
# (schemas never import from app.models, matching how ADMIN_USER_TYPE is
# duplicated to avoid a schemas -> core import). It is only the default for the
# internal ``LoginResponse.session_version`` carrier, which is always overwritten
# with the authenticated user's real generation at build time.
INITIAL_SESSION_VERSION = 1

# Failure wording for the user-type edit, reused by every user_type validator.
MSG_INVALID_USER_TYPE = "User type must be 'A' (admin) or 'U' (regular user)."

# Human-readable field labels handed to the shared validators so the returned
# messages read naturally (for example "User ID must be supplied.").
USER_ID_LABEL = "User ID"
PASSWORD_LABEL = "Password"
USER_TYPE_LABEL = "User type"


def _EnsureValid(result: validators.ValidationResult) -> None:
    """Raise ``ValueError`` when a shared validation result reports failure.

    Centralizes the ``if not result.isValid: raise ValueError(result.message)``
    pattern used by every field validator in this module, keeping each validator
    small (Ochs Rule) and the failure path identical across fields.

    Args:
        result: The outcome returned by an ``app.utils.validators`` edit.

    Raises:
        ValueError: If ``result.isValid`` is False; the raised message is the
            legacy-style wording carried by the result, which Pydantic surfaces
            as a clean ``ValidationError``.
    """
    if not result.isValid:
        raise ValueError(result.message)


def _EnsureUserType(value: str) -> str:
    """Validate that a user-type code is a single 'A' or 'U' character.

    Ports the COCOM01Y 88-level role check (CDEMO-USRTYP-ADMIN 'A' /
    CDEMO-USRTYP-USER 'U'). Shared by :class:`LoginResponse` and
    :class:`CurrentUser` so the role edit is defined once (Ochs
    "self-documenting code" and small-method rules).

    Args:
        value: The candidate role code, already whitespace-stripped by the
            owning model's ``str_strip_whitespace`` configuration.

    Returns:
        The validated role code, unchanged.

    Raises:
        ValueError: If the value is blank or is not exactly ``'A'`` or ``'U'``.
    """
    _EnsureValid(validators.ValidateRequired(USER_TYPE_LABEL, value))
    if value not in VALID_USER_TYPES:
        raise ValueError(MSG_INVALID_USER_TYPE)
    return value


class LoginRequest(RequestBase):
    """Inbound sign-on credentials submitted from the signon screen (CC00).

    Mirrors the two user-supplied fields of the legacy ``COSGN00`` mapset --
    ``USERIDI PIC X(8)`` and ``PASSWDI PIC X(8)`` -- and is the JSON body model
    for ``POST /auth/login``. When the router instead accepts an OAuth2 /
    ``python-multipart`` form, it adapts the form fields onto this same model.

    Inheriting :class:`~app.schemas.common.RequestBase` means unexpected fields
    are rejected (``extra="forbid"``) and surrounding whitespace is stripped
    before validation, both of which satisfy the Ochs "sanitize/validate all
    user input" rule. The plaintext ``password`` is accepted here only to be
    verified downstream against the stored hash; it is never stored or echoed.
    """

    user_id: str = Field(
        ...,
        max_length=USER_ID_LENGTH,
        description=(
            "Sign-on user id (COSGN00 USERIDI, PIC X(8)); the VSAM USRSEC key "
            "and the JWT 'sub' claim. Exactly 8 alphanumeric characters."
        ),
    )
    password: str = Field(
        ...,
        max_length=PASSWORD_LENGTH,
        description=(
            "Sign-on password (COSGN00 PASSWDI, PIC X(8)); accepted inbound only "
            "to be verified against the stored hash, and never stored or "
            "returned. At most 8 characters (legacy plaintext width)."
        ),
    )

    @field_validator("user_id")
    @classmethod
    def ValidateUserId(cls, value: str) -> str:
        """Validate the user id: required, exactly 8 chars, and alphanumeric.

        Reproduces the legacy fixed-width key edit and adds an alphanumeric
        allow-list so the id -- which becomes a database key and a token subject
        -- cannot smuggle injection characters downstream (Ochs sanitization
        rule). The sample ids ADMIN001 / USER0001 satisfy all three checks.

        Args:
            value: The submitted user id (already whitespace-stripped by the
                model configuration).

        Returns:
            The validated user id, unchanged.

        Raises:
            ValueError: If the id is blank, is not exactly
                :data:`USER_ID_LENGTH` characters, or contains any
                non-alphanumeric character.
        """
        _EnsureValid(validators.ValidateRequired(USER_ID_LABEL, value))
        _EnsureValid(validators.ValidateLength(USER_ID_LABEL, value, USER_ID_LENGTH))
        _EnsureValid(validators.ValidateAlphanumeric(USER_ID_LABEL, value))
        return value

    @field_validator("password")
    @classmethod
    def ValidatePassword(cls, value: str) -> str:
        """Validate that a password was supplied (non-blank).

        Only presence is checked here: the character set is deliberately NOT
        restricted (a password may contain symbols and is verified as a hash,
        never used in a query), and the maximum width is enforced by the field's
        ``max_length``. The value is never stored or echoed.

        Args:
            value: The submitted password (already whitespace-stripped by the
                model configuration).

        Returns:
            The validated password, unchanged.

        Raises:
            ValueError: If the password is blank (missing, all spaces, or empty).
        """
        _EnsureValid(validators.ValidateRequired(PASSWORD_LABEL, value))
        return value


class Token(OrmBase):
    """Signed bearer-token envelope returned when JWT auth mode is used.

    Represents the token minted by ``app.core.security.CreateAccessToken``: the
    JWT carries the identity (``sub`` = ``user_id``) and role (``user_type``)
    claims that replace COMMAREA propagation. Under the session baseline
    (technical specification section 0.8.4) the token travels in a session
    cookie instead and this envelope is not returned; it is therefore produced
    only in JWT mode.
    """

    access_token: str = Field(
        ...,
        description="Signed JWT access token carrying the 'sub' and 'user_type' claims.",
    )
    token_type: str = Field(
        default=DEFAULT_TOKEN_TYPE,
        description="OAuth2 token scheme; always 'bearer' for the JWT access token.",
    )


class LoginResponse(OrmBase):
    """Identity payload returned after a successful sign-on (COSGN00C success).

    Combines the caller's identity from the ``USRSEC`` record (``CSUSR01Y``:
    ``user_id`` / ``first_name`` / ``last_name`` / ``user_type``) into the body
    of the ``POST /auth/login`` response. The token fields are OPTIONAL: they
    are populated in JWT mode and omitted (left ``None``) under the session
    baseline, where the credential is set as a cookie instead (technical
    specification section 0.8.4).

    No password (or password-hash) field is declared, so a credential is never
    part of the response; because :class:`~app.schemas.common.OrmBase` reads
    only declared attributes, this DTO can be built directly from the ``User``
    ORM row (``LoginResponse.model_validate(user)``) and the row's
    ``password_hash`` is simply ignored.
    """

    user_id: str = Field(
        ...,
        max_length=USER_ID_LENGTH,
        description="Authenticated user id (CDEMO-USER-ID / SEC-USR-ID, PIC X(8)); the JWT 'sub'.",
    )
    first_name: str = Field(
        ...,
        max_length=FIRST_NAME_LENGTH,
        description="User first name for display (SEC-USR-FNAME, PIC X(20)).",
    )
    last_name: str = Field(
        ...,
        max_length=LAST_NAME_LENGTH,
        description="User last name for display (SEC-USR-LNAME, PIC X(20)).",
    )
    user_type: str = Field(
        ...,
        max_length=USER_TYPE_LENGTH,
        description=(
            "Role code (SEC-USR-TYPE / CDEMO-USER-TYPE, PIC X(1)): 'A' admin or "
            "'U' regular user; the JWT 'user_type' claim."
        ),
    )
    access_token: Optional[str] = Field(
        default=None,
        description="Signed JWT access token; present in JWT mode, None under the session baseline.",
    )
    token_type: Optional[str] = Field(
        default=None,
        description="OAuth2 token scheme ('bearer') in JWT mode; None under the session baseline.",
    )
    # M-02 internal carrier: the authenticated user's current session
    # generation, sourced from ``User.session_version`` by
    # ``AuthService._BuildResponse``. It is NEVER serialized to the JSON body
    # (``exclude=True``) -- it exists only so the session-baseline router can
    # read it and embed it as the token's ``sver`` claim (the revocation
    # anchor). Serialization of a LoginResponse therefore stays identical to
    # before this field existed; only the router reads it, in process.
    session_version: int = Field(
        default=INITIAL_SESSION_VERSION,
        exclude=True,
        description=(
            "Internal-only session generation (M-02); excluded from the wire "
            "contract. Carries User.session_version so the session-mode router "
            "can mint the token's 'sver' revocation claim."
        ),
    )

    @field_validator("user_type")
    @classmethod
    def ValidateUserType(cls, value: str) -> str:
        """Validate that the role code is a single 'A' or 'U' character.

        Args:
            value: The role code sourced from the authenticated user record.

        Returns:
            The validated role code, unchanged.

        Raises:
            ValueError: If the value is blank or is not exactly 'A' or 'U'.
        """
        return _EnsureUserType(value)


class CurrentUser(OrmBase):
    """Stateless identity DTO -- the modern replacement for the CICS COMMAREA.

    Carries the id and role that ``COCOM01Y`` (``CDEMO-USER-ID`` /
    ``CDEMO-USER-TYPE``) propagated between programs on the mainframe. It is a
    schema mirror of the authenticated principal: the ``get_current_user``
    dependency returns the ``User`` ORM object, and a router may serialize that
    object to this DTO (``CurrentUser.model_validate(user)``) thanks to the
    ``from_attributes`` configuration inherited from
    :class:`~app.schemas.common.OrmBase`. The field names align with the JWT
    claims minted by ``app.core.security`` (``sub`` -> ``user_id``,
    ``user_type``), so a decoded token maps onto this DTO as well.

    Only identity is represented -- never a password or password hash.
    """

    user_id: str = Field(
        ...,
        max_length=USER_ID_LENGTH,
        description="Caller user id (CDEMO-USER-ID, PIC X(8)); the JWT 'sub' claim.",
    )
    user_type: str = Field(
        ...,
        max_length=USER_TYPE_LENGTH,
        description=(
            "Caller role code (CDEMO-USER-TYPE, PIC X(1)): 'A' admin or 'U' "
            "regular user; drives the require_admin gate."
        ),
    )
    first_name: Optional[str] = Field(
        default=None,
        max_length=FIRST_NAME_LENGTH,
        description="Optional user first name (SEC-USR-FNAME, PIC X(20)) when resolved from the record.",
    )
    last_name: Optional[str] = Field(
        default=None,
        max_length=LAST_NAME_LENGTH,
        description="Optional user last name (SEC-USR-LNAME, PIC X(20)) when resolved from the record.",
    )

    @field_validator("user_type")
    @classmethod
    def ValidateUserType(cls, value: str) -> str:
        """Validate that the role code is a single 'A' or 'U' character.

        Args:
            value: The role code sourced from the authenticated principal.

        Returns:
            The validated role code, unchanged.

        Raises:
            ValueError: If the value is blank or is not exactly 'A' or 'U'.
        """
        return _EnsureUserType(value)

    @property
    def IsAdmin(self) -> bool:
        """Report whether this caller holds the administrator role.

        Convenience accessor mirroring the legacy ``CDEMO-USRTYP-ADMIN``
        88-level test; routers use it as a small, readable substitute for
        comparing the raw ``user_type`` code when gating admin-only screens.

        Returns:
            ``True`` when :attr:`user_type` equals :data:`ADMIN_USER_TYPE`
            ('A'), otherwise ``False``.
        """
        return self.user_type == ADMIN_USER_TYPE
