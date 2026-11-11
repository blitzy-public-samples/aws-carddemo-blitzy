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

CSD utility gate: ``UniKixAdapter.prepare``, on a started session whose
commands and BPE service are fakes, must block a job with a ``DFHCSDUP``
step, alone or in a chain, under GAP-MIS-016 before anything reaches the
region, after the broken-session GAP-MIS-004 and the job-name check, and let
any other job past the gate.

JOBS capture: ``UniKixAdapter``'s ``@TDQ:JOBS`` capture, on fakes, must
attribute only ``TRNRPT00`` jobs absent from the listing taken just before
the first step (again after a day-change restart) whose listed time, to its
precision, lies wholly within the steps; leave out, with a note, jobs listed
before the window or after the last step; block under GAP-MIS-017 a job its
precision cannot place inside or outside the steps at either end, one listed
only once polling began included, a listing line that is not a job line and a
job number listed twice; read a blank listing as no job, a listed time (year,
month or day first) at the precision it shows and a malformed one as no time;
and keep every observation in the evidence as soon as it is acquired, through
``BatchService.observe``'s ``keep`` as well, so a later failure loses none.

Evidence ownership: ``EvidenceWriter.write``, on a tests root of the case's
own, must refuse a scenario folder that already exists, whatever stands
there, and keep it as found; of concurrent writes of one scenario exactly one
must succeed, and the folder must hold its files alone. An id outside the
scenario id manifest is refused by ``write`` and ``evidence_path``, and a
record that breaks the result record contract by ``write``, before any folder
is created; a conforming record must read back field-equal.

Program launch: ``jobs.run_program``, on a build cache of the case's own whose
units are shell scripts writing what libcob and ld.so write, must raise
``HarnessError`` once the evidence is written for a fatal libcob error that is
no program behaviour (a module not found, with or without the source location)
and for a page-zero shim ld.so did not preload; it must end an I/O condition
on a file without FILE STATUS as an abend whose messages are the DISPLAY lines
alone, and keep a business return code with libcob warnings. Under a work root
whose path holds a space or a colon, ``LD_PRELOAD`` must name the shim from
the working directory without either, and ``command.yaml`` show it under
``<build>``; a shim whose path from there still holds one is refused unrun.

