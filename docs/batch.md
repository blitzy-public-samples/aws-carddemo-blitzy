# CardDemo Batch Guide

The legacy CardDemo JCL/COBOL batch chain is reimplemented as an idempotent
Python CLI package (`batch/`), invoked as `python -m batch.cli` and built on
[Typer](https://typer.tiangolo.com/). The package reuses the backend `app`
package (models, config, and utilities) but connects to PostgreSQL through its
own **synchronous** psycopg2 engine (`batch/db.py`, driven by the
`SYNC_DATABASE_URL` environment variable), distinct from the backend service's
async engine. There is no batch container in `docker-compose.yml` — the batch
package runs on demand from the repository root, replacing legacy JCL job
submission with CLI subcommands.

> **Source of truth.** The authoritative command names and entry functions are
> the batch modules themselves —
> [`batch/cli.py`](../batch/cli.py),
> [`batch/orchestration/batch_chain.py`](../batch/orchestration/batch_chain.py),
> [`batch/jobs/*.py`](../batch/jobs), and
> [`batch/loaders/*.py`](../batch/loaders). This guide is reconciled against
> those files. The legacy COBOL programs and JCL cited throughout are preserved
> **unmodified** as REFERENCE under [`../app/cbl/`](../app/cbl) and
> [`../app/jcl/`](../app/jcl); their business rules are reproduced field-for-field.

## Table of Contents

- [1. Prerequisites and Setup](#1-prerequisites-and-setup)
- [2. CLI Overview](#2-cli-overview)
- [3. Seeding the Database (Loaders)](#3-seeding-the-database-loaders)
- [4. Batch Jobs](#4-batch-jobs)
- [5. Full Batch Chain](#5-full-batch-chain)
- [6. Key Job Semantics](#6-key-job-semantics)
- [7. Golden-Master Parity and Idempotency](#7-golden-master-parity-and-idempotency)
- [8. Related Documentation](#8-related-documentation)

## 1. Prerequisites and Setup

Run every command from the **repository root**.

1. **Start PostgreSQL.** Bring up the database service defined in
   `docker-compose.yml`:

   ```bash
   docker compose up -d db
   ```

2. **Apply the schema migrations.** Alembic owns the entire schema; the batch
   package never issues DDL:

   ```bash
   cd backend && alembic upgrade head && cd ..
   ```

3. **Make the backend `app` package importable.** The batch modules import the
   backend `app` package (for example `batch.db` uses `app.core`). Either install
   the backend as an editable distribution, or rely on the path shim in
   [`batch/__init__.py`](../batch/__init__.py), which prepends `backend/` to
   `sys.path` so `python -m batch.cli` works from a fresh checkout with no
   install:

   ```bash
   pip install -e ./backend        # optional: add -e ./batch for the console script
   ```

4. **Set the environment.** Copy the backend environment template and confirm the
   batch DSN is present:

   ```bash
   cp backend/.env.example backend/.env
   ```

   The batch package needs only the `SYNC_DATABASE_URL` variable (a psycopg2 DSN
   of the form `postgresql+psycopg2://…`). Under `docker-compose` the database
   host is `db`; when connecting from the host, use `localhost:5432`. Never commit
   a real value — the DSN is read from the environment, in keeping with the Ochs
   no-hardcoding rule.

## 2. CLI Overview

The CLI is a thin dispatcher: every subcommand opens exactly one synchronous
SQLAlchemy unit of work (`batch/db.py`, `GetSyncSession`) and delegates to a
module-level entry function — no business logic lives in the CLI. Explore it with
`--help`:

```bash
python -m batch.cli --help          # top-level: job, load, run-all, seed-all
python -m batch.cli job --help       # 11 individual jobs (1:1 with CB* programs)
python -m batch.cli load --help      # 10 data loaders (1:1 with IDCAMS load jobs)
```

The CLI is organized into two sub-apps and two orchestration commands:

| Command group | Purpose |
| :------------ | :------ |
| `job` | Individual batch jobs, one-to-one with the legacy `CB*` COBOL programs. See [Section 4](#4-batch-jobs). |
| `load` | Data loaders, one-to-one with the legacy IDCAMS load jobs. See [Section 3](#3-seeding-the-database-loaders). |
| `run-all` | Runs the full legacy chain in preserved order. See [Section 5](#5-full-batch-chain). |
| `seed-all` | Seeds every table in foreign-key-safe order (initial bootstrap). See [Section 3](#3-seeding-the-database-loaders). |

Command names are **kebab-case** (for example `post-transactions`,
`interest-calc`, `tran-categories`). A global `--verbose` / `-v` flag raises the
log level to `DEBUG`; the default level is `INFO` and can also be set through the
`BATCH_LOG_LEVEL` environment variable.

## 3. Seeding the Database (Loaders)

The loaders correspond one-to-one with the legacy IDCAMS load jobs. Each reads a
display-readable ASCII seed file from `app/data/ASCII/` (the primary source; the
`--data-dir` option defaults to that directory) and upserts rows into the target
table, so re-running a loader is safe.

Bootstrap a fresh database by loading **every** table in a single
foreign-key-safe pass:

```bash
python -m batch.cli seed-all                        # all tables, FK-safe order
```

Run an individual loader with the optional `--data-dir` override:

```bash
python -m batch.cli load accounts                   # default: app/data/ASCII
python -m batch.cli load init-users
python -m batch.cli load transactions --data-dir app/data/ASCII
```

| CLI (`load …`) | Loads table | Source file | Legacy origin | Entry function |
| :------------- | :---------- | :---------- | :------------ | :------------- |
| `accounts` | `accounts` | `acctdata.txt` | `ACCTFILE` (IDCAMS) | `LoadAccounts` |
| `cards` | `cards` | `carddata.txt` | `CARDFILE` (IDCAMS) | `LoadCards` |
| `customers` | `customers` | `custdata.txt` | `CUSTFILE` (IDCAMS) | `LoadCustomers` |
| `xref` | `card_xref` | `cardxref.txt` | `XREFFILE` (IDCAMS) | `LoadCardXref` |
| `transactions` | `transactions` | `dailytran.txt` | `TRANFILE` (IDCAMS) | `LoadTransactions` |
| `disclosure-groups` | `disclosure_group` | `discgrp.txt` | `DISCGRP` (IDCAMS) | `LoadDisclosureGroups` |
| `tran-categories` | `transaction_category` | `trancatg.txt` | `TRANCATG` (IDCAMS) | `LoadTranCategories` |
| `tran-types` | `transaction_type` | `trantype.txt` | `TRANTYPE` (IDCAMS) | `LoadTranTypes` |
| `tcatbal` | `tran_category_balance` | `tcatbal.txt` | `TCATBALF` (IDCAMS) | `LoadTranCategoryBalances` |
| `init-users` | `users` | EBCDIC `USRSEC` | [`DUSRSECJ.jcl`](../app/jcl/DUSRSECJ.jcl) | `InitializeUsers` |

`seed-all` loads the tables in this foreign-key-safe order: `disclosure_group` →
`customers` → `accounts` → `cards` → `card_xref` → `transaction_type` →
`transaction_category` → `tran_category_balance` → `transactions` → `users`.

**User-security seed (`init-users`).** Unlike the other datasets, `USRSEC` ships
**only** as an EBCDIC dataset (`app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`); there
is no ASCII copy. `InitializeUsers` decodes it with the IBM `cp037` codec (or
regenerates the seed), slices the 80-byte `SEC-USER-DATA` record, and **hashes**
every 8-character plaintext password (`SEC-USR-PWD`) with bcrypt via
`app.core.security.HashPassword` before writing `password_hash`. Plaintext is
never stored, logged, or printed. The sample credentials `ADMIN001` / `USER0001`
with password `PASSWORD` are **non-production seed data only** — never treat them
as real credentials.

## 4. Batch Jobs

The `job` sub-app exposes the eleven batch jobs, each a one-to-one port of a
legacy `CB*` COBOL program (a few also carry JCL/control-card lineage). Each job
opens its own transaction and delegates to the listed entry function.

| CLI (`job …`) | Purpose | Legacy program | Entry function |
| :------------ | :------ | :------------- | :------------- |
| `post-transactions` | Post daily transactions | [`CBTRN02C`](../app/cbl/CBTRN02C.cbl) | `PostTransactions` |
| `interest-calc` | Calculate monthly interest | [`CBACT04C`](../app/cbl/CBACT04C.cbl) | `CalculateInterest` |
| `statement-gen` | Generate statements (CSV/PDF) | [`CBSTM03A`](../app/cbl/CBSTM03A.CBL) / [`CBSTM03B`](../app/cbl/CBSTM03B.CBL) | `GenerateStatements` |
| `print-account` | Account master listing | [`CBACT01C`](../app/cbl/CBACT01C.cbl) | `PrintAccounts` |
| `print-card` | Card master listing | [`CBACT02C`](../app/cbl/CBACT02C.cbl) | `PrintCards` |
| `print-xref` | Card cross-reference listing | [`CBACT03C`](../app/cbl/CBACT03C.cbl) | `PrintCardXref` |
| `print-customer` | Customer master listing | [`CBCUS01C`](../app/cbl/CBCUS01C.cbl) | `PrintCustomers` |
| `read-daily-tran` | Read daily transactions | [`CBTRN01C`](../app/cbl/CBTRN01C.cbl) | `ReadDailyTransactions` |
| `tran-detail-report` | Transaction detail report | [`CBTRN03C`](../app/cbl/CBTRN03C.cbl) | `ReportTransactionDetail` |
| `combine-tran` | Combine/merge transaction files | [`COMBTRAN.jcl`](../app/jcl/COMBTRAN.jcl) + [`REPROCT.ctl`](../app/ctl/REPROCT.ctl) | `CombineTransactions` |
| `backup-tran` | Back up transactions | [`TRANBKP.jcl`](../app/jcl/TRANBKP.jcl) | `BackupTransactions` |

Example — post the daily transaction file for a specific business date:

```bash
python -m batch.cli job post-transactions --run-date 2024-01-31
```

Jobs that produce artifacts accept an `--output-dir` option (`statement-gen`,
`backup-tran`, `tran-detail-report`), and the posting job accepts a `--reject-dir`
for its protected reject sink; all default to the gitignored `out/` directory so a
run never leaves untracked files in the working tree.

## 5. Full Batch Chain

The full chain is orchestrated by
[`batch/orchestration/batch_chain.py`](../batch/orchestration/batch_chain.py)
(`RunBatchChain`) and run with:

```bash
python -m batch.cli run-all --run-date 2024-01-31
```

`run-all` assumes an already-seeded database (run `seed-all` first to bootstrap a
fresh one) and accepts `--run-date`, `--data-dir`, and `--output-dir`. The job
order is **preserved exactly** from the legacy JCL sequence (AAP §0.7.6; legacy
README lines 165–183):

```text
CLOSEFIL → ACCTFILE → CARDFILE → XREFFILE → CUSTFILE → TRANBKP → DISCGRP →
TCATBALF → TRANTYPE → DUSRSECJ → POSTTRAN → INTCALC → TRANBKP → COMBTRAN →
CREASTMT → TRANIDX → OPENFIL
```

| # | Step | Purpose | Modern implementation | Legacy source |
| -: | :--- | :------ | :-------------------- | :------------ |
| 1 | `CLOSEFIL` | Quiesce CICS files (guard) | `batch_chain.py` (no-op) | `IEFBR14` |
| 2 | `ACCTFILE` | Load account master | `batch/loaders/load_accounts.py` | `ACCTFILE.jcl` |
| 3 | `CARDFILE` | Load card master | `batch/loaders/load_cards.py` | `CARDFILE.jcl` |
| 4 | `XREFFILE` | Load card cross-reference | `batch/loaders/load_xref.py` | `XREFFILE.jcl` |
| 5 | `CUSTFILE` | Load customer master | `batch/loaders/load_customers.py` | `CUSTFILE.jcl` |
| 6 | `TRANBKP` | Back up transactions (pre-post snapshot) | `batch/jobs/backup_tran.py` | `TRANBKP.jcl` |
| 7 | `DISCGRP` | Load disclosure groups | `batch/loaders/load_disclosure_groups.py` | `DISCGRP.jcl` |
| 8 | `TCATBALF` | Load transaction-category balances | `batch/loaders/load_tcatbal.py` | `TCATBALF.jcl` |
| 9 | `TRANTYPE` | Load transaction types | `batch/loaders/load_tran_types.py` | `TRANTYPE.jcl` |
| 10 | `DUSRSECJ` | Initialize users (hashed passwords) | `batch/loaders/init_users.py` | `DUSRSECJ.jcl` |
| 11 | `POSTTRAN` | Post daily transactions | `batch/jobs/post_transactions.py` | `CBTRN02C` + `POSTTRAN.jcl` |
| 12 | `INTCALC` | Calculate interest | `batch/jobs/interest_calc.py` | `CBACT04C` + `INTCALC.jcl` |
| 13 | `TRANBKP` | Back up transactions (post-interest snapshot) | `batch/jobs/backup_tran.py` | `TRANBKP.jcl` |
| 14 | `COMBTRAN` | Combine transactions (sort by `tran_id` ascending) | `batch/jobs/combine_tran.py` | `COMBTRAN.jcl` + `REPROCT.ctl` |
| 15 | `CREASTMT` | Generate statements (CSV + PDF) | `batch/jobs/statement_gen.py` | `CBSTM03A` + `CBSTM03B` + `CREASTMT.jcl` |
| 16 | `TRANIDX` | Alternate-index rebuild → PostgreSQL `ANALYZE` guard | `batch_chain.py` (`ANALYZE`) | `TRANIDX.jcl` |
| 17 | `OPENFIL` | Re-enable CICS files (guard) | `batch_chain.py` (no-op) | `IEFBR14` |

Notes on the preserved order:

- **`CLOSEFIL` and `OPENFIL`** were legacy `IEFBR14` no-ops that quiesced and
  re-enabled the CICS files around the chain. They become **no-op guard steps**
  in Python.
- **`TRANIDX`** was a VSAM alternate-index rebuild. PostgreSQL maintains indexes
  automatically, so this step becomes a lightweight `ANALYZE` guard (Alembic owns
  the actual index definitions).
- **`TRANBKP` appears twice** (steps 6 and 13): a pre-post snapshot and a
  post-interest snapshot, mirroring the legacy JCL.
- **Per-step transactions.** Each step opens and owns exactly one transaction
  through `batch/db.py` (`GetSyncSession`), committing on success and rolling back
  on error — a failed step rolls back only its own work. This mirrors the legacy
  per-JCL-step commit semantics and keeps the whole chain **idempotent and
  re-runnable**. If a step fails, the chain aborts and reports the failing legacy
  job name.
- **Return code.** If any transaction is rejected during `POSTTRAN`, `run-all`
  (like the standalone `post-transactions` job) exits with code `4`, mirroring the
  legacy `CBTRN02C` `RETURN-CODE = 4`.

## 6. Key Job Semantics

The business rules below are ported verbatim under the Minimal Change Clause. The
single intentional behavior-adjacent change is the statement output format.

### Transaction posting (`post-transactions` ← CBTRN02C)

Posting validates each daily transaction and either posts it or writes a reject
record. It enforces the following reason codes (see the shared definitions in
[`backend/app/core/exceptions.py`](../backend/app/core/exceptions.py) and the
[API reference](./api-reference.md#error-codes)):

| Code | Description | Condition |
| :--- | :---------- | :-------- |
| `100` | `INVALID CARD NUMBER FOUND` | Card cross-reference lookup failed. |
| `101` | `ACCOUNT RECORD NOT FOUND` | Account lookup failed during validation. |
| `102` | `OVERLIMIT TRANSACTION` | `credit_limit < curr_cyc_credit − curr_cyc_debit + tran_amt`. |
| `103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | Transaction date is after the account expiration date. |
| `109` | `ACCOUNT RECORD NOT FOUND` | Account not found on the balance-update rewrite. |

Codes `101` and `109` deliberately share the same description text but remain
**distinct** codes — `101` is the validation-time read miss, `109` is the
update-time rewrite miss. Rejected rows are written as a fixed-width **430-byte**
reject record to a `DALYREJS`-equivalent sink. Because reject records carry the
raw, unmasked card number, they are written only to a protected on-disk file and
are **never echoed or logged**; only the reject count and the opaque sink path are
surfaced. A data reject never aborts the run.

On a successful post, the balance update adds `tran_amt` to `curr_bal`, then adds
it to `curr_cyc_credit` when the amount is **≥ 0**, otherwise to `curr_cyc_debit`.

### Interest calculation (`interest-calc` ← CBACT04C)

The monthly interest is computed with the legacy formula, verbatim:

```text
monthly_interest = (tran_cat_bal × interest_rate) ÷ 1200
```

The legacy `COMPUTE` carries **no `ROUNDED` phrase**, so the result is
**truncated** (not rounded) to two decimal places — reproduced in Python with
`Decimal.quantize(Decimal('0.01'), rounding=ROUND_DOWN)`. Every value flows
through `Decimal`; **floating point is never used**. The resulting interest
transaction is posted as transaction **type `01`**, **category `05`** (stored as
the 4-byte value `0005`), and its amount is added to `curr_bal`.

### Statement generation (`statement-gen` ← CBSTM03A/CBSTM03B)

Statements are produced as **CSV and PDF** files. This is the single intentional
redesign (AAP §0.8.4): the legacy GDG text + HTML statement output is replaced by
a downloadable CSV plus a PDF rendering. All monetary values and business rules
are otherwise preserved exactly.

## 7. Golden-Master Parity and Idempotency

The batch package is validated for **golden-master parity** (AAP §0.8.1): its
outputs must reconcile field-for-field against the legacy output for the
[`../app/data`](../app/data) sample dataset. This covers reject reason codes and
their verbatim description strings, the 430-byte reject-record byte layout,
truncated interest amounts, and posted balances. Parity and behavior tests live
in [`batch/tests/`](../batch/tests).

Every job and loader is **idempotent and re-runnable**: each runs inside its own
database transaction (`batch/db.py`, `GetSyncSession`) that commits on success and
rolls back on error, and the loaders upsert rather than blind-insert. Re-running a
job — or the entire chain — is therefore safe and converges to the same state.

## 8. Related Documentation

- [Architecture overview](./architecture.md)
- [Data model](./data-model.md)
- [API reference](./api-reference.md)
- [Traceability matrix](./traceability.md)
- [Project README](../README.md)

