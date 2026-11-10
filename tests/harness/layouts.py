"""Copybook, BMS and LISTCAT parser; generator of ``tests/inventory/layouts.yaml``.

``python tests/harness/layouts.py --write`` regenerates the committed layout
file from the read-only sources under ``app/``; ``--check`` exits 1 when the
committed file is not what the sources produce. Every adapter decodes and
compares records and screens through that one file, which keeps scenarios and
baselines platform neutral.

Top-level keys of the generated mapping:

``version``
    Schema version of this file (``SCHEMA_VERSION``).
``sources``
    ``{<repo-relative path>: <sha256>}`` of every input file read.
``copybooks``
    One entry per ``app/cpy`` member: ``path``, ``kind`` (``record`` when it
    starts with an 01 entry, ``fragment`` when its entries start below level
    01, ``procedure`` for procedure-division text), ``recln_comment`` and the
    keys of the ``records`` it yields.
``records``
    Keyed ``<COPYBOOK>/<01-NAME>`` (copybook file stem in upper case). A
    fragment is laid out under a synthetic root named after the copybook
    (``CSUTLDWY/CSUTLDWY``). ``CBTRN02C/REJECT-RECORD`` is composed from the
    program's working storage, and ``TEXT/<n>`` describes line datasets. Each
    entry holds ``kind``, ``copybook``, ``record``, ``source``, ``length``,
    ``recln_comment``, ``listcat`` and ``fields``.
``screens``
    Keyed by mapset file stem, which is the ``@map`` value of a screen.
``listcat``
    Key, record-size and alternate-index attributes of every VSAM cluster and
    alternate index in ``app/catlg/LISTCAT.txt``.

Each field carries ``name``, ``path`` (dot-joined from the 01 name; FILLERs are
numbered through the record as ``FILLER#<n>``), ``level``, ``offset``
(0-based), ``length`` (bytes of one occurrence), ``type`` (``alphanumeric``,
``zoned``, ``zoned-signed``, ``comp-3``, ``binary``, ``edited``, ``pointer`` or
``group``), ``digits``, ``scale``, ``signed``, ``occurs``, ``redefines`` and
``value``; keys that do not apply hold null. Children of an ``OCCURS`` item
give the offsets of the first occurrence. 88-level entries occupy no storage
and appear as ``conditions: {NAME: [values]}`` on the item they qualify.
``value`` and condition values keep the literal as declared, in single quotes
with embedded quotes doubled (``'Page Total'``), so they never collide with a
figurative constant, which appears by name (``SPACES``, ``ZEROS``,
``LOW-VALUES``, ``HIGH-VALUES``, ``QUOTES``, ``NULLS``); ``ALL '<text>'``, a
numeric literal as written, and ``<a> THRU <b>`` ranges complete the forms.

Layouts describe the z/OS record images: ``BINARY`` is big-endian, signed
zoned decimals carry a trailing overpunch sign, a ``POINTER`` is 4 bytes.
Constructs whose storage this module cannot describe exactly (``SIGN``,
``SYNCHRONIZED``, ``COMP-1``/``-2``/``-5``, ``INDEX``, ``OCCURS DEPENDING
ON``, hexadecimal literals) raise ``HarnessError`` instead of producing a layout
that would decode wrongly.

The module only reads ``app/`` and only writes ``tests/inventory/layouts.yaml``.
"""

from __future__ import annotations

import argparse
import copy
import functools
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Iterator

import yaml

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from harness import (  # noqa: E402
    INVENTORY_DIR,
    REPO_ROOT,
    HarnessError,
    dump_yaml,
    load_yaml,
    sha256_file,
    write_text,
)

__all__ = [
    "LAYOUTS_FILE",
    "LAYOUT_CLUSTER",
    "SCHEMA_VERSION",
    "TEXT_LAYOUTS",
    "field_map",
    "generate",
    "load",
    "main",
    "parse_bms",
    "parse_copybook",
    "parse_listcat",
    "record",
    "record_length",
    "screen",
]

SCHEMA_VERSION = 1
LAYOUTS_FILE = INVENTORY_DIR / "layouts.yaml"
GENERATED_BY = "tests/harness/layouts.py"

COPYBOOK_DIR = "app/cpy"
BMS_DIR = "app/bms"
LISTCAT_PATH = "app/catlg/LISTCAT.txt"

# Mirrors the `cobc -ftab-width=4` the gnucobol-batch build uses: CUSTREC.cpy and
# CSLKPCDY.cpy carry TAB characters, and with any other width their columns
# (and so the sequence and indicator areas) would not be the ones compiled.
TAB_WIDTH = 4

# Copybook (file stem) -> the VSAM cluster whose DATA component stores it.
LAYOUT_CLUSTER = {
    "CVACT01Y": "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS",
    "CVACT02Y": "AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS",
    "CVACT03Y": "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS",
    "CVCUS01Y": "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS",
    "CUSTREC": "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS",
    "CVTRA01Y": "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS",
    "CVTRA02Y": "AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS",
    "CVTRA03Y": "AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS",
    "CVTRA04Y": "AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS",
    "CVTRA05Y": "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS",
    "CSUSR01Y": "AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS",
}

# Line datasets decoded as one alphanumeric field `LINE`: record length -> the
# logical datasets and observations that use it.
TEXT_LAYOUTS = {
    40: ("TCATBALF-REPT",),
    80: ("@TDQ:JOBS", "DATEPARM", "STMTFILE"),
    100: ("HTMLFILE",),
    133: ("TRANREPT",),
}


@dataclass(frozen=True)
class _Overlay:
    """A program MOVEs the record ``layout`` into the elementary item ``target``
    of the record it writes; the layout then describes the target's bytes."""

    target: str
    layout: str  # "<COPYBOOK>/<01-NAME>", or an 01 entry of the program itself

    @property
    def moved_record(self) -> str:
        return self.layout.rpartition("/")[2]


@dataclass(frozen=True)
class _DerivedRecord:
    key: str
    program: str
    record: str
    file_record: str  # the FD record written FROM `record`
    overlays: tuple[_Overlay, ...]


# DALYREJS has no copybook: CBTRN02C builds REJECT-RECORD from the daily
# transaction and its validation trailer and writes it as FD-REJS-RECORD.
DERIVED_RECORDS = (
    _DerivedRecord(
        key="CBTRN02C/REJECT-RECORD",
        program="app/cbl/CBTRN02C.cbl",
        record="REJECT-RECORD",
        file_record="FD-REJS-RECORD",
        overlays=(
            _Overlay(target="REJECT-TRAN-DATA", layout="CVTRA06Y/DALYTRAN-RECORD"),
            _Overlay(target="VALIDATION-TRAILER", layout="WS-VALIDATION-TRAILER"),
        ),
    ),
)


def _relative(path: Path, repo_root: Path) -> str:
    try:
        return path.resolve().relative_to(repo_root.resolve()).as_posix()
    except ValueError:
        raise HarnessError(f"{path} lies outside the repository {repo_root}") from None


def _read_lines(path: Path, tab_width: int | None) -> list[str]:
    """Physical lines of a source file: latin-1 (byte preserving), carriage
    returns dropped (COSTM01.CPY has CRLF endings), tabs expanded."""
    text = path.read_bytes().decode("latin-1").replace("\r", "")
    lines = text.split("\n")
    if tab_width is not None:
        lines = [line.expandtabs(tab_width) for line in lines]
    return lines


def _citation(rel: str, first: int, last: int) -> str:
    return f"{rel}:{first}" if first == last else f"{rel}:{first}-{last}"


# --------------------------------------------------------------------------
# COBOL fixed-format source
# --------------------------------------------------------------------------

_CODE_AREA_WIDTH = 65  # columns 8-72


