"""Data-access (repository) layer for the CardDemo backend.

One repository per former VSAM file. Each repository encapsulates all
SQLAlchemy 2.0 async access (READ/STARTBR/READNEXT/REWRITE -> select/insert/
update/delete) so the datastore can change without touching the service layer
(AAP 0.4.3 repository pattern). Business logic lives in app.services, not here.
"""

# Ergonomic, side-effect-free re-exports of the ten repository classes so the
# service layer can write ``from app.repositories import AccountRepository``
# instead of deep module paths. Importing a repository CLASS only imports its
# ORM model (which registers on Base.metadata); it never instantiates a
# repository, opens a database connection, or creates a session/engine. This
# marker therefore performs no side effects and deliberately avoids importing
# from app.services / app.api / app.main to keep the import graph acyclic (the
# same side-effect-free discipline as app/db/__init__.py). The ``__all__`` list
# below both documents the package's public surface and marks each re-exported
# name as used, so linters do not flag the imports as unused (F401).
from app.repositories.account_repo import AccountRepository
from app.repositories.card_repo import CardRepository
from app.repositories.customer_repo import CustomerRepository
from app.repositories.discgrp_repo import DisclosureGroupRepository
from app.repositories.tcatbal_repo import TranCategoryBalanceRepository
from app.repositories.trancat_repo import TransactionCategoryRepository
from app.repositories.transaction_repo import TransactionRepository
from app.repositories.trantype_repo import TransactionTypeRepository
from app.repositories.user_repo import UserRepository
from app.repositories.xref_repo import CardXrefRepository

__all__ = [
    "AccountRepository",
    "CardRepository",
    "CardXrefRepository",
    "CustomerRepository",
    "DisclosureGroupRepository",
    "TranCategoryBalanceRepository",
    "TransactionCategoryRepository",
    "TransactionRepository",
    "TransactionTypeRepository",
    "UserRepository",
]
