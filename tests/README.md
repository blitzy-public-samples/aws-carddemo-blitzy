<!--
Copyright Amazon.com, Inc. or its affiliates.
All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License").
You may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific
language governing permissions and limitations under the License.
-->

# CardDemo Test Suite

This directory contains the off-platform automated test infrastructure
for the CardDemo COBOL/CICS/VSAM application. The suite uses
**[cobol-check]** (Open Mainframe Project, version `0.2.16`) running
under **[GnuCOBOL] 3.1.2** with **OpenJDK 21**.

[cobol-check]: https://github.com/openmainframeproject/cobol-check
[GnuCOBOL]:    https://gnucobol.sourceforge.io/

> **Test authoring rules** are not negotiable. They are documented in
> the project root's Agent Action Plan and enforced automatically by
> the four scripts in [`tests/lint/`](lint/). Read those before writing
> a new testsuite.

---

## Layout

```
tests/
├── README.md                            # this file
├── cobol-check/
│   ├── config.properties                # cobol-check runtime config
│   ├── lib/                             # cobol-check JAR (bootstrapped)
│   │   └── cobol-check-0.2.16.jar
│   ├── scripts/                         # GnuCOBOL compile-and-run helper
│   │   └── linux_gnucobol_run_tests
│   └── <PROGRAM>/                       # one directory per program-under-test
│       └── <PROGRAM>.cut                # cobol-check testsuite (.cut)
├── stubs/
│   ├── CEEDAYS.cbl                      # LE date-conversion stub
│   ├── CEE3ABD.cbl                      # LE abend stub
│   └── DFHEI1.cbl                       # CICS API stub (link-time only)
├── fixtures/
│   ├── load_fixture.py                  # byte-extraction helper
│   └── cobol-snippets/                  # generated record-image .cpy files
└── lint/
    ├── check_no_business_logic.sh
    ├── check_no_production_redeclaration.sh
    ├── check_assertion_density.sh
    ├── check_isolation.sh
    └── parse_gcov_summary.sh
```

### Directory-per-program convention

cobol-check expects every program-under-test to have its own
directory under the configured `test.suite.directory`. So the testsuite
for `app/cbl/CSUTLDTC.cbl` lives at `tests/cobol-check/CSUTLDTC/` and
contains one or more `.cut` files (typically just `CSUTLDTC.cut`).

Multiple `.cut` files inside one program directory are allowed and are
the recommended way to keep individual files below 600 lines.  For
example, `COCRDUPC` may legitimately have:

```
tests/cobol-check/COCRDUPC/
├── COCRDUPC-validation.cut
├── COCRDUPC-rewrite.cut
└── COCRDUPC-receive-map.cut
```

---

## Prerequisites

| Tool      | Version        | Apt package(s)                                        |
| --------- | -------------- | ----------------------------------------------------- |
| GnuCOBOL  | `3.1.2`        | `gnucobol3 libcob4-dev libcob4t64`                    |
| OpenJDK   | `21.0.10` LTS  | `openjdk-21-jdk-headless`                             |
| GCC       | `13.2`         | `gcc` (provides `gcov` for coverage)                  |
| Make      | `4.3`          | `make`                                                |
| Python    | `3.10+`        | `python3` (used by `tests/fixtures/load_fixture.py`)  |
| cobol-check | `0.2.16`     | downloaded by `make init` from the upstream release   |

Install on Ubuntu 24.04:

```bash
sudo apt-get install -y \
    gnucobol3 libcob4-dev libcob4t64 \
    openjdk-21-jdk-headless \
    gcc make python3 curl unzip
```

---

## Running the suite locally

```bash
make init        # one-time: download cobol-check JAR (~270 KB)
make fixtures    # regenerate record-image .cpy snippets
make lint        # run the four validation gates
make test        # run every testsuite under tests/cobol-check/
make coverage    # rerun with --coverage and emit gcov summary
```

To run a single program:

```bash
make test-one PROGRAM=CSUTLDTC
```

To debug a single program with verbose logging:

```bash
make test-debug PROGRAM=CSUTLDTC
```

To clean the build artifacts:

```bash
make clean
```

---

