"""Batch chain orchestration package for the CardDemo Python batch jobs.

Exposes :mod:`batch.orchestration.batch_chain`, which reproduces the legacy
JCL batch sequence (see README.md chain and app/jcl/*.jcl) as an ordered,
per-step-transactional Python pipeline. Import submodules explicitly, e.g.
``from batch.orchestration.batch_chain import RunBatchChain, SeedAll``.
"""

__version__ = "1.0.0"
