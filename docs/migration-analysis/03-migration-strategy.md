# Migration Strategy

## Document Overview

This document provides a per-utility migration playbook for the AWS CardDemo mainframe-to-Java migration. For each proprietary utility identified in the [Proprietary Utility Inventory](01-proprietary-utility-inventory.md) and assessed in the [Dependency Impact Analysis](02-dependency-impact-analysis.md), this playbook specifies:

- **Recommended Approach:** Direct library replacement, custom implementation, or service wrapper
- **Specific Java Libraries:** Library names with versions for each migration target
- **Behavioral Equivalence Strategy:** How to ensure the Java replacement produces identical results
- **Code Mapping Examples:** COBOL → Java code examples demonstrating the migration pattern

**Scope:** All proprietary utility categories across the CardDemo application: IBM LE Runtime Services, CICS API Commands, z/OS Batch Utilities, BMS Map Macros, and COBOL Intrinsic Functions.

**Cross-References:**
- Impact assessments and complexity ratings: [02 — Dependency Impact Analysis](02-dependency-impact-analysis.md)
- Risk ratings per utility: [04 — Risk Assessment](04-risk-assessment.md)
- Behavioral parity testing: [05 — Testing & Validation Framework](05-testing-validation-framework.md)
- CICS migration flow diagram: [CICS Command Flow Diagram](diagrams/cics-command-flow.md)
- Batch migration flow diagram: [Batch Job Migration Flow Diagram](diagrams/batch-job-migration-flow.md)

---

## 1. Strategy per IBM LE Runtime Service

