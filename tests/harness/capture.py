"""Observed baseline capture on ``gnucobol-batch``.

    python tests/harness/capture.py <scenario_id> [<scenario_id> ...] [--bump major|minor|patch]
    python tests/harness/capture.py --all [--bump major|minor|patch]

Each scenario runs on ``gnucobol-batch`` through ``harness.runner.run``
without a comparison, and what it observed becomes its baseline
``tests/baselines/<id>/`` (AAP 0.4.5):

``expected.yaml``
    Exactly the keys the scenario's ``expected`` names, as observed: the
    return code, every message line, the DALYREJS reason codes, and every
    record of each ``datasets`` and ``file_state`` entry (``@CATALOG`` and
    ``@AIX:<path>`` included), decoded through ``tests/inventory/layouts.yaml``.
    A field the scenario gives a matcher (in one of its own ``records`` or
    ``contains`` records, in the entry's ``matchers`` or in the top-level
    ``matchers``) holds that matcher instead of the observed value, once the
    observed value is checked to be exactly the matcher's value under the
    scenario clock. Fields are matched as the comparison addresses them
    (``baselines.bind_matchers``): by decoded name or by field path, and a
    qualified name binds to the decoded field it denotes. The baseline then
    holds no clock-derived value that a run on another clock would contradict.
``datasets/<LOGICAL>.bin``
    The raw bytes of each business dataset those two sections name.
``manifest.yaml``
    ``baseline_version``, ``source_commit`` (HEAD), ``capture_platform:
    gnucobol-batch``, ``capture_date`` (today), the run's
    ``execution_fingerprint`` and ``source_identity``, and the
    ``execution_id`` of a run of the written baseline version, made with the
    baseline's new version as every later run of the same commit and build
    makes it (``manifest_execution_id``, ``record_at_version``).

Versions are planned against HEAD (``baselines.bump_needed``). A baseline
HEAD does not hold starts at ``1.0.0`` and ``--bump`` is ignored for it, also
when one was written since HEAD: the bump check against HEAD still treats it
as new. Replacing a baseline HEAD holds needs ``--bump``, which raises HEAD's
committed version, never a working-tree copy's, so the same command plans
the same version however often it runs. The level must cover the change as
``baselines.py --check-bumps --base HEAD`` judges it: MAJOR when an expected
value changes, MINOR when assertions or raw bytes are added or the capture
platform changes (a derived baseline replaced by an observed one), PATCH when
only the manifest changes; a lower ``--bump`` is refused with the level the
change needs. When git cannot tell what HEAD holds, the capture is refused.

A capture is refused, and nothing is written, when gnucobol-batch cannot run
the scenario (its baseline is derived, and only ``report.py --import-unikix``
promotes it); when the run does not pass: ``blocked``, ``failing``, or
``implemented-unexecuted`` as the post-open fault scenarios end, because no
adapter injects their fault (GAP-MIS-013), so no raw artifact is fabricated
for them; when an observed value contradicts the scenario's matcher; and when
the observed result does not compare equal to the would-be baseline through
``baselines.compare``, which also checks the scenario's inline expectations.

Exit status: 0 when every requested baseline was written, 1 when any capture
was refused or failed (with ``--all`` a scenario whose run ends
``implemented-unexecuted`` is reported and skipped, not an error), 2 for a
usage error. The committed ``tests/reports/results/<id>.yaml`` of a written
baseline still carries its old ``baseline_version``, so ``report.py`` counts
it as stale, which fails ``--check`` (and ``--check-proof`` for a job-chain
proof scenario), until a pytest run rewrites it and regenerates the reports:
the closing summary names that run, ``pytest tests/ -k <id>`` (``-k "<id> or
<id>"`` for several) for the written ids, or ``pytest tests/`` after
``--all``. Every written baseline must then pass ``python
tests/harness/baselines.py --check-bumps --base HEAD``.

The work root is ``--work-root``, else ``CARDDEMO_TEST_WORKDIR``, else a new
``tempfile.mkdtemp(prefix="carddemo-")`` that is removed at the end unless a
failing run kept its working directory there; it never lies inside the
repository. A configured root is used only when ``harness.usable_work_root``
accepts it (not blank, outside the repository, its resolved path holding no
backquote, ``$``, ``"``, ``\\`` or character that is not printable and no
longer than the work-root limit, a directory, created with mode 0700 when
missing, the user's own and writable by neither group nor others, and
readable, writable and searchable by the user, its owner, as the runner opens
it and makes each scenario's working directory in it). An unusable
``--work-root`` is a usage error; an unusable
``CARDDEMO_TEST_WORKDIR`` is named on stderr with the reason, and the new
temporary root replaces it. That temporary root must pass the same path rule
(``harness.check_work_root_path``), or the run is a usage error and the root
is removed. A configured root serves one run or pytest
session at a time, since each scenario's ``<work root>/<scenario_id>/`` is
emptied before it runs: the capture holds ``<work root>/_session.lock``
(``harness.lock_work_root``) until it ends, and a root another run or session
holds is a usage error naming that holder. Version planning parses HEAD's
YAML in a temporary folder inside the work root. Inside the repository the
script writes only under ``tests/baselines/``, besides the git-ignored runtime
files the runner writes under ``tests/reports/runtime/``: ``baselines.write``
publishes each ``<id>/`` whole, under a lock on that folder, through a staging
directory ``.<id>.<pid>.<8 hex>.new`` beside it (and, where the kernel cannot
exchange two names, a pre-image ``.<id>.<pid>.<8 hex>.old``), so an
interrupted or concurrent capture leaves the last complete baseline, or none,
and a retry needs no cleanup.
"""

