"""End-to-end scenarios of area MNU: the main menu ``COMEN01C`` (transaction
CM00, map ``COMEN01``) and the admin menu ``COADM01C`` (transaction CA00, map
``COADM01``).

Each scenario of ``tests/scenarios/e2e/MNU-*.yaml`` is one test, with the
scenario id as its test id and the markers ``harness.scenarios.marker_names``
derives: ``e2e`` and ``unikix``. Only ``unikix`` drives the online
application, so under the default ``gnucobol-batch`` adapter ``conftest.py``
deselects every one of them and ``harness/report.py`` reports them
``blocked``. Their baselines are derived from the source (GAP-INF-003).

``COMEN01C`` (14)
    ``MNU-COMEN01C-alternate-01`` ... ``-alternate-10``: menu options 1 to 10,
    each reaching its target program's map; ``-alternate-11``: PF3 back to
    sign-on; ``-alternate-12``: CM00 entered without a COMMAREA;
    ``-negative-01``: the invalid options ``0``, ``11`` and ``X``
    (GAP-DEF-010); ``-negative-02``: an invalid key.

``COADM01C`` (8)
    ``MNU-COADM01C-alternate-01``, ``-03``, ``-04``, ``-05``: admin options 1
    to 4; ``-alternate-02``: ``USER0001`` enters CA00 directly and reaches the
    sign-on screen, and 'No access - Admin Only option...' never appears
    (GAP-DOC-019); ``-alternate-06``: PF3 back to sign-on; ``-negative-01``:
    the invalid options ``0``, ``5`` and ``X``; ``-negative-02``: an invalid
    key.

Status to pytest, through ``harness.runner.conclude``: ``passed`` passes;
``failing`` raises ``AssertionError`` with the diff; ``blocked`` and
``implemented-unexecuted`` call ``pytest.skip("<status>: <reason>")``.
"""

import pytest

from harness import runner, scenarios

LEVEL = "e2e"
AREA = "MNU"
SCENARIOS = scenarios.load(LEVEL, AREA)


@pytest.mark.parametrize(
    "scenario",
    [
        pytest.param(s, id=s.id, marks=[getattr(pytest.mark, m) for m in scenarios.marker_names(s)])
        for s in SCENARIOS
    ],
)
def test_e2e_mnu(scenario, carddemo_adapter, carddemo_record):
    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)
    runner.conclude(outcome)
