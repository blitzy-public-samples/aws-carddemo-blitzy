# CardDemo characterization suite

This suite pins the current behavior of AWS CardDemo, the application in `app/`, as platform-neutral scenarios with versioned baselines. It is the comparison baseline for a future like-for-like conversion: the converted system runs the same scenarios through its own adapter, and its outcomes are compared with the baselines record by record, field by field. The suite never changes the application. It observes what `app/` does today, doubtful and defective behavior included, and records every open question in a gap register.

The application itself, its demo users and its batch run order are described in the repository's [`README.md`](../README.md).

- [What the suite measures](#what-the-suite-measures)
- [Layout](#layout)
- [Conventions](#conventions)
- [Local setup](#local-setup)
- [Running the suite](#running-the-suite)
- [Environment variables](#environment-variables)
- [Adapters and the conversion baseline](#adapters-and-the-conversion-baseline)
- [Page zero](#page-zero)
- [Continuous integration](#continuous-integration)
- [UniKix prerequisites](#unikix-prerequisites)
- [UniKix operator procedure](#unikix-operator-procedure)
- [Boundaries](#boundaries)
- [Reports, gaps and baselines](#reports-gaps-and-baselines)

## What the suite measures

**Coverage is functional:** the number of `verified` inventory items divided by the frozen inventory total. The inventory, `inventory/inventory.yaml`, was frozen at **684 items**: 155 source items (28 programs, 31 jobs and procs, 12 configuration definitions, 18 transactions, 17 maps, 28 copybooks, 21 data files) and 529 behavior items derived from them (rules, calculations, reports, interfaces, recovery paths and the rest). Code coverage, test counts and pass rates are never reported as coverage, and no code-coverage tool is installed.

"100% may be claimed" only when every item is `verified` **and** the gap register holds no open entry.

### Statuses

Each scenario run ends in one of four statuses, which pytest shows as follows.

| Scenario status | Meaning | pytest shows |
|---|---|---|
| `passed` | the run matched its baseline | a pass |
| `failing` | an assertion failed, the run hit an error or its 300-second deadline, or two adapters diverged | a failure carrying the diff |
| `blocked` | a runtime, a command or an observation the scenario needs is unavailable (`AdapterUnavailable`) | a skip reading `blocked: <reason> [<gap_id>] <scenario_id>: <detail>` |
| `implemented-unexecuted` | the runtime is available, but a dataset, access or fault the preconditions need cannot be provided (`MissingPrerequisite`) | a skip reading `implemented-unexecuted: <reason> [<gap_id>] <scenario_id>: <detail>` |

A scenario the selected adapter cannot run is deselected and gets no new result record in that session. Its status still comes from the records already in `reports/`: its `gnucobol-batch` record in `reports/results/` and its imported, eligible UniKix records (`validation.yaml` under `reports/unikix/`). It is `failing` when any current one fails, otherwise `passed` when any passed, otherwise `blocked` when all are `blocked`, otherwise `implemented-unexecuted`. A scenario with no current, eligible record and no stale one, such as a deselected scenario that never ran, is reported `blocked` with reason `adapter-unavailable`: under GAP-MIS-018 when `gnucobol-batch` can run it, GAP-MIS-008 when its only UniKix records are ineligible, else GAP-MIS-009. A result record counts only while its `baseline_version` and `scenario_digest` match the scenario and baseline as they stand; otherwise it is `stale` and counts as `implemented-unexecuted`. A current `passed` record of a scenario that fails the schema check (`harness.scenarios.validate`; one whose `expected` asserts only `return_code`, for example) counts as `failing` with reason `error`, as a live run records such a scenario, and `report.py --check` names the scenario.

An inventory item takes one of five statuses: `verified`, `failing`, `blocked`, `implemented-unexecuted` or `not-covered`. Its status is the worst over the scenarios that list it, in the order failing > blocked > implemented-unexecuted > verified. An item that no scenario lists is `not-covered`: it is unreachable, unused or has no supported observation, and its register entry says which.

### Expected CI figures

These are planning projections; the generated `reports/coverage-report.md` governs and explains any difference.

| Page-zero probe | verified ÷ total |
|---|---|
| succeeds | 125 ÷ 684 = 18.3% |
| fails | 112 ÷ 684 = 16.4% |

Until committed, eligible UniKix evidence covers them, every end-to-end item a scenario lists and every JCL-only item is `blocked` in CI: only the `unikix` adapter can run them, and CI has no UniKix installation. The report counts these UniKix-dependent items separately rather than hiding them.

## Layout

| Path | Holds |
|---|---|
| `scenarios/{unit,integration,e2e}/` | the 601 YAML scenarios (94 unit, 40 integration, 467 end-to-end) |
| `fixtures/datasets.yaml` | the logical datasets built from `app/data`, with their ASCII/EBCDIC cross-check pairs and registered mismatches |
| `baselines/<id>/` | one directory per scenario: `expected.yaml`, `manifest.yaml` and, for observed baselines, `datasets/*.bin` |
| `inventory/` | `inventory.yaml` (the frozen inventory), `layouts.yaml` (record and screen layouts generated from the copybooks and BMS maps), `gaps.yaml` (the gap register source) |
| `adapters/` | `base.py` (the adapter contract), `gnucobol_batch/`, `unikix/` |
| `stubs/` | the test seams compiled by `gnucobol-batch`: `CEE3ABD.cbl`, `ceedays.c`, `psa_tiot_shim.c`, `PARMDRV.cbl`, `DTCDRV.cbl` |
| `harness/` | scenario loading, codec, fixtures, runner, baselines, results, reports, capture and inventory tools |
| `reports/` | `coverage-matrix.csv`, `gap-register.csv`, `coverage-report.md`, `results/` (gnucobol-batch result records), `unikix/` (operator evidence); `runtime/` is git-ignored |
| `test_<level>_<area>.py` | one test function, parametrized over the scenarios of one level and area |
| `test_unit_harness.py` | the harness self-tests: layouts, codecs, cross-check, inventory, id manifest, gaps, configuration, baseline bumps, protected tree; none verifies an inventory item |
| `pytest.ini`, `conftest.py`, `requirements.txt` | the pytest configuration, the session wiring, the exact Python pins |

The suite has three layers, and data flows through them one way: **scenario → adapter → assertion and reporting**. Scenarios name no adapter, adapters read no baseline, and the reporting layer only reads results.

## Conventions

- **Inventory ids** are `INV-<area>-<nnn>`. The areas are `SEC`, `MNU`, `USR`, `ACT`, `CRD`, `TRN`, `BIL`, `RPT`, `FIN`, `DAT`, `UTL`, `CFG`, `LAY`, `DTA` and `BAT`.
- **Scenario files** are `<inventory-area>-<program-or-job>-<type>-<nn>.yaml` under `scenarios/<level>/`, and the file stem is the scenario id. The type is `normal`, `alternate`, `boundary`, `negative` or `recovery`. Three departures exist:
  - `BAT-CHAIN-*`, `BAT-POSTINT-*` and `BAT-RUNORDER-*` name the job sequence they run;
  - `CFG-FCT-normal-01` names the `fct.lst` resource list;
  - `ACT-COACTUPC-negative-nnn` has three digits, because that program has 130 negative scenarios.

  The scenario id set is closed: `test_unit_harness.py` fails on any scenario, baseline or result file outside it, and on any scenario or baseline missing from it.
- **Test modules** are `test_<level>_<area>.py`. Each parametrizes one test over the scenarios of its level and area; each parameter's id is the scenario id, and its marks are the level marker, `chain` for chain scenarios, and one marker per capable adapter (`gnucobol_batch`, `unikix`).
- **Gap ids** are `GAP-<CAT>-<nnn>`:

  | `CAT` | Category |
  |---|---|
  | `DOC` | doc-conflict |
  | `DEF` | suspected-defect |
  | `STB` | stub-limitation |
  | `MIS` | missing-input |
  | `INF` | inferred-not-observed |
  | `UNC` | uncertain-purpose |

- **Baselines:** exactly one directory per scenario, `baselines/<id>/`.
- **Scenario vocabulary:** logical dataset names, the reserved observation names, program and job names, transaction ids, AID keys and BMS field names; never ddnames, DSNs or hosts.

## Local setup

The default adapter, `gnucobol-batch`, needs GnuCOBOL 3.2 built with Berkeley DB, a C compiler and Python 3.11. CI uses Ubuntu 24.04; the steps below reproduce it. Build GnuCOBOL outside the repository.

```bash
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y build-essential libgmp-dev libncurses-dev libdb-dev

curl -fsSLO https://ftp.gnu.org/gnu/gnucobol/gnucobol-3.2.tar.xz
echo "3bb48af46ced4779facf41fdc2ee60e4ccb86eaa99d010b36685315df39c2ee2  gnucobol-3.2.tar.xz" | sha256sum -c -
tar -xJf gnucobol-3.2.tar.xz && cd gnucobol-3.2
./configure --prefix=$HOME/gnucobol-3.2 --with-db && make -j"$(nproc)" && make install

export PATH=$HOME/gnucobol-3.2/bin:$PATH LD_LIBRARY_PATH=$HOME/gnucobol-3.2/lib
cobc --version                              # cobc (GnuCOBOL) 3.2.0
cobc --info | grep 'indexed file handler'   # BDB
```

- CI pins `libdb-dev=1:5.3.21ubuntu2`; the three toolchain packages are installed by name.
- Ubuntu's `gnucobol3` package (3.1.2) is not supported, and no 3.2.1 tarball is published.
- On a host whose GCC defaults to C23 (GCC 15 and later), configure with `CC="gcc -std=gnu17"`, which cobc then also uses for the C it generates.

Then create a Python 3.11.18 virtual environment outside the checkout and install the exact pins, from the repository root:

```bash
python3.11 -m venv ~/venvs/carddemo && . ~/venvs/carddemo/bin/activate
pip install -r tests/requirements.txt
pytest tests/
```

Without that toolchain on `PATH` and its libraries on `LD_LIBRARY_PATH`, every `gnucobol-batch` scenario is `blocked` under GAP-MIS-019.

For orientation, every COBOL unit is compiled with `cobc -std=ibm -fsign=EBCDIC -ftab-width=4 -fbinary-truncate -I app/cpy`, which reproduces the Enterprise COBOL options of `samples/proc/BUILDBAT.prc`, `PARM=(APOST,LIST,MAP,NUMBER,NOSEQ)`, with the default `TRUNC(STD)`. `-fsign=EBCDIC` decodes the overpunch signs of the data files, and `-ftab-width=4` reads the tab characters in `CUSTREC`. `CBACT04C` and `CSUTLDTC` are built as modules (`-m`) and called by the `PARMDRV` and `DTCDRV` drivers; `CBSTM03A` and `CBSTM03B` are built together with `-fbinary-byteorder=native`. The build runs once per session into `<work root>/_build/<build key>/`, keyed by a hash of the unit sources, copybooks, stubs, compile options and `cobc --version` output.

## Running the suite

All commands run from the repository root, in the virtual environment, with GnuCOBOL on `PATH`.

| Purpose | Command |
|---|---|
| Full suite; also regenerates the reports | `pytest tests/` |
| One level | `pytest tests/ -m unit`, `-m integration`, `-m e2e`; every e2e scenario runs only on `unikix`, so on the default `gnucobol-batch` adapter `-m e2e` selects nothing and pytest exits 5: run the level as `CARDDEMO_ADAPTER=unikix pytest tests/ -m e2e`, where each scenario is `blocked` under GAP-MIS-009 while `CARDDEMO_UNIKIX_ENV` is unset |
| One marker | `pytest tests/ -m chain`, `-m gnucobol_batch`, `-m unikix` |
| One module | `pytest tests/test_unit_trn.py` |
| One scenario | `pytest tests/ -k TRN-CBTRN02C-negative-03` |
| Reverse order | `pytest tests/ --carddemo-order=reverse` (default `forward`) |
| Skip report regeneration | `--carddemo-no-report`, for local partial runs only; CI never passes it |
| Base of the in-suite bump check | `--carddemo-baseline-base=<revision>` (default `HEAD`) |
| Regenerate matrix, register and report from the recorded results | `python tests/harness/report.py` |
| Check the register and matrix rules | `python tests/harness/report.py --check` |
| Protected-tree check; job-chain proof | `python tests/harness/report.py --check-protected`, `--check-proof` |
| Import, revalidate or compare UniKix evidence | `python tests/harness/report.py --import-unikix <execution_id>`, `--revalidate`, `--compare-executions <id1> <id2>` |
| Baseline bump check | `python tests/harness/baselines.py --check-bumps --base <revision>` |
| Capture or refresh observed baselines | `python tests/harness/capture.py <scenario_id>\|--all --bump major\|minor\|patch` |
| Freeze or check the inventory | `python tests/harness/inventory_tools.py --freeze`, `--check` |
| Regenerate or check the layouts | `python tests/harness/layouts.py --write`, `--check` |

- **Reports are regenerated at session end.** `pytest tests/` rewrites `reports/coverage-matrix.csv`, `reports/gap-register.csv`, `reports/coverage-report.md` and the report fields of `inventory/inventory.yaml` from the result records; a failed regeneration fails the session. Add `--carddemo-no-report` to a local partial run (`-m`, `-k`, one module) to skip that rewrite and leave those four files as they are. It does not make the run read-only: the run still writes the result records of the scenarios it runs (below) and, on unikix, evidence under `reports/unikix/<execution_id>/`, so a partial or broken run can change committed records; restore those you do not mean to commit, for example with `git checkout -- tests/reports/results`. `-m` and `-k` are pytest's own options and work as usual.
- **Result records.** Each gnucobol-batch run writes `reports/results/<id>.yaml`. Run details that are not deterministic (elapsed times, diffs, tracebacks, the path of a failing scenario's kept working directory) go to the git-ignored `reports/runtime/`.
- **Determinism.** CI runs the suite twice, the second time with `--carddemo-order=reverse`, and requires the deterministic projection to be byte-identical: `reports/coverage-matrix.csv`, `reports/gap-register.csv`, `reports/coverage-report.md` and `reports/results/*.yaml`.
- **Host dependence.** The gnucobol-batch `execution_id` hashes the commit and the build key, which includes `cobc --version`, so a run on another host or GnuCOBOL build writes different execution ids into the records and reports.
- **Capture.** `capture.py` takes one or more scenario ids, or `--all`. Replacing a baseline HEAD already holds needs `--bump`, at least the level its change needs: `major` when an expected outcome changes, `minor` when assertions or raw bytes are added or the capture platform changes (a derived baseline replaced by an observed one), `patch` when only the manifest changes. A lower level is refused, naming the level needed, and a missing one with `replacing it needs --bump major|minor|patch`; either exits 1, so on the committed tree the bare forms `capture.py <scenario_id>` and `capture.py --all` fail. `--bump` may be left out only for a baseline HEAD does not hold, which is written at `1.0.0` with any `--bump` ignored, also when it was written since HEAD. `--work-root <path>` sets the work root (default `CARDDEMO_TEST_WORKDIR` when usable, else a temporary directory, removed afterwards unless a failing run kept its working directory there); with `--all`, a scenario whose run ends `implemented-unexecuted` is skipped. For example `python tests/harness/capture.py TRN-CBTRN02C-negative-03 --bump patch` or `python tests/harness/capture.py --all --bump minor`.
- **After a capture,** rerun `pytest tests/`, or `pytest tests/ -k "<id> or <id>"` for just the captured scenarios, without `--carddemo-no-report`, so that their result records carry the new baseline versions and the reports are regenerated; until then `report.py` counts those records as stale, which fails `--check` (and `--check-proof` for a job-chain proof scenario). `python tests/harness/baselines.py --check-bumps --base HEAD` must pass before the baselines are committed.

## Environment variables

| Variable | Purpose | Unset or invalid |
|---|---|---|
| `CARDDEMO_ADAPTER` | the execution adapter: `gnucobol-batch` (default) or `unikix` | unset gives `gnucobol-batch`; any other value, the empty one included, is a usage error |
| `CARDDEMO_TEST_WORKDIR` | the work root: per-scenario working directories, the build cache `_build/`, the extracted UniKix zip `_unikix_zip/` and the UniKix deployment root `unikix/<execution_id>/`. It must lie outside the repository and is created when missing. With `unikix` its path, symbolic links resolved, must also hold no whitespace and no quote, which the JVM options naming the deployment's `tmp/` cannot carry, and it must be your own and writable by neither group nor others (`chmod go-w`); a missing one is created with mode 0700. A work root serves one session at a time: the session holds `<work root>/_session.lock` while it runs, and a second session or `capture.py` run naming a held root stops with a usage error naming the root and the session holding it, so give each concurrent session its own work root | unset, the session silently uses a fresh `tempfile.mkdtemp(prefix="carddemo-")` work root; set but not usable (blank, inside the repository, not a directory, not creatable or not writable; with `unikix`, a path holding whitespace or a quote, not your own, or writable by group or others), the session warns, naming the refused value and why, then uses the same fresh temporary root |
| `CARDDEMO_UNIKIX_ENV` | path to a file in the format of the zip's `bin/carddemo.env` describing the UniKix installation | every `unikix` scenario is `blocked` under GAP-MIS-009; CI never sets it |
| `CARDDEMO_TN3270_ADDR` | `host:port` (or `[v6]:port`) of the test-owned region's TN3270 listener; the host must be `localhost`, an address in `127.0.0.0/8` or `::1` | online `unikix` scenarios are `blocked` under GAP-MIS-005; a non-loopback host is refused the same way |

The adapters set these for each subprocess only; they are not user settings:

- `DD_<ddname>`: the path of each dataset a program opens.
- `COB_LIBRARY_PATH`: the directory of the stubs and the called modules, given relative to the run's working directory, because libcob splits the variable at every colon and the work root's path may hold one.
- `COB_CURRENT_DATE`: the fixed clock, from the scenario's `clock` parameter. The default clock is `2022-07-06 10:00:00.00`, passed as `2022/07/06 10:00:00.00`; the fractional part is mandatory, and the build's clock probe decides whether libcob reads it as hundredths or nanoseconds.
- `CARDDEMO_CEEDAYS_FEEDBACK`: the feedback token the `CEEDAYS` stub returns instead of computing one, set only when the scenario's `ceedays_feedback` parameter names it; without it the stub computes the feedback. The value is exactly four hexadecimal digits, and a malformed one is a harness error.
- `LD_PRELOAD`, `CARDDEMO_JOB`, `CARDDEMO_STEP`, `CARDDEMO_DDS`: the page-zero shim for `CBSTM03A` and the job, step and ddnames it builds into the TIOT.

## Adapters and the conversion baseline

### The contract

Every adapter subclasses `Adapter` in `adapters/base.py`. The runner (`harness/runner.py`) gives each scenario a fresh `<work root>/<scenario_id>/` directory and a 300-second deadline, calls the adapter's `refuse(scenario)`, checks that the adapter can produce every observation the scenario expects, then calls `prepare(scenario, workdir)`, then `execute(scenario, workdir)` returning `Result{return_code: int or null, datasets: {logical_name: bytes}, screens: [{field: value}], messages: [str], elapsed_s: float}`, and always `cleanup(scenario, workdir)` in a `finally`.

- `return_code` is `None` when the run abended. `datasets` holds raw bytes, never decoded. `screens` has one entry per online step. `messages` holds program `DISPLAY` lines, `STEP <name> RC <nnnn>` lines of a job, or each step's `ERRMSG`.
- An adapter also implements `observations()` and `observation_gap(name)` (what it can observe, and the gap for what it cannot) and `source_identity(scenario)` (the tree, commit and unit hashes it ran). It may override `refuse(scenario)`, which refuses nothing by default, to raise `AdapterUnavailable` for a scenario it can never run whatever it observes, so that gap precedes the observation check: `unikix` refuses a job with a `DFHCSDUP` step under GAP-MIS-016 once its session has started. `capable(scenario)` is one rule for every adapter, `harness.scenarios.capable_adapters`.
- `AdapterUnavailable(reason, gap_id)` makes the scenario `blocked`; `MissingPrerequisite(need, gap_id)` makes it `implemented-unexecuted`. The gap id is mandatory and must exist in `inventory/gaps.yaml`.
- Besides business datasets under their logical names, `Result.datasets` carries the reserved observations `@CATALOG` (catalogued DSNs and GDG generations), `@AIX:<path>` (records read through an alternate-index path), `@AFTER:<JOB>:<LOGICAL>` (a dataset at the end of one job of a chain), `@REGION-FILES` (region files open or closed, enabled or disabled, also taken just before or after a job as `@BEFORE:<JOB>:@REGION-FILES` and `@AFTER:<JOB>:@REGION-FILES`) and `@TDQ:JOBS` (the records the region wrote to the `JOBS` queue). Each screen carries `@map`, the identified map or `TEXT` for an unformatted screen, and, for an unformatted screen, `@text`, its full 24 × 80 text.

### `gnucobol-batch`

The default adapter, in `adapters/gnucobol_batch/`. It runs the batch units of `app/cbl` standalone (`programs.yaml` maps each ddname to a logical dataset) and the jobs through a driver whose per-job step tables are transcribed from the JCL into `jobs.yaml`. The driver never parses JCL: IDCAMS, SORT, IEBGENER, IEFBR14 and SDSF steps run through replacements in `utilities.py`, the catalog and GDG generations are emulated in `datasets.py`, and a step that ends normally gets its normal-end `DISP` through `utilities.py` (`CATLG`, `DELETE`, `UNCATLG`, `KEEP` or `PASS`, and the JCL default when it is omitted; GAP-STB-007). `COND` and the abnormal-termination `DISP` are recorded but not evaluated, and a step that abends gets no disposition; these JCL semantics are items of their own that only `unikix` runs. Indexed files are Berkeley DB files loaded and unloaded by generated COBOL programs.

The seams are compiled from `stubs/`, and each has its stub-limitation entry:

| Seam | Stands in for |
|---|---|
| `CEE3ABD.cbl` | the LE abend service; the adapter reports `return_code: null` and the marker `CARDDEMO-ABEND CEE3ABD` |
| `ceedays.c` | LE `CEEDAYS`, with feedback tokens injectable through the scenario parameter `ceedays_feedback` |
| `psa_tiot_shim.c` | the z/OS PSA → TCB → TIOT chain that `CBSTM03A` walks (see [Page zero](#page-zero)) |
| `PARMDRV.cbl`, `DTCDRV.cbl` | the z/OS `PARM` linkage of `CBACT04C` and the CALL interface of `CSUTLDTC` |

The utility replacements, the catalog emulation, Berkeley DB in place of VSAM and the compile mapping are seams too; GAP-STB-001 to GAP-STB-011 register all of them. No seam injects a read, write, rewrite or close failure after a successful open, so those recovery scenarios stay `implemented-unexecuted` under GAP-MIS-013.

### `unikix`

The operator adapter, in `adapters/unikix/`. The `app/` sources cannot run unchanged on UniKix and must not be modified, so it deploys the UniKix copy of the application from `samples/m2/unikix/UniKix_CardDemo_runtime_v1.zip` (GAP-MIS-008). The zip is extracted once, read-only, into `<work root>/_unikix_zip/`; the build, region, BPE node and catalogs live in a test-owned deployment root, `<work root>/unikix/<execution_id>/`. Batch scenarios run as BPE jobs; online scenarios are driven through the region's TN3270 listener with `s3270`, signing on only as `ADMIN001` or `USER0001`. Every UniKix command the adapter issues is an entry of `adapters/unikix/commands.yaml`, and evidence is written to `reports/unikix/<execution_id>/`, documented in [`reports/unikix/README.md`](reports/unikix/README.md).

### Plugging in a converted system

A converted system would join as a third adapter: a subclass of `adapters.base.Adapter` with its own mapping files from logical datasets, programs, jobs, transactions and fields to its platform. The scenarios, fixtures and baselines name no adapter and need no change for it, and its outcomes would be held to the decoded boundary every adapter is held to: each dataset decoded field by field through `inventory/layouts.yaml`, text with its trailing spaces, numbers as `Decimal` values with their scale, screens field by field. Raw bytes are compared only between runs of the same platform. An outcome that differs from a baseline observed on `gnucobol-batch` or `unikix` makes the scenario `failing` with reason `cross-adapter-divergence`; the baseline stays authoritative.

Wiring it in is future work, and registration alone does not complete it. Registration puts its name in `harness.ADAPTER_NAMES` and `harness.MARKER_FOR_ADAPTER`, its class in `ADAPTER_CLASSES` of `conftest.py`, its marker in `pytest.ini`, and its capability in `harness.scenarios.capable_adapters`; that selects and runs its scenarios, but the record and reporting layers know only `gnucobol-batch` and `unikix`, so the integration also needs:

- **Result persistence and evidence.** `harness.results.write` stores gnucobol-batch records only and returns `None` for any other adapter; `harness.report.build_state` reads only `reports/results/` and the imported `validation.yaml` files under `reports/unikix/`; and `harness.results.evidence_path_for` names every other adapter's evidence path `reports/unikix/<execution_id>/<id>`, which requires a UniKix execution id.
- **Source identity and execution id.** A `source_identity` tree is `app` or `unikix-copy` (`harness.results.SOURCE_TREES`), and `harness.results.execution_id` gives only the derived `gnucobol-batch-<16 hex>` form or the supplied `unikix-<yyyymmddThhmmssZ>-<8 hex>` one; `harness.runner._identify` derives the gnucobol-batch form for every adapter but `unikix`. Until the converted tree and its id form exist, the record contract refuses the run's result record and the run ends `failing` with reason `error`, unless its timeout stands.
- **Status aggregation.** A scenario's status combines its current gnucobol-batch record with its current, eligible imported UniKix records and nothing else. Eligibility, currency by fingerprint, `--revalidate` and the register entry a divergence opens exist only for the UniKix import.
- **Platform handling.** `capture_platform` is `gnucobol-batch`, `unikix` or `derived` (`harness.baselines.CAPTURE_PLATFORMS`), and `capture.py` (on `gnucobol-batch`) and `report.py --import-unikix` are the only paths that capture or promote a baseline, so no baseline is captured on the new platform and its runs get no raw comparison. Matchers resolve from the scenario clock only on `harness.baselines.FIXED_CLOCK_ADAPTERS` (`gnucobol-batch`); elsewhere the adapter's `run_date(scenario)` supplies the run date, and without it a relative matcher fails as "run date unknown".

Building that adapter is outside this suite.

## Page zero

`CBSTM03A` walks z/OS control blocks from address 0. On Linux the adapter maps page 0 with an `LD_PRELOAD` shim, built from `stubs/psa_tiot_shim.c`, that lays out a PSA, a TCB and a TIOT from the scenario's job, step and ddnames. Once per session, before the first such scenario, `page0_probe` from the build cache maps address 0, writes and reads back a pattern, and unmaps it. The probe alone decides: the `vm.mmap_min_addr` value is never taken as proof, and the adapter never changes a kernel setting.

When page zero cannot be mapped, every scenario that runs `CBSTM03A` (the `RPT-CBSTM03A-*` unit scenarios, `RPT-CREASTMT-normal-01` and `BAT-CHAIN-normal-01`) is `blocked` under GAP-MIS-002, with the reason `page zero not mappable: <errno>; vm.mmap_min_addr=<n>`. To allow it on a disposable VM or a development box:

```bash
sudo -n sysctl -w vm.mmap_min_addr=0
```

The setting is host-wide; do not change it on a shared host. A process with `CAP_SYS_RAWIO`, such as root in a container that keeps that capability, can map page zero despite the setting unless a security module forbids it. The probe result, with `vm.mmap_min_addr`, is printed in the Environment section of `reports/coverage-report.md`.

## Continuous integration

`.github/workflows/carddemo-tests.yml` defines one job, `characterization`, on `ubuntu-24.04`, for every push and pull request. It holds no secrets and leaves `CARDDEMO_ADAPTER` unset, so every scenario only `unikix` can run is deselected, and is reported `blocked` unless committed, eligible UniKix evidence gives it another status (see [Statuses](#statuses)). In order, it:

1. checks out the full history (the protected base and the bump-check base must be present), sets up Python 3.11.18, installs the system packages with `libdb-dev=1:5.3.21ubuntu2` and `s3270=4.1ga10-1.1build2`, and restores or builds GnuCOBOL 3.2 (cached under the tarball's sha256);
2. installs `tests/requirements.txt` and tries `sudo -n sysctl -w vm.mmap_min_addr=0`, which may fail without failing the job;
3. runs `pytest tests/` (15 minutes at most), then `pytest tests/ --carddemo-order=reverse`, and `diff -r`s the deterministic projection of the two runs;
4. always regenerates the reports with `python tests/harness/report.py`, after first dropping the result records no run of this job wrote (they name an earlier commit), so a run cut short reports only what it executed, then runs `--check`, `--check-proof`, `--check-protected` and `python tests/harness/baselines.py --check-bumps --base "$BASE"`, where `BASE` is the pull request's base commit, else the push's previous commit, else the starting commit `93ebec71ff7b59a68cedd7c501afe496a8eaa68d`;
5. shows `git diff --stat -- tests/reports` for information, appends `coverage-report.md` to the job summary, and uploads `tests/reports` with the failing scenarios' working directories as the artifact `carddemo-reports`.

A failed run, check, proof or comparison fails the job. CI never commits: the committed `tests/reports/` comes from a full local run with the same pins.

## UniKix prerequisites

The `unikix` adapter runs only on an operator host with an NTT DATA UniKix installation. No public source states the versions these need, so they are unpinned; the first operator run records each installed version in `environment.yaml` (GAP-MIS-009). The paths are those the zip's `bin/carddemo.env` sets:

| Prerequisite | Location |
|---|---|
| NTT DATA UniKix TPE | `UNIKIX=/opt/software/TPE` |
| BPE | `INSTEBM=/opt/software/BPE/INSTEBM` |
| Visual COBOL toolchain | `VCOBOL=/opt/software/COBOL` |
| UniKix licence | `KIXLICDIR=/opt/unikix` |
| Java runtime | `JAVA_HOME`, derived from `/etc/alternatives/java` |
| TPE agent | `/opt/software/NTT_DATA_TPE_Agent`; recorded, not required |
| `s3270` | 4.x on `PATH` (CI pins `s3270=4.1ga10-1.1build2` but never uses it) |

`UNIKIX`, `INSTEBM`, `VCOBOL`, `KIXLICDIR` and `JAVA_HOME` must name existing paths; otherwise every `unikix` scenario is `blocked` under GAP-MIS-009.

## UniKix operator procedure

1. **Prepare the host.** Clone the repository, create a Python 3.11.18 virtual environment, run `pip install -r tests/requirements.txt`, and put `s3270` 4.x on `PATH`.
2. **Environment file.** Copy the zip's environment file outside the repository, for example `python -c "import pathlib, zipfile; pathlib.Path.home().joinpath('carddemo-unikix.env').write_bytes(zipfile.ZipFile('samples/m2/unikix/UniKix_CardDemo_runtime_v1.zip').read('bin/carddemo.env'))"`, adjust it to the installation, and `export CARDDEMO_UNIKIX_ENV=~/carddemo-unikix.env`. The adapter sources it in a clean bash, replaces its `BASE_DIR=$HOME/unikixdemo` with the test-owned deployment root and recomputes every variable derived from it, sets `PR`, `PARMFILES` and `SEQFILES`, which the file leaves unset, and points `MIGAPP_DIR` at a writable staging copy of the application. It sources `$KIXSYS/bin/userenv` and then `$EBMHOME/batchenv sys1` only from the deployment root, once it has created them; batchenv is sourced by running the `bpe_env` entry of `commands.yaml`, so its probe, its `timeout_s` and an override of its command apply. The file cannot change `TMPDIR`, `TMP`, `TEMP` or `_JAVA_OPTIONS` while it is sourced, because they are read-only there: a line that assigns, exports or unsets one fails with `readonly variable`, which blocks the run under GAP-MIS-009 only when it is the file's last line. The same holds for userenv and batchenv (GAP-MIS-003, GAP-MIS-006) and for every command. Every UniKix command and both sourcing stages run with `TMPDIR`, `TMP` and `TEMP` set to `<deployment root>/tmp`, `_JAVA_OPTIONS` set to `-Djava.io.tmpdir=<deployment root>/tmp -XX:+PerfDisableSharedMem`, and `JAVA_TOOL_OPTIONS` ending in those options; `environment.yaml` records them. A `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` with an unmatched quote blocks the run (GAP-MIS-009 from the file, GAP-MIS-003 or GAP-MIS-006 from userenv or batchenv), and so does a userenv or batchenv whose JVM options name another `java.io.tmpdir`. The deployment root, its `home/`, its `tmp/` and its `logs/` must be real directories of the user running the suite; a symbolic link there stops the session before the file is sourced, and one put there later stops the next command or log write. The file's `echo` lines are harmless: their output is recorded, never parsed.
3. **Work root (optional).** Set `CARDDEMO_TEST_WORKDIR` to a persistent directory outside the repository; it then keeps the deployment root and its region between sessions. Its path, symbolic links resolved, must hold no whitespace and no quote, because the JVM options name `<deployment root>/tmp` below it and a JVM splits them at whitespace and reads quotes. The directory must be your own and writable by neither group nor others: run `chmod go-w` on one you made yourself, since `mkdir` under umask 002 makes it group-writable (a missing one the session creates is 0700). Otherwise the session warns and uses a fresh temporary root instead. Use one work root per concurrent session: a second session naming a root another session holds stops with a usage error.
4. **Commands.** Review `tests/adapters/unikix/commands.yaml` against the installed release and correct each command for the installation. Set an entry to `status: confirmed` only after it has worked. The adapter issues nothing outside that file. Every entry ships `unconfirmed` and runs once its probe succeeds. An entry whose `command` is `null`, because no public source names one, ships the probe `false`; a scenario that needs it is `blocked` under its gap only while neither its command nor a supported fallback is available. Until the operator supplies the command and probe, the fallbacks are:

   - `catalog_reset`: a generated IDCAMS job, submitted through `bpe_submit`, deletes every catalog entry the post-deployment snapshot lacks and redefines every missing snapshot GDG base; the catalog is then listed again.
   - `region_file_close`, `region_file_open`, `region_file_enable`, `tdq_empty` and `tdq_enable`: a region stop and restart (`region_stop`, `region_start`).
   - `region_quiesce_query`: stopping and restarting the region before each scenario.
   - `bpe_active_jobs`: a BPE restart (`bpe_stop`, then `bpe_start`). `bpe_stop` also ships `null`, so no `unikix` scenario runs until one of the two is supplied (GAP-MIS-004).
   - `region_file_disable`: `region_file_close`. An online `unavailable` fault on a region file that neither can apply, one on `JOBS` without `tdq_disable`, or one counted with `after` without `region_file_requests` leaves its scenario `implemented-unexecuted` (GAP-MIS-011), not `blocked`.

   `bpe_node_create` (GAP-MIS-006) and `bpe_catalog_import` (GAP-MIS-007) have no fallback: deployment stops there, and every `unikix` scenario is `blocked`. `region_file_inquire` (GAP-MIS-011) and `jobs_capture` (GAP-MIS-017) have none either; they block only the scenarios that assert `@REGION-FILES` or `@TDQ:JOBS`. With `bpe_active_jobs` but no `bpe_cancel` (GAP-MIS-006), a BPE job left unfinished by a timeout or failure blocks the rest of the session (GAP-MIS-004). `clock_set` alone must also be `confirmed` before a fixed-clock scenario such as `BAT-CHAIN-normal-01` runs on `unikix` (GAP-MIS-015). The version probes `version_tpe`, `version_bpe`, `version_vcobol`, `version_java`, `version_licence` and `version_s3270` record the installed versions in `environment.yaml` and cite GAP-MIS-009.
5. **Listener.** Configure the TN3270 listener of the test-owned region on loopback, then `export CARDDEMO_TN3270_ADDR=127.0.0.1:<port>`. The port must equal the `TNServer*ListenPort` in `$KIXSYS/unikixrc.cfg`.
6. **Run.**

   ```bash
   CARDDEMO_ADAPTER=unikix pytest tests/ -m unikix
   ```

   This also runs the job scenarios both adapters can run, which provides the cross-adapter comparison.
7. **Evidence** is written to `tests/reports/unikix/<execution_id>/`, one directory per scenario, with `execution_id` of the form `unikix-<UTC yyyymmddThhmmssZ>-<8 hex>`. A UniKix run writes no `reports/results/` record.
8. **Import.** First set each `commands.yaml` entry that worked in the run, the version probes included, to `status: confirmed`; the import never confirms an entry. Then import with `python tests/harness/report.py --import-unikix <execution_id>`. It checks each record's source identity and environment, promotes eligible derived baselines, writes `validation.yaml` beside each `result.yaml`, updates `inventory/gaps.yaml` and regenerates the reports. It closes GAP-MIS-009 only once `environment.yaml` records every version and every `commands.yaml` entry citing GAP-MIS-009 is `confirmed`; otherwise it leaves GAP-MIS-009 open and names the unconfirmed entries, and importing the execution again once they are confirmed closes it.
9. **Revalidate and compare.** After any later scenario or baseline change, run `python tests/harness/report.py --revalidate`. Compare two executions with `python tests/harness/report.py --compare-executions <id1> <id2>`.
10. **Commit** the evidence directory with its `validation.yaml` files, the updated `commands.yaml`, the scenarios and baselines the import changed, `inventory/gaps.yaml` (and `inventory/inventory.yaml` when the import changed it), and the regenerated reports.

An item leaves `blocked` only on committed, eligible evidence. A UniKix record of an item whose behavior passes through a part of the UniKix copy that differs from `app/` (GAP-DOC-020 to GAP-DOC-032) is reported in the "UniKix-copy behavior" section of the coverage report and never verifies the `app/` item; the 6 UniKix-only `fct.lst` files of GAP-DOC-030, which have no `app/` counterpart, are verified through `CFG-FCT-normal-01`. The evidence layout, the import and revalidation rules and the comparison are documented in [`reports/unikix/README.md`](reports/unikix/README.md).

Never run `samples/m2/mf/CardDemo_runtime.zip`. The UniKix zip is only read; it is extracted, read-only, outside the repository.

## Boundaries

- **The system under test is never changed.** Nothing under `app/`, `samples/` or `diagrams/` changes: `python tests/harness/report.py --check-protected` diffs those paths against the starting commit `93ebec71ff7b59a68cedd7c501afe496a8eaa68d`, the inventory header's `protected_base`, and checks their working-tree status, in CI and in `test_unit_harness.py`. The root files (`README.md`, `CONTRIBUTING.md` and the rest) are not changed either; the only addition outside `tests/` is `.github/workflows/carddemo-tests.yml`.
- **Seams live only in the test tree,** in `stubs/` and `adapters/`, each with its stub-limitation entry.
- **Defects are pinned, never fixed.** A suspected defect is tagged `suspected-defect` and registered under a GAP-DEF entry; doubtful behavior is tagged `observed-only` with an open uncertain-purpose entry.
- **Credentials.** Online scenarios sign on only as `ADMIN001` or `USER0001`, with the password `PASSWORD` that the root [`README.md`](../README.md) documents, and only against the operator's local region. Users the scenarios create are data and never sign on.
- **No network.** No scenario uses a live service, a shared database or any network beyond loopback, and the adapters install nothing at run time.
- **Isolation.** Each scenario runs in its own fresh working directory under the work root. The directory is removed when the scenario passes and kept when it fails. Scenarios pass in any order; only the jobs of one chain scenario share state.
- **Time.** Each scenario has a 300-second deadline, which it cannot raise; a scenario that exceeds it is `failing` with reason `timeout`. The unit and integration levels complete within 15 minutes, as the CI step enforces.

## Reports, gaps and baselines

### Reports

`harness/report.py` writes these from the inventory, the scenarios, the result records and the imported UniKix records:

| File | Content |
|---|---|
| `reports/coverage-matrix.csv` | one row per inventory item: `inventory_id`, `implementation_locations`, `scenario_ids`, `assertions`, `status`, `execution_id`, `evidence_path`; multiple values are joined by `;` |
| `reports/gap-register.csv` | one row per entry of `inventory/gaps.yaml`: `gap_id`, `category`, `unknown`, `affected_inventory_ids`, `resolving_evidence`, `needed`, `status` |
| `reports/coverage-report.md` | Summary (counts per status, verified ÷ total, the planning projection); By level, with the chain on its own row; By area, with the tech-spec seed counts, and By kind; UniKix-dependent items; UniKix-copy behavior; Job-chain proof; Register summary; 100% claim; Inventory completeness; Environment (adapter, `cobc --version`, Python and package versions, toolchain packages, the page-zero probe, the commit) |
| `reports/results/<id>.yaml` | the gnucobol-batch result record of one scenario: `scenario_id`, `adapter`, `execution_id`, `status`, `reason`, `gap_id`, `baseline_version`, `scenario_digest`, `source_identity`, `execution_fingerprint`, `decoded` (the normalized outcome) and `evidence_path` |

`report.py --check` checks the register and matrix rules: one matrix row per inventory item, every item that is not `verified` covered by an open register entry, status counts adding up to the frozen total, every cited gap id registered, the seam rule, and report files as `report.py` would write them. `--check-proof` checks the job-chain proof: `BAT-POSTINT-normal-01` (`POSTTRAN` then `INTCALC`) and `BAT-CHAIN-normal-01` (`POSTTRAN` → `INTCALC` → `TRANBKP` → `COMBTRAN` → `CREASTMT` → `TRANREPT`) passed on current gnucobol-batch records with the proof's jobs, fixtures and clock and their business outcomes asserted, and their BAT, `POSTTRAN` and `INTCALC` items are `verified`.

### Gaps

Every open question is an entry of `inventory/gaps.yaml`, rendered into `reports/gap-register.csv`. Entries are raised as follows:

- **doc-conflict:** documentation disagrees with the repository, a fixture cross-check finds an unregistered mismatch, or an imported UniKix outcome contradicts a derived baseline taken from the tech spec.
- **suspected-defect:** every scenario tagged `suspected-defect` cites one.
- **inferred-not-observed:** every derived baseline cites one.
- **stub-limitation:** every seam has one, and every scenario that uses the seam cites it.
- **missing-input:** every `AdapterUnavailable` and `MissingPrerequisite` names one.
- **uncertain-purpose:** what the code is for is the open question: an unused or unreachable item, doubtful behavior tagged `observed-only`, an unexplained cross-adapter divergence.

An entry closes only on evidence, in five steps: set `status: closed`; record the `resolving_evidence`, an `execution_id` with its evidence path or a commit; update the inventory (activation, description, or additions with their `header.changes` reason); update the affected scenarios and baselines with their version bump; regenerate the reports. Entries are never deleted, and a closed entry reopens on contrary evidence. Closing a suspected defect never changes `app/`.

### Baselines

Each `baselines/<id>/` holds `expected.yaml` (the decoded expected outcome), `manifest.yaml` (`baseline_version`, `source_commit`, `capture_platform`, `capture_date`, `source_identity` and, for an observed baseline, `execution_id` and `execution_fingerprint`) and, for an observed baseline, `datasets/<LOGICAL>.bin` with the raw expected bytes.

- **Observed** baselines (`capture_platform: gnucobol-batch`) are captured with `python tests/harness/capture.py` for the unit scenarios without a post-open fault and the integration jobs and chains both adapters can run. `capture.py` refuses a run that ends `implemented-unexecuted`, so no raw artifact is fabricated.
- **Derived** baselines (`capture_platform: derived`) are authored from the source for the end-to-end scenarios, the integration scenarios only `unikix` can run, and the unit scenarios whose post-open fault cannot be injected. Their scenarios are tagged `inferred-not-observed`, and they have no `datasets/*.bin` until observed: a UniKix import promotes the online and JCL-level ones, and `capture.py` replaces the fault cases once a fault mechanism resolves GAP-MIS-013.

`baseline_version` is semver, and a new baseline starts at `1.0.0`:

| Bump | When |
|---|---|
| MAJOR | an expected business outcome changes |
| MINOR | assertions or raw bytes are added, or a derived baseline is replaced by an observed one with the same decoded outcome |
| PATCH | only manifest metadata changes |

`python tests/harness/baselines.py --check-bumps --base <revision>` compares every baseline directory with the same directory at `<revision>` and fails on a missing or insufficient bump, on a changed version of an unchanged directory, and on a new baseline that does not start at `1.0.0`. `test_unit_harness.py` runs the same check against `HEAD`, or the `--carddemo-baseline-base` revision.
