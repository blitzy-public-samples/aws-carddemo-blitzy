"""Integration scenarios of area TRN: the job ``POSTTRAN``.

One test, parametrized over ``tests/scenarios/integration/TRN-*.yaml``:

``TRN-POSTTRAN-normal-01``
    ``POSTTRAN`` (``CBTRN02C`` in ``STEP15``) on the repository's daily
    transactions. Both adapters can run it, so it carries the markers
    ``integration``, ``gnucobol_batch`` and ``unikix``; its baseline is
    observed on ``gnucobol-batch``.

The scenario and its baseline carry every expectation; this module only runs
them. ``harness.runner.run`` executes the scenario on the session's adapter,
``carddemo_record`` writes the result record, and ``harness.runner.conclude``
maps the status onto pytest: ``passed`` passes, ``failing`` raises
``AssertionError`` carrying the diff, and ``blocked`` and
``implemented-unexecuted`` call ``pytest.skip("<status>: <reason>")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "integration"
AREA = "TRN"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_integration_trn(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
