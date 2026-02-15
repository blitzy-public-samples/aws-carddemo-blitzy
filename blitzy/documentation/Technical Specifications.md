# Technical Specification

# 0. Agent Action Plan

## 0.1 Intent Clarification

### 0.1.1 Core Documentation Objective

Based on the provided requirements, the Blitzy platform understands that the documentation objective is to **create a comprehensive proprietary mainframe utility assessment report** for the AWS CardDemo COBOL/CICS/VSAM application. This report will serve as a structured migration analysis document that inventories all proprietary IBM mainframe dependencies, researches each through authoritative external documentation, evaluates Java migration paths, and provides a risk-aware testing strategy — all presented with citations to enable stakeholder verification.

**Request Category:** Create new documentation

**Documentation Type:** Technical analysis report / Migration guide / Architecture documentation

**Documentation Requirements with Enhanced Clarity:**

- **Proprietary Utility Inventory**: Produce a complete catalog of every IBM proprietary mainframe utility invoked across the CardDemo codebase — including COBOL `CALL` statements to Language Environment services, `EXEC CICS` commands, JCL-referenced utilities (IDCAMS, SORT, IEBGENER, IEFBR14), and BMS map processing dependencies. Each entry must specify the utility name, invocation parameters, expected behavior, and classification (business logic vs. infrastructure).
- **External Documentation Research**: For each identified utility, conduct web-based research to locate official IBM documentation, community migration guides, and known Java equivalents. Document search queries, discovered URLs, key behavioral specifications, and any migration warnings.
- **Dependency Impact Analysis**: Cross-reference each utility's documented behavior against its actual usage within the CardDemo codebase. Assess replication complexity in Java, identify candidate open-source libraries, and flag utilities where behavior cannot be directly replicated.
- **Per-Utility Migration Strategy**: Provide a concrete recommendation for each proprietary dependency — whether direct library replacement, custom implementation, or service wrapper — along with specific Java library candidates validated through external research.
- **Risk Assessment**: Classify utilities by migration risk level (HIGH / MEDIUM / LOW), identify gaps in available documentation, and highlight utilities requiring custom development or vendor engagement.
- **Testing and Validation Framework**: Define a byte-level comparison testing strategy that validates behavioral parity between mainframe and Java implementations, incorporating edge cases identified from external documentation.

**Inferred Documentation Needs:**

- Based on code analysis: The COBOL programs in `app/cbl/` contain 28 source files with extensive use of IBM Language Environment services (`CEEDAYS`, `CEE3ABD`) and CICS runtime APIs that all require migration documentation.
- Based on structure: The batch processing chain (POSTTRAN → INTCALC → COMBTRAN → CREASTMT) spans multiple programs and JCL utilities requiring consolidated workflow documentation.
- Based on dependencies: The VSAM KSDS file handling via CICS file control commands (READ, WRITE, REWRITE, DELETE, STARTBR, READNEXT, READPREV, ENDBR) represents the largest migration surface area and requires interface documentation mapping to JDBC/JPA patterns.
- Based on data formats: EBCDIC-to-ASCII encoding, COMP-3 packed decimal, and fixed-width record layouts require dedicated data transformation documentation for the migration.

### 0.1.2 Special Instructions and Constraints

**Critical Directives:**
- **Web search is mandatory** for each identified utility — the user explicitly requires external documentation research with citations and links to authoritative sources
- **Output must demonstrate stakeholder readiness** — findings must prove that "we understand EXACTLY what proprietary dependencies exist" with "evidence-based" plans
- **Citations and links to all external documentation** must be included so stakeholders can verify research and approach
- **Transparency about risks and unknowns** is required — documentation must flag gaps honestly

**Search Query Templates Specified by User:**
- "[Utility Name] IBM mainframe documentation"
- "[Utility Name] COBOL to Java migration"
- "[Utility Name] Java equivalent"
- "IBM [Utility Name] specification"
- "[Utility Name] modernization patterns"

**Style Preferences:**
- Structured report format with numbered sections
- Evidence-based analysis with source citations
- Risk classification using HIGH/MEDIUM/LOW labels
- Testing strategy with concrete validation steps
- Progressive disclosure: inventory → analysis → strategy → risk → testing

### 0.1.3 Technical Interpretation

These documentation requirements translate to the following technical documentation strategy:

- To **inventory proprietary utilities**, we will parse all 28 COBOL source files in `app/cbl/`, all 28 copybooks in `app/cpy/`, all 17 BMS maps in `app/bms/`, and the LISTCAT catalog snapshot in `app/catlg/LISTCAT.txt` to extract every `CALL`, `EXEC CICS`, `COPY` statement, and JCL utility reference documented in `README.md`.
- To **document external research**, we will create a structured findings section with URLs, key specifications, and migration patterns for each utility discovered via web search of IBM documentation and community sources.
- To **map dependency impacts**, we will create per-utility analysis documents cross-referencing code locations (`file:line`) with documented behavior from IBM sources.
- To **define migration strategies**, we will create a recommendations document mapping each utility to specific Java/Spring Boot libraries, JPA patterns, or custom implementations with evidence from community migration case studies.
- To **assess risks**, we will create a risk matrix document classifying each utility by migration complexity, documentation completeness, and behavioral equivalence confidence.
- To **design the testing framework**, we will create a validation strategy document specifying byte-level comparison methods, regression test case templates, and edge cases sourced from IBM documentation.

## 0.2 Documentation Discovery and Analysis

### 0.2.1 Existing Documentation Infrastructure Assessment

Repository analysis reveals a **minimal documentation structure** with a single top-level `README.md` file and no dedicated documentation framework or generator. The codebase is an AWS-published sample mainframe application ("CardDemo") with no existing migration analysis documentation, no API reference docs, and no architecture decision records.

**Documentation files found:**
- `README.md` — Project overview, technology inventory (COBOL, CICS, VSAM, JCL, BMS, RACF), dataset listings, compilation instructions, CICS resource definitions, batch job catalog, and transaction inventory
- `CONTRIBUTING.md` — Standard GitHub contribution guidelines
- `CODE_OF_CONDUCT.md` — Community code of conduct
- `LICENSE` — License file

**Documentation framework:** None detected (no `mkdocs.yml`, `docusaurus.config.js`, `sphinx/conf.py`, or `.readthedocs.yml`)

**API documentation tools:** None — COBOL source files lack structured inline documentation or API doc annotations

**Diagram tools:** None configured — Mermaid will be used for all diagrams in the new documentation

**Documentation hosting:** Not applicable — this is a sample repository with GitHub-hosted README only

### 0.2.2 Repository Code Analysis for Documentation

**Search patterns employed and key findings:**

