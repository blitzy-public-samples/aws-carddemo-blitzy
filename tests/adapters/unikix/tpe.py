"""Drive the online CardDemo programs through the TPE region's TN3270 listener.

The ``unikix`` adapter runs every online scenario (``inputs.steps``) through
this module. It speaks to the test-owned region's 3270 listener with
``s3270`` 4.x over stdin and stdout, as x3270-script defines the protocol;
``py3270`` is not used. Each reply is zero or more ``data: ...`` lines, a
12-field status line and ``ok`` or ``error``. Status field 1 is the keyboard
(``U`` unlocked, ``L`` locked, ``E`` operator error), field 2 the screen
formatting (``F`` formatted, ``U`` unformatted), field 4 the connection
(``N`` when not connected, ``C(<host>)`` when connected) and fields 7 and 8
the screen rows and columns (24 and 80, model 3279-2); a status line that
breaks any of these is outside the protocol. ``s3270`` 4.x never reports
``E``: it answers an operator error, a keystroke the terminal rejects, with
an ``error`` reply carrying the data line ``Keyboard locked``.

Actions issued: ``Connect``, ``Wait(<s>,Unlock)``, ``Clear``, ``String``,
``Enter``, ``PF``, ``PA``, ``MoveCursor1``, ``EraseEOF``, ``Ascii1``,
``Disconnect`` and ``Quit``.

Address
    ``CARDDEMO_TN3270_ADDR`` is ``host:port`` or ``[v6]:port``. The host must
    be the literal ``localhost``, an IPv4 address in ``127.0.0.0/8`` or
    ``::1``; no name is resolved. Anything else is refused with
    ``AdapterUnavailable(..., "GAP-MIS-005")``, so no scenario reaches beyond
    loopback.
Screens
    After every AID the driver waits for the keyboard to unlock and reads the
    screen with ``Ascii1(1,1,24,80)``: 24 rows of exactly 80 characters, every
    space kept. A formatted screen is the one mapset of
    ``tests/inventory/layouts.yaml`` whose static ``INITIAL`` texts all stand
    at their positions; none or several raise ``AdapterUnavailable(...,
    "GAP-MIS-005")``. An unformatted screen is ``@map: TEXT`` with ``@text``
    the full 24 x 80 text (``adapters.base.screen_text``).
Capture
    Each step yields ``{field: value}`` for every named field of the
    identified map plus ``@map``; values are never trimmed or case-folded.
    ``Result.messages`` receives the ``ERRMSG`` value of each step whose map
    has one. The ``capture`` object (``adapters.unikix.evidence.Capture``)
    receives the raw rows (``screens_raw``), every ``ERRMSG`` and ``@text``
    value (``observed_messages``) and the first step's screen date
    (``run_date["first_step_screen_date"]``). ``step`` in those records is the
    0-based index of the step in ``inputs.steps``, as for ``before_step``.
Credentials
    Only the demo users ``ADMIN001`` and ``USER0001`` sign on; ``NOSUCHID`` is
    the invented id of the negative sign-on case. A step that would enter any
    other user id on the sign-on screen raises ``HarnessError``: before any
    ``s3270`` process starts when the steps show its input map (a step
    starting ``CC00`` is always on ``COSGN00``, whatever ``@map`` it
    declares), and in any case before the id is typed.
Outcomes
    A missing ``s3270``, an unreachable listener, a lost connection, an
    action that ``s3270`` answers ``error`` while connected, or an
    unidentified screen is ``AdapterUnavailable`` (``blocked``); so is a reply
    outside the protocol or a malformed status line, under ``GAP-MIS-009``. A
    rejected keystroke (an operator error), an unknown field or an over-long
    value is ``HarnessError``. The scenario deadline passing while an action
    is sent or a reply is awaited is ``ScenarioTimeout``. An application
    outcome, such as landing on another map than the step expects, is captured
    and left to the comparison; it is never turned into ``blocked``.

The module writes nothing: ``s3270`` runs with the scenario working directory
as its current and home directory, and everything observed goes into the
returned screens and messages and the ``capture`` object.
"""

from __future__ import annotations

import functools
import ipaddress
import math
import os
import queue
import re
import select
import shutil
import signal
import subprocess
import threading
import time
from collections import deque
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, NamedTuple

from adapters.base import (
    DEFAULT_DEADLINE_S,
    SCREEN_COLS,
    SCREEN_MAP,
    SCREEN_ROWS,
    SCREEN_TEXT,
    UNFORMATTED_MAP,
    AdapterUnavailable,
    ScenarioTimeout,
    screen_text,
)
from harness import ADAPTERS_DIR, HarnessError, load_yaml
from harness import layouts

__all__ = [
    "ADDRESS_ENV",
    "AIDS",
    "MAX_SESSIONS",
    "RESOURCES_FILE",
    "SIGNON_IDS",
    "SIGN_ON_MAP",
    "SIGN_ON_TRANSID",
    "DriveOutcome",
    "MapField",
    "Reply",
    "S3270",
    "Screen",
    "ScreenIdentifier",
    "TN3270Address",
    "address_from_env",
    "check_sign_on",
    "close_all",
    "default_identifier",
    "drive",
    "find_s3270",
    "parse_address",
    "quote_string",
    "transactions",
]

ADDRESS_ENV = "CARDDEMO_TN3270_ADDR"
RESOURCES_FILE = ADAPTERS_DIR / "unikix" / "resources.yaml"

# Register entries: the listener and everything reached through it, and the
# operator host's installation, which s3270 is part of.
LISTENER_GAP = "GAP-MIS-005"
INSTALLATION_GAP = "GAP-MIS-009"

