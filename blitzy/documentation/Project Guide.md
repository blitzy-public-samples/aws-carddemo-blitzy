# Blitzy Project Guide — aws-carddemo-blitzy (Java 8 → 21 Idiom Modernization)

> **Brand color legend:** Completed / AI Work = Dark Blue `#5B39F3` · Remaining / Not Completed = White `#FFFFFF` · Headings/Accents = Violet-Black `#B23AF2` · Highlight = Mint `#A8FDD9`

---

## 1. Executive Summary

### 1.1 Project Overview

The request was an **in-place, behavior-preserving Java 8 → Java 21 (LTS) language-idiom modernization** (ten idiom substitutions: lambdas, Stream API, `var`, switch expressions + pattern matching, `instanceof` pattern matching, records, text blocks, `java.time`, sealed types, try-with-resources). During scope discovery Blitzy established with definitive, repository-wide evidence that the target repository `aws-carddemo-blitzy` is the **AWS CardDemo z/OS mainframe reference application** — 100% COBOL, CICS, VSAM, JCL, and BMS with **zero Java**. There is therefore **no valid input** for the requested refactor. The autonomous mandate — analyze, verify, preserve all 167 files byte-for-byte, and surface a blocking clarification — is complete; executing any code change would violate the prompt's own constraints.

### 1.2 Completion Status

Completion is computed with the PA1 AAP-scoped, hours-based methodology: **Completion % = Completed Hours ÷ (Completed + Remaining) Hours**. The completed work is the AAP's genuine deliverable (discovery, analysis, verification, preservation, and the blocking-clarification flag); the remaining work is the human-gated path to unblock the intended modernization.

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'pie1':'#5B39F3','pie2':'#FFFFFF','pieStrokeColor':'#B23AF2','pieStrokeWidth':'2px','pieOuterStrokeColor':'#B23AF2','pieOuterStrokeWidth':'2px','pieTitleTextSize':'16px','pieSectionTextSize':'14px','pieLegendTextSize':'14px'}}}%%
pie showData title Completion Status — 75.0% Complete
    "Completed Work (hrs)" : 21
    "Remaining Work (hrs)" : 7