- **COBOL source programs** (`app/cbl/*.cbl`, `app/cbl/*.CBL`): 28 files identified — 9 batch programs (prefix `CB*`) and 19 online CICS programs (prefix `CO*`). Each was analyzed with `grep` for `CALL`, `EXEC CICS`, `OPEN`, `CLOSE`, `SELECT`, `ASSIGN`, and `FILE-CONTROL` statements.
- **Copybooks** (`app/cpy/*.cpy`, `app/cpy/*.CPY`): 28 copybooks containing data record layouts (`CUSTREC.CPY`, `CVACT01Y.CPY`), screen I/O contracts (`COCOM01Y.CPY`), and procedural includes.
- **BMS Maps** (`app/bms/*.bms`): 17 BMS mapset definitions for 3270 terminal screens, including COACTUP (account update), COBIL00 (bill payment), CORPT00 (report selection), and COTRN0 (transactions).
- **Catalog Data** (`app/catlg/LISTCAT.txt`): IDCAMS LISTCAT output showing 13 VSAM KSDS clusters (ACCTFILE, CARDDATA, CARDXREF, CUSTDATA, DISCGRP, TRANFILE, TRANCATG, TRANTYPE, TCATBALF, TRANBKP, USRSEC), 3 Alternate Indexes (TRANSACT-AIX1, TRANSACT-AIX2, CARDXREF-AIX1), 2 GDG Base entries (DALYREJS, SYSTRAN), and several NONVSAM sequential files.
- **Sample data** (`app/data/ASCII/`, `app/data/EBCDIC/`): Test datasets in both ASCII (`.txt`) and EBCDIC (`.ps`) formats for ACCTDATA, CARDDATA, CUSTDATA, DISCGRP, TRANSACT, TRANCATG, TRANTYPE, and USRSEC.

**Key directories examined:** `app/cbl/`, `app/cpy/`, `app/bms/`, `app/cpy-bms/`, `app/catlg/`, `app/data/`, root directory

**Related documentation found:** The `README.md` provides an authoritative batch job catalog and CICS transaction inventory that serve as the baseline for utility enumeration.

### 0.2.3 Web Search Research Conducted

The following external research was conducted to support the proprietary utility assessment:

- **IDCAMS / Access Method Services**: Searched "IDCAMS IBM mainframe utility COBOL migration Java" — found that IDCAMS is the primary VSAM dataset management utility used for DEFINE, DELETE, REPRO, LISTCAT, and ALTER operations. IBM documentation at `ibm.com/docs` and community tutorials at `mainframestechhelp.com` confirmed its central role. Migration tools such as Heirloom Computing's EBP provide IDCAMS equivalents for off-mainframe environments.
- **CEEDAYS / Language Environment date services**: Searched "CEEDAYS IBM Language Environment Java equivalent date conversion" — IBM official documentation confirms CEEDAYS converts character dates to Lilian format (integer days since October 14, 1582). Java's `java.time.temporal.ChronoField.EPOCH_DAY` and `java.time.LocalDate` provide equivalent date arithmetic capabilities.
- **DFSORT (SORT)**: Searched "DFSORT IBM mainframe SORT Java equivalent migration" — confirmed DFSORT is a high-performance sort/merge/copy utility. Micro Focus MFSORT and SyncSort DMXPRESS provide off-mainframe equivalents. For Java-native migration, `java.util.Collections.sort()` or Apache Commons CSV with custom comparators can replicate sort operations. ING Bank's case study demonstrated successful DFSORT-to-Java migration using SoftwareMining tools.
- **CEE3ABD / Language Environment abend handling**: Searched "CEE3ABD IBM Language Environment abend Java equivalent" — confirmed CEE3ABD terminates an enclave with a user abend code. IBM documentation at `ibm.com/docs/en/zos/2.4.0` describes it as accepting an abend code and timing parameter. Java equivalent is `throw new RuntimeException()` or custom exception hierarchy with application-specific error codes.
- **IEBGENER / IEFBR14**: Searched "IEBGENER IEFBR14 IBM mainframe Java replacement migration" — IEBGENER is a general-purpose dataset copy utility mapped to `java.nio.file.Files.copy()` in Java. IEFBR14 is a no-operation placeholder used only for JCL dataset allocation/deallocation, which maps to filesystem operations or S3 bucket creation in AWS.

## 0.3 Documentation Scope Analysis

### 0.3.1 Code-to-Documentation Mapping

**IBM Language Environment (LE) Callable Services:**

- Module: `app/cbl/CSUTLDTC.cbl` (Date Conversion Wrapper)
  - Proprietary APIs: `CALL 'CEEDAYS'` — Converts character date to Lilian integer format using picture string
  - Current documentation: Missing
  - Documentation needed: Full behavioral specification, parameter mapping, Lilian date epoch definition (October 14, 1582), Java `LocalDate` equivalence analysis, edge case catalog (leap years, century boundaries, invalid dates)

- Module: `app/cbl/CBACT01C.cbl`, `CBACT02C.cbl`, `CBACT03C.cbl`, `CBACT04C.cbl`, `CBCUS01C.cbl`, `CBSTM03A.cbl`, `CBTRN01C.cbl`, `CBTRN02C.cbl`, `CBTRN03C.cbl`
  - Proprietary APIs: `CALL 'CEE3ABD'` — Terminates enclave with user abend code
  - Current documentation: Missing
  - Documentation needed: Abend code semantics, timing parameter behavior, Java exception handling equivalence, per-program abend code inventory

- Module: `app/cbl/CBSTM03B.CBL` (Batch I/O Subroutine)
  - Proprietary APIs: Called via `CALL 'CBSTM03B'` — Centralized file I/O handler for TRNX, XREF, CUST, ACCT files
  - Current documentation: Missing
  - Documentation needed: File I/O abstraction pattern, record-level access semantics, Java DAO/Repository equivalence

**CICS Runtime Commands (Online Programs):**

- Module: All 19 CICS programs (`COACTUP.cbl`, `COBIL00C.cbl`, `COCRDLI.cbl`, `COCRDSL.cbl`, `COCRDUP.cbl`, `COMEN01C.cbl`, `CORPT00C.cbl`, `COSIGN00.cbl`, `COTRN00C.cbl`, `COTRN01C.cbl`, `COTRN02C.cbl`, `COTRN04C.cbl`, `COTRN05C.cbl`, `COADM01C.cbl`, `COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl`, `COACTVWC.cbl`)
  - Proprietary APIs: 18 distinct EXEC CICS command types: `ABEND`, `ASKTIME`, `ASSIGN`, `DELETE`, `ENDBR`, `FORMATTIME`, `HANDLE`, `READ`, `READNEXT`, `READPREV`, `RECEIVE`, `RETURN`, `REWRITE`, `SEND`, `STARTBR`, `WRITE`, `WRITEQ`, `XCTL`
  - Current documentation: Missing
  - Documentation needed: Per-command behavioral specification, VSAM-to-JDBC mapping for file control commands, BMS-to-REST/HTML mapping for terminal I/O, pseudo-conversational pattern documentation, COMMAREA-to-session state mapping

- Module: `app/cbl/CORPT00C.cbl` (Report Generation — Online Trigger)
  - Proprietary APIs: `EXEC CICS WRITEQ TD QUEUE('JOBS')` — Writes JCL records to Transient Data Queue for batch job submission
  - Current documentation: Missing
  - Documentation needed: TDQ-to-message queue migration strategy, JES job submission equivalence in AWS (Step Functions, Lambda, SQS), JCL record format analysis

**CICS System Services:**

- Module: `app/cbl/COBIL00C.cbl`, `COTRN02C.cbl` and others
  - Proprietary APIs: `EXEC CICS ASKTIME` + `EXEC CICS FORMATTIME` — Retrieves current time and formats it
  - Current documentation: Missing
  - Documentation needed: Time format specifications, Java `java.time.LocalDateTime` equivalence, timezone handling differences

