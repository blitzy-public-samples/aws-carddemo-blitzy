# Appendix E — Source Code Cross-Reference

[Back to Executive Summary](../00-executive-summary.md) | [Proprietary Utility Inventory](../01-proprietary-utility-inventory.md) | [Appendix A — CICS Command Reference](./A-cics-command-reference.md) | [Appendix B — VSAM Dataset Catalog](./B-vsam-dataset-catalog.md)

---

## 1. Overview

This appendix serves as the **master index** for the CardDemo proprietary utility migration analysis. Every technical claim made in the preceding sections — from the [Proprietary Utility Inventory](../01-proprietary-utility-inventory.md) through the [Risk Assessment](../05-risk-assessment.md) — traces back to a specific source file and line number cited here.

The cross-reference covers all **28 COBOL source programs** in `app/cbl/`:

| Category | Count | Programs |
|----------|-------|----------|
| Batch programs | 9 | CBACT01C, CBACT02C, CBACT03C, CBACT04C, CBCUS01C, CBSTM03A, CBTRN01C, CBTRN02C, CBTRN03C |
| Batch I/O subroutine | 1 | CBSTM03B |
| Utility program | 1 | CSUTLDTC |
| Online CICS programs | 17 | COACTUPC, COACTVWC, COADM01C, COBIL00C, COCRDLIC, COCRDSLC, COCRDUPC, COMEN01C, CORPT00C, COSGN00C, COTRN00C, COTRN01C, COTRN02C, COUSR00C, COUSR01C, COUSR02C, COUSR03C |

> **Note:** The Agent Action Plan references COTRN04C and COTRN05C as sub-programs; however, these files do not exist in the `app/cbl/` directory. No source files named `COTRN04C.cbl` or `COTRN05C.cbl` are present in the repository. All cross-reference data below is derived from the 28 files that actually exist on disk.

---

## 2. How to Use This Reference

### Citation Format

Throughout the migration analysis documentation, source citations follow the format:

```
Source: app/cbl/<FILENAME>:<LINE_NUMBER>
```

For example, `app/cbl/CSUTLDTC.cbl:116` refers to line 116 of the `CSUTLDTC.cbl` source file, which contains the `CALL "CEEDAYS"` statement.

### Column Definitions

| Column | Description |
|--------|-------------|
| **Source File** | COBOL source file name in `app/cbl/` |
| **Type** | Program classification: Batch, Batch I/O, Utility, or Online (CICS) |
| **CEE3ABD Line** | Line number of `CALL 'CEE3ABD'` statement, or `—` if not present |
| **CEEDAYS Line** | Line number of `CALL "CEEDAYS"` statement, or `—` if not present |
| **VSAM Files** | CICS DATASET/FILE names or batch SELECT ASSIGN targets accessed by the program |
| **COPY Statements** | Copybooks included via COBOL `COPY` directive (unique names, excluding duplicates from REPLACING) |
| **Other CALLs** | Non-LE CALL statements (e.g., `CALL 'CBSTM03B'`, `CALL 'CSUTLDTC'`) |

### Navigating from Other Sections

- **[Section 01 — Proprietary Utility Inventory](../01-proprietary-utility-inventory.md)**: Use this appendix to verify utility counts and file locations cited in the inventory tables.
- **[Section 02 — External Documentation Research](../02-external-documentation-research.md)**: Cross-reference IBM documentation findings against actual invocation patterns listed here.
- **[Section 03 — Dependency Impact Analysis](../03-dependency-impact-analysis.md)**: Verify per-program dependency counts and complexity ratings against the matrices below.
- **[Section 04 — Migration Strategy Per Utility](../04-migration-strategy-per-utility.md)**: Confirm which programs require each migration strategy by consulting the command matrices.
- **[Section 05 — Risk Assessment](../05-risk-assessment.md)**: Validate risk classifications by reviewing the dependency heat map at the end of this appendix.
- **[Appendix A — CICS Command Reference](./A-cics-command-reference.md)**: Drill into per-command behavioral specifications for each EXEC CICS entry listed here.
- **[Appendix B — VSAM Dataset Catalog](./B-vsam-dataset-catalog.md)**: Map VSAM dataset names referenced here to their KSDS cluster definitions and RDS migration targets.
- **[Appendix C — Batch Job Dependency Map](./C-batch-job-dependency-map.md)**: Trace batch program dependencies to their JCL job chain context.
- **[Appendix D — BMS Screen Inventory](./D-bms-screen-inventory.md)**: Map SEND MAP/RECEIVE MAP references to their BMS mapset definitions.

---

## 3. Batch Programs — Proprietary Dependency Matrix

The following table catalogs every proprietary dependency for the 9 batch programs and 1 batch I/O subroutine. Each CEE3ABD line number was verified by direct `grep` against the source files.

| Source File | Type | CEE3ABD Line | CEEDAYS | VSAM Files (SELECT ASSIGN) | COPY Statements | Other CALLs |
|---|---|---|---|---|---|---|
| [CBACT01C.cbl](../../../app/cbl/CBACT01C.cbl) | Batch | 173 | — | ACCTFILE | CVACT01Y | — |
| [CBACT02C.cbl](../../../app/cbl/CBACT02C.cbl) | Batch | 158 | — | CARDFILE | CVACT02Y | — |
| [CBACT03C.cbl](../../../app/cbl/CBACT03C.cbl) | Batch | 158 | — | XREFFILE | CVACT03Y | — |
| [CBACT04C.cbl](../../../app/cbl/CBACT04C.cbl) | Batch | 632 | — | TCATBALF, XREFFILE, DISCGRP, ACCTFILE, TRANSACT | CVTRA01Y, CVACT03Y, CVTRA02Y, CVACT01Y, CVTRA05Y | — |
| [CBCUS01C.cbl](../../../app/cbl/CBCUS01C.cbl) | Batch | 158 | — | CUSTFILE | CVCUS01Y | — |
| [CBSTM03A.CBL](../../../app/cbl/CBSTM03A.CBL) | Batch | 923 | — | STMTFILE, HTMLFILE (output); delegates VSAM I/O to CBSTM03B | COSTM01, CVACT03Y, CUSTREC, CVACT01Y | CALL 'CBSTM03B' (lines 351, 377, 401, 734, 746, 769, 787, 805, 835, 860, 877, 893, 909) |
| [CBSTM03B.CBL](../../../app/cbl/CBSTM03B.CBL) | Batch I/O | — | — | TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE | *(none)* | — |
| [CBTRN01C.cbl](../../../app/cbl/CBTRN01C.cbl) | Batch | 473 | — | DALYTRAN, CUSTFILE, XREFFILE, CARDFILE, ACCTFILE, TRANFILE | CVTRA06Y, CVCUS01Y, CVACT03Y, CVACT02Y, CVACT01Y, CVTRA05Y | — |
| [CBTRN02C.cbl](../../../app/cbl/CBTRN02C.cbl) | Batch | 711 | — | DALYTRAN, TRANFILE, XREFFILE, DALYREJS, ACCTFILE, TCATBALF | CVTRA06Y, CVTRA05Y, CVACT03Y, CVACT01Y, CVTRA01Y | — |
| [CBTRN03C.cbl](../../../app/cbl/CBTRN03C.cbl) | Batch | 630 | — | TRANFILE, CARDXREF, TRANTYPE, TRANCATG, TRANREPT, DATEPARM | CVTRA05Y, CVACT03Y, CVTRA03Y, CVTRA04Y, CVTRA07Y | — |

