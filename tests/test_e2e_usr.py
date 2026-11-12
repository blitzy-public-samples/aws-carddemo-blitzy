"""End-to-end scenarios of area USR: the user-security maintenance programs.

Level ``e2e``. Programs and their scenarios under ``tests/scenarios/e2e/``:

- ``COUSR00C`` (CU00, user list, map COUSR00): ``USR-COUSR00C-normal-01``,
  ``-alternate-01`` .. ``-alternate-09``, ``-negative-01`` .. ``-negative-02``,
  ``-recovery-01``, ``-boundary-01`` and ``-boundary-02`` (10 and 11 users,
  one page or a second page); 15 scenarios.
- ``COUSR01C`` (CU01, user add, map COUSR01): ``USR-COUSR01C-normal-01``
  (ADMIN001 adds ``TESTU001``), ``-normal-02``, ``-alternate-01`` ..
  ``-alternate-02``, ``-negative-01`` .. ``-negative-07``, ``-recovery-01``;
  12 scenarios.
- ``COUSR02C`` (CU02, user update, map COUSR02): ``USR-COUSR02C-normal-01`` ..
  ``-normal-02``, ``-alternate-01`` .. ``-alternate-04``, ``-negative-01`` ..
  ``-negative-07``, ``-recovery-01`` .. ``-recovery-02``; 15 scenarios.
- ``COUSR03C`` (CU03, user delete, map COUSR03): ``USR-COUSR03C-normal-01`` ..
  ``-normal-02``, ``-alternate-01`` .. ``-alternate-03``, ``-negative-01`` ..
  ``-negative-03``, ``-recovery-01`` .. ``-recovery-02``; 10 scenarios. A
  failed USRSEC DELETE shows 'Unable to Update User...', pinned as it stands
  under GAP-DEF-011 (``-recovery-02``).

Only the ``unikix`` adapter can run them (markers ``e2e`` and ``unikix``), so
the default ``gnucobol-batch`` run deselects all 52 and the coverage report
shows them ``blocked``. Their baselines are derived (GAP-INF-004). The users the
scenarios create, ``TESTU001`` and ``TESTU002``, are data and never sign on.

Each test runs one scenario through ``harness.runner``, records its result and
ends as the outcome's status says: ``passed`` passes; ``failing`` raises
``AssertionError`` with the diff; ``blocked`` and ``implemented-unexecuted``
call ``pytest.skip("<status>: <reason> ...")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "e2e"
AREA = "USR"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_e2e_usr(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
