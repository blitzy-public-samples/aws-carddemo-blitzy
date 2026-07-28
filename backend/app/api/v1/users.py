# =============================================================================
# Admin user-management router (module ``app.api.v1.users``).
#
# Traceability (AAP 0.5.1 / 0.8.1): modern REST replacement for FOUR legacy
# CICS online COBOL programs that performed admin-only user maintenance against
# the VSAM ``USRSEC`` security file (record layout ``app/cpy/CSUSR01Y.cpy``):
#
#     COUSR00C  (tx CU00, BMS map COUSR00)  "List all users from USRSEC file"
#     COUSR01C  (tx CU01, BMS map COUSR01)  "Add a new Regular/Admin user"
#     COUSR02C  (tx CU02, BMS map COUSR02)  "Update a user in USRSEC file"
#     COUSR03C  (tx CU03, BMS map COUSR03)  "Delete a user from USRSEC file"
# =============================================================================
"""Admin user-management router (``/admin/users``) for the CardDemo backend.

Thin HTTP routing layer that re-expresses the admin-only user CRUD screens of
the legacy mainframe as REST endpoints. It is the 1:1 modern replacement
(Minimal Change Clause, AAP 0.8.1) for four CICS online COBOL programs:

    * ``COUSR00C`` -- tx ``CU00``, BMS map ``COUSR00`` -- list all users.
    * ``COUSR01C`` -- tx ``CU01``, BMS map ``COUSR01`` -- add a regular/admin user.
    * ``COUSR02C`` -- tx ``CU02``, BMS map ``COUSR02`` -- update a user.
    * ``COUSR03C`` -- tx ``CU03``, BMS map ``COUSR03`` -- delete a user.

Architecture (AAP 0.4.1 / 0.4.3): this module holds NO business logic and NO
data access. Every route delegates to :class:`app.services.UserAdminService`,
which owns the ported PROCEDURE DIVISION rules and the unit-of-work boundary;
password hashing lives in ``app.core.security`` (the legacy 8-character
plaintext ``SEC-USR-PWD`` is uplifted to a bcrypt hash and is never stored or
returned).

Authorization (F-002; QA finding M-04): all user maintenance is admin-only. The
router-level dependency ``Depends(require_admin)`` gates EVERY route in this
module on the ported role rule ``user_type == 'A'`` (legacy COMMAREA
``CDEMO-USRTYP-ADMIN``), returning HTTP 403 for a regular user. ``require_admin``
re-derives the caller identity per request (the stateless successor to COMMAREA
propagation) and RETURNS the acting administrator; each handler now captures that
identity (``currentUser: User = Depends(require_admin)``) and passes it to the
service as the mandatory actor. The service re-checks the admin role in depth and
enforces the self-action policy (an administrator may not delete or demote its
own account), so authorization no longer depends on the router gate alone.

Sensitive data (AAP 0.7.8): responses are typed with
:class:`~app.schemas.UserRead` and :class:`~app.schemas.UserSummary`, neither of
which declares a ``password`` or ``password_hash`` field, so no secret can leak
through this router.

Endpoint-to-program map. ``app.main`` mounts this router under
``settings.API_V1_PREFIX`` (that prefix is never hardcoded here), so the final
paths are ``<API_V1_PREFIX>/admin/users`` and
``<API_V1_PREFIX>/admin/users/{userId}``:

    GET     <API_V1_PREFIX>/admin/users            -> ListUsers   (COUSR00C, CU00)
    POST    <API_V1_PREFIX>/admin/users            -> AddUser     (COUSR01C, CU01)
    GET     <API_V1_PREFIX>/admin/users/{userId}   -> GetUser     (view pre-read)
    PUT     <API_V1_PREFIX>/admin/users/{userId}   -> UpdateUser  (COUSR02C, CU02)
    DELETE  <API_V1_PREFIX>/admin/users/{userId}   -> DeleteUser  (COUSR03C, CU03)

Exception policy: domain exceptions raised by the service --
:class:`~app.core.exceptions.NotFoundError` (404),
:class:`~app.core.exceptions.DomainValidationError` (400/422),
:class:`~app.core.exceptions.AuthorizationError` (403), and
:class:`~app.core.exceptions.ConflictError` (409, duplicate user id) -- bubble
up to the application-level handlers registered in ``app.main``. This router
therefore performs no local exception handling of its own.

Naming (Ochs resolution, AAP 0.8.3): the module/file name is snake_case; the
handler methods are PascalCase (``ListUsers``/``AddUser``/``GetUser``/
``UpdateUser``/``DeleteUser``); local variables are camelCase. The provider
names imported from ``app.core.dependencies`` (``get_db``/``require_admin``)
keep their snake_case framework-contract spelling.
"""

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

