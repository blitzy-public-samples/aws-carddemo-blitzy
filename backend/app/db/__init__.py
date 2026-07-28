"""CardDemo backend database-foundation package (``app.db``).

This package groups the SQLAlchemy 2.0 database foundation for the modernized
CardDemo FastAPI backend (AAP §0.4.1, §0.5.1):

* ``app.db.base`` — the declarative ``Base`` that every ORM model in
  ``app.models`` extends and that ``backend/alembic/env.py`` targets for
  migration autogeneration.
* ``app.db.session`` — the async engine and ``async_sessionmaker``
  (``AsyncSessionLocal``) consumed by ``app.main`` and
  ``app.core.dependencies`` (``get_db``).

It replaces the legacy VSAM KSDS connection / DD-name access layer — whose
cluster and alternate-index key positions are recorded in
``app/catlg/LISTCAT.txt`` — with a single PostgreSQL 17 connection sourced
entirely from the environment (``app.core.config.settings.DATABASE_URL``); no
connection string or secret is ever hardcoded here (Ochs Rule #3). The record
layouts in ``app/cpy/*.cpy`` are represented by the ORM models built on
``Base``.

Design note — intentionally side-effect-free:
    This package initializer deliberately performs **no** imports and declares
    **no** re-exports. Importing ``app.db`` must never construct the async
    engine or open a database connection, because lightweight contexts —
    Alembic migrations, the ``batch`` CLI, and unit tests that never touch the
    database — import subpackages of ``app`` without wanting a live engine.
    Consumers therefore import the concrete objects from their own modules
    (``from app.db.base import Base``; ``from app.db.session import engine,
    AsyncSessionLocal``) rather than through this marker. Keeping the
    initializer empty also avoids the import cycles a re-export chain would
    create (``session`` -> ``app.core.config``; ``app.core.dependencies`` ->
    ``app.db.session`` and ``app.models.user`` -> ``app.db.base``).
"""

# Package version for the database-foundation package. A plain string literal
# only: no filesystem, environment, or submodule access occurs at import time.
__version__ = "1.0.0"