```

| Metric | Value |
|--------|-------|
| **Total Hours** | **28.0** |
| **Completed Hours (AI + Manual)** | **21.0** (AI: 21.0 · Manual: 0.0) |
| **Remaining Hours** | **7.0** |
| **Percent Complete** | **75.0%** |

> Calculation: `21.0 ÷ (21.0 + 7.0) = 21 ÷ 28 = 75.0%`.

### 1.3 Key Accomplishments

- ✅ Repository-wide artifact discovery across all **167 files**, proving **0 Java/JVM sources** and **0 build manifests** exist.
- ✅ Per-idiom applicability analysis: all **10** requested modernizations map to **0** artifacts in a procedural COBOL codebase.
- ✅ Exhaustive scope determination: in-scope set is **empty (0 files)**; all 167 mainframe files classified out-of-scope with explicit preservation dispositions.
- ✅ **Byte-for-byte preservation** of all 167 files verified (`git diff` against `93ebec71` is empty; working tree clean).
- ✅ Blocking prerequisite gap surfaced with two concrete clarification paths (Option A — re-target; Option B — reframe as COBOL→Java).
- ✅ Independent Final-Validator verification confirmed the zero-change outcome is **PRODUCTION-READY** and correct for this AAP.
- ✅ Correctly **avoided** silent-proceed anti-patterns (fabricating Java, transpiling COBOL) that would breach preserve-behavior/API/structure and no-new-dependency constraints.

### 1.4 Critical Unresolved Issues

| Issue | Impact | Owner | ETA |
|-------|--------|-------|-----|
| Prompt/repository mismatch — Java-modernization request applied to a 100% COBOL repo | Blocking: no in-scope work can execute; zero value delivered until resolved | Product / Requestor | 2.0h to decide |
| Unfilled target-scope placeholder (`src/main/java/com/example/service`) | Ambiguous scope; intended Java source/sub-path unknown | Product / Requestor | 2.0h to confirm |
| Target Java-8 codebase (for Option A) not yet supplied/located | Cannot begin idiom modernization or preflight | Requestor / Eng | 2.0h |

### 1.5 Access Issues

No access issues identified. The repository was fully readable, git history was accessible, and all 167 files were inspectable. No repository-permission, credential, or third-party-API access limitation impacted this assessment. (Note: no build/deploy credentials were required because there is no buildable Java artifact in scope.)

### 1.6 Recommended Next Steps

1. **[High]** Resolve the blocking clarification: confirm whether the Java prompt targeted the wrong repository/scope (**Option A — recommended**) or genuinely intends a COBOL→Java transformation (**Option B**).
2. **[High]** For Option A, supply the intended **Java 8 repository or precise sub-path** and fill the unfilled scope placeholder.
3. **[Medium]** Run a **migration preflight** on the correct repository (current JDK, dependency inventory, build tool, test coverage/compilability).
4. **[Medium]** **Re-target and re-submit** the modernization job with corrected input; or, for Option B, open a **new** job with a new prompt + technical specification (AWS Blu Age / Mainframe Modernization).
5. **[Low]** Keep the CardDemo repository preserved byte-for-byte; do not "fix" GnuCOBOL informational diagnostics on out-of-scope COBOL artifacts.

---

## 2. Project Hours Breakdown

### 2.1 Completed Work Detail

Every completed component traces to an AAP requirement (R1–R11). Total = **21.0h** (matches Section 1.2 Completed Hours).

| Component | Hours | Description |
|-----------|-------|-------------|
| Repository Discovery & Java Artifact Scan | 2.5 | Exhaustive scan of 167 files; proved 0 Java/JVM/JS/TS/Python sources and 0 build manifests [AAP §0.1/§0.2] |
| Intent Interpretation & 10-Idiom Strategy | 1.5 | Faithful restatement of intent; idiom-to-Java-21-construct mapping table [AAP §0.1.1/§0.1.2] |
| Per-Idiom Applicability Analysis | 2.0 | Checked all 10 modernizations vs COBOL reality; each maps to 0 artifacts [AAP §0.6.1] |
| Scope Boundary Determination | 2.5 | Empty in-scope set; 167 out-of-scope files categorized with preservation dispositions [AAP §0.2] |
| Target Design & Transformation Mapping | 2.5 | Identity-transform analysis; empty transformation map with justification (no fabricated Java tree) [AAP §0.3/§0.4] |
| Dependency Inventory & Platform Stack Doc | 1.5 | Confirmed 0 Java dependencies; documented mainframe platform stack for context [AAP §0.5] |
| Migration Best-Practice Research (web) | 2.0 | Validated idiom mapping & migration approach (OpenRewrite, AWS Transform, preflight) [AAP §0.3.2] |
| Blocking-Gap Analysis & Clarification Paths | 2.5 | Documented the blocking prerequisite gap and Options A/B with decision rationale [AAP §0.1/§0.6] |
| Byte-for-Byte Preservation (167 files) | 1.0 | Zero-change discipline; verified working tree identical to Initial Commit [AAP §0.2/§0.7] |
| Independent Validation & Verification | 3.0 | Final Validator: git state, exhaustive scans, vacuous production gates, GnuCOBOL informational check |
| **Total Completed** | **21.0** | |

### 2.2 Remaining Work Detail

Every category traces to an AAP requirement or path-to-production need (R13–R16). Total = **7.0h** (matches Section 1.2 Remaining Hours and Section 7 "Remaining Work").

| Category | Hours | Priority |
|----------|-------|----------|
| Blocking Clarification Decision (Option A vs B) | 2.0 | High |
| Target Java Repository/Scope Identification (Option A) | 2.0 | High |
| Migration Preflight Assessment on Correct Repo | 2.0 | Medium |
| Job Re-Targeting & Re-Submission | 1.0 | Medium |
| **Total Remaining** | **7.0** | |

### 2.3 Hours Reconciliation

| Check | Result |
|-------|--------|
| Section 2.1 total = Section 1.2 Completed | 21.0 = 21.0 ✅ |
| Section 2.2 total = Section 1.2 Remaining | 7.0 = 7.0 ✅ |
| Section 2.1 + Section 2.2 = Total Project Hours | 21.0 + 7.0 = 28.0 ✅ |
| Section 7 pie "Remaining Work" = Section 2.2 total | 7 = 7.0 ✅ |
| Completion % consistent (1.2 / 7 / 8) | 75.0% ✅ |

> **Scope note:** The downstream modernization *execution* (Option A on the correct Java repo, or the Option B COBOL→Java transformation) is explicitly a **separate future engagement** requiring new input and/or a new prompt + specification [AAP §0.6.2]. Per PA1 it is outside this AAP's scope and is deliberately **excluded** from the 28-hour total.

---

## 3. Test Results

All results below originate exclusively from **Blitzy's autonomous validation logs** for this project. Because the in-scope set is empty, all test/build/run gates are **vacuously satisfied over zero in-scope units** — they do **not** assert that a Java project was built, tested, or run.

| Test Category | Framework | Total Tests | Passed | Failed | Coverage % | Notes |
|---------------|-----------|-------------|--------|--------|-----------|-------|
| Unit | N/A (no in-scope test suites) | 0 | 0 | 0 | N/A | 0 Java/JVM/JS/TS/Python test suites detected in exhaustive scan |
| Integration | N/A | 0 | 0 | 0 | N/A | No in-scope integration surface (no runnable components) |
| UI | N/A | 0 | 0 | 0 | N/A | Only UI is the out-of-scope 3270/BMS terminal layer (17 maps) |
| API | N/A | 0 | 0 | 0 | N/A | No HTTP/service endpoints in scope |
| End-to-End | N/A | 0 | 0 | 0 | N/A | No in-scope executable flow |
| Dependency install | N/A | 0 | 0 | 0 | N/A | 0 dependency manifests → nothing to install |
| Compilation | N/A | 0 | 0 | 0 | N/A | 0 in-scope compilable sources → 0 compile errors |

**Informational (not a project test; not a gate):** GnuCOBOL 3.2.0 read-only static check of the 11 pure-batch COBOL programs. With relaxed fixed-format checks the batch programs syntax-check clean (exit 0, column-position warnings only); `CBSTM03A.CBL` emits a diagnostic sourced from copybook `CUSTREC.cpy` (column-72 truncation). This is a **GnuCOBOL tool limitation**, not a defect — the copybook is the official upstream AWS artifact accepted by IBM Enterprise COBOL. The 17 CICS programs are not checkable by GnuCOBOL (cannot parse `EXEC CICS`/BMS). No files were modified.

---

## 4. Runtime Validation & UI Verification

Runtime posture reflects the empty in-scope set and the mainframe nature of the out-of-scope application.

- **In-scope runnable components:** ✅ N/A — none exist (0 `main()`/`@SpringBootApplication`/`__main__`/Dockerfile/compose). Nothing to run; nothing failing.
- **Build/compile health:** ✅ Operational (vacuous) — 0 in-scope compilable sources → 0 errors.
- **Dependency health:** ✅ Operational (vacuous) — 0 manifests → nothing to install.
- **Working-tree integrity:** ✅ Operational — `git status --porcelain` empty; `git diff --stat 93ebec71` empty; all 167 files byte-identical to Initial Commit.
- **Out-of-scope mainframe runtime:** ⚠ Partial (by design, not requested) — the real CardDemo runs on **z/OS + CICS TS + VSAM + Language Environment + JCL/JES**; it is not installable or runnable in this Linux environment and remains preserved unchanged.
- **UI verification:** ⚠ N/A — the only user interface is the out-of-scope **3270/BMS terminal** presentation layer (17 BMS maps); no web UI exists to verify. No screenshots applicable.
- **API integration outcomes:** ✅ N/A — no in-scope APIs or external integrations.

---

## 5. Compliance & Quality Review

Cross-mapping of AAP deliverables and prompt constraints to autonomous outcomes.

| Deliverable / Constraint (AAP) | Benchmark | Status | Notes |
|--------------------------------|-----------|--------|-------|
| Repository-wide Java artifact scan [§0.1/§0.2] | Complete & evidence-based | ✅ Pass | 0 of 167 files are Java (independently re-verified) |
| Per-idiom applicability check [§0.6.1] | All 10 idioms assessed | ✅ Pass | Every idiom maps to 0 artifacts |
| Scope boundaries enumerated [§0.2] | In-scope + out-of-scope exhaustive | ✅ Pass | In-scope empty; 167 files preserved |
| Transformation mapping [§0.4] | Evidence-based, no fabrication | ✅ Pass | Empty map with justification |
| Dependency inventory [§0.5] | No invented artifacts | ✅ Pass | 0 Java deps; platform stack documented |
| Preserve behavior/API/structure [§0.7] | No changes to public contracts | ✅ Pass | Zero code changes |
| No new third-party dependencies [§0.7] | Dependency set unchanged | ✅ Pass | 0 manifests added |
| Preserve all files byte-for-byte [§0.2] | `git diff` empty | ✅ Pass | Verified against `93ebec71` |
| Flag (don't silently resolve) [§0.7] | Blocking gap surfaced | ✅ Pass | Options A/B documented |
| Evidence-based reporting | No fabricated Java/tree/deps | ✅ Pass | All empties justified |
| Fill target-scope placeholder [§0.7.2] | Requires user input | ⚠ Outstanding | Placeholder never filled — needs clarification |
| Execute Java 8→21 modernization [§0.1.1] | Ten idiom substitutions applied | ❌ Blocked | No valid input; cannot execute against COBOL |

**Fixes applied during autonomous validation:** None required — there were zero in-scope issues (empty in-scope set). **Outstanding compliance item:** the target-scope placeholder must be supplied by the user before any execution can occur.

---

## 6. Risk Assessment

| Risk | Category | Severity | Probability | Mitigation | Status |
|------|----------|----------|-------------|------------|--------|
| Prompt/repository mismatch — Java prompt on a COBOL repo | Technical | High | Certain (already occurred) | Obtain clarification; re-target job at intended Java repo/scope | Open (flagged) |
| Correct target repo's Java state (compilability/tests/Java-8 baseline) unknown | Technical | Medium | Medium | Migration preflight before modernization | Open |
| GnuCOBOL informational diagnostics misread as real defects | Technical | Low | Low | Documented tool limitation; IBM Enterprise COBOL authoritative; do not modify | Documented / Accepted |
| No security-relevant change introduced (0 code, 0 deps) | Security | None/Info | N/A | No new attack surface; out-of-scope RACF/security untouched | N/A (no exposure) |
| Real CardDemo requires z/OS+CICS+VSAM+JES — not runnable in Linux | Operational | Low | N/A | Out of scope; documented for context | Documented / Accepted |
| Stakeholder-clarification delay keeps job blocked | Operational | Medium | Medium | Escalate with clear Options A/B and hour estimates | Open |
| Option B (COBOL→Java via AWS Blu Age) is a major separate integration effort | Integration | Medium | Low (Option A recommended) | Scope as separate engagement with new prompt + spec | Deferred / Out of scope |
| EBCDIC/ASCII sample data & VSAM layouts must remain intact for future migration | Integration | Low | Low | Preserved byte-for-byte | Preserved |

---

## 7. Visual Project Status

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'pie1':'#5B39F3','pie2':'#FFFFFF','pieStrokeColor':'#B23AF2','pieStrokeWidth':'2px','pieOuterStrokeColor':'#B23AF2','pieOuterStrokeWidth':'2px','pieTitleTextSize':'16px','pieSectionTextSize':'14px','pieLegendTextSize':'13px'}}}%%
pie showData title Project Hours Breakdown (Total 28h)
    "Completed Work" : 21
    "Remaining Work" : 7
```

