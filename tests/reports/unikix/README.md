# UniKix operator evidence

## Purpose

This folder holds the raw evidence of `unikix` operator runs and the validation records that import and revalidation derive from it. This README is the only hand-written file under `tests/reports/`. The folder is committed: `tests/.gitignore` ignores only `reports/runtime/` and Python bytecode.

- **Who creates evidence.** Only an operator run, `CARDDEMO_ADAPTER=unikix pytest tests/ -m unikix` on a host with NTT DATA UniKix TPE and BPE, and only for ids of the suite's closed 601-id scenario manifest. CI never runs the `unikix` adapter and never writes here. Online (e2e) and JCL-semantics scenarios run only on `unikix`, so CI reports them `blocked` until committed evidence from this folder verifies them. Until the first operator run the folder holds only this README.
- **Who writes which file.** `tests/adapters/unikix/evidence.py` (`EvidenceWriter`) writes the evidence, called through the adapter's `write_evidence` in `tests/adapters/unikix/adapter.py`. Only `tests/harness/report.py` writes `validation.yaml`, on import and revalidation; `evidence.py` never does.
- **What ran.** The adapter deploys the UniKix copy of the application, `migrated_app/` in `samples/m2/unikix/UniKix_CardDemo_runtime_v1.zip`, not the `app/` sources (GAP-MIS-008). The zip is extracted once, read-only, into `<work root>/_unikix_zip/`. The test-owned deployment, region and BPE node live in `<work root>/unikix/<execution_id>/`, where `<work root>` is `CARDDEMO_TEST_WORKDIR` or a fresh temporary directory. Inside the repository the adapter writes only this evidence tree. The `pytest` session around it also writes each scenario's run details (elapsed time, diff and detail, kept working directory, tracebacks) to the git-ignored `tests/reports/runtime/<scenario_id>.yaml`. At session end `harness.report.regenerate()` rewrites `tests/reports/coverage-matrix.csv`, `tests/reports/gap-register.csv`, `tests/reports/coverage-report.md` and the `scenario_ids`, `status` and `gap_ids` of `tests/inventory/inventory.yaml`, leaving a file whose content is unchanged untouched; `--carddemo-no-report` skips it. A UniKix run writes no `tests/reports/results/` record.

## Layout

```
tests/reports/unikix/
  README.md
  <execution_id>/
    <scenario_id>/
      result.yaml
      validation.yaml      # added by report.py --import-unikix / --revalidate
      promotion.yaml       # transient: present only while an import promotes a baseline
      screens.txt
      environment.yaml
      datasets/<LOGICAL>.bin
      sysout/
```

Evidence is immutable. `EvidenceWriter` claims a scenario directory by creating it exclusively and creates every file exclusively: a directory that already exists is refused and kept as found. Evidence is never written or read through a symbolic link.

- **`result.yaml`** — the raw record of one scenario run, written last, so its presence means the evidence is complete. It is never overwritten and never edited by hand or by any tool. It holds every key of `harness.results.RECORD_FIELDS`:
  - `scenario_id`, `adapter` (`unikix`) and `execution_id`
  - `status`: passed, failing, blocked or implemented-unexecuted
  - `reason`: assertion, error, timeout, cross-adapter-divergence, adapter-unavailable, missing-prerequisite or stale (null when passed)
  - `gap_id`, for blocked and implemented-unexecuted
  - `baseline_version` and `scenario_digest`
  - `source_identity` = `{tree: unikix-copy, commit, zip_sha256, units}`: the repository commit, the sha256 of `samples/m2/unikix/UniKix_CardDemo_runtime_v1.zip`, and the sha256 of each program, copybook, map, job source and resource list of the UniKix copy that the scenario executed
  - `execution_fingerprint`: the sha256 of everything that affects what was executed — the scenario's `inputs`; its `preconditions`, with each fixture dataset replaced by the sha256 of the bytes actually materialized, plus parameters, faults and sessions; and the adapter name. `expected`, tags, gap ids and inventory ids are outside it. It is written once, here.
  - `decoded`, the normalized projection of the outcome
  - `evidence_path`, this scenario directory

  Besides them it holds `observed` (the run's return code, messages and step screens, which with `datasets/` rebuild the run's result for import and revalidation), `observed_messages` (every `ERRMSG` and `@text` value seen, in order), `screens_raw` (every captured screen, its 24 rows of 80 characters exactly as read) and `capture_summary` (the `datasets/` and `sysout/` file maps, the captured steps and the BPE job numbers). `result.yaml` is raw evidence only: the status rules never read it.
