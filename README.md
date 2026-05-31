## CardDemo -- Mainframe CardDemo Application

- [CardDemo -- Mainframe CardDemo Application](#carddemo----mainframe-carddemo-application)
- [Java Spring Boot 3.2 Modernization](#java-spring-boot-32-modernization)
  - [Java Technology Stack](#java-technology-stack)
  - [Prerequisites](#prerequisites)
  - [PostgreSQL Setup](#postgresql-setup)
  - [Database Schema via Flyway](#database-schema-via-flyway)
  - [Build the Application](#build-the-application)
  - [Run the Application](#run-the-application)
  - [Test the Application](#test-the-application)
  - [REST API -- Sample curl Invocations](#rest-api--sample-curl-invocations)
  - [API Documentation](#api-documentation)
  - [Default Users](#default-users)
  - [Spring Boot Actuator Endpoints](#spring-boot-actuator-endpoints)
  - [Project Structure (Java)](#project-structure-java)
  - [What's Preserved vs. What's Modernized](#whats-preserved-vs-whats-modernized)
- [Description](#description)
- [Technologies used](#technologies-used)
- [Installation on the mainframe](#installation-on-the-mainframe)
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

# Java Spring Boot 3.2 Modernization

This repository contains **TWO co-located implementations** of the CardDemo application:

1. **Original Mainframe Implementation** (in `app/`) — COBOL/CICS/VSAM/JCL/RACF — preserved unchanged as REFERENCE for downstream regression verification. See the documentation below this section for mainframe setup and operation.
2. **Modern Java Spring Boot 3.2 Implementation** (in `src/`, `pom.xml`) — a Spring Boot 3.2 monolith using Java 17, Spring MVC, Spring Data JPA, Spring Batch 5, Spring Security 6, Hibernate 6, PostgreSQL 15, and Flyway. This is the active runtime deliverable.

The Java implementation **preserves 100% of the original CardDemo business logic** including the critical batch programs `CBACT04C` (interest calculation), `CBTRN02C` (transaction posting), and `CBSTM03A` (statement generation). Functional parity is verified by dedicated parity tests under `src/test/java/com/carddemo/businesslogic/`.

## Java Technology Stack

| Layer | Technology |
| :---- | :--------- |
| Language | Java 17 (Eclipse Temurin LTS) |
| Build | Apache Maven 3.9+ |
| Framework | Spring Boot 3.2.12 |
| Web | Spring MVC / Spring Web (embedded Tomcat 10.1.x) |
| Persistence | Spring Data JPA, Hibernate ORM 6.4.x |
| Batch | Spring Batch 5.1.x |
| Security | Spring Security 6.2.x, BCrypt password hashing |
| Migration | Flyway 9.22.x |
| Database | PostgreSQL 15 |
| API Docs | springdoc-openapi 2.3.0 (Swagger UI) |
| Observability | Spring Boot Actuator |
| Testing | JUnit 5, Mockito, AssertJ, Spring Batch Test, Testcontainers (PostgreSQL) |

## Prerequisites

Before building or running the Java application you need:

- **Java 17** (Eclipse Temurin recommended). Verify with `java -version`.
- **Apache Maven 3.9+**. Verify with `mvn -version`.
- **PostgreSQL 15** running locally or accessible over the network.
- **Docker** (optional, for running PostgreSQL via container and for integration tests via Testcontainers).

## PostgreSQL Setup

The application requires a PostgreSQL 15 database. Create a database and user, then configure the connection in `src/main/resources/application-dev.yml` (or via environment variables for `application-prod.yml`).

### Option 1: Local PostgreSQL via psql

```bash
# Connect as the postgres superuser
sudo -u postgres psql

-- Create the database and user
CREATE DATABASE carddemo;
CREATE USER carddemo WITH ENCRYPTED PASSWORD 'carddemo';
GRANT ALL PRIVILEGES ON DATABASE carddemo TO carddemo;

-- Exit psql
\q
```

### Option 2: PostgreSQL via Docker

```bash
docker run --name carddemo-postgres \
  -e POSTGRES_DB=carddemo \
  -e POSTGRES_USER=carddemo \
  -e POSTGRES_PASSWORD=carddemo \
  -p 5432:5432 \
  -d postgres:15
```

## Database Schema via Flyway

Schema creation and seed-data loading are handled automatically by **Flyway** on application startup. The migration scripts under `src/main/resources/db/migration/` are applied in order:

| Migration | Purpose |
| :-------- | :------ |
| `V1__schema.sql` | Creates all 14 base tables matching the original VSAM record layouts (accounts, cards, card_xref, customers, transactions, daily_transactions, rejected_transactions, tran_cat_balances, disclosure_groups, transaction_types, transaction_categories, users, plus Spring Batch metadata tables auto-created by Spring Boot) |
| `V2__indexes.sql` | Secondary B-tree indexes replacing the original VSAM AIX alternate indexes: `idx_card_account_id` (replaces `CARDDATA.AIX`), `idx_xref_account_id` (replaces `CARDXREF.AIX`), `idx_transaction_orig_ts` (replaces `TRANSACT.AIX`) |
| `V3__seed_reference_data.sql` | Seeds 7 transaction types, 18 transaction categories, 51 disclosure groups |
| `V4__seed_users.sql` | Inserts 10 default users (`ADMIN001`-`ADMIN005`, `USER0001`-`USER0005`) with BCrypt-hashed password `"PASSWORD"`. Each user receives a distinct hash due to BCrypt's random salt |
| `V5__seed_master_data.sql` | Seeds 50 customers, 50 accounts, 50 cards, 50 card cross-references, 100 transaction-category balances from the ASCII fixture data in `app/data/ASCII/` |

Flyway tracks applied migrations in the `flyway_schema_history` table; subsequent application restarts skip already-applied scripts. To inspect status: `mvn flyway:info`.

## Build the Application

From the repository root:

```bash
mvn clean package
```

This compiles all Java sources under `src/main/java/`, runs unit tests (`*Test.java`) via the Surefire plugin, and produces an executable Spring Boot JAR at `target/carddemo-1.0.0-SNAPSHOT.jar`.

To skip tests during the build (not recommended for CI):

```bash
mvn clean package -DskipTests
```

## Run the Application

### Development profile (local PostgreSQL)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

…or run the packaged JAR directly:

```bash
java -jar target/carddemo-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
```

### Production profile

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.com:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo \
SPRING_DATASOURCE_PASSWORD=*** \
JWT_SECRET=$(openssl rand -base64 48) \
APP_CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com \
java -jar target/carddemo-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod
```

The production profile sources **all** secrets from environment variables — none are committed to version control. The following are **required** and have **no default**, so a missing value aborts startup (fail-fast):

| Environment variable | Purpose |
| :--- | :--- |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | PostgreSQL connection. |
| `JWT_SECRET` | HS256 signing/verification secret shared by token issuance and validation. **Must be at least 32 bytes (256 bits)**; the application validates the length at startup and refuses to run on a shorter or blank value. There is no source-code fallback (a previously hardcoded default was removed to eliminate the risk of running on a publicly known key). |
| `APP_CORS_ALLOWED_ORIGINS` | Comma-separated allowlist of trusted front-end origins permitted to make credentialed cross-origin requests. Production never uses a wildcard origin. |

> The `dev` and `test` profiles ship non-production placeholder values for `jwt.secret` and a localhost `app.cors.allowed-origins`, so no environment variables are needed for local development.

On startup, Flyway applies any pending migrations, Hibernate validates the schema (`ddl-auto: validate`), and the application listens on port `8080` by default.

## Test the Application

The Java implementation includes **unit tests**, **integration tests**, and **parity tests** that verify functional equivalence with the original COBOL programs.

```bash
# Unit tests (Surefire — *Test.java)
mvn test

# Integration tests (Failsafe — *IT.java, uses Testcontainers PostgreSQL)
mvn verify

# Run a specific parity test
mvn test -Dtest=InterestCalculationParityTest
```

Critical parity tests:

| Test | Verifies |
| :--- | :------- |
| `InterestCalculationParityTest` | `CBACT04C` `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` formula yields identical results in Java for canonical inputs |
| `TransactionPostingParityTest` | `CBTRN02C` validation codes 100, 101, 102, 103 produce exact original message strings; `TCATBAL` upsert semantics; sign-based balance bucket |
| `StatementGenerationParityTest` | `CBSTM03A` `5100-WRITE-HTML-HEADER` HTML output matches the COBOL reference byte-for-byte |

## REST API — Sample curl Invocations

The application exposes a JSON REST API. Every endpoint requires JWT-bearer authentication except `POST /api/auth/login` (signon).

### Sign on (replaces the CC00 transaction / `COSGN00C` program)

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"userId":"ADMIN001","password":"PASSWORD"}'
```

Returns a JWT bearer token and the user type (`A` for admin, `U` for regular user). Use the token in the `Authorization: Bearer <token>` header for all subsequent requests.

### Retrieve the menu (replaces `COMEN01C` / `COADM01C`)

```bash
curl -X GET http://localhost:8080/api/menu \
  -H "Authorization: Bearer <token>"
```

Returns role-filtered menu options (admin sees user-administration entries; regular users do not).

### View an account (replaces `COACTVWC`)

```bash
curl -X GET http://localhost:8080/api/accounts/00000000001 \
  -H "Authorization: Bearer <token>"
```

### List cards for an account (replaces `COCRDLIC`)

```bash
curl -X GET "http://localhost:8080/api/accounts/00000000001/cards?page=0&size=10" \
  -H "Authorization: Bearer <token>"
```

Pagination via `page` and `size` parameters replaces the original `STARTBR DATASET('CARDAIX')` browse cursor with PF7/PF8 navigation.

### Create a transaction (replaces `COTRN02C`)

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "cardNumber":"4111111111111111",
    "tranTypeCd":"01",
    "tranCatCd":"01",
    "amount":42.50,
    "description":"Test transaction"
  }'
```

### Submit a bill payment (replaces `COBIL00C`)

```bash
curl -X POST http://localhost:8080/api/accounts/00000000001/payments \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"amount":100.00}'
```

### Launch a batch job (replaces the JCL submitter; admin only)

```bash
curl -X POST "http://localhost:8080/api/admin/jobs/interestCalculationJob/launch?tranDate=2022071800" \
  -H "Authorization: Bearer <token-with-ROLE_ADMIN>"
```

Supported job names mirror the JCL inventory: `transactionPostingJob` (replaces `POSTTRAN.jcl`), `interestCalculationJob` (replaces `INTCALC.jcl`), `transactionConsolidationJob` (replaces `COMBTRAN.jcl`), `statementGenerationJob` (replaces `CREASTMT.JCL`), `dataInitializationJob`, `userSeedingJob`, `transactionBackupJob`, `transactionReportJob`, `categoryBalanceReportJob`, plus diagnostic file-read jobs.

### Critical batch sequence

The original critical sequence `POSTTRAN → INTCALC → COMBTRAN → CREASTMT` is preserved. Either invoke each job individually via the BatchAdminController, or launch the composite chained job (when configured).

## API Documentation

Once running, the OpenAPI 3 documentation is available via springdoc-openapi:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## Default Users

Five admin users and five regular users are seeded by `V4__seed_users.sql`, all with the literal password `"PASSWORD"` (BCrypt-hashed):

| User ID | Role | Password |
| :------ | :--- | :------- |
| `ADMIN001` – `ADMIN005` | ADMIN | `PASSWORD` |
| `USER0001` – `USER0005` | USER | `PASSWORD` |

**Security note**: BCrypt with a random salt is used for password storage, replacing the original plaintext `USRSEC` VSAM compare. The default password is appropriate for demonstration only; rotate it immediately in any non-demonstration deployment. All user-administration endpoints (`/api/admin/users/*`) enforce `@PreAuthorize("hasRole('ADMIN')")`, closing the documented programmatic-authorization gap from the original `COUSR00C`-`COUSR03C` programs.

## Spring Boot Actuator Endpoints

Operational health and metrics are exposed via Spring Boot Actuator under `/actuator`:

| Endpoint | Purpose |
| :------- | :------ |
| `GET /actuator/health` | Application health (database connectivity, disk space, custom indicators) |
| `GET /actuator/info` | Build info (version, git commit) — only enabled when `info.*` properties are set |
| `GET /actuator/metrics` | List available metrics |
| `GET /actuator/metrics/{name}` | Metric details (e.g., `jvm.memory.used`, `hikaricp.connections.active`) |
| `GET /actuator/env` | Environment properties (sensitive values masked) |
| `GET /actuator/loggers` | View and adjust log levels at runtime |

Production deployments should restrict Actuator endpoints to a management network and protect them behind authentication.

## Project Structure (Java)

```
carddemo/
├── pom.xml                                         (Maven build, Spring Boot 3.2.12 parent)
├── src/
│   ├── main/
│   │   ├── java/com/carddemo/
│   │   │   ├── CardDemoApplication.java            (Spring Boot entry point)
│   │   │   ├── controller/                         (9 REST controllers + GlobalExceptionHandler)
│   │   │   ├── service/                            (10 service classes)
│   │   │   ├── repository/                         (12 Spring Data JPA repositories)
│   │   │   ├── entity/                             (15 JPA entities mirroring VSAM record layouts)
│   │   │   ├── batch/                              (Spring Batch Job/Step beans replacing JCL jobs)
│   │   │   ├── security/                           (SecurityConfig, UserDetailsServiceImpl, etc.)
│   │   │   ├── config/                             (DataSourceConfig, WebConfig, OpenApiConfig)
│   │   │   ├── dto/                                (Request/response DTOs nested by domain)
│   │   │   ├── mapper/                             (Entity-to-DTO mappers)
│   │   │   ├── exception/                          (Custom exceptions + ErrorResponse)
│   │   │   ├── util/                               (DateConversionUtil, BigDecimalUtil, TransactionIdGenerator)
│   │   │   └── validation/                         (TransactionValidator chain)
│   │   └── resources/
│   │       ├── application.yml                     (Base configuration)
│   │       ├── application-dev.yml                 (Development profile)
│   │       ├── application-prod.yml                (Production profile)
│   │       ├── db/migration/V*.sql                 (Flyway schema + seed scripts)
│   │       └── templates/statement-template.html   (CBSTM03A HTML preserved)
│   └── test/
│       ├── java/com/carddemo/
│       │   ├── controller/                         (MockMvc tests per controller)
│       │   ├── service/                            (Mockito-based unit tests)
│       │   ├── batch/                              (JobLauncherTestUtils tests)
│       │   ├── integration/                        (Testcontainers PostgreSQL end-to-end)
│       │   └── businesslogic/                      (Parity tests vs COBOL outputs)
│       └── resources/
│           ├── application-test.yml
│           └── fixtures/
├── docs/
│   └── migration-mapping.md                        (COBOL→Java mapping reference)
└── app/                                            (Original mainframe sources — PRESERVED)
    ├── bms/                                        (3270 BMS map sources)
    ├── catlg/LISTCAT.txt                           (VSAM catalog snapshot)
    ├── cbl/                                        (28 COBOL programs)
    ├── cpy/                                        (27 record-defining copybooks)
    ├── cpy-bms/                                    (17 BMS symbolic copybooks)
    ├── csd/CARDDEMO.CSD                            (CICS resource definitions)
    ├── ctl/REPROCT.ctl                             (IDCAMS control card)
    ├── data/ASCII/                                 (9 fixed-width seed fixture files)
    ├── jcl/                                        (28 JCL jobs)
    └── proc/                                       (JCL procs)
```

For a comprehensive per-file mapping from the original COBOL/JCL/copybook to the Java target, see [`docs/migration-mapping.md`](docs/migration-mapping.md).

## What's Preserved vs. What's Modernized

| Aspect | Original (Mainframe) | Modernized (Spring Boot) |
| :----- | :------------------- | :----------------------- |
| Business logic | COBOL programs (`app/cbl/`) | Java services + Spring Batch jobs — **byte/value parity tested** |
| Online UI | 3270 BMS maps (`app/bms/`) | REST API + JSON (no UI replacement; BMS preserved as REFERENCE) |
| Persistence | VSAM KSDS + AIX alternate indexes | PostgreSQL 15 + JPA `@Index` |
| Batch | JCL jobstreams (`app/jcl/`) | Spring Batch `Job` beans |
| Security | RACF + plaintext USRSEC compare | Spring Security 6 + BCrypt + `@PreAuthorize` |
| Currency math | COBOL `PIC S9(10)V99 COMP-3` | `java.math.BigDecimal` scale 2, `HALF_UP` |
| Concurrency | VSAM `READ UPDATE` exclusive lock | JPA `@Version` optimistic locking |
| Timestamp format | DB2 external `YYYY-MM-DD-HH.MM.SS.MIL0000` | `LocalDateTime` internally; DB2 format preserved at I/O boundaries |

---

> **Reference**: The original mainframe documentation below this section is preserved unchanged from the legacy CardDemo project. It remains accurate for the COBOL/CICS/VSAM implementation under `app/` and is retained for downstream regression verification.

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


