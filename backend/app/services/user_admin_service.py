"""User administration service.

Ported 1:1 from legacy CICS online programs COUSR00C (list, CU00), COUSR01C
(add, CU01), COUSR02C (update, CU02), COUSR03C (delete, CU03). All operations
are admin-gated (user_type='A', F-002). The legacy plaintext SEC-USR-PWD is
uplifted to a bcrypt password_hash via core.security; plaintext is never stored
or returned. Record layout CSUSR01Y. See section 0.5.1, 0.7.7, 0.8.1.

Design and responsibilities
---------------------------
This module is the service (business-logic) layer for the admin-only
``/admin/users`` screens. Following the layered FastAPI architecture (AAP
0.4.3), routers stay thin and delegate here, while all data access is delegated
in turn to :class:`app.repositories.UserRepository`. Each public method maps to
exactly one legacy online program:

    ListUsers   <- COUSR00C  (browse USRSEC, STARTBR/READNEXT)
    GetUser     <- COUSR02C/COUSR03C pre-read (READ USRSEC)
    AddUser     <- COUSR01C  (WRITE USRSEC)
    UpdateUser  <- COUSR02C  (READ ... UPDATE + REWRITE USRSEC)
    DeleteUser  <- COUSR03C  (READ ... UPDATE + DELETE USRSEC)

Unit of work
------------
The repository flushes but never commits (the caller owns the transaction), so
this service owns the unit of work: the three mutating operations (add, update,
delete) ``commit`` on success and ``rollback`` on failure, while the two
read-only operations (list, view) never commit. Because the session factory is
configured with ``expire_on_commit=False`` (``app.db.session``), a just-committed
ORM instance can be serialized into a response DTO without a second round-trip.

Security (AAP 0.7.7; Ochs Rule #3 "no hardcoding secrets")
----------------------------------------------------------
The legacy 8-character plaintext ``SEC-USR-PWD`` is replaced by a bcrypt
``password_hash``: :func:`app.core.security.HashPassword` produces the digest and
:func:`app.core.security.VerifyPassword` compares a candidate against it. Hashing
is applied to the EXACT (whitespace-stripped) plaintext bytes, matching the
sign-on credential policy -- ``auth_service.Login`` verifies the submitted
password over its exact bytes -- and the seed loader
(``batch/loaders/init_users.py``), so a password set here verifies identically
at sign-on. The legacy ``FUNCTION UPPER-CASE`` password fold (COSGN00C L135-136)
is deliberately NOT reproduced: it collapses every case variant of a password
onto a single hash and destroys credential entropy (QA finding M-01). Under AAP
0.1.1 the password-security uplift is mandatory (faithfully copying the insecure
legacy handling is "unacceptable"), so under the D1 precedence rule preserving
case governs. No plaintext password is ever stored, logged, or returned; the
:class:`app.schemas.UserRead` / :class:`app.schemas.UserSummary` response DTOs
carry no password field of any kind.

Admin gating and self-action policy (F-002; QA finding M-04)
-----------------------------------------------------------
The router dependency ``Depends(require_admin)`` is the first admin gate, but it
is no longer the only one. Every public method now takes a MANDATORY
``currentUser`` (the acting administrator, supplied by the router from
``require_admin``) and re-checks the role in depth: :meth:`_AssertAdmin` raises
:class:`app.core.exceptions.AuthorizationError` unless ``currentUser.user_type``
is :data:`ADMIN_USER_TYPE` ('A'). Making the actor mandatory closes the M-04 gap
where the check was a no-op whenever a caller omitted the actor, so an internal
(non-router) caller can no longer bypass the role enforcement.

The acting administrator is also policed against destructive self-actions
(again M-04, and with no legacy COBOL counterpart -- the mainframe allowed an
operator to delete or demote itself): an administrator may not delete its own
account (:meth:`_AssertNotSelfDelete`) nor strip its own administrator role
(:meth:`_AssertNotSelfDemotion`). These self-guards run AFTER the
last-administrator invariant (:meth:`_AssertNotLastAdmin`), so removing the sole
administrator still reports the systemic "last administrator" reason, while a
self-action that leaves other administrators intact is reported as the
self-action it is. Both are surfaced as HTTP 409 Conflict, matching the sibling
last-administrator invariant.

Naming (Ochs resolution, AAP 0.8.3)
-----------------------------------
The module/file name is snake_case; the class and methods are PascalCase; local
variables are camelCase; and module constants are ALL_UPPERCASE.
"""

from __future__ import annotations

import logging

