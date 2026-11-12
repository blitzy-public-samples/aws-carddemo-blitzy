"""CardDemo backend cross-cutting infrastructure package.

This package (``app.core``) groups the FastAPI backend's cross-cutting
infrastructure — the plumbing shared by every sibling package
(``app.db``, ``app.models``, ``app.schemas``, ``app.repositories``,
``app.services``, ``app.api``) and by the application factory
(``app.main``). Ported from the legacy COBOL/CICS/VSAM mainframe stack,
it holds:

    config          Typed, environment-driven settings (pydantic-settings),
                    satisfying the "no hardcoded secrets" rule.
    security        Password hashing plus session/JWT token handling that
                    replaces the legacy plaintext SEC-USR-PWD credential.
    exceptions      The domain exception hierarchy, including the ported
                    CBTRN02C posting validation codes (100-103 and 109).
    dependencies    FastAPI dependency-injection providers (get_db,
                    get_current_user, require_admin) that replace the
                    legacy COCOM01Y COMMAREA identity/role propagation.

Consumers reference the submodules directly (for example, ``app.core.config``
exposes the ``settings`` object). This initializer is intentionally minimal
and side-effect-free: it references none of the submodules above, so loading
``app.core`` never builds settings, opens a database connection, or triggers
an import cycle.
"""

__version__ = "1.0.0"
