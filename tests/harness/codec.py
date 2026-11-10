"""Field and record codec for CardDemo data: text, zoned and overpunch-signed
zoned decimals, ``COMP-3``, big-endian binary, in ASCII or cp037.

Field descriptors are the field dicts of ``tests/inventory/layouts.yaml``
(``type``, ``length``, ``digits``, ``scale``, ``signed``, ...). A record
layout is a layout name such as ``CVACT01Y/ACCOUNT-RECORD`` (resolved through
``harness.layouts.record``) or a record dict of that file.

Encodings
    ``ascii`` is the encoding of the repository's ASCII data, of GnuCOBOL files
    and of the UniKix copy's ``seqfiles``; text is decoded as latin-1, so every
    byte survives (an ``x'00'`` fill stays ``"\\x00"``). ``cp037`` is the
    EBCDIC of the z/OS data sets. Both are one-to-one over all 256 byte values.

Decoded values
    Text fields (``alphanumeric``, ``edited``, ``pointer``, and ``group`` when
    groups are asked for) decode to ``str`` with every trailing space kept;
    nothing is trimmed or case-folded. Numeric fields decode to ``Decimal``
    with exactly ``scale`` decimal places (``Decimal("194.00")``). Numeric
    bytes that are not valid for the field's type decode to ``Invalid``,
    never to an exception, so a record of ``x'00'`` or spaces still decodes.
    ``to_plain`` turns decoded values into YAML-safe data.

Signs
    A signed zoned decimal carries its sign as a trailing overpunch: ``{`` and
    ``A``-``I`` are +0..9, ``}`` and ``J``-``R`` are -0..9 (cp037 zones ``C``
    and ``D``); a plain digit (zone ``F``) is positive. ``-fsign=EBCDIC``
    makes GnuCOBOL write ASCII data the same way. A negative zero decodes as
    zero with the scale kept (``Decimal("0.00")``), because COBOL compares and
    moves it as zero.

Every function is pure: the module reads nothing except layouts through
``harness.layouts``.
"""

from __future__ import annotations

import functools
import re
import sys
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Any, Mapping

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from harness import HarnessError  # noqa: E402
from harness import layouts as _layouts  # noqa: E402

__all__ = [
    "ENCODINGS",
    "RAW_KEY",
    "TYPES",
    "CodecError",
    "Invalid",
    "decode",
    "decode_record",
    "encode",
    "encode_record",
    "field_key",
    "resolve_layout",
    "split_records",
    "to_plain",
]

# Encoding name -> Python codec.
ENCODINGS = {"ascii": "latin-1", "cp037": "cp037"}

TEXT_TYPES = frozenset({"alphanumeric", "edited", "pointer", "group"})
NUMERIC_TYPES = frozenset({"zoned", "zoned-signed", "comp-3", "binary"})
TYPES = TEXT_TYPES | NUMERIC_TYPES

# encode_record key that replaces the whole record image with a text.
RAW_KEY = "@raw"

_POSITIVE_OVERPUNCH = "{ABCDEFGHI"
_NEGATIVE_OVERPUNCH = "}JKLMNOPQR"
_DIGITS = "0123456789"
_POSITIVE_NIBBLES = frozenset({0xA, 0xC, 0xE, 0xF})
_NEGATIVE_NIBBLES = frozenset({0xB, 0xD})
_NUMERIC_TEXT_RE = re.compile(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)")


class CodecError(HarnessError):
    """A value that cannot be encoded, or a call that does not fit the field
    or layout (wrong length, unknown key, unknown type). Invalid *content*
    found while decoding is never an error: it decodes to ``Invalid``."""

    def __init__(self, field: str | None, value: Any, reason: str) -> None:
        self.field = field
        self.value = value
        self.reason = reason
        where = f"{field}: " if field else ""
        super().__init__(f"{where}{reason} (value {value!r})")


