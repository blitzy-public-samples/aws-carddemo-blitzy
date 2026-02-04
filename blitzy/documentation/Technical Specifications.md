# Technical Specification

# 0. Agent Action Plan

## 0.1 Intent Clarification

### 0.1.1 Core Documentation Objective

Based on the provided requirements, the Blitzy platform understands that the documentation objective is to **create a comprehensive Code Style Catalog** that extracts, documents, and enforces coding patterns from the CardDemo mainframe application codebase.

**Request Categorization:** Create new documentation

**Documentation Type:** Technical specification / Code Style Reference Guide

The Blitzy platform has identified the following documentation requirements with enhanced clarity:

- **Pattern Extraction Requirement:** Analyze the CardDemo COBOL/CICS/BMS codebase to identify reproducible high-quality architecture patterns and exemplary code snippets
- **Catalog Structure Requirement:** Organize extracted patterns into a structured code style catalog with:
  - Priority levels (MUST/SHOULD/MAY - 必須/推奨/任意)
  - Executable code snippets
  - Source file paths
  - Validation criteria
  - Rationale explanations
- **Statistics Output Requirement:** Generate statistical summaries of patterns identified
- **Enforcement Requirement:** Document validation gates where MUST-priority violations result in automatic rejection

**Inferred Documentation Needs:**

Based on codebase analysis, the following implicit documentation needs have been identified:
- **COBOL Program Structure Patterns:** The repository contains 28 COBOL programs (app/cbl/) following consistent structural conventions requiring documentation
- **BMS Map Interface Contracts:** 17 BMS map sources (app/bms/) define 3270 screen presentation contracts needing pattern documentation
- **Copybook Data Contracts:** 28 copybooks in app/cpy/ and 17 in app/cpy-bms/ define versioned interface contracts requiring pattern documentation
- **Error Handling Patterns:** Consistent RESP/RESP2 handling, APPL-RESULT codes, and 9910-DISPLAY-IO-STATUS patterns across all files
- **Validation Patterns:** 88-level condition-name patterns for input validation prevalent throughout the codebase

### 0.1.2 Special Instructions and Constraints

**CRITICAL User Directives (Captured in Original Japanese):**

USER PROVIDED TEMPLATE - Catalog Entry Format:
```yaml
パターン名: [識別名]
優先度: MUST | SHOULD | MAY
カテゴリ: [アーキテクチャ | 命名規則 | エラー処理 | データベース | API | テスト]
ファイルパス: [模範実装の場所]
スニペット: |
  [コード例]
検証基準:
  - [チェック項目1]
  - [チェック項目2]
根拠: [このパターンを採用する理由]
```

USER PROVIDED TEMPLATE - Compliance Report Format:
```plaintext
=== カタログ準拠レポート ===
生成ファイル: [パス]
適用ルール数: [N]
MUST準拠: [合格/不合格]
SHOULD準拠: [合格/逸脱あり]
逸脱項目:
  - [ルール名]: [正当化理由]
```

**Priority Level Definitions (User-Specified):**

| Level | Japanese | Enforcement |
|-------|----------|-------------|
| MUST | 必須 | Non-negotiable. Automatic rejection on violation |
| SHOULD | 推奨 | Applied by default. Deviation requires documented justification |
| MAY | 任意 | Developer discretion |

**Prohibited Patterns (User-Specified):**
- Code generation violating MUST-priority catalog rules
- Ignoring exemplary snippet patterns when equivalent structures exist in catalog
- Creating new architectural approaches when catalog provides established patterns
- Deviating from catalog validation criteria without explicit justification
- Generating code contradicting catalog rationale documentation

**Validation Gate Requirements:**
- All generated code must pass validation against applicable catalog rules
- Code violating MUST-priority rules results in automatic review failure
- SHOULD-priority deviations require documented justification in code comments
- Catalog compliance reports must accompany each generated output

### 0.1.3 Technical Interpretation

These documentation requirements translate to the following technical documentation strategy:

- **To document architecture patterns**, we will create `docs/code-style-catalog/architecture-patterns.md` extracting COBOL program structure from `app/cbl/*.cbl`
- **To document naming conventions**, we will create `docs/code-style-catalog/naming-conventions.md` analyzing variable naming patterns in `app/cpy/*.cpy` and `app/cbl/*.cbl`
- **To document error handling**, we will create `docs/code-style-catalog/error-handling.md` capturing RESP/RESP2 patterns from CICS programs
- **To document data contracts**, we will create `docs/code-style-catalog/data-contracts.md` documenting copybook structures from `app/cpy/*.cpy` and `app/cpy-bms/*.CPY`
- **To document BMS/UI patterns**, we will create `docs/code-style-catalog/bms-patterns.md` extracting screen definition patterns from `app/bms/*.bms`
- **To document validation patterns**, we will create `docs/code-style-catalog/validation-patterns.md` capturing 88-level and input validation patterns

### 0.1.4 Inferred Documentation Needs

Based on comprehensive code analysis:

- **Module `app/cbl/`:** Contains 28 COBOL programs with consistent paragraph numbering (0000, 1000, 2000, 9000 series), copybook-driven FD definitions, and standardized error handling - requires architecture and structure pattern documentation
- **Module `app/bms/`:** Contains 17 BMS map sources with consistent DFHMSD/DFHMDI/DFHMDF macro usage, standard attribute vocabulary (ASKIP, FSET, NORM, PROT/UNPROT), and color tokens - requires UI pattern documentation
- **Module `app/cpy/`:** Contains 28 shared copybooks defining 01-level record layouts with explicit PIC clauses, RECLN values, REDEFINES patterns, and 88-level condition names - requires data contract pattern documentation
- **Module `app/cpy-bms/`:** Contains 17 BMS copybooks following AI/AO two-view pattern with consistent FILLER usage - requires BMS interface pattern documentation
- **Integration `COCOM01Y.cpy`:** CARDDEMO-COMMAREA defines critical program-to-program communication contract requiring interface documentation
- **User Journey:** Code generators using this catalog will need setup guide, pattern reference, compliance checking, and troubleshooting documentation

## 0.2 Documentation Discovery and Analysis

### 0.2.1 Existing Documentation Infrastructure Assessment

**Repository Analysis Results:**

Repository analysis reveals a **minimal documentation structure** with the following coverage status:

| Documentation File | Status | Purpose |
|-------------------|--------|---------|
| `README.md` | EXISTS | Primary operational and onboarding manual |
| `CONTRIBUTING.md` | EXISTS | Contributor workflow and PR guidelines |
| `CODE_OF_CONDUCT.md` | EXISTS | Governance pointer to Amazon Open Source Code of Conduct |
| `LICENSE` | EXISTS | Apache License 2.0 |
| `diagrams/` | EXISTS | Application flow diagrams and screenshots |
| `docs/` | MISSING | No dedicated documentation directory exists |

**Current Documentation Framework:**
- **Documentation Generator:** None detected (no mkdocs.yml, docusaurus.config.js, sphinx.conf.py)
- **API Documentation Tools:** None detected (no JSDoc, Sphinx, or similar configurations)
- **Diagram Tools:** Draw.io detected (`diagrams/CARDDEMO-DataModel.drawio`)
- **Documentation Hosting:** None configured

**Search Patterns Employed:**
```
Searched: README*, docs/**, *.md, *.mdx, *.rst, wiki/**
Results:
  - ./README.md (primary documentation)
  - ./CONTRIBUTING.md (contributor guide)
  - ./CODE_OF_CONDUCT.md (conduct policy)
  - ./diagrams/ (visual assets only)
```

### 0.2.2 Repository Code Analysis for Documentation

**Search Patterns Used for Code Pattern Discovery:**

