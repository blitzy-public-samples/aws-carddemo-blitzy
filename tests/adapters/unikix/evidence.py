"""Immutable evidence of the ``unikix`` adapter's scenario runs.

UniKix scenarios never run in CI, so the evidence an operator run writes, and
later commits, is their only record. ``EvidenceWriter`` writes it to
``tests/reports/unikix/<execution_id>/<scenario_id>/``, the one place outside
the work root a ``unikix`` run writes to. It is raw data, excluded from the
deterministic projection: its execution id holds a UTC time by design, and its
files hold installation paths. ``harness/report.py --import-unikix`` reads it
and writes ``validation.yaml`` beside ``result.yaml``; this module never does.

Each scenario directory holds:

``datasets/<NAME>.bin``
    The bytes of each dataset and observation, unchanged. A name becomes a
    file name by replacing every character outside ``[A-Za-z0-9._-]`` with
    ``_`` (``@CATALOG`` -> ``_CATALOG.bin``, ``@AIX:<path>`` ->
    ``_AIX_<path>.bin``, ``@REGION-FILES`` -> ``_REGION-FILES.bin``,
    ``@TDQ:JOBS`` -> ``_TDQ_JOBS.bin``); ``result.yaml`` maps each file name
    back to its name under ``capture_summary.datasets``.
``sysout/<path>``
    BPE sysout, generated JCL, the scenario's command log and build-log
    excerpts, unchanged. ``capture_summary.sysout`` maps each path back to
    the name the adapter gave it.
``screens.txt``
    The screens for people to read: a ``--- step <n> session <s> @map <map>
    ---`` line per captured screen, then its rows with trailing spaces
    trimmed. It is the only trimmed copy and is never compared.
``environment.yaml``
    ``Capture.environment`` (installation and deployment paths, version
    probes, deployment substitutions, ``s3270`` and the listener address) and
    ``run_date``: ``bpe_job_start_date`` and ``first_step_screen_date``, the
    run date the relative-clock matchers are judged against (null when the
    scenario showed none), plus any other run-date facts the drivers record.
``result.yaml``
    Written last, so its presence means the evidence is complete. It holds
    every field of ``harness.results.RECORD_FIELDS`` from the record, with
    ``adapter: unikix``, the writer's ``execution_id`` and the
    ``evidence_path`` of this directory, and besides them:

    * ``observed``: ``return_code``, ``messages`` and ``screens`` of the
      ``adapters.base.Result`` the run produced (null when none was), which,
      with ``datasets/``, reconstructs that Result for import and
      revalidation. ``elapsed_s`` is left out.
    * ``observed_messages``: every ``ERRMSG`` and unformatted-screen
      ``@text`` value seen, in order, for the import's search for messages
      that match no inventory item (GAP-UNC-012).
    * ``screens_raw``: each captured screen with its rows exactly as read.
    * ``capture_summary``: ``datasets`` and ``sysout`` (file -> name),
      ``steps`` (the step, session, map and attempt of each captured screen)
      and ``job_numbers`` (the BPE job numbers).

Evidence is never overwritten or deleted: a scenario directory that already
holds anything is refused with ``HarnessError``, and every file is created
exclusively. Everything to be written is checked before the first file is
created, so a refused write leaves no file behind.
"""

from __future__ import annotations

import os
import re
import socket
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
from typing import Any

from adapters.base import Result
from harness import TESTS_ROOT, HarnessError, display_path, dump_yaml
from harness import results as _results

__all__ = [
    "DATASETS_DIR",
    "ENVIRONMENT_FILE",
    "EXECUTION_ID_RE",
    "RESULT_FILE",
    "SCREENS_FILE",
    "SYSOUT_DIR",
    "Capture",
    "EvidenceWriter",
    "dataset_file_name",
    "evidence_path",
    "evidence_root",
    "make_execution_id",
]

RESULT_FILE = "result.yaml"
SCREENS_FILE = "screens.txt"
ENVIRONMENT_FILE = "environment.yaml"
DATASETS_DIR = "datasets"
SYSOUT_DIR = "sysout"

