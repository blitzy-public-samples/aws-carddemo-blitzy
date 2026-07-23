# Enforceable parent/reference for ACCT-GROUP-ID (CVACT01Y) introduced per QA
# finding M-16 so that ``accounts.group_id`` can be a real FOREIGN KEY, as the
# AAP requires (section 0.5.1 "accounts ... group_id FK"; section 0.8.1
# "Preserve referential integrity").
"""SQLAlchemy 2.0 ORM model for the CardDemo *account group* registry table.

Background (why this table exists)
----------------------------------
The legacy account record ``CVACT01Y`` carries ``ACCT-GROUP-ID PIC X(10)``
(ported to :attr:`app.models.account.Account.group_id`). The AAP mandates that
this column be a **foreign key** (AAP 0.5.1 account row: "group_id FK"; AAP
0.8.1: "Preserve referential integrity"). The only other group-bearing table,
:class:`app.models.disclosure_group.DisclosureGroup`, uses a **composite**
primary key ``(group_id, tran_type_cd, tran_cat_cd)`` -- so ``group_id`` alone
is not a valid FK target there.

QA finding M-16 therefore directs: "Introduce an enforceable parent/reference
and FK." This table is that enforceable parent: a small registry whose sole
primary key is ``group_id``, giving ``accounts.group_id`` a single-column target
to reference. It is populated (Alembic migration ``0004`` and the test fixtures)
with the distinct set of account-group identifiers the dataset uses -- exactly
``SELECT DISTINCT group_id FROM disclosure_group`` (``A000000000``, ``DEFAULT``,
``ZEROAPR`` in the golden-master seed).

Legacy fidelity note
---------------------
The mainframe had no standalone "account groups" dataset; the group id was a
free-text field on the account and a key prefix on ``DISCGRP``. This registry is
the minimal modern construct required to make the AAP-mandated FK enforceable;
it does not alter any business rule. An account whose ``group_id`` has no
``disclosure_group`` rate rows still follows the legacy CBACT04C fallback to the
``DEFAULT`` disclosure group -- that fallback concerns missing *rate* rows, not
membership in this registry, so a group id may legitimately exist here while
having no disclosure rows (see ``batch/jobs/interest_calc.py``).
"""

from typing import List

from sqlalchemy import String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base


class AccountGroup(Base):
    """Registry of valid account-group identifiers (parent of ``accounts``).

    A single-column reference table whose primary key ``group_id`` is the FK
    target for :attr:`app.models.account.Account.group_id`. The class name uses
    the Ochs PascalCase rule; the column attribute is snake_case to match the
    PostgreSQL schema and the sibling ``accounts.group_id`` / ``disclosure_group``
    columns.
    """

    __tablename__ = "account_groups"

    # ACCT-GROUP-ID PIC X(10) -> VARCHAR(10). Sole primary key so that it can be
    # the single-column target of the ``accounts.group_id`` foreign key.
    group_id: Mapped[str] = mapped_column(String(10), primary_key=True)

    # --- Relationships (parent side; the FK lives on the child ``accounts``). --
    # Reciprocal of Account.group (FK accounts.group_id -> account_groups.group_id).
    # The target class is referenced as a string so this module adds no import of
    # Account; SQLAlchemy resolves "Account" from its registry once
    # app/models/__init__.py imports every model and configure_mappers() runs.
    accounts: Mapped[List["Account"]] = relationship(  # noqa: F821
        back_populates="group"
    )

    def __repr__(self) -> str:
        """Return a concise, unambiguous representation for logs and tests."""
        return f"AccountGroup(group_id={self.group_id!r})"
