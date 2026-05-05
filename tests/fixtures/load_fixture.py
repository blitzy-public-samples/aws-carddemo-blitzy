#!/usr/bin/env python3
# =====================================================================
# load_fixture.py - Pure byte-extraction helper for the CardDemo
# cobol-check test suite.
#
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License.
# =====================================================================
#
# This script is the SINGLE non-COBOL test helper in the suite.  Its only
# job is to read fixed-width records from `app/data/ASCII/*.txt` and emit
# COBOL snippet copybooks under `tests/fixtures/cobol-snippets/` that
# `MOCK FILE ... ON READ` blocks can `COPY` verbatim.
#
# *No business logic*.  No transformation of the bytes other than
# escaping COBOL-string-literal special characters.  No domain knowledge
# is encoded.  The list of fixtures below is purely structural metadata
# (filename, record width, target field name) extracted from the
# documented CardDemo record layouts.
# =====================================================================

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List


@dataclass(frozen=True)
class FixtureSpec:
    """Structural metadata for one fixed-width fixture file."""

    source_filename: str
    record_width: int
    target_field: str
    snippet_basename: str
    record_indices: List[int]


# ----------------------------------------------------------------------
# Fixture catalog.  This is a list of *file-format* facts, not business
# logic.  Each entry maps an existing `app/data/ASCII/<file>.txt` to:
#   * the COBOL field name a MOCK ON READ will write into
#   * a snippet basename (without extension)
#   * the 1-based indices of records the catalog wants emitted
# Adding a new fixture row requires only an additional entry here -- no
# code changes -- so the script remains business-logic-free.
# ----------------------------------------------------------------------
FIXTURES: List[FixtureSpec] = [
    FixtureSpec("acctdata.txt", 300, "ACCOUNT-RECORD",
                "ACCT-FIXTURE", [1, 2, 3]),
    FixtureSpec("carddata.txt", 150, "CARD-RECORD",
                "CARD-FIXTURE", [1, 2, 3]),
    FixtureSpec("cardxref.txt",  36, "CARD-XREF-RECORD",
                "XREF-FIXTURE", [1, 2, 3]),
    FixtureSpec("custdata.txt", 500, "CUSTOMER-RECORD",
                "CUST-FIXTURE", [1, 2, 3]),
    FixtureSpec("dailytran.txt", 350, "DALYTRAN-RECORD",
                "DALYTRAN-FIXTURE", [1, 2, 3]),
    FixtureSpec("discgrp.txt",   50, "DIS-GROUP-RECORD",
                "DISCGRP-FIXTURE", [1, 25, 51]),
    FixtureSpec("tcatbal.txt",   50, "TRAN-CAT-BAL-RECORD",
                "TCATBAL-FIXTURE", [1, 2, 3]),
    FixtureSpec("trancatg.txt",  60, "TRAN-CAT-RECORD",
                "TRANCATG-FIXTURE", [1, 2, 3]),
    FixtureSpec("trantype.txt",  60, "TRAN-TYPE-RECORD",
                "TRANTYPE-FIXTURE", [1, 2, 3]),
]


def _escape_cobol_literal(byte_text: str) -> str:
    """Escape a string for inclusion inside a COBOL alphanumeric literal.

    Only the apostrophe needs doubling; other ASCII characters are
    safe inside `'...'`.  No semantic transformation is performed.
    """
    return byte_text.replace("'", "''")


def _read_record(source_path: Path, record_width: int, index: int) -> str:
    """Return the *index*-th (1-based) fixed-width record from *source_path*.

    Lines are read as UTF-8 (the fixture files are pure ASCII).  Any
    line shorter than the declared width is right-padded with spaces;
    longer lines are truncated.  This is purely mechanical.
    """
    text = source_path.read_text(encoding="ascii", errors="strict")
    lines = text.splitlines()
    if index < 1 or index > len(lines):
        raise IndexError(
            f"Record index {index} out of range for {source_path} "
            f"(file has {len(lines)} records)"
        )
    record = lines[index - 1]
    if len(record) < record_width:
        record = record.ljust(record_width)
    elif len(record) > record_width:
        record = record[:record_width]
    return record


