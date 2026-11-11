"""Unit scenarios of area TRN: the batch programs ``CBTRN01C`` and ``CBTRN02C``.

Each scenario in ``tests/scenarios/unit/TRN-*.yaml`` runs one program
standalone on fixture files through the ``gnucobol-batch`` adapter, and its
outcome is compared with ``tests/baselines/<id>/``. The scenarios and
baselines hold every input and expected outcome; this module only collects
them and hands each to ``harness.runner``.

Scenarios (22)
    ``TRN-CBTRN01C-``: ``normal-01``, ``boundary-01``, ``negative-01``,
    ``negative-02``, ``recovery-01``, ``recovery-02``, ``recovery-03``.
    ``TRN-CBTRN02C-``: ``normal-01``, ``alternate-01``, ``boundary-01`` to
    ``boundary-03``, ``negative-01`` to ``negative-05``, ``recovery-01`` to
    ``recovery-05``. ``TRN-CBTRN02C-negative-01``, ``-02`` and ``-04`` are
    the proofs of reject reasons 100, 101 and 103, which the repository data
    never produces.

    ``recovery-02`` and higher need a read, write, rewrite or close failure
    after a successful open, which no adapter can inject (GAP-MIS-013), so
    they end ``implemented-unexecuted``.

Status -> pytest (``runner.conclude``)
    ``passed``: the test passes. ``failing``: ``AssertionError`` carrying the
    diff. ``blocked`` and ``implemented-unexecuted``:
    ``pytest.skip("<status>: <reason> ...")``. Every outcome's result record
    is written through ``carddemo_record`` before it is concluded.
"""

import pytest

from harness import runner, scenarios

LEVEL = "unit"
AREA = "TRN"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_unit_trn(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