from __future__ import annotations

import argparse
import copy
import datetime as _dt
import importlib
import os
import shutil
import sys
import tempfile
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from harness import (  # noqa: E402
    REPO_ROOT,
    HarnessError,
    check_work_root_path,
    git_head,
    lock_work_root,
    usable_work_root,
)
from harness import baselines as _baselines  # noqa: E402
from harness import results as _results  # noqa: E402
from harness import runner as _runner  # noqa: E402
from harness import scenarios as _scenarios  # noqa: E402

__all__ = [
    "ADAPTER",
    "CaptureError",
    "Captured",
    "build_expected",
    "build_manifest",
    "capture",
    "main",
    "make_adapter",
    "manifest_execution_id",
    "plan_version",
    "raw_datasets",
    "record_at_version",
    "select_all",
]

ADAPTER = _results.GNUCOBOL

# The concrete adapter, resolved at run time: the class tests/conftest.py
# instantiates. This is the harness's only resolution of a concrete adapter,
# so no harness module imports one statically.
_ADAPTER_MODULE = "adapters.gnucobol_batch.adapter"
_ADAPTER_CLASS = "GnuCOBOLBatchAdapter"

_WORKDIR_ENV = "CARDDEMO_TEST_WORKDIR"
_WORK_ROOT_PREFIX = "carddemo-"

_SECTIONS = ("datasets", "file_state")
_LINE_KEYS = ("messages", "reject_reason_codes")
_WHOLE_RECORD = "*"  # the key of a matcher that stands for a whole line
_RAW_DETAIL_CHARS = 160


class CaptureError(HarnessError):
    """Why a scenario's observed baseline is not written."""


@dataclass
class Captured:
    """How the capture of one scenario ended.

    ``written`` when its baseline was written at ``new_version``;
    ``old_version`` is the version it replaced: HEAD's for a baseline HEAD
    holds, else the working tree's (``None`` for a new baseline). Otherwise
    ``detail`` says why nothing was written and ``unexecuted`` whether the
    run ended ``implemented-unexecuted``. ``kept`` is the working directory a
    failing run kept."""

    scenario_id: str
    written: bool = False
    unexecuted: bool = False
    old_version: str | None = None
    new_version: str | None = None
    detail: str = ""
    kept: Path | None = None


# --------------------------------------------------------------------------
# Matchers
# --------------------------------------------------------------------------