S3270_MODEL = "3279-2"  # 24 x 80, the size of every CardDemo map
SIGN_ON_MAP = "COSGN00"
# CC00 starts COSGN00C [app/csd/CARDDEMO.CSD:378-379], which sends COSGN00
# first; the same value as harness.scenarios.SIGN_ON_TRANSID.
SIGN_ON_TRANSID = "CC00"
SIGNON_IDS = frozenset({"ADMIN001", "USER0001", "NOSUCHID"})
MAX_SESSIONS = 2
AIDS = frozenset({"ENTER", "CLEAR", "PA1", "PA2", "PA3"} | {f"PF{n}" for n in range(1, 25)})

_STEP_KEYS = frozenset({"transid", "aid", "fields", "session", SCREEN_MAP})
_STATUS_FIELD_COUNT = 12
# The status fields the driver reads: keyboard, formatting, connection and
# screen size; s3270 writes the connection as C(<host>) or N.
_KEYBOARD_STATES = frozenset({"U", "L", "E"})
_FORMATTING_STATES = frozenset({"F", "U"})
_NOT_CONNECTED = "N"
_CONNECTED_RE = re.compile(r"C\([^\s()]+\)")
_OPERATOR_ERROR = "E"  # the keyboard field x3270-script documents for an operator error
# The data line of the error reply that s3270 4.x gives a keystroke it rejects.
_OPERATOR_ERROR_LINE = "Keyboard locked"
_BUFFER_SIZE = SCREEN_ROWS * SCREEN_COLS
_REWAIT_PAUSE_S = 0.1  # between Wait(Unlock) replies that return early
_CLOSE_WAIT_S = 2.0  # for Quit, then for each signal, when a session closes
_CLOSE_SEND_S = 0.5  # for the input pipe to take Disconnect and Quit on close
_STDERR_LINES = 40

_BRACKETED_RE = re.compile(r"\[([^\[\]]+)\]:([0-9]+)")
_PORT_RE = re.compile(r"[0-9]{1,5}")
_IPV6_LOOPBACK = ipaddress.IPv6Address("::1")
_IPV4_LOOPBACK = ipaddress.IPv4Network("127.0.0.0/8")


# --------------------------------------------------------------------------
# Address, executable, quoting
# --------------------------------------------------------------------------


class TN3270Address(NamedTuple):
    """A validated loopback listener address."""

    host: str
    port: int

    @property
    def connect_arg(self) -> str:
        """The ``Connect`` argument: ``host:port``, or ``[v6]:port``."""
        return f"[{self.host}]:{self.port}" if ":" in self.host else f"{self.host}:{self.port}"

    def __str__(self) -> str:
        return self.connect_arg


def _is_loopback(host: str, bracketed: bool) -> bool:
    if not bracketed and host.lower() == "localhost":
        return True
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False  # a host name other than localhost is never resolved
    if bracketed:
        return isinstance(address, ipaddress.IPv6Address) and address == _IPV6_LOOPBACK
    return isinstance(address, ipaddress.IPv4Address) and address in _IPV4_LOOPBACK


def parse_address(value: str | None) -> TN3270Address:
    """Validate a TN3270 listener address, ``host:port`` or ``[v6]:port``.

    Raises ``AdapterUnavailable(reason, "GAP-MIS-005")`` when the value is
    unset or empty, has no valid port (1-65535), or names a host that is not
    loopback: ``localhost``, ``127.0.0.0/8`` or ``::1``."""
    if value is None or not value.strip():
        raise AdapterUnavailable(f"{ADDRESS_ENV} not set", LISTENER_GAP)
    text = value.strip()
    match = _BRACKETED_RE.fullmatch(text)
    if match is not None:
        host, port_text = match.groups()
    else:
        host, separator, port_text = text.rpartition(":")
        malformed = not separator or not host
        if ":" in host:
            try:
                ipaddress.IPv6Address(host)  # scope ids included; no name is resolved
            except ValueError:
                malformed = True  # colons in a host that is not an IPv6 address
            else:
                raise AdapterUnavailable(
                    f"an IPv6 TN3270 address must be written [address]:port, got {text!r}",
                    LISTENER_GAP,
                )
        if malformed:
            raise AdapterUnavailable(
                f"TN3270 address must be host:port or [v6]:port, got {text!r}", LISTENER_GAP
            )
    if not _PORT_RE.fullmatch(port_text) or not 1 <= int(port_text) <= 65535:
        raise AdapterUnavailable(f"TN3270 port must be 1-65535, got {text!r}", LISTENER_GAP)
    bracketed = match is not None
    if not _is_loopback(host, bracketed):
        raise AdapterUnavailable(f"non-loopback TN3270 address refused: {text!r}", LISTENER_GAP)
    canonical = str(ipaddress.ip_address(host)) if bracketed else host
    return TN3270Address(canonical, int(port_text))


def address_from_env() -> TN3270Address:
    """``parse_address`` of ``CARDDEMO_TN3270_ADDR``."""
    return parse_address(os.environ.get(ADDRESS_ENV))


def find_s3270() -> str:
    """The ``s3270`` executable on ``PATH``; ``AdapterUnavailable(...,
    "GAP-MIS-009")`` when there is none."""
    executable = shutil.which("s3270")
    if executable is None:
        raise AdapterUnavailable("s3270 not found", INSTALLATION_GAP)
    return executable


def quote_string(value: str) -> str:
    """``value`` as the double-quoted argument of ``String``, which types it
    literally.

    s3270 reads the argument twice. Its action parser reads ``\\"`` as a quote
    and keeps a backslash before any other character, but a ``"`` after two or
    more backslashes ends the argument, so doubling backslashes would let text
    such as ``\\") Quit() #`` close ``String`` and run further actions.
    ``String`` then decodes its own escapes: ``\\\\`` is one backslash, ``\\n``
    Enter, an unknown escape loses its backslash, a trailing ``\\`` is dropped,
    and ``\\u`` takes at most four hex digits; from s3270 4.3 a first argument
    equal to ``-subst`` in any case is an option, not text.

    So every ``"`` is written ``\\u0022``, every ``\\`` ``\\u005c`` and a
    leading ``-`` ``\\u002d``, always with four hex digits, so a hex letter
    after one stays text. The argument then holds no ``"`` and every backslash
    in it begins a ``\\uXXXX``: the action parser passes it on unchanged, and
    ``String`` types each escape as its one ASCII character, which s3270 4.1,
    keeping one byte of a ``\\u`` value, types the same way. Every other
    printable character is passed as it is; a non-printable character could
    only be sent as an escape or a key, so it is refused with
    ``HarnessError``."""
    if not isinstance(value, str):
        raise HarnessError(f"a field value must be text, got {type(value).__name__} {value!r}")
    for char in value:
        if not char.isprintable():
            raise HarnessError(f"field value {value!r} holds the non-printable character {char!r}")
    encoded = value.translate({ord("\\"): "\\u005c", ord('"'): "\\u0022"})
    if encoded.startswith("-"):
        encoded = "\\u002d" + encoded[1:]
    return '"' + encoded + '"'


