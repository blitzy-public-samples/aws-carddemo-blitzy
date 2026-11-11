"""End-to-end scenarios of area RPT: the online report request ``CORPT00C``.

Level ``e2e``, area ``RPT``, program ``CORPT00C`` (transaction ``CR00``, map
``CORPT00``), which validates the report choice and dates on its screen and
writes the ``TRNRPT00`` job (``EXEC PROC=TRANREPT``) to the TDQ ``JOBS``.
The batch RPT scenarios live in ``test_unit_rpt.py`` and
``test_integration_rpt.py``; this module loads only
``tests/scenarios/e2e/RPT-*.yaml``.

Scenarios (31), each marked ``e2e`` and ``unikix``, with derived baselines
(GAP-INF-009) and ``clock_mode: relative``::

    RPT-CORPT00C-normal-01 .. normal-05
    RPT-CORPT00C-alternate-01 .. alternate-03
    RPT-CORPT00C-negative-01 .. negative-17
    RPT-CORPT00C-recovery-01
    RPT-CORPT00C-boundary-01 .. boundary-05

``RPT-CORPT00C-normal-01`` (monthly), ``-normal-02`` (yearly) and
``-normal-03`` (custom) assert the job written to ``JOBS`` as ``@TDQ:JOBS``
(GAP-MIS-017).

Only ``unikix`` can run these scenarios, so under the default
``gnucobol-batch`` adapter ``conftest.py`` deselects all 31 and
``harness/report.py`` reports them ``blocked``. Run them with
``CARDDEMO_ADAPTER=unikix pytest tests/ -m unikix``.

Each scenario's outcome maps onto pytest through ``harness.runner.conclude``:
``passed`` passes; ``failing`` raises ``AssertionError`` carrying the diff;
``blocked`` and ``implemented-unexecuted`` end in
``pytest.skip("<status>: <reason>")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "e2e"
AREA = "RPT"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_e2e_rpt(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