### Batch Program Notes

- **CBSTM03A.CBL** delegates all VSAM file I/O to **CBSTM03B.CBL** via 13 `CALL 'CBSTM03B'` invocations using the `WS-M03B-AREA` communication block. CBSTM03A itself only opens sequential output files (STMTFILE, HTMLFILE).
  - Source: `app/cbl/CBSTM03A.CBL:351` (first CALL), `app/cbl/CBSTM03A.CBL:909` (last CALL)
- **CBSTM03B.CBL** is the only batch program that does **not** call `CEE3ABD`. It serves as a centralized I/O subroutine handling OPEN, READ, WRITE, and REWRITE operations for four VSAM files (TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE).
  - Source: `app/cbl/CBSTM03B.CBL:31-49` (SELECT statements), `app/cbl/CBSTM03B.CBL:103-108` (operation flags)
- **CBACT04C.cbl** has the most complex batch dependency footprint, accessing 5 VSAM files and including 5 copybooks. It performs interest calculation across transaction categories.
  - Source: `app/cbl/CBACT04C.cbl:28-53` (SELECT statements), `app/cbl/CBACT04C.cbl:97-117` (COPY statements)
- **CBTRN01C.cbl** accesses the most batch VSAM files (6 files) for daily transaction posting.
  - Source: `app/cbl/CBTRN01C.cbl:29-58` (SELECT statements)
- **CBTRN02C.cbl** accesses 6 VSAM files for transaction validation, including DALYREJS for rejected transactions.
  - Source: `app/cbl/CBTRN02C.cbl:29-57` (SELECT statements)

---

## 4. Utility Program — Proprietary Dependency Detail

| Source File | Type | CALL Target | Line(s) | Parameters | Purpose |
|---|---|---|---|---|---|
| [CSUTLDTC.cbl](../../../app/cbl/CSUTLDTC.cbl) | Utility | `CALL "CEEDAYS"` | 116 | WS-DATE-TO-TEST, WS-DATE-FORMAT, OUTPUT-LILLIAN, FEEDBACK-CODE | Converts character date to Lilian integer format using picture string |

### CSUTLDTC Invocation Details

**CEEDAYS Call Site** — `app/cbl/CSUTLDTC.cbl:116-120`:
```cobol
           CALL "CEEDAYS" USING
                  WS-DATE-TO-TEST,
                  WS-DATE-FORMAT,
                  OUTPUT-LILLIAN,
                  FEEDBACK-CODE
```

**Parameters defined at:**
- `WS-DATE-TO-TEST` — Line 25: Vstring (variable-length string) containing the date to validate
- `WS-DATE-FORMAT` — Line 33: Vstring containing the date format picture string (e.g., `"MM/DD/YYYY"`)
- `OUTPUT-LILLIAN` — Line 41: `PIC S9(9) BINARY` — Lilian day number output (days since October 14, 1582)
- `FEEDBACK-CODE` — Lines 60–80: LE feedback token with condition codes for 9 error categories

**Feedback Code Conditions** — `app/cbl/CSUTLDTC.cbl:62-70`:

| 88-Level Name | Meaning |
|---|---|
| `FC-INVALID-DATE` | Date is valid (severity 0) |
| `FC-INSUFFICIENT-DATA` | Insufficient data provided |
| `FC-BAD-DATE-VALUE` | Invalid date value |
| `FC-INVALID-ERA` | Invalid era specified |
| `FC-UNSUPP-RANGE` | Unsupported date range |
| `FC-INVALID-MONTH` | Invalid month value |
| `FC-BAD-PIC-STRING` | Bad picture string format |
| `FC-NON-NUMERIC-DATA` | Non-numeric data in date |
| `FC-YEAR-IN-ERA-ZERO` | Year in era is zero |

### Programs That Call CSUTLDTC

| Calling Program | Call Lines | Purpose |
|---|---|---|
| [CORPT00C.cbl](../../../app/cbl/CORPT00C.cbl) | 392, 412 | Validates start date and end date for report date range selection |
| [COTRN02C.cbl](../../../app/cbl/COTRN02C.cbl) | 393, 413 | Validates origination date and processing date for new transaction entry |

**CORPT00C call details** — `app/cbl/CORPT00C.cbl:388-394`:
```cobol
                   MOVE WS-START-DATE        TO CSUTLDTC-DATE
                   MOVE WS-DATE-FORMAT       TO CSUTLDTC-DATE-FORMAT
                   MOVE SPACES               TO CSUTLDTC-RESULT
                   CALL 'CSUTLDTC' USING   CSUTLDTC-DATE
                                           CSUTLDTC-DATE-FORMAT
                                           CSUTLDTC-RESULT
```

**COTRN02C call details** — `app/cbl/COTRN02C.cbl:389-395`:
```cobol
           MOVE TORIGDTI OF COTRN2AI TO CSUTLDTC-DATE
           MOVE WS-DATE-FORMAT       TO CSUTLDTC-DATE-FORMAT
           MOVE SPACES               TO CSUTLDTC-RESULT
           CALL 'CSUTLDTC' USING   CSUTLDTC-DATE
                                   CSUTLDTC-DATE-FORMAT
                                   CSUTLDTC-RESULT
```

---

## 5. Online Programs — EXEC CICS Command Cross-Reference

The following master matrix maps each of the 17 online CICS programs to the 18 distinct EXEC CICS command types found in the CardDemo codebase. Each cell contains the line number(s) where the command appears, or `—` if the program does not use that command.

> **Reading this table:** Line numbers reference the `EXEC CICS` statement start. For multi-line commands where `EXEC CICS` appears on one line and the command keyword on the next, the line number refers to the `EXEC CICS` line.

### 5.1 File Control Commands (READ, WRITE, REWRITE, DELETE)