**JCL-Referenced Utilities (from README.md batch job catalog):**

- Utility: **IDCAMS** — Used in DEFVSAM, LOADVSAM, DEFGDGB jobs
  - Functions: DEFINE CLUSTER, REPRO, DELETE, ALTER, LISTCAT
  - Current documentation: Missing
  - Documentation needed: Full command inventory for CardDemo, parameter analysis, AWS RDS/S3 initialization equivalence

- Utility: **DFSORT** — Used in COMBTRAN job
  - Functions: SORT FIELDS / MERGE FIELDS on transaction records
  - Current documentation: Missing
  - Documentation needed: Sort key specifications, Java `Comparator` equivalence, performance considerations

- Utility: **IEBGENER** — Used in DUSRSECJ job
  - Functions: Sequential file copy for user security file loading
  - Current documentation: Missing
  - Documentation needed: File copy semantics, `java.nio.file.Files.copy()` equivalence

- Utility: **IEFBR14** — Used in CLOSEFIL, OPENFIL jobs
  - Functions: No-op placeholder for dataset allocation/deallocation
  - Current documentation: Missing
  - Documentation needed: Purpose analysis (VSAM file availability control), AWS equivalent (service endpoint management)

**BMS Map Processing:**

- Module: `app/bms/*.bms` (17 mapsets)
  - Proprietary APIs: `DFHMSD`, `DFHMDI`, `DFHMDF` macros with `DFHBMSCA` and `DFHAID` copybooks
  - Current documentation: Missing
  - Documentation needed: Screen-to-REST/HTML mapping strategy, field attribute translation, AID key handling equivalence

### 0.3.2 Documentation Gap Analysis

Given the requirements and repository analysis, documentation gaps include:

**Undocumented Proprietary APIs (Complete Inventory):**
- `CEEDAYS` — 0% documented, critical for date arithmetic in 2+ programs
- `CEE3ABD` — 0% documented, used in all 9 batch programs for error handling
- `EXEC CICS` (18 command types) — 0% documented for migration purposes, used across all 19 online programs
- `WRITEQ TD` — 0% documented, critical batch/online coupling pattern in `CORPT00C`
- `IDCAMS` commands — 0% documented, foundational for VSAM dataset lifecycle
- `DFSORT` — 0% documented, used in transaction merging batch job
- `IEBGENER` — 0% documented, used in data loading
- `IEFBR14` — 0% documented, used in file availability control
- `BMS macros` — 0% documented for migration purposes, 17 mapsets requiring screen migration analysis

**Missing Migration Documentation:**
- No VSAM-to-relational database mapping guide
- No EBCDIC-to-ASCII data conversion strategy
- No COMP-3 packed decimal handling documentation
- No GDG-to-S3 versioned storage mapping
- No pseudo-conversational to stateless HTTP pattern documentation
- No COMMAREA-to-session/token state management guide

**Incomplete Architecture Documentation:**
- Batch processing chain dependencies undocumented
- Online-to-batch coupling via TDQ undocumented
- VSAM Alternate Index (AIX) query patterns undocumented
- CICS transaction routing and program control flow undocumented

## 0.4 Documentation Implementation Design

### 0.4.1 Documentation Structure Planning

The proprietary utility assessment report will be structured as a single comprehensive documentation tree, with each section addressing one pillar of the user's required analysis:

```
docs/
├── migration-analysis/
│   ├── 00-executive-summary.md
│   ├── 01-proprietary-utility-inventory.md
│   │   (Complete catalog of all IBM utilities with classification)
│   ├── 02-external-documentation-research.md
│   │   (Web search findings with citations and links per utility)
│   ├── 03-dependency-impact-analysis.md
│   │   (Cross-referenced behavioral analysis with complexity ratings)
│   ├── 04-migration-strategy-per-utility.md
│   │   (Java implementation options with library recommendations)
│   ├── 05-risk-assessment.md
│   │   (Risk matrix with HIGH/MEDIUM/LOW classifications)
│   ├── 06-testing-validation-framework.md
│   │   (Byte-level comparison strategy and regression test design)
│   └── appendices/
│       ├── A-cics-command-reference.md
│       ├── B-vsam-dataset-catalog.md
│       ├── C-batch-job-dependency-map.md
│       ├── D-bms-screen-inventory.md
│       └── E-source-code-cross-reference.md
└── README.md (updated with link to migration analysis)
```

### 0.4.2 Content Generation Strategy

**Information Extraction Approach:**

- Extract all `CALL` targets from `app/cbl/*.cbl` using static code analysis (grep patterns: `CALL '`, `CALL "`)
- Extract all `EXEC CICS` command types from online programs using pattern matching
- Parse `app/catlg/LISTCAT.txt` for complete VSAM cluster topology
- Cross-reference `README.md` batch job catalog against JCL utility references (IDCAMS, SORT, IEBGENER, IEFBR14)
- Analyze copybooks in `app/cpy/` for record layout definitions that inform data migration strategy
- Extract BMS field definitions from `app/bms/*.bms` for screen migration inventory

**Template Application:**

- The user has not provided a template. Documentation will follow the 6-section output format explicitly defined in the requirements (Inventory → Research → Impact → Strategy → Risk → Testing).
- Each utility entry will follow a consistent structure: Name, Classification, Source Location, Parameters, Behavior, Java Equivalent, Risk Level, Testing Approach.

**Documentation Standards:**

- Markdown formatting with proper heading hierarchy (`#` through `####`)
- Mermaid diagrams for batch processing chains, CICS transaction flows, and VSAM access patterns
- COBOL code snippets using `cobol` fenced code blocks for source citations
- Java code snippets using `java` fenced code blocks for migration equivalents
- Source citations formatted as: `Source: app/cbl/CSUTLDTC.cbl:lines 45-62`
- Tables for utility inventories, risk matrices, and library comparison charts
- Consistent terminology: "proprietary utility" for IBM-specific, "migration target" for Java equivalent

### 0.4.3 Diagram and Visual Strategy

**Mermaid diagrams to create:**

- **Batch Processing Chain Flowchart** — Showing POSTTRAN → INTCALC → COMBTRAN → CREASTMT pipeline with utility dependencies at each stage
- **CICS Online Transaction Flow Sequence Diagram** — Illustrating pseudo-conversational pattern with COMMAREA, MAP I/O, and VSAM file control
- **VSAM Dataset Relationship Entity Diagram** — Mapping KSDS clusters, AIX paths, and GDG base entries from LISTCAT
- **Utility Migration Decision Tree** — Classifying each utility into Direct Replacement / Custom Implementation / Service Wrapper paths
- **Risk Heat Map Table** — Color-coded risk matrix across behavioral equivalence, documentation availability, and implementation complexity dimensions
- **Batch-Online Coupling Diagram** — Showing the `CORPT00C → WRITEQ TD → JES → Batch Jobs` pattern and its AWS migration equivalent (CICS → SQS → Step Functions)

## 0.5 Documentation File Transformation Mapping

### 0.5.1 File-by-File Documentation Plan

