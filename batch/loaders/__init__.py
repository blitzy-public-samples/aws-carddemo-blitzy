"""Seed/load modules for the CardDemo PostgreSQL database.

This subpackage groups the one-time, idempotent data loaders that migrate the
legacy CardDemo seed datasets into the modern PostgreSQL schema. There is one
loader module per legacy IDCAMS ``DELETE``/``DEFINE``/``REPRO`` load job from
the mainframe file-refresh chain (``ACCTFILE``, ``CARDFILE``, ``CUSTFILE``,
``XREFFILE``, ``TRANFILE``, ``DISCGRP``, ``TRANCATG``, ``TRANTYPE`` and
``TCATBALF``), plus the user-security seed job (``DUSRSECJ``).

Loader contract
    Each data loader exposes a single entry-point callable, named after the
    thing it loads, that returns the count of rows processed::

        Load<Thing>(session, dataDir=None) -> int

    Concrete entry points are ``load_accounts.LoadAccounts``,
    ``load_cards.LoadCards``, ``load_customers.LoadCustomers``,
    ``load_xref.LoadXref``, ``load_transactions.LoadTransactions``,
    ``load_disclosure_groups.LoadDisclosureGroups``,
    ``load_tran_categories.LoadTranCategories``,
    ``load_tran_types.LoadTranTypes`` and ``load_tcatbal.LoadTcatbal``.

    The user-security loader is the sole exception. Its entry point takes no
    data directory because the records are regenerated rather than copied::

        init_users.InitializeUsers(session) -> int

Transaction ownership
    The caller owns the database transaction. Loaders never ``commit`` or
    ``rollback`` on their own; they operate against the ``session`` handed to
    them so the orchestrator (``batch.orchestration.batch_chain``) or the CLI
    (``batch.cli``) can compose several loaders into one atomic unit of work.

Seed sources
    The primary seed source is ``app/data/ASCII/*.txt``, which is
    display-readable and therefore needs no EBCDIC decode for the bulk of the
    data. ``USRSEC`` is the one exception: it ships only as an EBCDIC dataset,
    so ``init_users`` decodes that dataset (or regenerates the known seed
    users) while hashing every password instead of storing the legacy
    plaintext value.

Import policy
    This module is a deliberately minimal, side-effect-free package marker. It
    imports no loader submodule, ORM model, or database session, and performs
    no I/O at import time, which avoids import-time coupling and circular
    imports with ``app.*`` and ``batch.db``. Callers import the concrete
    loaders explicitly, e.g. ``from batch.loaders.load_accounts import
    LoadAccounts``. ``__all__`` is intentionally empty so that
    ``from batch.loaders import *`` re-exports nothing.
"""

__all__: list[str] = []
__version__ = "1.0.0"
