"""End-to-end scenarios of area CRD: the credit-card list, detail and update
programs, driven through the TPE 3270 listener.

Level ``e2e``, area ``CRD``. One test, parametrized over every
``tests/scenarios/e2e/CRD-*.yaml`` that ``harness.scenarios.load`` returns,
with the scenario id as the parameter id and ``harness.scenarios.marker_names``
as its marks (``e2e`` and ``unikix``):

- ``COCRDLIC`` (transaction ``CCLI``, map ``COCRDLI``), 21 scenarios:
  ``CRD-COCRDLIC-{normal-01, alternate-01..09, negative-01..05,
  recovery-01..04, boundary-01..02}``; ``boundary-01`` and ``-02`` page 7 and
  8 cards of one account.
- ``COCRDSLC`` (transaction ``CCDL``, map ``COCRDSL``), 16 scenarios:
  ``CRD-COCRDSLC-{normal-01, alternate-01..03, negative-01..08,
  recovery-01..04}``.
- ``COCRDUPC`` (transaction ``CCUP``, map ``COCRDUP``), 35 scenarios:
  ``CRD-COCRDUPC-{normal-01..03, alternate-01..06, negative-01..12,
  recovery-01..06, boundary-01..08}``; ``alternate-02`` is the two-session
  concurrent change.

Only the ``unikix`` adapter can run these scenarios, so under the default
``gnucobol-batch`` adapter ``conftest.py`` deselects all of them and
``harness/report.py`` reports them ``blocked``. Their baselines are derived
(GAP-INF-006).

``harness.runner.conclude`` maps each run's status onto pytest: ``passed``
passes; ``failing`` raises ``AssertionError`` carrying the diff; ``blocked``
and ``implemented-unexecuted`` end in ``pytest.skip("<status>: <reason>")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "e2e"
AREA = "CRD"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_e2e_crd(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
