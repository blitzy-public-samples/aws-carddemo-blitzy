"""End-to-end scenarios of area CFG: the region configuration of the UniKix copy.

Level ``e2e``, area ``CFG``. One scenario, ``CFG-FCT-normal-01``, named after
the ``fct.lst`` resource list because it runs no single program or job. With
the test-owned UniKix region started and reset, it asserts the region file
inquiry (``@REGION-FILES``): the 14 files of the copy's
``migrated_app/catlg/tpe/fct.lst``, each open and enabled. That verifies the 6
derived ``config`` items the list defines beyond the CSD's 8 FILE definitions,
``DISCGRP``, ``TCATBALF``, ``TRANCATG``, ``TRANSAIX``, ``TRANTYPE`` and
``TRXFL`` (GAP-DOC-030), which have no ``app/`` counterpart.

Only ``unikix`` can run it, so the default ``gnucobol-batch`` session
deselects it and the report counts it ``blocked``. Its baseline is derived
(GAP-INF-010), and it stays blocked on ``unikix`` until an operator confirms a
region file inquiry command (GAP-MIS-011).

Each scenario's outcome maps onto pytest through ``harness.runner.conclude``:
``passed`` passes, ``failing`` raises ``AssertionError`` with the diff, and
``blocked`` or ``implemented-unexecuted`` skips with
``"<status>: <reason>"``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "e2e"
AREA = "CFG"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_e2e_cfg(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
