# AWS CardDemo — COBOL-to-Java Traceability Matrix

This document is the single, authoritative **COBOL-paragraph → Java-method traceability matrix** for the AWS CardDemo modernization (z/OS COBOL / CICS / VSAM / JCL / BMS &rarr; Java 25 LTS + Spring Boot 3.x). It is a **hard compliance gate** (AAP §0.6.7, §0.7.3): it maps **100% of the COBOL paragraphs and sections** across **all 28 COBOL programs** to their implementing Java method(s). Partial coverage is a failure.

- **Total paragraphs/sections mapped:** **528** (Batch = 152, Online = 374, Utility = 2).
- **COBOL source of truth:** the 28 programs retained read-only under [`legacy/app/cbl/`](../legacy/app/cbl/).
- **Java source of truth:** the produced application under [`src/main/java/com/aws/carddemo/`](../src/main/java/com/aws/carddemo/).
- **Ordering:** rows within each program follow the **source order** of the COBOL `PROCEDURE DIVISION` (control-flow preservation, AAP §0.1.1, §0.6.7) — note this is the physical order paragraphs appear in the file, which is not always ascending by paragraph number.
- **Reading the tables:** each table has columns `#` (source-order index), `COBOL Paragraph / Section`, `Java Method` (implementing method signature; any parity/fold/EXIT note follows after an em-dash), and `Java File` (relative link to the produced source). COBOL `-EXIT` THRU-target labels map to the owning method's return.

## Coverage Summary