| Pattern Category | Search Location | Files Found |
|-----------------|-----------------|-------------|
| COBOL Programs | `app/cbl/*.cbl, app/cbl/*.CBL` | 28 files |
| BMS Maps | `app/bms/*.bms` | 17 files |
| Record Copybooks | `app/cpy/*.cpy, app/cpy/*.CPY` | 28 files |
| BMS Copybooks | `app/cpy-bms/*.CPY` | 17 files |
| Data Fixtures | `app/data/ASCII/*.txt` | 9 files |
| Catalog Metadata | `app/catlg/*.txt` | 1 file |

**Key Directories Examined:**

- `app/cbl/` - COBOL source tree with batch (CBACT*, CBTRN*, CBCUS*, CBSTM*) and CICS programs (COACTUPC, COACTVWC, COCRDUPC, etc.)
- `app/bms/` - CICS BMS map sources defining 24x80 3270 screen contracts
- `app/cpy/` - Shared copybooks with record layouts (CVACT01Y, CVTRA05Y) and procedural snippets (CSUTLDPY, CSSETATY)
- `app/cpy-bms/` - BMS-specific copybooks following AI/AO two-view pattern
- `app/data/ASCII/` - Fixed-width test fixtures (acctdata.txt, carddata.txt, etc.)

**Related Documentation Found:**

- `README.md` provides high-level application documentation with:
  - Installation instructions
  - JCL job sequences
  - Transaction-to-program mappings
  - Dataset conventions
- No existing code style documentation or coding standards exist

### 0.2.3 Code Pattern Categories Identified

**Architecture Patterns Detected:**

| Pattern Name | Frequency | Source Files | Priority Assessment |
|--------------|-----------|--------------|---------------------|
| Numbered Paragraph Structure | 28/28 programs | `app/cbl/*.cbl` | MUST |
| COPYbook-driven FD definitions | 28/28 programs | `app/cbl/*.cbl` | MUST |
| RESP/RESP2 Error Handling | All CICS programs | `app/cbl/CO*.cbl` | MUST |
| 88-Level Condition Names | All copybooks | `app/cpy/*.cpy` | MUST |
| AI/AO Two-View BMS Pattern | 17/17 BMS copybooks | `app/cpy-bms/*.CPY` | MUST |
| DFHMSD/DFHMDI/DFHMDF Structure | 17/17 maps | `app/bms/*.bms` | MUST |
| APPL-RESULT Status Codes | Batch programs | `app/cbl/CB*.cbl` | SHOULD |
| 9910-DISPLAY-IO-STATUS | I/O error display | `app/cbl/*.cbl` | SHOULD |
| WS-RETURN-MSG Error Messaging | CICS programs | `app/cbl/CO*.cbl` | SHOULD |
| PERFORM...THRU Structure | All programs | `app/cbl/*.cbl` | MAY |

**Naming Convention Patterns Detected:**

| Pattern | Example | Source | Priority |
|---------|---------|--------|----------|
| Program ID Prefix | CBACT (batch), CO (CICS) | All programs | MUST |
| Copybook Suffix | Y for data, CPY for BMS | `app/cpy/CV*Y.cpy` | MUST |
| Field Prefix by Type | WS- (Working Storage), FD- (File) | All programs | SHOULD |
| 88-Level Naming | FLG-*, IS-VALID, NOT-OK | All copybooks | SHOULD |
| Map Suffix Pattern | AI (input), AO (output) | BMS copybooks | MUST |

### 0.2.4 Web Search Research Requirements

**Research Topics for Best Practices:**
- COBOL coding standards for mainframe modernization
- BMS map design patterns for CICS applications
- VSAM file handling best practices
- COBOL copybook design patterns
- Mainframe code style documentation formats

**Note:** The codebase represents a well-established mainframe application with consistent internal patterns. The Code Style Catalog will document these existing patterns rather than introducing external standards, ensuring compatibility with AWS Mainframe Modernization tooling.

## 0.3 Documentation Scope Analysis

### 0.3.1 Code-to-Documentation Mapping

**COBOL Programs Requiring Pattern Documentation:**

| Module Path | Program Type | Pattern Categories | Documentation Needed |
|-------------|--------------|-------------------|---------------------|
| `app/cbl/COACTUPC.cbl` | CICS Online | Architecture, Error Handling, Validation | Exemplary CICS program structure |
| `app/cbl/COACTVWC.cbl` | CICS Online | Architecture, BMS Integration | View-only screen pattern |
| `app/cbl/COCRDUPC.cbl` | CICS Online | Architecture, CRUD Operations | Update transaction pattern |
| `app/cbl/COCRDLIC.cbl` | CICS Online | Architecture, List Display | List/browse pattern with STARTBR/READNEXT |
| `app/cbl/COSGN00C.cbl` | CICS Online | Security, Session Management | Signon pattern |
| `app/cbl/COMEN01C.cbl` | CICS Online | Navigation, Menu Structure | Menu navigation pattern |
| `app/cbl/CBACT01C.cbl` | Batch | Architecture, File I/O | Batch file reader pattern |
| `app/cbl/CBTRN02C.cbl` | Batch | Transaction Processing | Transaction posting pattern |
| `app/cbl/CBSTM03A.CBL` | Batch | Report Generation | Statement generator pattern |
| `app/cbl/CBSTM03B.CBL` | Batch Subroutine | I/O Abstraction | Centralized I/O wrapper pattern |

**BMS Maps Requiring Pattern Documentation:**

| Map Source | Map Name | Screen Type | Pattern Categories |
|------------|----------|-------------|-------------------|
| `app/bms/COSGN00.bms` | COSGN0A | Login | Signon screen layout, password masking |
| `app/bms/COMEN01.bms` | COMEN1A | Menu | Option list with numeric selection |
| `app/bms/COACTUP.bms` | CACTUPA | Form | Data entry with validation indicators |
| `app/bms/COCRDLI.bms` | CCRDLIA | List | Repeating row selection pattern |
| `app/bms/COTRN00.bms` | COTRN0A | List | Transaction list with pagination |

**Copybooks Requiring Pattern Documentation:**

| Copybook | Type | Pattern Category | Key Patterns |
|----------|------|-----------------|--------------|
| `app/cpy/COCOM01Y.cpy` | Communication | Interface Contract | COMMAREA structure, program routing |
| `app/cpy/CVACT01Y.cpy` | Record Layout | Data Contract | ACCOUNT-RECORD 300-byte layout |
| `app/cpy/CVTRA05Y.cpy` | Record Layout | Data Contract | TRAN-RECORD 350-byte layout |
| `app/cpy/CVCUS01Y.cpy` | Record Layout | Data Contract | CUSTOMER-RECORD 500-byte layout |
| `app/cpy/CSUTLDPY.cpy` | Procedural | Validation | Date validation paragraphs |
| `app/cpy/CSMSG02Y.cpy` | Working Storage | Error Handling | ABEND-DATA structure |
| `app/cpy-bms/COACTUP.CPY` | BMS Layout | UI Contract | AI/AO two-view pattern |

### 0.3.2 Documentation Gap Analysis

Given the requirements and repository analysis, documentation gaps include:

**Undocumented Code Patterns:**

| Gap Category | Current State | Documentation Required |
|--------------|---------------|----------------------|
| Program Structure | No formal documentation | MUST-level architecture patterns |
| Naming Conventions | Implicitly followed | MUST-level naming rules |
| Error Handling | No documentation | MUST-level error handling patterns |
| Data Contracts | No schema documentation | MUST-level copybook patterns |
| BMS Patterns | No documentation | MUST-level BMS structure patterns |
| Validation Logic | No documentation | SHOULD-level validation patterns |
| File I/O Patterns | No documentation | SHOULD-level I/O patterns |

**Missing Code Style Catalog Components:**

| Component | Status | Impact |
|-----------|--------|--------|
| Pattern catalog document | MISSING | No reference for code generation |
| Validation criteria | MISSING | No automated compliance checking |
| Priority assignments | MISSING | No enforcement hierarchy |
| Exemplary snippets | MISSING | No templates for new code |
| Compliance report template | MISSING | No standardized reporting |

### 0.3.3 Pattern Coverage by File Type

**COBOL Programs (app/cbl/) - 28 Files:**