# --------------------------------------------------------------------------
# The s3270 process
# --------------------------------------------------------------------------


class Reply(NamedTuple):
    """One s3270 reply: its ``data:`` lines (prefix stripped, content as
    sent), the 12 status fields, which ``_status_problem`` has found well
    formed, and whether it ended ``ok``."""

    action: str
    data: tuple[str, ...]
    status: tuple[str, ...]
    ok: bool

    @property
    def keyboard(self) -> str:
        return self.status[0]

    @property
    def formatted(self) -> bool:
        return self.status[1] == "F"

    @property
    def connected(self) -> bool:
        return self.status[3] != _NOT_CONNECTED

    @property
    def detail(self) -> str:
        return " | ".join(line.strip() for line in self.data if line.strip()) or "no detail"

    @property
    def operator_error(self) -> bool:
        """Whether the terminal rejected a keystroke: keyboard field ``E``, or
        an ``error`` reply carrying the data line ``Keyboard locked``."""
        return self.keyboard == _OPERATOR_ERROR or (
            not self.ok and any(line.strip() == _OPERATOR_ERROR_LINE for line in self.data)
        )


def _status_problem(fields: Sequence[str]) -> str | None:
    """What is wrong with a status line split into ``fields``, or ``None``
    when the fields the driver reads are well formed: 12 fields, keyboard
    ``U``, ``L`` or ``E``, formatting ``F`` or ``U``, connection ``N`` or
    ``C(<host>)``, and the 24 rows and 80 columns of model 3279-2."""
    if len(fields) != _STATUS_FIELD_COUNT:
        return f"field count {len(fields)}, expected {_STATUS_FIELD_COUNT}"
    keyboard, formatting, _, connection, _, _, rows, cols = fields[:8]
    if keyboard not in _KEYBOARD_STATES:
        return f"keyboard field {keyboard!r} is not U, L or E"
    if formatting not in _FORMATTING_STATES:
        return f"formatting field {formatting!r} is not F or U"
    if connection != _NOT_CONNECTED and not _CONNECTED_RE.fullmatch(connection):
        return f"connection field {connection!r} is not N or C(<host>)"
    if (rows, cols) != (str(SCREEN_ROWS), str(SCREEN_COLS)):
        return (
            f"screen size {rows} x {cols} is not the {SCREEN_ROWS} x {SCREEN_COLS} of model "
            f"{S3270_MODEL}"
        )
    return None


class Screen(NamedTuple):
    """The 24 rows of exactly 80 characters, and whether the screen is
    formatted (status field 2)."""

    rows: tuple[str, ...]
    formatted: bool


_LIVE: set["S3270"] = set()
_LIVE_LOCK = threading.Lock()


def close_all() -> None:
    """Close every ``s3270`` session still open in this process. ``drive``
    closes its own sessions; the adapter's ``cleanup`` calls this after a
    timeout or failure. Never raises."""
    with _LIVE_LOCK:
        sessions = list(_LIVE)
    for session in sessions:
        session.close()


def _pump_lines(stream: Any, sink: Callable[[str | None], None]) -> None:
    """Forward each line of ``stream`` to ``sink``, then ``None`` at the end.
    The reading thread also closes the stream: closing it from another thread
    would contend with the read in progress."""
    try:
        for line in stream:
            sink(line[:-1] if line.endswith("\n") else line)
    except (OSError, ValueError):
        pass  # the pipe broke as the process ended; the end is signalled below
    finally:
        sink(None)
        try:
            stream.close()
        except (OSError, ValueError):
            pass