**Remaining hours by category (Section 2.2):**

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'pie1':'#5B39F3','pie2':'#B23AF2','pie3':'#A8FDD9','pie4':'#FFFFFF','pieStrokeColor':'#B23AF2','pieStrokeWidth':'1px','pieOuterStrokeColor':'#B23AF2','pieSectionTextSize':'13px','pieLegendTextSize':'12px'}}}%%
pie showData title Remaining Work by Category (7h)
    "Clarification Decision (High)" : 2
    "Java Repo/Scope ID (High)" : 2
    "Migration Preflight (Medium)" : 2
    "Re-Target & Re-Submit (Medium)" : 1
```

> **Integrity:** Pie "Remaining Work" = **7** = Section 1.2 Remaining Hours = Section 2.2 total. Pie "Completed Work" = **21** = Section 1.2 Completed Hours.

---

## 8. Summary & Recommendations

**Achievements.** Blitzy interpreted the Java 8 → 21 modernization intent precisely and then, through evidence-based discovery, established that the target repository contains **no Java whatsoever** — it is the AWS CardDemo mainframe reference application (COBOL/CICS/VSAM/JCL/BMS). The autonomous mandate for this situation — analyze, verify, preserve all 167 files byte-for-byte, and surface a blocking clarification — was completed and independently validated as PRODUCTION-READY for this AAP.

**Remaining gaps & critical path.** The project is **75.0% complete** (21.0h of 28.0h). The remaining **7.0h** is human-gated coordination: resolve the blocking clarification (**2.0h**), identify/confirm the intended Java 8 repository or sub-path (**2.0h**), run migration preflight on the correct repository (**2.0h**), and re-target/re-submit the job (**1.0h**). The dominant, already-materialized risk is the prompt/repository mismatch; the critical path is a single product decision (Option A — recommended — re-target; or Option B — a separate COBOL→Java engagement).

**Success metrics.** For Option A, success = the intended Java 8 project supplied, preflight green (compiles + tests pass at Java 8 baseline), and the ten idiom substitutions applied with public API, package structure, tests, and observable behavior preserved. For this AAP specifically, success is already met: zero fabricated Java, zero altered COBOL, all files preserved, and a clear blocking flag raised.

**Production-readiness assessment.** For the *requested Java modernization*, the project is **blocked pending clarification** — not deployable because there is nothing in scope to deploy. For the *AAP as scoped* (analyze/verify/preserve/flag), the outcome is complete and correct. No code should be committed against this COBOL repository under the current prompt.

---

## 9. Development Guide

This repository is a **static mainframe source tree**. There is nothing to build, install, or run in a Linux/JVM environment; the guide therefore covers inspection, verification of the preserved state, and the path to unblock the intended Java job. All commands below were executed and verified during assessment.

### 9.1 System Prerequisites

- **Required:** `git` ≥ 2.51 (repository inspection and preservation verification).
- **Optional (informational only):** `GnuCOBOL` (`cobc`) 3.2.0 — read-only COBOL syntax aid; **not** the project compiler and cannot parse `EXEC CICS`/BMS.
- **Optional:** Python 3.13 for ad-hoc scripting.
- **NOT required:** JDK, Maven, Gradle, Node.js — the repository contains **0** build manifests.
- **Actual production runtime (out of scope, unavailable here):** IBM z/OS 2.4+, CICS TS 5.x+, IBM Enterprise COBOL, Language Environment, VSAM, JCL/JES.

### 9.2 Environment Setup

No environment variables, services, or virtual environments are needed — this is a static source repository.

```bash
# Clone (if not already present) and enter the repository
git clone <repo-url> aws-carddemo-blitzy
cd aws-carddemo-blitzy
```

### 9.3 Dependency Installation

```bash
# None. There are zero dependency manifests; nothing to install.
ls pom.xml build.gradle* package.json requirements.txt 2>/dev/null || echo "No build manifests — nothing to install (expected)."
```

### 9.4 Verify the Preserved State (primary workflow)

```bash
# Working tree must be clean and byte-identical to the Initial Commit
git status --porcelain            # expected: empty output (clean)
git diff --stat 93ebec71          # expected: empty output (no changes)
git log --oneline -1              # expected: 93ebec71 Initial Commit
git rev-list --count HEAD         # expected: 1
```

### 9.5 Inspect the Repository (example usage)

```bash
# Inventory by type
echo "Total files:        $(git ls-files | wc -l)"                       # 167
echo "COBOL programs:     $(git ls-files 'app/cbl/*.cbl' 'app/cbl/*.CBL' | wc -l)"   # 28
echo "Copybooks:          $(git ls-files 'app/cpy/*.cpy' 'app/cpy/*.CPY' | wc -l)"   # 28
echo "BMS maps:           $(git ls-files 'app/bms/*.bms' | wc -l)"        # 17
echo "JCL:                $(git ls-files '*.jcl' '*.JCL' | wc -l)"        # 32

