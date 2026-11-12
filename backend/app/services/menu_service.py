"""Menu service.

Ported 1:1 from legacy CICS menu programs COMEN01C (main menu, CM00) and
COADM01C (admin menu, CA00). Menu option tables come from copybooks COMEN02Y
(10 regular options) and COADM02Y (4 admin options). Role gating (admin vs
regular) previously used the COCOM01Y COMMAREA CDEMO-USER-TYPE field; here it
uses the authenticated user's user_type ('A' admin / 'U' regular). See section 0.4.4, section 0.5.1, section 0.8.1.

Design (preserves F-002 and the COMEN01C rule):
    * The regular menu (COMEN01) is available to every authenticated user and is
      returned by :meth:`MenuService.GetMenu`.
    * The admin menu (COADM01) is admin-only and is returned by
      :meth:`MenuService.GetAdminMenu`; a non-admin caller is rejected with
      :class:`~app.core.exceptions.AuthorizationError` carrying the verbatim
      COMEN01C guard text 'No access - Admin Only option... '.
    * :meth:`MenuService.SelectOption` reproduces the COMEN01C PROCESS-ENTER-KEY
      option-number validation (the two on-screen messages are preserved
      character-for-character) for callers that still submit a typed option
      number rather than clicking a rendered button/link.

The menus are static reference tables, so every method is read-only: no
repository is used and ``session.commit()`` is never called. The ``session``
parameter is accepted only to keep the router -> service calling convention
uniform with the data-backed services (technical specification section 0.5.1).

Naming follows the Ochs resolution (technical specification section 0.8.3):
the class and its methods are PascalCase (:class:`MenuService`, ``GetMenu``),
local variables are camelCase, and module-level constants are ALL_UPPERCASE;
only the file name itself stays snake_case.
"""

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.dependencies import ADMIN_USER_TYPE, REGULAR_USER_TYPE
from app.core.exceptions import AuthorizationError, DomainValidationError
from app.models.user import User
from app.schemas import MenuOption, MenuResponse

__all__ = [
    "MenuService",
    "REGULAR_MENU_OPTIONS",
    "ADMIN_MENU_OPTIONS",
    "MAIN_MENU_TITLE",
    "ADMIN_MENU_TITLE",
    "INVALID_OPTION_MESSAGE",
    "ADMIN_ONLY_MESSAGE",
]

# ---------------------------------------------------------------------------
# Menu titles and on-screen messages (Ochs Rule section 0.8.2: constants are
# ALL_UPPERCASE). The two message strings are reproduced VERBATIM from the
# COMEN01C PROCESS-ENTER-KEY paragraph and must never be reworded, re-cased, or
# re-punctuated -- note the trailing space that COMEN01C carries after the
# ellipsis of the admin-only message (app/cbl/COMEN01C.cbl L131, L140).
# ---------------------------------------------------------------------------
MAIN_MENU_TITLE = "Main Menu"
ADMIN_MENU_TITLE = "Admin Menu"
INVALID_OPTION_MESSAGE = "Please enter a valid option number..."
ADMIN_ONLY_MESSAGE = "No access - Admin Only option... "

# Lowest valid typed option number. COMEN01C rejects WS-OPTION = ZEROS, so the
# first selectable option is 1 (app/cbl/COMEN01C.cbl L127-129).
MIN_MENU_OPTION_NUMBER = 1