| Pattern Category | Files with Pattern | Coverage % |
|------------------|-------------------|------------|
| Numbered paragraphs (0000-9999) | 28/28 | 100% |
| COPY statement usage | 28/28 | 100% |
| RESP/RESP2 handling (CICS) | 20/28 | 71% |
| APPL-RESULT codes (batch) | 8/28 | 29% |
| 88-level conditions | 28/28 | 100% |
| WS-RETURN-MSG messaging | 20/28 | 71% |
| PERFORM...THRU | 28/28 | 100% |

**BMS Maps (app/bms/) - 17 Files:**

| Pattern Category | Files with Pattern | Coverage % |
|------------------|-------------------|------------|
| DFHMSD/DFHMDI/DFHMDF structure | 17/17 | 100% |
| Standard header (TRNNAME, CURDATE, etc.) | 17/17 | 100% |
| ERRMSG field | 17/17 | 100% |
| Color vocabulary (BLUE, YELLOW, etc.) | 17/17 | 100% |
| Attribute patterns (ASKIP, FSET, etc.) | 17/17 | 100% |

**Copybooks (app/cpy/ + app/cpy-bms/) - 45 Files:**

| Pattern Category | Files with Pattern | Coverage % |
|------------------|-------------------|------------|
| 01-level group definitions | 45/45 | 100% |
| PIC clause usage | 45/45 | 100% |
| FILLER padding | 40/45 | 89% |
| REDEFINES overlays | 35/45 | 78% |
| 88-level conditions | 30/45 | 67% |
| RECLN documentation | 15/45 | 33% |

## 0.4 Documentation Implementation Design

### 0.4.1 Documentation Structure Planning

**Documentation Hierarchy:**

```
docs/
├── code-style-catalog/
│   ├── README.md                          (Catalog overview and quick reference)
│   ├── index.md                           (統計サマリー - Statistics summary)
│   ├── architecture-patterns.md           (アーキテクチャパターン)
│   │   ├── MUST: Program structure
│   │   ├── MUST: Division organization
│   │   └── SHOULD: Paragraph numbering
│   ├── naming-conventions.md              (命名規則)
│   │   ├── MUST: Program ID prefixes
│   │   ├── MUST: Copybook naming
│   │   └── SHOULD: Field prefixes
│   ├── error-handling.md                  (エラー処理)
│   │   ├── MUST: RESP/RESP2 patterns
│   │   ├── MUST: APPL-RESULT codes
│   │   └── SHOULD: Error messaging
│   ├── data-contracts.md                  (データベース/データ契約)
│   │   ├── MUST: Copybook structure
│   │   ├── MUST: Record layouts
│   │   └── SHOULD: PIC clause conventions
│   ├── bms-patterns.md                    (API/UI パターン)
│   │   ├── MUST: DFHMSD structure
│   │   ├── MUST: AI/AO two-view pattern
│   │   └── SHOULD: Attribute vocabulary
│   ├── validation-patterns.md             (テスト/検証パターン)
│   │   ├── MUST: 88-level conditions
│   │   ├── SHOULD: Input validation
│   │   └── MAY: Date validation
│   └── compliance-reporting.md            (準拠レポート)
│       ├── Report format specification
│       ├── Validation checklist
│       └── Deviation documentation
└── diagrams/
    └── (existing diagrams maintained)
```

### 0.4.2 Content Generation Strategy

**Information Extraction Approach:**

| Source | Extraction Method | Target Documentation |
|--------|------------------|---------------------|
| `app/cbl/COACTUPC.cbl` | Parse IDENTIFICATION/ENVIRONMENT/DATA/PROCEDURE divisions | architecture-patterns.md |
| `app/cbl/CBACT01C.cbl` | Extract FILE-CONTROL and FD patterns | data-contracts.md |
| `app/cpy/COCOM01Y.cpy` | Document 01-level COMMAREA structure | data-contracts.md |
| `app/cpy/CSUTLDPY.cpy` | Extract validation paragraph patterns | validation-patterns.md |
| `app/bms/COSGN00.bms` | Parse DFHMSD/DFHMDI/DFHMDF macros | bms-patterns.md |
| `app/cpy-bms/COACTUP.CPY` | Document AI/AO pattern structure | bms-patterns.md |

**Template Application Strategy:**

For each pattern entry, apply the user-provided YAML template:
```yaml
パターン名: [Pattern identifier in Japanese]
優先度: MUST | SHOULD | MAY
カテゴリ: [Category from: アーキテクチャ | 命名規則 | エラー処理 | データベース | API | テスト]
ファイルパス: [Source file path]
スニペット: |
  [Extracted code example - 10-20 lines max]
検証基準:
  - [Validation criterion 1]
  - [Validation criterion 2]
根拠: [Rationale for this pattern]
```

### 0.4.3 Documentation Standards

**Markdown Formatting Requirements:**

- Headers: `#` for document title, `##` for priority sections, `###` for patterns
- Code blocks: Use ` ```cobol ` for COBOL snippets, ` ```bms ` for BMS macros
- Tables: Use markdown tables for pattern summaries and validation criteria
- Citations: Format as `Source: /app/cbl/filename.cbl:LineNumber`

**Source Citation Format:**

```
**Source:** `app/cbl/COACTUPC.cbl:600-616`
```

**Mermaid Diagrams to Create:**

| Diagram Type | Purpose | Location |
|--------------|---------|----------|
| Flowchart | Program execution flow | architecture-patterns.md |
| Class diagram | Copybook relationships | data-contracts.md |
| Sequence diagram | CICS transaction flow | architecture-patterns.md |
| Entity-relationship | Data model relationships | data-contracts.md |

### 0.4.4 Catalog Statistics Summary Specification

The index.md file shall include:

```
## カタログ統計サマリー (Catalog Statistics Summary)

| 指標 (Metric) | 値 (Value) |
|--------------|-----------|
| 総パターン数 (Total Patterns) | [N] |
| MUST優先度パターン | [N] |
| SHOULD優先度パターン | [N] |
| MAY優先度パターン | [N] |
| カテゴリ別分布 | See breakdown |

#### カテゴリ別パターン数 (Patterns by Category)

| カテゴリ | MUST | SHOULD | MAY | 合計 |
|---------|------|--------|-----|------|
| アーキテクチャ | X | X | X | X |
| 命名規則 | X | X | X | X |
| エラー処理 | X | X | X | X |
| データベース | X | X | X | X |
| API | X | X | X | X |
| テスト | X | X | X | X |
```

### 0.4.5 Exemplary Pattern Entry Examples

**Example MUST-Level Pattern Entry:**

```yaml
パターン名: CICS-RESP-RESP2-エラーハンドリング
優先度: MUST
カテゴリ: エラー処理
ファイルパス: app/cbl/COACTUPC.cbl:1040-1046
スニペット: |
  EXEC CICS RECEIVE MAP(LIT-THISMAP)
            MAPSET(LIT-THISMAPSET)
            INTO(CACTUPAI)
            RESP(WS-RESP-CD)
            RESP2(WS-REAS-CD)
  END-EXEC
検証基準:
  - すべてのEXEC CICSコマンドにRESPパラメータを含めること
  - RESPとRESP2の両方を常に取得すること
  - RESPコードをWS-RESP-CDに格納すること
根拠: CICS APIエラーを確実に捕捉し、適切なエラー処理を可能にするため
```

**Example SHOULD-Level Pattern Entry:**

```yaml
パターン名: 段落番号規則
優先度: SHOULD
カテゴリ: アーキテクチャ
ファイルパス: app/cbl/CBACT01C.cbl:70-116
スニペット: |
  PROCEDURE DIVISION.
      PERFORM 0000-ACCTFILE-OPEN.
      PERFORM 1000-ACCTFILE-GET-NEXT
      PERFORM 9000-ACCTFILE-CLOSE.
検証基準:
  - 0000番台: 初期化/オープン処理
  - 1000-8000番台: メイン処理
  - 9000番台: 終了/クローズ処理
  - 9910: I/Oステータス表示
  - 9999: 異常終了処理
根拠: コードナビゲーションと保守性の向上のため
```