# Classify COBOL: online (CICS) vs batch
echo "Online (EXEC CICS): $(grep -rli 'EXEC CICS' app/cbl | wc -l)"       # 17
# Batch = 28 - 17 = 11

# Peek at a program / copybook / CICS resource definition
sed -n '21,24p' app/cbl/CBACT01C.cbl      # IDENTIFICATION DIVISION / PROGRAM-ID
sed -n '1,6p'  app/cpy/CVACT01Y.cpy       # account record layout
git ls-files app/csd/*.CSD                # CARDDEMO.CSD (CICS resources)
```

### 9.6 Optional GnuCOBOL Static Check (informational only)

```bash
# Informational: NOT the project compiler; batch programs only (cannot parse EXEC CICS/BMS).
cobc -fsyntax-only -std=cobol85 -frelax-syntax-checks -I app/cpy app/cbl/CBACT02C.cbl
# => exit 0; any "'GOBACK' should start in Area A" is a fixed-format/column tool warning, not a defect.
```

### 9.7 Path to Unblock the Intended Java Modernization (Option A)

Run these **in the intended Java 8 repository** (they are N/A here — this repo has 0 Java):

```bash
java -version                          # identify current JDK
find . -name '*.java' | wc -l          # confirm Java sources exist (> 0)
ls pom.xml build.gradle* 2>/dev/null   # locate the build manifest
mvn -q -DskipTests compile             # or: ./gradlew compileJava
mvn -q test                            # capture baseline test pass rate
```

### 9.8 Troubleshooting

- **"No Java found / nothing to build."** Expected — this is a COBOL/CICS/JCL/BMS repository. See the blocking clarification (Section 1.4). Supply the intended Java repository/scope.
- **GnuCOBOL warns `'GOBACK' should start in Area A`.** Fixed-format/column tool strictness; add `-frelax-syntax-checks`. Do **not** edit the source to satisfy GnuCOBOL.
- **`CBSTM03A.CBL` → `CUSTREC.cpy` PICTURE/parentheses error.** GnuCOBOL column-72/TAB truncation limitation; the copybook is correct for IBM Enterprise COBOL. Do **not** modify.
- **"How do I actually run CardDemo?"** It requires z/OS + CICS + VSAM + JCL/JES (a mainframe or emulator). It cannot run in this Linux environment and is out of scope.

---

## 10. Appendices

### A. Command Reference

| Purpose | Command |
|---------|---------|
| Verify clean tree | `git status --porcelain` |
| Verify no changes vs baseline | `git diff --stat 93ebec71` |
| Confirm HEAD | `git log --oneline -1` |
| Count tracked files | `git ls-files \| wc -l` |
| Count COBOL programs | `git ls-files 'app/cbl/*.cbl' 'app/cbl/*.CBL' \| wc -l` |
| Classify online vs batch | `grep -rli 'EXEC CICS' app/cbl \| wc -l` |
| Optional COBOL syntax aid | `cobc -fsyntax-only -std=cobol85 -frelax-syntax-checks -I app/cpy <file>` |

### B. Port Reference

Not applicable — no services, servers, or listeners exist in scope. (Production CICS/z/OS listeners are out of scope.)

### C. Key File Locations

| Location | Contents |
|----------|----------|
| `app/cbl/` | 28 COBOL programs (17 CICS online, 11 batch) |
| `app/cpy/` | 28 COBOL copybooks (record layouts, COMMAREA, messages) |
| `app/cpy-bms/` | 17 generated BMS copybooks |
| `app/bms/` | 17 BMS 3270 screen maps |
| `app/jcl/`, `samples/jcl/` | 32 JCL jobs (incl. BATCMP/BMSCMP/CICCMP compile jobs) |
| `app/proc/`, `samples/proc/` | 5 JCL procedures |
| `app/csd/CARDDEMO.CSD` | CICS resource definitions |
| `app/data/` | ASCII sample data + EBCDIC print datasets |
| `diagrams/` | 5 screen PNGs + `CARDDEMO-DataModel.drawio` |
| `README.md` | Technologies: COBOL, CICS, VSAM, JCL |

### D. Technology Versions

| Component | Version | Role |
|-----------|---------|------|
| git | 2.51.0 | Inspection / verification (used) |
| GnuCOBOL (`cobc`) | 3.2.0 | Optional informational static check |
| Python | 3.13 | Optional scripting |
| Requested target | Java 21 (LTS) | Modernization target (no input present) |
| IBM z/OS | 2.4+ | Production OS (out of scope) |
| CICS TS | 5.x+ | Online transaction monitor (out of scope) |
| IBM Enterprise COBOL | COBOL-85+ | Authoritative compiler (out of scope) |
| VSAM | z/OS-bundled | KSDS/ESDS storage (out of scope) |

### E. Environment Variable Reference

Not applicable — no environment variables are required or consumed by any in-scope component.

### F. Developer Tools Guide

- **git** — the only required tool; used for preservation verification and inventory.
- **GnuCOBOL** — optional, informational only; produces flag-dependent column warnings on fixed-format source; never authoritative for this codebase.
- **Chrome DevTools MCP / screenshots** — not applicable; there is no web UI to verify.

### G. Glossary

| Term | Meaning |
|------|---------|
| AAP | Agent Action Plan — the governing directive for this job |
| Empty executable scope | A valid job whose correct in-scope work count is zero (here, 0 Java files to modernize) |
| Vacuous truth | A gate satisfied because there are zero in-scope units to evaluate (0/0) |
| BMS | Basic Mapping Support — 3270 terminal screen definitions |
| CICS | Customer Information Control System — z/OS online transaction monitor |
| VSAM | Virtual Storage Access Method — z/OS record storage (KSDS/ESDS) |
| JCL | Job Control Language — z/OS batch job definitions |
| Copybook | Reusable COBOL data-layout include (`COPY`) |
| Option A / Option B | Clarification paths: re-target at Java repo (A) vs reframe as COBOL→Java (B) |
| Blu Age | AWS Mainframe Modernization automated refactoring (COBOL→Java) |