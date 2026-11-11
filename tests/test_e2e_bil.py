"""End-to-end scenarios of area BIL: the online bill payment program ``COBIL00C``.

Level ``e2e``, area ``BIL``. The scenarios are the files
``tests/scenarios/e2e/BIL-*.yaml``, 22 in the id manifest:

- ``BIL-COBIL00C-normal-01`` .. ``-normal-04``
- ``BIL-COBIL00C-alternate-01`` .. ``-alternate-03``
- ``BIL-COBIL00C-negative-01`` .. ``-negative-07``
- ``BIL-COBIL00C-recovery-01`` .. ``-recovery-05``
- ``BIL-COBIL00C-boundary-01`` .. ``-boundary-03`` (balances 0.00, 0.01 and
  -0.01)

``BIL-COBIL00C-normal-01`` is the fixed id: account ``00000000001`` pays its
full balance and gets 'Payment successful.  Your Transaction ID is <max+1>.'.

Every scenario is driven through the TPE 3270 listener, so only ``unikix`` can
run it: each carries the ``e2e`` and ``unikix`` markers, is deselected under
the default ``gnucobol-batch`` adapter and reported ``blocked``. Their
baselines are derived (GAP-INF-008).

Status to pytest outcome (``harness.runner.conclude``):

- ``passed``: the test passes.
- ``failing``: ``AssertionError`` carrying the diff.
- ``blocked`` and ``implemented-unexecuted``:
  ``pytest.skip("<status>: <reason>")``, with the gap id.
"""

import pytest

from harness import runner, scenarios

LEVEL = "e2e"
AREA = "BIL"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_e2e_bil(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