| Target Documentation File | Transformation | Source Code/Docs | Content/Changes |
|---------------------------|----------------|------------------|-----------------|
| `docs/migration-analysis/00-executive-summary.md` | CREATE | `README.md`, `app/cbl/**`, `app/catlg/LISTCAT.txt` | Executive overview of proprietary dependency landscape, migration feasibility assessment, key findings summary, stakeholder-facing risk synopsis |
| `docs/migration-analysis/01-proprietary-utility-inventory.md` | CREATE | `app/cbl/*.cbl`, `app/cbl/*.CBL`, `app/cpy/*.cpy`, `app/bms/*.bms`, `README.md` | Complete catalog of all IBM proprietary utilities — CEE3ABD, CEEDAYS, IDCAMS, DFSORT, IEBGENER, IEFBR14, 18 EXEC CICS command types, BMS macros — with source locations, parameters, classifications |
| `docs/migration-analysis/02-external-documentation-research.md` | CREATE | Web search results, IBM documentation URLs | Per-utility research findings with IBM documentation links, behavioral specifications from authoritative sources, community migration patterns, known edge cases |
| `docs/migration-analysis/03-dependency-impact-analysis.md` | CREATE | `app/cbl/*.cbl`, `app/cpy/*.cpy`, `app/catlg/LISTCAT.txt` | Cross-referenced analysis of each utility in CardDemo context, complexity ratings, Java equivalence assessment, gap identification |
| `docs/migration-analysis/04-migration-strategy-per-utility.md` | CREATE | `app/cbl/*.cbl`, web search results | Per-utility migration recommendations with Java library candidates (Apache Commons, Spring Framework, java.time), implementation approach, behavioral equivalence strategy |
| `docs/migration-analysis/05-risk-assessment.md` | CREATE | `app/cbl/*.cbl`, `app/catlg/LISTCAT.txt`, web search results | Risk matrix with HIGH/MEDIUM/LOW classifications per utility, documentation gap analysis, custom development requirements, vendor engagement flags |
| `docs/migration-analysis/06-testing-validation-framework.md` | CREATE | `app/data/ASCII/`, `app/data/EBCDIC/`, `app/cbl/*.cbl` | Byte-level comparison strategy, database state validation approach, regression test case design, edge case catalog from IBM docs, sample data utilization plan |
| `docs/migration-analysis/appendices/A-cics-command-reference.md` | CREATE | `app/cbl/CO*.cbl` | Complete EXEC CICS command inventory with per-program usage matrix, parameter analysis, and Spring MVC/JPA mapping recommendations |
| `docs/migration-analysis/appendices/B-vsam-dataset-catalog.md` | CREATE | `app/catlg/LISTCAT.txt`, `app/cpy/*.cpy` | Full VSAM topology: 13 KSDS clusters, 3 AIX paths, 2 GDG bases, record layouts from copybooks, RDS/S3 migration targets |
| `docs/migration-analysis/appendices/C-batch-job-dependency-map.md` | CREATE | `README.md`, `app/cbl/CB*.cbl` | Batch job chain documentation with utility dependencies at each step, JCL-to-shell/Step Functions migration mapping |
| `docs/migration-analysis/appendices/D-bms-screen-inventory.md` | CREATE | `app/bms/*.bms`, `app/cpy-bms/*.cpy` | 17 BMS mapset catalog with field definitions, AID key handling, and web UI migration considerations |
| `docs/migration-analysis/appendices/E-source-code-cross-reference.md` | CREATE | `app/cbl/**`, `app/cpy/**` | Master cross-reference table mapping every COBOL source file to its proprietary dependencies with line-number citations |
| `README.md` | UPDATE | `README.md` | Add a "Migration Analysis" section with links to the documentation tree under `docs/migration-analysis/` |

### 0.5.2 New Documentation Files Detail

**File: `docs/migration-analysis/01-proprietary-utility-inventory.md`**
- Type: Technical inventory / catalog
- Source Code: `app/cbl/*.cbl`, `app/cbl/*.CBL` (28 source files), `app/bms/*.bms` (17 mapsets)
- Sections:
  - Overview (scope and methodology)
  - Language Environment Services (CEE3ABD from 9 batch programs, CEEDAYS from `CSUTLDTC.cbl`)
  - CICS Runtime Commands (18 command types across 19 programs)
  - JCL Utility Programs (IDCAMS, DFSORT, IEBGENER, IEFBR14 from batch job catalog)
  - BMS Map Processing (DFHMSD/DFHMDI/DFHMDF macros, DFHBMSCA/DFHAID copybooks)
  - VSAM File Access Patterns (KSDS, AIX, GDG from `LISTCAT.txt`)
  - Classification Summary (business logic vs. infrastructure)
- Diagrams:
  - Utility classification treemap
  - Per-program dependency matrix (heatmap table)
- Key Citations: `app/cbl/CSUTLDTC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cbl/CBACT01C.cbl`, `app/cbl/CBSTM03B.CBL`, `app/catlg/LISTCAT.txt`, `README.md`

**File: `docs/migration-analysis/02-external-documentation-research.md`**
- Type: Research findings / bibliography
- Source: Web search results from IBM docs, community forums, migration case studies
- Sections:
  - CEEDAYS — IBM z/OS Language Environment Programming Reference, Lilian date specification
  - CEE3ABD — IBM z/OS LE Reference, abend code and timing parameter specification
  - IDCAMS — IBM z/OS DFSMS Access Method Services documentation
  - DFSORT — IBM z/OS DFSORT Application Programming Guide (SC23-6878)
  - IEBGENER — IBM z/OS DFSMSdfp Utilities documentation
  - IEFBR14 — IBM z/OS MVS JCL Reference
  - CICS Commands — IBM CICS TS Application Programming Reference
  - BMS Macros — IBM CICS TS Application Programming Guide
- Key Citations: IBM official documentation URLs, community sources (mainframestechhelp.com, ibmmainframer.com), case studies (ING Bank/SoftwareMining)

**File: `docs/migration-analysis/04-migration-strategy-per-utility.md`**
- Type: Migration recommendation guide
- Source Code: `app/cbl/*.cbl`, web search results
- Sections:
  - CEEDAYS → `java.time.LocalDate` with custom Lilian date converter
  - CEE3ABD → Custom exception hierarchy with abend code mapping
  - IDCAMS → AWS RDS DDL scripts + S3 lifecycle policies + Flyway migrations
  - DFSORT → `java.util.Comparator` with Apache Commons IO for file-level sort
  - IEBGENER → `java.nio.file.Files.copy()` or AWS S3 copy operations
  - IEFBR14 → No-op; replaced by infrastructure provisioning (CloudFormation/Terraform)
  - CICS File Control → Spring Data JPA repositories with JDBC templates
  - CICS Terminal I/O → Spring MVC controllers with Thymeleaf/React UI
  - CICS Program Control → Spring service layer with dependency injection
  - WRITEQ TD → Amazon SQS or AWS Step Functions for async job submission
  - BMS Maps → HTML/CSS templates or React components

**File: `docs/migration-analysis/appendices/B-vsam-dataset-catalog.md`**
- Type: Data architecture reference
- Source: `app/catlg/LISTCAT.txt`, `app/cpy/*.cpy`
- Sections:
  - KSDS Cluster Inventory (ACCTFILE, CARDDATA, CARDXREF, CUSTDATA, DISCGRP, TRANFILE, TRANCATG, TRANTYPE, TCATBALF, TRANBKP, USRSEC)
  - Alternate Index Topology (TRANSACT-AIX1, TRANSACT-AIX2, CARDXREF-AIX1 with PATH definitions)
  - GDG Base Definitions (DALYREJS, SYSTRAN)
  - Record Layout Cross-Reference (copybooks → VSAM clusters)
  - RDS Table Mapping (KSDS → relational tables with primary/alternate key preservation)
