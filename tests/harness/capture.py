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
    scenario clock. The baseline then holds no clock-derived value that a run
    on another clock would contradict.
``datasets/<LOGICAL>.bin``
    The raw bytes of each business dataset those two sections name.
``manifest.yaml``
    ``baseline_version``, ``source_commit`` (HEAD), ``capture_platform:
    gnucobol-batch``, ``capture_date`` (today) and the run's
    ``execution_id``, ``execution_fingerprint`` and ``source_identity``.

Versions: a new baseline starts at ``1.0.0`` and ``--bump`` is ignored for
it; so is a baseline written since HEAD, which the bump check against HEAD
still treats as new. Replacing a baseline HEAD holds needs ``--bump`` at the
level of the change, as ``baselines.py --check-bumps`` judges it: MAJOR when
an expected value changes, MINOR when assertions or raw bytes are added,
PATCH when only the manifest changes. Replacing a baseline captured on
another platform (a derived one above all) changes the capture platform, so
it needs at least MINOR.

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
usage error. Every written baseline must then pass ``python
tests/harness/baselines.py --check-bumps --base HEAD``.

The work root is ``--work-root``, else ``CARDDEMO_TEST_WORKDIR``, else a new
``tempfile.mkdtemp(prefix="carddemo-")`` that is removed at the end unless a
failing run kept its working directory there; it never lies inside the
repository. Inside the repository the script writes only under
``tests/baselines/<id>/``, besides the git-ignored runtime files the runner
writes under ``tests/reports/runtime/``.
"""

from __future__ import annotations

import argparse
import copy
import datetime as _dt
import importlib
import os
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from harness import REPO_ROOT, HarnessError, git_head  # noqa: E402
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
    "committed_at_head",
    "main",
    "make_adapter",
    "plan_version",
    "raw_datasets",
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
_GIT_TIMEOUT_S = 60


class CaptureError(HarnessError):
    """Why a scenario's observed baseline is not written."""


@dataclass
class Captured:
    """How the capture of one scenario ended.

    ``written`` when its baseline was written at ``new_version``;
    ``old_version`` is the version it replaced (``None`` for a new
    baseline). Otherwise ``detail`` says why nothing was written and
    ``unexecuted`` whether the run ended ``implemented-unexecuted``. ``kept``
    is the working directory a failing run kept."""

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


def _matchers(value: Any) -> dict[str, Mapping[str, Any]]:
    """The field -> matcher entries of a ``matchers`` mapping."""
    if not isinstance(value, Mapping):
        return {}
    return {str(name): matcher for name, matcher in value.items() if _baselines.is_matcher(matcher)}


def _holds(matcher: Mapping[str, Any], observed: Any, clock: str) -> bool:
    return _baselines.check_matcher(matcher, observed, clock, True) is None


def _settled(matcher: Mapping[str, Any], observed: Any, clock: str, where: str) -> dict:
    """``matcher`` as the baseline holds it, once ``observed`` is checked to be
    exactly the one value it stands for under the fixed ``clock``."""
    problem = _baselines.check_matcher(matcher, observed, clock, True)
    if problem is not None:
        raise CaptureError(f"{where}: the observed value does not satisfy the scenario's matcher: {problem}")
    return dict(matcher)


def _same_literal(literal: Any, observed: Any) -> bool:
    """Whether ``observed`` (decoded text, ``{raw: <hex>}`` or a line) is the
    scenario literal ``literal`` as ``baselines.compare`` reads it: text
    exactly, trailing spaces included, and an integer as exactly its own
    text."""
    if isinstance(literal, bool) or literal is None:
        return False
    if isinstance(literal, int):
        try:
            return observed == str(literal)
        except ValueError:  # beyond Python's integer-to-text limit: no decoded field is that long
            return False
    return type(observed) is type(literal) and observed == literal