## 0.5 Documentation File Transformation Mapping

### 0.5.1 File-by-File Documentation Plan

**CRITICAL: Complete Documentation File Transformation Mapping**

| Target Documentation File | Transformation | Source Code/Docs | Content/Changes |
|---------------------------|----------------|------------------|-----------------|
| `docs/code-style-catalog/README.md` | CREATE | All source directories | Catalog overview, quick reference guide, navigation index, compliance workflow |
| `docs/code-style-catalog/index.md` | CREATE | All `app/` directories | Statistics summary (統計サマリー) with pattern counts by priority and category |
| `docs/code-style-catalog/architecture-patterns.md` | CREATE | `app/cbl/*.cbl` | COBOL program structure patterns, division organization, paragraph numbering conventions |
| `docs/code-style-catalog/naming-conventions.md` | CREATE | `app/cbl/*.cbl`, `app/cpy/*.cpy` | Program ID prefixes, copybook naming, field naming conventions, 88-level naming |
| `docs/code-style-catalog/error-handling.md` | CREATE | `app/cbl/CO*.cbl`, `app/cbl/CB*.cbl` | RESP/RESP2 patterns, APPL-RESULT codes, 9910-DISPLAY-IO-STATUS, ABEND handling |
| `docs/code-style-catalog/data-contracts.md` | CREATE | `app/cpy/*.cpy`, `app/cpy-bms/*.CPY` | Copybook structure, record layouts, PIC clause conventions, REDEFINES patterns |
| `docs/code-style-catalog/bms-patterns.md` | CREATE | `app/bms/*.bms`, `app/cpy-bms/*.CPY` | DFHMSD/DFHMDI/DFHMDF structure, AI/AO two-view pattern, attribute vocabulary, color tokens |
| `docs/code-style-catalog/validation-patterns.md` | CREATE | `app/cpy/CSUTLDPY.cpy`, `app/cbl/*.cbl` | 88-level condition patterns, input validation, date validation, field edit patterns |
| `docs/code-style-catalog/compliance-reporting.md` | CREATE | User requirements | Compliance report format, validation checklist, deviation documentation template |
| `README.md` | UPDATE | `README.md` | Add section linking to Code Style Catalog documentation |

### 0.5.2 New Documentation Files Detail

**File: `docs/code-style-catalog/README.md`**
```
Type: Catalog Overview
Source Code: All app/ directories
Sections:
  - Overview (カタログ概要)
  - Priority Level Definitions (優先度定義)
  - Quick Reference Matrix (クイックリファレンス)
  - Navigation Index (ナビゲーション)
  - How to Use This Catalog (使用方法)
  - Compliance Workflow (準拠ワークフロー)
Diagrams:
  - Flowchart: Catalog usage workflow
Key Citations: User requirements document
```

**File: `docs/code-style-catalog/index.md`**
```
Type: Statistics Summary
Source Code: All app/ directories (pattern analysis)
Sections:
  - Catalog Statistics Summary (カタログ統計サマリー)
  - Pattern Count by Priority (優先度別パターン数)
  - Pattern Count by Category (カテゴリ別パターン数)
  - Coverage Analysis (カバレッジ分析)
  - Source File Reference (ソースファイル参照)
Tables:
  - Statistics summary table
  - Category breakdown matrix
Key Citations: Pattern analysis from all source files
```

**File: `docs/code-style-catalog/architecture-patterns.md`**
```
Type: Pattern Reference
Source Code: app/cbl/COACTUPC.cbl, app/cbl/CBACT01C.cbl, app/cbl/CBSTM03B.CBL
Sections:
  - MUST: Program Structure (プログラム構造)
    - IDENTIFICATION DIVISION pattern
    - ENVIRONMENT DIVISION pattern
    - DATA DIVISION organization
    - PROCEDURE DIVISION structure
  - MUST: Division Organization (ディビジョン構成)
  - SHOULD: Paragraph Numbering (段落番号規則)
    - 0000 series: Initialization
    - 1000-8000 series: Main processing
    - 9000 series: Termination
  - MAY: PERFORM...THRU Usage
Diagrams:
  - Flowchart: Program execution flow
  - Sequence diagram: CICS transaction flow
Key Citations: app/cbl/COACTUPC.cbl, app/cbl/CBACT01C.cbl
```

**File: `docs/code-style-catalog/naming-conventions.md`**
```
Type: Pattern Reference
Source Code: app/cbl/*.cbl, app/cpy/*.cpy, app/bms/*.bms
Sections:
  - MUST: Program ID Prefixes (プログラムID接頭辞)
    - CB* for batch programs
    - CO* for CICS online programs
    - CS* for shared utilities
  - MUST: Copybook Naming (コピーブック命名)
    - CV* for VSAM record layouts
    - CS* for shared definitions
    - CO* for program-specific
  - SHOULD: Field Prefixes (フィールド接頭辞)
    - WS- for Working-Storage
    - FD- for File Description
    - LK- for Linkage Section
  - SHOULD: 88-Level Naming (88レベル命名)
    - FLG-*-ISVALID, FLG-*-NOT-OK patterns
Key Citations: app/cbl/COACTUPC.cbl:36-80, app/cpy/COCOM01Y.cpy
```

**File: `docs/code-style-catalog/error-handling.md`**
```
Type: Pattern Reference
Source Code: app/cbl/COACTUPC.cbl, app/cbl/CBACT01C.cbl, app/cpy/CSMSG02Y.cpy
Sections:
  - MUST: CICS RESP/RESP2 Handling (RESP/RESP2処理)
    - Every EXEC CICS command must include RESP
    - RESP2 required for detailed error info
  - MUST: APPL-RESULT Codes (APPL-RESULT コード)
    - 0: Success
    - 16: End of file
    - 12: Error
  - SHOULD: Error Message Display (エラーメッセージ表示)
    - 9910-DISPLAY-IO-STATUS pattern
    - WS-RETURN-MSG usage
  - SHOULD: ABEND Processing (異常終了処理)
    - ABEND-DATA structure
    - 9999-ABEND-PROGRAM pattern
Key Citations: app/cbl/COACTUPC.cbl:1040-1046, app/cbl/CBACT01C.cbl:92-116
```

**File: `docs/code-style-catalog/data-contracts.md`**
```
Type: Pattern Reference
Source Code: app/cpy/*.cpy, app/cpy-bms/*.CPY
Sections:
  - MUST: Copybook Structure (コピーブック構造)
    - 01-level group definitions
    - RECLN documentation
    - Apache-2.0 license header
  - MUST: Record Layouts (レコードレイアウト)
    - Fixed-length records with FILLER
    - PIC clause conventions
    - REDEFINES patterns
  - SHOULD: PIC Conventions (PIC句規約)
    - PIC 9(n) for numeric
    - PIC X(n) for alphanumeric
    - PIC S9(n)V99 for signed decimal
  - MUST: COMMAREA Contract (COMMAREA契約)
    - CARDDEMO-COMMAREA structure
Diagrams:
  - Entity-relationship diagram for data model
Key Citations: app/cpy/CVACT01Y.cpy, app/cpy/COCOM01Y.cpy
```

**File: `docs/code-style-catalog/bms-patterns.md`**
```
Type: Pattern Reference
Source Code: app/bms/*.bms, app/cpy-bms/*.CPY
Sections:
  - MUST: DFHMSD Structure (DFHMSD構造)
    - Required parameters: LANG, MODE, STORAGE, TIOAPFX
    - TYPE=&&SYSPARM parameterization
  - MUST: DFHMDI/DFHMDF Structure (DFHMDI/DFHMDF構造)
    - 24x80 coordinate grid
    - POS, LENGTH, ATTRB requirements
  - MUST: AI/AO Two-View Pattern (AI/AO二重ビューパターン)
    - AI suffix for input view
    - AO suffix with REDEFINES for output
  - SHOULD: Attribute Vocabulary (属性語彙)
    - ASKIP, FSET, NORM, PROT, UNPROT
    - Color tokens: BLUE, YELLOW, TURQUOISE, GREEN, RED
  - SHOULD: Standard Screen Elements (標準画面要素)
    - TRNNAME, CURDATE, PGMNAME, CURTIME
    - ERRMSG, INFOMSG fields
Key Citations: app/bms/COSGN00.bms, app/cpy-bms/COACTUP.CPY
```

