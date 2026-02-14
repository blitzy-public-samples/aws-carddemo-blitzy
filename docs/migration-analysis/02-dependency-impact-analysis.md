# Dependency Impact Analysis

## Document Overview

This document provides a per-utility dependency impact analysis for the AWS CardDemo mainframe-to-Java migration. For each proprietary utility identified in the [Proprietary Utility Inventory](01-proprietary-utility-inventory.md), this analysis documents:

- **Contextual Purpose:** How the utility is used within the CardDemo codebase
- **Complexity Assessment:** Java replication complexity rated as Low, Medium, High, or Very High
- **Java Equivalent Mapping:** Specific Java libraries, classes, and methods that replicate utility behavior
- **Behavioral Gap Flags:** Utilities where no direct Java equivalent exists and behavioral differences are expected

**Scope:** All 15 proprietary utility categories across 28 COBOL programs, 28 copybooks, and 17 BMS map sources.

**Cross-References:**
- Utility catalog definitions: [01 — Proprietary Utility Inventory](01-proprietary-utility-inventory.md)
- Migration playbooks per utility: [03 — Migration Strategy](03-migration-strategy.md)
- Risk ratings per utility: [04 — Risk Assessment](04-risk-assessment.md)
- Behavioral parity testing: [05 — Testing & Validation Framework](05-testing-validation-framework.md)

---

## 1. Complexity Scoring Methodology

Each proprietary utility is assessed using a four-level complexity scale that considers the availability of Java equivalents, the magnitude of code changes required, and the level of behavioral validation needed to ensure migration fidelity.

### 1.1 Complexity Levels

| Level | Label | Criteria | Typical Effort |
|:------|:------|:---------|:---------------|
| 1 | **Low** | Direct Java standard library equivalent exists. Minimal code changes required. No behavioral risk — output is deterministically identical. | 1–2 days per utility instance |
| 2 | **Medium** | Java equivalent exists but requires adaptation (parameter mapping, error code translation, or output format adjustment). Moderate code changes needed. Some behavioral validation required to confirm equivalence. | 3–5 days per utility instance |
| 3 | **High** | No single Java equivalent exists. Requires a combination of libraries or custom wrapper code. Significant behavioral validation required due to semantic differences between mainframe and Java approaches. | 1–3 weeks per utility category |
| 4 | **Very High** | No direct Java equivalent exists. Requires architectural rethinking, custom framework development, and extensive behavioral validation. Migration involves paradigm shift, not just library substitution. | 3–8 weeks per utility category |

### 1.2 Assessment Dimensions

Each complexity rating is derived from three assessment dimensions:

| Dimension | Low | Medium | High | Very High |
|:----------|:----|:-------|:-----|:----------|
| **API Availability** | 1:1 Java standard library match | Java library exists with different API surface | Multiple libraries needed in combination | No library equivalent; custom development required |
| **Behavioral Fidelity** | Byte-identical output achievable | Output equivalent with minor format differences | Output equivalent with semantic adaptation | Output conceptually different; paradigm change |
| **Integration Scope** | Isolated change within single method | Changes span single class or module | Changes span multiple classes and configuration | Changes require new architectural component |

### 1.3 Complexity Distribution Summary

| Complexity | Count | Utilities |
|:-----------|:------|:----------|
| Low | 4 | CICS System Services (ASSIGN/ASKTIME/FORMATTIME), IEBGENER, IEFBR14, COBOL Intrinsic Functions |
| Medium | 5 | CEE3ABD, CICS Program Control (XCTL/RETURN/LINK), IDCAMS, SORT, IBM Proprietary Copybooks (DFHBMSCA/DFHAID) |
| High | 2 | CEEDAYS/CSUTLDTC, CICS File Control (READ/WRITE/REWRITE/DELETE/STARTBR/READNEXT/READPREV/ENDBR) |
| Very High | 3 | CICS Terminal Control (SEND MAP/RECEIVE MAP/SEND TEXT), CICS TDQ (WRITEQ TD), BMS Map Macros (DFHMSD/DFHMDI/DFHMDF) |

---

## 2. Per-Utility Impact Cards

Each impact card provides a structured assessment of a single proprietary utility following a consistent format: context, complexity rating, Java equivalent mapping, and behavioral gap analysis.

### 2.1 CEE3ABD — LE Abend Handler

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | CEE3ABD (IBM Language Environment Abnormal Termination) |
| **Category** | Abend Handling |
| **Complexity** | **Medium** |
| **Programs Affected** | 9 batch programs |
| **Java Equivalent** | Custom exception framework + `System.exit()` / Spring Boot `ApplicationRunner` error handling |

**Contextual Purpose:**

CEE3ABD is called by all 9 batch programs in the CardDemo application (CBACT01C, CBACT02C, CBACT03C, CBACT04C, CBCUS01C, CBSTM03A, CBTRN01C, CBTRN02C, CBTRN03C) as the standard unrecoverable error handler. When invoked, it terminates the Language Environment enclave, produces a formatted CEEDUMP to SYSOUT, and returns a non-zero condition code to JCL for step-level error management.

All 9 programs use an identical call pattern within the `9999-ABEND-PROGRAM` paragraph:

```cobol
       9999-ABEND-PROGRAM.
           DISPLAY 'ABENDING PROGRAM'
           MOVE 0 TO TIMING
           MOVE 999 TO ABCODE
           CALL 'CEE3ABD'.
```

`Source: app/cbl/CBACT01C.cbl:169-173`

The ABCODE value of 999 is consistent across all batch programs, and TIMING set to 0 requests a dump. CEE3ABD is always invoked from file I/O error handling paragraphs after displaying the file status code, making it a centralized failure pathway.

`Source: app/cbl/CBACT04C.cbl:628-632`

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| `CALL 'CEE3ABD'` | `throw new ApplicationAbendException(abcode)` + `System.exit(abcode)` | Java Standard Library / Custom Framework |
| ABCODE (user abend code) | Exception error code or `System.exit()` status code | Java Standard Library |
| TIMING (dump control) | Thread dump via `Thread.getAllStackTraces()` or JVM heap dump via `-XX:+HeapDumpOnOutOfMemoryError` | JVM Runtime |
| CEEDUMP (formatted dump) | Stack trace logging via SLF4J/Logback + structured JSON diagnostic output | SLF4J 2.0 + Logback 1.4 |
| JCL condition code | Process exit code via `System.exit(int)` or Spring Batch `ExitStatus` | Java Standard Library / Spring Batch 5.x |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| Dump content | CEEDUMP includes register contents, storage areas, LE heap, and program traceback | Java stack trace shows call stack with line numbers but no register/memory dump | **Low** — functionally equivalent for diagnostics |
| Dump format | Fixed-format text dump written to SYSOUT DD | Configurable log format (text, JSON, XML) via logging framework | **Low** — Java provides superior flexibility |
| Termination scope | Terminates entire LE enclave (run unit) | `System.exit()` terminates JVM; exception handling can be more granular | **Low** — Java offers finer-grained control |

**Assessment Justification:** Rated **Medium** because while no single Java API replicates CEE3ABD exactly, a custom exception class combined with structured logging and `System.exit()` provides functionally equivalent behavior. The moderate effort comes from ensuring consistent dump formatting across all 9 migrated batch programs and mapping JCL condition code semantics to process exit codes.

---

### 2.2 CEEDAYS — Lillian Date Conversion (via CSUTLDTC Wrapper)

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | CEEDAYS (IBM Language Environment Date Conversion) |
| **Category** | Date/Time Services |
| **Complexity** | **High** |
| **Programs Affected** | 1 wrapper (CSUTLDTC) + 2 consumers (CORPT00C, COTRN02C) via CSUTLDPY copybook |
| **Java Equivalent** | `java.time.LocalDate.parse()` + `java.time.temporal.JulianFields.JULIAN_DAY` + `DateTimeParseException` |

**Contextual Purpose:**

CEEDAYS is not called directly by application programs. It is wrapped by CSUTLDTC.cbl, which provides a simplified three-parameter interface (date string, format mask, result message). The wrapper accepts a 10-character date and a 10-character format mask, calls CEEDAYS, and returns a structured result message containing the severity code, message number, and a human-readable validation result.

```cobol
           CALL "CEEDAYS" USING
                  WS-DATE-TO-TEST,
                  WS-DATE-FORMAT,
                  OUTPUT-LILLIAN,
                  FEEDBACK-CODE
```

`Source: app/cbl/CSUTLDTC.cbl:116-120`

The FEEDBACK-CODE structure contains 88-level conditions for 8 distinct error types:

| Condition Name | Hex Value | Meaning |
|:---------------|:----------|:--------|
| FC-INVALID-DATE | `X'0000000000000000'` | Date is valid (success) |
| FC-INSUFFICIENT-DATA | `X'000309CB59C3C5C5'` | Insufficient data supplied |
| FC-BAD-DATE-VALUE | `X'000309CC59C3C5C5'` | Invalid date value |
| FC-INVALID-ERA | `X'000309CD59C3C5C5'` | Invalid era specification |
| FC-UNSUPP-RANGE | `X'000309D159C3C5C5'` | Unsupported date range |
| FC-INVALID-MONTH | `X'000309D559C3C5C5'` | Invalid month value |
| FC-BAD-PIC-STRING | `X'000309D659C3C5C5'` | Bad picture string (format mask) |
| FC-NON-NUMERIC-DATA | `X'000309D859C3C5C5'` | Non-numeric data in date |
| FC-YEAR-IN-ERA-ZERO | `X'000309D959C3C5C5'` | Year in era is zero |

`Source: app/cbl/CSUTLDTC.cbl:62-70`

The wrapper evaluates these conditions and maps them to human-readable messages:

```cobol
           EVALUATE TRUE
              WHEN FC-INVALID-DATE
                 MOVE 'Date is valid'      TO WS-RESULT
              WHEN FC-INSUFFICIENT-DATA
                 MOVE 'Insufficient'       TO WS-RESULT
              WHEN FC-BAD-DATE-VALUE
                 MOVE 'Datevalue error'    TO WS-RESULT
              ...
           END-EVALUATE
```

`Source: app/cbl/CSUTLDTC.cbl:128-149`

