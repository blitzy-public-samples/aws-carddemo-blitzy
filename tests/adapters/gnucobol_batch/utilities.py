"""Utility-step replacements of the ``gnucobol-batch`` job driver: IDCAMS,
SORT, IEBGENER, IEFBR14 and SDSF.

``run_utility(step, ctx)`` runs one ``jobs.yaml`` step whose ``kind`` is a
utility, against the scenario's ``datasets.DatasetStore`` and ``Catalog``
through a ``StepContext``. Each replacement is a registered seam:

IDCAMS (GAP-STB-005)
    ``DELETE``, ``DEFINE CLUSTER``/``ALTERNATEINDEX``/``PATH``/
    ``GENERATIONDATAGROUP``, ``BLDINDEX``, ``REPRO``, ``SET`` and ``IF``, with
    ``LASTCC``/``MAXCC``. The SYSIN comes from ``params.sysin`` or from the
    SYSIN member (REPROC's ``&CNTLLIB(REPROCT)`` is ``app/ctl/REPROCT.ctl``,
    read, never written). Indexed files are the store's Berkeley DB files;
    there are no CI/CA, FREESPACE or SHAREOPTIONS, and the messages are
    emulated.
SORT (GAP-STB-006)
    The DFSORT subset the jobs use: ``SORT FIELDS`` (``CH``, ``ZD``; ``A``,
    ``D``) or ``FIELDS=COPY``, ``INCLUDE COND``, ``OUTREC FIELDS`` with
    ``EDIT``, and ``SYMNAMES``. ``CH`` compares bytes, so the collating
    sequence is ASCII, not EBCDIC; equal keys keep their input order
    (``EQUALS``). Records are padded with spaces or truncated to the SORTOUT
    record length with RC 0, as DFSORT's ``PAD=RC0``/``TRUNC=RC0`` do.
IEBGENER, IEFBR14 and DISP (GAP-STB-007)
    IEBGENER copies SYSUT1 to SYSUT2 at SYSUT2's record length (``SYSIN DD
    DUMMY`` only); IEFBR14 allocates its new datasets and does nothing else.
    ``apply_normal_dispositions`` applies the normal-end ``DISP`` of a step's
    datasets; the abnormal-termination disposition and ``COND`` are recorded in
    ``jobs.yaml`` and never evaluated here (JCL-semantics items, ``unikix``
    only).
SDSF (GAP-STB-008)
    ``CEMT SET FIL(...) OPE/CLO`` commands are a no-op with RC 0: the region
    state is not exercised.

Contract with the job driver
    ``run_utility`` applies the step's normal-end dispositions itself, since
    a replacement always ends normally; the driver calls
    ``apply_normal_dispositions`` only for program steps that end normally.
    Applying them again is harmless: a dataset already catalogued at the same
    file and a dataset already deleted are left alone. A new dataset is
    catalogued only when the step wrote its file; one it never wrote is noted
    as uncatalogued in the catalog evidence.
    ``UtilityResult.created`` names the catalog entries the step added (IDCAMS
    ``DEFINE`` and ``DISP=CATLG``) and ``deleted`` the ones it removed (IDCAMS
    ``DELETE``, ``DISP=DELETE``, ``DISP=UNCATLG`` and generations rolled off).

Output
    The utility's message dataset (SYSPRINT; SYSOUT for SORT; ISFOUT for
    SDSF) goes to ``<workdir>/evidence/<step index>-<step>.sysprint.txt`` and
    never to ``Result.messages``, which hold program DISPLAY lines only, so
    the same baselines hold on ``unikix``. The lines carry no time, path or
    host and follow the order of the statements and the datasets, so two runs
    write identical evidence.

``UtilityError`` (a ``HarnessError``) reports a step the replacements cannot
run as transcribed: an unparseable or unsupported statement, an unknown DD, a
record layout the emulation does not handle. It is never a step return code.
Importing this module reads no file and starts no process.
"""

from __future__ import annotations

import contextlib
import operator
import re
import time
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field, replace
from decimal import Decimal
from pathlib import Path
from typing import Any

from adapters.base import ScenarioTimeout
from adapters.gnucobol_batch.build import KeyProfile
from adapters.gnucobol_batch.datasets import (
    AIX,
    CLUSTER,
    GDG,
    GENERATION,
    NONVSAM,
    PATH,
    Catalog,
    DatasetStore,
    DsnMap,
    Entry,
    GenerationRef,
    JobGenerations,
    idcams_statements,
)
from harness import HarnessError, codec, write_text