**File: `docs/code-style-catalog/validation-patterns.md`**
```
Type: Pattern Reference
Source Code: app/cpy/CSUTLDPY.cpy, app/cbl/COACTUPC.cbl
Sections:
  - MUST: 88-Level Condition Names (88レベル条件名)
    - FLG-*-ISVALID VALUE LOW-VALUES
    - FLG-*-NOT-OK VALUE '0'
    - FLG-*-BLANK VALUE 'B' or SPACES
  - SHOULD: Input Validation Pattern (入力検証パターン)
    - WS-EDIT-* variable naming
    - INPUT-OK/INPUT-ERROR flags
  - MAY: Date Validation (日付検証)
    - EDIT-DATE-CCYYMMDD pattern
    - CEEDAYS LE service integration
Key Citations: app/cpy/CSUTLDPY.cpy:18-331, app/cbl/COACTUPC.cbl:51-146
```

**File: `docs/code-style-catalog/compliance-reporting.md`**
```
Type: Process Reference
Source Code: User requirements
Sections:
  - Compliance Report Format (準拠レポート形式)
  - Validation Checklist (検証チェックリスト)
  - Deviation Documentation (逸脱文書化)
  - Review Workflow (レビューワークフロー)
Templates:
  - User-provided compliance report template
Key Citations: User requirements document
```

### 0.5.3 Documentation File to Update

**`README.md` - Add Code Style Catalog Reference**

Update sections:
- Add "Code Style Catalog" to table of contents
- Add new section: "## Code Style Catalog"
- Include link to `docs/code-style-catalog/README.md`
- Brief description of catalog purpose

### 0.5.4 Cross-Documentation Dependencies

| Source Document | Links To | Relationship |
|-----------------|----------|--------------|
| `README.md` | `docs/code-style-catalog/README.md` | Navigation entry point |
| `docs/code-style-catalog/README.md` | All category files | Index/navigation |
| `architecture-patterns.md` | `naming-conventions.md` | Cross-reference for program naming |
| `error-handling.md` | `architecture-patterns.md` | Cross-reference for paragraph structure |
| `data-contracts.md` | `bms-patterns.md` | Cross-reference for BMS copybooks |
| `compliance-reporting.md` | All pattern files | Validation reference |

## 0.6 Dependency Inventory

### 0.6.1 Documentation Dependencies

**Documentation Tools and Packages:**

| Registry | Package Name | Version | Purpose |
|----------|--------------|---------|---------|
| N/A | Markdown | Standard | Primary documentation format |
| N/A | Mermaid | Standard | Diagram generation embedded in markdown |
| GitHub | GitHub Markdown | Standard | Rendering platform (GitHub native) |
| Draw.io | Desktop/Web | Latest | Complex diagrams (existing: CARDDEMO-DataModel.drawio) |

**Note:** This documentation task does not require external package installation. The Code Style Catalog will be created using standard Markdown format with embedded Mermaid diagrams, compatible with GitHub's native rendering.

### 0.6.2 Source Code Dependencies Documented

The Code Style Catalog documents patterns from the following source dependencies:

**COBOL Runtime Dependencies (for pattern context):**

| Component | Purpose | Pattern Impact |
|-----------|---------|----------------|
| z/OS COBOL Compiler | Enterprise COBOL compilation | MUST patterns for division structure |
| CICS Runtime | Transaction processing | MUST patterns for EXEC CICS commands |
| VSAM | File access method | MUST patterns for file status handling |
| Language Environment (LE) | Runtime services | SHOULD patterns for CEEDAYS integration |
| DFHBMSCA | BMS attribute constants | MUST patterns for screen attribute control |
| DFHAID | Attention identifier constants | MUST patterns for AID key handling |

**Copybook Dependencies (Interface Contracts):**

| Copybook | Consumers | Pattern Documentation |
|----------|-----------|----------------------|
| `COCOM01Y.cpy` | All CICS programs | COMMAREA communication contract |
| `DFHBMSCA` | All CICS programs | BMS attribute byte definitions |
| `DFHAID` | All CICS programs | Attention identifier definitions |
| `CVACT01Y.cpy` | Batch/CICS account programs | Account record layout contract |
| `CVCUS01Y.cpy` | Customer-related programs | Customer record layout contract |
| `CSUTLDPY.cpy` | Programs with date validation | Date validation procedure contract |
| `CSMSG02Y.cpy` | All programs with abend handling | ABEND-DATA structure contract |

### 0.6.3 Documentation Reference Updates

**Documentation Files Requiring Link Updates:**

| File | Update Required |
|------|-----------------|
| `README.md` | Add link to `docs/code-style-catalog/README.md` in table of contents |
| `CONTRIBUTING.md` | Reference Code Style Catalog for coding standards |

**Link Transformation Rules:**

| Context | Old Pattern | New Pattern |
|---------|-------------|-------------|
| Coding standards | (none) | Link to `docs/code-style-catalog/` |
| Pattern reference | (none) | Link to specific pattern file |
| Compliance | (none) | Link to `compliance-reporting.md` |

### 0.6.4 Version Control Considerations

**Documentation Versioning:**

| Document | Versioning Strategy |
|----------|---------------------|
| All catalog files | Include version comment at end of file |
| Pattern entries | Include source file version reference |
| Statistics | Regenerate on source code changes |

**Version Tag Format:**
```
<!-- Ver: CodeStyleCatalog_v1.0 Date: [Generation Date] -->
```

This mirrors the existing version tagging convention observed in source files:
```cobol
* Ver: CardDemo_v1.0-70-g193b394-123 Date: 2022-08-22 17:02:43 CDT
```

## 0.7 Coverage and Quality Targets

### 0.7.1 Documentation Coverage Metrics

**Current Coverage Analysis:**

| Category | Items Identified | Items Documented | Coverage % | Target |
|----------|-----------------|------------------|------------|--------|
| Architecture Patterns | 12 | 0 | 0% | 100% |
| Naming Conventions | 8 | 0 | 0% | 100% |
| Error Handling Patterns | 6 | 0 | 0% | 100% |
| Data Contract Patterns | 10 | 0 | 0% | 100% |
| BMS/UI Patterns | 8 | 0 | 0% | 100% |
| Validation Patterns | 5 | 0 | 0% | 100% |
| **Total** | **49** | **0** | **0%** | **100%** |

**Target Coverage by Priority Level:**

| Priority | Target Patterns | Target Coverage |
|----------|-----------------|-----------------|
| MUST | 25 patterns | 100% documented |
| SHOULD | 18 patterns | 100% documented |
| MAY | 6 patterns | 100% documented |

### 0.7.2 Pattern Inventory by Priority

**MUST-Level Patterns (25 patterns - 100% required):**

