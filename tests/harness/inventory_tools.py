"""Functional inventory of AWS CardDemo: extraction, freeze and drift check.

The inventory (``tests/inventory/inventory.yaml``) is the denominator of the
suite's functional coverage. It holds one item per source item of the
repository (programs, jobs and procs, configuration, CSD transactions, maps,
copybooks, data files) and one item per behaviour a like-for-like conversion
must reproduce (business rules, calculations, reports, interfaces, error and
recovery paths), each with the fields of the inventory item contract::

    id, kind, name, source, description, activation, level,
    scenario_ids, status, gap_ids

``scenario_ids``, ``status`` and ``gap_ids`` are rewritten by
``harness/report.py``; everything else is produced here.

Behaviour items come from two places:

* static tables transcribed from the batch, JCL and cross-job analysis, each
  row anchored to the paragraph or statement it cites, so the anchor fails
  loudly when the source moves;
* four extraction rules applied to the online programs after every ``COPY``
  is resolved (procedure-division copybooks such as ``CSUTLDPY`` contribute
  their paragraphs to the copying program): screen messages, navigation
  paths, the ``COACTUPC`` field edits, and side effects and content.

Command line (run from the repository root)::

    python tests/harness/inventory_tools.py --freeze   # generate or re-header
    python tests/harness/inventory_tools.py --check    # drift check, exit 1 on drift

The tool only reads ``app/``, ``samples/`` and ``README.md``; the UniKix zip is
read in memory with ``zipfile``. It writes nothing outside ``tests/inventory/``
and makes no network call.
"""

from __future__ import annotations

import argparse
import bisect
import datetime
import re
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from harness import (  # noqa: E402
    INVENTORY_DIR,
    PROTECTED_BASE,
    REPO_ROOT,
    HarnessError,
    dump_yaml,
    git_head,
    load_yaml,
    write_text,
)

__all__ = [
    "ACTIVATIONS",
    "AREAS",
    "INTERFACE_PATTERNS",
    "INVENTORY_FILE",
    "InventoryError",
    "ITEM_FIELDS",
    "KINDS",
    "LEVELS",
    "PROTECTED_BASE",
    "REPORT_FIELDS",
    "SOURCE_KINDS",
    "STATUSES",
    "SourceLine",
    "append_items",
    "by_id",
    "check",
    "copy_closure",
    "freeze",
    "generate_items",
    "header_from_items",
    "is_source_item",
    "item_area",
    "item_ids",
    "items",
    "load",
    "main",
    "next_id",
    "resolve_copies",
    "write",
]

# --------------------------------------------------------------------------
# Contract
# --------------------------------------------------------------------------

INVENTORY_FILE = INVENTORY_DIR / "inventory.yaml"

AREAS = (
    "SEC", "MNU", "USR", "ACT", "CRD", "TRN", "BIL", "RPT",
    "FIN", "DAT", "UTL", "CFG", "LAY", "DTA", "BAT",
)
KINDS = (
    "program", "job", "transaction", "map", "rule", "calculation",
    "report", "interface", "layout", "recovery", "config",
)
SOURCE_KINDS = ("program", "job", "config", "transaction", "map", "layout", "interface")
ACTIVATIONS = ("always", "conditional", "unreachable", "unused")
LEVELS = ("unit", "integration", "e2e")
STATUSES = ("verified", "failing", "blocked", "implemented-unexecuted", "not-covered")
ITEM_FIELDS = (
    "id", "kind", "name", "source", "description", "activation", "level",
    "scenario_ids", "status", "gap_ids",
)
# Rewritten by harness/report.py from scenarios, results and gaps.yaml; the
# drift check ignores them.
REPORT_FIELDS = ("scenario_ids", "status", "gap_ids")

ID_RE = re.compile(r"^INV-(" + "|".join(AREAS) + r")-(\d{3})$")
_GAP_RE = re.compile(r"^GAP-(DOC|DEF|STB|MIS|INF|UNC)-\d{3}$")

# Searches for external interfaces (Db2, IMS, MQ). None occurs at the
# protected base; a hit that no inventory item cites fails the drift check.
INTERFACE_PATTERNS = (
    r"EXEC\s+SQL",
    r"EXEC\s+DLI",
    r"CBLTDLI",
    r"AIBTDLI",
    r"MQPUT",
    r"MQGET",
    r"MQOPEN",
    r"\bCSQ\w*",
)
INTERFACE_SEARCH_DIRS = ("app/cbl", "app/cpy", "app/jcl", "app/proc", "app/csd")

UNIKIX_ZIP = "samples/m2/unikix/UniKix_CardDemo_runtime_v1.zip"
FCT_MEMBER = "migrated_app/catlg/tpe/fct.lst"
CSD_PATH = "app/csd/CARDDEMO.CSD"
README_PATH = "README.md"

# cobc is run with -ftab-width=4; the copybooks holding tabs (CUSTREC,
# CSLKPCDY) are read the same way.
TAB_WIDTH = 4
# CICS-supplied copybooks: not in the repository and not inventoried.
CICS_SUPPLIED_COPYBOOKS = frozenset({"DFHAID", "DFHBMSCA"})
COPYBOOK_DIRS = ("app/cpy", "app/cpy-bms")


class InventoryError(HarnessError):
    """The repository, the inventory file or an extraction anchor does not
    have the shape the inventory rules expect."""


# --------------------------------------------------------------------------
# Areas and owners of the source items
# --------------------------------------------------------------------------

# Programs per area, in inventory order. Batch units are unit level, the
# online programs (all CO*) end to end.
PROGRAM_AREAS: dict[str, tuple[str, ...]] = {
    "SEC": ("COSGN00C",),
    "MNU": ("COMEN01C", "COADM01C"),
    "USR": ("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C"),
    "ACT": ("COACTVWC", "COACTUPC"),
    "CRD": ("COCRDLIC", "COCRDSLC", "COCRDUPC"),
    "TRN": ("COTRN00C", "COTRN01C", "COTRN02C", "CBTRN01C", "CBTRN02C"),
    "BIL": ("COBIL00C",),
    "RPT": ("CORPT00C", "CBTRN03C", "CBSTM03A", "CBSTM03B"),
    "FIN": ("CBACT04C",),
    "DAT": ("CBACT01C", "CBACT02C", "CBACT03C", "CBCUS01C"),
    "UTL": ("CSUTLDTC",),
}
ONLINE_PROGRAMS = tuple(
    name for names in PROGRAM_AREAS.values() for name in names if name.startswith("CO")
)
SIGNON_PROGRAM = "COSGN00C"

# Jobs (app/jcl) and procs (app/proc) per area, in inventory order.
JOB_AREAS: dict[str, tuple[str, ...]] = {
    "TRN": ("POSTTRAN",),
    "RPT": ("TRANREPT", "CREASTMT", "PRTCATBL"),
    "FIN": ("INTCALC",),
    "DAT": (
        "ACCTFILE", "CARDFILE", "COMBTRAN", "CUSTFILE", "DALYREJS", "DEFCUST",
        "DEFGDGB", "DISCGRP", "DUSRSECJ", "READACCT", "READCARD", "READCUST",
        "READXREF", "REPTFILE", "TCATBALF", "TRANBKP", "TRANCATG", "TRANFILE",
        "TRANIDX", "TRANTYPE", "XREFFILE",
    ),
    "CFG": ("CBADMCDJ", "OPENFIL", "CLOSEFIL"),
}
PROC_AREAS: dict[str, tuple[str, ...]] = {"RPT": ("TRANREPT",), "DAT": ("REPROC",)}
CONTROL_CARD_AREAS: dict[str, tuple[str, ...]] = {"DAT": ("REPROCT",)}

# Copybooks whose lowest executing level is not the one their copy graph
# gives. CSUSR01Y is copied only by online programs, but its records are
# decoded in the DUSRSECJ job scenario, so integration executes it first.
LAYOUT_LEVEL_OVERRIDES = {"CSUSR01Y": "integration"}
LAYOUT_LEVEL_REASONS = {
    "CSUSR01Y": "Its USRSEC records are first decoded in the DUSRSECJ job scenario, the lowest level that executes it.",
}

# Fixed record lengths of the binary EBCDIC data files, from the copybooks
# and LISTCAT; the source range of these items is in records.
EBCDIC_RECORD_LENGTHS = {
    "AWS.M2.CARDDEMO.ACCDATA.PS": 300,
    "AWS.M2.CARDDEMO.ACCTDATA.PS": 300,
    "AWS.M2.CARDDEMO.CARDDATA.PS": 150,
    "AWS.M2.CARDDEMO.CARDXREF.PS": 50,
    "AWS.M2.CARDDEMO.CUSTDATA.PS": 500,
    "AWS.M2.CARDDEMO.DALYTRAN.PS": 350,
    "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT": 350,
    "AWS.M2.CARDDEMO.DISCGRP.PS": 50,
    "AWS.M2.CARDDEMO.TCATBALF.PS": 50,
    "AWS.M2.CARDDEMO.TRANCATG.PS": 60,
    "AWS.M2.CARDDEMO.TRANTYPE.PS": 60,
    "AWS.M2.CARDDEMO.USRSEC.PS": 80,
}
# EBCDIC files whose fixtures only job scenarios load: USRSEC.PS is the
# cross-checked source of the DUSRSECJ users, DALYTRAN.PS.INIT the
# TRANSACT-INIT of the TRANFILE job and the job chain.
DATA_LEVEL_OVERRIDES = {
    "AWS.M2.CARDDEMO.USRSEC.PS": "integration",
    "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT": "integration",
}


# --------------------------------------------------------------------------
# Source reading and COPY resolution
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class SourceLine:
    """One code line of a COBOL source, after COPY resolution.

    ``text`` is columns 8-72 exactly as written, so a literal continued on the
    next line keeps its trailing spaces up to column 72.
    """

    path: str  # repository-relative path of the file the line comes from
    line: int  # 1-based line number in that file
    text: str
    area_a: bool  # columns 8-11 hold code: a paragraph name or an 01 entry
    continued: bool  # column 7 is '-': the line continues a literal


