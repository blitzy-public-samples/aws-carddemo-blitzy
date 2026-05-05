<!--
Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# CardDemo Automated Test Suite

This directory contains the project's automated unit-test layer for the
production COBOL programs in `app/cbl/`. The suite uses
**[cobol-check]** (Open Mainframe Project, version `0.2.16`) as the
test framework and **[GnuCOBOL] 3.1.2** as the off-platform compiler,
hosted on **OpenJDK 21.0.10** for the framework's Java runtime.

Tests **import** the unmodified production COBOL source (via cobol-check's
source-merge precompiler) and **mock** every external dependency: VSAM
file I/O, CICS commands, Language Environment callable services, and
inter-program subprogram CALLs. The merged binary that cobol-check
produces is the *real* production code with test scaffolding injected
beside it — there is no transliteration, no paraphrase, and no stub of
any internal paragraph.

The suite is purely off-platform. No z/OS LPAR, no live CICS region,
and no real VSAM cluster are required to run it. Every artifact in
this folder is reproducible from a clean Ubuntu 24.04 Noble checkout
with `apt-get` and a single `make init` bootstrap.

[cobol-check]: https://github.com/openmainframeproject/cobol-check
[GnuCOBOL]:    https://gnucobol.sourceforge.io/

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Repository Layout](#repository-layout)
- [How to Run](#how-to-run)
- [How to Author a New Testsuite](#how-to-author-a-new-testsuite)
- [Coverage Output](#coverage-output)
- [Validation Gates](#validation-gates)
- [Fixture Catalog](#fixture-catalog)
- [Stub Subprograms](#stub-subprograms)
- [Mocking Cookbook](#mocking-cookbook)
- [Troubleshooting](#troubleshooting)
- [References](#references)

---

## Prerequisites

The toolchain is fixed by the project's verified build environment.
Every version below corresponds to a specific package candidate
available on the Ubuntu 24.04 Noble apt repositories (or, for
cobol-check, a specific GitHub release artifact). No other versions
have been validated.

| Component                     | Package                    | Version                  | Purpose                                                        |
| ----------------------------- | -------------------------- | ------------------------ | -------------------------------------------------------------- |
| Off-platform COBOL compiler   | `gnucobol3`                | `3.1.2-5.1ubuntu1`       | Compiles production COBOL plus testsuites                      |
| GnuCOBOL runtime              | `libcob4t64`               | `3.1.2-5.1ubuntu1`       | Auto-installed dependency of `gnucobol3`                       |
| GnuCOBOL development headers  | `libcob4-dev`              | `3.1.2-5.1ubuntu1`       | Required for linking stubs alongside the program-under-test    |
| Java runtime + JDK            | `openjdk-21-jdk-headless`  | `21.0.10+7-1~24.04`      | Hosts the cobol-check JAR (Java 8+ minimum; LTS chosen)        |
| Build orchestrator            | `make`                     | `4.3-4.1build2`          | Runs `make test`, `make coverage`, `make lint`, etc.           |
| C compiler / coverage         | `gcc`                      | `4:13.2.0-7ubuntu1`      | Provides `gcov` (transitive GnuCOBOL dependency)               |
| Test framework JAR            | cobol-check                | `0.2.16` (pre-release)   | Downloaded by `make init` to `tests/cobol-check/lib/`          |

One-time install (Ubuntu 24.04 Noble):

```bash
sudo apt-get install -y gnucobol3 libcob4-dev openjdk-21-jdk-headless make gcc
make init   # downloads cobol-check-0.2.16.jar (~270 KB)
```

The cobol-check JAR is fetched once into `tests/cobol-check/lib/`, which
is excluded from version control by `.gitignore`. The download URL is
mirrored from the upstream `0.2.16_release` tag of the
`openmainframeproject/cobol-check` GitHub repository.

No environment variables, secrets, or external network services are
required at test runtime once `make init` has succeeded.

---

## Quick Start

The most common commands, in the typical order of use during a working
session:

```bash
make test                         # Run all 28 testsuites
make test-one PROGRAM=CSUTLDTC    # Run a single program's testsuite
make coverage                     # Generate coverage report
make lint                         # Run validation gates
make fixtures                     # Regenerate cobol-snippets from app/data/ASCII/*.txt
make clean                        # Remove target/ and generated fixtures
make distclean                    # Like clean plus remove the downloaded JAR
make help                         # Print target reference
```

`make` (with no arguments) runs `lint`, `test`, and `coverage` in
sequence and is the canonical "did everything pass?" check.

---

## Repository Layout

```text
tests/
├── README.md                              # This file
├── cobol-check/                           # cobol-check testsuites (28 files) and config
│   ├── CSUTLDTC.cut                       # Reference exemplar — canonical pattern
│   ├── CBSTM03B.cut                       # I/O dispatcher tests
│   ├── CBACT01C.cut, CBACT02C.cut, ...    # Batch program tests (7 files)
│   ├── COSGN00C.cut, COMEN01C.cut, ...    # CICS program tests (17 files)
│   ├── config.properties                  # cobol-check runtime configuration
│   └── lib/cobol-check-0.2.16.jar         # Test runner (downloaded; .gitignore excludes)
├── stubs/                                 # Hand-authored infrastructure stubs (no business logic)
│   ├── CEEDAYS.cbl                        # Lillian-day conversion stub
│   ├── CEE3ABD.cbl                        # LE abend stub (sets flag, GOBACK)
│   └── DFHEI1.cbl                         # CICS API link-time fallback
├── fixtures/                              # Test data fixtures
│   ├── load_fixture.py                    # Byte-extraction helper (stdlib only)
│   └── cobol-snippets/                    # Generated COBOL snippet fixtures
│       ├── ACCT-FIXTURE-001.cpy
│       ├── CARD-FIXTURE-001.cpy
│       ├── CUST-FIXTURE-001.cpy
│       ├── XREF-FIXTURE-001.cpy
│       ├── DALYTRAN-FIXTURE-001.cpy
│       ├── DISCGRP-FIXTURE-A.cpy
│       ├── DISCGRP-FIXTURE-DEFAULT.cpy
│       ├── TCATBAL-FIXTURE-001.cpy
│       ├── TRANCATG-FIXTURE.cpy
│       └── TRANTYPE-FIXTURE.cpy
└── lint/                                  # Validation gate scripts
    ├── check_no_business_logic.sh         # Forbids COMPUTE/MULTIPLY/etc outside BEFORE-EACH
    ├── check_no_production_redeclaration.sh # Forbids IDENTIFICATION DIVISION in .cut
    ├── check_assertion_density.sh         # ≥1 EXPECT per testcase; ≥1 VERIFY for mocked tests
    ├── check_isolation.sh                 # Every testsuite must declare BEFORE-EACH
    └── parse_gcov_summary.sh              # Aggregates gcov + enforces thresholds
```

Build artifacts (created by `make` targets, never committed) live under
`target/cobol-check/` (merged sources, compiled binaries, gcov data)
and `target/coverage/` (the per-program coverage summary). Both
directories are excluded by `.gitignore`.

---

## How to Run

### Running all tests

```bash
make test
```

Internally invokes:

```bash
timeout 60 java -jar tests/cobol-check/lib/cobol-check-0.2.16.jar \
    --config-file tests/cobol-check/config.properties \
    --programs all \
    --tests tests/cobol-check
```

The runner iterates every program-under-test referenced by a `.cut`
file in the configured suite directory, compiles each merged binary
once, and executes only the testcases declared in the suite. Results
are streamed to stdout in TAP-like format, and a non-zero exit code is
returned if any testcase fails or any compilation fails.

### Running a single testsuite

```bash
make test-one PROGRAM=CSUTLDTC
```

Internally:

```bash
timeout 30 java -jar tests/cobol-check/lib/cobol-check-0.2.16.jar \
    --config-file tests/cobol-check/config.properties \
    --programs CSUTLDTC \
    --tests tests/cobol-check/CSUTLDTC.cut
```

Useful while iterating on a single program's testsuite — the wall-clock
budget per testsuite is 5 seconds (enforced by the outer `timeout`),
which is also the per-suite ceiling enforced by `make test`.

### Debug mode

```bash
make test-debug PROGRAM=CSUTLDTC TESTCASE='MAPS FC-INVALID-DATE'
```

Adds `-Xdebug -Dcobolcheck.log.level=DEBUG` to the JVM and retains the
merged source artifact at `target/cobol-check/CSUTLDTC.merged.cbl`. The
merged file shows the production source with test code injected beside
it — invaluable for understanding why a testcase failed because it
makes the actual line numbers reported by GnuCOBOL diagnostics
correspond to the visible source.

### CI command surface

The CI workflow at [`.github/workflows/test.yml`](../.github/workflows/test.yml)
runs:

```bash
make lint
make test
make coverage
```

in that order. Lint failures abort the workflow before tests run, so
the validation gates always have the first word. The workflow uploads
`target/coverage/coverage-summary.txt` as a build artifact named
`cobol-check-coverage` for post-mortem inspection.

---

## How to Author a New Testsuite

The four user-mandated authoring rules are non-negotiable and binding
on every contributor:

1. **Import production code, never copy it.**
2. **Mock or stub ONLY external dependencies.**
3. **Test structure must contain only:** imports, test setup/fixtures,
   function invocation, and assertions.
4. **Do not recreate algorithms** — no `COMPUTE`/`MULTIPLY`/`DIVIDE`/
   `ADD`/`SUBTRACT` outside `BEFORE-EACH`/`AFTER-EACH`.

The procedure to add a new testsuite:

1. Choose the production program from `app/cbl/` (e.g., `CSUTLDTC.cbl`).
2. Create `tests/cobol-check/<PROGRAM-ID>.cut` with exact case-matching
   to the production filename (drop the `.cbl`/`.CBL` extension).
3. Copy the structural conventions from the canonical pattern
   `tests/cobol-check/CSUTLDTC.cut`:
   - Apache 2.0 license header (HTML comment in markdown, COBOL comment
     in `.cut`).
   - `TESTSUITE 'PROGRAM-ID description'` declaration.
   - `BEFORE-EACH`/`END-BEFORE` block that `INITIALIZE`s working storage
     and resets `RETURN-CODE`, `WS-ABEND-FLAG`, `END-OF-FILE`.
   - Per-testcase `MOCK FILE`/`MOCK CALL`/`MOCK CICS` blocks BEFORE the
     `TESTCASE` they apply to.
   - `TESTCASE 'feature description'` blocks containing only
     `MOVE`/`SET` setup, `PERFORM`/`CALL` invocation, and
     `EXPECT`/`VERIFY` assertions.
   - `EXPECT` clauses alphabetized by field name when multiple are
     present in one testcase.
   - `VERIFY` clauses follow `EXPECT` clauses.
4. Declare COPY directives for any record-layout copybooks the program
   uses. The cobol-check copybook search path is configured in
   `tests/cobol-check/config.properties` to resolve against
   `app/cpy:app/cpy-bms:tests/fixtures/cobol-snippets:tests/stubs`.
5. Run the new testsuite locally:
   ```bash
   make test-one PROGRAM=<PROGRAM-ID>
   ```
6. Run the lint gates:
   ```bash
   make lint
   ```
7. Run coverage:
   ```bash
   make coverage
   ```
8. Commit and push. CI re-runs the same three commands.

### Permitted constructs

The complete enumeration of every COBOL construct allowed in a `.cut`
file:

- `TESTSUITE 'description' ... END-TESTSUITE`
- `BEFORE-EACH ... END-BEFORE`
- `AFTER-EACH ... END-AFTER`
- `TESTCASE 'description' ... END-TESTCASE`
- `MOCK FILE <fd-name> ON <verb> STATUS '<two-byte>' END-MOCK`
- `MOCK CALL "<program-name>" END-MOCK`
- `MOCK CICS <verb> <discriminator> END-MOCK`
- `MOVE <source> TO <target>`, `SET <name> TO <value>`
- `PERFORM <production-paragraph>` (in-program testing)
- `CALL "<production-program>" USING <args>` (subprogram testing)
- `EXPECT <field> TO BE <value>`
- `VERIFY <mock-target> WAS CALLED <n> TIMES`
- `COPY <copybook>` from `app/cpy/`, `app/cpy-bms/`,
  `tests/fixtures/cobol-snippets/`, or `tests/stubs/`
- COBOL comments (`*` in column 7)

### Forbidden constructs

The complete enumeration of every COBOL construct that fails review:

- `IDENTIFICATION DIVISION` for any program in `app/cbl/`
- `PROGRAM-ID` for any program in `app/cbl/`
- `COMPUTE`, `MULTIPLY`, `DIVIDE`, `ADD`, `SUBTRACT` outside
  `BEFORE-EACH`/`AFTER-EACH`
- `MOCK PARAGRAPH` or `MOCK SECTION` against any paragraph in the
  program-under-test

---

## Coverage Output

### Running coverage

```bash
make coverage
```

This pipeline:

1. Recompiles each program-under-test with `--coverage -O0` (GnuCOBOL
   gcov instrumentation; `-O0` is required so `gcov` can attribute
   lines correctly).
2. Runs `make test` against the instrumented binaries.
3. Runs `gcov -b -c` against every emitted `*.gcda` file to extract
   per-line and per-branch counters.
4. Aggregates results via `tests/lint/parse_gcov_summary.sh` against
   the per-program and per-category thresholds. The script exits
   non-zero if any threshold is missed, which fails the build.

### Coverage targets

| Category                                       | Target  | Programs                                       |
| ---------------------------------------------- | ------- | ---------------------------------------------- |
| Overall (line coverage)                        | ≥70%    | All 28 programs                                |
| Business logic (1xxx-8xxx paragraphs)          | ≥80%    | Numbered paragraphs across all programs        |
| Data validation (EVALUATE / 88-level branches) | ≥90%    | `CSUTLDPY`, `CSUTLDTC`, `1xxx-VALIDATE-*`      |
| File I/O (READ/WRITE/REWRITE/STARTBR)          | ≥70%    | Every file-touching paragraph                  |

Per-program targets:

| Program                                  | Line Coverage Target | Notes                                                       |
| ---------------------------------------- | -------------------- | ----------------------------------------------------------- |
| `CSUTLDTC`                               | 100%                 | All 9 feedback-code branches + default                      |
| `CBSTM03B`                               | 100%                 | 6 ops × 4 DDs matrix + per-cell error variants              |
| `CBACT01C`-`CBACT03C`, `CBCUS01C`        | ≥75%                 | Open / read / EOF / display / close                         |
| `CBACT04C`                               | ≥80%                 | Includes DISCGRP `'23'` fallback branch                     |
| `CBSTM03A`                               | ≥70%                 | Aggregation + report formatting                             |
| `CBTRN01C`-`CBTRN03C`                    | ≥80%                 | Reject codes 100/101/102/103, date filtering                |
| All `CO*C` CICS programs                 | ≥75%                 | Happy path + map-validation failure                         |

### Reading the report

The summary file is at `target/coverage/coverage-summary.txt`. The
format is one line per program plus an `[OVERALL]` summary line:

```text
[CSUTLDTC]  Lines executed: 100% (target 100%) — PASS
[CBSTM03B]  Lines executed: 96.4% (target 100%) — FAIL: missing 'M03B-REWRITE on ACCTFILE'
...
[OVERALL]   Lines executed: 78.3% (target 70%) — PASS
```

The build fails if any target line ends in `FAIL`. Per-program details
(per-paragraph and per-branch counters) are kept under
`target/coverage/<PROGRAM>.gcov` for offline inspection.

---

## Validation Gates

> "Flag any test file where business logic appears outside of imports,
> setup, invocation, or assertions. A test that computes expected
> values by reimplementing production algorithms instead of calling
> the production function fails review."

The four shell scripts in `tests/lint/` enforce this validation gate.
They are the canonical pass/fail criterion for test work and run on
every CI invocation before any test executes.

### `check_no_business_logic.sh`

- **Rule**: No `COMPUTE`/`MULTIPLY`/`DIVIDE`/`ADD`/`SUBTRACT` may appear
  outside `BEFORE-EACH`/`AFTER-EACH` blocks in any `.cut` file. No
  `MOCK PARAGRAPH` or `MOCK SECTION` directive may target a paragraph
  in the program-under-test.
- **Rationale**: The user's prompt forbids re-implementing production
  algorithms in test files. A test that recomputes
  `(TRAN-CAT-BAL * DIS-INT-RATE) / 1200` is no longer a test of the
  production code — it is a test of the test author's transcription.
- **Failure message**: `BLITZY VALIDATION GATE FAILED: business logic
  detected outside imports/setup/invocation/assertions in <file>:<line>.
  Re-author the test to PERFORM the production paragraph instead of
  computing the expected value yourself.`

### `check_no_production_redeclaration.sh`

- **Rule**: No `.cut` file may contain `IDENTIFICATION DIVISION` or
  `PROGRAM-ID` matching any name in `app/cbl/`.
- **Rationale**: The user's prompt requires importing production code,
  not redeclaring it. cobol-check resolves the program-under-test from
  `cobolcheck.application.source.directory` in `config.properties`; a
  redeclaration in the test file means the contributor copied the
  production source into the test instead of importing it.
- **Failure message**: `BLITZY VALIDATION GATE FAILED: production-code
  redeclaration detected in <file>:<line>. Configure
  cobolcheck.test.program.name in config.properties to import the
  production source.`

### `check_assertion_density.sh`

- **Rule**: Every `TESTCASE` must contain at least one `EXPECT` clause.
  Every `TESTCASE` containing a `MOCK FILE`/`MOCK CALL`/`MOCK CICS`
  directive must additionally contain at least one `VERIFY` clause.
- **Rationale**: A test without `EXPECT` is a no-op masquerading as a
  test. A mocked test without `VERIFY` cannot prove the production code
  reached the mock — the mock could have been silently bypassed and
  the test would still "pass".
- **Failure message**: `BLITZY VALIDATION GATE FAILED: testcase
  '<name>' in <file>:<line> has no EXPECT (or has MOCK without VERIFY).`

### `check_isolation.sh`

- **Rule**: Every `.cut` file must declare a `BEFORE-EACH`/`END-BEFORE`
  block that resets shared state.
- **Rationale**: Test independence — no testcase may depend on prior
  testcase state. A missing `BEFORE-EACH` means leftover working-storage
  values from a prior testcase can flow into the next, producing
  flaky results that pass in isolation but fail when run as a suite.
- **Failure message**: `BLITZY VALIDATION GATE FAILED: testsuite
  <file> is missing BEFORE-EACH (test isolation rule).`

### How the gates run

- **Locally**: `make lint`
- **In CI**: the workflow's first step is `make lint`; if it exits
  non-zero the workflow aborts before tests run.
- **Pre-commit (optional)**: you may copy `make lint` into a Git
  pre-commit hook for instant feedback during authoring.

---

## Fixture Catalog

The repository's `app/data/ASCII/` folder supplies byte-exact record
images that production VSAM datasets would deliver. These files are
*never* modified — they are the canonical record-layout examples and
are repurposed verbatim as fixture inputs.

| Fixture                          | Layout                                              | Consumer testsuites                                                  |
| -------------------------------- | --------------------------------------------------- | -------------------------------------------------------------------- |
| `app/data/ASCII/acctdata.txt`    | 50 records × 300 bytes (CVACT01Y)                   | `CBACT01C`, `CBACT04C`, `CBTRN02C`, `COACTVWC`, `COACTUPC`, `COBIL00C` |
| `app/data/ASCII/carddata.txt`    | 50 records × 150 bytes (CVACT02Y)                   | `CBACT02C`, `COCRDLIC`, `COCRDSLC`, `COCRDUPC`                       |
| `app/data/ASCII/custdata.txt`    | 50 records × 500 bytes (CVCUS01Y)                   | `CBCUS01C`, `CBSTM03A`, `COACTVWC`, `COACTUPC`                       |
| `app/data/ASCII/cardxref.txt`    | 50 records × 36 bytes (CVACT03Y)                    | `CBACT03C`, `CBTRN02C`, `CBTRN03C`                                   |
| `app/data/ASCII/dailytran.txt`   | 300 records × 350 bytes (CVTRA06Y)                  | `CBTRN01C`, `CBTRN02C`                                               |
| `app/data/ASCII/discgrp.txt`     | 51 records, 3 blocks (A, DEFAULT, ZEROAPR) (CVTRA02Y) | `CBACT04C`                                                         |
| `app/data/ASCII/tcatbal.txt`     | 50 records × 50 bytes (CVTRA01Y)                    | `CBACT04C`, `CBTRN02C`                                               |
| `app/data/ASCII/trancatg.txt`    | 18 records × 60 bytes (CVTRA04Y)                    | `CBTRN03C`                                                           |
| `app/data/ASCII/trantype.txt`    | 7 records × 60 bytes (CVTRA03Y)                     | `CBTRN03C`, `COTRN02C`                                               |

Production fixture files are never modified. The
`tests/fixtures/load_fixture.py` helper reads byte ranges from each
`.txt` file and emits corresponding `tests/fixtures/cobol-snippets/<NAME>.cpy`
files containing nothing but `MOVE x'…' TO …` statements. These
snippet `.cpy` files are then included in `.cut` testsuites via
`COPY` directives so that production `READ` statements encounter
byte-identical bits to what production VSAM would deliver. The helper
performs no domain logic — it is a pure byte-extraction utility, kept
under 80 lines, that uses only the Python standard library.

To add a new fixture row, edit the `FIXTURES` list inside
`tests/fixtures/load_fixture.py` and re-run `make fixtures`. The
`.cpy` outputs are regenerated deterministically each time, so no
manual hand-editing of snippet files is permitted.

---

## Stub Subprograms

The three files under `tests/stubs/` are link-time shims that allow
the production COBOL source to compile and run off-platform. They
contain **zero business logic** — they exist purely to satisfy
linkage requirements that would normally be met by IBM Language
Environment or by the CICS API.

### `tests/stubs/CEEDAYS.cbl`

- **Replaces**: IBM Language Environment service `CEEDAYS` (Lillian-day
  conversion).
- **Where called**: `app/cbl/CSUTLDTC.cbl` paragraph `A000-MAIN`.
- **Behavior**: Returns a feedback code controlled by an external
  `WS-CEEDAYS-RC-OVERRIDE` field. Provides a linkage-compatible
  signature with the production CALL site so cobol-check's
  `MOCK CALL "CEEDAYS"` directive can drive each branch of the
  production `EVALUATE TRUE` block deterministically.

### `tests/stubs/CEE3ABD.cbl`

- **Replaces**: IBM Language Environment service `CEE3ABD` (program
  abend).
- **Where called**: every `9999-ABEND-PROGRAM` paragraph in batch
  programs.
- **Behavior**: Sets `WS-ABEND-FLAG = 'Y'` on a shared work-area
  copybook and `GOBACK`s instead of terminating the test process.
  Without this stub, the first abend pathway exercised by a test
  would tear down the JVM and abort the entire suite. With it, the
  test asserts on `WS-ABEND-FLAG` to confirm the abend pathway was
  reached without corrupting subsequent testcases.

### `tests/stubs/DFHEI1.cbl`

- **Replaces**: CICS API entry point.
- **Where called**: every `EXEC CICS` verb in CICS programs
  (transitively via the EXEC CICS expansion that the COBOL preprocessor
  injects at compile time).
- **Behavior**: Returns `EIBRESP=27` (NOTAUTH) by default. This is a
  **link-time fallback** — testsuites that need specific CICS behavior
  must declare `MOCK CICS <verb>` blocks; the stub only catches
  commands the testsuite forgot to mock and fails loudly so the omission
  is obvious.

---

## Mocking Cookbook

Practical reference snippets for the most common mock patterns.
Each pattern shows a minimal `.cut` excerpt; copy and adapt as
needed.

### Mocking a VSAM file READ (happy path, status '00')

```cobol
       MOCK FILE ACCTFILE-FILE ON READ
            STATUS '00'
            COPY ACCT-FIXTURE-001
       END-MOCK
```

### Mocking EOF on sequential READ

```cobol
       MOCK FILE ACCTFILE-FILE ON READ
            STATUS '10'
       END-MOCK
```

### Mocking file-not-found on OPEN

```cobol
       MOCK FILE ACCTFILE-FILE ON OPEN
            STATUS '35'
       END-MOCK
```

### Mocking a CICS READ

```cobol
       MOCK CICS READ FILE('ACCTDAT')
            RIDFLD(WS-ACCT-ID)
            INTO(ACCOUNT-RECORD)
            RESP(EIBRESP)
            COPY ACCT-FIXTURE-001
       END-MOCK
```

### Mocking a CICS RECEIVE MAP

```cobol
       MOCK CICS RECEIVE MAP('CSGN00B')
            INTO(SIGN-INPUT-MAP)
            MOVE 'ADMIN001' TO USER-IDI OF SIGN-INPUT-MAP
            MOVE 'PASSWORD' TO PASSWDI OF SIGN-INPUT-MAP
       END-MOCK
```

### Mocking a CALL to a collaborator subprogram

```cobol
       MOCK CALL "CBSTM03B"
            MOVE '00' TO LK-M03B-RC
            COPY ACCT-FIXTURE-001
       END-MOCK
```

### Mocking CEEDAYS to drive a specific feedback code

Production `app/cbl/CSUTLDTC.cbl` (lines 60-69) declares the LE feedback
token as `02 FEEDBACK-TOKEN-VALUE PIC X(8)` with 88-level conditions
matching 8-byte hex values such as `X'000309CB59C3C5C5'`
(FC-INSUFFICIENT-DATA). The mock therefore moves an **8-byte hex
literal** into the token:

```cobol
       MOCK CALL "CEEDAYS"
            MOVE X'000309CB59C3C5C5' TO FEEDBACK-TOKEN-VALUE
                 *> drives EVALUATE branch for FC-INSUFFICIENT-DATA
       END-MOCK
```

When the test program-under-test is `CSUTLDTC` itself, the field name
`FEEDBACK-TOKEN-VALUE` resolves directly. When the test exercises a
different program that uses the linked CEEDAYS stub at
`tests/stubs/CEEDAYS.cbl`, set `WS-CEEDAYS-RC-OVERRIDE` (declared in
`tests/stubs/STUB-CEEDAYS-RC.cpy`, also `PIC X(8)`) in `BEFORE-EACH`
instead — the stub copies that 8-byte token into the FEEDBACK-CODE it
returns to the caller.

A literal **decimal** numeric move (e.g., `MOVE 2507 TO FEEDBACK-CODE`)
will NOT produce a valid 8-byte token comparable to the production
88-level VALUE clauses; the comparison will silently fail and the
default `WHEN OTHER` branch (`'Date is invalid'`) will fire, masking
the test intent.

### Verifying a mock was invoked the expected number of times

```cobol
       VERIFY MOCK FILE ACCTFILE-FILE ON READ WAS CALLED 3 TIMES
       VERIFY MOCK CICS XCTL WAS CALLED 1 TIMES
```

---

## Troubleshooting

### "make init: failed to download cobol-check"

The bootstrap fetches `cobol-check-0.2.16.zip` from
`https://raw.githubusercontent.com/openmainframeproject/cobol-check/0.2.16_release/build/distributions/`.
If your environment blocks GitHub raw content, manually download the
ZIP from the Open Mainframe Project's GitHub releases at
`https://github.com/openmainframeproject/cobol-check/releases/`,
extract `bin/cobol-check-0.2.16.jar`, and place it at
`tests/cobol-check/lib/cobol-check-0.2.16.jar`. Subsequent `make`
invocations will detect the JAR and skip the bootstrap.

### "cobc: command not found"

Run `sudo apt-get install -y gnucobol3 libcob4-dev` (Ubuntu Noble).
Confirm with `cobc --version`, which should report `3.1.2.0`.

### "gcov: cannot open notes file"

Coverage requires `--coverage` instrumentation, which produces `.gcno`
notes files at compile time. Run `make clean && make coverage` to
ensure binaries are recompiled with instrumentation; the `clean` step
is necessary because plain `make test` builds without the flag.

### "VALIDATION GATE FAILED: business logic detected"

Open the offending `.cut` file. The lint script reports the line
number. Replace the forbidden arithmetic with either:

- a literal expected value chosen from external knowledge (a fixture
  row, a user-provided number, a documented requirement), or
- a `PERFORM` of the production paragraph that is supposed to compute
  the value, followed by an `EXPECT` against the field the production
  code wrote into.

Never put `COMPUTE`/`MULTIPLY`/`DIVIDE`/`ADD`/`SUBTRACT` in a
`TESTCASE`. They belong only in `BEFORE-EACH`/`AFTER-EACH` for fixture
preparation (e.g., zero-initializing a counter before the testcase
runs).

### "VALIDATION GATE FAILED: production-code redeclaration"

The `.cut` file contains an `IDENTIFICATION DIVISION` or `PROGRAM-ID`
matching a name in `app/cbl/`. Remove it. The program-under-test must
be referenced via `cobolcheck.test.program.name` in `config.properties`
(or via the `-p PROGRAMNAME` command-line argument). cobol-check
resolves and merges the production source for you; redeclaring the
program in the test file is both unnecessary and a violation of the
"import production code" rule.

### "Test passes locally but fails in CI"

Common causes:

- **Fixture snippets out of sync** — run `make fixtures` before
  committing. The `.cpy` snippet files are regenerated from the
  source `.txt` files; if they drift, the local cache lags behind.
- **Coverage threshold drift** — run `make coverage` locally and
  inspect `target/coverage/coverage-summary.txt`.
- **Locale differences in DISPLAY output** — testsuites should EXPECT
  against PIC-formatted fields, not against terminal-rendered output
  that may vary with the runner's locale.
- **Forgotten `make init`** — the cobol-check JAR is cached locally;
  CI bootstraps fresh on every run, so a JAR-version mismatch can
  surface only in CI.

### "ABEND from CEE3ABD during a test"

The stub `tests/stubs/CEE3ABD.cbl` should set `WS-ABEND-FLAG='Y'` and
`GOBACK`. If your test process terminates instead, the stub did not
link in. Confirm that `tests/stubs/` is on the
`cobolcheck.application.copybook.directory` path in `config.properties`,
and that `make test` shows the stub being compiled in its log output.

### "WRN001: No test suite directory for program <X> was found"

cobol-check 0.2.16 walks the configured `test.suite.directory`
looking for a *sub-directory* whose name matches the program-id (i.e.,
it expects a nested layout `tests/cobol-check/<PROGRAM>/<file>.cut`).
The CardDemo project, per AAP §0.5.1 / §0.10.2 / §0.9.1, uses the
**flat layout** `tests/cobol-check/<PROGRAM>.cut` instead — and the
`Makefile` bridges the two by staging an ephemeral nested-layout
shadow tree at `target/cobol-check/suites/<PROGRAM>/<PROGRAM>.cut`
before invoking cobol-check, then pointing
`test.suite.directory` at that shadow via a runtime-generated
`target/cobol-check/config.properties`. If you see WRN001 it means
**you are running cobol-check directly with the canonical
`tests/cobol-check/config.properties`** (which still names
`tests/cobol-check` as `test.suite.directory`); use one of:

- `make test`, `make test-one`, `make test-debug`, or `make coverage`
  — all of these stage the shadow before invoking cobol-check.
- A direct `java -jar cobol-check.jar` invocation that supplies its
  own pre-staged nested layout under `tests/cobol-check/<PROGRAM>/`
  *and* uses the canonical config — the shadow staging is then
  redundant but harmless.

If the warning persists from the Make targets, confirm:

- The .cut file's name matches the program-id exactly (e.g.,
  `tests/cobol-check/CSUTLDTC.cut` for `app/cbl/CSUTLDTC.cbl`); the
  match is case-sensitive on Linux.
- The file extension is lowercase `.cut` (the auto-discovery glob in
  the Makefile is `*.cut`, not `*.CUT`).
- The file is non-empty.

### "Mock <CALL> <\"X\"> does not reference any construct in the source code"

cobol-check could not find a matching `CALL "X"` in the
program-under-test. Either you mocked the wrong subprogram name or
the production source's `CALL` statement spans multiple lines and has
not been recognized; consult the parser error log at
`target/cobol-check/ParserErrorLog.txt`.

---

## References

- **cobol-check**: https://github.com/openmainframeproject/cobol-check
- **GnuCOBOL**: https://gnucobol.sourceforge.io/
- **CardDemo project root**: [`README.md`](../README.md)
- **Contribution workflow**: [`CONTRIBUTING.md`](../CONTRIBUTING.md)
- **CI workflow**: [`.github/workflows/test.yml`](../.github/workflows/test.yml)
- **Build orchestrator**: [`Makefile`](../Makefile)
- **Reference exemplar testsuite**: `tests/cobol-check/CSUTLDTC.cut`
- **Test-user credentials**: `ADMIN001 / PASSWORD` (admin),
  `USER0001 / PASSWORD` (regular) — used in CICS testsuites only.
