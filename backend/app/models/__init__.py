# Aggregator for the CardDemo SQLAlchemy ORM layer. Importing this package
# imports every model module exactly once, which (a) registers all 10 tables
# into ``app.db.base.Base.metadata`` (consumed by Alembic autogenerate and the
# golden-master parity tests) and (b) makes the string-based ``relationship(...)``
# forward references (e.g. "Card", "CardXref") resolvable, so importing any
# single model and instantiating it — the normal repository consumption
# pattern — works without the caller having to import every sibling by hand.
# Ports the VSAM record layouts in app/cpy/*.cpy (AAP 0.5.1). See app/db/base.py,
# whose docstring documents that this module performs the collective import.
"""Package aggregator that registers every CardDemo ORM model.

This ``__init__`` is intentionally the one place that imports all ten model
modules together. SQLAlchemy resolves the string-based relationship targets
(``relationship("Card")``, ``relationship("CardXref")``, ...) from its class
registry, and that registry is only complete once **every** mapped class has
been imported. By importing all models here, the package guarantees that:

* ``from app.models.account import Account; Account()`` succeeds — importing any
  submodule first imports this package, so all sibling classes are already
  registered when the mapper is configured (this is the exact pattern the
  repository layer uses).
* ``from app.db.base import Base; Base.metadata`` holds all ten tables, which
  Alembic autogenerate and the parity tests rely on.
* ``sqlalchemy.orm.configure_mappers()`` completes with no unresolved
  forward-reference errors.

The models map 1:1 to the legacy VSAM record copybooks (AAP 0.5.1):

* :class:`~app.models.account.Account` — ``CVACT01Y`` (VSAM ``ACCTDATA``)
* :class:`~app.models.card.Card` — ``CVACT02Y`` (VSAM ``CARDDATA``)
* :class:`~app.models.card_xref.CardXref` — ``CVACT03Y`` (VSAM ``CARDXREF``)
* :class:`~app.models.customer.Customer` — ``CVCUS01Y`` (VSAM ``CUSTDATA``)
* :class:`~app.models.disclosure_group.DisclosureGroup` — ``CVTRA02Y``
* :class:`~app.models.tran_category_balance.TranCategoryBalance` — ``CVTRA01Y``
* :class:`~app.models.transaction.Transaction` — ``CVTRA05Y`` (VSAM ``TRANSACT``)
* :class:`~app.models.transaction_category.TransactionCategory` — ``CVTRA04Y``
* :class:`~app.models.transaction_type.TransactionType` — ``CVTRA03Y``
* :class:`~app.models.user.User` — ``CSUSR01Y`` (VSAM ``USRSEC``)

Importing this package has no side effects beyond class registration: the model
modules import only :mod:`app.db.base` and the standard library / SQLAlchemy, and
they open no database connection (that is owned by ``app.db.session``).
"""

from app.models.account import Account
from app.models.card import Card
from app.models.card_xref import CardXref
from app.models.customer import Customer
from app.models.disclosure_group import DisclosureGroup
from app.models.tran_category_balance import TranCategoryBalance
from app.models.transaction import Transaction
from app.models.transaction_category import TransactionCategory
from app.models.transaction_type import TransactionType
from app.models.user import User

# Public API: the ten ORM model classes, exported for ``from app.models import X``
# and for tooling that enumerates the mapped entities.
__all__ = [
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
]
