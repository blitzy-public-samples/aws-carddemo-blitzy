# Technical Specification

# 0. Agent Action Plan

## 0.1 Intent Clarification

### 0.1.1 Core Documentation Objective

Based on the provided requirements, the Blitzy platform understands that the documentation objective is to **create new documentation** that provides a comprehensive proprietary utilities assessment for the AWS CardDemo COBOL mainframe application. This documentation will serve as a structured migration analysis report identifying, cataloging, and mapping all proprietary mainframe utility dependencies to their Java equivalents, supporting stakeholder confidence in a mainframe-to-cloud migration.

**Documentation Type:** Migration Analysis Report / Technical Specification Documentation

**Request Category:** Create new documentation

**Documentation Requirements with Enhanced Clarity:**

- **Proprietary Utility Inventory:** Create an exhaustive catalog of all proprietary mainframe utility calls found across the 28 COBOL programs, 28 copybooks, and 17 BMS map sources, classifying each by type (sorting, file handling, data transformation, database operations, transaction processing, screen I/O, date/time services, abend handling)
- **Dependency Impact Analysis:** For each identified proprietary utility, document its contextual purpose within the CardDemo codebase, assess Java replication complexity, identify specific Java/open-source library equivalents, and flag any utilities with no direct behavioral equivalent
- **Migration Strategy per Utility:** Provide recommended approaches (direct library replacement, custom implementation, or service wrapper) with specific Java libraries/frameworks, behavioral equivalence strategies, and targeted testing approaches
- **Risk Assessment:** Identify HIGH RISK utilities where behavioral differences are most likely, document utilities without clear migration paths, and highlight dependencies requiring custom development or vendor engagement
- **Testing & Validation Framework:** Define byte-by-byte I/O comparison strategies, database state validation procedures, regression test data points, and test cases for validating utility replacement behavioral parity

### 0.1.2 Special Instructions and Constraints

- The analysis must demonstrate to stakeholders comprehensive understanding of ALL proprietary dependencies
- Findings must be presented in a structured report format with concrete plans for each utility
- Transparency about risks and unknowns is explicitly required
- The output must show: (1) exact proprietary dependencies, (2) concrete plan per dependency, (3) testing strategy for behavioral equivalence, (4) honest risk disclosure
- No code modifications are requested — this is purely a documentation and analysis exercise
- The analysis covers the full CardDemo codebase: batch programs, CICS online programs, copybooks, BMS maps, JCL jobs, and the IDCAMS catalog snapshot

### 0.1.3 Technical Interpretation

These documentation requirements translate to the following technical documentation strategy:

- To document the **Proprietary Utility Inventory**, we will **create** a new documentation file cataloging every CALL statement (CEE3ABD, CEEDAYS, CBSTM03B, CSUTLDTC), every EXEC CICS command category (READ, WRITE, SEND MAP, XCTL, STARTBR, WRITEQ TD, ASKTIME, FORMATTIME, ASSIGN, ABEND, HANDLE ABEND), every z/OS utility program reference (IDCAMS, IEBGENER, IEFBR14, SORT), and every IBM-proprietary copybook (DFHBMSCA, DFHAID) found across the `app/cbl/`, `app/cpy/`, `app/bms/`, and `app/catlg/` directories
- To document the **Dependency Impact Analysis**, we will **create** per-utility documentation cross-referencing each utility to its consuming programs with line-level citations, assessing complexity on a scale (Low/Medium/High/Very High), and mapping to specific Java replacement candidates
- To document the **Migration Strategy per Utility**, we will **create** detailed migration playbooks for each utility category with specific Java library versions, implementation patterns, and behavioral equivalence verification methods
- To document the **Risk Assessment**, we will **create** a risk matrix classifying each utility by migration difficulty, behavioral fidelity risk, and availability of Java equivalents
- To document the **Testing & Validation Framework**, we will **create** a comprehensive testing strategy document covering byte-level file comparison, VSAM-to-RDBMS state validation, and regression test design for each replaced utility

### 0.1.4 Inferred Documentation Needs

Based on code analysis:
- The 9 batch programs (CBACT01C–CBACT04C, CBTRN01C–CBTRN03C, CBCUS01C, CBSTM03A) all use `CALL 'CEE3ABD'` for abend handling and VSAM file I/O with status code checking — requiring consolidated documentation of the LE runtime dependency and VSAM access method replacement strategy
- The CSUTLDTC utility wraps the IBM Language Environment `CEEDAYS` service for date validation (Lillian day conversion) and is called by CORPT00C, COTRN02C, and the CSUTLDPY copybook — requiring documentation of date handling migration strategy
- CBSTM03A calls CBSTM03B as a centralized I/O subroutine 13 times — requiring documentation of the subroutine linkage pattern and its Java equivalent (service/DAO pattern)
- CORPT00C generates JCL dynamically and writes to TDQ 'JOBS' for asynchronous batch job submission — requiring documentation of the JCL/TDQ replacement strategy
- The COMBTRAN batch job uses the z/OS SORT utility for file merging — requiring documentation of sort/merge migration to Java
- 17 BMS map sources define 3270 screen contracts consumed by CICS programs via DFHMSD/DFHMDI/DFHMDF macros — requiring documentation of the presentation layer proprietary dependency

Based on structure:
- The application spans both online CICS programs (16 programs) and batch COBOL programs (12 programs), requiring separate migration strategy documentation for each execution context
- Copybook dependencies (DFHBMSCA, DFHAID, COCOM01Y) form compile-time contracts that must be documented as interface dependencies

Based on dependencies:
- VSAM KSDS file operations (10+ datasets) with FILE STATUS checking pattern is used universally — requiring consolidated documentation of VSAM-to-RDBMS or VSAM-to-file-system migration
- The COMMAREA-based state management pattern (COCOM01Y.cpy, 1024 bytes) is a CICS-specific inter-program communication mechanism requiring migration documentation

## 0.2 Documentation Discovery and Analysis

### 0.2.1 Existing Documentation Infrastructure Assessment

Repository analysis reveals a minimal documentation infrastructure limited to a single `README.md` and standard community files, with no dedicated documentation framework or generator in place.

**Documentation Files Found:**
- `README.md` — Primary project documentation covering application overview, technologies (COBOL, CICS, VSAM, JCL, RACF, DFSMS), installation, CICS resource definitions (DFHCSDUP), batch job descriptions (IDCAMS, IEBGENER, IEFBR14, SORT), application inventory table, and roadmap
- `CONTRIBUTING.md` — Contribution guidelines
- `CODE_OF_CONDUCT.md` — Community standards
- `LICENSE` — License terms

**Documentation Generator:** None detected. No mkdocs.yml, docusaurus.config.js, sphinx conf.py, or .readthedocs.yml found in the repository.

**API Documentation Tools:** None in use. No JSDoc, Sphinx, or Godoc configurations found.

**Diagram Tools:** The `diagrams/` folder exists but was empty at time of analysis. Mermaid will be used for all new diagrams in the migration analysis documentation.

