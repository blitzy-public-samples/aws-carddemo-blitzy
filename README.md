## CardDemo -- Mainframe CardDemo Application

- [CardDemo -- Mainframe CardDemo Application](#carddemo----mainframe-carddemo-application)
- [Description](#description)
- [Technologies used](#technologies-used)
  - [Legacy (mainframe) stack](#legacy-mainframe-stack)
  - [Target (Java) stack](#target-java-stack)
- [Running the Java application](#running-the-java-application)
  - [Prerequisites](#prerequisites)
  - [Repository layout](#repository-layout)
  - [Configuration](#configuration)
  - [Build](#build)
  - [Run](#run)
  - [Batch jobs](#batch-jobs)
  - [Observability](#observability)
  - [Testing](#testing)
- [Documentation](#documentation)
- [Legacy mainframe installation (reference)](#legacy-mainframe-installation-reference)
  - [Running full batch (legacy reference)](#running-full-batch-legacy-reference)
- [Application Details](#application-details)
  - [User Functions](#user-functions)
  - [Admin Functions](#admin-functions)
  - [Application Inventory](#application-inventory)
    - [**Online**](#online)
    - [**Batch**](#batch)
  - [Application Screens](#application-screens)
    - [**Signon Screen**](#signon-screen)
    - [**Main Menu**](#main-menu)
    - [**Admin Menu**](#admin-menu)
- [Support](#support)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Project status](#project-status)

<br/>

## Description

This repository now contains a **Java 25 / Spring Boot 3.5.16** migration of the original AWS CardDemo mainframe application. The migration preserves the existing business behavior while replacing the IBM z/OS COBOL/CICS/VSAM/JCL/BMS runtime with an idiomatic layered Spring Boot stack (Spring MVC + Thymeleaf, Spring Data JPA over PostgreSQL, Spring Batch, and Spring Security). The **original COBOL/CICS/JCL/BMS sources are retained read-only under [`legacy/`](./legacy)** for reference and traceability; they are not required to build or run the Java application.

CardDemo is a Mainframe application designed and developed to test and showcase AWS and partner technology for mainframe migration and modernization use-cases such as discovery, migration, modernization, performance test, augmentation, service enablement, service extraction, test creation, test harness, etc.

Note that the intent of this application is to provide mainframe coding scenarios to excercise analysis, transformation and migration tooling. So, the coding style is not uniform across the application

<br/>

## Technologies used

### Legacy (mainframe) stack

The original application &mdash; retained read-only under [`legacy/`](./legacy) &mdash; is built on:

1. COBOL
2. CICS
3. VSAM
4. JCL
5. RACF

### Target (Java) stack

The migrated application is built on:

1. **Java 25** (LTS) &mdash; language and runtime.
2. **Spring Boot 3.5.16** &mdash; application framework, comprising:
   * **Spring MVC** &mdash; controllers, one route per CICS transaction id (replaces the CICS online transaction handlers).
   * **Spring Data JPA** &mdash; Hibernate repositories over PostgreSQL (replaces VSAM KSDS I/O).
   * **Spring Batch** &mdash; chunk-oriented jobs (replaces JCL / JES2 batch).
   * **Spring Security** &mdash; authentication and role authorities (replaces the application-level signon + RACF).
   * **Thymeleaf** &mdash; server-rendered screens preserving the BMS field / label / PF-key contract.
   * **Bean Validation (Jakarta)** &mdash; field edits (replaces program / BMS field validation).
3. **PostgreSQL 18.4** (supported floor: 16) &mdash; relational store replacing VSAM.
4. **Flyway** &mdash; schema and reference / seed-data migrations (replaces the IDCAMS `DEFINE CLUSTER` / `REPRO` data initialization).
5. **Maven 3.9.9** &mdash; build orchestration, run via the bundled Maven Wrapper (`./mvnw`); no separate Maven install is required.
6. **JUnit 5 + Testcontainers** &mdash; unit tests and integration tests against a real PostgreSQL container.
7. **Micrometer Tracing + Prometheus + Spring Boot Actuator** &mdash; observability (structured logging, distributed tracing, metrics, and health checks).

<br/>

## Running the Java application

The migration targets a Java application that builds and runs on any clean local machine; no mainframe or running COBOL environment is required.

> **Implementation status.** This section describes the Java application, which **has been generated in full and builds, boots, and serves requests today.** Present and exercised at runtime: the Maven build (`pom.xml` + wrapper); all **10** JPA domain entities plus the reference/enum layer; the DTO layer (session context, screen forms, menu and report models); the **10** Spring Data **repositories**; the **17** online **services** and **9** MVC **controllers** covering all 18 CICS transaction ids; the **17** **Thymeleaf templates** preserving the BMS 24&times;80 contract; **`SecurityConfig`** with `CardDemoUserDetailsService` and role-based routing; the cross-cutting `config` / `exception` (including the `@ControllerAdvice` `GlobalExceptionHandler`) / `security` / `util` classes; the business **Spring Batch jobs** (`PostTransactionJob`, `InterestCalcJob`, `StatementJob`) alongside the utility/print jobs and the admin driver; the `application.yml` base config plus the `dev` and `test` **profile files**; structured logging (`logback-spring.xml`); the full Flyway migration set **`V0`&ndash;`V4`** (batch metadata, application schema, reference/seed data, index equivalents, and the card-xref single-key unique constraint); and the **`src/test/**`** JUnit 5 unit + Testcontainers integration + parity suites. The subsections below document commands and behavior that run end-to-end today.

### Prerequisites

* **JDK 25** (LTS) &mdash; required to compile and run the application.
* **Maven** &mdash; no separate install needed; the bundled Maven Wrapper (`./mvnw` on macOS / Linux, `mvnw.cmd` on Windows) pins Maven **3.9.9**.
* **PostgreSQL 18.x** (supported floor 16) running locally, **or Docker**. Docker is also required for the Testcontainers-based integration tests.
* **Git** &mdash; to clone the repository.

### Repository layout

```
carddemo/
├── pom.xml                         Maven build (Spring Boot 3.5.16 parent BOM)
├── mvnw, mvnw.cmd, .mvn/           Maven Wrapper (pinned to Maven 3.9.9)
├── src/main/java/com/aws/carddemo/
│   ├── config/                     DataSource, Batch, Observability, Web, and Security config
│   ├── domain/                     JPA entities (one per VSAM file) + enums
│   ├── dto/                        CardDemoContext (COMMAREA), screen forms, feed / report models
│   ├── repository/                 Spring Data JPA repositories (one per VSAM file)
│   ├── service/                    @Service classes (one per COBOL program; methods = paragraphs)
│   ├── web/                        Spring MVC controllers (one route per CICS transaction id)
│   ├── batch/                      Spring Batch @Configuration jobs (business + utility + admin driver)
│   ├── exception/                  FILE STATUS exception type + @ControllerAdvice handler
│   ├── security/                   UserDetailsService, user principal, and role wiring
│   └── util/                       Date conversion, decimal helpers, fixed-width mappers
├── src/main/resources/
│   ├── application.yml             Base config + application-dev.yml / application-test.yml profiles
│   ├── db/migration/               Flyway: V0 batch metadata; V1/V2/V3 schema/seed/index; V4 card-xref unique
│   ├── logback-spring.xml          Structured JSON logging with correlation IDs
│   └── templates/                  Thymeleaf screens (preserve the BMS 24x80 contract)
├── src/test/java/                  JUnit 5 unit + Testcontainers integration + parity tests
├── legacy/                         Original COBOL / CICS / JCL / BMS / CPY / CSD / data (read-only)
├── docs/                           Decision log, traceability, onboarding, and architecture
├── blitzy-deck/                    reveal.js deck: theme CSS + index.html
├── observability/                  Grafana dashboard template
├── diagrams/                       Legacy flow diagrams and screen captures
└── samples/                        Legacy sample JCL (reference only)
```

### Configuration

Configuration is environment-driven and contains **no hardcoded secrets**. The base `application.yml` is present, together with two Spring profiles &mdash; `dev` (local development) and `test` (used by the integration-test suite) &mdash; supplied as `application-dev.yml` / `application-test.yml`. The database connection is supplied entirely through environment variables:

| Environment variable         | Purpose                             | Example (local dev)                         |
| :--------------------------- | :---------------------------------- | :------------------------------------------ |
| `SPRING_DATASOURCE_URL`      | JDBC URL of the PostgreSQL database | `jdbc:postgresql://localhost:5432/carddemo` |
| `SPRING_DATASOURCE_USERNAME` | Database user                       | supplied via environment / secret manager   |
| `SPRING_DATASOURCE_PASSWORD` | Database password                   | supplied via environment / secret manager   |

**Flyway** manages the database schema as versioned migrations, applied automatically on startup so that a freshly created database is initialized with no manual steps. The full set is present: `V0__spring_batch_metadata.sql` (the Spring Batch metadata tables), `V1__schema.sql` (application schema), `V2__reference_data.sql` (reference and sample seed data), `V3__indexes.sql` (alternate-index equivalents), and `V4__card_xref_unique_card_num.sql` (the `uk_card_xref_card_num` single-key unique constraint on `card_xref`).

> **Flyway is pinned to a PostgreSQL&nbsp;18&ndash;tested release.** The project overrides the Spring Boot 3.5.16 parent BOM's Flyway version (`11.7.2`, whose highest *tested* PostgreSQL is 17) to `11.20.3` via the `flyway.version` property in [`pom.xml`](./pom.xml). Flyway&nbsp;11.20.3 marks PostgreSQL&nbsp;18 as tested, so startup against the target PostgreSQL&nbsp;18.4 server produces **no** compatibility warning and the build/start logs stay warning-free. All `V0`&ndash;`V4` migrations apply cleanly and idempotently on 16/17/18. The override stays within the Flyway&nbsp;11.x major line the BOM already uses (API-stable); see the rationale and risk/mitigation in [`docs/decision-log.md`](./docs/decision-log.md).

### Build

The application compiles cleanly today. To compile and package it:

```shell
./mvnw -DskipTests package
```

This compiles the sources with `--release 25`. The build is **zero-warning**: the compiler runs with `-Xlint:all` and `failOnWarning`, and the Maven wrapper's `.mvn/jvm.config` passes `--sun-misc-unsafe-memory-access=allow` so the JVM emits no `sun.misc.Unsafe` deprecation warnings either &mdash; every prescribed `./mvnw` invocation on Java 25 is free of **both** compiler and JVM-runtime warnings.

The full verification lifecycle runs the test suites and enforces the quality gates &mdash; the **JaCoCo &ge; 80% line-coverage** gate and the **OWASP dependency-check zero critical / high CVE** gate:

```shell
./mvnw clean verify   # unit + Testcontainers integration tests, coverage gate, CVE gate
```

### Run

Run the application end-to-end &mdash; the browser sign-on flow and seeded logins &mdash; with the web controllers, services, repositories, `SecurityConfig`, and the `V1` / `V2` migrations (schema + seed data) all in place. The commands are:

```shell
./mvnw spring-boot:run
```

To activate the local development profile explicitly:

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Open the application in a browser and sign on. As in the legacy system, two demo logins are provided as **seed data**:

* `ADMIN001` &mdash; with the initially configured password &mdash; to manage users (admin functions).
* `USER0001` &mdash; with the initially configured password &mdash; to access back-office (regular user) functions.

These are seeded application user records. The **database** credentials, by contrast, are always supplied via the `SPRING_DATASOURCE_*` environment variables and are never embedded in source or configuration.

### Batch jobs

The batch workload that ran as JCL jobs on the mainframe is implemented as **Spring Batch `Job`s** (chunk-oriented reader &rarr; processor &rarr; writer steps, with `Tasklet` steps for single-action utilities). The business jobs are present &mdash; `PostTransactionJob` (daily transaction posting), `InterestCalcJob` (monthly interest calculation), and `StatementJob` (statement generation) &mdash; alongside the utility/print jobs, the admin-driver job (`CBADMCDJ` &rarr; a documented no-op `Tasklet`), and the Spring Batch metadata schema (`V0`). Job parameters &mdash; for example the interest-calculation processing date &mdash; are supplied as Spring Batch `JobParameter`s, preserving the original JCL `PARM` semantics. See [`docs/traceability-matrix.md`](./docs/traceability-matrix.md) for the full JCL-job &rarr; Spring Batch job mapping.

#### Running batch jobs locally

The batch jobs do **not** run on web startup (`spring.batch.job.enabled=false` by default, so `./mvnw spring-boot:run` only serves the online app). Launch a single job explicitly by running the packaged jar in **non-web** mode, selecting the job by name and passing its parameters as `name=value` arguments:

```bash
# 1. Build the jar once (see Build above)
./mvnw -DskipTests package

# 2. Make sure PostgreSQL is running and the datasource env vars are exported
#    (SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD -- see Configuration).

# 3. Launch one job -- example: daily transaction posting (CBTRN02C / POSTTRAN)
java -jar target/carddemo-1.0.0.jar \
  --spring.main.web-application-type=none \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=postTransactionJob \
  inputPath=/path/to/DALYTRAN.txt \
  rejectPath=/path/to/DALYREJS.txt
echo "RETURN-CODE = $?"
```

`--spring.batch.job.enabled=true` turns the launcher on for this run, `--spring.batch.job.name=<job>` selects which job runs, and **job parameters are the non-`--` arguments** in `name=value` form. `--spring.main.web-application-type=none` makes the process exit when the job finishes (so `$?` carries the return code) instead of starting the web server.

**Jobs and their parameters** (the job name is the value for `--spring.batch.job.name`):

| Job | Parameters | Output artifact |
|-----|------------|-----------------|
| `postTransactionJob` | `inputPath` (DALYTRAN feed), `rejectPath` (DALYREJS rejects) | rejected records &rarr; `rejectPath` (430-byte reject layout) |
| `interestCalcJob` | `processingDate` (COBOL `PARM-DATE`, `PIC X(10)`, e.g. `2022071800`) | updated balances + generated interest transactions |
| `statementJob` | `textOutputPath`, `htmlOutputPath` | plain-text + HTML statement files |
| `transactionReportJob` | `startDate`, `endDate` (`yyyy-MM-dd`), `outputPath` | transaction report file |
| `transactionBackupJob` | `outputPath` | transaction backup file |
| `transactionCombineJob` | `backupInput`, `systemInput`, `combinedOutput` | combined file sorted ascending by `tranId` |
| `categoryBalancePrintJob` | `outputPath` | 40-byte category-balance report |
| `accountPrintJob` | *(none)* | structured log records (mirrors the SYSOUT-only `READACCT`) |
| `cardPrintJob` | *(none)* | structured log records (`READCARD`) |
| `xrefPrintJob` | *(none)* | structured log records (`READXREF`) |
| `customerLoadJob` | *(none)* | structured log records (`READCUST`) |
| `adminBatchJob` | *(none)* | no-op `Tasklet` (the `CBADMCDJ` admin driver) |

You choose the output paths (any writable location); the print/load jobs emit their output as structured log records rather than a dataset, mirroring the SYSOUT-only `READ*` JCL.

**Exit code &mdash; the JCL RETURN-CODE contract.** In non-web mode the JVM exit status reproduces the mainframe RETURN-CODE ladder so a scheduler can branch on the outcome: **`0`** clean completion; **`4`** completed with business rejects (for example `postTransactionJob` wrote one or more records to `rejectPath`); **`8`** the job failed or abended (for example a duplicate transaction id &mdash; the COBOL `9999-ABEND` equivalent). Inspect it with `echo $?` immediately after the run.

**Restart.** Spring Batch keys each job instance by its **identifying** parameters. To **restart** a `FAILED` run, relaunch with the **same** parameters &mdash; the job resumes that instance. To start a **new** instance (for example a fresh posting run over a new feed), change a parameter or add a unique one such as `runId=$(date +%s)`. A job instance that already `COMPLETED` cannot be re-run with identical identifying parameters (Spring Batch rejects it), which is the intended guard against accidentally reprocessing the same input twice.

### Observability

Observability is wired into the application from the start. The configuration artifacts are present and the live HTTP endpoints are exercised when the application runs (see [Run](#run)):

* **Structured logging** &mdash; JSON logging with correlation IDs is configured in [`src/main/resources/logback-spring.xml`](./src/main/resources/logback-spring.xml).
* **Dashboard** &mdash; a Grafana dashboard template is provided at [`observability/grafana-dashboard.json`](./observability/grafana-dashboard.json).
* **Health / readiness (with the running app)** &mdash; `GET /actuator/health`.
* **Metrics (Prometheus scrape, with the running app)** &mdash; `GET /actuator/prometheus`.

### Testing

The `src/test/**` suites are present and run with the standard Maven commands:

```shell
./mvnw test      # unit tests only
./mvnw verify    # unit + Testcontainers integration + parity tests, plus the coverage and CVE gates
```

**Docker must be running** for the Testcontainers-based integration tests, which start a real PostgreSQL container. The parity tests validate the Java results against the ASCII fixtures retained from the legacy system.

<br/>

## Documentation

Companion documentation for the migration lives under [`docs/`](./docs) and is present in full:

* [`docs/decision-log.md`](./docs/decision-log.md) &mdash; every non-trivial migration decision with its alternatives, rationale, and risks.
* [`docs/traceability-matrix.md`](./docs/traceability-matrix.md) &mdash; the bidirectional COBOL-construct &rarr; Java-artifact mapping (programs, copybooks, BMS maps, and JCL jobs).
* [`docs/onboarding.md`](./docs/onboarding.md) &mdash; a clean-machine-to-running-application onboarding guide (setup, domain context, common pitfalls, how to extend the project, and suggested next tasks).
* [`docs/architecture/architecture.md`](./docs/architecture/architecture.md) &mdash; Mermaid before / after architecture diagrams (the current z/OS state and the target Spring Boot state).
* [`docs/operation-inventory.md`](./docs/operation-inventory.md) &mdash; the authoritative, regenerable **0&ndash;433 operation inventory** (434 total operations: 432 create-equivalent + 1 README update + 1 `app/` deletion; 24 static/reference assets; 409 non-asset changed paths), reconstructed from `git diff --name-status`. It is the single source of truth for migration scope and operation counts.
* [`docs/validation-gate-manifest.md`](./docs/validation-gate-manifest.md) &mdash; the ordered **eight-gate validation manifest** (compile, zero-warning, unit tests, integration tests, coverage, OWASP, traceability, BUILD SUCCESS) with observed evidence, the complete **49-finding resolution matrix**, and the in-remediation correction/retest record.

A reveal.js executive-summary presentation is present at [`blitzy-deck/index.html`](./blitzy-deck/index.html); the canonical Blitzy reveal.js theme it depends on is at [`blitzy-deck/references/blitzy-reveal-theme.css`](./blitzy-deck/references/blitzy-reveal-theme.css). The deck loads its three presentation libraries &mdash; reveal.js 5.1.0, Mermaid 11.16.0, and Lucide 0.460.0 &mdash; from the jsDelivr CDN (each pinned by a Subresource-Integrity hash and served under a restrictive Content-Security-Policy), so it **requires network access when opened** and is not a fully offline, self-contained bundle. Open it over a local web server (for example `python3 -m http.server` from the `blitzy-deck/` directory) so the Content-Security-Policy resolves correctly.

<br/>

## Legacy mainframe installation (reference)

> **Note:** The steps in this section and in [Running full batch (legacy reference)](#running-full-batch-legacy-reference) apply to the **original COBOL** retained under [`legacy/`](./legacy) and are kept for reference and traceability only. They are **not** required to build or run the migrated Java application &mdash; see [Running the Java application](#running-the-java-application) for that.

To install this repository on the mainframe please follow the following steps

1. Clone this repository to your local development environment

2. Create datasets on the mainframe  hold the code
   * It is recommended to group them under a High Level Qualifier (HLQ)for all your datasets.
   * Upload the following application source folders from the main branch of git repository on to your mainframe
      using $INDFILE or your preferred upload tool.
   * If you have used AWS.M2 as your HLQ, you should end up with the below code structure on the mainframe

      | HLQ    | Name          | Format | Length |
      | :----- | :------------ | :----- | -----: |
      | AWS.M2 | CARDDEMO.JCL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.PROC | FB     |     80 |
      | AWS.M2 | CARDDEMO.CBL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.CPY  | FB     |     80 |
      | AWS.M2 | CARDDEMO.BMS  | FB     |     80 |

3. Use data for testing using either of the below approaches

   ** Use the supplied sample data**

      * Upload the sample data provided in the main/-/data/EBCDIC/ folder to the mainframe. Ensure that you use transfer mode binary

         | Dataset name                      | Name                                             | Copybook (Layout) | Format | Length | Name of equivalent ascii file |
         | :---------------------------------| :----------------------------------------------- | :-----            | :----- | -----: | :---------------------------- |
         | AWS.M2.CARDDEMO.USRSEC.PS         | User Security file                               | CSUSR01Y          | FB     |     80 | See DEFUSR01.jcl (inline)     |
         | AWS.M2.CARDDEMO.ACCTDATA.PS       | Account Data                                     | CVACT01Y          | FB     |    300 | acctdata.txt                  |
         | AWS.M2.CARDDEMO.CARDDATA.PS       | Card Data                                        | CVACT02Y          | FB     |    150 | carddata.txt                  |
         | AWS.M2.CARDDEMO.CUSTDATA.PS       | Customer Data                                    | CVCUS01Y          | FB     |    500 | custdata.txt                  |
         | AWS.M2.CARDDEMO.CARDXREF.PS       | Customer Account Card Cross reference            | CVACT03Y          | FB     |     50 | cardxref.txt                  |
         | AWS.M2.CARDDEMO.DALYTRAN.PS.INIT  | Transaction database initialization record       | CVTRA06Y          | FB     |    350 | 1 record (low-values ending with 00000100)|
         | AWS.M2.CARDDEMO.DALYTRAN.PS       | Transaction data which has to go through posting | CVTRA06Y          | FB     |    350 | dailytran.txt                 |
         | AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS| Transaction data entered online                  | CVTRA05Y          | FB     |    350 | not applicable                |
         | AWS.M2.CARDDEMO.DISCGRP.PS        | Disclosure Groups                                | CVTRA02Y          | FB     |     50 | discgrp.txt                   |
         | AWS.M2.CARDDEMO.TRANCATG.PS       | Transaction Category Types                       | CVTRA04Y          | FB     |     60 | trancatg.txt                  |
         | AWS.M2.CARDDEMO.TRANTYPE.PS       | Transaction Types                                | CVTRA03Y          | FB     |     60 | trantype.txt                  |
         | AWS.M2.CARDDEMO.TCATBALF.PS       | Transaction Category Balance                     | CVTRA01Y          | FB     |     50 | tcatbal.txt                   |

      * Execute the following JCLs in order

         | Jobname  | What it does                                        |
         | :------- | :-------------------------------------------------- |
         | DUSRSECJ | Sets up user security vsam file                     |
         | CLOSEFIL | Closes files opened by CICS                         |
         | ACCTFILE | Loads Account database using sample data            |
         | CARDFILE | Loads Card database with credit card sample data    |
         | CUSTFILE | Creates customer database                           |
         | XREFFILE | Loads Customer Card account cross reference to VSAM |
         | TRANFILE | Copies initial Trasaction file  to VSAM             |
         | DISCGRP  | Copies initial Disclosure Group file  to VSAM       |
         | TCATBALF | Copies initial TCATBALF file  to VSAM               |
         | TRANCATG | Copies initial transaction category file  to VSAM   |
         | TRANTYPE | Copies initial transaction type file                |
         | OPENFIL  | Makes files available to CICS                       |
         | DEFGDGB  | Defines GDG Base                                    |


4. Compile the Programs.

   You should use the compile process followed by your mainframe shopfloor

   We have however provided some sample JCLs in the samples folder in git to help you craft the JCL

5. Create resources in the CARDDEMO group in CICS

   You have 2 options

   Be sure to edit the HLQs in the below documents as required before you do the definition

   * (Preferred) . Use the DFHCSDUP JCL that the resources required by the application

      The resources required are in the CSD file provided in the CSD folder

      * Group CARDDEMO
      * Mapsets
      * Transactions
      * Maps
      * Files

   * Use the CEDA transaction to execute the commands in the above listing

      * Define group
         ```shell
         DEFINE LIBRARY(COM2DOLL) GROUP(CARDDEMO) DSNAME01(&HLQ..LOADLIB)
         ```
      * Define Mapsets, Maps , Programs and Files

         Sample CEDA commands

         ```shell
         DEF PROGRAM(COCRDLIC) GROUP(CARDDEMO)
         DEF MAPSET(COCRDLI) GROUP(CARDDEMO)
         DEFINE PROGRAM(COSGN00C) GROUP(CARDDEMO) DA(ANY) TRANSID(CC00) DESCRIPTION(LOGIN)
         DEFINE TRANSACTION(CC00) GROUP(CARDDEMO) PROGRAM(COSGN00C) TASKDATAL(ANY)
         ```

   * Install /Load the online resources to your CICS region

      ```shell
      CEDA INSTALL TRANS(CCLI) GROUP(CARDDEMO)
      CEDA INSTALL FILE(CARDDAT) GROUP(CARDDEMO)
      CECI LOAD PROG(COCRDUP)
      CECI LOAD PROG(COCRDUPC)
      ```

   * Execute a NEWCOPY of mapsets and maps
      ```shell
      CEMT SET PROG(COCRDUP) NEWCOPY
      CEMT SET PROG(COCRDUPC) NEWCOPY
      ```
6. Enjoy the demo

   * For online functions : Start the CardDemo application using the CC00 transaction
     - Enter userid ADMIN001 and the initially configured password to manage users
     - Enter userid USER0001 and the initially configured password to access back office functions
   * For batch            : See the instructions for running full batch below.

## Running full batch (legacy reference)

> **Note:** This section also applies to the original COBOL under [`legacy/`](./legacy) and is retained for reference only; in the migrated application the batch workload runs as Spring Batch jobs (see [Batch jobs](#batch-jobs)).

  * Execute the following JCLs in order

    | Jobname  | What it does                                        |
    | :------- | :-------------------------------------------------- |
    | CLOSEFIL | Closes files opened by CICS                         |
    | ACCTFILE | Loads Account database using sample data            |
    | CARDFILE | Loads Card database with credit card sample data    |
    | XREFFILE | Loads Customer Card account cross reference to VSAM |
    | CUSTFILE | Creates customer database                           |
    | TRANBKP  | Creates Transaction database                        |
    | DISCGRP  | Copies initial disclosure Group file  to VSAM       |
    | TCATBALF | Copies initial TCATBALF file  to VSAM               |
    | TRANTYPE | Copies initial transaction type file                |
    | DUSRSECJ | Sets up user security vsam file                     |
    | POSTTRAN | Core processing job                                 |
    | INTCALC  | Run interest calculations                           |
    | TRANBKP  | Backup Transaction database                         |
    | COMBTRAN | Combine system transactions with daily ones         |
    | CREASTMT | Produce transaction statement                       |
    | TRANIDX  | Define alternate index on transaction file          |
    | OPENFIL  | Makes files available to CICS                       |
<br/>

## Application Details
The CardDemo is a Credit Card management application, built primarily using COBOL programming language. The application has various functions that allows users to manage Account, Credit card, Transaction and Bill payment.

There are 2 types of users:
* Regular User
* Admin User

The Regular user can perform the user functions and the Admin users can only perform Admin functions.

<br/>

### User Functions

![Alt text](./diagrams/Application-Flow-User.png?raw=true "User Flow")

<br/>

### Admin Functions

![Alt text](./diagrams/Application-Flow-Admin.png?raw=true "Admin Flow")

<br/>

### Application Inventory

In the migrated application, each online transaction id maps to a Spring MVC controller route, each COBOL program maps to a `@Service` (its numbered paragraphs becoming methods), and each batch JCL job maps to a Spring Batch `Job`. See [`docs/traceability-matrix.md`](./docs/traceability-matrix.md) for the complete mapping.

#### **Online**

| Transaction |      | BMS Map | Program  | Function            |
| :---------- | :--- | :------ | :------- | :------------------ |
| CC00        |      | COSGN00 | COSGN00C | Signon Screen       |
| CM00        |      | COMEN01 | COMEN01C | Main Menu           |
|             | CAVW | COACTVW | COACTVWC | Account View        |
|             | CAUP | COACTUP | COACTUPC | Account Update      |
|             | CCLI | COCRDLI | COCRDLIC | Credit Card List    |
|             | CCDL | COCRDSL | COCRDSLC | Credit Card View    |
|             | CCUP | COCRDUP | COCRDUPC | Credit Card Update  |
|             | CT00 | COTRN00 | COTRN00C | Transaction List    |
|             | CT01 | COTRN01 | COTRN01C | Transaction View    |
|             | CT02 | COTRN02 | COTRN02C | Transaction Add     |
|             | CR00 | CORPT00 | CORPT00C | Transaction Reports |
|             | CB00 | COBIL00 | COBIL00C | Bill Payment        |
| CA00        |      | COADM01 | COADM01C | Admin Menu          |
|             | CU00 | COUSR00 | COUSR00C | List Users          |
|             | CU01 | COUSR01 | COUSR01C | Add User            |
|             | CU02 | COUSR02 | COUSR02C | Update User         |
|             | CU03 | COUSR03 | COUSR03C | Delete User         |

#### **Batch**

| Job      | Program  | Function                                   |
| :------- | :------- | :----------------------------------------- |
| DUSRSECJ | IEBGENER | Initial Load of User security file         |
| DEFGDGB  | IDCAMS   | Setup GDG Bases                            |
| ACCTFILE | IDCAMS   | Refresh Account Master                     |
| CARDFILE | IDCAMS   | Refresh Card Master                        |
| CUSTFILE | IDCAMS   | Refresh Customer Master                    |
| DISCGRP  | IDCAMS   | Load Disclosure Group File                 |
| TRANFILE | IDCAMS   | Load Transaction Master file               |
| TRANCATG | IDCAMS   | Load Transaction category types            |
| TRANTYPE | IDCAMS   | Load Transaction type file                 |
| XREFFILE | IDCAMS   | Account, Card and Customer cross reference |
| CLOSEFIL | IEFBR14  | Close VSAM files in CICS                   |
| TCATBALF | IDCAMS   | Refresh Transaction Category Balance       |
| TRANBKP  | IDCAMS   | Refresh Transaction Master                 |
| POSTTRAN | CBTRN02C | Transaction processing job                 |
| TRANIDX  | IDCAMS   | Define AIX for transaction file            |
| OPENFIL  | IEFBR14  | Open files in CICS                         |
| INTCALC  | CBACT04C | Run interest calculations                  |
| COMBTRAN | SORT     | Combine transaction files                  |
| CREASTMT | CBSTM03A | Produce transaction statement              |

<br/>

### Application Screens

#### **Signon Screen**

![Alt text](./diagrams/Signon-Screen.png?raw=true "Signon Screen")


#### **Main Menu**

![Alt text](./diagrams/Main-Menu.png?raw=true "Main Menu")

#### **Admin Menu**

![Alt text](./diagrams/Admin-Menu.png?raw=true "Admin Menu")

<br/>

## Support

If you have questions or requests for improvement please raise an issue in the repository.

<br/>

## Roadmap

The following features are **not implemented**; they remain on the future roadmap and are explicitly out of scope for the current migration, which preserves existing behavior without feature expansion:

1. More database types

   1. Relational Database usage : Db2

   2. Hierachical database calls : IMS

2. Integration

   * ftp, sftp

   * Message queue integration

   * Exposure of transactions for distributed application integration

In addition, the following migration follow-ups are recommended but are **not delivered in this migration** (recorded under "Suggested next tasks" in [`docs/decision-log.md`](./docs/decision-log.md)):

* Upgrade to **Spring Boot 4.x** &mdash; or procure commercial/extended 3.5 support (a pre-production prerequisite). The Spring Boot 3.5 line reached open-source end-of-life on 2026-06-30, so no future *upstream* CVE patches will be published for 3.5.16. The framework version is fixed at 3.5.16 by the frozen migration plan (AAP&nbsp;§0.7.3), so the upgrade is out of scope for this migration; documenting the EOL is **not** a substitute for vendor support, so before any production deployment either obtain supported commercial/extended maintenance for 3.5.16 or complete the 4.x upgrade and re-run every parity/security gate. Shipped-artifact CVEs are already patched at source via `pom.xml` overrides.
* Introduce **password hashing (BCrypt)** &mdash; replace the preserved cleartext password comparison (kept for behavioral parity) with a modern password-hashing scheme.
* Add a **CI/CD pipeline** under `.github/workflows/**` &mdash; the repository currently has no CI pipeline.

<br/>

## Contributing

We are looking forward to receiving contributions and enhancements to this initial codebase from the mainframe code base

Feel free to raise issues, create code and raise merge requests for enhancements so that we can build out this application as a resource for programmers wanting to understand and modernize their mainframes.

<br/>

## License

This is intended to be a community resource and it is released under the Apache 2.0 license.

<br/>

## Project status

We are planning a v2 of this application in Q1 2023.

Watch this space for updates

<br/>