def _settled(matcher: Mapping[str, Any], observed: Any, clock: str, where: str) -> dict:
    """``matcher`` as the baseline holds it, once ``observed`` is checked to be
    exactly the one value it stands for under the fixed ``clock``."""
    problem = _baselines.check_matcher(matcher, observed, clock, True)
    if problem is not None:
        raise CaptureError(f"{where}: the observed value does not satisfy the scenario's matcher: {problem}")
    return dict(matcher)


def _with_matchers(
    records: Sequence[Any], bindings: Sequence[Mapping[str, Mapping[str, Any]]], clock: str, where: str
) -> list:
    """``records`` (one decoded dataset or observation) with every field
    ``bindings`` gives a matcher holding that matcher, and a line whose
    binding is ``*`` the line's: ``bindings`` holds one ``{decoded key:
    matcher}`` per record, where ``baselines.bind_matchers`` places the
    scenario's matchers as ``baselines.compare`` checks them. Each replaced
    value is first checked against its matcher (``_settled``)."""
    if len(bindings) != len(records):
        raise CaptureError(
            f"{where}: the matchers are bound to {len(bindings)} record(s), not to the {len(records)} observed"
        )
    out: list = []
    for index, (record, bound) in enumerate(zip(records, bindings)):
        shown = f"{where} record {index + 1}"
        if isinstance(record, str):
            out.append(_settled(bound[_WHOLE_RECORD], record, clock, shown) if _WHOLE_RECORD in bound else record)
            continue
        if not isinstance(record, dict):
            raise CaptureError(f"{shown}: a decoded {type(record).__name__} is neither a field map nor a line")
        settled: dict[str, Any] = {}
        for name, value in record.items():
            matcher = bound.get(name)
            settled[name] = value if matcher is None else _settled(matcher, value, clock, f"{shown} {name}")
        out.append(settled)
    return out


# --------------------------------------------------------------------------
# The observed baseline
# --------------------------------------------------------------------------


def build_expected(scenario: Any, result: Any) -> dict:
    """The ``expected.yaml`` of an observed baseline: exactly the keys the
    scenario's ``expected`` names (its ``matchers`` aside), valued as
    ``result`` observed them (``baselines.decode_result``), with the
    scenario's matchers in place of the clock-derived fields (module
    docstring). ``CaptureError`` when a value was not observed or contradicts
    its matcher, or a matcher cannot be placed in a decoded field."""
    wanted = scenario.expected
    decoded = _baselines.decode_result(scenario, result)
    clock = _scenarios.clock(scenario)
    try:
        bindings = _baselines.bind_matchers(scenario, result, ADAPTER)
    except _baselines.BaselineError as exc:
        raise CaptureError(str(exc)) from None
    expected: dict[str, Any] = {}
    if "return_code" in wanted:
        expected["return_code"] = decoded["return_code"]
    for key in _LINE_KEYS:
        if key in wanted:
            lines = decoded.get(key)
            if not isinstance(lines, list):
                raise CaptureError(f"{key}: {_clip(lines)} was observed, which cannot stand as its value")
            expected[key] = list(lines)
    for section in _SECTIONS:
        entries = wanted.get(section)
        if not isinstance(entries, Mapping):
            continue
        observed = decoded.get(section)
        observed = observed if isinstance(observed, Mapping) else {}
        expected[section] = {}
        for name in entries:
            records = observed.get(str(name))
            if not isinstance(records, list):
                raise CaptureError(f"{section}.{name}: {_clip(records)} was observed, not a list of records")
            expected[section][str(name)] = _with_matchers(
                records, bindings.get((section, str(name)), []), clock, f"{section}.{name}"
            )
    if "screens" in wanted:
        expected["screens"] = copy.deepcopy(decoded.get("screens"))
    return expected


def _clip(value: Any) -> str:
    text = repr(value)
    return text if len(text) <= _RAW_DETAIL_CHARS else text[: _RAW_DETAIL_CHARS - 3] + "..."


