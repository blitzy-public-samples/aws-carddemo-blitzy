# CardDemo batch CLI package initializer.
#
# Package infrastructure for the greenfield ``batch/`` tree that reimplements the
# legacy CardDemo mainframe batch chain: the ``app/cbl`` COBOL ``CB*`` programs
# (CBACT01C-CBACT04C, CBCUS01C, CBTRN01C-CBTRN03C, CBSTM03A/CBSTM03B) plus the
# ``app/jcl`` IDCAMS load jobs. This module has no single legacy source program;
# it only marks ``batch/`` as an importable Python package (AAP 0.4.1, 0.5.2).
"""CardDemo batch CLI package.

The Python reimplementation of the legacy COBOL batch chain: the account, card,
cross-reference and customer print programs (``CBACT01C``-``CBACT04C``,
``CBCUS01C``), the daily-transaction reader, poster and detail report
(``CBTRN01C``-``CBTRN03C``) and the statement generator
(``CBSTM03A``/``CBSTM03B``), together with the IDCAMS seed-load jobs. Jobs are
invoked from the repository root as ``python -m batch.cli``.
"""

__version__ = "1.0.0"

import sys
from pathlib import Path

# Monorepo convenience shim: the batch package imports the backend ``app``
# package (for example ``batch.db`` -> ``app.core``). The preferred production
# setup is ``pip install -e ./backend -e ./batch``; this fallback additionally
# lets ``python -m batch.cli`` work from a fresh repository checkout.
#
# IMPORTANT (QA finding #59): the legacy COBOL tree at the repository root is a
# directory named ``app/`` that contains NO ``__init__.py``. Python therefore
# discovers it as an implicit *namespace* package, which means the previous
# guard ``importlib.util.find_spec("app") is None`` was never ``None`` and the
# backend fallback never ran -- so ``import app.core`` failed with
# ``ModuleNotFoundError: No module named 'app.core'``. Two rules make the shim
# deterministic:
#   1. Use a filesystem probe for the real backend package
#      (``backend/app/__init__.py``) rather than ``find_spec``. Calling
#      ``find_spec("app")`` would import and cache the legacy *namespace* ``app``
#      in ``sys.modules``, permanently defeating the fix.
#   2. Insert ``backend/`` at the front of ``sys.path``. ``backend/app`` is a
#      regular package (it has an ``__init__.py``), and a regular package always
#      resolves ahead of the legacy repository-root namespace portion.
# This module initializes before any ``import app`` executes, so ``sys.modules``
# holds no cached ``app`` yet and the path insertion takes effect.
_BACKEND_DIR = Path(__file__).resolve().parent.parent / "backend"
_BACKEND_APP_INIT = _BACKEND_DIR / "app" / "__init__.py"
if _BACKEND_APP_INIT.is_file():
    backendPath = str(_BACKEND_DIR)
    if backendPath not in sys.path:
        sys.path.insert(0, backendPath)
