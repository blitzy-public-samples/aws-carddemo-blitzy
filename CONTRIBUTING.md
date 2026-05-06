# Contributing Guidelines

Thank you for your interest in contributing to our project. Whether it's a bug report, new feature, correction, or additional
documentation, we greatly value feedback and contributions from our community.

Please read through this document before submitting any issues or pull requests to ensure we have all the necessary
information to effectively respond to your bug report or contribution.


## Reporting Bugs/Feature Requests

We welcome you to use the GitHub issue tracker to report bugs or suggest features.

When filing an issue, please check existing open, or recently closed, issues to make sure somebody else hasn't already
reported the issue. Please try to include as much information as you can. Details like these are incredibly useful:

* A reproducible test case or series of steps
* The version of our code being used
* Any modifications you've made relevant to the bug
* Anything unusual about your environment or deployment


## Contributing via Pull Requests
Contributions via pull requests are much appreciated. Before sending us a pull request, please ensure that:

1. You are working against the latest source on the *main* branch.
2. You check existing open, and recently merged, pull requests to make sure someone else hasn't addressed the problem already.
3. You open an issue to discuss any significant work - we would hate for your time to be wasted.

To send us a pull request, please:

1. Fork the repository.
2. Modify the source; please focus on the specific change you are contributing. If you also reformat all the code, it will be hard for us to focus on your change.
3. Ensure local tests pass.
4. Commit to your fork using clear commit messages.
5. Send us a pull request, answering any default questions in the pull request interface.
6. Pay attention to any automated CI failures reported in the pull request, and stay involved in the conversation.