Process-group scope: ``datasets.kill_all(within=...)`` must end only the
process groups ``run_process`` started in that directory or below it, as real
paths, and ``GnuCOBOLBatchAdapter.cleanup`` only those of the scenario's own
working directory, never another scenario's, the work root's or one outside
it, while the other programs run on to their own end; ``kill_all()`` and
``close`` end every group.
"""

from __future__ import annotations

import copy
import datetime
import functools
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from collections.abc import Iterator, Mapping
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

import pytest

_TESTS_ROOT = str(Path(__file__).resolve().parent)
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters import base  # noqa: E402
from adapters.gnucobol_batch import datasets, jobs  # noqa: E402
from adapters.gnucobol_batch.adapter import GnuCOBOLBatchAdapter  # noqa: E402
from adapters.unikix import adapter as unikix_adapter  # noqa: E402
from adapters.unikix import bpe, evidence, tpe  # noqa: E402
from adapters.unikix import env as unikix_env  # noqa: E402
from adapters.unikix.adapter import UniKixAdapter  # noqa: E402
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
    """The ``CommandSet`` prepare reads before the gate: its log mark."""

    def mark(self) -> int:
        return 0


class _GateBatch(_GateFake):
    """The ``BatchService`` prepare reads before the gate: the committed
    resources.yaml and no pending evidence."""

    def __init__(self) -> None:
        self.resources = bpe.Resources.load()

    def take_evidence(self) -> dict[str, bytes]:
        return {}


@pytest.fixture
def csd_gate_work_root() -> Iterator[Path]:
    """A fresh, empty directory of this case's own inside the work root,
    never under pytest's basetemp. A configured work root is created when
    missing and kept; the directory is removed afterwards, with the default
    root when this fixture made it."""
    configured = os.environ.get(WORK_ROOT_ENV, "")
    made = None if configured.strip() else Path(tempfile.mkdtemp(prefix=DEFAULT_WORK_ROOT_PREFIX))
    try:
        root = Path(configured) if made is None else made
        root.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix=CSD_GATE_WORK_ROOT_PREFIX, dir=root))
        try:
            yield directory
        finally:
            shutil.rmtree(directory)
    finally:
        if made is not None:
            shutil.rmtree(made)


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
    monkeypatch: pytest.MonkeyPatch, listings: list[str], jcl: Mapping[int, str] | None = None
) -> tuple[UniKixAdapter, _JobsCommands, list[tuple[str, str]]]:
    """A ``UniKixAdapter`` with a fresh capture, fake commands answering
    ``listings`` and ``jcl``, the committed resources.yaml, a scenario
    deadline 300 s away, and the jobs it would wait for recorded instead,
    each of which must have been registered (``adopt``) first."""
    work_root = Path(os.environ.get(WORK_ROOT_ENV, "").strip() or tempfile.gettempdir())
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
    instants: list[datetime.datetime],
    listings: list[str],
    restarts: int,
    captured: int,
    left_out: dict[int, str],
) -> None:
    adapter, commands, awaited = _jobs_adapter(monkeypatch, listings, {captured: JOBS_JCL})
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
    monkeypatch: pytest.MonkeyPatch, listed: str, left_out: str | None
) -> None:
    listing = JOBS_BEFORE_STEPS + f"  18 TRNRPT00 {listed}\n"
    adapter, commands, awaited = _jobs_adapter(monkeypatch, [listing], {18: JOBS_JCL})
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
    monkeypatch: pytest.MonkeyPatch, listed: str, precision: str
) -> None:
    # Job 21, listed first, was submitted during the steps; it is not
    # captured either once job 18 makes the listing no observation.
    listing = JOBS_BEFORE_STEPS + "  21 TRNRPT00 2025-03-04 10:01:15\n" + f"  18 TRNRPT00 {listed}\n"
    adapter, commands, awaited = _jobs_adapter(monkeypatch, [listing])
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
    monkeypatch: pytest.MonkeyPatch, snapshot: str, listed: str
) -> None:
    listings = [snapshot, snapshot, snapshot + f"  18 TRNRPT00 {listed}\n"]
    adapter, commands, awaited = _jobs_adapter(monkeypatch, listings, {18: JOBS_JCL})
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
    monkeypatch: pytest.MonkeyPatch, listing: str, named: str, listing_name: str
) -> None:
    adapter, commands, awaited = _jobs_adapter(monkeypatch, [listing], {18: JOBS_JCL})
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
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    adapter, commands, awaited = _jobs_adapter(monkeypatch, ["\n   \n\t\n"])
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
    monkeypatch: pytest.MonkeyPatch, fails_at: str | None, gap_id: str | None, kept: tuple[str, ...]
) -> None:
    listing = JOBS_BEFORE_STEPS + JOBS_LISTED_INSIDE
    adapter, _, _ = _jobs_adapter(monkeypatch, [listing], {18: JOBS_JCL})
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
LAUNCH_CC_TIMEOUT_S = 120

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
def launch_root() -> Iterator[Path]:
    """A fresh directory of this case's own inside the work root, never under
    pytest's basetemp, given by its real path. A configured work root is
    created when missing and kept; the directory is removed afterwards, with
    the default root when this fixture made it."""
    configured = os.environ.get(WORK_ROOT_ENV, "")
    made = None if configured.strip() else Path(tempfile.mkdtemp(prefix=DEFAULT_WORK_ROOT_PREFIX))
    try:
        root = Path(configured) if made is None else made
        root.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix=LAUNCH_ROOT_PREFIX, dir=root)).resolve()
        try:
            yield directory
        finally:
            shutil.rmtree(directory)
    finally:
        if made is not None:
            shutil.rmtree(made)


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
    effect, built with ``cc`` as ``build.py`` builds the real shim."""
    compiler = shutil.which("cc")
    assert compiler is not None, "cc, which builds the stubs, is not on PATH"
    path.parent.mkdir(parents=True, exist_ok=True)
    source = path.with_suffix(".c")
    source.write_text("int carddemo_unit_preloaded;\n")
    subprocess.run(
        [compiler, "-shared", "-fPIC", "-o", os.fspath(path), os.fspath(source)],
        check=True,
        capture_output=True,
        timeout=LAUNCH_CC_TIMEOUT_S,
    )


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
def process_scope_root() -> Iterator[Path]:
    """A fresh directory of this case's own inside the work root, never under
    pytest's basetemp, given by its real path. A configured work root is
    created when missing and kept. Afterwards every process group still
    registered is ended, and the directory is removed, with the default root
    when this fixture made it."""
    configured = os.environ.get(WORK_ROOT_ENV, "")
    made = None if configured.strip() else Path(tempfile.mkdtemp(prefix=DEFAULT_WORK_ROOT_PREFIX))
    try:
        root = Path(configured) if made is None else made
        root.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix=PROCESS_SCOPE_ROOT_PREFIX, dir=root)).resolve()
        try:
            yield directory
        finally:
            try:
                datasets.kill_all()
            finally:
                shutil.rmtree(directory)
    finally:
        if made is not None:
            shutil.rmtree(made)


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
