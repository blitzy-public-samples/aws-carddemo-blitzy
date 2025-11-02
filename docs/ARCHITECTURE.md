# CardDemo Application Architecture

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Technology Stack](#3-technology-stack)
4. [Layered Architecture Design](#4-layered-architecture-design)
5. [Data Architecture](#5-data-architecture)
6. [Security Architecture](#6-security-architecture)
7. [Performance and Scalability](#7-performance-and-scalability)
8. [Integration Points](#8-integration-points)
9. [Monitoring and Observability](#9-monitoring-and-observability)
10. [Disaster Recovery and Business Continuity](#10-disaster-recovery-and-business-continuity)
11. [Future Architecture Considerations](#11-future-architecture-considerations)
12. [Documentation References](#12-documentation-references)
13. [Appendix](#13-appendix)

---

## 1. Executive Summary

The CardDemo application has been comprehensively modernized from a legacy IBM mainframe technology stack (COBOL, CICS, VSAM) to a cloud-native Java Spring Boot architecture. This transformation preserves 100% functional equivalence while enabling modern DevOps practices, horizontal scalability, and cloud deployment flexibility.

### Key Transformation Metrics

- **28 COBOL programs** → 28 Java Spring Boot service classes
- **17 BMS 3270 screens** → 17 React 18 functional components
- **5 VSAM KSDS files** → PostgreSQL 15 relational database with foreign key constraints
- **20 JCL batch jobs** → Spring Batch jobs with Kubernetes CronJob scheduling
- **Monolithic mainframe** → Microservices-capable architecture
- **Response time target**: <200ms (maintained from legacy system)
- **Peak throughput**: 10,000 TPS (preserved)

### Transformation Approach

The migration followed a lift-and-shift-and-refactor strategy that decomposes the monolithic mainframe COBOL/CICS application into a modern microservices-ready architecture while maintaining strict functional equivalence through precise architectural mapping and pattern translation. Every business rule, calculation, and data validation from the original COBOL implementation has been preserved in the Java implementation.

### Key Architectural Benefits

1. **Cloud-Native Deployment**: Containerized application deployable on any Kubernetes cluster
2. **Horizontal Scalability**: Stateless REST APIs enable horizontal pod autoscaling
3. **Modern DevOps**: CI/CD pipelines with automated testing and deployment
4. **Enhanced Observability**: Prometheus metrics, structured logging, distributed tracing
5. **Developer Productivity**: Modern IDE support, comprehensive API documentation
6. **Maintainability**: Clean separation of concerns, well-defined layer boundaries
7. **Technology Currency**: LTS versions of Java 21, Spring Boot 3.x, PostgreSQL 15+

---

## 2. Architecture Overview

### 2.1 Legacy Mainframe Architecture (Before Migration)

The CardDemo application originally ran on an IBM z/OS mainframe with the following technology stack:

```
┌─────────────────────────────────────────────────────────────┐
│                    User Interface Layer                      │
│  3270 Terminal Emulator → BMS Mapsets (17 screens)          │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              Transaction Processing Layer                     │
│  CICS Transaction Server → Online Programs (17)             │
│  Pseudo-conversational processing, COMMAREA state mgmt      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                Business Logic Layer                          │
│  COBOL Programs (28 total: 17 online + 11 batch)            │
│  - COSGN00C (Sign-on/Authentication)                        │
│  - COMEN01C (Menu Navigation)                               │
│  - COACTVWC/COACTUPC (Account View/Update)                  │
│  - COCRDLIC (Card List Management)                          │
│  - COBIL00C (Bill Payment Processing)                       │
│  - CBACT04C (Interest Calculation Batch)                    │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   Data Access Layer                          │
│  VSAM File Handler → KSDS Files (5 primary)                 │
│  - CUSTDAT (Customer Data)                                  │
│  - ACCTDAT (Account Data)                                   │
│  - CARDDAT (Card Data)                                      │
│  - TRANSACT (Transaction Data)                              │
│  - USRSEC (User Security)                                   │
└─────────────────────────────────────────────────────────────┘
```

**Legacy Architecture Characteristics:**

- **Programming Language**: COBOL (Common Business-Oriented Language)
- **Transaction Manager**: CICS (Customer Information Control System)
- **Data Storage**: VSAM (Virtual Storage Access Method) KSDS (Key-Sequenced Data Sets)
- **Batch Processing**: JCL (Job Control Language) with system utilities
- **Security**: RACF (Resource Access Control Facility) with file-based user data
- **User Interface**: 3270 terminal emulation with BMS (Basic Mapping Support) screens

**Key Limitations of Legacy Architecture:**

1. Vertical scaling only - limited by mainframe LPAR resources
2. Proprietary technology stack with vendor lock-in
3. Limited developer pool with COBOL expertise
4. Difficult integration with modern cloud services
5. High operational costs for mainframe compute resources
6. Limited DevOps and CI/CD automation capabilities

### 2.2 Modern Cloud-Native Architecture (After Migration)

The modernized CardDemo application uses a layered, cloud-native architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                    User Interface Layer                      │
│  React 18 SPA → Functional Components (17 screens)          │
│  React Router, Redux State Management, Material-UI          │
└─────────────────────────────────────────────────────────────┘
                            ↓ HTTPS/REST
┌─────────────────────────────────────────────────────────────┐
│                     API Gateway Layer                        │
│  Spring Cloud Gateway → Route Management, Load Balancing    │
│  JWT Token Validation, Rate Limiting, API Composition       │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              Microservices Business Layer                    │
│  Spring Boot 3.2 REST Controllers + Service Classes          │
│  - AuthenticationService (← COSGN00C)                       │
│  - MenuNavigationService (← COMEN01C)                       │
│  - AccountViewService/AccountUpdateService (← COACTVWC/UPC) │
│  - CardListService (← COCRDLIC)                             │
│  - BillPaymentService (← COBIL00C)                          │
│  - InterestCalculationJob (← CBACT04C)                      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              Data Access Layer (JPA/Hibernate)               │
│  Spring Data JPA Repositories                               │
│  - CustomerRepository (← CUSTDAT VSAM)                      │
│  - AccountRepository (← ACCTDAT VSAM)                       │
│  - CardRepository (← CARDDAT VSAM)                          │
│  - TransactionRepository (← TRANSACT VSAM)                  │
│  - UserSecurityRepository (← USRSEC VSAM)                   │
└─────────────────────────────────────────────────────────────┘
                            ↓ JDBC
┌─────────────────────────────────────────────────────────────┐
│                   Database Layer                             │
│  PostgreSQL 15+ → Relational Tables with Indexes            │
│  - customer, account, card, transaction, user_security      │
│  B-tree indexes matching VSAM KSDS key access patterns      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                Batch Processing Layer                        │
│  Spring Batch 5.x Jobs → Kubernetes CronJobs                │
│  - AccountDataLoadJob, InterestCalculationJob               │
│  - DailyTransactionProcessingJob, StatementGenerationJob    │
│  Chunk-oriented processing, JobRepository tracking          │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                    Security Layer                            │
│  Spring Security 6.x → JWT Authentication                   │
│  UserDetailsService, @PreAuthorize, ROLE_USER/ROLE_ADMIN    │
│  Password encryption (BCrypt), Session management (Redis)   │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│              Infrastructure & Deployment Layer               │
│  Docker Containers → Kubernetes Orchestration               │
│  Prometheus Monitoring, ELK Logging, Helm Charts            │
└─────────────────────────────────────────────────────────────┘
```

**Modern Architecture Characteristics:**

- **Programming Languages**: Java 21 (backend), JavaScript/React (frontend)
- **Application Framework**: Spring Boot 3.2 with Spring ecosystem
- **Data Storage**: PostgreSQL 15 relational database with ACID compliance
- **Batch Processing**: Spring Batch 5.x with Kubernetes CronJob scheduling
- **Security**: Spring Security 6.x with JWT token-based authentication
- **User Interface**: React 18 single-page application with Material-UI components
- **Deployment**: Docker containers orchestrated by Kubernetes

**Key Advantages of Modern Architecture:**

1. Horizontal scaling with Kubernetes pod autoscaling
2. Open-source technology stack with broad community support
3. Large pool of Java and React developers
4. Native integration with cloud services (AWS, Azure, GCP)
5. Cost-effective compute resources with pay-as-you-go pricing
6. Comprehensive CI/CD automation with GitHub Actions

### 2.3 Architectural Transformation Mapping

The following table maps legacy mainframe components to their modern equivalents:

| Legacy Component | Modern Equivalent | Transformation Strategy |
|-----------------|-------------------|------------------------|
| COBOL Programs | Java Service Classes | Business logic preservation with OOP refactoring |
| CICS Transactions | REST API Endpoints | Stateless microservices with session management |
| VSAM KSDS Files | PostgreSQL Tables | Relational schema with referential integrity |
| BMS 3270 Screens | React Components | Modern web UI with equivalent workflows |
| JCL Batch Jobs | Spring Batch + K8s CronJobs | Containerized batch with chunk processing |
| RACF Security | Spring Security + JWT | Token-based auth with role-based access |
| COMMAREA Structures | JSON DTOs | RESTful request/response objects |
| COBOL COMP-3 Decimals | Java BigDecimal | Precision-preserving decimal arithmetic |
| CICS SYNCPOINT | @Transactional Annotation | Declarative transaction management |
| VSAM Primary Keys | PostgreSQL Primary Keys | B-tree indexed unique constraints |
| VSAM Alternate Indexes | PostgreSQL Secondary Indexes | Multi-column composite indexes |

---

## 3. Technology Stack

### 3.1 Backend Technology Choices

The backend technology stack was carefully selected to provide enterprise-grade reliability, performance, and maintainability while ensuring 100% functional equivalence with the legacy COBOL implementation.

| Component | Technology | Version | Rationale |
|-----------|-----------|---------|-----------|
| Programming Language | Java | 21 LTS | Long-term support, performance improvements, virtual threads support, pattern matching |
| Application Framework | Spring Boot | 3.2.1 | Industry standard, comprehensive ecosystem, production-ready features, auto-configuration |
| Data Access | Spring Data JPA | 3.2.1 | Type-safe repository pattern, automatic query generation, transaction management |
| Batch Processing | Spring Batch | 5.1.1 | Proven batch processing framework, chunk-oriented processing, restart capability |
| Security | Spring Security | 6.2.1 | Enterprise-grade authentication and authorization, method-level security |
| Database | PostgreSQL | 15.5 | ACID compliance, JSON support, excellent performance, open-source |
| Database Migration | Flyway | 10.4.1 | Version-controlled schema evolution, repeatable migrations, rollback support |
| Connection Pooling | HikariCP | 5.1.0 | Fastest JDBC connection pool, default in Spring Boot, production-proven |
| Caching/Session | Redis | 7.2 | In-memory data store, session management, distributed caching, pub/sub |
| API Documentation | SpringDoc OpenAPI | 2.3.0 | Automatic OpenAPI spec generation, Swagger UI integration, request validation |
| Logging | Logback + SLF4J | 1.4.14 / 2.0.11 | Flexible logging framework, JSON structured logging, MDC support |
| Monitoring | Micrometer + Prometheus | 1.12.2 | Metrics collection, dimensional data model, PromQL query language |
| JSON Processing | Jackson | 2.16.1 | High-performance JSON parsing, flexible configuration, Java 8 date/time support |
| Validation | Hibernate Validator | 8.0.1.Final | JSR-380 Bean Validation 2.0 implementation, custom validators |
| Utilities | Apache Commons Lang | 3.14.0 | String manipulation, number utilities, date/time operations |

**Technology Selection Criteria:**

1. **Spring Boot 3.2.1**: Selected for its comprehensive ecosystem, production-ready features (Actuator), and excellent documentation. Spring Boot's auto-configuration reduces boilerplate code while maintaining flexibility.

2. **Java 21 LTS**: Chosen for long-term support (until 2029), virtual threads for improved concurrency, pattern matching for cleaner code, and record types for immutable DTOs.

3. **PostgreSQL 15**: Selected over MySQL for superior JSON support, window functions, CTEs (Common Table Expressions), and better handling of complex queries. Open-source with no licensing costs.

4. **Spring Data JPA**: Eliminates repetitive JDBC code, provides type-safe queries with JPQL, and integrates seamlessly with Spring's transaction management.

5. **Spring Batch 5.1**: Industry-standard batch processing framework with chunk-oriented processing, automatic retry/skip logic, and comprehensive job execution tracking.

6. **Redis 7.2**: Chosen for session management due to its speed (sub-millisecond latency), support for distributed sessions, and automatic expiration of session data.

### 3.2 Frontend Technology Choices

The frontend stack prioritizes developer experience, component reusability, and modern user interface patterns while maintaining the familiar workflows from the legacy 3270 terminal interface.

| Component | Technology | Version | Rationale |
|-----------|-----------|---------|-----------|
| UI Framework | React | 18.2.0 | Component-based architecture, virtual DOM performance, extensive ecosystem |
| Routing | React Router | 6.21.1 | Declarative routing, nested routes support, programmatic navigation |
| State Management | Redux Toolkit | 2.0.1 | Predictable state container, DevTools integration, simplified Redux API |
| HTTP Client | Axios | 1.6.5 | Promise-based HTTP client, request/response interceptors, automatic JSON transformation |
| UI Components | Material-UI | 5.15.3 | Comprehensive component library, accessibility built-in, customizable themes |
| Form Management | Formik | 2.4.5 | Form state management, validation integration, field-level error handling |
| Schema Validation | Yup | 1.3.3 | Object schema validation, type-safe validation rules, async validation |
| Date Utilities | date-fns | 3.0.6 | Modern date manipulation, tree-shakeable, immutable operations |
| Charts | Recharts | 2.10.3 | React-first charting library, composable API, responsive charts |
| Build Tool | Vite | 5.0.11 | Fast HMR (Hot Module Replacement), optimized production builds, ES modules |
| Testing | Vitest + Testing Library | 1.1.3 / 14.1.2 | Fast unit testing, React component testing, user-centric queries |
| Linting | ESLint | 8.56.0 | Code quality enforcement, React-specific rules, consistent code style |
| Formatting | Prettier | 3.1.1 | Automatic code formatting, consistent code style across team |

**Frontend Technology Rationale:**

1. **React 18**: Chosen for its component-based architecture that naturally maps to BMS screen definitions, extensive community support, and rich ecosystem of libraries.

2. **Redux Toolkit**: Simplifies Redux usage with opinionated defaults, eliminates boilerplate code, and provides excellent DevTools for debugging state changes.

3. **Material-UI 5**: Provides comprehensive pre-built components that accelerate development, built-in accessibility features, and customizable themes matching brand guidelines.

4. **Vite**: Selected over Webpack/Create React App for significantly faster development server startup, instant Hot Module Replacement, and optimized production builds.

5. **Formik + Yup**: Industry-standard combination for form handling and validation, reduces form-related boilerplate, and provides consistent validation patterns.

### 3.3 DevOps and Infrastructure

The DevOps stack enables continuous integration, automated deployment, and comprehensive observability across all environments.

| Component | Technology | Version | Rationale |
|-----------|-----------|---------|-----------|
| Containerization | Docker | 24.x | Standard container format, multi-stage builds, layer caching, reproducible builds |
| Orchestration | Kubernetes | 1.28+ | Container orchestration, self-healing, horizontal scaling, declarative configuration |
| CI/CD | GitHub Actions | N/A | Integrated with repository, matrix builds, secrets management, extensive marketplace |
| Container Registry | Docker Hub / ECR | N/A | Image storage and distribution, vulnerability scanning, role-based access |
| Monitoring | Prometheus + Grafana | 2.48.1 / 10.2.3 | Metrics collection, time-series database, visualization dashboards, alerting |
| Logging | ELK Stack | 8.11.3 | Centralized logging, log aggregation, full-text search, kibana visualization |
| Service Mesh | (Optional) Istio | 1.20+ | Advanced traffic management, security policies, observability (future enhancement) |
| Package Management | Helm | 3.x | Kubernetes package manager, templating, release management, rollback capability |

**Infrastructure Rationale:**

1. **Kubernetes 1.28+**: Industry-standard container orchestration platform, cloud-agnostic deployment, extensive ecosystem, and proven scalability.

2. **GitHub Actions**: Chosen for tight integration with source repository, simple YAML configuration, and extensive marketplace of pre-built actions for common tasks.

3. **Prometheus + Grafana**: De facto standard for Kubernetes monitoring, dimensional metrics model, powerful query language (PromQL), and extensive dashboard library.

4. **ELK Stack**: Comprehensive logging solution with powerful search capabilities, structured logging support, and excellent visualization tools.

---

## 4. Layered Architecture Design

The CardDemo application follows a strict layered architecture with clear separation of concerns. Each layer has well-defined responsibilities and communicates only with adjacent layers.

### 4.1 Presentation Layer (React Frontend)

**Responsibilities:**

- User interface rendering and interaction
- Client-side routing and navigation
- Form validation and user input handling
- State management for UI components
- API calls to backend services
- Error display and user feedback
- Session management and JWT token storage

**Key Components:**

```
frontend/src/
├── components/
│   ├── auth/LoginComponent.jsx          (← COSGN00 BMS map)
│   ├── menu/MainMenuComponent.jsx       (← COMEN01 BMS map)
│   ├── account/AccountViewComponent.jsx (← COACTVW BMS map)
│   ├── card/CardListComponent.jsx       (← COCRDLI BMS map)
│   └── transaction/TransactionListComponent.jsx (← COTRN00 BMS map)
├── services/
│   ├── authService.js       (API calls for authentication)
│   ├── accountService.js    (API calls for account operations)
│   └── apiClient.js         (Axios configuration with interceptors)
├── redux/
│   ├── store.js            (Redux store configuration)
│   └── slices/
│       ├── authSlice.js    (Authentication state)
│       └── accountSlice.js (Account data state)
└── utils/
    ├── formatters.js       (Data formatting utilities)
    └── validators.js       (Custom validation functions)
```

**Screen Navigation Flow:**

The React application preserves the navigation patterns from the legacy 3270 terminal interface:

1. **Login Screen** → Main Menu (on successful authentication)
2. **Main Menu** → Feature Screens (Account, Card, Transaction, etc.)
3. **Feature Screens** → Detail Screens → Update Screens
4. **All Screens** → Main Menu (via "Return" button, equivalent to PF3)

**State Management Pattern:**

Redux Toolkit manages global application state:

```javascript
// Example: Account state slice
const accountSlice = createSlice({
  name: 'account',
  initialState: { accounts: [], selectedAccount: null, loading: false },
  reducers: {
    setAccounts: (state, action) => { state.accounts = action.payload; },
    selectAccount: (state, action) => { state.selectedAccount = action.payload; }
  }
});
```

### 4.2 API Gateway Layer (Spring Cloud Gateway)

**Responsibilities:**

- Request routing to appropriate microservices
- JWT token validation and authentication
- Rate limiting and throttling
- CORS (Cross-Origin Resource Sharing) configuration
- API versioning support
- Request/response logging and tracing
- Circuit breaking and fault tolerance

**Gateway Configuration Example:**

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: lb://BACKEND-SERVICE
          predicates:
            - Path=/api/auth/**
        - id: account-service
          uri: lb://BACKEND-SERVICE
          predicates:
            - Path=/api/accounts/**
          filters:
            - name: JwtAuthentication
        - id: transaction-service
          uri: lb://BACKEND-SERVICE
          predicates:
            - Path=/api/transactions/**
          filters:
            - name: JwtAuthentication
            - name: RateLimit
              args:
                redis-rate-limiter.replenishRate: 100
                redis-rate-limiter.burstCapacity: 200
```

**JWT Token Validation Filter:**

The gateway validates JWT tokens before forwarding requests to backend services, ensuring that only authenticated requests reach the business logic layer.

### 4.3 Business Logic Layer (Spring Boot Services)

**Responsibilities:**

- Business rule implementation and enforcement
- Transaction coordination and management
- Data validation beyond basic field validation
- Integration with external systems
- Audit logging for regulatory compliance
- Exception handling and error recovery
- Complex business calculations (interest, fees, balances)

**Design Patterns Applied:**

1. **Service Pattern**: Each COBOL program maps to a dedicated service class, preserving program boundaries and responsibilities.

2. **Facade Pattern**: Complex operations involving multiple entities are exposed through simplified service interfaces.

3. **Strategy Pattern**: Interchangeable business rule implementations (e.g., different interest calculation strategies).

4. **Template Method Pattern**: Common processing patterns abstracted in base service classes.

**Service Layer Structure:**

```
com.carddemo.service/
├── AuthenticationService.java      (← COSGN00C)
├── MenuNavigationService.java      (← COMEN01C)
├── AccountViewService.java         (← COACTVWC)
├── AccountUpdateService.java       (← COACTUPC)
├── AccountCreationService.java     (← COACTADD)
├── CardListService.java            (← COCRDLIC)
├── CardDetailService.java          (← COCRDSLC)
├── CardUpdateService.java          (← COCRDUPC)
├── TransactionListService.java     (← COTRN00C)
├── TransactionCategoryService.java (← COTRN01C)
├── TransactionCreationService.java (← COTRN02C)
├── BillPaymentService.java         (← COBIL00C)
├── ReportMenuService.java          (← CORPT00C)
├── AdminService.java               (← COADM01C)
├── UserManagementService.java      (← COUSR00C)
└── UserProfileService.java         (← COUSR01C)
```

**Service Implementation Example:**

```java
@Service
@Transactional
public class AccountUpdateService {
    
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    
    @Autowired
    public AccountUpdateService(AccountRepository accountRepository, 
                                AuditService auditService) {
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }
    
    /**
     * Updates account information preserving COBOL COACTUPC business logic.
     * Transaction boundary equivalent to CICS SYNCPOINT in COBOL.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, 
                   rollbackFor = Exception.class)
    public AccountDTO updateAccount(String accountId, AccountUpdateRequest request) {
        // Validate account exists (COBOL: READ with NOT FOUND check)
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
        
        // Apply business rules (preserved from COBOL validation logic)
        validateCreditLimit(request.getCreditLimit(), account.getCurrentBalance());
        
        // Update fields (COBOL: MOVE statements)
        account.setCreditLimit(request.getCreditLimit());
        account.setCashCreditLimit(request.getCashCreditLimit());
        account.setExpirationDate(request.getExpirationDate());
        
        // Save to database (COBOL: REWRITE record)
        Account updatedAccount = accountRepository.save(account);
        
        // Audit logging (COBOL: LOG-TRANSACTION call)
        auditService.logAccountUpdate(accountId, request);
        
        return toDTO(updatedAccount);
    }
    
    private void validateCreditLimit(BigDecimal newLimit, BigDecimal currentBalance) {
        if (newLimit.compareTo(currentBalance) < 0) {
            throw new BusinessValidationException(
                "Credit limit cannot be less than current balance");
        }
    }
}
```

**Transaction Management:**

Spring's `@Transactional` annotation provides declarative transaction management equivalent to CICS SYNCPOINT:

- **Isolation.READ_COMMITTED**: Prevents dirty reads, equivalent to CICS default isolation
- **rollbackFor = Exception.class**: Automatic rollback on any exception
- Transaction boundaries match COBOL program SYNCPOINT locations

### 4.4 Data Access Layer (Spring Data JPA)

**Responsibilities:**

- Database CRUD (Create, Read, Update, Delete) operations
- Query execution and optimization
- Entity lifecycle management
- Transaction participation (in coordination with service layer)
- Custom query implementations for complex scenarios
- Database connection management via HikariCP pool

**Repository Pattern Implementation:**

Spring Data JPA repositories replace COBOL file I/O operations:

```java
@Repository
public interface AccountRepository extends JpaRepository<Account, String> {
    
    /**
     * VSAM READ by primary key → findById
     * Equivalent to: EXEC CICS READ FILE('ACCTDAT') RIDFLD(account-id)
     */
    Optional<Account> findById(String accountId);
    
    /**
     * VSAM sequential read with filter → custom query
     * Equivalent to: READ FILE ACCTDAT NEXT WHERE ACCT-CUST-ID = customer-id
     */
    @Query("SELECT a FROM Account a WHERE a.customerId = :customerId")
    List<Account> findByCustomerId(@Param("customerId") String customerId);
    
    /**
     * VSAM alternate index access → custom query with index
     * Equivalent to: AIX read on ACCT-STATUS
     */
    @Query("SELECT a FROM Account a WHERE a.status = :status ORDER BY a.openDate DESC")
    List<Account> findByStatus(@Param("status") AccountStatus status);
    
    /**
     * VSAM update → save (update if exists)
     * Equivalent to: EXEC CICS REWRITE FILE('ACCTDAT')
     */
    @Modifying
    @Query("UPDATE Account a SET a.currentBalance = :balance WHERE a.accountId = :accountId")
    void updateBalance(@Param("accountId") String accountId, 
                       @Param("balance") BigDecimal balance);
    
    /**
     * Complex query with aggregation (no direct VSAM equivalent)
     * Leverages relational database capabilities
     */
    @Query("SELECT a.status, COUNT(a), SUM(a.currentBalance) " +
           "FROM Account a GROUP BY a.status")
    List<Object[]> getAccountStatisticsByStatus();
}
```

**Entity Mapping Example:**

JPA entities map directly to COBOL copybook structures:

```java
@Entity
@Table(name = "account")
public class Account {
    
    // COBOL: 01 ACCOUNT-RECORD.
    //        05 ACCT-ID PIC X(11).
    @Id
    @Column(name = "account_id", length = 11)
    private String accountId;
    
    // COBOL: 05 ACCT-CUST-ID PIC 9(9).
    @Column(name = "customer_id", length = 9, nullable = false)
    private String customerId;
    
    // COBOL: 05 ACCT-CURR-BAL PIC S9(13)V99 COMP-3.
    @Column(name = "current_balance", precision = 15, scale = 2)
    private BigDecimal currentBalance;
    
    // COBOL: 05 ACCT-CREDIT-LIMIT PIC S9(7)V99 COMP-3.
    @Column(name = "credit_limit", precision = 9, scale = 2)
    private BigDecimal creditLimit;
    
    // Foreign key relationship (VSAM cross-reference file equivalent)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false)
    private Customer customer;
}
```

**Query Optimization:**

Database indexes are strategically placed to match VSAM access patterns:

- **Primary Key Index**: B-tree index on `account_id` (VSAM primary key equivalent)
- **Foreign Key Index**: Index on `customer_id` for join optimization
- **Status Index**: Index on `status` for filtering active/closed accounts
- **Composite Index**: Index on `(customer_id, status)` for common query patterns

### 4.5 Batch Processing Layer (Spring Batch)

**Responsibilities:**

- Scheduled job execution via Kubernetes CronJobs
- Large dataset processing with chunk-oriented pattern
- Job restart and recovery from failures
- Skip logic for error records
- Batch reporting and job execution monitoring
- Transaction management for batch operations

**Spring Batch Job Architecture:**

```
Batch Job (e.g., Interest Calculation)
├── Job Configuration (Java Config)
├── Step 1: Read Accounts
│   ├── ItemReader (Database - JPA)
│   ├── ItemProcessor (Calculate Interest)
│   └── ItemWriter (Update Balance)
├── Step 2: Generate Report
│   ├── ItemReader (Processed Accounts)
│   ├── ItemProcessor (Format Report Data)
│   └── ItemWriter (File or Database)
└── Step 3: Send Notifications
    ├── ItemReader (Accounts with Changes)
    ├── ItemProcessor (Create Notifications)
    └── ItemWriter (Notification Service)
```

**Job Configuration Example (Interest Calculation):**

```java
@Configuration
@EnableBatchProcessing
public class InterestCalculationJobConfig {
    
    @Bean
    public Job interestCalculationJob(JobRepository jobRepository,
                                      Step readAccountsStep,
                                      Step calculateInterestStep,
                                      Step updateBalancesStep,
                                      Step generateReportStep) {
        return new JobBuilder("interestCalculationJob", jobRepository)
            .incrementer(new RunIdIncrementer())
            .start(readAccountsStep)
            .next(calculateInterestStep)
            .next(updateBalancesStep)
            .next(generateReportStep)
            .build();
    }
    
    @Bean
    public Step calculateInterestStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      ItemReader<Account> accountReader,
                                      ItemProcessor<Account, Account> interestProcessor,
                                      ItemWriter<Account> accountWriter) {
        return new StepBuilder("calculateInterestStep", jobRepository)
            .<Account, Account>chunk(1000, transactionManager)
            .reader(accountReader)
            .processor(interestProcessor)
            .writer(accountWriter)
            .faultTolerant()
            .skip(DataAccessException.class)
            .skipLimit(100)
            .build();
    }
}
```

**ItemProcessor Example (Interest Calculation Logic):**

```java
@Component
public class InterestCalculationProcessor implements ItemProcessor<Account, Account> {
    
    /**
     * Calculates interest preserving COBOL CBACT04C business logic.
     * Uses BigDecimal for COMP-3 precision equivalence.
     */
    @Override
    public Account process(Account account) throws Exception {
        // COBOL: COMPUTE INTEREST-AMT = ACCT-CURR-BAL * INTEREST-RATE / 365.
        BigDecimal dailyRate = account.getInterestRate()
            .divide(new BigDecimal("365"), 8, RoundingMode.HALF_UP);
        
        BigDecimal interestAmount = account.getCurrentBalance()
            .multiply(dailyRate)
            .setScale(2, RoundingMode.HALF_UP);
        
        // COBOL: ADD INTEREST-AMT TO ACCT-CURR-BAL.
        BigDecimal newBalance = account.getCurrentBalance()
            .add(interestAmount);
        
        account.setCurrentBalance(newBalance);
        account.setLastInterestDate(LocalDate.now());
        
        return account;
    }
}
```

**Kubernetes CronJob Scheduling:**

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: interest-calculation-job
spec:
  schedule: "0 2 * * *"  # Daily at 2 AM
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: batch-job
            image: carddemo-backend:latest
            command: ["java", "-jar", "app.jar"]
            args: ["--spring.batch.job.names=interestCalculationJob"]
            env:
            - name: SPRING_PROFILES_ACTIVE
              value: "production"
          restartPolicy: OnFailure
          concurrencyPolicy: Forbid  # Prevent concurrent executions
```

---

## 5. Data Architecture

### 5.1 Entity Relationship Diagram

The CardDemo data model represents the core business entities and their relationships:

```
┌──────────────┐
│   Customer   │
│──────────────│
│ customer_id  │ PK
│ first_name   │
│ last_name    │
│ ssn          │
│ dob          │
│ address      │
│ city         │
│ state        │
│ zip_code     │
│ phone_number │
│ fico_score   │
└──────────────┘
       │ 1
       │
       │ N
       ↓
┌──────────────┐
│   Account    │
│──────────────│
│ account_id   │ PK
│ customer_id  │ FK → Customer
│ status       │
│ open_date    │
│ current_bal  │
│ credit_limit │
│ cash_limit   │
│ expiry_date  │
│ reissue_date │
└──────────────┘
       │ 1
       │
       │ N
       ↓
┌──────────────┐       ┌──────────────┐
│     Card     │       │ Transaction  │
│──────────────│       │──────────────│
│ card_number  │ PK    │ trans_id     │ PK
│ account_id   │ FK    │ account_id   │ FK → Account
│ card_type    │       │ card_number  │ FK → Card
│ expiry_date  │       │ trans_type   │ FK → TransactionType
│ status       │       │ trans_categ  │ FK → TransactionCategory
│ cvv_code     │       │ amount       │
│ embossed_name│       │ trans_date   │
│ issue_date   │       │ merchant     │
└──────────────┘       │ description  │
                       │ orig_trans_id│
                       └──────────────┘

┌──────────────────┐    ┌────────────────────┐
│ TransactionType  │    │ TransactionCategory│
│──────────────────│    │────────────────────│
│ type_code        │ PK │ category_code      │ PK
│ description      │    │ description        │
│ debit_credit_ind │    │ category_type      │
└──────────────────┘    └────────────────────┘

┌──────────────────┐
│  UserSecurity    │
│──────────────────│
│ user_id          │ PK
│ username         │ UNIQUE
│ password_hash    │
│ first_name       │
│ last_name        │
│ user_type        │ ('R', 'A')
│ last_login       │
│ account_locked   │
└──────────────────┘
```

**Key Relationships:**

1. **Customer to Account**: One-to-Many - A customer can have multiple accounts
2. **Account to Card**: One-to-Many - An account can have multiple cards
3. **Account to Transaction**: One-to-Many - An account has many transactions
4. **Card to Transaction**: One-to-Many - A card has many transactions
5. **TransactionType to Transaction**: One-to-Many - Reference data relationship
6. **TransactionCategory to Transaction**: One-to-Many - Reference data relationship

**Referential Integrity:**

All foreign key relationships are enforced at the database level with appropriate ON DELETE and ON UPDATE actions:

- Customer deletion restricted if accounts exist
- Account deletion cascades to cards and transactions
- Card deletion cascades to transactions
- Reference data (types, categories) deletion restricted if referenced

### 5.2 Database Schema Design

**Customer Table:**

```sql
CREATE TABLE customer (
    customer_id VARCHAR(9) PRIMARY KEY,
    first_name VARCHAR(25) NOT NULL,
    last_name VARCHAR(25) NOT NULL,
    date_of_birth DATE NOT NULL,
    ssn VARCHAR(9) UNIQUE,
    fico_credit_score INTEGER CHECK (fico_credit_score BETWEEN 300 AND 850),
    address_line1 VARCHAR(50),
    address_line2 VARCHAR(50),
    city VARCHAR(25),
    state VARCHAR(2),
    zip_code VARCHAR(10),
    phone_number VARCHAR(15),
    email VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes matching VSAM access patterns
CREATE INDEX idx_customer_ssn ON customer(ssn);
CREATE INDEX idx_customer_name ON customer(last_name, first_name);
CREATE INDEX idx_customer_created ON customer(created_at DESC);
```

**Account Table:**

```sql
CREATE TABLE account (
    account_id VARCHAR(11) PRIMARY KEY,
    customer_id VARCHAR(9) NOT NULL,
    account_status CHAR(1) NOT NULL CHECK (account_status IN ('A', 'C')),
    open_date DATE NOT NULL,
    current_balance NUMERIC(15,2) DEFAULT 0.00,
    credit_limit NUMERIC(15,2) NOT NULL,
    cash_credit_limit NUMERIC(15,2) NOT NULL,
    expiration_date VARCHAR(10),
    reissue_date VARCHAR(10),
    interest_rate NUMERIC(8,5) DEFAULT 0.00,
    last_interest_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_account_customer FOREIGN KEY (customer_id)
        REFERENCES customer(customer_id) ON DELETE RESTRICT
);

-- Indexes for performance
CREATE INDEX idx_account_customer ON account(customer_id);
CREATE INDEX idx_account_status ON account(account_status);
CREATE INDEX idx_account_open_date ON account(open_date DESC);
CREATE INDEX idx_account_balance ON account(current_balance);
```

**Card Table:**

```sql
CREATE TABLE card (
    card_number VARCHAR(16) PRIMARY KEY,
    account_id VARCHAR(11) NOT NULL,
    card_type VARCHAR(10) NOT NULL CHECK (card_type IN ('VISA', 'MASTERCARD', 'AMEX')),
    expiry_date VARCHAR(10) NOT NULL,
    card_status CHAR(1) NOT NULL CHECK (card_status IN ('A', 'E', 'B', 'C')),
    cvv_code VARCHAR(3),
    embossed_name VARCHAR(50),
    issue_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_card_account FOREIGN KEY (account_id)
        REFERENCES account(account_id) ON DELETE CASCADE
);

-- Indexes
CREATE INDEX idx_card_account ON card(account_id);
CREATE INDEX idx_card_status ON card(card_status);
CREATE INDEX idx_card_expiry ON card(expiry_date);
```

**Transaction Table:**

```sql
CREATE TABLE transaction (
    transaction_id VARCHAR(16) PRIMARY KEY,
    account_id VARCHAR(11) NOT NULL,
    card_number VARCHAR(16),
    transaction_type_code VARCHAR(2) NOT NULL,
    transaction_category_code VARCHAR(4) NOT NULL,
    transaction_amount NUMERIC(9,2) NOT NULL,
    transaction_date TIMESTAMP NOT NULL,
    merchant_name VARCHAR(50),
    merchant_city VARCHAR(25),
    merchant_zip VARCHAR(10),
    description VARCHAR(100),
    original_transaction_id VARCHAR(16),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trans_account FOREIGN KEY (account_id)
        REFERENCES account(account_id) ON DELETE CASCADE,
    CONSTRAINT fk_trans_card FOREIGN KEY (card_number)
        REFERENCES card(card_number) ON DELETE SET NULL,
    CONSTRAINT fk_trans_type FOREIGN KEY (transaction_type_code)
        REFERENCES transaction_type(type_code),
    CONSTRAINT fk_trans_category FOREIGN KEY (transaction_category_code)
        REFERENCES transaction_category(category_code)
);

-- Indexes for common query patterns
CREATE INDEX idx_trans_account ON transaction(account_id, transaction_date DESC);
CREATE INDEX idx_trans_card ON transaction(card_number, transaction_date DESC);
CREATE INDEX idx_trans_date ON transaction(transaction_date DESC);
CREATE INDEX idx_trans_type ON transaction(transaction_type_code);
CREATE INDEX idx_trans_category ON transaction(transaction_category_code);
```

**UserSecurity Table:**

```sql
CREATE TABLE user_security (
    user_id VARCHAR(8) PRIMARY KEY,
    username VARCHAR(10) UNIQUE NOT NULL,
    password_hash VARCHAR(60) NOT NULL,  -- BCrypt hash
    first_name VARCHAR(25) NOT NULL,
    last_name VARCHAR(25) NOT NULL,
    user_type CHAR(1) NOT NULL CHECK (user_type IN ('R', 'A')),
    last_login_timestamp TIMESTAMP,
    account_locked BOOLEAN DEFAULT FALSE,
    failed_login_attempts INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_username ON user_security(username);
CREATE INDEX idx_user_type ON user_security(user_type);
```

### 5.3 Data Type Precision (CRITICAL)

Maintaining numeric precision is critical for financial calculations. COBOL COMP-3 packed decimal fields are mapped to PostgreSQL NUMERIC types with explicit precision and scale:

| COBOL Definition | PostgreSQL Column | Java Type | Notes |
|-----------------|------------------|-----------|-------|
| PIC S9(13)V99 COMP-3 | NUMERIC(15,2) | BigDecimal | Account balance, credit limit - 15 total digits, 2 decimal places |
| PIC S9(7)V99 COMP-3 | NUMERIC(9,2) | BigDecimal | Transaction amounts - 9 total digits, 2 decimal places |
| PIC S9(3)V9(5) COMP-3 | NUMERIC(8,5) | BigDecimal | Interest rates - 8 total digits, 5 decimal places (e.g., 15.75000%) |
| PIC 9(9) | VARCHAR(9) | String | Customer ID, Account ID - leading zeros preserved |
| PIC X(16) | VARCHAR(16) | String | Card numbers, transaction IDs |
| PIC 9(8) | DATE | LocalDate | Date fields - stored as DATE type |

**Critical BigDecimal Usage Rules:**

All financial calculations MUST use `BigDecimal` with explicit scale and rounding mode to match COBOL COMP-3 behavior:

```java
// CORRECT: Always specify scale and rounding mode
BigDecimal balance = account.getCurrentBalance();
BigDecimal interestRate = new BigDecimal("0.15750");  // 15.750%
BigDecimal dailyRate = interestRate.divide(new BigDecimal("365"), 8, RoundingMode.HALF_UP);
BigDecimal interest = balance.multiply(dailyRate).setScale(2, RoundingMode.HALF_UP);

// INCORRECT: Never use floating-point for money
double balance = 1234.56;  // WRONG - precision loss
float interest = 0.05f;    // WRONG - rounding errors
```

**Rounding Mode Selection:**

- `RoundingMode.HALF_UP` is used throughout to match COBOL COMP-3 default rounding behavior
- This is equivalent to "round half away from zero" or "commercial rounding"
- Example: 2.125 rounds to 2.13, -2.125 rounds to -2.13

### 5.4 Data Migration Strategy

Migration from VSAM files to PostgreSQL follows a systematic approach:

**Phase 1: Schema Creation**

1. Execute Flyway migration scripts in order (V1 through V9)
2. Create all tables, indexes, and constraints
3. Load reference data (transaction types, categories)

**Phase 2: Data Extraction**

1. Export VSAM files to sequential files
2. Convert EBCDIC to UTF-8 encoding
3. Validate data integrity and format

**Phase 3: Data Transformation**

1. Parse COBOL copybook layouts to extract field definitions
2. Transform COMP-3 packed decimal to ASCII numeric
3. Handle date conversions (Lilian to ISO 8601)
4. Generate SQL INSERT statements

**Phase 4: Data Loading**

1. Execute bulk INSERT operations with COPY command
2. Validate row counts match VSAM record counts
3. Verify referential integrity constraints
4. Run checksums on critical fields (balances, amounts)

**Phase 5: Validation**

1. Compare sample records field-by-field
2. Verify aggregate calculations (sum of balances, transaction counts)
3. Test all foreign key relationships
4. Execute read-only queries against both systems for comparison

---

## 6. Security Architecture

### 6.1 Authentication Flow

The modernized application uses JWT (JSON Web Token) based authentication instead of RACF file-based security:

```
┌──────────┐                                    ┌──────────────┐
│  Client  │                                    │   Backend    │
│ (React)  │                                    │ (Spring Boot)│
└────┬─────┘                                    └──────┬───────┘
     │                                                 │
     │  POST /api/auth/login                          │
     │  {username, password}                          │
     │────────────────────────────────────────────────>│
     │                                                 │
     │                                                 │ 1. Validate credentials
     │                                                 │    via UserDetailsService
     │                                                 │ 2. Query user_security table
     │                                                 │ 3. Compare BCrypt hashes
     │                                                 │ 4. Generate JWT token
     │                                                 │
     │                 JWT Token                       │
     │<────────────────────────────────────────────────│
     │  {                                              │
     │    "token": "eyJhbGc...",                       │
     │    "userId": "USER0001",                        │
     │    "userType": "R",                             │
     │    "expiresIn": 86400                           │
     │  }                                              │
     │                                                 │
     │  Store token in localStorage                   │
     │                                                 │
     │  Subsequent API requests                       │
     │  Authorization: Bearer eyJhbGc...              │
     │────────────────────────────────────────────────>│
     │                                                 │
     │                                                 │ 1. Extract JWT from header
     │                                                 │ 2. Validate signature
     │                                                 │ 3. Check expiration
     │                                                 │ 4. Extract user claims
     │                                                 │ 5. Load authorities
     │                                                 │
     │                 Response                        │
     │<────────────────────────────────────────────────│
     │                                                 │
```

**JWT Token Structure:**

```json
{
  "header": {
    "alg": "HS256",
    "typ": "JWT"
  },
  "payload": {
    "sub": "USER0001",
    "username": "USER0001",
    "userType": "R",
    "authorities": ["ROLE_USER"],
    "iat": 1705320000,
    "exp": 1705406400
  },
  "signature": "..."
}
```

**Authentication Configuration:**

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter(), 
                            UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);  // Strength 12 for security
    }
}
```

### 6.2 Authorization Model

Role-based access control (RBAC) preserves the two-tier role model from the legacy USRSEC file:

**Role Mapping:**

| Legacy User Type | Spring Security Role | Authorities | Description |
|-----------------|---------------------|-------------|-------------|
| 'R' (Regular) | ROLE_USER | User operations | View and manage own accounts, cards, transactions |
| 'A' (Admin) | ROLE_ADMIN | Admin operations | User management, all account access, system configuration |

**Method-Level Security:**

```java
@Service
public class AccountService {
    
    /**
     * Regular users can view their own accounts.
     * Admins can view any account.
     */
    @PreAuthorize("hasRole('USER')")
    public AccountDTO viewAccount(String accountId, String requestingUserId) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
        
        // Additional check: users can only view their own accounts
        if (!hasRole("ADMIN") && !account.getBelongsToUser(requestingUserId)) {
            throw new AccessDeniedException("Cannot view other users' accounts");
        }
        
        return toDTO(account);
    }
    
    /**
     * Only admins can create new accounts.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public AccountDTO createAccount(AccountCreateRequest request) {
        // Admin-only operation
        Account account = new Account();
        account.setAccountId(generateAccountId());
        account.setCustomerId(request.getCustomerId());
        account.setCreditLimit(request.getCreditLimit());
        // ... additional setup
        return toDTO(accountRepository.save(account));
    }
    
    /**
     * Admins can update any user, users can update themselves.
     */
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.userId")
    public UserDTO updateUser(String userId, UserUpdateRequest request) {
        UserSecurity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        
        return toDTO(userRepository.save(user));
    }
}
```

**Access Control Matrix:**

| Operation | ROLE_USER | ROLE_ADMIN | Notes |
|-----------|-----------|------------|-------|
| View own account | ✓ | ✓ | User must own the account |
| View any account | ✗ | ✓ | Admin can view all accounts |
| Update own account | ✓ | ✓ | Limited fields for regular users |
| Create account | ✗ | ✓ | Admin-only operation |
| View own cards | ✓ | ✓ | Associated with user's account |
| Update own card | ✓ | ✓ | Status changes restricted |
| View own transactions | ✓ | ✓ | Date range limited for users |
| Create transaction | ✓ | ✓ | Balance validation enforced |
| List all users | ✗ | ✓ | Admin user management |
| Create user | ✗ | ✓ | Admin user management |
| Update any user | ✗ | ✓ | Admin user management |
| Delete user | ✗ | ✓ | Admin user management |
| View reports | ✗ | ✓ | Comprehensive system reports |
| Batch job execution | ✗ | ✓ | System-level operations |

### 6.3 Session Management

**Stateless JWT Approach:**

The application primarily uses stateless JWT tokens, but supports Redis-backed sessions for specific scenarios:

1. **JWT Token Storage**: Stored in browser localStorage
2. **Token Refresh**: New token issued before expiration
3. **Token Revocation**: Blacklist maintained in Redis for logout
4. **Session Timeout**: 24-hour token expiration
5. **Concurrent Sessions**: Multiple devices supported with separate tokens

**Redis Session Configuration:**

```yaml
spring:
  session:
    store-type: redis
    redis:
      flush-mode: on_save
      namespace: carddemo:sessions
    timeout: 24h
  redis:
    host: redis-service
    port: 6379
    password: ${REDIS_PASSWORD}
    ssl: true
```

**Session Security Features:**

1. **Automatic Expiration**: Redis TTL expires old sessions
2. **Session Fixation Protection**: New session ID on authentication
3. **CSRF Protection**: CSRF tokens for state-changing operations
4. **Secure Cookies**: HttpOnly, Secure, SameSite attributes
5. **Session Binding**: Bind session to IP address (optional)

### 6.4 Password Security

**BCrypt Password Hashing:**

```java
@Service
public class AuthenticationService {
    
    private final PasswordEncoder passwordEncoder;
    
    /**
     * Registers a new user with BCrypt password hashing.
     * Replaces COBOL file-based plain-text password storage.
     */
    public UserDTO registerUser(UserRegistrationRequest request) {
        // Validate password complexity
        validatePasswordComplexity(request.getPassword());
        
        UserSecurity user = new UserSecurity();
        user.setUserId(generateUserId());
        user.setUsername(request.getUsername());
        
        // BCrypt with strength 12 (2^12 = 4096 iterations)
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setUserType(UserType.ROLE_USER);
        
        return toDTO(userRepository.save(user));
    }
    
    private void validatePasswordComplexity(String password) {
        if (password.length() < 8) {
            throw new PasswordValidationException("Password must be at least 8 characters");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new PasswordValidationException("Password must contain uppercase letter");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new PasswordValidationException("Password must contain lowercase letter");
        }
        if (!password.matches(".*[0-9].*")) {
            throw new PasswordValidationException("Password must contain digit");
        }
    }
}
```

**Password Policy Enforcement:**

- **Minimum Length**: 8 characters
- **Complexity**: Must contain uppercase, lowercase, and digit
- **History**: Last 5 passwords cannot be reused
- **Expiration**: 90-day password expiration (configurable)
- **Lockout**: 5 failed attempts locks account for 30 minutes

### 6.5 Audit Logging

**Comprehensive Audit Trail:**

All security-relevant events are logged for regulatory compliance:

```java
@Aspect
@Component
public class AuditLoggingAspect {
    
    private final AuditLogRepository auditLogRepository;
    
    @AfterReturning(pointcut = "@annotation(auditable)", returning = "result")
    public void logAuditEvent(JoinPoint joinPoint, Auditable auditable, Object result) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        AuditLog log = new AuditLog();
        log.setUserId(auth.getName());
        log.setAction(auditable.action());
        log.setResource(joinPoint.getSignature().getName());
        log.setTimestamp(Instant.now());
        log.setIpAddress(getClientIpAddress());
        log.setUserAgent(getClientUserAgent());
        log.setSuccess(true);
        
        auditLogRepository.save(log);
    }
}
```

**Audit Log Events:**

- User login/logout
- Account creation/modification
- Card activation/deactivation
- Transaction creation
- User management operations
- Failed authentication attempts
- Authorization failures
- Data exports
- Configuration changes

---

## 7. Performance and Scalability

### 7.1 Performance Requirements

The modernized application maintains strict performance requirements equivalent to the legacy mainframe system:

| Metric | Target | Measurement Method |
|--------|--------|-------------------|
| API Response Time (95th percentile) | <200ms | Prometheus histogram metrics, load testing |
| API Response Time (99th percentile) | <500ms | High-percentile latency tracking |
| Batch Processing Window | <4 hours | Spring Batch execution time monitoring |
| Peak Throughput | 10,000 TPS | Concurrent user simulation, JMeter load tests |
| Concurrent Users | 150+ | Active session count, connection pool monitoring |
| Database Query Time | <50ms | Single record retrieval via primary key |
| Database Write Time | <100ms | Single record INSERT/UPDATE operation |
| Page Load Time (Frontend) | <3 seconds | Lighthouse performance audit, Core Web Vitals |

**Performance Testing Strategy:**

1. **Unit Performance Tests**: Individual method execution time validation
2. **Integration Performance Tests**: End-to-end API response time
3. **Load Tests**: Sustained load at peak TPS for 1 hour
4. **Stress Tests**: Gradual load increase until failure point
5. **Soak Tests**: Continuous operation for 24 hours to detect memory leaks

### 7.2 Scalability Strategy

**Horizontal Scaling (Preferred):**

The stateless architecture enables linear horizontal scaling:

```yaml
# Kubernetes Horizontal Pod Autoscaler (HPA)
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: backend-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: backend-deployment
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - type: Percent
        value: 100
        periodSeconds: 30
```

**Scaling Characteristics:**

- **Backend Services**: 3-10 pod replicas based on CPU/memory utilization
- **Frontend**: 2-5 pod replicas (primarily for availability)
- **Database**: Read replicas for query load distribution
- **Redis**: Redis Cluster for distributed caching and session storage

**Vertical Scaling (Complementary):**

Resource limits per pod ensure efficient resource utilization:

```yaml
resources:
  requests:
    memory: "512Mi"
    cpu: "500m"
  limits:
    memory: "2Gi"
    cpu: "2000m"
```

### 7.3 Caching Strategy

**Multi-Level Caching:**

1. **Application-Level Caching** (Spring Cache + Redis):

```java
@Service
public class AccountService {
    
    /**
     * Cache account details for 5 minutes.
     * Cache key: "accounts::{accountId}"
     */
    @Cacheable(value = "accounts", key = "#accountId")
    public AccountDTO getAccount(String accountId) {
        return accountRepository.findById(accountId)
            .map(this::toDTO)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
    
    /**
     * Evict cache entry on account update.
     */
    @CacheEvict(value = "accounts", key = "#accountId")
    public AccountDTO updateAccount(String accountId, AccountUpdateRequest request) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
        account.updateFrom(request);
        return toDTO(accountRepository.save(account));
    }
    
    /**
     * Cache transaction list with composite key.
     * Cache key: "transactions::{accountId}::{pageNumber}"
     */
    @Cacheable(value = "transactions", key = "#accountId + '::' + #page")
    public Page<TransactionDTO> getTransactions(String accountId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("transactionDate").descending());
        return transactionRepository.findByAccountId(accountId, pageable)
            .map(this::toDTO);
    }
}
```

2. **Database-Level Caching**:

- **Query Result Cache**: PostgreSQL shared_buffers (25% of RAM)
- **Prepared Statement Cache**: HikariCP statement cache (250 statements)
- **Index Cache**: B-tree indexes cached in memory

3. **HTTP-Level Caching** (Frontend):

```javascript
// React Query for client-side caching
const { data, isLoading } = useQuery(
  ['account', accountId],
  () => accountService.getAccount(accountId),
  {
    staleTime: 5 * 60 * 1000,  // 5 minutes
    cacheTime: 10 * 60 * 1000,  // 10 minutes
  }
);
```

**Cache Configuration:**

```yaml
spring:
  cache:
    type: redis
    redis:
      time-to-live: 300000  # 5 minutes
      cache-null-values: false
  redis:
    host: redis-service
    port: 6379
    password: ${REDIS_PASSWORD}
    timeout: 2000
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
```

**Cache Invalidation Strategy:**

- **Write-Through**: Update database and invalidate cache
- **TTL-Based**: Automatic expiration after 5 minutes
- **Event-Based**: Invalidate related caches on entity updates
- **Manual**: Explicit cache eviction for batch operations

### 7.4 Database Performance Optimization

**Connection Pooling (HikariCP):**

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 20000
      idle-timeout: 300000
      max-lifetime: 1200000
      auto-commit: false
      pool-name: CardDemoHikariPool
      register-mbeans: true
```

**Query Optimization:**

1. **Index Usage**: All foreign keys and frequently queried columns indexed
2. **Query Planning**: EXPLAIN ANALYZE for all complex queries
3. **Batch Operations**: JDBC batch insert/update for bulk operations
4. **Pagination**: LIMIT/OFFSET for large result sets
5. **Lazy Loading**: Fetch associations only when needed

**Database Configuration:**

```sql
-- PostgreSQL performance tuning
ALTER SYSTEM SET shared_buffers = '4GB';
ALTER SYSTEM SET effective_cache_size = '12GB';
ALTER SYSTEM SET maintenance_work_mem = '1GB';
ALTER SYSTEM SET checkpoint_completion_target = 0.9;
ALTER SYSTEM SET wal_buffers = '16MB';
ALTER SYSTEM SET default_statistics_target = 100;
ALTER SYSTEM SET random_page_cost = 1.1;
ALTER SYSTEM SET effective_io_concurrency = 200;
ALTER SYSTEM SET work_mem = '10MB';
ALTER SYSTEM SET min_wal_size = '1GB';
ALTER SYSTEM SET max_wal_size = '4GB';
```

### 7.5 Frontend Performance Optimization

**React Performance Techniques:**

1. **Code Splitting**: Dynamic imports for route-based code splitting
2. **Lazy Loading**: Defer loading of non-critical components
3. **Memoization**: React.memo for pure components
4. **Virtual Scrolling**: Efficient rendering of large lists
5. **Asset Optimization**: Image compression, minification, tree-shaking

**Build Optimization:**

```javascript
// vite.config.js
export default defineConfig({
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-redux': ['@reduxjs/toolkit', 'react-redux'],
          'vendor-ui': ['@mui/material', '@mui/icons-material'],
        }
      }
    },
    chunkSizeWarningLimit: 1000,
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: true
      }
    }
  }
});
```

**Core Web Vitals Targets:**

- **LCP (Largest Contentful Paint)**: <2.5s
- **FID (First Input Delay)**: <100ms
- **CLS (Cumulative Layout Shift)**: <0.1

---

## 8. Integration Points

### 8.1 External System Interfaces

CardDemo integrates with external systems, preserving exact data formats from the legacy COBOL implementation:

| Interface | Protocol | Data Format | Frequency | Purpose | Notes |
|-----------|---------|-------------|-----------|---------|-------|
| Payment Network | ISO 8583 | Binary | Real-time | Card authorization requests | Exact message format preserved |
| Bank Core System | SFTP | Fixed-width | Daily batch | Account settlement files | Record layout unchanged |
| Regulatory Reporting | HTTPS | CSV/XML | Monthly | Compliance reports | Field order and format preserved |
| Credit Bureau | HTTPS REST | JSON | On-demand | Credit score updates | New integration (enhancement) |

**CRITICAL**: All external interfaces maintain **exact** data formats from COBOL implementation. No changes allowed per migration requirements.

**Payment Network Integration (ISO 8583):**

```java
@Service
public class PaymentNetworkService {
    
    /**
     * Sends card authorization request to payment network.
     * Preserves exact ISO 8583 message format from COBOL implementation.
     */
    public AuthorizationResponse authorizeTransaction(TransactionRequest request) {
        // Build ISO 8583 message with exact field positions
        ISO8583Message message = new ISO8583Message();
        message.setMTI("0100");  // Authorization request
        message.setField(2, request.getCardNumber());
        message.setField(3, request.getProcessingCode());
        message.setField(4, request.getAmount().toString());
        message.setField(7, formatTransmissionDateTime());
        message.setField(11, generateSystemTraceNumber());
        message.setField(41, request.getTerminalId());
        message.setField(42, request.getMerchantId());
        
        // Send via socket connection (legacy protocol)
        byte[] response = paymentNetworkClient.send(message.pack());
        
        // Parse response preserving exact field interpretation
        return parseAuthorizationResponse(response);
    }
}
```

**Bank Core System File Interface:**

```java
@Service
public class SettlementFileGenerator {
    
    /**
     * Generates fixed-width settlement file matching COBOL file layout.
     * Preserves exact record layout for bank core system compatibility.
     */
    public void generateSettlementFile(LocalDate settlementDate) {
        List<Transaction> transactions = transactionRepository
            .findBySettlementDate(settlementDate);
        
        try (BufferedWriter writer = Files.newBufferedWriter(
                Paths.get("/exports/settlement_" + settlementDate + ".dat"))) {
            
            for (Transaction txn : transactions) {
                // Build fixed-width record matching COBOL copybook
                String record = String.format(
                    "%-11s%-16s%-2s%015d%-10s%-50s\n",
                    txn.getAccountId(),           // 11 chars
                    txn.getCardNumber(),          // 16 chars
                    txn.getTransactionTypeCode(), // 2 chars
                    txn.getAmount().movePointRight(2).longValue(), // 15 digits
                    formatDate(txn.getTransactionDate()), // 10 chars YYYY-MM-DD
                    txn.getMerchantName()         // 50 chars
                );
                writer.write(record);
            }
        }
    }
}
```

### 8.2 API Contract Documentation

**REST API Contracts:**

All REST endpoints are documented using OpenAPI 3.0 specification:

```java
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Account Management", description = "Account operations API")
public class AccountController {
    
    @Operation(
        summary = "Get account details",
        description = "Retrieves account information by account ID. " +
                     "Equivalent to COBOL COACTVWC program."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Account found",
            content = @Content(schema = @Schema(implementation = AccountDTO.class))),
        @ApiResponse(responseCode = "404", description = "Account not found"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @GetMapping("/{accountId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<AccountDTO> getAccount(
            @Parameter(description = "Account ID", example = "00000000001")
            @PathVariable String accountId) {
        AccountDTO account = accountService.getAccount(accountId);
        return ResponseEntity.ok(account);
    }
}
```

**API Documentation Access:**

- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON**: `http://localhost:8080/v3/api-docs`
- **ReDoc**: `http://localhost:8080/redoc`

See [docs/API.md](./API.md) for comprehensive endpoint documentation.

### 8.3 Event-Driven Integration (Future Enhancement)

**Kafka Integration (Planned):**

Future architecture evolution may introduce event-driven patterns:

```java
// Example: Transaction event publishing
@Service
public class TransactionEventPublisher {
    
    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;
    
    public void publishTransactionCreated(Transaction transaction) {
        TransactionEvent event = new TransactionEvent();
        event.setEventType("TRANSACTION_CREATED");
        event.setTransactionId(transaction.getTransactionId());
        event.setAccountId(transaction.getAccountId());
        event.setAmount(transaction.getAmount());
        event.setTimestamp(Instant.now());
        
        kafkaTemplate.send("transaction-events", transaction.getAccountId(), event);
    }
}
```

---

## 9. Monitoring and Observability

### 9.1 Application Metrics

**Prometheus Metrics Exposure:**

Spring Boot Actuator exposes comprehensive metrics for Prometheus scraping:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: carddemo
      environment: ${SPRING_PROFILES_ACTIVE}
```

**Key Metrics Collected:**

1. **JVM Metrics**:
   - Heap memory usage
   - Garbage collection events
   - Thread counts
   - Class loading

2. **HTTP Request Metrics**:
   - Request count by endpoint
   - Request duration histograms
   - Status code distribution
   - Concurrent request count

3. **Database Metrics**:
   - Connection pool usage
   - Query execution time
   - Transaction count
   - Slow query count

4. **Custom Business Metrics**:
   ```java
   @Service
   public class AccountService {
       
       private final Counter accountCreationCounter;
       private final Timer accountQueryTimer;
       
       public AccountService(MeterRegistry meterRegistry) {
           this.accountCreationCounter = meterRegistry.counter(
               "carddemo.accounts.created", 
               "type", "savings"
           );
           this.accountQueryTimer = meterRegistry.timer(
               "carddemo.accounts.query.duration"
           );
       }
       
       public AccountDTO createAccount(AccountCreateRequest request) {
           accountCreationCounter.increment();
           // ... account creation logic
       }
       
       public AccountDTO getAccount(String accountId) {
           return accountQueryTimer.record(() -> {
               return accountRepository.findById(accountId)
                   .map(this::toDTO)
                   .orElseThrow(() -> new AccountNotFoundException(accountId));
           });
       }
   }
   ```

**Grafana Dashboards:**

Pre-built dashboards for common monitoring scenarios:

1. **Application Overview**: Request rate, error rate, latency
2. **JVM Metrics**: Heap usage, GC activity, thread count
3. **Database Performance**: Query time, connection pool, slow queries
4. **Business Metrics**: Transactions processed, accounts created, user logins

### 9.2 Logging Strategy

**Structured JSON Logging:**

All logs are output in JSON format for easy parsing and indexing:

```json
{
  "timestamp": "2024-01-15T10:30:45.123Z",
  "level": "INFO",
  "thread": "http-nio-8080-exec-1",
  "logger": "com.carddemo.service.AccountService",
  "message": "Account retrieved successfully",
  "correlationId": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
  "userId": "USER0001",
  "accountId": "00000000001",
  "duration": 45,
  "environment": "production",
  "application": "carddemo-backend"
}
```

**Logback Configuration:**

```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeContext>true</includeContext>
            <includeMdc>true</includeMdc>
            <fieldNames>
                <timestamp>timestamp</timestamp>
                <version>[ignore]</version>
                <thread>thread</thread>
            </fieldNames>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="JSON" />
    </root>
    
    <!-- Debug logging for specific packages -->
    <logger name="com.carddemo.service" level="DEBUG" />
    <logger name="org.springframework.security" level="DEBUG" />
</configuration>
```

**Log Levels:**

- **ERROR**: System errors, exceptions, failed operations
- **WARN**: Degraded performance, deprecated features, unusual conditions
- **INFO**: Important business events, user actions, state changes
- **DEBUG**: Detailed execution flow, variable values (non-production only)
- **TRACE**: Very detailed execution flow (development only)

**MDC (Mapped Diagnostic Context):**

Correlation IDs and user context propagated through all log messages:

```java
@Component
public class LoggingFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        
        MDC.put("correlationId", correlationId);
        MDC.put("requestUri", request.getRequestURI());
        MDC.put("method", request.getMethod());
        
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

### 9.3 Health Checks

**Kubernetes Liveness and Readiness Probes:**

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 20
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3
```

**Custom Health Indicators:**

```java
@Component
public class DatabaseHealthIndicator implements HealthIndicator {
    
    private final DataSource dataSource;
    
    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return Health.up()
                    .withDetail("database", "PostgreSQL")
                    .withDetail("validationQuery", "SELECT 1")
                    .build();
            } else {
                return Health.down()
                    .withDetail("error", "Connection validation failed")
                    .build();
            }
        } catch (SQLException e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

**Health Check Endpoints:**

- `/actuator/health` - Overall application health
- `/actuator/health/liveness` - Kubernetes liveness probe
- `/actuator/health/readiness` - Kubernetes readiness probe
- `/actuator/health/db` - Database connectivity
- `/actuator/health/redis` - Redis connectivity
- `/actuator/health/diskSpace` - Disk space availability

### 9.4 Distributed Tracing (Future Enhancement)

**OpenTelemetry Integration (Planned):**

```java
@Configuration
public class TracingConfig {
    
    @Bean
    public OpenTelemetry openTelemetry() {
        return OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(
                        JaegerGrpcSpanExporter.builder()
                            .setEndpoint("http://jaeger:14250")
                            .build()
                    ).build())
                    .build()
            )
            .buildAndRegisterGlobal();
    }
}
```

---

## 10. Disaster Recovery and Business Continuity

### 10.1 Backup Strategy

**Database Backups:**

1. **Automated Daily Backups**:
   - Full database backup every 24 hours
   - Stored in S3/GCS/Azure Blob with lifecycle policies
   - Retention: 30 daily backups, 12 monthly backups, 7 yearly backups

2. **Transaction Log Shipping**:
   - PostgreSQL WAL (Write-Ahead Log) continuously shipped to backup location
   - Enables point-in-time recovery (PITR)
   - 1-hour RPO (Recovery Point Objective)

3. **Backup Validation**:
   - Weekly automated restore test to verify backup integrity
   - Checksum validation on all backup files
   - Backup size and duration monitoring

**Configuration Backups:**

- **GitOps Approach**: All configuration in Git repository
- **Kubernetes Manifests**: Versioned in Git, deployed via CI/CD
- **Application Properties**: Stored in ConfigMaps and Secrets
- **Infrastructure as Code**: Terraform/CloudFormation templates in Git

**Application State:**

- **Stateless Design**: No local state to backup
- **Session Data**: Replicated across Redis cluster
- **File Uploads**: Stored in object storage (S3/GCS/Azure Blob)

### 10.2 Recovery Procedures

**Recovery Time Objective (RTO): 4 hours**

**Recovery Point Objective (RPO): 1 hour**

**Disaster Recovery Steps:**

1. **Assess Impact**: Determine scope of failure (database, application, infrastructure)

2. **Restore Database** (if database failure):
   ```bash
   # Restore from latest backup
   pg_restore --host=postgres-new --dbname=carddemo \
     --username=postgres --jobs=4 \
     /backups/carddemo_2024-01-15.dump
   
   # Apply WAL logs for point-in-time recovery
   pg_wal_replay --target-time='2024-01-15 14:30:00' \
     --wal-directory=/wal-archive
   ```

3. **Deploy Application**:
   ```bash
   # Deploy to disaster recovery Kubernetes cluster
   kubectl apply -f kubernetes/ --namespace=carddemo-dr
   
   # Scale up pods
   kubectl scale deployment backend-deployment --replicas=5
   kubectl scale deployment frontend-deployment --replicas=3
   ```

4. **Update DNS**: Point DNS records to disaster recovery environment

5. **Validate**: Execute smoke tests and health checks

6. **Notify Users**: Communicate service restoration

**Multi-Region Deployment (Production):**

- **Primary Region**: us-east-1 (or equivalent)
- **DR Region**: us-west-2 (or equivalent)
- **Data Replication**: PostgreSQL streaming replication to DR region
- **Failover**: Automatic DNS failover with Route53/Cloud DNS

### 10.3 High Availability Architecture

**Component Redundancy:**

- **Backend**: Minimum 3 pod replicas across multiple availability zones
- **Frontend**: Minimum 2 pod replicas
- **Database**: PostgreSQL with synchronous replication (primary + standby)
- **Redis**: Redis Sentinel or Redis Cluster (3+ nodes)
- **Load Balancer**: Cloud provider load balancer (highly available by default)

**Zero-Downtime Deployments:**

```yaml
# Rolling update strategy
spec:
  replicas: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 1
  template:
    spec:
      containers:
      - name: backend
        image: carddemo-backend:v1.2.3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
```

**Circuit Breaker Pattern:**

```java
@Service
public class ExternalServiceClient {
    
    private final CircuitBreaker circuitBreaker;
    
    @CircuitBreaker(name = "paymentNetwork", fallbackMethod = "fallbackAuthorization")
    public AuthorizationResponse authorizeTransaction(TransactionRequest request) {
        return paymentNetworkService.authorize(request);
    }
    
    private AuthorizationResponse fallbackAuthorization(TransactionRequest request, Exception e) {
        // Graceful degradation: approve small transactions, reject large ones
        if (request.getAmount().compareTo(new BigDecimal("50.00")) <= 0) {
            return new AuthorizationResponse("APPROVED", "Offline approval");
        } else {
            return new AuthorizationResponse("DECLINED", "Service unavailable");
        }
    }
}
```

---

## 11. Future Architecture Considerations

### 11.1 Microservices Evolution

**Current State**: Monolithic Spring Boot application with clear service boundaries

**Future State**: Decomposed microservices architecture

**Proposed Microservices:**

1. **Authentication Service**:
   - User authentication and authorization
   - JWT token management
   - User profile management

2. **Account Management Service**:
   - Account CRUD operations
   - Account balance management
   - Account relationship management

3. **Card Management Service**:
   - Card CRUD operations
   - Card activation/deactivation
   - Card status management

4. **Transaction Processing Service**:
   - Transaction creation and validation
   - Transaction history
   - Transaction reporting

5. **Batch Processing Service**:
   - Interest calculation
   - Statement generation
   - Data load jobs

**Service Communication:**

- **Synchronous**: REST APIs for real-time operations
- **Asynchronous**: Apache Kafka for event-driven communication
- **Service Mesh**: Istio for advanced traffic management and security

**Benefits of Microservices:**

- Independent deployment and scaling
- Technology diversity (different languages/frameworks per service)
- Fault isolation (failure in one service doesn't affect others)
- Team autonomy (dedicated teams per service)

**Challenges to Address:**

- Distributed transaction management
- Service discovery and coordination
- Increased operational complexity
- Network latency between services
- Data consistency across services

### 11.2 Event-Driven Architecture

**Event Sourcing and CQRS:**

**Event Sourcing** stores all state changes as a sequence of events:

```java
// Example: Account event stream
public class AccountAggregate {
    private String accountId;
    private BigDecimal balance;
    private List<DomainEvent> changes = new ArrayList<>();
    
    public void deposit(BigDecimal amount) {
        apply(new AccountDepositedEvent(accountId, amount, Instant.now()));
    }
    
    private void apply(AccountDepositedEvent event) {
        this.balance = this.balance.add(event.getAmount());
        this.changes.add(event);
    }
}
```

**CQRS** separates read and write models:

- **Command Model**: Handles writes, enforces business rules
- **Query Model**: Optimized for reads, denormalized views
- **Event Bus**: Kafka propagates events from command to query side

**Benefits:**

- Complete audit trail of all changes
- Ability to replay events for debugging
- Temporal queries ("what was the balance on date X?")
- Scalable reads and writes independently

### 11.3 Advanced Features (Enhancements)

**Machine Learning Integration:**

- **Fraud Detection**: Real-time transaction anomaly detection
- **Credit Risk Assessment**: ML models for credit limit recommendations
- **Customer Churn Prediction**: Identify at-risk customers
- **Spending Pattern Analysis**: Personalized financial insights

**Real-Time Notifications:**

- **WebSocket Integration**: Server-push notifications to frontend
- **Push Notifications**: Mobile app notifications (future)
- **Email/SMS Alerts**: Transaction alerts, balance notifications

**Multi-Currency Support:**

- Currency conversion API integration
- Multi-currency account balances
- Foreign exchange transaction processing

**GraphQL API:**

```graphql
type Query {
  account(id: ID!): Account
  transactions(accountId: ID!, limit: Int, offset: Int): [Transaction]
}

type Account {
  accountId: ID!
  customerId: ID!
  currentBalance: Decimal!
  creditLimit: Decimal!
  cards: [Card]
  transactions(limit: Int): [Transaction]
}

type Mutation {
  updateAccount(id: ID!, input: AccountInput!): Account
  createTransaction(input: TransactionInput!): Transaction
}
```

**API Gateway Enhancements:**

- **Rate Limiting**: Per-user/per-IP rate limits
- **Request Aggregation**: Combine multiple backend calls
- **Response Caching**: Gateway-level caching for read-heavy endpoints
- **API Versioning**: /v1/, /v2/ URL prefixes for backward compatibility

---

## 12. Documentation References

This architecture document is part of a comprehensive documentation suite for the CardDemo modernization project:

1. **[API Documentation](./API.md)**: Complete REST API endpoint specifications, request/response schemas, authentication requirements, and error codes.

2. **[Migration Guide](./MIGRATION_GUIDE.md)**: Detailed COBOL-to-Java transformation mappings, copybook-to-entity conversions, and business logic preservation patterns.

3. **[Deployment Guide](./DEPLOYMENT.md)**: Step-by-step Docker and Kubernetes deployment procedures, environment configuration, and troubleshooting guide.

4. **[Testing Strategy](./TESTING.md)**: Comprehensive testing approach including unit tests, integration tests, performance tests, and migration validation procedures.

5. **[README.md](../README.md)**: Project overview, quick start guide, and local development setup instructions.

**External Documentation:**

- **Spring Boot Reference**: https://docs.spring.io/spring-boot/docs/3.2.1/reference/html/
- **Spring Data JPA**: https://docs.spring.io/spring-data/jpa/docs/3.2.1/reference/html/
- **Spring Batch**: https://docs.spring.io/spring-batch/docs/5.1.1/reference/html/
- **React Documentation**: https://react.dev/
- **PostgreSQL Documentation**: https://www.postgresql.org/docs/15/
- **Kubernetes Documentation**: https://kubernetes.io/docs/

---

## 13. Appendix

### 13.1 Technology Decision Records (TDRs)

Key architectural decisions with rationale and alternatives considered:

**TDR-001: Database Selection - PostgreSQL over MySQL**

- **Decision**: Use PostgreSQL 15 as the primary relational database
- **Rationale**:
  - Superior JSON/JSONB support for flexible schema evolution
  - Advanced window functions for complex reporting
  - Common Table Expressions (CTEs) for recursive queries
  - Better handling of complex data types (arrays, ranges)
  - Excellent performance for read-heavy workloads
  - Strong ACID compliance and transaction support
- **Alternatives Considered**:
  - MySQL: Simpler but less feature-rich
  - Oracle: Expensive licensing, mainframe-like vendor lock-in
  - MongoDB: NoSQL unsuitable for financial transactions requiring ACID

**TDR-002: Session Management - Redis over Hazelcast**

- **Decision**: Use Redis 7.2 for session storage and distributed caching
- **Rationale**:
  - Simpler operational model (fewer moving parts)
  - Excellent performance (sub-millisecond latency)
  - Built-in TTL (Time-To-Live) for automatic expiration
  - Rich data structures (strings, hashes, lists, sets)
  - Strong community support and extensive documentation
- **Alternatives Considered**:
  - Hazelcast: More features but higher operational complexity
  - Memcached: Simpler but lacks persistence and advanced features
  - Database sessions: Slower performance, increased database load

**TDR-003: Architecture Pattern - Monolith First over Microservices**

- **Decision**: Deploy as a Spring Boot monolith initially, with clear service boundaries for future decomposition
- **Rationale**:
  - Simpler deployment and operations during migration validation
  - Easier to ensure functional equivalence with legacy system
  - Lower operational overhead (single deployment, single database)
  - Clear service boundaries enable future microservices decomposition
  - Faster time-to-market for initial cloud deployment
- **Alternatives Considered**:
  - Microservices from day one: Higher complexity, premature optimization
  - Serverless functions: Not suitable for stateful transaction processing

**TDR-004: Frontend Framework - React over Angular**

- **Decision**: Use React 18 with functional components for the frontend
- **Rationale**:
  - Component-based architecture naturally maps to BMS screens
  - Extensive ecosystem of libraries and tools
  - Larger developer pool and community support
  - Excellent performance with virtual DOM
  - Simpler learning curve compared to Angular
  - Flexibility in state management (Redux, Context, etc.)
- **Alternatives Considered**:
  - Angular: More opinionated, steeper learning curve
  - Vue.js: Smaller ecosystem, less enterprise adoption
  - Svelte: Newer framework, less mature ecosystem

**TDR-005: Container Orchestration - Kubernetes over ECS**

- **Decision**: Use Kubernetes for container orchestration
- **Rationale**:
  - Cloud-agnostic deployment (AWS, Azure, GCP, on-premises)
  - Industry-standard platform with extensive tooling
  - Declarative configuration with YAML manifests
  - Strong ecosystem (Helm, Istio, Prometheus, etc.)
  - Portability and vendor independence
  - Excellent scalability and self-healing capabilities
- **Alternatives Considered**:
  - AWS ECS: AWS-specific, vendor lock-in
  - Docker Swarm: Simpler but less feature-rich
  - Nomad: Smaller community, less mature

### 13.2 Glossary

**Mainframe and Legacy Terms:**

- **COMP-3**: COBOL packed decimal data type, stores decimal numbers in binary-coded decimal format with sign in last nibble
- **VSAM KSDS**: Virtual Storage Access Method Key-Sequenced Dataset - indexed file with primary key
- **CICS**: Customer Information Control System - mainframe transaction processing monitor
- **BMS**: Basic Mapping Support - CICS facility for defining 3270 terminal screens
- **COMMAREA**: Communication Area - data structure passed between CICS programs
- **JCL**: Job Control Language - scripting language for batch job execution on mainframes
- **RACF**: Resource Access Control Facility - mainframe security system
- **LPAR**: Logical Partition - virtualized mainframe environment
- **SYNCPOINT**: CICS commit point for database transactions
- **PF Keys**: Programmable Function keys on 3270 terminals (PF1-PF24)
- **AIX**: Alternate Index - secondary index on VSAM file
- **GDG**: Generation Data Group - versioned dataset for backups

**Modern Architecture Terms:**

- **API Gateway**: Entry point for all client requests, handles routing, authentication, rate limiting
- **Circuit Breaker**: Design pattern that prevents cascading failures in distributed systems
- **CQRS**: Command Query Responsibility Segregation - separate models for reads and writes
- **DTO**: Data Transfer Object - object that carries data between processes
- **Event Sourcing**: Storing all state changes as a sequence of events
- **Horizontal Scaling**: Adding more instances of a service to handle load
- **Idempotency**: Property where operation produces same result regardless of how many times executed
- **JWT**: JSON Web Token - self-contained token for authentication
- **Microservices**: Architectural style where application is composed of small, independent services
- **ORM**: Object-Relational Mapping - technique for converting between relational database and object-oriented programming
- **RBAC**: Role-Based Access Control - access control based on user roles
- **REST**: Representational State Transfer - architectural style for web services
- **SLA**: Service Level Agreement - commitment to performance and availability metrics
- **Stateless**: Design where each request contains all information needed, no server-side session state
- **TPS**: Transactions Per Second - measure of system throughput

**Database Terms:**

- **ACID**: Atomicity, Consistency, Isolation, Durability - properties of reliable database transactions
- **B-tree**: Self-balancing tree data structure for database indexes
- **Foreign Key**: Column that references primary key in another table
- **Index**: Data structure that improves query performance
- **JDBC**: Java Database Connectivity - API for database access from Java
- **JPA**: Java Persistence API - specification for object-relational mapping
- **Primary Key**: Unique identifier for a database table row
- **Referential Integrity**: Constraint that maintains relationships between tables
- **Schema**: Database structure definition (tables, columns, relationships)
- **Transaction**: Unit of work that is atomic (all-or-nothing)
- **WAL**: Write-Ahead Log - technique for ensuring data durability in databases

**DevOps Terms:**

- **CI/CD**: Continuous Integration / Continuous Deployment - automated build and deployment pipeline
- **Container**: Lightweight, standalone executable package with application and dependencies
- **Docker**: Platform for building and running containers
- **Helm**: Package manager for Kubernetes applications
- **Kubernetes**: Container orchestration platform for automating deployment, scaling, and management
- **Pod**: Smallest deployable unit in Kubernetes, contains one or more containers
- **Prometheus**: Open-source monitoring and alerting toolkit
- **Service Mesh**: Infrastructure layer for service-to-service communication (e.g., Istio)

### 13.3 Acronyms

- **API**: Application Programming Interface
- **CRUD**: Create, Read, Update, Delete
- **DTO**: Data Transfer Object
- **HPA**: Horizontal Pod Autoscaler
- **JDBC**: Java Database Connectivity
- **JPA**: Java Persistence API
- **JWT**: JSON Web Token
- **LTS**: Long-Term Support
- **MDC**: Mapped Diagnostic Context
- **ORM**: Object-Relational Mapping
- **PITR**: Point-In-Time Recovery
- **POJO**: Plain Old Java Object
- **RBAC**: Role-Based Access Control
- **REST**: Representational State Transfer
- **RTO**: Recovery Time Objective
- **RPO**: Recovery Point Objective
- **SLA**: Service Level Agreement
- **SPA**: Single-Page Application
- **TPS**: Transactions Per Second
- **TTL**: Time-To-Live
- **UI**: User Interface
- **UUID**: Universally Unique Identifier
- **WAL**: Write-Ahead Log

---

**Document Version**: 1.0  
**Last Updated**: January 15, 2024  
**Authors**: CardDemo Migration Team  
**Reviewers**: Architecture Review Board, Security Team, Operations Team  
**Status**: Approved for Production Deployment

---

*This architecture document reflects the comprehensive modernization of the CardDemo application from a legacy IBM mainframe stack to a cloud-native Java Spring Boot architecture. All design decisions prioritize functional equivalence, performance preservation, and future scalability while enabling modern DevOps practices.*