@dataclass
class _CodeLine:
    number: int  # physical line where the logical line starts
    text: str  # code area (columns 8-72), continuation lines joined
    last_segment: int  # width of the last physical segment joined


def _code_lines(raw_lines: list[str], rel: str) -> list[_CodeLine]:
    """Code area of every non-comment line. Columns 1-6 and 73-80 are sequence
    areas; ``*`` or ``/`` in column 7 is a comment, ``D`` a debugging line
    (compiled only WITH DEBUGGING MODE), ``-`` a continuation."""
    out: list[_CodeLine] = []
    for number, raw in enumerate(raw_lines, start=1):
        if len(raw) < 7:
            continue
        indicator = raw[6]
        if indicator in "*/Dd":
            continue
        code = raw[7:72]
        if indicator == "-":
            if not out:
                raise HarnessError(f"{rel}:{number}: continuation line without a line to continue")
            previous = out[-1]
            stripped = code.lstrip()
            if stripped[:1] in ("'", '"'):
                # A continued literal runs to column 72 of the line it starts on
                # and resumes after the quote that opens the continuation line.
                pad = " " * max(0, _CODE_AREA_WIDTH - previous.last_segment)
                previous.text = previous.text + pad + stripped[1:]
            else:
                previous.text = previous.text.rstrip() + stripped
            previous.last_segment = len(code)
            continue
        if indicator != " ":
            raise HarnessError(f"{rel}:{number}: unsupported indicator {indicator!r} in column 7")
        if not code.strip():
            continue
        out.append(_CodeLine(number=number, text=code, last_segment=len(code)))
    return out


@dataclass(frozen=True)
class _Token:
    kind: str  # "word", "literal" or "period"
    text: str
    line: int


_LITERAL_PREFIXES = {"X", "N", "NX", "Z", "G", "B"}


def _tokenize(lines: Iterable[_CodeLine], rel: str) -> Iterator[_Token]:
    """Words, literals and separator periods. A comma or semicolon followed by
    a space is a separator and dropped; a period ends an entry only where a
    space or the end of the line follows it, so ``-ZZZ,ZZZ,ZZZ.ZZ.`` yields the
    picture string and then the period."""
    for line in lines:
        text = line.text
        size = len(text)
        index = 0
        while index < size:
            char = text[index]
            if char.isspace():
                index += 1
                continue
            if char in ("'", '"'):
                quote = char
                chars: list[str] = []
                index += 1
                while True:
                    if index >= size:
                        raise HarnessError(f"{rel}:{line.number}: unterminated literal")
                    current = text[index]
                    if current == quote:
                        if index + 1 < size and text[index + 1] == quote:
                            chars.append(quote)
                            index += 2
                            continue
                        index += 1
                        break
                    chars.append(current)
                    index += 1
                yield _Token("literal", "".join(chars), line.number)
                continue
            end = index
            while end < size and not text[end].isspace() and text[end] not in ("'", '"'):
                end += 1
            word = text[index:end]
            if end < size and text[end] in ("'", '"') and word.upper() in _LITERAL_PREFIXES:
                raise HarnessError(
                    f"{rel}:{line.number}: {word}{text[end]}...{text[end]} literals "
                    "are not supported"
                )
            index = end
            word = word.rstrip(",;")
            period = word.endswith(".")
            if period:
                word = word[:-1].rstrip(",;")
            if word:
                yield _Token("word", word, line.number)
            if period:
                yield _Token("period", ".", line.number)


# --------------------------------------------------------------------------
# Data description entries
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Entry:
    tokens: tuple[_Token, ...]  # without the terminating period
    end_line: int  # line of the terminating period


def _entries(tokens: Iterable[_Token], rel: str) -> Iterator[_Entry]:
    current: list[_Token] = []
    for token in tokens:
        if token.kind != "period":
            current.append(token)
        elif current:
            yield _Entry(tuple(current), token.line)
            current = []
    if current:
        raise HarnessError(f"{rel}:{current[0].line}: entry is not terminated by a period")


_USAGES = {
    "DISPLAY": "DISPLAY",
    "BINARY": "BINARY",
    "COMP": "BINARY",
    "COMP-4": "BINARY",
    "COMPUTATIONAL": "BINARY",
    "COMPUTATIONAL-4": "BINARY",
    "COMP-3": "COMP-3",
    "COMPUTATIONAL-3": "COMP-3",
    "PACKED-DECIMAL": "COMP-3",
    "POINTER": "POINTER",
}
_UNSUPPORTED_USAGES = {
    "COMP-1",
    "COMP-2",
    "COMP-5",
    "COMP-X",
    "COMPUTATIONAL-1",
    "COMPUTATIONAL-2",
    "COMPUTATIONAL-5",
    "COMPUTATIONAL-X",
    "INDEX",
    "NATIONAL",
    "FUNCTION-POINTER",
    "PROCEDURE-POINTER",
    "PROGRAM-POINTER",
    "OBJECT",
}
_UNSUPPORTED_CLAUSES = {"SIGN", "LEADING", "TRAILING", "SYNC", "SYNCHRONIZED", "RENAMES"}
_SKIPPED_CLAUSES = {"GLOBAL", "EXTERNAL"}
_CLAUSE_WORDS = (
    {
        "REDEFINES",
        "PIC",
        "PICTURE",
        "USAGE",
        "OCCURS",
        "VALUE",
        "VALUES",
        "JUST",
        "JUSTIFIED",
        "BLANK",
    }
    | set(_USAGES)
    | _UNSUPPORTED_USAGES
    | _UNSUPPORTED_CLAUSES
    | _SKIPPED_CLAUSES
)
_FIGURATIVE = {
    "SPACE": "SPACES",
    "SPACES": "SPACES",
    "ZERO": "ZEROS",
    "ZEROS": "ZEROS",
    "ZEROES": "ZEROS",
    "LOW-VALUE": "LOW-VALUES",
    "LOW-VALUES": "LOW-VALUES",
    "HIGH-VALUE": "HIGH-VALUES",
    "HIGH-VALUES": "HIGH-VALUES",
    "QUOTE": "QUOTES",
    "QUOTES": "QUOTES",
    "NULL": "NULLS",
    "NULLS": "NULLS",
}
_NUMERIC_LITERAL_RE = re.compile(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)")


@dataclass
class _Item:
    """One data description entry with storage, and its subordinate entries."""

    level: int
    name: str
    line: int
    end_line: int
    pic: str | None = None
    usage: str | None = None
    occurs: int | None = None
    redefines: str | None = None
    value: str | None = None
    conditions: list[tuple[str, list[str]]] = field(default_factory=list)
    children: list["_Item"] = field(default_factory=list)
    # Computed by _layout.
    offset: int = 0
    length: int = 0
    type: str = ""
    digits: int | None = None
    scale: int | None = None
    signed: bool = False

    @property
    def extent(self) -> int:
        return self.length * (self.occurs or 1)

    def last_line(self) -> int:
        return max([self.end_line] + [child.last_line() for child in self.children])


@dataclass(frozen=True)
class _Condition:
    name: str
    values: list[str]
    end_line: int


