# CardDemo Backend - Spring Boot Application

## Overview

The CardDemo Backend is a modern Java 21 Spring Boot 3.4.5 application that replaces the legacy COBOL/CICS mainframe credit card management system. This backend provides a complete REST API, batch processing capabilities, and database integration to deliver the same business functionality as the original mainframe application while leveraging cloud-native technologies.

### Key Features

- **REST API**: RESTful endpoints replacing 15 CICS online transaction programs
- **JPA/PostgreSQL**: Spring Data JPA with PostgreSQL 16.x database replacing VSAM file storage
- **Spring Batch**: Batch job processing replacing 28 JCL batch jobs
- **Spring Security**: JWT-based authentication and role-based access control replacing RACF
- **Flyway Migrations**: Automated database schema management and version control
- **Cloud-Native**: Containerized deployment with Docker and Kubernetes support

### Migration Context

This application preserves **EXACT business logic** from 26 COBOL programs while modernizing the technology stack:
- All COBOL COMP-3 precision maintained using Java BigDecimal
- All 11 VSAM datasets migrated to PostgreSQL tables with proper indexes
- All transaction boundaries and rollback logic preserved
- All field validations and business rules maintained identically

---

## Table of Contents

- [Technology Stack](#technology-stack)
- [Architecture Overview](#architecture-overview)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Local Development Setup](#local-development-setup)
- [Database Configuration](#database-configuration)
- [REST API Endpoints](#rest-api-endpoints)
- [Spring Batch Jobs](#spring-batch-jobs)
- [Testing](#testing)
- [Build and Deployment](#build-and-deployment)
- [Performance Requirements](#performance-requirements)
- [Security](#security)
- [Monitoring and Observability](#monitoring-and-observability)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [Migration Notes](#migration-notes)
- [Links](#links)

---

## Technology Stack

The backend application leverages modern enterprise Java technologies:

### Core Framework
- **Java**: 21 LTS (Long-Term Support)
- **Spring Boot**: 3.4.5 (latest stable as of May 2025)
- **Spring Framework**: 6.2.x (bundled with Spring Boot 3.4.5)
- **Maven**: 3.9.x (build and dependency management)

### Data Access
- **Spring Data JPA**: 3.4.x (data access abstraction)
- **Hibernate ORM**: 6.6.x (default JPA implementation)
- **PostgreSQL**: 16.x (relational database)
- **PostgreSQL JDBC Driver**: 42.7.4
- **Flyway**: 10.20.1 (database migrations)

### Batch Processing
- **Spring Batch**: 5.2.x (enterprise batch processing)

### Security
- **Spring Security**: 6.4.x (authentication and authorization)
- **JWT (JJWT)**: 0.12.6 (JSON Web Token implementation)
- **BCrypt**: Password hashing (via Spring Security)

### API Documentation
- **SpringDoc OpenAPI**: 2.6.0 (Swagger/OpenAPI 3 documentation)

### Utilities
- **Lombok**: 1.18.36 (reduce boilerplate code)
- **MapStruct**: 1.6.3 (entity-DTO mapping)
- **Jackson**: 2.18.2 (JSON serialization)
- **Apache Commons Lang**: 3.17.0 (string utilities)

### Testing
- **JUnit**: 5.10.x (unit testing framework)
- **Mockito**: 5.14.2 (mocking framework)
- **Testcontainers**: 1.20.4 (integration testing with Docker)
- **REST Assured**: 5.5.0 (REST API testing)
- **Spring Boot Test**: (bundled testing utilities)

### Containerization
- **Docker**: 24.x (containerization platform)
- **Kubernetes**: 1.31 (orchestration, optional for production)

---

## Architecture Overview

The backend follows a **layered architecture** pattern that cleanly separates concerns:

```
┌─────────────────────────────────────────────────────────────┐
│                     Client Layer                             │
│              (React SPA / External Systems)                  │
└──────────────────────┬───────────────────────────────────────┘
                       │ HTTP/REST
┌──────────────────────▼───────────────────────────────────────┐
│                  Controller Layer                            │
│  REST API endpoints replacing CICS transaction programs      │
│  - AuthController (COSGN00C)                                 │
│  - AccountController (COACTUPC, COACTVWC)                    │
│  - CardController (COCRDLIC, COCRDUPC)                       │
│  - TransactionController (COTRN00C-02C)                      │
│  - 5 more controllers...                                     │
└──────────────────────┬───────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                   Service Layer                              │
│  Business logic from COBOL PROCEDURE DIVISION                │
│  - AuthService                                               │
│  - AccountService                                            │
│  - CardService                                               │
│  - TransactionService                                        │
│  - 4 more services...                                        │
└──────────────────────┬───────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                 Repository Layer                             │
│  Data access replacing VSAM I/O operations                   │
│  - AccountRepository (JpaRepository)                         │
│  - CardRepository                                            │
│  - TransactionRepository                                     │
│  - 7 more repositories...                                    │
└──────────────────────┬───────────────────────────────────────┘
                       │ JPA/JDBC
┌──────────────────────▼───────────────────────────────────────┐
│                PostgreSQL Database                           │
│  11 tables replacing VSAM datasets                           │
│  - account, card, customer, transaction, etc.                │
└──────────────────────────────────────────────────────────────┘
```

### Component Summary

| Component Type | Count | Original COBOL Source | Purpose |
|----------------|-------|----------------------|---------|
| REST Controllers | 9 | 15 CICS online programs | HTTP endpoints for client requests |
| Service Classes | 8 | COBOL PROCEDURE DIVISION | Business logic and transaction management |
| JPA Repositories | 10 | VSAM I/O operations | Database CRUD operations |
| JPA Entities | 11 | COBOL copybooks | Domain models and database tables |
| DTOs | 7+ | API contracts | Data transfer between layers |
| Spring Batch Jobs | 4 | 28 JCL jobs | Scheduled batch processing |
| Security Components | 4 | RACF | JWT authentication and authorization |

---

## Project Structure

```
backend/
├── pom.xml                                    # Maven build configuration
├── Dockerfile                                 # Backend container image
├── .dockerignore                              # Docker ignore patterns
├── README.md                                  # This file
│
└── src/
    ├── main/
    │   ├── java/com/carddemo/
    │   │   ├── CardDemoApplication.java       # Spring Boot main class
    │   │   │
    │   │   ├── config/                        # Configuration classes
    │   │   │   ├── DatabaseConfig.java
    │   │   │   ├── SecurityConfig.java
    │   │   │   ├── BatchConfig.java
    │   │   │   ├── RestApiConfig.java
    │   │   │   └── SwaggerConfig.java
    │   │   │
    │   │   ├── controller/                    # REST API Controllers
    │   │   │   ├── AuthController.java                  # COSGN00C
    │   │   │   ├── MenuController.java                  # COMEN01C, COADM01C
    │   │   │   ├── AccountController.java               # COACTUPC, COACTVWC
    │   │   │   ├── CardController.java                  # COCRDLIC, COCRDUPC
    │   │   │   ├── TransactionController.java           # COTRN00C-02C
    │   │   │   ├── BillingController.java               # COBIL00C
    │   │   │   ├── ReportController.java                # CORPT00C
    │   │   │   ├── UserController.java                  # COUSR00C-03C
    │   │   │   └── HealthCheckController.java
    │   │   │
    │   │   ├── service/                       # Business Logic Layer
    │   │   │   ├── AuthService.java
    │   │   │   ├── AccountService.java
    │   │   │   ├── CardService.java
    │   │   │   ├── TransactionService.java
    │   │   │   ├── BillingService.java
    │   │   │   ├── ReportService.java
    │   │   │   ├── UserService.java
    │   │   │   └── ValidationService.java
    │   │   │
    │   │   ├── repository/                    # Data Access Layer
    │   │   │   ├── AccountRepository.java
    │   │   │   ├── CardRepository.java
    │   │   │   ├── CustomerRepository.java
    │   │   │   ├── TransactionRepository.java
    │   │   │   ├── CardAccountXrefRepository.java
    │   │   │   ├── TransactionCategoryRepository.java
    │   │   │   ├── TransactionTypeRepository.java
    │   │   │   ├── DisclosureGroupRepository.java
    │   │   │   ├── UserSecurityRepository.java
    │   │   │   └── TransactionCategoryBalanceRepository.java
    │   │   │
    │   │   ├── model/                         # Domain Models
    │   │   │   ├── entity/                    # JPA Entities
    │   │   │   │   ├── Account.java                     # CVACT01Y.cpy
    │   │   │   │   ├── Card.java                        # CVACT02Y.cpy
    │   │   │   │   ├── Customer.java                    # CVCUS01Y.cpy
    │   │   │   │   ├── Transaction.java                 # CVTRA05Y.cpy
    │   │   │   │   ├── DailyTransaction.java            # CVTRA06Y.cpy
    │   │   │   │   ├── CardAccountXref.java             # CVACT03Y.cpy
    │   │   │   │   ├── TransactionCategory.java         # CVTRA04Y.cpy
    │   │   │   │   ├── TransactionType.java             # CVTRA03Y.cpy
    │   │   │   │   ├── DisclosureGroup.java             # CVTRA02Y.cpy
    │   │   │   │   ├── TransactionCategoryBalance.java  # CVTRA01Y.cpy
    │   │   │   │   └── UserSecurity.java                # CSUSR01Y.cpy
    │   │   │   │
    │   │   │   └── dto/                       # Data Transfer Objects
    │   │   │       ├── AccountDto.java
    │   │   │       ├── CardDto.java
    │   │   │       ├── TransactionDto.java
    │   │   │       ├── UserDto.java
    │   │   │       ├── AuthRequest.java
    │   │   │       ├── AuthResponse.java
    │   │   │       └── ErrorResponse.java
    │   │   │
    │   │   ├── batch/                         # Spring Batch Jobs
    │   │   │   ├── config/
    │   │   │   │   ├── AccountProcessingJobConfig.java  # CBACTJ01-04
    │   │   │   │   ├── TransactionProcessingJobConfig.java # CBTRNJ01-03
    │   │   │   │   ├── CustomerValidationJobConfig.java # CBCUSJ01
    │   │   │   │   └── StatementGenerationJobConfig.java
    │   │   │   │
    │   │   │   ├── processor/
    │   │   │   │   ├── AccountProcessor.java            # CBACT01C-04C
    │   │   │   │   ├── TransactionProcessor.java        # CBTRN01C-03C
    │   │   │   │   └── CustomerProcessor.java           # CBCUS01C
    │   │   │   │
    │   │   │   ├── reader/
    │   │   │   │   ├── AccountReader.java
    │   │   │   │   ├── TransactionReader.java
    │   │   │   │   └── CustomerReader.java
    │   │   │   │
    │   │   │   └── writer/
    │   │   │       ├── AccountWriter.java
    │   │   │       ├── TransactionWriter.java
    │   │   │       └── CustomerWriter.java
    │   │   │
    │   │   ├── security/                      # Spring Security
    │   │   │   ├── JwtTokenProvider.java
    │   │   │   ├── JwtAuthenticationFilter.java
    │   │   │   ├── CustomUserDetailsService.java
    │   │   │   └── SecurityRoles.java
    │   │   │
    │   │   ├── util/                          # Utility Classes
    │   │   │   ├── DateUtil.java                        # CSUTLDTC.cbl
    │   │   │   ├── ValidationUtil.java
    │   │   │   ├── FormatUtil.java
    │   │   │   └── MessageUtil.java
    │   │   │
    │   │   └── exception/                     # Exception Handling
    │   │       ├── GlobalExceptionHandler.java
    │   │       ├── BusinessException.java
    │   │       ├── DataNotFoundException.java
    │   │       └── ValidationException.java
    │   │
    │   └── resources/
    │       ├── application.yml                         # Main configuration
    │       ├── application-dev.yml                     # Development profile
    │       ├── application-test.yml                    # Test profile
    │       ├── application-prod.yml                    # Production profile
    │       │
    │       ├── db/
    │       │   └── migration/                          # Flyway migrations
    │       │       ├── V1__create_account_table.sql
    │       │       ├── V2__create_card_table.sql
    │       │       ├── V3__create_customer_table.sql
    │       │       ├── V4__create_transaction_tables.sql
    │       │       ├── V5__create_reference_tables.sql
    │       │       ├── V6__create_indexes.sql
    │       │       └── V7__insert_initial_data.sql
    │       │
    │       ├── messages/
    │       │   ├── messages.properties                  # CSMSG01Y messages
    │       │   └── messages_en.properties
    │       │
    │       └── static/
    │           └── api-docs.html                        # Swagger documentation
    │
    └── test/
        └── java/com/carddemo/
            ├── controller/                             # Controller tests
            ├── service/                                # Service tests
            ├── repository/                             # Repository tests
            ├── batch/                                  # Batch job tests
            └── integration/                            # Integration tests
```

---

## Prerequisites

Before setting up the backend application locally, ensure you have the following installed:

### Required Software

1. **Java Development Kit (JDK) 21**
   - OpenJDK 21 (recommended) or Oracle JDK 21
   - Download: [Adoptium OpenJDK 21](https://adoptium.net/)
   - Verify installation: `java -version`

2. **Apache Maven 3.9+**
   - Download: [Maven Downloads](https://maven.apache.org/download.cgi)
   - Verify installation: `mvn -version`

3. **PostgreSQL 16.x**
   - Download: [PostgreSQL Downloads](https://www.postgresql.org/download/)
   - Alternative: Use Docker (see setup instructions below)

4. **Docker and Docker Compose** (Optional but Recommended)
   - Download: [Docker Desktop](https://www.docker.com/products/docker-desktop/)
   - Enables containerized development with PostgreSQL

5. **IDE** (Choose one)
   - [IntelliJ IDEA](https://www.jetbrains.com/idea/) (recommended for Java development)
   - [Eclipse IDE](https://www.eclipse.org/downloads/)
   - [Visual Studio Code](https://code.visualstudio.com/) with Java extensions

### Optional Tools

- **Git**: Version control (if not already installed)
- **Postman** or **Insomnia**: REST API testing
- **pgAdmin** or **DBeaver**: PostgreSQL database management

---

## Local Development Setup

There are two approaches to set up the backend locally: using Docker Compose (recommended) or running PostgreSQL and the application separately.

### Option 1: Docker Compose (Recommended)

This is the easiest way to get started with all dependencies configured automatically.

1. **Clone the repository** (if not already done):
   ```bash
   git clone <repository-url>
   cd carddemo-modernized
   ```

2. **Navigate to the project root** and start all services:
   ```bash
   docker-compose up
   ```

   This command will:
   - Start PostgreSQL 16.6 on port 5432
   - Build and start the Spring Boot backend on port 8080
   - Start the React frontend (if configured)
   - Set up proper networking between services

3. **Verify the application is running**:
   - Backend health check: http://localhost:8080/api/health
   - Swagger UI: http://localhost:8080/api/swagger-ui.html

4. **Stop services**:
   ```bash
   docker-compose down
   ```

### Option 2: Local PostgreSQL + Maven

If you prefer to run PostgreSQL locally or use an existing instance:

1. **Start PostgreSQL**:

   Using Docker:
   ```bash
   docker run -d \
     --name carddemo-postgres \
     -p 5432:5432 \
     -e POSTGRES_DB=carddemo \
     -e POSTGRES_USER=carddemo_user \
     -e POSTGRES_PASSWORD=carddemo_password \
     postgres:16.6-alpine
   ```

   Or use your local PostgreSQL installation and create the database:
   ```sql
   CREATE DATABASE carddemo;
   CREATE USER carddemo_user WITH PASSWORD 'carddemo_password';
   GRANT ALL PRIVILEGES ON DATABASE carddemo TO carddemo_user;
   ```

2. **Navigate to the backend directory**:
   ```bash
   cd backend
   ```

3. **Build the application**:
   ```bash
   mvn clean install
   ```

   This will:
   - Compile Java source code
   - Run unit tests
   - Package the application as a JAR file

4. **Run the application**:
   ```bash
   mvn spring-boot:run
   ```

   Or run the packaged JAR:
   ```bash
   java -jar target/carddemo-backend.jar
   ```

   By default, the application uses the `dev` profile. To use a different profile:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=test
   # or
   java -jar target/carddemo-backend.jar --spring.profiles.active=prod
   ```

5. **Verify the application**:
   - Backend health: http://localhost:8080/api/health
   - Swagger UI: http://localhost:8080/api/swagger-ui.html
   - Actuator endpoints: http://localhost:8080/actuator

### Development Mode Features

When running in development mode (application-dev.yml):
- **Hot Reload**: Use Spring Boot DevTools for automatic restart on code changes
- **SQL Logging**: All SQL queries are logged to the console
- **Debug Logging**: Enhanced logging for troubleshooting
- **H2 Console**: Optional in-memory database for quick testing

---

## Database Configuration

The backend uses **PostgreSQL 16.x** as the relational database, replacing the mainframe's VSAM file system.

### Database Schema

The database consists of **11 tables** that replace VSAM datasets:

| Table Name | VSAM Dataset | COBOL Copybook | Records (Est) | Purpose |
|------------|--------------|----------------|---------------|---------|
| account | ACCTFILE | CVACT01Y.cpy | 50,000 | Account master data |
| card | CARDFILE | CVACT02Y.cpy | 100,000 | Credit card data |
| customer | CUSTFILE | CVCUS01Y.cpy | 50,000 | Customer information |
| transaction | TRANSACT | CVTRA05Y.cpy | 1,000,000 | Transaction records |
| daily_transaction | DALYTRAN | CVTRA06Y.cpy | 10,000 | Daily transactions |
| card_account_xref | XREFFILE | CVACT03Y.cpy | 100,000 | Card-account cross-reference |
| transaction_type | TRANTYPE | CVTRA03Y.cpy | 100 | Transaction type codes |
| transaction_category | TRANCATG | CVTRA04Y.cpy | 50 | Transaction categories |
| disclosure_group | DISCGRP | CVTRA02Y.cpy | 10 | Disclosure groups |
| transaction_category_balance | TCATBAL | CVTRA01Y.cpy | 100 | Category balances |
| user_security | USRSEC | CSUSR01Y.cpy | 100 | User authentication |

### Flyway Database Migrations

The database schema is managed using **Flyway** migrations located in `src/main/resources/db/migration/`:

1. **V1__create_account_table.sql**: Creates the account table with all COBOL copybook fields
2. **V2__create_card_table.sql**: Creates the card table with foreign key to account
3. **V3__create_customer_table.sql**: Creates the customer table
4. **V4__create_transaction_tables.sql**: Creates transaction and daily_transaction tables
5. **V5__create_reference_tables.sql**: Creates reference data tables
6. **V6__create_indexes.sql**: Creates indexes matching VSAM key access patterns
7. **V7__insert_initial_data.sql**: Inserts initial reference data

### Migration Execution

Flyway migrations run automatically on application startup. The migration history is tracked in the `flyway_schema_history` table.

**Manual migration execution**:
```bash
mvn flyway:migrate
```

**Check migration status**:
```bash
mvn flyway:info
```

**Clean database (development only)**:
```bash
mvn flyway:clean
```

### Index Strategy

Indexes are designed to replicate VSAM primary and alternate key performance:

- **Primary Keys**: B-tree indexes created automatically on primary key constraints
- **Foreign Keys**: Indexed for join performance
- **Composite Indexes**: Multi-column indexes for common query patterns
- **Query Performance**: Sub-10ms for primary key lookups (matching VSAM)

### Database Configuration Properties

Configuration is profile-specific:

**application-dev.yml** (Development):
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/carddemo
    username: carddemo_user
    password: carddemo_password
  jpa:
    show-sql: true
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    baseline-on-migrate: true
```

**application-prod.yml** (Production):
```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  jpa:
    show-sql: false
  flyway:
    enabled: true
```

---

## REST API Endpoints

The backend exposes a comprehensive REST API that replaces the 15 CICS online transaction programs.

### API Documentation

Full interactive API documentation is available via **Swagger UI**:
- **URL**: http://localhost:8080/api/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8080/api/v3/api-docs

### Authentication Endpoints

| Method | Endpoint | Original COBOL | Description |
|--------|----------|----------------|-------------|
| POST | `/api/auth/login` | COSGN00C | User authentication with JWT token generation |
| POST | `/api/auth/logout` | COSGN00C | User logout and token invalidation |
| POST | `/api/auth/refresh` | - | Refresh JWT access token |

**Example Login Request**:
```json
POST /api/auth/login
{
  "userId": "USER0001",
  "password": "PASSWORD"
}
```

**Example Login Response**:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 3600,
  "userType": "USER",
  "userId": "USER0001"
}
```

### Account Management Endpoints

| Method | Endpoint | Original COBOL | Description |
|--------|----------|----------------|-------------|
| GET | `/api/accounts/{id}` | COACTVWC | View account details |
| PUT | `/api/accounts/{id}` | COACTUPC | Update account information |
| POST | `/api/accounts` | COACTUPC | Create new account |
| GET | `/api/accounts` | COACTVWC | List accounts with pagination |

**Example Get Account**:
```bash
GET /api/accounts/123456789
Authorization: Bearer <jwt-token>
```

### Card Management Endpoints

| Method | Endpoint | Original COBOL | Description |
|--------|----------|----------------|-------------|
| GET | `/api/cards` | COCRDLIC | List credit cards with pagination |
| GET | `/api/cards/{cardNumber}` | COCRDSLC | View card details |
| PUT | `/api/cards/{cardNumber}` | COCRDUPC | Update card information |
| POST | `/api/cards` | COCRDUPC | Issue new card |
| DELETE | `/api/cards/{cardNumber}` | COCRDUPC | Deactivate card |

### Transaction Endpoints

| Method | Endpoint | Original COBOL | Description |
|--------|----------|----------------|-------------|
| GET | `/api/transactions` | COTRN00C | List transactions with date filtering |
| GET | `/api/transactions/{id}` | COTRN01C | View transaction details |
| POST | `/api/transactions` | COTRN02C | Post new transaction |
| GET | `/api/transactions/card/{cardNumber}` | COTRN00C | List transactions by card |

**Example Post Transaction**:
```json
POST /api/transactions
{
  "cardNumber": "4111111111111111",
  "transactionTypeCode": "01",
  "transactionCategoryCode": 5010,
  "transactionAmount": 125.50,
  "merchantId": "MERCH001",
  "merchantName": "Sample Store",
  "transactionDescription": "Purchase"
}
```

### Billing Endpoints

| Method | Endpoint | Original COBOL | Description |
|--------|----------|----------------|-------------|
| GET | `/api/billing/{accountId}` | COBIL00C | Get billing information |
| POST | `/api/billing/generate` | COBIL00C | Generate bill statement |

### Report Endpoints

| Method | Endpoint | Original COBOL | Description |
|--------|----------|----------------|-------------|
| GET | `/api/reports/menu` | CORPT00C | Get report options |
| POST | `/api/reports/generate` | CORPT00C | Generate custom report |

### User Management Endpoints

| Method | Endpoint | Original COBOL | Description |
|--------|----------|----------------|-------------|
| GET | `/api/users` | COUSR00C | List all users |
| GET | `/api/users/{userId}` | COUSR00C | View user details |
| POST | `/api/users` | COUSR01C | Create new user |
| PUT | `/api/users/{userId}` | COUSR02C | Update user information |
| DELETE | `/api/users/{userId}` | COUSR03C | Delete user |

### Health and Monitoring Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Application health status |
| GET | `/actuator/health` | Detailed health information |
| GET | `/actuator/metrics` | Application metrics |
| GET | `/actuator/info` | Application information |

### Error Response Format

All API errors follow a consistent format:

```json
{
  "timestamp": "2025-05-20T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid account number format",
  "path": "/api/accounts/INVALID"
}
```

---

## Spring Batch Jobs

The backend includes **Spring Batch** jobs that replace the 28 JCL batch jobs from the mainframe.

### Batch Job Configuration

Four main batch jobs handle different processing requirements:

#### 1. Account Processing Job
**Original JCL**: CBACTJ01.jcl, CBACTJ02.jcl, CBACTJ03.jcl, CBACTJ04.jcl  
**Original COBOL**: CBACT01C.cbl, CBACT02C.cbl, CBACT03C.cbl, CBACT04C.cbl

**Functions**:
- Daily account validation
- Interest calculation (monthly)
- Credit limit review (weekly)
- Account expiration processing (daily)

**Schedule**: Configured via cron expression
```yaml
spring.batch.job.accountProcessing.cron: 0 0 2 * * ?  # Daily at 2:00 AM
```

#### 2. Transaction Processing Job
**Original JCL**: CBTRNJ01.jcl, CBTRNJ02.jcl, CBTRNJ03.jcl  
**Original COBOL**: CBTRN01C.cbl, CBTRN02C.cbl, CBTRN03C.cbl

**Functions**:
- Transaction validation (daily 1:00 AM)
- Transaction posting (daily 1:30 AM)
- Category summarization (daily 4:00 AM)

**Chunk Processing**: Processes 1000 records per chunk for optimal performance

#### 3. Customer Validation Job
**Original JCL**: CBCUSJ01.jcl  
**Original COBOL**: CBCUS01C.cbl

**Functions**:
- Customer data validation
- Address verification
- Duplicate detection

**Schedule**: Weekly

#### 4. Statement Generation Job
**Original JCL**: DALYREJS.jcl  
**Original COBOL**: CBSTM03A.cbl, CBSTM03B.cbl

**Functions**:
- Monthly statement generation
- Bill formatting
- PDF generation (if configured)

**Schedule**: Monthly on the 1st day

### Triggering Batch Jobs

**Automatic (Scheduled)**:
Jobs run automatically based on configured schedules in application.yml

**Manual (REST API)**:
```bash
POST /api/batch/jobs/{jobName}/run
Authorization: Bearer <admin-jwt-token>
```

Example:
```bash
curl -X POST http://localhost:8080/api/batch/jobs/accountProcessingJob/run \
  -H "Authorization: Bearer <token>"
```

### Monitoring Batch Jobs

**Spring Boot Actuator Endpoints**:
```bash
# List all jobs
GET /actuator/batch/jobs

# Get job execution details
GET /actuator/batch/jobs/{jobName}/executions

# Get specific execution
GET /actuator/batch/jobs/{jobName}/executions/{executionId}
```

**Database Tables**:
- `batch_job_instance`: Job instances
- `batch_job_execution`: Job execution history
- `batch_step_execution`: Step execution details

### Batch Processing Requirements

- **Processing Window**: All batch jobs must complete within 4-hour overnight window (02:00-06:00)
- **Checkpoint/Restart**: Spring Batch provides automatic checkpoint and restart capabilities
- **Error Handling**: Failed records are logged for manual review without stopping the entire job
- **Performance**: Chunk sizes optimized for throughput (1000-5000 records per chunk)

---

## Testing

The backend has comprehensive test coverage using modern Java testing frameworks.

### Test Frameworks

- **JUnit 5** (5.10.x): Unit testing framework
- **Mockito** (5.14.2): Mocking framework for unit tests
- **Spring Boot Test**: Integration testing utilities
- **Testcontainers** (1.20.4): Docker-based integration testing
- **REST Assured** (5.5.0): REST API testing
- **AssertJ**: Fluent assertions

### Running Tests

**Run all tests**:
```bash
mvn test
```

**Run integration tests**:
```bash
mvn verify
```

**Run specific test class**:
```bash
mvn test -Dtest=AccountServiceTest
```

**Run specific test method**:
```bash
mvn test -Dtest=AccountServiceTest#testGetAccount
```

**Run tests with coverage report**:
```bash
mvn clean test jacoco:report
```

Coverage report: `target/site/jacoco/index.html`

### Test Structure

```
src/test/java/com/carddemo/
├── controller/           # Controller layer tests
│   ├── AccountControllerTest.java
│   ├── CardControllerTest.java
│   └── TransactionControllerTest.java
│
├── service/             # Service layer tests
│   ├── AccountServiceTest.java
│   ├── CardServiceTest.java
│   └── TransactionServiceTest.java
│
├── repository/          # Repository layer tests
│   ├── AccountRepositoryTest.java
│   └── TransactionRepositoryTest.java
│
├── batch/              # Batch job tests
│   ├── AccountProcessingJobTest.java
│   └── TransactionProcessingJobTest.java
│
└── integration/        # End-to-end integration tests
    ├── AccountIntegrationTest.java
    └── TransactionIntegrationTest.java
```

### Test Coverage Requirements

- **Minimum Code Coverage**: 80% for service layer
- **Critical Business Logic**: 100% coverage for financial calculations
- **Controllers**: Integration tests for all endpoints
- **Repositories**: Tests with Testcontainers PostgreSQL

### Example Unit Test

```java
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    
    @Mock
    private AccountRepository accountRepository;
    
    @InjectMocks
    private AccountService accountService;
    
    @Test
    void testGetAccount() {
        // Given
        Long accountId = 123456789L;
        Account account = new Account();
        account.setAcctId(accountId);
        when(accountRepository.findById(accountId))
            .thenReturn(Optional.of(account));
        
        // When
        AccountDto result = accountService.getAccount(accountId);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.getAcctId()).isEqualTo(accountId);
    }
}
```

### Example Integration Test

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = 
        new PostgreSQLContainer<>("postgres:16.6-alpine");
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Test
    void testGetAccount() {
        ResponseEntity<AccountDto> response = restTemplate
            .withBasicAuth("admin", "password")
            .getForEntity("/api/accounts/123456789", AccountDto.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }
}
```

---

## Build and Deployment

### Building the Application

**Build JAR file**:
```bash
mvn clean package
```

Output: `target/carddemo-backend.jar`

**Build without running tests** (not recommended):
```bash
mvn clean package -DskipTests
```

**Build Docker image**:
```bash
docker build -t carddemo-backend:latest .
```

**Build with specific tag**:
```bash
docker build -t carddemo-backend:1.0.0 .
```

### Running Locally

**Run JAR file**:
```bash
java -jar target/carddemo-backend.jar
```

**Run with specific profile**:
```bash
java -jar target/carddemo-backend.jar --spring.profiles.active=prod
```

**Run Docker container**:
```bash
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/carddemo \
  -e DATABASE_USERNAME=carddemo_user \
  -e DATABASE_PASSWORD=carddemo_password \
  carddemo-backend:latest
```

### Kubernetes Deployment

**Apply Kubernetes manifests**:
```bash
kubectl apply -f infrastructure/kubernetes/namespace.yaml
kubectl apply -f infrastructure/kubernetes/configmap.yaml
kubectl apply -f infrastructure/kubernetes/secrets.yaml
kubectl apply -f infrastructure/kubernetes/backend/
```

**Check deployment status**:
```bash
kubectl get pods -n carddemo
kubectl get services -n carddemo
```

**View logs**:
```bash
kubectl logs -f deployment/carddemo-backend -n carddemo
```

**Scale deployment**:
```bash
kubectl scale deployment carddemo-backend --replicas=5 -n carddemo
```

### Kubernetes Resources

The backend deployment includes:

- **Deployment**: 3 replicas by default
- **Service**: ClusterIP service on port 8080
- **HorizontalPodAutoscaler**: Auto-scaling based on CPU (70% threshold, 3-10 replicas)
- **Ingress**: External access to the API
- **ConfigMap**: Non-sensitive configuration
- **Secret**: Database credentials and JWT keys

### CI/CD Pipeline

The project includes GitHub Actions workflows:

- **backend-ci.yml**: Builds, tests, and pushes Docker images
- **deploy.yml**: Deploys to Kubernetes cluster

**Trigger workflow**:
```bash
git push origin main
```

---

## Performance Requirements

The backend must meet the following **non-negotiable** performance requirements to match or exceed mainframe performance:

### Transaction Response Time

- **Requirement**: < 200ms (95th percentile) for card authorization requests
- **Measurement**: Spring Boot Actuator `/actuator/metrics/http.server.requests`
- **Optimization**:
  - Database query optimization with proper indexes
  - HikariCP connection pool tuning (minimum 5, maximum 20)
  - JVM heap tuning for garbage collection

**Monitoring**:
```bash
curl http://localhost:8080/actuator/metrics/http.server.requests
```

### Transaction Throughput

- **Requirement**: 10,000 transactions per second (TPS) at peak load
- **Measurement**: Load testing with JMeter or Gatling
- **Scaling**: Horizontal pod autoscaling in Kubernetes

### Batch Processing Windows

- **Requirement**: Complete all batch jobs within 4-hour overnight window (02:00-06:00)
- **Current Processing**:
  - Account processing: ~1 hour (50,000 accounts)
  - Transaction processing: ~1.5 hours (1,000,000 transactions)
  - Statement generation: ~30 minutes
- **Optimization**: Spring Batch parallel processing with chunk size tuning

### Database Query Performance

- **Requirement**: Sub-10ms for primary key lookups (matching VSAM key access)
- **Measurement**: PostgreSQL EXPLAIN ANALYZE
- **Validation**: All indexes properly defined and utilized

**Example Performance Query**:
```sql
EXPLAIN ANALYZE 
SELECT * FROM account WHERE acct_id = 123456789;
```

Expected: Execution time < 10ms

### Memory and CPU Requirements

**Per Pod**:
- **Memory**: 1GB minimum, 2GB recommended, 4GB limit
- **CPU**: 0.5 cores minimum, 1 core recommended, 2 cores limit

**JVM Tuning**:
```bash
-Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

---

## Security

The backend implements enterprise-grade security replacing the mainframe's RACF security system.

### Authentication

**JWT-based Authentication**:
- JSON Web Tokens (JWT) for stateless authentication
- Token expiration: 1 hour (configurable)
- Refresh token mechanism for session extension
- BCrypt password hashing (replacing plain-text COBOL passwords)

### Authorization

**Role-Based Access Control (RBAC)**:

| Role | Permissions | Original RACF Profile |
|------|-------------|----------------------|
| ROLE_ADMIN | Full system access, user management | ADMIN profile |
| ROLE_USER | Account/card operations, transactions | USER profile |
| ROLE_OPERATOR | Read-only access, reports | OPERATOR profile |

### Security Configuration

**Spring Security Features**:
- JWT authentication filter for all API requests
- Method-level security with `@PreAuthorize` annotations
- CORS configuration for frontend integration
- HTTPS required in production
- CSRF protection disabled for REST API (using JWT instead)

**Example Secured Endpoint**:
```java
@PreAuthorize("hasRole('ADMIN')")
@DeleteMapping("/users/{userId}")
public ResponseEntity<?> deleteUser(@PathVariable String userId) {
    // Implementation
}
```

### Password Security

**Password Requirements**:
- Minimum 8 characters
- Must contain uppercase and lowercase letters
- Must contain at least one number
- BCrypt hashing with strength 12

**Password Change**:
```bash
POST /api/users/{userId}/password
{
  "currentPassword": "oldpass",
  "newPassword": "NewPass123"
}
```

### Audit Logging

**Security Events Logged**:
- User login/logout
- Failed authentication attempts
- Authorization failures
- Data access and modifications
- Administrative actions

**Log Format**: JSON structured logging to stdout

### HTTPS Configuration

**Production Requirement**: All API traffic must use HTTPS

**application-prod.yml**:
```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${KEYSTORE_PASSWORD}
    key-store-type: PKCS12
```

---

## Monitoring and Observability

The backend includes comprehensive monitoring capabilities through Spring Boot Actuator.

### Spring Boot Actuator

**Enabled Endpoints** (application.yml):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,batch
  endpoint:
    health:
      show-details: when-authorized
```

### Health Checks

**Basic Health Check**:
```bash
GET /api/health
```

**Detailed Health Information**:
```bash
GET /actuator/health
```

**Response Example**:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    }
  }
}
```

### Metrics

**Available Metrics**:
```bash
# List all metrics
GET /actuator/metrics

# Specific metric
GET /actuator/metrics/jvm.memory.used
GET /actuator/metrics/http.server.requests
GET /actuator/metrics/jdbc.connections.active
```

**Prometheus Metrics**:
```bash
GET /actuator/prometheus
```

### Application Logs

**Log Configuration**:
- **Development**: Console output, DEBUG level
- **Production**: JSON format to stdout, INFO level
- **Log Aggregation**: Compatible with ELK stack (Elasticsearch, Logstash, Kibana)

**Log Example**:
```json
{
  "timestamp": "2025-05-20T10:30:00.123Z",
  "level": "INFO",
  "logger": "com.carddemo.service.AccountService",
  "message": "Account retrieved successfully",
  "accountId": 123456789,
  "userId": "USER0001"
}
```

### Monitoring Integration

**Prometheus + Grafana**:
1. Expose Prometheus endpoint: `/actuator/prometheus`
2. Configure Prometheus to scrape metrics
3. Import Grafana dashboard for Spring Boot applications

**Example Prometheus Configuration**:
```yaml
scrape_configs:
  - job_name: 'carddemo-backend'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

---

## Troubleshooting

### Common Issues and Solutions

#### Port 8080 Already in Use

**Problem**: Application fails to start with "Port 8080 is already in use"

**Solution**:
```bash
# Find process using port 8080
lsof -i :8080

# Kill the process
kill -9 <PID>

# Or change the port in application.yml
server.port: 8081
```

#### Database Connection Failed

**Problem**: Application cannot connect to PostgreSQL

**Solution**:
1. Verify PostgreSQL is running:
   ```bash
   docker ps | grep postgres
   ```

2. Check database credentials in application.yml

3. Test database connection:
   ```bash
   psql -h localhost -p 5432 -U carddemo_user -d carddemo
   ```

4. Check database logs:
   ```bash
   docker logs carddemo-postgres
   ```

#### Flyway Migration Failed

**Problem**: Flyway migration errors on startup

**Solution**:
1. Check migration script syntax
2. View Flyway history:
   ```sql
   SELECT * FROM flyway_schema_history;
   ```

3. Clean and re-migrate (development only):
   ```bash
   mvn flyway:clean flyway:migrate
   ```

4. Skip failed migration (not recommended):
   ```yaml
   spring.flyway.baseline-on-migrate: true
   ```

#### Tests Failing

**Problem**: Tests fail with Testcontainers errors

**Solution**:
1. Ensure Docker is running:
   ```bash
   docker info
   ```

2. Check Docker permissions (Linux):
   ```bash
   sudo usermod -aG docker $USER
   ```

3. Increase Docker resources (memory/CPU)

4. Skip integration tests if needed:
   ```bash
   mvn test -DexcludeGroups=integration
   ```

#### Out of Memory Errors

**Problem**: Application crashes with OutOfMemoryError

**Solution**:
1. Increase JVM heap size:
   ```bash
   java -Xms1g -Xmx2g -jar carddemo-backend.jar
   ```

2. Analyze heap dump:
   ```bash
   jmap -dump:format=b,file=heap.bin <PID>
   ```

3. Check for memory leaks in batch jobs (large datasets)

#### Slow Query Performance

**Problem**: Database queries taking longer than expected

**Solution**:
1. Analyze query execution plan:
   ```sql
   EXPLAIN ANALYZE SELECT * FROM transaction WHERE card_num = '4111111111111111';
   ```

2. Check missing indexes:
   ```sql
   SELECT * FROM pg_stat_user_tables WHERE idx_scan = 0;
   ```

3. Optimize connection pool:
   ```yaml
   spring.datasource.hikari.maximum-pool-size: 20
   ```

### Log Analysis

**Enable SQL Logging** (development):
```yaml
spring.jpa.show-sql: true
logging.level.org.hibernate.SQL: DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder: TRACE
```

**Check Application Logs**:
```bash
# Docker
docker logs -f carddemo-backend

# Kubernetes
kubectl logs -f deployment/carddemo-backend -n carddemo

# Local file
tail -f logs/application.log
```

### Getting Help

If issues persist:
1. Check the [GitHub Issues](https://github.com/your-org/carddemo/issues)
2. Review Spring Boot documentation
3. Contact the development team

---

## Contributing

We welcome contributions to improve the CardDemo backend application!

### Development Guidelines

**Code Style**:
- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- Use Lombok annotations to reduce boilerplate code
- Maximum method length: 50 lines
- Maximum class length: 500 lines

**Naming Conventions**:
- Classes: PascalCase (`AccountService`)
- Methods: camelCase (`getAccount()`)
- Constants: UPPER_SNAKE_CASE (`MAX_RETRY_ATTEMPTS`)
- Packages: lowercase (`com.carddemo.service`)

**Documentation**:
- Write JavaDoc comments for all public methods
- Document complex business logic with inline comments
- Update README.md for new features

**Testing Requirements**:
- Write JUnit tests for all business logic (minimum 80% coverage)
- Include integration tests for new endpoints
- Test edge cases and error conditions
- Do not commit code with failing tests

### Contribution Workflow

1. **Fork the repository**

2. **Create a feature branch**:
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Make your changes**:
   - Write clean, well-documented code
   - Add appropriate tests
   - Ensure all tests pass: `mvn test`

4. **Commit your changes**:
   ```bash
   git add .
   git commit -m "Add feature: your feature description"
   ```

5. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

6. **Submit a Pull Request**:
   - Provide a clear description of changes
   - Reference any related issues
   - Ensure CI/CD pipeline passes

### Code Review Process

All pull requests require:
- At least one approval from a maintainer
- All CI/CD checks passing
- Code coverage not decreasing
- Documentation updated (if applicable)

---

## Migration Notes

This backend preserves the **exact business logic** from the mainframe COBOL application while modernizing the technology stack.

### COBOL-to-Java Conversion Highlights

**Data Type Conversions**:
- COBOL `PIC 9(11)` → Java `Long`
- COBOL `PIC S9(10)V99 COMP-3` → Java `BigDecimal` with scale 2
- COBOL `PIC X(50)` → Java `String`
- COBOL `PIC 9(8)` (date) → Java `LocalDate`

**Precision Preservation**:
- All financial calculations use `BigDecimal` to maintain COMP-3 precision
- Rounding modes match COBOL behavior
- No floating-point arithmetic for currency amounts

**Program Conversions**:
- 26 COBOL programs → 9 REST controllers + 8 service classes
- 11 VSAM datasets → 11 PostgreSQL tables
- 28 JCL jobs → 4 Spring Batch jobs
- RACF security → Spring Security with JWT

**Business Logic Preservation**:
- Account management algorithms identical to COBOL
- Transaction processing rules unchanged
- Balance calculations produce identical results
- All field validations preserved

### Known Differences from Mainframe

**User Interface**:
- 3270 terminal screens replaced with REST API
- Frontend now uses React SPA (see frontend README)

**Deployment**:
- Containerized deployment instead of mainframe LPAR
- Horizontal scaling with Kubernetes

**Performance**:
- Response times match or exceed mainframe (sub-200ms)
- Batch processing optimized with Spring Batch parallel processing

---

## Links

### Related Documentation
- **Root README**: [../README.md](../README.md)
- **Frontend README**: [../frontend/README.md](../frontend/README.md)
- **Infrastructure README**: [../infrastructure/README.md](../infrastructure/README.md)

### Local Resources (when running)
- **API Documentation**: http://localhost:8080/api/swagger-ui.html
- **Health Check**: http://localhost:8080/api/health
- **Actuator Endpoints**: http://localhost:8080/actuator
- **Prometheus Metrics**: http://localhost:8080/actuator/prometheus

### External Resources
- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/3.4.5/reference/html/)
- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/)
- [Spring Batch Documentation](https://docs.spring.io/spring-batch/docs/current/reference/html/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/16/)

---

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](../LICENSE) file for details.

---

**For questions or support, please open an issue in the GitHub repository.**
