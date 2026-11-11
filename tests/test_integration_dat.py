"""Integration scenarios of area DAT: the data jobs run as whole jobs.

Level ``integration``, area ``DAT``: every
``tests/scenarios/integration/DAT-*.yaml``. The scenarios and their baselines
carry every expectation, and ``harness.runner`` executes and compares them, so
this module holds no business logic, parses no JCL and has no adapter-specific
branch.

Jobs covered
    The load jobs ``ACCTFILE``, ``CARDFILE``, ``CUSTFILE``, ``XREFFILE``,
    ``TRANFILE``, ``DISCGRP``, ``TCATBALF``, ``TRANCATG`` and ``TRANTYPE``;
    the index, backup and combine jobs ``TRANIDX``, ``TRANBKP`` and
    ``COMBTRAN``; ``DUSRSECJ``; the definition jobs ``DEFGDGB``, ``DALYREJS``,
    ``REPTFILE`` and ``DEFCUST``; the read jobs ``READACCT``, ``READCARD``,
    ``READCUST`` and ``READXREF``; and the JCL semantics of ``ACCTFILE``,
    ``POSTTRAN``, ``TRANBKP`` and ``XREFFILE``. The proc ``REPROC`` and the
    control card ``REPROCT`` are covered through ``DAT-TRANBKP-*``. A
    ``DAT-POSTTRAN-*`` scenario lives here, not in ``test_integration_trn.py``,
    because its primary item is a DAT JCL item.

Scenarios (26)
    Both adapters (markers ``integration``, ``gnucobol_batch``, ``unikix``;
    baselines observed on ``gnucobol-batch``): ``DAT-<JOB>-normal-01`` for
    ``ACCTFILE``, ``CARDFILE``, ``CUSTFILE``, ``XREFFILE``, ``TRANFILE``,
    ``DISCGRP``, ``TCATBALF``, ``TRANCATG``, ``TRANTYPE``, ``TRANIDX``,
    ``TRANBKP``, ``COMBTRAN``, ``DUSRSECJ``, ``DEFGDGB``, ``DALYREJS``,
    ``REPTFILE``, ``DEFCUST``, ``READACCT``, ``READCARD``, ``READCUST`` and
    ``READXREF`` (21).

    ``unikix`` only (markers ``integration``, ``unikix``; derived baselines):
    ``DAT-ACCTFILE-recovery-01``, ``DAT-POSTTRAN-recovery-01``,
    ``DAT-TRANBKP-recovery-01``, ``DAT-POSTTRAN-alternate-01`` and
    ``DAT-XREFFILE-alternate-01`` (5). Only a real job scheduler evaluates
    ``COND``, ``DISP``, ``SET MAXCC``, GDG rolls and alternate-index paths, so
    under the default adapter these are deselected and ``harness/report.py``
    reports them ``blocked``.

    The markers come from ``harness.scenarios.marker_names``, and
    ``conftest.py`` deselects every scenario without the selected adapter's
    marker.

Status -> pytest
    ``passed`` passes; ``failing`` raises ``AssertionError`` carrying the diff;
    ``blocked`` and ``implemented-unexecuted`` end in
    ``pytest.skip("<status>: <reason> [<gap_id>] ...")``. The result record is
    written before the verdict, so skipped scenarios have one too.
"""

import pytest

from harness import runner, scenarios

LEVEL = "integration"
AREA = "DAT"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_integration_dat(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
