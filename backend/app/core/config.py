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

import re
from functools import lru_cache
from typing import Annotated, Literal

from pydantic import (
    Field,
    SecretStr,
    ValidationError,
    field_validator,
    model_validator,
)
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

# ---------------------------------------------------------------------------
# Hardening constants (ALL_UPPERCASE per the Ochs Rule). These make the
# fail-fast configuration rules below explicit, single-sourced, and testable.
# ---------------------------------------------------------------------------

# Minimum acceptable length for SECRET_KEY. A blank or trivially short HS256
# signing key permits trivial token forgery, so the application must refuse to
# start with a weak key. 32 characters matches the recommended
# ``openssl rand -hex 32`` key (see backend/.env.example).
MINIMUM_SECRET_KEY_LENGTH = 32

# Lower/upper bounds for the bcrypt work factor. Below 4, passlib silently
# clamps (weakening the hash to cost 4); much above ~15 a single typo turns the
# authentication path into a multi-second hang (a denial of service). The
# accepted band keeps hashing both strong and responsive.
MINIMUM_BCRYPT_ROUNDS = 4
MAXIMUM_BCRYPT_ROUNDS = 15

# Anchored pattern for a well-formed CORS origin: scheme (http/https) + host
# (letters, digits, dots, hyphens) + optional ``:port``, and nothing else (no
# path, query, or trailing slash). Rejects bare values such as ``not-a-url``.
CORS_ORIGIN_PATTERN = re.compile(r"^https?://[A-Za-z0-9.\-]+(:\d+)?$")

# Lower bound for the login lockout threshold (QA finding M-01). At least one
# failed attempt must be permitted before a lockout could ever trigger, so a
# value below 1 would lock out the very first attempt (including a legitimate
# typo) and is rejected. The default (below) is well above this floor.
MINIMUM_LOGIN_MAX_ATTEMPTS = 1

# Lower bound for the lockout duration in seconds (QA finding M-01). A
# non-positive window would make a "lockout" expire instantly (no throttle at
# all), so the duration must be strictly positive.
MINIMUM_LOGIN_LOCKOUT_SECONDS = 1

