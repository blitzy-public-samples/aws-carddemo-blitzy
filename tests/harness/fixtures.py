"""Fixture building, synthetic records and the EBCDIC cross-check.

``tests/fixtures/datasets.yaml`` defines every logical dataset. This module
turns a scenario's ``preconditions.datasets`` (and its parameter files) into
the exact bytes the adapters load, and cross-checks the repository's ASCII
data against the EBCDIC copies. It reads ``app/`` and writes nothing: the
runner writes the returned bytes into the scenario's working directory. A
source or cross-check path is read only when, symbolic links followed, it
leads to a regular file inside the repository; any other is refused unread.

Repository data (``repository_dataset``)
    ``ascii`` sources are split on ``\\n``; one trailing empty line is dropped,
    a trailing ``\\r`` is stripped, and each line is right-padded with spaces
    to the record length (``cardxref.txt`` lines are 36 characters, padded to
    50). A longer line raises ``FixtureError``. ``cp037`` sources are split at
    the record length, decoded and materialized as ASCII bytes (``x'00'``
    stays ``x'00'``), because both GnuCOBOL and the UniKix copy's
    ``seqfiles`` use ASCII. ``raw`` sources are taken as stored.

Cross-check (``cross_check``, ``verify``)
    Each dataset with a ``cross_check`` counterpart is compared with it record
    by record: a cp037 counterpart is split at the record length and decoded,
    an in-stream counterpart takes JCL data lines padded with spaces. A
    difference is registered when an entry of ``registered_mismatches`` names
    its record and its columns contain every differing column; any other
    difference makes ``verify`` raise ``FixtureMismatch``, which fails the
    scenario with reason ``error`` until a ``GAP-DOC`` entry registers it.
    A ``derive_from`` twin is verified through the dataset it derives from.

Building (``build``)
    For each ``preconditions.datasets.<LOGICAL>`` entry: the records of
    ``base`` (verified first), less those whose primary key text is listed in
    ``omit_keys``, plus the synthetic ``records``, each a decoded field map
    (or ``{"@raw": "<text>"}``) encoded through ``harness.codec``. In a keyed
    dataset a synthetic record whose key matches a base record replaces it,
    with the fields it does not name kept from the base record; any other
    synthetic record is added, and the dataset is ordered by primary-key bytes.
    In a sequential dataset synthetic records follow the base records in
    order. ``inputs.param_files`` and ``preconditions.parameters.DATEPARM``
    become datasets through the same assembly (``param_datasets``). Faults are
    not applied here: every named dataset is built, including one a fault
    targets, and the adapter decides what the fault does with it.

Every function is deterministic: the same scenario and checkout always give
the same bytes.
"""

from __future__ import annotations

import functools
import re
import stat
import sys
import threading
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters.base import GAP_ID_RE, MissingPrerequisite  # noqa: E402
from harness import FIXTURES_FILE, REPO_ROOT, HarnessError, load_yaml, sha256_hex  # noqa: E402
from harness import codec as _codec  # noqa: E402
from harness import layouts as _layouts  # noqa: E402
from harness.scenarios import Scenario  # noqa: E402

__all__ = [
    "DATEPARM",
    "ENCODINGS",
    "FIXTURES_FILE",
    "ORGANIZATIONS",
    "REPO_ROOT",
    "AlternateKey",
    "CrossCheck",
    "DatasetField",
    "DatasetSpec",
    "FixtureError",
    "FixtureMismatch",
    "KeySpec",
    "Mismatch",
    "RegisteredMismatch",
    "build",
    "cross_check",
    "materialized_digest",
    "param_datasets",
    "repository_dataset",
    "spec",
    "specs",
    "verify",
]

# Source encodings: ascii (text lines), cp037 (fixed EBCDIC records), raw
# (fixed records taken as stored).
ENCODINGS = ("ascii", "cp037", "raw")
ORGANIZATIONS = ("ksds", "sequential")

# The logical dataset, and the preconditions.parameters key, of the CBTRN03C
# date window record.
DATEPARM = "DATEPARM"

# Encoding of every fixture byte string: the ASCII data of GnuCOBOL files and
# of the UniKix copy's seqfiles (harness.codec decodes it as latin-1).
_FIXTURE_ENCODING = "ascii"

_NAME_RE = re.compile(r"^[A-Z][A-Z0-9-]*$")
_SPAN_RE = re.compile(r"^\s*([0-9]+)\s*(?:-\s*([0-9]+)\s*)?$")
_SLASH_KEY_RE = re.compile(r"^\s*([0-9]+)\s*/\s*([0-9]+)\s*$")
_SUBSCRIPT_RE = re.compile(r"\(\d+(?:,\d+)*\)$")
_INSTREAM_START_RE = re.compile(r"^//\S*\s+DD\s+(?:\*|DATA)(?:\s|,|$)")
_NO_SOURCE = frozenset({"", "built", "none", "null"})
_ENTRY_KEYS = frozenset({"base", "records", "omit_keys"})


class FixtureError(HarnessError):
    """A dataset definition, repository file or precondition that cannot be
    turned into fixture bytes. ``dataset`` and ``record`` (1-based record or
    line number) locate the problem where they are known."""

    def __init__(self, message: str, dataset: str | None = None, record: int | None = None) -> None:
        self.dataset = dataset
        self.record = record
        super().__init__(message)