- Diagrams: Entity-Relationship diagram of VSAM clusters and AIX paths

### 0.5.3 Documentation Files to Update Detail

- **`README.md`** — Add "Migration Analysis Documentation" section
  - New section: Link to `docs/migration-analysis/` tree
  - New section: Brief summary of proprietary dependency count and migration readiness level
  - Update: Table of contents to include migration analysis entry

### 0.5.4 Cross-Documentation Dependencies

- **Shared content/includes:** The VSAM dataset catalog (`appendices/B-vsam-dataset-catalog.md`) is referenced from both the inventory (Section 01), impact analysis (Section 03), and migration strategy (Section 04)
- **Navigation links:** Each section will include "Previous / Next" navigation to adjacent sections in the report chain
- **Source code cross-reference:** `appendices/E-source-code-cross-reference.md` serves as the master index linking all other sections back to specific file:line locations
- **External link registry:** `02-external-documentation-research.md` serves as the canonical link store; all other sections reference it rather than duplicating URLs

## 0.6 Dependency Inventory

### 0.6.1 Documentation Dependencies

The documentation deliverable is a pure Markdown-based report with Mermaid diagrams. No dedicated documentation framework is being introduced since the existing repository has none. The following tools are relevant to the documentation creation and validation process:

| Registry | Package Name | Version | Purpose |
|----------|--------------|---------|---------|
| npm | mermaid | 11.4.1 | Render Mermaid diagrams embedded in Markdown for batch flow, VSAM topology, and utility classification visuals |
| pip | markdown-it-py | 3.0.0 | Markdown validation and linting of documentation files |
| GitHub | GitHub Markdown Renderer | N/A | Native rendering of `.md` files with Mermaid support via GitHub's built-in diagram renderer |
| N/A | COBOL (IBM Enterprise COBOL) | 6.x | Source language of the analyzed codebase — no installation needed, analysis only |
| N/A | CICS Transaction Server | 5.x | Runtime environment of the analyzed application — documented from specs, not installed |
| N/A | z/OS | 2.4+ | Operating system of the mainframe environment — documented from IBM references |

### 0.6.2 Proprietary Utility Dependency Catalog (Subject of Documentation)

The following table catalogs every proprietary dependency that must be documented in the migration analysis report. These are the **subject** of the documentation, not documentation tools:

| Category | Utility Name | IBM Product | Usage Count | CardDemo Context |
|----------|-------------|-------------|-------------|------------------|
| Language Environment | `CEE3ABD` | z/OS Language Environment | 9 programs | Controlled abend in all batch programs (CBACT01C, CBACT02C, CBACT03C, CBACT04C, CBCUS01C, CBSTM03A, CBTRN01C, CBTRN02C, CBTRN03C) |
| Language Environment | `CEEDAYS` | z/OS Language Environment | 1 wrapper + 2 callers | Date-to-Lilian conversion in CSUTLDTC.cbl, called from CORPT00C.cbl and COTRN02C.cbl |
| CICS Runtime | `EXEC CICS READ` | CICS Transaction Server | 19 programs | VSAM KSDS record retrieval by primary key |
| CICS Runtime | `EXEC CICS WRITE` | CICS Transaction Server | 5+ programs | VSAM record insertion |
| CICS Runtime | `EXEC CICS REWRITE` | CICS Transaction Server | 5+ programs | VSAM record update after READ UPDATE |
| CICS Runtime | `EXEC CICS DELETE` | CICS Transaction Server | 3+ programs | VSAM record deletion |
| CICS Runtime | `EXEC CICS STARTBR/READNEXT/READPREV/ENDBR` | CICS Transaction Server | 8+ programs | VSAM browse (sequential read) operations |
| CICS Runtime | `EXEC CICS SEND MAP / RECEIVE MAP` | CICS Transaction Server | 19 programs | 3270 terminal I/O via BMS maps |
| CICS Runtime | `EXEC CICS RETURN / XCTL` | CICS Transaction Server | 19 programs | Pseudo-conversational program flow and inter-program transfer |
| CICS Runtime | `EXEC CICS ASKTIME / FORMATTIME` | CICS Transaction Server | 5+ programs | System timestamp retrieval and formatting |
| CICS Runtime | `EXEC CICS WRITEQ TD` | CICS Transaction Server | 1 program | Transient Data Queue write for batch job submission (CORPT00C) |
| CICS Runtime | `EXEC CICS HANDLE / ABEND` | CICS Transaction Server | 10+ programs | Error condition handling and controlled abend |
| JCL Utility | `IDCAMS` | z/OS DFSMS | 3+ JCL jobs | VSAM DEFINE CLUSTER, REPRO, DELETE, ALTER, LISTCAT for dataset lifecycle |
| JCL Utility | `DFSORT` (SORT) | z/OS DFSORT | 1 JCL job | Transaction record sorting/merging in COMBTRAN batch job |
| JCL Utility | `IEBGENER` | z/OS DFSMSdfp | 1 JCL job | Sequential file copy for user security data loading (DUSRSECJ) |
| JCL Utility | `IEFBR14` | z/OS MVS | 2 JCL jobs | No-op for VSAM file availability control (CLOSEFIL, OPENFIL) |
| BMS | `DFHMSD/DFHMDI/DFHMDF` | CICS Transaction Server | 17 mapsets | 3270 screen definitions for all online transactions |
| BMS | `DFHBMSCA` | CICS Transaction Server | 19 programs | BMS character attribute definitions (colors, highlighting) |
| BMS | `DFHAID` | CICS Transaction Server | 19 programs | Attention identifier definitions (PF keys, ENTER, CLEAR) |
| Batch I/O | `CBSTM03B` | Application-level | 1 program | Custom I/O subroutine for statement generation batch (not IBM proprietary but tightly coupled) |

### 0.6.3 Documentation Reference Updates

Documentation files requiring internal link configuration:

- `docs/migration-analysis/00-executive-summary.md` — Must link to all six analysis sections and five appendices
- `docs/migration-analysis/01-proprietary-utility-inventory.md` through `06-testing-validation-framework.md` — Each links to the next section and back to executive summary
- `docs/migration-analysis/appendices/*.md` — Each links back to the parent sections that reference it
- `README.md` — Updated to include a relative link to `docs/migration-analysis/00-executive-summary.md`

Link transformation rules:
- All internal links use relative Markdown paths: `[Section 01](./01-proprietary-utility-inventory.md)`
- All external links to IBM documentation use full URLs with descriptive text
- Source code citations use relative repository paths: `[CSUTLDTC.cbl](../../app/cbl/CSUTLDTC.cbl)`

## 0.7 Coverage and Quality Targets

### 0.7.1 Documentation Coverage Metrics

**Current coverage analysis:**

