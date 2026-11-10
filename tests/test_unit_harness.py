"""Harness self-tests.

These check the harness itself. They are not inventory scenarios and never
verify an inventory item on their own. They read the committed layouts and
write nothing in the repository.

Codec reconstruction over REDEFINES: a record ``decode_record`` decodes must
encode back to the same bytes in ASCII and cp037, a positive overpunch
included, and views that describe different bytes must be refused.
"""

from __future__ import annotations

import sys
from decimal import Decimal
from pathlib import Path

import pytest

_TESTS_ROOT = str(Path(__file__).resolve().parent)
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from harness import codec  # noqa: E402

pytestmark = pytest.mark.unit

ENCODINGS = [pytest.param("ascii", id="ascii"), pytest.param("cp037", id="cp037")]

# CC-ACCT-ID PIC X(11) and CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11), at
# byte offset 177 of CC-WORK-AREAS [app/cpy/CVCRD01Y.cpy:34-36].
CARD_WORK_AREAS = "CVCRD01Y/CC-WORK-AREAS"
ACCT_ID_OFFSET = 177
ACCT_ID_LENGTH = 11

# WS-EDIT-DATE-CCYYMMDD and its numeric and part views, at byte offset 0.
DATE_EDIT = "CSUTLDWY/CSUTLDWY"

# The last byte of the overpunch text '0000000000A' (+1) in each encoding.
OVERPUNCH_ONE = [
    pytest.param("ascii", 0x41, id="ascii"),
    pytest.param("cp037", 0xC1, id="cp037"),
]

KEY_ORDERS = [
    pytest.param(False, id="text-first"),
    pytest.param(True, id="numeric-first"),
]


def _record_with(layout: str, encoding: str, offset: int, text: str) -> bytes:
    """The default record of ``layout`` with ``text``, in ``encoding``, at
    ``offset``."""
    default = codec.encode_record({}, layout, encoding)
    data = text.encode(codec.ENCODINGS[encoding])
    return default[:offset] + data + default[offset + len(data) :]


def _numeric_view(numeric: int | Decimal | str, encoding: str) -> int | Decimal | codec.Invalid:
    """The CC-ACCT-ID-N value of a case: a number as given, or, for a ``str``,
    the ``Invalid`` holding that text's bytes in ``encoding``."""
    if isinstance(numeric, str):
        return codec.Invalid.of(numeric.encode(codec.ENCODINGS[encoding]))
    return numeric


def _account_views(text: str, numeric: object, numeric_first: bool) -> dict[str, object]:
    """CC-ACCT-ID and CC-ACCT-ID-N values, in the given key order."""
    views = [("CC-ACCT-ID", text), ("CC-ACCT-ID-N", numeric)]
    return dict(reversed(views) if numeric_first else views)


@pytest.mark.parametrize(("encoding", "last_byte"), OVERPUNCH_ONE)
def test_overpunch_redefines_decodes_both_views_and_round_trips(
    encoding: str, last_byte: int
) -> None:
    raw = _record_with(CARD_WORK_AREAS, encoding, ACCT_ID_OFFSET, "0000000000A")
    assert raw[ACCT_ID_OFFSET + ACCT_ID_LENGTH - 1] == last_byte
    decoded = codec.decode_record(raw, CARD_WORK_AREAS, encoding)
    assert decoded["CC-ACCT-ID"] == "0000000000A"
    assert isinstance(decoded["CC-ACCT-ID-N"], Decimal)
    assert decoded["CC-ACCT-ID-N"] == Decimal("1")
    assert codec.encode_record(decoded, CARD_WORK_AREAS, encoding) == raw


@pytest.mark.parametrize("numeric_first", KEY_ORDERS)
@pytest.mark.parametrize(
    ("text", "numeric"),
    [
        pytest.param("0000000000A", Decimal("1"), id="overpunch-text-and-its-value"),
        pytest.param("00000000001", "00000000001", id="text-and-invalid-of-its-bytes"),
    ],
)
@pytest.mark.parametrize("encoding", ENCODINGS)
def test_agreeing_redefines_views_keep_the_text_in_either_key_order(
    encoding: str, text: str, numeric: int | Decimal | str, numeric_first: bool
) -> None:
    values = _account_views(text, _numeric_view(numeric, encoding), numeric_first)
    expected = _record_with(CARD_WORK_AREAS, encoding, ACCT_ID_OFFSET, text)
    assert codec.encode_record(values, CARD_WORK_AREAS, encoding) == expected


@pytest.mark.parametrize("leaves_only", [True, False], ids=["leaves-only", "with-groups"])
@pytest.mark.parametrize("encoding", ENCODINGS)
def test_overpunched_date_views_round_trip(encoding: str, leaves_only: bool) -> None:
    raw = _record_with(DATE_EDIT, encoding, 0, "2022071A")
    decoded = codec.decode_record(raw, DATE_EDIT, encoding, leaves_only=leaves_only)
    assert decoded["WS-EDIT-DATE-DD"] == "1A"
    assert decoded["WS-EDIT-DATE-DD-N"] == Decimal("11")
    assert decoded["WS-EDIT-DATE-CCYYMMDD-N"] == Decimal("20220711")
    assert codec.encode_record(decoded, DATE_EDIT, encoding) == raw


@pytest.mark.parametrize("numeric_first", KEY_ORDERS)
@pytest.mark.parametrize(
    ("text", "numeric"),
    [
        pytest.param("0000000000A", 2, id="overpunch-one-and-two"),
        pytest.param("ABCDEFGHIJK", 5, id="letters-and-five"),
        pytest.param("0000000000A", "00000000001", id="overpunch-and-invalid-of-other-bytes"),
    ],
)
@pytest.mark.parametrize("encoding", ENCODINGS)
def test_contradicting_redefines_views_are_refused(
    encoding: str, text: str, numeric: int | str, numeric_first: bool
) -> None:
    values = _account_views(text, _numeric_view(numeric, encoding), numeric_first)
    with pytest.raises(codec.CodecError) as caught:
        codec.encode_record(values, CARD_WORK_AREAS, encoding)
    assert "contradicts" in caught.value.reason
