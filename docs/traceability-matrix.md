# CardDemo COBOL → Java Traceability Matrix

This document is the **bidirectional traceability matrix** mandated by the **Explainability rule**
(see Technical Specification §0.7.2 and §0.6.10). It maps **every** legacy COBOL / z/OS construct in
the AWS CardDemo application to the Java / Spring Boot artifact that **replaces** it,
and it defines the reverse-direction convention so that any Java class can be
traced back to its originating `legacy/` source.

- **Source baseline:** AWS CardDemo — an IBM z/OS credit-card management application built on
  **COBOL, CICS, VSAM, JCL, BMS and RACF**.
- **Target:** a single-module **Java 25 LTS + Spring Boot 3.5.16** layered application
  (`web/controller` → `service` → `repository` → PostgreSQL, with Spring Batch for JCL and Spring
  Security for signon/RACF) under the base package `com.aws.carddemo`.
- **Direction:** *forward* = COBOL construct → Java artifact (Sections 2–7); *reverse* = Java
  artifact → `legacy/` source path via a Javadoc origin-tag convention (Section 13).

> **Implementation status (read first).** The migration has generated the full layered application,
> so this matrix maps every legacy construct to a Java artifact that **exists now**. What that means
> for how to read it:
>
> - **Legacy side — present and verified.** Every `legacy/**` source cited below **exists now** in
>   the repository (the COBOL tree was moved read-only from `app/**` to `legacy/**` per Technical
>   Specification §0.2.2 / §0.4.1, and the 148-file inventory has been restored and byte-verified).
>   All source citations, counts, offsets, and quirks in this document are checked against those
>   files.
> - **Java side — fully generated.** The complete Java application exists under `src/main/java/**`:
>   all **10** domain entities plus the reference/enum layer; the DTO layer (screen forms, menu,
>   report, session context); the **10** Spring Data repositories; the **17** online services and the
>   **9** `web/controller` routes covering all 18 CICS transaction ids; the business Spring Batch jobs
>   plus the `CBADMCDJ` admin-driver config; the CSD-derived `SecurityConfig`; the `CSSTRPFY` /
>   `CSUTLDPY` / `CSUTLDWY` utilities (`util/PfKeyHandler`, `util/DateConversionSupport`); the
>   `CSUTLDTC` date utility; the cross-cutting `config` / `exception` (incl. `GlobalExceptionHandler`)
>   / `security` / `util` classes — together with `application.yml`, the `application-dev.yml` /
>   `application-test.yml` profiles, `logback-spring.xml`, the Thymeleaf `templates/` (17 screens),
>   the Flyway migrations `V0__spring_batch_metadata.sql` / `V1__schema.sql` / `V2__reference_data.sql`
>   / `V3__indexes.sql` / `V4__card_xref_unique_card_num.sql`, and the `src/test/**` suites (**120 of 123** primary constructs have their
>   target artifact present; the other **3** are the explicitly logged intentional non-migrations —
>   see [§1](#1-coverage-summary)). Java paths in the forward tables denote artifacts that exist now.
> - **Coverage claim, stated precisely.** This matrix guarantees **100 % inventory coverage** —
>   every legacy construct is mapped to its generated Java target or accounted for as an explicitly
>   logged intentional non-migration, with no *unaccounted* constructs — **and** that implementation
>   is complete for every migratable construct (see [§1 Coverage Summary](#1-coverage-summary)).
> - **Reverse direction — applied across the generated code.** The reverse Javadoc origin-tags
>   described in [§13](#13-reverse-direction-convention-java--legacy) are **present on the generated
>   `src/main/java/**` classes**: files carry an explicit `Origin:` tag citing their `legacy/` source
>   (several also cite it in class-Javadoc prose).

> **Legacy path convention.** All source citations use the `legacy/` prefix while preserving the
> original subfolder names and exact file-name casing (`legacy/cbl/…`, `legacy/cpy/…`,
> `legacy/cpy-bms/…`, `legacy/bms/…`, `legacy/jcl/…`, `legacy/proc/…`, `legacy/ctl/…`,
> `legacy/csd/…`, `legacy/catlg/…`, `legacy/data/…`).
>
> **Related documents.** Consolidation decisions and every intentional gap are explained in
> [`decision-log.md`](./decision-log.md). The before/after architecture diagrams appear inline in
> the Technical Specification (§0.1.2) and in
> [`architecture/architecture.md`](./architecture/architecture.md), each with a descriptive title
> and legend.

---

## Table of Contents

1. [Coverage Summary](#1-coverage-summary)
2. [Online COBOL Programs → Service + Controller](#2-online-cobol-programs--service--controller)
3. [Batch COBOL Programs + JCL → Spring Batch Jobs](#3-batch-cobol-programs--jcl--spring-batch-jobs)
4. [Copybooks → Entities / DTOs / Enums / Constants](#4-copybooks--entities--dtos--enums--constants)
5. [Repositories (One per VSAM File)](#5-repositories-one-per-vsam-file)
6. [BMS Maps → Thymeleaf Templates + Screen Form DTOs](#6-bms-maps--thymeleaf-templates--screen-form-dtos)
7. [Infrastructure JCL / PROC / CTL / CSD → Schema, Config, Sort](#7-infrastructure-jcl--proc--ctl--csd--schema-config-sort)
8. [Construct-Level Traceability (Paragraphs, Executable Copybooks, Subprograms)](#8-construct-level-traceability-paragraphs-executable-copybooks-subprograms)
   - 8.1 [Exhaustive Paragraph / Section → `class.method` → Test Enumeration](#81-exhaustive-paragraph--section--classmethod--test-enumeration)
9. [Exact Record Contracts (Offsets, Keys, Signs, FILLER)](#9-exact-record-contracts-offsets-keys-signs-filler)
10. [Source-Quirk Ledger (Preserve-or-Deviate + Parity-Test Obligations)](#10-source-quirk-ledger-preserve-or-deviate--parity-test-obligations)
11. [Sensitive-Field Classification & Authorization Matrix](#11-sensitive-field-classification--authorization-matrix)
12. [Intentional Non-Migrations (Gaps Ledger)](#12-intentional-non-migrations-gaps-ledger)
13. [Reverse-Direction Convention (Java → Legacy)](#13-reverse-direction-convention-java--legacy)

---

## 1. Coverage Summary

The table below counts every legacy construct type and confirms that each is either **mapped** to a
Java target that exists now or **accounted-for** as a logged intentional non-migration. The full
Java source tree is generated, so **120 of 123** primary constructs have their target artifact
present; the remaining **3** are the explicitly logged intentional non-migrations (`UNUSED1Y.cpy`
dead code, and `OPENFIL.jcl` / `CLOSEFIL.jcl` — N/A under a Spring-managed connection pool).

| Construct type | Count (present at HEAD) | Inventoried & assigned a target / logged gap | Constructs with target artifact present | Unaccounted constructs |
| :------------- | ----: | ---------------------: | ---: | --------------: |
| COBOL programs (`legacy/cbl/*.cbl`, `*.CBL`) | 28 | 28 | 28 | 0 |
| Copybooks (`legacy/cpy/*.cpy`, `*.CPY`) | 28 | 28 *(incl. `UNUSED1Y` logged as dead code)* | 27 *(all but `UNUSED1Y`)* | 0 |
| BMS map sources (`legacy/bms/*.bms`) | 17 | 17 | 17 | 0 |
| Symbolic map copybooks (`legacy/cpy-bms/*.CPY`) | 17 | 17 | 17 | 0 |
| JCL jobs (`legacy/jcl/*.jcl`, `*.JCL`) | 29 | 29 *(incl. `OPENFIL`/`CLOSEFIL` logged as N/A)* | 27 *(all but `OPENFIL`/`CLOSEFIL`)* | 0 |
| PROCs (`legacy/proc/*.prc`) | 2 | 2 | 2 | 0 |
| Sort control (`legacy/ctl/*.ctl`) | 1 | 1 | 1 | 0 |
| CICS resource definitions (`legacy/csd/CARDDEMO.CSD`) | 1 | 1 *(incl. `COCRDSEC` program logged → `SecurityConfig`)* | 1 | 0 |
| **Total primary constructs** | **123** | **123** | **120** | **0** |

**Paragraph-level coverage:** beyond this construct-level roll-up, every one of the **528**
`PROCEDURE DIVISION` paragraph/section declarations across the 28 programs is enumerated and bound
to its target `class.method` and covering test(s) in
[§8.1](#81-exhaustive-paragraph--section--classmethod--test-enumeration); that appendix is
generated directly from `legacy/cbl/**` and reproduces the authoritative 528 / 527-unique counts,
so forward traceability is complete at paragraph granularity with no gaps.

**How to read this:** *inventory* coverage is **100 %** (no legacy construct is unaccounted-for),
and *implementation* coverage is complete for every migratable construct: **120 of 123** primary
constructs have their target Java artifact generated under `src/main/java/**` — all **10** domain
entities plus the reference/DTO layer they depend on, the **17** screen-form DTOs and report DTOs,
the **10** repositories, the **17** online services + **9** controllers, the business batch jobs, the
`SecurityConfig` / routing derived from the CSD, the `CSSTRPFY` / `CSUTLDPY` / `CSUTLDWY` PF-key &
date-support utilities (`util/PfKeyHandler`, `util/DateConversionSupport`), the `CSUTLDTC` date
utility (`util/DateConversionService`), the **17** BMS screens (Thymeleaf templates), the
`V1`–`V3` Flyway schema/seed/index migrations, and the cross-cutting `config` / `exception` /
`security` / `util` foundation, plus the `CBADMCDJ` admin driver (`batch/AdminBatchJobConfig`, a
documented no-op `Tasklet`). The remaining **3** constructs are the explicitly logged intentional
non-migrations — `UNUSED1Y.cpy` (dead code) and `OPENFIL.jcl` / `CLOSEFIL.jcl` (N/A under a
Spring-managed connection pool) — recorded in the [Gaps ledger](#12-intentional-non-migrations-gaps-ledger).

**Reference-only artifacts** (retained as parity oracles / schema inputs, not transformed into a
single output, per Technical Specification §0.2.1): the VSAM catalog listing
`legacy/catlg/LISTCAT.txt` (informs the Flyway schema), the **9** ASCII fixtures under
`legacy/data/ASCII/` (Flyway `V2` seed + JUnit fixtures) and the **12** native EBCDIC datasets
under `legacy/data/EBCDIC/` (binary source-of-truth).

The **28 COBOL programs** decompose as **10 batch** + **17 online** + **1 date utility**
(`CSUTLDTC`). The CSD additionally defines the **18th** CICS program/transaction `COCRDSEC`/`CDV1`,
which has **no `.cbl`** and is realized as Spring Security authorization in `config/SecurityConfig` — see the
[Gaps ledger](#12-intentional-non-migrations-gaps-ledger).

---

## 2. Online COBOL Programs → Service + Controller

Each of the 17 online (pseudo-conversational) programs becomes one `@Service` (business
logic) and one Spring MVC controller **route**, keyed to its CICS transaction id. The 18th CSD
program (`COCRDSEC`, transaction `CDV1`) has no `.cbl` and is realized purely as Spring
Security authorization in `config/SecurityConfig`. *(All Java paths below exist now.)*

| CICS Tran | COBOL Program (legacy) | Java Service | Java Controller / route | Screen / Function |
| :-------- | :--------------------- | :----------- | :---------------------- | :---------------- |
| `CC00` | `legacy/cbl/COSGN00C.cbl` | `service/online/SignonService.java` | `web/controller/SignonController.java` | Signon |
| `CM00` | `legacy/cbl/COMEN01C.cbl` | `service/online/MainMenuService.java` | `web/controller/MenuController.java` | Main Menu |
| `CA00` | `legacy/cbl/COADM01C.cbl` | `service/online/AdminMenuService.java` | `web/controller/AdminMenuController.java` | Admin Menu |
| `CAVW` | `legacy/cbl/COACTVWC.cbl` | `service/online/AccountViewService.java` | `web/controller/AccountController.java` (view) | Account View |
| `CAUP` | `legacy/cbl/COACTUPC.cbl` | `service/online/AccountUpdateService.java` | `AccountController` (update) | Account Update |
| `CCLI` | `legacy/cbl/COCRDLIC.cbl` | `service/online/CardListService.java` | `web/controller/CardController.java` (list) | Card List |
| `CCDL` | `legacy/cbl/COCRDSLC.cbl` | `service/online/CardDetailService.java` | `CardController` (view) | Card Detail |
| `CCUP` | `legacy/cbl/COCRDUPC.cbl` | `service/online/CardUpdateService.java` | `CardController` (update) | Card Update |
| `CT00` | `legacy/cbl/COTRN00C.cbl` | `service/online/TransactionListService.java` | `web/controller/TransactionController.java` (list) | Transaction List |
| `CT01` | `legacy/cbl/COTRN01C.cbl` | `service/online/TransactionViewService.java` | `TransactionController` (view) | Transaction View |
| `CT02` | `legacy/cbl/COTRN02C.cbl` | `service/online/TransactionAddService.java` | `TransactionController` (add) | Transaction Add |
| `CB00` | `legacy/cbl/COBIL00C.cbl` | `service/online/BillPayService.java` | `web/controller/BillPayController.java` | Bill Pay |
| `CR00` | `legacy/cbl/CORPT00C.cbl` | `service/online/ReportSubmitService.java` | `web/controller/ReportController.java` | Transaction Reports (batch submit) |
| `CU00` | `legacy/cbl/COUSR00C.cbl` | `service/online/UserListService.java` | `web/controller/UserAdminController.java` (list) | List Users |
| `CU01` | `legacy/cbl/COUSR01C.cbl` | `service/online/UserAddService.java` | `UserAdminController` (add) | Add User |
| `CU02` | `legacy/cbl/COUSR02C.cbl` | `service/online/UserUpdateService.java` | `UserAdminController` (update) | Update User |
| `CU03` | `legacy/cbl/COUSR03C.cbl` | `service/online/UserDeleteService.java` | `UserAdminController` (delete) | Delete User |
| `CDV1` | `legacy/csd/CARDDEMO.CSD` (`COCRDSEC` program — no `.cbl`) | _(none — security cross-cut)_ | `config/SecurityConfig.java` (method/URL authorization) | Card-detail security variant — **intentional non-migration, see [§12](#12-intentional-non-migrations-gaps-ledger)** |

> **Paragraph → method granularity.** Each online program's numbered paragraphs (e.g. `0000-`,
> `1000-`, `9000-`) map to methods on the corresponding `@Service`, preserving the
> original `PERFORM`/`EVALUATE` control flow (`PERFORM` → method call, `EVALUATE` → `switch`,
> `PERFORM UNTIL` → loop; Technical Specification §0.3.3). This section maps at **program → class**
> granularity; the paragraph-level inventory (counts, the one duplicate label, and the executable
> copybooks) is detailed in [§8](#8-construct-level-traceability-paragraphs-executable-copybooks-subprograms).
> Inter-program `XCTL`/`LINK` transfers (e.g. `COSGN00C` → `COADM01C`/`COMEN01C` after
> authentication) become role-based controller redirects driven by a server-side allowlist (see
> [§11](#11-sensitive-field-classification--authorization-matrix)).

---

## 3. Batch COBOL Programs + JCL → Spring Batch Jobs

Each business batch program becomes a Spring Batch `@Configuration` `Job` composed of
chunk-oriented `Step`s (reader → processor → writer) or a `Tasklet` for single-action utilities. The
driving JCL job (and PROC, where present) is preserved as step topology and job parameters. The date
utility `CSUTLDTC` is a called subroutine rather than a job and becomes a `@Service`.

**Naming vs. behavior:** the Java `@Configuration` names below are the **frozen** artifact names from
the migration plan (Technical Specification §0.4.1). Where the frozen name reads more broadly than
the COBOL program actually behaves, the true source behavior is stated in the table and reconciled in
the footnotes — the name is preserved, the description is corrected.

| Business function (source behavior) | COBOL Program (legacy) | JCL / PROC (legacy) | Java Batch `@Configuration` / util (frozen name) |
| :---------------- | :--------------------- | :------------------ | :--------------------------------- |
| Post daily transactions | `legacy/cbl/CBTRN02C.cbl` | `legacy/jcl/POSTTRAN.jcl` | `batch/PostTransactionJobConfig.java` — reader = `DailyTransaction`, processor = validate xref + acct + credit-limit, writers = Transaction / TCATBAL / Account; over-limit → **430-byte** reject record. **`2900-WRITE-TRANSACTION-FILE` duplicate-key parity (S2):** the writer guards the insert with `transactionRepository.existsById` and throws `DuplicateKeyException` (FILE STATUS `22`) before any save, reproducing the COBOL `9999-ABEND-PROGRAM` (non-fault-tolerant step → chunk rollback → job FAILED) instead of a silent JPA merge. **Batch concurrency parity (S3):** the account read-for-update uses `AccountRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`), reproducing the `POSTTRAN` `DISP=OLD` dataset-level ENQ so concurrent launches cannot lose updates. **RETURN-CODE ladder (S4):** `CardDemoApplication.BatchReturnCodeExitCodeGenerator` maps the job outcome to process exit `0`/`4`/`8` (clean / with-rejects / failed-abend) — see `decision-log.md` (S2/S3/S4 rows) |
| Monthly interest calc | `legacy/cbl/CBACT04C.cbl` | `legacy/jcl/INTCALC.jcl` | `batch/InterestCalcJobConfig.java` — `(bal × rate) / 1200` truncated to 2 dp (`RoundingMode.DOWN`, no `ROUNDED` in source); `PARM` date → `JobParameter` |
| Statement generation | `legacy/cbl/CBSTM03A.CBL` (driver) + `legacy/cbl/CBSTM03B.CBL` (I/O subprogram) | `legacy/jcl/CREASTMT.JCL` | `batch/StatementJobConfig.java` — the `CBSTM03B` flag-driven I/O subprogram is absorbed into typed Spring Data repository calls (not a standalone class; see [§8](#8-construct-level-traceability-paragraphs-executable-copybooks-subprograms)); statement layout from `COSTM01` |
| Account file read/print | `legacy/cbl/CBACT01C.cbl` | `legacy/jcl/READACCT.jcl` | `batch/AccountPrintJobConfig.java` |
| Card file read/print | `legacy/cbl/CBACT02C.cbl` | `legacy/jcl/READCARD.jcl` | `batch/CardPrintJobConfig.java` |
| Xref file read/print | `legacy/cbl/CBACT03C.cbl` | `legacy/jcl/READXREF.jcl` | `batch/XrefPrintJobConfig.java` |
| Customer file **read/print** (`OPEN INPUT` + `READ` + `DISPLAY`) [^cbcus01c] | `legacy/cbl/CBCUS01C.cbl` | `legacy/jcl/READCUST.jcl` | `batch/CustomerLoadJobConfig.java` *(frozen name; source reads & prints — it does not load)* |
| Daily-transaction-feed **validation** (input-only) [^cbtrn01c] | `legacy/cbl/CBTRN01C.cbl` | `legacy/jcl/TRANBKP.jcl` | `batch/TransactionBackupJobConfig.java` *(frozen name; see footnote — the program validates the feed, the JCL is an IDCAMS backup)* |
| Transaction report | `legacy/cbl/CBTRN03C.cbl` | `legacy/jcl/TRANREPT.jcl` + `legacy/proc/TRANREPT.prc` | `batch/TransactionReportJobConfig.java` |
| Date validation utility | `legacy/cbl/CSUTLDTC.cbl` | _(called via Language Environment — no standalone JCL)_ | `util/DateConversionService.java` — wraps **`CEEDAYS`** only (validates a date string against a format mask, returning a Lillian day + feedback code) [^csutldtc] |

[^cbcus01c]: **`CBCUS01C` behavior correction.** The source opens the customer file `OPEN INPUT`,
    reads each record (`READ CUSTFILE-FILE INTO CUSTOMER-RECORD`) and writes it to the job log via
    `DISPLAY CUSTOMER-RECORD` — it is a **read-and-print** utility, not a data *load*. The frozen
    plan name `CustomerLoadJobConfig` is retained for traceability, but the target reproduces
    read/print behavior (and its sensitive-record output is governed by
    [§11](#11-sensitive-field-classification--authorization-matrix)). No write/load path is
    introduced that the source does not have.

[^cbtrn01c]: **`CBTRN01C` + `TRANBKP.jcl` behavior correction.** Two distinct source members are
    grouped under the frozen name `TransactionBackupJobConfig`: (1) **`CBTRN01C`** opens the daily
    transaction feed (`SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN`), reads each record, cross-references
    it against XREF/ACCOUNT/CARD and `DISPLAY`s results — it is **input-only daily-feed validation**,
    not a backup; it writes no transaction file. (2) **`TRANBKP.jcl`** executes **`IDCAMS REPRO`**
    copying `TRANSACT.VSAM.KSDS` → `TRANSACT.BKUP(+1)` (a GDG generation) and contains **no COBOL
    program**. The frozen name is preserved; the target reproduces the feed-validation behavior of
    `CBTRN01C`, and the IDCAMS backup semantics of `TRANBKP.jcl` are realized as a Spring Batch
    job-instance-versioned copy step. Neither source member is silently dropped.

[^csutldtc]: **`CSUTLDTC` behavior correction.** The source `CALL`s only **`CEEDAYS`** (confirmed:
    the single `CALL "CEEDAYS" USING …` at `legacy/cbl/CSUTLDTC.cbl:116`; the header comments name
    only CEEDAYS). It does **not** call `CEEDATE` or `CEECBLDY`. `DateConversionService` therefore
    reproduces the `CEEDAYS` validation contract (valid flag + feedback-code enum + Lillian/epoch-day
    equivalent) using `java.time.DateTimeFormatter`; it does not claim to replace the two LE services
    that the source never invokes.

> This table covers **all 10 batch programs** plus the `CSUTLDTC` date utility (11 programs in
> total; the statement row bundles the `CBSTM03A` driver with its `CBSTM03B` I/O subprogram). The
> remaining infrastructure JCL jobs (IDCAMS define/load, GDG, sort, file open/close) are mapped in
> [§7](#7-infrastructure-jcl--proc--ctl--csd--schema-config-sort).

---

## 4. Copybooks → Entities / DTOs / Enums / Constants

All 28 copybooks are accounted for. Record layouts with a VSAM home become JPA
`@Entity` classes; support copybooks become DTOs, enums or constant holders; the **executable**
copybooks (which contain procedure logic, not just data) become Java **behavior** rather than passive
constants — see the callouts below and [§8](#8-construct-level-traceability-paragraphs-executable-copybooks-subprograms).

> **Decimal representation (correcting the COMP-3 conflation).** The external record monetary fields
> are **signed DISPLAY / zoned decimal** with an implied scale and an **overpunch sign on the trailing
> byte** (e.g. `PIC S9(10)V99` occupies 12 bytes, character-encoded) — they are **not** packed
> `COMP-3`. Only *internal working-storage* fields in some programs use `COMP-3`. All monetary values
> map to `java.math.BigDecimal`, while the `FixedWidthRecordMapper` preserves the original zoned/overpunch
> character representation for flat-file feeds. See [§9](#9-exact-record-contracts-offsets-keys-signs-filler).

| Copybook (`legacy/cpy`) | VSAM file / role | Java artifact |
| :---------------------- | :--------------- | :------------ |
| `CVACT01Y.cpy` | `ACCTDAT` (account record, 300 B) | `domain/Account.java` |
| `CVACT02Y.cpy` | `CARDDAT` (card record, 150 B) | `domain/Card.java` |
| `CVACT03Y.cpy` | `CCXREF` (card-xref record, 50 B) | `domain/CardXref.java` |
| `CVCUS01Y.cpy` | `CUSTDAT` (customer record, 500 B) | `domain/Customer.java` *(consolidated with `CUSTREC.cpy`; see DOB-alias note below)* |
| `CUSTREC.cpy` | `CUSTDAT` (same 500 B layout, DOB data-name differs) | `domain/Customer.java` *(consolidated — see [`decision-log.md`](./decision-log.md) and note below)* |
| `CVTRA05Y.cpy` | `TRANSACT` (transaction record, 350 B) | `domain/Transaction.java` |
| `CVTRA01Y.cpy` | `TCATBAL` (transaction-category balance, 50 B) | `domain/TransactionCategoryBalance.java` |
| `CVTRA02Y.cpy` | `DISCGRP` (disclosure group, 50 B) | `domain/DisclosureGroup.java` |
| `CVTRA03Y.cpy` | `TRANTYPE` (transaction type, 60 B) | `domain/TransactionType.java` |
| `CVTRA04Y.cpy` | `TRANCATG` (transaction category, 60 B) | `domain/TransactionCategory.java` |
| `CSUSR01Y.cpy` | `USRSEC` (security user data, 80 B) | `domain/UserSecurity.java` |
| `COCOM01Y.cpy` | COMMAREA (pseudo-conversational state, 160 B) | `dto/CardDemoContext.java` |
| `CVTRA06Y.cpy` | `DALYTRAN` feed (batch input, 350 B) | `dto/DailyTransaction.java` |
| `COSTM01.CPY` | statement layout (`TRNX-RECORD`, 350 B) | `dto/StatementTransaction.java` |
| `CVTRA07Y.cpy` | report layout — **7 `01`-level groups** (headers + detail + totals) | `dto/report/ReportNameHeader.java`, `dto/report/TransactionReportHeaders.java` *(consolidates the two page-header `01` groups — see [`decision-log.md`](./decision-log.md))*, `dto/report/TransactionDetailReport.java`, `dto/report/ReportPageTotals.java`, `dto/report/ReportAccountTotals.java`, `dto/report/ReportGrandTotals.java`, with `dto/report/ReportAmountFormatter.java` providing the shared `PIC -ZZZ,ZZZ,ZZZ.ZZ` edit-mask formatting *(concrete DTOs — no wildcard)* |
| `CVCRD01Y.cpy` | card work areas (`CC-WORK-AREAS`) | `dto/CardWorkArea.java` *(AID/PFK 88-levels → PF-key enum)* |
| `COADM02Y.cpy` | admin menu options | `dto/menu/AdminMenuOptions.java` |
| `COMEN02Y.cpy` | main menu options *(note: active Transaction Add is user-accessible — quirk #8, [§10](#10-source-quirk-ledger-preserve-or-deviate--parity-test-obligations))* | `dto/menu/MainMenuOptions.java` |
| `CSDAT01Y.cpy` | working date structure | `dto/DateStruct.java` |
| `COTTL01Y.cpy` | screen title constants | `util/constants/ScreenTitles.java` |
| `CSLKPCDY.cpy` | reference / lookup codes *(frozen lookup tables — quirk #10)* | `domain/enums/LookupCodes.java` |
| `CSMSG01Y.cpy` | **message constants** (`01 CCDA-COMMON-MESSAGES` — `CCDA-MSG-*` `PIC X(50)` VALUEs) | `util/constants/Messages.java` |
| `CSMSG02Y.cpy` | **`01 ABEND-DATA` abend work area** — `ABEND-CODE X(4)`, `ABEND-CULPRIT X(8)`, `ABEND-REASON X(50)`, `ABEND-MSG X(72)` | `util/constants/Messages.java` — the four `ABEND-DATA` field widths are captured as the `ABEND_CODE_LENGTH` (4), `ABEND_CULPRIT_LENGTH` (8), `ABEND_REASON_LENGTH` (50), `ABEND_MSG_LENGTH` (72) constants, **consolidated with `CSMSG01Y`** into a single `Messages` class whose Javadoc cites both copybook origins. No separate `AbendData` type exists (the abend fields are intentionally consolidated onto `Messages`). |
| `CSSETATY.cpy` | **executable `COPY … REPLACING` screen-attribute logic** (`IF FLG-(TESTVAR1)-NOT-OK/BLANK … MOVE DFHRED …` / `MOVE '*' …`) — **not** passive constants | screen-attribute handling logic emitted where the `COPY … REPLACING` was expanded (field-error highlight/`*` behavior); see [§8](#8-construct-level-traceability-paragraphs-executable-copybooks-subprograms) |
| `CSSTRPFY.cpy` | **executable PF-key store logic** — 2 paragraphs (`YYYY-STORE-PFKEY`, `YYYY-STORE-PFKEY-EXIT`) | `util/PfKeyHandler.java` (behavior ported, not merely a constant table) |
| `CSUTLDPY.cpy` | **14 executable date-validation paragraphs** (`EDIT-DATE-CCYYMMDD`, `EDIT-YEAR-CCYY`, `EDIT-MONTH`, `EDIT-DAY`, `EDIT-DAY-MONTH-YEAR`, `EDIT-DATE-LE`, `EDIT-DATE-OF-BIRTH`, + their `-EXIT`s) — **not** mere linkage | `util/DateConversionSupport.java` *(the 14 paragraphs → validation methods; merged with `CSUTLDWY` working storage)* |
| `CSUTLDWY.cpy` | date working storage (century/window rules — quirk #10) | `util/DateConversionSupport.java` *(merged with `CSUTLDPY`)* |
| `UNUSED1Y.cpy` | _(not referenced by any program)_ | **NOT MIGRATED — dead code, intentional gap, see [§12](#12-intentional-non-migrations-gaps-ledger)** |

> **Customer DOB data-name alias (M-06 / quirk #7).** `CVCUS01Y.cpy` and `CUSTREC.cpy` describe the
> **same 500-byte `01 CUSTOMER-RECORD` layout with byte-for-byte identical offsets**, but they are
> **not textually identical**: the date-of-birth field (`PIC X(10)` at bytes **309–318**, the 19th
> field) is named **`CUST-DOB-YYYYMMDD` in `CUSTREC.cpy`** and **`CUST-DOB-YYYY-MM-DD` in
> `CVCUS01Y.cpy`**. This is a data-name alias only (same position, length, and picture), so
> consolidation into a single `domain.Customer` is safe; the field is documented as carrying both
> source aliases. This matches the corresponding entry in [`decision-log.md`](./decision-log.md).

---

## 5. Repositories (One per VSAM File)

Each of the six base VSAM KSDS files plus the four reference/composite files becomes
exactly one Spring Data JPA repository (10 repositories total). Alternate indexes become secondary DB
indexes (`V3__indexes.sql`) plus Spring Data **derived queries** (Technical Specification
§0.6.2).

| VSAM file | Copybook | Java Repository | Key / alternate-index handling |
| :-------- | :------- | :-------------- | :----------------------------- |
| `ACCTDAT` | `CVACT01Y` | `repository/AccountRepository.java` | PK `acctId`; `findByIdForUpdate` = the same PK read under a `PESSIMISTIC_WRITE` lock (`SELECT … FOR UPDATE`), **batch-only** (consumed solely by `PostTransactionJobConfig`) to reproduce the `POSTTRAN` `DISP=OLD` dataset-level ENQ (S3; see [`decision-log.md`](./decision-log.md)); the online update path stays lock-free (compare-before-rewrite) |
| `CARDDAT` | `CVACT02Y` | `repository/CardRepository.java` | PK `cardNum`; `findByCardAcctId` replaces the `CARDAIX` alt index |
| `CCXREF` | `CVACT03Y` | `repository/CardXrefRepository.java` | composite `@IdClass` (card + customer + account) **plus a `UNIQUE` constraint `uk_card_xref_card_num` on the 16-byte card number** (authored by Flyway `V4__card_xref_unique_card_num.sql`, the enforcing DDL authority under `ddl-auto=validate`) to preserve the VSAM KSDS single-key uniqueness (see [`decision-log.md`](./decision-log.md) F6 divergence); `findByXrefAcctId` replaces the nonunique `CXACAIX` alt index |
| `CUSTDAT` | `CVCUS01Y` | `repository/CustomerRepository.java` | PK `custId` |
| `TRANSACT` | `CVTRA05Y` | `repository/TransactionRepository.java` | PK `tranId`; the `TRANIDX` alternate index (`KEYS(26 304)` = `TRAN-PROC-TS`) maps to a secondary index on `proc_ts`; `findByCardNum` is a **separate functional** derived query for card-scoped report access (not the literal AIX) — see [`decision-log.md`](./decision-log.md) F7 |
| `USRSEC` | `CSUSR01Y` | `repository/UserSecurityRepository.java` | lookup by `usrId` (authentication) |
| `TCATBAL` | `CVTRA01Y` | `repository/TransactionCategoryBalanceRepository.java` | composite key (account + type + category) |
| `DISCGRP` | `CVTRA02Y` | `repository/DisclosureGroupRepository.java` | composite key (group + type + category) |
| `TRANTYPE` | `CVTRA03Y` | `repository/TransactionTypeRepository.java` | reference lookup |
| `TRANCATG` | `CVTRA04Y` | `repository/TransactionCategoryRepository.java` | composite key (type + category) |

> **Alternate-index parity.** The CSD defines two alternate-index PATHs — `CARDAIX`
> (`CARDDATA.VSAM.AIX.PATH`) and `CXACAIX` (`CARDXREF.VSAM.AIX.PATH`). The `TRANSACT` file
> additionally carries the `TRANIDX` alternate index, whose CSD key definition is
> `KEYS(26 304) NONUNIQUEKEY` — offset 304, length 26 = **`TRAN-PROC-TS`**, **not** the card number
> (F7). All map to secondary database indexes created in
> `src/main/resources/db/migration/V3__indexes.sql`: `CARDAIX` → `findByCardAcctId`
> (`idx_card_card_acct_id`), `CXACAIX` → `findByXrefAcctId` (`idx_card_xref_xref_acct_id`), and
> `TRANIDX` → a secondary index on `proc_ts` (`idx_transaction_proc_ts`). Card-scoped transaction
> access (`findByCardNum`, `idx_transaction_card_num`) is a **separate functional derived query** — a
> report/query optimization, **not** the literal `TRANIDX` AIX.

---

## 6. BMS Maps → Thymeleaf Templates + Screen Form DTOs

Each of the 17 BMS map sources (with its generated symbolic copybook) becomes one
server-rendered Thymeleaf template and one screen-form DTO. Templates preserve the **24 × 80** field
/ label / length / colour / PF-key contract (PF3 = back, PF7 / PF8 = page up/down, ENTER = submit;
Technical Specification §0.3.4). PF/focus quirks that must be preserved per map are catalogued in
[§10](#10-source-quirk-ledger-preserve-or-deviate--parity-test-obligations) (quirk #14).

| BMS map (`legacy/bms`) | Symbolic copybook (`legacy/cpy-bms`) | Thymeleaf template | Screen form DTO |
| :--------------------- | :----------------------------------- | :----------------- | :-------------- |
| `legacy/bms/COSGN00.bms` | `legacy/cpy-bms/COSGN00.CPY` | `src/main/resources/templates/COSGN00.html` | `dto/screen/COSGN00Form.java` |
| `legacy/bms/COMEN01.bms` | `legacy/cpy-bms/COMEN01.CPY` | `src/main/resources/templates/COMEN01.html` | `dto/screen/COMEN01Form.java` |
| `legacy/bms/COADM01.bms` | `legacy/cpy-bms/COADM01.CPY` | `src/main/resources/templates/COADM01.html` | `dto/screen/COADM01Form.java` |
| `legacy/bms/COACTVW.bms` | `legacy/cpy-bms/COACTVW.CPY` | `src/main/resources/templates/COACTVW.html` | `dto/screen/COACTVWForm.java` |
| `legacy/bms/COACTUP.bms` | `legacy/cpy-bms/COACTUP.CPY` | `src/main/resources/templates/COACTUP.html` | `dto/screen/COACTUPForm.java` |
| `legacy/bms/COCRDLI.bms` | `legacy/cpy-bms/COCRDLI.CPY` | `src/main/resources/templates/COCRDLI.html` | `dto/screen/COCRDLIForm.java` |
| `legacy/bms/COCRDSL.bms` | `legacy/cpy-bms/COCRDSL.CPY` | `src/main/resources/templates/COCRDSL.html` | `dto/screen/COCRDSLForm.java` |
| `legacy/bms/COCRDUP.bms` | `legacy/cpy-bms/COCRDUP.CPY` | `src/main/resources/templates/COCRDUP.html` | `dto/screen/COCRDUPForm.java` |
| `legacy/bms/COBIL00.bms` | `legacy/cpy-bms/COBIL00.CPY` | `src/main/resources/templates/COBIL00.html` | `dto/screen/COBIL00Form.java` |
| `legacy/bms/CORPT00.bms` | `legacy/cpy-bms/CORPT00.CPY` | `src/main/resources/templates/CORPT00.html` | `dto/screen/CORPT00Form.java` |
| `legacy/bms/COTRN00.bms` | `legacy/cpy-bms/COTRN00.CPY` | `src/main/resources/templates/COTRN00.html` | `dto/screen/COTRN00Form.java` |
| `legacy/bms/COTRN01.bms` | `legacy/cpy-bms/COTRN01.CPY` | `src/main/resources/templates/COTRN01.html` | `dto/screen/COTRN01Form.java` |
| `legacy/bms/COTRN02.bms` | `legacy/cpy-bms/COTRN02.CPY` | `src/main/resources/templates/COTRN02.html` | `dto/screen/COTRN02Form.java` |
| `legacy/bms/COUSR00.bms` | `legacy/cpy-bms/COUSR00.CPY` | `src/main/resources/templates/COUSR00.html` | `dto/screen/COUSR00Form.java` |
| `legacy/bms/COUSR01.bms` | `legacy/cpy-bms/COUSR01.CPY` | `src/main/resources/templates/COUSR01.html` | `dto/screen/COUSR01Form.java` |
| `legacy/bms/COUSR02.bms` | `legacy/cpy-bms/COUSR02.CPY` | `src/main/resources/templates/COUSR02.html` | `dto/screen/COUSR02Form.java` |
| `legacy/bms/COUSR03.bms` | `legacy/cpy-bms/COUSR03.CPY` | `src/main/resources/templates/COUSR03.html` | `dto/screen/COUSR03Form.java` |

---

## 7. Infrastructure JCL / PROC / CTL / CSD → Schema, Config, Sort

This section maps every remaining JCL job (those not already covered as business batch programs in
[§3](#3-batch-cobol-programs--jcl--spring-batch-jobs)), plus the 2 PROCs, the sort-control member,
the catalog listing and the CSD. IDCAMS `DEFINE CLUSTER` / `REPRO` become Flyway DDL and seed
data; `SORT` becomes a Java `Comparator` / SQL `ORDER BY`; GDG output becomes job-instance versioning
(Technical Specification §0.6.3).

| Legacy artifact | z/OS utility / role | Java / Spring target |
| :-------------- | :------------------ | :------------------- |
| `legacy/jcl/ACCTFILE.jcl` | IDCAMS define + load Account master | `V1__schema.sql` (account table DDL + load) |
| `legacy/jcl/CARDFILE.jcl` | IDCAMS refresh Card master | `V1__schema.sql` (card table) |
| `legacy/jcl/CUSTFILE.jcl` | IDCAMS refresh Customer master | `V1__schema.sql` (customer table) |
| `legacy/jcl/DEFCUST.jcl` | IDCAMS define Customer cluster | `V1__schema.sql` (customer define) |
| `legacy/jcl/XREFFILE.jcl` | IDCAMS load card/customer/account xref | `V1__schema.sql` (xref table) |
| `legacy/jcl/TRANFILE.jcl` | IDCAMS load Transaction master | `V1__schema.sql` (transaction table) |
| `legacy/jcl/TCATBALF.jcl` | IDCAMS refresh Transaction-category balance | `V1__schema.sql` (tcatbal table) |
| `legacy/jcl/TRANCATG.jcl` | IDCAMS load Transaction category types | `V1__schema.sql` + `V2__reference_data.sql` (seed) |
| `legacy/jcl/TRANTYPE.jcl` | IDCAMS load Transaction type file | `V1__schema.sql` + `V2__reference_data.sql` (seed) |
| `legacy/jcl/DISCGRP.jcl` | IDCAMS load Disclosure Group file | `V1__schema.sql` + `V2__reference_data.sql` (seed) |
| `legacy/jcl/DUSRSECJ.jcl` | IEBGENER initial load of User security | `V1__schema.sql` (usrsec table) + `V2__reference_data.sql` (seed users) |
| `legacy/catlg/LISTCAT.txt` | IDCAMS `LISTCAT` (VSAM cluster metadata) | informs `V1__schema.sql` — **REFERENCE only** |
| `legacy/jcl/TRANIDX.jcl` | IDCAMS define AIX on transaction file (`TRANIDX` = `KEYS(26 304)` = `TRAN-PROC-TS`) | `V3__indexes.sql` (secondary index on `transaction.proc_ts`; **not** `card_num` — see F7) |
| `legacy/jcl/COMBTRAN.jcl` + `legacy/ctl/REPROCT.ctl` + `legacy/proc/REPROC.prc` | `PGM=SORT`, `SORT FIELDS=(TRAN-ID,A)` | `batch/TransactionCombineJobConfig.java` — Java `Comparator` ascending by `tranId` / `ORDER BY tran_id` (deterministic `C`/`POSIX` collation — see `decision-log.md`) |
| `legacy/jcl/DALYREJS.jcl` + `legacy/jcl/DEFGDGB.jcl` | reject dataset (LRECL 430) + GDG base define | reject `FlatFileItemWriter` (350 B transaction + 80 B reason = 430 B) + Spring Batch job-instance versioning (GDG generations) |
| `legacy/jcl/PRTCATBL.jcl` + `legacy/jcl/REPTFILE.jcl` | category-balance / report output | `batch/CategoryBalancePrintJobConfig.java` |
| `legacy/jcl/CBADMCDJ.jcl` | admin batch driver | `batch/AdminBatchJobConfig.java` |
| `legacy/proc/TRANREPT.prc` | transaction-report PROC | batch step configuration for `TransactionReportJobConfig` (driver `CBTRN03C`, see [§3](#3-batch-cobol-programs--jcl--spring-batch-jobs)) |
| `legacy/csd/CARDDEMO.CSD` | CICS resource definitions (8 FILEs, 17 MAPSETs, 18 PROGRAMs, 18 TRANSACTIONS) | `config/SecurityConfig.java` + routing map — transaction → controller wiring (also the authoritative traceability reference) |
| `legacy/jcl/OPENFIL.jcl` | CICS file **enable** (`IEFBR14`) | **N/A — managed by the Spring connection pool; intentional non-migration, see [§12](#12-intentional-non-migrations-gaps-ledger)** |
| `legacy/jcl/CLOSEFIL.jcl` | CICS file **disable** (`IEFBR14`) | **N/A — managed by the Spring connection pool; intentional non-migration, see [§12](#12-intentional-non-migrations-gaps-ledger)** |

> **29-JCL cross-check.** All 29 JCL jobs are accounted for — **9** as business batch programs in
> [§3](#3-batch-cobol-programs--jcl--spring-batch-jobs) (`POSTTRAN`, `INTCALC`, `CREASTMT`,
> `READACCT`, `READCARD`, `READXREF`, `READCUST`, `TRANBKP`, `TRANREPT`) and **20** here in §7
> (`ACCTFILE`, `CARDFILE`, `CUSTFILE`, `DEFCUST`, `XREFFILE`, `TRANFILE`, `TCATBALF`, `TRANCATG`,
> `TRANTYPE`, `DISCGRP`, `DUSRSECJ`, `TRANIDX`, `COMBTRAN`, `DALYREJS`, `DEFGDGB`, `PRTCATBL`,
> `REPTFILE`, `CBADMCDJ`, `OPENFIL`, `CLOSEFIL`) = **29 total**. Both PROCs (`REPROC.prc`,
> `TRANREPT.prc`), the sort control member (`REPROCT.ctl`) and the CSD (`CARDDEMO.CSD`) are also
> mapped above. Note that `TRANBKP.jcl` is an IDCAMS REPRO backup job (no COBOL program); it is
> listed as a business row in §3 only because the migration plan groups it with `CBTRN01C` under one
> frozen job-config name — see footnote [^cbtrn01c].

> **No business foreign keys (loose VSAM coupling).** The relational schema deliberately declares
> **no** referential foreign-key constraints between the business tables; the only foreign keys in
> the database are the Spring Batch `batch_*` metadata FKs authored by `V0__spring_batch_metadata.sql`.
> VSAM KSDS files are physically independent datasets with no DBMS-enforced referential integrity, and
> the COBOL enforced every cross-file relationship in application logic (the daily-posting job
> `CBTRN02C` validates card/xref/account procedurally and writes unmatched records to `DALYREJS`
> rather than aborting), so DB-level FKs would change observable behavior. Keys and alternate indexes
> are preserved instead (one table per VSAM file, primary key + secondary indexes / Spring Data
> derived queries), and cross-file integrity remains a service-layer/batch concern — per AAP §0.6.2,
> the `V1__schema.sql` header (lines 16, 30), and [`decision-log.md`](./decision-log.md) (no-business-FK
> decision, finding **F-003**).

---

## 8. Construct-Level Traceability (Paragraphs, Executable Copybooks, Subprograms)

Section 2's program → class mapping is intentionally coarse; this section records the **paragraph /
section** level and the copybooks that carry *executable* logic, so that control-flow parity is
traceable and no logic is silently reclassified as data.

**Paragraph / section inventory.**

- **528** paragraph/section declarations across the 28 programs, of which **527** are unique
  `program + label` pairs.
- The single non-unique pair is a **duplicate label in `COACTVWC.cbl`: `0000-MAIN-EXIT` is declared
  twice** (independently confirmed). Because two identically-named paragraphs cannot both become one
  Java method, the target **disambiguates** them: the first is `mainExit()` and the second becomes a
  distinct method (e.g. `mainExitDuplicate()` / an inlined fall-through), with the duplication and
  the chosen disambiguation recorded as quirk-adjacent traceability so the behavior is preserved
  rather than accidentally merged.
- Each numbered paragraph maps to a `@Service`/job method preserving `PERFORM`/`EVALUATE`/`PERFORM
  UNTIL` control flow (§0.3.3).

**Executable copybooks (procedure logic, not data).** 16 executable paragraph declarations live in
copybooks and must be ported as behavior:

| Copybook | Executable declarations | Detail | Java target |
| :------- | ----------------------: | :----- | :---------- |
| `CSSTRPFY.cpy` | 2 | `YYYY-STORE-PFKEY`, `YYYY-STORE-PFKEY-EXIT` — capture/normalise the pressed PF (AID) key | `util/PfKeyHandler.java` |
| `CSUTLDPY.cpy` | 14 | `EDIT-DATE-CCYYMMDD`, `EDIT-YEAR-CCYY`, `EDIT-MONTH`, `EDIT-DAY`, `EDIT-DAY-MONTH-YEAR`, `EDIT-DATE-LE`, `EDIT-DATE-OF-BIRTH` (+ their `-EXIT` paragraphs) — date-component validation and century/window rules | `util/DateConversionSupport.java` |
| `CSSETATY.cpy` | inline (`COPY … REPLACING`) | executable field-attribute logic (`IF FLG-(TESTVAR1)-NOT-OK/BLANK … MOVE DFHRED …`, `MOVE '*' …`) expanded at each `COPY` site | screen-attribute highlight/`*` behavior emitted at each expansion site |

**Multi-member subprogram.** `CBSTM03B.CBL` is a **flag-driven I/O subprogram**
(`PROCEDURE DIVISION USING LK-M03B-AREA`; operation 88-levels `M03B-OPEN`='O', `M03B-READ`='R',
`M03B-READ-K`='K'; performs `OPEN INPUT` / `READ … INTO` for the statement's `TRNX`, `XREF`,
`CUSTOMER`, `ACCOUNT`, and `CARD` files). It is **not** a chunk step and — per AAP §0.4.1 — is
**not** reified as a standalone Java class; its I/O is absorbed into typed Spring Data repository
calls inside `batch/StatementJobConfig.java`: keyed reads (`K`) become `findById(...)`
(customer / account / card) and the sequential per-card scan (`R`) becomes the ordered derived query
`TransactionRepository.findByCardNumOrderByTranIdAsc(...)`, driven by a
`RepositoryItemReader<CardXref>`. This 1:1 mapping (`CBSTM03B` → repositories) keeps the matrix at
100% with no silent drop.

**Report DTOs (no wildcards).** `CVTRA07Y.cpy` declares **7** `01`-level groups, which map to the
concrete report DTOs (see [§4](#4-copybooks--entities--dtos--enums--constants)): `ReportNameHeader`,
`TransactionReportHeaders` (which consolidates the two page-header `01` groups), `TransactionDetailReport`,
`ReportPageTotals`, `ReportAccountTotals`, and `ReportGrandTotals`, with `ReportAmountFormatter`
providing the shared `PIC -ZZZ,ZZZ,ZZZ.ZZ` edit-mask formatting. The previous non-deterministic
`dto/report/*.java` wildcard is replaced by these concrete, present targets.

### 8.1 Exhaustive Paragraph / Section → `class.method` → Test Enumeration

This appendix satisfies the Explainability rule's mandate for a **paragraph-level** forward mapping (review finding #37). It enumerates **every one of the 528** `PROCEDURE DIVISION` paragraph/section declarations across the 28 programs — the same population summarised in §8 — and binds each to its target Java `class.method` and the test(s) that exercise it. The reverse (Java → legacy) direction is the Javadoc origin-tag convention recorded in [§13](#13-reverse-direction-convention-java--legacy).

**How this table was produced (auditable, regenerable).** The paragraph population is extracted directly from the retained COBOL under `legacy/cbl/**` (the parity oracle): for each program the scanner reads from `PROCEDURE DIVISION` to end-of-file and records every Area-A label (columns 8–11) that terminates in a period — both numbered (`NNNN-NAME`) and non-numbered (`MAIN-PARA`, `PROCESS-ENTER-KEY`, …) styles, plus `SECTION` headers — skipping comment lines (indicator `*`/`/`). This reproduces the authoritative counts exactly: **528 declarations, 527 unique `program+label` pairs, and the single duplicate `COACTVWC.cbl / 0000-MAIN-EXIT`** documented in §8, so the enumeration is provably complete with no silent drop.

**Attribution categories (source-grounded, not inferred).** Each paragraph's target `class.method` is resolved from the **origin citation in the ported Java source itself** — the `{@code LABEL}` tag in the Javadoc block immediately preceding a method declaration:

- **230 paragraphs → a named method.** The paragraph label is cited in that method's own Javadoc as its COBOL origin (a documented 1→N split lists up to two methods, with `(+n)` for further continuations — e.g. the sign-on `READ-USER-SEC-FILE` splits into the success and failure-branch methods).

- **127 `-EXIT` paragraphs → `PERFORM`-return no-ops.** COBOL `-EXIT` paragraphs are empty landing points for `PERFORM … THRU`; they carry no logic and are absorbed into their base paragraph's method (named where that base method is cited).

- **171 structural paragraphs → folded into control flow (§0.3.3).** Non-branching plumbing — file `OPEN`/`CLOSE` wrappers, `SEND`/`RECEIVE`-screen and `POPULATE-HEADER-INFO` routines (which become the controller + Thymeleaf render), `GET-NEXT` cursor advances, and `ABEND`/`DISPLAY-IO-STATUS` helpers — is absorbed into the enclosing control-flow method, the Spring Batch reader/processor/writer lambda, or the exception translation path rather than a dedicated public method. These are ported behaviour, not dropped logic; the batch-config Javadoc cites them at class/step granularity.

Coverage is therefore **100%**: every one of the 528 declarations is accounted for in exactly one category. The per-program tables below give the source line, the label, and the resolved target; the `@Service`/`@Configuration` class and covering test(s) are stated once in each program's sub-heading.


#### `legacy/cbl/COSGN00C.cbl` → `SignonService` &nbsp;·&nbsp; tests: `SignonServiceTest` · `SignonControllerIT` · `SecurityConfigIT`

_6 declaration(s): 3 method-mapped, 0 `-EXIT` no-op, 3 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 73 | `MAIN-PARA` | `SignonService.mainEntry(…)` |
| 108 | `PROCESS-ENTER-KEY` | `SignonService.processEnterKey(…)` / `SignonService.isBlankOrLowValues(…)` |
| 145 | `SEND-SIGNON-SCREEN` | folded into `SignonService` control flow (§0.3.3) |
| 162 | `SEND-PLAIN-TEXT` | folded into `SignonService` control flow (§0.3.3) |
| 177 | `POPULATE-HEADER-INFO` | folded into `SignonService` control flow (§0.3.3) |
| 209 | `READ-USER-SEC-FILE` | `SignonService.processEnterKey(…)` / `SignonService.readUserSecFile(…)` (+1) |

#### `legacy/cbl/COMEN01C.cbl` → `MainMenuService` &nbsp;·&nbsp; tests: `MainMenuServiceTest` · `MenuControllerIT`

_7 declaration(s): 5 method-mapped, 0 `-EXIT` no-op, 2 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 75 | `MAIN-PARA` | `MainMenuService.mainEntry(…)` |
| 115 | `PROCESS-ENTER-KEY` | `MainMenuService.processEnterKey(…)` |
| 170 | `RETURN-TO-SIGNON-SCREEN` | `MainMenuService.returnToSignonScreen(…)` |
| 182 | `SEND-MENU-SCREEN` | `MainMenuService.showMenu(…)` |
| 199 | `RECEIVE-MENU-SCREEN` | folded into `MainMenuService` control flow (§0.3.3) |
| 212 | `POPULATE-HEADER-INFO` | folded into `MainMenuService` control flow (§0.3.3) |
| 236 | `BUILD-MENU-OPTIONS` | `MainMenuService.buildMenuOptions(…)` |

#### `legacy/cbl/COADM01C.cbl` → `AdminMenuService` &nbsp;·&nbsp; tests: `AdminMenuServiceTest` · `AdminMenuControllerIT`

_7 declaration(s): 4 method-mapped, 0 `-EXIT` no-op, 3 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 75 | `MAIN-PARA` | `AdminMenuService.mainEntry(…)` |
| 115 | `PROCESS-ENTER-KEY` | `AdminMenuService.processEnterKey(…)` / `AdminMenuService.normalizeOptionField(…)` |
| 160 | `RETURN-TO-SIGNON-SCREEN` | `AdminMenuService.returnToSignonScreen(…)` |
| 172 | `SEND-MENU-SCREEN` | folded into `AdminMenuService` control flow (§0.3.3) |
| 189 | `RECEIVE-MENU-SCREEN` | folded into `AdminMenuService` control flow (§0.3.3) |
| 202 | `POPULATE-HEADER-INFO` | folded into `AdminMenuService` control flow (§0.3.3) |
| 226 | `BUILD-MENU-OPTIONS` | `AdminMenuService.buildMenuOptions(…)` |

#### `legacy/cbl/COACTVWC.cbl` → `AccountViewService` &nbsp;·&nbsp; tests: `AccountViewServiceTest` · `AccountControllerIT`

_35 declaration(s): 10 method-mapped, 17 `-EXIT` no-op, 8 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 262 | `0000-MAIN` | `AccountViewService.mainEntry(…)` / `AccountViewService.handlePf3Return(…)` |
| 394 | `COMMON-RETURN` | folded into `AccountViewService` control flow (§0.3.3) |
| 408 | `0000-MAIN-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.mainEntry(…)` |
| 411 | `0000-MAIN-EXIT` *(duplicate label — disambiguated, see §8)* | exit no-op — `PERFORM`-return landing for `AccountViewService.mainEntry(…)` |
| 416 | `1000-SEND-MAP` | folded into `AccountViewService` control flow (§0.3.3) |
| 427 | `1000-SEND-MAP-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountViewService`) |
| 431 | `1100-SCREEN-INIT` | folded into `AccountViewService` control flow (§0.3.3) |
| 457 | `1100-SCREEN-INIT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountViewService`) |
| 460 | `1200-SETUP-SCREEN-VARS` | `AccountViewService.mainEntry(…)` / `AccountViewService.readAcct(…)` (+2) |
| 537 | `1200-SETUP-SCREEN-VARS-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.mainEntry(…)` |
| 541 | `1300-SETUP-SCREEN-ATTRS` | folded into `AccountViewService` control flow (§0.3.3) |
| 574 | `1300-SETUP-SCREEN-ATTRS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountViewService`) |
| 577 | `1400-SEND-SCREEN` | folded into `AccountViewService` control flow (§0.3.3) |
| 592 | `1400-SEND-SCREEN-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountViewService`) |
| 596 | `2000-PROCESS-INPUTS` | `AccountViewService.processInputs(…)` |
| 607 | `2000-PROCESS-INPUTS-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.processInputs(…)` |
| 610 | `2100-RECEIVE-MAP` | `AccountViewService.processInputs(…)` |
| 619 | `2100-RECEIVE-MAP-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.processInputs(…)` |
| 622 | `2200-EDIT-MAP-INPUTS` | `AccountViewService.processInputs(…)` / `AccountViewService.editMapInputs(…)` |
| 645 | `2200-EDIT-MAP-INPUTS-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.processInputs(…)` |
| 649 | `2210-EDIT-ACCOUNT` | `AccountViewService.editAccount(…)` |
| 683 | `2210-EDIT-ACCOUNT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.editAccount(…)` |
| 687 | `9000-READ-ACCT` | `AccountViewService.readAcct(…)` |
| 720 | `9000-READ-ACCT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.readAcct(…)` |
| 723 | `9200-GETCARDXREF-BYACCT` | `AccountViewService.getCardXrefByAccount(…)` |
| 771 | `9200-GETCARDXREF-BYACCT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.getCardXrefByAccount(…)` |
| 774 | `9300-GETACCTDATA-BYACCT` | `AccountViewService.getAcctDataByAccount(…)` |
| 821 | `9300-GETACCTDATA-BYACCT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.getAcctDataByAccount(…)` |
| 825 | `9400-GETCUSTDATA-BYCUST` | `AccountViewService.getCustDataByCust(…)` |
| 870 | `9400-GETCUSTDATA-BYCUST-EXIT` | exit no-op — `PERFORM`-return landing for `AccountViewService.getCustDataByCust(…)` |
| 877 | `SEND-PLAIN-TEXT` | folded into `AccountViewService` control flow (§0.3.3) |
| 888 | `SEND-PLAIN-TEXT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountViewService`) |
| 896 | `SEND-LONG-TEXT` | folded into `AccountViewService` control flow (§0.3.3) |
| 907 | `SEND-LONG-TEXT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountViewService`) |
| 916 | `ABEND-ROUTINE` | folded into `AccountViewService` control flow (§0.3.3) |

#### `legacy/cbl/COACTUPC.cbl` → `AccountUpdateService` &nbsp;·&nbsp; tests: `AccountUpdateServiceTest` · `AccountControllerIT`

_85 declaration(s): 33 method-mapped, 41 `-EXIT` no-op, 11 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 859 | `0000-MAIN` | `AccountUpdateService.processInputs(…)` / `AccountUpdateService.process(…)` (+2) |
| 1007 | `COMMON-RETURN` | `AccountUpdateService.process(…)` |
| 1021 | `0000-MAIN-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.processInputs(…)` |
| 1025 | `1000-PROCESS-INPUTS` | `AccountUpdateService.processInputs(…)` |
| 1036 | `1000-PROCESS-INPUTS-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.processInputs(…)` |
| 1039 | `1100-RECEIVE-MAP` | `AccountUpdateService.processInputs(…)` / `AccountUpdateService.captureNewDetails(…)` |
| 1426 | `1100-RECEIVE-MAP-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.processInputs(…)` |
| 1429 | `1200-EDIT-MAP-INPUTS` | `AccountUpdateService.processInputs(…)` / `AccountUpdateService.editMapInputs(…)` |
| 1678 | `1200-EDIT-MAP-INPUTS-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.processInputs(…)` |
| 1681 | `1205-COMPARE-OLD-NEW` | `AccountUpdateService.editMapInputs(…)` / `AccountUpdateService.compareOldNew(…)` (+4) |
| 1777 | `1205-COMPARE-OLD-NEW-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editMapInputs(…)` |
| 1783 | `1210-EDIT-ACCOUNT` | `AccountUpdateService.editAccount(…)` / `AccountUpdateService.editMapInputs(…)` |
| 1820 | `1210-EDIT-ACCOUNT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editAccount(…)` |
| 1824 | `1215-EDIT-MANDATORY` | `AccountUpdateService.editMandatory(…)` |
| 1852 | `1215-EDIT-MANDATORY-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editMandatory(…)` |
| 1856 | `1220-EDIT-YESNO` | `AccountUpdateService.editYesNo(…)` |
| 1894 | `1220-EDIT-YESNO-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editYesNo(…)` |
| 1898 | `1225-EDIT-ALPHA-REQD` | `AccountUpdateService.editAlphaReqd(…)` |
| 1951 | `1225-EDIT-ALPHA-REQD-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editAlphaReqd(…)` |
| 1955 | `1230-EDIT-ALPHANUM-REQD` | `AccountUpdateService.editAlphanumReqd(…)` |
| 2009 | `1230-EDIT-ALPHANUM-REQD-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editAlphanumReqd(…)` |
| 2012 | `1235-EDIT-ALPHA-OPT` | `AccountUpdateService.editAlphaOpt(…)` |
| 2057 | `1235-EDIT-ALPHA-OPT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editAlphaOpt(…)` |
| 2061 | `1240-EDIT-ALPHANUM-OPT` | `AccountUpdateService.editAlphanumOpt(…)` |
| 2105 | `1240-EDIT-ALPHANUM-OPT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editAlphanumOpt(…)` |
| 2109 | `1245-EDIT-NUM-REQD` | `AccountUpdateService.editNumReqd(…)` / `AccountUpdateService.editUsSsn(…)` (+1) |
| 2176 | `1245-EDIT-NUM-REQD-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editNumReqd(…)` |
| 2180 | `1250-EDIT-SIGNED-9V2` | `AccountUpdateService.editSigned9v2(…)` |
| 2221 | `1250-EDIT-SIGNED-9V2-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editSigned9v2(…)` |
| 2225 | `1260-EDIT-US-PHONE-NUM` | `AccountUpdateService.editUsPhoneNum(…)` |
| 2246 | `EDIT-AREA-CODE` | `AccountUpdateService.editAreaCode(…)` |
| 2316 | `EDIT-US-PHONE-PREFIX` | `AccountUpdateService.editUsPhonePrefix(…)` |
| 2370 | `EDIT-US-PHONE-LINENUM` | `AccountUpdateService.editUsPhoneLineNum(…)` |
| 2424 | `EDIT-US-PHONE-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 2427 | `1260-EDIT-US-PHONE-NUM-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editUsPhoneNum(…)` |
| 2431 | `1265-EDIT-US-SSN` | `AccountUpdateService.editUsSsn(…)` |
| 2489 | `1265-EDIT-US-SSN-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editUsSsn(…)` |
| 2493 | `1270-EDIT-US-STATE-CD` | `AccountUpdateService.editUsStateCd(…)` |
| 2511 | `1270-EDIT-US-STATE-CD-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editUsStateCd(…)` |
| 2514 | `1275-EDIT-FICO-SCORE` | `AccountUpdateService.editFicoScore(…)` |
| 2531 | `1275-EDIT-FICO-SCORE-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editFicoScore(…)` |
| 2536 | `1280-EDIT-US-STATE-ZIP-CD` | `AccountUpdateService.editUsStateZipCd(…)` |
| 2558 | `1280-EDIT-US-STATE-ZIP-CD-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.editUsStateZipCd(…)` |
| 2562 | `2000-DECIDE-ACTION` | `AccountUpdateService.resetMessage(…)` / `AccountUpdateService.decideAction(…)` |
| 2643 | `2000-DECIDE-ACTION-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.resetMessage(…)` |
| 2649 | `3000-SEND-MAP` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 2664 | `3000-SEND-MAP-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 2668 | `3100-SCREEN-INIT` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 2694 | `3100-SCREEN-INIT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 2698 | `3200-SETUP-SCREEN-VARS` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 2727 | `3200-SETUP-SCREEN-VARS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 2731 | `3201-SHOW-INITIAL-VALUES` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 2783 | `3201-SHOW-INITIAL-VALUES-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 2787 | `3202-SHOW-ORIGINAL-VALUES` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 2867 | `3202-SHOW-ORIGINAL-VALUES-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 2870 | `3203-SHOW-UPDATED-VALUES` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 2951 | `3203-SHOW-UPDATED-VALUES-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 2955 | `3250-SETUP-INFOMSG` | `AccountUpdateService.process(…)` |
| 2983 | `3250-SETUP-INFOMSG-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.process(…)` |
| 2986 | `3300-SETUP-SCREEN-ATTRS` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 3437 | `3300-SETUP-SCREEN-ATTRS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 3441 | `3310-PROTECT-ALL-ATTRS` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 3496 | `3310-PROTECT-ALL-ATTRS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 3500 | `3320-UNPROTECT-FEW-ATTRS` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 3562 | `3320-UNPROTECT-FEW-ATTRS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 3566 | `3390-SETUP-INFOMSG-ATTRS` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 3584 | `3390-SETUP-INFOMSG-ATTRS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 3589 | `3400-SEND-SCREEN` | folded into `AccountUpdateService` control flow (§0.3.3) |
| 3603 | `3400-SEND-SCREEN-EXIT` | exit no-op — `PERFORM`-return landing (folded in `AccountUpdateService`) |
| 3608 | `9000-READ-ACCT` | `AccountUpdateService.readAcct(…)` / `AccountUpdateService.storeFetchedData(…)` |
| 3647 | `9000-READ-ACCT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.readAcct(…)` |
| 3650 | `9200-GETCARDXREF-BYACCT` | `AccountUpdateService.readAcct(…)` / `AccountUpdateService.getCardXrefByAcct(…)` (+1) |
| 3698 | `9200-GETCARDXREF-BYACCT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.readAcct(…)` |
| 3701 | `9300-GETACCTDATA-BYACCT` | `AccountUpdateService.readAcct(…)` / `AccountUpdateService.getAcctDataByAcct(…)` |
| 3748 | `9300-GETACCTDATA-BYACCT-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.readAcct(…)` |
| 3752 | `9400-GETCUSTDATA-BYCUST` | `AccountUpdateService.readAcct(…)` / `AccountUpdateService.getCustDataByCust(…)` |
| 3797 | `9400-GETCUSTDATA-BYCUST-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.readAcct(…)` |
| 3801 | `9500-STORE-FETCHED-DATA` | `AccountUpdateService.readAcct(…)` / `AccountUpdateService.storeFetchedData(…)` (+1) |
| 3885 | `9500-STORE-FETCHED-DATA-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.readAcct(…)` |
| 3888 | `9600-WRITE-PROCESSING` | `AccountUpdateService.composePhone1(…)` / `AccountUpdateService.composePhone2(…)` (+3) |
| 4105 | `9600-WRITE-PROCESSING-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.composePhone1(…)` |
| 4109 | `9700-CHECK-CHANGE-IN-REC` | `AccountUpdateService.storeFetchedData(…)` / `AccountUpdateService.buildSnapshot(…)` (+3) |
| 4193 | `9700-CHECK-CHANGE-IN-REC-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.storeFetchedData(…)` |
| 4203 | `ABEND-ROUTINE` | `AccountUpdateService.abendRoutine(…)` |
| 4226 | `ABEND-ROUTINE-EXIT` | exit no-op — `PERFORM`-return landing for `AccountUpdateService.abendRoutine(…)` |

#### `legacy/cbl/COCRDLIC.cbl` → `CardListService` &nbsp;·&nbsp; tests: `CardListServiceTest` · `CardControllerIT`

_39 declaration(s): 11 method-mapped, 19 `-EXIT` no-op, 9 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 298 | `0000-MAIN` | `CardListService.mainEntry(…)` / `CardListService.dispatchSelection(…)` |
| 604 | `COMMON-RETURN` | folded into `CardListService` control flow (§0.3.3) |
| 621 | `0000-MAIN-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.mainEntry(…)` |
| 624 | `1000-SEND-MAP` | `CardListService.setFormRow(…)` |
| 639 | `1000-SEND-MAP-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.setFormRow(…)` |
| 642 | `1100-SCREEN-INIT` | folded into `CardListService` control flow (§0.3.3) |
| 674 | `1100-SCREEN-INIT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardListService`) |
| 678 | `1200-SCREEN-ARRAY-INIT` | folded into `CardListService` control flow (§0.3.3) |
| 745 | `1200-SCREEN-ARRAY-INIT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardListService`) |
| 748 | `1250-SETUP-ARRAY-ATTRIBS` | folded into `CardListService` control flow (§0.3.3) |
| 834 | `1250-SETUP-ARRAY-ATTRIBS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardListService`) |
| 837 | `1300-SETUP-SCREEN-ATTRS` | folded into `CardListService` control flow (§0.3.3) |
| 890 | `1300-SETUP-SCREEN-ATTRS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardListService`) |
| 895 | `1400-SETUP-MESSAGE` | folded into `CardListService` control flow (§0.3.3) |
| 933 | `1400-SETUP-MESSAGE-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardListService`) |
| 938 | `1500-SEND-SCREEN` | folded into `CardListService` control flow (§0.3.3) |
| 948 | `1500-SEND-SCREEN-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardListService`) |
| 951 | `2000-RECEIVE-MAP` | `CardListService.mainEntry(…)` |
| 959 | `2000-RECEIVE-MAP-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.mainEntry(…)` |
| 962 | `2100-RECEIVE-SCREEN` | `CardListService.mainEntry(…)` |
| 981 | `2100-RECEIVE-SCREEN-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.mainEntry(…)` |
| 985 | `2200-EDIT-INPUTS` | `CardListService.editInputs(…)` |
| 999 | `2200-EDIT-INPUTS-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.editInputs(…)` |
| 1003 | `2210-EDIT-ACCOUNT` | `CardListService.editAccount(…)` |
| 1032 | `2210-EDIT-ACCOUNT-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.editAccount(…)` |
| 1036 | `2220-EDIT-CARD` | `CardListService.editCard(…)` |
| 1069 | `2220-EDIT-CARD-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.editCard(…)` |
| 1073 | `2250-EDIT-ARRAY` | `CardListService.editArray(…)` |
| 1119 | `2250-EDIT-ARRAY-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.editArray(…)` |
| 1123 | `9000-READ-FORWARD` | `CardListService.readForward(…)` |
| 1261 | `9000-READ-FORWARD-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.readForward(…)` |
| 1264 | `9100-READ-BACKWARDS` | `CardListService.readBackwards(…)` |
| 1374 | `9100-READ-BACKWARDS-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.readBackwards(…)` |
| 1382 | `9500-FILTER-RECORDS` | `CardListService.filterRecords(…)` / `CardListService.buildForwardWindow(…)` |
| 1409 | `9500-FILTER-RECORDS-EXIT` | exit no-op — `PERFORM`-return landing for `CardListService.filterRecords(…)` |
| 1422 | `SEND-PLAIN-TEXT` | folded into `CardListService` control flow (§0.3.3) |
| 1433 | `SEND-PLAIN-TEXT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardListService`) |
| 1441 | `SEND-LONG-TEXT` | folded into `CardListService` control flow (§0.3.3) |
| 1452 | `SEND-LONG-TEXT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardListService`) |

#### `legacy/cbl/COCRDSLC.cbl` → `CardDetailService` &nbsp;·&nbsp; tests: `CardDetailServiceTest` · `CardControllerIT`

_34 declaration(s): 12 method-mapped, 16 `-EXIT` no-op, 6 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 248 | `0000-MAIN` | `CardDetailService.mainEntry(…)` / `CardDetailService.handleExit(…)` (+1) |
| 394 | `COMMON-RETURN` | folded into `CardDetailService` control flow (§0.3.3) |
| 408 | `0000-MAIN-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.mainEntry(…)` |
| 412 | `1000-SEND-MAP` | `CardDetailService.buildShowResult(…)` |
| 423 | `1000-SEND-MAP-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.buildShowResult(…)` |
| 427 | `1100-SCREEN-INIT` | folded into `CardDetailService` control flow (§0.3.3) |
| 453 | `1100-SCREEN-INIT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardDetailService`) |
| 457 | `1200-SETUP-SCREEN-VARS` | `CardDetailService.getCardByAccountCard(…)` / `CardDetailService.populateCardData(…)` (+1) |
| 499 | `1200-SETUP-SCREEN-VARS-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.getCardByAccountCard(…)` |
| 502 | `1300-SETUP-SCREEN-ATTRS` | folded into `CardDetailService` control flow (§0.3.3) |
| 559 | `1300-SETUP-SCREEN-ATTRS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardDetailService`) |
| 563 | `1400-SEND-SCREEN` | `CardDetailService.buildShowResult(…)` |
| 578 | `1400-SEND-SCREEN-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.buildShowResult(…)` |
| 582 | `2000-PROCESS-INPUTS` | `CardDetailService.processInputs(…)` |
| 593 | `2000-PROCESS-INPUTS-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.processInputs(…)` |
| 596 | `2100-RECEIVE-MAP` | `CardDetailService.processInputs(…)` |
| 605 | `2100-RECEIVE-MAP-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.processInputs(…)` |
| 608 | `2200-EDIT-MAP-INPUTS` | `CardDetailService.editMapInputs(…)` |
| 643 | `2200-EDIT-MAP-INPUTS-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.editMapInputs(…)` |
| 647 | `2210-EDIT-ACCOUNT` | `CardDetailService.editAccount(…)` |
| 681 | `2210-EDIT-ACCOUNT-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.editAccount(…)` |
| 685 | `2220-EDIT-CARD` | `CardDetailService.editCard(…)` |
| 722 | `2220-EDIT-CARD-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.editCard(…)` |
| 726 | `9000-READ-DATA` | `CardDetailService.readData(…)` |
| 732 | `9000-READ-DATA-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.readData(…)` |
| 736 | `9100-GETCARD-BYACCTCARD` | `CardDetailService.readData(…)` / `CardDetailService.getCardByAccountCard(…)` |
| 775 | `9100-GETCARD-BYACCTCARD-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.readData(…)` |
| 779 | `9150-GETCARD-BYACCT` | `CardDetailService.readData(…)` / `CardDetailService.getCardByAccount(…)` |
| 810 | `9150-GETCARD-BYACCT-EXIT` | exit no-op — `PERFORM`-return landing for `CardDetailService.readData(…)` |
| 820 | `SEND-LONG-TEXT` | folded into `CardDetailService` control flow (§0.3.3) |
| 831 | `SEND-LONG-TEXT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardDetailService`) |
| 838 | `SEND-PLAIN-TEXT` | folded into `CardDetailService` control flow (§0.3.3) |
| 849 | `SEND-PLAIN-TEXT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardDetailService`) |
| 857 | `ABEND-ROUTINE` | folded into `CardDetailService` control flow (§0.3.3) |

#### `legacy/cbl/COCRDUPC.cbl` → `CardUpdateService` &nbsp;·&nbsp; tests: `CardUpdateServiceTest` · `CardControllerIT`

_45 declaration(s): 16 method-mapped, 22 `-EXIT` no-op, 7 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 367 | `0000-MAIN` | `CardUpdateService.mainEntry(…)` |
| 546 | `COMMON-RETURN` | folded into `CardUpdateService` control flow (§0.3.3) |
| 560 | `0000-MAIN-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.mainEntry(…)` |
| 564 | `1000-PROCESS-INPUTS` | `CardUpdateService.processInputs(…)` |
| 575 | `1000-PROCESS-INPUTS-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.processInputs(…)` |
| 578 | `1100-RECEIVE-MAP` | `CardUpdateService.processInputs(…)` / `CardUpdateService.normalizeStar(…)` |
| 638 | `1100-RECEIVE-MAP-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.processInputs(…)` |
| 641 | `1200-EDIT-MAP-INPUTS` | `CardUpdateService.editMapInputs(…)` / `CardUpdateService.armConfirmation(…)` |
| 717 | `1200-EDIT-MAP-INPUTS-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.editMapInputs(…)` |
| 721 | `1210-EDIT-ACCOUNT` | `CardUpdateService.editAccount(…)` |
| 758 | `1210-EDIT-ACCOUNT-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.editAccount(…)` |
| 762 | `1220-EDIT-CARD` | `CardUpdateService.editCard(…)` |
| 802 | `1220-EDIT-CARD-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.editCard(…)` |
| 806 | `1230-EDIT-NAME` | `CardUpdateService.editName(…)` / `CardUpdateService.isAlphaSpaces(…)` |
| 841 | `1230-EDIT-NAME-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.editName(…)` |
| 845 | `1240-EDIT-CARDSTATUS` | `CardUpdateService.editCardStatus(…)` |
| 874 | `1240-EDIT-CARDSTATUS-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.editCardStatus(…)` |
| 877 | `1250-EDIT-EXPIRY-MON` | `CardUpdateService.editExpiryMonth(…)` |
| 910 | `1250-EDIT-EXPIRY-MON-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.editExpiryMonth(…)` |
| 913 | `1260-EDIT-EXPIRY-YEAR` | `CardUpdateService.editExpiryYear(…)` |
| 945 | `1260-EDIT-EXPIRY-YEAR-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.editExpiryYear(…)` |
| 948 | `2000-DECIDE-ACTION` | `CardUpdateService.decideAction(…)` / `CardUpdateService.armConfirmation(…)` (+1) |
| 1029 | `2000-DECIDE-ACTION-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.decideAction(…)` |
| 1035 | `3000-SEND-MAP` | folded into `CardUpdateService` control flow (§0.3.3) |
| 1048 | `3000-SEND-MAP-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardUpdateService`) |
| 1052 | `3100-SCREEN-INIT` | folded into `CardUpdateService` control flow (§0.3.3) |
| 1078 | `3100-SCREEN-INIT-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardUpdateService`) |
| 1082 | `3200-SETUP-SCREEN-VARS` | folded into `CardUpdateService` control flow (§0.3.3) |
| 1135 | `3200-SETUP-SCREEN-VARS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardUpdateService`) |
| 1138 | `3250-SETUP-INFOMSG` | folded into `CardUpdateService` control flow (§0.3.3) |
| 1165 | `3250-SETUP-INFOMSG-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardUpdateService`) |
| 1168 | `3300-SETUP-SCREEN-ATTRS` | folded into `CardUpdateService` control flow (§0.3.3) |
| 1319 | `3300-SETUP-SCREEN-ATTRS-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardUpdateService`) |
| 1324 | `3400-SEND-SCREEN` | folded into `CardUpdateService` control flow (§0.3.3) |
| 1338 | `3400-SEND-SCREEN-EXIT` | exit no-op — `PERFORM`-return landing (folded in `CardUpdateService`) |
| 1343 | `9000-READ-DATA` | `CardUpdateService.readData(…)` |
| 1372 | `9000-READ-DATA-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.readData(…)` |
| 1376 | `9100-GETCARD-BYACCTCARD` | `CardUpdateService.getCardByAccountCard(…)` |
| 1415 | `9100-GETCARD-BYACCTCARD-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.getCardByAccountCard(…)` |
| 1420 | `9200-WRITE-PROCESSING` | `CardUpdateService.writeProcessing(…)` |
| 1494 | `9200-WRITE-PROCESSING-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.writeProcessing(…)` |
| 1498 | `9300-CHECK-CHANGE-IN-REC` | `CardUpdateService.checkChangeInRec(…)` |
| 1521 | `9300-CHECK-CHANGE-IN-REC-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.checkChangeInRec(…)` |
| 1531 | `ABEND-ROUTINE` | `CardUpdateService.abendRoutine(…)` |
| 1554 | `ABEND-ROUTINE-EXIT` | exit no-op — `PERFORM`-return landing for `CardUpdateService.abendRoutine(…)` |

#### `legacy/cbl/COTRN00C.cbl` → `TransactionListService` &nbsp;·&nbsp; tests: `TransactionListServiceTest` · `TransactionControllerIT`

_16 declaration(s): 15 method-mapped, 0 `-EXIT` no-op, 1 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 95 | `MAIN-PARA` | `TransactionListService.mainEntry(…)` |
| 146 | `PROCESS-ENTER-KEY` | `TransactionListService.mainEntry(…)` / `TransactionListService.processEnterKey(…)` |
| 234 | `PROCESS-PF7-KEY` | `TransactionListService.processPf7Key(…)` |
| 257 | `PROCESS-PF8-KEY` | `TransactionListService.processPf8Key(…)` |
| 279 | `PROCESS-PAGE-FORWARD` | `TransactionListService.processEnterKey(…)` / `TransactionListService.processPageForward(…)` |
| 333 | `PROCESS-PAGE-BACKWARD` | `TransactionListService.processPageBackward(…)` / `TransactionListService.loadBackwardWindow(…)` |
| 381 | `POPULATE-TRAN-DATA` | `TransactionListService.populateTranData(…)` / `TransactionListService.setRowFields(…)` |
| 450 | `INITIALIZE-TRAN-DATA` | `TransactionListService.initializeTranData(…)` / `TransactionListService.setRowFields(…)` |
| 510 | `RETURN-TO-PREV-SCREEN` | `TransactionListService.returnToPrevScreen(…)` |
| 527 | `SEND-TRNLST-SCREEN` | `TransactionListService.mainEntry(…)` |
| 554 | `RECEIVE-TRNLST-SCREEN` | `TransactionListService.mainEntry(…)` |
| 567 | `POPULATE-HEADER-INFO` | folded into `TransactionListService` control flow (§0.3.3) |
| 591 | `STARTBR-TRANSACT-FILE` | `TransactionListService.startBrowseTransactFile(…)` |
| 624 | `READNEXT-TRANSACT-FILE` | `TransactionListService.readNextTransactFile(…)` |
| 658 | `READPREV-TRANSACT-FILE` | `TransactionListService.readPrevTransactFile(…)` |
| 692 | `ENDBR-TRANSACT-FILE` | `TransactionListService.endBrowseTransactFile(…)` |

#### `legacy/cbl/COTRN01C.cbl` → `TransactionViewService` &nbsp;·&nbsp; tests: `TransactionViewServiceTest` · `TransactionControllerIT`

_9 declaration(s): 7 method-mapped, 0 `-EXIT` no-op, 2 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 86 | `MAIN-PARA` | `TransactionViewService.mainEntry(…)` |
| 144 | `PROCESS-ENTER-KEY` | `TransactionViewService.processEnterKey(…)` / `TransactionViewService.clearDisplayFields(…)` (+1) |
| 197 | `RETURN-TO-PREV-SCREEN` | `TransactionViewService.TransactionViewResult(…)` / `TransactionViewService.returnToPreviousScreen(…)` |
| 213 | `SEND-TRNVIEW-SCREEN` | `TransactionViewService.TransactionViewResult(…)` / `TransactionViewService.ofShow(…)` |
| 230 | `RECEIVE-TRNVIEW-SCREEN` | folded into `TransactionViewService` control flow (§0.3.3) |
| 243 | `POPULATE-HEADER-INFO` | folded into `TransactionViewService` control flow (§0.3.3) |
| 267 | `READ-TRANSACT-FILE` | `TransactionViewService.readTransactFile(…)` |
| 301 | `CLEAR-CURRENT-SCREEN` | `TransactionViewService.clearCurrentScreen(…)` |
| 309 | `INITIALIZE-ALL-FIELDS` | `TransactionViewService.initializeAllFields(…)` |

#### `legacy/cbl/COTRN02C.cbl` → `TransactionAddService` &nbsp;·&nbsp; tests: `TransactionAddServiceTest` · `TransactionControllerIT`

_18 declaration(s): 16 method-mapped, 0 `-EXIT` no-op, 2 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 107 | `MAIN-PARA` | `TransactionAddService.mainEntry(…)` |
| 164 | `PROCESS-ENTER-KEY` | `TransactionAddService.processEnterKey(…)` |
| 193 | `VALIDATE-INPUT-KEY-FIELDS` | `TransactionAddService.validateInputKeyFields(…)` |
| 235 | `VALIDATE-INPUT-DATA-FIELDS` | `TransactionAddService.validateInputDataFields(…)` |
| 442 | `ADD-TRANSACTION` | `TransactionAddService.addTransaction(…)` |
| 471 | `COPY-LAST-TRAN-DATA` | `TransactionAddService.copyLastTranData(…)` / `TransactionAddService.copyTransactionToForm(…)` (+1) |
| 500 | `RETURN-TO-PREV-SCREEN` | `TransactionAddService.beginReturnToPrevScreen(…)` |
| 516 | `SEND-TRNADD-SCREEN` | `TransactionAddService.validateInputKeyFields(…)` / `TransactionAddService.validateInputDataFields(…)` (+1) |
| 539 | `RECEIVE-TRNADD-SCREEN` | folded into `TransactionAddService` control flow (§0.3.3) |
| 552 | `POPULATE-HEADER-INFO` | folded into `TransactionAddService` control flow (§0.3.3) |
| 576 | `READ-CXACAIX-FILE` | `TransactionAddService.readCxacaixFile(…)` |
| 609 | `READ-CCXREF-FILE` | `TransactionAddService.readCcxrefFile(…)` |
| 642 | `STARTBR-TRANSACT-FILE` | `TransactionAddService.readLastTransaction(…)` |
| 673 | `READPREV-TRANSACT-FILE` | `TransactionAddService.readLastTransaction(…)` |
| 702 | `ENDBR-TRANSACT-FILE` | `TransactionAddService.readLastTransaction(…)` |
| 711 | `WRITE-TRANSACT-FILE` | `TransactionAddService.processEnterKey(…)` / `TransactionAddService.writeTransactFile(…)` |
| 754 | `CLEAR-CURRENT-SCREEN` | `TransactionAddService.clearCurrentScreen(…)` |
| 762 | `INITIALIZE-ALL-FIELDS` | `TransactionAddService.writeTransactFile(…)` / `TransactionAddService.initializeAllFields(…)` (+1) |

#### `legacy/cbl/COBIL00C.cbl` → `BillPayService` &nbsp;·&nbsp; tests: `BillPayServiceTest` · `BillPayControllerIT`

_16 declaration(s): 13 method-mapped, 0 `-EXIT` no-op, 3 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 99 | `MAIN-PARA` | `BillPayService.mainEntry(…)` |
| 154 | `PROCESS-ENTER-KEY` | `BillPayService.mainEntry(…)` / `BillPayService.processEnterKey(…)` (+1) |
| 249 | `GET-CURRENT-TIMESTAMP` | `BillPayService.getCurrentTimestamp(…)` |
| 273 | `RETURN-TO-PREV-SCREEN` | `BillPayService.BillPayResult(…)` / `BillPayService.mainEntry(…)` (+1) |
| 289 | `SEND-BILLPAY-SCREEN` | folded into `BillPayService` control flow (§0.3.3) |
| 306 | `RECEIVE-BILLPAY-SCREEN` | folded into `BillPayService` control flow (§0.3.3) |
| 319 | `POPULATE-HEADER-INFO` | folded into `BillPayService` control flow (§0.3.3) |
| 343 | `READ-ACCTDAT-FILE` | `BillPayService.readAcctdatFile(…)` |
| 377 | `UPDATE-ACCTDAT-FILE` | `BillPayService.updateAcctdatFile(…)` |
| 408 | `READ-CXACAIX-FILE` | `BillPayService.readCxacaixFile(…)` |
| 441 | `STARTBR-TRANSACT-FILE` | `BillPayService.nextTransactionId(…)` |
| 472 | `READPREV-TRANSACT-FILE` | `BillPayService.nextTransactionId(…)` |
| 501 | `ENDBR-TRANSACT-FILE` | `BillPayService.nextTransactionId(…)` |
| 510 | `WRITE-TRANSACT-FILE` | `BillPayService.writeTransactFile(…)` / `BillPayService.buildSuccessMessage(…)` |
| 552 | `CLEAR-CURRENT-SCREEN` | `BillPayService.clearCurrentScreen(…)` |
| 560 | `INITIALIZE-ALL-FIELDS` | `BillPayService.initializeAllFields(…)` / `BillPayService.writeTransactFile(…)` |

#### `legacy/cbl/CORPT00C.cbl` → `ReportSubmitService` &nbsp;·&nbsp; tests: `ReportSubmitServiceTest` · `ReportControllerIT`

_10 declaration(s): 7 method-mapped, 0 `-EXIT` no-op, 3 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 163 | `MAIN-PARA` | `ReportSubmitService.mainEntry(…)` |
| 208 | `PROCESS-ENTER-KEY` | `ReportSubmitService.processEnterKey(…)` / `ReportSubmitService.validateCustomRange(…)` |
| 462 | `SUBMIT-JOB-TO-INTRDR` | `ReportSubmitService.submitJobToIntrdr(…)` |
| 515 | `WIRTE-JOBSUB-TDQ` | `ReportSubmitService.writeJobSubmitTdq(…)` |
| 540 | `RETURN-TO-PREV-SCREEN` | `ReportSubmitService.ReportSubmitResult(…)` / `ReportSubmitService.returnToPrevScreen(…)` |
| 556 | `SEND-TRNRPT-SCREEN` | `ReportSubmitService.processEnterKey(…)` / `ReportSubmitService.submitJobToIntrdr(…)` |
| 585 | `RETURN-TO-CICS` | folded into `ReportSubmitService` control flow (§0.3.3) |
| 596 | `RECEIVE-TRNRPT-SCREEN` | folded into `ReportSubmitService` control flow (§0.3.3) |
| 609 | `POPULATE-HEADER-INFO` | folded into `ReportSubmitService` control flow (§0.3.3) |
| 633 | `INITIALIZE-ALL-FIELDS` | `ReportSubmitService.submitJobToIntrdr(…)` / `ReportSubmitService.initializeAllFields(…)` |

#### `legacy/cbl/COUSR00C.cbl` → `UserListService` &nbsp;·&nbsp; tests: `UserListServiceTest` · `UserAdminControllerIT`

_16 declaration(s): 15 method-mapped, 0 `-EXIT` no-op, 1 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 98 | `MAIN-PARA` | `UserListService.mainEntry(…)` |
| 149 | `PROCESS-ENTER-KEY` | `UserListService.mainEntry(…)` / `UserListService.processEnterKey(…)` |
| 237 | `PROCESS-PF7-KEY` | `UserListService.processPf7Key(…)` |
| 260 | `PROCESS-PF8-KEY` | `UserListService.processPf8Key(…)` |
| 282 | `PROCESS-PAGE-FORWARD` | `UserListService.processEnterKey(…)` / `UserListService.processPageForward(…)` |
| 336 | `PROCESS-PAGE-BACKWARD` | `UserListService.processPageBackward(…)` / `UserListService.loadBackwardWindow(…)` |
| 384 | `POPULATE-USER-DATA` | `UserListService.UserRow(…)` / `UserListService.populateUserData(…)` |
| 446 | `INITIALIZE-USER-DATA` | `UserListService.blank(…)` / `UserListService.initializeUserData(…)` |
| 506 | `RETURN-TO-PREV-SCREEN` | `UserListService.mainEntry(…)` / `UserListService.returnToPrevScreen(…)` |
| 522 | `SEND-USRLST-SCREEN` | `UserListService.UserListResult(…)` / `UserListService.ofScreen(…)` (+2) |
| 549 | `RECEIVE-USRLST-SCREEN` | `UserListService.mainEntry(…)` |
| 562 | `POPULATE-HEADER-INFO` | folded into `UserListService` control flow (§0.3.3) |
| 586 | `STARTBR-USER-SEC-FILE` | `UserListService.startBrowse(…)` |
| 619 | `READNEXT-USER-SEC-FILE` | `UserListService.readNext(…)` |
| 653 | `READPREV-USER-SEC-FILE` | `UserListService.readPrev(…)` |
| 687 | `ENDBR-USER-SEC-FILE` | `UserListService.endBrowse(…)` |

#### `legacy/cbl/COUSR01C.cbl` → `UserAddService` &nbsp;·&nbsp; tests: `UserAddServiceTest` · `UserAdminControllerIT`

_9 declaration(s): 6 method-mapped, 0 `-EXIT` no-op, 3 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 71 | `MAIN-PARA` | `UserAddService.mainEntry(…)` |
| 115 | `PROCESS-ENTER-KEY` | `UserAddService.processEnterKey(…)` |
| 165 | `RETURN-TO-PREV-SCREEN` | `UserAddService.UserAddResult(…)` / `UserAddService.mainEntry(…)` (+1) |
| 184 | `SEND-USRADD-SCREEN` | folded into `UserAddService` control flow (§0.3.3) |
| 201 | `RECEIVE-USRADD-SCREEN` | folded into `UserAddService` control flow (§0.3.3) |
| 214 | `POPULATE-HEADER-INFO` | folded into `UserAddService` control flow (§0.3.3) |
| 238 | `WRITE-USER-SEC-FILE` | `UserAddService.writeUserSecFile(…)` |
| 279 | `CLEAR-CURRENT-SCREEN` | `UserAddService.ofRedisplay(…)` / `UserAddService.clearCurrentScreen(…)` |
| 287 | `INITIALIZE-ALL-FIELDS` | `UserAddService.writeUserSecFile(…)` / `UserAddService.initializeAllFields(…)` |

#### `legacy/cbl/COUSR02C.cbl` → `UserUpdateService` &nbsp;·&nbsp; tests: `UserUpdateServiceTest` · `UserAdminControllerIT`

_11 declaration(s): 9 method-mapped, 0 `-EXIT` no-op, 2 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 82 | `MAIN-PARA` | `UserUpdateService.mainEntry(…)` |
| 143 | `PROCESS-ENTER-KEY` | `UserUpdateService.processEnterKey(…)` |
| 177 | `UPDATE-USER-INFO` | `UserUpdateService.mainEntry(…)` / `UserUpdateService.updateUserInfo(…)` (+1) |
| 250 | `RETURN-TO-PREV-SCREEN` | `UserUpdateService.returnToPrevScreen(…)` |
| 266 | `SEND-USRUPD-SCREEN` | `UserUpdateService.showScreen(…)` |
| 283 | `RECEIVE-USRUPD-SCREEN` | folded into `UserUpdateService` control flow (§0.3.3) |
| 296 | `POPULATE-HEADER-INFO` | folded into `UserUpdateService` control flow (§0.3.3) |
| 320 | `READ-USER-SEC-FILE` | `UserUpdateService.readUserSecFile(…)` |
| 358 | `UPDATE-USER-SEC-FILE` | `UserUpdateService.updateUserSecFile(…)` / `UserUpdateService.buildUpdatedMessage(…)` |
| 395 | `CLEAR-CURRENT-SCREEN` | `UserUpdateService.clearCurrentScreen(…)` |
| 403 | `INITIALIZE-ALL-FIELDS` | `UserUpdateService.initializeAllFields(…)` |

#### `legacy/cbl/COUSR03C.cbl` → `UserDeleteService` &nbsp;·&nbsp; tests: `UserDeleteServiceTest` · `UserAdminControllerIT`

_11 declaration(s): 9 method-mapped, 0 `-EXIT` no-op, 2 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 82 | `MAIN-PARA` | `UserDeleteService.mainEntry(…)` |
| 142 | `PROCESS-ENTER-KEY` | `UserDeleteService.processEnterKey(…)` |
| 174 | `DELETE-USER-INFO` | `UserDeleteService.deleteUserInfo(…)` |
| 197 | `RETURN-TO-PREV-SCREEN` | `UserDeleteService.redirect(…)` / `UserDeleteService.mainEntry(…)` |
| 213 | `SEND-USRDEL-SCREEN` | `UserDeleteService.showScreen(…)` / `UserDeleteService.readUserSecFile(…)` |
| 230 | `RECEIVE-USRDEL-SCREEN` | folded into `UserDeleteService` control flow (§0.3.3) |
| 243 | `POPULATE-HEADER-INFO` | folded into `UserDeleteService` control flow (§0.3.3) |
| 267 | `READ-USER-SEC-FILE` | `UserDeleteService.readUserSecFile(…)` |
| 305 | `DELETE-USER-SEC-FILE` | `UserDeleteService.deleteUserSecFile(…)` / `UserDeleteService.buildDeletedMessage(…)` |
| 341 | `CLEAR-CURRENT-SCREEN` | `UserDeleteService.clearCurrentScreen(…)` |
| 349 | `INITIALIZE-ALL-FIELDS` | `UserDeleteService.initializeAllFields(…)` |

#### `legacy/cbl/CBTRN02C.cbl` → `PostTransactionJobConfig` &nbsp;·&nbsp; tests: `PostTransactionJobConfigIT`

_26 declaration(s): 8 method-mapped, 0 `-EXIT` no-op, 18 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 236 | `0000-DALYTRAN-OPEN` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 254 | `0100-TRANFILE-OPEN` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 273 | `0200-XREFFILE-OPEN` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 291 | `0300-DALYREJS-OPEN` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 309 | `0400-ACCTFILE-OPEN` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 327 | `0500-TCATBALF-OPEN` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 345 | `1000-DALYTRAN-GET-NEXT` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 370 | `1500-VALIDATE-TRAN` | `PostTransactionJobConfig.postTransactionProcessor(…)` |
| 380 | `1500-A-LOOKUP-XREF` | `PostTransactionJobConfig.PostTransactionJobConfig(…)` |
| 393 | `1500-B-LOOKUP-ACCT` | `PostTransactionJobConfig.PostTransactionJobConfig(…)` |
| 424 | `2000-POST-TRANSACTION` | `PostTransactionJobConfig.postTransactionProcessor(…)` / `PostTransactionJobConfig.postTransactionWriter(…)` |
| 446 | `2500-WRITE-REJECT-REC` | `PostTransactionJobConfig.buildRejectLine(…)` |
| 467 | `2700-UPDATE-TCATBAL` | `PostTransactionJobConfig.PostTransactionJobConfig(…)` / `PostTransactionJobConfig.resolveCategoryBalance(…)` |
| 503 | `2700-A-CREATE-TCATBAL-REC` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 526 | `2700-B-UPDATE-TCATBAL-REC` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 545 | `2800-UPDATE-ACCOUNT-REC` | `PostTransactionJobConfig.applyAccountMutation(…)` |
| 562 | `2900-WRITE-TRANSACTION-FILE` | `PostTransactionJobConfig.PostTransactionJobConfig(…)` |
| 582 | `9000-DALYTRAN-CLOSE` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 600 | `9100-TRANFILE-CLOSE` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 619 | `9200-XREFFILE-CLOSE` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 637 | `9300-DALYREJS-CLOSE` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 655 | `9400-ACCTFILE-CLOSE` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 674 | `9500-TCATBALF-CLOSE` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 692 | `Z-GET-DB2-FORMAT-TIMESTAMP` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 707 | `9999-ABEND-PROGRAM` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |
| 714 | `9910-DISPLAY-IO-STATUS` | folded into `PostTransactionJobConfig` control flow (§0.3.3) |

#### `legacy/cbl/CBACT04C.cbl` → `InterestCalcJobConfig` &nbsp;·&nbsp; tests: `InterestCalcJobConfigIT`

_22 declaration(s): 10 method-mapped, 0 `-EXIT` no-op, 12 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 234 | `0000-TCATBALF-OPEN` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 252 | `0100-XREFFILE-OPEN` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 270 | `0200-DISCGRP-OPEN` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 289 | `0300-ACCTFILE-OPEN` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 307 | `0400-TRANFILE-OPEN` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 325 | `1000-TCATBALF-GET-NEXT` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 350 | `1050-UPDATE-ACCOUNT` | `InterestCalcJobConfig.updateAccount(…)` |
| 372 | `1100-GET-ACCT-DATA` | `InterestCalcJobConfig.getAccountData(…)` |
| 393 | `1110-GET-XREF-DATA` | `InterestCalcJobConfig.getCardNumber(…)` |
| 415 | `1200-GET-INTEREST-RATE` | `InterestCalcJobConfig.resolveInterestRate(…)` |
| 443 | `1200-A-GET-DEFAULT-INT-RATE` | `InterestCalcJobConfig.resolveInterestRate(…)` |
| 462 | `1300-COMPUTE-INTEREST` | `InterestCalcJobConfig.computeMonthlyInterest(…)` |
| 473 | `1300-B-WRITE-TX` | `InterestCalcJobConfig.writeInterestTransaction(…)` |
| 518 | `1400-COMPUTE-FEES` | `InterestCalcJobConfig.calculateInterest(…)` / `InterestCalcJobConfig.computeFees(…)` |
| 522 | `9000-TCATBALF-CLOSE` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 541 | `9100-XREFFILE-CLOSE` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 559 | `9200-DISCGRP-CLOSE` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 577 | `9300-ACCTFILE-CLOSE` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 595 | `9400-TRANFILE-CLOSE` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |
| 613 | `Z-GET-DB2-FORMAT-TIMESTAMP` | `InterestCalcJobConfig.writeInterestTransaction(…)` |
| 628 | `9999-ABEND-PROGRAM` | `InterestCalcJobConfig.interestCalcTasklet(…)` / `InterestCalcJobConfig.getAccountData(…)` (+1) |
| 635 | `9910-DISPLAY-IO-STATUS` | folded into `InterestCalcJobConfig` control flow (§0.3.3) |

#### `legacy/cbl/CBSTM03A.CBL` → `StatementJobConfig` &nbsp;·&nbsp; tests: `StatementJobConfigIT`

_25 declaration(s): 9 method-mapped, 3 `-EXIT` no-op, 13 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 296 | `0000-START` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 316 | `1000-MAINLINE` | `StatementJobConfig.statementProcessor(…)` / `StatementJobConfig.statementStep(…)` |
| 341 | `9999-GOBACK` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 345 | `1000-XREFFILE-GET-NEXT` | `StatementJobConfig.statementXrefReader(…)` |
| 368 | `2000-CUSTFILE-GET` | `StatementJobConfig.statementProcessor(…)` |
| 392 | `3000-ACCTFILE-GET` | `StatementJobConfig.statementProcessor(…)` |
| 416 | `4000-TRNXFILE-GET` | `StatementJobConfig.appendHtmlFooter(…)` |
| 458 | `5000-CREATE-STATEMENT` | `StatementJobConfig.buildName(…)` / `StatementJobConfig.buildAddr3(…)` (+1) |
| 506 | `5100-WRITE-HTML-HEADER` | `StatementJobConfig.appendHtmlHeader(…)` |
| 554 | `5100-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 558 | `5200-WRITE-HTML-NMADBS` | `StatementJobConfig.appendHtmlDetail(…)` / `StatementJobConfig.beforeDoubleSpace(…)` |
| 671 | `5200-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 675 | `6000-WRITE-TRANS` | `StatementJobConfig.appendHtmlTransaction(…)` |
| 726 | `8100-FILE-OPEN` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 730 | `8100-TRNXFILE-OPEN` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 765 | `8200-XREFFILE-OPEN` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 783 | `8300-CUSTFILE-OPEN` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 801 | `8400-ACCTFILE-OPEN` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 818 | `8500-READTRNX-READ` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 849 | `8599-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 856 | `9100-TRNXFILE-CLOSE` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 873 | `9200-XREFFILE-CLOSE` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 889 | `9300-CUSTFILE-CLOSE` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 905 | `9400-ACCTFILE-CLOSE` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 921 | `9999-ABEND-PROGRAM` | folded into `StatementJobConfig` control flow (§0.3.3) |

#### `legacy/cbl/CBSTM03B.CBL` → `StatementJobConfig` &nbsp;·&nbsp; tests: `StatementJobConfigIT`

_14 declaration(s): 0 method-mapped, 8 `-EXIT` no-op, 6 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 116 | `0000-START` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 130 | `9999-GOBACK` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 133 | `1000-TRNXFILE-PROC` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 151 | `1900-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 154 | `1999-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 157 | `2000-XREFFILE-PROC` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 175 | `2900-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 178 | `2999-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 181 | `3000-CUSTFILE-PROC` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 200 | `3900-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 203 | `3999-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 206 | `4000-ACCTFILE-PROC` | folded into `StatementJobConfig` control flow (§0.3.3) |
| 225 | `4900-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |
| 228 | `4999-EXIT` | exit no-op — `PERFORM`-return landing (folded in `StatementJobConfig`) |

#### `legacy/cbl/CBACT01C.cbl` → `AccountPrintJobConfig` &nbsp;·&nbsp; tests: `AccountPrintJobConfigIT`

_6 declaration(s): 2 method-mapped, 0 `-EXIT` no-op, 4 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 92 | `1000-ACCTFILE-GET-NEXT` | `AccountPrintJobConfig.accountItemReader(…)` |
| 118 | `1100-DISPLAY-ACCT-RECORD` | `AccountPrintJobConfig.accountPrintWriter(…)` |
| 133 | `0000-ACCTFILE-OPEN` | folded into `AccountPrintJobConfig` control flow (§0.3.3) |
| 151 | `9000-ACCTFILE-CLOSE` | folded into `AccountPrintJobConfig` control flow (§0.3.3) |
| 169 | `9999-ABEND-PROGRAM` | folded into `AccountPrintJobConfig` control flow (§0.3.3) |
| 176 | `9910-DISPLAY-IO-STATUS` | folded into `AccountPrintJobConfig` control flow (§0.3.3) |

#### `legacy/cbl/CBACT02C.cbl` → `CardPrintJobConfig` &nbsp;·&nbsp; tests: `CardPrintJobConfigIT`

_5 declaration(s): 1 method-mapped, 0 `-EXIT` no-op, 4 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 92 | `1000-CARDFILE-GET-NEXT` | `CardPrintJobConfig.cardPrintReader(…)` |
| 118 | `0000-CARDFILE-OPEN` | folded into `CardPrintJobConfig` control flow (§0.3.3) |
| 136 | `9000-CARDFILE-CLOSE` | folded into `CardPrintJobConfig` control flow (§0.3.3) |
| 154 | `9999-ABEND-PROGRAM` | folded into `CardPrintJobConfig` control flow (§0.3.3) |
| 161 | `9910-DISPLAY-IO-STATUS` | folded into `CardPrintJobConfig` control flow (§0.3.3) |

#### `legacy/cbl/CBACT03C.cbl` → `XrefPrintJobConfig` &nbsp;·&nbsp; tests: `XrefPrintJobConfigIT`

_5 declaration(s): 1 method-mapped, 0 `-EXIT` no-op, 4 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 92 | `1000-XREFFILE-GET-NEXT` | `XrefPrintJobConfig.xrefItemReader(…)` |
| 118 | `0000-XREFFILE-OPEN` | folded into `XrefPrintJobConfig` control flow (§0.3.3) |
| 136 | `9000-XREFFILE-CLOSE` | folded into `XrefPrintJobConfig` control flow (§0.3.3) |
| 154 | `9999-ABEND-PROGRAM` | folded into `XrefPrintJobConfig` control flow (§0.3.3) |
| 161 | `9910-DISPLAY-IO-STATUS` | folded into `XrefPrintJobConfig` control flow (§0.3.3) |

#### `legacy/cbl/CBCUS01C.cbl` → `CustomerLoadJobConfig` &nbsp;·&nbsp; tests: `CustomerLoadJobConfigIT`

_5 declaration(s): 1 method-mapped, 0 `-EXIT` no-op, 4 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 92 | `1000-CUSTFILE-GET-NEXT` | `CustomerLoadJobConfig.customerItemReader(…)` |
| 118 | `0000-CUSTFILE-OPEN` | folded into `CustomerLoadJobConfig` control flow (§0.3.3) |
| 136 | `9000-CUSTFILE-CLOSE` | folded into `CustomerLoadJobConfig` control flow (§0.3.3) |
| 154 | `Z-ABEND-PROGRAM` | folded into `CustomerLoadJobConfig` control flow (§0.3.3) |
| 161 | `Z-DISPLAY-IO-STATUS` | folded into `CustomerLoadJobConfig` control flow (§0.3.3) |

#### `legacy/cbl/CBTRN01C.cbl` → `TransactionBackupJobConfig` &nbsp;·&nbsp; tests: `TransactionBackupJobConfigIT`

_18 declaration(s): 0 method-mapped, 0 `-EXIT` no-op, 18 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 155 | `MAIN-PARA` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 202 | `1000-DALYTRAN-GET-NEXT` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 227 | `2000-LOOKUP-XREF` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 241 | `3000-READ-ACCOUNT` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 252 | `0000-DALYTRAN-OPEN` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 271 | `0100-CUSTFILE-OPEN` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 289 | `0200-XREFFILE-OPEN` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 307 | `0300-CARDFILE-OPEN` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 325 | `0400-ACCTFILE-OPEN` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 343 | `0500-TRANFILE-OPEN` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 361 | `9000-DALYTRAN-CLOSE` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 379 | `9100-CUSTFILE-CLOSE` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 397 | `9200-XREFFILE-CLOSE` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 415 | `9300-CARDFILE-CLOSE` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 433 | `9400-ACCTFILE-CLOSE` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 451 | `9500-TRANFILE-CLOSE` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 469 | `Z-ABEND-PROGRAM` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |
| 476 | `Z-DISPLAY-IO-STATUS` | folded into `TransactionBackupJobConfig` control flow (§0.3.3) |

#### `legacy/cbl/CBTRN03C.cbl` → `TransactionReportJobConfig` &nbsp;·&nbsp; tests: `TransactionReportJobConfigIT`

_26 declaration(s): 7 method-mapped, 0 `-EXIT` no-op, 19 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 220 | `0550-DATEPARM-READ` | `TransactionReportJobConfig.transactionReportTasklet(…)` / `TransactionReportJobConfig.parseRequiredDate(…)` |
| 248 | `1000-TRANFILE-GET-NEXT` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 274 | `1100-WRITE-TRANSACTION-REPORT` | `TransactionReportJobConfig.writeTransactionReport(…)` |
| 293 | `1110-WRITE-PAGE-TOTALS` | `TransactionReportJobConfig.writePageTotals(…)` |
| 306 | `1120-WRITE-ACCOUNT-TOTALS` | `TransactionReportJobConfig.writeAccountTotals(…)` |
| 318 | `1110-WRITE-GRAND-TOTALS` | `TransactionReportJobConfig.writeGrandTotals(…)` |
| 324 | `1120-WRITE-HEADERS` | `TransactionReportJobConfig.writeHeaders(…)` |
| 343 | `1111-WRITE-REPORT-REC` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 361 | `1120-WRITE-DETAIL` | `TransactionReportJobConfig.writeDetail(…)` |
| 376 | `0000-TRANFILE-OPEN` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 394 | `0100-REPTFILE-OPEN` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 412 | `0200-CARDXREF-OPEN` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 430 | `0300-TRANTYPE-OPEN` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 448 | `0400-TRANCATG-OPEN` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 466 | `0500-DATEPARM-OPEN` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 484 | `1500-A-LOOKUP-XREF` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 494 | `1500-B-LOOKUP-TRANTYPE` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 504 | `1500-C-LOOKUP-TRANCATG` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 514 | `9000-TRANFILE-CLOSE` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 532 | `9100-REPTFILE-CLOSE` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 551 | `9200-CARDXREF-CLOSE` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 569 | `9300-TRANTYPE-CLOSE` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 587 | `9400-TRANCATG-CLOSE` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 605 | `9500-DATEPARM-CLOSE` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 626 | `9999-ABEND-PROGRAM` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |
| 633 | `9910-DISPLAY-IO-STATUS` | folded into `TransactionReportJobConfig` control flow (§0.3.3) |

#### `legacy/cbl/CSUTLDTC.cbl` → `DateConversionService` &nbsp;·&nbsp; tests: `DateConversionServiceTest`

_2 declaration(s): 0 method-mapped, 1 `-EXIT` no-op, 1 folded._

| Src line | Paragraph / section | Target `class.method` (source-grounded) |
| -------: | :------------------ | :--------------------------------------- |
| 103 | `A000-MAIN` | folded into `DateConversionService` control flow (§0.3.3) |
| 152 | `A000-MAIN-EXIT` | exit no-op — `PERFORM`-return landing (folded in `DateConversionService`) |


---

## 9. Exact Record Contracts (Offsets, Keys, Signs, FILLER)

Fixed-width parity requires exact byte contracts, not just record lengths. The table below gives the
key, the notable field offsets (1-based byte positions), the sign/representation, and the FILLER /
padding for each record. **All external monetary fields are signed DISPLAY / zoned decimal with an
implied scale and an overpunch sign on the trailing byte — not packed `COMP-3`** (only some internal
working-storage fields use `COMP-3`). Java uses `BigDecimal`; the `FixedWidthRecordMapper` preserves
the original zoned/overpunch characters.

| Source | Length / key | Critical offsets & representation |
| :----- | :----------- | :-------------------------------- |
| `CVACT01Y` (ACCTDAT) | 300; key `ACCT-ID` 1–11 (`9(11)` unsigned zoned) | status 12 `X(01)`; **five signed DISPLAY `S9(10)V99` (12 B each)**: curr-bal 13–24, credit-limit 25–36, cash-credit-limit 37–48, curr-cyc-credit 79–90, curr-cyc-debit 91–102; dates `X(10)` open 49–58 / expiry 59–68 / reissue 69–78; ZIP 103–112; group 113–122; **FILLER 123–300** |
| `CVACT02Y` (CARDDAT) | 150; key card 1–16 | account 17–27; **CVV 28–30**; expiry/status follow |
| `CVACT03Y` (CCXREF) | 50; card primary access | customer 17–25; account 26–36 (account alternate access → `CXACAIX`) |
| `CVCUS01Y` / `CUSTREC` | 500; key customer 1–9 | phones 250–279; **SSN 280–288**; **government ID 289–308**; **DOB 309–318** (`X(10)`; alias `CUST-DOB-YYYYMMDD` in `CUSTREC` vs `CUST-DOB-YYYY-MM-DD` in `CVCUS01Y`); **FICO 330–332** |
| `CVTRA01Y` (TCATBAL) | 50; composite key 1–17 (account+type+category) | signed DISPLAY balance 18–28 |
| `CVTRA02Y` (DISCGRP) | 50; composite key 1–16 (group+type+category) | signed DISPLAY rate 17–22 (`S9(4)V99`) |
| `CVTRA03Y` (TRANTYPE) | 60 | type 1–2; description 3–52 |
| `CVTRA04Y` (TRANCATG) | 60 | type/category 1–6; description 7–56 |
| `CVTRA05Y` / `CVTRA06Y` | 350 | amount 133–143 (signed DISPLAY); card 263–278; timestamps 279–330; **FILLER 331–350** |
| `COSTM01` (statement) | 350 | card+transaction key 1–32; amount 149–159 |
| `CSUSR01Y` / `UNUSED1Y` | 80 | id 1–8; **password 49–56** (cleartext `X(08)`); type 57 `X(01)`; **FILLER 58–80** |
| `COCOM01Y` (COMMAREA) | 160 | routing/user/context 1–34; customer 35–118; account 119–130; card 131–146; map state 147–160 |
| `CBTRN02C` reject | 430 | input record 1–350; reason code 351–354; description 355–430 |
| `CBTRN03C` report | 133 | `DATEPARM` FD 80, meaningful prefix 21; padded report groups |
| `CBSTM03` outputs | 80 / 100 | fixed statement text (80) and HTML record (100) |
| `PRTCATBL` `OUTREC` + `REPTFILE` report (**print output** of `batch/CategoryBalancePrintJobConfig.java`; reads `CVTRA01Y`/TCATBAL, 50 — distinct from that 50-byte input record above) | **40** (`SORTOUT LRECL=40`) | acct 1–11 (`9(11)` zoned, zero-padded); space 12; type 13–14 (`X(02)`); space 15; category 16–19 (`9(04)` zoned); space 20; **balance 21–32 edited `TTTTTTTTT.TT` (12 B) — magnitude only, DFSORT `EDIT` emits no sign (`-504.77`→`000000504.77`)**; trailing spaces 33–40 (`OUTREC` `9X` capped to 8 by `LRECL=40`) |

> **Collation note.** Because EBCDIC and ASCII/UTF-8 orderings differ, ordered output (notably
> `COMBTRAN`'s `SORT FIELDS=(TRAN-ID,A)`) is reproduced with a deterministic `C`/`POSIX` collation on
> the `CHAR` key, validated against the ASCII fixtures (see `decision-log.md`).

---

## 10. Source-Quirk Ledger (Preserve-or-Deviate + Parity-Test Obligations)

The source contains deliberate and accidental quirks. Per the AAP's 100 %-parity mandate, each is
**preserved** unless an explicit deviation is justified; each carries a parity-test obligation so the
behavior is locked. "Cleaning up" any of these without a logged decision and a parity test would be a
defect.

| # | Quirk (source evidence) | Disposition | Parity-test obligation |
| --: | :---------------------- | :---------- | :--------------------- |
| 1 | `COCRDLIC.cbl:790` stray `I` token | **Preserve** semantics: the stray token does not alter observable output; the target reproduces the list behavior exactly and drops only the no-op token, logged here | Card-list output for 7 rows matches byte-for-byte |
| 2 | `CBACT04C`: unreachable final-account flush; no-`ROUNDED` truncation; empty fee paragraph (`1400-COMPUTE-FEES`) | **Preserve**: interest truncates (`RoundingMode.DOWN`); the empty fee step is a no-op; the unreachable flush stays unreachable | Interest parity oracle; assert no fee applied; assert final-flush path is not taken |
| 3 | `CBTRN02C`: reason **103 overwrites 102**; reason **109 occurs after acceptance and never reaches the reject writer** | **Preserve** exact reason-code precedence and the post-acceptance 109 path (record is accepted, 109 not written to `DALYREJS`) | Reject-file parity across 102/103 collision and 109-after-acceptance cases |
| 4 | `COACTUPC`: broad state machine; City/line-3 mapping; segmented phones; **5-byte screen ZIP vs `X(10)` record ZIP** | **Preserve** state breadth and the screen-vs-record ZIP width difference (map 5, record 10) | Update-flow parity incl. ZIP round-trip and phone segmentation |
| 5 | `COCRDUPC`: **CVV retained in comparison but hidden**; **protected EXPDAY** | **Preserve**: CVV participates in the optimistic compare but is never displayed; EXPDAY stays protected | Card-update optimistic-compare parity; assert CVV never rendered |
| 6 | Four online files define an **unused/mismatched list-map constant using `CCRDSLA` rather than `CCRDLIA`** | **Preserve** the constants as-is (unused); do not "fix" the mismatch | Assert the mismatched constant has no runtime effect (dead constant) |
| 7 | Customer **DOB identifier alias** (`CUST-DOB-YYYYMMDD` vs `CUST-DOB-YYYY-MM-DD`) | **Preserve** as a single field with both aliases documented (see [§4](#4-copybooks--entities--dtos--enums--constants) / `decision-log.md`) | Customer load/round-trip parity at bytes 309–318 |
| 8 | `COMEN02Y`: **active Transaction Add is user-accessible** despite a commented "Admin Only" text | **Preserve** the actual (user-accessible) behavior, not the stale comment | Menu-authorization parity: a non-admin user can reach Transaction Add exactly as in source |
| 9 | `CSSETATY` executable `COPY … REPLACING` logic; `CSMSG02Y` `ABEND-DATA` structure | **Preserve** as behavior/structure (see [§4](#4-copybooks--entities--dtos--enums--constants) / [§8](#8-construct-level-traceability-paragraphs-executable-copybooks-subprograms)); do **not** reduce to constants | Field-highlight/`*` behavior parity; abend-context population parity |
| 10 | `CSUTLDPY`/`CSUTLDWY` validation + century/window rules; `CSLKPCDY` **frozen lookups** | **Preserve** the exact validation outcomes, century windowing, and the frozen lookup values | Date-validation parity across the 14 paragraphs; lookup-value snapshot test |
| 11 | **Signed DISPLAY vs `COMP-3` distinction** for monetary fields | **Preserve**: external records are signed DISPLAY/zoned overpunch; only internal working fields may be `COMP-3` (see [§9](#9-exact-record-contracts-offsets-keys-signs-filler)) | Fixed-width codec round-trip preserves the exact zoned/overpunch bytes |
| 12 | **Active sensitive-record displays** (`CBACT01C/02C/03C`, `CBCUS01C`, `CBTRN01C`, `CBTRN03C` `DISPLAY` full records) | **Preserve** the protected parity *report* output; **separate** it from redacted operational logs (see [§11](#11-sensitive-field-classification--authorization-matrix)) | Report-output parity; assert operational logs are redacted |
| 13 | `CBTRN03C`: **stale final amount added again after EOF**; **final account total not emitted** | **Preserve** both: the post-EOF double-add and the omitted final total | Report-total parity reproducing the double-add and the missing final total |
| 14 | **PF/focus variances**: `COACTVW` & `COCRDLI` handle unadvertised ENTER; `COUSR01` advertises unhandled F12; `COUSR03` handles unadvertised F12; `COCRDUP` IC on a protected field; `COTRN00`/`COUSR00` have no IC marker | **Preserve** each screen's actual key/focus handling, not the advertised legend | Per-screen key/focus parity tests reproducing each variance |

---

## 11. Sensitive-Field Classification & Authorization Matrix

This section satisfies the security-traceability requirement (M-14): it classifies sensitive fields,
states storage/display/logging controls, and defines the direct-route A/U authorization matrix. It
complements the *Sensitive-field handling* decision in [`decision-log.md`](./decision-log.md).
Controls are **behavior-preserving** (parity first); behavior-changing hardening is deferred and
logged as a suggested next task.

**Field classification & controls.**

| Field (source offset) | Class | Storage (parity) | Display control | Logging control |
| :-------------------- | :---- | :--------------- | :-------------- | :-------------- |
| Password `CSUSR01Y` 49–56 | Secret / credential | Cleartext `CHAR(8)` (parity; BCrypt deferred) | Never rendered on screen | **Never logged**; excluded from structured logs |
| PAN / card number `CVACT02Y` 1–16 | PCI — sensitive | `CHAR(16)` (fixed-width parity) | Shown only where the source screen shows it | **Never logged** in operational logs; parity report output is protected |
| CVV `CVACT02Y` 28–30 | PCI — highly sensitive | `CHAR(3)` (parity) | **Never displayed** (quirk #5: used in compare, hidden) | **Never logged** |
| SSN `CVCUS01Y` 280–288 | PII | `9(9)`/`CHAR` (parity) | Shown only where source shows it | **Never logged** |
| Government ID 289–308 | PII | `CHAR(20)` (parity) | As per source screen | **Never logged** |
| DOB 309–318 | PII | `CHAR(10)` (parity; alias per quirk #7) | As per source screen | Redacted in operational logs |
| Phones 250–279 | PII | `CHAR` (parity; segmented — quirk #4) | As per source screen | Redacted in operational logs |
| EFT account data | PII / financial | `CHAR` (parity) | As per source screen | **Never logged** |
| FICO 330–332 | Financial-sensitive | `9(3)` (parity) | As per source screen | Redacted in operational logs |
| Balances / limits (`CVACT01Y` signed DISPLAY) | Financial | `NUMERIC(12,2)` via `BigDecimal` | As per source screen | Redacted in operational logs |
| Transaction history (`CVTRA05Y`) | Financial | per-record parity | As per source screen | Redacted in operational logs |

**Operational-log vs. parity-report separation.** The source `DISPLAY`s full records in `CBACT01C`,
`CBACT02C`, `CBACT03C`, `CBCUS01C`, `CBTRN01C`, and `CBTRN03C` (quirk #12). The Java target keeps two
distinct sinks: (a) **protected parity report output** that reproduces the source dump byte-for-byte
(for parity testing / operator report files), and (b) **redacted operational logs** (structured JSON
via `logback-spring.xml`) that never contain PAN/CVV/SSN/password. These are not the same stream.

**Redaction is enforced, not aspirational.** Every entity, DTO, and the security principal that
carries a sensitive field overrides `toString()` to emit only `ClassName@identityHash` — no field
values. Consequently, even if such an object is handed to a logger, no PAN / CVV / SSN / password /
DOB / FICO value can reach an operational log through `toString()`. The "Never logged" / "Redacted"
guarantees in the table above are therefore **implemented in code** (across all 25
sensitive-value-bearing classes), not merely planned; the only stream that intentionally retains full
values is the access-controlled parity report output described above.

**Direct-route A/U authorization matrix.** Menu visibility is **not** sufficient; each endpoint must
independently enforce role authorization, and dynamic `XCTL` targets become a **server-side
allowlist** (not a client-supplied program name).

| Route / function | Source tran | Required role | Direct-navigation control |
| :--------------- | :---------- | :------------ | :------------------------ |
| Admin menu, user admin (list/add/update/delete) | `CA00`, `CU00–CU03` | `ROLE_ADMIN` | Method/URL `@PreAuthorize`-equivalent; direct URL blocked for `ROLE_USER` |
| Main menu | `CM00` | `ROLE_USER` or `ROLE_ADMIN` | Authenticated only |
| Account view/update | `CAVW`, `CAUP` | authenticated; per source | Direct URL enforces auth independent of menu |
| Card list/detail/update | `CCLI`, `CCDL`, `CCUP` | authenticated; per source | Direct URL enforces auth |
| **Card-detail security variant** | `CDV1` / `COCRDSEC` | **deferred disposition** | Realized in `SecurityConfig` (no `.cbl`); see [§12](#12-intentional-non-migrations-gaps-ledger) — the `CDV1`/`COCRDSEC` authorization intent is captured as URL/method rules; any residual behavior is a logged deferred item |
| Transaction list/view/add | `CT00`, `CT01`, `CT02` | authenticated; **Transaction Add is user-accessible (quirk #8)** | Direct URL reproduces source accessibility exactly |
| Bill pay | `CB00` | authenticated; per source | Direct URL enforces auth |
| Report submit | `CR00` | authenticated; per source | `CORPT00C` validates dates then launches a **named Spring Batch job with typed date parameters** — no OS/JCL command-execution surface is reproduced |

---

## 12. Intentional Non-Migrations (Gaps Ledger)

The following three constructs are **deliberately not migrated to Java code**. Each is logged here
(and in [`decision-log.md`](./decision-log.md)) so that coverage is provably *accounted-for* rather
than silently reduced.

| Construct | Reason not migrated | How coverage is preserved |
| :-------- | :------------------ | :------------------------ |
| `legacy/cpy/UNUSED1Y.cpy` | Dead code — not referenced (`COPY`d) by any program in the codebase. | Logged here and in `decision-log.md`; excluded deliberately, so no orphaned entity/DTO is created. |
| `COCRDSEC` (CSD program, transaction `CDV1`; no `.cbl`) | CICS-only card-detail security variant with **no COBOL business logic** to translate. | Realized as `config/SecurityConfig.java` method/URL authorization rather than a standalone service; the `CDV1` authorization intent is captured in the [§11](#11-sensitive-field-classification--authorization-matrix) route matrix. |
| `legacy/jcl/OPENFIL.jcl`, `legacy/jcl/CLOSEFIL.jcl` | CICS file **enable/disable** (`IEFBR14`) operations that toggle VSAM availability to the CICS region. | Not applicable under a Spring-managed connection pool, which opens/closes connections automatically. Logged as N/A. |

**These are the only intentional gaps.** With all three explicitly accounted for, **every** COBOL /
z/OS construct is either mapped to a Java target or logged here — i.e. inventory coverage
reconciles exactly with the [Coverage Summary](#1-coverage-summary). As stated in the
implementation-status note, this is both *inventory* completeness and *implemented* completeness:
**120 of 123** constructs have their target artifact present, and the remaining **3** are the
intentional non-migrations ledgered in this section.

---

## 13. Reverse-Direction Convention (Java → Legacy)

Traceability is designed to be **bidirectional**. In addition to the forward tables above, generated
Java classes carry a Javadoc **origin-tag convention** citing the `legacy/` source path (and, where
relevant, the CICS transaction id or JCL job) they were derived from. **This convention is applied
across the generated application:** of the **121** Java files under `src/main/java/**`, **96 carry an
explicit `Origin:` Javadoc tag** and a further **20** cite their `legacy/` source in class-Javadoc
prose — so **116 of 121** carry a legacy-origin citation. The remaining **5** are net-new
cross-cutting infrastructure classes with **no COBOL antecedent**, for which omitting a legacy origin
is correct:

| Net-new class (no legacy origin) | Why there is no COBOL antecedent |
| :------------------------------- | :------------------------------- |
| `config/ObservabilityConfig` | Observability rule deliverable (metrics filters / tracing) — no mainframe equivalent |
| `security/SessionRevocationService` | Spring Security live-session revocation (#8/#43); CICS pseudo-conversational had no session-registry concept |
| `util/batch/BatchFilePathResolver` | Path-traversal-safe dataset resolution (#18) — a security hardening with no COBOL counterpart |
| `util/batch/AtomicFileStepPublisher` | Atomic (temp-fsync-rename, 0600) output publication (#18/#34) — infrastructure, not a ported program |
| `util/batch/FixedBlockLineAggregator` | Reusable `RECFM=FB` output framing helper (#17); it realises a COBOL *concept* but is generic Spring Batch infrastructure, not a 1:1 program port |

This 121 / 116 / 5 split is verifiable directly from the tree (`grep -rl 'legacy/\|Origin:'
src/main/java`). The paragraph-level reverse detail — which Java method each COBOL paragraph maps to —
is the forward enumeration in [§8.1](#81-exhaustive-paragraph--section--classmethod--test-enumeration);
each method there carries the corresponding `Origin:`/legacy citation in its own Javadoc, closing the
loop at method granularity.

The convention is a Javadoc line of the form:

```java
/**
 * Origin: legacy/cbl/COSGN00C.cbl (CICS tran CC00).
 * ... class description ...
 */
```

For artifacts synthesised from more than one legacy member, the origin tag lists each source, e.g.
`Origin: legacy/cbl/CBTRN02C.cbl + legacy/jcl/POSTTRAN.jcl`. This lets a reader navigate from any
Java class back to the exact COBOL/JCL/BMS/copybook it replaces — the property that makes the matrix
bidirectional per the Explainability rule.

**Examples (all present; the citation column notes whether the origin appears as an explicit `Origin:` tag or as class-Javadoc prose):**

| Java artifact | Javadoc origin tag (reverse citation) | Status |
| :------------ | :------------------------------------ | :----- |
| `domain/Account.java` | cites `legacy/cpy/CVACT01Y.cpy` (`ACCTDAT`, RECLN 300) in class Javadoc | present *(prose citation)* |
| `dto/CardDemoContext.java` | `Origin: legacy/cpy/COCOM01Y.cpy` | present *(explicit tag)* |
| `util/DateConversionService.java` | `Origin: legacy/cbl/CSUTLDTC.cbl` | present *(explicit tag)* |
| `batch/PostTransactionJobConfig.java` | cites `legacy/cbl/CBTRN02C.cbl` + `legacy/jcl/POSTTRAN.jcl` in class Javadoc | present *(prose citation)* |
| `web/controller/SignonController.java` | `Origin: legacy/cbl/COSGN00C.cbl (CICS tran CC00)` | present *(explicit tag)* |

> The generated application satisfies this in both directions: each forward row's target carries a
> corresponding reverse origin citation, keeping the two directions mutually consistent. The forward
> mapping in this document remains the authoritative COBOL → Java direction, and this section defines
> the reverse convention every generated class honors.
