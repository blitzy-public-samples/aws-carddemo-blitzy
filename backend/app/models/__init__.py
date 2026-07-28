"""SQLAlchemy ORM models for CardDemo (ported 1:1 from app/cpy record copybooks).

Importing this package registers exactly the ten VSAM-derived tables on
``Base.metadata`` -- the exact 10-table data model the AAP mandates (AAP 0.5.1).

This package initializer is the single aggregation point for the CardDemo ORM
layer. It intentionally imports every one of the ten model modules under
``app.models`` so that, purely as a *side effect* of importing ``app.models``,
each mapped class is registered on the shared declarative registry and its table
is attached to ``Base.metadata``.

That side effect is load-bearing. It is relied upon by:

* ``backend/alembic/env.py`` -- which sets ``target_metadata = Base.metadata``
  for autogenerate. If a model were not imported here, its table would be
  silently missing from every generated migration.
* ``app.repositories``, ``app.services``, ``app.core.dependencies`` and the
  ``batch`` package -- which import the model classes by name (for example
  ``from app.models import Account``).

Unlike the sibling package markers (``app``, ``app.db``, ``app.core``), which
are deliberately side-effect-free, this initializer MUST perform these imports;
they are therefore NOT dead code. The ``__all__`` list at the bottom both
documents the package's public surface and marks the re-exported names as used,
so linters do not flag the imports whose sole purpose is mapper registration.

All ten models port the legacy VSAM record copybooks (``app/cpy/*.cpy``) 1:1 and
extend the shared declarative :class:`app.db.base.Base`. Monetary and rate
fields are exact ``NUMERIC`` / :class:`decimal.Decimal` columns -- never floating
point (AAP section 0.7.1). Two ORM-only synonyms (``card_xref.card_num`` ->
``xref_card_num`` and ``disclosure_group.acct_group_id`` -> ``group_id``) expose
copybook/DTO field names without emitting phantom DDL columns.

``accounts.group_id`` (``ACCT-GROUP-ID PIC X(10)`` from ``CVACT01Y``) is a plain
indexed ``VARCHAR(10)`` column -- a faithful port of the legacy free-text group
id, which the mainframe carried as a field on the account record (there was no
standalone "account groups" dataset). It is NOT a foreign key: the only
group-bearing table, ``disclosure_group``, uses a COMPOSITE primary key, so
``group_id`` alone has no single-column FK target, and the AAP fixes the model at
exactly ten tables (AAP 0.5.1). See ``backend/app/models/account.py``.

Model module -> mapped class -> table:
    account                 -> Account              -> accounts
    card                    -> Card                 -> cards
    card_xref               -> CardXref             -> card_xref
    customer                -> Customer             -> customers
    disclosure_group        -> DisclosureGroup      -> disclosure_group
    tran_category_balance   -> TranCategoryBalance  -> tran_category_balance
    transaction             -> Transaction          -> transactions
    transaction_category    -> TransactionCategory  -> transaction_category
    transaction_type        -> TransactionType      -> transaction_type
    user                    -> User                 -> users
"""

# Re-export the declarative Base for convenience so callers may write
# ``from app.models import Base``. The canonical definition lives in
# ``app.db.base``; this is only a convenience alias.
from app.db.base import Base

# The following model imports are REQUIRED for their side effect: importing each
# module registers its mapped class on ``Base.metadata``. They are intentionally
# retained even though a linter may see them as "unused" -- do NOT remove them
# (see the module docstring). One module per table, ordered alphabetically by
# module path. The ``transaction`` module additionally exports the two
# staging-status constants used by the batch posting job and the services layer.
from app.models.account import Account
from app.models.card import Card
from app.models.card_xref import CardXref
from app.models.customer import Customer
from app.models.disclosure_group import DisclosureGroup
from app.models.tran_category_balance import TranCategoryBalance
from app.models.transaction import (
    STATUS_PENDING,
    STATUS_POSTED,
    STATUS_REJECTED,
    Transaction,
)
from app.models.transaction_category import TransactionCategory
from app.models.transaction_type import TransactionType
from app.models.user import User

# Public surface of the package. Enumerating the re-exported names here documents
# the package API and marks the "unused" model imports above as used -- their
# real job is mapper/table registration on ``Base.metadata``. Ordered as: the
# declarative Base, the ten ORM model classes, then the transaction
# staging-status constants.
__all__ = [
    "Base",
    "Account",
    "Card",
    "CardXref",
    "Customer",
    "DisclosureGroup",
    "TranCategoryBalance",
    "Transaction",
    "TransactionCategory",
    "TransactionType",
    "User",
    "STATUS_PENDING",
    "STATUS_POSTED",
    "STATUS_REJECTED",
]
