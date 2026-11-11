"""Unit scenarios of area DAT: the four read programs run standalone.

Level ``unit``, area ``DAT``. The programs are ``CBACT01C`` (account file),
``CBACT02C`` (card file), ``CBACT03C`` (cross-reference file) and
``CBCUS01C`` (customer file), each listing its indexed file in key order.
The module parametrizes one test over the 20 scenarios of
``tests/scenarios/unit/DAT-*.yaml``::

    DAT-CBACT01C-, DAT-CBACT02C-, DAT-CBACT03C-, DAT-CBCUS01C-
        normal-01, boundary-01, recovery-01, recovery-02, recovery-03

Each scenario names its inputs and expected outcomes; ``harness.runner`` runs
it on the adapter ``CARDDEMO_ADAPTER`` selects (``gnucobol-batch`` runs all
20) and compares the result with ``tests/baselines/<id>/``. This module holds
no assertion of its own. A run's status becomes the pytest outcome:

- ``passed``: the test passes;
- ``failing``: ``AssertionError`` carrying the diff;
- ``blocked`` and ``implemented-unexecuted``: ``pytest.skip("<status>:
  <reason> ...")``. The ``recovery-02`` and ``recovery-03`` cases need a read
  or close failure after a successful open, which no adapter injects, so they
  end ``implemented-unexecuted`` under GAP-MIS-013.
"""

import pytest

from harness import runner, scenarios

LEVEL = "unit"
AREA = "DAT"

SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_unit_dat(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
