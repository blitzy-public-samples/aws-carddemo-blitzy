"""Integration scenarios of area FIN: the interest-calculation job ``INTCALC``.

Level ``integration``, area ``FIN``. One scenario, ``FIN-INTCALC-normal-01``,
runs job ``INTCALC`` (STEP15, ``CBACT04C`` with ``PARM='2022071800'``) on the
repository fixtures. Both adapters can run it, so it carries the markers
``integration``, ``gnucobol_batch`` and ``unikix``; its expected outcome lives
in the scenario file and its baseline, never here.

Each scenario's run ends in pytest as its status says:

- ``passed``: the test passes.
- ``failing``: ``AssertionError`` carrying the diff against the baseline.
- ``blocked`` and ``implemented-unexecuted``: ``pytest.skip("<status>: <reason>")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "integration"
AREA = "FIN"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_integration_fin(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
