"""CardDemo FastAPI backend application package.

Root package of the modernized CardDemo backend -- a REST API over PostgreSQL
that replaces the legacy z/OS CardDemo online COBOL programs (``app/cbl/CO*C``)
and their VSAM record copybooks (``app/cpy``). The service is served as
``app.main:app``; every module addresses its siblings through absolute imports
rooted at this package -- for example the typed settings object
``app.core.config.settings`` or the ORM model ``app.models.account.Account``.

Layered architecture:
    api/v1 routers -> services -> repositories -> models/schemas,
with ``core`` (config, security, exceptions, dependencies), ``db`` (async
engine, session factory, declarative ``Base``), and ``utils`` as cross-cutting
concerns.

This module is intentionally a side-effect-free package marker. It imports no
submodules, opens no database connection, and constructs no FastAPI
application, so that importing any leaf module -- whether from the backend
service, the ``batch`` command-line jobs, or the Alembic migration
environment -- never triggers engine or application construction. Configuration
and secrets live in ``app.core.config`` (read from the environment), never
here.

See ``README.md`` and ``docs/`` for build, run, and API details.
"""

# Package version. Kept in sync with the ``project.version`` pin declared in
# ``backend/pyproject.toml``. This is a plain string literal on purpose -- the
# marker performs no file or environment access at import time.
__version__ = "1.0.0"