# The form harness.results accepts for a unikix execution id.
EXECUTION_ID_RE = re.compile(r"unikix-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}")
# A scenario id as harness.results accepts it in an evidence path.
_SCENARIO_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_UNSAFE_CHAR_RE = re.compile(r"[^A-Za-z0-9._-]")
_HOSTNAME_DIGITS = 8

# The run-date facts environment.yaml always names (AAP 0.4.6, clock on unikix).
_RUN_DATE_KEYS = ("bpe_job_start_date", "first_step_screen_date")
# What capture_summary.steps keeps of each screens_raw entry.
_STEP_KEYS = ("attempt", "step", "session", "map")


# --------------------------------------------------------------------------
# Identifiers
# --------------------------------------------------------------------------


def make_execution_id(now: datetime | None = None, hostname: str | None = None) -> str:
    """``unikix-<UTC yyyymmddThhmmssZ>-<first 8 hex of sha256(hostname)>``.

    ``now`` defaults to the current UTC time and must be timezone-aware, since
    a naive time names no instant; ``hostname`` defaults to
    ``socket.gethostname()``. Pure: no file is read or written."""
    if now is None:
        now = datetime.now(timezone.utc)
    elif not isinstance(now, datetime):
        raise TypeError(f"now must be a datetime, got {type(now).__name__}")
    elif now.utcoffset() is None:
        raise ValueError(f"now must be timezone-aware, got the naive {now.isoformat()}")
    if hostname is None:
        hostname = socket.gethostname()
    elif not isinstance(hostname, str):
        raise TypeError(f"hostname must be a str, got {type(hostname).__name__}")
    utc = now.astimezone(timezone.utc)
    # Formatted field by field: strftime's %Y leaves years before 1000 unpadded.
    stamp = (
        f"{utc.year:04d}{utc.month:02d}{utc.day:02d}"
        f"T{utc.hour:02d}{utc.minute:02d}{utc.second:02d}Z"
    )
    digest = sha256(hostname.encode("utf-8")).hexdigest()[:_HOSTNAME_DIGITS]
    return f"unikix-{stamp}-{digest}"


def _path_component(value: Any, what: str) -> str:
    """``value`` when it can name one directory: a non-empty string without
    ``/``, ``\\``, ``..``, whitespace or control characters."""
    if not isinstance(value, str) or not value:
        raise ValueError(f"{what} must be a non-empty string, got {value!r}")
    if (
        "/" in value
        or "\\" in value
        or ".." in value
        or any(char.isspace() or not char.isprintable() for char in value)
    ):
        raise ValueError(
            f"{what} {value!r} cannot name a directory: it holds '/', '\\', '..', "
            f"whitespace or a control character"
        )
    return value


def _execution_id(value: Any) -> str:
    _path_component(value, "execution_id")
    if not EXECUTION_ID_RE.fullmatch(value):
        raise ValueError(
            f"execution_id {value!r} is not unikix-<yyyymmddThhmmssZ>-<8 hex> (make_execution_id)"
        )
    return value


def _scenario_id(value: Any) -> str:
    _path_component(value, "scenario_id")
    if not _SCENARIO_ID_RE.fullmatch(value):
        raise ValueError(f"scenario_id {value!r} is not a scenario id")
    return value


def _tests_root(tests_root: str | os.PathLike[str] | None) -> Path:
    return TESTS_ROOT if tests_root is None else Path(tests_root)


def evidence_root(tests_root: str | os.PathLike[str] | None, execution_id: str) -> Path:
    """``<tests_root>/reports/unikix/<execution_id>``; ``tests_root`` ``None``
    is the suite's own ``tests/`` folder (``harness.TESTS_ROOT``)."""
    return _tests_root(tests_root) / "reports" / "unikix" / _execution_id(execution_id)