class FixtureMismatch(FixtureError):
    """A repository record differs from its cross-check counterpart where no
    registered mismatch accounts for it. The runner reports the scenario
    ``failing`` with reason ``error``; it can pass only once a ``GAP-DOC``
    entry registers the difference in ``tests/inventory/gaps.yaml`` and in the
    dataset's ``registered_mismatches``."""

    def __init__(
        self,
        dataset: str,
        record: int,
        columns: tuple[int, int],
        detail: str = "",
        mismatches: Iterable["Mismatch"] = (),
    ) -> None:
        self.columns = (int(columns[0]), int(columns[1]))
        self.mismatches = tuple(mismatches)
        message = (
            f"{dataset} record {record} columns {self.columns[0]}-{self.columns[1]} differ from "
            f"the cross-check counterpart and no registered mismatch covers them"
        )
        if detail:
            message += f": {detail}"
        others = len(self.mismatches) - 1
        if others > 0:
            message += f" ({others} more unregistered difference{'s' if others > 1 else ''})"
        message += (
            "; register a GAP-DOC entry in tests/inventory/gaps.yaml and in the dataset's "
            "registered_mismatches in tests/fixtures/datasets.yaml"
        )
        super().__init__(message, dataset, record)


# --------------------------------------------------------------------------
# Dataset definitions
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class KeySpec:
    """A key as 0-based byte offset and length within the record."""

    offset: int
    length: int

    def of(self, record: bytes) -> bytes:
        return bytes(record[self.offset : self.offset + self.length])


@dataclass(frozen=True)
class AlternateKey:
    """An alternate index of a keyed dataset; ``path`` is the region file or
    ``@AIX:<path>`` name of its path."""

    offset: int
    length: int
    duplicates: bool
    name: str | None = None
    path: str | None = None


@dataclass(frozen=True)
class CrossCheck:
    """The counterpart a dataset's repository records are compared with.
    ``form`` is ``records`` (fixed records, or text lines for ascii) or
    ``instream`` (JCL in-stream data lines); ``lines`` is the 1-based
    inclusive line range of the in-stream data, or ``None`` to take the
    first in-stream block of the member."""

    path: str
    encoding: str
    form: str
    lines: tuple[int, int] | None = None


@dataclass(frozen=True)
class RegisteredMismatch:
    """A known difference: 1-based record number and 1-based inclusive
    column range, with the gap that registers it."""

    record: int
    columns: tuple[int, int]
    gap_id: str

    def covers(self, record: int, columns: tuple[int, int]) -> bool:
        return (
            record == self.record
            and self.columns[0] <= columns[0]
            and columns[1] <= self.columns[1]
        )


@dataclass(frozen=True)
class DatasetField:
    """A named piece of the dataset's records beyond its layout (the inner
    structure datasets.yaml gives line data such as DATEPARM and DTCIN)."""

    name: str
    offset: int
    length: int
    type: str
    layout: str | None = None
    digits: int | None = None
    scale: int = 0

    def descriptor(self) -> dict[str, Any]:
        """The ``harness.codec`` field descriptor of this field."""
        digits = self.digits
        if digits is None and self.type in ("zoned", "zoned-signed"):
            digits = self.length
        elif digits is None and self.type == "comp-3":
            digits = self.length * 2 - 1
        return {
            "name": self.name,
            "type": self.type,
            "length": self.length,
            "digits": digits,
            "scale": self.scale,
            "signed": self.type == "zoned-signed",
        }


@dataclass(frozen=True)
class DatasetSpec:
    """One logical dataset of ``tests/fixtures/datasets.yaml``. ``source`` is
    the repository-relative data file, or ``None`` for a dataset that
    scenarios, programs or jobs build."""

    name: str
    source: str | None
    encoding: str
    record_length: int
    organization: str
    key: KeySpec | None
    alternate_keys: tuple[AlternateKey, ...]
    layout: str | None
    fields: tuple[DatasetField, ...]
    cross_check: CrossCheck | None
    registered_mismatches: tuple[RegisteredMismatch, ...]
    record_count: int | None
    derive_from: str | None
    data_items: tuple[str, ...]
    inventory_ids: tuple[str, ...]
    region_files: tuple[str, ...]
    gap_ids: tuple[str, ...]
    text_lines: bool

    @property
    def keyed(self) -> bool:
        return self.organization == "ksds"

    @property
    def repository_backed(self) -> bool:
        return self.source is not None

    @property
    def owner(self) -> str:
        """The dataset whose cross-check verifies this one's records."""
        return self.derive_from or self.name

    @property
    def missing_input_gap(self) -> str | None:
        """The first ``GAP-MIS`` id the entry declares: the gap a missing
        source of this dataset is reported under."""
        return next((gap for gap in self.gap_ids if gap.startswith("GAP-MIS-")), None)

    def key_of(self, record: bytes) -> bytes:
        if self.key is None:
            raise FixtureError(f"{self.name} has no primary key", self.name)
        return self.key.of(record)

    def key_text(self, record: bytes) -> str:
        """The primary key as text, the form ``omit_keys`` lists."""
        return self.key_of(record).decode(_codec.ENCODINGS[_FIXTURE_ENCODING])


@dataclass(frozen=True)
class Mismatch:
    """One record that differs from its cross-check counterpart: the dataset,
    the 1-based record number, the 1-based inclusive span of differing
    columns, and the registered gap that accounts for it (``None`` when none
    does). ``fixture_text`` and ``counterpart_text`` show the differing span
    for messages and take no part in comparisons."""

    dataset: str
    record: int
    columns: tuple[int, int]
    gap_id: str | None
    fixture_text: str = field(default="", compare=False, repr=False)
    counterpart_text: str = field(default="", compare=False, repr=False)

    @property
    def registered(self) -> bool:
        return self.gap_id is not None

    def describe(self) -> str:
        return f"{self.fixture_text!r} in the fixture, {self.counterpart_text!r} in the counterpart"


# --------------------------------------------------------------------------
# Parsing tests/fixtures/datasets.yaml
# --------------------------------------------------------------------------


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _int(value: Any, what: str, minimum: int = 0) -> int:
    if not _is_int(value) or value < minimum:
        raise FixtureError(f"{what} must be an integer of at least {minimum}, got {value!r}")
    return value


def _strings(value: Any, what: str) -> tuple[str, ...]:
    if value is None:
        return ()
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise FixtureError(f"{what} must be a list of strings, got {value!r}")
    return tuple(value)