**Documentation Hosting/Deployment:** No documentation hosting setup detected. A `Makefile` and `.github/workflows/deploy-job.yml` exist but are oriented toward application deployment, not documentation.

**Dependency Manifests Found:**
- `requirements.txt` — Python dependencies (likely for CI/testing tooling)
- `.pre-commit-config.yaml` — Pre-commit hooks configuration

### 0.2.2 Repository Code Analysis for Documentation

**Search Patterns Used for Code to Document:**

| Search Pattern | Target | Files Found |
|---|---|---|
| `CALL ` in `app/cbl/*.cbl` | Proprietary runtime calls | CEE3ABD (9 files), CEEDAYS (1 file), CBSTM03B (1 file), CSUTLDTC (2 files) |
| `EXEC CICS` in `app/cbl/*.cbl` | CICS API usage | 16 online programs with 100+ CICS API calls |
| `ASSIGN TO` in `app/cbl/*.cbl` | VSAM/file DD names | ACCTFILE, CARDFILE, XREFFILE, CUSTFILE, TRANSACT, DALYTRAN, TCATBAL, TRNXFILE, DISCGRP |
| `FUNCTION ` in `app/cbl/*.cbl` | COBOL intrinsic functions | CURRENT-DATE, MOD, TEST-NUMVAL-C, NUMVAL-C, UPPER-CASE, TRIM, INTEGER-OF-DATE |
| `COPY ` in `app/cbl/*.cbl` | Copybook dependencies | DFHAID, DFHBMSCA, COCOM01Y, COTTL01Y, CSDAT01Y, CSMSG01Y, CSUSR01Y, plus all screen/record copybooks |
| `FILE-STATUS` in `app/cbl/*.cbl` | VSAM file status checking | Universal across all batch and CICS programs |
| Content of `app/catlg/LISTCAT.txt` | VSAM cluster definitions | Full IDCAMS LISTCAT output for AWS.M2.CARDDEMO datasets |
| Content of `samples/jcl/` | JCL batch job definitions | BATCMP, BMSCMP, CICCMP (compile JCL) |
| Content of `samples/proc/` | JCL PROCs | Compilation procedures |

**Key Directories Examined:**
- `app/cbl/` — 28 COBOL source files (12 batch + 16 online CICS programs)
- `app/cpy/` — 28 copybooks (record layouts, screen maps, utility areas)
- `app/bms/` — 17 BMS map source files (3270 screen definitions)
- `app/catlg/` — 1 LISTCAT output file (VSAM catalog forensic snapshot)
- `app/data/` — 8 ASCII test data fixture files
- `samples/jcl/` — 3 JCL compile jobs
- `samples/proc/` — Compilation procedures
- `diagrams/` — Empty diagrams directory

**Existing Related Documentation from Tech Spec:**
- Section 3.5 "System Software and Utilities" catalogs z/OS OS, JES, Catalog Management, DFSMS, RACF, IEBGENER, IEFBR14, SORT, DFHCSDUP, CEDA, CEMT, CECI
- Section 3.4 "Data Management and Storage" documents VSAM types (KSDS, ESDS), IDCAMS commands, sequential datasets, and GDGs
- Section 3.7 "AWS Mainframe Modernization Integration" describes Rehost and Refactor migration strategies
- Section 5.1 "High-Level Architecture" describes the 3-tier pseudo-conversational architecture

### 0.2.3 Web Search Research Conducted

- **COBOL to Java migration patterns and performance** — IBM watsonx Code Assistant for Z and migration tool ecosystem reviewed for context on conversion approaches and the availability of Java equivalents for CICS/VSAM/LE services
- **Java equivalent IBM CEEDAYS date conversion** — Researched for mapping CEEDAYS Lillian date conversion to Java `java.time` API equivalents
- **Mainframe utility migration best practices** — General approach confirmed: phased migration, functional equivalence testing, service wrapper patterns for CICS-like behavior

## 0.3 Documentation Scope Analysis

### 0.3.1 Code-to-Documentation Mapping

**Category 1: IBM Language Environment (LE) Runtime Services**

- **Utility: CEE3ABD (Abend Handler)**
  - Programs using it: CBACT01C, CBACT02C, CBACT03C, CBACT04C, CBCUS01C, CBSTM03A, CBTRN01C, CBTRN02C, CBTRN03C (9 batch programs)
  - Current documentation: Missing — no migration guidance exists
  - Documentation needed: Utility behavior specification, Java equivalent (`System.exit()`, custom exception framework), migration playbook
- **Utility: CEEDAYS (Lillian Date Conversion)**
  - Programs using it: CSUTLDTC.cbl (wrapper), consumed via CSUTLDPY.cpy by CORPT00C, COTRN02C
  - Current documentation: Missing — no Java mapping exists
  - Documentation needed: Date format specification, Java `java.time.temporal.JulianFields` / `ChronoUnit` mapping, behavioral comparison

**Category 2: CICS Transaction Processing API**

- **Module: File Control (READ/WRITE/REWRITE/DELETE/STARTBR/READNEXT/READPREV/ENDBR)**
  - Programs: COACTVWC, COACTUPC, COBIL00C, COCRDLIC, COCRDSLC, CORPT00C, COSGN00C, COTRN00C, COTRN01C, COTRN02C, COUSR01C, COUSR02C, COUSR03C
  - Endpoints: ACCTDAT, CARDDAT, XREFDAT, CRDTRN, USRSEC, CARDAIX (VSAM files via CICS FCT)
  - Current documentation: Partially covered in Section 3.4 (data management), but no migration guidance
  - Documentation needed: Per-file access pattern catalog, Java JDBC/JPA equivalent mapping, connection pool strategy, transaction boundary documentation
- **Module: Program Control (XCTL/RETURN/LINK)**
  - Programs: COMEN01C (menu router), COMEN02C, all screen programs via RETURN TRANSID
  - Current documentation: Partially covered in Section 5.1 (architecture), no migration strategy
  - Documentation needed: Program navigation flow, Java servlet/controller routing equivalent, state passing mechanism migration
- **Module: Terminal Control (SEND MAP/RECEIVE MAP/SEND TEXT)**
  - Programs: All 16 CICS programs
  - BMS Maps: 17 BMS sources defining screen contracts
  - Current documentation: Section 7.x covers BMS maps, no migration docs
  - Documentation needed: BMS-to-HTML/REST migration, screen field mapping, attribute set (DFHBMSCA) replacement strategy
- **Module: System Services (ASSIGN/ASKTIME/FORMATTIME)**
  - Programs: COSGN00C (ASSIGN APPLID/SYSID), COBIL00C (ASKTIME/FORMATTIME), CORPT00C
  - Current documentation: Missing
  - Documentation needed: Java `InetAddress`/`System.getProperty()` for ASSIGN, `java.time.LocalDateTime` for ASKTIME/FORMATTIME