def _relative(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        raise InventoryError(f"{path} lies outside the repository {root}") from None


def read_lines(path: Path) -> list[str]:
    """The lines of a text file read as latin-1, without line terminators or
    ``\\r``, with tabs expanded to the cobc tab width."""
    lines = path.read_text(encoding="latin-1").split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return [line.rstrip("\r").expandtabs(TAB_WIDTH) for line in lines]


def code_lines(path: Path, repo_root: Path = REPO_ROOT) -> list[SourceLine]:
    """The code lines of a fixed-format COBOL file: comment lines (``*``,
    ``/``, debugging ``D`` in column 7) and blank code areas are dropped."""
    rel = _relative(path, repo_root)
    result = []
    for number, raw in enumerate(read_lines(path), start=1):
        indicator = raw[6:7]
        if indicator in ("*", "/", "D", "d"):
            continue
        text = raw[7:72]
        if not text.strip():
            continue
        result.append(
            SourceLine(rel, number, text, bool(raw[7:11].strip()), indicator == "-")
        )
    return result


def _mask_literals(lines: Sequence[SourceLine]) -> list[str]:
    """Each line's text with the characters inside literals replaced by
    ``x``, so keywords and separator periods can be found outside them. A
    literal left open at column 72 resumes after the first quote of the next
    (continuation) line."""
    masked = []
    open_quote: str | None = None
    for source_line in lines:
        text = source_line.text
        chars = list(text)
        position = 0
        quote: str | None = None
        if open_quote is not None:
            resume = text.find(open_quote) if source_line.continued else -1
            if resume < 0:
                raise InventoryError(
                    f"{source_line.path}:{source_line.line}: literal opened on the "
                    f"previous line is not continued"
                )
            position, quote = resume + 1, open_quote
        while position < len(text):
            char = text[position]
            if quote is not None:
                if char == quote:
                    if position + 1 < len(text) and text[position + 1] == quote:
                        chars[position] = chars[position + 1] = "x"
                        position += 2
                        continue
                    quote = None
                else:
                    chars[position] = "x"
            elif char in "'\"":
                quote = char
            position += 1
        open_quote = quote
        masked.append("".join(chars))
    if open_quote is not None and lines:
        last = lines[-1]
        raise InventoryError(f"{last.path}:{last.line}: literal is not closed")
    return masked


_COPY_RE = re.compile(r"(?<![A-Za-z0-9-])COPY\s+(['\"]?)([A-Za-z0-9][A-Za-z0-9-]*)\1", re.I)
_PERIOD_RE = re.compile(r"\.(?=\s|$)")
_PSEUDO_PAIR_RE = re.compile(r"==(.*?)==\s+BY\s+==(.*?)==", re.S | re.I)


def find_copybook(name: str, repo_root: Path = REPO_ROOT) -> Path:
    """The copybook ``name`` in ``app/cpy`` or ``app/cpy-bms``, matched
    without regard to case, with extension ``.cpy`` in any case."""
    wanted = name.upper()
    for directory in COPYBOOK_DIRS:
        folder = repo_root / directory
        if not folder.is_dir():
            continue
        for candidate in sorted(folder.iterdir()):
            if candidate.suffix.lower() == ".cpy" and candidate.stem.upper() == wanted:
                return candidate
    raise InventoryError(f"copybook {name} not found in {', '.join(COPYBOOK_DIRS)}")


def _normalise_pseudo_text(text: str) -> str:
    return " ".join(text.split())


def _apply_replacing(text: str, replacing: Sequence[tuple[str, str]]) -> str:
    # The pseudo-texts the repository uses (CSSETATY's "(TESTVAR1)" and
    # friends) are single text words replaced inside data names, which a plain
    # substring replacement reproduces.
    for old, new in replacing:
        text = text.replace(old, new)
    return text


def _resolve(
    path: Path,
    repo_root: Path,
    replacing: Sequence[tuple[str, str]],
    stack: tuple[str, ...],
    reached: set[Path],
) -> list[SourceLine]:
    lines = code_lines(path, repo_root)
    if replacing:
        lines = [
            SourceLine(ln.path, ln.line, _apply_replacing(ln.text, replacing), ln.area_a, ln.continued)
            for ln in lines
        ]
    masked = _mask_literals(lines)
    result: list[SourceLine] = []
    index = 0
    while index < len(lines):
        source_line, mask = lines[index], masked[index]
        match = _COPY_RE.search(mask)
        if match is None:
            result.append(source_line)
            index += 1
            continue
        name = source_line.text[match.start(2):match.end(2)].upper()
        # The COPY statement runs to the first separator period, possibly on a
        # later line (REPLACING clauses, or a period written on its own line).
        statement = [source_line.text[match.end():]]
        statement_mask = [mask[match.end():]]
        last = index
        while _PERIOD_RE.search(statement_mask[-1]) is None:
            last += 1
            if last >= len(lines):
                raise InventoryError(
                    f"{source_line.path}:{source_line.line}: COPY {name} has no ending period"
                )
            statement.append(lines[last].text)
            statement_mask.append(masked[last])
        period = _PERIOD_RE.search(statement_mask[-1]).start()
        clause = " ".join(statement[:-1] + [statement[-1][:period]])
        trailing = statement[-1][period + 1:]
        if last == index:
            trailing = source_line.text[match.end() + period + 1:]
        pairs: list[tuple[str, str]] = []
        clause_words = clause.split()
        if clause_words and clause_words[0].upper() == "REPLACING":
            pairs = [
                (_normalise_pseudo_text(old), _normalise_pseudo_text(new))
                for old, new in _PSEUDO_PAIR_RE.findall(clause)
            ]
            if not pairs:
                raise InventoryError(
                    f"{source_line.path}:{source_line.line}: COPY {name} REPLACING "
                    f"holds no ==pseudo-text== BY ==pseudo-text== pair"
                )
        elif clause.strip():
            raise InventoryError(
                f"{source_line.path}:{source_line.line}: unexpected text after COPY {name}: {clause.strip()!r}"
            )
        leading = source_line.text[:match.start()]
        if leading.strip():
            result.append(SourceLine(source_line.path, source_line.line, leading, source_line.area_a, False))
        if name not in CICS_SUPPLIED_COPYBOOKS:
            if name in stack:
                raise InventoryError(f"COPY cycle: {' -> '.join(stack + (name,))}")
            copybook = find_copybook(name, repo_root)
            reached.add(copybook.resolve())
            result.extend(_resolve(copybook, repo_root, pairs, stack + (name,), reached))
        if trailing.strip():
            end_line = lines[last]
            result.append(SourceLine(end_line.path, end_line.line, trailing, False, False))
        index = last + 1
    return result


def resolve_copies(program_path: str | Path, repo_root: Path = REPO_ROOT) -> list[SourceLine]:
    """The code lines of ``program_path`` with every ``COPY`` statement
    replaced, recursively, by the copybook it names (``REPLACING`` applied).

    Each line keeps the file and line it came from, so behaviour carried by a
    procedure-division copybook (``CSUTLDPY``, ``CSSTRPFY``, ``CSSETATY``) is
    owned by the copying program but cited at the copybook. ``DFHAID`` and
    ``DFHBMSCA`` are CICS-supplied and skipped.
    """
    path = Path(program_path)
    if not path.is_absolute():
        path = repo_root / path
    stem = path.stem.upper()
    return _resolve(path, repo_root, (), (stem,), set())


def copy_closure(path: str | Path, repo_root: Path = REPO_ROOT) -> list[Path]:
    """The program (or copybook) at ``path`` and every copybook its COPY
    statements reach, as absolute paths, sorted."""
    source = Path(path)
    if not source.is_absolute():
        source = repo_root / source
    reached: set[Path] = {source.resolve()}
    _resolve(source, repo_root, (), (source.stem.upper(),), reached)
    return sorted(reached)


# --------------------------------------------------------------------------
# Tokens and the program model
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Token:
    text: str
    word: str  # upper-case text of a word or punctuation; "" for a literal
    value: str  # content of a literal; "" otherwise
    path: str
    line: int
    first: bool  # first token of its source line
    area_a: bool  # its source line starts in area A

    @property
    def literal(self) -> bool:
        return not self.word


_TOKEN_RE = re.compile(
    r"""(?P<lit>'(?:[^']|'')*'|"(?:[^"]|"")*")"""
    r"""|(?P<open>['"].*$)"""
    r"""|(?P<num>[+-]?\d+\.\d+(?![A-Za-z0-9-]))"""
    r"""|(?P<word>[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)"""
    r"""|(?P<punct>==|\S)"""
)
_PICTURE_RE = re.compile(r"\s*(?:IS\s+)?(\S+?)(\.?)(?=\s|$)", re.I)


def _literal_value(text: str) -> str:
    quote = text[0]
    return text[1:-1].replace(quote * 2, quote)


def tokenize(lines: Sequence[SourceLine]) -> list[Token]:
    """Split resolved code lines into COBOL tokens: literals (continuations
    joined, value unescaped), words, decimal numbers, picture strings and
    punctuation."""
    tokens: list[Token] = []
    pending: tuple[str, SourceLine] | None = None  # literal open at column 72
    for source_line in lines:
        text = source_line.text
        position = 0
        first = True
        if pending is not None:
            partial, origin = pending
            quote = partial[0]
            resume = text.find(quote) if source_line.continued else -1
            if resume < 0:
                raise InventoryError(f"{origin.path}:{origin.line}: literal is not continued")
            rest = text[resume + 1:]
            closing = re.match(r"(?:[^%s]|%s%s)*%s" % ((re.escape(quote),) * 4), rest)
            if closing is None:
                pending = (partial + rest, origin)
                continue
            literal = partial + closing.group(0)
            tokens.append(Token(literal, "", _literal_value(literal), origin.path, origin.line, False, origin.area_a))
            position = resume + 1 + closing.end()
            pending = None
            first = False
        while position < len(text):
            match = _TOKEN_RE.search(text, position)
            if match is None:
                break
            position = match.end()
            kind = match.lastgroup
            if kind == "open":
                # Continued literal: the area up to column 72 belongs to it.
                pending = (match.group(0).ljust(72 - 7 - match.start()), source_line)
                break
            raw = match.group(0)
            origin = (source_line.path, source_line.line)
            if kind == "lit":
                token = Token(raw, "", _literal_value(raw), *origin, first, source_line.area_a)
            else:
                token = Token(raw, raw.upper(), "", *origin, first, source_line.area_a)
            tokens.append(token)
            first = False
            if token.word in ("PIC", "PICTURE"):
                picture = _PICTURE_RE.match(text, position)
                if picture is not None:
                    text_ = picture.group(1)
                    tokens.append(Token(text_, text_.upper(), "", *origin, False, source_line.area_a))
                    if picture.group(2):
                        tokens.append(Token(".", ".", "", *origin, False, source_line.area_a))
                    position = picture.end()
    if pending is not None:
        origin = pending[1]
        raise InventoryError(f"{origin.path}:{origin.line}: literal is not closed")
    return tokens


# Words that begin or end a statement. A MOVE target list or a STRING
# element list stops at the first of them.
STATEMENT_WORDS = frozenset(
    """
    ACCEPT ADD ALTER CALL CANCEL CLOSE COMPUTE CONTINUE DELETE DISPLAY DIVIDE
    ELSE EVALUATE EXEC EXIT GO GOBACK IF INITIALIZE INSPECT MERGE MOVE
    MULTIPLY NEXT OPEN PERFORM READ RELEASE RETURN REWRITE SEARCH SET SORT
    START STOP STRING SUBTRACT UNSTRING WHEN WRITE
    END-ADD END-CALL END-COMPUTE END-DELETE END-DIVIDE END-EVALUATE END-EXEC
    END-IF END-MULTIPLY END-PERFORM END-READ END-RETURN END-REWRITE
    END-SEARCH END-START END-STRING END-SUBTRACT END-UNSTRING END-WRITE
    """.split()
)


@dataclass(frozen=True)
class Paragraph:
    name: str
    start: int  # index of the name token
    end: int  # index one past the paragraph's last token
    path: str
    line: int


@dataclass(frozen=True)
class DataEntry:
    level: int
    name: str
    start: int  # index of the level-number token
    end: int  # index of the entry's closing period
    values: tuple[Token, ...]  # VALUE/VALUES literals, in order
    number: str | None  # a numeric VALUE, as written
    parent: str | None  # nearest enclosing entry of a lower level


@dataclass(frozen=True)
class StringStatement:
    start: int
    end: int  # last token of the statement
    target: str
    parts: tuple[str, ...]  # literal text, or <NAME> for an identifier
    names: tuple[str, ...]  # identifiers the statement concatenates


class CobolProgram:
    """A program after COPY resolution, tokenized, with its data entries and
    procedure-division paragraphs."""

    def __init__(self, name: str, path: str, lines: list[SourceLine]):
        self.name = name
        self.path = path
        self.lines = lines
        self.tokens = tokenize(lines)
        self.words = [token.word for token in self.tokens]
        self.proc_start = self._procedure_start()
        self.data = self._data_entries()
        self.data_by_name: dict[str, list[DataEntry]] = {}
        for entry in self.data:
            self.data_by_name.setdefault(entry.name, []).append(entry)
        self.paragraphs = self._paragraphs()
        self.paragraph_index = {para.name: number for number, para in enumerate(self.paragraphs)}
        self._paragraph_starts = [para.start for para in self.paragraphs]
        self.word_counts = Counter(word for word in self.words if word)

    # -- structure ---------------------------------------------------------

    def find_words(self, *words: str, start: int = 0, stop: int | None = None) -> int:
        stop = len(self.tokens) if stop is None else stop
        width = len(words)
        for index in range(start, stop - width + 1):
            if all(self.words[index + k] == words[k] for k in range(width)):
                return index
        return -1

    def _procedure_start(self) -> int:
        index = self.find_words("PROCEDURE", "DIVISION")
        if index < 0:
            raise InventoryError(f"{self.path}: no PROCEDURE DIVISION")
        while index < len(self.tokens) and self.words[index] != ".":
            index += 1
        return index + 1

    def _data_entries(self) -> list[DataEntry]:
        begin = self.find_words("DATA", "DIVISION", stop=self.proc_start)
        if begin < 0:
            return []
        entries: list[DataEntry] = []
        open_levels: list[tuple[int, str]] = []
        index = begin
        while index < self.proc_start:
            token = self.tokens[index]
            starts_entry = token.word.isdigit() and self.words[index - 1] == "."
            if not starts_entry or index + 1 >= self.proc_start:
                index += 1
                continue
            level = int(token.word)
            name = self.words[index + 1] or "FILLER"
            end = index + 1
            while end < self.proc_start and self.words[end] != ".":
                end += 1
            values: list[Token] = []
            number: str | None = None
            for position in range(index + 2, end):
                if self.words[position] in ("VALUE", "VALUES"):
                    if position + 1 < end and self.words[position + 1].isdigit():
                        number = self.words[position + 1]
                    for candidate in self.tokens[position + 1:end]:
                        if candidate.literal:
                            values.append(candidate)
                        elif candidate.word not in (",", "IS", "ARE", "THRU", "THROUGH"):
                            # A figurative constant, a number or the next
                            # clause ends the list of literals.
                            break
                    break
            if level in (66, 77) or level == 1:
                open_levels = []
            while open_levels and level != 88 and open_levels[-1][0] >= level:
                open_levels.pop()
            parent = open_levels[-1][1] if open_levels else None
            entries.append(DataEntry(level, name, index, end, tuple(values), number, parent))
            if level not in (66, 77, 88):
                open_levels.append((level, name))
            index = end + 1
        return entries

    def _paragraphs(self) -> list[Paragraph]:
        starts: list[tuple[int, str]] = []
        for index in range(self.proc_start, len(self.tokens) - 1):
            token = self.tokens[index]
            if not (token.first and token.area_a and token.word) or token.word in STATEMENT_WORDS:
                continue
            following = self.words[index + 1]
            if following == "." or (following == "SECTION" and self.words[index + 2:index + 3] == ["."]):
                starts.append((index, token.word))
        paragraphs = []
        for number, (start, name) in enumerate(starts):
            end = starts[number + 1][0] if number + 1 < len(starts) else len(self.tokens)
            token = self.tokens[start]
            paragraphs.append(Paragraph(name, start, end, token.path, token.line))
        return paragraphs

    def paragraph_at(self, index: int) -> Paragraph | None:
        number = bisect.bisect_right(self._paragraph_starts, index) - 1
        return self.paragraphs[number] if number >= 0 else None

    def paragraph(self, name: str) -> Paragraph:
        try:
            return self.paragraphs[self.paragraph_index[name.upper()]]
        except KeyError:
            raise InventoryError(f"{self.path}: paragraph {name} not found") from None

    def constant(self, name: str) -> str | None:
        """The first VALUE literal of the data item ``name``, if it has one."""
        for entry in self.data_by_name.get(name.upper(), ()):
            if entry.values:
                return entry.values[0].value
        return None

    # -- statements --------------------------------------------------------

    def statement_end(self, index: int) -> int:
        """Index of the last token of the statement that starts at ``index``:
        the token before the next statement word or separator period, or the
        END-EXEC of an EXEC block."""
        if self.words[index] == "EXEC":
            end = self.find_words("END-EXEC", start=index)
            if end < 0:
                raise InventoryError(f"{self.path}: EXEC at line {self.tokens[index].line} has no END-EXEC")
            return end
        position = index + 1
        while position < len(self.tokens):
            word = self.words[position]
            if word == "." or word in STATEMENT_WORDS:
                return position - 1
            position += 1
        return len(self.tokens) - 1

    def operands(self, start: int, stop: int) -> tuple[list[str], int]:
        """Data names from ``start`` up to ``stop`` or the next statement word
        or literal, skipping ``OF``/``IN`` qualifiers and parenthesised
        subscripts and reference modifiers. Returns the names and the index
        after the last one consumed."""
        names: list[str] = []
        position = start
        while position < stop:
            token = self.tokens[position]
            word = token.word
            if token.literal or word in STATEMENT_WORDS or word in (".", "DELIMITED", "INTO"):
                break
            if word in ("OF", "IN"):
                position += 2
                continue
            if word == "(":
                position = self.close_paren(position) + 1
                continue
            if word != ",":
                names.append(word)
            position += 1
        return names, position

    def close_paren(self, index: int) -> int:
        depth = 0
        for position in range(index, len(self.tokens)):
            if self.words[position] == "(":
                depth += 1
            elif self.words[position] == ")":
                depth -= 1
                if depth == 0:
                    return position
        raise InventoryError(f"{self.path}: unbalanced parenthesis at line {self.tokens[index].line}")

    def moves(self) -> list[tuple[int, int, Token, list[str]]]:
        """Every ``MOVE`` of the procedure division as (start, end, sending
        token, receiving data names)."""
        result = []
        for index in range(self.proc_start, len(self.tokens) - 2):
            if self.words[index] != "MOVE":
                continue
            to_index = index + 1
            while to_index < len(self.tokens) and self.words[to_index] != "TO":
                if self.words[to_index] in STATEMENT_WORDS or self.words[to_index] == ".":
                    to_index = -1
                    break
                to_index += 1
            if to_index < 0 or to_index >= len(self.tokens):
                continue
            targets, after = self.operands(to_index + 1, len(self.tokens))
            result.append((index, max(after - 1, to_index), self.tokens[index + 1], targets))
        return result

    def strings(self) -> list[StringStatement]:
        """Every ``STRING`` statement of the procedure division."""
        result = []
        for index in range(self.proc_start, len(self.tokens)):
            if self.words[index] != "STRING":
                continue
            parts: list[str] = []
            names: list[str] = []
            position = index + 1
            while position < len(self.tokens) and self.words[position] != "INTO":
                token = self.tokens[position]
                word = token.word
                if word == "." or (word in STATEMENT_WORDS and word != "STRING"):
                    raise InventoryError(f"{self.path}:{token.line}: STRING without INTO")
                if word == "DELIMITED":
                    position += 2 if self.words[position + 1] == "BY" else 1
                    if self.words[position] in ("ALL",):
                        position += 1
                    position += 1
                    continue
                if token.literal:
                    parts.append(token.value)
                    position += 1
                    continue
                if word == "FUNCTION":
                    position += 2
                    if self.words[position] == "(":
                        inner = [w for w in self.words[position + 1:self.close_paren(position)] if w and w[0].isalpha()]
                        name = inner[0] if inner else self.words[position - 1]
                        parts.append(f"<{name}>")
                        names.append(name)
                        position = self.close_paren(position) + 1
                    continue
                if word in ("OF", "IN"):
                    position += 2
                    continue
                if word == "(":
                    position = self.close_paren(position) + 1
                    continue
                if word != ",":
                    parts.append(f"<{word}>")
                    names.append(word)
                position += 1
            if position >= len(self.tokens):
                raise InventoryError(f"{self.path}: STRING at line {self.tokens[index].line} has no INTO")
            targets, after = self.operands(position + 1, len(self.tokens))
            end = after - 1
            if after < len(self.tokens) and self.words[after] == "END-STRING":
                end = after
            result.append(StringStatement(index, end, targets[0] if targets else "", tuple(parts), tuple(names)))
        return result

    def exec_blocks(self) -> list[tuple[int, int]]:
        """(start, end) token indexes of every ``EXEC ... END-EXEC`` block."""
        blocks = []
        index = self.proc_start
        while index < len(self.tokens):
            if self.words[index] == "EXEC":
                end = self.statement_end(index)
                blocks.append((index, end))
                index = end + 1
            else:
                index += 1
        return blocks

    def span(self, start: int, end: int) -> tuple[str, int, int]:
        """(path, first line, last line) of tokens ``start``..``end``, in the
        file of the first token."""
        path = self.tokens[start].path
        lines = [token.line for token in self.tokens[start:end + 1] if token.path == path]
        return path, min(lines), max(lines)

    def paragraph_span(self, name: str) -> tuple[str, int, int]:
        para = self.paragraph(name)
        return self.span(para.start, para.end - 1)

    def own_lines(self) -> list[SourceLine]:
        """The program's lines that come from its own file, not a copybook."""
        return [line for line in self.lines if line.path == self.path]


def cite(path: str, ranges: Iterable[tuple[int, int]]) -> str:
    """``path:lines`` with the line ranges sorted and merged, a single line
    written as ``n`` and a range as ``first-last``."""
    merged: list[list[int]] = []
    for first, last in sorted(ranges):
        if merged and first <= merged[-1][1] + 1:
            merged[-1][1] = max(merged[-1][1], last)
        else:
            merged.append([first, last])
    if not merged:
        raise InventoryError(f"no line range to cite in {path}")
    text = ",".join(f"{a}" if a == b else f"{a}-{b}" for a, b in merged)
    return f"{path}:{text}"


def cite_span(span: tuple[str, int, int]) -> str:
    path, first, last = span
    return cite(path, [(first, last)])


def _line_list(lines: Iterable[int]) -> str:
    ordered = sorted(set(lines))
    return ", ".join(str(n) for n in ordered)


# --------------------------------------------------------------------------
# Online rule 3: COACTUPC field edits
# --------------------------------------------------------------------------

FIELD_NAME_VARIABLE = "WS-EDIT-VARIABLE-NAME"
FIELD_EDIT_MESSAGE = "WS-RETURN-MSG"

# Predicate labels of each field-edit routine, in the textual order of the
# routine's STRING INTO WS-RETURN-MSG statements (one per predicate). The
# extraction finds the statements; the table only names them. A routine
# holding more statements than labels falls back to the message text.
PREDICATE_LABELS: dict[str, tuple[str, ...]] = {
    "1215-EDIT-MANDATORY": ("supplied",),
    "1220-EDIT-YESNO": ("supplied", "Y or N"),
    "1225-EDIT-ALPHA-REQD": ("supplied", "alphabets only"),
    "1230-EDIT-ALPHANUM-REQD": ("supplied", "numbers or alphabets only"),
    "1235-EDIT-ALPHA-OPT": ("alphabets only",),
    "1240-EDIT-ALPHANUM-OPT": ("numbers or alphabets only",),
    "1245-EDIT-NUM-REQD": ("supplied", "all numeric", "not zero"),
    "1250-EDIT-SIGNED-9V2": ("supplied", "valid signed amount"),
    "EDIT-AREA-CODE": (
        "area code supplied",
        "area code a 3-digit number",
        "area code not zero",
        "area code a North America general-purpose code",
    ),
    "EDIT-US-PHONE-PREFIX": ("prefix supplied", "prefix a 3-digit number", "prefix not zero"),
    "EDIT-US-PHONE-LINENUM": (
        "line number supplied",
        "line number a 4-digit number",
        "line number not zero",
    ),
    "1265-EDIT-US-SSN": ("not 000, 666 or 900-999",),
    "1270-EDIT-US-STATE-CD": ("a valid state code",),
    "1275-EDIT-FICO-SCORE": ("300 to 850",),
    "1280-EDIT-US-STATE-ZIP-CD": ("zip code valid for the state",),
    "EDIT-YEAR-CCYY": ("year supplied", "year a 4-digit number", "century 19 or 20"),
    "EDIT-MONTH": ("month supplied", "month 1-12", "month numeric"),
    "EDIT-DAY": ("day supplied", "day numeric", "day 1-31"),
    "EDIT-DAY-MONTH-YEAR": (
        "no 31st in a 30-day month",
        "no 30th of February",
        "no 29th of February outside a leap year",
    ),
    "EDIT-DATE-LE": ("CSUTLDTC severity 0 for mask YYYYMMDD",),
    "EDIT-DATE-OF-BIRTH": ("earlier than the run date",),
}
# Cross-field edits compose their message without the field name.
CROSS_FIELD_LABELS = {"1280-EDIT-US-STATE-ZIP-CD": "State with Zip"}


@dataclass(frozen=True)
class Predicate:
    field: str | None  # None: the routine is performed for no field
    routine: str  # paragraph holding the STRING statement
    ordinal: int  # position among that paragraph's predicates
    label: str
    message: str  # the composed text, field name included when used
    names_field: bool
    span: tuple[str, int, int]


def _message_strings(program: CobolProgram, target: str) -> dict[str, list[StringStatement]]:
    by_paragraph: dict[str, list[StringStatement]] = {}
    for statement in program.strings():
        if statement.target == target:
            para = program.paragraph_at(statement.start)
            if para is not None:
                by_paragraph.setdefault(para.name, []).append(statement)
    return by_paragraph


def _predicate_label(routine: str, ordinal: int, statement: StringStatement) -> str:
    labels = PREDICATE_LABELS.get(routine, ())
    if ordinal < len(labels):
        return labels[ordinal]
    return "".join(p for p in statement.parts if not p.startswith("<")).strip(" :")


def field_edit_predicates(program: CobolProgram) -> tuple[list[Predicate], set[str]]:
    """The (field, predicate) pairs of a program whose edit routines take the
    field name in ``WS-EDIT-VARIABLE-NAME``, and the paragraphs that compose
    those messages.

    The walk starts at the paragraph holding the first ``MOVE '<field>' TO
    WS-EDIT-VARIABLE-NAME`` and follows ``PERFORM x [THRU y]`` (a range of
    paragraphs in source order) in textual order. Every ``STRING ... INTO
    WS-RETURN-MSG`` met while a field name is set is one predicate of that
    field; a ``MOVE`` inside a routine renames the field (the SSN parts).
    Routines composing such messages that nothing performs yield predicates
    with no field.
    """
    label_moves = [
        index for index, _end, sending, targets in program.moves()
        if sending.literal and targets[:1] == [FIELD_NAME_VARIABLE]
    ]
    if not label_moves:
        return [], set()
    strings = _message_strings(program, FIELD_EDIT_MESSAGE)
    ordinal_of = {
        statement.start: (para, number)
        for para, statements in strings.items()
        for number, statement in enumerate(statements)
    }
    string_at = {statement.start: statement for statements in strings.values() for statement in statements}
    predicates: list[Predicate] = []
    seen: set[tuple[str | None, int]] = set()
    composing: set[str] = set()
    state: dict[str, str | None] = {"field": None}

    def walk_range(first: int, last: int, stack: tuple[str, ...]) -> None:
        for para in program.paragraphs[first:last + 1]:
            walk(para, stack)

    def walk(para: Paragraph, stack: tuple[str, ...]) -> None:
        if para.name in stack:
            return
        position = para.start + 2
        while position < para.end:
            word = program.words[position]
            if (
                word == "MOVE"
                and program.tokens[position + 1].literal
                and program.words[position + 2] == "TO"
                and program.words[position + 3] == FIELD_NAME_VARIABLE
            ):
                state["field"] = program.tokens[position + 1].value.strip()
                position += 4
                continue
            if word == "PERFORM" and program.words[position + 1] in program.paragraph_index:
                first = program.paragraph_index[program.words[position + 1]]
                last = first
                position += 2
                if program.words[position] in ("THRU", "THROUGH"):
                    last = program.paragraph_index.get(program.words[position + 1], first)
                    position += 2
                walk_range(first, last, stack + (para.name,))
                continue
            if word == "STRING" and position in string_at and state["field"] is not None:
                statement = string_at[position]
                routine, ordinal = ordinal_of[position]
                names_field = FIELD_NAME_VARIABLE in statement.names
                field_name = state["field"] if names_field else CROSS_FIELD_LABELS.get(routine, routine)
                key = (field_name, statement.start)
                if key not in seen:
                    seen.add(key)
                    composing.add(routine)
                    template = "".join(p for p in statement.parts if p != f"<{FIELD_NAME_VARIABLE}>")
                    message = f"{field_name}{template}" if names_field else template
                    predicates.append(
                        Predicate(
                            field_name, routine, ordinal, _predicate_label(routine, ordinal, statement),
                            message, names_field, program.span(statement.start, statement.end),
                        )
                    )
                position = statement.end + 1
                continue
            position += 1

    walk(program.paragraph_at(label_moves[0]), ())
    performed = {
        program.words[index + 1]
        for index in range(program.proc_start, len(program.tokens) - 1)
        if program.words[index] == "PERFORM"
    }
    for para_name, statements in strings.items():
        if para_name in composing or para_name in performed:
            continue
        if not any(FIELD_NAME_VARIABLE in statement.names for statement in statements):
            continue
        composing.add(para_name)
        for ordinal, statement in enumerate(statements):
            template = "".join(p for p in statement.parts if p != f"<{FIELD_NAME_VARIABLE}>")
            predicates.append(
                Predicate(
                    None, para_name, ordinal, _predicate_label(para_name, ordinal, statement),
                    template, True, program.span(statement.start, statement.end),
                )
            )
    return predicates, composing


# --------------------------------------------------------------------------
# Online rule 1: screen messages
# --------------------------------------------------------------------------

MESSAGE_FIELDS = frozenset(
    {"WS-MESSAGE", "WS-RETURN-MSG", "ERRMSGO", "WS-ERROR-MSG", "CCARD-ERROR-MSG", "WS-INFO-MSG", "ABEND-MSG"}
)
MESSAGE_88_PARENTS = frozenset({"WS-RETURN-MSG", "WS-INFO-MSG", "WS-ERROR-MSG"})
MIN_MESSAGE_LENGTH = 6
COMMON_MESSAGE_COPYBOOK = "app/cpy/CSMSG01Y.cpy"
FILE_ERROR_FIELD = "ERROR-FILE"
FILE_ERROR_OPERATION = "ERROR-OPNAME"
FILE_ERROR_MESSAGE = "WS-FILE-ERROR-MESSAGE"

# A message is a recovery item when it reports a record not found, a lookup
# or I/O failure, a lock or concurrency failure, a failed update, or an
# abend. A duplicate key on WRITE ('... already exist') and an empty browse
# for a filter ('NO RECORDS FOUND FOR THIS SEARCH CONDITION.') are business
# outcomes of the input, so they stay rules.
_RECOVERY_MESSAGE_RE = re.compile(
    r"not\s+found|did\s+not\s+find|unable\s+to|error\s+reading|file\s+error"
    r"|could\s+not\s+lock|changed\s+by\s+some\s*one|failed|unsuccessful|abend|unexpected",
    re.I,
)


@dataclass
class Message:
    text: str
    origin: str  # literal, condition, common, string, file-error
    position: int  # token index of the first site, for ordering
    span: tuple[str, int, int]  # first site
    sites: list[int] = field(default_factory=list)  # lines in the program's own file
    targets: set[str] = field(default_factory=set)
    conditions: list[str] = field(default_factory=list)
    condition_lines: list[tuple[str, int, int]] = field(default_factory=list)
    unused: bool = False
    context: str = ""  # paragraph of a STRING or file-error site
    constant: str = ""  # CSMSG01Y constant name


def classify_message(message: Message) -> str:
    if message.origin == "file-error" or "ABEND-MSG" in message.targets:
        return "recovery"
    return "recovery" if _RECOVERY_MESSAGE_RE.search(message.text) else "rule"


def _condition_references(program: CobolProgram, name: str) -> int:
    definitions = len(program.data_by_name.get(name, ()))
    return program.word_counts[name] - definitions


def _governing_command(program: CobolProgram, index: int, lower: int) -> tuple[str, int] | None:
    """The CICS command whose RESP the EVALUATE enclosing ``index`` tests:
    the EXEC block just before that EVALUATE, as (verb, line)."""
    depth = 0
    for position in range(index - 1, lower - 1, -1):
        word = program.words[position]
        if word == "END-EVALUATE":
            depth += 1
        elif word == "EVALUATE":
            if depth == 0:
                for back in range(position - 1, lower - 1, -1):
                    if program.words[back] == "EXEC" and program.words[back + 1] == "CICS":
                        verb = program.words[back + 2]
                        return verb, program.tokens[back + 2].line
                return None
            depth -= 1
    return None


def online_messages(program: CobolProgram, field_edit_paragraphs: set[str]) -> list[Message]:
    """The screen messages of an online program (rule 1), in source order."""
    keyed: dict[str, Message] = {}
    per_site: list[Message] = []

    def in_field_edit(index: int) -> bool:
        para = program.paragraph_at(index)
        return para is not None and para.name in field_edit_paragraphs

    # Literal messages of 88-level conditions under the message fields.
    for entry in program.data:
        if entry.level != 88 or entry.parent not in MESSAGE_88_PARENTS:
            continue
        for value in entry.values:
            text = value.value.rstrip()
            if len(text) < MIN_MESSAGE_LENGTH:
                continue
            span = program.span(entry.start, entry.end)
            message = keyed.get(text)
            if message is None:
                message = keyed[text] = Message(text, "condition", entry.start, span)
            message.conditions.append(entry.name)
            message.condition_lines.append(span)
            message.targets.add(entry.parent)

    common = {
        entry.name: entry.values[0].value.rstrip()
        for entry in program.data
        if entry.values and program.tokens[entry.start].path == COMMON_MESSAGE_COPYBOOK
    }
    for start, end, sending, targets in program.moves():
        if not MESSAGE_FIELDS.intersection(targets) or in_field_edit(start):
            continue
        if sending.literal:
            text, origin, constant = sending.value.rstrip(), "literal", ""
        elif sending.word in common:
            text, origin, constant = common[sending.word], "common", sending.word
        else:
            continue
        if len(text) < MIN_MESSAGE_LENGTH:
            continue
        span = program.span(start, end)
        message = keyed.get(text)
        if message is None:
            message = keyed[text] = Message(text, origin, start, span, constant=constant)
        elif message.origin == "condition":
            # Moved as a literal as well: the text is reachable whatever the
            # conditions' use.
            message.origin = origin
            message.position, message.span = start, span
            message.constant = constant or message.constant
        message.sites.append(span[1])
        message.targets.update(t for t in targets if t in MESSAGE_FIELDS)

    for message in keyed.values():
        if message.origin == "condition":
            message.unused = all(_condition_references(program, name) == 0 for name in message.conditions)

    for statement in program.strings():
        if statement.target not in MESSAGE_FIELDS or in_field_edit(statement.start):
            continue
        para = program.paragraph_at(statement.start)
        span = program.span(statement.start, statement.end)
        per_site.append(
            Message(
                "".join(statement.parts), "string", statement.start, span,
                sites=[span[1]], targets={statement.target}, context=para.name if para else "",
            )
        )

    moves = program.moves()
    for number, (start, end, sending, targets) in enumerate(moves):
        if targets[:1] != [FILE_ERROR_FIELD]:
            continue
        para = program.paragraph_at(start)
        lower = para.start if para else program.proc_start
        upper = para.end if para else len(program.tokens)
        operation, first = "", start
        for back in reversed(moves[:number]):
            if back[0] < lower:
                break
            if back[3][:1] == [FILE_ERROR_OPERATION] and back[2].literal:
                operation, first = back[2].value.strip(), back[0]
                break
        shown = next(
            (m for m in moves[number + 1:] if m[0] < upper and m[2].word == FILE_ERROR_MESSAGE
             and MESSAGE_FIELDS.intersection(m[3])),
            None,
        )
        if shown is None:
            continue
        if sending.literal:
            resource = sending.value.strip()
        else:
            resource = (program.constant(sending.word) or sending.word).strip()
        text = f"File Error: {operation} on {resource} returned RESP <ERROR-RESP>,RESP2 <ERROR-RESP2>"
        span = program.span(first, shown[1])
        context = para.name if para else ""
        command = _governing_command(program, start, lower)
        if command:
            context += f", {command[0]} at line {command[1]}"
        per_site.append(
            Message(
                text, "file-error", start, span, sites=[span[1]],
                targets={t for t in shown[3] if t in MESSAGE_FIELDS}, context=context,
            )
        )
    return sorted(list(keyed.values()) + per_site, key=lambda m: m.position)


# --------------------------------------------------------------------------
# Online rule 2: navigation
# --------------------------------------------------------------------------

PF3_CONDITIONS = frozenset({"DFHPF3", "CCARD-AID-PFK03"})
PROGRAM_TARGET_FIELDS = frozenset({"CDEMO-TO-PROGRAM", "CCARD-NEXT-PROG"})
SIGNON_RETURN_PARAGRAPH = "RETURN-TO-SIGNON-SCREEN"


@dataclass(frozen=True)
class Navigation:
    label: str
    description: str
    span: tuple[str, int, int]
    gap_ids: tuple[str, ...] = ()


def _transfer_end(program: CobolProgram, start: int, stop: int) -> int:
    """Index of the last token of the first transfer of control (an ``EXEC
    CICS XCTL`` or a ``PERFORM RETURN-TO-...``) between ``start`` and
    ``stop``, or ``stop - 1`` when there is none."""
    for position in range(start, stop):
        word = program.words[position]
        if word == "XCTL":
            return program.find_words("END-EXEC", start=position)
        if word == "PERFORM" and program.words[position + 1].startswith("RETURN-TO-"):
            return position + 1
    return stop - 1


def _transfer_targets(program: CobolProgram, start: int, end: int) -> list[str]:
    """The programs control passes to between ``start`` and ``end``: the
    values moved to CDEMO-TO-PROGRAM or CCARD-NEXT-PROG, or named in XCTL
    PROGRAM(...)."""
    targets: list[str] = []

    def add(token: Token) -> None:
        if token.literal:
            name = token.value.strip()
        else:
            name = (program.constant(token.word) or "").strip() or (
                "the calling program" if token.word == "CDEMO-FROM-PROGRAM" else token.word
            )
        if name and name not in targets:
            targets.append(name)

    for move_start, _move_end, sending, receiving in program.moves():
        if start <= move_start <= end and PROGRAM_TARGET_FIELDS.intersection(receiving):
            add(sending)
    for position in range(start, end + 1):
        if program.words[position] == "XCTL" and program.words[position + 1:position + 3] == ["PROGRAM", "("]:
            token = program.tokens[position + 3]
            if token.literal or token.word not in PROGRAM_TARGET_FIELDS:
                add(token)
    return targets


def _branch_end(program: CobolProgram, if_index: int) -> int:
    """Index of the ELSE or END-IF closing the true branch of the IF at
    ``if_index`` (or of the sentence's period)."""
    depth = 0
    for position in range(if_index + 1, len(program.tokens)):
        word = program.words[position]
        if word == "IF":
            depth += 1
        elif word == "END-IF":
            if depth == 0:
                return position
            depth -= 1
        elif word == "ELSE" and depth == 0:
            return position
        elif word == ".":
            return position
    return len(program.tokens) - 1


def _menu_options(program: CobolProgram) -> list[tuple[str, str, str, str, tuple[str, int, int]]]:
    """(number, name, program, user type, span) of each option of the menu
    tables (``*-OPTIONS-DATA``) the program copies."""
    options: list[list[Any]] = []
    for entry in program.data:
        if not (entry.parent or "").endswith("-OPTIONS-DATA"):
            continue
        if entry.number is not None:
            options.append([entry.number.lstrip("0") or "0", [], entry.start, entry.end])
        elif options and entry.values:
            options[-1][1].append(entry.values[0].value)
            options[-1][3] = entry.end
    result = []
    for number, values, start, end in options:
        name = values[0].strip() if values else ""
        target = values[1].strip() if len(values) > 1 else ""
        user_type = values[2].strip() if len(values) > 2 else ""
        result.append((number, name, target, user_type, program.span(start, end)))
    return result


def online_navigation(program: CobolProgram) -> list[Navigation]:
    """The navigation paths of an online program (rule 2), in the order:
    sign-on routing, menu options, PF3 return, list selections, entry
    without a COMMAREA."""
    result: list[Navigation] = []
    own = [i for i in range(program.proc_start, len(program.tokens)) if program.tokens[i].path == program.path]
    name = program.name

    if name == SIGNON_PROGRAM:
        for start, end in program.exec_blocks():
            words = program.words[start:end + 1]
            if "XCTL" not in words:
                continue
            xctl = start + words.index("XCTL")
            token = program.tokens[xctl + 3]
            if program.words[xctl + 1:xctl + 3] != ["PROGRAM", "("] or not token.literal:
                continue
            target = token.value.strip()
            back = start - 1
            while back > program.proc_start and program.words[back] not in ("IF", "ELSE"):
                back -= 1
            if program.words[back] == "IF":
                condition = " ".join(w for w in program.words[back + 1:start] if w)
                when = f"when {condition}"
            else:
                when = "otherwise"
            result.append(
                Navigation(
                    f"sign-on routing to {target}",
                    f"After a valid user id and password, {name} transfers control (XCTL) to "
                    f"{target} {when}; the {target} screen appears with the user's COMMAREA.",
                    program.span(back, end),
                )
            )

    options = _menu_options(program)
    if options:
        xctl_line = next(
            (program.tokens[s].line for s, e in program.exec_blocks()
             if "XCTL" in program.words[s:e + 1] and any(w.endswith("-OPT-PGMNAME") for w in program.words[s:e + 1])),
            None,
        )
        for number, option_name, target, user_type, span in options:
            typed = f", user type {user_type}" if user_type else ""
            via = f" through the XCTL at {program.path}:{xctl_line}" if xctl_line else ""
            result.append(
                Navigation(
                    f"option {number} {option_name} to {target}",
                    f"Option {number} '{option_name}' of the {name} menu table{typed} transfers control "
                    f"to {target}{via}; the {target} screen appears.",
                    span,
                )
            )

    if name != SIGNON_PROGRAM:
        for index in own:
            if program.words[index] in PF3_CONDITIONS and program.words[index - 1] in ("WHEN", "IF", "("):
                end = _transfer_end(program, index, len(program.tokens))
                targets = _transfer_targets(program, index, end)
                to = " or ".join(targets) if targets else "the previous screen"
                result.append(
                    Navigation(
                        "PF3 return",
                        f"PF3 leaves {name} and transfers control to {to}.",
                        program.span(index - 1 if program.words[index - 1] != "(" else index - 2, end),
                    )
                )
                break

    # List selections: WHEN '<letter>' of an EVALUATE on a selection flag,
    # or a one-letter 88-level condition of a selection array.
    for index in own:
        if program.words[index] != "EVALUATE" or "SEL" not in program.words[index + 1]:
            continue
        depth, position = 0, index + 2
        whens: list[int] = []
        while position < len(program.tokens):
            word = program.words[position]
            if word == "EVALUATE":
                depth += 1
            elif word == "END-EVALUATE":
                if depth == 0:
                    break
                depth -= 1
            elif word == "WHEN" and depth == 0:
                whens.append(position)
            position += 1
        # Consecutive WHEN phrases ('U' then 'u') share the branch that follows
        # the last of them.
        branch_start = {}
        for number in range(len(whens) - 1, -1, -1):
            follows = number + 1 < len(whens) and whens[number + 1] == whens[number] + 2
            branch_start[whens[number]] = branch_start[whens[number + 1]] if follows else whens[number]
        for when in whens:
            token = program.tokens[when + 1]
            if not (token.literal and len(token.value) == 1 and token.value.isupper()):
                continue
            later = [w for w in whens if w > branch_start[when]]
            branch_end = (later[0] if later else position) - 1
            targets = _transfer_targets(program, when, branch_end)
            to = f" transfers control to {' or '.join(targets)}" if targets else " is accepted"
            result.append(
                Navigation(
                    f"list selection {token.value}",
                    f"Selecting a row with '{token.value}' (or '{token.value.lower()}') in {name}{to}.",
                    program.span(when, branch_end),
                )
            )
    for entry in program.data:
        if entry.level != 88 or "SELECT" not in (entry.parent or "") or len(entry.values) != 1:
            continue
        letter = entry.values[0].value
        if not (len(letter) == 1 and letter.isupper()):
            continue
        uses = [i for i in own if program.words[i] == entry.name]
        if not uses:
            continue
        stop = min(uses[0] + 80, len(program.tokens) - 1)
        targets = _transfer_targets(program, uses[0], stop)
        to = f" transfers control to {targets[0]}" if targets else " is accepted"
        result.append(
            Navigation(
                f"list selection {letter}",
                f"Selecting a row with '{letter}' ({entry.name}) in {name}{to}.",
                program.span(uses[0], _transfer_end(program, uses[0], stop + 1)),
            )
        )

    if name != SIGNON_PROGRAM:
        for index in own:
            if program.words[index] != "IF" or program.words[index + 1] != "EIBCALEN":
                continue
            end = _branch_end(program, index)
            branch = program.tokens[index:end]
            to_signon = any(
                (t.literal and t.value.strip() == SIGNON_PROGRAM) or t.word == SIGNON_RETURN_PARAGRAPH
                for t in branch
            )
            if to_signon:
                result.append(
                    Navigation(
                        "entry without COMMAREA returns to sign-on",
                        f"Entered without a COMMAREA (EIBCALEN = 0), {name} returns to the "
                        f"{SIGNON_PROGRAM} sign-on screen.",
                        program.span(index, end - 1),
                    )
                )
            else:
                result.append(
                    Navigation(
                        "entry without COMMAREA starts without sign-on",
                        f"Entered without a COMMAREA (EIBCALEN = 0), {name} initialises a fresh "
                        f"COMMAREA and shows its own screen with no sign-on check; whether "
                        f"direct entry is intended is uncertain.",
                        program.span(index, end - 1),
                        ("GAP-UNC-010",),
                    )
                )
            break
    return result


# --------------------------------------------------------------------------
# Anchors: where a table row's behaviour is in the source
# --------------------------------------------------------------------------

# ("paragraph", NAME) | ("main",) | ("cics", VERB, RESOURCE) | ("regex", PATTERN)
Anchor = tuple[str, ...]


def _own_text(program: CobolProgram) -> tuple[str, list[int], list[int]]:
    lines = program.own_lines()
    offsets, numbers, parts, offset = [], [], [], 0
    for line in lines:
        offsets.append(offset)
        numbers.append(line.line)
        parts.append(line.text)
        offset += len(line.text) + 1
    return "\n".join(parts), offsets, numbers


def anchor_span(program: CobolProgram, anchor: Anchor) -> tuple[str, int, int]:
    """(path, first line, last line) that ``anchor`` designates in
    ``program``; InventoryError when the source no longer holds it."""
    kind = anchor[0]
    if kind == "paragraph":
        return program.paragraph_span(anchor[1])
    if kind == "main":
        # The statements between PROCEDURE DIVISION and the first paragraph.
        stop = program.paragraphs[0].start if program.paragraphs else len(program.tokens)
        own = [i for i in range(program.proc_start, stop) if program.tokens[i].path == program.path]
        if not own:
            raise InventoryError(f"{program.path}: no statements before the first paragraph")
        return program.span(own[0], own[-1])
    if kind == "cics":
        verb, resource = anchor[1], anchor[2]
        for start, end in program.exec_blocks():
            block = program.tokens[start:end + 1]
            words = [token.word for token in block]
            if verb in words and (resource in words or any(t.literal and t.value.strip() == resource for t in block)):
                return program.span(start, end)
        raise InventoryError(f"{program.path}: no EXEC CICS {verb} on {resource}")
    if kind == "regex":
        text, offsets, numbers = _own_text(program)
        match = re.search(anchor[1], text, re.S)
        if match is None:
            raise InventoryError(f"{program.path}: anchor /{anchor[1]}/ not found")
        first = numbers[bisect.bisect_right(offsets, match.start()) - 1]
        last = numbers[bisect.bisect_right(offsets, max(match.end() - 1, match.start())) - 1]
        return program.path, first, last
    raise InventoryError(f"unknown anchor kind {kind!r}")


def anchored_source(program: CobolProgram, anchors: Sequence[Anchor]) -> str:
    spans = [anchor_span(program, anchor) for anchor in anchors]
    paths = {span[0] for span in spans}
    if len(paths) != 1:
        raise InventoryError(f"{program.name}: anchors {anchors} span several files {sorted(paths)}")
    return cite(spans[0][0], [(first, last) for _path, first, last in spans])


# --------------------------------------------------------------------------
# Online rule 4: side effects and content
# --------------------------------------------------------------------------

# (program, kind, label, anchors, description)
SIDE_EFFECTS: tuple[tuple[str, str, str, tuple[Anchor, ...], str], ...] = (
    ("COUSR00C", "report", "user list content", (("paragraph", "POPULATE-USER-DATA"),),
     "The user list shows up to 10 USRSEC records per page (user id, first and last name, type) in key "
     "order, with PF7/PF8 paging."),
    ("COUSR01C", "interface", "USRSEC WRITE", (("cics", "WRITE", "WS-USRSEC-FILE"),),
     "Adding a user writes a new 80-byte USRSEC record (CSUSR01Y) keyed by the user id; observed as the "
     "record in file_state."),
    ("COUSR02C", "interface", "USRSEC REWRITE", (("cics", "REWRITE", "WS-USRSEC-FILE"),),
     "Saving changes (PF5) rewrites the USRSEC record of the selected user id with the edited names, "
     "password and type; observed as the record in file_state."),
    ("COUSR03C", "interface", "USRSEC DELETE", (("cics", "DELETE", "WS-USRSEC-FILE"),),
     "Confirming the deletion (PF5) deletes the USRSEC record of the selected user id; observed as its "
     "absence in file_state."),
    ("COACTVWC", "report", "account and customer detail content", (("paragraph", "1200-SETUP-SCREEN-VARS"),),
     "The account view shows the ACCTDAT fields (status, balances, limits, dates, group) and the CUSTDAT "
     "fields of the account's customer, the SSN formatted as nnn-nn-nnnn."),
    ("COACTUPC", "interface", "account REWRITE", (("cics", "REWRITE", "LIT-ACCTFILENAME"),),
     "After the changes are validated and confirmed (PF5), COACTUPC rewrites the ACCTDAT record with the "
     "edited account fields under the lock taken by READ UPDATE; observed as the record in file_state."),
    ("COACTUPC", "interface", "customer REWRITE", (("cics", "REWRITE", "LIT-CUSTFILENAME"),),
     "After the account rewrite, COACTUPC rewrites the CUSTDAT record with the edited customer fields, then "
     "SYNCPOINTs; observed as the record in file_state."),
    ("COCRDLIC", "report", "card list content, 7 rows per page", (("paragraph", "1200-SCREEN-ARRAY-INIT"),),
     "The card list shows up to 7 CARDDAT records per page (account, card number, status), filtered by the "
     "account and card filters, with PF7/PF8 paging."),
    ("COCRDSLC", "report", "card detail content", (("paragraph", "1200-SETUP-SCREEN-VARS"),),
     "The card view shows the CARDDAT record of the account and card entered: embossed name, expiry month "
     "and year, status."),
    ("COCRDUPC", "interface", "card REWRITE", (("cics", "REWRITE", "LIT-CARDFILENAME"),),
     "After the changes are validated and confirmed (PF5), COCRDUPC rewrites the CARDDAT record with the "
     "edited name, status and expiry under the lock taken by READ UPDATE; observed as the record in "
     "file_state."),
    ("COTRN00C", "report", "transaction list content", (("paragraph", "POPULATE-TRAN-DATA"),),
     "The transaction list shows up to 10 TRANSACT records per page (id, date, description, amount) in key "
     "order from the entered transaction id, with PF7/PF8 paging."),
    ("COTRN01C", "report", "transaction detail content", (("paragraph", "PROCESS-ENTER-KEY"),),
     "The transaction view shows every field of the TRANSACT record of the entered id: card, type, "
     "category, source, description, amount, timestamps and merchant data."),
    ("COTRN02C", "interface", "TRANSACT WRITE", (("cics", "WRITE", "WS-TRANSACT-FILE"),),
     "A confirmed transaction is written to TRANSACT with the entered fields and the generated id; observed "
     "as the new record in file_state."),
    ("COTRN02C", "calculation", "next TRAN-ID",
     (("regex", r"MOVE\s+HIGH-VALUES\s+TO\s+TRAN-ID.*?ADD\s+1\s+TO\s+WS-TRAN-ID-N\b"),),
     "The new transaction id is the highest TRANSACT key (STARTBR at HIGH-VALUES, READPREV) plus 1."),
    ("COTRN02C", "rule", "PF5 copy of the last transaction",
     (("regex", r"WHEN\s+DFHPF5\s+PERFORM\s+COPY-LAST-TRAN-DATA"),),
     "PF5 validates the account or card number entered, then reads the TRANSACT record with the highest "
     "TRAN-ID (STARTBR at HIGH-VALUES, READPREV) and copies its type, category, source, amount, description, "
     "original and processing timestamps and merchant id, name, city and zip into the input fields; a key or "
     "browse error ends PF5 with its message. The record is not checked against the card or account entered, "
     "so the copied data may come from another card (GAP-UNC-015). Enter processing then follows: the fields "
     "are validated and the transaction is added only when Confirm is Y."),
    ("COBIL00C", "interface", "TRANSACT WRITE", (("cics", "WRITE", "WS-TRANSACT-FILE"),),
     "A confirmed payment writes a TRANSACT record of type 02, category 2, source 'POS TERM', description "
     "'BILL PAYMENT - ONLINE', amount equal to the current balance; observed in file_state."),
    ("COBIL00C", "calculation", "next TRAN-ID",
     (("regex", r"MOVE\s+HIGH-VALUES\s+TO\s+TRAN-ID.*?ADD\s+1\s+TO\s+WS-TRAN-ID-NUM\b"),),
     "The payment's transaction id is the highest TRANSACT key (STARTBR at HIGH-VALUES, READPREV) plus 1."),
    ("COBIL00C", "calculation", "full-balance payment",
     (("regex", r"MOVE\s+ACCT-CURR-BAL\s+TO\s+WS-CURR-BAL"
                r".*?COMPUTE\s+ACCT-CURR-BAL\s*=\s*ACCT-CURR-BAL\s*-\s*TRAN-AMT"),),
     "The payment amount is the whole current balance, and the account is rewritten with ACCT-CURR-BAL "
     "reduced by it, leaving zero; a balance of zero or less is refused ('You have nothing to pay...')."),
    ("CORPT00C", "interface", "JOBS TDQ write", (("cics", "WRITEQ", "JOBS"),),
     "Submitting a report writes the TRNRPT00 job (EXEC PROC=TRANREPT with an in-stream DATEPARM of the "
     "start and end dates) as 80-byte records to the extrapartition TDQ JOBS; observed through @TDQ:JOBS."),
    ("CORPT00C", "calculation", "monthly date range",
     (("regex", r"WHEN\s+MONTHLYI\b.*?PERFORM\s+SUBMIT-JOB-TO-INTRDR"),),
     "The monthly report runs from the first to the last day of the run month (first day of the next month "
     "minus one day)."),
    ("CORPT00C", "calculation", "yearly date range",
     (("regex", r"WHEN\s+YEARLYI\b.*?PERFORM\s+SUBMIT-JOB-TO-INTRDR"),),
     "The yearly report runs from 1 January to 31 December of the run year."),
    ("CORPT00C", "calculation", "custom date range",
     (("regex", r"WHEN\s+CUSTOMI\b.*?PERFORM\s+SUBMIT-JOB-TO-INTRDR"),),
     "The custom report runs between the entered start and end dates once each part is supplied, numeric "
     "and in range and CSUTLDTC accepts both dates."),
)


# --------------------------------------------------------------------------
# Online behaviour items
# --------------------------------------------------------------------------


def _draft(
    area: str, kind: str, name: str, source: str, description: str, level: str,
    activation: str = "always", gap_ids: Iterable[str] = (),
) -> dict[str, Any]:
    """An inventory item without its id."""
    return {
        "area": area,
        "kind": kind,
        "name": name,
        "source": source,
        "description": description,
        "activation": activation,
        "level": level,
        "scenario_ids": [],
        "status": "not-covered",
        "gap_ids": sorted(set(gap_ids)),
    }


def _menu_user_types(program: CobolProgram) -> set[str]:
    return {user_type for _n, _name, _pgm, user_type, _span in _menu_options(program)}


def _menu_programs(program: CobolProgram) -> set[str]:
    return {target for _n, _name, target, _type, _span in _menu_options(program)}


# Messages no input or configuration can show: (program, text pattern, gap,
# test that the blocking condition still holds, why).
UNREACHABLE_MESSAGES: tuple[tuple[str, str, str, Callable[[CobolProgram], bool], str], ...] = (
    (
        "COMEN01C", r"No access - Admin Only", "GAP-DOC-019",
        lambda program: "A" not in _menu_user_types(program),
        "the guard needs a menu option of user type 'A' and every option of the COMEN02Y table is 'U'",
    ),
    (
        "COMEN01C", r"is coming soon", "GAP-UNC-005",
        lambda program: not any(p.startswith("DUMMY") for p in _menu_programs(program)),
        "it is shown only for an option whose program name begins 'DUMMY', and the menu table has none",
    ),
    (
        "COADM01C", r"is coming soon", "GAP-UNC-005",
        lambda program: not any(p.startswith("DUMMY") for p in _menu_programs(program)),
        "it is shown only for an option whose program name begins 'DUMMY', and the menu table has none",
    ),
)


def _message_draft(area: str, program: CobolProgram, message: Message) -> dict[str, Any]:
    name = program.name
    shown = "the abend data sent to the terminal" if "ABEND-MSG" in message.targets else "the screen message (ERRMSG)"
    fields = ", ".join(sorted(message.targets))
    lines = _line_list(message.sites)
    if message.origin == "condition":
        conditions = ", ".join(message.conditions)
        parents = fields
        description = (
            f"Message condition {conditions} of {parents} (VALUE '{message.text}'): when the condition is set "
            f"the text appears in {shown}."
        )
        if message.unused:
            description += (
                f" Unused: {conditions} {'is' if len(message.conditions) == 1 else 'are'} never set or tested "
                f"outside {'its definition' if len(message.conditions) == 1 else 'their definitions'}, so the "
                f"message cannot appear."
            )
    elif message.origin == "common":
        description = (
            f"{name} moves {message.constant} ('{message.text}', defined in {COMMON_MESSAGE_COPYBOOK}) to "
            f"{fields} at line(s) {lines}; the text appears in {shown}."
        )
    elif message.origin == "literal":
        description = f"{name} moves '{message.text}' to {fields} at line(s) {lines}; the text appears in {shown}."
        if message.conditions:
            description += f" Also the VALUE of condition(s) {', '.join(message.conditions)}."
    elif message.origin == "string":
        description = (
            f"{name} composes '{message.text}' with STRING into {fields} in {message.context}; the parts in "
            f"<> are run-time values. The text appears in {shown}."
        )
    else:
        description = (
            f"On an unexpected RESP in {message.context}, {name} composes WS-FILE-ERROR-MESSAGE "
            f"'{message.text}' and shows it in {shown}."
        )
    activation, gaps = "always", []
    if message.unused:
        activation, gaps = "unused", ["GAP-UNC-013"]
    for owner, pattern, gap, blocked, why in UNREACHABLE_MESSAGES:
        if owner == name and re.search(pattern, message.text) and blocked(program):
            activation, gaps = "unreachable", [gap]
            description += f" Unreachable: {why}."
    label = message.text.strip()
    if message.origin == "file-error":
        label = f"{label.split(' returned ')[0]}' ({message.context}"
        title = f"{name} message '{label})"
    else:
        title = f"{name} message '{label}'"
    return _draft(area, classify_message(message), title, cite_span(message.span), description, "e2e", activation, gaps)


def online_items(area: str, program: CobolProgram) -> list[dict[str, Any]]:
    """The behaviour items of one online program: messages, navigation,
    field edits, then side effects and content."""
    predicates, field_paragraphs = field_edit_predicates(program)
    drafts = [_message_draft(area, program, message) for message in online_messages(program, field_paragraphs)]
    for navigation in online_navigation(program):
        drafts.append(
            _draft(area, "rule", f"{program.name} {navigation.label}", cite_span(navigation.span),
                   navigation.description, "e2e", gap_ids=navigation.gap_ids)
        )
    for predicate in predicates:
        if predicate.field is None:
            drafts.append(
                _draft(
                    area, "rule", f"{program.name} {predicate.routine}: {predicate.label}", cite_span(predicate.span),
                    f"Edit routine {predicate.routine} would refuse a field ({predicate.label}) with "
                    f"'<field name>{predicate.message}', but no field edit performs the routine.",
                    "e2e", "unused", ["GAP-UNC-013"],
                )
            )
            continue
        described = f"'{predicate.message}'" if predicate.names_field else f"'{predicate.message}' (no field name)"
        drafts.append(
            _draft(
                area, "rule", f"{program.name} {predicate.field}: {predicate.label}", cite_span(predicate.span),
                f"{program.name} refuses {predicate.field} unless {predicate.label}: ERRMSG shows {described} "
                f"and the field is flagged (edit routine {predicate.routine}).",
                "e2e",
            )
        )
    for owner, kind, label, anchors, description in SIDE_EFFECTS:
        if owner == program.name:
            drafts.append(_draft(area, kind, f"{owner} {label}", anchored_source(program, anchors), description, "e2e"))
    return drafts


def text_span(path: str, pattern: str, repo_root: Path = REPO_ROOT) -> tuple[str, int, int]:
    """(path, first line, last line) of the first match of ``pattern``
    (``re.S``) in a text file such as a JCL member or the README."""
    lines = read_lines(repo_root / path)
    text = "\n".join(lines)
    match = re.search(pattern, text, re.S)
    if match is None:
        raise InventoryError(f"{path}: anchor /{pattern}/ not found")
    first = text.count("\n", 0, match.start()) + 1
    last = text.count("\n", 0, max(match.end() - 1, match.start())) + 1
    return path, first, last


def text_source(path: str, patterns: Sequence[str], repo_root: Path = REPO_ROOT) -> str:
    spans = [text_span(path, pattern, repo_root) for pattern in patterns]
    return cite(path, [(first, last) for _p, first, last in spans])


# --------------------------------------------------------------------------
# Batch behaviour items (static table)
# --------------------------------------------------------------------------

# (area, program, kind, label, anchors, description, activation, gap_ids)
BatchRow = tuple[str, str, str, str, tuple[Anchor, ...], str, str, tuple[str, ...]]


def _read_program_rows(
    program: str, ddname: str, noun: str, get_next: str, display: str | None,
    open_para: str, close_para: str, abend: str, status: str, layout: str,
) -> list[BatchRow]:
    listing: tuple[Anchor, ...] = (("main",), ("paragraph", get_next))
    if display:
        listing += (("paragraph", display),)
    return [
        ("DAT", program, "report", "record listing in key order", listing,
         f"{program} opens {ddname} INPUT, reads every {noun} record in key order and DISPLAYs it "
         f"({layout} layout) between the start and end of execution lines; return code 0.",
         "always", ()),
        ("DAT", program, "recovery", "abend on open failure",
         (("paragraph", open_para), ("paragraph", abend), ("paragraph", status)),
         f"When {ddname} cannot be opened, {program} displays 'ERROR OPENING {ddname}', the file status as "
         f"'FILE STATUS IS: NNNN<status>' and 'ABENDING PROGRAM', then calls CEE3ABD (the run abends).",
         "always", ()),
        ("DAT", program, "recovery", "abend on read or close failure after open",
         (("paragraph", get_next), ("paragraph", close_para), ("paragraph", status)),
         f"A read error other than end of file, or a close error, makes {program} display the error line, "
         f"'FILE STATUS IS: NNNN<status>' and abend through CEE3ABD.",
         "always", ()),
    ]


BATCH_ROWS: tuple[BatchRow, ...] = tuple(
    _read_program_rows("CBACT01C", "ACCTFILE", "account", "1000-ACCTFILE-GET-NEXT", "1100-DISPLAY-ACCT-RECORD",
                       "0000-ACCTFILE-OPEN", "9000-ACCTFILE-CLOSE", "9999-ABEND-PROGRAM",
                       "9910-DISPLAY-IO-STATUS", "CVACT01Y")
    + _read_program_rows("CBACT02C", "CARDFILE", "card", "1000-CARDFILE-GET-NEXT", None,
                         "0000-CARDFILE-OPEN", "9000-CARDFILE-CLOSE", "9999-ABEND-PROGRAM",
                         "9910-DISPLAY-IO-STATUS", "CVACT02Y")
    + _read_program_rows("CBACT03C", "XREFFILE", "card cross-reference", "1000-XREFFILE-GET-NEXT", None,
                         "0000-XREFFILE-OPEN", "9000-XREFFILE-CLOSE", "9999-ABEND-PROGRAM",
                         "9910-DISPLAY-IO-STATUS", "CVACT03Y")
    + _read_program_rows("CBCUS01C", "CUSTFILE", "customer", "1000-CUSTFILE-GET-NEXT", None,
                         "0000-CUSTFILE-OPEN", "9000-CUSTFILE-CLOSE", "Z-ABEND-PROGRAM",
                         "Z-DISPLAY-IO-STATUS", "CVCUS01Y")
) + (
    # CBTRN01C: its items take the program's activation (no job runs it).
    ("TRN", "CBTRN01C", "report", "per-record XREF and account verification display",
     (("paragraph", "MAIN-PARA"), ("paragraph", "2000-LOOKUP-XREF"), ("paragraph", "3000-READ-ACCOUNT")),
     "For each DALYTRAN record CBTRN01C displays the record, looks the card up in XREFFILE and the account in "
     "ACCTFILE, and displays 'SUCCESSFUL READ OF XREF' with the card, account and customer ids and 'SUCCESSFUL "
     "READ OF ACCOUNT FILE'. After end of file the last lookup and display are repeated once (GAP-DEF-006).",
     "always", ()),
    ("TRN", "CBTRN01C", "rule", "card not verified, transaction skipped",
     (("regex", r"DISPLAY\s+'CARD NUMBER '.*?DALYTRAN-ID"),),
     "A card number missing from XREFFILE displays 'INVALID CARD NUMBER FOR XREF' and 'CARD NUMBER <card> "
     "COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-<id>'; no account lookup follows.",
     "always", ()),
    ("TRN", "CBTRN01C", "rule", "account not found",
     (("regex", r"IF\s+WS-ACCT-READ-STATUS\s+NOT\s*=\s*0\s+DISPLAY\s+'ACCOUNT '.*?' NOT FOUND'"),),
     "A cross-reference hit whose account is missing from ACCTFILE displays 'INVALID ACCOUNT NUMBER FOUND' and "
     "'ACCOUNT <id> NOT FOUND'.",
     "always", ()),
    ("TRN", "CBTRN01C", "recovery", "abend on open failure",
     tuple(("paragraph", p) for p in ("0000-DALYTRAN-OPEN", "0100-CUSTFILE-OPEN", "0200-XREFFILE-OPEN",
                                      "0300-CARDFILE-OPEN", "0400-ACCTFILE-OPEN", "0500-TRANFILE-OPEN",
                                      "Z-ABEND-PROGRAM", "Z-DISPLAY-IO-STATUS")),
     "A file that cannot be opened (for example no TRANFILE) displays 'ERROR OPENING <file>', 'FILE STATUS IS: "
     "NNNN<status>' and 'ABENDING PROGRAM', then abends through CEE3ABD.",
     "always", ()),
    ("TRN", "CBTRN01C", "recovery", "abend on read or close failure after open",
     tuple(("paragraph", p) for p in ("1000-DALYTRAN-GET-NEXT", "9000-DALYTRAN-CLOSE", "9100-CUSTFILE-CLOSE",
                                      "9200-XREFFILE-CLOSE", "9300-CARDFILE-CLOSE", "9400-ACCTFILE-CLOSE",
                                      "9500-TRANFILE-CLOSE")),
     "A DALYTRAN read error displays 'ERROR READING DAILY TRANSACTION FILE', a close error 'ERROR CLOSING "
     "<file>', each with the file status display and the CEE3ABD abend.",
     "always", ()),
    # CBTRN02C: daily posting (POSTTRAN).
    ("TRN", "CBTRN02C", "rule", "reject 100 invalid card", (("paragraph", "1500-A-LOOKUP-XREF"),),
     "A DALYTRAN card number missing from XREFFILE is rejected with reason 0100 'INVALID CARD NUMBER FOUND' "
     "and written to DALYREJS; the account is not read.",
     "always", ()),
    ("TRN", "CBTRN02C", "rule", "reject 101 account not found",
     (("regex", r"READ\s+ACCOUNT-FILE\s+INTO\s+ACCOUNT-RECORD\s+INVALID\s+KEY\s+MOVE\s+101"
                r".*?WS-VALIDATION-FAIL-REASON-DESC"),),
     "A card whose cross-referenced account is missing from ACCTFILE is rejected with reason 0101 'ACCOUNT "
     "RECORD NOT FOUND'.",
     "always", ()),
    ("TRN", "CBTRN02C", "rule", "reject 102 overlimit",
     (("regex", r"COMPUTE\s+WS-TEMP-BAL.*?'OVERLIMIT TRANSACTION'\s+TO\s+WS-VALIDATION-FAIL-REASON-DESC\s+END-IF"),),
     "A transaction is rejected with reason 0102 'OVERLIMIT TRANSACTION' when ACCT-CREDIT-LIMIT is less than "
     "ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT; an amount reaching the limit exactly posts "
     "(>= comparison). The cycle fields include the postings earlier in the run.",
     "always", ()),
    ("TRN", "CBTRN02C", "rule", "reject 103 expired account",
     (("regex", r"IF\s+ACCT-EXPIRAION-DATE\s*>=.*?'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'"
                r"\s+TO\s+WS-VALIDATION-FAIL-REASON-DESC\s+END-IF"),),
     "A transaction whose origination date (DALYTRAN-ORIG-TS(1:10)) is after ACCT-EXPIRAION-DATE is rejected "
     "with reason 0103 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'; the expiration date itself posts.",
     "always", ()),
    ("TRN", "CBTRN02C", "rule", "reject 103 takes precedence over 102", (("paragraph", "1500-B-LOOKUP-ACCT"),),
     "Both the limit and the expiration checks run; a record that is over limit and expired is rejected with "
     "0103 only, because the later check overwrites the reason.",
     "always", ()),
    ("TRN", "CBTRN02C", "rule", "latent reject 109",
     (("regex", r"MOVE\s+109\s+TO\s+WS-VALIDATION-FAIL-REASON.*?WS-VALIDATION-FAIL-REASON-DESC"),),
     "Reason 109 'ACCOUNT RECORD NOT FOUND' is set only on REWRITE INVALID KEY of the account record read by "
     "key in the same run, after the transaction was posted, and is never written to DALYREJS.",
     "unreachable", ("GAP-UNC-004",)),
    ("TRN", "CBTRN02C", "rule", "return code 4 when any record is rejected",
     (("regex", r"IF\s+WS-REJECT-COUNT\s*>\s*0\s+MOVE\s+4\s+TO\s+RETURN-CODE\s+END-IF"),),
     "The run ends with RETURN-CODE 4 when at least one record was rejected and 0 otherwise; no other return "
     "code is set.",
     "always", ()),
    ("TRN", "CBTRN02C", "calculation", "balance posting", (("paragraph", "2800-UPDATE-ACCOUNT-REC"),),
     "Each posted amount is added to ACCT-CURR-BAL and to ACCT-CURR-CYC-CREDIT when it is zero or positive, "
     "else to ACCT-CURR-CYC-DEBIT; the account record is rewritten.",
     "always", ()),
    ("TRN", "CBTRN02C", "calculation", "TCATBAL create",
     (("paragraph", "2700-UPDATE-TCATBAL"), ("paragraph", "2700-A-CREATE-TCATBAL-REC")),
     "A posting whose account, type and category have no TCATBALF record displays 'TCATBAL record not found "
     "for key : <key>.. Creating.' and writes a new record whose balance is the amount.",
     "always", ()),
    ("TRN", "CBTRN02C", "calculation", "TCATBAL update", (("paragraph", "2700-B-UPDATE-TCATBAL-REC"),),
     "A posting whose TCATBALF record exists adds the amount to TRAN-CAT-BAL and rewrites it.",
     "always", ()),
    ("TRN", "CBTRN02C", "interface", "TRANSACT record with processing timestamp",
     (("paragraph", "2000-POST-TRANSACTION"), ("paragraph", "2900-WRITE-TRANSACTION-FILE"),
      ("paragraph", "Z-GET-DB2-FORMAT-TIMESTAMP")),
     "Each posted transaction is written to TRANFILE (opened OUTPUT, so the file is recreated) as a 350-byte "
     "CVTRA05Y record copying the DALYTRAN fields, with TRAN-PROC-TS the run clock as "
     "YYYY-MM-DD-HH.MM.SS.hh0000.",
     "always", ()),
    ("TRN", "CBTRN02C", "interface", "DALYREJS 430-byte reject record",
     (("regex", r"01\s+FD-REJS-RECORD\..*?FD-VALIDATION-TRAILER[^\n]*"), ("paragraph", "2500-WRITE-REJECT-REC")),
     "Each rejected transaction is written to DALYREJS as 430 bytes: the 350-byte DALYTRAN record followed by "
     "the 4-digit reason code and its 76-byte description.",
     "always", ()),
    ("TRN", "CBTRN02C", "report", "processed and rejected counts",
     (("regex", r"DISPLAY\s+'TRANSACTIONS PROCESSED :'.*?WS-REJECT-COUNT"),),
     "At the end of the run CBTRN02C displays 'TRANSACTIONS PROCESSED :<9 digits>' and 'TRANSACTIONS "
     "REJECTED  :<9 digits>', with no breakdown by reason.",
     "always", ()),
    ("TRN", "CBTRN02C", "recovery", "abend on open failure",
     tuple(("paragraph", p) for p in ("0000-DALYTRAN-OPEN", "0100-TRANFILE-OPEN", "0200-XREFFILE-OPEN",
                                      "0300-DALYREJS-OPEN", "0400-ACCTFILE-OPEN", "0500-TCATBALF-OPEN",
                                      "9999-ABEND-PROGRAM", "9910-DISPLAY-IO-STATUS")),
     "A file that cannot be opened displays 'ERROR OPENING <file>', 'FILE STATUS IS: NNNN<status>' and "
     "'ABENDING PROGRAM', then abends through CEE3ABD; no return code is set.",
     "always", ()),
    ("TRN", "CBTRN02C", "recovery", "abend on read, write, rewrite or close failure after open",
     tuple(("paragraph", p) for p in ("1000-DALYTRAN-GET-NEXT", "2500-WRITE-REJECT-REC", "2700-UPDATE-TCATBAL",
                                      "2700-A-CREATE-TCATBAL-REC", "2700-B-UPDATE-TCATBAL-REC",
                                      "2900-WRITE-TRANSACTION-FILE", "9000-DALYTRAN-CLOSE", "9100-TRANFILE-CLOSE",
                                      "9200-XREFFILE-CLOSE", "9300-DALYREJS-CLOSE", "9400-ACCTFILE-CLOSE",
                                      "9500-TCATBALF-CLOSE")),
     "A read, write, rewrite or close error displays its error line ('ERROR READING DALYTRAN FILE', 'ERROR "
     "WRITING TO REJECTS FILE', 'ERROR REWRITING TRANSACTION BALANCE FILE', 'ERROR CLOSING ...'), the file "
     "status and abends through CEE3ABD. The DALYREJS close error displays XREFFILE-STATUS instead of the "
     "DALYREJS status (GAP-DEF-002).",
     "always", ()),
)

BATCH_ROWS += (
    # CBACT04C: interest calculation (INTCALC, PARM date).
    ("FIN", "CBACT04C", "calculation", "monthly interest", (("paragraph", "1300-COMPUTE-INTEREST"),),
     "For each TCATBALF record with a non-zero rate, the monthly interest is TRAN-CAT-BAL * DIS-INT-RATE / 1200, "
     "truncated to cents (no ROUNDED), and is added to the account's total interest.",
     "always", ()),
    ("FIN", "CBACT04C", "calculation", "account update",
     (("paragraph", "1050-UPDATE-ACCOUNT"), ("regex", r"ELSE\s+PERFORM\s+1050-UPDATE-ACCOUNT")),
     "When the TCATBALF account changes, the previous account is rewritten with the total interest added to "
     "ACCT-CURR-BAL and both cycle fields set to zero. The end-of-file ELSE that would update the last account "
     "is unreachable, so the last account is never rewritten (GAP-DEF-003).",
     "always", ()),
    ("FIN", "CBACT04C", "calculation", "fees computation", (("paragraph", "1400-COMPUTE-FEES"),),
     "1400-COMPUTE-FEES is performed after each interest computation but holds no statement ('To be "
     "implemented'), so no fee changes any record.",
     "always", ()),
    ("FIN", "CBACT04C", "rule", "DEFAULT disclosure group fallback", (("paragraph", "1200-GET-INTEREST-RATE"),),
     "When DISCGRP has no record for the account's group, type and category, CBACT04C displays 'DISCLOSURE "
     "GROUP RECORD MISSING' and 'TRY WITH DEFAULT GROUP CODE' and reads the rate of group 'DEFAULT'. The "
     "group is taken from ACCT-GROUP-ID.",
     "always", ()),
    ("FIN", "CBACT04C", "rule", "no interest record at zero rate",
     (("regex", r"IF\s+DIS-INT-RATE\s+NOT\s*=\s*0\s+PERFORM\s+1300-COMPUTE-INTEREST"
                r"\s+PERFORM\s+1400-COMPUTE-FEES\s+END-IF"),),
     "A category whose disclosure rate is zero computes no interest and writes no transaction record.",
     "always", ()),
    ("FIN", "CBACT04C", "interface", "interest transaction record", (("paragraph", "1300-B-WRITE-TX"),),
     "Each interest amount is written to TRANSACT (SYSTRAN) as a CVTRA05Y record: TRAN-ID = the 10-character "
     "PARM date followed by a 6-digit counter, type '01', category 0005, source 'System', description 'Int. "
     "for a/c <account>' followed by x'00' fill, the card from the cross-reference, and the run clock as both "
     "timestamps.",
     "always", ()),
    ("FIN", "CBACT04C", "recovery", "abend when the DEFAULT group is missing",
     (("paragraph", "1200-A-GET-DEFAULT-INT-RATE"),),
     "When the 'DEFAULT' disclosure group is missing as well, CBACT04C displays 'ERROR READING DEFAULT "
     "DISCLOSURE GROUP', the file status, and abends through CEE3ABD.",
     "always", ()),
    ("FIN", "CBACT04C", "recovery", "abend on open failure",
     tuple(("paragraph", p) for p in ("0000-TCATBALF-OPEN", "0100-XREFFILE-OPEN", "0200-DISCGRP-OPEN",
                                      "0300-ACCTFILE-OPEN", "0400-TRANFILE-OPEN", "9999-ABEND-PROGRAM",
                                      "9910-DISPLAY-IO-STATUS")),
     "A file that cannot be opened displays its error line, the file status and abends through CEE3ABD; the "
     "DISCGRP open error reads 'ERROR OPENING DALY REJECTS FILE' (GAP-DEF-004).",
     "always", ()),
    ("FIN", "CBACT04C", "recovery", "abend on read, write, rewrite or close failure after open",
     tuple(("paragraph", p) for p in ("1000-TCATBALF-GET-NEXT", "1050-UPDATE-ACCOUNT", "1100-GET-ACCT-DATA",
                                      "1110-GET-XREF-DATA", "1300-B-WRITE-TX", "9000-TCATBALF-CLOSE",
                                      "9100-XREFFILE-CLOSE", "9200-DISCGRP-CLOSE", "9300-ACCTFILE-CLOSE",
                                      "9400-TRANFILE-CLOSE")),
     "A read, write, rewrite or close error displays its error line ('ERROR READING TRANSACTION CATEGORY "
     "FILE', 'ERROR WRITING TRANSACTION RECORD', 'ERROR RE-WRITING ACCOUNT FILE', 'ERROR CLOSING ...'), the "
     "file status and abends through CEE3ABD.",
     "always", ()),
    # CBTRN03C: transaction report (TRANREPT).
    ("RPT", "CBTRN03C", "rule", "DATEPARM window", (("main",), ("paragraph", "0550-DATEPARM-READ")),
     "Only TRANSACT records whose TRAN-PROC-TS(1:10) lies between the DATEPARM start and end dates (both "
     "included) are reported. The first record outside the window ends the read loop, because NEXT SENTENCE "
     "leaves the inline PERFORM: later records and all totals are lost (GAP-DEF-012).",
     "always", ()),
    ("RPT", "CBTRN03C", "report", "report header", (("paragraph", "1120-WRITE-HEADERS"),),
     "The 133-byte report starts with four header lines: the title 'Daily Transaction Report' with 'Date "
     "Range: <start> to <end>', a blank line, the column headings and a separator.",
     "always", ()),
    ("RPT", "CBTRN03C", "report", "detail line", (("paragraph", "1120-WRITE-DETAIL"),),
     "Each reported transaction is one detail line: id, account, type code and description, category code and "
     "description, source and edited amount.",
     "always", ()),
    ("RPT", "CBTRN03C", "report", "page break",
     (("paragraph", "1100-WRITE-TRANSACTION-REPORT"), ("paragraph", "1110-WRITE-PAGE-TOTALS")),
     "WS-LINE-COUNTER counts every header, total and detail line; before a detail line, when the counter is a "
     "multiple of 20, a page total, a separator and the four header lines are written. Account-total lines "
     "count too, so a break can be skipped (GAP-DEF-012).",
     "always", ()),
    ("RPT", "CBTRN03C", "report", "account totals", (("paragraph", "1120-WRITE-ACCOUNT-TOTALS"),),
     "When the card number changes, except before the first card, the account total of the previous card and "
     "a separator are written and the account total is reset.",
     "always", ()),
    ("RPT", "CBTRN03C", "report", "end-of-file totals",
     (("regex", r"DISPLAY\s+'TRAN-AMT '.*?PERFORM\s+1110-WRITE-GRAND-TOTALS"),
      ("paragraph", "1110-WRITE-GRAND-TOTALS")),
     "At end of file the last amount is added to the page and account totals a second time, then the page "
     "total and the grand total are written; no final account total is written (GAP-DEF-012).",
     "always", ()),
    ("RPT", "CBTRN03C", "recovery", "abend on INVALID KEY lookup of card, type or category",
     tuple(("paragraph", p) for p in ("1500-A-LOOKUP-XREF", "1500-B-LOOKUP-TRANTYPE", "1500-C-LOOKUP-TRANCATG")),
     "A card missing from CARDXREF, a type missing from TRANTYPE or a category missing from TRANCATG displays "
     "'INVALID CARD NUMBER : ', 'INVALID TRANSACTION TYPE : ' or 'INVALID TRAN CATG KEY : ' with the key and "
     "abends through CEE3ABD.",
     "always", ()),
    ("RPT", "CBTRN03C", "recovery", "abend on open failure",
     tuple(("paragraph", p) for p in ("0000-TRANFILE-OPEN", "0100-REPTFILE-OPEN", "0200-CARDXREF-OPEN",
                                      "0300-TRANTYPE-OPEN", "0400-TRANCATG-OPEN", "0500-DATEPARM-OPEN",
                                      "9999-ABEND-PROGRAM", "9910-DISPLAY-IO-STATUS")),
     "A file that cannot be opened (for example no DATEPARM) displays 'ERROR OPENING <file>' ('ERROR OPENING "
     "DATE PARM FILE'), the file status and abends through CEE3ABD.",
     "always", ()),
    ("RPT", "CBTRN03C", "recovery", "abend on read, write or close failure after open",
     tuple(("paragraph", p) for p in ("0550-DATEPARM-READ", "1000-TRANFILE-GET-NEXT", "1111-WRITE-REPORT-REC",
                                      "9000-TRANFILE-CLOSE", "9100-REPTFILE-CLOSE", "9200-CARDXREF-CLOSE",
                                      "9300-TRANTYPE-CLOSE", "9400-TRANCATG-CLOSE", "9500-DATEPARM-CLOSE")),
     "A read, write or close error displays 'ERROR READING TRANSACTION FILE', 'ERROR WRITING REPTFILE' or "
     "'ERROR CLOSING ...', the file status and abends through CEE3ABD.",
     "always", ()),
    # CBSTM03A with its file service CBSTM03B: statements (CREASTMT).
    ("RPT", "CBSTM03A", "report", "text statement",
     (("paragraph", "5000-CREATE-STATEMENT"), ("paragraph", "6000-WRITE-TRANS")),
     "For each card of the cross-reference, an 80-byte text statement is written to STMTFILE: the customer "
     "name and address block, account id, current balance and FICO score, one line per transaction and the "
     "total.",
     "always", ()),
    ("RPT", "CBSTM03A", "report", "HTML statement",
     (("paragraph", "5100-WRITE-HTML-HEADER"), ("paragraph", "5200-WRITE-HTML-NMADBS"),
      ("paragraph", "6000-WRITE-TRANS")),
     "The same statement is written to HTMLFILE as 100-byte HTML lines: header, name and address, account "
     "details, a table row per transaction and the total.",
     "always", ()),
    ("RPT", "CBSTM03A", "report", "JCL step and DD display", (("main",),),
     "CBSTM03A walks the z/OS PSA, TCB and TIOT from address 0 and displays 'Running JCL : <job> Step <step>' "
     "and each DD name with a valid or null UCB; this runs only where page zero holds those control blocks.",
     "conditional", ("GAP-DOC-020", "GAP-STB-003")),
    ("RPT", "CBSTM03A", "calculation", "per-statement transaction total", (("paragraph", "4000-TRNXFILE-GET"),),
     "The amounts of the card's transactions are summed into WS-TOTAL-AMT, which the statement shows as the "
     "total; it is reset for each card.",
     "always", ()),
    ("RPT", "CBSTM03B", "interface", "file service operations O/C/R/K/W/Z",
     (("regex", r"05\s+LK-M03B-OPER.*?88\s+M03B-REWRITE[^\n]*"), ("paragraph", "0000-START")),
     "CBSTM03A calls CBSTM03B with a DD name (TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE), an operation code, a key "
     "and its length. CBSTM03B declares six operations (O open, C close, R read next, K read by key, W write, Z "
     "rewrite) but implements only O, R and C for TRNXFILE and XREFFILE and O, K and C for CUSTFILE and "
     "ACCTFILE, opening each file for input; any other operation, W and Z included, which no caller passes, "
     "does no I/O and returns that DD's last file status (GAP-UNC-016). An unknown DD name returns at once "
     "with LK-M03B-RC unchanged. CBSTM03B returns the file status in LK-M03B-RC and the record in "
     "LK-M03B-FLDT.",
     "always", ()),
    ("RPT", "CBSTM03A", "rule", "51-card x 10-transaction table limit",
     (("regex", r"05\s+WS-CARD-TBL\s+OCCURS\s+51\s+TIMES.*?WS-TRCT[^\n]*"), ("paragraph", "8500-READTRNX-READ")),
     "The transactions are held in a table of 51 cards with 10 transactions each, filled without a bound "
     "check; more cards or transactions overrun it (GAP-DEF-007).",
     "always", ()),
    ("RPT", "CBSTM03A", "recovery", "abend on open failure",
     tuple(("paragraph", p) for p in ("8100-TRNXFILE-OPEN", "8200-XREFFILE-OPEN", "8300-CUSTFILE-OPEN",
                                      "8400-ACCTFILE-OPEN", "9999-ABEND-PROGRAM")),
     "A file that CBSTM03B cannot open displays 'ERROR OPENING <file>' with the return code and abends through "
     "CEE3ABD.",
     "always", ()),
    ("RPT", "CBSTM03A", "recovery", "abend on read or close failure after open",
     tuple(("paragraph", p) for p in ("1000-XREFFILE-GET-NEXT", "2000-CUSTFILE-GET", "3000-ACCTFILE-GET",
                                      "8500-READTRNX-READ", "9100-TRNXFILE-CLOSE", "9200-XREFFILE-CLOSE",
                                      "9300-CUSTFILE-CLOSE", "9400-ACCTFILE-CLOSE")),
     "A read or close error returned by CBSTM03B displays 'ERROR READING <file>' or 'ERROR CLOSING <file>' "
     "with the return code and abends through CEE3ABD.",
     "always", ()),
    # CSUTLDTC: date validation through LE CEEDAYS.
    ("UTL", "CSUTLDTC", "rule", "CEEDAYS feedback-token mapping",
     (("regex", r"02\s+FEEDBACK-TOKEN-VALUE\..*?FC-YEAR-IN-ERA-ZERO[^\n]*"), ("paragraph", "A000-MAIN")),
     "The CEEDAYS feedback token is mapped to the severity, the message number and a 15-character text: 'Date "
     "is valid', 'Insufficient', 'Datevalue error', 'Invalid Era', 'Unsupp. Range', 'Invalid month', 'Bad Pic "
     "String', 'Nonnumeric data', 'YearInEra is 0', and 'Date is invalid' for any other token.",
     "always", ()),
    ("UTL", "CSUTLDTC", "rule", "date acceptance for masks YYYY-MM-DD and YYYYMMDD", (("paragraph", "A000-MAIN"),),
     "Which dates are accepted is decided by CEEDAYS for the mask the caller passes: 'YYYY-MM-DD' from "
     "CORPT00C and COTRN02C, 'YYYYMMDD' from COACTUPC through CSUTLDPY (EDIT-DATE-LE).",
     "always", ()),
    ("UTL", "CSUTLDTC", "interface", "result message layout and RETURN-CODE",
     (("regex", r"01\s+WS-MESSAGE\..*?WS-DATE-FMT[^\n]*"), ("main",)),
     "The 80-byte result is the 4-digit severity, 'Mesg Code:', the message number, the 15-character text, "
     "'TstDate:' with the date and 'Mask used:' with the mask; RETURN-CODE is set to the severity.",
     "always", ()),
)


# --------------------------------------------------------------------------
# JCL-semantics and cross-job items (static tables)
# --------------------------------------------------------------------------

# (area, owner, kind, label, path, anchor patterns, description). Only a real
# job scheduler exercises these, so their scenarios run on unikix only.
JCL_ROWS: tuple[tuple[str, str, str, str, str, tuple[str, ...], str], ...] = (
    ("DAT", "ACCTFILE", "recovery", "JCL MAXCC reset after DELETE", "app/jcl/ACCTFILE.jcl",
     (r"IF\s+MAXCC\s+LE\s+08\s+THEN\s+SET\s+MAXCC\s*=\s*0",),
     "After the IDCAMS DELETE of a cluster that may not exist, 'IF MAXCC LE 08 THEN SET MAXCC = 0' resets the "
     "condition code so the DEFINE and REPRO steps run and the job ends with return code 0; the same "
     "statement is in {jobs}."),
    ("DAT", "POSTTRAN", "recovery", "JCL DISP=(NEW,CATLG,DELETE) on step failure", "app/jcl/POSTTRAN.jcl",
     (r"//DALYREJS\s+DD\s+DISP=\(NEW,CATLG,DELETE\).*?DALYREJS\(\+1\)",),
     "An output generation allocated with DISP=(NEW,CATLG,DELETE), such as DALYREJS(+1), is catalogued when "
     "the step ends normally and deleted when it abends, so an abended POSTTRAN leaves no new DALYREJS "
     "generation."),
    ("DAT", "TRANBKP", "recovery", "JCL COND=(4,LT) on the DEFINE step", "app/jcl/TRANBKP.jcl",
     (r"//STEP10\s+EXEC\s+PGM=IDCAMS,COND=\(4,LT\)",),
     "The TRANSACT DEFINE step is bypassed when a previous step ended with a return code greater than 4, so "
     "a failed backup or delete leaves the cluster undefined."),
    ("DAT", "DEFGDGB", "config", "JCL GDG (+1)/(0) generation roll", "app/jcl/DEFGDGB.jcl",
     (r"DEFINE\s+GENERATIONDATAGROUP.*DEFINE\s+GENERATIONDATAGROUP[^\n]*",),
     "Each (+1) reference ({plus}) creates a new generation of a GDG base defined here, and each (0) "
     "reference ({zero}) reads the newest one; generations beyond the LIMIT roll off."),
    ("DAT", "CARDFILE", "config", "JCL AIX, PATH and BLDINDEX alternate-key access", "app/jcl/CARDFILE.jcl",
     (r"//STEP40\s+EXEC", r"//STEP50\s+EXEC", r"//STEP60\s+EXEC"),
     "DEFINE ALTERNATEINDEX, DEFINE PATH and BLDINDEX give the card file (and likewise the cross-reference "
     "and transaction files in XREFFILE, TRANFILE and TRANIDX) an alternate key, read through the PATH "
     "cluster."),
    ("RPT", "CREASTMT", "recovery", "JCL COND=(0,NE) on the statement steps", "app/jcl/CREASTMT.JCL",
     (r"//STEP020\s+EXEC\s+PGM=IDCAMS,COND=\(0,NE\)", r"//STEP030\s+EXEC\s+PGM=IEFBR14,COND=\(0,NE\)",
      r"//STEP040\s+EXEC\s+PGM=CBSTM03A,COND=\(0,NE\)"),
     "The REPRO, delete and CBSTM03A steps are bypassed once any earlier step ended with a non-zero return "
     "code, so a failed sort produces no TRXFL load and no statements."),
    ("RPT", "TRANREPT", "config", "proc resolution of EXEC PROC=TRANREPT", "app/proc/TRANREPT.prc",
     (r"\A[^\n]*",),
     "The TRNRPT00 job that CORPT00C submits runs EXEC PROC=TRANREPT, but app/proc/TRANREPT.prc begins with "
     "'//REPROC PROC', so how the scheduler resolves the proc decides what runs (GAP-DEF-008)."),
    ("CFG", "OPENFIL", "config", "JCL CICS file close and open through SDSF CEMT", "app/jcl/OPENFIL.jcl",
     (r"//OPCIFIL\s+EXEC\s+PGM=SDSF.*?CEMT[^\n]*\n(?:[^\n]*CEMT[^\n]*\n)*",),
     "OPENFIL and CLOSEFIL (and the OPCIFIL/CLCIFIL steps of CARDFILE, CUSTFILE and TRANFILE) issue '/F "
     "CICSAWSA,'CEMT SET FIL(...) OPE' or 'CLO' through SDSF, opening or closing the region's files; observed "
     "only through a region file inquiry."),
    ("CFG", "CBADMCDJ", "config", "JCL CSD install by DFHCSDUP", "app/jcl/CBADMCDJ.jcl",
     (r"//STEP1\s+EXEC\s+PGM=DFHCSDUP.*?DFHCSD[^\n]*",),
     "CBADMCDJ runs DFHCSDUP against the region's CSD to define the CARDDEMO group (libraries, mapsets, "
     "programs, transactions) for installation."),
)

# (label, description, README anchor patterns)
BAT_ROWS: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("daily batch cycle POSTTRAN, INTCALC, TRANBKP, COMBTRAN, CREASTMT, TRANREPT",
     "Run against one catalog, POSTTRAN posts DALYTRAN, INTCALC writes the interest transactions to "
     "SYSTRAN, TRANBKP backs TRANSACT up and redefines it, COMBTRAN merges the backup and SYSTRAN into "
     "TRANSACT, CREASTMT writes the statements and TRANREPT the transaction report; the end state of "
     "ACCTDATA, TCATBALF, TRANSACT, the statements and the report is asserted. TRANBKP sits between INTCALC "
     "and COMBTRAN as in the README (GAP-DOC-015).",
     (r"\|\s*POSTTRAN\s*\|[^\n]*\n.*?\|\s*CREASTMT\s*\|[^\n]*",)),
    ("README full batch run order CLOSEFIL to OPENFIL",
     "The README's full batch run, CLOSEFIL, ACCTFILE, CARDFILE, XREFFILE, CUSTFILE, TRANBKP, DISCGRP, "
     "TCATBALF, TRANTYPE, DUSRSECJ, POSTTRAN, INTCALC, TRANBKP, COMBTRAN, CREASTMT, TRANIDX, OPENFIL, run in "
     "that order with the region's files closed and reopened around it.",
     (r"## Running full batch.*?\|\s*OPENFIL\s*\|[^\n]*",)),
)