def _aligns(item: Any, record: Any, field_level: Mapping[str, Mapping[str, Any]], clock: str) -> bool:
    """Whether the observed ``record`` satisfies the scenario's ``contains``
    ``item``: every field the item names is a decoded field of the record
    and holds the item's literal, or the value of the matcher that applies
    (the item's own, else a field-level one, as ``baselines.compare``
    reads it)."""
    if _baselines.is_matcher(item):
        return _holds(item, record, clock)
    if isinstance(item, Mapping):
        if not isinstance(record, dict):
            return False
        for name, literal in item.items():
            if name not in record:
                return False
            spec = literal if _baselines.is_matcher(literal) else field_level.get(name, literal)
            if _baselines.is_matcher(spec):
                if not _holds(spec, record[name], clock):
                    return False
            elif not _same_literal(spec, record[name]):
                return False
        return True
    return isinstance(record, str) and _same_literal(item, record)


def _inline_matchers(item: Any) -> dict[str, Mapping[str, Any]]:
    """The matchers one scenario record gives: ``{"*": matcher}`` for a
    whole-line matcher, else field -> matcher."""
    if _baselines.is_matcher(item):
        return {_WHOLE_RECORD: item}
    if isinstance(item, Mapping):
        return {str(name): value for name, value in item.items() if _baselines.is_matcher(value)}
    return {}


def _record_matchers(
    spec: Any, observed: Sequence[Any], field_level: Mapping[str, Mapping[str, Any]], clock: str, where: str
) -> dict[int, dict[str, Mapping[str, Any]]]:
    """Observed record index -> the matchers the scenario's own records give
    it: by position for ``records``; for ``contains``, at the record each
    item is found at, the first one after the previous item's that it
    matches, which is how ``baselines.compare`` aligns them. A ``contains``
    item holding a matcher that matches no record cannot place it, so it is
    refused."""
    if isinstance(spec, list):
        records_spec, contains_spec = spec, None
    elif isinstance(spec, Mapping):
        records_spec, contains_spec = spec.get("records"), spec.get("contains")
    else:
        return {}
    assigned: dict[int, dict[str, Mapping[str, Any]]] = {}
    if isinstance(records_spec, list):
        for index, item in enumerate(records_spec[: len(observed)]):
            found = _inline_matchers(item)
            if found:
                assigned.setdefault(index, {}).update(found)
    if isinstance(contains_spec, list):
        position = 0
        for number, item in enumerate(contains_spec, start=1):
            found = _inline_matchers(item)
            index = next(
                (at for at in range(position, len(observed)) if _aligns(item, observed[at], field_level, clock)),
                None,
            )
            if index is None:
                if found:
                    raise CaptureError(
                        f"{where}: contains[{number}] matches no observed record after record {position}, "
                        f"so its matchers ({', '.join(sorted(found))}) cannot be placed"
                    )
                continue
            position = index + 1
            if found:
                assigned.setdefault(index, {}).update(found)
    return assigned