class S3270:
    """One ``s3270`` process, the terminal of one session.

    Every read of a reply is bounded by ``time_left()``, the seconds before
    the scenario deadline; once it reaches 0, ``ScenarioTimeout`` is raised.
    Standard output is read by a thread into a queue so that bound holds even
    while s3270 is silent. Every write of an action is bounded the same way:
    standard input is non-blocking, and each line is written as the pipe takes
    it, so a child that stops reading cannot hold the driver past the
    deadline. An action written only in part when the deadline passed leaves
    s3270's input ending in half a line: every later ``command`` of the
    session then raises ``ScenarioTimeout`` without writing, and ``close``
    sends nothing more before its signals."""

    def __init__(
        self,
        workdir: str | Path,
        time_left: Callable[[], float],
        session: int = 1,
        executable: str | None = None,
    ) -> None:
        self.session = session
        self._time_left = time_left
        self._closed = False
        self._torn: str | None = None  # the action s3270 received only in part
        self._lines: queue.Queue[str | None] = queue.Queue()
        self._stderr: deque[str] = deque(maxlen=_STDERR_LINES)
        # The stderr thread appends while _gone reads: a deque must not change
        # while it is iterated.
        self._stderr_lock = threading.Lock()
        program = executable or find_s3270()
        environment = {
            "PATH": os.environ.get("PATH", os.defpath),
            "HOME": str(workdir),
            "LANG": "C.UTF-8",
        }
        try:
            self._process = subprocess.Popen(
                [program, "-model", S3270_MODEL],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                cwd=str(workdir),
                env=environment,
                start_new_session=True,
            )
        except OSError as exc:
            raise AdapterUnavailable(f"s3270 could not start: {exc}", INSTALLATION_GAP) from exc
        with _LIVE_LOCK:
            _LIVE.add(self)
        for stream, sink in (
            (self._process.stdout, self._lines.put),
            (self._process.stderr, self._keep_stderr),
        ):
            threading.Thread(
                target=_pump_lines, args=(stream, sink), name=f"s3270-{session}", daemon=True
            ).start()
        try:
            # Actions are written to the descriptor by _write, never through
            # the text stream, whose close therefore has nothing to flush.
            os.set_blocking(self._process.stdin.fileno(), False)
        except (OSError, ValueError) as exc:
            self.close()
            raise AdapterUnavailable(
                f"s3270 input could not be made non-blocking: {exc}", INSTALLATION_GAP
            ) from exc

    def _keep_stderr(self, line: str | None) -> None:
        if line is not None:
            with self._stderr_lock:
                self._stderr.append(line)

    def _gone(self, during: str) -> AdapterUnavailable:
        with self._stderr_lock:
            lines = list(self._stderr)
        tail = " | ".join(line.strip() for line in lines if line.strip())
        return AdapterUnavailable(
            f"s3270 session {self.session} ended during {during}"
            f" (exit {self._process.poll()}): {tail or 'no message'}",
            LISTENER_GAP,
        )

    def _write(self, data: bytes, time_left: Callable[[], float]) -> int:
        """Write ``data`` to s3270's non-blocking input as the pipe takes it,
        waiting for room only while ``time_left()`` is above 0, and return the
        number of bytes written: ``len(data)`` unless time ran out first.
        Raises ``OSError`` when the pipe is broken and ``ValueError`` when the
        stream is closed."""
        descriptor = self._process.stdin.fileno()
        poller = select.poll()
        poller.register(descriptor, select.POLLOUT)
        view = memoryview(data)
        sent = 0
        while sent < len(view):
            left = time_left()
            if left <= 0:
                break
            try:
                sent += os.write(descriptor, view[sent:])
            except BlockingIOError:
                # until there is room or time is up; one poll never outlasts a
                # whole deadline, so an unbounded time_left() cannot overflow it
                poller.poll(math.ceil(min(left, DEFAULT_DEADLINE_S) * 1000))
        return sent

    def _read_line(self, action: str) -> str:
        left = self._time_left()
        if left <= 0:
            raise ScenarioTimeout(f"session {self.session}: deadline passed awaiting {action}")
        try:
            line = self._lines.get(timeout=left)
        except queue.Empty:
            raise ScenarioTimeout(
                f"session {self.session}: deadline passed awaiting {action}"
            ) from None
        if line is None:
            self._lines.put(None)  # every later read sees the end too
            raise self._gone(action)
        return line

    def command(self, action: str) -> Reply:
        """Send one action and read its reply, whether ``ok`` or ``error``."""
        if self._closed:
            raise HarnessError(f"s3270 session {self.session} is closed; cannot send {action}")
        if "\n" in action or "\r" in action:
            raise HarnessError(f"an s3270 action is one line, got {action!r}")
        if self._torn is not None:
            raise ScenarioTimeout(
                f"session {self.session}: deadline passed sending {self._torn}, which s3270 "
                f"received in part; cannot send {action}"
            )
        if self._process.poll() is not None:
            raise self._gone(action)
        encoded = (action + "\n").encode("utf-8", "replace")
        try:
            sent = self._write(encoded, self._time_left)
        except (OSError, ValueError):
            raise self._gone(action) from None
        if sent < len(encoded):
            if sent:
                self._torn = action
            raise ScenarioTimeout(f"session {self.session}: deadline passed sending {action}")
        data: list[str] = []
        status: tuple[str, ...] | None = None
        while True:
            line = self._read_line(action)
            if line.startswith("data:"):
                data.append(line[6:] if line.startswith("data: ") else line[5:])
            elif line in ("ok", "error"):
                if status is None:
                    break
                return Reply(action, tuple(data), status, line == "ok")
            elif status is None:
                fields = tuple(line.split())
                problem = _status_problem(fields)
                if problem is not None:
                    raise AdapterUnavailable(
                        f"s3270 session {self.session} answered {action} with a status line "
                        f"outside the x3270-script protocol ({problem}): {line!r}",
                        INSTALLATION_GAP,
                    )
                status = fields
            else:
                break
        raise AdapterUnavailable(
            f"s3270 session {self.session} answered {action} outside the x3270-script "
            f"protocol (expected data lines, a {_STATUS_FIELD_COUNT}-field status line and "
            f"ok/error), got {line!r}",
            INSTALLATION_GAP,
        )

    def checked(self, action: str) -> Reply:
        """``command`` that must succeed, sent once and never repeated, since
        an AID is not idempotent.

        A lost connection, and an action that ``s3270`` answers ``error``
        while connected, are ``AdapterUnavailable(..., "GAP-MIS-005")``
        (``blocked``), the latter with the complete reply. A rejected
        keystroke (an operator error: keyboard ``E``, or an ``error`` reply
        carrying ``Keyboard locked``) is ``HarnessError``. ``command`` raises
        the rest: a reply outside the protocol or a malformed status line is
        ``AdapterUnavailable(..., "GAP-MIS-009")``, and the scenario deadline
        passing while the action is sent or its reply awaited is
        ``ScenarioTimeout``."""
        reply = self.command(action)
        if not reply.connected:
            raise AdapterUnavailable(
                f"TN3270 connection lost (session {self.session}) at {action}: {reply.detail}",
                LISTENER_GAP,
            )
        if reply.operator_error:
            raise HarnessError(
                f"s3270 {action} was rejected as an operator error (session {self.session}): "
                f"the terminal refused the input; {reply.detail}"
            )
        if not reply.ok:
            raise AdapterUnavailable(
                f"s3270 session {self.session} answered {action} with error while connected: "
                f"data {list(reply.data)!r}, status {' '.join(reply.status)!r}",
                LISTENER_GAP,
            )
        return reply

    def connect(self, address: TN3270Address) -> None:
        """``Connect`` to the listener, then wait for the keyboard."""
        reply = self.command(f"Connect({address.connect_arg})")
        if not reply.ok:
            raise AdapterUnavailable(
                f"TN3270 connect failed: {address}: {reply.detail}", LISTENER_GAP
            )
        self.wait_unlock()

    def wait_unlock(self) -> Reply:
        """``Wait(<s>,Unlock)``, where ``<s>`` is the whole seconds left before
        the scenario deadline (at least 1).

        Waiting for the application has no limit of its own: a wait that
        returns ``error`` while time is left means the keyboard is still
        locked, and is repeated until the deadline, unless the reply reports
        an operator error, which is ``HarnessError``."""
        while True:
            left = self._time_left()
            if left <= 0:
                raise ScenarioTimeout(f"session {self.session}: deadline passed awaiting Unlock")
            reply = self.command(f"Wait({max(1, int(left))},Unlock)")
            if reply.ok:
                return reply
            if not reply.connected:
                raise AdapterUnavailable(
                    f"TN3270 connection lost (session {self.session}) while waiting for the "
                    f"keyboard: {reply.detail}",
                    LISTENER_GAP,
                )
            if reply.operator_error:
                raise HarnessError(
                    f"keyboard in operator-error state (session {self.session}): the terminal "
                    f"refused the input; {reply.detail}"
                )
            pause = min(_REWAIT_PAUSE_S, self._time_left())
            if pause > 0:
                time.sleep(pause)

    def read_screen(self) -> Screen:
        """``Ascii1(1,1,24,80)``: every row padded or cut to exactly 80
        characters, every space kept."""
        reply = self.checked(f"Ascii1(1,1,{SCREEN_ROWS},{SCREEN_COLS})")
        if len(reply.data) != SCREEN_ROWS:
            raise AdapterUnavailable(
                f"s3270 session {self.session} returned {len(reply.data)} screen rows, "
                f"expected {SCREEN_ROWS} (model {S3270_MODEL})",
                INSTALLATION_GAP,
            )
        rows = tuple(row[:SCREEN_COLS].ljust(SCREEN_COLS) for row in reply.data)
        return Screen(rows, reply.formatted)

    def type_text(self, value: str) -> None:
        """``String`` of one field value, typed literally through ``quote_string``."""
        self.checked(f"String({quote_string(value)})")

    def enter_field(self, field: "MapField", value: str) -> None:
        """Replace a field's content: ``MoveCursor1`` to its first data
        position, ``EraseEOF``, then ``String`` unless ``value`` is empty."""
        row, col = field.cursor
        self.checked(f"MoveCursor1({row},{col})")
        # EraseEOF is not in the AAP's action list: without it a shorter or
        # blank value would leave the rest of a pre-filled field in place.
        self.checked("EraseEOF()")
        if value:
            self.type_text(value)

    def send_aid(self, aid: str) -> None:
        """Press an AID key: ``ENTER``, ``CLEAR``, ``PF1``-``PF24`` or
        ``PA1``-``PA3``."""
        self.checked(_aid_action(aid))

    def start_transaction(self, transid: str) -> None:
        """Clear the screen and enter a transaction id, as an operator does."""
        self.checked("Clear()")
        self.wait_unlock()
        self.type_text(transid)
        self.checked("Enter()")
        self.wait_unlock()

    def close(self) -> None:
        """End the session: ``Disconnect`` and ``Quit`` when s3270's input
        takes both whole within ``_CLOSE_SEND_S``, and never after an action
        was written only in part, then up to ``_CLOSE_WAIT_S`` for s3270 to
        exit; then SIGTERM and SIGKILL to the process group, each waited for
        up to ``_CLOSE_WAIT_S``. No step waits on the pipe without a bound, so
        a child that stopped reading is still killed and reaped. Idempotent;
        never raises, because it runs in ``finally`` blocks and in the
        adapter's cleanup, where an exception would replace the scenario's own
        outcome."""
        if self._closed:
            return
        self._closed = True
        with _LIVE_LOCK:
            _LIVE.discard(self)
        process = self._process
        try:
            if process.poll() is None and self._torn is None:
                farewell = b"Disconnect()\nQuit()\n"
                until = time.monotonic() + _CLOSE_SEND_S
                try:
                    sent = self._write(farewell, lambda: until - time.monotonic())
                except (OSError, ValueError):
                    sent = 0  # s3270 is already gone or no longer reading
                if sent == len(farewell):
                    try:
                        process.wait(timeout=_CLOSE_WAIT_S)
                    except subprocess.TimeoutExpired:
                        pass  # the signals below end it
            for sig in (signal.SIGTERM, signal.SIGKILL):
                if process.poll() is not None:
                    break
                try:
                    os.killpg(process.pid, sig)
                except (ProcessLookupError, PermissionError):
                    break
                try:
                    process.wait(timeout=_CLOSE_WAIT_S)
                except subprocess.TimeoutExpired:
                    continue
        except Exception:  # noqa: BLE001 - close never raises (see docstring)
            pass
        finally:
            try:
                process.stdin.close()  # nothing buffered in it, so it cannot block
            except (OSError, ValueError):
                pass  # the reader threads close stdout and stderr