def _jobs_matching(pattern: str, repo_root: Path) -> list[str]:
    folder = repo_root / "app/jcl"
    regex = re.compile(pattern)
    return sorted(
        path.stem.upper() for path in folder.iterdir()
        if path.is_file() and regex.search("\n".join(read_lines(path)))
    )


def jcl_items(repo_root: Path = REPO_ROOT) -> list[dict[str, Any]]:
    maxcc = [job for job in _jobs_matching(r"SET\s+MAXCC\s*=\s*0", repo_root) if job != "ACCTFILE"]
    plus = _jobs_matching(r"\(\+1\)", repo_root)
    zero = _jobs_matching(r"\(0\)", repo_root)
    fills = {"jobs": ", ".join(maxcc), "plus": ", ".join(plus), "zero": ", ".join(zero)}
    drafts = []
    for area, owner, kind, label, path, patterns, description in JCL_ROWS:
        drafts.append(
            _draft(area, kind, f"{owner} {label}", text_source(path, patterns, repo_root),
                   description.format(**fills), "integration")
        )
    return drafts


def bat_items(repo_root: Path = REPO_ROOT) -> list[dict[str, Any]]:
    return [
        _draft("BAT", "job", f"BAT {label}", text_source(README_PATH, patterns, repo_root), description, "integration")
        for label, description, patterns in BAT_ROWS
    ]