# ``require_admin`` transitively resolves the caller identity (it depends on
# ``get_current_user``), enforces ``user_type == 'A'``, and RETURNS the acting
# administrator, which each handler captures and forwards to the service as the
# mandatory actor (M-04). The ORM ``User`` is imported solely to type that actor
# parameter (it is the type ``require_admin`` returns).
from app.core.dependencies import get_db, require_admin
from app.models.user import User
from app.schemas import (
    PaginatedResponse,
    PaginationParams,
    UserCreate,
    UserRead,
    UserSummary,
    UserUpdate,
)
from app.services import UserAdminService

# Router for the admin-only user-management screens (COUSR00C-COUSR03C).
#
# ``dependencies=[Depends(require_admin)]`` applies the admin gate to EVERY route
# declared below (F-002): a non-admin caller receives HTTP 403 before any handler
# body runs. The ``/admin/users`` prefix is relative -- ``app.main`` mounts this
# router under ``settings.API_V1_PREFIX``, yielding the final
# ``<API_V1_PREFIX>/admin/users`` paths -- so the version prefix is never
# hardcoded in this module.
router = APIRouter(
    prefix="/admin/users",
    tags=["users"],
    dependencies=[Depends(require_admin)],
)


@router.get("", response_model=PaginatedResponse[UserSummary])
async def ListUsers(
    session: AsyncSession = Depends(get_db),
    params: PaginationParams = Depends(),
    currentUser: User = Depends(require_admin),
    user_id: str | None = Query(
        default=None,
        max_length=8,
        description=(
            "Optional case-insensitive User ID prefix. When supplied, the "
            "browse is filtered server-side across the WHOLE user table to ids "
            "beginning with this text (COUSR00C search), so a matching user on "
            "any page is found -- not only those on the page already loaded."
        ),
    ),
) -> PaginatedResponse[UserSummary]:
    """List security users for the admin browse screen (COUSR00C, CU00).

    Modern replacement for the ``COUSR00C`` USRSEC browse (legacy STARTBR /
    READNEXT over the whole file, ordered by user id). Delegates paging to the
    service and returns one page of password-free
    :class:`~app.schemas.UserSummary` rows plus pagination metadata.

    The optional ``user_id`` query parameter carries the admin "Search User ID"
    box: it is normalized (trimmed; blank becomes no filter) and passed to the
    service as a case-insensitive prefix search that runs at the database over
    the entire table (QA I23), rather than the former client-only filter that
    inspected only the current page.

    Args:
        session: The request-scoped async database session (from ``get_db``).
        params: The ``page`` / ``page_size`` query parameters.
        currentUser: The acting administrator (from ``require_admin``), passed to
            the service as the mandatory actor for the in-depth admin re-check.
        user_id: Optional User ID prefix search term (``<= 8`` chars).

    Returns:
        A :class:`~app.schemas.PaginatedResponse` page of ``UserSummary`` rows.
    """
    userIdPrefix = user_id.strip() if user_id else None
    return await UserAdminService().ListUsers(
        session,
        params,
        currentUser,
        userIdPrefix=userIdPrefix or None,
    )


