"""The ``unikix`` execution adapter: CardDemo scenarios on NTT DATA UniKix.

Online scenarios run in a UniKix TPE region and job scenarios in a UniKix BPE
node. The adapter runs only on an operator host that has a UniKix
installation, named by ``CARDDEMO_UNIKIX_ENV``. Without that variable every
``unikix`` scenario is ``blocked`` (GAP-MIS-009). CI never sets it, so no
``unikix`` scenario runs there.

Importing the package loads no submodule. ``adapters.unikix.adapter`` is
imported only by the callers that need it, so selecting another adapter never
loads UniKix code.

Modules:

``adapter``
    ``UniKixAdapter``, named ``unikix``. Starts the session, declares the
    observations the adapter can produce, resets the region before each
    scenario and confirms it is quiescent, and maps command outcomes to
    scenario statuses.
``env``
    Loads the ``CARDDEMO_UNIKIX_ENV`` file and derives the test-owned
    deployment variables from it. It is the only module that runs UniKix
    commands, and it runs only the commands listed in ``commands.yaml``.
``deploy``
    Extracts the UniKix zip read-only to ``<work root>/_unikix_zip/``, creates
    the test-owned deployment root ``<work root>/unikix/<execution_id>/``, and
    builds the application there.
``bpe``
    Submits BPE jobs, loads fixtures, unloads datasets (after a run, and at
    the end of a chain's job for ``@AFTER:<JOB>:<LOGICAL>``), and produces
    the ``@CATALOG`` and ``@AIX:<path>`` observations.
``tpe``
    Drives ``s3270`` over a loopback TN3270 address.
``evidence``
    Writes immutable evidence under
    ``tests/reports/unikix/<execution_id>/<scenario_id>/``.

Data files:

``resources.yaml``
    Maps each logical dataset to its UniKix DSN or cluster, and lists the
    transactions, region files and jobs.
``commands.yaml``
    Lists every UniKix command the adapter issues. Each entry stays
    ``unconfirmed`` until an operator confirms it.

The adapter never writes to ``app/``, ``samples/`` or ``diagrams/``, and never
modifies the UniKix zip.
"""