def raw_datasets(scenario: Any, result: Any) -> dict[str, bytes]:
    """The ``datasets/<LOGICAL>.bin`` content of an observed baseline: the raw
    bytes of each business dataset the scenario's ``expected.datasets`` and
    ``expected.file_state`` name, exactly as the adapter returned them.
    Reserved observations (``@CATALOG``, ``@AIX:<path>``,
    ``@AFTER:<JOB>:<LOGICAL>``, ``@REGION-FILES`` and its region stage
    observations) have none."""
    raw: dict[str, bytes] = {}
    for section in _SECTIONS:
        entries = scenario.expected.get(section)
        if not isinstance(entries, Mapping):
            continue
        for name in map(str, entries):
            if name.startswith("@") or name in raw:
                continue
            data = result.datasets.get(name)
            if data is None:
                raise CaptureError(f"{section}.{name}: the run produced no {name}, so it has no raw bytes")
            raw[name] = bytes(data)
    return raw


def _head_need(candidate: Any, work_root: Path) -> Any:
    """How ``baselines.py --check-bumps --base HEAD`` judges writing
    ``candidate`` (a ``baselines.Baseline``): ``baselines.bump_needed``
    against HEAD, ``None`` when HEAD does not hold the baseline. HEAD's YAML
    is parsed in a temporary folder under ``work_root``, the capture's
    existing work root, so the check writes nothing outside it. A git
    failure is never taken for absence: it, and a HEAD baseline whose change
    cannot be judged, raise ``CaptureError``."""
    folder = f"{_baselines.BASELINES_REL}/{candidate.id}/"
    try:
        return _baselines.bump_needed(candidate, scratch=work_root)
    except _baselines.BumpCheckError as exc:
        raise CaptureError(f"git cannot tell what HEAD holds for {folder}: {exc}; nothing is written") from None
    except _baselines.BaselineError as exc:
        raise CaptureError(f"the change of HEAD's {folder} cannot be judged: {exc}; nothing is written") from None


def plan_version(need: Any, bump: str | None, existing: Any = None) -> tuple[str | None, str]:
    """``(old version, new version)`` of the baseline about to be written,
    from ``need`` (``baselines.bump_needed`` against HEAD: a ``BumpNeed``,
    or ``None`` when HEAD does not hold the baseline) and ``existing``, the
    working-tree baseline (``None`` when there is none).

    A baseline HEAD does not hold is new to the bump check against HEAD,
    which requires ``1.0.0``, so it gets ``1.0.0`` whatever ``bump`` says and
    however often it was written since HEAD; its old version is the working
    tree's. Replacing one HEAD holds needs ``bump``, at least ``need.level``,
    and raises HEAD's committed version, never the working tree's, so the
    same ``need`` and ``bump`` always plan the same version. ``CaptureError``
    otherwise, naming the level the change needs."""
    if bump is not None and bump not in _baselines.BUMP_LEVELS:
        raise CaptureError(f"--bump {bump} is not one of {', '.join(_baselines.BUMP_LEVELS)}")
    if need is None:
        return (existing.version if existing is not None else None), _baselines.NEW_VERSION
    if bump is None:
        raise CaptureError(
            f"its {need.base_platform} baseline exists at {need.base_version} at HEAD; replacing it needs "
            f"--bump {'|'.join(_baselines.BUMP_LEVELS)}"
        )
    if not need.allows(bump):
        raise CaptureError(
            f"HEAD holds its {need.base_platform} baseline {need.base_version}: {need.reason}, which needs at "
            f"least --bump {need.level} (--bump {bump} given); nothing is written"
        )
    return need.base_version, _baselines.bump(need.base_version, bump)


def build_manifest(version: str, record: Any, execution_id: str | None = None) -> dict:
    """The ``manifest.yaml`` of an observed baseline at ``version``, naming the
    execution of the run's result ``record`` (``results.build_record``).
    ``execution_id``, when given, replaces the record's own
    (``manifest_execution_id``)."""
    evidence = {
        key: copy.deepcopy(record.get(key)) if isinstance(record, Mapping) else None
        for key in ("execution_id", "execution_fingerprint", "source_identity")
    }
    lacking = sorted(key for key, value in evidence.items() if not value)
    if lacking:
        raise CaptureError(f"the run's result record lacks {', '.join(lacking)}, so the capture names no execution")
    if execution_id is not None:
        evidence["execution_id"] = execution_id
    return {
        "baseline_version": version,
        "source_commit": git_head(),
        "capture_platform": ADAPTER,
        "capture_date": _dt.date.today().isoformat(),
        **evidence,
    }