- **Module: Transient Data Queue (WRITEQ TD)**
  - Programs: CORPT00C (writes JCL to TDQ 'JOBS' for internal reader submission)
  - Current documentation: Missing
  - Documentation needed: JCL submission via TDQ replacement (message queue, Spring Batch trigger, cloud batch API)

**Category 3: z/OS Batch Utilities**

- **Utility: IDCAMS (Access Method Services)**
  - Referenced in: `app/catlg/LISTCAT.txt`, `README.md` (DEFVSAM job)
  - Current documentation: Partially covered in Section 3.4
  - Documentation needed: DEFINE CLUSTER/REPRO/DELETE to DDL/DML mapping, schema creation scripting strategy
- **Utility: SORT (DFSORT/SyncSort)**
  - Referenced in: README.md (COMBTRAN job)
  - Current documentation: Section 3.5 mentions it
  - Documentation needed: Sort control statement to Java `Collections.sort()` / file-based merge sort / Apache Commons CSV mapping
- **Utility: IEBGENER (Sequential Copy)**
  - Referenced in: README.md (DUSRSECJ job)
  - Current documentation: Section 3.5 mentions it
  - Documentation needed: Java `Files.copy()` / `BufferedReader`/`BufferedWriter` equivalent, fixed-width record handling
- **Utility: IEFBR14 (Null Program)**
  - Referenced in: README.md (CLOSEFIL/OPENFIL jobs)
  - Current documentation: Section 3.5 mentions it
  - Documentation needed: DD statement allocation/deallocation to file system operations mapping

**Category 4: BMS Map Macros (DFHMSD/DFHMDI/DFHMDF)**

- **Source files:** 17 BMS sources in `app/bms/`
- Current documentation: Section 7.x covers screen structure
- Documentation needed: Macro-to-HTML/JSON mapping, screen field attribute set migration, validation rule extraction

**Category 5: COBOL Intrinsic Functions (Non-Proprietary but Migration-Relevant)**

- **Functions:** CURRENT-DATE, MOD, TEST-NUMVAL-C, NUMVAL-C, UPPER-CASE, TRIM, INTEGER-OF-DATE
- Programs: Distributed across batch and CICS programs
- Documentation needed: Java equivalent mapping table (standard library mappings)

### 0.3.2 Documentation Gap Analysis

Given the requirements and repository analysis, documentation gaps include:

**Undocumented Proprietary APIs:**
- No existing documentation maps CICS API commands to Java equivalents
- No documentation exists for LE runtime service migration (CEE3ABD, CEEDAYS)
- No documentation exists for TDQ-based JCL submission replacement strategy
- No VSAM-to-RDBMS migration mapping exists at the file/field level

**Missing Migration Guides:**
- No utility-by-utility migration playbook exists
- No behavioral equivalence testing strategy has been documented
- No risk assessment matrix exists for proprietary dependencies

**Incomplete Architecture Documentation:**
- Existing Section 3.7 mentions AWS Mainframe Modernization at a high level but lacks utility-specific migration detail
- Existing Section 3.5 catalogs utilities but provides no Java equivalent mapping
- No cross-reference exists between utility usage and consuming program inventory

**Outdated or Shallow Documentation:**
- README.md lists batch jobs (DEFVSAM, COMBTRAN, DUSRSECJ, CLOSEFIL, OPENFIL, CREASTMT) but does not detail their proprietary utility dependencies or migration path
- LISTCAT.txt provides a raw VSAM catalog snapshot but no interpreted documentation of cluster attributes and their RDBMS equivalents

## 0.4 Documentation Implementation Design

### 0.4.1 Documentation Structure Planning

The migration analysis documentation will be organized as a consolidated report structure with logical sections corresponding to the five analysis areas requested by the user:

```
docs/
├── migration-analysis/
│   ├── 00-executive-summary.md          (overview, key findings, stakeholder brief)
│   ├── 01-proprietary-utility-inventory.md (exhaustive catalog of all proprietary utilities)
│   ├── 02-dependency-impact-analysis.md   (per-utility impact assessment with Java equivalents)
│   ├── 03-migration-strategy.md           (per-utility migration playbook with libraries)
│   ├── 04-risk-assessment.md              (risk matrix, unknowns, vendor engagement needs)
│   ├── 05-testing-validation-framework.md (behavioral parity testing strategy)
│   └── diagrams/
│       ├── utility-dependency-map.md      (Mermaid: program-to-utility dependency graph)
│       ├── cics-command-flow.md           (Mermaid: CICS API migration flow)
│       ├── vsam-to-rdbms-mapping.md       (Mermaid: data layer transformation)
│       └── batch-job-migration-flow.md    (Mermaid: batch utility replacement flow)
├── README.md                              (UPDATE: add link to migration analysis)
```

### 0.4.2 Content Generation Strategy

**Information Extraction Approach:**
- Extract all CALL statements and EXEC CICS commands from `app/cbl/*.cbl` using grep/static analysis against source files
- Catalog VSAM dataset definitions from `app/catlg/LISTCAT.txt` to map cluster attributes (key position, record size, CI size) to RDBMS schema parameters
- Extract BMS field definitions from `app/bms/*.bms` to document DFHMSD/DFHMDI/DFHMDF macro dependencies
- Cross-reference copybook definitions in `app/cpy/*.cpy` with consuming programs to map DFHBMSCA/DFHAID dependencies
- Parse README.md batch job descriptions to catalog JCL utility references (IDCAMS, SORT, IEBGENER, IEFBR14)
- Generate examples by analyzing code patterns in CSUTLDTC.cbl (CEEDAYS wrapper), CBSTM03B.CBL (I/O subroutine), and CORPT00C.cbl (TDQ JCL submission)