def _quote(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def _value_list(tokens: tuple[_Token, ...], index: int, rel: str) -> tuple[list[str], int]:
    """Values of a VALUE clause from ``tokens[index]`` (after VALUE/IS/ARE),
    up to the next clause or the end of the entry."""
    values: list[str] = []

    def one(position: int) -> tuple[str, int]:
        if position >= len(tokens):
            raise HarnessError(f"{rel}:{tokens[-1].line}: VALUE clause ends without a value")
        token = tokens[position]
        if token.kind == "literal":
            return _quote(token.text), position + 1
        word = token.text.upper()
        if word == "ALL":
            if position + 1 >= len(tokens):
                raise HarnessError(f"{rel}:{token.line}: ALL without a literal")
            following = tokens[position + 1]
            if following.kind == "literal":
                return f"ALL {_quote(following.text)}", position + 2
            if following.text.upper() in _FIGURATIVE:
                return _FIGURATIVE[following.text.upper()], position + 2
            raise HarnessError(f"{rel}:{token.line}: ALL {following.text} is not a literal")
        if word in _FIGURATIVE:
            return _FIGURATIVE[word], position + 1
        if _NUMERIC_LITERAL_RE.fullmatch(token.text):
            return token.text, position + 1
        raise HarnessError(f"{rel}:{token.line}: {token.text!r} is not a valid VALUE literal")

    while index < len(tokens):
        token = tokens[index]
        word = token.text.upper() if token.kind == "word" else ""
        if word in ("THRU", "THROUGH"):
            if not values:
                raise HarnessError(f"{rel}:{token.line}: {word} without a lower bound")
            upper, index = one(index + 1)
            values[-1] = f"{values[-1]} THRU {upper}"
            continue
        if word in _CLAUSE_WORDS:
            break
        value, index = one(index)
        values.append(value)
    if not values:
        raise HarnessError(
            f"{rel}:{tokens[min(index, len(tokens) - 1)].line}: VALUE clause without a value"
        )
    return values, index


def _parse_entry(entry: _Entry, rel: str) -> _Item | _Condition | None:
    """An `_Item`, a `_Condition` for level 88, or None for level 66 (RENAMES
    describes no storage of its own)."""
    tokens = entry.tokens
    first = tokens[0]
    if first.kind != "word" or not first.text.isdigit():
        raise HarnessError(f"{rel}:{first.line}: expected a level number, found {first.text!r}")
    level = int(first.text)
    if not (1 <= level <= 49 or level in (66, 77, 88)):
        raise HarnessError(f"{rel}:{first.line}: invalid level number {first.text}")
    if level == 66:
        return None
    index = 1
    name = "FILLER"
    if (
        index < len(tokens)
        and tokens[index].kind == "word"
        and tokens[index].text.upper() not in _CLAUSE_WORDS
    ):
        name = tokens[index].text.upper()
        index += 1
    if level == 88:
        if index == 1:
            raise HarnessError(f"{rel}:{first.line}: level 88 entry without a condition name")
        if index >= len(tokens) or tokens[index].text.upper() not in ("VALUE", "VALUES"):
            raise HarnessError(f"{rel}:{first.line}: level 88 entry {name} without VALUE")
        index += 1
        if index < len(tokens) and tokens[index].text.upper() in ("IS", "ARE"):
            index += 1
        values, index = _value_list(tokens, index, rel)
        if index != len(tokens):
            raise HarnessError(
                f"{rel}:{tokens[index].line}: unexpected {tokens[index].text!r} in 88 {name}"
            )
        return _Condition(name, values, entry.end_line)

    item = _Item(level=level, name=name, line=first.line, end_line=entry.end_line)

    def operand(position: int, clause: str) -> _Token:
        if position >= len(tokens):
            raise HarnessError(f"{rel}:{tokens[-1].line}: {clause} without an operand")
        return tokens[position]

    while index < len(tokens):
        token = tokens[index]
        word = token.text.upper() if token.kind == "word" else ""
        if word == "REDEFINES":
            item.redefines = operand(index + 1, word).text.upper()
            index += 2
        elif word in ("PIC", "PICTURE"):
            index += 1
            if operand(index, word).text.upper() == "IS":
                index += 1
            item.pic = operand(index, word).text.upper()
            index += 1
        elif word == "USAGE":
            index += 1
            if operand(index, word).text.upper() == "IS":
                index += 1
            usage = operand(index, word).text.upper()
            if usage in _UNSUPPORTED_USAGES:
                raise HarnessError(f"{rel}:{token.line}: USAGE {usage} is not supported")
            if usage not in _USAGES:
                raise HarnessError(f"{rel}:{token.line}: unknown USAGE {usage}")
            item.usage = _USAGES[usage]
            index += 1
        elif word in _USAGES:
            item.usage = _USAGES[word]
            index += 1
        elif word in _UNSUPPORTED_USAGES:
            raise HarnessError(f"{rel}:{token.line}: USAGE {word} is not supported")
        elif word == "OCCURS":
            count = operand(index + 1, word)
            if not count.text.isdigit():
                raise HarnessError(
                    f"{rel}:{count.line}: OCCURS count {count.text!r} is not an integer"
                )
            item.occurs = int(count.text)
            index += 2
            if index < len(tokens) and tokens[index].text.upper() in ("TO", "DEPENDING"):
                raise HarnessError(f"{rel}:{token.line}: OCCURS DEPENDING ON is not supported")
            if index < len(tokens) and tokens[index].text.upper() == "TIMES":
                index += 1
            # KEY IS / INDEXED BY name the table's keys and indexes: no storage.
            while index < len(tokens) and tokens[index].text.upper() not in _CLAUSE_WORDS:
                index += 1
        elif word in ("VALUE", "VALUES"):
            index += 1
            if index < len(tokens) and tokens[index].text.upper() in ("IS", "ARE"):
                index += 1
            values, index = _value_list(tokens, index, rel)
            if len(values) != 1:
                raise HarnessError(f"{rel}:{token.line}: {name} declares {len(values)} values")
            item.value = values[0]
        elif word in ("JUST", "JUSTIFIED"):
            index += 1
            if index < len(tokens) and tokens[index].text.upper() == "RIGHT":
                index += 1
        elif word == "BLANK":
            index += 1
            if index < len(tokens) and tokens[index].text.upper() == "WHEN":
                index += 1
            if operand(index, word).text.upper() not in ("ZERO", "ZEROS", "ZEROES"):
                raise HarnessError(f"{rel}:{token.line}: BLANK WHEN must name ZERO")
            index += 1
        elif word in _SKIPPED_CLAUSES:
            index += 1
        elif word in _UNSUPPORTED_CLAUSES:
            raise HarnessError(
                f"{rel}:{token.line}: clause {word} is not supported by the layout generator"
            )
        else:
            raise HarnessError(
                f"{rel}:{token.line}: unexpected {token.text!r} in the entry for {name}"
            )
    return item


def _build_tree(entries: Iterable[_Entry], rel: str) -> list[_Item]:
    """Nest entries by level number. Entries below level 01 that no 01 entry
    contains (a fragment copybook) become roots of their own."""
    roots: list[_Item] = []
    stack: list[_Item] = []
    last: _Item | None = None
    for entry in entries:
        parsed = _parse_entry(entry, rel)
        if parsed is None:
            continue
        if isinstance(parsed, _Condition):
            if last is None:
                raise HarnessError(
                    f"{rel}:{entry.tokens[0].line}: 88 {parsed.name} qualifies no data item"
                )
            last.conditions.append((parsed.name, parsed.values))
            last.end_line = max(last.end_line, parsed.end_line)
            continue
        if parsed.level in (1, 77):
            stack = []
        while stack and stack[-1].level >= parsed.level:
            stack.pop()
        if stack:
            parent = stack[-1]
            if parent.level == 77:
                raise HarnessError(
                    f"{rel}:{parsed.line}: level 77 item {parent.name} cannot be a group"
                )
            parent.children.append(parsed)
        else:
            roots.append(parsed)
        stack.append(parsed)
        last = parsed
    return roots


# --------------------------------------------------------------------------
# Storage layout
# --------------------------------------------------------------------------

_PIC_SYMBOLS = set("XA9SVPZB0/,.*+-$")
_EDIT_SYMBOLS = set("ZB0/,.*+-$") | {"CR", "DB"}


def _expand_pic(pic: str, rel: str, line: int) -> list[str]:
    """Picture symbols with ``(n)`` repetitions expanded; CR and DB are one
    two-character symbol each."""
    symbols: list[str] = []
    index = 0
    while index < len(pic):
        if pic.startswith(("CR", "DB"), index):
            symbols.append(pic[index : index + 2])
            index += 2
            continue
        char = pic[index]
        if char not in _PIC_SYMBOLS:
            raise HarnessError(f"{rel}:{line}: unsupported symbol {char!r} in PICTURE {pic}")
        if index + 1 < len(pic) and pic[index + 1] == "(":
            close = pic.find(")", index + 2)
            count = pic[index + 2 : close] if close > 0 else ""
            if not count.isdigit() or int(count) == 0:
                raise HarnessError(f"{rel}:{line}: invalid repetition in PICTURE {pic}")
            symbols.extend([char] * int(count))
            index = close + 1
            continue
        symbols.append(char)
        index += 1
    return symbols


def _elementary(item: _Item, usage: str, rel: str) -> None:
    """Set type, length, digits, scale and signed of an elementary item."""
    if item.pic is None:
        if usage == "POINTER":
            item.type, item.length = "pointer", 4
            return
        raise HarnessError(f"{rel}:{item.line}: elementary item {item.name} has no PICTURE")
    if usage == "POINTER":
        raise HarnessError(f"{rel}:{item.line}: POINTER item {item.name} cannot have a PICTURE")
    symbols = _expand_pic(item.pic, rel, item.line)
    if any(symbol in ("X", "A") for symbol in symbols):
        if usage != "DISPLAY":
            raise HarnessError(f"{rel}:{item.line}: alphanumeric {item.name} must be DISPLAY")
        if any(symbol in ("S", "V", "P") for symbol in symbols):
            raise HarnessError(f"{rel}:{item.line}: PICTURE {item.pic} mixes X/A with S, V or P")
        item.type, item.length = "alphanumeric", len(symbols)
        return
    if any(symbol in _EDIT_SYMBOLS for symbol in symbols):
        if usage != "DISPLAY":
            raise HarnessError(f"{rel}:{item.line}: numeric-edited {item.name} must be DISPLAY")
        item.type = "edited"
        item.length = sum(len(symbol) for symbol in symbols if symbol not in ("S", "V", "P"))
        return
    digits = symbols.count("9")
    if digits == 0:
        raise HarnessError(f"{rel}:{item.line}: numeric PICTURE {item.pic} has no digit positions")
    if symbols.count("V") > 1 or symbols.count("S") > 1 or ("S" in symbols and symbols[0] != "S"):
        raise HarnessError(f"{rel}:{item.line}: malformed PICTURE {item.pic}")
    body = [symbol for symbol in symbols if symbol != "S"]
    if "V" in body:
        scale = sum(1 for symbol in body[body.index("V") + 1 :] if symbol in ("9", "P"))
    elif body[0] == "P":
        scale = sum(1 for symbol in body if symbol in ("9", "P"))
    else:
        scale = -body.count("P")
    item.digits, item.scale, item.signed = digits, scale, "S" in symbols
    if usage == "DISPLAY":
        item.type = "zoned-signed" if item.signed else "zoned"
        item.length = digits
    elif usage == "COMP-3":
        item.type, item.length = "comp-3", digits // 2 + 1
    elif usage == "BINARY":
        if digits > 18:
            raise HarnessError(f"{rel}:{item.line}: BINARY {item.name} has more than 18 digits")
        item.type = "binary"
        item.length = 2 if digits <= 4 else 4 if digits <= 9 else 8
    else:
        raise HarnessError(f"{rel}:{item.line}: USAGE {usage} cannot describe {item.name}")


def _layout(item: _Item, offset: int, inherited_usage: str | None, rel: str) -> None:
    """Assign offsets and lengths. A REDEFINES starts at its target and does not
    advance the next sibling; a group spans the furthest byte any child reaches,
    so a redefinition larger than its target widens the group (COADM02Y and
    COMEN02Y redefine 4 or 10 options as tables of 9 or 12)."""
    item.offset = offset
    # A grafted target is laid out a second time, as a group.
    item.digits, item.scale, item.signed = None, None, False
    usage = item.usage or inherited_usage
    if not item.children:
        _elementary(item, usage or "DISPLAY", rel)
        return
    if item.pic is not None:
        raise HarnessError(f"{rel}:{item.line}: group {item.name} has a PICTURE")
    cursor = end = offset
    siblings: dict[str, _Item] = {}
    for child in item.children:
        if child.redefines is not None:
            target = siblings.get(child.redefines)
            if target is None:
                raise HarnessError(
                    f"{rel}:{child.line}: {child.name} REDEFINES {child.redefines}, "
                    f"which is not an earlier item of {item.name}"
                )
            _layout(child, target.offset, usage, rel)
        else:
            _layout(child, cursor, usage, rel)
            cursor = child.offset + child.extent
        end = max(end, child.offset + child.extent)
        if child.name != "FILLER":
            siblings[child.name] = child
    item.type = "group"
    item.length = end - offset


def _fields(root: _Item, rel: str) -> list[dict[str, Any]]:
    """Every item of a record in declaration order, as layout field dicts."""
    fields: list[dict[str, Any]] = []
    fillers = 0
    paths: set[str] = set()

    def visit(item: _Item, parent_path: str | None) -> None:
        nonlocal fillers
        segment = item.name
        if item.name == "FILLER":
            fillers += 1
            segment = f"FILLER#{fillers}"
        path = segment if parent_path is None else f"{parent_path}.{segment}"
        if path in paths:
            raise HarnessError(f"{rel}:{item.line}: {path} is declared twice")
        paths.add(path)
        entry: dict[str, Any] = {
            "name": item.name,
            "path": path,
            "level": item.level,
            "offset": item.offset,
            "length": item.length,
            "type": item.type,
            "digits": item.digits,
            "scale": item.scale,
            "signed": item.signed,
            "occurs": item.occurs,
            "redefines": item.redefines,
            "value": item.value,
        }
        if item.conditions:
            conditions: dict[str, list[str]] = {}
            for name, values in item.conditions:
                if name in conditions:
                    raise HarnessError(f"{rel}:{item.line}: condition {name} is declared twice")
                conditions[name] = list(values)
            entry["conditions"] = conditions
        fields.append(entry)
        for child in item.children:
            visit(child, path)

    visit(root, None)
    return fields


def _record_entry(
    kind: str, copybook: str | None, root: _Item, source: str | None, recln: int | None, rel: str
) -> dict[str, Any]:
    return {
        "kind": kind,
        "copybook": copybook,
        "record": root.name,
        "source": source,
        "length": root.extent,
        "recln_comment": recln,
        "listcat": None,
        "fields": _fields(root, rel),
    }


# --------------------------------------------------------------------------
# Copybooks
# --------------------------------------------------------------------------

_RECLN_RE = re.compile(r"RECLN\s*=?\s*([0-9]+)", re.IGNORECASE)


@dataclass
class _Copybook:
    stem: str
    path: str
    kind: str
    recln: int | None
    roots: dict[str, _Item]  # record key -> laid-out root
    records: dict[str, dict[str, Any]]


def _recln_comment(raw_lines: list[str]) -> int | None:
    """The ``RECLN 300`` / ``RECLN = 350`` figure of the comment header."""
    for raw in raw_lines:
        if len(raw) > 7 and raw[6] in "*/":
            match = _RECLN_RE.search(raw[7:72])
            if match:
                return int(match.group(1))
    return None


def _classify(lines: list[_CodeLine], rel: str) -> str:
    if not lines:
        raise HarnessError(f"{rel}: the copybook holds no code")
    first = lines[0].text.split()[0].rstrip(".")
    if first.isdigit():
        return "record" if int(first) in (1, 77) else "fragment"
    return "procedure"


def _parse_copybook(path: Path, repo_root: Path) -> _Copybook:
    rel = _relative(path, repo_root)
    stem = path.stem.upper()
    raw_lines = _read_lines(path, TAB_WIDTH)
    lines = _code_lines(raw_lines, rel)
    kind = _classify(lines, rel)
    book = _Copybook(
        stem=stem, path=rel, kind=kind, recln=_recln_comment(raw_lines), roots={}, records={}
    )
    if kind == "procedure":
        return book
    roots = _build_tree(_entries(_tokenize(lines, rel), rel), rel)
    if kind == "fragment":
        # One record under a synthetic root named after the copybook, not one
        # record per top-level entry: the entries keep the offsets they have
        # inside the group that COPYs them, and REDEFINES between them resolve.
        if any(root.level in (1, 77) for root in roots):
            raise HarnessError(
                f"{rel}: fragment copybook mixes 01/77 records with lower-level entries"
            )
        synthetic = _Item(
            level=1,
            name=stem,
            line=roots[0].line,
            end_line=max(root.last_line() for root in roots),
            children=roots,
        )
        roots = [synthetic]
    for root in roots:
        if root.level not in (1, 77):
            raise HarnessError(
                f"{rel}:{root.line}: {root.name} at level {root.level} lies outside any 01 record"
            )
        key = f"{stem}/{root.name}"
        if key in book.roots:
            raise HarnessError(f"{rel}:{root.line}: record {root.name} is declared twice")
        _layout(root, 0, None, rel)
        book.roots[key] = root
        book.records[key] = _record_entry(
            kind, rel, root, _citation(rel, root.line, root.last_line()), book.recln, rel
        )
    return book


def parse_copybook(path: str | Path, repo_root: str | Path = REPO_ROOT) -> dict[str, Any]:
    """Parse one copybook: ``{path, kind, recln_comment, records}``, where
    ``records`` maps ``<STEM>/<01-NAME>`` to its record entry (empty for a
    procedure-division copybook)."""
    book = _parse_copybook(Path(path), Path(repo_root))
    return {
        "path": book.path,
        "kind": book.kind,
        "recln_comment": book.recln,
        "records": book.records,
    }


# --------------------------------------------------------------------------
# Program-derived and text records
# --------------------------------------------------------------------------


def _walk(item: _Item) -> Iterator[_Item]:
    yield item
    for child in item.children:
        yield from _walk(child)


def _program_record(lines: list[_CodeLine], name: str, rel: str) -> _Item:
    """The laid-out 01 entry ``name`` of a program, read up to the next 01/77
    entry or the first statement that is not a data description entry."""
    start = re.compile(rf"^\s*0?1\s+{re.escape(name)}(?=[\s.]|$)", re.IGNORECASE)
    for index, line in enumerate(lines):
        if start.match(line.text):
            break
    else:
        raise HarnessError(f"{rel}: no 01 {name} entry")
    entries: list[_Entry] = []
    for entry in _entries(_tokenize(lines[index:], rel), rel):
        head = entry.tokens[0]
        is_level = head.kind == "word" and head.text.isdigit()
        if entries and (not is_level or int(head.text) in (1, 77)):
            break
        entries.append(entry)
    roots = _build_tree(entries, rel)
    if len(roots) != 1:
        raise HarnessError(f"{rel}: 01 {name} does not form one record")
    _layout(roots[0], 0, None, rel)
    return roots[0]


def _statement_line(lines: list[_CodeLine], pattern: str, rel: str, statement: str) -> int:
    regex = re.compile(pattern, re.IGNORECASE)
    for line in lines:
        if regex.search(line.text):
            return line.number
    raise HarnessError(
        f"{rel}: '{statement}' not found, so the composed record no longer matches the program"
    )


def _name_pattern(name: str) -> str:
    return rf"(?<![\w-]){re.escape(name)}(?![\w-])"


def _graft(target: _Item, layout_root: _Item) -> None:
    """Describe ``target``'s bytes with the children of ``layout_root``, as if
    they had been declared under it; level numbers shift so the 01 maps to the
    target's level."""
    shift = target.level - layout_root.level
    children = copy.deepcopy(layout_root.children)
    for child in children:
        for item in _walk(child):
            item.level += shift
    target.children = children
    target.pic = None


def _derived_record(
    spec: _DerivedRecord, repo_root: Path, copybook_roots: dict[str, tuple[_Item, str]]
) -> dict[str, Any]:
    path = repo_root / spec.program
    rel = _relative(path, repo_root)
    lines = _code_lines(_read_lines(path, TAB_WIDTH), rel)
    root = _program_record(lines, spec.record, rel)
    declared_length = root.extent
    first, last = root.line, root.last_line()
    composition: list[dict[str, Any]] = []
    for overlay in spec.overlays:
        targets = [item for item in _walk(root) if item.name == overlay.target]
        if len(targets) != 1 or targets[0].children:
            raise HarnessError(
                f"{rel}: {spec.record} has no single elementary item {overlay.target}"
            )
        target = targets[0]
        if "/" in overlay.layout:
            if overlay.layout not in copybook_roots:
                raise HarnessError(
                    f"{rel}: layout {overlay.layout} for {overlay.target} does not exist"
                )
            layout_root, layout_source = copybook_roots[overlay.layout]
        else:
            layout_root = _program_record(lines, overlay.layout, rel)
            first, last = min(first, layout_root.line), max(last, layout_root.last_line())
            layout_source = _citation(rel, layout_root.line, layout_root.last_line())
        if layout_root.extent != target.extent:
            raise HarnessError(
                f"{rel}:{target.line}: {overlay.target} holds {target.extent} bytes but "
                f"{overlay.layout} describes {layout_root.extent}"
            )
        statement = f"MOVE {overlay.moved_record} TO {overlay.target}"
        move_line = _statement_line(
            lines,
            rf"(?<![\w-])MOVE\s+{_name_pattern(overlay.moved_record)}"
            rf"\s+TO\s+{_name_pattern(overlay.target)}",
            rel,
            statement,
        )
        _graft(target, layout_root)
        composition.append(
            {
                "target": overlay.target,
                "layout": overlay.layout,
                "layout_source": layout_source,
                "move": _citation(rel, move_line, move_line),
            }
        )
    write_line = _statement_line(
        lines,
        rf"(?<![\w-])WRITE\s+{_name_pattern(spec.file_record)}"
        rf"\s+FROM\s+{_name_pattern(spec.record)}",
        rel,
        f"WRITE {spec.file_record} FROM {spec.record}",
    )
    file_record = _program_record(lines, spec.file_record, rel)
    _layout(root, 0, None, rel)
    if root.extent != declared_length or file_record.extent != declared_length:
        raise HarnessError(
            f"{rel}: {spec.record} composes to {root.extent} bytes, declared {declared_length}, "
            f"written as {spec.file_record} of {file_record.extent}"
        )
    entry = _record_entry("derived", rel, root, _citation(rel, first, last), None, rel)
    entry["composition"] = composition
    entry["written_as"] = {
        "record": spec.file_record,
        "write": _citation(rel, write_line, write_line),
    }
    return entry


def _text_record(length: int, datasets: tuple[str, ...]) -> dict[str, Any]:
    root = _Item(level=1, name="LINE", line=0, end_line=0, pic=f"X({length})")
    _layout(root, 0, None, GENERATED_BY)
    entry = _record_entry("text", None, root, None, None, GENERATED_BY)
    entry["datasets"] = list(datasets)
    return entry


# --------------------------------------------------------------------------
# LISTCAT
# --------------------------------------------------------------------------

_LISTCAT_SECTION_RE = re.compile(
    r"^0(CLUSTER|DATA|INDEX|AIX|PATH|NONVSAM|GDG BASE)\s+-+\s+(\S+)\s*$"
)
_LISTCAT_ATTR_RE = re.compile(r"(?<![A-Z0-9-])(KEYLEN|RKP|AVGLRECL|MAXLRECL|AXRKP)-+([0-9]+)")
# Carriage control (0 or -) may precede a line that follows a page break.
_LISTCAT_ASSOC_RE = re.compile(r"^[0\- ]?\s*(CLUSTER|AIX|PATH)-{2,}(\S+)\s*$")
_LISTCAT_REQUIRED = ("KEYLEN", "RKP", "AVGLRECL", "MAXLRECL")


def parse_listcat(path: str | Path, repo_root: str | Path = REPO_ROOT) -> dict[str, dict[str, Any]]:
    """Key and record-size attributes of every cluster and alternate index:
    ``{name: {kind, keylen, rkp, avglrecl, maxlrecl, source}}``, plus
    ``axrkp``, ``unique_key``, ``base`` and ``path`` for an alternate index.
    The values come from the DATA component the entry owns; ``source`` cites
    the LISTCAT lines they were read from."""
    path, repo_root = Path(path), Path(repo_root)
    rel = _relative(path, repo_root)
    sections: list[dict[str, Any]] = []
    for number, line in enumerate(_read_lines(path, None), start=1):
        match = _LISTCAT_SECTION_RE.match(line)
        if match:
            sections.append(
                {"kind": match.group(1), "name": match.group(2), "line": number, "body": []}
            )
        elif sections:
            sections[-1]["body"].append((number, line))

    owners: dict[str, dict[str, Any]] = {}
    for section in sections:
        if section["kind"] not in ("CLUSTER", "AIX"):
            continue
        entry: dict[str, Any] = {"kind": section["kind"].lower()}
        if section["kind"] == "AIX":
            for _, line in section["body"]:
                match = _LISTCAT_ASSOC_RE.match(line)
                if match and match.group(1) == "CLUSTER":
                    entry["base"] = match.group(2)
                elif match and match.group(1) == "PATH":
                    entry["path"] = match.group(2)
            if "base" not in entry:
                raise HarnessError(
                    f"{rel}:{section['line']}: AIX {section['name']} names no base cluster"
                )
            entry.setdefault("path", None)
        owners[section["name"]] = entry

    found: dict[str, dict[str, Any]] = {}
    for section in sections:
        if section["kind"] != "DATA":
            continue
        owner = section["name"][: -len(".DATA")] if section["name"].endswith(".DATA") else None
        attributes: dict[str, tuple[int, int]] = {}
        unique_key: bool | None = None
        for number, line in section["body"]:
            association = _LISTCAT_ASSOC_RE.match(line)
            if association and association.group(1) in ("CLUSTER", "AIX"):
                owner = association.group(2)
            for match in _LISTCAT_ATTR_RE.finditer(line):
                attributes.setdefault(match.group(1), (int(match.group(2)), number))
            if re.search(r"(?<![A-Z])NONUNIQKEY(?![A-Z])", line):
                unique_key = False
            elif re.search(r"(?<![A-Z])UNIQUEKEY(?![A-Z])", line):
                unique_key = True
        if owner not in owners:
            continue
        entry = owners[owner]
        required = _LISTCAT_REQUIRED + (("AXRKP",) if entry["kind"] == "aix" else ())
        missing = [name for name in required if name not in attributes]
        if missing:
            raise HarnessError(
                f"{rel}:{section['line']}: DATA component of {owner} lacks {', '.join(missing)}"
            )
        lines = [attributes[name][1] for name in required]
        entry.update({name.lower(): attributes[name][0] for name in required})
        entry["source"] = _citation(rel, min(lines), max(lines))
        if entry["kind"] == "aix":
            entry["unique_key"] = bool(unique_key)
        found[owner] = entry
    return {name: found[name] for name in sorted(found)}


def _record_listcat(
    cluster: str, listcat: dict[str, dict[str, Any]], copybook: str
) -> dict[str, Any]:
    info = listcat.get(cluster)
    if info is None or info["kind"] != "cluster":
        raise HarnessError(
            f"LAYOUT_CLUSTER maps {copybook} to {cluster}, which LISTCAT does not define"
        )
    alternates = [
        {
            "name": name,
            "keylen": aix["keylen"],
            "axrkp": aix["axrkp"],
            "unique_key": aix["unique_key"],
            "path": aix["path"],
            "source": aix["source"],
        }
        for name, aix in listcat.items()
        if aix["kind"] == "aix" and aix["base"] == cluster
    ]
    return {
        "cluster": cluster,
        "keylen": info["keylen"],
        "rkp": info["rkp"],
        "avglrecl": info["avglrecl"],
        "maxlrecl": info["maxlrecl"],
        "source": info["source"],
        "alternate_indexes": alternates,
    }


# --------------------------------------------------------------------------
# BMS maps
# --------------------------------------------------------------------------

_BMS_CONTINUATION = 71  # 0-based index of column 72
_BMS_RESUME = 15  # continuation text resumes at column 16
_BMS_IGNORED_OPS = {"END", "TITLE", "PRINT", "EJECT", "SPACE"}


@dataclass(frozen=True)
class _BmsStatement:
    line: int
    label: str | None
    op: str
    operands: str


def _bms_statements(raw_lines: list[str], rel: str) -> Iterator[_BmsStatement]:
    """Assembler statements: label in column 1, then operation and operands;
    a non-blank column 72 continues the statement at column 16 of the next
    line. Operands end at the first blank outside quotes (the rest of the
    line is a remark); a quoted string runs to column 71 and resumes at
    column 16 of the continuation line, leading blanks included."""
    index = 0
    while index < len(raw_lines):
        raw = raw_lines[index]
        if "\t" in raw:
            raise HarnessError(f"{rel}:{index + 1}: TAB characters break assembler columns")
        if not raw.strip() or raw.startswith("*"):
            index += 1
            continue
        number = index + 1
        parts = [raw]
        while len(parts[-1]) > _BMS_CONTINUATION and parts[-1][_BMS_CONTINUATION] != " ":
            index += 1
            if index >= len(raw_lines):
                raise HarnessError(f"{rel}:{number}: continued statement has no continuation line")
            if raw_lines[index][:_BMS_RESUME].strip():
                raise HarnessError(f"{rel}:{index + 1}: continuation must leave columns 1-15 blank")
            parts.append(raw_lines[index])
        index += 1

        head = parts[0][:_BMS_CONTINUATION]
        match = re.match(r"(\S*)\s+(\S+)\s*", head)
        if match is None:
            raise HarnessError(f"{rel}:{number}: statement without an operation")
        label = match.group(1) or None
        op = match.group(2).upper()
        chars: list[str] = []
        in_quote = False
        for part_index, part in enumerate(parts):
            segment = part[:_BMS_CONTINUATION].ljust(_BMS_CONTINUATION)
            position = match.end() if part_index == 0 else _BMS_RESUME
            while position < _BMS_CONTINUATION:
                char = segment[position]
                if in_quote:
                    chars.append(char)
                    if char == "'":
                        if position + 1 < _BMS_CONTINUATION and segment[position + 1] == "'":
                            chars.append("'")
                            position += 2
                            continue
                        in_quote = False
                    position += 1
                    continue
                if char == " ":
                    break
                if char == "'":
                    in_quote = True
                chars.append(char)
                position += 1
            if in_quote and part_index == len(parts) - 1:
                raise HarnessError(f"{rel}:{number}: unterminated quoted string")
        yield _BmsStatement(number, label, op, "".join(chars))


def _bms_operands(text: str, rel: str, line: int) -> dict[str, Any]:
    """``KEY=VALUE`` operands: a parenthesized list becomes a list of strings,
    a quoted string its text with ``''`` and ``&&`` unescaped."""
    items: list[str] = []
    current: list[str] = []
    depth = 0
    in_quote = False
    index = 0
    while index < len(text):
        char = text[index]
        if in_quote:
            current.append(char)
            if char == "'":
                if index + 1 < len(text) and text[index + 1] == "'":
                    current.append("'")
                    index += 2
                    continue
                in_quote = False
            index += 1
            continue
        if char == "'":
            in_quote = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "," and depth == 0:
            items.append("".join(current))
            current = []
            index += 1
            continue
        current.append(char)
        index += 1
    if current:
        items.append("".join(current))
    operands: dict[str, Any] = {}
    for item in items:
        key, separator, value = item.partition("=")
        key = key.upper()
        if key in operands:
            raise HarnessError(f"{rel}:{line}: operand {key} given twice")
        if not separator:
            operands[key] = None
        elif len(value) >= 2 and value[0] == "'" and value[-1] == "'":
            operands[key] = value[1:-1].replace("''", "'").replace("&&", "&")
        elif value.startswith("(") and value.endswith(")"):
            operands[key] = [part.strip() for part in value[1:-1].split(",") if part.strip()]
        else:
            operands[key] = value
    return operands


def _bms_int(value: Any, key: str, rel: str, line: int) -> int:
    if not isinstance(value, str) or not value.isdigit():
        raise HarnessError(f"{rel}:{line}: {key}={value!r} is not a number")
    return int(value)


def _bms_position(value: Any, columns: int, rel: str, line: int) -> tuple[int, int]:
    """``POS=(row,col)``, or ``POS=n`` as the 0-based offset into the map."""
    if isinstance(value, list) and len(value) == 2:
        return _bms_int(value[0], "POS", rel, line), _bms_int(value[1], "POS", rel, line)
    if isinstance(value, str):
        offset = _bms_int(value, "POS", rel, line)
        return offset // columns + 1, offset % columns + 1
    raise HarnessError(f"{rel}:{line}: DFHMDF without a valid POS")


def parse_bms(path: str | Path, repo_root: str | Path = REPO_ROOT) -> dict[str, Any]:
    """Parse one BMS mapset: ``{source, mapset, maps}``, each map
    ``{map, size, dfhmdf_count, fields, statics}``.

    ``POS`` is the attribute byte's position (1-based); the field's data
    starts one column to its right. Named fields go to ``fields``; unnamed
    fields with an ``INITIAL`` text go to ``statics``, which identify the
    screen."""
    path, repo_root = Path(path), Path(repo_root)
    rel = _relative(path, repo_root)
    mapset: str | None = None
    maps: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    for statement in _bms_statements(_read_lines(path, None), rel):
        if statement.op in _BMS_IGNORED_OPS:
            continue
        operands = _bms_operands(statement.operands, rel, statement.line)
        where = f"{rel}:{statement.line}"
        if statement.op == "DFHMSD":
            if operands.get("TYPE") == "FINAL":
                current = None
                continue
            if mapset is not None:
                raise HarnessError(f"{where}: second DFHMSD in one mapset source")
            if not statement.label:
                raise HarnessError(f"{where}: DFHMSD without a mapset name")
            mapset = statement.label.upper()
        elif statement.op == "DFHMDI":
            if mapset is None or not statement.label:
                raise HarnessError(f"{where}: DFHMDI needs a name and a preceding DFHMSD")
            size = operands.get("SIZE")
            if not isinstance(size, list) or len(size) != 2:
                raise HarnessError(f"{where}: DFHMDI {statement.label} without SIZE=(rows,columns)")
            current = {
                "map": statement.label.upper(),
                "size": [
                    _bms_int(size[0], "SIZE", rel, statement.line),
                    _bms_int(size[1], "SIZE", rel, statement.line),
                ],
                "dfhmdf_count": 0,
                "fields": [],
                "statics": [],
            }
            maps.append(current)
        elif statement.op == "DFHMDF":
            if current is None:
                raise HarnessError(f"{where}: DFHMDF outside a DFHMDI map")
            current["dfhmdf_count"] += 1
            rows, columns = current["size"]
            row, col = _bms_position(operands.get("POS"), columns, rel, statement.line)
            if not (1 <= row <= rows and 1 <= col <= columns):
                raise HarnessError(
                    f"{where}: POS=({row},{col}) lies outside the {rows}x{columns} map"
                )
            initial = operands.get("INITIAL")
            if initial is not None and not isinstance(initial, str):
                raise HarnessError(f"{where}: INITIAL must be a quoted string")
            if "LENGTH" in operands:
                length = _bms_int(operands["LENGTH"], "LENGTH", rel, statement.line)
            elif initial is not None:
                length = len(initial)
            else:
                raise HarnessError(f"{where}: DFHMDF without LENGTH or INITIAL")
            if initial is not None and len(initial) > length:
                raise HarnessError(
                    f"{where}: INITIAL holds {len(initial)} characters but "
                    f"LENGTH={length}: {initial!r}"
                )
            if statement.label:
                name = statement.label.upper()
                if any(existing["name"] == name for existing in current["fields"]):
                    raise HarnessError(
                        f"{where}: field {name} is defined twice in {current['map']}"
                    )
                attrb = operands.get("ATTRB")
                current["fields"].append(
                    {
                        "name": name,
                        "row": row,
                        "col": col,
                        "length": length,
                        "attrb": (
                            []
                            if attrb is None
                            else [attrb] if isinstance(attrb, str) else list(attrb)
                        ),
                        "color": operands.get("COLOR"),
                        "initial": initial,
                        "picin": operands.get("PICIN"),
                        "picout": operands.get("PICOUT"),
                    }
                )
            elif initial is not None:
                current["statics"].append(
                    {"row": row, "col": col, "length": length, "text": initial}
                )
        else:
            raise HarnessError(f"{where}: unsupported BMS operation {statement.op}")
    if mapset is None or not maps:
        raise HarnessError(f"{rel}: no DFHMSD mapset with a DFHMDI map")
    return {"source": rel, "mapset": mapset, "maps": maps}


# --------------------------------------------------------------------------
# Generation, loading, lookup
# --------------------------------------------------------------------------


def _check_identifiable(screens: dict[str, dict[str, Any]]) -> None:
    """A screen is identified by finding all of a map's static texts at their
    positions, so a map whose statics all recur on another map would also match
    that map's screens."""
    texts = {
        stem: {(static["row"], static["col"], static["text"]) for static in entry["statics"]}
        for stem, entry in screens.items()
    }
    for stem, statics in sorted(texts.items()):
        if not statics:
            raise HarnessError(
                f"{screens[stem]['source']}: map has no static text to identify it by"
            )
        for other, other_statics in sorted(texts.items()):
            if other != stem and statics <= other_statics:
                raise HarnessError(
                    f"every static text of {stem} also appears on {other}, "
                    "so its screens cannot be told apart"
                )


def generate(repo_root: str | Path = REPO_ROOT) -> dict[str, Any]:
    """Build the complete layout mapping from the sources under ``repo_root``.
    The result depends only on the source bytes: no dates, no absolute paths."""
    root = Path(repo_root).resolve()
    sources: dict[str, str] = {}

    def read(path: Path) -> None:
        sources[_relative(path, root)] = sha256_file(path)

    copybooks: dict[str, dict[str, Any]] = {}
    records: dict[str, dict[str, Any]] = {}
    copybook_roots: dict[str, tuple[_Item, str]] = {}
    copybook_files = sorted(
        path
        for path in (root / COPYBOOK_DIR).iterdir()
        if path.is_file() and path.suffix.lower() == ".cpy"
    )
    for path in copybook_files:
        book = _parse_copybook(path, root)
        read(path)
        if book.stem in copybooks:
            raise HarnessError(
                f"{book.path} and {copybooks[book.stem]['path']} share the stem {book.stem}"
            )
        copybooks[book.stem] = {
            "path": book.path,
            "kind": book.kind,
            "recln_comment": book.recln,
            "records": sorted(book.records),
        }
        for key, entry in book.records.items():
            records[key] = entry
            copybook_roots[key] = (book.roots[key], entry["source"])

    listcat_path = root / LISTCAT_PATH
    listcat = parse_listcat(listcat_path, root)
    read(listcat_path)
    for stem, cluster in sorted(LAYOUT_CLUSTER.items()):
        book_entry = copybooks.get(stem)
        if book_entry is None or len(book_entry["records"]) != 1:
            raise HarnessError(
                f"LAYOUT_CLUSTER names {stem}, which is not a single-record copybook"
            )
        records[book_entry["records"][0]]["listcat"] = _record_listcat(cluster, listcat, stem)

    for spec in DERIVED_RECORDS:
        if spec.key in records:
            raise HarnessError(f"derived record {spec.key} collides with a copybook record")
        records[spec.key] = _derived_record(spec, root, copybook_roots)
        read(root / spec.program)

    for length, datasets in sorted(TEXT_LAYOUTS.items()):
        records[f"TEXT/{length}"] = _text_record(length, datasets)

    screens: dict[str, dict[str, Any]] = {}
    bms_files = sorted(
        path
        for path in (root / BMS_DIR).iterdir()
        if path.is_file() and path.suffix.lower() == ".bms"
    )
    for path in bms_files:
        parsed = parse_bms(path, root)
        read(path)
        stem = path.stem.upper()
        if len(parsed["maps"]) != 1:
            # `@map` names the mapset, so identifying a screen by its static
            # texts needs exactly one map per mapset.
            raise HarnessError(
                f"{parsed['source']} defines {len(parsed['maps'])} maps; "
                "screens are keyed by mapset"
            )
        only = parsed["maps"][0]
        screens[stem] = {
            "source": parsed["source"],
            "mapset": parsed["mapset"],
            "map": only["map"],
            "size": only["size"],
            "dfhmdf_count": only["dfhmdf_count"],
            "fields": only["fields"],
            "statics": only["statics"],
        }
    _check_identifiable(screens)

    return {
        "version": SCHEMA_VERSION,
        "generated_by": GENERATED_BY,
        "sources": dict(sorted(sources.items())),
        "copybooks": copybooks,
        "records": records,
        "screens": screens,
        "listcat": listcat,
    }


@functools.lru_cache(maxsize=None)
def load(path: str | Path = LAYOUTS_FILE) -> dict[str, Any]:
    """The committed layout file, parsed once per process. Callers share the
    returned mapping and must not modify it."""
    data = load_yaml(path, missing_ok=True)
    if data is None:
        raise HarnessError(f"{path} is missing or empty: run python {GENERATED_BY} --write")
    if not isinstance(data, dict) or data.get("version") != SCHEMA_VERSION:
        raise HarnessError(f"{path} is not a version {SCHEMA_VERSION} layout file")
    return data


def record(name: str) -> dict[str, Any]:
    """A record layout by key (``CVACT01Y/ACCOUNT-RECORD``, ``TEXT/133``), or by
    the stem of a copybook that holds exactly one record (``CVACT01Y``)."""
    layouts = load()
    records = layouts["records"]
    for key in (name, name.upper()):
        if key in records:
            return records[key]
    book = layouts["copybooks"].get(name.upper())
    if book is not None:
        if len(book["records"]) == 1:
            return records[book["records"][0]]
        raise HarnessError(
            f"copybook {name} holds {len(book['records'])} records; "
            f"name one of: {', '.join(book['records']) or 'none'}"
        )
    raise HarnessError(f"unknown record layout {name!r}")


def record_length(name: str) -> int:
    return record(name)["length"]


def field_map(layout_name: str) -> dict[str, dict[str, Any]]:
    """The fields of a record by path, and also by name where the name is
    unique within the record (FILLERs only by path)."""
    fields = record(layout_name)["fields"]
    counts: dict[str, int] = {}
    for entry in fields:
        counts[entry["name"]] = counts.get(entry["name"], 0) + 1
    mapping = {entry["path"]: entry for entry in fields}
    for entry in fields:
        name = entry["name"]
        if name != "FILLER" and counts[name] == 1:
            mapping.setdefault(name, entry)
    return mapping


def screen(mapset: str) -> dict[str, Any]:
    """The screen layout of a mapset stem (the ``@map`` value, e.g. ``COSGN00``)."""
    screens = load()["screens"]
    key = mapset.upper()
    if key not in screens:
        raise HarnessError(f"unknown mapset {mapset!r}; known: {', '.join(sorted(screens))}")
    return screens[key]


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------


def _first_difference(left: Any, right: Any, path: str = "") -> str | None:
    if isinstance(left, dict) and isinstance(right, dict):
        for key in sorted(set(left) | set(right), key=str):
            child = f"{path}.{key}" if path else str(key)
            if key not in left or key not in right:
                return child
            found = _first_difference(left[key], right[key], child)
            if found:
                return found
        return None
    if isinstance(left, list) and isinstance(right, list):
        for index, (left_item, right_item) in enumerate(zip(left, right)):
            found = _first_difference(left_item, right_item, f"{path}[{index}]")
            if found:
                return found
        return f"{path}[{min(len(left), len(right))}]" if len(left) != len(right) else None
    if type(left) is type(right) and left == right:
        return None
    return path or "(top level)"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="layouts.py",
        description="Generate or check tests/inventory/layouts.yaml from app/cpy, app/bms, "
        "app/catlg/LISTCAT.txt and app/cbl/CBTRN02C.cbl.",
    )
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--write", action="store_true", help="write tests/inventory/layouts.yaml")
    action.add_argument(
        "--check", action="store_true", help="exit 1 unless the committed file is current"
    )
    args = parser.parse_args(argv)

    shown = _relative(LAYOUTS_FILE, REPO_ROOT)
    try:
        generated = generate()
    except HarnessError as exc:
        print(f"layouts: {exc}", file=sys.stderr)
        return 1
    text = dump_yaml(generated)
    if args.write:
        written = write_text(LAYOUTS_FILE, text)
        print(
            f"{'wrote' if written else 'unchanged'} {shown}: {len(generated['records'])} records, "
            f"{len(generated['screens'])} screens, {len(generated['copybooks'])} copybooks"
        )
        return 0
    try:
        committed_text = LAYOUTS_FILE.read_text(encoding="utf-8")
    except FileNotFoundError:
        print(f"{shown} is missing: run python {GENERATED_BY} --write", file=sys.stderr)
        return 1
    if committed_text == text:
        print(f"{shown} is current")
        return 0
    try:
        committed = load_yaml(LAYOUTS_FILE)
    except yaml.YAMLError as exc:
        print(
            f"{shown} is not valid YAML ({exc}): run python {GENERATED_BY} --write", file=sys.stderr
        )
        return 1
    where = _first_difference(committed, generated) or "(formatting only)"
    print(
        f"{shown} is stale; first difference at {where}: run python {GENERATED_BY} --write",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