| Source File | READ | WRITE | REWRITE | DELETE | VSAM Datasets Accessed |
|---|---|---|---|---|---|
| [COACTUPC.cbl](../../../app/cbl/COACTUPC.cbl) | 3654, 3703, 3753, 3894, 3921 | — | 4065, 4085 | — | CXACAIX, ACCTDAT, CUSTDAT |
| [COACTVWC.cbl](../../../app/cbl/COACTVWC.cbl) | 727, 776, 826 | — | — | — | CXACAIX, ACCTDAT, CUSTDAT |
| [COADM01C.cbl](../../../app/cbl/COADM01C.cbl) | — | — | — | — | *(none)* |
| [COBIL00C.cbl](../../../app/cbl/COBIL00C.cbl) | 345, 410 | 512 | 379 | — | ACCTDAT, CXACAIX, TRANSACT |
| [COCRDLIC.cbl](../../../app/cbl/COCRDLIC.cbl) | — | — | — | — | CARDDAT, CARDAIX |
| [COCRDSLC.cbl](../../../app/cbl/COCRDSLC.cbl) | 742, 783 | — | — | — | CARDDAT, CARDAIX |
| [COCRDUPC.cbl](../../../app/cbl/COCRDUPC.cbl) | 1382, 1427 | — | 1477 | — | CARDDAT |
| [COMEN01C.cbl](../../../app/cbl/COMEN01C.cbl) | — | — | — | — | *(none)* |
| [CORPT00C.cbl](../../../app/cbl/CORPT00C.cbl) | — | — | — | — | *(none — uses WRITEQ TD instead)* |
| [COSGN00C.cbl](../../../app/cbl/COSGN00C.cbl) | 211 | — | — | — | USRSEC |
| [COTRN00C.cbl](../../../app/cbl/COTRN00C.cbl) | — | — | — | — | TRANSACT |
| [COTRN01C.cbl](../../../app/cbl/COTRN01C.cbl) | 269 | — | — | — | TRANSACT |
| [COTRN02C.cbl](../../../app/cbl/COTRN02C.cbl) | 578, 611 | 713 | — | — | CXACAIX, CCXREF, TRANSACT |
| [COUSR00C.cbl](../../../app/cbl/COUSR00C.cbl) | — | — | — | — | USRSEC |
| [COUSR01C.cbl](../../../app/cbl/COUSR01C.cbl) | — | 240 | — | — | USRSEC |
| [COUSR02C.cbl](../../../app/cbl/COUSR02C.cbl) | 322 | — | 360 | — | USRSEC |
| [COUSR03C.cbl](../../../app/cbl/COUSR03C.cbl) | 269 | — | — | 307 | USRSEC |

### 5.2 Browse Commands (STARTBR, READNEXT, READPREV, ENDBR)

| Source File | STARTBR | READNEXT | READPREV | ENDBR | Browse Dataset |
|---|---|---|---|---|---|
| [COACTUPC.cbl](../../../app/cbl/COACTUPC.cbl) | — | — | — | — | — |
| [COACTVWC.cbl](../../../app/cbl/COACTVWC.cbl) | — | — | — | — | — |
| [COADM01C.cbl](../../../app/cbl/COADM01C.cbl) | — | — | — | — | — |
| [COBIL00C.cbl](../../../app/cbl/COBIL00C.cbl) | 443 | — | 474 | 503 | TRANSACT |
| [COCRDLIC.cbl](../../../app/cbl/COCRDLIC.cbl) | 1129, 1273 | 1146, 1197 | 1294, 1322 | 1258, 1375 | CARDDAT |
| [COCRDSLC.cbl](../../../app/cbl/COCRDSLC.cbl) | — | — | — | — | — |
| [COCRDUPC.cbl](../../../app/cbl/COCRDUPC.cbl) | — | — | — | — | — |
| [COMEN01C.cbl](../../../app/cbl/COMEN01C.cbl) | — | — | — | — | — |
| [CORPT00C.cbl](../../../app/cbl/CORPT00C.cbl) | — | — | — | — | — |
| [COSGN00C.cbl](../../../app/cbl/COSGN00C.cbl) | — | — | — | — | — |
| [COTRN00C.cbl](../../../app/cbl/COTRN00C.cbl) | 593 | 626 | 660 | 694 | TRANSACT |
| [COTRN01C.cbl](../../../app/cbl/COTRN01C.cbl) | — | — | — | — | — |
| [COTRN02C.cbl](../../../app/cbl/COTRN02C.cbl) | 644 | — | 675 | 704 | TRANSACT |
| [COUSR00C.cbl](../../../app/cbl/COUSR00C.cbl) | 588 | 621 | 655 | 689 | USRSEC |
| [COUSR01C.cbl](../../../app/cbl/COUSR01C.cbl) | — | — | — | — | — |
| [COUSR02C.cbl](../../../app/cbl/COUSR02C.cbl) | — | — | — | — | — |
| [COUSR03C.cbl](../../../app/cbl/COUSR03C.cbl) | — | — | — | — | — |

### 5.3 Terminal I/O Commands (SEND MAP, RECEIVE MAP)

| Source File | SEND MAP | RECEIVE MAP | Map Name(s) |
|---|---|---|---|
| [COACTUPC.cbl](../../../app/cbl/COACTUPC.cbl) | 3594 | 1040 | COACTUP (via CCARD-NEXT-MAP / LIT-THISMAP) |
| [COACTVWC.cbl](../../../app/cbl/COACTVWC.cbl) | 583 | 611 | COACTVW (via CCARD-NEXT-MAP / LIT-THISMAP) |
| [COADM01C.cbl](../../../app/cbl/COADM01C.cbl) | 179 | 191 | COADM01 |
| [COBIL00C.cbl](../../../app/cbl/COBIL00C.cbl) | 295 | 308 | COBIL00 |
| [COCRDLIC.cbl](../../../app/cbl/COCRDLIC.cbl) | 939 | 963 | COCRDLI (via LIT-THISMAP / LIT-THISMAPSET) |
| [COCRDSLC.cbl](../../../app/cbl/COCRDSLC.cbl) | 569 | 597 | COCRDSL (via CCARD-NEXT-MAP / LIT-THISMAP) |
| [COCRDUPC.cbl](../../../app/cbl/COCRDUPC.cbl) | 1329 | 579 | COCRDUP (via CCARD-NEXT-MAP / LIT-THISMAP) |
| [COMEN01C.cbl](../../../app/cbl/COMEN01C.cbl) | 189 | 201 | COMEN01 |
| [CORPT00C.cbl](../../../app/cbl/CORPT00C.cbl) | 563, 571 | 598 | CORPT0A |
| [COSGN00C.cbl](../../../app/cbl/COSGN00C.cbl) | 151 | 110 | COSGN00 |
| [COTRN00C.cbl](../../../app/cbl/COTRN00C.cbl) | 534, 542 | 556 | COTRN00 |
| [COTRN01C.cbl](../../../app/cbl/COTRN01C.cbl) | 219 | 232 | COTRN01 |
| [COTRN02C.cbl](../../../app/cbl/COTRN02C.cbl) | 522 | 541 | COTRN02 |
| [COUSR00C.cbl](../../../app/cbl/COUSR00C.cbl) | 529, 537 | 551 | COUSR00 |
| [COUSR01C.cbl](../../../app/cbl/COUSR01C.cbl) | 190 | 203 | COUSR01 |
| [COUSR02C.cbl](../../../app/cbl/COUSR02C.cbl) | 272 | 285 | COUSR02 |
| [COUSR03C.cbl](../../../app/cbl/COUSR03C.cbl) | 219 | 232 | COUSR03 |

### 5.4 Program Control Commands (RETURN, XCTL)

