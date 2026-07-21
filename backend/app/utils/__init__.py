"""Cross-cutting helper utilities for the CardDemo FastAPI backend.

Pure, dependency-light helpers shared across the service, repository, schema,
model, and core layers of the backend, the ``backend/tests`` suites, the
Alembic migration environment, and (cross-tree) the top-level ``batch``
package loaders and jobs. Every helper is imported directly from its own
submodule using an absolute path rooted at ``app`` (for example,
``app.utils.decimal_utils.DecodeZonedDecimal`` or
``app.utils.date_utils.ValidateDate``).

Package members:

* ``decimal_utils`` - signed zoned-decimal (DISPLAY) decode/encode and exact
  ``Decimal`` arithmetic (never floating point), including the interest
  truncation used by the batch interest calculation. Ported from copybooks
  ``CVACT01Y`` / ``CVTRA05Y`` and program ``CBACT04C``.
* ``date_utils`` - legacy date and timestamp validation, parsing, and
  formatting. Ported from ``CSUTLDTC`` (the Language Environment ``CEEDAYS``
  wrapper) and the ``CSUTLDPY`` / ``CSUTLDWY`` date-edit copybooks.
* ``validators`` - reusable field-level validation rules ported from the
  COBOL PROCEDURE DIVISION edits (``COACTUPC``) and the BMS symbolic maps.

This package initializer is intentionally side-effect-free: it imports none of
its submodules and touches no configuration, database, filesystem, network, or
logging state. Importing ``app.utils`` therefore never triggers heavy work and
never introduces an import cycle, which keeps ``app.utils.decimal_utils`` (and
the batch tree's cross-imports of the same helpers) cheap and safe to load.
"""

# Package version for the ``app.utils`` helper package. Declared as a plain
# ALL_UPPERCASE-style dunder string literal (Ochs Rule) and kept in step with
# the project version in ``backend/pyproject.toml``; no file or environment
# reads occur at import time.
__version__ = "1.0.0"
