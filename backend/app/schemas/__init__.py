"""Public Pydantic DTO surface for the CardDemo backend (``app.schemas``).

This package holds the request/response Data Transfer Objects (DTOs) that the
API layer (``app.api.v1``) and the service layer (``app.services``) exchange
over HTTP/JSON. Each sibling module ports one legacy artifact class -- a VSAM
record copybook (``app/cpy/*.cpy``), a BMS symbolic-map copybook
(``app/cpy-bms/*.CPY``), or a CICS COMMAREA/screen construct -- into typed,
validated Pydantic v2 models, preserving the copybook field lengths, numeric
precision, and edit rules exactly (Minimal Change Clause).

This ``__init__`` is a thin, purely declarative aggregator. It performs no
logic and holds no state; it simply re-exports the public DTO classes from the
sibling modules so that consumers may write::

    from app.schemas import AccountDetail, LoginRequest, PaginatedResponse

instead of reaching into each submodule path individually. The longhand form
(``from app.schemas.account import AccountDetail``) remains equally valid and
is what the sibling modules themselves use internally.

Import-graph notes:
    * ``common`` is the root of the schema dependency graph -- every other
      module builds on :class:`OrmBase` / :class:`RequestBase` defined there --
      so it is imported first below.
    * The sibling modules address each other only by explicit submodule path
      (for example ``from app.schemas.customer import CustomerRead``); none of
      them imports this package root (``app.schemas``). That keeps this
      aggregator free of circular-import hazards: importing ``app.schemas``
      triggers the leaf modules, never the reverse.

Traceability: the individual DTOs cite their originating COBOL copybooks / BMS
maps in their own module docstrings; this file only collects them. See
``README.md`` and ``docs/`` for the full legacy-to-modern mapping.
"""

# ---------------------------------------------------------------------------
# Explicit re-exports, ordered to follow the schema dependency graph
# (``common`` first). Wildcard imports are intentionally never used: every
# name below is imported explicitly so the public API is auditable and static
# analysers can resolve each symbol. Re-exported class names remain PascalCase
# per the Ochs Rule (§0.8.2/§0.8.3); only the module/file names are snake_case.
# ---------------------------------------------------------------------------

# Shared base models and cross-cutting response envelopes.
from app.schemas.common import (
    ErrorResponse,
    MessageResponse,
    OrmBase,
    PaginatedResponse,
    PaginationParams,
    RequestBase,
)

# Security user record (``app/cpy/CSUSR01Y.cpy``) -- online program COUSR00-03C.
from app.schemas.user import (
    UserBase,
    UserCreate,
    UserRead,
    UserSummary,
    UserUpdate,
)

# Customer master record (``app/cpy/CVCUS01Y.cpy``).
from app.schemas.customer import (
    CustomerBase,
    CustomerRead,
    CustomerSummary,
    CustomerUpdate,
)

# Account master record (``app/cpy/CVACT01Y.cpy``) -- programs COACTVWC/COACTUPC.
from app.schemas.account import (
    AccountBase,
    AccountBeforeImage,
    AccountDetail,
    AccountRead,
    AccountUpdate,
)

# Card master record (``app/cpy/CVACT02Y.cpy``) -- programs COCRDLIC/SLC/UPC.
from app.schemas.card import (
    CardBase,
    CardBeforeImage,
    CardRead,
    CardSummary,
    CardUpdate,
)

# Card cross-reference record (``app/cpy/CVACT03Y.cpy``) -- VSAM CARDXREF.
from app.schemas.card_xref import CardXrefRead

# Transaction record (``app/cpy/CVTRA05Y.cpy`` + daily ``CVTRA06Y``).
from app.schemas.transaction import (
    TransactionBase,
    TransactionCreate,
    TransactionRead,
    TransactionSummary,
)

# Transaction-category balance record (``app/cpy/CVTRA01Y.cpy``).
from app.schemas.tran_category_balance import TranCategoryBalanceRead

# Disclosure-group / interest-rate record (``app/cpy/CVTRA02Y.cpy``).
from app.schemas.disclosure_group import DisclosureGroupRead

# Transaction-type reference record (``app/cpy/CVTRA03Y.cpy``).
from app.schemas.transaction_type import TransactionTypeRead

# Transaction-category reference record (``app/cpy/CVTRA04Y.cpy``).
from app.schemas.transaction_category import TransactionCategoryRead

# Sign-on / session identity (``app/cbl/COSGN00C.cbl`` + COMMAREA COCOM01Y).
from app.schemas.auth import (
    CurrentUser,
    LoginRequest,
    LoginResponse,
    Token,
)

# Main / admin menu options (``app/cbl/COMEN01C.cbl`` + ``COADM01C.cbl``).
from app.schemas.menu import (
    MenuOption,
    MenuResponse,
)

# Bill payment (``app/cbl/COBIL00C.cbl``): available credit = limit - balance.
from app.schemas.billpay import (
    BillPayRequest,
    BillPayResponse,
)

# Transaction reporting (``app/cbl/CORPT00C.cbl``): monthly/yearly/custom.
from app.schemas.report import (
    ReportRequest,
    ReportResponse,
    ReportType,
    TransactionReportRow,
)

# ---------------------------------------------------------------------------
# Public API. ``__all__`` lists every re-exported name so that a star-import
# of this package (used by consumers, never by the sibling modules) and static
# analysers see an explicit, closed surface. Grouped by originating module in
# the same dependency order as the imports above.
# ---------------------------------------------------------------------------
__all__ = [
    # common
    "OrmBase",
    "RequestBase",
    "PaginationParams",
    "PaginatedResponse",
    "MessageResponse",
    "ErrorResponse",
    # user
    "UserBase",
    "UserCreate",
    "UserUpdate",
    "UserRead",
    "UserSummary",
    # customer
    "CustomerBase",
    "CustomerRead",
    "CustomerUpdate",
    "CustomerSummary",
    # account
    "AccountBase",
    "AccountRead",
    "AccountBeforeImage",
    "AccountUpdate",
    "AccountDetail",
    # card
    "CardBase",
    "CardBeforeImage",
    "CardRead",
    "CardSummary",
    "CardUpdate",
    # card_xref
    "CardXrefRead",
    # transaction
    "TransactionBase",
    "TransactionRead",
    "TransactionSummary",
    "TransactionCreate",
    # tran_category_balance
    "TranCategoryBalanceRead",
    # disclosure_group
    "DisclosureGroupRead",
    # transaction_type
    "TransactionTypeRead",
    # transaction_category
    "TransactionCategoryRead",
    # auth
    "LoginRequest",
    "Token",
    "LoginResponse",
    "CurrentUser",
    # menu
    "MenuOption",
    "MenuResponse",
    # billpay
    "BillPayRequest",
    "BillPayResponse",
    # report
    "ReportType",
    "ReportRequest",
    "TransactionReportRow",
    "ReportResponse",
]