| Source File | RETURN | XCTL | XCTL Target(s) |
|---|---|---|---|
| [COACTUPC.cbl](../../../app/cbl/COACTUPC.cbl) | 1015 | 956 | CDEMO-TO-PROGRAM (dynamic) |
| [COACTVWC.cbl](../../../app/cbl/COACTVWC.cbl) | 402, 885, 904 | 349 | CDEMO-TO-PROGRAM (dynamic) |
| [COADM01C.cbl](../../../app/cbl/COADM01C.cbl) | 107 | 142, 165 | CDEMO-ADMIN-OPT-PGMNAME (line 143), CDEMO-TO-PROGRAM (line 166) |
| [COBIL00C.cbl](../../../app/cbl/COBIL00C.cbl) | 146 | 281 | Via XCTL PROGRAM (dynamic) |
| [COCRDLIC.cbl](../../../app/cbl/COCRDLIC.cbl) | 615, 1430, 1449 | 402, 538, 566 | LIT-MENUPGM (line 402), CCARD-NEXT-PROG (lines 538, 566) |
| [COCRDSLC.cbl](../../../app/cbl/COCRDSLC.cbl) | 402, 828, 846 | 331 | CDEMO-TO-PROGRAM (dynamic) |
| [COCRDUPC.cbl](../../../app/cbl/COCRDUPC.cbl) | 554 | 473 | CDEMO-TO-PROGRAM (dynamic) |
| [COMEN01C.cbl](../../../app/cbl/COMEN01C.cbl) | 107 | 152, 175 | CDEMO-MENU-OPT-PGMNAME (line 153), CDEMO-TO-PROGRAM (line 176) |
| [CORPT00C.cbl](../../../app/cbl/CORPT00C.cbl) | 199, 587 | 548 | CDEMO-TO-PROGRAM (dynamic) |
| [COSGN00C.cbl](../../../app/cbl/COSGN00C.cbl) | 98, 171 | 231, 236 | Dynamic XCTL targets |
| [COTRN00C.cbl](../../../app/cbl/COTRN00C.cbl) | 138 | 192, 518 | CDEMO-TO-PROGRAM (dynamic) |
| [COTRN01C.cbl](../../../app/cbl/COTRN01C.cbl) | 136 | 205 | CDEMO-TO-PROGRAM (dynamic) |
| [COTRN02C.cbl](../../../app/cbl/COTRN02C.cbl) | 156, 530 | 508 | CDEMO-TO-PROGRAM (dynamic) |
| [COUSR00C.cbl](../../../app/cbl/COUSR00C.cbl) | 141 | 196, 206, 514 | CDEMO-TO-PROGRAM (dynamic) |
| [COUSR01C.cbl](../../../app/cbl/COUSR01C.cbl) | 107 | 175 | CDEMO-TO-PROGRAM (dynamic) |
| [COUSR02C.cbl](../../../app/cbl/COUSR02C.cbl) | 135 | 258 | CDEMO-TO-PROGRAM (dynamic) |
| [COUSR03C.cbl](../../../app/cbl/COUSR03C.cbl) | 134 | 205 | CDEMO-TO-PROGRAM (dynamic) |

### 5.5 Error Handling Commands (HANDLE ABEND, ABEND)

| Source File | HANDLE ABEND | ABEND | Abend Code |
|---|---|---|---|
| [COACTUPC.cbl](../../../app/cbl/COACTUPC.cbl) | 862, 4218 | 4222 | `'9999'` |
| [COACTVWC.cbl](../../../app/cbl/COACTVWC.cbl) | 264, 930 | 934 | Abend triggered after error |
| [COADM01C.cbl](../../../app/cbl/COADM01C.cbl) | — | — | — |
| [COBIL00C.cbl](../../../app/cbl/COBIL00C.cbl) | — | — | — |
| [COCRDLIC.cbl](../../../app/cbl/COCRDLIC.cbl) | — | — | — |
| [COCRDSLC.cbl](../../../app/cbl/COCRDSLC.cbl) | 250, 871 | 875 | Abend triggered after error |
| [COCRDUPC.cbl](../../../app/cbl/COCRDUPC.cbl) | 370, 1546 | 1550 | Abend triggered after error |
| [COMEN01C.cbl](../../../app/cbl/COMEN01C.cbl) | — | — | — |
| [CORPT00C.cbl](../../../app/cbl/CORPT00C.cbl) | — | — | — |
| [COSGN00C.cbl](../../../app/cbl/COSGN00C.cbl) | — | — | — |
| [COTRN00C.cbl](../../../app/cbl/COTRN00C.cbl) | — | — | — |
| [COTRN01C.cbl](../../../app/cbl/COTRN01C.cbl) | — | — | — |
| [COTRN02C.cbl](../../../app/cbl/COTRN02C.cbl) | — | — | — |
| [COUSR00C.cbl](../../../app/cbl/COUSR00C.cbl) | — | — | — |
| [COUSR01C.cbl](../../../app/cbl/COUSR01C.cbl) | — | — | — |
| [COUSR02C.cbl](../../../app/cbl/COUSR02C.cbl) | — | — | — |
| [COUSR03C.cbl](../../../app/cbl/COUSR03C.cbl) | — | — | — |

### 5.6 System Service Commands (ASKTIME, FORMATTIME, ASSIGN, WRITEQ TD, SYNCPOINT)

| Source File | ASKTIME | FORMATTIME | ASSIGN | WRITEQ TD | SYNCPOINT |
|---|---|---|---|---|---|
| [COACTUPC.cbl](../../../app/cbl/COACTUPC.cbl) | — | — | — | — | 952, 4099 |
| [COACTVWC.cbl](../../../app/cbl/COACTVWC.cbl) | — | — | — | — | — |
| [COADM01C.cbl](../../../app/cbl/COADM01C.cbl) | — | — | — | — | — |
| [COBIL00C.cbl](../../../app/cbl/COBIL00C.cbl) | 251 | 255 | — | — | — |
| [COCRDLIC.cbl](../../../app/cbl/COCRDLIC.cbl) | — | — | — | — | — |
| [COCRDSLC.cbl](../../../app/cbl/COCRDSLC.cbl) | — | — | — | — | — |
| [COCRDUPC.cbl](../../../app/cbl/COCRDUPC.cbl) | — | — | — | — | 469 |
| [COMEN01C.cbl](../../../app/cbl/COMEN01C.cbl) | — | — | — | — | — |
| [CORPT00C.cbl](../../../app/cbl/CORPT00C.cbl) | — | — | — | 517 | — |
| [COSGN00C.cbl](../../../app/cbl/COSGN00C.cbl) | — | — | 198, 202 | — | — |
| [COTRN00C.cbl](../../../app/cbl/COTRN00C.cbl) | — | — | — | — | — |
| [COTRN01C.cbl](../../../app/cbl/COTRN01C.cbl) | — | — | — | — | — |
| [COTRN02C.cbl](../../../app/cbl/COTRN02C.cbl) | — | — | — | — | — |
| [COUSR00C.cbl](../../../app/cbl/COUSR00C.cbl) | — | — | — | — | — |
| [COUSR01C.cbl](../../../app/cbl/COUSR01C.cbl) | — | — | — | — | — |
| [COUSR02C.cbl](../../../app/cbl/COUSR02C.cbl) | — | — | — | — | — |
| [COUSR03C.cbl](../../../app/cbl/COUSR03C.cbl) | — | — | — | — | — |

**Notable System Service Usage:**
- **COBIL00C.cbl** is the only program using both `ASKTIME` (line 251) and `FORMATTIME` (line 255) — it retrieves and formats the current timestamp for bill payment processing.
- **COSGN00C.cbl** uses `ASSIGN` twice (lines 198, 202) to retrieve CICS system information during sign-on processing.
- **CORPT00C.cbl** contains the only `WRITEQ TD` command (line 517) in the entire codebase, writing JCL records to the `'JOBS'` Transient Data Queue for batch job submission.
  - Source: `app/cbl/CORPT00C.cbl:517-525`
