# CardDemo Backend Service

## Table of Contents

- [Project Overview](#project-overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Database Setup](#database-setup)
- [Configuration Profiles](#configuration-profiles)
- [Building the Application](#building-the-application)
- [Running the Application](#running-the-application)
- [API Documentation](#api-documentation)
- [Batch Jobs](#batch-jobs)
- [Testing](#testing)
- [Docker Build](#docker-build)
- [Key Design Patterns](#key-design-patterns)
- [COBOL-to-Java Transformation Notes](#cobol-to-java-transformation-notes)
- [Performance Targets](#performance-targets)
- [Security](#security)
- [Troubleshooting](#troubleshooting)
- [Additional Documentation](#additional-documentation)
- [Contributing](#contributing)
- [License](#license)

## Project Overview

The **CardDemo Backend Service** is a modern Java Spring Boot application that represents a comprehensive technology migration of the legacy CardDemo mainframe credit card management system from COBOL/CICS/VSAM to a cloud-native architecture.

### Purpose

This backend service is the result of a complete transformation that:
- Converts 28 COBOL programs to Java Spring Boot microservices
- Migrates VSAM KSDS files to PostgreSQL relational database
- Transforms CICS transactions to RESTful API endpoints
- Modernizes batch processing from JCL to Spring Batch
- Preserves 100% functional equivalence with the original mainframe application

### Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Java (OpenJDK) | 21 LTS |
| Framework | Spring Boot | 3.2.1 |
| Data Access | Spring Data JPA | 3.2.1 |
| Batch Processing | Spring Batch | 5.1.1 |
| Security | Spring Security | 6.2.1 |
| Database | PostgreSQL | 15+ |
| Cache & Session | Redis | 7.x |
| Build Tool | Apache Maven | 3.9.x |
| Container | Docker | 24.x |
| API Documentation | SpringDoc OpenAPI | 2.3.0 |

### Architecture

The application follows a layered microservices architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                    REST API Controllers                      │
│  AuthenticationController, AccountController, etc.          │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                     Service Layer                            │
│  Business Logic from COBOL Programs                         │
│  AuthenticationService, AccountViewService, etc.            │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              Data Access Layer (Repositories)                │
│  Spring Data JPA Repositories                               │
│  CustomerRepository, AccountRepository, etc.                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   PostgreSQL Database                        │
│  Relational tables with indexes and constraints             │
└─────────────────────────────────────────────────────────────┘
```

## Prerequisites

Before you begin, ensure you have the following installed:

### Required Software

1. **JDK 21 (LTS)**
   - OpenJDK 21 or Eclipse Temurin 21
   - Download: https://adoptium.net/
   - Verify: `java -version` (should show version 21.x.x)

2. **Apache Maven 3.9.x or higher**
   - Download: https://maven.apache.org/download.cgi
   - Verify: `mvn -version` (should show version 3.9.x or higher)

3. **PostgreSQL 15+ Database Server**
   - Download: https://www.postgresql.org/download/
   - Verify: `psql --version` (should show version 15.x or higher)

4. **Redis 7.x Server**
   - Download: https://redis.io/download
   - For macOS: `brew install redis`
   - For Linux: `sudo apt-get install redis-server`
   - Verify: `redis-cli --version`

### Optional Software

5. **Docker Desktop** (for containerized development)
   - Download: https://www.docker.com/products/docker-desktop
   - Required for running the application in containers
   - Verify: `docker --version` and `docker-compose --version`

6. **IDE (Choose one)**
   - IntelliJ IDEA Community Edition (recommended)
   - Eclipse IDE for Java Developers
   - Visual Studio Code with Java Extension Pack

### System Requirements

- **CPU**: 2+ cores recommended
- **Memory**: 8 GB RAM minimum, 16 GB recommended
- **Disk Space**: 5 GB free space for dependencies and build artifacts
- **OS**: Windows 10+, macOS 10.15+, or Linux (Ubuntu 20.04+)

## Quick Start

Get the CardDemo backend service running in minutes:

### 1. Clone and Navigate

```bash
# Clone the repository (if not already done)
git clone <repository-url>
cd backend
```

### 2. Build the Project

```bash
# Clean and build with tests
mvn clean install

# Or skip tests for faster build
mvn clean install -DskipTests
```

### 3. Start Required Services

#### Option A: Using Docker Compose (Recommended)

```bash
# From the repository root directory
docker-compose up postgres redis

# Wait for services to be ready
# PostgreSQL will be available on localhost:5432
# Redis will be available on localhost:6379
```

#### Option B: Local Installation

```bash
# Start PostgreSQL
sudo service postgresql start  # Linux
brew services start postgresql # macOS

# Start Redis
sudo service redis-server start  # Linux
brew services start redis        # macOS
```

### 4. Run the Application

```bash
# Run with development profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Application will start on http://localhost:8080
```

### 5. Verify Installation

Open your browser and navigate to:
- Health Check: http://localhost:8080/actuator/health
- API Documentation: http://localhost:8080/swagger-ui.html

Expected health check response:
```json
{
  "status": "UP"
}
```

### Using Docker Compose for Full Stack

```bash
# From the repository root directory
docker-compose up

# This starts:
# - PostgreSQL database
# - Redis cache
# - Backend service
# - Frontend application (if available)
```

## Project Structure

The backend follows Spring Boot best practices with clear separation of concerns:

```
backend/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── carddemo/
│   │   │           ├── CardDemoApplication.java          # Main application entry point
│   │   │           ├── config/                           # Configuration classes
│   │   │           │   ├── SecurityConfig.java           # Spring Security configuration
│   │   │           │   ├── DatabaseConfig.java           # Database connection settings
│   │   │           │   ├── RedisConfig.java             # Redis session configuration
│   │   │           │   ├── BatchConfig.java             # Spring Batch configuration
│   │   │           │   └── SwaggerConfig.java           # API documentation config
│   │   │           ├── controller/                       # REST API Controllers
│   │   │           │   ├── AuthenticationController.java # Login/logout endpoints
│   │   │           │   ├── MenuController.java          # Menu navigation
│   │   │           │   ├── AccountController.java       # Account CRUD operations
│   │   │           │   ├── CardController.java          # Card management
│   │   │           │   ├── TransactionController.java   # Transaction operations
│   │   │           │   ├── BillPaymentController.java   # Bill payment processing
│   │   │           │   ├── ReportController.java        # Report generation
│   │   │           │   ├── AdminController.java         # Admin functions
│   │   │           │   └── UserController.java          # User management
│   │   │           ├── service/                         # Business Logic Layer
│   │   │           │   ├── AuthenticationService.java   # From COSGN00C.cbl
│   │   │           │   ├── MenuNavigationService.java   # From COMEN01C.cbl
│   │   │           │   ├── AccountViewService.java      # From COACTVWC.cbl
│   │   │           │   ├── AccountUpdateService.java    # From COACTUPC.cbl
│   │   │           │   ├── AccountCreationService.java  # From COACTADD.cbl
│   │   │           │   ├── CardListService.java         # From COCRDLIC.cbl
│   │   │           │   ├── CardDetailService.java       # From COCRDSLC.cbl
│   │   │           │   ├── CardUpdateService.java       # From COCRDUPC.cbl
│   │   │           │   ├── TransactionListService.java  # From COTRN00C.cbl
│   │   │           │   ├── TransactionCategoryService.java  # From COTRN01C.cbl
│   │   │           │   ├── TransactionCreationService.java  # From COTRN02C.cbl
│   │   │           │   ├── BillPaymentService.java      # From COBIL00C.cbl
│   │   │           │   ├── ReportMenuService.java       # From CORPT00C.cbl
│   │   │           │   ├── AdminService.java            # From COADM01C.cbl
│   │   │           │   ├── UserManagementService.java   # From COUSR00C.cbl
│   │   │           │   └── UserProfileService.java      # From COUSR01C.cbl
│   │   │           ├── entity/                          # JPA Entities (from VSAM files)
│   │   │           │   ├── Customer.java                # From CUSTDAT VSAM
│   │   │           │   ├── Account.java                 # From ACCTDAT VSAM
│   │   │           │   ├── AccountGroup.java            # From CVACT02Y.cpy
│   │   │           │   ├── Card.java                    # From CARDDAT VSAM
│   │   │           │   ├── Transaction.java             # From TRANSACT VSAM
│   │   │           │   ├── TransactionDetail.java       # From CVTRA05Y.cpy
│   │   │           │   ├── UserSecurity.java            # From USRSEC VSAM
│   │   │           │   ├── AccountXref.java             # From XREF file
│   │   │           │   ├── CardXref.java                # From CXACAIX file
│   │   │           │   ├── TransactionType.java         # Reference data
│   │   │           │   ├── TransactionCategory.java     # Reference data
│   │   │           │   └── AccountBalance.java          # Balance tracking
│   │   │           ├── repository/                      # Data Access Layer
│   │   │           │   ├── CustomerRepository.java      # JPA repository for customers
│   │   │           │   ├── AccountRepository.java       # Account data access
│   │   │           │   ├── CardRepository.java          # Card data access
│   │   │           │   ├── TransactionRepository.java   # Transaction queries
│   │   │           │   ├── UserSecurityRepository.java  # User authentication
│   │   │           │   ├── AccountXrefRepository.java   # Cross-reference queries
│   │   │           │   ├── TransactionTypeRepository.java    # Reference data
│   │   │           │   └── TransactionCategoryRepository.java # Reference data
│   │   │           ├── dto/                             # Data Transfer Objects
│   │   │           │   ├── request/                     # Request DTOs
│   │   │           │   │   ├── LoginRequest.java        # Login credentials
│   │   │           │   │   ├── AccountUpdateRequest.java    # Account update data
│   │   │           │   │   ├── CardUpdateRequest.java   # Card update data
│   │   │           │   │   ├── TransactionRequest.java  # Transaction creation
│   │   │           │   │   └── BillPaymentRequest.java  # Bill payment data
│   │   │           │   └── response/                    # Response DTOs
│   │   │           │       ├── LoginResponse.java       # Login result with JWT
│   │   │           │       ├── AccountViewResponse.java # Account details
│   │   │           │       ├── CardListResponse.java    # Card list with pagination
│   │   │           │       ├── TransactionListResponse.java  # Transaction list
│   │   │           │       └── BillPaymentResponse.java # Payment confirmation
│   │   │           ├── batch/                           # Spring Batch Jobs
│   │   │           │   ├── job/                         # Job configurations
│   │   │           │   │   ├── AccountDataLoadJob.java  # From CBACT01C.cbl
│   │   │           │   │   ├── AccountXrefBuildJob.java # From CBACT02C.cbl
│   │   │           │   │   ├── AccountBalanceJob.java   # From CBACT03C.cbl
│   │   │           │   │   ├── InterestCalculationJob.java  # From CBACT04C.cbl
│   │   │           │   │   ├── CustomerDataLoadJob.java # From CBCUS01C.cbl
│   │   │           │   │   ├── TransactionDataLoadJob.java  # From CBTRN01C.cbl
│   │   │           │   │   ├── DailyTransactionProcessingJob.java  # From CBTRN02C.cbl
│   │   │           │   │   ├── TransactionAggregationJob.java  # From CBTRN03C.cbl
│   │   │           │   │   ├── StatementGenerationJob.java  # From CBSTM03A.cbl
│   │   │           │   │   ├── StatementFormattingJob.java  # From CBSTM03B.cbl
│   │   │           │   │   └── CardDataLoadJob.java     # From CBCRD01C.cbl
│   │   │           │   ├── reader/                      # Batch item readers
│   │   │           │   │   ├── CustomerItemReader.java  # Read customer data
│   │   │           │   │   ├── AccountItemReader.java   # Read account data
│   │   │           │   │   ├── TransactionItemReader.java   # Read transactions
│   │   │           │   │   └── CardItemReader.java      # Read card data
│   │   │           │   ├── processor/                   # Batch item processors
│   │   │           │   │   ├── AccountDataProcessor.java    # Process accounts
│   │   │           │   │   ├── InterestCalculationProcessor.java  # Calculate interest
│   │   │           │   │   ├── TransactionAggregationProcessor.java  # Aggregate data
│   │   │           │   │   └── StatementProcessor.java  # Generate statements
│   │   │           │   └── writer/                      # Batch item writers
│   │   │           │       ├── AccountItemWriter.java   # Write account data
│   │   │           │       ├── TransactionItemWriter.java   # Write transactions
│   │   │           │       ├── StatementItemWriter.java # Write statements
│   │   │           │       └── ReportItemWriter.java    # Write reports
│   │   │           ├── security/                        # Security Components
│   │   │           │   ├── JwtTokenProvider.java        # JWT token generation
│   │   │           │   ├── JwtAuthenticationFilter.java # JWT request filter
│   │   │           │   ├── CustomUserDetailsService.java    # User authentication
│   │   │           │   └── SecurityConstants.java       # Security constants
│   │   │           ├── util/                            # Utility Classes
│   │   │           │   ├── DateUtils.java               # Date operations
│   │   │           │   ├── DateUtilityService.java      # CEEDAYS conversion
│   │   │           │   ├── DateConverter.java           # Date format conversion
│   │   │           │   ├── StringUtils.java             # String processing
│   │   │           │   ├── DecimalUtils.java            # BigDecimal precision
│   │   │           │   ├── AttributeUtils.java          # Attribute handling
│   │   │           │   ├── MessageFormatter.java        # Message formatting
│   │   │           │   └── ValidationUtils.java         # Custom validators
│   │   │           ├── exception/                       # Exception Handling
│   │   │           │   ├── GlobalExceptionHandler.java  # Global error handler
│   │   │           │   ├── AccountNotFoundException.java    # Account errors
│   │   │           │   ├── CardNotFoundException.java   # Card errors
│   │   │           │   ├── TransactionException.java    # Transaction errors
│   │   │           │   ├── AuthenticationFailedException.java   # Auth errors
│   │   │           │   └── InsufficientBalanceException.java    # Balance errors
│   │   │           └── constants/                       # Application Constants
│   │   │               ├── MessageConstants.java        # Message codes
│   │   │               ├── ErrorCodes.java              # Error code enums
│   │   │               ├── TransactionTypes.java        # Transaction type enums
│   │   │               └── CardStatus.java              # Card status enums
│   │   └── resources/
│   │       ├── application.yml                  # Base configuration
│   │       ├── application-dev.yml              # Development settings
│   │       ├── application-prod.yml             # Production settings
│   │       ├── db/
│   │       │   └── migration/                   # Flyway database migrations
│   │       │       ├── V1__create_customer_table.sql
│   │       │       ├── V2__create_account_table.sql
│   │       │       ├── V3__create_card_table.sql
│   │       │       ├── V4__create_transaction_table.sql
│   │       │       ├── V5__create_user_security_table.sql
│   │       │       ├── V6__create_xref_tables.sql
│   │       │       ├── V7__create_indexes.sql
│   │       │       ├── V8__create_foreign_keys.sql
│   │       │       └── V9__load_reference_data.sql
│   │       ├── batch/                           # Spring Batch configurations
│   │       │   ├── account-load-job.xml
│   │       │   ├── transaction-processing-job.xml
│   │       │   └── statement-generation-job.xml
│   │       ├── messages/                        # Internationalization
│   │       │   ├── messages.properties
│   │       │   └── errors.properties
│   │       └── static/
│   │           └── api-docs.yaml                # OpenAPI specification
│   └── test/
│       └── java/
│           └── com/
│               └── carddemo/
│                   ├── controller/               # Controller tests
│                   │   ├── AuthenticationControllerTest.java
│                   │   ├── AccountControllerTest.java
│                   │   ├── CardControllerTest.java
│                   │   └── TransactionControllerTest.java
│                   ├── service/                  # Service tests
│                   │   ├── AccountServiceTest.java
│                   │   ├── CardServiceTest.java
│                   │   ├── TransactionServiceTest.java
│                   │   └── BillPaymentServiceTest.java
│                   ├── repository/               # Repository tests
│                   │   ├── CustomerRepositoryTest.java
│                   │   ├── AccountRepositoryTest.java
│                   │   └── TransactionRepositoryTest.java
│                   ├── batch/                    # Batch job tests
│                   │   ├── AccountDataLoadJobTest.java
│                   │   ├── InterestCalculationJobTest.java
│                   │   └── StatementGenerationJobTest.java
│                   └── integration/              # Integration tests
│                       ├── AccountIntegrationTest.java
│                       ├── CardIntegrationTest.java
│                       └── TransactionIntegrationTest.java
├── pom.xml                                      # Maven dependencies
├── Dockerfile                                   # Container image definition
└── README.md                                    # This file
```

## Database Setup

The application uses PostgreSQL 15+ as the primary database, replacing the legacy VSAM files.

### Creating the Database

#### Manual Setup

```bash
# Connect to PostgreSQL
psql -U postgres

# Create database
CREATE DATABASE carddemo;

# Create user (optional, for security)
CREATE USER carddemo WITH PASSWORD 'carddemo123';

# Grant privileges
GRANT ALL PRIVILEGES ON DATABASE carddemo TO carddemo;

# Exit psql
\q
```

#### Using Docker

```bash
# Start PostgreSQL container
docker run --name carddemo-postgres \
  -e POSTGRES_DB=carddemo \
  -e POSTGRES_USER=carddemo \
  -e POSTGRES_PASSWORD=carddemo123 \
  -p 5432:5432 \
  -d postgres:15-alpine

# Verify container is running
docker ps | grep carddemo-postgres
```

### Database Configuration

Configure database connection in `src/main/resources/application-dev.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/carddemo
    username: carddemo
    password: carddemo123
    driver-class-name: org.postgresql.Driver
    
  jpa:
    hibernate:
      ddl-auto: validate  # Flyway handles schema
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
        
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

### Database Migrations

The application uses **Flyway** for database version control. Migrations run automatically on application startup.

Migration files are located in: `src/main/resources/db/migration/`

#### Migration Naming Convention

```
V{version}__{description}.sql

Examples:
V1__create_customer_table.sql
V2__create_account_table.sql
V3__create_card_table.sql
```

#### Manual Migration Execution

```bash
# Run migrations manually
mvn flyway:migrate

# View migration status
mvn flyway:info

# Validate migrations
mvn flyway:validate
```

### VSAM to PostgreSQL Mapping

| Legacy VSAM File | PostgreSQL Table | Primary Key | Notes |
|-----------------|------------------|-------------|-------|
| CUSTDAT KSDS | customer | customer_id | Customer master data |
| ACCTDAT KSDS | account | account_id | Account information with FK to customer |
| CARDDAT KSDS | card | card_id | Card data with FK to account |
| TRANSACT KSDS | transaction | transaction_id | Transaction history |
| USRSEC KSDS | user_security | user_id | User authentication data |
| XREF file | account_xref | composite key | Customer-Account cross-reference |
| CXACAIX file | card_xref | composite key | Card cross-reference |

### Redis Setup

Redis is used for session management and caching:

#### Local Installation

```bash
# Start Redis server
redis-server

# Verify Redis is running
redis-cli ping
# Expected output: PONG
```

#### Docker Installation

```bash
# Start Redis container
docker run --name carddemo-redis \
  -p 6379:6379 \
  -d redis:7-alpine

# Verify connection
docker exec -it carddemo-redis redis-cli ping
```

#### Redis Configuration

Configure in `src/main/resources/application-dev.yml`:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 60000
      
  session:
    store-type: redis
    redis:
      namespace: carddemo:session
    timeout: 86400  # 24 hours
```

## Configuration Profiles

The application supports multiple Spring profiles for different environments.

### Available Profiles

| Profile | Purpose | Configuration File |
|---------|---------|-------------------|
| `dev` | Local development | application-dev.yml |
| `prod` | Production environment | application-prod.yml |
| `test` | Automated testing | application-test.yml (implicit) |

### Activating Profiles

#### Command Line

```bash
# Development profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Production profile
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

#### Environment Variable

```bash
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

#### In application.yml

```yaml
spring:
  profiles:
    active: dev
```

### Profile-Specific Configuration

#### Development Profile (application-dev.yml)

```yaml
server:
  port: 8080
  
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/carddemo
  
  jpa:
    show-sql: true  # Show SQL statements in logs
    
logging:
  level:
    com.carddemo: DEBUG
    org.springframework: INFO
```

#### Production Profile (application-prod.yml)

```yaml
server:
  port: 8080
  
spring:
  datasource:
    url: ${DATABASE_URL}  # From environment variable
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      
  jpa:
    show-sql: false  # Disable SQL logging in production
    
logging:
  level:
    com.carddemo: INFO
    org.springframework: WARN
```

## Building the Application

### Standard Build

```bash
# Clean and compile
mvn clean compile

# Run tests
mvn test

# Package as JAR (includes tests)
mvn clean package

# Package without tests
mvn clean package -DskipTests
```

### Build Output

The build process generates:
- Compiled classes: `target/classes/`
- Test classes: `target/test-classes/`
- Executable JAR: `target/carddemo-backend-1.0.0.jar`

### Running Specific Tests

```bash
# Run single test class
mvn test -Dtest=AccountServiceTest

# Run multiple test classes
mvn test -Dtest=AccountServiceTest,CardServiceTest

# Run tests matching pattern
mvn test -Dtest=*ServiceTest

# Run integration tests only
mvn verify -P integration-tests
```

### Build Profiles

```bash
# Development build (faster, skip some checks)
mvn clean package -P dev

# Production build (full validation)
mvn clean package -P prod

# Build with code coverage
mvn clean verify -P coverage
```

### Maven Plugins Used

| Plugin | Purpose | Command |
|--------|---------|---------|
| spring-boot-maven-plugin | Package executable JAR | `mvn spring-boot:run` |
| maven-compiler-plugin | Compile Java 21 code | `mvn compile` |
| maven-surefire-plugin | Run unit tests | `mvn test` |
| maven-failsafe-plugin | Run integration tests | `mvn verify` |
| jacoco-maven-plugin | Code coverage | `mvn jacoco:report` |
| flyway-maven-plugin | Database migrations | `mvn flyway:migrate` |

### Troubleshooting Build Issues

#### Out of Memory Error

```bash
# Increase Maven memory
export MAVEN_OPTS="-Xmx2048m -XX:MaxPermSize=512m"
mvn clean package
```

#### Compilation Errors

```bash
# Clean Maven cache
mvn dependency:purge-local-repository

# Force update dependencies
mvn clean install -U
```

## Running the Application

### Development Mode

#### Using Maven

```bash
# Run with default profile
mvn spring-boot:run

# Run with specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Run with JVM arguments
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx1024m"

# Run with application arguments
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=9090"
```

#### Using Executable JAR

```bash
# Package first
mvn clean package

# Run JAR
java -jar target/carddemo-backend-1.0.0.jar

# With profile
java -jar target/carddemo-backend-1.0.0.jar --spring.profiles.active=dev

# With custom port
java -jar target/carddemo-backend-1.0.0.jar --server.port=9090
```

### Production Mode

```bash
# Set production profile
export SPRING_PROFILES_ACTIVE=prod

# Set database URL
export DATABASE_URL=jdbc:postgresql://prod-db-host:5432/carddemo

# Set Redis URL
export REDIS_HOST=prod-redis-host

# Run application
java -Xmx2048m -Xms512m \
  -jar target/carddemo-backend-1.0.0.jar \
  --spring.profiles.active=prod
```

### Startup Verification

Once the application starts, you should see:

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.1)

2024-01-15 10:30:00.000  INFO 12345 --- [main] com.carddemo.CardDemoApplication : Starting CardDemoApplication
2024-01-15 10:30:05.000  INFO 12345 --- [main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port(s): 8080 (http)
2024-01-15 10:30:05.500  INFO 12345 --- [main] com.carddemo.CardDemoApplication : Started CardDemoApplication in 5.5 seconds
```

### Health Check Endpoints

Verify the application is running:

```bash
# Health check
curl http://localhost:8080/actuator/health

# Expected response:
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "UP"}
  }
}

# Application info
curl http://localhost:8080/actuator/info

# Metrics
curl http://localhost:8080/actuator/metrics
```

## API Documentation

The backend service exposes RESTful APIs documented using OpenAPI 3.0 (Swagger).

### Accessing API Documentation

#### Swagger UI (Interactive)

Open your browser and navigate to:
```
http://localhost:8080/swagger-ui.html
```

Features:
- Browse all available endpoints
- View request/response schemas
- Test API calls directly from the browser
- See authentication requirements

#### OpenAPI Specification (JSON)

```bash
# View raw OpenAPI spec
curl http://localhost:8080/api-docs

# Save to file
curl http://localhost:8080/api-docs > openapi.json
```

### API Endpoint Structure

All endpoints follow RESTful conventions and are prefixed with `/api`:

#### Authentication Endpoints

```
POST   /api/auth/login      # User login (returns JWT token)
POST   /api/auth/logout     # User logout
GET    /api/auth/me         # Get current user info
```

#### Menu Endpoints

```
GET    /api/menu            # Get menu options based on user role
```

#### Account Endpoints

```
GET    /api/accounts                    # List all accounts (paginated)
GET    /api/accounts/{id}               # Get account details
POST   /api/accounts                    # Create new account
PUT    /api/accounts/{id}               # Update account
DELETE /api/accounts/{id}               # Delete account
GET    /api/accounts/{id}/balance       # Get account balance
GET    /api/accounts/customer/{customerId}  # Get accounts by customer
```

#### Card Endpoints

```
GET    /api/cards                       # List all cards (paginated)
GET    /api/cards/{id}                  # Get card details
POST   /api/cards                       # Create new card
PUT    /api/cards/{id}                  # Update card
DELETE /api/cards/{id}                  # Delete card
GET    /api/cards/account/{accountId}   # Get cards by account
PUT    /api/cards/{id}/status           # Update card status
```

#### Transaction Endpoints

```
GET    /api/transactions                # List transactions (paginated)
GET    /api/transactions/{id}           # Get transaction details
POST   /api/transactions                # Create transaction
GET    /api/transactions/account/{accountId}  # Get transactions by account
GET    /api/transactions/card/{cardId}  # Get transactions by card
GET    /api/transactions/category       # Get transactions by category
GET    /api/transactions/dateRange      # Get transactions by date range
```

#### Bill Payment Endpoints

```
POST   /api/payments/bill               # Process bill payment
GET    /api/payments/history            # Get payment history
```

#### Report Endpoints

```
GET    /api/reports/menu                # Get available reports
GET    /api/reports/account/{accountId} # Generate account report
GET    /api/reports/transaction/summary # Transaction summary report
GET    /api/reports/monthly             # Monthly statement report
```

#### Admin Endpoints

```
GET    /api/admin/dashboard             # Admin dashboard data
GET    /api/admin/users                 # List all users
POST   /api/admin/users                 # Create user
PUT    /api/admin/users/{id}            # Update user
DELETE /api/admin/users/{id}            # Delete user
```

#### User Management Endpoints

```
GET    /api/users/profile               # Get user profile
PUT    /api/users/profile               # Update user profile
PUT    /api/users/password              # Change password
```

### COBOL Transaction to REST API Mapping

| COBOL Transaction | REST Endpoint | HTTP Method | Description |
|------------------|---------------|-------------|-------------|
| CC00 (COSGN00C) | /api/auth/login | POST | User sign-on |
| CM00 (COMEN01C) | /api/menu | GET | Main menu |
| CAVW (COACTVWC) | /api/accounts/{id} | GET | Account view |
| CAUP (COACTUPC) | /api/accounts/{id} | PUT | Account update |
| CCLI (COCRDLIC) | /api/cards | GET | Card list |
| CCDL (COCRDSLC) | /api/cards/{id} | GET | Card detail view |
| CCUP (COCRDUPC) | /api/cards/{id} | PUT | Card update |
| CT00 (COTRN00C) | /api/transactions | GET | Transaction list |
| CT01 (COTRN01C) | /api/transactions/category | GET | Transaction by category |
| CT02 (COTRN02C) | /api/transactions | POST | Add transaction |
| CR00 (CORPT00C) | /api/reports/menu | GET | Report menu |
| CB00 (COBIL00C) | /api/payments/bill | POST | Bill payment |
| CA00 (COADM01C) | /api/admin/dashboard | GET | Admin menu |
| CU00 (COUSR00C) | /api/admin/users | GET | List users |
| CU01 (COUSR01C) | /api/admin/users | POST | Add user |

### Authentication

All protected endpoints require JWT authentication:

```bash
# 1. Login to get token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "USER0001",
    "password": "PASSWORD"
  }'

# Response:
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": "USER0001",
  "userName": "John Doe",
  "userType": "R"
}

# 2. Use token in subsequent requests
curl -X GET http://localhost:8080/api/accounts/123 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

### Sample Request/Response

#### Get Account Details

**Request:**
```bash
curl -X GET http://localhost:8080/api/accounts/123456789 \
  -H "Authorization: Bearer <token>"
```

**Response (200 OK):**
```json
{
  "accountId": "123456789",
  "customerId": "987654321",
  "accountStatus": "A",
  "accountBalance": "15234.56",
  "creditLimit": "25000.00",
  "cashCreditLimit": "5000.00",
  "openDate": "2020-01-15",
  "expirationDate": "2025-01-15",
  "reissueDate": null,
  "currentBalance": "15234.56",
  "availableCredit": "9765.44"
}
```

## Batch Jobs

The application includes Spring Batch jobs that replace the legacy JCL batch programs.

### Available Batch Jobs

#### Data Load Jobs

| Job Name | Description | Source COBOL | Schedule |
|----------|-------------|--------------|----------|
| AccountDataLoadJob | Load account data from files | CBACT01C.cbl | Daily 2:00 AM |
| CustomerDataLoadJob | Load customer data | CBCUS01C.cbl | Daily 2:30 AM |
| CardDataLoadJob | Load card data | CBCRD01C.cbl | Daily 3:00 AM |
| TransactionDataLoadJob | Load transaction data | CBTRN01C.cbl | Daily 3:30 AM |

#### Processing Jobs

| Job Name | Description | Source COBOL | Schedule |
|----------|-------------|--------------|----------|
| DailyTransactionProcessingJob | Process daily transactions | CBTRN02C.cbl | Daily 4:00 AM |
| TransactionAggregationJob | Aggregate transaction data | CBTRN03C.cbl | Daily 5:00 AM |
| AccountBalanceJob | Calculate account balances | CBACT03C.cbl | Daily 6:00 AM |
| InterestCalculationJob | Calculate interest charges | CBACT04C.cbl | Monthly, 1st day |
| AccountXrefBuildJob | Build cross-reference data | CBACT02C.cbl | Weekly, Sunday |

#### Report Generation Jobs

| Job Name | Description | Source COBOL | Schedule |
|----------|-------------|--------------|----------|
| StatementGenerationJob | Generate monthly statements | CBSTM03A.cbl | Monthly, 1st day |
| StatementFormattingJob | Format statement output | CBSTM03B.cbl | Monthly, 1st day |

### Running Batch Jobs Manually

#### Using Maven Spring Boot Plugin

```bash
# Run specific job
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--spring.batch.job.names=AccountDataLoadJob"

# Run with parameters
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--spring.batch.job.names=InterestCalculationJob,effectiveDate=2024-01-01"
```

#### Using Executable JAR

```bash
# Run job from packaged JAR
java -jar target/carddemo-backend-1.0.0.jar \
  --spring.batch.job.names=DailyTransactionProcessingJob

# Run multiple jobs
java -jar target/carddemo-backend-1.0.0.jar \
  --spring.batch.job.names=AccountDataLoadJob,CustomerDataLoadJob
```

#### Using Spring Batch Admin API (if enabled)

```bash
# Trigger job via REST API
curl -X POST http://localhost:8080/batch/jobs/AccountDataLoadJob/launch
```

### Batch Job Configuration

Jobs are configured in `src/main/resources/batch/`:

#### Example: Account Data Load Job

```yaml
# account-load-job.xml
spring:
  batch:
    job:
      account-data-load:
        chunk-size: 1000           # Process 1000 records at a time
        skip-limit: 100            # Skip up to 100 errors
        retry-limit: 3             # Retry failed items 3 times
        transaction-timeout: 300   # 5 minute timeout
```

### Monitoring Batch Jobs

#### Job Execution Status

```bash
# View job execution history
curl http://localhost:8080/actuator/batch/jobs

# View specific job executions
curl http://localhost:8080/actuator/batch/jobs/AccountDataLoadJob/executions

# View execution details
curl http://localhost:8080/actuator/batch/jobs/AccountDataLoadJob/executions/1
```

#### Job Repository

Spring Batch maintains execution metadata in database tables:
- `BATCH_JOB_INSTANCE` - Job instances
- `BATCH_JOB_EXECUTION` - Job execution records
- `BATCH_STEP_EXECUTION` - Step execution records
- `BATCH_JOB_EXECUTION_PARAMS` - Job parameters
- `BATCH_JOB_EXECUTION_CONTEXT` - Execution context data

### Batch Processing Features

#### Chunk-Oriented Processing

Jobs use chunk-oriented processing for efficient handling of large datasets:

1. **Read** - ItemReader reads records in chunks (default 1000)
2. **Process** - ItemProcessor transforms each record
3. **Write** - ItemWriter writes chunk to database

#### Restart and Recovery

- Failed jobs can be restarted from the last successful checkpoint
- Execution context preserves state for recovery
- Configurable skip and retry policies

#### Parallel Processing

Jobs can execute steps in parallel for improved performance:
- Partition-based parallelism for data-intensive steps
- Task executor pool configuration
- Configurable thread pool size

## Testing

The application includes comprehensive test coverage to ensure functional equivalence with the COBOL source.

### Test Categories

#### Unit Tests

Located in `src/test/java/com/carddemo/`

```bash
# Run all unit tests
mvn test

# Run specific test class
mvn test -Dtest=AccountServiceTest

# Run tests with specific pattern
mvn test -Dtest=*ServiceTest
```

**Test Coverage:**
- **Service Layer**: Business logic validation (28 test classes)
- **Repository Layer**: Data access testing (8 test classes)
- **Utility Classes**: Helper function tests (10 test classes)
- **DTO Validation**: Request/response validation (15 test classes)

#### Integration Tests

Located in `src/test/java/com/carddemo/integration/`

```bash
# Run integration tests
mvn verify

# Run with specific profile
mvn verify -P integration-tests
```

**Test Coverage:**
- End-to-end workflow testing
- Database transaction testing
- REST API integration testing
- Security integration testing

#### Controller Tests

Located in `src/test/java/com/carddemo/controller/`

```bash
# Run controller tests
mvn test -Dtest=*ControllerTest
```

**Test Coverage:**
- REST endpoint testing with MockMvc
- Request validation testing
- Response format validation
- Error handling scenarios

#### Batch Job Tests

Located in `src/test/java/com/carddemo/batch/`

```bash
# Run batch tests
mvn test -Dtest=*JobTest
```

**Test Coverage:**
- Job configuration testing
- Reader/Processor/Writer testing
- Job execution flow testing
- Error handling and restart scenarios

### Code Coverage

The project uses JaCoCo for code coverage analysis:

```bash
# Generate coverage report
mvn clean verify jacoco:report

# View report
open target/site/jacoco/index.html
```

**Coverage Requirements:**
- Minimum line coverage: **80%**
- Minimum branch coverage: **70%**
- Critical business logic: **90%+**

### Test Database Configuration

Tests use H2 in-memory database by default:

```yaml
# application-test.yml (auto-loaded for tests)
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
  
  jpa:
    hibernate:
      ddl-auto: create-drop
    
  flyway:
    enabled: false  # Use Hibernate DDL for tests
```

### Running Tests with TestContainers

For integration tests using real PostgreSQL:

```bash
# Start TestContainers for integration tests
mvn verify -P testcontainers
```

### Test Data

Test data is managed through:
1. **SQL Scripts**: `src/test/resources/data.sql`
2. **Java Fixtures**: Test data builders in `src/test/java/com/carddemo/fixtures/`
3. **Mock Objects**: Mockito mocks for external dependencies

### Example Test

```java
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void testGetAccount_Success() throws Exception {
        mockMvc.perform(get("/api/accounts/123456789")
            .header("Authorization", "Bearer " + jwtToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("123456789"))
            .andExpect(jsonPath("$.accountBalance").value("15234.56"));
    }
}
```

## Docker Build

The application can be containerized using Docker for consistent deployment across environments.

### Dockerfile

The project includes a multi-stage Dockerfile for optimized image size:

```dockerfile
# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/carddemo-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Building Docker Image

```bash
# Build image
docker build -t carddemo-backend:latest .

# Build with custom tag
docker build -t carddemo-backend:1.0.0 .

# Build with build arguments
docker build \
  --build-arg MAVEN_OPTS="-Xmx1024m" \
  -t carddemo-backend:latest .
```

### Running Docker Container

#### Basic Run

```bash
# Run container
docker run -p 8080:8080 carddemo-backend:latest

# Run with environment variables
docker run \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=dev \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/carddemo \
  carddemo-backend:latest
```

#### Using Docker Compose

```yaml
# docker-compose.yml
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: carddemo
      POSTGRES_USER: carddemo
      POSTGRES_PASSWORD: carddemo123
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  backend:
    build: ./backend
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: dev
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/carddemo
      SPRING_DATA_REDIS_HOST: redis
    depends_on:
      - postgres
      - redis

volumes:
  postgres-data:
```

```bash
# Start all services
docker-compose up

# Start in background
docker-compose up -d

# View logs
docker-compose logs -f backend

# Stop all services
docker-compose down
```

### Container Health Check

```bash
# Check container health
docker ps --filter name=carddemo-backend

# View container logs
docker logs -f <container-id>

# Execute command in container
docker exec -it <container-id> sh

# Health check endpoint
curl http://localhost:8080/actuator/health
```

### Publishing to Registry

```bash
# Tag for registry
docker tag carddemo-backend:latest myregistry.com/carddemo-backend:latest

# Push to registry
docker push myregistry.com/carddemo-backend:latest

# Pull from registry
docker pull myregistry.com/carddemo-backend:latest
```

## Key Design Patterns

The application implements enterprise design patterns to ensure maintainability and scalability.

### Repository Pattern

**Purpose:** Abstraction layer for data access, separating business logic from data persistence.

**Implementation:**
- Spring Data JPA repositories provide CRUD operations
- Custom query methods using method naming conventions
- Native queries for complex operations
- Transaction management via @Transactional

**Example:**
```java
public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByCustomerId(Long customerId);
    Optional<Account> findByAccountNumber(String accountNumber);
    
    @Query("SELECT a FROM Account a WHERE a.balance > :minBalance")
    List<Account> findAccountsWithMinimumBalance(@Param("minBalance") BigDecimal minBalance);
}
```

### Service Layer Pattern

**Purpose:** Encapsulate business logic from COBOL programs, providing transaction boundaries.

**Implementation:**
- Each COBOL program maps to a service class
- Service methods correspond to COBOL paragraphs/sections
- @Transactional annotations for CICS SYNCPOINT equivalence
- Business rule validation and error handling

**COBOL to Service Mapping:**
```
COBOL Program: COACTVWC.cbl (Account View)
    ↓
Java Service: AccountViewService.java
    - getAccountDetails() method
    - WORKING-STORAGE → class fields
    - PERFORM statements → method calls
    - EXEC CICS READ → repository.findById()
```

### DTO Pattern

**Purpose:** Decouple API contracts from entity models, transform COMMAREA structures.

**Implementation:**
- Separate request and response DTOs
- Bean Validation annotations for field validation
- MapStruct for entity-DTO conversions
- JSON serialization via Jackson

**COMMAREA to DTO Mapping:**
```
COBOL COMMAREA Structure:
01 ACCOUNT-COMMAREA.
   05 ACCT-ID        PIC 9(11).
   05 ACCT-BALANCE   PIC S9(13)V99 COMP-3.
   05 ACCT-STATUS    PIC X(1).

Java Request DTO:
public class AccountUpdateRequest {
    @NotNull @Size(min=11, max=11)
    private String accountId;
    
    @DecimalMin("0.00") @DecimalMax("999999999.99")
    private BigDecimal balance;
    
    @Pattern(regexp="[ACI]")
    private String status;
}
```

### Transaction Management Pattern

**Purpose:** Maintain ACID properties, replicate CICS transaction semantics.

**Implementation:**
- @Transactional annotations with isolation and propagation settings
- Read-committed isolation level (CICS default)
- Rollback on runtime exceptions
- Programmatic transaction control where needed

**CICS to Spring Mapping:**
```java
// COBOL: EXEC CICS SYNCPOINT END-EXEC
// Java:
@Transactional(
    isolation = Isolation.READ_COMMITTED,
    propagation = Propagation.REQUIRED,
    rollbackFor = Exception.class
)
public void updateAccountAndCard(AccountUpdateRequest request) {
    // Atomic operations
    accountRepository.save(account);
    cardRepository.save(card);
    // Automatic commit or rollback
}
```

### Batch Processing Pattern

**Purpose:** Transform JCL batch jobs to chunk-oriented processing.

**Implementation:**
- ItemReader-Processor-Writer pattern
- Chunk-oriented processing for large datasets
- Job repository for execution tracking
- Restart and recovery capabilities

**JCL to Spring Batch Mapping:**
```
JCL Job: CBACT01C.jcl (Account Data Load)
    ↓
Spring Batch: AccountDataLoadJob.java
    - AccountItemReader (sequential file read)
    - AccountDataProcessor (data transformation)
    - AccountItemWriter (database write)
    - Chunk size: 1000 records
```

### Exception Handling Pattern

**Purpose:** Centralized error handling, map COBOL file status and RESP codes.

**Implementation:**
- @ControllerAdvice for global exception handling
- Custom exception hierarchy
- Consistent error response format
- HTTP status code mapping

**COBOL Error Handling:**
```java
// COBOL: IF FILE-STATUS NOT = '00' ...
// Java:
@ExceptionHandler(AccountNotFoundException.class)
public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse("ACCT-404", ex.getMessage()));
}
```

## COBOL-to-Java Transformation Notes

Understanding the transformation patterns helps maintain consistency and troubleshoot issues.

### Data Type Conversions

#### COBOL to Java Type Mapping

| COBOL Data Type | Java Equivalent | Notes |
|----------------|-----------------|-------|
| PIC 9(n) | Integer or Long | Use Long if n > 9 |
| PIC 9(n)V9(m) | BigDecimal | Specify scale = m |
| PIC S9(n)V99 COMP-3 | BigDecimal | Packed decimal, scale = 2 |
| PIC X(n) | String | Apply @Size(max=n) validation |
| PIC A(n) | String | Alphabetic only, validation required |
| PIC 9(n) COMP | Integer or Long | Binary integer |
| DATE-COMPILED | LocalDate | ISO 8601 format |
| COMP-1 | Float | Single precision |
| COMP-2 | Double | Double precision |

### Numeric Precision Preservation

**Critical Requirement:** COBOL COMP-3 packed decimal precision must be exactly replicated.

#### Example: COMP-3 to BigDecimal

```java
// COBOL: 01 INTEREST-AMOUNT PIC S9(7)V99 COMP-3.
// Java Entity:
@Column(name = "interest_amount", precision = 9, scale = 2)
private BigDecimal interestAmount;

// Always set scale explicitly:
interestAmount = new BigDecimal("123.45").setScale(2, RoundingMode.HALF_UP);

// Arithmetic operations:
BigDecimal result = principal
    .multiply(rate)
    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
```

#### Rounding Mode Mapping

| COBOL Rounding | Java RoundingMode |
|---------------|-------------------|
| Default | HALF_UP |
| ROUNDED | HALF_UP |
| Truncate | DOWN |

### Control Structure Transformations

#### PERFORM to Method Calls

```cobol
COBOL:
    PERFORM 1000-VALIDATE-INPUT
    PERFORM 2000-PROCESS-RECORD
    PERFORM 3000-UPDATE-DATABASE

Java:
    validateInput();
    processRecord();
    updateDatabase();
```

#### EVALUATE to Switch Expression

```cobol
COBOL:
    EVALUATE TRUE
        WHEN ACCT-TYPE = 'S'
            PERFORM PROCESS-SAVINGS
        WHEN ACCT-TYPE = 'C'
            PERFORM PROCESS-CHECKING
        WHEN OTHER
            PERFORM INVALID-TYPE
    END-EVALUATE

Java (Java 14+ switch expression):
    switch (account.getType()) {
        case 'S' -> processSavings();
        case 'C' -> processChecking();
        default -> invalidType();
    }
```

#### IF-ELSE Transformations

```cobol
COBOL:
    IF ACCT-BALANCE > CREDIT-LIMIT
        MOVE 'E' TO ERROR-CODE
    ELSE
        PERFORM UPDATE-BALANCE
    END-IF

Java:
    if (account.getBalance().compareTo(creditLimit) > 0) {
        errorCode = 'E';
    } else {
        updateBalance();
    }
```

### 88-Level Condition Names to Enums

```cobol
COBOL:
    01 CARD-STATUS PIC X(1).
       88 CARD-ACTIVE VALUE 'A'.
       88 CARD-EXPIRED VALUE 'E'.
       88 CARD-BLOCKED VALUE 'B'.

Java:
    public enum CardStatus {
        ACTIVE('A'),
        EXPIRED('E'),
        BLOCKED('B');
        
        private final char code;
        
        CardStatus(char code) {
            this.code = code;
        }
        
        public static CardStatus fromCode(char code) {
            return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid status code: " + code));
        }
    }
```

### File I/O Transformations

#### VSAM READ to JPA findById

```cobol
COBOL:
    EXEC CICS READ
        FILE('ACCTDAT')
        INTO(ACCOUNT-RECORD)
        RIDFLD(ACCOUNT-ID)
        RESP(WS-RESP)
    END-EXEC

Java:
    Optional<Account> accountOpt = accountRepository.findById(accountId);
    if (accountOpt.isPresent()) {
        Account account = accountOpt.get();
        // Process account
    } else {
        throw new AccountNotFoundException("Account not found: " + accountId);
    }
```

#### VSAM WRITE to JPA save

```cobol
COBOL:
    EXEC CICS WRITE
        FILE('ACCTDAT')
        FROM(ACCOUNT-RECORD)
        RIDFLD(ACCOUNT-ID)
        RESP(WS-RESP)
    END-EXEC

Java:
    Account savedAccount = accountRepository.save(account);
```

### Date Handling

#### CEEDAYS Conversion

```cobol
COBOL:
    CALL 'CEEDAYS' USING WS-DATE, WS-LILLIAN-DATE

Java:
    // CEEDAYS returns days since October 15, 1582 (Lillian date)
    public LocalDate convertLillianToLocalDate(int lillianDate) {
        LocalDate baseDate = LocalDate.of(1582, 10, 15);
        return baseDate.plusDays(lillianDate);
    }
    
    public int convertLocalDateToLillian(LocalDate date) {
        LocalDate baseDate = LocalDate.of(1582, 10, 15);
        return (int) ChronoUnit.DAYS.between(baseDate, date);
    }
```

### WORKING-STORAGE to Class Fields

```cobol
COBOL:
    WORKING-STORAGE SECTION.
    01 WS-ACCOUNT-DATA.
       05 WS-ACCT-ID       PIC 9(11).
       05 WS-ACCT-BALANCE  PIC S9(13)V99 COMP-3.
       05 WS-ACCT-STATUS   PIC X(1).

Java:
    public class AccountService {
        // Instance variables (not static unless shared across all instances)
        private Long accountId;
        private BigDecimal accountBalance;
        private String accountStatus;
        
        // Or better: use local variables within methods
    }
```

### COPY to Import Statements

```cobol
COBOL:
    COPY CVCUS01Y.
    COPY CVACT01Y.
    COPY COCOM01Y.

Java:
    import com.carddemo.entity.Customer;
    import com.carddemo.entity.Account;
    import com.carddemo.dto.CommonDTO;
```

### CALL to Service Injection

```cobol
COBOL:
    CALL 'COSGN00C' USING COMMAREA

Java:
    @Autowired
    private AuthenticationService authenticationService;
    
    LoginResponse response = authenticationService.login(loginRequest);
```

## Performance Targets

The application maintains performance parity with the mainframe system.

### Response Time Requirements

| Operation Type | Target (95th Percentile) | Measurement |
|---------------|-------------------------|-------------|
| Card Authorization Request | < 200ms | End-to-end API response |
| Account Lookup | < 100ms | Database query + processing |
| Transaction Posting | < 150ms | Including balance update |
| User Authentication | < 100ms | JWT token generation |
| Report Generation (Simple) | < 500ms | Single account report |
| Report Generation (Complex) | < 5s | Monthly statement |

### Throughput Requirements

| Metric | Target | Notes |
|--------|--------|-------|
| Peak TPS (Transactions Per Second) | 10,000 | Without performance degradation |
| Concurrent Users | 150+ | Simultaneous active sessions |
| Batch Processing Window | < 4 hours | All nightly batch jobs |
| Database Connections | 20-50 | HikariCP pool size |

### Performance Optimization Techniques

#### Database Optimization

```yaml
# HikariCP Configuration
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 60000
```

#### JVM Tuning

```bash
# Production JVM settings
java -Xms512m -Xmx2048m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -jar carddemo-backend.jar
```

#### Caching Strategy

```java
// Redis caching for frequently accessed data
@Cacheable(value = "accounts", key = "#accountId")
public Account getAccount(Long accountId) {
    return accountRepository.findById(accountId)
        .orElseThrow(() -> new AccountNotFoundException());
}

@CacheEvict(value = "accounts", key = "#account.id")
public Account updateAccount(Account account) {
    return accountRepository.save(account);
}
```

#### Query Optimization

```java
// Use pagination for large result sets
Page<Transaction> transactions = transactionRepository
    .findByAccountId(accountId, PageRequest.of(page, 10, Sort.by("transactionDate").descending()));

// Use projections to fetch only required fields
List<AccountSummary> summaries = accountRepository.findSummariesByCustomerId(customerId);
```

### Performance Monitoring

```bash
# Actuator metrics endpoints
curl http://localhost:8080/actuator/metrics/http.server.requests
curl http://localhost:8080/actuator/metrics/jvm.memory.used
curl http://localhost:8080/actuator/metrics/jdbc.connections.active

# Prometheus integration
curl http://localhost:8080/actuator/prometheus
```

## Security

The application implements enterprise-grade security using Spring Security 6.x.

### Authentication

#### JWT Token-Based Authentication

Replaces CICS session management with stateless JWT tokens.

**Login Flow:**
1. User submits credentials to `/api/auth/login`
2. Backend validates against `user_security` table (replaces USRSEC VSAM file)
3. JWT token generated with user claims and roles
4. Token returned to client with 24-hour expiration
5. Client includes token in `Authorization` header for subsequent requests

**Token Structure:**
```json
{
  "header": {
    "alg": "HS256",
    "typ": "JWT"
  },
  "payload": {
    "sub": "USER0001",
    "userName": "John Doe",
    "roles": ["ROLE_USER"],
    "iat": 1705320000,
    "exp": 1705406400
  }
}
```

#### Password Encryption

```java
// BCrypt with strength 12 (replaces plain text USRSEC passwords)
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
}

// Password validation
if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
    throw new AuthenticationFailedException("Invalid credentials");
}
```

### Authorization

#### Role-Based Access Control

Maps COBOL USER-TYPE to Spring Security roles:

| COBOL USER-TYPE | Spring Security Role | Access Level |
|-----------------|---------------------|--------------|
| 'R' (Regular) | ROLE_USER | Standard user functions |
| 'A' (Admin) | ROLE_ADMIN | Administrative functions |

**Method-Level Security:**
```java
@PreAuthorize("hasRole('USER')")
public AccountViewResponse viewAccount(Long accountId) {
    // Accessible to ROLE_USER and ROLE_ADMIN
}

@PreAuthorize("hasRole('ADMIN')")
public void deleteUser(Long userId) {
    // Accessible to ROLE_ADMIN only
}

@PreAuthorize("hasRole('USER') and #userId == authentication.principal.userId")
public UserProfileResponse viewProfile(Long userId) {
    // User can only view their own profile
}
```

### Security Configuration

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/swagger-ui/**", "/api-docs/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
            )
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
}
```

### Session Management

Redis-backed session storage for stateless scalability:

```yaml
spring:
  session:
    store-type: redis
    redis:
      namespace: carddemo:session
    timeout: 86400  # 24 hours
```

### Security Best Practices

1. **Password Policy**
   - Minimum 8 characters
   - Must contain uppercase, lowercase, digit
   - BCrypt hashing with strength 12
   - Password history tracking (prevent reuse)

2. **Token Security**
   - JWT tokens expire after 24 hours
   - Refresh token mechanism (optional)
   - Token revocation support via Redis blacklist

3. **HTTPS Enforcement**
   - TLS 1.3 minimum in production
   - HSTS headers enabled
   - Secure cookie attributes

4. **API Security**
   - Rate limiting on authentication endpoints
   - CORS configuration for frontend domain
   - SQL injection prevention via parameterized queries
   - XSS protection via Content-Security-Policy headers

## Troubleshooting

Common issues and their solutions.

### Database Connection Issues

**Problem:** Application fails to connect to PostgreSQL

**Symptoms:**
```
org.postgresql.util.PSQLException: Connection refused
```

**Solutions:**
1. Verify PostgreSQL is running: `pg_isready -h localhost -p 5432`
2. Check connection string in `application-dev.yml`
3. Verify credentials: `psql -U carddemo -d carddemo`
4. Check firewall rules allow port 5432

### Redis Connection Issues

**Problem:** Cannot connect to Redis

**Symptoms:**
```
io.lettuce.core.RedisConnectionException: Unable to connect to localhost:6379
```

**Solutions:**
1. Start Redis: `redis-server` or `brew services start redis`
2. Test connection: `redis-cli ping` (should return PONG)
3. Check Redis configuration in `application.yml`

### Port Already in Use

**Problem:** Port 8080 is already in use

**Symptoms:**
```
Web server failed to start. Port 8080 was already in use.
```

**Solutions:**
1. Find process using port: `lsof -i :8080` or `netstat -ano | findstr :8080`
2. Kill process or change port in `application.yml`:
   ```yaml
   server:
     port: 9090
   ```

### Flyway Migration Failures

**Problem:** Database migration fails

**Symptoms:**
```
FlywayException: Validate failed: Migration checksum mismatch
```

**Solutions:**
1. Repair Flyway metadata: `mvn flyway:repair`
2. Baseline existing database: `mvn flyway:baseline`
3. Drop and recreate database for clean start (dev only!)

### Out of Memory Errors

**Problem:** Application crashes with OutOfMemoryError

**Symptoms:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Solutions:**
1. Increase heap size: `-Xmx2048m`
2. Analyze heap dump: `jmap -dump:file=heap.bin <pid>`
3. Check for memory leaks in batch jobs
4. Reduce batch chunk size in configuration

### Slow Query Performance

**Problem:** API response times exceed 200ms

**Solutions:**
1. Enable SQL logging: `spring.jpa.show-sql=true`
2. Check query execution plans: `EXPLAIN ANALYZE SELECT...`
3. Add missing indexes on frequently queried columns
4. Use database connection pooling
5. Implement Redis caching for frequently accessed data

### JWT Token Errors

**Problem:** Authentication token invalid or expired

**Symptoms:**
```
401 Unauthorized - JWT token has expired
```

**Solutions:**
1. Check token expiration configuration
2. Verify token signature (secret key must match)
3. Clear browser storage and re-login
4. Check system clock synchronization

### Batch Job Failures

**Problem:** Spring Batch job fails mid-execution

**Solutions:**
1. Check job execution status in `BATCH_JOB_EXECUTION` table
2. Review step execution details in logs
3. Restart failed job (Spring Batch supports restart)
4. Adjust skip limit or retry count in job configuration
5. Check file permissions for file-based readers/writers

### Debug Logging

Enable debug logging for troubleshooting:

```yaml
logging:
  level:
    com.carddemo: DEBUG
    org.springframework.web: DEBUG
    org.springframework.security: DEBUG
    org.hibernate.SQL: DEBUG
    org.hibernate.type.descriptor.sql.BasicBinder: TRACE
```

## Additional Documentation

For more detailed information, see:

### Architecture Documentation

**File:** `../docs/ARCHITECTURE.md`

**Contents:**
- System architecture diagrams
- Component interaction flows
- Technology stack details
- Deployment architecture
- Scalability and high availability design

### API Reference

**File:** `../docs/API.md`

**Contents:**
- Complete REST API endpoint reference
- Request/response schemas
- Authentication and authorization details
- Error codes and handling
- Example API calls with curl/Postman

### Deployment Guide

**File:** `../docs/DEPLOYMENT.md`

**Contents:**
- Kubernetes deployment instructions
- Docker container configuration
- Environment-specific setup
- CI/CD pipeline configuration
- Production deployment checklist
- Rollback procedures

### Migration Guide

**File:** `../docs/MIGRATION_GUIDE.md`

**Contents:**
- COBOL-to-Java transformation mapping
- Data migration procedures
- VSAM to PostgreSQL conversion
- Testing and validation approach
- Cutover planning and execution
- Rollback strategy

### Testing Guide

**File:** `../docs/TESTING.md` (if available)

**Contents:**
- Test strategy and approach
- Unit testing guidelines
- Integration testing procedures
- Performance testing methodology
- Functional equivalence validation

## Contributing

We welcome contributions to improve the CardDemo backend service!

### How to Contribute

1. **Fork the Repository**
   ```bash
   git clone <repository-url>
   cd backend
   ```

2. **Create Feature Branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Make Changes**
   - Follow existing code style and patterns
   - Add unit tests for new functionality
   - Update documentation as needed
   - Ensure all tests pass: `mvn test`

4. **Commit Changes**
   ```bash
   git add .
   git commit -m "feat: add your feature description"
   ```

5. **Push and Create Pull Request**
   ```bash
   git push origin feature/your-feature-name
   ```

### Code Style Guidelines

- Follow standard Java conventions (Oracle Code Conventions)
- Use meaningful variable and method names
- Add JavaDoc comments for public methods
- Maintain test coverage above 80%
- Keep methods focused and concise (< 50 lines ideally)

### Commit Message Format

Follow conventional commits:
- `feat:` New feature
- `fix:` Bug fix
- `docs:` Documentation changes
- `test:` Test additions or modifications
- `refactor:` Code refactoring
- `perf:` Performance improvements

### Pull Request Guidelines

- Provide clear description of changes
- Reference related issues
- Ensure CI/CD pipeline passes
- Request review from maintainers
- Address review feedback promptly

## License

This project is licensed under the **Apache License 2.0**.

### Key Points

- Free to use, modify, and distribute
- Must include original copyright notice
- Changes must be documented
- No trademark rights granted
- Provided "AS IS" without warranties

### Full License

See the [LICENSE](../LICENSE) file in the repository root for complete terms.

---

## Quick Reference Card

### Essential Commands

```bash
# Build
mvn clean install

# Run (dev)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Run tests
mvn test

# Docker build
docker build -t carddemo-backend .

# Docker run
docker run -p 8080:8080 carddemo-backend

# Run batch job
java -jar target/carddemo-backend-1.0.0.jar --spring.batch.job.names=AccountDataLoadJob
```

### Important URLs

- Application: http://localhost:8080
- API Docs: http://localhost:8080/swagger-ui.html
- Health Check: http://localhost:8080/actuator/health
- Metrics: http://localhost:8080/actuator/metrics

### Default Credentials (Development)

- **Regular User:** `USER0001` / `PASSWORD`
- **Admin User:** `ADMIN001` / `PASSWORD`

---

**For questions or issues, please raise an issue in the repository or contact the development team.**

**Last Updated:** January 2024
**Version:** 1.0.0

