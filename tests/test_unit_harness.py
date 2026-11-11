"""Harness self-tests.

These check the harness itself. They are not inventory scenarios and never
verify an inventory item on their own. They read the committed layouts and
scenarios and write nothing in the repository.

Codec reconstruction over REDEFINES: a record ``decode_record`` decodes must
encode back to the same bytes in ASCII and cp037, a positive overpunch
included, and views that describe different bytes must be refused.

Deterministic projection: captures that differ only in the run clock must
project to the same result YAML, with every screen's CURDATE and CURTIME
holding their matcher kind whether or not the step asserts them.

Same region day: ``tpe.drive``, on a fake terminal whose region clock passes
midnight during the ACT-COACTUPC-boundary-24 steps, must drive the steps
again after its ``restart``, or end under GAP-MIS-015 without one, so the run
date typed and the date the birth date is judged against are one day.

Evidence ownership: ``EvidenceWriter.write``, on a tests root of the case's
own, must refuse a scenario folder that already exists, whatever stands
there, and keep it as found; of concurrent writes of one scenario exactly one
must succeed, and the folder must hold its files alone. An id outside the
scenario id manifest is refused by ``write`` and ``evidence_path``, and a
record that breaks the result record contract by ``write``, before any folder
is created; a conforming record must read back field-equal.
"""

from __future__ import annotations

import copy
import datetime
import functools
import os
import shutil
import sys
import tempfile
import threading
from collections.abc import Iterator, Mapping
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

import pytest

_TESTS_ROOT = str(Path(__file__).resolve().parent)
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters import base  # noqa: E402
from adapters.unikix import evidence, tpe  # noqa: E402
from harness import (  # noqa: E402
    HarnessError,
    baselines,
    codec,
    dump_yaml,
    layouts,
    load_yaml,
    results,
    scenarios,
)

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
# drive's working directory lies in the suite's work root, where every run
# writes: a non-blank CARDDEMO_TEST_WORKDIR, or else a root made by
# tempfile.mkdtemp(prefix="carddemo-").
WORK_ROOT_ENV = "CARDDEMO_TEST_WORKDIR"
DEFAULT_WORK_ROOT_PREFIX = "carddemo-"
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
def day_guard_workdir() -> Iterator[Path]:
    """A fresh directory of this case's own inside the work root, never under
    pytest's basetemp. A configured work root is created when missing and
    kept; the directory is removed afterwards, with the default root when this
    fixture made it."""
    configured = os.environ.get(WORK_ROOT_ENV, "")
    made = None if configured.strip() else Path(tempfile.mkdtemp(prefix=DEFAULT_WORK_ROOT_PREFIX))
    try:
        root = Path(configured) if made is None else made
        root.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix=DAY_GUARD_WORKDIR_PREFIX, dir=root))
        try:
            yield directory
        finally:
            shutil.rmtree(directory)
    finally:
        if made is not None:
            shutil.rmtree(made)


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
def evidence_tests_root() -> Iterator[Path]:
    """A fresh, empty tests root of this case's own inside the work root,
    where every run writes, never under pytest's basetemp or in the checkout.
    A configured work root is created when missing and kept; the tests root
    is removed afterwards, with the default work root when this fixture made
    it."""
    configured = os.environ.get(WORK_ROOT_ENV, "")
    made = None if configured.strip() else Path(tempfile.mkdtemp(prefix=DEFAULT_WORK_ROOT_PREFIX))
    try:
        root = Path(configured) if made is None else made
        root.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix=EVIDENCE_ROOT_PREFIX, dir=root))
        try:
            yield directory
        finally:
            shutil.rmtree(directory)
    finally:
        if made is not None:
            shutil.rmtree(made)


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


def test_concurrent_evidence_writes_of_one_scenario_leave_one_writers_files(
    evidence_tests_root: Path,
) -> None:
    record = _evidence_record()
    barrier = threading.Barrier(EVIDENCE_WRITERS)
    outcomes: dict[int, str | BaseException] = {}

    def names(writer: int) -> list[str]:
        return [f"DS{writer:02d}-{number:02d}" for number in range(EVIDENCE_WRITER_DATASETS)]

    def write(writer: int) -> None:
        try:
            capture = evidence.Capture(datasets={name: bytes([writer]) * 64 for name in names(writer)})
            own = evidence.EvidenceWriter(evidence_tests_root, EVIDENCE_EXECUTION_ID)
            barrier.wait(EVIDENCE_BARRIER_TIMEOUT_S)
            outcomes[writer] = own.write(EVIDENCE_SCENARIO, record, capture)
        except Exception as exc:
            outcomes[writer] = exc

    threads = [threading.Thread(target=write, args=(writer,)) for writer in range(EVIDENCE_WRITERS)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    winners = [writer for writer, outcome in outcomes.items() if isinstance(outcome, str)]
    refusals = [outcome for outcome in outcomes.values() if not isinstance(outcome, str)]
    assert sorted(outcomes) == list(range(EVIDENCE_WRITERS))
    assert len(winners) == 1, outcomes
    assert all(
        isinstance(refusal, HarnessError) and str(refusal).startswith("evidence is immutable: ")
        for refusal in refusals
    ), refusals
    folder = _evidence_folder(evidence_tests_root)
    files = sorted(os.listdir(folder / evidence.DATASETS_DIR))
    assert files == sorted(evidence.dataset_file_name(name) for name in names(winners[0]))
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
