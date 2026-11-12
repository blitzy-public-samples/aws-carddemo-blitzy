"""End-to-end scenarios of area SEC: sign-on, program ``COSGN00C``.

Level ``e2e``. Transaction ``CC00`` starts ``COSGN00C``, which sends map
``COSGN00``, checks the entered user id and password against the region file
``USRSEC`` and transfers a signed-on user to ``COADM01C`` or ``COMEN01C`` by
user type [app/cbl/COSGN00C.cbl:73-257] [app/csd/CARDDEMO.CSD:378-379].

Scenarios, one test each, parametrized from ``tests/scenarios/e2e/SEC-*.yaml``:

``SEC-COSGN00C-normal-01``
    ``ADMIN001`` signs on and reaches the admin menu.
``SEC-COSGN00C-normal-02``
    ``USER0001`` signs on and reaches the main menu.
``SEC-COSGN00C-alternate-01``
    PF3 ends the session with the unformatted farewell screen (``@map: TEXT``).
``SEC-COSGN00C-negative-01`` ... ``-negative-05``
    Blank user id, blank password, wrong password, the unknown id ``NOSUCHID``,
    and an invalid key.
``SEC-COSGN00C-recovery-01``
    ``USRSEC`` unavailable: 'Unable to verify the User ...'.

Only ``unikix`` can run them: it drives ``s3270`` against the TPE 3270
listener of a test-owned region, which must be on loopback. In CI
``tests/conftest.py`` deselects them and ``harness/report.py`` reports them
``blocked``. Their baselines are derived from the source and tagged
``inferred-not-observed`` (GAP-INF-002). Sign-on uses only the demo users
``ADMIN001`` and ``USER0001`` with their documented password ``PASSWORD``, an
invented wrong password, and the invented id ``NOSUCHID``. The ``SEC-*.yaml``
scenario files hold these inputs, and ``adapters/unikix/tpe.py`` reads the
listener address from ``CARDDEMO_TN3270_ADDR`` and refuses any host that is
not loopback; the test code below carries neither.

Outcome to pytest, through ``harness.runner.conclude``: ``passed`` passes;
``failing`` raises ``AssertionError`` carrying the diff; ``blocked`` and
``implemented-unexecuted`` end in ``pytest.skip("<status>: <reason>")``. The
result record is written before the test ends, whatever its outcome.
"""

import pytest

from harness import runner, scenarios

LEVEL = "e2e"
AREA = "SEC"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_e2e_sec(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
