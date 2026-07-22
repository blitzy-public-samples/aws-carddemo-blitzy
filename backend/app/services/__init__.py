"""Business-logic (service) layer for the CardDemo backend (``app.services``).

One service module per legacy CICS online program cluster (AAP 0.4.3 service
layer): each service holds the ported PROCEDURE DIVISION business rules 1:1 with
its COBOL origin (Minimal Change Clause, AAP 0.8.1), orchestrating the thin
repositories and enforcing the validations, optimistic-lock semantics, and
numeric rules the mainframe programs performed. Routers (``app.api.v1``) stay
thin and delegate here; repositories (``app.repositories``) own data access.

This ``__init__`` is a thin, purely declarative aggregator. It re-exports the
concrete service classes so callers can write::

    from app.services import AuthService, AccountService

instead of reaching into each submodule path individually. The longhand form
(``from app.services.auth_service import AuthService``) remains equally valid
and is what the sibling modules and tests use internally.

Legacy origin of each re-exported service (AAP 0.5.1):
    * AuthService         -- COSGN00C (signon, tx CC00)
    * MenuService         -- COMEN01C + COADM01C (main + admin menu)
    * AccountService      -- COACTVWC + COACTUPC (account view + update)
    * CardService         -- COCRDLIC + COCRDSLC + COCRDUPC (list/view/update)
    * TransactionService  -- COTRN00C + COTRN01C + COTRN02C (list/view/add)
    * BillPayService      -- COBIL00C (bill payment)
    * ReportService       -- CORPT00C (transaction report)
    * UserAdminService    -- COUSR00C-COUSR03C (user CRUD, admin-gated)

Side-effect discipline: importing a service CLASS pulls in that service's
repositories/schemas/core/utils, but it never instantiates a repository, opens
a database connection, creates a session/engine, reads request state, or
configures logging -- so ``import app.services`` stays cheap and safe. The
sibling modules address each other (and the layers below) only by explicit
submodule path; none imports this package root, and this aggregator never
imports from ``app.api`` / ``app.main``, keeping the import graph acyclic. The
``__all__`` list below both documents the package's public surface and marks
each re-exported name as used, so linters do not flag the imports as unused
(F401).
"""

from app.services.auth_service import AuthService
from app.services.menu_service import MenuService
from app.services.account_service import AccountService
from app.services.card_service import CardService
from app.services.transaction_service import TransactionService
from app.services.billpay_service import BillPayService
from app.services.report_service import ReportService
from app.services.user_admin_service import UserAdminService

__all__ = [
    "AuthService",
    "MenuService",
    "AccountService",
    "CardService",
    "TransactionService",
    "BillPayService",
    "ReportService",
    "UserAdminService",
]