def manifest_execution_id(scenario: Any, version: str, adapter: Any) -> str:
    """The execution id an observed baseline at ``version`` names: the run's
    commit, scenario id, build key and ``version`` (``results.execution_id``).

    The runner made the capture run's own id with the version the baseline
    had before it (none for a new one), so it is not the id a run of the
    written baseline gets. Naming ``version`` instead makes the manifest's id
    the one every later run of the same commit and build key writes in its
    result record (AAP 0.4.6)."""
    hook = getattr(adapter, "build_key", None)
    build_key = hook(scenario) if callable(hook) else None
    commit = git_head()
    return _results.execution_id(ADAPTER, scenario, version, build_key, None if commit == "unknown" else commit)


def record_at_version(scenario: Any, adapter: Any, record: Any, version: str) -> Any:
    """``record`` carrying the execution id of its run as the baseline at
    ``version`` names it (``manifest_execution_id``).

    The gnucobol-batch execution id hashes the baseline version
    (``results.execution_id``, AAP 0.4.6), and the capture run is made before
    that version exists: with no baseline, or with the one it replaces. Its
    record's id would therefore match no later run of the same commit and
    build, so the manifest takes the id those runs carry at ``version``. A
    record without an id is returned unchanged, for ``build_manifest`` to
    refuse."""
    if not isinstance(record, Mapping) or not record.get("execution_id"):
        return record
    versioned = dict(record)
    versioned["execution_id"] = manifest_execution_id(scenario, version, adapter)
    return versioned


# --------------------------------------------------------------------------
# Capture
# --------------------------------------------------------------------------


def make_adapter(work_root: Path) -> Any:
    """The gnucobol-batch adapter, working under ``work_root``."""
    module = importlib.import_module(_ADAPTER_MODULE)
    adapter = getattr(module, _ADAPTER_CLASS)(work_root=work_root, repo_root=REPO_ROOT)
    if getattr(adapter, "name", None) != ADAPTER:
        raise CaptureError(f"{_ADAPTER_MODULE}.{_ADAPTER_CLASS} is not the {ADAPTER} adapter")
    return adapter


def select_all() -> list[str]:
    """Every id of the scenario id manifest whose scenario gnucobol-batch can
    run, sorted."""
    return sorted(
        {
            scenario.id
            for scenario in _scenarios.load_all()
            if scenario.id in _scenarios.ALL_IDS and ADAPTER in _scenarios.capable_adapters(scenario)
        }
    )


def capture(scenario_id: str, bump: str | None, adapter: Any) -> Captured:
    """Run ``scenario_id`` on ``adapter`` (gnucobol-batch) and write its
    observed baseline, as the module docstring describes. A refusal comes
    back as a ``Captured`` that is not ``written``; only an interrupt
    propagates."""
    try:
        return _capture(scenario_id, bump, adapter)
    except (HarnessError, OSError) as exc:
        detail = str(exc) if isinstance(exc, CaptureError) else f"{type(exc).__name__}: {exc}"
        return Captured(scenario_id, detail=detail)