def evidence_path(execution_id: str, scenario_id: str) -> str:
    """``tests/reports/unikix/<execution_id>/<scenario_id>``: the
    repository-relative POSIX ``evidence_path`` of the scenario's records."""
    scenario_id = _scenario_id(scenario_id)
    execution_id = _execution_id(execution_id)
    # One definition of the form, shared with validation.yaml and the matrix.
    return _results.evidence_path_for(_results.UNIKIX, scenario_id, execution_id)


def dataset_file_name(name: str) -> str:
    """The ``datasets/`` file name of a dataset or observation ``name``: every
    character outside ``[A-Za-z0-9._-]`` becomes ``_``, then ``.bin``."""
    if not isinstance(name, str) or not name:
        raise HarnessError(f"dataset names must be non-empty strings, got {name!r}")
    return _UNSAFE_CHAR_RE.sub("_", name) + ".bin"


def _sysout_path(name: Any) -> str:
    """The ``sysout/``-relative POSIX path of a sysout file ``name``. Empty
    and ``.`` parts are dropped, a part of dots only becomes underscores, and
    every other character outside ``[A-Za-z0-9._-]`` becomes ``_``, so no name
    reaches outside ``sysout/``."""
    if not isinstance(name, str) or not name:
        raise HarnessError(f"sysout names must be non-empty strings, got {name!r}")
    parts = []
    for part in name.replace("\\", "/").split("/"):
        if part in ("", "."):
            continue
        if set(part) == {"."}:
            part = "_" * len(part)
        parts.append(_UNSAFE_CHAR_RE.sub("_", part))
    if not parts:
        raise HarnessError(f"sysout name {name!r} names no file")
    return "/".join(parts)


# --------------------------------------------------------------------------
# Capture
# --------------------------------------------------------------------------


@dataclass
class Capture:
    """What the adapter observes of one scenario, filled during ``prepare``
    and ``execute`` and written by ``EvidenceWriter``.

    ``screens_raw``
        ``{step, session, map, rows, attempt?}`` per captured screen, ``rows``
        the 24 rows of 80 characters as read (``adapters.unikix.tpe``).
    ``datasets``
        Logical dataset or observation name (``ACCTDATA``, ``@CATALOG``,
        ``@AIX:<path>``, ``@REGION-FILES``, ``@TDQ:JOBS``) -> the exact bytes
        put in ``Result.datasets``.
    ``sysout``
        Relative file name -> bytes (text is written as UTF-8): BPE sysout,
        generated JCL, the scenario's command log, build-log excerpts.
    ``observed_messages``
        ``{step, session, field, value, attempt?}`` for every ``ERRMSG`` and
        unformatted-screen ``@text`` value seen, in order.
    ``environment``
        The installation and deployment facts ``environment.yaml`` records.
    ``run_date``
        ``bpe_job_start_date`` and ``first_step_screen_date``, plus any other
        run-date facts (``day_changes``).
    ``job_numbers``
        The BPE job number of each submitted job, in submission order.
    ``result``
        The ``adapters.base.Result`` that ``execute`` returned, which the
        adapter sets so the evidence can reconstruct it; ``None`` when the
        run produced none. Its datasets are written with ``datasets``."""

    screens_raw: list[dict[str, Any]] = field(default_factory=list)
    datasets: dict[str, bytes] = field(default_factory=dict)
    sysout: dict[str, bytes] = field(default_factory=dict)
    observed_messages: list[dict[str, Any]] = field(default_factory=list)
    environment: dict[str, Any] = field(default_factory=dict)
    run_date: dict[str, Any] = field(default_factory=dict)
    job_numbers: list[Any] = field(default_factory=list)
    # Not in the evidence plan's field list: the import has to rebuild the
    # Result (return code, messages, screens) to compare it, and the adapter's
    # write_evidence hook receives only the record.
    result: Result | None = None


# --------------------------------------------------------------------------
# Writer
# --------------------------------------------------------------------------


def _bytes(value: Any, what: str, *, text_ok: bool = False) -> bytes:
    if isinstance(value, (bytes, bytearray, memoryview)):
        return bytes(value)
    if text_ok and isinstance(value, str):
        try:
            return value.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise HarnessError(f"{what}: text holds {value[exc.start]!r}, which is not UTF-8") from None
    kinds = "bytes or str" if text_ok else "bytes"
    raise HarnessError(f"{what} must hold {kinds}, got {type(value).__name__}")


