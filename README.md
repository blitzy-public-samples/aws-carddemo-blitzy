## CardDemo — Java (Spring Boot) Migration of the Mainframe CardDemo Application

- [CardDemo — Java (Spring Boot) Migration of the Mainframe CardDemo Application](#carddemo--java-spring-boot-migration-of-the-mainframe-carddemo-application)
- [Description](#description)
- [Technologies (Java target)](#technologies-java-target)
- [Technologies (legacy mainframe)](#technologies-legacy-mainframe)
- [Prerequisites](#prerequisites)
- [Build & Test](#build--test)
- [Run locally](#run-locally)
- [API / Screens](#api--screens)
- [Batch jobs](#batch-jobs)
- [Observability](#observability)
- [Security](#security)
- [Onboarding & Documentation](#onboarding--documentation)
- [Legacy reference](#legacy-reference)
- [Legacy mainframe installation (reference)](#legacy-mainframe-installation-reference)
- [Running full batch (legacy mainframe)](#running-full-batch-legacy-mainframe)
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
CardDemo is a Mainframe application designed and developed to test and showcase AWS and partner technology for mainframe migration and modernization use-cases such as discovery, migration, modernization, performance test, augmentation, service enablement, service extraction, test creation, test harness, etc.

Note that the intent of this application is to provide mainframe coding scenarios to excercise analysis, transformation and migration tooling. So, the coding style is not uniform across the application

This repository now additionally contains a **Java 25 (LTS) + Spring Boot 3.5.16** re-platforming of the original COBOL/CICS/VSAM/JCL application. The Java application is functionally equivalent to the mainframe original — every business rule, monetary computation, batch reject code, screen field contract, and file record layout is preserved with **zero business-logic changes** and no feature expansion. The online CICS transactions are re-expressed as Spring MVC REST endpoints, the JCL batch jobs as Spring Batch jobs, and the VSAM datasets as PostgreSQL 16 tables accessed through Spring Data JPA. The original COBOL source is retained read-only under [`legacy/`](./legacy) for reference.

<br/>

## Technologies (Java target)

The migrated application runs on a modern, fully open-source Java stack:

1. **Java 25 (LTS)** — language and runtime
2. **Spring Boot 3.5.16** — application framework (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-batch`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`)
3. **Spring Data JPA over PostgreSQL 16** — relational data tier (replaces the VSAM KSDS datasets)
4. **Spring Batch** — chunk-oriented batch processing (replaces the JCL batch jobs)
5. **Flyway** — versioned database schema migrations and reference-data seeding
6. **Maven 3.9+** — build tool, invoked through the `./mvnw` wrapper (no separate Maven install required)
7. **springdoc-openapi** — OpenAPI 3 specification and Swagger UI for the REST endpoint catalog
8. **Micrometer + OpenTelemetry + Prometheus** — application metrics and distributed tracing
9. **Logback** — structured logging with per-request correlation IDs
10. **Docker / Docker Compose** — local PostgreSQL and observability stack
11. **Testcontainers** — integration tests against a real PostgreSQL 16 instance

<br/>

## Technologies (legacy mainframe)

The original mainframe application (retained read-only under [`legacy/`](./legacy)) is built with:

1. COBOL
2. CICS
3. VSAM
4. JCL
5. RACF

<br/>

## Prerequisites

To build and run the Java application locally you need:

* **JDK 25** (LTS) — for example Eclipse Temurin 25
* **Docker** and **Docker Compose** — to run the local PostgreSQL 16 database and the observability stack (Prometheus, Tempo, Grafana)

Maven does **not** need to be installed separately: the repository ships the Maven Wrapper (`./mvnw` on Linux/macOS, `mvnw.cmd` on Windows), which downloads and runs the correct Maven version automatically.

<br/>

## Build & Test

> **Checkpoint status.** The runnable Java application is present and builds and runs now — the `src/**` sources, the `./mvnw` wrapper, `docker-compose.yml`, the `Dockerfile`, and `.github/workflows/ci.yml` — alongside the **complete legacy relocation** (all original COBOL/CICS/VSAM/JCL source under [`legacy/`](./legacy)), the Maven project descriptor (`pom.xml`), and the design/traceability/onboarding documentation. The commands and target paths in **Build & Test**, **Run locally**, and **Observability** below are **executable end-to-end** on the provisioned toolchain (Java 25 + the Maven wrapper + Docker Compose): `./mvnw -B clean verify` produces a zero-warning build, `docker compose up -d` brings up a healthy stack, and `java -jar target/carddemo-*.jar` starts the application. Exhaustive validation of the full observability **signal pipeline** (populated Grafana dashboards, traces landing in Tempo) remains owned by the observability workstream; the application itself and its Actuator/metrics endpoints run and respond locally today.

The build is reproducible, non-interactive, and produces **zero warnings**:

```shell
./mvnw -B clean verify
```

`clean verify` compiles the application under Java 25 and runs the full quality gate:

* unit tests (JUnit 5, Mockito, AssertJ);
* Testcontainers integration tests against a real PostgreSQL 16 instance;
* the **JaCoCo line-coverage gate** (the build fails below 80% combined line coverage);
* the **OWASP dependency-check** scan (the build fails on any critical or high CVE).

On Windows, use the `mvnw.cmd` wrapper instead:

```shell
mvnw.cmd -B clean verify
```

<br/>

## Run locally

1. **Start the infrastructure.** Bring up PostgreSQL 16 (plus Prometheus, Tempo, and Grafana for the observability stack) with Docker Compose:

   ```shell
   docker compose up -d
   ```

2. **Supply configuration via environment variables.** No credentials are hardcoded — the application reads every secret and connection value from the environment (typically activated through the `local` Spring profile). For example:

   ```shell
   export DB_URL=${DB_URL}                 # e.g. jdbc:postgresql://localhost:5432/carddemo
   export DB_USERNAME=${DB_USERNAME}       # database user, supplied at runtime
   export DB_PASSWORD=${DB_PASSWORD}       # database password, supplied at runtime (never committed)
   export SPRING_PROFILES_ACTIVE=local
   ```

3. **Start the application** from source or from the packaged jar:

   ```shell
   ./mvnw spring-boot:run
   # or, after ./mvnw -B clean verify:
   java -jar target/carddemo-*.jar
   ```

Demo logins `ADMIN001` (Admin) and `USER0001` (regular User) are seeded into the `user_security` table; their credentials are supplied via the seeded reference data / configuration rather than being hardcoded in the application. These are the original demo accounts and share a well-known password inherited from the legacy seed data — they are for local demonstration only and must be re-secured before any non-demo use (see the [Security](#security) section and the security note under [Legacy mainframe installation](#legacy-mainframe-installation-reference)).

<br/>

## API / Screens

The 17 online BMS screens are **re-expressed as REST endpoints**, not rendered as a new web UI. Each screen's 3270 field contract becomes a request/response DTO pair that preserves the original field names, lengths, PIC-derived types, edit rules, and PF-key actions (for example PF3 = back, PF7/PF8 = page, Enter = submit). This is a faithful contract migration with **no feature expansion**.

Browse the generated OpenAPI 3 specification and interactive Swagger UI (served by springdoc) for the full endpoint catalog:

* Swagger UI: `/swagger-ui.html`
* OpenAPI document: `/v3/api-docs`

See the [Application Inventory](#application-inventory) below for the authoritative screen-to-program mapping that these endpoints implement.

<br/>

## Batch jobs

The JCL-triggered batch programs are now **Spring Batch Jobs** (chunk-oriented `Job` / `Step` definitions) that preserve step ordering, key ordering, and return-code semantics. Representative mappings:

| Spring Batch Job             | Source COBOL program | Legacy JCL trigger |
| :--------------------------- | :------------------- | :----------------- |
| `DailyTransactionPostingJob` | CBTRN02C             | POSTTRAN           |
| `InterestCalculationJob`     | CBACT04C             | INTCALC            |
| `StatementGenerationJob`     | CBSTM03A / CBSTM03B  | CREASTMT           |
| `TransactionReportJob`       | CBTRN03C             | TRANREPT           |
| `TransactionCombineJob`      | SORT                 | COMBTRAN           |
| `TransactionBackupJob`       | IDCAMS REPRO         | TRANBKP            |

Job **scheduling** is no longer driven by a JCL scheduler; it is homed in the CI/CD workflow at `.github/workflows/ci.yml`, where the nightly schedule currently runs the reproducible build-and-verify gate and per-job batch launches are wired in as each batch job is delivered (see [`docs/decision-log.md`](./docs/decision-log.md), decision D14). The complete source-construct-to-job mapping is recorded in the [traceability matrix](./docs/traceability-matrix.md).

<br/>

## Observability

Operational visibility is **built into** the application and runs against the local `docker-compose` stack. The capabilities below are runtime-verifiable today:

* **Health & readiness** — Spring Boot Actuator exposes `/actuator/health` together with the `/actuator/health/liveness` and `/actuator/health/readiness` probes.
* **Metrics** — Prometheus-format metrics publish at `/actuator/prometheus` (Micrometer registry); HTTP server latency is emitted as a histogram, so the dashboard's p95/p99 latency panels are populated.
* **Structured logging** — every log line carries a **correlation ID** that propagates across service and batch boundaries (Logback).
* **Distributed tracing** — traces export over **OTLP** to Tempo via Micrometer Tracing + OpenTelemetry.
* **Dashboard** — a ready-to-import Grafana dashboard **template** is provided at [`docs/observability/grafana-dashboard.json`](./docs/observability/grafana-dashboard.json).

**Correlation-ID HTTP contract.** Every HTTP response carries an `X-Correlation-Id` header. If the request supplies an `X-Correlation-Id` whose value matches the safe pattern `^[A-Za-z0-9._-]{1,64}$`, that value is adopted for the request's logs, response header, and trace; otherwise — absent, malformed, over-length, or containing out-of-charset characters — the application generates a fresh UUID. The correlation ID is placed in the logging MDC under `correlationId` and appears in every log line as `[cid=...]`. Client-supplied values are validated (never reflected verbatim), so untrusted header content cannot reach the logs, response, or trace.

**Actuator access posture.** The exposure list is pinned to exactly `health,info,metrics,prometheus` (never `*`). `/actuator/health` and its `/actuator/health/liveness` and `/actuator/health/readiness` probes are **public** (no authentication) so container orchestrators can probe them; the remaining exposed endpoints — `/actuator/info`, `/actuator/metrics`, and `/actuator/prometheus` — require **HTTP Basic** authentication with a valid application user.

<br/>

## Security

Authentication parity with the mainframe is preserved via Spring Security backed by the migrated user table: the two legacy roles — **`A` = Admin** and **`U` = User** — map to the same authorization boundaries as the original application. All credentials (the database connection and any other secrets) are **externalized to environment variables**; nothing sensitive is hardcoded in source or configuration.

As a documented security improvement over the intentionally insecure legacy demo, the card **CVV is never logged or returned in full**, and passwords are never logged. These improvements are captured in the [decision log](./docs/decision-log.md).

<br/>

## Onboarding & Documentation

The onboarding guides take a new developer from a clean machine to a buildable, runnable, and modifiable application:

* [`docs/onboarding/getting-started.md`](./docs/onboarding/getting-started.md) — clean-machine setup, build, and run
* [`docs/onboarding/domain-context.md`](./docs/onboarding/domain-context.md) — credit-card domain and business background
* [`docs/onboarding/extending.md`](./docs/onboarding/extending.md) — how to extend the application, plus suggested next tasks
* [`docs/onboarding/pitfalls.md`](./docs/onboarding/pitfalls.md) — common pitfalls and gotchas

Additional design and traceability references:

* [`docs/architecture.md`](./docs/architecture.md) — target architecture and layer boundaries
* [`docs/decision-log.md`](./docs/decision-log.md) — every non-trivial decision, its alternatives, rationale, and risks
* [`docs/traceability-matrix.md`](./docs/traceability-matrix.md) — bidirectional COBOL-paragraph-to-Java mapping (100% coverage)

<br/>

## Legacy reference

The complete original mainframe source — COBOL programs, copybooks, BMS maps, JCL, PROCs, the CICS CSD, and seed data — is preserved **read-only** under [`legacy/`](./legacy) (relocated from the former `app/` tree) so the migration stays fully traceable to its source of truth. Nothing under `legacy/` is compiled or executed by the Java build.

A self-contained executive summary of the migration (business value, before/after architecture, and risk assessment) is available as a single HTML deck at [`blitzy-deck/executive-summary.html`](./blitzy-deck/executive-summary.html).

<br/>

## Legacy mainframe installation (reference)

> **Note:** The steps below describe installing and running the **original COBOL/CICS/VSAM** application on a mainframe. They are retained for reference and reproducibility only. The COBOL source and artifacts referenced here now live under [`legacy/`](./legacy) (formerly `app/`). For the migrated Java application, see [Build & Test](#build--test) and [Run locally](#run-locally) above.

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
   
      * Upload the sample data provided in the [`legacy/data/EBCDIC/`](./legacy/data/EBCDIC/) folder to the mainframe. Ensure that you use transfer mode binary

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
     - Enter userid ADMIN001 and the initially configured password PASSWORD to manage users
     - Enter userid USER0001 and the initially configured password PASSWORD to access back office functions
   * For batch            : See the instructions for running full batch below.

> **Security note (pre-existing legacy demo credentials).** The `ADMIN001`/`USER0001` logins above share a single well-known, plaintext password (`PASSWORD`) that ships in the original demo's seed data. This is an intentional legacy demonstration convenience, **not** a production-safe practice, and it predates this migration. The legacy usage instructions are retained verbatim for reference; the immutable `legacy/` source is not modified. In the migrated Java application these seeded demo accounts must be re-secured before any non-demo use — credentials are externalized (never hardcoded), and password hashing plus CVV-handling hardening are recorded as documented security improvements in the [decision log](./docs/decision-log.md).

## Running full batch (legacy mainframe)

> **Note:** This is the **legacy mainframe** batch sequence (JCL). In the migrated application these jobs are Spring Batch Jobs — see [Batch jobs](#batch-jobs) above.
   
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

The following features are planned for upcoming releases

1. More database types

   1. Relational Database usage : Db2 
   
   2. Hierachical database calls : IMS

2. Integration

   * ftp, sftp
   
   * Message queue integration
   
   * Exposure of transactions for distributed application integration

<br/>

## Contributing

We are looking forward to receiving contributions and enhancements to this initial codebase from the mainframe code base

Feel free to raise issues, create code and raise merge requests for enhancements so that we can build out this application as a resource for programmers wanting to understand and modernize their mainframes.

<br/>

## License

This is intended to be a community resource and it is released under the Apache 2.0 license.

<br/>

## Project status

The COBOL/CICS/VSAM/JCL application has been re-platformed to a functionally equivalent **Java 25 + Spring Boot 3.5.16** application (Spring Data JPA over PostgreSQL 16, Spring Batch for the batch jobs, and Spring Boot Actuator + Micrometer/OpenTelemetry for observability). Business behavior is preserved with **zero business-logic changes** and no feature expansion; the original mainframe source is retained under [`legacy/`](./legacy) for reference.

Watch this space for updates

<br/>


