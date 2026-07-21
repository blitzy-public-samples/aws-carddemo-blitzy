"""Menu DTOs. Source: app/cpy/COMEN02Y.cpy (regular, 10 options) + app/cpy/COADM02Y.cpy (admin, 4 options); screens COMEN01/COADM01.

Pydantic v2 data-transfer objects for the CardDemo navigation menus. They model
the role-based navigation that replaces the legacy 3270 menu-driven flow
(technical specification section 0.4.4): :class:`MenuResponse` is returned by
the regular-user menu endpoint ``GET /menu`` (transaction CM00, screen
``COMEN01``) and by the admin menu endpoint (transaction CA00, screen
``COADM01``). The menu service filters options by role on the server, so a
regular ('U') user never receives admin-only options.

Legacy record layouts (REFERENCE only):
    * ``app/cpy/COMEN02Y.cpy`` -- CARDDEMO-MAIN-MENU-OPTIONS, a ten-entry option
      table. Each entry carries CDEMO-MENU-OPT-NUM ``PIC 9(02)``,
      CDEMO-MENU-OPT-NAME ``PIC X(35)``, CDEMO-MENU-OPT-PGMNAME ``PIC X(08)``,
      and CDEMO-MENU-OPT-USRTYPE ``PIC X(01)`` (the role permitted to see the
      row -- all ten regular-menu rows are ``'U'``).
    * ``app/cpy/COADM02Y.cpy`` -- CARDDEMO-ADMIN-MENU-OPTIONS, a four-entry
      option table with CDEMO-ADMIN-OPT-NUM ``PIC 9(02)``, CDEMO-ADMIN-OPT-NAME
      ``PIC X(35)``, and CDEMO-ADMIN-OPT-PGMNAME ``PIC X(08)``. There is no
      per-row user-type gate because the whole admin menu is admin-only.

Field names are snake_case to match the JSON wire contract shared with
``frontend/src/types/menu.ts``; class names use PascalCase per the Ochs naming
rule (technical specification section 0.8.3). This module builds only on
:class:`app.schemas.common.OrmBase` and imports from no sibling schema module.
"""

from typing import Optional

from pydantic import Field

from app.schemas.common import OrmBase

__all__ = [
    "MenuOption",
    "MenuResponse",
]

# ---------------------------------------------------------------------------
# Field bounds (Ochs Rule section 0.8.2: constants are ALL_UPPERCASE).
#
# These mirror the fixed-width picture clauses of the legacy option tables so
# the DTO accepts exactly the value ranges the mainframe screens produced:
#   * OPTION_NUMBER_* -- CDEMO-*-OPT-NUM  PIC 9(02)  -> two-digit 0..99 index.
#   * OPTION_NAME_MAX_LENGTH    -- CDEMO-*-OPT-NAME     PIC X(35).
#   * PROGRAM_NAME_MAX_LENGTH   -- CDEMO-*-OPT-PGMNAME  PIC X(08).
#   * USER_TYPE_MAX_LENGTH      -- CDEMO-MENU-OPT-USRTYPE PIC X(01).
# ---------------------------------------------------------------------------
OPTION_NUMBER_MIN = 0
OPTION_NUMBER_MAX = 99
OPTION_NAME_MAX_LENGTH = 35
PROGRAM_NAME_MAX_LENGTH = 8
USER_TYPE_MAX_LENGTH = 1


class MenuOption(OrmBase):
    """A single selectable navigation-menu entry.

    Mirrors one row of the legacy option tables (CDEMO-MENU-OPT in
    ``COMEN02Y`` / CDEMO-ADMIN-OPT in ``COADM02Y``). ``program_name`` preserves
    the original CICS program identifier (for example ``COACTVWC``) for
    traceability and back-end routing, even though the frontend maps each option
    to a client-side route rather than transferring control to the program
    directly.
    """

    option_number: int = Field(
        ge=OPTION_NUMBER_MIN,
        le=OPTION_NUMBER_MAX,
        description=(
            "Menu index displayed beside the option and typed by the user to "
            "select it. Maps to CDEMO-*-OPT-NUM PIC 9(02), so the value "
            "occupies the two-digit 0-99 range."
        ),
    )
    option_name: str = Field(
        max_length=OPTION_NAME_MAX_LENGTH,
        description=("Human-readable option label, for example 'Account View'. Maps to CDEMO-*-OPT-NAME PIC X(35)."),
    )
    program_name: str = Field(
        max_length=PROGRAM_NAME_MAX_LENGTH,
        description=(
            "Originating CICS program identifier (for example 'COACTVWC'), "
            "retained for traceability and back-end routing. Maps to "
            "CDEMO-*-OPT-PGMNAME PIC X(08)."
        ),
    )
    user_type: Optional[str] = Field(
        default=None,
        max_length=USER_TYPE_MAX_LENGTH,
        description=(
            "Role permitted to see this option: 'A' (admin) or 'U' (regular "
            "user). None or empty means the option is visible to every role. "
            "Maps to CDEMO-MENU-OPT-USRTYPE PIC X(01); the admin menu "
            "(COADM02Y) omits this per-row gate because the whole menu is "
            "admin-only."
        ),
    )


class MenuResponse(OrmBase):
    """Role-filtered collection of menu options returned to the client.

    Returned by the regular-user menu endpoint (``GET /menu``, CM00 /
    ``COMEN01``) and the admin menu endpoint (CA00 / ``COADM01``). The menu
    service applies role filtering before building this response, so admin-only
    options are already excluded for a regular ('U') user (technical
    specification section 0.4.4 role-based rendering); the per-option
    ``user_type`` is still carried so the client can render or annotate role
    context.
    """

    menu_options: list[MenuOption] = Field(
        ...,
        description="Ordered list of options the requesting role may select.",
    )
    menu_title: Optional[str] = Field(
        default=None,
        description=("Optional heading for the rendered menu, for example 'Main Menu' or 'Admin Menu'."),
    )
    user_type: Optional[str] = Field(
        default=None,
        max_length=USER_TYPE_MAX_LENGTH,
        description=(
            "Role the menu was assembled for: 'A' (admin) or 'U' (regular "
            "user). None when the caller's role is unspecified."
        ),
    )
