"""Harness self-tests.

These check the instruments every business assertion depends on: the
layouts against the copybooks and LISTCAT, the codecs, the EBCDIC fixture
cross-check and the approved fixture sources, the frozen inventory, the
scenario, baseline and result files against the closed id manifest and the
scenario schema, the gap register and the seam rule, the configuration
(markers, ``commands.yaml``, the adapter contract, the documented launch and
the CI workflow), the baseline version-bump check and the protected-tree
check, with the harness and adapter code that runs scenarios and records,
compares and reports their outcomes, including the work-root admission.

They are not inventory scenarios and never verify an inventory item on their
own.

They read the committed tree and write nothing in the repository. Every case
that writes does so in a folder of its own inside the session work root (the
``carddemo_work_root`` fixture), never under pytest's basetemp or in the
checkout, removed afterwards; where a case needs it,
``tempfile.gettempdir()`` is pointed at a folder of its own inside that root.
The command-line checks run with the session work root, or a folder of the
case's own inside it, as ``CARDDEMO_TEST_WORKDIR``.
"""

from __future__ import annotations

import configparser
import copy
import csv
import dataclasses
import datetime
import errno
import functools
import inspect
import io
import json
import os
import re
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
import zlib
from collections import Counter
from collections.abc import Callable, Iterator, Mapping
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

_TESTS_ROOT = str(Path(__file__).resolve().parent)
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

import harness  # noqa: E402
from adapters import base  # noqa: E402
from adapters.gnucobol_batch import build, datasets, jobs, utilities  # noqa: E402
from adapters.gnucobol_batch.adapter import GnuCOBOLBatchAdapter  # noqa: E402
from adapters.unikix import adapter as unikix_adapter  # noqa: E402
from adapters.unikix import bpe, deploy, evidence, tpe  # noqa: E402
from adapters.unikix import env as unikix_env  # noqa: E402
from adapters.unikix.adapter import UniKixAdapter  # noqa: E402
from harness import (  # noqa: E402
    ADAPTERS_DIR,
    BASELINES_DIR,
    FIXTURES_FILE,
    INVENTORY_DIR,
    PROTECTED_BASE,
    RESULTS_DIR,
    SCENARIOS_DIR,
    TESTS_ROOT,
    UNIKIX_EVIDENCE_DIR,
    WORK_ROOT_LOCK_NAME,
    HarnessError,
    WorkRootBusy,
    baselines,
    capture,
    codec,
    dump_yaml,
    fixtures,
    inventory_tools,
    layouts,
    load_yaml,
    lock_work_root,
    report,
    results,
    runner,
    scenarios,
    sha256_file,
    usable_work_root,
)
from harness import capture as capture_cli  # noqa: E402

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

# Two run clocks as the online programs show them: CSDAT01Y's MM/DD/YY and
# HH:MM:SS [app/cpy/CSDAT01Y.cpy:30-41].
SCREEN_CLOCKS = (("07/06/22", "10:00:00"), ("11/10/26", "16:40:52"))

# The manifest of a derived baseline built in memory; its source_commit is
# the suite's starting commit.
DERIVED_MANIFEST = {
    "baseline_version": "1.0.0",
    "source_commit": "93ebec71ff7b59a68cedd7c501afe496a8eaa68d",
    "capture_platform": "derived",
    "capture_date": "2026-01-01",
    "source_identity": {"tree": "app"},
}

# Screen expectations that leave the screen clock unasserted: the first two
# screens of CRD-COCRDLIC-alternate-03 name only TRNNAME and PGMNAME, and
# CRD-COCRDLIC-recovery-02 ends on an unformatted (TEXT) screen.
PARTIAL_SCREENS = [
    pytest.param("CRD-COCRDLIC-alternate-03", id="identification-only-screens"),
    pytest.param("CRD-COCRDLIC-recovery-02", id="unformatted-last-screen"),
]

# A derived baseline holding the scenario's expectation, or none.
PROJECTION_BASELINES = [
    pytest.param(True, id="derived-baseline"),
    pytest.param(False, id="no-baseline"),
]

# Every step captured, or the last one without a screen ({"@missing": true}).
DELIVERED_STEPS = [
    pytest.param(0, id="every-step"),
    pytest.param(1, id="last-step-missing"),
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


def _captured_screen(step: Mapping[str, object], date: str, time: str) -> dict[str, str]:
    """The screen an adapter delivers for an expected ``step``: an
    unformatted screen as its text, else every named field of the map at its
    BMS length (spaces), the step's literal values, and CURDATE and CURTIME
    from the run clock at their field lengths."""
    mapset = step["@map"]
    if mapset == "TEXT":
        return {"@map": "TEXT", "@text": step.get("@text", base.screen_text([]))}
    screen = {"@map": mapset}
    for entry in layouts.screen(mapset)["fields"]:
        if entry.get("name"):
            screen[entry["name"]] = " " * entry["length"]
    screen.update((name, value) for name, value in step.items() if isinstance(value, str))
    for name, value in (("CURDATE", date), ("CURTIME", time)):
        screen[name] = value.ljust(len(screen[name]))
    return screen


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


@pytest.mark.parametrize("dropped", DELIVERED_STEPS)
@pytest.mark.parametrize("with_baseline", PROJECTION_BASELINES)
@pytest.mark.parametrize("scenario_id", PARTIAL_SCREENS)
def test_projection_shows_screen_clock_kinds_on_every_captured_screen(
    scenario_id: str, with_baseline: bool, dropped: int
) -> None:
    scenario = scenarios.get(scenario_id)
    baseline = (
        baselines.Baseline(scenario_id, dict(scenario.expected), DERIVED_MANIFEST)
        if with_baseline
        else None
    )
    steps = scenario.expected["screens"]
    texts = []
    for date, time in SCREEN_CLOCKS:
        screens = [_captured_screen(step, date, time) for step in steps[: len(steps) - dropped]]
        result = base.Result(
            return_code=0,
            screens=screens,
            messages=[screen["ERRMSG"] for screen in screens if "ERRMSG" in screen],
        )
        delivered = copy.deepcopy(result.screens)
        decoded = baselines.decode_result(scenario, result)
        before = copy.deepcopy(decoded)
        projected = baselines.projection(decoded, scenario, baseline)
        assert decoded == before
        assert result.screens == delivered
        assert len(projected["screens"]) == len(steps)
        for index, (shown, captured) in enumerate(zip(projected["screens"], decoded["screens"])):
            if index >= len(steps) - dropped:
                assert captured == {"@missing": True}
            if "@missing" in captured or captured["@map"] == "TEXT":
                assert shown == captured
                continue
            assert set(shown) == set(captured)
            for name, value in captured.items():
                assert shown[name] == baselines.SCREEN_CLOCK_FIELDS.get(name, value), (index, name)
        texts.append(dump_yaml(projected))
    assert texts[0] == texts[1]


def test_every_screen_layout_defines_the_screen_clock_fields() -> None:
    assert set(baselines.SCREEN_CLOCK_FIELDS.values()) <= set(baselines.MATCHERS)
    lacking = {}
    for mapset, layout in layouts.load()["screens"].items():
        names = {entry.get("name") for entry in layout["fields"]}
        missing = sorted(set(baselines.SCREEN_CLOCK_FIELDS) - names)
        if missing:
            lacking[mapset] = missing
    assert lacking == {}


# Matcher binding, on committed bytes under the default clock: TRANSACT as
# TRN-POSTTRAN-normal-01 posted it (262 records, whose first and last its two
# contains items name) and SYSTRAN as FIN-INTCALC-normal-01 wrote it (50
# records, each a records item). A spelling names each clock-derived
# timestamp: "{}" by its decoded name, "TRAN-RECORD.{}" by its field path in
# CVTRA05Y, and a spelling without "{}" names that field whatever the
# timestamp.
RUN_TIMESTAMP = {"match": "run-timestamp"}
DECODED_NAME = "{}"
FIELD_PATH = "TRAN-RECORD.{}"
MATCHER_PLACEMENTS = [pytest.param(placement, id=placement) for placement in ("records", "contains", "entry", "top")]
MATCHER_REFUSALS = [
    pytest.param("records", "TRAN-RECORD", "records[1] names TRAN-RECORD, a group", id="group-in-records"),
    pytest.param("contains", "TRAN-RECORD", "contains[1] matches no observed record", id="group-in-contains"),
    pytest.param("entry", "TRAN-RECORD", "TRANSACT.matchers names TRAN-RECORD, a group", id="group-in-entry"),
    pytest.param("top", "TRAN-RECORD", "expected.matchers names TRAN-RECORD, a group", id="group-in-top"),
    pytest.param("entry", "@raw", "names @raw, the whole raw record", id="raw-record-in-entry"),
    pytest.param("records", "TRAN-RECORD.TRAN-TS", "TRAN-RECORD.TRAN-TS name no decoded field", id="no-such-path"),
    pytest.param("whole-line", DECODED_NAME, "a whole-line matcher for a field-map record", id="whole-line"),
]


def _timestamp_scenario(placement: str, spelling: str) -> tuple[scenarios.Scenario, base.Result, str]:
    """An in-memory scenario asserting one dataset whose timestamps take
    ``run-timestamp`` matchers named by ``spelling``: in its ``records``
    (SYSTRAN; ``whole-line`` also makes the first one a whole-line matcher),
    or in the ``contains`` items, the entry's ``matchers`` or the top-level
    ``matchers`` (TRANSACT); a result holding the dataset's committed bytes;
    and the dataset's name."""
    if placement in ("records", "whole-line"):
        source, name = scenarios.get("FIN-INTCALC-normal-01"), "SYSTRAN"
        records = [
            {(spelling.format(field) if baselines.is_matcher(value) else field): value for field, value in item.items()}
            for item in source.expected["datasets"][name]["records"]
        ]
        if placement == "whole-line":
            records[0] = RUN_TIMESTAMP
        expected: dict = {"datasets": {name: {"records": records}}}
    else:
        source, name = scenarios.get("TRN-POSTTRAN-normal-01"), "TRANSACT"
        committed = source.expected["datasets"][name]
        contains = [
            {field: value for field, value in item.items() if field != "TRAN-PROC-TS"} for item in committed["contains"]
        ]
        named = {spelling.format("TRAN-PROC-TS"): RUN_TIMESTAMP}
        if placement == "contains":
            contains = [{**item, **named} for item in contains]
        expected = {"datasets": {name: {"count": committed["count"], "contains": contains}}}
        if placement == "entry":
            expected["datasets"][name]["matchers"] = named
        elif placement == "top":
            expected["matchers"] = named
    result = base.Result(datasets={name: baselines.load(source.id).datasets[name]})
    return dataclasses.replace(source, expected=copy.deepcopy(expected)), result, name


@pytest.mark.parametrize("placement", MATCHER_PLACEMENTS)
def test_matchers_named_by_field_path_bind_to_their_decoded_fields(placement: str) -> None:
    short, result, name = _timestamp_scenario(placement, DECODED_NAME)
    qualified, _, _ = _timestamp_scenario(placement, FIELD_PATH)
    expected = capture_cli.build_expected(short, result)
    assert capture_cli.build_expected(qualified, result) == expected
    observed = baselines.decode_result(short, result)["datasets"][name]
    absolute = baselines.resolve_matchers(RUN_TIMESTAMP, scenarios.clock(short))
    named = {item["TRAN-ID"] for item in short.expected["datasets"][name].get("contains", [])}
    fields = ("TRAN-ORIG-TS", "TRAN-PROC-TS") if placement == "records" else ("TRAN-PROC-TS",)
    bound = 0
    for record, decoded in zip(expected["datasets"][name], observed, strict=True):
        if placement == "contains" and decoded["TRAN-ID"] not in named:
            assert record == decoded
            continue
        bound += 1
        assert all(decoded[field] == absolute for field in fields)
        assert record == {**decoded, **dict.fromkeys(fields, RUN_TIMESTAMP)}
        assert absolute not in record.values()
    assert bound == (len(named) if placement == "contains" else len(observed))
    manifest = dict(baselines.load(qualified.id).manifest)
    baseline = baselines.Baseline(qualified.id, expected, manifest, dict(result.datasets))
    run_date = datetime.date.fromisoformat(scenarios.clock(qualified)[:10])
    assert baselines.compare(qualified, baseline, result, capture_cli.ADAPTER, run_date) == ("passed", None, "")


@pytest.mark.parametrize(("placement", "spelling", "refusal"), MATCHER_REFUSALS)
def test_matchers_no_decoded_field_can_hold_are_refused(placement: str, spelling: str, refusal: str) -> None:
    scenario, result, _ = _timestamp_scenario(placement, spelling)
    with pytest.raises(capture_cli.CaptureError, match=re.escape(refusal)):
        capture_cli.build_expected(scenario, result)


# The steps of ACT-COACTUPC-boundary-24 driven by tpe.drive on a fake region:
# each AID is handled DAY_GUARD_AID_SECONDS after the last, so from
# DAY_GUARD_MIDNIGHT the fifth AID, the ENTER of step 4, is the first after
# midnight, and from DAY_GUARD_MORNING every AID falls on 2025-12-31. tpe reads
# a two-digit screen year in the century nearest the host's year, so both days
# read back as written on any host before 2075.
DAY_GUARD_SCENARIO = "ACT-COACTUPC-boundary-24"
DAY_GUARD_AID_SECONDS = 15
DAY_GUARD_MIDNIGHT = datetime.datetime(2025, 12, 31, 23, 59)
DAY_GUARD_MORNING = datetime.datetime(2025, 12, 31, 10, 0)
DAY_GUARD_ADDRESS = "127.0.0.1:2001"
# The work-root variable conftest reads once per session; the cases never read
# it, the rootless-cleanup case removes it, and _cli sets it, for the command
# lines it runs, to the work root it is given.
WORK_ROOT_ENV = "CARDDEMO_TEST_WORKDIR"
# drive's working directory lies in the session work root, where every run
# writes (carddemo_work_root: that variable's value, or the root conftest made
# with tempfile.mkdtemp(prefix="carddemo-")).
DAY_GUARD_WORKDIR_PREFIX = "unit-tpe-day-guard-"
# The day change the midnight flow shows: its first attempt types 2025-12-31,
# and the screen of step 4 shows 01/01/26.
DAY_GUARD_CHANGE = {
    "attempt": 1,
    "first_step_screen_date": "12/31/25",
    "step": 3,
    "screen_date": "01/01/26",
}
# EDIT-DATE-OF-BIRTH's future-date refusal, and the prompt of a change that
# passed every edit [app/cpy/CSUTLDPY.cpy:341-365] [app/cbl/COACTUPC.cbl:473].
DOB_REFUSAL = "Date of Birth:cannot be in the future "
CHANGES_VALIDATED = "Changes validated.Press F5 to save"

# Each case: the region's start, whether drive gets a restart, whether the
# deadline has passed once the region's day changed, the restarts expected,
# the exception expected (None when drive returns), and the run date of the
# last attempt.
DAY_GUARD_CASES = [
    pytest.param(
        DAY_GUARD_MIDNIGHT,
        True,
        False,
        1,
        None,
        datetime.date(2026, 1, 1),
        id="midnight-driven-again",
    ),
    pytest.param(
        DAY_GUARD_MIDNIGHT,
        False,
        False,
        0,
        base.AdapterUnavailable,
        datetime.date(2025, 12, 31),
        id="midnight-without-restart",
    ),
    pytest.param(
        DAY_GUARD_MIDNIGHT,
        True,
        True,
        0,
        base.ScenarioTimeout,
        datetime.date(2025, 12, 31),
        id="midnight-after-deadline",
    ),
    pytest.param(
        DAY_GUARD_MORNING, True, False, 0, None, datetime.date(2025, 12, 31), id="same-day"
    ),
    pytest.param(
        DAY_GUARD_MORNING,
        False,
        False,
        0,
        None,
        datetime.date(2025, 12, 31),
        id="same-day-without-restart",
    ),
]


def _fake_rows(mapset: str, values: Mapping[str, str]) -> tuple[str, ...]:
    """The 24 rows of 80 characters of ``mapset`` with its static texts and
    ``values`` in its named fields, each from the column after its BMS POS."""
    layout = layouts.screen(mapset)
    named = {entry["name"]: entry for entry in layout["fields"] if entry.get("name")}
    texts = [(item["row"], item["col"], item["text"]) for item in layout["statics"]]
    for name, value in values.items():
        assert len(value) <= named[name]["length"], (mapset, name, value)
        texts.append((named[name]["row"], named[name]["col"], value.ljust(named[name]["length"])))
    buffer = [" "] * (base.SCREEN_ROWS * base.SCREEN_COLS)
    for row, col, text in texts:
        start = (row - 1) * base.SCREEN_COLS + col
        for offset, char in enumerate(text):
            buffer[(start + offset) % len(buffer)] = char
    screen = "".join(buffer)
    return tuple(
        screen[row * base.SCREEN_COLS : (row + 1) * base.SCREEN_COLS]
        for row in range(base.SCREEN_ROWS)
    )


class _FakeRegion:
    """A region clock and the path of the boundary-24 steps through COSGN00C,
    COMEN01C and COACTUPC. Each AID is handled at the clock's time, which then
    moves on DAY_GUARD_AID_SECONDS; the birth date is judged against that
    time's date, the date the screen then shows."""

    def __init__(self, start: datetime.datetime) -> None:
        self.now = start
        self.terminals: list[_FakeTerminal] = []

    def _tick(self) -> datetime.datetime:
        at = self.now
        self.now += datetime.timedelta(seconds=DAY_GUARD_AID_SECONDS)
        return at

    @staticmethod
    def _header(at: datetime.datetime, transid: str, program: str) -> dict[str, str]:
        return {
            "TRNNAME": transid,
            "PGMNAME": program,
            "CURDATE": at.strftime("%m/%d/%y"),
            "CURTIME": at.strftime("%H:%M:%S"),
        }

    def sign_on(self) -> tuple[str, dict[str, str]]:
        return "COSGN00", self._header(self._tick(), "CC00", "COSGN00C")

    def enter(self, mapset: str, entered: Mapping[str, str]) -> tuple[str, dict[str, str]]:
        at = self._tick()
        if mapset == "COSGN00" and entered == {
            "USERID": "USER0001",
            "PASSWD": scenarios.SIGN_ON_PASSWORD,
        }:
            menu = {"OPTN002": "02. Account Update"}
            return "COMEN01", {**self._header(at, "CM00", "COMEN01C"), **menu}
        update = self._header(at, "CAUP", "COACTUPC")
        if mapset == "COMEN01" and entered == {"OPTION": "2"}:
            return "COACTUP", {**update, "INFOMSG": "Enter or update id of account to update"}
        update.update(ACCTSID="00000000001", INFOMSG="Update account details presented above.")
        if mapset == "COACTUP" and entered == {"ACCTSID": "00000000001"}:
            return "COACTUP", {**update, "DOBYEAR": "1961", "DOBMON": "06", "DOBDAY": "08"}
        if mapset == "COACTUP" and set(entered) == {"DOBYEAR", "DOBMON", "DOBDAY"}:
            typed = datetime.date(
                int(entered["DOBYEAR"]), int(entered["DOBMON"]), int(entered["DOBDAY"])
            )
            if typed >= at.date():
                return "COACTUP", {**update, **entered, "ERRMSG": DOB_REFUSAL}
            return "COACTUP", {**update, **entered, "INFOMSG": CHANGES_VALIDATED}
        raise AssertionError(f"the fake region has no path for {dict(entered)} on {mapset}")


class _FakeTerminal:
    """The ``tpe.S3270`` methods ``drive`` calls, answered by a
    ``_FakeRegion``; it starts no process and opens no connection."""

    def __init__(
        self,
        region: _FakeRegion,
        workdir: Path,
        time_left: object,
        session: int = 1,
        executable: str | None = None,
    ) -> None:
        assert Path(workdir).is_dir() and session == 1 and executable == "s3270"
        self.region = region
        self.connected = False
        self.closed = False
        self.mapset = ""
        self.values: dict[str, str] = {}
        self.entered: dict[str, str] = {}
        region.terminals.append(self)

    def _show(self, screen: tuple[str, dict[str, str]]) -> None:
        self.mapset, self.values = screen
        self.entered = {}

    def connect(self, address: tpe.TN3270Address) -> None:
        assert address == tpe.parse_address(DAY_GUARD_ADDRESS)
        self.connected = True

    def start_transaction(self, transid: str) -> None:
        assert self.connected and not self.closed and transid == "CC00"
        self._show(self.region.sign_on())

    def read_screen(self) -> tpe.Screen:
        return tpe.Screen(_fake_rows(self.mapset, self.values), True)

    def enter_field(self, field: tpe.MapField, value: str) -> None:
        self.entered[field.name] = value

    def send_aid(self, aid: str) -> None:
        assert aid == "ENTER" and not self.closed
        self._show(self.region.enter(self.mapset, self.entered))

    def wait_unlock(self) -> None:
        assert not self.closed

    def close(self) -> None:
        self.closed = True


@pytest.fixture
def day_guard_workdir(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=DAY_GUARD_WORKDIR_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


@pytest.mark.parametrize(
    ("start", "with_restart", "deadline_at_midnight", "restarts", "raised", "run_date"),
    DAY_GUARD_CASES,
)
def test_tpe_drives_the_steps_again_when_the_region_day_changes(
    monkeypatch: pytest.MonkeyPatch,
    day_guard_workdir: Path,
    start: datetime.datetime,
    with_restart: bool,
    deadline_at_midnight: bool,
    restarts: int,
    raised: type[Exception] | None,
    run_date: datetime.date,
) -> None:
    scenario = scenarios.get(DAY_GUARD_SCENARIO)
    steps = scenario.inputs["steps"]
    region = _FakeRegion(start)
    monkeypatch.setattr(tpe, "find_s3270", lambda: "s3270")
    monkeypatch.setattr(tpe, "S3270", functools.partial(_FakeTerminal, region))
    restarted: list[datetime.datetime] = []

    def restart() -> None:
        # The adapter's reset runs once every session of the attempt is closed.
        assert region.terminals and all(terminal.closed for terminal in region.terminals)
        restarted.append(region.now)

    def time_left() -> float:
        passed = deadline_at_midnight and region.now.date() > start.date()
        return 0.0 if passed else float(base.DEFAULT_DEADLINE_S)

    before: list[int] = []
    capture = SimpleNamespace()
    drive = functools.partial(
        tpe.drive,
        steps,
        deadline=time_left,
        capture=capture,
        before_step=before.append,
        workdir=day_guard_workdir,
        address=DAY_GUARD_ADDRESS,
        expected_screens=scenario.expected["screens"],
        restart=restart if with_restart else None,
    )
    shown = run_date.strftime("%m/%d/%y")
    if raised is None:
        outcome = drive()
        assert len(outcome.screens) == len(steps)
        assert {screen["CURDATE"] for screen in outcome.screens} == {shown}
        last = outcome.screens[-1]
        assert last["ERRMSG"] == scenario.expected["screens"][-1]["ERRMSG"]
        assert (last["DOBYEAR"], last["DOBMON"], last["DOBDAY"]) == (
            f"{run_date:%Y}",
            f"{run_date:%m}",
            f"{run_date:%d}",
        )
        assert outcome.messages == [screen["ERRMSG"] for screen in outcome.screens]
    else:
        with pytest.raises(raised) as caught:
            drive()
        if raised is base.AdapterUnavailable:
            assert caught.value.gap_id == "GAP-MIS-015"
            for detail in ("12/31/25", "01/01/26", "steps[3]"):
                assert detail in caught.value.reason
    attempts = 1 + restarts
    assert len(restarted) == restarts
    assert len(region.terminals) == attempts
    assert all(terminal.closed for terminal in region.terminals)
    assert before == list(range(len(steps))) * attempts
    tags = [(attempt, step) for attempt in range(1, attempts + 1) for step in range(len(steps))]
    assert [(item["attempt"], item["step"]) for item in capture.screens_raw] == tags
    assert [(item["attempt"], item["step"]) for item in capture.observed_messages] == tags
    assert capture.run_date["first_step_screen_date"] == shown
    if start == DAY_GUARD_MIDNIGHT:
        assert capture.run_date["day_changes"] == [DAY_GUARD_CHANGE]
        # Unguarded, the date typed from the 12/31/25 screens passes the edit
        # of 01/01/26: the abandoned attempt ends on the confirmation prompt.
        assert CHANGES_VALIDATED in "".join(capture.screens_raw[len(steps) - 1]["rows"])
    else:
        assert "day_changes" not in capture.run_date


# Each accepted TN3270 address, the host it gives and its Connect argument:
# localhost, in any case, is pinned to 127.0.0.1 and never resolved, and a
# numeric host keeps its canonical form.
LOOPBACK_ADDRESSES = [
    pytest.param("localhost:2001", "127.0.0.1", "127.0.0.1:2001", id="localhost"),
    pytest.param("LOCALHOST:2001", "127.0.0.1", "127.0.0.1:2001", id="localhost-upper-case"),
    pytest.param("127.0.0.1:2001", "127.0.0.1", "127.0.0.1:2001", id="ipv4"),
    pytest.param("127.1.2.3:23", "127.1.2.3", "127.1.2.3:23", id="ipv4-loopback-network"),
    pytest.param("[::1]:4444", "::1", "[::1]:4444", id="ipv6"),
    pytest.param("[0:0:0:0:0:0:0:1]:4444", "::1", "[::1]:4444", id="ipv6-long-form"),
]
# S3270.connect on a terminal that starts no process: each case's address
# built by hand on CONNECT_PORT, and the one action it must send, or None when
# it must be refused under GAP-MIS-005 with nothing sent.
CONNECT_PORT = 2001
CONNECT_CASES = [
    pytest.param("localhost", f"Connect(127.0.0.1:{CONNECT_PORT})", id="localhost-pinned"),
    pytest.param("example.com", None, id="host-name-refused"),
    pytest.param("10.0.0.5", None, id="beyond-loopback-refused"),
]
# An ok reply's 12 status fields: unlocked, formatted, connected to 127.0.0.1,
# model 3279-2's 24 x 80.
CONNECTED_STATUS = ("U", "F", "U", "C(127.0.0.1)", "I", "4", "24", "80", "0", "0", "0x0", "-")


@pytest.mark.parametrize(("value", "host", "connect_arg"), LOOPBACK_ADDRESSES)
def test_tpe_address_is_a_numeric_loopback_address(value: str, host: str, connect_arg: str) -> None:
    address = tpe.parse_address(value)
    assert address.host == host
    assert address.connect_arg == str(address) == connect_arg


@pytest.mark.parametrize(("host", "connect"), CONNECT_CASES)
def test_tpe_connect_sends_only_a_numeric_loopback_address(host: str, connect: str | None) -> None:
    terminal = object.__new__(tpe.S3270)  # no s3270 process is started
    sent: list[str] = []
    waits: list[str] = []

    def command(action: str) -> tpe.Reply:
        sent.append(action)
        return tpe.Reply(action, (), CONNECTED_STATUS, True)

    def wait_unlock() -> tpe.Reply:
        waits.append(host)
        return tpe.Reply("Wait(1,Unlock)", (), CONNECTED_STATUS, True)

    terminal.command = command
    terminal.wait_unlock = wait_unlock
    address = tpe.TN3270Address(host, CONNECT_PORT)
    if connect is None:
        with pytest.raises(base.AdapterUnavailable) as caught:
            terminal.connect(address)
        assert caught.value.gap_id == "GAP-MIS-005"
        assert f"{host}:{CONNECT_PORT}" in caught.value.reason
        assert (sent, waits) == ([], [])
    else:
        terminal.connect(address)
        assert (sent, waits) == ([connect], [host])


# UniKixAdapter.prepare's CSD utility gate, on a session marked started whose
# commands and BPE service are fakes: any use of them past the scenario checks
# (a probe, a submission, a reset) raises _BeyondTheGate, so a case that ends
# on another exception reached nothing of the region. CBADMCDJ STEP1 runs
# DFHCSDUP [app/jcl/CBADMCDJ.jcl:27-30]; CLOSEFIL and OPENFIL run SDSF.
CSD_GATE_SCENARIO = "CFG-CBADMCDJ-normal-01"
CSD_GATE_WORK_ROOT_PREFIX = "unit-unikix-csd-gate-"
CSD_GATE_BROKEN = "the reset before CFG-OPENFIL-normal-01 was not confirmed"
CSD_GATE_REASON = (
    "DFHCSDUP has no confirmed UniKix equivalent",
    "CBADMCDJ step STEP1",
    "catlg/tpe lists installed at region creation",
)


class _BeyondTheGate(Exception):
    """A fake was used past prepare's scenario checks."""


# Each case: inputs.job, whether the session is broken, the exception
# expected, its gap_id (None for an exception without one) and the texts its
# message holds.
CSD_GATE_CASES = [
    pytest.param(
        "CBADMCDJ", False, base.AdapterUnavailable, "GAP-MIS-016", CSD_GATE_REASON, id="job"
    ),
    pytest.param(
        ["CLOSEFIL", "CBADMCDJ", "OPENFIL"],
        False,
        base.AdapterUnavailable,
        "GAP-MIS-016",
        CSD_GATE_REASON,
        id="chain-member",
    ),
    pytest.param("CLOSEFIL", False, _BeyondTheGate, None, (), id="other-job-passes"),
    pytest.param(
        "CBADMCDJ",
        True,
        base.AdapterUnavailable,
        "GAP-MIS-004",
        (CSD_GATE_BROKEN,),
        id="broken-session-first",
    ),
    pytest.param(
        ["CBADMCDJ", "NOSUCHJOB"],
        False,
        HarnessError,
        None,
        ("'NOSUCHJOB' is not a job",),
        id="unknown-job-first",
    ),
]


class _GateFake:
    """Any attribute a subclass does not define raises _BeyondTheGate."""

    def __getattr__(self, name: str) -> object:
        if name.startswith("__"):
            raise AttributeError(name)
        raise _BeyondTheGate(f"{type(self).__name__}.{name}")


class _GateCommands(_GateFake):
    """The ``CommandSet`` prepare, and a refusing ``refuse``, read before the
    gate: its log mark, 0 unless given."""

    def __init__(self, mark: int = 0) -> None:
        self._mark = mark

    def mark(self) -> int:
        return self._mark


class _GateBatch(_GateFake):
    """The ``BatchService`` prepare reads before the gate: the committed
    resources.yaml and the BPE evidence pending, none unless given, which
    ``take_evidence`` hands over once."""

    def __init__(self, pending: Mapping[str, bytes] | None = None) -> None:
        self.resources = bpe.Resources.load()
        self._pending = dict(pending or {})

    def take_evidence(self) -> dict[str, bytes]:
        taken, self._pending = self._pending, {}
        return taken


@pytest.fixture
def csd_gate_work_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh, empty directory of this case's own inside the session work
    root (carddemo_work_root), never under pytest's basetemp, removed
    afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=CSD_GATE_WORK_ROOT_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


@pytest.mark.parametrize(("job", "broken", "raised", "gap_id", "details"), CSD_GATE_CASES)
def test_unikix_prepare_blocks_a_csd_utility_job_before_the_region(
    csd_gate_work_root: Path,
    job: str | list[str],
    broken: bool,
    raised: type[Exception],
    gap_id: str | None,
    details: tuple[str, ...],
) -> None:
    adapter = UniKixAdapter(work_root=csd_gate_work_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter._session_ready = True
    adapter._env = object()
    adapter._commands = _GateCommands()
    adapter._batch = _GateBatch()
    if broken:
        adapter._broken = CSD_GATE_BROKEN
    scenario = SimpleNamespace(
        id=CSD_GATE_SCENARIO, inputs={"job": job}, expected={}, preconditions={}
    )
    with pytest.raises(raised) as caught:
        adapter.prepare(scenario, csd_gate_work_root / CSD_GATE_SCENARIO)
    assert getattr(caught.value, "gap_id", None) == gap_id
    for detail in details:
        assert detail in str(caught.value)
    assert adapter._plan is None and not adapter._touched
    assert list(csd_gate_work_root.iterdir()) == []


# Each refuse case: inputs.job, whether the session has started and whether
# it is blocked, the exception expected (None when refuse refuses nothing),
# its gap_id and the texts its message holds. Before the session and in a
# blocked one, refuse leaves the cause to prepare (the cases above).
CSD_REFUSE_CASES = [
    pytest.param(
        "CBADMCDJ", True, False, base.AdapterUnavailable, "GAP-MIS-016", CSD_GATE_REASON, id="job"
    ),
    pytest.param(
        ["CLOSEFIL", "CBADMCDJ", "OPENFIL"],
        True,
        False,
        base.AdapterUnavailable,
        "GAP-MIS-016",
        CSD_GATE_REASON,
        id="chain-member",
    ),
    pytest.param("CLOSEFIL", True, False, None, None, (), id="other-job-passes"),
    pytest.param(
        ["CBADMCDJ", "NOSUCHJOB"],
        True,
        False,
        HarnessError,
        None,
        ("'NOSUCHJOB' is not a job",),
        id="unknown-job",
    ),
    pytest.param("CBADMCDJ", True, True, None, None, (), id="broken-session"),
    pytest.param("CBADMCDJ", False, False, None, None, (), id="not-started"),
]
# The command log mark and the BPE evidence pending when refuse is called: a
# refusal opens the capture from that mark and takes that evidence under
# session/, as prepare does; a scenario refuse lets through leaves both.
CSD_REFUSE_LOG_MARK = 7
CSD_REFUSE_PENDING = {"jcl/UNLOAD01": b"//UNLOAD01 JOB\n"}


@pytest.mark.parametrize(("job", "started", "broken", "raised", "gap_id", "details"), CSD_REFUSE_CASES)
def test_unikix_refuse_blocks_a_csd_utility_job_only_in_a_started_session(
    csd_gate_work_root: Path,
    job: str | list[str],
    started: bool,
    broken: bool,
    raised: type[Exception] | None,
    gap_id: str | None,
    details: tuple[str, ...],
) -> None:
    adapter = UniKixAdapter(work_root=csd_gate_work_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter._session_ready = started
    adapter._env = object()
    healthy = started and not broken
    # Only a refusal reads the log mark, to open the capture; any other use of
    # the commands raises _BeyondTheGate, and a scenario refuse lets through
    # uses none. Only a started, healthy session resolves the jobs.
    adapter._commands = _GateCommands(CSD_REFUSE_LOG_MARK) if raised is not None else _GateFake()
    adapter._batch = _GateBatch(CSD_REFUSE_PENDING) if healthy else _GateFake()
    if broken:
        adapter._broken = CSD_GATE_BROKEN
    # What an earlier run left, which only a refusal resets, as prepare does.
    earlier = (object(), object(), True)
    adapter._plan, adapter._result, adapter._touched = earlier
    scenario = SimpleNamespace(
        id=CSD_GATE_SCENARIO, inputs={"job": job}, expected={}, preconditions={}
    )
    if raised is None:
        assert adapter.refuse(scenario) is None
        assert adapter._capture is None and adapter._capture_id is None and adapter._log_mark == 0
        assert (adapter._plan, adapter._result, adapter._touched) == earlier
        if healthy:
            assert adapter._batch.take_evidence() == CSD_REFUSE_PENDING
    else:
        with pytest.raises(raised) as caught:
            adapter.refuse(scenario)
        assert getattr(caught.value, "gap_id", None) == gap_id
        for detail in details:
            assert detail in str(caught.value)
        # The capture write_evidence writes, opened as prepare opens it.
        assert isinstance(adapter._capture, evidence.Capture)
        assert adapter._capture_id == CSD_GATE_SCENARIO
        assert adapter._log_mark == CSD_REFUSE_LOG_MARK
        assert adapter._capture.sysout == {f"session/{name}": data for name, data in CSD_REFUSE_PENDING.items()}
        assert adapter._plan is None and adapter._result is None and not adapter._touched
    assert list(csd_gate_work_root.iterdir()) == []


class _InquiryLessCommands(_GateFake):
    """The ``CommandSet`` of a started session whose probes found every entry
    of the committed ``commands.yaml`` available except
    ``region_file_inquire``, which that file leaves without a command, and
    whose log is empty: what the observation rule, a refusing ``refuse`` and
    ``write_evidence`` read. Any other use raises _BeyondTheGate."""

    def __init__(self) -> None:
        self._committed = unikix_env.CommandSet.load()
        self.path = self._committed.path
        self.ids = self._committed.ids

    def entry(self, entry_id: str) -> unikix_env.CommandEntry:
        return self._committed.entry(entry_id)

    def available(self, entry_id: str) -> bool:
        return entry_id != "region_file_inquire"

    def probe_reason(self, entry_id: str) -> str | None:
        return "" if self.available(entry_id) else self._committed.probe_reason(entry_id)

    def mark(self) -> int:
        return 0

    def log_since(self, mark: int) -> list[dict[str, object]]:
        return []


class _InquiryLessBatch(_GateBatch):
    """The ``BatchService`` of that session: _GateBatch, with no job number
    for ``write_evidence`` to take."""

    def take_job_numbers(self) -> list[str]:
        return []


class _RecordedEnvironment(_GateFake):
    """The ``UniKixEnvironment`` of that session as ``write_evidence`` reads
    it: its record alone."""

    def as_record(self) -> dict[str, object]:
        return {}


# runner.run on that session, in this order: each scenario, the gap it must
# end blocked under and a text of its reason. Both scenarios expect
# @REGION-FILES; CBADMCDJ STEP1 runs DFHCSDUP, OPENFIL runs SDSF.
CSD_RUNNER_ORDER = (
    ("CFG-CBADMCDJ-normal-01", "GAP-MIS-016", "DFHCSDUP has no confirmed UniKix equivalent"),
    ("CFG-OPENFIL-normal-01", "GAP-MIS-011", "@REGION-FILES not available on unikix"),
    ("CFG-CBADMCDJ-normal-01", "GAP-MIS-016", "DFHCSDUP has no confirmed UniKix equivalent"),
)


def test_runner_applies_the_csd_utility_refusal_before_the_observation_rule(
    csd_gate_work_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """On a started session without region file inquiry, CBADMCDJ ends
    blocked under GAP-MIS-016 wherever it runs, never under the GAP-MIS-011
    the observation rule would raise for its @REGION-FILES, while the rule
    still blocks OPENFIL. Neither reaches prepare, which raises
    _BeyondTheGate here. The adapter's own write_evidence hands the evidence
    writer each CBADMCDJ run, with a record naming GAP-MIS-016, and no
    OPENFIL run. The runtime, environment and evidence writers are replaced,
    so nothing is written in the repository."""
    adapter = UniKixAdapter(work_root=csd_gate_work_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter._session_ready = True
    adapter._env = _RecordedEnvironment()
    adapter._commands = _InquiryLessCommands()
    adapter._batch = _InquiryLessBatch()
    runtimes: list[dict[str, object]] = []
    environments: list[dict[str, object]] = []
    evidence_written: list[tuple[str, object, evidence.Capture]] = []

    def unreachable_prepare(scenario: object, workdir: Path) -> None:
        raise _BeyondTheGate(f"UniKixAdapter.prepare({getattr(scenario, 'id', scenario)})")

    def keep_runtime(scenario_id: str, data: Mapping[str, object]) -> Path:
        runtimes.append(dict(data))
        return csd_gate_work_root / f"{scenario_id}.yaml"

    def keep_environment(env: Mapping[str, object]) -> Path:
        environments.append(dict(env))
        return csd_gate_work_root / "environment.yaml"

    def keep_evidence(
        writer: evidence.EvidenceWriter,
        scenario: object,
        record: Mapping[str, object],
        capture: evidence.Capture,
    ) -> str:
        scenario_id = str(getattr(scenario, "id", scenario))
        evidence_written.append((scenario_id, record.get("gap_id"), capture))
        return evidence.evidence_path(writer.execution_id, scenario_id)

    monkeypatch.setattr(adapter, "prepare", unreachable_prepare)
    monkeypatch.setattr(results, "write_runtime", keep_runtime)
    monkeypatch.setattr(results, "write_environment", keep_environment)
    monkeypatch.setattr(evidence.EvidenceWriter, "write", keep_evidence)
    for scenario_id, gap_id, reason in CSD_RUNNER_ORDER:
        outcome = runner.run(scenario_id, adapter)
        assert (outcome.status, outcome.reason, outcome.gap_id) == (
            runner.BLOCKED,
            runner.REASON_UNAVAILABLE,
            gap_id,
        ), outcome.diff or outcome.detail
        assert reason in outcome.detail
        assert outcome.record is not None and outcome.record["gap_id"] == gap_id
        assert outcome.workdir is None
    assert [runtime["gap_id"] for runtime in runtimes] == [gap for _, gap, _ in CSD_RUNNER_ORDER]
    # CBADMCDJ gets its evidence before and after OPENFIL, which gets none.
    refused = [
        (scenario_id, gap_id) for scenario_id, gap_id, _ in CSD_RUNNER_ORDER if scenario_id == CSD_GATE_SCENARIO
    ]
    assert [(scenario_id, gap_id) for scenario_id, gap_id, _ in evidence_written] == refused
    assert len(refused) == 2
    for _, _, capture in evidence_written:
        assert capture.environment["execution_id"] == adapter.execution_id
        assert capture.environment["commands"]["region_file_inquire"]["available"] is False
    assert ["evidence" in runtime for runtime in runtimes] == [
        scenario_id == CSD_GATE_SCENARIO for scenario_id, _, _ in CSD_RUNNER_ORDER
    ]
    assert adapter._capture is None and adapter._plan is None and not adapter._touched
    assert list(csd_gate_work_root.iterdir()) == []


# The evidence of one blocked unikix run of EVIDENCE_SCENARIO, written to a
# tests root of each case's own inside the work root (evidence_tests_root),
# never to the checkout's reports.
EVIDENCE_SCENARIO = "SEC-COSGN00C-normal-01"
EVIDENCE_EXECUTION_ID = evidence.make_execution_id(
    datetime.datetime(2026, 11, 11, 7, 0, 0, tzinfo=datetime.timezone.utc), "unit-harness"
)
# Concurrent writers of EVIDENCE_SCENARIO, each with datasets of its own, and
# how long one waits for the others before giving up.
EVIDENCE_WRITERS = 8
EVIDENCE_WRITER_DATASETS = 40
EVIDENCE_BARRIER_TIMEOUT_S = 60
EVIDENCE_ROOT_PREFIX = "unit-evidence-"
# The writers are threads of one child process (sys.executable -B with tests/
# on its path, run in the case's tests root, which is also its TMPDIR so
# nothing it does with tempfile leaves the work root), so a writer that hangs
# can be killed: it gets the tests root, the execution and scenario ids, the
# barrier timeout, the result record and each writer's dataset names, and
# prints one JSON line, each writer's returned evidence path or its exception.
# It is killed once it runs past EVIDENCE_TIMEOUT_S, longer than the barrier's.
EVIDENCE_TIMEOUT_S = 120
EVIDENCE_CHILD = """\
import json, sys, threading
sys.path.insert(0, sys.argv[1])
from harness import HarnessError
from adapters.unikix import evidence

tests_root, execution_id, scenario_id = sys.argv[2], sys.argv[3], sys.argv[4]
barrier_timeout, record, names = float(sys.argv[5]), json.loads(sys.argv[6]), json.loads(sys.argv[7])
barrier = threading.Barrier(len(names))
outcomes = {}

def write(writer):
    try:
        capture = evidence.Capture(datasets={name: bytes([writer]) * 64 for name in names[writer]})
        own = evidence.EvidenceWriter(tests_root, execution_id)
        barrier.wait(barrier_timeout)
        outcomes[writer] = {"written": own.write(scenario_id, record, capture)}
    except Exception as exc:
        outcomes[writer] = {
            "error": type(exc).__name__,
            "harness_error": isinstance(exc, HarnessError),
            "message": str(exc),
        }

threads = [threading.Thread(target=write, args=(writer,)) for writer in range(len(names))]
for thread in threads:
    thread.start()
for thread in threads:
    thread.join()
print(json.dumps(outcomes))
"""

# What already stands where the scenario's evidence folder would be created,
# and the cause the refusal names.
EVIDENCE_OCCUPANTS = [
    pytest.param("empty-folder", "another write owns it or left it incomplete", id="empty-folder"),
    pytest.param("written-folder", f"its {evidence.RESULT_FILE} already exists", id="written-folder"),
    pytest.param("file", "a file stands in its place", id="file"),
    pytest.param("link", "a symbolic link stands in its place", id="link-to-an-empty-folder"),
]


def _evidence_record() -> dict:
    """The contract-conforming result record of a blocked unikix run of
    EVIDENCE_SCENARIO under EVIDENCE_EXECUTION_ID."""
    return results.build_record(
        scenarios.get(EVIDENCE_SCENARIO),
        results.UNIKIX,
        results.STATUS_BLOCKED,
        reason="adapter-unavailable",
        gap_id="GAP-MIS-009",
        execution_id=EVIDENCE_EXECUTION_ID,
    )


@pytest.fixture
def evidence_tests_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh, empty tests root of this case's own inside the session work
    root (carddemo_work_root), where every run writes, never under pytest's
    basetemp or in the checkout, removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=EVIDENCE_ROOT_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def _evidence_folder(tests_root: Path) -> Path:
    return evidence.evidence_root(tests_root, EVIDENCE_EXECUTION_ID) / EVIDENCE_SCENARIO


def _tree(root: Path) -> dict[str, bytes | str | None]:
    """Every path under ``root``: a file's bytes, a link's target, ``None``
    for a folder. Links are never followed."""
    tree: dict[str, bytes | str | None] = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            tree[path.relative_to(root).as_posix()] = os.readlink(path)
        else:
            tree[path.relative_to(root).as_posix()] = None if path.is_dir() else path.read_bytes()
    return tree


@pytest.mark.parametrize(("occupant", "cause"), EVIDENCE_OCCUPANTS)
def test_evidence_writer_refuses_an_existing_scenario_folder_and_keeps_it(
    evidence_tests_root: Path, occupant: str, cause: str
) -> None:
    folder = _evidence_folder(evidence_tests_root)
    writer = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
    if occupant == "written-folder":
        writer.write(EVIDENCE_SCENARIO, _evidence_record(), evidence.Capture())
    else:
        folder.parent.mkdir(parents=True)
        if occupant == "empty-folder":
            folder.mkdir()
        elif occupant == "file":
            folder.write_bytes(b"")
        else:
            (evidence_tests_root / "elsewhere").mkdir()
            folder.symlink_to(evidence_tests_root / "elsewhere", target_is_directory=True)
    before = _tree(evidence_tests_root)
    with pytest.raises(HarnessError) as caught:
        writer.write(EVIDENCE_SCENARIO, _evidence_record(), evidence.Capture())
    message = str(caught.value)
    assert message.startswith("evidence is immutable: ")
    assert f"/{EVIDENCE_SCENARIO} already exists ({cause}); it is kept as found" in message
    assert _tree(evidence_tests_root) == before


def _reaped(child: subprocess.Popen) -> tuple[int, str, str]:
    """The return code, stdout and stderr of the child process ``child``,
    killed first when it still runs, so it never outlives the case."""
    if child.poll() is None:
        child.kill()
    out, err = child.communicate()
    return child.returncode, out, err


def test_concurrent_evidence_writes_of_one_scenario_leave_one_writers_files(
    evidence_tests_root: Path,
) -> None:
    record = _evidence_record()
    assert json.loads(json.dumps(record)) == record  # the child gets it as JSON
    names = [
        [f"DS{writer:02d}-{number:02d}" for number in range(EVIDENCE_WRITER_DATASETS)]
        for writer in range(EVIDENCE_WRITERS)
    ]
    child = subprocess.Popen(
        [
            sys.executable,
            "-B",
            "-c",
            EVIDENCE_CHILD,
            str(TESTS_ROOT),
            str(evidence_tests_root),
            EVIDENCE_EXECUTION_ID,
            EVIDENCE_SCENARIO,
            str(EVIDENCE_BARRIER_TIMEOUT_S),
            json.dumps(record),
            json.dumps(names),
        ],
        cwd=evidence_tests_root,
        env={**os.environ, "TMPDIR": str(evidence_tests_root)},
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    timed_out = False
    try:
        child.communicate(timeout=EVIDENCE_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        timed_out = True
    finally:
        returncode, out, err = _reaped(child)
    assert not timed_out, f"the writers still ran after {EVIDENCE_TIMEOUT_S} s, so killed:\n{out}{err}"
    assert returncode == 0, out + err
    outcomes = {int(writer): outcome for writer, outcome in json.loads(out).items()}
    winners = [writer for writer, outcome in outcomes.items() if "written" in outcome]
    refusals = [outcome for outcome in outcomes.values() if "written" not in outcome]
    assert sorted(outcomes) == list(range(EVIDENCE_WRITERS))
    assert len(winners) == 1, outcomes
    assert all(
        refusal["harness_error"] and refusal["message"].startswith("evidence is immutable: ") for refusal in refusals
    ), refusals
    folder = _evidence_folder(evidence_tests_root)
    files = sorted(os.listdir(folder / evidence.DATASETS_DIR))
    assert files == sorted(evidence.dataset_file_name(name) for name in names[winners[0]])
    written = load_yaml(folder / evidence.RESULT_FILE)
    assert sorted(written["capture_summary"]["datasets"]) == files


# Ids of a valid form that the closed scenario id manifest does not hold: an
# invented id, a number past its range, the names of the reports' own folders
# and files, and a one-letter id.
NON_MANIFEST_IDS = [
    pytest.param(scenario_id, id=scenario_id)
    for scenario_id in (
        "NOT-A-SCENARIO",
        "SEC-COSGN00C-normal-99",
        "baselines",
        "results",
        "README.md",
        "validation.yaml",
        "x",
    )
]
NON_MANIFEST_REFUSAL = "is not an id of the closed scenario id manifest"


@pytest.mark.parametrize("as_object", [False, True], ids=["id", "scenario-object"])
@pytest.mark.parametrize("scenario_id", NON_MANIFEST_IDS)
def test_evidence_writer_refuses_an_id_outside_the_manifest(
    evidence_tests_root: Path, scenario_id: str, as_object: bool
) -> None:
    assert scenario_id not in scenarios.ALL_IDS
    scenario = SimpleNamespace(id=scenario_id) if as_object else scenario_id
    with pytest.raises(ValueError, match=NON_MANIFEST_REFUSAL):
        evidence.evidence_path(EVIDENCE_EXECUTION_ID, scenario_id)
    writer = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
    with pytest.raises(ValueError, match=NON_MANIFEST_REFUSAL):
        writer.write(scenario, {}, evidence.Capture())
    assert list(evidence_tests_root.iterdir()) == []


# Records that break the result record contract, as changes to the
# conforming blocked record (None: the empty record), with problems
# record_problems names for each.
CONTRACT_BREAKING_RECORDS = [
    pytest.param(
        {"status": "bogus", "reason": "nonsense", "gap_id": None},
        ("status: 'bogus' is not one of",),
        id="bogus-status",
    ),
    pytest.param(
        {
            "status": results.STATUS_PASSED,
            "reason": None,
            "gap_id": None,
            "source_identity": None,
            "execution_fingerprint": None,
            "decoded": None,
        },
        (
            "source_identity: a passed record names the sources it ran",
            "execution_fingerprint: a passed record names what it executed",
            "decoded: a passed record carries the decoded outcome it asserted",
        ),
        id="passed-without-identity-fingerprint-or-outcome",
    ),
    pytest.param(
        {"gap_id": None},
        ("gap_id: a blocked record names its gap",),
        id="blocked-without-gap",
    ),
    pytest.param(
        None,
        ("status: None is not one of", "scenario_digest: None is not a sha256 hex digest"),
        id="empty-record",
    ),
]
CONTRACT_REFUSAL = (
    f"{EVIDENCE_SCENARIO}: evidence not written: "
    "the result record breaks the result record contract: "
)


@pytest.mark.parametrize(("changes", "problems"), CONTRACT_BREAKING_RECORDS)
def test_evidence_writer_refuses_a_record_that_breaks_the_contract(
    evidence_tests_root: Path, changes: dict | None, problems: tuple[str, ...]
) -> None:
    record = {} if changes is None else {**_evidence_record(), **changes}
    writer = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
    with pytest.raises(HarnessError) as caught:
        writer.write(EVIDENCE_SCENARIO, record, evidence.Capture())
    message = str(caught.value)
    assert message.startswith(CONTRACT_REFUSAL)
    for problem in problems:
        assert problem in message
    assert list(evidence_tests_root.iterdir()) == []


def test_evidence_writer_writes_a_conforming_record_that_reads_back_equal(
    evidence_tests_root: Path,
) -> None:
    record = _evidence_record()
    capture = evidence.Capture(
        datasets={"USRSEC": b"\x00\x01abc", "@CATALOG": b"X\n"}, sysout={"1/SYSOUT": b"RC 0\n"}
    )
    writer = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
    relative = writer.write(scenarios.get(EVIDENCE_SCENARIO), record, capture)
    assert relative == record["evidence_path"]
    folder = _evidence_folder(evidence_tests_root)
    assert sorted(os.listdir(folder)) == sorted(
        (
            evidence.DATASETS_DIR,
            evidence.ENVIRONMENT_FILE,
            evidence.RESULT_FILE,
            evidence.SCREENS_FILE,
            evidence.SYSOUT_DIR,
        )
    )
    written = load_yaml(folder / evidence.RESULT_FILE)
    fields = {name: written.get(name) for name in results.RECORD_FIELDS}
    assert fields == record
    assert results.record_problems(fields) == []


# The folders above the scenario's evidence folder, relative to the case's
# tests root: an existing one is checked before anything is created in it.
EVIDENCE_ANCESTORS = [
    pytest.param("reports", id="reports"),
    pytest.param("reports/unikix", id="unikix"),
    pytest.param(f"reports/unikix/{EVIDENCE_EXECUTION_ID}", id="execution"),
]
# Where a write that a link redirected would land: a folder of the case's
# tests root outside reports/.
EVIDENCE_ELSEWHERE = "elsewhere"
EVIDENCE_FILES = (
    evidence.DATASETS_DIR,
    evidence.ENVIRONMENT_FILE,
    evidence.RESULT_FILE,
    evidence.SCREENS_FILE,
    evidence.SYSOUT_DIR,
)


def _evidence_capture() -> evidence.Capture:
    """A capture with a dataset and a sysout file in a folder of its own, so
    a write creates every kind of evidence folder."""
    return evidence.Capture(datasets={"USRSEC": b"\x00\x01abc"}, sysout={"1/SYSOUT": b"RC 0\n"})


@pytest.mark.parametrize("umask", [0o002, 0o077, 0o477], ids=["umask-002", "umask-077", "umask-477"])
def test_evidence_writer_sets_its_modes_whatever_the_umask(evidence_tests_root: Path, umask: int) -> None:
    writer = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
    previous = os.umask(umask)
    try:
        writer.write(EVIDENCE_SCENARIO, _evidence_record(), _evidence_capture())
    finally:
        os.umask(previous)
    scenario = _evidence_folder(evidence_tests_root).relative_to(evidence_tests_root).as_posix()
    folders = (
        "reports",
        "reports/unikix",
        f"reports/unikix/{EVIDENCE_EXECUTION_ID}",
        scenario,
        f"{scenario}/{evidence.DATASETS_DIR}",
        f"{scenario}/{evidence.SYSOUT_DIR}",
        f"{scenario}/{evidence.SYSOUT_DIR}/1",
    )
    files = (
        f"{scenario}/{evidence.DATASETS_DIR}/USRSEC.bin",
        f"{scenario}/{evidence.SYSOUT_DIR}/1/SYSOUT",
        f"{scenario}/{evidence.SCREENS_FILE}",
        f"{scenario}/{evidence.ENVIRONMENT_FILE}",
        f"{scenario}/{evidence.RESULT_FILE}",
    )
    reports = evidence_tests_root / "reports"
    modes = {
        path.relative_to(evidence_tests_root).as_posix(): stat.S_IMODE(path.lstat().st_mode)
        for path in (reports, *reports.rglob("*"))
    }
    assert modes == {**dict.fromkeys(folders, 0o755), **dict.fromkeys(files, 0o644)}


@pytest.mark.parametrize(("before", "after"), [(0o777, 0o755), (0o730, 0o710)], ids=["0777", "0730"])
@pytest.mark.parametrize("ancestor", EVIDENCE_ANCESTORS)
def test_evidence_writer_takes_group_and_other_write_from_an_existing_ancestor(
    evidence_tests_root: Path, ancestor: str, before: int, after: int
) -> None:
    folder = evidence_tests_root / ancestor
    folder.mkdir(parents=True)
    folder.chmod(before)
    writer = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
    writer.write(EVIDENCE_SCENARIO, _evidence_record(), _evidence_capture())
    assert stat.S_IMODE(folder.lstat().st_mode) == after
    assert sorted(os.listdir(_evidence_folder(evidence_tests_root))) == sorted(EVIDENCE_FILES)


@pytest.mark.parametrize("ancestor", EVIDENCE_ANCESTORS)
def test_evidence_writer_refuses_a_linked_ancestor_and_writes_nothing_through_it(
    evidence_tests_root: Path, ancestor: str
) -> None:
    link = evidence_tests_root / ancestor
    link.parent.mkdir(parents=True, exist_ok=True)
    elsewhere = evidence_tests_root / EVIDENCE_ELSEWHERE
    elsewhere.mkdir()
    link.symlink_to(elsewhere, target_is_directory=True)
    before = _tree(evidence_tests_root)
    writer = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
    with pytest.raises(HarnessError) as caught:
        writer.write(EVIDENCE_SCENARIO, _evidence_record(), _evidence_capture())
    assert str(caught.value) == (
        f"{harness.display_path(link)} is not a folder of its own: evidence is never written "
        f"through a symbolic link or over a file"
    )
    assert list(elsewhere.iterdir()) == []
    assert _tree(evidence_tests_root) == before


def test_evidence_writer_refuses_an_ancestor_another_user_owns(
    evidence_tests_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Another user's folder, as the writer sees it: the effective uid it
    # compares the owner with is one the folder's owner does not have, so no
    # chown (and no root) is needed.
    reports = evidence_tests_root / "reports"
    reports.mkdir()
    reports.chmod(0o777)
    owner = reports.lstat().st_uid
    before = _tree(evidence_tests_root)
    monkeypatch.setattr(evidence.os, "geteuid", lambda: owner + 1)
    writer = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
    with pytest.raises(HarnessError) as caught:
        writer.write(EVIDENCE_SCENARIO, _evidence_record(), _evidence_capture())
    assert str(caught.value) == (
        f"{harness.display_path(reports)} is owned by uid {owner}, not {owner + 1}: evidence is "
        f"written only into folders of its own user"
    )
    assert _tree(evidence_tests_root) == before
    assert stat.S_IMODE(reports.lstat().st_mode) == 0o777


def test_evidence_writer_writes_nothing_through_a_scenario_folder_swapped_for_a_link(
    evidence_tests_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    folder = _evidence_folder(evidence_tests_root)
    moved = folder.with_name(f"{folder.name}.moved")
    elsewhere = evidence_tests_root / EVIDENCE_ELSEWHERE
    elsewhere.mkdir()
    make_folder = os.mkdir
    swapped: list[str] = []

    def mkdir(path: str | os.PathLike[str], *args: object, **kwargs: object) -> None:
        # Just before datasets/ is made in the claimed scenario folder, one
        # who can write the execution folder moves the scenario folder aside
        # and puts a link to a folder outside reports/ in its place.
        if not swapped and os.path.basename(os.fspath(path)) == evidence.DATASETS_DIR:
            os.rename(folder, moved)
            os.symlink(elsewhere, folder, target_is_directory=True)
            swapped.append(os.fspath(path))
        make_folder(path, *args, **kwargs)

    monkeypatch.setattr(evidence.os, "mkdir", mkdir)
    writer = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
    writer.write(EVIDENCE_SCENARIO, _evidence_record(), _evidence_capture())
    assert swapped
    assert folder.is_symlink()
    assert list(elsewhere.iterdir()) == []
    assert sorted(os.listdir(moved)) == sorted(EVIDENCE_FILES)
    assert load_yaml(moved / evidence.RESULT_FILE)["scenario_id"] == EVIDENCE_SCENARIO


# baselines.write publishing one id whole, on a baselines folder in the
# case's own work_root_case folder inside the session work root. A writer
# child process (sys.executable -B with tests/ on its path, the case folder
# its working directory and its TMPDIR) kills itself with SIGKILL at one seam
# of the publication, so it dies exactly there: after the first staged file
# (_stage_file), between the two renames of the fallback
# swap (the stage's rename, once the pre-image holds the baseline), or after
# the exchange, before the replaced baseline is removed (_remove_entry).
# "exchange" and "fallback" children write to the end, the latter with
# renameat2 taken away.
PUBLICATION_ID = "TRN-CBTRN02C-negative-03"
PUBLICATION_DATASETS = ("ACCTDATA", "TRANSACT")
PUBLICATION_WRITERS = 3
PUBLICATION_ROUNDS = 12
PUBLICATION_TIMEOUT_S = 60
PUBLICATION_CHILD = """\
import json, os, signal, sys
sys.path.insert(0, sys.argv[1])
from harness import baselines

seam, root, writes, baseline_id = sys.argv[2], sys.argv[3], json.loads(sys.argv[4]), sys.argv[5]

def die(*args, **kwargs):
    os.kill(os.getpid(), signal.SIGKILL)

if seam == "staged-file":
    stage_file = baselines._stage_file
    def staged(*args, **kwargs):
        stage_file(*args, **kwargs)
        die()
    baselines._stage_file = staged
elif seam == "between-renames":
    baselines._exchange = lambda *args: False
    rename = os.rename
    def renamed(source, *args, **kwargs):
        if str(source).endswith(".new"):
            die()
        rename(source, *args, **kwargs)
    os.rename = renamed
elif seam == "after-exchange":
    baselines._remove_entry = die
elif seam == "fallback":
    baselines._exchange = lambda *args: False
for expected, manifest, raw in writes:
    datasets = {name: bytes.fromhex(data) for name, data in raw.items()}
    baselines.write(baseline_id, expected, manifest, datasets, baselines_dir=root)
"""

# The reader of the concurrent writers, a child process like them, so the
# test process itself never waits on the baselines lock: it loads the
# baseline over and over and takes each load's writer tags as
# _publication_tags does, creates the ready file once its first load is done,
# and stops after the load that began once the stop file existed. Between two
# loads it sleeps PUBLICATION_READ_PAUSE_S, so a writer waiting for the
# exclusive lock gets it on a busy host instead of losing it to the reader's
# next shared lock. Its only output is one JSON line: the number of loads,
# the tags of the first PUBLICATION_MIXED_KEPT loads that held more than one,
# and the last load's. The seed, the writers and the reader share one
# deadline of PUBLICATION_TIMEOUT_S, polled every PUBLICATION_POLL_S;
# whatever still runs when it passes is killed.
PUBLICATION_POLL_S = 0.01
PUBLICATION_MIXED_KEPT = 5
PUBLICATION_READ_PAUSE_S = 0.001
PUBLICATION_READY_FILE = "reader-ready"
PUBLICATION_STOP_FILE = "reader-stop"
PUBLICATION_READER = """\
import json, os, sys, time
sys.path.insert(0, sys.argv[1])
from harness import baselines

root, baseline_id, ready, stop, kept = sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], int(sys.argv[6])
pause = float(sys.argv[7])
loads, mixed = 0, []
while True:
    stopping = os.path.exists(stop)
    loaded = baselines.load(baseline_id, baselines_dir=root)
    tags = {
        loaded.expected["messages"][0].removeprefix("writer "),
        loaded.manifest["execution_id"].removeprefix("gnucobol-batch-"),
    }
    tags.update(data.decode().partition(":")[0] for data in loaded.datasets.values())
    loads += 1
    if len(tags) != 1 and len(mixed) < kept:
        mixed.append(sorted(tags))
    if stopping:
        break
    if loads == 1:
        open(ready, "x").close()
    time.sleep(pause)
print(json.dumps({"loads": loads, "mixed": mixed, "final": sorted(tags)}))
"""


def _publication_baseline(
    tag: str, names: tuple[str, ...] = PUBLICATION_DATASETS
) -> tuple[dict, dict, dict[str, bytes]]:
    """An observed baseline whose three kinds of artifact all carry ``tag``:
    the expected message, the manifest's execution id and each raw dataset."""
    expected = {"return_code": 0, "messages": [f"writer {tag}"], "datasets": {name: {"count": 1} for name in names}}
    manifest = {
        **DERIVED_MANIFEST,
        "capture_platform": "gnucobol-batch",
        "execution_id": f"gnucobol-batch-{tag}",
        "execution_fingerprint": "0" * 64,
    }
    return expected, manifest, {name: f"{tag}:{name}".encode() for name in names}


def _publication_tags(loaded: baselines.Baseline) -> set[str]:
    """The writer tags the artifacts of ``loaded`` carry: exactly one when it
    was published whole."""
    tags = {
        loaded.expected["messages"][0].removeprefix("writer "),
        loaded.manifest["execution_id"].removeprefix("gnucobol-batch-"),
    }
    tags.update(data.decode().partition(":")[0] for data in loaded.datasets.values())
    return tags


def _publication_child(seam: str, root: Path, writes: list) -> subprocess.Popen:
    """A writer process that writes each ``(expected, manifest, datasets)``
    of ``writes`` to ``root`` in turn, with ``seam`` set up as the comment
    on ``PUBLICATION_ID`` describes. It runs in the case folder holding
    ``root``, which is also its ``TMPDIR`` so nothing it does with tempfile
    leaves the work root, and writes no bytecode (``-B``): conftest's
    ``sys.dont_write_bytecode`` is not inherited."""
    payload = json.dumps(
        [[expected, manifest, {name: data.hex() for name, data in raw.items()}] for expected, manifest, raw in writes]
    )
    return subprocess.Popen(
        [sys.executable, "-B", "-c", PUBLICATION_CHILD, str(TESTS_ROOT), seam, str(root), payload, PUBLICATION_ID],
        cwd=root.parent,
        env={**os.environ, "TMPDIR": str(root.parent)},
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def _publication_reader(root: Path, ready: Path, stop: Path) -> subprocess.Popen:
    """The ``PUBLICATION_READER`` process loading ``PUBLICATION_ID`` from
    ``root``, signalling with ``ready`` and stopped by ``stop``; like the
    writers it runs in, with ``TMPDIR`` set to, the case folder holding
    ``root`` and writes no bytecode (``-B``)."""
    return subprocess.Popen(
        [
            sys.executable,
            "-B",
            "-c",
            PUBLICATION_READER,
            str(TESTS_ROOT),
            str(root),
            PUBLICATION_ID,
            str(ready),
            str(stop),
            str(PUBLICATION_MIXED_KEPT),
            str(PUBLICATION_READ_PAUSE_S),
        ],
        cwd=root.parent,
        env={**os.environ, "TMPDIR": str(root.parent)},
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def _awaited(done: Callable[[], bool], deadline: float) -> bool:
    """Whether ``done()`` held before ``deadline``, a ``time.monotonic()``
    value, asking every ``PUBLICATION_POLL_S``."""
    while not done():
        if time.monotonic() >= deadline:
            return False
        time.sleep(PUBLICATION_POLL_S)
    return True


def _finished(child: subprocess.Popen) -> tuple[int, str]:
    """The return code and output of a writer process, killed when it runs
    past ``PUBLICATION_TIMEOUT_S``."""
    try:
        out, err = child.communicate(timeout=PUBLICATION_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        child.kill()
        out, err = child.communicate()
    return child.returncode, out + err


# Where the writer dies, whether a baseline stood before it, and whose
# baseline load must then return: the last complete one, the new one once
# it is in place, or none for an interrupted first capture.
PUBLICATION_INTERRUPTIONS = [
    pytest.param("staged-file", True, "old", id="replacement-between-staged-files"),
    pytest.param("staged-file", False, None, id="first-capture-between-staged-files"),
    pytest.param("between-renames", True, "old", id="fallback-swap-between-its-renames"),
    pytest.param("after-exchange", True, "new", id="replacement-after-the-exchange"),
]


@pytest.mark.parametrize(("seam", "replacing", "survivor"), PUBLICATION_INTERRUPTIONS)
def test_an_interrupted_publication_leaves_a_complete_baseline_and_a_retry_succeeds(
    work_root_case: Path, seam: str, replacing: bool, survivor: str | None
) -> None:
    root = work_root_case / "baselines"
    if replacing:
        baselines.write(PUBLICATION_ID, *_publication_baseline("old"), baselines_dir=root)
    else:
        root.mkdir()
    returncode, output = _finished(_publication_child(seam, root, [_publication_baseline("new")]))
    assert returncode == -signal.SIGKILL, output
    left = sorted(name for name in os.listdir(root) if name != PUBLICATION_ID)
    assert left, "the writer died with nothing of its publication left"
    assert all(name.startswith(f".{PUBLICATION_ID}.") and name[-4:] in (".new", ".old") for name in left), left
    judged, problems = baselines._working_tree(root)  # what the bump check would judge
    assert (sorted(judged), problems) == (sorted({PUBLICATION_ID} & set(os.listdir(root))), [])
    loaded = baselines.load(PUBLICATION_ID, missing_ok=True, baselines_dir=root)
    if survivor is None:
        assert loaded is None
        assert not (root / PUBLICATION_ID).exists()
    else:
        assert _publication_tags(loaded) == {survivor}
    written = baselines.write(PUBLICATION_ID, *_publication_baseline("retry"), baselines_dir=root)
    assert _publication_tags(written) == {"retry"}
    assert os.listdir(root) == [PUBLICATION_ID]
    assert sorted(os.listdir(root / PUBLICATION_ID / baselines.DATASETS_DIR)) == [
        f"{name}{baselines.RAW_SUFFIX}" for name in PUBLICATION_DATASETS
    ]


@pytest.mark.parametrize("swap", ["exchange", "fallback"])
def test_concurrent_writers_of_one_id_never_publish_mixed_artifacts(work_root_case: Path, swap: str) -> None:
    root = work_root_case / "baselines"
    ready = work_root_case / PUBLICATION_READY_FILE
    stop = work_root_case / PUBLICATION_STOP_FILE
    writer_names = [f"writer {writer}" for writer in range(PUBLICATION_WRITERS)]
    deadline = time.monotonic() + PUBLICATION_TIMEOUT_S
    children: dict[str, subprocess.Popen] = {}
    try:
        children["seed"] = _publication_child(swap, root, [_publication_baseline("seed")])
        seed = children["seed"]
        if _awaited(lambda: seed.poll() is not None, deadline) and seed.returncode == 0:
            reader = children["reader"] = _publication_reader(root, ready, stop)
            # The writers start once the reader loads, so it reads all along.
            if _awaited(lambda: ready.exists() or reader.poll() is not None, deadline) and ready.exists():
                for writer, name in enumerate(writer_names):
                    children[name] = _publication_child(
                        swap,
                        root,
                        # Every other round drops TRANSACT, so .bin files come and go.
                        [
                            _publication_baseline(f"w{writer}r{turn}", PUBLICATION_DATASETS[: 1 + turn % 2])
                            for turn in range(PUBLICATION_ROUNDS)
                        ],
                    )
                _awaited(lambda: all(children[name].poll() is not None for name in writer_names), deadline)
            stop.touch()  # the reader loads once more, then reports
            _awaited(lambda: reader.poll() is not None, deadline)
    finally:
        outlived = [name for name, child in children.items() if child.poll() is None]
        outcomes = {name: _reaped(child) for name, child in children.items()}
    assert not outlived, f"still running after {PUBLICATION_TIMEOUT_S} s, so killed: {outlived}; {outcomes}"
    returncodes = {name: returncode for name, (returncode, _, _) in outcomes.items()}
    assert returncodes == dict.fromkeys(["seed", "reader", *writer_names], 0), outcomes
    read = json.loads(outcomes["reader"][1])
    assert read["mixed"] == [], read
    assert set(read["final"]) <= {f"w{writer}r{PUBLICATION_ROUNDS - 1}" for writer in range(PUBLICATION_WRITERS)}, read
    assert len(read["final"]) == 1, read
    assert os.listdir(root) == [PUBLICATION_ID]


# Entries a baseline directory cannot hold, by their path inside it, what
# stands there, and how the refusal names it.
FOREIGN_BASELINE_ENTRIES = [
    pytest.param("notes.txt", "file", "is not part of a baseline", id="file"),
    pytest.param("scratch", "folder", "is not part of a baseline", id="folder"),
    pytest.param("datasets/README", "file", "is not part of a baseline", id="file-in-datasets"),
    pytest.param("datasets/OLD.bin", "folder", "is not part of a baseline", id="folder-named-as-a-dataset"),
    pytest.param("elsewhere", "link", "is a symbolic link", id="symbolic-link"),
]


@pytest.mark.parametrize(("relative", "kind", "refusal"), FOREIGN_BASELINE_ENTRIES)
def test_a_baseline_directory_holding_a_foreign_entry_is_not_replaced(
    work_root_case: Path, relative: str, kind: str, refusal: str
) -> None:
    root = work_root_case / "baselines"
    baselines.write(PUBLICATION_ID, *_publication_baseline("old"), baselines_dir=root)
    entry = root / PUBLICATION_ID / relative
    if kind == "file":
        entry.write_bytes(b"kept\n")
    elif kind == "folder":
        entry.mkdir()
    else:
        (work_root_case / "target").mkdir()
        entry.symlink_to(work_root_case / "target", target_is_directory=True)
    before = _tree(work_root_case)
    with pytest.raises(baselines.BaselineError) as caught:
        baselines.write(PUBLICATION_ID, *_publication_baseline("new"), baselines_dir=root)
    assert f"{PUBLICATION_ID}/{relative} {refusal}" in str(caught.value)
    assert _tree(work_root_case) == before


# A second write after the "a" baseline: its change, the files it rewrites
# and the ones it drops.
BASELINE_REWRITES = [
    pytest.param("identical", (), (), id="identical"),
    pytest.param("manifest", (baselines.MANIFEST_FILE,), (), id="manifest-changed"),
    pytest.param(
        "dataset-dropped",
        (baselines.EXPECTED_FILE,),
        (f"{baselines.DATASETS_DIR}/TRANSACT{baselines.RAW_SUFFIX}",),
        id="dataset-dropped",
    ),
]


@pytest.mark.parametrize("hard_links", [True, False], ids=["hard-links", "copies"])
@pytest.mark.parametrize(("change", "rewritten", "dropped"), BASELINE_REWRITES)
def test_a_rewrite_keeps_unchanged_files_and_an_identical_one_publishes_nothing(
    work_root_case: Path,
    monkeypatch: pytest.MonkeyPatch,
    change: str,
    rewritten: tuple[str, ...],
    dropped: tuple[str, ...],
    hard_links: bool,
) -> None:
    root = work_root_case / "baselines"
    directory = root / PUBLICATION_ID
    expected, manifest, raw = _publication_baseline("a")
    baselines.write(PUBLICATION_ID, expected, manifest, raw, baselines_dir=root)
    (directory / baselines.MANIFEST_FILE).chmod(0o640)
    (directory / baselines.DATASETS_DIR).chmod(0o750)
    directory.chmod(0o750)

    def statuses() -> dict[str, os.stat_result]:
        return {path.relative_to(directory).as_posix(): path.lstat() for path in [directory, *directory.rglob("*")]}

    before = statuses()
    if change == "manifest":
        manifest = {**manifest, "capture_date": "2026-02-02"}
    elif change == "dataset-dropped":
        expected = {**expected, "datasets": {"ACCTDATA": {"count": 1}}}
        raw = {"ACCTDATA": raw["ACCTDATA"]}
    if not hard_links:

        def no_link(*args: object, **kwargs: object) -> None:
            raise PermissionError(1, "Operation not permitted")

        monkeypatch.setattr(os, "link", no_link)
    baselines.write(PUBLICATION_ID, expected, manifest, raw, baselines_dir=root)
    after = statuses()
    assert os.listdir(root) == [PUBLICATION_ID]
    assert set(after) == set(before) - set(dropped)
    for relative, status in after.items():
        old = before[relative]
        assert stat.S_IMODE(status.st_mode) == stat.S_IMODE(old.st_mode), relative
        if change == "identical":
            assert (status.st_ino, status.st_mtime_ns) == (old.st_ino, old.st_mtime_ns), relative
        elif stat.S_ISDIR(status.st_mode):
            assert status.st_ino != old.st_ino, relative
        elif relative in rewritten:
            assert status.st_ino != old.st_ino, relative
        else:
            assert status.st_mtime_ns == old.st_mtime_ns, relative
            assert (status.st_ino == old.st_ino) == hard_links, relative
    assert baselines.load(PUBLICATION_ID, baselines_dir=root).manifest == manifest


# The command log of a scenario's evidence: a CommandSet of the committed
# commands.yaml whose in-memory log holds a probe, a refused run and a run with
# clipped output, logged by its own _record with elapsed times; the env has no
# deployment root, so nothing is run or written.
EVIDENCE_COMMANDS_LOG = f"{evidence.SYSOUT_DIR}/adapter/commands.log"


def _logged_commands() -> unikix_env.CommandSet:
    commands = unikix_env.CommandSet.load()
    env = SimpleNamespace(vars={})
    commands._record(
        env, "probe", "region_start", "command -v kixregion", "/deployment/project", 0,
        "completed", 0.0042, stdout="/opt/unikix/bin/kixregion\n",
    )
    commands._record(
        env, "run", "bpe_submit", "BPESUB TRNRPT00", None, None, "refused", 0.0,
        reason="probe failed",
    )
    commands._record(
        env, "run", "region_quiesce_query", "kixstat", "/deployment/project", 0, "completed",
        12.3456, stdout="T" * (unikix_env.LOG_LIMIT_BYTES + 1), stderr="warning\n",
    )
    return commands


def _without_elapsed(records: list[dict]) -> list[dict]:
    return [
        {key: value for key, value in record.items() if key != unikix_env.LOG_ELAPSED_KEY}
        for record in records
    ]


def test_evidence_command_log_renders_every_field_but_the_elapsed_time() -> None:
    records = _logged_commands().log
    # The in-memory log keeps the elapsed times the evidence copy leaves out.
    assert [record[unikix_env.LOG_ELAPSED_KEY] for record in records] == ["0.004", "0.000", "12.346"]
    expected = _without_elapsed(records)
    assert [sorted(record) for record in expected] == [
        ["command", "cwd", "id", "kind", "outcome", "rc", "seq", "stderr", "stdout"],
        ["command", "cwd", "id", "kind", "outcome", "rc", "reason", "seq", "stderr", "stdout"],
        ["command", "cwd", "id", "kind", "outcome", "rc", "seq", "stderr", "stdout", "truncated"],
    ]
    rendered = unikix_env.CommandSet.render_evidence_log(records)
    assert rendered.startswith("---\n")
    assert list(yaml.safe_load_all(rendered)) == expected
    assert unikix_env.LOG_ELAPSED_KEY not in rendered
    # Rendering leaves the records it was given as they were.
    assert all(unikix_env.LOG_ELAPSED_KEY in record for record in records)


# A sourcing entry, logged by its own _record: what a sourced script printed
# on stderr, nothing, or more than the limit, beside a stdout within or over it.
UNIKIX_OVER_LIMIT = unikix_env.LOG_LIMIT_BYTES + 1


@pytest.mark.parametrize(
    ("stdout", "stderr", "truncated"),
    [
        pytest.param("", "batchenv sourced\n", None, id="printed"),
        pytest.param("", "", None, id="printed-nothing"),
        pytest.param("", "E" * UNIKIX_OVER_LIMIT, None, id="clipped-stderr"),
        pytest.param("T" * UNIKIX_OVER_LIMIT, "E" * UNIKIX_OVER_LIMIT, ["stdout"], id="clipped-both"),
    ],
)
def test_evidence_command_log_withholds_what_a_sourced_script_printed(
    stdout: str, stderr: str, truncated: list[str] | None
) -> None:
    commands = unikix_env.CommandSet.load()
    commands._record(
        SimpleNamespace(vars={}), "run", "bpe_env", '. "$EBMHOME/batchenv" sys1', "/deployment/project", 0,
        "completed", 0.5, stdout=stdout, stderr=stderr, sourcing=True,
    )
    records = commands.log
    [shown] = yaml.safe_load_all(unikix_env.CommandSet.render_evidence_log(records))
    expected = {
        key: value for key, value in records[0].items() if key not in (unikix_env.LOG_ELAPSED_KEY, "truncated")
    }
    if stderr:
        expected.update(stderr="", withheld=["stderr"])
    if truncated:
        expected["truncated"] = truncated
    assert shown == expected
    # The record keeps what the script printed, clipped to the limit.
    assert records[0]["sourcing"] is True
    assert records[0]["stderr"] == stderr[:unikix_env.LOG_LIMIT_BYTES]


def test_unikix_write_evidence_writes_the_command_log_without_elapsed_times(
    evidence_tests_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    commands = _logged_commands()
    adapter = UniKixAdapter(work_root=evidence_tests_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter.execution_id = EVIDENCE_EXECUTION_ID
    adapter._commands = commands
    adapter._capture, adapter._capture_id = evidence.Capture(), EVIDENCE_SCENARIO
    monkeypatch.setattr(unikix_adapter.harness, "TESTS_ROOT", evidence_tests_root)
    record = _evidence_record()
    assert adapter.write_evidence(scenarios.get(EVIDENCE_SCENARIO), record) == record["evidence_path"]
    folder = _evidence_folder(evidence_tests_root)
    text = (folder / EVIDENCE_COMMANDS_LOG).read_text(encoding="utf-8")
    assert list(yaml.safe_load_all(text)) == _without_elapsed(commands.log)
    assert unikix_env.LOG_ELAPSED_KEY not in text
    written = load_yaml(folder / evidence.RESULT_FILE)
    assert written["capture_summary"]["sysout"] == {"adapter/commands.log": "adapter/commands.log"}
    # The session's own log keeps them.
    assert all(unikix_env.LOG_ELAPSED_KEY in entry for entry in commands.log)


# The UniKix environment on a fake installation: an environment file naming
# empty installation directories, which also sets TMPDIR and a JVM tmpdir of
# its own, and fake userenv and batchenv scripts in the deployment root. Every
# bash runs for real, inside a work root of the case's own.
UNIKIX_TEMP_ROOT_PREFIX = "unit-unikix-temp-"
UNIKIX_TEMP_EXECUTION = "unikix-temp-case"
UNIKIX_TEMP_VARS = ("TMPDIR", "TMP", "TEMP")
UNIKIX_JVM_OWNED = "-XX:+PerfDisableSharedMem"
UNIKIX_BPE_ENV_OVERRIDE = '. "$EBMHOME/batchenv" sys1 && export CARDDEMO_BPE_OVERRIDE=1'
# A folder of the case outside the deployment root and the work root; OUTSIDE
# in a case's shell text stands for its path.
UNIKIX_OUTSIDE = "outside"
# The UniKix environment refuses a deployment whose tmp/ path holds
# whitespace or a quote, which the JVM option variables naming it cannot
# carry (env.py _contain_temp). Every folder of a session work root whose
# path holds one holds it too, so there a case needing a fake deployment is
# skipped for that reason alone; under any other work root it runs.
UNIKIX_UNSAFE_TEMP_REFUSAL = (
    "the temporary directory {path!r} holds whitespace or a quote, which a JVM option variable "
    "cannot carry; choose another work root"
)
UNIKIX_UNSAFE_ROOT_SKIP = (
    "the session work root's path holds whitespace or a quote, which a JVM option variable cannot "
    "carry, so no UniKix deployment, whose tmp/ the JVM options name, can lie inside it; run "
    "under a work root holding neither to cover this case"
)
# The UniKix environment and deployment also refuse a deployment root and a
# work root whose path holds ':', at which COBCPY, PATH and LD_LIBRARY_PATH,
# naming the deployment's directories, are split; a work root's path is
# checked for whitespace and quotes first. Under a session work root whose
# path holds ':', a case needing a fake deployment is skipped for that reason
# alone.
PATH_LIST_UNSAFE = (
    "holds ':', at which COBCPY, PATH and LD_LIBRARY_PATH are split, so the search paths naming the "
    "deployment's directories below it would break; choose another work root"
)
UNIKIX_COLON_ROOT_SKIP = (
    "the session work root's path holds ':', at which COBCPY, PATH and LD_LIBRARY_PATH are split, so "
    "no UniKix deployment, whose directories those search paths name, can lie inside it; run under a "
    "work root without ':' to cover this case"
)


def _holds_jvm_unsafe_text(path: str | os.PathLike[str]) -> bool:
    """Whether ``path`` holds whitespace or a quote, which a JVM option
    variable splits at or reads."""
    return any(char.isspace() or char in "'\"" for char in os.fspath(path))


def _unikix_path_refusal(path: str | os.PathLike[str]) -> str | None:
    """Why the UniKix work-root rule refuses ``path``, after the path, or
    ``None``: whitespace or a quote first, then ':'."""
    if _holds_jvm_unsafe_text(path):
        return JVM_UNSAFE_PATH
    if ":" in os.fspath(path):
        return PATH_LIST_UNSAFE
    return None


def _skip_where_no_deployment_fits(temp: str | os.PathLike[str]) -> None:
    """Skip the case (UNIKIX_UNSAFE_ROOT_SKIP) when ``temp``, the tmp/ of
    its fake deployment, holds whitespace or a quote."""
    if _holds_jvm_unsafe_text(os.path.abspath(temp)):
        pytest.skip(UNIKIX_UNSAFE_ROOT_SKIP)


@pytest.fixture
def unikix_temp_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh, empty folder of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=UNIKIX_TEMP_ROOT_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def _fake_unikix_env_file(case_root: Path, extra: tuple[str, ...] = ()) -> Path:
    """``<case_root>/carddemo.env``, a fake environment file naming empty
    installation directories under ``case_root``; ``extra`` lines end it."""
    lines = []
    for name in unikix_env.REQUIRED_INSTALLATION_VARS:
        directory = case_root / "installation" / name.lower()
        directory.mkdir(parents=True)
        lines.append(f"{name}={directory}")
    lines += [
        "BASE_DIR=$HOME/unikixdemo",
        "TMPDIR=/var/tmp",
        "JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/tmp -Xmx64m'",
        *extra,
    ]
    env_file = case_root / "carddemo.env"
    env_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return env_file


def _fake_unikix_environ(env_file: Path) -> dict[str, str]:
    """What ``UniKixEnvironment.load`` reads: ``env_file`` and ``PATH``."""
    return {unikix_env.ENV_VARIABLE: str(env_file), "PATH": os.environ.get("PATH", os.defpath)}


def _fake_unikix_environment(
    case_root: Path, extra: tuple[str, ...] = ()
) -> unikix_env.UniKixEnvironment:
    """``UniKixEnvironment.load`` of a fake environment file under
    ``case_root`` (``_fake_unikix_env_file``), its deployment root
    ``<case_root>/work/unikix/<id>``. A load refused because that root's
    path holds ':' skips the case (UNIKIX_COLON_ROOT_SKIP), and so does one
    refused because its tmp/ holds whitespace or a quote
    (``_skip_where_no_deployment_fits``); any other refusal, such as a
    planted link found first, reaches the case."""
    env_file = _fake_unikix_env_file(case_root, extra)
    work = case_root / "work"
    root = work / "unikix" / UNIKIX_TEMP_EXECUTION
    try:
        return unikix_env.UniKixEnvironment.load(work, root, environ=_fake_unikix_environ(env_file))
    except HarnessError as exc:
        if str(exc) == f"deployment root {os.path.abspath(root)} {PATH_LIST_UNSAFE}":
            pytest.skip(UNIKIX_COLON_ROOT_SKIP)
        temp = os.path.abspath(root / "tmp")
        if str(exc) == UNIKIX_UNSAFE_TEMP_REFUSAL.format(path=temp):
            _skip_where_no_deployment_fits(temp)
        raise


def _fake_runtime_scripts(
    environment: unikix_env.UniKixEnvironment, *, userenv: str = "", batchenv: str = ""
) -> dict[str, Path]:
    """Write ``$KIXSYS/bin/userenv`` and ``$EBMHOME/batchenv``, each ending
    with the given line; batchenv exports an ``MBMSUB`` inside the root, its
    arguments and what it saw of userenv."""
    scripts = {
        "userenv": (environment.path("KIXSYS") / "bin" / "userenv", ["USERENV_SEEN=1", userenv]),
        "batchenv": (
            environment.path("EBMHOME") / "batchenv",
            [
                'MBMSUB="$EBMHOME/submit"',
                'BATCHENV_ARGS="$*"',
                'BATCHENV_SAW_USERENV="$USERENV_SEEN"',
                "echo batchenv sourced",
                batchenv,
            ],
        ),
    }
    for path, lines in scripts.values():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return {key: path for key, (path, _lines) in scripts.items()}


def _one_command(case_root: Path, command: str, probe: str) -> unikix_env.CommandSet:
    """A ``CommandSet`` of the single entry ``temp_case``."""
    entry = unikix_env.CommandEntry(
        id="temp_case",
        action="make a temporary file",
        command=command,
        probe=probe,
        timeout_s=10,
        gap="GAP-MIS-003",
        status="unconfirmed",
        source="unit case",
        notes="unit case",
    )
    return unikix_env.CommandSet({entry.id: entry}, case_root / "commands.yaml")


def _outside(case_root: Path) -> Path:
    """The case's empty ``UNIKIX_OUTSIDE`` folder."""
    outside = case_root / UNIKIX_OUTSIDE
    outside.mkdir()
    return outside


def _bpe_env_commands(case_root: Path, **changes: object) -> unikix_env.CommandSet:
    """The committed commands.yaml, its ``bpe_env`` entry changed as an
    operator would override it, loaded from a copy under ``case_root``."""
    document = yaml.safe_load(unikix_env.COMMANDS_FILE.read_text(encoding="utf-8"))
    document["entries"]["bpe_env"].update(changes)
    path = case_root / "commands.yaml"
    path.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    return unikix_env.CommandSet.load(path)


def test_unikix_load_points_every_temporary_file_setting_at_the_deployment_tmp(
    unikix_temp_root: Path,
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    temp = environment.deployment_root / "tmp"
    assert temp.is_dir() and stat.S_IMODE(temp.stat().st_mode) & 0o077 == 0
    assert {name: environment.vars[name] for name in UNIKIX_TEMP_VARS} == dict.fromkeys(
        UNIKIX_TEMP_VARS, str(temp)
    )
    # The file's own options are kept, its tmpdir replaced by the owned one,
    # last; _JAVA_OPTIONS, read after the command line, holds the owned alone.
    owned = f"-Djava.io.tmpdir={temp} {UNIKIX_JVM_OWNED}"
    assert environment.vars["JAVA_TOOL_OPTIONS"] == f"-Xmx64m {owned}"
    assert environment.vars["_JAVA_OPTIONS"] == owned
    assert "JDK_JAVA_OPTIONS" not in environment.vars
    deployment = environment.as_record()["deployment"]
    assert [deployment[name] for name in UNIKIX_TEMP_VARS] == [str(temp)] * 3
    # environment.yaml shows the owned options as they are and every other one redacted.
    assert deployment["JAVA_TOOL_OPTIONS"] == f"{unikix_env.REDACTED} {owned}"
    assert deployment["_JAVA_OPTIONS"] == owned


# JVM options whose values hold whitespace inside quotes, as a JVM reads them.
UNIKIX_QUOTED_JVM_OPTIONS = '-Xmx64m -Djava.io.tmpdir="/a directory/tmp" -Dkeep="x y"'
UNIKIX_UNMATCHED_JVM_OPTIONS = "-Xmx64m -Dx='open"


@pytest.mark.parametrize("name", ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"])
def test_unikix_load_reads_jvm_options_as_a_jvm_does(unikix_temp_root: Path, name: str) -> None:
    environment = _fake_unikix_environment(unikix_temp_root, (f"{name}='{UNIKIX_QUOTED_JVM_OPTIONS}'",))
    temp = environment.deployment_root / "tmp"
    owned = f"-Djava.io.tmpdir={temp} {UNIKIX_JVM_OWNED}"
    # The quoted tmpdir goes whole; every other option stays as written.
    assert environment.vars[name] == f'-Xmx64m -Dkeep="x y" {owned}'
    # Without a backslash, a JVM splits and unquotes options as a POSIX shell does.
    assert shlex.split(environment.vars[name]) == ["-Xmx64m", "-Dkeep=x y", *owned.split()]
    assert environment.vars["_JAVA_OPTIONS"] == owned
    again = unikix_env._contain_temp(dict(environment.vars), temp)
    assert again == environment.vars


@pytest.mark.parametrize("name", ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"])
def test_unikix_load_refuses_jvm_options_with_an_unmatched_quote(unikix_temp_root: Path, name: str) -> None:
    with pytest.raises(base.AdapterUnavailable) as caught:
        _fake_unikix_environment(unikix_temp_root, (f'{name}="{UNIKIX_UNMATCHED_JVM_OPTIONS}"',))
    env_file = os.path.abspath(unikix_temp_root / "carddemo.env")
    assert (caught.value.reason, caught.value.gap_id) == (
        f"{name} set by {env_file} has an unmatched quote",
        "GAP-MIS-009",
    )


# An operator's JVM property carrying a credential, and a token an operator's
# environment file prints while sourced, each assembled from parts so that no
# secret scanner reads them as one.
UNIKIX_SECRET_PROPERTY = "-Dservice." + "password=" + "private-value"
UNIKIX_SOURCING_TOKEN = "install-" + "token-" + "1234"
# The name and the value of UNIKIX_SECRET_PROPERTY, as a property listing
# shows them.
UNIKIX_SECRET_NAME, UNIKIX_SECRET_VALUE = UNIKIX_SECRET_PROPERTY.removeprefix("-D").split("=", 1)
# What env.redact is given: the owned options of a deployment under /work,
# beside the secret property, a heap size and a quoted property.
UNIKIX_REDACT_OWNED = f"-Djava.io.tmpdir=/work/unikix/case/tmp {UNIKIX_JVM_OWNED}"
UNIKIX_REDACT_OPTIONS = f'{UNIKIX_SECRET_PROPERTY} -Xmx64m -Dlabel="x y" {UNIKIX_REDACT_OWNED}'
UNIKIX_REDACT_VARS = {"JAVA_TOOL_OPTIONS": UNIKIX_REDACT_OPTIONS, "_JAVA_OPTIONS": UNIKIX_REDACT_OWNED}
# What a listing may show that no hidden option sets: the secret property's
# name inside a longer name (after a word character, "." or "-", or before
# ".old") with its value, and the name with a longer value.
UNIKIX_OTHER_PROPERTIES = (
    f"x{UNIKIX_SECRET_NAME}={UNIKIX_SECRET_VALUE}\n    app.{UNIKIX_SECRET_NAME} = {UNIKIX_SECRET_VALUE}\n"
    f"my-{UNIKIX_SECRET_NAME}={UNIKIX_SECRET_VALUE}\n    {UNIKIX_SECRET_NAME}.old = {UNIKIX_SECRET_VALUE}\n"
    f"{UNIKIX_SECRET_NAME}={UNIKIX_SECRET_VALUE}s\n"
)
# Output as commands print it and as env.redact leaves it: {options} stands
# for UNIKIX_REDACT_OPTIONS, {owned} for UNIKIX_REDACT_OWNED, {shown} for the
# options as redacted, {r} for env.REDACTED.
UNIKIX_REDACT_CASES = {
    "hotspot-echo": (
        "Picked up JAVA_TOOL_OPTIONS: {options}\nPicked up _JAVA_OPTIONS: {owned}\n",
        "Picked up JAVA_TOOL_OPTIONS: {shown}\nPicked up _JAVA_OPTIONS: {owned}\n",
    ),
    # A variable the environment does not hold, which the launcher reads.
    "launcher-note": (
        "NOTE: Picked up JDK_JAVA_OPTIONS: -Dlauncher.key=k1 {owned}\r\nopenjdk 17\n",
        "NOTE: Picked up JDK_JAVA_OPTIONS: {r} {owned}\r\nopenjdk 17\n",
    ),
    "set-x-trace": (
        "+ JAVA_TOOL_OPTIONS='{options}'\n+ java -version\n",
        "+ JAVA_TOOL_OPTIONS='{shown}'\n+ java -version\n",
    ),
    "env-listing": ("PATH=/usr/bin\nJAVA_TOOL_OPTIONS={options}\n", "PATH=/usr/bin\nJAVA_TOOL_OPTIONS={shown}\n"),
    # An option as a JVM reads it, its quotes removed.
    "decoded-option": ("property -Dlabel=x y set\n", "property {r} set\n"),
    # An echo cut inside a quote, as LOG_LIMIT_BYTES may cut it.
    "clipped-echo": ("Picked up JAVA_TOOL_OPTIONS: -Dcut='private", "Picked up JAVA_TOOL_OPTIONS: {r}"),
    "owned-only": ("Picked up _JAVA_OPTIONS: {owned}\n", "Picked up _JAVA_OPTIONS: {owned}\n"),
    "no-option": ("kixregion 12.0.0\n", "kixregion 12.0.0\n"),
    # Words an option only begins: not an option of the environment.
    "longer-words": ("-Xmx64mb -Dlabel=x yz\n", "-Xmx64mb -Dlabel=x yz\n"),
    # A hidden -D option as java -XshowSettings:properties lists it, and as a
    # properties listing does: its value hidden, its name kept.
    "property-listing": (
        f"    {UNIKIX_SECRET_NAME} = {UNIKIX_SECRET_VALUE}\n", f"    {UNIKIX_SECRET_NAME} = {{r}}\n"
    ),
    "properties-file": (f"{UNIKIX_SECRET_NAME}={UNIKIX_SECRET_VALUE}\n", f"{UNIKIX_SECRET_NAME}={{r}}\n"),
    "other-property": (UNIKIX_OTHER_PROPERTIES, UNIKIX_OTHER_PROPERTIES),
}


@pytest.mark.parametrize(("text", "expected"), list(UNIKIX_REDACT_CASES.values()), ids=list(UNIKIX_REDACT_CASES))
def test_unikix_redact_hides_every_jvm_option_but_the_owned_temporary_settings(text: str, expected: str) -> None:
    r = unikix_env.REDACTED
    values = {
        "options": UNIKIX_REDACT_OPTIONS, "owned": UNIKIX_REDACT_OWNED, "shown": f"{r} {r} {r} {UNIKIX_REDACT_OWNED}",
        "r": r,
    }
    redacted = unikix_env.redact(text.format(**values), UNIKIX_REDACT_VARS)
    assert redacted == expected.format(**values)
    assert UNIKIX_SECRET_PROPERTY not in redacted
    # Applying it again changes nothing.
    assert unikix_env.redact(redacted, UNIKIX_REDACT_VARS) == redacted


@pytest.mark.parametrize(
    ("value", "shown"),
    [
        pytest.param(UNIKIX_REDACT_OPTIONS, "{r} {r} {r} " + UNIKIX_REDACT_OWNED, id="operator-options"),
        pytest.param(f"  {UNIKIX_REDACT_OWNED}\t", UNIKIX_REDACT_OWNED, id="owned-only"),
        # Only the owned settings as written stand: a quoted one is not one of them.
        pytest.param('-Djava.io.tmpdir="/a b" -XX:+PerfDisableSharedMem', "{r} -XX:+PerfDisableSharedMem", id="quoted"),
        pytest.param(UNIKIX_UNMATCHED_JVM_OPTIONS, "{r}", id="unmatched-quote"),
        pytest.param(" \t", "", id="blank"),
    ],
)
def test_unikix_redact_jvm_options_shows_the_owned_temporary_settings_alone(value: str, shown: str) -> None:
    redacted = unikix_env.redact_jvm_options(value)
    assert redacted == shown.format(r=unikix_env.REDACTED)
    assert unikix_env.redact_jvm_options(redacted) == redacted


# A command that prints the JVM's echo of JAVA_TOOL_OPTIONS on stderr and the
# variable itself on stdout.
UNIKIX_ECHOES_JVM_OPTIONS = (
    "printf 'Picked up JAVA_TOOL_OPTIONS: %s\\n' \"$JAVA_TOOL_OPTIONS\" >&2; printf '%s\\n' \"$JAVA_TOOL_OPTIONS\""
)


def test_unikix_evidence_holds_no_operator_jvm_option_and_no_sourcing_output(
    unikix_temp_root: Path, evidence_tests_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    environment = _fake_unikix_environment(
        unikix_temp_root, (f"JAVA_TOOL_OPTIONS='{UNIKIX_SECRET_PROPERTY}'", f"echo {UNIKIX_SOURCING_TOKEN}")
    )
    owned = f"-Djava.io.tmpdir={environment.deployment_root / 'tmp'} {UNIKIX_JVM_OWNED}"
    shown = f"{unikix_env.REDACTED} {owned}"
    # Containment is unchanged: every command still gets the operator's option.
    assert environment.vars["JAVA_TOOL_OPTIONS"] == f"{UNIKIX_SECRET_PROPERTY} {owned}"
    # A registry file of its own, which environment.yaml hashes.
    commands_file = unikix_temp_root / "commands.yaml"
    commands_file.write_text(dump_yaml({"schema_version": 1, "entries": {"temp_case": {
        "action": "echo the JVM options", "command": UNIKIX_ECHOES_JVM_OPTIONS, "probe": "true", "timeout_s": 10,
        "gap": "GAP-MIS-003", "status": "unconfirmed", "source": "unit case", "notes": "unit case",
    }}}), encoding="utf-8")
    commands = unikix_env.CommandSet.load(commands_file)
    assert commands.run("temp_case", environment).stdout == f"{UNIKIX_SECRET_PROPERTY} {owned}\n"
    ran = commands.log[-1]
    assert (ran["stdout"], ran["stderr"]) == (f"{shown}\n", f"Picked up JAVA_TOOL_OPTIONS: {shown}\n")
    record = environment.as_record()
    assert (record["deployment"]["JAVA_TOOL_OPTIONS"], record["deployment"]["_JAVA_OPTIONS"]) == (shown, owned)
    assert record["sourcing_log"] == str(environment.log_dir / "sourcing.log")
    assert "sourcing_output" not in record
    # The evidence the adapter writes of them.
    adapter = UniKixAdapter(work_root=evidence_tests_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter.execution_id = EVIDENCE_EXECUTION_ID
    adapter._env, adapter._commands = environment, commands
    adapter._capture, adapter._capture_id = evidence.Capture(), EVIDENCE_SCENARIO
    monkeypatch.setattr(unikix_adapter.harness, "TESTS_ROOT", evidence_tests_root)
    written = _evidence_record()
    assert adapter.write_evidence(scenarios.get(EVIDENCE_SCENARIO), written) == written["evidence_path"]
    tree = _tree(_evidence_folder(evidence_tests_root))
    files = {name: data for name, data in tree.items() if isinstance(data, bytes)}
    assert {evidence.ENVIRONMENT_FILE, EVIDENCE_COMMANDS_LOG} <= set(files)
    texts = {
        "as_record": dump_yaml(record),
        "evidence log": unikix_env.CommandSet.render_evidence_log(commands.log),
        "in-memory log": dump_yaml(commands.log),
        "logs/commands.log": (environment.log_dir / "commands.log").read_text(encoding="utf-8"),
        **{name: data.decode("utf-8") for name, data in files.items()},
    }
    for name, text in texts.items():
        assert UNIKIX_SECRET_PROPERTY not in text and UNIKIX_SOURCING_TOKEN not in text, name
    assert shown in texts[EVIDENCE_COMMANDS_LOG] and shown in texts[evidence.ENVIRONMENT_FILE]
    # What the file printed stays in memory and in the work root's sourcing.log.
    assert UNIKIX_SOURCING_TOKEN in environment.sourcing_output
    sourcing = (environment.log_dir / "sourcing.log").read_text(encoding="utf-8")
    assert sourcing.startswith(f"--- {environment.env_file} ---\n") and f"{UNIKIX_SOURCING_TOKEN}\n" in sourcing


def test_unikix_build_log_shows_no_operator_jvm_option(unikix_temp_root: Path) -> None:
    environment = _fake_unikix_environment(unikix_temp_root, (f"JAVA_TOOL_OPTIONS='{UNIKIX_SECRET_PROPERTY}'",))
    shown = f"{unikix_env.REDACTED} -Djava.io.tmpdir={environment.deployment_root / 'tmp'} {UNIKIX_JVM_OWNED}"
    commands = _one_command(unikix_temp_root, UNIKIX_ECHOES_JVM_OPTIONS, "true")
    log = environment.log_dir / "COSGN00C.log"
    deploy._run_build(commands, environment, "temp_case", log, deploy._Progress(lambda: 60.0), member="COSGN00C")
    assert log.read_text(encoding="utf-8") == (
        "--- temp_case exit status 0 ---\n"
        f"--- stdout ---\n{shown}\n"
        f"--- stderr ---\nPicked up JAVA_TOOL_OPTIONS: {shown}\n"
    )


# A link planted, before load, where a directory of the deployment goes: the
# temporary directory, HOME and the log folder point out of the work root;
# the root and its parent at a folder beside them inside it, which passes the
# work-root check.
UNIKIX_PLANTED_LINKS = [
    pytest.param(f"unikix/{UNIKIX_TEMP_EXECUTION}/tmp", False, id="tmp"),
    pytest.param(f"unikix/{UNIKIX_TEMP_EXECUTION}/home", False, id="home"),
    pytest.param(f"unikix/{UNIKIX_TEMP_EXECUTION}/logs", False, id="logs"),
    pytest.param(f"unikix/{UNIKIX_TEMP_EXECUTION}", True, id="root"),
    pytest.param("unikix", True, id="root-parent"),
]


@pytest.mark.parametrize(("link", "inside_work"), UNIKIX_PLANTED_LINKS)
def test_unikix_load_refuses_a_planted_link_before_sourcing_the_file(
    unikix_temp_root: Path, link: str, inside_work: bool
) -> None:
    work = unikix_temp_root / "work"
    target = work / "elsewhere" if inside_work else _outside(unikix_temp_root)
    target.mkdir(parents=True, exist_ok=True)
    planted = work / link
    planted.parent.mkdir(parents=True, exist_ok=True)
    planted.symlink_to(target, target_is_directory=True)
    with pytest.raises(HarnessError) as caught:
        _fake_unikix_environment(
            unikix_temp_root, ("MADE=$(mktemp)", 'printf "%s\\n" "$MADE" > "$HOME/made-path"')
        )
    assert str(caught.value) == (
        f"deployment directory {planted} is a symbolic link; remove it or choose another work root"
    )
    # The file never ran, so nothing was written through the link.
    assert list(target.iterdir()) == []


# A deployment root whose path holds ':', taken from the work root or from the
# execution id.
UNIKIX_COLON_ROOTS = [
    pytest.param("wo:rk", UNIKIX_TEMP_EXECUTION, id="work-root"),
    pytest.param("work", "unikix:case", id="execution-id"),
]


@pytest.mark.parametrize(("work_name", "execution_id"), UNIKIX_COLON_ROOTS)
def test_unikix_load_refuses_a_deployment_root_holding_a_colon_before_creating_anything(
    unikix_temp_root: Path, work_name: str, execution_id: str
) -> None:
    """COBCPY, PATH and LD_LIBRARY_PATH name the deployment's directories and
    are split at ':', so load refuses a deployment root whose path holds one,
    naming it, before it creates or sources anything, under any session work
    root; an unset CARDDEMO_UNIKIX_ENV is still GAP-MIS-009 first."""
    env_file = _fake_unikix_env_file(unikix_temp_root)
    work = unikix_temp_root / work_name
    root = work / "unikix" / execution_id
    with pytest.raises(base.AdapterUnavailable) as unset:
        unikix_env.UniKixEnvironment.load(work, root, environ={"PATH": os.environ.get("PATH", os.defpath)})
    assert (unset.value.reason, unset.value.gap_id) == (
        f"{unikix_env.ENV_VARIABLE} not set: no UniKix installation is named for this run",
        "GAP-MIS-009",
    )
    with pytest.raises(HarnessError) as refused:
        unikix_env.UniKixEnvironment.load(work, root, environ=_fake_unikix_environ(env_file))
    assert str(refused.value) == f"deployment root {os.path.abspath(root)} {PATH_LIST_UNSAFE}"
    assert not os.path.lexists(work), "a refused deployment root was created"


# An existing installation directory whose path holds ':'. COBCPY names
# $UNIKIX/copy, and the environment file puts $UNIKIX/bin and $VCOBOL/bin on
# PATH and $VCOBOL/lib on LD_LIBRARY_PATH, so those two are refused; no search
# path names INSTEBM, KIXLICDIR or JAVA_HOME, so they are kept as they are.
UNIKIX_INSTALLATION_COLON_REFUSAL = (
    "{name} {path} holds ':', at which COBCPY, PATH and LD_LIBRARY_PATH are split, so the search "
    "paths naming its directories would break; choose an installation path without ':'"
)
UNIKIX_INSTALLATION_COLON_CASES = [
    pytest.param("UNIKIX", True, id="unikix-refused"),
    pytest.param("VCOBOL", True, id="vcobol-refused"),
    pytest.param("INSTEBM", False, id="instebm-kept"),
    pytest.param("KIXLICDIR", False, id="kixlicdir-kept"),
    pytest.param("JAVA_HOME", False, id="java-home-kept"),
]


@pytest.mark.parametrize(("name", "refused"), UNIKIX_INSTALLATION_COLON_CASES)
def test_unikix_load_refuses_only_a_search_path_installation_directory_holding_a_colon(
    unikix_temp_root: Path, name: str, refused: bool
) -> None:
    """A UNIKIX or VCOBOL whose path holds ':' would split COBCPY, PATH or
    LD_LIBRARY_PATH, so vcc and kixclt would fail under GAP-MIS-008; load
    refuses it as an unusable installation (GAP-MIS-009), naming it. The same
    ':' in INSTEBM, KIXLICDIR or JAVA_HOME blocks nothing."""
    installed = unikix_temp_root / "instal:lation" / name.lower()
    installed.mkdir(parents=True)
    line = f"{name}={shlex.quote(str(installed))}"
    if refused:
        with pytest.raises(base.AdapterUnavailable) as caught:
            _fake_unikix_environment(unikix_temp_root, (line,))
        assert (caught.value.reason, caught.value.gap_id) == (
            UNIKIX_INSTALLATION_COLON_REFUSAL.format(name=name, path=installed),
            "GAP-MIS-009",
        )
    else:
        environment = _fake_unikix_environment(unikix_temp_root, (line,))
        assert environment.vars[name] == str(installed)


# Lines that try to move a temporary-file setting while the environment file is
# sourced. The held case follows one with what the file then sees, a temporary
# file it makes and an ordinary last assignment.
UNIKIX_TEMP_CHANGES = {
    "assign-tmpdir": ("TMPDIR=/var/tmp", "TMPDIR: readonly variable"),
    "export-tmp": ("export TMP=/tmp", "TMP: readonly variable"),
    "unset-temp": ("unset TEMP", "unset: TEMP: cannot unset: readonly variable"),
    "assign-java-options": ("_JAVA_OPTIONS=-Djava.io.tmpdir=/tmp", "_JAVA_OPTIONS: readonly variable"),
}
UNIKIX_HELD_TAIL = ('SEEN="$TMPDIR:$TMP:$TEMP:$_JAVA_OPTIONS"', "MADE=$(mktemp)", "SOURCED=1")


@pytest.mark.parametrize(
    ("change", "message"), list(UNIKIX_TEMP_CHANGES.values()), ids=list(UNIKIX_TEMP_CHANGES)
)
def test_unikix_environment_file_cannot_move_temporary_files_while_it_is_sourced(
    unikix_temp_root: Path, change: str, message: str
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root, (change, *UNIKIX_HELD_TAIL))
    temp = environment.deployment_root / "tmp"
    owned = f"-Djava.io.tmpdir={temp} {UNIKIX_JVM_OWNED}"
    assert environment.vars["SEEN"] == ":".join([str(temp)] * 3 + [owned])
    made = Path(environment.vars["MADE"])
    assert made.parent == temp and made.is_file()
    assert environment.vars["SOURCED"] == "1"
    # The change's own line, the last that holds it (the helper sets TMPDIR too).
    lines = environment.env_file.read_text(encoding="utf-8").splitlines()
    line = len(lines) - lines[::-1].index(change)
    assert f"{environment.env_file}: line {line}: {message}" in environment.sourcing_output


@pytest.mark.parametrize(
    "change", [change for change, _message in UNIKIX_TEMP_CHANGES.values()], ids=list(UNIKIX_TEMP_CHANGES)
)
def test_unikix_environment_file_ending_in_a_temporary_file_change_is_unavailable(
    unikix_temp_root: Path, change: str
) -> None:
    with pytest.raises(base.AdapterUnavailable) as caught:
        _fake_unikix_environment(unikix_temp_root, (change,))
    env_file = os.path.abspath(unikix_temp_root / "carddemo.env")
    assert (caught.value.reason, caught.value.gap_id) == (f"sourcing {env_file} exited 1", "GAP-MIS-009")


def test_unikix_commands_and_probes_write_temporary_files_in_the_deployment_tmp(
    unikix_temp_root: Path,
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    temp = environment.deployment_root / "tmp"
    # vars changed after load: every process starts contained all the same.
    environment.vars.update(
        TMPDIR="/tmp", TEMP="/tmp", JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp", _JAVA_OPTIONS="-Xint"
    )
    commands = _one_command(
        unikix_temp_root,
        'printf "%s\\n" "$TMPDIR" "$TMP" "$TEMP" "$JAVA_TOOL_OPTIONS" "$_JAVA_OPTIONS"; mktemp',
        'printf %s "$TMPDIR"',
    )
    *settings, made = commands.run("temp_case", environment).stdout.splitlines()
    owned = f"-Djava.io.tmpdir={temp} {UNIKIX_JVM_OWNED}"
    assert settings == [str(temp)] * 3 + [owned, owned]
    assert Path(made).parent == temp and Path(made).is_file()
    assert [record["stdout"] for record in commands.log if record["kind"] == "probe"] == [str(temp)]


# Commands, and a script one sources, that move TMPDIR before making a
# temporary file; each runs as both the probe and the command.
UNIKIX_COMMAND_MOVES = {
    "export": "export TMPDIR=OUTSIDE; mktemp",
    "assignment-line": "TMPDIR=OUTSIDE\nmktemp",
    "prefix-assignment": "TMPDIR=OUTSIDE mktemp",
    "sourced-script": '. "$BASE_DIR/moves-tmpdir"; mktemp',
}


@pytest.mark.parametrize("command", list(UNIKIX_COMMAND_MOVES.values()), ids=list(UNIKIX_COMMAND_MOVES))
def test_unikix_commands_and_probes_cannot_move_their_temporary_files(
    unikix_temp_root: Path, command: str
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    temp = environment.deployment_root / "tmp"
    outside = _outside(unikix_temp_root)
    (environment.deployment_root / "moves-tmpdir").write_text(f"export TMPDIR={outside}\n", encoding="utf-8")
    command = command.replace("OUTSIDE", str(outside))
    commands = _one_command(unikix_temp_root, command, command)
    result = commands.run("temp_case", environment)
    probe, run = commands.log
    made = [Path(probe["stdout"].strip()), Path(result.stdout.strip())]
    assert sorted(temp.iterdir()) == sorted(made) and list(outside.iterdir()) == []
    assert all("TMPDIR: readonly variable" in text for text in (probe["stderr"], result.stderr))
    # The log holds what was rendered, never the hold that ran before it.
    assert (probe["command"], run["command"]) == (command, command)


# Runtime-script lines that move a held setting and then make a temporary
# file, the line bash refuses first, and its message for it.
UNIKIX_SCRIPT_MOVES = {
    "export-tmpdir": (
        ("export TMPDIR=OUTSIDE", "MADE=$(mktemp)"), "export TMPDIR=OUTSIDE", "TMPDIR: readonly variable"
    ),
    "restore-tmpdir": (
        ('SAVED_TMPDIR="$TMPDIR"', "export TMPDIR=OUTSIDE", "MADE=$(mktemp)", 'TMPDIR="$SAVED_TMPDIR"'),
        "export TMPDIR=OUTSIDE",
        "TMPDIR: readonly variable",
    ),
    "unset-temp": (
        ("unset TMP TEMP", "MADE=$(mktemp)"), "unset TMP TEMP", "unset: TMP: cannot unset: readonly variable"
    ),
    "assign-java-options": (
        ("_JAVA_OPTIONS=-Djava.io.tmpdir=OUTSIDE", "MADE=$(mktemp)"),
        "_JAVA_OPTIONS=-Djava.io.tmpdir=OUTSIDE",
        "_JAVA_OPTIONS: readonly variable",
    ),
}


def _script_moves(case: str, outside: Path, tail: tuple[str, ...] = ()) -> tuple[str, str, str]:
    """The ``UNIKIX_SCRIPT_MOVES`` ``case`` as script text ending in
    ``tail``, its refused line and bash's message, ``OUTSIDE`` replaced."""
    lines, refused, message = UNIKIX_SCRIPT_MOVES[case]
    text = "\n".join(line.replace("OUTSIDE", str(outside)) for line in (*lines, *tail))
    return text, refused.replace("OUTSIDE", str(outside)), message


@pytest.mark.parametrize("script", ["userenv", "batchenv"])
@pytest.mark.parametrize("case", list(UNIKIX_SCRIPT_MOVES))
def test_unikix_runtime_scripts_cannot_move_held_temporary_settings(
    unikix_temp_root: Path, script: str, case: str
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    temp = environment.deployment_root / "tmp"
    owned = f"-Djava.io.tmpdir={temp} {UNIKIX_JVM_OWNED}"
    outside = _outside(unikix_temp_root)
    text, refused, message = _script_moves(
        case, outside, ('SEEN="$TMPDIR:$TMP:$TEMP:$_JAVA_OPTIONS"', "SOURCED=1")
    )
    paths = _fake_runtime_scripts(environment, **{script: text})
    assert environment.source_runtime_scripts(unikix_env.CommandSet.load()) == {
        "userenv": "sourced",
        "batchenv": "sourced",
    }
    made = Path(environment.vars["MADE"])
    assert sorted(temp.iterdir()) == [made] and list(outside.iterdir()) == []
    # What the script itself saw after its change, before anything re-asserted it.
    assert environment.vars["SEEN"] == ":".join([str(temp)] * 3 + [owned])
    assert environment.vars["SOURCED"] == "1"
    lines = paths[script].read_text(encoding="utf-8").splitlines()
    assert f"{paths[script]}: line {lines.index(refused) + 1}: {message}" in environment.sourcing_output


@pytest.mark.parametrize(
    ("script", "reason", "gap"),
    [
        pytest.param("userenv", "sourcing {path} exited 1", "GAP-MIS-003", id="userenv"),
        pytest.param("batchenv", "bpe_env exited 1", "GAP-MIS-006", id="batchenv"),
    ],
)
def test_unikix_runtime_script_ending_in_a_held_setting_change_is_unavailable(
    unikix_temp_root: Path, script: str, reason: str, gap: str
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    loaded = dict(environment.vars)
    temp = environment.deployment_root / "tmp"
    outside = _outside(unikix_temp_root)
    # The restore, its last line, is refused too, which fails its ".".
    text, _refused, _message = _script_moves("restore-tmpdir", outside)
    paths = _fake_runtime_scripts(environment, **{script: text})
    with pytest.raises(base.AdapterUnavailable) as caught:
        environment.source_runtime_scripts(unikix_env.CommandSet.load())
    assert (caught.value.reason, caught.value.gap_id) == (reason.format(path=paths[script]), gap)
    assert environment.vars == loaded and environment.generation == 0
    # Its temporary file was made in tmp/ all the same.
    assert len(list(temp.iterdir())) == 1 and list(outside.iterdir()) == []


@pytest.mark.parametrize(
    ("script", "line", "gap", "refusal"),
    [
        pytest.param(
            "userenv", 'JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djava.io.tmpdir=/tmp"', "GAP-MIS-003",
            "moves the JVM's temporary files outside the deployment root: "
            "JAVA_TOOL_OPTIONS sets -Djava.io.tmpdir=/tmp last",
            id="userenv-jvm-tmpdir",
        ),
        pytest.param(
            "batchenv", 'JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djava.io.tmpdir=/tmp"', "GAP-MIS-006",
            "moves the JVM's temporary files outside the deployment root: "
            "JAVA_TOOL_OPTIONS sets -Djava.io.tmpdir=/tmp last",
            id="batchenv-jvm-tmpdir",
        ),
        pytest.param(
            "userenv", "JAVA_TOOL_OPTIONS=\"$JAVA_TOOL_OPTIONS -Djava.io.tmpdir='/outside dir'\"",
            "GAP-MIS-003",
            "moves the JVM's temporary files outside the deployment root: "
            "JAVA_TOOL_OPTIONS sets -Djava.io.tmpdir=/outside dir last",
            id="userenv-jvm-tmpdir-quoted",
        ),
        pytest.param(
            "batchenv", "JDK_JAVA_OPTIONS=-Djava.io.tmpdir=/var/tmp", "GAP-MIS-006",
            "moves the JVM's temporary files outside the deployment root: "
            "JDK_JAVA_OPTIONS sets -Djava.io.tmpdir=/var/tmp last",
            id="batchenv-jdk-java-options",
        ),
        pytest.param(
            "userenv", 'JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:-PerfDisableSharedMem"',
            "GAP-MIS-003",
            "moves the JVM's temporary files outside the deployment root: JAVA_TOOL_OPTIONS sets "
            "-XX:-PerfDisableSharedMem last, so HotSpot writes /tmp/hsperfdata_<user>",
            id="userenv-hsperfdata",
        ),
        pytest.param(
            "batchenv", f'JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS {UNIKIX_UNMATCHED_JVM_OPTIONS}"',
            "GAP-MIS-006",
            "leaves JAVA_TOOL_OPTIONS with an unmatched quote",
            id="batchenv-unmatched-quote",
        ),
        # A deployment path moved inside the root, to a path holding ':', at
        # which the search paths naming it are split; {root} is the root.
        pytest.param(
            "userenv", 'MIGAPP_DIR="$BASE_DIR/staging/mi:grated"', "GAP-MIS-003",
            "moves MIGAPP_DIR to {root}/staging/mi:grated, which holds ':', at which COBCPY, PATH and "
            "LD_LIBRARY_PATH are split",
            id="userenv-migapp-colon",
        ),
        pytest.param(
            "batchenv", 'KIXSYS="$BASE_DIR/carddemo/kix:sys"', "GAP-MIS-006",
            "moves KIXSYS to {root}/carddemo/kix:sys, which holds ':', at which COBCPY, PATH and "
            "LD_LIBRARY_PATH are split",
            id="batchenv-kixsys-colon",
        ),
        # An installation directory that COBCPY ($UNIKIX/copy), PATH ($UNIKIX/bin,
        # $VCOBOL/bin) and LD_LIBRARY_PATH ($VCOBOL/lib) name, moved to a folder
        # the script makes, as its last command, so its path holding ':' is all
        # that is refused; {root} is the root.
        pytest.param(
            "userenv", 'UNIKIX="$BASE_DIR/uni:kix"; mkdir "$UNIKIX"', "GAP-MIS-003",
            "moves UNIKIX to {root}/uni:kix, which holds ':', at which COBCPY, PATH and "
            "LD_LIBRARY_PATH are split",
            id="userenv-unikix-colon",
        ),
        pytest.param(
            "batchenv", 'VCOBOL="$BASE_DIR/vco:bol"; mkdir "$VCOBOL"', "GAP-MIS-006",
            "moves VCOBOL to {root}/vco:bol, which holds ':', at which COBCPY, PATH and "
            "LD_LIBRARY_PATH are split",
            id="batchenv-vcobol-colon",
        ),
    ],
)
def test_unikix_refuses_a_runtime_script_that_moves_temporary_files_out(
    unikix_temp_root: Path, script: str, line: str, gap: str, refusal: str
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    loaded = dict(environment.vars)
    paths = _fake_runtime_scripts(environment, **{script: line})
    with pytest.raises(base.AdapterUnavailable) as caught:
        environment.source_runtime_scripts(unikix_env.CommandSet.load())
    reason = f"{paths[script]} {refusal.format(root=environment.deployment_root)}"
    assert (caught.value.reason, caught.value.gap_id) == (reason, gap)
    assert environment.vars == loaded and environment.generation == 0


# A runtime script cannot leave these, which its shell holds read-only; the
# check of what it left refuses them still.
@pytest.mark.parametrize(
    ("changes", "refusal"),
    [
        pytest.param({"TMPDIR": "/tmp"}, "moves TMPDIR outside the deployment root: /tmp", id="tmpdir"),
        pytest.param({"TEMP": None}, "moves TEMP outside the deployment root: (unset)", id="temp-unset"),
        pytest.param(
            {"_JAVA_OPTIONS": f"-Djava.io.tmpdir=/var/tmp {UNIKIX_JVM_OWNED}"},
            "moves the JVM's temporary files outside the deployment root: "
            "_JAVA_OPTIONS sets -Djava.io.tmpdir=/var/tmp last",
            id="java-options",
        ),
        pytest.param(
            {"_JAVA_OPTIONS": None},
            "moves the JVM's temporary files outside the deployment root: "
            "_JAVA_OPTIONS sets no -Djava.io.tmpdir, so java.io.tmpdir is /tmp",
            id="java-options-unset",
        ),
    ],
)
def test_unikix_runtime_snapshot_check_refuses_held_settings_moved_out(
    unikix_temp_root: Path, changes: dict[str, str | None], refusal: str
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    assert environment._runtime_escape(environment.vars) == ""
    snapshot = dict(environment.vars)
    for name, value in changes.items():
        if value is None:
            del snapshot[name]
        else:
            snapshot[name] = value
    assert environment._runtime_escape(snapshot) == refusal


@pytest.mark.parametrize("change", ["tmp-link", "logs-link", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"])
def test_unikix_process_start_refuses_what_changed_since_load(unikix_temp_root: Path, change: str) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    temp = environment.deployment_root / "tmp"
    outside = _outside(unikix_temp_root)
    if change.endswith("-link"):
        # The temporary directory or the log folder, which holds sourcing.log
        # since load, swapped for a link out.
        linked = environment.deployment_root / change.removesuffix("-link")
        shutil.rmtree(linked)
        linked.symlink_to(outside, target_is_directory=True)
        message = f"deployment directory {linked} is a symbolic link; remove it or choose another work root"
    else:
        environment.vars[change] = UNIKIX_UNMATCHED_JVM_OPTIONS
        message = f"UniKix variable {change} has an unmatched quote"
    commands = _one_command(unikix_temp_root, "mktemp", "mktemp")
    with pytest.raises(HarnessError) as caught:
        commands.run("temp_case", environment)
    assert str(caught.value) == message
    # Nothing started: no temporary file anywhere, nothing logged.
    assert commands.log == [] and list(temp.iterdir()) == [] and list(outside.iterdir()) == []


def test_unikix_command_log_is_never_appended_through_a_link(unikix_temp_root: Path) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    target = _outside(unikix_temp_root) / "commands.log"
    target.write_text("", encoding="utf-8")
    log = environment.log_dir / "commands.log"
    log.symlink_to(target)
    commands = _one_command(unikix_temp_root, "true", "true")
    with pytest.raises(HarnessError) as caught:
        commands.run("temp_case", environment)
    assert str(caught.value) == (
        f"the log file {log} is a symbolic link; remove it or choose another work root"
    )
    # The probe ran; its entry is kept in memory alone and nothing reached the target.
    assert [(record["kind"], record["id"]) for record in commands.log] == [("probe", "temp_case")]
    assert target.read_text(encoding="utf-8") == "" and log.is_symlink()



def test_unikix_sources_batchenv_by_running_the_bpe_env_entry(unikix_temp_root: Path) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    root = environment.deployment_root
    paths = _fake_runtime_scripts(environment)
    commands = _bpe_env_commands(unikix_temp_root, command=UNIKIX_BPE_ENV_OVERRIDE)
    assert environment.source_runtime_scripts(commands) == {
        "userenv": "sourced",
        "batchenv": "sourced",
    }
    # The override ran, sourcing batchenv with its argument after userenv.
    assert environment.vars["CARDDEMO_BPE_OVERRIDE"] == "1"
    assert environment.vars["BATCHENV_ARGS"] == "sys1"
    assert environment.vars["BATCHENV_SAW_USERENV"] == environment.vars["USERENV_SEEN"] == "1"
    assert environment.mbmsub == str(root / "bpenode" / "submit")
    assert environment.vars["TMPDIR"] == str(root / "tmp") and environment.generation == 1
    assert "batchenv sourced" in environment.sourcing_output
    records = [record for record in commands.log if record["id"] == "bpe_env"]
    assert [(record["kind"], record["outcome"], record["rc"], record["command"]) for record in records] == [
        ("probe", "completed", 0, 'test -r "$EBMHOME/batchenv"'),
        ("run", "completed", 0, UNIKIX_BPE_ENV_OVERRIDE),
    ]
    # The run's stdout is the exported environment: never logged.
    assert records[1]["stdout"] == "" and "batchenv sourced" in records[1]["stderr"]
    logged = (environment.log_dir / "commands.log").read_text(encoding="utf-8")
    assert "BATCHENV_ARGS" not in logged and str(root / "bpenode" / "submit") not in logged
    # What batchenv printed is a sourcing entry's: the evidence copy withholds it.
    assert records[1]["sourcing"] is True and "sourcing" not in records[0]
    shown = list(yaml.safe_load_all(unikix_env.CommandSet.render_evidence_log(records)))
    assert [(entry["stderr"], entry.get("withheld")) for entry in shown] == [
        (records[0]["stderr"], None),
        ("", ["stderr"]),
    ]
    assert "batchenv sourced" in records[1]["stderr"]
    # It goes to the work root's sourcing.log too.
    sourcing = (environment.log_dir / "sourcing.log").read_text(encoding="utf-8")
    assert f"--- {paths['batchenv']} ---\nbatchenv sourced\n" in sourcing


@pytest.mark.parametrize(
    ("changes", "reason"),
    [
        pytest.param({"command": None}, "no command is known (command: null)", id="command-null"),
        pytest.param({"probe": "false"}, "probe `false` exited 1", id="probe-failed"),
    ],
)
def test_unikix_leaves_batchenv_unavailable_when_bpe_env_is(
    unikix_temp_root: Path, changes: dict[str, object], reason: str
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    _fake_runtime_scripts(environment)
    commands = _bpe_env_commands(unikix_temp_root, **changes)
    assert environment.source_runtime_scripts(commands) == {
        "userenv": "sourced",
        "batchenv": "unavailable",
    }
    assert environment.vars["USERENV_SEEN"] == "1" and "BATCHENV_ARGS" not in environment.vars
    assert "MBMSUB" not in environment.vars and environment.mbmsub is None
    refused = [record for record in commands.log if record["id"] == "bpe_env" and record["kind"] == "run"]
    assert [(record["outcome"], record["reason"]) for record in refused] == [("refused", reason)]


@pytest.mark.parametrize(
    ("changes", "reason"),
    [
        pytest.param({"command": "sleep 30", "timeout_s": 1}, "bpe_env exceeded 1s", id="own-timeout"),
        pytest.param(
            {"command": '. "$EBMHOME/batchenv" sys1 && exit 3'}, "bpe_env exited 3", id="non-zero-exit"
        ),
        pytest.param({"command": "exit 0"}, "bpe_env exited 0", id="shell-ended-first"),
    ],
)
def test_unikix_blocks_the_deployment_when_bpe_env_fails(
    unikix_temp_root: Path, changes: dict[str, object], reason: str
) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    loaded = dict(environment.vars)
    _fake_runtime_scripts(environment)
    commands = _bpe_env_commands(unikix_temp_root, **changes)
    with pytest.raises(base.AdapterUnavailable) as caught:
        environment.source_runtime_scripts(commands)
    assert (caught.value.reason, caught.value.gap_id) == (reason, "GAP-MIS-006")
    assert environment.vars == loaded and environment.generation == 0


def test_unikix_bpe_env_stopped_at_the_scenario_deadline_is_a_timeout(unikix_temp_root: Path) -> None:
    environment = _fake_unikix_environment(unikix_temp_root)
    _fake_runtime_scripts(environment)
    commands = _bpe_env_commands(unikix_temp_root, command="sleep 30")
    with pytest.raises(base.ScenarioTimeout, match="bpe_env was stopped at the scenario deadline"):
        environment.source_runtime_scripts(commands, deadline_left=1.0)
    assert environment.generation == 0
    assert [record["outcome"] for record in commands.log if record["kind"] == "run"] == ["deadline"]


# deploy.deploy on the fake installation, the committed zip extracted into the
# case's work root: the region entries stand in for kixregion, whose
# createScript writes a userenv that moves the JVM's tmpdir out, and for
# kixinstall2, which would leave a marker. Every probe is true.
UNIKIX_REGION_STAND_INS = {
    "region_create": 'mkdir -p "$KIXSYS/bin"',
    "region_create_script": (
        "printf '%s\\n' JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/var/tmp > \"$KIXSYS/bin/userenv\""
    ),
    "region_install": 'touch "$KIXSYS/installed"',
}
# A createScript whose userenv is a symbolic link to an innocuous file in the
# work root, outside the deployment root ($BASE_DIR).
UNIKIX_USERENV_OUTSIDE = "userenv-outside"
UNIKIX_USERENV_LINKED_OUT = (
    f'outside="$BASE_DIR/../../{UNIKIX_USERENV_OUTSIDE}"; '
    "printf '%s\\n' USERENV_SEEN=1 > \"$outside\" && ln -s \"$outside\" \"$KIXSYS/bin/userenv\""
)


@pytest.fixture
def unikix_deploy_root(unikix_temp_root: Path) -> Iterator[Path]:
    """unikix_temp_root, whose read-only zip extraction is made writable
    afterwards, so that its removal succeeds for a user other than root."""
    yield unikix_temp_root
    for directory, _subdirectories, _files in os.walk(unikix_temp_root / "work" / deploy.ZIP_DIR_NAME):
        os.chmod(directory, 0o700)


@pytest.mark.parametrize(
    ("create_script", "reason", "generation"),
    [
        pytest.param(
            UNIKIX_REGION_STAND_INS["region_create_script"],
            "{userenv} moves the JVM's temporary files outside the deployment root: "
            "JAVA_TOOL_OPTIONS sets -Djava.io.tmpdir=/var/tmp last",
            0,
            id="moves-jvm-tmpdir-out",
        ),
        # Neither sourced nor checked by env.py, which completes a generation.
        pytest.param(
            UNIKIX_USERENV_LINKED_OUT,
            "{userenv} resolves outside the deployment root {root}; "
            "region_install would source it unchecked",
            1,
            id="resolves-outside-root",
        ),
    ],
)
def test_unikix_deploy_refuses_userenv_before_region_install_sources_it(
    unikix_deploy_root: Path, create_script: str, reason: str, generation: int
) -> None:
    environment = _fake_unikix_environment(unikix_deploy_root)
    # The work root must pass extract_zip's ownership check whatever the umask.
    environment.work_root.chmod(0o700)
    document = yaml.safe_load(unikix_env.COMMANDS_FILE.read_text(encoding="utf-8"))
    stand_ins = {**UNIKIX_REGION_STAND_INS, "region_create_script": create_script}
    for entry_id, command in stand_ins.items():
        document["entries"][entry_id].update(command=command, probe="true")
    path = unikix_deploy_root / "commands.yaml"
    path.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
    commands = unikix_env.CommandSet.load(path)
    with pytest.raises(base.AdapterUnavailable) as caught:
        deploy.deploy(environment, commands, repo_root=Path(_TESTS_ROOT).parent)
    kixsys = environment.path("KIXSYS")
    userenv = kixsys / "bin" / "userenv"
    assert (caught.value.reason, caught.value.gap_id) == (
        reason.format(userenv=userenv, root=environment.deployment_root),
        "GAP-MIS-003",
    )
    if create_script == UNIKIX_USERENV_LINKED_OUT:
        outside = environment.work_root / UNIKIX_USERENV_OUTSIDE
        assert userenv.is_symlink() and userenv.resolve() == outside.resolve()
        assert outside.read_text(encoding="utf-8") == "USERENV_SEEN=1\n"
    assert (environment.work_root / deploy.ZIP_DIR_NAME / deploy.APP_DIR_NAME).is_dir()
    assert not (kixsys / "installed").exists()
    # Nothing ran after region_create_script, region_install least of all.
    ran = [
        ("probe", "region_create"), ("run", "region_create"),
        ("probe", "region_create_script"), ("run", "region_create_script"),
    ]
    assert [(record["kind"], record["id"]) for record in commands.log] == ran
    logged = (environment.log_dir / "commands.log").read_text(encoding="utf-8")
    assert [(record["kind"], record["id"]) for record in yaml.safe_load_all(logged)] == ran
    # Nothing userenv sets was merged.
    assert environment.generation == generation and "USERENV_SEEN" not in environment.vars
    assert environment.vars["TMPDIR"] == str(environment.temp_dir)


# deploy.extract_zip on a fake repository root whose zip is crafted with
# zipfile, under bounds lowered so the zips stay tiny. UNIKIX_ZIP_AT_BOUNDS
# meets the entry, member and total bounds exactly: 8 entries, and files to
# extract holding 128 bytes, none over 64. Its __MACOSX/ and .DS_Store
# entries count as entries, while their 128 bytes, never extracted, count
# toward no size.
UNIKIX_ZIP_BOUNDS = {
    "_ZIP_MAX_ARCHIVE_BYTES": 2048,
    "_ZIP_MAX_MEMBER_BYTES": 64,
    "_ZIP_MAX_TOTAL_BYTES": 128,
    "_ZIP_MAX_ENTRIES": 8,
}
UNIKIX_ZIP_AT_BOUNDS = {
    "migrated_app/": b"",
    "migrated_app/cbl/": b"",
    "migrated_app/cbl/A.cbl": b"A" * 64,
    "migrated_app/cbl/B.cbl": b"B" * 64,
    "migrated_app/cbl/C.cbl": b"",
    "migrated_app/.DS_Store": b"D" * 64,
    "__MACOSX/": b"",
    "__MACOSX/migrated_app/._A.cbl": b"M" * 64,
}
UNIKIX_ZIP_EXTRACTED = [
    "migrated_app", "migrated_app/cbl",
    "migrated_app/cbl/A.cbl", "migrated_app/cbl/B.cbl", "migrated_app/cbl/C.cbl",
]
# What a reuse compares at a time, so a file of 64 bytes takes four blocks.
UNIKIX_ZIP_COMPARE_BLOCK = 16


def _crafted_unikix_zip(repo: Path, members: Mapping[str, bytes], comment: bytes = b"") -> Path:
    """Write ``members``, in order, with ``comment`` as the UniKix zip of the
    fake repository root ``repo``; return the zip's path."""
    path = deploy.zip_path(repo)
    path.parent.mkdir(parents=True)
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in members.items():
            archive.writestr(name, data)
        archive.comment = comment
    return path


def _bounded_work_root(case_root: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """``<case_root>/work``, made 0700 so that it passes extract_zip's
    ownership check whatever the umask, with deploy's zip bounds lowered to
    UNIKIX_ZIP_BOUNDS. Under a session work root whose path holds
    whitespace, a quote or ':', which extract_zip refuses as a work root,
    the case is skipped for that reason."""
    work = case_root / "work"
    refusal = _unikix_path_refusal(os.path.abspath(work))
    if refusal is not None:
        pytest.skip(UNIKIX_UNSAFE_ROOT_SKIP if refusal == JVM_UNSAFE_PATH else UNIKIX_COLON_ROOT_SKIP)
    work.mkdir(mode=0o700)
    work.chmod(0o700)
    for name, value in UNIKIX_ZIP_BOUNDS.items():
        monkeypatch.setattr(deploy, name, value)
    return work


@pytest.mark.parametrize(
    ("members", "comment", "refusal"),
    [
        pytest.param(
            UNIKIX_ZIP_AT_BOUNDS, b"#" * 2048, "holds more than 2048 bytes, the most the zip may hold",
            id="archive",
        ),
        # A skipped entry counts.
        pytest.param(
            {**UNIKIX_ZIP_AT_BOUNDS, "__MACOSX/migrated_app/._B.cbl": b""}, b"",
            "holds 9 entries, more than 8, the most it may hold",
            id="entries",
        ),
        pytest.param(
            {**UNIKIX_ZIP_AT_BOUNDS, "migrated_app/cbl/A.cbl": b"A" * 65, "migrated_app/cbl/B.cbl": b"B" * 63},
            b"",
            "holds unsafe entries: 'migrated_app/cbl/A.cbl' is 65 bytes, more than 64, the most one entry may hold",
            id="member",
        ),
        pytest.param(
            {**UNIKIX_ZIP_AT_BOUNDS, "migrated_app/cbl/C.cbl": b"C"}, b"",
            "holds unsafe entries: the entries to extract hold 129 bytes, more than 128, the most they may hold "
            "together",
            id="total",
        ),
    ],
)
def test_unikix_extract_zip_refuses_a_zip_past_a_bound_before_writing(
    unikix_temp_root: Path,
    monkeypatch: pytest.MonkeyPatch,
    members: dict[str, bytes],
    comment: bytes,
    refusal: str,
) -> None:
    repo = unikix_temp_root / "repo"
    _crafted_unikix_zip(repo, members, comment)
    work = _bounded_work_root(unikix_temp_root, monkeypatch)
    with pytest.raises(HarnessError) as caught:
        deploy.extract_zip(work, repo)
    assert str(caught.value) == f"{deploy.ZIP_RELPATH} {refusal}"
    # Only the lock taken first: no extraction, temporary sibling or marker.
    assert os.listdir(work) == [f"{deploy.ZIP_DIR_NAME}.lock"]


def test_unikix_extract_zip_reuse_refuses_a_file_of_other_bytes_of_its_size(
    unikix_deploy_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repo = unikix_deploy_root / "repo"
    path = _crafted_unikix_zip(repo, UNIKIX_ZIP_AT_BOUNDS)
    work = _bounded_work_root(unikix_deploy_root, monkeypatch)
    # The archive bound is met exactly too.
    monkeypatch.setattr(deploy, "_ZIP_MAX_ARCHIVE_BYTES", path.stat().st_size)
    monkeypatch.setattr(deploy, "_COMPARE_BLOCK_BYTES", UNIKIX_ZIP_COMPARE_BLOCK)
    target = deploy.extract_zip(work, repo)
    assert sorted(entry.relative_to(target).as_posix() for entry in target.rglob("*")) == UNIKIX_ZIP_EXTRACTED
    assert deploy.extract_zip(work, repo) == target
    # Other bytes in the last of its four blocks, the size and mode kept.
    tampered = target / "migrated_app" / "cbl" / "B.cbl"
    tampered.chmod(0o644)
    tampered.write_bytes(b"B" * 63 + b"b")
    tampered.chmod(0o444)
    with pytest.raises(HarnessError) as caught:
        deploy.extract_zip(work, repo)
    assert str(caught.value) == (
        f"{target} does not match {deploy.ZIP_RELPATH}: migrated_app/cbl/B.cbl differs from its zip member; "
        "remove it or choose another work root"
    )


@pytest.mark.parametrize(
    ("content", "same"),
    [
        pytest.param(b"B" * 64, True, id="equal"),
        pytest.param(b"b" + b"B" * 63, False, id="differs-in-the-first-block"),
        pytest.param(b"B" * 63 + b"b", False, id="differs-in-the-last-block"),
        pytest.param(b"B" * 63, False, id="ends-before-the-member"),
        pytest.param(b"B" * 65, False, id="holds-a-byte-beyond-the-member"),
    ],
)
def test_unikix_same_bytes_compares_a_file_with_its_member_block_by_block(
    unikix_temp_root: Path, monkeypatch: pytest.MonkeyPatch, content: bytes, same: bool
) -> None:
    monkeypatch.setattr(deploy, "_COMPARE_BLOCK_BYTES", UNIKIX_ZIP_COMPARE_BLOCK)
    path = _crafted_unikix_zip(unikix_temp_root / "repo", UNIKIX_ZIP_AT_BOUNDS)
    extracted = unikix_temp_root / "B.cbl"
    extracted.write_bytes(content)
    with zipfile.ZipFile(path) as archive:
        assert deploy._same_bytes(extracted, archive, archive.getinfo("migrated_app/cbl/B.cbl")) is same


@pytest.mark.parametrize(
    ("bound", "refusal"),
    [
        pytest.param(
            "_ZIP_MAX_MEMBER_BYTES", "{shown}:{member} is {size} bytes, more than {bound}, the most a member may hold",
            id="member",
        ),
        pytest.param(
            "_ZIP_MAX_ARCHIVE_BYTES", "{shown} is {size} bytes, more than {bound}, the most the zip may hold",
            id="archive",
        ),
    ],
)
def test_inventory_zip_member_refuses_a_zip_or_member_past_its_bound(
    unikix_temp_root: Path, monkeypatch: pytest.MonkeyPatch, bound: str, refusal: str
) -> None:
    content = b"F" * 64
    shown, member = inventory_tools.UNIKIX_ZIP, inventory_tools.FCT_MEMBER
    path = _crafted_unikix_zip(unikix_temp_root / "repo", {member: content})
    bounds = {"_ZIP_MAX_ARCHIVE_BYTES": path.stat().st_size, "_ZIP_MAX_MEMBER_BYTES": len(content)}
    for name, value in bounds.items():
        monkeypatch.setattr(inventory_tools, name, value)
    # At both bounds the member is read.
    assert inventory_tools._zip_member(path, shown, member) == content
    monkeypatch.setattr(inventory_tools, bound, bounds[bound] - 1)
    with pytest.raises(inventory_tools.InventoryError) as caught:
        inventory_tools._zip_member(path, shown, member)
    assert str(caught.value) == refusal.format(
        shown=shown, member=member, size=bounds[bound], bound=bounds[bound] - 1
    )


# The source identity readers of the UniKix zip, adapters/unikix/adapter.py
# _zip_index and harness/report.py _zip_members and _zip_units, on crafted
# zips under the UNIKIX_ZIP_BOUNDS bounds, members read 16 bytes at a time.
# Each holds every member but directories and __MACOSX/ entries, a .DS_Store
# included, to the bounds, so UNIKIX_IDENTITY_ZIP_AT_BOUNDS meets the entry,
# member and total bounds exactly: 8 entries, and members holding 128 bytes,
# none over 64. A.cbl COPYs the copybook B, a reference _zip_index and
# _zip_units read from its text.
UNIKIX_IDENTITY_COPY_LINE = b"       COPY B."
UNIKIX_IDENTITY_ZIP_AT_BOUNDS = {
    "migrated_app/": b"",
    "migrated_app/cbl/": b"",
    "migrated_app/cbl/A.cbl": UNIKIX_IDENTITY_COPY_LINE.ljust(63) + b"\n",
    "migrated_app/cpy/B": b"B" * 48,
    "migrated_app/cbl/C.cbl": b"",
    "migrated_app/.DS_Store": b"D" * 16,
    "__MACOSX/": b"",
    "__MACOSX/migrated_app/._A.cbl": b"M" * 64,
}
UNIKIX_IDENTITY_HASHED = (
    "migrated_app/cbl/A.cbl", "migrated_app/cpy/B", "migrated_app/cbl/C.cbl", "migrated_app/.DS_Store",
)
UNIKIX_IDENTITY_READ_BLOCK = 16
# Each reader by name: the module holding its bounds, and the name of its read block there.
UNIKIX_IDENTITY_READERS = {
    "adapter": (deploy, "_COMPARE_BLOCK_BYTES"),
    "report": (report, "_ZIP_READ_BLOCK_BYTES"),
    "units": (report, "_ZIP_READ_BLOCK_BYTES"),
}
UNIKIX_IDENTITY_REFUSALS = [
    pytest.param({}, b"#" * 2048, "holds more than 2048 bytes, the most the zip may hold", id="archive"),
    # A skipped entry counts.
    pytest.param(
        {"__MACOSX/migrated_app/._B.cbl": b""}, b"", "holds 9 entries, more than 8, the most it may hold", id="entries"
    ),
    pytest.param(
        {"migrated_app/cbl/A.cbl": UNIKIX_IDENTITY_COPY_LINE.ljust(64) + b"\n", "migrated_app/cpy/B": b"B" * 47},
        b"",
        "holds members past a size bound: 'migrated_app/cbl/A.cbl' is 65 bytes, more than 64, the most one member "
        "may hold",
        id="member",
    ),
    # A .DS_Store is hashed, so its bytes count.
    pytest.param(
        {"migrated_app/cbl/C.cbl": b"C"},
        b"",
        "holds members past a size bound: the members hold 129 bytes, more than 128, the most they may hold together",
        id="total",
    ),
]


def _identity_bounds(monkeypatch: pytest.MonkeyPatch, reader: str, archive_bytes: int | None = None) -> None:
    """Lower the zip bounds of ``reader`` to UNIKIX_ZIP_BOUNDS, its archive
    bound to ``archive_bytes`` when given, and its read block to
    UNIKIX_IDENTITY_READ_BLOCK."""
    module, block = UNIKIX_IDENTITY_READERS[reader]
    for name, value in UNIKIX_ZIP_BOUNDS.items():
        monkeypatch.setattr(module, name, value)
    if archive_bytes is not None:
        monkeypatch.setattr(module, "_ZIP_MAX_ARCHIVE_BYTES", archive_bytes)
    monkeypatch.setattr(module, block, UNIKIX_IDENTITY_READ_BLOCK)


class _CountedReads:
    """A file the reader under test opened, recording the length of each read."""

    def __init__(self, handle: io.BufferedReader, counted: list[int]) -> None:
        self._handle = handle
        self._counted = counted

    def __enter__(self) -> "_CountedReads":
        return self

    def __exit__(self, *exc_info: object) -> None:
        self._handle.close()

    def read(self, size: int = -1) -> bytes:
        data = self._handle.read(size)
        self._counted.append(len(data))
        return data


@pytest.mark.parametrize("reader", sorted(UNIKIX_IDENTITY_READERS))
@pytest.mark.parametrize(("changes", "comment", "refusal"), UNIKIX_IDENTITY_REFUSALS)
def test_unikix_zip_identity_readers_refuse_a_zip_past_a_bound_before_reading_a_member(
    unikix_temp_root: Path,
    monkeypatch: pytest.MonkeyPatch,
    changes: dict[str, bytes],
    comment: bytes,
    refusal: str,
    reader: str,
) -> None:
    path = _crafted_unikix_zip(unikix_temp_root / "repo", {**UNIKIX_IDENTITY_ZIP_AT_BOUNDS, **changes}, comment)
    digest, size = sha256_file(path), path.stat().st_size
    archive_bound = UNIKIX_ZIP_BOUNDS["_ZIP_MAX_ARCHIVE_BYTES"]
    _identity_bounds(monkeypatch, reader)
    counted: list[int] = []
    module = unikix_adapter if reader == "adapter" else report
    monkeypatch.setattr(module, "open", lambda *args, **kwargs: _CountedReads(open(*args, **kwargs), counted),
                        raising=False)

    def refuse(*args: object, **kwargs: object) -> None:
        raise AssertionError("the zip was read past a bound")

    # No member is opened; a zip past the archive bound is never parsed.
    monkeypatch.setattr(zipfile.ZipFile, "open", refuse)
    if size > archive_bound:
        monkeypatch.setattr(zipfile.ZipFile, "__init__", refuse)
    if reader == "adapter":
        with pytest.raises(HarnessError) as caught:
            unikix_adapter._zip_index(str(path), digest)
        assert type(caught.value) is HarnessError
        assert str(caught.value) == f"{deploy.ZIP_RELPATH} {refusal}"
    else:
        read = report._zip_members if reader == "report" else report._zip_units
        with pytest.raises(report.ReportError) as caught:
            read(digest, str(path))
        assert str(caught.value) == f"{report.display_path(path)} {refusal}"
    # One read of the zip, of at most one byte past the archive bound.
    assert counted == [min(size, archive_bound + 1)]
    if reader == "report":
        assert report.zip_identity(path) is None


@pytest.mark.parametrize("reader", sorted(UNIKIX_IDENTITY_READERS))
def test_unikix_zip_identity_readers_refuse_other_bytes_than_their_digest_before_parsing(
    unikix_temp_root: Path, monkeypatch: pytest.MonkeyPatch, reader: str
) -> None:
    path = _crafted_unikix_zip(unikix_temp_root / "repo", UNIKIX_IDENTITY_ZIP_AT_BOUNDS)
    digest = sha256_file(path)
    # Another zip takes its place once its digest is taken: each reader hashes
    # the bytes it reads and refuses them before zipfile parses any.
    other = _crafted_unikix_zip(
        unikix_temp_root / "other", {**UNIKIX_IDENTITY_ZIP_AT_BOUNDS, "migrated_app/cpy/B": b"b" * 48}
    )
    path.write_bytes(other.read_bytes())

    def refuse(*args: object, **kwargs: object) -> None:
        raise AssertionError("a zip of other bytes was parsed")

    monkeypatch.setattr(zipfile.ZipFile, "__init__", refuse)
    shown = report.display_path(path)
    if reader == "adapter":
        with pytest.raises(HarnessError) as caught:
            unikix_adapter._zip_index(str(path), digest)
        assert type(caught.value) is HarnessError
        assert str(caught.value) == f"{deploy.ZIP_RELPATH} changed while its source identity was read"
    elif reader == "report":
        with pytest.raises(report.ReportError) as caught:
            report._zip_members(digest, str(path))
        assert str(caught.value) == f"{shown} changed while it was read"
    else:
        message = f"{shown} is not the zip whose sha256 is {digest}"
        with pytest.raises(report.ReportError) as caught:
            report._zip_units(digest, str(path))
        assert str(caught.value) == message
        # identity_problem's derivation of the units a run executes reports it, never raising.
        monkeypatch.setattr(report, "UNIKIX_ZIP", path)
        assert report._required_units(SimpleNamespace(id="SWAPPED"), digest) == (
            set(), f"the units SWAPPED executes cannot be derived: {report._clip(message, 300)}"
        )



def test_unikix_zip_identity_readers_hash_each_member_a_block_at_a_time(
    unikix_temp_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    path = _crafted_unikix_zip(unikix_temp_root / "repo", UNIKIX_IDENTITY_ZIP_AT_BOUNDS)
    digest = sha256_file(path)
    expected = {name: harness.sha256_hex(UNIKIX_IDENTITY_ZIP_AT_BOUNDS[name]) for name in UNIKIX_IDENTITY_HASHED}
    # The archive bound is met exactly too.
    for reader in UNIKIX_IDENTITY_READERS:
        _identity_bounds(monkeypatch, reader, path.stat().st_size)
    requested: list[int] = []
    read = zipfile.ZipExtFile.read

    def recorded(self: zipfile.ZipExtFile, n: int = -1) -> bytes:
        requested.append(n)
        return read(self, n)

    monkeypatch.setattr(zipfile.ZipExtFile, "read", recorded)
    index = unikix_adapter._zip_index(str(path), digest)
    assert dict(index.hashes) == expected
    assert dict(index.references) == {
        "migrated_app/cbl/A.cbl": frozenset({"migrated_app/cpy/B"}),
        "migrated_app/cpy/B": frozenset(),
        "migrated_app/cbl/C.cbl": frozenset(),
    }
    assert report.zip_identity(path) == (digest, expected)
    units = report._zip_units(digest, str(path))
    assert units.names == frozenset(UNIKIX_IDENTITY_HASHED)
    assert units.references == dict(index.references)
    # Each reader reads every member it needs a block at a time until an
    # empty block, never whole: A.cbl in 5 reads, B in 4, C.cbl in 1 and
    # .DS_Store in 2, which _zip_units, reading programs and copybooks only,
    # does not read.
    assert requested == [UNIKIX_IDENTITY_READ_BLOCK] * 34


def test_unikix_zip_identity_readers_hash_the_committed_zip_as_whole_member_reads_do() -> None:
    repo = Path(_TESTS_ROOT).parent
    path, digest = deploy.zip_path(repo), deploy.zip_sha256(repo)
    with zipfile.ZipFile(path) as archive:
        whole = {
            info.filename: harness.sha256_hex(archive.read(info))
            for info in archive.infolist()
            if not info.is_dir() and not info.filename.startswith("__MACOSX/")
        }
    assert len(whole) == 183
    index = unikix_adapter._zip_index(str(path), digest)
    assert dict(index.hashes) == whole
    # The references read from the streamed texts are those report.py reads whole.
    assert dict(index.references) == report._zip_units(digest, str(path)).references
    assert report.zip_identity(path) == (digest, whole)
    # The committed UniKix-copy source identities name these very hashes.
    identities = [
        manifest["source_identity"]
        for manifest in map(load_yaml, sorted(BASELINES_DIR.glob("*/manifest.yaml")))
        if (manifest.get("source_identity") or {}).get("tree") == "unikix-copy"
    ]
    assert len(identities) == 3
    for identity in identities:
        assert identity["zip_sha256"] == digest
        assert {unit: whole.get(unit) for unit in identity["units"]} == identity["units"]


# The same readers on a zip whose first member, A.cbl, deflated, is damaged
# as each case names, followed by the copybook B, stored. Each case gives
# the error zipfile itself raises (one of inventory_tools._ZIP_ERRORS) and
# where harness/report.py meets it: at A.cbl's read, or at the parse of the
# central directory for a name it cannot decode.
UNIKIX_IDENTITY_DAMAGED = "migrated_app/cbl/A.cbl"
UNIKIX_IDENTITY_AT_MEMBER = "cannot read {member!r} from {shown}: {reason}"
UNIKIX_IDENTITY_AT_PARSE = "cannot read {shown}: {reason}"
UNIKIX_IDENTITY_DAMAGES = [
    # Its compressed bytes all 0xff: a final block of the reserved type 11.
    pytest.param("corrupt-deflate", zlib.error, UNIKIX_IDENTITY_AT_MEMBER, id="corrupt-deflate"),
    pytest.param("unsupported-compression", NotImplementedError, UNIKIX_IDENTITY_AT_MEMBER,
                 id="unsupported-compression"),
    pytest.param("encrypted", RuntimeError, UNIKIX_IDENTITY_AT_MEMBER, id="encrypted"),
    pytest.param("bad-crc", zipfile.BadZipFile, UNIKIX_IDENTITY_AT_MEMBER, id="bad-crc"),
    pytest.param("undecodable-name", UnicodeDecodeError, UNIKIX_IDENTITY_AT_PARSE, id="undecodable-name"),
]


def _damaged_unikix_zip(repo: Path, damage: str) -> Path:
    """Write the UniKix zip of the fake repository root ``repo``:
    UNIKIX_IDENTITY_DAMAGED, deflated and damaged as ``damage`` names
    (UNIKIX_IDENTITY_DAMAGES), then the copybook B, stored. Return its path."""
    path = deploy.zip_path(repo)
    path.parent.mkdir(parents=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(UNIKIX_IDENTITY_DAMAGED, (UNIKIX_IDENTITY_COPY_LINE + b"\n") * 4,
                         compress_type=zipfile.ZIP_DEFLATED)
        archive.writestr("migrated_app/cpy/B", b"B" * 48)
    with zipfile.ZipFile(path) as archive:
        info = archive.getinfo(UNIKIX_IDENTITY_DAMAGED)
        # A.cbl's local header, and its record, the first of the central directory.
        local, central = info.header_offset, archive.start_dir
    data = bytearray(path.read_bytes())

    def field(offset: int, size: int) -> int:
        return int.from_bytes(data[offset:offset + size], "little")

    def patch(offset: int, size: int, value: int) -> None:
        data[offset:offset + size] = value.to_bytes(size, "little")

    # The local header gives the name and extra field lengths at 26 and 28;
    # a central directory record the flags at 8, the method at 10, the
    # CRC-32 at 16 and the name at 46. zipfile takes all but the name from
    # the central directory.
    if damage == "corrupt-deflate":
        start = local + 30 + field(local + 26, 2) + field(local + 28, 2)
        data[start:start + info.compress_size] = b"\xff" * info.compress_size
    elif damage == "unsupported-compression":
        patch(central + 10, 2, 99)
    elif damage == "encrypted":
        patch(central + 8, 2, field(central + 8, 2) | 0x1)
    elif damage == "bad-crc":
        patch(central + 16, 4, info.CRC ^ 0xFFFFFFFF)
    elif damage == "undecodable-name":
        # The UTF-8 flag set on a name holding the byte 0xff in place of "A".
        patch(central + 8, 2, field(central + 8, 2) | 0x800)
        data[central + 46 + len("migrated_app/cbl/")] = 0xFF
    else:
        raise AssertionError(f"no damage named {damage!r}")
    path.write_bytes(bytes(data))
    return path


@pytest.mark.parametrize("reader", sorted(UNIKIX_IDENTITY_READERS))
@pytest.mark.parametrize(("damage", "error", "where"), UNIKIX_IDENTITY_DAMAGES)
def test_unikix_zip_identity_readers_refuse_a_damaged_member(
    unikix_temp_root: Path,
    monkeypatch: pytest.MonkeyPatch,
    damage: str,
    error: type[Exception],
    where: str,
    reader: str,
) -> None:
    path = _damaged_unikix_zip(unikix_temp_root / "repo", damage)
    digest = sha256_file(path)
    with pytest.raises(error) as raised:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                archive.read(info)
    assert type(raised.value) is error
    reason = str(raised.value) or type(raised.value).__name__
    if reader == "adapter":
        with pytest.raises(HarnessError) as caught:
            unikix_adapter._zip_index(str(path), digest)
        assert type(caught.value) is HarnessError
        assert str(caught.value) == f"cannot read {deploy.ZIP_RELPATH}: {reason}"
        return
    message = where.format(member=UNIKIX_IDENTITY_DAMAGED, shown=report.display_path(path), reason=reason)
    if reader == "report":
        with pytest.raises(report.ReportError) as caught:
            report._zip_members(digest, str(path))
        assert str(caught.value) == message
        assert report.zip_identity(path) is None
    else:
        with pytest.raises(report.ReportError) as caught:
            report._zip_units(digest, str(path))
        assert str(caught.value) == message
        # identity_problem's derivation of the units a run executes reports it, never raising.
        monkeypatch.setattr(report, "UNIKIX_ZIP", path)
        assert report._required_units(SimpleNamespace(id="DAMAGED"), digest) == (
            set(), f"the units DAMAGED executes cannot be derived: {report._clip(message, 300)}"
        )


# UniKixAdapter's @TDQ:JOBS capture on fakes: jobs_capture answers with
# scripted listings and JCL while parse.job and the gap come from the
# committed commands.yaml; adapter.py's monotonic clock moves only when it
# sleeps, its datetime.now() gives each case's scripted instants, and a
# captured job is recorded instead of waited for. Nothing is written to disk.
JOBS_SCENARIO = "RPT-CORPT00C-normal-01"
JOBS_GAP = "GAP-MIS-017"
# The window opens, opens again after a restart, and the last step ends.
JOBS_FIRST = datetime.datetime(2025, 3, 4, 10, 0, 40, 500000)
JOBS_RESTARTED = datetime.datetime(2025, 3, 4, 10, 1, 20)
JOBS_LAST = datetime.datetime(2025, 3, 4, 10, 2, 30, 250000)
JOBS_JCL = bpe.trnrpt00_jcl("2025-03-01", "2025-03-31")
# What jobs_capture lists before the first step: another job, and a TRNRPT00
# of an earlier scenario listed in the minute the window opens in.
JOBS_BEFORE_STEPS = "  16 POSTTRAN 2025-03-04 10:00:12\n  17 TRNRPT00 2025-03-04 10:00\n"
# New TRNRPT00 jobs listed after the window opened: one submitted before it,
# one during the steps, one after the last step ended, and one during the
# steps of a restarted attempt.
JOBS_LISTED_BEFORE = "  15 TRNRPT00 2025-03-04 10:00:39\n"
JOBS_LISTED_INSIDE = "  18 TRNRPT00 2025-03-04 10:01:15\n"
JOBS_LISTED_AFTER = "  19 TRNRPT00 2025-03-04 10:02:31\n"
JOBS_LISTED_RESTARTED = "  20 TRNRPT00 2025-03-04 10:01:50\n"
JOBS_LEFT_BEFORE = "before the window opened"
JOBS_LEFT_AFTER = "after the last step ended"
# The listings a capture without any job inside the window takes: the first,
# then one each poll until the settle period ends.
JOBS_SETTLE_LISTINGS = 1 + round(unikix_adapter._JOBS_SETTLE_S / unikix_adapter._JOBS_POLL_S)


class _JobsCommands:
    """The ``CommandSet`` the JOBS capture uses: entries of the committed
    commands.yaml; ``jobs_capture`` without a job number gives the next
    scripted listing (the last one again once the script is used up) and
    with one that job's JCL. ``job_numbers`` records every run's number."""

    def __init__(self, listings: list[str], jcl: Mapping[int, str]) -> None:
        self.entries = unikix_env.CommandSet.load()
        self.listings = list(listings)
        self.jcl = dict(jcl)
        self.job_numbers: list[str] = []

    def entry(self, entry_id: str) -> unikix_env.CommandEntry:
        return self.entries.entry(entry_id)

    def run(
        self, entry_id: str, env: object, deadline_left: float | None = None, **values: str | int
    ) -> SimpleNamespace:
        assert entry_id == "jobs_capture" and deadline_left is not None and deadline_left > 0
        number = str(values["job_number"])
        self.job_numbers.append(number)
        if number:
            return SimpleNamespace(stdout=self.jcl[int(number)])
        listing = self.listings.pop(0) if len(self.listings) > 1 else self.listings[0]
        return SimpleNamespace(stdout=listing)


class _SettleClock:
    """adapter.py's ``time``: a monotonic clock that only ``sleep`` moves."""

    def __init__(self) -> None:
        self.now = 0.0

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        assert 0 < seconds <= unikix_adapter._JOBS_POLL_S
        self.now += seconds


class _Instants(datetime.datetime):
    """adapter.py's ``datetime``: ``now`` gives the case's scripted instants
    in turn and fails once they are used up."""

    script: list[datetime.datetime] = []

    @classmethod
    def now(cls, tz: datetime.tzinfo | None = None) -> datetime.datetime:
        assert cls.script, "datetime.now() read more often than the JOBS window needs"
        return cls.script.pop(0)


def _jobs_adapter(
    monkeypatch: pytest.MonkeyPatch,
    work_root: Path,
    listings: list[str],
    jcl: Mapping[int, str] | None = None,
) -> tuple[UniKixAdapter, _JobsCommands, list[tuple[str, str]]]:
    """A ``UniKixAdapter`` on ``work_root`` (the session's carddemo_work_root)
    with a fresh capture, fake commands answering ``listings`` and ``jcl``,
    the committed resources.yaml, a scenario deadline 300 s away, and the jobs
    it would wait for recorded instead, each of which must have been
    registered (``adopt``) first."""
    adapter = UniKixAdapter(work_root=work_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter._capture = evidence.Capture()
    commands = _JobsCommands(listings, jcl or {})
    adapter._commands = commands
    adapter._env = object()
    adopted: dict[str, bpe.JobRun] = {}

    def adopt(card_name: str, job_number: str, entry_id: str) -> bpe.JobRun:
        return adopted.setdefault(
            job_number, bpe.JobRun(card_name, card_name, card_name, entry_id, job_number)
        )

    adapter._batch = SimpleNamespace(
        resources=bpe.Resources.load(), take_job_numbers=lambda: [], adopt=adopt
    )
    adapter.deadline = time.monotonic() + 300
    awaited: list[tuple[str, str]] = []

    def await_region_job(run: bpe.JobRun) -> None:
        assert adopted.get(run.job_number) is run, "waited for a job never registered"
        awaited.append((run.card_name, run.job_number))

    monkeypatch.setattr(adapter, "_await_region_job", await_region_job)
    monkeypatch.setattr(unikix_adapter, "time", _SettleClock())
    return adapter, commands, awaited


def _jobs_records() -> bytes:
    """The @TDQ:JOBS bytes of one captured JOBS_JCL: each line an 80-byte
    record."""
    return b"".join(line.ljust(base.TDQ_RECORD_LENGTH).encode("ascii") for line in JOBS_JCL.splitlines())


# Each case: the instants adapter.py reads (the window opening, once more per
# restart, then the end of the last step), the listings jobs_capture gives in
# turn (one per window opening, then the polls), the restarts tpe.drive asks
# for, the job captured, and the jobs left out with the reason noted.
JOBS_RUN_CASES = [
    pytest.param(
        [JOBS_FIRST, JOBS_LAST],
        [
            JOBS_BEFORE_STEPS,
            JOBS_BEFORE_STEPS + JOBS_LISTED_BEFORE + JOBS_LISTED_INSIDE + JOBS_LISTED_AFTER,
        ],
        0,
        18,
        {15: JOBS_LEFT_BEFORE, 19: JOBS_LEFT_AFTER},
        id="one-attempt",
    ),
    pytest.param(
        [JOBS_FIRST, JOBS_RESTARTED, JOBS_LAST],
        [
            JOBS_BEFORE_STEPS,
            JOBS_BEFORE_STEPS + JOBS_LISTED_INSIDE,
            JOBS_BEFORE_STEPS
            + JOBS_LISTED_INSIDE
            + JOBS_LISTED_BEFORE
            + JOBS_LISTED_RESTARTED
            + JOBS_LISTED_AFTER,
        ],
        1,
        20,
        {15: JOBS_LEFT_BEFORE, 19: JOBS_LEFT_AFTER},
        id="restarted-attempt",
    ),
]


@pytest.mark.parametrize(
    ("instants", "listings", "restarts", "captured", "left_out"), JOBS_RUN_CASES
)
def test_unikix_jobs_capture_attributes_only_new_jobs_submitted_during_the_steps(
    monkeypatch: pytest.MonkeyPatch,
    carddemo_work_root: Path,
    instants: list[datetime.datetime],
    listings: list[str],
    restarts: int,
    captured: int,
    left_out: dict[int, str],
) -> None:
    adapter, commands, awaited = _jobs_adapter(
        monkeypatch, carddemo_work_root, listings, {captured: JOBS_JCL}
    )
    fake_datetime = SimpleNamespace(
        datetime=_Instants, timedelta=datetime.timedelta, date=datetime.date
    )
    monkeypatch.setattr(unikix_adapter, "_dt", fake_datetime)
    monkeypatch.setattr(_Instants, "script", list(instants))
    resets: list[object] = []
    monkeypatch.setattr(adapter, "_attempt_reset", resets.append)

    def drive(
        steps: object, sessions: object, deadline: object, capture: object, before_step: object,
        *, restart: object, **options: object,
    ) -> SimpleNamespace:
        for _ in range(restarts):
            restart()
        return SimpleNamespace(screens=[], messages=[])

    monkeypatch.setattr(unikix_adapter.tpe, "drive", drive)
    plan = unikix_adapter._Plan(JOBS_SCENARIO, True, [{"aid": "ENTER"}], 1, (), None)
    adapter._plan = plan
    scenario = SimpleNamespace(id=JOBS_SCENARIO, expected={"file_state": {base.TDQ_JOBS: []}})
    result = adapter.execute(scenario, Path(_TESTS_ROOT) / JOBS_SCENARIO)
    capture = adapter._capture
    assert result.datasets == {base.TDQ_JOBS: _jobs_records()}
    assert capture.datasets == result.datasets
    assert capture.job_numbers == [str(captured)]
    assert awaited == [("TRNRPT00", str(captured))]
    assert resets == [plan] * restarts
    # The window took its instants once each: never again while listing.
    assert _Instants.script == []
    # One listing per window opening, one poll that lists the job, its JCL.
    assert commands.job_numbers == [""] * (1 + restarts) + ["", str(captured)]
    assert capture.sysout["adapter/tdq-jobs/listing-before-steps.txt"] == listings[restarts]
    assert capture.sysout["adapter/tdq-jobs/listing.txt"] == listings[-1]
    assert capture.sysout[f"adapter/tdq-jobs/{captured}.jcl"] == JOBS_JCL
    notes = capture.sysout["adapter/tdq-jobs/correlation.txt"].splitlines()
    assert len(notes) == len(left_out)
    for number, reason in left_out.items():
        assert any(f"job {number} " in note and reason in note for note in notes), notes


# Each case: the submission time jobs_capture lists for job 18, which is not
# in the window's snapshot, and the reason it is left out given the window
# from JOBS_FIRST to JOBS_LAST (None: it is captured). Job 17, in the
# snapshot, is listed in the minute the window opened in and never captured.
# Job 18 stands for a region submission the listing shows only after the
# snapshot, whenever it was made: listed wholly before the first step, it is
# left out.
JOBS_PLACEMENT_CASES = [
    pytest.param("2025-03-04 10:00:41", None, id="second-after-the-first-step-began"),
    pytest.param("2025-03-04 10:00:40.6", None, id="tenth-after-the-first-step-began"),
    pytest.param("2025-03-04 10:02:30.24", None, id="hundredth-before-the-end"),
    pytest.param("2025-03-04 10:00:39", JOBS_LEFT_BEFORE, id="second-before-the-window"),
    pytest.param("2025-03-04 10:00:40.4", JOBS_LEFT_BEFORE, id="tenth-ending-as-the-first-step-began"),
    pytest.param("2025-03-04 10:02:31", JOBS_LEFT_AFTER, id="second-after-the-end"),
    pytest.param("2025-03-04 10:02:30.3", JOBS_LEFT_AFTER, id="tenth-after-the-end"),
]


@pytest.mark.parametrize(("listed", "left_out"), JOBS_PLACEMENT_CASES)
def test_unikix_jobs_capture_places_a_new_job_by_its_listed_time(
    monkeypatch: pytest.MonkeyPatch, carddemo_work_root: Path, listed: str, left_out: str | None
) -> None:
    listing = JOBS_BEFORE_STEPS + f"  18 TRNRPT00 {listed}\n"
    adapter, commands, awaited = _jobs_adapter(
        monkeypatch, carddemo_work_root, [listing], {18: JOBS_JCL}
    )
    window = unikix_adapter._JobsWindow(frozenset({16, 17}), JOBS_FIRST, JOBS_LAST)
    data = adapter._jobs_queue(window)
    capture = adapter._capture
    assert capture.datasets == {base.TDQ_JOBS: data}
    assert capture.sysout["adapter/tdq-jobs/listing.txt"] == listing
    if left_out is None:
        assert data == _jobs_records()
        assert commands.job_numbers == ["", "18"]
        assert capture.job_numbers == ["18"] and awaited == [("TRNRPT00", "18")]
        assert "adapter/tdq-jobs/correlation.txt" not in capture.sysout
    else:
        assert data == b""
        assert commands.job_numbers == [""] * JOBS_SETTLE_LISTINGS
        assert capture.job_numbers == [] and awaited == []
        # Noted once, however often the settle period listed it.
        notes = capture.sysout["adapter/tdq-jobs/correlation.txt"].splitlines()
        assert len(notes) == 1 and "job 18 " in notes[0] and left_out in notes[0], notes


# Each case: a submission time listed for new job 18 in the time unit in
# which the first step began (JOBS_FIRST) or the last step ended (JOBS_LAST),
# and the precision the refusal names.
JOBS_UNPLACEABLE_CASES = [
    pytest.param("2025-03-04 10:00", "to 60 s", id="minute-the-first-step-began-in"),
    pytest.param("2025-03-04 10:00:40", "to 1 s", id="second-the-first-step-began-in"),
    pytest.param("2025-03-04 10:02", "to 60 s", id="minute-the-last-step-ended-in"),
    pytest.param("2025-03-04 10:02:30", "to 1 s", id="second-the-last-step-ended-in"),
    pytest.param("2025-03-04 10:02:30.2", "to 0.1 s", id="tenth-the-last-step-ended-in"),
]


@pytest.mark.parametrize(("listed", "precision"), JOBS_UNPLACEABLE_CASES)
def test_unikix_jobs_capture_blocks_a_job_its_precision_cannot_place(
    monkeypatch: pytest.MonkeyPatch, carddemo_work_root: Path, listed: str, precision: str
) -> None:
    # Job 21, listed first, was submitted during the steps; it is not
    # captured either once job 18 makes the listing no observation.
    listing = JOBS_BEFORE_STEPS + "  21 TRNRPT00 2025-03-04 10:01:15\n" + f"  18 TRNRPT00 {listed}\n"
    adapter, commands, awaited = _jobs_adapter(monkeypatch, carddemo_work_root, [listing])
    window = unikix_adapter._JobsWindow(frozenset({16, 17}), JOBS_FIRST, JOBS_LAST)
    with pytest.raises(base.AdapterUnavailable) as caught:
        adapter._jobs_queue(window)
    assert caught.value.gap_id == JOBS_GAP
    steps = (JOBS_FIRST.isoformat(" "), JOBS_LAST.isoformat(" "))
    for detail in ("TRNRPT00 job 18 ", repr(listed), precision, *steps):
        assert detail in caught.value.reason, caught.value.reason
    # Blocked on that listing: no JCL printed, nothing captured or waited for.
    assert commands.job_numbers == [""]
    assert adapter._capture.job_numbers == [] and awaited == []


# Each case: what jobs_capture lists just before the first step, then the
# submission time of job 18, a region submission made before that step that
# the listing shows only once polling began, in the second or minute the
# first step began in. One listed wholly before the first step is left out
# (JOBS_PLACEMENT_CASES, second-before-the-window).
JOBS_LATE_LISTED_CASES = [
    pytest.param("", "2025-03-04 10:00:40", id="empty-snapshot-second"),
    pytest.param("", "2025-03-04 10:00", id="empty-snapshot-minute"),
    pytest.param(JOBS_BEFORE_STEPS, "2025-03-04 10:00:40", id="other-jobs-snapshot-second"),
    pytest.param(JOBS_BEFORE_STEPS, "2025-03-04 10:00", id="other-jobs-snapshot-minute"),
]


@pytest.mark.parametrize(("snapshot", "listed"), JOBS_LATE_LISTED_CASES)
def test_unikix_jobs_capture_blocks_a_job_listed_late_in_the_unit_the_first_step_began_in(
    monkeypatch: pytest.MonkeyPatch, carddemo_work_root: Path, snapshot: str, listed: str
) -> None:
    listings = [snapshot, snapshot, snapshot + f"  18 TRNRPT00 {listed}\n"]
    adapter, commands, awaited = _jobs_adapter(
        monkeypatch, carddemo_work_root, listings, {18: JOBS_JCL}
    )
    fake_datetime = SimpleNamespace(
        datetime=_Instants, timedelta=datetime.timedelta, date=datetime.date
    )
    monkeypatch.setattr(unikix_adapter, "_dt", fake_datetime)
    monkeypatch.setattr(_Instants, "script", [JOBS_FIRST])
    window = adapter._jobs_window()
    assert 18 not in window.known and window.first == JOBS_FIRST and _Instants.script == []
    window.last = JOBS_LAST
    with pytest.raises(base.AdapterUnavailable) as caught:
        adapter._jobs_queue(window)
    assert caught.value.gap_id == JOBS_GAP
    steps = (JOBS_FIRST.isoformat(" "), JOBS_LAST.isoformat(" "))
    for detail in ("TRNRPT00 job 18 ", repr(listed), *steps):
        assert detail in caught.value.reason, caught.value.reason
    # The snapshot, a poll without job 18, the poll that blocks: no JCL
    # printed, nothing captured or waited for.
    assert commands.job_numbers == ["", "", ""]
    capture = adapter._capture
    assert capture.job_numbers == [] and awaited == []
    assert base.TDQ_JOBS not in capture.datasets


JOBS_LISTING_JOB = "  18 TRNRPT00 2025-03-04 10:01:15\n"
# Each case: a jobs_capture listing that is no listing, and what the refusal
# names.
JOBS_LISTING_REFUSALS = [
    pytest.param(
        "JOB#  NAME      SUBMITTED\n" + JOBS_LISTING_JOB,
        "'JOB#  NAME      SUBMITTED'",
        id="header-line",
    ),
    pytest.param(
        JOBS_LISTING_JOB + "spool unreadable: permission denied\n",
        "'spool unreadable: permission denied'",
        id="diagnostic-after-the-jobs",
    ),
    pytest.param(
        "x" * 200 + "\n",
        repr("x" * unikix_adapter._SHOWN_LINE_CHARS + "..."),
        id="long-line-cut",
    ),
    pytest.param(JOBS_LISTING_JOB * 2, "job 18 more than once", id="job-number-twice"),
    pytest.param(
        JOBS_LISTING_JOB + "  18 POSTTRAN 2025-03-04 10:01:15\n",
        "job 18 more than once",
        id="job-number-twice-other-name",
    ),
]


@pytest.mark.parametrize("listing_name", ["listing-before-steps.txt", "listing.txt"])
@pytest.mark.parametrize(("listing", "named"), JOBS_LISTING_REFUSALS)
def test_unikix_jobs_capture_blocks_a_listing_that_is_not_job_lines(
    monkeypatch: pytest.MonkeyPatch,
    carddemo_work_root: Path,
    listing: str,
    named: str,
    listing_name: str,
) -> None:
    adapter, commands, awaited = _jobs_adapter(
        monkeypatch, carddemo_work_root, [listing], {18: JOBS_JCL}
    )
    window = unikix_adapter._JobsWindow(frozenset(), JOBS_FIRST, JOBS_LAST)
    with pytest.raises(base.AdapterUnavailable) as caught:
        if listing_name == "listing.txt":
            adapter._jobs_queue(window)
        else:
            adapter._jobs_window()
    assert caught.value.gap_id == JOBS_GAP
    assert named in caught.value.reason, caught.value.reason
    # The output refused is kept as listed; no JCL printed, nothing captured.
    assert adapter._capture.sysout[f"adapter/tdq-jobs/{listing_name}"] == listing
    assert commands.job_numbers == [""]
    assert adapter._capture.job_numbers == [] and awaited == []


def test_unikix_jobs_capture_reads_a_blank_listing_as_no_job(
    monkeypatch: pytest.MonkeyPatch, carddemo_work_root: Path
) -> None:
    adapter, commands, awaited = _jobs_adapter(monkeypatch, carddemo_work_root, ["\n   \n\t\n"])
    window = adapter._jobs_window()
    assert window.known == frozenset() and window.last is None
    window.last = datetime.datetime.now()
    assert adapter._jobs_queue(window) == b""
    assert adapter._capture.datasets == {base.TDQ_JOBS: b""}
    assert commands.job_numbers == [""] * (1 + JOBS_SETTLE_LISTINGS)
    assert adapter._capture.job_numbers == [] and awaited == []


# Each case: a submission time as jobs_capture may list it, and the instant
# and precision it is read as (None: it is not a submission time).
SUBMITTED_TIMES = [
    pytest.param(
        "2025-03-04 10:02",
        datetime.datetime(2025, 3, 4, 10, 2),
        datetime.timedelta(minutes=1),
        id="minutes",
    ),
    pytest.param(
        " 2025-03-04T10:02:30 ",
        datetime.datetime(2025, 3, 4, 10, 2, 30),
        datetime.timedelta(seconds=1),
        id="seconds",
    ),
    pytest.param(
        "2025-03-04 10:02:30.2",
        datetime.datetime(2025, 3, 4, 10, 2, 30, 200000),
        datetime.timedelta(milliseconds=100),
        id="tenths",
    ),
    pytest.param(
        "03/04/2025 10:02:30:25",
        datetime.datetime(2025, 3, 4, 10, 2, 30, 250000),
        datetime.timedelta(milliseconds=10),
        id="hundredths-month-first",
    ),
    pytest.param(
        "04.03.2025 10:02:30,250",
        datetime.datetime(2025, 3, 4, 10, 2, 30, 250000),
        datetime.timedelta(milliseconds=1),
        id="milliseconds-day-first",
    ),
    pytest.param(
        "2025-03-04 10:02:30.123456789",
        datetime.datetime(2025, 3, 4, 10, 2, 30, 123456),
        datetime.timedelta(microseconds=1),
        id="nanoseconds-cut-to-microseconds",
    ),
    pytest.param("25-03-04 10:02:30", None, None, id="no-four-digit-year"),
    pytest.param("2025-03-04 10:02:61", None, None, id="no-such-second"),
    pytest.param("2025-03-04 10:02:30.", None, None, id="fraction-without-digits"),
]


@pytest.mark.parametrize(("text", "at", "precision"), SUBMITTED_TIMES)
def test_unikix_submission_time_is_read_with_its_listed_precision(
    text: str, at: datetime.datetime | None, precision: datetime.timedelta | None
) -> None:
    expected = None if at is None else (at, precision)
    assert unikix_adapter._submitted_at(text) == expected


# The raw observations of one run, in the order _observe acquires them.
OBSERVED = {
    base.REGION_FILES: b"region files as region_file_inquire listed them\n",
    base.TDQ_JOBS: b"",
    "ACCTDATA": b"A" * 300,
    "TRANSACT": b"T" * 350,
}
# Each case: where acquisition fails after the region files and the JOBS
# capture were read (None: nowhere), the gap of that failure, and the
# observations the evidence then holds.
OBSERVE_FAILURES = [
    pytest.param(
        "wait", "GAP-MIS-006", (base.REGION_FILES, base.TDQ_JOBS), id="waiting-for-the-captured-job"
    ),
    pytest.param(
        "unload",
        "GAP-MIS-007",
        (base.REGION_FILES, base.TDQ_JOBS, "ACCTDATA"),
        id="second-unload",
    ),
    pytest.param("reopen", "GAP-MIS-011", tuple(OBSERVED), id="reopening-the-region-files"),
    pytest.param(None, None, tuple(OBSERVED), id="no-failure"),
]


@pytest.mark.parametrize(("fails_at", "gap_id", "kept"), OBSERVE_FAILURES)
def test_unikix_observations_stay_in_the_evidence_when_a_later_step_fails(
    monkeypatch: pytest.MonkeyPatch,
    carddemo_work_root: Path,
    fails_at: str | None,
    gap_id: str | None,
    kept: tuple[str, ...],
) -> None:
    listing = JOBS_BEFORE_STEPS + JOBS_LISTED_INSIDE
    adapter, _, _ = _jobs_adapter(monkeypatch, carddemo_work_root, [listing], {18: JOBS_JCL})
    acquired = {**OBSERVED, base.TDQ_JOBS: _jobs_records()}

    def fail(where: str) -> None:
        if where == fails_at:
            raise base.AdapterUnavailable(f"{where} failed", gap_id)

    def observe(
        names: list[str], *, deadline: float | None, keep: object
    ) -> dict[str, bytes]:
        assert names == ["ACCTDATA", "TRANSACT"] and deadline == adapter.deadline
        keep("ACCTDATA", acquired["ACCTDATA"])
        fail("unload")
        keep("TRANSACT", acquired["TRANSACT"])
        return {name: acquired[name] for name in names}

    adapter._batch.observe = observe
    monkeypatch.setattr(adapter, "_region_files", lambda: acquired[base.REGION_FILES])
    monkeypatch.setattr(adapter, "_await_region_job", lambda run: fail("wait"))
    monkeypatch.setattr(adapter, "_close_region_files", lambda: None)
    monkeypatch.setattr(adapter, "_open_region_files", lambda: fail("reopen"))
    window = unikix_adapter._JobsWindow(frozenset({16, 17}), JOBS_FIRST, JOBS_LAST)
    names = {*OBSERVED, base.SCREENS}
    if fails_at is None:
        assert adapter._observe(names, window) == acquired
    else:
        with pytest.raises(base.AdapterUnavailable) as caught:
            adapter._observe(names, window)
        assert caught.value.gap_id == gap_id
    assert adapter._capture.datasets == {name: acquired[name] for name in kept}


# Each case: the dataset whose unload fails (None: none does), and the
# observations BatchService.observe has passed to keep, in order, when it
# raises or returns. TRANSACT is not in the catalog, so it is left out.
BATCH_OBSERVE_CASES = [
    pytest.param("CUSTDATA", ("@AIX:CARDAIX", base.CATALOG, "ACCTDATA"), id="third-unload-fails"),
    pytest.param(
        None, ("@AIX:CARDAIX", base.CATALOG, "ACCTDATA", "CUSTDATA"), id="every-unload-succeeds"
    ),
]


@pytest.mark.parametrize(("failing", "kept"), BATCH_OBSERVE_CASES)
def test_unikix_batch_observe_keeps_each_observation_as_it_is_acquired(
    failing: str | None, kept: tuple[str, ...]
) -> None:
    resources = bpe.Resources.load()
    service = bpe.BatchService(SimpleNamespace(vars={}), SimpleNamespace(), resources)
    entries = tuple(
        sorted(
            [
                bpe.CatalogEntry(resources.dataset(name).dsn, "CLUSTER")
                for name in ("ACCTDATA", "CARDDATA", "CUSTDATA")
            ]
            + [bpe.CatalogEntry(resources.aix_path("CARDAIX").dsn, "PATH")]
        )
    )

    def unload(logical: str, *, deadline: float | None = None) -> bytes:
        assert deadline is not None
        if logical == failing:
            raise base.AdapterUnavailable(f"dataset_unload of {logical} exited 12", "GAP-MIS-007")
        return f"records of {logical}".encode("ascii")

    service._list_catalog = lambda budget: entries
    service.unload = unload
    service.unload_aix = lambda path, *, deadline=None: f"records through {path}".encode("ascii")
    acquired = {
        "@AIX:CARDAIX": b"records through CARDAIX",
        base.CATALOG: base.format_catalog(entry.observation for entry in entries),
        "ACCTDATA": b"records of ACCTDATA",
        "CUSTDATA": b"records of CUSTDATA",
    }
    calls: list[tuple[str, bytes]] = []
    names = ["TRANSACT", "CUSTDATA", "ACCTDATA", base.CATALOG, "@AIX:CARDAIX"]
    deadline = time.monotonic() + 300
    if failing is None:
        found = service.observe(names, deadline=deadline, keep=lambda *kept: calls.append(kept))
        assert found == dict(calls)
    else:
        with pytest.raises(base.AdapterUnavailable) as caught:
            service.observe(names, deadline=deadline, keep=lambda *kept: calls.append(kept))
        assert caught.value.gap_id == "GAP-MIS-007"
    assert calls == [(name, acquired[name]) for name in kept]


# Online fault placement: UniKixAdapter._plan_faults and the before_step
# callback _fault_applier gives tpe.drive, on fake commands. A fault's `after`
# is the number of its operation requests on the target that succeed before
# it, read through region_file_requests when it is 1 or more.
FAULT_SCENARIO = "CRD-COCRDLIC-recovery-03"
FAULT_DISABLE = "region_file_disable"
FAULT_COUNT = "region_file_requests"
FAULT_GAP = "GAP-MIS-011"
FAULT_NOTE = "adapter/faults.txt"
FAULT_CARD_READ = {"target": "CARDDAT", "operation": "read", "condition": "unavailable"}


class _FaultCommands:
    """The ``CommandSet`` online fault planning and placement use: entries of
    the committed commands.yaml, ``found`` the entries probed available, and
    ``region_file_requests`` printing the scripted ``counts`` in turn.
    ``runs`` records every run with its placeholder values."""

    def __init__(self, found: frozenset[str], counts: tuple[str, ...] = ()) -> None:
        self.entries = unikix_env.CommandSet.load()
        self.found = frozenset(found)
        self.counts = list(counts)
        self.runs: list[tuple[str, dict[str, str | int]]] = []

    def entry(self, entry_id: str) -> unikix_env.CommandEntry:
        return self.entries.entry(entry_id)

    def available(
        self, entry_id: str, env: object = None, *, deadline_left: float | None = None
    ) -> bool:
        return entry_id in self.found

    def probe_reason(self, entry_id: str) -> str | None:
        return None if entry_id in self.found else f"probe found no {entry_id}"

    def run(
        self, entry_id: str, env: object, deadline_left: float | None = None, **values: str | int
    ) -> SimpleNamespace:
        assert entry_id in self.found, f"{entry_id} ran although its probe failed"
        assert deadline_left is not None and deadline_left > 0
        self.runs.append((entry_id, dict(values)))
        if entry_id != FAULT_COUNT:
            return SimpleNamespace(stdout="")
        assert self.counts, f"{FAULT_COUNT} read more often than the case scripts"
        return SimpleNamespace(stdout=self.counts.pop(0))


def _fault_adapter(commands: _FaultCommands, work_root: Path) -> UniKixAdapter:
    """A ``UniKixAdapter`` on ``work_root`` (the session's carddemo_work_root)
    with a fresh capture, ``commands``, the committed resources.yaml and no
    deadline (the default limit applies)."""
    adapter = UniKixAdapter(work_root=work_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter._capture = evidence.Capture()
    adapter._commands = commands
    adapter._env = object()
    adapter._batch = SimpleNamespace(resources=bpe.Resources.load())
    return adapter


def _fault_plan(
    adapter: UniKixAdapter, scenario: object
) -> tuple[unikix_adapter._Plan, base.MissingPrerequisite | None]:
    """The online plan of ``scenario`` with its faults planned, and the first
    fault ``_plan_faults`` found it cannot provide."""
    plan = unikix_adapter._Plan(
        scenario_id=scenario.id,
        online=True,
        steps=list(scenario.inputs.get("steps") or []),
        sessions=1,
        jobs=(),
        dateparm=None,
    )
    return plan, adapter._plan_faults(scenario, plan)


def _planned(step: int | None, target: str, entry: str, operation: str, after: int | None) -> object:
    placeholder = "queue" if target == "JOBS" else "file"
    return unikix_adapter._OnlineFault(step, target, entry, placeholder, operation, after)


# Each case: the fault, how many steps the scenario has, the entries probed
# available, and the planned fault, or the gap and texts of the
# MissingPrerequisite _plan_faults returns instead.
FAULT_PLANS = [
    pytest.param(
        FAULT_CARD_READ,
        4,
        {FAULT_DISABLE},
        _planned(1, "CARDDAT", FAULT_DISABLE, "read", None),
        id="absent-after-the-sign-on-step",
    ),
    pytest.param(
        {**FAULT_CARD_READ, "target": "USRSEC", "after": 0},
        1,
        {FAULT_DISABLE},
        _planned(0, "USRSEC", FAULT_DISABLE, "read", 0),
        id="zero-before-the-first-step",
    ),
    pytest.param(
        {**FAULT_CARD_READ, "after": 18},
        4,
        {FAULT_DISABLE, FAULT_COUNT},
        _planned(None, "CARDDAT", FAULT_DISABLE, "read", 18),
        id="counted-with-request-count",
    ),
    pytest.param(
        {**FAULT_CARD_READ, "after": 1},
        3,
        {"region_file_close", FAULT_COUNT},
        _planned(None, "CARDDAT", "region_file_close", "read", 1),
        id="counted-closing-for-disabling",
    ),
    pytest.param(
        {"target": "JOBS", "operation": "write", "condition": "unavailable"},
        3,
        {"tdq_disable"},
        _planned(1, "JOBS", "tdq_disable", "write", None),
        id="tdq-after-the-sign-on-step",
    ),
    pytest.param(
        {**FAULT_CARD_READ, "after": 18},
        4,
        {FAULT_DISABLE},
        (FAULT_GAP, (f"{FAULT_COUNT} unavailable: probe found no {FAULT_COUNT}",)),
        id="counted-without-request-count",
    ),
    pytest.param(
        {**FAULT_CARD_READ, "after": 1},
        3,
        set(),
        (FAULT_GAP, (f"{FAULT_COUNT} unavailable", "region_file_close unavailable")),
        id="counted-without-file-control",
    ),
    pytest.param(
        {"target": "JOBS", "operation": "write", "condition": "unavailable", "after": 1},
        3,
        {"tdq_disable", FAULT_COUNT},
        (FAULT_GAP, ("counts requests on the TDQ JOBS",)),
        id="counted-on-the-tdq",
    ),
    pytest.param(
        {**FAULT_CARD_READ, "operation": "open", "after": 2},
        3,
        {FAULT_DISABLE, FAULT_COUNT},
        (FAULT_GAP, (f"{FAULT_COUNT} counts only read, write, rewrite requests",)),
        id="counted-open-requests",
    ),
    pytest.param(
        {**FAULT_CARD_READ, "after": 1},
        1,
        {FAULT_DISABLE, FAULT_COUNT},
        (FAULT_GAP, ("no step boundary follows the first step",)),
        id="counted-with-one-step",
    ),
    pytest.param(
        FAULT_CARD_READ,
        1,
        {FAULT_DISABLE},
        (FAULT_GAP, ("before steps[1] (after the sign-on step)",)),
        id="absent-with-one-step",
    ),
    pytest.param(
        {**FAULT_CARD_READ, "after": 0},
        0,
        {FAULT_DISABLE},
        (FAULT_GAP, ("before steps[0] (with `after` 0)",)),
        id="zero-without-steps",
    ),
    *(
        pytest.param(
            {**FAULT_CARD_READ, "condition": condition, "after": 1},
            5,
            {FAULT_DISABLE, FAULT_COUNT},
            ("GAP-MIS-014", ("no mechanism holds a record lock",)),
            id=condition,
        )
        for condition in ("locked", "io-error", "abend")
    ),
]


@pytest.mark.parametrize(("fault", "steps", "found", "expected"), FAULT_PLANS)
def test_unikix_plans_an_online_fault_from_its_operation_count(
    carddemo_work_root: Path, fault: dict, steps: int, found: set[str], expected: object
) -> None:
    commands = _FaultCommands(frozenset(found))
    adapter = _fault_adapter(commands, carddemo_work_root)
    scenario = SimpleNamespace(
        id=FAULT_SCENARIO,
        inputs={"steps": [{"aid": "ENTER"} for _ in range(steps)]},
        expected={},
        preconditions={"faults": [fault]},
    )
    plan, unprovidable = _fault_plan(adapter, scenario)
    if isinstance(expected, unikix_adapter._OnlineFault):
        assert unprovidable is None
        assert plan.online_faults == [expected]
    else:
        gap_id, texts = expected
        assert isinstance(unprovidable, base.MissingPrerequisite)
        assert unprovidable.gap_id == gap_id
        for text in texts:
            assert text in unprovidable.need
        assert plan.online_faults == []
    assert commands.runs == []


# Each case: the scenario, what region_file_requests prints in turn, the step
# indices before_step is called with (a restart starts again at 0), the step
# indices before which the fault's entry ran, how many counts were read, the
# exception the placement raises (None: none) with its gap and texts, and a
# text the fault note holds.
FAULT_RUNS = [
    pytest.param(
        "CRD-COCRDLIC-recovery-03",
        ("requests 0", "requests 0", "requests 9", "requests 18"),
        (0, 1, 2, 3),
        [3],
        4,
        None,
        "before steps[3]: region_file_disable CARDDAT, after 18 read request(s)",
        id="card-list-before-page-up",
    ),
    pytest.param(
        "CRD-COCRDUPC-recovery-01",
        ("requests 0", "requests 0", "requests 0", "requests 1", "requests 1"),
        (0, 1, 2, 3, 4),
        [3],
        4,
        None,
        "before steps[3]: region_file_disable CARDDAT, after 1 read request(s)",
        id="card-update-before-the-edit",
    ),
    pytest.param(
        "CRD-COCRDLIC-recovery-03",
        ("requests 7", "requests 7", "requests 16", "requests 25"),
        (0, 1, 2, 3),
        [3],
        4,
        None,
        f"{FAULT_COUNT} 7 before steps[0], 25 before steps[3]",
        id="served-since-the-first-step",
    ),
    pytest.param(
        "CRD-COCRDLIC-recovery-03",
        (
            "requests 0", "requests 0", "requests 9", "requests 18",
            "requests 40", "requests 40", "requests 49", "requests 58",
        ),
        (0, 1, 2, 3, 0, 1, 2, 3),
        [3, 3],
        8,
        None,
        f"{FAULT_COUNT} 40 before steps[0], 58 before steps[3]",
        id="restart-takes-a-new-baseline",
    ),
    pytest.param(
        "CRD-COCRDLIC-recovery-03",
        ("requests 0", "requests 0", "requests 9", "requests 19"),
        (0, 1, 2, 3),
        [],
        4,
        (
            base.MissingPrerequisite,
            FAULT_GAP,
            ("19 were served by the start of steps[3]", "request 19 was served inside steps[2]"),
        ),
        None,
        id="served-past-after",
    ),
    pytest.param(
        "CRD-COCRDLIC-recovery-03",
        ("requests 0", "requests 0", "requests 9", "requests 17"),
        (0, 1, 2, 3),
        [],
        4,
        (
            base.MissingPrerequisite,
            FAULT_GAP,
            ("only 17 were served by the start of the last step, steps[3]",),
        ),
        None,
        id="after-never-reached",
    ),
    pytest.param(
        "CRD-COCRDLIC-recovery-03",
        ("requests 0", "requests 0", "requests 9"),
        (0, 1, 2),
        [],
        3,
        None,
        "the steps stopped at steps[2], before the last step, steps[3]",
        id="steps-stopped-before-the-boundary",
    ),
    pytest.param(
        "CRD-COCRDLIC-recovery-03",
        ("requests 0", "the region answered nothing"),
        (0, 1),
        [],
        2,
        (base.AdapterUnavailable, FAULT_GAP, ("printed no count",)),
        None,
        id="unreadable-count",
    ),
    pytest.param(
        "CRD-COCRDLIC-recovery-03",
        ("requests 0", "requests 4\nrequests 5\n"),
        (0, 1),
        [],
        2,
        (base.AdapterUnavailable, FAULT_GAP, ("printed 2 count(s)",)),
        None,
        id="two-counts",
    ),
    pytest.param(
        "CRD-COCRDLIC-recovery-03",
        ("requests 5", "requests 5", "requests 4"),
        (0, 1, 2),
        [],
        3,
        (base.AdapterUnavailable, FAULT_GAP, ("fell from 5 before steps[0] to 4 before steps[2]",)),
        None,
        id="count-below-the-baseline",
    ),
    pytest.param(
        "USR-COUSR02C-recovery-01",
        (),
        (0, 1, 2),
        [1],
        0,
        None,
        "before steps[1]: region_file_disable USRSEC",
        id="absent-after-the-sign-on-step",
    ),
    pytest.param(
        "SEC-COSGN00C-recovery-01",
        (),
        (0,),
        [0],
        0,
        None,
        "before steps[0]: region_file_disable USRSEC",
        id="zero-before-the-only-step",
    ),
]


@pytest.mark.parametrize(
    ("scenario_id", "counts", "indices", "applied", "reads", "raised", "noted"), FAULT_RUNS
)
def test_unikix_applies_an_online_fault_after_its_operation_count(
    carddemo_work_root: Path,
    scenario_id: str,
    counts: tuple[str, ...],
    indices: tuple[int, ...],
    applied: list[int],
    reads: int,
    raised: tuple | None,
    noted: str | None,
) -> None:
    commands = _FaultCommands(frozenset({FAULT_DISABLE, FAULT_COUNT}), counts)
    adapter = _fault_adapter(commands, carddemo_work_root)
    plan, unprovidable = _fault_plan(adapter, scenarios.get(scenario_id))
    assert unprovidable is None
    (fault,) = plan.online_faults
    placement = adapter._fault_applier(plan)
    assert placement is not None
    before: list[int] = []

    def disabled() -> int:
        return sum(entry_id == FAULT_DISABLE for entry_id, _ in commands.runs)

    def drive() -> None:
        # As tpe.drive calls before_step, then the check made once it returns.
        for index in indices:
            ran = disabled()
            placement(index)
            if disabled() > ran:
                before.append(index)
        placement.finish()

    if raised is None:
        drive()
    else:
        exception, gap_id, texts = raised
        with pytest.raises(exception) as caught:
            drive()
        assert caught.value.gap_id == gap_id
        for text in texts:
            assert text in str(caught.value)
    assert before == applied
    assert [values for entry_id, values in commands.runs if entry_id == FAULT_COUNT] == [
        {"file": fault.target, "operation": fault.operation}
    ] * reads
    assert [values for entry_id, values in commands.runs if entry_id == FAULT_DISABLE] == [
        {"file": fault.target}
    ] * len(applied)
    if noted is not None:
        assert noted in adapter._capture.sysout[FAULT_NOTE]


# jobs.run_program cases: a scenario working directory and a build cache under
# a work root of each case's own (launch_root); the units are /bin/sh scripts.
LAUNCH_ROOT_PREFIX = "unit-launch-"
LAUNCH_WORK_ROOT = "work"
LAUNCH_SCENARIO = "SCN-LAUNCH"
LAUNCH_BUILD_KEY = "0" * 64
LAUNCH_DEADLINE_S = 60
LAUNCH_DISPLAYS = (
    "START OF EXECUTION OF PROGRAM CBACT01C",
    "END OF EXECUTION OF PROGRAM CBACT01C",
)

# Runs that end in HarnessError: the unit, its stderr as libcob 3.2 or ld.so
# writes it, how the script ends, what the error says, the stderr line the
# evidence keeps, and command.yaml's ending.libcob_error. CBACT01C calls
# CEE3ABD at line 173 [app/cbl/CBACT01C.cbl:173].
LAUNCH_HARNESS_FAILURES = [
    pytest.param(
        "CBACT01C",
        "libcob: error: module 'CEE3ABD' not found\n",
        "exit 1",
        "CBACT01C: libcob ended the run: libcob: error: module 'CEE3ABD' not found "
        "(exit status 1; evidence evidence)",
        "libcob: error: module 'CEE3ABD' not found",
        "libcob: error: module 'CEE3ABD' not found",
        id="module-not-found",
    ),
    pytest.param(
        "CBACT01C",
        "libcob: CBACT01C.cbl:173: error: module 'CEE3ABD' not found\n\n"
        ' Last statement of "CBACT01C" was CALL at line 173 of CBACT01C.cbl\n',
        "exit 1",
        "CBACT01C: libcob ended the run: libcob: CBACT01C.cbl:173: error: module 'CEE3ABD' "
        "not found (exit status 1; evidence evidence)",
        "libcob: CBACT01C.cbl:173: error: module 'CEE3ABD' not found",
        "libcob: CBACT01C.cbl:173: error: module 'CEE3ABD' not found",
        id="module-not-found-with-location",
    ),
    # No shim is built, so ld.so itself reports the LD_PRELOAD object it cannot
    # load; the script then dies on a signal, as CBSTM03A faults without it.
    pytest.param(
        "CBSTM03A",
        "",
        "kill -9 $$",
        f"CBSTM03A: the page-zero shim was not preloaded: ERROR: ld.so: object "
        f"'../_build/{LAUNCH_BUILD_KEY}/lib/psa_tiot_shim.so' from LD_PRELOAD cannot be "
        f"preloaded",
        "from LD_PRELOAD cannot be preloaded",
        None,
        id="shim-not-preloaded",
    ),
]

# Work roots whose path holds a separator ld.so splits LD_PRELOAD at.
LAUNCH_SEPARATOR_ROOTS = [
    pytest.param("work root", id="space"),
    pytest.param("work:root", id="colon"),
]
# datasets.runtime_env cases, inside launch_root: the work root holding the
# build cache, the directory the run starts in, the COB_LIBRARY_PATH it gets,
# and whether that path is refused because libcob would split it at the
# colon. The run starts in the scenario working directory (run_program), its
# data directory (DatasetStore loaders and unloaders), the work root
# (page0_probe), or inside the build cache.
MODULE_PATH_ROOT = "work sp:ace"
MODULE_PATH_CASES = [
    pytest.param(
        MODULE_PATH_ROOT, f"{MODULE_PATH_ROOT}/{LAUNCH_SCENARIO}",
        f"../_build/{LAUNCH_BUILD_KEY}/modules", False, id="scenario-workdir",
    ),
    pytest.param(
        MODULE_PATH_ROOT, f"{MODULE_PATH_ROOT}/{LAUNCH_SCENARIO}/data",
        f"../../_build/{LAUNCH_BUILD_KEY}/modules", False, id="data-dir",
    ),
    pytest.param(
        MODULE_PATH_ROOT, MODULE_PATH_ROOT, f"_build/{LAUNCH_BUILD_KEY}/modules", False,
        id="work-root",
    ),
    pytest.param(
        MODULE_PATH_ROOT, f"{MODULE_PATH_ROOT}/_build/{LAUNCH_BUILD_KEY}", "./modules", False,
        id="build-dir-bare-name",
    ),
    pytest.param(
        "cache sp:ace", f"{MODULE_PATH_ROOT}/{LAUNCH_SCENARIO}",
        f"../../cache sp:ace/_build/{LAUNCH_BUILD_KEY}/modules", True,
        id="cache-outside-the-work-root-refused",
    ),
]
LAUNCH_CC_TIMEOUT_S = 120
# The folder beside the library that cc runs in, with it as cc's TMPDIR.
LAUNCH_CC_SCRATCH_PREFIX = "cc-tmp-"

# Names no JCL name can be, put in CBSTM03A's programs.yaml tiot as its job,
# its step or an added ddname: the shim would copy them into the TIOT whose
# names CBSTM03A DISPLAYs, splitting, forging or truncating message lines.
LAUNCH_BAD_TIOT_NAMES = [
    pytest.param("dds", "\nlibcob:", id="dd-newline-libcob-prefix"),
    pytest.param("dds", "A\nCARDDEMO-ABEND CEE3ABD", id="dd-newline-abend-marker"),
    pytest.param("dds", "AB\x00CD", id="dd-nul"),
    pytest.param("dds", "VERYLONGDDNAME16", id="dd-over-8-characters"),
    pytest.param("job", "CREA\x1bSTMT", id="job-escape"),
    pytest.param("step", "ST\nEP040", id="step-newline"),
    pytest.param("step", "STEP040\r", id="step-carriage-return"),
    pytest.param("dds", "sysout", id="dd-lowercase"),
    pytest.param("step", "1STEP", id="step-leading-digit"),
]

# Runs that end as the program ended: stderr ({workdir} is the working
# directory), exit status, then return code, abended, and the libcob_error
# command.yaml shows. CBACT01C's OPEN and its other file verbs check a FILE
# STATUS, so the first case stands for a unit whose file declares none, as
# CBSTM03A's STMTFILE and HTMLFILE do [app/cbl/CBSTM03A.CBL:39-40].
LAUNCH_PROGRAM_ENDINGS = [
    pytest.param(
        "libcob: error: permanent file error (status = 30) for file ACCTFILE-FILE "
        "('DD_ACCTFILE' => {workdir}/data/ACCTFILE)\n",
        1,
        None,
        True,
        "libcob: error: permanent file error (status = 30) for file ACCTFILE-FILE "
        "('DD_ACCTFILE' => data/ACCTFILE)",
        id="file-without-file-status-abends",
    ),
    pytest.param(
        "libcob: warning: implicit CLOSE of ACCTFILE-FILE ('ACCTFILE')\n",
        4,
        4,
        False,
        None,
        id="business-return-code-with-a-warning",
    ),
]


@pytest.fixture
def launch_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, given by its real
    path and removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=LAUNCH_ROOT_PREFIX, dir=carddemo_work_root)).resolve()
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


class _LaunchCache:
    """The parts of a build cache ``jobs.run_program`` reads, laid out as
    ``build.py`` lays them out under ``<work root>/_build/<key>/``. Each
    unit's executable is a /bin/sh script that prints ``<unit>.stdout`` and
    ``<unit>.stderr`` beside it, then runs its ending; no shim is built."""

    clock_fraction_mode = "hundredths"
    cobc_bin_dir = Path("/usr/bin")
    lib_dirs: tuple[Path, ...] = ()

    def __init__(self, work_root: Path) -> None:
        self.dir = work_root / "_build" / LAUNCH_BUILD_KEY
        self.module_dir = self.dir / "modules"
        self.parmdrv = self.dir / "bin" / "PARMDRV"
        self.dtcdrv = self.dir / "bin" / "DTCDRV"
        self.shim = self.dir / "lib" / "psa_tiot_shim.so"

    def executable(self, unit: str) -> Path:
        return self.dir / "bin" / unit

    def script(self, unit: str, stdout: str, stderr: str, ending: str) -> None:
        path = self.executable(unit)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.with_name(f"{unit}.stdout").write_text(stdout, encoding="latin-1")
        path.with_name(f"{unit}.stderr").write_text(stderr, encoding="latin-1")
        path.write_text(f'#!/bin/sh\ncat "$0.stdout"\ncat "$0.stderr" >&2\n{ending}\n')
        path.chmod(0o755)


def _launch_case(launch_root: Path, work_root_name: str) -> tuple[_LaunchCache, Path]:
    """The build cache and the scenario working directory of a work root
    named ``work_root_name`` inside ``launch_root``."""
    work_root = launch_root / work_root_name
    workdir = work_root / LAUNCH_SCENARIO
    workdir.mkdir(parents=True)
    return _LaunchCache(work_root), workdir


def _launch(
    cache: _LaunchCache, unit: str, workdir: Path, *, tiot: Mapping[str, object] | None = None
) -> jobs.ProgramOutcome:
    """Run ``unit`` with a path in ``<workdir>/data`` for every dd it and the
    units it calls declare, none of which exists, and ``tiot`` when given."""
    config = jobs.unit_config(unit)
    dd_paths = {
        ddname: workdir / "data" / ddname
        for ddname, _ in datasets.program_dds(config, jobs.called_configs(unit))
    }
    return jobs.run_program(
        cache, unit, dd_paths, workdir=workdir, deadline=time.monotonic() + LAUNCH_DEADLINE_S,
        tiot=tiot,
    )


def _command(workdir: Path) -> dict:
    return load_yaml(workdir / jobs.EVIDENCE_SUBDIR / "command.yaml")


@pytest.mark.parametrize(
    ("unit", "stderr", "ending", "raised", "kept", "libcob_error"), LAUNCH_HARNESS_FAILURES
)
def test_run_program_raises_for_a_harness_failure_once_the_evidence_is_written(
    launch_root: Path,
    unit: str,
    stderr: str,
    ending: str,
    raised: str,
    kept: str,
    libcob_error: str | None,
) -> None:
    cache, workdir = _launch_case(launch_root, LAUNCH_WORK_ROOT)
    cache.script(unit, "".join(f"{line}\n" for line in LAUNCH_DISPLAYS), stderr, ending)
    with pytest.raises(HarnessError) as failure:
        _launch(cache, unit, workdir)
    assert raised in str(failure.value)
    evidence = workdir / jobs.EVIDENCE_SUBDIR
    assert kept in (evidence / "stderr.txt").read_text(encoding="latin-1")
    assert _command(workdir)["ending"]["libcob_error"] == libcob_error


@pytest.mark.parametrize(
    ("stderr", "status", "return_code", "abended", "libcob_error"), LAUNCH_PROGRAM_ENDINGS
)
def test_run_program_ends_a_run_as_the_program_ended(
    launch_root: Path,
    stderr: str,
    status: int,
    return_code: int | None,
    abended: bool,
    libcob_error: str | None,
) -> None:
    cache, workdir = _launch_case(launch_root, LAUNCH_WORK_ROOT)
    displays = "".join(f"{line}\n" for line in LAUNCH_DISPLAYS)
    cache.script("CBACT01C", displays, stderr.format(workdir=workdir), f"exit {status}")
    outcome = _launch(cache, "CBACT01C", workdir)
    assert (outcome.return_code, outcome.abended, outcome.signal) == (return_code, abended, None)
    assert outcome.messages == outcome.displays == list(LAUNCH_DISPLAYS)
    command = _command(workdir)
    assert command["ending"] == {
        "cee3abd_marker": False,
        "libcob_error": libcob_error,
        "returncode": status,
        "signal": None,
    }
    assert "LD_PRELOAD" not in command["env"]


def _build_empty_library(path: Path) -> None:
    """An empty shared library at ``path``, which preloading leaves without
    effect, built with ``cc`` as ``build.py`` builds the real shim. cc runs
    with its working directory and ``TMPDIR`` in a folder of its own beside
    the library, removed afterwards, so its temporaries stay inside the work
    root, as ``build.py`` confines its compiles."""
    compiler = shutil.which("cc")
    assert compiler is not None, "cc, which builds the stubs, is not on PATH"
    path = path.absolute()
    path.parent.mkdir(parents=True, exist_ok=True)
    source = path.with_suffix(".c")
    source.write_text("int carddemo_unit_preloaded;\n")
    scratch = Path(tempfile.mkdtemp(prefix=LAUNCH_CC_SCRATCH_PREFIX, dir=path.parent))
    try:
        subprocess.run(
            [compiler, "-shared", "-fPIC", "-o", os.fspath(path), os.fspath(source)],
            check=True,
            capture_output=True,
            timeout=LAUNCH_CC_TIMEOUT_S,
            cwd=scratch,
            env={**os.environ, "TMPDIR": os.fspath(scratch)},
        )
    finally:
        shutil.rmtree(scratch)


@pytest.mark.parametrize("work_root_name", LAUNCH_SEPARATOR_ROOTS)
def test_run_program_preloads_the_shim_by_a_path_ld_so_keeps_whole(
    launch_root: Path, work_root_name: str
) -> None:
    cache, workdir = _launch_case(launch_root, work_root_name)
    _build_empty_library(cache.shim)
    cache.script("CBSTM03A", "", "", 'printf "%s\\n" "$LD_PRELOAD"')
    outcome = _launch(cache, "CBSTM03A", workdir)
    assert (outcome.return_code, outcome.abended, outcome.stderr) == (0, False, b"")
    [preload] = outcome.displays
    assert "/" in preload and ":" not in preload and not any(c.isspace() for c in preload)
    assert (workdir / preload).resolve() == cache.shim.resolve()
    command = _command(workdir)
    assert command["env"]["LD_PRELOAD"] == "<build>/lib/psa_tiot_shim.so"
    text = (workdir / jobs.EVIDENCE_SUBDIR / "command.yaml").read_text()
    assert os.fspath(launch_root) not in text


@pytest.mark.parametrize("cache_root_name", LAUNCH_SEPARATOR_ROOTS)
def test_run_program_refuses_a_shim_path_ld_so_would_split(
    launch_root: Path, cache_root_name: str
) -> None:
    """A build cache outside the work root, on a path with a separator, leaves
    one in the shim's path from the working directory: refused unrun."""
    _, workdir = _launch_case(launch_root, LAUNCH_WORK_ROOT)
    cache = _LaunchCache(launch_root / cache_root_name)
    with pytest.raises(HarnessError, match="at which ld.so splits LD_PRELOAD"):
        _launch(cache, "CBSTM03A", workdir)
    assert not (workdir / jobs.EVIDENCE_SUBDIR).exists()


@pytest.mark.parametrize(("cache_root", "cwd", "modules", "refused"), MODULE_PATH_CASES)
def test_runtime_env_names_the_modules_by_a_path_libcob_keeps_whole(
    launch_root: Path, cache_root: str, cwd: str, modules: str, refused: bool
) -> None:
    """COB_LIBRARY_PATH is the module directory's path from the run's cwd, so
    the colon and the space of the work root's path never reach it; a path
    that still holds a colon is refused, naming it."""
    cache = _LaunchCache(launch_root / cache_root)
    cache.module_dir.mkdir(parents=True)
    directory = launch_root / cwd
    directory.mkdir(parents=True, exist_ok=True)
    if refused:
        with pytest.raises(datasets.DatasetError, match=re.escape(repr(modules))) as failure:
            datasets.runtime_env(cache, cwd=directory)
        assert "at which libcob splits COB_LIBRARY_PATH" in str(failure.value)
        return
    library_path = datasets.runtime_env(cache, cwd=directory)["COB_LIBRARY_PATH"]
    assert library_path == modules
    assert (directory / library_path).resolve() == cache.module_dir.resolve()


@pytest.mark.parametrize(("field", "name"), LAUNCH_BAD_TIOT_NAMES)
def test_run_program_refuses_a_tiot_name_that_is_no_jcl_name(
    launch_root: Path, field: str, name: str
) -> None:
    """A job, step or ddname for the TIOT that is no JCL name is refused
    unrun, the error showing it escaped."""
    cache, workdir = _launch_case(launch_root, LAUNCH_WORK_ROOT)
    tiot = dict(jobs.unit_config("CBSTM03A")["tiot"])
    tiot[field] = [*tiot["dds"], name] if field == "dds" else name
    with pytest.raises(HarnessError, match="must be a JCL name") as refusal:
        _launch(cache, "CBSTM03A", workdir, tiot=tiot)
    assert repr(name) in str(refusal.value)
    assert not (workdir / jobs.EVIDENCE_SUBDIR).exists()


# build._complete_mode and BuildCache profile cases: a build directory laid out
# as build.py lays it out, under a work root of each case's own (cache_root);
# nothing is compiled.
CACHE_ROOT_PREFIX = "unit-build-cache-"
CACHE_KEY = "c" * 64
CACHE_MODE = "nanoseconds"
CACHE_STATIC_PROFILE = build.KeyProfile(50, (0, 16))
CACHE_PROFILE = build.KeyProfile(80, (0, 8))  # not static: built on demand
# The files of the build directory by their path in it, a static profile's
# COMPLETE among them; build._artifact_hashes records every one.
CACHE_ARTIFACTS = {
    "bin/CBTRN02C": b"\x7fELF CBTRN02C " * 64,
    "modules/CEE3ABD.so": b"\x7fELF CEE3ABD.so " * 32,
    f"profiles/{CACHE_STATIC_PROFILE.name}/KSLOAD": b"\x7fELF KSLOAD " * 16,
    f"profiles/{CACHE_STATIC_PROFILE.name}/COMPLETE": b"",
}
# The files of a profile directory by their path in it.
CACHE_PROFILE_ARTIFACTS = {
    "KSLOAD": b"\x7fELF KSLOAD " * 16,
    "KSUNLOAD": b"\x7fELF KSUNLOAD " * 16,
    "logs/KSLOAD.log": b"command: cobc -x\n",
}


def _truncate_artifact(directory: Path, manifest: dict) -> None:
    os.truncate(directory / "bin" / "CBTRN02C", 100)


def _alter_artifact_byte(directory: Path, manifest: dict) -> None:
    path = directory / "modules" / "CEE3ABD.so"
    data = bytearray(path.read_bytes())
    data[10] ^= 0xFF
    path.write_bytes(bytes(data))


def _delete_listed_artifact(directory: Path, manifest: dict) -> None:
    (directory / "profiles" / CACHE_STATIC_PROFILE.name / "KSLOAD").unlink()


def _drop_artifacts(directory: Path, manifest: dict) -> None:
    del manifest["artifacts"]


def _empty_artifacts(directory: Path, manifest: dict) -> None:
    manifest["artifacts"] = {}


def _list_escaping_artifact(directory: Path, manifest: dict) -> None:
    """A path out of the directory with ``..``, naming a file whose digest it
    records truly."""
    outside = directory.parent / "outside.so"
    outside.write_bytes(b"\x7fELF outside")
    manifest["artifacts"][f"../{outside.name}"] = sha256_file(outside)


def _list_absolute_artifact(directory: Path, manifest: dict) -> None:
    path = directory / "bin" / "CBTRN02C"
    manifest["artifacts"][os.fspath(path)] = manifest["artifacts"].pop("bin/CBTRN02C")


def _upper_case_digest(directory: Path, manifest: dict) -> None:
    manifest["artifacts"]["bin/CBTRN02C"] = manifest["artifacts"]["bin/CBTRN02C"].upper()


# What makes a build directory stale: its files changed after the manifest
# recorded them, or a manifest whose artifacts are missing or malformed.
CACHE_CORRUPTIONS = [
    pytest.param(_truncate_artifact, id="truncated-artifact"),
    pytest.param(_alter_artifact_byte, id="one-byte-altered"),
    pytest.param(_delete_listed_artifact, id="listed-artifact-deleted"),
    pytest.param(_drop_artifacts, id="manifest-without-artifacts"),
    pytest.param(_empty_artifacts, id="empty-artifacts"),
    pytest.param(_list_escaping_artifact, id="path-escaping-with-dotdot"),
    pytest.param(_list_absolute_artifact, id="absolute-path"),
    pytest.param(_upper_case_digest, id="digest-not-lowercase-hex"),
]


def _truncate_profile_loader(directory: Path) -> None:
    os.truncate(directory / "KSLOAD", 3)


def _remove_profile_manifest(directory: Path) -> None:
    (directory / "manifest.yaml").unlink()


# What makes an on-demand profile unsound once it was built.
CACHE_PROFILE_CORRUPTIONS = [
    pytest.param(_truncate_profile_loader, id="truncated-loader"),
    pytest.param(_remove_profile_manifest, id="manifest-missing"),
]


@pytest.fixture
def cache_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, given by its real
    path and removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=CACHE_ROOT_PREFIX, dir=carddemo_work_root)).resolve()
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def _write_tree(directory: Path, files: Mapping[str, bytes]) -> None:
    for relative, data in files.items():
        path = directory / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def _cache_directory(
    cache_root: Path, corrupt: Callable[[Path, dict], None] | None = None
) -> Path:
    """``<cache_root>/_build/<CACHE_KEY>/`` holding ``CACHE_ARTIFACTS``, its
    manifest recording their hashes with ``build._artifact_hashes`` as
    ``_populate`` does, then COMPLETE. ``corrupt`` changes the files or the
    manifest in between."""
    directory = cache_root / "_build" / CACHE_KEY
    _write_tree(directory, CACHE_ARTIFACTS)
    manifest = {
        "artifacts": build._artifact_hashes(directory),
        "clock_fraction_mode": CACHE_MODE,
        "key": CACHE_KEY,
    }
    if corrupt is not None:
        corrupt(directory, manifest)
    (directory / "manifest.yaml").write_text(dump_yaml(manifest), encoding="utf-8")
    (directory / "COMPLETE").write_text("", encoding="utf-8")
    return directory


def _write_profile(directory: Path) -> None:
    """A sound on-demand profile ``CACHE_PROFILE`` at ``directory``, laid out
    as ``BuildCache._build_profile`` publishes it."""
    _write_tree(directory, CACHE_PROFILE_ARTIFACTS)
    manifest = {"artifacts": build._artifact_hashes(directory), "profile": CACHE_PROFILE.name}
    (directory / "manifest.yaml").write_text(dump_yaml(manifest), encoding="utf-8")
    (directory / "COMPLETE").write_text("", encoding="utf-8")


def _stand_in_cache(directory: Path) -> build.BuildCache:
    """A ``BuildCache`` on ``directory`` whose toolchain and plan are
    stand-ins holding what its constructor reads, with one static profile."""
    toolchain = SimpleNamespace(cobc="/usr/bin/cobc", cobc_version="cobc (GnuCOBOL) 3.2.0",
                                lib_dirs=())
    plan = SimpleNamespace(units={}, compiles=(), profiles=(CACHE_STATIC_PROFILE,))
    return build.BuildCache(
        key=CACHE_KEY, directory=directory, repo_root=Path(_TESTS_ROOT).parent,
        toolchain=toolchain, plan=plan, clock_fraction_mode=CACHE_MODE,
    )


def test_complete_mode_reuses_a_build_directory_whose_artifacts_all_match(
    cache_root: Path,
) -> None:
    directory = _cache_directory(cache_root)
    # A file the manifest does not list, as a profile built on demand later.
    later = f"profiles/{CACHE_PROFILE.name}/KSLOAD"
    _write_tree(directory, {later: b"\x7fELF later"})
    # The directory's own manifest and COMPLETE are never artifacts; a
    # profile's COMPLETE is one.
    assert sorted(build._artifact_hashes(directory)) == sorted([*CACHE_ARTIFACTS, later])
    assert build._complete_mode(directory, CACHE_KEY) == CACHE_MODE
    assert {relative: (directory / relative).read_bytes() for relative in CACHE_ARTIFACTS} == (
        CACHE_ARTIFACTS
    )
    assert list(directory.parent.glob(f"{CACHE_KEY}.stale-*")) == []


@pytest.mark.parametrize("corrupt", CACHE_CORRUPTIONS)
def test_complete_mode_discards_a_build_directory_whose_artifacts_do_not_match(
    cache_root: Path, corrupt: Callable[[Path, dict], None]
) -> None:
    directory = _cache_directory(cache_root, corrupt)
    assert build._complete_mode(directory, CACHE_KEY) is None
    assert not os.path.lexists(directory)
    assert list(directory.parent.glob(f"{CACHE_KEY}.stale-*")) == []


@pytest.mark.parametrize("corrupt", CACHE_PROFILE_CORRUPTIONS)
def test_build_cache_rebuilds_an_unsound_on_demand_profile_once(
    cache_root: Path, monkeypatch: pytest.MonkeyPatch, corrupt: Callable[[Path], None]
) -> None:
    directory = _cache_directory(cache_root)
    profile_dir = directory / "profiles" / CACHE_PROFILE.name
    _write_profile(profile_dir)
    corrupt(profile_dir)
    checks: list[str] = []
    builds: list[str] = []
    profile_intact = build._profile_intact

    def counted_check(path: Path, name: str) -> bool:
        checks.append(name)
        return profile_intact(path, name)

    def build_profile(cache: build.BuildCache, profile: build.KeyProfile, target: Path) -> None:
        # The unsound copy is gone before the build: _publish would keep it.
        assert not os.path.lexists(target)
        builds.append(profile.name)
        _write_profile(target)

    monkeypatch.setattr(build, "_profile_intact", counted_check)
    monkeypatch.setattr(build.BuildCache, "_build_profile", build_profile)
    cache = _stand_in_cache(directory)
    assert cache.loader(CACHE_PROFILE) == profile_dir / "KSLOAD"
    assert builds == [CACHE_PROFILE.name]
    assert (profile_dir / "KSLOAD").read_bytes() == CACHE_PROFILE_ARTIFACTS["KSLOAD"]
    assert list(profile_dir.parent.glob(f"{CACHE_PROFILE.name}.stale-*")) == []
    checked = len(checks)
    assert cache.loader(CACHE_PROFILE) == profile_dir / "KSLOAD"
    assert cache.unloader(CACHE_PROFILE) == profile_dir / "KSUNLOAD"
    static_dir = directory / "profiles" / CACHE_STATIC_PROFILE.name
    assert cache.loader(CACHE_STATIC_PROFILE) == static_dir / "KSLOAD"
    assert (builds, len(checks)) == ([CACHE_PROFILE.name], checked)
    # Another session's cache checks the rebuilt profile once and keeps it.
    later = _stand_in_cache(directory)
    assert later.loader(CACHE_PROFILE) == profile_dir / "KSLOAD"
    assert later.unloader(CACHE_PROFILE) == profile_dir / "KSUNLOAD"
    assert (builds, checks[checked:]) == ([CACHE_PROFILE.name], [CACHE_PROFILE.name])


# Build cache containment: a component of the build cache planted in a work
# root of the case's own (cache_root/work) as a symbolic link aimed at a
# directory outside it (cache_root/outside), as a file of the wrong kind, or
# writable by group or others. ensure_built runs on stand-ins; nothing is
# compiled.
CACHE_LOCK = f"_build/{CACHE_KEY}.lock"


def _link_build_root(work: Path, outside: Path) -> Path:
    link = work / "_build"
    link.symlink_to(outside, target_is_directory=True)
    return link


def _build_root_of_mode(mode: int) -> Callable[[Path, Path], Path]:
    def plant(work: Path, outside: Path) -> Path:
        build_root = work / "_build"
        build_root.mkdir()
        build_root.chmod(mode)
        return build_root

    return plant


def _file_build_root(work: Path, outside: Path) -> Path:
    path = work / "_build"
    path.write_bytes(b"")
    return path


def _link_key_lock(work: Path, outside: Path) -> Path:
    """``<key>.lock`` linked to a path outside that does not exist."""
    (work / "_build").mkdir(mode=0o700)
    link = work / CACHE_LOCK
    link.symlink_to(outside / "planted.lock")
    return link


def _link_build_directory(work: Path, outside: Path) -> Path:
    """``<key>`` linked to a complete cache outside whose hashes all match,
    which ``_complete_mode`` would reuse were it followed."""
    (work / "_build").mkdir(mode=0o700)
    link = work / "_build" / CACHE_KEY
    link.symlink_to(_cache_directory(outside), target_is_directory=True)
    return link


def _file_build_directory(work: Path, outside: Path) -> Path:
    (work / "_build").mkdir(mode=0o700)
    path = work / "_build" / CACHE_KEY
    path.write_bytes(b"")
    return path


# What ensure_built refuses, and the start of its message, of the planted path.
CACHE_CONTAINMENT_PLANTS = [
    pytest.param(_link_build_root, "the build root {} is a symbolic link; ", id="build-root-linked"),
    pytest.param(
        _build_root_of_mode(0o770), "the build root {} is writable by group or others (mode 0770)",
        id="build-root-group-writable",
    ),
    pytest.param(
        _build_root_of_mode(0o757), "the build root {} is writable by group or others (mode 0757)",
        id="build-root-world-writable",
    ),
    pytest.param(_file_build_root, "the build root {} is not a directory; ", id="build-root-a-file"),
    pytest.param(_link_key_lock, "the build lock {} is a symbolic link; ", id="key-lock-linked"),
    pytest.param(
        _link_build_directory, "the build directory {} is a symbolic link; ",
        id="build-directory-linked-to-a-complete-cache",
    ),
    pytest.param(
        _file_build_directory, "the build directory {} is not a directory; ",
        id="build-directory-a-file",
    ),
]


def _stand_in_build(monkeypatch: pytest.MonkeyPatch) -> list[Path]:
    """Stand-ins for the toolchain, the plan and the build key (CACHE_KEY), a
    ``_populate`` that writes the directory ``_cache_directory`` lays out into
    its workspace, and fresh memos, so nothing this case builds reaches the
    session's adapters. Returns the workspaces populated, in order."""
    populated: list[Path] = []

    def populate(workspace: Path, *_: object) -> None:
        populated.append(workspace)
        _write_tree(workspace, CACHE_ARTIFACTS)
        manifest = {
            "artifacts": build._artifact_hashes(workspace),
            "clock_fraction_mode": CACHE_MODE,
            "key": CACHE_KEY,
        }
        (workspace / "manifest.yaml").write_text(dump_yaml(manifest), encoding="utf-8")
        (workspace / "COMPLETE").write_text("", encoding="utf-8")

    toolchain = SimpleNamespace(cobc="/usr/bin/cobc", cobc_version="cobc (GnuCOBOL) 3.2.0",
                                lib_dirs=())
    plan = SimpleNamespace(units={}, compiles=(), profiles=(CACHE_STATIC_PROFILE,))
    monkeypatch.setattr(build, "_toolchain", lambda: toolchain)
    monkeypatch.setattr(build, "_plan", lambda _root: plan)
    monkeypatch.setattr(build, "_build_key", lambda *_: CACHE_KEY)
    monkeypatch.setattr(build, "_populate", populate)
    monkeypatch.setattr(build, "_CACHES", {})
    monkeypatch.setattr(build, "_BUILD_FAILURES", {})
    return populated


@pytest.mark.parametrize(("plant", "refusal"), CACHE_CONTAINMENT_PLANTS)
def test_ensure_built_refuses_an_unsound_build_cache_component(
    cache_root: Path, monkeypatch: pytest.MonkeyPatch,
    plant: Callable[[Path, Path], Path], refusal: str,
) -> None:
    repo_root, work_root, outside = (cache_root / name for name in ("repo", "work", "outside"))
    for directory in (repo_root, work_root, outside):
        directory.mkdir()
    planted = plant(work_root, outside)
    planted_mode = os.lstat(planted).st_mode
    work_before, outside_before = _tree(work_root), _tree(outside)
    populated = _stand_in_build(monkeypatch)
    with pytest.raises(build.BuildError) as error:
        build.ensure_built(repo_root, work_root)
    assert not isinstance(error.value, build.ToolchainUnavailable)
    assert str(error.value).startswith(refusal.format(planted)), str(error.value)
    # Nothing populated or memoized, and nothing outside created or changed:
    # no lock, no temporary workspace, the planted cache byte for byte.
    assert (populated, build._BUILD_FAILURES, build._CACHES) == ([], {}, {})
    assert _tree(outside) == outside_before
    # The work root is left as planted, the link in place, but for the key
    # lock taken once _build was admitted.
    work_after = _tree(work_root)
    if CACHE_LOCK not in work_before and work_after.get(CACHE_LOCK) == b"":
        del work_after[CACHE_LOCK]
    assert work_after == work_before
    assert os.lstat(planted).st_mode == planted_mode


@pytest.mark.parametrize("existing_mode", [
    pytest.param(None, id="fresh-work-root"),
    pytest.param(0o755, id="existing-0755-build-root"),
])
def test_ensure_built_admits_a_private_build_root(
    cache_root: Path, monkeypatch: pytest.MonkeyPatch, existing_mode: int | None
) -> None:
    repo_root, work_root = cache_root / "repo", cache_root / "work"
    build_root = work_root / "_build"
    repo_root.mkdir()
    if existing_mode is not None:
        build_root.mkdir(parents=True)
        build_root.chmod(existing_mode)
    populated = _stand_in_build(monkeypatch)
    cache = build.ensure_built(repo_root, work_root)
    assert (cache.dir, cache.clock_fraction_mode) == (build_root / CACHE_KEY, CACHE_MODE)
    assert [workspace.parent for workspace in populated] == [build_root]
    # The permission bits; a set-group-ID work root passes its bit on.
    assert os.lstat(build_root).st_mode & 0o777 == (existing_mode or 0o700)
    assert sorted(os.listdir(build_root)) == [CACHE_KEY, f"{CACHE_KEY}.lock"]
    assert build.ensure_built(repo_root, work_root) is cache
    assert len(populated) == 1


def _link_profiles(directory: Path, outside: Path) -> Path:
    """``profiles/`` moved outside, a sound on-demand profile added to it
    there, and linked from the cache directory."""
    profiles = directory / "profiles"
    moved = outside / "profiles"
    os.replace(profiles, moved)
    _write_profile(moved / CACHE_PROFILE.name)
    profiles.symlink_to(moved, target_is_directory=True)
    return profiles


def _link_profile(directory: Path, outside: Path) -> Path:
    """``profiles/<CACHE_PROFILE>`` linked to a sound profile outside."""
    _write_profile(outside / CACHE_PROFILE.name)
    link = directory / "profiles" / CACHE_PROFILE.name
    link.symlink_to(outside / CACHE_PROFILE.name, target_is_directory=True)
    return link


# The linked component, what the refusal calls it, and the profiles it refuses.
CACHE_PROFILE_LINKS = [
    pytest.param(
        _link_profiles, "profiles directory", (CACHE_PROFILE, CACHE_STATIC_PROFILE),
        id="profiles-linked",
    ),
    pytest.param(_link_profile, "profile directory", (CACHE_PROFILE,), id="profile-linked"),
]


@pytest.mark.parametrize(("plant", "what", "refused"), CACHE_PROFILE_LINKS)
def test_build_cache_refuses_a_linked_profile_directory(
    cache_root: Path, monkeypatch: pytest.MonkeyPatch,
    plant: Callable[[Path, Path], Path], what: str, refused: tuple[build.KeyProfile, ...],
) -> None:
    directory = _cache_directory(cache_root)
    outside = cache_root / "outside"
    outside.mkdir()
    link = plant(directory, outside)
    build_before, outside_before = _tree(cache_root / "_build"), _tree(outside)
    builds: list[str] = []
    monkeypatch.setattr(
        build.BuildCache, "_build_profile",
        lambda cache, profile, target: builds.append(profile.name),
    )
    cache = _stand_in_cache(directory)
    for profile in refused:
        for program in (cache.loader, cache.unloader):
            with pytest.raises(build.BuildError) as error:
                program(profile)
            assert str(error.value) == (
                f"the {what} {link} is a symbolic link; remove it or choose another work root"
            )
    assert builds == []
    assert (_tree(cache_root / "_build"), _tree(outside)) == (build_before, outside_before)


def test_cache_verification_never_trusts_a_linked_directory(cache_root: Path) -> None:
    real = _cache_directory(cache_root / "outside")
    artifacts = build._artifact_hashes(real)
    assert build._artifacts_intact(real, artifacts)
    (cache_root / "_build").mkdir()
    linked = cache_root / "_build" / CACHE_KEY
    linked.symlink_to(real, target_is_directory=True)
    via_parent = cache_root / "linked"
    via_parent.symlink_to(real.parent, target_is_directory=True)
    before = _tree(cache_root)
    assert not build._artifacts_intact(linked, artifacts)
    assert not build._artifacts_intact(via_parent / CACHE_KEY, artifacts)
    with pytest.raises(build.BuildError) as error:
        build._complete_mode(linked, CACHE_KEY)
    assert str(error.value) == (
        f"the build directory {linked} is a symbolic link; remove it or choose another work root"
    )
    assert _tree(cache_root) == before


def _swapped(relative: str, *, linked: bool) -> Callable[[Path, Path], Path]:
    """A swap of the work root's ``relative`` once it was admitted: moved
    outside, then linked back from its place (``linked``) or replaced by a
    copy, a different real directory holding the same files and modes."""

    def swap(work: Path, outside: Path) -> Path:
        component, moved = work / relative, outside / Path(relative).name
        os.replace(component, moved)
        if linked:
            component.symlink_to(moved, target_is_directory=True)
        else:
            shutil.copytree(moved, component, symlinks=True)
        return component

    return swap


def _unswap(component: Path, outside: Path) -> None:
    """Put the admitted directory a ``_swapped`` swap moved outside back."""
    if component.is_symlink():
        component.unlink()
    else:
        shutil.rmtree(component)
    os.replace(outside / component.name, component)


CACHE_SWAP_REFUSAL = "; remove it or choose another work root"
# A swap after the build cache was admitted, what the refusal calls the
# component and what it says of it.
CACHE_SWAPS = [
    pytest.param(
        _swapped("_build", linked=True), "build root", "is a symbolic link",
        id="build-root-linked",
    ),
    pytest.param(
        _swapped("_build", linked=False), "build root", "is no longer the directory admitted",
        id="build-root-replaced",
    ),
    pytest.param(
        _swapped(f"_build/{CACHE_KEY}", linked=True), "build directory", "is a symbolic link",
        id="build-directory-linked",
    ),
    pytest.param(
        _swapped(f"_build/{CACHE_KEY}", linked=False), "build directory",
        "is no longer the directory admitted", id="build-directory-replaced",
    ),
]


@pytest.mark.parametrize(("swap", "what", "problem"), CACHE_SWAPS)
def test_a_build_cache_swapped_after_admission_is_refused_before_reuse(
    cache_root: Path, monkeypatch: pytest.MonkeyPatch,
    swap: Callable[[Path, Path], Path], what: str, problem: str,
) -> None:
    repo_root, work_root, outside = (cache_root / name for name in ("repo", "work", "outside"))
    for directory in (repo_root, outside):
        directory.mkdir()
    populated = _stand_in_build(monkeypatch)
    cache = build.ensure_built(repo_root, work_root)
    builds: list[str] = []
    monkeypatch.setattr(
        build.BuildCache, "_build_profile",
        lambda cache, profile, target: builds.append(profile.name),
    )
    # A sound on-demand profile checked on its first use, and the static one,
    # so the loader's fast path would return either without building.
    _write_profile(cache.dir / "profiles" / CACHE_PROFILE.name)
    profiles = (CACHE_PROFILE, CACHE_STATIC_PROFILE)
    for profile in profiles:
        assert cache.loader(profile) == cache.dir / "profiles" / profile.name / "KSLOAD"
    component = swap(work_root, outside)
    work_before, outside_before = _tree(work_root), _tree(outside)
    refusal = f"the {what} {component} {problem}{CACHE_SWAP_REFUSAL}"
    for _ in range(2):  # never memoized: the second call checks again
        with pytest.raises(build.BuildError) as error:
            build.ensure_built(repo_root, work_root)
        assert not isinstance(error.value, build.ToolchainUnavailable)
        assert str(error.value) == refusal
    for profile in profiles:
        for program in (cache.loader, cache.unloader):
            with pytest.raises(build.BuildError) as error:
                program(profile)
            assert str(error.value) == refusal
    # Nothing built, populated again or memoized, and neither the work root
    # nor the outside directory changed, links included, byte for byte.
    assert (builds, len(populated), build._BUILD_FAILURES) == ([], 1, {})
    assert (_tree(work_root), _tree(outside)) == (work_before, outside_before)
    # The admitted directory put back is admitted again by its identity.
    _unswap(component, outside)
    assert build.ensure_built(repo_root, work_root) is cache
    assert cache.unloader(CACHE_PROFILE) == cache.dir / "profiles" / CACHE_PROFILE.name / "KSUNLOAD"
    assert (builds, len(populated)) == ([], 1)


def _stop_compile_failed() -> BaseException:
    return build._CompileFailure("CBTRN02C", "logs/CBTRN02C.log", "stand-in compile failure")


# How a build whose _build was swapped while it compiled ends: published,
# after a failed compile or interrupted, and the exception ensure_built raises.
BUILD_WINDOW_STOPS = [
    pytest.param(None, build.BuildError, id="published"),
    pytest.param(_stop_compile_failed, build.BuildError, id="compile-failed"),
    pytest.param(KeyboardInterrupt, KeyboardInterrupt, id="interrupted"),
]


@pytest.mark.parametrize(("stop", "raised"), BUILD_WINDOW_STOPS)
def test_ensure_built_refuses_a_build_root_swapped_while_it_builds(
    cache_root: Path, monkeypatch: pytest.MonkeyPatch,
    stop: Callable[[], BaseException] | None, raised: type[BaseException],
) -> None:
    repo_root, work_root, outside = (cache_root / name for name in ("repo", "work", "outside"))
    for directory in (repo_root, outside):
        directory.mkdir()
    _stand_in_build(monkeypatch)
    populate = build._populate
    swapped: list[dict[str, bytes | str | None]] = []

    def swapping_populate(workspace: Path, *rest: object) -> None:
        # The workspace is written, then _build is moved outside and linked
        # back before the build ends.
        populate(workspace, *rest)
        _swapped("_build", linked=True)(work_root, outside)
        swapped.append(_tree(outside))
        if stop is not None:
            raise stop()

    monkeypatch.setattr(build, "_populate", swapping_populate)
    with pytest.raises(raised) as error:
        build.ensure_built(repo_root, work_root)
    if raised is build.BuildError:
        assert not isinstance(error.value, build.ToolchainUnavailable)
        assert str(error.value) == (
            f"the build root {work_root / '_build'} is a symbolic link{CACHE_SWAP_REFUSAL}"
        )
    # Nothing published, set aside or removed through the link: the moved
    # build root, its workspace included, is as the swap left it, and nothing
    # is memoized.
    assert len(swapped) == 1
    assert _tree(outside) == swapped[0]
    assert (build._CACHES, build._BUILD_FAILURES) == ({}, {})


PROCESS_SCOPE_ROOT_PREFIX = "unit-process-scope-"
# The whole environment of the cases' shell groups; run_process adds HOME.
PROCESS_SCOPE_ENV = {"PATH": "/usr/bin:/bin"}
# Each group prints START, waits for a "release" file in its working
# directory, then prints END and exits 0, so whether it ends by itself or is
# killed never depends on timing.
PROCESS_SCOPE_RELEASE = "release"
PROCESS_SCOPE_SCRIPT = (
    f"echo START; until [ -e {PROCESS_SCOPE_RELEASE} ]; do sleep 0.05; done; echo END"
)
PROCESS_SCOPE_DEADLINE_S = 30
PROCESS_SCOPE_WAIT_S = 10  # bound of the wait for a registration and for a group's thread
PROCESS_SCOPE_POLL_S = 0.01


@pytest.fixture
def process_scope_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, given by its real
    path. Afterwards every process group still registered is ended, and the
    directory is removed."""
    directory = Path(
        tempfile.mkdtemp(prefix=PROCESS_SCOPE_ROOT_PREFIX, dir=carddemo_work_root)
    ).resolve()
    try:
        yield directory
    finally:
        try:
            datasets.kill_all()
        finally:
            shutil.rmtree(directory)


class _Group:
    """A ``PROCESS_SCOPE_SCRIPT`` process group that ``datasets.run_process``
    runs in ``cwd``, created when missing, on a thread of its own; ``outcome``
    is its ``ProcessResult`` or what the run raised."""

    def __init__(self, cwd: Path) -> None:
        cwd.mkdir(parents=True, exist_ok=True)
        self.cwd = Path(os.path.realpath(cwd))
        self.outcome: datasets.ProcessResult | BaseException | None = None
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        try:
            self.outcome = datasets.run_process(
                ["/bin/sh", "-c", PROCESS_SCOPE_SCRIPT],
                env=PROCESS_SCOPE_ENV,
                cwd=self.cwd,
                deadline=time.monotonic() + PROCESS_SCOPE_DEADLINE_S,
            )
        except BaseException as exc:
            self.outcome = exc

    @property
    def running(self) -> bool:
        return self._thread.is_alive()

    def release(self) -> None:
        (self.cwd / PROCESS_SCOPE_RELEASE).touch()

    def result(self) -> datasets.ProcessResult:
        self._thread.join(PROCESS_SCOPE_WAIT_S)
        assert not self._thread.is_alive(), f"the group in {self.cwd} is still running"
        assert isinstance(self.outcome, datasets.ProcessResult), self.outcome
        return self.outcome


def _registered_dirs() -> list[Path]:
    """The working directory of every process group ``run_process`` holds
    registered."""
    with datasets._LIVE_LOCK:
        return list(datasets._LIVE_CWD.values())


def _await_registered(*groups: _Group) -> None:
    until = time.monotonic() + PROCESS_SCOPE_WAIT_S
    while not all(group.cwd in _registered_dirs() for group in groups):
        if time.monotonic() >= until or not all(group.running for group in groups):
            pytest.fail(
                "process groups not registered: "
                + "; ".join(f"{group.cwd}: {group.outcome!r}" for group in groups)
            )
        time.sleep(PROCESS_SCOPE_POLL_S)


def _assert_killed(group: _Group) -> None:
    outcome = group.result()
    assert (outcome.returncode, outcome.signal) == (None, signal.SIGKILL), outcome
    assert b"END" not in outcome.stdout


def test_kill_all_within_a_directory_ends_only_the_groups_started_there(
    process_scope_root: Path,
) -> None:
    scope = process_scope_root / "SCN-1"
    ended = _Group(scope / "data")
    kept = _Group(process_scope_root / "SCN-10")  # its path text begins with the scope's
    above = _Group(process_scope_root)
    link = process_scope_root / "link-to-SCN-1"
    link.symlink_to(scope, target_is_directory=True)
    _await_registered(ended, kept, above)
    datasets.kill_all(within=link)
    _assert_killed(ended)
    assert kept.running and above.running
    assert {kept.cwd, above.cwd} == set(_registered_dirs())
    datasets.kill_all(within=scope)  # nothing of the scope is left: nothing happens
    datasets.kill_all(within=process_scope_root / "SCN-2")  # never created
    assert kept.running and above.running
    kept.release()
    finished = kept.result()
    assert (finished.returncode, finished.signal, finished.stdout) == (0, None, b"START\nEND\n")
    datasets.kill_all()
    _assert_killed(above)
    assert datasets._LIVE == {} and datasets._LIVE_CWD == {}


def test_gnucobol_batch_cleanup_ends_only_its_own_scenarios_groups(
    monkeypatch: pytest.MonkeyPatch, process_scope_root: Path
) -> None:
    work_root = process_scope_root / "work-root"
    adapter = GnuCOBOLBatchAdapter(work_root=work_root)
    own = [_Group(work_root / "SCN-B"), _Group(work_root / "SCN-B" / "data")]
    prepared = _Group(work_root / "SCN-C")
    others = [
        _Group(work_root / "SCN-A"),  # another scenario's program
        _Group(work_root),  # session work, as the page-zero probe runs
        _Group(process_scope_root / "outside"),
    ]
    _await_registered(*own, prepared, *others)
    # No scope of a scenario: the work root itself, a directory above it or
    # outside it, a working directory never created, odd scenarios and odd
    # working directories. None of these ends a group or raises.
    for scenario, workdir in (
        ({"id": "SCN-X"}, work_root),
        ({"id": "SCN-X"}, process_scope_root),
        ({"id": "SCN-X"}, process_scope_root / "outside"),
        ({"id": "SCN-X"}, work_root / "SCN-X"),
        ({"id": "SCN-X"}, "bad\0path"),
        ({"id": 7}, 42),
        (object(), None),
    ):
        adapter.cleanup(scenario, workdir)
    monkeypatch.delenv(WORK_ROOT_ENV, raising=False)
    rootless = GnuCOBOLBatchAdapter()
    rootless.cleanup({"id": "SCN-B"}, work_root / "SCN-B")  # creates no work root either
    assert rootless._work_root is None
    assert all(group.running for group in (*own, prepared, *others))
    adapter.cleanup({"id": "SCN-B"}, work_root / "SCN-B")
    for group in own:
        _assert_killed(group)
    adapter.cleanup({"id": "SCN-B"}, work_root / "SCN-B")  # idempotent
    # What prepare laid out names the scope, whatever directory cleanup gets.
    adapter._states["SCN-C"] = SimpleNamespace(workdir=prepared.cwd)
    adapter.cleanup(SimpleNamespace(id="SCN-C"), work_root)
    _assert_killed(prepared)
    assert "SCN-C" not in adapter._states
    assert all(group.running for group in others)
    assert {group.cwd for group in others} == set(_registered_dirs())
    adapter.close()
    for group in others:
        _assert_killed(group)
    assert datasets._LIVE == {} and datasets._LIVE_CWD == {}


BUILD_MEMO_ROOT_PREFIX = "unit-build-memo-"
# A fake cobc that appends its arguments to a runs file, starts a background
# child, writes the child's pid and waits for it; the probe deadline the case
# sets leaves it time for all three.
BUILD_HANG_PROBE_S = 2
BUILD_HANG_CHILD = ("sleep", "1000")
BUILD_HANG_COBC = """\
#!/bin/sh
echo "$*" >> {runs}
{child} &
echo $! > {pid}
wait
"""
# A fake probe that writes its own pid, starts the background child, fills
# its stdout past a pipe's capacity, writes the child's pid and waits for it.
# Each pid is one whole line once written. The child's pid is written only
# once the caller reads the probe's output, so the caller is then waiting for
# the probe. The caller, a process of its own session, sets the SIGTERM
# disposition its argument names, the default action or a handler that prints
# "handler <signal>" and returns, and runs build._tool_output on the probe; it
# prints a BuildError as "<type>: <message>" and exits 0.
BUILD_SIGNAL_PROBE = """\
#!/bin/sh
echo $$ > {probe_pid}
{child} &
head -c {fill} /dev/zero
echo $! > {child_pid}
wait
"""
BUILD_SIGNAL_FILL = 256 * 1024
BUILD_SIGNAL_CALLER = """\
import os, signal, sys
sys.path.insert(0, sys.argv[1])
from adapters.gnucobol_batch import build

probe, disposition = sys.argv[2], sys.argv[3]

def handler(signum, frame):
    print(f"handler {signal.Signals(signum).name}")

signal.signal(signal.SIGTERM, signal.SIG_DFL if disposition == "default" else handler)
try:
    build._tool_output([probe], os.environ)
except build.BuildError as exc:
    print(f"{type(exc).__name__}: {exc}")
"""
BUILD_SIGNAL_DISPOSITIONS = [
    pytest.param("default", id="default-action"),
    pytest.param("handler", id="handler-returns"),
]
# The build key of the stand-ins, and the unit, log and detail of the
# failing compile.
BUILD_FAILED_KEY = "0123456789abcdef" * 4
BUILD_FAILED_UNIT = "UNIT"
BUILD_FAILED_LOG = "logs/UNIT.log"
BUILD_FAILED_DETAIL = "cobc exited 1"
BUILD_FAILED_LOG_TEXT = "status: exit 1\n"
BUILD_FAILURES = [
    pytest.param(True, id="compile-fails"),
    pytest.param(False, id="published-incomplete"),
]


@pytest.fixture
def build_memo_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, given by its real
    path and removed afterwards."""
    directory = Path(
        tempfile.mkdtemp(prefix=BUILD_MEMO_ROOT_PREFIX, dir=carddemo_work_root)
    ).resolve()
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def _process_gone(pid: int, argv: tuple[str, ...]) -> bool:
    """Whether the process ``pid`` running ``argv`` has ended: there is no
    such process, it is a zombie left to be reaped, or another program holds
    the pid."""
    proc = Path("/proc") / str(pid)
    try:
        stat_line = (proc / "stat").read_text(encoding="utf-8", errors="replace")
        cmdline = (proc / "cmdline").read_bytes()
    except (FileNotFoundError, ProcessLookupError):
        return True
    state = stat_line.rpartition(")")[2].split()[:1]
    return state in (["Z"], ["X"]) or cmdline.split(b"\0")[:-1] != [
        part.encode() for part in argv
    ]


def test_toolchain_probe_kills_a_hung_probes_group_and_runs_once(
    monkeypatch: pytest.MonkeyPatch, build_memo_root: Path
) -> None:
    fake_bin = build_memo_root / "bin"
    fake_bin.mkdir()
    runs, pid_file = build_memo_root / "runs.log", build_memo_root / "child.pid"
    cobc = fake_bin / "cobc"
    cobc.write_text(
        BUILD_HANG_COBC.format(
            runs=shlex.quote(str(runs)), child=shlex.join(BUILD_HANG_CHILD),
            pid=shlex.quote(str(pid_file)),
        ),
        encoding="utf-8",
    )
    cobc.chmod(0o755)
    # Fresh memos, so nothing this case probes reaches the session's adapters.
    monkeypatch.setattr(build, "_TOOL_PROBE_TIMEOUT_S", BUILD_HANG_PROBE_S)
    monkeypatch.setattr(build, "_TOOLCHAINS", {})
    monkeypatch.setattr(build, "_TOOLCHAIN_FAILURES", {})
    # The fake's folder is on PATH relative to the case's folder, the working
    # directory while the case probes: a PATH entry cannot hold ':', and the
    # session work root's path may. shutil.which then gives the fake by its
    # absolute path, the one the message names.
    path = os.pathsep.join([os.path.join(os.curdir, fake_bin.name), "/usr/bin", "/bin"])
    expected = (
        f"cannot run {shlex.join([str(cobc), '--version'])}: it did not finish within "
        f"{BUILD_HANG_PROBE_S} s. {build._SETUP_HINT}"
    )
    child: int | None = None
    try:
        with monkeypatch.context() as in_case:
            in_case.chdir(build_memo_root)
            with pytest.raises(build.ToolchainUnavailable) as first:
                build._probe_toolchain(path, "", ())
            child = int(pid_file.read_text(encoding="utf-8"))
            assert _awaited(
                lambda: _process_gone(child, BUILD_HANG_CHILD),
                time.monotonic() + PROCESS_SCOPE_WAIT_S,
            ), f"the probe's child {child} outlived it"
            with pytest.raises(build.ToolchainUnavailable) as second:
                build._probe_toolchain(path, "", ())
    finally:
        if child is not None and not _process_gone(child, BUILD_HANG_CHILD):
            os.kill(child, signal.SIGKILL)
    assert str(first.value) == str(second.value) == expected
    assert second.value is not first.value
    assert runs.read_text(encoding="utf-8").splitlines() == ["--version"]


@pytest.mark.parametrize("compile_fails", BUILD_FAILURES)
def test_ensure_built_raises_a_failed_build_again_without_rebuilding(
    monkeypatch: pytest.MonkeyPatch, build_memo_root: Path, compile_fails: bool
) -> None:
    repo_root, work_root = build_memo_root / "repo", build_memo_root / "work"
    repo_root.mkdir()
    populated: list[Path] = []

    def populate(workspace: Path, *_: object) -> None:
        # A failing compile writes its log; otherwise nothing is written, so
        # the published directory holds no COMPLETE.
        populated.append(workspace)
        if compile_fails:
            log = workspace / BUILD_FAILED_LOG
            log.parent.mkdir(parents=True)
            log.write_text(BUILD_FAILED_LOG_TEXT, encoding="utf-8")
            raise build._CompileFailure(BUILD_FAILED_UNIT, BUILD_FAILED_LOG, BUILD_FAILED_DETAIL)

    toolchain, plan = object(), object()
    monkeypatch.setattr(build, "_toolchain", lambda: toolchain)
    monkeypatch.setattr(build, "_plan", lambda _root: plan)
    monkeypatch.setattr(build, "_build_key", lambda *_: BUILD_FAILED_KEY)
    monkeypatch.setattr(build, "_populate", populate)
    monkeypatch.setattr(build, "_CACHES", {})
    monkeypatch.setattr(build, "_BUILD_FAILURES", {})
    raised = []
    for _ in range(2):
        with pytest.raises(build.BuildError) as error:
            build.ensure_built(repo_root, work_root)
        raised.append(error.value)
    final = work_root / "_build" / BUILD_FAILED_KEY
    log = final.with_name(f"{BUILD_FAILED_KEY}.failed") / BUILD_FAILED_LOG
    expected = (
        (f"cannot compile {BUILD_FAILED_UNIT}: {BUILD_FAILED_DETAIL}; log {log}",
         BUILD_FAILED_UNIT, log)
        if compile_fails
        else (f"the build directory {final} is incomplete after publishing", None, None)
    )
    assert [(type(exc), str(exc), exc.unit, exc.log_path) for exc in raised] == [
        (build.BuildError, *expected)
    ] * 2
    assert raised[1] is not raised[0]
    assert len(populated) == 1
    if compile_fails:
        assert log.read_text(encoding="utf-8") == BUILD_FAILED_LOG_TEXT
    else:
        assert not final.exists()


def _compile_toolchain(cobc: str) -> object:
    """A toolchain whose cobc is ``cobc`` and whose compile ``PATH`` holds
    the system's tools; nothing is probed."""
    return build._Toolchain(
        cobc=cobc, cc="/usr/bin/cc", cobc_version="", cc_version="", bin_dirs=("/usr/bin", "/bin"),
        lib_dirs=(), overrides=(),
    )


def test_compile_arguments_name_no_path_below_the_work_root(build_memo_root: Path) -> None:
    """Every compile of the build plan, and the loader and unloader of every
    static key profile, in the build directory and, built on demand, in a
    profile workspace of their own, names its output and generated sources
    relative to the directory it runs in, so no argument holds that
    directory's path, which starts with the work root's."""
    repo_root = TESTS_ROOT.parent
    toolchain = _compile_toolchain("/usr/bin/cobc")
    plan = build._plan(repo_root)
    workspace = build_memo_root / "work`root" / "_build" / ".key-workspace"
    runs = [(compile_, workspace, workspace) for compile_ in plan.compiles]
    for profile in plan.profiles:
        for compile_, _ in build._profile_compiles(profile).values():
            runs.append((compile_, workspace / "profiles" / profile.name, workspace))
            on_demand = workspace / "profiles" / f".{profile.name}-workspace"
            runs.append((compile_, on_demand, on_demand))
    assert len(runs) == len(plan.compiles) + 4 * len(plan.profiles)
    for compile_, base, cwd in runs:
        argv = compile_.argv(toolchain, repo_root, base, cwd)
        assert not [element for element in argv if str(build_memo_root) in element], argv
        output = argv[argv.index("-o") + 1]
        assert not Path(output).is_absolute() and cwd / output == base / compile_.artifact, argv
        if compile_.generated:
            assert [cwd / element for element in argv[-len(compile_.sources):]] == [
                base / source for source in compile_.sources
            ], argv
        else:
            assert argv[-len(compile_.sources):] == [str(repo_root / source) for source in compile_.sources]


# A fake cobc that records its working directory, its TMPDIR and each of its
# arguments, one per line, touches a canary and creates the file after -o.
BUILD_RECORDING_COBC = """\
#!/bin/sh
touch {canary}
printf '%s\\n' "$PWD" "$TMPDIR" "$@" > {record}
while [ "$#" -gt 0 ]; do
    if [ "$1" = -o ]; then : > "$2"; fi
    shift
done
"""
# The repository folder name each case's checkout path holds: a plain one,
# and ones holding a character a shell reads inside cobc's double quotes or
# one that is not printable.
BUILD_CHECKOUT_TEXT = [
    pytest.param("", None, id="plain"),
    pytest.param("`", "holds '`', which a shell reads even inside the double quotes", id="backquote"),
    pytest.param("$", "holds '$', which a shell reads even inside the double quotes", id="dollar"),
    pytest.param('"', "holds '\"', which a shell reads even inside the double quotes", id="double-quote"),
    pytest.param("\\", "holds '\\\\', which a shell reads even inside the double quotes", id="backslash"),
    pytest.param("\n", "holds the non-printable character '\\n'", id="newline"),
]


@pytest.mark.parametrize(("text", "refusal"), BUILD_CHECKOUT_TEXT)
def test_compile_runs_in_the_build_directory_and_refuses_shell_text(
    build_memo_root: Path, text: str, refusal: str | None
) -> None:
    """``build._compile_all`` runs a cobc compile in the build directory with
    ``TMPDIR=tmp``, its output named from there, and logs that directory as
    ``<build>``. When an argument holds a character a shell would read in
    cobc's C compiler commands, or one that is not printable, as the
    checkout's absolute ``-I app/cpy`` does here, nothing runs (the fake's
    canary stays absent) and the compile fails with ``_CompileFailure``
    naming the argument and the character, which the log gives as its
    status. The refused characters are those the work-root admission
    refuses."""
    assert build._SHELL_QUOTED_CHARACTERS == harness._SHELL_QUOTED_CHARACTERS
    fake = build_memo_root / "cobc"
    canary, record = build_memo_root / "canary", build_memo_root / "record"
    fake.write_text(
        BUILD_RECORDING_COBC.format(canary=shlex.quote(str(canary)), record=shlex.quote(str(record))),
        encoding="utf-8",
    )
    fake.chmod(0o755)
    repo_root = build_memo_root / f"re{text}po"
    workspace = build_memo_root / "work" / "_build" / ".key-workspace"
    workspace.mkdir(parents=True)
    job = next(compile_ for compile_ in build._TOOL_COMPILES if compile_.name == "DTCDRV")
    task = build._Task(job, job.name, workspace, workspace / "logs" / f"{job.name}.log")
    toolchain = _compile_toolchain(str(fake))
    include = str(repo_root / build.COPYBOOK_DIR)
    argv = [str(fake), *job.flags, "-I", include, "-o", job.artifact, str(repo_root / job.sources[0])]
    if refusal is None:
        build._compile_all([task], toolchain, repo_root, workspace)
        assert canary.is_file() and (workspace / job.artifact).is_file()
        assert record.read_text(encoding="utf-8").splitlines() == [str(workspace), "tmp", *argv[1:]]
        status = "exit 0"
    else:
        with pytest.raises(build._CompileFailure) as failed:
            build._compile_all([task], toolchain, repo_root, workspace)
        assert not canary.exists() and not record.exists(), "the refused compile ran cobc"
        status = failed.value.detail
        assert status.startswith(f"not run: the argument {include!r} {refusal}"), status
        assert (failed.value.unit, failed.value.log) == (job.name, f"logs/{job.name}.log")
        assert failed.value.message(workspace / failed.value.log).startswith(f"cannot compile {job.name}: {status}")
    log = task.log.read_text(encoding="utf-8")
    assert log.startswith(f"command: {shlex.join(argv)}\ncwd: <build>\nstatus: {status}\n"), log
    assert not (workspace / build._COMPILE_TMPDIR).exists(), "the compile's TMPDIR was left behind"


# A stand-in for cobc and cc that records each run in the case's runs file and
# fails, so a case can tell whether anything was compiled.
BUILD_RECORDING_TOOL = """\
#!/bin/sh
echo "$*" >> {runs}
exit 1
"""
BUILD_PROGRAMS_YAML = "tests/adapters/gnucobol_batch/programs.yaml"
BUILD_UNIT_ID_RULE = (
    "is not a batch program name: one to eight upper-case letters and digits, starting "
    "with a letter"
)
BUILD_FLAG_RULE = "is not allowed; a unit may add only -fbinary-byteorder=native"
BUILD_DESTINATION_RULE = "is not a relative path inside the build directory"
BUILD_OUTSIDE = "{outside}"  # in a case's flag, the case's directory outside the work root
# The dialect flags of every COBOL compile, and the committed units as
# planned: run, then the flags, sources and artifact of the cobc call that
# compiles each one. CBSTM03B has none of its own: CBSTM03A's call holds it.
BUILD_COBOL_FLAGS = ("-std=ibm", "-fsign=EBCDIC", "-ftab-width=4", "-fbinary-truncate")
BUILD_COMMITTED_UNITS = {
    **{
        unit: ("executable", ("-x", *BUILD_COBOL_FLAGS), (f"app/cbl/{unit}.cbl",), f"bin/{unit}")
        for unit in (
            "CBACT01C", "CBACT02C", "CBACT03C", "CBCUS01C", "CBTRN01C", "CBTRN02C", "CBTRN03C",
        )
    },
    "CBACT04C": (
        "parmdrv", ("-m", *BUILD_COBOL_FLAGS), ("app/cbl/CBACT04C.cbl",), "modules/CBACT04C.so",
    ),
    "CBSTM03A": (
        "executable", ("-x", *BUILD_COBOL_FLAGS, "-fbinary-byteorder=native"),
        ("app/cbl/CBSTM03A.CBL", "app/cbl/CBSTM03B.CBL"), "bin/CBSTM03A",
    ),
    "CSUTLDTC": (
        "dtcdrv", ("-m", *BUILD_COBOL_FLAGS), ("app/cbl/CSUTLDTC.cbl",), "modules/CSUTLDTC.so",
    ),
}
BUILD_CALLED_UNITS = {"CBSTM03B": {"called_by": "CBSTM03A", "run": "called"}}


def _unit_key_case(key: object) -> Callable[[dict, Path], str]:
    """An admission case adding the unit ``key`` with a copy of CBACT01C's
    entry; it returns the message of the ``BuildError`` it must raise."""

    def change(programs: dict, outside: Path) -> str:
        programs[key] = copy.deepcopy(programs["CBACT01C"])
        return f"{BUILD_PROGRAMS_YAML} unit {key!r} {BUILD_UNIT_ID_RULE}"

    return change


def _flag_case(unit: str, *flags: str) -> Callable[[dict, Path], str]:
    """An admission case appending ``flags``, the refused one first and
    ``BUILD_OUTSIDE`` standing for the case's outside directory, to the
    ``extra_flags`` of ``unit``."""

    def change(programs: dict, outside: Path) -> str:
        placed = [flag.replace(BUILD_OUTSIDE, str(outside)) for flag in flags]
        programs[unit]["compile"]["extra_flags"] += placed
        return (
            f"{BUILD_PROGRAMS_YAML} unit {unit} extra_flags: flag {placed[0]!r} {BUILD_FLAG_RULE}"
        )

    return change


def _called_by_case(called_by: object) -> Callable[[dict, Path], str]:
    """An admission case giving CBSTM03B the ``called_by`` ``called_by``."""

    def change(programs: dict, outside: Path) -> str:
        programs["CBSTM03B"]["called_by"] = called_by
        return (
            f"{BUILD_PROGRAMS_YAML} unit CBSTM03B is called, but its called_by {called_by!r} "
            f"names no unit of {BUILD_PROGRAMS_YAML}"
        )

    return change


def _link_with_case(unit: str) -> Callable[[dict, Path], str]:
    """An admission case making CBSTM03A link ``unit`` too."""

    def change(programs: dict, outside: Path) -> str:
        programs["CBSTM03A"]["compile"]["link_with"].append(unit)
        return (
            f"{BUILD_PROGRAMS_YAML} unit CBSTM03A links {unit}, which {BUILD_PROGRAMS_YAML} lacks"
        )

    return change


BUILD_ADMISSION_CASES = [
    pytest.param(_unit_key_case("../../../../outside/payload"), id="unit-id-traversal"),
    pytest.param(_unit_key_case("CBACT/01"), id="unit-id-slash"),
    pytest.param(_unit_key_case("cbact09c"), id="unit-id-lower-case"),
    pytest.param(_unit_key_case("CBACT099C"), id="unit-id-overlong"),
    pytest.param(_unit_key_case(12345678), id="unit-id-not-text"),
    pytest.param(_flag_case("CBACT01C", "-t", f"{BUILD_OUTSIDE}/listing.lst"), id="flag-listing"),
    pytest.param(_flag_case("CBACT01C", "-T", f"{BUILD_OUTSIDE}/wide.lst"), id="flag-wide-listing"),
    pytest.param(_flag_case("CBACT01C", f"-P={BUILD_OUTSIDE}"), id="flag-preprocessed-listing"),
    pytest.param(_flag_case("CBACT01C", f"--save-temps={BUILD_OUTSIDE}"), id="flag-save-temps"),
    pytest.param(_flag_case("CBACT01C", "-A", f"-o {BUILD_OUTSIDE}/unit.o"), id="flag-c-compiler"),
    pytest.param(_flag_case("CBACT01C", "-Q", f"-Wl,-o,{BUILD_OUTSIDE}/unit"), id="flag-linker"),
    pytest.param(
        _flag_case("CBACT01C", f"--conf={BUILD_OUTSIDE}/carddemo.conf"), id="flag-configuration"
    ),
    pytest.param(_flag_case("CBACT01C", "-o", f"{BUILD_OUTSIDE}/payload"), id="flag-output"),
    pytest.param(
        _flag_case("CBSTM03B", "-t", f"{BUILD_OUTSIDE}/linked.lst"), id="flag-of-a-linked-unit"
    ),
    pytest.param(_called_by_case(["CBSTM03A"]), id="called-by-not-text"),
    pytest.param(_called_by_case("NOSUCHU"), id="called-by-unknown-unit"),
    pytest.param(_link_with_case("NOSUCHU"), id="link-with-unknown-unit"),
]
# Compiles whose artifact or generated source would leave the directory they
# are built in: the artifact, the generated source (None: a repository
# source), and the destination the BuildError names.
BUILD_ESCAPING_COMPILES = [
    pytest.param(
        "bin/../../../outside/payload", None, "the artifact of UNIT 'bin/../../../outside/payload'",
        id="artifact-traversal",
    ),
    pytest.param(
        "/outside/payload", None, "the artifact of UNIT '/outside/payload'", id="artifact-absolute"
    ),
    pytest.param("", None, "the artifact of UNIT ''", id="artifact-empty"),
    pytest.param(".", None, "the artifact of UNIT '.'", id="artifact-the-directory"),
    pytest.param(
        "bin/UNIT", "../outside/UNIT.cbl", "the generated source of UNIT '../outside/UNIT.cbl'",
        id="generated-source-traversal",
    ),
    pytest.param(
        "bin/UNIT", "/outside/UNIT.cbl", "the generated source of UNIT '/outside/UNIT.cbl'",
        id="generated-source-absolute",
    ),
]
BUILD_ESCAPING_TASKS = [
    pytest.param("log", id="task-log-traversal"),
    pytest.param("base", id="task-outside-the-workspace"),
    pytest.param("generated", id="generated-source-traversal"),
    pytest.param("plan-log", id="plan-log-traversal"),
]


def _recording_toolchain(directory: Path, runs: Path) -> build._Toolchain:
    """A toolchain whose cobc and cc are one ``BUILD_RECORDING_TOOL`` in
    ``directory``, recording each run in ``runs``."""
    tool = directory / "tool"
    tool.write_text(BUILD_RECORDING_TOOL.format(runs=shlex.quote(str(runs))), encoding="utf-8")
    tool.chmod(0o755)
    return build._Toolchain(
        str(tool), str(tool), "cobc (GnuCOBOL) 3.2.0", "cc (stand-in) 1", (), (), ()
    )


@pytest.mark.parametrize("case", BUILD_ADMISSION_CASES)
def test_ensure_built_refuses_a_bad_unit_id_or_flag_before_writing(
    monkeypatch: pytest.MonkeyPatch, build_memo_root: Path, case: Callable[[dict, Path], str]
) -> None:
    repo_root = Path(_TESTS_ROOT).parent
    work_root, outside = build_memo_root / "work", build_memo_root / "outside"
    work_root.mkdir()
    outside.mkdir()
    runs = build_memo_root / "runs.log"
    toolchain = _recording_toolchain(build_memo_root, runs)
    committed = build._load_config
    document = copy.deepcopy(committed(repo_root, BUILD_PROGRAMS_YAML))
    expected = case(document["programs"], outside)

    def load_config(root: Path, relative: str) -> object:
        if relative == BUILD_PROGRAMS_YAML:
            return copy.deepcopy(document)
        return committed(root, relative)

    # Fresh memos, so nothing this case builds reaches the session's adapters.
    monkeypatch.setattr(build, "_toolchain", lambda: toolchain)
    monkeypatch.setattr(build, "_load_config", load_config)
    monkeypatch.setattr(build, "_CACHES", {})
    monkeypatch.setattr(build, "_BUILD_FAILURES", {})
    with pytest.raises(build.BuildError) as error:
        build.ensure_built(repo_root, work_root)
    assert (type(error.value), str(error.value), error.value.unit, error.value.log_path) == (
        build.BuildError, expected, None, None
    )
    # Neither _build nor anything outside the work root was created, and no
    # compiler ran.
    assert list(work_root.iterdir()) == []
    assert list(outside.iterdir()) == []
    assert sorted(path.name for path in build_memo_root.iterdir()) == ["outside", "tool", "work"]


def test_build_plan_admits_the_committed_programs_table_unchanged() -> None:
    plan = build._plan(Path(_TESTS_ROOT).parent)
    compiles = {compile_.name: compile_ for compile_ in plan.compiles}
    planned = {
        unit: (entry["run"], compiles[unit].flags, compiles[unit].sources, compiles[unit].artifact)
        for unit, entry in plan.units.items()
        if entry["run"] != "called"
    }
    assert planned == BUILD_COMMITTED_UNITS
    assert {unit: plan.units[unit]["artifact"] for unit in BUILD_COMMITTED_UNITS} == {
        unit: artifact for unit, (_, _, _, artifact) in BUILD_COMMITTED_UNITS.items()
    }
    assert {unit: entry for unit, entry in plan.units.items() if entry["run"] == "called"} == (
        BUILD_CALLED_UNITS
    )


# Python's integer string conversion limit, which int() enforces with
# ValueError. A case that needs a number past it lowers it to 640, the least
# sys.set_int_max_str_digits allows, so the numbers stay small.
DIGIT_LIMIT = 640
DIGIT_LIMIT_REFUSAL = "has {digits} digits, too many to read as a number"


def _lowered_digit_limit_call(call: Callable[[], object]) -> object:
    """``call()`` with the integer string conversion limit at ``DIGIT_LIMIT``,
    restored afterwards."""
    limit = sys.get_int_max_str_digits()
    sys.set_int_max_str_digits(DIGIT_LIMIT)
    try:
        return call()
    finally:
        sys.set_int_max_str_digits(limit)


# Build configuration values of the wrong type, which build._plan reads
# directly or through static_profiles: each is refused with a BuildError
# naming the entry, not the AttributeError, TypeError or ValueError reading it
# raised. (changes as (file, the keys and indexes to a value, the value put
# there), the message.)
BUILD_JOBS_YAML = "tests/adapters/gnucobol_batch/jobs.yaml"
BUILD_DATASETS_YAML = "tests/fixtures/datasets.yaml"
BUILD_ACCTFILE_DD = ("programs", "CBACT01C", "dd", "ACCTFILE")
BUILD_FIRST_STEP = f"{BUILD_JOBS_YAML} job ACCTFILE step STEP05"  # the first step read
BUILD_FIRST_STEP_PATH = ("jobs", "ACCTFILE", "steps", 0)
BUILD_PAST_LIMIT = "1" * (DIGIT_LIMIT + 1)
BUILD_PAST_LIMIT_REFUSAL = DIGIT_LIMIT_REFUSAL.format(digits=DIGIT_LIMIT + 1)
BUILD_DEFINE_SYSIN = ("jobs", "ACCTFILE", "steps", 1, "params", "sysin")
BUILD_CLUSTER = "  DEFINE CLUSTER (NAME(A.B) KEYS({keys}) RECORDSIZE(300 300) INDEXED)"
BUILD_MALFORMED_CASES = [
    pytest.param(
        [(BUILD_PROGRAMS_YAML, ("programs", "CBACT01C", "compile"), ["executable"])],
        f"{BUILD_PROGRAMS_YAML} unit CBACT01C compile must be a mapping, got ['executable']",
        id="compile-list",
    ),
    pytest.param(
        [(BUILD_PROGRAMS_YAML, ("programs", "CBACT01C", "compile"), "executable")],
        f"{BUILD_PROGRAMS_YAML} unit CBACT01C compile must be a mapping, got 'executable'",
        id="compile-text",
    ),
    # CBSTM03B, planned before CSUTLDTC, reads its caller's compile first.
    pytest.param(
        [
            (BUILD_PROGRAMS_YAML, ("programs", "CBSTM03B", "called_by"), "CSUTLDTC"),
            (BUILD_PROGRAMS_YAML, ("programs", "CSUTLDTC", "compile"), ["-m"]),
        ],
        f"{BUILD_PROGRAMS_YAML} unit CSUTLDTC compile must be a mapping, got ['-m']",
        id="caller-compile-list",
    ),
    pytest.param(
        [(BUILD_PROGRAMS_YAML, ("programs", "CBSTM03B", "compile"), "-x")],
        f"{BUILD_PROGRAMS_YAML} unit CBSTM03B compile must be a mapping, got '-x'",
        id="linked-compile-text",
    ),
    pytest.param(
        [(BUILD_PROGRAMS_YAML, ("programs", "CBACT01C", "run"), ["executable"])],
        f"{BUILD_PROGRAMS_YAML} unit CBACT01C: run ['executable'] does not fit compile mode "
        f"'executable'",
        id="run-list",
    ),
    pytest.param(
        [(BUILD_PROGRAMS_YAML, ("programs", "CBACT01C", "dd"), ["ACCTFILE"])],
        f"{BUILD_PROGRAMS_YAML} CBACT01C dd must be a mapping, got ['ACCTFILE']",
        id="dd-list",
    ),
    pytest.param(
        [(BUILD_PROGRAMS_YAML, ("programs", "CBACT01C", "dd", 1), {"organization": "indexed"})],
        f"{BUILD_PROGRAMS_YAML} CBACT01C dd name 1 is not text",
        id="dd-name-number",
    ),
    pytest.param(
        [(BUILD_PROGRAMS_YAML, (*BUILD_ACCTFILE_DD, "logical"), ["X"])],
        f"{BUILD_PROGRAMS_YAML} CBACT01C dd ACCTFILE logical must be a dataset name, got ['X']",
        id="dd-logical-list",
    ),
    pytest.param(
        [(BUILD_PROGRAMS_YAML, (*BUILD_ACCTFILE_DD, "keys", "alternates"), 5)],
        "alternates must be a list, got 5",
        id="dd-alternates-number",
    ),
    pytest.param(
        [(BUILD_DATASETS_YAML, ("datasets", 0, "layout"), ["X"])],
        "dataset ACCTDATA names layout ['X'], which tests/inventory/layouts.yaml lacks",
        id="dataset-layout-list",
    ),
    pytest.param(
        [(BUILD_DATASETS_YAML, ("datasets", 0, "alternate_keys"), 7)],
        "alternate_keys must be a list, got 7",
        id="dataset-alternate-keys-number",
    ),
    pytest.param(
        [(BUILD_JOBS_YAML, ("jobs", 1), {"steps": []})],
        f"{BUILD_JOBS_YAML} job name 1 is not text",
        id="job-name-number",
    ),
    pytest.param(
        [(BUILD_JOBS_YAML, (*BUILD_FIRST_STEP_PATH, "dd"), ["SYSPRINT"])],
        f"{BUILD_FIRST_STEP} dd must be a mapping, got ['SYSPRINT']",
        id="step-dd-list",
    ),
    pytest.param(
        [(BUILD_JOBS_YAML, (*BUILD_FIRST_STEP_PATH, "dd", "SYSPRINT", "concat"), 5)],
        f"{BUILD_FIRST_STEP} dd SYSPRINT concat must be a list, got 5",
        id="step-concat-number",
    ),
    pytest.param(
        [
            (
                BUILD_JOBS_YAML, (*BUILD_FIRST_STEP_PATH, "dd", "SYSPRINT"),
                {"dsn": ["A.B"], "logical": "ACCTDATA"},
            )
        ],
        f"{BUILD_FIRST_STEP} dd SYSPRINT: dsn and logical must be text, got ['A.B'] and "
        f"'ACCTDATA'",
        id="step-dsn-list",
    ),
    pytest.param(
        [(BUILD_JOBS_YAML, (*BUILD_FIRST_STEP_PATH, "params"), ["sysin"])],
        f"{BUILD_FIRST_STEP} params must be a mapping, got ['sysin']",
        id="step-params-list",
    ),
    pytest.param(
        [(BUILD_JOBS_YAML, (*BUILD_FIRST_STEP_PATH, "params", "sysin"), 5)],
        f"{BUILD_FIRST_STEP} sysin must be a list, got 5",
        id="step-sysin-number",
    ),
    pytest.param(
        [
            (
                BUILD_JOBS_YAML, BUILD_DEFINE_SYSIN,
                [BUILD_CLUSTER.format(keys=f"{BUILD_PAST_LIMIT} 0")],
            )
        ],
        f"{BUILD_JOBS_YAML} job ACCTFILE: DEFINE CLUSTER A.B KEYS length "
        f"{BUILD_PAST_LIMIT_REFUSAL}",
        id="define-cluster-keys-past-digit-limit",
    ),
    pytest.param(
        [
            (
                BUILD_JOBS_YAML, BUILD_DEFINE_SYSIN,
                [
                    BUILD_CLUSTER.format(keys="11 0"),
                    f"  DEFINE ALTERNATEINDEX (NAME(A.B.AIX) RELATE(A.B) "
                    f"KEYS(16 {BUILD_PAST_LIMIT}))",
                ],
            )
        ],
        f"{BUILD_JOBS_YAML} job ACCTFILE: DEFINE ALTERNATEINDEX KEYS offset "
        f"{BUILD_PAST_LIMIT_REFUSAL}",
        id="define-aix-keys-past-digit-limit",
    ),
]


@pytest.mark.parametrize(("changes", "message"), BUILD_MALFORMED_CASES)
def test_build_plan_refuses_a_configuration_value_of_the_wrong_type(
    monkeypatch: pytest.MonkeyPatch, changes: list[tuple[str, tuple, object]], message: str
) -> None:
    repo_root = Path(_TESTS_ROOT).parent
    committed = build._load_config
    documents = {
        relative: copy.deepcopy(committed(repo_root, relative)) for relative, _, _ in changes
    }
    for relative, path, value in changes:
        target = documents[relative]
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = value

    def load_config(root: Path, relative: str) -> object:
        if relative in documents:
            return copy.deepcopy(documents[relative])
        return committed(root, relative)

    monkeypatch.setattr(build, "_load_config", load_config)
    with pytest.raises(build.BuildError) as refused:
        _lowered_digit_limit_call(lambda: build._plan(repo_root))
    assert (type(refused.value), str(refused.value)) == (build.BuildError, message)


# Control-statement numbers longer than the integer string conversion limit:
# each SYMNAMES and IDCAMS reader refuses them with UtilityError, and reads a
# number of exactly the limit's digits as before.
# (reader, line, digits of its number {n}, the UtilityError's message, or None
# when the line is read): in a message, {refused} stands for
# DIGIT_LIMIT_REFUSAL and {line} for the line as the reader quotes it.
DIGIT_LIMIT_CASES = [
    pytest.param("symnames", "FLD,{n},8", DIGIT_LIMIT, None, id="symnames-position-at-limit"),
    pytest.param(
        "symnames", "FLD,{n},8", DIGIT_LIMIT + 1,
        "SYMNAMES line 1: the position {refused}; the line reads: {line}",
        id="symnames-position-over-limit",
    ),
    pytest.param("symnames", "FLD,1,{n}", DIGIT_LIMIT, None, id="symnames-length-at-limit"),
    pytest.param(
        "symnames", "FLD,1,{n}", DIGIT_LIMIT + 1,
        "SYMNAMES line 1: the length {refused}; the line reads: {line}",
        id="symnames-length-over-limit",
    ),
    # SYMNAMES has no repetition factor, so one is refused whatever its length.
    pytest.param(
        "symnames", "FLD,{n}C'A'", DIGIT_LIMIT + 1,
        "SYMNAMES line 1: a repetition factor is not valid in {n}C'A'; the line reads: {line}",
        id="symnames-repetition-over-limit",
    ),
    pytest.param(
        "idcams", "  IF LASTCC > {n} THEN SET MAXCC = 0", DIGIT_LIMIT, None,
        id="idcams-if-value-at-limit",
    ),
    pytest.param(
        "idcams", "  IF LASTCC > {n} THEN SET MAXCC = 0", DIGIT_LIMIT + 1,
        "IDCAMS command 1: the IF LASTCC value {refused}; the command reads: {line}",
        id="idcams-if-value-over-limit",
    ),
    pytest.param(
        "idcams", "  SET MAXCC = {n}", DIGIT_LIMIT + 1,
        "IDCAMS command 1: the SET MAXCC value {refused}; the command reads: {line}",
        id="idcams-set-value-over-limit",
    ),
    pytest.param(
        "idcams", "  REPRO INFILE(A) OUTFILE(B) ERRORLIMIT({n})", DIGIT_LIMIT, None,
        id="idcams-number-at-limit",
    ),
    pytest.param(
        "idcams", "  DEFINE CLUSTER (NAME(A.B) KEYS({n} 0) RECORDSIZE(10 10) INDEXED)",
        DIGIT_LIMIT + 1,
        "IDCAMS command 1 (DEFINE): a number of KEYS {refused}; the command reads: {line}",
        id="idcams-number-over-limit",
    ),
]


@pytest.mark.parametrize(("reader", "line", "digits", "refusal"), DIGIT_LIMIT_CASES)
def test_control_statement_numbers_beyond_the_int_digit_limit_raise_utility_error(
    reader: str, line: str, digits: int, refusal: str | None
) -> None:
    number = "1" * digits
    text = line.format(n=number)
    read = {"symnames": utilities.parse_symnames, "idcams": utilities.parse_idcams}[reader]
    if refusal is None:
        _lowered_digit_limit_call(lambda: read([text]))
        return
    with pytest.raises(utilities.UtilityError) as refused:
        _lowered_digit_limit_call(lambda: read([text]))
    assert str(refused.value) == refusal.format(
        n=number, refused=DIGIT_LIMIT_REFUSAL.format(digits=digits), line=" ".join(text.split())
    )


@pytest.mark.parametrize(("artifact", "generated", "refused"), BUILD_ESCAPING_COMPILES)
def test_build_compile_refuses_a_destination_outside_its_directory(
    artifact: str, generated: str | None, refused: str
) -> None:
    sources = ("app/cbl/CBACT01C.cbl",) if generated is None else (generated,)
    with pytest.raises(build.BuildError) as error:
        build._Compile("UNIT", "cobc", ("-x",), sources, artifact, generated=generated is not None)
    assert str(error.value) == f"{refused} {BUILD_DESTINATION_RULE}"


@pytest.mark.parametrize("escape", BUILD_ESCAPING_TASKS)
def test_build_refuses_a_task_or_generated_source_outside_the_workspace_unwritten(
    build_memo_root: Path, escape: str
) -> None:
    repo_root = Path(_TESTS_ROOT).parent
    workspace = build_memo_root / "work" / "_build" / ".workspace"
    outside = build_memo_root / "outside"
    workspace.mkdir(parents=True)
    outside.mkdir()
    runs = build_memo_root / "runs.log"
    toolchain = _recording_toolchain(build_memo_root, runs)
    unit = build._Compile("UNIT", "cobc", ("-x",), ("app/cbl/CBACT01C.cbl",), "bin/UNIT")
    sound = build._Task(unit, "UNIT", workspace, workspace / "logs" / "UNIT.log")
    # Up four levels from logs/ is the case's folder, which holds outside/.
    escaping_log = workspace / "logs" / ".." / ".." / ".." / ".." / "outside" / "UNIT.log"
    generated = {"src/SOUND.cbl": "", "../../../outside/X.cbl": ""}
    escaping_name = build._Compile(
        "../../../../outside/X", "cobc", ("-x",), ("app/cbl/CBACT01C.cbl",), "bin/X"
    )
    attempts: dict[str, tuple[Callable[[], object], str]] = {
        "log": (
            lambda: build._compile_all(
                [sound, build._Task(unit, "LOGGED", workspace, escaping_log)],
                toolchain, repo_root, workspace,
            ),
            f"the log of LOGGED 'logs/../../../../outside/UNIT.log' {BUILD_DESTINATION_RULE}",
        ),
        "base": (
            lambda: build._compile_all(
                [sound, build._Task(unit, "BASED", outside, outside / "UNIT.log")],
                toolchain, repo_root, workspace,
            ),
            f"the artifact of BASED {str(outside / 'bin' / 'UNIT')!r} is not inside the build "
            f"directory",
        ),
        "generated": (
            lambda: build._populate(
                workspace, BUILD_FAILED_KEY,
                build._Plan(units={}, compiles=(), profiles=(), generated=generated),
                toolchain, repo_root,
            ),
            f"the generated source '../../../outside/X.cbl' {BUILD_DESTINATION_RULE}",
        ),
        # A compile whose name takes its log out, refused before the sound
        # generated source is written.
        "plan-log": (
            lambda: build._populate(
                workspace, BUILD_FAILED_KEY,
                build._Plan(
                    units={}, compiles=(escaping_name,), profiles=(),
                    generated={"src/SOUND.cbl": ""},
                ),
                toolchain, repo_root,
            ),
            f"the log of {escaping_name.name} 'logs/{escaping_name.name}.log' "
            f"{BUILD_DESTINATION_RULE}",
        ),
    }
    attempt, expected = attempts[escape]
    with pytest.raises(build.BuildError) as error:
        attempt()
    assert str(error.value) == expected
    # Refused before any directory was created, file written or compiler run.
    assert list(workspace.iterdir()) == []
    assert list(outside.iterdir()) == []
    assert not runs.exists()


def _written_pid(path: Path) -> int | None:
    """The pid written to ``path`` as one whole line, or ``None`` while the
    file is absent or partly written."""
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None
    return int(text) if text.endswith("\n") else None


@pytest.mark.parametrize("disposition", BUILD_SIGNAL_DISPOSITIONS)
def test_sigterm_to_the_callers_group_kills_the_toolchain_probes_group(
    build_memo_root: Path, disposition: str
) -> None:
    probe = build_memo_root / "probe"
    probe_pid_file, child_pid_file = build_memo_root / "probe.pid", build_memo_root / "child.pid"
    probe.write_text(
        BUILD_SIGNAL_PROBE.format(
            probe_pid=shlex.quote(str(probe_pid_file)), child=shlex.join(BUILD_HANG_CHILD),
            fill=BUILD_SIGNAL_FILL, child_pid=shlex.quote(str(child_pid_file)),
        ),
        encoding="utf-8",
    )
    probe.chmod(0o755)
    probe_argv = ("/bin/sh", str(probe))
    caller = subprocess.Popen(
        [sys.executable, "-B", "-c", BUILD_SIGNAL_CALLER, _TESTS_ROOT, str(probe), disposition],
        cwd=build_memo_root, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, start_new_session=True,
    )
    probe_pid: int | None = None
    child: int | None = None
    try:
        assert _awaited(
            lambda: caller.poll() is not None or _written_pid(child_pid_file) is not None,
            time.monotonic() + PROCESS_SCOPE_WAIT_S,
        ), "the probe never wrote its child's pid"
        probe_pid, child = _written_pid(probe_pid_file), _written_pid(child_pid_file)
        assert caller.poll() is None, caller.communicate(timeout=PROCESS_SCOPE_WAIT_S)
        os.killpg(caller.pid, signal.SIGTERM)
        # The caller's teardown of the probe is bounded by _REAP_TIMEOUT_S.
        out, err = caller.communicate(timeout=build._REAP_TIMEOUT_S + PROCESS_SCOPE_WAIT_S)
        assert probe_pid is not None and child is not None
        assert _awaited(
            lambda: _process_gone(probe_pid, probe_argv) and _process_gone(child, BUILD_HANG_CHILD),
            time.monotonic() + PROCESS_SCOPE_WAIT_S,
        ), f"the probe {probe_pid} or its child {child} outlived the caller"
    finally:
        if caller.poll() is None:
            os.killpg(caller.pid, signal.SIGKILL)
            caller.communicate(timeout=PROCESS_SCOPE_WAIT_S)
        probe_pid = probe_pid or _written_pid(probe_pid_file)
        if probe_pid is not None and not _process_gone(probe_pid, probe_argv):
            os.kill(probe_pid, signal.SIGKILL)
        child = child or _written_pid(child_pid_file)
        if child is not None and not _process_gone(child, BUILD_HANG_CHILD):
            os.kill(child, signal.SIGKILL)
    if disposition == "default":
        assert (caller.returncode, out) == (-signal.SIGTERM, ""), err
    else:
        assert (caller.returncode, out) == (
            0, "handler SIGTERM\nBuildError: the build stopped on SIGTERM\n"
        ), err


# GDG roll-off past LIMIT (GAP-STB-009): a base of LIMIT 1 holding two
# generations rolls off the older one, whose file SCRATCH deletes and NOSCRATCH
# keeps, recorded in the catalog evidence as a dataset left without an entry.
GDG_ROLL_BASE = "AWS.M2.CARDDEMO.TRANSACT.BKUP"
GDG_ROLL_RECORD_LENGTH = 350
GDG_ROLL_WORKDIR_PREFIX = "unit-gdg-roll-off-"


@pytest.fixture
def gdg_roll_workdir(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=GDG_ROLL_WORKDIR_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


@pytest.mark.parametrize("scratch", [True, False], ids=["scratch", "noscratch"])
def test_catalog_new_records_a_generation_rolled_off_under_noscratch_as_uncatalogued(
    gdg_roll_workdir: Path, scratch: bool
) -> None:
    store = datasets.DatasetStore(gdg_roll_workdir, None)
    catalog = datasets.Catalog(store, datasets.DsnMap({"jobs": {}}, specs={}))
    catalog.define(datasets.Entry.gdg(GDG_ROLL_BASE, limit=1, scratch=scratch))
    first, second = (datasets.generation_name(GDG_ROLL_BASE, number) for number in (1, 2))
    data = {first: b"1" * GDG_ROLL_RECORD_LENGTH, second: b"2" * GDG_ROLL_RECORD_LENGTH}
    paths = {
        name: store.write_sequential(store.path_for(name), data[name], GDG_ROLL_RECORD_LENGTH)
        for name in (first, second)
    }
    rolled = [
        catalog.catalog_new(
            datasets.Entry.generation_entry(
                GDG_ROLL_BASE, number, paths[name], record_length=GDG_ROLL_RECORD_LENGTH
            )
        )
        for number, name in ((1, first), (2, second))
    ]
    assert rolled == [[], [first]]
    assert catalog.get(first) is None
    assert [entry.name for entry in catalog.generations(GDG_ROLL_BASE)] == [second]
    assert store.read_sequential(paths[second]) == data[second]
    if scratch:
        expected_uncatalogued: list[dict[str, str]] = []
        assert not store.exists(paths[first])
        assert f"rolled off {first}: scratched" in catalog.log
    else:
        expected_uncatalogued = [
            {
                "name": first,
                "path": f"data/{first}",
                "reason": f"rolled off under NOSCRATCH when {second} was catalogued",
            }
        ]
        assert store.read_sequential(paths[first]) == data[first]
        assert f"rolled off {first}: uncatalogued, file kept" in catalog.log
    assert catalog.uncatalogued == expected_uncatalogued
    evidence = yaml.safe_load(catalog.write_evidence().read_text(encoding="utf-8"))
    assert evidence["uncatalogued"] == expected_uncatalogued


# Utility size bounds (CWE-789): the DFSORT OUTREC limits (repetition 4095,
# column and input field end 32752), the longest fixed-length record (32760)
# and the bytes one utility step may build or write, each patched to a few
# bytes so that a regression could allocate no more than that.
BOUND_REPETITION = 8
BOUND_COLUMN = 40  # also the last byte an OUTREC input field may reach
BOUND_RECORD = 48
# (OUTREC operand, SYMNAMES lines, the OUTREC record length when accepted,
# the refusal's words when refused): each limit at its value and one past it.
OUTREC_BOUND_CASES = [
    pytest.param("(8X)", [], 8, None, id="blanks-repetition-at-limit"),
    pytest.param(
        "(9X)", [], None, "the repetition factor 9 of 9X is more than 8",
        id="blanks-repetition-over-limit",
    ),
    pytest.param("(8C'AB')", [], 16, None, id="char-repetition-at-limit"),
    pytest.param(
        "(9C'A')", [], None, "the repetition factor 9 of 9C'A' is more than 8",
        id="char-repetition-over-limit",
    ),
    pytest.param("(8X'C1')", [], 8, None, id="hex-repetition-at-limit"),
    pytest.param(
        "(9X'C1')", [], None, "the repetition factor 9 of 9X'C1' is more than 8",
        id="hex-repetition-over-limit",
    ),
    pytest.param("(40:X)", [], 40, None, id="column-at-limit"),
    pytest.param("(41:X)", [], None, "column 41 is beyond 40", id="column-over-limit"),
    pytest.param("(33,8)", [], 8, None, id="field-end-at-limit"),
    pytest.param(
        "(33,9)", [], None, "the OUTREC field 33,9 ends at byte 41, beyond 40",
        id="field-end-over-limit",
    ),
    pytest.param("(FLD)", ["FLD,33,8"], 8, None, id="symbol-field-end-at-limit"),
    pytest.param(
        "(FLD)", ["FLD,33,9"], None, "the OUTREC field FLD ends at byte 41, beyond 40",
        id="symbol-field-end-over-limit",
    ),
    pytest.param("(33:8C'AB')", [], 48, None, id="repeated-item-end-at-record-limit"),
    pytest.param(
        "(34:8C'AB')", [], None,
        "the OUTREC item \"8C'AB'\" at column 34 ends at byte 49, beyond 48",
        id="repeated-item-end-over-record-limit",
    ),
    pytest.param("(40:1,9)", [], 48, None, id="field-item-end-at-record-limit"),
    pytest.param(
        "(40:1,10)", [], None, "the OUTREC item '1,10' at column 40 ends at byte 49, beyond 48",
        id="field-item-end-over-record-limit",
    ),
    pytest.param("(8X,8X,8X,8X,8X,8X)", [], 48, None, id="items-end-at-record-limit"),
    pytest.param(
        "(8X,8X,8X,8X,8X,8X,X)", [], None, "the OUTREC item 'X' at column 49 ends at byte 49",
        id="items-end-over-record-limit",
    ),
]


@pytest.mark.parametrize(("operand", "symnames", "length", "refusal"), OUTREC_BOUND_CASES)
def test_parse_sort_bounds_outrec_items_by_the_dfsort_limits(
    monkeypatch: pytest.MonkeyPatch,
    operand: str,
    symnames: list[str],
    length: int | None,
    refusal: str | None,
) -> None:
    monkeypatch.setattr(utilities, "_MAX_REPETITION", BOUND_REPETITION)
    monkeypatch.setattr(utilities, "_MAX_COLUMN", BOUND_COLUMN)
    monkeypatch.setattr(utilities, "_MAX_FIELD_END", BOUND_COLUMN)
    monkeypatch.setattr(utilities, "_MAX_RECORD_LENGTH", BOUND_RECORD)
    lines = [" SORT FIELDS=COPY", f" OUTREC FIELDS={operand}"]
    symbols = utilities.parse_symnames(symnames)
    if refusal is None:
        spec = utilities.parse_sort(lines, symbols)
        assert spec.outrec_length == length
        assert len(spec.reformat(b"R" * BOUND_COLUMN)) == length
        return
    with pytest.raises(utilities.UtilityError) as refused:
        utilities.parse_sort(lines, symbols)
    message = str(refused.value)
    assert message.startswith(f"SORT OUTREC statement: {refusal}"), message
    assert message.endswith(f"; the statement reads: OUTREC FIELDS={operand}"), message


@pytest.mark.parametrize("length", [BOUND_RECORD, BOUND_RECORD + 1], ids=["at-limit", "over-limit"])
def test_sort_spec_reformat_bounds_the_outrec_record_by_the_record_limit(
    monkeypatch: pytest.MonkeyPatch, length: int
) -> None:
    monkeypatch.setattr(utilities, "_MAX_RECORD_LENGTH", BOUND_RECORD)
    spec = utilities.SortSpec((), outrec=(utilities.OutItem(1, length, data=b"A" * length),))
    if length <= BOUND_RECORD:
        assert spec.reformat(b"") == b"A" * length
        return
    with pytest.raises(utilities.UtilityError) as refused:
        spec.reformat(b"")
    assert str(refused.value).startswith(
        f"the OUTREC record is {length} bytes, longer than {BOUND_RECORD}"
    ), refused.value


UTILITY_BOUND_WORKDIR_PREFIX = "unit-utility-bounds-"
UTILITY_BOUND_JOB = "BOUNDJOB"
UTILITY_BOUND_DSN = "CARDDEMO.BOUND.OUTPUT"
UTILITY_BOUND_INPUT = ("A", "B", "C")  # three in-stream card images
UTILITY_BOUND_OUTREC = " OUTREC FIELDS=(1:1,1,48:X)"  # a 48-byte OUTREC record
# (utility, the output's DCB LRECL or None for an existing dataset catalogued
# with a record length of BOUND_RECORD + 1, an OUTREC statement, the step
# byte bound, the refusal's words when refused).
UTILITY_BOUND_CASES = [
    pytest.param("sort", 48, None, 3 * 48, None, id="sort-lrecl-at-record-limit"),
    pytest.param(
        "sort", 49, None, 3 * 49, "step STEP01 DD SORTOUT: DCB LRECL=49 is longer than 48",
        id="sort-lrecl-over-record-limit",
    ),
    pytest.param("sort", 40, None, 3 * 40, None, id="sort-output-at-step-bound"),
    pytest.param(
        "sort", 40, None, 3 * 40 - 1,
        f"job {UTILITY_BOUND_JOB} step STEP01: 3 records of 40 bytes for {UTILITY_BOUND_DSN} "
        f"are 120 bytes, more than the 119 one utility step may build",
        id="sort-output-over-step-bound",
    ),
    pytest.param(
        "sort", 40, UTILITY_BOUND_OUTREC, 3 * 48, None, id="sort-outrec-at-step-bound"
    ),
    pytest.param(
        "sort", 40, UTILITY_BOUND_OUTREC, 3 * 48 - 1,
        f"job {UTILITY_BOUND_JOB} step STEP01: 3 records of 48 bytes for {UTILITY_BOUND_DSN} "
        f"are 144 bytes, more than the 143 one utility step may build",
        id="sort-outrec-over-step-bound",
    ),
    pytest.param("iebgener", 40, None, 3 * 40, None, id="iebgener-output-at-step-bound"),
    pytest.param(
        "iebgener", 40, None, 3 * 40 - 1,
        f"job {UTILITY_BOUND_JOB} step STEP01: 3 records of 40 bytes for {UTILITY_BOUND_DSN} "
        f"are 120 bytes, more than the 119 one utility step may write",
        id="iebgener-output-over-step-bound",
    ),
    pytest.param(
        "iebgener", None, None, 3 * 49,
        f"job {UTILITY_BOUND_JOB} step STEP01: {UTILITY_BOUND_DSN} has a record length of 49, "
        f"longer than 48",
        id="iebgener-catalogued-length-over-record-limit",
    ),
]


@pytest.fixture
def utility_bound_workdir(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, removed afterwards."""
    directory = Path(
        tempfile.mkdtemp(prefix=UTILITY_BOUND_WORKDIR_PREFIX, dir=carddemo_work_root)
    )
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def _bound_step(
    kind: str, lrecl: int | None, outrec: str | None, status: str = "OLD"
) -> dict[str, object]:
    """A SORT (``FIELDS=COPY``), IEBGENER or IDCAMS ``REPRO`` step copying
    the in-stream UTILITY_BOUND_INPUT to UTILITY_BOUND_DSN: a new dataset of
    DCB LRECL ``lrecl``, or the existing catalogued one, with DISP status
    ``status``, when ``lrecl`` is None."""
    output: dict[str, object] = (
        {"dsn": UTILITY_BOUND_DSN, "disp": [status]}
        if lrecl is None
        else {
            "dsn": UTILITY_BOUND_DSN,
            "disp": ["NEW", "CATLG", "DELETE"],
            "dcb": {"lrecl": lrecl, "recfm": "FB"},
        }
    )
    source = {"instream": list(UTILITY_BOUND_INPUT)}
    if kind == "sort":
        sysin = [" SORT FIELDS=COPY", *([outrec] if outrec else [])]
        return {
            "step": "STEP01",
            "kind": "sort",
            "dd": {"SORTIN": source, "SYSOUT": {"sysout": "*"}, "SORTOUT": output},
            "params": {"sysin": sysin},
        }
    if kind == "idcams":
        return {
            "step": "STEP01",
            "kind": "idcams",
            "dd": {"SYSPRINT": {"sysout": "*"}, "SYSUT1": source, "SYSUT2": output},
            "params": {"sysin": [" REPRO INFILE(SYSUT1) OUTFILE(SYSUT2)"]},
        }
    return {
        "step": "STEP01",
        "kind": "iebgener",
        "dd": {
            "SYSPRINT": {"sysout": "*"},
            "SYSUT1": source,
            "SYSUT2": output,
            "SYSIN": {"dummy": True},
        },
    }


@pytest.mark.parametrize(
    ("kind", "lrecl", "outrec", "step_bytes", "refusal"), UTILITY_BOUND_CASES
)
def test_run_utility_bounds_the_record_length_and_the_step_output(
    utility_bound_workdir: Path,
    carddemo_repo_root: Path,
    monkeypatch: pytest.MonkeyPatch,
    kind: str,
    lrecl: int | None,
    outrec: str | None,
    step_bytes: int,
    refusal: str | None,
) -> None:
    monkeypatch.setattr(utilities, "_MAX_RECORD_LENGTH", BOUND_RECORD)
    monkeypatch.setattr(utilities, "_MAX_STEP_BYTES", step_bytes)
    store = datasets.DatasetStore(utility_bound_workdir, None)
    catalog = datasets.Catalog(store, datasets.DsnMap({"jobs": {}}, specs={}))
    path = catalog.path_for(UTILITY_BOUND_DSN)
    if lrecl is None:
        store.write_sequential(path, b"", BOUND_RECORD + 1)
        catalog.register(
            datasets.Entry.nonvsam(UTILITY_BOUND_DSN, path, record_length=BOUND_RECORD + 1)
        )
    ctx = utilities.StepContext(
        store, catalog, datasets.JobGenerations(catalog), catalog.dsn_map, carddemo_repo_root,
        utility_bound_workdir, 1, job=UTILITY_BOUND_JOB,
    )
    step = _bound_step(kind, lrecl, outrec)
    if refusal is None:
        assert utilities.run_utility(step, ctx).rc == 0
        expected = b"".join(f"{line:<{lrecl}}".encode("latin-1") for line in UTILITY_BOUND_INPUT)
        assert store.read_sequential(path) == expected
        return
    with pytest.raises(utilities.UtilityError) as refused:
        utilities.run_utility(step, ctx)
    assert str(refused.value).startswith(refusal), refused.value
    if lrecl is None:
        assert store.read_sequential(path) == b""
    else:
        assert not store.exists(path)


# A DISP=MOD output: UTILITY_BOUND_DSN catalogued with one kept record of
# UTILITY_MOD_LENGTH bytes, to which the step appends the three in-stream
# records, so the dataset written holds UTILITY_MOD_WRITTEN bytes.
UTILITY_MOD_LENGTH = 40
UTILITY_MOD_KEPT = b"KEPT".ljust(UTILITY_MOD_LENGTH)
UTILITY_MOD_WRITTEN = len(UTILITY_MOD_KEPT) + len(UTILITY_BOUND_INPUT) * UTILITY_MOD_LENGTH
UTILITY_MOD_KINDS = ["sort", "iebgener", "idcams"]


def _mod_bound_context(
    workdir: Path, repo_root: Path
) -> tuple[datasets.DatasetStore, Path, utilities.StepContext]:
    """The store, the file of UTILITY_BOUND_DSN holding UTILITY_MOD_KEPT,
    and the step context of a job whose step extends it under DISP=MOD."""
    store = datasets.DatasetStore(workdir, None)
    catalog = datasets.Catalog(store, datasets.DsnMap({"jobs": {}}, specs={}))
    path = catalog.path_for(UTILITY_BOUND_DSN)
    store.write_sequential(path, UTILITY_MOD_KEPT, UTILITY_MOD_LENGTH)
    catalog.register(
        datasets.Entry.nonvsam(UTILITY_BOUND_DSN, path, record_length=UTILITY_MOD_LENGTH)
    )
    ctx = utilities.StepContext(
        store, catalog, datasets.JobGenerations(catalog), catalog.dsn_map, repo_root, workdir, 1,
        job=UTILITY_BOUND_JOB,
    )
    return store, path, ctx


@pytest.mark.parametrize("excess", [0, 1], ids=["at-step-bound", "over-step-bound"])
@pytest.mark.parametrize("kind", UTILITY_MOD_KINDS)
def test_run_utility_bounds_a_disp_mod_output_with_the_bytes_it_keeps(
    utility_bound_workdir: Path,
    carddemo_repo_root: Path,
    monkeypatch: pytest.MonkeyPatch,
    kind: str,
    excess: int,
) -> None:
    monkeypatch.setattr(utilities, "_MAX_RECORD_LENGTH", BOUND_RECORD)
    monkeypatch.setattr(utilities, "_MAX_STEP_BYTES", UTILITY_MOD_WRITTEN - excess)
    store, path, ctx = _mod_bound_context(utility_bound_workdir, carddemo_repo_root)
    step = _bound_step(kind, None, None, status="MOD")
    if not excess:
        assert utilities.run_utility(step, ctx).rc == 0
        appended = b"".join(
            f"{line:<{UTILITY_MOD_LENGTH}}".encode("latin-1") for line in UTILITY_BOUND_INPUT
        )
        assert store.read_sequential(path) == UTILITY_MOD_KEPT + appended
        return
    with pytest.raises(utilities.UtilityError) as refused:
        utilities.run_utility(step, ctx)
    assert str(refused.value) == (
        f"job {UTILITY_BOUND_JOB} step STEP01: {len(UTILITY_BOUND_INPUT)} records of "
        f"{UTILITY_MOD_LENGTH} bytes for {UTILITY_BOUND_DSN} plus the {len(UTILITY_MOD_KEPT)} "
        f"bytes DISP=MOD keeps of it are {UTILITY_MOD_WRITTEN} bytes, more than the "
        f"{UTILITY_MOD_WRITTEN - 1} one utility step may write (an emulation bound)"
    )
    assert store.read_sequential(path) == UTILITY_MOD_KEPT


def test_run_utility_refuses_a_disp_mod_output_whose_size_cannot_be_read(
    utility_bound_workdir: Path, carddemo_repo_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    store, path, ctx = _mod_bound_context(utility_bound_workdir, carddemo_repo_root)
    check_deadline = utilities.StepContext.check_deadline

    def vanish_before_the_write(self: utilities.StepContext, what: str) -> None:
        """The deadline check, after which the DISP=MOD dataset is gone."""
        check_deadline(self, what)
        if what == f"before SORT wrote {UTILITY_BOUND_DSN}":
            path.unlink()

    monkeypatch.setattr(utilities.StepContext, "check_deadline", vanish_before_the_write)
    with pytest.raises(utilities.UtilityError) as refused:
        utilities.run_utility(_bound_step("sort", None, None, status="MOD"), ctx)
    assert str(refused.value) == (
        f"job {UTILITY_BOUND_JOB} step STEP01: the size of {UTILITY_BOUND_DSN}, which DISP=MOD "
        f"extends, cannot be read: {os.strerror(errno.ENOENT)}"
    )
    assert isinstance(refused.value.__cause__, FileNotFoundError)
    assert not store.exists(path)


# Stage observations: logical dataset <LOGICAL> at the end of job <JOB> of a
# scenario's job chain, checked on in-memory copies of the full daily chain.
STAGE_CHAIN = "BAT-CHAIN-normal-01"
STAGE_CHAIN_JOBS = ("POSTTRAN", "INTCALC", "TRANBKP", "COMBTRAN", "CREASTMT", "TRANREPT")
STAGE_ACCOUNTS = "@AFTER:POSTTRAN:ACCTDATA"
ACCOUNT_LAYOUT = "CVACT01Y/ACCOUNT-RECORD"
# Account ids in delivered order, which is not key order, and the cycle credit
# and debit CBTRN02C posts [app/cbl/CBTRN02C.cbl:545-554], as account 1 holds
# them after POSTTRAN on the repository data.
STAGE_ACCOUNT_IDS = (3, 1, 2)
STAGE_CYCLE_CREDIT = Decimal("1164.87")
STAGE_CYCLE_DEBIT = Decimal("-70.77")

STAGE_NAMES = [
    pytest.param("POSTTRAN", "ACCTDATA", id="posted-accounts"),
    pytest.param("TRANBKP", "TRANSACT-BKUP", id="backup-generation"),
    pytest.param("TRNRPT00", "TRANREPT", id="generated-job-report"),
]

STAGE_REFUSED_PARTS = [
    pytest.param("", "ACCTDATA", id="empty-job"),
    pytest.param("POSTTRAN", "", id="empty-dataset"),
    pytest.param("POST:TRAN", "ACCTDATA", id="colon-in-job"),
    pytest.param("POSTTRAN", "ACCT:DATA", id="colon-in-dataset"),
    pytest.param("POST TRAN", "ACCTDATA", id="space-in-job"),
    pytest.param("POSTTRAN", "ACCTDATA\n", id="line-break-in-dataset"),
    pytest.param("POSTTRAN", "@CATALOG", id="reserved-observation"),
    pytest.param(None, "ACCTDATA", id="job-not-text"),
    pytest.param("POSTTRAN", 7, id="dataset-not-text"),
]

STAGE_NOT_NAMES = [
    pytest.param("@AFTER:", id="prefix-only"),
    pytest.param("@AFTER:POSTTRAN", id="no-dataset"),
    pytest.param("@AFTER::ACCTDATA", id="empty-job"),
    pytest.param("@AFTER:POSTTRAN:", id="empty-dataset"),
    pytest.param("@AFTER:POSTTRAN:ACCTDATA:INTCALC", id="third-part"),
    pytest.param("@AFTER:POSTTRAN:@CATALOG", id="reserved-observation"),
    pytest.param("@AFTER:POST TRAN:ACCTDATA", id="space-in-job"),
    pytest.param("@AIX:CXACAIX", id="another-family"),
    pytest.param("ACCTDATA", id="business-dataset"),
    pytest.param(None, id="not-text"),
]

# (name, value): stage observations of jobs the chain runs once, each value
# checked as its logical dataset's own: record field maps, an empty cluster,
# and the text lines of a TEXT/<n> layout.
STAGE_ACCEPTED = [
    pytest.param(
        STAGE_ACCOUNTS,
        {"count": 50, "contains": [
            {"ACCT-ID": "1", "ACCT-CURR-CYC-CREDIT": str(STAGE_CYCLE_CREDIT),
             "ACCT-CURR-CYC-DEBIT": str(STAGE_CYCLE_DEBIT)},
        ]},
        id="posted-accounts",
    ),
    pytest.param("@AFTER:TRANBKP:TRANSACT-BKUP", {"count": 262}, id="first-backup-generation"),
    pytest.param("@AFTER:TRANBKP:TRANSACT", {"records": []}, id="emptied-cluster"),
    pytest.param(
        "@AFTER:TRANREPT:TRANREPT", {"contains": ["a report line"]}, id="text-layout-lines"
    ),
    pytest.param(
        "@BEFORE:POSTTRAN:@REGION-FILES",
        {"records": ["ACCTDAT open enabled", "CARDAIX open enabled"]},
        id="region-files-before-a-job",
    ),
    pytest.param(
        "@AFTER:TRANREPT:@REGION-FILES",
        {"contains": ["ACCTDAT closed disabled"]},
        id="region-files-after-a-job",
    ),
]

# (section, name, value, jobs, problem): jobs replaces inputs.job when given.
STAGE_REFUSALS = [
    pytest.param(
        "file_state", "@AFTER:POSTTRAN", {"records": []}, None,
        "is not a stage observation @AFTER:<JOB>:<LOGICAL>", id="malformed-name",
    ),
    pytest.param(
        "file_state", "@AFTER:READACCT:ACCTDATA", {"records": []}, None,
        "names job READACCT, which the scenario does not run", id="job-not-run",
    ),
    pytest.param(
        "file_state", STAGE_ACCOUNTS, {"records": []}, ["POSTTRAN", "INTCALC", "POSTTRAN"],
        "names job POSTTRAN, which inputs.job lists 2 times", id="job-listed-twice",
    ),
    pytest.param(
        "file_state", "@AFTER:POSTTRAN:NOSUCHDATA", {"records": []}, None,
        "names 'NOSUCHDATA', which is not a logical dataset of tests/fixtures/datasets.yaml",
        id="unknown-dataset",
    ),
    pytest.param(
        "file_state", "@AFTER:TRANBKP:TRANSACT", {"contains": [{"ACCT-CURR-BAL": "1.00"}]}, None,
        "is not a field of layout CVTRA05Y/TRAN-RECORD", id="field-of-another-layout",
    ),
    pytest.param(
        "file_state", "@AFTER:TRANBKP:TRANSACT-BKUP", {"contains": ["a report line"]}, None,
        "must be a field map of the dataset's layout", id="text-line-of-a-record-layout",
    ),
    pytest.param(
        "datasets", STAGE_ACCOUNTS, {"records": []}, None,
        "reserved observations belong under file_state", id="under-expected-datasets",
    ),
    pytest.param(
        "file_state", "@BEFORE:POSTTRAN", {"records": []}, None,
        "is not a region stage observation @BEFORE:<JOB>:@REGION-FILES", id="region-malformed-name",
    ),
    pytest.param(
        "file_state", "@BEFORE:POSTTRAN:@CATALOG", {"records": []}, None,
        "is not a region stage observation @BEFORE:<JOB>:@REGION-FILES", id="region-other-observation",
    ),
    pytest.param(
        "file_state", "@AFTER:READACCT:@REGION-FILES", {"records": []}, None,
        "names job READACCT, which the scenario does not run", id="region-job-not-run",
    ),
    pytest.param(
        "file_state", "@BEFORE:POSTTRAN:@REGION-FILES", {"records": []}, ["POSTTRAN", "INTCALC", "POSTTRAN"],
        "names job POSTTRAN, which inputs.job lists 2 times, so the start of POSTTRAN",
        id="region-job-listed-twice",
    ),
    pytest.param(
        "file_state", "@AFTER:POSTTRAN:@REGION-FILES", {"records": [{"ACCTDAT": "open enabled"}]}, None,
        "must be a line of text or a matcher", id="region-record-not-a-line",
    ),
    pytest.param(
        "datasets", "@AFTER:POSTTRAN:@REGION-FILES", {"records": []}, None,
        "reserved observations belong under file_state", id="region-under-expected-datasets",
    ),
]

STAGE_GUARD_REFUSALS = [
    pytest.param(
        "@AFTER:READACCT:ACCTDATA", STAGE_CHAIN_JOBS, "does not run READACCT", id="job-not-run"
    ),
    pytest.param(
        STAGE_ACCOUNTS, ("POSTTRAN", "INTCALC", "POSTTRAN"), "runs POSTTRAN 2 times",
        id="job-run-twice",
    ),
]


def _chain_with(
    section: str, name: str, value: object, jobs: list[str] | None = None
) -> scenarios.Scenario:
    """An in-memory copy of ``STAGE_CHAIN`` whose ``expected.<section>`` also
    names ``name`` with ``value``, and whose ``inputs.job`` is ``jobs`` when
    given; the committed scenario is left as it is. With ``jobs`` the copy
    keeps no stage observation of its own besides ``name``: the committed
    ones name the chain's jobs, which another job list may not run once, so
    every problem the swap adds is one of ``name``."""
    scenario = scenarios.get(STAGE_CHAIN)
    data = copy.deepcopy(scenario.data)
    data["expected"].setdefault(section, {})[name] = value
    if jobs is not None:
        data["inputs"]["job"] = jobs
        file_state = data["expected"].get("file_state", {})
        for other in [key for key in file_state if key != name and base.parse_after_job(key) is not None]:
            del file_state[other]
    return dataclasses.replace(
        scenario, data=data, inputs=data["inputs"], expected=data["expected"]
    )


@pytest.mark.parametrize(("job", "logical"), STAGE_NAMES)
def test_stage_observation_names_round_trip_and_match_their_family(job: str, logical: str) -> None:
    name = base.after_job_observation(job, logical)
    assert name == f"{base.AFTER_PREFIX}{job}:{logical}"
    assert base.parse_after_job(name) == (job, logical)
    assert base.observation_matches(name, {base.AFTER_PREFIX + "*"})
    assert base.observation_matches(name, {base.AFTER_PREFIX})
    assert not base.observation_matches(
        name, {base.BUSINESS_DATASETS, base.CATALOG, base.AIX_PREFIX + "*"}
    )
    assert GnuCOBOLBatchAdapter().can_observe(name)  # construction creates nothing


@pytest.mark.parametrize(("job", "logical"), STAGE_REFUSED_PARTS)
def test_stage_observation_name_refuses_a_malformed_part(job: object, logical: object) -> None:
    with pytest.raises(ValueError):
        base.after_job_observation(job, logical)


@pytest.mark.parametrize("key", STAGE_NOT_NAMES)
def test_parse_after_job_reads_no_stage_from_any_other_name(key: object) -> None:
    assert base.parse_after_job(key) is None


@pytest.mark.parametrize(("name", "value"), STAGE_ACCEPTED)
def test_schema_accepts_a_stage_observation_of_a_job_the_chain_runs_once(
    name: str, value: dict
) -> None:
    committed = scenarios.validate(scenarios.get(STAGE_CHAIN))
    assert scenarios.validate(_chain_with("file_state", name, value)) == committed


@pytest.mark.parametrize(("section", "name", "value", "jobs", "problem"), STAGE_REFUSALS)
def test_schema_refuses_a_stage_observation_no_single_job_end_can_hold(
    section: str, name: str, value: dict, jobs: list[str] | None, problem: str
) -> None:
    committed = set(scenarios.validate(scenarios.get(STAGE_CHAIN)))
    added = set(scenarios.validate(_chain_with(section, name, value, jobs))) - committed
    where = f": expected.{section}.{name}"
    assert added and all(where in line for line in added), added
    assert any(problem in line for line in added), added


def test_gnucobol_batch_groups_stage_observations_by_job() -> None:
    wanted = {
        "ACCTDATA",
        base.CATALOG,
        "@AIX:CXACAIX",
        "@AFTER:POSTTRAN",  # malformed: never produced, as the schema refuses it
        "@AFTER:TRANBKP:TRANSACT-BKUP",
        "@AFTER:TRANBKP:TRANSACT",
        STAGE_ACCOUNTS,
    }
    stages = GnuCOBOLBatchAdapter._stage_observations(STAGE_CHAIN, STAGE_CHAIN_JOBS, wanted)
    assert stages == {
        "POSTTRAN": [(STAGE_ACCOUNTS, "ACCTDATA")],
        "TRANBKP": [
            ("@AFTER:TRANBKP:TRANSACT", "TRANSACT"),
            ("@AFTER:TRANBKP:TRANSACT-BKUP", "TRANSACT-BKUP"),
        ],
    }
    plain = GnuCOBOLBatchAdapter._stage_observations(STAGE_CHAIN, STAGE_CHAIN_JOBS, {"ACCTDATA"})
    assert plain == {}


@pytest.mark.parametrize(("name", "jobs", "problem"), STAGE_GUARD_REFUSALS)
def test_gnucobol_batch_refuses_a_stage_observation_without_one_job_end(
    name: str, jobs: tuple[str, ...], problem: str
) -> None:
    with pytest.raises(HarnessError) as caught:
        GnuCOBOLBatchAdapter._stage_observations(STAGE_CHAIN, jobs, {name})
    assert problem in str(caught.value) and name in str(caught.value)


def test_decode_result_reads_a_stage_observation_as_its_dataset_in_key_order() -> None:
    accounts = b"".join(
        codec.encode_record(
            {"ACCT-ID": number, "ACCT-CURR-CYC-CREDIT": STAGE_CYCLE_CREDIT,
             "ACCT-CURR-CYC-DEBIT": STAGE_CYCLE_DEBIT},
            ACCOUNT_LAYOUT,
            "ascii",
        )
        for number in STAGE_ACCOUNT_IDS
    )
    scenario = _chain_with("file_state", STAGE_ACCOUNTS, {"count": len(STAGE_ACCOUNT_IDS)})
    scenario.expected["file_state"]["ACCTDATA"] = {"count": len(STAGE_ACCOUNT_IDS)}  # the copy's
    delivered = base.Result(
        return_code=4, datasets={STAGE_ACCOUNTS: accounts, "ACCTDATA": accounts}
    )
    decoded = baselines.decode_result(scenario, delivered)["file_state"]
    stage = decoded[STAGE_ACCOUNTS]
    assert stage == decoded["ACCTDATA"]
    assert [record["ACCT-ID"] for record in stage] == [
        str(number) for number in sorted(STAGE_ACCOUNT_IDS)
    ]
    assert {
        (record["ACCT-CURR-CYC-CREDIT"], record["ACCT-CURR-CYC-DEBIT"]) for record in stage
    } == {(str(STAGE_CYCLE_CREDIT), str(STAGE_CYCLE_DEBIT))}
    assert delivered.datasets[STAGE_ACCOUNTS] == accounts  # raw bytes are never changed
    lacking = base.Result(return_code=4, datasets={"ACCTDATA": accounts})
    absent = baselines.decode_result(scenario, lacking)
    assert absent["file_state"][STAGE_ACCOUNTS] == {"@missing": True}


# Job-run fault rule (GAP-MIS-013): Catalog.seed, on the committed jobs.yaml and
# programs.yaml, leaves out the target of an open/unavailable fault only when a
# use of the run reaches the absent dataset before the run creates it itself.
# Every case has DALYTRAN as its one precondition, which POSTTRAN seeds, so a
# refusal that came after seeding would leave its file under data/.
JOB_FAULT_WORKDIR_PREFIX = "unit-job-fault-"
JOB_FAULT_PRECONDITION = "DALYTRAN"
JOB_FAULT_FAULT = {"operation": "open", "condition": "unavailable"}

# (inputs.job, target, the cause the refusal gives).
JOB_FAULT_REFUSED = [
    pytest.param("POSTTRAN", "DALYREJS", "POSTTRAN only creates it", id="new-generation"),
    pytest.param("POSTTRAN", "TRANSACT", "POSTTRAN only creates it", id="program-opens-output"),
    pytest.param("TRANBKP", "TRANSACT-BKUP", "TRANBKP only creates it", id="repro-new-generation"),
    pytest.param("INTCALC", "SYSTRAN", "INTCALC only creates it", id="program-new-generation"),
    pytest.param(
        "POSTTRAN", "CUSTDATA", "no dd or IDCAMS command of POSTTRAN uses it", id="unused"
    ),
    pytest.param(
        ["INTCALC", "COMBTRAN"],
        "SYSTRAN",
        "INTCALC, COMBTRAN only create it",
        id="generation-zero-is-an-earlier-jobs-own",
    ),
    pytest.param(
        "TRANREPT", "TRANSACT-BKUP", "TRANREPT only creates it", id="reads-its-own-plus-one"
    ),
]

# (inputs.job, target): the use that reaches the absent dataset first.
JOB_FAULT_ACCEPTED = [
    pytest.param("POSTTRAN", "CARDXREF", id="program-reads-shr"),
    pytest.param("COMBTRAN", "SYSTRAN", id="generation-zero-is-the-seeded-one"),
    pytest.param(["POSTTRAN", "TRANBKP"], "TRANSACT", id="output-through-shr-stays-uncatalogued"),
    pytest.param("ACCTFILE", "ACCTDATA", id="idcams-delete-before-define"),
    pytest.param("TRANIDX", "TRANSACT", id="idcams-relate-and-bldindex"),
]


@pytest.fixture
def job_fault_workdir(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh scenario working directory of this case's own inside the
    session work root (carddemo_work_root), never under pytest's basetemp,
    whose ``inputs/`` holds one DALYTRAN record; removed afterwards."""
    directory = Path(
        tempfile.mkdtemp(prefix=JOB_FAULT_WORKDIR_PREFIX, dir=carddemo_work_root)
    ).resolve()
    try:
        inputs = directory / "inputs"
        inputs.mkdir()
        length = datasets.dataset_specs()[JOB_FAULT_PRECONDITION].record_length
        (inputs / f"{JOB_FAULT_PRECONDITION}.dat").write_bytes(b" " * length)
        yield directory
    finally:
        shutil.rmtree(directory)


def _seed_with_fault(workdir: Path, job: str | list[str], target: str) -> datasets.Catalog:
    """``Catalog.seed`` of a job scenario running ``job`` with an
    ``open``/``unavailable`` fault on ``target``."""
    scenario = {
        "id": "SCN-JOB-FAULT",
        "inputs": {"job": job},
        "preconditions": {"faults": [{"target": target, **JOB_FAULT_FAULT}]},
    }
    return datasets.Catalog.seed(
        scenario,
        jobs.load_jobs(),
        workdir / "inputs",
        datasets.DatasetStore(workdir, None),
        programs=jobs.load_programs()["programs"],
    )


@pytest.mark.parametrize(("job", "target", "cause"), JOB_FAULT_REFUSED)
def test_catalog_seed_refuses_a_fault_no_use_of_the_job_run_observes(
    job_fault_workdir: Path, job: str | list[str], target: str, cause: str
) -> None:
    with pytest.raises(base.MissingPrerequisite) as refusal:
        _seed_with_fault(job_fault_workdir, job, target)
    assert refusal.value.gap_id == "GAP-MIS-013"
    message = str(refusal.value)
    assert message.startswith(f"open fault (unavailable) on {target}: {cause}"), message
    assert "so leaving it out fails no OPEN of the run" in message
    assert not (job_fault_workdir / datasets.DATA_DIR).exists()


@pytest.mark.parametrize(("job", "target"), JOB_FAULT_ACCEPTED)
def test_catalog_seed_leaves_out_a_fault_target_a_use_of_the_job_run_reaches(
    job_fault_workdir: Path, job: str | list[str], target: str
) -> None:
    catalog = _seed_with_fault(job_fault_workdir, job, target)
    assert catalog.left_out == frozenset({target})
    dsn, is_gdg = catalog.dsn_map.dsn_for(target)
    assert (catalog.generations(dsn) == []) if is_gdg else (catalog.get(dsn) is None)
    assert not any(entry.logical == target for entry in catalog.entries())


# (programs, what the DatasetError says): a programs table lacking POSTTRAN's
# CBTRN02C, and no table at all.
JOB_FAULT_BAD_PROGRAMS = [
    pytest.param({}, "program 'CBTRN02C' is not a unit of programs.yaml", id="unit-missing"),
    pytest.param(None, "needs the programs.yaml programs table", id="no-table"),
]


@pytest.mark.parametrize(("programs", "problem"), JOB_FAULT_BAD_PROGRAMS)
def test_catalog_seed_refuses_programs_that_lack_a_program_steps_unit(
    job_fault_workdir: Path, programs: object, problem: str
) -> None:
    scenario = {"id": "SCN-JOB-FAULT", "inputs": {"job": "POSTTRAN"}}
    with pytest.raises(datasets.DatasetError, match=re.escape(problem)):
        datasets.Catalog.seed(
            scenario,
            jobs.load_jobs(),
            job_fault_workdir / "inputs",
            datasets.DatasetStore(job_fault_workdir, None),
            programs=programs,
        )
    assert not (job_fault_workdir / datasets.DATA_DIR).exists()


# A POSTTRAN STEP15 DD that CBTRN02C declares and that codes, DISP=SHR, a DSN
# the catalog lacks with no fault declared: z/OS fails the allocation
# (IEF212I). Each case: the indexed dataset the catalog lacks and the DD that
# codes it; TRANFILE comes before DALYREJS(+1), the step's NEW dataset, in the
# DD order and ACCTFILE after it.
JOB_ALLOCATION_CLUSTERS = ("TRANSACT", "CARDXREF", "ACCTDATA", "TCATBALF")
JOB_ALLOCATION_REFUSALS = [
    pytest.param("TRANSACT", "TRANFILE", id="tranfile-before-the-new-dataset"),
    pytest.param("ACCTDATA", "ACCTFILE", id="acctfile-after-the-new-dataset"),
]


@pytest.mark.parametrize(("lacking", "ddname"), JOB_ALLOCATION_REFUSALS)
def test_run_jobs_refuses_a_program_dd_whose_dataset_the_catalog_lacks(
    job_fault_workdir: Path, launch_root: Path, lacking: str, ddname: str
) -> None:
    """Refused before CBTRN02C runs or any dataset of the step is allocated.
    The other clusters are catalog entries alone: the refusal is decided by
    the catalog, and CBTRN02C is a script that would end RC 0 if it ran."""
    cache = _LaunchCache(launch_root / LAUNCH_WORK_ROOT)
    cache.script("CBTRN02C", "", "", "exit 0")
    scenario = {"id": "SCN-JOB-ALLOCATION", "inputs": {"job": "POSTTRAN"}}
    catalog = datasets.Catalog.seed(
        scenario,
        jobs.load_jobs(),
        job_fault_workdir / "inputs",
        datasets.DatasetStore(job_fault_workdir, None),
        programs=jobs.load_programs()["programs"],
    )
    for logical in JOB_ALLOCATION_CLUSTERS:
        if logical != lacking:
            dsn, _ = catalog.dsn_map.dsn_for(logical)
            catalog.register(
                datasets.Entry.cluster(
                    dsn,
                    catalog.store.path_for(dsn),
                    catalog.dsn_map.specs[logical].profile,
                    logical=logical,
                )
            )
    with pytest.raises(HarnessError) as refusal:
        jobs.run_jobs(
            cache,
            scenario,
            catalog,
            workdir=job_fault_workdir,
            deadline=time.monotonic() + LAUNCH_DEADLINE_S,
        )
    message = str(refusal.value)
    assert message.startswith(f"job POSTTRAN step STEP15: DD {ddname} of CBTRN02C codes "), message
    assert "with DISP=SHR" in message and "IEF212I DATA SET NOT FOUND" in message
    assert f"declare {lacking} in preconditions.datasets" in message
    dalyrejs = catalog.store.path_for(datasets.generation_name("AWS.M2.CARDDEMO.DALYREJS", 1))
    assert not dalyrejs.exists()
    evidence = job_fault_workdir / jobs.EVIDENCE_SUBDIR
    assert not (evidence / "steps").exists()
    assert load_yaml(evidence / "jobs.yaml") == {"jobs": [], "steps": []}


# Stage observations on unikix: a chain of three jobs whose first and last
# have stage observations and whose middle one has none. UNIKIX_STAGE_LEFT is
# what each job leaves in the catalog: logical -> bytes, a logical it does not
# name keeping what it held; one never listed is not catalogued. TRANBKP's
# TRANSACT is the emptied cluster, whose unload holds no record.
UNIKIX_STAGE_SCENARIO = "BAT-CHAIN-normal-01"
UNIKIX_STAGE_JOBS = ("POSTTRAN", "INTCALC", "TRANBKP")
UNIKIX_STAGE_BACKUP_EARLY = "@AFTER:POSTTRAN:TRANSACT-BKUP"  # not catalogued yet then
UNIKIX_STAGE_EMPTIED = "@AFTER:TRANBKP:TRANSACT"
UNIKIX_STAGE_BACKUP = "@AFTER:TRANBKP:TRANSACT-BKUP"
UNIKIX_STAGE_MALFORMED = "@AFTER:POSTTRAN"  # never produced, as the schema refuses it
UNIKIX_STAGE_EXPECTED = (
    "ACCTDATA",
    STAGE_ACCOUNTS,
    UNIKIX_STAGE_BACKUP_EARLY,
    UNIKIX_STAGE_EMPTIED,
    UNIKIX_STAGE_BACKUP,
    UNIKIX_STAGE_MALFORMED,
)
UNIKIX_STAGE_LEFT = {
    "POSTTRAN": {"ACCTDATA": b"P" * 300, "TRANSACT": b"T" * 350},
    "INTCALC": {"ACCTDATA": b"I" * 300},
    "TRANBKP": {"TRANSACT": b"", "TRANSACT-BKUP": b"T" * 350},
}

# The adapter's region control and BPE calls, in order, for each part of the
# run: a job's submission and wait, a stage's unloads at the end of POSTTRAN
# and of TRANBKP (each kept under its stage name as it is acquired), and the
# post-run unload of the one business dataset expected.
UNIKIX_STAGE_RUN = {job: [("submit", job), ("wait", job)] for job in UNIKIX_STAGE_JOBS}
UNIKIX_STAGE_AFTER_POSTTRAN = [
    ("close",),
    ("observe", ("ACCTDATA", "TRANSACT-BKUP")),
    ("kept", STAGE_ACCOUNTS),
    ("open",),
]
UNIKIX_STAGE_AFTER_TRANBKP_UNTIL_THE_BACKUP = [
    ("close",),
    ("observe", ("TRANSACT", "TRANSACT-BKUP")),
    ("kept", UNIKIX_STAGE_EMPTIED),
]
UNIKIX_STAGE_AFTER_TRANBKP = UNIKIX_STAGE_AFTER_TRANBKP_UNTIL_THE_BACKUP + [
    ("kept", UNIKIX_STAGE_BACKUP),
    ("open",),
]
UNIKIX_STAGE_POST_RUN = [("close",), ("observe", ("ACCTDATA",)), ("kept", "ACCTDATA"), ("open",)]

# Each case: the job that abends (None: none), the dataset whose unload fails
# (None: none does), the calls in order, the observations the evidence holds
# afterwards (the Result's datasets too when the run returns), and the gap of
# the failure the run ends in (None: it returns).
UNIKIX_STAGE_EXECUTIONS = [
    pytest.param(
        None,
        None,
        UNIKIX_STAGE_RUN["POSTTRAN"]
        + UNIKIX_STAGE_AFTER_POSTTRAN
        + UNIKIX_STAGE_RUN["INTCALC"]
        + UNIKIX_STAGE_RUN["TRANBKP"]
        + UNIKIX_STAGE_AFTER_TRANBKP
        + UNIKIX_STAGE_POST_RUN,
        {
            "ACCTDATA": UNIKIX_STAGE_LEFT["INTCALC"]["ACCTDATA"],
            STAGE_ACCOUNTS: UNIKIX_STAGE_LEFT["POSTTRAN"]["ACCTDATA"],
            UNIKIX_STAGE_EMPTIED: b"",
            UNIKIX_STAGE_BACKUP: UNIKIX_STAGE_LEFT["TRANBKP"]["TRANSACT-BKUP"],
        },
        None,
        id="every-job-completes",
    ),
    pytest.param(
        "INTCALC",
        None,
        UNIKIX_STAGE_RUN["POSTTRAN"]
        + UNIKIX_STAGE_AFTER_POSTTRAN
        + UNIKIX_STAGE_RUN["INTCALC"]
        + UNIKIX_STAGE_POST_RUN,
        {
            "ACCTDATA": UNIKIX_STAGE_LEFT["INTCALC"]["ACCTDATA"],
            STAGE_ACCOUNTS: UNIKIX_STAGE_LEFT["POSTTRAN"]["ACCTDATA"],
        },
        None,
        id="an-abend-leaves-the-later-stages-absent",
    ),
    pytest.param(
        None,
        "TRANSACT-BKUP",
        UNIKIX_STAGE_RUN["POSTTRAN"]
        + UNIKIX_STAGE_AFTER_POSTTRAN
        + UNIKIX_STAGE_RUN["INTCALC"]
        + UNIKIX_STAGE_RUN["TRANBKP"]
        + UNIKIX_STAGE_AFTER_TRANBKP_UNTIL_THE_BACKUP,
        {STAGE_ACCOUNTS: UNIKIX_STAGE_LEFT["POSTTRAN"]["ACCTDATA"], UNIKIX_STAGE_EMPTIED: b""},
        "GAP-MIS-007",
        id="a-failed-stage-unload-keeps-what-was-acquired",
    ),
]

# Each case: the job that abends (None: none), and the jobs that then run.
UNIKIX_RUN_JOBS_CASES = [
    pytest.param(None, list(UNIKIX_STAGE_JOBS), id="every-job-completes"),
    pytest.param("INTCALC", ["POSTTRAN", "INTCALC"], id="an-abend-ends-the-chain"),
]

# Each case: whether the session has started, the commands.yaml entries its
# probes found available, whether the stage observations (and with them the
# business datasets) are declared, and what the reason they are not names.
UNIKIX_STAGE_UNLOADS = frozenset(unikix_adapter._UNLOAD_ENTRIES)
UNIKIX_STAGE_SESSIONS = [
    pytest.param(False, frozenset(), True, None, id="before-the-session"),
    pytest.param(
        True,
        UNIKIX_STAGE_UNLOADS | set(unikix_adapter._FILE_CONTROL),
        True,
        None,
        id="unloads-with-region-file-control",
    ),
    pytest.param(
        True,
        UNIKIX_STAGE_UNLOADS | set(unikix_adapter._REGION_RESTART),
        True,
        None,
        id="unloads-with-a-region-restart",
    ),
    pytest.param(
        True,
        (UNIKIX_STAGE_UNLOADS - {"dataset_unload"}) | set(unikix_adapter._FILE_CONTROL),
        False,
        "dataset_unload: probe found no dataset_unload",
        id="no-unload-entry",
    ),
    pytest.param(
        True,
        UNIKIX_STAGE_UNLOADS,
        False,
        "the region files cannot be closed for BPE",
        id="region-files-not-closable",
    ),
]

# Each case: the stage observation, the scenario's inputs.job (empty for an
# online run), the exception _plan_for raises and a text its message holds.
UNIKIX_STAGE_GUARD_CASES = [
    pytest.param(
        "@AFTER:READACCT:ACCTDATA",
        UNIKIX_STAGE_JOBS,
        HarnessError,
        "does not run READACCT",
        id="job-not-run",
    ),
    pytest.param(
        STAGE_ACCOUNTS,
        ("POSTTRAN", "INTCALC", "POSTTRAN"),
        HarnessError,
        "runs POSTTRAN 2 times",
        id="job-run-twice",
    ),
    pytest.param(STAGE_ACCOUNTS, (), HarnessError, "the scenario runs no job", id="online-run"),
    pytest.param(
        STAGE_ACCOUNTS, UNIKIX_STAGE_JOBS, _BeyondTheGate, "_GateCommands", id="job-run-once-passes"
    ),
    pytest.param(
        "@BEFORE:READACCT:@REGION-FILES",
        UNIKIX_STAGE_JOBS,
        HarnessError,
        "does not run READACCT, so no start of it exists to read the region files at",
        id="region-job-not-run",
    ),
    pytest.param(
        "@AFTER:POSTTRAN:@REGION-FILES",
        ("POSTTRAN", "INTCALC", "POSTTRAN"),
        HarnessError,
        "runs POSTTRAN 2 times, so its end is not one point of the run",
        id="region-job-run-twice",
    ),
    pytest.param(
        "@BEFORE:POSTTRAN:@REGION-FILES",
        (),
        HarnessError,
        "the scenario runs no job, so no start of POSTTRAN exists",
        id="region-online-run",
    ),
    pytest.param(
        "@AFTER:POSTTRAN:@REGION-FILES",
        UNIKIX_STAGE_JOBS,
        _BeyondTheGate,
        "_GateCommands",
        id="region-job-run-once-passes",
    ),
]


class _ProbedCommands:
    """The ``CommandSet`` a started session's observations read: the
    entries its probes found available, and why each other one is not."""

    def __init__(self, available: frozenset[str]) -> None:
        self.found = frozenset(available)

    def available(self, entry_id: str) -> bool:
        return entry_id in self.found

    def probe_reason(self, entry_id: str) -> str | None:
        return None if entry_id in self.found else f"probe found no {entry_id}"


def _unikix_stage_scenario(names: tuple[str, ...], jobs: tuple[str, ...]) -> SimpleNamespace:
    """A ``UNIKIX_STAGE_SCENARIO`` stand-in running ``jobs`` (``inputs.steps``
    when there are none) whose ``expected.file_state`` names ``names``."""
    inputs = {"job": list(jobs)} if jobs else {"steps": []}
    return SimpleNamespace(
        id=UNIKIX_STAGE_SCENARIO,
        inputs=inputs,
        expected={"file_state": {name: {"count": 1} for name in names}},
        preconditions={},
    )


@pytest.mark.parametrize(("abends", "ran"), UNIKIX_RUN_JOBS_CASES)
def test_unikix_run_jobs_calls_before_job_and_after_job_around_each_job(
    abends: str | None, ran: list[str]
) -> None:
    service = bpe.BatchService(SimpleNamespace(vars={}), SimpleNamespace(), bpe.Resources.load())
    events: list[tuple] = []

    def submit(name: str, jcl_text: str | None, entry_id: str, budget: object) -> bpe.JobRun:
        assert jcl_text is None and entry_id == "bpe_submit"
        events.append(("submit", name))
        return bpe.JobRun(name, name, name, entry_id, str(len(events)))

    def wait(run: bpe.JobRun, budget: object) -> bpe.JobRun:
        events.append(("wait", run.name))
        run.completed = True
        run.steps = [bpe.StepOutcome("STEP15", None if run.name == abends else 0)]
        return run

    service._submit = submit
    service._wait = wait
    outcome = service.run_jobs(
        list(UNIKIX_STAGE_JOBS),
        time.monotonic() + 300,
        before_job=lambda position, job: events.append(("before", position, job)),
        after_job=lambda position, job: events.append(("after", position, job)),
    )
    # before_job runs after the previous job's after_job and before the
    # submission, and never for a job the chain does not reach.
    assert events == [
        event
        for position, job in enumerate(ran)
        for event in (("before", position, job), ("submit", job), ("wait", job), ("after", position, job))
    ]
    assert [run.name for run in outcome.runs] == ran
    assert outcome.return_code == (None if abends else 0)


@pytest.mark.parametrize(
    ("abends", "failing", "calls", "kept", "gap_id"), UNIKIX_STAGE_EXECUTIONS
)
def test_unikix_acquires_each_stage_observation_between_the_jobs(
    monkeypatch: pytest.MonkeyPatch,
    csd_gate_work_root: Path,
    abends: str | None,
    failing: str | None,
    calls: list[tuple],
    kept: dict[str, bytes],
    gap_id: str | None,
) -> None:
    # A work root of this case's own inside the session's, so the working
    # directory named after the real chain scenario is never the one that
    # scenario's own run uses.
    work_root = csd_gate_work_root
    adapter = UniKixAdapter(work_root=work_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter._capture = evidence.Capture()
    adapter.deadline = time.monotonic() + 300
    scenario = _unikix_stage_scenario(UNIKIX_STAGE_EXPECTED, UNIKIX_STAGE_JOBS)
    stages = UniKixAdapter._stage_observations(
        scenario.id, UNIKIX_STAGE_JOBS, base.required_observations(scenario.expected)
    )
    assert stages == {
        "POSTTRAN": [(STAGE_ACCOUNTS, "ACCTDATA"), (UNIKIX_STAGE_BACKUP_EARLY, "TRANSACT-BKUP")],
        "TRANBKP": [(UNIKIX_STAGE_EMPTIED, "TRANSACT"), (UNIKIX_STAGE_BACKUP, "TRANSACT-BKUP")],
    }
    adapter._plan = unikix_adapter._Plan(
        scenario_id=scenario.id,
        online=False,
        steps=[],
        sessions=1,
        jobs=UNIKIX_STAGE_JOBS,
        dateparm=None,
        stages=stages,
    )
    service = bpe.BatchService(SimpleNamespace(vars={}), SimpleNamespace(), bpe.Resources.load())
    catalog: dict[str, bytes] = {}
    events: list[tuple] = []
    region = {"closed": False}

    def submit(name: str, jcl_text: str | None, entry_id: str, budget: object) -> bpe.JobRun:
        assert not region["closed"], "a job was submitted while the region files were closed"
        events.append(("submit", name))
        return bpe.JobRun(name, name, name, entry_id, str(len(events)))

    def wait(run: bpe.JobRun, budget: object) -> bpe.JobRun:
        events.append(("wait", run.name))
        catalog.update(UNIKIX_STAGE_LEFT[run.name])
        run.completed = True
        run.steps = [bpe.StepOutcome("STEP15", None if run.name == abends else 0)]
        return run

    def observe(names: list[str], *, deadline: float | None, keep: object) -> dict[str, bytes]:
        assert region["closed"] and deadline == adapter.deadline
        events.append(("observe", tuple(names)))
        found: dict[str, bytes] = {}
        for logical in names:
            if logical not in catalog:
                continue
            if logical == failing:
                raise base.AdapterUnavailable(f"dataset_unload of {logical} exited 12", "GAP-MIS-007")
            before = dict(adapter._capture.datasets)
            keep(logical, catalog[logical])
            added = {
                name: data
                for name, data in adapter._capture.datasets.items()
                if name not in before or before[name] != data
            }
            assert list(added.values()) == [catalog[logical]], added
            events.append(("kept", *added))
            found[logical] = catalog[logical]
        return found

    def set_closed(closed: bool, label: str) -> None:
        assert region["closed"] is not closed, f"region files {label} twice"
        region["closed"] = closed
        events.append((label,))

    service._submit, service._wait, service.observe = submit, wait, observe
    adapter._batch = service
    monkeypatch.setattr(adapter, "_close_region_files", lambda: set_closed(True, "close"))
    monkeypatch.setattr(adapter, "_open_region_files", lambda: set_closed(False, "open"))
    workdir = work_root / UNIKIX_STAGE_SCENARIO
    if gap_id is None:
        result = adapter.execute(scenario, workdir)
        assert result.datasets == kept
        assert result.return_code == (None if abends else 0)
        assert adapter._capture.result is result
    else:
        with pytest.raises(base.AdapterUnavailable) as caught:
            adapter.execute(scenario, workdir)
        assert caught.value.gap_id == gap_id
    assert events == calls
    assert adapter._capture.datasets == kept


@pytest.mark.parametrize(("started", "available", "declared", "missing"), UNIKIX_STAGE_SESSIONS)
def test_unikix_declares_stage_observations_with_the_business_datasets(
    carddemo_work_root: Path,
    started: bool,
    available: frozenset[str],
    declared: bool,
    missing: str | None,
) -> None:
    assert base.AFTER_PREFIX + "*" in unikix_adapter._STATIC_OBSERVATIONS
    adapter = UniKixAdapter(work_root=carddemo_work_root, repo_root=Path(_TESTS_ROOT).parent)
    if started:
        adapter._session_ready = True
        adapter._commands = _ProbedCommands(available)
    else:
        assert adapter.observations() == set(unikix_adapter._STATIC_OBSERVATIONS)
    assert adapter.can_observe("ACCTDATA") is declared
    for name in (STAGE_ACCOUNTS, UNIKIX_STAGE_BACKUP):
        assert adapter.can_observe(name) is declared
        assert adapter.observation_gap(name) == (None if declared else "GAP-MIS-007")
        if missing is not None:
            assert missing in adapter._unobservable(name)


@pytest.mark.parametrize(("name", "jobs", "raised", "detail"), UNIKIX_STAGE_GUARD_CASES)
def test_unikix_refuses_a_stage_observation_without_one_job_end_before_the_region(
    csd_gate_work_root: Path,
    name: str,
    jobs: tuple[str, ...],
    raised: type[Exception],
    detail: str,
) -> None:
    adapter = UniKixAdapter(work_root=csd_gate_work_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter._session_ready = True
    adapter._env = object()
    adapter._commands = _GateCommands()
    adapter._batch = _GateBatch()
    scenario = _unikix_stage_scenario((name,), jobs)
    with pytest.raises(raised) as caught:
        adapter._plan_for(
            scenario, csd_gate_work_root / UNIKIX_STAGE_SCENARIO, not jobs, [], jobs
        )
    assert detail in str(caught.value)
    if raised is HarnessError:
        assert name in str(caught.value)
    assert adapter._plan is None and not adapter._touched
    assert list(csd_gate_work_root.iterdir()) == []


# Region stage observations: @REGION-FILES just before job <JOB> of a
# scenario's job chain is submitted (@BEFORE:<JOB>:@REGION-FILES) and at the
# end of <JOB> (@AFTER:<JOB>:@REGION-FILES). REGION_STAGE_CLOSED holds the
# files CLOSEFIL closes on app/ [app/jcl/CLOSEFIL.jcl:22-30], which CEMT SET
# FILE CLOSED leaves disabled.
REGION_STAGE_CLOSED = frozenset({"ACCTDAT", "CCXREF", "CXACAIX", "TRANSACT", "USRSEC"})

REGION_STAGE_NAMES = [
    pytest.param("before", "OPENFIL", id="before-a-job"),
    pytest.param("after", "CLOSEFIL", id="after-a-job"),
    pytest.param("before", "TRNRPT00", id="before-the-generated-job"),
]

REGION_STAGE_REFUSED_PARTS = [
    pytest.param("during", "OPENFIL", id="unknown-stage"),
    pytest.param("BEFORE", "OPENFIL", id="stage-in-capitals"),
    pytest.param(None, "OPENFIL", id="stage-not-text"),
    pytest.param("before", "", id="empty-job"),
    pytest.param("after", "CLOSE:FIL", id="colon-in-job"),
    pytest.param("before", "OPEN FIL", id="space-in-job"),
    pytest.param("after", None, id="job-not-text"),
]

REGION_STAGE_NOT_NAMES = [
    pytest.param("@BEFORE:", id="prefix-only"),
    pytest.param("@BEFORE:OPENFIL", id="no-observation"),
    pytest.param("@BEFORE::@REGION-FILES", id="empty-job"),
    pytest.param("@BEFORE:OPEN FIL:@REGION-FILES", id="space-in-job"),
    pytest.param("@AFTER:CLOSEFIL:@REGION-FILES:OPENFIL", id="third-part"),
    pytest.param("@BEFORE:OPENFIL:@CATALOG", id="another-reserved-observation"),
    pytest.param("@AFTER:CLOSEFIL:@TDQ:JOBS", id="a-reserved-observation-with-a-colon"),
    pytest.param("@BEFORE:OPENFIL:ACCTDATA", id="a-dataset-before-a-job"),
    pytest.param(STAGE_ACCOUNTS, id="a-dataset-stage"),
    pytest.param(base.REGION_FILES, id="the-end-of-run-inquiry"),
    pytest.param(None, id="not-text"),
]

# Every pattern family but the region file inquiry itself, a dataset-stage
# pattern included: none of them covers a region stage observation.
REGION_STAGE_OTHER_PATTERNS = frozenset(
    {
        base.BUSINESS_DATASETS,
        base.CATALOG,
        base.TDQ_JOBS,
        base.SCREENS,
        base.AIX_PREFIX + "*",
        base.AFTER_PREFIX + "*",
        base.AFTER_PREFIX,
        base.BEFORE_PREFIX + "*",
        base.BEFORE_PREFIX,
    }
)

# Each case: whether the session has started, the commands.yaml entries its
# probes found available, whether the region stage observations are
# declared, whether the business datasets are, and what the reason they are
# not names.
UNIKIX_REGION_STAGE_SESSIONS = [
    pytest.param(False, frozenset(), True, True, None, id="before-the-session"),
    pytest.param(
        True, frozenset({"region_file_inquire"}), True, False, None, id="inquiry-without-unloads"
    ),
    pytest.param(
        True,
        UNIKIX_STAGE_UNLOADS | set(unikix_adapter._FILE_CONTROL),
        False,
        True,
        "region_file_inquire: probe found no region_file_inquire",
        id="unloads-without-inquiry",
    ),
]

# Each case: the scenario, the region file states its run shows (name -> the
# files closed and disabled; every other fct.lst file open and enabled), and
# the status compare gives against the committed baseline.
REGION_STAGE_COMPARISONS = [
    pytest.param(
        "CFG-OPENFIL-normal-01",
        {"@BEFORE:OPENFIL:@REGION-FILES": frozenset(), base.REGION_FILES: frozenset()},
        "passed",
        id="openfil-leaves-every-file-open",
    ),
    pytest.param(
        "CFG-OPENFIL-normal-01",
        {"@BEFORE:OPENFIL:@REGION-FILES": REGION_STAGE_CLOSED, base.REGION_FILES: frozenset()},
        "failing",
        id="openfil-from-closed-files",
    ),
    pytest.param(
        "CFG-CLOSEFIL-normal-01",
        {"@BEFORE:CLOSEFIL:@REGION-FILES": frozenset(), base.REGION_FILES: REGION_STAGE_CLOSED},
        "passed",
        id="closefil-closes-its-five-files",
    ),
    pytest.param(
        "CFG-CLOSEFIL-normal-01",
        {"@BEFORE:CLOSEFIL:@REGION-FILES": frozenset(), base.REGION_FILES: frozenset()},
        "failing",
        id="closefil-leaves-every-file-open",
    ),
    pytest.param(
        "BAT-RUNORDER-normal-01",
        {"@AFTER:CLOSEFIL:@REGION-FILES": REGION_STAGE_CLOSED, base.REGION_FILES: frozenset()},
        "passed",
        id="runorder-closed-after-closefil-open-at-the-end",
    ),
    pytest.param(
        "BAT-RUNORDER-normal-01",
        {"@AFTER:CLOSEFIL:@REGION-FILES": frozenset(), base.REGION_FILES: frozenset()},
        "failing",
        id="runorder-every-file-open-after-closefil",
    ),
]

# Region stage observations on unikix: UNIKIX_STAGE_JOBS with the region
# files expected before POSTTRAN, at its end beside its dataset stage
# STAGE_ACCOUNTS, before INTCALC and at the end of the run. Each region file
# inquiry returns the next of UNIKIX_REGION_INQUIRIES.
UNIKIX_REGION_BEFORE_POSTTRAN = "@BEFORE:POSTTRAN:@REGION-FILES"
UNIKIX_REGION_AFTER_POSTTRAN = "@AFTER:POSTTRAN:@REGION-FILES"
UNIKIX_REGION_BEFORE_INTCALC = "@BEFORE:INTCALC:@REGION-FILES"
UNIKIX_REGION_EXPECTED = (
    STAGE_ACCOUNTS,
    UNIKIX_REGION_BEFORE_POSTTRAN,
    UNIKIX_REGION_AFTER_POSTTRAN,
    UNIKIX_REGION_BEFORE_INTCALC,
    base.REGION_FILES,
)
UNIKIX_REGION_INQUIRIES = tuple(f"region file inquiry {number}\n".encode("ascii") for number in range(1, 5))
# POSTTRAN's part of the run: the inquiry before it is submitted, then, once
# it has completed, the inquiry at its end before its dataset stage's unload
# closes and reopens the region files.
UNIKIX_REGION_POSTTRAN = [
    ("inquire", 1),
    ("kept", UNIKIX_REGION_BEFORE_POSTTRAN),
    ("submit", "POSTTRAN"),
    ("wait", "POSTTRAN"),
    ("inquire", 2),
    ("kept", UNIKIX_REGION_AFTER_POSTTRAN),
    ("close",),
    ("observe", ("ACCTDATA",)),
    ("kept", STAGE_ACCOUNTS),
    ("open",),
]

# Each case: the job that abends (None: none), the calls in order, and the
# observations the Result and the evidence hold.
UNIKIX_REGION_EXECUTIONS = [
    pytest.param(
        None,
        UNIKIX_REGION_POSTTRAN
        + [("inquire", 3), ("kept", UNIKIX_REGION_BEFORE_INTCALC)]
        + UNIKIX_STAGE_RUN["INTCALC"]
        + UNIKIX_STAGE_RUN["TRANBKP"]
        + [("inquire", 4), ("kept", base.REGION_FILES)],
        {
            UNIKIX_REGION_BEFORE_POSTTRAN: UNIKIX_REGION_INQUIRIES[0],
            UNIKIX_REGION_AFTER_POSTTRAN: UNIKIX_REGION_INQUIRIES[1],
            STAGE_ACCOUNTS: UNIKIX_STAGE_LEFT["POSTTRAN"]["ACCTDATA"],
            UNIKIX_REGION_BEFORE_INTCALC: UNIKIX_REGION_INQUIRIES[2],
            base.REGION_FILES: UNIKIX_REGION_INQUIRIES[3],
        },
        id="every-job-completes",
    ),
    pytest.param(
        "POSTTRAN",
        UNIKIX_REGION_POSTTRAN + [("inquire", 3), ("kept", base.REGION_FILES)],
        {
            UNIKIX_REGION_BEFORE_POSTTRAN: UNIKIX_REGION_INQUIRIES[0],
            UNIKIX_REGION_AFTER_POSTTRAN: UNIKIX_REGION_INQUIRIES[1],
            STAGE_ACCOUNTS: UNIKIX_STAGE_LEFT["POSTTRAN"]["ACCTDATA"],
            base.REGION_FILES: UNIKIX_REGION_INQUIRIES[2],
        },
        id="an-abend-leaves-the-later-region-stages-absent",
    ),
]


def _region_file_states(closed: frozenset[str]) -> bytes:
    """``@REGION-FILES`` of the 14 fct.lst files with ``closed`` closed and
    disabled and every other file open and enabled."""
    return base.format_region_files(
        (name, name not in closed, name not in closed) for name in bpe.Resources.load().region_files
    )


@pytest.mark.parametrize(("stage", "job"), REGION_STAGE_NAMES)
def test_region_stage_names_round_trip_and_match_only_the_region_file_inquiry(
    stage: str, job: str
) -> None:
    name = base.region_stage_observation(stage, job)
    prefix = base.BEFORE_PREFIX if stage == "before" else base.AFTER_PREFIX
    assert name == f"{prefix}{job}:{base.REGION_FILES}"
    assert base.parse_region_stage(name) == (stage, job)
    assert base.parse_after_job(name) is None  # never a dataset stage
    assert base.observation_matches(name, {base.REGION_FILES})
    assert not base.observation_matches(name, REGION_STAGE_OTHER_PATTERNS)
    # gnucobol-batch declares "@AFTER:*" for its dataset stages, never the
    # region file inquiry, so it is blocked under the inquiry's gap.
    gnucobol = GnuCOBOLBatchAdapter()  # construction creates nothing
    assert not gnucobol.can_observe(name)
    assert gnucobol.observation_gap(name) == "GAP-MIS-011"
    assert runner._observation_gap(SimpleNamespace(), name) == "GAP-MIS-011"
    # Neither adapter unloads it as a dataset stage at the end of a job.
    wanted = {name, STAGE_ACCOUNTS}
    grouped = {"POSTTRAN": [(STAGE_ACCOUNTS, "ACCTDATA")]}
    assert GnuCOBOLBatchAdapter._stage_observations(STAGE_CHAIN, (job, "POSTTRAN"), wanted) == grouped
    assert UniKixAdapter._stage_observations(STAGE_CHAIN, (job, "POSTTRAN"), wanted) == grouped


@pytest.mark.parametrize(("stage", "job"), REGION_STAGE_REFUSED_PARTS)
def test_region_stage_name_refuses_a_malformed_part(stage: object, job: object) -> None:
    with pytest.raises(ValueError):
        base.region_stage_observation(stage, job)


@pytest.mark.parametrize("key", REGION_STAGE_NOT_NAMES)
def test_parse_region_stage_reads_no_stage_from_any_other_name(key: object) -> None:
    assert base.parse_region_stage(key) is None


@pytest.mark.parametrize("stage", ["before", "after"])
def test_schema_refuses_a_region_stage_observation_of_an_online_scenario(stage: str) -> None:
    scenario = scenarios.get(EVIDENCE_SCENARIO)
    assert scenarios.job_names(scenario) == () and scenario.inputs.get("steps")
    name = base.region_stage_observation(stage, "CLOSEFIL")
    data = copy.deepcopy(scenario.data)
    data["expected"].setdefault("file_state", {})[name] = {"records": ["ACCTDAT open enabled"]}
    online = dataclasses.replace(scenario, data=data, inputs=data["inputs"], expected=data["expected"])
    added = set(scenarios.validate(online)) - set(scenarios.validate(scenario))
    assert len(added) == 1, added
    (line,) = added
    assert f": expected.file_state.{name}: " in line
    assert "names job CLOSEFIL, which the scenario does not run (the scenario runs no job)" in line


def test_decode_result_reads_a_region_stage_observation_as_region_file_lines() -> None:
    before = base.region_stage_observation("before", "POSTTRAN")
    after = base.region_stage_observation("after", "TRANREPT")
    scenario = _chain_with("file_state", before, {"count": 14})
    scenario.expected["file_state"][after] = {"count": 14}  # the copy's
    delivered = base.Result(
        return_code=4,
        datasets={before: _region_file_states(frozenset()), after: _region_file_states(REGION_STAGE_CLOSED)},
    )
    decoded = baselines.decode_result(scenario, delivered)["file_state"]
    files = bpe.Resources.load().region_files
    assert decoded[before] == [f"{name} open enabled" for name in files]
    assert decoded[after] == [
        f"{name} {'closed disabled' if name in REGION_STAGE_CLOSED else 'open enabled'}" for name in files
    ]
    assert delivered.datasets[before] == _region_file_states(frozenset())  # raw bytes are never changed
    absent = baselines.decode_result(scenario, base.Result(return_code=4, datasets={}))
    assert absent["file_state"][before] == {"@missing": True}
    # Like @REGION-FILES, a region stage has no raw .bin in an observed baseline.
    named = SimpleNamespace(expected={"file_state": {before: {"count": 14}, "ACCTDATA": {"count": 1}}})
    accounts = base.Result(return_code=0, datasets={before: b"open\n", "ACCTDATA": b"A" * 300})
    assert capture.raw_datasets(named, accounts) == {"ACCTDATA": b"A" * 300}


@pytest.mark.parametrize(("scenario_id", "states", "status"), REGION_STAGE_COMPARISONS)
def test_region_stage_baselines_compare_the_region_state_around_the_job(
    scenario_id: str, states: dict[str, frozenset[str]], status: str
) -> None:
    scenario = scenarios.get(scenario_id)
    named = {
        name
        for name in scenario.expected["file_state"]
        if name == base.REGION_FILES or base.parse_region_stage(name) is not None
    }
    assert named == set(states)  # the committed scenario names exactly these
    # The committed scenario and baseline, judged on their region file states alone.
    region_only = SimpleNamespace(
        id=scenario.id,
        inputs=scenario.inputs,
        preconditions=scenario.preconditions,
        expected={"file_state": {name: scenario.expected["file_state"][name] for name in named}},
    )
    result = base.Result(return_code=0, datasets={name: _region_file_states(closed) for name, closed in states.items()})
    found, reason, diff = baselines.compare(region_only, baselines.load(scenario_id), result, "unikix", None)
    assert found == status, diff
    assert reason == (None if status == "passed" else "assertion"), diff


@pytest.mark.parametrize(
    ("started", "available", "declared", "datasets_declared", "missing"), UNIKIX_REGION_STAGE_SESSIONS
)
def test_unikix_declares_region_stage_observations_with_the_region_file_inquiry(
    carddemo_work_root: Path,
    started: bool,
    available: frozenset[str],
    declared: bool,
    datasets_declared: bool,
    missing: str | None,
) -> None:
    adapter = UniKixAdapter(work_root=carddemo_work_root, repo_root=Path(_TESTS_ROOT).parent)
    if started:
        adapter._session_ready = True
        adapter._commands = _ProbedCommands(available)
    assert adapter.can_observe(base.REGION_FILES) is declared
    assert adapter.can_observe(STAGE_ACCOUNTS) is datasets_declared
    for stage in ("before", "after"):
        name = base.region_stage_observation(stage, "CLOSEFIL")
        assert adapter.can_observe(name) is declared
        assert adapter.observation_gap(name) == (None if declared else "GAP-MIS-011")
        assert adapter._observation_entries(name) == ("region_file_inquire",)
        if missing is not None:
            reason = adapter._unobservable(name)
            assert missing in reason and "the region files cannot be closed" not in reason


@pytest.mark.parametrize("stage", ["before", "after"])
def test_unikix_source_identity_covers_fct_lst_for_a_region_stage_observation(
    carddemo_work_root: Path, stage: str
) -> None:
    adapter = UniKixAdapter(work_root=carddemo_work_root, repo_root=Path(_TESTS_ROOT).parent)
    fct = unikix_adapter._APP + "catlg/tpe/fct.lst"

    def units(file_state: dict) -> dict:
        scenario = SimpleNamespace(
            id="CFG-CLOSEFIL-normal-01",
            inputs={"job": "CLOSEFIL"},
            expected={"messages": ["STEP CLCIFIL RC 0000"], "file_state": file_state},
            inventory_ids=[],
        )
        return adapter.source_identity(scenario)["units"]

    assert fct not in units({})
    assert fct in units({base.region_stage_observation(stage, "CLOSEFIL"): {"count": 14}})


@pytest.mark.parametrize(("abends", "calls", "kept"), UNIKIX_REGION_EXECUTIONS)
def test_unikix_reads_each_region_stage_observation_around_its_job(
    monkeypatch: pytest.MonkeyPatch,
    csd_gate_work_root: Path,
    abends: str | None,
    calls: list[tuple],
    kept: dict[str, bytes],
) -> None:
    # A work root of this case's own inside the session's, so the working
    # directory named after the real chain scenario is never the one that
    # scenario's own run uses.
    work_root = csd_gate_work_root
    adapter = UniKixAdapter(work_root=work_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter._capture = evidence.Capture()
    adapter.deadline = time.monotonic() + 300
    scenario = _unikix_stage_scenario(UNIKIX_REGION_EXPECTED, UNIKIX_STAGE_JOBS)
    wanted = base.required_observations(scenario.expected)
    before, after = UniKixAdapter._region_stage_observations(scenario.id, UNIKIX_STAGE_JOBS, wanted)
    assert before == {"POSTTRAN": UNIKIX_REGION_BEFORE_POSTTRAN, "INTCALC": UNIKIX_REGION_BEFORE_INTCALC}
    assert after == {"POSTTRAN": UNIKIX_REGION_AFTER_POSTTRAN}
    adapter._plan = unikix_adapter._Plan(
        scenario_id=scenario.id,
        online=False,
        steps=[],
        sessions=1,
        jobs=UNIKIX_STAGE_JOBS,
        dateparm=None,
        stages=UniKixAdapter._stage_observations(scenario.id, UNIKIX_STAGE_JOBS, wanted),
        regions_before=before,
        regions_after=after,
    )
    service = bpe.BatchService(SimpleNamespace(vars={}), SimpleNamespace(), bpe.Resources.load())
    catalog: dict[str, bytes] = {}
    events: list[tuple] = []
    region = {"closed": False}
    inquiries = list(UNIKIX_REGION_INQUIRIES)

    def submit(name: str, jcl_text: str | None, entry_id: str, budget: object) -> bpe.JobRun:
        assert not region["closed"], "a job was submitted while the region files were closed"
        events.append(("submit", name))
        return bpe.JobRun(name, name, name, entry_id, str(len(events)))

    def wait(run: bpe.JobRun, budget: object) -> bpe.JobRun:
        events.append(("wait", run.name))
        catalog.update(UNIKIX_STAGE_LEFT[run.name])
        run.completed = True
        run.steps = [bpe.StepOutcome("STEP15", None if run.name == abends else 0)]
        return run

    def observe(names: list[str], *, deadline: float | None, keep: object) -> dict[str, bytes]:
        assert region["closed"] and deadline == adapter.deadline
        events.append(("observe", tuple(names)))
        for logical in names:
            keep(logical, catalog[logical])
        return {logical: catalog[logical] for logical in names}

    def inquire() -> bytes:
        assert not region["closed"], "the region files were inquired while closed for BPE"
        events.append(("inquire", len(UNIKIX_REGION_INQUIRIES) - len(inquiries) + 1))
        return inquiries.pop(0)

    def set_closed(closed: bool, label: str) -> None:
        assert region["closed"] is not closed, f"region files {label} twice"
        region["closed"] = closed
        events.append((label,))

    keep = adapter._keep

    def recorded_keep(name: str, data: bytes) -> None:
        events.append(("kept", name))
        keep(name, data)

    service._submit, service._wait, service.observe = submit, wait, observe
    adapter._batch = service
    monkeypatch.setattr(adapter, "_region_files", inquire)
    monkeypatch.setattr(adapter, "_keep", recorded_keep)
    monkeypatch.setattr(adapter, "_close_region_files", lambda: set_closed(True, "close"))
    monkeypatch.setattr(adapter, "_open_region_files", lambda: set_closed(False, "open"))
    result = adapter.execute(scenario, work_root / UNIKIX_STAGE_SCENARIO)
    assert events == calls
    assert result.datasets == kept
    assert adapter._capture.datasets == kept
    assert result.return_code == (None if abends else 0)
    # The state before a job is asserted under its stage name, never kept as evidence alone.
    assert "adapter/region-files-before.txt" not in adapter._capture.sysout



# The job-chain proof's stage checks (harness.report) on synthetic decoded
# outcomes and baseline expectations: the accounts as POSTTRAN leaves them,
# and the chain's TRANSACT-BKUP generation and TRANSACT as TRANBKP leaves
# them. Only the problems naming a stage observation are judged: the rest of
# each synthetic outcome, which other proof checks read, is left out.
PROOF_POSTINT = "BAT-POSTINT-normal-01"
PROOF_ACCOUNT_IDS = range(1, 51)
PROOF_BACKUP_COUNT = 262
PROOF_POSTED = report.PROOF_POSTED_ACCOUNTS
PROOF_BACKUP = report.PROOF_FIRST_BACKUP
PROOF_EMPTIED = report.PROOF_EMPTIED_TRANSACT
PROOF_LATER_TS = "2022-07-07-10.00.00.000000"

# (scenario id, mutation of the conforming file_state, the one stage problem
# it yields, or None for the conforming outcome itself).
PROOF_STAGE_OUTCOMES = [
    pytest.param(STAGE_CHAIN, None, None, id="chain-conforming"),
    pytest.param(PROOF_POSTINT, None, None, id="postint-conforming"),
    pytest.param(
        PROOF_POSTINT, lambda state: state.pop(PROOF_POSTED),
        f"{PROOF_POSTED} is not in the decoded outcome", id="postint-posted-accounts-absent",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state.update({PROOF_POSTED: {baselines.MISSING: True}}),
        f"{PROOF_POSTED} was not produced", id="posted-accounts-not-produced",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_POSTED].append(dict(state[PROOF_POSTED][0])),
        f"{PROOF_POSTED} holds 51 record(s), not 50", id="posted-accounts-count",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_POSTED][6].update({"ACCT-ID": "51"}),
        f"{PROOF_POSTED} lacks the account(s) 7", id="posted-account-missing",
    ),
    pytest.param(
        STAGE_CHAIN,
        lambda state: state[PROOF_POSTED][6].update({"ACCT-CURR-CYC-CREDIT": "0.00", "ACCT-CURR-CYC-DEBIT": "0.00"}),
        f"{PROOF_POSTED} account(s) 7 have zero ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT",
        id="posted-account-without-postings",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_POSTED][6].update({"ACCT-CURR-CYC-CREDIT": "1,164.87"}),
        f"1 {PROOF_POSTED} record(s) have an ACCT-ID, ACCT-CURR-CYC-CREDIT or ACCT-CURR-CYC-DEBIT that is not "
        "decoded number text",
        id="posted-cycle-not-number-text",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state["ACCTDATA"][-1].update({"ACCT-CURR-BAL": "1964.64"}),
        f"account 50's ACCT-CURR-BAL differ between {PROOF_POSTED} and the final ACCTDATA",
        id="last-account-rewritten",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_BACKUP].pop(),
        f"{PROOF_BACKUP} holds 261 record(s), not 262", id="backup-count",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_BACKUP].insert(0, state[PROOF_BACKUP].pop(1)),
        f"{PROOF_BACKUP} is not in ascending TRAN-ID order", id="backup-order",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_BACKUP][-1].update({"TRAN-ID": report.PROOF_INTEREST_IDS[0]}),
        f"{PROOF_BACKUP} holds 1 interest record(s) ({report.PROOF_INTEREST_IDS[0]})", id="backup-interest-record",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_BACKUP][0].update({"TRAN-PROC-TS": PROOF_LATER_TS}),
        f"1 {PROOF_BACKUP} record(s) have a TRAN-PROC-TS other than {report.PROOF_PROC_TS[0]}",
        id="backup-timestamp",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_EMPTIED].append(dict(state[PROOF_BACKUP][0])),
        f"{PROOF_EMPTIED} holds 1 record(s), not 0", id="transact-not-emptied",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state.update({PROOF_EMPTIED: {baselines.MISSING: True}}),
        f"{PROOF_EMPTIED} was not produced", id="emptied-transact-not-produced",
    ),
]

# (scenario id, mutation of the conforming file_state expectation, the one
# stage problem it yields, or None for the conforming expectation itself).
PROOF_STAGE_EXPECTATIONS = [
    pytest.param(STAGE_CHAIN, None, None, id="chain-conforming"),
    pytest.param(PROOF_POSTINT, None, None, id="postint-conforming"),
    pytest.param(
        PROOF_POSTINT, lambda state: state.pop(PROOF_POSTED),
        f"its baseline does not expect {PROOF_POSTED}", id="postint-posted-accounts-unexpected",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state.update({PROOF_POSTED: {"count": 50}}),
        f"its baseline expects {PROOF_POSTED} as a mapping of count, not its full record list",
        id="posted-accounts-count-only",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_POSTED]["records"].pop(),
        f"its baseline expects 49 {PROOF_POSTED} record(s), not the 50 accounts", id="posted-accounts-49",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state[PROOF_POSTED]["records"][6].pop("ACCT-CURR-CYC-DEBIT"),
        f"1 {PROOF_POSTED} record(s) of its baseline do not name ACCT-CURR-CYC-DEBIT",
        id="posted-account-without-debit",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state.update({PROOF_BACKUP: {"contains": state[PROOF_BACKUP]["records"][:2]}}),
        f"its baseline expects {PROOF_BACKUP} as a mapping of contains, not its full record list",
        id="backup-subsequence-only",
    ),
    pytest.param(
        STAGE_CHAIN, lambda state: state.pop(PROOF_EMPTIED),
        f"its baseline does not expect {PROOF_EMPTIED}", id="emptied-transact-unexpected",
    ),
]


def _proof_stage_state() -> dict:
    """The decoded ``file_state`` of a conforming run: every account with
    posted cycle totals after POSTTRAN and, but for account 50, interest
    added and zero cycle totals at the end; TRANBKP's 262 posted records in
    TRAN-ID order at the default clock; and an empty TRANSACT."""
    posted = [
        {"ACCT-ID": str(number), "ACCT-CURR-BAL": "1288.10", "ACCT-CURR-CYC-CREDIT": "1164.87",
         "ACCT-CURR-CYC-DEBIT": "-70.77"}
        for number in PROOF_ACCOUNT_IDS
    ]
    rewritten = {"ACCT-CURR-BAL": "1302.66", "ACCT-CURR-CYC-CREDIT": "0.00", "ACCT-CURR-CYC-DEBIT": "0.00"}
    final = [dict(record, **rewritten) for record in posted[:-1]] + [dict(posted[-1])]
    backup = [
        {"TRAN-ID": f"{number:016d}", "TRAN-CARD-NUM": "0500024453765740", "TRAN-AMT": "504.77",
         "TRAN-PROC-TS": report.PROOF_PROC_TS[0]}
        for number in range(1, PROOF_BACKUP_COUNT + 1)
    ]
    return {PROOF_POSTED: posted, "ACCTDATA": final, PROOF_BACKUP: backup, PROOF_EMPTIED: []}


def _stage_problems(problems: list[str]) -> list[str]:
    return [problem for problem in problems if base.AFTER_PREFIX in problem]


@pytest.mark.parametrize(("scenario_id", "mutate", "problem"), PROOF_STAGE_OUTCOMES)
def test_proof_outcome_checks_each_stage_observation(scenario_id: str, mutate, problem: str | None) -> None:
    state = _proof_stage_state()
    if mutate is not None:
        mutate(state)
    found = _stage_problems(report._proof_outcome_problems(scenario_id, {"file_state": state}, {}))
    if problem is None:
        assert found == []
    else:
        assert len(found) == 1 and problem in found[0], found


@pytest.mark.parametrize(("scenario_id", "mutate", "problem"), PROOF_STAGE_EXPECTATIONS)
def test_proof_expectation_pins_each_stage_observation(scenario_id: str, mutate, problem: str | None) -> None:
    state = {name: {"records": records} for name, records in _proof_stage_state().items()}
    if mutate is not None:
        mutate(state)
    found = _stage_problems(report._proof_expectation_problems(scenario_id, {"file_state": state}))
    if problem is None:
        assert found == []
    else:
        assert len(found) == 1 and problem in found[0], found


# Closing evidence: what report._entry_problems accepts as the evidence that
# settled a register entry, its resolving_evidence or an earlier closure's in
# superseded_evidence. The execution ids are invented ids of valid form; the
# commits are the suite's starting commit, in full and abbreviated.
CLOSING_GAP = "GAP-UNC-008"
CLOSING_UNIKIX = "unikix-20250101T000000Z-0123abcd"
CLOSING_OTHER_UNIKIX = "unikix-20250102T000000Z-0123abcd"
CLOSING_GNUCOBOL = "gnucobol-batch-0123456789abcdef"
CLOSING_SCENARIO = "FIN-INTCALC-normal-01"
CLOSING_COMMIT = "93ebec71ff7b59a68cedd7c501afe496a8eaa68d"
CLOSING_FOLDER = f"tests/reports/unikix/{CLOSING_UNIKIX}"
CLOSING_REJECTED = f"{CLOSING_GAP}: resolving_evidence must be "

# (fields over a closed uncertain-purpose entry, and the start and a part of
# the one problem they give, or None when the entry is valid).
CLOSING_EVIDENCE = [
    pytest.param({"resolving_evidence": {"execution_id": CLOSING_UNIKIX, "evidence_path": CLOSING_FOLDER}},
                 None, id="unikix-execution"),
    pytest.param({"resolving_evidence": {"execution_id": CLOSING_UNIKIX, "scenario_id": CLOSING_SCENARIO,
                                         "evidence_path": f"{CLOSING_FOLDER}/{CLOSING_SCENARIO}"}},
                 None, id="unikix-scenario-folder"),
    pytest.param({"resolving_evidence": {"execution_id": CLOSING_GNUCOBOL, "scenario_id": CLOSING_SCENARIO,
                                         "evidence_path": f"tests/reports/results/{CLOSING_SCENARIO}.yaml"}},
                 None, id="gnucobol-batch-record"),
    pytest.param({"resolving_evidence": {"commit": CLOSING_COMMIT}}, None, id="commit-40-hex"),
    pytest.param({"resolving_evidence": {"commit": CLOSING_COMMIT[:7]}}, None, id="commit-7-hex"),
    pytest.param({"resolving_evidence": "done"}, (CLOSING_REJECTED, "got 'done'"), id="plain-text"),
    pytest.param({"resolving_evidence": {"note": "resolved"}}, (CLOSING_REJECTED, "unknown key(s) 'note'"),
                 id="unrelated-mapping"),
    pytest.param({"resolving_evidence": {"execution_id": CLOSING_UNIKIX}}, (CLOSING_REJECTED, "no evidence_path"),
                 id="execution-without-path"),
    pytest.param({"resolving_evidence": {"evidence_path": CLOSING_FOLDER}}, (CLOSING_REJECTED, "no execution_id"),
                 id="path-without-execution"),
    pytest.param({"resolving_evidence": {"execution_id": "unikix-2025-01-01",
                                         "evidence_path": "tests/reports/unikix/unikix-2025-01-01"}},
                 (CLOSING_REJECTED, "execution_id 'unikix-2025-01-01' is neither"), id="malformed-execution-id"),
    pytest.param({"resolving_evidence": {"execution_id": CLOSING_UNIKIX,
                                         "evidence_path": f"tests/reports/unikix/{CLOSING_OTHER_UNIKIX}"}},
                 (CLOSING_REJECTED, f"is neither {CLOSING_FOLDER} nor"), id="path-of-another-execution"),
    pytest.param({"resolving_evidence": {"execution_id": CLOSING_UNIKIX, "scenario_id": CLOSING_SCENARIO,
                                         "evidence_path": f"{CLOSING_FOLDER}/FIN-INTCALC-normal-02"}},
                 (CLOSING_REJECTED, f"nor {CLOSING_FOLDER}/{CLOSING_SCENARIO}"), id="path-of-another-scenario"),
    pytest.param({"resolving_evidence": {"execution_id": CLOSING_GNUCOBOL,
                                         "evidence_path": f"tests/reports/results/{CLOSING_SCENARIO}.yaml"}},
                 (CLOSING_REJECTED, "so it needs scenario_id"), id="gnucobol-batch-without-scenario"),
    pytest.param({"resolving_evidence": {"execution_id": CLOSING_UNIKIX, "evidence_path": CLOSING_FOLDER,
                                         "verdict": "passed"}},
                 (CLOSING_REJECTED, "unknown key(s) 'verdict'"), id="unknown-key"),
    pytest.param({"resolving_evidence": {"commit": "HEAD"}}, (CLOSING_REJECTED, "is not a git commit id"),
                 id="commit-not-hex"),
    pytest.param({"resolving_evidence": {"commit": CLOSING_COMMIT, "execution_id": CLOSING_UNIKIX,
                                         "evidence_path": CLOSING_FOLDER}},
                 (CLOSING_REJECTED, "commit stands alone"), id="commit-with-execution"),
    pytest.param({"resolving_evidence": {"execution_id": CLOSING_UNIKIX, "evidence_path": 42}},
                 (CLOSING_REJECTED, "evidence_path must be text"), id="value-not-text"),
    pytest.param({"resolving_evidence": None}, (f"{CLOSING_GAP}: a closed entry names its resolving_evidence", ""),
                 id="closed-without-evidence"),
    pytest.param({"status": "open", "resolving_evidence": None,
                  "superseded_evidence": [{"commit": CLOSING_COMMIT}, "done"]},
                 (f"{CLOSING_GAP}: superseded_evidence[1] must be ", "got 'done'"), id="superseded-text"),
]


@pytest.mark.parametrize(("fields", "problem"), CLOSING_EVIDENCE)
def test_entry_closing_evidence_shape(fields: dict, problem: tuple[str, str] | None) -> None:
    entry = {
        "gap_id": CLOSING_GAP, "category": "uncertain-purpose", "status": "closed",
        "unknown": "Whether truncating the monthly interest is intended.",
        "needed": "The owner's statement of the intended rounding.",
        "affected_inventory_ids": ["INV-FIN-003"],
    }
    entry.update(fields)
    problems = report._entry_problems(CLOSING_GAP, entry)
    if problem is None:
        assert problems == []
    else:
        start, part = problem
        assert len(problems) == 1 and problems[0].startswith(start) and part in problems[0], problems


# report._validate_evidence on a record of NON_RAN_SCENARIO, which only unikix
# runs, with no outcome to compare (report.Evidence.ran false) and whose
# execution fingerprint is the scenario's current one, its validation.yaml
# writer recording the record instead of writing it, so nothing reaches an
# evidence tree. Each status with the reason and gap result.yaml records for
# it.
NON_RAN_SCENARIO = "SEC-COSGN00C-normal-01"
NON_RAN_RECORDS = [
    pytest.param(results.STATUS_BLOCKED, "adapter-unavailable", "GAP-MIS-009", id="blocked"),
    pytest.param(results.STATUS_UNEXECUTED, "missing-prerequisite", "GAP-MIS-014", id="implemented-unexecuted"),
    pytest.param(results.STATUS_FAILING, "timeout", None, id="failing-timeout"),
]
# Each judgement: the import or a revalidation (``importing``), whether the
# observed payload holds every observation the scenario and its baseline name
# or is absent, and whether the recorded verdict is then written.
NON_RAN_JUDGEMENTS = [
    pytest.param(False, False, False, id="revalidation-without-observed"),
    pytest.param(True, False, True, id="import-without-observed"),
    pytest.param(False, True, True, id="revalidation-with-every-observation"),
]
NON_RAN_FINGERPRINT = "5" * 64
NON_RAN_LACKS = "observed (the run's return code, messages and screens)"
# The result.yaml sha256 that in-memory evidence carries, as read_evidence
# sets it, so the validation.yaml judged from it can bind its raw record.
BOUND_RESULT_SHA256 = "4" * 64


def _every_observation(scenario: scenarios.Scenario, baseline: baselines.Baseline) -> dict:
    """An observed payload holding every field the scenario's and the
    baseline's expected screens name, each step's ``@map`` as asserted and a
    matcher's field blank, and the scenario's expected messages."""
    screens: list[dict[str, str]] = []
    for expected in (scenario.expected, baseline.expected):
        steps = expected.get("screens")
        for index, step in enumerate(steps if isinstance(steps, list) else []):
            if index == len(screens):
                screens.append({})
            if isinstance(step, Mapping):
                screens[index].update((str(name), value if isinstance(value, str) else "")
                                      for name, value in step.items())
    messages = scenario.expected.get("messages")
    messages = [message for message in messages if isinstance(message, str)] if isinstance(messages, list) else []
    return {"return_code": None, "messages": messages, "screens": screens}


@pytest.mark.parametrize(("importing", "observed", "written"), NON_RAN_JUDGEMENTS)
@pytest.mark.parametrize(("status", "reason", "gap_id"), NON_RAN_RECORDS)
def test_a_verdict_with_no_outcome_to_compare_is_revalidated_only_from_every_observation(
    monkeypatch: pytest.MonkeyPatch,
    work_root_case: Path,
    status: str,
    reason: str,
    gap_id: str | None,
    importing: bool,
    observed: bool,
    written: bool,
) -> None:
    scenario = scenarios.get(NON_RAN_SCENARIO)
    baseline = baselines.load(NON_RAN_SCENARIO)
    record = results.build_record(
        scenario,
        results.UNIKIX,
        status,
        reason=reason,
        gap_id=gap_id,
        execution_fingerprint=NON_RAN_FINGERPRINT,
        execution_id=EVIDENCE_EXECUTION_ID,
    )
    raw = dict(record, observed=_every_observation(scenario, baseline) if observed else None)
    found = report.Evidence(EVIDENCE_EXECUTION_ID, NON_RAN_SCENARIO, work_root_case / NON_RAN_SCENARIO, raw, {}, {},
                            result_sha256=BOUND_RESULT_SHA256)
    assert not found.ran
    assert report.missing_observations(found, scenario, baseline) == ([] if observed else [NON_RAN_LACKS])
    writes: list[dict] = []
    monkeypatch.setattr(report, "current_fingerprint", lambda current: (NON_RAN_FINGERPRINT, None))
    monkeypatch.setattr(report, "_write_validation", lambda evidence, validation: writes.append(dict(validation)))
    document: dict = {}
    summary = report.ImportSummary(EVIDENCE_EXECUTION_ID)
    outcome = report._validate_evidence(found, scenario, document, summary, report._CopyGaps({}, frozenset()), None,
                                        importing=importing)
    assert document == {} and summary.gaps_added == []
    if not written:
        assert outcome is None and writes == []
        assert summary.skipped == [(NON_RAN_SCENARIO, f"stale: the evidence lacks {NON_RAN_LACKS}")]
        return
    assert outcome == (status, reason) and summary.skipped == []
    assert len(writes) == 1
    carried = {name: writes[0][name] for name in ("status", "reason", "gap_id", "execution_fingerprint", "decoded")}
    assert carried == {
        "status": status,
        "reason": reason,
        "gap_id": gap_id,
        "execution_fingerprint": NON_RAN_FINGERPRINT,
        "decoded": None,
    }
    assert writes[0]["baseline_version"] == baseline.version
    assert writes[0]["scenario_digest"] == scenarios.digest(scenario)


# Schema gate: report._validate_evidence on an eligible, passed UniKix record,
# whose execution fingerprint is current, of a scenario edited to assert only
# its return code (_return_code_only): on import of CFG-OPENFIL-normal-01,
# whose derived baseline an agreeing record would promote, and of
# DAT-READACCT-normal-01, whose gnucobol-batch baseline it is compared with,
# and on revalidation of either. The validation.yaml writer records the
# record instead of writing it, and a promotion or baseline write fails the
# case.
SCHEMA_GATE_CASES = [
    pytest.param("CFG-OPENFIL-normal-01", True, id="import-derived"),
    pytest.param("DAT-READACCT-normal-01", True, id="import-observed"),
    pytest.param("CFG-OPENFIL-normal-01", False, id="revalidation-derived"),
    pytest.param("DAT-READACCT-normal-01", False, id="revalidation-observed"),
]
SCHEMA_GATE_FINGERPRINT = "6" * 64
# A UniKix-copy identity of the record contract's shape; eligibility is
# granted by the case, so no zip is read.
SCHEMA_GATE_IDENTITY = {"tree": "unikix-copy", "commit": "a" * 40, "zip_sha256": "7" * 64, "units": {}}
RETURN_CODE_ONLY = "asserts no business outcome beyond return_code"
# report.build_state over the committed tree with SCHEMA_DEMOTION_SCENARIO
# replaced by its return-code-only copy, given a current passed record of the
# copy on each adapter: the committed gnucobol-batch record carrying the
# copy's digest, or an eligible, current imported UniKix record.
SCHEMA_DEMOTION_SCENARIO = "DAT-READACCT-normal-01"
SCHEMA_DEMOTION_ADAPTERS = [
    pytest.param(results.GNUCOBOL, id="gnucobol-batch"),
    pytest.param(results.UNIKIX, id="unikix"),
]


def _return_code_only(scenario: scenarios.Scenario) -> scenarios.Scenario:
    """``scenario`` with every business assertion removed: its ``expected``
    holds only its ``return_code``, and its inputs and preconditions, so its
    execution fingerprint, are kept."""
    expected = {"return_code": scenario.expected["return_code"]}
    return dataclasses.replace(scenario, data=dict(scenario.data, expected=expected), expected=expected)


def _refuse(what: str) -> Callable[..., None]:
    def refused(*args: object, **kwargs: object) -> None:
        raise AssertionError(f"{what} was called")

    return refused


@pytest.mark.parametrize(("scenario_id", "importing"), SCHEMA_GATE_CASES)
def test_a_scenario_failing_the_schema_check_is_validated_failing_and_never_promoted(
    monkeypatch: pytest.MonkeyPatch,
    work_root_case: Path,
    scenario_id: str,
    importing: bool,
) -> None:
    committed = scenarios.get(scenario_id)
    assert scenarios.validate(committed) == []
    scenario = _return_code_only(committed)
    baseline = baselines.load(scenario_id)
    code = scenario.expected["return_code"]
    record = results.build_record(
        scenario,
        results.UNIKIX,
        results.STATUS_PASSED,
        decoded={"return_code": code},
        execution_fingerprint=SCHEMA_GATE_FINGERPRINT,
        source_identity=SCHEMA_GATE_IDENTITY,
        execution_id=EVIDENCE_EXECUTION_ID,
    )
    raw = dict(record, observed={"return_code": code, "messages": [], "screens": []})
    found = report.Evidence(EVIDENCE_EXECUTION_ID, scenario_id, work_root_case / scenario_id, raw, {}, {},
                            result_sha256=BOUND_RESULT_SHA256)
    assert found.ran and report.missing_observations(found, scenario, baseline) == []
    writes: list[dict] = []
    monkeypatch.setattr(report, "current_fingerprint", lambda current: (SCHEMA_GATE_FINGERPRINT, None))
    monkeypatch.setattr(report, "_write_validation", lambda evidence, validation: writes.append(dict(validation)))
    monkeypatch.setattr(report, "_ineligibility", lambda *args: None)
    monkeypatch.setattr(report, "_promote", _refuse("the promotion"))
    monkeypatch.setattr(baselines, "write", _refuse("baselines.write"))
    document: dict = {"gaps": []}
    summary = report.ImportSummary(EVIDENCE_EXECUTION_ID)
    with pytest.raises(report._SchemaRefusal) as refused:
        report._validate_evidence(found, scenario, document, summary, report._CopyGaps({}, frozenset()), None,
                                  importing=importing)
    assert str(refused.value).startswith(f"{scenario_id}: the scenario fails the schema check: ")
    assert RETURN_CODE_ONLY in str(refused.value) and "records failing (error)" in str(refused.value)
    assert len(writes) == 1
    written = {name: writes[0][name] for name in ("status", "reason", "gap_id", "decoded", "execution_fingerprint")}
    assert written == {
        "status": results.STATUS_FAILING,
        "reason": baselines.REASON_ERROR,
        "gap_id": None,
        "decoded": None,
        "execution_fingerprint": SCHEMA_GATE_FINGERPRINT,
    }
    assert writes[0]["baseline_version"] == baseline.version
    assert writes[0]["scenario_digest"] == scenarios.digest(scenario) != scenarios.digest(committed)
    assert document == {"gaps": []}
    assert (summary.promoted, summary.gaps_added, summary.gaps_changed, summary.skipped) == ([], [], [], [])


@pytest.mark.parametrize("adapter", SCHEMA_DEMOTION_ADAPTERS)
def test_a_passed_record_of_a_scenario_failing_the_schema_check_verifies_nothing(
    monkeypatch: pytest.MonkeyPatch,
    adapter: str,
) -> None:
    scenario = _return_code_only(scenarios.get(SCHEMA_DEMOTION_SCENARIO))
    present = report._present_scenarios

    def with_copy() -> tuple[dict, dict[str, str]]:
        found, unread = present()
        return dict(found, **{SCHEMA_DEMOTION_SCENARIO: scenario}), unread

    monkeypatch.setattr(report, "_present_scenarios", with_copy)
    recorded = results.load(SCHEMA_DEMOTION_SCENARIO)
    assert recorded is not None and recorded["status"] == results.STATUS_PASSED
    passed = dict(recorded, scenario_digest=scenarios.digest(scenario))
    if adapter == results.GNUCOBOL:
        load = results.load
        monkeypatch.setattr(results, "load", lambda scenario_id, **kwargs: (
            dict(passed) if scenario_id == SCHEMA_DEMOTION_SCENARIO else load(scenario_id, **kwargs)))
    else:
        # The committed gnucobol-batch record no longer matches the copy's
        # digest, so it is stale and only the imported record counts.
        passed = dict(passed, adapter=results.UNIKIX, execution_id=EVIDENCE_EXECUTION_ID,
                      evidence_path=results.evidence_path_for(results.UNIKIX, SCHEMA_DEMOTION_SCENARIO,
                                                              EVIDENCE_EXECUTION_ID))
        path = UNIKIX_EVIDENCE_DIR / EVIDENCE_EXECUTION_ID / SCHEMA_DEMOTION_SCENARIO / report.VALIDATION_FILE
        imported = report.UnikixRecord(EVIDENCE_EXECUTION_ID, SCHEMA_DEMOTION_SCENARIO, passed, path)
        monkeypatch.setattr(report, "load_unikix_records", lambda *args, **kwargs: ([imported], [], {}))
        monkeypatch.setattr(report, "_ineligibility", lambda *args: None)
        monkeypatch.setattr(report, "_imported_is_current", lambda *args: True)
    state = report.build_state()
    status = state.statuses[SCHEMA_DEMOTION_SCENARIO]
    assert (status.status, status.reason) == (results.STATUS_FAILING, baselines.REASON_ERROR)
    counted = [(record["adapter"], record["execution_id"], record["status"], record["reason"])
               for record in status.records]
    assert counted == [(adapter, passed["execution_id"], results.STATUS_FAILING, baselines.REASON_ERROR)]
    assert passed["status"] == results.STATUS_PASSED  # demoted in memory only
    assert scenario.inventory_ids and all(
        state.item_status[str(item_id)] == report.FAILING for item_id in scenario.inventory_ids)
    named = [problem for problem in state.problems
             if problem.startswith(f"{SCHEMA_DEMOTION_SCENARIO}: the scenario fails the schema check: ")]
    assert len(named) == 1 and RETURN_CODE_ONLY in named[0], state.problems


# Recurring questions: report._add_gap on in-memory registers, whose entries
# ask the question their unknown starts with (the text up to its first
# colon). Each register holds another open question (GAP-UNC-001), a
# stub-limitation entry (GAP-STB-010) and, as RAISE_ENTRY, the cross-adapter
# divergence of RAISE_SCENARIO first seen in RAISE_FIRST, closed on
# RAISE_CLOSING or open; RAISE_RECURRING raises it again. Every gap id here,
# the ids expected of a new entry included, is one gaps.yaml holds, so
# report.py --check finds each id this file cites; the entries under them
# are invented. The execution ids are invented ids of valid form, and the
# item ids need not exist.
RAISE_SCENARIO = "FIN-INTCALC-normal-01"
RAISE_QUESTION = f"Cross-adapter divergence of {RAISE_SCENARIO}"
RAISE_ENTRY = "GAP-UNC-014"
RAISE_FIRST = "unikix-20251201T000000Z-89abcdef"
RAISE_CLOSING = {
    "execution_id": "unikix-20260101T000000Z-0123abcd",
    "evidence_path": "tests/reports/unikix/unikix-20260101T000000Z-0123abcd",
}
RAISE_RECURRING = "unikix-20260201T000000Z-4567cdef"
RAISE_ITEMS = ["INV-FIN-008", "INV-FIN-009"]
RAISE_EXTRA_ITEM = "INV-FIN-010"
RAISE_NEEDED = "The owner's statement of which outcome is intended."
RAISE_FORMS = [pytest.param("list", id="list-form"), pytest.param("mapping", id="mapping-form")]

# Each case: the items the open RAISE_ENTRY lists, the items raised again,
# whether a closed GAP-UNC-013 asking the same question stands before it,
# what _add_gap returns (None: nothing changes) and the items listed after.
RAISE_OPEN_CASES = [
    pytest.param(RAISE_ITEMS, RAISE_ITEMS[::-1], False, None, RAISE_ITEMS, id="lists-every-item"),
    pytest.param(RAISE_ITEMS, [RAISE_EXTRA_ITEM, RAISE_ITEMS[0]], False, (RAISE_ENTRY, "extended"),
                 [*RAISE_ITEMS, RAISE_EXTRA_ITEM], id="extra-item"),
    pytest.param(RAISE_ITEMS, RAISE_ITEMS, True, None, RAISE_ITEMS, id="beside-a-closed-duplicate"),
]

# Each case: the category and the question raised, and the id of the entry
# added, the category's next free number.
RAISE_NEW_CASES = [
    pytest.param("uncertain-purpose", "Cross-adapter divergence of FIN-INTCALC-normal-02", "GAP-UNC-015",
                 id="another-question"),
    pytest.param("stub-limitation", RAISE_QUESTION, "GAP-STB-011", id="same-question-another-category"),
]


def _raised_unknown(question: str, execution_id: str) -> str:
    """The unknown of a divergence raised by ``execution_id``, as
    ``report._divergence_entry`` words it."""
    return (f"{question}: UniKix execution {execution_id} gives another decoded outcome than the gnucobol-batch "
            "baseline, which stays authoritative, in messages.")


def _raise_register(form: str, status: str, items: list[str], duplicate: bool = False) -> dict:
    """The gaps.yaml document of a case, its ``gaps`` a list of entries or,
    for ``"mapping"``, a mapping of gap id to entry: RAISE_ENTRY in
    ``status`` listing ``items``, after GAP-UNC-001, GAP-STB-010 and, with
    ``duplicate``, a closed GAP-UNC-013 that asks RAISE_ENTRY's question."""
    entries = [
        {"gap_id": "GAP-UNC-001", "category": "uncertain-purpose", "status": "open",
         "unknown": "Whether UNUSED1Y is used: no program copies it.", "needed": "The owner's statement of purpose.",
         "affected_inventory_ids": ["INV-DAT-001"], "resolving_evidence": None},
        {"gap_id": "GAP-STB-010", "category": "stub-limitation", "status": "open",
         "unknown": "The emulated catalog: GDG generations are resolved by the harness.",
         "needed": "An eligible UniKix run of the same scenario that agrees.",
         "affected_inventory_ids": ["INV-DAT-002"], "resolving_evidence": None},
    ]
    if duplicate:
        entries.append({"gap_id": "GAP-UNC-013", "category": "uncertain-purpose", "status": "closed",
                        "unknown": _raised_unknown(RAISE_QUESTION, RAISE_FIRST), "needed": RAISE_NEEDED,
                        "affected_inventory_ids": list(items), "resolving_evidence": dict(RAISE_CLOSING)})
    entries.append({"gap_id": RAISE_ENTRY, "category": "uncertain-purpose", "status": status,
                    "unknown": _raised_unknown(RAISE_QUESTION, RAISE_FIRST), "needed": RAISE_NEEDED,
                    "affected_inventory_ids": list(items),
                    "resolving_evidence": dict(RAISE_CLOSING) if status == "closed" else None})
    if form == "mapping":
        return {"gaps": {entry["gap_id"]: {key: value for key, value in entry.items() if key != "gap_id"}
                         for entry in entries}}
    return {"gaps": entries}


@pytest.mark.parametrize("form", RAISE_FORMS)
def test_add_gap_reopens_the_closed_entry_asking_the_same_question(form: str) -> None:
    document = _raise_register(form, "closed", RAISE_ITEMS[:1])
    unknown = _raised_unknown(RAISE_QUESTION, RAISE_RECURRING)
    needed = f"An eligible UniKix run that agrees with the baseline, after {RAISE_RECURRING}."
    registered = report._add_gap(document, "uncertain-purpose", unknown, iter(RAISE_ITEMS[::-1]), needed)
    entries = report._gap_entries(document)
    assert registered == (RAISE_ENTRY, "reopened")
    assert sorted(entries) == ["GAP-STB-010", "GAP-UNC-001", RAISE_ENTRY]
    entry = entries[RAISE_ENTRY]
    assert entry["status"] == "open" and entry["resolving_evidence"] is None
    assert entry["superseded_evidence"] == [RAISE_CLOSING]
    assert entry["unknown"] == unknown and entry["needed"] == needed
    assert entry["affected_inventory_ids"] == RAISE_ITEMS
    assert report._entry_problems(RAISE_ENTRY, entry) == []


@pytest.mark.parametrize(("listed", "raised", "duplicate", "registered", "after"), RAISE_OPEN_CASES)
@pytest.mark.parametrize("form", RAISE_FORMS)
def test_add_gap_adds_no_entry_beside_the_open_one_asking_the_same_question(
    form: str, listed: list[str], raised: list[str], duplicate: bool, registered: tuple[str, str] | None,
    after: list[str],
) -> None:
    document = _raise_register(form, "open", listed, duplicate)
    expected = copy.deepcopy(document)
    report._gap_entries(expected)[RAISE_ENTRY]["affected_inventory_ids"] = after
    unknown = _raised_unknown(RAISE_QUESTION, RAISE_RECURRING)
    assert report._add_gap(document, "uncertain-purpose", unknown, iter(raised), "Another statement.") == registered
    assert document == expected


@pytest.mark.parametrize(("category", "question", "gap_id"), RAISE_NEW_CASES)
@pytest.mark.parametrize("form", RAISE_FORMS)
def test_add_gap_gives_another_question_the_next_free_number(form: str, category: str, question: str,
                                                             gap_id: str) -> None:
    document = _raise_register(form, "closed", RAISE_ITEMS)
    before = report._gap_entries(copy.deepcopy(document))
    unknown = _raised_unknown(question, RAISE_RECURRING)
    assert report._add_gap(document, category, unknown, iter(RAISE_ITEMS[::-1]), RAISE_NEEDED) == (gap_id, "added")
    entries = report._gap_entries(document)
    added = {"affected_inventory_ids": RAISE_ITEMS, "category": category, "needed": RAISE_NEEDED,
             "resolving_evidence": None, "status": "open", "unknown": unknown}
    assert entries.pop(gap_id) == (added if form == "mapping" else dict(added, gap_id=gap_id))
    assert entries == before


@pytest.mark.parametrize("form", RAISE_FORMS)
def test_add_gap_refuses_an_entry_whose_items_are_not_a_list(form: str) -> None:
    document = _raise_register(form, "closed", RAISE_ITEMS)
    report._gap_entries(document)[RAISE_ENTRY]["affected_inventory_ids"] = RAISE_ITEMS[0]
    before = copy.deepcopy(document)
    with pytest.raises(report.ReportError, match=f"the affected_inventory_ids of {RAISE_ENTRY} is not a list"):
        report._add_gap(document, "uncertain-purpose", _raised_unknown(RAISE_QUESTION, RAISE_RECURRING),
                        RAISE_ITEMS, RAISE_NEEDED)
    assert document == before


# Promotion field names: report.observed_expectation on in-memory copies of
# PROMOTION_SCENARIO, a derived baseline only unikix runs, and of its
# baseline, whose ACCTDATA (file_state) or, added here, DATEPARM (datasets)
# records name the fields of a case. Their derived values are read from the
# repository accounts and PROMOTION_DATEPARM's first record; the evidence,
# in memory and under EVIDENCE_EXECUTION_ID, observes PROMOTION_BALANCE as
# the first account's ACCT-CURR-BAL, or DATEPARM's second record, so the
# derived expectation fails and an import would promote MAJOR. Nothing is
# written.
PROMOTION_SCENARIO = "DAT-ACCTFILE-recovery-01"
PROMOTION_ACCOUNT_LAYOUT = "CVACT01Y/ACCOUNT-RECORD"
PROMOTION_BALANCE = "999.00"
PROMOTION_DATEPARM = (b"2022-01-01 2022-07-06".ljust(80), b"2022-02-01 2022-07-06".ljust(80))
# Each case: the dataset, its section, the names the baseline's records give,
# the names the scenario's records give (None: it defers with 'baseline',
# since its schema refuses @raw in an expected record) and the names the
# promoted scenario's records keep (None: 'baseline'). The promoted baseline
# keeps every name either gives.
PROMOTION_FORMS = [
    pytest.param("ACCTDATA", "file_state", ("ACCT-ID", "ACCT-CURR-BAL"), ("ACCT-ID", "ACCT-CURR-BAL"),
                 ("ACCT-ID", "ACCT-CURR-BAL"), id="decoded-field"),
    pytest.param("ACCTDATA", "file_state", ("ACCT-ID", "ACCOUNT-RECORD.ACCT-CURR-BAL"),
                 ("ACCT-ID", "ACCOUNT-RECORD.ACCT-CURR-BAL"), ("ACCT-ID", "ACCOUNT-RECORD.ACCT-CURR-BAL"),
                 id="field-path"),
    pytest.param("ACCTDATA", "file_state", ("ACCT-ID", "ACCOUNT-RECORD"), ("ACCT-ID", "ACCOUNT-RECORD"),
                 ("ACCT-ID", "ACCOUNT-RECORD"), id="group"),
    pytest.param("DATEPARM", "datasets", ("START-DATE", "END-DATE"), ("START-DATE", "END-DATE"),
                 ("START-DATE", "END-DATE"), id="dataset-own-field"),
    pytest.param("ACCTDATA", "file_state", ("@raw",), None, None, id="raw"),
    pytest.param("ACCTDATA", "file_state", ("ACCT-ID", "ACCT-CURR-BAL", "ACCT-ACTIVE-STATUS"),
                 ("ACCT-ID", "ACCOUNT-RECORD.ACCT-CURR-BAL"),
                 ("ACCT-ID", "ACCT-ACTIVE-STATUS", "ACCOUNT-RECORD.ACCT-CURR-BAL"), id="field-path-beside-its-key"),
    pytest.param("ACCTDATA", "file_state", ("ACCT-ID", "@raw"), ("ACCT-ID",), ("ACCT-ID",), id="raw-beside-a-field"),
]
# The run date the evidence records for the matcher case, DATEPARM's END-DATE
# in both of PROMOTION_DATEPARM's records, and the matcher the baseline gives
# that field, one of DATEPARM's own fields, which its decoded line lacks.
PROMOTION_RUN_DATE = "2022-07-06"
PROMOTION_END_DATE_MATCHER = {"match": "run-date"}
# Names the baseline gives that denote no field of an ACCTDATA record: a
# field path the layout lacks, and a subscript on a field that is no table
# item.
PROMOTION_UNRESOLVED = [
    pytest.param("ACCOUNT-RECORD.ACCT-NO-SUCH-FIELD", id="unknown-field-path"),
    pytest.param("ACCT-CURR-BAL(1)", id="subscript-outside-a-table"),
]


def _promotion_value(dataset: str, record: bytes, name: str) -> str:
    """Field ``name`` of the raw ``record`` of ``dataset``, read here from its
    bytes rather than through the harness's resolver: the whole record for
    ``@raw``; a dataset's own field, or a group of its layout, as the text of
    its bytes; any other layout field, named by its key or its path, as
    ``codec.decode_record`` decodes it."""
    text = codec.ENCODINGS["ascii"]
    if name == baselines.RAW_KEY:
        return record.decode(text)
    own = {field.name: (field.offset, field.length) for field in fixtures.spec(dataset).fields}
    layout = fixtures.spec(dataset).layout
    entry = layouts.field_map(layout)[name] if name not in own else None
    if entry is None or entry["type"] == "group":
        offset, length = own[name] if entry is None else (entry["offset"], entry["length"])
        return record[offset:offset + length].decode(text)
    return codec.to_plain(codec.decode_record(record, layout)[codec.field_key(entry, layout)])


def _promotion_records(dataset: str, data: bytes, names: tuple[str, ...]) -> list[dict]:
    """Every record of ``data``, a ``dataset`` file, as ``{name: value}`` over
    ``names`` (``_promotion_value``)."""
    length = fixtures.spec(dataset).record_length
    return [{name: _promotion_value(dataset, data[start:start + length], name) for name in names}
            for start in range(0, len(data), length)]


def _promotion_case(
    dataset: str, section: str, stored_names: tuple[str, ...], own_names: tuple[str, ...] | None
) -> tuple[scenarios.Scenario, baselines.Baseline, report.Evidence]:
    """PROMOTION_SCENARIO and its baseline, their ``dataset`` records naming
    ``stored_names`` and ``own_names`` with the derived values, and the
    evidence of a run that observes every other outcome they assert as
    derived and ``dataset`` as observed."""
    scenario = scenarios.get(PROMOTION_SCENARIO)
    baseline = baselines.load(PROMOTION_SCENARIO)
    accounts = fixtures.repository_dataset("ACCTDATA-PS")
    length = fixtures.spec("ACCTDATA").record_length
    first = codec.encode_record({"ACCT-CURR-BAL": PROMOTION_BALANCE}, PROMOTION_ACCOUNT_LAYOUT, base=accounts[:length])
    files = {"ACCTDATA": first + accounts[length:]}
    derived = accounts
    if dataset == "DATEPARM":
        files = {"ACCTDATA": accounts, "DATEPARM": PROMOTION_DATEPARM[1]}
        derived = PROMOTION_DATEPARM[0]
    catalog = baseline.expected["file_state"]["@CATALOG"]["contains"]
    files["@CATALOG"] = "".join(f"{line}\n" for line in catalog).encode("ascii")
    stored = {**baseline.expected, section: {**baseline.expected.get(section, {}),
                                             dataset: _promotion_records(dataset, derived, stored_names)}}
    own = scenarios.BASELINE if own_names is None else {"records": _promotion_records(dataset, derived, own_names)}
    expected = {**scenario.expected, section: {**scenario.expected.get(section, {}), dataset: own}}
    scenario = dataclasses.replace(scenario, expected=expected, data={**scenario.data, "expected": expected})
    baseline = baselines.Baseline(PROMOTION_SCENARIO, stored, dict(baseline.manifest), {})
    observed = {"return_code": baseline.expected["return_code"], "messages": list(baseline.expected["messages"]),
                "screens": []}
    found = report.Evidence(EVIDENCE_EXECUTION_ID, PROMOTION_SCENARIO,
                            UNIKIX_EVIDENCE_DIR / EVIDENCE_EXECUTION_ID / PROMOTION_SCENARIO,
                            {"observed": observed}, {}, files)
    return scenario, baseline, found


@pytest.mark.parametrize(("dataset", "section", "stored_names", "own_names", "inline_names"), PROMOTION_FORMS)
def test_observed_expectation_promotes_each_name_the_comparison_reads(
    dataset: str,
    section: str,
    stored_names: tuple[str, ...],
    own_names: tuple[str, ...] | None,
    inline_names: tuple[str, ...] | None,
) -> None:
    scenario, baseline, found = _promotion_case(dataset, section, stored_names, own_names)
    result = report.evidence_result(found)
    assert scenarios.validate(scenario) == []
    assert baselines.compare(scenario, baseline, result, results.UNIKIX)[:2] == (results.STATUS_FAILING, "assertion")
    promoted = report.observed_expectation(found, scenario, baseline)
    every_name = tuple(dict.fromkeys(stored_names + (own_names or ())))
    records = _promotion_records(dataset, found.datasets[dataset], every_name)
    assert promoted[section][dataset] == (records if own_names is None else {"records": records})
    assert promoted["file_state"]["@CATALOG"] == {"records": baseline.expected["file_state"]["@CATALOG"]["contains"]}
    inline = report._inline_expectation(scenario, promoted)
    kept = scenarios.BASELINE if inline_names is None else {
        "records": _promotion_records(dataset, found.datasets[dataset], inline_names)}
    assert inline[section][dataset] == kept
    candidate = dataclasses.replace(scenario, expected=inline, data={**scenario.data, "expected": inline})
    assert scenarios.validate(candidate) == []
    promoted_baseline = baselines.Baseline(PROMOTION_SCENARIO, promoted, dict(baseline.manifest), {})
    assert baselines.compare(candidate, promoted_baseline, result, results.UNIKIX) == (results.STATUS_PASSED, None, "")


def test_observed_expectation_carries_a_matcher_given_under_a_dataset_own_field() -> None:
    scenario, baseline, found = _promotion_case("DATEPARM", "datasets", ("START-DATE", "END-DATE"), ("START-DATE",))
    stored = copy.deepcopy(baseline.expected)
    stored["datasets"]["DATEPARM"][0]["END-DATE"] = dict(PROMOTION_END_DATE_MATCHER)
    baseline = baselines.Baseline(PROMOTION_SCENARIO, stored, dict(baseline.manifest), {})
    run_date = {"bpe_job_start_date": PROMOTION_RUN_DATE, "first_step_screen_date": None}
    found = dataclasses.replace(found, environment={"run_date": run_date})
    result = report.evidence_result(found)
    promoted = report.observed_expectation(found, scenario, baseline)
    start = _promotion_value("DATEPARM", PROMOTION_DATEPARM[1], "START-DATE")
    records = [{"START-DATE": start, "END-DATE": PROMOTION_END_DATE_MATCHER}]
    assert promoted["datasets"]["DATEPARM"] == {"records": records}
    inline = report._inline_expectation(scenario, promoted)
    assert inline["datasets"]["DATEPARM"] == {"records": records}
    candidate = dataclasses.replace(scenario, expected=inline, data={**scenario.data, "expected": inline})
    assert scenarios.validate(candidate) == []
    promoted_baseline = baselines.Baseline(PROMOTION_SCENARIO, promoted, dict(baseline.manifest), {})
    assert baselines.compare(candidate, promoted_baseline, result, results.UNIKIX, PROMOTION_RUN_DATE) == (
        results.STATUS_PASSED, None, "")


@pytest.mark.parametrize("name", PROMOTION_UNRESOLVED)
def test_observed_expectation_refuses_a_name_no_record_denotes(name: str) -> None:
    scenario, baseline, found = _promotion_case("ACCTDATA", "file_state", ("ACCT-ID",), ("ACCT-ID",))
    stored = copy.deepcopy(baseline.expected)
    for record in stored["file_state"]["ACCTDATA"]:
        record[name] = PROMOTION_BALANCE
    baseline = baselines.Baseline(PROMOTION_SCENARIO, stored, dict(baseline.manifest), {})
    refusal = f"{PROMOTION_SCENARIO}: file_state.ACCTDATA record 1 has no field {name} as the comparison reads it"
    with pytest.raises(report.ReportError, match=re.escape(refusal)):
        report.observed_expectation(found, scenario, baseline)


# Divergence on revalidation: report._validate_evidence, revalidating an
# eligible UniKix record of DIVERGENCE_SCENARIO (both adapters run it, and
# its gnucobol-batch baseline stays authoritative) whose execution
# fingerprint is current and whose observed messages now differ from that
# baseline in their last line, against an in-memory register beside
# GAP-UNC-001. Each case: the status of RAISE_ENTRY asking the divergence's
# question there (absent, closed on RAISE_CLOSING, open), the items it lists
# and how _add_gap changes it. The validation.yaml writer records the record
# instead of writing it; a promotion, a baseline write or a file write fails
# the case.
DIVERGENCE_SCENARIO = "DAT-READACCT-normal-01"
DIVERGENCE_QUESTION = f"Cross-adapter divergence of {DIVERGENCE_SCENARIO}"
DIVERGENCE_ITEM = "INV-DAT-014"
DIVERGENCE_OTHER_ITEM = "INV-DAT-015"
DIVERGENCE_CASES = [
    pytest.param(None, [], "added", id="new-question"),
    pytest.param("closed", [DIVERGENCE_ITEM], "reopened", id="closed-question"),
    pytest.param("open", [DIVERGENCE_OTHER_ITEM], "extended", id="open-question-another-item"),
    pytest.param("open", [DIVERGENCE_ITEM], None, id="open-question-every-item"),
]


def _tree_bytes(folder: Path) -> dict[str, bytes]:
    return {path.relative_to(folder).as_posix(): path.read_bytes() for path in sorted(folder.rglob("*"))
            if path.is_file()}


def _divergent_evidence(directory: Path) -> report.Evidence:
    """The evidence, in ``directory``, of a UniKix run of DIVERGENCE_SCENARIO
    that agreed with its gnucobol-batch baseline when it ran and whose
    observed messages differ from that baseline in their last line: the
    record carries SCHEMA_GATE_FINGERPRINT and SCHEMA_GATE_IDENTITY."""
    scenario = scenarios.get(DIVERGENCE_SCENARIO)
    baseline = baselines.load(DIVERGENCE_SCENARIO)
    assert baseline.capture_platform == results.GNUCOBOL
    messages = list(baseline.expected["messages"])
    messages[-1] += " ON THE UNIKIX COPY"
    record = results.build_record(
        scenario,
        results.UNIKIX,
        results.STATUS_PASSED,
        decoded={"return_code": 0, "messages": list(baseline.expected["messages"])},
        execution_fingerprint=SCHEMA_GATE_FINGERPRINT,
        source_identity=SCHEMA_GATE_IDENTITY,
        execution_id=EVIDENCE_EXECUTION_ID,
    )
    raw = dict(record, observed={"return_code": 0, "messages": messages, "screens": []})
    return report.Evidence(EVIDENCE_EXECUTION_ID, DIVERGENCE_SCENARIO, directory, raw, {}, {},
                           result_sha256=BOUND_RESULT_SHA256)


def _judged_eligible(monkeypatch: pytest.MonkeyPatch) -> list[dict]:
    """Let ``report._validate_evidence`` judge a record of the divergent
    evidence as eligible and current, recording each ``validation.yaml``
    in the list returned instead of writing it; a promotion, a baseline
    write or a file write fails the case."""
    writes: list[dict] = []
    monkeypatch.setattr(report, "current_fingerprint", lambda current: (SCHEMA_GATE_FINGERPRINT, None))
    monkeypatch.setattr(report, "_write_validation", lambda evidence, validation: writes.append(dict(validation)))
    monkeypatch.setattr(report, "_ineligibility", lambda *args: None)
    monkeypatch.setattr(report, "_promote", _refuse("the promotion"))
    monkeypatch.setattr(baselines, "write", _refuse("baselines.write"))
    return writes


@pytest.mark.parametrize(("asking", "listed", "how"), DIVERGENCE_CASES)
def test_a_divergence_found_on_revalidation_registers_its_entry_and_changes_no_baseline(
    monkeypatch: pytest.MonkeyPatch,
    work_root_case: Path,
    asking: str | None,
    listed: list[str],
    how: str | None,
) -> None:
    scenario = scenarios.get(DIVERGENCE_SCENARIO)
    found = _divergent_evidence(work_root_case / DIVERGENCE_SCENARIO)
    entries = [{"gap_id": "GAP-UNC-001", "category": "uncertain-purpose", "status": "open",
                "unknown": "Whether UNUSED1Y is used: no program copies it.",
                "needed": "The owner's statement of purpose.", "affected_inventory_ids": ["INV-DAT-001"],
                "resolving_evidence": None}]
    if asking is not None:
        entries.append({"gap_id": RAISE_ENTRY, "category": "uncertain-purpose", "status": asking,
                        "unknown": f"{DIVERGENCE_QUESTION}: UniKix execution {RAISE_FIRST} differed.",
                        "needed": RAISE_NEEDED, "affected_inventory_ids": list(listed),
                        "resolving_evidence": dict(RAISE_CLOSING) if asking == "closed" else None})
    document = {"gaps": entries}
    before = copy.deepcopy(document)
    baseline_files = _tree_bytes(BASELINES_DIR / DIVERGENCE_SCENARIO)
    scenario_file = scenario.path.read_bytes()
    writes = _judged_eligible(monkeypatch)
    monkeypatch.setattr(report, "write_text", _refuse("write_text"))
    summary = report.ImportSummary(EVIDENCE_EXECUTION_ID)
    outcome = report._validate_evidence(found, scenario, document, summary, report._CopyGaps({}, frozenset()), None,
                                        importing=False)
    assert outcome == (results.STATUS_FAILING, baselines.REASON_DIVERGENCE)
    assert len(writes) == 1 and (writes[0]["status"], writes[0]["reason"]) == outcome
    assert writes[0]["decoded"] is not None and writes[0]["scenario_digest"] == scenarios.digest(scenario)
    assert _tree_bytes(BASELINES_DIR / DIVERGENCE_SCENARIO) == baseline_files
    assert scenario.path.read_bytes() == scenario_file and summary.promoted == []
    if how is None:
        assert document == before and (summary.gaps_added, summary.gaps_changed) == ([], [])
        return
    if how == "added":
        assert len(summary.gaps_added) == 1 and summary.gaps_changed == []
        gap_id = summary.gaps_added[0]
        assert gap_id not in report._gap_entries(before)
    else:
        gap_id = RAISE_ENTRY
        change = "reopened" if how == "reopened" else "lists more items"
        assert summary.gaps_added == []
        assert summary.gaps_changed == [f"{gap_id}: {change}, cross-adapter divergence of {DIVERGENCE_SCENARIO} "
                                        f"recurs in {EVIDENCE_EXECUTION_ID}"]
    entry = report._gap_entries(document)[gap_id]
    assert (entry["category"], entry["status"]) == ("uncertain-purpose", "open")
    assert entry["unknown"].split(":", 1)[0] == DIVERGENCE_QUESTION
    assert entry["affected_inventory_ids"] == sorted({*listed, DIVERGENCE_ITEM})
    assert report._entry_problems(gap_id, entry) == []


# Revalidation writes: report.revalidate over an evidence tree of the case's
# own, holding either no execution or one with the validation.yaml of
# DIVERGENCE_SCENARIO (_divergent_evidence), with report.py's register and
# inventory pointed at the case's copies (closure_root), the state build and
# the inferred settling replaced, and regenerate recorded instead of run.
# Each case: how the record is judged (none to judge; passed and rewritten;
# passed and rewritten unchanged; left stale; rejected before anything is
# written; refused by the schema check once its validation.yaml is written;
# or judged for real, divergent, under _judged_eligible), whether the
# settling changes the register, the start of each line the revalidation
# reports and whether the reports are regenerated. The validation.yaml
# writer writes the record's YAML into the case's tree.
REVALIDATION_LABEL = f"{EVIDENCE_EXECUTION_ID}/{DIVERGENCE_SCENARIO}"
REVALIDATION_RECORDED = "{}\n"  # the validation.yaml the tree starts with
REVALIDATION_CASES = [
    pytest.param(None, False, [], False, id="nothing-to-revalidate"),
    pytest.param(None, True, [], True, id="register-changed"),
    pytest.param("passed", False, [f"revalidated {REVALIDATION_LABEL}: passed"], True, id="record-revalidated"),
    pytest.param("unchanged", False, [f"revalidated {REVALIDATION_LABEL}: passed"], False,
                 id="record-rewritten-unchanged"),
    pytest.param("stale", False, [f"left {REVALIDATION_LABEL}: stale: "], False, id="record-left-stale"),
    pytest.param("unjudged", False, [f"rejected {REVALIDATION_LABEL}: {DIVERGENCE_SCENARIO}: the baseline cannot"],
                 False, id="record-rejected-unwritten"),
    pytest.param("schema", False,
                 [f"rejected {REVALIDATION_LABEL}: {DIVERGENCE_SCENARIO}: the scenario fails the schema check"],
                 True, id="record-refused-by-the-schema-check"),
    pytest.param("divergent", False,
                 [f"revalidated {REVALIDATION_LABEL}: failing ({baselines.REASON_DIVERGENCE})", "gap added GAP-UNC-"],
                 True, id="divergence-registered"),
]


@pytest.mark.parametrize(("judgement", "settled", "shown", "regenerated"), REVALIDATION_CASES)
def test_revalidation_regenerates_the_reports_only_when_it_changed_a_record_or_the_register(
    monkeypatch: pytest.MonkeyPatch,
    closure_root: Path,
    work_root_case: Path,
    judgement: str | None,
    settled: bool,
    shown: list[str],
    regenerated: bool,
) -> None:
    scenario_dir = work_root_case / EVIDENCE_EXECUTION_ID / DIVERGENCE_SCENARIO
    scenario_dir.mkdir(parents=True)
    validation = scenario_dir / report.VALIDATION_FILE
    validation.write_text(REVALIDATION_RECORDED, encoding="utf-8")
    found = _divergent_evidence(scenario_dir)
    original = report.GAPS_FILE.read_text(encoding="utf-8")
    # A state the reports can be regenerated from (_refuse_unreportable): no
    # inventory drift and no unread scenario file, gnucobol-batch record or
    # validation.yaml.
    state = SimpleNamespace(zip_info=None, copy_gaps=report._CopyGaps({}, frozenset()), items=[], total=0,
                            unread_scenarios={}, unread_records={}, unread_validations={})
    monkeypatch.setattr(report, "build_state", lambda: state)
    monkeypatch.setattr(report, "_evidence_folder", lambda *parts: work_root_case)
    monkeypatch.setattr(report, "_evidence_executions",
                        lambda folder, links: [scenario_dir.parent] if judgement is not None else [])
    monkeypatch.setattr(report, "_scenario_dirs", lambda folder, links: [scenario_dir])
    monkeypatch.setattr(report, "read_evidence", lambda execution_id, folder, zip_info: (found, None))

    def settle(document: dict, current: object, execution_id: str | None, changes: list, closures: list) -> None:
        if settled:
            entry = report._gap_entries(document)["GAP-UNC-001"]
            entry["needed"] = f"{entry['needed']} Asked again."

    monkeypatch.setattr(report, "_settle_inferred", settle)
    regenerations: list[bool] = []
    monkeypatch.setattr(report, "regenerate", lambda: regenerations.append(True))
    judge = report._validate_evidence
    writes = _judged_eligible(monkeypatch)

    def write(evidence: report.Evidence, record: Mapping) -> None:
        writes.append(dict(record))
        (evidence.directory / report.VALIDATION_FILE).write_text(dump_yaml(dict(record)), encoding="utf-8")

    monkeypatch.setattr(report, "_write_validation", write)
    received: list[tuple[bool, bool]] = []

    def judged(evidence: report.Evidence, scenario: scenarios.Scenario, document: dict, summary: report.ImportSummary,
               copy_gaps: object, zip_info: object, *, importing: bool) -> tuple[str, str | None] | None:
        received.append((document == load_yaml(report.GAPS_FILE), importing))
        if judgement in ("passed", "unchanged"):
            if judgement == "passed":
                write(evidence, {"status": results.STATUS_PASSED, "reason": None})
            else:
                validation.write_text(REVALIDATION_RECORDED, encoding="utf-8")
            return results.STATUS_PASSED, None
        if judgement == "stale":
            summary.skipped.append((scenario.id, "stale: the scenario's inputs or preconditions changed"))
            return None
        if judgement == "unjudged":
            raise report.ReportError(f"{scenario.id}: the baseline cannot be read: it is gone")
        if judgement == "schema":
            write(evidence, {"status": results.STATUS_FAILING, "reason": baselines.REASON_ERROR})
            raise report._SchemaRefusal(f"{scenario.id}: the scenario fails the schema check: expected: "
                                        f"{RETURN_CODE_ONLY}")
        return judge(evidence, scenario, document, summary, copy_gaps, zip_info, importing=importing)

    monkeypatch.setattr(report, "_validate_evidence", judged)
    summary = report.revalidate()
    lines = summary.lines()
    assert len(lines) == len(shown) and all(line.startswith(start) for line, start in zip(lines, shown)), lines
    assert received == ([] if judgement is None else [(True, False)])
    assert bool(regenerations) == regenerated
    written = report.GAPS_FILE.read_text(encoding="utf-8")
    assert (written != original) == (settled or judgement == "divergent")
    assert (validation.read_text(encoding="utf-8") != REVALIDATION_RECORDED) == (
        judgement in ("passed", "schema", "divergent"))
    if judgement == "divergent":
        assert [(record["status"], record["reason"]) for record in writes] == [
            (results.STATUS_FAILING, baselines.REASON_DIVERGENCE)]
        gap_id = lines[-1].removeprefix("gap added ")
        assert summary.gaps_added == [gap_id] and gap_id not in report._gap_entries(yaml.safe_load(original))
        entry = report._gap_entries(load_yaml(report.GAPS_FILE))[gap_id]
        assert (entry["status"], entry["affected_inventory_ids"]) == ("open", [DIVERGENCE_ITEM])
        assert entry["unknown"].split(":", 1)[0] == DIVERGENCE_QUESTION


# Closure records: the inventory update of a register entry the import or the
# revalidation closes, and the write that records it before gaps.yaml. Every
# case works on copies of the committed inventory.yaml, gaps.yaml and
# commands.yaml in a repository root of its own inside the work root, which
# report.py's gaps.yaml, inventory, commands.yaml and repository root point
# at for the case. The gaps.yaml copy starts every inferred-not-observed
# entry and GAP-MIS-009 open, without evidence and with its full membership,
# whatever the committed register's lifecycle holds, so a valid import that
# closed or narrowed one changes no case's outcome; the commands.yaml copy
# has every entry citing GAP-MIS-009 confirmed.
# CLOSURE_EXECUTION is an invented execution id of valid form.
CLOSURE_ROOT_PREFIX = "unit-closure-"
CLOSURE_EXECUTION = "unikix-20250103T000000Z-0123abcd"
CLOSURE_FOLDER = f"tests/reports/unikix/{CLOSURE_EXECUTION}"
CLOSURE_EVIDENCE = {"execution_id": CLOSURE_EXECUTION, "evidence_path": CLOSURE_FOLDER}
CLOSURE_INFERRED = "GAP-INF-001"
CLOSURE_INSTALLATION = "GAP-MIS-009"
CLOSURE_VERIFIED = "every affected item verified"
CLOSURE_VERSIONS_RECORDED = "the installation's versions are recorded"
CLOSURE_LEFT_OPEN = f"{CLOSURE_INFERRED}: left open, the inventory could not record its closure: "

# An environment.yaml that records a version for every UniKix prerequisite.
CLOSURE_ENVIRONMENT = {"versions": {key: "12.0.0" for key in report.UNIKIX_VERSION_KEYS}}

# Each case: whether every GAP-INF-001 item is verified, whether the
# installation's versions are recorded, and the entries closed, each with why.
CLOSURE_SETTLINGS = [
    pytest.param(True, False, [(CLOSURE_INFERRED, CLOSURE_VERIFIED)], id="inferred-entry-all-verified"),
    pytest.param(False, True, [(CLOSURE_INSTALLATION, CLOSURE_VERSIONS_RECORDED)], id="installation-versions"),
    pytest.param(False, False, [], id="nothing-settled"),
]

# The resolving_evidence of an earlier closing of each entry: an invented
# commit id and an invented earlier execution id, both of valid form.
CLOSURE_PRIOR_EXECUTION = "unikix-20241215T000000Z-89abcdef"
CLOSURE_PRIOR_EVIDENCE = {
    CLOSURE_INFERRED: {"commit": "89abcdef" * 5},
    CLOSURE_INSTALLATION: {"execution_id": CLOSURE_PRIOR_EXECUTION,
                           "evidence_path": f"tests/reports/unikix/{CLOSURE_PRIOR_EXECUTION}"},
}

# Each case: the entries an earlier closing already closed when the settling
# would close both.
CLOSURE_ALREADY_CLOSED = [
    pytest.param([CLOSURE_INFERRED], id="inferred-closed"),
    pytest.param([CLOSURE_INSTALLATION], id="installation-closed"),
    pytest.param([CLOSURE_INFERRED, CLOSURE_INSTALLATION], id="both-closed"),
]

# Each case: how the commands.yaml copy is left once the installation's
# versions are recorded, and the problem the GAP-MIS-009 line names when the
# file cannot show which entries citing it are confirmed. "all" leaves every
# such entry unconfirmed, as the committed file ships them, and "one" only
# the last of them; the other cases make the file unusable.
CLOSURE_UNCONFIRMED = [
    pytest.param("all", None, id="every-citing-entry-unconfirmed"),
    pytest.param("one", None, id="one-citing-entry-unconfirmed"),
    pytest.param("absent", os.strerror(errno.ENOENT), id="commands-file-absent"),
    pytest.param("directory", os.strerror(errno.EISDIR), id="commands-file-unreadable"),
    pytest.param("not-yaml", "not valid YAML", id="commands-file-not-yaml"),
    pytest.param("no-entries", "holds no 'entries' mapping", id="no-entries-mapping"),
    pytest.param("entry-text", "is not a mapping", id="entry-not-a-mapping"),
]

# A gap id of valid form that no register entry has, put together at run time
# so that report.referenced_gap_ids, which reads every gap id written in
# tests/, does not take it for a citation the register must hold.
CLOSURE_NO_SUCH_GAP = "-".join(("GAP", "INF", "999"))

# Closure pairs append_closures refuses, and what its refusal says.
CLOSURE_REFUSALS = [
    pytest.param((CLOSURE_NO_SUCH_GAP, "A reason."),
                 f"{CLOSURE_NO_SUCH_GAP} are not entries of tests/inventory/gaps.yaml", id="no-such-entry"),
    pytest.param(("INV-UTL-003", "A reason."), "'INV-UTL-003' is not GAP-<CAT>-<nnn>", id="not-a-gap-id"),
    pytest.param((CLOSURE_INFERRED, "  "), f"the closure record of {CLOSURE_INFERRED} needs a reason",
                 id="blank-reason"),
]

# The gap_id of a header change, and whether the header check refuses it.
CLOSURE_GAP_IDS = [
    pytest.param(CLOSURE_INFERRED, False, id="gap-id"),
    pytest.param("GAP-XYZ-001", True, id="unknown-category"),
    pytest.param(f"{CLOSURE_INFERRED}\n", True, id="trailing-newline"),
    pytest.param(1, True, id="not-text"),
]

# How the inventory copy is broken so that it cannot record a closing, and
# what the reason the operator is given says.
CLOSURE_INVENTORY_FAILURES = [
    pytest.param("no-header", "has no header; freeze the inventory before recording a closure", id="no-header"),
    pytest.param("directory", "not an inventory file", id="a-directory"),
]


def _closure_reason(gap_id: str, why: str) -> str:
    """The reason of the closure record of ``gap_id`` closed for ``why`` on
    CLOSURE_EXECUTION, as ``report._close`` words it."""
    return f"{gap_id} closed: {why}; resolving evidence: UniKix execution {CLOSURE_EXECUTION} ({CLOSURE_FOLDER})"


@pytest.fixture
def closure_root(monkeypatch: pytest.MonkeyPatch, carddemo_work_root: Path) -> Iterator[Path]:
    """A repository root of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp or in the checkout,
    given by its real path, holding copies of the committed
    ``inventory.yaml`` and ``gaps.yaml`` under ``tests/inventory/`` and of
    ``commands.yaml`` under ``tests/adapters/unikix/``, which
    ``report.GAPS_FILE``, ``inventory_tools.INVENTORY_FILE``,
    ``report.UNIKIX_COMMANDS_FILE`` and ``report.REPO_ROOT`` name for the
    case; removed afterwards.

    The ``gaps.yaml`` copy holds a controlled lifecycle state rather than the
    committed one: every inferred-not-observed entry and GAP-MIS-009 is
    ``open`` with ``resolving_evidence: null`` and no
    ``superseded_evidence``, and a narrowed entry has the full membership its
    ``narrowed_from`` kept (``affected_inventory_ids``, and ``affected`` only
    when it held clauses) with ``narrowed_from`` dropped. The copy is
    rewritten, its comments kept, only when the committed register differs
    from that state. The ``commands.yaml`` copy has every entry citing
    GAP-MIS-009 ``confirmed``, whatever the committed file holds, so that
    GAP-MIS-009 closes once the installation's versions are recorded."""
    directory = Path(tempfile.mkdtemp(prefix=CLOSURE_ROOT_PREFIX, dir=carddemo_work_root)).resolve()
    try:
        copies = directory / "tests" / "inventory"
        copies.mkdir(parents=True)
        for name in ("inventory.yaml", "gaps.yaml"):
            shutil.copyfile(INVENTORY_DIR / name, copies / name)
        commands = directory / "tests" / "adapters" / "unikix" / COMMANDS_FILE.name
        commands.parent.mkdir(parents=True)
        registry = load_yaml(COMMANDS_FILE)
        for entry in registry["entries"].values():
            if isinstance(entry, dict) and entry.get("gap") == report.GAP_NO_INSTALLATION:
                entry["status"] = "confirmed"
        commands.write_text(dump_yaml(registry), encoding="utf-8")
        monkeypatch.setattr(report, "GAPS_FILE", copies / "gaps.yaml")
        monkeypatch.setattr(inventory_tools, "INVENTORY_FILE", copies / "inventory.yaml")
        monkeypatch.setattr(report, "UNIKIX_COMMANDS_FILE", commands)
        monkeypatch.setattr(report, "REPO_ROOT", directory)
        original = report.GAPS_FILE.read_text(encoding="utf-8")
        committed = load_yaml(report.GAPS_FILE)
        controlled = copy.deepcopy(committed)
        for gap_id, entry in report._gap_entries(controlled).items():
            if entry.get("category") != report.CATEGORIES["INF"] and gap_id != report.GAP_NO_INSTALLATION:
                continue
            entry["status"], entry["resolving_evidence"] = "open", None
            entry.pop("superseded_evidence", None)
            if "narrowed_from" in entry:
                narrowed = entry.pop("narrowed_from")
                assert isinstance(narrowed, dict), f"{gap_id}: narrowed_from is not a mapping: {narrowed!r}"
                entry["affected_inventory_ids"] = list(narrowed.get("affected_inventory_ids") or [])
                if narrowed.get("affected"):
                    entry["affected"] = list(narrowed["affected"])
                else:
                    entry.pop("affected", None)
        if controlled != committed:
            report.GAPS_FILE.write_text(report._gaps_text(controlled, original), encoding="utf-8")
        yield directory
    finally:
        shutil.rmtree(directory)


def _closed_inferred_entry() -> tuple[dict, str, list, list]:
    """The register copy as ``report.GAPS_FILE`` holds it, read as a document
    and as text, with GAP-INF-001 closed on CLOSURE_EXECUTION through
    ``report._close``, the changes noted and the closings queued. The entry
    starts open without evidence, as ``closure_root`` sets it."""
    original = report.GAPS_FILE.read_text(encoding="utf-8")
    document = load_yaml(report.GAPS_FILE)
    changes: list[str] = []
    closures: list = []
    entry = report._gap_entries(document)[CLOSURE_INFERRED]
    assert (entry["status"], entry["resolving_evidence"]) == ("open", None)
    report._close(CLOSURE_INFERRED, entry, dict(CLOSURE_EVIDENCE), CLOSURE_VERIFIED, changes, closures)
    return document, original, changes, closures


def test_append_closures_records_a_closure_without_an_addition(closure_root: Path,
                                                              carddemo_repo_root: Path) -> None:
    path = inventory_tools.INVENTORY_FILE
    before = inventory_tools.load(path)
    first = datetime.date.today().isoformat()
    records = inventory_tools.append_closures([(CLOSURE_INFERRED, " A reason. ")], path, repo_root=closure_root)
    days = {first, datetime.date.today().isoformat()}
    after = inventory_tools.load(path)
    assert len(records) == 1 and records[0]["date"] in days
    assert records == [{"date": records[0]["date"], "gap_id": CLOSURE_INFERRED, "reason": "A reason."}]
    assert after["items"] == before["items"]
    assert after["header"]["changes"] == [*before["header"]["changes"], *records]
    assert {key: value for key, value in after["header"].items() if key != "changes"} == {
        key: value for key, value in before["header"].items() if key != "changes"
    }
    assert after["header"]["total"] == len(after["items"])
    assert inventory_tools._added_ids(after["header"]) == inventory_tools._added_ids(before["header"])
    assert inventory_tools.append_closures([], path, repo_root=closure_root) == []
    # The drift check reads no addition from the record: the copy still
    # matches the repository.
    assert inventory_tools.check(path, carddemo_repo_root) == []


@pytest.mark.parametrize(("case", "remaining", "refusal"), [
    pytest.param("last", [0, 1, 2], None, id="last-equal-occurrence-only"),
    pytest.param("both", [1, 2], None, id="two-requested-occurrences"),
    pytest.param("absent", [], "no matching header.changes record remains", id="absent-refused"),
    pytest.param("excess", [], "no matching header.changes record remains", id="too-many-refused"),
    pytest.param("malformed", [], "malformed closure record", id="addition-is-not-a-closure"),
])
def test_withdraw_closures_removes_only_the_requested_records_or_writes_nothing(
    closure_root: Path, case: str, remaining: list[int], refusal: str | None,
) -> None:
    path = inventory_tools.INVENTORY_FILE
    before = inventory_tools.load(path)
    records = inventory_tools.append_closures([
        (CLOSURE_INFERRED, "A reason."), (CLOSURE_INSTALLATION, "Another closing."),
        (CLOSURE_INFERRED, "A different reason."), (CLOSURE_INFERRED, "A reason."),
    ], path, repo_root=closure_root)
    requested = [records[-1]]
    if case == "both":
        requested *= 2
    elif case == "absent":
        requested = [records[-1], {**records[-1], "date": "1900-01-01"}]
    elif case == "excess":
        requested *= 3
    elif case == "malformed":
        requested = [{**records[-1], "inventory_id": "INV-SEC-001"}]
    written = path.read_bytes()
    if refusal is not None:
        with pytest.raises(inventory_tools.InventoryError, match=re.escape(refusal)):
            inventory_tools.withdraw_closures(requested, path, repo_root=closure_root)
        assert path.read_bytes() == written
    else:
        assert inventory_tools.withdraw_closures(requested, path, repo_root=closure_root) == requested
        after = inventory_tools.load(path)
        assert after["header"]["changes"] == [*before["header"]["changes"], *(records[i] for i in remaining)]
        assert after["items"] == before["items"]
        assert {key: value for key, value in after["header"].items() if key != "changes"} == {
            key: value for key, value in before["header"].items() if key != "changes"
        }
    assert inventory_tools.withdraw_closures([], closure_root / "absent.yaml", repo_root=closure_root) == []
    assert not (closure_root / "absent.yaml").exists()


@pytest.mark.parametrize(("closure", "refusal"), CLOSURE_REFUSALS)
def test_append_closures_refuses_a_closure_it_cannot_record(closure_root: Path, closure: tuple[str, str],
                                                            refusal: str) -> None:
    path = inventory_tools.INVENTORY_FILE
    before = path.read_bytes()
    with pytest.raises(inventory_tools.InventoryError, match=re.escape(refusal)):
        inventory_tools.append_closures([(CLOSURE_INFERRED, "A reason."), closure], path, repo_root=closure_root)
    assert path.read_bytes() == before


@pytest.mark.parametrize(("gap_id", "refused"), CLOSURE_GAP_IDS)
def test_header_check_holds_a_change_gap_id_to_a_gap_id(gap_id: object, refused: bool) -> None:
    change = {"date": "2025-01-03", "gap_id": gap_id, "reason": "A reason."}
    problems = inventory_tools._header_problems({"changes": [change]})
    assert problems == ([f"header: change {inventory_tools._shown(change)} has a gap_id that is not "
                         "GAP-<CAT>-<nnn>"] if refused else [])


@pytest.mark.parametrize(("verified", "versions", "closed"), CLOSURE_SETTLINGS)
def test_settling_closes_each_entry_through_one_queued_closure_record(
    closure_root: Path, verified: bool, versions: bool, closed: list[tuple[str, str]]
) -> None:
    items = inventory_tools.load(inventory_tools.INVENTORY_FILE)["items"]
    document = load_yaml(report.GAPS_FILE)
    inferred = report._gap_entries(document)[CLOSURE_INFERRED]
    members = report._resolve(CLOSURE_INFERRED, inferred, report._Universe.build(items, []))
    state = SimpleNamespace(
        items=items, scenarios={}, statuses={}, item_scenarios={},
        item_status={item_id: report.VERIFIED for item_id in members} if verified else {},
    )
    expected = copy.deepcopy(document)
    for gap_id, _ in closed:
        report._gap_entries(expected)[gap_id].update(status="closed", resolving_evidence=CLOSURE_EVIDENCE)
    summary = report.ImportSummary(CLOSURE_EXECUTION)
    closures: list = []
    report._settle_gaps(document, state, CLOSURE_EXECUTION, [CLOSURE_ENVIRONMENT] if versions else [], summary,
                        closures)
    assert document == expected
    assert summary.gaps_changed == [f"{gap_id}: closed, {why}" for gap_id, why in closed]
    assert [(closure.gap_id, closure.reason) for closure in closures] == [
        (gap_id, _closure_reason(gap_id, why)) for gap_id, why in closed
    ]
    assert all(closure.entry is report._gap_entries(document)[closure.gap_id] for closure in closures)


@pytest.mark.parametrize("preclosed", CLOSURE_ALREADY_CLOSED)
def test_settling_leaves_an_already_closed_entry_as_it_is(closure_root: Path, preclosed: list[str]) -> None:
    items = inventory_tools.load(inventory_tools.INVENTORY_FILE)["items"]
    document = load_yaml(report.GAPS_FILE)
    entries = report._gap_entries(document)
    for gap_id in preclosed:
        entries[gap_id].update(status="closed", resolving_evidence=copy.deepcopy(CLOSURE_PRIOR_EVIDENCE[gap_id]))
    members = report._resolve(CLOSURE_INFERRED, entries[CLOSURE_INFERRED], report._Universe.build(items, []))
    assert members
    state = SimpleNamespace(
        items=items, scenarios={}, statuses={}, item_scenarios={},
        item_status={item_id: report.VERIFIED for item_id in members},
    )
    # Every GAP-INF-001 item is verified and the installation's versions are
    # recorded, so each entry still open closes, inferred before installation.
    closing = [(gap_id, why) for gap_id, why in ((CLOSURE_INFERRED, CLOSURE_VERIFIED),
                                                (CLOSURE_INSTALLATION, CLOSURE_VERSIONS_RECORDED))
               if gap_id not in preclosed]
    expected = copy.deepcopy(document)
    for gap_id, _ in closing:
        report._gap_entries(expected)[gap_id].update(status="closed", resolving_evidence=CLOSURE_EVIDENCE)
    summary = report.ImportSummary(CLOSURE_EXECUTION)
    closures: list = []
    report._settle_gaps(document, state, CLOSURE_EXECUTION, [CLOSURE_ENVIRONMENT], summary, closures)
    assert document == expected
    for gap_id in preclosed:
        entry = entries[gap_id]
        assert (entry["status"], entry["resolving_evidence"]) == ("closed", CLOSURE_PRIOR_EVIDENCE[gap_id])
        assert "superseded_evidence" not in entry
    assert summary.gaps_changed == [f"{gap_id}: closed, {why}" for gap_id, why in closing]
    assert [(closure.gap_id, closure.reason) for closure in closures] == [
        (gap_id, _closure_reason(gap_id, why)) for gap_id, why in closing
    ]
    assert all(closure.entry is entries[closure.gap_id] for closure in closures)


# UniKixEnvironment.versions() run for real on a commands.yaml of the case's
# own: version_bpe runs the case's command once $INSTEBM, a directory of the
# case's own holding the case's files, exists, and every other version_*
# entry prints "<id> 12.0.0". Each case: version_bpe's command, the files
# INSTEBM holds, what environment.yaml then records for version_bpe, and
# whether the installation's versions are recorded (GAP-MIS-009). The empty
# listing is the committed version_bpe on an installation whose INSTEBM is
# empty, a probe that exits 0 and prints nothing. The environment's
# JAVA_TOOL_OPTIONS holds the operator's UNIKIX_SECRET_PROPERTY, which a
# version command run on a JVM echoes; {redacted} and {temp} in what is
# recorded stand for env.REDACTED and the deployment's tmp/.
VERSION_PROBE_LISTING = 'ls -1 "$INSTEBM"'
VERSION_PROBE_JVM_ECHO = "echo 'ebm 12.0.0'; printf 'Picked up JAVA_TOOL_OPTIONS: %s\\n' \"$JAVA_TOOL_OPTIONS\" >&2"
VERSION_PROBE_CASES = [
    pytest.param(VERSION_PROBE_LISTING, [], "no output: version_bpe exited 0 and printed nothing", False,
                 id="empty-listing"),
    pytest.param("printf ' \\n\\t\\n'", [], "no output: version_bpe exited 0 and printed only whitespace", False,
                 id="blank-output"),
    pytest.param(VERSION_PROBE_LISTING, ["ebm-12.0.0"], "ebm-12.0.0\n", True, id="listing"),
    pytest.param("echo 'no such release' >&2; exit 3", [], "exit status 3\nno such release\n", False,
                 id="failure"),
    pytest.param(VERSION_PROBE_JVM_ECHO, [],
                 "ebm 12.0.0\nPicked up JAVA_TOOL_OPTIONS: {redacted} -Djava.io.tmpdir={temp} "
                 f"{UNIKIX_JVM_OWNED}\n", True, id="jvm-echo"),
]


@pytest.mark.parametrize(("command", "listed", "recorded", "named"), VERSION_PROBE_CASES)
def test_version_probes_are_recorded_as_text_the_import_accepts(
    work_root_case: Path, command: str, listed: list[str], recorded: str, named: bool
) -> None:
    instebm = work_root_case / "BPE" / "INSTEBM"
    instebm.mkdir(parents=True)
    for name in listed:
        (instebm / name).write_text("", encoding="utf-8")
    deployment_root = work_root_case / "unikix" / CLOSURE_EXECUTION
    # Every probe starts in the deployment, which refuses a tmp/ such as this.
    _skip_where_no_deployment_fits(deployment_root / "tmp")
    deployment_root.mkdir(parents=True)
    env_file = work_root_case / "carddemo.env"
    env_file.write_text(f"INSTEBM={instebm}\n", encoding="utf-8")
    commands_file = work_root_case / "commands.yaml"
    commands_file.write_text(dump_yaml({"schema_version": 1, "entries": {
        entry_id: {
            "action": f"record {entry_id}",
            "command": command if entry_id == "version_bpe" else f"echo '{entry_id} 12.0.0'",
            "probe": 'test -d "$INSTEBM"' if entry_id == "version_bpe" else "true",
            "timeout_s": 30, "gap": CLOSURE_INSTALLATION, "status": "unconfirmed",
            "source": "the version probe cases", "notes": "the version probe cases",
        }
        for entry_id in unikix_env.VERSION_ENTRIES
    }}), encoding="utf-8")
    environment = unikix_env.UniKixEnvironment(
        env_file=env_file, env_file_sha256=sha256_file(env_file), work_root=work_root_case,
        deployment_root=deployment_root,
        variables={
            "PATH": os.environ.get("PATH", os.defpath), "INSTEBM": str(instebm),
            "JAVA_TOOL_OPTIONS": UNIKIX_SECRET_PROPERTY,
        },
        inherited_cobcpy="", sourcing_output="",
    )
    versions = environment.versions(unikix_env.CommandSet.load(commands_file))
    assert versions == {
        **{entry_id: f"{entry_id} 12.0.0\n" for entry_id in unikix_env.VERSION_ENTRIES},
        "version_bpe": recorded.format(redacted=unikix_env.REDACTED, temp=deployment_root / "tmp"),
    }
    assert not any(UNIKIX_SECRET_PROPERTY in text for text in versions.values())
    # The record an import reads: the run date and the installation and
    # deployment facts, valid, beside the versions just recorded.
    document = {
        "run_date": {key: None for key in report._RUN_DATE_KEYS},
        "installation": {name: str(work_root_case / name.lower()) for name in report._INSTALLATION_FACTS},
        "deployment": {name: str(deployment_root / name.lower()) for name in report._DEPLOYMENT_FACTS},
        "versions": versions,
    }
    assert report._environment_problems(document) == []
    assert report._versions_recorded(document) is named


@pytest.mark.parametrize(("case", "problem"), CLOSURE_UNCONFIRMED)
def test_settling_leaves_the_installation_open_while_a_command_citing_it_is_unconfirmed(
    closure_root: Path, monkeypatch: pytest.MonkeyPatch, case: str, problem: str | None
) -> None:
    items = inventory_tools.load(inventory_tools.INVENTORY_FILE)["items"]
    document = load_yaml(report.GAPS_FILE)
    path = report.UNIKIX_COMMANDS_FILE
    registry = load_yaml(path)
    citing = sorted(entry_id for entry_id, entry in registry["entries"].items()
                    if entry.get("gap") == CLOSURE_INSTALLATION)
    assert citing, f"no commands.yaml entry cites {CLOSURE_INSTALLATION}"
    unconfirmed = {"all": citing, "one": citing[-1:]}.get(case, [])
    for entry_id in unconfirmed:
        registry["entries"][entry_id]["status"] = "unconfirmed"
    if case == "no-entries":
        registry["entries"] = sorted(registry["entries"])
    elif case == "entry-text":
        registry["entries"][citing[0]] = "confirmed"
    if case == "absent":
        path.unlink()
    elif case == "directory":
        path = path.parent
        monkeypatch.setattr(report, "UNIKIX_COMMANDS_FILE", path)
    else:
        path.write_text("entries: [\n" if case == "not-yaml" else dump_yaml(registry), encoding="utf-8")
    # Nothing is verified, so only the installation's versions could settle
    # an entry, and the commands.yaml copy withholds that.
    state = SimpleNamespace(items=items, scenarios={}, statuses={}, item_scenarios={}, item_status={})
    expected = copy.deepcopy(document)
    summary = report.ImportSummary(CLOSURE_EXECUTION)
    closures: list = []
    report._settle_gaps(document, state, CLOSURE_EXECUTION, [CLOSURE_ENVIRONMENT], summary, closures)
    entry = report._gap_entries(document)[CLOSURE_INSTALLATION]
    assert (entry["status"], entry["resolving_evidence"]) == ("open", None)
    assert document == expected
    assert closures == []
    [line] = summary.gaps_changed
    assert line.startswith(f"{CLOSURE_INSTALLATION}: left open although {CLOSURE_VERSIONS_RECORDED}: "), line
    assert report.display_path(path) in line, line
    if problem is None:
        assert f" {', '.join(unconfirmed)} that cite" in line and "status: confirmed" in line, line
        assert all(entry_id not in line for entry_id in set(citing) - set(unconfirmed)), line
    else:
        assert problem in line and "repair the file" in line, line


@pytest.mark.parametrize(("gap_id", "revalidating"), [
    pytest.param(CLOSURE_INFERRED, False, id="inferred-import"),
    pytest.param(CLOSURE_INFERRED, True, id="inferred-revalidation"),
    pytest.param(CLOSURE_INSTALLATION, False, id="installation-import"),
])
@pytest.mark.parametrize(("condition", "withheld"), [
    pytest.param("unmatched", True, id="unmatched-message"),
    pytest.param("unmatched-screen", True, id="unmatched-screen-message"),
    pytest.param("unvalidated-message", True, id="unvalidated-scenario-in-closing-execution"),
    pytest.param("earlier-verifier", True, id="earlier-verifying-execution"),
    pytest.param("matched", False, id="matched-message"),
    pytest.param("unreachable", True, id="verified-but-unreachable"),
    pytest.param("empty-screen", False, id="no-addition-text"),
    pytest.param("short-added", False, id="short-message-already-added"),
    pytest.param("long-added", False, id="clipped-message-already-added"),
    pytest.param("unrelated", False, id="unrelated-execution"),
])
def test_closing_requires_the_inventory_to_reflect_its_evidence(
    closure_root: Path, monkeypatch: pytest.MonkeyPatch, gap_id: str, revalidating: bool,
    condition: str, withheld: bool,
) -> None:
    items = inventory_tools.load(inventory_tools.INVENTORY_FILE)["items"]
    item_id = "INV-SEC-007"
    item = next(item for item in items if item["id"] == item_id)
    document = load_yaml(report.GAPS_FILE)
    entry = report._gap_entries(document)[gap_id]
    entry["affected_inventory_ids"] = [item_id]
    entry.pop("affected", None)
    scenario = scenarios.get("SEC-COSGN00C-normal-01")
    earlier = "unikix-20250102T000000Z-0123abcd"
    unrelated = "unikix-20250101T000000Z-0123abcd"
    message_execution = earlier if condition == "earlier-verifier" else (
        unrelated if condition == "unrelated" else CLOSURE_EXECUTION
    )
    evidence_dir = closure_root / "tests" / "reports" / "unikix"
    monkeypatch.setattr(report, "UNIKIX_EVIDENCE_DIR", evidence_dir)
    message_scenario = scenarios.get("SEC-COSGN00C-negative-01") if condition == "unvalidated-message" else scenario
    result = evidence_dir / message_execution / message_scenario.id / report.RESULT_FILE
    result.parent.mkdir(parents=True)
    text = "A newly observed closure message"
    field_name = "ERRMSG"
    if condition == "unmatched-screen":
        field_name, text = scenarios.SCREEN_TEXT, f" \n  {text}\nA second screen line"
    elif condition == "matched":
        text = "Wrong Password. Try again ..."
    elif condition == "unreachable":
        item["activation"] = "unreachable"
        text = "Wrong Password. Try again ..."
    elif condition == "empty-screen":
        field_name, text = scenarios.SCREEN_TEXT, " \n   \n"
    elif condition in {"short-added", "long-added"}:
        text = "OK" if condition == "short-added" else "Observed message " * 20
        item["name"] = f"COSGN00C observed message '{report._clip(text.strip(), report._ADDITION_QUOTE_LIMIT)}'"
    result.write_text(dump_yaml({"observed_messages": [{"step": 1, "field": field_name, "value": text}]}),
                      encoding="utf-8")
    executions = [CLOSURE_EXECUTION, earlier] if condition == "earlier-verifier" else [CLOSURE_EXECUTION]
    state = SimpleNamespace(
        items=items, scenarios={scenario.id: scenario, message_scenario.id: message_scenario},
        statuses={scenario.id: SimpleNamespace(counted=[
            {"execution_id": execution, "adapter": "unikix", "status": "passed"} for execution in executions
        ])},
        item_scenarios={item_id: [scenario.id]}, item_status={item_id: report.VERIFIED},
        unikix_records=[] if condition == "unvalidated-message" else [
            report.UnikixRecord(message_execution, scenario.id, {}, result)
        ],
    )
    summary = report.ImportSummary(CLOSURE_EXECUTION)
    closures: list = []
    if revalidating:
        report._settle_inferred(document, state, None, summary.gaps_changed, closures)
    else:
        report._settle_gaps(document, state, CLOSURE_EXECUTION, [CLOSURE_ENVIRONMENT], summary, closures)
    assert entry["status"] == ("open" if withheld else "closed")
    assert (entry["resolving_evidence"] is None) == withheld
    assert any(closure.gap_id == gap_id for closure in closures) != withheld
    line = next(line for line in summary.gaps_changed if line.startswith(f"{gap_id}:"))
    if withheld:
        assert "left open although" in line and "the inventory does not yet reflect its closing evidence" in line
        if condition == "unreachable":
            assert f"{item_id} is recorded unreachable but verified" in line
        else:
            quoted = "A newly observed closure message" if condition == "unmatched-screen" else text
            assert f"1 observed message(s) of {message_execution} match no item (first: '{quoted}')" in line
    else:
        assert line.startswith(f"{gap_id}: closed,")


# An observed value, the text its inventory addition quotes, whether that
# text is shorter than the source extraction's cutoff
# (inventory_tools.MIN_MESSAGE_LENGTH), a different value of the same field
# observed later, and the text the later value's addition quotes: a short
# ERRMSG, and a short unformatted screen whose later rows hold more text,
# each later value merely containing the first; and ERRMSG values that read
# like a source template's placeholders, `<...>` and the ellipsis, short and
# long, whose later value only such a placeholder would match.
OBSERVED_MESSAGE_ADDITIONS = [
    pytest.param("ERRMSG", "Error", "Error", True, "Error reading the user security file",
                 "Error reading the user security file", id="errmsg"),
    pytest.param(scenarios.SCREEN_TEXT, " \n  Bye\nA second screen line", "Bye", True,
                 " \n  Bye for now\nA second screen line", "Bye for now", id="screen-text"),
    pytest.param("ERRMSG", "<x>", "<x>", True, "An unrelated outcome QQQ", "An unrelated outcome QQQ",
                 id="errmsg-angle-brackets"),
    pytest.param("ERRMSG", "\u2026", "\u2026", True, "An unrelated outcome QQQ", "An unrelated outcome QQQ",
                 id="errmsg-ellipsis"),
    pytest.param("ERRMSG", "<none> selected", "<none> selected", False, "Account selected", "Account selected",
                 id="errmsg-long-angle-brackets"),
]


@pytest.mark.parametrize(("field_name", "value", "text", "short", "later", "later_text"), OBSERVED_MESSAGE_ADDITIONS)
def test_an_observed_message_is_added_once_and_matches_only_its_literal_text(
    closure_root: Path, monkeypatch: pytest.MonkeyPatch, field_name: str, value: str, text: str, short: bool,
    later: str, later_text: str,
) -> None:
    assert (len(text) < inventory_tools.MIN_MESSAGE_LENGTH) == short
    evidence_dir = closure_root / "tests" / "reports" / "unikix"
    monkeypatch.setattr(report, "UNIKIX_EVIDENCE_DIR", evidence_dir)
    scenario = scenarios.get("SEC-COSGN00C-normal-01")
    later_execution = "unikix-20250104T000000Z-0123abcd"
    for execution_id, observed in ((CLOSURE_EXECUTION, value), (later_execution, later)):
        result = evidence_dir / execution_id / scenario.id / report.RESULT_FILE
        result.parent.mkdir(parents=True)
        result.write_text(dump_yaml({"observed_messages": [{"step": 1, "field": field_name, "value": observed}]}),
                          encoding="utf-8")
    items = inventory_tools.load(inventory_tools.INVENTORY_FILE)["items"]
    added: list[dict] = []

    def record_additions(new_items: list[dict], reason: str) -> list[str]:
        """append_items without the committed inventory its defaults name:
        each item gets the next id of its area and is recorded."""
        assert reason.strip()
        assigned = []
        for new in new_items:
            item_id = inventory_tools.next_id(new["area"], {"header": {}, "items": [*items, *added]})
            added.append({"id": item_id, **{key: entry for key, entry in new.items() if key != "area"}})
            assigned.append(item_id)
        return assigned

    monkeypatch.setattr(report._inventory, "append_items", record_additions)
    document = {"gaps": [{"gap_id": report.GAP_COMPLETENESS, "status": "open", "affected_inventory_ids": []}]}
    first = report.ImportSummary(CLOSURE_EXECUTION)
    report._new_message_items(SimpleNamespace(items=items, scenarios={scenario.id: scenario}), CLOSURE_EXECUTION,
                              [scenario.id], document, first)
    assert len(added) == 1 and first.items_added == [added[0]["id"]] and first.rejected == []
    assert added[0]["name"] == f"{scenario.program_or_job} observed message '{text}'"
    listed = report._gap_entries(document)[report.GAP_COMPLETENESS]["affected_inventory_ids"]
    assert listed == first.items_added
    # The next import of the same evidence finds the addition: nothing is
    # added, the total stays where the first import raised it, and the
    # register is left as it was.
    second = report.ImportSummary(CLOSURE_EXECUTION)
    report._new_message_items(SimpleNamespace(items=[*items, *added], scenarios={scenario.id: scenario}),
                              CLOSURE_EXECUTION, [scenario.id], document, second)
    assert len(added) == 1
    assert (second.items_added, second.gaps_changed, second.rejected) == ([], [], [])
    assert report._gap_entries(document)[report.GAP_COMPLETENESS]["affected_inventory_ids"] == listed
    # The addition matches its own value as literal text, but not the later
    # value, neither one holding it nor one a placeholder would stand for.
    patterns = report._patterns_of([*items, *added])
    assert report.message_items(value, field_name, patterns) == [added[0]["id"]]
    assert report.message_items(later, field_name, patterns) == []
    # So the import of the later execution adds the later value in turn: one
    # more item, which the register lists beside the first.
    third = report.ImportSummary(later_execution)
    report._new_message_items(SimpleNamespace(items=[*items, *added], scenarios={scenario.id: scenario}),
                              later_execution, [scenario.id], document, third)
    assert len(added) == 2 and third.items_added == [added[1]["id"]] and third.rejected == []
    assert added[1]["name"] == f"{scenario.program_or_job} observed message '{later_text}'"
    assert report._gap_entries(document)[report.GAP_COMPLETENESS]["affected_inventory_ids"] == sorted(
        [*listed, added[1]["id"]])
    patterns = report._patterns_of([*items, *added])
    assert report.message_items(value, field_name, patterns) == [added[0]["id"]]
    assert report.message_items(later, field_name, patterns) == [added[1]["id"]]


def test_write_register_records_the_closing_in_the_inventory_before_gaps_yaml(closure_root: Path) -> None:
    before = inventory_tools.load(inventory_tools.INVENTORY_FILE)
    document, original, changes, closures = _closed_inferred_entry()
    report._write_register(document, original, closures, changes)
    after = inventory_tools.load(inventory_tools.INVENTORY_FILE)
    *earlier, recorded = after["header"]["changes"]
    assert earlier == before["header"]["changes"]
    assert (recorded["gap_id"], recorded["reason"]) == (CLOSURE_INFERRED,
                                                        _closure_reason(CLOSURE_INFERRED, CLOSURE_VERIFIED))
    assert after["items"] == before["items"] and after["header"]["total"] == before["header"]["total"]
    entry = report._gap_entries(load_yaml(report.GAPS_FILE))[CLOSURE_INFERRED]
    assert entry["status"] == "closed" and entry["resolving_evidence"] == CLOSURE_EVIDENCE
    assert changes == [f"{CLOSURE_INFERRED}: closed, {CLOSURE_VERIFIED}"]


@pytest.mark.parametrize(("failure", "why"), CLOSURE_INVENTORY_FAILURES)
def test_write_register_leaves_the_entry_open_when_the_inventory_cannot_record_its_closing(
    closure_root: Path, failure: str, why: str
) -> None:
    path = inventory_tools.INVENTORY_FILE
    if failure == "directory":
        path.unlink()
        path.mkdir()
    else:
        path.write_text(dump_yaml({"items": inventory_tools.load(INVENTORY_DIR / "inventory.yaml")["items"]}),
                        encoding="utf-8")
    document, original, _, closures = _closed_inferred_entry()
    summary = report.ImportSummary(CLOSURE_EXECUTION)
    summary.gaps_changed.append(f"{CLOSURE_INFERRED}: closed, {CLOSURE_VERIFIED}")
    report._write_register(document, original, closures, summary.gaps_changed)
    entry = report._gap_entries(document)[CLOSURE_INFERRED]
    assert entry["status"] == "open" and entry["resolving_evidence"] is None
    assert report.GAPS_FILE.read_text(encoding="utf-8") == original
    assert len(summary.gaps_changed) == 1 and summary.gaps_changed[0].startswith(CLOSURE_LEFT_OPEN)
    assert why in summary.gaps_changed[0]
    assert f"  gap updated {summary.gaps_changed[0]}" in summary.lines()
    assert summary.rejected == []


def test_write_register_withdraws_its_closure_when_gaps_yaml_cannot_be_written(
    closure_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    register = report.GAPS_FILE
    link = closure_root / "gaps-link.yaml"
    link.symlink_to(register)
    monkeypatch.setattr(report, "GAPS_FILE", link)
    inventory_text, register_text = inventory_tools.INVENTORY_FILE.read_bytes(), register.read_bytes()
    document, original, changes, closures = _closed_inferred_entry()
    with pytest.raises(report.ReportError, match="is a symbolic link; nothing is written through it"):
        report._write_register(document, original, closures, changes)
    assert inventory_tools.INVENTORY_FILE.read_bytes() == inventory_text
    assert register.read_bytes() == register_text


def test_write_register_rollback_preserves_a_concurrent_inventory_addition(
    closure_root: Path, carddemo_repo_root: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = inventory_tools.INVENTORY_FILE
    before = inventory_tools.load(path)
    source = closure_root / "app" / "cbl" / "COSGN00C.cbl"
    source.parent.mkdir(parents=True)
    shutil.copyfile(carddemo_repo_root / "app" / "cbl" / source.name, source)
    document, original, changes, closures = _closed_inferred_entry()
    assigned: list[str] = []
    during: list[list] = []
    reason = "New behavior observed while the import updates the register"
    first = datetime.date.today().isoformat()

    def add_then_refuse(document: dict, original: str) -> None:
        assigned.extend(inventory_tools.append_items([{
            "area": "SEC", "kind": "rule", "name": "Concurrent newly observed behavior",
            "source": "app/cbl/COSGN00C.cbl:1", "description": "A behavior found by another inventory writer.",
            "level": "e2e", "gap_ids": [report.GAP_COMPLETENESS],
        }], reason, path, repo_root=closure_root))
        during.append(inventory_tools.load(path)["header"]["changes"])
        raise PermissionError(13, "simulated gaps.yaml write refusal")

    monkeypatch.setattr(report, "_write_gaps", add_then_refuse)
    with pytest.raises(PermissionError, match="simulated gaps.yaml write refusal"):
        report._write_register(document, original, closures, changes)
    days = {first, datetime.date.today().isoformat()}
    after = inventory_tools.load(path)
    earlier = before["header"]["changes"]
    assert len(assigned) == 1 and len(during) == 1
    dates = [change.get("date") for change in during[0][len(earlier):]]
    assert len(dates) == 2 and set(dates) <= days
    attempted = {"date": dates[0], "gap_id": CLOSURE_INFERRED,
                 "reason": _closure_reason(CLOSURE_INFERRED, CLOSURE_VERIFIED)}
    addition = {"date": dates[1], "inventory_id": assigned[0], "reason": reason}
    # While gaps.yaml is written, the inventory holds every earlier change,
    # this write's closure record, and then the concurrent addition.
    assert during[0] == [*earlier, attempted, addition]
    assert after["header"]["total"] == before["header"]["total"] + 1 == len(after["items"]) == (
        len(before["items"]) + 1
    )
    assert set(inventory_tools.item_ids(after)) == {*inventory_tools.item_ids(before), *assigned}
    assert next(item for item in after["items"] if item["id"] == assigned[0])["name"] == (
        "Concurrent newly observed behavior"
    )
    # The rollback withdrew only the attempted closure record: every earlier
    # change, closure records included, and the concurrent addition remain.
    assert after["header"]["changes"] == [*earlier, addition]
    assert report.GAPS_FILE.read_text(encoding="utf-8") == original


def test_write_register_names_both_failures_when_its_closures_cannot_be_withdrawn(
    closure_root: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    link = closure_root / "gaps-link.yaml"
    link.symlink_to(report.GAPS_FILE)
    monkeypatch.setattr(report, "GAPS_FILE", link)

    def refuse(records: list, path: Path, *, repo_root: Path) -> None:
        raise inventory_tools.InventoryError(f"cannot write {path}: Permission denied")

    monkeypatch.setattr(inventory_tools, "withdraw_closures", refuse)
    before = inventory_tools.load(inventory_tools.INVENTORY_FILE)["header"]["changes"]
    document, original, changes, closures = _closed_inferred_entry()
    with pytest.raises(report.ReportError, match="could not withdraw its closure records") as raised:
        report._write_register(document, original, closures, changes)
    text = str(raised.value)
    assert "is a symbolic link" in text and "Permission denied" in text
    *earlier, recorded = inventory_tools.load(inventory_tools.INVENTORY_FILE)["header"]["changes"]
    assert earlier == before and recorded["gap_id"] == CLOSURE_INFERRED
    assert repr(recorded) in text and "without discarding other inventory changes" in text


@pytest.mark.parametrize("files", [
    pytest.param([], id="empty"),
    pytest.param(["environment.yaml"], id="files-only"),
])
def test_import_of_an_execution_with_no_scenario_record_writes_nothing(
    closure_root: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str], files: list[str]
) -> None:
    tests_root = closure_root / "tests"
    evidence_dir = tests_root / "reports" / "unikix"
    execution_dir = evidence_dir / CLOSURE_EXECUTION
    execution_dir.mkdir(parents=True)
    for name in files:
        (execution_dir / name).write_text(dump_yaml(CLOSURE_ENVIRONMENT), encoding="utf-8")
    # The lock is taken on this case's own tests/ root, never the checkout's.
    monkeypatch.setattr(report, "TESTS_ROOT", tests_root)
    monkeypatch.setattr(report, "UNIKIX_EVIDENCE_DIR", evidence_dir)

    def refuse(name: str) -> Callable[..., None]:
        def called(*args: object, **kwargs: object) -> None:
            pytest.fail(f"report.{name} ran for an execution holding no scenario record")
        return called

    for name in ("build_state", "_write_register", "regenerate"):
        monkeypatch.setattr(report, name, refuse(name))
    copies = (report.GAPS_FILE, inventory_tools.INVENTORY_FILE)
    before = [path.read_bytes() for path in copies]
    assert report.main(["--import-unikix", CLOSURE_EXECUTION]) == 2
    out, err = capsys.readouterr()
    assert out == ""
    assert err == (f"report.py: error: 0 scenario records found in {report.display_path(execution_dir)}; "
                   "nothing was imported\n")
    assert [path.read_bytes() for path in copies] == before
    assert sorted(path.name for path in execution_dir.iterdir()) == files


# Evidence dataset bounds: report._evidence_datasets on a datasets/ folder of
# the case's own, read through _DatasetReads, report's os for the case, with
# DATASET_LIMITS taken down where a case gives them (None keeps the committed
# ones). Each case: the files, as (name, size, sparse), a sparse file taking
# no disk space; the limits; the file whose size fstat understates as
# DATASET_GROWN_SIZE, as when it grew after its size was taken; the outcome,
# each dataset returned by name with the file holding its bytes, or the
# refusal after the folder's shown path; the bytes read from each file
# opened, which names every file opened; and the capture_summary.datasets
# map of result.yaml, file name -> the name it gives back. The made-up
# CARD<n>.bin names of the folder limit case are no dataset's: the entries
# count toward that limit whatever their names.
DATASET_MIB = 1024 * 1024
DATASET_GROWN_SIZE = 10
DATASET_LIMITS = ("EVIDENCE_DATASET_MAX_FILES", "EVIDENCE_DATASET_MAX_BYTES", "EVIDENCE_DATASETS_MAX_BYTES")
# A file of every kind of name a scenario can name, the map giving back the
# reserved observations whose file names do not show them.
DATASET_RECOGNIZED_FILES = (
    ("ACCTDATA.bin", 3, False), ("_AFTER_POSTTRAN_ACCTDATA.bin", 4, False),
    ("_AFTER_POSTTRAN__REGION-FILES.bin", 5, False), ("_AIX_CARDAIX.bin", 6, False),
    ("_BEFORE_POSTTRAN__REGION-FILES.bin", 7, False), ("_CATALOG.bin", 8, False),
    ("_REGION-FILES.bin", 9, False), ("_TDQ_JOBS.bin", 10, False),
)
DATASET_RECOGNIZED_SUMMARY = {
    "_AFTER_POSTTRAN_ACCTDATA.bin": "@AFTER:POSTTRAN:ACCTDATA",
    "_AFTER_POSTTRAN__REGION-FILES.bin": "@AFTER:POSTTRAN:@REGION-FILES",
    "_AIX_CARDAIX.bin": "@AIX:CARDAIX",
    "_BEFORE_POSTTRAN__REGION-FILES.bin": "@BEFORE:POSTTRAN:@REGION-FILES",
}
# Sparse gibibyte files under names no scenario can name, from the map or
# their file names: an unknown alternate-index path, a stage observation of
# no logical dataset or of another observation, a stage name holding
# whitespace, a region stage of another observation, and plain unknown names.
DATASET_UNRECOGNIZED_FILES = tuple((name, 1024 * DATASET_MIB, True) for name in (
    "CARDDATA.bin", "JUNK.bin", "X.bin", "_AFTER_POSTTRAN_JUNK.bin", "_AFTER_POSTTRAN__CATALOG.bin",
    "_AFTER_POST_TRAN_ACCTDATA.bin", "_AIX_NOSUCHAIX.bin", "_BEFORE_POSTTRAN__CATALOG.bin", "_JUNK.bin",
))
DATASET_UNRECOGNIZED_SUMMARY = {
    "CARDDATA.bin": "JUNK",
    "X.bin": "JUNK",
    "_AFTER_POSTTRAN_JUNK.bin": "@AFTER:POSTTRAN:JUNK",
    "_AFTER_POSTTRAN__CATALOG.bin": "@AFTER:POSTTRAN:@CATALOG",
    "_AFTER_POST_TRAN_ACCTDATA.bin": "@AFTER:POST TRAN:ACCTDATA",
    "_AIX_NOSUCHAIX.bin": "@AIX:NOSUCHAIX",
    "_BEFORE_POSTTRAN__CATALOG.bin": "@BEFORE:POSTTRAN:@CATALOG",
}
DATASET_BOUND_CASES = [
    pytest.param((("ACCTDATA.bin", 16 * DATASET_MIB + 1, True),), None, None,
                 "/ACCTDATA.bin holds 16777217 bytes; one evidence datasets/ file may hold at most 16777216",
                 {"ACCTDATA.bin": 0}, {}, id="one-byte-past-the-file-limit"),
    pytest.param((("ACCTDATA.bin", 60, False), ("CARDDATA.bin", 41, False)), (512, 64, 100), None,
                 "/CARDDATA.bin holds 41 bytes, and the datasets/ files of one scenario's evidence may hold at most "
                 "100 together (40 were left for it)",
                 {"ACCTDATA.bin": 60, "CARDDATA.bin": 0}, {}, id="files-together-past-the-scenario-limit"),
    pytest.param(tuple((f"CARD{index}.bin", 1, False) for index in range(4)), (3, 64, 128), None,
                 " holds more than 3 entries; an evidence datasets/ folder may hold at most 3", {}, {},
                 id="one-entry-past-the-folder-limit"),
    pytest.param((("ACCTDATA.bin", 64, False), ("_CATALOG.bin", 64, False)), (2, 64, 128), None,
                 {"ACCTDATA": "ACCTDATA.bin", "@CATALOG": "_CATALOG.bin"}, {"ACCTDATA.bin": 64, "_CATALOG.bin": 64},
                 {}, id="exactly-at-every-limit"),
    pytest.param((("ACCTDATA.bin", 1024 * DATASET_MIB, True),), (512, 64, 128), "ACCTDATA.bin",
                 "/ACCTDATA.bin holds more than 64 bytes; one evidence datasets/ file may hold at most 64",
                 {"ACCTDATA.bin": 65}, {}, id="grown-after-its-size-was-taken"),
    pytest.param((("_AIX_CARDAIX.bin", 1024 * DATASET_MIB, True), ("ACCTDATA.bin", 3, False)), None, None,
                 {"ACCTDATA": "ACCTDATA.bin"}, {"ACCTDATA.bin": 3}, {},
                 id="an-unnamed-observation-file-is-never-opened"),
    pytest.param((("JUNK.bin", 1024 * DATASET_MIB, True), ("ACCTDATA.bin", 3, False)), None, None,
                 {"ACCTDATA": "ACCTDATA.bin"}, {"ACCTDATA.bin": 3}, {}, id="a-file-of-no-dataset-is-never-opened"),
    pytest.param(DATASET_UNRECOGNIZED_FILES + (("ACCTDATA.bin", 3, False),), None, None,
                 {"ACCTDATA": "ACCTDATA.bin"}, {"ACCTDATA.bin": 3}, DATASET_UNRECOGNIZED_SUMMARY,
                 id="files-mapped-or-named-to-no-scenario-name-are-never-opened"),
    pytest.param(DATASET_RECOGNIZED_FILES, (8, 10, 52), None,
                 {"ACCTDATA": "ACCTDATA.bin", "@AFTER:POSTTRAN:ACCTDATA": "_AFTER_POSTTRAN_ACCTDATA.bin",
                  "@AFTER:POSTTRAN:@REGION-FILES": "_AFTER_POSTTRAN__REGION-FILES.bin",
                  "@AIX:CARDAIX": "_AIX_CARDAIX.bin",
                  "@BEFORE:POSTTRAN:@REGION-FILES": "_BEFORE_POSTTRAN__REGION-FILES.bin",
                  "@CATALOG": "_CATALOG.bin", "@REGION-FILES": "_REGION-FILES.bin", "@TDQ:JOBS": "_TDQ_JOBS.bin"},
                 {name: size for name, size, _sparse in DATASET_RECOGNIZED_FILES}, DATASET_RECOGNIZED_SUMMARY,
                 id="every-kind-of-name-a-scenario-can-name-is-read"),
]


class _DatasetReads:
    """``report``'s ``os`` for one case: every call goes to ``os``, but each
    file opened relative to a folder is recorded in ``received`` with the
    bytes read from it, and ``fstat`` gives the file named ``grown`` the size
    DATASET_GROWN_SIZE."""

    def __init__(self, grown: str | None) -> None:
        self.grown = grown
        self.received: dict[str, int] = {}
        self._names: dict[int, str] = {}

    def __getattr__(self, name: str) -> object:
        return getattr(os, name)

    def open(self, path: str, flags: int, mode: int = 0o777, *, dir_fd: int | None = None) -> int:
        descriptor = os.open(path, flags, mode, dir_fd=dir_fd)
        if dir_fd is not None:
            self._names[descriptor] = path
            self.received.setdefault(path, 0)
        return descriptor

    def read(self, descriptor: int, length: int) -> bytes:
        data = os.read(descriptor, length)
        if descriptor in self._names:
            self.received[self._names[descriptor]] += len(data)
        return data

    def fstat(self, descriptor: int) -> object:
        info = os.fstat(descriptor)
        if self.grown is not None and self._names.get(descriptor) == self.grown:
            return SimpleNamespace(st_mode=info.st_mode, st_size=DATASET_GROWN_SIZE)
        return info

    def close(self, descriptor: int) -> None:
        self._names.pop(descriptor, None)
        os.close(descriptor)


def _evidence_snapshot(root: Path) -> dict[str, tuple[int, int, int, bytes | None]]:
    """Every path under ``root``, links never followed: its mode, size and
    modification time, and the bytes of each regular file of at most a
    mebibyte, so a sparse gibibyte file is never read whole."""
    snapshot: dict[str, tuple[int, int, int, bytes | None]] = {}
    for path in sorted(root.rglob("*")):
        info = path.lstat()
        small = stat.S_ISREG(info.st_mode) and info.st_size <= DATASET_MIB
        snapshot[path.relative_to(root).as_posix()] = (
            info.st_mode, info.st_size, info.st_mtime_ns, path.read_bytes() if small else None)
    return snapshot


@pytest.mark.parametrize(("files", "limits", "grown", "outcome", "reads", "summary"), DATASET_BOUND_CASES)
def test_evidence_datasets_are_read_only_within_their_bounds(
    monkeypatch: pytest.MonkeyPatch,
    work_root_case: Path,
    files: tuple[tuple[str, int, bool], ...],
    limits: tuple[int, int, int] | None,
    grown: str | None,
    outcome: str | dict[str, str],
    reads: dict[str, int],
    summary: dict[str, str],
) -> None:
    folder = work_root_case / EVIDENCE_SCENARIO / report.EVIDENCE_DATASETS_DIR
    folder.mkdir(parents=True)
    contents = {}
    for index, (name, size, sparse) in enumerate(files):
        path = folder / name
        path.write_bytes(b"" if sparse else bytes([ord("A") + index]) * size)
        if sparse:
            os.truncate(path, size)
        else:
            contents[name] = path.read_bytes()
    before = _evidence_snapshot(folder)
    if limits is not None:
        for name, value in zip(DATASET_LIMITS, limits):
            monkeypatch.setattr(report, name, value)
    reader = _DatasetReads(grown)
    monkeypatch.setattr(report, "os", reader)
    raw = {"capture_summary": {"datasets": summary}} if summary else {}
    if isinstance(outcome, str):
        with pytest.raises(report.ReportError) as refused:
            report._evidence_datasets(folder.parent, raw)
        assert str(refused.value) == f"{report.display_path(folder)}{outcome}"
    else:
        found = report._evidence_datasets(folder.parent, raw)
        assert found == {name: contents[file_name] for name, file_name in outcome.items()}
        assert list(found) == sorted(found, key=outcome.__getitem__)
    assert reader.received == reads
    assert _evidence_snapshot(folder) == before


# When the logical datasets of tests/fixtures/datasets.yaml cannot be read,
# report._evidence_datasets cannot tell which datasets/ files a scenario can
# name: each case, the failure harness.fixtures.specs raises and the end of
# the refusal after "the datasets/ files of <scenario dir> cannot be named: ".
DATASET_NAMING_FAILURES = [
    pytest.param(OSError(errno.EACCES, "Permission denied", str(fixtures.FIXTURES_FILE)),
                 "cannot read tests/fixtures/datasets.yaml: Permission denied", id="unreadable"),
    pytest.param(fixtures.FixtureError("tests/fixtures/datasets.yaml: datasets must be a mapping"),
                 "tests/fixtures/datasets.yaml: datasets must be a mapping", id="malformed"),
]


@pytest.mark.parametrize(("failure", "refusal"), DATASET_NAMING_FAILURES)
def test_evidence_datasets_are_refused_unread_when_no_name_can_be_recognized(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, failure: Exception, refusal: str
) -> None:
    folder = work_root_case / EVIDENCE_SCENARIO / report.EVIDENCE_DATASETS_DIR
    folder.mkdir(parents=True)
    (folder / "ACCTDATA.bin").write_bytes(b"A" * 3)
    before = _evidence_snapshot(folder)

    def specs() -> dict:
        raise failure

    monkeypatch.setattr(report._fixtures, "specs", specs)
    reader = _DatasetReads(None)
    monkeypatch.setattr(report, "os", reader)
    with pytest.raises(report.ReportError) as refused:
        report._evidence_datasets(folder.parent, {})
    assert str(refused.value) == (f"the datasets/ files of {report.display_path(folder.parent)} cannot be named: "
                                  f"{refusal}")
    assert reader.received == {}
    assert _evidence_snapshot(folder) == before


# The bounds on import and on revalidation: report.main on the evidence, in a
# tests root of the case's own (evidence_tests_root), of a passed UniKix run
# of PROMOTION_SCENARIO, whose derived baseline an agreeing record would
# promote, written by evidence.EvidenceWriter with the zip's hash of every
# unit the scenario executes and admissible as written, then its ACCTDATA.bin
# made a sparse gibibyte file; before a revalidation an earlier
# validation.yaml lies beside it. report's state holds the zip's identity
# alone; validating, promoting and writing a validation.yaml or a baseline
# fail the case, and the steps after the records are recorded instead of
# run, so nothing in the checkout is written. Each case: the arguments and
# the start of the line reporting the record rejected.
DATASET_BOUND_ACTIONS = [
    pytest.param(["--import-unikix", EVIDENCE_EXECUTION_ID], f"  rejected {PROMOTION_SCENARIO}: ", id="import"),
    pytest.param(["--revalidate"], f"rejected {EVIDENCE_EXECUTION_ID}/{PROMOTION_SCENARIO}: ", id="revalidation"),
]


@pytest.mark.parametrize(("argv", "label"), DATASET_BOUND_ACTIONS)
def test_evidence_past_a_dataset_bound_is_rejected_with_nothing_written(
    monkeypatch: pytest.MonkeyPatch,
    evidence_tests_root: Path,
    capsys: pytest.CaptureFixture[str],
    argv: list[str],
    label: str,
) -> None:
    # The limits tests/reports/unikix/README.md states.
    assert tuple(getattr(report, name) for name in DATASET_LIMITS) == (512, 16 * DATASET_MIB, 64 * DATASET_MIB)
    tests_root = evidence_tests_root.resolve()
    zip_info = report.zip_identity()
    assert zip_info is not None
    digest, members = zip_info
    scenario = scenarios.get(PROMOTION_SCENARIO)
    baseline = baselines.load(PROMOTION_SCENARIO)
    assert baseline.capture_platform == "derived"
    units, problem = report._required_units(scenario, digest)
    assert problem is None
    identity = {"commit": "a" * 40, "tree": "unikix-copy", "units": {unit: members[unit] for unit in sorted(units)},
                "zip_sha256": digest}
    code = baseline.expected["return_code"]
    record = results.build_record(
        scenario,
        results.UNIKIX,
        results.STATUS_PASSED,
        baseline_version=baseline.version,
        decoded={"return_code": code},
        execution_fingerprint=SCHEMA_GATE_FINGERPRINT,
        source_identity=identity,
        execution_id=EVIDENCE_EXECUTION_ID,
    )
    environment: dict[str, object] = {
        key: f"/opt/carddemo/{key.lower()}" for key in (*report._INSTALLATION_FACTS, *report._DEPLOYMENT_FACTS)}
    environment.update({key: "12.0.0" for key in report.UNIKIX_VERSION_KEYS})
    run = base.Result(return_code=code, datasets={"ACCTDATA": fixtures.repository_dataset("ACCTDATA-PS")},
                      screens=[], messages=list(baseline.expected["messages"]))
    evidence.EvidenceWriter(tests_root, EVIDENCE_EXECUTION_ID).write(
        PROMOTION_SCENARIO, record, evidence.Capture(environment=environment, result=run))
    scenario_dir = evidence.evidence_root(tests_root, EVIDENCE_EXECUTION_ID) / PROMOTION_SCENARIO
    found, why = report.read_evidence(EVIDENCE_EXECUTION_ID, scenario_dir, zip_info)
    assert why is None and found is not None and found.ran
    oversized = scenario_dir / evidence.DATASETS_DIR / "ACCTDATA.bin"
    os.truncate(oversized, 1024 * DATASET_MIB)
    if argv == ["--revalidate"]:
        (scenario_dir / report.VALIDATION_FILE).write_text(REVALIDATION_RECORDED, encoding="utf-8")
    before = _evidence_snapshot(tests_root)
    monkeypatch.setattr(report, "TESTS_ROOT", tests_root)
    monkeypatch.setattr(report, "UNIKIX_EVIDENCE_DIR", tests_root / "reports" / "unikix")
    state = SimpleNamespace(zip_info=zip_info, copy_gaps=report._CopyGaps({}, frozenset()), items=[], total=0,
                            unread_scenarios={}, unread_records={}, unread_validations={})
    monkeypatch.setattr(report, "build_state", lambda: state)
    for name, what in (("_validate_evidence", "the validation"), ("_promote", "the promotion"),
                       ("_write_validation", "a validation.yaml write")):
        monkeypatch.setattr(report, name, _refuse(what))
    monkeypatch.setattr(baselines, "write", _refuse("baselines.write"))
    handed: list[tuple[str, object]] = []

    def hand(step: str, pick: Callable[..., object], returns: object = None) -> Callable[..., object]:
        def called(*args: object) -> object:
            handed.append((step, pick(*args)))
            return returns
        return called

    monkeypatch.setattr(report, "_new_message_items",
                        hand("imported", lambda current, execution, imported, *rest: sorted(imported)))
    monkeypatch.setattr(report, "_settle_gaps",
                        hand("environments", lambda document, current, execution, environments, *rest:
                             list(environments)))
    monkeypatch.setattr(report, "_settle_inferred", hand("inferred", lambda *args: None))
    monkeypatch.setattr(report, "_write_register",
                        hand("register unchanged", lambda document, *rest: document == load_yaml(report.GAPS_FILE),
                             False))
    monkeypatch.setattr(report, "regenerate", hand("regenerated", lambda: None))
    assert report.main(argv) == 1
    out, err = capsys.readouterr()
    refusal = (f"{label}{report.display_path(oversized)} holds {1024 * DATASET_MIB} bytes; one evidence datasets/ "
               f"file may hold at most {16 * DATASET_MIB}")
    assert err == ""
    if argv[0] == "--import-unikix":
        assert out.splitlines() == [f"import of {EVIDENCE_EXECUTION_ID}:", refusal]
        assert handed == [("imported", []), ("environments", []), ("register unchanged", True),
                          ("regenerated", None)]
    else:
        assert out.splitlines() == [refusal]
        assert handed == [("inferred", None), ("register unchanged", True)]
    assert _evidence_snapshot(tests_root) == before


def test_report_lists_a_closure_record_by_its_gap_id() -> None:
    state = report.build_state()
    addition = {"date": "2025-01-03", "inventory_id": "INV-SEC-001", "reason": "An addition."}
    closure = {"date": "2025-01-03", "gap_id": CLOSURE_INFERRED,
               "reason": _closure_reason(CLOSURE_INFERRED, CLOSURE_VERIFIED)}
    state = dataclasses.replace(state, header={**state.header, "changes": [addition, closure]})
    lines = report.render_report(state, (False, "not judged here")).split("\n")
    start = lines.index("Changes since the freeze:")
    assert lines[start + 1:start + 5] == [
        "",
        "- 2025-01-03 INV-SEC-001: An addition.",
        f"- 2025-01-03 {CLOSURE_INFERRED}: {_closure_reason(CLOSURE_INFERRED, CLOSURE_VERIFIED)}",
        "",
    ]


def test_report_shows_both_projections_and_names_the_page_zero_one_it_equals() -> None:
    state = report.build_state()
    failing_probe = report.PLANNING_PROJECTION_NO_PAGE_ZERO
    chosen = sorted(state.item_status)[:sum(failing_probe.values())]
    statuses = [status for status in report.ITEM_STATUSES for _ in range(failing_probe[status])]
    state = dataclasses.replace(
        state,
        items=[item for item in state.items if item["id"] in set(chosen)],
        item_status=dict(zip(chosen, statuses)),
    )
    lines = report.render_report(state, (False, "not judged here")).split("\n")
    planned = report.PLANNING_PROJECTION
    assert (f"| verified | {failing_probe['verified']} | {planned['verified']} | {failing_probe['verified']} |"
            in lines)
    assert "- Planning projection: verified / total 125 / 684 = 18.3%" in lines
    assert "- Projection with the page-zero probe failing: verified / total 112 / 684 = 16.4%" in lines
    assert ("The counts equal the projection with the page-zero probe failing and differ from the planning "
            "projection:") in lines
    assert "The counts equal the planning projection." not in lines
    assert "100% may be claimed: no" in lines


def test_report_seeds_authentication_with_the_sign_on_and_admin_menu_scenarios(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    state = report.build_state()
    scenarios = Counter((scenario.area, scenario.program_or_job) for scenario in state.scenarios.values())
    sign_on = sum(count for (area, _), count in scenarios.items() if area == "SEC")
    admin_menu, main_menu = scenarios[("MNU", "COADM01C")], scenarios[("MNU", "COMEN01C")]
    assert sign_on and admin_menu and main_menu
    lines = report.render_report(state, (False, "not judged here")).split("\n")
    # The MNU-COMEN01C scenarios are left out of the Authentication count.
    assert f"| Authentication | 5 | SEC, MNU-COADM01C | {sign_on + admin_menu} |" in lines
    by_area = lines[lines.index("## By area"):lines.index("### Tech-spec seed counts (Section 6.6.7.1)")]
    shared = "| Authentication 5 (shared; SEC and MNU-COADM01C scenarios) |"
    for area in ("SEC", "MNU"):
        assert next(line for line in by_area if line.startswith(f"| {area} |")).endswith(shared)
    assert next(line for line in by_area if line.startswith("| RPT |")).endswith(
        "| Batch Jobs 15 (shared; batch scenarios) |")
    # Two seeds naming one area are both shown there.
    monkeypatch.setattr(report, "SEED_COUNTS", (*report.SEED_COUNTS, ("Main Menu", 1, ("MNU-COMEN01C",), False)))
    assert report._seed_of_area()["MNU"] == (
        "Authentication 5 (shared; SEC and MNU-COADM01C scenarios); Main Menu 1 (shared; MNU-COMEN01C scenarios)")


@pytest.fixture(scope="module")
def committed_state() -> report.State:
    """``report.build_state()`` over the committed inventory, register,
    scenarios and records, built once for the module; cases derive their own
    states from it with ``dataclasses.replace`` and never change it."""
    return report.build_state()


def _forbid_report_writes(monkeypatch: pytest.MonkeyPatch) -> None:
    """Make any write of a report file or of the inventory fail the case."""

    def refuse(*args: object, **kwargs: object) -> None:
        raise AssertionError(f"a refused regeneration wrote {args[:1]!r}")

    monkeypatch.setattr(report, "write_text", refuse)
    monkeypatch.setattr(report, "_write_inventory", refuse)


def _percent_text(numerator: int, denominator: int) -> str:
    """A fraction as the report prints it: a percentage to one decimal place, halves rounded up."""
    value = (Decimal(numerator) * 100 / Decimal(denominator)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
    return f"{value}%"


# (items listed against the frozen total, how many of them are verified, the
# rest not-covered); the register holds no entry, so only the totals decide
# the claim.
FROZEN_TOTAL_CASES = [
    pytest.param(0, None, True, id="list equal to the header, all verified"),
    pytest.param(-584, None, False, id="list shrunk, every live item verified"),
    pytest.param(1, None, False, id="list enlarged, every live item verified"),
    pytest.param(1, 0, False, id="list enlarged, the frozen total verified"),
]


@pytest.mark.parametrize(("listed_change", "verified_change", "claimed"), FROZEN_TOTAL_CASES)
def test_report_divides_by_the_frozen_total_and_claims_only_when_the_list_agrees(
    committed_state: report.State, listed_change: int, verified_change: int | None, claimed: bool
) -> None:
    frozen = committed_state.total
    items = list(committed_state.items)
    if listed_change < 0:
        items = items[:len(items) + listed_change]
    batch = [item for item in committed_state.items if item["id"].startswith("INV-BAT-")]
    last = max(int(item["id"].rsplit("-", 1)[1]) for item in batch)
    for number in range(last + 1, last + 1 + max(listed_change, 0)):
        items.append({**batch[-1], "id": f"INV-BAT-{number:03d}"})
    verified = len(items) if verified_change is None else frozen + verified_change
    ids = [item["id"] for item in items]
    state = dataclasses.replace(
        committed_state,
        items=items,
        item_status={item_id: report.VERIFIED if index < verified else report.NOT_COVERED
                     for index, item_id in enumerate(ids)},
        gaps=report.GapRegister({"gaps": []}, {}),
    )
    assert state.total == frozen == committed_state.header["total"]
    lines = report.render_report(state, (False, "not judged here")).split("\n")
    summary = lines[lines.index("## Summary"):lines.index("### Against the planning projection")]
    assert f"| total | {frozen} |" in summary
    assert f"Verified / total: {verified} / {frozen} = {_percent_text(verified, frozen)}" in summary
    planned = sum(report.PLANNING_PROJECTION.values())
    assert f"| total | {frozen} | {planned} | {sum(report.PLANNING_PROJECTION_NO_PAGE_ZERO.values())} |" in lines
    assert f"100% may be claimed: {'yes' if claimed else 'no'}" in lines
    drift = f"The inventory header gives a total of {frozen}, but the inventory holds {len(items)} items."
    cause = (f"- The inventory holds {len(items)} items, not its frozen total {frozen}; "
             "python tests/harness/inventory_tools.py --check reports the drift.")
    assert (drift in summary) is (cause in lines) is (len(items) != frozen)


MISSING_TOTAL = object()


@pytest.mark.parametrize("value", [
    pytest.param(MISSING_TOTAL, id="missing"),
    pytest.param("684", id="string"),
    pytest.param(True, id="boolean"),
    pytest.param(-1, id="negative"),
])
def test_report_refuses_a_malformed_frozen_total_before_any_action(
    committed_state: report.State, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str],
    value: object,
) -> None:
    header = {key: item for key, item in committed_state.header.items() if key != "total"}
    if value is not MISSING_TOTAL:
        header["total"] = value
    named = r"tests/inventory/inventory\.yaml header\.total"
    with pytest.raises(report.ReportError, match=named):
        dataclasses.replace(committed_state, header=header).total
    inventory = {"header": header, "items": committed_state.items}
    monkeypatch.setattr(report._inventory, "load", lambda *args, **kwargs: inventory)
    _forbid_report_writes(monkeypatch)
    with pytest.raises(report.ReportError, match=named):
        report.build_state()
    capsys.readouterr()
    for argv in ([], ["--check"], ["--check-proof"]):
        assert report.main(argv) == 2
        captured = capsys.readouterr()
        assert captured.out == "" and re.match(rf"report\.py: error: {named}", captured.err)


def test_regenerate_refuses_an_inventory_whose_list_drifts_from_its_frozen_total(
    committed_state: report.State, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    frozen = committed_state.total
    inventory = {"header": {**committed_state.header, "total": frozen + 1}, "items": committed_state.items}
    monkeypatch.setattr(report._inventory, "load", lambda *args, **kwargs: inventory)
    _forbid_report_writes(monkeypatch)
    before = {path: path.read_bytes() for path in (report.MATRIX_FILE, report.REGISTER_FILE, report.REPORT_FILE)}
    assert report.main([]) == 2
    error = capsys.readouterr().err
    assert error.startswith("report.py: error: the reports are not regenerated, and nothing is written: ")
    assert (f"tests/inventory/inventory.yaml header.total is {frozen + 1} but the inventory holds {frozen} "
            "items; run python tests/harness/inventory_tools.py --check") in error
    assert {path: path.read_bytes() for path in before} == before


RECORD_PROBLEM_ROOT_PREFIX = "unit-record-problems-"
# The record the reproduction overwrites, and the text it writes over it.
UNREADABLE_RECORD = "TRN-CBTRN02C-negative-01"
INVALID_RECORD_YAML = "adapter: gnucobol-batch\nstatus: [passed\n  : : broken {\n"
REFUSED_RECORD_LINK = "is a symbolic link; result records are neither read nor written through it"


@pytest.fixture
def record_problem_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp or in the checkout,
    given by its real path, removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=RECORD_PROBLEM_ROOT_PREFIX, dir=carddemo_work_root)).resolve()
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def _copied_results(folder: Path) -> list[Path]:
    """``folder``, created, holding copies of the committed gnucobol-batch
    records; their paths in name order."""
    folder.mkdir(parents=True)
    for path in sorted(RESULTS_DIR.glob("*.yaml")):
        shutil.copyfile(path, folder / path.name)
    return sorted(folder.glob("*.yaml"))


@pytest.mark.parametrize("layout", ["plain", "linked record", "linked folder", "linked parent"])
def test_results_refused_link_names_the_link_load_does_not_read_through(
    record_problem_root: Path, layout: str
) -> None:
    reports = record_problem_root / "reports"
    real = (record_problem_root / "reports-real") if layout == "linked parent" else reports
    stored = real / ("results-real" if layout == "linked folder" else "results")
    record = _copied_results(stored)[0]
    folder = reports / "results"
    if layout == "linked parent":
        reports.symlink_to(real.name)
    if layout == "linked folder":
        folder.symlink_to(stored.name)
    if layout == "linked record":
        moved = record_problem_root / record.name
        record.rename(moved)
        record.symlink_to(moved)
    linked_folder = {"linked folder": folder, "linked parent": reports}.get(layout)
    linked = linked_folder or (record if layout == "linked record" else None)
    assert results.refused_link(results_dir=folder) == linked_folder
    assert results.refused_link(record.stem, results_dir=folder) == linked
    assert results.refused_link("NOT-A-SCENARIO", results_dir=folder) == linked_folder
    loaded = results.load(record.stem, results_dir=folder)
    assert (loaded is None) is (linked is not None)
    assert sorted(results.load_all(results_dir=folder)) == sorted(
        path.stem for path in stored.glob("*.yaml") if linked_folder is None and not path.is_symlink()
    )


def _unreadable_and_linked_records(root: Path) -> tuple[Path, dict[str, str] | None]:
    """A results folder of copies in which one record is not valid YAML,
    one has the status ``verified``, one a ``scenario_digest`` of 64 zeros
    (which YAML reads as the integer 0) and one is a symbolic link to a copy
    outside it; each of those scenario ids with the path its problem names."""
    folder = root / "reports" / "results"
    copies = _copied_results(folder)
    unreadable = folder / f"{UNREADABLE_RECORD}.yaml"
    passed = [path for path in copies
              if path != unreadable and re.search(r"^status: passed$", path.read_text(encoding="utf-8"), re.M)]
    status_path, digest_path, linked_path = passed[:3]
    unreadable.write_text(INVALID_RECORD_YAML, encoding="utf-8")
    text = status_path.read_text(encoding="utf-8")
    status_path.write_text(re.sub(r"^status: passed$", "status: verified", text, count=1, flags=re.M),
                           encoding="utf-8")
    text = digest_path.read_text(encoding="utf-8")
    digest_path.write_text(re.sub(r"^scenario_digest: [0-9a-f]{64}$", "scenario_digest: " + "0" * 64, text,
                                  count=1, flags=re.M), encoding="utf-8")
    moved = root / linked_path.name
    linked_path.rename(moved)
    linked_path.symlink_to(moved)
    return folder, {path.stem: path.as_posix() for path in (unreadable, status_path, digest_path, linked_path)}


def _linked_results_folder(root: Path) -> tuple[Path, dict[str, str] | None]:
    """A results folder that is a symbolic link to a folder of copies; no
    record is named, since none is read through the link."""
    _copied_results(root / "reports" / "results-real")
    folder = root / "reports" / "results"
    folder.symlink_to("results-real")
    return folder, None


@pytest.mark.parametrize("layout", [
    pytest.param(_unreadable_and_linked_records, id="unreadable, contract-breaking and linked records"),
    pytest.param(_linked_results_folder, id="linked results folder"),
])
def test_regenerate_refuses_unread_records_and_never_reports_them_as_not_run(
    record_problem_root: Path, monkeypatch: pytest.MonkeyPatch,
    layout: Callable[[Path], tuple[Path, dict[str, str] | None]],
) -> None:
    folder, named = layout(record_problem_root)
    monkeypatch.setattr(report._results, "load", functools.partial(results.load, results_dir=folder))
    monkeypatch.setattr(report._results, "refused_link", functools.partial(results.refused_link, results_dir=folder))
    built: list[report.State] = []
    original = report.build_state

    def build() -> report.State:
        built.append(original())
        return built[-1]

    monkeypatch.setattr(report, "build_state", build)
    _forbid_report_writes(monkeypatch)
    with pytest.raises(report.ReportError) as raised:
        report.regenerate()
    [state] = built
    refusal = str(raised.value)
    assert refusal.startswith("the reports are not regenerated, and nothing is written: ")
    if named is None:
        link = f"{folder.as_posix()} {REFUSED_RECORD_LINK}"
        capable = [scenario_id for scenario_id, scenario in state.scenarios.items()
                   if results.GNUCOBOL in scenarios.capable_adapters(scenario)]
        assert capable and state.unread_records == dict.fromkeys(capable, link)
        assert [problem for problem in state.problems if REFUSED_RECORD_LINK in problem] == [link]
        assert refusal.count(link) == 1
    else:
        assert sorted(state.unread_records) == sorted(named)
        for scenario_id, path in named.items():
            assert state.unread_records[scenario_id].startswith(path) and path in refusal
            assert f"{scenario_id}: gnucobol-batch record: {state.unread_records[scenario_id]}" in state.problems
    unread = sorted(state.unread_records)
    assert {state.statuses[scenario_id].status for scenario_id in unread} == {results.STATUS_BLOCKED}
    causes = report._projection_causes(state)
    assert not [cause for cause in causes if "not run in this session" in cause]
    [cause] = [cause for cause in causes if "have a result record that cannot be read" in cause]
    assert cause.startswith(f"{len(unread)} scenarios gnucobol-batch can run have a result record")
    assert ", ".join(unread[:10]) in cause


@pytest.mark.parametrize("action", [
    pytest.param(lambda: report._import_execution(CLOSURE_EXECUTION, UNIKIX_EVIDENCE_DIR / CLOSURE_EXECUTION),
                 id="import"),
    pytest.param(lambda: report._revalidate(), id="revalidation"),
])
def test_import_and_revalidation_refuse_what_regeneration_refuses_before_writing(
    committed_state: report.State, monkeypatch: pytest.MonkeyPatch, action: Callable[[], object]
) -> None:
    unread = f"tests/reports/results/{UNREADABLE_RECORD}.yaml: not valid YAML: at line 2, column 9"
    scenario_file = f"{UNREADABLE_RECORD}: scenario file cannot be read: tests/scenarios/unit/{UNREADABLE_RECORD}.yaml"
    shown = f"tests/reports/unikix/{BINDING_EXECUTION}/{BINDING_SCENARIO}/validation.yaml"
    validation = report._unread_validation(f"{shown} {report._ENTRY_LINK}", BINDING_EXECUTION)
    refused = dataclasses.replace(
        committed_state,
        header={**committed_state.header, "total": committed_state.total + 1},
        unread_scenarios={UNREADABLE_RECORD: scenario_file},
        unread_records={UNREADABLE_RECORD: unread},
        unread_validations={shown: validation},
    )
    monkeypatch.setattr(report, "build_state", lambda: refused)

    def unreached(*args: object, **kwargs: object) -> None:
        raise AssertionError("the refused action went on past its first state")

    for name in ("_scenario_dirs", "_evidence_executions", "_write_register", "regenerate"):
        monkeypatch.setattr(report, name, unreached)
    _forbid_report_writes(monkeypatch)
    with pytest.raises(report.ReportError) as raised:
        action()
    assert str(raised.value) == (
        "the reports are not regenerated, and nothing is written: tests/inventory/inventory.yaml header.total is "
        f"{committed_state.total + 1} but the inventory holds {len(committed_state.items)} items; run python "
        f"tests/harness/inventory_tools.py --check; {scenario_file}; {unread}; {validation}"
    )


# UniKix record binding: report.load_unikix_records over an evidence tree of
# the case's own (binding_tree), report.py's tests root and evidence folder
# pointed at it. The tree holds the evidence of one UniKix execution of
# BINDING_SCENARIO, which only unikix runs: a passed result.yaml written by
# EvidenceWriter, carrying the adapter's source identity of the committed zip
# and the scenario's current execution fingerprint, beside the validation.yaml
# the import's own writer judged from it (report._judge, report._validation,
# report._write_validation). Each case alters the tree as an operator or a
# forger could, and gives the problem that then keeps the record from
# counting, or None for the record left as judged. BINDING_EXECUTION and
# BINDING_SECOND_EXECUTION are invented execution ids of valid form.
BINDING_SCENARIO = "SEC-COSGN00C-normal-02"
BINDING_ITEM = "INV-SEC-011"  # the scenario's primary item, which no other scenario lists
BINDING_EXECUTION = "unikix-20261112T070000Z-0a0b0c0d"
BINDING_SECOND_EXECUTION = "unikix-20261112T080000Z-0a0b0c0d"
BINDING_RUN_DATE = datetime.date(2026, 11, 12)
BINDING_ENVIRONMENT = {
    "installation": {"UNIKIX": "/opt/software/TPE", "INSTEBM": "/opt/software/BPE/INSTEBM",
                     "VCOBOL": "/opt/software/COBOL", "KIXLICDIR": "/opt/unikix", "JAVA_HOME": "/usr/lib/jvm/java"},
    "deployment": {name: f"/srv/unikix/{name.lower()}" for name in report._DEPLOYMENT_FACTS},
    "versions": {key: "12.0.0" for key in report.UNIKIX_VERSION_KEYS},
}
BINDING_DOES_NOT_COUNT = "; the record does not count"
BINDING_CHANGED = "its result_sha256 is not the sha256 of the result.yaml beside it"
BINDING_REJECTED = "the evidence beside it fails the import's checks: "
BINDING_CASES = [
    pytest.param("as-judged", None, id="judged-and-bound"),
    pytest.param("planted-beside-blocked",
                 "its verdict passed judges an observed outcome, but the result.yaml beside it records blocked "
                 "(adapter-unavailable) under GAP-MIS-005, with no observed outcome",
                 id="passed-validation-planted-and-bound-beside-a-blocked-run"),
    pytest.param("copied-beside-blocked", BINDING_CHANGED, id="passed-validation-copied-beside-a-replaced-run"),
    pytest.param("zip-sha256", f"{BINDING_REJECTED}source identity: source_identity.zip_sha256 is not the sha256 of",
                 id="result-zip-sha256-changed-and-rebound"),
    pytest.param("unit-hash", f"{BINDING_REJECTED}source identity: source_identity unit ",
                 id="result-unit-hash-changed-and-rebound"),
    pytest.param("line-endings", BINDING_CHANGED, id="result-line-endings-changed"),
    pytest.param("fingerprint", "it disagrees with the result.yaml beside it on execution_fingerprint",
                 id="execution-fingerprint-disagrees"),
    pytest.param("identity", "it disagrees with the result.yaml beside it on source_identity",
                 id="source-identity-disagrees"),
    pytest.param("no-result", f"{BINDING_REJECTED}no result.yaml: the evidence is incomplete", id="result-removed"),
]


def _binding_screens(baseline: baselines.Baseline) -> list[dict]:
    """The screens of a run showing what ``baseline`` expects on
    BINDING_RUN_DATE: each matcher field holds a value its matcher accepts."""
    screens = []
    for step in baseline.expected["screens"]:
        screen = {}
        for name, value in step.items():
            if baselines.is_matcher(value):
                value = BINDING_RUN_DATE.strftime(value["format"]) if value["match"] == "run-date" else "10:00:00"
            screen[name] = value
        screens.append(screen)
    return screens


def _write_binding_evidence(tests_root: Path, status: str) -> Path:
    """The scenario directory EvidenceWriter writes under ``tests_root`` for
    a run of BINDING_SCENARIO under BINDING_EXECUTION: passed, with the
    screens its baseline expects, or blocked under GAP-MIS-005 with no
    observed outcome. The record carries the adapter's source identity of
    the committed zip and the scenario's current execution fingerprint."""
    scenario = scenarios.get(BINDING_SCENARIO)
    baseline = baselines.load(BINDING_SCENARIO)
    tests_root.mkdir(parents=True)
    adapter = UniKixAdapter(work_root=tests_root.parent, repo_root=Path(_TESTS_ROOT).parent)
    fields = {
        "baseline_version": baseline.version,
        "execution_fingerprint": results.execution_fingerprint(scenario, fixtures.build(scenario), results.UNIKIX),
        "source_identity": adapter.source_identity(scenario),
        "execution_id": BINDING_EXECUTION,
    }
    run = None
    if status == results.STATUS_PASSED:
        run = base.Result(return_code=None, datasets={}, screens=_binding_screens(baseline), messages=[])
        decoded = baselines.projection(baselines.decode_result(scenario, run), scenario, baseline)
        record = results.build_record(scenario, results.UNIKIX, status, decoded=decoded, **fields)
    else:
        record = results.build_record(scenario, results.UNIKIX, status, reason="adapter-unavailable",
                                      gap_id="GAP-MIS-005", **fields)
    capture = evidence.Capture(environment=copy.deepcopy(BINDING_ENVIRONMENT),
                               run_date={"first_step_screen_date": BINDING_RUN_DATE.isoformat()}, result=run)
    evidence.EvidenceWriter(tests_root, BINDING_EXECUTION).write(scenario, record, capture)
    return evidence.evidence_root(tests_root, BINDING_EXECUTION) / BINDING_SCENARIO


@pytest.fixture
def binding_tree(work_root_case: Path, monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    """The evidence tree of the binding cases, under ``<case>/tests``, with
    report.py's tests root and evidence folder pointed at it, and the
    validation.yaml the import's own writer judged and wrote for it."""
    tests_root = work_root_case / "tests"
    evidence_dir = tests_root / "reports" / "unikix"
    monkeypatch.setattr(report, "TESTS_ROOT", tests_root)
    monkeypatch.setattr(report, "UNIKIX_EVIDENCE_DIR", evidence_dir)
    scenario_dir = _write_binding_evidence(tests_root, results.STATUS_PASSED)
    found, why = report.read_evidence(BINDING_EXECUTION, scenario_dir, report.zip_identity())
    assert found is not None, why
    scenario, baseline = scenarios.get(BINDING_SCENARIO), baselines.load(BINDING_SCENARIO)
    status, reason, diff, projected = report._judge(found, scenario, baseline)
    assert (status, reason) == (results.STATUS_PASSED, None), diff
    judged = report._validation(found, scenario, baseline, status, reason, None, projected)
    report._write_validation(found, judged)
    return SimpleNamespace(root=work_root_case, evidence_dir=evidence_dir, scenario_dir=scenario_dir,
                           validation=scenario_dir / report.VALIDATION_FILE, judged=judged)


def _rebind(tree: SimpleNamespace) -> None:
    """Make the tree's validation.yaml bind the result.yaml beside it as it
    now stands, as a forger who recomputes the binding would."""
    document = load_yaml(tree.validation)
    document[report.VALIDATION_BINDING] = sha256_file(tree.scenario_dir / report.RESULT_FILE)
    tree.validation.write_text(dump_yaml(document), encoding="utf-8")


def _alter_binding_tree(tree: SimpleNamespace, alteration: str) -> None:
    """Apply one alteration of BINDING_CASES to the tree."""
    result = tree.scenario_dir / report.RESULT_FILE
    if alteration in ("planted-beside-blocked", "copied-beside-blocked"):
        blocked = _write_binding_evidence(tree.root / "blocked" / "tests", results.STATUS_BLOCKED)
        shutil.copyfile(blocked / report.RESULT_FILE, result)
        if alteration == "planted-beside-blocked":
            _rebind(tree)
    elif alteration in ("zip-sha256", "unit-hash"):
        raw = load_yaml(result)
        identity = raw["source_identity"]
        if alteration == "zip-sha256":
            identity["zip_sha256"] = "0" * 64
        else:
            identity["units"][sorted(identity["units"])[0]] = "f" * 64
        result.write_text(dump_yaml(raw), encoding="utf-8")
        _rebind(tree)
    elif alteration == "line-endings":
        result.write_bytes(result.read_bytes().replace(b"\n", b"\r\n"))
    elif alteration in ("fingerprint", "identity"):
        document = load_yaml(tree.validation)
        if alteration == "fingerprint":
            document["execution_fingerprint"] = "0" * 64
        else:
            document["source_identity"] = {**document["source_identity"], "commit": "0" * 40}
        tree.validation.write_text(dump_yaml(document), encoding="utf-8")
    elif alteration == "no-result":
        result.unlink()


@pytest.mark.parametrize(("alteration", "problem"), BINDING_CASES)
def test_a_validation_counts_only_beside_the_result_it_was_judged_from(
    binding_tree: SimpleNamespace, alteration: str, problem: str | None
) -> None:
    _alter_binding_tree(binding_tree, alteration)
    found, problems, unread = report.load_unikix_records(binding_tree.evidence_dir, report.zip_identity())
    assert unread == {}
    fields = {name: value for name, value in binding_tree.judged.items() if name != report.VALIDATION_BINDING}
    if problem is None:
        assert problems == []
        assert binding_tree.judged[report.VALIDATION_BINDING] == sha256_file(
            binding_tree.scenario_dir / report.RESULT_FILE)
        assert [(record.execution_id, record.scenario_id, record.record, record.path) for record in found] == [
            (BINDING_EXECUTION, BINDING_SCENARIO, fields, binding_tree.validation)]
        # Comparing executions reads the same record fields, without the binding.
        assert report._execution_records(BINDING_EXECUTION) == ({BINDING_SCENARIO: fields}, [])
        return
    assert found == []
    [named] = problems
    shown = report.display_path(binding_tree.validation)
    assert named.startswith(f"{shown}: ") and named.endswith(BINDING_DOES_NOT_COUNT), named
    assert problem in named, named


@pytest.mark.parametrize(("alteration", "counted"), [
    pytest.param("as-judged", True, id="judged-and-bound"),
    pytest.param("planted-beside-blocked", False, id="planted-beside-a-blocked-run"),
])
def test_the_status_rules_and_check_repeat_the_cross_check_on_every_read(
    binding_tree: SimpleNamespace, monkeypatch: pytest.MonkeyPatch, alteration: str, counted: bool
) -> None:
    _alter_binding_tree(binding_tree, alteration)
    monkeypatch.setattr(report, "load_unikix_records",
                        functools.partial(report.load_unikix_records, binding_tree.evidence_dir))
    state = report.build_state()
    status = state.statuses[BINDING_SCENARIO]
    shown = report.display_path(binding_tree.validation)
    named = [problem for problem in state.problems if problem.startswith(f"{shown}: ")]
    if counted:
        assert status.status == results.STATUS_PASSED and named == []
        assert [record["execution_id"] for record in status.records] == [BINDING_EXECUTION]
        assert state.item_status[BINDING_ITEM] == report.VERIFIED
        return
    assert status.status == results.STATUS_BLOCKED and status.counted == ()
    assert state.item_status[BINDING_ITEM] == report.BLOCKED
    assert len(named) == 1 and named[0].endswith(BINDING_DOES_NOT_COUNT), state.problems
    assert named[0] in report.check(state)


def test_judging_evidence_again_never_binds_a_changed_result(binding_tree: SimpleNamespace) -> None:
    _alter_binding_tree(binding_tree, "line-endings")
    found, why = report.read_evidence(BINDING_EXECUTION, binding_tree.scenario_dir, report.zip_identity())
    assert found is not None, why
    before = binding_tree.validation.read_bytes()
    for importing in (True, False):
        with pytest.raises(report.ReportError) as raised:
            report._validate_evidence(found, scenarios.get(BINDING_SCENARIO), None, None,
                                      report._CopyGaps({}, frozenset()), report.zip_identity(), importing=importing)
        assert str(raised.value).startswith(
            f"{report.display_path(binding_tree.validation)} was written from another result.yaml: its "
            f"result_sha256 is not the sha256 of {report.display_path(binding_tree.scenario_dir / 'result.yaml')} "
            f"({found.result_sha256}), which changed after that verdict; evidence is immutable, so nothing is "
            "judged or written")
        assert binding_tree.validation.read_bytes() == before


# Unread validation.yaml files: report.load_unikix_records over the binding
# tree once its validation.yaml is made unreadable in one way, which then is
# not read at all: unread, led by its path, ending in the way past it, and
# counted nowhere. Each case: how it is made unreadable, and the problem named.
UNREAD_VALIDATION_WAY_PAST = (
    f"; restore it, or remove it and import {BINDING_EXECUTION} again (python tests/harness/report.py "
    f"--import-unikix {BINDING_EXECUTION}), which writes it anew"
)
UNREAD_VALIDATION_CASES = [
    pytest.param("not-yaml", ": not valid YAML: ", id="not-valid-yaml"),
    pytest.param("too-large", ": holds more than ", id="larger-than-an-evidence-file-may-hold"),
    pytest.param("unbound", ": result_sha256: missing; a validation.yaml binds the sha256 of the result.yaml its "
                 "verdict was judged from", id="no-binding"),
    pytest.param("malformed-binding", ": result_sha256: 'ABC' is not a sha256 hex digest", id="malformed-binding"),
    pytest.param("contract", ": status: 'verified' is not one of ", id="record-contract-broken"),
    pytest.param("link", f" {report._ENTRY_LINK}", id="symbolic-link"),
]


def _make_validation_unread(tree: SimpleNamespace, how: str, monkeypatch: pytest.MonkeyPatch) -> None:
    """Make the tree's validation.yaml unreadable in the way ``how`` of
    UNREAD_VALIDATION_CASES names."""
    validation = tree.validation
    if how == "not-yaml":
        validation.write_text(INVALID_RECORD_YAML, encoding="utf-8")
    elif how == "too-large":
        monkeypatch.setattr(report, "EVIDENCE_YAML_MAX_BYTES", len(validation.read_bytes()) - 1)
    elif how == "link":
        moved = tree.root / "validation-copy.yaml"
        validation.rename(moved)
        validation.symlink_to(moved)
    else:
        document = load_yaml(validation)
        if how == "unbound":
            del document[report.VALIDATION_BINDING]
        elif how == "malformed-binding":
            document[report.VALIDATION_BINDING] = "ABC"
        else:
            document["status"] = "verified"
        validation.write_text(dump_yaml(document), encoding="utf-8")


@pytest.mark.parametrize(("how", "problem"), UNREAD_VALIDATION_CASES)
def test_a_validation_that_is_not_read_is_named_with_the_way_past_it(
    binding_tree: SimpleNamespace, monkeypatch: pytest.MonkeyPatch, how: str, problem: str
) -> None:
    _make_validation_unread(binding_tree, how, monkeypatch)
    found, problems, unread = report.load_unikix_records(binding_tree.evidence_dir, report.zip_identity())
    shown = report.display_path(binding_tree.validation)
    assert found == [] and list(unread) == [shown] and problems == [unread[shown]]
    assert unread[shown].startswith(shown) and problem in unread[shown], unread[shown]
    assert unread[shown].endswith(UNREAD_VALIDATION_WAY_PAST), unread[shown]


# Unread inputs on regeneration: python tests/harness/report.py over the
# committed tree, with two scenario files read from copies of the case's own
# (one ending in a python tag the safe loader refuses, so its command never
# runs, one giving a key twice), or with the binding tree's evidence holding
# two validation.yaml files that are not read (one without its binding, one a
# symbolic link in a second execution). The regeneration must exit 2, write
# nothing and name every file, and the projection causes must not count an
# unreadable scenario file as absent.
# Each copy: its scenario, the text appended to the committed file, and the
# problem the refusal names.
UNREADABLE_SCENARIO_COPIES = [
    ("DAT-READACCT-normal-01", 'qa_injected: !!python/object/apply:os.system ["true"]\n',
     "not valid YAML: could not determine a constructor for the tag"),
    ("DAT-READCUST-normal-01", "id: DAT-READCUST-normal-01\n", "found duplicate key 'id' at line "),
]
REFUSED_REGENERATION = "report.py: error: the reports are not regenerated, and nothing is written: "


def _refused_regeneration(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> tuple[
        report.State, str]:
    """The state ``python tests/harness/report.py`` builds and the error it
    prints, once it has exited 2 without writing anything."""
    built: list[report.State] = []
    original = report.build_state

    def build() -> report.State:
        built.append(original())
        return built[-1]

    monkeypatch.setattr(report, "build_state", build)
    _forbid_report_writes(monkeypatch)
    before = {path: path.read_bytes() for path in (
        report.MATRIX_FILE, report.REGISTER_FILE, report.REPORT_FILE, inventory_tools.INVENTORY_FILE)}
    capsys.readouterr()
    assert report.main([]) == 2
    captured = capsys.readouterr()
    assert captured.out == "" and captured.err.startswith(REFUSED_REGENERATION), captured.err
    assert {path: path.read_bytes() for path in before} == before
    [state] = built
    return state, captured.err


def test_regenerate_refuses_unreadable_scenario_files_and_never_reports_them_absent(
    work_root_case: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    get = scenarios.get
    copies: dict[str, Path] = {}
    for scenario_id, extra, _ in UNREADABLE_SCENARIO_COPIES:
        source = get(scenario_id).path
        copied = work_root_case / source.parent.name / source.name
        copied.parent.mkdir(exist_ok=True)
        copied.write_text(source.read_text(encoding="utf-8") + extra, encoding="utf-8")
        copies[scenario_id] = copied

    def reading_copies(scenario_id: str, missing_ok: bool = False) -> scenarios.Scenario | None:
        if scenario_id in copies:
            return scenarios.load_file(copies[scenario_id])
        return get(scenario_id, missing_ok)

    monkeypatch.setattr(scenarios, "get", reading_copies)
    state, error = _refused_regeneration(monkeypatch, capsys)
    assert sorted(state.unread_scenarios) == sorted(copies)
    for scenario_id, _, problem in UNREADABLE_SCENARIO_COPIES:
        unread = state.unread_scenarios[scenario_id]
        assert unread.startswith(f"{scenario_id}: scenario file cannot be read: "
                                 f"{report.display_path(copies[scenario_id])}: "), unread
        assert problem in unread and unread in error and unread in state.problems, unread
        assert scenario_id not in state.scenarios and scenario_id not in state.statuses
    causes = report._projection_causes(state)
    assert not [cause for cause in causes if "are absent" in cause], causes
    [cause] = [cause for cause in causes if "exist but cannot be read" in cause]
    assert cause == (f"{len(copies)} scenario files of the id manifest exist but cannot be read (python "
                     f"tests/harness/report.py --check names each problem): {', '.join(sorted(copies))}; the "
                     "items only they would list are not-covered.")


def test_regenerate_and_check_name_every_validation_that_is_not_read(
    binding_tree: SimpleNamespace, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _make_validation_unread(binding_tree, "unbound", monkeypatch)
    linked = binding_tree.evidence_dir / BINDING_SECOND_EXECUTION / BINDING_SCENARIO / report.VALIDATION_FILE
    linked.parent.mkdir(parents=True)
    linked.symlink_to(binding_tree.validation)
    monkeypatch.setattr(report, "load_unikix_records",
                        functools.partial(report.load_unikix_records, binding_tree.evidence_dir))
    state, error = _refused_regeneration(monkeypatch, capsys)
    unbound, link = report.display_path(binding_tree.validation), report.display_path(linked)
    assert sorted(state.unread_validations) == sorted([unbound, link])
    assert state.unread_validations[link] == report._unread_validation(f"{link} {report._ENTRY_LINK}",
                                                                       BINDING_SECOND_EXECUTION)
    assert state.unread_validations[unbound].startswith(f"{unbound}: result_sha256: missing")
    assert state.unread_validations[unbound].endswith(UNREAD_VALIDATION_WAY_PAST)
    findings = report.check(state)
    for problem in state.unread_validations.values():
        assert problem in error and problem in findings, problem
    assert state.statuses[BINDING_SCENARIO].counted == ()


# Evidence YAML read again after it was parsed: _observed_messages finds the
# line of each observed message in result.yaml, and _revalidate compares the
# bytes of a validation.yaml before and after it judges the evidence. Both
# read the file only as report._evidence_yaml_bytes does: never through a
# symbolic link swapped in, never from a FIFO, never more than one byte past
# EVIDENCE_YAML_MAX_BYTES. The result.yaml holds its message on line 5.
REREAD_MESSAGE = "A message the evidence observed on its fifth line"
REREAD_RESULT_LINES = (
    "adapter: unikix",
    "observed_messages:",
    "- field: ERRMSG",
    "  step: 1",
    f"  value: {REREAD_MESSAGE}",
    "scenario_id: SEC-COSGN00C-normal-01",
)
REREAD_MESSAGE_LINE = 5
# Each case: how the file is altered after it was parsed, the line ending it
# is written with, whether _validation_bytes then gives its bytes, and the
# line _observed_messages then cites.
REREAD_CASES = [
    pytest.param("as-parsed", "\n", True, REREAD_MESSAGE_LINE, id="as-parsed-lf"),
    pytest.param("as-parsed", "\r\n", True, REREAD_MESSAGE_LINE, id="as-parsed-crlf"),
    pytest.param("as-parsed", "\r", True, REREAD_MESSAGE_LINE, id="as-parsed-cr"),
    pytest.param("at-the-bound", "\n", True, REREAD_MESSAGE_LINE, id="exactly-as-large-as-an-evidence-file-may-be"),
    pytest.param("too-large", "\n", False, 1, id="grown-past-the-bound"),
    pytest.param("link", "\n", False, 1, id="symbolic-link-swapped-in"),
    pytest.param("fifo", "\n", False, 1, id="fifo-swapped-in"),
    pytest.param("removed", "\n", False, 1, id="removed"),
    pytest.param("not-utf-8", "\n", True, 1, id="no-longer-utf-8"),
]


def _reread_file(folder: Path, name: str, ending: str, how: str, monkeypatch: pytest.MonkeyPatch) -> Path:
    """``<folder>/<name>`` holding REREAD_RESULT_LINES ended by ``ending``,
    then altered as ``how`` of REREAD_CASES names. A symbolic link swapped in
    points at a regular copy of the same bytes, so reading through it would
    find every line."""
    path = folder / name
    path.write_bytes("".join(line + ending for line in REREAD_RESULT_LINES).encode("utf-8"))
    if how == "not-utf-8":
        path.write_bytes(path.read_bytes() + b"\xff")
    elif how == "at-the-bound":
        monkeypatch.setattr(report, "EVIDENCE_YAML_MAX_BYTES", len(path.read_bytes()))
    elif how == "too-large":
        monkeypatch.setattr(report, "EVIDENCE_YAML_MAX_BYTES", len(path.read_bytes()) - 1)
    elif how == "link":
        moved = folder / f"elsewhere-{name}"
        path.rename(moved)
        path.symlink_to(moved)
    elif how == "fifo":
        path.unlink()
        os.mkfifo(path)
    elif how == "removed":
        path.unlink()
    return path


@pytest.mark.parametrize(("how", "ending", "read", "line"), REREAD_CASES)
def test_observed_messages_read_the_result_again_only_within_the_evidence_bounds(
    work_root_case: Path, monkeypatch: pytest.MonkeyPatch, how: str, ending: str, read: bool, line: int
) -> None:
    source = work_root_case / "parsed.yaml"
    source.write_bytes("".join(text + ending for text in REREAD_RESULT_LINES).encode("utf-8"))
    raw = load_yaml(source, max_bytes=report.EVIDENCE_YAML_MAX_BYTES)
    path = _reread_file(work_root_case, report.RESULT_FILE, ending, how, monkeypatch)
    [message] = report._observed_messages(path, raw, BINDING_EXECUTION, "SEC-COSGN00C-normal-01")
    assert (message.text, message.field, message.step, message.line) == (REREAD_MESSAGE, "ERRMSG", 1, line)


@pytest.mark.parametrize(("how", "ending", "read", "line"), REREAD_CASES)
def test_validation_bytes_read_only_what_the_validation_loader_reads(
    work_root_case: Path, monkeypatch: pytest.MonkeyPatch, how: str, ending: str, read: bool, line: int
) -> None:
    written = "".join(text + ending for text in REREAD_RESULT_LINES).encode("utf-8")
    path = _reread_file(work_root_case, report.VALIDATION_FILE, ending, how, monkeypatch)
    expected = (written + b"\xff" if how == "not-utf-8" else written) if read else None
    assert report._validation_bytes(path) == expected


def test_check_compares_each_report_as_written_with_a_carriage_return_in_a_cell(
    work_root_case: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    stale_suffix = " is not what the current records give; run python tests/harness/report.py"
    state = report.build_state()
    gap_id = sorted(state.gaps.entries)[0]
    state.gaps.entries[gap_id]["unknown"] = "What the first line asks\rand what the second one adds"
    for name in ("MATRIX_FILE", "REGISTER_FILE", "REPORT_FILE"):
        monkeypatch.setattr(report, name, work_root_case / getattr(report, name).name)
    texts = report.render(state)
    assert "What the first line asks\rand what the second one adds" in texts[report.REGISTER_FILE]
    for path, text in texts.items():
        harness.write_text(path, text)  # as regenerate writes them, byte for byte
    # The report's Environment section describes the host, never the records.
    harness.write_text(report.REPORT_FILE, texts[report.REPORT_FILE] + "- another host\n")

    def stale() -> list[str]:
        return [finding for finding in report.check(state) if finding.endswith(stale_suffix)]

    assert stale() == []
    # The register as a newline-translating read would see it differs.
    report.REGISTER_FILE.write_bytes(report.REGISTER_FILE.read_bytes().replace(b"\r", b"\n"))
    assert stale() == [f"{report.display_path(report.REGISTER_FILE)}{stale_suffix}"]
    # A register that is no longer UTF-8 is named too, never a crash.
    report.REGISTER_FILE.write_bytes(texts[report.REGISTER_FILE].encode("utf-8") + b"\xff")
    assert stale() == [f"{report.display_path(report.REGISTER_FILE)}{stale_suffix}"]


# ==========================================================================
# Instruments: layouts, codec, fixtures, inventory, the id manifest, gaps,
# configuration, baseline bumps and the protected tree
# ==========================================================================

# The suite's starting commit, which the inventory header carries as
# protected_base.
STARTING_COMMIT = "93ebec71ff7b59a68cedd7c501afe496a8eaa68d"

# A command-line check gets the scenario deadline; each takes seconds.
CLI_TIMEOUT_S = 300

LISTCAT_FILE = "app/catlg/LISTCAT.txt"
COPYBOOK_DIR = "app/cpy"
_RECLN_RE = re.compile(r"RECLN\s*=?\s*(\d+)")
_MAXLRECL_RE = re.compile(r"MAXLRECL-+(\d+)")

# Copybook -> its record length, the RECLN its second line states, and the
# VSAM cluster whose DATA component stores it. None where the copybook states
# no RECLN (CSUSR01Y) or the dataset is sequential (DALYTRAN, CVTRA06Y).
RECORD_LENGTHS = [
    pytest.param("CVACT01Y", 300, 300, "ACCTDATA", id="CVACT01Y"),
    pytest.param("CVACT02Y", 150, 150, "CARDDATA", id="CVACT02Y"),
    pytest.param("CVCUS01Y", 500, 500, "CUSTDATA", id="CVCUS01Y"),
    pytest.param("CVTRA06Y", 350, 350, None, id="CVTRA06Y"),
    pytest.param("CVTRA05Y", 350, 350, "TRANSACT", id="CVTRA05Y"),
    pytest.param("CVACT03Y", 50, 50, "CARDXREF", id="CVACT03Y"),
    pytest.param("CVTRA01Y", 50, 50, "TCATBALF", id="CVTRA01Y"),
    pytest.param("CVTRA02Y", 50, 50, "DISCGRP", id="CVTRA02Y"),
    pytest.param("CVTRA03Y", 60, 60, "TRANTYPE", id="CVTRA03Y"),
    pytest.param("CVTRA04Y", 60, 60, "TRANCATG", id="CVTRA04Y"),
    pytest.param("CSUSR01Y", 80, None, "USRSEC", id="CSUSR01Y"),
    pytest.param("CUSTREC", 500, 500, "CUSTDATA", id="CUSTREC"),
]

# The 17 mapsets of app/bms, which are the @map values of formatted screens.
MAPSETS = (
    "COSGN00", "COMEN01", "COADM01", "COUSR00", "COUSR01", "COUSR02", "COUSR03",
    "COACTVW", "COACTUP", "COCRDLI", "COCRDSL", "COCRDUP", "COTRN00", "COTRN01",
    "COTRN02", "COBIL00", "CORPT00",
)


def _recln_comment(repo_root: Path, stem: str) -> int | None:
    """The RECLN the copybook's second line states, read from the copybook
    itself rather than through ``harness.layouts``."""
    lines = (repo_root / COPYBOOK_DIR / f"{stem}.cpy").read_text(encoding="latin-1").splitlines()
    match = _RECLN_RE.search(lines[1]) if len(lines) > 1 else None
    return int(match.group(1)) if match else None


def _listcat_maxlrecl(repo_root: Path, cluster: str) -> int:
    """MAXLRECL of the cluster's DATA component: the first MAXLRECL after the
    cluster's header, read from LISTCAT itself. A page-break header can stand
    between the two (the CARDXREF AIX's MAXLRECL follows one)."""
    text = (repo_root / LISTCAT_FILE).read_text(encoding="latin-1")
    header = f"CLUSTER ------- AWS.M2.CARDDEMO.{cluster}.VSAM.KSDS"
    start = text.find(header)
    assert start >= 0, f"{LISTCAT_FILE} has no {header!r}"
    assert text.find(header, start + 1) < 0, f"{LISTCAT_FILE} holds {header!r} twice"
    match = _MAXLRECL_RE.search(text, start + len(header))
    assert match is not None, f"{LISTCAT_FILE} has no MAXLRECL after {header!r}"
    return int(match.group(1))


@pytest.mark.parametrize(("stem", "length", "recln", "cluster"), RECORD_LENGTHS)
def test_record_lengths(
    carddemo_repo_root: Path, stem: str, length: int, recln: int | None, cluster: str | None
) -> None:
    layout = layouts.record(stem)
    assert layout["length"] == length
    assert _recln_comment(carddemo_repo_root, stem) == recln
    if recln is not None:
        assert layout["length"] == recln
    if cluster is not None:
        maxlrecl = _listcat_maxlrecl(carddemo_repo_root, cluster)
        assert layout["length"] == maxlrecl
        assert layout["listcat"]["cluster"] == f"AWS.M2.CARDDEMO.{cluster}.VSAM.KSDS"
        assert layout["listcat"]["maxlrecl"] == maxlrecl


def test_layouts_file_is_current(carddemo_repo_root: Path) -> None:
    committed = layouts.load()
    assert layouts.generate(carddemo_repo_root) == committed, (
        "tests/inventory/layouts.yaml is not what the sources produce: "
        "run python tests/harness/layouts.py --write"
    )
    screens = committed["screens"]
    assert sorted(screens) == sorted(MAPSETS)
    for mapset in MAPSETS:
        entry = screens[mapset]
        rows, cols = entry["size"]
        assert entry["fields"], f"{mapset} has no named field"
        assert entry["statics"], f"{mapset} has no static text to identify it by"
        for placed in entry["fields"]:
            assert placed["name"], f"{mapset}: a field without a name"
            assert 1 <= placed["row"] <= rows and 1 <= placed["col"] <= cols, (mapset, placed)
            assert isinstance(placed["length"], int) and placed["length"] >= 1, (mapset, placed)
        for static in entry["statics"]:
            assert 1 <= static["row"] <= rows and 1 <= static["col"] <= cols, (mapset, static)
            assert isinstance(static["text"], str) and static["text"], (mapset, static)


LAYOUTS_OUTPUT_ROOT_PREFIX = "unit-layouts-output-"
LAYOUTS_OUTPUT = Path("tests", "inventory", "layouts.yaml")
# The protected source a planted link aims at, copied into the case's own root.
LAYOUTS_PROTECTED_COPY = Path("app", "cbl", "CBACT01C.cbl")


@pytest.fixture
def layouts_output_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=LAYOUTS_OUTPUT_ROOT_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def _tree_state(root: Path) -> dict[str, tuple[str, bytes | str | None]]:
    """Every entry below ``root``, no link followed: a file's bytes, a link's
    target, a folder's presence."""
    state: dict[str, tuple[str, bytes | str | None]] = {}
    for folder, folders, files in os.walk(root):
        for name in folders + files:
            path = Path(folder, name)
            mode = path.lstat().st_mode
            shown = path.relative_to(root).as_posix()
            if stat.S_ISLNK(mode):
                state[shown] = ("link", os.readlink(path))
            elif stat.S_ISDIR(mode):
                state[shown] = ("folder", None)
            else:
                state[shown] = ("file", path.read_bytes())
    return state


def _output_links_into_app(root: Path, outside: Path) -> Path:
    (root / LAYOUTS_OUTPUT.parent).mkdir(parents=True)
    (root / LAYOUTS_OUTPUT).symlink_to(Path("..", "..", LAYOUTS_PROTECTED_COPY))
    return root / LAYOUTS_PROTECTED_COPY


def _output_links_outside(root: Path, outside: Path) -> Path:
    target = outside / "layouts.yaml"
    target.write_bytes(b"outside the repository\n")
    (root / LAYOUTS_OUTPUT.parent).mkdir(parents=True)
    (root / LAYOUTS_OUTPUT).symlink_to(target)
    return target


def _inventory_links_into_app(root: Path, outside: Path) -> Path:
    (root / "tests").mkdir()
    (root / LAYOUTS_OUTPUT.parent).symlink_to(Path("..", LAYOUTS_PROTECTED_COPY.parent))
    return root / LAYOUTS_PROTECTED_COPY


def _tests_links_outside(root: Path, outside: Path) -> Path:
    target = outside / LAYOUTS_OUTPUT
    target.parent.mkdir(parents=True)
    target.write_bytes(b"another tests folder\n")
    (root / "tests").symlink_to(outside / "tests")
    return target


def _output_is_a_folder(root: Path, outside: Path) -> Path:
    kept = root / LAYOUTS_OUTPUT / "kept.txt"
    kept.parent.mkdir(parents=True)
    kept.write_bytes(b"kept\n")
    return kept


LAYOUTS_OUTPUT_REFUSALS = [
    pytest.param(
        _output_links_into_app,
        "tests/inventory/layouts.yaml is a symbolic link; nothing is read or written through it",
        id="file-link-to-protected-source",
    ),
    pytest.param(
        _output_links_outside,
        "tests/inventory/layouts.yaml is a symbolic link; nothing is read or written through it",
        id="file-link-outside-repository",
    ),
    pytest.param(
        _inventory_links_into_app,
        "tests/inventory is a symbolic link; nothing is read or written through it",
        id="inventory-link-to-protected-folder",
    ),
    pytest.param(
        _tests_links_outside,
        "tests is a symbolic link; nothing is read or written through it",
        id="tests-link-outside-repository",
    ),
    pytest.param(
        _output_is_a_folder,
        "tests/inventory/layouts.yaml is not a regular file; nothing is read from or written to it",
        id="file-is-a-folder",
    ),
]


@pytest.mark.parametrize(("arrange", "refusal"), LAYOUTS_OUTPUT_REFUSALS)
def test_layouts_output_refuses_links(
    carddemo_repo_root: Path,
    layouts_output_root: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    arrange: Callable[[Path, Path], Path],
    refusal: str,
) -> None:
    root = layouts_output_root / "repository"
    outside = layouts_output_root / "outside"
    (root / LAYOUTS_PROTECTED_COPY).parent.mkdir(parents=True)
    shutil.copyfile(carddemo_repo_root / LAYOUTS_PROTECTED_COPY, root / LAYOUTS_PROTECTED_COPY)
    outside.mkdir()
    target = arrange(root, outside)
    original = target.read_bytes()
    before = _tree_state(layouts_output_root)

    for attempt in (
        lambda: layouts._write_layouts("version: 1\n", root),
        lambda: layouts._read_layouts(root),
        lambda: layouts._check_output(root),
    ):
        with pytest.raises(HarnessError, match=f"^{re.escape(refusal)}$"):
            attempt()

    # Both actions refuse before anything is generated: the case's root holds
    # no source, so generating would fail with another message.
    monkeypatch.setattr(layouts, "REPO_ROOT", root)
    monkeypatch.setattr(layouts, "LAYOUTS_FILE", root / LAYOUTS_OUTPUT)
    for action in ("--write", "--check"):
        assert layouts.main([action]) == 1
        captured = capsys.readouterr()
        assert (captured.out, captured.err) == ("", f"layouts: {refusal}\n"), action

    assert target.read_bytes() == original
    assert _tree_state(layouts_output_root) == before


def test_layouts_output_written_in_place(layouts_output_root: Path) -> None:
    root = layouts_output_root / "repository"
    root.mkdir()
    output = root / LAYOUTS_OUTPUT
    assert layouts._read_layouts(root) is None

    # A missing file is created, with the folders holding it.
    assert layouts._write_layouts("version: 1\n", root) is True
    assert output.read_bytes() == layouts._read_layouts(root) == b"version: 1\n"

    # A differing regular file of the same size is replaced by a new file, so
    # a hard link to the old one keeps the old bytes, and the replacement
    # keeps the old file's permission bits.
    output.chmod(0o640)
    old = layouts_output_root / "old-layouts.yaml"
    os.link(output, old)
    assert layouts._write_layouts("version: 2\n", root) is True
    assert output.read_bytes() == b"version: 2\n"
    assert old.read_bytes() == b"version: 1\n"
    assert output.stat().st_ino != old.stat().st_ino
    assert stat.S_IMODE(output.stat().st_mode) == 0o640

    # An identical file is left untouched.
    before = output.stat()
    assert layouts._write_layouts("version: 2\n", root) is False
    after = output.stat()
    assert (after.st_ino, after.st_mtime_ns) == (before.st_ino, before.st_mtime_ns)
    assert [entry.name for entry in output.parent.iterdir()] == ["layouts.yaml"]


# PIC S9(9)V99 with the sign overpunched on its last (units) digit.
SIGNED_9_V99 = {"type": "zoned-signed", "digits": 11, "scale": 2}

# The overpunch replaces the units digit ({, A-I are +0..9 and }, J-R are
# -0..9), so -504.70 is 0000005047} and +504.70 is 0000005047{; the texts
# 0000005040} and 0000005040{ are -504.00 and +504.00, not -504.70 and +504.70.
OVERPUNCH_EXAMPLES = [
    pytest.param("0000005047G", Decimal("504.77"), id="G-plus-7"),
    pytest.param("0000005047P", Decimal("-504.77"), id="P-minus-7"),
    pytest.param("0000005047}", Decimal("-504.70"), id="close-brace-minus-0"),
    pytest.param("0000005047{", Decimal("504.70"), id="open-brace-plus-0"),
]


@pytest.mark.parametrize(("text", "value"), OVERPUNCH_EXAMPLES)
@pytest.mark.parametrize("encoding", ENCODINGS)
def test_overpunch_examples(encoding: str, text: str, value: Decimal) -> None:
    raw = text.encode(codec.ENCODINGS[encoding])
    decoded = codec.decode(raw, SIGNED_9_V99, encoding)
    assert type(decoded) is Decimal
    # str keeps the scale, which Decimal equality ignores (504.7 == 504.70).
    assert str(decoded) == str(value)
    assert codec.encode(value, SIGNED_9_V99, encoding) == raw


def _zoned(digits: int, scale: int, signed: bool) -> dict:
    return {"type": "zoned-signed" if signed else "zoned", "digits": digits, "scale": scale}


def _packed(digits: int, scale: int, signed: bool) -> dict:
    return {"type": "comp-3", "digits": digits, "scale": scale, "signed": signed}


def _binary(digits: int, scale: int, signed: bool) -> dict:
    return {"type": "binary", "digits": digits, "scale": scale, "signed": signed}


POSITIVE_OVERPUNCH = "{ABCDEFGHI"
NEGATIVE_OVERPUNCH = "}JKLMNOPQR"

# (field, encoding, decoded value, field bytes). Each pair is canonical, as
# the codec writes it: a non-zero value behind every overpunch character
# (a negative zero decodes as zero and is written back with '{'), packed
# signs C and D (F when unsigned), big-endian two's complement binary.
ROUND_TRIPS = [
    pytest.param({"type": "alphanumeric", "length": 10}, "ascii", "CARD 01   ", b"CARD 01   ",
                 id="alphanumeric-trailing-spaces"),
    pytest.param({"type": "alphanumeric", "length": 4}, "ascii", "    ", b"    ", id="alphanumeric-blank"),
    pytest.param({"type": "alphanumeric", "length": 4}, "ascii", "A\x00\x00\x00", b"A\x00\x00\x00",
                 id="alphanumeric-nul-fill"),
    pytest.param({"type": "alphanumeric", "length": 6}, "cp037", "Ab 1{ ", bytes.fromhex("c18240f1c040"),
                 id="cp037-text"),
    pytest.param(_zoned(5, 0, False), "ascii", Decimal("12345"), b"12345", id="zoned"),
    pytest.param(_zoned(5, 2, False), "ascii", Decimal("123.45"), b"12345", id="zoned-scale-2"),
    pytest.param(_zoned(5, 2, False), "cp037", Decimal("0.07"), bytes.fromhex("f0f0f0f0f7"), id="zoned-cp037"),
    *(
        pytest.param(_zoned(5, 2, True), "ascii", Decimal(f"{sign}1.2{digit}"), f"0012{char}".encode(),
                     id=f"overpunch-{'plus' if sign == '' else 'minus'}-{digit}")
        for sign, alphabet in (("", POSITIVE_OVERPUNCH), ("-", NEGATIVE_OVERPUNCH))
        for digit, char in enumerate(alphabet)
    ),
    pytest.param(_zoned(5, 2, True), "cp037", Decimal("-1.25"), bytes.fromhex("f0f0f1f2d5"),
                 id="overpunch-cp037-minus"),
    pytest.param(_zoned(5, 2, True), "cp037", Decimal("1.25"), bytes.fromhex("f0f0f1f2c5"),
                 id="overpunch-cp037-plus"),
    pytest.param(_packed(5, 0, True), "ascii", Decimal("12345"), bytes.fromhex("12345c"), id="comp-3-plus"),
    pytest.param(_packed(5, 0, True), "ascii", Decimal("-12345"), bytes.fromhex("12345d"), id="comp-3-minus"),
    pytest.param(_packed(4, 0, True), "ascii", Decimal("1234"), bytes.fromhex("01234c"),
                 id="comp-3-even-digits"),
    pytest.param(_packed(5, 2, True), "ascii", Decimal("0.00"), bytes.fromhex("00000c"), id="comp-3-zero"),
    pytest.param(_packed(3, 0, False), "ascii", Decimal("123"), bytes.fromhex("123f"), id="comp-3-unsigned"),
    pytest.param(_packed(9, 2, True), "ascii", Decimal("-1234567.89"), bytes.fromhex("123456789d"),
                 id="comp-3-scale-2"),
    pytest.param(_packed(11, 2, True), "cp037", Decimal("12345.67"), bytes.fromhex("00001234567c"),
                 id="comp-3-cp037"),
    pytest.param(_binary(4, 0, True), "ascii", Decimal("-2"), bytes.fromhex("fffe"), id="halfword-minus"),
    pytest.param(_binary(4, 0, True), "ascii", Decimal("1234"), bytes.fromhex("04d2"), id="halfword-plus"),
    pytest.param(_binary(4, 0, False), "ascii", Decimal("9999"), bytes.fromhex("270f"), id="halfword-unsigned"),
    pytest.param(_binary(9, 0, True), "ascii", Decimal("-123456789"), bytes.fromhex("f8a432eb"),
                 id="fullword-minus"),
    pytest.param(_binary(9, 0, False), "ascii", Decimal("123456789"), bytes.fromhex("075bcd15"),
                 id="fullword-unsigned"),
    pytest.param(_binary(7, 2, True), "ascii", Decimal("-1.23"), bytes.fromhex("ffffff85"),
                 id="fullword-scale-2"),
    pytest.param(_binary(18, 0, True), "ascii", Decimal("-1"), bytes.fromhex("ff" * 8), id="doubleword-minus"),
]


def _shown(value: object) -> tuple[type, str]:
    """A decoded value with its type and, for a Decimal, its scale."""
    return type(value), str(value)


@pytest.mark.parametrize(("field", "encoding", "value", "raw"), ROUND_TRIPS)
def test_round_trips(field: dict, encoding: str, value: str | Decimal, raw: bytes) -> None:
    decoded = codec.decode(raw, field, encoding)
    assert _shown(decoded) == _shown(value)
    assert codec.encode(value, field, encoding) == raw
    assert codec.encode(decoded, field, encoding) == raw
    assert _shown(codec.decode(codec.encode(value, field, encoding), field, encoding)) == _shown(value)


@pytest.mark.parametrize("digit", range(10))
def test_cp037_sign_bytes(digit: int) -> None:
    assert POSITIVE_OVERPUNCH[digit].encode("cp037") == bytes([0xC0 + digit])
    assert NEGATIVE_OVERPUNCH[digit].encode("cp037") == bytes([0xD0 + digit])
    # PIC S9(3): +10d and -10d carry zone F on the leading digits and zone C
    # or D on the units digit.
    field = _zoned(3, 0, True)
    plus, minus = bytes([0xF1, 0xF0, 0xC0 + digit]), bytes([0xF1, 0xF0, 0xD0 + digit])
    assert codec.encode(Decimal(100 + digit), field, "cp037") == plus
    assert codec.encode(Decimal(-100 - digit), field, "cp037") == minus
    assert _shown(codec.decode(plus, field, "cp037")) == _shown(Decimal(100 + digit))
    assert _shown(codec.decode(minus, field, "cp037")) == _shown(Decimal(-100 - digit))


def _one_field_record(field: Mapping[str, object], length: int = 10) -> dict:
    """A record layout dict of ``length`` bytes whose one field, ``F`` at
    offset 0, is ``field`` (whose own keys win)."""
    return {"record": "R", "length": length, "fields": [{"name": "F", "path": "F", "offset": 0, **field}]}


SCALE_BOUNDS = "scale must lie between -33 and 38"

# (field descriptor, record layout, reason). A field descriptor is refused by
# encode and decode, and inside a one-field record by encode_record and
# decode_record; a layout alone is refused by the record calls. Each is a
# bound on a descriptor dimension, checked before any power of ten or record
# image is computed: without it scale 10**9 builds 10**1000000000, and a
# 10**12 length allocates that many bytes.
REFUSED_DESCRIPTORS = [
    pytest.param(_zoned(5, 10**9, False), None, SCALE_BOUNDS, id="scale-1e9"),
    pytest.param(_zoned(5, -(10**9), False), None, SCALE_BOUNDS, id="scale-minus-1e9"),
    pytest.param(_zoned(5, 39, False), None, SCALE_BOUNDS, id="scale-39"),
    # PIC 9(5)P(34): 39 digit positions.
    pytest.param(_zoned(5, -34, False), None, SCALE_BOUNDS, id="digits-5-scale-minus-34"),
    pytest.param({"type": "alphanumeric", "length": 10**12}, None, "length must be at most 32760 bytes",
                 id="text-length-1e12"),
    pytest.param(None, _one_field_record({"type": "alphanumeric", "length": 1}, 10**12),
                 "a record layout is at most 32760 bytes long", id="record-length-1e12"),
    pytest.param(None, _one_field_record({"type": "alphanumeric", "length": 1, "occurs": "3"}),
                 "occurs must be a non-negative integer", id="string-occurs"),
    pytest.param(None, _one_field_record({"type": "alphanumeric", "length": 1, "occurs": -1}),
                 "occurs must be a non-negative integer", id="negative-occurs"),
    pytest.param(None, _one_field_record({"type": "alphanumeric", "length": 1, "offset": -1}),
                 "offset must be a non-negative integer", id="negative-offset"),
    pytest.param(None, _one_field_record({"type": "alphanumeric", "length": 1, "occurs": 10**12}),
                 "lies outside the 10 bytes of R", id="occurs-beyond-the-record"),
    # A table of 10 one-byte items inside a one-byte group occurrence: unchecked,
    # nested tables would lay out a slot per pair of subscripts.
    pytest.param(None, {"record": "R", "length": 100, "fields": [
        {"name": "G", "path": "G", "type": "group", "length": 1, "offset": 0, "occurs": 5},
        {"name": "C", "path": "G.C", "type": "alphanumeric", "length": 1, "offset": 0, "occurs": 10},
    ]}, "lies outside its group G(1), columns 1-1", id="table-beyond-its-group"),
]


@pytest.mark.parametrize(("field", "layout", "reason"), REFUSED_DESCRIPTORS)
def test_out_of_bounds_descriptors_are_refused(
    field: dict | None, layout: dict | None, reason: str
) -> None:
    calls: list[tuple[Callable[..., object], object, dict]] = []
    if field is not None:
        calls += [(codec.encode, "1", field), (codec.decode, b"1", field)]
        layout = _one_field_record(field)
    calls += [(codec.encode_record, {}, layout), (codec.decode_record, b" " * 10, layout)]
    for function, data, target in calls:
        with pytest.raises(codec.CodecError) as caught:
            function(data, target)
        assert reason in caught.value.reason, (function.__name__, str(caught.value))


# Values whose exponent lies far outside every field: refused by their count
# of significant digits, never by computing the power of ten they imply. A
# digit string or int beyond 4300 digits also exceeds Python's limit on
# converting between int and text, which neither the check nor the error
# message may reach.
EXTREME_VALUES = [
    pytest.param(Decimal("1E+1000000000"), "does not fit 5 digits at scale 2", id="1E+1000000000"),
    pytest.param(Decimal("-1E+1000000000"), "does not fit 5 digits at scale 2", id="minus-1E+1000000000"),
    pytest.param(Decimal("1E-1000000000"), "has more decimal places than the field's scale 2",
                 id="1E-1000000000"),
    pytest.param("1" * 5000, "does not fit 5 digits at scale 2", id="5000-digit-text"),
    pytest.param(10**5000, "does not fit 5 digits at scale 2", id="5001-digit-int"),
    pytest.param("0." + "0" * 4999 + "1", "has more decimal places than the field's scale 2",
                 id="5000-decimal-places-text"),
]


@pytest.mark.parametrize(("value", "reason"), EXTREME_VALUES)
@pytest.mark.parametrize(
    "field",
    [
        pytest.param(_zoned(5, 2, True), id="zoned"),
        pytest.param(_packed(5, 2, True), id="comp-3"),
        pytest.param(_binary(5, 2, True), id="binary"),
    ],
)
def test_extreme_values_are_refused_by_digit_count(field: dict, value: Decimal | int | str, reason: str) -> None:
    with pytest.raises(codec.CodecError) as caught:
        codec.encode(value, field)
    assert caught.value.reason == reason


# (field, field bytes, decoded value, the same value written otherwise). The
# extreme scales of 5 digits, PIC P(33)9(5) and PIC 9(5)P(33), hold 38 digit
# positions and still round-trip; values with far-out exponents or many
# trailing zeros encode as the number they are.
EXTREME_VALID_VALUES = [
    pytest.param(_zoned(5, 38, False), b"12345", Decimal("1.2345E-34"), "0." + "0" * 33 + "12345",
                 id="zoned-scale-38"),
    pytest.param(_zoned(5, -33, False), b"12345", Decimal("12345" + "0" * 33), Decimal("1.2345E+37"),
                 id="zoned-scale-minus-33"),
    pytest.param(_packed(5, 38, True), bytes.fromhex("12345d"), Decimal("-1.2345E-34"),
                 Decimal("-1234500E-40"), id="comp-3-scale-38"),
    pytest.param(_zoned(5, 38, False), b"00000", Decimal("0E-38"), Decimal("0E+1000000000"),
                 id="zero-with-a-huge-exponent"),
    pytest.param(_zoned(5, 2, False), b"00100", Decimal("1.00"), "1." + "0" * 5000,
                 id="5000-trailing-zeros-text"),
]


@pytest.mark.parametrize(("field", "raw", "value", "written"), EXTREME_VALID_VALUES)
def test_extreme_valid_values_round_trip(field: dict, raw: bytes, value: Decimal, written: Decimal | str) -> None:
    decoded = codec.decode(raw, field)
    assert _shown(decoded) == _shown(value)
    assert codec.encode(decoded, field) == raw
    assert codec.encode(written, field) == raw


# The registered differences between app/data/ASCII and app/data/EBCDIC:
# (dataset, 1-based record) -> (registered columns, gap).
REGISTERED_MISMATCHES = {
    ("ACCTDATA", 49): ((103, 112), "GAP-DOC-012"),
    ("DISCGRP", 34): ((17, 22), "GAP-DOC-013"),
}

# The columns that actually differ in each registered record, established
# from the source bytes: the ASCII line right-padded to the record length
# against the cp037-decoded EBCDIC record. ACCTDATA record 49 is
# 'A000000000' against 'ZEROAPR   ' across all of ACCT-ADDR-ZIP; DISCGRP
# record 34 is '00' against '15' in columns 19-20 only, so its registered
# field (17-22, the whole DIS-INT-RATE) is deliberately broader.
ACTUAL_MISMATCH_COLUMNS = {
    ("ACCTDATA", 49): (103, 112),
    ("DISCGRP", 34): (19, 20),
}


def test_cross_check_registered_mismatches(carddemo_repo_root: Path) -> None:
    found = fixtures.cross_check(carddemo_repo_root)
    shown = [f"{m.dataset} record {m.record} columns {m.columns}: {m.describe()} ({m.gap_id})" for m in found]
    assert sorted((m.dataset, m.record) for m in found) == sorted(REGISTERED_MISMATCHES), shown
    for mismatch in found:
        (first, last), gap_id = REGISTERED_MISMATCHES[(mismatch.dataset, mismatch.record)]
        assert mismatch.gap_id == gap_id, shown
        # A mismatch spans exactly the columns that differ, inside the
        # registered field: DISCGRP record 34 differs in columns 19-20 of
        # DIS-INT-RATE.
        assert mismatch.columns == ACTUAL_MISMATCH_COLUMNS[(mismatch.dataset, mismatch.record)], shown
        assert first <= mismatch.columns[0] <= mismatch.columns[1] <= last, shown
    for (dataset, record), (columns, gap_id) in REGISTERED_MISMATCHES.items():
        registered = fixtures.spec(dataset).registered_mismatches
        assert [(entry.record, entry.columns, entry.gap_id) for entry in registered] == [
            (record, columns, gap_id)
        ]


CARDXREF_SOURCE = "app/data/ASCII/cardxref.txt"
FIXTURE_PADDING_ROOT_PREFIX = "unit-fixture-padding-"


@pytest.fixture
def fixture_padding_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=FIXTURE_PADDING_ROOT_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def test_fixture_padding(carddemo_repo_root: Path, fixture_padding_root: Path) -> None:
    lines = (carddemo_repo_root / CARDXREF_SOURCE).read_bytes().splitlines()
    assert lines and {len(line) for line in lines} == {36}
    assert fixtures.spec("CARDXREF").record_length == 50
    assert fixtures.repository_dataset("CARDXREF", carddemo_repo_root) == b"".join(
        line.ljust(50, b" ") for line in lines
    )

    # A line longer than the record is refused, never truncated: a copy of
    # the source whose first line is one byte too long, in a repository root
    # of the test's own.
    root = fixture_padding_root / "repository"
    source = root / CARDXREF_SOURCE
    source.parent.mkdir(parents=True)
    source.write_bytes(b"\n".join([lines[0].ljust(51, b"9"), *lines[1:]]) + b"\n")
    with pytest.raises(fixtures.FixtureError, match="line 1 is 51 bytes, longer than the 50-byte record"):
        fixtures.repository_dataset("CARDXREF", root)


USRSEC_SOURCE = "app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS"
DUSRSECJ_MEMBER = "app/jcl/DUSRSECJ.jcl"
# What a refusal states after the path it refuses.
FIXTURE_SOURCE_RULE = (
    "is not an approved fixture source; approved fixture sources are the files below app/data/"
)

# A copy of the committed CARDXREF entry with one key replaced: the key, its
# value, and the field the refusal names before the value.
FIXTURE_SOURCE_REFUSALS = {
    "source-git-metadata": ("source", ".git/config", "source"),
    "source-program": ("source", "app/cbl/CBACT01C.cbl", "source"),
    "source-hidden-data-file": ("source", "app/data/EBCDIC/.gitkeep", "source"),
    "source-fixture-definitions": ("source", "tests/fixtures/datasets.yaml", "source"),
    "records-counterpart-git-metadata": (
        "cross_check",
        {"source": ".git/HEAD", "encoding": "cp037"},
        "cross_check.source (records form)",
    ),
    "instream-counterpart-other-job": (
        "cross_check",
        {"source": "app/jcl/ACCTFILE.jcl", "encoding": "ascii", "form": "instream"},
        "cross_check.source (instream form)",
    ),
    "instream-alias-counterpart-other-job": (
        "cross_check",
        {"source": "app/jcl/ACCTFILE.jcl", "encoding": "jcl-instream"},
        "cross_check.source (instream form)",
    ),
    "records-counterpart-dusrsecj": (
        "cross_check",
        {"source": DUSRSECJ_MEMBER, "encoding": "ascii"},
        "cross_check.source (records form)",
    ),
}


def _fixture_document_with(name: str, key: str, value: object) -> object:
    """The committed datasets.yaml document with ``key`` of the entry
    ``name`` set to ``value``."""
    document = copy.deepcopy(load_yaml(FIXTURES_FILE))
    entry = next(entry for entry in document["datasets"] if entry["name"] == name)
    entry[key] = value
    return document


def test_committed_fixture_sources_are_approved() -> None:
    # The committed definitions name only approved fixture sources, so the
    # document parses, here as in fixtures.specs(), and USRSEC is still
    # cross-checked against DUSRSECJ's in-stream SYSUT1 lines.
    definitions = fixtures.specs()
    assert definitions
    assert fixtures._parse_document(load_yaml(FIXTURES_FILE), FIXTURES_FILE) == definitions
    assert definitions["USRSEC"].cross_check == fixtures.CrossCheck(DUSRSECJ_MEMBER, "ascii", "instream", (35, 44))


@pytest.mark.parametrize("case", sorted(FIXTURE_SOURCE_REFUSALS))
def test_fixture_source_definition_refused(case: str) -> None:
    # A dataset source or cross-check counterpart that is not an approved
    # fixture source fails the whole document when it is loaded, before any
    # file is read.
    key, value, field = FIXTURE_SOURCE_REFUSALS[case]
    named = value["source"] if isinstance(value, dict) else value
    expected = f"tests/fixtures/datasets.yaml CARDXREF.{field} {named!r} {FIXTURE_SOURCE_RULE}"
    with pytest.raises(fixtures.FixtureError, match=re.escape(expected)) as raised:
        fixtures._parse_document(_fixture_document_with("CARDXREF", key, value), FIXTURES_FILE)
    assert raised.value.dataset == "CARDXREF"


FIXTURE_SOURCE_ROOT_PREFIX = "unit-fixture-source-"
# The Git metadata of each case's repository root, and the text it holds on
# every line. It has more lines than DUSRSECJ's in-stream range 35-44, so a
# read of it as in-stream data would reach the comparison.
FIXTURE_SOURCE_METADATA = ".git/config"
FIXTURE_SOURCE_MARKER = "carddemo-fixture-source-marker"
FIXTURE_SOURCE_DECOY = "decoy"

# Each case's repository root: the files copied into it from the checkout
# (path in the root -> checkout path), its symbolic links (link -> target,
# both in the root), the fixtures function called and its logical dataset,
# and the purpose and repository-relative path the refusal names, or None
# where the link stays inside the data area and the dataset reads as in the
# checkout.
FIXTURE_SOURCE_LINKS = {
    "source-to-git-metadata": (
        {},
        {CARDXREF_SOURCE: FIXTURE_SOURCE_METADATA},
        "repository_dataset",
        "CARDXREF",
        ("source", CARDXREF_SOURCE),
    ),
    "records-counterpart-to-git-metadata": (
        {CARDXREF_SOURCE: CARDXREF_SOURCE},
        {"app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS": FIXTURE_SOURCE_METADATA},
        "verify",
        "CARDXREF",
        ("cross-check counterpart", "app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS"),
    ),
    "instream-counterpart-to-git-metadata": (
        {USRSEC_SOURCE: USRSEC_SOURCE},
        {DUSRSECJ_MEMBER: FIXTURE_SOURCE_METADATA},
        "verify",
        "USRSEC",
        ("cross-check counterpart", DUSRSECJ_MEMBER),
    ),
    "data-area-to-another-directory": (
        {f"{FIXTURE_SOURCE_DECOY}/ASCII/cardxref.txt": CARDXREF_SOURCE},
        {"app/data": FIXTURE_SOURCE_DECOY},
        "repository_dataset",
        "CARDXREF",
        ("source", CARDXREF_SOURCE),
    ),
    "source-to-another-data-file": (
        {"app/data/ASCII/cardxref-copy.txt": CARDXREF_SOURCE},
        {CARDXREF_SOURCE: "app/data/ASCII/cardxref-copy.txt"},
        "repository_dataset",
        "CARDXREF",
        None,
    ),
}


@pytest.fixture
def fixture_source_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh directory of this case's own inside the session work root
    (carddemo_work_root), never under pytest's basetemp, removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=FIXTURE_SOURCE_ROOT_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


@pytest.mark.parametrize("case", sorted(FIXTURE_SOURCE_LINKS))
def test_fixture_source_resolved_target(carddemo_repo_root: Path, fixture_source_root: Path, case: str) -> None:
    # The target a fixture path resolves to must itself be an approved
    # fixture source: a link to Git metadata, or a data area that is a link
    # elsewhere in the repository root, is refused unread, and the refusal
    # shows neither the target nor its text. A link from one data file to
    # another stays readable.
    copies, links, function, logical, refused = FIXTURE_SOURCE_LINKS[case]
    root = fixture_source_root / "repository"
    metadata = root / FIXTURE_SOURCE_METADATA
    metadata.parent.mkdir(parents=True)
    metadata.write_text(
        "[core]\n" + "".join(f"\t{FIXTURE_SOURCE_MARKER} = {number}\n" for number in range(1, 61)),
        encoding="ascii",
    )
    for copied, origin in copies.items():
        (root / copied).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(carddemo_repo_root / origin, root / copied)
    for link, target in links.items():
        (root / link).parent.mkdir(parents=True, exist_ok=True)
        (root / link).symlink_to(os.path.relpath(root / target, (root / link).parent))
    read = getattr(fixtures, function)
    if refused is None:
        assert read(logical, root) == read(logical, carddemo_repo_root)
        return
    with pytest.raises(fixtures.FixtureError) as raised:
        read(logical, root)
    message = str(raised.value)
    purpose, path = refused
    assert not isinstance(raised.value, fixtures.FixtureMismatch), message
    assert raised.value.dataset == logical
    assert (
        f"{logical}: its {purpose} {path} does not lead to an approved fixture source, so it is not read; "
        f"approved fixture sources are the files below app/data/"
    ) in message
    for hidden in (FIXTURE_SOURCE_MARKER, ".git", FIXTURE_SOURCE_DECOY, str(fixture_source_root)):
        assert hidden not in message, message


def _cli(
    repo_root: Path,
    script: str,
    *args: str,
    work_root: Path,
    extra_env: Mapping[str, str] | None = None,
    launcher: tuple[str, ...] = (),
) -> subprocess.CompletedProcess:
    """Run ``python tests/harness/<script> <args>`` from the repository root,
    writing no bytecode into the checkout.

    The child gets this process's environment plus ``extra_env``, with
    ``CARDDEMO_TEST_WORKDIR`` set to ``work_root`` (the session's
    carddemo_work_root, or a folder of a case's own inside it) whatever the
    variable held here, so a scratch folder the script makes stays inside the
    session work root even when the variable is unset or unusable and
    conftest made the root. ``extra_env`` cannot replace that variable or
    ``PYTHONDONTWRITEBYTECODE``. ``launcher`` goes between the interpreter and
    the script's path, so a ``-c`` bootstrap given there receives the path and
    ``args`` in its ``sys.argv`` and runs the script itself."""
    return subprocess.run(
        [sys.executable, *launcher, str(repo_root / "tests" / "harness" / script), *args],
        cwd=repo_root,
        capture_output=True,
        text=True,
        timeout=CLI_TIMEOUT_S,
        env={**os.environ, **(extra_env or {}), "PYTHONDONTWRITEBYTECODE": "1", WORK_ROOT_ENV: str(work_root)},
        check=False,
    )


def _assert_passes(completed: subprocess.CompletedProcess) -> None:
    assert completed.returncode == 0, (
        f"{' '.join(map(str, completed.args[1:]))} exited {completed.returncode}\n"
        f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
    )


def test_inventory_check(carddemo_repo_root: Path, carddemo_work_root: Path) -> None:
    _assert_passes(_cli(carddemo_repo_root, "inventory_tools.py", "--check", work_root=carddemo_work_root))


INVENTORY_ID_RE = re.compile(r"^INV-(SEC|MNU|USR|ACT|CRD|TRN|BIL|RPT|FIN|DAT|UTL|CFG|LAY|DTA|BAT)-\d{3}$")
SOURCE_ITEMS = {"program": 28, "job": 31, "config": 12, "transaction": 18, "map": 17, "layout": 28, "interface": 21}


def test_inventory_header() -> None:
    inventory = inventory_tools.load()
    header, items = inventory["header"], inventory["items"]
    assert header["total"] == len(items)
    assert header["protected_base"] == STARTING_COMMIT == PROTECTED_BASE
    assert header["source_items"] == SOURCE_ITEMS
    assert sum(header["source_items"].values()) == 155
    ids = [item.get("id") for item in items]
    assert [item for item in ids if not isinstance(item, str) or not INVENTORY_ID_RE.fullmatch(item)] == []
    assert sorted(item for item, count in Counter(ids).items() if count > 1) == []


# One edit of one source line per case, a resolved code line or a comment line
# of the program's own file, each taking away a fact that a never-shown
# message row (OVERWRITTEN_MESSAGES, UNSENT_MESSAGES, NEVER_SET_MESSAGES,
# UNPERFORMED_PARAGRAPHS) rests on: (program, area, path, line, old text, new
# text, part of the refusal).
NEVER_SHOWN_DRIFTS = {
    "unsigned-work-field-signed": (
        "CORPT00C", "RPT", "app/cbl/CORPT00C.cbl", 75, "PIC 9999", "PIC S9999", "anchor /",
    ),
    "tested-field-wider": (
        "CORPT00C", "RPT", "app/cpy-bms/CORPT00.CPY", 90, "X(4)", "X(5)",
        "no longer has the width of the value moved into it",
    ),
    "text-moved-under-a-test-that-holds": (
        "CORPT00C", "RPT", "app/cbl/CORPT00C.cbl", 340, "'Start Date - Not a valid Day...'",
        "'Start Date - Not a valid Year...'", "must be moved only at app/cbl/CORPT00C.cbl:347-349",
    ),
    "tested-field-written-before-the-test": (
        "CORPT00C", "RPT", "app/cbl/CORPT00C.cbl", 334, "SDTMML", "SDTYYYYO", "anchor /",
    ),
    "condition-set-elsewhere": (
        "COCRDLIC", "CRD", "app/cbl/COCRDLIC.cbl", 311, "WS-ERROR-MSG-OFF", "WS-EXIT-MESSAGE",
        "WS-EXIT-MESSAGE must be set only at app/cbl/COCRDLIC.cbl:396-405",
    ),
    "xctl-passes-another-commarea": (
        "COCRDLIC", "CRD", "app/cbl/COCRDLIC.cbl", 404, "CARDDEMO-COMMAREA", "WS-COMMAREA", "anchor /",
    ),
    "tested-condition-set-in-code": (
        "COACTVWC", "ACT", "app/cbl/COACTVWC.cbl", 788, "FOUND-ACCT-IN-MASTER", "DID-NOT-FIND-ACCT-IN-ACCTDAT",
        "DID-NOT-FIND-ACCT-IN-ACCTDAT must be set nowhere, its only SET being commented out at "
        "app/cbl/COACTVWC.cbl:792, but is set at app/cbl/COACTVWC.cbl:788",
    ),
    "test-of-the-never-set-condition-removed": (
        "COACTVWC", "ACT", "app/cbl/COACTVWC.cbl", 704, "IF DID-NOT-FIND-ACCT-IN-ACCTDAT", "IF FLG-ACCTFILTER-NOT-OK",
        r"IF\s+DID-NOT-FIND-ACCT-IN-ACCTDAT\s+GO\s+TO\s+9000-READ-ACCT-EXIT\b/ not found",
    ),
    "commented-out-set-changed": (
        "COACTVWC", "ACT", "app/cbl/COACTVWC.cbl", 792, "DID-NOT-FIND-ACCT-IN-ACCTDAT",
        "DID-NOT-FIND-ACCT-IN-CARDXREF", r"no comment line matches /\bSET\s+DID-NOT-FIND-ACCT-IN-ACCTDAT",
    ),
    "commented-out-set-outside-the-not-found-branch": (
        "COACTVWC", "ACT", "app/cbl/COACTVWC.cbl", 696, "IF DID-NOT-FIND-ACCT-IN-CARDXREF",
        "SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE",
        "the commented-out SET of DID-NOT-FIND-ACCT-IN-ACCTDAT at line 696 must lie in the NOTFND branch at "
        "app/cbl/COACTVWC.cbl:789-807",
    ),
    "unperformed-paragraph-in-a-thru-range": (
        "COCRDSLC", "CRD", "app/cbl/COCRDSLC.cbl", 345, "9000-READ-DATA-EXIT", "9150-GETCARD-BYACCT-EXIT",
        "no statement may perform or go to 9150-GETCARD-BYACCT, but the PERFORM at app/cbl/COCRDSLC.cbl:344 "
        "reaches it",
    ),
    "unperformed-paragraph-named-by-a-go-to": (
        "COCRDSLC", "CRD", "app/cbl/COCRDSLC.cbl", 674, "2210-EDIT-ACCOUNT-EXIT", "9150-GETCARD-BYACCT",
        "no statement may perform or go to 9150-GETCARD-BYACCT, but the GO TO at app/cbl/COCRDSLC.cbl:674 "
        "reaches it",
    ),
    "paragraph-before-the-unperformed-one-named-by-a-go-to": (
        "COCRDSLC", "CRD", "app/cbl/COCRDSLC.cbl", 715, "2220-EDIT-CARD-EXIT", "9100-GETCARD-BYACCTCARD-EXIT",
        "the GO TO at app/cbl/COCRDSLC.cbl:715 enters 9100-GETCARD-BYACCTCARD-EXIT, from which control runs on "
        "into 9150-GETCARD-BYACCT",
    ),
    "go-to-outside-any-perform-into-a-range-that-runs-on-into-the-unperformed-one": (
        "COCRDSLC", "CRD", "app/cbl/COCRDSLC.cbl", 348, "COMMON-RETURN", "9100-GETCARD-BYACCTCARD",
        "the GO TO at app/cbl/COCRDSLC.cbl:348 enters 9100-GETCARD-BYACCTCARD, from which control runs on into "
        "9150-GETCARD-BYACCT",
    ),
    "return-that-ends-the-main-run-on-no-longer-ends-it": (
        "COCRDSLC", "CRD", "app/cbl/COCRDSLC.cbl", 402, "EXEC CICS RETURN", "EXEC CICS SYNCPOINT",
        "the statements before the first paragraph run on into 0000-MAIN, from which control runs on into "
        "9150-GETCARD-BYACCT",
    ),
}


@pytest.mark.parametrize("case", sorted(NEVER_SHOWN_DRIFTS))
def test_inventory_never_shown_message_rows_refuse_source_drift(carddemo_repo_root: Path, case: str) -> None:
    """A message row that says its text never reaches the screen stops the
    extraction, and with it ``inventory_tools.py --check``, once the source
    no longer shows why: the intact program yields a never-reaches
    description, and the same program with the case's one-line edit raises
    InventoryError naming the broken fact."""
    name, area, path, line, old, new, refusal = NEVER_SHOWN_DRIFTS[case]
    source_file = carddemo_repo_root / "app" / "cbl" / f"{name}.cbl"
    lines = inventory_tools.resolve_copies(source_file, carddemo_repo_root)
    comments = inventory_tools.comment_lines(source_file, carddemo_repo_root)

    def edit(sources: list[inventory_tools.SourceLine]) -> list[inventory_tools.SourceLine]:
        return [
            dataclasses.replace(source, text=source.text.replace(old, new))
            if (source.path, source.line) == (path, line) else source
            for source in sources
        ]

    edited, edited_comments = edit(lines), edit(comments)
    assert (edited, edited_comments) != (lines, comments), f"{path}:{line} does not hold {old!r}"
    program = f"app/cbl/{name}.cbl"
    intact = inventory_tools.online_items(area, inventory_tools.CobolProgram(name, program, lines, comments))
    assert any("never reaches" in draft["description"] for draft in intact)
    with pytest.raises(inventory_tools.InventoryError, match=re.escape(refusal)):
        inventory_tools.online_items(area, inventory_tools.CobolProgram(name, program, edited, edited_comments))



MANIFEST_SIZES = {"unit": 94, "integration": 40, "e2e": 467}
# Observed baselines of the starting state: the 71 unit scenarios without a
# post-open fault and the 29 integration scenarios both adapters run. An
# imported UniKix run only adds to them.
OBSERVED_AT_START = 100
# The scenarios gnucobol-batch can run: each gets a result record once the
# suite has run in full.
GNUCOBOL_RUNNABLE = 123


def _yaml_stems(folder: Path) -> set[str]:
    return {path.stem for path in folder.glob("*.yaml")} if folder.is_dir() else set()


def _listed(label: str, ids: set[str]) -> list[str]:
    return [f"{label} ({len(ids)}): {', '.join(sorted(ids)[:20])}"] if ids else []


def test_id_manifest() -> None:
    assert {level: len(scenarios.MANIFEST[level]) for level in scenarios.LEVELS} == MANIFEST_SIZES
    assert len(scenarios.ALL_IDS) == sum(MANIFEST_SIZES.values()) == 601
    problems = list(scenarios.check_manifest(TESTS_ROOT))

    for level in scenarios.LEVELS:
        stems = _yaml_stems(SCENARIOS_DIR / level)
        problems += _listed(f"scenarios/{level}: ids outside its manifest level", stems - scenarios.MANIFEST[level])
        problems += _listed(f"scenarios/{level}: manifest ids without a file", scenarios.MANIFEST[level] - stems)

    folders = {path.name for path in BASELINES_DIR.iterdir() if path.is_dir()} if BASELINES_DIR.is_dir() else set()
    problems += _listed("baselines: directories outside the manifest", folders - scenarios.ALL_IDS)
    problems += _listed("baselines: manifest ids without a directory", scenarios.ALL_IDS - folders)
    observed = 0
    for scenario_id in sorted(folders & scenarios.ALL_IDS):
        folder = BASELINES_DIR / scenario_id
        lacking = [name for name in (baselines.EXPECTED_FILE, baselines.MANIFEST_FILE) if not (folder / name).is_file()]
        if lacking:
            problems.append(f"baselines/{scenario_id}: lacks {', '.join(lacking)}")
            continue
        manifest = load_yaml(folder / baselines.MANIFEST_FILE)
        platform = manifest.get("capture_platform") if isinstance(manifest, dict) else None
        raw = sorted((folder / baselines.DATASETS_DIR).glob(f"*{baselines.RAW_SUFFIX}"))
        if platform == baselines.DERIVED:
            if raw:
                problems.append(f"baselines/{scenario_id}: a derived baseline holds {raw[0].name}")
        else:
            observed += 1
    if observed < OBSERVED_AT_START:
        problems.append(f"baselines: {observed} observed baselines, fewer than the {OBSERVED_AT_START} of the start")

    runnable = {s.id for s in scenarios.load_all() if "gnucobol-batch" in scenarios.capable_adapters(s)}
    if len(runnable) != GNUCOBOL_RUNNABLE:
        problems.append(f"gnucobol-batch can run {len(runnable)} scenarios, not {GNUCOBOL_RUNNABLE}")
    recorded = _yaml_stems(RESULTS_DIR)
    problems += _listed("reports/results: records outside the manifest", recorded - scenarios.ALL_IDS)
    problems += _listed(
        "reports/results: records of scenarios gnucobol-batch cannot run", (recorded & scenarios.ALL_IDS) - runnable
    )
    problems += _listed("reports/results: runnable ids without a record", runnable - recorded)
    assert problems == [], "\n".join(problems)


# A scenario gnucobol-batch can run, so the manifest check requires its record.
RECORDED_ID = "UTL-CSUTLDTC-normal-01"


@pytest.mark.parametrize("change", ["removed", "linked", "no results folder"])
def test_id_manifest_reports_runnable_ids_without_a_record(work_root_case: Path, change: str) -> None:
    """``check_manifest`` on a copy of ``tests/scenarios`` and
    ``tests/reports/results`` in the case's work root folder: the intact copy
    holds a record for every id gnucobol-batch can run; removing
    RECORDED_ID's record, or replacing it by a symbolic link, leaves that id
    without one, and removing the results folder leaves every runnable id
    without one. The copy has no baselines, so only the reports problems are
    compared."""
    shutil.copytree(SCENARIOS_DIR, work_root_case / "scenarios", symlinks=True)
    folder = work_root_case / "reports" / "results"
    shutil.copytree(RESULTS_DIR, folder, symlinks=True)

    def report_problems() -> list[str]:
        return [problem for problem in scenarios.check_manifest(work_root_case) if problem.startswith("reports")]

    assert report_problems() == []
    record = folder / f"{RECORDED_ID}.yaml"
    missing = [RECORDED_ID]
    expected = []
    if change == "removed":
        record.unlink()
    elif change == "linked":
        record.symlink_to(record.rename(work_root_case / record.name))
        expected.append(f"reports/results/{record.name}: a symbolic link, not a result record <id>.yaml")
    else:
        missing = sorted(_yaml_stems(folder))
        shutil.rmtree(folder)
    expected.append(
        f"reports/results: {len(missing)} result record(s) missing for id(s) gnucobol-batch can run: "
        + ", ".join(missing)
    )
    assert report_problems() == expected


def _scenario_files() -> list:
    """One parameter per scenario file on disk, so a file outside the manifest
    is checked too; ``validate`` refuses it."""
    return [
        pytest.param(level, path.name, id=path.stem)
        for level in scenarios.LEVELS
        for path in sorted((SCENARIOS_DIR / level).glob("*.yaml"))
    ]


# The parameter is not named `scenario`: conftest deselects a test
# parametrized over `scenario` that lacks the selected adapter's marker.
@pytest.mark.parametrize(("level", "file_name"), _scenario_files())
def test_scenario_schema(level: str, file_name: str) -> None:
    problems = scenarios.validate(scenarios.load_file(SCENARIOS_DIR / level / file_name, level))
    assert problems == [], "\n".join(problems)


FIXED_SCENARIO_IDS = (
    "SEC-COSGN00C-normal-01", "SEC-COSGN00C-normal-02",
    "SEC-COSGN00C-negative-01", "SEC-COSGN00C-negative-02", "SEC-COSGN00C-negative-03",
    "SEC-COSGN00C-negative-04", "SEC-COSGN00C-negative-05",
    "SEC-COSGN00C-alternate-01", "SEC-COSGN00C-recovery-01",
    "MNU-COADM01C-alternate-02",
    "USR-COUSR01C-normal-01", "USR-COUSR00C-boundary-01", "USR-COUSR00C-boundary-02",
    "ACT-COACTVWC-recovery-01", "ACT-COACTUPC-alternate-02",
    "CRD-COCRDUPC-alternate-02", "CRD-COCRDLIC-boundary-01", "CRD-COCRDLIC-boundary-02",
    "TRN-COTRN02C-normal-01", "TRN-COTRN02C-negative-03",
    "TRN-COTRN00C-boundary-01", "TRN-COTRN00C-boundary-02",
    "BIL-COBIL00C-normal-01",
    "RPT-CORPT00C-normal-01", "RPT-CORPT00C-normal-02", "RPT-CORPT00C-normal-03",
    "CFG-FCT-normal-01",
    "BAT-POSTINT-normal-01", "BAT-CHAIN-normal-01",
)


def test_fixed_ids_exist() -> None:
    level_of = {item: level for level, ids in scenarios.MANIFEST.items() for item in ids}
    outside = [item for item in FIXED_SCENARIO_IDS if item not in level_of]
    assert outside == [], f"fixed ids outside the manifest: {outside}"
    missing = [
        f"scenarios/{level_of[item]}/{item}.yaml"
        for item in FIXED_SCENARIO_IDS
        if not (SCENARIOS_DIR / level_of[item] / f"{item}.yaml").is_file()
    ]
    assert missing == [], f"fixed ids without their file: {missing}"
    assert set(FIXED_SCENARIO_IDS) <= scenarios.FIXED_IDS


def test_chain_marker() -> None:
    chained = sorted(s.id for s in scenarios.load_all() if "chain" in scenarios.marker_names(s))
    assert chained == ["BAT-CHAIN-normal-01", "BAT-POSTINT-normal-01"]


GAPS_FILE = INVENTORY_DIR / "gaps.yaml"
GAP_ID_RE = re.compile(r"GAP-(DOC|DEF|STB|MIS|INF|UNC)-\d{3}")
GAP_CATEGORY = {
    "UNC": "uncertain-purpose",
    "MIS": "missing-input",
    "INF": "inferred-not-observed",
    "STB": "stub-limitation",
    "DOC": "doc-conflict",
    "DEF": "suspected-defect",
}
GAP_STATUSES = ("open", "closed")
SEAM_GAPS = tuple(f"GAP-STB-{number:03d}" for number in range(1, 12))


def _gap_entries() -> list[dict]:
    document = load_yaml(GAPS_FILE)
    assert isinstance(document, dict) and isinstance(document.get("gaps"), list), (
        f"{GAPS_FILE} must hold a 'gaps' list"
    )
    entries = document["gaps"]
    assert all(isinstance(entry, dict) for entry in entries), f"{GAPS_FILE}: every entry must be a mapping"
    return entries


def _cited_gap_ids() -> dict[str, set[str]]:
    """Gap id -> where it is cited: each scenario's and inventory item's
    ``gap_ids``, the fixture definitions, and every adapter and harness
    source, which raise ``AdapterUnavailable`` and ``MissingPrerequisite``
    with them."""
    cited: dict[str, set[str]] = {}
    for scenario in scenarios.load_all():
        for gap_id in scenario.gap_ids:
            cited.setdefault(str(gap_id), set()).add(f"scenario {scenario.id}")
    for item in inventory_tools.load()["items"]:
        for gap_id in item.get("gap_ids") or ():
            cited.setdefault(str(gap_id), set()).add(f"inventory item {item.get('id')}")
    sources = [
        *sorted(ADAPTERS_DIR.rglob("*.py")),
        *sorted(ADAPTERS_DIR.rglob("*.yaml")),
        *sorted((TESTS_ROOT / "harness").glob("*.py")),
        FIXTURES_FILE,
    ]
    for path in sources:
        if "__pycache__" in path.parts:
            continue
        for match in GAP_ID_RE.finditer(path.read_text(encoding="utf-8")):
            cited.setdefault(match.group(), set()).add(path.relative_to(TESTS_ROOT).as_posix())
    return cited


def test_open_gap_gate_needs_an_open_entry_that_affects_the_item() -> None:
    # An item that is not verified is covered only by an open entry whose
    # resolved affected items, the ids gap-register.csv renders, include it.
    # An open gap a listing scenario merely cites, which build_state adds to
    # the item's gap_ids, does not cover it.
    state = report.build_state()
    assert report._uncovered_items(state) == []
    # The open gap affects the first item alone; the second, not verified,
    # carries the gap as a scenario citation would; the third is verified.
    gap_id = next(iter(state.gaps.entries))
    affected, citing, verified = state.items[:3]
    gated = dataclasses.replace(
        state,
        items=[
            {**affected, "status": report.BLOCKED, "gap_ids": [gap_id]},
            {**citing, "status": report.BLOCKED, "gap_ids": [gap_id]},
            {**verified, "status": report.VERIFIED, "gap_ids": []},
        ],
        gaps=dataclasses.replace(state.gaps, entries={gap_id: {**state.gaps.entries[gap_id], "status": "open"}}),
        affected={gap_id: [affected["id"]]},
    )
    assert report._uncovered_items(gated) == [f"{citing['id']} (open, not affecting the item: {gap_id})"]


def test_gap_ids_exist() -> None:
    entries = _gap_entries()
    ids = [entry.get("gap_id") for entry in entries]
    problems = [f"{gap_id} is defined {count} times" for gap_id, count in Counter(ids).items() if count > 1]
    for position, entry in enumerate(entries):
        gap_id = entry.get("gap_id")
        match = GAP_ID_RE.fullmatch(gap_id) if isinstance(gap_id, str) else None
        if match is None:
            problems.append(f"entry {position}: {gap_id!r} is not a gap id GAP-<CAT>-<nnn>")
            continue
        category = entry.get("category")
        if category != GAP_CATEGORY[match.group(1)]:
            problems.append(f"{gap_id}: category {category!r}, but its prefix names {GAP_CATEGORY[match.group(1)]}")
        if entry.get("status") not in GAP_STATUSES:
            problems.append(f"{gap_id}: status {entry.get('status')!r} is not open or closed")
    known = set(ids)
    for gap_id, where in sorted(_cited_gap_ids().items()):
        if gap_id not in known:
            problems.append(f"{gap_id} is cited by {', '.join(sorted(where)[:5])} but is not in gaps.yaml")
    assert problems == [], "\n".join(problems)


def test_seam_rule() -> None:
    entries = {entry.get("gap_id"): entry for entry in _gap_entries()}
    assert sorted(gap_id for _, gap_id in report.SEAMS) == list(SEAM_GAPS)
    for gap_id in SEAM_GAPS:
        assert gap_id in entries, f"the seam entry {gap_id} is missing from gaps.yaml"
        entry = entries[gap_id]
        assert entry.get("category") == "stub-limitation", (gap_id, entry.get("category"))
        if entry.get("status") == "closed":
            evidence = entry.get("resolving_evidence")
            assert isinstance(evidence, dict), f"{gap_id} is closed without resolving evidence"
            folder = UNIKIX_EVIDENCE_DIR / str(evidence.get("execution_id"))
            assert (folder / str(evidence.get("scenario_id")) / report.VALIDATION_FILE).is_file(), (
                f"{gap_id} is closed on {evidence}, whose validation record is not under tests/reports/unikix/"
            )
    # Whether a closing record is current, passed and eligible, and agrees
    # with the gnucobol-batch outcome, is the seam rule of harness/report.py.
    assert report.check_seams() == []


SEAM_CITATIONS = [
    # Every gnucobol-batch job seeds the emulated catalog and resolves its
    # DDs by DSN through it.
    pytest.param("DAT-READACCT-normal-01", "GAP-STB-009", (), id="catalog"),
    # These jobs hold utility steps only, so no programs.yaml unit gives the
    # Berkeley DB seam. ACCTFILE's IDCAMS DEFINE CLUSTER ... INDEXED and its
    # REPRO into the ksds ACCTDATA both give it; DEFCUST's DEFINE CLUSTER and
    # TRANIDX's DEFINE ALTERNATEINDEX and PATH and BLDINDEX give it with no dd
    # naming an indexed dataset; PRTCATBL's REPRO reads the ksds TCATBALF
    # through a dd, its SYSIN being the REPROCT member.
    pytest.param("DAT-ACCTFILE-normal-01", "GAP-STB-010", ("program",), id="indexed-utility-job"),
    pytest.param("DAT-DEFCUST-normal-01", "GAP-STB-010", ("program",), id="indexed-cluster-define"),
    pytest.param("DAT-TRANIDX-normal-01", "GAP-STB-010", ("program",), id="alternate-index-define"),
    pytest.param("RPT-PRTCATBL-normal-01", "GAP-STB-010", ("program",), id="indexed-dd"),
    # POSTTRAN holds one program step and no IEBGENER or IEFBR14 step, so
    # only the DISP=(NEW,CATLG,DELETE) of its DALYREJS generation, applied
    # when the step ends normally, gives the seam.
    pytest.param("TRN-POSTTRAN-normal-01", "GAP-STB-007", ("iebgener", "iefbr14"), id="program-step-disp"),
]


@pytest.mark.parametrize(("scenario_id", "gap_id", "absent_kinds"), SEAM_CITATIONS)
def test_seam_rule_names_a_scenario_that_does_not_cite_a_seam_it_uses(
    scenario_id: str, gap_id: str, absent_kinds: tuple[str, ...]
) -> None:
    # An in-memory copy of the scenario without its citation of a seam its
    # job steps use is the one finding.
    state = report.build_state()
    scenario = state.scenarios[scenario_id]
    assert gap_id in scenario.gap_ids
    table = load_yaml(results.GNUCOBOL_JOBS_FILE)["jobs"]
    kinds = {step["kind"] for job in scenarios.job_names(scenario) for step in table[job]["steps"]}
    assert kinds and not kinds & set(absent_kinds), (scenario_id, sorted(kinds))
    data = copy.deepcopy(scenario.data)
    data["gap_ids"] = [cited for cited in data["gap_ids"] if cited != gap_id]
    state.scenarios[scenario.id] = dataclasses.replace(scenario, data=data, gap_ids=tuple(data["gap_ids"]))
    seam = dict((entry, what) for what, entry in report.SEAMS)[gap_id]
    assert report.check_seams(state) == [
        f"{scenario_id} uses the seam of {gap_id} ({seam}) but its gap_ids do not cite it"
    ]


# The work-root variable, which the cases below set, blank or remove.
WORKDIR_VARIABLE = "CARDDEMO_TEST_WORKDIR"
WORK_ROOT_CASE_PREFIX = "unit-work-root-"
# A scenario of the id manifest that capture.py accepts on its command line.
CAPTURE_SCENARIO_ID = "UTL-CSUTLDTC-normal-01"
# git for a repository of the case's own, configured on the command line only.
GIT_LOCAL = (
    "git",
    "-c",
    "init.defaultBranch=main",
    "-c",
    "user.name=CardDemo self-test",
    "-c",
    "user.email=self-test@localhost",
    "-c",
    "commit.gpgsign=false",
)


@pytest.fixture
def work_root_case(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh folder of this case's own inside the session work root, never
    under pytest's basetemp or in the checkout; removed afterwards."""
    folder = Path(tempfile.mkdtemp(prefix=WORK_ROOT_CASE_PREFIX, dir=carddemo_work_root)).resolve()
    try:
        yield folder
    finally:
        shutil.rmtree(folder)


@pytest.fixture
def case_temporary_directory(work_root_case: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """``<case>/temp``, which ``tempfile.gettempdir()`` returns for the rest of
    the case, so a fresh temporary root or scratch folder the code under test
    makes in place of a refused work root stays inside the session work root."""
    folder = work_root_case / "temp"
    folder.mkdir()
    monkeypatch.setattr(tempfile, "tempdir", str(folder))
    return folder


# Each maker returns the CARDDEMO_TEST_WORKDIR value to set (None: unset) and
# the folder, inside the repository or below the case's folder, that a
# refusal must not create (or None).


def _unset_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    return None, None


def _empty_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    return "", None


def _blank_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    return "  ", None


def _file_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    path = case / "regular-file"
    path.write_text("not a directory\n", encoding="utf-8")
    return str(path), None


def _under_file_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    path = case / "regular-file"
    path.write_text("not a directory\n", encoding="utf-8")
    return str(path / "root"), None


def _repository_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    return str(TESTS_ROOT.parent), None


def _in_repository_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    folder = TESTS_ROOT / f".{case.name}"
    return str(folder / "root"), folder


def _unwritable_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    # An existing directory in which nothing can be made, as under chattr +i or
    # without write permission. Simulated, so the case holds when the suite
    # runs as root too; every other mkdtemp works as before. Private, so it is
    # refused as unwritable under any umask.
    root = case / "unwritable"
    root.mkdir(mode=0o700)
    make_directory = tempfile.mkdtemp

    def mkdtemp(suffix: str | None = None, prefix: str | None = None, dir: str | Path | None = None) -> str:
        if dir is not None and Path(dir).resolve() == root:
            raise PermissionError(f"[Errno 13] Permission denied (simulated): {os.path.join(dir, prefix or '')!r}")
        return make_directory(suffix, prefix, dir)

    monkeypatch.setattr(tempfile, "mkdtemp", mkdtemp)
    return str(root), None


def _group_writable_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    # As mkdir under umask 002 leaves a directory: whoever is in its group
    # could swap a scenario's working directory for a link.
    root = case / "group-writable"
    root.mkdir(mode=0o700)
    os.chmod(root, 0o775)
    return str(root), None


def _foreign_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    # Another owner, simulated through os.geteuid, so the case holds whoever
    # runs the suite.
    root = case / "foreign"
    root.mkdir(mode=0o700)
    owner = os.stat(root).st_uid
    monkeypatch.setattr(os, "geteuid", lambda: owner + 1)
    return str(root), None


def _path_text_work_root(text: str) -> Callable[[Path, pytest.MonkeyPatch], tuple[str | None, Path | None]]:
    """The maker of a missing work root below a folder whose name holds
    ``text``, which the refusal must not create."""

    def make(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
        folder = case / f"w{text}r"
        return str(folder / "root"), folder

    return make


def _work_root_of_length(case: Path, length: int) -> Path:
    """A path below ``case`` of exactly ``length`` bytes, of names no longer
    than 200 bytes; the case is skipped when ``case`` leaves no room for
    one."""
    path, remaining = str(case), length - len(os.fsencode(case))
    if remaining < 2:
        pytest.skip(f"the session work root leaves no room below it for a path of {length} bytes")
    while remaining:
        taken = min(201, remaining)  # the separator and a name of up to 200 bytes
        if remaining - taken == 1:  # never leave room for a separator alone
            taken -= 1
        path += "/" + "w" * (taken - 1)
        remaining -= taken
    return Path(path)


def _over_long_work_root(case: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[str | None, Path | None]:
    root = _work_root_of_length(case, harness._WORK_ROOT_MAX_BYTES + 1)
    return str(root), case / root.relative_to(case).parts[0]


# Text no work root's path may hold, whatever the adapter, and how the
# refusal names it: the characters a shell reads inside the double quotes of
# cobc's compiler commands, and characters that are not printable.
WORK_ROOT_PATH_TEXT = [
    pytest.param("`", "holds '`', which a shell reads even inside double quotes", id="backquote"),
    pytest.param("$", "holds '$', which a shell reads even inside double quotes", id="dollar"),
    pytest.param('"', "holds '\"', which a shell reads even inside double quotes", id="double-quote"),
    pytest.param("\\", "holds '\\\\', which a shell reads even inside double quotes", id="backslash"),
    pytest.param("\n", "holds the non-printable character '\\n'", id="newline"),
    pytest.param("\t", "holds the non-printable character '\\t'", id="tab"),
    pytest.param("\x1b", "holds the non-printable character '\\x1b'", id="escape"),
]
WORK_ROOT_OVER_LONG = f"bytes long, more than the {harness._WORK_ROOT_MAX_BYTES} a work root may have"

# The work-root values replaced by a fresh temporary root, and what the
# reason for refusing a set one says (None: unset, which is not refused).
REPLACED_WORK_ROOTS = [
    pytest.param(_unset_work_root, None, id="unset"),
    pytest.param(_empty_work_root, "the value is blank", id="empty"),
    pytest.param(_blank_work_root, "the value is blank", id="blank"),
    pytest.param(_file_work_root, "is not a directory", id="regular-file"),
    pytest.param(_under_file_work_root, "cannot be created", id="under-regular-file"),
    pytest.param(_repository_work_root, "the path is the repository itself", id="repository"),
    pytest.param(_in_repository_work_root, "inside the repository", id="inside-repository"),
    pytest.param(_unwritable_work_root, "is not writable", id="unwritable-directory"),
    pytest.param(_group_writable_work_root, "is writable by group or others (mode 0775)", id="group-writable"),
    pytest.param(_foreign_work_root, "is owned by uid", id="foreign-owner"),
    *(
        pytest.param(_path_text_work_root(case.values[0]), case.values[1], id=f"path-{case.id}")
        for case in WORK_ROOT_PATH_TEXT
    ),
    pytest.param(_over_long_work_root, WORK_ROOT_OVER_LONG, id="over-long"),
]
REFUSED_WORK_ROOTS = [case for case in REPLACED_WORK_ROOTS if case.values[0] is not _unset_work_root]


def _set_work_root_variable(monkeypatch: pytest.MonkeyPatch, value: str | None) -> None:
    if value is None:
        monkeypatch.delenv(WORKDIR_VARIABLE, raising=False)
    else:
        monkeypatch.setenv(WORKDIR_VARIABLE, value)


def _assert_not_created(absent: Path | None) -> None:
    """Fail when a refused root created ``absent``, inside the repository or
    below the case's folder, after removing it."""
    if absent is None or not os.path.lexists(absent):
        return
    shutil.rmtree(absent)
    pytest.fail(f"{absent} was created by a refused work root")


def _assert_fresh_temporary_root(root: Path, case: Path) -> None:
    """``root`` is a new, empty ``carddemo-`` folder of the temporary
    directory, outside the repository and apart from the session work root."""
    assert root == root.resolve(), root
    assert root.name.startswith("carddemo-") and root.parent == Path(tempfile.gettempdir()).resolve(), root
    assert root.is_dir() and not any(root.iterdir()), root
    assert root != TESTS_ROOT.parent and TESTS_ROOT.parent not in root.parents, root
    assert root != case.parent, "the session work root is reused instead of a fresh one"


def _configure_session(
    monkeypatch: pytest.MonkeyPatch, adapter: str | None = None
) -> tuple[SimpleNamespace, list[Warning]]:
    """A stand-in for pytest's config after conftest's ``pytest_configure``,
    with the config-time warnings it gave, for ``CARDDEMO_ADAPTER`` set to
    ``adapter`` (unset when ``None``)."""
    # pytest imports tests/conftest.py as the module `conftest`.
    import conftest

    if adapter is None:
        monkeypatch.delenv("CARDDEMO_ADAPTER", raising=False)
    else:
        monkeypatch.setenv("CARDDEMO_ADAPTER", adapter)
    given: list[Warning] = []
    config = SimpleNamespace(
        stash=pytest.Stash(),
        issue_config_time_warning=lambda warning, stacklevel: given.append(warning),
    )
    conftest.pytest_configure(config)
    return config, given


@pytest.mark.parametrize(("make", "reason"), REPLACED_WORK_ROOTS)
def test_conftest_replaces_an_unset_or_unusable_work_root(
    monkeypatch: pytest.MonkeyPatch,
    work_root_case: Path,
    case_temporary_directory: Path,
    make: Callable,
    reason: str | None,
) -> None:
    import conftest

    configured, absent = make(work_root_case, monkeypatch)
    _set_work_root_variable(monkeypatch, configured)
    try:
        config, given = _configure_session(monkeypatch)
        root = config.stash[conftest.WORK_ROOT_KEY]
        try:
            _assert_fresh_temporary_root(root, work_root_case)
            assert config.stash.get(conftest.CREATED_WORK_ROOT_KEY, None) == root
            if configured is None:
                assert given == []
            else:
                assert [type(warning) for warning in given] == [pytest.PytestConfigWarning], given
                text = str(given[0])
                assert text.startswith(f"{WORKDIR_VARIABLE}={configured!r} is not a usable work root: "), text
                assert reason in text and f"the fresh temporary work root {root} instead" in text, text
            conftest.pytest_unconfigure(config)
            assert not root.exists(), "pytest_unconfigure keeps the empty temporary work root the session made"
        finally:
            shutil.rmtree(root, ignore_errors=True)
    finally:
        _assert_not_created(absent)


@pytest.mark.parametrize(
    "mode",
    [
        pytest.param(None, id="missing"),
        pytest.param(0o700, id="existing-private"),
        pytest.param(0o755, id="existing-0755-as-umask-022-makes-it"),
    ],
)
def test_conftest_uses_a_usable_work_root(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, mode: int | None
) -> None:
    """A usable root is used as configured, with no warning; a missing one is
    created with mode 0700, and an existing one keeps its mode, 0755 as
    ``mkdir`` makes one under umask 022 included."""
    import conftest

    root = work_root_case / "work root" / "nested"
    if mode is not None:
        root.mkdir(mode=0o700, parents=True)
        os.chmod(root, mode)
    _set_work_root_variable(monkeypatch, str(root))
    config, given = _configure_session(monkeypatch)
    try:
        assert config.stash[conftest.WORK_ROOT_KEY] == root
        # Permission bits only: a setgid bit is inherited from the folder above.
        assert os.stat(root).st_mode & 0o777 == (0o700 if mode is None else mode)
        assert conftest.CREATED_WORK_ROOT_KEY not in config.stash
        assert given == []
        # Only the session lock, which names this process; the write probe is gone.
        assert sorted(path.name for path in root.iterdir()) == [WORK_ROOT_LOCK_NAME]
        assert (root / WORK_ROOT_LOCK_NAME).read_text(encoding="utf-8").startswith(f"pid {os.getpid()}, since ")
        assert config.stash[conftest.WORK_ROOT_LOCK_KEY].held
        with pytest.raises(WorkRootBusy):
            lock_work_root(root)
    finally:
        conftest.pytest_unconfigure(config)
    assert root.is_dir(), "pytest_unconfigure removes a configured work root"
    assert not config.stash[conftest.WORK_ROOT_LOCK_KEY].held, "pytest_unconfigure keeps the work root locked"
    assert (root / WORK_ROOT_LOCK_NAME).read_text(encoding="utf-8") == "", "the released lock still names a holder"
    with lock_work_root(root):
        pass


# Work-root modes every admission holds a root to: accepted only without the
# group and other write bits, whatever the setgid and sticky bits.
WORK_ROOT_MODES = [0o700, 0o750, 0o755, 0o775, 0o757, 0o777, 0o2775, 0o1777]
# Work-root names held to the UniKix deployment's path rule as well: with
# jvm_safe_path, refused when the path holds whitespace or a quote, which the
# JVM option variables naming the deployment's tmp/ cannot carry, or else ':',
# at which the search paths naming its directories are split, before its
# owner and mode are looked at; without it only the double quote, which a
# shell reads, is refused, also before its owner and mode, and ':' is
# accepted.
JVM_SAFE_PATH_NAMES = [
    pytest.param("root", id="plain"),
    pytest.param("ro ot", id="space"),
    pytest.param("ro'ot", id="single-quote"),
    pytest.param('ro"ot', id="double-quote"),
    pytest.param("ro:ot", id="colon"),
]
JVM_UNSAFE_PATH = "holds whitespace or a quote, which a JVM option variable cannot carry; choose another work root"
# Why every admission refuses a path holding a character a shell reads inside
# double quotes, after the character itself.
SHELL_TEXT_REFUSAL = (
    "which a shell reads even inside double quotes, as in the C compiler commands GnuCOBOL's cobc runs "
    "through /bin/sh; choose another work root"
)


@pytest.mark.parametrize("name", JVM_SAFE_PATH_NAMES)
@pytest.mark.parametrize("foreign", [pytest.param(False, id="own"), pytest.param(True, id="foreign-owner")])
@pytest.mark.parametrize("mode", WORK_ROOT_MODES, ids=lambda mode: f"{mode:04o}")
def test_work_root_admission_matches_the_unikix_deployment(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, mode: int, foreign: bool, name: str
) -> None:
    """``usable_work_root(jvm_safe_path=True)``, conftest's admission under
    unikix, accepts and refuses exactly the work roots the UniKix
    deployment's ``_check_work_root`` does, for the same reason. The default
    admission, every other caller's, refuses the same owners and modes for
    the same reason, but of the names only the one holding a double quote,
    for its own reason; neither changes the root. Another owner is simulated
    through ``os.geteuid``, so the case holds whoever runs the suite. The
    expected outcome follows the root's whole path, so a session work root
    holding whitespace, a quote or ':' makes every ``jvm_safe_path`` case the
    path refusal."""
    root = work_root_case / name
    root.mkdir(mode=0o700)
    os.chmod(root, mode)
    actual = stat.S_IMODE(os.stat(root).st_mode)
    owner = os.stat(root).st_uid
    if foreign:
        monkeypatch.setattr(os, "geteuid", lambda: owner + 1)
    refusals: list[str | None] = []
    for check in (
        unikix_adapter.deploy._check_work_root,
        functools.partial(usable_work_root, jvm_safe_path=True),
        usable_work_root,
    ):
        try:
            check(root)
        except HarnessError as exc:
            refusals.append(str(exc))
        else:
            refusals.append(None)
    if foreign:
        owner_reason = f"is owned by uid {owner}, not {owner + 1}; choose another work root"
    elif actual & (stat.S_IWGRP | stat.S_IWOTH):
        owner_reason = (
            f"is writable by group or others (mode {actual:04o}), so they could plant links in it; "
            "run chmod go-w on it or choose another work root"
        )
    else:
        owner_reason = None
    jvm_reason = _unikix_path_refusal(root) or owner_reason
    default_reason = f"holds '\"', {SHELL_TEXT_REFUSAL}" if '"' in name else owner_reason
    assert refusals == [
        None if jvm_reason is None else f"work root {root} {jvm_reason}",
        None if jvm_reason is None else f"{root} {jvm_reason}",
        None if default_reason is None else f"{root} {default_reason}",
    ], refusals
    assert stat.S_IMODE(os.stat(root).st_mode) == actual and not any(root.iterdir()), "the admission changed the root"


def test_jvm_safe_path_rule_is_the_unikix_environment_s() -> None:
    """The harness keeps its own copy of the pattern the UniKix environment
    refuses a temporary directory by (``env._JVM_UNSAFE_PATH_RE``), and of
    the reason it and the deployment refuse a root whose path holds ':' with
    (``env._PATH_LIST_UNSAFE``), because it does not import the adapters;
    neither copy may drift from its original."""
    ours, theirs = harness._JVM_UNSAFE_PATH_RE, unikix_env._JVM_UNSAFE_PATH_RE
    assert (ours.pattern, ours.flags) == (theirs.pattern, theirs.flags)
    assert harness._PATH_LIST_UNSAFE == unikix_env._PATH_LIST_UNSAFE


@pytest.mark.parametrize("jvm_safe_path", [pytest.param(True, id="jvm-safe-path"), pytest.param(False, id="default")])
def test_work_root_made_under_umask_002(work_root_case: Path, jvm_safe_path: bool) -> None:
    """A missing work root made under umask 002, as an operator's shell may
    have it: either admission creates it private, so the runner accepts it
    and, for a path without whitespace, a quote or ':', so does the UniKix
    deployment; missing parents get the umask's mode. Under a session work
    root whose path holds whitespace, a quote or ':', the ``jvm_safe_path``
    admission refuses the root before creating anything."""
    root = work_root_case / "made" / "root"
    refusal = _unikix_path_refusal(root)
    if jvm_safe_path and refusal is not None:
        with pytest.raises(HarnessError) as refused:
            usable_work_root(root, jvm_safe_path=jvm_safe_path)
        assert str(refused.value) == f"{root} {refusal}"
        assert not root.parent.exists(), "a refused work root was created"
        return
    previous = os.umask(0o002)
    try:
        assert usable_work_root(root, jvm_safe_path=jvm_safe_path) == root
        workdir, _ = runner._fresh_workdir(SimpleNamespace(work_root=root), RUNNER_SCENARIO_ID)
    finally:
        os.umask(previous)
    # Permission bits only: a setgid bit is inherited from the folder above.
    assert os.stat(root).st_mode & 0o777 == 0o700
    assert os.stat(workdir).st_mode & 0o777 == 0o700
    assert os.stat(root.parent).st_mode & 0o777 == 0o775
    if refusal is None:
        unikix_adapter.deploy._check_work_root(root)


@pytest.mark.parametrize(("text", "reason"), WORK_ROOT_PATH_TEXT)
def test_usable_work_root_refuses_text_no_work_root_may_hold(work_root_case: Path, text: str, reason: str) -> None:
    """Every admission refuses a path holding a character a shell reads even
    inside double quotes, or one that is not printable, naming it (the path
    shown as its ``repr`` when it is not printable), before creating
    anything. The resolved path is judged, so a link without such text
    leading to a folder holding it is refused too."""
    folder = work_root_case / f"w{text}r"
    root = folder / "root"
    shown = str(root) if str(root).isprintable() else repr(str(root))
    with pytest.raises(HarnessError) as refused:
        usable_work_root(root)
    assert str(refused.value).startswith(f"{shown} {reason}"), refused.value
    assert str(refused.value).endswith("; choose another work root"), refused.value
    assert not os.path.lexists(folder), "a refused work root was created"
    folder.mkdir(mode=0o700)
    link = work_root_case / "plain"
    link.symlink_to(folder, target_is_directory=True)
    with pytest.raises(HarnessError) as through_link:
        usable_work_root(link / "root")
    assert str(through_link.value) == str(refused.value), through_link.value
    assert not any(folder.iterdir()), "a refused work root was created"


@pytest.mark.parametrize(
    ("extra", "accepted"), [pytest.param(0, True, id="at-the-limit"), pytest.param(1, False, id="one-byte-over")]
)
def test_usable_work_root_holds_a_path_to_the_length_limit(work_root_case: Path, extra: int, accepted: bool) -> None:
    """A resolved work root of ``_WORK_ROOT_MAX_BYTES`` bytes is accepted and
    created, and one byte more refused, naming its length and the limit,
    before anything is created: the limit leaves ``_WORK_ROOT_PATH_RESERVE``
    bytes of ``PATH_MAX`` for the paths the suite makes below the root."""
    assert harness._WORK_ROOT_MAX_BYTES + harness._WORK_ROOT_PATH_RESERVE == harness._PATH_MAX - 1
    length = harness._WORK_ROOT_MAX_BYTES + extra
    root = _work_root_of_length(work_root_case, length)
    assert len(os.fsencode(root)) == length
    first = work_root_case / root.relative_to(work_root_case).parts[0]
    if accepted:
        assert usable_work_root(root) == root
        assert os.stat(root).st_mode & 0o777 == 0o700 and not any(root.iterdir())
        return
    with pytest.raises(HarnessError) as refused:
        usable_work_root(root)
    assert str(refused.value) == (
        f"the path is {length} bytes long, more than the {harness._WORK_ROOT_MAX_BYTES} a work root may have: "
        f"the paths the suite makes below it take up to {harness._WORK_ROOT_PATH_RESERVE} more, and no path "
        f"may exceed {harness._PATH_MAX - 1} bytes (PATH_MAX); choose a shorter work root"
    )
    assert not os.path.lexists(first), "a refused work root was created"


@pytest.mark.parametrize("caller", ["conftest", "capture"])
@pytest.mark.parametrize(("text", "reason"), WORK_ROOT_PATH_TEXT)
def test_a_temporary_root_holding_text_no_work_root_may_hold_is_refused(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, caller: str, text: str, reason: str
) -> None:
    """The fresh temporary root that replaces an unset work root is held to
    the same path rule: under a temporary directory whose path holds such a
    character, conftest stops with a usage error and ``capture.py`` with a
    ``CaptureError``, each naming the character, and neither leaves the root
    it made behind."""
    import conftest

    temporary = work_root_case / f"te{text}mp"
    temporary.mkdir(mode=0o700)
    monkeypatch.setattr(tempfile, "tempdir", str(temporary))
    _set_work_root_variable(monkeypatch, None)
    if caller == "conftest":
        with pytest.raises(pytest.UsageError) as stopped:
            conftest._work_root()
    else:
        with pytest.raises(capture.CaptureError) as stopped:
            capture._work_root(None)
    message = str(stopped.value)
    assert message.startswith("the temporary work root cannot be used: ") and reason in message, message
    assert not any(temporary.iterdir()), "the refused temporary root was left behind"


@pytest.mark.parametrize(
    ("adapter", "name", "mode"),
    [
        pytest.param("unikix", "work-root", 0o775, id="unikix-group-writable"),
        pytest.param("unikix", "work-root", 0o700, id="unikix-private"),
        pytest.param("unikix", "work root", 0o700, id="unikix-whitespace"),
        pytest.param("unikix", "work'root", 0o700, id="unikix-quote"),
        pytest.param("unikix", "work:root", 0o700, id="unikix-colon"),
        pytest.param("unikix", "work`root", 0o700, id="unikix-backquote"),
        pytest.param("gnucobol-batch", "work root", 0o775, id="gnucobol-batch-group-writable"),
        pytest.param("gnucobol-batch", "work root", 0o700, id="gnucobol-batch-whitespace"),
        pytest.param("gnucobol-batch", "work'root", 0o700, id="gnucobol-batch-quote"),
        pytest.param("gnucobol-batch", "work:root", 0o700, id="gnucobol-batch-colon"),
        pytest.param("gnucobol-batch", "work`root", 0o700, id="gnucobol-batch-backquote"),
    ],
)
def test_conftest_holds_a_work_root_to_its_adapter_s_rules(
    monkeypatch: pytest.MonkeyPatch,
    work_root_case: Path,
    case_temporary_directory: Path,
    adapter: str,
    name: str,
    mode: int,
) -> None:
    """A configured work root that group or others can write to, or whose
    path holds a character a shell reads even inside double quotes, is
    replaced under every adapter by a fresh private temporary root with a
    warning naming the rule; under ``unikix`` so is one whose path holds
    whitespace, a quote or ':', which the deployment would refuse in every
    scenario, while gnucobol-batch uses a private one as configured. The
    expected outcome follows the root's whole path, so under a session work
    root holding whitespace, a quote or ':' every unikix case is the path
    refusal."""
    import conftest

    root = work_root_case / name
    root.mkdir(mode=0o700)
    os.chmod(root, mode)
    unikix_refusal = _unikix_path_refusal(root) if adapter == "unikix" else None
    if unikix_refusal is not None:
        reason = f"{root} {unikix_refusal}"
    elif "`" in name:
        reason = f"{root} holds '`', {SHELL_TEXT_REFUSAL}"
    elif mode & (stat.S_IWGRP | stat.S_IWOTH):
        reason = (
            f"{root} is writable by group or others (mode {mode:04o}), so they could plant links in it; "
            "run chmod go-w on it or choose another work root"
        )
    else:
        reason = None
    used = reason is None
    _set_work_root_variable(monkeypatch, str(root))
    config, given = _configure_session(monkeypatch, adapter)
    session_root = config.stash[conftest.WORK_ROOT_KEY]
    try:
        assert config.stash[conftest.ADAPTER_NAME_KEY] == adapter
        if used:
            assert session_root == root and given == [], given
            assert conftest.CREATED_WORK_ROOT_KEY not in config.stash
        else:
            _assert_fresh_temporary_root(session_root, work_root_case)
            assert os.stat(session_root).st_mode & 0o777 == 0o700, "the replacement root is not private"
            assert [type(warning) for warning in given] == [pytest.PytestConfigWarning], given
            assert str(given[0]) == (
                f"{WORKDIR_VARIABLE}={str(root)!r} is not a usable work root: {reason}; "
                f"this session uses the fresh temporary work root {session_root} instead"
            ), given
    finally:
        conftest.pytest_unconfigure(config)
    assert stat.S_IMODE(os.stat(root).st_mode) == mode, "the configured root's mode was changed"
    if not used:
        try:
            assert not session_root.exists(), "pytest_unconfigure keeps the empty temporary work root the session made"
        finally:
            shutil.rmtree(session_root, ignore_errors=True)


def _temporary_directory_in_repository(monkeypatch: pytest.MonkeyPatch) -> str:
    monkeypatch.setattr(tempfile, "tempdir", str(TESTS_ROOT))
    return f"the temporary directory ({TESTS_ROOT}) lies inside the repository"


def _temporary_root_refused(monkeypatch: pytest.MonkeyPatch) -> str:
    def mkdtemp(suffix: str | None = None, prefix: str | None = None, dir: str | Path | None = None) -> str:
        raise PermissionError("[Errno 13] Permission denied (simulated)")

    monkeypatch.setattr(tempfile, "mkdtemp", mkdtemp)
    return "no temporary work root can be made: [Errno 13] Permission denied (simulated)"


@pytest.mark.parametrize("configured", [pytest.param(None, id="unset"), pytest.param("", id="empty")])
@pytest.mark.parametrize(
    "break_fallback",
    [
        pytest.param(_temporary_directory_in_repository, id="temporary-directory-in-repository"),
        pytest.param(_temporary_root_refused, id="temporary-root-refused"),
    ],
)
def test_conftest_stops_without_a_work_root_outside_the_repository(
    monkeypatch: pytest.MonkeyPatch, configured: str | None, break_fallback: Callable
) -> None:
    import conftest

    def strays() -> list[Path]:
        return sorted(path for path in TESTS_ROOT.iterdir() if path.name.startswith(conftest.WORK_ROOT_PREFIX))

    _set_work_root_variable(monkeypatch, configured)
    before = strays()
    problem = break_fallback(monkeypatch)
    try:
        with pytest.raises(pytest.UsageError) as stopped:
            conftest._work_root()
    finally:
        made = [path for path in strays() if path not in before]
        for path in made:
            shutil.rmtree(path)
    assert made == [], "a temporary work root was made inside the repository"
    message = str(stopped.value)
    assert problem in message, message
    refused = f"{WORKDIR_VARIABLE}={configured!r} is not a usable work root: the value is blank"
    assert (refused in message) is (configured is not None), message


@pytest.mark.parametrize(("make", "reason"), REPLACED_WORK_ROOTS)
def test_capture_replaces_an_unset_or_unusable_work_root_variable(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    work_root_case: Path,
    case_temporary_directory: Path,
    make: Callable,
    reason: str | None,
) -> None:
    from harness import capture

    configured, absent = make(work_root_case, monkeypatch)
    _set_work_root_variable(monkeypatch, configured)
    try:
        root, created = capture._work_root(None)
        try:
            assert created
            _assert_fresh_temporary_root(root, work_root_case)
            warning = capsys.readouterr().err
            if configured is None:
                assert warning == ""
            else:
                assert warning.startswith(
                    f"capture.py: warning: {WORKDIR_VARIABLE}={configured!r} is not a usable work root: "
                ), warning
                assert reason in warning, warning
            capture._discard(root, created, [])
            assert not root.exists(), "capture.py keeps the temporary work root it made"
        finally:
            shutil.rmtree(root, ignore_errors=True)
    finally:
        _assert_not_created(absent)


def _no_capture_adapter(work_root: Path) -> None:
    raise HarnessError(f"the work root {work_root} was accepted, so the capture would run")


@pytest.mark.parametrize(("make", "reason"), REFUSED_WORK_ROOTS)
def test_capture_refuses_an_unusable_work_root_option(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    work_root_case: Path,
    make: Callable,
    reason: str,
) -> None:
    from harness import capture

    configured, absent = make(work_root_case, monkeypatch)
    monkeypatch.delenv(WORKDIR_VARIABLE, raising=False)
    # A work root accepted by mistake ends the run here, before any capture.
    monkeypatch.setattr(capture, "make_adapter", _no_capture_adapter)
    try:
        with pytest.raises(SystemExit) as stopped:
            capture.main([CAPTURE_SCENARIO_ID, "--work-root", configured])
        assert stopped.value.code == 2
        error = capsys.readouterr().err
        assert f"--work-root {configured!r} is not a usable work root: " in error and reason in error, error
    finally:
        _assert_not_created(absent)


@pytest.mark.parametrize("source", [pytest.param("variable", id="variable"), pytest.param("option", id="option")])
def test_capture_uses_a_usable_work_root(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str], work_root_case: Path, source: str
) -> None:
    from harness import capture

    root = work_root_case / "work root"
    _set_work_root_variable(monkeypatch, str(root) if source == "variable" else None)
    assert capture._work_root(str(root) if source == "option" else None) == (root, False)
    assert root.is_dir() and not any(root.iterdir()), "the write probe stays in the work root"
    assert os.stat(root).st_mode & 0o777 == 0o700, "the missing work root was not made private"
    assert capsys.readouterr().err == ""


def test_lock_work_root_records_its_holder(monkeypatch: pytest.MonkeyPatch, work_root_case: Path) -> None:
    """The session lock replaces the record a holder that ended left, a
    longer one included, with one line naming this process (pid, UTC time,
    command line with line breaks shown as ``?`` and clipped to 200
    characters) on a descriptor no child inherits. A refused session reads
    that line back without waiting, or, after re-reading an empty record
    every poll interval up to the wait bound, says the holder has not
    recorded itself yet; release empties the record, keeps the file and may
    be repeated, and the lock can then be taken again."""
    path = work_root_case / WORK_ROOT_LOCK_NAME
    path.write_text(
        f"pid 1, since 2000-01-01T00:00:00Z, running a session that ended\n{'its second line ' * 40}\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(sys, "argv", ["pytest", "tests/\nnext", "x" * 400])
    lock = lock_work_root(work_root_case)
    try:
        assert lock.held and lock.root == work_root_case and lock.path == path
        assert not os.get_inheritable(lock._descriptor), "a child process would inherit the lock"
        record = path.read_text(encoding="utf-8")
        matched = re.fullmatch(rf"pid {os.getpid()}, since \d{{4}}-\d\d-\d\dT\d\d:\d\d:\d\dZ, running (.+)\n", record)
        assert matched, record
        command = matched.group(1)
        assert len(command) == 200, command
        assert command.startswith("pytest tests/?next xxx") and command.endswith("x..."), command
        slept: list[float] = []

        def no_wait(seconds: float) -> None:
            raise AssertionError(f"a refusal whose holder record is present waited {seconds} s")

        def sleep(seconds: float) -> None:
            slept.append(seconds)
            time.sleep(seconds)

        monkeypatch.setattr("harness.time", SimpleNamespace(monotonic=time.monotonic, sleep=no_wait))
        with pytest.raises(WorkRootBusy) as busy:
            lock_work_root(work_root_case)
        assert busy.value.root == work_root_case and busy.value.holder == record.rstrip("\n"), busy.value
        monkeypatch.setattr("harness.time", SimpleNamespace(monotonic=time.monotonic, sleep=sleep))
        monkeypatch.setattr("harness._HOLDER_WAIT_S", 0.05)
        monkeypatch.setattr("harness._HOLDER_POLL_S", 0.01)
        os.truncate(path, 0)  # as a holder shows before it has written its record
        with pytest.raises(WorkRootBusy, match=r" \(it has not recorded itself yet\); "):
            lock_work_root(work_root_case)
        # Polled every interval at most, and for no longer than the bound.
        assert slept and all(0 < seconds <= 0.01 for seconds in slept), slept
        assert sum(slept) <= 0.05 + 1e-9, slept
    finally:
        lock.release()
    assert not lock.held and path.is_file() and path.read_text(encoding="utf-8") == ""
    lock.release()
    with lock_work_root(work_root_case) as again:
        assert again.held
    assert not again.held


def test_lock_work_root_names_a_holder_that_records_itself_late(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path
) -> None:
    """A session refused while the holder has taken the lock but not yet
    written its record re-reads the record and names the holder as soon as
    the record appears, well before the wait bound."""
    monkeypatch.setattr("harness._HOLDER_WAIT_S", 60.0)
    path = work_root_case / WORK_ROOT_LOCK_NAME
    line = "pid 4242, since 2000-01-01T00:00:00Z, running a holder that records itself late"
    holder = lock_work_root(work_root_case)
    try:
        os.truncate(path, 0)  # the holder has locked but not written its record yet
        writer = threading.Timer(0.1, path.write_text, args=(f"{line}\n",), kwargs={"encoding": "utf-8"})
        started = time.monotonic()
        writer.start()
        try:
            with pytest.raises(WorkRootBusy) as busy:
                lock_work_root(work_root_case)
            waited = time.monotonic() - started
        finally:
            writer.join()
    finally:
        holder.release()
    assert busy.value.holder == line, busy.value
    assert 0.1 <= waited < 30, f"the refused session waited {waited:.3f} s for a record written after 0.1 s"


def test_lock_work_root_takes_a_root_whose_unrecorded_holder_ends(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path
) -> None:
    """A session refused while the holder has not yet recorded itself takes
    the lock once that holder ends during the wait, instead of refusing a
    root no session holds any more."""
    monkeypatch.setattr("harness._HOLDER_WAIT_S", 60.0)
    path = work_root_case / WORK_ROOT_LOCK_NAME
    holder = lock_work_root(work_root_case)
    try:
        os.truncate(path, 0)  # the holder has locked but not written its record yet
        ender = threading.Timer(0.1, holder.release)
        started = time.monotonic()
        ender.start()
        try:
            lock = lock_work_root(work_root_case)
            waited = time.monotonic() - started
        finally:
            ender.join()
    finally:
        holder.release()
    try:
        assert lock.held and 0.1 <= waited < 30, f"the lock was taken after {waited:.3f} s"
        assert path.read_text(encoding="utf-8").startswith(f"pid {os.getpid()}, since ")
    finally:
        lock.release()


def _plant_symbolic_link(path: Path) -> Path | None:
    target = path.parent / "target"
    target.write_text("kept\n", encoding="utf-8")
    path.symlink_to(target)
    return target


def _plant_directory(path: Path) -> Path | None:
    path.mkdir()
    return None


def _plant_fifo(path: Path) -> Path | None:
    os.mkfifo(path)
    return None


@pytest.mark.parametrize(
    ("plant", "reason"),
    [
        pytest.param(_plant_symbolic_link, "is a symbolic link, which is never followed", id="symbolic-link"),
        pytest.param(_plant_directory, "is not a regular file", id="directory"),
        pytest.param(_plant_fifo, "is not a regular file", id="fifo"),
    ],
)
def test_lock_work_root_refuses_a_planted_lock(work_root_case: Path, plant: Callable, reason: str) -> None:
    """A session lock that is a symbolic link, a directory or a FIFO is
    refused with the reason, never followed or written through, and is not
    taken for another session's lock."""
    path = work_root_case / WORK_ROOT_LOCK_NAME
    target = plant(path)
    with pytest.raises(HarnessError) as refused:
        lock_work_root(work_root_case)
    assert not isinstance(refused.value, WorkRootBusy), refused.value
    assert str(refused.value) == f"the session lock {path} {reason}; remove it or choose another work root"
    if target is not None:
        assert path.is_symlink() and target.read_text(encoding="utf-8") == "kept\n", "the link was followed"


def _hold_in_this_process(root: Path) -> tuple[int, Callable[[], None]]:
    """Hold ``root``'s session lock through another open file description of
    this process; the holder's pid and how to end it."""
    lock = lock_work_root(root)
    return os.getpid(), lock.release


# A child process that holds the session lock of the work root argv[2] (with
# tests/ as argv[1]) until its standard input closes.
LOCK_HOLDER_CHILD = """\
import sys

sys.path.insert(0, sys.argv[1])
import harness

with harness.lock_work_root(sys.argv[2]):
    print("locked", flush=True)
    sys.stdin.read()
"""


def _hold_in_another_process(root: Path) -> tuple[int, Callable[[], None]]:
    """Hold ``root``'s session lock in a child process; its pid and how to end
    it."""
    child = subprocess.Popen(
        [sys.executable, "-B", "-c", LOCK_HOLDER_CHILD, str(TESTS_ROOT), str(root)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )

    def release() -> None:
        child.stdin.close()
        child.wait(timeout=60)
        child.stdout.close()

    line = child.stdout.readline()
    if line != "locked\n":
        release()
        pytest.fail(f"the holder process did not take the lock: {line!r}")
    return child.pid, release


WORK_ROOT_HOLDERS = [
    pytest.param(_hold_in_this_process, id="this-process"),
    pytest.param(_hold_in_another_process, id="another-process"),
]


def _busy_text(root: Path, holder: int) -> str:
    """The start of the refusal of the work root ``root`` held by pid ``holder``."""
    return f"the work root {root} is in use by another session (pid {holder}, since "


@pytest.mark.parametrize("hold", WORK_ROOT_HOLDERS)
def test_conftest_refuses_a_work_root_another_session_holds(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, hold: Callable
) -> None:
    """A configured work root whose session lock another session holds, in
    another process or through another open file description of this one,
    stops the session with a usage error naming the root and the holder's
    pid, so the two never empty each other's working directories; once the
    holder ends, the same root is used."""
    import conftest

    root = work_root_case / "shared"
    root.mkdir(mode=0o700)  # private under any umask, as every admission requires
    _set_work_root_variable(monkeypatch, str(root))
    holder, release = hold(root)
    try:
        with pytest.raises(pytest.UsageError) as stopped:
            _configure_session(monkeypatch)
    finally:
        release()
    message = str(stopped.value)
    assert message.startswith(f"{WORKDIR_VARIABLE}: {_busy_text(root, holder)}"), message
    assert message.endswith(
        "; give each concurrent session its own work root, or wait for that session to end"
    ), message
    config, given = _configure_session(monkeypatch)
    try:
        assert config.stash[conftest.WORK_ROOT_KEY] == root and given == [], given
        assert (root / WORK_ROOT_LOCK_NAME).read_text(encoding="utf-8").startswith(f"pid {os.getpid()}, since ")
    finally:
        conftest.pytest_unconfigure(config)


@pytest.mark.parametrize("source", [pytest.param("variable", id="variable"), pytest.param("option", id="option")])
@pytest.mark.parametrize("hold", WORK_ROOT_HOLDERS)
def test_capture_refuses_a_work_root_another_session_holds(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    work_root_case: Path,
    source: str,
    hold: Callable,
) -> None:
    """``capture.py`` on a work root another run or session holds, named by
    ``CARDDEMO_TEST_WORKDIR`` or ``--work-root``, stops with exit status 2
    naming the root and the holder's pid, before any capture runs."""
    from harness import capture

    root = work_root_case / "shared"
    root.mkdir(mode=0o700)  # private under any umask, as every admission requires
    _set_work_root_variable(monkeypatch, str(root) if source == "variable" else None)
    option = ["--work-root", str(root)] if source == "option" else []
    monkeypatch.setattr(capture, "make_adapter", _no_capture_adapter)
    holder, release = hold(root)
    try:
        with pytest.raises(SystemExit) as stopped:
            capture.main([CAPTURE_SCENARIO_ID, *option])
    finally:
        release()
    assert stopped.value.code == 2
    error = capsys.readouterr().err
    assert f"capture.py: error: {_busy_text(root, holder)}" in error, error


@pytest.mark.parametrize("ending", ["setup-fails", "capture-raises"])
def test_capture_releases_the_work_root_it_locked(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, ending: str
) -> None:
    """``capture.py`` holds a configured work root's session lock while it
    captures and gives it up however the run ends: when the adapter cannot
    be set up (exit status 1) and when a capture raises, after closing the
    adapter."""
    from harness import capture

    root = work_root_case / "work root"
    _set_work_root_variable(monkeypatch, str(root))
    held: list[bool] = []

    def assert_held() -> None:
        with pytest.raises(WorkRootBusy):
            lock_work_root(root)
        held.append(True)

    if ending == "setup-fails":

        def make_adapter(work_root: Path) -> None:
            assert_held()
            raise HarnessError("no adapter (simulated)")

        monkeypatch.setattr(capture, "make_adapter", make_adapter)
        assert capture.main([CAPTURE_SCENARIO_ID]) == 1
    else:
        closed: list[bool] = []

        def fail(scenario_id: str, bump: str | None, adapter: object) -> None:
            assert_held()
            raise RuntimeError("the capture failed (simulated)")

        adapter = SimpleNamespace(close=lambda: closed.append(True))
        monkeypatch.setattr(capture, "make_adapter", lambda work_root: adapter)
        monkeypatch.setattr(capture, "capture", fail)
        with pytest.raises(RuntimeError, match="simulated"):
            capture.main([CAPTURE_SCENARIO_ID])
        assert closed == [True], "the adapter was not closed"
    assert held == [True], "the capture ran without holding the work root"
    assert (root / WORK_ROOT_LOCK_NAME).read_text(encoding="utf-8") == "", "the released lock still names a holder"
    with lock_work_root(root):
        pass


@pytest.mark.parametrize("make", [pytest.param(case.values[0], id=case.id) for case in REPLACED_WORK_ROOTS])
def test_bump_check_scratch_avoids_an_unusable_work_root(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, case_temporary_directory: Path, make: Callable
) -> None:
    repository = work_root_case / "repository"
    repository.mkdir()
    for args in (("init", "-q"), ("commit", "-q", "--allow-empty", "-m", "base")):
        subprocess.run([*GIT_LOCAL, *args], cwd=repository, check=True, capture_output=True, timeout=60)
    configured, absent = make(work_root_case, monkeypatch)
    _set_work_root_variable(monkeypatch, configured)
    # Every folder mkdtemp makes from here on, through any maker's own wrapper.
    made: list[Path] = []
    make_directory = tempfile.mkdtemp

    def mkdtemp(suffix: str | None = None, prefix: str | None = None, dir: str | Path | None = None) -> str:
        path = make_directory(suffix, prefix, dir)
        made.append(Path(path))
        return path

    monkeypatch.setattr(tempfile, "mkdtemp", mkdtemp)
    try:
        assert baselines.check_bumps("HEAD", repository) == []
    finally:
        _assert_not_created(absent)
    scratch = [path.parent for path in made if path.name.startswith("carddemo-bumps-")]
    assert scratch == [case_temporary_directory], f"the scratch folder is made in {scratch}"


# The scenario whose working directory the runner cases make and fill.
RUNNER_SCENARIO_ID = "UTL-CSUTLDTC-normal-01"
# What a case puts outside the work root, which nothing may touch.
RUNNER_OUTSIDE_FILE = "kept.txt"
RUNNER_OUTSIDE_TEXT = "kept\n"


def _runner_case(case: Path) -> tuple[Path, Path]:
    """A private work root of the case's own and a folder beside it, outside
    it, holding ``RUNNER_OUTSIDE_FILE``."""
    root = case / "root"
    root.mkdir(mode=0o700)
    outside = case / "outside"
    outside.mkdir(mode=0o700)
    (outside / RUNNER_OUTSIDE_FILE).write_text(RUNNER_OUTSIDE_TEXT, encoding="utf-8")
    return root, outside


def _assert_outside_untouched(outside: Path) -> None:
    assert sorted(path.name for path in outside.iterdir()) == [RUNNER_OUTSIDE_FILE], sorted(outside.iterdir())
    assert (outside / RUNNER_OUTSIDE_FILE).read_text(encoding="utf-8") == RUNNER_OUTSIDE_TEXT


@pytest.mark.parametrize(
    ("mode", "foreign", "reason"),
    [
        pytest.param(0o770, False, "is writable by group or others (mode 0770)", id="group-writable"),
        pytest.param(0o703, False, "is writable by group or others (mode 0703)", id="other-writable"),
        pytest.param(0o700, True, "is owned by uid", id="foreign-owner"),
    ],
)
def test_fresh_workdir_refuses_a_work_root_others_can_write(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, mode: int, foreign: bool, reason: str
) -> None:
    """The runner holds the work root an adapter names, however the adapter
    was made, to the admission's rule on its open descriptor: one group or
    others can write to, or of another owner (``os.geteuid`` simulated), is
    refused before the working directory is made."""
    root, _ = _runner_case(work_root_case)
    os.chmod(root, mode)
    if foreign:
        owner = os.stat(root).st_uid
        monkeypatch.setattr(os, "geteuid", lambda: owner + 1)
    with pytest.raises(HarnessError) as refused:
        runner._fresh_workdir(SimpleNamespace(work_root=root), RUNNER_SCENARIO_ID)
    assert str(refused.value).startswith(f"{root} {reason}"), refused.value
    assert str(refused.value).endswith("choose another work root"), refused.value
    assert not any(root.iterdir()), "a working directory was made in a refused work root"


def test_the_runner_opens_directories_as_the_admission_does() -> None:
    """The runner opens the work root, each working directory and its
    ``inputs/`` with the helper ``usable_work_root`` admits a root by, so a
    root admitted is one the runner can open; it opens for reading, as a
    directory, without following a symbolic link, by no child process."""
    assert runner._open_directory is harness.open_directory
    assert harness.DIRECTORY_OPEN_FLAGS == os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC


# Modes of a work root of the user's own that the user cannot use as the
# runner does: without read permission the runner cannot open it, without
# search permission nothing can be made or looked up in it.
INACCESSIBLE_WORK_ROOT_MODES = [
    pytest.param(0o300, id="0300-no-read"),
    pytest.param(0o600, id="0600-no-search"),
]
# What a refusal for want of permission states after its cause.
WORK_ROOT_ACCESS_RULE = "a work root must be a directory you own and can read, write and search (chmod u+rwx)"
# Each admission such a root meets, and the runner, which refuses it at use.
WORK_ROOT_ACCESS_CALLERS = ["usable_work_root", "conftest", "capture-variable", "capture-option", "runner"]
# The most seconds the child process judging such a root may take.
WORK_ROOT_ACCESS_TIMEOUT_S = 120


def _work_root_access_outcome(tests_root: str, caller: str, root: str, temporary: str, scenario_id: str) -> dict:
    """What ``caller`` does with the work root ``root``, as JSON-ready
    observations: ``usable_work_root``'s verdict; conftest's session (its
    root's path, mode and entries, whether it made the root, the config-time
    warnings, whether ``pytest_unconfigure`` removed the root);
    ``capture.py``'s ``CARDDEMO_TEST_WORKDIR`` path (the same, with stderr
    and ``_discard``) and ``--work-root`` path (exit status and stderr, the
    capture refused should the root be accepted); or the exception
    ``runner._fresh_workdir`` refuses the root with at use. A fresh temporary
    root is made in ``temporary``, and ``CARDDEMO_ADAPTER`` is unset. It
    imports what it needs and restores what it changes (the environment,
    ``tempfile.tempdir``, ``capture.make_adapter``), because it runs in the
    test process or, from its source, in a child process that cannot
    override file permissions."""
    import contextlib
    import io
    import os
    import sys
    import tempfile
    from types import SimpleNamespace

    if tests_root not in sys.path:
        sys.path.insert(0, tests_root)
    import pytest

    import conftest
    import harness
    from harness import capture, runner

    def made_root(path: os.PathLike) -> dict:
        # Permission bits only: a setgid bit is inherited from the folder above.
        return {"root": str(path), "mode": os.stat(path).st_mode & 0o777, "entries": sorted(os.listdir(path))}

    def refuse_capture(work_root: os.PathLike) -> None:
        raise harness.HarnessError(f"the work root {work_root} was accepted, so the capture would run")

    names = (conftest.WORKDIR_ENV, conftest.ADAPTER_ENV)
    saved_environment = {name: os.environ.get(name) for name in names}
    saved_tempdir, saved_make_adapter = tempfile.tempdir, capture.make_adapter
    outcome: dict = {}
    try:
        for name in names:
            os.environ.pop(name, None)
        tempfile.tempdir = temporary
        if caller in ("conftest", "capture-variable"):
            os.environ[conftest.WORKDIR_ENV] = root
        if caller == "usable_work_root":
            try:
                outcome["admitted"] = str(harness.usable_work_root(root))
            except harness.HarnessError as exc:
                outcome["refused"] = str(exc)
        elif caller == "conftest":
            given: list = []
            config = SimpleNamespace(
                stash=pytest.Stash(),
                issue_config_time_warning=lambda warning, stacklevel: given.append(warning),
            )
            conftest.pytest_configure(config)
            session_root = config.stash[conftest.WORK_ROOT_KEY]
            outcome.update(made_root(session_root))
            outcome["created"] = config.stash.get(conftest.CREATED_WORK_ROOT_KEY, None) == session_root
            outcome["warnings"] = [[type(warning).__name__, str(warning)] for warning in given]
            conftest.pytest_unconfigure(config)
            outcome["removed"] = not os.path.lexists(session_root)
        elif caller == "capture-variable":
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                capture_root, created = capture._work_root(None)
                outcome.update(made_root(capture_root))
                outcome["created"] = created
                capture._discard(capture_root, created, [])
            outcome["stderr"] = error.getvalue()
            outcome["removed"] = not os.path.lexists(capture_root)
        elif caller == "capture-option":
            capture.make_adapter = refuse_capture
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                try:
                    outcome["exit"] = capture.main([scenario_id, "--work-root", root])
                except SystemExit as exc:
                    outcome["exit"] = exc.code
            outcome["stderr"] = error.getvalue()
        elif caller == "runner":
            try:
                runner._fresh_workdir(SimpleNamespace(work_root=root), scenario_id)
            except Exception as exc:
                outcome.update(
                    error=type(exc).__name__,
                    harness_error=isinstance(exc, harness.HarnessError),
                    errno=getattr(exc, "errno", None),
                    message=str(exc),
                )
        else:
            raise ValueError(f"no such caller: {caller!r}")
    finally:
        for name, value in saved_environment.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
        tempfile.tempdir, capture.make_adapter = saved_tempdir, saved_make_adapter
    return outcome


def _overrides_file_permissions(case: Path) -> bool:
    """Whether this process opens a directory its mode denies it reading, as
    root, or a process holding CAP_DAC_OVERRIDE or CAP_DAC_READ_SEARCH, does;
    such a process must judge a permission case in a child without those
    privileges."""
    probe = case / "permission-probe"
    probe.mkdir(mode=0o700)
    try:
        os.chmod(probe, 0o300)
        try:
            os.close(os.open(probe, harness.DIRECTORY_OPEN_FLAGS))
        except PermissionError:
            return False
        return True
    finally:
        os.chmod(probe, 0o700)
        probe.rmdir()


def _judge_work_root_access(case: Path, caller: str, root: Path, temporary: Path) -> dict:
    """``_work_root_access_outcome`` with file permissions enforced: in this
    process when it does not override them, else in a child started by
    ``setpriv`` without capabilities (still the same user, so the owner's
    mode bits apply), which prints the outcome as its last line. Skipped
    when this process overrides permissions and ``setpriv`` is absent."""
    arguments = (str(TESTS_ROOT), caller, str(root), str(temporary), RUNNER_SCENARIO_ID)
    if not _overrides_file_permissions(case):
        return _work_root_access_outcome(*arguments)
    setpriv = shutil.which("setpriv")
    if setpriv is None:
        pytest.skip(
            "this process overrides file permissions (euid 0 or a DAC capability) and setpriv, which starts a "
            "child without them, is absent, so a root its owner cannot read, write and search cannot be judged"
        )
    source = (
        "from __future__ import annotations\nimport json, sys\n\n"
        f"{inspect.getsource(_work_root_access_outcome)}\n"
        "print(json.dumps(_work_root_access_outcome(*sys.argv[1:])))\n"
    )
    child = subprocess.run(
        [setpriv, "--inh-caps=-all", "--bounding-set=-all", sys.executable, "-B", "-c", source, *arguments],
        cwd=case,
        env={**os.environ, "TMPDIR": str(temporary)},
        capture_output=True,
        text=True,
        timeout=WORK_ROOT_ACCESS_TIMEOUT_S,
    )
    assert child.returncode == 0 and child.stdout, child.stdout + child.stderr
    return json.loads(child.stdout.splitlines()[-1])


@pytest.mark.parametrize("caller", WORK_ROOT_ACCESS_CALLERS)
@pytest.mark.parametrize("mode", INACCESSIBLE_WORK_ROOT_MODES)
def test_a_work_root_its_owner_cannot_read_write_and_search_is_refused(
    work_root_case: Path, case_temporary_directory: Path, mode: int, caller: str
) -> None:
    """A work root of the user's own that the user cannot read (0300), so the
    runner cannot open it, or cannot search (0600), so nothing can be made in
    it, is refused by ``usable_work_root`` with the permission rule (``chmod
    u+rwx``), replaced with a warning and a fresh private temporary root by
    conftest and by ``capture.py``'s ``CARDDEMO_TEST_WORKDIR`` path, and a
    usage error as ``--work-root``; nothing is made in it. The runner still
    refuses such a root at use, as one changed since its admission. Judged
    with file permissions enforced (``_judge_work_root_access``), so the case
    holds when the suite runs as root too."""
    root = work_root_case / "inaccessible"
    root.mkdir(mode=0o700)
    try:
        os.chmod(root, mode)
        outcome = _judge_work_root_access(work_root_case, caller, root, case_temporary_directory)
    finally:
        os.chmod(root, 0o700)
    assert not any(root.iterdir()), "something was made in a refused work root"
    denied = os.strerror(errno.EACCES)
    if mode == 0o300:
        reason = f"{root} cannot be opened: {denied}; {WORK_ROOT_ACCESS_RULE}"
    else:
        reason = f"no directory can be made in {root}: {denied}; {WORK_ROOT_ACCESS_RULE}"
    refused = f"{WORKDIR_VARIABLE}={str(root)!r} is not a usable work root: {reason}"
    if caller == "usable_work_root":
        assert outcome == {"refused": reason}, outcome
    elif caller == "runner":
        if mode == 0o300:
            assert outcome["harness_error"], outcome
            assert outcome["message"] == f"the work root {root} cannot be opened: {denied}", outcome
        else:
            assert (outcome["error"], outcome["errno"]) == ("PermissionError", errno.EACCES), outcome
    elif caller == "capture-option":
        assert outcome["exit"] == 2, outcome
        assert f"--work-root {str(root)!r} is not a usable work root: {reason}" in outcome["stderr"], outcome
    else:
        made = Path(outcome["root"])
        assert made.name.startswith("carddemo-") and made.parent == case_temporary_directory, outcome
        assert outcome["mode"] == 0o700 and outcome["entries"] == [] and outcome["created"], outcome
        assert outcome["removed"], f"the empty temporary work root {made} was kept"
        if caller == "conftest":
            assert outcome["warnings"] == [
                ["PytestConfigWarning", f"{refused}; this session uses the fresh temporary work root {made} instead"]
            ], outcome
        else:
            assert outcome["stderr"] == (
                f"capture.py: warning: {refused}; a new temporary work root is used instead\n"
            ), outcome


def _plant_link_to_folder(workdir: Path, outside: Path) -> None:
    workdir.symlink_to(outside, target_is_directory=True)


def _plant_link_to_file(workdir: Path, outside: Path) -> None:
    workdir.symlink_to(outside / RUNNER_OUTSIDE_FILE)


def _plant_regular_file(workdir: Path, outside: Path) -> None:
    workdir.write_text("left by a previous run\n", encoding="utf-8")


def _plant_folder_linking_outward(workdir: Path, outside: Path) -> None:
    workdir.mkdir()
    (workdir / "inputs").symlink_to(outside, target_is_directory=True)
    (workdir / "stale.dat").write_bytes(b"left by a previous run")


@pytest.mark.parametrize(
    "plant",
    [
        pytest.param(_plant_link_to_folder, id="link-to-folder"),
        pytest.param(_plant_link_to_file, id="link-to-file"),
        pytest.param(_plant_regular_file, id="regular-file"),
        pytest.param(_plant_folder_linking_outward, id="folder-linking-outward"),
    ],
)
def test_fresh_workdir_removes_what_is_left_without_following_links(
    work_root_case: Path, plant: Callable[[Path, Path], None]
) -> None:
    """Whatever is left at ``<root>/<id>``, a link leading outside the root
    included, is removed without following a link, so what it leads to is
    untouched, and replaced by an empty real directory of mode 0700 whose
    identity is returned."""
    root, outside = _runner_case(work_root_case)
    workdir = root / RUNNER_SCENARIO_ID
    plant(workdir, outside)
    made, identity = runner._fresh_workdir(SimpleNamespace(work_root=root), RUNNER_SCENARIO_ID)
    status = os.lstat(workdir)
    assert made == workdir and stat.S_ISDIR(status.st_mode), made
    # Permission bits only: a setgid bit is inherited from the work root.
    assert status.st_mode & 0o777 == 0o700 and (status.st_dev, status.st_ino) == identity
    assert not any(workdir.iterdir())
    _assert_outside_untouched(outside)


def _keep_workdir(workdir: Path, outside: Path, aside: Path) -> None:
    return None


def _replace_workdir_with_outward_link(workdir: Path, outside: Path, aside: Path) -> None:
    workdir.rename(aside)
    workdir.symlink_to(outside, target_is_directory=True)


def _replace_workdir_with_another_folder(workdir: Path, outside: Path, aside: Path) -> None:
    workdir.rename(aside)
    workdir.mkdir(mode=0o700)


def _plant_inputs_link(workdir: Path, outside: Path, aside: Path) -> None:
    (workdir / runner.INPUTS_DIR).symlink_to(outside, target_is_directory=True)


@pytest.mark.parametrize(
    ("substitute", "refusal"),
    [
        pytest.param(_keep_workdir, None, id="kept"),
        pytest.param(_replace_workdir_with_outward_link, "is a symbolic link", id="replaced-by-outward-link"),
        pytest.param(
            _replace_workdir_with_another_folder,
            "is no longer the directory made for this run: it was replaced, so nothing is written there",
            id="replaced-by-another-folder",
        ),
        pytest.param(_plant_inputs_link, FileExistsError, id="inputs-link-planted"),
    ],
)
def test_materialize_writes_only_into_the_directory_made_for_the_run(
    work_root_case: Path, substitute: Callable[[Path, Path, Path], None], refusal: str | type | None
) -> None:
    """``inputs/`` gets the bytes ``fixtures.build`` gives and the YAML
    ``dump_yaml`` gives, in the working directory ``_fresh_workdir`` made. A
    working directory replaced since, by a link leading outside the root or
    by another directory, is refused naming it, and a link planted at
    ``inputs`` is never written through; the outside folder, the directory
    set aside and the replacement receive nothing."""
    scenario = scenarios.get(RUNNER_SCENARIO_ID)
    root, outside = _runner_case(work_root_case)
    aside = work_root_case / "aside"
    workdir, identity = runner._fresh_workdir(SimpleNamespace(work_root=root), scenario.id)
    substitute(workdir, outside, aside)
    if refusal is None:
        data = runner._materialize(scenario, workdir, identity)
        assert data == fixtures.build(scenario)
        inputs = workdir / runner.INPUTS_DIR
        names = [f"{logical}.dat" for logical in data]
        assert sorted(path.name for path in inputs.iterdir()) == sorted(
            [*names, runner.DATASETS_FILE, runner.PARAMETERS_FILE]
        )
        for logical, payload in data.items():
            assert (inputs / f"{logical}.dat").read_bytes() == payload
        listing = (inputs / runner.DATASETS_FILE).read_bytes()
        assert listing == dump_yaml(load_yaml(inputs / runner.DATASETS_FILE)).encode("utf-8")
        assert sorted(load_yaml(inputs / runner.DATASETS_FILE)) == sorted(data)
        assert (inputs / runner.PARAMETERS_FILE).read_bytes() == dump_yaml(
            runner._parameters(scenario, data)
        ).encode("utf-8")
    elif isinstance(refusal, str):
        with pytest.raises(HarnessError) as refused:
            runner._materialize(scenario, workdir, identity)
        assert str(refused.value) == f"the working directory {workdir} {refusal}", refused.value
    else:
        with pytest.raises(refusal):
            runner._materialize(scenario, workdir, identity)
    _assert_outside_untouched(outside)
    if aside.exists():
        assert not any(aside.iterdir()), "the directory set aside received inputs"
    if refusal is not None and not workdir.is_symlink():
        assert sorted(path.name for path in workdir.iterdir()) == (
            [runner.INPUTS_DIR] if substitute is _plant_inputs_link else []
        ), sorted(workdir.iterdir())


MARKERS = {"unit", "integration", "e2e", "chain", "gnucobol_batch", "unikix"}


def test_markers_registered(pytestconfig: pytest.Config) -> None:
    # getini("markers") also lists pytest's own markers (skip, xfail,
    # parametrize, ...), so "exactly these six" holds for the markers the
    # suite's ini file registers, all of which pytest must have read.
    ini = configparser.ConfigParser(interpolation=None)
    ini.read(pytestconfig.inipath, encoding="utf-8")
    registered = {line.split(":", 1)[0].strip() for line in ini["pytest"]["markers"].splitlines() if line.strip()}
    assert registered == MARKERS
    assert MARKERS <= {line.split(":", 1)[0].split("(", 1)[0].strip() for line in pytestconfig.getini("markers")}
    assert "--strict-markers" in pytestconfig.getini("addopts")


# The documented launch configuration: the README's local-setup blocks run as
# a developer pastes them, into a shell without errexit, with stubs first on
# PATH that log each call; every file lands in the case's own folder.
README_FILE = TESTS_ROOT / "README.md"
LAUNCH_TIMEOUT_S = 60
GNUCOBOL_URL = "https://ftp.gnu.org/gnu/gnucobol/gnucobol-3.2.tar.xz"
GNUCOBOL_CHECKSUM_LINE = "3bb48af46ced4779facf41fdc2ee60e4ccb86eaa99d010b36685315df39c2ee2  gnucobol-3.2.tar.xz\n"
# curl as the README calls it (-o <file>), writing an archive that is not
# GnuCOBOL's; or failing as curl -f does on an HTTP error, writing nothing.
CURL_WRITES_JUNK = """out=
while [ "$#" -gt 0 ]; do
  if [ "$1" = -o ]; then out=$2; shift; fi
  shift
done
[ -n "$out" ] || exit 2
printf 'not the GnuCOBOL 3.2 archive\\n' > "$out"
"""
CURL_FAILS = "exit 22\n"
COBC_ANSWERS = """case $1 in
  --version) echo 'cobc (GnuCOBOL) 3.2.0' ;;
  --info) echo 'indexed file handler          : BDB' ;;
esac
"""
# (download_fails, checksum_passes): only the verified archive reaches tar.
README_BUILD_CASES = [
    pytest.param(False, False, id="checksum-mismatch"),
    pytest.param(True, False, id="download-fails"),
    pytest.param(False, True, id="verified"),
]


def _readme_bash_block(heading: str, holding: str) -> str:
    """The one fenced bash block of the README section ``heading`` (its
    ``## `` line) that holds ``holding``."""
    sections = re.split(r"(?m)^(?=## )", README_FILE.read_text(encoding="utf-8"))
    section = [part for part in sections if part.startswith(f"{heading}\n")]
    assert len(section) == 1, f"tests/README.md has {len(section)} sections {heading!r}"
    blocks = [block for block in re.findall(r"(?ms)^```bash\n(.*?)^```$", section[0]) if holding in block]
    assert len(blocks) == 1, f"{heading!r} has {len(blocks)} bash blocks holding {holding!r}"
    return blocks[0]


def _stub_log_line(name: str) -> str:
    """Shell text appending ``name``, the working directory and every argument,
    tab separated, as one line to ``$STUB_LOG``."""
    return (
        f"{{ printf '%s\\t%s' {shlex.quote(name)} \"$PWD\"; "
        "for arg in \"$@\"; do printf '\\t%s' \"$arg\"; done; printf '\\n'; } >> \"$STUB_LOG\"\n"
    )


# _run_bash puts the case's bin/ ahead of the real PATH, which is split at
# ':', so a bin/ whose path holds one is never searched and the real command
# of each stub would run instead: sudo, curl, make, pip.
README_STUB_PATH_SKIP = (
    "the session work root's path holds ':', at which PATH is split, so the case's stub folder inside "
    "it cannot go on PATH and the real commands would run in place of the stubs; run under a work "
    "root without ':' to cover this case"
)


def _stub_folder(case: Path) -> Path:
    """``case/bin``, made empty, the folder ``_run_bash`` puts first on
    ``PATH``; the case is skipped (README_STUB_PATH_SKIP) before anything is
    made when its path holds ``os.pathsep``."""
    folder = case / "bin"
    if os.pathsep in str(folder):
        pytest.skip(README_STUB_PATH_SKIP)
    folder.mkdir()
    return folder


def _logging_stub(folder: Path, name: str, then: str = "") -> Path:
    """An executable ``folder/name`` that logs its call, then runs ``then``."""
    path = folder / name
    path.write_text(f"#!/bin/sh\n{_stub_log_line(name)}{then}", encoding="utf-8")
    path.chmod(0o755)
    return path


def _stub_calls(case: Path) -> list[tuple[str, Path, list[str]]]:
    """The calls the case's stubs logged, in order: name, working directory,
    arguments."""
    log = case / "stub.log"
    if not log.exists():
        return []
    calls = []
    for line in log.read_text(encoding="utf-8").splitlines():
        name, cwd, *arguments = line.split("\t")
        calls.append((name, Path(cwd), arguments))
    return calls


def _run_bash(case: Path, *arguments: str, **variables: str) -> subprocess.CompletedProcess[str]:
    """``bash --noprofile --norc`` with ``arguments``, run in the case folder
    with HOME, TMPDIR and the stub log inside it, its stub folder ahead of the
    real PATH, and ``variables``."""
    bash = shutil.which("bash")
    assert bash is not None, "bash is not on PATH"
    for folder in ("bin", "home", "tmp"):
        (case / folder).mkdir(exist_ok=True)
    environment = {
        "HOME": str(case / "home"),
        "TMPDIR": str(case / "tmp"),
        "PATH": f"{case / 'bin'}{os.pathsep}{os.environ['PATH']}",
        "STUB_LOG": str(case / "stub.log"),
        **variables,
    }
    return subprocess.run(
        [bash, "--noprofile", "--norc", *arguments],
        cwd=case,
        env=environment,
        capture_output=True,
        text=True,
        timeout=LAUNCH_TIMEOUT_S,
        check=False,
    )


@pytest.mark.parametrize(("download_fails", "checksum_passes"), README_BUILD_CASES)
def test_readme_gnucobol_build_fails_closed(work_root_case: Path, download_fails: bool, checksum_passes: bool) -> None:
    # The block must stop by itself: the pasting shell has no errexit, so the
    # cobc lines after the subshell run in every case, and only a verified
    # archive is extracted, configured and built.
    block = _readme_bash_block("## Local setup", "sha256sum -c")
    stubs = _stub_folder(work_root_case)
    _logging_stub(stubs, "sudo")
    _logging_stub(stubs, "curl", CURL_FAILS if download_fails else CURL_WRITES_JUNK)
    if checksum_passes:
        _logging_stub(stubs, "sha256sum", 'cat > "$STUB_LOG.input"\n')
    _logging_stub(
        stubs,
        "tar",
        f"mkdir gnucobol-3.2\ncat > gnucobol-3.2/configure <<'CONFIGURE'\n#!/bin/sh\n{_stub_log_line('configure')}"
        "CONFIGURE\nchmod 755 gnucobol-3.2/configure\n",
    )
    _logging_stub(stubs, "make")
    _logging_stub(stubs, "cobc", COBC_ANSWERS)
    completed = _run_bash(work_root_case, "-c", block)
    calls = _stub_calls(work_root_case)
    names = [name for name, _, _ in calls]
    shown = (names, completed.stdout, completed.stderr)
    assert names[:2] == ["sudo", "curl"] and names[-2:] == ["cobc", "cobc"], shown
    _, build_dir, curl_arguments = calls[1]
    assert GNUCOBOL_URL in curl_arguments, curl_arguments
    assert build_dir.parent == work_root_case / "tmp", f"curl ran in {build_dir}, not a fresh folder of TMPDIR"
    if download_fails or not checksum_passes:
        assert not {"tar", "configure", "make"} & set(names), shown
        assert not (build_dir / "gnucobol-3.2").exists()
        if download_fails:
            assert not (build_dir / "gnucobol-3.2.tar.xz").exists()
        else:
            assert "gnucobol-3.2.tar.xz: FAILED" in completed.stdout, shown
        return
    assert names == ["sudo", "curl", "sha256sum", "tar", "configure", "make", "make", "cobc", "cobc"], shown
    assert (work_root_case / "stub.log.input").read_text(encoding="utf-8") == GNUCOBOL_CHECKSUM_LINE
    source = build_dir / "gnucobol-3.2"
    assert calls[3][1:] == (build_dir, ["-xJf", "gnucobol-3.2.tar.xz"])
    assert calls[4][1:] == (source, [f"--prefix={work_root_case / 'home' / 'gnucobol-3.2'}", "--with-db"])
    assert calls[5][1] == source and len(calls[5][2]) == 1 and re.fullmatch(r"-j[1-9][0-9]*", calls[5][2][0]), calls[5]
    assert calls[6][1:] == (source, ["install"])
    assert completed.returncode == 0, shown


# python3.11 -m venv <folder> as the README launch calls it: an activate
# script that changes nothing, so the stubs stay first on PATH. It reaches
# the real mkdir past the stubs.
VENV_MAKES_ACTIVATE = """if [ "$1" = -m ] && [ "$2" = venv ]; then
  command -p mkdir -p "$3/bin" && : > "$3/bin/activate"
fi
"""
MKTEMP_FAILS = "echo 'mktemp: failed to create directory' >&2\nexit 1\n"
# Beside a failing mktemp, mkdir makes nothing: a launch that went on would
# otherwise make /_pytest from the empty work root.
MKDIR_MAKES_NOTHING = "exit 1\n"
# pytest as the launch starts it: one JSON line per call to $STUB_LOG.pytest,
# with the temporary root and work root it was given and what lstat sees there.
PYTEST_RECORDER = """import json, os, sys


def seen(path):
    if not path:
        return None
    try:
        status = os.lstat(path)
    except OSError as exc:
        return {"error": exc.strerror}
    return {"mode": status.st_mode, "uid": status.st_uid}


temproot = os.environ.get("PYTEST_DEBUG_TEMPROOT")
workdir = os.environ.get("CARDDEMO_TEST_WORKDIR")
with open(os.environ["STUB_LOG"] + ".pytest", "a", encoding="utf-8") as log:
    log.write(json.dumps({"argv": sys.argv[1:], "temproot": temproot, "workdir": workdir,
                          "temproot_seen": seen(temproot), "workdir_seen": seen(workdir)}) + "\\n")
"""
# Whether mktemp fails, as when TMPDIR cannot take a new folder.
README_LAUNCH_CASES = [
    pytest.param(False, id="fresh-work-root"),
    pytest.param(True, id="mktemp-fails"),
]


def _owner_only_directory(seen: Mapping[str, int] | None) -> bool:
    """Whether an lstat record is a real directory (no symbolic link) owned by
    the effective user whose permission bits are 0700. A set-group-ID bit it
    inherits from its parent, as under a set-group-ID /tmp, grants no access."""
    return (
        seen is not None
        and "mode" in seen
        and stat.S_ISDIR(seen["mode"])
        and seen["mode"] & 0o777 == 0o700
        and seen["uid"] == os.geteuid()
    )


@pytest.mark.parametrize("mktemp_fails", README_LAUNCH_CASES)
def test_readme_launch_gives_pytest_a_private_temp_root(work_root_case: Path, mktemp_fails: bool) -> None:
    # The pinned pytest's default temporary root, <temp dir>/pytest-of-<user>,
    # is predictable (CVE-2025-71176): the launch starts pytest once, only with
    # PYTEST_DEBUG_TEMPROOT naming a new owner-only folder of a fresh owner-only
    # work root, and never when that root cannot be made.
    block = _readme_bash_block("## Local setup", "pytest tests/")
    stubs = _stub_folder(work_root_case)
    _logging_stub(stubs, "python3.11", VENV_MAKES_ACTIVATE)
    _logging_stub(stubs, "pip")
    if mktemp_fails:
        _logging_stub(stubs, "mktemp", MKTEMP_FAILS)
        _logging_stub(stubs, "mkdir", MKDIR_MAKES_NOTHING)
    recorder = stubs / "pytest"
    recorder.write_text(f"#!{sys.executable}\n{PYTEST_RECORDER}", encoding="utf-8")
    recorder.chmod(0o755)
    completed = _run_bash(work_root_case, "-c", block)
    calls = _stub_calls(work_root_case)
    runs_file = work_root_case / "stub.log.pytest"
    runs = []
    if runs_file.exists():
        runs = [json.loads(line) for line in runs_file.read_text(encoding="utf-8").splitlines()]
    shown = ([name for name, _, _ in calls], runs, completed.stdout, completed.stderr)
    home = work_root_case / "home"
    assert calls[0][0] == "python3.11" and calls[0][2] == ["-m", "venv", f"{home}/venvs/carddemo"], shown
    assert calls[1][0] == "pip" and calls[1][2] == ["install", "-r", "tests/requirements.txt"], shown
    if mktemp_fails:
        assert [name for name, _, _ in calls[2:]] == ["mktemp"] and runs == [], shown
        assert completed.returncode != 0, shown
        assert list((work_root_case / "tmp").iterdir()) == []
        return
    assert len(runs) == 1 and runs[0]["argv"] == ["tests/"], shown
    assert runs[0]["workdir"] and runs[0]["temproot"], "pytest ran without its work root or temporary root"
    workdir, temproot = Path(runs[0]["workdir"]), Path(runs[0]["temproot"])
    assert temproot.parent == workdir and temproot.name == "_pytest", shown
    assert list((work_root_case / "tmp").iterdir()) == [workdir], "the work root is not a fresh folder of TMPDIR"
    assert _owner_only_directory(runs[0]["temproot_seen"]), runs[0]["temproot_seen"]
    assert _owner_only_directory(runs[0]["workdir_seen"]), runs[0]["workdir_seen"]
    assert completed.returncode == 0, shown


WORKFLOW_FILE = harness.REPO_ROOT / ".github" / "workflows" / "carddemo-tests.yml"
# The action releases the suite pins, each by its exact tag.
PINNED_ACTIONS = (
    "actions/cache@v6.1.0",
    "actions/checkout@v7.0.1",
    "actions/setup-python@v7.1.0",
    "actions/upload-artifact@v7.0.2",
)


def _workflow() -> dict:
    """The workflow as YAML loads it (its ``on`` key loads as ``True``)."""
    workflow = yaml.safe_load(WORKFLOW_FILE.read_text(encoding="utf-8"))
    assert list(workflow["jobs"]) == ["characterization"], list(workflow["jobs"])
    return workflow


def test_workflow_actions_take_tls13_only() -> None:
    # The pinned action releases bundle an undici that a TLS 1.2 peer
    # renegotiating with another certificate ends on an assertion
    # (CVE-2026-87018). Their Node processes take TLS 1.3 only, from the job's
    # env, since the runner refuses NODE_OPTIONS from GITHUB_ENV, and nothing
    # else sets NODE_OPTIONS.
    workflow = _workflow()
    job = workflow["jobs"]["characterization"]
    environment = job.get("env") or {}
    assert "NODE_OPTIONS" in environment, "the characterization job's env sets no NODE_OPTIONS"
    options = str(environment["NODE_OPTIONS"]).split()
    assert [option for option in options if option.startswith("--tls-")] == ["--tls-min-v1.3"], options
    assert "NODE_OPTIONS" not in (workflow.get("env") or {})
    for step in job["steps"]:
        assert "NODE_OPTIONS" not in (step.get("env") or {}), step["name"]
        assert "NODE_OPTIONS" not in step.get("run", ""), step["name"]
    setting = [
        line.strip()
        for line in WORKFLOW_FILE.read_text(encoding="utf-8").splitlines()
        if "NODE_OPTIONS" in line and not line.lstrip().startswith("#")
    ]
    assert setting == ["NODE_OPTIONS: --tls-min-v1.3"], setting
    used = sorted(step["uses"] for step in job["steps"] if "uses" in step)
    assert used == list(PINNED_ACTIONS), used


# (runner umask, what already stands at <RUNNER_TEMP>/carddemo-work, what
# already stands at its _pytest) when the Work root step runs. Either may be
# nothing, an owner-only folder or a symbolic link to one; the work root may
# also be a folder group can write (0775), as mkdir makes one under umask 002.
WORK_ROOT_STEP_CASES = [
    pytest.param(0o022, None, None, id="fresh-runner-temp"),
    pytest.param(0o002, None, None, id="fresh-runner-temp-umask-002"),
    pytest.param(0o022, "directory", None, id="work-root-exists"),
    pytest.param(0o022, "group-writable", None, id="work-root-group-writable"),
    pytest.param(0o022, "symlink", None, id="work-root-is-a-link"),
    pytest.param(0o022, "directory", "directory", id="temp-root-exists"),
    pytest.param(0o022, "directory", "symlink", id="temp-root-is-a-link"),
]


def _workflow_step(name: str) -> dict:
    """The characterization job's one step called ``name``."""
    steps = [step for step in _workflow()["jobs"]["characterization"]["steps"] if step.get("name") == name]
    assert len(steps) == 1, f"the workflow has {len(steps)} steps named {name!r}"
    return steps[0]


def test_workflow_temp_root_precedes_runs_and_stays_out_of_upload() -> None:
    # PYTEST_DEBUG_TEMPROOT is exported before either pytest run starts, and
    # the artifact, which carries the failing scenarios' folders of the work
    # root, leaves pytest's temporary root out.
    names = [step.get("name") for step in _workflow()["jobs"]["characterization"]["steps"]]
    assert names.index("Work root") < names.index("Run 1") < names.index("Run 2"), names
    assert "PYTEST_DEBUG_TEMPROOT=" in _workflow_step("Work root")["run"]
    paths = _workflow_step("Upload reports")["with"]["path"].splitlines()
    assert "${{ env.CARDDEMO_TEST_WORKDIR }}" in paths, paths
    assert "!${{ env.CARDDEMO_TEST_WORKDIR }}/_pytest" in paths, paths


@pytest.mark.parametrize(("umask", "planted_root", "planted"), WORK_ROOT_STEP_CASES)
def test_workflow_work_root_step_makes_a_private_temp_root(
    work_root_case: Path, umask: int, planted_root: str | None, planted: str | None
) -> None:
    # Run as Actions runs a `shell: bash` step, under the runner's umask. The
    # step makes the work root owner-only whatever the umask, so the session
    # admits the root CARDDEMO_TEST_WORKDIR names instead of replacing it,
    # and fails unless the root is then the runner user's own 0700 folder,
    # not a link. It exports a temporary root only once it has made it, a new
    # owner-only folder, and fails when anything already stands there, a
    # symbolic link included.
    script = work_root_case / "work-root-step.sh"
    script.write_text(_workflow_step("Work root")["run"], encoding="utf-8")
    runner_temp = work_root_case / "runner-temp"
    runner_temp.mkdir()
    github_env = work_root_case / "github-env"
    github_env.touch()
    root = runner_temp / "carddemo-work"
    temp_root = root / "_pytest"
    elsewhere = work_root_case / "elsewhere"
    if planted_root == "symlink":
        elsewhere.mkdir(mode=0o700)
        elsewhere.chmod(0o700)
        root.symlink_to(elsewhere, target_is_directory=True)
    elif planted_root is not None:
        root.mkdir(mode=0o700)
        root.chmod(0o775 if planted_root == "group-writable" else 0o700)
    if planted == "directory":
        temp_root.mkdir()
    elif planted == "symlink":
        elsewhere.mkdir()
        elsewhere.chmod(0o755)
        temp_root.symlink_to(elsewhere, target_is_directory=True)
    previous = os.umask(umask)
    try:
        completed = _run_bash(
            work_root_case, "-eo", "pipefail", str(script), RUNNER_TEMP=str(runner_temp), GITHUB_ENV=str(github_env)
        )
    finally:
        os.umask(previous)
    exported = github_env.read_text(encoding="utf-8").splitlines()
    shown = (exported, completed.stdout, completed.stderr)
    if planted_root in ("group-writable", "symlink") or planted is not None:
        # CARDDEMO_TEST_WORKDIR is exported first, so the upload paths stay valid.
        assert completed.returncode != 0, shown
        assert exported == [f"CARDDEMO_TEST_WORKDIR={root}"], shown
        if planted is not None:
            assert "File exists" in completed.stderr, shown
            if planted == "symlink":
                assert temp_root.is_symlink() and stat.S_IMODE(elsewhere.stat().st_mode) == 0o755
                assert list(elsewhere.iterdir()) == []
            return
        assert "must be a directory of the runner user with mode 0700" in completed.stdout, shown
        if planted_root == "symlink":
            assert root.is_symlink() and stat.S_IMODE(elsewhere.stat().st_mode) == 0o700
            assert list(elsewhere.iterdir()) == []
        else:
            assert stat.S_IMODE(os.lstat(root).st_mode) == 0o775, "the step changed the refused root's mode"
            assert list(root.iterdir()) == []
        return
    assert completed.returncode == 0, shown
    assert exported == [
        f"CARDDEMO_TEST_WORKDIR={root}",
        f"PYTEST_DEBUG_TEMPROOT={temp_root}",
    ], shown
    for folder in (root, temp_root):
        status = os.lstat(folder)
        assert _owner_only_directory({"mode": status.st_mode, "uid": status.st_uid}), (folder, oct(status.st_mode))
    # The session's admission takes the root as it stands, so no fresh one replaces it.
    assert usable_work_root(root) == root


# CARDDEMO_ADAPTER value (None: unset) -> the adapter it selects, None when
# it is refused. Only the unset variable selects the default; every supplied
# value must name an adapter exactly, the empty and the blank one included.
ADAPTER_SELECTIONS = [
    pytest.param(None, "gnucobol-batch", id="unset"),
    pytest.param("gnucobol-batch", "gnucobol-batch", id="gnucobol-batch"),
    pytest.param("unikix", "unikix", id="unikix"),
    pytest.param("", None, id="empty"),
    pytest.param(" ", None, id="blank"),
    pytest.param("foo", None, id="unknown"),
    pytest.param("GNUCOBOL-BATCH", None, id="upper-case"),
    pytest.param("gnucobol_batch", None, id="marker-name"),
]
ADAPTER_REFUSAL = "CARDDEMO_ADAPTER must be 'gnucobol-batch' or 'unikix'"


def _set_adapter(monkeypatch: pytest.MonkeyPatch, value: str | None) -> None:
    if value is None:
        monkeypatch.delenv("CARDDEMO_ADAPTER", raising=False)
    else:
        monkeypatch.setenv("CARDDEMO_ADAPTER", value)


@pytest.mark.parametrize(("value", "selected"), ADAPTER_SELECTIONS)
def test_conftest_adapter_selection(monkeypatch: pytest.MonkeyPatch, value: str | None, selected: str | None) -> None:
    # pytest imports tests/conftest.py as the module `conftest`.
    import conftest

    _set_adapter(monkeypatch, value)
    if selected is not None:
        assert conftest._selected_adapter() == selected
        return
    with pytest.raises(pytest.UsageError, match=re.escape(ADAPTER_REFUSAL)) as refused:
        conftest._selected_adapter()
    assert f"(got {value!r})" in str(refused.value)


ADAPTER_OVER_LONG = "x" * 300


@pytest.mark.parametrize(
    ("value", "selected"), [*ADAPTER_SELECTIONS, pytest.param(ADAPTER_OVER_LONG, None, id="over-long")]
)
def test_report_adapter_selection(monkeypatch: pytest.MonkeyPatch, value: str | None, selected: str | None) -> None:
    # The coverage report's Environment section names the adapter by the
    # conftest rule and never copies a refused value into the report.
    _set_adapter(monkeypatch, value)
    if selected is not None:
        assert report._selected_adapter() == selected
        assert report._environment_lines()[0] == f"- Adapter: {selected}"
        return
    with pytest.raises(report.ReportError, match=re.escape(ADAPTER_REFUSAL)) as refused:
        report._environment_lines()
    message = str(refused.value)
    if value == ADAPTER_OVER_LONG:
        assert value not in message and len(message) < len(ADAPTER_REFUSAL) + 100, message
    else:
        assert f"(got {value!r})" in message


COMMANDS_FILE = ADAPTERS_DIR / "unikix" / "commands.yaml"
COMMAND_STATUSES = ("confirmed", "unconfirmed")
# The UniKix actions the adapter needs -> the commands.yaml entries that
# carry them and the missing-input gap they settle once confirmed.
REQUIRED_COMMANDS = {
    "build": (("build_kixclt", "build_vcc_online", "build_vcc_batch", "build_kixbms"), "GAP-MIS-008"),
    "region creation": (("region_create", "region_create_script", "region_install"), "GAP-MIS-003"),
    "start, stop and quiesce": (("region_start", "region_stop", "region_quiesce_query"), "GAP-MIS-004"),
    "3270 listener": (("listener_start", "listener_port"), "GAP-MIS-005"),
    "BPE submission and cancellation": (
        ("bpe_env", "bpe_system", "bpe_translate", "bpe_submit", "bpe_sysout", "bpe_cancel"),
        "GAP-MIS-006",
    ),
    "dataset load, unload and catalog listing": (("dataset_load", "dataset_unload", "catalog_list"), "GAP-MIS-007"),
    "region file control and inquiry": (
        (
            "region_file_close",
            "region_file_open",
            "region_file_disable",
            "region_file_enable",
            "region_file_inquire",
            "region_file_requests",
        ),
        "GAP-MIS-011",
    ),
    "JOBS capture": (("jobs_capture",), "GAP-MIS-017"),
    "clock": (("clock_set",), "GAP-MIS-015"),
}


def test_commands_yaml() -> None:
    document = load_yaml(COMMANDS_FILE)
    assert isinstance(document, dict) and isinstance(document.get("entries"), dict), (
        f"{COMMANDS_FILE} must hold an 'entries' mapping"
    )
    entries = document["entries"]
    gaps = {entry.get("gap_id"): entry for entry in _gap_entries()}
    problems = []
    for entry_id, entry in entries.items():
        if not isinstance(entry, dict):
            problems.append(f"{entry_id}: not a mapping")
            continue
        probe, timeout_s, status, gap = (entry.get(key) for key in ("probe", "timeout_s", "status", "gap"))
        if not isinstance(probe, str) or not probe.strip():
            problems.append(f"{entry_id}: no probe")
        if isinstance(timeout_s, bool) or not isinstance(timeout_s, int) or timeout_s <= 0:
            problems.append(f"{entry_id}: timeout_s {timeout_s!r} is not a positive number of seconds")
        if status not in COMMAND_STATUSES:
            problems.append(f"{entry_id}: status {status!r} is not confirmed or unconfirmed")
        if not isinstance(gap, str) or not GAP_ID_RE.fullmatch(gap) or gap not in gaps:
            problems.append(f"{entry_id}: gap {gap!r} is not an entry of gaps.yaml")
        elif status == "unconfirmed" and gaps[gap].get("status") != "open":
            problems.append(f"{entry_id} is unconfirmed, but its gap {gap} is {gaps[gap].get('status')}")
    for action, (entry_ids, gap) in REQUIRED_COMMANDS.items():
        for entry_id in entry_ids:
            entry = entries.get(entry_id)
            if not isinstance(entry, dict):
                problems.append(f"{action}: no entry {entry_id}")
            elif entry.get("gap") != gap:
                problems.append(f"{action}: {entry_id} settles {entry.get('gap')!r}, not {gap}")
    assert problems == [], "\n".join(problems)


ADAPTER_METHODS = ("capable", "observations", "prepare", "execute", "cleanup")
RESULT_FIELDS = ["return_code", "datasets", "screens", "messages", "elapsed_s"]
ADAPTER_EXCEPTIONS = {base.AdapterUnavailable: ["reason", "gap_id"], base.MissingPrerequisite: ["need", "gap_id"]}


def test_adapters_implement_base() -> None:
    for adapter_class, name in ((GnuCOBOLBatchAdapter, "gnucobol-batch"), (UniKixAdapter, "unikix")):
        assert issubclass(adapter_class, base.Adapter)
        assert not inspect.isabstract(adapter_class), sorted(adapter_class.__abstractmethods__)
        assert adapter_class.name == name
        for method in ADAPTER_METHODS:
            assert callable(getattr(adapter_class, method, None)), f"{adapter_class.__name__}.{method}"
    assert [field.name for field in dataclasses.fields(base.Result)] == RESULT_FIELDS
    for exception, parameters in ADAPTER_EXCEPTIONS.items():
        signature = inspect.signature(exception)
        assert list(signature.parameters) == parameters
        assert all(parameter.default is inspect.Parameter.empty for parameter in signature.parameters.values())
        raised = exception("the runtime is absent", "GAP-MIS-009")
        assert raised.gap_id == "GAP-MIS-009"
        with pytest.raises(TypeError):
            exception("the runtime is absent")


def _require_commit(
    repo_root: Path,
    revision: str,
    remedy: str = "Pass an existing commit to --carddemo-baseline-base and run again.",
) -> None:
    """Fail, never skip, when ``revision`` names no commit of this clone.

    The value reaches git as one argument after ``--end-of-options``, so no
    value is read as an option. The failure quotes the value. An empty value
    and, in a full clone, an unknown revision get ``remedy``; a shallow clone,
    which lacks the history the checks compare with, is told to fetch it."""
    completed = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", "--end-of-options", f"{revision}^{{commit}}"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        timeout=CLI_TIMEOUT_S,
        check=False,
    )
    if completed.returncode == 0:
        return
    unshallow = "fetch the full history (git fetch --unshallow, or actions/checkout with fetch-depth: 0)"
    if not str(revision).strip():
        pytest.fail(f"{revision!r} is an empty revision: it names no commit. {remedy}", pytrace=False)
    shallow = subprocess.run(
        ["git", "rev-parse", "--is-shallow-repository"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        timeout=CLI_TIMEOUT_S,
        check=False,
    )
    answer = shallow.stdout.strip() if shallow.returncode == 0 else ""
    if answer == "false":
        message = f"{revision!r} is an unknown revision: it names no commit of this clone. {remedy}"
    elif answer == "true":
        message = f"{revision!r} is not a commit of this shallow clone's history: {unshallow} and run again"
    else:
        said = (shallow.stderr or shallow.stdout).splitlines()
        reason = next((line.strip() for line in said if line.strip()), f"exit {shallow.returncode}")
        message = (
            f"{revision!r} names no commit of this clone, and git rev-parse --is-shallow-repository "
            f"cannot tell whether the clone is shallow ({reason}). {remedy} "
            f"If the commit exists upstream, {unshallow} and run again."
        )
    pytest.fail(message, pytrace=False)


@pytest.mark.parametrize(
    ("revision", "kind"),
    [
        pytest.param("", "an empty revision", id="empty"),
        pytest.param("   ", "an empty revision", id="blank"),
        pytest.param("nosuchrev-does-not-exist", "an unknown revision", id="unknown"),
        pytest.param("--path-format=absolute", "an unknown revision", id="option-like"),
        pytest.param("HEAD", None, id="HEAD"),
    ],
)
def test_require_commit_names_the_revision(carddemo_repo_root: Path, revision: str, kind: str | None) -> None:
    """An existing commit passes. Any other value fails, never skips, quoting
    the value; only a shallow clone's unknown revision gets the unshallow
    remedy, and an empty value is called empty."""
    if kind is None:
        _require_commit(carddemo_repo_root, revision)
        return
    with pytest.raises(pytest.fail.Exception) as raised:
        _require_commit(carddemo_repo_root, revision)
    assert raised.value.pytrace is False
    message = raised.value.msg
    assert message.startswith(repr(revision)), message
    shallow = subprocess.run(
        ["git", "rev-parse", "--is-shallow-repository"],
        cwd=carddemo_repo_root,
        capture_output=True,
        text=True,
        timeout=CLI_TIMEOUT_S,
        check=False,
    )
    if kind == "an empty revision" or (shallow.returncode == 0 and shallow.stdout.strip() == "false"):
        assert f" is {kind}: " in message, message
        assert "--carddemo-baseline-base" in message, message
        assert "--unshallow" not in message, message
    else:
        assert "git fetch --unshallow" in message, message


def test_baseline_bumps(carddemo_repo_root: Path, carddemo_work_root: Path, carddemo_baseline_base: str) -> None:
    _require_commit(carddemo_repo_root, carddemo_baseline_base)
    _assert_passes(
        _cli(
            carddemo_repo_root,
            "baselines.py",
            "--check-bumps",
            "--base",
            carddemo_baseline_base,
            work_root=carddemo_work_root,
        )
    )


# The prefix of the scratch folder baselines.check_bumps makes for its YAML
# reads; a tracer module that appends the real path of every folder
# tempfile.mkdtemp makes (TemporaryDirectory's included) to the log beside it;
# and the bootstrap a child runs as `python -B -c MKDTEMP_TRACER_BOOTSTRAP
# <tracer file> <script> <args>`. The bootstrap loads the tracer from its file,
# then runs the script as __main__ with the sys.argv, sys.path[0] and exit
# status a plain `python <script> <args>` gives it. Both paths travel in argv,
# never on PYTHONPATH, which splits at os.pathsep, so a work root whose path
# holds ':' or spaces is traced like any other.
BUMP_SCRATCH_PREFIX = "carddemo-bumps-"
MKDTEMP_TRACER_LOG = "mkdtemp.log"
MKDTEMP_TRACER_MODULE = "carddemo_mkdtemp_tracer"
MKDTEMP_TRACER = f"""\
import os
import tempfile

_FOLDER = os.path.dirname(os.path.abspath(__file__))
_LOG = os.path.join(_FOLDER, {MKDTEMP_TRACER_LOG!r})
_make_directory = tempfile.mkdtemp


def mkdtemp(suffix=None, prefix=None, dir=None):
    path = _make_directory(suffix, prefix, dir)
    with open(_LOG, "a", encoding="utf-8") as log:
        log.write(os.path.realpath(path) + "\\n")
    return path


tempfile.mkdtemp = mkdtemp
"""
MKDTEMP_TRACER_BOOTSTRAP = f"""\
import importlib.util
import os
import runpy
import sys

_tracer, _script, *_arguments = sys.argv[1:]
_spec = importlib.util.spec_from_file_location({MKDTEMP_TRACER_MODULE!r}, _tracer)
_module = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = _module
_spec.loader.exec_module(_module)
sys.argv[:] = [_script, *_arguments]
if not sys.flags.safe_path:
    sys.path[0] = os.path.dirname(os.path.realpath(_script))
runpy.run_path(_script, run_name="__main__")
"""


def test_baseline_bumps_scratch_stays_in_the_work_root(carddemo_repo_root: Path, work_root_case: Path) -> None:
    """The bump-check command line makes its scratch folder inside the work
    root ``_cli`` hands it, whatever ``CARDDEMO_TEST_WORKDIR`` holds in this
    process, as the tracer records in the child. The work root is a folder of
    the case's own, narrower than the session's, so a scratch folder made in
    the session root or in the temporary directory is caught alike. The child
    loads the tracer through MKDTEMP_TRACER_BOOTSTRAP, which takes the
    tracer's path from its arguments, so the session work root's path may hold
    ':' or spaces. Only where the scratch lies is judged here: the bump
    verdict, which uncommitted baseline changes decide, is
    test_baseline_bumps', so the command need only end with a verdict (exit 0
    or 1 and its summary line, no traceback)."""
    work_root = work_root_case / "work root"
    work_root.mkdir(mode=0o700)  # private under any umask, so the bump check admits it
    tracer = work_root_case / "tracer"
    tracer.mkdir()
    tracer_file = tracer / f"{MKDTEMP_TRACER_MODULE}.py"
    tracer_file.write_text(MKDTEMP_TRACER, encoding="utf-8")
    launcher = ("-B", "-c", MKDTEMP_TRACER_BOOTSTRAP, str(tracer_file))
    completed = _cli(
        carddemo_repo_root,
        "baselines.py",
        "--check-bumps",
        "--base",
        "HEAD",
        work_root=work_root,
        launcher=launcher,
    )
    verdict = completed.stderr.splitlines()[-1:] or [""]
    assert (
        completed.returncode in (0, 1)
        and "Traceback" not in completed.stderr
        and verdict[0].startswith("baselines.py: ")
        and verdict[0].endswith(" against HEAD")
    ), (
        f"{' '.join(map(str, completed.args[1 + len(launcher) :]))} under the mkdtemp tracer "
        f"exited {completed.returncode} without a bump verdict\n"
        f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
    )
    log = tracer / MKDTEMP_TRACER_LOG
    assert log.is_file(), "the tracer did not run in the child, or the child made no folder with tempfile.mkdtemp"
    made = [Path(line) for line in log.read_text(encoding="utf-8").splitlines()]
    assert any(path.name.startswith(BUMP_SCRATCH_PREFIX) for path in made), f"no bump-check scratch folder in {made}"
    outside = [path for path in made if work_root not in path.parents]
    assert outside == [], f"folders made outside the work root {work_root}: {outside}"


def test_protected_tree(carddemo_repo_root: Path, carddemo_work_root: Path) -> None:
    _require_commit(
        carddemo_repo_root,
        PROTECTED_BASE,
        remedy="This clone's history must hold the suite's starting commit, the base of the protected-tree check.",
    )
    _assert_passes(_cli(carddemo_repo_root, "report.py", "--check-protected", work_root=carddemo_work_root))


# A git the protected-tree check runs in place of the real one: past the
# check's global options, ``rev-parse --verify`` and ``rev-parse
# --is-shallow-repository`` run the case's shell commands, and every call they
# let through fails as a fatal git error does.
FAKE_GIT_FAILURE = "fatal: simulated git failure"
FAKE_GIT_SCRIPT = """\
#!/bin/sh
while :; do
  case "$1" in
    -c) shift 2 ;;
    -*) shift ;;
    *) break ;;
  esac
done
case "$1 $2" in
  "rev-parse --verify") {verify} ;;
  "rev-parse --is-shallow-repository") {shallow} ;;
esac
echo "{failure}" >&2
exit 128
"""
# Runs the script after the fake git's path in its arguments, every git
# command the script starts through subprocess executing that fake. The fake
# is named by its path, never through PATH: the fake lies in the session work
# root, whose path may hold ':', and a PATH entry cannot.
FAKE_GIT_BOOTSTRAP = """\
import os
import runpy
import subprocess
import sys

_git, _script, *_arguments = sys.argv[1:]


class _FakeGitPopen(subprocess.Popen):
    def __init__(self, args, *positional, **keywords):
        if (
            isinstance(args, (list, tuple))
            and list(args[:1]) == ["git"]
            and len(positional) < 2
            and keywords.get("executable") is None
        ):
            keywords["executable"] = _git
        super().__init__(args, *positional, **keywords)


subprocess.Popen = _FakeGitPopen
sys.argv[:] = [_script, *_arguments]
if not sys.flags.safe_path:
    sys.path[0] = os.path.dirname(os.path.realpath(_script))
runpy.run_path(_script, run_name="__main__")
"""


@pytest.mark.parametrize(
    ("verify", "shallow", "returncode", "says", "advises"),
    [
        pytest.param(":", ":", 2, FAKE_GIT_FAILURE, False, id="git-fatal"),
        pytest.param("exit 1", "echo true; exit 0", 1, "fetch-depth: 0", True, id="no-base-shallow"),
        pytest.param("exit 1", "echo false; exit 0", 1, "is not a commit of this clone", False, id="no-base-full"),
    ],
)
def test_protected_tree_names_the_git_failure_cause(
    carddemo_repo_root: Path, work_root_case: Path, verify: str, shallow: str, returncode: int, says: str, advises: bool
) -> None:
    """git failing in any other way than "no such commit" stops the check
    with git's own message (exit 2). A base that names no commit is a finding
    (exit 1) naming the base, and the advice to fetch the full history
    appears only when git says the clone is shallow. The child runs the
    check through FAKE_GIT_BOOTSTRAP, which gives every git command the fake
    by its path, so the session work root's path may hold ':' or spaces."""
    fake = work_root_case / "bin"
    fake.mkdir()
    git = fake / "git"
    git.write_text(FAKE_GIT_SCRIPT.format(verify=verify, shallow=shallow, failure=FAKE_GIT_FAILURE), encoding="utf-8")
    git.chmod(0o755)
    completed = _cli(
        carddemo_repo_root,
        "report.py",
        "--check-protected",
        work_root=work_root_case,
        launcher=("-B", "-c", FAKE_GIT_BOOTSTRAP, str(git)),
    )
    shown = f"exited {completed.returncode}\nstdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
    assert completed.returncode == returncode and "Traceback" not in completed.stderr, shown
    if returncode == 2:
        assert completed.stderr.startswith("report.py: error: git rev-parse --verify failed (exit 128)"), shown
        assert says in completed.stderr, shown
    else:
        assert completed.stdout.startswith("protected tree: 1 finding(s)"), shown
        assert says in completed.stdout, shown
        assert f"the protected base {PROTECTED_BASE} " in completed.stdout, shown
    assert ("fetch-depth" in completed.stdout + completed.stderr) is advises, shown


@pytest.mark.parametrize("tampered", ["HEAD", "head-commit", "option-like"])
def test_protected_tree_checks_against_the_starting_commit(
    carddemo_repo_root: Path, work_root_case: Path, monkeypatch: pytest.MonkeyPatch, tampered: str
) -> None:
    """The protected-tree check diffs against the starting commit whatever the
    inventory header's protected_base holds, so a change that rewrites the
    header to HEAD, or to a later commit already holding a change under
    app/, cannot move the base. A header value other than the starting commit
    is a finding naming the value, and the value never reaches git: an
    option-looking one is never read as git's --output, whose file would
    then exist."""
    leak = work_root_case / "leak"
    value = {
        "HEAD": "HEAD",
        "head-commit": _git_output(carddemo_repo_root, "rev-parse", "--verify", "HEAD^{commit}").decode().strip(),
        "option-like": f"--output={leak}",
    }[tampered]
    inventory = inventory_tools.load()
    tampered_inventory = {**inventory, "header": {**inventory["header"], "protected_base": value}}
    monkeypatch.setattr(report._inventory, "load", lambda *args, **kwargs: tampered_inventory)
    calls: list[list[str]] = []
    run_git = report._git

    def recording_git(args: list[str], **keywords: bool | bytes | None) -> subprocess.CompletedProcess | None:
        calls.append(list(args))
        return run_git(args, **keywords)

    monkeypatch.setattr(report, "_git", recording_git)
    findings = report.check_protected()
    named = [finding for finding in findings if "protected_base" in finding]
    # The finding clips a long value, so only the start of its repr is sure to
    # appear whatever the work root's path.
    assert len(named) == 1 and named[0].startswith(
        f"the inventory header's protected_base {repr(value)[:60]}"
    ), findings
    assert f" is not the starting commit {PROTECTED_BASE}; " in named[0], findings
    assert [argv for argv in calls if any(value in arg for arg in argv)] == [], calls
    options = list(report._PROTECTED_GIT_OPTIONS)
    assert [argv for argv in calls if argv[len(options) :][:1] == ["diff"]] == [
        [*options, "diff", "--exit-code", "--name-status", PROTECTED_BASE, "--", "app", "samples", "diagrams"]
    ], calls
    assert not leak.exists()


# The protected tree, and a line a case appends to a file of a copy of it.
PROTECTED_PATHS = ("app", "samples", "diagrams")
PROTECTED_APPENDED = b"      * QA HIDDEN\n"
# 60 new files and one changed one: 61 status entries, 11 past the 50 a
# protected-tree finding names.
PROTECTED_MANY = tuple(f"app/cbl/QA{number:02d}.cbl" for number in range(1, 61))
# How the finding of the check's comparison of the files on disk begins.
PROTECTED_ON_DISK = (
    "files under app/, samples/ or diagrams/ whose bytes or mode on disk differ from the starting commit: "
)


def _protected_copy_git(tree: Path, env: Mapping[str, str], *args: str) -> subprocess.CompletedProcess:
    """``git <args>`` in the copy ``tree`` with the copy's ``env``, so it reads
    and writes only the copy and its own index, never the checkout's."""
    return subprocess.run(
        ["git", *args],
        cwd=tree,
        env={**os.environ, **env},
        capture_output=True,
        text=True,
        timeout=CLI_TIMEOUT_S,
        check=False,
    )


@pytest.mark.parametrize(
    ("marks", "appended", "created", "excluded", "returncode", "says", "omits", "hides"),
    [
        pytest.param((), (), (), (), 0, ("protected tree: ok",), (), False, id="clean"),
        pytest.param(
            ("--assume-unchanged", "app/cbl/CBACT02C.cbl"),
            ("app/cbl/CBACT02C.cbl",),
            (),
            (),
            1,
            (
                "protected tree: 2 finding(s)",
                "git does not compare: app/cbl/CBACT02C.cbl (assume-unchanged)",
                f"{PROTECTED_ON_DISK}app/cbl/CBACT02C.cbl (content)\n",
            ),
            (),
            True,
            id="assume-unchanged",
        ),
        pytest.param(
            ("--skip-worktree", "app/cbl/CBACT03C.cbl"),
            ("app/cbl/CBACT03C.cbl",),
            (),
            (),
            1,
            (
                "protected tree: 2 finding(s)",
                "git does not compare: app/cbl/CBACT03C.cbl (skip-worktree)",
                f"{PROTECTED_ON_DISK}app/cbl/CBACT03C.cbl (content)\n",
            ),
            (),
            True,
            id="skip-worktree",
        ),
        pytest.param(
            (),
            (),
            ("app/cbl/hidden.cbl",),
            ("app/cbl/hidden.cbl",),
            1,
            (
                "protected tree: 2 finding(s)",
                "an ignore rule hides from the status: app/cbl/hidden.cbl",
                f"{PROTECTED_ON_DISK}app/cbl/hidden.cbl (not in the starting commit)\n",
            ),
            (),
            True,
            id="ignored",
        ),
        pytest.param(
            (),
            ("app/cbl/CBTRN02C.cbl",),
            PROTECTED_MANY,
            (),
            1,
            (
                "protected tree: 3 finding(s)",
                "M\tapp/cbl/CBTRN02C.cbl",
                "?? app/cbl/QA49.cbl; ... and 11 more (61 in all)",
                f"{PROTECTED_ON_DISK}app/cbl/CBTRN02C.cbl (content); app/cbl/QA01.cbl (not in the starting commit); ",
                "app/cbl/QA49.cbl (not in the starting commit); ... and 11 more (61 in all)\n",
            ),
            ("QA50", "QA60"),
            False,
            id="many",
        ),
    ],
)
def test_protected_tree_names_hidden_and_listed_changes(
    carddemo_repo_root: Path,
    work_root_case: Path,
    marks: tuple[str, ...],
    appended: tuple[str, ...],
    created: tuple[str, ...],
    excluded: tuple[str, ...],
    returncode: int,
    says: tuple[str, ...],
    omits: tuple[str, ...],
    hides: bool,
) -> None:
    """The protected-tree check, run on a copy of the protected tree with an
    index of its own and the checkout's history, so no case touches the
    checkout. A clean copy passes. An index entry marked assume-unchanged or
    skip-worktree, and an untracked file an excludes file hides, are each a
    finding naming the path, although both commands of the check see
    nothing, and the comparison of the files on disk names the changed or
    added file as well. A list longer than 50 entries names 50 and counts
    the rest."""
    tree = work_root_case / "tree"
    for folder in PROTECTED_PATHS:
        shutil.copytree(carddemo_repo_root / folder, tree / folder, symlinks=True)
    git_dir = _git_output(carddemo_repo_root, "rev-parse", "--absolute-git-dir").decode().strip()
    index = work_root_case / "index"
    shutil.copyfile(Path(git_dir) / "index", index)
    env = {"GIT_DIR": git_dir, "GIT_WORK_TREE": str(tree), "GIT_INDEX_FILE": str(index)}
    if excluded:
        excludes = work_root_case / "excludes"
        excludes.write_text("".join(f"{path}\n" for path in excluded), encoding="utf-8")
        env.update(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="core.excludesFile", GIT_CONFIG_VALUE_0=str(excludes))
    if marks:
        marked = _protected_copy_git(tree, env, "update-index", *marks)
        assert marked.returncode == 0, marked.stderr
    for path in appended:
        with (tree / path).open("ab") as changed:
            changed.write(PROTECTED_APPENDED)
    for path in created:
        (tree / path).write_bytes(b"IDENTIFICATION DIVISION.\n")
    if hides:
        # Both commands of the check see nothing: only the hidden-change
        # checks can name the change.
        for command in (
            ("diff", "--exit-code", "--name-status", PROTECTED_BASE, "--", *PROTECTED_PATHS),
            ("status", "--porcelain", "--untracked-files=all", "--", *PROTECTED_PATHS),
        ):
            seen = _protected_copy_git(tree, env, *command)
            assert (seen.returncode, seen.stdout) == (0, ""), (command, seen.stdout, seen.stderr)
    completed = _cli(carddemo_repo_root, "report.py", "--check-protected", work_root=work_root_case, extra_env=env)
    shown = f"exited {completed.returncode}\nstdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
    assert completed.returncode == returncode and "Traceback" not in completed.stderr, shown
    assert [text for text in says if text not in completed.stdout] == [], shown
    assert [text for text in omits if text in completed.stdout] == [], shown


# The tracked file the local-git cases below change in their copy, and what a
# case that cannot find the starting commit is told.
PROTECTED_CHANGED = "app/cbl/CBACT02C.cbl"
PROTECTED_BASE_REMEDY = (
    "This clone's history must hold the suite's starting commit, the base of the protected-tree check."
)
# An fsmonitor hook that answers git's version 2 query with a fresh token and
# no changed path, so git takes every file it last saw unchanged to be
# unchanged still.
PROTECTED_LYING_FSMONITOR = "#!/bin/sh\nprintf 'carddemo-self-test\\0'\n"


def _protected_scratch_copy(repo_root: Path, folder: Path) -> tuple[Path, dict[str, str]]:
    """``(tree, env)``: a copy of the protected tree in ``folder``, with a copy
    of the checkout's index and a repository of the case's own whose HEAD is
    the checkout's HEAD. That repository borrows the checkout's objects
    through its ``objects/info/alternates`` file, never through
    ``GIT_ALTERNATE_OBJECT_DIRECTORIES``, whose ':'-separated value cannot
    name a work root whose path holds ':'. ``env`` gives git that repository,
    the copy and its index, so git reads the checkout's objects and writes
    only inside ``folder``: a ref, a config or a hook a case adds never
    reaches the checkout's .git."""
    tree = folder / "tree"
    for name in PROTECTED_PATHS:
        shutil.copytree(repo_root / name, tree / name, symlinks=True)
    index, objects = (
        _git_output(repo_root, "rev-parse", "--path-format=absolute", "--git-path", name).decode().strip()
        for name in ("index", "objects")
    )
    shutil.copyfile(index, folder / "index")
    repository = folder / "repository"
    subprocess.run([*GIT_LOCAL, "init", "-q", str(repository)], check=True, capture_output=True, timeout=CLI_TIMEOUT_S)
    (repository / ".git" / "objects" / "info" / "alternates").write_text(f"{objects}\n", encoding="utf-8")
    env = {"GIT_DIR": str(repository / ".git"), "GIT_WORK_TREE": str(tree), "GIT_INDEX_FILE": str(folder / "index")}
    head = _git_output(repo_root, "rev-parse", "--verify", "HEAD^{commit}").decode().strip()
    updated = _protected_copy_git(tree, env, "update-ref", "HEAD", head)
    assert updated.returncode == 0, updated.stderr
    return tree, env


def _assert_protected_change_named(
    repo_root: Path,
    folder: Path,
    tree: Path,
    env: Mapping[str, str],
    says: str = f"changed since {PROTECTED_BASE} under app/, samples/ or diagrams/: M\t{PROTECTED_CHANGED}",
) -> None:
    """Both commands of the protected-tree check, run by plain git in the copy
    ``tree`` with ``env``, see nothing, and yet the check (exit 1) says
    ``says``: by default that ``PROTECTED_CHANGED`` changed since the
    starting commit, which only the check's own git options expose."""
    for command in (
        ("diff", "--exit-code", "--name-status", PROTECTED_BASE, "--", *PROTECTED_PATHS),
        ("status", "--porcelain", "--untracked-files=all", "--", *PROTECTED_PATHS),
    ):
        seen = _protected_copy_git(tree, env, *command)
        assert (seen.returncode, seen.stdout) == (0, ""), (command, seen.stdout, seen.stderr)
    completed = _cli(repo_root, "report.py", "--check-protected", work_root=folder, extra_env=env)
    shown = f"exited {completed.returncode}\nstdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
    assert completed.returncode == 1 and "Traceback" not in completed.stderr, shown
    assert says in completed.stdout, shown


@pytest.mark.parametrize(
    ("key", "value"),
    [
        pytest.param("core.fsmonitor", None, id="fsmonitor-hook"),
        pytest.param("core.trustCtime", "false", id="trust-ctime-false"),
        pytest.param("core.checkStat", "minimal", id="check-stat-minimal"),
    ],
)
def test_protected_tree_overrides_local_settings_that_hide_a_change(
    carddemo_repo_root: Path, work_root_case: Path, key: str, value: str | None
) -> None:
    """A local git setting that makes git trust its record of a tracked file
    hides a change to it from both commands of the protected-tree check: an
    fsmonitor hook that reports nothing changed, given an appended line, or
    a change-time or stat check turned off, given a same-size edit whose
    modification time is put back. The check overrides each one and names
    the file. The setting comes through GIT_CONFIG_COUNT, which the check's
    ``git -c`` overrides, and the copy's repository is the case's own."""
    _require_commit(carddemo_repo_root, PROTECTED_BASE, remedy=PROTECTED_BASE_REMEDY)
    tree, env = _protected_scratch_copy(carddemo_repo_root, work_root_case)
    if value is None:
        hook = work_root_case / "fsmonitor"
        hook.write_text(PROTECTED_LYING_FSMONITOR, encoding="utf-8")
        hook.chmod(0o755)
        # git runs the hook through the shell, and the work root's path may
        # hold spaces.
        value = shlex.quote(str(hook))
    env.update(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0=key, GIT_CONFIG_VALUE_0=value)
    # Under the setting, plain git records the copy's files in its index:
    # their stat data and, for fsmonitor, the hook's token.
    for _ in range(2):
        recorded = _protected_copy_git(tree, env, "status", "--porcelain", "--", *PROTECTED_PATHS)
        assert (recorded.returncode, recorded.stdout) == (0, ""), (recorded.stdout, recorded.stderr)
    changed = tree / PROTECTED_CHANGED
    if key == "core.fsmonitor":
        with changed.open("ab") as appended:
            appended.write(PROTECTED_APPENDED)
    else:
        before = changed.stat()
        # git may compare the change time to the second only, so the edit
        # waits for a later second than the one the index recorded.
        while time.time_ns() < (before.st_ctime_ns // 10**9 + 1) * 10**9 + 50_000_000:
            time.sleep(0.05)
        text = changed.read_bytes()
        edited = text.replace(b"PROGRAM-ID", b"PROGRAM-QA", 1)
        assert edited != text and len(edited) == len(text)
        with changed.open("r+b") as rewritten:
            rewritten.write(edited)
        os.utime(changed, ns=(before.st_atime_ns, before.st_mtime_ns))
    _assert_protected_change_named(carddemo_repo_root, work_root_case, tree, env)


def test_protected_tree_ignores_a_replace_ref_for_the_base(carddemo_repo_root: Path, work_root_case: Path) -> None:
    """A replace ref that stands a commit holding a changed tree in for the
    starting commit hides the change from both commands of the
    protected-tree check, while ``git rev-parse --verify`` still prints the
    starting commit's own id. The check reads no replace ref and names the
    file. The ref lives in the copy's repository, never in the checkout's."""
    _require_commit(carddemo_repo_root, PROTECTED_BASE, remedy=PROTECTED_BASE_REMEDY)
    tree, env = _protected_scratch_copy(carddemo_repo_root, work_root_case)
    with (tree / PROTECTED_CHANGED).open("ab") as appended:
        appended.write(PROTECTED_APPENDED)
    # The stand-in's tree is the starting commit's with the changed file, built
    # in an index of its own, and the copy's index and HEAD then match it.
    stand_in_env = {**env, "GIT_INDEX_FILE": str(work_root_case / "stand-in-index")}
    for args in (("read-tree", PROTECTED_BASE), ("update-index", "--", PROTECTED_CHANGED)):
        done = _protected_copy_git(tree, stand_in_env, *args)
        assert done.returncode == 0, (args, done.stderr)
    written = _protected_copy_git(tree, stand_in_env, "write-tree")
    assert written.returncode == 0, written.stderr
    stand_in = _protected_copy_git(tree, env, *GIT_LOCAL[1:], "commit-tree", written.stdout.strip(), "-m", "stand-in")
    assert stand_in.returncode == 0, stand_in.stderr
    for args in (
        ("replace", PROTECTED_BASE, stand_in.stdout.strip()),
        ("update-ref", "HEAD", PROTECTED_BASE),
        ("update-index", "--", PROTECTED_CHANGED),
    ):
        done = _protected_copy_git(tree, env, *args)
        assert done.returncode == 0, (args, done.stderr)
    verified = _protected_copy_git(tree, env, "rev-parse", "--verify", "--quiet", f"{PROTECTED_BASE}^{{commit}}")
    assert verified.stdout.strip() == PROTECTED_BASE, (verified.stdout, verified.stderr)
    _assert_protected_change_named(carddemo_repo_root, work_root_case, tree, env)


# What the cases below put on disk in a copy: a clean filter that turns the
# edited file back into the starting commit's bytes, a FIFO, the target of a
# symbolic link, which no finding may show, an added file, and a file name
# holding control characters.
PROTECTED_LYING_CLEAN = "sed -e s/PROGRAM-QA/PROGRAM-ID/"
PROTECTED_FIFO = "app/cbl/QA-FIFO"
PROTECTED_LINK_TARGET = "QA-LINK-TARGET-never-shown"
PROTECTED_ADDED = "app/cbl/QA-EXTRA.cbl"
PROTECTED_CONTROL_NAME = "app/cbl/QA\x1b[31mRED\n.cbl"


@pytest.mark.parametrize(
    ("change", "config", "entry", "hidden"),
    [
        pytest.param(
            "filtered-edit",
            ("filter.carddemo-qa.clean", PROTECTED_LYING_CLEAN),
            f"{PROTECTED_CHANGED} (content)",
            True,
            id="lying-clean-filter",
        ),
        pytest.param(
            "chmod",
            ("core.fileMode", "false"),
            f"{PROTECTED_CHANGED} (mode 100644 -> 100755)",
            True,
            id="file-mode-false",
        ),
        pytest.param("fifo", None, f"{PROTECTED_FIFO} (FIFO, neither a regular file nor a symlink)", True, id="fifo"),
        pytest.param(
            "symlink", None, f"{PROTECTED_CHANGED} (content, mode 100644 -> 120000)", False, id="symlink-for-file"
        ),
        pytest.param(
            "directory-link", None, "app/qa-link (not in the starting commit)", False, id="symlink-to-directory"
        ),
        pytest.param("create", None, f"{PROTECTED_ADDED} (not in the starting commit)", False, id="untracked-file"),
        pytest.param("delete", None, f"{PROTECTED_CHANGED} (missing)", False, id="missing-file"),
        pytest.param(
            "control-name",
            None,
            f"{PROTECTED_CONTROL_NAME!r} (not in the starting commit)",
            False,
            id="control-character-name",
        ),
    ],
)
def test_protected_tree_compares_the_files_on_disk(
    carddemo_repo_root: Path,
    work_root_case: Path,
    change: str,
    config: tuple[str, str] | None,
    entry: str,
    hidden: bool,
) -> None:
    """The protected-tree check reads the copy's files from disk and names
    each one whose bytes or mode differ from the starting commit, with how.
    A clean filter that turns the edited file back into the starting
    commit's bytes (the attribute in the copy repository's info/attributes,
    the filter through GIT_CONFIG_COUNT), core.fileMode=false over an added
    execute bit, and a FIFO, which git never lists, each hide the change
    from both commands of the check and from its other findings, so the
    comparison on disk is the one finding. A symbolic link in place of a
    file, a link to a directory (one entry, never followed), an added file,
    a deleted file and a name holding control characters are named too, and
    no output shows a link's target or a raw control character."""
    _require_commit(carddemo_repo_root, PROTECTED_BASE, remedy=PROTECTED_BASE_REMEDY)
    tree, env = _protected_scratch_copy(carddemo_repo_root, work_root_case)
    if config is not None:
        env.update(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0=config[0], GIT_CONFIG_VALUE_0=config[1])
    changed = tree / PROTECTED_CHANGED
    if change == "filtered-edit":
        info = Path(env["GIT_DIR"]) / "info"
        info.mkdir(exist_ok=True)
        (info / "attributes").write_text(f"{PROTECTED_CHANGED} filter=carddemo-qa\n", encoding="utf-8")
        text = changed.read_bytes()
        edited = text.replace(b"PROGRAM-ID", b"PROGRAM-QA", 1)
        assert edited != text
        changed.write_bytes(edited)
    elif change == "chmod":
        changed.chmod(changed.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    elif change == "fifo":
        os.mkfifo(tree / PROTECTED_FIFO)
    elif change == "symlink":
        changed.unlink()
        changed.symlink_to(PROTECTED_LINK_TARGET)
    elif change == "directory-link":
        (tree / "app" / "qa-link").symlink_to("cbl", target_is_directory=True)
    elif change == "create":
        (tree / PROTECTED_ADDED).write_bytes(b"IDENTIFICATION DIVISION.\n")
    elif change == "delete":
        changed.unlink()
    else:
        (tree / PROTECTED_CONTROL_NAME).write_bytes(b"IDENTIFICATION DIVISION.\n")
    finding = f"  - {PROTECTED_ON_DISK}{entry}\n"
    if hidden:
        _assert_protected_change_named(
            carddemo_repo_root, work_root_case, tree, env, says=f"protected tree: 1 finding(s)\n{finding}"
        )
        return
    completed = _cli(carddemo_repo_root, "report.py", "--check-protected", work_root=work_root_case, extra_env=env)
    shown = f"exited {completed.returncode}\nstdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
    assert completed.returncode == 1 and "Traceback" not in completed.stderr, shown
    assert finding in completed.stdout, shown
    output = completed.stdout + completed.stderr
    assert [text for text in (PROTECTED_LINK_TARGET, "app/qa-link/", "\x1b") if text in output] == [], shown


def test_protected_tree_reads_this_checkout_when_git_compares_another(
    carddemo_repo_root: Path, work_root_case: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A core.worktree that points git at a pristine copy hides a change in
    this checkout from both commands of the protected-tree check and from
    its comparison of git's work tree, since all of them read the copy. The
    check reads this checkout's own files as well and names the change. The
    case's copy stands in for this checkout (``report.REPO_ROOT``), and its
    repository's config names a second, pristine copy."""
    _require_commit(carddemo_repo_root, PROTECTED_BASE, remedy=PROTECTED_BASE_REMEDY)
    tree, env = _protected_scratch_copy(carddemo_repo_root, work_root_case)
    pristine = work_root_case / "pristine"
    for name in PROTECTED_PATHS:
        shutil.copytree(carddemo_repo_root / name, pristine / name, symlinks=True)
    configured = _protected_copy_git(tree, env, "config", "core.worktree", str(pristine))
    assert configured.returncode == 0, configured.stderr
    del env["GIT_WORK_TREE"]
    monkeypatch.delenv("GIT_WORK_TREE", raising=False)
    with (tree / PROTECTED_CHANGED).open("ab") as appended:
        appended.write(PROTECTED_APPENDED)
    toplevel = _protected_copy_git(tree, env, "rev-parse", "--show-toplevel")
    assert toplevel.stdout.strip() == str(pristine), (toplevel.stdout, toplevel.stderr)
    for command in (
        ("diff", "--exit-code", "--name-status", PROTECTED_BASE, "--", *PROTECTED_PATHS),
        ("status", "--porcelain", "--untracked-files=all", "--", *PROTECTED_PATHS),
    ):
        seen = _protected_copy_git(tree, env, *command)
        assert (seen.returncode, seen.stdout) == (0, ""), (command, seen.stdout, seen.stderr)
    for name, value in env.items():
        monkeypatch.setenv(name, value)
    monkeypatch.setattr(report, "REPO_ROOT", tree)
    assert report.check_protected() == [
        "files under app/, samples/ or diagrams/ of this checkout, which is not the work tree git compares, whose "
        f"bytes or mode on disk differ from the starting commit: {PROTECTED_CHANGED} (content)"
    ]


def test_protected_tree_takes_blob_ids_from_git_outside_sha1(
    carddemo_repo_root: Path, work_root_case: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """In a repository whose object format is not SHA-1 the comparison of the
    files on disk takes each blob id from ``git hash-object --no-filters
    --stdin``, given the bytes read from disk, instead of hashing them
    itself. The case's git says ``sha256`` when asked the object format and
    runs every other call as it is, so its ids still match the starting
    commit's: the clean copy has no finding, with one hash-object call per
    file, and a changed file is named with its content and mode."""
    _require_commit(carddemo_repo_root, PROTECTED_BASE, remedy=PROTECTED_BASE_REMEDY)
    tree, env = _protected_scratch_copy(carddemo_repo_root, work_root_case)
    for name, value in env.items():
        monkeypatch.setenv(name, value)
    # The copy stands in for this checkout, so only its files are read.
    monkeypatch.setattr(report, "REPO_ROOT", tree)
    options = len(report._PROTECTED_GIT_OPTIONS)
    hashed: list[list[str]] = []
    run_git = report._git

    def sha256_git(args: list[str], **keywords: bool | bytes | None) -> subprocess.CompletedProcess | None:
        if list(args[options:]) == ["rev-parse", "--show-object-format"]:
            return subprocess.CompletedProcess(list(args), 0, "sha256\n", "")
        if list(args[options:][:1]) == ["hash-object"]:
            hashed.append(list(args[options:]))
        return run_git(args, **keywords)

    monkeypatch.setattr(report, "_git", sha256_git)
    assert report.check_protected() == []
    files = _git_output(carddemo_repo_root, "ls-tree", "-r", "--name-only", PROTECTED_BASE, "--", *PROTECTED_PATHS)
    assert hashed == [["hash-object", "--no-filters", "--stdin"]] * len(files.splitlines())
    changed = tree / PROTECTED_CHANGED
    with changed.open("ab") as appended:
        appended.write(PROTECTED_APPENDED)
    changed.chmod(changed.stat().st_mode | stat.S_IXUSR)
    assert f"{PROTECTED_ON_DISK}{PROTECTED_CHANGED} (content, mode 100644 -> 100755)" in report.check_protected()


# --------------------------------------------------------------------------
# runner.conclude on the outcomes it skips: the skip text names the scenario
# after the gap, so pytest's short summary never merges two scenarios that
# share a reason.
# --------------------------------------------------------------------------

# Each skipped status with its reason, gap, scenario and detail (empty for
# none), and the exact skip text.
CONCLUDE_SKIPS = [
    pytest.param(
        results.STATUS_BLOCKED,
        "adapter-unavailable",
        "GAP-MIS-002",
        "RPT-CBSTM03A-normal-01",
        "page zero not mappable: 1; vm.mmap_min_addr=65536",
        "blocked: adapter-unavailable [GAP-MIS-002] RPT-CBSTM03A-normal-01: "
        "page zero not mappable: 1; vm.mmap_min_addr=65536",
        id="blocked-with-detail",
    ),
    pytest.param(
        results.STATUS_BLOCKED,
        "adapter-unavailable",
        "GAP-MIS-002",
        "RPT-CBSTM03A-normal-01",
        "",
        "blocked: adapter-unavailable [GAP-MIS-002] RPT-CBSTM03A-normal-01",
        id="blocked-without-detail",
    ),
    pytest.param(
        results.STATUS_UNEXECUTED,
        "missing-prerequisite",
        "GAP-MIS-013",
        "TRN-CBTRN01C-recovery-02",
        "read fault (io-error) on DALYTRAN: no gnucobol-batch mechanism injects a failure after a successful open",
        "implemented-unexecuted: missing-prerequisite [GAP-MIS-013] TRN-CBTRN01C-recovery-02: "
        "read fault (io-error) on DALYTRAN: no gnucobol-batch mechanism injects a failure after a successful open",
        id="implemented-unexecuted-with-detail",
    ),
    pytest.param(
        results.STATUS_UNEXECUTED,
        "missing-prerequisite",
        "GAP-MIS-013",
        "TRN-CBTRN01C-recovery-02",
        "",
        "implemented-unexecuted: missing-prerequisite [GAP-MIS-013] TRN-CBTRN01C-recovery-02",
        id="implemented-unexecuted-without-detail",
    ),
]


@pytest.mark.parametrize(("status", "reason", "gap_id", "scenario_id", "detail", "message"), CONCLUDE_SKIPS)
def test_runner_conclude_skip_text_names_the_scenario_after_the_gap(
    status: str, reason: str, gap_id: str, scenario_id: str, detail: str, message: str
) -> None:
    outcome = runner.Outcome(
        scenario_id=scenario_id,
        adapter="gnucobol-batch",
        status=status,
        reason=reason,
        gap_id=gap_id,
        detail=detail,
    )
    with pytest.raises(pytest.skip.Exception) as skipped:
        runner.conclude(outcome)
    assert skipped.value.msg == message


# --------------------------------------------------------------------------
# The scenario deadline over judging: decode_result, projection, compare,
# the diff and the record's text stop at it, and runner.run ends a run whose
# judging overruns it failing/timeout, with no outcome kept.
# --------------------------------------------------------------------------

# Under a far deadline each judging step gives what it gives without one, and at
# a passed one it raises ScenarioTimeout naming the step, at the first, a middle
# or the last clock reading, never as a finding. A diff of long lists or long
# strings keeps its verdict and problem text and costs what it can show
# (DIFF_MAX_LINES, DIFF_MAX_CHARS, counted through _yaml_lines). dump_yaml
# writes committed records identically under a far deadline and raises
# TimeoutError once it has passed, completion included. runner.run ends a run
# whose judging, record or record text overruns the judge deadline
# failing/timeout with no outcome, and keeps a result execute returned in time
# when cleanup overruns the deadline.

# An observed baseline asserting datasets, file states, reason codes and
# messages, judged on the bytes it observed; and a scenario asserting only a
# return code and messages, which a stand-in adapter can run.
JUDGE_OBSERVED_SCENARIO = "TRN-POSTTRAN-normal-01"
JUDGE_SCENARIO = "DAT-CBACT01C-normal-01"
JUDGE_WORK_ROOT_PREFIX = "unit-judge-deadline-"
JUDGE_DEADLINE_S = 1  # runner.DEADLINE_S in the runner cases
JUDGE_FAR_S = 600  # a deadline no case reaches
JUDGE_PAST_S = 0.05  # how far past a deadline a stand-in sleeps
# A list of LONG_ITEMS items whose observed copy differs at index
# LONG_CHANGED only.
LONG_ITEMS = 200_000
LONG_CHANGED = 150_000
# The most YAML lines a diff of such a list may write through _yaml_lines.
# A diff writes five windows of at most DIFF_MAX_LINES lines plus one item:
# _window_end measures one to learn that a list does not fit and one per side
# from the first difference, and _render writes both cut sides. The sixth
# window covers each window's last-item overshoot and a mapping's own keys.
# Writing whole values instead costs at least 2 * LONG_ITEMS lines.
LONG_RENDERED_LINES = 6 * baselines.DIFF_MAX_LINES
# The same bound on the characters those windows write, each string cut to
# _DIFF_MAX_CHARS_PER_VALUE first; a string of LONG_CHARS characters written
# whole on both sides costs more than it.
LONG_RENDERED_CHARS = 6 * baselines.DIFF_MAX_CHARS
LONG_CHARS = 10 * baselines.DIFF_MAX_CHARS
# A long list value as a plain list or as {count, records}.
LONG_SHAPES = [pytest.param(False, id="records-list"), pytest.param(True, id="count-and-records")]

JUDGING_STEPS = [
    pytest.param("decode_result", "decoding the result", id="decode_result"),
    pytest.param("projection", "projecting the decoded outcome", id="projection"),
    pytest.param("compare", "comparing the result", id="compare"),
]
# Where, among the clock readings of a whole step, the deadline passes.
DEADLINE_READINGS = [
    pytest.param(0.0, id="first-loop-reading"),
    pytest.param(0.5, id="midway"),
    pytest.param(1.0, id="last-reading"),
]


class _ReadingClock:
    """A ``time.monotonic`` stand-in: ``0.0`` for its first ``before``
    readings, then a time past every deadline; ``readings`` counts them."""

    def __init__(self, before: float = float("inf")) -> None:
        self.before = before
        self.readings = 0

    def __call__(self) -> float:
        self.readings += 1
        return 0.0 if self.readings <= self.before else float("inf")


class _YamlLineCount:
    """A ``baselines._yaml_lines`` stand-in returning what ``yaml_lines``
    returns; ``lines`` totals the YAML lines it has returned and ``chars``
    their characters, a line break counted for each."""

    def __init__(self, yaml_lines: Callable[..., list[str]]) -> None:
        self.yaml_lines = yaml_lines
        self.lines = 0
        self.chars = 0

    def __call__(self, value: object, *deadline: object) -> list[str]:
        written = self.yaml_lines(value, *deadline)
        self.lines += len(written)
        self.chars += sum(len(line) + 1 for line in written)
        return written


@pytest.fixture
def judge_work_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh, empty work root of this case's own inside the session's,
    removed afterwards with any working directory a failing run kept."""
    directory = Path(tempfile.mkdtemp(prefix=JUDGE_WORK_ROOT_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def _observed_result(baseline: baselines.Baseline) -> base.Result:
    """A result holding what ``baseline`` observed: its return code, its
    messages, its raw datasets and its ``@CATALOG`` lines, which have no
    raw bytes."""
    catalog = baseline.expected["file_state"][base.CATALOG]
    return base.Result(
        return_code=baseline.expected["return_code"],
        messages=list(baseline.expected["messages"]),
        datasets={**baseline.datasets, base.CATALOG: ("\n".join(catalog) + "\n").encode("ascii")},
    )


def _judging_step(
    step: str, scenario: scenarios.Scenario, baseline: baselines.Baseline, result: base.Result, **deadline: float
) -> object:
    """Run one judging step of ``baselines`` with ``deadline`` (``projection``
    on an unbounded ``decode_result``)."""
    if step == "decode_result":
        return baselines.decode_result(scenario, result, **deadline)
    if step == "projection":
        return baselines.projection(baselines.decode_result(scenario, result), scenario, baseline, **deadline)
    return baselines.compare(scenario, baseline, result, "gnucobol-batch", **deadline)


@pytest.mark.parametrize(("step", "interrupted"), JUDGING_STEPS)
def test_judging_steps_stop_at_a_passed_deadline_and_are_unchanged_before_it(step: str, interrupted: str) -> None:
    scenario = scenarios.get(JUDGE_OBSERVED_SCENARIO)
    baseline = baselines.load(scenario.id)
    result = _observed_result(baseline)
    unbounded = _judging_step(step, scenario, baseline, result)
    if step == "compare":
        assert unbounded == ("passed", None, "")
    assert _judging_step(step, scenario, baseline, result, deadline=time.monotonic() + JUDGE_FAR_S) == unbounded
    with pytest.raises(base.ScenarioTimeout) as caught:
        _judging_step(step, scenario, baseline, result, deadline=time.monotonic())
    assert str(caught.value) == f"the scenario deadline passed while {interrupted}"


@pytest.mark.parametrize("share", DEADLINE_READINGS)
@pytest.mark.parametrize("step", [param.values[0] for param in JUDGING_STEPS])
def test_a_deadline_passing_inside_a_judging_step_always_interrupts_it(
    monkeypatch: pytest.MonkeyPatch, step: str, share: float
) -> None:
    """With the clock read at every loop iteration, a deadline that passes at
    a reading after the step's entry check raises ScenarioTimeout at that
    reading: no check of the step turns it into a finding, a marker or a
    diff and goes on."""
    scenario = scenarios.get(JUDGE_OBSERVED_SCENARIO)
    baseline = baselines.load(scenario.id)
    result = _observed_result(baseline)
    monkeypatch.setattr(baselines, "_DEADLINE_EVERY", 1)
    counting = _ReadingClock()
    monkeypatch.setattr(baselines, "time", SimpleNamespace(monotonic=counting))
    assert _judging_step(step, scenario, baseline, result, deadline=1.0) == _judging_step(
        step, scenario, baseline, result
    )
    assert counting.readings > 2
    passing = _ReadingClock(before=1 + round(share * (counting.readings - 2)))
    monkeypatch.setattr(baselines, "time", SimpleNamespace(monotonic=passing))
    with pytest.raises(base.ScenarioTimeout) as caught:
        _judging_step(step, scenario, baseline, result, deadline=1.0)
    assert str(caught.value).startswith("the scenario deadline passed while ")
    # The entry check passed; the reading after the deadline was the last.
    assert passing.before >= 1 and passing.readings == passing.before + 1


@pytest.mark.parametrize("counted", LONG_SHAPES)
def test_a_long_mismatch_keeps_its_verdict_with_a_bounded_diff_at_its_first_difference(
    monkeypatch: pytest.MonkeyPatch, counted: bool
) -> None:
    lines = [f"LINE {number:06d}" for number in range(LONG_ITEMS)]
    observed = list(lines)
    observed[LONG_CHANGED] = "CHANGED"
    stored = {"count": LONG_ITEMS, "records": lines} if counted else lines
    key = f"file_state.{base.CATALOG}"
    scenario = dataclasses.replace(scenarios.get(JUDGE_SCENARIO), expected={"file_state": {base.CATALOG: "baseline"}})
    baseline = baselines.Baseline(scenario.id, {"file_state": {base.CATALOG: stored}}, DERIVED_MANIFEST)
    result = base.Result(return_code=0, datasets={base.CATALOG: ("\n".join(observed) + "\n").encode("ascii")})
    written = _YamlLineCount(baselines._yaml_lines)
    monkeypatch.setattr(baselines, "_yaml_lines", written)
    status, reason, diff = baselines.compare(scenario, baseline, result, "gnucobol-batch")
    assert 0 < written.lines <= LONG_RENDERED_LINES
    assert (status, reason) == ("failing", "assertion")
    shown = diff.splitlines()
    assert len(shown) <= baselines.DIFF_MAX_LINES + 1
    assert shown[:2] == [
        f"{scenario.id}: {key}: differs from the baseline",
        f"  - {key} record {LONG_CHANGED + 1}: expected 'LINE {LONG_CHANGED:06d}', got 'CHANGED'",
    ]
    leading = f"{LONG_CHANGED - 3} identical leading item(s) and "
    assert shown[2].startswith(f"  ... records: {leading}" if counted else f"  ... {leading}")
    changed = [line for line in shown if line.startswith(("-- ", "+- "))]
    assert changed == [f"-- LINE {LONG_CHANGED:06d}", "+- CHANGED"]


# The actual side of a long record list, the lines the diff must show at the
# first difference, and how its line saying what is left out begins: the
# records with one balance changed, or the marker of a dataset not produced,
# against which the expected records are shown from the first.
LONG_RECORD_ACTUALS = [
    pytest.param(
        False,
        ["-  ACCT-CURR-BAL: '194.00'", "+  ACCT-CURR-BAL: '0.00'", f"   ACCT-ID: '{LONG_CHANGED:011d}'"],
        f"  ... {LONG_CHANGED - 3} identical leading item(s) and ",
        id="records-differ",
    ),
    # A record is three YAML lines, so the window ends with the record that
    # brings it to DIFF_MAX_LINES lines.
    pytest.param(
        True,
        ["-- ACCT-ACTIVE-STATUS: Y", "-  ACCT-CURR-BAL: '194.00'", "-  ACCT-ID: '00000000000'"],
        f"  ... {LONG_ITEMS - (baselines.DIFF_MAX_LINES // 3 + 1)} expected trailing item(s)",
        id="records-not-produced",
    ),
]


@pytest.mark.parametrize(("missing", "first_difference", "left_out"), LONG_RECORD_ACTUALS)
def test_the_diff_of_long_record_lists_shows_the_records_at_the_first_difference(
    monkeypatch: pytest.MonkeyPatch, missing: bool, first_difference: list[str], left_out: str
) -> None:
    records = [
        {"ACCT-ID": f"{number:011d}", "ACCT-ACTIVE-STATUS": "Y", "ACCT-CURR-BAL": "194.00"}
        for number in range(LONG_ITEMS)
    ]
    observed = [dict(record) for record in records]
    observed[LONG_CHANGED]["ACCT-CURR-BAL"] = "0.00"
    actual: object = {"@missing": True} if missing else observed
    finding = baselines._Finding("baseline", "datasets.ACCTDATA", ["datasets.ACCTDATA: differs"], records, actual)
    written = _YamlLineCount(baselines._yaml_lines)
    monkeypatch.setattr(baselines, "_yaml_lines", written)
    shown = baselines._render([finding], JUDGE_SCENARIO).splitlines()
    assert 0 < written.lines <= LONG_RENDERED_LINES
    assert len(shown) <= baselines.DIFF_MAX_LINES + 1
    assert shown[2].startswith(left_out) and shown[2].endswith(" trailing item(s) not shown")
    at = shown.index(first_difference[0])
    assert shown[at : at + len(first_difference)] == first_difference


# A diff shows a string whole up to this many characters, and a longer one
# cut to them and this marker of the characters LONG_CHARS leaves out.
SHOWN_CHARS = baselines._DIFF_MAX_CHARS_PER_VALUE
LONG_MARKER = f"... ({LONG_CHARS - SHOWN_CHARS} more character(s) not shown)"
# Expected and observed messages holding outsize strings, and the lines the
# diff must show in order: one long line on one side, long lines on both
# sides, and DIFF_MAX_LINES lines just short of the cut, so that the
# character cap, not the line cap, ends the diff.
LONG_STRINGS = [
    pytest.param(
        ["expected"], ["X" * LONG_CHARS], ["-- expected", f"+- {'X' * SHOWN_CHARS}{LONG_MARKER}"], id="one-side"
    ),
    pytest.param(
        ["A" * LONG_CHARS],
        ["B" * LONG_CHARS],
        [f"-- {'A' * SHOWN_CHARS}{LONG_MARKER}", f"+- {'B' * SHOWN_CHARS}{LONG_MARKER}"],
        id="both-sides",
    ),
    pytest.param(
        [f"{number:04d}{'A' * (SHOWN_CHARS - 4)}" for number in range(baselines.DIFF_MAX_LINES)],
        [f"{number:04d}{'B' * (SHOWN_CHARS - 4)}" for number in range(baselines.DIFF_MAX_LINES)],
        [f"-- 0000{'A' * (SHOWN_CHARS - 4)}", f"-- 0001{'A' * (SHOWN_CHARS - 4)}"],
        id="character-cap",
    ),
]


@pytest.mark.parametrize(("stored", "observed", "lines"), LONG_STRINGS)
def test_a_long_string_mismatch_keeps_its_verdict_with_a_diff_within_the_character_cap(
    monkeypatch: pytest.MonkeyPatch, stored: list[str], observed: list[str], lines: list[str]
) -> None:
    scenario = dataclasses.replace(scenarios.get(JUDGE_SCENARIO), expected={"messages": "baseline"})
    baseline = baselines.Baseline(scenario.id, {"messages": stored}, DERIVED_MANIFEST)
    result = base.Result(return_code=0, messages=observed)
    unbounded = baselines.compare(scenario, baseline, result, "gnucobol-batch")
    written = _YamlLineCount(baselines._yaml_lines)
    monkeypatch.setattr(baselines, "_yaml_lines", written)
    status, reason, diff = baselines.compare(
        scenario, baseline, result, "gnucobol-batch", deadline=time.monotonic() + JUDGE_FAR_S
    )
    assert (status, reason, diff) == unbounded
    assert (status, reason) == ("failing", "assertion")
    assert 0 < written.chars <= LONG_RENDERED_CHARS
    assert len(diff) <= baselines.DIFF_MAX_CHARS
    shown = diff.splitlines()
    assert shown[:2] == [
        f"{scenario.id}: messages: differs from the baseline",
        f"  - messages record 1: expected {baselines._show(stored[0])}, got {baselines._show(observed[0])}",
    ]
    at = shown.index(lines[0])
    assert shown[at : at + len(lines)] == lines
    if len(stored) == baselines.DIFF_MAX_LINES:
        assert re.fullmatch(r"\.\.\. diff truncated: [0-9]+ more character\(s\) not shown", shown[-1])


# Values a diff writes, and the same values with every string cut as it
# shows them: whole at the limit, cut past it inside lists, tuples and
# mappings, and of two mapping keys that cut to the same text, the second
# kept whole so that no entry is lost.
LONG_KEY = "K" * (SHOWN_CHARS + 5)
CLIPPED_VALUES = [
    pytest.param("X" * SHOWN_CHARS, "X" * SHOWN_CHARS, id="string-at-the-limit"),
    pytest.param("X" * (SHOWN_CHARS + 7), f"{'X' * SHOWN_CHARS}... (7 more character(s) not shown)", id="long-string"),
    pytest.param(
        {"F": ["Y" * (SHOWN_CHARS + 1), 5, None], "G": ("Z" * (SHOWN_CHARS + 2),)},
        {
            "F": [f"{'Y' * SHOWN_CHARS}... (1 more character(s) not shown)", 5, None],
            "G": [f"{'Z' * SHOWN_CHARS}... (2 more character(s) not shown)"],
        },
        id="nested",
    ),
    pytest.param(
        {f"{LONG_KEY}1": "a", f"{LONG_KEY}2": "b"},
        {f"{'K' * SHOWN_CHARS}... (6 more character(s) not shown)": "a", f"{LONG_KEY}2": "b"},
        id="keys-cut-alike",
    ),
]


@pytest.mark.parametrize(("value", "shown"), CLIPPED_VALUES)
def test_a_diff_cuts_every_long_string_it_writes_and_keeps_every_key(value: object, shown: object) -> None:
    original = copy.deepcopy(value)
    assert baselines._clipped(value, baselines._UNBOUNDED) == shown
    assert value == original


# Committed result records: the largest file, a job's with datasets, file
# states and reason codes, a program's messages, and one with no decoded
# outcome (implemented-unexecuted).
DUMPED_RECORDS = [
    pytest.param(scenario_id, id=scenario_id)
    for scenario_id in ("RPT-CBSTM03A-normal-01", JUDGE_OBSERVED_SCENARIO, JUDGE_SCENARIO, "DAT-CBACT01C-recovery-02")
]


@pytest.mark.parametrize("scenario_id", DUMPED_RECORDS)
def test_dump_yaml_writes_a_committed_record_the_same_under_a_far_deadline(scenario_id: str) -> None:
    value = load_yaml(RESULTS_DIR / f"{scenario_id}.yaml")
    assert dump_yaml(value, deadline=time.monotonic() + JUDGE_FAR_S) == dump_yaml(value)


@pytest.mark.parametrize(
    "share", [pytest.param(None, id="before-writing"), *DEADLINE_READINGS]
)
def test_dump_yaml_raises_timeout_error_once_its_deadline_has_passed(
    monkeypatch: pytest.MonkeyPatch, share: float | None
) -> None:
    value = load_yaml(RESULTS_DIR / f"{JUDGE_OBSERVED_SCENARIO}.yaml")
    if share is None:
        with pytest.raises(TimeoutError):
            dump_yaml(value, deadline=time.monotonic())
        return
    counting = _ReadingClock()
    monkeypatch.setattr(harness, "time", SimpleNamespace(monotonic=counting))
    assert dump_yaml(value, deadline=1.0) == dump_yaml(value)
    # The clock is read at every node and scalar; the last reading is taken
    # once the text is complete, so text finished past the deadline is
    # refused too.
    passing = _ReadingClock(before=1 + round(share * (counting.readings - 2)))
    monkeypatch.setattr(harness, "time", SimpleNamespace(monotonic=passing))
    with pytest.raises(TimeoutError):
        dump_yaml(value, deadline=1.0)
    assert passing.readings == passing.before + 1


class _PassingClock:
    """A ``time.monotonic`` stand-in: ``0.0`` until ``passed`` is set, then a
    time past every deadline."""

    def __init__(self) -> None:
        self.passed = False

    def __call__(self) -> float:
        return float("inf") if self.passed else 0.0


# Values holding three long scalars, as list items, mapping values and
# mapping keys; DUMPED_LONG_CHARS is longer than any other scalar in them.
DUMPED_LONG_CHARS = 10_000
DUMPED_LONG_SCALARS = [f"{index}{'X' * DUMPED_LONG_CHARS}" for index in range(3)]
LONG_SCALAR_VALUES = [
    pytest.param({"messages": DUMPED_LONG_SCALARS}, id="list-items"),
    pytest.param({f"key{index}": text for index, text in enumerate(DUMPED_LONG_SCALARS)}, id="mapping-values"),
    pytest.param({text: index for index, text in enumerate(DUMPED_LONG_SCALARS)}, id="mapping-keys"),
]


@pytest.mark.parametrize("value", LONG_SCALAR_VALUES)
def test_dump_yaml_raises_timeout_error_right_after_the_long_scalar_its_deadline_passes_in(
    monkeypatch: pytest.MonkeyPatch, value: dict
) -> None:
    """Time passes while the emitter analyses the first long scalar it
    writes; the reading that follows that scalar raises, before any other
    long scalar is written."""
    clock = _PassingClock()
    analysed: list[str] = []
    analyze = yaml.emitter.Emitter.analyze_scalar

    def analysing(emitter: yaml.emitter.Emitter, scalar: str) -> object:
        if len(scalar) > DUMPED_LONG_CHARS:
            analysed.append(scalar)
            clock.passed = True
        return analyze(emitter, scalar)

    monkeypatch.setattr(yaml.emitter.Emitter, "analyze_scalar", analysing)
    monkeypatch.setattr(harness, "time", SimpleNamespace(monotonic=clock))
    with pytest.raises(TimeoutError):
        dump_yaml(value, deadline=1.0)
    assert len(analysed) == 1


@pytest.mark.parametrize(
    ("text", "refused"),
    [
        pytest.param(None, False, id="no-text"),
        pytest.param("{rendered}# rendered by the runner\n", False, id="text"),
        pytest.param("{rendered}", True, id="refused-record"),
    ],
)
def test_results_write_writes_the_record_text_once_the_record_passes_its_checks(
    judge_work_root: Path, text: str | None, refused: bool
) -> None:
    record = results.load(JUDGE_SCENARIO)
    assert record is not None
    if refused:
        record = {**record, "status": "unknown"}
    rendered = dump_yaml(dict(record))
    given = None if text is None else text.format(rendered=rendered)
    outcome = SimpleNamespace(adapter="gnucobol-batch", scenario_id=JUDGE_SCENARIO, record=record, record_text=given)
    folder = judge_work_root / "results"
    if refused:
        with pytest.raises(results.ResultError):
            results.write(outcome, results_dir=folder)
        assert not folder.exists()
        return
    path = results.write(outcome, results_dir=folder)
    assert path is not None and path.read_text(encoding="utf-8") == (rendered if given is None else given)


def _sleep_past(deadline: float) -> None:
    time.sleep(max(0.0, deadline - time.monotonic()) + JUDGE_PAST_S)


class _JudgedAdapter:
    """A gnucobol-batch stand-in that can observe everything and returns
    ``result`` from ``execute`` at once; with ``overrun_cleanup`` its
    ``cleanup`` returns only once the deadline the runner armed has
    passed."""

    name = "gnucobol-batch"

    def __init__(self, work_root: Path, result: base.Result, overrun_cleanup: bool = False) -> None:
        self.work_root = work_root
        self.deadline: float | None = None
        self.result = result
        self.overrun_cleanup = overrun_cleanup

    def can_observe(self, observation: str) -> bool:
        return True

    def prepare(self, scenario: object, workdir: Path) -> None:
        return None

    def execute(self, scenario: object, workdir: Path) -> base.Result:
        return self.result

    def cleanup(self, scenario: object, workdir: Path) -> None:
        if self.overrun_cleanup and self.deadline is not None:
            _sleep_past(self.deadline)


def _overrunning(real: Callable[..., object], stops: bool) -> Callable[..., object]:
    """``real``, a step taking ``deadline=``, called with a deadline only once
    that deadline has passed (``stops``), or called without it and returning
    once it has passed; called without a deadline, ``real`` unchanged."""

    def step(*args: object, deadline: float | None = None, **kwargs: object) -> object:
        if deadline is None:
            return real(*args, **kwargs)
        if stops:
            _sleep_past(deadline)
            return real(*args, deadline=deadline, **kwargs)
        value = real(*args, **kwargs)
        _sleep_past(deadline)
        return value

    return step


def _kept_runtimes(monkeypatch: pytest.MonkeyPatch, work_root: Path) -> list[dict[str, object]]:
    """The runtime files the runner writes, kept in memory, with nothing
    written in the repository."""
    runtimes: list[dict[str, object]] = []

    def keep_runtime(scenario_id: str, data: Mapping[str, object]) -> Path:
        runtimes.append(dict(data))
        return work_root / f"{scenario_id}.yaml"

    monkeypatch.setattr(results, "write_runtime", keep_runtime)
    monkeypatch.setattr(results, "write_environment", lambda env: work_root / "environment.yaml")
    return runtimes


@pytest.mark.parametrize(
    ("owner", "name", "stops", "phase", "superseded"),
    [
        pytest.param(baselines, "decode_result", True, "decoding the result", None, id="decode_result"),
        pytest.param(baselines, "projection", True, "projecting the decoded result", None, id="projection"),
        pytest.param(baselines, "compare", True, "comparing the result", None, id="compare"),
        pytest.param(baselines, "compare", False, "judging the result", "passed", id="compare-returns-late"),
        pytest.param(runner, "dump_yaml", True, "rendering the result record", "passed", id="record-text"),
    ],
)
def test_a_run_whose_judging_overruns_the_deadline_is_a_timeout_without_an_outcome(
    monkeypatch: pytest.MonkeyPatch,
    judge_work_root: Path,
    owner: object,
    name: str,
    stops: bool,
    phase: str,
    superseded: str | None,
) -> None:
    monkeypatch.setattr(runner, "DEADLINE_S", JUDGE_DEADLINE_S)
    monkeypatch.setattr(owner, name, _overrunning(getattr(owner, name), stops))
    runtimes = _kept_runtimes(monkeypatch, judge_work_root)
    messages = list(baselines.load(JUDGE_SCENARIO).expected["messages"])
    adapter = _JudgedAdapter(judge_work_root, base.Result(return_code=0, messages=messages, elapsed_s=0.25))
    outcome = runner.run(JUDGE_SCENARIO, adapter)
    assert (outcome.status, outcome.reason) == ("failing", "timeout"), outcome.diff
    assert outcome.diff == (
        f"{JUDGE_SCENARIO}: timeout: the scenario deadline of {JUDGE_DEADLINE_S} s passed while {phase}"
    )
    assert outcome.decoded is None and outcome.result is None
    assert outcome.record is not None and outcome.record["decoded"] is None
    assert (outcome.record["status"], outcome.record["reason"]) == ("failing", "timeout")
    assert outcome.record_text == dump_yaml(dict(outcome.record))
    assert outcome.workdir is not None and outcome.workdir.parent == judge_work_root.resolve()
    [runtime] = runtimes
    assert runtime["timeout_phase"] == phase
    assert runtime["elapsed_execute_s"] == 0.25
    if superseded is None:
        assert "superseded" not in runtime
    else:
        assert str(runtime["superseded"]).startswith(f"before the timeout the run was {superseded}")


@pytest.mark.parametrize(
    ("return_code", "status", "reason"),
    [pytest.param(0, "passed", None, id="passed"), pytest.param(4, "failing", "assertion", id="failing")],
)
def test_a_result_returned_in_time_stands_when_cleanup_overruns_the_deadline(
    monkeypatch: pytest.MonkeyPatch, judge_work_root: Path, return_code: int, status: str, reason: str | None
) -> None:
    monkeypatch.setattr(runner, "DEADLINE_S", JUDGE_DEADLINE_S)
    runtimes = _kept_runtimes(monkeypatch, judge_work_root)
    messages = list(baselines.load(JUDGE_SCENARIO).expected["messages"])
    adapter = _JudgedAdapter(
        judge_work_root, base.Result(return_code=return_code, messages=messages), overrun_cleanup=True
    )
    started = time.monotonic()
    outcome = runner.run(JUDGE_SCENARIO, adapter)
    assert time.monotonic() - started > JUDGE_DEADLINE_S
    assert (outcome.status, outcome.reason) == (status, reason), outcome.diff
    assert outcome.decoded is not None and outcome.decoded["messages"] == messages
    assert outcome.record is not None and outcome.record["decoded"] is not None
    assert outcome.record_text == dump_yaml(dict(outcome.record))
    [runtime] = runtimes
    assert "timeout_phase" not in runtime


def _late_once(real: Callable[..., object]) -> Callable[..., object]:
    """``real``, its first call returning only once the judge deadline of a
    run under ``JUDGE_DEADLINE_S`` has passed: that deadline lies at most
    ``JUDGE_DEADLINE_S`` after the end of ``cleanup``, which comes first.
    Later calls are ``real`` unchanged."""
    called: list[bool] = []

    def step(*args: object, **kwargs: object) -> object:
        value = real(*args, **kwargs)
        if not called:
            called.append(True)
            time.sleep(JUDGE_DEADLINE_S + JUDGE_PAST_S)
        return value

    return step


# The steps after judging whose work must be finished before the judge
# deadline, each finishing in full past it: the owner and name of what the
# stand-in replaces, the stand-in, and the step the timeout names. The record
# text is written without a deadline, so dump_yaml itself never raises.
LATE_STEPS = [
    pytest.param(runner, "_identify", _late_once, "identifying the run", id="identify"),
    pytest.param(results, "build_record", _late_once, "building the result record", id="record"),
    pytest.param(
        runner,
        "dump_yaml",
        functools.partial(_overrunning, stops=False),
        "rendering the result record",
        id="record-text",
    ),
]


@pytest.mark.parametrize("compare", [pytest.param(True, id="compared"), pytest.param(False, id="capture")])
@pytest.mark.parametrize(("owner", "name", "late", "phase"), LATE_STEPS)
def test_a_run_whose_record_is_finished_past_the_judge_deadline_is_a_timeout_without_an_outcome(
    monkeypatch: pytest.MonkeyPatch,
    judge_work_root: Path,
    owner: object,
    name: str,
    late: Callable[[Callable[..., object]], Callable[..., object]],
    phase: str,
    compare: bool,
) -> None:
    monkeypatch.setattr(runner, "DEADLINE_S", JUDGE_DEADLINE_S)
    monkeypatch.setattr(owner, name, late(getattr(owner, name)))
    runtimes = _kept_runtimes(monkeypatch, judge_work_root)
    messages = list(baselines.load(JUDGE_SCENARIO).expected["messages"])
    adapter = _JudgedAdapter(judge_work_root, base.Result(return_code=0, messages=messages, elapsed_s=0.25))
    outcome = runner.run(JUDGE_SCENARIO, adapter, compare=compare)
    assert (outcome.status, outcome.reason) == ("failing", "timeout"), outcome.diff
    assert outcome.diff == (
        f"{JUDGE_SCENARIO}: timeout: the scenario deadline of {JUDGE_DEADLINE_S} s passed while {phase}"
    )
    assert outcome.decoded is None and outcome.result is None
    assert outcome.record is not None and outcome.record["decoded"] is None
    assert (outcome.record["status"], outcome.record["reason"]) == ("failing", "timeout")
    assert outcome.record_text == dump_yaml(dict(outcome.record))
    [runtime] = runtimes
    assert runtime["timeout_phase"] == phase
    assert str(runtime["superseded"]).startswith("before the timeout the run was passed")


def test_decoding_stops_at_a_deadline_passing_among_the_screens(monkeypatch: pytest.MonkeyPatch) -> None:
    scenario = dataclasses.replace(
        scenarios.get(JUDGE_SCENARIO), expected={"screens": [{base.SCREEN_MAP: "COSGN0A"}]}
    )
    result = base.Result(return_code=0, screens=[{base.SCREEN_MAP: "COSGN0A"}] * 3)
    monkeypatch.setattr(baselines, "_DEADLINE_EVERY", 1)
    # The entry check reads the clock first; the deadline passes at the next
    # reading, the first screen's.
    monkeypatch.setattr(baselines, "time", SimpleNamespace(monotonic=_ReadingClock(before=1)))
    with pytest.raises(base.ScenarioTimeout) as caught:
        baselines.decode_result(scenario, result, deadline=1.0)
    assert str(caught.value) == "the scenario deadline passed while decoding the screens"


@pytest.mark.parametrize(
    ("text", "shown"),
    [
        pytest.param("AB\udcff", repr("AB\udcff"), id="short"),
        pytest.param(
            "A" * (2 * results._TEXT_SHOWN_CHARS) + "\udcff", f"{'A' * results._TEXT_SHOWN_CHARS!r}...", id="long"
        ),
    ],
)
def test_a_record_text_holding_a_lone_surrogate_is_refused_showing_at_most_its_start(text: str, shown: str) -> None:
    with pytest.raises(results.ResultError) as caught:
        results.build_record(
            scenarios.get(JUDGE_SCENARIO), "gnucobol-batch", "failing", reason="error", decoded={"messages": [text]}
        )
    offset = len(text) - 1
    assert (
        f"decoded.messages[0]: text {shown} holds the lone surrogate '\\udcff' at offset {offset}, "
        "which no record can hold"
    ) in str(caught.value)


# --------------------------------------------------------------------------
# runner.run stopped by the schema check: the failing record names the
# version of the baseline it stands against, so it is current and its
# scenario counts as failing, never as stale.
# --------------------------------------------------------------------------

# How the schema check stops the run of SCHEMA_DEMOTION_SCENARIO: it lists
# the problems of the scenario's return-code-only copy (_return_code_only),
# or it raises SCHEMA_STOP_RAISED on the committed scenario.
SCHEMA_STOP_CASES = [
    pytest.param(False, id="schema-problems"),
    pytest.param(True, id="schema-check-raised"),
]
SCHEMA_STOP_RAISED = "the schema check itself is broken"
# Whether the baseline can be read once the schema check has stopped the run;
# an unreadable one raises BaselineError with SCHEMA_STOP_UNREADABLE.
SCHEMA_STOP_BASELINES = [
    pytest.param(True, id="baseline-read"),
    pytest.param(False, id="baseline-unreadable"),
]
SCHEMA_STOP_UNREADABLE = "manifest.yaml is not valid YAML"


class _SchemaStopAdapter:
    """A gnucobol-batch adapter for runs the schema check stops: ``prepare``
    and ``execute`` must never be called, and ``cleanup`` records the
    scenario id and working directory of each call."""

    name = results.GNUCOBOL

    def __init__(self, work_root: Path) -> None:
        self.work_root = work_root
        self.deadline: float | None = None
        self.cleaned: list[tuple[str, Path]] = []

    def prepare(self, scenario: scenarios.Scenario, workdir: Path) -> None:
        raise AssertionError(f"prepare was called for {scenario.id}")

    def execute(self, scenario: scenarios.Scenario, workdir: Path) -> base.Result:
        raise AssertionError(f"execute was called for {scenario.id}")

    def cleanup(self, scenario: scenarios.Scenario, workdir: Path) -> None:
        self.cleaned.append((scenario.id, workdir))


@pytest.mark.parametrize("readable", SCHEMA_STOP_BASELINES)
@pytest.mark.parametrize("raised", SCHEMA_STOP_CASES)
def test_runner_records_a_schema_stop_against_the_baseline_it_stands_against(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, raised: bool, readable: bool
) -> None:
    """The run is ``failing``/``error`` with the schema check's diff, its
    record names the committed baseline's version, which the gnucobol-batch
    execution id is derived from, and ``results.is_current`` holds, so the
    scenario counts as failing. A baseline that cannot be read leaves the
    verdict and diff as they were, the record without a version (so stale)
    and a ``baseline_error`` note. Nothing reaches ``prepare``, and the
    runtime writer is replaced, so nothing is written in the repository."""
    committed = scenarios.get(SCHEMA_DEMOTION_SCENARIO)
    baseline = baselines.load(SCHEMA_DEMOTION_SCENARIO)
    if raised:
        scenario = committed

        def broken(checked: scenarios.Scenario) -> list[str]:
            raise RuntimeError(SCHEMA_STOP_RAISED)

        monkeypatch.setattr(scenarios, "validate", broken)
        diff = f"{scenario.id}: the schema check raised: RuntimeError: {SCHEMA_STOP_RAISED}"
    else:
        scenario = _return_code_only(committed)
        problems = scenarios.validate(scenario)
        assert any(RETURN_CODE_ONLY in problem for problem in problems), problems
        diff = f"{scenario.id}: the scenario fails the schema check:\n" + "\n".join(problems)
    if not readable:

        def unreadable(baseline_id: str, missing_ok: bool = False, **kwargs: object) -> baselines.Baseline:
            raise baselines.BaselineError(f"{baseline_id}: {SCHEMA_STOP_UNREADABLE}")

        monkeypatch.setattr(baselines, "load", unreadable)
    runtimes: list[dict[str, object]] = []

    def keep_runtime(scenario_id: str, data: Mapping[str, object]) -> Path:
        runtimes.append(dict(data))
        return work_root_case / f"{scenario_id}.yaml"

    monkeypatch.setattr(results, "write_runtime", keep_runtime)
    adapter = _SchemaStopAdapter(work_root_case)
    outcome = runner.run(scenario, adapter)
    assert (outcome.status, outcome.reason, outcome.gap_id) == (results.STATUS_FAILING, runner.REASON_ERROR, None)
    assert outcome.diff == diff
    assert adapter.cleaned == [(scenario.id, work_root_case / scenario.id)]
    assert outcome.workdir is None and not (work_root_case / scenario.id).exists()
    record = outcome.record
    assert record is not None
    assert (record["status"], record["reason"], record["gap_id"]) == (
        results.STATUS_FAILING,
        runner.REASON_ERROR,
        None,
    )
    version = baseline.version if readable else None
    assert record["baseline_version"] == version
    assert record["scenario_digest"] == scenarios.digest(scenario)
    commit = harness.git_head()
    assert record["execution_id"] == results.execution_id(
        results.GNUCOBOL, scenario, version, None, None if commit == "unknown" else commit
    )
    assert results.is_current(record, scenario, baseline) is readable
    assert len(runtimes) == 1 and runtimes[0]["diff"] == diff
    note = (
        "its baseline cannot be read, so the record names no baseline version: "
        f"BaselineError: {SCHEMA_DEMOTION_SCENARIO}: {SCHEMA_STOP_UNREADABLE}"
    )
    if readable:
        assert "baseline_error" not in runtimes[0] and note not in outcome.detail
        assert report.scenario_status(scenario, [record]).status == results.STATUS_FAILING
    else:
        assert runtimes[0]["baseline_error"] == note and note in outcome.detail


# --------------------------------------------------------------------------
# UniKixAdapter.close on fakes: the region and BPE the session started are
# stopped, and a stop that could not run is kept and raised.
# --------------------------------------------------------------------------

CLOSE_WORK_ROOT_PREFIX = "unit-unikix-close-"
CLOSE_PROBE_REASON = "kixstop is not on PATH"
CLOSE_STAMP_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")


class _CloseCommands:
    """The ``CommandSet`` ``UniKixAdapter.close`` uses: the committed
    commands.yaml entries, so each stop carries its own gap; the stops in
    ``unavailable`` probe unavailable with ``CLOSE_PROBE_REASON``, and those
    in ``failing`` raise ``error(entry_id)`` when run. ``calls`` records each
    availability check and run in order."""

    def __init__(
        self, calls: list[tuple[str, str]], unavailable: frozenset[str], failing: frozenset[str]
    ) -> None:
        self.entries = unikix_env.CommandSet.load()
        self.calls = calls
        self.unavailable = unavailable
        self.failing = failing

    def entry(self, entry_id: str) -> unikix_env.CommandEntry:
        return self.entries.entry(entry_id)

    def available(self, entry_id: str, env: object = None, *, deadline_left: float | None = None) -> bool:
        self.calls.append(("available", entry_id))
        return entry_id not in self.unavailable

    def probe_reason(self, entry_id: str) -> str:
        return CLOSE_PROBE_REASON if entry_id in self.unavailable else ""

    def error(self, entry_id: str) -> base.AdapterUnavailable:
        """A failed stop, on two lines as a command's stderr gives them."""
        return base.AdapterUnavailable(
            f"{entry_id} exited 12:\n{entry_id}: still active", self.entry(entry_id).gap
        )

    def run(
        self, entry_id: str, env: object, deadline_left: float | None = None, **values: str | int
    ) -> SimpleNamespace:
        # The session close is no scenario time: only the entry's own
        # timeout_s bounds a stop.
        assert deadline_left is None and not values
        self.calls.append(("run", entry_id))
        if entry_id in self.failing:
            raise self.error(entry_id)
        return SimpleNamespace(stdout="", stderr="", returncode=0)

    def problem(self, entry_id: str) -> str:
        """What ``_stop_started`` reports for ``entry_id`` once it could not
        stop."""
        if entry_id in self.unavailable:
            return f"{entry_id} unavailable: {CLOSE_PROBE_REASON}; it may still run"
        return f"{entry_id} failed: {self.error(entry_id)}"


@pytest.fixture
def close_work_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh, empty work root of this case's own inside the session work
    root (carddemo_work_root), never under pytest's basetemp, removed
    afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=CLOSE_WORK_ROOT_PREFIX, dir=carddemo_work_root))
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


# The committed observed baseline the capture version planning is judged on,
# and a committed derived one (a post-open fault scenario, GAP-MIS-013).
CAPTURE_SCENARIO = "TRN-CBTRN02C-negative-03"
DERIVED_SCENARIO = "TRN-CBTRN02C-recovery-02"


def _flipped(data: bytes) -> bytes:
    """``data`` with the lowest bit of its last byte inverted."""
    return data[:-1] + bytes([data[-1] ^ 0x01])


def _observed(manifest: dict, raw: dict) -> None:
    """A derived ``manifest`` and its empty ``raw`` made those of a
    gnucobol-batch observation of the one dataset DERIVED_SCENARIO names."""
    manifest.pop("derived_from", None)
    manifest.update(
        capture_platform="gnucobol-batch", execution_id="gnucobol-batch-0000000000000000",
        execution_fingerprint="0" * 64,
    )
    raw.update(ACCTDATA=b"0" * 300)


# (the baseline HEAD holds; mutation of its expected, manifest and raw
# datasets, or None for HEAD's own bytes; the level the bump check needs; a
# fragment of its reason).
BUMP_NEEDS = [
    pytest.param(CAPTURE_SCENARIO, None, "none", "holds exactly what HEAD", id="head-bytes"),
    pytest.param(
        CAPTURE_SCENARIO, lambda expected, manifest, raw: manifest.update(capture_date="1999-12-31"),
        "patch", "manifest.yaml metadata changed", id="manifest-only",
    ),
    pytest.param(
        CAPTURE_SCENARIO, lambda expected, manifest, raw: raw.update(ACCTDATA=_flipped(raw["ACCTDATA"])),
        "minor", "datasets/ACCTDATA.bin changed", id="raw-bytes-changed",
    ),
    pytest.param(
        CAPTURE_SCENARIO, lambda expected, manifest, raw: manifest.update(capture_platform="unikix"),
        "minor", "capture_platform changed from gnucobol-batch to unikix", id="capture-platform-changed",
    ),
    pytest.param(
        DERIVED_SCENARIO, lambda expected, manifest, raw: _observed(manifest, raw),
        "minor", "datasets/ACCTDATA.bin was added", id="derived-to-observed-raw-added",
    ),
    pytest.param(
        CAPTURE_SCENARIO,
        lambda expected, manifest, raw: expected["datasets"]["DALYREJS"][0].update({"DALYTRAN-AMT": "2020.02"}),
        "major", "the decoded expected outcome changed at datasets.DALYREJS[0].DALYTRAN-AMT", id="decoded-value",
    ),
]

_PATCH_NEED = baselines.BumpNeed("1.2.3", "gnucobol-batch", "patch", "manifest.yaml metadata changed")
_MINOR_NEED = baselines.BumpNeed("1.2.3", "derived", "minor", "capture_platform changed from derived to gnucobol-batch")
_MAJOR_NEED = baselines.BumpNeed(
    "1.2.3", "gnucobol-batch", "major", "the decoded expected outcome changed at datasets.DALYREJS[0].DALYTRAN-AMT"
)

# (the bump check's need against HEAD, None when HEAD lacks the baseline;
# --bump; the working-tree version, None for no copy; the planned versions).
PLANNED_VERSIONS = [
    pytest.param(None, None, None, (None, "1.0.0"), id="new"),
    pytest.param(None, "major", "1.0.0", ("1.0.0", "1.0.0"), id="written-since-head-bump-ignored"),
    pytest.param(None, "patch", "1.0.3", ("1.0.3", "1.0.0"), id="rewritten-since-head-stays-new"),
    pytest.param(
        baselines.BumpNeed("1.2.3", "gnucobol-batch", "none", "unchanged"), "patch", "1.2.3", ("1.2.3", "1.2.4"),
        id="unchanged-patch",
    ),
    pytest.param(_PATCH_NEED, "patch", "1.2.9", ("1.2.3", "1.2.4"), id="patch-from-head-not-working-tree"),
    pytest.param(_MINOR_NEED, "minor", None, ("1.2.3", "1.3.0"), id="derived-to-observed-minor"),
    pytest.param(_PATCH_NEED, "major", "1.3.0", ("1.2.3", "2.0.0"), id="above-the-need"),
    pytest.param(_MAJOR_NEED, "major", "3.0.0", ("1.2.3", "2.0.0"), id="major-from-head-not-working-tree"),
]

# (need, --bump below it or none, a fragment of the refusal).
PLAN_REFUSALS = [
    pytest.param(_MAJOR_NEED, "patch", "needs at least --bump major (--bump patch given)", id="major-change-patch"),
    pytest.param(_MAJOR_NEED, "minor", "needs at least --bump major (--bump minor given)", id="major-change-minor"),
    pytest.param(_MINOR_NEED, "patch", "needs at least --bump minor (--bump patch given)", id="platform-change-patch"),
    pytest.param(
        _PATCH_NEED, None, "exists at 1.2.3 at HEAD; replacing it needs --bump major|minor|patch", id="no-bump"
    ),
]


def _git_output(repo_root: Path, *args: str) -> bytes:
    return subprocess.run(
        ["git", *args], cwd=repo_root, capture_output=True, timeout=CLI_TIMEOUT_S, check=True
    ).stdout


def _head_baseline(repo_root: Path, folder: Path, baseline_id: str = CAPTURE_SCENARIO) -> baselines.Baseline:
    """The baseline HEAD holds for ``baseline_id``, built in memory from git's
    blobs whatever the working tree holds; its YAML is parsed in
    ``folder``."""
    prefix = f"{baselines.BASELINES_REL}/{baseline_id}/"
    listing = _git_output(repo_root, "ls-tree", "-r", "-z", "--name-only", "HEAD", "--", prefix.rstrip("/"))
    files = {
        name[len(prefix):]: _git_output(repo_root, "cat-file", "blob", f"HEAD:{name}")
        for name in listing.decode().split("\0")
        if name
    }

    def parsed(name: str) -> object:
        path = folder / name
        path.write_bytes(files[name])
        return load_yaml(path)

    raw = {
        Path(name).stem: data for name, data in files.items() if name.startswith(f"{baselines.DATASETS_DIR}/")
    }
    return baselines.Baseline(baseline_id, parsed(baselines.EXPECTED_FILE), parsed(baselines.MANIFEST_FILE), raw)


@pytest.mark.parametrize(("baseline_id", "mutate", "level", "reason"), BUMP_NEEDS)
def test_bump_needed_judges_as_the_bump_check(
    carddemo_repo_root: Path, work_root_case: Path, baseline_id: str, mutate, level: str, reason: str
) -> None:
    head = _head_baseline(carddemo_repo_root, work_root_case, baseline_id)
    expected, manifest, raw = copy.deepcopy(head.expected), dict(head.manifest), dict(head.datasets)
    if mutate is not None:
        mutate(expected, manifest, raw)
    need = baselines.bump_needed(baselines.Baseline(head.id, expected, manifest, raw), scratch=work_root_case)
    assert need is not None
    assert (need.base_version, need.base_platform, need.level) == (head.version, head.capture_platform, level)
    assert reason in need.reason, need.reason


def test_bump_needed_absent_at_base_is_new(carddemo_repo_root: Path, work_root_case: Path) -> None:
    _require_commit(carddemo_repo_root, STARTING_COMMIT)
    candidate = _head_baseline(carddemo_repo_root, work_root_case)
    assert baselines.bump_needed(candidate, STARTING_COMMIT, scratch=work_root_case) is None


@pytest.mark.parametrize("command", ["ls-tree", "cat-file"])
def test_bump_needed_git_failure_is_never_absence(
    carddemo_repo_root: Path, work_root_case: Path, monkeypatch: pytest.MonkeyPatch, command: str
) -> None:
    candidate = _head_baseline(carddemo_repo_root, work_root_case)
    real_git = baselines._git
    stderr = f"fatal: {command} cannot read the object database"

    def failing_git(root: Path, args, stdin: bytes | None = None) -> bytes:
        if args[0] == command:
            raise baselines._GitFailed(f"git {' '.join(args[:2])} failed in .: {stderr}", 128, stderr)
        return real_git(root, args, stdin)

    monkeypatch.setattr(baselines, "_git", failing_git)
    with pytest.raises(baselines.BumpCheckError) as failed:
        baselines.bump_needed(candidate, scratch=work_root_case)
    assert stderr in str(failed.value)
    with pytest.raises(capture_cli.CaptureError) as refused:
        capture_cli._head_need(candidate, work_root_case)
    message = str(refused.value)
    assert message.startswith(f"git cannot tell what HEAD holds for {baselines.BASELINES_REL}/{CAPTURE_SCENARIO}/: ")
    assert stderr in message and message.endswith("; nothing is written")


@pytest.mark.parametrize("captures", [0, 1, 2, 7])
def test_plan_version_raises_head_version(carddemo_repo_root: Path, work_root_case: Path, captures: int) -> None:
    """``captures`` earlier ``--bump patch`` captures since HEAD leave the
    working tree that many patches above HEAD; the next plans the same
    version as the first."""
    head = _head_baseline(carddemo_repo_root, work_root_case)
    working = head.version
    for _ in range(captures):
        working = baselines.bump(working, "patch")
    manifest = dict(head.manifest, baseline_version=working, capture_date="1999-12-31")
    need = baselines.bump_needed(
        baselines.Baseline(head.id, head.expected, manifest, head.datasets), scratch=work_root_case
    )
    assert need is not None and need.base_version == head.version
    planned = capture_cli.plan_version(need, "patch", SimpleNamespace(version=working))
    assert planned == (head.version, baselines.bump(head.version, "patch"))


@pytest.mark.parametrize(("need", "bump", "working", "planned"), PLANNED_VERSIONS)
def test_plan_version(need, bump: str | None, working: str | None, planned: tuple) -> None:
    existing = None if working is None else SimpleNamespace(version=working)
    assert capture_cli.plan_version(need, bump, existing) == planned


@pytest.mark.parametrize(("need", "bump", "refusal"), PLAN_REFUSALS)
def test_plan_version_refuses_an_insufficient_bump(need, bump: str | None, refusal: str) -> None:
    with pytest.raises(capture_cli.CaptureError) as refused:
        capture_cli.plan_version(need, bump, SimpleNamespace(version=need.base_version))
    message = str(refused.value)
    assert refusal in message, message
    if bump is not None:
        assert message.startswith(f"HEAD holds its {need.base_platform} baseline {need.base_version}: {need.reason}")
        assert message.endswith("; nothing is written")


@pytest.mark.parametrize(
    ("written", "all_mode", "refresh"),
    [
        pytest.param([CAPTURE_SCENARIO], False, f"pytest tests/ -k {CAPTURE_SCENARIO}", id="one-id"),
        pytest.param(
            [CAPTURE_SCENARIO, CAPTURE_SCENARIO_ID],
            False,
            f'pytest tests/ -k "{CAPTURE_SCENARIO} or {CAPTURE_SCENARIO_ID}"',
            id="several-ids",
        ),
        pytest.param([CAPTURE_SCENARIO, CAPTURE_SCENARIO_ID], True, "pytest tests/", id="all"),
        pytest.param([], False, None, id="none-written"),
    ],
)
def test_capture_summary_names_the_refresh_run(
    capsys: pytest.CaptureFixture[str], written: list[str], all_mode: bool, refresh: str | None
) -> None:
    """Once a baseline is written, the closing summary names the pytest run
    that rewrites the stale result records, over the written ids only (a
    refused capture is left out) or the whole suite after ``--all``, before
    the bump check."""
    refused = "BAT-POSTINT-normal-01"
    captures = [capture_cli.Captured(scenario_id, written=True, new_version="1.0.1") for scenario_id in written]
    captures.append(capture_cli.Captured(refused, detail="the run ended failing"))
    assert capture_cli._summarize(captures, all_mode) == 1
    lines = capsys.readouterr().err.splitlines()
    summary = f"capture.py: {len(written)} baseline(s) written, 0 skipped as implemented-unexecuted, 1 not captured"
    if refresh is None:
        assert lines == [summary], lines
        return
    assert lines == [
        summary,
        f"capture.py: rerun {refresh} so that the result records carry the new baseline versions and the reports "
        "are regenerated; until then report.py counts them as stale, which fails --check (and --check-proof for a "
        "job-chain proof scenario)",
        "capture.py: python tests/harness/baselines.py --check-bumps --base HEAD must pass "
        "before the baselines are committed",
    ], lines
    assert refused not in lines[1], lines


@pytest.mark.parametrize("work_root_env", ["unset", "another-directory"])
def test_head_need_parses_inside_the_work_root(
    carddemo_repo_root: Path, work_root_case: Path, monkeypatch: pytest.MonkeyPatch, work_root_env: str
) -> None:
    """Whatever ``CARDDEMO_TEST_WORKDIR`` names, the bump check of capture's
    version planning parses HEAD's YAML only in temporary folders directly
    in capture's work root, and removes them."""
    candidate = _head_baseline(carddemo_repo_root, work_root_case)
    work_root = work_root_case / "work-root"
    elsewhere = work_root_case / "another-directory"
    work_root.mkdir()
    elsewhere.mkdir()
    if work_root_env == "unset":
        monkeypatch.delenv(WORK_ROOT_ENV, raising=False)
    else:
        monkeypatch.setenv(WORK_ROOT_ENV, str(elsewhere))
    folders: list[Path] = []
    real_init = baselines._YamlReader.__init__

    def recording_init(reader, folder: Path) -> None:
        folders.append(Path(folder))
        real_init(reader, folder)

    monkeypatch.setattr(baselines._YamlReader, "__init__", recording_init)
    need = capture_cli._head_need(candidate, work_root)
    assert need is not None and need.level == "none", need
    assert folders
    system_temp = Path(tempfile.gettempdir())
    for folder in folders:
        assert folder.parent == work_root, folder
        assert elsewhere not in folder.parents and folder.parent != system_temp, folder
    assert not any(work_root.iterdir())
    assert not any(elsewhere.iterdir())


# Each case: whether a session started; the flags (_region_started,
# _region_down, _bpe_started) when close is called; the stops that probe
# unavailable and those whose run fails; whether the deployment root's log
# folder cannot be created; the stops close attempts; and the stops still
# owed afterwards, the first of which gives the raised gap.
CLOSE_CASES = [
    pytest.param(
        True, (True, False, True), {"region_stop"}, set(), False,
        ("region_stop", "bpe_stop"), ("region_stop",), id="region-stop-unavailable",
    ),
    pytest.param(
        True, (True, False, True), set(), {"region_stop"}, False,
        ("region_stop", "bpe_stop"), ("region_stop",), id="region-stop-raising",
    ),
    pytest.param(
        True, (True, False, True), set(), {"bpe_stop"}, False,
        ("region_stop", "bpe_stop"), ("bpe_stop",), id="bpe-stop-failing",
    ),
    pytest.param(
        True, (True, False, True), set(), {"region_stop", "bpe_stop"}, False,
        ("region_stop", "bpe_stop"), ("region_stop", "bpe_stop"), id="both-failing",
    ),
    pytest.param(
        True, (True, False, True), set(), set(), False,
        ("region_stop", "bpe_stop"), (), id="nothing-failing",
    ),
    pytest.param(False, (False, False, False), set(), set(), False, (), (), id="no-session"),
    # The adapter stopped the region itself: region_stop, which would fail,
    # is never run again.
    pytest.param(
        True, (True, True, True), set(), {"region_stop"}, False,
        ("bpe_stop",), (), id="region-already-stopped",
    ),
    pytest.param(
        True, (True, False, True), set(), {"region_stop"}, True,
        ("region_stop", "bpe_stop"), ("region_stop",), id="diagnostics-unwritable",
    ),
]


@pytest.mark.parametrize(
    ("session", "flags", "unavailable", "failing", "unwritable", "attempted", "owed"), CLOSE_CASES
)
def test_unikix_close_raises_what_it_could_not_stop(
    monkeypatch: pytest.MonkeyPatch,
    close_work_root: Path,
    session: bool,
    flags: tuple[bool, bool, bool],
    unavailable: set[str],
    failing: set[str],
    unwritable: bool,
    attempted: tuple[str, ...],
    owed: tuple[str, ...],
) -> None:
    adapter = UniKixAdapter(work_root=close_work_root, repo_root=Path(_TESTS_ROOT).parent)
    calls: list[tuple[str, str]] = []
    monkeypatch.setattr(tpe, "close_all", lambda: calls.append(("close_all", "")))
    commands = _CloseCommands(calls, frozenset(unavailable), frozenset(failing))
    log_dir = unikix_adapter.deploy.deployment_root(close_work_root, adapter.execution_id) / "logs"
    log = log_dir / unikix_adapter._SESSION_CLOSE_LOG
    # The work root's unikix folder, which UniKixEnvironment.load makes.
    log_dir.parent.parent.mkdir(parents=True)
    if unwritable:
        # The deployment root is a file, so its log folder cannot be created.
        log_dir.parent.write_text("not a directory\n", encoding="utf-8")
    if session:
        # A loaded session's environment: close appends through its append_log.
        environment = unikix_env.UniKixEnvironment(
            env_file=close_work_root / "carddemo.env", env_file_sha256="", work_root=close_work_root,
            deployment_root=log_dir.parent, variables={}, inherited_cobcpy="", sourcing_output="",
        )
        adapter._env, adapter._commands = environment, commands
    region_down = flags[1]
    adapter._region_started, adapter._region_down, adapter._bpe_started = flags

    def attempts(entry_ids: tuple[str, ...]) -> list[tuple[str, str]]:
        """Every s3270 session ended first, then each stop checked and run
        unless it probed unavailable."""
        expected = [("close_all", "")]
        for entry_id in entry_ids:
            expected.append(("available", entry_id))
            if entry_id not in commands.unavailable:
                expected.append(("run", entry_id))
        return expected

    if owed:
        problems = [commands.problem(entry_id) for entry_id in owed]
        with pytest.raises(base.AdapterUnavailable) as caught:
            adapter.close()
        assert caught.value.gap_id == commands.entry(owed[0]).gap
        reason = caught.value.reason
        assert adapter.execution_id in reason
        assert all(problem in reason for problem in problems), reason
        if unwritable:
            assert f"; the diagnostics could not be appended to {log}: " in reason
            assert not log.exists()
        else:
            assert reason.endswith(f"; diagnostics appended to {log}")
            assert log.is_relative_to(close_work_root)
            lines = log.read_text(encoding="utf-8").splitlines()
            assert [line.split(" ", 2)[1:] for line in lines] == [
                [adapter.execution_id, " ".join(problem.split())] for problem in problems
            ]
            assert all(CLOSE_STAMP_RE.fullmatch(line.split(" ", 1)[0]) for line in lines)
    else:
        assert adapter.close() is None
        assert not log.exists()
    assert calls == attempts(attempted)
    assert adapter._pending_stops() == list(owed)
    assert adapter._region_down is (
        region_down or ("region_stop" in attempted and "region_stop" not in owed)
    )
    assert adapter._bpe_started is ("bpe_stop" in owed)

    # Once the stops work, a second close attempts only those still owed and
    # keeps the diagnostics as they were; a third has nothing left to stop.
    kept = log.read_bytes() if log.exists() else None
    commands.unavailable = commands.failing = frozenset()
    calls.clear()
    assert adapter.close() is None
    assert calls == attempts(owed)
    assert adapter._pending_stops() == []
    assert adapter._region_down is (region_down or "region_stop" in attempted)
    assert adapter._bpe_started is False
    assert (log.read_bytes() if log.exists() else None) == kept
    calls.clear()
    assert adapter.close() is None
    assert calls == [("close_all", "")]


MATRIX_UNIKIX_EARLIER = "unikix-20250101T000000Z-0123abcd"
MATRIX_UNIKIX_SHARED = "unikix-20250102T000000Z-0123abcd"


def _matrix_result(scenario_id: str) -> str:
    return f"tests/reports/results/{scenario_id}.yaml"


def _matrix_unikix(execution_id: str, scenario_id: str) -> str:
    return f"tests/reports/unikix/{execution_id}/{scenario_id}"


# Each case: the passed records of each scenario listing the item, as
# (adapter, execution_id, evidence_path), given out of the (adapter,
# execution id) order the status rules sort them into; and the
# (execution_id, evidence_path) pairs the item's matrix row must carry, in
# scenario order.
MATRIX_PAIRING_CASES = [
    pytest.param(
        {
            "TRN-CBTRN02C-boundary-02": [
                (results.GNUCOBOL, "gnucobol-batch-770e608f2a41c701", _matrix_result("TRN-CBTRN02C-boundary-02")),
            ],
            "TRN-CBTRN02C-negative-04": [
                (results.GNUCOBOL, "gnucobol-batch-111f877390bb2c24", _matrix_result("TRN-CBTRN02C-negative-04")),
            ],
        },
        [
            ("gnucobol-batch-770e608f2a41c701", _matrix_result("TRN-CBTRN02C-boundary-02")),
            ("gnucobol-batch-111f877390bb2c24", _matrix_result("TRN-CBTRN02C-negative-04")),
        ],
        id="execution-ids-sorting-opposite-to-scenario-ids",
    ),
    # One UniKix execution covers both scenarios: its id is kept once per
    # scenario, beside that scenario's own evidence path.
    pytest.param(
        {
            "SEC-COSGN00C-negative-01": [
                (results.UNIKIX, execution_id, _matrix_unikix(execution_id, "SEC-COSGN00C-negative-01"))
                for execution_id in (MATRIX_UNIKIX_SHARED, MATRIX_UNIKIX_EARLIER)
            ],
            "SEC-COSGN00C-normal-01": [
                (results.UNIKIX, MATRIX_UNIKIX_SHARED, _matrix_unikix(MATRIX_UNIKIX_SHARED, "SEC-COSGN00C-normal-01")),
            ],
        },
        [
            (MATRIX_UNIKIX_EARLIER, _matrix_unikix(MATRIX_UNIKIX_EARLIER, "SEC-COSGN00C-negative-01")),
            (MATRIX_UNIKIX_SHARED, _matrix_unikix(MATRIX_UNIKIX_SHARED, "SEC-COSGN00C-negative-01")),
            (MATRIX_UNIKIX_SHARED, _matrix_unikix(MATRIX_UNIKIX_SHARED, "SEC-COSGN00C-normal-01")),
        ],
        id="unikix-execution-shared-by-two-scenarios",
    ),
    # A record without an execution id keeps its place with '' in that
    # column, one with neither value is left out, and an exact repeat of a
    # pair is listed once.
    pytest.param(
        {
            "TRN-CBTRN02C-boundary-01": [(results.GNUCOBOL, None, _matrix_result("TRN-CBTRN02C-boundary-01"))],
            "TRN-CBTRN02C-boundary-02": [(results.GNUCOBOL, None, None)],
            "TRN-CBTRN02C-negative-04": [
                (results.GNUCOBOL, "gnucobol-batch-111f877390bb2c24", _matrix_result("TRN-CBTRN02C-negative-04")),
                (results.GNUCOBOL, "gnucobol-batch-111f877390bb2c24", _matrix_result("TRN-CBTRN02C-negative-04")),
            ],
        },
        [
            ("", _matrix_result("TRN-CBTRN02C-boundary-01")),
            ("gnucobol-batch-111f877390bb2c24", _matrix_result("TRN-CBTRN02C-negative-04")),
        ],
        id="missing-value-blank-and-exact-repeat-once",
    ),
]


@pytest.mark.parametrize(("deciding", "pairs"), MATRIX_PAIRING_CASES)
def test_matrix_pairs_each_execution_id_with_its_evidence_path(
    deciding: dict[str, list[tuple[str, str | None, str | None]]], pairs: list[tuple[str, str]]
) -> None:
    item_id, source = "INV-TRN-077", "app/cbl/CBTRN02C.cbl:414-420"
    scenario_ids = sorted(deciding)
    listing = {
        scenario_id: SimpleNamespace(id=scenario_id, expected={"return_code": 4}) for scenario_id in scenario_ids
    }
    statuses = {
        scenario_id: report.scenario_status(listing[scenario_id], [
            {"adapter": adapter, "execution_id": execution_id, "evidence_path": path, "status": results.STATUS_PASSED}
            for adapter, execution_id, path in records
        ])
        for scenario_id, records in deciding.items()
    }
    state = SimpleNamespace(
        items=[{"id": item_id, "source": source}], scenarios=listing, statuses=statuses,
        item_scenarios={item_id: scenario_ids}, item_status={item_id: report.VERIFIED},
    )
    header, *lines = report.render_matrix(state).splitlines()
    assert header == ",".join(report.MATRIX_HEADER)
    assert len(lines) == 1
    row = dict(zip(report.MATRIX_HEADER, lines[0].split(","), strict=True))
    assert row == {
        "inventory_id": item_id,
        "implementation_locations": source,
        "scenario_ids": ";".join(scenario_ids),
        "assertions": ";".join(f"{scenario_id}:return_code" for scenario_id in scenario_ids),
        "status": report.VERIFIED,
        "execution_id": ";".join(execution_id for execution_id, _ in pairs),
        "evidence_path": ";".join(path for _, path in pairs),
    }
    # The i-th execution id is the one recorded in the i-th evidence path.
    assert list(zip(row["execution_id"].split(";"), row["evidence_path"].split(";"), strict=True)) == pairs


# --------------------------------------------------------------------------
# CSV formula neutralization: a register or matrix cell whose text begins
# with a spreadsheet formula prefix is written with a leading "'", the state
# it was rendered from keeps the exact text, and the matrix check reads the
# rows back as render_matrix wrote them.
# --------------------------------------------------------------------------

CSV_GAP_ID = "GAP-UNC-001"
CSV_AFFECTED = "INV-DAT-001"
CSV_FORMULA_CASES = [
    pytest.param("=1+1", "'=1+1", id="equals"),
    pytest.param("+1+1", "'+1+1", id="plus"),
    pytest.param("-1+1", "'-1+1", id="minus"),
    pytest.param("@SUM(A1)", "'@SUM(A1)", id="at"),
    pytest.param("\t=1+1", "'\t=1+1", id="tab"),
    pytest.param("\r=1+1", "'\r=1+1", id="carriage-return"),
    pytest.param("\n=1+1", "'\n=1+1", id="line-feed"),
    pytest.param("\uff1d1+1", "'\uff1d1+1", id="full-width-equals"),
    pytest.param("\uff0b1+1", "'\uff0b1+1", id="full-width-plus"),
    pytest.param("\uff0d1+1", "'\uff0d1+1", id="full-width-minus"),
    pytest.param("\uff20SUM(A1)", "'\uff20SUM(A1)", id="full-width-at"),
    # Text that begins with no prefix is written as it is.
    pytest.param("The owner's statement of purpose", "The owner's statement of purpose", id="plain"),
    pytest.param("a=1+1", "a=1+1", id="inner-equals"),
    pytest.param("", "", id="empty"),
    pytest.param(None, "", id="none"),
    pytest.param("'=1+1", "'=1+1", id="leading-apostrophe"),
]


@pytest.mark.parametrize(("text", "cell"), CSV_FORMULA_CASES)
def test_register_csv_neutralizes_formula_prefixes_and_leaves_the_entry_exact(text: str | None, cell: str) -> None:
    entry = {
        "category": "uncertain-purpose", "unknown": text, "affected_inventory_ids": [CSV_AFFECTED],
        "resolving_evidence": text, "needed": text, "status": "open",
    }
    original = copy.deepcopy(entry)
    state = SimpleNamespace(gaps=SimpleNamespace(entries={CSV_GAP_ID: entry}), affected={CSV_GAP_ID: [CSV_AFFECTED]})
    rows = list(csv.reader(io.StringIO(report.render_register(state))))
    assert rows == [
        list(report.REGISTER_HEADER),
        [CSV_GAP_ID, "uncertain-purpose", cell, CSV_AFFECTED, cell, cell, "open"],
    ]
    assert entry == original


def test_matrix_csv_neutralizes_formula_prefixes_and_the_check_reads_its_rows_back(
    work_root_case: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # The inventory refuses an id that is not INV-<AREA>-<nnn>; the second id
    # stands for any row name the check must compare as render_matrix wrote it.
    items = [
        {"id": "INV-TRN-077", "source": '=HYPERLINK("http://example.invalid")'},
        {"id": "@INV-TRN-078", "source": "app/cbl/CBTRN02C.cbl:414-420"},
    ]
    original = copy.deepcopy(items)
    state = SimpleNamespace(
        items=items, scenarios={}, statuses={},
        item_scenarios={item["id"]: [] for item in items},
        item_status={item["id"]: report.NOT_COVERED for item in items},
    )
    text = report.render_matrix(state)
    assert list(csv.reader(io.StringIO(text))) == [
        list(report.MATRIX_HEADER),
        ["INV-TRN-077", '\'=HYPERLINK("http://example.invalid")', "", "", report.NOT_COVERED, "", ""],
        ["'@INV-TRN-078", "app/cbl/CBTRN02C.cbl:414-420", "", "", report.NOT_COVERED, "", ""],
    ]
    assert items == original
    matrix = work_root_case / "coverage-matrix.csv"
    monkeypatch.setattr(report, "MATRIX_FILE", matrix)
    matrix.write_text(text, encoding="utf-8")
    assert report._matrix_findings(state) == []
    # A row left out is still named.
    matrix.write_text("".join(text.splitlines(keepends=True)[:2]), encoding="utf-8")
    assert report._matrix_findings(state) == ["coverage matrix: no row for 1 inventory items: '@INV-TRN-078"]


# --------------------------------------------------------------------------
# The BPE restart fallback of the unikix quiescence check on fakes: without
# bpe_active_jobs, the BPE work the batch service tracks is settled before
# bpe_stop and bpe_start run, or the session is blocked under GAP-MIS-004.
# --------------------------------------------------------------------------

RESTART_WORK_ROOT_PREFIX = "unit-unikix-restart-"
RESTART_SCENARIO = "TRN-POSTTRAN-normal-01"
# The copy member job submitted, as BPE job 42 or without a job number.
RESTART_JOB = "POSTTRAN"
RESTART_NUMBER = "42"
RESTART_ACCEPTED = "JOB00042 submitted\n"
RESTART_UNNUMBERED = "submission queued\n"
# Job 42's sysout entry and the job log in it, as bpe_sysout's parse reads
# them: the job still running, or ended.
RESTART_ENTRY = "POSTTRAN.00042"
RESTART_LOG = f"{RESTART_ENTRY}/JESMSGLG"
RESTART_RUNNING = b"POSTTRAN(JOB00042) STARTED 2025-03-04 10:00:00\n"
RESTART_ENDED = RESTART_RUNNING + b"POSTTRAN(JOB00042) JOB ENDED\n"
# Every entry the restart fallback and the submission use, bpe_active_jobs
# and bpe_cancel excepted.
RESTART_AVAILABLE = frozenset(
    {
        "region_quiesce_query",
        "region_stop",
        "bpe_stop",
        "bpe_start",
        "bpe_system",
        "bpe_translate",
        "bpe_submit",
        "bpe_sysout",
    }
)


class _RestartCommands:
    """The ``CommandSet`` of a session whose BPE quiescence falls back to a
    restart: the committed commands.yaml entries, so every parse and gap is
    the shipped one. The entries in ``found`` probe available; any other is
    refused when run, as ``CommandSet.run`` refuses it. ``bpe_submit`` prints
    ``submitted``, ``bpe_sysout`` lists the entries of ``sysout``, every other
    command prints nothing. ``probes`` records each probe that names a
    deployment environment and ``calls`` each command run with its values.
    Every probe and run must be capped at a scenario deadline exactly when
    ``bounded``."""

    def __init__(self, found: frozenset[str], sysout: Path, submitted: str, bounded: bool) -> None:
        self.entries = unikix_env.CommandSet.load()
        self.found = found
        self.sysout = sysout
        self.submitted = submitted
        self.bounded = bounded
        self.probes: list[str] = []
        self.calls: list[tuple[str, dict]] = []

    def _capped(self, deadline_left: float | None) -> None:
        assert (deadline_left is not None) is self.bounded, deadline_left
        assert deadline_left is None or deadline_left > 0

    def entry(self, entry_id: str) -> unikix_env.CommandEntry:
        return self.entries.entry(entry_id)

    def available(self, entry_id: str, env: object = None, *, deadline_left: float | None = None) -> bool:
        self._capped(deadline_left)
        if env is not None:
            self.probes.append(entry_id)
        return entry_id in self.found

    def probe_reason(self, entry_id: str) -> str:
        return "" if entry_id in self.found else f"probe found no {entry_id}"

    def mark(self) -> int:
        return len(self.calls)

    def run(
        self, entry_id: str, env: object, deadline_left: float | None = None, **values: str | int
    ) -> SimpleNamespace:
        self._capped(deadline_left)
        if entry_id not in self.found:
            raise base.AdapterUnavailable(
                f"{entry_id} unavailable: {self.probe_reason(entry_id)}", self.entry(entry_id).gap
            )
        self.calls.append((entry_id, dict(values)))
        if entry_id == "bpe_submit":
            stdout = self.submitted
        elif entry_id == "bpe_sysout":
            stdout = "".join(f"{path.name}\n" for path in sorted(self.sysout.iterdir()))
        else:
            stdout = ""
        return SimpleNamespace(stdout=stdout, stderr="", returncode=0)


@pytest.fixture
def restart_work_root(carddemo_work_root: Path) -> Iterator[Path]:
    """A fresh, empty work root of this case's own inside the session work
    root (carddemo_work_root), never under pytest's basetemp, given by its real
    path and removed afterwards."""
    directory = Path(tempfile.mkdtemp(prefix=RESTART_WORK_ROOT_PREFIX, dir=carddemo_work_root)).resolve()
    try:
        yield directory
    finally:
        shutil.rmtree(directory)


def _restart_service(
    root: Path, found: frozenset[str], tracked: str, log: bytes | None, bounded: bool
) -> tuple[bpe.BatchService, _RestartCommands]:
    """A ``BatchService`` on the fake deployment root ``root``, whose sysout
    directory holds ``log`` as job 42's job log (no entry when ``None``),
    tracking ``tracked``: ``nothing``, job 42 submitted and not seen completed
    (``unfinished``), or a submission whose job number could not be read
    (``unidentified``). The commands of that submission are not recorded."""
    project = root / "project"
    sysout = project / "sysoutdir"
    sysout.mkdir(parents=True)
    if log is not None:
        (sysout / RESTART_ENTRY).mkdir()
        (sysout / RESTART_LOG).write_bytes(log)
    submitted = RESTART_UNNUMBERED if tracked == "unidentified" else RESTART_ACCEPTED
    commands = _RestartCommands(found, sysout, submitted, bounded)
    env = SimpleNamespace(vars={"PROJECT_ROOT": str(project)}, deployment_root=str(root))
    service = bpe.BatchService(env, commands, bpe.Resources.load())
    deadline = time.monotonic() + 300 if bounded else None
    if tracked == "unfinished":
        assert service.submit(RESTART_JOB, deadline=deadline).job_number == RESTART_NUMBER
    elif tracked == "unidentified":
        with pytest.raises(base.AdapterUnavailable, match="job number not found"):
            service.submit(RESTART_JOB, deadline=deadline)
    assert service.pending is (tracked != "nothing")
    commands.probes.clear()
    commands.calls.clear()
    return service, commands


# Each case: what the batch service tracks, whether bpe_cancel probes
# available, job 42's job log in the sysout directory (None for none),
# whether a scenario deadline caps the call, the commands it runs, and whether
# it raises GAP-MIS-004, leaving the tracked work as it was. The ended log of
# the last case names the submission's JOB card, which still identifies no
# job of its own.
RESTART_RECONCILE_CASES = [
    pytest.param("nothing", True, None, False, [], False, id="nothing-tracked"),
    pytest.param(
        "unfinished", True, RESTART_RUNNING, True, [("bpe_cancel", {"job_number": RESTART_NUMBER})],
        False, id="job-cancelled",
    ),
    pytest.param("unfinished", False, RESTART_ENDED, False, [("bpe_sysout", {})], False, id="job-shown-ended"),
    pytest.param(
        "unfinished", False, RESTART_RUNNING, True, [("bpe_sysout", {})], True, id="job-not-shown-ended"
    ),
    pytest.param("unidentified", True, RESTART_ENDED, False, [], True, id="submission-unidentified"),
]


@pytest.mark.parametrize(
    ("tracked", "cancel", "log", "bounded", "runs", "raises"), RESTART_RECONCILE_CASES
)
def test_bpe_reconcile_for_restart_clears_only_work_proven_ended(
    restart_work_root: Path,
    tracked: str,
    cancel: bool,
    log: bytes | None,
    bounded: bool,
    runs: list[tuple[str, dict]],
    raises: bool,
) -> None:
    found = RESTART_AVAILABLE | ({"bpe_cancel"} if cancel else set())
    service, commands = _restart_service(restart_work_root / "deployment", found, tracked, log, bounded)
    unfinished, unidentified = dict(service._unfinished), list(service._unidentified)
    deadline = time.monotonic() + 300 if bounded else None
    if raises:
        with pytest.raises(base.AdapterUnavailable) as caught:
            service.reconcile_for_restart(deadline=deadline)
        assert caught.value.gap_id == "GAP-MIS-004"
        labels = [run.label for run in unfinished.values()] + [attempt.label for attempt in unidentified]
        assert labels and all(label in caught.value.reason for label in labels), caught.value.reason
        assert service.pending
        assert service._unfinished == unfinished and service._unidentified == unidentified
    else:
        assert service.reconcile_for_restart(deadline=deadline) is None
        assert not service.pending
    assert commands.calls == runs
    # bpe_cancel is probed only for a job not seen completed, never for a
    # submission without a job number.
    assert commands.probes == (["bpe_cancel"] if tracked == "unfinished" else [])
    polled = ("bpe_sysout", {}) in runs
    assert service.take_evidence() == ({f"{RESTART_NUMBER}/{RESTART_LOG}": log} if polled else {})
    if unidentified:
        # The submission is still unresolved, so the next submit is refused
        # before it runs anything.
        with pytest.raises(base.AdapterUnavailable) as refused:
            service.submit(RESTART_JOB, deadline=deadline)
        assert refused.value.gap_id == "GAP-MIS-006"
        assert unidentified[0].label in refused.value.reason
        assert commands.calls == runs


# Each case: what the batch service tracks when cleanup confirms quiescence
# after RESTART_SCENARIO, whether bpe_cancel probes available, the commands
# cleanup runs, and whether the session ends blocked.
RESTART_CLEANUP_CASES = [
    pytest.param(
        "nothing", False, ["region_quiesce_query", "bpe_stop", "bpe_start", "bpe_system"], False,
        id="nothing-pending",
    ),
    pytest.param(
        "unfinished", True, ["region_quiesce_query", "bpe_cancel", "bpe_stop", "bpe_start", "bpe_system"],
        False, id="job-cancelled-before-the-restart",
    ),
    pytest.param(
        "unidentified", False, ["region_quiesce_query", "region_stop"], True, id="submission-unidentified"
    ),
]


@pytest.mark.parametrize(("tracked", "cancel", "runs", "blocked"), RESTART_CLEANUP_CASES)
def test_unikix_cleanup_restarts_bpe_only_over_settled_work(
    monkeypatch: pytest.MonkeyPatch,
    restart_work_root: Path,
    tracked: str,
    cancel: bool,
    runs: list[str],
    blocked: bool,
) -> None:
    found = RESTART_AVAILABLE | ({"bpe_cancel"} if cancel else set())
    service, commands = _restart_service(restart_work_root / "deployment", found, tracked, None, False)
    unidentified = list(service._unidentified)
    adapter = UniKixAdapter(work_root=restart_work_root, repo_root=Path(_TESTS_ROOT).parent)
    adapter._session_ready = True
    adapter._env, adapter._commands, adapter._batch = service.env, commands, service
    adapter._capture = evidence.Capture()
    adapter._touched = True
    monkeypatch.setattr(tpe, "close_all", lambda: None)
    resets: list[object] = []
    monkeypatch.setattr(adapter, "_attempt_reset", resets.append)
    scenario = SimpleNamespace(id=RESTART_SCENARIO, inputs={"job": RESTART_JOB}, expected={}, preconditions={})
    workdir = restart_work_root / RESTART_SCENARIO
    assert adapter.cleanup(scenario, workdir) is None
    assert [entry_id for entry_id, _ in commands.calls] == runs
    assert list(restart_work_root.iterdir()) == [restart_work_root / "deployment"]
    if not blocked:
        assert adapter._broken is None and not adapter._region_down
        assert not service.pending
        return
    # The submission is neither resolved nor restarted over: the session is
    # blocked, its region stopped, and the submission still tracked.
    broken = adapter._broken
    assert broken is not None and broken.startswith(f"quiescence after {RESTART_SCENARIO} not confirmed: ")
    assert unidentified[0].label in broken
    assert adapter._region_down
    assert service.pending and service._unidentified == unidentified
    assert broken in adapter._capture.sysout["adapter/adapter-errors.txt"]
    # The next scenario is refused under GAP-MIS-004 before any reset.
    commands.calls.clear()
    with pytest.raises(base.AdapterUnavailable) as caught:
        adapter.prepare(scenario, workdir)
    assert caught.value.gap_id == "GAP-MIS-004"
    assert unidentified[0].label in caught.value.reason
    assert resets == [] and commands.calls == []
    assert adapter._plan is None and not adapter._touched