- Proprietary utilities documented: 0/20 (0%) — No existing migration analysis documentation exists
- IBM LE callable services documented: 0/2 (0%) — CEE3ABD and CEEDAYS have no migration docs
- CICS command types documented: 0/18 (0%) — No command-to-Java mapping exists
- JCL utilities documented: 0/4 (0%) — IDCAMS, DFSORT, IEBGENER, IEFBR14 undocumented
- BMS mapsets documented: 0/17 (0%) — No screen migration analysis exists
- VSAM datasets documented: 0/13 (0%) — No schema migration mapping exists
- Batch job chains documented: 0/4 (0%) — POSTTRAN, INTCALC, COMBTRAN, CREASTMT chains undocumented
- Source files with cross-referenced dependencies: 0/28 (0%)

**Target coverage:** 100% of all identified proprietary utilities, based on the user's explicit requirement that "we understand EXACTLY what proprietary dependencies exist."

**Coverage gaps to address:**

| Coverage Domain | Current | Target | Gap |
|----------------|---------|--------|-----|
| Language Environment services (CEE3ABD, CEEDAYS) | 0% | 100% | Full behavioral spec + Java mapping needed |
| CICS file control commands (READ, WRITE, REWRITE, DELETE, STARTBR, READNEXT, READPREV, ENDBR) | 0% | 100% | Per-command VSAM-to-JPA analysis needed |
| CICS terminal I/O (SEND MAP, RECEIVE MAP) | 0% | 100% | BMS-to-web UI mapping needed |
| CICS program control (RETURN, XCTL, HANDLE, ABEND) | 0% | 100% | Flow control-to-Spring pattern mapping needed |
| CICS system services (ASKTIME, FORMATTIME, ASSIGN, WRITEQ TD) | 0% | 100% | Service-by-service Java equivalence needed |
| JCL utilities (IDCAMS, DFSORT, IEBGENER, IEFBR14) | 0% | 100% | Full migration strategy per utility needed |
| BMS map definitions | 0% | 100% | Screen inventory with field-level analysis needed |
| VSAM dataset topology | 0% | 100% | Complete RDS/S3 mapping needed |
| External IBM documentation citations | 0% | 100% | Every utility requires at least one IBM source URL |
| Risk classification per utility | 0% | 100% | HIGH/MEDIUM/LOW for all 20 utilities |
| Java equivalent recommendation per utility | 0% | 100% | Specific library + approach for each |
| Testing strategy per utility | 0% | 100% | Validation method for behavioral parity |

### 0.7.2 Documentation Quality Criteria

**Completeness requirements:**
- Every proprietary utility has: name, classification, source file locations, parameters, documented behavior (from IBM sources), Java equivalent, risk level, and testing approach
- Every VSAM dataset has: cluster name, key structure, record layout (from copybooks), and target relational table mapping
- Every batch job has: utility dependencies listed, data flow documented, and migration approach specified
- Every CICS command has: behavioral specification, per-program usage count, and Spring/JPA equivalent pattern

**Accuracy validation:**
- All IBM documentation citations must link to valid, accessible URLs from `ibm.com/docs` or other authoritative sources
- All code references must include exact file paths relative to the repository root (e.g., `app/cbl/CSUTLDTC.cbl`)
- All Java library recommendations must specify versions and include evidence of suitability from community or official sources
- VSAM record layouts must match copybook definitions in `app/cpy/`

**Clarity standards:**
- Technical accuracy with accessible language suitable for stakeholders who may not be mainframe experts
- Progressive disclosure: executive summary → detailed inventory → per-utility deep-dive → appendices
- Consistent terminology throughout: "proprietary utility" (not "mainframe function"), "migration target" (not "replacement"), "behavioral parity" (not "equivalence")
- Every risk classification includes a one-sentence justification

**Maintainability:**
- Source citations embedded in every technical claim for traceability
- Relative links for all internal cross-references (no absolute URLs to repository)
- Standardized section structure for each utility entry enabling future additions
- Appendices structured for independent reference without requiring full document read

### 0.7.3 Example and Diagram Requirements

- **Minimum examples per utility:** 1 COBOL source snippet showing the utility invocation + 1 Java code snippet showing the recommended equivalent
- **Diagram types required:**
  - 1 batch processing chain flowchart (Mermaid `graph TD`)
  - 1 CICS transaction flow sequence diagram (Mermaid `sequenceDiagram`)
  - 1 VSAM entity-relationship diagram (Mermaid `erDiagram`)
  - 1 utility migration decision tree (Mermaid `graph LR`)
  - 1 risk assessment summary table
  - 1 online-to-batch coupling diagram (Mermaid `sequenceDiagram`)
- **Code example testing:** Java snippets must be syntactically valid and reference real library APIs (e.g., `java.time.LocalDate`, `org.springframework.data.jpa`)
- **Visual content freshness:** Diagrams generated from current LISTCAT and source code analysis; must reflect actual CardDemo state

## 0.8 Scope Boundaries

### 0.8.1 Exhaustively In Scope (with trailing patterns)

**New documentation files:**
- `docs/migration-analysis/**/*.md` — All migration analysis report sections and appendices (13 files)
- `docs/migration-analysis/00-executive-summary.md` — Stakeholder-facing summary
- `docs/migration-analysis/01-proprietary-utility-inventory.md` — Complete utility catalog
- `docs/migration-analysis/02-external-documentation-research.md` — IBM documentation research with citations
- `docs/migration-analysis/03-dependency-impact-analysis.md` — Codebase-specific impact assessment
- `docs/migration-analysis/04-migration-strategy-per-utility.md` — Java migration recommendations per utility
- `docs/migration-analysis/05-risk-assessment.md` — Risk matrix and gap analysis
- `docs/migration-analysis/06-testing-validation-framework.md` — Testing strategy for behavioral parity
- `docs/migration-analysis/appendices/A-cics-command-reference.md` — Full CICS command inventory
- `docs/migration-analysis/appendices/B-vsam-dataset-catalog.md` — VSAM topology and RDS mapping
- `docs/migration-analysis/appendices/C-batch-job-dependency-map.md` — Batch chain documentation
- `docs/migration-analysis/appendices/D-bms-screen-inventory.md` — BMS mapset catalog
- `docs/migration-analysis/appendices/E-source-code-cross-reference.md` — Master file-to-dependency index

**Documentation file updates:**
- `README.md` — Add migration analysis section with navigation links

**Analysis targets (read-only, not modified):**
- `app/cbl/*.cbl` and `app/cbl/*.CBL` — All 28 COBOL source programs
- `app/cpy/*.cpy` and `app/cpy/*.CPY` — All 28 copybooks
- `app/bms/*.bms` — All 17 BMS mapset definitions
- `app/cpy-bms/*.cpy` — All BMS-generated copybooks
- `app/catlg/LISTCAT.txt` — VSAM catalog snapshot
- `app/data/ASCII/*.txt` and `app/data/EBCDIC/*.ps` — Sample datasets for test strategy

**External research in scope:**
- IBM z/OS Language Environment Programming Reference (CEE3ABD, CEEDAYS)
- IBM z/OS DFSMS Access Method Services (IDCAMS)
- IBM z/OS DFSORT Application Programming Guide (SORT/MERGE)
- IBM z/OS DFSMSdfp Utilities (IEBGENER)
- IBM z/OS MVS JCL Reference (IEFBR14)
- IBM CICS Transaction Server Application Programming Reference (all EXEC CICS commands)
- IBM CICS BMS Mapping Reference (DFHMSD, DFHMDI, DFHMDF macros)
- Community migration case studies and best practices
- Java library documentation for recommended equivalents