from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import (
    AuthorizationError,
    ConflictError,
    DomainValidationError,
    NotFoundError,
)
from app.core.security import HashPassword, VerifyPassword
from app.models.user import User
from app.repositories import UserRepository
from app.schemas import (
    MessageResponse,
    PaginatedResponse,
    PaginationParams,
    UserCreate,
    UserRead,
    UserSummary,
    UserUpdate,
)
from app.utils import validators

__all__ = ["UserAdminService"]

# Module logger. Success outcomes are logged (a monitoring hook) so the legacy
# on-screen confirmation strings still surface in the modern stack; only the
# non-sensitive user id and action are ever logged, never a password.
LOGGER = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Field labels (Ochs Rule: constants are ALL_UPPERCASE). Handed to the shared
# mandatory edit ``validators.ValidateRequired`` purely to describe the field;
# the message it builds is discarded in favor of the verbatim COBOL text below.
# ---------------------------------------------------------------------------
USER_ID_LABEL = "User ID"
FIRST_NAME_LABEL = "First Name"
LAST_NAME_LABEL = "Last Name"
PASSWORD_LABEL = "Password"
USER_TYPE_LABEL = "User Type"

# ---------------------------------------------------------------------------
# Verbatim COBOL screen messages (character-for-character parity, including the
# trailing ellipsis and its spacing). Each cites the originating program line so
# the 1:1 port stays auditable (AAP 0.8.1).
# ---------------------------------------------------------------------------
MSG_FIRST_NAME_REQUIRED = "First Name can NOT be empty..."  # COUSR01C L120 / COUSR02C L188
MSG_LAST_NAME_REQUIRED = "Last Name can NOT be empty..."  # COUSR01C L126 / COUSR02C L194
MSG_USER_ID_REQUIRED = "User ID can NOT be empty..."  # COUSR01C L132 / 02C L182 / 03C L179
MSG_PASSWORD_REQUIRED = "Password can NOT be empty..."  # COUSR01C L138 / COUSR02C L200
MSG_USER_TYPE_REQUIRED = "User Type can NOT be empty..."  # COUSR01C L144 / COUSR02C L206
MSG_USER_ALREADY_EXISTS = "User ID already exist..."  # COUSR01C L263 (DUPKEY / DUPREC)
MSG_UNABLE_TO_ADD = "Unable to Add User..."  # COUSR01C L270 (WRITE OTHER)
MSG_NO_CHANGES = "Please modify to update ..."  # COUSR02C L239 (note the space before "...")
MSG_UNABLE_TO_UPDATE = "Unable to Update User..."  # COUSR02C L386 / COUSR03C L332 (OTHER)
MSG_USER_NOT_FOUND = "User ID NOT found..."  # COUSR02C L342/L379 / COUSR03C L289/L325 (NOTFND)
MSG_UNABLE_TO_LOOKUP = "Unable to lookup User..."  # COUSR02C L349 / COUSR03C L296 (READ OTHER)

# Modern defense-in-depth message. The legacy programs relied on the CICS
# region / COMMAREA role to reach these screens (there is no per-program COBOL
# text for a non-admin caller); the router ``require_admin`` gate is authoritative.
MSG_ADMIN_REQUIRED = "Administrator privileges are required to manage users."

# Administrator role code (legacy COCOM01Y ``88 CDEMO-USRTYP-ADMIN VALUE 'A'``).
# Defined locally, matching the intentional local duplication (with citation) in
# app.core.dependencies, app.schemas.auth, and app.repositories.user_repo.
ADMIN_USER_TYPE = "A"

# Modern security-uplift message (QA finding F3; NO legacy COBOL equivalent --
# the mainframe permitted the security file to be emptied of administrators).
# Surfaced as an HTTP 409 Conflict when demoting or deleting a user would leave
# zero administrators, which would irrecoverably lock everyone out of the
# admin-only user-management screens (AAP 0.1.1 mandatory security uplift).
MSG_LAST_ADMIN = "Cannot remove the last administrator account..."

# Modern self-action safeguards (QA finding M-04; NO legacy COBOL equivalent --
# the mainframe let an operator delete or demote its own USRSEC record). An
# administrator may not delete its own account nor remove its own administrator
# role; both are surfaced as HTTP 409 Conflict, matching the sibling
# last-administrator invariant. They are checked AFTER the last-administrator
# guard so removing the sole admin still reports the systemic reason.
MSG_CANNOT_DELETE_SELF = "Cannot delete your own account..."
MSG_CANNOT_DEMOTE_SELF = "Cannot remove your own administrator privileges..."

# ---------------------------------------------------------------------------
# Success-message action words. The legacy programs build the confirmation with
# ``STRING 'User ' SEC-USR-ID ' has been <action> ...'`` -- see
# :meth:`UserAdminService._BuildSuccessMessage`.
# ---------------------------------------------------------------------------
ADDED_ACTION = "added"  # COUSR01C L257
UPDATED_ACTION = "updated"  # COUSR02C L374
DELETED_ACTION = "deleted"  # COUSR03C L320