__all__ = [
    "INSTREAM_RECORD_LENGTH",
    "SYSPRINT_SUFFIX",
    "UTILITY_KINDS",
    "Comparison",
    "Constant",
    "FieldRef",
    "IdcamsCommand",
    "Logical",
    "OutItem",
    "ResolvedDD",
    "SortKey",
    "SortSpec",
    "StepContext",
    "Symbol",
    "UtilityError",
    "UtilityResult",
    "apply_normal_dispositions",
    "edit_zoned",
    "parse_idcams",
    "parse_sort",
    "parse_symnames",
    "run_utility",
    "zoned_value",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

UTILITY_KINDS = ("idcams", "sort", "iebgener", "iefbr14", "sdsf")
SYSPRINT_SUFFIX = ".sysprint.txt"
INSTREAM_RECORD_LENGTH = 80  # a DD * record is a card image
IDCAMS_COLUMNS = 72  # IDCAMS reads columns 1-72 of a SYSIN member record
SORT_COLUMNS = 71  # DFSORT control statements end in column 71

_BLANK = b" "
_STATUSES = ("NEW", "OLD", "SHR", "MOD")
_NORMAL_DISPOSITIONS = ("DELETE", "KEEP", "PASS", "CATLG", "UNCATLG")
_ABNORMAL_DISPOSITIONS = ("DELETE", "KEEP", "CATLG", "UNCATLG")
_STEP_FILE_RE = re.compile(r"[^A-Za-z0-9.#@$-]")


# --------------------------------------------------------------------------
# Errors and results
# --------------------------------------------------------------------------


class UtilityError(HarnessError):
    """A utility step the replacements cannot run as transcribed in
    ``jobs.yaml``: an unparseable or unsupported control statement, a DD the
    step does not have, a record layout the emulation does not handle. The
    runner reports it ``failing`` with reason ``error``; it is never turned
    into a step return code."""


@dataclass
class UtilityResult:
    """The outcome of one utility step: its return code, the lines of its
    message dataset, the catalog entries it added and removed, and the
    working-directory-relative path of the evidence file holding the lines."""

    rc: int = 0
    sysprint: list[str] = field(default_factory=list)
    created: list[str] = field(default_factory=list)
    deleted: list[str] = field(default_factory=list)
    evidence: str | None = None


@dataclass(frozen=True)
class ResolvedDD:
    """One dataset of a DD (one member of a concatenation).

    ``dsn`` is the catalog name: the DSN, or ``<base>.G<nnnn>V00`` for a
    relative generation; it is ``None`` for in-stream, ``DUMMY`` and
    ``SYSOUT`` DDs and for a generation that does not resolve. ``coded`` is
    the DSN as the JCL codes it (``BASE(+1)``). ``path`` is the data file (the
    base cluster's for an alternate-index path). ``exists`` means catalogued
    with its file present; ``is_new`` means allocated by this step (``NEW``,
    or ``MOD`` of a dataset that does not exist). ``organization`` is ``ksds``
    or ``sequential``; ``profile`` the keys the indexed file physically has."""

    ddname: str
    dsn: str | None
    coded: str | None
    logical: str | None
    path: Path | None
    record_length: int | None
    organization: str | None
    exists: bool
    is_new: bool
    disp: tuple[str, ...] = ()
    generation: GenerationRef | None = None
    profile: KeyProfile | None = None
    instream: tuple[str, ...] | None = None
    dummy: bool = False
    sysout: bool = False
    member: bool = False

    @property
    def shown(self) -> str:
        """The name messages give the dataset."""
        return self.dsn or self.coded or self.ddname

    @property
    def dataset(self) -> bool:
        """Whether the DD names a dataset of the store (not in-stream data,
        ``DUMMY``, ``SYSOUT`` or a repository member)."""
        return not (self.instream is not None or self.dummy or self.sysout or self.member)


def _positive_int(value: Any, what: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise UtilityError(f"{what} must be a positive integer, got {value!r}")
    return value


def _disp(entry: Mapping[str, Any], where: str) -> tuple[str, ...]:
    raw = entry.get("disp")
    if raw is None:
        return ()
    if not isinstance(raw, list) or not all(isinstance(part, str) for part in raw):
        raise UtilityError(f"{where}: disp must be a list of subparameters, got {raw!r}")
    disp = tuple(part.strip().upper() for part in raw)
    if not 1 <= len(disp) <= 3:
        raise UtilityError(f"{where}: DISP takes one to three subparameters, got {raw!r}")
    if disp[0] and disp[0] not in _STATUSES:
        raise UtilityError(f"{where}: DISP status {disp[0]} is not one of {', '.join(_STATUSES)}")
    if len(disp) > 1 and disp[1] and disp[1] not in _NORMAL_DISPOSITIONS:
        raise UtilityError(f"{where}: normal disposition {disp[1]} is not known")
    if len(disp) > 2 and disp[2] and disp[2] not in _ABNORMAL_DISPOSITIONS:
        raise UtilityError(f"{where}: abnormal disposition {disp[2]} is not known")
    return disp


def _status(disp: Sequence[str]) -> str:
    """The DISP status; an omitted one is ``NEW``, as in JCL."""
    return disp[0] if disp and disp[0] else "NEW"


# --------------------------------------------------------------------------
# Step context and DD resolution
# --------------------------------------------------------------------------


@dataclass
class StepContext:
    """What a utility step runs against: the scenario's ``store`` and
    ``catalog``, the job's ``generations`` (relative generations fixed at job
    start), the ``dsn_map`` of ``jobs.yaml``, the repository root (read only,
    for SYSIN members), the scenario working directory (the store's), the
    step's position in the scenario (its evidence file name) and the scenario
    deadline, a ``time.monotonic()`` value. ``run_utility`` binds the step it
    runs (``bound``); ``resolve_dd`` reads that step's DDs."""

    store: DatasetStore
    catalog: Catalog
    generations: JobGenerations
    dsn_map: DsnMap
    repo_root: Path
    workdir: Path
    step_index: int
    deadline: float | None = None
    step: Mapping[str, Any] | None = None
    # Record length of each sequential file a replacement wrote during the
    # step, by path: the length a new dataset is catalogued with when its DD
    # codes none. Fresh for every bound step.
    written: dict[Path, int] = field(default_factory=dict, init=False, repr=False)

    def __post_init__(self) -> None:
        self.repo_root = Path(self.repo_root).resolve()
        self.workdir = Path(self.workdir).resolve()
        if self.workdir != self.store.workdir:
            raise UtilityError(
                f"the step context's working directory {self.workdir} is not the store's "
                f"{self.store.workdir}"
            )
        if self.catalog.store is not self.store:
            raise UtilityError("the step context's catalog belongs to another dataset store")
        if isinstance(self.step_index, bool) or not isinstance(self.step_index, int):
            raise UtilityError(f"step_index must be an integer, got {self.step_index!r}")
        if self.step_index < 0:
            raise UtilityError(f"step_index must not be negative, got {self.step_index}")

    def bound(self, step: Mapping[str, Any]) -> StepContext:
        """This context for ``step``, with no dataset written yet."""
        if not isinstance(step, Mapping):
            raise UtilityError(f"a jobs.yaml step must be a mapping, got {type(step).__name__}")
        return replace(self, step=step)

    @property
    def step_name(self) -> str:
        name = (self.step or {}).get("step")
        return name if isinstance(name, str) and name else f"step {self.step_index}"

    def check_deadline(self, what: str) -> None:
        """Raise ``ScenarioTimeout`` once the scenario deadline has passed."""
        if self.deadline is not None and time.monotonic() >= self.deadline:
            raise ScenarioTimeout(f"the scenario deadline passed {what} in step {self.step_name}")

    # -- DDs -----------------------------------------------------------------

    def _dds(self) -> Mapping[str, Any]:
        if self.step is None:
            raise UtilityError("the step context is not bound to a step")
        dds = self.step.get("dd") or {}
        if not isinstance(dds, Mapping):
            raise UtilityError(f"step {self.step_name}: dd must be a mapping")
        return dds

    def dd_names(self) -> list[str]:
        """The step's ddnames in transcription order."""
        return [str(name) for name in self._dds()]

    def has_dd(self, ddname: str) -> bool:
        return ddname in self._dds()

    def dd(self, ddname: str) -> Mapping[str, Any]:
        """The ``jobs.yaml`` entry of one DD; an unknown ddname is a
        ``UtilityError``."""
        entry = self._dds().get(ddname)
        if entry is None:
            raise UtilityError(f"step {self.step_name} has no DD {ddname}")
        if not isinstance(entry, Mapping):
            raise UtilityError(f"step {self.step_name} DD {ddname} is not a mapping")
        return entry

    def resolve_dd(self, ddname: str) -> list[ResolvedDD]:
        """The datasets of DD ``ddname``: one, or every member of a
        concatenation in order."""
        return self._resolve_dd(ddname, frozenset())

    def resolve_dsn(self, dsn: str, ddname: str | None = None) -> ResolvedDD:
        """A dataset IDCAMS names directly (``INDATASET``, ``OUTDATASET``): an
        existing dataset, opened as ``OLD``."""
        if not isinstance(dsn, str) or not dsn:
            raise UtilityError(f"step {self.step_name}: a dataset name is required, got {dsn!r}")
        entry = {"dsn": dsn, "disp": ["OLD"], "logical": self.dsn_map.logical_for(dsn)}
        return self._resolve(ddname or dsn, entry, frozenset())

    def _resolve_dd(self, ddname: str, seen: frozenset[str]) -> list[ResolvedDD]:
        entry = self.dd(ddname)
        concat = entry.get("concat")
        if concat is None:
            return [self._resolve(ddname, entry, seen)]
        if not isinstance(concat, list) or not concat:
            raise UtilityError(f"step {self.step_name} DD {ddname}: concat must be a list")
        members = []
        for member in concat:
            if not isinstance(member, Mapping):
                raise UtilityError(
                    f"step {self.step_name} DD {ddname}: a concatenation member is not a mapping"
                )
            members.append(self._resolve(ddname, member, seen))
        return members

    def _dcb_length(
        self, ddname: str, entry: Mapping[str, Any], seen: frozenset[str]
    ) -> int | None:
        """The LRECL of a DD's DCB; ``DCB=(*.<dd>)`` refers to the first
        dataset of another DD of the step."""
        dcb = entry.get("dcb")
        if dcb is None:
            return None
        where = f"step {self.step_name} DD {ddname}"
        if not isinstance(dcb, Mapping):
            raise UtilityError(f"{where}: dcb must be a mapping")
        if dcb.get("lrecl") is not None:
            return _positive_int(dcb["lrecl"], f"{where} DCB LRECL")
        refer = dcb.get("refer")
        if refer is None:
            return None
        if not isinstance(refer, str) or refer == ddname or refer in seen:
            raise UtilityError(f"{where}: DCB=(*.{refer}) cannot be resolved")
        return self._resolve_dd(refer, seen | {ddname})[0].record_length

    def _resolve(
        self, ddname: str, entry: Mapping[str, Any], seen: frozenset[str]
    ) -> ResolvedDD:
        where = f"step {self.step_name} DD {ddname}"
        if entry.get("instream") is not None:
            lines = entry["instream"]
            if not isinstance(lines, list) or not all(isinstance(line, str) for line in lines):
                raise UtilityError(f"{where}: instream must be a list of lines")
            return ResolvedDD(
                ddname, None, None, None, None, INSTREAM_RECORD_LENGTH, "sequential",
                True, False, instream=tuple(lines),
            )
        if entry.get("dummy"):
            return ResolvedDD(ddname, None, None, None, None, None, None, True, False, dummy=True)
        if entry.get("sysout") is not None:
            return ResolvedDD(ddname, None, None, None, None, None, None, True, False, sysout=True)
        dsn = entry.get("dsn")
        if not isinstance(dsn, str) or not dsn:
            raise UtilityError(f"{where} names no dataset")
        disp = _disp(entry, where)
        if entry.get("member") is not None:
            return ResolvedDD(
                ddname, dsn, dsn, None, None, None, "sequential", True, False, disp, member=True
            )
        logical = entry.get("logical")
        logical = logical if isinstance(logical, str) else None
        spec = self.dsn_map.specs.get(logical) if logical is not None else None
        dcb_length = self._dcb_length(ddname, entry, seen)
        status = _status(disp)
        gdg = entry.get("gdg")
        reference: GenerationRef | None = None
        if gdg is not None:
            coded = f"{dsn}({gdg})"
            reference = self.generations.resolve(dsn, gdg)
            if reference is None:
                return ResolvedDD(
                    ddname, None, coded, logical, None,
                    dcb_length or (spec.record_length if spec else None),
                    "sequential", False, status in ("NEW", "MOD"), disp,
                )
            name, path = reference.name, reference.path
            catalogued = self.catalog.get(name)
        else:
            coded = name = dsn
            catalogued = self.catalog.get(dsn)
            if catalogued is not None and catalogued.type == GDG:
                raise UtilityError(
                    f"{where}: {dsn} is a GDG base coded without a relative generation; the "
                    f"concatenation of all its generations is not emulated (GAP-STB-009)"
                )
            if catalogued is not None and catalogued.type in (AIX, PATH):
                catalogued = self.catalog.base_cluster(dsn)
            path = (
                catalogued.path
                if catalogued is not None and catalogued.path is not None
                else self.catalog.path_for(dsn)
            )
        exists = catalogued is not None and self.store.exists(path)
        is_new = status == "NEW" or (status == "MOD" and not exists)
        profile = None
        if catalogued is not None and catalogued.type == CLUSTER:
            organization = "ksds"
            profile = self.store.profile(path) or catalogued.profile
            record_length = catalogued.record_length
        elif catalogued is not None and not is_new:
            organization = "sequential"
            record_length = catalogued.record_length or dcb_length or (
                spec.record_length if spec else None
            )
        elif is_new:
            organization = "sequential"
            record_length = dcb_length or (spec.record_length if spec else None)
        else:
            organization = spec.organization if spec is not None else "sequential"
            record_length = dcb_length or (spec.record_length if spec else None)
        return ResolvedDD(
            ddname, name, coded, logical, path, record_length, organization, exists, is_new,
            disp, reference, profile,
        )


# --------------------------------------------------------------------------
# Normal-end dispositions (GAP-STB-007)
# --------------------------------------------------------------------------


def _noted(catalog: Catalog, name: str) -> bool:
    return any(item.get("name") == name for item in catalog.uncatalogued)


def apply_normal_dispositions(
    step: Mapping[str, Any], ctx: StepContext
) -> tuple[list[str], list[str]]:
    """Apply the normal-end disposition of every dataset of ``step``, in DD
    order; returns the catalog names added and removed.

    ``CATLG`` catalogues a dataset the step wrote: a relative generation as
    its generation (rolling off the oldest past the GDG ``LIMIT``), any other
    as ``NONVSAM``. ``DELETE`` removes it from the catalog and the store.
    ``UNCATLG`` drops the entry, keeps the file and reports the name as
    removed. ``KEEP`` and ``PASS`` change nothing; a new dataset kept that
    way stays uncatalogued. An omitted disposition is ``DELETE`` for a new
    dataset and ``KEEP`` otherwise, as in JCL. The abnormal-termination
    subparameter is never applied: the job driver applies no disposition to
    a step that abends.

    The call is idempotent, so the driver may apply the dispositions of a
    step ``run_utility`` already applied."""
    bound = ctx if ctx.step is step else ctx.bound(step)
    created: list[str] = []
    deleted: list[str] = []
    catalog, store = bound.catalog, bound.store
    for ddname in bound.dd_names():
        for resolved in bound.resolve_dd(ddname):
            if not resolved.dataset:
                continue
            disp = resolved.disp
            normal = disp[1] if len(disp) > 1 and disp[1] else None
            if normal is None:
                normal = "DELETE" if resolved.is_new else "KEEP"
            if normal == "CATLG":
                created.extend(_catalog_new_dataset(bound, resolved, deleted))
            elif normal == "DELETE":
                deleted.extend(_delete_dataset(bound, resolved))
            elif normal == "UNCATLG":
                if resolved.dsn is not None and catalog.uncatalog(resolved.dsn) is not None:
                    catalog.note_uncatalogued(
                        resolved.dsn, resolved.path, f"DISP=UNCATLG in step {bound.step_name}"
                    )
                    catalog.log.append(
                        f"step {bound.step_name}: uncatalogued {resolved.dsn} (DISP=UNCATLG)"
                    )
                    deleted.append(resolved.dsn)
            elif (
                resolved.is_new
                and resolved.dsn is not None
                and resolved.path is not None
                and store.exists(resolved.path)
                and catalog.get(resolved.dsn) is None
                and not _noted(catalog, resolved.dsn)
            ):
                catalog.note_uncatalogued(
                    resolved.dsn,
                    resolved.path,
                    f"DISP={normal} in step {bound.step_name}: a new dataset left uncatalogued",
                )
    return created, deleted


def _catalog_new_dataset(
    ctx: StepContext, resolved: ResolvedDD, rolled_off: list[str]
) -> list[str]:
    catalog, store = ctx.catalog, ctx.store
    if resolved.dsn is None or resolved.path is None:
        return []
    if not resolved.is_new and catalog.get(resolved.dsn) is not None:
        return []  # OLD/SHR/MOD of a catalogued dataset: already catalogued
    if not store.exists(resolved.path):
        # A new dataset is catalogued only once the step has written its file;
        # this keeps a failed utility's output out of the catalog.
        if not _noted(catalog, resolved.dsn):
            catalog.note_uncatalogued(
                resolved.dsn, None, f"DISP=CATLG in step {ctx.step_name}: the step never wrote it"
            )
        return []
    record_length = resolved.record_length or ctx.written.get(resolved.path)
    current = catalog.get(resolved.dsn)
    if record_length is None and current is not None and current.path == resolved.path:
        record_length = current.record_length  # catalogued by an earlier call for this step
    if resolved.generation is not None:
        entry = resolved.generation.entry(record_length=record_length, logical=resolved.logical)
    else:
        entry = Entry.nonvsam(
            resolved.dsn, resolved.path, record_length=record_length, logical=resolved.logical
        )
    if current == entry:
        return []
    rolled_off.extend(catalog.catalog_new(entry))
    catalog.log.append(f"step {ctx.step_name}: catalogued {entry.name} (DISP=CATLG)")
    return [entry.name]


def _delete_dataset(ctx: StepContext, resolved: ResolvedDD) -> list[str]:
    catalog, store = ctx.catalog, ctx.store
    if resolved.dsn is not None and catalog.get(resolved.dsn) is not None:
        removed = catalog.delete(resolved.dsn)
        catalog.log.append(f"step {ctx.step_name}: deleted {resolved.dsn} (DISP=DELETE)")
        return removed
    if resolved.path is not None and store.exists(resolved.path):
        store.remove(resolved.path)  # allocated by this step and never catalogued
        catalog.log.append(f"step {ctx.step_name}: deleted {resolved.shown} (DISP=DELETE)")
        return [resolved.shown]
    return []


# --------------------------------------------------------------------------
# Dispatcher and evidence
# --------------------------------------------------------------------------


def _evidence_path(ctx: StepContext) -> Path:
    name = _STEP_FILE_RE.sub("_", ctx.step_name)
    return ctx.store.inside(
        ctx.store.evidence_dir / f"{ctx.step_index:02d}-{name}{SYSPRINT_SUFFIX}"
    )


def _write_evidence(ctx: StepContext, lines: Sequence[str]) -> str:
    target = _evidence_path(ctx)
    write_text(target, "".join(f"{line}\n" for line in lines))
    return ctx.store.relative(target)


def run_utility(step: Mapping[str, Any], ctx: StepContext) -> UtilityResult:
    """Run one utility step of ``jobs.yaml`` (``kind`` one of
    ``UTILITY_KINDS``), apply its normal-end dispositions and write its
    message lines to the step's evidence file.

    Raises ``UtilityError`` for a step the replacements cannot run as
    transcribed and ``ScenarioTimeout`` once the deadline has passed; the
    lines written until then still go to the evidence file."""
    bound = ctx.bound(step)
    kind = step.get("kind")
    runner = _RUNNERS.get(kind) if isinstance(kind, str) else None
    if runner is None:
        raise UtilityError(
            f"step {bound.step_name}: kind {kind!r} is not a utility; the replacements are "
            f"{', '.join(UTILITY_KINDS)}"
        )
    result = UtilityResult()
    try:
        bound.check_deadline(f"before {str(kind).upper()} started")
        result.rc = runner(bound, result)
        created, deleted = apply_normal_dispositions(step, bound)
        result.created.extend(created)
        result.deleted.extend(deleted)
    except BaseException:
        with contextlib.suppress(OSError, HarnessError):
            _write_evidence(bound, result.sysprint)
        raise
    result.evidence = _write_evidence(bound, result.sysprint)
    return result


# --------------------------------------------------------------------------
# Shared record helpers
# --------------------------------------------------------------------------


def _fit(record: bytes, length: int) -> bytes:
    """``record`` padded with spaces or truncated to ``length`` bytes."""
    if len(record) >= length:
        return record[:length]
    return record + _BLANK * (length - len(record))


def _split(data: bytes, length: int | None, shown: str) -> list[bytes]:
    """The ``length``-byte records of ``data``, framed by ``codec.split_records``."""
    if length is None:
        raise UtilityError(f"{shown} has no known record length")
    try:
        return codec.split_records(data, length, dataset=shown)
    except codec.CodecError as exc:
        raise UtilityError(str(exc)) from exc


def _instream_records(resolved: ResolvedDD) -> list[bytes]:
    records = []
    for number, line in enumerate(resolved.instream or (), start=1):
        if len(line) > INSTREAM_RECORD_LENGTH:
            raise UtilityError(
                f"DD {resolved.ddname} in-stream line {number} is longer than "
                f"{INSTREAM_RECORD_LENGTH} columns"
            )
        try:
            records.append(_fit(line.encode("latin-1"), INSTREAM_RECORD_LENGTH))
        except UnicodeEncodeError:
            raise UtilityError(
                f"DD {resolved.ddname} in-stream line {number} holds a character outside latin-1"
            ) from None
    return records


def _read_records(ctx: StepContext, resolved: ResolvedDD) -> list[bytes]:
    """Every record of an existing input dataset: an indexed file unloaded in
    primary-key order, a sequential file split at its record length,
    in-stream lines as 80-byte card images, nothing for ``DUMMY``."""
    if resolved.dummy:
        return []
    if resolved.instream is not None:
        return _instream_records(resolved)
    if resolved.path is None or not resolved.exists:
        raise UtilityError(f"{resolved.shown} is not an existing dataset")
    if resolved.organization == "ksds":
        profile = resolved.profile
        # An empty cluster yields no records: the VSAM open failure of a
        # cluster never loaded (open return code 160) is not emulated.
        data = ctx.store.unload_indexed(resolved.path, profile)
        length = profile.record_length if profile is not None else resolved.record_length
        return _split(data, length, resolved.shown)
    return _split(ctx.store.read_sequential(resolved.path), resolved.record_length, resolved.shown)


def _write_records(
    ctx: StepContext, resolved: ResolvedDD, records: Sequence[bytes], length: int
) -> None:
    """Write fixed-length records to a sequential output: appended under
    ``DISP=MOD`` of an existing dataset, replacing it otherwise."""
    if resolved.path is None:
        raise UtilityError(f"{resolved.shown} has no file to write")
    data = b"".join(_fit(record, length) for record in records)
    if _status(resolved.disp) == "MOD" and resolved.exists:
        data = ctx.store.read_sequential(resolved.path) + data
    ctx.store.write_sequential(resolved.path, data, length)
    ctx.written[resolved.path] = length


def _shown_key(key: bytes) -> str:
    text = "".join(char if char.isprintable() else "." for char in key.decode("latin-1"))
    return f"  KEY: {text}  X'{key.hex().upper()}'"


# --------------------------------------------------------------------------
# IDCAMS (GAP-STB-005): parser
# --------------------------------------------------------------------------

# A parenthesized IDCAMS list, kept as nested tuples of words.
_Operand = str | tuple["_Operand", ...]

_IDCAMS_TOKEN_RE = re.compile(r"'(?:[^']|'')*'?|[(),]|[^\s(),']+")
_SET_RE = re.compile(r"SET\s+(MAXCC|LASTCC)\s*=\s*(\d+)", re.IGNORECASE)
_IF_RE = re.compile(
    r"IF\s+(LASTCC|MAXCC)\s*(¬=|\^=|>=|<=|=|>|<|EQ|NE|GT|GE|LT|LE)\s*(\d+)\s+THEN\b\s*(.*)",
    re.IGNORECASE | re.DOTALL,
)
_ELSE_RE = re.compile(r"ELSE\b\s*(.*)", re.IGNORECASE | re.DOTALL)
_TOP_ELSE_RE = re.compile(r"\bELSE\b", re.IGNORECASE)
_CC_OPERATORS: dict[str, Callable[[int, int], bool]] = {
    "=": operator.eq, "EQ": operator.eq,
    "¬=": operator.ne, "^=": operator.ne, "NE": operator.ne,
    ">": operator.gt, "GT": operator.gt,
    ">=": operator.ge, "GE": operator.ge,
    "<": operator.lt, "LT": operator.lt,
    "<=": operator.le, "LE": operator.le,
}
_MAX_CONDITION_CODE = 16

# Abbreviations IDCAMS accepts for the keywords the replacement interprets.
_ALIASES = {
    "DEF": "DEFINE", "DEL": "DELETE", "BIX": "BLDINDEX",
    "CL": "CLUSTER", "AIX": "ALTERNATEINDEX", "GDG": "GENERATIONDATAGROUP",
    "NVSAM": "NONVSAM", "RECSZ": "RECORDSIZE", "UNQK": "UNIQUEKEY",
    "NUNQK": "NONUNIQUEKEY", "UPG": "UPGRADE", "NUPG": "NOUPGRADE", "RUS": "REUSE",
    "NRUS": "NOREUSE", "IXD": "INDEXED", "NIXD": "NONINDEXED", "NUMD": "NUMBERED",
    "LIN": "LINEAR", "IDS": "INDATASET", "ODS": "OUTDATASET", "IFILE": "INFILE",
    "OFILE": "OUTFILE", "LIM": "LIMIT", "SCR": "SCRATCH", "NSCR": "NOSCRATCH",
    "EMP": "EMPTY", "NEMP": "NOEMPTY", "PRG": "PURGE", "NPRG": "NOPURGE",
    "PENT": "PATHENTRY", "REL": "RELATE", "ELIMIT": "ERRORLIMIT", "CAT": "CATALOG",
    "CYL": "CYLINDERS", "TRK": "TRACKS", "REC": "RECORDS", "VOL": "VOLUMES",
    "SHR": "SHAREOPTIONS", "FSPC": "FREESPACE", "CISZ": "CONTROLINTERVALSIZE",
}

# Space, placement and performance parameters: accepted and without effect,
# since the store has no CI/CA, FREESPACE or SHAREOPTIONS (GAP-STB-005).
_IGNORED_VSAM = frozenset({
    "CYLINDERS", "TRACKS", "RECORDS", "KILOBYTES", "MEGABYTES", "VOLUMES", "SHAREOPTIONS",
    "ERASE", "NOERASE", "FREESPACE", "CONTROLINTERVALSIZE", "SPEED", "RECOVERY", "IMBED",
    "NOIMBED", "REPLICATE", "NOREPLICATE", "UNIQUE", "SUBALLOCATION", "DATACLASS",
    "STORAGECLASS", "MANAGEMENTCLASS", "BUFFERSPACE", "OWNER", "TO", "FOR", "WRITECHECK",
    "NOWRITECHECK", "SPANNED", "NONSPANNED", "CATALOG", "FILE", "NOREUSE",
})


@dataclass(frozen=True)
class _Delete:
    names: tuple[str, ...]
    types: frozenset[str] | None  # catalog types the entry may have; None for any
    scratch: bool
    force: bool


@dataclass(frozen=True)
class _DefineCluster:
    name: str
    profile: KeyProfile
    reuse: bool


@dataclass(frozen=True)
class _DefineAix:
    name: str
    relate: str
    keys: tuple[int, int]  # (offset, length)
    unique: bool
    upgrade: bool
    record_length: int


@dataclass(frozen=True)
class _DefinePath:
    name: str
    pathentry: str


@dataclass(frozen=True)
class _DefineGdg:
    name: str
    limit: int
    scratch: bool
    empty: bool


@dataclass(frozen=True)
class _Bldindex:
    source: tuple[str, str]  # ("dsn" | "dd", name)
    target: tuple[str, str]


@dataclass(frozen=True)
class _Repro:
    source: tuple[str, str]
    target: tuple[str, str]
    error_limit: int


@dataclass(frozen=True)
class IdcamsCommand:
    """One IDCAMS command of a SYSIN. ``text`` is the command as echoed, with
    its continuations joined and its blanks collapsed. ``verb`` is
    ``DELETE``, ``DEFINE``, ``BLDINDEX``, ``REPRO``, ``SET``, ``IF``, ``ELSE``
    or ``NULL`` (an empty ``THEN`` or ``ELSE`` clause). A functional command
    carries its parsed ``spec``; ``SET`` and ``IF`` their ``register``
    (``MAXCC`` or ``LASTCC``) and ``value``; ``IF`` its ``operator``,
    ``then`` and ``otherwise`` commands; ``ELSE`` its command in ``then``."""

    text: str
    verb: str
    spec: Any = None
    register: str | None = None
    operator: str | None = None
    value: int | None = None
    then: IdcamsCommand | None = None
    otherwise: IdcamsCommand | None = None


def _nest(text: str, where: str) -> tuple[_Operand, ...]:
    """Words and parenthesized lists of an IDCAMS command, blanks and commas
    both separating."""
    stack: list[list[_Operand]] = [[]]
    for token in _IDCAMS_TOKEN_RE.findall(text):
        if token == "(":
            stack.append([])
        elif token == ")":
            if len(stack) == 1:
                raise UtilityError(f"{where}: unbalanced ')' in {text!r}")
            group = tuple(stack.pop())
            stack[-1].append(group)
        elif token == ",":
            continue
        elif token.startswith("'") and (len(token) < 2 or not token.endswith("'")):
            raise UtilityError(f"{where}: unterminated quoted string in {text!r}")
        else:
            stack[-1].append(token)
    if len(stack) != 1:
        raise UtilityError(f"{where}: unbalanced '(' in {text!r}")
    return tuple(stack[0])


def _canonical(word: str) -> str:
    upper = word.upper()
    return _ALIASES.get(upper, upper)


def _keywords(items: Sequence[_Operand], where: str) -> dict[str, tuple[_Operand, ...] | None]:
    """``KEYWORD`` and ``KEYWORD(values)`` parameters by canonical keyword."""
    result: dict[str, tuple[_Operand, ...] | None] = {}
    index = 0
    while index < len(items):
        item = items[index]
        if not isinstance(item, str):
            raise UtilityError(f"{where}: a parenthesized list follows no keyword")
        keyword = _canonical(item)
        value: tuple[_Operand, ...] | None = None
        following = items[index + 1] if index + 1 < len(items) else None
        if isinstance(following, tuple):
            value = following
            index += 1
        if keyword in result:
            raise UtilityError(f"{where}: {keyword} is coded twice")
        result[keyword] = value
        index += 1
    return result


def _word(value: tuple[_Operand, ...] | None, keyword: str, where: str) -> str:
    if value is None or len(value) != 1 or not isinstance(value[0], str):
        raise UtilityError(f"{where}: {keyword} needs one value")
    return value[0]


def _numbers(value: tuple[_Operand, ...] | None, keyword: str, count: int, where: str) -> list[int]:
    if (
        value is None
        or len(value) != count
        or not all(isinstance(item, str) and item.isdigit() for item in value)
    ):
        raise UtilityError(f"{where}: {keyword} needs {count} number(s), got {value!r}")
    return [int(item) for item in value]  # type: ignore[arg-type]


def _flag(params: Mapping[str, Any], on: str, off: str, default: bool, where: str) -> bool:
    if on in params and off in params:
        raise UtilityError(f"{where}: {on} and {off} are both coded")
    for keyword in (on, off):
        if keyword in params and params[keyword] is not None:
            raise UtilityError(f"{where}: {keyword} takes no value")
    return True if on in params else False if off in params else default


_DELETE_TYPES: dict[str, frozenset[str]] = {
    "CLUSTER": frozenset({CLUSTER}),
    "ALTERNATEINDEX": frozenset({AIX}),
    "PATH": frozenset({PATH}),
    "GENERATIONDATAGROUP": frozenset({GDG}),
    "NONVSAM": frozenset({NONVSAM, GENERATION}),
}
_DELETE_FLAGS = (
    "PURGE", "NOPURGE", "ERASE", "NOERASE", "FORCE", "NOFORCE", "SCRATCH", "NOSCRATCH",
)
_CLUSTER_ORGANIZATIONS = ("NONINDEXED", "NUMBERED", "LINEAR")
_DEFAULT_KEYS = (64, 0)  # IDCAMS KEYS default: length 64 at offset 0
_DEFAULT_CLUSTER_RECORDSIZE = (4089, 4089)
_DEFAULT_AIX_RECORDSIZE = (4086, 32600)
_DEFAULT_ERROR_LIMIT = 4


def _check_known(
    params: Mapping[str, Any],
    known: Iterable[str],
    where: str,
    ignored: frozenset[str] = _IGNORED_VSAM,
) -> None:
    allowed = set(known) | ignored
    unknown = sorted(keyword for keyword in params if keyword not in allowed)
    if unknown:
        raise UtilityError(
            f"{where}: {', '.join(unknown)} is not emulated by the IDCAMS replacement "
            f"(GAP-STB-005)"
        )


def _required(params: Mapping[str, Any], keyword: str, where: str) -> str:
    if keyword not in params:
        raise UtilityError(f"{where}: {keyword} is required")
    return _word(params[keyword], keyword, where)


def _key_pair(params: Mapping[str, Any], where: str) -> tuple[int, int]:
    """``(offset, length)`` of ``KEYS(length offset)``."""
    length, offset = (
        _numbers(params["KEYS"], "KEYS", 2, where) if "KEYS" in params else _DEFAULT_KEYS
    )
    if length == 0:
        raise UtilityError(f"{where}: KEYS length must be positive")
    return offset, length


def _record_size(params: Mapping[str, Any], default: tuple[int, int], where: str) -> int:
    average, maximum = (
        _numbers(params["RECORDSIZE"], "RECORDSIZE", 2, where)
        if "RECORDSIZE" in params
        else default
    )
    if not 0 < average <= maximum:
        raise UtilityError(f"{where}: RECORDSIZE({average} {maximum}) is not valid")
    return maximum


def _io(
    params: Mapping[str, Any], dsn_keyword: str, dd_keyword: str, where: str
) -> tuple[str, str]:
    coded = [keyword for keyword in (dsn_keyword, dd_keyword) if keyword in params]
    if len(coded) != 1:
        raise UtilityError(f"{where}: exactly one of {dsn_keyword} and {dd_keyword} is required")
    value = _word(params[coded[0]], coded[0], where)
    # INFILE(dd/password): the password is not emulated.
    return ("dsn", value) if coded[0] == dsn_keyword else ("dd", value.split("/", 1)[0])


def _parse_delete(operands: tuple[_Operand, ...], where: str) -> _Delete:
    if not operands:
        raise UtilityError(f"{where}: DELETE names no entry")
    first = operands[0]
    names = (first,) if isinstance(first, str) else first
    if not names or not all(isinstance(name, str) for name in names):
        raise UtilityError(f"{where}: DELETE needs entry names, got {first!r}")
    for name in names:
        if "*" in name:  # type: ignore[operator]
            raise UtilityError(f"{where}: the generic name {name} is not emulated (GAP-STB-005)")
    params = _keywords(operands[1:], where)
    _check_known(params, [*_DELETE_TYPES, *_DELETE_FLAGS, "CATALOG"], where, frozenset())
    types: set[str] = set()
    for keyword, value in params.items():
        if keyword in _DELETE_TYPES:
            if value is not None:
                raise UtilityError(f"{where}: {keyword} takes no value")
            types |= _DELETE_TYPES[keyword]
    _flag(params, "PURGE", "NOPURGE", False, where)
    _flag(params, "ERASE", "NOERASE", False, where)
    return _Delete(
        tuple(names),  # type: ignore[arg-type]
        frozenset(types) or None,
        _flag(params, "SCRATCH", "NOSCRATCH", True, where),
        _flag(params, "FORCE", "NOFORCE", False, where),
    )


def _parse_define(operands: tuple[_Operand, ...], where: str) -> Any:
    if len(operands) < 2 or not isinstance(operands[0], str) or not isinstance(operands[1], tuple):
        raise UtilityError(f"{where}: DEFINE needs an entry type and its parameter list")
    kind = _canonical(operands[0])
    params = _keywords(operands[1], where)
    components = _keywords(operands[2:], where)
    component_names = {"DATA", "INDEX"} if kind in ("CLUSTER", "ALTERNATEINDEX") else set()
    for component, value in components.items():
        if component == "CATALOG":
            continue
        if component not in component_names or value is None:
            raise UtilityError(f"{where}: {component} is not a component of DEFINE {kind}")
        _check_known(_keywords(value, where), {"NAME"}, f"{where} {component}")
    if kind == "CLUSTER":
        _check_known(
            params,
            {"NAME", "KEYS", "RECORDSIZE", "INDEXED", "REUSE", "NOREUSE", *_CLUSTER_ORGANIZATIONS},
            where,
        )
        for organization in _CLUSTER_ORGANIZATIONS:
            if organization in params:
                raise UtilityError(
                    f"{where}: {organization} clusters are not emulated; only INDEXED (GAP-STB-005)"
                )
        if params.get("INDEXED") is not None:
            raise UtilityError(f"{where}: INDEXED takes no value")
        name = _required(params, "NAME", where)
        key = _key_pair(params, where)
        record_length = _record_size(params, _DEFAULT_CLUSTER_RECORDSIZE, where)
        try:
            profile = KeyProfile(record_length, key)
        except HarnessError as exc:
            raise UtilityError(f"{where}: {exc}") from None
        return _DefineCluster(name, profile, _flag(params, "REUSE", "NOREUSE", False, where))
    if kind == "ALTERNATEINDEX":
        _check_known(
            params,
            {"NAME", "RELATE", "KEYS", "UNIQUEKEY", "NONUNIQUEKEY", "UPGRADE", "NOUPGRADE",
             "RECORDSIZE", "REUSE", "NOREUSE"},
            where,
        )
        _flag(params, "REUSE", "NOREUSE", False, where)
        return _DefineAix(
            _required(params, "NAME", where),
            _required(params, "RELATE", where),
            _key_pair(params, where),
            _flag(params, "UNIQUEKEY", "NONUNIQUEKEY", True, where),
            _flag(params, "UPGRADE", "NOUPGRADE", True, where),
            _record_size(params, _DEFAULT_AIX_RECORDSIZE, where),
        )
    if kind == "PATH":
        _check_known(params, {"NAME", "PATHENTRY", "UPDATE", "NOUPDATE"}, where)
        _flag(params, "UPDATE", "NOUPDATE", True, where)
        return _DefinePath(_required(params, "NAME", where), _required(params, "PATHENTRY", where))
    if kind == "GENERATIONDATAGROUP":
        _check_known(
            params,
            {"NAME", "LIMIT", "SCRATCH", "NOSCRATCH", "EMPTY", "NOEMPTY", "PURGE", "NOPURGE",
             "EXTENDED", "LIFO"},
            where,
        )
        if "LIMIT" not in params:
            raise UtilityError(f"{where}: LIMIT is required")
        limit = _numbers(params["LIMIT"], "LIMIT", 1, where)[0]
        if not 1 <= limit <= (999 if "EXTENDED" in params else 255):
            raise UtilityError(f"{where}: LIMIT({limit}) is out of range")
        _flag(params, "PURGE", "NOPURGE", False, where)
        return _DefineGdg(
            _required(params, "NAME", where),
            limit,
            _flag(params, "SCRATCH", "NOSCRATCH", False, where),
            _flag(params, "EMPTY", "NOEMPTY", False, where),
        )
    raise UtilityError(f"{where}: DEFINE {kind} is not emulated (GAP-STB-005)")


def _parse_bldindex(operands: tuple[_Operand, ...], where: str) -> _Bldindex:
    params = _keywords(operands, where)
    _check_known(
        params,
        {"INDATASET", "INFILE", "OUTDATASET", "OUTFILE", "EXTERNALSORT", "INTERNALSORT",
         "WORKFILES", "SORTCALL", "NOSORTCALL", "CATALOG"},
        where,
        frozenset(),
    )
    return _Bldindex(
        _io(params, "INDATASET", "INFILE", where), _io(params, "OUTDATASET", "OUTFILE", where)
    )


def _parse_repro(operands: tuple[_Operand, ...], where: str) -> _Repro:
    params = _keywords(operands, where)
    _check_known(
        params, {"INDATASET", "INFILE", "OUTDATASET", "OUTFILE", "ERRORLIMIT"}, where, frozenset()
    )
    limit = (
        _numbers(params["ERRORLIMIT"], "ERRORLIMIT", 1, where)[0]
        if "ERRORLIMIT" in params
        else _DEFAULT_ERROR_LIMIT
    )
    if limit == 0:
        raise UtilityError(f"{where}: ERRORLIMIT must be positive")
    return _Repro(
        _io(params, "INDATASET", "INFILE", where),
        _io(params, "OUTDATASET", "OUTFILE", where),
        limit,
    )


_PARSERS: dict[str, Callable[[tuple[_Operand, ...], str], Any]] = {
    "DELETE": _parse_delete,
    "DEFINE": _parse_define,
    "BLDINDEX": _parse_bldindex,
    "REPRO": _parse_repro,
}


def _top_level_else(clause: str) -> int | None:
    """The offset of the first ``ELSE`` of an ``IF`` clause outside
    parentheses and quotes."""
    for match in _TOP_ELSE_RE.finditer(clause):
        prefix = re.sub(r"'(?:[^']|'')*'", "", clause[: match.start()])
        if prefix.count("'") == 0 and prefix.count("(") == prefix.count(")"):
            return match.start()
    return None


def _parse_command(text: str, where: str) -> IdcamsCommand:
    statement = " ".join(text.split())
    if not statement:
        return IdcamsCommand("", "NULL")
    match = _SET_RE.fullmatch(statement)
    if match:
        value = int(match.group(2))
        if value > _MAX_CONDITION_CODE:
            raise UtilityError(f"{where}: SET {match.group(1).upper()} = {value} is above 16")
        return IdcamsCommand(statement, "SET", register=match.group(1).upper(), value=value)
    match = _IF_RE.fullmatch(statement)
    if match:
        register, comparison, threshold, clause = match.groups()
        clause = clause.strip()
        otherwise = None
        split = None if clause.upper().startswith("IF ") else _top_level_else(clause)
        if split is not None:
            otherwise = _parse_command(clause[split + len("ELSE") :], where)
            clause = clause[:split]
        if clause.strip().upper() == "DO" or (otherwise and otherwise.text.upper() == "DO"):
            raise UtilityError(f"{where}: DO-END groups are not emulated (GAP-STB-005)")
        return IdcamsCommand(
            statement,
            "IF",
            register=register.upper(),
            operator=comparison.upper(),
            value=int(threshold),
            then=_parse_command(clause, where),
            otherwise=otherwise,
        )
    match = _ELSE_RE.fullmatch(statement)
    if match:
        return IdcamsCommand(statement, "ELSE", then=_parse_command(match.group(1), where))
    items = _nest(statement, where)
    verb = _canonical(items[0]) if items and isinstance(items[0], str) else ""
    if verb == "IF":
        raise UtilityError(f"{where}: malformed IF command: {statement}")
    parser = _PARSERS.get(verb)
    if parser is None:
        raise UtilityError(
            f"{where}: the IDCAMS command {verb or statement!r} is not emulated (GAP-STB-005)"
        )
    return IdcamsCommand(statement, verb, spec=parser(items[1:], f"{where} ({verb})"))


def parse_idcams(lines: Iterable[Any]) -> list[IdcamsCommand]:
    """The commands of an IDCAMS SYSIN: ``/* ... */`` comments removed,
    continuation lines (a trailing ``-`` or ``+``) joined, and each command
    parsed. ``KEYS`` and ``RECORDSIZE`` pairs may be written ``(16 0)`` or
    ``(11,25)``, ``KEYS`` length first. A command the replacement does not
    emulate, or cannot parse, raises ``UtilityError``."""
    commands = []
    for number, statement in enumerate(idcams_statements(lines), start=1):
        commands.append(_parse_command(statement, f"IDCAMS command {number}"))
    return commands


# --------------------------------------------------------------------------
# IDCAMS (GAP-STB-005): execution
# --------------------------------------------------------------------------

# IDCAMS entry type letters of the IDC0550I message.
_TYPE_LETTERS = {CLUSTER: "C", AIX: "G", PATH: "R", GDG: "B", GENERATION: "H", NONVSAM: "A"}


class _Idcams:
    """One IDCAMS run: ``LASTCC`` and ``MAXCC``, the SYSPRINT lines, and the
    catalog entries the commands add and remove."""

    def __init__(self, ctx: StepContext, result: UtilityResult) -> None:
        self.ctx = ctx
        self.result = result
        self.lines = result.sysprint
        self.lastcc = 0
        self.maxcc = 0

    def run(self, commands: Sequence[IdcamsCommand]) -> int:
        else_pending: bool | None = None  # the outcome of an IF a separate ELSE may follow
        for command in commands:
            self.ctx.check_deadline(f"before IDCAMS {command.verb}")
            self.lines.append(command.text)
            if command.verb == "ELSE":
                if else_pending is None:
                    raise UtilityError(
                        f"step {self.ctx.step_name}: ELSE without IF: {command.text}"
                    )
                if not else_pending and command.then is not None:
                    self._execute(command.then)
                else_pending = None
            elif command.verb == "IF":
                outcome = self._holds(command)
                self._execute(command.then if outcome else command.otherwise)
                else_pending = outcome if command.otherwise is None else None
            else:
                self._execute(command)
                else_pending = None
            self.lines.append("")
            if self.maxcc >= _MAX_CONDITION_CODE:
                break  # condition code 16 ends IDCAMS processing
        self.lines.append(
            f"IDC0002I IDCAMS PROCESSING COMPLETE. MAXIMUM CONDITION CODE WAS {self.maxcc}"
        )
        return self.maxcc

    def _holds(self, command: IdcamsCommand) -> bool:
        value = self.lastcc if command.register == "LASTCC" else self.maxcc
        return _CC_OPERATORS[command.operator or "="](value, command.value or 0)

    def _execute(self, command: IdcamsCommand | None) -> None:
        if command is None or command.verb == "NULL":
            return
        if command.verb == "IF":
            outcome = self._holds(command)
            self._execute(command.then if outcome else command.otherwise)
        elif command.verb == "SET":
            value = command.value or 0
            if command.register == "MAXCC":
                self.maxcc = value
            else:
                self.lastcc = value
                self.maxcc = max(self.maxcc, value)
        elif command.verb == "ELSE":
            raise UtilityError(f"step {self.ctx.step_name}: ELSE without IF: {command.text}")
        else:
            handler = {
                "DELETE": self._delete,
                "DEFINE": self._define,
                "BLDINDEX": self._bldindex,
                "REPRO": self._repro,
            }[command.verb]
            code = handler(command.spec)
            self.lastcc = code
            self.maxcc = max(self.maxcc, code)
            self.lines.append(f"IDC0001I FUNCTION COMPLETED, HIGHEST CONDITION CODE WAS {code}")

    # -- DELETE --------------------------------------------------------------

    def _delete(self, spec: _Delete) -> int:
        catalog = self.ctx.catalog
        code = 0
        for name in spec.names:
            entry = catalog.get(name)
            if entry is None or (spec.types is not None and entry.type not in spec.types):
                self.lines += [
                    f"IDC3012I ENTRY {name} NOT FOUND",
                    f"IDC0551I ** ENTRY {name} NOT DELETED",
                ]
                code = max(code, 8)
                continue
            if entry.type == GDG and catalog.generations(name) and not spec.force:
                self.lines += [
                    f"IDC3009I ** VSAM CATALOG RETURN CODE IS 8 - GDG {name} HAS GENERATIONS",
                    f"IDC0551I ** ENTRY {name} NOT DELETED",
                ]
                code = max(code, 8)
                continue
            types = {item.name: item.type for item in catalog.entries()}
            removed = catalog.delete(name, scratch=spec.scratch)
            # The entry asked for first, then the alternate indexes, paths or
            # generations deleted with it.
            for removed_name in [name, *(item for item in removed if item != name)]:
                self.lines.append(
                    f"IDC0550I ENTRY ({_TYPE_LETTERS[types[removed_name]]}) {removed_name} DELETED"
                )
            self.result.deleted.extend(removed)
        return code

    # -- DEFINE --------------------------------------------------------------

    def _duplicate(self, name: str) -> bool:
        if self.ctx.catalog.get(name) is None:
            return False
        self.lines.append(f"IDC3013I DUPLICATE DATA SET NAME {name}")
        return True

    def _not_found(self, name: str, code: int) -> int:
        self.lines.append(f"IDC3012I ENTRY {name} NOT FOUND")
        return code

    def _define(self, spec: Any) -> int:
        ctx, catalog = self.ctx, self.ctx.catalog
        if self._duplicate(spec.name):
            return 12
        if isinstance(spec, _DefineCluster):
            path = ctx.store.path_for(spec.name)
            ctx.store.create_indexed(path, spec.profile)
            entry = Entry.cluster(
                spec.name,
                path,
                spec.profile,
                logical=ctx.dsn_map.logical_for(spec.name),
                reuse=spec.reuse,
            )
        elif isinstance(spec, _DefineAix):
            base = catalog.get(spec.relate)
            if base is None or base.type != CLUSTER:
                return self._not_found(spec.relate, 8)
            offset, length = spec.keys
            if base.record_length is not None and offset + length > base.record_length:
                self.lines.append(
                    f"IDC3009I ** KEYS({length} {offset}) DO NOT FIT THE "
                    f"{base.record_length}-BYTE RECORDS OF {spec.relate}"
                )
                return 12
            entry = Entry.aix(
                spec.name,
                spec.relate,
                spec.keys,
                unique=spec.unique,
                upgrade=spec.upgrade,
                record_length=spec.record_length,
            )
        elif isinstance(spec, _DefinePath):
            target = catalog.get(spec.pathentry)
            if target is None or target.type not in (AIX, CLUSTER):
                return self._not_found(spec.pathentry, 8)
            entry = Entry.path_entry(spec.name, spec.pathentry)
        else:
            entry = Entry.gdg(spec.name, limit=spec.limit, scratch=spec.scratch, empty=spec.empty)
        catalog.define(entry)
        self.result.created.append(entry.name)
        return 0

    # -- dataset names of BLDINDEX and REPRO ---------------------------------

    def _dataset(self, reference: tuple[str, str]) -> list[ResolvedDD]:
        kind, name = reference
        if kind == "dsn":
            return [self.ctx.resolve_dsn(name)]
        return self.ctx.resolve_dd(name)

    # -- BLDINDEX ------------------------------------------------------------

    def _bldindex(self, spec: _Bldindex) -> int:
        ctx, catalog, store = self.ctx, self.ctx.catalog, self.ctx.store
        source = self._dataset(spec.source)
        target = self._dataset(spec.target)
        if len(source) != 1 or len(target) != 1:
            raise UtilityError(f"step {ctx.step_name}: BLDINDEX takes no concatenation")
        base_name, aix_name = source[0].dsn, target[0].dsn
        base = catalog.get(base_name) if base_name else None
        if base is None or base.type != CLUSTER:
            return self._not_found(source[0].shown, 12)
        aix = catalog.get(aix_name) if aix_name else None
        if aix is None or aix.type != AIX or aix.keys is None:
            return self._not_found(target[0].shown, 12)
        if aix.relate != base.name:
            self.lines.append(
                f"IDC3009I ** ALTERNATE INDEX {aix.name} IS NOT RELATED TO {base.name}"
            )
            return 12
        if base.path is None or not store.exists(base.path):
            self.lines.append(f"IDC3300I ERROR OPENING {base.name}")
            return 12
        physical = store.profile(base.path) or base.profile
        if physical is None:
            raise UtilityError(f"{base.name} has no key profile to build an alternate index on")
        offset, length = aix.keys
        duplicate = None
        if aix.unique:
            seen: set[bytes] = set()
            data = store.unload_indexed(base.path, physical)
            for record in _split(data, physical.record_length, base.name):
                key = record[offset : offset + length]
                if key in seen:
                    duplicate = key
                    break
                seen.add(key)
        # The base file gains the key as an alternate (or has its duplicates
        # flag set), keeping the alternates it already carries in their order.
        wanted = (offset, length, not aix.unique)
        if any(alt[:2] == (offset, length) for alt in physical.alternates):
            alternates = [wanted if alt[:2] == wanted[:2] else alt for alt in physical.alternates]
        else:
            alternates = [*physical.alternates, wanted]
        rebuilt = physical.with_alternates(alternates)
        if duplicate is not None or (rebuilt != physical and not store.rekey(base.path, rebuilt)):
            self.lines.append(f"IDC3302I ACTION ERROR ON {aix.name}")
            self.lines.append("IDC3308I ** DUPLICATE RECORD - KEY FOLLOWS:")
            if duplicate is not None:
                self.lines.append(_shown_key(duplicate))
            return 8
        self.lines.append(f"IDC0652I {aix.name} SUCCESSFULLY BUILT")
        return 0

    # -- REPRO ---------------------------------------------------------------

    def _repro(self, spec: _Repro) -> int:
        ctx = self.ctx
        sources = self._dataset(spec.source)
        for member in sources:
            if not (member.exists or member.dummy):
                self.lines.append(f"IDC3300I ERROR OPENING {member.shown}")
                return 12
        records: list[bytes] = []
        for member in sources:
            ctx.check_deadline(f"while REPRO read {member.shown}")
            records.extend(_read_records(ctx, member))
        targets = self._dataset(spec.target)
        if len(targets) != 1:
            raise UtilityError(f"step {ctx.step_name}: a REPRO output cannot be a concatenation")
        target = targets[0]
        if target.path is None or not (target.exists or target.is_new):
            self.lines.append(f"IDC3300I ERROR OPENING {target.shown}")
            return 12
        ctx.check_deadline(f"before REPRO wrote {target.shown}")
        if target.organization == "ksds":
            return self._repro_indexed(target, records, spec.error_limit)
        length = target.record_length or sources[0].record_length
        if length is None:
            raise UtilityError(
                f"step {ctx.step_name}: no record length is known for {target.shown}"
            )
        # Records of another length are padded with spaces or truncated to the
        # output LRECL rather than refused (GAP-STB-005).
        _write_records(ctx, target, records, length)
        self.lines.append(f"IDC0005I NUMBER OF RECORDS PROCESSED WAS {len(records)}")
        return 0

    def _record_error(self, target: ResolvedDD, message: str, key: bytes) -> None:
        self.lines += [f"IDC3302I ACTION ERROR ON {target.shown}", message, _shown_key(key)]

    def _repro_indexed(self, target: ResolvedDD, records: Sequence[bytes], limit: int) -> int:
        """Copy into a KSDS as VSAM's keyed sequential insert does: a record
        whose key is below the previous one is out of sequence, one whose key
        equals it or is already in the cluster a duplicate. Both are rejected
        with condition code 8, and the copy stops at the ``ERRORLIMIT``-th
        error."""
        store = self.ctx.store
        profile = target.profile
        if profile is None or target.path is None:
            raise UtilityError(f"{target.shown} has no key profile to copy records into")
        offset, length = profile.primary
        for number, record in enumerate(records, start=1):
            if len(record) != profile.record_length:
                raise UtilityError(
                    f"REPRO into {target.shown}: input record {number} is {len(record)} bytes and "
                    f"the cluster's records are {profile.record_length}; variable-length records "
                    f"are not emulated (GAP-STB-005)"
                )
        present = store.exists(target.path)
        existing = (
            {
                record[offset : offset + length]
                for record in _split(
                    store.unload_indexed(target.path, profile), profile.record_length, target.shown
                )
            }
            if present
            else set()
        )
        accepted: list[bytes] = []
        errors = 0
        previous: bytes | None = None
        for record in records:
            key = record[offset : offset + length]
            if previous is not None and key < previous:
                self._record_error(target, "IDC3314I **RECORD OUT OF SEQUENCE - KEY FOLLOWS:", key)
                errors += 1
            elif key == previous or key in existing:
                self._record_error(target, "IDC3308I ** DUPLICATE RECORD - KEY FOLLOWS:", key)
                errors += 1
            else:
                accepted.append(record)
                previous = key
            if errors >= limit:
                self.lines.append("IDC31467I MAXIMUM ERROR LIMIT REACHED")
                break
        rejected: list[int] = []
        if accepted or not present:
            rejected = store.append_indexed(
                target.path, b"".join(accepted), profile, on_duplicate="report"
            )
        for number in rejected:  # a duplicate key of a unique alternate index
            record = accepted[number - 1]
            key = record[offset : offset + length]
            self._record_error(target, "IDC3308I ** DUPLICATE RECORD - KEY FOLLOWS:", key)
        self.lines.append(
            f"IDC0005I NUMBER OF RECORDS PROCESSED WAS {len(accepted) - len(rejected)}"
        )
        return 8 if errors or rejected else 0


def _idcams_sysin(ctx: StepContext) -> list[str]:
    """The SYSIN lines: ``params.sysin``, or the member a SYSIN DD names,
    read from the repository (columns 1-72)."""
    sysin = _lines_param(_step_params(ctx), "sysin", ctx)
    if sysin is not None:
        if ctx.has_dd("SYSIN"):
            raise UtilityError(f"step {ctx.step_name}: SYSIN is both in-stream and a DD")
        return sysin
    if not ctx.has_dd("SYSIN"):
        raise UtilityError(f"step {ctx.step_name}: the IDCAMS step has no SYSIN")
    entry = ctx.dd("SYSIN")
    if entry.get("instream") is not None:
        return list(ctx.resolve_dd("SYSIN")[0].instream or ())
    member = entry.get("member")
    source = member.get("source") if isinstance(member, Mapping) else None
    if not isinstance(source, str) or not source:
        raise UtilityError(f"step {ctx.step_name}: SYSIN names no member source")
    path = (ctx.repo_root / source).resolve()
    if ctx.repo_root not in path.parents:
        raise UtilityError(
            f"step {ctx.step_name}: SYSIN member {source} lies outside the repository"
        )
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise UtilityError(f"step {ctx.step_name}: SYSIN member {source} does not exist") from None
    except UnicodeDecodeError:
        raise UtilityError(f"step {ctx.step_name}: SYSIN member {source} is not text") from None
    return [line[:IDCAMS_COLUMNS].rstrip() for line in text.splitlines()]


def _run_idcams(ctx: StepContext, result: UtilityResult) -> int:
    return _Idcams(ctx, result).run(parse_idcams(_idcams_sysin(ctx)))


# --------------------------------------------------------------------------
# SORT (GAP-STB-006): symbols, control statements and conditions
# --------------------------------------------------------------------------

_SORT_FORMATS = ("CH", "ZD")
_ZD_MAX_DIGITS = 31  # DFSORT's longest ZD field
_SYMBOL_NAME_RE = re.compile(r"[A-Za-z#$@][A-Za-z0-9#$@_-]*")
_CHAR_CONSTANT_RE = re.compile(r"(\d*)C'((?:[^']|'')*)'", re.IGNORECASE)
_HEX_CONSTANT_RE = re.compile(r"(\d*)X'([0-9A-Fa-f]*)'", re.IGNORECASE)
_NUMBER_RE = re.compile(r"[+-]?\d+")
_BLANKS_RE = re.compile(r"(\d*)X", re.IGNORECASE)
_COLUMN_RE = re.compile(r"(\d+):(.+)")
_EDIT_RE = re.compile(r"EDIT=\((.*)\)", re.IGNORECASE)
_CONDITION_OPERATORS = ("EQ", "NE", "GT", "GE", "LT", "LE")
_PY_OPERATORS: dict[str, Callable[[Any, Any], bool]] = {
    "EQ": operator.eq, "NE": operator.ne, "GT": operator.gt,
    "GE": operator.ge, "LT": operator.lt, "LE": operator.le,
}


def _split_top(text: str, where: str) -> list[str]:
    """``text`` split at the commas outside parentheses and quotes."""
    parts: list[str] = []
    depth, quoted, start, index = 0, False, 0, 0
    while index < len(text):
        char = text[index]
        if quoted:
            if char == "'":
                if text[index + 1 : index + 2] == "'":
                    index += 2
                    continue
                quoted = False
        elif char == "'":
            quoted = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth < 0:
                raise UtilityError(f"{where}: unbalanced ')' in {text!r}")
        elif char == "," and depth == 0:
            parts.append(text[start:index])
            start = index + 1
        index += 1
    if quoted or depth:
        raise UtilityError(f"{where}: unbalanced quotes or parentheses in {text!r}")
    parts.append(text[start:])
    if any(not part for part in parts):
        raise UtilityError(f"{where}: an empty operand in {text!r}")
    return parts


def _first_field(text: str) -> str:
    """``text`` up to the first blank outside quotes: what follows that blank
    is a remark."""
    quoted = False
    for index, char in enumerate(text):
        if char == "'":
            quoted = not quoted
        elif char == " " and not quoted:
            return text[:index]
    return text


def _parenthesized(value: str, where: str) -> str:
    if not (value.startswith("(") and value.endswith(")")):
        raise UtilityError(f"{where}: {value!r} must be a parenthesized list")
    inner = value[1:-1]
    _split_top(inner, where)  # checks that the outer parentheses enclose it
    return inner


@dataclass(frozen=True)
class Constant:
    """A ``C'...'`` or ``X'...'`` constant (``data``) or a decimal one
    (``number``), as coded in ``text``."""

    text: str
    data: bytes | None = None
    number: Decimal | None = None
    hexadecimal: bool = False


def _constant(token: str, where: str, *, repeat: bool = False) -> tuple[int, Constant] | None:
    """``(repetition, constant)`` of a constant token, ``None`` for a token
    that is no constant. ``nC'..'`` and ``nX'..'`` repeat only in OUTREC."""
    for pattern, hexadecimal in ((_CHAR_CONSTANT_RE, False), (_HEX_CONSTANT_RE, True)):
        match = pattern.fullmatch(token)
        if match is None:
            continue
        count = int(match.group(1)) if match.group(1) else 1
        if match.group(1) and not repeat:
            raise UtilityError(f"{where}: a repetition factor is not valid in {token}")
        if count == 0:
            raise UtilityError(f"{where}: a repetition factor must be positive in {token}")
        if hexadecimal:
            if len(match.group(2)) % 2:
                raise UtilityError(f"{where}: {token} has an odd number of hex digits")
            data = bytes.fromhex(match.group(2))
        else:
            try:
                data = match.group(2).replace("''", "'").encode("latin-1")
            except UnicodeEncodeError:
                raise UtilityError(f"{where}: {token} holds a character outside latin-1") from None
        return count, Constant(token, data=data, hexadecimal=hexadecimal)
    if not repeat and _NUMBER_RE.fullmatch(token):
        return 1, Constant(token, number=Decimal(token))
    return None


@dataclass(frozen=True)
class Symbol:
    """A SYMNAMES symbol: a field (1-based ``position``, ``length``, optional
    ``format``) or a ``constant``."""

    name: str
    position: int | None = None
    length: int | None = None
    format: str | None = None
    constant: Constant | None = None

    @property
    def tokens(self) -> tuple[str, ...]:
        """What the symbol stands for in a control statement."""
        if self.constant is not None:
            return (self.constant.text,)
        parts = [str(self.position), str(self.length)]
        return (*parts, self.format) if self.format else tuple(parts)

    @property
    def value(self) -> bytes | Decimal | None:
        """The value of a constant symbol; ``None`` for a field."""
        if self.constant is None:
            return None
        return self.constant.data if self.constant.data is not None else self.constant.number


def parse_symnames(lines: Iterable[Any]) -> dict[str, Symbol]:
    """The symbols of a SYMNAMES dataset: ``NAME,position,length[,format]``
    (1-based position) and ``NAME,constant`` (``C'..'``, ``X'..'`` or a
    decimal number). Text after the first blank outside quotes is a remark
    (TRANREPT's ``//Date``), and a line starting with ``*`` is a comment."""
    symbols: dict[str, Symbol] = {}
    for number, raw in enumerate(lines, start=1):
        if not isinstance(raw, str):
            raise UtilityError(f"SYMNAMES line {number} is not text: {raw!r}")
        line = raw.strip()
        if not line or line.startswith("*"):
            continue
        where = f"SYMNAMES line {number}"
        name, *values = _split_top(_first_field(line), where)
        if not _SYMBOL_NAME_RE.fullmatch(name):
            raise UtilityError(f"{where}: {name!r} is not a symbol name")
        if name in symbols:
            raise UtilityError(f"{where}: symbol {name} is defined twice")
        constant = _constant(values[0], where) if len(values) == 1 else None
        if constant is not None:
            symbols[name] = Symbol(name, constant=constant[1])
            continue
        if len(values) not in (2, 3) or not all(value.isdigit() for value in values[:2]):
            raise UtilityError(f"{where}: {line!r} is not 'name,position,length[,format]'")
        position, length = int(values[0]), int(values[1])
        if position == 0 or length == 0:
            raise UtilityError(f"{where}: position and length must be positive")
        fmt = values[2].upper() if len(values) == 3 else None
        if fmt is not None and not fmt.isalpha():
            raise UtilityError(f"{where}: {values[2]!r} is not a format")
        symbols[name] = Symbol(name, position, length, fmt)
    return symbols


@dataclass(frozen=True)
class FieldRef:
    """A record field: 1-based ``position``, ``length`` and ``format``."""

    position: int
    length: int
    format: str

    @property
    def end(self) -> int:
        """The last byte the field reaches, 1-based."""
        return self.position + self.length - 1

    def extract(self, record: bytes) -> bytes:
        return record[self.position - 1 : self.end]


def zoned_value(raw: bytes) -> Decimal:
    """The integer value of a ``ZD`` field. Valid data decodes with the
    overpunch rules of ``harness.codec`` (``{``, ``A``-``I`` positive, ``}``,
    ``J``-``R`` negative). Like DFSORT, which does not validate ZD data, other
    bytes contribute the digit nibble of their EBCDIC image, and the zone of
    the last byte gives the sign."""
    value = codec.decode(raw, {"type": "zoned-signed", "digits": len(raw)})
    if isinstance(value, Decimal):
        return value
    image = raw.decode("latin-1").encode("cp037")
    magnitude = 0
    for byte in image:
        magnitude = magnitude * 10 + (byte & 0x0F)
    negative = (image[-1] >> 4) in (0xB, 0xD)
    return Decimal(-magnitude if negative else magnitude)


@dataclass(frozen=True)
class Comparison:
    """``left op right`` of an INCLUDE condition: a field compared with a
    field of the same format or with a constant."""

    left: FieldRef
    operator: str
    right: FieldRef | Constant

    def fields(self) -> list[FieldRef]:
        return [self.left, *([self.right] if isinstance(self.right, FieldRef) else [])]

    def matches(self, record: bytes) -> bool:
        compare = _PY_OPERATORS[self.operator]
        left = self.left.extract(record)
        if self.left.format == "ZD":
            right_value = (
                zoned_value(self.right.extract(record))
                if isinstance(self.right, FieldRef)
                else self.right.number
            )
            return compare(zoned_value(left), right_value)
        if isinstance(self.right, FieldRef):
            right = self.right.extract(record)
            width = max(len(left), len(right))
            return compare(_fit(left, width), _fit(right, width))
        data = self.right.data or b""
        # A constant shorter than the field is padded on the right: with
        # blanks for C'..', with binary zeros for X'..'.
        filler = b"\x00" if self.right.hexadecimal else _BLANK
        right = data[: len(left)] + filler * (len(left) - len(data))
        return compare(left, right)


@dataclass(frozen=True)
class Logical:
    """Conditions joined by ``AND`` or ``OR``."""

    operator: str
    parts: tuple[Condition, ...]

    def fields(self) -> list[FieldRef]:
        return [ref for part in self.parts for ref in part.fields()]

    def matches(self, record: bytes) -> bool:
        if self.operator == "AND":
            return all(part.matches(record) for part in self.parts)
        return any(part.matches(record) for part in self.parts)


Condition = Comparison | Logical


def _expand(tokens: Iterable[str], symbols: Mapping[str, Symbol]) -> list[str]:
    """Tokens with each symbol replaced by what it stands for; ``c:SYMBOL``
    keeps its column."""
    expanded: list[str] = []
    for token in tokens:
        column = _COLUMN_RE.fullmatch(token)
        name = column.group(2) if column else token
        if name in symbols:
            parts = list(symbols[name].tokens)
            if column:
                parts[0] = f"{column.group(1)}:{parts[0]}"
            expanded.extend(parts)
        else:
            expanded.append(token)
    return expanded


def _number_token(tokens: Sequence[str], index: int, what: str, where: str) -> int:
    if index >= len(tokens) or not tokens[index].isdecimal() or int(tokens[index]) == 0:
        found = tokens[index] if index < len(tokens) else "nothing"
        raise UtilityError(f"{where}: {what} must be a positive number, found {found}")
    return int(tokens[index])


def _field_format(token: str, where: str) -> str:
    fmt = token.upper()
    if fmt not in _SORT_FORMATS:
        raise UtilityError(
            f"{where}: format {token} is not emulated; the SORT replacement has "
            f"{' and '.join(_SORT_FORMATS)} (GAP-STB-006)"
        )
    return fmt


def _field(position: int, length: int, fmt: str, where: str) -> FieldRef:
    if fmt == "ZD" and length > _ZD_MAX_DIGITS:
        raise UtilityError(f"{where}: a ZD field holds at most {_ZD_MAX_DIGITS} digits")
    return FieldRef(position, length, fmt)


class _ConditionParser:
    """Recursive descent over the tokens of ``COND=(...)``: ``OR`` (``|``)
    of ``AND`` (``&``) groups, so AND binds tighter; a parenthesized token
    is a nested condition."""

    def __init__(self, tokens: Sequence[str], symbols: Mapping[str, Symbol], where: str) -> None:
        self.tokens = _expand(tokens, symbols)
        self.symbols = symbols
        self.where = where
        self.index = 0

    def parse(self) -> Condition:
        condition = self._any()
        if self.index != len(self.tokens):
            raise UtilityError(f"{self.where}: unexpected {self.tokens[self.index]!r} in COND")
        return condition

    def _peek(self) -> str | None:
        return self.tokens[self.index] if self.index < len(self.tokens) else None

    def _next(self, what: str) -> str:
        token = self._peek()
        if token is None:
            raise UtilityError(f"{self.where}: COND ends where {what} is expected")
        self.index += 1
        return token

    def _joined(
        self, words: tuple[str, ...], name: str, part: Callable[[], Condition]
    ) -> Condition:
        parts = [part()]
        while (self._peek() or "").upper() in words:
            self.index += 1
            parts.append(part())
        return parts[0] if len(parts) == 1 else Logical(name, tuple(parts))

    def _any(self) -> Condition:
        return self._joined(("OR", "|"), "OR", self._all)

    def _all(self) -> Condition:
        return self._joined(("AND", "&"), "AND", self._factor)

    def _factor(self) -> Condition:
        token = self._peek()
        if token is not None and token.startswith("("):
            self.index += 1
            inner = _parenthesized(token, self.where)
            return _ConditionParser(_split_top(inner, self.where), self.symbols, self.where).parse()
        left = self._field()
        comparison = self._next("an operator").upper()
        if comparison not in _CONDITION_OPERATORS:
            raise UtilityError(f"{self.where}: {comparison} is not a COND operator")
        token = self._peek() or ""
        following = self.tokens[self.index + 2] if self.index + 2 < len(self.tokens) else ""
        # p,l,f after the operator is a field; a lone number is a constant.
        is_field = token.isdigit() and following.upper() in _SORT_FORMATS
        constant = None if is_field else _constant(token, self.where)
        if constant is not None:
            self.index += 1
            literal = constant[1]
            if (literal.number is not None) != (left.format == "ZD"):
                raise UtilityError(
                    f"{self.where}: a {left.format} field cannot be compared with {literal.text}"
                )
            right: FieldRef | Constant = literal
        else:
            right = self._field()
            if right.format != left.format:
                raise UtilityError(
                    f"{self.where}: a {left.format} field cannot be compared with a "
                    f"{right.format} field"
                )
        return Comparison(left, comparison, right)

    def _field(self) -> FieldRef:
        position = _number_token(self.tokens, self.index, "a field position", self.where)
        length = _number_token(self.tokens, self.index + 1, "a field length", self.where)
        self.index += 2
        fmt = _field_format(self._next("a field format"), self.where)
        return _field(position, length, fmt, self.where)


@dataclass(frozen=True)
class SortKey:
    """A control field of ``SORT FIELDS``."""

    field: FieldRef
    descending: bool


@dataclass(frozen=True)
class OutItem:
    """One OUTREC item placed at 1-based ``column`` for ``length`` bytes: a
    copied ``field``, an edited one (``mask``), or the constant ``data``."""

    column: int
    length: int
    field: FieldRef | None = None
    mask: str | None = None
    data: bytes | None = None

    def render(self, record: bytes) -> bytes:
        if self.data is not None:
            return self.data
        if self.field is None:
            raise UtilityError(f"OUTREC item at column {self.column} has neither data nor a field")
        raw = self.field.extract(record)
        return edit_zoned(raw, self.mask) if self.mask is not None else raw


def edit_zoned(raw: bytes, mask: str) -> bytes:
    """A ZD field edited by a DFSORT ``EDIT`` mask. ``T`` is a significant
    digit, always shown; ``I`` a leading digit shown as a blank while it and
    every digit before it are zero; any other character is a literal, shown
    once a digit has been shown and blank before. The digits are the absolute
    value, right-aligned to the mask's digit positions (high-order digits
    beyond them are dropped); a mask without ``S`` shows no sign."""
    positions = sum(1 for char in mask if char in "IT")
    digits = str(abs(int(zoned_value(raw))))[-positions:].rjust(positions, "0")
    shown: list[str] = []
    started = False
    remaining = iter(digits)
    for char in mask:
        if char in "IT":
            digit = next(remaining)
            if char == "T" or digit != "0" or started:
                started = True
                shown.append(digit)
            else:
                shown.append(" ")
        else:
            shown.append(char if started else " ")
    return "".join(shown).encode("latin-1")


@dataclass(frozen=True)
class SortSpec:
    """The parsed control statements of one SORT step. ``keys`` is empty for
    ``SORT FIELDS=COPY``; ``include`` and ``outrec`` are ``None`` when not
    coded."""

    keys: tuple[SortKey, ...]
    include: Condition | None = None
    outrec: tuple[OutItem, ...] | None = None

    @property
    def copy(self) -> bool:
        return not self.keys

    @property
    def outrec_length(self) -> int | None:
        if self.outrec is None:
            return None
        return max((item.column + item.length - 1 for item in self.outrec), default=0)

    def fields(self) -> list[FieldRef]:
        """Every input field the statements reference."""
        refs = [key.field for key in self.keys]
        if self.include is not None:
            refs += self.include.fields()
        refs += [item.field for item in self.outrec or () if item.field is not None]
        return refs

    def reformat(self, record: bytes) -> bytes:
        if self.outrec is None:
            return record
        built = bytearray(_BLANK * (self.outrec_length or 0))
        for item in self.outrec:
            built[item.column - 1 : item.column - 1 + item.length] = item.render(record)
        return bytes(built)


_DFSORT_FORMATS = frozenset({"CH", "ZD", "PD", "BI", "FI", "FS", "CSF", "UFF", "SFF", "AC", "AQ"})


def _sort_statements(lines: Iterable[Any], where: str) -> list[tuple[str, str]]:
    """``(operation, operand)`` of each control statement. Column 1 holds a
    label, a line starting with ``*`` is a comment, the operand ends at the
    first blank outside quotes, and an operand ending in a comma continues
    on the next line. A label without an operation raises ``UtilityError``,
    except after ``END``, whose following cards ``parse_sort`` ignores."""
    statements: list[tuple[str, str]] = []
    pending: tuple[str, str] | None = None
    for number, raw in enumerate(lines, start=1):
        if not isinstance(raw, str):
            raise UtilityError(f"{where} line {number} is not text: {raw!r}")
        line = raw[:SORT_COLUMNS].rstrip()
        if not line.strip() or (pending is None and line.startswith("*")):
            continue
        if pending is not None:
            pending = (pending[0], pending[1] + _first_field(line.lstrip()))
        else:
            label, _, labelled = line.partition(" ")
            body = line if line.startswith(" ") else labelled
            operation, _, rest = body.strip().partition(" ")
            if not operation:
                if any(statement[0] == "END" for statement in statements):
                    continue
                raise UtilityError(
                    f"{where} line {number}: {raw.rstrip()!r} has the label {label} in column 1 "
                    f"but no operation"
                )
            pending = (operation.upper(), _first_field(rest.lstrip()))
        if not pending[1].endswith(","):
            statements.append(pending)
            pending = None
    if pending is not None:
        raise UtilityError(f"{where}: the {pending[0]} statement continues past the last line")
    return statements


def _sort_params(operand: str, where: str) -> dict[str, str | None]:
    params: dict[str, str | None] = {}
    if not operand:
        return params
    for part in _split_top(operand, where):
        key, equals, value = part.partition("=")
        key = key.upper()
        if key in params:
            raise UtilityError(f"{where}: {key} is coded twice")
        params[key] = value if equals else None
    return params


def _sort_keys(value: str, symbols: Mapping[str, Symbol], where: str) -> tuple[SortKey, ...]:
    if value.upper() == "COPY":
        return ()
    tokens = _expand(_split_top(_parenthesized(value, where), where), symbols)
    keys: list[SortKey] = []
    index = 0
    while index < len(tokens):
        position = _number_token(tokens, index, "a control field position", where)
        length = _number_token(tokens, index + 1, "a control field length", where)
        if index + 2 >= len(tokens) or tokens[index + 2].upper() in ("A", "D"):
            raise UtilityError(
                f"{where}: control field {position},{length} has no format; FORMAT= is not "
                f"emulated (GAP-STB-006)"
            )
        fmt = _field_format(tokens[index + 2], where)
        order = tokens[index + 3].upper() if index + 3 < len(tokens) else ""
        if order not in ("A", "D"):
            raise UtilityError(f"{where}: control field {position},{length} needs order A or D")
        keys.append(SortKey(_field(position, length, fmt, where), order == "D"))
        index += 4
    if not keys:
        raise UtilityError(f"{where}: FIELDS names no control field")
    return tuple(keys)


def _edit_mask(token: str, where: str) -> str | None:
    match = _EDIT_RE.fullmatch(token)
    if match is None:
        return None
    mask = match.group(1)
    if "S" in mask:
        raise UtilityError(f"{where}: the sign character S of EDIT=({mask}) is not emulated")
    if not any(char in "IT" for char in mask):
        raise UtilityError(f"{where}: EDIT=({mask}) has no digit position")
    return mask


def _outrec_items(value: str, symbols: Mapping[str, Symbol], where: str) -> tuple[OutItem, ...]:
    tokens = _split_top(_parenthesized(value, where), where)
    items: list[OutItem] = []
    cursor = 1
    index = 0

    def take_mask() -> str | None:
        nonlocal index
        mask = _edit_mask(tokens[index], where) if index < len(tokens) else None
        if mask is not None:
            index += 1
        return mask

    while index < len(tokens):
        token = tokens[index]
        index += 1
        column_match = _COLUMN_RE.fullmatch(token)
        if column_match:
            column, token = int(column_match.group(1)), column_match.group(2)
            if column == 0 or column < cursor:
                raise UtilityError(f"{where}: column {column} overlaps the item before it")
            start = column
        else:
            start = cursor
        symbol = symbols.get(token)
        if symbol is not None and symbol.constant is not None:
            data = symbol.constant.data
            if data is None:
                raise UtilityError(
                    f"{where}: the numeric constant {token} cannot be an OUTREC item"
                )
            item = OutItem(start, len(data), data=data)
        elif symbol is not None or token.isdigit():
            if symbol is not None:
                named = f"OUTREC field symbol {token}"
                position = _positive_int(symbol.position, f"{where}: the position of {named}")
                length = _positive_int(symbol.length, f"{where}: the length of {named}")
                fmt = symbol.format
            else:
                position = _number_token((token,), 0, "an OUTREC field position", where)
                length = _number_token(tokens, index, "an OUTREC field length", where)
                index += 1
                fmt = tokens[index].upper() if index < len(tokens) else None
                if fmt in _DFSORT_FORMATS:
                    index += 1
                else:
                    fmt = None
            mask = take_mask()
            if mask is not None:
                if fmt != "ZD":
                    raise UtilityError(f"{where}: EDIT is emulated for ZD fields only, not {fmt}")
                ref = _field(position, length, "ZD", where)
                item = OutItem(start, len(mask), field=ref, mask=mask)
            elif fmt is not None and symbol is None:
                raise UtilityError(
                    f"{where}: {position},{length},{fmt} without EDIT is a conversion, which is "
                    f"not emulated (GAP-STB-006)"
                )
            else:
                # A symbol's format is ignored where only p,l is valid.
                item = OutItem(start, length, field=FieldRef(position, length, "CH"))
        else:
            constant = _constant(token, where, repeat=True)
            blanks = None if constant is not None else _BLANKS_RE.fullmatch(token)
            if constant is not None:
                count, literal = constant
                data = (literal.data or b"") * count
                item = OutItem(start, len(data), data=data)
            elif blanks is not None:
                count = int(blanks.group(1)) if blanks.group(1) else 1
                item = OutItem(start, count, data=_BLANK * count)
            else:
                raise UtilityError(
                    f"{where}: the OUTREC item {token!r} is not emulated (GAP-STB-006)"
                )
        if item.length == 0:
            raise UtilityError(f"{where}: the OUTREC item {token!r} is empty")
        items.append(item)
        cursor = start + item.length
    return tuple(items)


def parse_sort(lines: Iterable[Any], symbols: Mapping[str, Symbol] | None = None) -> SortSpec:
    """The ``SORT``, ``INCLUDE`` and ``OUTREC`` statements of a SORT SYSIN,
    with ``symbols`` (``parse_symnames``) substituted. ``SORT FIELDS`` takes
    ``(p,l,f,o,...)`` or symbols, formats ``CH`` and ``ZD``, orders ``A`` and
    ``D``, or ``COPY``; ``INCLUDE COND`` compares fields with fields or
    constants (``EQ NE GT GE LT LE``) joined by ``AND`` and ``OR``;
    ``OUTREC FIELDS`` (or ``BUILD``) takes ``c:``, ``p,l``, symbols, ``X``,
    ``nX``, ``C'..'``, ``X'..'`` and ``EDIT=(mask)`` of ZD fields. Anything
    else raises ``UtilityError``."""
    table = dict(symbols or {})
    keys: tuple[SortKey, ...] | None = None
    include: Condition | None = None
    outrec: tuple[OutItem, ...] | None = None
    seen: set[str] = set()
    for operation, operand in _sort_statements(lines, "SORT SYSIN"):
        where = f"SORT {operation} statement"
        if operation == "END":
            break
        if operation in seen:
            raise UtilityError(f"{where} is coded twice")
        seen.add(operation)
        params = _sort_params(operand, where)
        if operation == "SORT":
            if set(params) != {"FIELDS"} or params["FIELDS"] is None:
                raise UtilityError(f"{where}: only FIELDS is emulated, got {operand!r}")
            keys = _sort_keys(params["FIELDS"], table, where)
        elif operation == "INCLUDE":
            if set(params) != {"COND"} or params["COND"] is None:
                raise UtilityError(f"{where}: only COND is emulated, got {operand!r}")
            inner = _parenthesized(params["COND"], where)
            include = _ConditionParser(_split_top(inner, where), table, where).parse()
        elif operation == "OUTREC":
            if len(params) != 1 or not ({"FIELDS", "BUILD"} & set(params)):
                raise UtilityError(f"{where}: only FIELDS or BUILD is emulated, got {operand!r}")
            value = params.get("FIELDS") or params.get("BUILD")
            if value is None:
                raise UtilityError(f"{where}: FIELDS needs a list")
            outrec = _outrec_items(value, table, where)
        else:
            raise UtilityError(f"the {operation} statement is not emulated (GAP-STB-006)")
    if keys is None:
        raise UtilityError("the SORT SYSIN has no SORT statement")
    return SortSpec(keys, include, outrec)


# --------------------------------------------------------------------------
# SORT (GAP-STB-006): execution
# --------------------------------------------------------------------------


def _step_params(ctx: StepContext) -> Mapping[str, Any]:
    params = (ctx.step or {}).get("params") or {}
    if not isinstance(params, Mapping):
        raise UtilityError(f"step {ctx.step_name}: params must be a mapping")
    return params


def _lines_param(params: Mapping[str, Any], name: str, ctx: StepContext) -> list[str] | None:
    value = params.get(name)
    if value is None:
        return None
    if not isinstance(value, list) or not all(isinstance(line, str) for line in value):
        raise UtilityError(f"step {ctx.step_name}: params.{name} must be a list of lines")
    return list(value)


def _single(ctx: StepContext, ddname: str) -> ResolvedDD:
    resolved = ctx.resolve_dd(ddname)
    if len(resolved) != 1:
        raise UtilityError(f"step {ctx.step_name}: {ddname} cannot be a concatenation")
    return resolved[0]


def _opened(resolved: ResolvedDD) -> bool:
    """Whether an input DD can be opened: an existing dataset, in-stream
    data or ``DUMMY``."""
    return resolved.exists or resolved.dummy or resolved.instream is not None


def _sort_key(ref: FieldRef) -> Callable[[bytes], Any]:
    """The sort key of one control field: its bytes (``CH``, the ASCII
    collating sequence) or its numeric value (``ZD``)."""
    if ref.format == "ZD":
        return lambda record: zoned_value(ref.extract(record))
    return ref.extract


def _run_sort(ctx: StepContext, result: UtilityResult) -> int:
    lines = result.sysprint
    params = _step_params(ctx)
    sysin = _lines_param(params, "sysin", ctx)
    if sysin is None:
        raise UtilityError(f"step {ctx.step_name}: the SORT step has no SYSIN statements")
    symnames = _lines_param(params, "symnames", ctx) or []
    lines += [f"SYMNAMES: {line.rstrip()}" for line in symnames]
    lines += [line.rstrip() for line in sysin]
    spec = parse_sort(sysin, parse_symnames(symnames))
    for ddname in ("SORTIN", "SORTOUT"):
        if not ctx.has_dd(ddname):
            raise UtilityError(f"step {ctx.step_name}: the SORT step has no {ddname} DD")
    sources = ctx.resolve_dd("SORTIN")
    target = _single(ctx, "SORTOUT")
    for member in sources:
        if not _opened(member):
            lines += [
                f"ICE185A SORTIN DATA SET {member.shown} COULD NOT BE OPENED",
                "ICE052I 3 END OF DFSORT",
            ]
            return 16
    lengths = sorted({member.record_length for member in sources if not member.dummy}, key=str)
    if len(lengths) > 1:
        raise UtilityError(
            f"step {ctx.step_name}: SORTIN concatenates record lengths {lengths}; mixed lengths "
            f"are not emulated (GAP-STB-006)"
        )
    sortin_length = lengths[0] if lengths else None
    if sortin_length is not None and any(ref.end > sortin_length for ref in spec.fields()):
        lines += ["ICE027A END OF FIELD BEYOND MAXIMUM RECORD LENGTH", "ICE052I 3 END OF DFSORT"]
        return 16
    if target.path is None or not (target.exists or target.is_new):
        lines += [
            f"ICE185A SORTOUT DATA SET {target.shown} COULD NOT BE OPENED",
            "ICE052I 3 END OF DFSORT",
        ]
        return 16
    if target.organization == "ksds":
        raise UtilityError(f"step {ctx.step_name}: a VSAM SORTOUT is not emulated (GAP-STB-006)")
    records: list[bytes] = []
    for member in sources:
        ctx.check_deadline(f"while SORT read {member.shown}")
        records.extend(_read_records(ctx, member))
    selected = [
        record for record in records if spec.include is None or spec.include.matches(record)
    ]
    ordered = list(selected)
    # Least significant key first: each stable pass keeps the order of the
    # previous one among equal keys, so equal records keep their input order.
    for key in reversed(spec.keys):
        ordered.sort(key=_sort_key(key.field), reverse=key.descending)
    built = [spec.reformat(record) for record in ordered]
    built_length = spec.outrec_length or sortin_length
    length = target.record_length or built_length
    if length is None:
        raise UtilityError(f"step {ctx.step_name}: no record length is known for {target.shown}")
    if built_length is not None and length != built_length:
        lines.append(
            f"ICE171I SORTOUT LRECL OF {length} IS DIFFERENT FROM SORTIN(OUT) LRECL OF "
            f"{built_length} - RC=0"
        )
    ctx.check_deadline(f"before SORT wrote {target.shown}")
    _write_records(ctx, target, built, length)
    lines.append(f"ICE054I 0 RECORDS - IN: {len(records)}, OUT: {len(built)}")
    if spec.include is not None:
        lines.append(f"ICE055I 0 INSERT 0, DELETE {len(records) - len(selected)}")
    lines.append("ICE052I 0 END OF DFSORT")
    return 0


# --------------------------------------------------------------------------
# IEBGENER, IEFBR14 (GAP-STB-007) and SDSF (GAP-STB-008)
# --------------------------------------------------------------------------


def _run_iebgener(ctx: StepContext, result: UtilityResult) -> int:
    lines = result.sysprint
    for ddname in ("SYSUT1", "SYSUT2", "SYSIN"):
        if not ctx.has_dd(ddname):
            raise UtilityError(f"step {ctx.step_name}: the IEBGENER step has no {ddname} DD")
    sysin = ctx.resolve_dd("SYSIN")
    if _lines_param(_step_params(ctx), "sysin", ctx) or not all(item.dummy for item in sysin):
        raise UtilityError(
            f"step {ctx.step_name}: IEBGENER control statements are not emulated; SYSIN must be "
            f"DUMMY (GAP-STB-007)"
        )
    lines.append("DATA SET UTILITY - GENERATE")
    sources = ctx.resolve_dd("SYSUT1")
    if not all(_opened(member) for member in sources):
        lines.append("IEB316I DDNAME SYSUT1 CANNOT BE OPENED")
        return 12
    target = _single(ctx, "SYSUT2")
    if target.path is None or not (target.exists or target.is_new):
        lines.append("IEB316I DDNAME SYSUT2 CANNOT BE OPENED")
        return 12
    if target.organization == "ksds":
        raise UtilityError(f"step {ctx.step_name}: a VSAM SYSUT2 is not emulated (GAP-STB-007)")
    records: list[bytes] = []
    for member in sources:
        records.extend(_read_records(ctx, member))
    length = target.record_length or sources[0].record_length
    if length is None:
        raise UtilityError(f"step {ctx.step_name}: no record length is known for {target.shown}")
    _write_records(ctx, target, records, length)
    lines.append("PROCESSING ENDED AT EOD")
    return 0


def _run_iefbr14(ctx: StepContext, result: UtilityResult) -> int:
    """IEFBR14 does nothing; the system allocates its new datasets at step
    start (``MOD`` of an absent one included), and the step-end dispositions
    then catalogue or delete them."""
    for ddname in ctx.dd_names():
        for resolved in ctx.resolve_dd(ddname):
            if resolved.dataset and resolved.is_new and not resolved.exists and resolved.path:
                length = resolved.record_length or INSTREAM_RECORD_LENGTH
                ctx.store.write_sequential(resolved.path, b"", length)
                ctx.written[resolved.path] = length
    return 0


def _run_sdsf(ctx: StepContext, result: UtilityResult) -> int:
    isfin = _lines_param(_step_params(ctx), "isfin", ctx)
    if isfin is None:
        raise UtilityError(f"step {ctx.step_name}: the SDSF step has no ISFIN commands")
    result.sysprint += [f"SDSF NO-OP: {line.strip()}" for line in isfin if line.strip()]
    return 0


_RUNNERS: dict[str, Callable[[StepContext, UtilityResult], int]] = {
    "idcams": _run_idcams,
    "sort": _run_sort,
    "iebgener": _run_iebgener,
    "iefbr14": _run_iefbr14,
    "sdsf": _run_sdsf,
}