GitHub provides additional document on [forking a repository](https://help.github.com/articles/fork-a-repo/) and
[creating a pull request](https://help.github.com/articles/creating-a-pull-request/).


## Finding contributions to work on
Looking at the existing issues is a great way to find something to contribute on. As our projects, by default, use the default GitHub issue labels (enhancement/bug/duplicate/help wanted/invalid/question/wontfix), looking at any 'help wanted' issues is a great place to start.


## Code of Conduct
This project has adopted the [Amazon Open Source Code of Conduct](https://aws.github.io/code-of-conduct).
For more information see the [Code of Conduct FAQ](https://aws.github.io/code-of-conduct-faq) or contact
opensource-codeofconduct@amazon.com with any additional questions or comments.


## Security issue notifications
If you discover a potential security issue in this project we ask that you notify AWS/Amazon Security via our [vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/). Please do **not** create a public github issue.


## Licensing

See the [LICENSE](LICENSE) file for our project's licensing. We will ask you to confirm the licensing of your contribution.


## Adding a new testsuite

CardDemo uses [cobol-check](https://github.com/openmainframeproject/cobol-check) (Open Mainframe Project, version 0.2.16) as its automated unit-test framework, and every testsuite lives at `tests/cobol-check/<PROGRAM-ID>.cut`. The canonical pattern is that each testsuite **imports** its program-under-test by setting `cobolcheck.test.program.name` in `tests/cobol-check/config.properties` (or by passing the `-p PROGRAMNAME` CLI flag through `make test-one PROGRAM=<PROGRAM-ID>`); the production source under `app/cbl/` is never modified, copied, or re-declared inside a `.cut` file.

### Prerequisites

Local-machine prerequisites (the same versions are pinned in `.github/workflows/test.yml` for CI):

- `gnucobol3` (3.1.2-5.1ubuntu1) and `libcob4-dev` (3.1.2-5.1ubuntu1) — off-platform COBOL compiler.
- `openjdk-21-jdk-headless` (21.0.10+7-1~24.04) — hosts the cobol-check JAR.
- `make` (4.3-4.1build2) — test orchestration.
- `gcc` (4:13.2.0-7ubuntu1) — provides `gcov` for coverage reporting.
- A one-time `make init` to download `cobol-check-0.2.16.jar` into `tests/cobol-check/lib/`.

```bash
sudo apt-get install -y gnucobol3 libcob4-dev openjdk-21-jdk-headless make gcc
make init
```

### Authoring rules

These four rules are non-negotiable; the lint scripts under `tests/lint/` enforce each one in CI:

1. **Import production code, never copy it.** The testsuite references the production program via `cobolcheck.test.program.name`; never declare an `IDENTIFICATION DIVISION` or `PROGRAM-ID` for any name in `app/cbl/` inside a `.cut` file.
2. **Mock or stub ONLY external dependencies.** Permitted mock targets are listed in `tests/README.md`: VSAM file operations (`MOCK FILE`), CICS commands (`MOCK CICS`), and Language Environment / inter-program calls (`MOCK CALL`). Internal paragraphs of the program-under-test must never appear in `MOCK PARAGRAPH` or `MOCK SECTION`.
3. **Test structure must contain only:** imports (`COPY`), test setup/fixtures (`BEFORE-EACH` / `MOVE` / `SET`), function invocation (`PERFORM <production-paragraph>` or `CALL "<production-program>"`), and assertions (`EXPECT`, `VERIFY`).
4. **Do NOT recreate algorithms.** No `COMPUTE`, `MULTIPLY`, `DIVIDE`, `ADD`, or `SUBTRACT` outside `BEFORE-EACH` / `AFTER-EACH` blocks. Expected values must be literal constants taken from the user's example, byte-exact records from `app/data/ASCII/*.txt`, or deterministic mock-controlled return values.

The validation gate (`make lint`) enforces these rules and fails with the following message:

> BLITZY VALIDATION GATE FAILED: business logic detected outside imports/setup/invocation/assertions in &lt;file&gt;:&lt;line&gt;. Re-author the test to PERFORM the production paragraph instead of computing the expected value yourself.

### Step-by-step

1. Choose the production program to test from `app/cbl/` (for example, `CSUTLDTC.cbl`).
2. Create `tests/cobol-check/<PROGRAM-ID>.cut` (use the same casing as the production filename, drop the `.cbl` / `.CBL` extension).
3. Open `tests/cobol-check/CSUTLDTC.cut` (the canonical reference exemplar) and copy its structural conventions: Apache 2.0 license preamble, `TESTSUITE` banner, `BEFORE-EACH` initializer, `MOCK` blocks before each `TESTCASE`, `EXPECT` clauses alphabetized by field name when multiple are present, and `VERIFY` clauses appended after `EXPECT` for testcases that declare mocks.
4. For every external boundary the program-under-test touches, declare the appropriate mock. **Important**: cobol-check 0.2.16 does NOT implement `MOCK FILE` or `MOCK CICS` directly (see `tests/README.md` § Mock-type table); the canonical patterns used throughout this repository are:
   - VSAM file operations → use the **pre-set-WS pattern** (see `tests/README.md` § Pre-set-WS pattern). In each `TESTCASE`, `MOVE '<status>' TO <fd-name>-STATUS` *before* `PERFORM` of the file-handling paragraph. cobol-check has already commented out every `OPEN`/`READ`/`WRITE`/`REWRITE`/`CLOSE` in the merged source (substitutes `CONTINUE`), so the production `IF <fd>-STATUS = '00'` / `EVALUATE` that follows reads the deterministic value the test set. Reference: `tests/cobol-check/CBACT01C.cut`.
   - CICS commands → use `MOCK CALL 'DFHEI1' CONTINUE END-MOCK`. Off-platform GnuCOBOL comments out every `EXEC CICS` verb in the merged source, and `tests/stubs/DFHEI1.cbl` plus the link-time `DFHAID` / `DFHBMSCA` copybook stubs in `tests/stubs/` provide the compile-time scaffolding. The `MOCK CALL 'DFHEI1'` directive paired with `VERIFY CALL 'DFHEI1' HAPPENED 0 TIMES` (the `check_dfhei1_safety_net.sh` lint gate) guarantees no rogue CICS verb slips past the precompiler. Reference: `tests/cobol-check/COSGN00C.cut`.
   - LE / subprogram calls → `MOCK CALL "<program-name>" END-MOCK` is fully supported (e.g., `MOCK CALL "CEEDAYS"`, `MOCK CALL "CEE3ABD"`, `MOCK CALL "CBSTM03B"`).
5. For every paragraph or feature being tested, write a `TESTCASE 'description' ... END-TESTCASE` block whose body contains only `MOVE` setup, `PERFORM` / `CALL` invocation, and `EXPECT` / `VERIFY` assertions.
6. Run the new suite locally:
   ```bash
   make test-one PROGRAM=<PROGRAM-ID>
   ```
7. Run the lint gates:
   ```bash
   make lint
   ```
8. Run coverage and confirm the per-program target from `tests/README.md` is met:
   ```bash
   make coverage
   ```
9. Commit and push; CI (`.github/workflows/test.yml`) will rerun lint, test, and coverage on every push and pull request. A pull request cannot merge if any lint, test, or coverage gate exits non-zero.

### Where to find more

- `tests/README.md` — full testing guide including coverage targets and the validation-gate semantics.
- `tests/cobol-check/CSUTLDTC.cut` — canonical reference exemplar.
- `tests/cobol-check/config.properties` — runtime configuration consumed by the cobol-check JAR.
- `Makefile` — the canonical command surface (`make init`, `make fixtures`, `make lint`, `make test`, `make test-one PROGRAM=…`, `make coverage`, `make clean`).