def _aid_action(aid: Any) -> str:
    if aid == "ENTER":
        return "Enter()"
    if aid == "CLEAR":
        return "Clear()"
    if isinstance(aid, str) and aid in AIDS:
        return f"{aid[:2]}({int(aid[2:])})"
    raise HarnessError(f"aid {aid!r} is not ENTER, CLEAR, PA1-PA3 or PF1-PF24")


# --------------------------------------------------------------------------
# Screen identification and capture
# --------------------------------------------------------------------------


def _data_offset(row: int, col: int) -> int:
    """0-based offset into the 1920-character screen of the first data
    position of a BMS field whose ``POS=(row,col)`` addresses its attribute
    byte. Offsets wrap as the 3270 buffer does."""
    return ((row - 1) * SCREEN_COLS + col) % _BUFFER_SIZE


def _buffer_slice(buffer: str, offset: int, length: int) -> str:
    # A field may run past column 80 onto the next row (the 80-character
    # ERRMSG of COCRDSL and COCRDUP starts at row 23 column 2), and past the
    # last position back to the first, so fields are read from the screen as
    # one 1920-character buffer rather than row by row.
    end = offset + length
    if end <= len(buffer):
        return buffer[offset:end]
    return buffer[offset:] + buffer[: end - len(buffer)]


