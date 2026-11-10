"""The ``gnucobol-batch`` execution adapter.

This package runs the batch side of CardDemo on Linux. Each batch unit of
``app/cbl`` is compiled with GnuCOBOL 3.2 and run standalone on fixture
datasets (unit scenarios). A job driver runs the jobs, procs and job chains
from step tables transcribed from the JCL (integration scenarios). The compile
options ``-std=ibm -fsign=EBCDIC -ftab-width=4 -fbinary-truncate`` reproduce
the Enterprise COBOL ``PARM=(APOST,LIST,MAP,NUMBER,NOSEQ)`` build with the
default ``TRUNC(STD)`` of ``samples/proc/BUILDBAT.prc``.

Modules:

``adapter.py``
    ``GnuCOBOLBatchAdapter``, the ``adapters.base.Adapter`` implementation
    named ``gnucobol-batch`` (pytest marker ``gnucobol_batch``).
``build.py``
    Compilation of the batch units, the test-tree stubs and drivers, the
    generated indexed-file loaders and unloaders, and the page-zero probe,
    into a build cache keyed by the sha256 of sources, copybooks, options
    and ``cobc --version``.
``datasets.py``
    Dataset materialization and unload, and the per-scenario catalog with
    its GDG emulation, which also produce the ``@CATALOG`` and
    ``@AIX:<path>`` observations.
``utilities.py``
    Replacements for the IDCAMS, SORT, IEBGENER, IEFBR14 and SDSF steps.
``jobs.py``
    The job driver and the program runner it shares with unit scenarios.
``programs.yaml``
    Each program's ddname-to-logical-dataset map, with organization and keys.
``jobs.yaml``
    One step table per job; the driver never parses JCL.

Every write happens under the work root: the build cache in
``<work root>/_build/<build key>/`` and each scenario's datasets and evidence
in ``<work root>/<scenario_id>/``. Nothing is written in the repository, and
``app/``, ``samples/`` and ``diagrams/`` are only read.

Importing this package imports no submodule, starts no process and reads no
file, so ``adapters.gnucobol_batch.adapter`` is imported explicitly by its
callers.
"""
