## CardDemo -- Mainframe CardDemo Application

> **This repository contains both the original COBOL mainframe application and its complete migration to Java 25 LTS + Spring Boot 3.5.x.** The original COBOL source is preserved under [`legacy/app/`](legacy/app/) for reference and traceability. No COBOL compiler or mainframe runtime is required to build and run the migrated Java application.

**Table of Contents**

- [Migrated Java Application](#migrated-java-application)
  - [Prerequisites](#prerequisites)
  - [Quick Start](#quick-start)
  - [Environment Variables](#environment-variables)
  - [Project Structure](#project-structure)
  - [Technology Stack](#technology-stack)
  - [Quality Gates](#quality-gates)
  - [User Credentials (Local Testing)](#user-credentials-local-testing)
  - [Key Documentation](#key-documentation)
- [Legacy COBOL Application](#legacy-cobol-application)
  - [Description](#description)
  - [Technologies used](#technologies-used)
  - [Installation on the mainframe](#installation-on-the-mainframe)
  - [Running full batch](#running-full-batch)
  - [Application Details](#application-details)
- [Support](#support)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Project status](#project-status)

<br/>

## Migrated Java Application

The CardDemo application has been fully migrated from COBOL/CICS/VSAM/JCL to **Java 25 LTS + Spring Boot 3.5.x** with 100% business logic parity. All 28 COBOL programs (18 online CICS transactions and 10 batch programs) have been translated into Spring service classes, Spring Batch jobs, and REST controllers. All 10 VSAM KSDS datasets have been migrated to PostgreSQL 16+ tables with JPA entities using `BigDecimal` for all monetary fields.

### Prerequisites

| Requirement | Version | Notes |
|------------|---------|-------|
| Java | 25 LTS | OpenJDK (Eclipse Temurin) or Oracle JDK |
| Docker | 20.10+ | Required for Testcontainers integration tests (PostgreSQL 16+) |
| Maven | 3.9.9 | Included via Maven Wrapper (`./mvnw` / `mvnw.cmd`) — no separate install needed |
| PostgreSQL | 16+ | Required for runtime; tests use Testcontainers auto-provisioned instances |

> **Note:** No COBOL compiler, CICS runtime, or mainframe environment is required.

### Quick Start

```bash
# Clone the repository
git clone <repository-url>
cd cardemo

# Build (compile + unit tests + integration tests + JaCoCo coverage + OWASP dependency check)
./mvnw clean verify

# Run the application (set environment variables first — see below)
./mvnw spring-boot:run

# Run unit tests only
./mvnw test

# Run unit + integration tests
./mvnw verify
```

### Environment Variables

The following environment variables must be set before running the application:

| Variable | Description | Example |
|----------|-------------|---------|
| `DB_URL` | PostgreSQL JDBC connection URL | `jdbc:postgresql://localhost:5432/cardemo` |
| `DB_USERNAME` | Database username | *(set via environment)* |
| `DB_PASSWORD` | Database password | *(set via environment)* |

> **Note:** For tests, Testcontainers automatically provisions a PostgreSQL 16 container — no database environment variables are needed for running tests.

### Project Structure

```
cardemo/
├── pom.xml                          Maven build configuration
├── mvnw / mvnw.cmd                  Maven Wrapper (no separate Maven install needed)
├── src/
│   ├── main/
│   │   ├── java/com/cardemo/       Main application source
│   │   │   ├── common/             Shared DTOs, enums, utilities, exceptions
│   │   │   ├── config/             Spring configuration classes
│   │   │   ├── entity/             JPA entities (← VSAM datasets)
│   │   │   ├── repository/         Spring Data JPA repositories
│   │   │   ├── service/
│   │   │   │   ├── online/         Online transaction services (← CICS programs)
│   │   │   │   └── batch/          Batch processing services (← batch COBOL programs)
│   │   │   ├── batch/              Spring Batch job configurations (← JCL jobs)
│   │   │   └── controller/         REST controllers
│   │   └── resources/
│   │       ├── application.yml     Application configuration
│   │       └── db/
│   │           ├── migration/      Flyway schema migrations
│   │           └── seed/           Seed data (parsed from legacy fixed-width files)
│   └── test/
│       ├── java/com/cardemo/       JUnit 5 unit and integration tests
│       └── resources/fixtures/     Test data fixtures
├── docs/                            Architecture, decisions, traceability, onboarding
├── legacy/app/                      Original COBOL source (preserved for reference)
└── .mvn/                            Maven Wrapper configuration
```

### Technology Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| Java | 25 LTS | Runtime platform |
| Spring Boot | 3.5.11 | Application framework |
| Spring Data JPA | *(managed by Spring Boot BOM)* | Data access layer (← VSAM KSDS) |
| PostgreSQL | 16+ | Relational database (← VSAM) |
| Spring Batch | *(managed by Spring Boot BOM)* | Batch processing (← JCL jobs) |
| Spring Security | *(managed by Spring Boot BOM)* | Role-based authentication (Admin / User) |
| Flyway | 10.22.0 | Database schema migrations |
| JUnit 5 + Testcontainers | 2.0.2 | Testing with real PostgreSQL containers |
| JaCoCo | 0.8.14 | Code coverage enforcement (≥80% line coverage) |
| OWASP dependency-check | 12.1.0 | Vulnerability scanning (zero critical/high CVEs) |
| Micrometer + Prometheus | 1.14.4 | Observability: metrics, tracing, health checks |

### Quality Gates

The Maven build enforces the following quality gates:

- **Zero-warning compilation** — Java compiler configured with `-Xlint:all -Werror`
- **≥80% line coverage** — JaCoCo enforces minimum 80% line coverage across unit and integration tests
- **Zero critical/high CVEs** — OWASP dependency-check fails the build on CVSS score ≥ 7.0

Run all quality gates with:

```bash
./mvnw clean verify
```

### User Credentials (Local Testing)

The following test credentials are available after database seed data is loaded:

| User ID | Password | Role | Access |
|---------|----------|------|--------|
| `ADMIN001` | `PASSWORD` | Admin | User management (add, update, delete users) |
| `USER0001` | `PASSWORD` | Regular User | Account view/update, card management, transactions, bill payment |

> **Note:** In the Java version, passwords are BCrypt-hashed in the database. The original plaintext passwords from the COBOL seed data are hashed during the initial database load via Flyway seed migration.

### Key Documentation

| Document | Description |
|----------|-------------|
| [Onboarding Guide](docs/onboarding.md) | New developer setup: clean machine → running application |
| [Architecture Diagrams](docs/architecture/diagrams.md) | Before/after Mermaid architecture views |
| [Decision Log](docs/decision-log.md) | Non-trivial implementation decisions with rationale |
| [Traceability Matrix](docs/traceability-matrix.md) | 100% COBOL paragraph → Java method mapping |
| [Executive Summary](docs/slides/executive-summary.html) | reveal.js presentation for leadership |

<br/>

---

## Legacy COBOL Application

The sections below contain the original COBOL/CICS/VSAM mainframe application documentation, preserved for reference. The original source code is also retained under [`legacy/app/`](legacy/app/).

<br/>

## Description
CardDemo is a Mainframe application designed and developed to test and showcase AWS and partner technology for mainframe migration and modernization use-cases such as discovery, migration, modernization, performance test, augmentation, service enablement, service extraction, test creation, test harness, etc.

Note that the intent of this application is to provide mainframe coding scenarios to excercise analysis, transformation and migration tooling. So, the coding style is not uniform across the application

<br/>

## Technologies used
1. COBOL
2. CICS
3. VSAM
4. JCL
5. RACF

<br/>

## Installation on the mainframe 

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
     - Enter userid ADMIN001 and the initially configured password PASSWORD to manage users
     - Enter userid USER0001 and the initially configured password PASSWORD to access back office functions
   * For batch            : See the instructions for running full batch below.

## Running full batch 
   
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

We are planning a v2 of this application in Q1 2023.

Watch this space for updates

<br/>