def _mapping(value: Any, what: str) -> Mapping:
    if not isinstance(value, Mapping):
        raise HarnessError(f"{what} must be a mapping, got {type(value).__name__}")
    return value


def _sequence(value: Any, what: str) -> Sequence:
    if isinstance(value, (str, bytes, bytearray)) or not isinstance(value, Sequence):
        raise HarnessError(f"{what} must be a list, got {type(value).__name__}")
    return value


def _make_dir(path: Path) -> None:
    """Create the folder ``path`` unless it exists; refuse a symbolic link or
    a file in its place, so no evidence is written outside its folder."""
    try:
        os.mkdir(path)
    except FileExistsError:
        pass
    except OSError as exc:
        raise HarnessError(
            f"cannot create the evidence folder {display_path(path)}: {exc.strerror or exc}"
        ) from None
    if path.is_symlink() or not path.is_dir():
        raise HarnessError(
            f"{display_path(path)} is not a folder of its own: evidence is never written "
            f"through a symbolic link or over a file"
        )


def _create(path: Path, data: bytes) -> None:
    """Write ``data`` to the new file ``path``; an existing file, or a link
    in its place, is never replaced."""
    try:
        with open(path, "xb") as handle:
            handle.write(data)
    except FileExistsError:
        raise HarnessError(f"evidence is immutable: {display_path(path)} already exists") from None
    except OSError as exc:
        raise HarnessError(f"cannot write evidence {display_path(path)}: {exc.strerror or exc}") from None


