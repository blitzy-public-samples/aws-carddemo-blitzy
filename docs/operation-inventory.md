# Authoritative Operation Inventory

> **Finding #2 (MAJOR, repository-wide).** *"Actual scope is 434 operations / 432
> creation-equivalent / 409 non-assets, not 424 / 422 / 399. Publish the authoritative
> 0–433 inventory and synchronize all scope/count claims."*
>
> This document is that authoritative inventory. It **supersedes** the stale
> `424 / 422 / 399` acceptance figures. Every figure below is **regenerable from Git**
> (commands given inline), so the inventory can be re-verified at any time and can never
> silently drift again.

## 1. Purpose and Scope

This inventory enumerates every **operation** performed by the AWS CardDemo
COBOL → Java 25 / Spring Boot 3.5.16 migration on the destination branch, indexed
`0`–`433`. An *operation* is one of:

- **A** — a destination file created (new Java/resource/doc/test/deck artifact);
- **R100** — a legacy source file relocated `app/** → legacy/**` byte-identically
  (Git rename similarity 100 %), counted as one CREATE-equivalent operation at its new path;
- **M** — an in-place modification (only `README.md` at the checkpoint);
- **D** — the single `app/` directory-deletion meta-operation, recorded only after all
  148 children were verified byte-identical under `legacy/`.

> **This is a distinct inventory from the AAP §0.2.1 _source_ inventory of 148 legacy
> artifacts.** The 148-file figure counts the *legacy COBOL/JCL/BMS/data source* being
> migrated; the 434 figure counts the *operations* that realise the migration (relocations
> + net-new Java/resource/doc/test/deck artifacts + the README update + the `app/`
> deletion). Both are correct and measure different things; neither supersedes the other.

## 2. Methodology — how these numbers are regenerated

The checkpoint baseline is the last non-agent commit; the checkpoint tip is the reviewed
HEAD at the final checkpoint:

- **Baseline** `93ebec71` — *"Initial Commit"* (original COBOL-only repository).
- **Checkpoint tip** `04392b21` — the reviewed HEAD (2026-07-19 22:55 UTC).

Regenerate the full changed-path set (284 `A` + 148 `R100` + 1 `M` = 433 paths):

```bash
git diff --name-status 93ebec71 04392b21        # 433 changed paths
git diff --name-status 93ebec71 04392b21 | awk '{print $1}' \
  | sed 's/[0-9]*$//' | sort | uniq -c           # A=284  R=148  M=1
```

Add the separately-modelled `app/` directory deletion → **434 total operations (0–433)**.

## 3. Authoritative Reconciliation (checkpoint, reviewer-verified)

| Item | Figure | Derivation |
|------|-------:|-----------|
| Additions (`A`) | 284 | net-new destination artifacts |
| Relocations (`R100`) | 148 | `app/** → legacy/**` byte-identical renames |
| **CREATE-equivalent files** | **432** | 284 `A` + 148 `R100` |
| `README.md` update (`M`) | 1 | documentation update |
| `app/` deletion (`D`) | 1 | directory deletion after verified relocation |
| **TOTAL OPERATIONS (indices 0–433)** | **434** | 432 + 1 + 1 |
| Changed file paths | 433 | 284 `A` + 148 `R100` + 1 `M` |
| Static / reference **assets** | 24 | 9 ASCII + 12 EBCDIC + 2 screenshots + 1 deck theme |
| **Non-asset changed paths** | **409** | 433 − 24 |

The 24 assets reconcile precisely as **9** ASCII fixtures (`legacy/data/ASCII/*.txt`),
**12** EBCDIC datasets (`legacy/data/EBCDIC/*.PS` + `*.PS.INIT`; the three `.gitkeep`
placeholders are structural non-assets), **2** tracked screenshots
(`blitzy/screenshots/COUSR03_*.png`), and **1** deck theme
(`blitzy-deck/references/blitzy-reveal-theme.css`). These figures match the reviewer's
reconciliation exactly.

## 4. Category Summary (checkpoint 0–433)