class MapField(NamedTuple):
    """A named field of a map: its ``POS`` (the attribute byte), ``LENGTH``,
    and where its data starts."""

    name: str
    row: int
    col: int
    length: int

    @property
    def offset(self) -> int:
        return _data_offset(self.row, self.col)

    @property
    def cursor(self) -> tuple[int, int]:
        """1-based ``MoveCursor1`` row and column of the first data position."""
        return self.offset // SCREEN_COLS + 1, self.offset % SCREEN_COLS + 1


class ScreenIdentifier:
    """Identifies a screen as one of the CardDemo maps and captures its
    fields, from the screen layouts of ``tests/inventory/layouts.yaml``.

    A map matches when every one of its static texts (the unnamed ``INITIAL``
    fields) stands at its position; the data of a field starts one column
    after its ``POS``. ``layouts.py`` guarantees no map's statics all recur on
    another map."""

    def __init__(self, mapsets: Sequence[str] | None = None) -> None:
        stems = sorted(layouts.load()["screens"]) if mapsets is None else sorted(mapsets)
        self._statics: dict[str, tuple[tuple[int, str], ...]] = {}
        self._fields: dict[str, dict[str, MapField]] = {}
        for stem in stems:
            entry = layouts.screen(stem)
            if list(entry["size"]) != [SCREEN_ROWS, SCREEN_COLS]:
                raise HarnessError(
                    f"map {stem} is {entry['size']}; the terminal model {S3270_MODEL} is "
                    f"{SCREEN_ROWS}x{SCREEN_COLS}"
                )
            self._statics[stem] = tuple(
                (_data_offset(static["row"], static["col"]), static["text"])
                for static in entry["statics"]
            )
            self._fields[stem] = {
                item["name"]: MapField(item["name"], item["row"], item["col"], item["length"])
                for item in entry["fields"]
            }

    @property
    def mapsets(self) -> tuple[str, ...]:
        return tuple(self._statics)

    def fields(self, mapset: str) -> Mapping[str, MapField]:
        """The named fields of a mapset."""
        try:
            return self._fields[mapset]
        except KeyError:
            raise HarnessError(
                f"unknown mapset {mapset!r}; known: {', '.join(self._fields)}"
            ) from None

    def candidates(self, rows: Sequence[str]) -> list[str]:
        """Every mapset whose static texts all stand on the screen."""
        buffer = "".join(rows)
        return [
            stem
            for stem, statics in self._statics.items()
            if all(_buffer_slice(buffer, offset, len(text)) == text for offset, text in statics)
        ]

    def identify(self, screen: Screen) -> str:
        """The ``@map`` of a screen: ``TEXT`` when it is unformatted, else the
        one matching mapset. None or several raise ``AdapterUnavailable(...,
        "GAP-MIS-005")``."""
        if not screen.formatted:
            return UNFORMATTED_MAP
        matches = self.candidates(screen.rows)
        if len(matches) == 1:
            return matches[0]
        if matches:
            found = f"candidates {', '.join(matches)}"
        else:
            first = next((row.strip() for row in screen.rows if row.strip()), "")
            found = f"no map matches; first non-blank row {first!r}"
        raise AdapterUnavailable(f"screen not identified: {found}", LISTENER_GAP)

    def capture(self, rows: Sequence[str], mapset: str) -> dict[str, str]:
        """The step's screen fields: for a map, every named field exactly as
        shown (trailing spaces kept, nulls as spaces) plus ``@map``; for
        ``TEXT``, ``@map`` and ``@text``."""
        if mapset == UNFORMATTED_MAP:
            return {SCREEN_MAP: UNFORMATTED_MAP, SCREEN_TEXT: screen_text(list(rows))}
        buffer = "".join(rows)
        values = {
            name: _buffer_slice(buffer, item.offset, item.length)
            for name, item in self.fields(mapset).items()
        }
        values[SCREEN_MAP] = mapset
        return values


@functools.lru_cache(maxsize=1)
def default_identifier() -> ScreenIdentifier:
    """The identifier over all 17 mapsets, built once per process."""
    return ScreenIdentifier()


@functools.lru_cache(maxsize=1)
def transactions() -> frozenset[str]:
    """The transaction ids the region defines (``resources.yaml``
    ``transactions``, from the copy's ``pct.lst``)."""
    data = load_yaml(RESOURCES_FILE)
    table = data.get("transactions") if isinstance(data, Mapping) else None
    if not isinstance(table, Mapping) or not table:
        raise HarnessError(f"{RESOURCES_FILE} lists no transactions")
    return frozenset(str(transid) for transid in table)


# --------------------------------------------------------------------------
# Steps
# --------------------------------------------------------------------------


class DriveOutcome(NamedTuple):
    """What ``drive`` observed: one screen mapping per step that ran (fewer
    than the steps when the application left the expected path), and the
    ``ERRMSG`` value of each of those screens whose map has one."""

    screens: list[dict[str, str]]
    messages: list[str]


def _check_user(value: Any, where: str) -> None:
    if isinstance(value, str) and (not value.strip() or value in SIGNON_IDS):
        return  # a blank user id signs nobody on (the blank-user-id case)
    raise HarnessError(
        f"{where}.fields.USERID {value!r} is not a demo user: only ADMIN001 and USER0001 sign "
        f"on, and NOSUCHID is the one invented id"
    )