### 0.8.2 Explicitly Out of Scope

- **Source code modifications:** No changes to any `.cbl`, `.CBL`, `.cpy`, `.CPY`, `.bms`, or other source files. This is a documentation-only exercise.
- **Test file modifications:** No test harnesses or test scripts will be created or modified.
- **Feature additions or code refactoring:** No COBOL-to-Java conversion code will be produced; only the analysis and strategy documentation.
- **Actual Java implementation:** The migration strategy documents recommend approaches and libraries but do not include executable Java projects.
- **Deployment configuration changes:** No AWS CloudFormation, Terraform, or infrastructure-as-code files are created.
- **DB2-related documentation:** The CardDemo application does not use DB2; it is purely VSAM-based. DB2 migration documentation is not applicable.
- **IMS-related documentation:** No IMS/DC or IMS/DB components exist in this codebase.
- **RACF security migration:** While RACF is mentioned in the README as a technology dependency, detailed security migration analysis is excluded unless it intersects with a specific utility.
- **Network/communication protocols:** SNA/LU 6.2 and other mainframe networking protocols are out of scope.
- **Performance benchmarking:** No performance comparison between mainframe and Java implementations will be produced; only qualitative complexity assessments.
- **Non-CardDemo codebases:** Analysis is strictly limited to the files in this repository.

## 0.9 Execution Parameters

### 0.9.1 Documentation-Specific Instructions

- **Documentation build command:** Not applicable — pure Markdown output rendered natively by GitHub. No static site generator is configured or required.
- **Documentation preview command:** Markdown files can be previewed locally via any Markdown renderer or by pushing to a GitHub branch for native rendering.
- **Diagram generation command:** Mermaid diagrams are embedded inline within Markdown using fenced code blocks (`mermaid`). GitHub natively renders these. For offline rendering:
  ```
  npx @mermaid-js/mermaid-cli -i docs/migration-analysis/*.md -o docs/migration-analysis/rendered/
  ```
- **Documentation deployment command:** Not applicable — documentation is committed directly to the repository under `docs/migration-analysis/`.
- **Default format:** Markdown (`.md`) with Mermaid diagram blocks
- **Citation requirement:** Every technical claim about a proprietary utility must reference either a source code file path (e.g., `app/cbl/CSUTLDTC.cbl:45`) or an external URL (e.g., IBM z/OS documentation)
- **Style guide:** No existing repository style guide. Documentation will follow:
  - GitHub Flavored Markdown (GFM) specification
  - Heading hierarchy: `#` for document title, `##` for major sections, `###` for subsections
  - Tables for structured data (inventories, mappings, risk matrices)
  - Fenced code blocks with language tags (`cobol`, `java`, `jcl`, `mermaid`)
  - Consistent use of bold for utility names and emphasis for classifications
- **Documentation validation:** 
  - Markdown linting via `markdownlint` rules (no trailing spaces, consistent heading levels, no bare URLs)
  - Link validation: all internal `[text](path)` links resolve to existing files
  - External URL validation: all IBM documentation links verified as accessible

## 0.10 Rules for Documentation

The following rules are derived from the user's explicit instructions and must be honored throughout the documentation creation process:

- **Include citations and links to all external documentation referenced** — Every utility analysis section must contain at least one URL to IBM official documentation or an authoritative community source. Stakeholders must be able to verify research independently.
- **Use web search to find documentation for each identified utility** — This is a non-negotiable requirement. Each utility in the inventory must have corresponding external research documented with search queries, findings, and source links.
- **Present findings in a structured report** that demonstrates five capabilities: (1) understanding of proprietary dependencies, (2) researched documentation per dependency, (3) concrete evidence-based migration plans, (4) testing for behavioral equivalence, and (5) transparency about risks.
- **Search queries must follow the prescribed template patterns** — The user specified five query templates: "[Utility Name] IBM mainframe documentation", "[Utility Name] COBOL to Java migration", "[Utility Name] Java equivalent", "IBM [Utility Name] specification", "[Utility Name] modernization patterns".
- **Document search findings with specificity** — Links to relevant IBM documentation, key behavioral specifications from official sources, migration patterns documented by IBM or the community, and any warnings or known issues in migration scenarios.
- **Flag utilities where behavior cannot be directly replicated** — Utilities with no clear Java equivalent or insufficient documentation must be explicitly identified with proposed mitigation strategies.
- **Provide specific Java libraries/frameworks** for each migration recommendation — Generic recommendations ("use a Java library") are insufficient. Specific libraries (e.g., "Apache Commons IO 2.15.x for file operations") with version numbers and evidence of suitability are required.
- **Testing strategy must include byte-by-byte comparison** — The user explicitly requires file content comparison at the byte level, exact database state validation, and regression test cases informed by documented utility behavior.
- **Cross-reference online documentation with actual codebase usage** — Every utility's documented behavior must be validated against its actual invocation pattern in CardDemo source files.
- **Identify HIGH RISK utilities explicitly** — Risk classification is not optional; every utility must receive a risk level, and HIGH RISK items must have dedicated mitigation narratives.
- **Maintain source code citations throughout** — Every technical claim must trace back to a specific file and, where possible, a line number range in the CardDemo repository.

## 0.11 References

### 0.11.1 Repository Files and Folders Searched

The following repository files and folders were systematically analyzed to derive the conclusions in this Agent Action Plan:

**Root Directory:**
- `README.md` — Project overview, technology inventory, dataset catalog, batch job inventory, CICS transaction list, compilation instructions, CICS resource definitions
- `CONTRIBUTING.md` — Contribution guidelines (reviewed for documentation patterns)
- `CODE_OF_CONDUCT.md` — Community guidelines (reviewed for documentation presence)
- `LICENSE` — License file

**Source Code (`app/cbl/`):**
- `CBACT01C.cbl` — Batch: Account file read, uses CEE3ABD
- `CBACT02C.cbl` — Batch: Account record processing, uses CEE3ABD
- `CBACT03C.cbl` — Batch: Account update processing, uses CEE3ABD
- `CBACT04C.cbl` — Batch: Interest calculation, uses CEE3ABD and COMPUTE
- `CBCUS01C.cbl` — Batch: Customer file processing, uses CEE3ABD
- `CBSTM03A.cbl` — Batch: Statement generation driver, uses CEE3ABD
- `CBSTM03B.CBL` — Batch: Centralized I/O subroutine for TRNX, XREF, CUST, ACCT files
- `CBTRN01C.cbl` — Batch: Transaction posting, uses CEE3ABD, VSAM INDEXED ACCESS MODE
- `CBTRN02C.cbl` — Batch: Transaction validation, uses CEE3ABD
- `CBTRN03C.cbl` — Batch: Transaction processing, uses CEE3ABD
- `CSUTLDTC.cbl` — Utility: Date conversion wrapper for CEEDAYS
- `COACTUP.cbl`, `COBIL00C.cbl`, `COCRDLI.cbl`, `COCRDSL.cbl`, `COCRDUP.cbl` — Online CICS programs with EXEC CICS commands
- `COMEN01C.cbl`, `CORPT00C.cbl`, `COSIGN00.cbl` — Online CICS programs, CORPT00C uses WRITEQ TD
- `COTRN00C.cbl`, `COTRN01C.cbl`, `COTRN02C.cbl`, `COTRN04C.cbl`, `COTRN05C.cbl` — Online CICS transaction programs
- `COADM01C.cbl`, `COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` — Online CICS admin and user management programs
- `COACTVWC.cbl` — Online CICS account view program