def _capture(scenario_id: str, bump: str | None, adapter: Any) -> Captured:
    try:
        scenario = _scenarios.get(scenario_id)
    except KeyError as exc:
        raise CaptureError(str(exc.args[0]) if exc.args else f"no scenario {scenario_id}") from None
    capable = _scenarios.capable_adapters(scenario)
    if ADAPTER not in capable:
        raise CaptureError(
            f"{ADAPTER} cannot run it (it runs on {', '.join(capable) or 'no adapter'}); its baseline is derived "
            f"and only report.py --import-unikix promotes it"
        )
    outcome = _runner.run(scenario, adapter, compare=False)
    if outcome.status != _results.STATUS_PASSED:
        return _not_passed(outcome)
    result = outcome.result
    if result is None:
        raise CaptureError("the run passed without a result")
    expected = build_expected(scenario, result)
    datasets = raw_datasets(scenario, result)
    # The version is planned only once the run has passed, so --all without
    # --bump skips the post-open fault scenarios, whose derived baselines
    # exist, as implemented-unexecuted instead of refusing them for --bump.
    existing = _baselines.load(scenario.id, missing_ok=True)
    # What HEAD holds is learnt from the capture at a provisional version: the
    # version HEAD holds, or that it holds none, does not depend on it.
    provisional = build_manifest(
        _baselines.NEW_VERSION, record_at_version(scenario, adapter, outcome.record, _baselines.NEW_VERSION)
    )
    # The run's work root, which the runner created: the bump check's files stay inside it.
    work_root = Path(adapter.work_root)
    need = _head_need(_baselines.Baseline(scenario.id, expected, provisional, datasets), work_root)
    old_version, new_version = plan_version(need, bump, existing)
    manifest = build_manifest(new_version, record_at_version(scenario, adapter, outcome.record, new_version))
    candidate = _baselines.Baseline(scenario.id, expected, manifest, datasets)
    if need is not None:
        # The bump check judges the baseline as written, at its new version.
        need = _head_need(candidate, work_root)
        if plan_version(need, bump, existing) != (old_version, new_version):
            raise CaptureError("HEAD changed while the capture's version was planned; nothing is written")
    run_date = _dt.date.fromisoformat(_scenarios.clock(scenario)[:10])
    status, reason, diff = _baselines.compare(scenario, candidate, result, ADAPTER, run_date)
    if status != _results.STATUS_PASSED:
        raise CaptureError(
            f"the observed result does not compare equal to the would-be baseline ({reason}), "
            f"so nothing is written:\n{diff}"
        )
    _baselines.write(scenario.id, expected, manifest, datasets)
    return Captured(
        scenario.id,
        written=True,
        old_version=old_version,
        new_version=new_version,
        detail=_change_note(need, existing, expected, datasets, bump),
    )


def _not_passed(outcome: Any) -> Captured:
    """The refusal of a run that did not pass: nothing is written, so no raw
    artifact is fabricated for a run that never produced one."""
    verdict = f"{outcome.status}: {outcome.reason}"
    if outcome.gap_id:
        verdict += f" [{outcome.gap_id}]"
    lines = [f"the run ended {verdict}" + (f"; {outcome.detail}" if outcome.detail else "")]
    if outcome.diff:
        lines.append(outcome.diff)
    if outcome.workdir is not None:
        lines.append(f"working directory kept: {outcome.workdir}")
    return Captured(
        outcome.scenario_id,
        unexecuted=outcome.status == _results.STATUS_UNEXECUTED,
        detail="\n".join(lines),
        kept=outcome.workdir,
    )


def _change_note(
    need: Any, existing: Any, expected: Mapping[str, Any], datasets: Mapping[str, bytes], bump: str | None
) -> str:
    """What the written baseline changed: against HEAD as the bump check
    judges it (``need``, ``None`` when HEAD does not hold it), and against the
    working-tree baseline ``existing`` it replaced."""
    ignored = f"; --bump {bump} ignored" if bump else ""
    if need is None:
        if existing is None:
            return f"new baseline{ignored}" if bump else ""
        outcome = "unchanged" if existing.expected == expected else "changed"
        raw = "unchanged" if existing.datasets == dict(datasets) else "changed"
        return (
            f"replaced the {existing.capture_platform} baseline {existing.version}: "
            f"expected outcome {outcome}, raw datasets {raw}; HEAD does not hold it, so it stays a new baseline "
            f"at {_baselines.NEW_VERSION}{ignored}"
        )
    note = (
        f"against HEAD's {need.base_platform} baseline {need.base_version}: {need.reason}, "
        f"a {need.level} change (--bump {bump})"
    )
    if existing is not None and existing.version != need.base_version:
        note += f"; planned from HEAD's {need.base_version}, not the working tree's {existing.version}"
    return note


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------

