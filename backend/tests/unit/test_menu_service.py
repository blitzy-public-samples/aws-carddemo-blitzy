# Unit tests for app.services.menu_service.MenuService
# Traceability: app/cbl/COMEN01C.cbl (CM00), app/cbl/COADM01C.cbl (CA00),
#   copybooks COMEN02Y.cpy (10 opts), COADM02Y.cpy (4 opts)
"""Unit tests for :class:`app.services.menu_service.MenuService`.

These tests port the behavior of the legacy CICS menu programs COMEN01C
(regular main menu, transaction CM00) and COADM01C (admin menu, transaction
CA00). They lock three contracts that must never silently regress:

* The regular menu exposes exactly ten options (COMEN02Y
  ``CDEMO-MENU-OPT-COUNT VALUE 10``) and the admin menu exactly four
  (COADM02Y ``CDEMO-ADMIN-OPT-COUNT VALUE 4``).
* Every regular option is well formed and numbered 1..10 in copybook order.
* The admin menu is admin-only: a regular ('U') user is refused with the
  verbatim COMEN01C guard message ``'No access - Admin Only option... '``
  (note the significant trailing space, COMEN01C L140), while an administrator
  ('A') receives the four-option menu without error.

The suite runs under ``asyncio_mode = "auto"`` (see ``backend/pyproject.toml``),
so each behavior is an ``async def test_*`` with no explicit marker. Identities
come from the shared ``admin_user`` / ``regular_user`` fixtures in
``backend/tests/conftest.py`` (seed-only ADMIN001 / USER0001); no network access
and no real credentials are involved.

Ochs naming (technical specification section 0.8.3): the test functions stay
snake_case (the pytest discovery contract), the reused helper is PascalCase
(:func:`MessageOf`), local variables are camelCase, and the expected-value
constants are ALL_UPPERCASE.
"""

import pytest

from app.core.exceptions import AuthorizationError
from app.services.menu_service import (
    ADMIN_MENU_OPTIONS,
    MenuService,
    REGULAR_MENU_OPTIONS,
)

# ---------------------------------------------------------------------------
# Expected values, encoded here INDEPENDENTLY of the implementation so that a
# regression in the service (or in the ported copybook counts / guard text) is
# actually caught by these tests rather than silently mirrored.
#
#   * EXPECTED_REGULAR_OPTION_COUNT -- COMEN02Y CDEMO-MENU-OPT-COUNT VALUE 10.
#   * EXPECTED_ADMIN_OPTION_COUNT   -- COADM02Y CDEMO-ADMIN-OPT-COUNT VALUE 4.
#   * ADMIN_ONLY_DENIAL_MESSAGE     -- COMEN01C L140 guard text; the TRAILING
#     SPACE before the closing quote is significant and is preserved verbatim.
# ---------------------------------------------------------------------------
EXPECTED_REGULAR_OPTION_COUNT = 10
EXPECTED_ADMIN_OPTION_COUNT = 4
ADMIN_ONLY_DENIAL_MESSAGE = "No access - Admin Only option... "


def MessageOf(error):
    """Return the human-readable message carried by a domain error.

    CardDemo domain errors (see ``app.core.exceptions.CardDemoError``) store the
    text on a ``message`` attribute and also forward it to ``Exception`` so that
    ``str(error)`` yields the same value. Reading ``message`` first and falling
    back to ``str`` keeps the assertions robust regardless of which surface a
    caller inspects.

    Args:
        error: The raised exception instance to read the message from.

    Returns:
        The error's message text.
    """
    return getattr(error, "message", None) or str(error)


# ===========================================================================
# Phase A -- regular main menu (COMEN01C / CM00, ten options).
# ===========================================================================


async def test_get_menu_returns_ten_options(db_session, regular_user):
    """GetMenu returns the ten-option regular menu for a regular user.

    Locks the count against both the concrete response and the module-level
    ``REGULAR_MENU_OPTIONS`` table (COMEN02Y).
    """
    service = MenuService()
    response = await service.GetMenu(db_session, regular_user)
    assert len(response.menu_options) == EXPECTED_REGULAR_OPTION_COUNT
    assert len(response.menu_options) == len(REGULAR_MENU_OPTIONS)


async def test_regular_menu_option_count_constant():
    """The regular option table locks the COMEN02Y count of ten."""
    assert len(REGULAR_MENU_OPTIONS) == EXPECTED_REGULAR_OPTION_COUNT


async def test_get_menu_options_are_well_formed(db_session, regular_user):
    """Every regular option is well formed and numbered 1..10 in copybook order.

    Each option must carry a non-empty ``option_name`` and ``program_name``, and
    the option numbers must be the exact ascending sequence 1..10 (COMEN02Y row
    order).
    """
    service = MenuService()
    response = await service.GetMenu(db_session, regular_user)
    for menuOption in response.menu_options:
        assert menuOption.option_name
        assert menuOption.program_name
    optionNumbers = [menuOption.option_number for menuOption in response.menu_options]
    assert optionNumbers == list(range(1, EXPECTED_REGULAR_OPTION_COUNT + 1))


# ===========================================================================
# Phase B -- admin menu (COADM01C / CA00, four options).
# ===========================================================================


async def test_get_admin_menu_returns_four_options(db_session, admin_user):
    """GetAdminMenu returns the four-option admin menu for an administrator.

    Locks the count against both the concrete response and the module-level
    ``ADMIN_MENU_OPTIONS`` table (COADM02Y).
    """
    service = MenuService()
    response = await service.GetAdminMenu(db_session, admin_user)
    assert len(response.menu_options) == EXPECTED_ADMIN_OPTION_COUNT
    assert len(response.menu_options) == len(ADMIN_MENU_OPTIONS)


async def test_admin_menu_option_count_constant():
    """The admin option table locks the COADM02Y count of four."""
    assert len(ADMIN_MENU_OPTIONS) == EXPECTED_ADMIN_OPTION_COUNT


# ===========================================================================
# Phase C -- admin gating / authorization (COMEN01C L136-140).
# ===========================================================================


async def test_regular_user_denied_admin_menu(db_session, regular_user):
    """A regular user is refused the admin menu with the verbatim guard text.

    Asserts the specific :class:`AuthorizationError` is raised and that its
    message equals the COMEN01C L140 text EXACTLY, including the trailing space.
    """
    service = MenuService()
    with pytest.raises(AuthorizationError) as excInfo:
        await service.GetAdminMenu(db_session, regular_user)
    assert MessageOf(excInfo.value) == ADMIN_ONLY_DENIAL_MESSAGE
    assert MessageOf(excInfo.value).startswith("No access - Admin Only option")


async def test_admin_user_allowed_admin_menu(db_session, admin_user):
    """An administrator receives the four-option admin menu without error."""
    service = MenuService()
    response = await service.GetAdminMenu(db_session, admin_user)
    assert len(response.menu_options) == EXPECTED_ADMIN_OPTION_COUNT
    assert len(response.menu_options) == len(ADMIN_MENU_OPTIONS)
