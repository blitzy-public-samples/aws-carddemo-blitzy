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

import importlib.util
import sys
from pathlib import Path

# Monorepo convenience shim: the batch package imports the backend `app`
# package. Preferred setup is `pip install -e backend`; this fallback lets
# `python -m batch.cli` work from a fresh checkout by adding the sibling
# `backend/` directory to sys.path when `app` is not already importable.
if importlib.util.find_spec("app") is None:
    backendDir = Path(__file__).resolve().parent.parent / "backend"
    if backendDir.is_dir():
        sys.path.insert(0, str(backendDir))
