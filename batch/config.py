# Typed, environment-driven configuration for the CardDemo batch CLI. No
# hardcoded secrets (Ochs Rule #3) -- the database URL comes from the
# environment. This module has no single legacy COBOL source; it is
# infrastructure that replaces the JCL DD/dataset wiring of the mainframe
# batch chain (AAP 0.5.5: "the connection string comes from the environment").
"""Environment-driven configuration for the CardDemo batch package.

This module defines :class:`BatchSettings`, a ``pydantic-settings`` model that
exposes the *single* configuration value the batch CLI and its loaders need to
reach PostgreSQL: the synchronous SQLAlchemy URL ``SYNC_DATABASE_URL`` (the
psycopg2 driver used by ``batch.db``, the Alembic migrations and the seed
loaders). It also exposes the module-level singleton :data:`batchSettings`::

    from batch.config import batchSettings

    engine = create_engine(batchSettings.SYNC_DATABASE_URL.get_secret_value())

Why a batch-owned settings model (QA finding #60):
    The batch CLI is launched from the repository root as ``python -m
    batch.cli`` (AAP 0.4.1). The backend's :class:`app.core.config.Settings`
    resolves its ``.env`` file *relative to the current working directory*, so
    from the repository root it never finds ``backend/.env``; worse, importing
    that model requires the backend-only ``SECRET_KEY``, which is irrelevant to
    batch data processing and would make every batch command fail fast on an
    unset secret. This module therefore:

        * declares ONLY ``SYNC_DATABASE_URL`` -- no ``SECRET_KEY`` and no other
          backend-only field is required to run a batch job;
        * anchors its ``.env`` file to the *absolute* ``backend/.env`` path so a
          root-launched CLI reads the same database URL the backend uses;
        * sets ``extra="ignore"`` so the backend's other variables (``SECRET_KEY``,
          ``DATABASE_URL`` ...) present in ``backend/.env`` are silently skipped
          rather than raising.

    It deliberately does NOT import :mod:`app.core.config`: doing so would
    instantiate the backend singleton and reintroduce the ``SECRET_KEY``
    requirement this split removes.

Import safety: constructing :class:`BatchSettings` reads environment variables
and an optional ``.env`` file only -- it never opens a database connection, so
importing this module (and therefore ``batch.db``) stays side-effect free apart
from configuration parsing.
"""

from functools import lru_cache
from pathlib import Path

from pydantic import SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule).
# ---------------------------------------------------------------------------

# Absolute path to the backend environment file. Anchored (not CWD-relative) so
# that ``python -m batch.cli`` launched from the repository root still reads the
# database URL from ``backend/.env`` (QA finding #60). ``batch/config.py`` lives
# at ``<repo>/batch/config.py``; ``.parent.parent`` is the repository root.
_BACKEND_ENV_FILE = Path(__file__).resolve().parent.parent / "backend" / ".env"

# Development-only default that matches ``backend/.env.example`` so a fresh
# checkout can run against the docker-compose ``postgres:17`` service without
# any additional configuration. Production deployments override it through the
# ``SYNC_DATABASE_URL`` environment variable (which takes precedence over the
# ``.env`` file). It contains only the throwaway local docker credential, never
# a real secret, so it does not violate the Ochs "no hardcoding" rule.
_DEFAULT_SYNC_DATABASE_URL = (
    "postgresql+psycopg2://carddemo:carddemo@localhost:5432/carddemo"
)

# Environments treated as unmistakable local development/test profiles, in which
# the ``_DEFAULT_SYNC_DATABASE_URL`` convenience default is permitted. Any OTHER
# value of ``ENVIRONMENT`` -- notably "staging"/"production" -- is treated as a
# real deployment that MUST supply its own ``SYNC_DATABASE_URL`` explicitly, so
# a shipped credential default can never silently reach a deployed batch run (QA
# finding M-25). Mirrors ``app.core.config.LOCAL_PROFILE_ENVIRONMENTS``.
LOCAL_PROFILE_ENVIRONMENTS = frozenset({"development", "test"})