- **`validation.yaml`** — the UniKix record the status rules read. It holds the same record fields: the scenario id, the `baseline_version` and `scenario_digest` it was judged against, the `source_identity` and `execution_fingerprint` copied from `result.yaml`, the status, reason and decoded outcome of that judgement. It counts only beside its raw evidence. Loading it, the status rules check only that a `result.yaml` lies beside it as a regular file, not a symbolic link; they do not read it again, because the import read and checked it. They do read the `environment.yaml` the import read, the scenario directory's, else the execution directory's, which must be a mapping that records the run date and the installation, deployment and version facts. A `validation.yaml` is a `report.py --check` problem and counts nowhere when it is a symbolic link, unreadable, or not a valid `unikix` record of its own execution and scenario; when no regular `result.yaml` lies beside it; or when its `environment.yaml` is missing, unreadable or not such a mapping.
- **`promotion.yaml`** — the journal of a baseline promotion in progress (see [Importing evidence](#importing-evidence)). The import writes it before its first change and removes it after its last.
- **`screens.txt`** — every captured screen for people to read: a `--- step <n> session <s> @map <map> attempt <a> ---` line, with `<n>` counted from 0, then the rows with trailing spaces trimmed; a blank line separates screens. `attempt <a>` appears when the capture records it, as the TPE driver always does: `1`, or `2`, `3`… for the screens of a later attempt, when the steps were driven again after the region's date changed. A run that captured no screen leaves the single line `(no screen was captured)`. It is never compared, and neither is `screens_raw`, which keeps the rows as read. Screens are compared only through `observed.screens`, the screens of the last attempt: per step, the fields `expected.screens` names, `@map` included, and for an unformatted screen (`@map: TEXT`) `@text`, its full 24 rows of 80 characters with every space kept.
- **`datasets/<LOGICAL>.bin`** — the bytes of each dataset and observation the run acquired, unchanged. Business datasets keep their logical names (`ACCTDATA.bin`, `TRANSACT.bin`). In reserved observation names every character outside `[A-Za-z0-9._-]` becomes `_`: `@CATALOG` is `_CATALOG.bin`, `@REGION-FILES` is `_REGION-FILES.bin`, `@TDQ:JOBS` is `_TDQ_JOBS.bin`, `@AIX:<path>` is `_AIX_<path>.bin` and `@AFTER:<JOB>:<LOGICAL>` is `_AFTER_<JOB>_<LOGICAL>.bin`. `capture_summary.datasets` maps each file back to its name.
- **`sysout/`** — the BPE job output (job and step return codes, program `DISPLAY` lines), linked to each job by its BPE job number in `capture_summary.job_numbers`; the generated JCL; the scenario's command log `adapter/commands.log`; and the build-log excerpts `adapter/build/<member>.log` of the programs and maps it executed. The first scenario of a session also carries the session setup's output under `session/`. `capture_summary.sysout` maps each path back to its name.
- **`environment.yaml`** — the facts a record is checked against on import:
  - the installation paths, as the operator's environment file sets them. The zip's `bin/carddemo.env` sets `UNIKIX=/opt/software/TPE`, `INSTEBM=/opt/software/BPE/INSTEBM`, `VCOBOL=/opt/software/COBOL`, `KIXLICDIR=/opt/unikix`, `JAVA_HOME` (derived from `/etc/alternatives/java`) and `TPE_AGENT=/opt/software/NTT_DATA_TPE_Agent`
  - the deployment paths under the test-owned deployment root, the environment file with its sha256, and the availability of every `commands.yaml` entry
  - the output of the version probes `version_tpe`, `version_bpe`, `version_vcobol`, `version_licence`, `version_java` and `version_s3270`. These versions are unpinned and first become known here (GAP-MIS-009); a failed probe is recorded as its failure text, and a probe that exits 0 and prints nothing, or only whitespace, as `no output: <entry id> exited 0 and printed nothing` (or `only whitespace`). Neither names a version, so GAP-MIS-009 stays open on it while the record stays importable
  - the `s3270` path and the listener address
  - `run_date`: `bpe_job_start_date` and `first_step_screen_date`, the run date that relative-clock matchers are judged against, each null when the scenario showed none. No confirmed UniKix command sets the clock (GAP-MIS-015)

## Execution id

An execution id has the form `unikix-<UTC yyyymmddThhmmssZ>-<first 8 hex of sha256(hostname)>`, made by `evidence.make_execution_id` once per adapter session, so one `pytest` session writes one execution directory. An illustrative id: `unikix-20270115T093000Z-1a2b3c4d`. `report.py` accepts only this form.

- **Never identical.** Two UniKix runs never produce identical evidence. The tree is therefore excluded from the two-run determinism comparison, which covers only `tests/reports/coverage-matrix.csv`, `tests/reports/gap-register.csv`, `tests/reports/coverage-report.md` and `tests/reports/results/*.yaml`. Two executions are compared through their normalized projections instead ([Comparing two executions](#comparing-two-executions)).
- **No elapsed times.** They go only to the git-ignored `tests/reports/runtime/`.
- **Not gnucobol-batch.** A `gnucobol-batch` execution id is `gnucobol-batch-` and 16 hex digits, and its records live in `tests/reports/results/<scenario_id>.yaml`, never here.

## Producing evidence

On the operator host, set `CARDDEMO_UNIKIX_ENV` to a file in the format of the zip's `bin/carddemo.env` and `CARDDEMO_TN3270_ADDR` to `127.0.0.1:<port>` (loopback only), then run:

```
CARDDEMO_ADAPTER=unikix pytest tests/ -m unikix
```

The run also executes the job scenarios both adapters can run, which provides the cross-adapter comparison. Every UniKix command the adapter issues comes from `tests/adapters/unikix/commands.yaml`.

- **Once the session has started** (environment loaded, application deployed, region and BPE started), every scenario that reaches the adapter's `prepare` gets its directory, because `prepare` creates its capture. A scenario that `prepare` blocks because a command, observation or clock it needs is unavailable, or leaves `implemented-unexecuted` because a fault or dataset its preconditions need cannot be provided, still gets a `result.yaml` naming the gap. So does a job with a `DFHCSDUP` step, such as `CFG-CBADMCDJ-normal-01`, which the adapter's `refuse` blocks under GAP-MIS-016 just before the observation rule: `refuse` opens its capture as `prepare` would have, so its evidence does not depend on where it runs. Any other scenario the runner settles before `prepare` gets no directory: for example one its observation rule blocks for an observation the session cannot produce, or one whose fixtures cannot be built.
- **When the session cannot start,** every `unikix` scenario that reaches `prepare` ends with that setup failure — `blocked` under its gap when the environment or a command is unavailable, `failing` with reason `error` otherwise — and nothing is written here.
- **Nothing counts yet.** Evidence counts toward coverage only after the import has written its `validation.yaml`.

[`tests/README.md`](../../README.md) gives the full operator procedure: host preparation, the environment file, confirming commands and the listener setup.

## Importing evidence

```
python tests/harness/report.py --import-unikix <execution_id>
```

Imports and revalidations hold an exclusive lock on `tests/`, so they run one at a time. The import does the following, in order.

1. **Read.** It reads every scenario directory of `tests/reports/unikix/<execution_id>/`. `result.yaml` and the other raw files stay immutable.
2. **Check.** It checks each record's `source_identity` and `environment.yaml`. The identity must name the zip's UniKix copy as committed: tree `unikix-copy`, the zip's sha256, and unit hashes equal to the zip's members, covering every unit the scenario executes. `environment.yaml` must record the run date and the installation, deployment and version facts. A record that fails a check is reported and not imported. A record whose `execution_fingerprint` no longer equals the scenario's current one is skipped as stale. A record not skipped as stale whose scenario fails the schema check of `tests/harness/scenarios.py` (one whose `expected` asserts only `return_code`, for example) is rejected: it is never judged, nothing is promoted for it, and its `validation.yaml` records `failing` with reason `error`, as a live run records such a scenario.
3. **Eligibility.** The register entries GAP-DOC-020 to GAP-DOC-032 list, in `affected_inventory_ids`, every item whose behavior passes through a part of the UniKix copy that differs from `app/`: for example the `COACTUPC` amount edits (GAP-DOC-022), the `CSUTLDPY` date edits (GAP-DOC-024), the `CBSTM03A` JCL and DD display (GAP-DOC-020) and the region actions behind the no-op `SDSF.cbl` (GAP-DOC-031). A record counts toward its scenario's items only when none of those items is in those lists. Otherwise the record is kept and reported in the "UniKix-copy behavior" section of `tests/reports/coverage-report.md`: it never verifies the `app/` item, never replaces its baseline, and the GAP-DOC entry stays open.
   - **Exception:** the 6 UniKix-only `fct.lst` files (`DISCGRP`, `TCATBALF`, `TRANCATG`, `TRANSAIX`, `TRANTYPE`, `TRXFL`; GAP-DOC-030) have no `app/` counterpart. A record whose `source_identity` holds the zip's `fct.lst` hash verifies them, through scenario `CFG-FCT-normal-01`.
4. **Promotion.** Each derived baseline whose scenario ran and whose items are all eligible becomes an observed baseline. Its `manifest.yaml` gets `capture_platform: unikix` with the `execution_id`, `execution_fingerprint` and `source_identity`; the business datasets' bytes are copied from the evidence into its `datasets/*.bin`; and the scenario loses its `inferred-not-observed` tag. The version bump follows the comparison:
   - **Equal decoded outcome:** **MINOR**.
   - **Different decoded outcome:** the observed outcome replaces the expected one, in the baseline and in the scenario, and the version bumps **MAJOR**. A register entry quoting both outcomes is opened: `doc-conflict` when the derived expectation came from the tech spec (`derived_from: tech-spec` in its manifest), `uncertain-purpose` otherwise.

   Each promotion is journalled in `promotion.yaml` beside `result.yaml`. An import that stops part-way leaves the journal, and the next `--import-unikix` of the same execution completes the promotion from it. If the scenario has since come to fail the schema check, the promotion still completes, but the record is not judged: its `validation.yaml` records `failing` with reason `error`, and the record is rejected.
5. **Revalidation without rerun.** It compares the immutable observed payload again, against the baseline as it now stands, through the same decoded comparison as a live run, and writes `validation.yaml` beside `result.yaml`. Promotion changes only tags, versions and expected values, so a pre-promotion failure never remains the verdict. A record with no outcome to compare (blocked, implemented-unexecuted, or failing on timeout) keeps its recorded status, reason and gap.
6. **Inferred-not-observed entries.** A GAP-INF entry closes only when every item in its `affected_inventory_ids` is `verified` by current records. Otherwise the importer narrows `affected_inventory_ids` to the items still unverified, keeps the earlier list under `narrowed_from`, and leaves the entry open.
7. **Unmatched messages.** Each observed `ERRMSG` or `@text` value that matches no inventory item becomes an inventory addition, with its reason in `header.changes`, and raises the total. GAP-UNC-012 lists the additions and stays open until a full `unikix` run reports none.
8. **Register and reports.** It closes GAP-MIS-009 once `environment.yaml` records every prerequisite's version and every entry of `tests/adapters/unikix/commands.yaml` that cites GAP-MIS-009 (the six `version_*` probes) is `status: confirmed`. Otherwise GAP-MIS-009 stays open and the import names the unconfirmed entries, or what keeps it from reading the file; the import never confirms an entry, and importing the execution again once the operator has confirmed them closes GAP-MIS-009. It writes `tests/inventory/gaps.yaml` with the entries it opened, closed or narrowed, and regenerates `tests/reports/*`. An entry it closes names, as its `resolving_evidence`, the UniKix execution id and evidence path that settle it. It closes an entry only once `tests/inventory/inventory.yaml` reflects the closing evidence: it holds the addition of every observed message of that evidence that matches no item (step 7), and records none of the items the evidence verifies as `unreachable`. Otherwise the entry stays open and the import lists what the inventory lacks. Each closing is recorded in the inventory's `header.changes` as `{date, gap_id, reason}` before `gaps.yaml` is written, and an entry whose closing the inventory cannot record stays open. Every other entry closes through the register's own procedure, described at the head of `tests/inventory/gaps.yaml`.

**Currency.** A UniKix record counts only while the `baseline_version` and `scenario_digest` in its `validation.yaml` match the scenario and baseline as they stand and, when it records an `execution_fingerprint`, that fingerprint equals the scenario's current one; when the current fingerprint cannot be computed, no recorded one equals it. Otherwise it is `stale` and counts as `implemented-unexecuted`. A `passed` record always carries a fingerprint: without one it breaks the record contract and is never current. A non-passing record written without one stays current on its version and digest alone, but `--revalidate` never rewrites it.

The import exits 0 when no record was rejected, 1 when any record was rejected, and 2 when it cannot run, as when the execution directory is missing or holds neither a scenario directory nor a symbolic link (`0 scenario records found`); in those two cases nothing is written. Exit 0 does not mean every record was imported: a record skipped as stale, because its `execution_fingerprint` no longer equals the scenario's current one or its evidence lacks an observation the current `expected` names, still leaves it 0. Read the summary's `validated`, `promoted`, `rejected` and `skipped` lines to see what became of each record.

## Revalidating evidence

```
python tests/harness/report.py --revalidate
```

Run it after any later change to a scenario or baseline. It rewrites a `validation.yaml` only when the scenario's current `execution_fingerprint` equals the one recorded in `result.yaml` **and** every observation the current `expected` names is present in the evidence.

- **Assertion or metadata change:** revalidated from the captured observations.
- **Change to anything that affects execution** (fixtures, faults, the AID sequence): the record is left `stale` until a new run, because re-comparing old output cannot show that the new inputs were executed.
- **Pending promotion:** the record is skipped until `--import-unikix` of its execution completes the promotion.
- **No recorded fingerprint:** the record is never revalidated.
- **Scenario failing the schema check:** a record that would otherwise be rewritten is rejected and never judged; its `validation.yaml` records `failing` with reason `error`, as on import.

Revalidation registers each cross-adapter divergence an eligible record now shows, as the import does: its `stub-limitation` or `uncertain-purpose` entry is added, reopened or extended, printed as `gap added …` or `gap updated …`, and no baseline or scenario changes ([Cross-adapter divergence](#cross-adapter-divergence)). It then judges the GAP-INF entries again. It regenerates the reports only when a `validation.yaml`, `gaps.yaml` or the inventory changed. Otherwise it writes nothing at all: when there is no `validation.yaml` to revalidate, or every one was left stale, rejected before it was written, or already held what revalidation would write, and the register is unchanged. It exits 0 when it only leaves records stale, 1 when it rejects evidence, cannot compare a record or refuses one whose scenario fails the schema check, and 2 when it cannot run.

## Comparing two executions

```
python tests/harness/report.py --compare-executions <id1> <id2>
```

It compares each scenario's status and decoded outcome between two executions, taking each scenario's `validation.yaml`, else the record fields of its `result.yaml`. It reports each scenario whose record cannot be read, each scenario present in only one execution, each differing status and each member of the decoded outcome that differs (for example `messages` or `datasets`). Nothing else is compared: not raw bytes, `screens.txt`, `screens_raw` or `observed`, nor the records' other fields, such as `execution_id`, `source_identity` and `execution_fingerprint`. A timestamp in the decoded outcome is normalized only where the projection replaces it: a value the scenario or its baseline expects through a matcher holds the matcher's name (for example `run-timestamp`), and `CURDATE` and `CURTIME` hold `run-date` and `run-time` on every captured screen, or the name of a matcher given for them. Every other timestamp or date is compared as captured. It exits 0 when the two executions agree, 1 when they differ, and 2 when it cannot run.

## Cross-adapter divergence

For a scenario both adapters run, the `gnucobol-batch` baseline stays authoritative. An eligible UniKix outcome that differs from it in decoded comparison makes the scenario `failing` with reason `cross-adapter-divergence`. The import, and any later `--revalidate`, registers it in `tests/inventory/gaps.yaml` as:

- `stub-limitation` when every difference lies within the scope of the GAP-STB entries the scenario cites;
- `uncertain-purpose` otherwise.

An entry of that category that already asks about the scenario's divergence keeps its id: a closed one reopens, and an open one adds the scenario's items it does not yet list. Otherwise a new entry is added. Neither the baseline nor the adapter is changed, and nothing under `app/` is changed.

## Committing evidence

After the import, and after any later revalidation, commit together:

- the evidence directory `tests/reports/unikix/<execution_id>/`, whole, with its `validation.yaml` files
- `tests/adapters/unikix/commands.yaml`, with the entries the operator confirmed
- the scenarios and baselines the import changed
- `tests/inventory/gaps.yaml`, and `tests/inventory/inventory.yaml` when the import added items or an entry closed (its closure record in `header.changes`)
- the regenerated `tests/reports/*`

On the next CI run the report includes the committed evidence. An item leaves `blocked` only on that committed evidence, and only as far as the eligibility rule allows.

- Never edit `result.yaml` or any other raw file.
- Never delete an evidence directory or a register entry; a closed entry reopens on contrary evidence.
- Never commit a scenario directory that still holds `promotion.yaml`: run `--import-unikix` for that execution again first.
- Nothing in this procedure changes `app/`, `samples/`, `diagrams/` or the zip.

## See also

- [`tests/README.md`](../../README.md) — the full operator procedure; `CARDDEMO_UNIKIX_ENV`; `CARDDEMO_TN3270_ADDR`, which must be a loopback address (any other is refused, GAP-MIS-005); sign-on only as `ADMIN001` or `USER0001`.
- [`tests/adapters/unikix/commands.yaml`](../../adapters/unikix/commands.yaml) — every UniKix command the adapter issues. Each entry ships `status: unconfirmed` with the gap it settles (GAP-MIS-003 to GAP-MIS-007, GAP-MIS-009, GAP-MIS-011, GAP-MIS-015 and GAP-MIS-017, and GAP-MIS-008 for the build), and becomes `confirmed` only after the operator has seen it work.
- [`tests/reports/coverage-report.md`](../coverage-report.md) — the "UniKix-dependent items" and "UniKix-copy behavior" sections.
- [`tests/inventory/gaps.yaml`](../../inventory/gaps.yaml) and [`tests/reports/gap-register.csv`](../gap-register.csv) — the gap register.
