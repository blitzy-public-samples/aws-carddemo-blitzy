"""Integration scenarios of area BAT: cross-job behaviour, run as job chains.

Each scenario is one ``tests/scenarios/integration/BAT-*.yaml`` file whose
``inputs.job`` is an ordered list of jobs that the adapter runs against one
catalog:

``BAT-POSTINT-normal-01``
    ``POSTTRAN`` -> ``INTCALC``, the job-chain proof on the repository data
    (no page-zero mapping needed). Markers ``integration``, ``chain``,
    ``gnucobol_batch``, ``unikix``.
``BAT-CHAIN-normal-01``
    ``POSTTRAN`` -> ``INTCALC`` -> ``TRANBKP`` -> ``COMBTRAN`` -> ``CREASTMT``
    -> ``TRANREPT``, the daily cycle; ``TRANBKP`` runs where the README runs
    it, because ``COMBTRAN`` reads its backup (GAP-DOC-015). Markers
    ``integration``, ``chain``, ``gnucobol_batch``, ``unikix``. It is blocked
    on gnucobol-batch when the page-zero probe fails (GAP-MIS-002), and on
    unikix because its clock is fixed and no clock command is confirmed
    (GAP-MIS-015).
``BAT-RUNORDER-normal-01``
    The README full batch order, ``CLOSEFIL`` ... ``OPENFIL``. Markers
    ``integration``, ``unikix``: only unikix can run it, so CI deselects it
    and reports it blocked (GAP-MIS-011).

``python tests/harness/report.py --check-proof`` requires the two ``chain``
scenarios to be ``passed`` on current records, and ``coverage-report.md``
reports the chain items on their own row of its By-level table, apart from
the other integration items.

A chain is one scenario, never a sequence of dependent tests, so no scenario
depends on another's output. The test ends as ``harness.runner.conclude``
maps the outcome: ``passed`` passes, ``failing`` raises ``AssertionError``
carrying the diff, and ``blocked`` or ``implemented-unexecuted`` calls
``pytest.skip("<status>: <reason> ...")``. The result record is written
before that, so skipped scenarios are recorded too.
"""

import pytest

from harness import runner, scenarios

LEVEL = "integration"
AREA = "BAT"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_integration_bat(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
