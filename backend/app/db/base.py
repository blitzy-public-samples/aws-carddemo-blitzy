# SQLAlchemy 2.0 declarative Base. Root MetaData/registry for all CardDemo ORM
# models (app/models/*), which port the VSAM record layouts in app/cpy/*.cpy.
# Base.metadata replaces the VSAM catalog (app/catlg/LISTCAT.txt) and is consumed
# by backend/alembic/env.py as target_metadata for autogenerate.
"""Declarative base and shared MetaData for the CardDemo ORM layer.

This module defines the single SQLAlchemy 2.0 ``DeclarativeBase`` subclass,
:class:`Base`, that every CardDemo ORM model extends. It is deliberately the
*root* of the model dependency graph: models import ``Base`` from here, and
``Base.metadata`` is imported by ``backend/alembic/env.py`` as the
``target_metadata`` used for autogenerate.

To keep that dependency direction acyclic (models -> base, never the reverse),
this module imports ONLY from :mod:`sqlalchemy`. It must never import anything
under the ``app`` package, and it must never define concrete tables, columns, or
mixins -- the individual model modules in ``app.models`` own their own columns.
``Base.metadata`` is populated lazily, purely as a side effect of importing the
model modules (done by ``app/models/__init__.py`` and by the Alembic env), so no
model registration is performed here.

The shared :data:`NAMING_CONVENTION` gives every index and constraint a
deterministic, portable name. This makes Alembic autogenerate emit stable
identifiers for primary keys, foreign keys, unique constraints, indexes, and
check constraints, so the ``0001_initial_schema`` migration -- and its
downgrade -- are reproducible. Deterministic names directly support the
VSAM-to-PostgreSQL mapping (primary KSDS key -> PRIMARY KEY, alternate index ->
UNIQUE / secondary INDEX, cross-reference relationship -> FOREIGN KEY) and the
golden-master parity requirement (AAP 0.7.4).

Example:
    Models are declared in ``app.models`` (never here) and look like::

        from app.db.base import Base
        from sqlalchemy.orm import Mapped, mapped_column

        class Account(Base):
            __tablename__ = "accounts"

            acct_id: Mapped[str] = mapped_column(primary_key=True)

    Once the model modules are imported (via ``app/models/__init__.py`` or the
    Alembic environment), ``Base.metadata.tables`` holds every mapped table.
"""

from sqlalchemy import MetaData
from sqlalchemy.orm import DeclarativeBase

# Shared constraint / index naming convention for the entire schema. The
# ``%(...)s`` entries are SQLAlchemy naming-convention tokens (NOT Python string
# formatting) and MUST be preserved verbatim. Keys map to constraint kinds:
#   ix -> index, uq -> unique, ck -> check, fk -> foreign key, pk -> primary key.
# These are the Alembic-standard tokens; defining them once here (rather than
# inline on individual constraints) ensures every model and every migration
# share one authoritative, deterministic definition.
NAMING_CONVENTION: dict[str, str] = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    """Declarative base class for every CardDemo ORM model.

    All models in :mod:`app.models` extend this class. The class-level
    :attr:`metadata` binds :data:`NAMING_CONVENTION`, so ``Base.metadata`` is the
    single, shared registry that Alembic (``backend/alembic/env.py``) consumes as
    its autogenerate ``target_metadata``.

    This class is intentionally free of model imports and of concrete table or
    column definitions, keeping the dependency graph acyclic (models depend on
    ``Base``, never the reverse). Each table is declared by its own model module
    and is registered into the shared :attr:`metadata` when that module imports.
    """

    # Shared MetaData carrying the deterministic naming convention. Every table
    # mapped by a subclass is registered into this single MetaData instance.
    metadata = MetaData(naming_convention=NAMING_CONVENTION)


__all__ = ["Base", "NAMING_CONVENTION"]
