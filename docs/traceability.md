# CardDemo Traceability Matrix

This matrix maps every legacy z/OS mainframe artifact of the CardDemo
application to its modern three-tier equivalent, and back again. It follows the
**Minimal Change Clause** of the migration: **1 BMS map → 1 frontend page → 1
REST endpoint**, **1 batch program → 1 `batch/jobs` module**, **1 record
copybook → 1 SQLAlchemy model + Pydantic schema**, and **1 VSAM file → 1
PostgreSQL table + repository**. Online **services are consolidated by domain**:
the related `CO*C` programs of one domain share a single service (for example
account view + update → `account_service`), so the program → service mapping is
**many-to-one, not 1:1**. The per-program detail, the domain consolidations, and
the additional non-screen operations are all disclosed in
[§1](#1-online-transactions).
Use it to trace any mainframe program, map, copybook, or dataset to the exact
modern route, endpoint, service, model, or module that now carries its business
logic. Every legacy source remains **unchanged** under [`../app/`](../app); the
modern code lives under `backend/`, `frontend/`, and `batch/`.

## Table of Contents

- [1. Online Transactions](#1-online-transactions)
- [2. Batch Programs](#2-batch-programs)
- [3. Copybooks to Models and Schemas](#3-copybooks-to-models-and-schemas)
- [4. VSAM Datasets to Tables and Repositories](#4-vsam-datasets-to-tables-and-repositories)
- [5. Concept Mapping](#5-concept-mapping)
- [6. Out-of-Scope Legacy Artifacts](#6-out-of-scope-legacy-artifacts)
- [7. Target Inventory and Scope Reconciliation](#7-target-inventory-and-scope-reconciliation)
- [8. Related Documentation](#8-related-documentation)

## 1. Online Transactions

Each legacy CICS transaction (a BMS map driven by one `CO*C` COBOL program) maps
to one Next.js/Material UI page and one REST endpoint. The **backend service is
shared by domain**: several related programs consolidate into one `*_service.py`
module (see the consolidation note below the table), so the `Backend Service`
column is intentionally *many-to-one*. Backend services are the `*_service.py`
modules under `backend/app/services/`; their HTTP routers are the matching files
under `backend/app/api/v1/`. REST endpoints are shown relative to the versioned
API mount — every endpoint is served under the `/api/v1` prefix (for example
`POST /auth/login` is `POST /api/v1/auth/login`); see the
[API reference](./api-reference.md) for the full contract.

| Legacy Tx | BMS Map | COBOL Program | Frontend Route | REST Endpoint | Backend Service |
| :-------- | :------ | :------------ | :------------- | :------------ | :-------------- |
| `CC00` | `COSGN00` | `COSGN00C` | `/signon` | `POST /auth/login` | `auth_service` |
| `CM00` | `COMEN01` | `COMEN01C` | `/menu` | `GET /menu` | `menu_service` |
| `CA00` | `COADM01` | `COADM01C` | `/admin` | `GET /admin/menu` | `menu_service` |
| `CAVW` | `COACTVW` | `COACTVWC` | `/accounts/view` | `GET /accounts/{acctId}` | `account_service` |
| `CAUP` | `COACTUP` | `COACTUPC` | `/accounts/update` | `PUT /accounts/{acctId}` | `account_service` |
| `CCLI` | `COCRDLI` | `COCRDLIC` | `/cards` | `GET /cards` | `card_service` |
| `CCDL` | `COCRDSL` | `COCRDSLC` | `/cards/view` | `GET /cards/{cardNum}` | `card_service` |
| `CCUP` | `COCRDUP` | `COCRDUPC` | `/cards/update` | `PUT /cards/{cardNum}` | `card_service` |
| `CT00` | `COTRN00` | `COTRN00C` | `/transactions` | `GET /transactions` | `transaction_service` |
| `CT01` | `COTRN01` | `COTRN01C` | `/transactions/view` | `GET /transactions/{tranId}` | `transaction_service` |
| `CT02` | `COTRN02` | `COTRN02C` | `/transactions/add` | `POST /transactions` | `transaction_service` |
| `CR00` | `CORPT00` | `CORPT00C` | `/reports` | `GET /reports/transactions` | `report_service` |
| `CB00` | `COBIL00` | `COBIL00C` | `/billpay` | `POST /billpay` | `billpay_service` |
| `CU00` | `COUSR00` | `COUSR00C` | `/users` | `GET /admin/users` | `user_admin_service` |
| `CU01` | `COUSR01` | `COUSR01C` | `/users/add` | `POST /admin/users` | `user_admin_service` |
| `CU02` | `COUSR02` | `COUSR02C` | `/users/update` | `PUT /admin/users/{userId}` | `user_admin_service` |
| `CU03` | `COUSR03` | `COUSR03C` | `/users/delete` | `DELETE /admin/users/{userId}` | `user_admin_service` |

> **Admin gating:** the admin rows — `CA00` (Admin Menu) and `CU00`–`CU03` (user
> administration) — require an administrator identity (`user_type='A'`). This
> replaces the legacy `COCOM01Y` COMMAREA role check and is enforced on both the
> server (via `Depends(require_admin)`) and the client (route guards). The exact
> menu and admin-user paths are those declared in
> `backend/app/api/v1/menu.py` and `backend/app/api/v1/users.py`.

**Service consolidation (many-to-one).** Related online programs share one
domain service, so the program → service mapping above is deliberately
*many-to-one*:

| Backend Service | Consolidated COBOL programs (screens) |
| :-------------- | :------------------------------------ |
| `auth_service` | `COSGN00C` (CC00) |
| `menu_service` | `COMEN01C` (CM00) + `COADM01C` (CA00) |
| `account_service` | `COACTVWC` (CAVW) + `COACTUPC` (CAUP) |
| `card_service` | `COCRDLIC` (CCLI) + `COCRDSLC` (CCDL) + `COCRDUPC` (CCUP) |
| `transaction_service` | `COTRN00C` (CT00) + `COTRN01C` (CT01) + `COTRN02C` (CT02) |
| `report_service` | `CORPT00C` (CR00) |
| `billpay_service` | `COBIL00C` (CB00) |
| `user_admin_service` | `COUSR00C`–`COUSR03C` (CU00–CU03) |

**Additional operations (no 1:1 legacy screen).** Beyond the 17 screen
endpoints above, the API exposes three read/support operations under `/api/v1`
that have no dedicated legacy BMS map, plus two unversioned health probes. They
are disclosed here for a complete, bidirectional inventory:

| Operation | Purpose | Origin |
| :-------- | :------ | :----- |
| `POST /auth/logout` | Server-side session revocation (session-based auth baseline, AAP §0.8.4) | Modern session infrastructure (no legacy screen) |
| `GET /billpay/{acctId}` | Available-credit lookup backing the bill-pay screen (`limit − balance`) | Read half of the `COBIL00C` bill-pay flow |
| `GET /admin/users/{userId}` | Single-user fetch that populates the Update-User and Delete-User forms | Read half of `COUSR02C` / `COUSR03C` |
| `GET /health`, `GET /health/ready` | Unversioned liveness + DB-readiness probes | Modern operational infrastructure |

This yields **20 operations under `/api/v1`** (the 17 screen endpoints above plus
the three read/support operations) and **2 unversioned health probes** — **22
HTTP operations in total**.

## 2. Batch Programs

Each in-scope batch COBOL program becomes one idempotent Python module under
`batch/jobs/`, invoked through the Typer CLI (`batch/cli.py`) and sequenced by
`batch/orchestration/batch_chain.py`. The entry function is the callable that
runs the job. The legacy job (JCL member) that historically drove each program
is listed for cross-reference.

| Legacy Program | Legacy Job (JCL) | Modern Module | Entry Function |
| :------------- | :--------------- | :------------ | :------------- |
| `CBTRN02C` | `POSTTRAN` | `batch/jobs/post_transactions.py` | `PostTransactions` |
| `CBACT04C` | `INTCALC` | `batch/jobs/interest_calc.py` | `CalculateInterest` |
| `CBSTM03A` / `CBSTM03B` | `CREASTMT` | `batch/jobs/statement_gen.py` | `GenerateStatements` |
| `CBACT01C` | `ACCTFILE` (print) | `batch/jobs/print_account.py` | `PrintAccounts` |
| `CBACT02C` | `CARDFILE` (print) | `batch/jobs/print_card.py` | `PrintCards` |
| `CBACT03C` | `XREFFILE` (print) | `batch/jobs/print_xref.py` | `PrintCardXref` |
| `CBCUS01C` | `CUSTFILE` (print) | `batch/jobs/print_customer.py` | `PrintCustomers` |
| `CBTRN01C` | (daily-tran read) | `batch/jobs/read_daily_tran.py` | `ReadDailyTransactions` |
| `CBTRN03C` | (detail report) | `batch/jobs/tran_detail_report.py` | `ReportTransactionDetail` |
| (SORT) | `COMBTRAN` + `REPROCT.ctl` | `batch/jobs/combine_tran.py` | `CombineTransactions` |
| (IDCAMS) | `TRANBKP` | `batch/jobs/backup_tran.py` | `BackupTransactions` |
| `CSUTLDTC` | (date utility) | `backend/app/utils/date_utils.py` | (date validation) |

> The full batch chain order (`CLOSEFIL → … → OPENFIL`) and per-job semantics —
> posting codes, interest truncation, and statement formats — are documented in
> the [batch guide](./batch.md).

### Data Loaders

The seed loaders under `batch/loaders/` populate each table from the
`../app/data` sample datasets, re-expressing the legacy IDCAMS `REPRO` load jobs.
`USRSEC` ships EBCDIC-only, so `init_users.py` decodes it (or regenerates the
seed users) and **hashes** the plaintext password before it reaches the `users`
table.

| Loader Module | Seed Source (`app/data`) | Legacy Load JCL | Table |
| :------------ | :----------------------- | :-------------- | :---- |
| `init_users.py` | `EBCDIC/USRSEC.PS` | `DUSRSECJ` | `users` |
| `load_accounts.py` | `ASCII/acctdata.txt` | `ACCTFILE` | `accounts` |
| `load_cards.py` | `ASCII/carddata.txt` | `CARDFILE` | `cards` |
| `load_customers.py` | `ASCII/custdata.txt` | `CUSTFILE` | `customers` |
| `load_xref.py` | `ASCII/cardxref.txt` | `XREFFILE` | `card_xref` |
| `load_transactions.py` | `ASCII/dailytran.txt` | `TRANFILE` | `transactions` |
| `load_disclosure_groups.py` | `ASCII/discgrp.txt` | `DISCGRP` | `disclosure_group` |
| `load_tcatbal.py` | `ASCII/tcatbal.txt` | `TCATBALF` | `tran_category_balance` |
| `load_tran_categories.py` | `ASCII/trancatg.txt` | `TRANCATG` | `transaction_category` |
| `load_tran_types.py` | `ASCII/trantype.txt` | `TRANTYPE` | `transaction_type` |

## 3. Copybooks to Models and Schemas

Record-layout copybooks in [`../app/cpy/`](../app/cpy) become SQLAlchemy ORM
models (one file per table under `backend/app/models/`) plus their matching
Pydantic request/response schemas under `backend/app/schemas/`. Non-record
copybooks (communication area, screen/message layouts, and date work areas)
become the corresponding cross-cutting constructs and carry no table of their
own.

| Copybook | Legacy Record | Modern Model | Table |
| :------- | :------------ | :----------- | :---- |
| `CVACT01Y` | `ACCOUNT-RECORD` | `backend/app/models/account.py` | `accounts` |
| `CVACT02Y` | `CARD-RECORD` | `backend/app/models/card.py` | `cards` |
| `CVACT03Y` | `CARD-XREF-RECORD` | `backend/app/models/card_xref.py` | `card_xref` |
| `CVCUS01Y` | `CUSTOMER-RECORD` | `backend/app/models/customer.py` | `customers` |
| `CUSTREC` | `CUSTOMER-RECORD` (legacy alternate layout) | `backend/app/models/customer.py` (consolidated with `CVCUS01Y`) | `customers` |
| `CSUSR01Y` | `SEC-USER-DATA` | `backend/app/models/user.py` | `users` |
| `CVTRA05Y` (+ `CVTRA06Y` daily) | `TRAN-RECORD` / `DALYTRAN-RECORD` | `backend/app/models/transaction.py` | `transactions` |
| `CVTRA01Y` | `TRAN-CAT-BAL-RECORD` | `backend/app/models/tran_category_balance.py` | `tran_category_balance` |
| `CVTRA02Y` | `DIS-GROUP-RECORD` | `backend/app/models/disclosure_group.py` | `disclosure_group` |
| `CVTRA03Y` | `TRAN-TYPE-RECORD` | `backend/app/models/transaction_type.py` | `transaction_type` |
| `CVTRA04Y` | `TRAN-CAT-RECORD` | `backend/app/models/transaction_category.py` | `transaction_category` |
| `COCOM01Y` | `CARDDEMO-COMMAREA` | `backend/app/core/dependencies.py` — session/JWT identity + role (replaces COMMAREA propagation) | — |
| `COMEN02Y`, `COADM02Y`, `COTTL01Y`, `COSTM01`, `CSDAT01Y`, `CSMSG01Y`, `CSMSG02Y`, `CSSETATY`, `CSSTRPFY` | Screen / message / attribute layouts | Pydantic `backend/app/schemas/*` + constants + `frontend/src/types/*` | — |
| `CSUTLDPY`, `CSUTLDWY` | Date work areas | `backend/app/utils/date_utils.py` | — |
| `CVTRA07Y` | `REPORT-NAME-HEADER` (report header layout) | `backend/app/services/report_service.py` + `batch/jobs/tran_detail_report.py` (report headers) | — |
| `CVCRD01Y` | `CC-WORK-AREAS` (card-screen work areas + AID / PF-key constants) | `frontend/src/app/cards/*` pages + PF-key → button/keyboard mapping | — |
| `CSLKPCDY` | US phone / state / ZIP lookup-edit codes | `backend/app/utils/validators.py` (field validation) | — |
| `UNUSED1Y` | `UNUSED-DATA` (intentionally unused placeholder) | none — **not ported** (no target artifact) | — |

> Symbolic-map copybooks in [`../app/cpy-bms/`](../app/cpy-bms) supply the exact
> screen field names, lengths, and attributes that drive the TypeScript
> interfaces and form validation on the frontend. Column types, keys, and
> numeric precision for every table are catalogued in the
> [data model](./data-model.md).

## 4. VSAM Datasets to Tables and Repositories

Each VSAM KSDS (or sequential dataset) becomes one PostgreSQL table and one
repository module under `backend/app/repositories/`. Repositories encapsulate
all data access, replacing the COBOL `READ` / `STARTBR` / `READNEXT` / `REWRITE`
verbs with SQLAlchemy queries.

| VSAM / Dataset | PostgreSQL Table | Repository |
| :------------- | :--------------- | :--------- |
| `ACCTDAT` | `accounts` | `backend/app/repositories/account_repo.py` |
| `CARDDAT` | `cards` | `backend/app/repositories/card_repo.py` |
| `CARDXREF` | `card_xref` | `backend/app/repositories/xref_repo.py` |
| `CUSTDAT` (customers) | `customers` | `backend/app/repositories/customer_repo.py` |
| `TRANSACT` | `transactions` | `backend/app/repositories/transaction_repo.py` |
| `TCATBALF` | `tran_category_balance` | `backend/app/repositories/tcatbal_repo.py` |
| `DISCGRP` | `disclosure_group` | `backend/app/repositories/discgrp_repo.py` |
| `TRANTYPE` | `transaction_type` | `backend/app/repositories/trantype_repo.py` |
| `TRANCATG` | `transaction_category` | `backend/app/repositories/trancat_repo.py` |
| `USRSEC` (EBCDIC-only) | `users` | `backend/app/repositories/user_repo.py` |

> `USRSEC` ships only as an EBCDIC dataset, so `batch/loaders/init_users.py`
> decodes it (or regenerates the seed users) and **hashes** the legacy plaintext
> password before it ever reaches the `users` table — plaintext is never stored
> or returned.

## 5. Concept Mapping

Cross-cutting mainframe concepts do not map to a single file; they map to a
modern pattern applied throughout the stack.

| Legacy concept | Modern equivalent |
| :------------- | :---------------- |
| CICS COMMAREA (`COCOM01Y`) identity / role propagation | Stateless session / JWT via `Depends(get_current_user)` / `Depends(require_admin)` |
| BMS symbolic-map copybooks ([`../app/cpy-bms/*.CPY`](../app/cpy-bms)) | TypeScript interfaces (`frontend/src/types/*`) + Material UI form fields and validation |
| BMS 3270 maps ([`../app/bms/*.bms`](../app/bms)) | Next.js / Material UI page components (redesigned per Material Design 3) |
| JCL job chain + PROC | `batch/orchestration/batch_chain.py` + Typer CLI (`batch/cli.py`) |
| VSAM DD names / CICS file control | Repositories + async SQLAlchemy session (connection from `DATABASE_URL`) |
| VSAM alternate index (AIX) | PostgreSQL `UNIQUE` / secondary index |
| LE date services (CEEDAYS via `CSUTLDTC`) | `backend/app/utils/date_utils.py` |
| CICS record locking | Database transaction with `SELECT ... FOR UPDATE` |

## 6. Out-of-Scope Legacy Artifacts

The following legacy artifacts are intentionally **not** ported (there is no
target equivalent, or they are add-ons outside the base application). They
remain reference-only where present, and several are simply absent from this
base-application checkout:

- **Credit Card Authorizations (IMS/DB2/MQ add-on)** — transactions `CPVS`,
  `CPVD`, `CP00`; programs `COPAUS0C`, `COPAUS1C`, `COPAUA0C`; batch `CBPAUP0J`.
  The `app/app-authorization-ims-db2-mq/` directory is **absent** from this
  checkout.
- **DB2 Transaction-Type Management** — transactions `CTTU`, `CTLI`; programs
  `COTRTUPC`, `COTRTLIC`; batch `CREADB21`, `TRANEXTR`, `MNTTRDB2`.
- **MQ Account Extracts** — transactions `CDRD`, `CDRA`; programs `CODATE01`,
  `COACCT01`.
- **Additional JCL utilities** — FTP, TXT2PDF, DB2/IMS load-unload, and Internal
  Reader helpers.
- **Mainframe infrastructure** — RACF security definitions, CICS region
  configuration, and the CICS RDO resource definitions in
  [`../app/csd/`](../app/csd). Preserved as reference; not ported.
- **ASSEMBLER utilities** — `MVSWAIT` and `COBDATFT`. Where equivalent behavior
  (timer control, date formatting) is needed, it is reproduced in Python rather
  than translated line by line.
- **Build and diagram artifacts** — [`../samples/`](../samples) (mainframe
  compile/runtime JCL and zips) and [`../diagrams/`](../diagrams) (flow/screen
  PNGs). These are legacy reference material and are **not** part of the modern
  build.

All of the above legacy material — together with every ported COBOL program,
copybook, BMS map, and dataset — is preserved unchanged under
[`../app/`](../app).

## 7. Target Inventory and Scope Reconciliation

The modern target trees add files beyond the specific paths named in the AAP
feature description. Every such file is **either** matched by an AAP trailing
wildcard (§0.2.1 / §0.5.6) **or** mandated by a specific remediation finding —
none is a silent scope expansion, and none can be removed without dropping
required behaviour. The categories are:

| Category | Example files | Authorization |
| :------- | :------------ | :------------ |
| Additional Alembic migrations | `0003_drop_card_cvv`, `0004_add_account_group_fk`, `0005_add_user_session_version`, `0006_drop_account_groups`, `0007_repair_daily_staging_status` | AAP `backend/alembic/versions/*.py` wildcard; each realizes a required schema correction (CVV removal, `group_id` handling, session versioning, daily-staging repair) |
| Security / hardening modules | `core/rate_limiter.py`, `core/log_masking.py`, `core/correlation.py`, `utils/csv_safety.py`, `jobs/output_safety.py` | AAP `core/*.py`, `utils/*.py`, `jobs/*.py` wildcards; mandated by the Ochs no-hardcoding / sanitize-input rule and findings on rate limiting, PII redaction, CSV-injection safety, and correlation IDs |
| Split dependency manifest | `backend/requirements-dev.txt` | Runtime-vs-dev dependency split so the production image installs runtime deps only |
| Additional tests | files under `backend/tests/`, `batch/tests/`, `frontend/__tests__/` | AAP `tests/**` / `__tests__/**` wildcards; cover the concurrency, race-safety, idempotency, staging-status, and header behaviours added during remediation |

Current tracked target-file counts (excluding the reference-only `app/` tree):
**backend 124, frontend 81, batch 51, docs 6, root config 5**. Test modules:
**backend 36, batch 14, frontend 26**. These counts are stated here as the
authoritative, honest inventory; they exceed the AAP's individually-named paths
only by the wildcard-permitted and finding-mandated files catalogued above.

## 8. Related Documentation

- [Architecture](./architecture.md) — system design, layering, and migration mapping.
- [API Reference](./api-reference.md) — the full REST endpoint contract.
- [Data Model](./data-model.md) — tables, keys, and numeric precision.
- [Batch Jobs](./batch.md) — the Python CLI batch chain and per-job detail.
- [Project README](../README.md) — build, run, and quick-start instructions.