# Environments treated as unmistakable local development/test profiles, in which
# the built-in ``DATABASE_URL`` / ``SYNC_DATABASE_URL`` convenience default
# (localhost throwaway credentials) is permitted. Any OTHER value of
# ``ENVIRONMENT`` -- notably "staging"/"production" -- is treated as a real
# deployment that MUST supply its own database URLs explicitly, so a shipped
# credential default can never silently reach a deployed system (QA finding
# M-25). Compared case-insensitively against the stripped ``ENVIRONMENT`` value.
LOCAL_PROFILE_ENVIRONMENTS = frozenset({"development", "test"})


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
        DEBUG: Whether debug behavior is enabled; defaults to ``False``
            (secure-by-default) and must be ``False`` in production.
        DATABASE_URL: SQLAlchemy async engine URL (asyncpg driver) used by the
            application runtime. Held as :class:`~pydantic.SecretStr` (embeds a
            password); read with ``.get_secret_value()``.
        SYNC_DATABASE_URL: SQLAlchemy sync engine URL (psycopg2 driver) used by
            Alembic migrations and the batch loaders. Held as
            :class:`~pydantic.SecretStr`.
        SECRET_KEY: Signing key for session cookies / JWTs, held as
            :class:`~pydantic.SecretStr`. Required with no default so a missing
            secret fails fast at startup; :meth:`ValidateSecretKey` additionally
            rejects a blank or shorter-than-:data:`MINIMUM_SECRET_KEY_LENGTH`
            key.
        AUTH_MODE: Authentication strategy, constrained to ``session`` (the
            confirmed baseline) or ``jwt`` (the supported alternative); any other
            value fails fast.
        ALGORITHM: JWT signing algorithm.
        ACCESS_TOKEN_EXPIRE_MINUTES: Session / token lifetime in minutes.
        SESSION_COOKIE_NAME: Name of the server-side session cookie.
        BCRYPT_ROUNDS: bcrypt work factor (cost) used when hashing passwords,
            bounded to ``[MINIMUM_BCRYPT_ROUNDS, MAXIMUM_BCRYPT_ROUNDS]`` so a
            typo can neither silently weaken hashing nor hang the auth path.
        BACKEND_CORS_ORIGINS: Allowed CORS origins. Accepts either a real list or
            a comma-separated string from the environment; each origin must be a
            well-formed ``scheme://host[:port]`` value. See
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
    # Secure by default: DEBUG is OFF unless explicitly opted into (e.g. via
    # backend/.env in development). A default of True would leak frame locals
    # (including this settings object) through debug tracebacks in production.
    DEBUG: bool = False

    # --- Database URLs (consumed by app/db/session.py, Alembic, loaders) ---
    # Typed as SecretStr so the embedded credentials never render in cleartext
    # through repr()/str()/model_dump() (logging, debug tracebacks, error
    # reporting). Consumers read the URL with ``.get_secret_value()``.
    #
    # The defaults below carry ONLY throwaway local docker credentials and exist
    # purely for the local-development/test profile so a fresh checkout runs
    # against the compose ``postgres:17`` service with no extra config. Outside
    # that profile they are NEVER used as a silent fallback: the
    # ``RequireExplicitDatabaseUrls`` model validator fails startup closed when a
    # non-local ``ENVIRONMENT`` leaves either URL at its built-in default (QA
    # finding M-25).
    DATABASE_URL: SecretStr = SecretStr(
        "postgresql+asyncpg://carddemo:carddemo@localhost:5432/carddemo"
    )
    SYNC_DATABASE_URL: SecretStr = SecretStr(
        "postgresql+psycopg2://carddemo:carddemo@localhost:5432/carddemo"
    )

    # --- Security / auth (consumed by app/core/security.py & dependencies.py) ---
    # SECRET_KEY intentionally has NO default (Ochs Rule #3): the one true secret
    # must be supplied by the environment, so the app fails fast when it is unset.
    # It is typed as SecretStr so it never renders in cleartext via
    # repr()/str()/model_dump(); ``ValidateSecretKey`` additionally rejects a
    # blank or trivially short key (an empty/weak HS256 key allows token forgery).
    SECRET_KEY: SecretStr
    # AUTH_MODE is constrained to the two authentication strategies the target
    # supports (AAP 0.8.4): the ``session`` baseline and the ``jwt`` alternative.
    # Any other value fails fast at startup naming AUTH_MODE.
    AUTH_MODE: Literal["session", "jwt"] = "session"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    SESSION_COOKIE_NAME: str = "carddemo_session"
    # bcrypt work factor, bounded to a safe band. Below the minimum passlib
    # silently clamps (weakening security); above the maximum a config typo makes
    # the hashing/authentication path hang for seconds (a denial of service). An
    # out-of-range value fails fast at startup naming BCRYPT_ROUNDS.
    BCRYPT_ROUNDS: int = Field(
        default=12,
        ge=MINIMUM_BCRYPT_ROUNDS,
        le=MAXIMUM_BCRYPT_ROUNDS,
    )
    # --- Login throttling (consumed by app/core/rate_limiter.py via the auth
    # router). QA finding M-01: the legacy sign-on and the initial port applied
    # no rate limit, so an attacker could try passwords without bound (twelve
    # bad logins produced only 401s). LOGIN_MAX_ATTEMPTS consecutive failures for
    # a given (user id, client IP) pair lock further attempts for
    # LOGIN_LOCKOUT_SECONDS, after which the window resets; a success clears the
    # counter immediately. Both are bounded so a misconfiguration cannot disable
    # the throttle (max attempts < 1) or make a lockout instantly expire
    # (duration < 1s). Values are environment-tunable and never hardcoded.
    LOGIN_MAX_ATTEMPTS: int = Field(
        default=5,
        ge=MINIMUM_LOGIN_MAX_ATTEMPTS,
    )
    LOGIN_LOCKOUT_SECONDS: int = Field(
        default=900,
        ge=MINIMUM_LOGIN_LOCKOUT_SECONDS,
    )

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
            The list of trimmed, non-empty, well-formed origin strings.

        Raises:
            ValueError: If ``rawValue`` is neither a string nor a list, or if any
                supplied origin is not a well-formed ``scheme://host[:port]``
                value. A specific exception is raised (never a bare ``except``)
                per the Ochs error-handling rule.
        """
        if isinstance(rawValue, str):
            originList = [origin.strip() for origin in rawValue.split(",") if origin.strip()]
        elif isinstance(rawValue, list):
            originList = [str(origin).strip() for origin in rawValue]
        else:
            raise ValueError(
                "BACKEND_CORS_ORIGINS must be a comma-separated string or a list of origins"
            )
        for origin in originList:
            if CORS_ORIGIN_PATTERN.fullmatch(origin) is None:
                raise ValueError(
                    f"BACKEND_CORS_ORIGINS contains an invalid origin: {origin!r} "
                    "(expected scheme://host[:port])"
                )
        return originList

    @field_validator("SECRET_KEY")
    @classmethod
    def ValidateSecretKey(cls, rawValue: SecretStr) -> SecretStr:
        """Reject a blank or trivially short signing key (fail fast on a weak key).

        ``SECRET_KEY`` already fails fast when it is entirely unset (pydantic
        reports it as a required field). This validator closes the remaining
        gap: a blank/whitespace value, or one shorter than
        :data:`MINIMUM_SECRET_KEY_LENGTH`, is rejected with a specific,
        non-secret error that names the offending key. An empty HS256 key would
        permit trivial session/JWT forgery, so the application must refuse to
        start with one. The check reads the underlying value via
        ``get_secret_value`` and never echoes it in the error message.

        Args:
            rawValue: The SecretStr wrapping the configured signing key.

        Returns:
            The validated :class:`~pydantic.SecretStr` unchanged.

        Raises:
            ValueError: If the key is blank/whitespace or shorter than
                :data:`MINIMUM_SECRET_KEY_LENGTH` characters.
        """
        secretValue = rawValue.get_secret_value()
        if len(secretValue.strip()) < MINIMUM_SECRET_KEY_LENGTH:
            raise ValueError(
                "SECRET_KEY must be a non-blank value of at least "
                f"{MINIMUM_SECRET_KEY_LENGTH} characters"
            )
        return rawValue

    @model_validator(mode="after")
    def RequireExplicitDatabaseUrls(self) -> "Settings":
        """Fail closed when a real deployment omits its database URLs.

        The built-in ``DATABASE_URL`` / ``SYNC_DATABASE_URL`` defaults carry only
        throwaway local docker credentials and exist purely as a
        local-development/test convenience. Silently falling back to them in a
        real deployment would let a production process connect to a wrong (or
        attacker-controlled) ``localhost`` database, so outside an unmistakable
        local profile (:data:`LOCAL_PROFILE_ENVIRONMENTS`) both URLs MUST be
        supplied explicitly by the environment (QA finding M-25). The guard
        inspects ``model_fields_set`` -- the fields the environment actually
        populated -- so a value still at its built-in default (never explicitly
        set) is what trips it. No URL value is included in the error, so a
        startup traceback can never leak a credential.

        Returns:
            The validated settings instance, unchanged.

        Raises:
            ValueError: If ``ENVIRONMENT`` is not a local profile and either
                database URL was left at its built-in default.
        """
        normalizedEnvironment = self.ENVIRONMENT.strip().lower()
        if normalizedEnvironment in LOCAL_PROFILE_ENVIRONMENTS:
            return self
        missingFields = [
            fieldName
            for fieldName in ("DATABASE_URL", "SYNC_DATABASE_URL")
            if fieldName not in self.model_fields_set
        ]
        if missingFields:
            raise ValueError(
                f"{', '.join(missingFields)} must be set explicitly when "
                f"ENVIRONMENT={self.ENVIRONMENT!r} (the built-in local default "
                "is not used outside a development/test profile)"
            )
        return self

    @model_validator(mode="after")
    def RequireSecureCorsOriginsInProduction(self) -> "Settings":
        """Reject cleartext ``http://`` CORS origins outside a local profile.

        Browsers send the session cookie / bearer token to any allowed CORS
        origin, so permitting a cleartext ``http://`` origin in a real
        deployment would expose those credentials to trivial network
        interception and stripping. Inside an unmistakable local profile
        (:data:`LOCAL_PROFILE_ENVIRONMENTS`) plain ``http://localhost`` origins
        remain allowed for developer convenience; everywhere else every
        configured origin MUST use ``https://`` (QA finding M11). The origins
        were already normalized and format-checked by
        :meth:`AssembleCorsOrigins`, so this guard only inspects their scheme.

        Returns:
            The validated settings instance, unchanged.

        Raises:
            ValueError: If ``ENVIRONMENT`` is not a local profile and any
                configured CORS origin uses the cleartext ``http://`` scheme.
        """
        normalizedEnvironment = self.ENVIRONMENT.strip().lower()
        if normalizedEnvironment in LOCAL_PROFILE_ENVIRONMENTS:
            return self
        insecureOrigins = [
            origin
            for origin in self.BACKEND_CORS_ORIGINS
            if not origin.lower().startswith("https://")
        ]
        if insecureOrigins:
            raise ValueError(
                "BACKEND_CORS_ORIGINS must use https:// when "
                f"ENVIRONMENT={self.ENVIRONMENT!r}; insecure origin(s): "
                f"{', '.join(insecureOrigins)}"
            )
        return self


def _BuildSettings() -> Settings:
    """Construct the settings singleton, sanitizing any validation failure.

    A malformed configuration must still fail fast at import, but the raised
    error must never echo a secret. pydantic's native
    :class:`~pydantic.ValidationError` embeds the raw input (which for a missing
    ``SECRET_KEY`` includes the whole env dict, and thus a fragment of the
    ``DATABASE_URL`` password) in its rendered ``input_value``. This helper
    catches that error and re-raises a :class:`ValueError` built solely from the
    offending field locations and messages — never the input values — so a
    startup traceback names the bad key(s) without leaking any secret. The
    original ValidationError context is suppressed (``from None``) so its
    input-bearing representation is not printed either.

    Returns:
        The validated :class:`Settings` singleton.

    Raises:
        ValueError: If configuration validation fails; the message names the
            offending field(s) and their error type with no secret values.
    """
    try:
        return Settings()
    except ValidationError as validationError:
        problemDescriptions = "; ".join(
            f"{'.'.join(str(locPart) for locPart in fieldError['loc'])}: {fieldError['msg']}"
            for fieldError in validationError.errors(include_url=False, include_input=False)
        )
        raise ValueError(f"Invalid backend configuration: {problemDescriptions}") from None


# Module-level singleton. Constructed at import time so configuration errors
# (for example, a missing or weak SECRET_KEY) surface immediately — as a
# sanitized, secret-free ValueError — rather than at first use. This is the
# primary export that every other backend module imports:
# ``from app.core.config import settings``.
settings = _BuildSettings()


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
