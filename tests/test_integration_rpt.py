"""Integration scenarios of area RPT: the report and statement jobs.

Level ``integration``, area ``RPT``: one test per scenario file
``tests/scenarios/integration/RPT-*.yaml``. The scenarios run the jobs
``TRANREPT``, ``TRNRPT00`` (the job ``CORPT00C`` writes to the ``JOBS`` queue,
``EXEC PROC=TRANREPT``), ``CREASTMT`` and ``PRTCATBL``, and through them the
procedures ``TRANREPT`` and ``REPROC`` and the control card ``REPROCT``. The
scenarios and their baselines carry every expectation; this module only runs
them.

Scenarios the ``gnucobol-batch`` job driver and ``unikix`` both run, with
baselines observed on ``gnucobol-batch``:

- ``RPT-TRANREPT-normal-01``
- ``RPT-TRNRPT00-normal-01``
- ``RPT-CREASTMT-normal-01``, ``blocked`` under GAP-MIS-002 when page zero
  cannot be mapped for ``CBSTM03A``
- ``RPT-PRTCATBL-normal-01``

Scenarios only ``unikix`` runs, because they verify JCL semantics the job
driver never evaluates; their baselines are derived:

- ``RPT-CREASTMT-recovery-01``: ``COND=(0,NE)`` bypasses the steps after a
  failed sort
- ``RPT-TRNRPT00-alternate-01``: resolution of ``EXEC PROC=TRANREPT`` against
  a member that begins ``//REPROC PROC`` (GAP-DEF-008)

``conftest.py`` deselects the scenarios without the selected adapter's
marker. Each run's outcome is recorded, then maps onto pytest: ``passed``
passes; ``failing`` raises ``AssertionError`` carrying the diff; ``blocked``
and ``implemented-unexecuted`` end in ``pytest.skip("<status>: <reason>")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "integration"
AREA = "RPT"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_integration_rpt(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
