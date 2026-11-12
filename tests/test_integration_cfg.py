"""Integration scenarios of area CFG: the jobs that act on the CICS region.

Level ``integration``, area ``CFG``. One test, parametrized over
``tests/scenarios/integration/CFG-*.yaml`` (``harness.scenarios.load``):

``CFG-CBADMCDJ-normal-01``
    ``CBADMCDJ`` installs the CardDemo CSD resources with the z/OS CICS
    utility ``DFHCSDUP`` [app/jcl/CBADMCDJ.jcl:27-30]. UniKix takes its
    resources from the ``catlg/tpe`` lists installed at region creation, so
    the expected outcome is the job with its ``DFHCSDUP`` step absent and the
    region's files unchanged; the adapter blocks it under GAP-MIS-016 until
    an operator confirms an equivalent.
``CFG-OPENFIL-normal-01``, ``CFG-CLOSEFIL-normal-01``
    ``OPENFIL`` and ``CLOSEFIL`` issue ``CEMT SET FIL(...) OPE`` or ``CLO``
    through an SDSF step [app/jcl/OPENFIL.jcl:22-30]
    [app/jcl/CLOSEFIL.jcl:22-30]. On UniKix that step runs the copy's no-op
    ``SDSF.cbl`` (GAP-DOC-031), so a completed step proves nothing: each
    scenario asserts the region file inquiry just before the job is
    submitted (``@BEFORE:<JOB>:@REGION-FILES``) and after it
    (``@REGION-FILES``), which needs GAP-MIS-011.

Only ``unikix`` can run them, because they act on a running CICS region: the
``gnucobol-batch`` job driver has no step table for them, and their JCL items
are evaluated only by a real job scheduler. Their baselines are derived. With
the default adapter, ``conftest.py`` deselects them and ``harness/report.py``
reports them ``blocked``.

A run ends as ``harness.runner.conclude`` maps its status: ``passed`` passes;
``failing`` raises ``AssertionError`` carrying the diff; ``blocked`` and
``implemented-unexecuted`` call ``pytest.skip("<status>: <reason> ...")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "integration"
AREA = "CFG"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_integration_cfg(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