**Copybooks (`app/cpy/`):**
- 28 copybook files containing record layouts (CUSTREC, CVACT*, COTTL*, COCOM*, etc.) analyzed for data structure definitions relevant to VSAM migration

**BMS Maps (`app/bms/`):**
- 17 BMS mapset files (COACTUP.bms, COBIL00.bms, COCRDLI.bms, COCRDSL.bms, COCRDUP.bms, COMEN01.bms, CORPT00.bms, COSIGN0.bms, COTRN00.bms, COTRN01.bms, COTRN02.bms, COTRN04.bms, COTRN05.bms, COADM01.bms, COUSR00.bms, COUSR01.bms, COUSR02.bms) analyzed for screen definitions

**Catalog Data (`app/catlg/`):**
- `LISTCAT.txt` — Complete IDCAMS LISTCAT output with VSAM cluster attributes, AIX definitions, GDG base entries, and NONVSAM dataset references

**Sample Data:**
- `app/data/ASCII/` — ASCII-encoded test files for ACCTDATA, CARDDATA, CUSTDATA, DISCGRP, TRANSACT, TRANCATG, TRANTYPE, USRSEC
- `app/data/EBCDIC/` — EBCDIC-encoded physical sequential files (`.ps`) for the same datasets

### 0.11.2 Technical Specification Sections Reviewed

The following sections of the existing Technical Specification were retrieved and analyzed:

- **Section 1.1 — Executive Summary**: Provided the overall project context, core business problem (mainframe modernization), and stakeholder identification
- **Section 3.2 — Programming Languages**: Confirmed COBOL as the primary language with JCL for batch orchestration; provided specific program file listings
- **Section 3.3 — Frameworks and Runtime Environments**: Documented CICS Transaction Server details, BMS mapping mechanisms, and IBM Language Environment (LE) services including CEEDAYS and CEE3ABD
- **Section 3.4 — Data Management and Storage**: Provided VSAM KSDS architecture details, EBCDIC encoding specifications, file status code handling, and date format conventions
- **Section 3.5 — System Software and Utilities**: Enumerated z/OS utilities including IDCAMS, DFSORT, IEBGENER, IEFBR14, DFHCSDUP, and their roles in the CardDemo infrastructure
- **Section 3.7 — AWS Mainframe Modernization Integration**: Documented the target migration platform (AWS), Rehost (Micro Focus Enterprise Server) and Refactor (Blu Age) strategies, and data migration approaches (VSAM → RDS, Sequential → S3)

### 0.11.3 External Sources Consulted via Web Search

The following external resources were discovered and consulted during the documentation research phase:

**IBM Official Documentation:**
- IBM z/OS 2.4 Language Environment Programming Reference — CEE3ABD: `https://www.ibm.com/docs/en/zos/2.4.0?topic=services-cee3abdterminate-enclave-abend`
- IBM z/OS CEEDAYS API (Convert Date to Lilian Format): `https://www.ibm.com/docs/en/zos/2.1.0?topic=services-ceedays-convert-date-lilian-format`
- IBM i Convert Date to Lilian Format (CEEDAYS) API: `https://www.ibm.com/docs/api/v1/content/ssw_ibm_i_74/apis/CEEDAYS.htm`
- IBM ILE CL Usage of CEE APIs (CEEDAYS, CEEDATE, CEEDYWK): `https://www.ibm.com/support/pages/ile-cl-usage-cee-apis-ceedays-ceedate-ceedywk`
- IBM z/OS 2.5 Language Environment Programming Reference (SA38-0683): `https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/ceea300_v2r5.pdf`
- IBM z/OS 2.5 DFSORT Application Programming Guide (SC23-6878): `https://www.ibm.com/docs/en/SSLTBW_2.5.0/pdf/icea100_v2r5.pdf`
- IBM DFSORT example: `https://www.ibm.com/docs/SSLTBW_2.4.0/com.ibm.zos.v2r4.icea100/ice2ca_DFSORT_example.htm`
- IBM SORT control statement: `https://www.ibm.com/docs/en/zos/2.1.0?topic=statements-sort-control-statement`
- IBM IEBGENER utility: `https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEBGENER.htm`
- IBM IEFBR14 utility: `https://www.ibm.com/docs/zosbasics/com.ibm.zos.zdatamgmt/zsysprogc_utilities_IEFBR14.htm`
- IBM Migration Utility Explorer: `https://www.ibm.com/support/pages/ibm-migration-utility-explorer`
- IBM Example of COBOL Calling CEEDAYS/CEEDATE/CEEDYWK: `https://www.ibm.com/support/pages/example-cobol-calling-apis-ceedays-ceedate-and-ceedywk`

**Community and Tutorial Sources:**
- IDCAMS Tutorial (mainframestechhelp.com): `https://www.mainframestechhelp.com/utilities/idcams/`
- JCL IDCAMS Utility (ibmmainframer.com): `https://www.ibmmainframer.com/jcl-tutorial/jcl-idcams-utility/`
- DFSORT Tutorial (mainframestechhelp.com): `https://www.mainframestechhelp.com/utilities/sort/`
- JCL DFSORT Overview (ibmmainframer.com): `https://www.ibmmainframer.com/jcl-tutorial/jcl-sort-utility/`
- JCL Utility Programs (ibmmainframer.com): `https://www.ibmmainframer.com/jcl-tutorial/jcl-utility-programs/`
- IEBGENER Utility (mainframestechhelp.com): `https://www.mainframestechhelp.com/utilities/iebgener/`
- IEFBR14 Utility (mainframestechhelp.com): `https://www.mainframestechhelp.com/utilities/iefbr14/`
- IBM Mainframe Utility Programs (Encyclopedia MDPI): `https://encyclopedia.pub/entry/31139`
- Abend-AID in Language Environment (BMC Documentation): `https://docs.bmc.com/xwiki/bin/view/Mainframe/DevX/BMC-AMI-DevX-Abend-AID/`

**Migration Case Studies and Tools:**
- ING Bank COBOL-to-Java Migration Case Study (SoftwareMining): `https://softwaremining.com/news/ING-Bank-Mainframe-Modernization.jsp`
- Heirloom Computing Standard Utility Guide: `https://support.heirloom.cc/hc/en-us/articles/212578706-Standard-Utility-Programmers-Guide`
- IBM Migration and Modernization Services (Digital Marketplace): `https://www.applytosupply.digitalmarketplace.service.gov.uk/g-cloud/services/752697492878620`
- VerraDyne IBM Mainframe Migration: `https://verradyne.com/ibm-mainframe-migration/`
- Mainframe Sort/Merge (Wikipedia): `https://en.wikipedia.org/wiki/Mainframe_sort_merge`
- IEFBR14 (Wikipedia): `https://en.wikipedia.org/wiki/IEFBR14`

### 0.11.4 Attachments

No attachments were provided for this project. All analysis is based on the repository contents and web-sourced external documentation.

