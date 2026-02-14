# Proprietary Utility Inventory

## Document Overview

This document provides an exhaustive catalog of all proprietary mainframe utility dependencies found across the AWS CardDemo application. The inventory covers 28 COBOL programs, 28 copybooks, and 17 BMS map sources, classifying each utility by type and documenting its parameters, consuming programs, and contextual purpose.

This inventory serves as the foundational catalog referenced by all other migration analysis documents:

- [Dependency Impact Analysis](02-dependency-impact-analysis.md)
- [Migration Strategy](03-migration-strategy.md)
- [Risk Assessment](04-risk-assessment.md)
- [Testing & Validation Framework](05-testing-validation-framework.md)

**Scope:** All proprietary (non-standard, IBM-specific) utilities, APIs, macros, and runtime services used by the CardDemo application. COBOL intrinsic functions are also included as they require mapping to Java equivalents during migration.

**Classification Types Used:**

| Type | Description |
|:-----|:------------|
| Abend Handling | Abnormal termination and dump generation services |
| Date/Time Services | Date conversion, formatting, and system time retrieval |
| File Handling | File I/O operations including sequential copy and dataset management |
| Database Operations | VSAM dataset definition, catalog management, and record-level access |
| Transaction Processing | Inter-program communication, state management, and asynchronous job submission |
| Screen I/O | Terminal input/output, screen rendering, and BMS map processing |
| Data Transformation | Numeric validation, string manipulation, and data format conversion |
| Sorting | File merge and sort operations |
| System Identification | System and application attribute retrieval |

---

## 1. IBM Language Environment (LE) Runtime Services

IBM Language Environment (LE) provides runtime services for COBOL programs running under z/OS. The CardDemo application uses two LE services: CEE3ABD for abend handling and CEEDAYS for date conversion.

### 1.1 CEE3ABD — Abend Handler

| Attribute | Detail |
|:----------|:-------|
| **Utility Name** | CEE3ABD |
| **Full Name** | Language Environment Abnormal Termination |
| **Type** | Abend Handling |
| **Invocation** | `CALL 'CEE3ABD'` |
| **Vendor** | IBM (z/OS Language Environment) |
| **Parameters** | No explicit parameters passed. The LE runtime uses pre-set working storage fields (`ABCODE` and `TIMING`) to control dump generation and abend code. |

**Purpose:** CEE3ABD provides controlled abnormal termination of batch COBOL programs when unrecoverable errors are detected (e.g., file I/O failures). When invoked, it terminates the program, generates a formatted dump for debugging, and returns a non-zero condition code to JCL for job step management.

**Behavioral Characteristics:**
- Reads `ABCODE` (S9(9) BINARY) for the user abend code
- Reads `TIMING` (S9(9) BINARY) — when set to 0, a dump is produced
- Terminates the Language Environment enclave
- Produces a formatted LE dump (CEEDUMP) to SYSOUT

**Consuming Programs (9 batch programs):**

| Program | Source Location | Context |
|:--------|:---------------|:--------|
| CBACT01C | `Source: app/cbl/CBACT01C.cbl:173` | Account file read — abends on unrecoverable file I/O error |
| CBACT02C | `Source: app/cbl/CBACT02C.cbl:158` | Account file processing — abends on file status error |
| CBACT03C | `Source: app/cbl/CBACT03C.cbl:158` | Account file processing — abends on file status error |
| CBACT04C | `Source: app/cbl/CBACT04C.cbl:632` | Interest calculation — abends on file open/close/I/O error |
| CBCUS01C | `Source: app/cbl/CBCUS01C.cbl:158` | Customer file processing — abends on file status error |
| CBSTM03A | `Source: app/cbl/CBSTM03A.CBL:923` | Statement generation — abends on critical I/O failure |
| CBTRN01C | `Source: app/cbl/CBTRN01C.cbl:473` | Transaction file processing — abends on file error |
| CBTRN02C | `Source: app/cbl/CBTRN02C.cbl:711` | Transaction posting — abends on file error |
| CBTRN03C | `Source: app/cbl/CBTRN03C.cbl:630` | Transaction report — abends on file error |

**Common Call Pattern:**

```cobol
       9999-ABEND-PROGRAM.
           DISPLAY 'ABENDING PROGRAM'
           MOVE 0 TO TIMING
           MOVE 999 TO ABCODE
           CALL 'CEE3ABD'.
```

`Source: app/cbl/CBACT01C.cbl:169-173`

All 9 batch programs use an identical pattern: the paragraph `9999-ABEND-PROGRAM` sets `TIMING` to 0 (requesting a dump) and `ABCODE` to 999 (user abend code), then calls CEE3ABD.

---

### 1.2 CEEDAYS — Lillian Date Conversion

| Attribute | Detail |
|:----------|:-------|
| **Utility Name** | CEEDAYS |
| **Full Name** | Language Environment Convert Date to Lillian Format |
| **Type** | Date/Time Services |
| **Invocation** | `CALL "CEEDAYS"` |
| **Vendor** | IBM (z/OS Language Environment) |

**Purpose:** CEEDAYS converts a character date string into a Lillian day number (days since October 15, 1582). In CardDemo, it is used exclusively by the CSUTLDTC utility program as a date validation mechanism — if the conversion succeeds with severity code 0, the date is valid.

**Parameters:**

| Parameter | COBOL Definition | Direction | Description |
|:----------|:-----------------|:----------|:------------|
| WS-DATE-TO-TEST | VSTRING (S9(4) BINARY length + X(256) text) | Input | Date string to validate/convert |
| WS-DATE-FORMAT | VSTRING (S9(4) BINARY length + X(256) text) | Input | Date format mask (e.g., `YYYYMMDD`) |
| OUTPUT-LILLIAN | S9(9) BINARY | Output | Lillian day number result |
| FEEDBACK-CODE | Structured (see below) | Output | LE condition token with severity and message codes |

**FEEDBACK-CODE Structure:**

| 88-Level Condition | Hex Value | Meaning |
|:-------------------|:----------|:--------|
| FC-INVALID-DATE | `X'0000000000000000'` | Date is valid (severity 0) |
| FC-INSUFFICIENT-DATA | `X'000309CB59C3C5C5'` | Insufficient input data |
| FC-BAD-DATE-VALUE | `X'000309CC59C3C5C5'` | Invalid date value |
| FC-INVALID-ERA | `X'000309CD59C3C5C5'` | Invalid era specification |
| FC-UNSUPP-RANGE | `X'000309D159C3C5C5'` | Date outside supported range |
| FC-INVALID-MONTH | `X'000309D559C3C5C5'` | Invalid month value |
| FC-BAD-PIC-STRING | `X'000309D659C3C5C5'` | Invalid format mask |
| FC-NON-NUMERIC-DATA | `X'000309D859C3C5C5'` | Non-numeric data in date |
| FC-YEAR-IN-ERA-ZERO | `X'000309D959C3C5C5'` | Year zero in era |

`Source: app/cbl/CSUTLDTC.cbl:60-70`

**Wrapper Program — CSUTLDTC:**

CSUTLDTC.cbl is a dedicated wrapper program that encapsulates the CEEDAYS call and provides a simplified interface for consuming programs:

```cobol
       PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT.
           ...
           CALL "CEEDAYS" USING
                  WS-DATE-TO-TEST,
                  WS-DATE-FORMAT,
                  OUTPUT-LILLIAN,
                  FEEDBACK-CODE
```

`Source: app/cbl/CSUTLDTC.cbl:88, 116-120`

**CSUTLDTC Linkage Parameters:**

