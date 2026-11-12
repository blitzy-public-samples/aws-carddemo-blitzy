# CardDemo batch unit-test package.
#
# Houses the DB-free unit suites for the batch CLI, jobs, loaders, and
# orchestration chain (AAP §0.5.2 ``batch/tests/**``). This package marker keeps
# the tests importable as ``batch.tests.unit.*`` so pytest can collect the whole
# ``batch/tests`` tree in one run without test-module basename collisions (e.g.
# ``test_batch_chain.py`` exists in both ``unit`` and ``integration``).