@router.post(
    "",
    status_code=status.HTTP_201_CREATED,
    response_model=UserRead,
)
async def AddUser(
    userCreate: UserCreate,
    session: AsyncSession = Depends(get_db),
    currentUser: User = Depends(require_admin),
) -> UserRead:
    """Add a new regular/admin user (COUSR01C, CU01).

    Modern replacement for the ``COUSR01C`` add-user screen. The service
    validates the mandatory fields in the legacy field order, rejects a
    duplicate id with HTTP 409 (:class:`~app.core.exceptions.ConflictError`),
    hashes the plaintext password via ``app.core.security`` (never stored or
    returned), then inserts and commits. Responds with HTTP 201 Created.

    Args:
        userCreate: The add-user request body (id, names, password, type).
        session: The request-scoped async database session (from ``get_db``).
        currentUser: The acting administrator (from ``require_admin``), passed to
            the service as the mandatory actor for the in-depth admin re-check.

    Returns:
        The created user as a password-free :class:`~app.schemas.UserRead`.
    """
    return await UserAdminService().AddUser(session, userCreate, currentUser)


@router.get("/{userId}", response_model=UserRead)
async def GetUser(
    userId: str,
    session: AsyncSession = Depends(get_db),
    currentUser: User = Depends(require_admin),
) -> UserRead:
    """Read a single security user by id.

    Modern equivalent of the ``READ USRSEC`` pre-read shared by the legacy
    update and delete screens (COUSR02C / COUSR03C). A missing user surfaces as
    :class:`~app.core.exceptions.NotFoundError` (HTTP 404).

    Args:
        userId: The user id path parameter (``SEC-USR-ID``, VARCHAR(8)).
        session: The request-scoped async database session (from ``get_db``).
        currentUser: The acting administrator (from ``require_admin``), passed to
            the service as the mandatory actor for the in-depth admin re-check.

    Returns:
        The matching user as a password-free :class:`~app.schemas.UserRead`.
    """
    return await UserAdminService().GetUser(session, userId, currentUser)


@router.put("/{userId}", response_model=UserRead)
async def UpdateUser(
    userId: str,
    userUpdate: UserUpdate,
    session: AsyncSession = Depends(get_db),
    currentUser: User = Depends(require_admin),
) -> UserRead:
    """Update an existing security user (COUSR02C, CU02).

    Modern replacement for the ``COUSR02C`` update-user screen. The service
    validates the fields, requires an actual change ("Please modify to update"),
    re-hashes the password only when a new one is supplied, then persists and
    commits. A missing user surfaces as
    :class:`~app.core.exceptions.NotFoundError` (HTTP 404). An attempt by the
    acting administrator to strip its own admin role is rejected with HTTP 409
    (the service self-demotion guard, M-04).

    Args:
        userId: The immutable user id path parameter (``SEC-USR-ID``).
        userUpdate: The update request body (names, type, optional password).
        session: The request-scoped async database session (from ``get_db``).
        currentUser: The acting administrator (from ``require_admin``), passed to
            the service as the mandatory actor for the in-depth admin re-check
            and the self-demotion guard.

    Returns:
        The updated user as a password-free :class:`~app.schemas.UserRead`.
    """
    return await UserAdminService().UpdateUser(
        session, userId, userUpdate, currentUser
    )


@router.delete("/{userId}", status_code=status.HTTP_204_NO_CONTENT)
async def DeleteUser(
    userId: str,
    session: AsyncSession = Depends(get_db),
    currentUser: User = Depends(require_admin),
) -> None:
    """Delete a security user by id (COUSR03C, CU03).

    Modern replacement for the ``COUSR03C`` delete-user screen. The service
    reads the record (missing -> :class:`~app.core.exceptions.NotFoundError`,
    HTTP 404), then deletes and commits. The service's confirmation message is
    intentionally discarded here: this endpoint returns HTTP 204 No Content with
    an empty body, so no ``response_model`` is declared. An attempt by the acting
    administrator to delete its own account is rejected with HTTP 409 (the
    service self-delete guard, M-04).

    Args:
        userId: The user id path parameter (``SEC-USR-ID``, VARCHAR(8)).
        session: The request-scoped async database session (from ``get_db``).
        currentUser: The acting administrator (from ``require_admin``), passed to
            the service as the mandatory actor for the in-depth admin re-check
            and the self-delete guard.

    Returns:
        ``None`` -- serialized as an empty HTTP 204 No Content response.
    """
    await UserAdminService().DeleteUser(session, userId, currentUser)
    return None
