"""Harness self-tests.

These check the harness itself. They are not inventory scenarios and never
verify an inventory item on their own. They read the committed layouts and
scenarios and write nothing in the repository.

Instruments every business assertion depends on:

- Layouts: the committed ``tests/inventory/layouts.yaml`` is what the
  sources generate, holds the 17 maps with field positions and static texts,
  and gives each record layout the length its copybook's ``RECLN`` comment
  and the LISTCAT ``MAXLRECL`` of its cluster's DATA component state, both
  read here independently of the harness.
- Codec: overpunch-signed zoned decimals decode as ``Decimal`` with their
  scale (``0000005047G`` = +504.77, ``}`` negative), every field type round
  trips value to bytes and bytes to value, and cp037 carries the overpunch in
  zones ``C`` and ``D``.
- Fixtures: the EBCDIC cross-check finds exactly the two registered
  mismatches (GAP-DOC-012, GAP-DOC-013), and ASCII lines are padded with
  spaces to the record length while a longer line is refused.
- Inventory: ``inventory_tools.py --check`` finds no drift, and the frozen
  header holds the protected base, the 155 source items and the total.
- Scenarios, baselines and results match the closed id manifest; every
  scenario passes the schema, the fixed ids exist, and only the two chain
  scenarios carry the ``chain`` marker.
- Gaps: every gap id the scenarios, the inventory, the fixtures, the
  adapters and the harness cite exists in ``gaps.yaml``, whose entries are
  unique and well formed, and each seam keeps its ``stub-limitation`` entry,
  closed only on valid resolving evidence; every scenario gnucobol-batch runs
  cites in ``gap_ids`` the entry of each seam its program or job steps use
  (the seam rule). An item that is not verified is covered only by an open
  entry whose resolved affected items include it, never by an open gap a
  listing scenario merely cites.
- Configuration: the six markers are registered, every ``commands.yaml``
  entry has a probe, a timeout, a status and a gap (open while unconfirmed),
  and both adapters implement ``adapters.base.Adapter``.
- Baselines and the protected tree: the version-bump check against
  ``--carddemo-baseline-base`` and ``report.py --check-protected`` pass; both
  need the git history and fail, never skip, without it. The command-line
  checks run with the session work root as ``CARDDEMO_TEST_WORKDIR``, and the
  bump check's scratch folder, traced in its process, lies inside the work
  root it is given.

Codec reconstruction over REDEFINES: a record ``decode_record`` decodes must
encode back to the same bytes in ASCII and cp037, a positive overpunch
included, and views that describe different bytes must be refused.

Deterministic projection: captures that differ only in the run clock must
project to the same result YAML, with every screen's CURDATE and CURTIME
holding their matcher kind whether or not the step asserts them.

Matcher binding: ``capture.build_expected``, on the committed TRANSACT of
TRN-POSTTRAN-normal-01 and SYSTRAN of FIN-INTCALC-normal-01, must write the
same baseline whether a scenario names its timestamp matchers by decoded name
or by field path (``TRAN-RECORD.TRAN-PROC-TS``), in ``records``, in
``contains``, in the entry's ``matchers`` or in the top-level ``matchers``:
``{match: run-timestamp}`` in place of the absolute timestamp of every record
the matcher binds to, and a baseline ``baselines.compare`` passes with the
qualified scenario. A matcher no decoded field can hold (a group, ``@raw``, a
path naming no field, a whole-line matcher for a field map) must be refused.

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

Online fault placement: ``UniKixAdapter``'s fault planning, on fake commands,
must place an online ``unavailable`` fault without ``after`` before step
index 1 and one with ``after`` 0 before step index 0, MissingPrerequisite
under GAP-MIS-011 when the scenario has no such step; accept ``after`` 1 or
more only for a read, write or rewrite fault on a region file with a step
after the first and ``region_file_requests`` available, MissingPrerequisite
under GAP-MIS-011 otherwise; and keep ``locked``, ``io-error`` and ``abend``
MissingPrerequisite under GAP-MIS-014. Called as ``tpe.drive`` calls it, on
scripted ``region_file_requests`` counts, the placement must apply a counted
fault before the first step at whose start ``after`` requests have been
served since the attempt's first step (CRD-COCRDLIC-recovery-03 and
CRD-COCRDUPC-recovery-01 before step index 3), count only while it is
unapplied, take a new baseline after a restart, end in MissingPrerequisite
under GAP-MIS-011 when more were served by a step's start or fewer by the
last step's, in AdapterUnavailable under GAP-MIS-011 for output without
exactly one count or a count below the baseline, and leave a run that stopped
before its last step to the comparison.

Evidence ownership: ``EvidenceWriter.write``, on a tests root of the case's
own, must refuse a scenario folder that already exists, whatever stands
there, and keep it as found; of concurrent writes of one scenario exactly one
must succeed, and the folder must hold its files alone. The concurrent writes
run as threads of one child process under a deadline, killed and reaped when
it passes, so a writer that hangs fails the case instead of stalling the run.
An id outside the scenario id manifest is refused by ``write`` and
``evidence_path``, and a record that breaks the result record contract by
``write``, before any folder is created; a conforming record must read back
field-equal.

Baseline publication: ``baselines.write``, on a baselines folder of the
case's own, must publish each id whole. A writer process killed between two
staged files, between the two renames of the fallback swap or after the
exchange leaves the last complete baseline, the new one once it is in place,
or none for a first capture, and ``load`` returns exactly that; a retry
succeeds and leaves no staging or pre-image directory. Concurrent writer
processes of one id, through the exchange or the fallback swap, never let
``load``, in a reader process loading all the while, read one writer's
expected outcome with another's manifest or raw bytes. The seed write, the
writers and the reader are child processes under one deadline; any still
running when it passes is killed and reaped and the case fails, so a lock
that hangs fails instead of stalling the run. An entry that is no baseline
artifact is refused, with nothing changed; unchanged files keep their inode
and mtime (a copy its mtime where no hard link can be made), replaced files
and folders their permission bits, and an identical baseline publishes
nothing.

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

Stage observations: ``base.after_job_observation`` and ``parse_after_job``
must round-trip ``@AFTER:<JOB>:<LOGICAL>``, which ``observation_matches``
covers with ``"@AFTER:*"`` and ``"@AFTER:"``, and refuse a part that is
empty, not text or holds ``:`` or whitespace, a ``<LOGICAL>`` starting with
``@``, and any other name. ``scenarios.validate``, on in-memory copies of
BAT-CHAIN-normal-01, must accept one under ``expected.file_state`` for a job
the chain runs once, its records checked as its logical dataset's, and
refuse a malformed name, a job ``inputs.job`` does not run or lists twice, an
unknown dataset, records of another layout and one under
``expected.datasets``. ``GnuCOBOLBatchAdapter`` must group the stage
observations by job and refuse, before any job runs, one whose job the chain
does not run once. ``baselines.decode_result`` must decode one through its
logical dataset's layout in primary-key order, as that dataset decodes, and
as ``@missing`` when the result lacks it.

Job-chain proof stage checks: ``report._proof_outcome_problems``, on
synthetic decoded outcomes, must report nothing about a conforming set of
stage observations and exactly one problem for each stage rule broken: the
accounts after POSTTRAN absent, not produced, not 50, lacking an account,
with an account whose cycle totals are both zero or not number text, or with
an account 50 the final ACCTDATA does not repeat; TRANBKP's backup not 262,
out of TRAN-ID order, holding an interest record or another TRAN-PROC-TS;
and its TRANSACT not empty or not produced. ``_proof_expectation_problems``
must likewise require each stage observation's full record list, the 50
accounts after POSTTRAN each naming the proof's account fields.

Stage observations on unikix: ``BatchService.run_jobs``, on fake
submissions, must call ``after_job`` with each job's 0-based position once
its wait returned and before the next job's ``before_job`` and submission,
an abended job included, and submit nothing after an abend. ``UniKixAdapter``, on fakes, must declare
``@AFTER:*`` before its session and, once it has started, exactly when it
declares the business datasets, under GAP-MIS-007 otherwise; refuse with
``HarnessError``, before any command or BPE use, a stage observation whose job
the chain does not run once, an online run's included; unload each one's
dataset at the end of its job with the region files closed and then reopened,
keeping it in the evidence under its stage name as soon as it is acquired;
leave out one whose dataset is not catalogued then or whose job never ran;
keep what was acquired when a later unload fails; and never pass a stage name
to the post-run unloads.

Closing evidence: ``report._entry_problems`` must accept as a register
entry's ``resolving_evidence`` only a commit (7 to 40, or 64, lowercase hex)
or an execution id with the path of that execution's evidence:
``tests/reports/unikix/<execution_id>`` or its scenario folder for a UniKix
run, ``tests/reports/results/<scenario_id>.yaml`` with its ``scenario_id`` for
a gnucobol-batch run. It must report exactly one problem for plain text, an
unrelated mapping, an execution id without a path or a path without one, a
malformed execution id, a path naming another execution or scenario, a
gnucobol-batch run without its scenario, an unknown key, a commit that is not
hex or stands beside execution keys, and a value that is not text; still
refuse a closed entry without evidence; and hold each ``superseded_evidence``
element to the same shapes.

Revalidation observation gate: ``report._validate_evidence``, on a blocked,
implemented-unexecuted or timed-out UniKix record whose execution fingerprint
is current, must carry its recorded status, reason and gap forward on import
whatever its evidence holds, and while revalidating only from an observed
payload holding every observation the scenario and its baseline name;
without one it writes nothing and reports the record stale for lacking
``observed``.

Recurring questions: ``report._add_gap``, on in-memory registers whose
``gaps`` is a list of entries or a mapping of gap id to entry, must reopen
the closed entry of the raise's category that asks its question (the text of
``unknown`` up to the first colon) under its own id: open, its resolving
evidence moved to ``superseded_evidence``, the raise's ``unknown`` and
``needed`` taken and its items added. It must add to the open entry asking
the question, which it prefers to a closed one asking the same, only the
items that entry does not list yet, and change nothing when it lists them
all; give a new id, the category's next free number, only to another
question or to the same question in another category; and refuse, changing
nothing, an entry asking the question whose ``affected_inventory_ids`` is
not a list.

Closure records: ``inventory_tools.append_closures``, on copies of the
committed inventory and register in a repository root of the case's own,
must append ``{date, gap_id, reason}`` to ``header.changes`` and leave the
items, the total and the additions as they were, so the drift check still
passes, and refuse, writing nothing, a gap id that is no register entry or
no GAP id, and a blank reason; the header check must refuse a change whose
``gap_id`` is not a GAP id. ``report._settle_gaps`` must close an
inferred-not-observed entry whose items are all verified, and GAP-MIS-009
once the installation's versions are recorded, only when the inventory
holds the additions their importing and verifying executions entail (even
for a scenario not yet validated) and no verified member is recorded
unreachable. Otherwise it must leave the entry
open and say what the inventory lacks; matched, blank or already-added
short/clipped messages and unrelated executions must not prevent closing.
Inferred revalidation applies the same check. Each closing queues one
closure record whose reason names the entry, why it closed and its evidence.
``report._write_register`` must record those closings in the inventory
before ``gaps.yaml``; when the inventory cannot record them, leave each entry
open with no evidence and say why among the gaps changed. If ``gaps.yaml``
then cannot be written, ``inventory_tools.withdraw_closures`` must remove
only the returned records under the inventory lock, one equal occurrence
at a time from the end, preserving unrelated changes and items, including a
concurrent addition and its raised total. It must refuse missing occurrences
or malformed requests without writing; a failed withdrawal must report both
failures and the records left behind. The coverage report must list a
closure record by its gap id beside the additions.

Capture version planning: ``baselines.bump_needed``, on in-memory copies of
the baselines HEAD holds for TRN-CBTRN02C-negative-03 (observed) and
TRN-CBTRN02C-recovery-02 (derived), must judge a change as ``--check-bumps
--base HEAD`` does, from HEAD's version and platform: ``none`` for HEAD's own
bytes, ``patch`` for manifest metadata, ``minor`` for changed raw bytes, a
changed capture platform or raw bytes added to a derived baseline, ``major``
for a changed decoded value. It must return ``None`` at the starting commit,
where git lists no baseline, and raise ``BumpCheckError``, never ``None``,
when git fails to list or read the base, which ``capture.py`` refuses as a
``CaptureError`` carrying git's message.
``capture.plan_version`` must raise HEAD's version, never the working tree's,
so repeated captures plan one version; keep ``1.0.0`` with ``--bump`` ignored
for a baseline HEAD lacks; and refuse a missing ``--bump`` or one below the
level the change needs, naming that level. The bump check behind that
planning must parse HEAD's YAML only in temporary folders directly in
capture's work root, and leave none behind, whether ``CARDDEMO_TEST_WORKDIR``
is unset or names another directory.

Work root: a ``CARDDEMO_TEST_WORKDIR`` that is unset, empty, blank, a regular
file, under one, the repository, inside it or an unwritable directory must
give conftest and ``capture.py`` a fresh empty ``carddemo-`` root of their own
in the temporary directory, outside the repository, which they remove when it
stays empty, with a warning naming a set value and its reason, and must put
the bump check's scratch folder in the temporary directory instead; a refused
value creates nothing in the repository. Each such case points
``tempfile.gettempdir()`` at a folder of its own inside the session work root,
so nothing it makes lies outside that root. The same values given as
``--work-root`` stop ``capture.py`` with exit status 2. When no temporary
root outside the repository can be made, conftest stops with a usage error
that names the refused value too. A usable root is used as configured,
created when missing, kept, and gets no warning.

Region stage observations: ``base.region_stage_observation`` and
``parse_region_stage`` must round-trip ``@BEFORE:<JOB>:@REGION-FILES`` and
``@AFTER:<JOB>:@REGION-FILES``, which ``observation_matches`` covers exactly
when it covers ``@REGION-FILES`` (never through ``"@AFTER:*"``), and refuse
an unknown stage, a malformed job and any other name. gnucobol-batch and the
runner's default must block one under GAP-MIS-011, and neither adapter may
unload one as a dataset stage. ``scenarios.validate`` must accept one under
``expected.file_state`` for a job the chain runs once, its records lines, and
refuse a malformed name, a job ``inputs.job`` does not run or lists twice, an
online scenario's, a record that is not a line and one under
``expected.datasets``. ``baselines.decode_result`` must decode one as its
lines, ``capture.raw_datasets`` keep no raw bytes of it, and ``compare`` pass
the committed CFG-OPENFIL, CFG-CLOSEFIL and BAT-RUNORDER baselines on their
derived region states and fail them on files left unchanged where the job
changes them, or changed where it does not. ``BatchService.run_jobs`` must
call ``before_job`` just before each submission, after the previous
``after_job``, and never for a job an abend leaves unreached.
``UniKixAdapter``, on fakes, must declare one exactly when
``region_file_inquire`` probed available, unloads or not, under GAP-MIS-011
otherwise; cover ``fct.lst`` in its source identity; refuse, before the
region, one whose job the chain does not run once; and read the region files
just before the job is submitted and, at its end, before the unloads of that
job's dataset stages, keeping each under its stage name and leaving out the
ones of jobs never reached.

Session close on unikix: ``UniKixAdapter.close``, on fake commands, must end
every ``s3270`` session first, then attempt ``region_stop`` (unless the
adapter has stopped the region itself) and ``bpe_stop`` whatever the other
did; append each stop that probed unavailable or failed to
``<deployment root>/logs/session-close.log`` as one line with the UTC time
and the execution id, and raise ``AdapterUnavailable`` with the
``commands.yaml`` gap of the first of them, its reason listing every problem
and where they were appended or why they could not be; return ``None`` when
nothing failed or no session started; and, closed again, attempt only the
stops still owed, never raising once nothing is left to stop.
"""

