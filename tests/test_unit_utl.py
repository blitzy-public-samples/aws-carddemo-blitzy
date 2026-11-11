"""Unit scenarios of area UTL: the date utility ``CSUTLDTC``.

Level ``unit``, adapter ``gnucobol-batch``. ``CSUTLDTC`` is built as a module
and called by the test-tree driver ``DTCDRV`` (``tests/stubs/DTCDRV.cbl``)
once per record of its ``DTCIN`` call list (date, mask). Its ``CALL
"CEEDAYS"`` reaches the C stub ``tests/stubs/ceedays.c``, which computes the
feedback token, or returns the token a scenario injects through
``preconditions.parameters.ceedays_feedback``. Expected outcomes live in the
scenarios and their baselines; ``harness.runner`` executes and compares them.

Scenarios (``tests/scenarios/unit/UTL-CSUTLDTC-*.yaml``, 18):

- ``normal-01``, ``normal-02``: a valid date with the mask ``YYYY-MM-DD``
  (``CORPT00C``, ``COTRN02C``) and with ``YYYYMMDD`` (``CSUTLDPY``).
- ``boundary-01``, ``boundary-02``: the first Lilian day and the day before
  it; 29 February in a leap year and in a common year.
- ``negative-01`` to ``negative-05``: an impossible day, an impossible
  month, a non-numeric year, a blank date, an invalid mask.
- ``negative-06`` to ``negative-14``: the injected tokens ``09CB``, ``09CC``,
  ``09CD``, ``09D1``, ``09D5``, ``09D6``, ``09D8``, ``09D9`` and an unlisted
  token, each mapped by ``CSUTLDTC`` to its message.

Each scenario's status becomes its pytest outcome: ``passed`` passes,
``failing`` raises ``AssertionError`` carrying the diff, and ``blocked`` or
``implemented-unexecuted`` ends in ``pytest.skip("<status>: <reason>")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "unit"
AREA = "UTL"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_unit_utl(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
