"""End-to-end scenarios of area ACT: account view and account update.

Level ``e2e``, area ``ACT``. The programs are ``COACTVWC`` (account view,
transaction ``CAVW``, map ``COACTVW``) and ``COACTUPC`` (account update,
transaction ``CAUP``, map ``COACTUP``), the latter with the date edits of the
procedure copybook ``CSUTLDPY`` and its ``CSUTLDTC`` call. The module
parametrizes one test over the 194 scenarios of
``tests/scenarios/e2e/ACT-*.yaml``, their ids taken from the file stems::

    ACT-COACTVWC-  normal-01, alternate-01..03, negative-01..08,
                   recovery-01..05                                    (17)
    ACT-COACTUPC-  normal-01..04, alternate-01..06, negative-001..130,
                   recovery-01..09, boundary-01..28                   (177)

``ACT-COACTVWC-recovery-01`` reads the account with ``CXACAIX`` unavailable
and expects 'File Error: READ on CXACAIX'; ``ACT-COACTUPC-alternate-02`` runs
two sessions and expects 'Record changed by some one else. Please review'.

Only ``unikix`` runs these scenarios, driving the TPE 3270 listener. Under the
default ``gnucobol-batch`` adapter ``conftest.py`` deselects them and
``harness/report.py`` reports them ``blocked``. Each scenario and its derived
baseline (GAP-INF-005) hold every input and expected outcome;
``harness.runner`` executes and compares, so this module asserts nothing of
its own. A run's status becomes the pytest outcome:

- ``passed``: the test passes;
- ``failing``: ``AssertionError`` carrying the diff;
- ``blocked`` and ``implemented-unexecuted``: ``pytest.skip("<status>:
  <reason> ...")``.

Every outcome gets its result record before it is concluded.
"""

import pytest

from harness import runner, scenarios

LEVEL = "e2e"
AREA = "ACT"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_e2e_act(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
