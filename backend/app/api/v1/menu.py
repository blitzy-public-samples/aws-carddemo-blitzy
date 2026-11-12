"""Menu router (thin HTTP layer) for the CardDemo backend.

Modern REST replacement for TWO legacy CICS online menu programs (AAP 0.5.1,
0.8.1):

    * ``COMEN01C`` -- "Main Menu for the Regular users", transaction **CM00**,
      BMS map **COMEN01** (``app/bms/COMEN01.bms``, option copybook
      ``app/cpy/COMEN02Y.cpy``). Exposed here as ``GET /menu``.
    * ``COADM01C`` -- "Admin Menu for Admin users", transaction **CA00**, BMS
      map **COADM01** (``app/bms/COADM01.bms``, option copybook
      ``app/cpy/COADM02Y.cpy``). Exposed here as ``GET /admin/menu``.

Role in the architecture (AAP 0.4.1 / 0.4.3): this module is a *thin* router.
It performs no business logic and no database access of its own. The menu
option tables, titles, and role gating live entirely in
:class:`~app.services.menu_service.MenuService` (the 1:1 port of the two COBOL
programs); the router only wires HTTP requests to service calls and declares
the response schema. It forwards the request-scoped async session obtained from
``Depends(get_db)`` and the authenticated user obtained from
``Depends(get_current_user)`` -- the stateless successors to the legacy
``COCOM01Y`` COMMAREA identity/role propagation.

Path convention: this router intentionally declares **no** ``prefix``. The two
routes (``/menu`` and ``/admin/menu``) share no common resource segment, and
``/admin/menu`` must not nest under ``/menu``, so each route carries its full
sub-path. The versioned API prefix is never hardcoded here; it is applied by
``app.main`` when the router is mounted.

Authorization (F-002): ``GET /admin/menu`` is admin-only. The primary gate is
the route-level dependency ``Depends(require_admin)``, which raises HTTP 403 for
any non-administrator (``user_type != 'A'``) before the handler body runs.
``MenuService.GetAdminMenu`` re-checks the role as defense in depth and raises
:class:`~app.core.exceptions.AuthorizationError`; both the 403 from
``require_admin`` and any bubbled ``AuthorizationError`` (401 for
``AuthenticationError``) are translated by the application-level handlers in
``app.main``. This module therefore contains no ``try``/``except`` blocks.

Naming follows the Ochs resolution (AAP 0.8.3): the handler methods are
PascalCase (:func:`GetMenu`, :func:`GetAdminMenu`) and local variables are
camelCase (``currentUser``); only the module/file name stays snake_case. The
dependency-provider names (``get_db``, ``get_current_user``, ``require_admin``)
are the imported framework contract and keep their canonical snake_case.
"""

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.dependencies import get_db, get_current_user, require_admin
from app.services import MenuService
from app.schemas import MenuResponse

# Router with no prefix (see the module docstring): each route declares its full
# sub-path, and the versioned API mount prefix is added by ``app.main``. The
# ``menu`` tag groups both endpoints together in the generated OpenAPI schema.
router = APIRouter(tags=["menu"])


@router.get("/menu", response_model=MenuResponse)
async def GetMenu(
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> MenuResponse:
    """Return the regular main menu for any authenticated user.

    Ports ``COMEN01C`` (transaction CM00, BMS map COMEN01). The ten-option
    regular menu is available to every authenticated user; administrators reach
    their additional options through :func:`GetAdminMenu`. All menu content,
    titles, and role tagging come from
    :class:`~app.services.menu_service.MenuService`; this handler adds no
    business logic and issues no query itself.

    Args:
        session: The request-scoped async database session (unit-of-work)
            provided by :func:`~app.core.dependencies.get_db` and forwarded to
            the service. The static menu performs no query and never commits.
        currentUser: The authenticated user (identity + role) resolved by
            :func:`~app.core.dependencies.get_current_user` -- the stateless
            successor to the legacy COCOM01Y COMMAREA identity.

    Returns:
        The role-tagged :class:`~app.schemas.menu.MenuResponse` holding the ten
        regular menu options in copybook order.
    """
    return await MenuService().GetMenu(session, currentUser)


@router.get(
    "/admin/menu",
    response_model=MenuResponse,
    dependencies=[Depends(require_admin)],
)
async def GetAdminMenu(
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> MenuResponse:
    """Return the admin menu (admin-only, F-002).

    Ports ``COADM01C`` (transaction CA00, BMS map COADM01). Access is gated by
    the route-level ``Depends(require_admin)`` dependency declared above, which
    rejects any caller whose ``user_type`` is not ``'A'`` with HTTP 403 before
    this body runs.
    :meth:`~app.services.menu_service.MenuService.GetAdminMenu` re-validates the
    role as defense in depth and raises
    :class:`~app.core.exceptions.AuthorizationError` for a non-admin; that error
    bubbles to the application-level 403 handler in ``app.main``.

    Args:
        session: The request-scoped async database session (unit-of-work)
            provided by :func:`~app.core.dependencies.get_db` and forwarded to
            the service. The static menu performs no query and never commits.
        currentUser: The authenticated user (identity + role) resolved by
            :func:`~app.core.dependencies.get_current_user`.

    Returns:
        The role-tagged :class:`~app.schemas.menu.MenuResponse` holding the four
        admin menu options in copybook order.
    """
    return await MenuService().GetAdminMenu(session, currentUser)