from __future__ import annotations

import configparser
import copy
import dataclasses
import datetime
import functools
import inspect
import json
import os
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
from collections import Counter
from collections.abc import Callable, Iterator, Mapping
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

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
    ADAPTERS_DIR,
    BASELINES_DIR,
    FIXTURES_FILE,
    INVENTORY_DIR,
    PROTECTED_BASE,
    RESULTS_DIR,
    SCENARIOS_DIR,
    TESTS_ROOT,
    UNIKIX_EVIDENCE_DIR,
    HarnessError,
    baselines,
    capture,
    codec,
    dump_yaml,
    fixtures,
    inventory_tools,
    layouts,
    load_yaml,
    report,
    results,
    runner,
    scenarios,
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
# Each judgement: the import (a gaps document) or a revalidation, whether the
# observed payload holds every observation the scenario and its baseline name
# or is absent, and whether the recorded verdict is then written.
NON_RAN_JUDGEMENTS = [
    pytest.param(False, False, False, id="revalidation-without-observed"),
    pytest.param(True, False, True, id="import-without-observed"),
    pytest.param(False, True, True, id="revalidation-with-every-observation"),
]
NON_RAN_FINGERPRINT = "5" * 64
NON_RAN_LACKS = "observed (the run's return code, messages and screens)"


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
    found = report.Evidence(EVIDENCE_EXECUTION_ID, NON_RAN_SCENARIO, work_root_case / NON_RAN_SCENARIO, raw, {}, {})
    assert not found.ran
    assert report.missing_observations(found, scenario, baseline) == ([] if observed else [NON_RAN_LACKS])
    writes: list[dict] = []
    monkeypatch.setattr(report, "current_fingerprint", lambda current: (NON_RAN_FINGERPRINT, None))
    monkeypatch.setattr(report, "_write_validation", lambda evidence, validation: writes.append(dict(validation)))
    document: dict | None = {} if importing else None
    summary = report.ImportSummary(EVIDENCE_EXECUTION_ID)
    outcome = report._validate_evidence(found, scenario, document, summary, report._CopyGaps({}, frozenset()), None)
    assert document == ({} if importing else None) and summary.gaps_added == []
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