| Parameter | PIC | Direction | Description |
|:----------|:----|:----------|:------------|
| LS-DATE | X(10) | Input | Date to validate |
| LS-DATE-FORMAT | X(10) | Input | Format mask |
| LS-RESULT | X(80) | Output | Formatted result message with severity and status |

**Consuming Programs (via CSUTLDTC wrapper):**

| Program | Source Location | Context |
|:--------|:---------------|:--------|
| CSUTLDTC | `Source: app/cbl/CSUTLDTC.cbl:116` | Direct CEEDAYS caller (wrapper) |
| CORPT00C | `Source: app/cbl/CORPT00C.cbl:392-394` | Transaction report — validates start/end date range |
| COTRN02C | `Source: app/cbl/COTRN02C.cbl:393-395` | Transaction add — validates origination and processing dates |

**Indirect Dependency via Copybook:**

The CSUTLDPY.cpy copybook (`Source: app/cpy/CSUTLDPY.cpy`) provides reusable date validation paragraphs (EDIT-DATE-CCYYMMDD, EDIT-YEAR-CCYY, EDIT-MONTH, EDIT-DAY) that work in conjunction with the CSUTLDTC date validation service.

---

## 2. CICS Transaction Server API Commands

The CICS Transaction Server API provides the runtime environment for all 17 online programs in CardDemo. CICS commands are invoked via `EXEC CICS ... END-EXEC` blocks and fall into six functional categories.

### 2.1 File Control — READ / WRITE / REWRITE / DELETE

| Attribute | Detail |
|:----------|:-------|
| **Command Category** | CICS File Control (Direct Access) |
| **Commands** | `EXEC CICS READ`, `EXEC CICS WRITE`, `EXEC CICS REWRITE`, `EXEC CICS DELETE` |
| **Type** | File Handling / Database Operations |
| **Vendor** | IBM (CICS Transaction Server) |

**Purpose:** Provides random (keyed) access to VSAM KSDS datasets through CICS File Control Table (FCT) entries. Programs read, write, update, and delete individual records using the record's primary key.

**Common Parameters:**

| Parameter | Description |
|:----------|:------------|
| FILE / DATASET | FCT entry name identifying the VSAM file |
| INTO | Receiving data area for READ operations |
| FROM | Source data area for WRITE/REWRITE operations |
| RIDFLD | Record Identification Field (primary key) |
| LENGTH | Record length |
| RESP | Primary response code |
| RESP2 | Secondary response code |
| UPDATE | Lock record for subsequent REWRITE/DELETE |
| KEYLENGTH | Key length for generic key operations |

**VSAM Files Accessed:**

| FCT Name | VSAM Dataset | Record Layout Copybook | Key | Operations |
|:----------|:-------------|:-----------------------|:----|:-----------|
| ACCTDAT | AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS | CVACT01Y | Account ID (11 bytes, RKP 0) | READ, REWRITE |
| CARDDAT | AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS | CVACT02Y | Card Number (16 bytes, RKP 0) | READ, WRITE, REWRITE |
| XREFDAT | AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS | CVACT03Y | Card Number (16 bytes, RKP 0) | READ |
| CRDTRN | AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS | CVTRA05Y | Transaction ID (16 bytes, RKP 0) | READ, WRITE, REWRITE |
| USRSEC | AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS | CSUSR01Y | User ID (8 bytes, RKP 0) | READ, WRITE, REWRITE, DELETE |
| CARDAIX | AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH | CVACT02Y | Account ID (alternate index) | READ |

`Source: app/catlg/LISTCAT.txt:22 (ACCTDATA cluster), app/catlg/LISTCAT.txt:164 (CARDDATA cluster)`

**Consuming Programs (15 programs using direct file access):**

| Program | Files Accessed | Operations |
|:--------|:---------------|:-----------|
| COACTUPC | ACCTDAT, CARDDAT, XREFDAT, CARDAIX | READ, READ UPDATE, REWRITE |
| COACTVWC | ACCTDAT, CARDDAT, XREFDAT | READ |
| COBIL00C | ACCTDAT, CRDTRN | READ, READ UPDATE, REWRITE, WRITE, STARTBR, READPREV, ENDBR |
| COCRDLIC | CARDDAT, CARDAIX | READ, STARTBR, READNEXT, ENDBR |
| COCRDSLC | ACCTDAT, CARDDAT, XREFDAT | READ |
| COCRDUPC | CARDDAT, XREFDAT, ACCTDAT | READ, READ UPDATE, REWRITE |
| CORPT00C | CRDTRN | READ, STARTBR, READNEXT, ENDBR |
| COSGN00C | USRSEC | READ |
| COTRN00C | CRDTRN, CARDDAT | READ, STARTBR, READNEXT, READPREV, ENDBR |
| COTRN01C | CRDTRN | READ |
| COTRN02C | CRDTRN, CARDDAT, XREFDAT, ACCTDAT | READ, WRITE, REWRITE, STARTBR, READNEXT, ENDBR |
| COUSR00C | USRSEC | STARTBR, READNEXT, ENDBR |
| COUSR01C | USRSEC | READ, STARTBR, READNEXT, ENDBR |
| COUSR02C | USRSEC | READ, READ UPDATE, REWRITE, WRITE |
| COUSR03C | USRSEC | READ, READ UPDATE, REWRITE, DELETE |

---

### 2.2 File Control — Browse (STARTBR / READNEXT / READPREV / ENDBR)

| Attribute | Detail |
|:----------|:-------|
| **Command Category** | CICS File Control (Browse/Sequential) |
| **Commands** | `EXEC CICS STARTBR`, `EXEC CICS READNEXT`, `EXEC CICS READPREV`, `EXEC CICS ENDBR` |
| **Type** | File Handling |
| **Vendor** | IBM (CICS Transaction Server) |

**Purpose:** Provides sequential browsing capability over VSAM KSDS records. STARTBR establishes a browse cursor at a specified key position, READNEXT/READPREV retrieve records sequentially forward or backward, and ENDBR releases the browse cursor.

**Common Parameters:**

| Parameter | Description |
|:----------|:------------|
| FILE / DATASET | FCT entry name |
| RIDFLD | Starting key position for browse |
| INTO | Receiving data area |
| LENGTH | Record length |
| KEYLENGTH | Key length for generic positioning |
| GTEQ / EQUAL | Key match mode (greater-than-or-equal vs exact) |
| RESP | Primary response code |
| RESP2 | Secondary response code |

**Consuming Programs (5 programs using browse):**

| Program | Source Location | Files Browsed | Purpose |
|:--------|:---------------|:--------------|:--------|
| COACTVWC | `Source: app/cbl/COACTVWC.cbl:727-826` | ACCTDAT, CARDDAT, XREFDAT | Browse account records for list display |
| COBIL00C | `Source: app/cbl/COBIL00C.cbl:443-503` | CRDTRN | Browse transactions for bill payment history |
| COCRDLIC | `Source: app/cbl/COCRDLIC.cbl:1129-1258` | CARDDAT, CARDAIX | Browse credit cards for list display |
| COTRN00C | `Source: app/cbl/COTRN00C.cbl` | CRDTRN | Browse transactions for list display with forward/backward paging |
| COUSR00C | `Source: app/cbl/COUSR00C.cbl` | USRSEC | Browse user records for admin list display |

---

### 2.3 Program Control — XCTL / RETURN / LINK

| Attribute | Detail |
|:----------|:-------|
| **Command Category** | CICS Program Control |
| **Commands** | `EXEC CICS XCTL`, `EXEC CICS RETURN`, `EXEC CICS LINK` |
| **Type** | Transaction Processing |
| **Vendor** | IBM (CICS Transaction Server) |