class BatchSettings(BaseSettings):
    """Batch-only runtime configuration read from the environment.

    Attributes:
        SYNC_DATABASE_URL: SQLAlchemy *synchronous* engine URL (psycopg2 driver)
            used by ``batch.db``, the Alembic migrations and the seed loaders.
            Typed as :class:`~pydantic.SecretStr` so the embedded database
            credentials never render in cleartext through ``repr()`` / ``str()``
            / ``model_dump()`` (logs, tracebacks). Read the value with
            ``.get_secret_value()``.

    The attribute name is intentionally ``UPPER_SNAKE_CASE`` (which also reads as
    an ALL_UPPERCASE constant): it is the external environment-variable contract
    shared with ``backend/.env.example`` and ``docker-compose.yml``, not an
    ordinary local variable, so it is a documented exception to the Ochs
    camelCase-variable convention (the backend ``Settings`` model documents the
    same exception).
    """

    model_config = SettingsConfigDict(
        env_file=str(_BACKEND_ENV_FILE),
        env_file_encoding="utf-8",
        case_sensitive=False,
        # Ignore the backend-only variables (SECRET_KEY, DATABASE_URL, ...) that
        # also live in backend/.env; batch only consumes SYNC_DATABASE_URL.
        extra="ignore",
    )

    # Deployment environment name (``development``/``test``/``staging``/
    # ``production``). Read from the same ``ENVIRONMENT`` variable the backend
    # uses; defaults to the local ``development`` profile. It gates the
    # fail-closed database-URL guard below (QA finding M-25) and is otherwise
    # unused by batch processing. The attribute name is intentionally
    # ``UPPER_SNAKE_CASE`` (external env-var contract), the same documented Ochs
    # exception as ``SYNC_DATABASE_URL``.
    ENVIRONMENT: str = "development"

    SYNC_DATABASE_URL: SecretStr = SecretStr(_DEFAULT_SYNC_DATABASE_URL)

    @model_validator(mode="after")
    def RequireExplicitSyncDatabaseUrl(self) -> "BatchSettings":
        """Fail closed when a real deployment omits the batch database URL.

        The built-in ``_DEFAULT_SYNC_DATABASE_URL`` carries only throwaway local
        docker credentials and exists purely as a local-development/test
        convenience. Silently falling back to it in a real deployment would let a
        batch job connect to a wrong ``localhost`` database, so outside an
        unmistakable local profile (:data:`LOCAL_PROFILE_ENVIRONMENTS`) the URL
        MUST be supplied explicitly by the environment (QA finding M-25). The
        guard inspects ``model_fields_set`` so a value still at its built-in
        default (never explicitly set) is what trips it; no URL value is included
        in the error, so a traceback can never leak a credential.

        Returns:
            The validated settings instance, unchanged.

        Raises:
            ValueError: If ``ENVIRONMENT`` is not a local profile and
                ``SYNC_DATABASE_URL`` was left at its built-in default.
        """
        normalizedEnvironment = self.ENVIRONMENT.strip().lower()
        if normalizedEnvironment in LOCAL_PROFILE_ENVIRONMENTS:
            return self
        if "SYNC_DATABASE_URL" not in self.model_fields_set:
            raise ValueError(
                "SYNC_DATABASE_URL must be set explicitly when "
                f"ENVIRONMENT={self.ENVIRONMENT!r} (the built-in local default "
                "is not used outside a development/test profile)"
            )
        return self


@lru_cache(maxsize=1)
def GetBatchSettings() -> BatchSettings:
    """Return the process-wide :class:`BatchSettings` singleton.

    The result is cached so configuration is parsed exactly once per process.
    Tests that need to exercise a different environment can call
    ``GetBatchSettings.cache_clear()`` before re-invoking this function.

    Returns:
        The shared :class:`BatchSettings` instance built from the current
        environment and (if present) ``backend/.env``.
    """
    return BatchSettings()


# Module-level singleton imported directly by ``batch.db`` (and any other batch
# module needing the database URL), mirroring the backend's ``settings`` export.
batchSettings = GetBatchSettings()

__all__ = ["BatchSettings", "GetBatchSettings", "batchSettings"]
