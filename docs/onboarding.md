# CardDemo — Developer Onboarding Guide

Welcome to **CardDemo**. This repository is a **Java 25 (LTS) / Spring Boot 3.5.16**
migration of the original AWS CardDemo mainframe credit-card management application. The
migration preserves the existing business behavior while replacing the IBM z/OS
**COBOL / CICS / VSAM / JCL / BMS** runtime with an idiomatic layered Spring Boot stack
(Spring MVC + Thymeleaf, Spring Data JPA over PostgreSQL, Spring Batch, and Spring
Security). The **original COBOL / CICS / JCL / BMS sources are retained read-only under
[`legacy/`](../legacy)** for reference and traceability — **you do not need a mainframe,
a running COBOL environment, or any z/OS tooling to build, run, or modify the Java
application.**

This guide takes you from a **clean machine to a running, modifiable application**. It
covers prerequisites, cloning, database configuration (secrets are always supplied through
the environment — never hardcoded), building and running, verifying observability, a brief
tour of the domain, the pitfalls new developers hit most often, and how to extend the
project safely.

## How this guide fits with the rest of the documentation

This onboarding guide is intentionally **task-oriented and deep** on setup, troubleshooting,
and extension. It **complements — it does not duplicate — the root
[`README.md`](../README.md)**. Reach for each document as follows:

| Document | Use it for |
| :------- | :--------- |
| [`README.md`](../README.md) | Project overview, the target technology stack, the build/run/test workflow, and the full **Application Inventory** tables (every online transaction and batch job). |
| **This guide** (`docs/onboarding.md`) | Clean-machine setup, environment configuration, build/run/test workflow, common pitfalls, and how to extend the codebase. |
| [`docs/decision-log.md`](./decision-log.md) | Every non-trivial migration decision with its alternatives, rationale, and risks (the *why* behind the design). |
| [`docs/traceability-matrix.md`](./traceability-matrix.md) | The bidirectional COBOL-construct → Java-artifact mapping (programs, copybooks, BMS maps, JCL jobs). |
| [`docs/architecture/`](./architecture/) | Mermaid **before / after** architecture diagrams — the original z/OS state and the target Spring Boot state. |
| [`blitzy-deck/index.html`](../blitzy-deck/index.html) | A reveal.js executive-summary presentation of the migration. It loads reveal.js, Mermaid, and Lucide from the jsDelivr CDN (SRI-pinned, under a restrictive CSP), so it needs network access when opened. |

> **Note on migration status.** CardDemo was migrated in a single phase, and the Java
> application has been generated in full: it builds, boots, applies its Flyway schema, and
> serves the sign-on flow and all online/batch functions today. This onboarding guide describes
> the **end-to-end developer workflow** for that migrated application. The commands and paths
> below are the stable, runnable contract.

---

## 1. Prerequisites

You need only a handful of standard tools on a clean machine. **No mainframe and no COBOL
toolchain are required.**

