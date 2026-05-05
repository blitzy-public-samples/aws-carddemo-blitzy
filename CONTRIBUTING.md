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


## Adding a new testsuite

CardDemo's automated tests run via the
[Open Mainframe Project's cobol-check](https://github.com/openmainframeproject/cobol-check)
framework.  Follow these steps to add a new testsuite:

1. **Create the program directory**:
   `mkdir -p tests/cobol-check/<PROGRAM>/`.
2. **Create one or more `.cut` files** inside the new directory.
   `<PROGRAM>.cut` is the conventional name for the primary
   testsuite; long suites may be split (for example,
   `COCRDUPC-validation.cut` and `COCRDUPC-rewrite.cut`).
3. **Reference the unmodified production source** by setting
   `cobolcheck.test.program.name = <PROGRAM>` (or by passing
   `make test-one PROGRAM=<PROGRAM>` on the command line).  *Never*
   copy production COBOL into a `.cut` file -- cobol-check merges the
   real source from `app/cbl/` automatically.
4. **Mock only external dependencies**:
   - `MOCK FILE <fd-name>` for every VSAM cluster and sequential
     dataset.
   - `MOCK CALL "CEEDAYS"` / `MOCK CALL "CEE3ABD"` for Language
     Environment services.
   - `MOCK CALL "<other-program>"` for every cross-program subprogram
     CALL.
   - `MOCK CICS <verb>` for every `EXEC CICS` verb.
   - **Never** `MOCK PARAGRAPH` or `MOCK SECTION` against a paragraph
     of the program-under-test -- that would mock internal logic.
5. **Author assertions** with `EXPECT <field> TO BE <value>` against
   real LINKAGE / WORKING-STORAGE / RETURN-CODE / mocked-WRITE-buffer
   fields.  Add a `VERIFY <mock-target> WAS CALLED N TIMES` clause
   whenever the testcase declares a `MOCK` directive.
6. **Reuse fixtures** from `tests/fixtures/cobol-snippets/` via
   `COPY 'ACCT-FIXTURE-001'.` (or similar).  To add a new fixture
   record, edit the `FIXTURES` list in
   `tests/fixtures/load_fixture.py` and re-run `make fixtures`.
7. **Run the validation gates** locally:
   ```bash
   make lint
   make test-one PROGRAM=<PROGRAM>
   ```
8. **Inspect coverage**:
   ```bash
   make coverage
   ```
   The `tests/lint/parse_gcov_summary.sh` script enforces the
   per-program targets documented in `tests/README.md`.

The four validation gates under `tests/lint/` run automatically in
GitHub Actions on every push and pull request.  A pull request cannot
merge if any gate exits non-zero.

See [`tests/README.md`](tests/README.md) for the full authoring guide,
fixture catalog, and troubleshooting tips.


## Code of Conduct
This project has adopted the [Amazon Open Source Code of Conduct](https://aws.github.io/code-of-conduct).
For more information see the [Code of Conduct FAQ](https://aws.github.io/code-of-conduct-faq) or contact
opensource-codeofconduct@amazon.com with any additional questions or comments.


## Security issue notifications
If you discover a potential security issue in this project we ask that you notify AWS/Amazon Security via our [vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/). Please do **not** create a public github issue.


## Licensing

See the [LICENSE](LICENSE) file for our project's licensing. We will ask you to confirm the licensing of your contribution.
