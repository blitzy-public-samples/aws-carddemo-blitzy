# Getting Started — CardDemo (Java / Spring Boot)

This guide takes you from a **clean machine** to a **running, modifiable
CardDemo application** — built, tested, and serving requests locally with its
full observability stack. Follow it top to bottom; every command is
copy-pasteable and no step assumes prior knowledge of the project.

**Estimated time:** about 15–20 minutes once the [prerequisites](#1-prerequisites)
are installed (the first build downloads dependencies and pulls Docker images, so
allow extra time on a cold machine).

**You do not need to install Maven.** The repository ships the
[Maven Wrapper](https://maven.apache.org/wrapper/) (`./mvnw`, and `mvnw.cmd` on
Windows), which downloads and runs the exact pinned Maven version for you.

CardDemo is a re-platform of the AWS mainframe **CardDemo** credit-card account
management sample from **COBOL / CICS / VSAM / JCL** to **Java 25 LTS +
Spring Boot 3.5.16** over **PostgreSQL 16**. Business behavior is preserved with
no feature expansion; the original mainframe source is retained, read-only, under
[`legacy/`](../../legacy).

> **Checkpoint status.** This guide is **runnable now** on the provisioned toolchain. The repository
> ships the Maven project descriptor (`pom.xml`), the Java application sources under `src/**`, the Maven
> Wrapper (`./mvnw`, `mvnw.cmd`), `docker-compose.yml`, the `Dockerfile`,
> `src/main/resources/application-local.yml`, and `.github/workflows/ci.yml`, alongside the relocated
> **read-only** legacy source under [`legacy/`](../../legacy) and the design/onboarding documentation.
> Every command below executes end-to-end: `./mvnw -B clean verify` builds with zero warnings,
> `docker compose up -d` brings up a healthy stack, and `./mvnw spring-boot:run` / `java -jar …` start the
> application. (An identically pinned system Maven can stand in for `./mvnw` if preferred.) This mirrors
> the **Checkpoint status** note in the [root README](../../README.md#build--test).

**Where to go after this guide:**

- [`domain-context.md`](./domain-context.md) — what the application does and where
  the authoritative business behavior lives in the legacy source.
- [`extending.md`](./extending.md) — how to change the application the idiomatic
  way, plus suggested next tasks.
- [`pitfalls.md`](./pitfalls.md) — the parity traps most likely to introduce a
  silent behavioral regression.

---

## Contents

1. [Prerequisites](#1-prerequisites)
2. [Clone the repository](#2-clone-the-repository)
3. [Start the local dependencies (Docker Compose)](#3-start-the-local-dependencies-docker-compose)
4. [Configure environment variables](#4-configure-environment-variables)
5. [Build and test](#5-build-and-test)
6. [Run the application](#6-run-the-application)
7. [Verify it works](#7-verify-it-works)
8. [Run the batch jobs](#8-run-the-batch-jobs)
9. [Demo logins (legacy seed data)](#9-demo-logins-legacy-seed-data)
10. [Troubleshooting](#10-troubleshooting)
11. [Where to go next](#11-where-to-go-next)

---

## 1. Prerequisites

Install the following before you begin. The versions listed are what the build
targets; newer patch releases within the same major line are fine.

| Tool | Version | Why you need it | Verify with |
|------|---------|-----------------|-------------|
| **JDK — Eclipse Temurin (Adoptium)** | **Java 25** (LTS) | Compiles and runs the application; the compiler is configured for Java 25. | `java -version` |
| **Docker** + **Docker Compose** | current | Runs the local **PostgreSQL 16** database and the observability stack (Prometheus, Tempo, Grafana). Also required by the Testcontainers integration tests. | `docker --version` and `docker compose version` |
| **Git** | current | Clones the repository. | `git --version` |

**Maven is intentionally absent from this list** — the committed Maven Wrapper
(`./mvnw` on Linux/macOS, `mvnw.cmd` on Windows) provisions Maven 3.9+ on first
use, so the whole team builds with an identical, pinned Maven version.

**Supported operating systems:** Linux, macOS, and Windows.

- On **Linux/macOS**, run the wrapper as `./mvnw ...`.
- On **Windows**, run it as `mvnw.cmd ...` (PowerShell or Command Prompt).

Confirm your JDK first — this is the single most common source of build failures:

```shell
java -version
```

You should see a `25` version line (for example, `openjdk version "25"`). If you
see an older major version, install Temurin 25 and make it the active JDK (set
`JAVA_HOME` to the JDK 25 installation) before continuing.

---

## 2. Clone the repository

Clone the repository and change into it (replace the placeholder URL with your
actual remote):

```shell
git clone <repo-url> carddemo
cd carddemo
```

### Repository layout

The Java application lives at the **repository root**; the original mainframe
source is retained under `legacy/`. The key locations are:

| Path | What it contains |
|------|------------------|
| `pom.xml` | Maven build manifest — dependencies, the Java 25 toolchain, the coverage gate, and the OWASP security gate. |
| `mvnw`, `mvnw.cmd`, `.mvn/` | The Maven Wrapper — build without a separate Maven install. |
| `src/main/java/com/aws/carddemo/**` | Application source, organized package-by-layer: `config`, `domain`, `repository`, `dto`, `mapper`, `web` (REST controllers), `service` (business logic), `batch` (Spring Batch jobs), `exception`, `observability`, `security`, `common`. |
| `src/main/resources/**` | `application.yml` / `application-local.yml`, `logback-spring.xml`, `db/migration/**` (Flyway schema + reference data), and `db/seed/**` (local seed data). |
| `src/test/**` | Unit tests plus Testcontainers-backed integration tests. |
| `docker-compose.yml`, `Dockerfile` | The local dependency stack and the application container image. |
| `docs/` | Project documentation — [`architecture.md`](../architecture.md), [`decision-log.md`](../decision-log.md), [`traceability-matrix.md`](../traceability-matrix.md), the `onboarding/` guides, and [`observability/grafana-dashboard.json`](../observability/grafana-dashboard.json). |
| `legacy/` | The original **COBOL / CICS / VSAM / JCL** source (formerly `app/`), kept **read-only for reference**. |
| `blitzy-deck/` | The self-contained executive-summary presentation. |

For the layered design and the layer boundaries, read
[`../architecture.md`](../architecture.md). For the exact COBOL-construct → Java
mapping (100% paragraph coverage, bidirectional), read
[`../traceability-matrix.md`](../traceability-matrix.md). When you need to confirm
the original behavior of a program, the authoritative COBOL is under
[`legacy/cbl`](../../legacy/cbl) and its copybooks under
[`legacy/cpy`](../../legacy/cpy).

---

## 3. Start the local dependencies (Docker Compose)

> **Export your environment variables first.** `docker-compose.yml` reads the database
> credentials from `DB_USERNAME` / `DB_PASSWORD` (via fail-fast `${VAR:?}` expansion), so
> `docker compose up -d` **errors out** if they are not set. Complete
> [Section 4 — Configure environment variables](#4-configure-environment-variables) **before**
> running the command below (a first-time reader can jump to §4, export the variables, then return
> here). No credentials are ever baked into the compose file or this guide.

The application needs PostgreSQL to run, and the observability tools make the
logs, metrics, and traces visible. Bring the whole stack up with Docker Compose
from the repository root:

```shell
docker compose up -d
```

> This uses the Docker Compose **plugin** (`docker compose`), which is what the provisioned toolchain
> provides. If your environment only has the older standalone binary, the equivalent is the hyphenated
> `docker-compose up -d`.

The stack provides the following services:

| Service | Purpose | Local port |
|---------|---------|------------|
| **PostgreSQL 16** | Relational database (replaces the legacy VSAM data store). | **5432** |
| **Prometheus** | Scrapes and stores application metrics. | **9090** |
| **Tempo** | Distributed-trace backend; receives spans over OTLP. | OTLP receiver on **4318** (HTTP) |
| **Grafana** | Dashboards over Prometheus and Tempo; provisions [`../observability/grafana-dashboard.json`](../observability/grafana-dashboard.json). | **3000** |

Credentials for these services are supplied through **environment variables**
(see the [next section](#4-configure-environment-variables)); the values shown in
this guide are **local-only examples**, never production secrets.

Check that the containers started and, in particular, that PostgreSQL reports
healthy **before you build or run** (the database must accept connections):

```shell
docker compose ps
```

Wait until the PostgreSQL service shows a healthy/running status. If it never
becomes healthy, see [Troubleshooting](#10-troubleshooting).

---

## 4. Configure environment variables

**No credentials are hardcoded anywhere in the source or configuration.** The
application resolves every connection value and secret from the environment. The
datasource properties in `application.yml` have **no literal fallback**, so a
missing variable fails fast at startup rather than silently using an insecure
baked-in default.

> **All example values below are placeholders for local development only.** They
> are deliberately non-production. **Never commit real secrets** — production
> values come from your environment or a secret store. See decision
> [D6 — Externalized credentials, no hardcoded secrets](../decision-log.md#d6--externalized-credentials-no-hardcoded-secrets).

**Consumed by the application** (the first four are required):

| Variable | Purpose | Example (placeholder — local only) |
|----------|---------|------------------------------------|
| `DB_URL` | JDBC URL of the PostgreSQL 16 database. | `jdbc:postgresql://localhost:5432/carddemo` |
| `DB_USERNAME` | Database user. | `carddemo` |
| `DB_PASSWORD` | Database password (non-production). | `change-me-locally` |
| `SPRING_PROFILES_ACTIVE` | Activates `application-local.yml` (local datasource + observability wiring, relaxed logging). | `local` |
| `OTLP_ENDPOINT` | OTLP/HTTP endpoint for exporting traces to Tempo. Optional — defaults to `http://localhost:4318/v1/traces`. | `http://localhost:4318/v1/traces` |
| `SERVER_PORT` | HTTP port the app listens on. Optional — defaults to `8080`. | `8080` |

> The trace endpoint can also be set with the standard Spring Boot property
> `management.otlp.tracing.endpoint` (environment form
> `MANAGEMENT_OTLP_TRACING_ENDPOINT`); the shipped configuration reads
> `OTLP_ENDPOINT`, so prefer that variable locally.

**Consumed by the Docker Compose stack** (configure the local containers; keep
these consistent with the `DB_*` values above so the app can connect):

| Variable | Purpose | Example (placeholder — local only) |
|----------|---------|------------------------------------|
| `POSTGRES_DB` | Database name created by the PostgreSQL container. | `carddemo` |
| `POSTGRES_USER` | PostgreSQL user created by the container. | `carddemo` |
| `POSTGRES_PASSWORD` | PostgreSQL password (non-production). | `change-me-locally` |
| `GF_SECURITY_ADMIN_USER` | Grafana admin login. | `admin` |
| `GF_SECURITY_ADMIN_PASSWORD` | Grafana admin password (non-production). | `change-me-locally` |

**macOS / Linux** — export the variables in your shell (placeholder values):

```shell
export DB_URL="jdbc:postgresql://localhost:5432/carddemo"
export DB_USERNAME="carddemo"
export DB_PASSWORD="change-me-locally"        # local-only, non-production
export SPRING_PROFILES_ACTIVE="local"
export OTLP_ENDPOINT="http://localhost:4318/v1/traces"
# Required by the Docker Compose stack (Section 3). POSTGRES_USER/POSTGRES_PASSWORD
# default to DB_USERNAME/DB_PASSWORD above; the Grafana admin password has no default
# and must be set or `docker compose up -d` fails fast.
export GF_SECURITY_ADMIN_USER="admin"
export GF_SECURITY_ADMIN_PASSWORD="change-me-locally"   # local-only, non-production
```

**Windows PowerShell** — set the same variables (placeholder values):

```powershell
$Env:DB_URL="jdbc:postgresql://localhost:5432/carddemo"
$Env:DB_USERNAME="carddemo"
$Env:DB_PASSWORD="change-me-locally"          # local-only, non-production
$Env:SPRING_PROFILES_ACTIVE="local"
$Env:OTLP_ENDPOINT="http://localhost:4318/v1/traces"
# Required by the Docker Compose stack (Section 3). POSTGRES_USER/POSTGRES_PASSWORD
# default to DB_USERNAME/DB_PASSWORD above; the Grafana admin password has no default
# and must be set or `docker compose up -d` fails fast.
$Env:GF_SECURITY_ADMIN_USER="admin"
$Env:GF_SECURITY_ADMIN_PASSWORD="change-me-locally"     # local-only, non-production
```

Again: these are **examples**. Do not commit them, and use real values only from
a secure source in any non-local environment.

---

## 5. Build and test

Build, test, and run every quality gate with a single command from the
repository root:

```shell
./mvnw -B clean verify
```

On Windows:

```shell
mvnw.cmd -B clean verify
```

`clean verify` is the authoritative gate. It:

- **compiles the application under Java 25 with zero warnings**;
- runs the **unit tests** (JUnit 5, Mockito, AssertJ);
- runs the **Testcontainers integration tests** against a **real PostgreSQL 16**
  container that the tests start and stop themselves;
- enforces the **JaCoCo line-coverage gate** — the build **fails below 80%**
  combined line coverage;
- runs the **OWASP dependency-check** software-composition scan — with
  `failBuildOnCVSS` the build **fails on any critical or high CVE**
  (see [D5 — OWASP dependency-check 12.2.2 with `failBuildOnCVSS`](../decision-log.md#d5--owasp-dependency-check-1222-with-failbuildoncvss)).

> **Docker must be running for `verify`.** The integration tests use
> Testcontainers, which starts its **own** PostgreSQL 16 container — you do **not**
> need `docker compose up` for the tests, but the **Docker daemon must be
> available**. If Docker is not running, the integration tests fail; see
> [Troubleshooting](#10-troubleshooting).

To run the **whole test suite** without the coverage gate or the security scan
while iterating:

```shell
./mvnw -B test
```

This stops after the Maven `test` phase, so — unlike `verify` — it does **not**
run the JaCoCo coverage report/gate or the OWASP dependency-check. It still runs
**every** test: the integration tests are named `*Test` and execute in the
`test` phase alongside the unit tests, so `./mvnw -B test` runs the
Testcontainers integration tests too and therefore **also requires a running
Docker daemon** (exactly as `verify` does). To narrow a run to a single class
while iterating, use `./mvnw -B test -Dtest=<ClassName>` (Docker is still
required when that class is a Testcontainers integration test).

After a build, the human-readable coverage report is written to:

```
target/site/jacoco/index.html
```

Open it in a browser to see per-package and per-class line coverage against the
80% gate.

---

## 6. Run the application

Make sure the [local stack is up](#3-start-the-local-dependencies-docker-compose)
and your [environment variables are exported](#4-configure-environment-variables)
(including `SPRING_PROFILES_ACTIVE=local`), then choose one of the following.

**Option A — run from source (recommended for development):**

```shell
./mvnw spring-boot:run
```

**Option B — run the packaged jar:**

```shell
./mvnw -B clean package          # or: ./mvnw -B clean verify
java -jar target/carddemo-1.0.0.jar
```

The build produces the artifact `target/carddemo-1.0.0.jar` (Maven coordinates
`com.aws.carddemo:carddemo:1.0.0`).

**Option C — run as a container:**

Build the image from the provided `Dockerfile` and run it, injecting the same
environment variables at runtime (the app also runs as a service inside the
Compose stack):

```shell
docker build -t carddemo:1.0.0 .
docker run --rm -p 8080:8080 \
  -e DB_URL -e DB_USERNAME -e DB_PASSWORD \
  -e SPRING_PROFILES_ACTIVE -e OTLP_ENDPOINT \
  carddemo:1.0.0
```

Whichever option you pick:

- The application listens on **`http://localhost:8080`** by default (override with
  `SERVER_PORT`).
- It connects to PostgreSQL using the `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`
  variables.
- On startup, **Flyway** applies the migrations under
  `src/main/resources/db/migration/**` to create the schema (11 tables — 10 core plus 1 staging — their indexes,
  and foreign keys) and load reference data. Hibernate only **validates** the
  mapping against that schema — it never creates or alters it.
- **Batch jobs do not run on startup.** They are triggered explicitly (the
  CI/CD-workflow equivalent of the legacy JCL scheduler), so starting the app
  never kicks off posting, interest, or statement jobs.

---

## 7. Verify it works

With the application running, confirm each surface responds.

**REST API — Swagger / OpenAPI UI** (springdoc 2.8.17). Open the interactive
endpoint catalog — the 17 online screens re-expressed as request/response DTOs:

```
http://localhost:8080/swagger-ui.html
```

The raw OpenAPI 3 document is at `http://localhost:8080/v3/api-docs`.

**Health and readiness** (Spring Boot Actuator) — these probes are **public** (no
authentication) so container orchestrators can reach them; each should report `UP`:

```shell
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/actuator/health/liveness
```

**Metrics** — the Prometheus scrape endpoint (this is what Prometheus on
`:9090` reads). Like the health probes, `/actuator/prometheus` and `/actuator/info`
are **public** (no authentication) — the local Prometheus scrapes `/actuator/prometheus`
without credentials because the Compose scrape config carries no `basic_auth`. Only
`/actuator/metrics` requires **HTTP Basic** authentication with a valid application
user (an unauthenticated request returns `401`). This matches `SecurityConfig` and
§8 below:

```shell
# Public — no credentials required:
curl http://localhost:8080/actuator/prometheus
curl http://localhost:8080/actuator/info
# Protected — HTTP Basic required (no credentials returns 401):
curl -u ADMIN001:PASSWORD http://localhost:8080/actuator/metrics
```

**Correlation-ID header** — every HTTP response includes an `X-Correlation-Id`
header. Supply your own to correlate a request across its logs, response, and
trace; if you omit it (or send a value outside the safe pattern
`^[A-Za-z0-9._-]{1,64}$`) the application generates a fresh UUID instead of
reflecting the untrusted value:

```shell
# A well-formed id you send is echoed back on the response:
curl -si http://localhost:8080/actuator/health -H 'X-Correlation-Id: demo-123' | grep -i x-correlation-id
# Absent or malformed -> the response carries a freshly generated UUID:
curl -si http://localhost:8080/actuator/health | grep -i x-correlation-id
```

**Grafana dashboard and traces:**

1. Open Grafana at `http://localhost:3000` and log in with the local-only
   Grafana admin credentials from [section 4](#4-configure-environment-variables).
2. Confirm the dashboard provisioned from
   [`../observability/grafana-dashboard.json`](../observability/grafana-dashboard.json)
   renders and shows metrics scraped from Prometheus.
3. Exercise a few REST endpoints, then confirm the corresponding **traces**
   appear in **Tempo** (via the Grafana Explore view). Every log line carries a
   **correlation ID** that propagates across service and batch boundaries, so you
   can pivot from a trace to its logs.

For the rationale behind the observability design, see
[`./pitfalls.md`](./pitfalls.md) and the decision log at
[`../decision-log.md`](../decision-log.md).

---


## 8. Run the batch jobs

The batch pipelines are the JCL-equivalent execution surface. As noted in
[Run the application](#6-run-the-application), **they never run on startup** — you launch each one
explicitly by name against the packaged jar, with the web server disabled so the **process exit code
equals the Spring Batch return code** (`0` = `COMPLETED`, `4` = completed with rejects, `8` = abend).

**Prerequisites:** the [local stack is up](#3-start-the-local-dependencies-docker-compose), your
[environment variables are exported](#4-configure-environment-variables) (including
`SPRING_PROFILES_ACTIVE=local` — add the `batch` profile, i.e. `SPRING_PROFILES_ACTIVE=local,batch`, to
publish batch-job metrics to Prometheus/Grafana; see the **Batch metrics** note after the launch pattern),
and you have built the jar
(`./mvnw -B clean package` — see [Build and test](#5-build-and-test)).

### Launch pattern

```shell
java -jar target/carddemo-1.0.0.jar \
  --spring.main.web-application-type=none \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=<jobName> \
  <parameter=value> ...
```

> **Batch metrics (optional but recommended).** A batch JVM runs headless
> (`--spring.main.web-application-type=none`), so there is **no** `/actuator/prometheus` endpoint for
> Prometheus to scrape, and the process exits within seconds of the job finishing. To make a run appear
> in the Grafana **Spring Batch** panels, activate the **`batch`** profile so the JVM **pushes** its
> `spring_batch_job_seconds` / `spring_batch_step_seconds` metrics over OTLP to Prometheus's native OTLP
> receiver just before it exits — either export `SPRING_PROFILES_ACTIVE=local,batch` (as in the
> prerequisites, which every `$J` example below then inherits) or append
> `--spring.profiles.active=local,batch` to a single launch. It is safe even when the observability stack
> is down: the push simply fails with a logged warning and the job's return code is unaffected. Mechanism
> and dashboard details (why the panels use last-run p95/avg semantics, and `OTLP_METRICS_URL`):
> decision-log
> [**D65**](../decision-log.md#d65--standalone-batch-jvms-push-metrics-via-otlp-to-prometheus-online-scrape-path-preserved).

`<jobName>` is the job's Spring bean name. The in-scope pipelines and the parameters each one
requires are:

| Job (`--spring.batch.job.name`) | Purpose (legacy) | Required parameters | Output |
|---|---|---|---|
| `dailyTransactionLoadJob` | Ingest a raw 350-byte DALYTRAN fixed-width file into the `daily_transaction` staging table | `inputResource=<Spring resource URL>` (required; fail-fast — D30) | rows in `daily_transaction` |
| `dailyTransactionValidateJob` | Validate staged daily transactions (CBTRN01C; read-only) | none | log only; `RC 0` |
| `dailyTransactionPostingJob` | Post staged daily transactions and write rejects (POSTTRAN/CBTRN02C) | none (reads staged `daily_transaction` rows) | `./target/batch/DALYREJS.dat` |
| `interestCalculationJob` | Monthly interest calculation (INTCALC/CBACT04C) | `parmDate=<YYYYMMDDHH>` (10 chars, e.g. `2022071800`; required — D33) | `./target/batch/SYSTRAN.dat` |
| `transactionReportJob` | Date-range transaction report (TRANREPT/CBTRN03C) | `startDate=<YYYY-MM-DD>` `endDate=<YYYY-MM-DD>` (inclusive) | `./target/batch/DALYREPT.txt` |
| `statementGenerationJob` | Generate one statement per account/card cross-reference from posted history (CREASTMT/CBSTM03A+CBSTM03B) | none (reads posted `transaction` rows) | `./target/batch/statements.txt` + `./target/batch/statements.html` |

> `interestCalculationJob` and `dailyTransactionLoadJob` **fail fast** if their required parameter is
> missing or blank (job-parameter validators — D33 / D30), so an empty launch surfaces a clear error
> rather than corrupt output. This is also why these jobs are not part of the CI nightly
> correlationId-only smoke loop (decision-log D14); they are exercised by the Testcontainers
> integration-test suite instead.

> `statementGenerationJob` takes **no** parameters: it reads the posted `transaction` history (which the
> `local` profile seeds, so the statement job has data out of the box) and writes one statement per
> account/card cross-reference to `statements.txt` (plain text) and `statements.html` (HTML). Like the
> other no-parameter jobs (`validate`, `post`), every empty-parameter launch is the **same** Spring
> Batch job instance — so a completed no-parameter job will not re-run. To launch it **again** (for
> example after regenerating output to inspect it), pass any unique job parameter, e.g.
> `stamp=$(date +%s)`, which creates a fresh job instance.

### Supply your own DALYTRAN fixture

The posting and validate jobs read rows that have already been **staged** in the `daily_transaction`
table. To process your own input, first load a raw fixed-width DALYTRAN file with
`dailyTransactionLoadJob`. A ready-made 10-record sample lives at
`src/test/resources/seed/dailytran-fixedwidth-sample.txt` (each record is exactly 350 bytes,
ISO-8859-1, with overpunch-signed amounts):

```shell
# 1) Load a raw DALYTRAN file into the daily_transaction staging table.
java -jar target/carddemo-1.0.0.jar \
  --spring.main.web-application-type=none \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=dailyTransactionLoadJob \
  inputResource=file:./src/test/resources/seed/dailytran-fixedwidth-sample.txt
```

`inputResource` is a Spring resource URL, so `file:./relative/path`, an absolute `file:/...` path, or
`classpath:...` all work. Point it at your own 350-byte fixed-width file to stage your own data.

### End-to-end example flow

The five commands below walk the whole pipeline and demonstrate the **launch mechanics** — bean
name, parameters, and the exit-code contract:

```shell
# load -> validate -> post -> interest -> report -> statement
J="java -jar target/carddemo-1.0.0.jar --spring.main.web-application-type=none --spring.batch.job.enabled=true"

$J --spring.batch.job.name=dailyTransactionLoadJob \
   inputResource=file:./src/test/resources/seed/dailytran-fixedwidth-sample.txt
$J --spring.batch.job.name=dailyTransactionValidateJob
$J --spring.batch.job.name=dailyTransactionPostingJob      # on the SEEDED db this abends (exit 8) - see the note below
$J --spring.batch.job.name=interestCalculationJob parmDate=2022071800
$J --spring.batch.job.name=transactionReportJob startDate=2022-07-01 endDate=2022-07-31
$J --spring.batch.job.name=statementGenerationJob          # no parameters; writes statements.txt + statements.html
```

> **Re-running the whole recipe? Give each job a fresh parameter first.** A Spring Batch
> *job instance* is identified by its job name plus its **identifying** job parameters (by
> default every parameter is identifying), and a **completed** instance cannot be launched
> again. On a second pass of the block above — reusing the same parameter values, including
> the empty parameter set of the no-parameter jobs (`validate`, `post`, `statement`) — each
> job that **COMPLETED** on the first pass is relaunched as the *same* instance and fails
> fast with `JobInstanceAlreadyCompleteException`. That launch failure is caught in
> `CardDemoApplication.main` and surfaced to the OS as the abend return code **exit 8**
> (the same `0`/`4`/`8` contract as a runtime abend — [§8 intro](#8-run-the-batch-jobs),
> decision-log
> [**D45**](../decision-log.md#d45--batch-process-exit-code-equals-the-spring-batch-return-code-jcl-condition-code-parity)),
> so a repeat run is unmistakable rather than silent. To repeat the pipeline cleanly, append a
> **unique identifying parameter** to every job you re-launch, for example `runId=$(date +%s)`
> — this is the same mechanism the `statementGenerationJob` note above uses (`stamp=$(date +%s)`)
> and it forces a brand-new job instance each time:
>
> ```shell
> R="runId=$(date +%s)"   # one fresh value per pipeline pass
> $J --spring.batch.job.name=dailyTransactionValidateJob $R
> $J --spring.batch.job.name=dailyTransactionPostingJob $R
> $J --spring.batch.job.name=interestCalculationJob parmDate=2022071800 $R
> $J --spring.batch.job.name=transactionReportJob startDate=2022-07-01 endDate=2022-07-31 $R
> $J --spring.batch.job.name=statementGenerationJob $R
> ```
>
> The posting step is the one exception that does **not** need this on the seeded database: it
> **abends** (exit 8) on the first pass instead of completing (see the next note), and a
> *failed* instance is restartable with the same parameters.

> **The posting step abends on the freshly-seeded database — and that is correct.** The `local`
> profile seeds **two** tables from the same demonstration data: `transaction` (300 rows of
> *already-posted* history, so the online screens and the report/statement jobs have data) and
> `daily_transaction` (the same 300 IDs *staged* for posting, plus the 10 you load above). Posting
> therefore tries to re-post transactions that already exist, and the first row hits the
> `pk_transaction` primary key —
> `duplicate key value violates unique constraint "pk_transaction" ... (tran_id)=(0000000000683580) already exists`.
> The step fails, its chunk rolls back (so no `DALYREJS.dat` is written and the table is left
> unchanged), and the job abends with **exit 8**. This is faithful `CBTRN02C` behavior:
> `2900-WRITE-TRANSACTION-FILE` treats a duplicate-key write (VSAM `FILE STATUS '22'`) as
> `9999-ABEND-PROGRAM`. Because of the exit-code contract
> ([§8 intro](#8-run-the-batch-jobs), decision-log
> [**D45**](../decision-log.md#d45--batch-process-exit-code-equals-the-spring-batch-return-code-jcl-condition-code-parity)),
> that abend now surfaces **as process exit 8** rather than the silent "exit 0 with no output" a new
> developer would otherwise see.
> `load`, `validate`, `interest`, and `report` all run clean against the seed; only `post` collides,
> because only `post` inserts new `transaction` rows.

#### Run a clean post

To watch a posting run actually **succeed**, give it staging rows whose IDs are not already posted.
The simplest way — no extra fixture needed — is to release exactly the IDs you are about to post,
leaving the rest of the posted history in place so the local seed loader does **not** re-add them on
the next launch (it only fills a table that is completely empty). The `psql` calls below run inside
the Compose `postgres` container, so they need no local client:

```shell
J="java -jar target/carddemo-1.0.0.jar --spring.main.web-application-type=none --spring.batch.job.enabled=true"

# 1) Replace the pre-seeded staging rows with just your own input file.
docker compose exec -T postgres psql -U "$DB_USERNAME" -d carddemo -c "TRUNCATE daily_transaction;"
$J --spring.batch.job.name=dailyTransactionLoadJob \
   inputResource=file:./src/test/resources/seed/dailytran-fixedwidth-sample.txt

# 2) Free exactly those tran-ids in the posted table. transaction stays non-empty (~290 rows), so the
#    local seed loader leaves it untouched on the next launch and the IDs remain available to post.
docker compose exec -T postgres psql -U "$DB_USERNAME" -d carddemo \
  -c "DELETE FROM transaction t USING daily_transaction d WHERE t.tran_id = d.dalytran_id;"

# 3) Validate and post: the IDs are now free, so posting COMPLETEs with exit 0
#    (or exit 4 if a staged row is genuinely rejected — reason code 100/102/103).
$J --spring.batch.job.name=dailyTransactionValidateJob
$J --spring.batch.job.name=dailyTransactionPostingJob
```

See [Common pitfalls](pitfalls.md) for more on the seed's posted/staging overlap.

### Recovering an interrupted job (`STARTED` / `UNKNOWN`)

A **graceful** batch failure (a runtime abend, an invalid parameter, or the already-complete
case above) ends with a clean `FAILED` execution and process **exit 8**, and Spring Batch will
happily **restart** that same instance once the underlying cause is fixed. A **hard** interruption
is different: if the JVM is killed mid-step (`kill -9`, an OOM kill, a container/node eviction, a
power loss), the graceful `FAILED` transition never runs, so the metadata row is left in
`STARTED` — and if the step's commit outcome could not be recorded, in `UNKNOWN`. Spring Batch's
automatic restart **treats `STARTED` as "still running" and outright refuses to restart an
`UNKNOWN` execution** (it cannot know whether the interrupted chunk committed), so a naive relaunch
of the same instance fails fast with **exit 8** and no progress. Use this runbook to reconcile the
**metadata**, the **data**, and the **file** side-effects and get the pipeline moving again.

Recovery is bounded by design — the atomicity and idempotency fixes elsewhere in this codebase
guarantee a killed run leaves **no partial published output and no double-applied data**:

1. **Confirm nothing is still running (do this first).** A `STARTED` row can also mean the job
   *really is* live in another process; restarting concurrently would double-run it. Verify no JVM
   is still executing the job before touching any metadata:

   ```shell
   # No CardDemo batch JVM should be alive. (Look for the jar + --spring.batch.job.name=...)
   ps -ef | grep '[c]arddemo-1.0.0.jar' || echo "no live batch process"
   ```

   In a container/orchestrator, confirm the pod/task that launched the job is gone. Only proceed
   once you are certain the process is dead.

2. **Find the stale execution.** Query the standard Spring Batch metadata tables (prefix `BATCH_`,
   auto-created by Spring Boot). Anything `STARTED`/`UNKNOWN` with no live process is a stale row:

   ```shell
   docker compose exec -T postgres psql -U "$DB_USERNAME" -d carddemo -c "
     SELECT je.job_execution_id, ji.job_name, je.status, je.exit_code, je.start_time, je.end_time
     FROM batch_job_execution je
     JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
     WHERE je.status IN ('STARTED','UNKNOWN') ORDER BY je.job_execution_id;"
   ```

3. **Reconcile file side-effects (usually nothing to do).** Every batch writer publishes its output
   with a **temp-write-then-atomic-rename** (F-P5-C/D/E): work goes to a run-scoped temp sibling
   (`*.part`, `*.tmp`, or `*.inprogress`) in the output directory and is `ATOMIC_MOVE`d onto the
   final name **only on success**. A hard kill therefore leaves **at most a leftover temp file and
   never a half-written published output**. Delete any stray temp files before re-running:

   ```shell
   ls -l ./target/batch                    # inspect first
   rm -f ./target/batch/*.part ./target/batch/*.tmp ./target/batch/*.inprogress
   ```

4. **Reconcile data side-effects.** The committed data is a **consistent prefix** of the work, and a
   re-run is safe to complete it:
   - **Posting** is chunk-transactional — committed chunks persist and the interrupted chunk was
     rolled back; a restart resumes cleanly from the last commit point.
   - **Interest** is **cycle-idempotent** (F-P6-A / F-P5-D): each account carries a
     `last_interest_cycle` marker and the balance update is a guarded conditional bulk `UPDATE`, so
     accounts already finalized in the interrupted cycle are **not** applied a second time on re-run.
   No manual data surgery is required; if you want to inspect what committed, query the affected
   table (e.g. `SELECT count(*) FROM transaction WHERE ...`).

5. **Remediate the metadata, then re-launch.** Choose one:

   - **Restartable failure (`STARTED`)** — mark the stale execution `FAILED` so Spring Batch will
     restart the *same* instance and resume from the last good step/commit:

     ```shell
     docker compose exec -T postgres psql -U "$DB_USERNAME" -d carddemo -c "
       UPDATE batch_step_execution SET status='FAILED', exit_code='FAILED'
         WHERE job_execution_id=<ID> AND status IN ('STARTED','UNKNOWN');
       UPDATE batch_job_execution  SET status='FAILED', exit_code='FAILED', end_time=now()
         WHERE job_execution_id=<ID>;"
     # then re-launch the SAME job + parameters; Spring Batch resumes the instance
     ```

   - **Un-restartable execution (`UNKNOWN`)** — Spring Batch refuses to restart an `UNKNOWN`
     execution, so mark it **`ABANDONED`** (the status Spring Batch skips on restart) and then start a
     **fresh instance** with a unique identifying parameter (the same `runId=$(date +%s)` mechanism
     shown above):

     ```shell
     docker compose exec -T postgres psql -U "$DB_USERNAME" -d carddemo -c "
       UPDATE batch_job_execution SET status='ABANDONED', end_time=now()
         WHERE job_execution_id=<ID> AND status='UNKNOWN';"
     $J --spring.batch.job.name=<jobName> <original params> runId=$(date +%s)
     ```

   Because of steps 3–4, the fresh run re-does only the un-committed remainder of the work and
   re-publishes the output file atomically — no orphaned finals, no double-applied interest.

### Inspect the outputs

Batch file outputs default to the **`./target/batch`** directory (each is overridable — see the table
below). List and inspect them after a run:

```shell
ls -l ./target/batch
# DALYREJS.dat    — rejected postings (430-byte record + trailing LF; reason codes 100/101/102/103)
# SYSTRAN.dat     — interest transactions (350-byte records; TRAN-ID = parmDate + 6-digit suffix)
# DALYREPT.txt    — transaction report (fixed-width 133-byte lines; card control-break + totals)
# statements.txt  — per-account statements, plain text (80-byte fixed-width records; RECFM=FB, no delimiter)
# statements.html — per-account statements, HTML (100-byte fixed-width records; open in a browser)
```

Override the output locations with these properties (command-line `--key=value`, environment
variable, or `application.yml`) — no paths are hardcoded:

| Property | Default |
|---|---|
| `carddemo.batch.posting.reject-directory` / `carddemo.batch.posting.reject-file` | `./target/batch` / `DALYREJS.dat` |
| `carddemo.batch.interest.output-directory` / `carddemo.batch.interest.output-file` | `./target/batch` / `SYSTRAN.dat` |
| `carddemo.batch.report.output-directory` / `carddemo.batch.report.output-file` | `./target/batch` / `DALYREPT.txt` |
| `carddemo.batch.statement.output-directory` / `carddemo.batch.statement.text-file` / `carddemo.batch.statement.html-file` | `./target/batch` / `statements.txt` / `statements.html` |

The same launch-by-name mechanism is what the CI workflow's scheduled batch job invokes for the
read-only master-print and backup jobs (`accountMasterPrintJob`, `cardMasterPrintJob`,
`xrefPrintJob`, `customerMasterPrintJob`, `transactionBackupJob`); see
[`../decision-log.md`](../decision-log.md) (D14) and [`../architecture.md`](../architecture.md).

To measure these jobs against a realistically larger data tier — generating a scaled (Nx) dataset
and reading the resulting query plans and batch timings — follow
[`./performance-testing.md`](./performance-testing.md).

---


## 9. Demo logins (legacy seed data)

When the application starts under the **`local` profile** (the default for the
local `docker compose` stack and the documented `./mvnw spring-boot:run`
workflow), the `user_security` table — together with the other demonstration
tables (customers, accounts, cards, cross-references, transactions, and
transaction-category balances) — is seeded **automatically** from the CSV
fixtures under `src/main/resources/db/seed/**` by `LocalSeedDataLoader`, a
`local`-profile `ApplicationRunner`. The load is **idempotent**: it runs once
against a fresh database and is skipped on subsequent starts, and it never runs
under the `test` profile or any production profile (so it cannot inject demo
data outside local development). This gives you the original demo accounts so
you can explore the application immediately:

| User ID | Role | Type code | Demo password |
|---------|------|-----------|---------------|
| `ADMIN001` | Administrator | `A` | `PASSWORD` |
| `USER0001` | Regular user | `U` | `PASSWORD` |

> **These are legacy demo seed accounts for local exploration only — not a
> security recommendation.** The shared, well-known password `PASSWORD` is
> inherited verbatim from the original mainframe demo's seed data. Do **not**
> reuse these accounts or this password outside local demonstration; re-secure
> them before any non-demo use.
>
> In the migrated application, stored passwords are **hashed** and the card
> **CVV is never logged or returned in full** — documented security improvements
> over the intentionally insecure legacy demo. Passwords are **never logged**.
> See [D22 — Password hashing and CVV hardening](../decision-log.md#d22--password-hashing-and-cvv-hardening).

Authentication parity with the mainframe is preserved: the two roles map to the
same authorization boundaries as the legacy application — **`A` = Admin** and
**`U` = User**. Administrative endpoints (for example, user management) require an
**Admin** account such as `ADMIN001`; a regular user such as `USER0001` can reach
the back-office functions but not the admin-only ones.

You authenticate with HTTP Basic and then call a **protected** endpoint. The
documentation and probe endpoints (`/swagger-ui.html`, `/v3/api-docs`,
`/actuator/health`, `/actuator/info`, `/actuator/prometheus`) are intentionally
public, so to actually exercise a login use a secured endpoint such as
`/actuator/metrics` (every non-public request requires authentication):

```shell
# No credentials -> 401 (the endpoint is protected):
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/metrics
# Seeded demo credentials -> 200 (authenticated):
curl -s -o /dev/null -w '%{http_code}\n' -u ADMIN001:PASSWORD http://localhost:8080/actuator/metrics
# A regular user authenticates too (any authenticated user may read /actuator/metrics):
curl -s -o /dev/null -w '%{http_code}\n' -u USER0001:PASSWORD http://localhost:8080/actuator/metrics
```

Authorization boundaries still apply to the business API: admin-only paths (for
example, user management under `/api/v1/admin/**`) require an **Admin** account
such as `ADMIN001`, while a regular user such as `USER0001` is limited to the
non-admin functions. Browse the exact paths and request bodies in
[Swagger UI](#7-verify-it-works).

Use [Swagger UI](http://localhost:8080/swagger-ui.html) to discover the precise
endpoint paths, required roles, and DTO shapes for the REST screens as they are
implemented.

---

## 10. Troubleshooting

| Symptom | Likely cause and fix |
|---------|----------------------|
| `./mvnw -B clean verify` fails during integration tests with a Docker/Testcontainers error | The Docker daemon is not running. Start Docker and re-run. Testcontainers needs the daemon even though it manages its own PostgreSQL container. |
| App or containers fail to bind a port | A **port conflict** on `5432` (PostgreSQL), `8080` (app), `3000` (Grafana), `9090` (Prometheus), or `4318` (Tempo OTLP). Stop the conflicting process, or change the mapped port (for the app, set `SERVER_PORT`). |
| App exits immediately at startup complaining about the datasource | **Missing environment variables.** `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` are required and have no fallback. Confirm they are exported and that `SPRING_PROFILES_ACTIVE=local` is set. |
| PostgreSQL never becomes healthy in `docker compose ps` | Give it a few more seconds on first start; if it still fails, check `docker compose logs` for the DB service and confirm `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` are set and consistent with `DB_*`. |
| Build fails with a Java version / `release 25` error | **Java version mismatch.** `java -version` must report **25**. Install Temurin 25 and point `JAVA_HOME` at it. |
| Flyway reports a migration or validation error on startup | The database already contains an incompatible schema. Recreate the local database (for example, `docker compose down -v` then `docker compose up -d`) so Flyway can apply the migrations from a clean state. |
| OWASP dependency-check is slow or fails to update its CVE database offline | The scan downloads the NVD data set on first run. Provide an `NVD_API_KEY` to speed up and stabilize the download, or run once online to warm the local NVD cache. This is a **build/CI** concern, not a runtime credential — see the note in [`pom.xml`](../../pom.xml) and [D5](../decision-log.md#d5--owasp-dependency-check-1222-with-failbuildoncvss). |

---

## 11. Where to go next

You now have a running, testable, modifiable CardDemo. Continue with:

- [`./domain-context.md`](./domain-context.md) — the credit-card domain and where
  the authoritative business behavior lives in the legacy source.
- [`./extending.md`](./extending.md) — how to add functionality the idiomatic way,
  including the **suggested next tasks** discovered during the migration.
- [`./pitfalls.md`](./pitfalls.md) — the parity traps to avoid when changing code.
- [`./performance-testing.md`](./performance-testing.md) — how to generate a
  scaled (Nx) dataset and verify query plans and batch timings against it.
- [`../architecture.md`](../architecture.md) — the layered target architecture and
  its boundaries.
- [`../../README.md`](../../README.md) — the project overview, build/run summary,
  and the full application inventory.

To contribute changes, follow [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md):
work from the latest `main`, keep your change focused, ensure local tests pass
(`./mvnw -B clean verify`), and open a pull request.

