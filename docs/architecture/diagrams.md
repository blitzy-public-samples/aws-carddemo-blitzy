# CardDemo Migration — Architecture Diagrams

> **Before** (COBOL / CICS / VSAM / JCL) → **After** (Java 25 / Spring Boot 3.5.x / PostgreSQL / Spring Batch)

This document provides visual architecture documentation of the AWS CardDemo mainframe
application migration from COBOL/CICS/VSAM to Java 25 + Spring Boot 3.x. All diagrams
use [Mermaid](https://mermaid.js.org/) syntax for native rendering on GitHub and compatible
Markdown viewers. Each diagram includes a descriptive title and is covered by the
[Legend and Notation Guide](#10-legend-and-notation-guide) at the end of this document.

---

## Table of Contents

1. [Before State: Mainframe Architecture Overview](#1-before-state-mainframe-architecture-overview)
2. [Before State: CICS Online Transaction Flow](#2-before-state-cics-online-transaction-flow)
3. [Before State: JCL Batch Processing Sequence](#3-before-state-jcl-batch-processing-sequence)
4. [After State: Java / Spring Boot Architecture Overview](#4-after-state-java--spring-boot-architecture-overview)
5. [After State: Spring Batch Job Flow](#5-after-state-spring-batch-job-flow)
6. [Transformation Mapping](#6-transformation-mapping)
7. [Data Model — Entity Relationship Diagram](#7-data-model--entity-relationship-diagram)
8. [After State: REST API Request Flow](#8-after-state-rest-api-request-flow)
9. [Component Inventory Comparison](#9-component-inventory-comparison)
10. [Legend and Notation Guide](#10-legend-and-notation-guide)

---

## 1. Before State: Mainframe Architecture Overview

The original CardDemo application runs on z/OS with a classic three-tier architecture:
**3270 terminals** (presentation) → **COBOL/CICS programs** (business logic) →
**VSAM KSDS files** (data). This diagram shows every tier, all 28 COBOL programs,
17 BMS maps, 10 VSAM datasets, 3 alternate indexes, and the JCL batch pipeline.

```mermaid
graph TB
    %% ── Presentation Tier ──────────────────────────────────────────
    subgraph PRES["Presentation Tier — 3270 / BMS"]
        direction TB
        TERM["3270 Terminals"]

        subgraph BMS_MAPS["17 BMS Map Sources"]
            COSGN00_M["COSGN00 — Sign-on"]
            COMEN01_M["COMEN01 — Main Menu"]
            COADM01_M["COADM01 — Admin Menu"]
            COACTVW_M["COACTVW — Acct View"]
            COACTUP_M["COACTUP — Acct Update"]
            COCRDLI_M["COCRDLI — Card List"]
            COCRDSL_M["COCRDSL — Card Detail"]
            COCRDUP_M["COCRDUP — Card Update"]
            COTRN00_M["COTRN00 — Tran List"]
            COTRN01_M["COTRN01 — Tran View"]
            COTRN02_M["COTRN02 — Tran Add"]
            CORPT00_M["CORPT00 — Reports"]
            COBIL00_M["COBIL00 — Bill Pay"]
            COUSR00_M["COUSR00 — User List"]
            COUSR01_M["COUSR01 — User Add"]
            COUSR02_M["COUSR02 — User Update"]
            COUSR03_M["COUSR03 — User Delete"]
        end

        BMS_CPY["17 BMS Copybooks — AI/AO two-view pattern"]
        TERM --> BMS_MAPS
        BMS_MAPS --> BMS_CPY
    end

    %% ── Business Logic Tier ────────────────────────────────────────
    subgraph LOGIC["Business Logic Tier — COBOL / CICS"]
        direction TB
        CICS["CICS Transaction Processor — Pseudo-conversational"]
        COMMAREA["COMMAREA — COCOM01Y — 1024-byte session context"]

        subgraph ONLINE["18 Online CICS Programs"]
            COSGN00C["COSGN00C — Sign-on — CC00"]
            COMEN01C["COMEN01C — Main Menu — CM00"]
            COADM01C["COADM01C — Admin Menu — CA00"]
            COACTVWC["COACTVWC — Acct View — CAVW"]
            COACTUPC["COACTUPC — Acct Update — CAUP"]
            COCRDLIC["COCRDLIC — Card List — CCLI"]
            COCRDSLC["COCRDSLC — Card Detail — CCDL"]
            COCRDUPC["COCRDUPC — Card Update — CCUP"]
            COTRN00C["COTRN00C — Tran List — CT00"]
            COTRN01C["COTRN01C — Tran View — CT01"]
            COTRN02C["COTRN02C — Tran Add — CT02"]
            CORPT00C["CORPT00C — Reports — CR00"]
            COBIL00C["COBIL00C — Bill Pay — CB00"]
            COUSR00C["COUSR00C — User List — CU00"]
            COUSR01C["COUSR01C — User Add — CU01"]
            COUSR02C["COUSR02C — User Update — CU02"]
            COUSR03C["COUSR03C — User Delete — CU03"]
            CSUTLDTC["CSUTLDTC — Shared Date Utility"]
        end

        subgraph BATCH_PGM["10 Batch COBOL Programs"]
            CBACT01C["CBACT01C — Acct Refresh"]
            CBACT02C["CBACT02C — Acct Processing"]
            CBACT03C["CBACT03C — Acct Operations"]
            CBACT04C["CBACT04C — Interest Calc"]
            CBCUS01C["CBCUS01C — Customer File"]
            CBTRN01C["CBTRN01C — Tran Utilities"]
            CBTRN02C["CBTRN02C — Daily Posting"]
            CBTRN03C["CBTRN03C — Tran Processing"]
            CBSTM03A["CBSTM03A — Statement Engine"]
            CBSTM03B["CBSTM03B — Statement I/O"]
        end

        CPY["28 Copybooks — record layouts and shared data structures"]

        CICS --> ONLINE
        ONLINE --> COMMAREA
        ONLINE --> CPY
        BATCH_PGM --> CPY
    end

    %% ── Data Tier ──────────────────────────────────────────────────
    subgraph DATA["Data Tier — VSAM"]
        direction TB
        subgraph VSAM_DS["10 VSAM KSDS Datasets"]
            ACCTDATA["ACCTDATA — 300B — PK ACCT-ID 11B"]
            CARDDATA["CARDDATA — 150B — PK CARD-NUM 16B"]
            CARDXREF["CARDXREF — 50B — PK XREF-CARD-NUM 16B"]
            CUSTDATA["CUSTDATA — 500B — PK CUST-ID 9B"]
            TRANSACT["TRANSACT — 350B — PK TRAN-ID 16B"]
            DALYTRAN["DALYTRAN — 350B — Sequential feed"]
            USRSEC["USRSEC — 80B — PK SEC-USR-ID 8B"]
            TRANTYPE["TRANTYPE — 50B — PK TRAN-TYPE 2B"]
            TRANCATG["TRANCATG — 50B — PK TRAN-CAT 4B"]
            TCATBALF["TCATBALF — 50B — Composite PK"]
        end

        subgraph AIX["3 Alternate Indexes"]
            AIX1["CARDDATA AIX — CARD-ACCT-ID — pos 16, len 11"]
            AIX2["CARDXREF AIX — XREF-ACCT-ID — pos 25, len 11"]
            AIX3["TRANSACT AIX — TRAN-ORIG-TS — pos 304, len 26"]
        end
    end

    %% ── Batch Orchestration ────────────────────────────────────────
    subgraph JCL_ORCH["Batch Orchestration — JCL"]
        direction LR
        CLOSEFIL["CLOSEFIL"] --> POSTTRAN["POSTTRAN"]
        POSTTRAN --> INTCALC["INTCALC"]
        INTCALC --> COMBTRAN["COMBTRAN"]
        COMBTRAN --> CREASTMT["CREASTMT"]
        CREASTMT --> OPENFIL["OPENFIL"]
    end

    %% ── Cross-tier Connections ─────────────────────────────────────
    PRES --> LOGIC
    LOGIC --> DATA
    BATCH_PGM --> VSAM_DS
    JCL_ORCH -.-> BATCH_PGM

    %% ── Styling ────────────────────────────────────────────────────
    style PRES fill:#ffcdd2,stroke:#b71c1c,color:#000
    style LOGIC fill:#ffe0b2,stroke:#e65100,color:#000
    style DATA fill:#bbdefb,stroke:#0d47a1,color:#000
    style JCL_ORCH fill:#e1bee7,stroke:#4a148c,color:#000
    style BMS_MAPS fill:#ffcdd2,stroke:#c62828,color:#000
    style ONLINE fill:#ffe0b2,stroke:#ef6c00,color:#000
    style BATCH_PGM fill:#fff3e0,stroke:#ef6c00,color:#000
    style VSAM_DS fill:#bbdefb,stroke:#1565c0,color:#000
    style AIX fill:#e3f2fd,stroke:#1565c0,color:#000
```

> **Legend — Diagram 1:** Pink = Presentation tier. Orange = Business logic tier.
> Blue = Data tier. Purple = Batch orchestration. Solid arrows = data flow.
> Dashed arrows = control/scheduling flow. Each node label shows program name,
> description, and CICS transaction ID (for online programs).

---

## 2. Before State: CICS Online Transaction Flow

This sequence diagram shows how a user interacts with CardDemo through a 3270 terminal.
The pseudo-conversational CICS model sends a BMS map, returns control to CICS,
then dispatches back to the program when the user presses an AID key. The COMMAREA
(COCOM01Y, 1024 bytes) carries session state between program invocations.

```mermaid
sequenceDiagram
    autonumber
    participant T as 3270 Terminal
    participant CICS as CICS TP Monitor
    participant SGN as COSGN00C — Sign-on
    participant MEN as COMEN01C — Main Menu
    participant ADM as COADM01C — Admin Menu
    participant SVC as CICS Program — e.g. COACTVWC
    participant VSAM as VSAM KSDS Files

    Note over T, VSAM: Sign-on Flow — Transaction CC00

    T->>CICS: AID key press — TRANSID CC00
    CICS->>SGN: Dispatch COSGN00C
    SGN->>SGN: EXEC CICS RECEIVE MAP COSGN00
    SGN->>VSAM: EXEC CICS READ FILE USRSEC — key SEC-USR-ID
    VSAM-->>SGN: User record — plaintext SEC-USR-PWD
    SGN->>SGN: Verify password — compare SEC-USR-PWD
    SGN->>SGN: Check CDEMO-USER-TYPE — 88 ADMIN or USER

    alt User Type = ADMIN — value A
        SGN->>CICS: EXEC CICS XCTL PROGRAM COADM01C COMMAREA
        CICS->>ADM: Dispatch Admin Menu
        ADM->>T: EXEC CICS SEND MAP COADM01
    else User Type = USER — value U
        SGN->>CICS: EXEC CICS XCTL PROGRAM COMEN01C COMMAREA
        CICS->>MEN: Dispatch Main Menu
        MEN->>T: EXEC CICS SEND MAP COMEN01
    end

    Note over T, VSAM: Subsequent Transaction — e.g. Account View CAVW

    T->>CICS: AID key press — TRANSID CAVW
    CICS->>SVC: Dispatch COACTVWC with COMMAREA
    SVC->>SVC: EXEC CICS RECEIVE MAP COACTVW
    SVC->>VSAM: EXEC CICS READ FILE ACCTDATA — key ACCT-ID
    VSAM-->>SVC: Account record — 300 bytes
    SVC->>VSAM: EXEC CICS READ FILE CARDXREF — via AIX XREF-ACCT-ID
    VSAM-->>SVC: Card cross-reference records
    SVC->>T: EXEC CICS SEND MAP COACTVW
    SVC->>CICS: EXEC CICS RETURN TRANSID CAVW COMMAREA
```

> **Legend — Diagram 2:** Solid arrows = request/dispatch. Dashed arrows = response/data
> return. The COMMAREA (COCOM01Y) travels with every XCTL and RETURN, carrying
> user ID, user type, current program, and navigation context.

---

## 3. Before State: JCL Batch Processing Sequence

The nightly batch window follows a strict six-step JCL sequence. CLOSEFIL shuts
CICS file access, four processing jobs run in order, and OPENFIL re-opens files.
Separate seed-data jobs (ACCTFILE, CUSTFILE, TRANFILE) load initial data on demand.

```mermaid
graph LR
    subgraph NIGHTLY["Nightly Batch Window — Sequential JCL Execution"]
        direction LR
        CLOSE["CLOSEFIL<br/>IDCAMS — Close CICS files"]
        POST["POSTTRAN<br/>CBTRN02C — Post daily<br/>transactions from DALYTRAN"]
        INT["INTCALC<br/>CBACT04C — Calculate<br/>interest on ACCTDATA"]
        COMB["COMBTRAN<br/>SORT — Combine and sort<br/>transactions by COBOL keys"]
        STMT["CREASTMT<br/>CBSTM03A + CBSTM03B<br/>Statement generation"]
        OPEN["OPENFIL<br/>IDCAMS — Re-open CICS files"]

        CLOSE --> POST
        POST --> INT
        INT --> COMB
        COMB --> STMT
        STMT --> OPEN
    end

    subgraph POST_DETAIL["POSTTRAN Detail — Reject Codes"]
        direction TB
        DALY_IN["Input: DALYTRAN<br/>Daily transaction feed"]
        VALIDATE["Validate each transaction"]
        ACCEPT["Accept: Write to TRANSACT<br/>Update ACCTDATA balance"]
        REJECT["Reject: Write to DALYREJS"]

        DALY_IN --> VALIDATE
        VALIDATE -->|"Valid"| ACCEPT
        VALIDATE -->|"Code 100: XREF not found<br/>Code 101: Account not found<br/>Code 102: Credit limit exceeded<br/>Code 103: Account expired"| REJECT
    end

    subgraph STMT_DETAIL["CREASTMT Detail"]
        direction TB
        STM_A["CBSTM03A — Main engine<br/>Iterate accounts"]
        STM_B["CBSTM03B — I/O subroutine<br/>CALL target"]
        STM_OUT_T["Output: Text statements"]
        STM_OUT_H["Output: HTML statements"]

        STM_A --> STM_B
        STM_B --> STM_OUT_T
        STM_B --> STM_OUT_H
    end

    subgraph SEED["On-Demand Seed Data Loaders"]
        direction TB
        ACCTFILE["ACCTFILE — CBACT01C<br/>Load acctdata.txt"]
        CUSTFILE["CUSTFILE — CBCUS01C<br/>Load custdata.txt"]
        TRANFILE["TRANFILE — CBTRN01C<br/>Load transaction data"]
    end

    POST -.-> POST_DETAIL
    STMT -.-> STMT_DETAIL

    style NIGHTLY fill:#e1bee7,stroke:#4a148c,color:#000
    style POST_DETAIL fill:#f3e5f5,stroke:#6a1b9a,color:#000
    style STMT_DETAIL fill:#f3e5f5,stroke:#6a1b9a,color:#000
    style SEED fill:#ede7f6,stroke:#4527a0,color:#000
```

> **Legend — Diagram 3:** Purple = batch orchestration. Solid arrows = sequential job
> execution order. Dashed arrows = detail expansion. Reject codes 100–103 correspond
> to specific validation failures in CBTRN02C. CBSTM03A CALLs CBSTM03B as a subroutine.

---

## 4. After State: Java / Spring Boot Architecture Overview

The migrated CardDemo application uses Java 25 LTS with Spring Boot 3.5.x in a
layered modular-monolith architecture. Each COBOL program maps to a Java service class.
VSAM datasets become PostgreSQL tables via JPA entities. JCL jobs become Spring Batch jobs.

```mermaid
graph TB
    %% ── API Layer ──────────────────────────────────────────────────
    subgraph API["API Layer — 7 REST Controllers"]
        direction TB
        AuthCtrl["AuthController<br/>POST /api/auth/login<br/>POST /api/auth/logout"]
        AcctCtrl["AccountController<br/>GET/PUT /api/accounts/id"]
        CardCtrl["CardController<br/>GET /api/cards<br/>GET/PUT /api/cards/num"]
        TranCtrl["TransactionController<br/>GET /api/transactions<br/>GET/POST /api/transactions/id"]
        RptCtrl["ReportController<br/>GET/POST /api/reports"]
        BillCtrl["BillPaymentController<br/>POST /api/billing/pay"]
        UserCtrl["UserAdminController<br/>CRUD /api/admin/users — Admin only"]
    end

    %% ── Service Layer ──────────────────────────────────────────────
    subgraph SVC["Service Layer — 27 Services"]
        direction TB
        subgraph ONLINE_SVC["17 Online Services — 1 per CICS program"]
            SignonSvc["SignonService"]
            MainMenuSvc["MainMenuService"]
            AdminMenuSvc["AdminMenuService"]
            AcctViewSvc["AccountViewService"]
            AcctUpdSvc["AccountUpdateService"]
            CardListSvc["CreditCardListService"]
            CardDetSvc["CreditCardDetailService"]
            CardUpdSvc["CreditCardUpdateService"]
            TranListSvc["TransactionListService"]
            TranViewSvc["TransactionViewService"]
            TranAddSvc["TransactionAddService"]
            ReportSvc["ReportService"]
            BillPaySvc["BillPaymentService"]
            UsrListSvc["UserListService"]
            UsrAddSvc["UserAddService"]
            UsrUpdSvc["UserUpdateService"]
            UsrDelSvc["UserDeleteService"]
        end
        subgraph BATCH_SVC["10 Batch Services — 1 per batch program"]
            AcctRefSvc["AccountRefreshService"]
            AcctProcSvc["AccountProcessingService"]
            AcctOpsSvc["AccountOperationsService"]
            IntCalcSvc["InterestCalculationService"]
            CustFileSvc["CustomerFileService"]
            TranUtilSvc["TransactionUtilService"]
            DailyPostSvc["DailyPostingService"]
            TranProcSvc["TransactionProcessService"]
            StmtEngSvc["StatementEngineService"]
            StmtIoSvc["StatementIoService"]
        end
    end

    %% ── Batch Layer ────────────────────────────────────────────────
    subgraph BATCH["Batch Layer — 7 Spring Batch Jobs"]
        direction TB
        DailyJob["DailyPostingJobConfig"]
        IntJob["InterestCalcJobConfig"]
        SortJob["TransactionSortJobConfig"]
        StmtJob["StatementGenJobConfig"]
        AcctLoad["AccountLoadJobConfig"]
        CustLoad["CustomerLoadJobConfig"]
        TranLoad["TransactionLoadJobConfig"]

        subgraph INFRA["Batch Infrastructure"]
            FWReader["FixedWidthFileReader"]
            DailyReader["DailyTransactionReader"]
            PostProc["TransactionPostingProcessor"]
            IntProc["InterestCalculationProcessor"]
            StmtProc["StatementProcessor"]
            RejWriter["RejectFileWriter"]
            StmtWriter["StatementFileWriter"]
        end
    end

    %% ── Data Layer ─────────────────────────────────────────────────
    subgraph DAL["Data Layer — 11 JPA Entities + 11 Repositories"]
        direction TB
        subgraph ENTITIES["JPA Entities"]
            Account["Account"]
            Card["Card"]
            CardXref["CardXref"]
            Customer["Customer"]
            Transaction["Transaction"]
            DailyTran["DailyTransaction"]
            UserSec["UserSecurity"]
            TranTypeRef["TransactionTypeRef"]
            TranCatRef["TransactionCategoryRef"]
            DiscGroup["DiscountGroup"]
            CatBal["CategoryBalance"]
        end
        PG[("PostgreSQL 16+")]
        FLYWAY["Flyway Schema Migrations"]
        ENTITIES --> PG
        FLYWAY --> PG
    end

    %% ── Cross-Cutting ──────────────────────────────────────────────
    subgraph CROSS["Cross-Cutting Concerns"]
        direction TB
        SEC["Spring Security — ADMIN/USER roles — BCrypt"]
        CTX["CardDemoContext — Request-scoped — replaces COMMAREA"]
        DTOs["14 Common DTOs — from copybooks"]
        ENUMS["4 Enums — UserType, TransactionType, TransactionCategory, FileStatusCode"]
        EXC["6 Exception Classes — FileStatusException hierarchy"]
        UTIL["5 Shared Utilities — DateConversion, StringProcessing, Attribute, LookupCode, FieldValidator"]
    end

    %% ── Observability ──────────────────────────────────────────────
    subgraph OBS["Observability"]
        direction TB
        ACT["Spring Boot Actuator — /actuator/health, /actuator/readiness"]
        TRACE["Micrometer Tracing — distributed tracing via OpenTelemetry"]
        PROM["Prometheus Metrics — /actuator/prometheus"]
        LOG["Structured JSON Logging — Logstash Logback Encoder + correlation IDs"]
    end

    %% ── Connections ────────────────────────────────────────────────
    API --> SVC
    SVC --> DAL
    BATCH --> BATCH_SVC
    BATCH_SVC --> DAL
    API -.-> CROSS
    SVC -.-> CROSS
    API -.-> OBS

    %% ── Styling ────────────────────────────────────────────────────
    style API fill:#c8e6c9,stroke:#1b5e20,color:#000
    style SVC fill:#a5d6a7,stroke:#2e7d32,color:#000
    style ONLINE_SVC fill:#c8e6c9,stroke:#388e3c,color:#000
    style BATCH_SVC fill:#dcedc8,stroke:#558b2f,color:#000
    style BATCH fill:#e8f5e9,stroke:#1b5e20,color:#000
    style INFRA fill:#f1f8e9,stroke:#33691e,color:#000
    style DAL fill:#bbdefb,stroke:#0d47a1,color:#000
    style ENTITIES fill:#e3f2fd,stroke:#1565c0,color:#000
    style CROSS fill:#fff9c4,stroke:#f57f17,color:#000
    style OBS fill:#e0f7fa,stroke:#006064,color:#000
```

> **Legend — Diagram 4:** Green = application layers (API, Service, Batch).
> Blue = data layer (entities, PostgreSQL). Yellow = cross-cutting concerns.
> Cyan = observability. Solid arrows = runtime data flow. Dashed arrows = dependency
> references. Every COBOL online program maps 1-to-1 to an online service; every
> batch program maps 1-to-1 to a batch service.

---

## 5. After State: Spring Batch Job Flow

Each JCL batch job is translated to a Spring Batch `Job` with chunk-oriented
`ItemReader` → `ItemProcessor` → `ItemWriter` steps. All monetary calculations
use `BigDecimal` with `RoundingMode.HALF_UP`.

```mermaid
graph LR
    subgraph DAILY["DailyPostingJob — from JCL POSTTRAN"]
        direction LR
        DR["DailyTransactionReader<br/>Parse DALYTRAN feed"]
        TP["TransactionPostingProcessor<br/>Validate: XREF, account,<br/>credit limit, expiry"]
        TW["TransactionWriter<br/>Write to TRANSACT table"]
        RW["RejectFileWriter<br/>Write to DALYREJS<br/>Codes: 100, 101, 102, 103"]

        DR --> TP
        TP -->|"Valid"| TW
        TP -->|"Rejected"| RW
    end

    subgraph INTEREST["InterestCalcJob — from JCL INTCALC"]
        direction LR
        AR["AccountReader<br/>Read all accounts"]
        IP["InterestCalculationProcessor<br/>BigDecimal arithmetic<br/>RoundingMode.HALF_UP"]
        AW["AccountWriter<br/>Update ACCT-CURR-BAL"]

        AR --> IP
        IP --> AW
    end

    subgraph SORT["TransactionSortJob — from JCL COMBTRAN"]
        direction LR
        TR["TransactionReader<br/>Read TRANSACT"]
        CS["Comparator-based Sort<br/>Matches COBOL SORT keys"]
        TSW["TransactionWriter<br/>Write sorted result"]

        TR --> CS
        CS --> TSW
    end

    subgraph STATEMENT["StatementGenJob — from JCL CREASTMT"]
        direction LR
        SAR["AccountIterator<br/>Step 1: Read accounts"]
        AGG["TransactionAggregator<br/>Step 2: Aggregate by account"]
        SFW["StatementFileWriter<br/>Step 3: Output text + HTML"]

        SAR --> AGG
        AGG --> SFW
    end

    subgraph LOADERS["Seed Data Loaders — from JCL ACCTFILE, CUSTFILE, TRANFILE"]
        direction LR
        FWR["FixedWidthFileReader<br/>Parse acctdata.txt,<br/>custdata.txt, etc."]
        DBW["DatabaseWriter<br/>INSERT into PostgreSQL"]

        FWR --> DBW
    end

    style DAILY fill:#c8e6c9,stroke:#1b5e20,color:#000
    style INTEREST fill:#a5d6a7,stroke:#2e7d32,color:#000
    style SORT fill:#dcedc8,stroke:#558b2f,color:#000
    style STATEMENT fill:#e8f5e9,stroke:#1b5e20,color:#000
    style LOADERS fill:#f1f8e9,stroke:#33691e,color:#000
```

> **Legend — Diagram 5:** Green shades = Spring Batch jobs. Each subgraph represents
> one `Job` bean. Arrows show the chunk-oriented flow: Reader → Processor → Writer.
> The DailyPostingJob has a dual-output path — valid transactions go to TRANSACT,
> rejected ones go to DALYREJS with codes 100 (XREF not found), 101 (account not
> found), 102 (credit limit exceeded), 103 (account expired).

---

## 6. Transformation Mapping

This diagram maps every major COBOL/CICS/VSAM construct to its Java/Spring Boot
equivalent. The left side shows the mainframe components; the right side shows
the modern targets. Counts match the source repository exactly.

```mermaid
graph LR
    subgraph BEFORE["Before — COBOL / CICS / VSAM / JCL"]
        direction TB
        B_CICS["CICS TP Monitor"]
        B_BMS["BMS Maps — 17 mapsets"]
        B_PGM["COBOL Programs — 28 total"]
        B_VSAM["VSAM KSDS — 10 datasets"]
        B_AIX["VSAM AIX — 3 alternate indexes"]
        B_COMM["COMMAREA — 1024 bytes"]
        B_CPY["Copybooks — 28"]
        B_JCL["JCL Jobs — 6 core"]
        B_CALL["COBOL CALL statement"]
        B_FS["FILE STATUS — 2-byte codes"]
        B_88["88-level Conditions"]
        B_COMP3["COMP-3 Packed Decimal"]
        B_BMSCA["DFHBMSCA Attributes"]
    end

    subgraph AFTER["After — Java 25 / Spring Boot 3.5.x / PostgreSQL"]
        direction TB
        A_BOOT["Spring Boot 3.5.x"]
        A_DTO["REST DTOs — Request/Response POJOs"]
        A_SVC["Java Service Classes — 27 + 1 utility"]
        A_PG["PostgreSQL 16+ Tables — 11 entities"]
        A_IDX["PostgreSQL Secondary Indexes + JPA @Index"]
        A_CTX["CardDemoContext — @RequestScope bean"]
        A_SHARED["Shared DTOs + Entities — 14 DTOs + 11 entities"]
        A_BATCH["Spring Batch Jobs — 7"]
        A_INJECT["@Autowired Service Injection"]
        A_EXC["Exception Hierarchy — 6 classes"]
        A_ENUM["Java Enums — 4 types"]
        A_BD["BigDecimal — exact arithmetic"]
        A_VALID["Bean Validation Annotations"]
    end

    B_CICS -->|"replaces"| A_BOOT
    B_BMS -->|"becomes"| A_DTO
    B_PGM -->|"translates to"| A_SVC
    B_VSAM -->|"migrates to"| A_PG
    B_AIX -->|"maps to"| A_IDX
    B_COMM -->|"becomes"| A_CTX
    B_CPY -->|"becomes"| A_SHARED
    B_JCL -->|"becomes"| A_BATCH
    B_CALL -->|"becomes"| A_INJECT
    B_FS -->|"becomes"| A_EXC
    B_88 -->|"becomes"| A_ENUM
    B_COMP3 -->|"becomes"| A_BD
    B_BMSCA -->|"becomes"| A_VALID

    style BEFORE fill:#ffcdd2,stroke:#b71c1c,color:#000
    style AFTER fill:#c8e6c9,stroke:#1b5e20,color:#000
```

> **Legend — Diagram 6:** Red/pink = legacy mainframe constructs. Green = modern
> Java/Spring Boot targets. Each arrow represents a one-to-one or one-to-many
> construct transformation. No features are added — the right side is a strict
> functional equivalent of the left side.

---

## 7. Data Model — Entity Relationship Diagram

All 11 JPA entities are shown with primary keys, key fields, and relationships.
Field names are derived from the original COBOL copybooks. Every monetary field
uses `BigDecimal` (not float/double). PII fields are annotated. Passwords use BCrypt.

```mermaid
erDiagram
    Account {
        VARCHAR_11 acctId PK "ACCT-ID — from CVACT01Y"
        CHAR_1 acctActiveStatus "ACCT-ACTIVE-STATUS"
        BIGDECIMAL acctCurrBal "ACCT-CURR-BAL — S9(10)V99"
        BIGDECIMAL acctCreditLimit "ACCT-CREDIT-LIMIT — S9(10)V99"
        BIGDECIMAL acctCashCreditLimit "ACCT-CASH-CREDIT-LIMIT — S9(10)V99"
        VARCHAR_10 acctOpenDate "ACCT-OPEN-DATE"
        VARCHAR_10 acctExpirationDate "ACCT-EXPIRAION-DATE"
        VARCHAR_10 acctReissueDate "ACCT-REISSUE-DATE"
        BIGDECIMAL acctCurrCycCredit "ACCT-CURR-CYC-CREDIT — S9(10)V99"
        BIGDECIMAL acctCurrCycDebit "ACCT-CURR-CYC-DEBIT — S9(10)V99"
        VARCHAR_10 acctAddrZip "ACCT-ADDR-ZIP"
        VARCHAR_10 acctGroupId "ACCT-GROUP-ID"
        INTEGER version "Optimistic lock — @Version"
    }

    Card {
        VARCHAR_16 cardNum PK "CARD-NUM — from CVACT02Y"
        VARCHAR_11 cardAcctId FK "CARD-ACCT-ID — indexed — AIX equivalent"
        VARCHAR_3 cardCvvCd "CARD-CVV-CD — PII"
        VARCHAR_50 cardEmbossedName "CARD-EMBOSSED-NAME"
        VARCHAR_10 cardExpirationDate "CARD-EXPIRAION-DATE"
        CHAR_1 cardActiveStatus "CARD-ACTIVE-STATUS"
    }

    CardXref {
        VARCHAR_16 xrefCardNum PK "XREF-CARD-NUM — from CVACT03Y"
        VARCHAR_9 xrefCustId FK "XREF-CUST-ID"
        VARCHAR_11 xrefAcctId FK "XREF-ACCT-ID — indexed — AIX equivalent"
    }

    Customer {
        VARCHAR_9 custId PK "CUST-ID — from CVCUS01Y"
        VARCHAR_25 custFirstName "CUST-FIRST-NAME"
        VARCHAR_25 custMiddleName "CUST-MIDDLE-NAME"
        VARCHAR_25 custLastName "CUST-LAST-NAME"
        VARCHAR_50 custAddrLine1 "CUST-ADDR-LINE-1"
        VARCHAR_50 custAddrLine2 "CUST-ADDR-LINE-2"
        VARCHAR_50 custAddrLine3 "CUST-ADDR-LINE-3"
        VARCHAR_2 custAddrStateCd "CUST-ADDR-STATE-CD"
        VARCHAR_3 custAddrCountryCd "CUST-ADDR-COUNTRY-CD"
        VARCHAR_10 custAddrZip "CUST-ADDR-ZIP"
        VARCHAR_15 custPhoneNum1 "CUST-PHONE-NUM-1"
        VARCHAR_15 custPhoneNum2 "CUST-PHONE-NUM-2"
        VARCHAR_9 custSsn "CUST-SSN — PII"
        VARCHAR_20 custGovtIssuedId "CUST-GOVT-ISSUED-ID — PII"
        VARCHAR_10 custDobYyyyMmDd "CUST-DOB-YYYY-MM-DD"
        VARCHAR_10 custEftAccountId "CUST-EFT-ACCOUNT-ID"
        CHAR_1 custPriCardHolderInd "CUST-PRI-CARD-HOLDER-IND"
        INTEGER custFicoCreditScore "CUST-FICO-CREDIT-SCORE"
    }

    Transaction {
        VARCHAR_16 tranId PK "TRAN-ID — from CVTRA05Y"
        VARCHAR_2 tranTypeCd FK "TRAN-TYPE-CD"
        INTEGER tranCatCd FK "TRAN-CAT-CD — 9(04)"
        VARCHAR_10 tranSource "TRAN-SOURCE"
        VARCHAR_100 tranDesc "TRAN-DESC"
        BIGDECIMAL tranAmt "TRAN-AMT — S9(09)V99"
        VARCHAR_9 tranMerchantId "TRAN-MERCHANT-ID"
        VARCHAR_50 tranMerchantName "TRAN-MERCHANT-NAME"
        VARCHAR_50 tranMerchantCity "TRAN-MERCHANT-CITY"
        VARCHAR_10 tranMerchantZip "TRAN-MERCHANT-ZIP"
        VARCHAR_16 tranCardNum "TRAN-CARD-NUM"
        VARCHAR_26 tranOrigTs "TRAN-ORIG-TS — indexed — AIX — ISO-8601"
        VARCHAR_26 tranProcTs "TRAN-PROC-TS"
    }

    DailyTransaction {
        BIGINT id PK "Auto-generated — staging table"
        VARCHAR_16 dalytranId "DALYTRAN-ID — from CVTRA06Y"
        VARCHAR_2 dalytranTypeCd "DALYTRAN-TYPE-CD"
        INTEGER dalytranCatCd "DALYTRAN-CAT-CD"
        VARCHAR_10 dalytranSource "DALYTRAN-SOURCE"
        VARCHAR_100 dalytranDesc "DALYTRAN-DESC"
        BIGDECIMAL dalytranAmt "DALYTRAN-AMT — S9(09)V99"
        VARCHAR_16 dalytranCardNum "DALYTRAN-CARD-NUM"
        VARCHAR_26 dalytranOrigTs "DALYTRAN-ORIG-TS"
        VARCHAR_26 dalytranProcTs "DALYTRAN-PROC-TS"
    }

    UserSecurity {
        VARCHAR_8 secUsrId PK "SEC-USR-ID — from CSUSR01Y"
        VARCHAR_20 secUsrFname "SEC-USR-FNAME"
        VARCHAR_20 secUsrLname "SEC-USR-LNAME"
        VARCHAR_72 secUsrPwd "SEC-USR-PWD — BCrypt hashed — was plaintext X(08)"
        CHAR_1 secUsrType "SEC-USR-TYPE — A=ADMIN, U=USER"
    }

    TransactionTypeRef {
        VARCHAR_2 tranType PK "TRAN-TYPE — 7 reference records"
        VARCHAR_50 tranTypeDesc "Type description"
    }

    TransactionCategoryRef {
        VARCHAR_4 tranCat PK "TRAN-CAT — 18 reference records"
        VARCHAR_50 tranCatDesc "Category description"
    }

    DiscountGroup {
        BIGINT id PK "Auto-generated"
        VARCHAR_10 groupId "Group identifier"
        BIGDECIMAL interestRate "Interest/discount rate — BigDecimal"
    }

    CategoryBalance {
        VARCHAR_11 acctId PK "Composite PK part 1"
        VARCHAR_4 tranCatCd PK "Composite PK part 2"
        BIGDECIMAL balance "Category-level balance — BigDecimal"
    }

    Account ||--o{ Card : "has cards"
    Account ||--o{ CardXref : "referenced by xrefs"
    Account ||--o{ Transaction : "contains transactions"
    Account ||--o{ CategoryBalance : "tracks balances"
    Customer ||--o{ CardXref : "linked via xrefs"
    Card }o--|| Account : "belongs to"
    Transaction }o--|| TransactionTypeRef : "of type"
    Transaction }o--|| TransactionCategoryRef : "in category"
    CardXref }o--|| Customer : "references customer"
    CardXref }o--|| Account : "references account"
```

> **Legend — Diagram 7:** Each entity maps to a PostgreSQL table. PK = primary key.
> FK = foreign key. Fields marked **PII** (CUST-SSN, CUST-GOVT-ISSUED-ID, CARD-CVV-CD)
> require secure handling. `SEC-USR-PWD` stores BCrypt-hashed passwords (was plaintext
> in COBOL). All `BIGDECIMAL` fields correspond to COBOL `PIC S9(n)V99 COMP-3` packed
> decimal — no floating-point is used. `@Version` enables JPA optimistic locking,
> replacing CICS `READ UPDATE` → `REWRITE` semantics. Indexed FK fields replace
> VSAM Alternate Indexes (AIX).

---

## 8. After State: REST API Request Flow

This sequence diagram shows the Spring Boot request lifecycle, including Spring
Security authentication, the request-scoped `CardDemoContext` (replacing COMMAREA),
and the layered delegation from Controller → Service → Repository → PostgreSQL.

```mermaid
sequenceDiagram
    autonumber
    participant C as HTTP Client
    participant SF as Spring Security Filter Chain
    participant AC as AuthController
    participant SS as SignonService
    participant USR as UserSecurityRepository
    participant DB as PostgreSQL 16+
    participant CTX as CardDemoContext — Request Scope

    Note over C, DB: Authentication Flow — POST /api/auth/login

    C->>SF: POST /api/auth/login — userId + password
    SF->>AC: Pass to AuthController — public endpoint
    AC->>SS: authenticate — userId, password
    SS->>USR: findById — secUsrId
    USR->>DB: SELECT FROM user_security WHERE sec_usr_id = ?
    DB-->>USR: UserSecurity entity
    USR-->>SS: UserSecurity — BCrypt-hashed password
    SS->>SS: BCrypt.matches — verify password
    SS->>CTX: Initialize — set userId, userType, currentProgram
    SS-->>AC: Authentication result + session token
    AC-->>C: 200 OK — session token + user info

    Note over C, DB: Authenticated Request — GET /api/accounts/00000000001

    participant ACtrl as AccountController
    participant AVS as AccountViewService
    participant AR as AccountRepository
    participant XR as CardXrefRepository

    C->>SF: GET /api/accounts/00000000001 — with session token
    SF->>SF: Validate session token — extract roles
    SF->>ACtrl: Dispatch to AccountController
    ACtrl->>CTX: Read user context — userId, userType
    ACtrl->>AVS: viewAccount — acctId
    AVS->>AR: findById — acctId
    AR->>DB: SELECT FROM account WHERE acct_id = ?
    DB-->>AR: Account entity — BigDecimal monetary fields
    AR-->>AVS: Account
    AVS->>XR: findByAcctId — acctId — indexed query — AIX equivalent
    XR->>DB: SELECT FROM card_xref WHERE xref_acct_id = ?
    DB-->>XR: List of CardXref
    XR-->>AVS: CardXref list
    AVS-->>ACtrl: AccountView response DTO
    ACtrl-->>C: 200 OK — JSON response
```

> **Legend — Diagram 8:** Solid arrows = request flow. Dashed arrows = response flow.
> The Spring Security Filter Chain intercepts every request. The `CardDemoContext`
> (request-scoped bean) replaces the 1024-byte COMMAREA, carrying user session state
> through the request lifecycle. Repository queries to PostgreSQL replace VSAM
> `EXEC CICS READ` operations. Indexed queries on `xref_acct_id` replace AIX lookups.

---

## 9. Component Inventory Comparison

This table provides a side-by-side count of every architectural component before
and after the migration. All counts match the source repository and the AAP exactly.

| Category | Before (COBOL / CICS / VSAM) | After (Java 25 / Spring Boot 3.5.x / PostgreSQL) |
|---|---|---|
| **Runtime** | z/OS + CICS TS | JVM 25 LTS + Spring Boot 3.5.x embedded Tomcat |
| **Presentation** | 17 BMS Maps + 17 BMS Copybooks (AI/AO) | 7 REST Controllers + Request/Response DTOs |
| **Business Logic** | 28 COBOL Programs (18 online + 10 batch) | 27 Service Classes (17 online + 10 batch) + 1 Utility |
| **Data Access** | 10 VSAM KSDS Datasets + 3 Alternate Indexes | 11 JPA Entities + 11 Spring Data Repositories |
| **Session Context** | COMMAREA (COCOM01Y) — 1024 bytes | CardDemoContext — `@RequestScope` bean |
| **Batch Processing** | 6 JCL Core Jobs + 10 COBOL batch programs | 7 Spring Batch Jobs + 10 batch service classes |
| **Data Definitions** | 28 COBOL Copybooks | 14 DTOs + 4 Enums + 6 Exception classes |
| **Security** | RACF + plaintext passwords (SEC-USR-PWD) | Spring Security + BCrypt hashed passwords |
| **Database** | VSAM KSDS files on DASD | PostgreSQL 16+ |
| **Schema Management** | IDCAMS DEFINE CLUSTER | Flyway versioned migrations |
| **Observability** | CICS system logs + JCL job logs | Actuator + Micrometer Tracing + Prometheus metrics |
| **Build System** | z/OS Enterprise COBOL compiler + Linkage editor | Maven 3.9.9 + Java 25 compiler (`-Xlint:all -Werror`) |
| **Testing** | Manual 3270 terminal testing | JUnit 5 + Testcontainers (≥80% line coverage) |
| **Dependency Security** | N/A | OWASP dependency-check — zero critical/high CVEs |
| **Shared Utility** | CSUTLDTC (date conversion) — COBOL CALL target | DateConversionUtil — `@Autowired` Spring bean |
| **Monetary Arithmetic** | COMP-3 packed decimal (`PIC S9(n)V99`) | `BigDecimal` with `RoundingMode.HALF_UP` |

### Detailed Program-to-Service Mapping — Online

| # | CICS Trans ID | BMS Map | COBOL Program | Java Service |
|---|---|---|---|---|
| 1 | CC00 | COSGN00 | COSGN00C | SignonService |
| 2 | CM00 | COMEN01 | COMEN01C | MainMenuService |
| 3 | CA00 | COADM01 | COADM01C | AdminMenuService |
| 4 | CAVW | COACTVW | COACTVWC | AccountViewService |
| 5 | CAUP | COACTUP | COACTUPC | AccountUpdateService |
| 6 | CCLI | COCRDLI | COCRDLIC | CreditCardListService |
| 7 | CCDL | COCRDSL | COCRDSLC | CreditCardDetailService |
| 8 | CCUP | COCRDUP | COCRDUPC | CreditCardUpdateService |
| 9 | CT00 | COTRN00 | COTRN00C | TransactionListService |
| 10 | CT01 | COTRN01 | COTRN01C | TransactionViewService |
| 11 | CT02 | COTRN02 | COTRN02C | TransactionAddService |
| 12 | CR00 | CORPT00 | CORPT00C | ReportService |
| 13 | CB00 | COBIL00 | COBIL00C | BillPaymentService |
| 14 | CU00 | COUSR00 | COUSR00C | UserListService |
| 15 | CU01 | COUSR01 | COUSR01C | UserAddService |
| 16 | CU02 | COUSR02 | COUSR02C | UserUpdateService |
| 17 | CU03 | COUSR03 | COUSR03C | UserDeleteService |

> **Note:** CSUTLDTC (shared date utility) is not a standalone service — it becomes
> `DateConversionUtil`, a shared utility class injected via `@Autowired` wherever needed.

### Detailed Program-to-Service Mapping — Batch

| # | JCL Job | COBOL Program | Java Service | Spring Batch Job |
|---|---|---|---|---|
| 1 | ACCTFILE | CBACT01C | AccountRefreshService | AccountLoadJobConfig |
| 2 | — | CBACT02C | AccountProcessingService | — |
| 3 | — | CBACT03C | AccountOperationsService | — |
| 4 | INTCALC | CBACT04C | InterestCalculationService | InterestCalcJobConfig |
| 5 | CUSTFILE | CBCUS01C | CustomerFileService | CustomerLoadJobConfig |
| 6 | TRANFILE | CBTRN01C | TransactionUtilService | TransactionLoadJobConfig |
| 7 | POSTTRAN | CBTRN02C | DailyPostingService | DailyPostingJobConfig |
| 8 | — | CBTRN03C | TransactionProcessService | — |
| 9 | CREASTMT | CBSTM03A | StatementEngineService | StatementGenJobConfig |
| 10 | — | CBSTM03B | StatementIoService | — |
| — | COMBTRAN | (SORT utility) | — | TransactionSortJobConfig |

---

## 10. Legend and Notation Guide

### Color Conventions

| Color | Hex Range | Meaning |
|---|---|---|
| Red / Pink | `#ffcdd2` – `#b71c1c` | Legacy mainframe components (COBOL, CICS, BMS, VSAM) |
| Orange / Amber | `#ffe0b2` – `#e65100` | Business logic tier in before-state diagrams |
| Green | `#c8e6c9` – `#1b5e20` | Modern Java / Spring Boot components (API, Service, Batch) |
| Blue | `#bbdefb` – `#0d47a1` | Data layer (VSAM datasets, PostgreSQL, JPA entities) |
| Purple | `#e1bee7` – `#4a148c` | Batch orchestration (JCL jobs, Spring Batch jobs) |
| Yellow | `#fff9c4` – `#f57f17` | Cross-cutting concerns (Security, Context, DTOs, Enums) |
| Cyan | `#e0f7fa` – `#006064` | Observability (Actuator, Tracing, Metrics, Logging) |

### Shape Conventions

| Shape | Mermaid Syntax | Meaning |
|---|---|---|
| Rectangle | `["label"]` | Service, program, controller, or processing component |
| Cylinder | `[("label")]` | Database (PostgreSQL, VSAM) |
| Rounded rectangle | `("label")` | External actor (terminal, HTTP client) |
| Subgraph | `subgraph` | Logical grouping — tier, layer, or component cluster |

### Arrow Conventions

| Arrow Style | Mermaid Syntax | Meaning |
|---|---|---|
| Solid line | `-->` | Runtime data flow or request/response path |
| Dashed line | `-.->` | Control flow, scheduling, or dependency reference |
| Labeled arrow | `-->\|"label"\|` | Transformation or conditional routing |

### Abbreviations

| Abbreviation | Full Term |
|---|---|
| **AIX** | Alternate Index — VSAM secondary access path |
| **BMS** | Basic Mapping Support — CICS screen definition |
| **CICS** | Customer Information Control System — online transaction processor |
| **COMMAREA** | Communication Area — 1024-byte session context (COCOM01Y) |
| **COMP-3** | Packed decimal — COBOL binary-coded decimal storage |
| **DASD** | Direct Access Storage Device — z/OS disk |
| **DTO** | Data Transfer Object — Java POJO for data transport |
| **FK** | Foreign Key |
| **GDG** | Generation Data Group — VSAM versioned dataset family |
| **IDCAMS** | Access Method Services — VSAM utility program |
| **JCL** | Job Control Language — z/OS batch job definition |
| **JPA** | Jakarta Persistence API — Java ORM standard |
| **KSDS** | Key-Sequenced Data Set — VSAM primary file organization |
| **PII** | Personally Identifiable Information |
| **PK** | Primary Key |
| **RACF** | Resource Access Control Facility — z/OS security |
| **TRANSID** | Transaction Identifier — 4-character CICS transaction code |
| **VSAM** | Virtual Storage Access Method — z/OS file system |
| **XCTL** | Transfer Control — CICS inter-program transfer |

---

*Document generated as part of the CardDemo COBOL-to-Java migration. All diagrams
reflect the source repository structure with zero feature expansion — the Java
application is a strict functional equivalent of the original COBOL application.*