# Closure records: the inventory update of a register entry the import or the
# revalidation closes, and the write that records it before gaps.yaml. Every
# case works on copies of the committed inventory.yaml and gaps.yaml in a
# repository root of its own inside the work root, which report.py's
# gaps.yaml, inventory and repository root point at for the case. The
# gaps.yaml copy starts every inferred-not-observed entry and GAP-MIS-009
# open, without evidence and with its full membership, whatever the
# committed register's lifecycle holds, so a valid import that closed or
# narrowed one changes no case's outcome.
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
    ``inventory.yaml`` and ``gaps.yaml`` under ``tests/inventory/``, which
    ``report.GAPS_FILE``, ``inventory_tools.INVENTORY_FILE`` and
    ``report.REPO_ROOT`` name for the case; removed afterwards.

    The ``gaps.yaml`` copy holds a controlled lifecycle state rather than the
    committed one: every inferred-not-observed entry and GAP-MIS-009 is
    ``open`` with ``resolving_evidence: null`` and no
    ``superseded_evidence``, and a narrowed entry has the full membership its
    ``narrowed_from`` kept (``affected_inventory_ids``, and ``affected`` only
    when it held clauses) with ``narrowed_from`` dropped. The copy is
    rewritten, its comments kept, only when the committed register differs
    from that state."""
    directory = Path(tempfile.mkdtemp(prefix=CLOSURE_ROOT_PREFIX, dir=carddemo_work_root)).resolve()
    try:
        copies = directory / "tests" / "inventory"
        copies.mkdir(parents=True)
        for name in ("inventory.yaml", "gaps.yaml"):
            shutil.copyfile(INVENTORY_DIR / name, copies / name)
        monkeypatch.setattr(report, "GAPS_FILE", copies / "gaps.yaml")
        monkeypatch.setattr(inventory_tools, "INVENTORY_FILE", copies / "inventory.yaml")
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


def _cli(
    repo_root: Path, script: str, *args: str, work_root: Path, extra_env: Mapping[str, str] | None = None
) -> subprocess.CompletedProcess:
    """Run ``python tests/harness/<script> <args>`` from the repository root,
    writing no bytecode into the checkout.

    The child gets this process's environment plus ``extra_env``, with
    ``CARDDEMO_TEST_WORKDIR`` set to ``work_root`` (the session's
    carddemo_work_root, or a folder of a case's own inside it) whatever the
    variable held here, so a scratch folder the script makes stays inside the
    session work root even when the variable is unset or unusable and conftest
    made the root. ``extra_env`` cannot replace that variable or
    ``PYTHONDONTWRITEBYTECODE``."""
    return subprocess.run(
        [sys.executable, str(repo_root / "tests" / "harness" / script), *args],
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
    assert problems == [], "\n".join(problems)


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
# the folder inside the repository that a refusal must not create (or None).


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
    # runs as root too; every other mkdtemp works as before.
    root = case / "unwritable"
    root.mkdir()
    make_directory = tempfile.mkdtemp

    def mkdtemp(suffix: str | None = None, prefix: str | None = None, dir: str | Path | None = None) -> str:
        if dir is not None and Path(dir).resolve() == root:
            raise PermissionError(f"[Errno 13] Permission denied (simulated): {os.path.join(dir, prefix or '')!r}")
        return make_directory(suffix, prefix, dir)

    monkeypatch.setattr(tempfile, "mkdtemp", mkdtemp)
    return str(root), None


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
]
REFUSED_WORK_ROOTS = [case for case in REPLACED_WORK_ROOTS if case.values[0] is not _unset_work_root]


def _set_work_root_variable(monkeypatch: pytest.MonkeyPatch, value: str | None) -> None:
    if value is None:
        monkeypatch.delenv(WORKDIR_VARIABLE, raising=False)
    else:
        monkeypatch.setenv(WORKDIR_VARIABLE, value)


def _assert_not_created(absent: Path | None) -> None:
    """Fail when a refused root created ``absent`` inside the repository,
    after removing it."""
    if absent is None or not os.path.lexists(absent):
        return
    shutil.rmtree(absent)
    pytest.fail(f"{absent} was created inside the repository")


def _assert_fresh_temporary_root(root: Path, case: Path) -> None:
    """``root`` is a new, empty ``carddemo-`` folder of the temporary
    directory, outside the repository and apart from the session work root."""
    assert root == root.resolve(), root
    assert root.name.startswith("carddemo-") and root.parent == Path(tempfile.gettempdir()).resolve(), root
    assert root.is_dir() and not any(root.iterdir()), root
    assert root != TESTS_ROOT.parent and TESTS_ROOT.parent not in root.parents, root
    assert root != case.parent, "the session work root is reused instead of a fresh one"


def _configure_session(monkeypatch: pytest.MonkeyPatch) -> tuple[SimpleNamespace, list[Warning]]:
    """A stand-in for pytest's config after conftest's ``pytest_configure``,
    with the config-time warnings it gave."""
    # pytest imports tests/conftest.py as the module `conftest`.
    import conftest

    monkeypatch.delenv("CARDDEMO_ADAPTER", raising=False)
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


@pytest.mark.parametrize("existing", [pytest.param(True, id="existing"), pytest.param(False, id="missing")])
def test_conftest_uses_a_usable_work_root(
    monkeypatch: pytest.MonkeyPatch, work_root_case: Path, existing: bool
) -> None:
    import conftest

    root = work_root_case / "work root" / "nested"
    if existing:
        root.mkdir(parents=True)
    _set_work_root_variable(monkeypatch, str(root))
    config, given = _configure_session(monkeypatch)
    assert config.stash[conftest.WORK_ROOT_KEY] == root
    assert conftest.CREATED_WORK_ROOT_KEY not in config.stash
    assert given == []
    assert root.is_dir() and not any(root.iterdir()), "the write probe stays in the work root"
    conftest.pytest_unconfigure(config)
    assert root.is_dir(), "pytest_unconfigure removes a configured work root"


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
    assert capsys.readouterr().err == ""


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
# reads, and a tracer a child process imports at start-up as `sitecustomize`
# when its folder comes first on PYTHONPATH: it appends the real path of every
# folder tempfile.mkdtemp makes (TemporaryDirectory's included) to the log
# beside it, then runs the `sitecustomize` it shadows, if there is one.
BUMP_SCRATCH_PREFIX = "carddemo-bumps-"
MKDTEMP_TRACER_LOG = "mkdtemp.log"
MKDTEMP_TRACER = f"""\
import importlib.machinery
import importlib.util
import os
import sys
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

_shadowed = importlib.machinery.PathFinder.find_spec(
    "sitecustomize", [entry for entry in sys.path if os.path.abspath(entry or os.curdir) != _FOLDER]
)
if _shadowed is not None and _shadowed.loader is not None:
    _shadowed.loader.exec_module(importlib.util.module_from_spec(_shadowed))
"""


def test_baseline_bumps_scratch_stays_in_the_work_root(carddemo_repo_root: Path, work_root_case: Path) -> None:
    """The bump-check command line makes its scratch folder inside the work
    root ``_cli`` hands it, whatever ``CARDDEMO_TEST_WORKDIR`` holds in this
    process, as the tracer records in the child. The work root is a folder of
    the case's own, narrower than the session's, so a scratch folder made in
    the session root or in the temporary directory is caught alike. Only
    where the scratch lies is judged here: the bump verdict, which uncommitted
    baseline changes decide, is test_baseline_bumps', so the command need only
    end with a verdict (exit 0 or 1 and its summary line, no traceback)."""
    work_root = work_root_case / "work root"
    work_root.mkdir()
    tracer = work_root_case / "tracer"
    tracer.mkdir()
    (tracer / "sitecustomize.py").write_text(MKDTEMP_TRACER, encoding="utf-8")
    search_path = os.pathsep.join(filter(None, [str(tracer), os.environ.get("PYTHONPATH")]))
    completed = _cli(
        carddemo_repo_root,
        "baselines.py",
        "--check-bumps",
        "--base",
        "HEAD",
        work_root=work_root,
        extra_env={"PYTHONPATH": search_path},
    )
    verdict = completed.stderr.splitlines()[-1:] or [""]
    assert (
        completed.returncode in (0, 1)
        and "Traceback" not in completed.stderr
        and verdict[0].startswith("baselines.py: ")
        and verdict[0].endswith(" against HEAD")
    ), (
        f"{' '.join(map(str, completed.args[1:]))} exited {completed.returncode} without a bump verdict\n"
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
        inventory_tools.load()["header"]["protected_base"],
        remedy="The inventory header's protected_base must be the suite's starting commit, "
        "and this clone's history must hold it.",
    )
    _assert_passes(_cli(carddemo_repo_root, "report.py", "--check-protected", work_root=carddemo_work_root))


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
    if unwritable:
        # The deployment root is a file, so its log folder cannot be created.
        log_dir.parent.parent.mkdir(parents=True)
        log_dir.parent.write_text("not a directory\n", encoding="utf-8")
    if session:
        adapter._env, adapter._commands = SimpleNamespace(log_dir=log_dir), commands
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
