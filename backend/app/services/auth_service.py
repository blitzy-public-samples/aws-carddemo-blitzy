# Authentication/signon service. Ports app/cbl/COSGN00C.cbl (tx CC00): the legacy
# plaintext compare IF SEC-USR-PWD = WS-USER-PWD (COSGN00C L223) becomes bcrypt
# VerifyPassword. Identity/role (COCOM01Y COMMAREA) -> stateless session/JWT claims.
"""Authentication service.

Ported 1:1 from legacy CICS online program COSGN00C (transaction CC00, signon).
Identity/role was previously propagated via the CICS COMMAREA copybook COCOM01Y;
it is replaced here by stateless session/JWT claims. The legacy plaintext password
compare (IF SEC-USR-PWD = WS-USER-PWD) is replaced by bcrypt hash verification.
See tech spec 0.5.1, 0.7.7, 0.8.1.

The service owns the single sign-on business flow. It reproduces the COSGN00C
PROCESS-ENTER-KEY and READ-USER-SEC-FILE paragraphs (Minimal Change
Clause 0.8.1): the field-presence edits, the FUNCTION UPPER-CASE of the entered
user id (the uppercase USRSEC/VSAM key), the USRSEC lookup, and the legacy
failure precedence. The password is verified over its EXACT bytes and is NOT
upper-cased -- the legacy password case-fold (COSGN00C L135-136) destroys
credential entropy, and AAP 0.1.1 makes the password-security uplift mandatory
(faithfully copying the insecure legacy handling is "unacceptable"), so under
the D1 precedence rule the entropy-preserving behavior governs (QA finding
M-01). The two blank-field edits keep their verbatim COSGN00C messages, but the
unknown-user and wrong-password paths now surface a single generic
"invalid credentials" message (QA Issue C8: the divergent 401 bodies leaked
which user ids exist; AAP 0.1.1 makes closing that oracle mandatory, which
under D1 overrides verbatim faithfulness for those two branches). Data access
is delegated to
:class:`app.repositories.UserRepository` and password verification/token minting
to :mod:`app.core.security`; the sign-on is a READ-ONLY unit of work and never
commits. A plaintext password is never stored, logged, echoed, or returned.
"""

import logging

from sqlalchemy import update
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.exceptions import AuthenticationError, DomainValidationError
from app.core.security import (
    SUBJECT_CLAIM,
    CreateAccessToken,
    DecodeAccessToken,
    VerifyPassword,
    VerifyPasswordDummy,
)
from app.models.user import User
from app.repositories import UserRepository
from app.schemas import LoginRequest, LoginResponse
from app.utils import validators

__all__ = ["AuthService"]