- **COACTUPC.cbl** uses `SYNCPOINT` (line 952) and `SYNCPOINT ROLLBACK` (line 4099) for transaction commit/rollback during account updates.
- **COCRDUPC.cbl** uses `SYNCPOINT` (line 469) for transaction commit during card record updates.

---

## 6. COPY Statement Inventory

The following tables list every `COPY` statement across all 28 programs, grouped by copybook category. Line numbers reference the `COPY` directive location in each source file.

### 6.1 IBM Proprietary Copybooks

These are IBM-supplied copybooks that are part of the CICS runtime environment.

#### DFHAID — CICS Attention Identifier Definitions

Used by all 17 online programs to define PF key, ENTER, and CLEAR key byte values.

| Source File | COPY Line |
|---|---|
| COACTUPC.cbl | 616 |
| COACTVWC.cbl | 222 |
| COADM01C.cbl | 60 |
| COBIL00C.cbl | 84 |
| COCRDLIC.cbl | 268 |
| COCRDSLC.cbl | 209 |
| COCRDUPC.cbl | 328 |
| COMEN01C.cbl | 60 |
| CORPT00C.cbl | 148 |
| COSGN00C.cbl | 57 |
| COTRN00C.cbl | 80 |
| COTRN01C.cbl | 71 |
| COTRN02C.cbl | 92 |
| COUSR00C.cbl | 83 |
| COUSR01C.cbl | 55 |
| COUSR02C.cbl | 67 |
| COUSR03C.cbl | 67 |

#### DFHBMSCA — BMS Character Attribute Definitions

Used by all 17 online programs for screen field attributes (color, highlighting, protection).

| Source File | COPY Line |
|---|---|
| COACTUPC.cbl | 615 |
| COACTVWC.cbl | 221 |
| COADM01C.cbl | 61 |
| COBIL00C.cbl | 85 |
| COCRDLIC.cbl | 267 |
| COCRDSLC.cbl | 208 |
| COCRDUPC.cbl | 327 |
| COMEN01C.cbl | 61 |
| CORPT00C.cbl | 149 |
| COSGN00C.cbl | 58 |
| COTRN00C.cbl | 81 |
| COTRN01C.cbl | 72 |
| COTRN02C.cbl | 93 |
| COUSR00C.cbl | 84 |
| COUSR01C.cbl | 56 |
| COUSR02C.cbl | 68 |
| COUSR03C.cbl | 68 |

### 6.2 Application Communication and Common Copybooks

#### COCOM01Y.cpy — Common Communication Area (COMMAREA)

Used by all 17 online programs to define the shared COMMAREA structure for pseudo-conversational state management.

| Source File | COPY Line |
|---|---|
| COACTUPC.cbl | 650 |
| COACTVWC.cbl | 211 |
| COADM01C.cbl | 50 |
| COBIL00C.cbl | 63 |
| COCRDLIC.cbl | 227 |
| COCRDSLC.cbl | 198 |
| COCRDUPC.cbl | 272 |
| COMEN01C.cbl | 50 |
| CORPT00C.cbl | 138 |
| COSGN00C.cbl | 48 |
| COTRN00C.cbl | 61 |
| COTRN01C.cbl | 52 |
| COTRN02C.cbl | 71 |
| COUSR00C.cbl | 66 |
| COUSR01C.cbl | 46 |
| COUSR02C.cbl | 49 |
| COUSR03C.cbl | 49 |

#### COTTL01Y.cpy — Title/Header Layout

| Source File | COPY Line |
|---|---|
| COACTUPC.cbl | 620 |
| COACTVWC.cbl | 226 |
| COADM01C.cbl | 55 |
| COBIL00C.cbl | 76 |
| COCRDLIC.cbl | 272 |
| COCRDSLC.cbl | 213 |
| COCRDUPC.cbl | 332 |
| COMEN01C.cbl | 55 |
| CORPT00C.cbl | 142 |
| COSGN00C.cbl | 52 |
| COTRN00C.cbl | 74 |
| COTRN01C.cbl | 65 |
| COTRN02C.cbl | 84 |
| COUSR00C.cbl | 78 |
| COUSR01C.cbl | 50 |
| COUSR02C.cbl | 62 |
| COUSR03C.cbl | 62 |

#### CSDAT01Y.cpy — Date Field Definitions

| Source File | COPY Line |
|---|---|
| COACTUPC.cbl | 626 |
| COACTVWC.cbl | 232 |
| COADM01C.cbl | 56 |
| COBIL00C.cbl | 77 |
| COCRDLIC.cbl | 279 |
| COCRDSLC.cbl | 218 |
| COCRDUPC.cbl | 337 |
| COMEN01C.cbl | 56 |
| CORPT00C.cbl | 143 |
| COSGN00C.cbl | 53 |
| COTRN00C.cbl | 75 |
| COTRN01C.cbl | 66 |
| COTRN02C.cbl | 85 |
| COUSR00C.cbl | 79 |
| COUSR01C.cbl | 51 |
| COUSR02C.cbl | 63 |
| COUSR03C.cbl | 63 |

#### CSMSG01Y.cpy — Message Handling (Primary)

| Source File | COPY Line |
|---|---|
| COACTUPC.cbl | 629 |
| COACTVWC.cbl | 235 |
| COADM01C.cbl | 57 |
| COBIL00C.cbl | 78 |
| COCRDLIC.cbl | 281 |
| COCRDSLC.cbl | 221 |
| COCRDUPC.cbl | 340 |
| COMEN01C.cbl | 57 |
| CORPT00C.cbl | 144 |
| COSGN00C.cbl | 54 |
| COTRN00C.cbl | 76 |
| COTRN01C.cbl | 67 |
| COTRN02C.cbl | 86 |
| COUSR00C.cbl | 80 |
| COUSR01C.cbl | 52 |
| COUSR02C.cbl | 64 |
| COUSR03C.cbl | 64 |

#### CSMSG02Y.cpy — Message Handling (Secondary)

| Source File | COPY Line |
|---|---|
| COACTUPC.cbl | 632 |
| COACTVWC.cbl | 238 |
| COCRDSLC.cbl | 224 |
| COCRDUPC.cbl | 343 |

### 6.3 BMS Map Copybooks (Generated from BMS Macros)

Each online program includes a copybook generated from its corresponding BMS mapset definition.

| Copybook | Source File | COPY Line | Corresponding BMS Map |
|---|---|---|---|
| COACTUP | COACTUPC.cbl | 623 | app/bms/COACTUP.bms |
| COACTVW | COACTVWC.cbl | 229 | *(view-only map)* |
| COADM01 | COADM01C.cbl | 53 | app/bms/COADM01.bms |
| COBIL00 | COBIL00C.cbl | 74 | app/bms/COBIL00.bms |
| COCRDLI | COCRDLIC.cbl | 276 | app/bms/COCRDLI.bms |
| COCRDSL | COCRDSLC.cbl | 215 | app/bms/COCRDSL.bms |
| COCRDUP | COCRDUPC.cbl | 334 | app/bms/COCRDUP.bms |
| COMEN01 | COMEN01C.cbl | 53 | app/bms/COMEN01.bms |
| CORPT00 | CORPT00C.cbl | 140 | app/bms/CORPT00.bms |
| COSGN00 | COSGN00C.cbl | 50 | app/bms/COSIGN0.bms |
| COTRN00 | COTRN00C.cbl | 72 | app/bms/COTRN00.bms |
| COTRN01 | COTRN01C.cbl | 63 | app/bms/COTRN01.bms |
| COTRN02 | COTRN02C.cbl | 82 | app/bms/COTRN02.bms |
| COUSR00 | COUSR00C.cbl | 76 | app/bms/COUSR00.bms |
| COUSR01 | COUSR01C.cbl | 48 | app/bms/COUSR01.bms |
| COUSR02 | COUSR02C.cbl | 60 | app/bms/COUSR02.bms |
| COUSR03 | COUSR03C.cbl | 60 | app/bms/COUSR03.bms |