@dataclass(frozen=True)
class Invalid:
    """Bytes that are not a valid value of the field's numeric type, kept as
    lower-case hex. Two instances are equal when their bytes are."""

    raw: str

    def __post_init__(self) -> None:
        if not isinstance(self.raw, str) or not re.fullmatch(r"(?:[0-9a-f]{2})*", self.raw):
            raise CodecError(None, self.raw, "Invalid.raw must be lower-case hex of whole bytes")

    @classmethod
    def of(cls, data: bytes) -> "Invalid":
        return cls(bytes(data).hex())

    @property
    def data(self) -> bytes:
        return bytes.fromhex(self.raw)

    def to_plain(self) -> dict[str, str]:
        return {"raw": self.raw}


def to_plain(value: Any) -> Any:
    """YAML-safe form of a decoded value: ``Decimal`` -> its fixed-point
    string with the scale kept (``"194.00"``, ``"-0.50"``), ``Invalid`` ->
    ``{"raw": "<hex>"}``, ``str``, ``int`` and ``None`` unchanged; dicts,
    lists and tuples are converted element by element (tuples become lists).
    A float, or any other type, raises ``CodecError``: numbers are never
    floats."""
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise CodecError(None, value, "a decoded number must be finite")
        # format "f" never switches to exponent notation (str(Decimal("0E-8"))
        # would give "0E-8"), so the text always shows the scale.
        return format(value, "f")
    if isinstance(value, Invalid):
        return value.to_plain()
    if value is None or isinstance(value, (str, int)):
        return value
    if isinstance(value, Mapping):
        return {key: to_plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [to_plain(item) for item in value]
    raise CodecError(None, value, f"cannot convert {type(value).__name__} to plain data")


def _codec(encoding: str) -> str:
    try:
        return ENCODINGS[encoding]
    except (KeyError, TypeError):
        raise CodecError(
            None, encoding, f"unknown encoding; use one of {', '.join(sorted(ENCODINGS))}"
        ) from None


# --------------------------------------------------------------------------
# Field descriptors
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Spec:
    """A field descriptor reduced to what the codec needs, with defaults
    derived and checked once."""

    label: str
    type: str
    length: int | None  # None only for a text field given without a length
    digits: int | None
    scale: int
    signed: bool


def _binary_length(digits: int) -> int:
    return 2 if digits <= 4 else 4 if digits <= 9 else 8


def _numeric_length(type_: str, digits: int) -> int:
    if type_ in ("zoned", "zoned-signed"):
        return digits
    if type_ == "comp-3":
        return digits // 2 + 1
    return _binary_length(digits)


def _spec(field: Mapping[str, Any]) -> _Spec:
    if isinstance(field, _Spec):
        return field
    if not isinstance(field, Mapping):
        raise CodecError(None, field, "a field descriptor must be a mapping")
    label = str(field.get("path") or field.get("name") or field.get("type") or "field")
    type_ = field.get("type")
    if type_ not in TYPES:
        raise CodecError(label, type_, f"unknown field type; use one of {', '.join(sorted(TYPES))}")
    length = field.get("length")
    if length is not None and (
        isinstance(length, bool) or not isinstance(length, int) or length <= 0
    ):
        raise CodecError(label, length, "length must be a positive integer")
    if type_ in TEXT_TYPES:
        if type_ == "pointer" and length is None:
            length = 4
        return _Spec(label, type_, length, None, 0, False)
    digits = field.get("digits")
    if isinstance(digits, bool) or not isinstance(digits, int) or not 1 <= digits <= 38:
        raise CodecError(label, digits, "a numeric field needs digits between 1 and 38")
    scale = field.get("scale")
    scale = 0 if scale is None else scale
    if isinstance(scale, bool) or not isinstance(scale, int):
        raise CodecError(label, scale, "scale must be an integer")
    if type_ == "binary" and digits > 18:
        raise CodecError(label, digits, "a binary field holds at most 18 digits")
    expected = _numeric_length(type_, digits)
    if length is None:
        length = expected
    elif length != expected:
        raise CodecError(
            label, length, f"a {type_} field of {digits} digits is {expected} bytes long"
        )
    if type_ == "zoned-signed":
        signed = True
    elif type_ == "zoned":
        signed = False
    else:
        signed = bool(field.get("signed"))
    return _Spec(label, type_, length, digits, scale, signed)


def _check_length(spec: _Spec, raw: bytes) -> bytes:
    if not isinstance(raw, (bytes, bytearray, memoryview)):
        raise CodecError(spec.label, raw, "raw data must be bytes")
    raw = bytes(raw)
    if spec.length is not None and len(raw) != spec.length:
        raise CodecError(spec.label, raw, f"expected {spec.length} bytes, got {len(raw)}")
    return raw


# --------------------------------------------------------------------------
# Text
# --------------------------------------------------------------------------


def _encode_text(spec: _Spec, value: Any, codec: str) -> bytes:
    """Left-justify and pad with spaces, as MOVE to an alphanumeric item does;
    a longer value is refused rather than truncated."""
    if not isinstance(value, str):
        # An unquoted YAML scalar such as 0500024453765740 loads as a number
        # (here even an octal one), so text must arrive as a string.
        raise CodecError(spec.label, value, "a text field takes a string; quote the value in YAML")
    if spec.length is None:
        raise CodecError(spec.label, value, "the field descriptor has no length")
    if len(value) > spec.length:
        raise CodecError(spec.label, value, f"longer than the field's {spec.length} characters")
    try:
        return value.ljust(spec.length).encode(codec)
    except UnicodeEncodeError:
        raise CodecError(spec.label, value, f"holds a character {codec} cannot encode") from None


# --------------------------------------------------------------------------
# Numbers
# --------------------------------------------------------------------------


def _number(negative: bool, digits: str, scale: int) -> Decimal:
    """The exact value of an unscaled digit string, with exactly ``scale``
    decimal places (none for a negative scale, whose ``P`` positions are
    zeros). Built from a tuple, so no context precision or rounding applies;
    a zero is never negative."""
    if scale < 0:
        digits, scale = digits + "0" * -scale, 0
    sign = 1 if negative and digits.strip("0") else 0
    return Decimal((sign, tuple(int(char) for char in digits), -scale))


def _unscaled(spec: _Spec, value: Any) -> int:
    """The integer the field stores (``value * 10**scale``), checked against
    the PICTURE: too many integer digits, more decimal places than the scale
    holds (or, for a negative scale, a value that is not a multiple of its
    ``P`` positions), or a sign an unsigned field cannot keep raise
    ``CodecError``. Nothing is truncated or rounded."""
    if isinstance(value, bool) or isinstance(value, float):
        raise CodecError(
            spec.label, value, "numbers are Decimal, int or numeric text, never float or bool"
        )
    if isinstance(value, int):
        number = Decimal(value)
    elif isinstance(value, Decimal):
        number = value
    elif isinstance(value, str):
        if not _NUMERIC_TEXT_RE.fullmatch(value):
            raise CodecError(spec.label, value, "not a decimal number such as -504.77")
        number = Decimal(value)
    else:
        raise CodecError(spec.label, value, f"cannot encode {type(value).__name__} as a number")
    if not number.is_finite():
        raise CodecError(spec.label, value, "not a finite number")
    sign, digit_tuple, exponent = number.as_tuple()
    magnitude = int("".join(str(digit) for digit in digit_tuple) or "0")
    shift = exponent + spec.scale
    if shift >= 0:
        unscaled = magnitude * 10**shift
    else:
        unscaled, remainder = divmod(magnitude, 10**-shift)
        if remainder:
            raise CodecError(
                spec.label, value, f"has more decimal places than the field's scale {spec.scale}"
            )
    if unscaled >= 10 ** spec.digits:
        raise CodecError(
            spec.label, value, f"does not fit {spec.digits} digits at scale {spec.scale}"
        )
    negative = bool(sign) and unscaled != 0
    if negative and not spec.signed:
        raise CodecError(spec.label, value, "an unsigned field cannot hold a negative value")
    return -unscaled if negative else unscaled


def _digit_string(spec: _Spec, unscaled: int) -> str:
    return str(abs(unscaled)).rjust(spec.digits, "0")


def _decode_zoned(spec: _Spec, raw: bytes, codec: str) -> Decimal | Invalid:
    # cp037 maps zones F, C and D onto the same characters the ASCII data
    # uses (x'F5' -> '5', x'C5' -> 'E', x'D5' -> 'N', x'C0' -> '{',
    # x'D0' -> '}'), so one rule decodes both encodings. An unsigned field
    # also accepts the positive overpunch in its last position (IBM preferred
    # sign C); the ASCII files are character translations of the cp037 ones,
    # so the ASCII form is accepted too.
    text = raw.decode(codec)
    body, last = text[:-1], text[-1]
    if any(char not in _DIGITS for char in body):
        return Invalid.of(raw)
    negative = False
    if last in _DIGITS:
        digit = last
    elif last in _POSITIVE_OVERPUNCH:
        digit = str(_POSITIVE_OVERPUNCH.index(last))
    elif last in _NEGATIVE_OVERPUNCH and spec.signed:
        digit, negative = str(_NEGATIVE_OVERPUNCH.index(last)), True
    else:
        return Invalid.of(raw)
    return _number(negative, body + digit, spec.scale)


def _encode_zoned(spec: _Spec, value: Any, codec: str) -> bytes:
    unscaled = _unscaled(spec, value)
    digits = _digit_string(spec, unscaled)
    if spec.signed:
        last = int(digits[-1])
        overpunch = _NEGATIVE_OVERPUNCH if unscaled < 0 else _POSITIVE_OVERPUNCH
        digits = digits[:-1] + overpunch[last]
    return digits.encode(codec)


def _decode_packed(spec: _Spec, raw: bytes) -> Decimal | Invalid:
    nibbles = []
    for byte in raw:
        nibbles.extend((byte >> 4, byte & 0x0F))
    *digit_nibbles, sign = nibbles
    if any(nibble > 9 for nibble in digit_nibbles):
        return Invalid.of(raw)
    if sign in _POSITIVE_NIBBLES:
        negative = False
    elif sign in _NEGATIVE_NIBBLES:
        negative = True
    else:
        return Invalid.of(raw)
    # An even digit count leaves a pad nibble ahead of the digits; a value in
    # it is more digits than the PICTURE declares, which no MOVE produces.
    pad = len(digit_nibbles) - spec.digits
    if any(digit_nibbles[:pad]):
        return Invalid.of(raw)
    digits = "".join(str(nibble) for nibble in digit_nibbles[pad:])
    return _number(negative, digits, spec.scale)


def _encode_packed(spec: _Spec, value: Any) -> bytes:
    unscaled = _unscaled(spec, value)
    digits = _digit_string(spec, unscaled).rjust(spec.length * 2 - 1, "0")
    sign = 0xD if unscaled < 0 else 0xC if spec.signed else 0xF
    nibbles = [int(char) for char in digits] + [sign]
    return bytes((high << 4) | low for high, low in zip(nibbles[0::2], nibbles[1::2]))


def _decode_binary(spec: _Spec, raw: bytes) -> Decimal:
    # Every bit pattern is a value; a value beyond the PICTURE's digits is
    # decoded as stored (binary truncation is a property of the program).
    integer = int.from_bytes(raw, "big", signed=spec.signed)
    return _number(integer < 0, str(abs(integer)), spec.scale)


def _encode_binary(spec: _Spec, value: Any) -> bytes:
    return _unscaled(spec, value).to_bytes(spec.length, "big", signed=spec.signed)


def decode(
    raw: bytes, field: Mapping[str, Any], encoding: str = "ascii"
) -> str | Decimal | Invalid:
    """Decode the bytes of one field.

    ``field`` is a layout field dict; ``length`` may be omitted for a numeric
    field (it follows from ``type`` and ``digits``) and for a text field (the
    whole of ``raw`` is the text). Raises ``CodecError`` only when ``raw`` is
    not bytes of the field's length or the descriptor itself is unusable.
    """
    spec = _spec(field)
    return _decode(spec, _check_length(spec, raw), _codec(encoding))


def _decode(spec: _Spec, raw: bytes, codec: str) -> str | Decimal | Invalid:
    if spec.type in TEXT_TYPES:
        return raw.decode(codec)
    if spec.type in ("zoned", "zoned-signed"):
        return _decode_zoned(spec, raw, codec)
    if spec.type == "comp-3":
        return _decode_packed(spec, raw)
    return _decode_binary(spec, raw)


def encode(value: Any, field: Mapping[str, Any], encoding: str = "ascii") -> bytes:
    """Encode one value into exactly the field's bytes.

    Text fields take a ``str``, left-justified and space-padded. Numeric
    fields take ``Decimal``, ``int`` or numeric text (``"-504.77"``); signed
    zoned values get a trailing overpunch, packed values sign ``C``/``D`` (or
    ``F`` when unsigned), binary values big-endian two's complement. A value
    that does not fit the PICTURE raises ``CodecError``; nothing is
    truncated or rounded. An ``Invalid`` writes its bytes back unchanged.
    """
    return _encode(_spec(field), value, _codec(encoding))


def _encode(spec: _Spec, value: Any, codec: str) -> bytes:
    if isinstance(value, Invalid):
        return _check_length(spec, value.data)
    if spec.type in TEXT_TYPES:
        return _encode_text(spec, value, codec)
    if spec.type in ("zoned", "zoned-signed"):
        return _encode_zoned(spec, value, codec)
    if spec.type == "comp-3":
        return _encode_packed(spec, value)
    return _encode_binary(spec, value)


# --------------------------------------------------------------------------
# Records
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Slot:
    """One field occurrence of a record: where it lies and how it is keyed."""

    key: str  # the output key: field_key() plus subscripts
    aliases: tuple[str, ...]  # every accepted input key, the output key included
    path: str
    indices: tuple[int, ...]  # 1-based subscripts, outermost OCCURS first
    offset: int
    spec: _Spec
    leaf: bool
    redefining: bool  # the field or one of its groups REDEFINES another item

    def contains(self, other: "_Slot") -> bool:
        return other.path.startswith(self.path + ".") and (
            other.indices[: len(self.indices)] == self.indices
        )


@dataclass(frozen=True, eq=False)
class _Plan:
    label: str
    length: int
    slots: tuple[_Slot, ...]  # declaration order, each OCCURS expanded in place
    by_key: dict[str, _Slot]
    shapes: tuple[str, ...]  # one entry per field, for error messages
    defaults: dict[str, bytes]  # encoding -> default record image, filled lazily


def resolve_layout(layout: str | Mapping[str, Any]) -> Mapping[str, Any]:
    """A record layout dict from a layout name (anything
    ``harness.layouts.record`` accepts) or from a layout dict, which must hold
    ``length`` and ``fields``."""
    if isinstance(layout, str):
        return _layouts.record(layout)
    if not isinstance(layout, Mapping):
        raise CodecError(None, layout, "a layout is a layout name or a record layout dict")
    fields = layout.get("fields")
    length = layout.get("length")
    if not isinstance(fields, list) or not fields:
        raise CodecError(
            None, layout.get("record"), "a record layout needs a non-empty 'fields' list"
        )
    if isinstance(length, bool) or not isinstance(length, int) or length <= 0:
        raise CodecError(None, layout.get("record"), "a record layout needs a positive 'length'")
    return layout


def _name_counts(fields: list[Mapping[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for entry in fields:
        counts[entry["name"]] = counts.get(entry["name"], 0) + 1
    return counts


def _key_for(entry: Mapping[str, Any], counts: Mapping[str, int]) -> str:
    name = entry["name"]
    return name if name != "FILLER" and counts.get(name) == 1 else entry["path"]


def field_key(field: Mapping[str, Any], layout: str | Mapping[str, Any]) -> str:
    """The key under which ``decode_record`` reports ``field``: its ``name``
    when no other field of the record has that name and it is not a FILLER,
    otherwise its ``path`` (``ACCT-CURR-BAL``, but
    ``ACCOUNT-RECORD.FILLER#1``). An occurrence of an ``OCCURS`` item, or of
    an item inside one, adds its 1-based subscripts in COBOL form:
    ``CDEMO-ADMIN-OPT-NUM(3)``, or ``NAME(2,1)`` for nested tables.
    ``encode_record`` also accepts the ``path`` of any field. The same rule
    picks the names ``harness.layouts.field_map`` accepts."""
    return _key_for(field, _name_counts(resolve_layout(layout)["fields"]))


def _subscripted(key: str, indices: tuple[int, ...]) -> str:
    return f"{key}({','.join(str(index) for index in indices)})" if indices else key


def _layout_label(record: Mapping[str, Any]) -> str:
    """The layouts.yaml key of a record dict (``CVACT01Y/ACCOUNT-RECORD``,
    ``TEXT/80``), for messages about a layout passed as a dict."""
    length = record["length"]
    if record.get("kind") == "text":
        return f"TEXT/{length}"
    source, name = record.get("copybook"), record.get("record")
    if source and name:
        return f"{Path(str(source)).stem.upper()}/{name}"
    return str(name or f"record of {length} bytes")


def _build_plan(layout: str | Mapping[str, Any]) -> _Plan:
    record = resolve_layout(layout)
    label = layout if isinstance(layout, str) else _layout_label(record)
    length = record["length"]
    fields = record["fields"]
    counts = _name_counts(fields)
    children: dict[str, list[Mapping[str, Any]]] = {}
    roots: list[Mapping[str, Any]] = []
    dimensions: dict[str, tuple[int, ...]] = {}
    shapes: list[str] = []
    for entry in fields:
        path = entry["path"]
        parent = path.rpartition(".")[0]
        if parent and parent not in dimensions:
            raise CodecError(
                path, None, f"in layout {label} follows no entry for its group {parent}"
            )
        (children.setdefault(parent, []) if parent else roots).append(entry)
        occurs = entry.get("occurs") or 0
        dimensions[path] = dimensions.get(parent, ()) + ((occurs,) if occurs else ())
        ranges = ",".join(f"1..{count}" for count in dimensions[path])
        key = _key_for(entry, counts)
        shapes.append(f"{key}({ranges})" if ranges else key)
    slots: list[_Slot] = []

    def visit(
        entry: Mapping[str, Any], delta: int, indices: tuple[int, ...], redefining: bool
    ) -> None:
        spec = _spec(entry)
        if spec.length is None:
            raise CodecError(spec.label, entry, f"field of layout {label} has no length")
        key = _key_for(entry, counts)
        redefining = redefining or bool(entry.get("redefines"))
        occurs = entry.get("occurs") or 0
        leaf = spec.type != "group" and entry["path"] not in children
        for occurrence in range(1, occurs + 1) if occurs else (0,):
            sub_indices = indices + (occurrence,) if occurrence else indices
            sub_delta = delta + (occurrence - 1) * spec.length if occurrence else delta
            offset = entry["offset"] + sub_delta
            if offset < 0 or offset + spec.length > length:
                raise CodecError(spec.label, offset, f"lies outside the {length} bytes of {label}")
            aliases = {_subscripted(key, sub_indices), _subscripted(entry["path"], sub_indices)}
            slots.append(
                _Slot(
                    key=_subscripted(key, sub_indices),
                    aliases=tuple(sorted(aliases)),
                    path=entry["path"],
                    indices=sub_indices,
                    offset=offset,
                    spec=spec,
                    leaf=leaf,
                    redefining=redefining,
                )
            )
            for child in children.get(entry["path"], ()):
                visit(child, sub_delta, sub_indices, redefining)

    for root in roots:
        visit(root, 0, (), False)
    by_key: dict[str, _Slot] = {}
    for slot in slots:
        for alias in slot.aliases:
            if by_key.setdefault(alias, slot) is not slot:
                raise CodecError(alias, None, f"names two fields of {label}")
    return _Plan(label, length, tuple(slots), by_key, tuple(shapes), {})


@functools.lru_cache(maxsize=None)
def _named_plan(name: str) -> _Plan:
    return _build_plan(name)


def _plan(layout: str | Mapping[str, Any]) -> _Plan:
    # Plans of named layouts are cached: layouts.load() parses the committed
    # file once per process and its records are never modified.
    return _named_plan(layout) if isinstance(layout, str) else _build_plan(layout)


def _default_image(plan: _Plan, encoding: str, codec: str) -> bytes:
    """Spaces, with every numeric field zero in its own representation (a
    pointer is NULL). Fields under a REDEFINES are another view of bytes an
    earlier field already describes, so they set defaults only for bytes no
    such field covers (a redefinition longer than its target)."""
    cached = plan.defaults.get(encoding)
    if cached is not None:
        return cached
    image = bytearray(" ".encode(codec) * plan.length)
    covered = bytearray(plan.length)
    for redefining in (False, True):
        for slot in plan.slots:
            if not slot.leaf or slot.redefining is not redefining:
                continue
            start, end = slot.offset, slot.offset + slot.spec.length
            if redefining and any(covered[start:end]):
                continue
            if slot.spec.type == "pointer":
                image[start:end] = bytes(slot.spec.length)
            elif slot.spec.type in NUMERIC_TYPES:
                image[start:end] = _encode(slot.spec, 0, codec)
            covered[start:end] = b"\x01" * (end - start)
    plan.defaults[encoding] = bytes(image)
    return plan.defaults[encoding]


def split_records(data: bytes, record_length: int) -> list[bytes]:
    """Fixed-length records of ``data``; a partial last record raises
    ``CodecError``."""
    if isinstance(record_length, bool) or not isinstance(record_length, int) or record_length <= 0:
        raise CodecError(None, record_length, "record length must be a positive integer")
    if not isinstance(data, (bytes, bytearray, memoryview)):
        raise CodecError(None, type(data).__name__, "records are split from bytes")
    data = bytes(data)
    if len(data) % record_length:
        raise CodecError(
            None,
            len(data),
            f"{len(data)} bytes are not a whole number of {record_length}-byte records",
        )
    return [data[start : start + record_length] for start in range(0, len(data), record_length)]


def decode_record(
    raw: bytes,
    layout: str | Mapping[str, Any],
    encoding: str = "ascii",
    leaves_only: bool = True,
) -> dict[str, str | Decimal | Invalid]:
    """Decode one record into ``{field key: value}`` in declaration order.

    Every elementary field is decoded, FILLERs included, keyed as
    ``field_key`` describes; each occurrence of an ``OCCURS`` item is its own
    entry, and both views of a REDEFINES are decoded. ``leaves_only=False``
    adds group items, decoded as text. ``raw`` must be exactly one record.
    """
    codec = _codec(encoding)
    plan = _plan(layout)
    if not isinstance(raw, (bytes, bytearray, memoryview)):
        raise CodecError(plan.label, type(raw).__name__, "a record is decoded from bytes")
    raw = bytes(raw)
    if len(raw) != plan.length:
        raise CodecError(plan.label, len(raw), f"a record of {plan.label} is {plan.length} bytes")
    return {
        slot.key: _decode(slot.spec, raw[slot.offset : slot.offset + slot.spec.length], codec)
        for slot in plan.slots
        if slot.leaf or not leaves_only
    }


def encode_record(
    values: Mapping[str, Any] | None,
    layout: str | Mapping[str, Any],
    encoding: str = "ascii",
    base: bytes | None = None,
) -> bytes:
    """Encode decoded field values into one record image.

    The image starts as ``base`` (an existing record, exactly one record long)
    or, without it, as the default record: spaces, numeric fields zero.
    ``values[RAW_KEY]`` (``"@raw"``), a string, then replaces the whole image,
    space-padded to the record length. Every other key is a field key as
    ``decode_record`` reports it, or a field path, with subscripts for table
    occurrences; each value overlays its field's bytes, groups before the
    fields inside them, so a ``decode_record`` result encodes back to the
    record it came from. Unknown keys, two keys for one field, and values of
    two REDEFINES views that disagree on shared bytes raise ``CodecError``.
    """
    codec = _codec(encoding)
    plan = _plan(layout)
    if values is None:
        values = {}
    if not isinstance(values, Mapping):
        raise CodecError(plan.label, values, "record values must be a mapping of field keys")
    if base is None:
        image = bytearray(_default_image(plan, encoding, codec))
    elif isinstance(base, (bytes, bytearray, memoryview)) and len(base) == plan.length:
        image = bytearray(base)
    else:
        raise CodecError(plan.label, base, f"a base record of {plan.label} is {plan.length} bytes")
    if RAW_KEY in values:
        text = values[RAW_KEY]
        if not isinstance(text, str) or len(text) > plan.length:
            raise CodecError(
                RAW_KEY, text, f"must be a string of at most {plan.length} characters"
            )
        try:
            image = bytearray(text.ljust(plan.length).encode(codec))
        except UnicodeEncodeError:
            raise CodecError(RAW_KEY, text, f"holds a character {codec} cannot encode") from None
    unknown = sorted(str(key) for key in values if key != RAW_KEY and key not in plan.by_key)
    if unknown:
        raise CodecError(
            plan.label,
            unknown,
            f"unknown field key(s) {', '.join(unknown)}; valid keys: {', '.join(plan.shapes)}",
        )
    chosen: dict[int, tuple[_Slot, Any, str]] = {}
    order = {id(slot): position for position, slot in enumerate(plan.slots)}
    for key, value in values.items():
        if key == RAW_KEY:
            continue
        slot = plan.by_key[key]
        position = order[id(slot)]
        if position in chosen:
            raise CodecError(key, value, f"names the same field as {chosen[position][2]}")
        chosen[position] = (slot, value, key)
    encoded: list[tuple[_Slot, bytes, str]] = []
    for position in sorted(chosen):
        slot, value, key = chosen[position]
        try:
            encoded.append((slot, _encode(slot.spec, value, codec), key))
        except CodecError as exc:
            # The descriptor names the field by path; the caller's key also
            # carries the subscripts of a table occurrence.
            raise CodecError(key, value, exc.reason) from None
    # Values for two REDEFINES views of the same bytes must agree there, so a
    # decode_record result re-encodes as it was decoded and a contradiction is
    # refused instead of being settled by key order. A group and the fields
    # inside it may differ: the inner field is applied after the group.
    for index, (first, first_bytes, first_key) in enumerate(encoded):
        for second, second_bytes, second_key in encoded[index + 1 :]:
            start = max(first.offset, second.offset)
            end = min(first.offset + first.spec.length, second.offset + second.spec.length)
            if start >= end or first.contains(second) or second.contains(first):
                continue
            if (
                first_bytes[start - first.offset : end - first.offset]
                != second_bytes[start - second.offset : end - second.offset]
            ):
                raise CodecError(
                    second_key,
                    values[second_key],
                    f"contradicts {first_key}, another view of the same bytes",
                )
    for slot, data, _ in encoded:
        image[slot.offset : slot.offset + slot.spec.length] = data
    return bytes(image)