# Module logger for the signon flow. Only non-sensitive context is ever logged
# (the user id, which is a lookup key -- never the password). It exists so that
# the modern equivalent of the COSGN00C ``WHEN OTHER`` read branch records WHY a
# USRSEC read failed at ERROR (QA finding F6) before the failure is surfaced to
# the caller as the verbatim "Unable to verify the User ..." 401, which by itself
# is indistinguishable from a wrong password in the logs.
_LOGGER = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Verbatim WS-MESSAGE literals from COSGN00C (ALL_UPPERCASE constants, Ochs Rule
# 0.8.2). These are the exact 3270 sign-on messages; under the Minimal Change
# Clause (0.8.1) they are never reworded, re-cased, or re-punctuated.
# ---------------------------------------------------------------------------
MSG_ENTER_USER_ID = "Please enter User ID ..."          # COSGN00C L120
MSG_ENTER_PASSWORD = "Please enter Password ..."        # COSGN00C L125
MSG_USER_NOT_FOUND = "User not found. Try again ..."    # COSGN00C L249 (READ RESP 13)
MSG_WRONG_PASSWORD = "Wrong Password. Try again ..."    # COSGN00C L242 (password mismatch)
MSG_UNABLE_TO_VERIFY = "Unable to verify the User ..."  # COSGN00C L254 (READ RESP other)

# Single, generic sign-on failure message surfaced to the CALLER for both the
# unknown-user and the wrong-password paths (QA Issue C8: user enumeration).
# The two legacy messages above (MSG_USER_NOT_FOUND / MSG_WRONG_PASSWORD)
# distinguished "no such user id" from "bad password" in the 401 body, giving an
# attacker an oracle to enumerate valid user ids. AAP 0.1.1 makes the security
# uplift mandatory and the authoritative QA report classifies the leak as MAJOR,
# so under the D1 precedence rule the security requirement overrides the Minimal
# Change Clause's verbatim-message faithfulness for these two branches. The
# legacy literals are retained ONLY as the server-side diagnostic log strings
# (see Login below), emitted at INFO level -- routine auth failures are not
# warnings -- so operators who enable INFO keep the exact reason in the logs
# while the attacker-visible response reveals only that the credentials were
# invalid.
MSG_INVALID_CREDENTIALS = "Invalid user ID or password. Try again ..."

# Field labels handed to the shared mandatory-field edit. Only the edit's boolean
# result is consumed; the wording surfaced to the caller is always the verbatim
# COSGN00C message above, so these labels never reach the API response.
USER_ID_FIELD_LABEL = "User ID"    # COSGN00 USERIDI
PASSWORD_FIELD_LABEL = "Password"  # COSGN00 PASSWDI

# Auth-mode / token constants aligned with the app.core.config + app.core.security
# contract (AAP 0.8.4). Under the 'jwt' mode a signed bearer access token is
# returned on the response; under the 'session' baseline the token fields stay
# None and the router establishes the session cookie instead.
JWT_AUTH_MODE = "jwt"          # settings.AUTH_MODE value that returns a token
BEARER_TOKEN_TYPE = "bearer"   # OAuth2 token scheme reported in JWT mode


class AuthService:
    """Sign-on business logic, ported 1:1 from CICS program COSGN00C (CC00).

    Reproduces the COSGN00C sign-on flow (Minimal Change Clause 0.8.1):
    the field-presence edits, the uppercasing of the entered user id
    (FUNCTION UPPER-CASE -- the uppercase USRSEC key), the USRSEC lookup, and the
    four failure messages, in the legacy precedence. Two mandatory security
    uplifts diverge from a byte-for-byte port, both under AAP 0.1.1 (which makes
    faithfully copying the insecure legacy credential handling "unacceptable")
    and the D1 precedence rule: (1) the plaintext compare
    ``IF SEC-USR-PWD = WS-USER-PWD`` (COSGN00C L223) becomes a bcrypt hash
    verification via :func:`app.core.security.VerifyPassword`, and (2) the
    password is verified over its EXACT bytes -- the legacy password
    FUNCTION UPPER-CASE (COSGN00C L135-136) is NOT reproduced because it destroys
    credential entropy (QA finding M-01).

    Identity and role (``CDEMO-USER-ID`` / ``CDEMO-USER-TYPE``) that the mainframe
    carried forward in the ``COCOM01Y`` COMMAREA are returned in the response
    instead; the caller (router) establishes the stateless session cookie or JWT
    and chooses the landing screen from the returned role. The service performs a
    READ-ONLY unit of work and never commits, and never stores, logs, or returns
    a plaintext password.
    """

    def __init__(self) -> None:
        """Construct the service with its user-security repository.

        Holds no mutable state beyond the stateless
        :class:`~app.repositories.UserRepository` used to read the ``users``
        table (the legacy VSAM ``USRSEC`` file).
        """
        self.userRepository = UserRepository()

    async def Login(
        self, session: AsyncSession, loginRequest: LoginRequest
    ) -> LoginResponse:
        """Authenticate a sign-on request and return the caller's identity.

        Ports the COSGN00C PROCESS-ENTER-KEY + READ-USER-SEC-FILE flow. The
        failure paths are evaluated in the legacy precedence: empty user id, then
        empty password, then user-not-found, then wrong-password. The two blank
        edits raise their verbatim messages; the unknown-user and wrong-password
        paths raise ONE shared generic message (QA Issue C8) so the 401 response
        cannot be used to tell an unknown user id apart from a bad password.

        Args:
            session: Active async unit-of-work session (READ-ONLY; never committed).
            loginRequest: The submitted sign-on credentials (user id + password).

        Returns:
            A :class:`~app.schemas.LoginResponse` carrying the authenticated
            identity and role. In JWT mode it also carries a signed bearer token;
            under the session baseline the token fields are ``None``.

        Raises:
            DomainValidationError: If the user id or password is blank
                (COSGN00C L120 / L125).
            AuthenticationError: If the user is unknown (L249), the password does
                not match (L242), or the USRSEC read fails unexpectedly (L254).
        """
        self._CheckRequiredFields(loginRequest)  # COSGN00C L119-129 (presence edits)
        # The user id is FUNCTION UPPER-CASE'd (COSGN00C L132-134) because it is
        # the uppercase USRSEC/VSAM key -- that normalization is a key-lookup
        # concern and is preserved. The PASSWORD, however, is verified over its
        # EXACT bytes and is NEVER upper-cased (QA finding M-01). The legacy
        # program applied FUNCTION UPPER-CASE to the password too (COSGN00C
        # L135-136), but that case-fold destroys password entropy (it collapses
        # every case variant of a password onto one hash). AAP 0.1.1 makes the
        # password-security uplift mandatory and explicitly rules that faithfully
        # copying the insecure legacy credential handling is "unacceptable", so
        # under the D1 precedence rule the entropy-preserving behavior governs
        # here; it is AAP-aligned, not a Minimal-Change deviation. The seed
        # loader (init_users.py) and the admin create/update paths hash the exact
        # bytes for the same reason, so a case-sensitive password set anywhere
        # verifies identically at sign-on. (The canonical seed password
        # "PASSWORD" is already all-uppercase, so seed sign-on is unaffected.)
        normalizedUserId = loginRequest.user_id.strip().upper()
        submittedPassword = loginRequest.password
        userRecord = await self._LoadUser(session, normalizedUserId)  # READ-USER-SEC-FILE
        if userRecord is None:
            # READ RESP 13 (NOTFND): user id not on the USRSEC file (COSGN00C L249).
            # C8: log the specific reason server-side at INFO -- the user id is a
            # lookup key, never a secret -- so operators keep the exact diagnostic,
            # then surface the generic message so the 401 body cannot be used to
            # enumerate which user ids exist.
            _LOGGER.info("%s (user id: %s)", MSG_USER_NOT_FOUND, normalizedUserId)
            # F1: equalize response time with the password-verify path below so
            # the 401 cannot be used as a TIMING oracle to enumerate valid user
            # ids. Without this, a found user pays for a bcrypt VerifyPassword
            # (hundreds of ms) while a missing user returns almost instantly --
            # a ~253 ms gap that reliably distinguishes the two. Burning one
            # equivalent bcrypt verification here makes both paths constant-time.
            VerifyPasswordDummy()
            raise AuthenticationError(MSG_INVALID_CREDENTIALS)
        # Replaces the plaintext compare IF SEC-USR-PWD = WS-USER-PWD (COSGN00C L223).
        passwordMatches = VerifyPassword(submittedPassword, userRecord.password_hash)
        if not passwordMatches:
            # Password mismatch on an existing user (COSGN00C L242).
            # C8: return the SAME generic 401 as the unknown-user path above; the
            # specific reason is logged server-side only (never the submitted
            # password) so the response is indistinguishable between the two.
            _LOGGER.info("%s (user id: %s)", MSG_WRONG_PASSWORD, normalizedUserId)
            raise AuthenticationError(MSG_INVALID_CREDENTIALS)
        return self._BuildResponse(userRecord)  # success (COSGN00C L226-238)

    async def RevokeSession(
        self, session: AsyncSession, rawToken: str | None
    ) -> None:
        """Revoke every token outstanding for a token's subject (logout, M-02).

        Server-side counterpart to the client cookie deletion performed by the
        ``POST /auth/logout`` router. Where sign-off on the mainframe cleared the
        CICS COMMAREA so the terminal session could no longer act, the modern
        stateless equivalent advances the subject's ``session_version`` so that
        every previously issued token -- whose ``sver`` claim froze the prior
        generation -- is rejected by :func:`get_current_user`. This closes the
        M-02 gap where a captured pre-logout cookie stayed valid because logout
        only deleted the client copy.

        The operation is deliberately best-effort and idempotent so logout never
        itself fails (the router takes no auth dependency): a missing token, or a
        token that no longer decodes (already expired/tampered), simply revokes
        nothing and returns. The version bump is issued as a single atomic
        ``UPDATE ... SET session_version = session_version + 1`` so concurrent
        logouts or a simultaneous privilege change cannot lose an increment via a
        read-modify-write race; a subject that matches no row updates nothing.

        Unlike :meth:`Login` (a read-only unit of work), this method mutates and
        commits its own change.

        Args:
            session: Active async unit-of-work session.
            rawToken: The signed token carried by the request (session cookie or
                bearer header), or ``None`` when the caller presented none.
        """
        if not rawToken:
            return
        try:
            tokenClaims = DecodeAccessToken(rawToken)
        except AuthenticationError:
            # Already invalid/expired -> there is no live session to revoke;
            # keep logout idempotent by returning without touching any row.
            return
        subject = tokenClaims.get(SUBJECT_CLAIM)
        if not subject:
            return
        await session.execute(
            update(User)
            .where(User.user_id == subject)
            .values(session_version=User.session_version + 1)
        )
        await session.commit()

    def _CheckRequiredFields(self, loginRequest: LoginRequest) -> None:
        """Reproduce the COSGN00C field-presence edits in legacy precedence.

        Mirrors the ``EVALUATE TRUE`` block (COSGN00C L119-129): a blank user id
        is reported before a blank password. Blankness reuses the shared
        mandatory-field edit (:func:`app.utils.validators.ValidateRequired`, the
        port of COBOL ``1215-EDIT-MANDATORY`` covering None / SPACES / LOW-VALUES);
        only its boolean result is used, and the message surfaced is the verbatim
        COSGN00C WS-MESSAGE text.

        Args:
            loginRequest: The submitted sign-on credentials to check.

        Raises:
            DomainValidationError: If the user id (L120) or, failing that, the
                password (L125) is blank.
        """
        if not validators.ValidateRequired(USER_ID_FIELD_LABEL, loginRequest.user_id).isValid:
            raise DomainValidationError(MSG_ENTER_USER_ID)
        if not validators.ValidateRequired(PASSWORD_FIELD_LABEL, loginRequest.password).isValid:
            raise DomainValidationError(MSG_ENTER_PASSWORD)

    async def _LoadUser(
        self, session: AsyncSession, normalizedUserId: str
    ) -> User | None:
        """Read the USRSEC user record, translating an unexpected read failure.

        Ports READ-USER-SEC-FILE (COSGN00C L210-221): a found record is returned
        and a NOTFND maps to ``None`` (the repository contract). An unexpected
        database failure is the modern equivalent of the ``WHEN OTHER`` read branch
        (COSGN00C L253-257): the two specific, named categories such a failure
        takes are caught (never a bare except) -- a
        :class:`sqlalchemy.exc.SQLAlchemyError` (for example the ``ProgrammingError``
        raised when the ``users`` relation does not exist against a pre-migration,
        empty-schema database) and an :class:`OSError` (the connect-phase socket
        failures SQLAlchemy does not wrap, such as ``socket.gaierror`` when the
        database host cannot be resolved). The real cause is logged at ERROR with
        non-sensitive context and chained for diagnostics, then re-raised as the
        verbatim "Unable to verify the User ..." authentication failure rather than
        being swallowed -- so an infrastructure outage surfaces as the legacy
        parity 401, never an unhandled 500.

        Args:
            session: Active async unit-of-work session.
            normalizedUserId: The uppercased 8-character user id (USRSEC key).

        Returns:
            The matching :class:`~app.models.user.User`, or ``None`` when absent.

        Raises:
            AuthenticationError: If the USRSEC read fails unexpectedly (L254).
        """
        try:
            return await self.userRepository.GetByUserId(session, normalizedUserId)
        except (SQLAlchemyError, OSError) as readError:
            # Modern WHEN-OTHER branch (COSGN00C L253-257): record the real cause
            # at ERROR with non-sensitive context (user id only, never the
            # password) so an operator can tell an infrastructure failure apart
            # from a routine bad-password 401, then re-raise the verbatim
            # "Unable to verify the User ..." 401 to preserve legacy parity. Both
            # a SQLAlchemyError (empty-schema query failure) and an OSError
            # (host-unreachable connect failure) map here.
            _LOGGER.error(
                "USRSEC read failed for user id '%s'; unable to verify credentials: %s",
                normalizedUserId,
                readError,
            )
            raise AuthenticationError(MSG_UNABLE_TO_VERIFY) from readError

    def _BuildResponse(self, userRecord: User) -> LoginResponse:
        """Build the success identity payload, minting a token only in JWT mode.

        Ports the COSGN00C success branch (COSGN00C L226-238), which moved the
        identity and role into the COMMAREA and routed admins to ``COADM01C`` and
        regular users to ``COMEN01C``. The modern service instead returns the
        identity and role, and the router chooses the landing screen from
        ``user_type``. Under the ``session`` baseline (AAP 0.8.4) the token fields
        stay ``None`` (the router sets the session cookie); under ``jwt`` a signed
        bearer access token carrying the ``sub`` / ``user_type`` claims is attached.
        No password or password hash is placed on the response.

        Args:
            userRecord: The authenticated USRSEC user record.

        Returns:
            The populated :class:`~app.schemas.LoginResponse`.
        """
        accessToken = None
        tokenType = None
        if settings.AUTH_MODE == JWT_AUTH_MODE:
            # M-02: embed the user's current session generation as the token's
            # ``sver`` claim so the bearer token is revocable server-side
            # (get_current_user rejects it once session_version advances).
            accessToken = CreateAccessToken(
                subject=userRecord.user_id,
                userType=userRecord.user_type,
                sessionVersion=userRecord.session_version,
            )
            tokenType = BEARER_TOKEN_TYPE
        # session_version is carried on the response (excluded from the JSON
        # body) so the session-baseline router can mint the same ``sver`` claim
        # when it sets the cookie; it is unused in JWT mode (token already made).
        return LoginResponse(
            user_id=userRecord.user_id,
            first_name=userRecord.first_name,
            last_name=userRecord.last_name,
            user_type=userRecord.user_type,
            access_token=accessToken,
            token_type=tokenType,
            session_version=userRecord.session_version,
        )
