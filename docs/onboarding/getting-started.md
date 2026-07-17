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
8. [Demo logins (legacy seed data)](#8-demo-logins-legacy-seed-data)
9. [Troubleshooting](#9-troubleshooting)
10. [Where to go next](#10-where-to-go-next)

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
becomes healthy, see [Troubleshooting](#9-troubleshooting).

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
```

**Windows PowerShell** — set the same variables (placeholder values):

```powershell
$Env:DB_URL="jdbc:postgresql://localhost:5432/carddemo"
$Env:DB_USERNAME="carddemo"
$Env:DB_PASSWORD="change-me-locally"          # local-only, non-production
$Env:SPRING_PROFILES_ACTIVE="local"
$Env:OTLP_ENDPOINT="http://localhost:4318/v1/traces"
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
> [Troubleshooting](#9-troubleshooting).

To run just the fast **unit tests** (no integration tests, no security scan)
while iterating:

```shell
./mvnw -B test
```

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
`:9090` reads). Unlike the health probes, `/actuator/prometheus` — together with
`/actuator/metrics` and `/actuator/info` — requires **HTTP Basic** authentication
with a valid application user (an unauthenticated request returns `401`):

```shell
curl -u ADMIN001:PASSWORD http://localhost:8080/actuator/prometheus
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


## 8. Demo logins (legacy seed data)

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

## 9. Troubleshooting

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

## 10. Where to go next

You now have a running, testable, modifiable CardDemo. Continue with:

- [`./domain-context.md`](./domain-context.md) — the credit-card domain and where
  the authoritative business behavior lives in the legacy source.
- [`./extending.md`](./extending.md) — how to add functionality the idiomatic way,
  including the **suggested next tasks** discovered during the migration.
- [`./pitfalls.md`](./pitfalls.md) — the parity traps to avoid when changing code.
- [`../architecture.md`](../architecture.md) — the layered target architecture and
  its boundaries.
- [`../../README.md`](../../README.md) — the project overview, build/run summary,
  and the full application inventory.

To contribute changes, follow [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md):
work from the latest `main`, keep your change focused, ensure local tests pass
(`./mvnw -B clean verify`), and open a pull request.

