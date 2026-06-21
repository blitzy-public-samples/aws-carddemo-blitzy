# CardDemo — Credit Card Management Application

CardDemo is a credit-card management system that was originally built for the IBM z/OS
mainframe (COBOL / CICS / VSAM / JCL / BMS) and has now been **modernized to Java 25 LTS +
Spring Boot 3.x**. Both implementations live in this single repository: the modernized Java
application is the primary, actively built artifact, while the original COBOL source is
retained **read-only** under `legacy/app/` as the behavioral specification, traceability
anchor, and golden-file parity reference.

- [Description](#description)
- [Modernized Java Application](#modernized-java-application)
- [Technologies used (Modernized Stack)](#technologies-used-modernized-stack)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Build and Run (Java)](#build-and-run-java)
  - [1. Start PostgreSQL locally](#1-start-postgresql-locally)
  - [2. Build the application](#2-build-the-application)
  - [3. Run the application](#3-run-the-application)
  - [4. Run the full stack with Docker](#4-run-the-full-stack-with-docker)
- [Project Structure](#project-structure)
- [Batch Jobs (Spring Batch)](#batch-jobs-spring-batch)
- [Security](#security)
- [Testing](#testing)
- [Continuous Integration](#continuous-integration)
- [Traceability](#traceability)
- [Legacy Mainframe Application (retained read-only under `legacy/app/`)](#legacy-mainframe-application-retained-read-only-under-legacyapp)
  - [Technologies (legacy mainframe stack)](#technologies-legacy-mainframe-stack)
  - [Installation on the mainframe](#installation-on-the-mainframe)
  - [Running full batch](#running-full-batch)
  - [Application Details](#application-details)
  - [Application Inventory](#application-inventory)
  - [Application Screens](#application-screens)
- [Support](#support)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Project status](#project-status)

<br/>

## Description

This repository contains **two complete implementations** of the CardDemo credit-card
management application:

1. **Modernized Java application (primary).** A layered Java 25 / Spring Boot 3.x service that
   re-expresses every legacy business rule, calculation, validation, and control-flow branch as
   object-oriented, layered code. Online CICS transactions become Spring MVC controllers and
   services, batch COBOL/JCL becomes Spring Batch jobs, VSAM/sequential files become PostgreSQL
   tables accessed through Spring Data JPA, and copybooks become JPA entities and DTOs. This is
   the artifact that is built, tested, and run.

2. **Legacy mainframe application (reference, read-only).** The original z/OS COBOL / CICS /
   VSAM / JCL / BMS source, retained **read-only** under `legacy/app/`. It is **not** modified or
   deleted; it serves as the authoritative behavioral specification, the COBOL-paragraph →
   Java-method traceability anchor, and the source of golden-file parity fixtures used by the
   test suite.

The migration goal is **100% behavioral parity with zero functional regression**: every business
rule, monetary calculation, validation, branch, and edge case in the original 28 COBOL programs is
reproduced exactly in the Java implementation. All fixed-point monetary and rate fields use
`java.math.BigDecimal` with COBOL-faithful scale and truncation — floating-point types are never
used for decimal data.

> Note: the original application was intended to provide mainframe coding scenarios to exercise
> analysis, transformation, and migration tooling, so the legacy COBOL coding style is
> intentionally not uniform across programs. The Java rewrite normalizes this into a consistent,
> idiomatic Spring Boot codebase while preserving behavior.

<br/>

## Modernized Java Application

The modernized application is a single-module Maven Spring Boot project rooted at the Java package
`com.aws.carddemo`. It preserves the legacy system's behavior while adopting a conventional layered
architecture:

- **Online transactions** — the 17 pseudo-conversational CICS/BMS transactions are re-implemented
  as Spring MVC controllers (`web/`) backed by services (`service/online/`). COMMAREA-driven
  navigation and `ADMIN`/`USER` role gating are preserved as server-side session/navigation state.
- **Batch jobs** — the JCL-driven batch programs are re-implemented as Spring Batch jobs
  (`batch/`) with chunk-oriented readers, processors, and writers; JCL execution parameters (such
  as the interest-calculation run date) map to Spring Batch `JobParameters`.
- **Persistence** — VSAM KSDS and sequential files become PostgreSQL tables accessed through Spring
  Data JPA repositories (`repository/`), with schema and seed data managed by Flyway migrations.
- **Domain model** — each copybook 01-level record layout becomes exactly one Java type: JPA
  entities (`domain/`) and DTOs (`dto/`).

The remaining sections describe the technology stack, architecture, how to build and run the
application, the project layout, and the quality gates. The original mainframe documentation is
preserved in full under [Legacy Mainframe Application](#legacy-mainframe-application-retained-read-only-under-legacyapp).

<br/>

## Technologies used (Modernized Stack)

The modernized application targets the following stack. Transitive library versions are governed
by the Spring Boot dependency BOM and are confirmed at build time; only build plugins are pinned
explicitly.

| Area | Technology | Version | Purpose |
| :--- | :--------- | :------ | :------ |
| Language / runtime | Java (Temurin/OpenJDK) | 25 (LTS) | Compile + runtime target (`<release>25</release>`) |
| Framework | Spring Boot (Spring Framework) | 3.5.x (6.2.x) | Application framework; the 3.5.x line is required for Java 25 runtime support |
| Web (online screens) | Spring MVC + Thymeleaf | BOM-managed | Re-implements CICS/BMS online transactions and server-side screen rendering |
| Persistence | Spring Data JPA | BOM-managed | Repositories replacing VSAM/sequential file access |
| Batch | Spring Batch | BOM-managed | Chunk-oriented jobs replacing JCL batch steps |
| Security | Spring Security | BOM-managed | Authentication and `ADMIN`/`USER` role gating; credential externalization |
| Validation | Spring Validation (Bean Validation) | BOM-managed | Field-level rule enforcement |
| Database | PostgreSQL | 16+ | Relational store for the migrated VSAM/sequential data |
| Migrations | Flyway | BOM-managed | Versioned DDL and reference-data seed migrations |
| Build tool | Apache Maven (wrapper provided) | 3.9+ | Build, test, and packaging via `./mvnw` |
| Testing | JUnit 5, Mockito, AssertJ, Testcontainers | BOM-managed | Unit and integration tests against an ephemeral PostgreSQL |
| Coverage | JaCoCo | 0.8.15 | Enforces the ≥80% line-coverage quality gate |
| Security scanning | OWASP dependency-check | 12.2.2 | Fails the build on critical/high CVEs |
| Code style | Spotless (google-java-format) | 3.7.0 | Enforces a consistent, zero-warning code style |
| Mapping | MapStruct | 1.6.3 | Entity ↔ DTO mapping |
| Local infrastructure | Docker / Docker Compose | — | Brings up a local PostgreSQL instance for development and runtime |

> The original mainframe technology stack — **COBOL, CICS, VSAM, JCL, and RACF** — is described in
> the [Legacy Mainframe Application](#legacy-mainframe-application-retained-read-only-under-legacyapp)
> section. That source is retained read-only for reference and parity testing.

<br/>


## Architecture

The migration re-expresses the procedural, file-centric COBOL/CICS architecture as a layered
Spring Boot application while holding behavior constant. Each legacy construct maps to a specific
Java/Spring target and package:

| Legacy (z/OS) construct | Java / Spring target | Package |
| :---------------------- | :------------------- | :------ |
| Copybook record layouts (DATA DIVISION 01-levels) | JPA entities and DTOs (one Java type per copybook) | `domain/`, `dto/` |
| VSAM KSDS and sequential files | PostgreSQL tables accessed via Spring Data repositories | `repository/` |
| COBOL paragraphs / sections | Service methods preserving the original perform/call order | `service/online/`, `service/batch/` |
| JCL job steps | Spring Batch jobs (reader → processor → writer) and tasklets | `batch/` |
| BMS mapsets (3270 screens) | Web controllers + per-screen request/response DTOs | `web/`, `dto/screen/` |
| COMMAREA pseudo-conversational state | Server-side session / navigation state | `dto/` (e.g. `CardDemoCommarea`) |
| FILE STATUS codes / CICS RESP | Typed exception hierarchy + `@ControllerAdvice` | `exception/` |
| `CALL 'subprogram'` | Spring bean injection / method invocation | `service/` |
| Date/format/string utilities | Shared utility components | `util/` |

The web layer depends on the service layer, the service layer depends on the repository layer, and
all layers share the domain and DTO model — a conventional, testable Spring Boot dependency
direction.

### Decimal and arithmetic fidelity

Every COBOL fixed-point monetary or rate field (`PIC S9(n)V99`) maps to `java.math.BigDecimal` with
an explicit scale; `float` and `double` are **never** used for decimal data. COBOL truncation and
rounding behavior is reproduced exactly — for example, the monthly interest computation
`(TRAN-CAT-BAL * DIS-INT-RATE) / 1200` carries no `ROUNDED` phrase in COBOL, so the Java
implementation uses `BigDecimal` arithmetic with `RoundingMode.DOWN` at scale 2 and preserves the
`/1200` monthly divisor, producing results that match the legacy output to the cent. Edited report
fields (for example `-ZZZ,ZZZ,ZZZ.ZZ`) are rendered through dedicated formatter utilities so report
output remains byte-faithful.

### Reference diagrams

The entity/data model and navigation flows are documented under [`diagrams/`](diagrams):
`CARDDEMO-DataModel.drawio` (entity/data-model reference) plus the navigation-flow and screen images
that are also shown in the [Legacy Mainframe Application](#legacy-mainframe-application-retained-read-only-under-legacyapp)
section. These informed the PostgreSQL schema and the preserved UI/navigation contract.

<br/>


## Prerequisites

- **JDK 25** (Eclipse Temurin recommended) — required to compile and run the application.
- **Docker** and **Docker Compose** — used to run a local PostgreSQL 16+ instance and, optionally,
  the full application stack.
- **No local Maven installation is required** — the project ships a Maven wrapper (`./mvnw` on
  Linux/macOS, `mvnw.cmd` on Windows) that downloads and uses the correct Maven version (3.9+)
  automatically.

The automated test suite uses Testcontainers, which starts its own ephemeral PostgreSQL container,
so a running database is **not** required to execute the tests — only Docker must be available.

<br/>

## Build and Run (Java)

### 1. Start PostgreSQL locally

A `docker-compose.yml` at the repository root defines a PostgreSQL 16+ service for local
development and runtime. Start it in the background:

```bash
docker compose up -d
```

By default this exposes PostgreSQL on `localhost:5432` with database, user, and password all set to
`carddemo` (a **local-development default only** — production and CI must override
`CARDDEMO_DB_PASSWORD` via the environment). The container is initialized with the `C` locale so key
ordering matches the original VSAM semantics.

### 2. Build the application

Build, run all tests, and enforce the quality gates (JaCoCo coverage, Spotless formatting, and the
OWASP dependency-check) with a single command:

```bash
# Linux / macOS
./mvnw clean verify
```

```bat
:: Windows
mvnw.cmd clean verify
```

`clean verify` compiles the sources, runs the unit and integration tests, and fails the build if the
≥80% line-coverage gate, the code-style checks, or the CVE scan are not satisfied. To produce the
runnable JAR without running the full verification, use `./mvnw clean package`.

> **OWASP dependency-check & the NVD API key.** The CVE scan downloads the National Vulnerability
> Database (NVD) feed, which is heavily rate-limited for anonymous clients. Supply an
> [NVD API key](https://nvd.nist.gov/developers/request-an-api-key) to make the scan fast and
> reliable: `./mvnw clean verify -DnvdApiKey=$NVD_API_KEY`. In CI the key is provided by the
> `NVD_API_KEY` repository secret (see *Continuous Integration* below). An offline or key-less build
> may opt out of **only** this gate with `./mvnw clean verify -Ddependency-check.skip=true`; the gate
> is otherwise enabled by default.

> **Zero-warning build on Java 25.** The compiler runs with `-Werror -Xlint:all` so source
> warnings fail the build. Two pieces of build configuration keep the *runtime* (Maven/test JVM)
> output free of the JDK 25 warnings emitted by tooling dependencies:
> - **`.mvn/jvm.config`** passes `--sun-misc-unsafe-memory-access=allow` and
>   `--enable-native-access=ALL-UNNAMED` to the Maven JVM, silencing the `sun.misc.Unsafe` warning
>   from Maven's bundled Guice and the Jansi native-access warning. These flags only relax JDK
>   diagnostics for Maven's own libraries; they do not alter application behavior.
> - The **Surefire** `argLine` loads Mockito as an explicit `-javaagent` (with `-Xshare:off`),
>   following Mockito's JDK 21+ guidance, so the inline mock-maker no longer self-attaches at
>   runtime. `@{argLine}` is late-bound to JaCoCo's coverage agent, so the ≥80% line-coverage gate
>   is unaffected. The Maven wrapper is pinned to Apache Maven **3.9.11** in
>   `.mvn/wrapper/maven-wrapper.properties`.

### 3. Run the application

The application reads its database connection and seed credentials from the environment, so no
secrets are hardcoded. Export the required variables and start the app:

```bash
# Database connection (point at the local Docker PostgreSQL started above)
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/carddemo"
export SPRING_DATASOURCE_USERNAME="carddemo"
export SPRING_DATASOURCE_PASSWORD="<your-db-password>"

# Seed sign-on credentials (BCrypt-hashed at startup; never committed)
export CARDDEMO_ADMIN_PASSWORD="<choose-an-admin-password>"
export CARDDEMO_USER_PASSWORD="<choose-a-user-password>"

# Option A: run via the Spring Boot Maven plugin
./mvnw spring-boot:run

# Option B: run the packaged JAR
java -jar target/*.jar
```

Spring profiles select environment-specific configuration backed by `application.yml`
(default profile) and `application-test.yml` (the `test` profile, used by the integration tests).
Activate a profile with `--spring.profiles.active=<profile>` or the `SPRING_PROFILES_ACTIVE`
environment variable, for example:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=default
```

On startup, Flyway applies the schema and reference-data migrations automatically, and the seed
users are created with BCrypt-hashed passwords taken from the environment variables above. Once the
application is running, sign on as described in [Security](#security).

### 4. Run the full stack with Docker

A `Dockerfile` at the repository root builds an application image, and `docker-compose.yml` can run
the application together with PostgreSQL. Build the image and start the stack with:

```bash
# Build the application image
docker build -t carddemo:latest .

# Bring up PostgreSQL (and the app, when enabled in the compose file)
docker compose up -d
```

Provide the same `SPRING_DATASOURCE_*` and seed-credential environment variables to the container
(via an env file or your orchestrator) so the application can connect to the database and seed its
users securely.

<br/>


## Project Structure

The repository is a single-module Maven project. The modernized Java application lives under
`src/`, the build/run scaffolding lives at the repository root, and the original COBOL source is
retained read-only under `legacy/app/`. The `diagrams/` folder remains at the repository root.

> **How "read-only" is enforced.** Git tracks the legacy source like any other file, so it cannot
> be made filesystem-immutable inside a clone. Instead the read-only rule (AAP §0.7.2, Rule R7) is
> enforced as **governance**: the `legacy-readonly` job in `.github/workflows/ci.yml` fails the
> build if any commit modifies a file under `legacy/`, and `.github/CODEOWNERS` routes any pull
> request that touches the legacy tree to the maintainers (make this a hard gate by enabling
> "Require review from Code Owners" branch protection). The legacy tree therefore remains
> byte-identical to its baseline; it is reference-only and is never modified by the migration.

```text
.
├── pom.xml                          # Maven build manifest (Java 25, Spring Boot 3.5.x)
├── mvnw, mvnw.cmd, .mvn/            # Maven wrapper (no local Maven install required)
├── Dockerfile                       # Application image
├── docker-compose.yml               # Local PostgreSQL 16+ (and optional app) service
├── .gitignore
├── README.md                        # This file
├── LICENSE, NOTICE                  # Apache License 2.0
├── .github/
│   ├── CODEOWNERS                   # Review governance (routes legacy/ changes to maintainers)
│   └── workflows/
│       └── ci.yml                   # CI: build + tests + JaCoCo (>=80%) + OWASP + legacy read-only guard
├── docs/
│   └── traceability-matrix.md       # 100% COBOL paragraph -> Java method mapping
├── diagrams/                        # Data-model and navigation/screen references (unchanged)
│   ├── CARDDEMO-DataModel.drawio
│   ├── Application-Flow-User.png
│   ├── Application-Flow-Admin.png
│   ├── Signon-Screen.png
│   ├── Main-Menu.png
│   └── Admin-Menu.png
├── legacy/                          # Original mainframe source, retained READ-ONLY
│   └── app/
│       ├── cbl/                     # COBOL programs (28)
│       ├── cpy/                     # Copybooks (record layouts, constants)
│       ├── cpy-bms/                 # BMS symbolic copybooks
│       ├── bms/                     # BMS mapset sources (3270 screens)
│       ├── jcl/                     # JCL members
│       ├── proc/                    # JCL PROCs
│       ├── ctl/                     # IDCAMS control statements
│       ├── csd/                     # CICS resource definitions (CARDDEMO.CSD)
│       ├── catlg/                   # VSAM catalog listing (keys, indexes)
│       └── data/                    # Sample fixtures (ASCII + EBCDIC)
└── src/
    ├── main/
    │   ├── java/com/aws/carddemo/
    │   │   ├── CardDemoApplication.java   # @SpringBootApplication entry point
    │   │   ├── domain/                    # JPA entities (<- copybooks)
    │   │   ├── dto/                        # DTOs, COMMAREA, screen DTOs (dto/screen/)
    │   │   ├── repository/                 # Spring Data JPA repositories (<- VSAM files)
    │   │   ├── service/
    │   │   │   ├── online/                 # Online transaction services (<- CICS programs)
    │   │   │   └── batch/                  # Batch services (<- batch COBOL)
    │   │   ├── batch/                      # Spring Batch job configs, readers, processors, writers
    │   │   ├── web/                        # Controllers (<- CICS online programs)
    │   │   ├── config/                     # DataSource, Batch, Security, Jackson configuration
    │   │   ├── exception/                  # Typed exceptions (<- FILE STATUS / CICS RESP)
    │   │   └── util/                       # Date/string/format utilities (<- CSUTLDTC, CVTRA07Y)
    │   └── resources/
    │       ├── application.yml             # Default configuration
    │       ├── application-test.yml        # Test profile configuration
    │       ├── db/migration/               # Flyway migrations (V1__schema.sql, V2__seed_*.sql)
    │       └── templates/                  # Thymeleaf screen templates
    └── test/
        ├── java/                           # Unit + Testcontainers integration + golden-file parity tests
        └── resources/                      # Seed fixtures (<- legacy/app/data/ASCII)
```

<br/>


## Batch Jobs (Spring Batch)

Each legacy JCL job step is re-implemented as a Spring Batch job. Read → validate → post → write
loops become chunk-oriented steps (reader/processor/writer), while single-action steps such as
sorts and file copies become tasklets. JCL execution parameters map to Spring Batch
`JobParameters` — for example, the interest-calculation run date supplied to the COBOL job as
`PARM='2022071800'` is passed as a typed job parameter so the batch result is reproducible.

| Modernized job | COBOL source | Legacy JCL | Function |
| :------------- | :----------- | :--------- | :------- |
| Transaction posting | CBTRN02C | POSTTRAN | Daily transaction posting: validation, category-balance upsert, account update, reject write |
| Interest calculation | CBACT04C | INTCALC | Monthly interest `(balance * rate) / 1200`, truncated; run date → `JobParameter` |
| Statement generation | CBSTM03A + CBSTM03B | CREASTMT | Statement production (CBSTM03B subroutine → injected `FileIoService`) |
| Transaction report | CBTRN03C | TRANREPT | Transaction detail report with page/account/grand totals |
| Account extract | CBACT01C | READACCT | Read/print the account master |
| Card extract | CBACT02C | READCARD | Read/print the card master |
| Customer extract | CBCUS01C | READCUST | Read/print the customer master |
| Card cross-reference extract | CBACT03C | READXREF | Read/print the card/account cross-reference |
| Daily transaction post | CBTRN01C | — | Daily transaction posting (no shipped JCL driver) |
| Transaction combine / sort | SORT + IDCAMS | COMBTRAN | `SORT FIELDS=(TRAN-ID,A)` merge and file copy → sort + copy tasklets |
| Category-balance report | — | PRTCATBL | Print the transaction category balances |

Invalid daily transactions are routed to a reject writer that preserves the legacy reject-file
record layout and reason codes. The original full-batch JCL execution sequence is preserved for
reference under [Running full batch](#running-full-batch) in the Legacy section.

<br/>

## Security

The legacy application stored a clear-text password and relied on the mainframe RACF model. The
modernized application replaces this with Spring Security:

- **BCrypt password hashing** — user passwords are stored only as BCrypt hashes; clear-text
  passwords are never persisted.
- **Externalized secrets** — all credentials are supplied through environment variables or external
  configuration (for example `CARDDEMO_ADMIN_PASSWORD`, `CARDDEMO_USER_PASSWORD`, and the
  `SPRING_DATASOURCE_*` variables). **No secrets are hardcoded** in source or committed to version
  control.
- **Role-based authorization** — online functions are gated by role. Administrative screens (user
  management and the admin menu) require the `ADMIN` role, while standard back-office functions are
  available to the `USER` role, mirroring the legacy `CDEMO-USRTYP-ADMIN` / `CDEMO-USRTYP-USER`
  distinction.

The legacy default identities — `ADMIN001` (administrator) and `USER0001` (standard user) — are
seeded at startup as BCrypt-hashed users whose passwords are taken from the environment variables
above; the legacy hardcoded password is no longer used.

<br/>

## Testing

Run the unit tests, or run the full verification (tests plus quality gates), with the wrapper:

```bash
# Unit tests only
./mvnw test

# Full verification: tests + JaCoCo coverage + Spotless + OWASP checks
./mvnw verify
```

- **JUnit 5** drives unit tests (with Mockito and AssertJ) and integration tests.
- **Testcontainers** starts an ephemeral PostgreSQL container for integration tests, seeded from the
  legacy fixtures under `legacy/app/data/ASCII/*.txt`; no external database or mainframe is required.
- **Golden-file parity tests** compare the Java outputs (statements, reports, reject files) against
  the expected COBOL output to verify behavioral parity to the byte/cent.
- **Coverage gate** — JaCoCo enforces a **≥80% line-coverage** threshold; the build fails if coverage
  drops below it.

<br/>

## Continuous Integration

The GitHub Actions workflow at `.github/workflows/ci.yml` runs on every push and pull request and
enforces the project's quality gates:

1. Build the project and compile all sources with zero warnings.
2. Run the unit and integration tests (Testcontainers PostgreSQL).
3. Enforce the **JaCoCo ≥80% line-coverage** gate.
4. Run the **OWASP dependency-check** and fail on any **critical/high** CVE.

The OWASP gate runs to completion (it is **never** skipped in CI): the workflow passes an
authenticated NVD API key from the `NVD_API_KEY` repository secret as `-DnvdApiKey=$NVD_API_KEY` and
caches the NVD database between runs. Configure the secret under **Settings → Secrets and variables →
Actions** before relying on the gate; request a key from
<https://nvd.nist.gov/developers/request-an-api-key>.

A green CI run is the bar for merging changes.

<br/>

## Traceability

A complete mapping from every COBOL paragraph to its corresponding Java method is maintained in
[`docs/traceability-matrix.md`](docs/traceability-matrix.md). The numbered control-flow paragraphs of
each program (for example the posting archetype's `1000-DALYTRAN-GET-NEXT`, `1500-VALIDATE-TRAN`,
`2000-POST-TRANSACTION`, `2500-WRITE-REJECT-REC`, `2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`,
and `2900-WRITE-TRANSACTION-FILE`) become private service methods invoked in the same order,
preserving the original control flow. The [Application Inventory](#application-inventory) tables in
the Legacy section provide the transaction/job → COBOL program cross-reference that anchors this
matrix.

<br/>


## Legacy Mainframe Application (retained read-only under `legacy/app/`)

The original z/OS mainframe implementation is retained **read-only** under `legacy/app/`. It is the
authoritative behavioral specification for the modernized Java application: the inventory and screen
tables below map every transaction and batch job to its COBOL program, which directly corresponds to
the new controllers, services, and Spring Batch jobs (see [Architecture](#architecture) and
[Batch Jobs (Spring Batch)](#batch-jobs-spring-batch)). The COBOL/CICS/VSAM/JCL/BMS source is **not**
modified or deleted; it also supplies the golden-file fixtures used by the parity tests.

> The folder paths below refer to the retained source under `legacy/app/` (for example
> `legacy/app/jcl`, `legacy/app/cbl`, `legacy/app/cpy`, `legacy/app/bms`, `legacy/app/csd`, and
> `legacy/app/data`). The mainframe installation steps are preserved verbatim for reference and are
> **not** required to build or run the modernized Java application.

### Technologies (legacy mainframe stack)

1. COBOL
2. CICS
3. VSAM
4. JCL
5. RACF

The original CardDemo application was designed and developed to test and showcase AWS and partner
technology for mainframe migration and modernization use-cases such as discovery, migration,
modernization, performance test, augmentation, service enablement, service extraction, test
creation, and test harness.

### Installation on the mainframe

To install the legacy application on the mainframe, follow these steps.

1. Clone this repository to your local development environment

2. Create datasets on the mainframe to hold the code
   * It is recommended to group them under a High Level Qualifier (HLQ) for all your datasets.
   * Upload the application source folders from `legacy/app/` of the git repository on to your
     mainframe using `$INDFILE` or your preferred upload tool (`legacy/app/jcl`, `legacy/app/proc`,
     `legacy/app/cbl`, `legacy/app/cpy`, `legacy/app/bms`).
   * If you have used `AWS.M2` as your HLQ, you should end up with the below code structure on the
     mainframe

      | HLQ    | Name          | Format | Length |
      | :----- | :------------ | :----- | -----: |
      | AWS.M2 | CARDDEMO.JCL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.PROC | FB     |     80 |
      | AWS.M2 | CARDDEMO.CBL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.CPY  | FB     |     80 |
      | AWS.M2 | CARDDEMO.BMS  | FB     |     80 |

3. Use data for testing using either of the below approaches

   **Use the supplied sample data**

      * Upload the sample data provided in the `legacy/app/data/EBCDIC/` folder to the mainframe.
        Ensure that you use transfer mode binary

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

   You should use the compile process followed by your mainframe shopfloor.

   We have however provided some sample JCLs in the `samples` folder in git to help you craft the JCL.

5. Create resources in the CARDDEMO group in CICS

   You have 2 options.

   Be sure to edit the HLQs in the below documents as required before you do the definition.

   * (Preferred) Use the DFHCSDUP JCL for the resources required by the application.

      The resources required are in the CSD file provided in the `legacy/app/csd` folder.

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
      * Define Mapsets, Maps, Programs and Files

         Sample CEDA commands

         ```shell
         DEF PROGRAM(COCRDLIC) GROUP(CARDDEMO)
         DEF MAPSET(COCRDLI) GROUP(CARDDEMO)
         DEFINE PROGRAM(COSGN00C) GROUP(CARDDEMO) DA(ANY) TRANSID(CC00) DESCRIPTION(LOGIN)
         DEFINE TRANSACTION(CC00) GROUP(CARDDEMO) PROGRAM(COSGN00C) TASKDATAL(ANY)
         ```

   * Install / Load the online resources to your CICS region

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

   * For online functions: Start the CardDemo application using the `CC00` transaction
     - Sign on as `ADMIN001` (administrator) to manage users
     - Sign on as `USER0001` (standard user) to access back-office functions
     - In the modernized Java application these identities are seeded with BCrypt-hashed passwords
       supplied via environment variables (see [Security](#security)); the legacy clear-text default
       password is no longer used.
   * For batch: See the instructions for running full batch below.

<br/>


### Running full batch

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

In the modernized application, this sequence is reproduced as Spring Batch jobs — see
[Batch Jobs (Spring Batch)](#batch-jobs-spring-batch).

<br/>

### Application Details

The CardDemo is a Credit Card management application, built primarily using the COBOL programming
language. The application has various functions that allow users to manage Account, Credit card,
Transaction and Bill payment.

There are 2 types of users:
* Regular User
* Admin User

The Regular user can perform the user functions and the Admin users can only perform Admin functions.

#### User Functions

![Alt text](./diagrams/Application-Flow-User.png?raw=true "User Flow")

#### Admin Functions

![Alt text](./diagrams/Application-Flow-Admin.png?raw=true "Admin Flow")

<br/>


### Application Inventory

These tables map each legacy transaction and batch job to its COBOL program. They are the
cross-reference anchor for the modernized controllers, services, and Spring Batch jobs and for the
[Traceability](#traceability) matrix.

#### Online

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

#### Batch

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

#### Signon Screen

![Alt text](./diagrams/Signon-Screen.png?raw=true "Signon Screen")

#### Main Menu

![Alt text](./diagrams/Main-Menu.png?raw=true "Main Menu")

#### Admin Menu

![Alt text](./diagrams/Admin-Menu.png?raw=true "Admin Menu")

<br/>


## Support

If you have questions or requests for improvement please raise an issue in the repository.

<br/>

## Roadmap

The following features were planned for a future v2 of the original mainframe application. They are
**explicitly out of scope** of the COBOL-to-Java migration documented here — the modernization
reproduces existing behavior only and does not add new business functionality:

1. More database types

   1. Relational Database usage: Db2

   2. Hierarchical database calls: IMS

2. Integration

   * ftp, sftp

   * Message queue integration

   * Exposure of transactions for distributed application integration

<br/>

## Contributing

We are looking forward to receiving contributions and enhancements to this codebase. Please see
[`CONTRIBUTING.md`](CONTRIBUTING.md) and the [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

Feel free to raise issues, create code and raise merge requests for enhancements so that we can
build out this application as a resource for programmers wanting to understand and modernize their
mainframes.

<br/>

## License

This is intended to be a community resource and it is released under the
[Apache License 2.0](LICENSE). New dependencies introduced by the modernized stack must be
license-compatible with Apache 2.0.

<br/>

## Project status

The application has been **modernized to Java 25 LTS + Spring Boot 3.x** in this same repository,
targeting 100% behavioral parity with the original mainframe implementation and zero functional
regression. The legacy COBOL/CICS/VSAM/JCL/BMS source is retained read-only under `legacy/app/` as
the behavioral specification and parity reference.

Watch this space for updates.

<br/>