# ---------------------------------------------------------------------------
# Ported option tables. The raw (number, name, program) rows below are the
# faithful contents of the legacy copybooks -- COMEN02Y (CARDDEMO-MAIN-MENU-
# OPTIONS, 10 rows) and COADM02Y (CARDDEMO-ADMIN-MENU-OPTIONS, 4 rows). The
# option names are the exact CDEMO-*-OPT-NAME literals (trailing pad spaces
# trimmed); the program names are the exact CDEMO-*-OPT-PGMNAME literals kept
# for traceability and back-end routing. They are converted to the public
# ALL_UPPERCASE MenuOption tuples further below.
# ---------------------------------------------------------------------------
_REGULAR_MENU_ROWS: tuple[tuple[int, str, str], ...] = (
    (1, "Account View", "COACTVWC"),
    (2, "Account Update", "COACTUPC"),
    (3, "Credit Card List", "COCRDLIC"),
    (4, "Credit Card View", "COCRDSLC"),
    (5, "Credit Card Update", "COCRDUPC"),
    (6, "Transaction List", "COTRN00C"),
    (7, "Transaction View", "COTRN01C"),
    (8, "Transaction Add", "COTRN02C"),
    (9, "Transaction Reports", "CORPT00C"),
    (10, "Bill Payment", "COBIL00C"),
)

# COADM02Y carries the "(Security)" suffix on every admin option name; it is
# preserved exactly (1:1 port, technical specification section 0.8.1). The admin
# table has no per-row user-type field because the whole menu is admin-only, so
# every row is tagged with ADMIN_USER_TYPE here.
_ADMIN_MENU_ROWS: tuple[tuple[int, str, str], ...] = (
    (1, "User List (Security)", "COUSR00C"),
    (2, "User Add (Security)", "COUSR01C"),
    (3, "User Update (Security)", "COUSR02C"),
    (4, "User Delete (Security)", "COUSR03C"),
)


def _BuildOptionTable(
    rows: tuple[tuple[int, str, str], ...],
    userType: str,
) -> tuple[MenuOption, ...]:
    """Convert raw copybook rows into an immutable tuple of MenuOption objects.

    Keeps the option-table declarations compact and identical in shape for both
    menus, tagging every produced option with the supplied role code.

    Args:
        rows: Raw ``(option_number, option_name, program_name)`` triples in
            copybook order.
        userType: The role code stamped onto every option ('A' or 'U').

    Returns:
        An immutable tuple of :class:`~app.schemas.menu.MenuOption`, in order.
    """
    return tuple(
        MenuOption(
            option_number=number,
            option_name=name,
            program_name=program,
            user_type=userType,
        )
        for (number, name, program) in rows
    )


# Public ALL_UPPERCASE option tables (Ochs Rule section 0.8.2). Regular rows are
# tagged 'U' (REGULAR_USER_TYPE); admin rows are tagged 'A' (ADMIN_USER_TYPE).
REGULAR_MENU_OPTIONS: tuple[MenuOption, ...] = _BuildOptionTable(
    _REGULAR_MENU_ROWS,
    REGULAR_USER_TYPE,
)
ADMIN_MENU_OPTIONS: tuple[MenuOption, ...] = _BuildOptionTable(
    _ADMIN_MENU_ROWS,
    ADMIN_USER_TYPE,
)


