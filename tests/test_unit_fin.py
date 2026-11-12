"""Unit scenarios of area FIN: the interest calculation ``CBACT04C``.

Level ``unit``, adapter ``gnucobol-batch``. ``CBACT04C`` has a ``PROCEDURE
DIVISION USING`` linkage, so it is built as a module and called by the
``tests/stubs/PARMDRV.cbl`` driver with the scenario's ``parm: '2022071800'``,
the PARM of ``app/jcl/INTCALC.jcl``. The scenarios and their baselines hold
every input and expected outcome; ``harness.runner`` executes and compares.

Scenarios (``tests/scenarios/unit/FIN-CBACT04C-*.yaml``):
    ``FIN-CBACT04C-normal-01``, ``-alternate-01``, ``-alternate-02``,
    ``-boundary-01``, ``-boundary-02``, ``-boundary-03``, ``-negative-01``,
    ``-recovery-01``, ``-recovery-02``, ``-recovery-03``, ``-recovery-04``,
    ``-recovery-05``. ``-recovery-02`` to ``-05`` inject a fault after a
    successful open, which no adapter can do, so they end
    ``implemented-unexecuted`` under GAP-MIS-013.

Status -> pytest
    ``passed`` passes; ``failing`` raises ``AssertionError`` carrying the
    diff; ``blocked`` and ``implemented-unexecuted`` end in
    ``pytest.skip("<status>: <reason> ...")``. Every outcome gets its result
    record before it is concluded.
"""

import pytest

from harness import runner, scenarios

LEVEL = "unit"
AREA = "FIN"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_unit_fin(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
