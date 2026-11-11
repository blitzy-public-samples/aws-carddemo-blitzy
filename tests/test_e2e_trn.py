"""End-to-end scenarios of area TRN: the online transaction programs.

Level ``e2e``, area ``TRN``; one test per scenario file
``tests/scenarios/e2e/TRN-*.yaml``, each driven through the TPE 3270 listener
by the ``unikix`` adapter and compared with its derived baseline
(GAP-INF-007):

- ``COTRN00C`` (CT00, map ``COTRN00``), the transaction list:
  ``TRN-COTRN00C-{normal-01, alternate-01..08, negative-01..03,
  recovery-01, boundary-01..02}`` (15; the boundaries are 10 and 11
  transactions, one page or two).
- ``COTRN01C`` (CT01, map ``COTRN01``), the transaction view:
  ``TRN-COTRN01C-{normal-01, alternate-01..02, negative-01..03,
  recovery-01}`` (7).
- ``COTRN02C`` (CT02, map ``COTRN02``), the transaction add:
  ``TRN-COTRN02C-{normal-01..03, alternate-01..04, negative-01..28,
  recovery-01..04, boundary-01..03}`` (42; ``normal-01`` adds a transaction
  for card ``0500024453765740`` with Tran ID max+1, ``negative-03`` enters
  card ``9999999999999999`` and gets 'Card Number NOT found...').

Every scenario is marked ``e2e`` and ``unikix``, so under the default
``gnucobol-batch`` adapter ``conftest.py`` deselects them all and the report
lists them ``blocked``. The batch TRN scenarios live in ``test_unit_trn.py``
and ``test_integration_trn.py``.

Status -> pytest (``harness.runner.conclude``): ``passed`` passes;
``failing`` raises ``AssertionError`` carrying the diff; ``blocked`` and
``implemented-unexecuted`` call ``pytest.skip("<status>: <reason>")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "e2e"
AREA = "TRN"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_e2e_trn(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