# --------------------------------------------------------------------------
# Source items
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class CsdEntry:
    kind: str  # FILE, LIBRARY, MAPSET, PROGRAM, TDQUEUE, TRANSACTION
    name: str
    first: int
    last: int
    attributes: dict[str, str]


_CSD_DEFINE_RE = re.compile(r"^\s*DEFINE\s+([A-Z]+)\(([^)]+)\)\s+GROUP\(", re.I)
_CSD_ATTRIBUTE_RE = re.compile(r"\b([A-Z][A-Z0-9]*)\(([^()]*)\)")


def parse_csd(repo_root: Path = REPO_ROOT) -> list[CsdEntry]:
    """Every ``DEFINE`` of the CSD, with its line range (the DEFINE line to
    the line before the next DEFINE) and its attributes."""
    lines = read_lines(repo_root / CSD_PATH)
    starts = [(number, match) for number, line in enumerate(lines, start=1)
              if (match := _CSD_DEFINE_RE.match(line))]
    entries = []
    for position, (number, match) in enumerate(starts):
        last = starts[position + 1][0] - 1 if position + 1 < len(starts) else len(lines)
        while last > number and not lines[last - 1].strip():
            last -= 1
        text = " ".join(lines[number - 1:last])
        attributes = {key.upper(): value.strip() for key, value in _CSD_ATTRIBUTE_RE.findall(text)}
        entries.append(CsdEntry(match.group(1).upper(), match.group(2).strip().upper(), number, last, attributes))
    return entries