Consuming programs access CSUTLDTC through the CSUTLDPY copybook, which provides reusable date validation paragraphs (EDIT-DATE-CCYYMMDD, EDIT-YEAR-CCYY, EDIT-MONTH, EDIT-DAY).

`Source: app/cpy/CSUTLDPY.cpy:18-60`

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| `CALL "CEEDAYS"` | `java.time.LocalDate.parse(dateStr, formatter)` | Java Standard Library (`java.time`) |
| Lillian day number (days since Oct 15, 1582) | `date.getLong(JulianFields.JULIAN_DAY) - 2299161L` | `java.time.temporal.JulianFields` |
| FEEDBACK-CODE (8 error types) | `DateTimeParseException` + custom validation enum | Java Standard Library + Custom |
| CSUTLDTC wrapper program | `DateValidationService.validate(date, format)` — Java service class | Custom Service |
| CSUTLDPY copybook (reusable paragraphs) | Shared `DateValidator` utility class with static methods | Custom Utility |
| Format mask `'YYYY-MM-DD'` | `DateTimeFormatter.ofPattern("yyyy-MM-dd")` | `java.time.format.DateTimeFormatter` |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| Error granularity | 8 distinct FEEDBACK-CODE conditions with hex-encoded severity/message pairs | `DateTimeParseException` with text message; fewer distinct error categories | **Medium** — requires custom error enum to replicate granularity |
| Lillian day epoch | Days since October 15, 1582 (Gregorian calendar reform) | `JulianFields.JULIAN_DAY` uses Julian Day Number (days since January 1, 4713 BC); offset calculation required | **Medium** — mathematically convertible but must be precisely validated |
| Calendar system | CEEDAYS supports proleptic Gregorian calendar with era handling | `java.time` uses ISO-8601 proleptic Gregorian calendar | **Low** — same calendar system, minor edge cases at era boundaries |
| Format mask syntax | LE format masks: `YYYYMMDD`, `YYYY-MM-DD`, `MM/DD/YYYY`, etc. | `DateTimeFormatter` patterns: `yyyyMMdd`, `yyyy-MM-dd`, `MM/dd/yyyy` | **Low** — pattern syntax differs but semantics map 1:1 |

**Assessment Justification:** Rated **High** because while `java.time` provides excellent date parsing capabilities, replicating the exact CEEDAYS FEEDBACK-CODE granularity (8 distinct error conditions) requires custom error handling. The Lillian day epoch calculation adds mathematical complexity, and the CSUTLDTC wrapper's integration pattern (LINKAGE SECTION with result message formatting) requires a purpose-built Java service class. Consuming programs rely on specific severity code values, requiring careful validation.

---

### 2.3 CICS File Control — READ/WRITE/REWRITE/DELETE/STARTBR/READNEXT/READPREV/ENDBR

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | CICS File Control API |
| **Category** | Database Operations / File Handling |
| **Complexity** | **High** |
| **Programs Affected** | 13 CICS programs |
| **Java Equivalent** | Spring Data JPA / JDBC + HikariCP connection pooling |

**Contextual Purpose:**

CICS File Control commands provide record-level access to VSAM KSDS files through the CICS File Control Table (FCT). In CardDemo, 13 online programs use these commands to access 6 VSAM datasets:

| VSAM File (DD Name) | FCT Name | Record Type | Key Field | Programs Using |
|:---------------------|:---------|:------------|:----------|:---------------|
| ACCTFILE | ACCTDAT | Account records | ACCT-ID (11 bytes) | COACTVWC, COACTUPC, COBIL00C |
| CARDFILE | CARDDAT | Card records | CARD-NUM (16 bytes) | COCRDLIC, COCRDSLC |
| XREFFILE | XREFDAT | Cross-reference | CARD-NUM (16 bytes) | COACTUPC, COCRDLIC, COTRN02C |
| TRANSACT | CRDTRN | Transaction records | CARD-NUM + TRAN-ID (32 bytes) | COTRN00C, COTRN01C, COTRN02C |
| USRSEC | USRSEC | User security | USER-ID (8 bytes) | COSGN00C, COUSR01C, COUSR02C, COUSR03C |
| CARDAIX | CARDAIX | Card alternate index | ACCT-ID (11 bytes) | COACTUPC |

The standard CICS File Control access pattern in CardDemo uses RESP/RESP2 response code checking:

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

           EVALUATE WS-RESP-CD
               WHEN 0
                   ...process record...
               WHEN DFHRESP(NOTFND)
                   ...handle not found...
               WHEN OTHER
                   ...handle error...
           END-EVALUATE
```

`Source: app/cbl/COSGN00C.cbl:211-221`

Browse operations use STARTBR/READNEXT/READPREV/ENDBR for sequential traversal with positioned cursor:

- **STARTBR:** Establishes browse position at a key value
- **READNEXT:** Reads the next record in key sequence
- **READPREV:** Reads the previous record (backward browse)
- **ENDBR:** Terminates the browse session and releases the cursor

REWRITE operations (used by COACTUPC for account updates) rely on the record token from a prior READ for UPDATE, establishing optimistic locking semantics.

`Source: app/cbl/COACTUPC.cbl:3654-3662`

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| `EXEC CICS READ` | `repository.findById(key)` or `jdbcTemplate.queryForObject()` | Spring Data JPA 3.x / Spring JDBC 6.x |
| `EXEC CICS WRITE` | `repository.save(entity)` (INSERT) | Spring Data JPA 3.x |
| `EXEC CICS REWRITE` | `repository.save(entity)` (UPDATE) with `@Version` optimistic locking | Spring Data JPA 3.x |
| `EXEC CICS DELETE` | `repository.deleteById(key)` | Spring Data JPA 3.x |
| `EXEC CICS STARTBR` | `SELECT ... WHERE key >= ? ORDER BY key` with JDBC `ResultSet` cursor | Spring JDBC 6.x |
| `EXEC CICS READNEXT` | `resultSet.next()` | JDBC Standard |
| `EXEC CICS READPREV` | `resultSet.previous()` (scrollable) or reverse-order query | JDBC Standard |
| `EXEC CICS ENDBR` | `resultSet.close()` | JDBC Standard |
| RESP/RESP2 error codes | Exception handling (`DataAccessException` hierarchy) | Spring Framework 6.x |
| VSAM KSDS (keyed file) | RDBMS table with primary key + indexes | PostgreSQL / MySQL / Oracle |
| FCT (File Control Table) | DataSource configuration + JPA entity mapping | Spring Boot auto-configuration |
| HikariCP connection pool | Connection pooling for JDBC connections | HikariCP 5.x |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| Browse cursor semantics | STARTBR/READNEXT maintains a server-side cursor with positioning within the VSAM dataset; cursor survives across CICS pseudo-conversational boundaries when held | SQL cursors are typically connection-scoped; pagination with `LIMIT/OFFSET` or keyset pagination differs in positioning semantics | **High** — browse-intensive programs (COACTVWC, COTRN00C, COUSR01C) require careful cursor migration |
| Record-level locking | CICS READ for UPDATE acquires exclusive record lock until REWRITE or UNLOCK | JPA `@Version` provides optimistic locking; pessimistic locking via `SELECT ... FOR UPDATE` is connection-scoped | **Medium** — locking granularity differs but functional equivalence achievable |
| RESP/RESP2 error model | Numeric response codes (0=OK, 13=NOTFND, 12=FILENOTFOUND, etc.) with companion RESP2 detail codes | Exception-based model (DataAccessException hierarchy) with different categorization | **Medium** — requires mapping table for error code translation |
| Key equality semantics | VSAM key comparison uses EBCDIC collation sequence | RDBMS key comparison uses database-specific collation (typically UTF-8) | **Low** — CardDemo keys are numeric, minimizing collation impact |
| Transaction boundaries | CICS task-level transaction (SYNCPOINT for commit) | Spring `@Transactional` annotation with configurable isolation levels | **Low** — well-supported by Spring Transaction Management |

**Assessment Justification:** Rated **High** because CICS File Control is the most pervasive API in the application (13 programs, 6 files, 100+ individual commands) and the VSAM-to-RDBMS migration involves fundamental data access pattern changes. While Spring Data JPA provides excellent CRUD support, the browse operation (STARTBR/READNEXT/READPREV/ENDBR) requires careful pagination design, and the RESP/RESP2 error code model differs significantly from Java's exception-based approach.

---

### 2.4 CICS Program Control — XCTL/RETURN/LINK

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | CICS Program Control API |
| **Category** | Transaction Processing |
| **Complexity** | **Medium** |
| **Programs Affected** | 16 CICS programs |
| **Java Equivalent** | Spring MVC `@Controller` routing with `@RequestMapping` + `HttpSession` |

**Contextual Purpose:**

CICS Program Control manages inter-program communication and pseudo-conversational flow in the CardDemo application. Three commands are used:

**XCTL (Transfer Control):** Used by COMEN01C for dynamic menu routing. XCTL transfers control to a target program, passing the COMMAREA, and the calling program is removed from the program stack.

```cobol
               EXEC CICS
                   XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
                   COMMAREA(CARDDEMO-COMMAREA)
               END-EXEC
```

`Source: app/cbl/COMEN01C.cbl:152-155`

**RETURN TRANSID:** Used by all CICS programs to return control to CICS, specifying the next transaction ID for pseudo-conversational continuation. This is the core mechanism enabling CardDemo's pseudo-conversational architecture.

```cobol
           EXEC CICS RETURN
                     TRANSID (WS-TRANID)
                     COMMAREA (CARDDEMO-COMMAREA)
           END-EXEC.