# Generous upper bound for the single ordered fetch that backs the paginated
# list. USRSEC is a small administrative security file (the legacy seed defines
# roughly ten users), so this cap is never reached in practice; it exists only
# to bound the query defensively while still letting :meth:`UserAdminService.ListUsers`
# report exact pagination metadata (total_items / total_pages / has_next).
MAX_USER_LIST_SIZE = 10000


class UserAdminService:
    """Admin user-management business logic (COUSR00C through COUSR03C).

    Groups the list / view / add / update / delete operations for the security
    ``users`` table (legacy VSAM ``USRSEC``). The service holds a single
    stateless :class:`~app.repositories.UserRepository`; every method receives
    the active :class:`~sqlalchemy.ext.asyncio.AsyncSession` from the caller and,
    for mutating operations, owns the commit/rollback boundary.

    All operations are admin-only (F-002). The router ``require_admin``
    dependency is the first gate; each method ALSO requires a mandatory
    ``currentUser`` and re-checks the admin role in depth (see
    :meth:`_AssertAdmin`), and the two mutating removals police the acting
    administrator against destructive self-actions (M-04; see
    :meth:`_AssertNotSelfDelete` and :meth:`_AssertNotSelfDemotion`).
    """

    def __init__(self) -> None:
        """Initialize the service with its user-data repository.

        The repository is stateless and holds no connection, so a single
        instance is reused across requests; the per-request session is always
        passed in explicitly.
        """
        self.userRepository = UserRepository()

    # -----------------------------------------------------------------------
    # Private helpers -- admin gating and field validation.
    # -----------------------------------------------------------------------
    def _AssertAdmin(self, currentUser: User) -> None:
        """Enforce the mandatory service-level admin check (defense in depth).

        The router ``require_admin`` dependency is the first gate; this method is
        the second, so the role rule holds even for a caller that reaches the
        service by some path other than the router (QA finding M-04). The acting
        user's ``user_type`` must equal :data:`ADMIN_USER_TYPE` ('A'); anything
        else -- including a ``None`` actor, which is treated as unauthorized so a
        missing identity fails closed rather than raising ``AttributeError`` --
        is rejected with :class:`app.core.exceptions.AuthorizationError`.

        Args:
            currentUser: The authenticated acting administrator (the ORM
                :class:`~app.models.user.User` returned by ``require_admin``).

        Raises:
            AuthorizationError: If ``currentUser`` is ``None`` or its
                ``user_type`` is not :data:`ADMIN_USER_TYPE` ('A').
        """
        if currentUser is None or currentUser.user_type != ADMIN_USER_TYPE:
            raise AuthorizationError(MSG_ADMIN_REQUIRED)

    def _AssertNotSelfDelete(self, currentUser: User, foundUser: User) -> None:
        """Block an administrator from deleting its own account (M-04).

        A destructive self-action safeguard with no legacy counterpart. Checked
        AFTER :meth:`_AssertNotLastAdmin` (see :meth:`DeleteUser`), so an
        administrator that is the sole remaining admin still reports the systemic
        "last administrator" reason, while a self-delete that would leave other
        administrators intact is reported as a self-delete.

        Args:
            currentUser: The acting administrator.
            foundUser: The persisted user targeted by the delete.

        Raises:
            ConflictError: If the acting administrator is the delete target.
        """
        if currentUser.user_id == foundUser.user_id:
            raise ConflictError(MSG_CANNOT_DELETE_SELF)

    def _AssertNotSelfDemotion(
        self, currentUser: User, foundUser: User, newUserType: str
    ) -> None:
        """Block an administrator from removing its own admin role (M-04).

        Fires only when the acting administrator edits its OWN record in a way
        that strips administrator access ('A' -> non-'A'). A self-edit that keeps
        the administrator role (for example changing one's own name or password)
        is allowed. Checked AFTER :meth:`_AssertNotLastAdmin` (see
        :meth:`UpdateUser`) for the same reason as :meth:`_AssertNotSelfDelete`.

        Args:
            currentUser: The acting administrator.
            foundUser: The persisted user targeted by the update.
            newUserType: The requested post-update role code.

        Raises:
            ConflictError: If the acting administrator would demote itself.
        """
        isSelf = currentUser.user_id == foundUser.user_id
        isAdmin = foundUser.user_type == ADMIN_USER_TYPE
        losesAdminRole = newUserType != ADMIN_USER_TYPE
        if isSelf and isAdmin and losesAdminRole:
            raise ConflictError(MSG_CANNOT_DEMOTE_SELF)

    def _RequireField(
        self, fieldLabel: str, value: object, cobolMessage: str
    ) -> None:
        """Reject a blank mandatory field with its verbatim COBOL message.

        Routes the presence check through the shared
        :func:`app.utils.validators.ValidateRequired` edit (1215-EDIT-MANDATORY:
        None / spaces / low-values are blank) so the accept/reject behavior
        matches the rest of the backend, then raises with the exact legacy screen
        text for parity.

        Args:
            fieldLabel: Human-readable field label handed to the shared edit.
            value: The candidate value to check for presence.
            cobolMessage: The verbatim COBOL message raised when the value is
                blank.

        Raises:
            DomainValidationError: If ``value`` is blank.
        """
        if not validators.ValidateRequired(fieldLabel, value).isValid:
            raise DomainValidationError(cobolMessage)

    def _ValidateAddFields(self, userCreate: UserCreate) -> None:
        """Validate the add-user request in COUSR01C field order.

        Mirrors the ``PROCESS-ENTER-KEY`` evaluation of COUSR01C, which checks
        first name, last name, user id, password, then user type, each with its
        own screen message.

        Args:
            userCreate: The inbound add-user DTO.

        Raises:
            DomainValidationError: On the first blank field, carrying that
                field's verbatim COBOL message.
        """
        self._RequireField(FIRST_NAME_LABEL, userCreate.first_name, MSG_FIRST_NAME_REQUIRED)
        self._RequireField(LAST_NAME_LABEL, userCreate.last_name, MSG_LAST_NAME_REQUIRED)
        self._RequireField(USER_ID_LABEL, userCreate.user_id, MSG_USER_ID_REQUIRED)
        self._RequireField(PASSWORD_LABEL, userCreate.password, MSG_PASSWORD_REQUIRED)
        self._RequireField(USER_TYPE_LABEL, userCreate.user_type, MSG_USER_TYPE_REQUIRED)

    def _ValidateUpdateFields(self, userId: str, userUpdate: UserUpdate) -> None:
        """Validate the update-user request in COUSR02C field order.

        Mirrors the ``UPDATE-USER-INFO`` evaluation of COUSR02C, which checks
        user id, first name, last name, password, then user type. Unlike the
        legacy screen (which always re-sent the password), the modern
        :class:`~app.schemas.UserUpdate` treats the password as optional: it is
        validated only when supplied, and omitting it leaves the stored hash
        unchanged.

        Args:
            userId: The path-supplied user id (the immutable key).
            userUpdate: The inbound update DTO (first/last name, user type, and
                an optional password).

        Raises:
            DomainValidationError: On the first blank mandatory field, carrying
                that field's verbatim COBOL message.
        """
        self._RequireField(USER_ID_LABEL, userId, MSG_USER_ID_REQUIRED)
        self._RequireField(FIRST_NAME_LABEL, userUpdate.first_name, MSG_FIRST_NAME_REQUIRED)
        self._RequireField(LAST_NAME_LABEL, userUpdate.last_name, MSG_LAST_NAME_REQUIRED)
        if userUpdate.password is not None:
            self._RequireField(PASSWORD_LABEL, userUpdate.password, MSG_PASSWORD_REQUIRED)
        self._RequireField(USER_TYPE_LABEL, userUpdate.user_type, MSG_USER_TYPE_REQUIRED)

    # -----------------------------------------------------------------------
    # Private helpers -- lookup, change detection, construction, persistence.
    # -----------------------------------------------------------------------
    async def _LookupUser(self, session: AsyncSession, userId: str) -> User:
        """Read a user by id, translating misses and read errors to parity messages.

        Reproduces the ``READ USRSEC`` responses shared by COUSR02C and COUSR03C:
        a NOTFND condition surfaces "User ID NOT found..." and any other file
        error surfaces "Unable to lookup User...".

        Args:
            session: The active async database session.
            userId: The user id to read.

        Returns:
            The matching :class:`~app.models.user.User` ORM instance.

        Raises:
            NotFoundError: If no user matches (NOTFND) or the read fails (OTHER).
        """
        try:
            foundUser = await self.userRepository.GetByUserId(session, userId)
        except SQLAlchemyError as lookupError:
            raise NotFoundError(MSG_UNABLE_TO_LOOKUP) from lookupError
        if foundUser is None:
            raise NotFoundError(MSG_USER_NOT_FOUND)
        return foundUser

    async def _AssertNotLastAdmin(
        self, session: AsyncSession, foundUser: User, newUserType: str | None
    ) -> None:
        """Block demoting or deleting the last administrator (QA finding F3).

        Enforces the modern last-administrator invariant that has no legacy
        counterpart (the mainframe let the USRSEC security file be emptied of
        administrators, AAP 0.1.1). The guard fires only when the operation
        actually REMOVES administrator access from an administrator:

            * update -- ``foundUser`` is an admin and ``newUserType`` is the
              non-admin regular code (a demotion 'A' -> 'U'); and
            * delete -- ``foundUser`` is an admin and ``newUserType`` is ``None``
              (the sentinel this method uses for "the row is being deleted").

        Promotions, admin edits that keep the admin role, and any operation on a
        non-admin user are all no-ops here. When the operation would remove admin
        access, :meth:`app.repositories.UserRepository.CountAdminsForUpdate`
        locks the administrator rows ``FOR UPDATE`` and returns their count; a
        count of one (this user is the sole remaining admin) raises
        :class:`~app.core.exceptions.ConflictError`. Because that count is taken
        under a row lock, concurrent removals are serialized: a second request
        blocks, then re-reads the reduced admin set, so two callers cannot each
        assume another admin remains and jointly drive the count to zero. This
        single target-keyed invariant therefore covers every reported vector --
        demoting/deleting the sole admin (including the admin acting on its own
        account), and the concurrent mutual-demotion race.

        Args:
            session: Active async session (owns the unit of work and the lock).
            foundUser: The persisted user targeted by the update or delete.
            newUserType: The user's post-operation role code, or ``None`` when
                the user is being deleted.

        Raises:
            ConflictError: If the operation would leave zero administrators.
        """
        isAdmin = foundUser.user_type == ADMIN_USER_TYPE
        losesAdminRole = newUserType != ADMIN_USER_TYPE
        if not (isAdmin and losesAdminRole):
            return
        adminCount = await self.userRepository.CountAdminsForUpdate(session)
        if adminCount <= 1:
            raise ConflictError(MSG_LAST_ADMIN)

    def _IsPasswordUpdated(self, user: User, userUpdate: UserUpdate) -> bool:
        """Report whether the update supplies a genuinely new password.

        Faithful to the legacy plaintext comparison in COUSR02C (which only
        rewrote the record when a field actually changed): a password is
        considered updated only when one is supplied AND it does not already
        verify against the stored bcrypt hash. This keeps an unchanged password
        from being needlessly re-hashed.

        Args:
            user: The current persisted user.
            userUpdate: The inbound update DTO.

        Returns:
            ``True`` if a new, different password was supplied; ``False`` when no
            password was supplied or the supplied one matches the stored hash.
        """
        if userUpdate.password is None:
            return False
        # M-01: verify the candidate over its EXACT bytes, matching the sign-on
        # policy (auth_service.Login no longer upper-cases the password) and the
        # exact-byte hashing applied in _ApplyChanges/_BuildUser below. Preserving
        # case keeps full password entropy; a genuine case change is correctly
        # detected as a change (the stored hash will not verify the new casing).
        return not VerifyPassword(userUpdate.password, user.password_hash)

    def _DetectChanges(self, user: User, userUpdate: UserUpdate) -> bool:
        """Determine whether the update modifies any stored field.

        Mirrors the COUSR02C change check that raises "Please modify to update
        ..." when nothing differs. Cheap scalar comparisons (first name, last
        name, user type) are evaluated first so the more expensive bcrypt verify
        is only reached when the other fields are unchanged.

        Args:
            user: The current persisted user.
            userUpdate: The inbound update DTO.

        Returns:
            ``True`` if any field (name, type, or password) differs from the
            stored record; ``False`` otherwise.
        """
        if userUpdate.first_name != user.first_name:
            return True
        if userUpdate.last_name != user.last_name:
            return True
        if userUpdate.user_type != user.user_type:
            return True
        return self._IsPasswordUpdated(user, userUpdate)

    def _ApplyChanges(self, user: User, userUpdate: UserUpdate) -> None:
        """Copy the update DTO onto the ORM instance, re-hashing when needed.

        The role/password change flags are captured BEFORE any mutation so
        :meth:`_IsPasswordUpdated` reads the original hash before it is
        overwritten; the password is re-hashed ONLY when a new, different one was
        supplied (over its exact bytes -- M-01), and the plaintext is never
        stored. When the role or the password changes, the user's
        ``session_version`` is incremented (M-02) so any outstanding session/JWT
        that embedded the previous generation is revoked at the next request --
        "rotate on privilege change" -- preventing a demoted admin (or a user
        whose password was reset) from continuing to act on a stale token.

        Args:
            user: The persisted user to mutate in place.
            userUpdate: The validated update DTO.
        """
        # M-02: rotate the target user's session generation when a security-
        # material field changes (role or password), so any outstanding token
        # carrying the old generation is revoked ("rotate on privilege change").
        # Captured before the mutation below so the comparisons read the original
        # values; the password check reads the ORIGINAL hash before it is
        # overwritten.
        roleChanged = userUpdate.user_type != user.user_type
        passwordChanged = self._IsPasswordUpdated(user, userUpdate)
        if passwordChanged:
            # M-01: hash the password over its EXACT bytes (case preserved). The
            # sign-on path (auth_service.Login) verifies exact bytes, so a
            # case-sensitive password set here verifies identically at sign-on;
            # upper-casing would destroy credential entropy.
            user.password_hash = HashPassword(userUpdate.password)
        user.first_name = userUpdate.first_name
        user.last_name = userUpdate.last_name
        user.user_type = userUpdate.user_type
        if roleChanged or passwordChanged:
            user.session_version = user.session_version + 1

    def _BuildUser(self, userCreate: UserCreate) -> User:
        """Construct a new ORM user from the add DTO, hashing the password.

        The plaintext password is converted to a bcrypt digest here (a service
        concern); the repository persists whatever ``password_hash`` it is given.

        Args:
            userCreate: The validated add-user DTO.

        Returns:
            A transient :class:`~app.models.user.User` ready to be persisted.
        """
        return User(
            user_id=userCreate.user_id,
            first_name=userCreate.first_name,
            last_name=userCreate.last_name,
            # M-01: hash the password over its EXACT bytes (case preserved). The
            # sign-on path (auth_service.Login) verifies exact bytes, so a
            # case-sensitive password set at create verifies identically at
            # sign-on. Upper-casing here would destroy credential entropy (it
            # collapses every case variant of a password onto a single hash) --
            # the QA finding M-01 defect.
            password_hash=HashPassword(userCreate.password),
            user_type=userCreate.user_type,
        )

    def _BuildSuccessMessage(self, userId: str, action: str) -> str:
        """Build the COBOL-parity confirmation "User <id> has been <action> ...".

        Reproduces the ``STRING 'User ' SEC-USR-ID ' has been <action> ...'``
        construction shared by COUSR01C/02C/03C, preserving the surrounding
        spaces and the trailing " ...".

        Args:
            userId: The affected user id.
            action: One of :data:`ADDED_ACTION`, :data:`UPDATED_ACTION`,
                :data:`DELETED_ACTION`.

        Returns:
            The formatted confirmation string.
        """
        return f"User {userId} has been {action} ..."

    async def _PersistNewUser(self, session: AsyncSession, newUser: User) -> None:
        """Insert a new user and commit, translating failures to parity errors.

        Owns the add unit of work. A unique-key violation surfaced by the flush
        (a race with a concurrent insert) maps to "User ID already exist..." and
        any other database failure maps to "Unable to Add User..." (COUSR01C
        WRITE OTHER). The session is rolled back before either error propagates.

        Args:
            session: The active async database session.
            newUser: The transient user to insert.

        Raises:
            ConflictError: On a duplicate user id (IntegrityError).
            DomainValidationError: On any other database failure.
        """
        try:
            await self.userRepository.Create(session, newUser)
            await session.commit()
        except IntegrityError as duplicateError:
            await session.rollback()
            raise ConflictError(MSG_USER_ALREADY_EXISTS) from duplicateError
        except SQLAlchemyError as persistError:
            await session.rollback()
            raise DomainValidationError(MSG_UNABLE_TO_ADD) from persistError

    async def _PersistUpdatedUser(self, session: AsyncSession, user: User) -> None:
        """Persist an updated user and commit, mapping failures to COUSR02C text.

        Owns the update unit of work; any database failure maps to "Unable to
        Update User..." (COUSR02C REWRITE OTHER) after a rollback.

        Args:
            session: The active async database session.
            user: The mutated user to persist.

        Raises:
            DomainValidationError: On any database failure.
        """
        try:
            await self.userRepository.Update(session, user)
            await session.commit()
        except SQLAlchemyError as persistError:
            await session.rollback()
            raise DomainValidationError(MSG_UNABLE_TO_UPDATE) from persistError

    async def _PersistDeletedUser(self, session: AsyncSession, user: User) -> None:
        """Delete a user and commit, mapping failures to the COUSR03C text.

        Owns the delete unit of work. Note the legacy quirk: COUSR03C's DELETE
        OTHER branch reuses "Unable to Update User..." (not "delete"), so that
        exact text is preserved here after a rollback.

        Args:
            session: The active async database session.
            user: The persisted user to delete.

        Raises:
            DomainValidationError: On any database failure.
        """
        try:
            await self.userRepository.Delete(session, user)
            await session.commit()
        except SQLAlchemyError as persistError:
            await session.rollback()
            raise DomainValidationError(MSG_UNABLE_TO_UPDATE) from persistError


    # -----------------------------------------------------------------------
    # Public API -- one method per legacy online program.
    # -----------------------------------------------------------------------
    async def ListUsers(
        self,
        session: AsyncSession,
        params: PaginationParams,
        currentUser: User,
    ) -> PaginatedResponse[UserSummary]:
        """List users for the admin browse screen (COUSR00C, CU00).

        Reproduces the COUSR00C USRSEC browse (STARTBR/READNEXT over the whole
        file, ordered by user id). The repository returns users ordered by id;
        this method slices the requested page and reports exact pagination
        metadata. Read-only: it never commits.

        Args:
            session: The active async database session.
            params: Page number and page size.
            currentUser: The acting administrator (mandatory; from
                ``require_admin``), re-checked in depth by :meth:`_AssertAdmin`.

        Returns:
            A page of :class:`~app.schemas.UserSummary` items (no password) with
            total-item / total-page / has-next metadata.

        Raises:
            AuthorizationError: If ``currentUser`` is not an administrator.
        """
        self._AssertAdmin(currentUser)
        allUsers = await self.userRepository.ListUsers(
            session, startUserId=None, limit=MAX_USER_LIST_SIZE
        )
        totalItems = len(allUsers)
        startIndex = (params.page - 1) * params.page_size
        pageUsers = allUsers[startIndex : startIndex + params.page_size]
        userSummaries = [UserSummary.model_validate(row) for row in pageUsers]
        return PaginatedResponse.Create(userSummaries, totalItems, params)

    async def GetUser(
        self,
        session: AsyncSession,
        userId: str,
        currentUser: User,
    ) -> UserRead:
        """Read a single user for the view / update / delete pre-read.

        Reproduces the ``READ USRSEC`` shared by COUSR02C and COUSR03C: a blank
        id is rejected with "User ID can NOT be empty..." and a miss (or read
        error) surfaces through :meth:`_LookupUser`. Read-only: it never commits.

        Args:
            session: The active async database session.
            userId: The user id to read.
            currentUser: The acting administrator (mandatory; from
                ``require_admin``), re-checked in depth by :meth:`_AssertAdmin`.

        Returns:
            The matching user as a :class:`~app.schemas.UserRead` (no password).

        Raises:
            AuthorizationError: If ``currentUser`` is not an administrator.
            DomainValidationError: If ``userId`` is blank.
            NotFoundError: If no user matches or the read fails.
        """
        self._AssertAdmin(currentUser)
        self._RequireField(USER_ID_LABEL, userId, MSG_USER_ID_REQUIRED)
        foundUser = await self._LookupUser(session, userId)
        return UserRead.model_validate(foundUser)

    async def AddUser(
        self,
        session: AsyncSession,
        userCreate: UserCreate,
        currentUser: User,
    ) -> UserRead:
        """Add a new user (COUSR01C, CU01).

        Validates the mandatory fields in COUSR01C order, rejects a duplicate id
        with "User ID already exist...", hashes the plaintext password to bcrypt,
        then inserts and commits. The success confirmation is logged (never the
        password). The commit/rollback boundary is owned by :meth:`_PersistNewUser`.

        The duplicate id is guarded twice: a pre-read rejects an id that already
        exists, and -- should a concurrent insert slip in between that pre-read
        and the write (a TOCTOU race) -- the unique-key violation surfaced by
        :meth:`_PersistNewUser` is mapped to the same "User ID already exist..."
        conflict, so the race can never create a duplicate or leak a 500.

        Args:
            session: The active async database session.
            userCreate: The add-user DTO (first/last name, id, password, type).
            currentUser: The acting administrator (mandatory; from
                ``require_admin``), re-checked in depth by :meth:`_AssertAdmin`.

        Returns:
            The created user as a :class:`~app.schemas.UserRead` (no password).

        Raises:
            AuthorizationError: If ``currentUser`` is not an administrator.
            DomainValidationError: On a blank mandatory field or a non-duplicate
                database failure ("Unable to Add User...").
            ConflictError: If the user id already exists (pre-read or race).
        """
        self._AssertAdmin(currentUser)
        self._ValidateAddFields(userCreate)
        existingUser = await self.userRepository.GetByUserId(session, userCreate.user_id)
        if existingUser is not None:
            raise ConflictError(MSG_USER_ALREADY_EXISTS)
        newUser = self._BuildUser(userCreate)
        await self._PersistNewUser(session, newUser)
        LOGGER.info(self._BuildSuccessMessage(newUser.user_id, ADDED_ACTION))
        return UserRead.model_validate(newUser)

    async def UpdateUser(
        self,
        session: AsyncSession,
        userId: str,
        userUpdate: UserUpdate,
        currentUser: User,
    ) -> UserRead:
        """Update an existing user (COUSR02C, CU02).

        Validates the mandatory fields in COUSR02C order, reads the current
        record (miss -> "User ID NOT found..."), and rejects a no-op edit with
        "Please modify to update ...". Otherwise it applies the changes
        (re-hashing the password only when a new, different one is supplied),
        then persists and commits via :meth:`_PersistUpdatedUser`.

        Args:
            session: The active async database session.
            userId: The path-supplied user id (immutable key).
            userUpdate: The update DTO (first/last name, type, optional password).
            currentUser: The acting administrator (mandatory; from
                ``require_admin``), re-checked in depth by :meth:`_AssertAdmin`.

        Returns:
            The updated user as a :class:`~app.schemas.UserRead` (no password).

        Raises:
            AuthorizationError: If ``currentUser`` is not an administrator.
            DomainValidationError: On a blank field, an unchanged request
                ("Please modify to update ..."), or a database failure
                ("Unable to Update User...").
            NotFoundError: If no user matches or the read fails.
            ConflictError: If the update would demote the last administrator
                (F3 last-administrator invariant) or would strip the acting
                administrator's own admin role (M-04 self-demotion guard).
        """
        self._AssertAdmin(currentUser)
        self._ValidateUpdateFields(userId, userUpdate)
        foundUser = await self._LookupUser(session, userId)
        if not self._DetectChanges(foundUser, userUpdate):
            raise DomainValidationError(MSG_NO_CHANGES)
        # F3: refuse a demotion (admin -> regular) that would remove the last
        # administrator. Checked after the no-op guard so an unchanged request
        # still reports "Please modify to update ...", and before the mutation so
        # the FOR UPDATE admin-row lock is held through the commit below.
        await self._AssertNotLastAdmin(session, foundUser, userUpdate.user_type)
        # M-04: refuse a self-demotion (the acting admin stripping its OWN admin
        # role). Checked AFTER the last-admin guard so removing the sole admin
        # still reports the systemic reason; a self-demotion that leaves other
        # admins intact is reported as the self-action it is.
        self._AssertNotSelfDemotion(currentUser, foundUser, userUpdate.user_type)
        self._ApplyChanges(foundUser, userUpdate)
        await self._PersistUpdatedUser(session, foundUser)
        LOGGER.info(self._BuildSuccessMessage(foundUser.user_id, UPDATED_ACTION))
        return UserRead.model_validate(foundUser)

    async def DeleteUser(
        self,
        session: AsyncSession,
        userId: str,
        currentUser: User,
    ) -> MessageResponse:
        """Delete an existing user (COUSR03C, CU03).

        Rejects a blank id with "User ID can NOT be empty...", reads the record
        (miss -> "User ID NOT found..."), then deletes and commits via
        :meth:`_PersistDeletedUser`. Returns the COBOL-parity confirmation
        "User <id> has been deleted ...". The legacy PF5 confirmation gesture is
        a router/UI concern and is not enforced here.

        Args:
            session: The active async database session.
            userId: The user id to delete.
            currentUser: The acting administrator (mandatory; from
                ``require_admin``), re-checked in depth by :meth:`_AssertAdmin`.

        Returns:
            A :class:`~app.schemas.MessageResponse` carrying the confirmation.

        Raises:
            AuthorizationError: If ``currentUser`` is not an administrator.
            DomainValidationError: If ``userId`` is blank or the delete fails
                ("Unable to Update User..." -- the COUSR03C OTHER text).
            NotFoundError: If no user matches or the read fails.
            ConflictError: If the delete would remove the last administrator
                (F3 last-administrator invariant) or would delete the acting
                administrator's own account (M-04 self-delete guard).
        """
        self._AssertAdmin(currentUser)
        self._RequireField(USER_ID_LABEL, userId, MSG_USER_ID_REQUIRED)
        foundUser = await self._LookupUser(session, userId)
        # F3: refuse to delete the last administrator (NULL new-type sentinel
        # signals a delete). The FOR UPDATE admin-row lock is held through the
        # commit in _PersistDeletedUser, serializing concurrent removals.
        await self._AssertNotLastAdmin(session, foundUser, None)
        # M-04: refuse a self-delete (the acting admin removing its OWN account).
        # Checked AFTER the last-admin guard so removing the sole admin still
        # reports the systemic reason; a self-delete that leaves other admins
        # intact is reported as the self-action it is.
        self._AssertNotSelfDelete(currentUser, foundUser)
        await self._PersistDeletedUser(session, foundUser)
        successMessage = self._BuildSuccessMessage(foundUser.user_id, DELETED_ACTION)
        LOGGER.info(successMessage)
        return MessageResponse(message=successMessage)