def read_fct_list(repo_root: Path = REPO_ROOT) -> list[tuple[int, str]]:
    """(line, file name) of each entry of the UniKix copy's ``fct.lst``, read
    from the zip in memory. ``__MACOSX/`` AppleDouble members are skipped."""
    with zipfile.ZipFile(repo_root / UNIKIX_ZIP) as archive:
        members = [n for n in archive.namelist() if n == FCT_MEMBER and not n.startswith("__MACOSX/")]
        if not members:
            raise InventoryError(f"{UNIKIX_ZIP} holds no {FCT_MEMBER}")
        text = archive.read(members[0]).decode("ascii")
    return [
        (number, line.split(",")[0].strip().upper())
        for number, line in enumerate(text.splitlines(), start=1)
        if line.strip()
    ]


def _files(directory: Path) -> list[Path]:
    return sorted(
        (p for p in directory.iterdir() if p.is_file() and p.name != ".gitkeep"),
        key=lambda p: p.name,
    )


def _whole_file(path: Path, repo_root: Path) -> str:
    return cite(_relative(path, repo_root), [(1, max(len(read_lines(path)), 1))])


_EXEC_RE = re.compile(r"^//(\S+)\s+EXEC\s+(PGM|PROC)=([A-Z0-9#@$]+)", re.I | re.M)