def _static_input_map(
    steps: Sequence[Mapping[str, Any]],
    expected_screens: Sequence[Mapping[str, Any]] | None,
    index: int,
) -> str | None:
    """The map a step's fields are entered on, as far as it is known before
    running: for a step starting ``SIGN_ON_TRANSID``, the sign-on map
    COSGN00C sends, whatever ``@map`` it declares; else the step's own
    ``@map``; else, unless it starts a transaction, the ``@map`` its
    session's previous step is expected to end on."""
    step = steps[index]
    if step.get("transid") == SIGN_ON_TRANSID:
        return SIGN_ON_MAP
    declared = step.get(SCREEN_MAP)
    if isinstance(declared, str):
        return declared
    if "transid" in step or expected_screens is None:
        return None
    session = step.get("session", 1)
    for earlier in range(index - 1, -1, -1):
        if steps[earlier].get("session", 1) == session:
            screen = expected_screens[earlier] if earlier < len(expected_screens) else None
            value = screen.get(SCREEN_MAP) if isinstance(screen, Mapping) else None
            return value if isinstance(value, str) else None
    return None


def check_sign_on(
    steps: Sequence[Mapping[str, Any]],
    expected_screens: Sequence[Mapping[str, Any]] | None = None,
) -> None:
    """Refuse, with ``HarnessError``, a step that would sign on a user other
    than the demo users: one whose ``USERID`` is neither blank nor in
    ``SIGNON_IDS`` and whose input map is the sign-on map or not known. A
    step starting ``SIGN_ON_TRANSID`` is always entered on the sign-on map,
    whatever ``@map`` it declares.

    ``expected_screens`` (the scenario's ``expected.screens``) lets a step
    without ``@map`` take the map its session's previous step ends on."""
    # Only a sign-on entry is a credential. COUSR01 and COUSR02 also have a
    # USERID field, holding the user record being maintained (TESTU001 in
    # USR-COUSR01C-normal-01), so the demo-user rule applies to steps
    # starting CC00 (always on COSGN00, whatever @map they declare), to steps
    # entered on COSGN00 and to steps whose input map is not known, as
    # harness/scenarios.py applies it; refusing every USERID field would make
    # the user-maintenance scenarios impossible to run.
    for index, step in enumerate(steps):
        fields = step.get("fields") if isinstance(step, Mapping) else None
        if not isinstance(fields, Mapping) or "USERID" not in fields:
            continue
        if _static_input_map(steps, expected_screens, index) in (None, SIGN_ON_MAP):
            _check_user(fields["USERID"], f"steps[{index}]")


def _validate_steps(
    steps: Sequence[Mapping[str, Any]], sessions: Any, identifier: ScreenIdentifier
) -> None:
    if isinstance(steps, (str, bytes)) or not isinstance(steps, Sequence):
        raise HarnessError(f"steps must be a list of steps, got {type(steps).__name__}")
    if (
        isinstance(sessions, bool)
        or not isinstance(sessions, int)
        or not 1 <= sessions <= MAX_SESSIONS
    ):
        raise HarnessError(f"sessions must be 1 to {MAX_SESSIONS}, got {sessions!r}")
    for index, step in enumerate(steps):
        where = f"steps[{index}]"
        if not isinstance(step, Mapping):
            raise HarnessError(
                f"{where} must be a mapping {{transid?, aid, fields?, session?, '@map'?}}"
            )
        unknown = sorted(str(key) for key in step if key not in _STEP_KEYS)
        if unknown:
            raise HarnessError(f"{where} has unknown keys {unknown}")
        _aid_action(step.get("aid"))
        session = step.get("session", 1)
        if (
            isinstance(session, bool)
            or not isinstance(session, int)
            or not 1 <= session <= sessions
        ):
            raise HarnessError(f"{where}.session must be 1 to {sessions}, got {session!r}")
        if "transid" in step and step["transid"] not in transactions():
            raise HarnessError(
                f"{where}.transid {step['transid']!r} is not a transaction of {RESOURCES_FILE.name}"
            )
        declared = step.get(SCREEN_MAP)
        if SCREEN_MAP in step and not (
            declared == UNFORMATTED_MAP or declared in identifier.mapsets
        ):
            raise HarnessError(f"{where}.@map {declared!r} is not a mapset or {UNFORMATTED_MAP}")
        fields = step.get("fields", {})
        if fields is None:
            continue
        if not isinstance(fields, Mapping):
            raise HarnessError(f"{where}.fields must be a mapping of BMS field -> text")
        for name, value in fields.items():
            if not isinstance(name, str):
                raise HarnessError(f"{where}.fields has a non-text field name {name!r}")
            quote_string(value)


def _time_left_function(deadline: Any) -> Callable[[], float]:
    """``deadline`` as a seconds-left function: a ``time.monotonic()`` value,
    a function returning the seconds left (such as ``Adapter.time_left``), or
    ``None`` for ``DEFAULT_DEADLINE_S`` from now."""
    if callable(deadline):
        return lambda: max(0.0, float(deadline()))
    if deadline is None:
        end = time.monotonic() + DEFAULT_DEADLINE_S
    elif isinstance(deadline, bool) or not isinstance(deadline, (int, float)):
        raise HarnessError(f"deadline must be a monotonic time or a function, got {deadline!r}")
    else:
        end = float(deadline)
    return lambda: max(0.0, end - time.monotonic())


def _capture_attribute(capture: Any, name: str, factory: Callable[[], Any]) -> Any:
    value = getattr(capture, name, None)
    if value is None:
        value = factory()
        setattr(capture, name, value)
    return value


