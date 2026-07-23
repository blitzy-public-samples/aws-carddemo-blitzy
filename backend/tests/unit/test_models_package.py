# Unit tests for the app.models package aggregator (app/models/__init__.py).
# Traceability: reproduces the QA runtime finding MODELS-1 — before the
#   aggregator existed, importing a single model (the normal repository
#   consumption pattern) crashed with
#   ``sqlalchemy.exc.InvalidRequestError: ... expression 'Card' failed to locate
#   a name`` and ``Base.metadata`` held 0 tables. app/db/base.py's docstring
#   promises that app/models/__init__.py performs the collective import that
#   makes single-model imports work and populates Base.metadata with all 11
#   tables (AAP 0.5.1 record-layout ports; 0.7.4 metadata/index design). The
#   eleventh table, account_groups, is the referential-integrity registry
#   introduced per QA finding M-16 (parent of accounts.group_id).
#
# Subprocess isolation is MANDATORY: SQLAlchemy's mapper registry and
# ``Base.metadata`` are process-global, so a same-process test could be masked
# by another test that already imported the models. Each scenario therefore runs
# in a FRESH interpreter (no SECRET_KEY needed — the model modules import only
# app.db.base + SQLAlchemy and open no database connection).
#
# Ochs naming (AAP 0.8.2 / 0.8.3): snake_case test-function names and file name;
# camelCase local variables; ALL_UPPERCASE module-level constants; 4-space
# indentation; one asserted behavior per test.

import os
import subprocess
import sys
from pathlib import Path

# Backend package root (parents: unit -> tests -> backend) for PYTHONPATH.
BACKEND_ROOT = str(Path(__file__).resolve().parents[2])

# The number of ORM tables the schema defines: the 10 VSAM-derived models
# (AAP 0.4.1) plus the account_groups referential-integrity registry introduced
# per QA finding M-16 (parent of accounts.group_id) = 11.
EXPECTED_TABLE_COUNT = 11

# Timeout (seconds) guarding each probe against an unexpected hang.
PROBE_TIMEOUT_SECONDS = 30


def _RunModelProbe(probeCode, workingDir):
    """Run a model-package probe in a fresh interpreter.

    Args:
        probeCode: Python source executed with ``python -c``.
        workingDir: Process CWD (a scratch directory with no ``.env``).

    Returns:
        The completed :class:`subprocess.CompletedProcess` (output captured).
    """
    probeEnv = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "PYTHONPATH": BACKEND_ROOT,
        "ENVIRONMENT": "test",
    }
    completed = subprocess.run(
        [sys.executable, "-c", probeCode],
        cwd=str(workingDir),
        env=probeEnv,
        capture_output=True,
        text=True,
        timeout=PROBE_TIMEOUT_SECONDS,
    )
    return completed


def test_single_model_import_and_instantiate_succeeds(tmp_path):
    # The exact MODELS-1 reproduction: importing ONE model and instantiating it
    # must not raise (previously an InvalidRequestError on 'Card').
    probeCode = "from app.models.account import Account; Account(); print('INSTANTIATED_OK')"
    completed = _RunModelProbe(probeCode, tmp_path)
    assert completed.returncode == 0, completed.stderr
    assert "INSTANTIATED_OK" in completed.stdout


def test_metadata_registers_all_eleven_tables(tmp_path):
    probeCode = (
        "import app.models; "
        "from app.db.base import Base; "
        "print('TABLE_COUNT=' + str(len(Base.metadata.tables)))"
    )
    completed = _RunModelProbe(probeCode, tmp_path)
    assert completed.returncode == 0, completed.stderr
    assert f"TABLE_COUNT={EXPECTED_TABLE_COUNT}" in completed.stdout


def test_configure_mappers_succeeds(tmp_path):
    probeCode = (
        "import app.models; "
        "from sqlalchemy.orm import configure_mappers; "
        "configure_mappers(); "
        "print('MAPPERS_OK')"
    )
    completed = _RunModelProbe(probeCode, tmp_path)
    assert completed.returncode == 0, completed.stderr
    assert "MAPPERS_OK" in completed.stdout


def test_package_exports_all_eleven_model_classes(tmp_path):
    # The package MUST export all eleven ORM model classes, and every name listed
    # in ``__all__`` MUST be importable. The aggregator also re-exports a few
    # convenience names alongside the models -- the declarative ``Base`` and the
    # transaction staging-status constants (``STATUS_PENDING``/``STATUS_POSTED``)
    # consumed by the batch posting job and the services layer -- so this test
    # verifies the eleven model classes are a subset of ``__all__`` rather than
    # pinning the exact length of the export list. The eleventh, ``AccountGroup``,
    # is the M-16 referential-integrity registry (parent of accounts.group_id).
    probeCode = (
        "import app.models as M; "
        "modelClasses = ["
        "'Account', 'AccountGroup', 'Card', 'CardXref', 'Customer', "
        "'DisclosureGroup', 'TranCategoryBalance', 'Transaction', "
        "'TransactionCategory', 'TransactionType', 'User']; "
        "print('MODEL_COUNT=' + str(sum(name in M.__all__ for name in modelClasses))); "
        "print('ALL_IMPORTABLE=' + str(all(hasattr(M, name) for name in M.__all__)))"
    )
    completed = _RunModelProbe(probeCode, tmp_path)
    assert completed.returncode == 0, completed.stderr
    assert f"MODEL_COUNT={EXPECTED_TABLE_COUNT}" in completed.stdout
    assert "ALL_IMPORTABLE=True" in completed.stdout