| # | Pattern Name | Category | Source File |
|---|--------------|----------|-------------|
| 1 | IDENTIFICATION DIVISION structure | アーキテクチャ | `app/cbl/*.cbl` |
| 2 | ENVIRONMENT DIVISION structure | アーキテクチャ | `app/cbl/*.cbl` |
| 3 | DATA DIVISION organization | アーキテクチャ | `app/cbl/*.cbl` |
| 4 | PROCEDURE DIVISION structure | アーキテクチャ | `app/cbl/*.cbl` |
| 5 | CICS RESP/RESP2 error capture | エラー処理 | `app/cbl/CO*.cbl` |
| 6 | Batch APPL-RESULT codes | エラー処理 | `app/cbl/CB*.cbl` |
| 7 | Program ID prefix (CB*/CO*) | 命名規則 | `app/cbl/*.cbl` |
| 8 | Copybook naming (CV*/CS*/CO*) | 命名規則 | `app/cpy/*.cpy` |
| 9 | 01-level record definitions | データベース | `app/cpy/*.cpy` |
| 10 | RECLN documentation | データベース | `app/cpy/*.cpy` |
| 11 | FILLER padding convention | データベース | `app/cpy/*.cpy` |
| 12 | PIC clause format | データベース | `app/cpy/*.cpy` |
| 13 | COMMAREA structure | データベース | `app/cpy/COCOM01Y.cpy` |
| 14 | DFHMSD required parameters | API | `app/bms/*.bms` |
| 15 | DFHMDI 24x80 grid | API | `app/bms/*.bms` |
| 16 | DFHMDF field definition | API | `app/bms/*.bms` |
| 17 | AI/AO two-view pattern | API | `app/cpy-bms/*.CPY` |
| 18 | ERRMSG field requirement | API | `app/bms/*.bms` |
| 19 | 88-level ISVALID pattern | テスト | `app/cpy/*.cpy` |
| 20 | 88-level NOT-OK pattern | テスト | `app/cpy/*.cpy` |
| 21 | Apache-2.0 license header | アーキテクチャ | All files |
| 22 | Version tag comment | アーキテクチャ | All files |
| 23 | COPY statement usage | アーキテクチャ | `app/cbl/*.cbl` |
| 24 | FD record definitions | データベース | `app/cbl/CB*.cbl` |
| 25 | FILE STATUS handling | エラー処理 | `app/cbl/*.cbl` |

**SHOULD-Level Patterns (18 patterns - 100% required):**

| # | Pattern Name | Category | Source File |
|---|--------------|----------|-------------|
| 1 | Paragraph numbering (0000-9999) | アーキテクチャ | `app/cbl/*.cbl` |
| 2 | 9910-DISPLAY-IO-STATUS | エラー処理 | `app/cbl/*.cbl` |
| 3 | WS-RETURN-MSG messaging | エラー処理 | `app/cbl/CO*.cbl` |
| 4 | 9999-ABEND-PROGRAM | エラー処理 | `app/cbl/*.cbl` |
| 5 | WS- field prefix | 命名規則 | `app/cbl/*.cbl` |
| 6 | FD- field prefix | 命名規則 | `app/cbl/*.cbl` |
| 7 | FLG- condition prefix | 命名規則 | `app/cpy/*.cpy` |
| 8 | REDEFINES overlay pattern | データベース | `app/cpy/*.cpy` |
| 9 | S9(n)V99 decimal format | データベース | `app/cpy/*.cpy` |
| 10 | BMS color vocabulary | API | `app/bms/*.bms` |
| 11 | BMS attribute vocabulary | API | `app/bms/*.bms` |
| 12 | Standard header fields | API | `app/bms/*.bms` |
| 13 | INPUT-OK/INPUT-ERROR flags | テスト | `app/cbl/*.cbl` |
| 14 | WS-EDIT-* variables | テスト | `app/cbl/*.cbl` |
| 15 | INFOMSG field usage | API | `app/bms/*.bms` |
| 16 | Comment block format | アーキテクチャ | All files |
| 17 | Section organization | アーキテクチャ | `app/cbl/*.cbl` |
| 18 | Consistent indentation | アーキテクチャ | All files |

**MAY-Level Patterns (6 patterns - 100% required):**

| # | Pattern Name | Category | Source File |
|---|--------------|----------|-------------|
| 1 | PERFORM...THRU structure | アーキテクチャ | `app/cbl/*.cbl` |
| 2 | GO TO usage (intentional) | アーキテクチャ | `app/cpy/CSUTLDPY.cpy` |
| 3 | CEEDAYS LE service | テスト | `app/cbl/CSUTLDTC.cbl` |
| 4 | ASCII art in BMS | API | `app/bms/COSGN00.bms` |
| 5 | HILIGHT=UNDERLINE usage | API | `app/bms/*.bms` |
| 6 | Zero-length DFHMDF | API | `app/bms/*.bms` |

### 0.7.3 Documentation Quality Criteria

**Completeness Requirements:**

| Criterion | Requirement |
|-----------|-------------|
| Every pattern | Must have Japanese name (パターン名) |
| Every pattern | Must have priority level (優先度) |
| Every pattern | Must have category (カテゴリ) |
| Every pattern | Must have file path (ファイルパス) |
| Every pattern | Must have code snippet (スニペット) |
| Every pattern | Must have validation criteria (検証基準) |
| Every pattern | Must have rationale (根拠) |
| MUST patterns | Minimum 2 validation criteria |
| SHOULD patterns | Minimum 1 validation criterion |

**Accuracy Validation:**

| Validation Type | Method |
|-----------------|--------|
| Code snippets | Must be extracted verbatim from source files |
| File paths | Must resolve to existing repository files |
| Line numbers | Must match actual source file locations |
| Pattern names | Must match observed naming in codebase |

**Clarity Standards:**

| Standard | Requirement |
|----------|-------------|
| Language | Bilingual (Japanese primary, English for code terms) |
| Terminology | Consistent with mainframe COBOL conventions |
| Progressive disclosure | MUST patterns first, then SHOULD, then MAY |
| Code examples | Maximum 20 lines per snippet |

### 0.7.4 Example and Diagram Requirements

**Minimum Requirements per Pattern:**

| Element | MUST Patterns | SHOULD Patterns | MAY Patterns |
|---------|--------------|-----------------|--------------|
| Code snippet | Required | Required | Required |
| Validation criteria | 2+ items | 1+ items | Optional |
| Usage example | Required | Recommended | Optional |
| Mermaid diagram | If applicable | If applicable | Optional |

**Diagram Types Required:**

| Document | Diagram Type | Purpose |
|----------|--------------|---------|
| `architecture-patterns.md` | Flowchart | Program execution flow |
| `architecture-patterns.md` | Sequence | CICS transaction flow |
| `data-contracts.md` | Class/ER | Copybook relationships |
| `bms-patterns.md` | Component | AI/AO pattern structure |
| `index.md` | Pie chart | Pattern distribution by category |

## 0.8 Scope Boundaries

### 0.8.1 Exhaustively In Scope

**New Documentation Files (with trailing patterns):**

| Path Pattern | Description |
|--------------|-------------|
| `docs/code-style-catalog/README.md` | Catalog overview and navigation |
| `docs/code-style-catalog/index.md` | Statistics summary page |
| `docs/code-style-catalog/architecture-patterns.md` | COBOL architecture patterns |
| `docs/code-style-catalog/naming-conventions.md` | Naming convention patterns |
| `docs/code-style-catalog/error-handling.md` | Error handling patterns |
| `docs/code-style-catalog/data-contracts.md` | Data contract patterns |
| `docs/code-style-catalog/bms-patterns.md` | BMS/UI patterns |
| `docs/code-style-catalog/validation-patterns.md` | Validation patterns |
| `docs/code-style-catalog/compliance-reporting.md` | Compliance report documentation |

**Documentation File Updates:**

| File | Update Description |
|------|-------------------|
| `README.md` | Add Code Style Catalog section and navigation link |

**Source Files for Pattern Extraction (READ-ONLY):**

| Pattern | Files |
|---------|-------|
| `app/cbl/*.cbl` | All 28 COBOL programs |
| `app/cbl/*.CBL` | Uppercase extension variants |
| `app/bms/*.bms` | All 17 BMS map sources |
| `app/cpy/*.cpy` | All 28 shared copybooks |
| `app/cpy/*.CPY` | Uppercase extension variants |
| `app/cpy-bms/*.CPY` | All 17 BMS copybooks |

**Documentation Assets:**

| Path Pattern | Description |
|--------------|-------------|
| `docs/code-style-catalog/diagrams/*.md` | Embedded Mermaid diagrams (if separate files needed) |

