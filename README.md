## CardDemo -- Mainframe CardDemo Application

- [CardDemo -- Mainframe CardDemo Application](#carddemo----mainframe-carddemo-application)
- [Description](#description)
- [Modernized Java Edition (Spring Boot 3.2.x)](#modernized-java-edition-spring-boot-32x)
- [Building and Running the Java Application](#building-and-running-the-java-application)
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

## Description
CardDemo is a Mainframe application designed and developed to test and showcase AWS and partner technology for mainframe migration and modernization use-cases such as discovery, migration, modernization, performance test, augmentation, service enablement, service extraction, test creation, test harness, etc.

Note that the intent of this application is to provide mainframe coding scenarios to excercise analysis, transformation and migration tooling. So, the coding style is not uniform across the application

<br/>

## Modernized Java Edition (Spring Boot 3.2.x)

This repository now contains **two editions** of CardDemo:

1. The **original mainframe application** (under `app/**` -- COBOL, CICS, VSAM, JCL, BMS) is retained **unchanged** as the authoritative source-of-truth **REFERENCE** that defines the behavior the Java code reproduces.
2. A net-new, functionally-equivalent **Java 17 / Spring Boot 3.2.x monolithic** re-implementation (under `src/**`, built by the root [`pom.xml`](./pom.xml)).

The Java edition delivers **100% functional parity** across the nine feature domains (authentication, menu routing, account, card, transaction, bill payment, reporting, user administration, and batch utilities). It is a **single monolithic application** (not microservices) exposing a **JSON REST API** -- the BMS 3270 screens are retired, so there is **no web UI**.

The COBOL -> Java technology mapping is:

| Legacy (mainframe)                 | Modernized (Spring Boot)                                       |
| :--------------------------------- | :------------------------------------------------------------- |
| CICS online programs (`CO*`)       | Spring MVC REST controllers                                    |
| BMS 3270 screen maps               | Retired -- replaced by JSON request/response DTOs (no web UI)  |
| VSAM KSDS files                    | PostgreSQL 15.x tables via Spring Data JPA / Hibernate         |
| JCL-scheduled COBOL batch (`CB*`)  | Spring Batch 5 chunk-oriented jobs                             |
| CICS COMMAREA session handoff      | Stateless JWT (HS256)                                          |
| Plaintext password comparison      | BCrypt password hashing (strength 12)                          |
| `CSUTLDTC` date validation         | `DateValidationService` (`java.time`)                          |

High-level project layout of the Java edition:

```text
pom.xml                                  Maven build (Java 17, Spring Boot 3.2.x)
src/
|-- main/
|   |-- java/com/carddemo/
|   |   |-- CardDemoApplication.java      Spring Boot bootstrap (@SpringBootApplication)
|   |   |-- config/                       Datasource, batch, Jackson, OpenAPI configuration
|   |   |-- security/                     JWT provider/filter, SecurityConfig, UserDetails
|   |   |-- controller/                   REST controllers (Auth, Menu, Account, Card,
|   |   |                                  Transaction, BillPayment, Report, User)
|   |   |-- service/                      Transactional business logic ported from COBOL
|   |   |-- repository/                   Spring Data JPA repositories (one per entity)
|   |   |-- entity/                       JPA entities (10, one per VSAM dataset)
|   |   |-- dto/                          Request/response DTOs (CVV/SSN suppression)
|   |   |-- mapper/                        Entity <-> DTO mappers
|   |   |-- batch/                         Spring Batch job configurations
|   |   |-- exception/                     Global exception handling
|   |   `-- util/                          Shared utilities (e.g., transaction-id generator)
|   `-- resources/
|       |-- application.yml                Base configuration
|       |-- application-dev.yml            dev profile (H2 in-memory)
|       |-- application-prod.yml           prod profile (PostgreSQL)
|       |-- logback-spring.xml             Logging (PII/CVV masking)
|       `-- db/migration/                  Flyway migrations
|           |-- V1__schema.sql             10 tables, FKs, sequences, indexes
|           |-- V2__seed_reference.sql     Reference seed data
|           |-- V3__seed_master.sql        Master/balance seed data
|           `-- V4__seed_users.sql         BCrypt-hashed user seed (generated)
`-- test/                                  JUnit 5 / MockMvc / @SpringBatchTest suite
```

<br/>

## Building and Running the Java Application

### Prerequisites

* **JDK 17** (Java 17 LTS).
* **Maven 3.9.x** (the project is a single Maven module).
* **PostgreSQL 15.x** -- required **only** for the `prod` profile. The `dev` and `test` profiles use an in-memory **H2** database and need no external database.

### Build and test (with coverage gate)

```shell
# Compile, run the full test suite (JUnit 5 / MockMvc / @SpringBatchTest),
# and enforce the >=80% JaCoCo line-coverage gate.
mvn clean verify

# Produce an executable Spring Boot JAR (via spring-boot-maven-plugin).
mvn clean package
```

### Run (dev profile -- H2, no external database)

```shell
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# or, after packaging:
java -jar target/*.jar --spring.profiles.active=dev
```

On startup, **Flyway** automatically applies migrations `V1`-`V4`, creating the H2 schema and loading the seed data.

### Run (prod profile -- PostgreSQL)

Activate the `prod` profile and supply the following environment variables:

| Environment variable         | Purpose                                                                                                   |
| :--------------------------- | :-------------------------------------------------------------------------------------------------------- |
| `SPRING_DATASOURCE_URL`      | PostgreSQL JDBC URL (e.g., `jdbc:postgresql://localhost:5432/carddemo`)                                    |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL username                                                                                       |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password                                                                                       |
| `JWT_SECRET`                 | HS256 signing key -- **must be >=256 bits (32+ characters)**                                              |
| `JWT_EXPIRATION_MS`          | JWT lifetime in milliseconds (default `3600000` = 1 hour)                                                  |
| `report.output.path`         | Output directory for the statement/report batch jobs                                                      |
| `input.file.path`            | Daily-transaction input path; also passed as a Spring Batch job parameter to the transaction-posting job  |

```shell
java -jar target/*.jar --spring.profiles.active=prod
```

### Default credentials (Java edition)

The generated BCrypt user seed (`V4__seed_users.sql`) reproduces the legacy default credentials:

* Admin -- userid `ADMIN001`, password `PASSWORD`
* User  -- userid `USER0001`, password `PASSWORD`

There is **no `usrsec.txt` fixture** in the repository (it is absent from `app/data/ASCII/`); the user seed is **generated** with BCrypt hashing rather than loaded from an ASCII file. These are the same credentials documented for the mainframe edition under *Installation on the mainframe* below.

### API documentation

When the application is running, interactive **OpenAPI / Swagger UI** documentation (provided by springdoc) is available for exploring the REST endpoints. The documentation endpoints are **public** (no token required):

| Resource           | URL                                          |
| :----------------- | :------------------------------------------- |
| OpenAPI JSON spec  | `http://localhost:8080/v3/api-docs`          |
| Swagger UI         | `http://localhost:8080/swagger-ui.html`      |

This is a **monolithic JSON REST API** (the legacy 3270/BMS screens are retired — there is no web UI). All endpoints are served under the application root (there is **no `/api` prefix**). Authenticate first with `POST /auth/signon` to obtain a JWT, then send it as `Authorization: Bearer <token>` on every subsequent request.

#### Endpoint inventory

| Method(s)            | Path                                  | Auth required        | Description                                                                 |
| :------------------- | :------------------------------------ | :------------------- | :------------------------------------------------------------------------- |
| `POST`               | `/auth/signon`                        | Public               | Sign on; returns a JWT (`tokenType: Bearer`, ~1&nbsp;hour expiry).         |
| `GET`                | `/menu`                               | Authenticated        | User menu options (re-expression of `COMEN01C`).                           |
| `GET`                | `/admin/menu`                         | **ADMIN**            | Admin menu options (re-expression of `COADM01C`).                          |
| `GET`, `PUT`         | `/accounts/{accountId}`               | Authenticated        | View / update an account. Update is optimistic-locked and returns the **incremented `version`**. |
| `GET`, `POST`        | `/accounts/{accountId}/bill-payment`  | Authenticated        | Available-credit inquiry / full-balance payment.                          |
| `GET`                | `/cards`                              | Authenticated        | List cards — **paginated, fixed page size 7**.                            |
| `GET`, `PUT`         | `/cards/{cardNum}`                    | Authenticated        | View / update a card. `cardNum` and `cardAcctId` are **immutable**; the CVV is **never returned**. |
| `GET`, `POST`        | `/transactions`                       | Authenticated        | List (page size 7, ordered by origination timestamp) / add a transaction (16-char zero-padded id). |
| `GET`                | `/transactions/{tranId}`              | Authenticated        | View a single transaction.                                                 |
| `POST`               | `/reports`                            | Authenticated        | Submit a transaction report for **asynchronous** batch processing (returns **202** + `jobExecutionId`). |
| `GET`, `POST`        | `/users`                              | **ADMIN**            | List (page size 7) / create a user.                                        |
| `GET`, `PUT`, `DELETE` | `/users/{userId}`                   | **ADMIN**            | View / update / delete a user.                                             |

Operational probes `/actuator/health` and `/actuator/info` are public; `/actuator/metrics` requires authentication.

#### Pagination

Endpoints that re-express the legacy 3270 browse screens (`/cards`, `/transactions`, `/users`) return a **fixed page size of 7** rows, preserving the original screen geometry. Requesting a larger `size` (for example `?size=999`) is **capped at 7**.

#### Key HTTP status codes

| Status | Meaning in this API                                                                 |
| :----- | :--------------------------------------------------------------------------------- |
| `200`  | Successful `GET` / `PUT`.                                                          |
| `201`  | Created — a new resource was created (`POST /transactions`, `POST /users`).      |
| `202`  | Report request accepted for asynchronous processing (`POST /reports`).            |
| `400`  | Validation failure (e.g. missing/invalid date, both-or-neither account/card key, oversize or malformed field, malformed JSON). |
| `401`  | Missing, malformed, tampered, or expired JWT.                                      |
| `403`  | Authenticated but lacking the required `ADMIN` role.                               |
| `404`  | Resource not found.                                                                |
| `405`  | HTTP method not allowed for the path.                                              |
| `409`  | Optimistic-lock conflict — the submitted account `version` is stale (concurrent update). |
| `415`  | Unsupported media type (a non-JSON `Content-Type` was sent).                       |

Every error response uses one standardized JSON envelope (`timestamp`, `status`, `error`, `message`, and — for validation failures — `fieldErrors`); it never exposes a stack trace, SQL, exception class, or the request path.

#### Batch transaction-posting reject codes

The daily transaction-posting batch job (`TransactionPostingJob`, the re-expression of `CBTRN02C`) validates each input record through an ordered gauntlet and writes rejects to a fixed-width 430-byte reject record. The full reject-code superset is:

| Code  | Reason                                                                  |
| :---- | :--------------------------------------------------------------------- |
| `100` | Invalid card number (no cross-reference record found).                  |
| `101` | Account record not found for the cross-referenced account.             |
| `102` | Over the credit limit (cycle-based balance check).                     |
| `103` | Transaction expired (account expiry date precedes the transaction origination date). |
| `109` | Account update failed (post-update rewrite failure).                   |

### Database migrations

[Flyway](https://flywaydb.org/) runs the following migrations from `src/main/resources/db/migration/` automatically on startup:

| Migration                | Contents                                               |
| :----------------------- | :----------------------------------------------------- |
| `V1__schema.sql`         | 10 tables, foreign keys, sequences, and 3 indexes      |
| `V2__seed_reference.sql` | Reference data (transaction types/categories, groups)  |
| `V3__seed_master.sql`    | Master and balance data (customers, accounts, cards)   |
| `V4__seed_users.sql`     | BCrypt-hashed user seed (generated)                    |

<br/>

## Technologies used

> **Legacy stack (mainframe).** The technologies listed below describe the **original** mainframe implementation, retained as the source-of-truth reference. The modernized edition is built on **Java 17 / Spring Boot 3.2.x / PostgreSQL** -- see *[Building and Running the Java Application](#building-and-running-the-java-application)* above.

1. COBOL
2. CICS
3. VSAM
4. JCL
5. RACF

<br/>

## Installation on the mainframe 

> **Legacy (mainframe) deployment -- reference only.** This section documents the original z/OS deployment and is retained as the source-of-truth reference. For the modernized Java edition, see *[Building and Running the Java Application](#building-and-running-the-java-application)* above. Note that no `jcl` folder exists in this repository; the JCL jobs are documented here only, and their batch orchestration is reproduced by Spring Batch jobs in the Java edition.

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

> **Legacy (mainframe) batch -- reference only.** In the modernized Java edition these JCL jobs are reproduced as Spring Batch jobs: `POSTTRAN` / `CBTRN02C` -> transaction-posting job, `INTCALC` / `CBACT04C` -> interest-calculation job, and `CREASTMT` / `CBSTM03A` -> statement-creation job, plus account-refresh and customer-refresh jobs (from `CBACT01C`-`CBACT03C` and `CBCUS01C`). See *[Building and Running the Java Application](#building-and-running-the-java-application)* above.

   
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

> In the modernized Java edition, these 17 online programs are re-implemented as **8 Spring MVC REST controllers** (Auth, Menu, Account, Card, Transaction, BillPayment, Report, User), with one operation per original transaction id.

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

> In the modernized Java edition, these batch programs are re-implemented as **Spring Batch 5 jobs** -- transaction posting (`CBTRN02C`), interest calculation (`CBACT04C`), statement creation (`CBSTM03A`), and account/customer refresh (`CBACT01C`-`CBACT03C`, `CBCUS01C`).

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

> **Legacy (mainframe) 3270 screens -- reference only.** The screens shown below are the original BMS 3270 character-terminal screens, retained as the source-of-truth reference. The modernized Java edition retires the BMS UI and exposes a JSON REST API with **no web UI** -- see *[Building and Running the Java Application](#building-and-running-the-java-application)* above.

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