### 6.4 Data Record Layout Copybooks

#### CVACT01Y.cpy — Account Record Layout

| Source File | COPY Line | Context |
|---|---|---|
| CBACT01C.cbl | 45 | Batch: account file read |
| CBACT04C.cbl | 112 | Batch: interest calculation |
| CBSTM03A.CBL | 57 | Batch: statement generation |
| CBTRN01C.cbl | 119 | Batch: transaction posting |
| CBTRN02C.cbl | 121 | Batch: transaction validation |
| COACTUPC.cbl | 640 | Online: account update |
| COACTVWC.cbl | 244 | Online: account view |
| COBIL00C.cbl | 80 | Online: bill payment |
| COTRN02C.cbl | 89 | Online: add transaction |

#### CVACT02Y.cpy — Card Record Layout

| Source File | COPY Line | Context |
|---|---|---|
| CBACT02C.cbl | 45 | Batch: card file read |
| CBTRN01C.cbl | 114 | Batch: transaction posting |
| COACTVWC.cbl | 248 | Online: account view |
| COCRDLIC.cbl | 290 | Online: card list |
| COCRDSLC.cbl | 234 | Online: card detail |
| COCRDUPC.cbl | 353 | Online: card update |

#### CVACT03Y.cpy — Card Cross-Reference Layout

| Source File | COPY Line | Context |
|---|---|---|
| CBACT03C.cbl | 45 | Batch: cross-reference read |
| CBACT04C.cbl | 102 | Batch: interest calculation |
| CBSTM03A.CBL | 53 | Batch: statement generation |
| CBTRN02C.cbl | 112 | Batch: transaction validation |
| CBTRN03C.cbl | 98 | Batch: transaction reporting |
| COACTUPC.cbl | 643 | Online: account update |
| COACTVWC.cbl | 251 | Online: account view |
| COBIL00C.cbl | 81 | Online: bill payment |
| COTRN02C.cbl | 90 | Online: add transaction |

#### CVCUS01Y.cpy — Customer Record Layout

| Source File | COPY Line | Context |
|---|---|---|
| CBCUS01C.cbl | 45 | Batch: customer file read |
| CBSTM03A.CBL | 55 (as CUSTREC) | Batch: statement generation |
| CBTRN01C.cbl | 104 | Batch: transaction posting |
| COACTUPC.cbl | 646 | Online: account update |
| COACTVWC.cbl | 254 | Online: account view |
| COCRDSLC.cbl | 240 | Online: card detail |
| COCRDUPC.cbl | 359 | Online: card update |

#### CVTRA05Y.cpy — Transaction Record Layout

| Source File | COPY Line | Context |
|---|---|---|
| CBACT04C.cbl | 117 | Batch: interest calculation |
| CBTRN01C.cbl | 124 | Batch: transaction posting |
| CBTRN02C.cbl | 107 | Batch: transaction validation |
| CBTRN03C.cbl | 93 | Batch: transaction reporting |
| COBIL00C.cbl | 82 | Online: bill payment |
| CORPT00C.cbl | 146 | Online: report generation |
| COTRN00C.cbl | 78 | Online: transaction list |
| COTRN01C.cbl | 69 | Online: transaction view |
| COTRN02C.cbl | 88 | Online: add transaction |

#### CSUSR01Y.cpy — User Security Record Layout

| Source File | COPY Line | Context |
|---|---|---|
| COACTUPC.cbl | 635 | Online: account update (user context) |
| COACTVWC.cbl | 241 | Online: account view (user context) |
| COADM01C.cbl | 58 | Online: admin menu |
| COCRDLIC.cbl | 285 | Online: card list |
| COCRDSLC.cbl | 227 | Online: card detail |
| COCRDUPC.cbl | 346 | Online: card update |
| COMEN01C.cbl | 58 | Online: main menu |
| COSGN00C.cbl | 55 | Online: sign-on |
| COUSR00C.cbl | 81 | Online: user list |
| COUSR01C.cbl | 53 | Online: user add |
| COUSR02C.cbl | 65 | Online: user update |
| COUSR03C.cbl | 65 | Online: user delete |

#### Additional Transaction Copybooks

| Copybook | Source File(s) | COPY Line(s) |
|---|---|---|
| CVTRA01Y.cpy | CBACT04C.cbl (97), CBTRN02C.cbl (126) | Transaction category record |
| CVTRA02Y.cpy | CBACT04C.cbl (107) | Transaction type record |
| CVTRA03Y.cpy | CBTRN03C.cbl (103) | Transaction category balance |
| CVTRA04Y.cpy | CBTRN03C.cbl (108) | Transaction type detail |
| CVTRA06Y.cpy | CBTRN01C.cbl (99), CBTRN02C.cbl (102) | Daily transaction record |
| CVTRA07Y.cpy | CBTRN03C.cbl (113) | Transaction report record |

### 6.5 Special-Purpose Copybooks

| Copybook | Source File(s) | COPY Line(s) | Purpose |
|---|---|---|---|
| CVCRD01Y.cpy | COACTUPC.cbl (597), COACTVWC.cbl (207), COCRDLIC.cbl (221), COCRDSLC.cbl (194), COCRDUPC.cbl (268) | Card display record layout |
| CSLKPCDY.cpy | COACTUPC.cbl (602) | Lookup code definitions |
| COADM02Y.cpy | COADM01C.cbl (51) | Admin menu option definitions |
| COMEN02Y.cpy | COMEN01C.cbl (51) | Main menu option definitions |
| COSTM01.cpy | CBSTM03A.CBL (51) | Statement generation parameters |
| CSUTLDWY.cpy | COACTUPC.cbl (166) | Date utility working storage |
| CSUTLDPY.cpy | COACTUPC.cbl (4232) | Date utility procedure division |
| CSSETATY.cpy | COACTUPC.cbl (3208+) | Screen attribute setting (used 39 times with REPLACING) |
| CSSTRPFY.cpy | COACTVWC.cbl (913), COCRDLIC.cbl (1416), COCRDSLC.cbl (855), COCRDUPC.cbl (1528) | PF key string parsing |

---

## 7. Proprietary Dependency Summary Statistics

### Total Invocation Counts