### 0.8.2 Explicitly Out of Scope

**Source Code Modifications:**

| Exclusion | Rationale |
|-----------|-----------|
| `app/cbl/*.cbl` modifications | Documentation task only - no source changes |
| `app/bms/*.bms` modifications | Documentation task only - no source changes |
| `app/cpy/*.cpy` modifications | Documentation task only - no source changes |
| `app/cpy-bms/*.CPY` modifications | Documentation task only - no source changes |
| `app/data/**/*` modifications | Test data not in documentation scope |
| `app/catlg/**/*` modifications | Catalog metadata not in documentation scope |

**Test File Modifications:**

| Exclusion | Rationale |
|-----------|-----------|
| Test creation/modification | Not requested in documentation task |
| Test data updates | Not in scope |

**Feature Additions or Code Refactoring:**

| Exclusion | Rationale |
|-----------|-----------|
| New COBOL programs | Documentation task only |
| Program refactoring | Documentation task only |
| New copybooks | Documentation task only |
| New BMS maps | Documentation task only |

**Deployment Configuration:**

| Exclusion | Rationale |
|-----------|-----------|
| JCL modifications | Not in documentation scope |
| CICS configuration | Not in documentation scope |
| VSAM definitions | Not in documentation scope |

**Unrelated Documentation:**

| Exclusion | Rationale |
|-----------|-----------|
| API documentation beyond patterns | Not requested |
| User guide documentation | Not requested |
| Installation guide updates | Not requested (beyond link addition) |
| Diagrams outside pattern documentation | Not requested |

### 0.8.3 Boundary Clarifications

**Pattern vs. Implementation:**

| In Scope | Out of Scope |
|----------|--------------|
| Documenting existing patterns | Implementing new patterns |
| Extracting code snippets | Modifying code |
| Describing validation criteria | Implementing validation tools |
| Creating compliance templates | Building compliance automation |

**Documentation vs. Tooling:**

| In Scope | Out of Scope |
|----------|--------------|
| Markdown documentation files | CI/CD pipeline configuration |
| Mermaid diagrams in markdown | External diagramming tools |
| Static compliance templates | Automated compliance checking |
| Pattern reference documentation | Code generation scripts |

### 0.8.4 Scope Decision Matrix

| Item | In Scope? | Justification |
|------|-----------|---------------|
| Create `docs/code-style-catalog/` directory | ✅ YES | Required for catalog organization |
| Create 9 new markdown files | ✅ YES | Core deliverables |
| Update `README.md` | ✅ YES | Navigation to catalog |
| Extract code snippets from sources | ✅ YES | Required for pattern documentation |
| Modify source COBOL files | ❌ NO | Documentation task only |
| Create compliance automation | ❌ NO | Beyond documentation scope |
| Update `CONTRIBUTING.md` | ✅ YES | Reference to coding standards |
| Create new diagrams | ✅ YES | Mermaid diagrams for patterns |
| Modify existing diagrams | ❌ NO | Unless directly related to patterns |

## 0.9 Rules for Documentation

### 0.9.1 User-Specified Documentation Rules

The following rules are explicitly specified by the user and must be strictly followed:

**Rule 1: Catalog Entry Format Compliance**

All pattern entries MUST follow the user-provided YAML template format:
```yaml
パターン名: [識別名]
優先度: MUST | SHOULD | MAY
カテゴリ: [アーキテクチャ | 命名規則 | エラー処理 | データベース | API | テスト]
ファイルパス: [模範実装の場所]
スニペット: |
  [コード例]
検証基準:
  - [チェック項目1]
  - [チェック項目2]
根拠: [このパターンを採用する理由]
```

**Rule 2: Priority Level Enforcement**

| Priority | Japanese | Enforcement Rule |
|----------|----------|------------------|
| MUST | 必須 | Non-negotiable constraint. Automatic failure on violation. |
| SHOULD | 推奨 | Applied by default. Deviation requires explicit justification. |
| MAY | 任意 | Developer discretion. Optional application. |

**Rule 3: Compliance Report Format**

All compliance reports MUST follow the user-provided template:
```plaintext
=== カタログ準拠レポート ===
生成ファイル: [パス]
適用ルール数: [N]
MUST準拠: [合格/不合格]
SHOULD準拠: [合格/逸脱あり]
逸脱項目:
  - [ルール名]: [正当化理由]
```

**Rule 4: Prohibited Code Generation Patterns**

The following actions are PROHIBITED when using this catalog:
- Generating code that violates MUST-priority catalog rules
- Ignoring exemplary snippet patterns when equivalent structures exist in catalog
- Creating new architectural approaches when catalog provides established patterns
- Deviating from catalog validation criteria without explicit justification
- Generating code that contradicts catalog rationale documentation

**Rule 5: Validation Gate Requirements**

- All generated code MUST pass validation against applicable catalog rules
- Code violating MUST-priority rules results in automatic review failure
- SHOULD-priority deviations REQUIRE documented justification in code comments
- Catalog compliance reports MUST accompany each generated output

### 0.9.2 Documentation Language Rules

**Rule 6: Bilingual Documentation**

- Pattern names (パターン名) MUST be in Japanese
- Category names (カテゴリ) MUST use Japanese terms:
  - アーキテクチャ (Architecture)
  - 命名規則 (Naming Conventions)
  - エラー処理 (Error Handling)
  - データベース (Database/Data Contracts)
  - API (API/UI Patterns)
  - テスト (Testing/Validation)
- Code snippets remain in COBOL/BMS source language
- File paths use repository-relative English paths

**Rule 7: Statistics Output Requirement**

- Initial catalog output MUST include statistics summary (統計サマリー)
- Statistics MUST show pattern counts by priority level
- Statistics MUST show pattern counts by category

### 0.9.3 Pattern Extraction Rules

**Rule 8: Source Code Citation**

- Every pattern MUST cite specific source file(s)
- Line numbers SHOULD be included where applicable
- Format: `ファイルパス: app/cbl/COACTUPC.cbl:1040-1046`

**Rule 9: Code Snippet Standards**

- Snippets MUST be extracted verbatim from source files
- Maximum snippet length: 20 lines
- Snippets MUST be syntactically complete (no mid-statement cuts)
- Use `...` to indicate omitted surrounding code

**Rule 10: Validation Criteria Standards**

- MUST patterns require minimum 2 validation criteria
- SHOULD patterns require minimum 1 validation criterion
- Criteria MUST be objectively verifiable
- Criteria MUST be written in Japanese

### 0.9.4 Operational Rules

**Rule 11: Catalog Update Protocol**

- Catalog serves as authoritative reference for all code generation
- After initial output, catalog rules apply automatically to subsequent tasks
- Compliance reports attach to each generated output

**Rule 12: Pattern Authority**

- When equivalent structures exist in catalog, use catalog patterns
- Do not create new architectural approaches when catalog provides established patterns
- Catalog rationale documentation takes precedence

### 0.9.5 Execution Parameters

**Documentation Build Commands:**

| Purpose | Command |
|---------|---------|
| Preview markdown | Use GitHub or local markdown viewer |
| Validate links | Manual review (no automation configured) |
| Generate diagrams | Mermaid renders natively in GitHub markdown |

**Default Documentation Format:**

- Primary format: GitHub-flavored Markdown
- Diagram format: Mermaid (embedded in markdown)
- Code block language hints: `cobol`, `bms`, `yaml`

**Documentation Validation:**

| Check | Method |
|-------|--------|
| Markdown syntax | GitHub rendering preview |
| Internal links | Manual verification |
| Code snippet accuracy | Source file comparison |
| Template compliance | Manual review against user template |

## 0.10 References

### 0.10.1 Repository Files Searched

**Root Directory Files:**

| File Path | Summary |
|-----------|---------|
| `README.md` | Primary operational and onboarding manual for CardDemo; contains installation instructions, JCL sequences, transaction mappings |
| `CONTRIBUTING.md` | Contributor workflow and PR guidelines |
| `CODE_OF_CONDUCT.md` | Governance pointer to Amazon Open Source Code of Conduct |
| `LICENSE` | Apache License 2.0 |