class _Observations:
    """The screens and messages of one ``drive`` call, mirrored into the
    adapter's evidence ``capture`` object (when one is given)."""

    def __init__(self, capture: Any, identifier: ScreenIdentifier) -> None:
        self.capture = capture
        self.identifier = identifier
        self.screens: list[dict[str, str]] = []
        self.messages: list[str] = []

    def _evidence(self, name: str, item: dict[str, Any]) -> None:
        if self.capture is not None:
            _capture_attribute(self.capture, name, list).append(item)

    def record(self, index: int, session: int, screen: Screen, mapset: str) -> None:
        values = self.identifier.capture(screen.rows, mapset)
        self.screens.append(values)
        self._evidence(
            "screens_raw",
            {"step": index, "session": session, "map": mapset, "rows": list(screen.rows)},
        )
        if mapset == UNFORMATTED_MAP:
            self._evidence(
                "observed_messages",
                {
                    "step": index,
                    "session": session,
                    "field": SCREEN_TEXT,
                    "value": values[SCREEN_TEXT],
                },
            )
        elif "ERRMSG" in values:
            self.messages.append(values["ERRMSG"])
            self._evidence(
                "observed_messages",
                {"step": index, "session": session, "field": "ERRMSG", "value": values["ERRMSG"]},
            )
        if index == 0 and "CURDATE" in values and self.capture is not None:
            _capture_attribute(self.capture, "run_date", dict)["first_step_screen_date"] = values[
                "CURDATE"
            ]


def _enter_fields(
    terminal: S3270,
    identifier: ScreenIdentifier,
    mapset: str,
    fields: Mapping[str, str],
    index: int,
) -> None:
    known = identifier.fields(mapset)
    unknown = sorted(name for name in fields if name not in known)
    if unknown:
        raise HarnessError(f"steps[{index}].fields {unknown} are not fields of map {mapset}")
    if mapset == SIGN_ON_MAP and "USERID" in fields:
        _check_user(fields["USERID"], f"steps[{index}]")
    ordered = sorted(fields, key=lambda name: (known[name].row, known[name].col))
    for name in ordered:
        if len(fields[name]) > known[name].length:
            # Typing past the end of a field would carry on in the next
            # unprotected field and silently change another input.
            raise HarnessError(
                f"steps[{index}].fields.{name} {fields[name]!r} is longer than the "
                f"{known[name].length} characters of field {name} of map {mapset}"
            )
    for name in ordered:
        terminal.enter_field(known[name], fields[name])


def drive(
    steps: Sequence[Mapping[str, Any]],
    sessions: int = 1,
    deadline: float | Callable[[], float] | None = None,
    capture: Any = None,
    before_step: Callable[[int], None] | None = None,
    *,
    workdir: str | Path,
    address: TN3270Address | str | None = None,
    expected_screens: Sequence[Mapping[str, Any]] | None = None,
) -> DriveOutcome:
    """Run a scenario's ``inputs.steps`` against the region and return the
    screen of each step and its ``ERRMSG``.

    ``steps``
        ``{transid?, aid, fields?, session?, '@map'?}`` mappings; ``@map``
        names the map the step expects as its input screen.
    ``sessions``
        ``preconditions.parameters.sessions``: 1, or 2 for two terminals; a
        step's ``session`` (default 1) selects its terminal.
    ``deadline``
        The scenario deadline as a ``time.monotonic()`` value or a
        seconds-left function (``Adapter.time_left``); every wait and read is
        bounded by it.
    ``capture``
        The evidence object receiving ``screens_raw``, ``observed_messages``
        and ``run_date`` (``adapters.unikix.evidence.Capture``), or ``None``.
    ``before_step``
        Called with each step's 0-based index before the step runs; the
        adapter applies an online ``unavailable`` fault there, after sign-on
        and before the failing step.
    ``workdir``
        The scenario working directory, s3270's current and home directory.
    ``address``
        The listener; ``CARDDEMO_TN3270_ADDR`` when ``None``. It is validated
        again whatever its form, so only loopback is ever reached.
    ``expected_screens``
        The scenario's ``expected.screens``, used only to find the input map
        of a step without ``@map`` for the sign-on check.

    Each step: start ``transid`` when given (``Clear``, ``String``,
    ``Enter``), identify the input screen, enter the fields in screen order,
    press the AID, wait for the keyboard and capture the result. When the
    input screen is not the step's ``@map``, or fields are due on an
    unformatted screen, that screen is the step's result and driving stops:
    the comparison then reports the application's path, which is never a
    platform failure. Every session is closed before returning or raising."""
    identifier = default_identifier()
    _validate_steps(steps, sessions, identifier)
    check_sign_on(steps, expected_screens)
    target = address_from_env() if address is None else parse_address(str(address))
    executable = find_s3270()
    directory = Path(workdir)
    if not directory.is_dir():
        raise HarnessError(f"scenario working directory {directory} does not exist")
    time_left = _time_left_function(deadline)
    observed = _Observations(capture, identifier)
    terminals: list[S3270] = []
    try:
        for number in range(1, sessions + 1):
            terminal = S3270(directory, time_left, number, executable)
            terminals.append(terminal)
            terminal.connect(target)
        for index, step in enumerate(steps):
            if before_step is not None:
                before_step(index)
            number = step.get("session", 1)
            terminal = terminals[number - 1]
            if "transid" in step:
                terminal.start_transaction(step["transid"])
            screen = terminal.read_screen()
            input_map = identifier.identify(screen)
            fields = step.get("fields") or {}
            declared = step.get(SCREEN_MAP)
            if (declared is not None and declared != input_map) or (
                fields and input_map == UNFORMATTED_MAP
            ):
                observed.record(index, number, screen, input_map)
                break
            if fields:
                _enter_fields(terminal, identifier, input_map, fields, index)
            terminal.send_aid(step["aid"])
            terminal.wait_unlock()
            screen = terminal.read_screen()
            observed.record(index, number, screen, identifier.identify(screen))
    finally:
        for terminal in terminals:
            terminal.close()
    return DriveOutcome(observed.screens, observed.messages)