| Dependency Type | Total Invocations | Distinct Programs |
|---|---|---|
| `CALL 'CEE3ABD'` | 9 | 9 batch programs (all except CBSTM03B) |
| `CALL "CEEDAYS"` | 1 | 1 utility program (CSUTLDTC.cbl:116) |
| `CALL 'CBSTM03B'` | 13 | 1 batch program (CBSTM03A.CBL) |
| `CALL 'CSUTLDTC'` | 4 | 2 online programs (CORPT00C: lines 392, 412; COTRN02C: lines 393, 413) |
| `EXEC CICS` commands | 170 | 17 online programs |
| `COPY` directives | 247 | 26 programs (all except CSUTLDTC and CBSTM03B) |
| IBM copybooks (DFHAID + DFHBMSCA) | 34 | 17 online programs (2 per program) |

### EXEC CICS Command Distribution

| Command Type | Total Occurrences | Programs Using |
|---|---|---|
| SEND (MAP/TEXT/FROM) | 31 | 17 (all online) |
| RETURN | 26 | 17 (all online) |
| RECEIVE (MAP) | 17 | 17 (all online) |
| XCTL | 25 | 17 (all online) |
| READ | 20 | 10 |
| HANDLE ABEND | 8 | 4 (COACTUPC, COACTVWC, COCRDSLC, COCRDUPC) |
| STARTBR | 6 | 5 (COBIL00C, COCRDLIC, COTRN00C, COTRN02C, COUSR00C) |
| READNEXT | 4 | 3 (COCRDLIC, COTRN00C, COUSR00C) |
| READPREV | 6 | 5 (COBIL00C, COCRDLIC, COTRN00C, COTRN02C, COUSR00C) |
| ENDBR | 6 | 5 (COBIL00C, COCRDLIC, COTRN00C, COTRN02C, COUSR00C) |
| REWRITE | 5 | 4 (COACTUPC, COBIL00C, COCRDUPC, COUSR02C) |
| WRITE | 3 | 3 (COBIL00C, COTRN02C, COUSR01C) |
| ABEND | 4 | 4 (COACTUPC, COACTVWC, COCRDSLC, COCRDUPC) |
| DELETE | 1 | 1 (COUSR03C) |
| ASKTIME | 1 | 1 (COBIL00C) |
| FORMATTIME | 1 | 1 (COBIL00C) |
| ASSIGN | 2 | 1 (COSGN00C) |
| WRITEQ TD | 1 | 1 (CORPT00C) |
| SYNCPOINT | 3 | 2 (COACTUPC, COCRDUPC) |

### VSAM Dataset Access Summary

| CICS Dataset Name | Programs Accessing | Access Modes |
|---|---|---|
| TRANSACT | COBIL00C, COTRN00C, COTRN01C, COTRN02C | READ, WRITE, STARTBR, READNEXT, READPREV, ENDBR |
| USRSEC | COSGN00C, COUSR00C, COUSR01C, COUSR02C, COUSR03C | READ, WRITE, REWRITE, DELETE, STARTBR, READNEXT, READPREV, ENDBR |
| ACCTDAT | COACTUPC, COACTVWC, COBIL00C, COTRN02C | READ, REWRITE |
| CARDDAT | COCRDLIC, COCRDSLC, COCRDUPC | READ, REWRITE, STARTBR, READNEXT, READPREV, ENDBR |
| CUSTDAT | COACTUPC, COACTVWC | READ, REWRITE |
| CXACAIX | COACTUPC, COACTVWC, COBIL00C, COTRN02C | READ (via alternate index path) |
| CARDAIX | COCRDLIC, COCRDSLC | READ (via alternate index path) |
| CCXREF | COTRN02C | READ |

---

## 8. Dependency Risk Heat Map

The following table classifies each program by its total number of distinct proprietary dependency types, providing a per-program migration complexity indicator. Higher dependency counts indicate more migration effort required.

### Online Programs — Risk Classification

| Risk Level | Source File | EXEC CICS Commands | VSAM Datasets | COPY (IBM) | Total Dependency Types |
|---|---|---|---|---|---|
| 🔴 **HIGH** | [COBIL00C.cbl](../../../app/cbl/COBIL00C.cbl) | 12 (READ, WRITE, REWRITE, STARTBR, READPREV, ENDBR, ASKTIME, FORMATTIME, SEND, RECEIVE, RETURN, XCTL) | 3 (ACCTDAT, CXACAIX, TRANSACT) | 2 | 17 |
| 🔴 **HIGH** | [COTRN02C.cbl](../../../app/cbl/COTRN02C.cbl) | 9 (READ, WRITE, STARTBR, READPREV, ENDBR, SEND, RECEIVE, RETURN, XCTL) + CALL CSUTLDTC | 3 (CXACAIX, CCXREF, TRANSACT) | 2 | 15 |
| 🔴 **HIGH** | [COACTUPC.cbl](../../../app/cbl/COACTUPC.cbl) | 9 (READ, REWRITE, SYNCPOINT, SEND, RECEIVE, RETURN, XCTL, HANDLE ABEND, ABEND) | 3 (CXACAIX, ACCTDAT, CUSTDAT) | 2 | 14 |
| 🔴 **HIGH** | [COCRDLIC.cbl](../../../app/cbl/COCRDLIC.cbl) | 8 (STARTBR, READNEXT, READPREV, ENDBR, SEND, RECEIVE, RETURN, XCTL) | 2 (CARDDAT, CARDAIX) | 2 | 12 |
| 🔴 **HIGH** | [COACTVWC.cbl](../../../app/cbl/COACTVWC.cbl) | 7 (READ, SEND, RECEIVE, RETURN, XCTL, HANDLE ABEND, ABEND) | 3 (CXACAIX, ACCTDAT, CUSTDAT) | 2 | 12 |
| 🔴 **HIGH** | [COCRDUPC.cbl](../../../app/cbl/COCRDUPC.cbl) | 9 (READ, REWRITE, SYNCPOINT, SEND, RECEIVE, RETURN, XCTL, HANDLE ABEND, ABEND) | 1 (CARDDAT) | 2 | 12 |
| 🟡 **MEDIUM** | [COTRN00C.cbl](../../../app/cbl/COTRN00C.cbl) | 8 (STARTBR, READNEXT, READPREV, ENDBR, SEND, RECEIVE, RETURN, XCTL) | 1 (TRANSACT) | 2 | 11 |
| 🟡 **MEDIUM** | [COUSR00C.cbl](../../../app/cbl/COUSR00C.cbl) | 8 (STARTBR, READNEXT, READPREV, ENDBR, SEND, RECEIVE, RETURN, XCTL) | 1 (USRSEC) | 2 | 11 |
| 🟡 **MEDIUM** | [COCRDSLC.cbl](../../../app/cbl/COCRDSLC.cbl) | 7 (READ, SEND, RECEIVE, RETURN, XCTL, HANDLE ABEND, ABEND) | 2 (CARDDAT, CARDAIX) | 2 | 11 |
| 🟡 **MEDIUM** | [COSGN00C.cbl](../../../app/cbl/COSGN00C.cbl) | 6 (READ, ASSIGN, SEND, RECEIVE, RETURN, XCTL) | 1 (USRSEC) | 2 | 9 |
| 🟡 **MEDIUM** | [COUSR02C.cbl](../../../app/cbl/COUSR02C.cbl) | 6 (READ, REWRITE, SEND, RECEIVE, RETURN, XCTL) | 1 (USRSEC) | 2 | 9 |
| 🟡 **MEDIUM** | [COUSR03C.cbl](../../../app/cbl/COUSR03C.cbl) | 6 (READ, DELETE, SEND, RECEIVE, RETURN, XCTL) | 1 (USRSEC) | 2 | 9 |
| 🟡 **MEDIUM** | [CORPT00C.cbl](../../../app/cbl/CORPT00C.cbl) | 5 (WRITEQ TD, SEND, RECEIVE, RETURN, XCTL) + CALL CSUTLDTC | 0 | 2 | 8 |
| 🟡 **MEDIUM** | [COTRN01C.cbl](../../../app/cbl/COTRN01C.cbl) | 5 (READ, SEND, RECEIVE, RETURN, XCTL) | 1 (TRANSACT) | 2 | 8 |
| 🟡 **MEDIUM** | [COUSR01C.cbl](../../../app/cbl/COUSR01C.cbl) | 5 (WRITE, SEND, RECEIVE, RETURN, XCTL) | 1 (USRSEC) | 2 | 8 |
| 🟢 **LOW** | [COADM01C.cbl](../../../app/cbl/COADM01C.cbl) | 4 (SEND, RECEIVE, RETURN, XCTL) | 0 | 2 | 6 |
| 🟢 **LOW** | [COMEN01C.cbl](../../../app/cbl/COMEN01C.cbl) | 4 (SEND, RECEIVE, RETURN, XCTL) | 0 | 2 | 6 |