def _job_steps(path: Path) -> list[tuple[str, str]]:
    text = "\n".join(read_lines(path))
    return [(step, target.upper()) for step, _kind, target in _EXEC_RE.findall(text)]


@dataclass
class _Repository:
    """Everything the item builders read, parsed once per generation."""

    root: Path
    programs: dict[str, CobolProgram]
    program_files: dict[str, Path]
    job_files: dict[str, Path]
    proc_files: dict[str, Path]
    control_files: dict[str, Path]
    copiers: dict[str, list[str]]  # copybook path -> programs whose COPY reaches it
    csd: list[CsdEntry]

    @classmethod
    def load(cls, root: Path) -> "_Repository":
        program_files = {p.stem.upper(): p for p in _files(root / "app/cbl")}
        programs: dict[str, CobolProgram] = {}
        copiers: dict[str, list[str]] = {}
        for name, path in program_files.items():
            program = CobolProgram(name, _relative(path, root), resolve_copies(path, root))
            programs[name] = program
            for copied in sorted({line.path for line in program.lines} - {program.path}):
                copiers.setdefault(copied, []).append(name)
        return cls(
            root,
            programs,
            program_files,
            {p.stem.upper(): p for p in _files(root / "app/jcl")},
            {p.stem.upper(): p for p in _files(root / "app/proc")},
            {p.stem.upper(): p for p in _files(root / "app/ctl")},
            copiers,
            parse_csd(root),
        )

    def callers(self, name: str) -> list[str]:
        result = []
        for caller, program in self.programs.items():
            tokens = program.tokens
            if any(
                program.words[i] == "CALL" and tokens[i + 1].literal and tokens[i + 1].value.strip().upper() == name
                for i in range(program.proc_start, len(tokens) - 1)
            ):
                result.append(caller)
        return result

    def job_runs(self, name: str) -> list[str]:
        runs = []
        for kind, files in (("job", self.job_files), ("proc", self.proc_files)):
            for member, path in files.items():
                runs.extend(f"{kind} {member} step {step}" for step, target in _job_steps(path) if target == name)
        return runs


