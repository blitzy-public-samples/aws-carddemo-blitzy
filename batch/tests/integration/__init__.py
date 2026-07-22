# CardDemo batch integration-test package.
#
# Houses the DB-backed golden-master parity suites that drive the batch chain
# against a real PostgreSQL test database (AAP §0.5.2 ``batch/tests/**``). This
# package marker keeps the tests importable as ``batch.tests.integration.*`` so
# pytest can collect the whole ``batch/tests`` tree in one run without
# test-module basename collisions (e.g. ``test_batch_chain.py`` exists in both
# ``unit`` and ``integration``).
