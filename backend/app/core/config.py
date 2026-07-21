# Cross-cutting typed configuration. No hardcoded secrets (Ochs Rule #3) — all
# values come from the environment. Env-var contract: backend/.env.example.
"""Typed, environment-driven configuration for the CardDemo FastAPI backend.

This module is the single source of truth for backend runtime configuration. It
defines :class:`Settings`, a ``pydantic-settings`` model whose fields are read
from the process environment (optionally seeded from a local ``.env`` file in
development), and it exposes the module-level singleton :data:`settings` that
every other layer imports instead of reading environment variables directly::

    from app.core.config import settings

    engine = create_async_engine(settings.DATABASE_URL)

Design notes:
    * There is no legacy COBOL source for this module — it is infrastructure that
      replaces the CICS region / JCL configuration of the mainframe original.
    * Ochs Rule #3 ("no hardcoding of sensitive information") is enforced by
      giving the lone secret, ``SECRET_KEY``, no default: the application fails
      fast at startup if it is not supplied by the environment.
    * The environment-variable NAMES declared below are the external
      configuration contract shared with ``backend/.env.example`` and the root
      ``docker-compose.yml``. They are kept as ``UPPER_SNAKE_CASE`` identifiers
      (which also read as ALL_UPPERCASE constants) — an intentional, documented
      exception to the Ochs camelCase-variable convention because they form an
      external contract, not ordinary local variables.
"""

from functools import lru_cache
from typing import Annotated

from pydantic import field_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


class Settings(BaseSettings):
    """Strongly-typed application settings loaded from the environment.

    Each attribute maps case-insensitively to an environment variable of the
    same name (for example, the ``SECRET_KEY`` attribute is populated from the
    ``SECRET_KEY`` environment variable). During development the values may be
    seeded from ``backend/.env``; in containers the real process environment
    overrides that file. Unrelated environment variables are ignored
    (``extra="ignore"``) so the process never crashes on unknown keys.

    Attributes:
        PROJECT_NAME: Human-readable application name used as the FastAPI /
            OpenAPI title.
        API_V1_PREFIX: URL prefix under which the version-1 routers are mounted.
        ENVIRONMENT: Deployment environment name (``development``, ``staging`` or
            ``production``).
        DEBUG: Whether debug behavior is enabled; must be ``False`` in
            production.
        DATABASE_URL: SQLAlchemy async engine URL (asyncpg driver) used by the
            application runtime.
        SYNC_DATABASE_URL: SQLAlchemy sync engine URL (psycopg2 driver) used by
            Alembic migrations and the batch loaders.
        SECRET_KEY: Signing key for session cookies / JWTs. Required with no
            default so a missing secret fails fast at startup.
        AUTH_MODE: Authentication strategy; ``session`` is the confirmed baseline
            and ``jwt`` is the supported alternative.
        ALGORITHM: JWT signing algorithm.
        ACCESS_TOKEN_EXPIRE_MINUTES: Session / token lifetime in minutes.
        SESSION_COOKIE_NAME: Name of the server-side session cookie.
        BCRYPT_ROUNDS: bcrypt work factor (cost) used when hashing passwords.
        BACKEND_CORS_ORIGINS: Allowed CORS origins. Accepts either a real list or
            a comma-separated string from the environment; see
            :meth:`AssembleCorsOrigins`.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # --- Application metadata (consumed by app/main.py) ---
    PROJECT_NAME: str = "CardDemo"
    API_V1_PREFIX: str = "/api/v1"
    ENVIRONMENT: str = "development"
    DEBUG: bool = True

    # --- Database URLs (consumed by app/db/session.py, Alembic, loaders) ---
    DATABASE_URL: str = "postgresql+asyncpg://carddemo:carddemo@localhost:5432/carddemo"
    SYNC_DATABASE_URL: str = "postgresql+psycopg2://carddemo:carddemo@localhost:5432/carddemo"

    # --- Security / auth (consumed by app/core/security.py & dependencies.py) ---
    # SECRET_KEY intentionally has NO default (Ochs Rule #3): the one true secret
    # must be supplied by the environment, so the app fails fast when it is unset.
    SECRET_KEY: str
    AUTH_MODE: str = "session"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    SESSION_COOKIE_NAME: str = "carddemo_session"
    BCRYPT_ROUNDS: int = 12

    # --- CORS (consumed by app/main.py -> CORSMiddleware) ---
    # ``NoDecode`` disables pydantic-settings' default JSON decoding of complex
    # (list) fields, so a plain comma-separated environment string reaches the
    # ``mode="before"`` validator below instead of raising a JSON parse error.
    BACKEND_CORS_ORIGINS: Annotated[list[str], NoDecode] = ["http://localhost:3000"]

    @field_validator("BACKEND_CORS_ORIGINS", mode="before")
    @classmethod
    def AssembleCorsOrigins(cls, rawValue: object) -> list[str]:
        """Normalize the CORS-origins value into a list of origin strings.

        The environment supplies CORS origins as a comma-separated string (for
        example ``http://localhost:3000`` or ``http://a,http://b``), whereas
        tests and programmatic construction may pass an already-parsed list.
        Both forms are accepted and normalized here before ``main.py`` hands the
        result to Starlette's ``CORSMiddleware(allow_origins=...)``.

        Args:
            rawValue: The raw value taken from the environment or the
                constructor — either a comma-separated ``str`` or a ``list``.

        Returns:
            The list of trimmed, non-empty origin strings.

        Raises:
            ValueError: If ``rawValue`` is neither a string nor a list. A
                specific exception is raised (never a bare ``except``) per the
                Ochs error-handling rule.
        """
        if isinstance(rawValue, str):
            return [origin.strip() for origin in rawValue.split(",") if origin.strip()]
        if isinstance(rawValue, list):
            return rawValue
        raise ValueError("BACKEND_CORS_ORIGINS must be a comma-separated string or a list of origins")


# Module-level singleton. Constructed at import time so configuration errors
# (for example, a missing SECRET_KEY) surface immediately as a pydantic
# ValidationError rather than at first use. This is the primary export that every
# other backend module imports: ``from app.core.config import settings``.
settings = Settings()


@lru_cache
def GetSettings() -> Settings:
    """Return the process-wide cached settings singleton.

    Provided as a FastAPI dependency-injection entry point
    (``Depends(GetSettings)``) and as a convenience for tests. It returns the
    same :data:`settings` instance created at import time, guaranteeing that the
    environment is parsed exactly once per process.

    Returns:
        The shared :class:`Settings` instance.
    """
    return settings