def _with_matchers(
    records: Sequence[Any], spec: Any, top: Mapping[str, Mapping[str, Any]], clock: str, where: str
) -> list:
    """``records`` (one decoded dataset or observation) with every field the
    scenario gives a matcher holding that matcher. A field takes the matcher
    of its own scenario record first, then the entry's ``matchers``, then the
    top-level ``matchers``: the precedence ``baselines.compare`` applies.
    Each replaced value is first checked against the matcher
    (``_settled``)."""
    section = _matchers(spec.get("matchers")) if isinstance(spec, Mapping) else {}
    field_level = {**top, **section}
    inline = _record_matchers(spec, records, field_level, clock, where)
    out: list = []
    for index, record in enumerate(records):
        own = inline.get(index, {})
        shown = f"{where} record {index + 1}"
        if isinstance(record, str):
            stray = sorted(name for name in own if name != _WHOLE_RECORD)
            if stray:
                raise CaptureError(
                    f"{shown}: is a line, so the scenario's matchers for {', '.join(stray)} name no field"
                )
            out.append(_settled(own[_WHOLE_RECORD], record, clock, shown) if _WHOLE_RECORD in own else record)
            continue
        if not isinstance(record, dict):
            raise CaptureError(f"{shown}: a decoded {type(record).__name__} is neither a field map nor a line")
        if _WHOLE_RECORD in own:
            raise CaptureError(f"{shown}: the scenario gives a whole-line matcher for a field-map record")
        unplaced = sorted(name for name in own if name not in record)
        if unplaced:
            raise CaptureError(
                f"{shown}: the scenario's matchers for {', '.join(unplaced)} name no decoded field of the record; "
                f"name each field as it decodes"
            )
        settled: dict[str, Any] = {}
        for name, value in record.items():
            matcher = own.get(name) or section.get(name) or top.get(name)
            settled[name] = value if matcher is None else _settled(matcher, value, clock, f"{shown} {name}")
        out.append(settled)
    if records:
        unused = sorted(name for name in section if not any(isinstance(r, dict) and name in r for r in records))
        if unused:
            raise CaptureError(
                f"{where}.matchers: {', '.join(unused)} names no decoded field of any record; "
                f"name each field as it decodes"
            )
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
    its matcher."""
    wanted = scenario.expected
    decoded = _baselines.decode_result(scenario, result)
    clock = _scenarios.clock(scenario)
    top = _matchers(wanted.get("matchers"))
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
        for name, spec in entries.items():
            records = observed.get(str(name))
            if not isinstance(records, list):
                raise CaptureError(f"{section}.{name}: {_clip(records)} was observed, not a list of records")
            expected[section][str(name)] = _with_matchers(records, spec, top, clock, f"{section}.{name}")
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
    ``@AFTER:<JOB>:<LOGICAL>``) have none."""
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


def committed_at_head(baseline_id: str) -> bool:
    """Whether HEAD holds the baseline's manifest, so that the bump check
    (``baselines.py --check-bumps --base HEAD``) judges a rewrite as a
    change of that baseline rather than as a new one. ``True`` when git
    cannot tell, which keeps ``--bump`` required."""
    if git_head() == "unknown":
        return True
    relative = f"{_baselines.BASELINES_REL}/{baseline_id}/{_baselines.MANIFEST_FILE}"
    try:
        completed = subprocess.run(
            ["git", "cat-file", "-e", f"HEAD:{relative}"],
            cwd=str(REPO_ROOT),
            capture_output=True,
            timeout=_GIT_TIMEOUT_S,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return True
    return completed.returncode == 0


def plan_version(existing: Any, bump: str | None, committed: bool = True) -> tuple[str | None, str]:
    """``(old version, new version)`` of the baseline about to be written over
    ``existing`` (a ``baselines.Baseline``, or ``None`` when there is none);
    ``committed`` says whether HEAD holds ``existing`` (``committed_at_head``).
    A new baseline starts at ``1.0.0`` whatever ``bump`` says, and so does one
    HEAD does not hold yet. Replacing a committed one needs ``bump``, at least
    ``minor`` when it was not observed on gnucobol-batch. ``CaptureError``
    otherwise."""
    if existing is None:
        return None, _baselines.NEW_VERSION
    # A baseline written since HEAD is still new to the bump check, which
    # requires 1.0.0 for it, so recapturing it before a commit keeps 1.0.0.
    if not committed:
        return existing.version, _baselines.NEW_VERSION
    platform = existing.capture_platform
    if bump is None:
        raise CaptureError(
            f"its {platform} baseline exists at {existing.version}; replacing it needs "
            f"--bump {'|'.join(_baselines.BUMP_LEVELS)}"
        )
    # Not only a derived baseline: replacing any baseline captured elsewhere
    # changes capture_platform, which the bump check rates MINOR at least.
    if platform != ADAPTER and bump == "patch":
        raise CaptureError(
            f"its {platform} baseline {existing.version} would become one observed on {ADAPTER}, a change of "
            f"capture platform that needs at least --bump minor (--bump major when the decoded outcome changes)"
        )
    return existing.version, _baselines.bump(existing.version, bump)


def build_manifest(version: str, record: Any) -> dict:
    """The ``manifest.yaml`` of an observed baseline at ``version``, naming the
    execution of the run's result ``record`` (``results.build_record``)."""
    evidence = {
        key: copy.deepcopy(record.get(key)) if isinstance(record, Mapping) else None
        for key in ("execution_id", "execution_fingerprint", "source_identity")
    }
    lacking = sorted(key for key, value in evidence.items() if not value)
    if lacking:
        raise CaptureError(f"the run's result record lacks {', '.join(lacking)}, so the capture names no execution")
    return {
        "baseline_version": version,
        "source_commit": git_head(),
        "capture_platform": ADAPTER,
        "capture_date": _dt.date.today().isoformat(),
        **evidence,
    }


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
    committed = existing is not None and committed_at_head(scenario.id)
    old_version, new_version = plan_version(existing, bump, committed)
    manifest = build_manifest(new_version, outcome.record)
    candidate = _baselines.Baseline(scenario.id, expected, manifest, datasets)
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
        detail=_change_note(existing, committed, expected, datasets, bump),
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
    existing: Any, committed: bool, expected: Mapping[str, Any], datasets: Mapping[str, bytes], bump: str | None
) -> str:
    """What the written baseline changed, to choose and check the bump by."""
    ignored = f"; --bump {bump} ignored" if bump else ""
    if existing is None:
        return f"new baseline{ignored}" if bump else ""
    outcome = "unchanged" if existing.expected == expected else "changed"
    raw = "unchanged" if existing.datasets == dict(datasets) else "changed"
    note = (
        f"replaced the {existing.capture_platform} baseline {existing.version}: "
        f"expected outcome {outcome}, raw datasets {raw}"
    )
    if not committed:
        note += f"; HEAD does not hold it, so it stays a new baseline at {_baselines.NEW_VERSION}{ignored}"
    return note


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------