def _is_batch(name: str) -> bool:
    return not name.startswith("CO")


def _program_item(area: str, repo: _Repository, name: str) -> dict[str, Any]:
    path = repo.program_files[name]
    source = _whole_file(path, repo.root)
    if not _is_batch(name):
        transactions = [e.name for e in repo.csd if e.kind == "TRANSACTION" and e.attributes.get("PROGRAM") == name]
        maps = [Path(p).stem for p, users in repo.copiers.items() if p.startswith("app/cpy-bms/") and name in users]
        description = (
            f"Online CICS program {name}, started by transaction {', '.join(transactions) or '(none)'} and "
            f"showing map {', '.join(maps) or '(none)'}; its behaviour items cover the messages, navigation, "
            f"edits and file updates the screen exposes."
        )
        return _draft(area, "program", f"{name} program", source, description, "e2e")
    runs = repo.job_runs(name)
    callers = repo.callers(name)
    parts = []
    if runs:
        parts.append("run by " + ", ".join(runs))
    if callers:
        parts.append("called by " + ", ".join(callers))
    if parts:
        description = f"Batch program {name}, " + "; ".join(parts) + "."
        return _draft(area, "program", f"{name} program", source, description, "unit")
    description = f"Batch program {name}; no job or proc step runs it and no program calls it."
    return _draft(area, "program", f"{name} program", source, description, "unit", "unused", ["GAP-UNC-003"])


def _job_item(area: str, repo: _Repository, name: str, kind: str) -> dict[str, Any]:
    files = repo.job_files if kind == "job" else repo.proc_files
    path = files[name]
    steps = ", ".join(f"{step} {target}" for step, target in _job_steps(path))
    noun = "Job" if kind == "job" else "Proc"
    description = f"{noun} {name} ({_relative(path, repo.root)}): steps {steps or '(none)'}."
    return _draft(area, "job", f"{name} {kind}", _whole_file(path, repo.root), description, "integration")


def _control_card_item(area: str, repo: _Repository, name: str) -> dict[str, Any]:
    path = repo.control_files[name]
    statements = " ".join(
        line.strip() for line in read_lines(path) if line.strip() and not line.lstrip().startswith("/*")
    )
    users = sorted(
        member for files in (repo.job_files, repo.proc_files) for member, p in files.items()
        if re.search(rf"\b{re.escape(name)}\b", "\n".join(read_lines(p)))
    )
    description = (
        f"IDCAMS control card {name} holding '{statements}', read as SYSIN by {', '.join(users) or 'no member'}."
    )
    return _draft(area, "config", f"{name} control card", _whole_file(path, repo.root), description, "integration")


def _map_item(area: str, repo: _Repository, map_name: str) -> dict[str, Any]:
    bms = next(p for p in _files(repo.root / "app/bms") if p.stem.upper() == map_name)
    symbolic = f"app/cpy-bms/{map_name}.CPY"
    shown_by = ", ".join(repo.copiers.get(symbolic, [])) or "no program"
    description = (
        f"BMS mapset {map_name} ({_relative(bms, repo.root)}) with its symbolic map copybook {symbolic}; sent and "
        f"received by {shown_by}. Covered through the screen-field assertions of that program's scenarios."
    )
    return _draft(area, "map", f"{map_name} map", _whole_file(bms, repo.root), description, "e2e")


def _csd_items(repo: _Repository) -> list[dict[str, Any]]:
    """The CSD definitions that are items of their own: files, transactions,
    libraries and the TDQ, in that order and in CSD order within each.
    PROGRAM and MAPSET definitions belong to the program and map items."""
    items = []
    programs_with_source = set(repo.program_files)
    order = ("FILE", "TRANSACTION", "LIBRARY", "TDQUEUE")
    for kind in order:
        for entry in (e for e in repo.csd if e.kind == kind):
            source = cite(CSD_PATH, [(entry.first, entry.last)])
            attrs = entry.attributes
            if kind == "FILE":
                items.append(_draft(
                    "CFG", "config", f"{entry.name} CSD file", source,
                    f"CSD FILE {entry.name} on {attrs.get('DSNAME', '?')} (STATUS {attrs.get('STATUS', '?')}, "
                    f"ADD {attrs.get('ADD', '?')}, UPDATE {attrs.get('UPDATE', '?')}, DELETE "
                    f"{attrs.get('DELETE', '?')}); covered through the online scenarios that read or update it.",
                    "e2e",
                ))
            elif kind == "TRANSACTION":
                program = attrs.get("PROGRAM", "")
                description = f"CSD TRANSACTION {entry.name} starts PROGRAM({program})"
                if attrs.get("DESCRIPTION"):
                    description += f", '{attrs['DESCRIPTION']}'"
                if program in programs_with_source:
                    items.append(_draft("CFG", "transaction", f"{entry.name} transaction", source,
                                        description + "; covered by the scenarios that enter it.", "e2e"))
                else:
                    items.append(_draft(
                        "CFG", "transaction", f"{entry.name} transaction", source,
                        description + f"; no source of {program} exists, so the transaction cannot run.",
                        "e2e", "unreachable", ["GAP-DOC-001"],
                    ))
            elif kind == "LIBRARY":
                status = attrs.get("STATUS", "")
                description = (
                    f"CSD LIBRARY {entry.name} on {attrs.get('DSNAME01', '?')} (RANKING {attrs.get('RANKING', '?')}, "
                    f"STATUS {status})."
                )
                if status == "DISABLED":
                    items.append(_draft("CFG", "config", f"{entry.name} CSD library", source,
                                        description + " Disabled, so the region loads nothing from it.",
                                        "e2e", "conditional", ["GAP-MIS-012", "GAP-UNC-006"]))
                else:
                    items.append(_draft("CFG", "config", f"{entry.name} CSD library", source, description, "e2e"))
            else:
                items.append(_draft(
                    "CFG", "config", f"{entry.name} CSD TDQ", source,
                    f"CSD TDQUEUE {entry.name}, '{attrs.get('DESCRIPTION', '')}': extrapartition, DDNAME "
                    f"{attrs.get('DDNAME', '?')}, {attrs.get('RECORDSIZE', '?')}-byte {attrs.get('RECORDFORMAT', '?')} "
                    f"records; CORPT00C writes report jobs to it.",
                    "e2e",
                ))
    return items


def _layout_items(repo: _Repository) -> list[dict[str, Any]]:
    items = []
    for path in _files(repo.root / "app/cpy"):
        rel = _relative(path, repo.root)
        name = path.stem.upper()
        users = repo.copiers.get(rel, [])
        source = _whole_file(path, repo.root)
        if not users:
            items.append(_draft("LAY", "layout", f"{name} copybook", source,
                                f"Copybook {name}: no program copies it.", "unit", "unused", ["GAP-UNC-001"]))
            continue
        batch_users = [u for u in users if _is_batch(u)]
        level = LAYOUT_LEVEL_OVERRIDES.get(name, "unit" if batch_users else "e2e")
        description = (
            f"Copybook {name}, copied by {', '.join(users)}; covered through the scenarios whose decoded "
            f"assertions use it."
        )
        if name in LAYOUT_LEVEL_OVERRIDES:
            description += f" {LAYOUT_LEVEL_REASONS[name]}"
        items.append(_draft("LAY", "layout", f"{name} copybook", source, description, level))
    return items


def _data_items(repo: _Repository) -> list[dict[str, Any]]:
    items = []
    member_texts = {
        member: "\n".join(read_lines(path))
        for files in (repo.job_files, repo.proc_files) for member, path in sorted(files.items())
    }
    for path in _files(repo.root / "app/data/ASCII"):
        lines = read_lines(path)
        items.append(_draft(
            "DTA", "interface", f"{path.name} data file", _whole_file(path, repo.root),
            f"ASCII data file of {len(lines)} records, one per line; a fixture source whose lines are padded with "
            f"spaces to the record length.",
            DATA_LEVEL_OVERRIDES.get(path.name, "unit"),
        ))
    for path in _files(repo.root / "app/data/EBCDIC"):
        length = EBCDIC_RECORD_LENGTHS.get(path.name)
        if length is None:
            raise InventoryError(f"no record length is registered for {path.name}")
        size = path.stat().st_size
        if size % length:
            raise InventoryError(f"{path.name}: {size} bytes is not a multiple of the record length {length}")
        records = size // length
        rel = _relative(path, repo.root)
        description = (
            f"EBCDIC (cp037) data file of {records} fixed-length {length}-byte record{'s' if records != 1 else ''}; "
            f"the source range counts records, not lines."
        )
        level = DATA_LEVEL_OVERRIDES.get(path.name, "unit")
        dataset = re.compile(re.escape(path.name) + r"(?![\w.])")
        users = [member for member, text in member_texts.items() if dataset.search(text)]
        if users:
            items.append(_draft("DTA", "interface", f"{path.name} data file", cite(rel, [(1, records)]),
                                description + f" Its data set is named by {', '.join(users)}.", level))
        else:
            items.append(_draft("DTA", "interface", f"{path.name} data file", cite(rel, [(1, records)]),
                                description + " No job or proc names its data set.", level, "unused", ["GAP-UNC-002"]))
    return items


def _unikix_file_items(repo: _Repository) -> list[dict[str, Any]]:
    csd_files = {e.name for e in repo.csd if e.kind == "FILE"}
    items = []
    for number, name in read_fct_list(repo.root):
        if name in csd_files:
            continue
        items.append(_draft(
            "CFG", "config", f"{name} fct.lst file", f"{UNIKIX_ZIP}:{FCT_MEMBER}:{number}",
            f"Region file {name} defined only by the UniKix copy's fct.lst (not by the CSD); verified when the "
            f"test-owned region lists it open and enabled.",
            "e2e",
        ))
    return items


def _program_without_source_items(repo: _Repository) -> list[dict[str, Any]]:
    items = []
    for entry in (e for e in repo.csd if e.kind == "PROGRAM"):
        if entry.name in repo.program_files:
            continue
        transactions = [
            e.name for e in repo.csd if e.kind == "TRANSACTION" and e.attributes.get("PROGRAM") == entry.name
        ]
        items.append(_draft(
            PROGRAM_AREA_OVERRIDES.get(entry.name, "CFG"), "program", f"{entry.name} program",
            cite(CSD_PATH, [(entry.first, entry.last)]),
            f"CSD PROGRAM({entry.name}) '{entry.attributes.get('DESCRIPTION', '')}', started by transaction "
            f"{', '.join(transactions) or '(none)'}; neither app/cbl nor the UniKix copy holds its source, so "
            f"nothing can run it.",
            "e2e", "unreachable", ["GAP-DOC-001"],
        ))
    return items


# CSD programs without a source, by area.
PROGRAM_AREA_OVERRIDES = {"COCRDSEC": "CRD"}


# --------------------------------------------------------------------------
# Generation
# --------------------------------------------------------------------------


def _assignment_problems(repo: _Repository) -> list[str]:
    """Repository files that no area table assigns, or that a table names
    but the repository lacks: a new or removed source item."""
    problems = []
    tables = (
        ("program", repo.program_files, PROGRAM_AREAS, "app/cbl"),
        ("job", repo.job_files, JOB_AREAS, "app/jcl"),
        ("proc", repo.proc_files, PROC_AREAS, "app/proc"),
        ("control card", repo.control_files, CONTROL_CARD_AREAS, "app/ctl"),
    )
    for noun, files, table, folder in tables:
        assigned = [name for names in table.values() for name in names]
        for name in sorted(set(files) - set(assigned)):
            problems.append(f"{folder}/{files[name].name}: {noun} {name} has no inventory area")
        for name in sorted(set(assigned) - set(files)):
            problems.append(f"{noun} {name} is assigned an area but {folder} has no such member")
        for name, count in sorted(Counter(assigned).items()):
            if count > 1:
                problems.append(f"{noun} {name} is assigned to {count} areas")
    for bms in _files(repo.root / "app/bms"):
        symbolic = f"app/cpy-bms/{bms.stem.upper()}.CPY"
        if not (repo.root / symbolic).is_file():
            problems.append(f"{_relative(bms, repo.root)}: no symbolic map copybook {symbolic}")
        elif not repo.copiers.get(symbolic):
            problems.append(f"{_relative(bms, repo.root)}: no program copies {symbolic}")
    return problems


def _maps_of(repo: _Repository, program: str) -> list[str]:
    return sorted(
        Path(path).stem.upper() for path, users in repo.copiers.items()
        if path.startswith("app/cpy-bms/") and program in users
    )


def _drafts_by_area(repo: _Repository) -> dict[str, list[dict[str, Any]]]:
    problems = _assignment_problems(repo)
    if problems:
        raise InventoryError("source items not assigned:\n  " + "\n  ".join(problems))
    by_area: dict[str, list[dict[str, Any]]] = {area: [] for area in AREAS}
    jcl = jcl_items(repo.root)
    for area in AREAS:
        drafts = by_area[area]
        programs = PROGRAM_AREAS.get(area, ())
        online = [p for p in programs if not _is_batch(p)]
        batch = [p for p in programs if _is_batch(p)]
        drafts.extend(_program_item(area, repo, p) for p in online)
        drafts.extend(_map_item(area, repo, m) for p in online for m in _maps_of(repo, p))
        drafts.extend(_program_item(area, repo, p) for p in batch)
        if area == "CFG":
            drafts.extend(_csd_items(repo))
        drafts.extend(_job_item(area, repo, j, "job") for j in JOB_AREAS.get(area, ()))
        drafts.extend(_job_item(area, repo, p, "proc") for p in PROC_AREAS.get(area, ()))
        drafts.extend(_control_card_item(area, repo, c) for c in CONTROL_CARD_AREAS.get(area, ()))
        if area == "LAY":
            drafts.extend(_layout_items(repo))
        elif area == "DTA":
            drafts.extend(_data_items(repo))
        # Behaviour items follow the source items of the area.
        for program in online:
            drafts.extend(online_items(area, repo.programs[program]))
        for row_area, program, kind, label, anchors, description, activation, gaps in BATCH_ROWS:
            if row_area == area:
                drafts.append(_draft(area, kind, f"{program} {label}",
                                     anchored_source(repo.programs[program], anchors),
                                     description, "unit", activation, gaps))
        drafts.extend(d for d in jcl if d["area"] == area)
        drafts.extend(d for d in _program_without_source_items(repo) if d["area"] == area)
        if area == "CFG":
            drafts.extend(_unikix_file_items(repo))
        if area == "BAT":
            drafts.extend(bat_items(repo.root))
    # An unused batch program makes its behaviour items unused as well.
    for drafts in by_area.values():
        unused = {d["name"].split()[0] for d in drafts if d["kind"] == "program" and d["activation"] == "unused"}
        for draft in drafts:
            owner = draft["name"].split()[0]
            if owner in unused and draft["kind"] != "program" and draft["activation"] == "always":
                draft["activation"] = "unused"
                draft["gap_ids"] = sorted(set(draft["gap_ids"]) | {"GAP-UNC-003"})
    return by_area


def generate_items(repo_root: Path = REPO_ROOT) -> list[dict[str, Any]]:
    """Every inventory item the extraction rules and tables produce for the
    repository at ``repo_root``, numbered per area: source items first, then
    behaviour items."""
    repo = _Repository.load(Path(repo_root))
    items = []
    for area, drafts in _drafts_by_area(repo).items():
        for number, draft in enumerate(drafts, start=1):
            item = {"id": f"INV-{area}-{number:03d}"}
            item.update({key: draft[key] for key in ITEM_FIELDS[1:]})
            items.append(item)
    names = Counter(item["name"] for item in items)
    duplicates = sorted(name for name, count in names.items() if count > 1)
    if duplicates:
        raise InventoryError("item names are not unique: " + "; ".join(duplicates))
    return items


# --------------------------------------------------------------------------
# Header
# --------------------------------------------------------------------------

# The planning figures of the freeze; --freeze reports any difference so it
# can be recorded in header.changes with its reason.
PLANNED_TOTAL = 684
PLANNED_SOURCE_ITEMS = {
    "program": 28, "job": 31, "config": 12, "transaction": 18, "map": 17, "layout": 28, "interface": 21,
}
PLANNED_DERIVED_ITEMS = {
    "program": 1, "job": 2, "config": 11, "interface": 14, "report": 20,
    "calculation": 13, "recovery": 104, "rule": 364,
}


def item_area(item: dict[str, Any]) -> str:
    match = ID_RE.match(str(item.get("id", "")))
    if match is None:
        raise InventoryError(f"inventory id {item.get('id')!r} is not INV-<AREA>-<nnn>")
    return match.group(1)


def _source_path(source: str) -> str:
    return source.split(":", 1)[0]


def is_source_item(item: dict[str, Any]) -> bool:
    """Whether ``item`` is one of the repository's source items rather than a
    derived behaviour item, decided by its kind and the file it cites."""
    kind, path = item["kind"], _source_path(item["source"])
    if kind in ("transaction", "map", "layout"):
        return True
    if kind == "program":
        return path.startswith("app/cbl/")
    if kind == "job":
        return path.startswith(("app/jcl/", "app/proc/"))
    if kind == "config":
        return path in (CSD_PATH,) or path.startswith("app/ctl/")
    if kind == "interface":
        return path.startswith("app/data/")
    return False