### Batch Programs — Risk Classification

| Risk Level | Source File | CEE3ABD | VSAM Files | COPY Count | Other CALLs | Complexity Factor |
|---|---|---|---|---|---|---|
| 🔴 **HIGH** | [CBACT04C.cbl](../../../app/cbl/CBACT04C.cbl) | ✓ (line 632) | 5 | 5 | — | Multi-file I/O with interest calculation logic |
| 🔴 **HIGH** | [CBTRN02C.cbl](../../../app/cbl/CBTRN02C.cbl) | ✓ (line 711) | 6 | 5 | — | Multi-file validation with reject handling |
| 🟡 **MEDIUM** | [CBTRN01C.cbl](../../../app/cbl/CBTRN01C.cbl) | ✓ (line 473) | 6 | 6 | — | Transaction posting across 6 files |
| 🟡 **MEDIUM** | [CBTRN03C.cbl](../../../app/cbl/CBTRN03C.cbl) | ✓ (line 630) | 6 | 5 | — | Report generation with multiple inputs |
| 🟡 **MEDIUM** | [CBSTM03A.CBL](../../../app/cbl/CBSTM03A.CBL) | ✓ (line 923) | 2 + delegated | 4 | CALL 'CBSTM03B' ×13 | Statement generation with delegated I/O |
| 🟢 **LOW** | [CBACT01C.cbl](../../../app/cbl/CBACT01C.cbl) | ✓ (line 173) | 1 | 1 | — | Single-file read and display |
| 🟢 **LOW** | [CBACT02C.cbl](../../../app/cbl/CBACT02C.cbl) | ✓ (line 158) | 1 | 1 | — | Single-file read and display |
| 🟢 **LOW** | [CBACT03C.cbl](../../../app/cbl/CBACT03C.cbl) | ✓ (line 158) | 1 | 1 | — | Single-file read and display |
| 🟢 **LOW** | [CBCUS01C.cbl](../../../app/cbl/CBCUS01C.cbl) | ✓ (line 158) | 1 | 1 | — | Single-file read and display |
| 🟢 **LOW** | [CBSTM03B.CBL](../../../app/cbl/CBSTM03B.CBL) | — | 4 | 0 | — | I/O subroutine (no LE calls) |

---

## 9. Programs with Unique or Notable Dependencies

The following programs warrant special migration attention due to unique proprietary dependency patterns not found elsewhere in the codebase:

| Program | Unique Dependency | Line(s) | Migration Impact |
|---|---|---|---|
| **CORPT00C.cbl** | Only program using `EXEC CICS WRITEQ TD` | 517 | Requires message queue migration (CICS TDQ → AWS SQS/Step Functions). See [Section 04](../04-migration-strategy-per-utility.md). |
| **COBIL00C.cbl** | Only program using `EXEC CICS ASKTIME` + `FORMATTIME` | 251, 255 | Requires `java.time` timestamp formatting. See [Section 04](../04-migration-strategy-per-utility.md). |
| **COSGN00C.cbl** | Only program using `EXEC CICS ASSIGN` | 198, 202 | Requires CICS system info retrieval equivalent. See [Appendix A](./A-cics-command-reference.md). |
| **COACTUPC.cbl** | Only program using `EXEC CICS SYNCPOINT ROLLBACK` | 4099 | Requires Spring `@Transactional(rollbackFor=...)` pattern. See [Section 04](../04-migration-strategy-per-utility.md). |
| **COUSR03C.cbl** | Only program using `EXEC CICS DELETE` | 307 | Requires JPA `deleteById()` or JDBC DELETE mapping. See [Appendix A](./A-cics-command-reference.md). |
| **CBSTM03B.CBL** | Only batch program without `CEE3ABD` | N/A | Simpler migration path — no abend handling required. |
| **CSUTLDTC.cbl** | Only program calling `CEEDAYS` directly | 116 | Central date validation utility — must be migrated first as dependency of CORPT00C and COTRN02C. |
| **CBSTM03A.CBL** | Highest `CALL 'CBSTM03B'` count (13 calls) | 351–909 | Tight coupling requires coordinated migration with CBSTM03B. |
| **COACTUPC.cbl** | Only program using CSUTLDWY/CSUTLDPY copybooks | 166, 4232 | Inline date validation via copybook inclusion (not CALL). |

---

## 10. Navigation

| Link | Description |
|---|---|
| [Back to Executive Summary](../00-executive-summary.md) | Return to the migration analysis overview |
| [Section 01 — Proprietary Utility Inventory](../01-proprietary-utility-inventory.md) | Complete catalog of all proprietary utilities |
| [Section 02 — External Documentation Research](../02-external-documentation-research.md) | IBM documentation research findings |
| [Section 03 — Dependency Impact Analysis](../03-dependency-impact-analysis.md) | Cross-referenced behavioral analysis |
| [Section 04 — Migration Strategy Per Utility](../04-migration-strategy-per-utility.md) | Java migration recommendations |
| [Section 05 — Risk Assessment](../05-risk-assessment.md) | Risk matrix with classifications |
| [Section 06 — Testing and Validation Framework](../06-testing-validation-framework.md) | Byte-level comparison strategy |
| [Appendix A — CICS Command Reference](./A-cics-command-reference.md) | Per-command behavioral specifications |
| [Appendix B — VSAM Dataset Catalog](./B-vsam-dataset-catalog.md) | VSAM topology and RDS mapping |
| [Appendix C — Batch Job Dependency Map](./C-batch-job-dependency-map.md) | Batch job chain documentation |
| [Appendix D — BMS Screen Inventory](./D-bms-screen-inventory.md) | BMS mapset catalog |

---

*Generated from static analysis of all 28 COBOL source files in `app/cbl/`. Line numbers verified against repository commit. All citations reference files relative to the repository root.*