```

`Source: app/cbl/COMEN01C.cbl:107-110`

**COMMAREA (Communication Area):** The COCOM01Y.cpy copybook defines the 1024-byte COMMAREA structure that carries state between program invocations. Key fields include:

| Field | Size | Purpose |
|:------|:-----|:--------|
| CDEMO-FROM-TRANID | 4 bytes | Source transaction ID |
| CDEMO-FROM-PROGRAM | 8 bytes | Source program name |
| CDEMO-TO-TRANID | 4 bytes | Target transaction ID |
| CDEMO-TO-PROGRAM | 8 bytes | Target program name |
| CDEMO-USER-ID | 8 bytes | Authenticated user identifier |
| CDEMO-USER-TYPE | 1 byte | 'A' (admin) or 'U' (user) |
| CDEMO-PGM-CONTEXT | 1 byte | 0=enter, 1=reenter |
| CDEMO-CUST-ID | 9 bytes | Current customer context |
| CDEMO-ACCT-ID | 11 bytes | Current account context |
| CDEMO-CARD-NUM | 16 bytes | Current card context |

`Source: app/cpy/COCOM01Y.cpy:19-47`

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| `EXEC CICS XCTL` | `return "forward:/targetController"` or `RedirectView` | Spring MVC 6.x |
| `EXEC CICS RETURN TRANSID` | HTTP response (end of request lifecycle); next request routed by URL | Spring MVC 6.x |
| `EXEC CICS LINK` | Method call to service bean or `RestTemplate` for remote call | Spring Framework 6.x |
| COMMAREA (COCOM01Y.cpy) | `HttpSession` attributes or `@SessionAttributes` model | Spring MVC 6.x / Jakarta Servlet |
| DFHCOMMAREA LINKAGE | Controller method parameters + session-scoped beans | Spring Framework 6.x |
| Transaction ID (TRANSID) | URL path or `@RequestMapping` value | Spring MVC 6.x |
| Program name routing | Controller class mapping via `@Controller` | Spring MVC 6.x |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| XCTL memory semantics | XCTL replaces the calling program in the CICS program stack; the calling program is no longer in memory | Servlet forward preserves the request chain; calling controller remains in the JVM | **Low** — memory management difference has no functional impact |
| COMMAREA byte layout | Fixed 1024-byte structure with positional field access | Java objects with named fields; no byte-level packing required | **Medium** — requires mapping COMMAREA fields to session attributes |
| Pseudo-conversational flow | RETURN TRANSID frees CICS resources between interactions; state preserved only in COMMAREA | HTTP session persists between requests; server resources remain allocated | **Low** — HTTP session is a natural equivalent |
| Program name resolution | Dynamic program name from COMMAREA field | URL-based routing; dynamic dispatch via controller mapping | **Low** — Spring MVC provides equivalent routing flexibility |

**Assessment Justification:** Rated **Medium** because Spring MVC provides a natural mapping for CICS program control — XCTL maps to controller forwarding, RETURN TRANSID maps to HTTP request/response lifecycle, and COMMAREA maps to HTTP session state. The moderate complexity comes from translating the byte-level COMMAREA structure to typed Java objects and preserving the pseudo-conversational flow pattern across all 16 programs.

---

### 2.5 CICS Terminal Control — SEND MAP/RECEIVE MAP/SEND TEXT

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | CICS Terminal Control API |
| **Category** | Screen I/O |
| **Complexity** | **Very High** |
| **Programs Affected** | 16 CICS programs + 17 BMS map sources |
| **Java Equivalent** | Spring MVC + Thymeleaf (server-rendered) or REST API + React/Angular (SPA) |

**Contextual Purpose:**

CICS Terminal Control is the most pervasive API in CardDemo, used by all 16 CICS online programs to interact with the 3270 terminal. Every user-facing screen operation flows through SEND MAP (display data) and RECEIVE MAP (capture input).

**SEND MAP:** Transmits a formatted screen to the 3270 terminal using a BMS map definition. The map contains field positions, lengths, attributes (color, intensity, protection), and data values.

```cobol
           EXEC CICS SEND
                     MAP('COMEN1A')
                     MAPSET('COMEN01')
                     FROM(COMEN1AO)
                     ERASE
           END-EXEC.
```

`Source: app/cbl/COMEN01C.cbl:189-194`

**RECEIVE MAP:** Captures user input from the 3270 terminal into the map's input data structure. Only modified fields (those with Modified Data Tags set) are transmitted back.

**SEND TEXT:** Used by COMEN02C for simple text display without BMS map formatting.

The 17 BMS map sources define the screen contracts consumed by these commands:

| BMS Mapset | Map Name | Program | Screen Function |
|:-----------|:---------|:--------|:----------------|
| COMEN01 | COMEN1A | COMEN01C | Main menu |
| COMEN02 | COMEN2A | COMEN02C | Admin menu |
| COSGN00 | COSGN0A | COSGN00C | Sign-on screen |
| COACTUP | CACTUPA | COACTUPC | Account update |
| COACTVW | CACTVWA | COACTVWC | Account view |
| COBIL00 | COBIL0A | COBIL00C | Bill payment |
| COCRDLI | CCRDLIA | COCRDLIC | Card list |
| COCRDSL | CCRDSLA | COCRDSLC | Card detail |
| COTRN00 | COTRN0A | COTRN00C | Transaction list |
| COTRN01 | COTRN1A | COTRN01C | Transaction detail |
| COTRN02 | COTRN2A | COTRN02C | Transaction add |
| COUSR00 | COUSR0A | COUSR00C | User menu |
| COUSR01 | COUSR1A | COUSR01C | User list |
| COUSR02 | COUSR2A | COUSR02C | User add |
| COUSR03 | COUSR3A | COUSR03C | User update |
| CORPT00 | CORPT0A | CORPT00C | Report generation |
| COADM01 | COADM1A | COADM01C | Admin screen |

Each BMS map defines field positions on a 24×80 character grid with attributes controlled by DFHBMSCA constants (color, intensity, protection).

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent (Server-Rendered) | Java Equivalent (SPA) | Library |
|:--------------------|:----------------------------------|:----------------------|:--------|
| `EXEC CICS SEND MAP` | Thymeleaf template rendering via `return "viewName"` | REST API `@ResponseBody` JSON response | Spring MVC + Thymeleaf 3.x / Spring Web |
| `EXEC CICS RECEIVE MAP` | HTML form POST with `@ModelAttribute` binding | AJAX POST with `@RequestBody` JSON binding | Spring MVC 6.x |
| `EXEC CICS SEND TEXT` | `ResponseEntity<String>` or `@ResponseBody` | REST text/plain response | Spring MVC 6.x |
| BMS Map (DFHMSD/DFHMDI/DFHMDF) | HTML template with form fields | React/Angular component with form state | Thymeleaf 3.x / React 18 / Angular 17 |
| Map ERASE option | Full page render (HTML) | SPA full component re-render | Browser Standard |
| Field attributes (DFHBMSCA) | CSS classes (color, visibility, readonly) | CSS-in-JS or CSS Modules | CSS Standard |
| Modified Data Tag (MDT) | HTML form only submits filled fields; dirty checking on client | React controlled components with state change tracking | Framework Standard |
| Cursor positioning | HTML `autofocus` attribute or JavaScript `focus()` | React `useRef` / Angular `@ViewChild` | Browser/Framework Standard |
| 3270 screen coordinates (row, col) | CSS grid or flexbox layout | CSS Grid Layout | CSS Standard |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| I/O model | 3270 is a block-mode terminal: entire screen sent/received in one I/O operation; field-level attribute control per character position | HTTP is request/response: form data submitted as key-value pairs; no character-position concept | **Very High** — complete paradigm shift from block-mode to request/response |
| Screen layout model | Fixed 24×80 character grid with absolute coordinate positioning (row, column) for every field | HTML/CSS uses flow layout with responsive design; no fixed character grid | **Very High** — requires complete UI redesign |
| Attribute bytes | Single-byte hex values (DFHBMSCA) control field color, intensity, protection, and cursor position simultaneously | CSS properties (color, font-weight, readonly attribute) provide equivalent visuals but through separate mechanisms | **High** — 1:many mapping from attribute byte to CSS properties |
| AID key handling | Physical terminal keys (PF1-PF24, ENTER, CLEAR) generate specific AID byte values checked via DFHAID constants | Keyboard events (JavaScript) or button clicks; no standard PF key concept in browsers | **High** — requires keyboard shortcut mapping or button-based navigation |
| Screen field protection | DFHBMSCA attribute byte controls whether field is protected (display-only), unprotected (input), or autoskip | HTML `readonly`, `disabled` attributes; CSS pointer-events | **Low** — direct mapping available |

**Assessment Justification:** Rated **Very High** because the 3270 terminal I/O model is fundamentally different from HTTP-based web applications. There is no library that converts BMS maps to HTML — each of the 17 BMS maps must be manually redesigned as HTML templates or React/Angular components. The field-level attribute control, cursor positioning, Modified Data Tag semantics, and AID key handling require a complete UI paradigm migration affecting all 16 CICS programs. This is the largest single migration effort in the CardDemo application.

---

### 2.6 CICS System Services — ASSIGN/ASKTIME/FORMATTIME

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | CICS System Services API |
| **Category** | System Identification / Date/Time Services |
| **Complexity** | **Low** |
| **Programs Affected** | 2 programs (COSGN00C, COBIL00C) |
| **Java Equivalent** | `System.getProperty()` / `InetAddress` / `java.time.LocalDateTime` |

**Contextual Purpose:**

CICS System Services provide application identity and time retrieval. Usage in CardDemo is limited to two programs:

**ASSIGN APPLID/SYSID** (COSGN00C): Retrieves the CICS application ID and system ID for display on the sign-on screen. Used to identify which CICS region the user is connected to.

```cobol
           EXEC CICS ASSIGN
               APPLID(APPLIDO OF COSGN0AO)
           END-EXEC

           EXEC CICS ASSIGN
               SYSID(SYSIDO OF COSGN0AO)
           END-EXEC.