_EPILOG = """\
examples (from the repository root):
  # replacing baselines HEAD holds (without --bump these exit 1):
  python tests/harness/capture.py TRN-CBTRN02C-negative-03 --bump patch
  python tests/harness/capture.py --all --bump minor
  # a baseline HEAD does not hold yet: written at 1.0.0
  python tests/harness/capture.py <scenario_id>

exit status: 0 when every requested baseline was written; 1 when any capture
was refused or failed (with --all, a scenario whose run ends
implemented-unexecuted is reported and skipped, not an error); 2 for a usage
error. Afterwards rerun the pytest command the summary names (pytest tests/
-k <id> for the written ids, pytest tests/ after --all) so that the result
records carry the new baseline versions and the reports are regenerated;
until then report.py counts them as stale, which fails --check (and
--check-proof for a job-chain proof scenario). Then python
tests/harness/baselines.py --check-bumps --base HEAD must pass.
"""


def _work_root(option: str | None) -> tuple[Path, bool]:
    """The resolved work root and whether this run created it as a temporary
    directory. ``--work-root`` and ``CARDDEMO_TEST_WORKDIR`` are taken after
    ``~`` expansion and used only when ``harness.usable_work_root`` accepts
    them: not blank, outside the repository, a resolved path holding no
    backquote, ``$``, ``"``, ``\\`` or character that is not printable and no
    longer than the work-root limit, created with mode 0700 when missing, a
    directory, the user's own and writable by neither group nor others, and
    readable, writable and searchable by the user, its owner (it opens as the
    runner opens it, and a directory can be made in it). ``CaptureError``
    when ``--work-root`` is unusable. An unusable
    ``CARDDEMO_TEST_WORKDIR`` is named on stderr with the reason and, like an
    unset one, replaced by a new temporary directory, which ``mkdtemp`` makes
    private; ``CaptureError``, once that directory is removed, when it lies
    inside the repository or its path fails the same path rule
    (``harness.check_work_root_path``)."""
    if option is not None:
        try:
            return usable_work_root(os.path.expanduser(option)), False
        except HarnessError as exc:
            raise CaptureError(f"--work-root {option!r} is not a usable work root: {exc}") from None
    configured = os.environ.get(_WORKDIR_ENV)
    if configured is not None:
        try:
            return usable_work_root(os.path.expanduser(configured)), False
        except HarnessError as exc:
            print(
                f"capture.py: warning: {_WORKDIR_ENV}={configured!r} is not a usable work root: {exc}; "
                "a new temporary work root is used instead",
                file=sys.stderr,
                flush=True,
            )
    resolved = Path(tempfile.mkdtemp(prefix=_WORK_ROOT_PREFIX)).resolve()
    repository = REPO_ROOT.resolve()
    if resolved == repository or repository in resolved.parents:
        shutil.rmtree(resolved, ignore_errors=True)
        raise CaptureError(f"the work root {resolved} lies inside the repository; it must lie outside it")
    try:
        check_work_root_path(resolved)
    except HarnessError as exc:
        shutil.rmtree(resolved, ignore_errors=True)
        raise CaptureError(f"the temporary work root cannot be used: {exc}") from None
    return resolved, True


def _discard(work_root: Path, created: bool, captures: Sequence[Captured]) -> None:
    """Remove a temporary work root this run created, unless a failing run
    kept its working directory there."""
    if not created:
        return
    if any(captured.kept is not None for captured in captures):
        print(f"capture.py: working files kept under {work_root}", file=sys.stderr)
        return
    try:
        shutil.rmtree(work_root)
    except OSError as exc:
        print(f"capture.py: the temporary work root {work_root} is not removed: {exc}", file=sys.stderr)


def _skipped(captured: Captured, all_mode: bool) -> bool:
    return not captured.written and all_mode and captured.unexecuted


def _report(captured: Captured, all_mode: bool) -> None:
    if captured.written:
        print(f"{captured.scenario_id} {captured.old_version or '(none)'} -> {captured.new_version}", flush=True)
        if captured.detail:
            print(f"capture.py: {captured.scenario_id}: {captured.detail}", file=sys.stderr, flush=True)
        return
    verb = "skipped" if _skipped(captured, all_mode) else "not captured"
    print(f"capture.py: {captured.scenario_id}: {verb}: {captured.detail}", file=sys.stderr, flush=True)


