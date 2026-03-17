# CardDemo Java Migration — Developer Onboarding Guide

> **From clean machine to running application in 15 minutes.**

This guide covers the complete setup path for the **CardDemo Java 25 + Spring Boot 3.5.x** application — a faithful migration of the AWS CardDemo mainframe Credit Card Management System originally written in COBOL/CICS/VSAM. Follow these steps to clone, build, run, and modify the application without needing any prior mainframe experience.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
   - [Hardware Requirements](#11-hardware-requirements)
   - [Software Requirements](#12-software-requirements)
   - [No Mainframe Dependencies](#13-no-mainframe-dependencies)
2. [Quick Start — Clone → Build → Run](#2-quick-start--clone--build--run)
   - [Step 1: Clone the Repository](#step-1-clone-the-repository)
   - [Step 2: Set Environment Variables](#step-2-set-environment-variables)
   - [Step 3: Build and Test](#step-3-build-and-test)
   - [Step 4: Run the Application](#step-4-run-the-application)
   - [Step 5: Test Login](#step-5-test-login)
3. [Domain Context — Understanding CardDemo](#3-domain-context--understanding-carddemo)
   - [What is CardDemo?](#31-what-is-carddemo)
   - [Business Functions](#32-business-functions)
   - [Data Model Overview](#33-data-model-overview)
   - [COBOL Heritage — Key Concepts](#34-cobol-heritage--key-concepts)
4. [Project Structure Guide](#4-project-structure-guide)
   - [Directory Layout](#41-directory-layout)
   - [Key Architectural Patterns](#42-key-architectural-patterns)
5. [Common Development Tasks](#5-common-development-tasks)
   - [Running Unit Tests](#51-running-unit-tests)
   - [Running Unit + Integration Tests](#52-running-unit--integration-tests)
   - [Adding a New Service Method](#53-adding-a-new-service-method)
   - [Database Schema Changes](#54-database-schema-changes)
   - [Checking Code Coverage](#55-checking-code-coverage)
   - [Running a Batch Job](#56-running-a-batch-job)
6. [Common Pitfalls](#6-common-pitfalls)
7. [Suggested Next Tasks (Out of Scope)](#7-suggested-next-tasks-out-of-scope)
8. [Key Documentation Links](#8-key-documentation-links)

---

## 1. Prerequisites

### 1.1 Hardware Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| RAM | 8 GB | 16 GB (for running Testcontainers alongside the IDE) |
| Disk Space | 10 GB free | 20 GB free (includes Docker images and Maven cache) |
| CPU | 2 cores | 4+ cores |
| Docker | Docker-capable host (native Linux or Docker Desktop virtualization) | Same |

### 1.2 Software Requirements

Install the following tools before proceeding. Each entry includes the verification command you should run after installation.

#### 1. Java 25 LTS

Java 25 is a Long-Term Support release (September 2025, 8-year Oracle premier support).

| Method | Command |
|--------|---------|
| **SDKMAN (Linux/macOS)** | `sdk install java 25-open` |
| **Homebrew (macOS)** | `brew install openjdk@25` |
| **Manual download** | [https://jdk.java.net/25/](https://jdk.java.net/25/) |
| **Adoptium (all platforms)** | [https://adoptium.net/](https://adoptium.net/) — select Temurin 25 |

**Verify:**

```bash
java -version
# Expected output includes: openjdk version "25" or similar
```

#### 2. Docker

Required for Testcontainers, which automatically provisions a PostgreSQL 16+ container during integration tests. No manual database setup needed for testing.

| Platform | Install |
|----------|---------|
| **macOS / Windows** | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| **Linux** | [Docker Engine](https://docs.docker.com/engine/install/) |

**Verify:**

```bash
docker --version
# Expected: Docker version 24.x or later

docker run --rm hello-world
# Expected: "Hello from Docker!" message
```

#### 3. Git

For cloning the repository and version control.

**Verify:**

```bash
git --version
# Expected: git version 2.x or later
```

#### 4. PostgreSQL 16+ (Optional — for local development)

Not needed for running tests (Testcontainers handles this automatically). Only required if you want to run the application locally against a persistent database.

**Quick start with Docker:**

```bash
docker run -d \
  --name cardemo-pg \
  -e POSTGRES_DB=cardemo \
  -e POSTGRES_USER=cardemo \
  -e POSTGRES_PASSWORD=cardemo \
  -p 5432:5432 \
  postgres:16-alpine
```

**Verify:**

```bash
docker exec cardemo-pg psql -U cardemo -d cardemo -c "SELECT version();"
# Expected: PostgreSQL 16.x
```

#### 5. Maven 3.9.9

**You do NOT need to install Maven separately.** The project includes the Maven Wrapper (`./mvnw` on Linux/macOS, `mvnw.cmd` on Windows) which automatically downloads and caches Maven 3.9.9 on first use. All build commands in this guide use `./mvnw`.

#### 6. IDE (Recommended)

Any Java IDE will work. Recommended options:

| IDE | Notes |
|-----|-------|
| **IntelliJ IDEA** (Community or Ultimate) | Best Java support; auto-detects Maven projects |
| **VS Code** with [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack) | Lightweight; good for smaller edits |
| **Eclipse** with Spring Tools Suite | Alternative full-featured IDE |

### 1.3 No Mainframe Dependencies

> **You do NOT need a COBOL compiler, CICS runtime, VSAM subsystem, or z/OS mainframe access.**
>
> The Java migration is fully self-contained. All original COBOL source code is preserved under `legacy/app/` for reference and traceability, but it is never compiled or executed. The Java application is the complete, running system.

---

## 2. Quick Start — Clone → Build → Run

### Step 1: Clone the Repository

```bash
git clone <repository-url>
cd CardDemo
```

### Step 2: Set Environment Variables

The application reads database credentials from environment variables. **No credentials are hardcoded anywhere in the codebase.**

```bash
# Required when running the application (not needed for tests — Testcontainers handles it)
export DB_URL=jdbc:postgresql://localhost:5432/cardemo
export DB_USERNAME=cardemo
export DB_PASSWORD=cardemo
```

On Windows (PowerShell):

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/cardemo"
$env:DB_USERNAME = "cardemo"
$env:DB_PASSWORD = "cardemo"
```

> **Note:** These variables match the placeholders in `src/main/resources/application.yml`. For tests, Testcontainers provisions its own ephemeral PostgreSQL container, so no database environment variables are required to run `./mvnw test` or `./mvnw verify`.

### Step 3: Build and Test

```bash
./mvnw clean verify
```

This single command performs the entire build and quality-gate pipeline:

| Stage | What Happens |
|-------|-------------|
| **Compile** | Java 25 source compiled with strict zero-warning enforcement (`-Xlint:all -Werror`) |
| **Unit Tests** | JUnit 5 tests run via Surefire plugin |
| **Integration Tests** | Testcontainers spins up a PostgreSQL 16+ Docker container automatically; integration tests run via Failsafe plugin |
| **Coverage Check** | JaCoCo verifies ≥80% line coverage; build fails if coverage is below threshold |
| **OWASP Check** | `dependency-check-maven` scans all dependencies for known vulnerabilities; build fails on any critical/high CVE (CVSS ≥ 7.0) |

**Expected timing:**

- First run: ~3–5 minutes (Docker image download for `postgres:16-alpine`, Maven dependency resolution)
- Subsequent runs: ~1–2 minutes

**If the build succeeds**, you will see:

```
[INFO] BUILD SUCCESS
```

### Step 4: Run the Application

Start a local PostgreSQL instance first (see [Section 1.2, item 4](#4-postgresql-16-optional--for-local-development) above), then:

```bash
./mvnw spring-boot:run
```

The application starts on `http://localhost:8080`. On first startup, **Flyway** automatically:

1. Creates all database tables (`V1__create_schema.sql`)
2. Creates secondary indexes (`V2__create_indexes.sql`)
3. Loads seed data from the original VSAM ASCII fixtures (`V100__seed_data.sql`)

**Verify the application is running:**

```bash
# Health check
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus
# Expected: Prometheus-format metrics output
```

### Step 5: Test Login

The seed data includes two test users (passwords are BCrypt-hashed in the database — the original plaintext passwords from the legacy COBOL system are hashed during the Flyway seed data load):

| Role | User ID | Password | Access Level |
|------|---------|----------|-------------|
| **Admin** | `ADMIN001` | `PASSWORD` | User management + all user functions |
| **Regular User** | `USER0001` | `PASSWORD` | Account, card, transaction, billing, reports |

**Authentication endpoint:**

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"userId": "USER0001", "password": "PASSWORD"}'
```

---

## 3. Domain Context — Understanding CardDemo

### 3.1 What is CardDemo?

CardDemo is an **AWS open-source mainframe sample application** (Apache 2.0 license, released Q4 2022) designed to test and showcase mainframe migration and modernization tooling. It is a **Credit Card Management System** that was originally built with:

- **COBOL** — 28 programs (18 online CICS + 10 batch)
- **CICS** — Transaction Processing monitor (pseudo-conversational model)
- **VSAM** — Virtual Storage Access Method (10 keyed datasets + 3 alternate indexes)
- **JCL** — Job Control Language (batch job scheduling and sequencing)

The Java migration faithfully reproduces **100% of the original business logic** with zero behavioral regressions. Every COBOL paragraph has a corresponding Java method (see [`docs/traceability-matrix.md`](traceability-matrix.md) for the complete mapping).

**Two user roles:**

| Role | Capabilities |
|------|-------------|
| **Admin** (`UserType.ADMIN`) | User list, add, update, delete operations |
| **Regular User** (`UserType.USER`) | Account view/update, credit card operations, transactions, bill payment, reports |

### 3.2 Business Functions

The application is organized into 8 major functional areas, each mapping to one or more Java service classes:

| # | Function | Java Service(s) | Description |
|---|----------|-----------------|-------------|
| 1 | **User Authentication** | `service/online/SignonService` | Login with user ID + password, role determination, session initialization |
| 2 | **Account Management** | `service/online/AccountViewService`, `AccountUpdateService` | View and update credit card account details (balance, limits, dates) |
| 3 | **Credit Card Operations** | `service/online/CreditCardListService`, `CreditCardDetailService`, `CreditCardUpdateService` | List, view, and update credit cards linked to accounts |
| 4 | **Transaction Management** | `service/online/TransactionListService`, `TransactionViewService`, `TransactionAddService` | Browse transaction history, view details, add new transactions |
| 5 | **Bill Payment** | `service/online/BillPaymentService` | Process bill payments against credit card accounts |
| 6 | **Reports** | `service/online/ReportService` | Generate and retrieve transaction reports |
| 7 | **User Administration** | `service/online/UserListService`, `UserAddService`, `UserUpdateService`, `UserDeleteService` | Admin-only CRUD operations on user accounts |
| 8 | **Batch Processing** | `batch/job/DailyPostingJobConfig`, `InterestCalcJobConfig`, `StatementGenJobConfig`, etc. | Daily transaction posting, interest calculation, statement generation |

### 3.3 Data Model Overview

The application manages 11 database entities, each derived from a VSAM dataset and its corresponding COBOL copybook:

| Entity | Original Size | Primary Key | Description |
|--------|--------------|-------------|-------------|
| **Account** | 300 bytes | `ACCT-ID` (11 chars) | Credit card account: current balance, credit limit, cash limit, dates, status |
| **Card** | 150 bytes | `CARD-NUM` (16 chars) | Credit card record: number, status, expiry, linked account |
| **CardXref** | 50 bytes | `XREF-CARD-NUM` (16 chars) | Junction table linking cards to accounts and customers |
| **Customer** | 500 bytes | `CUST-ID` (9 chars) | Customer personal information (PII-sensitive: SSN, government ID) |
| **Transaction** | 350 bytes | `TRAN-ID` (16 chars) | Transaction master: amount, type, category, merchant, timestamps |
| **DailyTransaction** | ~220 bytes | Sequential | Staging table for daily batch transaction posting input |
| **UserSecurity** | 80 bytes | `SEC-USR-ID` (8 chars) | User credentials: ID, name, BCrypt-hashed password, role type |
| **TransactionTypeRef** | ~50 bytes | `TRAN-TYPE` (2 chars) | 7 reference records for transaction types |
| **TransactionCategoryRef** | ~50 bytes | `TRAN-CAT` (4 chars) | 18 reference records for transaction categories |
| **DiscountGroup** | ~50 bytes | Composite | Interest rate discount group definitions |
| **CategoryBalance** | 50 bytes | Composite | Category-level balance aggregation for reporting |

**Entity Relationship Diagram (simplified):**

```
Customer (1) ──── (N) CardXref (N) ──── (1) Card
                         │
                         │
                    Account (1) ──── (N) Transaction
                         │
                    CategoryBalance
```

### 3.4 COBOL Heritage — Key Concepts

If you are new to mainframe-migrated applications, here are the key COBOL concepts that carry over into the Java codebase and why they matter:

| COBOL Concept | Java Equivalent | Why It Matters |
|---------------|----------------|----------------|
| **COMMAREA** (1024-byte session structure defined in `COCOM01Y.cpy`) | `CardDemoContext` — a `@RequestScope` Spring bean | Carries user ID, user type (Admin/User), current program, navigation state between service calls. In COBOL, this was passed between programs via CICS; in Java, it is a request-scoped bean injected into services. |
| **VSAM READ / WRITE / REWRITE / DELETE** | JPA Repository methods: `findById()`, `save()`, `deleteById()` | All data access in COBOL was through keyed VSAM file operations. These map directly to Spring Data JPA repository methods. |
| **VSAM STARTBR / READNEXT / READPREV / ENDBR** | JPA pagination queries: `findAll(Pageable)`, custom `@Query` | COBOL browse operations (sequential scan through records) become paginated JPA queries with `Sort` and `Pageable`. |
| **88-level condition names** | Java Enums: `UserType.ADMIN`, `UserType.USER` | COBOL uses `88 CDEMO-USRTYP-ADMIN VALUE 'A'` as named boolean conditions. Java enums provide the same type-safe semantics. |
| **COMP-3 packed decimal** (`PIC S9(10)V99`) | `java.math.BigDecimal` | All monetary amounts in COBOL use exact decimal arithmetic via packed-decimal encoding. Java `BigDecimal` with `RoundingMode.HALF_UP` preserves identical precision. **Never use `double` or `float` for monetary values.** |
| **JCL Jobs** (POSTTRAN, INTCALC, CREASTMT) | Spring Batch `Job` with `Step` definitions | Batch processing in COBOL was orchestrated by JCL job streams. Each JCL job becomes a Spring Batch job with equivalent step sequencing. |
| **FILE STATUS codes** (two-byte status: `00`, `22`, `23`, `35`) | Custom exception hierarchy: `FileStatusException` → `RecordNotFoundException`, `DuplicateRecordException` | COBOL programs check a two-byte FILE STATUS code after every I/O operation. The Java codebase maps these to typed exceptions for equivalent error handling. |
| **CALL 'subroutine'** | `@Autowired` service injection | COBOL subroutine calls (e.g., `CALL 'CBSTM03B' USING WS-PARM`) become Spring bean injection and method invocation. |
| **COPY copybook** | Shared Java classes in `common/dto/` and `entity/` | COBOL copybooks define reusable record layouts. Each copybook becomes a shared Java class (DTO or JPA entity). |

---

## 4. Project Structure Guide

### 4.1 Directory Layout

```
CardDemo/
├── pom.xml                              — Maven build configuration (Spring Boot 3.5.x parent)
├── mvnw / mvnw.cmd                      — Maven Wrapper scripts (no separate Maven install needed)
├── .mvn/wrapper/                         — Maven Wrapper JAR and properties
│
├── src/main/java/com/cardemo/
│   ├── CardDemoApplication.java          — Spring Boot entry point (@SpringBootApplication)
│   ├── common/
│   │   ├── context/
│   │   │   └── CardDemoContext.java       — Session context bean (← COBOL COMMAREA)
│   │   ├── dto/                           — Data Transfer Objects (← COBOL copybook record layouts)
│   │   ├── enums/                         — Enum types (← COBOL 88-level conditions)
│   │   ├── exception/                     — Custom exception hierarchy (← VSAM FILE STATUS codes)
│   │   ├── util/                          — Utility classes (date conversion, string processing)
│   │   ├── validation/                    — Field validation (← COBOL validation logic)
│   │   └── message/                       — Message constants (← COBOL CSMSG01Y/CSMSG02Y)
│   ├── config/
│   │   ├── SecurityConfig.java            — Spring Security: role-based auth (Admin/User)
│   │   ├── BatchConfig.java               — Spring Batch infrastructure
│   │   ├── JpaConfig.java                 — JPA/Hibernate settings
│   │   ├── ObservabilityConfig.java        — Micrometer tracing, metrics
│   │   └── AppProperties.java             — Custom @ConfigurationProperties
│   ├── entity/                             — JPA entities (one per VSAM dataset — 11 total)
│   ├── repository/                         — Spring Data JPA repositories (11 interfaces)
│   ├── service/
│   │   ├── online/                         — Online service classes (1 per CICS program — 17 total)
│   │   └── batch/                          — Batch service classes (1 per batch program — 10 total)
│   ├── batch/
│   │   ├── job/                            — Spring Batch job configurations (← JCL jobs)
│   │   ├── reader/                         — ItemReaders (fixed-width file parser, DALYTRAN reader)
│   │   ├── processor/                      — ItemProcessors (validation, calculation, formatting)
│   │   └── writer/                         — ItemWriters (reject file, statement output)
│   └── controller/                         — REST API controllers (7 endpoints)
│
├── src/main/resources/
│   ├── application.yml                     — Main configuration (DB, JPA, Batch, Actuator)
│   ├── application-test.yml                — Test profile (Testcontainers PostgreSQL)
│   ├── application-local.yml               — Local dev profile (enhanced logging)
│   ├── logback-spring.xml                  — Structured logging config (JSON + correlation IDs)
│   └── db/
│       ├── migration/
│       │   ├── V1__create_schema.sql       — All tables, primary keys, constraints
│       │   └── V2__create_indexes.sql      — Secondary indexes (VSAM AIX equivalents)
│       └── seed/
│           └── V100__seed_data.sql         — Seed data parsed from legacy ASCII fixtures
│
├── src/test/
│   ├── java/com/cardemo/                   — JUnit 5 + Testcontainers tests
│   │   ├── service/online/*Test.java       — Unit tests for online services
│   │   ├── service/batch/*Test.java        — Unit tests for batch services
│   │   ├── batch/*IntegrationTest.java     — Spring Batch integration tests
│   │   ├── repository/*Test.java           — JPA repository tests
│   │   ├── controller/*Test.java           — MockMvc controller tests
│   │   └── common/                         — Utility and validation tests
│   └── resources/
│       ├── application-test.yml            — Test configuration
│       └── fixtures/                        — Copy of legacy ASCII test data files
│
├── docs/
│   ├── architecture/diagrams.md            — Before/after Mermaid architecture diagrams
│   ├── decision-log.md                     — Non-trivial implementation decisions with rationale
│   ├── traceability-matrix.md              — 100% COBOL paragraph → Java method mapping
│   ├── onboarding.md                       — This file
│   └── slides/executive-summary.html       — reveal.js executive presentation
│
└── legacy/app/                              — Original COBOL source (preserved for reference)
    ├── bms/                                 — 17 BMS map sources (3270 screen definitions)
    ├── cbl/                                 — 28 COBOL programs (18 online + 10 batch)
    ├── cpy/                                 — 28 data copybooks (record layouts)
    ├── cpy-bms/                             — 17 BMS data structure copybooks
    ├── catlg/                               — VSAM catalog metadata (LISTCAT.txt)
    └── data/ASCII/                          — 9 fixed-width test fixture files
```

### 4.2 Key Architectural Patterns

| # | Pattern | Description |
|---|---------|-------------|
| 1 | **Layered Architecture** | `Controller` → `Service` → `Repository` → `Entity`. Each layer has a clear responsibility. Controllers handle HTTP, services contain business logic, repositories manage data access. |
| 2 | **One Service per COBOL Program** | Each of the 28 COBOL programs (`CO*.cbl` for online, `CB*.cbl` for batch) has a matching `*Service.java`. This provides a 1:1 traceability mapping. |
| 3 | **Repository Pattern** | Each VSAM dataset becomes a JPA `@Entity` + `JpaRepository` interface. VSAM keyed access is replaced by Spring Data JPA methods. |
| 4 | **DTO Pattern** | Each COBOL copybook record layout becomes a shared Java class in `common/dto/`. These carry data between layers without exposing JPA entities to controllers. |
| 5 | **Exception Hierarchy** | VSAM file status codes (`00`, `22`, `23`, `35`) map to a typed exception hierarchy: `FileStatusException` → `RecordNotFoundException` (status `23`), `DuplicateRecordException` (status `22`), etc. |
| 6 | **Request-Scoped Context** | The COBOL `COMMAREA` (1024-byte session structure) becomes `CardDemoContext` — a `@RequestScope` Spring bean that carries user state within a single HTTP request. |
| 7 | **Spring Batch Chunk Processing** | JCL batch jobs become Spring Batch `Job` → `Step` → `ItemReader` / `ItemProcessor` / `ItemWriter` pipelines with equivalent step sequencing and error handling. |

---

## 5. Common Development Tasks

### 5.1 Running Unit Tests

```bash
./mvnw test
```

Runs all unit tests (JUnit 5 via Surefire plugin). Does **not** require Docker — unit tests use mocks, not real databases.

### 5.2 Running Unit + Integration Tests

```bash
./mvnw verify
```

Runs unit tests **and** integration tests (via Failsafe plugin). **Requires Docker running** — Testcontainers will automatically start a PostgreSQL 16+ container for integration tests.

This also runs the JaCoCo coverage check and OWASP dependency check.

### 5.3 Adding a New Service Method

Example workflow for adding a method to `AccountViewService`:

1. **Read the COBOL source** — Open `legacy/app/cbl/COACTVWC.cbl` and find the paragraph you are implementing (e.g., `9000-READ-ACCT`)
2. **Implement the Java method** — Add the corresponding method to `src/main/java/com/cardemo/service/online/AccountViewService.java`
3. **Write a unit test** — Add a test case in `src/test/java/com/cardemo/service/online/AccountViewServiceTest.java`
4. **Update the traceability matrix** — Add the mapping in `docs/traceability-matrix.md`
5. **Run tests** — `./mvnw verify` to confirm everything passes

### 5.4 Database Schema Changes

Flyway manages all database schema evolution. **Never modify an existing migration file** after it has been applied.

1. Create a new migration file: `src/main/resources/db/migration/V3__your_description.sql`
2. Update the corresponding JPA entity in `src/main/java/com/cardemo/entity/`
3. Restart the application — Flyway runs automatically on startup

```bash
# Alternative: run Flyway directly
./mvnw flyway:migrate
```

### 5.5 Checking Code Coverage

After running the full build:

```bash
./mvnw verify
```

Open the JaCoCo report in your browser:

```bash
open target/site/jacoco/index.html       # macOS
xdg-open target/site/jacoco/index.html   # Linux
start target/site/jacoco/index.html      # Windows
```

The build enforces a **minimum 80% line coverage** threshold. If coverage drops below 80%, the build fails.

### 5.6 Running a Batch Job

Batch jobs are not auto-started on application startup (`spring.batch.job.enabled=false`). They can be triggered programmatically or via REST endpoints.

```bash
# Example: trigger daily posting job via Spring Boot Actuator (if endpoint exposed)
curl -X POST http://localhost:8080/api/batch/daily-posting
```

---

## 6. Common Pitfalls

### Pitfall 1: Docker Not Running

**Symptom:** Integration tests fail with:
```
Could not find a valid Docker environment
```

**Solution:** Start Docker Desktop (macOS/Windows) or Docker Engine (Linux) before running `./mvnw verify`. Testcontainers requires a running Docker daemon to provision the PostgreSQL test container.

---

### Pitfall 2: BigDecimal Scale Mismatches

**Symptom:** Assertion errors on monetary values like:
```
expected: 1234.56 but was: 1234.5600
```

**Solution:** All monetary fields use `BigDecimal` with explicit scale matching the COBOL PIC clause (typically 2 decimal places for `V99`).

- Always use `RoundingMode.HALF_UP` (matches COBOL default rounding)
- **Never compare BigDecimal with `equals()`** — use `compareTo() == 0` instead, because `equals()` considers scale (e.g., `1.0` ≠ `1.00`)
- When creating BigDecimal values in tests, use `new BigDecimal("1234.56")` (string constructor), not `new BigDecimal(1234.56)` (double constructor introduces floating-point artifacts)

---

### Pitfall 3: Missing Environment Variables

**Symptom:** Application fails to start with:
```
Failed to configure a DataSource: 'url' attribute is not specified
```

**Solution:** Set the required environment variables before running the application:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/cardemo
export DB_USERNAME=cardemo
export DB_PASSWORD=cardemo
```

These are **not** needed for running tests — only for `./mvnw spring-boot:run`.

---

### Pitfall 4: Flyway Migration Ordering

**Symptom:** Errors like:
```
Migration checksum mismatch for migration version X
```
or
```
Detected resolved migration not applied to database
```

**Solution:** Never modify an existing `V*` migration file after it has been applied to a database. If you need to change the schema, create a **new** migration file with the next version number (e.g., `V3__add_column.sql`).

To reset a local database completely:

```bash
# Drop and recreate the local PostgreSQL database
docker exec cardemo-pg psql -U cardemo -c "DROP DATABASE cardemo;"
docker exec cardemo-pg psql -U cardemo -c "CREATE DATABASE cardemo;"
# Restart the application — Flyway re-applies all migrations
```

---

### Pitfall 5: COBOL Paragraph Name Confusion

**Symptom:** Confusion about which Java method corresponds to which COBOL logic.

**Solution:** Consult [`docs/traceability-matrix.md`](traceability-matrix.md) for the complete paragraph-to-method mapping. Every Java service class includes comments referencing the source COBOL paragraph name and line number. For example:

```java
// Paragraph: 9000-READ-ACCT (COACTVWC.cbl line 687)
public Account readAccount(String accountId) { ... }
```

---

### Pitfall 6: Test Data Mismatches

**Symptom:** Tests fail because expected seed data does not match actual database contents.

**Solution:** All seed data is loaded from `src/main/resources/db/seed/V100__seed_data.sql`, which is parsed from the original VSAM ASCII fixture files at `legacy/app/data/ASCII/*.txt`. Test fixture files at `src/test/resources/fixtures/` are copies of these same legacy data files.

If you see mismatches:
1. Verify the fixture file in `src/test/resources/fixtures/` matches the corresponding file in `legacy/app/data/ASCII/`
2. Check that `V100__seed_data.sql` was generated correctly from the fixed-width source data

---

### Pitfall 7: Java 25 Compilation Warnings

**Symptom:** Build fails with:
```
error: warnings found and -Werror specified
```

**Solution:** The project enforces **zero-warning compilation** via `-Xlint:all -Werror` in the Maven Compiler plugin. All compiler warnings must be resolved — they cannot be suppressed. Common causes:
- Unused imports → remove them
- Unchecked casts → add proper generic types
- Deprecated API usage → use the replacement API

---

### Pitfall 8: Port Already in Use

**Symptom:** Application fails to start with:
```
Web server failed to start. Port 8080 was already in use.
```

**Solution:** Either stop the process using port 8080, or start the application on a different port:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=9090"
```

---

## 7. Suggested Next Tasks (Out of Scope)

The following improvements were identified during migration but are **explicitly out of scope** for the current phase. They represent potential future work:

| # | Improvement | Description | Why Deferred |
|---|------------|-------------|-------------|
| 1 | **Microservices Decomposition** | Split into bounded contexts: Account Service, Card Service, Transaction Service, User Service | Deferred to modernization Phase 4–6 per tech spec. Current architecture is a modular monolith preserving original COBOL coupling. |
| 2 | **API Documentation** | Add OpenAPI/Swagger annotations to controllers for auto-generated interactive API docs | Not present in original COBOL application; would be a feature expansion. |
| 3 | **Frontend UI** | Build a React, Angular, or other frontend consuming the REST APIs | Explicitly out of scope per project requirements. BMS 3270 maps are translated to service-layer DTOs only. |
| 4 | **Production Deployment** | Docker Compose, Kubernetes manifests, Helm charts, CI/CD pipeline | All validation runs locally per project constraints. Production deployment is a separate effort. |
| 5 | **Performance Optimization** | Connection pooling tuning, query optimization, response caching, index tuning | SLA targets are noted but not contractually enforced in this migration phase. |
| 6 | **Audit Logging** | Database-level audit trail for compliance (who changed what, when) | Not present in the original COBOL application; adding it would be feature expansion. |
| 7 | **API Rate Limiting** | Protect REST endpoints from abuse with rate limiting middleware | Not present in original COBOL application. |
| 8 | **Data Migration Tooling** | ETL pipeline for migrating production VSAM data to PostgreSQL | Only seed-data-based schema initialization is included; production data migration is a separate effort. |
| 9 | **MQ Integration** | Implement actual message queue integration for external system communication | Only interface contracts are preserved; actual MQ middleware integration is out of scope. |
| 10 | **GDG Backup Replication** | Implement multi-generation backup cycling (VSAM Generation Data Groups) | Simplified to PostgreSQL audit columns and table snapshots. |

---

## 8. Key Documentation Links

| Document | Path | Description |
|----------|------|-------------|
| **Architecture Diagrams** | [`docs/architecture/diagrams.md`](architecture/diagrams.md) | Before (COBOL/CICS/VSAM) and after (Java/Spring Boot/PostgreSQL) Mermaid architecture diagrams |
| **Decision Log** | [`docs/decision-log.md`](decision-log.md) | Every non-trivial implementation decision with alternatives considered, rationale, and risks |
| **Traceability Matrix** | [`docs/traceability-matrix.md`](traceability-matrix.md) | 100% COBOL paragraph → Java method mapping across all 28 programs |
| **Executive Summary** | [`docs/slides/executive-summary.html`](slides/executive-summary.html) | reveal.js presentation for non-technical leadership |
| **Project README** | [`README.md`](../README.md) | Project overview, quick start, technology stack |
| **Legacy COBOL Source** | [`legacy/app/`](../legacy/app/) | Complete original COBOL source tree preserved for reference and audit traceability |

---

*Last updated: March 2026*
*CardDemo Java Migration — Version 1.0.0-SNAPSHOT*
