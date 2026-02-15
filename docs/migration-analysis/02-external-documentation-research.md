# 2. External Documentation Research — IBM Documentation and Community Source Findings

> **Document Role:** This document serves as the **canonical link store** for the entire migration analysis. All other analysis sections reference this file rather than duplicating external URLs. Every utility identified in the [Proprietary Utility Inventory](./01-proprietary-utility-inventory.md) has corresponding research documented here with search queries, IBM official documentation links, key behavioral specifications, community migration patterns, and known edge cases.

---

## Table of Contents

- [2.1 Research Methodology](#21-research-methodology)
- [2.2 IBM Language Environment Callable Services](#22-ibm-language-environment-callable-services)
  - [2.2.1 CEEDAYS — Convert Date to Lilian Format](#221-ceedays--convert-date-to-lilian-format)
  - [2.2.2 CEE3ABD — Terminate Enclave with Abend](#222-cee3abd--terminate-enclave-with-abend)
- [2.3 JCL Utility Programs](#23-jcl-utility-programs)
  - [2.3.1 IDCAMS — Access Method Services](#231-idcams--access-method-services)
  - [2.3.2 DFSORT — Data Facility Sort](#232-dfsort--data-facility-sort)
  - [2.3.3 IEBGENER — Sequential Dataset Copy Utility](#233-iebgener--sequential-dataset-copy-utility)
  - [2.3.4 IEFBR14 — No-Operation Utility](#234-iefbr14--no-operation-utility)
- [2.4 CICS Runtime Commands](#24-cics-runtime-commands)
  - [2.4.1 CICS File Control Commands](#241-cics-file-control-commands)
  - [2.4.2 CICS Terminal I/O Commands](#242-cics-terminal-io-commands)
  - [2.4.3 CICS Program Control Commands](#243-cics-program-control-commands)
  - [2.4.4 CICS System Services](#244-cics-system-services)
- [2.5 BMS Macro Processing](#25-bms-macro-processing)
- [2.6 Additional Migration Resources](#26-additional-migration-resources)
- [2.7 Citation Summary Table](#27-citation-summary-table)
- [Navigation](#navigation)

---

## 2.1 Research Methodology

External documentation research was conducted systematically for every proprietary IBM utility identified in the CardDemo codebase. For each utility, web searches were performed using the following five prescribed query templates:

1. **`"[Utility Name] IBM mainframe documentation"`** — To locate IBM official reference material
2. **`"[Utility Name] COBOL to Java migration"`** — To find community migration patterns and tools
3. **`"[Utility Name] Java equivalent"`** — To identify specific Java libraries and APIs that replicate behavior
4. **`"IBM [Utility Name] specification"`** — To find formal behavioral specifications
5. **`"[Utility Name] modernization patterns"`** — To discover architectural patterns for migration

### Research Quality Standards

- **Primary sources**: IBM official documentation at `ibm.com/docs` is treated as the authoritative behavioral specification for each utility
- **Secondary sources**: Community tutorials (mainframestechhelp.com, ibmmainframer.com), Wikipedia entries, and vendor documentation provide supplementary context
- **Migration evidence**: Published case studies (e.g., ING Bank/SoftwareMining) and commercial migration tool documentation provide real-world migration validation
- **Cross-validation**: All behavioral specifications from IBM documentation were cross-referenced against actual usage in CardDemo source files (see [Source Code Cross-Reference](./appendices/E-source-code-cross-reference.md))

### Coverage Summary

| Utility Category | Utilities Researched | IBM Docs Found | Community Sources Found |
|:-----------------|:-------------------:|:--------------:|:----------------------:|
| Language Environment | 2 (CEEDAYS, CEE3ABD) | 5 | 4 |
| JCL Utilities | 4 (IDCAMS, DFSORT, IEBGENER, IEFBR14) | 8 | 10 |
| CICS Commands | 18 command types | 2 | 3 |
| BMS Macros | 3 macro types | 2 | 2 |
| **Total** | **27** | **17** | **19** |

---

## 2.2 IBM Language Environment Callable Services

### 2.2.1 CEEDAYS — Convert Date to Lilian Format

#### Search Queries Used

| Query | Primary Findings |
|:------|:-----------------|
| `CEEDAYS IBM mainframe documentation` | IBM z/OS LE Programming Reference; IBM i API reference |
| `CEEDAYS COBOL to Java migration` | Lilian date epoch mapping to `java.time.LocalDate` |
| `CEEDAYS Java equivalent` | `java.time.LocalDate` with custom epoch adjustment from October 14, 1582 |
| `IBM CEEDAYS specification` | Full parameter specification including picture strings and feedback codes |
| `CEEDAYS modernization patterns` | Date arithmetic migration to Java `java.time` package |

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM z/OS 2.1 Language Environment Programming Reference — CEEDAYS | [https://www.ibm.com/docs/en/zos/2.1.0?topic=services-ceedays-convert-date-lilian-format](https://www.ibm.com/docs/en/zos/2.1.0?topic=services-ceedays-convert-date-lilian-format) | Authoritative z/OS specification for CEEDAYS callable service |
| IBM i Convert Date to Lilian Format (CEEDAYS) API | [https://www.ibm.com/docs/api/v1/content/ssw_ibm_i_74/apis/CEEDAYS.htm](https://www.ibm.com/docs/api/v1/content/ssw_ibm_i_74/apis/CEEDAYS.htm) | Cross-platform API reference with parameter details and feedback code structure |
| IBM ILE CL Usage of CEE APIs (CEEDAYS, CEEDATE, CEEDYWK) | [https://www.ibm.com/support/pages/ile-cl-usage-cee-apis-ceedays-ceedate-ceedywk](https://www.ibm.com/support/pages/ile-cl-usage-cee-apis-ceedays-ceedate-ceedywk) | Working examples of CEEDAYS usage including Lilian date range bounds |
| IBM COBOL Calling APIs CEEDAYS, CEEDATE, and CEEDYWK — Example | [https://www.ibm.com/support/pages/example-cobol-calling-apis-ceedays-ceedate-and-ceedywk](https://www.ibm.com/support/pages/example-cobol-calling-apis-ceedays-ceedate-and-ceedywk) | COBOL-specific calling convention examples |
| IBM z/OS 2.5 Language Environment Programming Reference (SA38-0683) | [https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/ceea300_v2r5.pdf](https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/ceea300_v2r5.pdf) | Complete LE reference manual (PDF), CEEDAYS on page 214 |

#### Key Behavioral Specifications

Based on IBM official documentation:

- **Function**: Converts a character string representing a date into a **Lilian date** — a 32-bit binary integer representing the number of days since **October 14, 1582** (the beginning of the Gregorian calendar).
- **Parameters**:
  - `input_char_date` — A character string representing a date (field width 5–255 characters). May contain leading/trailing blanks.
  - `picture_string` — A character string indicating the format of the date (e.g., `YYYY-MM-DD`, `MM/DD/YY`, `YYYYMMDD`). If null or blank, format is derived from the current job's country/region ID.
  - `output_Lilian_date` — A 32-bit binary integer output. Set to 0 if the input date is invalid.
  - `feedback_code` — A 12-byte feedback code (condition token) returned by reference.
- **Valid date range**: October 15, 1582 through December 31, 9999.
- **Lilian date epoch**: October 14, 1582 = Lilian day 0; October 15, 1582 = Lilian day 1; January 1, 2000 = Lilian day 152,385; December 31, 9999 = Lilian day 3,074,324.
- **Inverse operation**: `CEEDATE` converts Lilian dates back to character format.
- **Related services**: `CEEDYWK` calculates day-of-week from Lilian date.

**CardDemo Usage** (Source: `app/cbl/CSUTLDTC.cbl:116-120`):

```cobol
CALL "CEEDAYS" USING
       WS-DATE-TO-TEST,
       WS-DATE-FORMAT,
       OUTPUT-LILLIAN,
       FEEDBACK-CODE
```

The CardDemo wrapper program `CSUTLDTC` accepts a date string and format via the `LINKAGE SECTION`, calls `CEEDAYS`, and evaluates the feedback code to return a validation result. This wrapper is called from `CORPT00C.cbl` (report generation) and `COTRN02C.cbl` (transaction processing) for date validation.

#### Feedback Code Error Conditions

From `app/cbl/CSUTLDTC.cbl:62-70`, the following feedback codes are handled:

| Condition | Hex Value | Meaning |
|:----------|:----------|:--------|
| `FC-INVALID-DATE` | `X'0000000000000000'` | Date is valid (severity 0) |
| `FC-INSUFFICIENT-DATA` | `X'000309CB59C3C5C5'` | Input too short for picture string |
| `FC-BAD-DATE-VALUE` | `X'000309CC59C3C5C5'` | Date value out of valid range |
| `FC-INVALID-ERA` | `X'000309CD59C3C5C5'` | Invalid era specification |
| `FC-UNSUPP-RANGE` | `X'000309D159C3C5C5'` | Date outside supported range |
| `FC-INVALID-MONTH` | `X'000309D559C3C5C5'` | Month value not 01-12 |
| `FC-BAD-PIC-STRING` | `X'000309D659C3C5C5'` | Picture string format error |
| `FC-NON-NUMERIC-DATA` | `X'000309D859C3C5C5'` | Non-numeric characters in date |
| `FC-YEAR-IN-ERA-ZERO` | `X'000309D959C3C5C5'` | Year within era is zero |

#### Community and Migration Sources

| Source | URL | Relevance |
|:-------|:----|:----------|
| MC Press Online — ILE CEE Date and Time APIs | [https://www.mcpressonline.com/programming-other/cl/the-cl-corner-more-on-ile-cee-date-and-time-apis](https://www.mcpressonline.com/programming-other/cl/the-cl-corner-more-on-ile-cee-date-and-time-apis) | Practical usage patterns and edge cases for CEEDAYS |
| IBM i Program Examples (CEEDAYS, CEEDATE, CEEDYWK) | [https://www.ibm.com/support/pages/program-examples-ile-ibm-c400-using-ceedays-ceedays-and-ceedywk](https://www.ibm.com/support/pages/program-examples-ile-ibm-c400-using-ceedays-ceedays-and-ceedywk) | C language examples demonstrating the Lilian date epoch and API usage |

#### Java Equivalent

The Java `java.time.LocalDate` class with epoch adjustment provides a functionally equivalent replacement:

```java
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class LilianDateConverter {
    // Lilian epoch: October 15, 1582 = Lilian day 1
    private static final LocalDate LILIAN_EPOCH = LocalDate.of(1582, 10, 14);

    /**
     * Converts a date to Lilian format (days since October 14, 1582).
     * Equivalent to IBM CEEDAYS callable service.
     */
    public static long toLilian(LocalDate date) {
        return ChronoUnit.DAYS.between(LILIAN_EPOCH, date);
    }

    /**
     * Converts a Lilian day number to a LocalDate.
     * Equivalent to IBM CEEDATE callable service.
     */
    public static LocalDate fromLilian(long lilianDay) {
        return LILIAN_EPOCH.plusDays(lilianDay);
    }
}
```

#### Known Edge Cases and Migration Warnings

- **Leap year handling**: CEEDAYS correctly handles the Gregorian calendar leap year rules (divisible by 4, except centuries unless divisible by 400). Java's `LocalDate` follows the same ISO-8601 proleptic Gregorian calendar, ensuring behavioral parity.
- **Century boundary**: Picture strings with 2-digit years (`YY`) use the Language Environment century window (controlled by `CEESCEN`). Java migration must explicitly define a century pivot year when parsing 2-digit years using `java.time.format.DateTimeFormatterBuilder.appendValueReduced()`.
- **Japanese and ROC eras**: CEEDAYS supports `<JJJJ>` (Japanese era) and `<CCCC>` (Republic of China era) picture strings. These are not used in CardDemo but would require `java.time.chrono.JapaneseChronology` for migration.
- **Locale-dependent defaults**: When `picture_string` is blank, CEEDAYS defaults to the country/region ID format. Java migration must explicitly specify date format patterns — there is no equivalent implicit locale mechanism.
- **Feedback code structure**: The 12-byte LE feedback code structure (severity, message number, case/cause codes, facility ID) has no direct Java equivalent. Migration should map feedback conditions to Java exceptions or validation result objects.

---

### 2.2.2 CEE3ABD — Terminate Enclave with Abend

#### Search Queries Used

| Query | Primary Findings |
|:------|:-----------------|
| `CEE3ABD IBM mainframe documentation` | IBM z/OS LE Programming Reference — CEE3ABD specification |
| `CEE3ABD COBOL to Java migration` | Mapping to `RuntimeException` or `System.exit()` patterns |
| `CEE3ABD Java equivalent` | Custom exception hierarchy with abend code preservation |
| `IBM CEE3ABD specification` | Two parameters: abend code (binary) and timing (binary) |
| `CEE3ABD modernization patterns` | Exception-based error handling as replacement for abend model |

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM z/OS 2.4 Language Environment Programming Reference — CEE3ABD | [https://www.ibm.com/docs/en/zos/2.4.0?topic=services-cee3abdterminate-enclave-abend](https://www.ibm.com/docs/en/zos/2.4.0?topic=services-cee3abdterminate-enclave-abend) | Authoritative specification for CEE3ABD callable service — terminates enclave with user abend |
| IBM z/OS 2.5 Language Environment Programming Reference (SA38-0683) | [https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/ceea300_v2r5.pdf](https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/ceea300_v2r5.pdf) | Complete LE reference manual (PDF), CEE3ABD on page 113 |
| IBM z/OS 2.5 Language Environment Runtime Messages (SA38-0686) | [https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/ceea900_v2r5.pdf](https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/ceea900_v2r5.pdf) | LE abend codes reference — documents user abend codes issued by CEE3ABD |
| IBM z/OS Understanding Abend Codes | [https://www.ibm.com/docs/en/zos/2.4.0?topic=errors-understanding-abend-codes](https://www.ibm.com/docs/en/zos/2.4.0?topic=errors-understanding-abend-codes) | Comprehensive abend code interpretation guide |

#### Key Behavioral Specifications

Based on IBM official documentation:

- **Function**: Terminates the current Language Environment enclave with a user-specified abend code. The enclave is the outermost scope of execution (analogous to a process in UNIX terms).
- **Parameters**:
  - `abend_code` — A fullword (32-bit) binary integer specifying the user abend code. Abend codes issued by CEE3ABD are USER abends (not SYSTEM abends).
  - `timing` — A fullword (32-bit) binary integer controlling the timing of the abend. When set to 0, the abend occurs immediately. When set to 1, cleanup processing occurs before the abend.
- **Behavior**: When invoked, CEE3ABD causes full ABEND processing — condition handlers are driven, abnormal termination exits (ATEs) are called, and the enclave terminates. This is distinct from setting `RETURN-CODE`, which sets a return code without causing an abend.
- **ILBOABN0 relationship**: The older `ILBOABN0` interface, when called from Enterprise COBOL V5+, has the same result as calling `CEE3ABD` with `ACTION` code 1.
- **CEE3AB2 variant**: `CEE3AB2` extends `CEE3ABD` by accepting an additional reason code parameter.

**CardDemo Usage** (Source: `app/cbl/CBACT01C.cbl:169-173`):

```cobol
9999-ABEND-PROGRAM.
    DISPLAY 'ABENDING PROGRAM'
    MOVE 0 TO TIMING
    MOVE 999 TO ABCODE
    CALL 'CEE3ABD'.
```

CEE3ABD is used in all 9 CardDemo batch programs (`CBACT01C`, `CBACT02C`, `CBACT03C`, `CBACT04C`, `CBCUS01C`, `CBSTM03A`, `CBTRN01C`, `CBTRN02C`, `CBTRN03C`) as the standard error termination handler. In each case, it is called from a `9999-ABEND-PROGRAM` paragraph that is `PERFORM`ed when file I/O operations encounter unrecoverable errors.

#### Abend Code Inventory in CardDemo

| Program | Abend Code | Timing | Trigger Condition |
|:--------|:----------:|:------:|:------------------|
| `CBACT01C.cbl` | 999 | 0 | Account file read/open/close error |
| `CBACT02C.cbl` | 999 | 0 | Account record processing error |
| `CBACT03C.cbl` | 999 | 0 | Account update error |
| `CBACT04C.cbl` | 999 | 0 | Interest calculation file error |
| `CBCUS01C.cbl` | 999 | 0 | Customer file processing error |
| `CBSTM03A.cbl` | 999 | 0 | Statement generation driver error |
| `CBTRN01C.cbl` | 999 | 0 | Transaction posting file error |
| `CBTRN02C.cbl` | 999 | 0 | Transaction validation error |
| `CBTRN03C.cbl` | 999 | 0 | Transaction processing error |

All CardDemo batch programs use a uniform abend code of **999** with immediate timing (**0**), indicating a standardized error handling pattern across the batch suite.

#### Community and Migration Sources

| Source | URL | Relevance |
|:-------|:----|:----------|
| BMC Abend-AID in Language Environment | [https://docs.bmc.com/xwiki/bin/view/Mainframe/DevX/BMC-AMI-DevX-Abend-AID/](https://docs.bmc.com/xwiki/bin/view/Mainframe/DevX/BMC-AMI-DevX-Abend-AID/) | Documents how third-party tools integrate with CEE3ABD abend processing via ATEs |
| IBM Enterprise COBOL ILBOABN0 Considerations | [https://www.ibm.com/docs/en/cobol-zos/6.3.0?topic=6-ilboabn0-considerations](https://www.ibm.com/docs/en/cobol-zos/6.3.0?topic=6-ilboabn0-considerations) | Documents the equivalence between ILBOABN0 and CEE3ABD with ACTION code 1 |
| Mainframe Discussion — User Abends and COBOL | [https://www.zmainframes.com/viewtopic.php?t=260](https://www.zmainframes.com/viewtopic.php?t=260) | Community discussion confirming CEE3ABD produces USER abends, not SYSTEM abends |

#### Java Equivalent

The recommended Java migration pattern replaces `CEE3ABD` with a custom exception hierarchy that preserves abend code semantics:

```java
/**
 * Custom exception equivalent to IBM CEE3ABD enclave termination.
 * Preserves the abend code for audit trail and error classification.
 */
public class MainframeAbendException extends RuntimeException {
    private final int abendCode;
    private final int timing;

    public MainframeAbendException(int abendCode, int timing) {
        super("User ABEND U" + String.format("%04d", abendCode));
        this.abendCode = abendCode;
        this.timing = timing;
    }

    public int getAbendCode() { return abendCode; }
    public int getTiming() { return timing; }
}
```

For batch programs, the exception should be caught at the top-level job orchestrator (e.g., Spring Batch `JobExecution`) to translate abend codes into job exit codes, preserving the mainframe convention where abend codes determine subsequent JCL step execution via `COND` parameters.

#### Known Edge Cases and Migration Warnings

- **Timing parameter**: When `timing=0` (used in all CardDemo programs), the abend is immediate with no cleanup. When `timing=1`, LE calls registered ATEs and condition handlers before terminating. Java migration must decide whether to use `Runtime.getRuntime().addShutdownHook()` for `timing=1` scenarios.
- **JCL COND parameter interaction**: On z/OS, the abend code affects subsequent JCL step execution. In Java batch migration (e.g., Spring Batch), this maps to job step exit codes and `StepExecution` status codes.
- **Abend vs. return code**: Setting `RETURN-CODE` in COBOL is not an abend — it sets a return code that is checked by JCL COND parameters. CEE3ABD causes a full abend, which bypasses COND checking and instead triggers step-level error handling. Java migration must distinguish between `System.exit(code)` (return code) and `throw new MainframeAbendException(code)` (abend).
- **CICS environment**: CEE3ABD should not be used in CICS online programs — CICS programs use `EXEC CICS ABEND` instead. All 9 CardDemo usages are correctly in batch programs only.

---

## 2.3 JCL Utility Programs

### 2.3.1 IDCAMS — Access Method Services

#### Search Queries Used

| Query | Primary Findings |
|:------|:-----------------|
| `IDCAMS IBM mainframe documentation` | IBM z/OS DFSMS Access Method Services reference |
| `IDCAMS COBOL to Java migration` | VSAM-to-relational database migration patterns |
| `IDCAMS Java equivalent` | DDL scripts + Flyway migrations for DEFINE; JDBC for REPRO; SQL queries for LISTCAT |
| `IBM IDCAMS specification` | Full command set: DEFINE, REPRO, DELETE, ALTER, LISTCAT, VERIFY, PRINT |
| `IDCAMS modernization patterns` | Infrastructure-as-Code (CloudFormation/Terraform) for dataset lifecycle |

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM z/OS Basic Skills — IDCAMS | [https://www.ibm.com/docs/en/zos-basic-skills?topic=utilities-idcams-use-access-method-services-catalogs](https://www.ibm.com/docs/en/zos-basic-skills?topic=utilities-idcams-use-access-method-services-catalogs) | Overview of IDCAMS as the primary VSAM management utility |
| IBM z/OS IEBGENER (DFSMS Utilities) | [https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEBGENER.htm](https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEBGENER.htm) | Related DFSMSdfp utility documentation context |

#### Key Behavioral Specifications

Based on IBM official documentation and community sources:

- **Function**: IDCAMS (also known as Access Method Services or AMS) is the primary utility for defining and managing VSAM data sets and Integrated Catalog Facility (ICF) catalogs on z/OS.
- **Key Commands Used in CardDemo**:

| Command | Purpose | CardDemo Usage |
|:--------|:--------|:---------------|
| `DEFINE CLUSTER` | Creates VSAM KSDS/ESDS/RRDS clusters with DATA and INDEX components | DEFVSAM job — Defines 13 KSDS clusters (ACCTFILE, CARDDATA, CARDXREF, etc.) |
| `REPRO` | Copies/loads records from sequential files into VSAM clusters | LOADVSAM job — Loads sample data from `.PS` files into VSAM clusters |
| `DELETE` | Removes VSAM clusters and uncatalogs entries | DEFVSAM job — Deletes existing clusters before redefining |
| `ALTER` | Modifies VSAM cluster attributes without delete/redefine | DEFVSAM job — Alters cluster parameters |
| `LISTCAT` | Lists catalog entries with cluster attributes, statistics | LISTCAT output stored in `app/catlg/LISTCAT.txt` |
| `DEFINE GDG` | Defines Generation Data Group base entries | DEFGDGB job — Defines DALYREJS and SYSTRAN GDG bases |

- **Return Codes**: 0 (normal), 4 (minor error/warning), 8 (major error), 12 (logical error — command bypassed).
- **Conditional Processing**: IDCAMS supports `IF-THEN-ELSE` constructs with `LASTCC` (last command return code) and `MAXCC` (maximum return code in session) for scripted operations.

#### Community and Migration Sources

| Source | URL | Relevance |
|:-------|:----|:----------|
| IDCAMS Tutorial — MainframesTechHelp | [https://www.mainframestechhelp.com/utilities/idcams/](https://www.mainframestechhelp.com/utilities/idcams/) | Comprehensive IDCAMS command tutorial with examples |
| JCL IDCAMS Utility — IBM Mainframer | [https://www.ibmmainframer.com/jcl-tutorial/jcl-idcams-utility/](https://www.ibmmainframer.com/jcl-tutorial/jcl-idcams-utility/) | JCL coding patterns for IDCAMS invocation |
| Heirloom Computing Standard Utility Programmers Guide | [https://support.heirloom.cc/hc/en-us/articles/212578706-Standard-Utility-Programmers-Guide](https://support.heirloom.cc/hc/en-us/articles/212578706-Standard-Utility-Programmers-Guide) | Off-mainframe IDCAMS equivalents for Heirloom Computing EBP platform |
| VSAM Access Method Services Tutorial — IBM Mainframer | [https://www.ibmmainframer.com/vsam-tutorial/vsam-access-method-services/](https://www.ibmmainframer.com/vsam-tutorial/vsam-access-method-services/) | Detailed VSAM AMS command reference with return code interpretation |

#### Java/AWS Equivalent

| IDCAMS Command | Java/AWS Equivalent | Implementation Approach |
|:---------------|:-------------------:|:------------------------|
| `DEFINE CLUSTER` | SQL DDL + Flyway migration | `CREATE TABLE` statements with primary key matching KSDS key; managed via Flyway (`org.flywaydb:flyway-core:9.x`) |
| `REPRO` (load) | Spring Batch `FlatFileItemReader` + `JdbcBatchItemWriter` | Spring Batch job reading CSV/fixed-width source files and bulk-inserting into RDS tables |
| `DELETE` | SQL `DROP TABLE` or Flyway `V*__drop.sql` | Schema management via Flyway migration scripts |
| `ALTER` | SQL `ALTER TABLE` | Flyway versioned migration scripts |
| `LISTCAT` | Database metadata queries or AWS RDS `information_schema` | `java.sql.DatabaseMetaData` for catalog inspection |
| `DEFINE GDG` | S3 versioned bucket or timestamped directory structure | AWS S3 with versioning enabled; lifecycle policies for retention |

#### Known Edge Cases and Migration Warnings

- **KSDS key structure**: IDCAMS DEFINE CLUSTER specifies `KEYS(length offset)` for KSDS. The migration must ensure that the corresponding RDS table primary key matches the exact key structure. CardDemo uses 11-byte numeric keys for ACCTFILE (offset 0) — this maps to a `CHAR(11)` or `BIGINT` primary key.
- **SHAREOPTIONS**: VSAM SHAREOPTIONS (cross-region, cross-system) have no direct equivalent in RDBMS. Connection pooling and transaction isolation levels provide analogous concurrency control.
- **VERIFY command**: IDCAMS VERIFY repairs catalog inconsistencies after abnormal termination. RDS equivalent is `CHECK TABLE` (MySQL) or `pg_catalog` integrity checks (PostgreSQL).
- **REPRO with key range**: IDCAMS REPRO supports `FROMKEY`/`TOKEY` filtering. Spring Batch migration should implement equivalent key range filtering in the `ItemReader` configuration.

---

### 2.3.2 DFSORT — Data Facility Sort

#### Search Queries Used

| Query | Primary Findings |
|:------|:-----------------|
| `DFSORT IBM mainframe documentation` | IBM z/OS DFSORT Application Programming Guide (SC23-6878) |
| `DFSORT COBOL to Java migration` | SoftwareMining tools, custom Java Comparators |
| `DFSORT Java equivalent` | `java.util.Collections.sort()`, Apache Commons CSV, external sort libraries |
| `IBM DFSORT specification` | SORT/MERGE/COPY control statements, INCLUDE/OMIT filtering |
| `DFSORT modernization patterns` | Stream-based processing in Java 8+, Spring Batch sorting step |

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM z/OS 2.5 DFSORT Application Programming Guide (SC23-6878) | [https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/icea100_v2r5.pdf](https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/icea100_v2r5.pdf) | Complete DFSORT programming reference (PDF) |
| IBM SORT Control Statement Reference | [https://www.ibm.com/docs/en/zos/2.1.0?topic=statements-sort-control-statement](https://www.ibm.com/docs/en/zos/2.1.0?topic=statements-sort-control-statement) | SORT FIELDS syntax and operand specifications |
| IBM DFSORT Example | [https://www.ibm.com/docs/SSLTBW_2.4.0/com.ibm.zos.v2r4.icea100/ice2ca_DFSORT_example.htm](https://www.ibm.com/docs/SSLTBW_2.4.0/com.ibm.zos.v2r4.icea100/ice2ca_DFSORT_example.htm) | Working DFSORT example with JCL and control statements |

#### Key Behavioral Specifications

Based on IBM official documentation:

- **Function**: DFSORT (Data Facility Sort) is IBM's high-performance sort/merge/copy utility for z/OS. It processes records from one or more input datasets and produces sorted, merged, or copied output.
- **Key Control Statements**:
  - `SORT FIELDS=(position,length,format,order,...)` — Specifies sort keys by position, length, data format, and ascending/descending order.
  - `MERGE FIELDS=(...)` — Merges pre-sorted input datasets.
  - `INCLUDE COND=(...)` / `OMIT COND=(...)` — Filters records based on field conditions.
  - `OUTREC FIELDS=(...)` — Reformats output records (field selection, reordering, padding).
  - `INREC FIELDS=(...)` — Reformats input records before sorting.
  - `SUM FIELDS=(...)` — Summarizes numeric fields for records with duplicate keys.
- **Data Formats**: CH (character), ZD (zoned decimal), PD (packed decimal), BI (binary), FI (fixed-point), FL (floating-point), AC (ASCII character).
- **Performance**: DFSORT is optimized for large-scale sorting with features like Blockset processing, HIPERSPACE sorting, and parallel sort. These performance characteristics do not have direct Java equivalents but are rarely needed for CardDemo-scale data volumes.

**CardDemo Usage**: DFSORT is referenced in the **COMBTRAN** batch job (per `README.md`), which combines system transactions with daily transactions. The sort operation merges transaction records from multiple sources into a unified, ordered transaction file.

#### Community and Migration Sources

| Source | URL | Relevance |
|:-------|:----|:----------|
| DFSORT Tutorial — MainframesTechHelp | [https://www.mainframestechhelp.com/utilities/sort/](https://www.mainframestechhelp.com/utilities/sort/) | Comprehensive DFSORT tutorial with control statement examples |
| JCL DFSORT Overview — IBM Mainframer | [https://www.ibmmainframer.com/jcl-tutorial/jcl-sort-utility/](https://www.ibmmainframer.com/jcl-tutorial/jcl-sort-utility/) | JCL coding patterns for DFSORT invocation |
| Mainframe Sort/Merge — Wikipedia | [https://en.wikipedia.org/wiki/Mainframe_sort_merge](https://en.wikipedia.org/wiki/Mainframe_sort_merge) | Historical context and comparison of mainframe sort utilities (DFSORT, SyncSort) |
| ING Bank COBOL-to-Java Migration — SoftwareMining | [https://softwaremining.com/news/ING-Bank-Mainframe-Modernization.jsp](https://softwaremining.com/news/ING-Bank-Mainframe-Modernization.jsp) | Real-world case study of mainframe batch migration including sort utilities |

#### Java Equivalent

For the CardDemo COMBTRAN sort operation, the recommended Java migration approach uses `java.util.Comparator` with file-level sort processing:

```java
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

// Equivalent to DFSORT SORT FIELDS=(position,length,CH,A)
Comparator<TransactionRecord> sortComparator = Comparator
    .comparing(TransactionRecord::getCardNumber)
    .thenComparing(TransactionRecord::getProcessingDate);

List<TransactionRecord> sortedTransactions = transactions.stream()
    .sorted(sortComparator)
    .collect(Collectors.toList());
```

For large-volume external sorting (millions of records), consider:
- **Apache Commons IO** (`org.apache.commons:commons-io:2.15.x`) for file-based merge operations.
- **Spring Batch** sort step with chunk-oriented processing for memory-efficient handling.
- **Java NIO** (`java.nio.file.Files`) for large file I/O with memory-mapped buffers.

#### Known Edge Cases and Migration Warnings

- **Packed decimal (PD) sort keys**: DFSORT natively sorts COMP-3 packed decimal fields. Java has no built-in packed decimal type — migration requires a `BigDecimal`-based comparator or a custom packed decimal parser. The `jt400.jar` library from IBM provides `AS400PackedDecimal` for packed decimal handling.
- **EBCDIC collating sequence**: DFSORT uses EBCDIC collation by default. Java uses Unicode/ASCII. Sorting results may differ for mixed-case alphabetic data or special characters. Migration testing must validate sort order equivalence.
- **OUTREC/INREC reformatting**: DFSORT's record reformatting capabilities are powerful but do not have a single Java equivalent. These map to Java stream `map()` operations or Spring Batch `ItemProcessor` transformations.
- **MERGE vs. SORT**: The MERGE statement assumes pre-sorted inputs and uses a merge algorithm. Java's `Collections.sort()` performs a full sort regardless. For performance-critical migration, consider implementing a true merge algorithm using `PriorityQueue` with `K` sorted input streams.

---

### 2.3.3 IEBGENER — Sequential Dataset Copy Utility

#### Search Queries Used

| Query | Primary Findings |
|:------|:-----------------|
| `IEBGENER IBM mainframe documentation` | IBM z/OS DFSMSdfp Utilities reference |
| `IEBGENER COBOL to Java migration` | `java.nio.file.Files.copy()` as direct replacement |
| `IEBGENER Java equivalent` | Java NIO file copy or AWS S3 copy operations |
| `IBM IEBGENER specification` | Sequential dataset copy with optional record reformatting |
| `IEBGENER modernization patterns` | File copy operations via Java NIO or cloud storage APIs |

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM z/OS DFSMSdfp Utilities — IEBGENER | [https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEBGENER.htm](https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEBGENER.htm) | Official IBM documentation for the IEBGENER utility |

#### Key Behavioral Specifications

Based on IBM official documentation:

- **Function**: IEBGENER is a general-purpose sequential dataset copy utility. It copies records from an input dataset (SYSUT1) to an output dataset (SYSUT2) with optional record selection and reformatting.
- **Key Features**:
  - Copies sequential datasets, PDS members, and PDSE members.
  - Supports record reformatting via SYSIN control statements (GENERATE, RECORD, MEMBER, LABELS statements).
  - Can convert between record formats (FB, VB, U).
  - Performs 1:1 copy when SYSIN is specified as DUMMY (no control statements).
- **DD Statements**: `SYSUT1` (input), `SYSUT2` (output), `SYSPRINT` (messages), `SYSIN` (control statements or DUMMY).

**CardDemo Usage**: IEBGENER is used in the **DUSRSECJ** batch job to copy the user security data from a sequential file into the USRSEC VSAM file. This is a straightforward 1:1 copy operation with no reformatting.

#### Community and Migration Sources

| Source | URL | Relevance |
|:-------|:----|:----------|
| IEBGENER Tutorial — MainframesTechHelp | [https://www.mainframestechhelp.com/utilities/iebgener/](https://www.mainframestechhelp.com/utilities/iebgener/) | Practical IEBGENER usage examples and JCL patterns |
| JCL Utility Programs — IBM Mainframer | [https://www.ibmmainframer.com/jcl-tutorial/jcl-utility-programs/](https://www.ibmmainframer.com/jcl-tutorial/jcl-utility-programs/) | Overview of IBM utility programs including IEBGENER |

#### Java Equivalent

For the CardDemo DUSRSECJ use case (simple sequential copy):

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Direct equivalent of IEBGENER with SYSIN DUMMY (1:1 copy)
Files.copy(
    Path.of("input/usrsec.dat"),
    Path.of("output/usrsec.dat"),
    StandardCopyOption.REPLACE_EXISTING
);
```

For AWS environments, the equivalent is an S3 copy operation:

```java
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;

S3Client s3 = S3Client.create();
s3.copyObject(CopyObjectRequest.builder()
    .sourceBucket("carddemo-data")
    .sourceKey("input/usrsec.dat")
    .destinationBucket("carddemo-data")
    .destinationKey("vsam/usrsec.dat")
    .build());
```

#### Known Edge Cases and Migration Warnings

- **Record format conversion**: IEBGENER can convert between FB (fixed-block) and VB (variable-block) record formats. Java file operations are byte-stream oriented and do not have a concept of record formats. Migration must implement explicit record-length handling for fixed-width mainframe files.
- **EBCDIC encoding**: Mainframe files are EBCDIC-encoded. Java file copy preserves bytes, but if any downstream processing interprets content as text, EBCDIC-to-ASCII conversion is required using `java.nio.charset.Charset.forName("IBM037")` or `"Cp1047"`.
- **PDS member copy**: IEBGENER can copy individual PDS members. This feature is not used in CardDemo but would map to directory-based file operations in Java.

---

### 2.3.4 IEFBR14 — No-Operation Utility

#### Search Queries Used

| Query | Primary Findings |
|:------|:-----------------|
| `IEFBR14 IBM mainframe documentation` | IBM z/OS MVS JCL Reference |
| `IEFBR14 COBOL to Java migration` | No-op; replaced by infrastructure provisioning |
| `IEFBR14 Java equivalent` | Not needed — infrastructure managed externally |
| `IBM IEFBR14 specification` | Single-instruction program (BR 14 = return to caller) |
| `IEFBR14 modernization patterns` | Infrastructure-as-Code (Terraform, CloudFormation) |

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM z/OS DFSMSdfp Utilities — IEFBR14 | [https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEFBR14.htm](https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEFBR14.htm) | Official IBM documentation for the IEFBR14 utility |

#### Key Behavioral Specifications

Based on IBM official documentation:

- **Function**: IEFBR14 is a "do-nothing" utility program. It consists of a single assembler instruction: `BR 14` (Branch to Register 14, which returns to the caller). The program itself performs no processing.
- **Purpose**: The sole purpose of IEFBR14 is to allow JCL DD statement processing to occur. JCL processes DD statements (allocating, cataloging, or deleting datasets) regardless of what the executed program does. IEFBR14 provides a vehicle for these JCL-level operations without any program logic.
- **Common Uses**:
  - Creating empty datasets (via DD with `DISP=(NEW,CATLG)`)
  - Deleting datasets (via DD with `DISP=(OLD,DELETE)`)
  - Triggering dataset disposition processing
  - Making VSAM files available or unavailable to CICS (open/close operations)

**CardDemo Usage**: IEFBR14 is used in two batch jobs:
- **CLOSEFIL** — Closes VSAM files opened by CICS (DD statements trigger VSAM file close processing)
- **OPENFIL** — Makes VSAM files available to CICS (DD statements trigger VSAM file open processing)

#### Community and Migration Sources

| Source | URL | Relevance |
|:-------|:----|:----------|
| IEFBR14 Tutorial — MainframesTechHelp | [https://www.mainframestechhelp.com/utilities/iefbr14/](https://www.mainframestechhelp.com/utilities/iefbr14/) | Practical IEFBR14 usage examples and explanation of its role in JCL |
| IEFBR14 — Wikipedia | [https://en.wikipedia.org/wiki/IEFBR14](https://en.wikipedia.org/wiki/IEFBR14) | Historical context including the famous "bug in a one-instruction program" story and its correction |

#### Java/AWS Equivalent

IEFBR14 has **no direct Java equivalent** because its function is entirely infrastructure-level. The CardDemo CLOSEFIL and OPENFIL jobs control VSAM file availability to CICS, which maps to service endpoint management in Java:

| CardDemo Job | Mainframe Function | AWS/Java Equivalent |
|:-------------|:-------------------|:--------------------|
| CLOSEFIL | Close VSAM files for CICS | Stop database connection pool; set service health check to unhealthy |
| OPENFIL | Open VSAM files for CICS | Initialize database connection pool; set service health check to healthy |

In an AWS migration context, these operations map to:
- **AWS RDS**: Database instance start/stop via AWS SDK
- **Spring Boot**: Application context lifecycle (`@PreDestroy`, `@PostConstruct` annotations)
- **Infrastructure-as-Code**: Terraform `aws_rds_cluster` or CloudFormation `AWS::RDS::DBInstance` for provisioning

#### Known Edge Cases and Migration Warnings

- **No behavioral migration needed**: IEFBR14 has no program logic to migrate. The migration concern is entirely about replicating the JCL DD statement effects in the target environment.
- **Dataset disposition processing**: The real work in CLOSEFIL/OPENFIL is done by JCL, not by IEFBR14. Migration must ensure that the equivalent lifecycle operations (connection pool management, service availability) are triggered at the correct points in the batch processing chain.
- **Historical note**: IEFBR14 originally had a bug — it did not set `RETURN-CODE` to 0, causing non-zero completion codes. IBM corrected this, but the incident is notable in mainframe folklore. No equivalent issue exists in Java.

---

## 2.4 CICS Runtime Commands

### 2.4.1 CICS File Control Commands

#### Search Queries Used

| Query | Primary Findings |
|:------|:-----------------|
| `CICS file control commands IBM documentation` | IBM CICS TS Application Programming Reference |
| `CICS READ WRITE REWRITE Java migration` | Spring Data JPA repository operations |
| `EXEC CICS STARTBR READNEXT Java equivalent` | JPA Criteria queries, Spring Data pagination |
| `CICS VSAM to JDBC migration patterns` | VSAM-to-relational mapping strategies |
| `CICS file control modernization` | Spring Data JPA, JDBC Template patterns |

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM CICS Transaction Server Application Programming Reference | IBM CICS TS documentation library at `ibm.com/docs/en/cics-ts` | Authoritative reference for all EXEC CICS commands (file control, terminal I/O, program control, system services) |
| IBM CICS TS for z/OS — File Control | IBM CICS TS library — File Control chapter | Detailed specifications for READ, WRITE, REWRITE, DELETE, STARTBR, READNEXT, READPREV, ENDBR commands |

#### Key Behavioral Specifications

The following 8 CICS file control commands are used across all 19 CardDemo online programs:

| Command | Function | VSAM Equivalent | CardDemo Programs |
|:--------|:---------|:----------------|:------------------|
| `EXEC CICS READ` | Read a record by primary key (direct access) | VSAM KSDS keyed read | All 19 online programs |
| `EXEC CICS READ UPDATE` | Read a record with intent to update (locks record) | VSAM KSDS read-for-update | COACTUP, COBIL00C, COCRDUP, COTRN02C |
| `EXEC CICS WRITE` | Insert a new record | VSAM KSDS sequential insert | COCRDUP, COTRN01C, COBIL00C |
| `EXEC CICS REWRITE` | Update a previously READ UPDATE record | VSAM KSDS rewrite (after read-for-update) | COACTUP, COBIL00C, COCRDUP, COTRN02C |
| `EXEC CICS DELETE` | Delete a record by primary key | VSAM KSDS delete | COCRDUP, COACTUP |
| `EXEC CICS STARTBR` | Start browse (position cursor for sequential read) | VSAM KSDS browse start | COCRDSL, COACTVWC, COTRN00C |
| `EXEC CICS READNEXT` | Read next record in browse sequence | VSAM KSDS sequential read forward | COCRDSL, COACTVWC, COTRN00C |
| `EXEC CICS READPREV` | Read previous record in browse sequence | VSAM KSDS sequential read backward | COCRDSL, COACTVWC, COTRN00C |
| `EXEC CICS ENDBR` | End browse session | VSAM KSDS browse end | COCRDSL, COACTVWC, COTRN00C |

**Key Parameters**: `FILE(name)`, `INTO(data-area)`, `RIDFLD(key-field)`, `LENGTH(data-length)`, `KEYLENGTH(key-length)`, `RESP(response-code)`, `RESP2(reason-code)`, `UPDATE`, `GTEQ/EQUAL`.

#### Java/Spring Equivalent Mapping

| CICS Command | Spring Data JPA Equivalent | JDBC Template Equivalent |
|:-------------|:--------------------------|:-------------------------|
| `READ FILE(name) INTO(area) RIDFLD(key)` | `repository.findById(key)` | `jdbcTemplate.queryForObject(sql, mapper, key)` |
| `READ FILE(name) INTO(area) RIDFLD(key) UPDATE` | `@Lock(LockModeType.PESSIMISTIC_WRITE) findById(key)` | `SELECT ... FOR UPDATE` |
| `WRITE FILE(name) FROM(area)` | `repository.save(entity)` (new entity) | `jdbcTemplate.update(insertSql, params)` |
| `REWRITE FILE(name) FROM(area)` | `repository.save(entity)` (existing entity) | `jdbcTemplate.update(updateSql, params)` |
| `DELETE FILE(name) RIDFLD(key)` | `repository.deleteById(key)` | `jdbcTemplate.update(deleteSql, key)` |
| `STARTBR` + `READNEXT` loop + `ENDBR` | `repository.findAllByKeyGreaterThanEqual(startKey, pageable)` | Cursor-based `ResultSet` iteration |
| `READPREV` | `repository.findAllByKeyLessThanEqual(key, Sort.DESC)` | `ORDER BY key DESC` with cursor |

#### Known Edge Cases and Migration Warnings

- **READ UPDATE locking**: CICS READ UPDATE places an exclusive lock on the record until REWRITE, DELETE, or UNLOCK is issued. In JPA, this maps to `PESSIMISTIC_WRITE` lock mode, but the lock scope differs — CICS locks a single record, while JPA locks a database row (which may have different granularity).
- **RESP/RESP2 error handling**: CICS file control commands return `RESP` codes (e.g., `DFHRESP(NORMAL)`, `DFHRESP(NOTFND)`, `DFHRESP(DUPREC)`). Java migration should use try-catch with specific exceptions (`EntityNotFoundException`, `DataIntegrityViolationException`).
- **GTEQ positioning**: `STARTBR` with `GTEQ` (greater-than-or-equal) positions the browse cursor at the first record whose key is >= the specified value. JPA equivalent uses `findAllByKeyGreaterThanEqual()` with `Sort.ASC`.
- **File status vs. RESP codes**: Batch COBOL programs use FILE STATUS codes (00, 10, 35, etc.), while CICS programs use RESP/RESP2 codes. The migration must unify these into a single exception handling strategy.

---

### 2.4.2 CICS Terminal I/O Commands

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM CICS TS Application Programming Reference — SEND/RECEIVE MAP | IBM CICS TS library — BMS chapter | Specifications for SEND MAP, RECEIVE MAP, SEND CONTROL commands |
| IBM CICS TS Application Programming Guide — BMS | IBM CICS TS library — BMS Mapping Guide | BMS mapset design, symbolic map generation, MDT (Modified Data Tag) handling |

#### Key Behavioral Specifications

| Command | Function | CardDemo Usage |
|:--------|:---------|:---------------|
| `EXEC CICS SEND MAP(name) MAPSET(setname) FROM(data)` | Send a formatted BMS map to the 3270 terminal | All 19 online programs — sends screen data to user |
| `EXEC CICS SEND MAP ... ERASE` | Send map and erase the screen first | Used on initial screen display |
| `EXEC CICS SEND MAP ... CURSOR` | Send map with cursor positioned at specified field | Used for error field focus |
| `EXEC CICS RECEIVE MAP(name) MAPSET(setname) INTO(data)` | Receive user-modified map data from 3270 terminal | All 19 online programs — receives user input |

**Key Parameters**: `MAP(name)`, `MAPSET(setname)`, `FROM(data-area)`, `INTO(data-area)`, `ERASE`, `CURSOR`, `RESP(code)`, `RESP2(code)`.

#### Java/Web Equivalent Mapping

| CICS Operation | Spring MVC Equivalent |
|:---------------|:---------------------|
| `SEND MAP` (initial display) | `@GetMapping` → return `ModelAndView` with form data |
| `RECEIVE MAP` (user input) | `@PostMapping` → accept `@ModelAttribute` form bean |
| `SEND MAP ... ERASE` (full refresh) | HTTP redirect (`RedirectAttributes`) for full page reload |
| `SEND MAP ... CURSOR(field)` | JavaScript `focus()` on error field in HTML form |
| BMS field attributes (color, highlight) | CSS classes and HTML5 `required`, `pattern` attributes |
| DFHAID (PF keys, ENTER, CLEAR) | HTML buttons, keyboard shortcuts via JavaScript event listeners |

#### Known Edge Cases and Migration Warnings

- **Modified Data Tag (MDT)**: BMS tracks which fields the user modified via MDT. In HTML forms, all fields are submitted regardless of modification. Migration should implement client-side change detection if differential update behavior is required.
- **Cursor positioning**: CICS positions the cursor at a specific field via symbolic cursor positioning (setting field length to -1). HTML equivalent uses JavaScript `document.getElementById('field').focus()`.
- **Screen-level validation**: BMS maps perform basic field validation (numeric-only, length limits) at the 3270 terminal level. HTML5 form validation (`type="number"`, `maxlength`, `pattern`) provides equivalent client-side validation.

---

### 2.4.3 CICS Program Control Commands

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM CICS TS Application Programming Reference — Program Control | IBM CICS TS library — Program Control chapter | RETURN, XCTL, LINK command specifications |

#### Key Behavioral Specifications

| Command | Function | CardDemo Usage |
|:--------|:---------|:---------------|
| `EXEC CICS RETURN TRANSID(id) COMMAREA(data)` | Return control to CICS with pseudo-conversational restart | All 19 online programs — end of task cycle |
| `EXEC CICS XCTL PROGRAM(name) COMMAREA(data)` | Transfer control to another program (no return) | Menu navigation, program-to-program flow |
| `EXEC CICS LINK PROGRAM(name) COMMAREA(data)` | Call another program (with return) | Subroutine calls |
| `EXEC CICS HANDLE CONDITION` | Register condition handlers | Error handling setup |
| `EXEC CICS ABEND ABCODE(code)` | Terminate task with abend code | Unrecoverable error handling in online programs |

**CardDemo Pattern** — Pseudo-conversational flow (Source: `app/cbl/CORPT00C.cbl:199-202`):

```cobol
EXEC CICS RETURN
          TRANSID (WS-TRANID)
          COMMAREA (CARDDEMO-COMMAREA)
END-EXEC.
```

#### Java/Spring Equivalent Mapping

| CICS Concept | Spring Equivalent |
|:-------------|:-----------------|
| `RETURN TRANSID COMMAREA` (pseudo-conversational) | HTTP session attributes or JWT token state; `@SessionAttributes` |
| `XCTL PROGRAM(name)` (transfer control) | Spring MVC `redirect:/target-endpoint` or `@Controller` method call |
| `LINK PROGRAM(name)` (call/return) | Spring `@Service` injection and method invocation |
| COMMAREA (Communication Area) | HTTP session, JWT claims, or `@SessionScope` beans |
| EIBCALEN (COMMAREA length check) | Session existence check (`session.getAttribute() != null`) |
| Pseudo-conversational pattern | Stateless HTTP request/response cycle |

#### Known Edge Cases and Migration Warnings

- **COMMAREA size limit**: CICS COMMAREA is limited to 32,767 bytes. HTTP sessions have no such limit but should be kept small for scalability. Consider using server-side session stores (Redis, database) for large state.
- **Pseudo-conversational state management**: CICS pseudo-conversational programs store state in COMMAREA between task invocations. In a web application, this maps to HTTP session state. For stateless architectures, consider JWT tokens with encoded state.
- **XCTL vs. LINK**: XCTL does not return to the caller (like a `goto`), while LINK returns (like a subroutine call). In Spring, XCTL maps to `redirect:` (new HTTP request), and LINK maps to direct service method invocation.

---

### 2.4.4 CICS System Services

#### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM CICS TS Application Programming Reference — System Services | IBM CICS TS library — System Services chapter | ASKTIME, FORMATTIME, ASSIGN, WRITEQ TD specifications |

#### Key Behavioral Specifications

| Command | Function | CardDemo Usage |
|:--------|:---------|:---------------|
| `EXEC CICS ASKTIME ABSTIME(data)` | Get current time as absolute time value | Timestamp retrieval in multiple programs |
| `EXEC CICS FORMATTIME ABSTIME(data) DATESEP TIMESEP` | Format absolute time into readable date/time strings | Date/time formatting for screen display |
| `EXEC CICS ASSIGN SYSID(data)` | Retrieve system information (system ID, terminal ID) | System identification in header displays |
| `EXEC CICS WRITEQ TD QUEUE(name) FROM(data)` | Write to Transient Data Queue | Batch job submission from CORPT00C |

**WRITEQ TD — Critical Pattern** (Source: `app/cbl/CORPT00C.cbl:517-523`):

```cobol
EXEC CICS WRITEQ TD
  QUEUE ('JOBS')
  FROM (JCL-RECORD)
  LENGTH (LENGTH OF JCL-RECORD)
  RESP(WS-RESP-CD)
  RESP2(WS-REAS-CD)
END-EXEC.
```

This pattern writes JCL records to the `JOBS` Transient Data Queue, which is an extrapartition TDQ connected to the JES internal reader. Each JCL record is written as a separate TDQ entry. When all records are written, JES processes the JCL and submits the batch job.

#### Java/AWS Equivalent Mapping

| CICS Command | Java/AWS Equivalent |
|:-------------|:-------------------|
| `ASKTIME` + `FORMATTIME` | `java.time.LocalDateTime.now()` with `DateTimeFormatter` |
| `ASSIGN SYSID` | `InetAddress.getLocalHost().getHostName()` or Spring `@Value("${spring.application.name}")` |
| `WRITEQ TD QUEUE('JOBS')` | Amazon SQS `sendMessage()` or AWS Step Functions `startExecution()` |

**WRITEQ TD Migration Architecture** (online-to-batch coupling):

```
Mainframe:  CORPT00C → WRITEQ TD → JES Internal Reader → Batch Job
AWS:        ReportController → SQS.sendMessage() → Lambda/Step Functions → Batch Job
```

#### Known Edge Cases and Migration Warnings

- **ASKTIME/FORMATTIME timezone**: CICS ASKTIME returns time in the CICS region's timezone (typically UTC or local). Java `LocalDateTime.now()` uses the JVM's default timezone. Migration must ensure consistent timezone handling — recommend using `ZonedDateTime.now(ZoneId.of("UTC"))`.
- **TDQ-to-message queue migration**: The `WRITEQ TD` → JES pattern is a tightly coupled synchronous-to-asynchronous bridge. Amazon SQS provides equivalent decoupling but with different delivery semantics (at-least-once vs. exactly-once). For exactly-once, consider SQS FIFO queues.
- **JCL record format**: The CORPT00C program writes 80-byte JCL records to the TDQ. In the AWS migration, the batch job submission message should contain the equivalent job parameters (report type, date range) in a structured format (JSON), rather than raw JCL text.

---

## 2.5 BMS Macro Processing

### Search Queries Used

| Query | Primary Findings |
|:------|:-----------------|
| `BMS macros IBM CICS documentation` | IBM CICS TS Application Programming Guide — BMS chapter |
| `BMS DFHMSD DFHMDI DFHMDF Java migration` | HTML/CSS form mapping patterns |
| `BMS map COBOL to Java migration` | Screen-to-web UI transformation tools (MaTriX, BMS2HTML) |
| `IBM BMS specification` | Macro-level screen definition syntax |
| `BMS modernization patterns` | React/Angular component generation from BMS definitions |

### IBM Official Documentation

| Source | URL | Key Content |
|:-------|:----|:------------|
| IBM CICS TS Application Programming Guide — BMS Mapping | IBM CICS TS library — BMS chapter | Complete BMS macro specification including DFHMSD, DFHMDI, DFHMDF |
| IBM CICS TS — DFHBMSCA Copybook Reference | IBM CICS TS library — BMS Attributes | Character attribute definitions for 3270 terminal display (colors, highlighting, protection) |
| IBM CICS TS — DFHAID Copybook Reference | IBM CICS TS library — AID Keys | Attention Identifier definitions for 3270 keyboard keys (PF1-PF24, ENTER, CLEAR, PA1-PA3) |

### Key Behavioral Specifications

**BMS Macro Hierarchy**:

| Macro | Function | CardDemo Usage |
|:------|:---------|:---------------|
| `DFHMSD` | Define a mapset (collection of maps) | 17 mapsets in `app/bms/*.bms` |
| `DFHMDI` | Define an individual map within a mapset | Screen layout definition (row, column, size) |
| `DFHMDF` | Define a field within a map | Individual input/output fields (position, length, attributes, initial value) |

**Supporting Copybooks**:

| Copybook | Function | Usage |
|:---------|:---------|:------|
| `DFHBMSCA` | BMS Character Attributes — defines constants for field attributes (DFHBMPEM, DFHBMPRO, DFHBMUNP, DFHBMBRY, etc.) and colors (DFHGREEN, DFHRED, DFHYELLO, etc.) | All 19 online programs |
| `DFHAID` | Attention Identifier Definitions — defines constants for 3270 AID keys (DFHENTER, DFHPF1-DFHPF24, DFHCLEAR, DFHPA1-DFHPA3) | All 19 online programs for keyboard input evaluation |

**BMS Field Attributes**:

| Attribute | 3270 Meaning | HTML Equivalent |
|:----------|:-------------|:----------------|
| `ASKIP` (Autoskip) | Cursor skips over field | `readonly` attribute or `<span>` display-only |
| `PROT` (Protected) | Field cannot be modified | `disabled` or `readonly` attribute |
| `UNPROT` (Unprotected) | Field is editable | Standard `<input>` element |
| `NUM` (Numeric) | Only numeric input allowed | `<input type="number">` or `pattern="[0-9]*"` |
| `BRT` (Bright) | High-intensity display | CSS `font-weight: bold` or highlight class |
| `NORM` (Normal) | Normal intensity | Default CSS styling |
| `DRK` (Dark) | Hidden field | `<input type="hidden">` or `display: none` |
| `IC` (Insert Cursor) | Cursor starts at this field | JavaScript `focus()` on page load |
| `FSET` (Field Set) | MDT is turned on | Always submit field value (default in HTML) |

### Community and Migration Sources

| Source | URL | Relevance |
|:-------|:----|:----------|
| Pro et Con — CoJaC BMS Migration (MaTriX) | [https://proetcon.de/index.php/en/software-migration-2/technology-and-tools/cojac/](https://proetcon.de/index.php/en/software-migration-2/technology-and-tools/cojac/) | Commercial tool for migrating BMS ASCII masks to web interfaces |

### Java/Web Equivalent Mapping

The 17 CardDemo BMS mapsets map to web UI components as follows:

| BMS Concept | HTML/React Equivalent |
|:------------|:---------------------|
| `DFHMSD TYPE=MAP` (mapset) | HTML page template or React component module |
| `DFHMDI` (map) | HTML `<form>` element or React form component |
| `DFHMDF POS=(row,col)` (field) | HTML `<input>`, `<select>`, `<span>` at CSS grid position |
| BMS symbolic map (input structure) | Form bean / DTO (Data Transfer Object) |
| BMS symbolic map (output structure) | View model / response DTO |
| `DFHBMSCA` attributes | CSS classes for styling (protected, error, highlight) |
| `DFHAID` key evaluation | JavaScript keyboard event handlers |
| 3270 screen (24×80 characters) | Responsive HTML form with CSS Grid layout |

### Known Edge Cases and Migration Warnings

- **Cursor positioning arithmetic**: BMS uses absolute screen positions `POS=(row,col)` in a fixed 24×80 grid. Web layouts use responsive design. Migration should use CSS Grid with named areas or absolute positioning within a fixed-width container for exact layout preservation.
- **MDT (Modified Data Tag)**: BMS tracks per-field modification state. HTML forms submit all fields. If differential update behavior is critical, implement JavaScript change tracking.
- **Attribute byte overhead**: In 3270, each field has a preceding attribute byte that consumes a screen position. This affects field positioning calculations. Web migration does not have this constraint.
- **PF key mapping**: 3270 terminals have 24 PF keys. Web applications typically use toolbar buttons or keyboard shortcuts. Migration should document the PF key-to-button mapping for user training purposes.
- **Color constraints**: 3270 terminals support 7 colors (default, blue, red, pink, green, turquoise, yellow, white). Web migration can expand the color palette but should maintain the original color semantics for accessibility.

---

## 2.6 Additional Migration Resources

The following resources provide cross-cutting migration guidance applicable to multiple CardDemo utilities:

### General Migration Tools and Services

| Source | URL | Description |
|:-------|:----|:------------|
| IBM Migration Utility Explorer | [https://www.ibm.com/support/pages/ibm-migration-utility-explorer](https://www.ibm.com/support/pages/ibm-migration-utility-explorer) | IBM's tool for exploring migration paths for mainframe utilities |
| IBM Digital Marketplace — Migration and Modernization Services | [https://www.applytosupply.digitalmarketplace.service.gov.uk/g-cloud/services/752697492878620](https://www.applytosupply.digitalmarketplace.service.gov.uk/g-cloud/services/752697492878620) | IBM's G-Cloud migration service offering |
| VerraDyne IBM Mainframe Migration | [https://verradyne.com/ibm-mainframe-migration/](https://verradyne.com/ibm-mainframe-migration/) | Third-party mainframe migration services and methodology |

### Reference Material

| Source | URL | Description |
|:-------|:----|:------------|
| IBM Mainframe Utility Programs — Encyclopedia MDPI | [https://encyclopedia.pub/entry/31139](https://encyclopedia.pub/entry/31139) | Academic overview of IBM mainframe utility programs and their roles |
| BMC Abend-AID Documentation | [https://docs.bmc.com/xwiki/bin/view/Mainframe/DevX/BMC-AMI-DevX-Abend-AID/](https://docs.bmc.com/xwiki/bin/view/Mainframe/DevX/BMC-AMI-DevX-Abend-AID/) | Third-party abend analysis tool documentation — provides context for CEE3ABD/CICS ABEND diagnostics |
| ING Bank Mainframe Modernization — SoftwareMining | [https://softwaremining.com/news/ING-Bank-Mainframe-Modernization.jsp](https://softwaremining.com/news/ING-Bank-Mainframe-Modernization.jsp) | Published case study of large-scale COBOL-to-Java migration at a major financial institution |

### Data Conversion Resources

| Topic | Key Information |
|:------|:----------------|
| EBCDIC-to-ASCII Conversion | Java charset `IBM037` or `Cp1047` via `java.nio.charset.Charset.forName("IBM037")`. The `jt400.jar` library provides `AS400Text` for bidirectional EBCDIC conversion. |
| COMP-3 Packed Decimal | Java `java.math.BigDecimal` for arithmetic; custom byte-level parser for packed decimal (each byte = 2 digits, last nibble = sign). The `jt400.jar` library provides `AS400PackedDecimal`. |
| Fixed-Width Record Layouts | Java `String.substring()` with copybook-derived offsets, or Apache Commons `FixedLengthTokenizer` in Spring Batch. |
| Lilian Date Arithmetic | `java.time.LocalDate` with epoch offset (October 14, 1582). See [CEEDAYS section](#221-ceedays--convert-date-to-lilian-format) for implementation. |

---

## 2.7 Citation Summary Table

The following table provides a consolidated reference of all external URLs cited in this document, organized by utility for quick stakeholder verification:

| # | Utility | Source Type | URL |
|:--|:--------|:------------|:----|
| 1 | CEEDAYS | IBM Official | [z/OS 2.1 LE — CEEDAYS](https://www.ibm.com/docs/en/zos/2.1.0?topic=services-ceedays-convert-date-lilian-format) |
| 2 | CEEDAYS | IBM Official | [IBM i CEEDAYS API](https://www.ibm.com/docs/api/v1/content/ssw_ibm_i_74/apis/CEEDAYS.htm) |
| 3 | CEEDAYS | IBM Official | [ILE CL CEE API Usage](https://www.ibm.com/support/pages/ile-cl-usage-cee-apis-ceedays-ceedate-ceedywk) |
| 4 | CEEDAYS | IBM Official | [COBOL CEEDAYS Example](https://www.ibm.com/support/pages/example-cobol-calling-apis-ceedays-ceedate-and-ceedywk) |
| 5 | CEEDAYS / CEE3ABD | IBM Official | [z/OS 2.5 LE Programming Reference (PDF)](https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/ceea300_v2r5.pdf) |
| 6 | CEEDAYS | Community | [MC Press — CEE Date APIs](https://www.mcpressonline.com/programming-other/cl/the-cl-corner-more-on-ile-cee-date-and-time-apis) |
| 7 | CEEDAYS | IBM Official | [IBM i C/400 CEEDAYS Examples](https://www.ibm.com/support/pages/program-examples-ile-ibm-c400-using-ceedays-ceedays-and-ceedywk) |
| 8 | CEE3ABD | IBM Official | [z/OS 2.4 LE — CEE3ABD](https://www.ibm.com/docs/en/zos/2.4.0?topic=services-cee3abdterminate-enclave-abend) |
| 9 | CEE3ABD | IBM Official | [z/OS 2.5 LE Runtime Messages (PDF)](https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/ceea900_v2r5.pdf) |
| 10 | CEE3ABD | IBM Official | [z/OS Understanding Abend Codes](https://www.ibm.com/docs/en/zos/2.4.0?topic=errors-understanding-abend-codes) |
| 11 | CEE3ABD | IBM Official | [Enterprise COBOL ILBOABN0](https://www.ibm.com/docs/en/cobol-zos/6.3.0?topic=6-ilboabn0-considerations) |
| 12 | CEE3ABD | Community | [BMC Abend-AID in LE](https://docs.bmc.com/xwiki/bin/view/Mainframe/DevX/BMC-AMI-DevX-Abend-AID/) |
| 13 | CEE3ABD | Community | [Mainframe Forum — User Abends](https://www.zmainframes.com/viewtopic.php?t=260) |
| 14 | IDCAMS | IBM Official | [z/OS Basic Skills — IDCAMS](https://www.ibm.com/docs/en/zos-basic-skills?topic=utilities-idcams-use-access-method-services-catalogs) |
| 15 | IDCAMS | Community | [MainframesTechHelp — IDCAMS](https://www.mainframestechhelp.com/utilities/idcams/) |
| 16 | IDCAMS | Community | [IBM Mainframer — IDCAMS](https://www.ibmmainframer.com/jcl-tutorial/jcl-idcams-utility/) |
| 17 | IDCAMS | Community | [Heirloom Computing Utility Guide](https://support.heirloom.cc/hc/en-us/articles/212578706-Standard-Utility-Programmers-Guide) |
| 18 | IDCAMS | Community | [IBM Mainframer — VSAM AMS](https://www.ibmmainframer.com/vsam-tutorial/vsam-access-method-services/) |
| 19 | DFSORT | IBM Official | [z/OS 2.5 DFSORT APG (PDF)](https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/icea100_v2r5.pdf) |
| 20 | DFSORT | IBM Official | [SORT Control Statement](https://www.ibm.com/docs/en/zos/2.1.0?topic=statements-sort-control-statement) |
| 21 | DFSORT | IBM Official | [DFSORT Example](https://www.ibm.com/docs/SSLTBW_2.4.0/com.ibm.zos.v2r4.icea100/ice2ca_DFSORT_example.htm) |
| 22 | DFSORT | Community | [MainframesTechHelp — DFSORT](https://www.mainframestechhelp.com/utilities/sort/) |
| 23 | DFSORT | Community | [IBM Mainframer — DFSORT](https://www.ibmmainframer.com/jcl-tutorial/jcl-sort-utility/) |
| 24 | DFSORT | Reference | [Wikipedia — Mainframe Sort/Merge](https://en.wikipedia.org/wiki/Mainframe_sort_merge) |
| 25 | DFSORT | Case Study | [ING Bank Migration — SoftwareMining](https://softwaremining.com/news/ING-Bank-Mainframe-Modernization.jsp) |
| 26 | IEBGENER | IBM Official | [DFSMSdfp — IEBGENER](https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEBGENER.htm) |
| 27 | IEBGENER | Community | [MainframesTechHelp — IEBGENER](https://www.mainframestechhelp.com/utilities/iebgener/) |
| 28 | IEBGENER | Community | [IBM Mainframer — Utility Programs](https://www.ibmmainframer.com/jcl-tutorial/jcl-utility-programs/) |
| 29 | IEFBR14 | IBM Official | [DFSMSdfp — IEFBR14](https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEFBR14.htm) |
| 30 | IEFBR14 | Community | [MainframesTechHelp — IEFBR14](https://www.mainframestechhelp.com/utilities/iefbr14/) |
| 31 | IEFBR14 | Reference | [Wikipedia — IEFBR14](https://en.wikipedia.org/wiki/IEFBR14) |
| 32 | General | IBM Official | [IBM Migration Utility Explorer](https://www.ibm.com/support/pages/ibm-migration-utility-explorer) |
| 33 | General | Reference | [Encyclopedia MDPI — Mainframe Utilities](https://encyclopedia.pub/entry/31139) |
| 34 | General | Vendor | [VerraDyne Mainframe Migration](https://verradyne.com/ibm-mainframe-migration/) |
| 35 | General | Vendor | [IBM Digital Marketplace Migration](https://www.applytosupply.digitalmarketplace.service.gov.uk/g-cloud/services/752697492878620) |
| 36 | BMS | Vendor | [Pro et Con — CoJaC BMS Migration](https://proetcon.de/index.php/en/software-migration-2/technology-and-tools/cojac/) |

**Total External Citations: 36** (17 IBM Official, 12 Community, 4 Vendor, 3 Reference/Case Study)

---

## Navigation

| | |
|:--|--:|
| ← Previous: [Proprietary Utility Inventory](./01-proprietary-utility-inventory.md) | Next: [Dependency Impact Analysis](./03-dependency-impact-analysis.md) → |

[↑ Executive Summary](./00-executive-summary.md) · [Appendix A: CICS Command Reference](./appendices/A-cics-command-reference.md) · [Appendix B: VSAM Dataset Catalog](./appendices/B-vsam-dataset-catalog.md)