```

`Source: app/cbl/COSGN00C.cbl:198-204`

**ASKTIME/FORMATTIME** (COBIL00C): Retrieves the current system time and formats it into a displayable date and time string for the bill payment timestamp.

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

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| `EXEC CICS ASSIGN APPLID` | `System.getProperty("application.name")` or Spring `@Value("${spring.application.name}")` | Java Standard Library / Spring Boot |
| `EXEC CICS ASSIGN SYSID` | `InetAddress.getLocalHost().getHostName()` or Spring `@Value("${server.hostname}")` | `java.net.InetAddress` |
| `EXEC CICS ASKTIME` | `java.time.LocalDateTime.now()` | `java.time` |
| `EXEC CICS FORMATTIME YYYYMMDD` | `LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))` | `java.time.format.DateTimeFormatter` |
| `EXEC CICS FORMATTIME TIME` | `LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))` | `java.time.format.DateTimeFormatter` |
| ABSTIME (packed decimal) | `Instant.now()` or `System.currentTimeMillis()` | `java.time.Instant` |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| Application identity | CICS APPLID is a 1-8 character CICS region identifier defined in SIT | Application name is a configurable property; no fixed-length constraint | **Minimal** — functionally equivalent |
| System identity | CICS SYSID is a 4-character CICS system identifier | Hostname can be any length; mapped from environment | **Minimal** — functionally equivalent |
| Time precision | ABSTIME is a packed decimal value with microsecond precision (CICS epoch-based) | `Instant.now()` provides nanosecond precision | **Minimal** — Java has equal or better precision |

**Assessment Justification:** Rated **Low** because application identity and time retrieval are standard operations with direct Java equivalents. The limited scope (2 programs) and straightforward mapping make this the simplest CICS API category to migrate.

---

### 2.7 CICS TDQ — WRITEQ TD (Transient Data Queue)

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | CICS Transient Data Queue API |
| **Category** | Transaction Processing / Asynchronous Job Submission |
| **Complexity** | **Very High** |
| **Programs Affected** | 1 program (CORPT00C) |
| **Java Equivalent** | JMS (ActiveMQ/RabbitMQ) + Spring Batch, or AWS Batch / AWS Step Functions |

**Contextual Purpose:**

CORPT00C uses WRITEQ TD to write JCL records to an extrapartition Transient Data Queue named 'JOBS'. This TDQ is configured to route to the JES internal reader, enabling an online CICS program to submit batch jobs asynchronously. The program dynamically constructs JCL statements containing report parameters (date ranges, report type) and writes them record-by-record to the TDQ:

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

The JCL data includes job card, PROC execution (TRANREPT), SYSIN parameters (date range, card number filters), and the `/*EOF` end-of-file marker. This allows CORPT00C to trigger batch transaction report generation from the online CICS environment without user intervention in the batch submission process.

The JCL job data structure (JOB-DATA in WORKING-STORAGE) contains pre-formatted JCL statements with variable parameters:

```cobol
       01 JOB-DATA.
        02 JOB-DATA-1.
         05 FILLER                     PIC X(80) VALUE
         "//TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,".
         05 FILLER                     PIC X(80) VALUE
         "// NOTIFY=&SYSUID".
```

`Source: app/cbl/CORPT00C.cbl:81-86`

Error handling checks the RESP code after each write, displaying an error message and flagging the operation if the TDQ write fails.

`Source: app/cbl/CORPT00C.cbl:525-535`

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| `EXEC CICS WRITEQ TD QUEUE('JOBS')` | `jmsTemplate.convertAndSend("batch-jobs", jobRequest)` | Spring JMS 6.x + ActiveMQ/RabbitMQ |
| TDQ extrapartition → JES internal reader | Message consumer triggers Spring Batch job execution | Spring Batch 5.x |
| JCL job card + PROC | Spring Batch `Job` definition with parameters | Spring Batch 5.x |
| JCL SYSIN parameters | Job parameters (`JobParameters`) | Spring Batch 5.x |
| JES job scheduling | AWS Batch job submission or Kubernetes CronJob | AWS SDK / Kubernetes API |
| Asynchronous execution | `@Async` method + `CompletableFuture` or message queue consumer | Spring Framework 6.x |
| TDQ record-by-record write | Single message with structured payload (JSON) | Jackson JSON |

**Cloud-Native Alternative:**

| Mainframe Component | AWS Cloud-Native Equivalent | Service |
|:--------------------|:----------------------------|:--------|
| TDQ → JES internal reader | AWS Step Functions workflow trigger | AWS Step Functions |
| JCL batch job | AWS Batch job definition | AWS Batch |
| PROC execution | Container-based batch step | AWS Batch / ECS |
| SYSIN parameters | Step Functions input / Batch job parameters | AWS SDK |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| Job submission mechanism | TDQ-to-internal-reader is a mainframe-native pipeline: write records → JES picks up → JCL interpreted → job executed | No equivalent pipeline exists; requires explicit message queue + job scheduler integration | **Very High** — requires architectural replacement, not library substitution |
| JCL interpretation | JES interprets JCL dynamically (DD statements, PROC resolution, symbolic substitution) | No JCL interpreter in Java; job definition must be pre-configured in Spring Batch or AWS Batch | **Very High** — entire JCL paradigm is replaced |
| Record-by-record assembly | JCL built as 80-byte records, written sequentially to TDQ | Structured job request object (JSON/POJO) sent as single message | **Medium** — simpler in Java but requires restructuring |
| Synchronous confirmation | RESP code confirms each record was written to TDQ | Message acknowledgment confirms delivery to queue | **Low** — equivalent reliability pattern |

**Assessment Justification:** Rated **Very High** because the TDQ-to-internal-reader mechanism is a deeply mainframe-specific pattern with no direct Java parallel. The migration requires not just replacing an API call but redesigning the entire asynchronous batch job submission architecture — from JCL-based job definitions to Spring Batch jobs or AWS Batch containers. While only one program (CORPT00C) uses this pattern, the architectural impact is significant.

---

### 2.8 IDCAMS — Access Method Services

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | IDCAMS (IBM Access Method Services) |
| **Category** | Database Operations |
| **Complexity** | **Medium** |
| **Programs Affected** | JCL jobs (DEFVSAM, ACCTFILE, CARDFILE); 10+ VSAM datasets |
| **Java Equivalent** | DDL scripts (SQL) + JDBC + Flyway/Liquibase |

**Contextual Purpose:**

IDCAMS is the z/OS utility for defining, populating, and managing VSAM datasets. In CardDemo, it is used in JCL jobs to:

- **DEFINE CLUSTER:** Create VSAM KSDS datasets (ACCTDAT, CARDDAT, XREFDAT, CRDTRN, USRSEC, etc.)
- **REPRO:** Load initial data from sequential files into VSAM datasets
- **DELETE:** Remove VSAM datasets (cleanup/reinitialization)

The `app/catlg/LISTCAT.txt` file provides a complete IDCAMS LISTCAT forensic snapshot of all CardDemo VSAM clusters with attributes:

| VSAM Cluster | Key Length | Record Size (Avg/Max) | Key Position |
|:-------------|:-----------|:----------------------|:-------------|
| AWS.M2.CARDDEMO.ACCTDATA.PS | 11 bytes | 300/300 | 0 |
| AWS.M2.CARDDEMO.CARDDATA.PS | 16 bytes | 150/150 | 0 |
| AWS.M2.CARDDEMO.CARDXREF.PS | 16 bytes | 50/50 | 0 |
| AWS.M2.CARDDEMO.TRANSACT.PS | 32 bytes | 350/350 | 0 |
| AWS.M2.CARDDEMO.USRSEC.PS | 8 bytes | 80/80 | 0 |
| AWS.M2.CARDDEMO.CUSTDATA.PS | 9 bytes | 500/500 | 0 |
| AWS.M2.CARDDEMO.TCATBALF.PS | 17 bytes | 50/50 | 0 |
| AWS.M2.CARDDEMO.DISCGRP.PS | 16 bytes | 50/50 | 0 |
| AWS.M2.CARDDEMO.DALYTRAN.PS | 32 bytes | 350/350 | 0 |
| AWS.M2.CARDDEMO.TRANCATG.PS | 16 bytes | 33/33 | 0 |

`Source: app/catlg/LISTCAT.txt`

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| `DEFINE CLUSTER` | `CREATE TABLE` + `CREATE INDEX` DDL | SQL Standard / JDBC |
| `REPRO` (data load) | `INSERT INTO ... VALUES` or bulk load via JDBC batch | Spring JDBC 6.x |
| `DELETE` (dataset removal) | `DROP TABLE` DDL | SQL Standard |
| `LISTCAT` (catalog query) | Database metadata queries (`DatabaseMetaData`) | JDBC Standard |
| VSAM cluster attributes (CI size, CA splits) | Table storage parameters (tablespace, partitioning) | Database-specific DDL |
| KSDS key definition | `PRIMARY KEY` constraint | SQL Standard |
| Alternate index | `CREATE INDEX` or `CREATE UNIQUE INDEX` | SQL Standard |
| Schema versioning | Migration scripts with version tracking | Flyway 10.x / Liquibase 4.x |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| CI/CA split management | VSAM Control Interval and Control Area sizes affect I/O performance and are tunable | RDBMS manages page/block sizes internally; less direct control | **Low** — RDBMS auto-tuning is typically sufficient |
| SHAREOPTIONS | VSAM SHAREOPTIONS control cross-region/cross-system sharing semantics | RDBMS handles concurrency through isolation levels and locking | **Low** — RDBMS concurrency is more sophisticated |
| Catalog structure | VSAM catalog is a hierarchical namespace (HLQ.qualifier.component) | RDBMS uses schema.table naming | **Low** — naming convention change only |
| Fixed-length records | VSAM records are fixed-length binary structures | RDBMS rows have typed columns; no fixed-byte-length concept | **Medium** — record layout must be mapped to column definitions |

**Assessment Justification:** Rated **Medium** because the VSAM-to-RDBMS migration is well-understood (DEFINE CLUSTER → CREATE TABLE, REPRO → INSERT, DELETE → DROP TABLE) but requires careful translation of VSAM cluster attributes (key position, record size) to RDBMS schema definitions. Flyway or Liquibase provides equivalent schema versioning. The moderate effort comes from mapping 10+ VSAM datasets with their specific key structures and record layouts to normalized RDBMS tables.

---

### 2.9 SORT — DFSORT/SyncSort

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | SORT (DFSORT or SyncSort) |
| **Category** | Sorting / Data Transformation |
| **Complexity** | **Medium** |
| **Programs Affected** | COMBTRAN batch job |
| **Java Equivalent** | Java `Collections.sort()` / Streams API, Apache Commons CSV |

**Contextual Purpose:**

The COMBTRAN batch job uses the z/OS SORT utility to merge daily transactions with the master transaction file. SORT reads input datasets, applies control statements specifying sort keys (field position, length, format, and order), and produces a sorted/merged output dataset.

SORT control statements define field-level operations on fixed-width records using positional parameters (byte position, length, data type). Common operations include:

- **SORT FIELDS:** Define sort key positions and ordering (ascending/descending)
- **MERGE:** Combine multiple pre-sorted datasets
- **INCLUDE/OMIT:** Filter records based on field value conditions
- **OUTREC:** Reformat output records

`Source: README.md (COMBTRAN batch job description)`

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| SORT FIELDS | `Comparator.comparing()` chain on extracted fields | Java Standard Library (`java.util`) |
| MERGE (multiple files) | Multi-way merge sort using `PriorityQueue` | Java Standard Library |
| INCLUDE/OMIT (filtering) | `Stream.filter()` predicate | Java Standard Library (`java.util.stream`) |
| OUTREC (reformatting) | `Stream.map()` transformation | Java Standard Library (`java.util.stream`) |
| Fixed-width record parsing | `String.substring(start, end)` or Apache Commons `FixedLengthReader` | Apache Commons CSV 1.11 |
| Large file handling | External merge sort for files exceeding memory | Custom implementation or Apache Commons IO 2.16 |
| SORT utility invocation | Spring Batch `FlatFileItemReader` + `SortStepBuilder` | Spring Batch 5.x |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| EBCDIC collation | SORT uses EBCDIC collation sequence for character comparisons | Java uses Unicode (UTF-16) collation; character ordering differs | **Medium** — critical for character key fields; numeric keys unaffected |
| Positional field extraction | SORT operates on fixed byte positions within fixed-length records | Java requires explicit field parsing logic (substring or format-aware reader) | **Low** — straightforward with known record layout |
| Performance on large files | DFSORT is optimized for multi-GB file sorting with disk-based spill | Java `Collections.sort()` is memory-bound; external sort needed for very large files | **Medium** — requires external merge sort implementation for production-scale data |
| Control statement syntax | SORT control statements (SORT FIELDS, MERGE, INCLUDE, OUTREC) are declarative | Java sorting is imperative (code-based comparators and transformations) | **Low** — different syntax but equivalent semantics |

**Assessment Justification:** Rated **Medium** because Java provides robust sorting capabilities through the standard library and Streams API, but the EBCDIC-to-Unicode collation difference requires careful validation for character key fields. The fixed-width record parsing and potential need for external merge sort on large datasets add moderate complexity. The limited scope (one batch job) constrains the overall effort.

---

### 2.10 IEBGENER — Sequential Copy

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | IEBGENER (IBM Sequential Dataset Copy) |
| **Category** | File Handling |
| **Complexity** | **Low** |
| **Programs Affected** | DUSRSECJ batch job |
| **Java Equivalent** | `java.nio.file.Files.copy()`, `BufferedReader`/`BufferedWriter` |

**Contextual Purpose:**

IEBGENER is used by the DUSRSECJ batch job to copy the initial user security data from a sequential input file to the USRSEC VSAM dataset. In the simplest case, IEBGENER performs a byte-for-byte copy from SYSUT1 (input) to SYSUT2 (output) with no transformation.

`Source: README.md (DUSRSECJ batch job description)`

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| IEBGENER (byte copy) | `Files.copy(source, target, REPLACE_EXISTING)` | `java.nio.file.Files` |
| SYSUT1 (input DD) | `Path.of("/input/usrsec.dat")` | `java.nio.file.Path` |
| SYSUT2 (output DD) | `Path.of("/output/usrsec.dat")` or JDBC INSERT for RDBMS | `java.nio.file.Path` / Spring JDBC |
| Fixed-width record handling | `BufferedReader` with fixed-length `read(char[], off, len)` | `java.io.BufferedReader` |
| RECFM/LRECL (record format) | Known record length constant in Java | Application configuration |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| Character encoding | IEBGENER copies EBCDIC bytes as-is | Java reads/writes in platform encoding (UTF-8); EBCDIC conversion needed for migrated data | **Low** — one-time conversion during data migration |
| Record boundaries | Fixed-length records defined by LRECL; no delimiter needed | Java file I/O is stream-based; fixed-length parsing requires known record length | **Low** — straightforward with documented record layout |

**Assessment Justification:** Rated **Low** because sequential file copy is a trivial operation in Java. The `java.nio.file.Files.copy()` method provides a direct equivalent, and `BufferedReader`/`BufferedWriter` handles fixed-width record processing. The only consideration is the one-time EBCDIC-to-UTF-8 character encoding conversion during data migration.

---

### 2.11 IEFBR14 — Null Program

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | IEFBR14 (IBM Null Program) |
| **Category** | File Handling / Dataset Management |
| **Complexity** | **Low** |
| **Programs Affected** | CLOSEFIL and OPENFIL batch jobs |
| **Java Equivalent** | `java.nio.file.Files.createFile()` / `Files.delete()` |

**Contextual Purpose:**

IEFBR14 is a "do-nothing" program — it receives control and immediately returns. Its purpose is to trigger JCL DD statement processing for dataset allocation and deallocation. In CardDemo:

- **CLOSEFIL job:** Uses IEFBR14 with DD statements specifying DISP=(OLD,DELETE) to deallocate CICS VSAM files, making them available for batch processing
- **OPENFIL job:** Uses IEFBR14 with DD statements specifying DISP=(NEW,CATLG) or DISP=SHR to allocate VSAM files back to CICS

This pattern manages the transition of VSAM file ownership between CICS online and batch environments.

`Source: README.md (CLOSEFIL and OPENFIL batch job descriptions)`

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| IEFBR14 (null program) | No-op — the "program" itself has no Java equivalent needed | N/A |
| DD DISP=(OLD,DELETE) | `Files.deleteIfExists(path)` or database connection release | `java.nio.file.Files` |
| DD DISP=(NEW,CATLG) | `Files.createFile(path)` or database connection acquisition | `java.nio.file.Files` |
| DD DISP=SHR | Shared file lock via `FileChannel.lock(0, Long.MAX_VALUE, true)` | `java.nio.channels.FileChannel` |
| File ownership transition (CICS ↔ batch) | Connection pool management or database schema/role switching | HikariCP / Spring Security |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| DD allocation semantics | JCL DD statements manage dataset lifecycle (create, catalog, delete) independent of program logic | Java manages files directly via file system APIs; no catalog concept | **Low** — Java's direct file management is simpler |
| CICS/batch file sharing | IEFBR14 jobs manage file ownership transitions between CICS and batch regions | RDBMS handles concurrent access through connection pools and transaction isolation | **Low** — RDBMS eliminates the need for file ownership management |

**Assessment Justification:** Rated **Low** because IEFBR14 is a JCL-level utility with no programmatic logic. In a Java environment with RDBMS-backed data storage, the concept of file allocation/deallocation is replaced by connection pool management and transaction isolation — capabilities that are built into Spring Data and HikariCP.

---

### 2.12 BMS Map Macros — DFHMSD/DFHMDI/DFHMDF

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | BMS (Basic Mapping Support) Macros |
| **Category** | Screen I/O / Presentation Layer |
| **Complexity** | **Very High** |
| **Programs Affected** | 17 BMS map sources defining screens for 16 CICS programs |
| **Java Equivalent** | HTML5 forms + CSS, Thymeleaf/JSP templates, or React/Angular components |

**Contextual Purpose:**

BMS macros define the 3270 screen contracts for all CardDemo online screens. Three macro levels define the hierarchy:

- **DFHMSD (Map Set Definition):** Defines a set of related maps (screens) with shared attributes (terminal type, language, control mode)
- **DFHMDI (Map Definition):** Defines an individual map (screen) within a mapset, specifying screen size (24 rows × 80 columns)
- **DFHMDF (Map Field Definition):** Defines individual fields within a map, specifying position (row, column), length, attributes (color, intensity, protection), initial value, and symbolic name

The 17 BMS sources in `app/bms/` define the complete presentation layer contract:

| BMS Source File | Mapset Name | Fields | Screen Purpose |
|:----------------|:------------|:-------|:---------------|
| COACTUP.bms | COACTUP | ~40 fields | Account update form |
| COACTVW.bms | COACTVW | ~30 fields | Account view display |
| COBIL00.bms | COBIL00 | ~25 fields | Bill payment form |
| COCRDLI.bms | COCRDLI | ~35 fields | Card listing |
| COCRDSL.bms | COCRDSL | ~20 fields | Card detail |
| COMEN01.bms | COMEN01 | ~15 fields | Main menu |
| COMEN02.bms | COMEN02 | ~10 fields | Admin menu |
| CORPT00.bms | CORPT00 | ~20 fields | Report parameters |
| COSGN00.bms | COSGN00 | ~10 fields | Sign-on form |
| COTRN00.bms | COTRN00 | ~40 fields | Transaction list |
| COTRN01.bms | COTRN01 | ~25 fields | Transaction detail |
| COTRN02.bms | COTRN02 | ~30 fields | Transaction add form |
| COUSR00.bms | COUSR00 | ~10 fields | User menu |
| COUSR01.bms | COUSR01 | ~30 fields | User list |
| COUSR02.bms | COUSR02 | ~20 fields | User add form |
| COUSR03.bms | COUSR03 | ~20 fields | User update form |
| COADM01.bms | COADM01 | ~10 fields | Admin screen |

`Source: app/bms/*.bms`

Each DFHMDF macro specifies field-level detail:
- **POS=(row,col):** Absolute screen position on the 24×80 grid
- **LENGTH:** Field length in characters
- **ATTRB:** Attribute combination (BRT=bright, PROT=protected, UNPROT=input, NUM=numeric, IC=insert cursor, FSET=force MDT)
- **COLOR:** Field color (RED, GREEN, BLUE, YELLOW, TURQUOISE, WHITE, NEUTRAL)
- **INITIAL:** Default display value

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| DFHMSD (mapset) | HTML page template or React App component | Thymeleaf 3.x / React 18 |
| DFHMDI (map) | HTML `<form>` element or React form component | HTML5 Standard / React |
| DFHMDF (field) | HTML `<input>`, `<label>`, `<span>` elements | HTML5 Standard |
| POS=(row,col) | CSS Grid `grid-row`/`grid-column` or flexbox layout | CSS Grid Layout |
| ATTRB=(BRT) | CSS `font-weight: bold` | CSS |
| ATTRB=(PROT) | HTML `readonly` attribute | HTML5 Standard |
| ATTRB=(NUM) | HTML `<input type="number">` or `pattern="[0-9]*"` | HTML5 Standard |
| ATTRB=(IC) | HTML `autofocus` attribute | HTML5 Standard |
| COLOR=RED | CSS `color: red` or CSS class `.field-error` | CSS |
| INITIAL='value' | HTML `value="value"` attribute or Thymeleaf `th:value` | HTML5 / Thymeleaf |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| Layout model | Fixed 24×80 character grid; every field has absolute (row, column) coordinates | HTML/CSS flow layout; responsive design with no fixed character grid | **Very High** — fundamental layout paradigm difference |
| Attribute byte model | Single byte controls multiple field attributes simultaneously (color + intensity + protection) | Separate HTML attributes and CSS properties for each visual characteristic | **High** — 1:many mapping from BMS attribute to HTML/CSS |
| Compile-time binding | BMS macros are assembled into physical and symbolic map pairs linked at compile time | HTML templates are parsed at runtime; no compile-time binding | **Medium** — different binding model but functionally equivalent |
| Field naming | Symbolic map generates COBOL copybook with `fieldnameI` (input) and `fieldnameO` (output) structures | HTML `name` attribute for form fields; single field direction | **Medium** — input/output field duality must be mapped |

**Assessment Justification:** Rated **Very High** because each of the 17 BMS sources must be individually redesigned as HTML templates or React components. There is no automated BMS-to-HTML conversion that preserves the exact visual layout and field behavior. The fixed 24×80 grid model, attribute byte system, and compile-time binding pattern have no direct HTML/CSS equivalent, requiring complete UI redesign for each screen.

---

### 2.13 IBM Proprietary Copybooks — DFHBMSCA and DFHAID

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | DFHBMSCA (BMS Character Attribute Set) + DFHAID (Attention Identifier) |
| **Category** | Screen I/O Support Constants |
| **Complexity** | **Medium** |
| **Programs Affected** | 16+ CICS programs (all online programs include both copybooks) |
| **Java Equivalent** | Java enum or constant classes |

**Contextual Purpose:**

**DFHBMSCA** provides character attribute set constants used to control 3270 screen field appearance. In CardDemo, these constants are used via the CSSETATY.cpy copybook to dynamically set field colors and attributes based on validation state:

```cobol
      *    Set (TESTVAR1) to red if in error and * if blank
           IF (FLG-(TESTVAR1)-NOT-OK
           OR  FLG-(TESTVAR1)-BLANK)
           AND CDEMO-PGM-REENTER
               MOVE DFHRED             TO
                    (SCRNVAR2)C OF (MAPNAME3)O
               IF  FLG-(TESTVAR1)-BLANK
                   MOVE '*'            TO
                    (SCRNVAR2)O OF (MAPNAME3)O
               END-IF
           END-IF
```

`Source: app/cpy/CSSETATY.cpy:17-27`

Key DFHBMSCA constants used in CardDemo include:
- `DFHRED`, `DFHGREEN`, `DFHBLUE`, `DFHYELLO`, `DFHTURQ`, `DFHWHITE` (color attributes)
- `DFHBRY` (bright), `DFHNORM` (normal intensity), `DFHDARK` (dark/invisible)
- `DFHPROT` (protected), `DFHUNPRT` (unprotected)
- `DFHFSET` (force MDT — Modified Data Tag)

**DFHAID** provides attention identifier constants for detecting which key the user pressed. In CardDemo, these are used via the CSSTRPFY.cpy copybook to map 3270 AID keys to internal application flags:

```cobol
       YYYY-STORE-PFKEY.
           EVALUATE TRUE
             WHEN EIBAID IS EQUAL TO DFHENTER
               SET CCARD-AID-ENTER TO TRUE
             WHEN EIBAID IS EQUAL TO DFHCLEAR
               SET CCARD-AID-CLEAR TO TRUE
             WHEN EIBAID IS EQUAL TO DFHPF3
               SET CCARD-AID-PFK03 TO TRUE
             ...
           END-EVALUATE
```

`Source: app/cpy/CSSTRPFY.cpy:17-78`

Key DFHAID constants used in CardDemo include:
- `DFHENTER` (Enter key)
- `DFHCLEAR` (Clear key)
- `DFHPF1` through `DFHPF24` (PF keys 1-24)
- `DFHPA1`, `DFHPA2` (PA keys)

**Java Equivalent Mapping:**

| Mainframe Component | Java Equivalent | Library |
|:--------------------|:----------------|:--------|
| DFHBMSCA attribute constants | `FieldAttribute` enum with CSS class mappings | Custom Java enum |
| DFHRED, DFHGREEN, etc. | CSS classes: `.field-red`, `.field-green`, etc. | CSS |
| DFHBRY, DFHNORM, DFHDARK | CSS classes: `.field-bright`, `.field-normal`, `.field-hidden` | CSS |
| DFHPROT, DFHUNPRT | HTML `readonly`/`disabled` attributes | HTML5 Standard |
| DFHAID key constants | `UserAction` enum (ENTER, CLEAR, PF1-PF24) | Custom Java enum |
| DFHENTER | Button click or Enter key handler | JavaScript Event |
| DFHPF3 (typically "Exit") | Navigation button or keyboard shortcut (Ctrl+Q) | JavaScript Event |
| CSSETATY.cpy (attribute manipulation) | Thymeleaf conditional CSS class binding or React conditional className | Thymeleaf / React |
| CSSTRPFY.cpy (AID key mapping) | Event handler mapping (button onClick, keyboard onKeyDown) | JavaScript/React |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| Attribute encoding | Single-byte hex values with specific 3270 meaning; multiple attributes encoded in one byte | Separate CSS properties and HTML attributes; multiple mechanisms for one field | **Medium** — 1:many decomposition required |
| PF key model | Physical terminal keys generate unique single-byte AID values; universal across all 3270 applications | Browser keyboard events use different key codes; PF keys have no standard browser equivalent | **Medium** — requires button-based UI or keyboard shortcut mapping |
| Compile-time inclusion | COPY statement includes copybook at compile time; constants become inline values | Java enum/constants loaded at class initialization | **Low** — equivalent timing |

**Assessment Justification:** Rated **Medium** because the constants themselves are simple value mappings that translate directly to Java enums. The moderate complexity arises from the need to implement the CSSETATY.cpy pattern (dynamic attribute manipulation based on validation state) using Thymeleaf conditional classes or React state-driven styling, and the CSSTRPFY.cpy pattern (AID-to-action mapping) using JavaScript event handlers.

---

### 2.14 COBOL Intrinsic Functions

| Attribute | Assessment |
|:----------|:-----------|
| **Utility** | COBOL Intrinsic Functions (7 functions) |
| **Category** | Data Transformation / Date/Time Services |
| **Complexity** | **Low** |
| **Programs Affected** | 6+ programs (CBACT01C, CBACT04C, CBTRN02C, CBTRN03C, COACTUPC, others) |
| **Java Equivalent** | Java Standard Library (`java.time`, `Math`, `String`, `Double`) |

**Contextual Purpose:**

Seven COBOL intrinsic functions are used across the CardDemo application for standard data manipulation. While not IBM-proprietary (they are COBOL-85/2002 standard), they require mapping to Java equivalents during migration.

| Function | Programs | Usage Context | Source Citation |
|:---------|:---------|:-------------|:----------------|
| CURRENT-DATE | CBACT01C, CBACT04C, CBTRN02C | Timestamp generation for records | `Source: app/cbl/CBACT04C.cbl:614` |
| MOD | CBACT04C, CBTRN03C | Modular arithmetic in calculations | `Source: app/cbl/CBACT04C.cbl` |
| TEST-NUMVAL-C | COACTUPC | Currency amount validation (returns 0 if valid) | `Source: app/cbl/COACTUPC.cbl:1078` |
| NUMVAL-C | COACTUPC | Currency string to numeric conversion | `Source: app/cbl/COACTUPC.cbl:1080` |
| UPPER-CASE | COACTUPC | Case-insensitive field comparison | `Source: app/cbl/COACTUPC.cbl:1685` |
| TRIM | COACTUPC | Whitespace removal for comparison | `Source: app/cbl/COACTUPC.cbl:1698` |
| INTEGER-OF-DATE | Various | Convert date to integer for arithmetic | Standard usage |

**Java Equivalent Mapping:**

| COBOL Function | Java Equivalent | Library | Notes |
|:---------------|:----------------|:--------|:------|
| `FUNCTION CURRENT-DATE` | `java.time.LocalDateTime.now()` + `DateTimeFormatter` | `java.time` | COBOL returns 21-char string (YYYYMMDDHHMMSSNNNNNN±HHMM); Java returns structured object |
| `FUNCTION MOD(a, b)` | `Math.floorMod(a, b)` or `a % b` | `java.lang.Math` | COBOL MOD follows mathematical definition; Java `%` is remainder (sign differs for negatives) |
| `FUNCTION TEST-NUMVAL-C(str)` | Custom validator: regex `^[+-]?\\d*\\.?\\d+$` with currency symbol handling | Custom / `java.util.regex` | Returns 0 if valid in COBOL; Java requires try-catch or regex approach |
| `FUNCTION NUMVAL-C(str)` | `Double.parseDouble(str.replaceAll("[^0-9.+-]", ""))` | `java.lang.Double` | Currency symbol stripping needed; locale-aware parsing for international formats |
| `FUNCTION UPPER-CASE(str)` | `str.toUpperCase()` | `java.lang.String` | Direct equivalent; locale parameter recommended |
| `FUNCTION TRIM(str)` | `str.trim()` or `str.strip()` (Java 11+) | `java.lang.String` | Direct equivalent; `strip()` handles Unicode whitespace |
| `FUNCTION INTEGER-OF-DATE(date)` | `date.toEpochDay()` or `ChronoUnit.DAYS.between(epoch, date)` | `java.time` | Epoch differs: COBOL uses Jan 1, 1601; Java uses Jan 1, 1970 |

**Behavioral Gap:**

| Gap Area | Mainframe Behavior | Java Behavior | Severity |
|:---------|:-------------------|:-------------|:---------|
| NUMVAL-C currency handling | Handles standard COBOL currency symbols ($, CR, DB) and PICTURE editing characters | `Double.parseDouble()` does not handle currency symbols; requires pre-processing | **Low** — regex preprocessing resolves this |
| MOD semantics | COBOL MOD follows mathematical modulo (result has sign of divisor) | Java `%` is remainder (result has sign of dividend); `Math.floorMod()` matches COBOL | **Low** — use `Math.floorMod()` for exact equivalence |
| CURRENT-DATE format | Returns 21-character string: `YYYYMMDDHHMMSSNNNNNN±HHMM` (with UTC offset) | `LocalDateTime.now()` returns structured object; `ZonedDateTime` includes offset | **Low** — more flexibility in Java |
| INTEGER-OF-DATE epoch | Epoch is January 1 of year 1 in the Gregorian calendar | Java `toEpochDay()` uses January 1, 1970 | **Low** — offset constant resolves difference |

**Assessment Justification:** Rated **Low** because all 7 COBOL intrinsic functions have direct or near-direct Java standard library equivalents. The most complex mapping is NUMVAL-C (currency-aware numeric parsing), which requires a small utility method for currency symbol handling. All other functions map 1:1 to Java APIs with minimal behavioral difference.

---

## 3. Java Equivalent Mapping Table

The following consolidated table maps every proprietary utility to its recommended Java replacement library, specific class/method, and complexity rating.

| Proprietary Utility | Java Library | Specific Class/Method | Complexity | Notes |
|:--------------------|:-------------|:---------------------|:-----------|:------|
| CEE3ABD | Java Standard Library + Custom | `System.exit(code)` + custom `ApplicationAbendException` | Medium | Custom exception framework for structured dumps |
| CEEDAYS | `java.time` | `LocalDate.parse()` + `JulianFields.JULIAN_DAY` | High | Lillian day offset calculation required; custom error enum |
| CICS READ | Spring Data JPA 3.x | `JpaRepository.findById(key)` | High | Entity mapping from VSAM record layout |
| CICS WRITE | Spring Data JPA 3.x | `JpaRepository.save(entity)` | High | INSERT semantics with key generation |
| CICS REWRITE | Spring Data JPA 3.x | `JpaRepository.save(entity)` + `@Version` | High | Optimistic locking for update operations |
| CICS DELETE | Spring Data JPA 3.x | `JpaRepository.deleteById(key)` | High | Cascading delete considerations |
| CICS STARTBR | Spring JDBC 6.x | `JdbcTemplate.query()` with keyset pagination | High | Cursor position management for browse |
| CICS READNEXT | JDBC Standard | `ResultSet.next()` | High | Pagination boundary handling |
| CICS READPREV | JDBC Standard | `ResultSet.previous()` (scrollable) | High | Reverse query or scrollable cursor |
| CICS ENDBR | JDBC Standard | `ResultSet.close()` | High | Resource cleanup |
| CICS XCTL | Spring MVC 6.x | `return "forward:/path"` or `RedirectView` | Medium | URL-based routing replaces program name |
| CICS RETURN TRANSID | Spring MVC 6.x | HTTP response lifecycle | Medium | Pseudo-conversational → HTTP request/response |
| CICS LINK | Spring Framework 6.x | Service method call or `RestTemplate` | Medium | Direct method invocation |
| CICS SEND MAP | Spring MVC + Thymeleaf 3.x | `return "viewName"` + Thymeleaf template | Very High | BMS → HTML template migration |
| CICS RECEIVE MAP | Spring MVC 6.x | `@ModelAttribute` or `@RequestBody` | Very High | Form POST → model binding |
| CICS SEND TEXT | Spring MVC 6.x | `ResponseEntity<String>` | Very High | Simple text response |
| CICS ASSIGN APPLID | Spring Boot | `@Value("${spring.application.name}")` | Low | Application property |
| CICS ASSIGN SYSID | `java.net` | `InetAddress.getLocalHost().getHostName()` | Low | System property |
| CICS ASKTIME | `java.time` | `LocalDateTime.now()` | Low | Direct equivalent |
| CICS FORMATTIME | `java.time.format` | `DateTimeFormatter.ofPattern()` | Low | Format pattern mapping |
| CICS WRITEQ TD | Spring JMS 6.x + Spring Batch 5.x | `JmsTemplate.convertAndSend()` + Batch `Job` | Very High | Architectural replacement required |
| IDCAMS DEFINE CLUSTER | SQL DDL + Flyway 10.x | `CREATE TABLE` + `Flyway.migrate()` | Medium | VSAM attributes → DDL mapping |
| IDCAMS REPRO | Spring JDBC 6.x | `JdbcTemplate.batchUpdate()` | Medium | Bulk data loading |
| IDCAMS DELETE | SQL DDL | `DROP TABLE` | Medium | Schema cleanup |
| SORT | Java Standard Library | `Collections.sort()` / `Stream.sorted()` | Medium | EBCDIC collation consideration |
| IEBGENER | `java.nio.file` | `Files.copy()` | Low | Direct file copy |
| IEFBR14 | `java.nio.file` | `Files.createFile()` / `Files.delete()` | Low | File system operations |
| DFHMSD | Thymeleaf 3.x / React 18 | HTML page template / React App | Very High | Mapset → page template |
| DFHMDI | HTML5 | `<form>` element | Very High | Map → form element |
| DFHMDF | HTML5 | `<input>` / `<label>` elements | Very High | Field definition → HTML elements |
| DFHBMSCA constants | Custom Java enum | `FieldAttribute` enum + CSS classes | Medium | Attribute byte → CSS mapping |
| DFHAID constants | Custom Java enum | `UserAction` enum + event handlers | Medium | AID key → JavaScript event mapping |
| FUNCTION CURRENT-DATE | `java.time` | `LocalDateTime.now()` | Low | Direct equivalent |
| FUNCTION MOD | `java.lang.Math` | `Math.floorMod(a, b)` | Low | Use `floorMod` for COBOL parity |
| FUNCTION TEST-NUMVAL-C | Custom / `java.util.regex` | Regex validator | Low | Custom utility method |
| FUNCTION NUMVAL-C | `java.lang.Double` | `Double.parseDouble()` with preprocessing | Low | Currency symbol stripping |
| FUNCTION UPPER-CASE | `java.lang.String` | `str.toUpperCase()` | Low | Direct equivalent |
| FUNCTION TRIM | `java.lang.String` | `str.strip()` | Low | Direct equivalent (Java 11+) |
| FUNCTION INTEGER-OF-DATE | `java.time` | `LocalDate.toEpochDay()` | Low | Epoch offset adjustment |

---

## 4. Behavioral Gap Analysis

This section consolidates all utilities flagged as having no direct Java behavioral parity. These gaps represent areas where the migrated Java code will behave differently from the mainframe implementation, requiring architectural decisions and targeted validation.

### 4.1 Critical Behavioral Gaps (No Direct Java Equivalent)

#### 4.1.1 CICS TDQ — WRITEQ TD to Internal Reader

**Gap Description:** The TDQ-to-JES-internal-reader pipeline has no Java equivalent. This mainframe-native mechanism allows an online CICS program (CORPT00C) to write JCL records to a Transient Data Queue, which the JES subsystem automatically reads and submits as a batch job.

**Programs Affected:** CORPT00C

**Mainframe Behavior:**
1. Online program writes 80-byte JCL records to TDQ 'JOBS'
2. JES internal reader consumes TDQ records
3. JES interprets JCL and schedules batch job execution
4. No programmatic handshake — fire-and-forget from CICS perspective

`Source: app/cbl/CORPT00C.cbl:517-523`

**Java Behavior:** No equivalent pipeline. Requires:
- Message queue (JMS/RabbitMQ) for job request delivery
- Job scheduler/executor (Spring Batch, AWS Batch) for batch processing
- Explicit job definition (no JCL interpretation)
- Monitoring/callback mechanism for job completion

**Recommended Mitigation:** Implement a `BatchJobSubmissionService` that accepts structured job parameters (not JCL text), publishes them to a message queue, and has a consumer that triggers Spring Batch job execution with those parameters.

---

#### 4.1.2 BMS Map Macros — 3270 Coordinate-Based Screen Definition

**Gap Description:** The BMS macro model defines screens using absolute character coordinates on a fixed 24×80 grid. No HTML/CSS framework provides equivalent coordinate-based layout with single-byte attribute control.

**Programs Affected:** All 16 CICS programs + 17 BMS map sources

**Mainframe Behavior:**
- Fields positioned at exact (row, column) coordinates
- Single attribute byte controls color, intensity, and protection simultaneously
- Modified Data Tag (MDT) tracks per-field user modifications
- Physical terminal keys (PF1-PF24) provide navigation context

`Source: app/bms/*.bms (17 files)`

**Java Behavior:**
- HTML/CSS uses flow-based layout with responsive design
- Multiple CSS properties and HTML attributes needed per field
- JavaScript change events track modifications
- Browser keyboard shortcuts or buttons replace PF keys

**Recommended Mitigation:** Create a `ScreenDefinitionService` that maps BMS field definitions to HTML form templates, preserving field names, validation rules, and relative positioning. Accept that exact pixel-level fidelity to the 24×80 grid is not a goal — functional equivalence of user workflows is the success criterion.

---

#### 4.1.3 CEEDAYS Feedback Codes — Granular Error Reporting

**Gap Description:** CEEDAYS FEEDBACK-CODE provides 8 distinct error conditions with specific hex-encoded severity and message values. Java's `DateTimeParseException` provides a single exception type with a text message, offering significantly less granular error classification.

**Programs Affected:** CSUTLDTC (wrapper), CORPT00C, COTRN02C (consumers via CSUTLDPY)

**Mainframe Behavior:**
- 8 distinct 88-level conditions with hex values
- Severity code (S9(4) BINARY) indicates error class
- Message number (S9(4) BINARY) identifies specific error type
- Consumer programs can branch on individual error conditions

`Source: app/cbl/CSUTLDTC.cbl:62-70`

**Java Behavior:**
- `DateTimeParseException` with index position and message text
- No built-in error classification enum
- Single exception type for all parse failures

**Recommended Mitigation:** Implement a `DateValidationResult` enum with 9 values (8 error conditions + SUCCESS) that maps to the CEEDAYS FEEDBACK-CODE conditions. The `DateValidationService.validate()` method returns this enum, allowing consuming code to branch on specific error types just as the COBOL code does.

---

### 4.2 Significant Behavioral Gaps (Semantic Differences)

#### 4.2.1 VSAM Browse — STARTBR/READNEXT/READPREV Cursor Semantics

**Gap Description:** VSAM browse operations maintain a server-side cursor that persists within the CICS task and can survive across pseudo-conversational boundaries (when the browse is held). SQL cursors are connection-scoped and do not natively persist across HTTP requests.

**Programs Affected:** COACTVWC, COCRDLIC, COTRN00C, COUSR01C (browse-intensive programs)

**Mainframe Behavior:**
- STARTBR establishes cursor at a specific key position
- READNEXT/READPREV move cursor forward/backward
- Cursor position survives between SEND MAP and RECEIVE MAP cycles (within a held browse)
- ENDBR explicitly releases cursor

**Java Behavior:**
- SQL `ResultSet` cursor is connection-bound
- HTTP stateless model requires re-query on each request
- Pagination via `LIMIT/OFFSET` or keyset pagination
- No automatic cursor persistence across requests

**Recommended Mitigation:** Implement keyset pagination using the last-retrieved key value stored in HTTP session. For READPREV functionality, use a reverse-ordered query with the first key from the current page. This approach provides functionally equivalent browsing without server-side cursor management.

---

#### 4.2.2 COMMAREA State Management — Byte-Level Layout

**Gap Description:** The CICS COMMAREA (COCOM01Y.cpy) is a 1024-byte fixed-layout binary structure used for inter-program communication. HTTP session attributes are typed Java objects with no byte-level packing.

**Programs Affected:** All 16 CICS programs

**Mainframe Behavior:**
- Fixed 1024-byte layout with positional field access
- Binary packed fields (PIC S9(n) COMP)
- Fields accessed by byte position and length
- COMMAREA is the sole state transfer mechanism between programs

`Source: app/cpy/COCOM01Y.cpy:19-47`

**Java Behavior:**
- `HttpSession` attributes are named Java objects
- No byte-level packing or positional access
- Session can hold arbitrary object types
- Multiple state transfer mechanisms available (session, cookies, URL parameters)

**Recommended Mitigation:** Create a `CardDemoSession` POJO (Plain Old Java Object) mirroring the COMMAREA fields as typed Java properties. Store this object in the HTTP session. All controller methods access session state through this object, preserving the centralized state management pattern while gaining type safety.

---

### 4.3 Minor Behavioral Gaps (Low Impact)

| Gap | Mainframe | Java | Impact |
|:----|:----------|:-----|:-------|
| EBCDIC collation in SORT | EBCDIC byte ordering for character sort keys | Unicode (UTF-16) ordering | Low — CardDemo uses primarily numeric sort keys |
| CEE3ABD dump format | LE CEEDUMP with register contents and storage areas | Java stack trace with thread info | Low — functionally equivalent diagnostics |
| IEBGENER character encoding | EBCDIC byte copy | UTF-8 I/O | Low — one-time encoding conversion during data migration |
| IEFBR14 catalog semantics | JCL DD allocation manages VSAM catalog entries | Java manages files directly | Low — simplified in Java |
| NUMVAL-C currency symbols | Handles COBOL PICTURE editing characters ($, CR, DB) | Requires preprocessing to strip symbols | Low — small utility method |
| INTEGER-OF-DATE epoch | Days since January 1, year 1 | `toEpochDay()` uses January 1, 1970 epoch | Low — constant offset |

---

## 5. Impact Heat Map

The following table provides an at-a-glance view of all proprietary utilities plotted by complexity and program impact.

| Utility | Complexity | Programs Affected | Behavioral Gap Severity | Migration Priority |
|:--------|:-----------|:-----------------|:-----------------------|:-------------------|
| **CICS Terminal Control** | 🔴 Very High | 16 programs + 17 BMS maps | Very High | **P1 — Critical Path** |
| **BMS Map Macros** | 🔴 Very High | 17 BMS sources → 16 programs | Very High | **P1 — Critical Path** |
| **CICS TDQ (WRITEQ TD)** | 🔴 Very High | 1 program (CORPT00C) | Very High | **P2 — Architectural** |
| **CICS File Control** | 🟠 High | 13 programs | High | **P1 — Critical Path** |
| **CEEDAYS/CSUTLDTC** | 🟠 High | 1 wrapper + 2 consumers | Medium | **P2 — Targeted** |
| **CEE3ABD** | 🟡 Medium | 9 batch programs | Low | **P3 — Standard** |
| **CICS Program Control** | 🟡 Medium | 16 programs | Medium | **P2 — Architectural** |
| **IDCAMS** | 🟡 Medium | JCL jobs / 10+ datasets | Medium | **P2 — Data Layer** |
| **SORT** | 🟡 Medium | 1 batch job | Medium | **P3 — Standard** |
| **DFHBMSCA/DFHAID** | 🟡 Medium | 16+ programs | Medium | **P2 — Coupled with Terminal Control** |
| **CICS System Services** | 🟢 Low | 2 programs | Minimal | **P4 — Low Priority** |
| **IEBGENER** | 🟢 Low | 1 batch job | Low | **P4 — Low Priority** |
| **IEFBR14** | 🟢 Low | 2 batch jobs | Low | **P4 — Low Priority** |
| **COBOL Intrinsic Functions** | 🟢 Low | 6+ programs | Minimal | **P4 — Low Priority** |

### Legend

| Symbol | Meaning |
|:-------|:--------|
| 🔴 | Very High complexity — architectural redesign required |
| 🟠 | High complexity — multi-library integration with significant validation |
| 🟡 | Medium complexity — Java equivalents exist with moderate adaptation |
| 🟢 | Low complexity — direct Java standard library replacement |

### Migration Priority Definitions

| Priority | Description |
|:---------|:------------|
| **P1 — Critical Path** | Must be completed first; blocks other migration work. Includes the presentation layer (BMS/Terminal Control) and data access layer (File Control). |
| **P2 — Architectural** | Requires architectural decisions that affect multiple components. Includes program routing, state management, job submission, and data schema design. |
| **P3 — Standard** | Straightforward migration with known patterns. Includes batch program error handling and file sorting. |
| **P4 — Low Priority** | Direct library replacement with minimal risk. Can be completed in parallel with other work. |

---

## 6. Cross-Reference Summary

### 6.1 Related Documents

| Document | Relationship |
|:---------|:-------------|
| [01 — Proprietary Utility Inventory](01-proprietary-utility-inventory.md) | Provides the foundational catalog of all utilities assessed in this document |
| [03 — Migration Strategy](03-migration-strategy.md) | Provides detailed migration playbooks for each utility assessed here |
| [04 — Risk Assessment](04-risk-assessment.md) | Provides risk classification using the complexity scores from this document |
| [05 — Testing & Validation Framework](05-testing-validation-framework.md) | Provides test cases addressing the behavioral gaps identified here |
| [Utility Dependency Map](diagrams/utility-dependency-map.md) | Visual representation of program-to-utility dependencies |
| [CICS Command Flow](diagrams/cics-command-flow.md) | Visual migration path for CICS API commands |
| [VSAM-to-RDBMS Mapping](diagrams/vsam-to-rdbms-mapping.md) | Visual data layer transformation for File Control migration |

### 6.2 Source Citation Index

All source citations used in this document reference files in the CardDemo repository:

| Citation | File | Content Referenced |
|:---------|:-----|:-------------------|
| `app/cbl/CBACT01C.cbl:169-173` | Batch account reader | CEE3ABD abend pattern |
| `app/cbl/CBACT04C.cbl:614` | Interest calculator | FUNCTION CURRENT-DATE usage |
| `app/cbl/CBACT04C.cbl:628-632` | Interest calculator | CEE3ABD abend pattern |
| `app/cbl/CSUTLDTC.cbl:62-70` | Date utility wrapper | CEEDAYS FEEDBACK-CODE definitions |
| `app/cbl/CSUTLDTC.cbl:116-120` | Date utility wrapper | CEEDAYS CALL statement |
| `app/cbl/CSUTLDTC.cbl:128-149` | Date utility wrapper | FEEDBACK-CODE evaluation |
| `app/cbl/CORPT00C.cbl:81-86` | Report generator | JCL JOB-DATA structure |
| `app/cbl/CORPT00C.cbl:517-523` | Report generator | WRITEQ TD to TDQ 'JOBS' |
| `app/cbl/CORPT00C.cbl:525-535` | Report generator | TDQ error handling |
| `app/cbl/COSGN00C.cbl:198-204` | Sign-on program | ASSIGN APPLID/SYSID |
| `app/cbl/COBIL00C.cbl:251-261` | Bill payment program | ASKTIME/FORMATTIME |
| `app/cbl/COMEN01C.cbl:107-110` | Main menu | RETURN TRANSID |
| `app/cbl/COMEN01C.cbl:152-155` | Main menu | XCTL dynamic routing |
| `app/cbl/COMEN01C.cbl:189-194` | Main menu | SEND MAP |
| `app/cbl/COACTUPC.cbl:1078` | Account update | TEST-NUMVAL-C usage |
| `app/cbl/COACTUPC.cbl:1080` | Account update | NUMVAL-C usage |
| `app/cbl/COACTUPC.cbl:1685` | Account update | UPPER-CASE usage |
| `app/cbl/COACTUPC.cbl:1698` | Account update | TRIM usage |
| `app/cbl/COACTUPC.cbl:3654-3662` | Account update | CICS READ pattern |
| `app/cpy/COCOM01Y.cpy:19-47` | COMMAREA copybook | Communication area layout |
| `app/cpy/CSSETATY.cpy:17-27` | Attribute setter | DFHBMSCA usage pattern |
| `app/cpy/CSSTRPFY.cpy:17-78` | PF key mapper | DFHAID key mapping |
| `app/cpy/CSUTLDPY.cpy:18-60` | Date validation paragraphs | CSUTLDTC consumption pattern |
| `app/catlg/LISTCAT.txt` | VSAM catalog snapshot | VSAM cluster definitions |
| `app/bms/*.bms` | BMS map sources (17 files) | Screen contract definitions |
| `README.md` | Project documentation | Batch job descriptions (COMBTRAN, DUSRSECJ, CLOSEFIL, OPENFIL) |

---

*This document is part of the AWS CardDemo Migration Analysis. For the complete analysis, start with the [Executive Summary](00-executive-summary.md).*
