# CardDemo COBOL-to-Java Migration Guide

## Table of Contents

1. [Migration Overview](#1-migration-overview)
2. [COBOL Program to Java Service Class Mapping](#2-cobol-program-to-java-service-class-mapping)
3. [Data Type Mapping](#3-data-type-mapping)
4. [COBOL Control Structures to Java](#4-cobol-control-structures-to-java)
5. [CICS Transaction to Spring Transaction](#5-cics-transaction-to-spring-transaction)
6. [VSAM File Operations to JPA Repository](#6-vsam-file-operations-to-jpa-repository)
7. [BMS Map to React Component](#7-bms-map-to-react-component)
8. [COBOL Copybook to JPA Entity](#8-cobol-copybook-to-jpa-entity)
9. [JCL Batch Job to Spring Batch](#9-jcl-batch-job-to-spring-batch)
10. [Date Format Conversion](#10-date-format-conversion)
11. [Error Handling Transformation](#11-error-handling-transformation)
12. [Functional Equivalence Validation](#12-functional-equivalence-validation)
13. [Common Pitfalls and Solutions](#13-common-pitfalls-and-solutions)
14. [Migration Checklist](#14-migration-checklist)

---

## 1. Migration Overview

### 1.1 Technology Stack Transformation Matrix

The CardDemo application migration represents a comprehensive technology stack transformation from IBM mainframe to modern cloud-native architecture:

| Legacy Component | Target Technology | Migration Strategy |
|-----------------|-------------------|-------------------|
| COBOL (33 programs) | Java 21 + Spring Boot 3.x | Business logic preservation with OOP refactoring |
| CICS Transaction Server | Spring Boot REST APIs | Stateless microservices with session management |
| VSAM KSDS Files | PostgreSQL 15+ | Relational schema with referential integrity |
| BMS 3270 Screens (17 maps) | React 18+ Components | Modern web UI with equivalent workflows |
| JCL Batch Processing | Spring Batch 5.x | Containerized batch jobs with chunk processing |
| RACF Security | Spring Security 6.x | JWT-based authentication with role-based access |
| IBM z/OS | Docker + Kubernetes | Cloud-native containerized deployment |

### 1.2 Core Refactoring Goals

**Business Logic Preservation**: Transform all 33 COBOL programs to functionally equivalent Java Spring Boot services while maintaining identical computational results, particularly for COBOL COMP-3 decimal precision in financial calculations.

**Data Architecture Modernization**: Migrate 5 primary VSAM KSDS files to normalized PostgreSQL tables, preserving all cross-reference relationships using foreign key constraints.

**Transaction Processing Transformation**: Convert CICS pseudo-conversational processing to stateless REST API architecture, transforming COMMAREA structures to JSON DTOs while preserving transaction boundaries.

**User Interface Modernization**: Convert all 17 BMS mapsets to React functional components with equivalent screen layouts, preserving user workflows and field validation rules.

**Performance Preservation**: Maintain transaction response times under 200ms at 95th percentile and ensure batch processing completes within the existing 4-hour window.

### 1.3 Functional Equivalence Guarantee

Every feature, operation, and data manipulation present in the COBOL source must have an exact equivalent in the Java target:

- **Identical Calculations**: All numeric computations must produce byte-for-byte identical results
- **Preserved Workflows**: Screen navigation flows and business processes remain unchanged
- **Exact Validation**: All data validation rules and business rules are replicated precisely
- **Transaction Semantics**: ACID properties and transaction boundaries are maintained

---

## 2. COBOL Program to Java Service Class Mapping

### 2.1 Program Structure Transformation

#### COBOL Pattern
```cobol
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COACTVWC.
       
       ENVIRONMENT DIVISION.
       
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-ACCOUNT-ID        PIC 9(11).
       01 WS-BALANCE           PIC S9(13)V99 COMP-3.
       01 WS-CUSTOMER-NAME     PIC X(50).
       01 WS-RESP-CODE         PIC 99.
       
       PROCEDURE DIVISION.
       MAIN-LOGIC.
           PERFORM INITIALIZE-VARIABLES.
           PERFORM GET-ACCOUNT-DATA.
           PERFORM DISPLAY-ACCOUNT.
           PERFORM CLEANUP.
           GOBACK.
           
       INITIALIZE-VARIABLES.
           MOVE SPACES TO WS-CUSTOMER-NAME.
           MOVE ZERO TO WS-BALANCE.
           
       GET-ACCOUNT-DATA.
           EXEC CICS READ
               FILE('ACCTDAT')
               INTO(ACCOUNT-RECORD)
               RIDFLD(WS-ACCOUNT-ID)
               RESP(WS-RESP-CODE)
           END-EXEC.
           
       DISPLAY-ACCOUNT.
           MOVE ACCT-CUSTOMER-NAME TO WS-CUSTOMER-NAME.
           MOVE ACCT-BALANCE TO WS-BALANCE.
```

#### Java Spring Boot Equivalent
```java
package com.carddemo.service;

import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;
import com.carddemo.dto.response.AccountViewResponse;
import com.carddemo.exception.AccountNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
@Transactional(readOnly = true)
public class AccountViewService {
    
    @Autowired
    private AccountRepository accountRepository;
    
    // WORKING-STORAGE variables → instance variables (if needed for state)
    // Most state is passed via parameters or encapsulated in entities
    
    /**
     * View account details by account ID.
     * Equivalent to COBOL MAIN-LOGIC paragraph.
     * 
     * @param accountId The account ID to retrieve
     * @return AccountViewResponse containing account details
     * @throws AccountNotFoundException if account not found
     */
    public AccountViewResponse viewAccount(String accountId) {
        // PERFORM INITIALIZE-VARIABLES (implicit in Java)
        // PERFORM GET-ACCOUNT-DATA
        Account account = getAccountData(accountId);
        
        // PERFORM DISPLAY-ACCOUNT
        return displayAccount(account);
        
        // PERFORM CLEANUP (handled by Java garbage collection)
        // GOBACK (implicit return)
    }
    
    /**
     * Retrieve account data from repository.
     * Equivalent to GET-ACCOUNT-DATA paragraph with CICS READ.
     * 
     * @param accountId The account ID to retrieve
     * @return Account entity
     * @throws AccountNotFoundException if account not found
     */
    private Account getAccountData(String accountId) {
        // EXEC CICS READ → JPA repository findById
        return accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(
                "Account not found: " + accountId));
    }
    
    /**
     * Transform account entity to response DTO.
     * Equivalent to DISPLAY-ACCOUNT paragraph.
     * 
     * @param account The account entity
     * @return AccountViewResponse DTO
     */
    private AccountViewResponse displayAccount(Account account) {
        // Map entity fields to response DTO
        AccountViewResponse response = new AccountViewResponse();
        response.setAccountId(account.getAccountId());
        response.setCustomerName(account.getCustomer().getCustomerName());
        response.setCurrentBalance(account.getCurrentBalance());
        response.setAccountStatus(account.getStatus().name());
        response.setCreditLimit(account.getCreditLimit());
        response.setOpenDate(account.getOpenDate());
        return response;
    }
}
```

### 2.2 Complete Program-to-Service Mapping Table

| COBOL Program | Program ID | Java Service Class | REST Controller | HTTP Method | Endpoint |
|--------------|------------|-------------------|----------------|-------------|----------|
| COSGN00C.cbl | COSGN00C | AuthenticationService | AuthenticationController | POST | /api/auth/login |
| COMEN01C.cbl | COMEN01C | MenuNavigationService | MenuController | GET | /api/menu |
| COACTVWC.cbl | COACTVWC | AccountViewService | AccountController | GET | /api/accounts/{id} |
| COACTUPC.cbl | COACTUPC | AccountUpdateService | AccountController | PUT | /api/accounts/{id} |
| COACTADD.cbl | COACTADD | AccountCreationService | AccountController | POST | /api/accounts |
| COCRDLIC.cbl | COCRDLIC | CardListService | CardController | GET | /api/cards |
| COCRDSLC.cbl | COCRDSLC | CardDetailService | CardController | GET | /api/cards/{id} |
| COCRDUPC.cbl | COCRDUPC | CardUpdateService | CardController | PUT | /api/cards/{id} |
| COTRN00C.cbl | COTRN00C | TransactionListService | TransactionController | GET | /api/transactions |
| COTRN01C.cbl | COTRN01C | TransactionCategoryService | TransactionController | GET | /api/transactions/categories/summary |
| COTRN02C.cbl | COTRN02C | TransactionCreationService | TransactionController | POST | /api/transactions |
| COBIL00C.cbl | COBIL00C | BillPaymentService | BillPaymentController | POST | /api/payments/bill |
| CORPT00C.cbl | CORPT00C | ReportMenuService | ReportController | GET | /api/reports |
| COADM01C.cbl | COADM01C | AdminService | AdminController | GET/POST | /api/admin/* |
| COUSR00C.cbl | COUSR00C | UserManagementService | UserController | GET | /api/users |
| COUSR01C.cbl | COUSR01C | UserProfileService | UserController | GET | /api/users/{id} |

### 2.3 COBOL WORKING-STORAGE to Java Fields

**Transformation Rules:**

1. **Level 01 Group Items** → Java class or nested object
2. **Level 05/10 Elementary Items** → Java instance variables
3. **77-level Independent Items** → Java local or instance variables
4. **REDEFINES** → Union-like pattern using multiple fields or type casting

**Example:**

```cobol
COBOL:
       WORKING-STORAGE SECTION.
       01 WS-ACCOUNT-DATA.
           05 WS-ACCOUNT-ID        PIC 9(11).
           05 WS-CUSTOMER-ID       PIC 9(9).
           05 WS-BALANCE           PIC S9(13)V99 COMP-3.
       77 WS-COUNTER              PIC 9(4) COMP.
```

Java:
```java
// Group item → nested class or separate fields
private String accountId;      // WS-ACCOUNT-ID
private String customerId;     // WS-CUSTOMER-ID
private BigDecimal balance;    // WS-BALANCE (COMP-3)
private int counter;           // 77-level WS-COUNTER
```

---

## 3. Data Type Mapping

### 3.1 COBOL PIC Clauses to Java Types

#### Comprehensive Mapping Table

| COBOL Data Type | Example | Java Type | JPA Annotation | Validation | Notes |
|----------------|---------|-----------|---------------|-----------|-------|
| PIC 9(n) where n ≤ 9 | PIC 9(8) | Integer | @Column(length=n) | @Min(0) | Unsigned numeric |
| PIC 9(n) where n > 9 | PIC 9(11) | Long or String | @Column(precision=n) or length=n | @Pattern if String | Large unsigned numbers |
| PIC S9(n) | PIC S9(7) | Integer/Long | @Column | @Min, @Max | Signed numbers |
| PIC S9(n)V99 COMP-3 | PIC S9(13)V99 COMP-3 | **BigDecimal** | @Column(precision=n+2, scale=2) | @Digits | **CRITICAL: Financial data** |
| PIC S9(n)V9(m) COMP-3 | PIC S9(3)V9(5) COMP-3 | BigDecimal | @Column(precision=n+m, scale=m) | @Digits | Variable scale |
| PIC X(n) | PIC X(50) | String | @Column(length=n) | @Size(max=n) | Alphanumeric |
| PIC A(n) | PIC A(25) | String | @Column(length=n) | @Pattern(regexp="[A-Za-z ]+") | Alphabetic only |
| PIC 9(n) COMP | PIC 9(4) COMP | Integer/Long | @Column | N/A | Binary representation |
| PIC 9(8) (Date) | PIC 9(8) | LocalDate | @Column | @Past or @Future | YYYYMMDD format |
| COMP-1 | COMP-1 | Float | @Column | N/A | Single precision float |
| COMP-2 | COMP-2 | Double | @Column | N/A | Double precision float |

### 3.2 Critical COMP-3 Decimal Precision Preservation

**CRITICAL**: COBOL COMP-3 (packed decimal) fields require exact BigDecimal configuration to maintain financial calculation precision.

#### Example 1: Account Balance

```cobol
COBOL:
01 ACCOUNT-RECORD.
   05 ACCT-CURR-BAL        PIC S9(13)V99 COMP-3.
   05 ACCT-CREDIT-LIMIT    PIC S9(13)V99 COMP-3.
   
PROCEDURE DIVISION.
   COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL + TRANSACTION-AMT.
```

Java Entity (MUST preserve precision):
```java
@Entity
@Table(name = "account")
public class Account {
    
    @Column(name = "current_balance", precision = 15, scale = 2, nullable = false)
    private BigDecimal currentBalance;  // PIC S9(13)V99 → precision=15, scale=2
    
    @Column(name = "credit_limit", precision = 15, scale = 2, nullable = false)
    private BigDecimal creditLimit;
    
    /**
     * Add transaction amount to balance.
     * CRITICAL: Must use setScale and RoundingMode to match COBOL behavior.
     */
    public void addTransaction(BigDecimal transactionAmount) {
        // COBOL COMPUTE equivalent
        this.currentBalance = this.currentBalance
            .add(transactionAmount)
            .setScale(2, RoundingMode.HALF_UP);  // Matches COBOL rounding
    }
}
```

#### Example 2: Interest Calculation

```cobol
COBOL:
01 INTEREST-CALC-FIELDS.
   05 PRINCIPAL-AMT        PIC S9(13)V99 COMP-3.
   05 INTEREST-RATE        PIC S9(3)V9(5) COMP-3.
   05 INTEREST-AMOUNT      PIC S9(7)V99 COMP-3.
   
PROCEDURE DIVISION.
   COMPUTE INTEREST-AMOUNT = PRINCIPAL-AMT * INTEREST-RATE.
```

Java Service (MUST specify scale and rounding):
```java
public class InterestCalculationService {
    
    /**
     * Calculate interest amount.
     * CRITICAL: Must match COBOL COMP-3 precision and rounding.
     * 
     * @param principalAmount Balance amount (precision 15, scale 2)
     * @param interestRate Annual rate (precision 8, scale 5)
     * @return Interest amount (precision 9, scale 2)
     */
    public BigDecimal calculateInterest(BigDecimal principalAmount, 
                                       BigDecimal interestRate) {
        // COMPUTE INTEREST-AMOUNT = PRINCIPAL-AMT * INTEREST-RATE
        return principalAmount
            .multiply(interestRate)
            .setScale(2, RoundingMode.HALF_UP);  // MUST match COBOL rounding
    }
    
    /**
     * Calculate daily interest rate from annual rate.
     * Division requires explicit scale to prevent ArithmeticException.
     */
    public BigDecimal getDailyRate(BigDecimal annualRate) {
        return annualRate
            .divide(new BigDecimal("365"), 8, RoundingMode.HALF_UP);
    }
}
```

### 3.3 BigDecimal Best Practices

**ALWAYS Follow These Rules:**

1. **Specify Scale**: Always use `.setScale(n, RoundingMode.HALF_UP)` after arithmetic operations
2. **Explicit Rounding**: Use `RoundingMode.HALF_UP` to match COBOL default rounding
3. **Division Precision**: Always specify scale in `divide()` to prevent `ArithmeticException`
4. **String Construction**: Use `new BigDecimal("0.00")` instead of `new BigDecimal(0.0)` to avoid precision issues
5. **Comparison**: Use `.compareTo()` instead of `.equals()` for value comparison

```java
// CORRECT Examples
BigDecimal amount1 = new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP);
BigDecimal rate = new BigDecimal("0.15000").setScale(5, RoundingMode.HALF_UP);
BigDecimal interest = amount1.multiply(rate).setScale(2, RoundingMode.HALF_UP);

// Comparison
if (balance.compareTo(BigDecimal.ZERO) > 0) {
    // Balance is positive
}

// INCORRECT Examples (DO NOT USE)
BigDecimal bad1 = new BigDecimal(10000.00);  // Floating point precision issues
BigDecimal bad2 = amount.multiply(rate);     // No scale specified
BigDecimal bad3 = amount.divide(divisor);    // May throw ArithmeticException
```

---

## 4. COBOL Control Structures to Java

### 4.1 IF-ELSE Statement Transformation

#### Simple IF-ELSE

```cobol
COBOL:
       IF CARD-STATUS = 'A'
           PERFORM PROCESS-ACTIVE-CARD
       ELSE
           PERFORM PROCESS-INACTIVE-CARD
       END-IF.
```

Java:
```java
if (card.getStatus() == CardStatus.ACTIVE) {
    processActiveCard(card);
} else {
    processInactiveCard(card);
}
```

#### Nested IF-ELSE

```cobol
COBOL:
       IF CARD-STATUS = 'A'
           PERFORM PROCESS-ACTIVE-CARD
       ELSE
           IF CARD-STATUS = 'E'
               PERFORM PROCESS-EXPIRED-CARD
           ELSE
               IF CARD-STATUS = 'B'
                   PERFORM PROCESS-BLOCKED-CARD
               ELSE
                   PERFORM PROCESS-UNKNOWN-STATUS
               END-IF
           END-IF
       END-IF.
```

Java (using else-if):
```java
if (card.getStatus() == CardStatus.ACTIVE) {
    processActiveCard(card);
} else if (card.getStatus() == CardStatus.EXPIRED) {
    processExpiredCard(card);
} else if (card.getStatus() == CardStatus.BLOCKED) {
    processBlockedCard(card);
} else {
    processUnknownStatus(card);
}
```

### 4.2 EVALUATE Statement Transformation

```cobol
COBOL:
       EVALUATE TRANSACTION-TYPE
           WHEN 'PU'
               PERFORM PROCESS-PURCHASE
           WHEN 'WD'
               PERFORM PROCESS-WITHDRAWAL
           WHEN 'PM'
               PERFORM PROCESS-PAYMENT
           WHEN 'RF'
               PERFORM PROCESS-REFUND
           WHEN OTHER
               PERFORM INVALID-TRANSACTION-TYPE
       END-EVALUATE.
```

Java (using Java 14+ switch expression):
```java
switch (transactionType) {
    case PURCHASE -> processPurchase(transaction);
    case WITHDRAWAL -> processWithdrawal(transaction);
    case PAYMENT -> processPayment(transaction);
    case REFUND -> processRefund(transaction);
    default -> handleInvalidTransactionType(transaction);
}
```

Java (traditional switch for Java 8-13):
```java
switch (transactionType) {
    case PURCHASE:
        processPurchase(transaction);
        break;
    case WITHDRAWAL:
        processWithdrawal(transaction);
        break;
    case PAYMENT:
        processPayment(transaction);
        break;
    case REFUND:
        processRefund(transaction);
        break;
    default:
        handleInvalidTransactionType(transaction);
        break;
}
```

### 4.3 PERFORM UNTIL Loop Transformation

#### Sequential File Processing

```cobol
COBOL:
       MOVE 'N' TO END-OF-FILE.
       PERFORM UNTIL END-OF-FILE = 'Y'
           READ CARDFILE
           AT END
               MOVE 'Y' TO END-OF-FILE
           NOT AT END
               PERFORM PROCESS-CARD-RECORD
           END-READ
       END-PERFORM.
```

Java (using Stream API - preferred):
```java
// Modern approach with JPA repository
cardRepository.findAll().forEach(card -> {
    processCardRecord(card);
});

// Or with pagination for large datasets
int pageSize = 1000;
int pageNumber = 0;
Page<Card> cardPage;

do {
    cardPage = cardRepository.findAll(PageRequest.of(pageNumber, pageSize));
    cardPage.getContent().forEach(card -> {
        processCardRecord(card);
    });
    pageNumber++;
} while (cardPage.hasNext());
```

Java (traditional while loop):
```java
boolean endOfFile = false;
Iterator<Card> cardIterator = cardRepository.findAll().iterator();

while (!endOfFile) {
    if (cardIterator.hasNext()) {
        Card card = cardIterator.next();
        processCardRecord(card);
    } else {
        endOfFile = true;
    }
}
```

#### Counter-Controlled Loop

```cobol
COBOL:
       PERFORM VARYING WS-INDEX FROM 1 BY 1
           UNTIL WS-INDEX > 10
               PERFORM PROCESS-ITEM
       END-PERFORM.
```

Java:
```java
for (int index = 1; index <= 10; index++) {
    processItem(index);
}
```

### 4.4 COBOL 88-Level Condition Names

```cobol
COBOL:
       01 CARD-STATUS-CODE     PIC X(1).
           88 CARD-ACTIVE      VALUE 'A'.
           88 CARD-EXPIRED     VALUE 'E'.
           88 CARD-BLOCKED     VALUE 'B'.
           88 CARD-CLOSED      VALUE 'C'.
       
       PROCEDURE DIVISION.
           IF CARD-ACTIVE
               PERFORM AUTHORIZE-TRANSACTION
           END-IF.
```

Java (using Enum - preferred):
```java
public enum CardStatus {
    ACTIVE('A', "Active"),
    EXPIRED('E', "Expired"),
    BLOCKED('B', "Blocked"),
    CLOSED('C', "Closed");
    
    private final char code;
    private final String description;
    
    CardStatus(char code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public char getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static CardStatus fromCode(char code) {
        for (CardStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid card status code: " + code);
    }
}

// Usage in service
if (card.getStatus() == CardStatus.ACTIVE) {
    authorizeTransaction(transaction);
}
```

---

## 5. CICS Transaction to Spring Transaction

### 5.1 CICS Commands to Spring Patterns

#### CICS File Operations to JPA

```cobol
COBOL CICS:
       EXEC CICS HANDLE CONDITION
           ERROR(ERROR-PARAGRAPH)
           NOTFND(NOT-FOUND-PARAGRAPH)
       END-EXEC.
       
       EXEC CICS READ
           FILE('ACCTDAT')
           INTO(ACCOUNT-RECORD)
           RIDFLD(ACCOUNT-KEY)
           RESP(WS-RESP)
       END-EXEC.
       
       IF WS-RESP = DFHRESP(NORMAL)
           PERFORM UPDATE-ACCOUNT-BALANCE
           EXEC CICS REWRITE
               FILE('ACCTDAT')
               FROM(ACCOUNT-RECORD)
           END-EXEC
       END-IF.
       
       EXEC CICS SYNCPOINT END-EXEC.
       EXEC CICS RETURN END-EXEC.
```

Java Spring Equivalent:
```java
@Service
@Transactional(
    isolation = Isolation.READ_COMMITTED,
    propagation = Propagation.REQUIRED,
    rollbackFor = Exception.class
)
public class AccountUpdateService {
    
    @Autowired
    private AccountRepository accountRepository;
    
    /**
     * Update account balance with transaction semantics.
     * Equivalent to CICS transaction with SYNCPOINT.
     * 
     * @param accountKey Account identifier
     * @param newBalance New balance amount
     * @return Updated account
     * @throws AccountNotFoundException if account not found
     */
    public Account updateAccountBalance(String accountKey, BigDecimal newBalance) {
        try {
            // EXEC CICS READ → JPA findById
            Account account = accountRepository.findById(accountKey)
                .orElseThrow(() -> new AccountNotFoundException(
                    "Account not found: " + accountKey));
            
            // PERFORM UPDATE-ACCOUNT-BALANCE
            account.setCurrentBalance(newBalance.setScale(2, RoundingMode.HALF_UP));
            
            // EXEC CICS REWRITE → JPA save
            Account updatedAccount = accountRepository.save(account);
            
            // EXEC CICS SYNCPOINT → @Transactional automatic commit
            // Transaction commits automatically on method return
            
            return updatedAccount;
            
        } catch (Exception e) {
            // EXEC CICS HANDLE CONDITION ERROR
            // @Transactional automatically rolls back on exception
            throw new AccountUpdateException("Failed to update account balance", e);
        }
        // EXEC CICS RETURN → method return (implicit)
    }
}
```

### 5.2 CICS Transaction Isolation Mapping

| CICS Concept | Spring @Transactional Setting | Description |
|-------------|------------------------------|-------------|
| CICS default isolation | `isolation = Isolation.READ_COMMITTED` | Reads committed data only |
| CICS SYNCPOINT | Transaction method boundary | Commit point |
| CICS SYNCPOINT ROLLBACK | `rollbackFor = Exception.class` | Rollback on exception |
| CICS Pseudo-conversational | Stateless REST with session | Session state in Redis |
| CICS COMMAREA | JSON Request/Response DTO | Data transfer object |

### 5.3 COMMAREA to JSON DTO Transformation

```cobol
COBOL COMMAREA:
       01 COMMAREA.
           05 CA-REQUEST-ID        PIC X(4).
           05 CA-TRANS-CODE        PIC X(4).
           05 CA-ACCOUNT-ID        PIC 9(11).
           05 CA-CUSTOMER-NAME     PIC X(50).
           05 CA-BALANCE           PIC S9(13)V99 COMP-3.
           05 CA-RETURN-CODE       PIC 99.
           05 CA-ERROR-MESSAGE     PIC X(80).
```

Java Request DTO:
```java
package com.carddemo.dto.request;

import javax.validation.constraints.*;
import java.math.BigDecimal;

public class AccountUpdateRequest {
    
    @NotBlank(message = "Request ID is required")
    @Size(max = 4)
    private String requestId;
    
    @NotBlank(message = "Transaction code is required")
    @Size(max = 4)
    private String transactionCode;
    
    @NotNull(message = "Account ID is required")
    @Pattern(regexp = "\\d{11}", message = "Account ID must be 11 digits")
    private String accountId;
    
    @Size(max = 50)
    private String customerName;
    
    @NotNull(message = "Balance is required")
    @Digits(integer = 13, fraction = 2)
    private BigDecimal balance;
    
    // Getters and setters
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    
    public String getTransactionCode() { return transactionCode; }
    public void setTransactionCode(String transactionCode) { 
        this.transactionCode = transactionCode; 
    }
    
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { 
        this.customerName = customerName; 
    }
    
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { 
        this.balance = balance.setScale(2, RoundingMode.HALF_UP); 
    }
}
```

Java Response DTO:
```java
package com.carddemo.dto.response;

import java.math.BigDecimal;

public class AccountUpdateResponse {
    
    private String requestId;
    private String accountId;
    private String customerName;
    private BigDecimal balance;
    private Integer returnCode;      // 00 = success, non-zero = error
    private String errorMessage;
    
    // Getters and setters
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { 
        this.customerName = customerName; 
    }
    
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    
    public Integer getReturnCode() { return returnCode; }
    public void setReturnCode(Integer returnCode) { this.returnCode = returnCode; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { 
        this.errorMessage = errorMessage; 
    }
}
```

REST Controller:
```java
@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    
    @Autowired
    private AccountUpdateService accountUpdateService;
    
    @PutMapping("/{id}")
    public ResponseEntity<AccountUpdateResponse> updateAccount(
            @PathVariable("id") String accountId,
            @Valid @RequestBody AccountUpdateRequest request) {
        
        AccountUpdateResponse response = accountUpdateService.updateAccount(request);
        return ResponseEntity.ok(response);
    }
}
```

---

## 6. VSAM File Operations to JPA Repository

### 6.1 VSAM Operation Mapping

| VSAM Operation | COBOL Command | JPA Repository Method | SQL Equivalent | Notes |
|---------------|---------------|---------------------|----------------|-------|
| Random READ | EXEC CICS READ FILE RIDFLD | findById(key) | SELECT WHERE id = ? | Primary key access |
| Sequential READ | EXEC CICS STARTBR / READNEXT | findAll() or findBy* | SELECT ORDER BY | Browse operation |
| WRITE | EXEC CICS WRITE FILE FROM | save(entity) with null ID | INSERT | New record |
| REWRITE | EXEC CICS REWRITE FILE FROM | save(entity) with existing ID | UPDATE | Update existing |
| DELETE | EXEC CICS DELETE FILE RIDFLD | deleteById(key) | DELETE WHERE id = ? | Remove record |
| ENDBR | EXEC CICS ENDBR | N/A | N/A | Automatic in JPA |

### 6.2 VSAM Random Access Pattern

```cobol
COBOL:
       EXEC CICS READ
           FILE('ACCTDAT')
           INTO(ACCOUNT-RECORD)
           RIDFLD(ACCOUNT-KEY)
           RESP(WS-RESP)
           RESP2(WS-RESP2)
       END-EXEC.
       
       IF WS-RESP = DFHRESP(NORMAL)
           PERFORM PROCESS-ACCOUNT
       ELSE
           IF WS-RESP = DFHRESP(NOTFND)
               PERFORM ACCOUNT-NOT-FOUND
           ELSE
               PERFORM FILE-ERROR
           END-IF
       END-IF.
```

Java JPA:
```java
@Repository
public interface AccountRepository extends JpaRepository<Account, String> {
    // findById is inherited from JpaRepository
}

@Service
public class AccountService {
    
    @Autowired
    private AccountRepository accountRepository;
    
    public void processAccountLookup(String accountKey) {
        Optional<Account> accountOpt = accountRepository.findById(accountKey);
        
        if (accountOpt.isPresent()) {
            // WS-RESP = DFHRESP(NORMAL)
            processAccount(accountOpt.get());
        } else {
            // WS-RESP = DFHRESP(NOTFND)
            handleAccountNotFound(accountKey);
        }
    }
}
```

### 6.3 VSAM Sequential Browse Pattern

```cobol
COBOL:
       EXEC CICS STARTBR
           FILE('CARDDAT')
           RIDFLD(ACCOUNT-KEY)
           GTEQ
       END-EXEC.
       
       MOVE 'N' TO END-OF-BROWSE.
       PERFORM UNTIL END-OF-BROWSE = 'Y'
           EXEC CICS READNEXT
               FILE('CARDDAT')
               INTO(CARD-RECORD)
               RIDFLD(CARD-KEY)
               RESP(WS-RESP)
           END-EXEC
           
           IF WS-RESP = DFHRESP(NORMAL)
               PERFORM PROCESS-CARD
           ELSE
               MOVE 'Y' TO END-OF-BROWSE
           END-IF
       END-PERFORM.
       
       EXEC CICS ENDBR
           FILE('CARDDAT')
       END-EXEC.
```

Java JPA (using custom query method):
```java
@Repository
public interface CardRepository extends JpaRepository<Card, String> {
    
    // Custom query for sequential access with criteria
    List<Card> findByAccountIdOrderByCardNumber(String accountId);
    
    // Or with pagination for large result sets
    Page<Card> findByAccountIdOrderByCardNumber(
        String accountId, Pageable pageable);
}

@Service
public class CardBrowseService {
    
    @Autowired
    private CardRepository cardRepository;
    
    /**
     * Browse cards for an account (equivalent to STARTBR/READNEXT).
     */
    public List<CardDTO> browseCardsForAccount(String accountId) {
        List<Card> cards = cardRepository
            .findByAccountIdOrderByCardNumber(accountId);
        
        return cards.stream()
            .map(this::processCard)
            .collect(Collectors.toList());
    }
    
    /**
     * Browse with pagination (for large datasets).
     */
    public Page<CardDTO> browseCardsForAccountPaged(
            String accountId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, 
            Sort.by("cardNumber").ascending());
        
        Page<Card> cardPage = cardRepository
            .findByAccountIdOrderByCardNumber(accountId, pageable);
        
        return cardPage.map(this::processCard);
    }
}
```

### 6.4 VSAM WRITE (Insert) Pattern

```cobol
COBOL:
       MOVE SPACES TO ACCOUNT-RECORD.
       MOVE WS-ACCOUNT-ID TO ACCT-ID.
       MOVE WS-CUSTOMER-ID TO ACCT-CUSTOMER-ID.
       MOVE 'A' TO ACCT-STATUS.
       MOVE WS-BALANCE TO ACCT-CURR-BAL.
       
       EXEC CICS WRITE
           FILE('ACCTDAT')
           FROM(ACCOUNT-RECORD)
           RIDFLD(ACCT-ID)
           RESP(WS-RESP)
       END-EXEC.
       
       IF WS-RESP NOT = DFHRESP(NORMAL)
           PERFORM WRITE-ERROR
       END-IF.
```

Java JPA:
```java
@Service
@Transactional
public class AccountCreationService {
    
    @Autowired
    private AccountRepository accountRepository;
    
    /**
     * Create new account (equivalent to CICS WRITE).
     */
    public Account createAccount(AccountCreateRequest request) {
        // Create new entity
        Account account = new Account();
        account.setAccountId(request.getAccountId());
        account.setCustomerId(request.getCustomerId());
        account.setStatus(AccountStatus.ACTIVE);
        account.setCurrentBalance(request.getInitialBalance()
            .setScale(2, RoundingMode.HALF_UP));
        account.setOpenDate(LocalDate.now());
        
        try {
            // JPA save with null/new ID performs INSERT
            return accountRepository.save(account);
        } catch (DataIntegrityViolationException e) {
            // Duplicate key or constraint violation
            throw new AccountAlreadyExistsException(
                "Account already exists: " + request.getAccountId(), e);
        }
    }
}
```

### 6.5 VSAM REWRITE (Update) Pattern

```cobol
COBOL:
       EXEC CICS READ
           FILE('ACCTDAT')
           INTO(ACCOUNT-RECORD)
           RIDFLD(ACCOUNT-KEY)
           UPDATE
       END-EXEC.
       
       ADD TRANSACTION-AMT TO ACCT-CURR-BAL.
       
       EXEC CICS REWRITE
           FILE('ACCTDAT')
           FROM(ACCOUNT-RECORD)
       END-EXEC.
```

Java JPA:
```java
@Service
@Transactional
public class AccountUpdateService {
    
    @Autowired
    private AccountRepository accountRepository;
    
    /**
     * Update account balance (equivalent to CICS READ UPDATE + REWRITE).
     */
    public Account updateBalance(String accountId, BigDecimal transactionAmount) {
        // READ with UPDATE intent (pessimistic locking if needed)
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
        
        // ADD TRANSACTION-AMT TO ACCT-CURR-BAL
        BigDecimal newBalance = account.getCurrentBalance()
            .add(transactionAmount)
            .setScale(2, RoundingMode.HALF_UP);
        account.setCurrentBalance(newBalance);
        
        // REWRITE (JPA save with existing ID performs UPDATE)
        return accountRepository.save(account);
    }
}
```

### 6.6 VSAM DELETE Pattern

```cobol
COBOL:
       EXEC CICS DELETE
           FILE('CARDDAT')
           RIDFLD(CARD-KEY)
           RESP(WS-RESP)
       END-EXEC.
       
       IF WS-RESP = DFHRESP(NORMAL)
           PERFORM DELETE-SUCCESSFUL
       ELSE
           IF WS-RESP = DFHRESP(NOTFND)
               PERFORM RECORD-NOT-FOUND
           END-IF
       END-IF.
```

Java JPA:
```java
@Service
@Transactional
public class CardDeletionService {
    
    @Autowired
    private CardRepository cardRepository;
    
    /**
     * Delete card by ID (equivalent to CICS DELETE).
     */
    public void deleteCard(String cardId) {
        if (cardRepository.existsById(cardId)) {
            cardRepository.deleteById(cardId);
            // Delete successful
        } else {
            // Record not found
            throw new CardNotFoundException(
                "Card not found for deletion: " + cardId);
        }
    }
    
    /**
     * Alternative: Using Optional pattern.
     */
    public boolean deleteCardSafe(String cardId) {
        return cardRepository.findById(cardId)
            .map(card -> {
                cardRepository.delete(card);
                return true;
            })
            .orElse(false);
    }
}
```

---

## 7. BMS Map to React Component

### 7.1 BMS Screen Field Mapping

#### COBOL BMS Definition

```cobol
COSGN00M DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,            
               TIOAPFX=YES,CTRL=FREEKB
               
COSGN00  DFHMDI SIZE=(24,80),LINE=1,COLUMN=1
         
TITLE1   DFHMDF POS=(1,1),LENGTH=40,ATTRB=(NORM,PROT),           
               INITIAL='     CardDemo - Sign On Screen'
               
USERID   DFHMDF POS=(10,20),LENGTH=8,ATTRB=(NORM,UNPROT,IC)
USERIDO  DFHMDF POS=(10,29),LENGTH=0,ATTRB=ASKIP
         
PASSWD   DFHMDF POS=(12,20),LENGTH=8,ATTRB=(NORM,UNPROT,DRK)
PASSWDO  DFHMDF POS=(12,29),LENGTH=0,ATTRB=ASKIP
         
ERRMSG   DFHMDF POS=(23,1),LENGTH=79,ATTRB=(BRT,PROT),COLOR=RED
```

#### React Component Equivalent

```jsx
import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDispatch } from 'react-redux';
import { login } from '../../redux/slices/authSlice';
import './LoginComponent.css';

/**
 * Login Component - Equivalent to BMS COSGN00 screen.
 * Maps BMS field attributes to React props.
 */
export const LoginComponent = () => {
    // State management for form fields
    const [userId, setUserId] = useState('');        // USERID field
    const [password, setPassword] = useState('');    // PASSWD field
    const [errorMsg, setErrorMsg] = useState('');    // ERRMSG field
    const [isSubmitting, setIsSubmitting] = useState(false);
    
    const navigate = useNavigate();
    const dispatch = useDispatch();
    
    /**
     * Handle form submission - equivalent to ENTER key action.
     */
    const handleLogin = async (e) => {
        e.preventDefault();
        setErrorMsg('');
        setIsSubmitting(true);
        
        try {
            // Call authentication service
            const response = await dispatch(login({ 
                userId, 
                password 
            })).unwrap();
            
            // Navigate to main menu on success (PF3 equivalent)
            navigate('/menu');
            
        } catch (error) {
            // Display error message (ERRMSG field, ATTRB=BRT,PROT,COLOR=RED)
            setErrorMsg(error.message || 'Invalid user ID or password');
        } finally {
            setIsSubmitting(false);
        }
    };
    
    /**
     * Handle clear/reset - equivalent to CLEAR key.
     */
    const handleClear = () => {
        setUserId('');
        setPassword('');
        setErrorMsg('');
    };
    
    return (
        <div className="login-screen">
            {/* TITLE1 field - ATTRB=NORM,PROT */}
            <div className="screen-title">
                CardDemo - Sign On Screen
            </div>
            
            <form onSubmit={handleLogin} className="login-form">
                {/* USERID field - ATTRB=NORM,UNPROT,IC */}
                <div className="form-group">
                    <label htmlFor="userId">User ID:</label>
                    <input
                        id="userId"
                        type="text"
                        value={userId}
                        onChange={(e) => setUserId(e.target.value.toUpperCase())}
                        maxLength={8}                // LENGTH=8
                        autoFocus                    // IC (Initial Cursor)
                        required
                        disabled={isSubmitting}
                        className="input-field"
                    />
                </div>
                
                {/* PASSWD field - ATTRB=NORM,UNPROT,DRK */}
                <div className="form-group">
                    <label htmlFor="password">Password:</label>
                    <input
                        id="password"
                        type="password"              // DRK (Dark/Hidden)
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        maxLength={8}                // LENGTH=8
                        required
                        disabled={isSubmitting}
                        className="input-field"
                    />
                </div>
                
                <div className="button-group">
                    <button 
                        type="submit" 
                        disabled={isSubmitting}
                        className="btn-primary"
                    >
                        {isSubmitting ? 'Signing in...' : 'Sign In'}
                    </button>
                    
                    <button 
                        type="button" 
                        onClick={handleClear}
                        disabled={isSubmitting}
                        className="btn-secondary"
                    >
                        Clear
                    </button>
                </div>
                
                {/* ERRMSG field - ATTRB=BRT,PROT,COLOR=RED */}
                {errorMsg && (
                    <div className="error-message">
                        {errorMsg}
                    </div>
                )}
            </form>
        </div>
    );
};
```

### 7.2 BMS Field Attribute Mapping

| BMS Attribute | Description | React Equivalent | CSS/Props |
|--------------|-------------|------------------|-----------|
| ATTRB=PROT | Protected (display only) | disabled={true} or read-only div | readonly, disabled |
| ATTRB=UNPROT | Unprotected (input field) | enabled input | standard input |
| ATTRB=IC | Initial Cursor | autoFocus={true} | autoFocus prop |
| ATTRB=DRK | Dark (hidden input) | type="password" | type="password" |
| ATTRB=BRT | Bright (highlighted) | className="highlight" | font-weight, color |
| ATTRB=ASKIP | Auto-skip (no stop) | tabIndex={-1} | tabIndex=-1 |
| COLOR=RED | Red text | className="error" | color: red |
| COLOR=BLUE | Blue text | className="info" | color: blue |

### 7.3 PF Key to React Navigation Mapping

| BMS PF Key | Function | React Implementation | Code |
|-----------|----------|---------------------|------|
| PF3 | Exit/Return to previous | navigate(-1) | Button onClick |
| PF7 | Scroll Backward | Previous page | Pagination component |
| PF8 | Scroll Forward | Next page | Pagination component |
| PF12 | Cancel | navigate('/menu') | Button onClick with confirmation |
| ENTER | Submit form | Form onSubmit | Form submission |
| CLEAR | Clear screen | Reset form state | Button onClick |

#### Example: Card List with Pagination (PF7/PF8)

```jsx
export const CardListComponent = () => {
    const [currentPage, setCurrentPage] = useState(0);
    const [cards, setCards] = useState([]);
    const [totalPages, setTotalPages] = useState(0);
    const pageSize = 7;  // BMS screen shows 7 cards per page
    
    useEffect(() => {
        fetchCards(currentPage, pageSize);
    }, [currentPage]);
    
    const fetchCards = async (page, size) => {
        const response = await cardService.getCards(page, size);
        setCards(response.content);
        setTotalPages(response.totalPages);
    };
    
    // PF7 - Previous page (Scroll Backward)
    const handlePreviousPage = () => {
        if (currentPage > 0) {
            setCurrentPage(currentPage - 1);
        }
    };
    
    // PF8 - Next page (Scroll Forward)
    const handleNextPage = () => {
        if (currentPage < totalPages - 1) {
            setCurrentPage(currentPage + 1);
        }
    };
    
    // PF3 - Return to menu
    const handleReturn = () => {
        navigate('/menu');
    };
    
    return (
        <div className="card-list-screen">
            <div className="card-list">
                {cards.map(card => (
                    <div key={card.cardNumber} className="card-item">
                        {/* Display card details */}
                    </div>
                ))}
            </div>
            
            <div className="navigation-buttons">
                <button 
                    onClick={handlePreviousPage} 
                    disabled={currentPage === 0}
                    className="btn-pf7"
                >
                    ◄ Previous (PF7)
                </button>
                
                <span className="page-info">
                    Page {currentPage + 1} of {totalPages}
                </span>
                
                <button 
                    onClick={handleNextPage} 
                    disabled={currentPage >= totalPages - 1}
                    className="btn-pf8"
                >
                    Next (PF8) ►
                </button>
                
                <button 
                    onClick={handleReturn}
                    className="btn-pf3"
                >
                    Return (PF3)
                </button>
            </div>
        </div>
    );
};
```

---

## 8. COBOL Copybook to JPA Entity

### 8.1 Copybook Record Layout Transformation

#### COBOL Copybook (CVACT01Y.cpy)

```cobol
      ******************************************************************
      * Account Record Layout
      ******************************************************************
       01 ACCOUNT-RECORD.
           05 ACCT-ID              PIC 9(11).
           05 ACCT-CUSTOMER-ID     PIC 9(9).
           05 ACCT-STATUS          PIC X(1).
               88 ACCT-ACTIVE      VALUE 'A'.
               88 ACCT-CLOSED      VALUE 'C'.
               88 ACCT-SUSPENDED   VALUE 'S'.
           05 ACCT-OPEN-DATE       PIC X(10).
           05 ACCT-EXPIRATION-DATE PIC X(10).
           05 ACCT-CURR-BAL        PIC S9(13)V99 COMP-3.
           05 ACCT-CREDIT-LIMIT    PIC S9(13)V99 COMP-3.
           05 ACCT-CASH-LIMIT      PIC S9(13)V99 COMP-3.
           05 ACCT-GROUP-ID        PIC X(10).
```

#### Java JPA Entity

```java
package com.carddemo.entity;

import javax.persistence.*;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Account Entity - Equivalent to COBOL ACCOUNT-RECORD copybook.
 * Maps VSAM ACCTDAT file to PostgreSQL account table.
 */
@Entity
@Table(name = "account", indexes = {
    @Index(name = "idx_account_customer", columnList = "customer_id"),
    @Index(name = "idx_account_status", columnList = "account_status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    
    @Id
    @Column(name = "account_id", length = 11, nullable = false)
    @Pattern(regexp = "\\d{11}", message = "Account ID must be 11 digits")
    private String accountId;  // ACCT-ID PIC 9(11)
    
    @Column(name = "customer_id", length = 9, nullable = false)
    @Pattern(regexp = "\\d{9}", message = "Customer ID must be 9 digits")
    private String customerId;  // ACCT-CUSTOMER-ID PIC 9(9)
    
    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", length = 1, nullable = false)
    private AccountStatus status;  // ACCT-STATUS with 88-level conditions
    
    @Column(name = "open_date", nullable = false)
    private LocalDate openDate;  // ACCT-OPEN-DATE PIC X(10)
    
    @Column(name = "expiration_date")
    private LocalDate expirationDate;  // ACCT-EXPIRATION-DATE PIC X(10)
    
    @Column(name = "current_balance", precision = 15, scale = 2, nullable = false)
    @Digits(integer = 13, fraction = 2)
    private BigDecimal currentBalance;  // ACCT-CURR-BAL PIC S9(13)V99 COMP-3
    
    @Column(name = "credit_limit", precision = 15, scale = 2, nullable = false)
    @Digits(integer = 13, fraction = 2)
    private BigDecimal creditLimit;  // ACCT-CREDIT-LIMIT PIC S9(13)V99 COMP-3
    
    @Column(name = "cash_limit", precision = 15, scale = 2, nullable = false)
    @Digits(integer = 13, fraction = 2)
    private BigDecimal cashLimit;  // ACCT-CASH-LIMIT PIC S9(13)V99 COMP-3
    
    @Column(name = "group_id", length = 10)
    private String groupId;  // ACCT-GROUP-ID PIC X(10)
    
    // Foreign key relationship
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false)
    private Customer customer;
    
    // One-to-many relationship with cards
    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Card> cards = new ArrayList<>();
    
    /**
     * Update balance with transaction amount.
     * Preserves COMP-3 precision with RoundingMode.HALF_UP.
     */
    public void addTransaction(BigDecimal amount) {
        this.currentBalance = this.currentBalance
            .add(amount)
            .setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * Check if account is active (88-level condition equivalent).
     */
    public boolean isActive() {
        return this.status == AccountStatus.ACTIVE;
    }
    
    /**
     * Check if balance is within credit limit.
     */
    public boolean hasAvailableCredit(BigDecimal amount) {
        BigDecimal availableCredit = this.creditLimit.subtract(this.currentBalance);
        return availableCredit.compareTo(amount) >= 0;
    }
}

/**
 * Account Status Enum - Maps COBOL 88-level conditions.
 */
public enum AccountStatus {
    ACTIVE('A', "Active"),          // 88 ACCT-ACTIVE VALUE 'A'
    CLOSED('C', "Closed"),          // 88 ACCT-CLOSED VALUE 'C'
    SUSPENDED('S', "Suspended");    // 88 ACCT-SUSPENDED VALUE 'S'
    
    private final char code;
    private final String description;
    
    AccountStatus(char code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public char getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static AccountStatus fromCode(char code) {
        for (AccountStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid account status code: " + code);
    }
}
```

### 8.2 Copybook Group Items to Nested Classes

```cobol
COBOL:
       01 TRANSACTION-RECORD.
           05 TRAN-ID              PIC 9(16).
           05 TRAN-TYPE-CD         PIC X(2).
           05 TRAN-CATEGORY-CD     PIC 9(4).
           05 TRAN-AMT             PIC S9(9)V99 COMP-3.
           05 TRAN-MERCHANT-DATA.
               10 TRAN-MERCHANT-ID     PIC X(15).
               10 TRAN-MERCHANT-NAME   PIC X(50).
               10 TRAN-MERCHANT-CITY   PIC X(50).
               10 TRAN-MERCHANT-ZIP    PIC X(10).
```

Java (Option 1 - Flatten fields):
```java
@Entity
@Table(name = "transaction")
public class Transaction {
    @Id
    @Column(name = "transaction_id", length = 16)
    private String transactionId;
    
    @Column(name = "type_code", length = 2)
    private String typeCode;
    
    @Column(name = "category_code", length = 4)
    private String categoryCode;
    
    @Column(name = "amount", precision = 11, scale = 2)
    private BigDecimal amount;
    
    // Flattened merchant data
    @Column(name = "merchant_id", length = 15)
    private String merchantId;
    
    @Column(name = "merchant_name", length = 50)
    private String merchantName;
    
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;
    
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;
}
```

Java (Option 2 - Embeddable nested class):
```java
@Embeddable
public class MerchantData {
    @Column(name = "merchant_id", length = 15)
    private String merchantId;
    
    @Column(name = "merchant_name", length = 50)
    private String merchantName;
    
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;
    
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;
}

@Entity
@Table(name = "transaction")
public class Transaction {
    @Id
    @Column(name = "transaction_id", length = 16)
    private String transactionId;
    
    @Column(name = "type_code", length = 2)
    private String typeCode;
    
    @Column(name = "category_code", length = 4)
    private String categoryCode;
    
    @Column(name = "amount", precision = 11, scale = 2)
    private BigDecimal amount;
    
    @Embedded
    private MerchantData merchantData;  // Group item as embedded object
}
```

---

## 9. JCL Batch Job to Spring Batch

### 9.1 JCL Job Structure to Spring Batch Configuration

#### JCL Job Definition

```jcl
//INTCALC  JOB (ACCT),'INTEREST CALC',CLASS=A,MSGCLASS=X,
//         NOTIFY=&SYSUID
//********************************************************************
//* Interest Calculation Batch Job
//********************************************************************
//STEP01   EXEC PGM=CBACT04C
//STEPLIB  DD   DSN=AWS.M2.LOADLIB,DISP=SHR
//ACCTFILE DD   DSN=AWS.M2.ACCTDAT.VSAM,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//SYSIN    DD   *
  INTEREST_RATE=0.15
  CALCULATION_DATE=20240101
/*
```

#### Spring Batch Job Configuration

```java
package com.carddemo.batch.job;

import com.carddemo.batch.processor.InterestCalculationProcessor;
import com.carddemo.batch.reader.AccountItemReader;
import com.carddemo.batch.writer.AccountItemWriter;
import com.carddemo.entity.Account;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Interest Calculation Job - Equivalent to JCL INTCALC job (CBACT04C program).
 * Calculates and applies interest to all active accounts.
 */
@Configuration
@EnableBatchProcessing
public class InterestCalculationJobConfig {
    
    @Value("${batch.interest.rate:0.15}")
    private String interestRate;
    
    @Value("${batch.chunk.size:1000}")
    private int chunkSize;
    
    /**
     * Define the interest calculation job.
     */
    @Bean
    public Job interestCalculationJob(JobRepository jobRepository,
                                      Step interestCalculationStep) {
        return new JobBuilder("interestCalculationJob", jobRepository)
            .start(interestCalculationStep)
            .build();
    }
    
    /**
     * Define the interest calculation step with chunk processing.
     * Equivalent to JCL STEP01 EXEC PGM=CBACT04C.
     */
    @Bean
    public Step interestCalculationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<Account> accountReader,
            ItemProcessor<Account, Account> interestProcessor,
            ItemWriter<Account> accountWriter) {
        
        return new StepBuilder("interestCalculationStep", jobRepository)
            .<Account, Account>chunk(chunkSize, transactionManager)
            .reader(accountReader)
            .processor(interestProcessor)
            .writer(accountWriter)
            .build();
    }
}
```

### 9.2 ItemReader - Sequential File Read

```java
package com.carddemo.batch.reader;

import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;

import java.util.Map;

/**
 * Account Item Reader - Equivalent to COBOL sequential file read.
 * Reads accounts in sorted order like VSAM STARTBR/READNEXT.
 */
@Configuration
public class AccountItemReaderConfig {
    
    @Autowired
    private AccountRepository accountRepository;
    
    @Bean
    public RepositoryItemReader<Account> accountReader() {
        return new RepositoryItemReaderBuilder<Account>()
            .name("accountReader")
            .repository(accountRepository)
            .methodName("findAll")
            .pageSize(1000)  // Read in chunks of 1000
            .sorts(Map.of("accountId", Sort.Direction.ASC))  // Sorted like VSAM
            .build();
    }
}
```

### 9.3 ItemProcessor - Business Logic

```java
package com.carddemo.batch.processor;

import com.carddemo.entity.Account;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Interest Calculation Processor - Equivalent to COBOL CBACT04C logic.
 * Calculates interest on account balances.
 */
@Component
public class InterestCalculationProcessor implements ItemProcessor<Account, Account> {
    
    @Value("${batch.interest.rate:0.15}")
    private String interestRateStr;
    
    /**
     * Process each account to calculate and apply interest.
     * Equivalent to COBOL COMPUTE INTEREST-AMOUNT logic.
     */
    @Override
    public Account process(Account account) throws Exception {
        // Skip inactive accounts (equivalent to IF ACCT-STATUS = 'A')
        if (!account.isActive()) {
            return null;  // Returning null skips this item
        }
        
        // Parse interest rate (SYSIN parameter equivalent)
        BigDecimal interestRate = new BigDecimal(interestRateStr)
            .setScale(5, RoundingMode.HALF_UP);
        
        // Calculate interest: INTEREST = BALANCE * RATE
        // CRITICAL: Must preserve COMP-3 precision
        BigDecimal currentBalance = account.getCurrentBalance();
        BigDecimal interestAmount = currentBalance
            .multiply(interestRate)
            .setScale(2, RoundingMode.HALF_UP);  // Match COBOL rounding
        
        // Apply interest to balance: ADD INTEREST TO BALANCE
        account.addTransaction(interestAmount);
        
        return account;
    }
}
```

### 9.4 ItemWriter - Database Update

```java
package com.carddemo.batch.writer;

import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.batch.item.data.builder.RepositoryItemWriterBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Account Item Writer - Equivalent to COBOL REWRITE operation.
 * Updates account records in database.
 */
@Configuration
public class AccountItemWriterConfig {
    
    @Autowired
    private AccountRepository accountRepository;
    
    @Bean
    public RepositoryItemWriter<Account> accountWriter() {
        return new RepositoryItemWriterBuilder<Account>()
            .repository(accountRepository)
            .methodName("save")
            .build();
    }
}
```

### 9.5 Kubernetes CronJob for Batch Scheduling

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: interest-calculation-cronjob
  namespace: carddemo
spec:
  # Schedule: Run monthly on first day at 2 AM (equivalent to JCL schedule)
  schedule: "0 2 1 * *"
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: interest-calculation-job
            image: carddemo-backend:latest
            command:
            - java
            - -jar
            - /app/carddemo-backend.jar
            - --spring.batch.job.names=interestCalculationJob
            - --batch.interest.rate=0.15
            env:
            - name: SPRING_PROFILES_ACTIVE
              value: "production"
            - name: SPRING_DATASOURCE_URL
              valueFrom:
                configMapKeyRef:
                  name: carddemo-config
                  key: database.url
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: carddemo-secrets
                  key: database.username
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: carddemo-secrets
                  key: database.password
          restartPolicy: OnFailure
      backoffLimit: 3
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
```

---

## 10. Date Format Conversion

### 10.1 CEEDAYS Lilian Date to Java LocalDate

COBOL uses CEEDAYS to convert dates to/from Lilian format (days since October 15, 1582).

```cobol
COBOL:
       CALL 'CEEDAYS' USING WS-DATE-YYYYMMDD, WS-LILIAN-DATE, 
                            WS-FC.
       COMPUTE WS-LILIAN-PLUS-30 = WS-LILIAN-DATE + 30.
       CALL 'CEEDATE' USING WS-LILIAN-PLUS-30, WS-PICSTR, 
                            WS-NEW-DATE, WS-FC.
```

Java Equivalent:
```java
package com.carddemo.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Date conversion utilities - Equivalent to COBOL CEEDAYS/CEEDATE.
 */
public class DateUtils {
    
    // Lilian date base: October 15, 1582
    public static final LocalDate LILIAN_BASE_DATE = LocalDate.of(1582, 10, 15);
    
    // Common date formatters
    public static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    public static final DateTimeFormatter CCYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    public static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    
    /**
     * Convert Lilian days to LocalDate.
     * Equivalent to CEEDATE.
     */
    public static LocalDate lilianToLocalDate(int lilianDays) {
        return LILIAN_BASE_DATE.plusDays(lilianDays);
    }
    
    /**
     * Convert LocalDate to Lilian days.
     * Equivalent to CEEDAYS.
     */
    public static int localDateToLilian(LocalDate date) {
        return (int) ChronoUnit.DAYS.between(LILIAN_BASE_DATE, date);
    }
    
    /**
     * Add days to a date (equivalent to LILIAN arithmetic).
     */
    public static LocalDate addDays(LocalDate date, int days) {
        return date.plusDays(days);
    }
    
    /**
     * Parse YYYYMMDD string to LocalDate.
     */
    public static LocalDate parseYYYYMMDD(String dateStr) {
        return LocalDate.parse(dateStr, YYYYMMDD);
    }
    
    /**
     * Format LocalDate to YYYYMMDD string.
     */
    public static String formatYYYYMMDD(LocalDate date) {
        return date.format(YYYYMMDD);
    }
    
    /**
     * Calculate days between two dates.
     */
    public static long daysBetween(LocalDate startDate, LocalDate endDate) {
        return ChronoUnit.DAYS.between(startDate, endDate);
    }
}
```

---

## 11. Error Handling Transformation

### 11.1 COBOL File Status to Java Exceptions

| COBOL File Status | Meaning | Java Exception | HTTP Status |
|------------------|---------|----------------|-------------|
| 00 | Successful operation | No exception | 200 OK |
| 22 | Duplicate key | DataIntegrityViolationException | 409 Conflict |
| 23 | Record not found | EntityNotFoundException | 404 Not Found |
| 35 | File not open | IllegalStateException | 500 Internal Error |
| 92 | Logic error | IllegalArgumentException | 400 Bad Request |
| 9x | File I/O error | DataAccessException | 500 Internal Error |

### 11.2 Global Exception Handler

```java
package com.carddemo.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.dao.DataIntegrityViolationException;
import javax.persistence.EntityNotFoundException;

@ControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException ex) {
        ErrorResponse error = new ErrorResponse();
        error.setReturnCode(23);  // COBOL file status 23
        error.setErrorMessage(ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }
    
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(
            DataIntegrityViolationException ex) {
        ErrorResponse error = new ErrorResponse();
        error.setReturnCode(22);  // COBOL file status 22
        error.setErrorMessage("Duplicate key violation");
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }
}
```

---

## 12. Functional Equivalence Validation

### 12.1 Test Pattern for Equivalence

```java
@SpringBootTest
public class InterestCalculationServiceTest {
    
    @Autowired
    private InterestCalculationService service;
    
    /**
     * Test that interest calculation matches COBOL output exactly.
     */
    @Test
    public void testInterestCalculationMatchesCOBOL() {
        // Given: Same input as COBOL test case
        BigDecimal balance = new BigDecimal("10000.00");
        BigDecimal interestRate = new BigDecimal("0.15000");
        
        // When: Calculate interest
        BigDecimal interest = service.calculateInterest(balance, interestRate);
        
        // Then: Result matches COBOL output exactly
        BigDecimal expectedInterest = new BigDecimal("1500.00");
        assertEquals(0, interest.compareTo(expectedInterest),
            "Interest calculation must match COBOL COMP-3 precision");
    }
}
```

---

## 13. Common Pitfalls and Solutions

### 13.1 Rounding Discrepancies
- **Problem**: Java default rounding differs from COBOL
- **Solution**: Always specify `RoundingMode.HALF_UP` for COMP-3 equivalence

### 13.2 String Length Validation
- **Problem**: COBOL truncates, Java throws exception
- **Solution**: Use `@Size` annotation and validate lengths

### 13.3 Transaction Boundary Errors
- **Problem**: Missing CICS SYNCPOINT equivalents
- **Solution**: Ensure all service methods have `@Transactional` annotations

---

## 14. Migration Checklist

For each COBOL program being migrated:

- [ ] Identify all WORKING-STORAGE variables → Java instance fields
- [ ] Map all PIC clauses to Java types with correct precision
- [ ] Convert all PERFORM paragraphs to Java methods
- [ ] Transform CICS commands to Spring annotations
- [ ] Map file I/O to JPA repository calls
- [ ] Preserve COMP-3 decimal precision with BigDecimal
- [ ] Add @Transactional for SYNCPOINT equivalence
- [ ] Create equivalent error handling
- [ ] Write unit tests matching COBOL test cases
- [ ] Validate functional equivalence with side-by-side testing

---

## Conclusion

This migration guide provides comprehensive patterns for transforming CardDemo COBOL application to Java Spring Boot. All transformations preserve functional equivalence while modernizing the technology stack. Critical attention to numeric precision, transaction semantics, and business logic preservation ensures a successful migration with zero data loss or behavioral changes.
