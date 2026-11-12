"""Unit scenarios of area RPT: the batch report programs, run standalone on fixtures.

Level ``unit``, area ``RPT``. Programs: ``CBTRN03C`` (the daily transaction
report) and ``CBSTM03A`` (the text and HTML account statements), which calls
the file service ``CBSTM03B``. Every scenario runs on ``gnucobol-batch``.

Scenarios (22), loaded from ``tests/scenarios/unit/RPT-*.yaml``:

- ``RPT-CBTRN03C-{normal-01, normal-02, boundary-01 .. boundary-05,
  negative-01 .. negative-03, recovery-01 .. recovery-04}`` (14).
  ``recovery-02 .. recovery-04`` need a fault after a successful open, which
  no adapter injects, so they end ``implemented-unexecuted`` (GAP-MIS-013).
- ``RPT-CBSTM03A-{normal-01, normal-02, boundary-01 .. boundary-03,
  recovery-01 .. recovery-03}`` (8). ``recovery-02`` and ``recovery-03`` end
  ``implemented-unexecuted`` (GAP-MIS-013). All 8 end ``blocked``
  (GAP-MIS-002) when the adapter's page-zero probe fails in ``prepare``.

Expected outcomes live in the scenarios and their baselines; the report
behaviours pinned as observed (GAP-DEF-007, GAP-DEF-012) are not special-cased
here. ``harness.runner`` executes and compares, and the outcome maps onto
pytest as follows:

- ``passed``: the test passes.
- ``failing``: ``AssertionError`` carrying the diff.
- ``blocked`` and ``implemented-unexecuted``:
  ``pytest.skip("<status>: <reason> [<gap_id>] ...")``.

Each outcome's result record is written before it is concluded, so skipped
scenarios are recorded too.
"""

import pytest

from harness import runner, scenarios

LEVEL = "unit"
AREA = "RPT"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_unit_rpt(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