def _emit_snippet(spec: FixtureSpec, index: int, repo_root: Path,
                  output_dir: Path) -> Path:
    """Generate one COBOL `.cpy` snippet from one fixture record."""
    source_path = repo_root / "app" / "data" / "ASCII" / spec.source_filename
    record = _read_record(source_path, spec.record_width, index)
    literal = _escape_cobol_literal(record)
    snippet_name = f"{spec.snippet_basename}-{index:03d}.cpy"
    snippet_path = output_dir / snippet_name

    # Fixed-format COBOL limits Area B to columns 12..72 (inclusive).
    # We render each chunk as `<15 spaces>'<chunk>'` which means the
    # closing quote falls at column (16 + len(chunk) + 1).  To keep the
    # closing quote at column <= 72 we cap each chunk at 55 characters.
    # We choose 50 to leave a small safety margin for inline comments
    # in Area C should reviewers add them later.  STRING is permitted
    # because BEFORE-EACH / AFTER-EACH blocks may contain any COBOL
    # statement.
    chunk_size = 50
    chunks = [literal[i:i + chunk_size]
              for i in range(0, len(literal), chunk_size)]

    out_lines: list[str] = []
    out_lines.append(
        "      *>>>>> Generated by tests/fixtures/load_fixture.py"
    )
    out_lines.append(
        f"      *>>>>> Source: app/data/ASCII/{spec.source_filename} "
        f"record #{index}"
    )
    out_lines.append(
        f"      *>>>>> Width : {spec.record_width} bytes; "
        f"target {spec.target_field}"
    )
    out_lines.append(
        f"      *>>>>> Do not edit by hand -- regenerate via "
        f"`make fixtures`."
    )
    # Emit a STRING statement that builds the record in the target
    # field.  Each chunk becomes one literal in the STRING list.
    out_lines.append("           STRING")
    for c in chunks:
        out_lines.append(f"               '{c}'")
    out_lines.append(
        f"               DELIMITED BY SIZE INTO {spec.target_field}"
    )
    out_lines.append("           END-STRING")
    out_lines.append("           .")
    snippet_path.write_text("\n".join(out_lines) + "\n", encoding="ascii")
    return snippet_path


def regenerate(repo_root: Path, output_dir: Path,
               specs: Iterable[FixtureSpec]) -> List[Path]:
    """Regenerate every snippet listed by *specs*. Returns the file list."""
    output_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for spec in specs:
        for idx in spec.record_indices:
            written.append(_emit_snippet(spec, idx, repo_root, output_dir))
    return written


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=("Regenerate fixed-width record snippets from "
                     "app/data/ASCII into tests/fixtures/cobol-snippets/."))
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Repository root (defaults to two levels above this script).",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=None,
        help=("Output directory for generated snippets "
              "(defaults to <repo-root>/tests/fixtures/cobol-snippets)."),
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="Print the catalog of snippets that would be generated, "
             "then exit without writing anything.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_arg_parser().parse_args(argv)
    repo_root = args.repo_root.resolve()
    output_dir = (args.output_dir
                  or repo_root / "tests" / "fixtures" / "cobol-snippets")
    output_dir = output_dir.resolve()

    if args.list:
        for spec in FIXTURES:
            for idx in spec.record_indices:
                print(f"{spec.snippet_basename}-{idx:03d}.cpy "
                      f"<- {spec.source_filename}#{idx} "
                      f"({spec.record_width} bytes -> {spec.target_field})")
        return 0

    written = regenerate(repo_root, output_dir, FIXTURES)
    for path in written:
        print(path.relative_to(repo_root))
    return 0


if __name__ == "__main__":
    sys.exit(main())