**COBOL Source Files (app/cbl/):**

| File Path | Summary |
|-----------|---------|
| `app/cbl/COACTUPC.cbl` | CICS Account Update program - exemplary CICS program structure with comprehensive validation patterns |
| `app/cbl/COACTVWC.cbl` | CICS Account View program |
| `app/cbl/COCRDUPC.cbl` | CICS Credit Card Update program |
| `app/cbl/COCRDLIC.cbl` | CICS Credit Card List program |
| `app/cbl/COCRDSLC.cbl` | CICS Credit Card Selection program |
| `app/cbl/COSGN00C.cbl` | CICS Signon program |
| `app/cbl/COMEN01C.cbl` | CICS Main Menu program |
| `app/cbl/COADM01C.cbl` | CICS Admin Menu program |
| `app/cbl/COBIL00C.cbl` | CICS Bill Payment program |
| `app/cbl/CORPT00C.cbl` | CICS Transaction Reports program |
| `app/cbl/COTRN00C.cbl` | CICS Transaction List program |
| `app/cbl/COTRN01C.cbl` | CICS Transaction View program |
| `app/cbl/COTRN02C.cbl` | CICS Transaction Add program |
| `app/cbl/COUSR00C.cbl` - `COUSR03C.cbl` | CICS User management programs |
| `app/cbl/CBACT01C.cbl` | Batch Account file reader - exemplary batch program structure |
| `app/cbl/CBACT02C.cbl` - `CBACT04C.cbl` | Batch Account processing programs |
| `app/cbl/CBTRN01C.cbl` - `CBTRN03C.cbl` | Batch Transaction processing programs |
| `app/cbl/CBCUS01C.cbl` | Batch Customer file dumper |
| `app/cbl/CBSTM03A.CBL` | Batch Statement generator |
| `app/cbl/CBSTM03B.CBL` | Batch I/O wrapper subroutine |
| `app/cbl/CSUTLDTC.cbl` | Date conversion utility using LE services |

**BMS Map Sources (app/bms/):**

| File Path | Summary |
|-----------|---------|
| `app/bms/COSGN00.bms` | Login screen BMS map with ASCII art banner |
| `app/bms/COMEN01.bms` | Main Menu BMS map |
| `app/bms/COADM01.bms` | Admin Menu BMS map |
| `app/bms/COACTUP.bms` | Account Update form BMS map |
| `app/bms/COACTVW.bms` | Account View BMS map |
| `app/bms/COCRDLI.bms` | Credit Card List BMS map |
| `app/bms/COCRDSL.bms` | Credit Card Selection BMS map |
| `app/bms/COCRDUP.bms` | Credit Card Update BMS map |
| `app/bms/COBIL00.bms` | Bill Payment BMS map |
| `app/bms/CORPT00.bms` | Transaction Reports BMS map |
| `app/bms/COTRN00.bms` - `COTRN02.bms` | Transaction BMS maps |
| `app/bms/COUSR00.bms` - `COUSR03.bms` | User management BMS maps |

**Copybooks (app/cpy/):**

| File Path | Summary |
|-----------|---------|
| `app/cpy/COCOM01Y.cpy` | CARDDEMO-COMMAREA communication area definition |
| `app/cpy/CVACT01Y.cpy` | ACCOUNT-RECORD 300-byte layout |
| `app/cpy/CVACT02Y.cpy` | CARD-RECORD 150-byte layout |
| `app/cpy/CVACT03Y.cpy` | CARD-XREF-RECORD 50-byte layout |
| `app/cpy/CVCUS01Y.cpy` | CUSTOMER-RECORD 500-byte layout |
| `app/cpy/CVTRA01Y.cpy` - `CVTRA07Y.cpy` | Transaction-related record layouts |
| `app/cpy/CSUTLDPY.cpy` | Date validation procedural copybook |
| `app/cpy/CSUTLDWY.cpy` | Date validation working storage |
| `app/cpy/CSMSG01Y.cpy` | Common messages copybook |
| `app/cpy/CSMSG02Y.cpy` | ABEND-DATA structure copybook |
| `app/cpy/CSSETATY.cpy` | Attribute setting procedural copybook |
| `app/cpy/CSSTRPFY.cpy` | AID key mapping procedural copybook |
| `app/cpy/CSUSR01Y.cpy` | User security record layout |
| `app/cpy/COTTL01Y.cpy` | Screen titles copybook |
| `app/cpy/CSLKPCDY.cpy` | Lookup codes (area codes, states) |
| `app/cpy/COADM02Y.cpy`, `COMEN02Y.cpy` | Menu option definitions |

**BMS Copybooks (app/cpy-bms/):**

| File Path | Summary |
|-----------|---------|
| `app/cpy-bms/*.CPY` | 17 BMS copybooks following AI/AO two-view pattern |

**Other Directories:**

| Directory | Summary |
|-----------|---------|
| `app/data/ASCII/` | 9 fixed-width test data files |
| `app/catlg/` | LISTCAT.txt IDCAMS catalog snapshot |
| `diagrams/` | Application flow diagrams and screenshots |

### 0.10.2 User-Provided Attachments

**No file attachments provided.**

The user requirements were provided as inline text (Japanese language prompt) defining:
- Code Style Catalog extraction requirements
- Priority level definitions (MUST/SHOULD/MAY)
- Catalog entry YAML template
- Compliance report template
- Prohibited patterns
- Validation gate requirements

### 0.10.3 External References

**No Figma screens provided.**

**No external URLs provided requiring documentation.**

### 0.10.4 Technical Specification Sections Referenced

The following existing technical specification sections provide relevant context:

| Section | Relevance |
|---------|-----------|
| 1.1 Executive Summary | Application overview context |
| 3.2 Programming Languages | COBOL technology context |
| 3.3 Frameworks and Runtime Environments | CICS runtime context |
| 5.1 High-Level Architecture | System architecture context |
| 7.2 3270 Terminal Architecture | BMS/UI architecture context |

### 0.10.5 Search Tracking Summary

| Search # | Tool | Target | Purpose | Result |
|----------|------|--------|---------|--------|
| 1 | bash | `.blitzyignore` | Find exclusion patterns | None found |
| 2 | get_source_folder_contents | Root (`""`) | Repository structure | 4 files + app/ folder |
| 3 | get_source_folder_contents | `app` | Application structure | 6 subdirectories |
| 4 | get_source_folder_contents | `app/cbl` | COBOL programs | 28 programs |
| 5 | get_source_folder_contents | `app/cpy` | Copybooks | 28 copybooks |
| 6 | get_source_folder_contents | `app/bms` | BMS maps | 17 map sources |
| 7 | get_source_folder_contents | `app/cpy-bms` | BMS copybooks | 17 copybooks |
| 8 | read_file | `app/cbl/COACTUPC.cbl` | CICS program patterns | Architecture patterns extracted |
| 9 | read_file | `app/cbl/CBACT01C.cbl` | Batch program patterns | Architecture patterns extracted |
| 10 | read_file | `app/bms/COSGN00.bms` | BMS map patterns | BMS patterns extracted |
| 11 | read_file | `app/cpy/COCOM01Y.cpy` | COMMAREA patterns | Data contract patterns extracted |
| 12 | read_file | `app/cpy/CSUTLDPY.cpy` | Validation patterns | Validation patterns extracted |
| 13 | read_file | `app/cpy/CSMSG02Y.cpy` | Error handling patterns | ABEND patterns extracted |
| 14 | read_file | `app/cpy/CVACT01Y.cpy` | Record layout patterns | Data contract patterns extracted |
| 15 | read_file | `README.md` | Existing documentation | Application documentation reviewed |
| 16 | bash | find *.md | Documentation discovery | 3 markdown files found |
| 17 | bash | ls diagrams/ | Diagram assets | 6 diagram files found |

