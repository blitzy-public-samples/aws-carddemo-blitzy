"""Execution adapters of the CardDemo characterization suite.

The adapter layer turns one platform-neutral scenario into a run on one
platform and returns an ``adapters.base.Result``. Data flows one way:
scenarios never name an adapter, adapters never read baselines, and the
decoding, comparison and reporting layer only reads what adapters return.

The contract lives in ``adapters.base``: ``Adapter``, ``Result``,
``AdapterUnavailable`` (the scenario is ``blocked``), ``MissingPrerequisite``
(the scenario is ``implemented-unexecuted``), ``ScenarioTimeout`` and the
reserved observation names (``@CATALOG``, ``@AIX:<path>``, ``@REGION-FILES``,
``@TDQ:JOBS`` and the screen fields ``@map`` and ``@text``).

The concrete adapters are:

* ``adapters.gnucobol_batch.adapter.GnuCOBOLBatchAdapter``, name
  ``gnucobol-batch``, pytest marker ``gnucobol_batch``;
* ``adapters.unikix.adapter.UniKixAdapter``, name ``unikix``, pytest marker
  ``unikix``.

``tests/conftest.py`` selects one of them through ``CARDDEMO_ADAPTER``.

This package deliberately imports nothing: importing ``adapters.base`` (as
``harness/fixtures.py`` does) must not load the GnuCOBOL or UniKix modules,
start processes or touch the file system.
"""