**Documentation Standards:**
- Markdown formatting with proper headers (# for document titles, ## for major sections, ### for subsections)
- Mermaid diagram integration for dependency graphs, migration flows, and architecture mappings
- Code examples in dual-column format: COBOL source (left) → Java equivalent (right)
- Source citations as inline references in format: `Source: app/cbl/FILENAME.cbl:LineNumber`
- Tables for parameter descriptions, utility catalogs, risk matrices, and library mappings
- Consistent terminology: "proprietary utility" for IBM-specific, "Java equivalent" for replacements, "behavioral parity" for functional equivalence

### 0.4.3 Diagram and Visual Strategy

**Mermaid Diagrams to Create:**

- **Utility Dependency Map** — Graph showing which programs depend on which proprietary utilities, organized by utility category (LE Runtime, CICS API, Batch Utilities, BMS Macros)
- **CICS Command Migration Flow** — Sequence diagram showing the migration path from CICS EXEC commands to Java Spring equivalents
- **VSAM-to-RDBMS Mapping** — Entity-relationship diagram mapping VSAM KSDS clusters (ACCTDAT, CARDDAT, etc.) to relational database tables with key/index correspondence
- **Batch Job Migration Flow** — Flowchart showing each z/OS utility (IDCAMS, SORT, IEBGENER, IEFBR14) and its Java/cloud replacement path
- **Risk Heat Map** — Table-based visual showing utility categories by migration complexity and behavioral risk
- **Testing Validation Pipeline** — Sequence diagram showing the byte-by-byte comparison and state validation workflow

## 0.5 Documentation File Transformation Mapping

### 0.5.1 File-by-File Documentation Plan

| Target Documentation File | Transformation | Source Code/Docs | Content/Changes |
|---|---|---|---|
| docs/migration-analysis/00-executive-summary.md | CREATE | app/cbl/*.cbl, app/cpy/*.cpy, app/bms/*.bms, app/catlg/LISTCAT.txt, README.md | Executive overview of all proprietary utilities found, key migration risks, stakeholder-ready summary of the complete assessment |
| docs/migration-analysis/01-proprietary-utility-inventory.md | CREATE | app/cbl/CBACT01C.cbl, app/cbl/CBACT02C.cbl, app/cbl/CBACT03C.cbl, app/cbl/CBACT04C.cbl, app/cbl/CBCUS01C.cbl, app/cbl/CBSTM03A.cbl, app/cbl/CBSTM03B.CBL, app/cbl/CBTRN01C.cbl, app/cbl/CBTRN02C.cbl, app/cbl/CBTRN03C.cbl, app/cbl/CSUTLDTC.cbl, app/cbl/CO*.cbl, app/cpy/DFHAID.cpy, app/cpy/DFHBMSCA.cpy, app/bms/*.bms, app/catlg/LISTCAT.txt, README.md | Exhaustive catalog of all 11 proprietary utility types: CEE3ABD, CEEDAYS, CICS File Control, CICS Program Control, CICS Terminal Control, CICS System Services, CICS TDQ, IDCAMS, SORT, IEBGENER, IEFBR14, classified by type with parameters and consuming programs |
| docs/migration-analysis/02-dependency-impact-analysis.md | CREATE | app/cbl/CSUTLDTC.cbl, app/cbl/CBSTM03B.CBL, app/cbl/CORPT00C.cbl, app/cbl/COSGN00C.cbl, app/cbl/COBIL00C.cbl, app/cbl/COMEN01C.cbl, app/cbl/CBACT04C.cbl, app/cbl/CBTRN02C.cbl, app/cpy/CSUTLDPY.cpy, app/cpy/CSSETATY.cpy, app/cpy/CSSTRPFY.cpy, app/cpy/COCOM01Y.cpy | Per-utility impact analysis with contextual description, complexity assessment (Low/Medium/High/Very High), Java equivalent mapping (java.time, Spring Framework, JDBC, Apache Commons), and flags for utilities without direct Java equivalent |
| docs/migration-analysis/03-migration-strategy.md | CREATE | app/cbl/*.cbl, app/cpy/*.cpy, app/bms/*.bms, app/catlg/LISTCAT.txt | Per-utility migration playbook: recommended approach (replacement/custom/wrapper), specific Java libraries with versions, behavioral equivalence strategy, short code mapping examples (COBOL → Java) |
| docs/migration-analysis/04-risk-assessment.md | CREATE | app/cbl/CORPT00C.cbl (TDQ/JCL), app/cbl/CSUTLDTC.cbl (CEEDAYS), app/cbl/CBSTM03B.CBL (VSAM I/O), app/bms/*.bms (BMS), app/catlg/LISTCAT.txt (VSAM clusters) | Risk matrix with HIGH/MEDIUM/LOW per utility, unknowns disclosure, vendor engagement recommendations, custom development flagging |
| docs/migration-analysis/05-testing-validation-framework.md | CREATE | app/cbl/CBACT04C.cbl, app/cbl/CBTRN02C.cbl, app/cbl/CBSTM03A.cbl, app/data/*.txt | Testing strategy: byte-by-byte file comparison, database state validation, regression test data points from app/data/ fixtures, specific test cases per utility replacement, validation pipeline design |
| docs/migration-analysis/diagrams/utility-dependency-map.md | CREATE | app/cbl/*.cbl (all CALL/EXEC CICS references) | Mermaid graph diagram mapping all 28 COBOL programs to their proprietary utility dependencies |
| docs/migration-analysis/diagrams/cics-command-flow.md | CREATE | app/cbl/CO*.cbl (CICS programs) | Mermaid sequence diagram showing CICS command categories and their Spring/Java migration paths |
| docs/migration-analysis/diagrams/vsam-to-rdbms-mapping.md | CREATE | app/catlg/LISTCAT.txt, app/cpy/CVACT01Y.cpy, app/cpy/CVACT02Y.cpy, app/cpy/CVACT03Y.cpy, app/cpy/CVTRA05Y.cpy, app/cpy/CUSTREC.cpy, app/cpy/CVTRA01Y.cpy | Mermaid ER diagram mapping VSAM KSDS clusters to relational tables with key/column correspondence |
| docs/migration-analysis/diagrams/batch-job-migration-flow.md | CREATE | README.md (batch job descriptions), app/cbl/CBACT*.cbl, app/cbl/CBTRN*.cbl, app/cbl/CBSTM03A.cbl, app/cbl/CBCUS01C.cbl | Mermaid flowchart showing each batch job's utility chain and the Java/cloud replacement pipeline |
| README.md | UPDATE | README.md | Add new section linking to migration analysis documentation under the existing structure |

### 0.5.2 New Documentation Files Detail

```
File: docs/migration-analysis/00-executive-summary.md
Type: Executive Summary / Stakeholder Report
Source Code: All app/cbl/*.cbl, app/cpy/*.cpy, app/bms/*.bms, app/catlg/LISTCAT.txt
Sections:
    - Overview (purpose of assessment, scope of analysis)
    - Key Findings Summary (utility count by category, highest risk items)
    - Migration Readiness Score (aggregate complexity assessment)
    - Critical Path Items (utilities requiring immediate attention)
    - Stakeholder Recommendations (phased approach, resource needs)
Diagrams:
    - High-level utility dependency summary chart
Key Citations: All source files listed above
```

```
File: docs/migration-analysis/01-proprietary-utility-inventory.md
Type: Technical Catalog / Inventory
Source Code: app/cbl/*.cbl, app/cpy/DFHAID.cpy, app/cpy/DFHBMSCA.cpy, app/bms/*.bms
Sections:
    - IBM LE Runtime Services (CEE3ABD from 9 batch programs, CEEDAYS from CSUTLDTC.cbl)
    - CICS API Commands (File Control, Program Control, Terminal Control, System Services, TDQ from 16 CICS programs)
    - z/OS Batch Utilities (IDCAMS, SORT, IEBGENER, IEFBR14 from README.md batch jobs)
    - BMS Map Macros (DFHMSD/DFHMDI/DFHMDF from 17 BMS sources)
    - IBM Copybooks (DFHBMSCA, DFHAID from app/cpy/)
    - COBOL Intrinsic Functions (CURRENT-DATE, MOD, etc. — migration-relevant)
    - Program-to-Utility Cross-Reference Matrix
Diagrams:
    - Utility dependency map (Mermaid graph)
Key Citations: app/cbl/CBACT01C.cbl, app/cbl/CSUTLDTC.cbl, app/cbl/CORPT00C.cbl, app/cpy/DFHAID.cpy
```

```
File: docs/migration-analysis/02-dependency-impact-analysis.md
Type: Impact Assessment
Source Code: app/cbl/CSUTLDTC.cbl, app/cbl/CBSTM03B.CBL, app/cbl/CORPT00C.cbl, app/cbl/COSGN00C.cbl
Sections:
    - Per-Utility Impact Cards (contextual purpose, complexity, Java equivalent, behavioral gaps)
    - Complexity Scoring Methodology (Low/Medium/High/Very High criteria)
    - Java Equivalent Mapping Table (utility → library → class/method)
    - Behavioral Gap Analysis (utilities with no direct Java parity)
Diagrams:
    - Impact heat map table
Key Citations: app/cbl/CSUTLDTC.cbl:36-55 (CEEDAYS call), app/cbl/CORPT00C.cbl:517 (WRITEQ TD)
```

```
File: docs/migration-analysis/03-migration-strategy.md
Type: Migration Playbook
Source Code: All app/cbl/*.cbl, app/bms/*.bms, app/catlg/LISTCAT.txt
Sections:
    - Strategy per LE Runtime Service (CEE3ABD → Java exception framework, CEEDAYS → java.time)
    - Strategy per CICS Command Category (File Control → Spring Data/JDBC, Program Control → Spring MVC routing, Terminal Control → REST API/HTML, System Services → Java system APIs, TDQ → JMS/Spring Batch)
    - Strategy per Batch Utility (IDCAMS → DDL scripts, SORT → Java sort/merge, IEBGENER → Java file copy, IEFBR14 → file system ops)
    - Strategy per BMS Map (DFHMSD → HTML form, DFHMDI → page section, DFHMDF → input field)
    - Code Mapping Examples (COBOL snippet → Java snippet for each utility)
Diagrams:
    - CICS command migration flow sequence diagram
    - Batch job migration flowchart
Key Citations: app/cbl/CSUTLDTC.cbl, app/cbl/CBSTM03B.CBL, app/bms/COACTUP.bms
```

```
File: docs/migration-analysis/04-risk-assessment.md
Type: Risk Matrix / Assessment
Source Code: app/cbl/CORPT00C.cbl, app/cbl/CSUTLDTC.cbl, app/bms/*.bms
Sections:
    - Risk Classification Methodology (impact × likelihood × complexity)
    - HIGH RISK Utilities (TDQ/JCL submission, BMS 3270 screens, VSAM browse semantics, CEEDAYS edge cases)
    - MEDIUM RISK Utilities (CICS File Control transaction boundaries, SORT field-level behavior, CEE3ABD dump vs. exception semantics)
    - LOW RISK Utilities (IEBGENER, IEFBR14, COBOL intrinsic functions)
    - Unknowns and Gaps (Db2 planned but not yet implemented, IMS/MQ on roadmap)
    - Vendor Engagement Recommendations (AWS Mainframe Modernization tooling, IBM watsonx assistance)
Diagrams:
    - Risk heat map table
Key Citations: app/cbl/CORPT00C.cbl:507-535 (TDQ), README.md (roadmap section)
```

```
File: docs/migration-analysis/05-testing-validation-framework.md
Type: Test Strategy
Source Code: app/data/*.txt, app/cbl/CBACT04C.cbl, app/cbl/CBTRN02C.cbl
Sections:
    - Testing Philosophy (behavioral parity as success criterion)
    - File I/O Validation (byte-by-byte comparison using app/data/ fixtures as baseline)
    - Database State Validation (VSAM record-level comparison to RDBMS rows)
    - Regression Test Data Points (key fields from acctdata.txt, carddata.txt, custdata.txt, etc.)
    - Per-Utility Test Cases (specific validation for each replaced utility)
    - Test Automation Framework (JUnit 5 + Apache Commons IO for file comparison, DbUnit for state validation)
    - Continuous Validation Pipeline (CI integration for ongoing parity checks)
Diagrams:
    - Testing validation pipeline sequence diagram
Key Citations: app/data/acctdata.txt, app/data/carddata.txt, app/cbl/CBACT04C.cbl
```

### 0.5.3 Documentation Files to Update Detail

- **README.md** — Add a new section titled "Migration Analysis" containing a brief description and link to `docs/migration-analysis/00-executive-summary.md`. Insert after the existing "Road Map" section. No other changes to existing README content required.

### 0.5.4 Documentation Configuration Updates

No documentation configuration files require changes since no documentation generator framework exists in the repository. If a documentation framework is adopted in the future, the following would be needed:
- `mkdocs.yml` or equivalent: Add `docs/migration-analysis/` to navigation
- `.gitignore`: Ensure `docs/migration-analysis/diagrams/` generated output is tracked

### 0.5.5 Cross-Documentation Dependencies

- All five analysis documents (01–05) reference the utility inventory in `01-proprietary-utility-inventory.md` as the canonical catalog
- `03-migration-strategy.md` references impact assessments from `02-dependency-impact-analysis.md` for complexity justification
- `05-testing-validation-framework.md` references migration strategies from `03-migration-strategy.md` for per-utility test case design
- `04-risk-assessment.md` cross-references both `02-dependency-impact-analysis.md` (complexity) and `03-migration-strategy.md` (approach gaps)
- All four diagram files are embedded by reference in their parent analysis documents
- `README.md` update links to `docs/migration-analysis/00-executive-summary.md` as entry point

## 0.6 Dependency Inventory

### 0.6.1 Documentation Dependencies

The following documentation tooling is recommended for generating the migration analysis report. Since no documentation framework currently exists in the repository, these are new additions.

| Registry | Package Name | Version | Purpose |
|---|---|---|---|
| npm | @mermaid-js/mermaid-cli | 11.4.2 | Generate SVG/PNG diagrams from Mermaid markdown blocks in documentation |
| pip | markdown | 3.7 | Process and validate markdown documentation files |
| pip | mdformat | 0.7.21 | Auto-format markdown files for consistent style |
| pip | linkchecker | 10.5.0 | Validate all internal and external links across documentation |
| N/A | Git (built-in) | N/A | Markdown rendering natively supported by GitHub/GitLab for review |

**Note:** The primary documentation format is plain Markdown (`.md`) files with embedded Mermaid diagram blocks, which are natively rendered by GitHub. The above tools are optional for local preview and CI validation. No heavy documentation framework (MkDocs, Docusaurus, Sphinx) is required for this deliverable scope.

### 0.6.2 Documentation Reference Updates

Since this is a new documentation structure, no existing links require transformation. The only link update is:

- **README.md** — Add new internal link:
  - New: `[Migration Analysis Report](docs/migration-analysis/00-executive-summary.md)`
  - Location: After the "Road Map" section in README.md

**Internal cross-references within new documentation:**

| Source Document | Link Target | Link Text |
|---|---|---|
| 00-executive-summary.md | 01-proprietary-utility-inventory.md | Full Utility Inventory |
| 00-executive-summary.md | 04-risk-assessment.md | Risk Assessment Details |
| 02-dependency-impact-analysis.md | 01-proprietary-utility-inventory.md | Utility Catalog Reference |
| 03-migration-strategy.md | 02-dependency-impact-analysis.md | Impact Analysis Reference |
| 04-risk-assessment.md | 02-dependency-impact-analysis.md | Complexity Scores |
| 04-risk-assessment.md | 03-migration-strategy.md | Migration Approach Gaps |
| 05-testing-validation-framework.md | 03-migration-strategy.md | Per-Utility Strategies |
| 05-testing-validation-framework.md | 01-proprietary-utility-inventory.md | Utility Catalog |

## 0.7 Coverage and Quality Targets

### 0.7.1 Documentation Coverage Metrics

**Current Coverage Analysis:**

| Coverage Area | Items Found | Currently Documented | Coverage % |
|---|---|---|---|
| IBM LE Runtime Services (CEE3ABD, CEEDAYS) | 2 distinct utilities across 11 call sites | 0% — no migration docs exist | 0% |
| CICS API Command Categories | 12 distinct command types across 16 programs | ~20% — mentioned in tech spec 3.5/5.1 but no migration guidance | 20% |
| z/OS Batch Utilities (IDCAMS, SORT, IEBGENER, IEFBR14) | 4 utilities across 6+ batch jobs | ~15% — listed in README but no Java equivalent mapping | 15% |
| BMS Map Macros (DFHMSD/DFHMDI/DFHMDF) | 3 macro types across 17 BMS sources | ~10% — screen inventory in tech spec 7.x but no migration path | 10% |
| IBM Proprietary Copybooks (DFHBMSCA, DFHAID) | 2 copybooks used universally across CICS programs | 0% — no migration docs exist | 0% |
| VSAM Dataset Migration Mapping | 10+ KSDS clusters documented in LISTCAT.txt | ~25% — Section 3.4 covers VSAM types but no RDBMS mapping | 25% |
| COBOL Intrinsic Functions (migration-relevant) | 7 functions (CURRENT-DATE, MOD, etc.) | 0% — no Java mapping exists | 0% |

**Target Coverage:** 100% of all identified proprietary utilities must have:
- Inventory entry with classification and consuming programs
- Impact analysis with complexity rating and Java equivalent
- Migration strategy with recommended approach and specific libraries
- Risk assessment with HIGH/MEDIUM/LOW classification
- At least one test case for behavioral parity validation

**Coverage Gaps to Address:**
- LE Runtime Services: Currently 0% documented, target 100%. Focus: CEE3ABD abend behavior, CEEDAYS Lillian date semantics
- CICS APIs: Currently 20% documented, target 100%. Focus: File Control transaction boundaries, TDQ replacement, COMMAREA state migration
- Batch Utilities: Currently 15% documented, target 100%. Focus: SORT control statement behavior, IDCAMS DEFINE-to-DDL mapping
- BMS Macros: Currently 10% documented, target 100%. Focus: DFHMSD attribute set migration, field-level validation extraction

### 0.7.2 Documentation Quality Criteria

**Completeness Requirements:**
- Every proprietary utility has a description, consuming programs list, parameter documentation, behavioral specification, Java equivalent mapping, and at least one migration code example
- Every migration strategy includes recommended approach, specific Java library with version, behavioral equivalence assertion, and testing approach
- Every risk assessment entry includes severity rating, justification, mitigation plan, and fallback option
- The testing framework includes per-utility test cases with expected inputs, expected outputs, and comparison methodology

**Accuracy Validation:**
- All utility references cite specific COBOL source file and line number (e.g., `Source: app/cbl/CSUTLDTC.cbl:36`)
- All Java equivalent mappings cite specific library package and class (e.g., `java.time.temporal.JulianFields.JULIAN_DAY`)
- All VSAM cluster attributes cite LISTCAT.txt entries for key position, record size, and CI size
- All CICS command usage statistics cite grep results against source files

**Clarity Standards:**
- Technical accuracy maintained with accessible language for stakeholder consumption
- Progressive disclosure: executive summary → inventory → impact → strategy → risk → testing
- Consistent terminology: "proprietary utility" (not "IBM service"), "behavioral parity" (not "functional equivalence"), "migration strategy" (not "conversion plan")

**Maintainability:**
- Every section includes source citations for traceability to codebase
- Utility inventory structured for easy updates as codebase evolves (e.g., Db2 addition on roadmap)
- Modular document structure allows individual section updates without cascade effects

### 0.7.3 Example and Diagram Requirements

- Minimum 1 COBOL-to-Java code mapping example per utility category (total: 7+ examples minimum)
- Mermaid diagrams required for: utility dependency graph, CICS migration flow, VSAM-to-RDBMS ER mapping, batch job migration pipeline, testing validation pipeline (total: 5+ diagrams minimum)
- All code examples must be syntactically correct in both COBOL and Java
- Diagrams must be renderable by GitHub native Mermaid support (no external rendering required)

## 0.8 Scope Boundaries

### 0.8.1 Exhaustively In Scope

**New Documentation Files:**
- `docs/migration-analysis/00-executive-summary.md` — Stakeholder-ready summary of proprietary utility assessment
- `docs/migration-analysis/01-proprietary-utility-inventory.md` — Complete catalog of all proprietary utilities
- `docs/migration-analysis/02-dependency-impact-analysis.md` — Per-utility impact assessment
- `docs/migration-analysis/03-migration-strategy.md` — Per-utility migration playbook
- `docs/migration-analysis/04-risk-assessment.md` — Risk matrix and unknowns
- `docs/migration-analysis/05-testing-validation-framework.md` — Behavioral parity testing strategy
- `docs/migration-analysis/diagrams/utility-dependency-map.md` — Mermaid program-to-utility dependency graph
- `docs/migration-analysis/diagrams/cics-command-flow.md` — Mermaid CICS migration sequence diagram
- `docs/migration-analysis/diagrams/vsam-to-rdbms-mapping.md` — Mermaid VSAM-to-RDBMS ER diagram
- `docs/migration-analysis/diagrams/batch-job-migration-flow.md` — Mermaid batch utility replacement flowchart

**Documentation File Updates:**
- `README.md` — Add link to migration analysis report

**Source Files Analyzed for Documentation (read-only):**
- `app/cbl/*.cbl` and `app/cbl/*.CBL` — All 28 COBOL source files for CALL/EXEC CICS/FUNCTION extraction
- `app/cpy/*.cpy` — All 28 copybooks for DFHAID/DFHBMSCA/record layout analysis
- `app/bms/*.bms` — All 17 BMS map sources for DFHMSD/DFHMDI/DFHMDF macro analysis
- `app/catlg/LISTCAT.txt` — VSAM catalog snapshot for cluster attribute documentation
- `app/data/*.txt` — 8 test data fixtures for regression test data point identification
- `samples/jcl/*` — JCL samples for batch utility reference
- `samples/proc/*` — PROC samples for compilation procedures

**Documentation Assets (new):**
- `docs/migration-analysis/diagrams/` — Directory for all Mermaid diagram source files

**Proprietary Utility Categories Fully In Scope:**
- IBM Language Environment runtime services (CEE3ABD, CEEDAYS)
- CICS Transaction Server API (all 12 command categories identified)
- z/OS Batch Utilities (IDCAMS, SORT, IEBGENER, IEFBR14)
- BMS Screen Definition Macros (DFHMSD, DFHMDI, DFHMDF)
- IBM Proprietary Copybooks (DFHBMSCA, DFHAID)
- COBOL Intrinsic Functions (CURRENT-DATE, MOD, TEST-NUMVAL-C, NUMVAL-C, UPPER-CASE, TRIM, INTEGER-OF-DATE)
- VSAM file access operations and dataset management
- JES/Internal Reader job submission via TDQ

### 0.8.2 Explicitly Out of Scope

- **Source code modifications** — No changes to any `.cbl`, `.CBL`, `.cpy`, `.bms`, or JCL files. This is a documentation-only exercise
- **Test file creation or modification** — No new test programs or test scripts will be created as part of this documentation
- **Feature additions or code refactoring** — No Java code will be generated or committed; Java examples in documentation are illustrative only
- **Deployment configuration changes** — No changes to `.github/workflows/deploy-job.yml`, `Makefile`, or any CI/CD configuration
- **Db2 migration analysis** — Per README.md, Db2 support is on the roadmap but not yet implemented in the codebase; no EXEC SQL statements exist to document
- **IMS and MQ analysis** — Per README.md, IMS and MQ are on the roadmap but not yet implemented; no IMS or MQ calls exist to document
- **RACF security migration analysis** — While RACF is listed as a technology, security model migration is an infrastructure concern beyond the scope of utility dependency analysis
- **Performance benchmarking** — No performance comparison between COBOL and Java implementations is requested
- **Unrelated documentation** — No changes to CONTRIBUTING.md, CODE_OF_CONDUCT.md, or LICENSE files
- **Documentation framework setup** — No MkDocs, Docusaurus, or Sphinx installation/configuration unless explicitly requested later

## 0.9 Execution Parameters

### 0.9.1 Documentation-Specific Instructions

| Parameter | Value |
|---|---|
| Documentation build command | Not applicable — documentation is plain Markdown rendered natively by GitHub. For Mermaid diagram preview: `npx @mermaid-js/mermaid-cli -i diagram.md -o diagram.svg` |
| Documentation preview command | Open .md files in any Markdown viewer (VS Code, GitHub web UI) — no build step required |
| Diagram generation command | `npx mmdc -i input.md -o output.svg` per Mermaid diagram file |
| Documentation deployment command | Not applicable — documentation lives in repository alongside source code |
| Default format | Markdown (.md) with embedded Mermaid diagram blocks using fenced code blocks |
| Citation requirement | Every technical claim must reference a source file path and line number where applicable (e.g., Source: app/cbl/CSUTLDTC.cbl:36) |
| Style guide | Follow existing README.md formatting conventions — ATX-style headers, pipe-delimited tables, fenced code blocks for examples |
| Documentation validation | Use linkchecker for internal link verification; visual inspection for Mermaid diagram correctness via GitHub rendering |

## 0.10 Rules for Documentation

The following rules govern the creation and maintenance of all migration analysis documentation:

- **Source Citation Mandatory:** Every proprietary utility reference, Java equivalent mapping, complexity rating, and risk assessment must cite the specific COBOL source file, copybook, BMS map, or catalog entry from which it was derived. Use format: `Source: <relative-path>:<line-number>`
- **Behavioral Parity as Success Criterion:** All migration strategies must define how Java implementations will produce identical results to mainframe utility behavior. Functional equivalence without byte-level parity must be explicitly justified
- **Structured Report Format:** Findings must follow the five-section structure (Inventory → Impact → Strategy → Risk → Testing) to support stakeholder review
- **Transparency About Unknowns:** The risk assessment must honestly disclose utilities where migration paths are unclear, behavioral differences are likely, or custom development may be required
- **No Source Code Modifications:** This is a documentation-only exercise. Java code examples in documentation are illustrative and not intended for direct compilation or deployment
- **Consistent Terminology:** Use "proprietary utility" for IBM-specific services and tools, "Java equivalent" for replacement candidates, "behavioral parity" for functional equivalence, and "migration strategy" for the recommended approach
- **Mermaid Diagrams for All Dependency Relationships:** Every utility dependency, migration flow, data mapping, and test pipeline must have a corresponding Mermaid diagram
- **Stakeholder Accessibility:** The executive summary must be readable by non-technical stakeholders while all subsequent sections provide full technical depth for implementation teams
- **Future-Proofing:** Documentation structure must accommodate planned but unimplemented technologies (Db2, IMS, MQ per README.md roadmap) without restructuring existing documents
- **Test Data Fixtures as Baseline:** The 8 ASCII test data files in `app/data/` must be referenced as regression test baselines for byte-by-byte comparison validation

## 0.11 References

### 0.11.1 Repository Files and Folders Searched

**COBOL Source Files (app/cbl/) — 28 files analyzed for CALL, EXEC CICS, FUNCTION, COPY, ASSIGN TO, and FILE STATUS patterns:**

| File | Key Proprietary Dependencies Found |
|---|---|
| app/cbl/CBACT01C.cbl | CALL 'CEE3ABD', VSAM FILE STATUS, FUNCTION CURRENT-DATE |
| app/cbl/CBACT02C.cbl | CALL 'CEE3ABD', VSAM FILE STATUS |
| app/cbl/CBACT03C.cbl | CALL 'CEE3ABD', VSAM FILE STATUS |
| app/cbl/CBACT04C.cbl | CALL 'CEE3ABD', VSAM FILE STATUS, FUNCTION CURRENT-DATE, FUNCTION MOD |
| app/cbl/CBCUS01C.cbl | CALL 'CEE3ABD', VSAM FILE STATUS |
| app/cbl/CBSTM03A.cbl | CALL 'CEE3ABD', CALL 'CBSTM03B', VSAM FILE STATUS |
| app/cbl/CBSTM03B.CBL | VSAM FILE STATUS (centralized I/O subroutine) |
| app/cbl/CBTRN01C.cbl | CALL 'CEE3ABD', VSAM FILE STATUS |
| app/cbl/CBTRN02C.cbl | CALL 'CEE3ABD', VSAM FILE STATUS, FUNCTION CURRENT-DATE |
| app/cbl/CBTRN03C.cbl | CALL 'CEE3ABD', VSAM FILE STATUS |
| app/cbl/CSUTLDTC.cbl | CALL 'CEEDAYS' (LE date conversion wrapper) |
| app/cbl/COACTVWC.cbl | EXEC CICS READ/STARTBR/READNEXT/ENDBR, SEND MAP, RECEIVE MAP, XCTL, RETURN |
| app/cbl/COACTUPC.cbl | EXEC CICS READ/REWRITE, SEND MAP, RECEIVE MAP, XCTL, RETURN |
| app/cbl/COBIL00C.cbl | EXEC CICS READ/WRITE, ASKTIME, FORMATTIME, SEND MAP, RECEIVE MAP |
| app/cbl/COCRDLIC.cbl | EXEC CICS READ/STARTBR/READNEXT/ENDBR, SEND MAP, RECEIVE MAP |
| app/cbl/COCRDSLC.cbl | EXEC CICS READ, SEND MAP, RECEIVE MAP, XCTL |
| app/cbl/COMEN01C.cbl | EXEC CICS XCTL (dynamic routing), SEND MAP, RECEIVE MAP, RETURN |
| app/cbl/COMEN02C.cbl | EXEC CICS SEND TEXT, RETURN |
| app/cbl/CORPT00C.cbl | EXEC CICS WRITEQ TD (TDQ 'JOBS' for JCL submission), CALL 'CSUTLDTC', SEND MAP, RECEIVE MAP |
| app/cbl/COSGN00C.cbl | EXEC CICS ASSIGN (APPLID, SYSID), READ, SEND MAP, RECEIVE MAP, RETURN |
| app/cbl/COTRN00C.cbl | EXEC CICS READ/STARTBR/READNEXT/READPREV/ENDBR, SEND MAP, RECEIVE MAP |
| app/cbl/COTRN01C.cbl | EXEC CICS READ, SEND MAP, RECEIVE MAP |
| app/cbl/COTRN02C.cbl | EXEC CICS READ/WRITE/REWRITE, CALL 'CSUTLDTC', SEND MAP, RECEIVE MAP |
| app/cbl/COUSR00C.cbl | EXEC CICS SEND MAP, RECEIVE MAP, XCTL |
| app/cbl/COUSR01C.cbl | EXEC CICS READ/STARTBR/READNEXT/ENDBR, SEND MAP, RECEIVE MAP |
| app/cbl/COUSR02C.cbl | EXEC CICS READ/WRITE, SEND MAP, RECEIVE MAP |
| app/cbl/COUSR03C.cbl | EXEC CICS READ/REWRITE/DELETE, SEND MAP, RECEIVE MAP |
| app/cbl/COADM01C.cbl | EXEC CICS SEND MAP, RECEIVE MAP, XCTL |

**Copybooks (app/cpy/) — 28 files analyzed for IBM-proprietary copybooks and record layouts:**

| File | Content |
|---|---|
| app/cpy/DFHAID.cpy | IBM CICS AID key definitions (PF1-PF24, ENTER, CLEAR, etc.) |
| app/cpy/DFHBMSCA.cpy | IBM BMS Character Attribute Set definitions |
| app/cpy/COCOM01Y.cpy | COMMAREA layout (1024 bytes, CICS inter-program communication) |
| app/cpy/CSSETATY.cpy | Screen attribute manipulation using DFHBMSCA values |
| app/cpy/CSSTRPFY.cpy | EIBAID-to-internal-flag AID key mapping |
| app/cpy/CSUTLDPY.cpy | Date validation copybook calling CSUTLDTC (CEEDAYS wrapper) |
| app/cpy/CVACT01Y.cpy–CVACT03Y.cpy | Account record layouts |
| app/cpy/CVTRA01Y.cpy–CVTRA05Y.cpy | Transaction record layouts |
| app/cpy/CUSTREC.cpy | Customer record layout |
| app/cpy/COTTL01Y.cpy | Screen title area layout |
| app/cpy/CSDAT01Y.cpy | Date display area layout |
| app/cpy/CSMSG01Y.cpy–CSMSG02Y.cpy | Message area layouts |
| app/cpy/CSUSR01Y.cpy | User info area layout |

**BMS Maps (app/bms/) — 17 files analyzed for DFHMSD/DFHMDI/DFHMDF macro usage:**
- app/bms/COACTUP.bms, COBIL00.bms, COCRDLI.bms, COCRDSL.bms, COMEN01.bms, COMEN02.bms, CORPT00.bms, COSGN00.bms, COTRN00.bms, COTRN01.bms, COTRN02.bms, COUSR00.bms, COUSR01.bms, COUSR02.bms, COUSR03.bms, COADM01.bms, COACTVW.bms

**Other Repository Files Analyzed:**
- `app/catlg/LISTCAT.txt` — IDCAMS LISTCAT output showing VSAM KSDS cluster definitions for AWS.M2.CARDDEMO datasets
- `app/data/acctdata.txt`, `carddata.txt`, `custdata.txt`, `discgrp.txt`, `tcatbalf.txt`, `trancatg.txt`, `transact.txt`, `usrsec.txt` — ASCII fixed-width test data fixtures
- `samples/jcl/BATCMP`, `BMSCMP`, `CICCMP` — Sample JCL compile jobs
- `README.md` — Application overview, technology stack, batch job descriptions, application inventory, roadmap
- `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `LICENSE` — Community files (reviewed, not in scope for changes)
- `Makefile` — Build configuration (reviewed for documentation build targets, none found)
- `requirements.txt` — Python dependencies (reviewed for documentation tools, none found)
- `.pre-commit-config.yaml` — Pre-commit hooks (reviewed for documentation linting, none found)
- `.github/workflows/deploy-job.yml` — CI/CD workflow (reviewed for documentation deployment, none found)

### 0.11.2 Technical Specification Sections Referenced

| Section | Content Retrieved | Relevance |
|---|---|---|
| 1.1 Executive Summary | Application overview and migration context | Scope understanding and system description |
| 3.4 Data Management and Storage | VSAM types, IDCAMS commands, data formats | VSAM migration and IDCAMS utility documentation |
| 3.5 System Software and Utilities | z/OS OS, JES, DFSMS, RACF, IEBGENER, IEFBR14, SORT, DFHCSDUP, CEDA, CEMT, CECI | z/OS batch utility and CICS admin utility inventory |
| 3.7 AWS Mainframe Modernization Integration | Rehost vs Refactor strategies, planned integrations | Migration approach context and target platform understanding |
| 5.1 High-Level Architecture | 3-tier pseudo-conversational architecture, component inventory | Application architecture context for utility dependency mapping |

### 0.11.3 External Research

- COBOL to Java migration patterns and tooling ecosystem (IBM watsonx Code Assistant for Z, Astadia FastTrack Factory, Ispirer Toolkit) — reviewed for context on industry approaches to proprietary utility replacement
- Java equivalent libraries for mainframe services — reviewed for mapping LE runtime, CICS API, and z/OS batch utilities to Java Standard Library and Apache Commons ecosystem

### 0.11.4 Attachments

No attachments were provided for this project. No Figma designs or external files referenced.