| Tool | Version | Why | Verify |
| :--- | :------ | :-- | :----- |
| **JDK** | **25** (LTS) | Compile and run the application; the build targets `--release 25`. | `java -version` should report `25` (for example `openjdk version "25"`). |
| **Git** | any recent | Clone the repository. | `git --version` |
| **Docker** | Docker Desktop or Engine | **Required** for the Testcontainers-based integration tests (they start a real PostgreSQL container), and the easiest way to run PostgreSQL locally for development. | `docker --version` and `docker info` (the daemon must be running). |
| **PostgreSQL** | **18.x** (supported floor **16**) | The relational store that replaces VSAM. Needed **only if you are not using Docker** to run the database. | `psql --version` |
| **Maven** | *(none to install)* | Build orchestration. **You do not install Maven separately** — the bundled **Maven Wrapper** (`./mvnw` on macOS/Linux, `mvnw.cmd` on Windows) pins **Maven 3.9.9**. | `./mvnw -version` |
| **`unzip`** | any recent | Used by the **Maven Wrapper** on the **first** `./mvnw` run to unpack the pinned Maven 3.9.9 `.zip`. Pre-installed on macOS and most Linux distributions; on minimal images (slim CI/dev containers) install it (e.g. `apt-get install -y unzip`). Not needed once the distribution is cached. See [Common pitfalls](#7-common-pitfalls--troubleshooting). | `unzip -v` |

Notes:

* **JDK 25 must be on your `PATH`.** If `java -version` reports a different major version,
  fix your `JAVA_HOME` / `PATH` before continuing — a mismatched JDK is the most common
  cause of build/toolchain errors (see [Common pitfalls](#7-common-pitfalls--troubleshooting)).
* A **browser** is all you need to use the application once it is running; the UI is
  server-rendered (Thymeleaf) and preserves the original 24×80 screen contract.

---

## 2. Get the code

Clone the repository and change into it:

```shell
git clone <your-fork-or-repo-url> carddemo
cd carddemo
```

Everything below is run from the repository root (the directory that contains `pom.xml`
and `mvnw`).

### Repository layout

The project is a single Spring Boot Maven module. New Java artifacts live under
`src/main/java/com/aws/carddemo/**`; the original mainframe sources are retained read-only
under `legacy/`.

```
carddemo/
├── pom.xml                         Maven build (Spring Boot 3.5.16 parent BOM)
├── mvnw, mvnw.cmd, .mvn/           Maven Wrapper (pins Maven 3.9.9)
├── README.md                       Project overview + application inventory
├── src/main/java/com/aws/carddemo/
│   ├── CardDemoApplication.java    Spring Boot entry point
│   ├── config/                     DataSource, Batch, Security, Observability, Web configuration
│   ├── domain/                     JPA @Entity classes (one per VSAM file) + enums
│   ├── dto/                        CardDemoContext (COMMAREA replacement), screen forms, feed/report models
│   ├── repository/                 Spring Data JPA repositories (one per VSAM file)
│   ├── service/                    @Service classes (one per COBOL program; methods mirror numbered paragraphs)
│   │   └── online/                 Online (CICS transaction) services
│   ├── web/                        Spring MVC controllers (one route per CICS transaction id)
│   │   └── controller/
│   ├── batch/                      Spring Batch @Configuration jobs (one per business JCL job)
│   ├── exception/                  FILE STATUS / CICS RESP exception hierarchy + @ControllerAdvice handler
│   ├── security/                   UserDetailsService + role authorities (replaces signon + RACF)
│   └── util/                       Date conversion, decimal (BigDecimal) helpers, fixed-width record mappers
├── src/main/resources/
│   ├── application.yml             Base configuration (environment-driven; no secrets)
│   ├── application-dev.yml         `dev` profile (local development)
│   ├── application-test.yml        `test` profile (used by the test suite / Testcontainers)
│   ├── db/migration/               Flyway migrations: V0 batch metadata, V1 schema, V2 reference/seed data, V3 indexes, V4 card-xref unique
│   ├── logback-spring.xml          Structured JSON logging with correlation IDs
│   └── templates/                  Thymeleaf screens (preserve the BMS 24×80 field/label/PF-key contract)
├── src/test/java/                  JUnit 5 unit + Testcontainers integration + parity tests
├── legacy/                         Original COBOL / CICS / JCL / BMS / CPY / CSD / data (READ-ONLY)
├── docs/                           decision-log.md, traceability-matrix.md, onboarding.md (this file), architecture/
├── blitzy-deck/                    reveal.js executive-summary deck (index.html + references/)
├── observability/                  grafana-dashboard.json (dashboard template)
├── diagrams/                       Legacy flow diagrams and screen captures (reference)
└── samples/                        Legacy sample JCL (reference only)
```

The layered package structure mirrors the classic three-tier z/OS design: `web` (replaces
the 3270/BMS presentation and CICS transaction handlers) → `service` (business logic, one
`@Service` per COBOL program) → `repository` (replaces VSAM I/O) → PostgreSQL, with `batch`
replacing JCL/JES2 and `config` / `security` / `exception` / `util` handling cross-cutting
concerns. See [`docs/architecture/`](./architecture/) for the before/after diagrams and
[`docs/traceability-matrix.md`](./traceability-matrix.md) for the full construct-by-construct
mapping.

---

## 3. Configure the database

CardDemo needs a PostgreSQL database. Configuration is **environment-driven and contains no
hardcoded secrets** — the database connection is supplied entirely through environment
variables, and the application **never** embeds credentials in source code or in
`application.yml`.

### 3.1 Start PostgreSQL

The quickest path on a clean machine is Docker. Start a local PostgreSQL 18 container,
**supplying your own password** from your shell or secret store (do **not** commit it):

```shell
# Choose a password and export it in YOUR shell (never commit it):
export CARDDEMO_DB_PASSWORD='choose-a-strong-local-password'

docker run --name carddemo-pg \
  -e POSTGRES_DB=carddemo \
  -e POSTGRES_USER=carddemo \
  -e POSTGRES_PASSWORD="$CARDDEMO_DB_PASSWORD" \
  -p 5432:5432 \
  -d postgres:18
```

This creates an empty database named `carddemo` owned by the `carddemo` role. **That is the
only manual database step** — the application creates every table and loads all reference
data itself on startup via Flyway (see [§3.4](#34-flyway-runs-automatically)).

> Prefer a locally installed PostgreSQL instead of Docker? That works too — just create an
> empty `carddemo` database and a role to own it, then point the environment variables below
> at it. PostgreSQL **18.x** is the target; **16** is the supported floor.

### 3.2 Set the datasource environment variables

Spring Boot reads the datasource from these three environment variables. **Secrets come
from the environment, never from source or `application.yml`:**

| Environment variable | Purpose | Example (local dev) |
| :------------------- | :------ | :------------------ |
| `SPRING_DATASOURCE_URL` | JDBC URL of the PostgreSQL database | `jdbc:postgresql://localhost:5432/carddemo` |
| `SPRING_DATASOURCE_USERNAME` | Database user | `carddemo` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | *(supplied from your shell / secret manager)* |
| `CARDDEMO_SEED_PASSWORD` | **Required.** Cleartext password seeded for every demo `USRSEC` principal (`ADMIN001`&hellip;, `USER0001`&hellip;) by the `V2` migration, via the Flyway placeholder `${carddemo_seed_password}` (bound in `application.yml` with **no default** &mdash; fails closed). **Exactly 8 uppercase characters** (`usr_pwd` is `CHAR(8)`, origin `SEC-USR-PWD PIC X(08)`; the signon provider uppercases before the fixed-width compare). It becomes the sign-on password for the seeded logins. | *(supplied from your shell / secret manager; 8 uppercase chars)* |

> **You must set `CARDDEMO_SEED_PASSWORD` before the first migration, `./mvnw spring-boot:run`,
> or `./mvnw clean verify`** (§4/§5). Without it the `V2` seed migration fails closed and both a
> clean run and the test suite abort. This is required **in addition to** the datasource
> variables. The integration tests run the same seed migration under **Testcontainers**, so
> `CARDDEMO_SEED_PASSWORD` is required for `./mvnw verify` even though the `SPRING_DATASOURCE_*`
> variables are not (see §3.3).

**macOS / Linux (bash / zsh):**

```shell
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/carddemo'
export SPRING_DATASOURCE_USERNAME='carddemo'
export SPRING_DATASOURCE_PASSWORD="$CARDDEMO_DB_PASSWORD"   # reuse the value from §3.1; do not hardcode
export CARDDEMO_SEED_PASSWORD='CDEMOPWD'                    # exactly 8 uppercase chars; seeds every demo USRSEC login; no default (fails closed)
```

**Windows (PowerShell):**

```powershell
$Env:SPRING_DATASOURCE_URL      = 'jdbc:postgresql://localhost:5432/carddemo'
$Env:SPRING_DATASOURCE_USERNAME = 'carddemo'
$Env:SPRING_DATASOURCE_PASSWORD = $Env:CARDDEMO_DB_PASSWORD   # from your secret store; do not hardcode
$Env:CARDDEMO_SEED_PASSWORD     = 'CDEMOPWD'                  # exactly 8 uppercase chars; seeds every demo USRSEC login; no default (fails closed)
```

> **Security rule (non-negotiable).** Never place a real password in `application.yml`, in
> `application-dev.yml` / `application-test.yml`, in source code, or in any file you commit.
> Always inject it through the environment (or a secret manager). See the "no hardcoded
> credentials" rationale in [`docs/decision-log.md`](./decision-log.md).

### 3.3 Spring profiles

Two Spring profiles tailor configuration to context:

* **`dev`** — local development. Activate it explicitly when running the app:

  ```shell
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```

* **`test`** — used automatically by the test suite; the integration tests provision their
  PostgreSQL through **Testcontainers**, so you generally do not set the
  `SPRING_DATASOURCE_*` variables for `./mvnw test` / `./mvnw verify`. You **do**, however,
  still need to export `CARDDEMO_SEED_PASSWORD` (§3.2): the integration tests apply the same
  `V2` seed migration inside their Testcontainers database, and that migration fails closed
  when the placeholder is unbound.

The base `application.yml` holds settings common to every profile; the profile-specific
`application-dev.yml` / `application-test.yml` layer on top.

### 3.4 Flyway runs automatically

You do **not** run any DDL by hand. On startup the application applies its
[**Flyway**](https://flywaydb.org/) migrations in order from
`src/main/resources/db/migration/`:

| Migration | Contents |
| :-------- | :------- |
| `V0__spring_batch_metadata.sql` | The Spring Batch metadata schema — the `BATCH_*` job/step tables and their sequences that Spring Batch's `JdbcJobRepository` requires before any batch job can run or restart. |
| `V1__schema.sql` | The relational schema — one table per VSAM file, with primary keys and composite keys preserved (`NUMERIC`, `CHAR(n)`, `NUMERIC(n+2,2)` mappings from the copybook layouts). |
| `V2__reference_data.sql` | Reference and sample seed data, **including the demo application users** (see [§4.3](#43-run-the-application)). |
| `V3__indexes.sql` | Secondary indexes that reproduce the legacy VSAM alternate indexes (for example the transaction-by-card index). |
| `V4__card_xref_unique_card_num.sql` | Adds the single-key `UNIQUE` constraint (`uk_card_xref_card_num`) on `card_xref(xref_card_num)`, in place before Hibernate `ddl-auto=validate` verifies the JPA entity mappings at startup. |

Because Flyway initializes everything, the **only** manual database step is creating an
empty database ([§3.1](#31-start-postgresql)); a freshly created database is fully populated
the first time the application starts.

> **Flyway is pinned to a PostgreSQL 18–tested release — no compatibility warning.** The
> project overrides the Spring Boot 3.5.16 parent BOM's Flyway version (`11.7.2`, whose highest
> *tested* PostgreSQL is 17) to `11.20.3` through the `flyway.version` property in `pom.xml`.
> Flyway 11.20.3 marks PostgreSQL 18 as tested, so running against the target PostgreSQL 18.4
> server produces **no** "support has not been tested" warning and startup logs stay
> warning-free. Every migration (`V0`–`V4`) applies cleanly and idempotently on 16/17/18. The
> override stays within the Flyway 11.x major line the BOM already uses (API-stable, low-risk).
> Rationale and risk/mitigation are recorded in [`docs/decision-log.md`](./decision-log.md).

---

## 4. Build, test, and run

All commands use the **Maven Wrapper** (`./mvnw`), so no separate Maven install is needed.
On Windows, substitute `mvnw.cmd` for `./mvnw`.

### 4.1 Build and full verification

```shell
./mvnw clean verify
```

`clean verify` is the complete quality pipeline. It:

* compiles the sources with **`--release 25`** as a **zero-warning build** (`-Xlint:all` with
  `failOnWarning` — any compiler warning fails the build);
* runs the **unit tests** (`*Test`, via Surefire);
* runs the **Testcontainers integration and parity tests** (`*IT`, via Failsafe) against a
  real PostgreSQL container;
* enforces the **JaCoCo line-coverage gate** — the build fails below **80%** combined
  (unit + integration) line coverage (JaCoCo **0.8.15**);
* enforces the **OWASP dependency-check gate** — the build fails on any **HIGH or CRITICAL**
  CVE (CVSS ≥ 7), using dependency-check **12.2.2**.

> **Docker must be running** for `verify`, because the integration tests start a PostgreSQL
> container. If Docker is not available the integration tests fail fast — see
> [Common pitfalls](#7-common-pitfalls--troubleshooting).

> **OWASP / NVD note.** The dependency-check scan downloads the National Vulnerability
> Database (NVD) cache on its first run. To avoid NVD rate limits, provide an **NVD API key**
> through the `NVD_API_KEY` environment variable (the build reads it from that variable).
> **Never hardcode the key** in `pom.xml` or any committed file.

### 4.2 Run the tests only

Unit tests only (fast; no container required):

```shell
./mvnw test
```

Unit **plus** Testcontainers integration and parity tests, with the coverage and CVE gates:

```shell
./mvnw verify
```

### 4.3 Run the application

```shell
./mvnw spring-boot:run
```

To activate the local development profile explicitly:

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Make sure PostgreSQL is running and that both the `SPRING_DATASOURCE_*` variables **and**
`CARDDEMO_SEED_PASSWORD` are set ([§3.2](#32-set-the-datasource-environment-variables)) before
you start the app — on a fresh database the `V2` seed migration runs on first startup and
fails closed if `CARDDEMO_SEED_PASSWORD` is unset. Once it is up, open it in a
browser and sign on. As in the legacy system, two demo logins are provided as **seed data**
(loaded by the `V2` Flyway migration):

* **`ADMIN001`** — for **admin functions** (user administration).
* **`USER0001`** — for **back-office (regular user) functions**.

The sign-on password for **both** seeded logins is the value you exported as
`CARDDEMO_SEED_PASSWORD` (§3.2) — the migration seeds that exact cleartext value (uppercased,
8 characters) into every demo `USRSEC` principal.

These are seeded **application user** records. The **database** credentials, by contrast,
are always supplied through the `SPRING_DATASOURCE_*` environment variables and are never
embedded in source or configuration.

### 4.4 Batch jobs

The batch workload that ran as JCL jobs on the mainframe is implemented as **Spring Batch
`Job`s** — chunk-oriented `reader → processor → writer` steps, with `Tasklet` steps for
single-action utilities. The core business jobs are, for example, **`PostTransactionJob`**
(daily transaction posting), **`InterestCalcJob`** (monthly interest calculation), and
**`StatementJob`** (statement generation).

Jobs are launched through the Spring Batch job launcher, and JCL `PARM` values become Spring
Batch **`JobParameter`s** — for instance, the interest-calculation **processing date** is
supplied as a job parameter, preserving the original JCL semantics. Job-instance versioning
reproduces the legacy generation-data-group (GDG) output behavior.

**Launching a job.** The jobs do **not** auto-run on web startup (`spring.batch.job.enabled=false`
by default, so `./mvnw spring-boot:run` serves only the online app). Run one job explicitly by
launching the packaged jar in **non-web** mode, selecting the job by name and passing its
parameters as `name=value` arguments — for example the daily transaction posting job:

```bash
./mvnw -DskipTests package        # build the jar once
# PostgreSQL must be running and the section 3 env vars exported — the SPRING_DATASOURCE_*
# variables AND CARDDEMO_SEED_PASSWORD (the jar applies Flyway, incl. the V2 seed, at startup)
java -jar target/carddemo-1.0.0.jar \
  --spring.main.web-application-type=none \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=postTransactionJob \
  inputPath=/path/to/DALYTRAN.txt \
  rejectPath=/path/to/DALYREJS.txt
echo "RETURN-CODE = $?"
```

`--spring.batch.job.enabled=true` turns the launcher on for the run, `--spring.batch.job.name=<job>`
picks the job, and job parameters are the non-`--` `name=value` arguments;
`--spring.main.web-application-type=none` makes the JVM exit with a return code when the job
finishes. The **full table of every job name, its parameters, and its output artifact** is the
**Running batch jobs locally** subsection of [`README.md`](../README.md#batch-jobs) — for example
`interestCalcJob` (`processingDate`), `statementJob` (`textOutputPath`, `htmlOutputPath`),
`transactionReportJob` (`startDate`, `endDate` as `yyyy-MM-dd`, `outputPath`), and the read-only
print jobs (`accountPrintJob`, `cardPrintJob`, `xrefPrintJob`, `customerLoadJob`) which take no
parameters and emit structured log records.

**Return code (JCL RETURN-CODE contract).** In non-web mode the JVM exit status reproduces the
mainframe ladder so a scheduler can branch on it: **`0`** clean, **`4`** completed with business
rejects (e.g. `postTransactionJob` wrote to `rejectPath`), **`8`** failed/abended (e.g. a duplicate
transaction id — the COBOL `9999-ABEND` equivalent). Read it with `echo $?`.

**Restart.** A job instance is keyed by its **identifying** parameters. Relaunch a `FAILED` run with
the **same** parameters to resume that instance; change a parameter (or add `runId=$(date +%s)`) to
start a **new** instance. A job that already `COMPLETED` cannot be re-run with identical parameters,
guarding against reprocessing the same input twice.

**Abnormal termination & automatic recovery (finding F-02).** If a batch JVM is killed while a job
is running — a `SIGTERM` from the scheduler, a `kill -9`, an OOM, or a node crash — Spring Batch has
no chance to mark the run terminal, so the `JobExecution` is left `STARTED` (the metadata still says
"running" even though nothing is). By itself that **blocks restart**: relaunching the same instance
would fail with `JobExecutionAlreadyRunningException`, because Spring Batch refuses to start an
instance that appears to be already running. CardDemo recovers from this **automatically**:

- At job start, `BatchExecutionOwnerListener` stamps each `JobExecution`'s execution context with the
  **owning host name and process id** (`carddemo.owner.host` / `carddemo.owner.pid`). The stamp is
  persisted before the first chunk, so it survives an abrupt crash.
- On the **next** CLI batch launch, `BatchExecutionRecovery` (an `ApplicationRunner` that runs
  *before* the Spring Batch job launcher) scans the running executions of the job being launched and
  **abandons** — marks `FAILED`, with a clear exit message — any execution left behind by a dead
  owner. The relaunch then resumes the instance normally. In practice you simply **relaunch the job
  on the same host** and it recovers itself; the log shows a `WARN` such as
  `Reconciled stale batch execution <id> of job '<name>' to FAILED (owner process dead)` followed by a
  normal completion and exit code `0`.

  This covers **both** `SIGTERM` and `kill -9`: because recovery happens at the *next startup*, it
  does not depend on any shutdown hook having run (a `kill -9` gives the dying JVM no chance to run
  one). A best-effort `DisposableBean` shutdown marker additionally tries to mark this JVM's own
  running executions `STOPPED` on a *graceful* stop, but the startup reconciliation is the
  authoritative path.

- **Why this never disturbs a genuinely running job.** The reconciler abandons an execution only when
  **all** of the following hold: the owner **host equals this host**, the owner **pid differs from
  this JVM's pid**, and the owner **process is no longer alive** (`ProcessHandle.of(pid)`). A live
  owner (a real concurrent run), an execution owned by **another host**, or one with **no owner
  stamp** is left untouched. Consequently the concurrency guarantee is fully preserved — a second
  launch of an instance that is *actually* running is still refused with
  `JobExecutionAlreadyRunningException`. (The four-part discriminator and its non-regression are
  proven by `BatchExecutionRecoveryIT`, which races real OS processes.)

**Manual recovery (rare edge cases).** The automatic path handles same-host recovery. Manual
intervention is only needed when the reconciler *intentionally* declines to act — most notably a
stale execution whose owner **host is a different (now-decommissioned) machine**, which this host
must not assume is dead:

- Preferred: relaunch the job **on the host that originally ran it** — that host's reconciler will
  recognize its own dead pid and clean the execution.
- Otherwise, abandon the stale execution explicitly. Spring Batch's `JobOperator` bean is
  auto-configured; an operator task can call `jobOperator.stop(executionId)` (best-effort) and then
  `jobOperator.abandon(executionId)` to move a non-running `STARTED`/`STOPPED` execution to
  `ABANDONED`, after which the instance is restartable with its original parameters. As a
  DBA-level last resort the same effect is achieved by setting the stale row's `status`/`exit_code`
  to `FAILED` (with a non-null `end_time`) in `BATCH_JOB_EXECUTION` (and its `BATCH_STEP_EXECUTION`
  rows) — exactly what the automatic reconciler does on your behalf in the common case.

For the **complete JCL-job → Spring Batch job mapping** (including which COBOL program each
job derives from), see [`docs/traceability-matrix.md`](./traceability-matrix.md) and the
**Batch** table in [`README.md`](../README.md).

---

## 5. Observability

Observability ships with the application from the start; the **configuration artifacts are
in the repository now**, and the **live HTTP endpoints are exercised once the application is
running** ([§4.3](#43-run-the-application)). Everything below is verifiable locally.

| Concern | How to use it |
| :------ | :------------ |
| **Health / readiness** | `GET /actuator/health` — the Spring Boot Actuator health endpoint (liveness and readiness). |
| **Metrics (Prometheus)** | `GET /actuator/prometheus` — the Prometheus scrape endpoint exposed by Micrometer. |
| **Structured logging** | JSON logging with **correlation IDs**, configured in [`src/main/resources/logback-spring.xml`](../src/main/resources/logback-spring.xml). |
| **Distributed tracing** | Micrometer Tracing propagates a trace/correlation id across service boundaries so logs and traces line up. |
| **Dashboard** | A ready-to-import Grafana dashboard template is provided at [`observability/grafana-dashboard.json`](../observability/grafana-dashboard.json). |

Quick local check once the app is running:

```shell
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/prometheus | head
```

To visualize metrics, point a Prometheus instance at `/actuator/prometheus` and import
`observability/grafana-dashboard.json` into Grafana.

---

## 6. Domain context at a glance

CardDemo is a **credit-card management application**. There are two kinds of users:

* **Regular users** — perform back-office user functions (view/update accounts and cards,
  list/view/add transactions, bill payment, request reports).
* **Admin users** — perform administrative functions (list/add/update/delete application
  users).

The core persistent entities are **Account**, **Card**, **CardXref** (the customer ↔ account
↔ card cross-reference), **Customer**, and **Transaction**, supported by reference data —
**TransactionType**, **TransactionCategory**, **DisclosureGroup**, and
**TransactionCategoryBalance** — and the **UserSecurity** store that backs sign-on.

In the migrated application:

* each **CICS transaction id** maps to a **Spring MVC controller route**;
* each **COBOL program** maps to a **`@Service`** whose methods mirror the program's numbered
  paragraphs (preserving `PERFORM` / `EVALUATE` control flow);
* each **copybook** maps to exactly one **entity or DTO**; and
* each business **JCL job** maps to exactly one **Spring Batch job**.

This section is deliberately brief. For the **full inventory** — every online transaction and
every batch job — see the **Application Inventory** tables in [`README.md`](../README.md); for
the **before/after architecture** see [`docs/architecture/`](./architecture/); and for the
**construct-by-construct mapping** see [`docs/traceability-matrix.md`](./traceability-matrix.md).

---

## 7. Common pitfalls & troubleshooting

These are the issues new developers hit most often. Most are configuration or environment
problems, not code problems.

* **First `./mvnw` on a clean machine fails with "your Maven distribution might be
  compromised" → you are missing `unzip`.** The Maven Wrapper downloads the pinned Maven
  3.9.9 **`.zip`** and needs `unzip` to unpack it. When `unzip` is absent the wrapper falls
  back to the `.tar.gz` archive but still validates it against the **`.zip`** SHA-256 pinned
  in `.mvn/wrapper/maven-wrapper.properties`, so the checksum legitimately mismatches and the
  wrapper refuses to run. The security-flavoured message is real fail-safe behaviour, but the
  true cause is the missing `unzip`, not a tampered download. **Fix:** install `unzip`
  (`apt-get install -y unzip`, `dnf install -y unzip`, or `brew install unzip`) and re-run
  `./mvnw -version`. Once the distribution is cached under `~/.m2/wrapper/dists/` the wrapper
  no longer downloads or unpacks it, so this only affects the very first run.

* **Docker is not running → integration tests fail.** `./mvnw verify` starts a real
  PostgreSQL container through Testcontainers. If you see errors about not being able to
  connect to the Docker daemon (or containers failing to start), **start Docker first**
  (`docker info` should succeed). If you only need a fast inner loop, run `./mvnw test`
  (unit tests only) — it does not need Docker.

* **Missing or incorrect `SPRING_DATASOURCE_*` variables → startup / DataSource errors.**
  When running the app (`./mvnw spring-boot:run`), the three
  [datasource environment variables](#32-set-the-datasource-environment-variables) must be
  set and must point at a reachable PostgreSQL. Symptoms include `DataSource`/HikariCP
  connection failures or Flyway being unable to connect. Double-check the URL, username, and
  password, and that PostgreSQL is actually listening on the configured port.

* **Wrong JDK → build / toolchain errors.** The project targets **Java 25**. If `java -version`
  reports a different major version, fix `JAVA_HOME` / `PATH`. A mismatched JDK typically
  shows up as a release/toolchain error from the compiler plugin (which is configured for
  `--release 25`).

* **Monetary values are `BigDecimal`, never `double`/`float`.** Every packed-decimal COBOL
  field (`COMP-3` / `S9(n)V99`) maps to `java.math.BigDecimal` with a fixed scale. **Do not**
  introduce `double` or `float` for monetary math — that breaks the decimal-fidelity parity
  requirement. Rounding follows the **per-statement** COBOL semantics (truncation where the
  COBOL `COMPUTE` omits `ROUNDED`, half-up where it is present); the exact rounding mode for
  each computation is recorded in [`docs/decision-log.md`](./decision-log.md).

* **Fixed-width flat-file feeds must preserve their exact record layout.** The daily
  transaction feed (`DALYTRAN`, **350 bytes**) and the reject file (`DALYREJS`, **430 bytes**
  = 350-byte transaction + 80-byte reason) are byte-for-byte contracts. Use the provided
  `FixedWidthRecordMapper` — **do not** trim, reorder, or reformat fields, and do not treat
  these as CSV.

* **`CHAR(n)` columns carry trailing spaces.** Fixed-width `PIC X(n)` fields are stored as
  `CHAR(n)` to preserve trailing-space semantics. Account for this in comparisons and
  equality checks (trim intentionally and consistently only where the legacy behavior does),
  so string matches behave as they did on the mainframe.

* **The OWASP gate fails the build on HIGH/CRITICAL CVEs.** `./mvnw verify` fails on any CVE
  with **CVSS ≥ 7**. For reliable local scans, provide an **NVD API key** via the
  `NVD_API_KEY` environment variable to avoid NVD rate limiting — **never hardcode it**. The
  first scan downloads the NVD cache (slower); later scans reuse it. See
  [`docs/decision-log.md`](./decision-log.md) for the offline / rate-limit notes.

---

## 8. Extending the application

The migration's guiding principle is **preserve behavior and traceability**. When you extend
the codebase, mirror an existing artifact of the same kind, keep the original contract, cite
the legacy source, and add tests so the coverage gate stays green.

**Add a screen (online transaction).** Model it on an existing transaction end-to-end:

1. Add a **Thymeleaf template** under `src/main/resources/templates/` that preserves the
   BMS **24×80** field/label/color contract and the **PF-key** actions (PF3 = back,
   PF7/PF8 = page up/down, ENTER = submit).
2. Add a **form DTO** under `dto/` for the screen fields.
3. Add a **controller route** under `web/controller/` mapped to the transaction id, reading
   and writing the session-scoped `CardDemoContext` (the COMMAREA replacement).
4. Add or extend the **`@Service`** under `service/online/` that holds the business logic,
   keeping the original paragraph/control-flow structure.

**Add a batch job.** Create a new Spring Batch **`@Configuration`** under `batch/` defining a
`Job` with chunk-oriented `reader → processor → writer` steps (or a `Tasklet` for a
single-action utility). Preserve the original step topology and expose JCL `PARM`s as
`JobParameter`s.

**Add a repository / entity.** Add a JPA **`@Entity`** under `domain/` mapped to its table,
**preserving the primary/composite keys** exactly, and a Spring Data **repository** under
`repository/`. Use `BigDecimal` (with an explicit precision/scale) for any monetary field and
`CHAR(n)` semantics for fixed-width text.

In every case:

* **Preserve behavior** — no algorithmic changes, no schema denormalization, no "improvements"
  to business rules. Parity includes preserving existing quirks.
* **Preserve traceability** — add a Javadoc **origin tag** on generated classes citing the
  `legacy/…` source path, and update
  [`docs/traceability-matrix.md`](./traceability-matrix.md).
* **Add tests** — unit tests (`*Test`) and, where a database is involved, Testcontainers
  integration/parity tests (`*IT`) to keep combined line coverage **≥ 80%**.
* **Record non-trivial decisions** in [`docs/decision-log.md`](./decision-log.md).

### Suggested next tasks

These are **explicitly out of scope for the current migration** (which preserves existing
behavior without feature expansion) and are recorded as follow-ups in
[`docs/decision-log.md`](./decision-log.md):

* **Upgrade to Spring Boot 4.x — or procure commercial/extended 3.5 support (pre-production prerequisite).**
  The Spring Boot **3.5** line reached open-source end-of-life on **2026-06-30**, so no future
  *upstream* CVE patches will be published for 3.5.16 (this is review finding #52). The framework
  version is fixed at 3.5.16 by the frozen migration plan (AAP §0.7.3), so the upgrade is out of
  scope for *this* migration; **documenting the EOL is not a substitute for vendor support**, so
  before any production deployment you must either obtain supported commercial/extended maintenance
  for 3.5.16 **or** complete the 4.x upgrade and re-run every parity and security gate
  (`./mvnw clean verify`). Shipped-artifact CVEs are already patched at source via `pom.xml`
  version overrides, but no future upstream patches will arrive on the EOL line.
* **Introduce password hashing (BCrypt).** The current implementation preserves the legacy
  **cleartext** password comparison for behavioral parity; replacing it with a modern
  password-hashing scheme is a recommended hardening step (a deliberate behavior change, so
  it is deferred rather than applied silently).
* **Add a CI/CD pipeline** under `.github/workflows/**`. The repository currently has no CI
  pipeline; automating `./mvnw clean verify` (with the coverage and CVE gates) on every push
  is the natural next step.

> **Not on the delivered scope.** Features such as IBM **MQ / JMS** messaging, **Db2**,
> **IMS**, **FTP/SFTP**, and **REST/SOAP** web-service APIs are **not** part of this
> migration. They do not exist in the source COBOL and appear only on the future **Roadmap**
> in [`README.md`](../README.md#roadmap) — do not treat them as delivered functionality.

---

## 9. Further reading & cross-links

* [`README.md`](../README.md) — project overview, target stack, and the full Application
  Inventory (online transactions and batch jobs).
* [`docs/decision-log.md`](./decision-log.md) — every non-trivial decision with alternatives,
  rationale, and risks.
* [`docs/traceability-matrix.md`](./traceability-matrix.md) — the bidirectional
  COBOL-construct → Java-artifact mapping.
* [`docs/architecture/`](./architecture/) — Mermaid before/after architecture diagrams.
* [`blitzy-deck/index.html`](../blitzy-deck/index.html) — the reveal.js executive-summary
  presentation. It loads reveal.js, Mermaid, and Lucide from the jsDelivr CDN (each SRI-pinned,
  under a restrictive Content-Security-Policy), so it requires network access when opened; serve
  it over a local web server (e.g. `python3 -m http.server` from `blitzy-deck/`).

Welcome aboard — with a clean machine, this guide, and the README, you can build, run, test,
and start extending CardDemo without ever touching a mainframe.