def _counts(items: Iterable[dict[str, Any]]) -> tuple[Counter, Counter, dict[str, Counter]]:
    source, derived = Counter(), Counter()
    areas = {area: Counter() for area in AREAS}
    for item in items:
        area = item_area(item)
        if is_source_item(item):
            source[item["kind"]] += 1
            areas[area]["source"] += 1
        else:
            derived[item["kind"]] += 1
            areas[area]["derived"] += 1
    return source, derived, areas


def header_from_items(
    items: Sequence[dict[str, Any]],
    *,
    frozen_commit: str,
    frozen_on: str,
    changes: Sequence[dict[str, Any]] = (),
    protected_base: str = PROTECTED_BASE,
) -> dict[str, Any]:
    """The inventory header, its counts computed from ``items``."""
    source, derived, areas = _counts(items)
    return {
        "frozen_commit": frozen_commit,
        "frozen_on": frozen_on,
        "protected_base": protected_base,
        "total": len(items),
        "source_items": {kind: source.get(kind, 0) for kind in SOURCE_KINDS},
        "derived_items": {kind: derived[kind] for kind in KINDS if derived[kind]},
        "by_area": {
            area: {
                "source": areas[area]["source"],
                "derived": areas[area]["derived"],
                "total": areas[area]["source"] + areas[area]["derived"],
            }
            for area in AREAS
        },
        "changes": [dict(change) for change in changes],
    }


# --------------------------------------------------------------------------
# Inventory file API
# --------------------------------------------------------------------------


def _area_order(item: dict[str, Any]) -> tuple[int, str]:
    # The file lists items area by area (SEC ... BAT), then by id, so each
    # area reads as its source items followed by its behaviour items.
    return AREAS.index(item_area(item)), item["id"]


def load(path: str | Path = INVENTORY_FILE, missing_ok: bool = False) -> dict[str, Any]:
    """The inventory file as ``{"header": {...}, "items": [...]}``.

    A missing file raises InventoryError unless ``missing_ok``, which returns
    an empty inventory."""
    data = load_yaml(path, missing_ok=True)
    if data is None:
        if missing_ok:
            return {"header": {}, "items": []}
        raise InventoryError(f"{path} does not exist; run inventory_tools.py --freeze")
    if (
        not isinstance(data, dict)
        or not isinstance(data.get("items", []), list)
        or not isinstance(data.get("header", {}), dict)
    ):
        raise InventoryError(f"{path} is not a mapping with a 'header' mapping and an 'items' list")
    return {"header": data.get("header") or {}, "items": data.get("items") or []}


def write(inventory: dict[str, Any], path: str | Path = INVENTORY_FILE) -> bool:
    """Write ``inventory`` as canonical YAML, items in area order then id.
    Returns whether the file changed."""
    records = inventory.get("items", [])
    for item in records:
        missing = [key for key in ITEM_FIELDS if key not in item]
        if missing:
            raise InventoryError(f"{item.get('id', '?')}: missing field(s) {', '.join(missing)}")
    document = {
        "header": inventory.get("header", {}),
        "items": [{key: item[key] for key in ITEM_FIELDS} for item in sorted(records, key=_area_order)],
    }
    return write_text(path, dump_yaml(document))


def items(inventory: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    """The inventory items (loaded from the inventory file when not given)."""
    return list((inventory if inventory is not None else load())["items"])


def item_ids(inventory: dict[str, Any] | None = None) -> list[str]:
    return [item["id"] for item in items(inventory)]


def by_id(inventory: dict[str, Any] | None = None) -> dict[str, dict[str, Any]]:
    return {item["id"]: item for item in items(inventory)}


def next_id(area: str, inventory: dict[str, Any] | None = None) -> str:
    """The next unused id of ``area``; numbers are never reused."""
    if area not in AREAS:
        raise InventoryError(f"unknown area {area!r}; one of {', '.join(AREAS)}")
    matches = (ID_RE.match(item_id) for item_id in item_ids(inventory))
    numbers = [int(match.group(2)) for match in matches if match and match.group(1) == area]
    number = max(numbers, default=0) + 1
    if number > 999:
        raise InventoryError(f"area {area} has no inventory number left")
    return f"INV-{area}-{number:03d}"


def _validate_new_item(item: dict[str, Any]) -> None:
    for key in ("kind", "name", "source", "description"):
        if not isinstance(item.get(key), str) or not item[key].strip():
            raise InventoryError(f"new item needs a non-empty {key}: {item!r}")
    if item["kind"] not in KINDS:
        raise InventoryError(f"new item kind {item['kind']!r} is not one of {', '.join(KINDS)}")
    if item["activation"] not in ACTIVATIONS:
        raise InventoryError(f"new item activation {item['activation']!r} is not one of {', '.join(ACTIVATIONS)}")
    if item["level"] not in LEVELS:
        raise InventoryError(f"new item level {item['level']!r} is not one of {', '.join(LEVELS)}")
    for gap in item["gap_ids"]:
        if not _GAP_RE.match(gap):
            raise InventoryError(f"new item gap id {gap!r} is not GAP-<CAT>-<nnn>")


def append_items(
    new_items: Sequence[dict[str, Any]], reason: str, path: str | Path = INVENTORY_FILE
) -> list[str]:
    """Add behaviour or source items found after the freeze.

    Each new item gives its ``area`` and the contract fields ``kind``,
    ``name``, ``source``, ``description`` and ``level`` (``activation``
    defaults to ``always``). Ids are assigned with the next free number of
    the area, each addition is recorded in ``header.changes`` as ``{date,
    inventory_id, reason}`` and ``header.total`` rises with it. Items are
    never deleted. Returns the assigned ids.
    """
    if not isinstance(reason, str) or not reason.strip():
        raise InventoryError("an inventory addition needs a reason")
    inventory = load(path)
    header = dict(inventory["header"])
    if not header:
        raise InventoryError(f"{path} has no header; freeze the inventory before adding items")
    records = list(inventory["items"])
    changes = list(header.get("changes") or [])
    today = datetime.date.today().isoformat()
    assigned = []
    for new in new_items:
        candidate = dict(new)
        area = candidate.pop("area", None)
        item = {
            "kind": candidate.get("kind"),
            "name": candidate.get("name"),
            "source": candidate.get("source"),
            "description": candidate.get("description"),
            "activation": candidate.get("activation", "always"),
            "level": candidate.get("level"),
            "scenario_ids": sorted(candidate.get("scenario_ids") or []),
            "status": "not-covered",
            "gap_ids": sorted(set(candidate.get("gap_ids") or [])),
        }
        _validate_new_item(item)
        item_id = next_id(area, {"header": header, "items": records})
        records.append({"id": item_id, **item})
        changes.append({"date": today, "inventory_id": item_id, "reason": reason.strip()})
        assigned.append(item_id)
    header.update(
        header_from_items(
            records,
            frozen_commit=header.get("frozen_commit", "unknown"),
            frozen_on=header.get("frozen_on", today),
            changes=changes,
            protected_base=header.get("protected_base", PROTECTED_BASE),
        )
    )
    write({"header": header, "items": records}, path)
    return assigned


def _added_ids(header: dict[str, Any]) -> set[str]:
    return {
        change["inventory_id"]
        for change in header.get("changes") or []
        if isinstance(change, dict) and change.get("inventory_id")
    }


def freeze(path: str | Path = INVENTORY_FILE, repo_root: Path = REPO_ROOT) -> dict[str, Any]:
    """Write the inventory header from the items recorded at ``path``.

    An inventory with no items is generated first, numbered and stamped with
    the commit and date of the freeze. Existing items are never renumbered or
    rewritten; only the header counts are recomputed. Returns the header."""
    inventory = load(path, missing_ok=True)
    header = inventory["header"]
    records = inventory["items"]
    if not records:
        records = generate_items(repo_root)
        frozen_commit, frozen_on = git_head(repo_root), datetime.date.today().isoformat()
    else:
        frozen_commit = header.get("frozen_commit") or git_head(repo_root)
        frozen_on = header.get("frozen_on") or datetime.date.today().isoformat()
    new_header = header_from_items(
        records,
        frozen_commit=frozen_commit,
        frozen_on=frozen_on,
        changes=header.get("changes") or [],
        protected_base=header.get("protected_base") or PROTECTED_BASE,
    )
    write({"header": new_header, "items": records}, path)
    return new_header


def _planning_differences(header: dict[str, Any]) -> list[str]:
    notes = []
    if header["total"] != PLANNED_TOTAL:
        notes.append(f"total {header['total']} differs from the planning total {PLANNED_TOTAL}")
    for label, recorded, planned in (
        ("source", header["source_items"], PLANNED_SOURCE_ITEMS),
        ("derived", header["derived_items"], PLANNED_DERIVED_ITEMS),
    ):
        for kind in sorted(set(recorded) | set(planned)):
            if recorded.get(kind, 0) != planned.get(kind, 0):
                notes.append(
                    f"{label} {kind}: {recorded.get(kind, 0)} against the planning figure {planned.get(kind, 0)}"
                )
    return notes


# --------------------------------------------------------------------------
# Drift check
# --------------------------------------------------------------------------

_RANGE_RE = re.compile(r"^\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*$")
_HEADER_KEYS = (
    "frozen_commit", "frozen_on", "protected_base", "total",
    "source_items", "derived_items", "by_area", "changes",
)
_MAX_LISTED_DIFFERENCES = 25


def parse_source(source: str) -> tuple[str, list[tuple[int, int]]]:
    """Split an item ``source`` into the cited file and its line ranges. The
    file part of a zip member citation keeps the member path."""
    path, _, lines = str(source).rpartition(":")
    if not path or not _RANGE_RE.match(lines):
        raise InventoryError(f"source {source!r} is not '<path>:<lines>'")
    ranges = []
    for part in lines.split(","):
        first, _, last = part.partition("-")
        ranges.append((int(first), int(last or first)))
    return path, ranges


def _item_problems(records: Sequence[Any]) -> list[str]:
    problems: list[str] = []
    seen: dict[str, int] = {}
    numbers: dict[str, list[int]] = {area: [] for area in AREAS}
    for position, item in enumerate(records):
        if not isinstance(item, dict):
            problems.append(f"item {position} is not a mapping")
            continue
        label = item.get("id", f"item {position}")
        extra = sorted(set(item) - set(ITEM_FIELDS))
        missing = [key for key in ITEM_FIELDS if key not in item]
        if missing:
            problems.append(f"{label}: missing field(s) {', '.join(missing)}")
            continue
        if extra:
            problems.append(f"{label}: unexpected field(s) {', '.join(extra)}")
        match = ID_RE.match(str(item["id"]))
        if match is None:
            problems.append(f"{label}: id is not INV-<AREA>-<nnn>")
        else:
            numbers[match.group(1)].append(int(match.group(2)))
        seen[item["id"]] = seen.get(item["id"], 0) + 1
        for key, allowed in (("kind", KINDS), ("activation", ACTIVATIONS), ("level", LEVELS), ("status", STATUSES)):
            if item[key] not in allowed:
                problems.append(f"{label}: {key} {item[key]!r} is not one of {', '.join(allowed)}")
        for key in ("name", "description"):
            if not isinstance(item[key], str) or not item[key].strip():
                problems.append(f"{label}: {key} is empty")
        try:
            parse_source(item["source"])
        except InventoryError as exc:
            problems.append(f"{label}: {exc}")
        for key in ("scenario_ids", "gap_ids"):
            if not isinstance(item[key], list):
                problems.append(f"{label}: {key} is not a list")
        if isinstance(item["gap_ids"], list):
            for gap in item["gap_ids"]:
                if not _GAP_RE.match(str(gap)):
                    problems.append(f"{label}: gap id {gap!r} is not GAP-<CAT>-<nnn>")
    problems.extend(f"{item_id}: id used {count} times" for item_id, count in sorted(seen.items()) if count > 1)
    for area, used in numbers.items():
        if used and sorted(used) != list(range(1, max(used) + 1)):
            problems.append(f"area {area}: ids are not numbered 1..{max(used)} without holes")
    return problems


def _subtract(counts: Counter, minus: Counter) -> dict[str, int]:
    return {key: counts.get(key, 0) - minus.get(key, 0) for key in set(counts) | set(minus)}


def interface_hits(repo_root: Path = REPO_ROOT) -> list[tuple[str, int, str]]:
    """(path, line, text) of every match of INTERFACE_PATTERNS in the
    application sources, comments included."""
    regex = re.compile("|".join(f"(?:{pattern})" for pattern in INTERFACE_PATTERNS), re.I)
    hits = []
    for directory in INTERFACE_SEARCH_DIRS:
        folder = repo_root / directory
        if not folder.is_dir():
            continue
        for path in sorted(p for p in folder.rglob("*") if p.is_file()):
            for number, line in enumerate(read_lines(path), start=1):
                if regex.search(line):
                    hits.append((_relative(path, repo_root), number, line.strip()))
    return hits


def check(path: str | Path = INVENTORY_FILE, repo_root: Path = REPO_ROOT) -> list[str]:
    """Every way the inventory at ``path`` drifts from the repository and
    from its own header; an empty list means none.

    The source items are recounted and the extraction rules re-run; the
    result must equal the recorded items (``scenario_ids``, ``status`` and
    ``gap_ids`` ignored) apart from the additions recorded in
    ``header.changes``, and the header counts net of those additions."""
    findings: list[str] = []
    inventory = load(path)
    header, records = inventory["header"], inventory["items"]
    findings.extend(_item_problems(records))
    if findings:
        return findings
    for key in _HEADER_KEYS:
        if key not in header:
            findings.append(f"header: missing {key}")
    if findings:
        return findings
    if header["protected_base"] != PROTECTED_BASE:
        findings.append(f"header: protected_base {header['protected_base']} is not {PROTECTED_BASE}")
    if header["total"] != len(records):
        findings.append(f"header: total {header['total']} but {len(records)} items are recorded")
    expected = header_from_items(
        records, frozen_commit=header["frozen_commit"], frozen_on=header["frozen_on"],
        changes=header["changes"], protected_base=header["protected_base"],
    )
    for key in ("source_items", "derived_items", "by_area"):
        if header[key] != expected[key]:
            findings.append(f"header: {key} {header[key]} does not match the recorded items {expected[key]}")
    known = {item["id"] for item in records}
    for change in header["changes"]:
        if not isinstance(change, dict) or not change.get("reason") or not change.get("date"):
            findings.append(f"header: change {change!r} lacks a date or reason")
        elif change.get("inventory_id") and change["inventory_id"] not in known:
            findings.append(f"header: change cites unknown item {change['inventory_id']}")

    added = _added_ids(header)
    try:
        regenerated = generate_items(repo_root)
    except InventoryError as exc:
        findings.append(f"extraction failed: {exc}")
        return findings
    added_items = [item for item in records if item["id"] in added]
    added_source, added_derived, added_areas = _counts(added_items)
    source, derived, areas = _counts(regenerated)
    net_source = _subtract(Counter(header["source_items"]), added_source)
    net_derived = _subtract(Counter(header["derived_items"]), added_derived)
    for kind in sorted(set(net_source) | set(source)):
        if net_source.get(kind, 0) != source.get(kind, 0):
            findings.append(
                f"source items: {kind} recounts to {source.get(kind, 0)}, "
                f"header has {net_source.get(kind, 0)} net of additions"
            )
    for kind in sorted(set(net_derived) | set(derived)):
        if net_derived.get(kind, 0) != derived.get(kind, 0):
            findings.append(
                f"derived items: {kind} re-extracts to {derived.get(kind, 0)}, "
                f"header has {net_derived.get(kind, 0)} net of additions"
            )
    for area in AREAS:
        recorded = header["by_area"].get(area, {})
        for part in ("source", "derived"):
            net = recorded.get(part, 0) - added_areas[area][part]
            if net != areas[area][part]:
                findings.append(
                    f"area {area}: {part} recounts to {areas[area][part]}, header has {net} net of additions"
                )

    frozen = {item["id"]: item for item in records if item["id"] not in added}
    fresh = {item["id"]: item for item in regenerated}
    differences = []
    for item_id in sorted(set(frozen) | set(fresh), key=lambda i: (AREAS.index(item_area({"id": i})), i)):
        old, new = frozen.get(item_id), fresh.get(item_id)
        if old is None:
            differences.append(f"{item_id}: extracted ({new['name']}) but not recorded")
        elif new is None:
            differences.append(f"{item_id}: recorded ({old['name']}) but no longer extracted")
        else:
            for key in ITEM_FIELDS:
                if key not in REPORT_FIELDS and old[key] != new[key]:
                    differences.append(f"{item_id}: {key} recorded {old[key]!r}, extracted {new[key]!r}")
    if differences:
        findings.extend(differences[:_MAX_LISTED_DIFFERENCES])
        if len(differences) > _MAX_LISTED_DIFFERENCES:
            findings.append(f"... and {len(differences) - _MAX_LISTED_DIFFERENCES} more item differences")

    # A Db2, IMS or MQ reference needs an interface item of its own; the
    # whole-file citation of a program or job item does not account for it.
    cited: dict[str, list[tuple[int, int]]] = {}
    for item in records:
        if item["kind"] == "interface":
            file_path, ranges = parse_source(item["source"])
            cited.setdefault(file_path, []).extend(ranges)
    for file_path, number, text in interface_hits(repo_root):
        if not any(first <= number <= last for first, last in cited.get(file_path, [])):
            findings.append(f"{file_path}:{number}: external interface reference with no interface item: {text}")
    return findings


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="inventory_tools.py",
        description="Freeze the CardDemo functional inventory or check it for drift.",
    )
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--freeze", action="store_true",
                        help="generate the items when none are recorded, then write the header from the items")
    action.add_argument("--check", action="store_true",
                        help="recount and re-extract; exit 1 on any drift from the recorded inventory")
    parser.add_argument("--inventory", type=Path, default=INVENTORY_FILE,
                        help="inventory file (default: tests/inventory/inventory.yaml)")
    args = parser.parse_args(argv)
    try:
        if args.freeze:
            header = freeze(args.inventory)
            print(
                f"inventory frozen: {header['total']} items "
                f"({sum(header['source_items'].values())} source, {sum(header['derived_items'].values())} derived) "
                f"in {args.inventory}"
            )
            notes = _planning_differences(header)
            for note in notes:
                print(f"note: {note}", file=sys.stderr)
            if notes:
                print("note: record each difference in header.changes with its reason before committing the freeze",
                      file=sys.stderr)
            return 0
        findings = check(args.inventory)
    except HarnessError as exc:
        print(f"inventory_tools: {exc}", file=sys.stderr)
        return 1
    if findings:
        for finding in findings:
            print(finding, file=sys.stderr)
        print(f"inventory check failed: {len(findings)} finding(s)", file=sys.stderr)
        return 1
    print(f"inventory check passed: {len(load(args.inventory)['items'])} items match the repository")
    return 0


if __name__ == "__main__":
    sys.exit(main())