class MenuService:
    """Navigation-menu business logic (ports COMEN01C and COADM01C).

    Supplies the role-appropriate menu option lists that replace the legacy 3270
    menu-driven flow (technical specification section 0.4.4). The service is
    stateless and holds no resources: the option tables are module-level
    reference data, so no ``__init__`` is required and every method is read-only.
    A regular ('U') user never receives admin options because the regular and
    admin menus are exposed through two distinct methods and the admin method is
    role-gated.
    """

    async def GetMenu(
        self,
        session: AsyncSession,
        currentUser: User,
    ) -> MenuResponse:
        """Return the regular main menu for any authenticated user.

        Ports COMEN01C (transaction CM00): the ten-option regular menu is
        visible to every authenticated user, administrators included (admins
        reach their extra options through :meth:`GetAdminMenu`). The response is
        tagged with the caller's actual role so the client can annotate context.

        Args:
            session: The request-scoped async session, accepted for calling-
                convention uniformity; the static menu performs no query and
                never commits.
            currentUser: The authenticated user resolved by ``get_current_user``.

        Returns:
            A :class:`~app.schemas.menu.MenuResponse` titled 'Main Menu' holding
            the ten regular options in copybook order.
        """
        return self._BuildMenu(
            REGULAR_MENU_OPTIONS,
            MAIN_MENU_TITLE,
            currentUser.user_type,
        )

    async def GetAdminMenu(
        self,
        session: AsyncSession,
        currentUser: User,
    ) -> MenuResponse:
        """Return the admin menu, rejecting non-admin callers.

        Ports COADM01C (transaction CA00). Reproduces the COMEN01C admin guard:
        a caller whose ``user_type`` is not :data:`ADMIN_USER_TYPE` ('A') is
        refused with :class:`~app.core.exceptions.AuthorizationError` carrying
        the verbatim message 'No access - Admin Only option... ' (HTTP 403).

        Args:
            session: The request-scoped async session, accepted for calling-
                convention uniformity; the static menu performs no query and
                never commits.
            currentUser: The authenticated user resolved by ``get_current_user``.

        Returns:
            A :class:`~app.schemas.menu.MenuResponse` titled 'Admin Menu' holding
            the four admin options in copybook order.

        Raises:
            AuthorizationError: If the authenticated user is not an administrator.
        """
        if currentUser.user_type != ADMIN_USER_TYPE:
            raise AuthorizationError(ADMIN_ONLY_MESSAGE)
        return self._BuildMenu(
            ADMIN_MENU_OPTIONS,
            ADMIN_MENU_TITLE,
            ADMIN_USER_TYPE,
        )

    async def SelectOption(
        self,
        session: AsyncSession,
        currentUser: User,
        optionNumber: int,
    ) -> MenuOption:
        """Validate and resolve a typed regular-menu option number.

        Ports the COMEN01C PROCESS-ENTER-KEY edits for callers that submit a
        numeric selection instead of clicking a rendered option. An out-of-range
        number (below :data:`MIN_MENU_OPTION_NUMBER` or above the option count)
        is rejected with the verbatim 'Please enter a valid option number...'
        text; a regular user selecting an admin-flagged option is rejected with
        the verbatim 'No access - Admin Only option... ' text.

        Args:
            session: The request-scoped async session, accepted for calling-
                convention uniformity; no query is issued and nothing is
                committed.
            currentUser: The authenticated user resolved by ``get_current_user``.
            optionNumber: The one-based option number the caller selected.

        Returns:
            A copy of the selected :class:`~app.schemas.menu.MenuOption`.

        Raises:
            DomainValidationError: If ``optionNumber`` is outside the valid range.
            AuthorizationError: If a regular user selects an admin-only option.
        """
        if optionNumber < MIN_MENU_OPTION_NUMBER or optionNumber > len(REGULAR_MENU_OPTIONS):
            raise DomainValidationError(INVALID_OPTION_MESSAGE)
        selectedOption = REGULAR_MENU_OPTIONS[optionNumber - 1]
        if currentUser.user_type == REGULAR_USER_TYPE and selectedOption.user_type == ADMIN_USER_TYPE:
            raise AuthorizationError(ADMIN_ONLY_MESSAGE)
        return selectedOption.model_copy()

    def _BuildMenu(
        self,
        options: tuple[MenuOption, ...],
        title: str,
        userType: str | None,
    ) -> MenuResponse:
        """Assemble a MenuResponse from an option table, title, and role.

        Copies each option so the returned response never shares mutable state
        with the module-level tables (defensive against accidental mutation by a
        caller); the copy is cheap given the small, fixed table sizes.

        Args:
            options: The immutable source option table to expose.
            title: The heading for the rendered menu.
            userType: The role the menu was assembled for, or ``None``.

        Returns:
            A fully populated :class:`~app.schemas.menu.MenuResponse`.
        """
        menuOptions = [option.model_copy() for option in options]
        return MenuResponse(
            menu_options=menuOptions,
            menu_title=title,
            user_type=userType,
        )