_EPILOG = """\
examples (from the repository root):
  python tests/harness/capture.py TRN-CBTRN02C-negative-03
  python tests/harness/capture.py TRN-CBTRN02C-negative-03 --bump patch
  python tests/harness/capture.py --all
  python tests/harness/capture.py --all --bump minor

exit status: 0 when every requested baseline was written; 1 when any capture
was refused or failed (with --all, a scenario whose run ends
implemented-unexecuted is reported and skipped, not an error); 2 for a usage
error. Afterwards python tests/harness/baselines.py --check-bumps --base HEAD
must pass.
"""


def _work_root(option: str | None) -> tuple[Path, bool]:
    """The resolved work root and whether this run created it as a temporary
    directory. ``CaptureError`` when it lies inside the repository."""
    configured = option if option is not None else (os.environ.get(_WORKDIR_ENV, "").strip() or None)
    created = configured is None
    root = Path(tempfile.mkdtemp(prefix=_WORK_ROOT_PREFIX)) if created else Path(configured).expanduser()
    resolved = root.resolve()
    repository = REPO_ROOT.resolve()
    if resolved == repository or repository in resolved.parents:
        if created:
            shutil.rmtree(resolved, ignore_errors=True)
        raise CaptureError(f"the work root {resolved} lies inside the repository; it must lie outside it")
    resolved.mkdir(parents=True, exist_ok=True)
    return resolved, created


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
        help="the version level of a replaced baseline; ignored for a new one, which starts at 1.0.0",
    )
    parser.add_argument(
        "--work-root",
        metavar="PATH",
        help=f"the work root, outside the repository (default: ${_WORKDIR_ENV}, else a new temporary directory)",
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
    try:
        ids = select_all() if args.all else list(dict.fromkeys(args.scenario_ids))
        adapter = make_adapter(work_root)
    except (HarnessError, ImportError, AttributeError, OSError) as exc:
        print(f"capture.py: cannot set up the {ADAPTER} capture: {type(exc).__name__}: {exc}", file=sys.stderr)
        _discard(work_root, created, [])
        return 1
    captures: list[Captured] = []
    try:
        for scenario_id in ids:
            captured = capture(scenario_id, args.bump, adapter)
            captures.append(captured)
            _report(captured, args.all)
    finally:
        close = getattr(adapter, "close", None)
        if callable(close):
            close()
        _discard(work_root, created, captures)
    return _summarize(captures, args.all)


if __name__ == "__main__":
    sys.exit(main())