| Category | Op kind | Count | Class |
|----------|:-------:|------:|:-----:|
| Build, wrapper & repo config | A/M | 7 | non-asset |
| README (UPDATE) | M | 1 | non-asset |
| Legacy COBOL programs | R100 | 28 | non-asset |
| Legacy copybooks | R100 | 28 | non-asset |
| Legacy symbolic-map copybooks | R100 | 18 | non-asset |
| Legacy BMS maps | R100 | 17 | non-asset |
| Legacy JCL jobs | R100 | 29 | non-asset |
| Legacy PROCs / CTL / CSD / catalog | R100 | 6 | non-asset |
| Legacy ASCII fixtures (ASSET) | R100 | 9 | asset |
| Legacy EBCDIC datasets (ASSET) | R100 | 12 | asset |
| Legacy structural placeholders (.gitkeep) | R100 | 1 | non-asset |
| Java domain entities | A | 11 | non-asset |
| Java DTOs | A | 31 | non-asset |
| Java repositories | A | 10 | non-asset |
| Java services | A | 17 | non-asset |
| Java web / controllers | A | 9 | non-asset |
| Java batch job configs | A | 12 | non-asset |
| Java security / config / exception / util | A | 23 | non-asset |
| Java application root | A | 1 | non-asset |
| Flyway migrations | A | 5 | non-asset |
| Thymeleaf templates | A | 17 | non-asset |
| Resources (application.yml, logback) | A | 4 | non-asset |
| Java tests (unit + IT + parity) | A | 128 | non-asset |
| Documentation (docs/**) | A | 4 | non-asset |
| Executive deck theme (ASSET) | A | 1 | asset |
| Executive deck (blitzy-deck/**) | A | 1 | non-asset |
| Observability dashboard | A | 1 | non-asset |
| Evidence screenshots (ASSET) | A | 2 | asset |
| **Subtotal — changed paths** |  | **433** |  |
| Directory deletion `app/` (meta-op) | D | 1 | — |
| **TOTAL OPERATIONS (indices 0–433)** |  | **434** |  |

## 5. Post-Review Remediation Ledger (net-new since checkpoint)

Resolving the 49 final-checkpoint findings added net-new artifacts on top of the 434
checkpoint operations. Every addition is authorized by, and traceable to, a specific
finding (several were **explicitly requested** by the reviewer — e.g. #27 *"Add
independent source-derived adversarial suites alongside each correction."*). None is
unauthorized scope creep.

Regenerate the net-new set (pre-Phase-15-commit):

```bash
git status --porcelain=v1 | awk '$1=="??"{print $2}' \
  | grep -Ev '^(target/|META-INF/)'                       # untracked, excl. build output
```

### 5.1 Source, test, deck and process-evidence additions (22)

| # | New artifact | Finding(s) | Purpose |
|---:|------|:----------:|---------|
| 1 | `blitzy-deck/references/deck-init.js` | #38, #46, #28 | Externalized deck bootstrap: Mermaid `startOnLoad:false` init, pinned CDN wiring, layout hardening. |
| 2 | `src/main/java/com/aws/carddemo/security/SessionRevocationService.java` | #43, #8 | SessionRegistry-backed revocation of live sessions on security-relevant user change/delete. |
| 3 | `src/main/java/com/aws/carddemo/util/batch/AtomicFileStepPublisher.java` | #34, #19 | Durable/atomic batch output publication boundary (temp-then-rename); restart-safe. |
| 4 | `src/main/java/com/aws/carddemo/util/batch/BatchFilePathResolver.java` | #18 | Centralized traversal-safe path resolution for all batch flat-file I/O. |
| 5 | `src/main/java/com/aws/carddemo/util/batch/FixedBlockLineAggregator.java` | #17 | RECFM=FB fixed-block **output** framing (undelimited fixed-length records). |
| 6 | `src/main/java/com/aws/carddemo/util/batch/FixedLengthItemReader.java` | #17 | RECFM=FB fixed-block **input** framing (undelimited fixed-length records). |
| 7 | `src/main/java/com/aws/carddemo/web/support/ConfirmationTokenService.java` | #12, #10 | Server-owned single-use confirmation nonce (constant-time compare) for confirm flows. |
| 8 | `src/main/java/com/aws/carddemo/web/support/PendingConfirmation.java` | #12, #10 | Server-carried pending-change snapshot restoring COBOL COMMAREA parity for edit→confirm. |
| 9 | `src/main/resources/templates/error.html` | #25 | Shared CardDemo 24x80 terminal error screen (replaces silent Whitelabel rendering). |
| 10 | `src/main/resources/templates/fragments/bms-palette.html` | #53 | Centralized WCAG-contrast BMS palette fragment (single source for all 18 screens). |
| 11 | `src/test/java/com/aws/carddemo/TestCredentials.java` | #5 | Test seed-credential helper reading externalized env value (no literal password in tests). |
| 12 | `src/test/java/com/aws/carddemo/observability/ObservabilityTracingIT.java` | #51 | Asserts bounded low-cardinality `@Observed` spans are emitted across service/repository. |
| 13 | `src/test/java/com/aws/carddemo/repository/ConcurrencyParityIT.java` | #15 | Real multi-thread Testcontainers concurrency: lost-update prevention + duplicate-key parity. |
| 14 | `src/test/java/com/aws/carddemo/repository/PessimisticLockFinderTest.java` | #14 | Verifies `@Lock(PESSIMISTIC_WRITE)` finder mechanism on all four write-path finders. |
| 15 | `src/test/java/com/aws/carddemo/security/SessionRevocationIT.java` | #8, #43 | End-to-end session-revocation: revoked session expired on next request. |
| 16 | `src/test/java/com/aws/carddemo/util/batch/BatchFilePathResolverTest.java` | #27, #18 | Adversarial safe-path unit suite (traversal, absolute-escape, symlink-leaf). |
| 17 | `src/test/java/com/aws/carddemo/util/batch/FixedBlockLineAggregatorTest.java` | #27, #17 | Adversarial FB **output** framing unit suite (pad/truncate/exact-length). |
| 18 | `src/test/java/com/aws/carddemo/util/batch/FixedLengthItemReaderTest.java` | #27, #17 | Adversarial FB **input** framing unit suite (short/over-long/trailing bytes). |
| 19 | `src/test/java/com/aws/carddemo/web/BmsPaletteContrastTest.java` | #53 | WCAG AA contrast-ratio tests for the accessible palette tokens. |
| 20 | `src/test/java/com/aws/carddemo/web/CousrThreeBmsPositionParityTest.java` | #55 | COUSR03 field-position parity vs BMS ground truth (off-by-one correction). |
| 21 | `docs/operation-inventory.md` | #2 | THIS document — authoritative regenerable operation inventory (supersedes stale 424/422/399). |
| 22 | `docs/validation-gate-manifest.md` | #3 | Ordered eight-gate validation manifest + full 49-finding resolution/retest matrix. |

### 5.2 Evidence screenshots (15)

Fifteen validation screenshots under `blitzy/screenshots/` capture the visual/interaction
evidence for the UI, error-rendering, deck-layout and responsive findings
(#25, #28, #38, #47, #53, #54, #56). They join the 2 screenshots already tracked at the
checkpoint, for 17 in the final state.

## 6. Final Committed Scope (projected; closed in the commit phase)

| Segment | Operations |
|---------|-----------:|
| Checkpoint operations (indices 0–433) | 434 |
| Post-review source/test/deck/doc additions (§5.1) | 22 |
| Post-review evidence screenshots (§5.2) | 15 |
| **Final committed operations (projected)** | **471** |

Build output (`target/`) and the transient `META-INF/` compile artifact are **excluded**
from the commit and therefore from this inventory. The final figure is re-verifiable
after commit with:

```bash
git diff --name-status 93ebec71 HEAD | wc -l    # final changed paths (+1 for app/ deletion)
```

## 7. Scope / Count Synchronization

- The stale `424 / 422 / 399` acceptance figures are **superseded** by the verified
  `434 / 432 / 409`. A repository-wide search confirms the stale literals appear in **no**
  tracked document, deck slide, or README — so there were no contradictory published
  claims to rewrite; the corrective action is the **publication** of this authoritative
  inventory.
- The AAP §0.2.1 **source** inventory (148 legacy artifacts) and this **operation**
  inventory (434) measure different things (see §1) and are both retained as correct.
- Any future scope statement MUST cite this document and regenerate its figure with the
  §2 / §6 commands rather than transcribing a static number.

## 8. Appendix — Full Indexed Enumeration (indices 0–433)

The complete authoritative enumeration follows, grouped by category with a continuous
`0`–`433` index. `R100` rows show the `legacy/**` destination path and the `app/**` path
they were relocated from.


#### README (UPDATE) — 1 operation(s), indices 0–0

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 0 | M | `README.md` | — |

#### Build & Maven wrapper — 5 operation(s), indices 1–5

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 1 | A | `.mvn/jvm.config` | — |
| 2 | A | `.mvn/wrapper/maven-wrapper.properties` | — |
| 3 | A | `mvnw` | — |
| 4 | A | `mvnw.cmd` | — |
| 5 | A | `pom.xml` | — |

#### Repo config (.gitattributes, OWASP suppressions) — 2 operation(s), indices 6–7

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 6 | A | `.gitattributes` | — |
| 7 | A | `dependency-check-suppressions.xml` | — |

#### Legacy COBOL programs (R100 relocation) — 28 operation(s), indices 8–35

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 8 | R | `legacy/cbl/CBACT01C.cbl` | `app/cbl/CBACT01C.cbl` |
| 9 | R | `legacy/cbl/CBACT02C.cbl` | `app/cbl/CBACT02C.cbl` |
| 10 | R | `legacy/cbl/CBACT03C.cbl` | `app/cbl/CBACT03C.cbl` |
| 11 | R | `legacy/cbl/CBACT04C.cbl` | `app/cbl/CBACT04C.cbl` |
| 12 | R | `legacy/cbl/CBCUS01C.cbl` | `app/cbl/CBCUS01C.cbl` |
| 13 | R | `legacy/cbl/CBSTM03A.CBL` | `app/cbl/CBSTM03A.CBL` |
| 14 | R | `legacy/cbl/CBSTM03B.CBL` | `app/cbl/CBSTM03B.CBL` |
| 15 | R | `legacy/cbl/CBTRN01C.cbl` | `app/cbl/CBTRN01C.cbl` |
| 16 | R | `legacy/cbl/CBTRN02C.cbl` | `app/cbl/CBTRN02C.cbl` |
| 17 | R | `legacy/cbl/CBTRN03C.cbl` | `app/cbl/CBTRN03C.cbl` |
| 18 | R | `legacy/cbl/COACTUPC.cbl` | `app/cbl/COACTUPC.cbl` |
| 19 | R | `legacy/cbl/COACTVWC.cbl` | `app/cbl/COACTVWC.cbl` |
| 20 | R | `legacy/cbl/COADM01C.cbl` | `app/cbl/COADM01C.cbl` |
| 21 | R | `legacy/cbl/COBIL00C.cbl` | `app/cbl/COBIL00C.cbl` |
| 22 | R | `legacy/cbl/COCRDLIC.cbl` | `app/cbl/COCRDLIC.cbl` |
| 23 | R | `legacy/cbl/COCRDSLC.cbl` | `app/cbl/COCRDSLC.cbl` |
| 24 | R | `legacy/cbl/COCRDUPC.cbl` | `app/cbl/COCRDUPC.cbl` |
| 25 | R | `legacy/cbl/COMEN01C.cbl` | `app/cbl/COMEN01C.cbl` |
| 26 | R | `legacy/cbl/CORPT00C.cbl` | `app/cbl/CORPT00C.cbl` |
| 27 | R | `legacy/cbl/COSGN00C.cbl` | `app/cbl/COSGN00C.cbl` |
| 28 | R | `legacy/cbl/COTRN00C.cbl` | `app/cbl/COTRN00C.cbl` |
| 29 | R | `legacy/cbl/COTRN01C.cbl` | `app/cbl/COTRN01C.cbl` |
| 30 | R | `legacy/cbl/COTRN02C.cbl` | `app/cbl/COTRN02C.cbl` |
| 31 | R | `legacy/cbl/COUSR00C.cbl` | `app/cbl/COUSR00C.cbl` |
| 32 | R | `legacy/cbl/COUSR01C.cbl` | `app/cbl/COUSR01C.cbl` |
| 33 | R | `legacy/cbl/COUSR02C.cbl` | `app/cbl/COUSR02C.cbl` |
| 34 | R | `legacy/cbl/COUSR03C.cbl` | `app/cbl/COUSR03C.cbl` |
| 35 | R | `legacy/cbl/CSUTLDTC.cbl` | `app/cbl/CSUTLDTC.cbl` |

#### Legacy copybooks (R100 relocation) — 28 operation(s), indices 36–63

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 36 | R | `legacy/cpy/COADM02Y.cpy` | `app/cpy/COADM02Y.cpy` |
| 37 | R | `legacy/cpy/COCOM01Y.cpy` | `app/cpy/COCOM01Y.cpy` |
| 38 | R | `legacy/cpy/COMEN02Y.cpy` | `app/cpy/COMEN02Y.cpy` |
| 39 | R | `legacy/cpy/COSTM01.CPY` | `app/cpy/COSTM01.CPY` |
| 40 | R | `legacy/cpy/COTTL01Y.cpy` | `app/cpy/COTTL01Y.cpy` |
| 41 | R | `legacy/cpy/CSDAT01Y.cpy` | `app/cpy/CSDAT01Y.cpy` |
| 42 | R | `legacy/cpy/CSLKPCDY.cpy` | `app/cpy/CSLKPCDY.cpy` |
| 43 | R | `legacy/cpy/CSMSG01Y.cpy` | `app/cpy/CSMSG01Y.cpy` |
| 44 | R | `legacy/cpy/CSMSG02Y.cpy` | `app/cpy/CSMSG02Y.cpy` |
| 45 | R | `legacy/cpy/CSSETATY.cpy` | `app/cpy/CSSETATY.cpy` |
| 46 | R | `legacy/cpy/CSSTRPFY.cpy` | `app/cpy/CSSTRPFY.cpy` |
| 47 | R | `legacy/cpy/CSUSR01Y.cpy` | `app/cpy/CSUSR01Y.cpy` |
| 48 | R | `legacy/cpy/CSUTLDPY.cpy` | `app/cpy/CSUTLDPY.cpy` |
| 49 | R | `legacy/cpy/CSUTLDWY.cpy` | `app/cpy/CSUTLDWY.cpy` |
| 50 | R | `legacy/cpy/CUSTREC.cpy` | `app/cpy/CUSTREC.cpy` |
| 51 | R | `legacy/cpy/CVACT01Y.cpy` | `app/cpy/CVACT01Y.cpy` |
| 52 | R | `legacy/cpy/CVACT02Y.cpy` | `app/cpy/CVACT02Y.cpy` |
| 53 | R | `legacy/cpy/CVACT03Y.cpy` | `app/cpy/CVACT03Y.cpy` |
| 54 | R | `legacy/cpy/CVCRD01Y.cpy` | `app/cpy/CVCRD01Y.cpy` |
| 55 | R | `legacy/cpy/CVCUS01Y.cpy` | `app/cpy/CVCUS01Y.cpy` |
| 56 | R | `legacy/cpy/CVTRA01Y.cpy` | `app/cpy/CVTRA01Y.cpy` |
| 57 | R | `legacy/cpy/CVTRA02Y.cpy` | `app/cpy/CVTRA02Y.cpy` |
| 58 | R | `legacy/cpy/CVTRA03Y.cpy` | `app/cpy/CVTRA03Y.cpy` |
| 59 | R | `legacy/cpy/CVTRA04Y.cpy` | `app/cpy/CVTRA04Y.cpy` |
| 60 | R | `legacy/cpy/CVTRA05Y.cpy` | `app/cpy/CVTRA05Y.cpy` |
| 61 | R | `legacy/cpy/CVTRA06Y.cpy` | `app/cpy/CVTRA06Y.cpy` |
| 62 | R | `legacy/cpy/CVTRA07Y.cpy` | `app/cpy/CVTRA07Y.cpy` |
| 63 | R | `legacy/cpy/UNUSED1Y.cpy` | `app/cpy/UNUSED1Y.cpy` |

#### Legacy symbolic-map copybooks (R100 relocation) — 18 operation(s), indices 64–81

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 64 | R | `legacy/cpy-bms/.gitkeep` | `app/cpy-bms/.gitkeep` |
| 65 | R | `legacy/cpy-bms/COACTUP.CPY` | `app/cpy-bms/COACTUP.CPY` |
| 66 | R | `legacy/cpy-bms/COACTVW.CPY` | `app/cpy-bms/COACTVW.CPY` |
| 67 | R | `legacy/cpy-bms/COADM01.CPY` | `app/cpy-bms/COADM01.CPY` |
| 68 | R | `legacy/cpy-bms/COBIL00.CPY` | `app/cpy-bms/COBIL00.CPY` |
| 69 | R | `legacy/cpy-bms/COCRDLI.CPY` | `app/cpy-bms/COCRDLI.CPY` |
| 70 | R | `legacy/cpy-bms/COCRDSL.CPY` | `app/cpy-bms/COCRDSL.CPY` |
| 71 | R | `legacy/cpy-bms/COCRDUP.CPY` | `app/cpy-bms/COCRDUP.CPY` |
| 72 | R | `legacy/cpy-bms/COMEN01.CPY` | `app/cpy-bms/COMEN01.CPY` |
| 73 | R | `legacy/cpy-bms/CORPT00.CPY` | `app/cpy-bms/CORPT00.CPY` |
| 74 | R | `legacy/cpy-bms/COSGN00.CPY` | `app/cpy-bms/COSGN00.CPY` |
| 75 | R | `legacy/cpy-bms/COTRN00.CPY` | `app/cpy-bms/COTRN00.CPY` |
| 76 | R | `legacy/cpy-bms/COTRN01.CPY` | `app/cpy-bms/COTRN01.CPY` |
| 77 | R | `legacy/cpy-bms/COTRN02.CPY` | `app/cpy-bms/COTRN02.CPY` |
| 78 | R | `legacy/cpy-bms/COUSR00.CPY` | `app/cpy-bms/COUSR00.CPY` |
| 79 | R | `legacy/cpy-bms/COUSR01.CPY` | `app/cpy-bms/COUSR01.CPY` |
| 80 | R | `legacy/cpy-bms/COUSR02.CPY` | `app/cpy-bms/COUSR02.CPY` |
| 81 | R | `legacy/cpy-bms/COUSR03.CPY` | `app/cpy-bms/COUSR03.CPY` |

#### Legacy BMS maps (R100 relocation) — 17 operation(s), indices 82–98

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 82 | R | `legacy/bms/COACTUP.bms` | `app/bms/COACTUP.bms` |
| 83 | R | `legacy/bms/COACTVW.bms` | `app/bms/COACTVW.bms` |
| 84 | R | `legacy/bms/COADM01.bms` | `app/bms/COADM01.bms` |
| 85 | R | `legacy/bms/COBIL00.bms` | `app/bms/COBIL00.bms` |
| 86 | R | `legacy/bms/COCRDLI.bms` | `app/bms/COCRDLI.bms` |
| 87 | R | `legacy/bms/COCRDSL.bms` | `app/bms/COCRDSL.bms` |
| 88 | R | `legacy/bms/COCRDUP.bms` | `app/bms/COCRDUP.bms` |
| 89 | R | `legacy/bms/COMEN01.bms` | `app/bms/COMEN01.bms` |
| 90 | R | `legacy/bms/CORPT00.bms` | `app/bms/CORPT00.bms` |
| 91 | R | `legacy/bms/COSGN00.bms` | `app/bms/COSGN00.bms` |
| 92 | R | `legacy/bms/COTRN00.bms` | `app/bms/COTRN00.bms` |
| 93 | R | `legacy/bms/COTRN01.bms` | `app/bms/COTRN01.bms` |
| 94 | R | `legacy/bms/COTRN02.bms` | `app/bms/COTRN02.bms` |
| 95 | R | `legacy/bms/COUSR00.bms` | `app/bms/COUSR00.bms` |
| 96 | R | `legacy/bms/COUSR01.bms` | `app/bms/COUSR01.bms` |
| 97 | R | `legacy/bms/COUSR02.bms` | `app/bms/COUSR02.bms` |
| 98 | R | `legacy/bms/COUSR03.bms` | `app/bms/COUSR03.bms` |

#### Legacy JCL jobs (R100 relocation) — 29 operation(s), indices 99–127

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 99 | R | `legacy/jcl/ACCTFILE.jcl` | `app/jcl/ACCTFILE.jcl` |
| 100 | R | `legacy/jcl/CARDFILE.jcl` | `app/jcl/CARDFILE.jcl` |
| 101 | R | `legacy/jcl/CBADMCDJ.jcl` | `app/jcl/CBADMCDJ.jcl` |
| 102 | R | `legacy/jcl/CLOSEFIL.jcl` | `app/jcl/CLOSEFIL.jcl` |
| 103 | R | `legacy/jcl/COMBTRAN.jcl` | `app/jcl/COMBTRAN.jcl` |
| 104 | R | `legacy/jcl/CREASTMT.JCL` | `app/jcl/CREASTMT.JCL` |
| 105 | R | `legacy/jcl/CUSTFILE.jcl` | `app/jcl/CUSTFILE.jcl` |
| 106 | R | `legacy/jcl/DALYREJS.jcl` | `app/jcl/DALYREJS.jcl` |
| 107 | R | `legacy/jcl/DEFCUST.jcl` | `app/jcl/DEFCUST.jcl` |
| 108 | R | `legacy/jcl/DEFGDGB.jcl` | `app/jcl/DEFGDGB.jcl` |
| 109 | R | `legacy/jcl/DISCGRP.jcl` | `app/jcl/DISCGRP.jcl` |
| 110 | R | `legacy/jcl/DUSRSECJ.jcl` | `app/jcl/DUSRSECJ.jcl` |
| 111 | R | `legacy/jcl/INTCALC.jcl` | `app/jcl/INTCALC.jcl` |
| 112 | R | `legacy/jcl/OPENFIL.jcl` | `app/jcl/OPENFIL.jcl` |
| 113 | R | `legacy/jcl/POSTTRAN.jcl` | `app/jcl/POSTTRAN.jcl` |
| 114 | R | `legacy/jcl/PRTCATBL.jcl` | `app/jcl/PRTCATBL.jcl` |
| 115 | R | `legacy/jcl/READACCT.jcl` | `app/jcl/READACCT.jcl` |
| 116 | R | `legacy/jcl/READCARD.jcl` | `app/jcl/READCARD.jcl` |
| 117 | R | `legacy/jcl/READCUST.jcl` | `app/jcl/READCUST.jcl` |
| 118 | R | `legacy/jcl/READXREF.jcl` | `app/jcl/READXREF.jcl` |
| 119 | R | `legacy/jcl/REPTFILE.jcl` | `app/jcl/REPTFILE.jcl` |
| 120 | R | `legacy/jcl/TCATBALF.jcl` | `app/jcl/TCATBALF.jcl` |
| 121 | R | `legacy/jcl/TRANBKP.jcl` | `app/jcl/TRANBKP.jcl` |
| 122 | R | `legacy/jcl/TRANCATG.jcl` | `app/jcl/TRANCATG.jcl` |
| 123 | R | `legacy/jcl/TRANFILE.jcl` | `app/jcl/TRANFILE.jcl` |
| 124 | R | `legacy/jcl/TRANIDX.jcl` | `app/jcl/TRANIDX.jcl` |
| 125 | R | `legacy/jcl/TRANREPT.jcl` | `app/jcl/TRANREPT.jcl` |
| 126 | R | `legacy/jcl/TRANTYPE.jcl` | `app/jcl/TRANTYPE.jcl` |
| 127 | R | `legacy/jcl/XREFFILE.jcl` | `app/jcl/XREFFILE.jcl` |

#### Legacy PROCs (R100 relocation) — 2 operation(s), indices 128–129

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 128 | R | `legacy/proc/REPROC.prc` | `app/proc/REPROC.prc` |
| 129 | R | `legacy/proc/TRANREPT.prc` | `app/proc/TRANREPT.prc` |

#### Legacy CTL (R100 relocation) — 1 operation(s), indices 130–130

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 130 | R | `legacy/ctl/REPROCT.ctl` | `app/ctl/REPROCT.ctl` |

#### Legacy CSD (R100 relocation) — 2 operation(s), indices 131–132

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 131 | R | `legacy/csd/.gitkeep` | `app/csd/.gitkeep` |
| 132 | R | `legacy/csd/CARDDEMO.CSD` | `app/csd/CARDDEMO.CSD` |

#### Legacy catalog metadata (R100 relocation) — 1 operation(s), indices 133–133

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 133 | R | `legacy/catlg/LISTCAT.txt` | `app/catlg/LISTCAT.txt` |

#### Legacy ASCII fixtures — ASSET (R100 relocation) — 9 operation(s), indices 134–142

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 134 | R | `legacy/data/ASCII/acctdata.txt` | `app/data/ASCII/acctdata.txt` |
| 135 | R | `legacy/data/ASCII/carddata.txt` | `app/data/ASCII/carddata.txt` |
| 136 | R | `legacy/data/ASCII/cardxref.txt` | `app/data/ASCII/cardxref.txt` |
| 137 | R | `legacy/data/ASCII/custdata.txt` | `app/data/ASCII/custdata.txt` |
| 138 | R | `legacy/data/ASCII/dailytran.txt` | `app/data/ASCII/dailytran.txt` |
| 139 | R | `legacy/data/ASCII/discgrp.txt` | `app/data/ASCII/discgrp.txt` |
| 140 | R | `legacy/data/ASCII/tcatbal.txt` | `app/data/ASCII/tcatbal.txt` |
| 141 | R | `legacy/data/ASCII/trancatg.txt` | `app/data/ASCII/trancatg.txt` |
| 142 | R | `legacy/data/ASCII/trantype.txt` | `app/data/ASCII/trantype.txt` |

#### Legacy EBCDIC datasets — ASSET (R100 relocation) — 12 operation(s), indices 143–154

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 143 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.ACCDATA.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.ACCDATA.PS` |
| 144 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS` |
| 145 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.CARDDATA.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.CARDDATA.PS` |
| 146 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS` |
| 147 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.CUSTDATA.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.CUSTDATA.PS` |
| 148 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.DALYTRAN.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.DALYTRAN.PS` |
| 149 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.DALYTRAN.PS.INIT` | `app/data/EBCDIC/AWS.M2.CARDDEMO.DALYTRAN.PS.INIT` |
| 150 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS` |
| 151 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.TCATBALF.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.TCATBALF.PS` |
| 152 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.TRANCATG.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.TRANCATG.PS` |
| 153 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.TRANTYPE.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.TRANTYPE.PS` |
| 154 | R | `legacy/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS` | `app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS` |

#### Legacy structural placeholders (.gitkeep) (R100 relocation) — 1 operation(s), indices 155–155

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 155 | R | `legacy/data/EBCDIC/.gitkeep` | `app/data/EBCDIC/.gitkeep` |

#### Java domain entities — 11 operation(s), indices 156–166

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 156 | A | `src/main/java/com/aws/carddemo/domain/Account.java` | — |
| 157 | A | `src/main/java/com/aws/carddemo/domain/Card.java` | — |
| 158 | A | `src/main/java/com/aws/carddemo/domain/CardXref.java` | — |
| 159 | A | `src/main/java/com/aws/carddemo/domain/Customer.java` | — |
| 160 | A | `src/main/java/com/aws/carddemo/domain/DisclosureGroup.java` | — |
| 161 | A | `src/main/java/com/aws/carddemo/domain/Transaction.java` | — |
| 162 | A | `src/main/java/com/aws/carddemo/domain/TransactionCategory.java` | — |
| 163 | A | `src/main/java/com/aws/carddemo/domain/TransactionCategoryBalance.java` | — |
| 164 | A | `src/main/java/com/aws/carddemo/domain/TransactionType.java` | — |
| 165 | A | `src/main/java/com/aws/carddemo/domain/UserSecurity.java` | — |
| 166 | A | `src/main/java/com/aws/carddemo/domain/enums/LookupCodes.java` | — |

#### Java DTOs — 31 operation(s), indices 167–197

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 167 | A | `src/main/java/com/aws/carddemo/dto/CardDemoContext.java` | — |
| 168 | A | `src/main/java/com/aws/carddemo/dto/CardWorkArea.java` | — |
| 169 | A | `src/main/java/com/aws/carddemo/dto/DailyTransaction.java` | — |
| 170 | A | `src/main/java/com/aws/carddemo/dto/DateStruct.java` | — |
| 171 | A | `src/main/java/com/aws/carddemo/dto/StatementTransaction.java` | — |
| 172 | A | `src/main/java/com/aws/carddemo/dto/menu/AdminMenuOptions.java` | — |
| 173 | A | `src/main/java/com/aws/carddemo/dto/menu/MainMenuOptions.java` | — |
| 174 | A | `src/main/java/com/aws/carddemo/dto/report/ReportAccountTotals.java` | — |
| 175 | A | `src/main/java/com/aws/carddemo/dto/report/ReportAmountFormatter.java` | — |
| 176 | A | `src/main/java/com/aws/carddemo/dto/report/ReportGrandTotals.java` | — |
| 177 | A | `src/main/java/com/aws/carddemo/dto/report/ReportNameHeader.java` | — |
| 178 | A | `src/main/java/com/aws/carddemo/dto/report/ReportPageTotals.java` | — |
| 179 | A | `src/main/java/com/aws/carddemo/dto/report/TransactionDetailReport.java` | — |
| 180 | A | `src/main/java/com/aws/carddemo/dto/report/TransactionReportHeaders.java` | — |
| 181 | A | `src/main/java/com/aws/carddemo/dto/screen/COACTUPForm.java` | — |
| 182 | A | `src/main/java/com/aws/carddemo/dto/screen/COACTVWForm.java` | — |
| 183 | A | `src/main/java/com/aws/carddemo/dto/screen/COADM01Form.java` | — |
| 184 | A | `src/main/java/com/aws/carddemo/dto/screen/COBIL00Form.java` | — |
| 185 | A | `src/main/java/com/aws/carddemo/dto/screen/COCRDLIForm.java` | — |
| 186 | A | `src/main/java/com/aws/carddemo/dto/screen/COCRDSLForm.java` | — |
| 187 | A | `src/main/java/com/aws/carddemo/dto/screen/COCRDUPForm.java` | — |
| 188 | A | `src/main/java/com/aws/carddemo/dto/screen/COMEN01Form.java` | — |
| 189 | A | `src/main/java/com/aws/carddemo/dto/screen/CORPT00Form.java` | — |
| 190 | A | `src/main/java/com/aws/carddemo/dto/screen/COSGN00Form.java` | — |
| 191 | A | `src/main/java/com/aws/carddemo/dto/screen/COTRN00Form.java` | — |
| 192 | A | `src/main/java/com/aws/carddemo/dto/screen/COTRN01Form.java` | — |
| 193 | A | `src/main/java/com/aws/carddemo/dto/screen/COTRN02Form.java` | — |
| 194 | A | `src/main/java/com/aws/carddemo/dto/screen/COUSR00Form.java` | — |
| 195 | A | `src/main/java/com/aws/carddemo/dto/screen/COUSR01Form.java` | — |
| 196 | A | `src/main/java/com/aws/carddemo/dto/screen/COUSR02Form.java` | — |
| 197 | A | `src/main/java/com/aws/carddemo/dto/screen/COUSR03Form.java` | — |

#### Java repositories — 10 operation(s), indices 198–207

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 198 | A | `src/main/java/com/aws/carddemo/repository/AccountRepository.java` | — |
| 199 | A | `src/main/java/com/aws/carddemo/repository/CardRepository.java` | — |
| 200 | A | `src/main/java/com/aws/carddemo/repository/CardXrefRepository.java` | — |
| 201 | A | `src/main/java/com/aws/carddemo/repository/CustomerRepository.java` | — |
| 202 | A | `src/main/java/com/aws/carddemo/repository/DisclosureGroupRepository.java` | — |
| 203 | A | `src/main/java/com/aws/carddemo/repository/TransactionCategoryBalanceRepository.java` | — |
| 204 | A | `src/main/java/com/aws/carddemo/repository/TransactionCategoryRepository.java` | — |
| 205 | A | `src/main/java/com/aws/carddemo/repository/TransactionRepository.java` | — |
| 206 | A | `src/main/java/com/aws/carddemo/repository/TransactionTypeRepository.java` | — |
| 207 | A | `src/main/java/com/aws/carddemo/repository/UserSecurityRepository.java` | — |

#### Java services — 17 operation(s), indices 208–224

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 208 | A | `src/main/java/com/aws/carddemo/service/online/AccountUpdateService.java` | — |
| 209 | A | `src/main/java/com/aws/carddemo/service/online/AccountViewService.java` | — |
| 210 | A | `src/main/java/com/aws/carddemo/service/online/AdminMenuService.java` | — |
| 211 | A | `src/main/java/com/aws/carddemo/service/online/BillPayService.java` | — |
| 212 | A | `src/main/java/com/aws/carddemo/service/online/CardDetailService.java` | — |
| 213 | A | `src/main/java/com/aws/carddemo/service/online/CardListService.java` | — |
| 214 | A | `src/main/java/com/aws/carddemo/service/online/CardUpdateService.java` | — |
| 215 | A | `src/main/java/com/aws/carddemo/service/online/MainMenuService.java` | — |
| 216 | A | `src/main/java/com/aws/carddemo/service/online/ReportSubmitService.java` | — |
| 217 | A | `src/main/java/com/aws/carddemo/service/online/SignonService.java` | — |
| 218 | A | `src/main/java/com/aws/carddemo/service/online/TransactionAddService.java` | — |
| 219 | A | `src/main/java/com/aws/carddemo/service/online/TransactionListService.java` | — |
| 220 | A | `src/main/java/com/aws/carddemo/service/online/TransactionViewService.java` | — |
| 221 | A | `src/main/java/com/aws/carddemo/service/online/UserAddService.java` | — |
| 222 | A | `src/main/java/com/aws/carddemo/service/online/UserDeleteService.java` | — |
| 223 | A | `src/main/java/com/aws/carddemo/service/online/UserListService.java` | — |
| 224 | A | `src/main/java/com/aws/carddemo/service/online/UserUpdateService.java` | — |

#### Java web / controllers — 9 operation(s), indices 225–233

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 225 | A | `src/main/java/com/aws/carddemo/web/controller/AccountController.java` | — |
| 226 | A | `src/main/java/com/aws/carddemo/web/controller/AdminMenuController.java` | — |
| 227 | A | `src/main/java/com/aws/carddemo/web/controller/BillPayController.java` | — |
| 228 | A | `src/main/java/com/aws/carddemo/web/controller/CardController.java` | — |
| 229 | A | `src/main/java/com/aws/carddemo/web/controller/MenuController.java` | — |
| 230 | A | `src/main/java/com/aws/carddemo/web/controller/ReportController.java` | — |
| 231 | A | `src/main/java/com/aws/carddemo/web/controller/SignonController.java` | — |
| 232 | A | `src/main/java/com/aws/carddemo/web/controller/TransactionController.java` | — |
| 233 | A | `src/main/java/com/aws/carddemo/web/controller/UserAdminController.java` | — |

#### Java batch job configs — 12 operation(s), indices 234–245

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 234 | A | `src/main/java/com/aws/carddemo/batch/AccountPrintJobConfig.java` | — |
| 235 | A | `src/main/java/com/aws/carddemo/batch/AdminBatchJobConfig.java` | — |
| 236 | A | `src/main/java/com/aws/carddemo/batch/CardPrintJobConfig.java` | — |
| 237 | A | `src/main/java/com/aws/carddemo/batch/CategoryBalancePrintJobConfig.java` | — |
| 238 | A | `src/main/java/com/aws/carddemo/batch/CustomerLoadJobConfig.java` | — |
| 239 | A | `src/main/java/com/aws/carddemo/batch/InterestCalcJobConfig.java` | — |
| 240 | A | `src/main/java/com/aws/carddemo/batch/PostTransactionJobConfig.java` | — |
| 241 | A | `src/main/java/com/aws/carddemo/batch/StatementJobConfig.java` | — |
| 242 | A | `src/main/java/com/aws/carddemo/batch/TransactionBackupJobConfig.java` | — |
| 243 | A | `src/main/java/com/aws/carddemo/batch/TransactionCombineJobConfig.java` | — |
| 244 | A | `src/main/java/com/aws/carddemo/batch/TransactionReportJobConfig.java` | — |
| 245 | A | `src/main/java/com/aws/carddemo/batch/XrefPrintJobConfig.java` | — |

#### Java security — 3 operation(s), indices 246–248

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 246 | A | `src/main/java/com/aws/carddemo/security/CardDemoAuthenticationProvider.java` | — |
| 247 | A | `src/main/java/com/aws/carddemo/security/CardDemoUserDetails.java` | — |
| 248 | A | `src/main/java/com/aws/carddemo/security/CardDemoUserDetailsService.java` | — |

#### Java config — 5 operation(s), indices 249–253

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 249 | A | `src/main/java/com/aws/carddemo/config/BatchConfig.java` | — |
| 250 | A | `src/main/java/com/aws/carddemo/config/DataSourceConfig.java` | — |
| 251 | A | `src/main/java/com/aws/carddemo/config/ObservabilityConfig.java` | — |
| 252 | A | `src/main/java/com/aws/carddemo/config/SecurityConfig.java` | — |
| 253 | A | `src/main/java/com/aws/carddemo/config/WebConfig.java` | — |

#### Java exception — 7 operation(s), indices 254–260

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 254 | A | `src/main/java/com/aws/carddemo/exception/DuplicateKeyException.java` | — |
| 255 | A | `src/main/java/com/aws/carddemo/exception/EndOfFileException.java` | — |
| 256 | A | `src/main/java/com/aws/carddemo/exception/FileStatusException.java` | — |
| 257 | A | `src/main/java/com/aws/carddemo/exception/GlobalExceptionHandler.java` | — |
| 258 | A | `src/main/java/com/aws/carddemo/exception/LogicError.java` | — |
| 259 | A | `src/main/java/com/aws/carddemo/exception/RecordNotFoundException.java` | — |
| 260 | A | `src/main/java/com/aws/carddemo/exception/ResourceUnavailable.java` | — |

#### Java util — 8 operation(s), indices 261–268

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 261 | A | `src/main/java/com/aws/carddemo/util/CobolDecimal.java` | — |
| 262 | A | `src/main/java/com/aws/carddemo/util/DateConversionService.java` | — |
| 263 | A | `src/main/java/com/aws/carddemo/util/DateConversionSupport.java` | — |
| 264 | A | `src/main/java/com/aws/carddemo/util/FixedWidthRecordMapper.java` | — |
| 265 | A | `src/main/java/com/aws/carddemo/util/PfKeyHandler.java` | — |
| 266 | A | `src/main/java/com/aws/carddemo/util/constants/Messages.java` | — |
| 267 | A | `src/main/java/com/aws/carddemo/util/constants/ScreenAttributes.java` | — |
| 268 | A | `src/main/java/com/aws/carddemo/util/constants/ScreenTitles.java` | — |

#### Java application root — 1 operation(s), indices 269–269

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 269 | A | `src/main/java/com/aws/carddemo/CardDemoApplication.java` | — |

#### Flyway migrations — 5 operation(s), indices 270–274

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 270 | A | `src/main/resources/db/migration/V0__spring_batch_metadata.sql` | — |
| 271 | A | `src/main/resources/db/migration/V1__schema.sql` | — |
| 272 | A | `src/main/resources/db/migration/V2__reference_data.sql` | — |
| 273 | A | `src/main/resources/db/migration/V3__indexes.sql` | — |
| 274 | A | `src/main/resources/db/migration/V4__card_xref_unique_card_num.sql` | — |

#### Thymeleaf templates — 17 operation(s), indices 275–291

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 275 | A | `src/main/resources/templates/COACTUP.html` | — |
| 276 | A | `src/main/resources/templates/COACTVW.html` | — |
| 277 | A | `src/main/resources/templates/COADM01.html` | — |
| 278 | A | `src/main/resources/templates/COBIL00.html` | — |
| 279 | A | `src/main/resources/templates/COCRDLI.html` | — |
| 280 | A | `src/main/resources/templates/COCRDSL.html` | — |
| 281 | A | `src/main/resources/templates/COCRDUP.html` | — |
| 282 | A | `src/main/resources/templates/COMEN01.html` | — |
| 283 | A | `src/main/resources/templates/CORPT00.html` | — |
| 284 | A | `src/main/resources/templates/COSGN00.html` | — |
| 285 | A | `src/main/resources/templates/COTRN00.html` | — |
| 286 | A | `src/main/resources/templates/COTRN01.html` | — |
| 287 | A | `src/main/resources/templates/COTRN02.html` | — |
| 288 | A | `src/main/resources/templates/COUSR00.html` | — |
| 289 | A | `src/main/resources/templates/COUSR01.html` | — |
| 290 | A | `src/main/resources/templates/COUSR02.html` | — |
| 291 | A | `src/main/resources/templates/COUSR03.html` | — |

#### Resources (application.yml, logback) — 4 operation(s), indices 292–295

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 292 | A | `src/main/resources/application-dev.yml` | — |
| 293 | A | `src/main/resources/application-test.yml` | — |
| 294 | A | `src/main/resources/application.yml` | — |
| 295 | A | `src/main/resources/logback-spring.xml` | — |

#### Java tests (unit + IT + parity) — 128 operation(s), indices 296–423

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 296 | A | `src/test/java/com/aws/carddemo/AbstractPostgresIntegrationTest.java` | — |
| 297 | A | `src/test/java/com/aws/carddemo/CardDemoApplicationIT.java` | — |
| 298 | A | `src/test/java/com/aws/carddemo/batch/AccountPrintJobConfigIT.java` | — |
| 299 | A | `src/test/java/com/aws/carddemo/batch/AdminBatchJobConfigIT.java` | — |
| 300 | A | `src/test/java/com/aws/carddemo/batch/CardPrintJobConfigIT.java` | — |
| 301 | A | `src/test/java/com/aws/carddemo/batch/CardPrintJobConfigTest.java` | — |
| 302 | A | `src/test/java/com/aws/carddemo/batch/CategoryBalancePrintJobConfigIT.java` | — |
| 303 | A | `src/test/java/com/aws/carddemo/batch/CategoryBalancePrintJobConfigTest.java` | — |
| 304 | A | `src/test/java/com/aws/carddemo/batch/CustomerLoadJobConfigIT.java` | — |
| 305 | A | `src/test/java/com/aws/carddemo/batch/InterestCalcJobConfigIT.java` | — |
| 306 | A | `src/test/java/com/aws/carddemo/batch/InterestCalcJobConfigTest.java` | — |
| 307 | A | `src/test/java/com/aws/carddemo/batch/PostTransactionJobConfigIT.java` | — |
| 308 | A | `src/test/java/com/aws/carddemo/batch/StatementJobConfigIT.java` | — |
| 309 | A | `src/test/java/com/aws/carddemo/batch/TransactionBackupJobConfigIT.java` | — |
| 310 | A | `src/test/java/com/aws/carddemo/batch/TransactionBackupJobConfigTest.java` | — |
| 311 | A | `src/test/java/com/aws/carddemo/batch/TransactionCombineJobConfigIT.java` | — |
| 312 | A | `src/test/java/com/aws/carddemo/batch/TransactionCombineJobConfigTest.java` | — |
| 313 | A | `src/test/java/com/aws/carddemo/batch/TransactionReportJobConfigIT.java` | — |
| 314 | A | `src/test/java/com/aws/carddemo/batch/XrefPrintJobConfigIT.java` | — |
| 315 | A | `src/test/java/com/aws/carddemo/config/SecurityConfigIT.java` | — |
| 316 | A | `src/test/java/com/aws/carddemo/domain/AccountPersistenceIT.java` | — |
| 317 | A | `src/test/java/com/aws/carddemo/domain/AccountTest.java` | — |
| 318 | A | `src/test/java/com/aws/carddemo/domain/CardPersistenceIT.java` | — |
| 319 | A | `src/test/java/com/aws/carddemo/domain/CardTest.java` | — |
| 320 | A | `src/test/java/com/aws/carddemo/domain/CardXrefPersistenceIT.java` | — |
| 321 | A | `src/test/java/com/aws/carddemo/domain/CardXrefTest.java` | — |
| 322 | A | `src/test/java/com/aws/carddemo/domain/CustomerPersistenceIT.java` | — |
| 323 | A | `src/test/java/com/aws/carddemo/domain/CustomerTest.java` | — |
| 324 | A | `src/test/java/com/aws/carddemo/domain/DisclosureGroupPersistenceIT.java` | — |
| 325 | A | `src/test/java/com/aws/carddemo/domain/DisclosureGroupTest.java` | — |
| 326 | A | `src/test/java/com/aws/carddemo/domain/TransactionCategoryBalancePersistenceIT.java` | — |
| 327 | A | `src/test/java/com/aws/carddemo/domain/TransactionCategoryBalanceTest.java` | — |
| 328 | A | `src/test/java/com/aws/carddemo/domain/TransactionCategoryPersistenceIT.java` | — |
| 329 | A | `src/test/java/com/aws/carddemo/domain/TransactionCategoryTest.java` | — |
| 330 | A | `src/test/java/com/aws/carddemo/domain/TransactionPersistenceIT.java` | — |
| 331 | A | `src/test/java/com/aws/carddemo/domain/TransactionTest.java` | — |
| 332 | A | `src/test/java/com/aws/carddemo/domain/TransactionTypePersistenceIT.java` | — |
| 333 | A | `src/test/java/com/aws/carddemo/domain/TransactionTypeTest.java` | — |
| 334 | A | `src/test/java/com/aws/carddemo/domain/UserSecurityPersistenceIT.java` | — |
| 335 | A | `src/test/java/com/aws/carddemo/domain/UserSecurityTest.java` | — |
| 336 | A | `src/test/java/com/aws/carddemo/domain/enums/LookupCodesTest.java` | — |
| 337 | A | `src/test/java/com/aws/carddemo/dto/CardDemoContextTest.java` | — |
| 338 | A | `src/test/java/com/aws/carddemo/dto/CardWorkAreaTest.java` | — |
| 339 | A | `src/test/java/com/aws/carddemo/dto/DailyTransactionTest.java` | — |
| 340 | A | `src/test/java/com/aws/carddemo/dto/DateStructTest.java` | — |
| 341 | A | `src/test/java/com/aws/carddemo/dto/StatementTransactionTest.java` | — |
| 342 | A | `src/test/java/com/aws/carddemo/dto/menu/AdminMenuOptionsTest.java` | — |
| 343 | A | `src/test/java/com/aws/carddemo/dto/menu/MainMenuOptionsTest.java` | — |
| 344 | A | `src/test/java/com/aws/carddemo/dto/report/ReportAccountTotalsTest.java` | — |
| 345 | A | `src/test/java/com/aws/carddemo/dto/report/ReportAmountFormatterTest.java` | — |
| 346 | A | `src/test/java/com/aws/carddemo/dto/report/ReportGrandTotalsTest.java` | — |
| 347 | A | `src/test/java/com/aws/carddemo/dto/report/ReportNameHeaderTest.java` | — |
| 348 | A | `src/test/java/com/aws/carddemo/dto/report/ReportPageTotalsTest.java` | — |
| 349 | A | `src/test/java/com/aws/carddemo/dto/report/TransactionDetailReportTest.java` | — |
| 350 | A | `src/test/java/com/aws/carddemo/dto/report/TransactionReportHeadersTest.java` | — |
| 351 | A | `src/test/java/com/aws/carddemo/dto/screen/COACTUPFormTest.java` | — |
| 352 | A | `src/test/java/com/aws/carddemo/dto/screen/COACTVWFormTest.java` | — |
| 353 | A | `src/test/java/com/aws/carddemo/dto/screen/COADM01FormTest.java` | — |
| 354 | A | `src/test/java/com/aws/carddemo/dto/screen/COBIL00FormTest.java` | — |
| 355 | A | `src/test/java/com/aws/carddemo/dto/screen/COCRDLIFormTest.java` | — |
| 356 | A | `src/test/java/com/aws/carddemo/dto/screen/COCRDSLFormTest.java` | — |
| 357 | A | `src/test/java/com/aws/carddemo/dto/screen/COCRDUPFormTest.java` | — |
| 358 | A | `src/test/java/com/aws/carddemo/dto/screen/COMEN01FormTest.java` | — |
| 359 | A | `src/test/java/com/aws/carddemo/dto/screen/CORPT00FormTest.java` | — |
| 360 | A | `src/test/java/com/aws/carddemo/dto/screen/COSGN00FormTest.java` | — |
| 361 | A | `src/test/java/com/aws/carddemo/dto/screen/COTRN00FormTest.java` | — |
| 362 | A | `src/test/java/com/aws/carddemo/dto/screen/COTRN01FormTest.java` | — |
| 363 | A | `src/test/java/com/aws/carddemo/dto/screen/COTRN02FormTest.java` | — |
| 364 | A | `src/test/java/com/aws/carddemo/dto/screen/COUSR00FormTest.java` | — |
| 365 | A | `src/test/java/com/aws/carddemo/dto/screen/COUSR01FormTest.java` | — |
| 366 | A | `src/test/java/com/aws/carddemo/dto/screen/COUSR02FormTest.java` | — |
| 367 | A | `src/test/java/com/aws/carddemo/dto/screen/COUSR03FormTest.java` | — |
| 368 | A | `src/test/java/com/aws/carddemo/exception/DuplicateKeyExceptionTest.java` | — |
| 369 | A | `src/test/java/com/aws/carddemo/exception/EndOfFileExceptionTest.java` | — |
| 370 | A | `src/test/java/com/aws/carddemo/exception/FileStatusExceptionTest.java` | — |
| 371 | A | `src/test/java/com/aws/carddemo/exception/GlobalExceptionHandlerTest.java` | — |
| 372 | A | `src/test/java/com/aws/carddemo/exception/LogicErrorTest.java` | — |
| 373 | A | `src/test/java/com/aws/carddemo/exception/RecordNotFoundExceptionTest.java` | — |
| 374 | A | `src/test/java/com/aws/carddemo/exception/ResourceUnavailableTest.java` | — |
| 375 | A | `src/test/java/com/aws/carddemo/repository/AccountRepositoryIT.java` | — |
| 376 | A | `src/test/java/com/aws/carddemo/repository/CardRepositoryIT.java` | — |
| 377 | A | `src/test/java/com/aws/carddemo/repository/CardXrefRepositoryIT.java` | — |
| 378 | A | `src/test/java/com/aws/carddemo/repository/CustomerRepositoryIT.java` | — |
| 379 | A | `src/test/java/com/aws/carddemo/repository/DisclosureGroupRepositoryIT.java` | — |
| 380 | A | `src/test/java/com/aws/carddemo/repository/TransactionCategoryBalanceRepositoryIT.java` | — |
| 381 | A | `src/test/java/com/aws/carddemo/repository/TransactionCategoryRepositoryIT.java` | — |
| 382 | A | `src/test/java/com/aws/carddemo/repository/TransactionRepositoryIT.java` | — |
| 383 | A | `src/test/java/com/aws/carddemo/repository/TransactionTypeRepositoryIT.java` | — |
| 384 | A | `src/test/java/com/aws/carddemo/repository/UserSecurityRepositoryIT.java` | — |
| 385 | A | `src/test/java/com/aws/carddemo/security/CardDemoAuthenticationProviderTest.java` | — |
| 386 | A | `src/test/java/com/aws/carddemo/security/CardDemoUserDetailsServiceIT.java` | — |
| 387 | A | `src/test/java/com/aws/carddemo/security/CardDemoUserDetailsServiceTest.java` | — |
| 388 | A | `src/test/java/com/aws/carddemo/security/CardDemoUserDetailsTest.java` | — |
| 389 | A | `src/test/java/com/aws/carddemo/service/online/AccountUpdateServiceTest.java` | — |
| 390 | A | `src/test/java/com/aws/carddemo/service/online/AccountViewServiceTest.java` | — |
| 391 | A | `src/test/java/com/aws/carddemo/service/online/AdminMenuServiceTest.java` | — |
| 392 | A | `src/test/java/com/aws/carddemo/service/online/BillPayServiceTest.java` | — |
| 393 | A | `src/test/java/com/aws/carddemo/service/online/CardDetailServiceTest.java` | — |
| 394 | A | `src/test/java/com/aws/carddemo/service/online/CardListServiceTest.java` | — |
| 395 | A | `src/test/java/com/aws/carddemo/service/online/CardUpdateServiceTest.java` | — |
| 396 | A | `src/test/java/com/aws/carddemo/service/online/MainMenuServiceTest.java` | — |
| 397 | A | `src/test/java/com/aws/carddemo/service/online/ReportSubmitServiceTest.java` | — |
| 398 | A | `src/test/java/com/aws/carddemo/service/online/SignonServiceTest.java` | — |
| 399 | A | `src/test/java/com/aws/carddemo/service/online/TransactionAddServiceTest.java` | — |
| 400 | A | `src/test/java/com/aws/carddemo/service/online/TransactionListServiceTest.java` | — |
| 401 | A | `src/test/java/com/aws/carddemo/service/online/TransactionViewServiceTest.java` | — |
| 402 | A | `src/test/java/com/aws/carddemo/service/online/UserAddServiceTest.java` | — |
| 403 | A | `src/test/java/com/aws/carddemo/service/online/UserDeleteServiceTest.java` | — |
| 404 | A | `src/test/java/com/aws/carddemo/service/online/UserListServiceTest.java` | — |
| 405 | A | `src/test/java/com/aws/carddemo/service/online/UserUpdateServiceTest.java` | — |
| 406 | A | `src/test/java/com/aws/carddemo/util/CobolDecimalTest.java` | — |
| 407 | A | `src/test/java/com/aws/carddemo/util/DateConversionServiceTest.java` | — |
| 408 | A | `src/test/java/com/aws/carddemo/util/DateConversionSupportTest.java` | — |
| 409 | A | `src/test/java/com/aws/carddemo/util/FixedWidthRecordMapperTest.java` | — |
| 410 | A | `src/test/java/com/aws/carddemo/util/PfKeyHandlerTest.java` | — |
| 411 | A | `src/test/java/com/aws/carddemo/util/constants/MessagesTest.java` | — |
| 412 | A | `src/test/java/com/aws/carddemo/util/constants/ScreenAttributesTest.java` | — |
| 413 | A | `src/test/java/com/aws/carddemo/util/constants/ScreenTitlesTest.java` | — |
| 414 | A | `src/test/java/com/aws/carddemo/web/controller/AccountControllerIT.java` | — |
| 415 | A | `src/test/java/com/aws/carddemo/web/controller/AdminMenuControllerIT.java` | — |
| 416 | A | `src/test/java/com/aws/carddemo/web/controller/BillPayControllerIT.java` | — |
| 417 | A | `src/test/java/com/aws/carddemo/web/controller/CardControllerIT.java` | — |
| 418 | A | `src/test/java/com/aws/carddemo/web/controller/MenuControllerIT.java` | — |
| 419 | A | `src/test/java/com/aws/carddemo/web/controller/ReportControllerIT.java` | — |
| 420 | A | `src/test/java/com/aws/carddemo/web/controller/SignonControllerIT.java` | — |
| 421 | A | `src/test/java/com/aws/carddemo/web/controller/TransactionControllerIT.java` | — |
| 422 | A | `src/test/java/com/aws/carddemo/web/controller/UserAdminControllerIT.java` | — |
| 423 | A | `src/test/resources/junit-platform.properties` | — |

#### Documentation (docs/**) — 4 operation(s), indices 424–427

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 424 | A | `docs/architecture/architecture.md` | — |
| 425 | A | `docs/decision-log.md` | — |
| 426 | A | `docs/onboarding.md` | — |
| 427 | A | `docs/traceability-matrix.md` | — |

#### Executive deck (blitzy-deck/**) — 2 operation(s), indices 428–429

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 428 | A | `blitzy-deck/index.html` | — |
| 429 | A | `blitzy-deck/references/blitzy-reveal-theme.css` | — |

#### Observability dashboard (observability/**) — 1 operation(s), indices 430–430

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 430 | A | `observability/grafana-dashboard.json` | — |

#### Evidence screenshots — ASSET (blitzy/screenshots/**) — 2 operation(s), indices 431–432

| # | Op | Path | Relocated from |
|---:|:--:|------|----------------|
| 431 | A | `blitzy/screenshots/COUSR03_delete_user_desktop_920.png` | — |
| 432 | A | `blitzy/screenshots/COUSR03_delete_user_error_state.png` | — |

#### Directory deletion (meta-operation) — 1 operation, index 433

| # | Op | Path | Note |
|---:|:--:|------|------|
| 433 | D | `app/` | Deletion recorded only after all 148 children verified byte-identical under `legacy/` (R100). |