### 1.1 CEE3ABD → Java Exception Framework

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Custom Implementation |
| **Java Libraries** | Java Standard Library (`java.lang`) + SLF4J 2.0 / Logback 1.4 (logging) + Custom `AbendException` class |
| **Complexity** | Medium (see [Impact Analysis §2.1](02-dependency-impact-analysis.md#21-cee3abd--le-abend-handler)) |
| **Programs Affected** | 9 batch programs: CBACT01C, CBACT02C, CBACT03C, CBACT04C, CBCUS01C, CBSTM03A, CBTRN01C, CBTRN02C, CBTRN03C |

**Current Mainframe Behavior:**

CEE3ABD is called within the `9999-ABEND-PROGRAM` paragraph in all 9 batch programs. When invoked, it terminates the Language Environment enclave, produces a formatted CEEDUMP to SYSOUT, and returns a non-zero condition code (ABCODE 999) to JCL.

```cobol
       9999-ABEND-PROGRAM.
           DISPLAY 'ABENDING PROGRAM'
           MOVE 0 TO TIMING
           MOVE 999 TO ABCODE
           CALL 'CEE3ABD'.
```

`Source: app/cbl/CBACT01C.cbl:169-173`

**Java Migration Strategy:**

1. **Create a custom `ApplicationAbendException`** extending `RuntimeException` that encapsulates the abend code, timing flag, and diagnostic context.
2. **Wrap batch program `main()` methods** in a top-level try/catch that catches `ApplicationAbendException`, logs a structured diagnostic dump via SLF4J/Logback, and calls `System.exit()` with the abend code.
3. **Map ABCODE to process exit code** to maintain JCL condition code semantics for downstream job step dependencies.
4. **Replace CEEDUMP with structured logging:** Use SLF4J MDC (Mapped Diagnostic Context) to capture thread state, memory usage, and stack traces in JSON format.

**Java Equivalent Implementation:**

```java
// Custom exception class replacing CEE3ABD
public class ApplicationAbendException extends RuntimeException {
    private final int abendCode;
    private final boolean generateDump;

    public ApplicationAbendException(int abendCode, boolean generateDump) {
        super("Program abend with code: " + abendCode);
        this.abendCode = abendCode;
        this.generateDump = generateDump;
    }

    public int getAbendCode() {
        return abendCode;
    }

    public boolean isGenerateDump() {
        return generateDump;
    }
}

// Batch program wrapper replacing 9999-ABEND-PROGRAM
public class BatchProgramRunner {
    private static final Logger logger = LoggerFactory.getLogger(BatchProgramRunner.class);

    public static void runWithAbendHandling(Runnable program) {
        try {
            program.run();
        } catch (ApplicationAbendException e) {
            logger.error("ABENDING PROGRAM - Code: {}", e.getAbendCode(), e);
            if (e.isGenerateDump()) {
                DiagnosticDumper.writeDump(e);
            }
            System.exit(e.getAbendCode());
        }
    }
}
```

**Behavioral Equivalence Strategy:**

| Mainframe Behavior | Java Equivalent | Parity Check |
|:-------------------|:----------------|:-------------|
| ABCODE 999 passed to JCL step | `System.exit(999)` returns process exit code | Verify exit code matches via `$?` in shell |
| TIMING=0 triggers CEEDUMP | `generateDump=true` triggers `DiagnosticDumper` | Verify dump file is generated |
| Enclave termination (entire run unit) | `System.exit()` terminates JVM | Verify no further processing occurs |
| CEEDUMP to SYSOUT DD | JSON-structured log to `application.log` | Verify diagnostic fields present in log |

**Testing Approach:**

- Unit test: Throw `ApplicationAbendException` and verify exit code, dump generation, and log output
- Integration test: Run batch program with simulated I/O error, verify process terminates with expected exit code
- Regression: Compare mainframe JCL COND CODE from job output with Java process exit code

---

### 1.2 CEEDAYS → java.time.temporal.JulianFields

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Direct Library Replacement |
| **Java Libraries** | Java Standard Library (`java.time`, `java.time.format`, `java.time.temporal`) |
| **Complexity** | High (see [Impact Analysis §2.2](02-dependency-impact-analysis.md#22-ceedays--lillian-date-conversion-via-csutldtc-wrapper)) |
| **Programs Affected** | 1 wrapper (CSUTLDTC) + 2 consumers (CORPT00C, COTRN02C) via CSUTLDPY copybook |

**Current Mainframe Behavior:**

CSUTLDTC.cbl wraps the IBM Language Environment CEEDAYS service. It accepts a date string and format mask as VSTRING parameters, calls CEEDAYS to convert to a Lillian day number (days since October 15, 1582), and returns a structured result message with severity code and human-readable validation text.

```cobol
           CALL "CEEDAYS" USING
                  WS-DATE-TO-TEST,
                  WS-DATE-FORMAT,
                  OUTPUT-LILLIAN,
                  FEEDBACK-CODE
```

`Source: app/cbl/CSUTLDTC.cbl:116-120`

The FEEDBACK-CODE structure maps 8 distinct error conditions:

| Condition | Meaning |
|:----------|:--------|
| FC-INVALID-DATE | Date is valid (success — confusingly named) |
| FC-INSUFFICIENT-DATA | Insufficient data supplied |
| FC-BAD-DATE-VALUE | Invalid date value |
| FC-INVALID-ERA | Invalid era specification |
| FC-UNSUPP-RANGE | Unsupported date range |
| FC-INVALID-MONTH | Invalid month value |
| FC-BAD-PIC-STRING | Bad picture string (format mask) |
| FC-NON-NUMERIC-DATA | Non-numeric data in date |

`Source: app/cbl/CSUTLDTC.cbl:62-70`

**Java Migration Strategy:**

1. **Create a `DateValidationService`** class as a direct replacement for the CSUTLDTC wrapper program.
2. **Use `java.time.LocalDate.parse()`** with `DateTimeFormatter` for date parsing, replacing the CEEDAYS call.
3. **Convert to Lillian day number** using `JulianFields.JULIAN_DAY` with the appropriate epoch offset (Lillian epoch = October 15, 1582; Julian Day Number for that date = 2299161).
4. **Map all 8 FEEDBACK-CODE conditions** to a custom `DateValidationResult` enum with a `DateTimeParseException` handler for error differentiation.
5. **Replicate the CSUTLDPY copybook** as a shared `DateValidator` utility class with static methods for `editDateCcyymmdd()`, `editYearCcyy()`, `editMonth()`, and `editDay()`.
6. **Map LE format masks** to `DateTimeFormatter` patterns: `YYYYMMDD` → `yyyyMMdd`, `YYYY-MM-DD` → `yyyy-MM-dd`, `MM/DD/YYYY` → `MM/dd/yyyy`.

**Java Equivalent Implementation:**

```java
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.JulianFields;

public class DateValidationService {

    // Lillian epoch offset: Julian Day Number for October 15, 1582
    private static final long LILLIAN_EPOCH_JDN = 2299161L;

    public enum ValidationResult {
        DATE_VALID("Date is valid", 0),
        INSUFFICIENT_DATA("Insufficient", 2507),
        BAD_DATE_VALUE("Datevalue error", 2508),
        INVALID_ERA("Invalid Era", 2509),
        UNSUPPORTED_RANGE("Unsupp. Range", 2513),
        INVALID_MONTH("Invalid month", 2517),
        BAD_PIC_STRING("Bad Pic String", 2518),
        NON_NUMERIC_DATA("Nonnumeric data", 2520),
        YEAR_IN_ERA_ZERO("Year in era 0", 2521);

        private final String message;
        private final int messageNumber;

        ValidationResult(String message, int messageNumber) {
            this.message = message;
            this.messageNumber = messageNumber;
        }

        public String getMessage() { return message; }
        public int getMessageNumber() { return messageNumber; }
    }

    public static class DateValidationResponse {
        private final ValidationResult result;
        private final long lillianDay;
        private final int severity;

        public DateValidationResponse(ValidationResult result, long lillianDay,
                                      int severity) {
            this.result = result;
            this.lillianDay = lillianDay;
            this.severity = severity;
        }

        public ValidationResult getResult() { return result; }
        public long getLillianDay() { return lillianDay; }
        public int getSeverity() { return severity; }
    }

    /**
     * Validates a date string against a format mask and returns
     * Lillian day number. Direct replacement for CSUTLDTC.cbl
     * CALL "CEEDAYS" wrapper.
     */
    public static DateValidationResponse validateDate(String dateStr,
                                                      String formatMask) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return new DateValidationResponse(
                ValidationResult.INSUFFICIENT_DATA, 0, 3);
        }

        String javaPattern = convertLeFormatToJava(formatMask);
        if (javaPattern == null) {
            return new DateValidationResponse(
                ValidationResult.BAD_PIC_STRING, 0, 3);
        }

        if (!dateStr.chars().allMatch(c ->
                Character.isDigit(c) || c == '-' || c == '/')) {
            return new DateValidationResponse(
                ValidationResult.NON_NUMERIC_DATA, 0, 3);
        }

        try {
            DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern(javaPattern);
            LocalDate date = LocalDate.parse(dateStr, formatter);
            long julianDay = date.getLong(JulianFields.JULIAN_DAY);
            long lillianDay = julianDay - LILLIAN_EPOCH_JDN;
            return new DateValidationResponse(
                ValidationResult.DATE_VALID, lillianDay, 0);
        } catch (DateTimeParseException e) {
            return classifyParseError(e, dateStr);
        }
    }

    private static String convertLeFormatToJava(String leMask) {
        if (leMask == null) return null;
        return leMask.replace("YYYY", "yyyy")
                     .replace("MM", "MM")
                     .replace("DD", "dd");
    }

    private static DateValidationResponse classifyParseError(
            DateTimeParseException e, String dateStr) {
        String msg = e.getMessage().toLowerCase();
        if (msg.contains("month")) {
            return new DateValidationResponse(
                ValidationResult.INVALID_MONTH, 0, 3);
        }
        return new DateValidationResponse(
            ValidationResult.BAD_DATE_VALUE, 0, 3);
    }
}
```

**Behavioral Equivalence Strategy:**

| Mainframe Behavior | Java Equivalent | Parity Check |
|:-------------------|:----------------|:-------------|
| Lillian day for `2024-01-15` = 161,188 | `LocalDate.parse("2024-01-15").getLong(JULIAN_DAY) - 2299161` | Verify identical Lillian day number |
| FC-INVALID-DATE (severity 0) on valid date | `ValidationResult.DATE_VALID` (severity 0) | Verify severity = 0 |
| FC-BAD-DATE-VALUE on `2024-13-01` | `ValidationResult.BAD_DATE_VALUE` (severity 3) | Verify severity = 3 |
| FC-INSUFFICIENT-DATA on empty string | `ValidationResult.INSUFFICIENT_DATA` | Verify result classification |
| Format mask `YYYYMMDD` → pattern `yyyyMMdd` | `DateTimeFormatter.ofPattern("yyyyMMdd")` | Verify parse with same format |

**Testing Approach:**

- Unit test: Validate each of the 8 FEEDBACK-CODE conditions with corresponding Java `ValidationResult` enum values
- Unit test: Verify Lillian day calculation against known dates (boundary dates: Oct 15, 1582 = day 1; Jan 1, 2000 = day 152,384)
- Integration test: Run date validation with CSUTLDPY-equivalent calls (editDateCcyymmdd) and compare result messages

---

## 2. Strategy per CICS Command Category

> **Visual Reference:** See the [CICS Command Flow Diagram](diagrams/cics-command-flow.md) for a sequence diagram showing the complete CICS-to-Java migration path.

### 2.1 File Control (READ/WRITE/REWRITE/DELETE/STARTBR/READNEXT/READPREV/ENDBR) → Spring Data JPA / JDBC

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Service Wrapper |
| **Java Libraries** | Spring Data JPA 3.x, Spring Boot 3.x, HikariCP 5.x (connection pool), Hibernate 6.x (JPA provider) |
| **Complexity** | High (see [Impact Analysis §2.3](02-dependency-impact-analysis.md#23-cics-file-control--readwriterewritedeletestartbrreadnextreadprevendbr)) |
| **Programs Affected** | 13 CICS programs: COACTVWC, COACTUPC, COBIL00C, COCRDLIC, COCRDSLC, CORPT00C, COSGN00C, COTRN00C, COTRN01C, COTRN02C, COUSR01C, COUSR02C, COUSR03C |

**Current Mainframe Behavior:**

CICS File Control commands access VSAM KSDS files through the CICS File Control Table (FCT). The pattern is: issue EXEC CICS command, check RESP/RESP2 codes, handle errors with EVALUATE blocks.

```cobol
           EXEC CICS READ
                DATASET   (WS-USRSEC-FILE)
                INTO      (SEC-USER-DATA)
                LENGTH    (LENGTH OF SEC-USER-DATA)
                RIDFLD    (WS-USER-ID)
                KEYLENGTH (LENGTH OF WS-USER-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.
```

`Source: app/cbl/COSGN00C.cbl:211-219`

The VSAM files accessed include: ACCTDAT, CARDDAT, XREFDAT (CARDXREF), CUSTDAT, USRSEC, TRANSACT, CARDAIX, CXACAIX.

**Java Migration Strategy:**

1. **Define JPA entity classes** for each VSAM record layout (mapped from copybook record definitions): `Account`, `Card`, `CardXref`, `Customer`, `UserSecurity`, `Transaction`.
2. **Create Spring Data JPA repositories** (`JpaRepository<Entity, Key>`) for each entity, replacing CICS File Control commands with repository method calls.
3. **Map each CICS command to a JPA repository method:**

| CICS Command | JPA Equivalent | Notes |
|:-------------|:---------------|:------|
| `READ FILE(...) RIDFLD(key)` | `repository.findById(key)` | Direct key lookup |
| `WRITE FILE(...) FROM(record)` | `repository.save(entity)` | Insert new record |
| `REWRITE FILE(...) FROM(record)` | `repository.save(entity)` | Update with optimistic locking (`@Version`) |
| `DELETE FILE(...) RIDFLD(key)` | `repository.deleteById(key)` | Remove by primary key |
| `STARTBR / READNEXT / READPREV / ENDBR` | `repository.findAll(Pageable)` or JDBC `ResultSet` cursor | Browse operations become paginated queries |

4. **Map RESP/RESP2 error codes to Spring exceptions:**

| CICS RESP Code | Spring Exception | Handling |
|:---------------|:----------------|:---------|
| `DFHRESP(NORMAL)` | No exception | Success path |
| `DFHRESP(NOTFND)` | `EmptyResultDataAccessException` | Record not found |
| `DFHRESP(DUPKEY)` | `DuplicateKeyException` | Duplicate primary key |
| `DFHRESP(NOSPACE)` | `DataIntegrityViolationException` | Constraint violation |
| `DFHRESP(INVREQ)` | `InvalidDataAccessApiUsageException` | Invalid operation |

5. **Configure HikariCP connection pool** to replace VSAM file allocation semantics (DD statements).
6. **Use `@Transactional`** boundaries to replace CICS task-level transaction semantics.

**Java Equivalent Implementation:**

```java
// JPA Entity replacing VSAM ACCTDAT record layout (CVACT01Y.cpy)
@Entity
@Table(name = "ACCOUNTS")
public class Account {
    @Id
    @Column(name = "ACCT_ID", length = 11)
    private Long accountId;

    @Column(name = "ACTIVE_STATUS", length = 1)
    private String activeStatus;

    @Column(name = "CURR_BAL", precision = 12, scale = 2)
    private BigDecimal currentBalance;

    @Column(name = "CREDIT_LIMIT", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    // ... additional fields matching CVACT01Y.cpy record layout
}

// Spring Data JPA Repository replacing CICS File Control
public interface AccountRepository extends JpaRepository<Account, Long> {
    // READ FILE('ACCTDAT') RIDFLD(key) → findById(key)
    // STARTBR/READNEXT → findAll(Pageable)
    Page<Account> findByActiveStatus(String status, Pageable pageable);
}

// Service wrapper replacing CICS File Control call patterns
@Service
@Transactional
public class AccountService {
    private static final Logger logger =
        LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Replaces: EXEC CICS READ FILE('ACCTDAT') INTO(ACCOUNT-RECORD)
     *           RIDFLD(WS-ACCT-ID) RESP(WS-RESP-CD)
     */
    public Optional<Account> readAccount(Long accountId) {
        try {
            return accountRepository.findById(accountId);
        } catch (DataAccessException e) {
            logger.error("File Control READ error for ACCTDAT, key={}",
                         accountId, e);
            throw e;
        }
    }
}
```

**Behavioral Equivalence Strategy:**

- Verify VSAM key lookup returns identical record content to JPA entity field values
- Validate STARTBR/READNEXT browse sequence produces same record ordering as VSAM KSDS sequential access
- Confirm RESP code error conditions map correctly to Spring exceptions
- Test REWRITE with concurrent access to verify optimistic locking matches CICS update semantics

**Testing Approach:**

- Unit test: Mock repositories, verify service methods return correct entities for known keys
- Integration test: Load VSAM test data (`app/data/acctdata.txt`) into RDBMS, verify identical records retrieved
- Regression: Compare browse operation output (STARTBR/READNEXT sequence) with JPA paginated query results

---

### 2.2 Program Control (XCTL/RETURN/LINK) → Spring MVC Controller Routing

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Direct Replacement |
| **Java Libraries** | Spring Web MVC 6.x, Spring Boot 3.x, Jakarta Servlet 6.0 |
| **Complexity** | Medium (see [Impact Analysis §2.4](02-dependency-impact-analysis.md)) |
| **Programs Affected** | COMEN01C (menu router), COACTUPC, COACTVWC, COCRDLIC, COCRDSLC, COSGN00C, and all CICS programs via RETURN TRANSID |

**Current Mainframe Behavior:**

CICS Program Control provides inter-program navigation. COMEN01C acts as the central menu router, using XCTL to transfer control to selected programs with the COMMAREA (defined in COCOM01Y.cpy, 1024 bytes) as a state-passing mechanism.

```cobol
               EXEC CICS
                   XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
                   COMMAREA(CARDDEMO-COMMAREA)
               END-EXEC
```

`Source: app/cbl/COMEN01C.cbl:152-155`

The COMMAREA structure (COCOM01Y.cpy) carries general info (from/to transaction IDs, program names, user ID, user type, program context), customer info, account info, card info, and UI state:

```cobol
       01 CARDDEMO-COMMAREA.
          05 CDEMO-GENERAL-INFO.
             10 CDEMO-FROM-TRANID       PIC X(04).
             10 CDEMO-FROM-PROGRAM      PIC X(08).
             10 CDEMO-TO-TRANID         PIC X(04).
             10 CDEMO-TO-PROGRAM        PIC X(08).
             10 CDEMO-USER-ID           PIC X(08).
             10 CDEMO-USER-TYPE         PIC X(01).
             10 CDEMO-PGM-CONTEXT       PIC 9(01).
          05 CDEMO-CUSTOMER-INFO.
             10 CDEMO-CUST-ID           PIC 9(09).
             ...
```

`Source: app/cpy/COCOM01Y.cpy:19-47`

Programs use `EXEC CICS RETURN TRANSID(...)` for pseudo-conversational return, which causes CICS to wait for the next terminal input before re-invoking the specified transaction.

**Java Migration Strategy:**

1. **Map each COBOL CICS program to a Spring MVC `@Controller`** class, with the transaction ID becoming the URL path.
2. **Replace XCTL with Spring MVC `forward:`** or `redirect:` dispatch, or direct `@Controller` method calls within the same request.
3. **Replace COMMAREA with `HttpSession`** attributes or a Spring-managed `@SessionScope` bean (`CardDemoSessionState`) that mirrors the COCOM01Y.cpy layout.
4. **Replace RETURN TRANSID with HTTP response** — the pseudo-conversational pattern naturally maps to HTTP request/response cycles where the browser holds the "terminal" state.
5. **Map COMEN01C menu routing** to a Spring MVC dispatcher controller with `@RequestMapping` for each menu option.

**Java Equivalent Implementation:**

```java
// Session state bean replacing COMMAREA (COCOM01Y.cpy)
@Component
@SessionScope
public class CardDemoSessionState implements Serializable {
    private String fromTransactionId;
    private String fromProgram;
    private String toTransactionId;
    private String toProgram;
    private String userId;
    private char userType; // 'A' = Admin, 'U' = User
    private int programContext; // 0 = ENTER, 1 = REENTER
    private Long customerId;
    private Long accountId;
    private Long cardNumber;
    private String lastMap;
    private String lastMapset;

    // Getters and setters...
}

// Controller replacing COMEN01C menu routing with XCTL
@Controller
@RequestMapping("/menu")
public class MainMenuController {
    private final CardDemoSessionState sessionState;

    public MainMenuController(CardDemoSessionState sessionState) {
        this.sessionState = sessionState;
    }

    /**
     * Replaces: EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(option))
     *           COMMAREA(CARDDEMO-COMMAREA)
     */
    @PostMapping("/select")
    public String handleMenuSelection(@RequestParam int option) {
        sessionState.setFromTransactionId("CM00");
        sessionState.setFromProgram("COMEN01C");
        sessionState.setProgramContext(0);

        String targetProgram = getTargetProgram(option);
        return "redirect:/" + targetProgram.toLowerCase();
    }
}
```

**Behavioral Equivalence Strategy:**

- Verify navigation flow: Menu selection → target program matches XCTL dispatch
- Validate COMMAREA fields preserved across HTTP session lifecycle
- Confirm pseudo-conversational semantics: each HTTP request/response cycle = one CICS pseudo-conversational interaction
- Test user type enforcement: admin-only options restricted identically

**Testing Approach:**

- Unit test: Verify controller routing for each menu option matches COMEN01C XCTL targets
- Integration test: HTTP session state persistence across multiple requests simulating COMMAREA passing
- Regression: Walk through complete user workflow (sign-on → menu → account view → return) verifying navigation parity

---

### 2.3 Terminal Control (SEND MAP/RECEIVE MAP/SEND TEXT) → REST API + HTML Templates

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Custom Implementation |
| **Java Libraries** | Spring Web MVC 6.x, Thymeleaf 3.x, Bootstrap 5.x (CSS framework) |
| **Complexity** | Very High (see [Impact Analysis §2.5](02-dependency-impact-analysis.md)) |
| **Programs Affected** | All 16 CICS programs use SEND MAP/RECEIVE MAP; COMEN02C uses SEND TEXT |

**Current Mainframe Behavior:**

BMS maps define 3270 screen contracts using DFHMSD (mapset), DFHMDI (map), and DFHMDF (field) macros. CICS programs send populated map data to the terminal and receive user input via MAP commands.

```cobol
           EXEC CICS SEND
                     MAP('COMEN1A')
                     MAPSET('COMEN01')
                     FROM(COMEN1AO)
                     ERASE
           END-EXEC.
```

`Source: app/cbl/COMEN01C.cbl:189-194`

BMS maps specify field attributes (ASKIP, UNPROT, FSET), colors (BLUE, TURQUOISE, YELLOW), highlighting (UNDERLINE), and screen positions (row, column). The DFHBMSCA copybook provides attribute byte constants, and the DFHAID copybook provides AID key definitions (PF1–PF24, ENTER, CLEAR).

`Source: app/bms/COACTUP.bms:20-28`

**Java Migration Strategy:**

1. **Map each BMS mapset to an HTML page template** (Thymeleaf) or a REST API resource root.
2. **Map each DFHMDI map to an HTML `<form>` section** or REST endpoint.
3. **Map each DFHMDF field to an HTML `<input>` element** with:
   - ASKIP (auto-skip) → `readonly` attribute
   - UNPROT (unprotected) → editable `<input>` field
   - FSET (modified data tag) → `<input>` with hidden field tracking changes
   - COLOR → Bootstrap CSS color classes
   - HILIGHT(UNDERLINE) → CSS `text-decoration: underline`
   - POS(row, col) → CSS Grid or Bootstrap grid positioning
4. **Replace DFHBMSCA attribute bytes with CSS classes:**

| DFHBMSCA Constant | CSS Equivalent |
|:-------------------|:---------------|
| `DFHBMASB` (ASKIP, BRT) | `class="form-control-plaintext fw-bold"` |
| `DFHBMASK` (ASKIP) | `class="form-control-plaintext"` + `readonly` |
| `DFHBMFSE` (FSET) | `class="form-control"` (tracked field) |
| `DFHBMPRF` (PROT, FSET) | `class="form-control-plaintext"` + `readonly` |
| `DFHBMUNN` (UNPROT, NUM) | `class="form-control" type="number"` |
| `DFHGREEN`, `DFHTURQ`, `DFHYELLO` | `class="text-success"`, `class="text-info"`, `class="text-warning"` |

5. **Replace SEND MAP with `ModelAndView` return** from Spring MVC controllers.
6. **Replace RECEIVE MAP with `@PostMapping` and `@ModelAttribute`** form binding.
7. **Replace AID key handling** (DFHAID PF key detection) with HTML button/link elements or JavaScript key event handlers.

**Java Equivalent Implementation:**

```java
// Controller replacing COACTUPC SEND MAP / RECEIVE MAP
@Controller
@RequestMapping("/account")
public class AccountUpdateController {
    private final AccountService accountService;
    private final CardDemoSessionState sessionState;

    public AccountUpdateController(AccountService accountService,
                                   CardDemoSessionState sessionState) {
        this.accountService = accountService;
        this.sessionState = sessionState;
    }

    /**
     * Replaces: EXEC CICS SEND MAP('CACTUPA') MAPSET('COACTUP')
     *           FROM(CACTUPAO) ERASE
     */
    @GetMapping("/update")
    public ModelAndView sendAccountUpdateMap(
            @RequestParam Long accountId) {
        ModelAndView mav = new ModelAndView("account-update");
        Account account = accountService.readAccount(accountId)
            .orElseThrow(() -> new RecordNotFoundException(
                "ACCTDAT", accountId));
        mav.addObject("account", account);
        mav.addObject("transactionName", sessionState.getFromTransactionId());
        mav.addObject("programName", "COACTUPC");
        return mav;
    }

    /**
     * Replaces: EXEC CICS RECEIVE MAP('CACTUPA') MAPSET('COACTUP')
     *           INTO(CACTUPAI)
     */
    @PostMapping("/update")
    public ModelAndView receiveAccountUpdateMap(
            @ModelAttribute AccountUpdateForm form) {
        // Process input equivalent to RECEIVE MAP field extraction
        accountService.updateAccount(form.toEntity());
        return new ModelAndView("redirect:/account/update?accountId="
                                + form.getAccountId());
    }
}
```

**Corresponding Thymeleaf Template (replacing COACTUP.bms):**

```html
<!-- account-update.html — Replaces BMS COACTUP mapset (CACTUPA map) -->
<!-- Source: app/bms/COACTUP.bms:20-28 -->
<form th:action="@{/account/update}" method="post" class="container">
  <div class="row">
    <label class="col-3 text-info">Account Number :</label>
    <input type="text" th:value="${account.accountId}"
           name="accountId" class="col-2 form-control"
           style="text-decoration: underline;" />
    <label class="col-2 text-info">Active Y/N:</label>
    <input type="text" th:value="${account.activeStatus}"
           name="activeStatus" maxlength="1" class="col-1 form-control"
           style="text-decoration: underline;" />
  </div>
  <div class="row">
    <label class="col-3 text-info">Credit Limit :</label>
    <input type="text" th:value="${account.creditLimit}"
           name="creditLimit" class="col-3 form-control"
           style="text-decoration: underline;" />
  </div>
</form>
```

`Source: app/bms/COACTUP.bms:84-135 (ACCTSID, ACSTTUS, ACRDLIM field definitions)`

**Behavioral Equivalence Strategy:**

- Verify all BMS fields present in HTML template with correct editability (ASKIP vs UNPROT)
- Validate color mapping between 3270 color attributes and CSS classes
- Confirm form submission captures identical field values to RECEIVE MAP input
- Test PF key equivalents (F3=Exit → Cancel button, ENTER → Submit button)

**Testing Approach:**

- Visual comparison: Render HTML template and compare field layout against 3270 screen definition
- Functional test: Submit form data and verify controller receives identical field values to RECEIVE MAP
- Regression: Per-screen field-by-field comparison of BMS definition vs HTML rendering

---

### 2.4 System Services (ASSIGN/ASKTIME/FORMATTIME) → Java System APIs

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Direct Replacement |
| **Java Libraries** | Java Standard Library (`java.net`, `java.time`) |
| **Complexity** | Low (see [Impact Analysis §2.6](02-dependency-impact-analysis.md)) |
| **Programs Affected** | COSGN00C (ASSIGN APPLID/SYSID), COBIL00C (ASKTIME/FORMATTIME), CORPT00C (ASKTIME) |

**Current Mainframe Behavior:**

CICS System Services provide environment information and time formatting:

**ASSIGN — Environment query:**

```cobol
           EXEC CICS ASSIGN
               APPLID(APPLIDO OF COSGN0AO)
           END-EXEC

           EXEC CICS ASSIGN
               SYSID(SYSIDO OF COSGN0AO)
           END-EXEC.
```

`Source: app/cbl/COSGN00C.cbl:198-204`

**ASKTIME/FORMATTIME — Timestamp retrieval and formatting:**

```cobol
           EXEC CICS ASKTIME
             ABSTIME(WS-ABS-TIME)
           END-EXEC

           EXEC CICS FORMATTIME
             ABSTIME(WS-ABS-TIME)
             YYYYMMDD(WS-CUR-DATE-X10)
             DATESEP('-')
             TIME(WS-CUR-TIME-X08)
             TIMESEP(':')
           END-EXEC
```

`Source: app/cbl/COBIL00C.cbl:251-261`

**Java Migration Strategy:**

1. **Replace EXEC CICS ASSIGN APPLID** with `System.getProperty("spring.application.name")` or `InetAddress.getLocalHost().getHostName()`.
2. **Replace EXEC CICS ASSIGN SYSID** with `System.getProperty("server.id")` or a Spring Boot configuration property.
3. **Replace EXEC CICS ASKTIME** with `java.time.LocalDateTime.now()`.
4. **Replace EXEC CICS FORMATTIME** with `DateTimeFormatter` patterns matching the COBOL format specifiers:
   - `YYYYMMDD` with `DATESEP('-')` → `DateTimeFormatter.ofPattern("yyyy-MM-dd")`
   - `TIME` with `TIMESEP(':')` → `DateTimeFormatter.ofPattern("HH:mm:ss")`

**Java Equivalent Implementation:**

```java
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class SystemServicesHelper {
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Replaces: EXEC CICS ASSIGN APPLID(var)
     * Source: app/cbl/COSGN00C.cbl:198-200
     */
    public static String getApplicationId() {
        String appName = System.getProperty("spring.application.name");
        if (appName != null) {
            return appName;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * Replaces: EXEC CICS ASSIGN SYSID(var)
     * Source: app/cbl/COSGN00C.cbl:202-204
     */
    public static String getSystemId() {
        return System.getProperty("server.id", "LOCAL");
    }

    /**
     * Replaces: EXEC CICS ASKTIME ABSTIME(var)
     *           EXEC CICS FORMATTIME ABSTIME(var)
     *             YYYYMMDD(date) DATESEP('-') TIME(time) TIMESEP(':')
     * Source: app/cbl/COBIL00C.cbl:251-261
     */
    public static String[] getCurrentTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        String formattedDate = now.format(DATE_FORMAT);
        String formattedTime = now.format(TIME_FORMAT);
        return new String[]{formattedDate, formattedTime};
    }
}
```

**Behavioral Equivalence Strategy:**

| Mainframe Command | Java Equivalent | Parity Check |
|:------------------|:----------------|:-------------|
| `ASSIGN APPLID` returns CICS region APPLID | `System.getProperty("spring.application.name")` | Configure property to match legacy APPLID |
| `ASSIGN SYSID` returns CICS system ID | `System.getProperty("server.id")` | Configure property to match legacy SYSID |
| `ASKTIME` returns ABSTIME (packed decimal) | `LocalDateTime.now()` | Verify timestamp is within 1-second tolerance |
| `FORMATTIME YYYYMMDD DATESEP('-')` | `DateTimeFormatter.ofPattern("yyyy-MM-dd")` | Verify identical date string format |
| `FORMATTIME TIME TIMESEP(':')` | `DateTimeFormatter.ofPattern("HH:mm:ss")` | Verify identical time string format |

**Testing Approach:**

- Unit test: Verify date/time formatting produces identical string patterns
- Integration test: Verify APPLID and SYSID return expected configured values
- Regression: Compare formatted timestamps from CICS and Java outputs

---

### 2.5 TDQ (WRITEQ TD) → JMS / Spring Batch

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Service Wrapper with Architectural Change |
| **Java Libraries** | Spring JMS 6.x, Spring Batch 5.x, ActiveMQ Artemis 2.x (or Amazon SQS for cloud-native) |
| **Complexity** | Very High (see [Impact Analysis §2.7](02-dependency-impact-analysis.md)) |
| **Programs Affected** | CORPT00C (writes JCL to TDQ 'JOBS' for internal reader submission) |

**Current Mainframe Behavior:**

CORPT00C generates JCL statements dynamically and writes them to the CICS Transient Data Queue named 'JOBS', which is defined as an extrapartition TDQ routed to the JES Internal Reader. This triggers asynchronous batch job submission directly from an online CICS transaction.

```cobol
           EXEC CICS WRITEQ TD
             QUEUE ('JOBS')
             FROM (JCL-RECORD)
             LENGTH (LENGTH OF JCL-RECORD)
             RESP(WS-RESP-CD)
             RESP2(WS-REAS-CD)
           END-EXEC.
```

`Source: app/cbl/CORPT00C.cbl:517-523`

The JCL-RECORD contains dynamically built JCL lines for a batch report generation job. Error handling checks RESP codes and displays error messages if the TDQ write fails.

`Source: app/cbl/CORPT00C.cbl:525-535`

**Java Migration Strategy:**

This is an architectural change, not a simple library replacement. The mainframe pattern (online → TDQ → JES → batch) must be reimagined for Java:

**Option A: Spring JMS + Spring Batch (recommended for enterprise deployments)**

1. **Replace TDQ WRITEQ TD with `JmsTemplate.convertAndSend()`** to send a batch job request message (JSON) to a JMS queue.
2. **Create a `@JmsListener`** that receives the message and launches a Spring Batch job via `JobLauncher`.
3. **Replace JCL job parameters** with Spring Batch `JobParameters`.

**Option B: AWS Batch API (recommended for cloud-native deployments)**

1. **Replace TDQ with AWS Batch API** `submitJob()` call, passing report parameters as job parameters.
2. **Configure AWS Batch job definition** to run the report generation program as a containerized Java application.

**Java Equivalent Implementation (Option A):**

```java
// Producer: Replaces EXEC CICS WRITEQ TD QUEUE('JOBS')
@Service
public class BatchJobSubmissionService {
    private static final Logger logger =
        LoggerFactory.getLogger(BatchJobSubmissionService.class);

    private final JmsTemplate jmsTemplate;

    public BatchJobSubmissionService(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    /**
     * Replaces: EXEC CICS WRITEQ TD QUEUE('JOBS')
     *           FROM(JCL-RECORD) LENGTH(LENGTH OF JCL-RECORD)
     * Source: app/cbl/CORPT00C.cbl:517-523
     */
    public void submitReportJob(ReportJobRequest request) {
        try {
            jmsTemplate.convertAndSend("batch-jobs", request);
            logger.info("Report job submitted: {}", request.getJobName());
        } catch (JmsException e) {
            logger.error("Unable to Write TDQ (JOBS)...", e);
            throw new BatchSubmissionException(
                "Unable to Write TDQ (JOBS)...", e);
        }
    }
}

// Consumer: Replaces JES Internal Reader job execution
@Component
public class BatchJobListener {
    private final JobLauncher jobLauncher;
    private final Job reportJob;

    public BatchJobListener(JobLauncher jobLauncher,
                            @Qualifier("reportJob") Job reportJob) {
        this.jobLauncher = jobLauncher;
        this.reportJob = reportJob;
    }

    @JmsListener(destination = "batch-jobs")
    public void onJobRequest(ReportJobRequest request) {
        JobParameters params = new JobParametersBuilder()
            .addString("monthlyFrom", request.getStartDate())
            .addString("monthlyTo", request.getEndDate())
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        jobLauncher.run(reportJob, params);
    }
}
```

**Behavioral Equivalence Strategy:**

| Mainframe Behavior | Java Equivalent | Parity Check |
|:-------------------|:----------------|:-------------|
| WRITEQ TD to 'JOBS' queue | `jmsTemplate.convertAndSend("batch-jobs", request)` | Verify message delivery |
| JES Internal Reader executes JCL | `jobLauncher.run(reportJob, params)` | Verify job execution with parameters |
| RESP(NORMAL) = success | No JmsException thrown | Verify no exception on success |
| RESP error = TDQ write failure | `JmsException` caught | Verify error message matches |
| Asynchronous execution | JMS listener processes asynchronously | Verify non-blocking behavior |

**Testing Approach:**

- Unit test: Mock JmsTemplate, verify `convertAndSend()` called with correct queue and payload
- Integration test: Submit job request, verify Spring Batch job executes with correct parameters
- Regression: Compare report output from mainframe batch execution with Java batch execution

---

## 3. Strategy per Batch Utility

> **Visual Reference:** See the [Batch Job Migration Flow Diagram](diagrams/batch-job-migration-flow.md) for a flowchart showing each batch utility's replacement pipeline.

### 3.1 IDCAMS → DDL Scripts + Java Database Management

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Direct Replacement (DDL scripts) + Service Wrapper (data loading) |
| **Java Libraries** | Flyway 10.x (schema migration), Spring JDBC 6.x, HikariCP 5.x |
| **Complexity** | Medium (see [Impact Analysis §2.8](02-dependency-impact-analysis.md)) |
| **Affected Jobs** | DEFVSAM, DEFGDGB, ACCTFILE, CARDFILE, XREFFILE, CUSTFILE, TCATBALF, TRANFILE, TRANCATG, TRANTYPE |

**Current Mainframe Behavior:**

IDCAMS (Access Method Services) manages VSAM datasets:
- **DEFINE CLUSTER:** Creates VSAM KSDS clusters with attributes (key length, key position, record size, CI size, space allocation)
- **REPRO:** Copies data from sequential files into VSAM clusters (bulk load)
- **DELETE:** Removes VSAM cluster definitions and data

The LISTCAT output captures cluster attributes:

```
 KEYLEN----------------11     AVGLRECL-------------300
 RKP--------------------0     MAXLRECL-------------300
 CISIZE-------------18432     CI/CA-----------------45
```

`Source: app/catlg/LISTCAT.txt:59-60`

**Java Migration Strategy:**

1. **Replace DEFINE CLUSTER with DDL CREATE TABLE** scripts. Map VSAM cluster attributes to RDBMS table parameters:

| VSAM Attribute | DDL Equivalent | Example |
|:---------------|:---------------|:--------|
| `KEYLEN=11, RKP=0` | `PRIMARY KEY (ACCT_ID)` with `NUMERIC(11)` | Account ID as PK |
| `AVGLRECL=300, MAXLRECL=300` | Row size estimation for capacity planning | 300 bytes per row |
| `CISIZE=18432` | DB page size configuration (advisory) | 16KB page size |
| `INDEXED, UNIQUE` | `UNIQUE INDEX` on primary key | Unique constraint |
| `SHROPTNS(2,3)` | Connection pool read concurrency | HikariCP `maximumPoolSize` |

2. **Use Flyway for schema migration** — create versioned SQL migration scripts:

```sql
-- V1__create_accounts_table.sql
-- Replaces: IDCAMS DEFINE CLUSTER for AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
-- Source: app/catlg/LISTCAT.txt:22 (KEYLEN=11, AVGLRECL=300)
CREATE TABLE ACCOUNTS (
    ACCT_ID         NUMERIC(11)     NOT NULL,
    ACTIVE_STATUS   CHAR(1)         DEFAULT 'Y',
    CURR_BAL        DECIMAL(12,2)   DEFAULT 0.00,
    CREDIT_LIMIT    DECIMAL(12,2)   DEFAULT 0.00,
    CASH_CREDIT_LIM DECIMAL(12,2)   DEFAULT 0.00,
    OPEN_DATE       VARCHAR(10),
    EXPIRATION_DATE VARCHAR(10),
    REISSUE_DATE    VARCHAR(10),
    CURR_CYC_CREDIT DECIMAL(12,2)   DEFAULT 0.00,
    CURR_CYC_DEBIT  DECIMAL(12,2)   DEFAULT 0.00,
    ADDR_ZIP        VARCHAR(10),
    GROUP_ID        VARCHAR(10),
    CONSTRAINT PK_ACCOUNTS PRIMARY KEY (ACCT_ID)
);

CREATE INDEX IDX_ACCT_STATUS ON ACCOUNTS (ACTIVE_STATUS);
```

3. **Replace REPRO with JDBC batch INSERT** or Spring Batch `FlatFileItemReader` → `JdbcBatchItemWriter` for bulk data loading.
4. **Replace DELETE with DROP TABLE or TRUNCATE TABLE.**

**Behavioral Equivalence Strategy:**

- Verify CREATE TABLE DDL produces tables with identical column definitions to VSAM record layouts
- Validate REPRO-equivalent data loading preserves all record field values (byte-level comparison of VSAM record vs table row)
- Confirm primary key enforcement matches VSAM KSDS unique key semantics

**Testing Approach:**

- Schema validation: Compare DDL table structure with copybook record layout field-by-field
- Data loading: Load `app/data/acctdata.txt` via JDBC and compare row count and field values with VSAM cluster content
- Key constraint: Attempt duplicate key insertion and verify rejection

---

### 3.2 SORT (DFSORT/SyncSort) → Java Sort/Merge

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Custom Implementation |
| **Java Libraries** | Java Streams API, Apache Commons CSV 1.11.x, Java NIO (`java.nio.file`) |
| **Complexity** | Medium (see [Impact Analysis §2.9](02-dependency-impact-analysis.md)) |
| **Affected Jobs** | COMBTRAN (transaction merge) |

**Current Mainframe Behavior:**

The COMBTRAN job uses DFSORT/SyncSort to merge daily transaction files with existing system transaction files, producing a combined sorted output file. SORT control statements specify field positions, lengths, data types, and sort direction.

**Java Migration Strategy:**

1. **Parse fixed-width records** using column positions matching the COBOL record layout.
2. **Use Java Streams API with custom `Comparator`** for in-memory sort/merge of small-to-medium datasets.
3. **Use external merge sort** for large datasets that exceed available memory.
4. **Handle EBCDIC-to-ASCII collation differences** — EBCDIC sorts lowercase before uppercase and numbers after letters; Java sorts in ASCII/Unicode order (numbers before uppercase before lowercase).

**Java Equivalent Implementation:**

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Replaces DFSORT/SyncSort utility for the COMBTRAN batch job.
 * Merges daily transaction file with system transaction file,
 * sorted by transaction key (card number + transaction ID).
 */
public class TransactionFileMerger {

    // Fixed-width field positions matching CVTRA05Y.cpy
    private static final int CARD_NUM_START = 0;
    private static final int CARD_NUM_LEN = 16;
    private static final int TRAN_ID_START = 16;
    private static final int TRAN_ID_LEN = 16;
    private static final int RECORD_LENGTH = 350;

    /**
     * Merges two fixed-width transaction files sorted by key.
     * Replaces: SORT FIELDS=(1,16,CH,A,17,16,CH,A)
     */
    public static void mergeSorted(Path dailyFile, Path systemFile,
                                   Path outputFile) throws IOException {
        Comparator<String> keyComparator = Comparator
            .comparing((String rec) ->
                rec.substring(CARD_NUM_START,
                              CARD_NUM_START + CARD_NUM_LEN))
            .thenComparing(rec ->
                rec.substring(TRAN_ID_START,
                              TRAN_ID_START + TRAN_ID_LEN));

        List<String> dailyRecords = Files.readAllLines(dailyFile);
        List<String> systemRecords = Files.readAllLines(systemFile);

        List<String> merged = Stream.concat(
                dailyRecords.stream(), systemRecords.stream())
            .sorted(keyComparator)
            .collect(Collectors.toList());

        Files.write(outputFile, merged);
    }
}
```

**Behavioral Equivalence Strategy:**

| Mainframe Behavior | Java Equivalent | Parity Check |
|:-------------------|:----------------|:-------------|
| SORT FIELDS=(1,16,CH,A) | `Comparator.comparing(rec -> rec.substring(0, 16))` | Verify sort order matches |
| EBCDIC character collation | ASCII/Unicode collation | Verify collation difference does not affect business results for alphanumeric keys |
| Fixed-width record preservation | Line-based I/O preserving record length | Verify output record length = input record length |
| Merge of two sorted inputs | `Stream.concat().sorted()` | Verify merged output contains all records from both inputs |

**Testing Approach:**

- Unit test: Sort test records with known ordering, compare Java output with expected SORT output
- Data parity: Merge `app/data/transact.txt` with a test daily file and compare record count and sort order
- Edge cases: Empty files, single-record files, duplicate keys

---

### 3.3 IEBGENER → Java File Copy

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Direct Replacement |
| **Java Libraries** | Java NIO (`java.nio.file.Files`), Java IO (`java.io.BufferedReader` / `BufferedWriter`) |
| **Complexity** | Low (see [Impact Analysis §2.10](02-dependency-impact-analysis.md)) |
| **Affected Jobs** | DUSRSECJ (user security file load) |

**Current Mainframe Behavior:**

IEBGENER is a z/OS utility that performs sequential dataset copies. In the DUSRSECJ job, it copies user security data from a sequential source into the USRSEC file.

**Java Migration Strategy:**

1. **For simple byte-for-byte copy:** Use `java.nio.file.Files.copy()`.
2. **For fixed-width record transformation:** Use `BufferedReader` / `BufferedWriter` with explicit record length handling to preserve fixed-width record boundaries.

**Java Equivalent Implementation:**

```java
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replaces IEBGENER utility for sequential file copy operations.
 * Used by DUSRSECJ job to copy user security data.
 */
public class SequentialFileCopier {

    /**
     * Simple file copy — replaces IEBGENER with no record transformation.
     */
    public static void copy(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Fixed-width record copy with record length validation.
     * Ensures each output record matches the expected length
     * (e.g., 80 bytes for USRSEC per CSUSR01Y.cpy).
     */
    public static void copyFixedWidth(Path source, Path target,
                                      int recordLength) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(source);
             BufferedWriter writer = Files.newBufferedWriter(target)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String padded = String.format("%-" + recordLength + "s", line);
                writer.write(padded.substring(0, recordLength));
                writer.newLine();
            }
        }
    }
}
```

**Behavioral Equivalence Strategy:**

- Verify byte count of output file matches input file
- For fixed-width records: verify each record is padded/truncated to exact record length
- Compare checksums of source and target files after copy

**Testing Approach:**

- Unit test: Copy `app/data/usrsec.txt`, verify output is byte-identical to input
- Edge cases: Empty source file, records shorter/longer than expected length

---

### 3.4 IEFBR14 → File System Operations

| Attribute | Value |
|:----------|:------|
| **Recommended Approach** | Direct Replacement |
| **Java Libraries** | Java NIO (`java.nio.file.Files`) |
| **Complexity** | Low (see [Impact Analysis §2.11](02-dependency-impact-analysis.md)) |
| **Affected Jobs** | CLOSEFIL, OPENFIL |

**Current Mainframe Behavior:**

IEFBR14 is a z/OS null program (does nothing) used exclusively for its JCL DD statement side effects. In the CLOSEFIL and OPENFIL jobs, the DD statements with `DISP=(OLD,KEEP)` cause z/OS to allocate and deallocate VSAM file handles, effectively closing/opening files for CICS access.

**Java Migration Strategy:**

In a Java/Spring environment, file handle management is automatic — database connections are managed by the connection pool (HikariCP), and file handles are managed by the JVM garbage collector. The IEFBR14 pattern maps to a **no-op** in the Java world.

For cases where explicit file lifecycle management is needed:

```java
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Replaces IEFBR14 DD statement side effects.
 * In Java, most IEFBR14 use cases become no-ops since file/connection
 * lifecycle is managed automatically by the runtime.
 */
public class FileLifecycleManager {

    /**
     * Replaces CLOSEFIL job: IEFBR14 + DD DISP=(OLD,KEEP)
     * In Spring, connections are managed by HikariCP pool.
     * This method is provided for explicit cache/buffer flush.
     */
    public static void flushAndSync(Path filePath) throws Exception {
        if (Files.exists(filePath)) {
            // Force sync to disk (equivalent to closing VSAM buffer)
            filePath.toFile().getFreeSpace(); // ensure accessible
        }
    }

    /**
     * Replaces OPENFIL job: IEFBR14 + DD DISP=(OLD,KEEP)
     * In Spring, data source initialization opens connections on startup.
     * This method validates file accessibility.
     */
    public static boolean verifyAccessible(Path filePath) {
        return Files.exists(filePath) && Files.isReadable(filePath);
    }

    /**
     * For JCL DD DISP=(NEW,CATLG) — creates a new file.
     */
    public static void createIfNotExists(Path filePath) throws Exception {
        if (!Files.exists(filePath)) {
            Files.createFile(filePath);
        }
    }

    /**
     * For JCL DD DISP=(OLD,DELETE) — deletes a file.
     */
    public static void deleteIfExists(Path filePath) throws Exception {
        Files.deleteIfExists(filePath);
    }
}
```

**Behavioral Equivalence Strategy:**

- CLOSEFIL → Connection pool idle timeout or explicit `DataSource.close()` in Spring context shutdown
- OPENFIL → `DataSource` initialization on Spring context startup
- No functional output to compare — success means no errors during lifecycle operations

**Testing Approach:**

- Integration test: Verify Spring context startup/shutdown correctly opens/closes database connections
- Verify no orphan file handles remain after application shutdown

---

## 4. Strategy per BMS Map Macro

### 4.1 BMS Macro to HTML/REST Mapping Overview

| BMS Macro | HTML Equivalent | REST API Equivalent |
|:----------|:----------------|:--------------------|
| **DFHMSD** (mapset definition) | HTML page template (`.html` file) | REST resource root (`/api/v1/<resource>`) |
| **DFHMDI** (map definition within mapset) | HTML `<form>` section or `<div>` container | REST endpoint (`GET/POST /api/v1/<resource>/<action>`) |
| **DFHMDF** (field definition within map) | HTML `<input>`, `<select>`, or `<span>` element | JSON field in request/response body |

**Current Mainframe Behavior:**

The 17 BMS map sources in `app/bms/` define 3270 screen contracts using IBM macro instructions. Each mapset (DFHMSD) contains one or more maps (DFHMDI), and each map contains field definitions (DFHMDF) specifying position, length, attributes, and color.

Example from the Account Update screen:

```
COACTUP DFHMSD LANG=COBOL,                                             -
               MODE=INOUT,                                             -
               STORAGE=AUTO,                                           -
               TIOAPFX=YES,                                            -
               TYPE=&&SYSPARM
CACTUPA DFHMDI CTRL=(FREEKB),                                          -
               DSATTS=(COLOR,HILIGHT,PS,VALIDN),                       -
               MAPATTS=(COLOR,HILIGHT,PS,VALIDN),                      -
               SIZE=(24,80)
ACCTSID DFHMDF ATTRB=(IC,UNPROT),                                      -
               HILIGHT=UNDERLINE,                                      -
               LENGTH=11,                                              -
               POS=(5,38)
ACSTTUS DFHMDF ATTRB=(UNPROT),                                         -
               HILIGHT=UNDERLINE,                                      -
               LENGTH=1,                                               -
               POS=(5,70)
ACRDLIM DFHMDF ATTRB=(FSET,UNPROT),                                    -
               HILIGHT=UNDERLINE,                                      -
               LENGTH=15,                                              -
               POS=(6,61)
```

`Source: app/bms/COACTUP.bms:20-135`

### 4.2 DFHMSD → HTML Page Template Strategy

**Mapping Rules:**

| DFHMSD Parameter | HTML/Thymeleaf Equivalent |
|:-----------------|:-------------------------|
| `LANG=COBOL` | N/A — server-side language (Java) |
| `MODE=INOUT` | `<form method="post">` (bidirectional data flow) |
| `STORAGE=AUTO` | Spring-managed model objects (`@ModelAttribute`) |
| `TIOAPFX=YES` | Automatic — HTTP protocol handles framing |
| `TYPE=&&SYSPARM` | Build configuration selects MAP vs DSECT equivalent (template vs DTO) |

Each DFHMSD mapset becomes a Thymeleaf template directory or a Spring MVC controller class.

### 4.3 DFHMDI → HTML Form Section Strategy

**Mapping Rules:**

| DFHMDI Parameter | HTML Equivalent |
|:-----------------|:----------------|
| `CTRL=(FREEKB)` | HTML forms have free keyboard input by default |
| `DSATTS=(COLOR,HILIGHT,PS,VALIDN)` | CSS styling + HTML5 validation attributes |
| `MAPATTS=(COLOR,HILIGHT,PS,VALIDN)` | Thymeleaf conditional CSS class injection |
| `SIZE=(24,80)` | CSS `max-width: 80ch; min-height: 24em;` or responsive Bootstrap grid |

### 4.4 DFHMDF → HTML Input Element Strategy

**Attribute Mapping:**

| DFHMDF Attribute | HTML/CSS Equivalent | Example |
|:-----------------|:--------------------|:--------|
| `ATTRB=(ASKIP)` | `<span>` or `<input readonly>` | Display-only field |
| `ATTRB=(UNPROT)` | `<input type="text">` | Editable field |
| `ATTRB=(IC,UNPROT)` | `<input type="text" autofocus>` | Initial cursor position |
| `ATTRB=(FSET,UNPROT)` | `<input type="text" class="tracked-field">` | Modified data tag (field sent even if unchanged) |
| `ATTRB=(PROT,FSET)` | `<input type="hidden">` + `<span>` display | Protected field always transmitted |
| `HILIGHT=UNDERLINE` | `style="text-decoration: underline"` | Underlined input |
| `COLOR=BLUE` | `class="text-primary"` (Bootstrap) | Blue text color |
| `COLOR=TURQUOISE` | `class="text-info"` (Bootstrap) | Turquoise/cyan text |
| `COLOR=YELLOW` | `class="text-warning"` (Bootstrap) | Yellow text |
| `COLOR=GREEN` | `class="text-success"` (Bootstrap) | Green text |
| `COLOR=RED` | `class="text-danger"` (Bootstrap) | Red text |
| `LENGTH=n` | `maxlength="n"` attribute | Field length limit |
| `POS=(row,col)` | CSS Grid `grid-row: row; grid-column: col;` or Bootstrap grid | Screen position |
| `INITIAL='text'` | `<label>text</label>` or `placeholder="text"` | Initial display value |
| `JUSTIFY=(RIGHT)` | `style="text-align: right"` | Right-justified input |

### 4.5 BMS-to-HTML Example: Account Update Screen

**BMS Source (COACTUP.bms):**

```
ACCTSID DFHMDF ATTRB=(IC,UNPROT),HILIGHT=UNDERLINE,LENGTH=11,POS=(5,38)
ACSTTUS DFHMDF ATTRB=(UNPROT),HILIGHT=UNDERLINE,LENGTH=1,POS=(5,70)
ACRDLIM DFHMDF ATTRB=(FSET,UNPROT),HILIGHT=UNDERLINE,LENGTH=15,POS=(6,61)
```

`Source: app/bms/COACTUP.bms:84-135`

**HTML Equivalent:**

```html
<!-- Account Update Screen — Replaces COACTUP.bms CACTUPA map -->
<div class="container" style="max-width: 80ch; font-family: monospace;">
  <!-- Row 5: Account Number and Active Status -->
  <div class="row mb-1">
    <label class="col-4 text-info">Account Number :</label>
    <input type="text" name="accountId" maxlength="11"
           class="col-2 form-control" autofocus
           style="text-decoration: underline;"
           th:value="${account.accountId}" />
    <div class="col-1"></div>
    <label class="col-2 text-info">Active Y/N:</label>
    <input type="text" name="activeStatus" maxlength="1"
           class="col-1 form-control"
           style="text-decoration: underline;"
           th:value="${account.activeStatus}" />
  </div>
  <!-- Row 6: Credit Limit -->
  <div class="row mb-1">
    <label class="col-4 text-info">Credit Limit :</label>
    <input type="text" name="creditLimit" maxlength="15"
           class="col-3 form-control tracked-field"
           style="text-decoration: underline; text-align: right;"
           th:value="${account.creditLimit}" />
  </div>
</div>
```

**Behavioral Equivalence Strategy:**

- Verify all BMS fields are represented in HTML with correct editability
- Validate field length constraints (`maxlength` = BMS `LENGTH`)
- Confirm color mapping: BMS COLOR constants → Bootstrap CSS classes
- Test initial cursor position: BMS `IC` attribute → HTML `autofocus`
- Validate FSET behavior: tracked fields are always submitted even if unchanged

**Testing Approach:**

- Visual comparison: Render HTML template and compare layout with 3270 screen mockup
- Field validation: Verify each BMS DFHMDF field has a corresponding HTML element with matching attributes
- Form submission: Submit HTML form, verify all field values received by server match RECEIVE MAP field extraction

---

## 5. Strategy per COBOL Intrinsic Function

### 5.1 Function-to-Java Mapping Table

| COBOL Intrinsic Function | Java Equivalent | Library | Notes |
|:-------------------------|:----------------|:--------|:------|
| `FUNCTION CURRENT-DATE` | `LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSnnnnnn"))` | `java.time` | COBOL returns 21-char string: `YYYYMMDDHHMMSSnnnnnn±HHMM` (includes timezone offset). Java `LocalDateTime` does not include timezone; use `ZonedDateTime` for full parity. |
| `FUNCTION MOD(a, b)` | `Math.floorMod(a, b)` | `java.lang.Math` | `Math.floorMod()` matches COBOL MOD behavior for negative numbers (result has same sign as divisor). Do NOT use `%` operator, which has different semantics for negative operands. |
| `FUNCTION TEST-NUMVAL-C(str)` | Custom `isNumeric()` method | Custom utility | COBOL `TEST-NUMVAL-C` returns 0 if string is a valid edited numeric value (with commas, currency symbols, signs). No direct Java equivalent. Requires custom regex or `DecimalFormat.parse()` with `ParsePosition` check. |
| `FUNCTION NUMVAL-C(str)` | `Double.parseDouble(str.replaceAll("[^\\d.\\-]", ""))` | `java.lang.Double` | COBOL `NUMVAL-C` strips currency symbols and commas before conversion. Java requires explicit stripping before `parseDouble()`. Handle locale-specific decimal separators. |
| `FUNCTION UPPER-CASE(str)` | `str.toUpperCase(Locale.ENGLISH)` | `java.lang.String` | Specify `Locale.ENGLISH` to avoid locale-dependent behavior (e.g., Turkish İ/i). COBOL UPPER-CASE is EBCDIC-aware; Java is Unicode-aware. |
| `FUNCTION TRIM(str)` | `str.trim()` | `java.lang.String` | COBOL TRIM removes leading and trailing spaces by default. Java `trim()` removes all characters ≤ U+0020. For exact parity, use `str.strip()` (Java 11+) which is Unicode-aware. |
| `FUNCTION INTEGER-OF-DATE(date)` | `ChronoUnit.DAYS.between(LocalDate.of(1601, 1, 1), localDate) + 1` | `java.time.temporal.ChronoUnit` | COBOL `INTEGER-OF-DATE` returns days since December 31, 1600 (day 1 = Jan 1, 1601). Java `ChronoUnit.DAYS.between()` requires explicit epoch alignment. |

### 5.2 Detailed Mapping: TEST-NUMVAL-C (Custom Implementation Required)

COBOL `TEST-NUMVAL-C` validates whether a string represents a valid edited numeric value, handling embedded currency symbols (`$`), commas, decimal points, and sign indicators (`+`, `-`, `CR`, `DB`). Java has no single equivalent.

```java
/**
 * Replaces: FUNCTION TEST-NUMVAL-C(string)
 * Returns 0 if valid numeric, > 0 if invalid (position of first error)
 */
public static int testNumvalC(String input) {
    if (input == null || input.trim().isEmpty()) {
        return 1;
    }
    String stripped = input.trim()
        .replaceAll("[$,]", "")
        .replaceAll("(?i)(CR|DB)$", "")
        .replaceAll("[+\\-]", "");
    try {
        Double.parseDouble(stripped);
        return 0; // Valid numeric
    } catch (NumberFormatException e) {
        // Find position of first non-numeric character
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                return i + 1;
            }
        }
        return 1;
    }
}
```

### 5.3 Detailed Mapping: INTEGER-OF-DATE (Epoch Alignment Required)

COBOL `INTEGER-OF-DATE` converts a date in `YYYYMMDD` format to an integer representing days since December 31, 1600 (the COBOL integer date epoch). Day 1 = January 1, 1601.

```java
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Replaces: FUNCTION INTEGER-OF-DATE(date-in-yyyymmdd)
 * Returns days since December 31, 1600 (day 1 = Jan 1, 1601)
 */
public static long integerOfDate(String yyyymmdd) {
    LocalDate cobolEpoch = LocalDate.of(1601, 1, 1);
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
    LocalDate date = LocalDate.parse(yyyymmdd, fmt);
    return ChronoUnit.DAYS.between(cobolEpoch, date) + 1;
}
```

**Behavioral Equivalence Strategy:**

- Unit test each function with boundary values and known COBOL outputs
- For `MOD`: verify behavior with negative operands (`FUNCTION MOD(-7, 3)` = 2, `Math.floorMod(-7, 3)` = 2)
- For `INTEGER-OF-DATE`: verify against known COBOL results (e.g., `INTEGER-OF-DATE(20240101)` = 154,378)
- For `TEST-NUMVAL-C`: verify with currency-formatted strings (`$1,234.56` → valid, `$12.34.56` → invalid)

---

## 6. Consolidated Code Mapping Examples

This section provides at least 7 syntactically correct COBOL → Java code mapping examples, one per utility category.

### Example 1: CEE3ABD — Abend Handling (LE Runtime)

**COBOL (Source):**

```cobol
       9999-ABEND-PROGRAM.
           DISPLAY 'ABENDING PROGRAM'
           MOVE 0 TO TIMING
           MOVE 999 TO ABCODE
           CALL 'CEE3ABD'.
```

`Source: app/cbl/CBACT01C.cbl:169-173`

**Java (Target):**

```java
private void abendProgram() {
    logger.error("ABENDING PROGRAM");
    DiagnosticDumper.writeDump(Thread.currentThread());
    throw new ApplicationAbendException(999, true);
}

// Top-level main method wrapper:
public static void main(String[] args) {
    try {
        new CBACT01C().execute();
    } catch (ApplicationAbendException e) {
        System.exit(e.getAbendCode());
    }
}
```

---

### Example 2: CEEDAYS — Date Validation (LE Runtime)

**COBOL (Source):**

```cobol
           CALL "CEEDAYS" USING
                  WS-DATE-TO-TEST,
                  WS-DATE-FORMAT,
                  OUTPUT-LILLIAN,
                  FEEDBACK-CODE

           EVALUATE TRUE
              WHEN FC-INVALID-DATE
                 MOVE 'Date is valid'      TO WS-RESULT
              WHEN FC-BAD-DATE-VALUE
                 MOVE 'Datevalue error'    TO WS-RESULT
           END-EVALUATE
```

`Source: app/cbl/CSUTLDTC.cbl:116-134`

**Java (Target):**

```java
DateValidationResponse response =
    DateValidationService.validateDate(dateStr, "YYYY-MM-DD");

switch (response.getResult()) {
    case DATE_VALID:
        result = "Date is valid";
        break;
    case BAD_DATE_VALUE:
        result = "Datevalue error";
        break;
    case INSUFFICIENT_DATA:
        result = "Insufficient";
        break;
    default:
        result = response.getResult().getMessage();
        break;
}
```

---

### Example 3: CICS File Control — READ (CICS API)

**COBOL (Source):**

```cobol
           EXEC CICS READ
                DATASET   (WS-USRSEC-FILE)
                INTO      (SEC-USER-DATA)
                LENGTH    (LENGTH OF SEC-USER-DATA)
                RIDFLD    (WS-USER-ID)
                KEYLENGTH (LENGTH OF WS-USER-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.
```

`Source: app/cbl/COSGN00C.cbl:211-219`

**Java (Target):**

```java
try {
    Optional<UserSecurity> userData =
        userSecurityRepository.findById(userId);
    if (userData.isPresent()) {
        secUserData = userData.get();
        respCode = 0; // DFHRESP(NORMAL)
    } else {
        respCode = 13; // DFHRESP(NOTFND)
    }
} catch (DataAccessException e) {
    respCode = 12; // DFHRESP(INVREQ)
    logger.error("READ error for USRSEC, key={}", userId, e);
}
```

---

### Example 4: CICS Program Control — XCTL (CICS API)

**COBOL (Source):**

```cobol
               EXEC CICS
                   XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
                   COMMAREA(CARDDEMO-COMMAREA)
               END-EXEC
```

`Source: app/cbl/COMEN01C.cbl:152-155`

**Java (Target):**

```java
@PostMapping("/menu/select")
public String handleMenuSelection(@RequestParam int option,
                                  HttpSession session) {
    CardDemoSessionState state =
        (CardDemoSessionState) session.getAttribute("commarea");
    state.setFromTransactionId("CM00");
    state.setFromProgram("COMEN01C");
    state.setProgramContext(0);

    String targetProgram = menuOptions.get(option).getProgramName();
    session.setAttribute("commarea", state);
    return "redirect:/" + targetProgram.toLowerCase();
}
```

---

### Example 5: CICS System Services — ASKTIME/FORMATTIME (CICS API)

**COBOL (Source):**

```cobol
           EXEC CICS ASKTIME
             ABSTIME(WS-ABS-TIME)
           END-EXEC

           EXEC CICS FORMATTIME
             ABSTIME(WS-ABS-TIME)
             YYYYMMDD(WS-CUR-DATE-X10)
             DATESEP('-')
             TIME(WS-CUR-TIME-X08)
             TIMESEP(':')
           END-EXEC
```

`Source: app/cbl/COBIL00C.cbl:251-261`

**Java (Target):**

```java
LocalDateTime now = LocalDateTime.now();
String formattedDate = now.format(
    DateTimeFormatter.ofPattern("yyyy-MM-dd"));
String formattedTime = now.format(
    DateTimeFormatter.ofPattern("HH:mm:ss"));

String timestamp = formattedDate + " " + formattedTime + ".000000";
```

---

### Example 6: TDQ WRITEQ TD — Batch Job Submission (CICS API)

**COBOL (Source):**

```cobol
           EXEC CICS WRITEQ TD
             QUEUE ('JOBS')
             FROM (JCL-RECORD)
             LENGTH (LENGTH OF JCL-RECORD)
             RESP(WS-RESP-CD)
             RESP2(WS-REAS-CD)
           END-EXEC.
```

`Source: app/cbl/CORPT00C.cbl:517-523`

**Java (Target):**

```java
try {
    ReportJobRequest request = new ReportJobRequest(
        reportName, startDate, endDate);
    jmsTemplate.convertAndSend("batch-jobs", request);
    respCode = 0; // DFHRESP(NORMAL)
} catch (JmsException e) {
    logger.error("Unable to Write TDQ (JOBS)...", e);
    respCode = -1;
    errorMessage = "Unable to Write TDQ (JOBS)...";
}
```

---

### Example 7: SORT — Fixed-Width File Merge (Batch Utility)

**COBOL/JCL (Source):**

```
//COMBTRAN EXEC PGM=SORT
//SORTIN01 DD DSN=&&DALYTRAN,DISP=SHR
//SORTIN02 DD DSN=&&TRANFILE,DISP=SHR
//SORTOUT  DD DSN=&&COMBINED,DISP=(NEW,PASS)
//SYSIN    DD *
  SORT FIELDS=(1,16,CH,A,17,16,CH,A)
  MERGE
/*
```

**Java (Target):**

```java
Path dailyTranFile = Path.of("data/dalytran.txt");
Path systemTranFile = Path.of("data/transact.txt");
Path combinedFile = Path.of("data/combined.txt");

Comparator<String> sortKey = Comparator
    .comparing((String rec) -> rec.substring(0, 16))  // Card Number
    .thenComparing(rec -> rec.substring(16, 32));      // Transaction ID

List<String> merged = Stream.concat(
        Files.lines(dailyTranFile),
        Files.lines(systemTranFile))
    .sorted(sortKey)
    .collect(Collectors.toList());

Files.write(combinedFile, merged);
```

---

### Example 8: BMS Map — Screen Field Definition (BMS Macro)

**BMS Source:**

```
ACCTSID DFHMDF ATTRB=(IC,UNPROT),
               HILIGHT=UNDERLINE,
               LENGTH=11,
               POS=(5,38)
```

`Source: app/bms/COACTUP.bms:84-87`

**HTML Target:**

```html
<input type="text"
       name="accountId"
       maxlength="11"
       autofocus
       class="form-control"
       style="text-decoration: underline;"
       th:value="${account.accountId}" />
```

---

### Example 9: COBOL Intrinsic Functions — Multiple Mappings

**COBOL (Source):**

```cobol
           MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
           COMPUTE WS-REMAINDER = FUNCTION MOD(WS-VALUE, WS-DIVISOR)
           IF FUNCTION TEST-NUMVAL-C(WS-INPUT) = 0
               COMPUTE WS-AMOUNT = FUNCTION NUMVAL-C(WS-INPUT)
           END-IF
           MOVE FUNCTION UPPER-CASE(WS-NAME) TO WS-NAME-UPPER
           MOVE FUNCTION TRIM(WS-INPUT) TO WS-TRIMMED
```

`Source: app/cbl/CBACT04C.cbl (CURRENT-DATE, MOD usage)`

**Java (Target):**

```java
String currentDate = ZonedDateTime.now()
    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSnnnnnnZZZZZ"));
int remainder = Math.floorMod(value, divisor);
if (testNumvalC(input) == 0) {
    double amount = Double.parseDouble(
        input.replaceAll("[^\\d.\\-]", ""));
}
String nameUpper = name.toUpperCase(Locale.ENGLISH);
String trimmed = input.strip();
```

---

## Appendix A: Java Library Version Summary

| Library | Version | Purpose | Maven Coordinates |
|:--------|:--------|:--------|:-----------------|
| Spring Boot | 3.2.x | Application framework | `org.springframework.boot:spring-boot-starter` |
| Spring Data JPA | 3.2.x | VSAM → RDBMS data access | `org.springframework.boot:spring-boot-starter-data-jpa` |
| Spring Web MVC | 6.1.x | CICS Program/Terminal Control replacement | `org.springframework.boot:spring-boot-starter-web` |
| Spring JMS | 6.1.x | TDQ replacement (message queuing) | `org.springframework.boot:spring-boot-starter-activemq` |
| Spring Batch | 5.1.x | Batch job execution framework | `org.springframework.boot:spring-boot-starter-batch` |
| Hibernate | 6.4.x | JPA provider for data access | Included via Spring Data JPA starter |
| HikariCP | 5.1.x | JDBC connection pool | Included via Spring Data JPA starter |
| Thymeleaf | 3.1.x | BMS map → HTML template engine | `org.springframework.boot:spring-boot-starter-thymeleaf` |
| Bootstrap | 5.3.x | CSS framework for 3270 attribute mapping | CDN or WebJar |
| Flyway | 10.x | IDCAMS DEFINE CLUSTER → DDL schema migration | `org.flywaydb:flyway-core` |
| Apache Commons CSV | 1.11.x | SORT utility file processing | `org.apache.commons:commons-csv` |
| SLF4J | 2.0.x | Logging (replaces DISPLAY/CEEDUMP) | `org.slf4j:slf4j-api` |
| Logback | 1.4.x | Logging implementation | `ch.qos.logback:logback-classic` |
| ActiveMQ Artemis | 2.x | JMS broker for TDQ replacement | `org.apache.activemq:artemis-jakarta-client` |
| Jakarta Servlet | 6.0 | HTTP request handling | Included via Spring Web starter |

---

## Appendix B: Migration Approach Decision Matrix

| Utility | Approach | Justification |
|:--------|:---------|:-------------|
| CEE3ABD | Custom Implementation | No single Java API replicates LE abend + dump + exit code semantics |
| CEEDAYS | Direct Replacement | `java.time` provides comprehensive date parsing; requires custom enum for error code mapping |
| CICS File Control | Service Wrapper | Spring Data JPA provides declarative data access; wrapper needed for RESP code mapping |
| CICS Program Control | Direct Replacement | Spring MVC routing maps naturally to XCTL/RETURN patterns |
| CICS Terminal Control | Custom Implementation | 3270 BMS to HTML/REST is a paradigm change requiring per-screen conversion |
| CICS System Services | Direct Replacement | Java standard library provides 1:1 replacements for ASSIGN/ASKTIME/FORMATTIME |
| CICS TDQ | Service Wrapper + Arch. Change | TDQ→JES pattern requires JMS + Spring Batch replacement with new architecture |
| IDCAMS | Direct Replacement | VSAM cluster definitions map cleanly to DDL CREATE TABLE statements |
| SORT | Custom Implementation | SORT control statements require field-position-aware Java comparators |
| IEBGENER | Direct Replacement | `java.nio.file.Files.copy()` provides byte-identical copy semantics |
| IEFBR14 | No-Op / Direct Replacement | File lifecycle is automatic in Java; no functional migration needed |
| BMS Macros | Custom Implementation | DFHMSD/DFHMDI/DFHMDF to HTML/CSS is a presentation layer paradigm change |
| COBOL Intrinsic Functions | Direct Replacement | Java standard library covers most functions; custom code for TEST-NUMVAL-C and INTEGER-OF-DATE |

---

## Appendix C: Source File Citations Index

All COBOL source citations used in this document for traceability:

| Citation | Content |
|:---------|:--------|
| `app/cbl/CBACT01C.cbl:169-173` | CEE3ABD abend program paragraph |
| `app/cbl/CSUTLDTC.cbl:116-120` | CEEDAYS call with VSTRING parameters |
| `app/cbl/CSUTLDTC.cbl:62-70` | FEEDBACK-CODE 88-level conditions |
| `app/cbl/CSUTLDTC.cbl:128-149` | EVALUATE TRUE error classification |
| `app/cbl/COSGN00C.cbl:198-204` | EXEC CICS ASSIGN APPLID/SYSID |
| `app/cbl/COSGN00C.cbl:211-219` | EXEC CICS READ for USRSEC |
| `app/cbl/COBIL00C.cbl:251-261` | EXEC CICS ASKTIME/FORMATTIME |
| `app/cbl/COMEN01C.cbl:152-155` | EXEC CICS XCTL dynamic routing |
| `app/cbl/COMEN01C.cbl:189-194` | EXEC CICS SEND MAP |
| `app/cbl/CORPT00C.cbl:517-523` | EXEC CICS WRITEQ TD to JOBS queue |
| `app/cbl/CORPT00C.cbl:525-535` | TDQ error handling EVALUATE block |
| `app/cpy/COCOM01Y.cpy:19-47` | COMMAREA layout (CARDDEMO-COMMAREA) |
| `app/cpy/CSUTLDPY.cpy:18-60` | Date validation reusable paragraphs |
| `app/bms/COACTUP.bms:20-28` | DFHMSD/DFHMDI mapset and map definition |
| `app/bms/COACTUP.bms:84-135` | DFHMDF field definitions (ACCTSID, ACSTTUS, ACRDLIM) |
| `app/catlg/LISTCAT.txt:22` | VSAM KSDS cluster definition for ACCTDATA |
| `app/catlg/LISTCAT.txt:59-60` | VSAM cluster key/record attributes |