def _refresh_command(captures: Sequence[Captured], all_mode: bool) -> str:
    """The pytest run that rewrites the result records of the written
    baselines and regenerates the reports: the full suite after ``--all``,
    else a ``-k`` selection of the written scenario ids."""
    if all_mode:
        return "pytest tests/"
    ids = [captured.scenario_id for captured in captures if captured.written]
    return f"pytest tests/ -k {ids[0]}" if len(ids) == 1 else f'pytest tests/ -k "{" or ".join(ids)}"'


def _summarize(captures: Sequence[Captured], all_mode: bool) -> int:
    written = sum(1 for captured in captures if captured.written)
    skipped = sum(1 for captured in captures if _skipped(captured, all_mode))
    refused = len(captures) - written - skipped
    print(
        f"capture.py: {written} baseline(s) written, {skipped} skipped as implemented-unexecuted, "
        f"{refused} not captured",
        file=sys.stderr,
    )
    if written:
        print(
            f"capture.py: rerun {_refresh_command(captures, all_mode)} so that the result records carry the new "
            "baseline versions and the reports are regenerated; until then report.py counts them as stale, which "
            "fails --check (and --check-proof for a job-chain proof scenario)",
            file=sys.stderr,
        )
        print(
            "capture.py: python tests/harness/baselines.py --check-bumps --base HEAD must pass "
            "before the baselines are committed",
            file=sys.stderr,
        )
    return 1 if refused else 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="capture.py",
        description="Run scenarios on gnucobol-batch and write their observed baselines (AAP 0.4.5).",
        epilog=_EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("scenario_ids", nargs="*", metavar="SCENARIO_ID", help="the scenarios to capture")
    parser.add_argument(
        "--all",
        action="store_true",
        help="every scenario gnucobol-batch can run; those whose run ends implemented-unexecuted are skipped",
    )
    parser.add_argument(
        "--bump",
        choices=_baselines.BUMP_LEVELS,
        help=(
            "the level by which HEAD's version of a replaced baseline is raised, at least the level its change "
            "needs; ignored for a baseline HEAD does not hold, which starts at 1.0.0"
        ),
    )
    parser.add_argument(
        "--work-root",
        metavar="PATH",
        help=(
            f"the work root, outside the repository; an unusable one is a usage error "
            f"(default: ${_WORKDIR_ENV} when usable, else a new temporary directory)"
        ),
    )
    args = parser.parse_args(argv)
    if args.all and args.scenario_ids:
        parser.error("give scenario ids or --all, not both")
    if not args.all and not args.scenario_ids:
        parser.error("nothing to capture: give scenario ids or --all")
    unknown = sorted({scenario_id for scenario_id in args.scenario_ids if scenario_id not in _scenarios.ALL_IDS})
    if unknown:
        parser.error(f"not ids of the scenario id manifest: {', '.join(unknown)}")
    try:
        work_root, created = _work_root(args.work_root)
    except (CaptureError, OSError) as exc:
        parser.error(str(exc))
    # A configured root may be named by another capture or pytest session,
    # whose working directories a run would delete; a temporary one is this
    # run's alone.
    lock = None
    if not created:
        try:
            lock = lock_work_root(work_root)
        except HarnessError as exc:
            parser.error(str(exc))
    try:
        ids = select_all() if args.all else list(dict.fromkeys(args.scenario_ids))
        adapter = make_adapter(work_root)
    except (HarnessError, ImportError, AttributeError, OSError) as exc:
        print(f"capture.py: cannot set up the {ADAPTER} capture: {type(exc).__name__}: {exc}", file=sys.stderr)
        _discard(work_root, created, [])
        if lock is not None:
            lock.release()
        return 1
    captures: list[Captured] = []
    try:
        for scenario_id in ids:
            captured = capture(scenario_id, args.bump, adapter)
            captures.append(captured)
            _report(captured, args.all)
    finally:
        try:
            close = getattr(adapter, "close", None)
            if callable(close):
                close()
            _discard(work_root, created, captures)
        finally:
            if lock is not None:
                lock.release()
    return _summarize(captures, args.all)


if __name__ == "__main__":
    sys.exit(main())