## Authoring a new testsuite

1. **Create the program directory**
   `mkdir -p tests/cobol-check/<PROGRAM>/`.

2. **Copy the canonical template**
   The first delivered testsuite is the in-house pattern; copy its
   structure (header banner, `BEFORE-EACH`, alphabetized `EXPECT`s,
   `VERIFY` after `EXPECT`).

3. **Reference the production source verbatim**
   *Never* copy production COBOL into a `.cut` file. cobol-check's
   precompiler reads the unmodified production source straight out of
   `app/cbl/` and merges your testsuite into a copy.

4. **Mock only external dependencies**
   The permitted mock targets are documented in the Agent Action Plan
   §0.3.3:

   * **`MOCK FILE <fd-name>`** – every VSAM cluster (`ACCTFILE`,
     `CARDFILE`, `XREFFILE`, `CUSTFILE`, `TRANSACT`, `DALYTRAN`,
     `DALYREJS`, `TCATBAL`, `DISCGRP`, `USRSEC`, `TRANTYPE`,
     `TRANCATG`, …) and every sequential dataset.
   * **`MOCK CALL "<name>"`** – every Language Environment service
     (`CEEDAYS`, `CEE3ABD`) and every inter-program subprogram CALL
     (`CSUTLDTC`, `CBSTM03B`).
   * **`MOCK CICS <verb>`** – every `EXEC CICS` verb (`READ`,
     `WRITE`, `REWRITE`, `STARTBR`, `READNEXT`, `READPREV`, `ENDBR`,
     `RECEIVE MAP`, `SEND MAP`, `XCTL`, `LINK`, `RETURN`, `READQ TD`,
     `WRITEQ TD`, …).

   `MOCK PARAGRAPH` and `MOCK SECTION` are **forbidden** for the
   program-under-test (they would mock internal logic, which is
   exactly what the user's directive prohibits).

5. **Assert against real production state**
   `EXPECT` clauses must reference (a) `LINKAGE SECTION` fields of the
   program-under-test, (b) `WORKING-STORAGE` fields, (c)
   `RETURN-CODE`, (d) record-buffer fields written via a mocked
   `WRITE`/`REWRITE`, or (e) `EIBRESP`/`EIBRESP2` after a mocked
   `EXEC CICS`. All five categories are *side effects of real
   production code executing*.

6. **Run the validation gates** (`make lint`) before pushing.

### What the validation gates enforce

| Script                                   | Purpose                                                                                |
| ---------------------------------------- | -------------------------------------------------------------------------------------- |
| `check_no_business_logic.sh`             | No `COMPUTE`/`MULTIPLY`/`DIVIDE`/`ADD`/`SUBTRACT` outside `BEFORE-EACH`/`AFTER-EACH`. No `MOCK PARAGRAPH`/`MOCK SECTION` directives. |
| `check_no_production_redeclaration.sh`   | No `IDENTIFICATION DIVISION` or `PROGRAM-ID` redeclaration of any program in `app/cbl/`. |
| `check_assertion_density.sh`             | Every `TESTCASE` has at least one `EXPECT`. Every `MOCK`-using `TESTCASE` also has a `VERIFY`. |
| `check_isolation.sh`                     | Every testsuite declares a `BEFORE-EACH` block.                                          |

If any gate fails, the build fails with a clear diagnostic naming the
file, line number, and the user-prompt rule that was violated.

---

## Fixtures

The `app/data/ASCII/*.txt` files supply byte-exact record images that
production VSAM would deliver.  `tests/fixtures/load_fixture.py`
extracts a curated subset of records and writes COBOL `STRING`
snippets under `tests/fixtures/cobol-snippets/`.  Snippets are
included in mocks via `COPY 'ACCT-FIXTURE-001'.` (or similar).

| Source (`app/data/ASCII/`) | Width | Snippet basename       | Records emitted |
| -------------------------- | ----- | ----------------------- | --------------- |
| `acctdata.txt`             | 300   | `ACCT-FIXTURE-NNN.cpy`     | 1, 2, 3 |
| `carddata.txt`             | 150   | `CARD-FIXTURE-NNN.cpy`     | 1, 2, 3 |
| `cardxref.txt`             |  36   | `XREF-FIXTURE-NNN.cpy`     | 1, 2, 3 |
| `custdata.txt`             | 500   | `CUST-FIXTURE-NNN.cpy`     | 1, 2, 3 |
| `dailytran.txt`            | 350   | `DALYTRAN-FIXTURE-NNN.cpy` | 1, 2, 3 |
| `discgrp.txt`              |  50   | `DISCGRP-FIXTURE-NNN.cpy`  | 1, 25, 51 |
| `tcatbal.txt`              |  50   | `TCATBAL-FIXTURE-NNN.cpy`  | 1, 2, 3 |
| `trancatg.txt`             |  60   | `TRANCATG-FIXTURE-NNN.cpy` | 1, 2, 3 |
| `trantype.txt`             |  60   | `TRANTYPE-FIXTURE-NNN.cpy` | 1, 2, 3 |

To add a new fixture row, edit the `FIXTURES` list inside
`tests/fixtures/load_fixture.py`.  Re-run `make fixtures` to
regenerate the snippets.  No business logic lives in the helper – it
performs byte-exact extraction only.

---

## Stubs

The three files under `tests/stubs/` are link-time shims that allow
the production COBOL source to compile and run off-platform.  They
contain **no business logic**:

| Stub             | Replaces                          | Behavior                                                                                          |
| ---------------- | --------------------------------- | ------------------------------------------------------------------------------------------------- |
| `CEEDAYS.cbl`    | IBM Language Environment date svc | Returns feedback code 0 (success) by default.  cobol-check's `MOCK CALL "CEEDAYS"` overrides this. |
| `CEE3ABD.cbl`    | IBM Language Environment abend svc| Sets a flag and returns to the caller instead of terminating the address space.                    |
| `DFHEI1.cbl`     | CICS API entry point              | No-op fallback when a `MOCK CICS` directive is missing for a particular EXEC CICS verb.            |

---

## Coverage targets

The per-program targets come from §0.7.1 of the Agent Action Plan and
are enforced by `tests/lint/parse_gcov_summary.sh` after `make
coverage` runs.

| Component category                                | Target |
| ------------------------------------------------- | ------ |
| `CSUTLDTC` (date conversion)                      | 100%   |
| `CBSTM03B` (I/O dispatcher)                       | 100%   |
| `CBACT04C`, `CBTRN02C`, `CBTRN01C`, `CBTRN03C`, `COBIL00C` | 80%    |
| Other batch / CICS programs                       | 75%    |
| **Overall**                                       | **70%** |

Coverage is measured by GnuCOBOL's `--coverage` flag, which emits
`gcov`-compatible counter data.  HTML reports can be produced with
`lcov` + `genhtml` if desired (not enforced by CI).

---

## Continuous Integration

[`.github/workflows/test.yml`](../.github/workflows/test.yml) runs on
every push and pull request.  It installs GnuCOBOL 3.1.2, OpenJDK 21,
bootstraps cobol-check 0.2.16, runs `make lint test coverage`, and
uploads the per-run gcov summary as a build artifact named
`cobol-check-coverage`.

---

## Troubleshooting

* **`WRN001: No test suite directory for program <X> was found`** –
  cobol-check expects per-program directories.  Move your file into
  `tests/cobol-check/<X>/<X>.cut`.
* **`WRN002: No test suite files were found under directory <X>`** –
  cobol-check found the directory but couldn't recognize any of its
  files as testsuites.  Make sure the file extension is `.cut` (lower
  case) and that the file is non-empty.
* **`Mock <CALL> <"X"> does not reference any construct in the source code`** –
  cobol-check could not find a matching `CALL "X"` in the
  program-under-test.  Either you mocked the wrong subprogram name or
  the production source's `CALL` statement spans multiple lines and
  has not been recognized; consult the parser error log at
  `target/cobol-check/ParserErrorLog.txt`.
* **`cobc: ... CC##99.CBL: No such file or directory`** – the merged
  test program was not generated.  This is almost always caused by an
  empty test suite directory or a syntax error in the `.cut` file
  that prevents cobol-check from emitting the merged source.