**Purpose:** Manages inter-program communication and navigation flow within the CardDemo application. XCTL transfers control to another program (releasing the calling program's resources). RETURN returns control to CICS or the invoking program, optionally specifying the next transaction to execute. LINK invokes a subprogram while retaining the calling program.

**Common Parameters:**

| Parameter | Description |
|:----------|:------------|
| PROGRAM | Target program name |
| COMMAREA | Communication area passed between programs (1024 bytes — see COCOM01Y.cpy) |
| LENGTH | Length of COMMAREA |
| TRANSID | Transaction ID for next pseudo-conversational iteration |

**COMMAREA Structure (COCOM01Y.cpy):**

The COMMAREA is the primary state management mechanism for inter-program communication:

```cobol
       01 CARDDEMO-COMMAREA.
          05 CDEMO-GENERAL-INFO.
             10 CDEMO-FROM-TRANID         PIC X(04).
             10 CDEMO-FROM-PROGRAM        PIC X(08).
             10 CDEMO-TO-TRANID           PIC X(04).
             10 CDEMO-TO-PROGRAM          PIC X(08).
             10 CDEMO-USER-ID             PIC X(08).
             10 CDEMO-USER-TYPE           PIC X(01).
             10 CDEMO-PGM-CONTEXT         PIC 9(01).
          05 CDEMO-CUSTOMER-INFO.
             ...
          05 CDEMO-ACCOUNT-INFO.
             ...
```

`Source: app/cpy/COCOM01Y.cpy:19-47`

**Programs Using XCTL (6 programs):**

| Program | Source Location | Target Programs | Context |
|:--------|:---------------|:----------------|:--------|
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:956` | Dynamic (via CCARD-NEXT-PROG) | Navigation to next screen |
| COACTVWC | `Source: app/cbl/COACTVWC.cbl:349` | Dynamic (via CCARD-NEXT-PROG) | Navigation to next screen |
| COCRDLIC | `Source: app/cbl/COCRDLIC.cbl:402, 538, 566` | COCRDSLC, COCRDUPC | Card detail/update navigation |
| COCRDSLC | `Source: app/cbl/COCRDSLC.cbl:331` | Dynamic (via CCARD-NEXT-PROG) | Navigation to next screen |
| COCRDUPC | `Source: app/cbl/COCRDUPC.cbl:473` | Dynamic (via CCARD-NEXT-PROG) | Navigation to next screen |
| COSGN00C | `Source: app/cbl/COSGN00C.cbl:231, 236` | COMEN01C, COADM01C | Post-login menu routing |

**Programs Using RETURN (all 17 CICS programs):**

Every CICS program in CardDemo uses `EXEC CICS RETURN TRANSID(...)` for pseudo-conversational control flow. The RETURN command ends the current task and specifies which transaction ID should be initiated when the user next presses a key.

| Program | Source Location |
|:--------|:---------------|
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:1015` |
| COACTVWC | `Source: app/cbl/COACTVWC.cbl:402` |
| COADM01C | `Source: app/cbl/COADM01C.cbl:107` |
| COBIL00C | `Source: app/cbl/COBIL00C.cbl:146` |
| COCRDLIC | `Source: app/cbl/COCRDLIC.cbl:615` |
| COCRDSLC | `Source: app/cbl/COCRDSLC.cbl:402` |
| COCRDUPC | `Source: app/cbl/COCRDUPC.cbl:554` |
| COMEN01C | `Source: app/cbl/COMEN01C.cbl:107` |
| CORPT00C | `Source: app/cbl/CORPT00C.cbl:199` |
| COSGN00C | `Source: app/cbl/COSGN00C.cbl:98` |
| COTRN00C | `Source: app/cbl/COTRN00C.cbl:138` |
| COTRN01C | `Source: app/cbl/COTRN01C.cbl:136` |
| COTRN02C | `Source: app/cbl/COTRN02C.cbl:156` |
| COUSR00C | `Source: app/cbl/COUSR00C.cbl:141` |
| COUSR01C | `Source: app/cbl/COUSR01C.cbl:107` |
| COUSR02C | `Source: app/cbl/COUSR02C.cbl:135` |
| COUSR03C | `Source: app/cbl/COUSR03C.cbl:134` |

**Programs Using LINK:**

COMEN01C uses `EXEC CICS XCTL` (not LINK) for menu routing to dynamically transfer control to the program selected by the user from the main menu.

`Source: app/cbl/COMEN01C.cbl:142-165`

---

### 2.4 Terminal Control — SEND MAP / RECEIVE MAP / SEND TEXT

| Attribute | Detail |
|:----------|:-------|
| **Command Category** | CICS Terminal Control (BMS) |
| **Commands** | `EXEC CICS SEND MAP`, `EXEC CICS RECEIVE MAP`, `EXEC CICS SEND TEXT` |
| **Type** | Screen I/O |
| **Vendor** | IBM (CICS Transaction Server) |

**Purpose:** Manages 3270 terminal screen presentation through Basic Mapping Support (BMS). SEND MAP renders a formatted screen using a pre-compiled BMS map. RECEIVE MAP reads user input from the terminal into a data structure. SEND TEXT sends unformatted text to the terminal.

**Common Parameters (SEND MAP):**

| Parameter | Description |
|:----------|:------------|
| MAP | Map name (defined in BMS source via DFHMDI) |
| MAPSET | Mapset name (defined in BMS source via DFHMSD) |
| FROM | Data area containing field values to display |
| CURSOR | Cursor position (field offset or absolute position) |
| ERASE | Erase screen before display |
| DATAONLY | Send only data fields (no constants/labels) |
| MAPONLY | Send only map constants (no data fields) |
| FREEKB | Unlock keyboard after send |

**Common Parameters (RECEIVE MAP):**

| Parameter | Description |
|:----------|:------------|
| MAP | Map name |
| MAPSET | Mapset name |
| INTO | Data area to receive field values |
| RESP | Primary response code |

**Map-to-Program Correspondence:**

| Mapset | Map | Program | Screen Function |
|:-------|:----|:--------|:----------------|
| COSGN00 | COSGN0A | COSGN00C | Signon Screen |
| COMEN01 | COMEN1A | COMEN01C | Main Menu |
| COADM01 | COADM1A | COADM01C | Admin Menu |
| COACTVW | CACTVW0A | COACTVWC | Account View |
| COACTUP | CACTUP0A | COACTUPC | Account Update |
| COCRDLI | CCRDLI0A | COCRDLIC | Credit Card List |
| COCRDSL | CCRDSL0A | COCRDSLC | Credit Card Detail |
| COCRDUP | CCRDUP0A | COCRDUPC | Credit Card Update |
| COTRN00 | COTRN0A | COTRN00C | Transaction List |
| COTRN01 | COTRN1A | COTRN01C | Transaction View |
| COTRN02 | COTRN2A | COTRN02C | Transaction Add |
| CORPT00 | CORPT0A | CORPT00C | Transaction Reports |
| COBIL00 | COBIL0A | COBIL00C | Bill Payment |
| COUSR00 | COUSR0A | COUSR00C | User List |
| COUSR01 | COUSR1A | COUSR01C | User Add |
| COUSR02 | COUSR2A | COUSR02C | User Update |
| COUSR03 | COUSR3A | COUSR03C | User Delete |

**Programs Using SEND MAP / RECEIVE MAP (all 17 CICS programs):**

All 17 CICS online programs use SEND MAP and RECEIVE MAP as their primary user interface mechanism. Each program has at least one send and one receive operation paired with its corresponding BMS map.

**Programs Using SEND TEXT (1 program):**

| Program | Source Location | Purpose |
|:--------|:---------------|:--------|
| COACTVWC | `Source: app/cbl/COACTVWC.cbl:878, 897` | Sends unformatted error/informational text to terminal |

---

### 2.5 System Services — ASSIGN / ASKTIME / FORMATTIME

| Attribute | Detail |
|:----------|:-------|
| **Command Category** | CICS System Services |
| **Commands** | `EXEC CICS ASSIGN`, `EXEC CICS ASKTIME`, `EXEC CICS FORMATTIME` |
| **Type** | System Identification / Date/Time Services |
| **Vendor** | IBM (CICS Transaction Server) |

**Purpose:** ASSIGN retrieves CICS system attributes (application ID, system ID). ASKTIME retrieves the current absolute time. FORMATTIME converts absolute time to formatted date/time strings.

#### ASSIGN

| Parameter | Description |
|:----------|:------------|
| APPLID | Returns the CICS application identifier |
| SYSID | Returns the CICS system identifier |

**Consuming Programs:**

| Program | Source Location | Parameters Retrieved |
|:--------|:---------------|:---------------------|
| COSGN00C | `Source: app/cbl/COSGN00C.cbl:198-204` | APPLID, SYSID — displayed on signon screen |

**Code Example:**

```cobol
           EXEC CICS ASSIGN
               APPLID(APPLIDO OF COSGN0AO)
           END-EXEC

           EXEC CICS ASSIGN
               SYSID(SYSIDO OF COSGN0AO)
           END-EXEC.
```

`Source: app/cbl/COSGN00C.cbl:198-204`

#### ASKTIME / FORMATTIME

| Parameter | Description |
|:----------|:------------|
| ABSTIME | Absolute time value (packed decimal, milliseconds since epoch) |
| YYYYMMDD | Formatted date output in YYYY-MM-DD format |
| DATESEP | Date separator character |
| TIME | Formatted time output in HH:MM:SS format |
| TIMESEP | Time separator character |

**Consuming Programs:**

| Program | Source Location | Purpose |
|:--------|:---------------|:--------|
| COBIL00C | `Source: app/cbl/COBIL00C.cbl:251-261` | Generate current timestamp for bill payment processing |

**Code Example:**

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

---

### 2.6 Transient Data Queue — WRITEQ TD

| Attribute | Detail |
|:----------|:-------|
| **Command Category** | CICS Transient Data (TD) Queue |
| **Command** | `EXEC CICS WRITEQ TD` |
| **Type** | Transaction Processing / Asynchronous Batch Submission |
| **Vendor** | IBM (CICS Transaction Server) |

**Purpose:** Writes records to an extrapartition Transient Data Queue (TDQ). In CardDemo, CORPT00C uses WRITEQ TD to write dynamically generated JCL records to the 'JOBS' TDQ, which is mapped to the JES internal reader (INTRDR). This mechanism allows an online CICS program to trigger asynchronous batch job execution.

**Parameters:**

| Parameter | Description |
|:----------|:------------|
| QUEUE | TDQ name — `'JOBS'` (extrapartition, mapped to JES internal reader) |
| FROM | Data area containing JCL record (80 bytes per JCL line) |
| LENGTH | Record length |
| RESP | Primary response code |
| RESP2 | Secondary response code |

**Consuming Programs:**

| Program | Source Location | Queue | Purpose |
|:--------|:---------------|:------|:--------|
| CORPT00C | `Source: app/cbl/CORPT00C.cbl:517-523` | JOBS | Submit batch report generation JCL to JES |

**Code Example:**

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

---

### 2.7 CICS Abend Handling — HANDLE ABEND / ABEND

| Attribute | Detail |
|:----------|:-------|
| **Command Category** | CICS Abend Handling |
| **Commands** | `EXEC CICS HANDLE ABEND`, `EXEC CICS ABEND` |
| **Type** | Abend Handling |
| **Vendor** | IBM (CICS Transaction Server) |

**Purpose:** HANDLE ABEND establishes an abend exit routine for graceful error recovery in CICS programs. ABEND forces a CICS task abend, optionally producing a transaction dump. These are the CICS-specific equivalents of CEE3ABD for online programs.

**Consuming Programs:**

| Program | Source Location | Usage |
|:--------|:---------------|:------|
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:862, 4218, 4222` | HANDLE ABEND to set handler; ABEND for forced termination |
| COACTVWC | `Source: app/cbl/COACTVWC.cbl:264, 930, 934` | HANDLE ABEND to set handler; ABEND for forced termination |
| COCRDSLC | `Source: app/cbl/COCRDSLC.cbl:250, 871, 875` | HANDLE ABEND to set handler; ABEND for forced termination |
| COCRDUPC | `Source: app/cbl/COCRDUPC.cbl:370, 1546, 1550` | HANDLE ABEND to set handler; ABEND for forced termination |

---

## 3. z/OS Batch Utilities

The CardDemo application relies on four z/OS batch utilities for dataset management, file copying, sorting, and resource allocation. These are invoked via JCL job steps, not from COBOL source code directly.

### 3.1 IDCAMS — Access Method Services

| Attribute | Detail |
|:----------|:-------|
| **Utility Name** | IDCAMS |
| **Full Name** | Integrated Data Catalog Access Method Services |
| **Type** | Database Operations |
| **Invocation** | JCL EXEC PGM=IDCAMS with SYSIN control statements |
| **Vendor** | IBM (z/OS DFSMS) |

**Purpose:** IDCAMS is the primary VSAM dataset management utility. In CardDemo, it is used to define VSAM clusters, load (REPRO) data from sequential files into VSAM datasets, delete datasets, and list catalog entries.

**Key IDCAMS Commands Used:**

| Command | Purpose | Context |
|:--------|:--------|:--------|
| DEFINE CLUSTER | Create VSAM KSDS cluster with key, record size, and space parameters | Initial dataset setup |
| REPRO | Copy records from sequential file to VSAM or VSAM to VSAM | Data loading from PS to KSDS |
| DELETE | Remove VSAM cluster and associated components | Dataset cleanup before reload |
| LISTCAT | Display catalog entries for datasets matching a pattern | Catalog forensic snapshot |
| DEFINE AIX | Define alternate index on VSAM cluster | Transaction file alternate access |
| DEFINE PATH | Define access path through an alternate index | AIX access configuration |
| BLDINDEX | Build alternate index entries from base cluster | AIX population |

**VSAM Clusters Managed (from LISTCAT):**

| Cluster Name | Type | Key Length | Record Length | RKP |
|:-------------|:-----|:-----------|:-------------|:----|
| AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS | KSDS | 11 | 300 | 0 |
| AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS | KSDS | 16 | 150 | 0 |
| AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS | KSDS | 16 | 50 | 0 |
| AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS | KSDS | 9 | 500 | 0 |
| AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS | KSDS | — | 50 | 0 |
| AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS | KSDS | 16 | 350 | 0 |
| AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS | KSDS | 8 | 80 | 0 |
| AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX | AIX | — | — | — |
| AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH | PATH | — | — | — |

`Source: app/catlg/LISTCAT.txt:22, 59 (ACCTDATA), app/catlg/LISTCAT.txt:164, 190 (CARDDATA)`

**Batch Jobs Using IDCAMS:**

| Job Name | IDCAMS Function | Dataset(s) Affected |
|:---------|:----------------|:--------------------|
| DEFVSAM | DEFINE CLUSTER | All VSAM clusters |
| ACCTFILE | DELETE + DEFINE + REPRO | ACCTDATA.VSAM.KSDS |
| CARDFILE | DELETE + DEFINE + REPRO | CARDDATA.VSAM.KSDS |
| XREFFILE | DELETE + DEFINE + REPRO | CARDXREF.VSAM.KSDS |
| CUSTFILE | DELETE + DEFINE + REPRO | CUSTDATA.VSAM.KSDS |
| TCATBALF | DELETE + DEFINE + REPRO | TCATBALF.VSAM.KSDS |
| TRANBKP | DELETE + DEFINE + REPRO | TRANSACT.VSAM.KSDS |
| DISCGRP | DELETE + DEFINE + REPRO | DISCGRP.VSAM.KSDS |
| TRANCATG | DELETE + DEFINE + REPRO | TRANCATG.VSAM.KSDS |
| TRANTYPE | DELETE + DEFINE + REPRO | TRANTYPE.VSAM.KSDS |
| TRANIDX | DEFINE AIX + DEFINE PATH + BLDINDEX | CARDDATA.VSAM.AIX |
| DEFGDGB | DEFINE GDG BASE | GDG base for statement output |

`Source: README.md:235-256`

---

### 3.2 SORT — DFSORT / SyncSort

| Attribute | Detail |
|:----------|:-------|
| **Utility Name** | SORT (DFSORT or SyncSort) |
| **Full Name** | Data Facility Sort / Syncsort MFX |
| **Type** | Sorting |
| **Invocation** | JCL EXEC PGM=SORT with SYSIN control statements |
| **Vendor** | IBM (DFSORT) or Syncsort |

**Purpose:** The SORT utility merges and sorts sequential files based on control field specifications. In CardDemo, it is used by the COMBTRAN job to combine system-generated transactions with daily user transactions into a single consolidated transaction file.

**Batch Jobs Using SORT:**

| Job Name | Source | Function |
|:---------|:-------|:---------|
| COMBTRAN | `Source: README.md:254` | Combine system transactions with daily transactions via file merge/sort |

**Sort Control Statement Pattern (typical):**

```
  SORT FIELDS=(1,16,CH,A)
  MERGE ...
```

The sort key corresponds to the 16-byte transaction ID at position 1, matching the VSAM KSDS key definition for the transaction file.

---

### 3.3 IEBGENER — Sequential Copy Utility

| Attribute | Detail |
|:----------|:-------|
| **Utility Name** | IEBGENER |
| **Full Name** | Sequential Dataset Generator/Copier |
| **Type** | File Handling |
| **Invocation** | JCL EXEC PGM=IEBGENER |
| **Vendor** | IBM (z/OS DFSMSdfp) |

**Purpose:** IEBGENER copies sequential datasets or members. In CardDemo, it is used by the DUSRSECJ job to load the initial user security data from a sequential source into the VSAM user security file.

**Batch Jobs Using IEBGENER:**

| Job Name | Source | Function |
|:---------|:-------|:---------|
| DUSRSECJ | `Source: README.md:237` | Initial load of user security VSAM file from sequential source |

**Typical JCL Pattern:**

```jcl
//STEP1    EXEC PGM=IEBGENER
//SYSUT1   DD DSN=input.sequential.dataset,DISP=SHR
//SYSUT2   DD DSN=output.dataset,DISP=OLD
//SYSIN    DD DUMMY
//SYSPRINT DD SYSOUT=*
```

---

### 3.4 IEFBR14 — Null Program

| Attribute | Detail |
|:----------|:-------|
| **Utility Name** | IEFBR14 |
| **Full Name** | Null Utility Program |
| **Type** | File Handling |
| **Invocation** | JCL EXEC PGM=IEFBR14 |
| **Vendor** | IBM (z/OS) |

**Purpose:** IEFBR14 is a do-nothing program whose sole purpose is to allow JCL DD statement processing (allocation and deallocation of datasets). In CardDemo, it is used by the CLOSEFIL and OPENFIL jobs to close and open VSAM files for CICS without executing any program logic.

**Batch Jobs Using IEFBR14:**

| Job Name | Source | Function |
|:---------|:-------|:---------|
| CLOSEFIL | `Source: README.md:247` | Close VSAM files in CICS (DD DISP=SHR triggers deallocation) |
| OPENFIL | `Source: README.md:252` | Open/make files available to CICS (DD allocation triggers enablement) |

---

## 4. BMS Map Macros (DFHMSD / DFHMDI / DFHMDF)

### 4.1 Overview

| Attribute | Detail |
|:----------|:-------|
| **Macro Names** | DFHMSD, DFHMDI, DFHMDF |
| **Full Name** | BMS (Basic Mapping Support) Map Definition Macros |
| **Type** | Screen I/O / Data Transformation |
| **Vendor** | IBM (CICS Transaction Server) |

**Purpose:** BMS macros define the 3270 terminal screen layout used by CICS programs. DFHMSD defines a mapset (collection of maps). DFHMDI defines an individual map (screen). DFHMDF defines individual screen fields with their position, length, attributes, color, and validation characteristics.

### 4.2 BMS Macro Hierarchy

```
DFHMSD (Mapset Definition)
  └── DFHMDI (Map/Screen Definition)
        └── DFHMDF (Field Definition)
              ├── POS=(row,col)         — Screen position
              ├── LENGTH=n              — Field length
              ├── ATTRB=(attributes)    — Field attributes
              ├── COLOR=color           — Field color
              ├── HILIGHT=hilight       — Highlight style
              ├── PICIN/PICOUT=mask     — Input/output picture
              └── INITIAL='text'        — Default value
```

### 4.3 Common DFHMSD Properties

All 17 BMS source files use these common mapset-level properties:

| Property | Value | Description |
|:---------|:------|:------------|
| LANG | COBOL | Generate COBOL copybook data structures |
| MODE | INOUT | Support both input and output operations |
| STORAGE | AUTO | Automatic storage allocation |
| TIOAPFX | YES | Include Terminal I/O Area prefix |
| TYPE | &&SYSPARM | Parameterized assembly (MAP or DSECT) |

**Example DFHMSD Declaration:**

```
COACTUP DFHMSD LANG=COBOL,                                             -
               MODE=INOUT,                                              -
               STORAGE=AUTO,                                            -
               TIOAPFX=YES,                                             -
               TYPE=&&SYSPARM
```

`Source: app/bms/COACTUP.bms:20-24`

### 4.4 DFHMDF Field Attributes

| Attribute Value | Description |
|:----------------|:------------|
| ASKIP | Auto-skip (protected, cursor skips over) |
| PROT | Protected (display only) |
| UNPROT | Unprotected (user can enter data) |
| NUM | Numeric-only input |
| BRT | Bright (high intensity) |
| DRK | Dark (hidden, e.g., passwords) |
| IC | Insert Cursor (initial cursor position) |
| FSET | Field Set (always transmit, even if unchanged) |

### 4.5 BMS Source File Inventory

| BMS Source | Mapset Name | Map(s) | Consuming Program | Screen Function |
|:-----------|:------------|:-------|:-------------------|:----------------|
| `app/bms/COACTUP.bms` | COACTUP | CACTUPAI/O | COACTUPC | Account Update |
| `app/bms/COACTVW.bms` | COACTVW | CACTVWAI/O | COACTVWC | Account View |
| `app/bms/COADM01.bms` | COADM01 | COADM1AI/O | COADM01C | Admin Menu |
| `app/bms/COBIL00.bms` | COBIL00 | COBIL0AI/O | COBIL00C | Bill Payment |
| `app/bms/COCRDLI.bms` | COCRDLI | CCRDLIAI/O | COCRDLIC | Credit Card List |
| `app/bms/COCRDSL.bms` | COCRDSL | CCRDSLAI/O | COCRDSLC | Credit Card Detail |
| `app/bms/COCRDUP.bms` | COCRDUP | CCRDUPAI/O | COCRDUPC | Credit Card Update |
| `app/bms/COMEN01.bms` | COMEN01 | COMEN1AI/O | COMEN01C | Main Menu |
| `app/bms/CORPT00.bms` | CORPT00 | CORPT0AI/O | CORPT00C | Transaction Reports |
| `app/bms/COSGN00.bms` | COSGN00 | COSGN0AI/O | COSGN00C | Signon Screen |
| `app/bms/COTRN00.bms` | COTRN00 | COTRN0AI/O | COTRN00C | Transaction List |
| `app/bms/COTRN01.bms` | COTRN01 | COTRN1AI/O | COTRN01C | Transaction View |
| `app/bms/COTRN02.bms` | COTRN02 | COTRN2AI/O | COTRN02C | Transaction Add |
| `app/bms/COUSR00.bms` | COUSR00 | COUSR0AI/O | COUSR00C | User List |
| `app/bms/COUSR01.bms` | COUSR01 | COUSR1AI/O | COUSR01C | User Add |
| `app/bms/COUSR02.bms` | COUSR02 | COUSR2AI/O | COUSR02C | User Update |
| `app/bms/COUSR03.bms` | COUSR03 | COUSR3AI/O | COUSR03C | User Delete |

**Total:** 17 BMS source files defining screen contracts for the CardDemo user interface on the 3270 terminal (24×80 character grid).

---

## 5. IBM Proprietary Copybooks

### 5.1 DFHBMSCA — BMS Character Attribute Set

| Attribute | Detail |
|:----------|:-------|
| **Copybook Name** | DFHBMSCA |
| **Full Name** | BMS Character Attribute Set Constants |
| **Type** | Screen I/O |
| **Vendor** | IBM (CICS Transaction Server) |
| **Location** | IBM-provided system copybook (not in application repository — supplied by CICS TS installation) |

**Purpose:** DFHBMSCA defines symbolic constants for BMS screen field attributes. These constants are used programmatically to set field colors, protection status, intensity, and other display characteristics at runtime.

**Key Constants Defined:**

| Constant Name | Purpose |
|:--------------|:--------|
| DFHBMPRF | Protected field attribute |
| DFHBMPRO | Protected, auto-skip |
| DFHBMASF | Auto-skip field |
| DFHBMUNP | Unprotected field |
| DFHBMUNN | Unprotected, numeric |
| DFHBMASB | Auto-skip, bright |
| DFHBMPFB | Protected, bright |
| DFHRED | Red color |
| DFHGREEN | Green color |
| DFHYELLO | Yellow color |
| DFHBLUE | Blue color |
| DFHTURQ | Turquoise color |
| DFHPINK | Pink color |
| DFHNEUTR | Neutral (default) color |
| DFHDFCOL | Default color |

**Usage via CSSETATY.cpy:**

The CSSETATY.cpy copybook (`Source: app/cpy/CSSETATY.cpy`) provides a reusable pattern for setting field attributes using DFHBMSCA constants. It uses a template pattern with replaceable tokens (TESTVAR1, SCRNVAR2, MAPNAME3) to conditionally set fields to red when validation errors occur:

```cobol
       *    Set (TESTVAR1) to red if in error
            IF (FLG-(TESTVAR1)-NOT-OK
            OR  FLG-(TESTVAR1)-BLANK)
            AND CDEMO-PGM-REENTER
                MOVE DFHRED             TO
                     (SCRNVAR2)C OF (MAPNAME3)O
```

`Source: app/cpy/CSSETATY.cpy:17-26`

**Programs Copying DFHBMSCA (17 CICS programs):**

| Program | Source Location |
|:--------|:---------------|
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:615` |
| COACTVWC | `Source: app/cbl/COACTVWC.cbl:221` |
| COADM01C | `Source: app/cbl/COADM01C.cbl:61` |
| COBIL00C | `Source: app/cbl/COBIL00C.cbl:85` |
| COCRDLIC | `Source: app/cbl/COCRDLIC.cbl:267` |
| COCRDSLC | `Source: app/cbl/COCRDSLC.cbl:208` |
| COCRDUPC | `Source: app/cbl/COCRDUPC.cbl:327` |
| COMEN01C | `Source: app/cbl/COMEN01C.cbl:61` |
| CORPT00C | `Source: app/cbl/CORPT00C.cbl:149` |
| COSGN00C | `Source: app/cbl/COSGN00C.cbl:58` |
| COTRN00C | `Source: app/cbl/COTRN00C.cbl:81` |
| COTRN01C | `Source: app/cbl/COTRN01C.cbl:72` |
| COTRN02C | `Source: app/cbl/COTRN02C.cbl:93` |
| COUSR00C | `Source: app/cbl/COUSR00C.cbl:84` |
| COUSR01C | `Source: app/cbl/COUSR01C.cbl:56` |
| COUSR02C | `Source: app/cbl/COUSR02C.cbl:68` |
| COUSR03C | `Source: app/cbl/COUSR03C.cbl:68` |

---

### 5.2 DFHAID — Attention Identifier Key Definitions

| Attribute | Detail |
|:----------|:-------|
| **Copybook Name** | DFHAID |
| **Full Name** | Attention Identifier Byte Definitions |
| **Type** | Screen I/O |
| **Vendor** | IBM (CICS Transaction Server) |
| **Location** | IBM-provided system copybook (not in application repository — supplied by CICS TS installation) |

**Purpose:** DFHAID defines symbolic constants for 3270 terminal Attention Identifier (AID) keys. These are compared against EIBAID (the Execute Interface Block AID byte) to determine which key the user pressed to submit the screen.

**Key Constants Defined:**

| Constant Name | Key | Hex Value |
|:--------------|:----|:----------|
| DFHENTER | Enter key | X'7D' |
| DFHCLEAR | Clear key | X'6D' |
| DFHPA1 | PA1 key | X'6C' |
| DFHPA2 | PA2 key | X'6E' |
| DFHPF1 | PF1/F1 key | X'F1' |
| DFHPF2 | PF2/F2 key | X'F2' |
| DFHPF3 | PF3/F3 key | X'F3' |
| ... | PF4–PF12 | ... |
| DFHPF12 | PF12/F12 key | X'7C' |
| DFHPF13–DFHPF24 | PF13–PF24 keys | Various |

**Usage via CSSTRPFY.cpy:**

The CSSTRPFY.cpy copybook (`Source: app/cpy/CSSTRPFY.cpy`) provides a reusable paragraph that maps the EIBAID byte to internal COMMAREA flag values using DFHAID constants:

```cobol
       YYYY-STORE-PFKEY.
            EVALUATE TRUE
              WHEN EIBAID IS EQUAL TO DFHENTER
                SET CCARD-AID-ENTER TO TRUE
              WHEN EIBAID IS EQUAL TO DFHCLEAR
                SET CCARD-AID-CLEAR TO TRUE
              WHEN EIBAID IS EQUAL TO DFHPF1
                SET CCARD-AID-PFK01 TO TRUE
              ...
            END-EVALUATE
```

`Source: app/cpy/CSSTRPFY.cpy:17-78`

**Programs Copying DFHAID (17 CICS programs — 34 COPY statements total):**

| Program | Source Location |
|:--------|:---------------|
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:616` |
| COACTVWC | `Source: app/cbl/COACTVWC.cbl:222` |
| COADM01C | `Source: app/cbl/COADM01C.cbl:60` |
| COBIL00C | `Source: app/cbl/COBIL00C.cbl:84` |
| COCRDLIC | `Source: app/cbl/COCRDLIC.cbl:268` |
| COCRDSLC | `Source: app/cbl/COCRDSLC.cbl:209` |
| COCRDUPC | `Source: app/cbl/COCRDUPC.cbl:328` |
| COMEN01C | `Source: app/cbl/COMEN01C.cbl:60` |
| CORPT00C | `Source: app/cbl/CORPT00C.cbl:148` |
| COSGN00C | `Source: app/cbl/COSGN00C.cbl:57` |
| COTRN00C | `Source: app/cbl/COTRN00C.cbl:80` |
| COTRN01C | `Source: app/cbl/COTRN01C.cbl:71` |
| COTRN02C | `Source: app/cbl/COTRN02C.cbl:92` |
| COUSR00C | `Source: app/cbl/COUSR00C.cbl:83` |
| COUSR01C | `Source: app/cbl/COUSR01C.cbl:55` |
| COUSR02C | `Source: app/cbl/COUSR02C.cbl:67` |
| COUSR03C | `Source: app/cbl/COUSR03C.cbl:67` |

---

## 6. COBOL Intrinsic Functions (Migration-Relevant)

While COBOL intrinsic functions are part of the COBOL language standard (not IBM-proprietary), they require explicit mapping to Java equivalents during migration. The following functions are used across the CardDemo codebase.

### 6.1 FUNCTION CURRENT-DATE

| Attribute | Detail |
|:----------|:-------|
| **Function** | CURRENT-DATE |
| **Type** | Date/Time Services |
| **Standard** | COBOL 85 / COBOL 2002 |
| **Returns** | 21-character alphanumeric: YYYYMMDDHHMMSSFFGMMMM (date, time, GMT offset) |

**Consuming Programs:**

| Program | Source Location | Context |
|:--------|:---------------|:--------|
| CBACT01C | `Source: app/cbl/CBACT01C.cbl` | Batch timestamp for account processing |
| CBACT04C | `Source: app/cbl/CBACT04C.cbl:614` | Generate DB2-format timestamp for interest calculation |
| CBTRN02C | `Source: app/cbl/CBTRN02C.cbl:693` | Generate DB2-format timestamp for transaction posting |
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:2671, 2678` | Current date for screen display header |
| COACTVWC | `Source: app/cbl/COACTVWC.cbl:434, 441` | Current date for screen display header |
| COADM01C | `Source: app/cbl/COADM01C.cbl:204` | Current date for admin menu header |
| COBIL00C | `Source: app/cbl/COBIL00C.cbl:321` | Current date for bill payment screen |
| COCRDLIC | `Source: app/cbl/COCRDLIC.cbl:645, 652` | Current date for credit card list header |
| COCRDSLC | `Source: app/cbl/COCRDSLC.cbl:430, 437` | Current date for credit card detail header |
| COCRDUPC | `Source: app/cbl/COCRDUPC.cbl:1055, 1062` | Current date for credit card update header |
| COMEN01C | `Source: app/cbl/COMEN01C.cbl:214` | Current date for main menu header |
| CORPT00C | `Source: app/cbl/CORPT00C.cbl:215, 241, 611` | Current date for report date range defaulting and header |
| COSGN00C | `Source: app/cbl/COSGN00C.cbl:179` | Current date for signon screen |
| COTRN00C | `Source: app/cbl/COTRN00C.cbl:569` | Current date for transaction list header |
| COTRN01C | `Source: app/cbl/COTRN01C.cbl:245` | Current date for transaction view header |
| COTRN02C | `Source: app/cbl/COTRN02C.cbl:554` | Current date for transaction add header |
| COUSR00C | `Source: app/cbl/COUSR00C.cbl:564` | Current date for user list header |
| COUSR01C | `Source: app/cbl/COUSR01C.cbl:216` | Current date for user add header |
| COUSR02C | `Source: app/cbl/COUSR02C.cbl:298` | Current date for user update header |
| COUSR03C | `Source: app/cbl/COUSR03C.cbl:245` | Current date for user delete header |

---

### 6.2 FUNCTION MOD

| Attribute | Detail |
|:----------|:-------|
| **Function** | MOD |
| **Type** | Data Transformation |
| **Standard** | COBOL 85 |
| **Returns** | Remainder of integer division |

**Consuming Programs:**

| Program | Source Location | Context |
|:--------|:---------------|:--------|
| CBTRN03C | `Source: app/cbl/CBTRN03C.cbl:282` | Page break calculation — `FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)` |
| CBACT04C | `Source: app/cbl/CBACT04C.cbl` | Calculation operations in interest processing |

---

### 6.3 FUNCTION TEST-NUMVAL-C / NUMVAL-C

| Attribute | Detail |
|:----------|:-------|
| **Functions** | TEST-NUMVAL-C, NUMVAL-C |
| **Type** | Data Transformation |
| **Standard** | COBOL 2002 (TEST-NUMVAL-C), COBOL 85 (NUMVAL-C) |
| **Returns** | TEST-NUMVAL-C: 0 if valid numeric, non-zero otherwise. NUMVAL-C: Numeric value with currency sign and comma handling |

**Purpose:** TEST-NUMVAL-C validates whether a string contains a valid numeric value (with optional currency signs and commas). NUMVAL-C converts such a validated string to a numeric value. In CardDemo, these are used by COACTUPC for validating and converting currency amount input fields.

**Consuming Programs:**

| Program | Source Location | Context |
|:--------|:---------------|:--------|
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:1078-1136` | Validate and convert credit limit, cash limit, current balance, cycle credit, and cycle debit fields |
| CORPT00C | `Source: app/cbl/CORPT00C.cbl:305-325` | Validate and convert report parameter numeric fields |
| COTRN02C | `Source: app/cbl/COTRN02C.cbl:383, 456` | Validate and convert transaction amount |

---

### 6.4 FUNCTION UPPER-CASE

| Attribute | Detail |
|:----------|:-------|
| **Function** | UPPER-CASE |
| **Type** | Data Transformation |
| **Standard** | COBOL 2002 |
| **Returns** | Input string converted to uppercase |

**Consuming Programs:**

| Program | Source Location | Context |
|:--------|:---------------|:--------|
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:1685-1766` | Case-insensitive comparison of old vs new field values for change detection |
| COCRDUPC | `Source: app/cbl/COCRDUPC.cbl:680-681` | Case-insensitive card data comparison |
| COSGN00C | `Source: app/cbl/COSGN00C.cbl:132, 135` | Normalize user ID and password to uppercase before authentication |

---

### 6.5 FUNCTION TRIM

| Attribute | Detail |
|:----------|:-------|
| **Function** | TRIM |
| **Type** | Data Transformation |
| **Standard** | COBOL 2002 |
| **Returns** | Input string with leading and/or trailing spaces removed |

**Consuming Programs:**

| Program | Source Location | Context |
|:--------|:---------------|:--------|
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:1698-1700` | Trim whitespace before comparison in change detection logic |
| COCRDUPC | `Source: app/cbl/COCRDUPC.cbl:828` | Check trimmed card name length for validation |
| CSUTLDPY | `Source: app/cpy/CSUTLDPY.cpy:36-100` | Trim variable names in date validation error messages |

---

### 6.6 FUNCTION INTEGER-OF-DATE

| Attribute | Detail |
|:----------|:-------|
| **Function** | INTEGER-OF-DATE |
| **Type** | Date/Time Services |
| **Standard** | COBOL 85 |
| **Returns** | Integer day count from an internal reference date |

**Consuming Programs:**

| Program | Source Location | Context |
|:--------|:---------------|:--------|
| CORPT00C | `Source: app/cbl/CORPT00C.cbl:230` | Date arithmetic — compute prior day for default date range |

---

### 6.7 FUNCTION NUMVAL

| Attribute | Detail |
|:----------|:-------|
| **Function** | NUMVAL |
| **Type** | Data Transformation |
| **Standard** | COBOL 85 |
| **Returns** | Numeric value from a simple numeric string (no currency/comma) |

**Consuming Programs:**

| Program | Source Location | Context |
|:--------|:---------------|:--------|
| COTRN02C | `Source: app/cbl/COTRN02C.cbl:204, 218` | Convert account ID and card number from screen input to numeric |
| COACTUPC | `Source: app/cbl/COACTUPC.cbl:2156` | Convert alphanumeric-only validation field to numeric |

---

## 7. Program-to-Utility Cross-Reference Matrix

The following matrix maps all 28 COBOL programs to their proprietary utility dependencies. Programs are grouped by execution context: batch programs (CB*, CS*) and online CICS programs (CO*).

**Legend:**

| Symbol | Meaning |
|:-------|:--------|
| ✓ | Utility is used by this program |
| — | Utility is not used by this program |

### 7.1 Batch Programs

| Program | CEE3ABD | CEEDAYS | CBSTM03B | VSAM File I/O | CURRENT-DATE | MOD | NUMVAL-C |
|:--------|:-------:|:-------:|:--------:|:-------------:|:------------:|:---:|:--------:|
| CBACT01C | ✓ | — | — | ✓ | ✓ | — | — |
| CBACT02C | ✓ | — | — | ✓ | — | — | — |
| CBACT03C | ✓ | — | — | ✓ | — | — | — |
| CBACT04C | ✓ | — | — | ✓ | ✓ | ✓ | — |
| CBCUS01C | ✓ | — | — | ✓ | — | — | — |
| CBSTM03A | ✓ | — | ✓ | ✓ | — | — | — |
| CBSTM03B | — | — | — | ✓ | — | — | — |
| CBTRN01C | ✓ | — | — | ✓ | — | — | — |
| CBTRN02C | ✓ | — | — | ✓ | ✓ | — | — |
| CBTRN03C | ✓ | — | — | ✓ | — | ✓ | — |
| CSUTLDTC | — | ✓ | — | — | — | — | — |

### 7.2 Online CICS Programs

| Program | CICS File Ctrl | CICS Browse | CICS XCTL | CICS RETURN | SEND/RECV MAP | ASSIGN | ASKTIME | WRITEQ TD | HANDLE ABEND | CSUTLDTC | DFHAID | DFHBMSCA | CURRENT-DATE | UPPER-CASE | TRIM | TEST-NUMVAL-C | NUMVAL-C | NUMVAL | INTEGER-OF-DATE |
|:--------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| COACTUPC | ✓ | — | ✓ | ✓ | ✓ | — | — | — | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| COACTVWC | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — | ✓ | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COADM01C | — | — | — | ✓ | ✓ | — | — | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COBIL00C | ✓ | ✓ | — | ✓ | ✓ | — | ✓ | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COCRDLIC | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COCRDSLC | ✓ | — | ✓ | ✓ | ✓ | — | — | — | ✓ | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COCRDUPC | ✓ | — | ✓ | ✓ | ✓ | — | — | — | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — | — |
| COMEN01C | — | — | — | ✓ | ✓ | — | — | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| CORPT00C | ✓ | ✓ | — | ✓ | ✓ | — | — | ✓ | — | ✓ | ✓ | ✓ | ✓ | — | — | — | ✓ | — | ✓ |
| COSGN00C | ✓ | — | ✓ | ✓ | ✓ | ✓ | — | — | — | — | ✓ | ✓ | ✓ | ✓ | — | — | — | — | — |
| COTRN00C | ✓ | ✓ | — | ✓ | ✓ | — | — | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COTRN01C | ✓ | — | — | ✓ | ✓ | — | — | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COTRN02C | ✓ | ✓ | — | ✓ | ✓ | — | — | — | — | ✓ | ✓ | ✓ | ✓ | — | — | — | ✓ | ✓ | — |
| COUSR00C | — | ✓ | — | ✓ | ✓ | — | — | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COUSR01C | ✓ | ✓ | — | ✓ | ✓ | — | — | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COUSR02C | ✓ | — | — | ✓ | ✓ | — | — | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |
| COUSR03C | ✓ | — | — | ✓ | ✓ | — | — | — | — | — | ✓ | ✓ | ✓ | — | — | — | — | — | — |

### 7.3 JCL Batch Jobs (Utility Programs)

| Job Name | IDCAMS | SORT | IEBGENER | IEFBR14 | COBOL Program |
|:---------|:------:|:----:|:--------:|:-------:|:-------------:|
| DEFVSAM | ✓ | — | — | — | — |
| DEFGDGB | ✓ | — | — | — | — |
| ACCTFILE | ✓ | — | — | — | — |
| CARDFILE | ✓ | — | — | — | — |
| CUSTFILE | ✓ | — | — | — | — |
| XREFFILE | ✓ | — | — | — | — |
| TRANFILE | ✓ | — | — | — | — |
| DISCGRP | ✓ | — | — | — | — |
| TCATBALF | ✓ | — | — | — | — |
| TRANCATG | ✓ | — | — | — | — |
| TRANTYPE | ✓ | — | — | — | — |
| TRANBKP | ✓ | — | — | — | — |
| TRANIDX | ✓ | — | — | — | — |
| DUSRSECJ | — | — | ✓ | — | — |
| CLOSEFIL | — | — | — | ✓ | — |
| OPENFIL | — | — | — | ✓ | — |
| POSTTRAN | — | — | — | — | CBTRN02C |
| INTCALC | — | — | — | — | CBACT04C |
| COMBTRAN | — | ✓ | — | — | — |
| CREASTMT | — | — | — | — | CBSTM03A |

`Source: README.md:235-256`

---

## 8. Utility Dependency Diagram

For a visual representation of the program-to-utility dependency relationships, see the Mermaid dependency graph:

➡️ [Utility Dependency Map Diagram](diagrams/utility-dependency-map.md)

---

## 9. Summary Statistics

| Category | Distinct Utilities/Commands | Programs Affected | Call Sites |
|:---------|:---------------------------|:------------------|:-----------|
| IBM LE Runtime Services | 2 (CEE3ABD, CEEDAYS) | 10 programs | 10 |
| CICS File Control (Direct) | 4 (READ, WRITE, REWRITE, DELETE) | 15 programs | 60+ |
| CICS File Control (Browse) | 4 (STARTBR, READNEXT, READPREV, ENDBR) | 5 programs | 20+ |
| CICS Program Control | 3 (XCTL, RETURN, LINK) | 17 programs | 35+ |
| CICS Terminal Control | 3 (SEND MAP, RECEIVE MAP, SEND TEXT) | 17 programs | 40+ |
| CICS System Services | 3 (ASSIGN, ASKTIME, FORMATTIME) | 2 programs | 4 |
| CICS TDQ | 1 (WRITEQ TD) | 1 program | 1 |
| CICS Abend Handling | 2 (HANDLE ABEND, ABEND) | 4 programs | 8 |
| z/OS Batch Utilities | 4 (IDCAMS, SORT, IEBGENER, IEFBR14) | 20 JCL jobs | 20+ |
| BMS Map Macros | 3 (DFHMSD, DFHMDI, DFHMDF) | 17 BMS sources | 17 mapsets |
| IBM Proprietary Copybooks | 2 (DFHBMSCA, DFHAID) | 17 CICS programs | 34 COPY statements |
| COBOL Intrinsic Functions | 7 (CURRENT-DATE, MOD, TEST-NUMVAL-C, NUMVAL-C, NUMVAL, UPPER-CASE, TRIM, INTEGER-OF-DATE) | 21 programs | 80+ |
| **Total** | **38 distinct utility types** | **28 programs + 20 JCL jobs** | **330+ call sites** |

---

*Document generated from analysis of the AWS CardDemo application codebase. All source citations reference files in the repository relative to the project root.*

*Last updated: 2025*