class EvidenceWriter:
    """Writes the evidence of each scenario of one execution under
    ``<tests_root>/reports/unikix/<execution_id>/``; ``tests_root`` ``None``
    is ``harness.TESTS_ROOT``. The writer holds no state between writes."""

    def __init__(self, tests_root: str | os.PathLike[str] | None, execution_id: str) -> None:
        self.tests_root = _tests_root(tests_root)
        self.execution_id = _execution_id(execution_id)
        self.root = evidence_root(self.tests_root, self.execution_id)

    def write(self, scenario: Any, record: Mapping[str, Any], capture: Capture) -> str:
        """Write the evidence of ``scenario`` (a scenario object or its id)
        from its result ``record`` and ``capture``, and return its
        repository-relative path ``tests/reports/unikix/<execution_id>/<id>``.

        ``HarnessError`` when the scenario's directory already holds anything
        (``result.yaml`` or an earlier, incomplete write), when the record
        names another scenario, adapter or execution or a field outside
        ``RECORD_FIELDS``, or when the capture holds something that cannot be
        written. Nothing is written in those cases."""
        scenario_id = _scenario_id(scenario if isinstance(scenario, str) else getattr(scenario, "id", None))
        if not isinstance(capture, Capture):
            raise TypeError(f"capture must be an evidence Capture, got {type(capture).__name__}")
        relative = evidence_path(self.execution_id, scenario_id)
        try:
            files = self._files(scenario_id, record, capture, relative)
        except HarnessError as exc:
            raise HarnessError(f"{scenario_id}: evidence not written: {exc}") from None

        for folder in (self.tests_root / "reports", self.tests_root / "reports" / "unikix", self.root):
            _make_dir(folder)
        directory = self.root / scenario_id
        _make_dir(directory)
        if os.path.lexists(directory / RESULT_FILE):
            raise HarnessError(
                f"evidence is immutable: {display_path(directory / RESULT_FILE)} already exists"
            )
        try:
            earlier = sorted(os.listdir(directory))
        except OSError as exc:
            raise HarnessError(f"cannot read {display_path(directory)}: {exc.strerror or exc}") from None
        if earlier:
            raise HarnessError(
                f"evidence is immutable: {display_path(directory)} already holds "
                f"{', '.join(earlier)} from an earlier, incomplete write; it is kept as found"
            )
        # Both folders exist in every evidence directory, empty or not.
        for folder_name in (DATASETS_DIR, SYSOUT_DIR):
            _make_dir(directory / folder_name)
        for relative_file, data in files:
            target = directory
            parts = relative_file.split("/")
            for part in parts[:-1]:
                target = target / part
                _make_dir(target)
            _create(target / parts[-1], data)
        return relative

    def _files(
        self, scenario_id: str, record: Mapping[str, Any], capture: Capture, relative: str
    ) -> list[tuple[str, bytes]]:
        """Every file of the scenario's evidence as ``(path in the scenario
        directory, bytes)``, in writing order, ``result.yaml`` last."""
        datasets = self._datasets(capture)
        sysout = self._sysout(capture)
        screens_raw, steps, screens_text = self._screens(capture.screens_raw)
        observed_messages = [
            dict(_mapping(item, f"observed_messages[{index}]"))
            for index, item in enumerate(_sequence(capture.observed_messages, "observed_messages"))
        ]
        job_numbers = list(_sequence(capture.job_numbers, "job_numbers"))

        environment = dict(_mapping(capture.environment, "environment"))
        if "run_date" in environment:
            raise HarnessError("environment holds its own run_date; the capture's run_date is written there")
        run_date: dict[Any, Any] = {key: None for key in _RUN_DATE_KEYS}
        run_date.update(_mapping(capture.run_date, "run_date"))
        environment["run_date"] = run_date

        result = self._record(scenario_id, record, relative)
        result.update(
            {
                "capture_summary": {
                    "datasets": {name: logical for name, (logical, _) in datasets.items()},
                    "job_numbers": job_numbers,
                    "steps": steps,
                    "sysout": {path: name for path, (name, _) in sysout.items()},
                },
                "observed": self._observed(capture.result),
                "observed_messages": observed_messages,
                "screens_raw": screens_raw,
            }
        )

        files = [(f"{DATASETS_DIR}/{name}", data) for name, (_, data) in sorted(datasets.items())]
        files += [(f"{SYSOUT_DIR}/{path}", data) for path, (_, data) in sorted(sysout.items())]
        files.append((SCREENS_FILE, _bytes(screens_text, SCREENS_FILE, text_ok=True)))
        files.append((ENVIRONMENT_FILE, _yaml(environment, ENVIRONMENT_FILE)))
        files.append((RESULT_FILE, _yaml(result, RESULT_FILE)))
        return files

    def _record(self, scenario_id: str, record: Any, relative: str) -> dict[str, Any]:
        """The ``RECORD_FIELDS`` of ``result.yaml``: the record's values, a
        missing field as null. ``adapter``, ``execution_id`` and
        ``scenario_id`` are this evidence's, and ``evidence_path`` its
        directory; ``source_identity`` and ``execution_fingerprint`` are kept
        as the record holds them."""
        record = _mapping(record, "the result record")
        unknown = sorted(str(name) for name in record if name not in _results.RECORD_FIELDS)
        if unknown:
            raise HarnessError(
                f"the result record holds {', '.join(unknown)}, outside RECORD_FIELDS "
                f"({', '.join(_results.RECORD_FIELDS)})"
            )
        fields = {name: record.get(name) for name in _results.RECORD_FIELDS}
        # A record built for another run would be filed under the wrong
        # evidence, so a contradicting value is refused rather than replaced.
        for name, own in (
            ("scenario_id", scenario_id),
            ("adapter", _results.UNIKIX),
            ("execution_id", self.execution_id),
        ):
            if fields[name] is not None and fields[name] != own:
                raise HarnessError(f"the result record names {name} {fields[name]!r}, this evidence is {own!r}")
            fields[name] = own
        fields["evidence_path"] = relative
        return fields

    @staticmethod
    def _observed(result: Any) -> dict[str, Any] | None:
        """What ``result.yaml`` keeps of the run's ``Result``: everything but
        the datasets, written to ``datasets/``, and ``elapsed_s``."""
        if result is None:
            return None
        if not isinstance(result, Result):
            raise HarnessError(f"capture.result must be an adapters.base.Result, got {type(result).__name__}")
        return {
            "return_code": result.return_code,
            "messages": list(result.messages),
            "screens": [dict(screen) for screen in result.screens],
        }

    @staticmethod
    def _datasets(capture: Capture) -> dict[str, tuple[str, bytes]]:
        """``datasets/`` file name -> (dataset or observation name, bytes),
        from the capture and its ``Result``, which must agree on every name
        both hold."""
        merged: dict[str, bytes] = {}
        for name, data in _mapping(capture.datasets, "datasets").items():
            dataset_file_name(name)
            merged[name] = _bytes(data, f"dataset {name}")
        if isinstance(capture.result, Result):
            for name, data in capture.result.datasets.items():
                data = _bytes(data, f"Result dataset {name}")
                if name in merged and merged[name] != data:
                    raise HarnessError(
                        f"dataset {name}: the capture and the Result hold different bytes "
                        f"({len(merged[name])} and {len(data)} bytes)"
                    )
                merged[name] = data
        files: dict[str, tuple[str, bytes]] = {}
        for name in sorted(merged):
            file_name = dataset_file_name(name)
            if file_name in files:
                raise HarnessError(
                    f"datasets {files[file_name][0]!r} and {name!r} would both be written as "
                    f"{DATASETS_DIR}/{file_name}"
                )
            files[file_name] = (name, merged[name])
        return files

    @staticmethod
    def _sysout(capture: Capture) -> dict[str, tuple[str, bytes]]:
        """``sysout/``-relative path -> (name the adapter gave, bytes)."""
        files: dict[str, tuple[str, bytes]] = {}
        for name, data in _mapping(capture.sysout, "sysout").items():
            path = _sysout_path(name)
            if path in files:
                raise HarnessError(
                    f"sysout files {files[path][0]!r} and {name!r} would both be written as {SYSOUT_DIR}/{path}"
                )
            files[path] = (name, _bytes(data, f"sysout {name}", text_ok=True))
        for path in files:
            parts = path.split("/")
            for depth in range(1, len(parts)):
                folder = "/".join(parts[:depth])
                if folder in files:
                    raise HarnessError(
                        f"sysout {files[folder][0]!r} would be written as {SYSOUT_DIR}/{folder}, the folder "
                        f"of sysout {files[path][0]!r}"
                    )
        return files

    @staticmethod
    def _screens(entries: Any) -> tuple[list[dict[Any, Any]], list[dict[str, Any]], str]:
        """``screens_raw`` as written, its ``capture_summary.steps`` and the
        text of ``screens.txt``."""
        raw: list[dict[Any, Any]] = []
        steps: list[dict[str, Any]] = []
        lines: list[str] = []
        for index, entry in enumerate(_sequence(entries, "screens_raw")):
            entry = _mapping(entry, f"screens_raw[{index}]")
            rows = _sequence(entry.get("rows"), f"screens_raw[{index}].rows")
            if not all(isinstance(row, str) for row in rows):
                raise HarnessError(f"screens_raw[{index}].rows must hold str rows")
            raw.append({**entry, "rows": list(rows)})
            steps.append({key: entry[key] for key in _STEP_KEYS if key in entry})
            header = f"--- step {entry.get('step')} session {entry.get('session')} @map {entry.get('map')}"
            if "attempt" in entry:
                header += f" attempt {entry['attempt']}"
            lines.append(header + " ---")
            # Trimmed for reading only: screens_raw in result.yaml keeps every space.
            lines.extend(row.rstrip(" ") for row in rows)
            lines.append("")
        text = "\n".join(lines) if lines else "(no screen was captured)\n"
        return raw, steps, text


def _yaml(value: Any, what: str) -> bytes:
    """``value`` as canonical YAML (``harness.dump_yaml``), UTF-8."""
    try:
        return dump_yaml(value).encode("utf-8")
    except HarnessError as exc:
        raise HarnessError(f"{what}: {exc}") from None