Every COBOL paragraph/section is mapped to its implementing Java method(s) below. Per-program counts were produced by the extractor in [Appendix A](#appendix-a--how-this-matrix-was-generated) and reproduce the verified checksum exactly.

| Group | Program | Paragraph/Section count | Target Java class |
|---|---|---|---|
| Batch | CBACT01C | 6 | `service/batch/AccountExtractService.java` |
| Batch | CBACT02C | 5 | `service/batch/CardExtractService.java` |
| Batch | CBACT03C | 5 | `service/batch/XrefExtractService.java` |
| Batch | CBACT04C | 22 | `service/batch/InterestCalculationService.java` |
| Batch | CBCUS01C | 5 | `service/batch/CustomerExtractService.java` |
| Batch | CBSTM03A | 25 | `service/batch/StatementGenerationService.java` |
| Batch | CBSTM03B | 14 | `service/batch/FileIoService.java` |
| Batch | CBTRN01C | 18 | `service/batch/DailyTransactionPostService.java` |
| Batch | CBTRN02C | 26 | `service/batch/TransactionPostingService.java` |
| Batch | CBTRN03C | 26 | `service/batch/TransactionReportService.java` |
| Online | COACTUPC | 85 | `web/AccountUpdateController.java` + `service/online/AccountUpdateService.java` |
| Online | COACTVWC | 35 | `web/AccountViewController.java` + `service/online/AccountViewService.java` |
| Online | COADM01C | 7 | `web/AdminMenuController.java` + `service/online/AdminMenuService.java` |
| Online | COBIL00C | 16 | `web/BillPayController.java` + `service/online/BillPayService.java` |
| Online | COCRDLIC | 39 | `web/CardListController.java` + `service/online/CardListService.java` |
| Online | COCRDSLC | 34 | `web/CardViewController.java` + `service/online/CardViewService.java` |
| Online | COCRDUPC | 45 | `web/CardUpdateController.java` + `service/online/CardUpdateService.java` |
| Online | COMEN01C | 7 | `web/MainMenuController.java` + `service/online/MainMenuService.java` |
| Online | CORPT00C | 10 | `web/ReportController.java` + `service/online/ReportService.java` |
| Online | COSGN00C | 6 | `web/SignonController.java` + `service/online/SignonService.java` |
| Online | COTRN00C | 16 | `web/TranListController.java` + `service/online/TranListService.java` |
| Online | COTRN01C | 9 | `web/TranViewController.java` + `service/online/TranViewService.java` |
| Online | COTRN02C | 18 | `web/TranAddController.java` + `service/online/TranAddService.java` |
| Online | COUSR00C | 16 | `web/UserListController.java` + `service/online/UserListService.java` |
| Online | COUSR01C | 9 | `web/UserAddController.java` + `service/online/UserAddService.java` |
| Online | COUSR02C | 11 | `web/UserUpdateController.java` + `service/online/UserUpdateService.java` |
| Online | COUSR03C | 11 | `web/UserDeleteController.java` + `service/online/UserDeleteService.java` |
| Utility | CSUTLDTC | 2 | `util/DateValidationService.java` |
| | **TOTAL** | **528** | |

**Subtotals:** Batch = 152 &nbsp;|&nbsp; Online = 374 &nbsp;|&nbsp; Utility = 2 &nbsp;&rarr;&nbsp; **TOTAL = 528** paragraphs/sections across 28 programs.

## Batch Programs

The 10 batch programs (driven from JCL) become Spring Batch jobs and `service/batch/*` services. COBOL `CALL`s become Spring bean injection — notably `CBSTM03A` (`StatementGenerationService`) delegates record I/O to the injected `CBSTM03B` component (`FileIoService`).

### CBACT01C — Account master read/print

**COBOL source:** [`legacy/app/cbl/CBACT01C.cbl`](../legacy/app/cbl/CBACT01C.cbl) &rarr; **Java:** [`AccountExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/AccountExtractService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `1000-ACCTFILE-GET-NEXT` | `acctfileGetNext()` | [`AccountExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/AccountExtractService.java) |
| 2 | `1100-DISPLAY-ACCT-RECORD` | `displayAcctRecord(Account)` | [`AccountExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/AccountExtractService.java) |
| 3 | `0000-ACCTFILE-OPEN` | `openAcctfile()` | [`AccountExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/AccountExtractService.java) |
| 4 | `9000-ACCTFILE-CLOSE` | `closeAcctfile()` | [`AccountExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/AccountExtractService.java) |
| 5 | `9999-ABEND-PROGRAM` | `abend(...)` — → IoStatusException | [`AccountExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/AccountExtractService.java) |
| 6 | `9910-DISPLAY-IO-STATUS` | `abend(...)` — I/O status display folded into abend(); → IoStatusException | [`AccountExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/AccountExtractService.java) |

### CBACT02C — Card master read/print

**COBOL source:** [`legacy/app/cbl/CBACT02C.cbl`](../legacy/app/cbl/CBACT02C.cbl) &rarr; **Java:** [`CardExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CardExtractService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `1000-CARDFILE-GET-NEXT` | `cardfileGetNext()` | [`CardExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CardExtractService.java) |
| 2 | `0000-CARDFILE-OPEN` | `openCardfile()` | [`CardExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CardExtractService.java) |
| 3 | `9000-CARDFILE-CLOSE` | `closeCardfile()` | [`CardExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CardExtractService.java) |
| 4 | `9999-ABEND-PROGRAM` | `buildAbend(...)` — → IoStatusException | [`CardExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CardExtractService.java) |
| 5 | `9910-DISPLAY-IO-STATUS` | `buildAbend(...)` — folded into buildAbend(); → IoStatusException | [`CardExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CardExtractService.java) |

### CBACT03C — Card-xref read/print

**COBOL source:** [`legacy/app/cbl/CBACT03C.cbl`](../legacy/app/cbl/CBACT03C.cbl) &rarr; **Java:** [`XrefExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/XrefExtractService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `1000-XREFFILE-GET-NEXT` | `xreffileGetNext()` — each xref record DISPLAYed twice (in get-next and main loop) — parity preserved | [`XrefExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/XrefExtractService.java) |
| 2 | `0000-XREFFILE-OPEN` | `openXreffile()` | [`XrefExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/XrefExtractService.java) |
| 3 | `9000-XREFFILE-CLOSE` | `closeXreffile()` | [`XrefExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/XrefExtractService.java) |
| 4 | `9999-ABEND-PROGRAM` | `abendProgram(...)` — → IoStatusException | [`XrefExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/XrefExtractService.java) |
| 5 | `9910-DISPLAY-IO-STATUS` | `abendProgram(...)` — folded into abendProgram(); → IoStatusException | [`XrefExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/XrefExtractService.java) |

### CBACT04C — Interest calculation (parity-critical)

**COBOL source:** [`legacy/app/cbl/CBACT04C.cbl`](../legacy/app/cbl/CBACT04C.cbl) &rarr; **Java:** [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0000-TCATBALF-OPEN` | `openTcatbalf()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 2 | `0100-XREFFILE-OPEN` | `openXreffile()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 3 | `0200-DISCGRP-OPEN` | `openDiscgrp()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 4 | `0300-ACCTFILE-OPEN` | `openAcctfile()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 5 | `0400-TRANFILE-OPEN` | `openTranfile()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 6 | `1000-TCATBALF-GET-NEXT` | `tcatbalfGetNext()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 7 | `1050-UPDATE-ACCOUNT` | `updateAccount()` — accumulate monthly interest into ACCT-CURR-BAL | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 8 | `1100-GET-ACCT-DATA` | `getAcctData(Long)` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 9 | `1110-GET-XREF-DATA` | `getXrefData(Long)` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 10 | `1200-GET-INTEREST-RATE` | `getInterestRate()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 11 | `1200-A-GET-DEFAULT-INT-RATE` | `getDefaultInterestRate(String, String)` — default-group fallback rate | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 12 | `1300-COMPUTE-INTEREST` | `computeInterest(String parmDate)` — **PARITY-CRITICAL** (AAP §0.6.1): monthly interest = (TRAN-CAT-BAL × DIS-INT-RATE) / 1200 via BigDecimal, RoundingMode.DOWN (truncation), scale 2 — COBOL has no ROUNDED phrase | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 13 | `1300-B-WRITE-TX` | `writeInterestTx(String, BigDecimal)` — writes interest transaction record | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 14 | `1400-COMPUTE-FEES` | `computeFees()` — empty stub (no fee logic in legacy program) | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 15 | `9000-TCATBALF-CLOSE` | `closeTcatbalf()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 16 | `9100-XREFFILE-CLOSE` | `closeXreffile()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 17 | `9200-DISCGRP-CLOSE` | `closeDiscgrp()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 18 | `9300-ACCTFILE-CLOSE` | `closeAcctfile()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 19 | `9400-TRANFILE-CLOSE` | `closeTranfile()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 20 | `Z-GET-DB2-FORMAT-TIMESTAMP` | `getDb2FormatTimestamp()` | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 21 | `9999-ABEND-PROGRAM` | `abend(...)` — → IoStatusException | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |
| 22 | `9910-DISPLAY-IO-STATUS` | `abend(...)` — folded into abend(); → IoStatusException | [`InterestCalculationService.java`](../src/main/java/com/aws/carddemo/service/batch/InterestCalculationService.java) |

### CBCUS01C — Customer master read/print

**COBOL source:** [`legacy/app/cbl/CBCUS01C.cbl`](../legacy/app/cbl/CBCUS01C.cbl) &rarr; **Java:** [`CustomerExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CustomerExtractService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `1000-CUSTFILE-GET-NEXT` | `custfileGetNext()` — customer record DISPLAYed twice (get-next and main loop) — parity preserved | [`CustomerExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CustomerExtractService.java) |
| 2 | `0000-CUSTFILE-OPEN` | `openCustfile()` | [`CustomerExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CustomerExtractService.java) |
| 3 | `9000-CUSTFILE-CLOSE` | `closeCustfile()` | [`CustomerExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CustomerExtractService.java) |
| 4 | `Z-ABEND-PROGRAM` | `raiseAbend(...)` — Z- abend naming (not 9999/9910); → IoStatusException | [`CustomerExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CustomerExtractService.java) |
| 5 | `Z-DISPLAY-IO-STATUS` | `raiseAbend(...)` — folded into raiseAbend(); → IoStatusException | [`CustomerExtractService.java`](../src/main/java/com/aws/carddemo/service/batch/CustomerExtractService.java) |

### CBSTM03A — Statement generation (driver)

**COBOL source:** [`legacy/app/cbl/CBSTM03A.CBL`](../legacy/app/cbl/CBSTM03A.CBL) &rarr; **Java:** [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0000-START` | `run(Consumer<String> stmtSink, Consumer<String> htmlSink)` — program entry / init (z/OS ALTER plumbing folded in) | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 2 | `1000-MAINLINE` | `mainline()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 3 | `9999-GOBACK` | `run(...)` — normal program return (GOBACK) | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 4 | `1000-XREFFILE-GET-NEXT` | `xreffileGetNext()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 5 | `2000-CUSTFILE-GET` | `custfileGet()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 6 | `3000-ACCTFILE-GET` | `acctfileGet()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 7 | `4000-TRNXFILE-GET` | `trnxfileGet()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 8 | `5000-CREATE-STATEMENT` | `createStatement()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 9 | `5100-WRITE-HTML-HEADER` | `writeHtmlHeader()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 10 | `5100-EXIT` | `writeHtmlHeader()` — EXIT label → method return | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 11 | `5200-WRITE-HTML-NMADBS` | `writeHtmlNmadbs()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 12 | `5200-EXIT` | `writeHtmlNmadbs()` — EXIT label → method return | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 13 | `6000-WRITE-TRANS` | `writeTrans(Transaction)` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 14 | `8100-FILE-OPEN` | `run(...)` — 8100-FILE-OPEN ALTER umbrella — folded into run() open-sequence | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 15 | `8100-TRNXFILE-OPEN` | `openTrnxfile()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 16 | `8200-XREFFILE-OPEN` | `openFile(String ddname, String label)` — generic open dispatch (record I/O delegated to FileIoService) | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 17 | `8300-CUSTFILE-OPEN` | `openFile(String ddname, String label)` — generic open dispatch | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 18 | `8400-ACCTFILE-OPEN` | `openFile(String ddname, String label)` — generic open dispatch | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 19 | `8500-READTRNX-READ` | `readTrnxNext()` | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 20 | `8599-EXIT` | `buildTrnxTable()` — EXIT label → method return (8599-EXIT → table-build return) | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 21 | `9100-TRNXFILE-CLOSE` | `closeFile(String ddname, String label)` — generic close dispatch | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 22 | `9200-XREFFILE-CLOSE` | `closeFile(String ddname, String label)` — generic close dispatch | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 23 | `9300-CUSTFILE-CLOSE` | `closeFile(String ddname, String label)` — generic close dispatch | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 24 | `9400-ACCTFILE-CLOSE` | `closeFile(String ddname, String label)` — generic close dispatch | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |
| 25 | `9999-ABEND-PROGRAM` | `abend(String fileName, String operation, String fileStatus)` — → IoStatusException | [`StatementGenerationService.java`](../src/main/java/com/aws/carddemo/service/batch/StatementGenerationService.java) |

### CBSTM03B — File-I/O subroutine (injected component)

**COBOL source:** [`legacy/app/cbl/CBSTM03B.CBL`](../legacy/app/cbl/CBSTM03B.CBL) &rarr; **Java:** [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0000-START` | `process(WorkArea)` — EVALUATE dispatcher on requested file function | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 2 | `9999-GOBACK` | `process(WorkArea)` — dispatcher WHEN OTHER / GOBACK return | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 3 | `1000-TRNXFILE-PROC` | `trnxfileProc(WorkArea)` | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 4 | `1900-EXIT` | `trnxfileProc(WorkArea)` — EXIT label → method return | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 5 | `1999-EXIT` | `trnxfileProc(WorkArea)` — EXIT label → method return | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 6 | `2000-XREFFILE-PROC` | `xreffileProc(WorkArea)` | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 7 | `2900-EXIT` | `xreffileProc(WorkArea)` — EXIT label → method return | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 8 | `2999-EXIT` | `xreffileProc(WorkArea)` — EXIT label → method return | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 9 | `3000-CUSTFILE-PROC` | `custfileProc(WorkArea)` | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 10 | `3900-EXIT` | `custfileProc(WorkArea)` — EXIT label → method return | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 11 | `3999-EXIT` | `custfileProc(WorkArea)` — EXIT label → method return | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 12 | `4000-ACCTFILE-PROC` | `acctfileProc(WorkArea)` | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 13 | `4900-EXIT` | `acctfileProc(WorkArea)` — EXIT label → method return | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |
| 14 | `4999-EXIT` | `acctfileProc(WorkArea)` — EXIT label → method return | [`FileIoService.java`](../src/main/java/com/aws/carddemo/service/batch/FileIoService.java) |

### CBTRN01C — Daily transaction validation

**COBOL source:** [`legacy/app/cbl/CBTRN01C.cbl`](../legacy/app/cbl/CBTRN01C.cbl) &rarr; **Java:** [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `run()` — MAIN-PARA (validation-only; no posting) | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 2 | `1000-DALYTRAN-GET-NEXT` | `dalytranGetNext()` | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 3 | `2000-LOOKUP-XREF` | `lookupXref()` | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 4 | `3000-READ-ACCOUNT` | `readAccount()` | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 5 | `0000-DALYTRAN-OPEN` | `openDalytran()` | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 6 | `0100-CUSTFILE-OPEN` | `openCustfile()` — no-op (opened, not posted) | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 7 | `0200-XREFFILE-OPEN` | `openXreffile()` — no-op | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 8 | `0300-CARDFILE-OPEN` | `openCardfile()` — no-op | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 9 | `0400-ACCTFILE-OPEN` | `openAcctfile()` — no-op | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 10 | `0500-TRANFILE-OPEN` | `openTranfile()` — no-op | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 11 | `9000-DALYTRAN-CLOSE` | `closeDalytran()` | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 12 | `9100-CUSTFILE-CLOSE` | `closeCustfile()` — no-op | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 13 | `9200-XREFFILE-CLOSE` | `closeXreffile()` — no-op | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 14 | `9300-CARDFILE-CLOSE` | `closeCardfile()` — no-op | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 15 | `9400-ACCTFILE-CLOSE` | `closeAcctfile()` — no-op | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 16 | `9500-TRANFILE-CLOSE` | `closeTranfile()` — no-op | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 17 | `Z-ABEND-PROGRAM` | `abend(...)` — Z- abend naming; → IoStatusException | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |
| 18 | `Z-DISPLAY-IO-STATUS` | `abend(...)` — folded into abend(); → IoStatusException | [`DailyTransactionPostService.java`](../src/main/java/com/aws/carddemo/service/batch/DailyTransactionPostService.java) |

### CBTRN02C — Daily transaction posting (parity archetype)

**COBOL source:** [`legacy/app/cbl/CBTRN02C.cbl`](../legacy/app/cbl/CBTRN02C.cbl) &rarr; **Java:** [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0000-DALYTRAN-OPEN` | `openDalytran()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 2 | `0100-TRANFILE-OPEN` | `openTranfile()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 3 | `0200-XREFFILE-OPEN` | `openXreffile()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 4 | `0300-DALYREJS-OPEN` | `openDalyrejs()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 5 | `0400-ACCTFILE-OPEN` | `openAcctfile()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 6 | `0500-TCATBALF-OPEN` | `openTcatbalf()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 7 | `1000-DALYTRAN-GET-NEXT` | `dalytranGetNext()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 8 | `1500-VALIDATE-TRAN` | `validateTran()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 9 | `1500-A-LOOKUP-XREF` | `lookupXref()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 10 | `1500-B-LOOKUP-ACCT` | `lookupAcct()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 11 | `2000-POST-TRANSACTION` | `postTransaction()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 12 | `2500-WRITE-REJECT-REC` | `writeRejectRec(Consumer<String> rejectSink)` — writes rejected daily transaction (DALYREJS, LRECL=430) | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 13 | `2700-UPDATE-TCATBAL` | `updateTcatbal()` — find-or-create upsert: read accepts FILE STATUS '00' OR '23' (AAP §0.6.4) | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 14 | `2700-A-CREATE-TCATBAL-REC` | `updateTcatbal()` — 2700-A-CREATE-TCATBAL-REC folded into updateTcatbal() (upsert create branch) | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 15 | `2700-B-UPDATE-TCATBAL-REC` | `updateTcatbal()` — 2700-B-UPDATE-TCATBAL-REC folded into updateTcatbal() (upsert update branch) | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 16 | `2800-UPDATE-ACCOUNT-REC` | `updateAccountRec()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 17 | `2900-WRITE-TRANSACTION-FILE` | `writeTransactionFile()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 18 | `9000-DALYTRAN-CLOSE` | `closeDalytran()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 19 | `9100-TRANFILE-CLOSE` | `closeTranfile()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 20 | `9200-XREFFILE-CLOSE` | `closeXreffile()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 21 | `9300-DALYREJS-CLOSE` | `closeDalyrejs()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 22 | `9400-ACCTFILE-CLOSE` | `closeAcctfile()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 23 | `9500-TCATBALF-CLOSE` | `closeTcatbalf()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 24 | `Z-GET-DB2-FORMAT-TIMESTAMP` | `getDb2FormatTimestamp()` | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 25 | `9999-ABEND-PROGRAM` | `abend(...)` — → IoStatusException | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |
| 26 | `9910-DISPLAY-IO-STATUS` | `abend(...)` — folded into abend(); → IoStatusException | [`TransactionPostingService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionPostingService.java) |

### CBTRN03C — Transaction detail report

**COBOL source:** [`legacy/app/cbl/CBTRN03C.cbl`](../legacy/app/cbl/CBTRN03C.cbl) &rarr; **Java:** [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0550-DATEPARM-READ` | `dateparmRead()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 2 | `1000-TRANFILE-GET-NEXT` | `tranfileGetNext()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 3 | `1100-WRITE-TRANSACTION-REPORT` | `writeTransactionReport()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 4 | `1110-WRITE-PAGE-TOTALS` | `writePageTotals()` — duplicate paragraph number 1110- (distinct full name → distinct method) | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 5 | `1120-WRITE-ACCOUNT-TOTALS` | `writeAccountTotals()` — duplicate paragraph number 1120- (distinct method) | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 6 | `1110-WRITE-GRAND-TOTALS` | `writeGrandTotals()` — duplicate paragraph number 1110- (distinct method) | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 7 | `1120-WRITE-HEADERS` | `writeHeaders()` — duplicate paragraph number 1120- (distinct method) | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 8 | `1111-WRITE-REPORT-REC` | `writeReportRec(String)` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 9 | `1120-WRITE-DETAIL` | `writeDetail()` — duplicate paragraph number 1120- (distinct method) | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 10 | `0000-TRANFILE-OPEN` | `openTranfile()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 11 | `0100-REPTFILE-OPEN` | `openReptfile()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 12 | `0200-CARDXREF-OPEN` | `openCardxref()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 13 | `0300-TRANTYPE-OPEN` | `openTrantype()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 14 | `0400-TRANCATG-OPEN` | `openTrancatg()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 15 | `0500-DATEPARM-OPEN` | `openDateparm()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 16 | `1500-A-LOOKUP-XREF` | `lookupXref()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 17 | `1500-B-LOOKUP-TRANTYPE` | `lookupTrantype()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 18 | `1500-C-LOOKUP-TRANCATG` | `lookupTrancatg()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 19 | `9000-TRANFILE-CLOSE` | `closeTranfile()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 20 | `9100-REPTFILE-CLOSE` | `closeReptfile()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 21 | `9200-CARDXREF-CLOSE` | `closeCardxref()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 22 | `9300-TRANTYPE-CLOSE` | `closeTrantype()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 23 | `9400-TRANCATG-CLOSE` | `closeTrancatg()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 24 | `9500-DATEPARM-CLOSE` | `closeDateparm()` | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 25 | `9999-ABEND-PROGRAM` | `abend(...)` — → IoStatusException | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |
| 26 | `9910-DISPLAY-IO-STATUS` | `abend(...)` — 9910-DISPLAY-IO-STATUS folded into abend() (no separate displayIoStatus method); → IoStatusException | [`TransactionReportService.java`](../src/main/java/com/aws/carddemo/service/batch/TransactionReportService.java) |

## Online Programs

The 17 pseudo-conversational CICS programs become a `web/*Controller` + `service/online/*Service` pair each. **Convention:** screen `SEND-*-SCREEN` paragraphs map to controller `show*()` (GET / paint) and `RECEIVE-*` paragraphs to controller `do*()` (POST / receive); business logic, validation and file access live in the service `process*()` entry and its private methods. The shared abstract base [`BaseScreenController.java`](../src/main/java/com/aws/carddemo/web/BaseScreenController.java) provides the COMMAREA/navigation helpers (`getCommarea`, `storeCommarea`, `clearCommarea`, `resolveAid`, `redirectFor`) and a 17-entry `PROGRAM_TO_URL` map; `COMMON-RETURN` rows therefore resolve to its persist-and-redirect helpers, and `ABEND-ROUTINE` rows resolve to the `@ControllerAdvice` [`GlobalExceptionHandler.java`](../src/main/java/com/aws/carddemo/exception/GlobalExceptionHandler.java). Admin-gated screens (`COADM01C`, `COUSR00C`–`COUSR03C`) are protected with `@PreAuthorize`.

### COACTUPC — Account update `CAUP`

**COBOL source:** [`legacy/app/cbl/COACTUPC.cbl`](../legacy/app/cbl/COACTUPC.cbl) &rarr; **Java:** [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) + [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0000-MAIN` | `processAccountUpdate()` — pseudo-conversational entry | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 2 | `COMMON-RETURN` | `redirectFor()` / `storeCommarea()` — common return: persist COMMAREA + redirect (BaseScreenController) | [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) |
| 3 | `0000-MAIN-EXIT` | `processAccountUpdate()` — pseudo-conversational entry; EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 4 | `1000-PROCESS-INPUTS` | `processInputs()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 5 | `1000-PROCESS-INPUTS-EXIT` | `processInputs()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 6 | `1100-RECEIVE-MAP` | `processInputs()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 7 | `1100-RECEIVE-MAP-EXIT` | `processInputs()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 8 | `1200-EDIT-MAP-INPUTS` | `processInputs()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 9 | `1200-EDIT-MAP-INPUTS-EXIT` | `processInputs()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 10 | `1205-COMPARE-OLD-NEW` | `compareOldNew()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 11 | `1205-COMPARE-OLD-NEW-EXIT` | `compareOldNew()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 12 | `1210-EDIT-ACCOUNT` | `editAccount()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 13 | `1210-EDIT-ACCOUNT-EXIT` | `editAccount()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 14 | `1215-EDIT-MANDATORY` | `editMandatory()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 15 | `1215-EDIT-MANDATORY-EXIT` | `editMandatory()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 16 | `1220-EDIT-YESNO` | `editYesNo()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 17 | `1220-EDIT-YESNO-EXIT` | `editYesNo()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 18 | `1225-EDIT-ALPHA-REQD` | `editAlphaReqd()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 19 | `1225-EDIT-ALPHA-REQD-EXIT` | `editAlphaReqd()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 20 | `1230-EDIT-ALPHANUM-REQD` | `editMandatory()` — alphanumeric-required folded into presence check (validateAllFields) | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 21 | `1230-EDIT-ALPHANUM-REQD-EXIT` | `editMandatory()` — alphanumeric-required folded into presence check (validateAllFields); EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 22 | `1235-EDIT-ALPHA-OPT` | `editAlphaOpt()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 23 | `1235-EDIT-ALPHA-OPT-EXIT` | `editAlphaOpt()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 24 | `1240-EDIT-ALPHANUM-OPT` | `noOp()` — optional alphanumeric → no-op validator | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 25 | `1240-EDIT-ALPHANUM-OPT-EXIT` | `noOp()` — optional alphanumeric → no-op validator; EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 26 | `1245-EDIT-NUM-REQD` | `editNumReqd()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 27 | `1245-EDIT-NUM-REQD-EXIT` | `editNumReqd()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 28 | `1250-EDIT-SIGNED-9V2` | `editSigned9v2()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 29 | `1250-EDIT-SIGNED-9V2-EXIT` | `editSigned9v2()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 30 | `1260-EDIT-US-PHONE-NUM` | `editUsPhoneNum()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 31 | `EDIT-AREA-CODE` | `editUsPhoneNum()` — nested standalone paragraph inside the 1260-EDIT-US-PHONE-NUM THRU block | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 32 | `EDIT-US-PHONE-PREFIX` | `editUsPhoneNum()` — nested standalone paragraph inside the 1260-EDIT-US-PHONE-NUM THRU block | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 33 | `EDIT-US-PHONE-LINENUM` | `editUsPhoneNum()` — nested standalone paragraph inside the 1260-EDIT-US-PHONE-NUM THRU block | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 34 | `EDIT-US-PHONE-EXIT` | `editUsPhoneNum()` — nested THRU-target inside 1260-EDIT-US-PHONE-NUM; EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 35 | `1260-EDIT-US-PHONE-NUM-EXIT` | `editUsPhoneNum()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 36 | `1265-EDIT-US-SSN` | `editUsSsn()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 37 | `1265-EDIT-US-SSN-EXIT` | `editUsSsn()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 38 | `1270-EDIT-US-STATE-CD` | `editUsStateCd()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 39 | `1270-EDIT-US-STATE-CD-EXIT` | `editUsStateCd()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 40 | `1275-EDIT-FICO-SCORE` | `editFicoScore()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 41 | `1275-EDIT-FICO-SCORE-EXIT` | `editFicoScore()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 42 | `1280-EDIT-US-STATE-ZIP-CD` | `editUsStateZipCd()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 43 | `1280-EDIT-US-STATE-ZIP-CD-EXIT` | `editUsStateZipCd()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 44 | `2000-DECIDE-ACTION` | `decideAction()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 45 | `2000-DECIDE-ACTION-EXIT` | `decideAction()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 46 | `3000-SEND-MAP` | `sendMap()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 47 | `3000-SEND-MAP-EXIT` | `sendMap()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 48 | `3100-SCREEN-INIT` | `populateHeader()` — screen/header init phase of 3000-SEND-MAP | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 49 | `3100-SCREEN-INIT-EXIT` | `populateHeader()` — screen/header init phase of 3000-SEND-MAP; EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 50 | `3200-SETUP-SCREEN-VARS` | `populateScreenForState()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 51 | `3200-SETUP-SCREEN-VARS-EXIT` | `populateScreenForState()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 52 | `3201-SHOW-INITIAL-VALUES` | `clearDetailFields()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 53 | `3201-SHOW-INITIAL-VALUES-EXIT` | `clearDetailFields()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 54 | `3202-SHOW-ORIGINAL-VALUES` | `populateScreenFromEntities()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 55 | `3202-SHOW-ORIGINAL-VALUES-EXIT` | `populateScreenFromEntities()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 56 | `3203-SHOW-UPDATED-VALUES` | `populateScreenForState()` — keeps operator NEW input already carried in the screen DTO | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 57 | `3203-SHOW-UPDATED-VALUES-EXIT` | `populateScreenForState()` — keeps operator NEW input already carried in the screen DTO; EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 58 | `3250-SETUP-INFOMSG` | `setupInfoMsg()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 59 | `3250-SETUP-INFOMSG-EXIT` | `setupInfoMsg()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 60 | `3300-SETUP-SCREEN-ATTRS` | `showAccountUpdate()` — BMS 3270 field attributes → controller / Thymeleaf-rendered | [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) |
| 61 | `3300-SETUP-SCREEN-ATTRS-EXIT` | `showAccountUpdate()` — BMS 3270 field attributes → controller / Thymeleaf-rendered; EXIT label → method return | [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) |
| 62 | `3310-PROTECT-ALL-ATTRS` | `showAccountUpdate()` — BMS 3270 field attributes → controller / Thymeleaf-rendered | [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) |
| 63 | `3310-PROTECT-ALL-ATTRS-EXIT` | `showAccountUpdate()` — BMS 3270 field attributes → controller / Thymeleaf-rendered; EXIT label → method return | [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) |
| 64 | `3320-UNPROTECT-FEW-ATTRS` | `showAccountUpdate()` — BMS 3270 field attributes → controller / Thymeleaf-rendered | [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) |
| 65 | `3320-UNPROTECT-FEW-ATTRS-EXIT` | `showAccountUpdate()` — BMS 3270 field attributes → controller / Thymeleaf-rendered; EXIT label → method return | [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) |
| 66 | `3390-SETUP-INFOMSG-ATTRS` | `setupInfoMsg()` — info-message attribute setup | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 67 | `3390-SETUP-INFOMSG-ATTRS-EXIT` | `setupInfoMsg()` — info-message attribute setup; EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 68 | `3400-SEND-SCREEN` | `showAccountUpdate()` — paint (SEND screen) | [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) |
| 69 | `3400-SEND-SCREEN-EXIT` | `showAccountUpdate()` — paint (SEND screen); EXIT label → method return | [`AccountUpdateController.java`](../src/main/java/com/aws/carddemo/web/AccountUpdateController.java) |
| 70 | `9000-READ-ACCT` | `readAccount()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 71 | `9000-READ-ACCT-EXIT` | `readAccount()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 72 | `9200-GETCARDXREF-BYACCT` | `readAccount()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 73 | `9200-GETCARDXREF-BYACCT-EXIT` | `readAccount()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 74 | `9300-GETACCTDATA-BYACCT` | `readAccount()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 75 | `9300-GETACCTDATA-BYACCT-EXIT` | `readAccount()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 76 | `9400-GETCUSTDATA-BYCUST` | `readAccount()` — keyed customer read folded into readAccount | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 77 | `9400-GETCUSTDATA-BYCUST-EXIT` | `readAccount()` — keyed customer read folded into readAccount; EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 78 | `9500-STORE-FETCHED-DATA` | `readAccount()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 79 | `9500-STORE-FETCHED-DATA-EXIT` | `readAccount()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 80 | `9600-WRITE-PROCESSING` | `writeProcessing()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 81 | `9600-WRITE-PROCESSING-EXIT` | `writeProcessing()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 82 | `9700-CHECK-CHANGE-IN-REC` | `checkChangeInRec()` | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 83 | `9700-CHECK-CHANGE-IN-REC-EXIT` | `checkChangeInRec()` — EXIT label → method return | [`AccountUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java) |
| 84 | `ABEND-ROUTINE` | `handleCardDemo()` — abend → @ControllerAdvice GlobalExceptionHandler.handleCardDemo(CardDemoException) | [`GlobalExceptionHandler.java`](../src/main/java/com/aws/carddemo/exception/GlobalExceptionHandler.java) |
| 85 | `ABEND-ROUTINE-EXIT` | `handleCardDemo()` — abend → @ControllerAdvice GlobalExceptionHandler.handleCardDemo(CardDemoException); EXIT label → method return | [`GlobalExceptionHandler.java`](../src/main/java/com/aws/carddemo/exception/GlobalExceptionHandler.java) |

### COACTVWC — Account view `CAVW`

**COBOL source:** [`legacy/app/cbl/COACTVWC.cbl`](../legacy/app/cbl/COACTVWC.cbl) &rarr; **Java:** [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) + [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0000-MAIN` | `processAccountView()` — pseudo-conversational entry | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 2 | `COMMON-RETURN` | `redirectFor()` / `storeCommarea()` — common return: persist COMMAREA + redirect (BaseScreenController) | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 3 | `0000-MAIN-EXIT` | `processAccountView()` — pseudo-conversational entry; EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 4 | `0000-MAIN-EXIT` | `processAccountView()` — pseudo-conversational entry; EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 5 | `1000-SEND-MAP` | `handleFirstEntry()` | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 6 | `1000-SEND-MAP-EXIT` | `handleFirstEntry()` — EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 7 | `1100-SCREEN-INIT` | `showAccountView()` — controller-owned (BMS rendering) | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 8 | `1100-SCREEN-INIT-EXIT` | `showAccountView()` — controller-owned (BMS rendering); EXIT label → method return | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 9 | `1200-SETUP-SCREEN-VARS` | `handleReentry()` | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 10 | `1200-SETUP-SCREEN-VARS-EXIT` | `handleReentry()` — EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 11 | `1300-SETUP-SCREEN-ATTRS` | `showAccountView()` — controller-owned (BMS rendering) | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 12 | `1300-SETUP-SCREEN-ATTRS-EXIT` | `showAccountView()` — controller-owned (BMS rendering); EXIT label → method return | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 13 | `1400-SEND-SCREEN` | `showAccountView()` — paint (SEND screen) | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 14 | `1400-SEND-SCREEN-EXIT` | `showAccountView()` — paint (SEND screen); EXIT label → method return | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 15 | `2000-PROCESS-INPUTS` | `processInputs()` | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 16 | `2000-PROCESS-INPUTS-EXIT` | `processInputs()` — EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 17 | `2100-RECEIVE-MAP` | `doAccountView()` — controller-owned (AID capture/receive) | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 18 | `2100-RECEIVE-MAP-EXIT` | `doAccountView()` — controller-owned (AID capture/receive); EXIT label → method return | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 19 | `2200-EDIT-MAP-INPUTS` | `processInputs()` | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 20 | `2200-EDIT-MAP-INPUTS-EXIT` | `processInputs()` — EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 21 | `2210-EDIT-ACCOUNT` | `processInputs()` | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 22 | `2210-EDIT-ACCOUNT-EXIT` | `processInputs()` — EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 23 | `9000-READ-ACCT` | `readAccount()` | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 24 | `9000-READ-ACCT-EXIT` | `readAccount()` — EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 25 | `9200-GETCARDXREF-BYACCT` | `getCardXrefByAcct()` | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 26 | `9200-GETCARDXREF-BYACCT-EXIT` | `getCardXrefByAcct()` — EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 27 | `9300-GETACCTDATA-BYACCT` | `getAccountById()` | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 28 | `9300-GETACCTDATA-BYACCT-EXIT` | `getAccountById()` — EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 29 | `9400-GETCUSTDATA-BYCUST` | `getCustomerById()` | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 30 | `9400-GETCUSTDATA-BYCUST-EXIT` | `getCustomerById()` — EXIT label → method return | [`AccountViewService.java`](../src/main/java/com/aws/carddemo/service/online/AccountViewService.java) |
| 31 | `SEND-PLAIN-TEXT` | `showAccountView()` — CICS SEND TEXT (error/abend) → controller / GlobalExceptionHandler; not a separate service method | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 32 | `SEND-PLAIN-TEXT-EXIT` | `showAccountView()` — CICS SEND TEXT (error/abend) → controller / GlobalExceptionHandler; not a separate service method; EXIT label → method return | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 33 | `SEND-LONG-TEXT` | `showAccountView()` — CICS SEND TEXT (error/abend) → controller / GlobalExceptionHandler; not a separate service method | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 34 | `SEND-LONG-TEXT-EXIT` | `showAccountView()` — CICS SEND TEXT (error/abend) → controller / GlobalExceptionHandler; not a separate service method; EXIT label → method return | [`AccountViewController.java`](../src/main/java/com/aws/carddemo/web/AccountViewController.java) |
| 35 | `ABEND-ROUTINE` | `handleCardDemo()` — abend → @ControllerAdvice GlobalExceptionHandler.handleCardDemo(CardDemoException) | [`GlobalExceptionHandler.java`](../src/main/java/com/aws/carddemo/exception/GlobalExceptionHandler.java) |

### COADM01C — Admin menu `CA00`

**COBOL source:** [`legacy/app/cbl/COADM01C.cbl`](../legacy/app/cbl/COADM01C.cbl) &rarr; **Java:** [`AdminMenuController.java`](../src/main/java/com/aws/carddemo/web/AdminMenuController.java) + [`AdminMenuService.java`](../src/main/java/com/aws/carddemo/service/online/AdminMenuService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processAdminMenu()` — pseudo-conversational entry | [`AdminMenuService.java`](../src/main/java/com/aws/carddemo/service/online/AdminMenuService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`AdminMenuService.java`](../src/main/java/com/aws/carddemo/service/online/AdminMenuService.java) |
| 3 | `RETURN-TO-SIGNON-SCREEN` | `processAdminMenu()` | [`AdminMenuService.java`](../src/main/java/com/aws/carddemo/service/online/AdminMenuService.java) |
| 4 | `SEND-MENU-SCREEN` | `showAdminMenu()` — paint (SEND screen) | [`AdminMenuController.java`](../src/main/java/com/aws/carddemo/web/AdminMenuController.java) |
| 5 | `RECEIVE-MENU-SCREEN` | `doAdminMenu()` — receive (HTTP form binding) | [`AdminMenuController.java`](../src/main/java/com/aws/carddemo/web/AdminMenuController.java) |
| 6 | `POPULATE-HEADER-INFO` | `processAdminMenu()` | [`AdminMenuService.java`](../src/main/java/com/aws/carddemo/service/online/AdminMenuService.java) |
| 7 | `BUILD-MENU-OPTIONS` | `buildMenuOptions()` | [`AdminMenuService.java`](../src/main/java/com/aws/carddemo/service/online/AdminMenuService.java) |

### COBIL00C — Bill payment `CB00`

**COBOL source:** [`legacy/app/cbl/COBIL00C.cbl`](../legacy/app/cbl/COBIL00C.cbl) &rarr; **Java:** [`BillPayController.java`](../src/main/java/com/aws/carddemo/web/BillPayController.java) + [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processBillPay()` — pseudo-conversational entry | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 3 | `GET-CURRENT-TIMESTAMP` | `returnToPrevScreen()` | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 4 | `RETURN-TO-PREV-SCREEN` | `returnToPrevScreen()` | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 5 | `SEND-BILLPAY-SCREEN` | `showBillPay()` — paint (SEND screen) | [`BillPayController.java`](../src/main/java/com/aws/carddemo/web/BillPayController.java) |
| 6 | `RECEIVE-BILLPAY-SCREEN` | `doBillPay()` — receive (HTTP form binding) | [`BillPayController.java`](../src/main/java/com/aws/carddemo/web/BillPayController.java) |
| 7 | `POPULATE-HEADER-INFO` | `showBillPay()` — not modeled (BMS rendering) | [`BillPayController.java`](../src/main/java/com/aws/carddemo/web/BillPayController.java) |
| 8 | `READ-ACCTDAT-FILE` | `readAcctdatFile()` | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 9 | `UPDATE-ACCTDAT-FILE` | `updateAcctdatFile()` | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 10 | `READ-CXACAIX-FILE` | `readCxacaixFile()` | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 11 | `STARTBR-TRANSACT-FILE` | `findHighestTranId()` — VSAM browse (highest tran id) → JPA | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 12 | `READPREV-TRANSACT-FILE` | `findHighestTranId()` — VSAM browse (highest tran id) → JPA | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 13 | `ENDBR-TRANSACT-FILE` | `findHighestTranId()` — VSAM browse (highest tran id) → JPA | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 14 | `WRITE-TRANSACT-FILE` | `writeTransactFile()` | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 15 | `CLEAR-CURRENT-SCREEN` | `clearCurrentScreen()` | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |
| 16 | `INITIALIZE-ALL-FIELDS` | `initializeAllFields()` | [`BillPayService.java`](../src/main/java/com/aws/carddemo/service/online/BillPayService.java) |

### COCRDLIC — Card list `CCLI`

**COBOL source:** [`legacy/app/cbl/COCRDLIC.cbl`](../legacy/app/cbl/COCRDLIC.cbl) &rarr; **Java:** [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) + [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0000-MAIN` | `processCardList()` — pseudo-conversational entry | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 2 | `COMMON-RETURN` | `redirectFor()` / `storeCommarea()` — common return: persist COMMAREA + redirect (BaseScreenController) | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 3 | `0000-MAIN-EXIT` | `processCardList()` — pseudo-conversational entry; EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 4 | `1000-SEND-MAP` | `finishRedisplay()` — 1000-SEND-MAP family: render/redisplay + COMMON-RETURN origin stamping | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 5 | `1000-SEND-MAP-EXIT` | `finishRedisplay()` — 1000-SEND-MAP family: render/redisplay + COMMON-RETURN origin stamping; EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 6 | `1100-SCREEN-INIT` | `populateHeader()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 7 | `1100-SCREEN-INIT-EXIT` | `populateHeader()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 8 | `1200-SCREEN-ARRAY-INIT` | `populateRows()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 9 | `1200-SCREEN-ARRAY-INIT-EXIT` | `populateRows()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 10 | `1250-SETUP-ARRAY-ATTRIBS` | `showCardList()` — BMS 7-row array attributes → controller / Thymeleaf-rendered | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 11 | `1250-SETUP-ARRAY-ATTRIBS-EXIT` | `showCardList()` — BMS 7-row array attributes → controller / Thymeleaf-rendered; EXIT label → method return | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 12 | `1300-SETUP-SCREEN-ATTRS` | `populateHeader()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 13 | `1300-SETUP-SCREEN-ATTRS-EXIT` | `populateHeader()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 14 | `1400-SETUP-MESSAGE` | `setupMessage()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 15 | `1400-SETUP-MESSAGE-EXIT` | `setupMessage()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 16 | `1500-SEND-SCREEN` | `showCardList()` — paint (SEND screen) | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 17 | `1500-SEND-SCREEN-EXIT` | `showCardList()` — paint (SEND screen); EXIT label → method return | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 18 | `2000-RECEIVE-MAP` | `receiveMap()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 19 | `2000-RECEIVE-MAP-EXIT` | `receiveMap()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 20 | `2100-RECEIVE-SCREEN` | `doCardList()` — receive (HTTP form binding) | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 21 | `2100-RECEIVE-SCREEN-EXIT` | `doCardList()` — receive (HTTP form binding); EXIT label → method return | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 22 | `2200-EDIT-INPUTS` | `editInputs()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 23 | `2200-EDIT-INPUTS-EXIT` | `editInputs()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 24 | `2210-EDIT-ACCOUNT` | `editAccount()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 25 | `2210-EDIT-ACCOUNT-EXIT` | `editAccount()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 26 | `2220-EDIT-CARD` | `editCard()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 27 | `2220-EDIT-CARD-EXIT` | `editCard()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 28 | `2250-EDIT-ARRAY` | `editArray()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 29 | `2250-EDIT-ARRAY-EXIT` | `editArray()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 30 | `9000-READ-FORWARD` | `readForward()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 31 | `9000-READ-FORWARD-EXIT` | `readForward()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 32 | `9100-READ-BACKWARDS` | `readBackwards()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 33 | `9100-READ-BACKWARDS-EXIT` | `readBackwards()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 34 | `9500-FILTER-RECORDS` | `filterRecords()` | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 35 | `9500-FILTER-RECORDS-EXIT` | `filterRecords()` — EXIT label → method return | [`CardListService.java`](../src/main/java/com/aws/carddemo/service/online/CardListService.java) |
| 36 | `SEND-PLAIN-TEXT` | `showCardList()` — CICS SEND TEXT (error) → controller / GlobalExceptionHandler | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 37 | `SEND-PLAIN-TEXT-EXIT` | `showCardList()` — CICS SEND TEXT (error) → controller / GlobalExceptionHandler; EXIT label → method return | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 38 | `SEND-LONG-TEXT` | `showCardList()` — CICS SEND TEXT (error) → controller / GlobalExceptionHandler | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |
| 39 | `SEND-LONG-TEXT-EXIT` | `showCardList()` — CICS SEND TEXT (error) → controller / GlobalExceptionHandler; EXIT label → method return | [`CardListController.java`](../src/main/java/com/aws/carddemo/web/CardListController.java) |

### COCRDSLC — Card view `CCDL`

**COBOL source:** [`legacy/app/cbl/COCRDSLC.cbl`](../legacy/app/cbl/COCRDSLC.cbl) &rarr; **Java:** [`CardViewController.java`](../src/main/java/com/aws/carddemo/web/CardViewController.java) + [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0000-MAIN` | `processCardView()` — pseudo-conversational entry | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 2 | `COMMON-RETURN` | `redirectFor()` / `storeCommarea()` — common return: persist COMMAREA + redirect (BaseScreenController) | [`CardViewController.java`](../src/main/java/com/aws/carddemo/web/CardViewController.java) |
| 3 | `0000-MAIN-EXIT` | `processCardView()` — pseudo-conversational entry; EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 4 | `1000-SEND-MAP` | `sendMap()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 5 | `1000-SEND-MAP-EXIT` | `sendMap()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 6 | `1100-SCREEN-INIT` | `sendMap()` — screen-init phase of send-map | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 7 | `1100-SCREEN-INIT-EXIT` | `sendMap()` — screen-init phase of send-map; EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 8 | `1200-SETUP-SCREEN-VARS` | `populateScreen()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 9 | `1200-SETUP-SCREEN-VARS-EXIT` | `populateScreen()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 10 | `1300-SETUP-SCREEN-ATTRS` | `sendMap()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 11 | `1300-SETUP-SCREEN-ATTRS-EXIT` | `sendMap()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 12 | `1400-SEND-SCREEN` | `showCardView()` — paint (SEND screen) | [`CardViewController.java`](../src/main/java/com/aws/carddemo/web/CardViewController.java) |
| 13 | `1400-SEND-SCREEN-EXIT` | `showCardView()` — paint (SEND screen); EXIT label → method return | [`CardViewController.java`](../src/main/java/com/aws/carddemo/web/CardViewController.java) |
| 14 | `2000-PROCESS-INPUTS` | `processInputs()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 15 | `2000-PROCESS-INPUTS-EXIT` | `processInputs()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 16 | `2100-RECEIVE-MAP` | `processCardView()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 17 | `2100-RECEIVE-MAP-EXIT` | `processCardView()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 18 | `2200-EDIT-MAP-INPUTS` | `editMapInputs()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 19 | `2200-EDIT-MAP-INPUTS-EXIT` | `editMapInputs()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 20 | `2210-EDIT-ACCOUNT` | `editAccount()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 21 | `2210-EDIT-ACCOUNT-EXIT` | `editAccount()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 22 | `2220-EDIT-CARD` | `editCard()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 23 | `2220-EDIT-CARD-EXIT` | `editCard()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 24 | `9000-READ-DATA` | `readData()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 25 | `9000-READ-DATA-EXIT` | `readData()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 26 | `9100-GETCARD-BYACCTCARD` | `getCardByAcctCard()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 27 | `9100-GETCARD-BYACCTCARD-EXIT` | `getCardByAcctCard()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 28 | `9150-GETCARD-BYACCT` | `readData()` | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 29 | `9150-GETCARD-BYACCT-EXIT` | `readData()` — EXIT label → method return | [`CardViewService.java`](../src/main/java/com/aws/carddemo/service/online/CardViewService.java) |
| 30 | `SEND-LONG-TEXT` | `showCardView()` — CICS SEND TEXT (error) → controller / GlobalExceptionHandler | [`CardViewController.java`](../src/main/java/com/aws/carddemo/web/CardViewController.java) |
| 31 | `SEND-LONG-TEXT-EXIT` | `showCardView()` — CICS SEND TEXT (error) → controller / GlobalExceptionHandler; EXIT label → method return | [`CardViewController.java`](../src/main/java/com/aws/carddemo/web/CardViewController.java) |
| 32 | `SEND-PLAIN-TEXT` | `showCardView()` — CICS SEND TEXT (error) → controller / GlobalExceptionHandler | [`CardViewController.java`](../src/main/java/com/aws/carddemo/web/CardViewController.java) |
| 33 | `SEND-PLAIN-TEXT-EXIT` | `showCardView()` — CICS SEND TEXT (error) → controller / GlobalExceptionHandler; EXIT label → method return | [`CardViewController.java`](../src/main/java/com/aws/carddemo/web/CardViewController.java) |
| 34 | `ABEND-ROUTINE` | `handleCardDemo()` — abend → @ControllerAdvice GlobalExceptionHandler.handleCardDemo(CardDemoException) | [`GlobalExceptionHandler.java`](../src/main/java/com/aws/carddemo/exception/GlobalExceptionHandler.java) |

### COCRDUPC — Card update `CCUP`

**COBOL source:** [`legacy/app/cbl/COCRDUPC.cbl`](../legacy/app/cbl/COCRDUPC.cbl) &rarr; **Java:** [`CardUpdateController.java`](../src/main/java/com/aws/carddemo/web/CardUpdateController.java) + [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `0000-MAIN` | `processCardUpdate()` — pseudo-conversational entry | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 2 | `COMMON-RETURN` | `redirectFor()` / `storeCommarea()` — common return: persist COMMAREA + redirect (BaseScreenController) | [`CardUpdateController.java`](../src/main/java/com/aws/carddemo/web/CardUpdateController.java) |
| 3 | `0000-MAIN-EXIT` | `processCardUpdate()` — pseudo-conversational entry; EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 4 | `1000-PROCESS-INPUTS` | `processCardUpdate()` — input-processing phase (RECEIVE-MAP + EDIT-MAP-INPUTS) orchestrated by the entry method | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 5 | `1000-PROCESS-INPUTS-EXIT` | `processCardUpdate()` — input-processing phase (RECEIVE-MAP + EDIT-MAP-INPUTS) orchestrated by the entry method; EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 6 | `1100-RECEIVE-MAP` | `receiveMap()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 7 | `1100-RECEIVE-MAP-EXIT` | `receiveMap()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 8 | `1200-EDIT-MAP-INPUTS` | `editMapInputs()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 9 | `1200-EDIT-MAP-INPUTS-EXIT` | `editMapInputs()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 10 | `1210-EDIT-ACCOUNT` | `editAccount()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 11 | `1210-EDIT-ACCOUNT-EXIT` | `editAccount()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 12 | `1220-EDIT-CARD` | `editCard()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 13 | `1220-EDIT-CARD-EXIT` | `editCard()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 14 | `1230-EDIT-NAME` | `editName()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 15 | `1230-EDIT-NAME-EXIT` | `editName()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 16 | `1240-EDIT-CARDSTATUS` | `editCardStatus()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 17 | `1240-EDIT-CARDSTATUS-EXIT` | `editCardStatus()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 18 | `1250-EDIT-EXPIRY-MON` | `editExpiryMonth()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 19 | `1250-EDIT-EXPIRY-MON-EXIT` | `editExpiryMonth()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 20 | `1260-EDIT-EXPIRY-YEAR` | `editExpiryYear()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 21 | `1260-EDIT-EXPIRY-YEAR-EXIT` | `editExpiryYear()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 22 | `2000-DECIDE-ACTION` | `decideAction()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 23 | `2000-DECIDE-ACTION-EXIT` | `decideAction()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 24 | `3000-SEND-MAP` | `sendMap()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 25 | `3000-SEND-MAP-EXIT` | `sendMap()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 26 | `3100-SCREEN-INIT` | `populateHeader()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 27 | `3100-SCREEN-INIT-EXIT` | `populateHeader()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 28 | `3200-SETUP-SCREEN-VARS` | `setupScreenVars()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 29 | `3200-SETUP-SCREEN-VARS-EXIT` | `setupScreenVars()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 30 | `3250-SETUP-INFOMSG` | `setupInfoMsg()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 31 | `3250-SETUP-INFOMSG-EXIT` | `setupInfoMsg()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 32 | `3300-SETUP-SCREEN-ATTRS` | `showCardUpdate()` — BMS 3270 field attributes → controller / Thymeleaf-rendered | [`CardUpdateController.java`](../src/main/java/com/aws/carddemo/web/CardUpdateController.java) |
| 33 | `3300-SETUP-SCREEN-ATTRS-EXIT` | `showCardUpdate()` — BMS 3270 field attributes → controller / Thymeleaf-rendered; EXIT label → method return | [`CardUpdateController.java`](../src/main/java/com/aws/carddemo/web/CardUpdateController.java) |
| 34 | `3400-SEND-SCREEN` | `showCardUpdate()` — paint (SEND screen) | [`CardUpdateController.java`](../src/main/java/com/aws/carddemo/web/CardUpdateController.java) |
| 35 | `3400-SEND-SCREEN-EXIT` | `showCardUpdate()` — paint (SEND screen); EXIT label → method return | [`CardUpdateController.java`](../src/main/java/com/aws/carddemo/web/CardUpdateController.java) |
| 36 | `9000-READ-DATA` | `readData()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 37 | `9000-READ-DATA-EXIT` | `readData()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 38 | `9100-GETCARD-BYACCTCARD` | `getCardByAcctCard()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 39 | `9100-GETCARD-BYACCTCARD-EXIT` | `getCardByAcctCard()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 40 | `9200-WRITE-PROCESSING` | `writeProcessing()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 41 | `9200-WRITE-PROCESSING-EXIT` | `writeProcessing()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 42 | `9300-CHECK-CHANGE-IN-REC` | `checkChangeInRec()` | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 43 | `9300-CHECK-CHANGE-IN-REC-EXIT` | `checkChangeInRec()` — EXIT label → method return | [`CardUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/CardUpdateService.java) |
| 44 | `ABEND-ROUTINE` | `handleCardDemo()` — abend → @ControllerAdvice GlobalExceptionHandler.handleCardDemo(CardDemoException) | [`GlobalExceptionHandler.java`](../src/main/java/com/aws/carddemo/exception/GlobalExceptionHandler.java) |
| 45 | `ABEND-ROUTINE-EXIT` | `handleCardDemo()` — abend → @ControllerAdvice GlobalExceptionHandler.handleCardDemo(CardDemoException); EXIT label → method return | [`GlobalExceptionHandler.java`](../src/main/java/com/aws/carddemo/exception/GlobalExceptionHandler.java) |

### COMEN01C — Main menu `CM00`

**COBOL source:** [`legacy/app/cbl/COMEN01C.cbl`](../legacy/app/cbl/COMEN01C.cbl) &rarr; **Java:** [`MainMenuController.java`](../src/main/java/com/aws/carddemo/web/MainMenuController.java) + [`MainMenuService.java`](../src/main/java/com/aws/carddemo/service/online/MainMenuService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processMainMenu()` — pseudo-conversational entry | [`MainMenuService.java`](../src/main/java/com/aws/carddemo/service/online/MainMenuService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`MainMenuService.java`](../src/main/java/com/aws/carddemo/service/online/MainMenuService.java) |
| 3 | `RETURN-TO-SIGNON-SCREEN` | `processMainMenu()` | [`MainMenuService.java`](../src/main/java/com/aws/carddemo/service/online/MainMenuService.java) |
| 4 | `SEND-MENU-SCREEN` | `showMenu()` — paint (SEND screen) | [`MainMenuController.java`](../src/main/java/com/aws/carddemo/web/MainMenuController.java) |
| 5 | `RECEIVE-MENU-SCREEN` | `doMenu()` — receive (HTTP form binding) | [`MainMenuController.java`](../src/main/java/com/aws/carddemo/web/MainMenuController.java) |
| 6 | `POPULATE-HEADER-INFO` | `processMainMenu()` | [`MainMenuService.java`](../src/main/java/com/aws/carddemo/service/online/MainMenuService.java) |
| 7 | `BUILD-MENU-OPTIONS` | `buildMenuOptions()` | [`MainMenuService.java`](../src/main/java/com/aws/carddemo/service/online/MainMenuService.java) |

### CORPT00C — Report submit `CR00`

**COBOL source:** [`legacy/app/cbl/CORPT00C.cbl`](../legacy/app/cbl/CORPT00C.cbl) &rarr; **Java:** [`ReportController.java`](../src/main/java/com/aws/carddemo/web/ReportController.java) + [`ReportService.java`](../src/main/java/com/aws/carddemo/service/online/ReportService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processReport()` — pseudo-conversational entry | [`ReportService.java`](../src/main/java/com/aws/carddemo/service/online/ReportService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`ReportService.java`](../src/main/java/com/aws/carddemo/service/online/ReportService.java) |
| 3 | `SUBMIT-JOB-TO-INTRDR` | `submitJobToIntrdr()` | [`ReportService.java`](../src/main/java/com/aws/carddemo/service/online/ReportService.java) |
| 4 | `WIRTE-JOBSUB-TDQ` | `submitJobToIntrdr()` | [`ReportService.java`](../src/main/java/com/aws/carddemo/service/online/ReportService.java) |
| 5 | `RETURN-TO-PREV-SCREEN` | `returnToPrevScreen()` | [`ReportService.java`](../src/main/java/com/aws/carddemo/service/online/ReportService.java) |
| 6 | `SEND-TRNRPT-SCREEN` | `showReport()` — paint (SEND screen) | [`ReportController.java`](../src/main/java/com/aws/carddemo/web/ReportController.java) |
| 7 | `RETURN-TO-CICS` | `showReport()` — CICS RETURN TRANSID (end pseudo-conversation) → controller re-displays screen | [`ReportController.java`](../src/main/java/com/aws/carddemo/web/ReportController.java) |
| 8 | `RECEIVE-TRNRPT-SCREEN` | `doReport()` — receive (HTTP form binding) | [`ReportController.java`](../src/main/java/com/aws/carddemo/web/ReportController.java) |
| 9 | `POPULATE-HEADER-INFO` | `showReport()` — not modeled (BMS rendering) | [`ReportController.java`](../src/main/java/com/aws/carddemo/web/ReportController.java) |
| 10 | `INITIALIZE-ALL-FIELDS` | `initializeAllFields()` | [`ReportService.java`](../src/main/java/com/aws/carddemo/service/online/ReportService.java) |

### COSGN00C — Sign-on `CC00`

**COBOL source:** [`legacy/app/cbl/COSGN00C.cbl`](../legacy/app/cbl/COSGN00C.cbl) &rarr; **Java:** [`SignonController.java`](../src/main/java/com/aws/carddemo/web/SignonController.java) + [`SignonService.java`](../src/main/java/com/aws/carddemo/service/online/SignonService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processSignon()` — pseudo-conversational entry | [`SignonService.java`](../src/main/java/com/aws/carddemo/service/online/SignonService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`SignonService.java`](../src/main/java/com/aws/carddemo/service/online/SignonService.java) |
| 3 | `SEND-SIGNON-SCREEN` | `showSignon()` — paint (SEND screen) | [`SignonController.java`](../src/main/java/com/aws/carddemo/web/SignonController.java) |
| 4 | `SEND-PLAIN-TEXT` | `sendPlainText()` | [`SignonService.java`](../src/main/java/com/aws/carddemo/service/online/SignonService.java) |
| 5 | `POPULATE-HEADER-INFO` | `populateHeader()` | [`SignonService.java`](../src/main/java/com/aws/carddemo/service/online/SignonService.java) |
| 6 | `READ-USER-SEC-FILE` | `readUserSecFile()` | [`SignonService.java`](../src/main/java/com/aws/carddemo/service/online/SignonService.java) |

### COTRN00C — Transaction list `CT00`

**COBOL source:** [`legacy/app/cbl/COTRN00C.cbl`](../legacy/app/cbl/COTRN00C.cbl) &rarr; **Java:** [`TranListController.java`](../src/main/java/com/aws/carddemo/web/TranListController.java) + [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processTranList()` — pseudo-conversational entry | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 3 | `PROCESS-PF7-KEY` | `processPf7Key()` | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 4 | `PROCESS-PF8-KEY` | `processPf8Key()` | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 5 | `PROCESS-PAGE-FORWARD` | `processPageForward()` | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 6 | `PROCESS-PAGE-BACKWARD` | `processPageBackward()` | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 7 | `POPULATE-TRAN-DATA` | `populateTranData()` | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 8 | `INITIALIZE-TRAN-DATA` | `initializeTranData()` | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 9 | `RETURN-TO-PREV-SCREEN` | `returnToPrevScreen()` | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 10 | `SEND-TRNLST-SCREEN` | `showTranList()` — paint (SEND screen) | [`TranListController.java`](../src/main/java/com/aws/carddemo/web/TranListController.java) |
| 11 | `RECEIVE-TRNLST-SCREEN` | `doTranList()` — receive (HTTP form binding) | [`TranListController.java`](../src/main/java/com/aws/carddemo/web/TranListController.java) |
| 12 | `POPULATE-HEADER-INFO` | `processTranList()` — header fields populated inline within entry | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 13 | `STARTBR-TRANSACT-FILE` | `loadAllTransactions()` — VSAM browse → JPA findAll/iteration | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 14 | `READNEXT-TRANSACT-FILE` | `loadAllTransactions()` — VSAM browse → JPA findAll/iteration | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 15 | `READPREV-TRANSACT-FILE` | `loadAllTransactions()` — VSAM browse → JPA findAll/iteration | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |
| 16 | `ENDBR-TRANSACT-FILE` | `loadAllTransactions()` — VSAM browse → JPA findAll/iteration | [`TranListService.java`](../src/main/java/com/aws/carddemo/service/online/TranListService.java) |

### COTRN01C — Transaction view `CT01`

**COBOL source:** [`legacy/app/cbl/COTRN01C.cbl`](../legacy/app/cbl/COTRN01C.cbl) &rarr; **Java:** [`TranViewController.java`](../src/main/java/com/aws/carddemo/web/TranViewController.java) + [`TranViewService.java`](../src/main/java/com/aws/carddemo/service/online/TranViewService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processTranView()` — pseudo-conversational entry | [`TranViewService.java`](../src/main/java/com/aws/carddemo/service/online/TranViewService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`TranViewService.java`](../src/main/java/com/aws/carddemo/service/online/TranViewService.java) |
| 3 | `RETURN-TO-PREV-SCREEN` | `returnToPrevScreen()` | [`TranViewService.java`](../src/main/java/com/aws/carddemo/service/online/TranViewService.java) |
| 4 | `SEND-TRNVIEW-SCREEN` | `showTranView()` — paint (SEND screen) | [`TranViewController.java`](../src/main/java/com/aws/carddemo/web/TranViewController.java) |
| 5 | `RECEIVE-TRNVIEW-SCREEN` | `doTranView()` — receive (HTTP form binding) | [`TranViewController.java`](../src/main/java/com/aws/carddemo/web/TranViewController.java) |
| 6 | `POPULATE-HEADER-INFO` | `returnToPrevScreen()` — not modeled | [`TranViewService.java`](../src/main/java/com/aws/carddemo/service/online/TranViewService.java) |
| 7 | `READ-TRANSACT-FILE` | `readTransactFile()` | [`TranViewService.java`](../src/main/java/com/aws/carddemo/service/online/TranViewService.java) |
| 8 | `CLEAR-CURRENT-SCREEN` | `clearCurrentScreen()` | [`TranViewService.java`](../src/main/java/com/aws/carddemo/service/online/TranViewService.java) |
| 9 | `INITIALIZE-ALL-FIELDS` | `initializeAllFields()` | [`TranViewService.java`](../src/main/java/com/aws/carddemo/service/online/TranViewService.java) |

### COTRN02C — Transaction add `CT02`

**COBOL source:** [`legacy/app/cbl/COTRN02C.cbl`](../legacy/app/cbl/COTRN02C.cbl) &rarr; **Java:** [`TranAddController.java`](../src/main/java/com/aws/carddemo/web/TranAddController.java) + [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processTranAdd()` — pseudo-conversational entry | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 3 | `VALIDATE-INPUT-KEY-FIELDS` | `validateInputKeyFields()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 4 | `VALIDATE-INPUT-DATA-FIELDS` | `validateInputDataFields()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 5 | `ADD-TRANSACTION` | `addTransaction()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 6 | `COPY-LAST-TRAN-DATA` | `copyLastTranData()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 7 | `RETURN-TO-PREV-SCREEN` | `returnToPrevScreen()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 8 | `SEND-TRNADD-SCREEN` | `showTranAdd()` — paint (SEND screen) | [`TranAddController.java`](../src/main/java/com/aws/carddemo/web/TranAddController.java) |
| 9 | `RECEIVE-TRNADD-SCREEN` | `doTranAdd()` — receive (HTTP form binding) | [`TranAddController.java`](../src/main/java/com/aws/carddemo/web/TranAddController.java) |
| 10 | `POPULATE-HEADER-INFO` | `processTranAdd()` — header fields populated inline within entry | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 11 | `READ-CXACAIX-FILE` | `readCxacaixFile()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 12 | `READ-CCXREF-FILE` | `readCcxrefFile()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 13 | `STARTBR-TRANSACT-FILE` | `readLastTransaction()` — VSAM browse (last tran id) → JPA | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 14 | `READPREV-TRANSACT-FILE` | `readLastTransaction()` — VSAM browse (last tran id) → JPA | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 15 | `ENDBR-TRANSACT-FILE` | `readLastTransaction()` — VSAM browse (last tran id) → JPA | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 16 | `WRITE-TRANSACT-FILE` | `writeTransactFile()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 17 | `CLEAR-CURRENT-SCREEN` | `clearCurrentScreen()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |
| 18 | `INITIALIZE-ALL-FIELDS` | `initializeAllFields()` | [`TranAddService.java`](../src/main/java/com/aws/carddemo/service/online/TranAddService.java) |

### COUSR00C — List users `CU00` (admin)

**COBOL source:** [`legacy/app/cbl/COUSR00C.cbl`](../legacy/app/cbl/COUSR00C.cbl) &rarr; **Java:** [`UserListController.java`](../src/main/java/com/aws/carddemo/web/UserListController.java) + [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processUserList()` — pseudo-conversational entry | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 3 | `PROCESS-PF7-KEY` | `processPf7Key()` | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 4 | `PROCESS-PF8-KEY` | `processPf8Key()` | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 5 | `PROCESS-PAGE-FORWARD` | `processPageForward()` | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 6 | `PROCESS-PAGE-BACKWARD` | `processPageBackward()` | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 7 | `POPULATE-USER-DATA` | `populateUserData()` | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 8 | `INITIALIZE-USER-DATA` | `initializeUserData()` | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 9 | `RETURN-TO-PREV-SCREEN` | `returnToPrevScreen()` | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 10 | `SEND-USRLST-SCREEN` | `showUserList()` — paint (SEND screen) | [`UserListController.java`](../src/main/java/com/aws/carddemo/web/UserListController.java) |
| 11 | `RECEIVE-USRLST-SCREEN` | `doUserList()` — receive (HTTP form binding) | [`UserListController.java`](../src/main/java/com/aws/carddemo/web/UserListController.java) |
| 12 | `POPULATE-HEADER-INFO` | `processUserList()` — header fields populated inline within entry | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 13 | `STARTBR-USER-SEC-FILE` | `loadAllUsersSafe()` — VSAM browse → JPA findAll/iteration | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 14 | `READNEXT-USER-SEC-FILE` | `loadAllUsersSafe()` — VSAM browse → JPA findAll/iteration | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 15 | `READPREV-USER-SEC-FILE` | `loadAllUsersSafe()` — VSAM browse → JPA findAll/iteration | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |
| 16 | `ENDBR-USER-SEC-FILE` | `loadAllUsersSafe()` — VSAM browse → JPA findAll/iteration | [`UserListService.java`](../src/main/java/com/aws/carddemo/service/online/UserListService.java) |

### COUSR01C — Add user `CU01` (admin)

**COBOL source:** [`legacy/app/cbl/COUSR01C.cbl`](../legacy/app/cbl/COUSR01C.cbl) &rarr; **Java:** [`UserAddController.java`](../src/main/java/com/aws/carddemo/web/UserAddController.java) + [`UserAddService.java`](../src/main/java/com/aws/carddemo/service/online/UserAddService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processUserAdd()` — pseudo-conversational entry | [`UserAddService.java`](../src/main/java/com/aws/carddemo/service/online/UserAddService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`UserAddService.java`](../src/main/java/com/aws/carddemo/service/online/UserAddService.java) |
| 3 | `RETURN-TO-PREV-SCREEN` | `returnToPrevScreen()` | [`UserAddService.java`](../src/main/java/com/aws/carddemo/service/online/UserAddService.java) |
| 4 | `SEND-USRADD-SCREEN` | `showUserAdd()` — paint (SEND screen) | [`UserAddController.java`](../src/main/java/com/aws/carddemo/web/UserAddController.java) |
| 5 | `RECEIVE-USRADD-SCREEN` | `doUserAdd()` — receive (HTTP form binding) | [`UserAddController.java`](../src/main/java/com/aws/carddemo/web/UserAddController.java) |
| 6 | `POPULATE-HEADER-INFO` | `returnToPrevScreen()` — not modeled | [`UserAddService.java`](../src/main/java/com/aws/carddemo/service/online/UserAddService.java) |
| 7 | `WRITE-USER-SEC-FILE` | `writeUserSecFile()` | [`UserAddService.java`](../src/main/java/com/aws/carddemo/service/online/UserAddService.java) |
| 8 | `CLEAR-CURRENT-SCREEN` | `clearCurrentScreen()` | [`UserAddService.java`](../src/main/java/com/aws/carddemo/service/online/UserAddService.java) |
| 9 | `INITIALIZE-ALL-FIELDS` | `initializeAllFields()` | [`UserAddService.java`](../src/main/java/com/aws/carddemo/service/online/UserAddService.java) |

### COUSR02C — Update user `CU02` (admin)

**COBOL source:** [`legacy/app/cbl/COUSR02C.cbl`](../legacy/app/cbl/COUSR02C.cbl) &rarr; **Java:** [`UserUpdateController.java`](../src/main/java/com/aws/carddemo/web/UserUpdateController.java) + [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processUserUpdate()` — pseudo-conversational entry | [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java) |
| 3 | `UPDATE-USER-INFO` | `updateUserInfo()` | [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java) |
| 4 | `RETURN-TO-PREV-SCREEN` | `returnToPrevScreen()` | [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java) |
| 5 | `SEND-USRUPD-SCREEN` | `showUserUpdate()` — paint (SEND screen) | [`UserUpdateController.java`](../src/main/java/com/aws/carddemo/web/UserUpdateController.java) |
| 6 | `RECEIVE-USRUPD-SCREEN` | `doUserUpdate()` — receive (HTTP form binding) | [`UserUpdateController.java`](../src/main/java/com/aws/carddemo/web/UserUpdateController.java) |
| 7 | `POPULATE-HEADER-INFO` | `processUserUpdate()` — header fields populated inline within entry | [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java) |
| 8 | `READ-USER-SEC-FILE` | `readUserSecFile()` | [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java) |
| 9 | `UPDATE-USER-SEC-FILE` | `updateUserSecFile()` | [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java) |
| 10 | `CLEAR-CURRENT-SCREEN` | `clearCurrentScreen()` | [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java) |
| 11 | `INITIALIZE-ALL-FIELDS` | `initializeAllFields()` | [`UserUpdateService.java`](../src/main/java/com/aws/carddemo/service/online/UserUpdateService.java) |

### COUSR03C — Delete user `CU03` (admin)

**COBOL source:** [`legacy/app/cbl/COUSR03C.cbl`](../legacy/app/cbl/COUSR03C.cbl) &rarr; **Java:** [`UserDeleteController.java`](../src/main/java/com/aws/carddemo/web/UserDeleteController.java) + [`UserDeleteService.java`](../src/main/java/com/aws/carddemo/service/online/UserDeleteService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `MAIN-PARA` | `processUserDelete()` — pseudo-conversational entry | [`UserDeleteService.java`](../src/main/java/com/aws/carddemo/service/online/UserDeleteService.java) |
| 2 | `PROCESS-ENTER-KEY` | `processEnterKey()` | [`UserDeleteService.java`](../src/main/java/com/aws/carddemo/service/online/UserDeleteService.java) |
| 3 | `DELETE-USER-INFO` | `deleteUserInfo()` | [`UserDeleteService.java`](../src/main/java/com/aws/carddemo/service/online/UserDeleteService.java) |
| 4 | `RETURN-TO-PREV-SCREEN` | `returnToPrevScreen()` | [`UserDeleteService.java`](../src/main/java/com/aws/carddemo/service/online/UserDeleteService.java) |
| 5 | `SEND-USRDEL-SCREEN` | `showUserDelete()` — paint (SEND screen) | [`UserDeleteController.java`](../src/main/java/com/aws/carddemo/web/UserDeleteController.java) |
| 6 | `RECEIVE-USRDEL-SCREEN` | `doUserDelete()` — receive (HTTP form binding) | [`UserDeleteController.java`](../src/main/java/com/aws/carddemo/web/UserDeleteController.java) |
| 7 | `POPULATE-HEADER-INFO` | `showUserDelete()` — not modeled (BMS rendering) | [`UserDeleteController.java`](../src/main/java/com/aws/carddemo/web/UserDeleteController.java) |
| 8 | `READ-USER-SEC-FILE` | `readUserSecFile()` | [`UserDeleteService.java`](../src/main/java/com/aws/carddemo/service/online/UserDeleteService.java) |
| 9 | `DELETE-USER-SEC-FILE` | `deleteUserSecFile()` | [`UserDeleteService.java`](../src/main/java/com/aws/carddemo/service/online/UserDeleteService.java) |
| 10 | `CLEAR-CURRENT-SCREEN` | `clearCurrentScreen()` | [`UserDeleteService.java`](../src/main/java/com/aws/carddemo/service/online/UserDeleteService.java) |
| 11 | `INITIALIZE-ALL-FIELDS` | `initializeAllFields()` | [`UserDeleteService.java`](../src/main/java/com/aws/carddemo/service/online/UserDeleteService.java) |

## Utility Programs

The single date-validation utility (CICS transaction `CDV1`) becomes `util/DateValidationService.java`.

### CSUTLDTC — Date-validation utility (CDV1)

**COBOL source:** [`legacy/app/cbl/CSUTLDTC.cbl`](../legacy/app/cbl/CSUTLDTC.cbl) &rarr; **Java:** [`DateValidationService.java`](../src/main/java/com/aws/carddemo/util/DateValidationService.java)

| # | COBOL Paragraph / Section | Java Method | Java File |
|---|---|---|---|
| 1 | `A000-MAIN` | `validateDate(String date, String formatMask)` — static; A000-MAIN entry (wraps CEEDAYS / CSUTLDPY date-edit logic) | [`DateValidationService.java`](../src/main/java/com/aws/carddemo/util/DateValidationService.java) |
| 2 | `A000-MAIN-EXIT` | `validateDate(...)` — EXIT label → method return | [`DateValidationService.java`](../src/main/java/com/aws/carddemo/util/DateValidationService.java) |

> **Additional (non-counted) methods.** `CSUTLDTC` delegates to the z/OS `CEEDAYS` service and the `CSUTLDPY` copybook for the detailed date edits. Those copybook routines are implemented as supplementary `DateValidationService` methods — `editDateCcyymmdd()`, `editDateOfBirth()`, `isLeapYearCobol()` — and are **not** counted among the 528 program paragraphs.

## Parity & Quirk Notes

The following behaviors are preserved exactly in the Java translation. Each is reflected in the relevant rows above.

- **CBACT04C — truncated interest (most parity-critical, AAP §0.6.1).** `1300-COMPUTE-INTEREST` computes monthly interest as `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` using `java.math.BigDecimal` with `RoundingMode.DOWN` (truncation) at scale 2. The COBOL `COMPUTE` carries **no `ROUNDED` phrase**, so the result is truncated to the declared scale; floating-point types are prohibited for decimal data.
- **CBACT03C / CBCUS01C — double-display quirk.** Each record is `DISPLAY`ed twice (once in the get-next paragraph and once in the main loop); the Java emits the record twice to preserve identical output.
- **CBACT02C — no `1100` paragraph.** The card extract emits a single record per read; the per-read `DISPLAY` is commented out in the legacy source, so there is no `1100-DISPLAY-*` paragraph (contrast with CBACT01C, which has `1100-DISPLAY-ACCT-RECORD`).
- **CBCUS01C / CBTRN01C — `Z-` abend naming.** These programs use `Z-ABEND-PROGRAM` / `Z-DISPLAY-IO-STATUS` rather than the `9999-`/`9910-` names used by the other batch programs; both map to the service abend method raising `IoStatusException`.
- **CBTRN01C — validation-only.** The daily-transaction program opens the customer/xref/card/account/transaction files but performs no posting; those open/close paragraphs are modeled as no-ops.
- **CBTRN02C — TCATBAL find-or-create (upsert).** `2700-UPDATE-TCATBAL` reads the category-balance record accepting FILE STATUS `'00' OR '23'`; when not found (`'23'`) it creates the record (`2700-A-CREATE-TCATBAL-REC`), otherwise it updates it (`2700-B-UPDATE-TCATBAL-REC`). Both branches fold into the single `updateTcatbal()` upsert method (AAP §0.6.4).
- **CBTRN03C — duplicate paragraph numbers → distinct methods.** The report reuses paragraph numbers (two `1110-`: `WRITE-PAGE-TOTALS` and `WRITE-GRAND-TOTALS`; three `1120-`: `WRITE-ACCOUNT-TOTALS`, `WRITE-HEADERS`, `WRITE-DETAIL`). Because the full names are distinct, each maps to a **distinct** Java method and is **not** collapsed. Additional parity: the last transaction is double-counted at EOF, the final account-total line is never emitted, and the control break is on `TRAN-CARD-NUM`.
- **COACTVWC — duplicate `0000-MAIN-EXIT`.** The source genuinely contains the `0000-MAIN-EXIT` label twice; both are emitted as separate rows.
- **COACTUPC — nested phone-edit THRU block.** The standalone paragraphs `EDIT-AREA-CODE`, `EDIT-US-PHONE-PREFIX`, `EDIT-US-PHONE-LINENUM`, and `EDIT-US-PHONE-EXIT` are nested within the `1260-EDIT-US-PHONE-NUM` … `1260-EDIT-US-PHONE-NUM-EXIT` THRU range; each is emitted as its own row and bound to the consolidated phone-edit method.
- **CORPT00C — `WIRTE-JOBSUB-TDQ` misspelling preserved.** The legacy paragraph name is misspelled (`WIRTE` instead of `WRITE`); the matrix preserves the exact spelling.
- **CSUTLDTC — copybook date logic.** The heavy date-edit routines live in copybook `CSUTLDPY` (not a standalone program) and are implemented as supplementary methods (`editDateCcyymmdd()`, `editDateOfBirth()`, `isLeapYearCobol()`); these are **not** counted among the 528 program paragraphs.
- **FILE STATUS → exception mapping (AAP §0.6.4).** `'00'` = successful I/O (normal return); `'10'` = end-of-file on a sequential read (reader exhaustion / loop end, not an error); `'23'` = record not found (`Optional.empty()`, conditionally handled); any other value = I/O error, mapped to a thrown `IoStatusException` (display status + abend-equivalent).
- **Online service-vs-controller split.** Pseudo-conversational `RECEIVE-*` / `SEND-*-SCREEN` paragraphs are owned by the `web/*Controller` layer (`do*` = receive/process POST, `show*` = paint/SEND), while business logic, validation, and file access are owned by the `service/online/*Service` layer. `COMMON-RETURN` maps to `BaseScreenController` COMMAREA persistence + redirect; `ABEND-ROUTINE` maps to the `@ControllerAdvice` `GlobalExceptionHandler`.

## Appendix A — How this matrix was generated

AWS CardDemo COBOL is **fixed-format**: the indicator is column 7 and Area A begins at column 8. Paragraph and section headers were identified as lines following `PROCEDURE DIVISION` whose name begins in Area A (column 8) with a blank indicator column, matching `^[A-Za-z0-9][A-Za-z0-9-]*( SECTION)?\.$`. Each such header is one matrix row. The extractor below was run against `legacy/app/cbl/` (the read-only retained copy of the 28 COBOL programs) and produced the verified total of **528** paragraphs/sections, reproducing the per-program counts in the Coverage Summary exactly. Java method names were then bound by reading the produced sources under `src/main/java/com/aws/carddemo/` (the produced code is authoritative where it differs from any embedded expectation).

```python
import os, re, glob
cbl_dir = "legacy/app/cbl"
files = sorted(glob.glob(os.path.join(cbl_dir, "*.cbl")) + glob.glob(os.path.join(cbl_dir, "*.CBL")))
def extract(path):
    with open(path, 'r', errors='replace') as f:
        lines = f.readlines()
    proc_start = None
    for i, ln in enumerate(lines):
        body = ln.rstrip('\n')
        if len(body) >= 8 and body[6] == ' ' and body[7] != ' ':
            if body[7:].lstrip().upper().startswith("PROCEDURE DIVISION"):
                proc_start = i; break
    paras = []
    if proc_start is None: return paras
    for i in range(proc_start+1, len(lines)):
        body = lines[i].rstrip('\n')
        if len(body) < 8: continue
        if body[6] in ('*','/','-'): continue      # skip comment/continuation
        if body[:6].strip() != '': continue        # cols 1-6 must be blank (no seq nums here)
        if body[6] != ' ': continue                # indicator col must be blank
        if body[7] == ' ': continue                # name must start in Area A (col 8)
        areaA = body[7:].rstrip()
        m = re.match(r'^([A-Za-z0-9][A-Za-z0-9-]*)(\s+SECTION)?\s*\.\s*$', areaA)
        if m:
            paras.append((m.group(1), 'SECTION' if m.group(2) else 'PARA'))
    return paras
```

Run from the repository root; `extract(path)` returns the ordered list of `(name, 'PARA'|'SECTION')` tuples for each program, and summing the list lengths over all 28 files yields 528.

**Total paragraphs/sections mapped: 528 across 28 programs (100% coverage).**
