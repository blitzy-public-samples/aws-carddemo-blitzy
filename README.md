## CardDemo -- Mainframe CardDemo Application

- [CardDemo -- Mainframe CardDemo Application](#carddemo----mainframe-card-demo-application)
- [Description](#description)
- [Technologies used](#technologies-used)
  - [Legacy (mainframe) stack](#legacy-mainframe-stack)
  - [Target (Java) stack](#target-java-stack)
- [Running the Java application](#running-the-java-application)
  - [Prerequisites](#prerequisites)
  - [Repository layout](#repository-layout)
  - [Configuration](#configuration)
  - [Build](#build-current)
  - [Run](#run-forthcoming)
  - [Batch jobs](#batch-jobs-forthcoming)
  - [Observability](#observability)
  - [Testing](#testing-forthcoming)
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

> **Implementation status at this checkpoint.** This section describes the **target** Java application. The migration has generated the **build baseline and a substantial foundation that compile cleanly today**: the Maven build (`pom.xml` + wrapper), all **10** JPA domain entities and the reference/enum layer, the DTO layer (session context, screen forms, menu and report models), the cross-cutting `config` / `exception` / `security` / `util` classes, the `application.yml` base config, structured logging (`logback-spring.xml`), the Spring Batch metadata migration (`V0`), and the admin-driver batch job. The **remaining layers are forthcoming** in the same generation phase: the Spring Data **repositories**, the online **services and controllers**, the business **Spring Batch jobs**, the **`SecurityConfig`** and role routing, the `V1`&ndash;`V3` **Flyway schema/seed/index** migrations, the **Thymeleaf templates**, the `dev` / `test` **profile files**, and the **`src/test/**`** test suites. Subsections below are tagged **(current)** where they work today and **(forthcoming)** where they describe target behavior that becomes available once those artifacts are generated; paths and commands in a *(forthcoming)* subsection are the intended contract, not a claim that they run end-to-end today.

### Prerequisites

* **JDK 25** (LTS) &mdash; required to compile and run the application.
* **Maven** &mdash; no separate install needed; the bundled Maven Wrapper (`./mvnw` on macOS / Linux, `mvnw.cmd` on Windows) pins Maven **3.9.9**.
* **PostgreSQL 18.x** (supported floor 16) running locally, **or Docker**. Docker is also required for the Testcontainers-based integration tests.
* **Git** &mdash; to clone the repository.

### Repository layout

```
carddemo/
├── pom.xml                         Maven build (Spring Boot 3.5.16 parent BOM)                    [current]
├── mvnw, mvnw.cmd, .mvn/           Maven Wrapper (pinned to Maven 3.9.9)                           [current]
├── src/main/java/com/aws/carddemo/
│   ├── config/                     DataSource, Batch, Observability, Web config [current]; SecurityConfig [forthcoming]
│   ├── domain/                     JPA entities (one per VSAM file) + enums                        [current]
│   ├── dto/                        CardDemoContext (COMMAREA), screen forms, feed / report models  [current]
│   ├── repository/                 Spring Data JPA repositories (one per VSAM file)                [forthcoming]
│   ├── service/                    @Service classes (one per COBOL program; methods = paragraphs)  [forthcoming]
│   ├── web/                        Spring MVC controllers (one route per CICS transaction id)      [forthcoming]
│   ├── batch/                      Spring Batch @Configuration jobs; admin driver [current], business jobs [forthcoming]
│   ├── exception/                  FILE STATUS exception type [current]; @ControllerAdvice handler [forthcoming]
│   ├── security/                   User principal [current]; UserDetailsService + role wiring       [forthcoming]
│   └── util/                       Date conversion, decimal helpers, fixed-width mappers           [current]
├── src/main/resources/
│   ├── application.yml             Base config [current]; application-dev.yml / -test.yml           [forthcoming]
│   ├── db/migration/               Flyway: V0 batch metadata [current]; V1/V2/V3 schema/seed/index  [forthcoming]
│   ├── logback-spring.xml          Structured JSON logging with correlation IDs                    [current]
│   └── templates/                  Thymeleaf screens (preserve the BMS 24x80 contract)             [forthcoming]
├── src/test/java/                  JUnit 5 unit + Testcontainers integration + parity tests         [forthcoming]
├── legacy/                         Original COBOL / CICS / JCL / BMS / CPY / CSD / data (read-only)  [current]
├── docs/                           Decision log + traceability [current]; onboarding + architecture [forthcoming]
├── blitzy-deck/                    reveal.js deck: theme CSS [current]; index.html                  [forthcoming]
├── observability/                  Grafana dashboard template                                      [current]
├── diagrams/                       Legacy flow diagrams and screen captures                        [current]
└── samples/                        Legacy sample JCL (reference only)                              [current]
```

### Configuration

Configuration is environment-driven and contains **no hardcoded secrets**. The base `application.yml` is present now; two Spring profiles &mdash; `dev` (local development) and `test` (used by the integration-test suite) &mdash; are **forthcoming** as `application-dev.yml` / `application-test.yml`. The database connection is supplied entirely through environment variables:

| Environment variable         | Purpose                             | Example (local dev)                         |
| :--------------------------- | :---------------------------------- | :------------------------------------------ |
| `SPRING_DATASOURCE_URL`      | JDBC URL of the PostgreSQL database | `jdbc:postgresql://localhost:5432/carddemo` |
| `SPRING_DATASOURCE_USERNAME` | Database user                       | supplied via environment / secret manager   |
| `SPRING_DATASOURCE_PASSWORD` | Database password                   | supplied via environment / secret manager   |

**Flyway** manages the database schema as versioned migrations. Present now is `V0__spring_batch_metadata.sql` (the Spring Batch metadata tables). **Forthcoming** in the same generation phase are `V1__schema.sql` (application schema), `V2__reference_data.sql` (reference and sample seed data), and `V3__indexes.sql` (alternate-index equivalents); once those land, a freshly created database is initialized on startup with no manual steps.

### Build (current)

The generated foundation compiles cleanly today. To compile and package it:

```shell
./mvnw -DskipTests package
```

This compiles the sources with `--release 25`. The build is **zero-warning**: the compiler runs with `-Xlint:all` and `failOnWarning`, and the Maven wrapper's `.mvn/jvm.config` passes `--sun-misc-unsafe-memory-access=allow` so the JVM emits no `sun.misc.Unsafe` deprecation warnings either &mdash; every prescribed `./mvnw` invocation on Java 25 is free of **both** compiler and JVM-runtime warnings.

**Forthcoming.** Once the repository / service / web layers and the `src/test/**` suites are generated, the full verification lifecycle runs and enforces the quality gates &mdash; the **JaCoCo &ge; 80% line-coverage** gate and the **OWASP dependency-check zero critical / high CVE** gate:

```shell
./mvnw clean verify   # forthcoming: unit + Testcontainers integration tests, coverage gate, CVE gate
```

### Run (forthcoming)

Running the application end-to-end &mdash; the browser sign-on flow and seeded logins &mdash; becomes available once the forthcoming web controllers, services, repositories, `SecurityConfig`, and the `V1` / `V2` migrations (schema + seed data) are generated. The intended commands are:

```shell
./mvnw spring-boot:run
```

To activate the local development profile explicitly (once `application-dev.yml` is added):

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

You will then open the application in a browser and sign on. As in the legacy system, two demo logins will be provided as **seed data**:

* `ADMIN001` &mdash; with the initially configured password &mdash; to manage users (admin functions).
* `USER0001` &mdash; with the initially configured password &mdash; to access back-office (regular user) functions.

These are seeded application user records. The **database** credentials, by contrast, are always supplied via the `SPRING_DATASOURCE_*` environment variables and are never embedded in source or configuration.

### Batch jobs (forthcoming)

The batch workload that ran as JCL jobs on the mainframe will be implemented as **Spring Batch `Job`s** (chunk-oriented reader &rarr; processor &rarr; writer steps, with `Tasklet` steps for single-action utilities). At this checkpoint the admin-driver job (`CBADMCDJ` &rarr; a documented no-op `Tasklet`) and the Spring Batch metadata schema (`V0`) are present; the business jobs are forthcoming &mdash; `PostTransactionJob` (daily transaction posting), `InterestCalcJob` (monthly interest calculation), and `StatementJob` (statement generation). Job parameters &mdash; for example the interest-calculation processing date &mdash; will be supplied as Spring Batch `JobParameter`s, preserving the original JCL `PARM` semantics. See [`docs/traceability-matrix.md`](./docs/traceability-matrix.md) for the full JCL-job &rarr; Spring Batch job mapping.

### Observability

Observability is wired into the application from the start. The **configuration artifacts are present now**; the **live HTTP endpoints are exercised once the application runs** (see [Run](#run-forthcoming)):

* **Structured logging (current)** &mdash; JSON logging with correlation IDs is configured in [`src/main/resources/logback-spring.xml`](./src/main/resources/logback-spring.xml).
* **Dashboard (current)** &mdash; a Grafana dashboard template is provided at [`observability/grafana-dashboard.json`](./observability/grafana-dashboard.json).
* **Health / readiness (with the running app)** &mdash; `GET /actuator/health`.
* **Metrics (Prometheus scrape, with the running app)** &mdash; `GET /actuator/prometheus`.

### Testing (forthcoming)

The `src/test/**` suites are generated later in the migration phase; until then these commands compile the project but execute no tests. The intended contract is:

```shell
./mvnw test      # forthcoming: unit tests only
./mvnw verify    # forthcoming: unit + Testcontainers integration + parity tests, plus the coverage and CVE gates
```

**Docker must be running** for the Testcontainers-based integration tests, which start a real PostgreSQL container. The parity tests will validate the Java results against the ASCII fixtures retained from the legacy system.

<br/>

## Documentation

Companion documentation for the migration lives under [`docs/`](./docs). Items marked **(forthcoming)** are generated later in the same migration phase; their links are intentionally left unlinked until the artifacts exist, so nothing here points at a missing file:

* [`docs/decision-log.md`](./docs/decision-log.md) **(current)** &mdash; every non-trivial migration decision with its alternatives, rationale, and risks.
* [`docs/traceability-matrix.md`](./docs/traceability-matrix.md) **(current)** &mdash; the bidirectional COBOL-construct &rarr; Java-artifact mapping (programs, copybooks, BMS maps, and JCL jobs).
* `docs/onboarding.md` **(forthcoming)** &mdash; a clean-machine-to-running-application onboarding guide (setup, domain context, common pitfalls, how to extend the project, and suggested next tasks).
* `docs/architecture/` **(forthcoming)** &mdash; Mermaid before / after architecture diagrams (the current z/OS state and the target Spring Boot state).

A self-contained reveal.js executive-summary presentation at `blitzy-deck/index.html` is **(forthcoming)**; the canonical Blitzy reveal.js theme it depends on is already present at [`blitzy-deck/references/blitzy-reveal-theme.css`](./blitzy-deck/references/blitzy-reveal-theme.css).

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

> **Note:** This section also applies to the original COBOL under [`legacy/`](./legacy) and is retained for reference only; in the migrated application the batch workload runs as Spring Batch jobs (see [Batch jobs](#batch-jobs-forthcoming)).

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

* Upgrade to **Spring Boot 4.x** &mdash; the Spring Boot 3.5 line reached open-source end-of-life on 2026-06-30, so future CVE patches require an upgrade.
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