def _relative_path(value: Any, what: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise FixtureError(f"{what} must be a repository-relative path, got {value!r}")
    path = PurePosixPath(value.strip())
    if path.is_absolute() or ".." in path.parts:
        raise FixtureError(
            f"{what} {value!r} must be relative to the repository root and stay inside it"
        )
    return path.as_posix()


def _span(value: Any, what: str) -> tuple[int, int]:
    """A 1-based inclusive range written ``"a-b"``, ``"a"``, ``[a, b]``,
    ``{from: a, to: b}`` or a single integer."""
    if _is_int(value):
        first = last = value
    elif isinstance(value, str) and _SPAN_RE.match(value):
        match = _SPAN_RE.match(value)
        first = int(match.group(1))
        last = int(match.group(2) or match.group(1))
    elif isinstance(value, (list, tuple)) and len(value) == 2 and all(map(_is_int, value)):
        first, last = value
    elif isinstance(value, Mapping) and _is_int(value.get("from")) and _is_int(value.get("to")):
        first, last = value["from"], value["to"]
    else:
        raise FixtureError(f'{what} must be a 1-based range such as "103-112", got {value!r}')
    if not 1 <= first <= last:
        raise FixtureError(f"{what} {value!r} is not a 1-based range with first <= last")
    return first, last


def _offset_length(value: Any, what: str, record_length: int) -> tuple[int, int]:
    if isinstance(value, str) and _SLASH_KEY_RE.match(value):
        match = _SLASH_KEY_RE.match(value)
        offset, length = int(match.group(1)), int(match.group(2))
    elif isinstance(value, Mapping):
        offset = _int(value.get("offset"), f"{what}.offset")
        length = _int(value.get("length"), f"{what}.length", 1)
    else:
        raise FixtureError(
            f'{what} must be "<offset>/<length>" or {{offset, length}}, got {value!r}'
        )
    if length < 1 or offset + length > record_length:
        raise FixtureError(
            f"{what} {offset}/{length} does not lie within the {record_length}-byte record"
        )
    return offset, length


def _key(value: Any, what: str, record_length: int) -> KeySpec | None:
    if value is None or (isinstance(value, str) and value.strip().lower() in _NO_SOURCE):
        return None
    return KeySpec(*_offset_length(value, what, record_length))


def _alternate_keys(value: Any, what: str, record_length: int) -> tuple[AlternateKey, ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        raise FixtureError(f"{what} must be a list, got {value!r}")
    keys = []
    for index, item in enumerate(value):
        where = f"{what}[{index}]"
        offset, length = _offset_length(item, where, record_length)
        if isinstance(item, Mapping):
            duplicates = item.get("duplicates", False)
            name, path = item.get("name"), item.get("path")
            if not isinstance(duplicates, bool):
                raise FixtureError(f"{where}.duplicates must be true or false, got {duplicates!r}")
            for label, text in (("name", name), ("path", path)):
                if text is not None and not isinstance(text, str):
                    raise FixtureError(f"{where}.{label} must be a string, got {text!r}")
            keys.append(AlternateKey(offset, length, duplicates, name, path))
        else:
            keys.append(AlternateKey(offset, length, False))
    return tuple(keys)


def _source(value: Any, what: str) -> str | None:
    if value is None or (isinstance(value, str) and value.strip().lower() in _NO_SOURCE):
        return None
    return _relative_path(value, what)


def _encoding(entry: Mapping[str, Any], source: str | None, what: str) -> str:
    encoding = entry.get("encoding", entry.get("source_encoding"))
    if encoding is None:
        encoding = "cp037" if source and source.startswith("app/data/EBCDIC/") else "ascii"
    if encoding not in ENCODINGS:
        raise FixtureError(f"{what} {encoding!r} is not one of {', '.join(ENCODINGS)}")
    return encoding


def _cross_check(value: Any, what: str) -> CrossCheck | None:
    if value is None or (isinstance(value, str) and value.strip().lower() in _NO_SOURCE):
        return None
    if not isinstance(value, Mapping):
        raise FixtureError(
            f"{what} must be a mapping {{source, encoding, form?, lines?}} or none, got {value!r}"
        )
    path = _relative_path(value.get("source", value.get("path")), f"{what}.source")
    encoding = value.get("encoding", "cp037")
    form = value.get("form")
    if encoding == "jcl-instream":
        encoding, form = "ascii", form or "instream"
    if encoding not in ENCODINGS:
        raise FixtureError(
            f"{what}.encoding {encoding!r} is not one of {', '.join(ENCODINGS)} or jcl-instream"
        )
    form = form or "records"
    if form not in ("records", "instream"):
        raise FixtureError(f"{what}.form {form!r} is not records or instream")
    if form == "instream" and encoding != "ascii":
        raise FixtureError(f"{what}: in-stream JCL data is text, so its encoding is ascii")
    lines = value.get("lines")
    span = _span(lines, f"{what}.lines") if lines is not None else None
    if span is not None and form != "instream":
        raise FixtureError(f"{what}.lines applies only to the instream form")
    return CrossCheck(path, encoding, form, span)


def _registered_mismatches(
    value: Any, what: str, record_length: int
) -> tuple[RegisteredMismatch, ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        raise FixtureError(f"{what} must be a list, got {value!r}")
    entries = []
    for index, item in enumerate(value):
        where = f"{what}[{index}]"
        if not isinstance(item, Mapping):
            raise FixtureError(
                f"{where} must be a mapping {{record, columns, gap_id}}, got {item!r}"
            )
        record = _int(item.get("record"), f"{where}.record", 1)
        columns = _span(item.get("columns"), f"{where}.columns")
        if columns[1] > record_length:
            raise FixtureError(
                f"{where}.columns {columns} lie beyond the {record_length}-byte record"
            )
        gap_id = item.get("gap_id")
        if not isinstance(gap_id, str) or not GAP_ID_RE.fullmatch(gap_id):
            raise FixtureError(
                f"{where}.gap_id must be a gap id such as GAP-DOC-012, got {gap_id!r}"
            )
        entries.append(RegisteredMismatch(record, columns, gap_id))
    return tuple(entries)


def _fields(value: Any, what: str, record_length: int) -> tuple[DatasetField, ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        raise FixtureError(f"{what} must be a list, got {value!r}")
    fields = []
    names: set[str] = set()
    for index, item in enumerate(value):
        where = f"{what}[{index}]"
        if not isinstance(item, Mapping) or not isinstance(item.get("name"), str):
            raise FixtureError(f"{where} must be a mapping with a name, got {item!r}")
        name = item["name"]
        if name in names:
            raise FixtureError(f"{where}: field {name} is defined twice")
        names.add(name)
        offset, length = _offset_length(item, where, record_length)
        layout = item.get("layout")
        if layout is not None and not isinstance(layout, str):
            raise FixtureError(f"{where}.layout must be a layout name, got {layout!r}")
        type_ = item.get("type", "group" if layout else "alphanumeric")
        if type_ not in _codec.TYPES:
            raise FixtureError(
                f"{where}.type {type_!r} is not one of {', '.join(sorted(_codec.TYPES))}"
            )
        digits = item.get("digits")
        if digits is not None:
            digits = _int(digits, f"{where}.digits", 1)
        scale = item.get("scale")
        scale = 0 if scale is None else scale
        if not _is_int(scale):
            raise FixtureError(f"{where}.scale must be an integer, got {scale!r}")
        fields.append(DatasetField(name, offset, length, type_, layout, digits, scale))
    return tuple(fields)


def _parse_entry(name: str, entry: Mapping[str, Any]) -> DatasetSpec:
    what = f"tests/fixtures/datasets.yaml {name}"
    if not _NAME_RE.fullmatch(name):
        raise FixtureError(f"{what}: logical names match {_NAME_RE.pattern}", name)
    record_length = _int(entry.get("record_length"), f"{what}.record_length", 1)
    source = _source(entry.get("source"), f"{what}.source")
    organization = str(entry.get("organization", "sequential")).lower()
    if organization == "indexed":
        organization = "ksds"
    if organization not in ORGANIZATIONS:
        raise FixtureError(
            f"{what}.organization {organization!r} is not one of {', '.join(ORGANIZATIONS)}", name
        )
    key = _key(entry.get("key"), f"{what}.key", record_length)
    if organization == "ksds" and key is None:
        raise FixtureError(f"{what}: a ksds dataset needs a primary key", name)
    layout = entry.get("layout")
    if layout is not None and not isinstance(layout, str):
        raise FixtureError(f"{what}.layout must be a layout name, got {layout!r}", name)
    record_count = entry.get("record_count")
    if record_count is not None:
        record_count = _int(record_count, f"{what}.record_count")
    derive_from = entry.get("derive_from")
    if derive_from is not None and not isinstance(derive_from, str):
        raise FixtureError(
            f"{what}.derive_from must be a logical dataset name, got {derive_from!r}", name
        )
    gap_ids = _strings(entry.get("gap_ids"), f"{what}.gap_ids")
    for gap_id in gap_ids:
        if not GAP_ID_RE.fullmatch(gap_id):
            raise FixtureError(f"{what}.gap_ids: {gap_id!r} is not a gap id", name)
    text_lines = entry.get("text_lines", False)
    if not isinstance(text_lines, bool):
        raise FixtureError(f"{what}.text_lines must be true or false, got {text_lines!r}", name)
    return DatasetSpec(
        name=name,
        source=source,
        encoding=_encoding(entry, source, f"{what}.encoding"),
        record_length=record_length,
        organization=organization,
        key=key,
        alternate_keys=_alternate_keys(
            entry.get("alternate_keys"), f"{what}.alternate_keys", record_length
        ),
        layout=layout,
        fields=_fields(entry.get("fields"), f"{what}.fields", record_length),
        cross_check=_cross_check(entry.get("cross_check"), f"{what}.cross_check"),
        registered_mismatches=_registered_mismatches(
            entry.get("registered_mismatches"), f"{what}.registered_mismatches", record_length
        ),
        record_count=record_count,
        derive_from=derive_from,
        data_items=_strings(entry.get("data_items"), f"{what}.data_items"),
        inventory_ids=_strings(entry.get("inventory_ids"), f"{what}.inventory_ids"),
        region_files=_strings(entry.get("region_files"), f"{what}.region_files"),
        gap_ids=gap_ids,
        text_lines=text_lines,
    )


def _parse_document(document: Any, path: Path) -> dict[str, DatasetSpec]:
    if not isinstance(document, Mapping) or "datasets" not in document:
        raise FixtureError(f"{path} holds no top-level datasets section")
    section = document["datasets"]
    if isinstance(section, Mapping):
        named = [(str(name), entry) for name, entry in section.items()]
    elif isinstance(section, list):
        named = []
        for index, entry in enumerate(section):
            if not isinstance(entry, Mapping) or not isinstance(entry.get("name"), str):
                raise FixtureError(f"{path}: datasets[{index}] must be a mapping with a name")
            named.append((entry["name"], entry))
    else:
        raise FixtureError(
            f"{path}: datasets must be a list of entries or a mapping of logical names"
        )
    parsed: dict[str, DatasetSpec] = {}
    for name, entry in named:
        if not isinstance(entry, Mapping):
            raise FixtureError(f"{path}: the entry of {name} must be a mapping", name)
        if name in parsed:
            raise FixtureError(f"{path}: logical dataset {name} is defined twice", name)
        parsed[name] = _parse_entry(name, entry)
    for entry in parsed.values():
        parent = parsed.get(entry.derive_from) if entry.derive_from else entry
        if parent is None:
            raise FixtureError(
                f"{path}: {entry.name} derives from {entry.derive_from}, which is not defined",
                entry.name,
            )
        if parent is not entry and parent.derive_from:
            raise FixtureError(
                f"{path}: {entry.name} derives from {parent.name}, which is itself a twin",
                entry.name,
            )
        # A twin is verified through its parent's cross-check, which only
        # holds while both read the same records the same way.
        if parent is not entry and (
            (entry.source, entry.encoding, entry.record_length)
            != (parent.source, parent.encoding, parent.record_length)
        ):
            raise FixtureError(
                f"{path}: {entry.name} must repeat the source, encoding and record_length of "
                f"{parent.name}, which it derives from",
                entry.name,
            )
    return dict(sorted(parsed.items()))


_SPEC_LOCK = threading.Lock()
_SPEC_CACHE: dict[str, tuple[tuple[int, int], dict[str, DatasetSpec]]] = {}


def _load_specs(path: Path = FIXTURES_FILE) -> dict[str, DatasetSpec]:
    # Re-read when the file changes, so an edited definition is seen within a
    # session; callers share the returned mapping and must not modify it.
    try:
        status = path.stat()
    except FileNotFoundError:
        raise FixtureError(f"{path} is missing: the logical datasets are defined there") from None
    stamp = (status.st_mtime_ns, status.st_size)
    with _SPEC_LOCK:
        hit = _SPEC_CACHE.get(str(path))
    if hit is not None and hit[0] == stamp:
        return hit[1]
    parsed = _parse_document(load_yaml(path), path)
    with _SPEC_LOCK:
        _SPEC_CACHE[str(path)] = (stamp, parsed)
    return parsed


def specs() -> dict[str, DatasetSpec]:
    """Every logical dataset of ``tests/fixtures/datasets.yaml``, by name."""
    return dict(_load_specs())


def spec(logical: str) -> DatasetSpec:
    """The definition of one logical dataset; an unknown name raises
    ``FixtureError``."""
    definitions = _load_specs()
    if not isinstance(logical, str) or logical not in definitions:
        raise FixtureError(
            f"{logical!r} is not a logical dataset of tests/fixtures/datasets.yaml; "
            f"known: {', '.join(definitions)}",
            logical if isinstance(logical, str) else None,
        )
    return definitions[logical]


# --------------------------------------------------------------------------
# Repository data
# --------------------------------------------------------------------------


def _root(repo_root: str | Path) -> str:
    return str(Path(repo_root).resolve())


def _read_repository_file(definition: DatasetSpec, relative: str, root: str, purpose: str) -> bytes:
    """The bytes of a repository file the dataset needs. ``root`` is the
    resolved repository root. The path is read only when, symbolic links
    followed, it leads to a regular file inside the repository: one that
    leads out of it, or to a directory, device or FIFO, is refused unread.
    An absent file is a missing prerequisite under the dataset's ``GAP-MIS``
    entry; without one it is an error in the checkout or in the definition.
    Messages name only the repository-relative path, never where it leads."""
    what = f"{definition.name}: its {purpose} {relative}"
    try:
        resolved = (Path(root) / relative).resolve()
        if not resolved.is_relative_to(root):
            raise FixtureError(
                f"{what} resolves outside the repository through a symbolic link, so it is not read",
                definition.name,
            )
        if not stat.S_ISREG(resolved.stat().st_mode):
            raise FixtureError(f"{what} is not a regular file, so it is not read", definition.name)
        return resolved.read_bytes()
    except FileNotFoundError:
        need = f"{what} is absent from the checkout"
        gap_id = definition.missing_input_gap
        if gap_id is not None:
            raise MissingPrerequisite(need, gap_id) from None
        raise FixtureError(
            f"{need}, and the dataset declares no GAP-MIS entry", definition.name
        ) from None
    except RuntimeError:
        # Path.resolve reports a symbolic link loop as RuntimeError on Python 3.11.
        raise FixtureError(f"{what} is a symbolic link loop, so it is not read", definition.name) from None
    except ValueError as exc:
        # A path holding a NUL byte cannot name a file.
        raise FixtureError(f"{what} is not a usable path ({exc}), so it is not read", definition.name) from None
    except OSError as exc:
        raise FixtureError(
            f"{definition.name}: cannot read its {purpose} {relative}: "
            f"{exc.strerror or type(exc).__name__}",
            definition.name,
        ) from None


def _text_lines(data: bytes) -> list[bytes]:
    lines = data.split(b"\n")
    if lines and lines[-1] == b"":
        lines.pop()
    return [line[:-1] if line.endswith(b"\r") else line for line in lines]


def _pad_lines(lines: Iterable[bytes], record_length: int, what: str, dataset: str) -> list[bytes]:
    records = []
    for number, line in enumerate(lines, 1):
        if len(line) > record_length:
            raise FixtureError(
                f"{what}: line {number} is {len(line)} bytes, longer than the "
                f"{record_length}-byte record",
                dataset,
                number,
            )
        records.append(line.ljust(record_length, b" "))
    return records


def _split(data: bytes, record_length: int, what: str, dataset: str) -> list[bytes]:
    try:
        return _codec.split_records(data, record_length)
    except _codec.CodecError as exc:
        raise FixtureError(f"{what}: {exc.reason}", dataset) from None


def _decode_source(
    data: bytes, encoding: str, record_length: int, what: str, dataset: str
) -> list[bytes]:
    """Fixed-length ASCII records of a file in the given source encoding."""
    if encoding == "ascii":
        return _pad_lines(_text_lines(data), record_length, what, dataset)
    records = _split(data, record_length, what, dataset)
    if encoding == "cp037":
        # cp037 and latin-1 both cover all 256 byte values one to one, so the
        # translation keeps every byte and never fails.
        return [
            record.decode("cp037").encode(_codec.ENCODINGS[_FIXTURE_ENCODING]) for record in records
        ]
    return records


@functools.lru_cache(maxsize=None)
def _repository_records(definition: DatasetSpec, root: str) -> tuple[bytes, ...]:
    if definition.source is None:
        gap_id = definition.missing_input_gap
        if gap_id is not None:
            raise MissingPrerequisite(
                f"{definition.name} has no repository source: no job or data file in the checkout "
                f"creates it",
                gap_id,
            )
        raise FixtureError(
            f"{definition.name} holds no repository data (source built); build it from scenario "
            f"records instead",
            definition.name,
        )
    data = _read_repository_file(definition, definition.source, root, "source")
    records = _decode_source(
        data,
        definition.encoding,
        definition.record_length,
        f"{definition.name} source {definition.source}",
        definition.name,
    )
    if definition.record_count is not None and len(records) != definition.record_count:
        raise FixtureError(
            f"{definition.name} source {definition.source} holds {len(records)} records, not the "
            f"{definition.record_count} tests/fixtures/datasets.yaml declares",
            definition.name,
        )
    return tuple(records)


def repository_dataset(logical: str, repo_root: str | Path = REPO_ROOT) -> bytes:
    """The fixed-length ASCII records of a repository-backed dataset, in
    source order. A dataset without repository data raises
    ``MissingPrerequisite`` under its ``GAP-MIS`` entry, or ``FixtureError``
    when it declares none."""
    return b"".join(_repository_records(spec(logical), _root(repo_root)))


# --------------------------------------------------------------------------
# Cross-check
# --------------------------------------------------------------------------


def _instream_lines(data: bytes, check: CrossCheck, what: str, dataset: str) -> list[bytes]:
    lines = _text_lines(data)
    if check.lines is not None:
        first, last = check.lines
        if last > len(lines):
            raise FixtureError(
                f"{what}: lines {first}-{last} lie beyond its {len(lines)} lines", dataset
            )
        chosen = lines[first - 1 : last]
        for number, line in enumerate(chosen, first):
            if line.startswith((b"//", b"/*")):
                raise FixtureError(
                    f"{what}: line {number} is a JCL statement, not in-stream data", dataset
                )
        return chosen
    for index, line in enumerate(lines):
        if _INSTREAM_START_RE.match(line.decode("latin-1")):
            block = []
            for following in lines[index + 1 :]:
                if following.startswith((b"//", b"/*")):
                    break
                block.append(following)
            return block
    raise FixtureError(f"{what} holds no in-stream data (no DD * statement)", dataset)


def _counterpart_records(definition: DatasetSpec, root: str) -> list[str]:
    check = definition.cross_check
    data = _read_repository_file(definition, check.path, root, "cross-check counterpart")
    what = f"{definition.name} cross-check counterpart {check.path}"
    if check.form == "instream":
        records = _pad_lines(
            _instream_lines(data, check, what, definition.name),
            definition.record_length,
            what,
            definition.name,
        )
    else:
        records = _decode_source(
            data, check.encoding, definition.record_length, what, definition.name
        )
    return [record.decode(_codec.ENCODINGS[_FIXTURE_ENCODING]) for record in records]


def _compare(definition: DatasetSpec, fixture: list[str], counterpart: list[str]) -> list[Mismatch]:
    """One ``Mismatch`` per differing record. A record present on one side
    only differs in every column."""
    length = definition.record_length
    found = []
    for index in range(max(len(fixture), len(counterpart))):
        mine = fixture[index] if index < len(fixture) else None
        theirs = counterpart[index] if index < len(counterpart) else None
        if mine == theirs:
            continue
        if mine is None or theirs is None:
            columns = (1, length)
            mine_text = "<no record>" if mine is None else mine
            theirs_text = "<no record>" if theirs is None else theirs
        else:
            differing = [column for column in range(length) if mine[column] != theirs[column]]
            columns = (differing[0] + 1, differing[-1] + 1)
            mine_text = mine[differing[0] : differing[-1] + 1]
            theirs_text = theirs[differing[0] : differing[-1] + 1]
        record = index + 1
        gap_id = next(
            (
                entry.gap_id
                for entry in definition.registered_mismatches
                if entry.covers(record, columns)
            ),
            None,
        )
        found.append(Mismatch(definition.name, record, columns, gap_id, mine_text, theirs_text))
    return found


@functools.lru_cache(maxsize=None)
def _checked(definition: DatasetSpec, root: str) -> tuple[Mismatch, ...]:
    if definition.cross_check is None:
        return ()
    fixture = [
        record.decode(_codec.ENCODINGS[_FIXTURE_ENCODING])
        for record in _repository_records(definition, root)
    ]
    return tuple(_compare(definition, fixture, _counterpart_records(definition, root)))


def cross_check(repo_root: str | Path = REPO_ROOT) -> list[Mismatch]:
    """Every difference between a dataset's repository records and its
    cross-check counterpart, registered or not, ordered by dataset and
    record."""
    root = _root(repo_root)
    found: list[Mismatch] = []
    for definition in _load_specs().values():
        found.extend(_checked(definition, root))
    return sorted(found, key=lambda mismatch: (mismatch.dataset, mismatch.record, mismatch.columns))


def verify(logical: str, repo_root: str | Path = REPO_ROOT) -> list[Mismatch]:
    """Cross-check the dataset ``logical`` reads its records from (a twin's
    parent, otherwise itself) and return its registered mismatches. An
    unregistered difference raises ``FixtureMismatch``. The comparison runs
    once per process and checkout."""
    owner = spec(spec(logical).owner)
    found = _checked(owner, _root(repo_root))
    unregistered = [mismatch for mismatch in found if mismatch.gap_id is None]
    if unregistered:
        first = unregistered[0]
        raise FixtureMismatch(
            first.dataset, first.record, first.columns, first.describe(), unregistered
        )
    return list(found)


# --------------------------------------------------------------------------
# Building a scenario's datasets
# --------------------------------------------------------------------------


def _scenario_parts(
    scenario: Scenario | Mapping[str, Any],
) -> tuple[str, Mapping[str, Any], Mapping[str, Any]]:
    """The id, preconditions and inputs of a parsed scenario or of its raw
    mapping."""
    if isinstance(scenario, Scenario):
        return scenario.id, scenario.preconditions, scenario.inputs
    if isinstance(scenario, Mapping):
        preconditions = scenario.get("preconditions") or {}
        inputs = scenario.get("inputs") or {}
        scenario_id = str(scenario.get("id", "scenario"))
        if not isinstance(preconditions, Mapping) or not isinstance(inputs, Mapping):
            raise FixtureError(f"{scenario_id}: preconditions and inputs must be mappings")
        return scenario_id, preconditions, inputs
    raise FixtureError(
        f"a scenario is a harness.scenarios.Scenario or its mapping, not {type(scenario).__name__}"
    )


@functools.lru_cache(maxsize=None)
def _layout_keys(layout: str) -> frozenset[str]:
    try:
        return frozenset(_layouts.field_map(layout))
    except HarnessError as exc:
        raise FixtureError(f"layout {layout}: {exc}") from None


@functools.lru_cache(maxsize=None)
def _layout_length(layout: str) -> int:
    try:
        return _layouts.record_length(layout)
    except HarnessError as exc:
        raise FixtureError(f"layout {layout}: {exc}") from None


def _encode_record(definition: DatasetSpec, values: Any, base: bytes | None, where: str) -> bytes:
    """One record of ``definition`` from a decoded field map, on ``base`` (an
    existing record) or on the layout's default record. Keys the layout does
    not know, or a field map for a dataset field with its own layout, go to
    the dataset's ``fields``; everything else to ``harness.codec``, which
    reports unknown keys with the valid ones."""
    if not isinstance(values, Mapping):
        raise FixtureError(
            f"{where} must be a field map of the dataset's layout or {{'@raw': <text>}}",
            definition.name,
        )
    if definition.layout is None:
        raise FixtureError(
            f"{where}: {definition.name} has no layout, so its records cannot be encoded",
            definition.name,
        )
    if _layout_length(definition.layout) != definition.record_length:
        raise FixtureError(
            f"{where}: layout {definition.layout} is {_layout_length(definition.layout)} "
            f"bytes, but {definition.name} records are {definition.record_length}",
            definition.name,
        )
    layout_keys = _layout_keys(definition.layout)
    own_fields = {own.name: own for own in definition.fields}
    layout_values: dict[str, Any] = {}
    overlays: list[tuple[DatasetField, Any]] = []
    for key, value in values.items():
        if not isinstance(key, str):
            raise FixtureError(f"{where}: field key {key!r} must be text", definition.name)
        own = own_fields.get(key)
        if own is not None and (
            _SUBSCRIPT_RE.sub("", key) not in layout_keys
            or (own.layout and isinstance(value, Mapping))
        ):
            overlays.append((own, value))
        else:
            layout_values[key] = value
    try:
        image = _codec.encode_record(layout_values, definition.layout, _FIXTURE_ENCODING, base)
    except _codec.CodecError as exc:
        raise FixtureError(f"{where}: {exc}", definition.name) from None
    if overlays:
        buffer = bytearray(image)
        for own, value in overlays:
            start, end = own.offset, own.offset + own.length
            try:
                if own.layout and isinstance(value, Mapping):
                    data = _codec.encode_record(
                        value, own.layout, _FIXTURE_ENCODING, bytes(buffer[start:end])
                    )
                else:
                    data = _codec.encode(value, own.descriptor(), _FIXTURE_ENCODING)
            except _codec.CodecError as exc:
                raise FixtureError(f"{where}.{own.name}: {exc}", definition.name) from None
            if len(data) != own.length:
                raise FixtureError(
                    f"{where}.{own.name}: encodes to {len(data)} bytes, not the field's "
                    f"{own.length}",
                    definition.name,
                )
            buffer[start:end] = data
        image = bytes(buffer)
    if len(image) != definition.record_length:
        raise FixtureError(
            f"{where}: the record is {len(image)} bytes, not {definition.record_length}",
            definition.name,
        )
    return image


def _assemble(
    definition: DatasetSpec, base_records: list[bytes], synthetic: Any, item_prefix: str
) -> bytes:
    """The dataset's bytes from its base records and its synthetic records.

    Keyed: a synthetic record whose primary key matches a base record
    replaces it, built on that record so unnamed fields keep its values;
    other synthetic records are added; the result is ordered by key bytes,
    and two records with one key are an error. Sequential: base records in
    order, then synthetic records in order."""
    if not isinstance(synthetic, list):
        raise FixtureError(f"{item_prefix} must be a list of records", definition.name)
    for number, record in enumerate(base_records, 1):
        if len(record) != definition.record_length:
            raise FixtureError(
                f"{item_prefix}: base record {number} is {len(record)} bytes, not "
                f"{definition.record_length}",
                definition.name,
                number,
            )
    if not definition.keyed:
        records = list(base_records)
        for index, values in enumerate(synthetic):
            records.append(_encode_record(definition, values, None, f"{item_prefix}[{index}]"))
        return b"".join(records)
    by_key: dict[bytes, bytes] = {}
    origin: dict[bytes, str] = {}
    for number, record in enumerate(base_records, 1):
        key = definition.key_of(record)
        if key in by_key:
            raise FixtureError(
                f"{item_prefix}: {origin[key]} and base record {number} share the primary key "
                f"{definition.key_text(record)!r}",
                definition.name,
                number,
            )
        by_key[key] = record
        origin[key] = f"base record {number}"
    claimed: set[bytes] = set()
    for index, values in enumerate(synthetic):
        label = f"{item_prefix}[{index}]"
        record = _encode_record(definition, values, None, label)
        key = definition.key_of(record)
        if key in claimed:
            raise FixtureError(
                f"{label} has the primary key {definition.key_text(record)!r} of {origin[key]}",
                definition.name,
            )
        if key in by_key:
            record = _encode_record(definition, values, by_key[key], label)
        claimed.add(key)
        by_key[key] = record
        origin[key] = label
    return b"".join(by_key[key] for key in sorted(by_key))


def _precondition_dataset(logical: str, entry: Any, root: str, scenario_id: str) -> bytes:
    where = f"{scenario_id}: preconditions.datasets.{logical}"
    definition = spec(logical)
    if entry is None:
        entry = {}
    if not isinstance(entry, Mapping):
        raise FixtureError(f"{where} must be a mapping {{base?, records?, omit_keys?}}", logical)
    unknown = sorted(str(key) for key in entry if key not in _ENTRY_KEYS)
    if unknown:
        raise FixtureError(
            f"{where}: unknown key(s) {', '.join(unknown)}; use base, records, omit_keys", logical
        )
    base_records: list[bytes] = []
    if "base" in entry:
        base_name = entry["base"]
        base = spec(base_name)
        if base.record_length != definition.record_length:
            raise FixtureError(
                f"{where}.base: {base_name} records are {base.record_length} bytes, "
                f"{logical} records {definition.record_length}",
                logical,
            )
        verify(base_name, root)
        base_records = list(_repository_records(base, root))
    omit_keys = entry.get("omit_keys")
    if omit_keys is not None:
        if not isinstance(omit_keys, list) or not all(isinstance(key, str) for key in omit_keys):
            raise FixtureError(f"{where}.omit_keys must be a list of primary-key texts", logical)
        if definition.key is None:
            raise FixtureError(f"{where}.omit_keys: {logical} has no primary key", logical)
        if "base" not in entry:
            raise FixtureError(
                f"{where}.omit_keys drops base records, so the entry needs base", logical
            )
        omitted = set(omit_keys)
        present = {definition.key_text(record) for record in base_records}
        unmatched = sorted(omitted - present)
        if unmatched:
            raise FixtureError(
                f"{where}.omit_keys {unmatched} match no record of {entry['base']}", logical
            )
        base_records = [
            record for record in base_records if definition.key_text(record) not in omitted
        ]
    records = entry.get("records")
    return _assemble(
        definition, base_records, [] if records is None else records, f"{where}.records"
    )


def param_datasets(scenario: Scenario | Mapping[str, Any]) -> dict[str, bytes]:
    """The datasets a scenario supplies as text: each ``inputs.param_files``
    entry (one record per line) and ``preconditions.parameters.DATEPARM``
    (the one DATEPARM record), each line padded with spaces to the record
    length. Ordered by logical name."""
    scenario_id, preconditions, inputs = _scenario_parts(scenario)
    built: dict[str, bytes] = {}
    param_files = inputs.get("param_files")
    if param_files is not None:
        if not isinstance(param_files, Mapping):
            raise FixtureError(
                f"{scenario_id}: inputs.param_files must map logical datasets to record lines"
            )
        for logical in sorted(param_files, key=str):
            where = f"{scenario_id}: inputs.param_files.{logical}"
            lines = param_files[logical]
            if not isinstance(lines, list) or not all(isinstance(line, str) for line in lines):
                raise FixtureError(f"{where} must be a list of record lines", str(logical))
            built[logical] = _assemble(
                spec(logical), [], [{_codec.RAW_KEY: line} for line in lines], where
            )
    parameters = preconditions.get("parameters")
    if parameters is not None and not isinstance(parameters, Mapping):
        raise FixtureError(f"{scenario_id}: preconditions.parameters must be a mapping")
    if parameters and DATEPARM in parameters:
        where = f"{scenario_id}: preconditions.parameters.{DATEPARM}"
        text = parameters[DATEPARM]
        if not isinstance(text, str):
            raise FixtureError(f"{where} must be the record text, quoted", DATEPARM)
        if DATEPARM in built:
            raise FixtureError(f"{where}: DATEPARM is also given in inputs.param_files", DATEPARM)
        built[DATEPARM] = _assemble(spec(DATEPARM), [], [{_codec.RAW_KEY: text}], where)
    return dict(sorted(built.items()))


def build(
    scenario: Scenario | Mapping[str, Any], repo_root: str | Path = REPO_ROOT
) -> dict[str, bytes]:
    """The bytes of every dataset the scenario's preconditions and parameter
    files define, keyed by logical name in sorted order. Repository bases
    are cross-checked first (``verify``). Every named dataset is built, also
    when a fault targets it."""
    scenario_id, preconditions, _ = _scenario_parts(scenario)
    datasets = preconditions.get("datasets")
    if datasets is None:
        datasets = {}
    if not isinstance(datasets, Mapping):
        raise FixtureError(
            f"{scenario_id}: preconditions.datasets must map logical datasets to entries"
        )
    root = _root(repo_root)
    built: dict[str, bytes] = {}
    for logical in sorted(datasets, key=str):
        if not isinstance(logical, str):
            raise FixtureError(
                f"{scenario_id}: preconditions.datasets key {logical!r} must be a logical name"
            )
        built[logical] = _precondition_dataset(logical, datasets[logical], root, scenario_id)
    for logical, data in param_datasets(scenario).items():
        if logical in built:
            raise FixtureError(
                f"{scenario_id}: {logical} is given both in preconditions.datasets and as a "
                f"parameter",
                logical,
            )
        built[logical] = data
    return dict(sorted(built.items()))


def materialized_digest(data: Mapping[str, bytes]) -> dict[str, str]:
    """``{logical: sha256}`` of materialized datasets, in sorted order: the
    form ``execution_fingerprint`` records fixtures in."""
    if not isinstance(data, Mapping):
        raise FixtureError("materialized datasets are a mapping of logical name to bytes")
    digest: dict[str, str] = {}
    for logical in sorted(data, key=str):
        value = data[logical]
        if not isinstance(value, (bytes, bytearray, memoryview)):
            raise FixtureError(f"{logical} must be bytes, not {type(value).__name__}", str(logical))
        digest[logical] = sha256_hex(bytes(value))
    return digest
