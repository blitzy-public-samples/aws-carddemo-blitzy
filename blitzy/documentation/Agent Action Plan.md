# Technical Specification

# 0. Agent Action Plan

## 0.1 Intent Clarification

### 0.1.1 Core Refactoring Objective

Based on the prompt, the Blitzy platform understands that the refactoring objective is to migrate the entire CardDemo COBOL/CICS/VSAM mainframe application to a modern Java technology stack while preserving 100% of the existing functional behavior. Specifically, the refactor must:

- Convert all 17 CICS online programs (CO* prefix) in `app/cbl/` [app/cbl/:directory] into Spring Boot REST controllers organized by functional domain (Account, Card, Customer, Transaction, BillPayment, Report, User, Menu, Auth)
- Convert all 29 JCL batch jobs in `app/jcl/` [app/jcl/:directory] into Spring Batch Job beans, preserving the critical batch sequence `POSTTRAN → INTCALC → COMBTRAN → CREASTMT`
- Convert all 11 batch COBOL programs (CB* prefix and CSUTLDTC) in `app/cbl/` [app/cbl/:directory] into Spring Batch step components, with `CBACT04C` (interest calculation) [app/cbl/CBACT04C.cbl:L462-L470], `CBTRN02C` (transaction posting) [app/cbl/CBTRN02C.cbl:L370-L501], and `CBSTM03A` (statement generation) [app/cbl/CBSTM03A.CBL:L506-L555] preserved line-by-line
- Replace all VSAM KSDS files and AIX alternate indexes with JPA entities backed by PostgreSQL 15, mapping each record-defining copybook in `app/cpy/` [app/cpy/:directory] to a `@Entity` class
- Replace the RACF/VSAM `USRSEC` security model [app/cpy/CSUSR01Y.cpy:L17-L23] with Spring Security 6 using BCrypt password encoding and the existing two-tier role model (`A` = ADMIN, `U` = USER) [app/cpy/COCOM01Y.cpy:L19-L44]
- Preserve all original COBOL/JCL/copybook/BMS/CSD source files in place as REFERENCE artifacts for downstream regression verification

**Refactoring type:** Tech stack migration (legacy COBOL/CICS/VSAM → modern Java Spring Boot/JPA/PostgreSQL).

**Target repository:** Same repository — Java sources colocated under a new `src/` tree, with the existing `app/` tree (COBOL, JCL, copybooks, BMS, CSD, ASCII fixtures) preserved unchanged as reference material.

**Refactoring goals, surfaced with enhanced clarity:**

- **G1 — Functional parity:** Every existing business behavior produces identical outputs given identical inputs. Includes interest calculation, transaction validation/posting, statement generation, account balance updates, bill payment, and report submission.
- **G2 — Data integrity:** Every VSAM record layout maps to a PostgreSQL table whose columns, types, and lengths mirror the COBOL `PIC` clause semantics (signed packed-decimal money fields → `NUMERIC` and `java.math.BigDecimal` scale 2, fixed-width strings → `VARCHAR(n)`, dates → `DATE`/`TIMESTAMP`).
- **G3 — Security improvement (mandatory closure of pre-existing gaps):** Replace plaintext password comparison [app/cbl/COSGN00C.cbl:L211-L257] with BCrypt; enforce `@PreAuthorize("hasRole('ADMIN')")` on all user-administration endpoints (the original COUSR00C-03C programs had no programmatic auth check, relying only on menu routing — a documented vulnerability).
- **G4 — Operational simplicity:** Single Spring Boot monolith deployable as an executable JAR; no microservices decomposition, no message queues, no cloud-native services beyond what is required by Spring Boot itself.
- **G5 — Verifiability:** Parity tests (`InterestCalculationParityTest`, `TransactionPostingParityTest`, `StatementGenerationParityTest`) compare Java outputs against COBOL outputs character-by-character or value-by-value for the three critical batch programs.

**Implicit requirements surfaced from the prompt:**

- **Currency arithmetic must use `BigDecimal` with scale 2 and `RoundingMode.HALF_UP`.** COBOL `PIC S9(10)V99 COMP-3` fields [app/cpy/CVACT01Y.cpy:L4-L17] are exact packed-decimal; Java `float`/`double` would corrupt cents and is forbidden for any monetary calculation.
- **Initial database seeding must rehash the literal password `"PASSWORD"`** for the 10 default users (`ADMIN001`-`ADMIN005`, `USER0001`-`USER0005`) [Tech Spec §6.4] into BCrypt hashes; the original USRSEC plaintext values cannot be retained.
- **Composite primary keys via `@EmbeddedId`** for `TCATBAL` (account + type + category) [app/cpy/CVTRA01Y.cpy:L4-L10], `DISCGRP` (group + type + category) [app/cpy/CVTRA02Y.cpy:L4-L10], and `TRANCATG` (type + category) [app/cpy/CVTRA04Y.cpy:L4-L9].
- **JPA `@Index` annotations** replace the three VSAM AIX alternate indexes: `CARDDATA.AIX` on `CARD-ACCT-ID`, `CARDXREF.AIX` on `XREF-ACCT-ID`, `TRANSACT.AIX` on `TRAN-ORIG-TS` [app/csd/CARDDEMO.CSD:L1-L99].
- **`@Transactional` boundaries** on service methods replicate the implicit CICS Unit-of-Work / `SYNCPOINT` semantics that bracketed each pseudo-conversational interaction.
- **DALYTRAN staging table** materializes the daily-transaction PS file [app/cpy/CVTRA06Y.cpy:L4-L18] as a Spring Batch input source, since PostgreSQL cannot directly read EBCDIC fixed-width data.
- **CSUTLDTC date conversion utility** [app/cbl/CSUTLDTC.cbl] becomes a Java `DateConversionUtil` class supporting CCYYMMDD ↔ MM/DD/YYYY ↔ DB2 timestamp format `YYYY-MM-DD-HH.MM.SS.MIL0000`.
- **`COMMAREA` (1024 bytes, `COCOM01Y`)** [app/cpy/COCOM01Y.cpy:L19-L44] is decomposed into: Spring Security `SecurityContextHolder` (user identity + authorities), HTTP path/query parameters (account/card/customer context), and request DTO fields (`pgmContext` ENTER/REENTER flag for form-submission distinctions).
- **No 3270 terminal UI is produced.** The BMS map sources in `app/bms/` and BMS symbolic copybooks in `app/cpy-bms/` become REFERENCE artifacts; the REST API consumes/emits JSON only.

### 0.1.2 Technical Interpretation

This refactoring translates to the following technical transformation strategy: the existing dual-mode mainframe (CICS pseudo-conversational online + JCL batch) is replaced by a single Spring Boot 3.2 application that exposes a stateless REST API for online flows and runs Spring Batch 5 jobs for batch flows, with both modes sharing a single PostgreSQL 15 schema and a single Spring Security 6 authentication model.

**Current architecture → target architecture mapping:**

| Current Mainframe Construct | Target Java/Spring Construct |
|---|---|
| CICS online program (CO* COBOL) | `@RestController` + `@Service` + `@Repository` in `com.carddemo.{controller,service,repository}` |
| EXEC CICS XCTL PROGRAM(...) [app/cbl/COSGN00C.cbl:L211-L257] | Stateless REST request/response; client controls navigation (no server-side program chaining) |
| EXEC CICS RETURN TRANSID(...) | HTTP response; next-state hint via response body field or HATEOAS link |
| COMMAREA (1024 bytes, COCOM01Y) [app/cpy/COCOM01Y.cpy:L19-L44] | `SecurityContextHolder` authentication object + HTTP path/query params + DTO context fields |
| BMS map (`SEND MAP` / `RECEIVE MAP`) | JSON request/response DTOs serialized by Jackson |
| EXEC CICS READ DATASET(...) RIDFLD(...) | `JpaRepository.findById(...)` |
| EXEC CICS READ ... KEYLENGTH(...) on AIX path | `@Index`-backed finder method (`findByAccountId`, `findByOrigTimestampBetween`) |
| EXEC CICS READ ... UPDATE / REWRITE | JPA managed entity + `entityManager.merge` with `@Version` optimistic locking |
| EXEC CICS WRITE | `repository.save(...)` |
| STARTBR / READNEXT / ENDBR (browse cursor for COCRDLIC pagination) [app/cbl/COCRDLIC.cbl] | Spring Data `Pageable` (`PageRequest.of(page, size, Sort.by(...))`) — cursor recomputed per request |
| EXEC CICS WRITEQ TD QUEUE('JOBS') (CORPT00C async job submission) [app/cbl/CORPT00C.cbl] | `JobLauncher.run(...)` with `@Async` for fire-and-forget batch submission |
| JCL job (PGM=...) [app/jcl/*.jcl] | `@Bean public Job ...JobConfig` with one or more `Step` beans |
| JCL EXEC step ordering (STEP010 → STEP020 → ...) | `JobBuilder.start(step1).next(step2).next(step3).build()` |
| JCL `PARM='2022071800'` [app/jcl/INTCALC.jcl:L22] | `JobParametersBuilder` parameter (e.g., `tranDate`) |
| Sequential DALYTRAN read [app/cpy/CVTRA06Y.cpy:L4-L18] | `JdbcCursorItemReader` over `daily_transactions` staging table OR `FlatFileItemReader` for fixed-width input |
| INVALID-KEY handler (status `23`) [app/cbl/CBACT04C.cbl:L415-L440] | `Optional.empty()` from `findById`, mapped to custom exception (e.g., `AccountNotFoundException` → HTTP 404 / batch code 101) |
| RACF / VSAM USRSEC keyed read [app/cbl/COSGN00C.cbl:L211-L257] | `UserDetailsServiceImpl.loadUserByUsername(...)` + `DaoAuthenticationProvider` + `BCryptPasswordEncoder` |
| User type `A`/`U` (COCOM01Y 88-levels) [app/cpy/COCOM01Y.cpy:L19-L44] | Spring Security `GrantedAuthority` `ROLE_ADMIN` / `ROLE_USER` mapped by `CustomAuthorityMapper` |
| Menu-only authorization (COADM01C → COUSR00C-03C) [app/cbl/COADM01C.cbl] | Method-level `@PreAuthorize("hasRole('ADMIN')")` (closes the documented programmatic-auth gap per Tech Spec §6.4) |
| VSAM `READ UPDATE` exclusive lock (CI-level, ~5-20ms, DTIMOUT 180s) | JPA `@Version` optimistic locking; `OptimisticLockException` → HTTP 409 Conflict |
| VSAM AIX rebuild via `TRANIDX` (DELETE → DEFINE → BLDINDEX → DEFINE PATH) [app/jcl/TRANIDX.jcl] | Replaced by PostgreSQL B-tree `@Index` annotations + Flyway DDL (no rebuild needed) |
| IDCAMS REPRO seed load [app/ctl/REPROCT.ctl] | Spring Batch `DataInitializationJobConfig` reading `app/data/ASCII/*.txt` via `AsciiFixedWidthItemReader` |
| GDG backup datasets (`TRANSACT.BKUP(0)`, etc.) [app/jcl/TRANBKP.jcl] | PostgreSQL `pg_dump` invoked from `TransactionBackupJobConfig` tasklet (operational pattern; not a deliverable artifact) |
| DB2-format timestamp `YYYY-MM-DD-HH.MM.SS.MIL0000` (26 chars) [app/cbl/CBACT04C.cbl:L613-L626] | `DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS'0000'")` applied at I/O boundaries |
| Plain text + HTML statement files (STMT-FILE 80-byte, HTML-FILE 100-byte) [app/cbl/CBSTM03A.CBL:L1-L35] | Two writer beans — `PlainTextStatementWriter` (80-char line wrap) and `HtmlStatementWriter` (Thymeleaf or string concatenation matching `5100-WRITE-HTML-HEADER`) |

**Transformation rules applied uniformly across the codebase:**

- Every record-defining copybook in `app/cpy/` becomes exactly one `@Entity` class in `com.carddemo.entity` with field names converted from `COBOL-DASH-CASE` to `camelCase`, preserving COBOL field length via `@Column(length = ...)`.
- Every CICS online program becomes a controller method (or set of methods) in the appropriately named controller, with the COBOL program's main paragraph logic moved to a service method.
- Every batch COBOL program becomes a Spring Batch `Job` bean plus one or more `Step` beans (chunk-oriented where the COBOL loops sequentially, tasklet where the operation is a single bulk SQL statement).
- Every JCL job becomes a `Job` bean of the same logical name, registered with `JobRegistry` so the `BatchAdminController` can launch it by name.
- Every VSAM AIX alternate index becomes a JPA `@Index` declaration on the entity, plus a finder method on the repository whose name encodes the index column.
- Every `EXEC CICS SYNCPOINT` boundary is replaced by a `@Transactional` method scope.
- Every plaintext password is replaced by a BCrypt hash; every direct string comparison is replaced by `BCryptPasswordEncoder.matches(rawPassword, storedHash)`.

## 0.2 Scope Boundaries

### 0.2.1 Exhaustively In Scope

The following files and file patterns are explicitly within the scope of this refactor. Each is annotated with its target transformation mode (see §0.4 for the complete file-by-file mapping).

**Source COBOL programs (transformed into Java services / batch components):**

- `app/cbl/CO*.cbl` — All 17 CICS online programs (transformed into REST controllers + services):
    - `app/cbl/COSGN00C.cbl` (signon — replaces plaintext check with BCrypt)
    - `app/cbl/COMEN01C.cbl`, `app/cbl/COADM01C.cbl` (main and admin menus → `MenuController`)
    - `app/cbl/COACTVWC.cbl`, `app/cbl/COACTUPC.cbl` (account view/update → `AccountController` + `AccountService`)
    - `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` (card list/view/update → `CardController` + `CardService`)
    - `app/cbl/COTRN00C.cbl`, `app/cbl/COTRN01C.cbl`, `app/cbl/COTRN02C.cbl` (transaction list/view/add → `TransactionController` + `TransactionService`)
    - `app/cbl/COBIL00C.cbl` (bill payment → `BillPaymentController` + `BillPaymentService`)
    - `app/cbl/CORPT00C.cbl` (report request via TDQ → `ReportController` + `ReportService` using `JobLauncher`)
    - `app/cbl/COUSR00C.cbl`, `app/cbl/COUSR01C.cbl`, `app/cbl/COUSR02C.cbl`, `app/cbl/COUSR03C.cbl` (user admin → `UserController` + `UserService`, all `@PreAuthorize("hasRole('ADMIN')")`)
- `app/cbl/CB*.cbl` and `app/cbl/CSUTLDTC.cbl` — All 11 batch programs (transformed into Spring Batch Job configurations):
    - `app/cbl/CBACT04C.cbl` (interest calculation — **PRESERVE LINE-BY-LINE**) [app/cbl/CBACT04C.cbl:L462-L470]
    - `app/cbl/CBTRN02C.cbl` (transaction posting — **PRESERVE LINE-BY-LINE**) [app/cbl/CBTRN02C.cbl:L370-L501, L545-L560]
    - `app/cbl/CBSTM03A.CBL` (statement generation — **PRESERVE HTML EMISSION LINE-BY-LINE**) [app/cbl/CBSTM03A.CBL:L506-L555]
    - `app/cbl/CBSTM03B.CBL` (I/O subroutine; merged into `StatementIoSubroutine` helper)
    - `app/cbl/CBACT01C.cbl`, `app/cbl/CBACT02C.cbl`, `app/cbl/CBACT03C.cbl`, `app/cbl/CBCUS01C.cbl`, `app/cbl/CBTRN01C.cbl`, `app/cbl/CBTRN03C.cbl` (diagnostic batch readers)
    - `app/cbl/CSUTLDTC.cbl` (date conversion → `DateConversionUtil`)

**JCL jobs (transformed into Spring Batch `Job` beans):**

- `app/jcl/POSTTRAN.jcl` → `TransactionPostingJobConfig` [app/jcl/POSTTRAN.jcl:L23]
- `app/jcl/INTCALC.jcl` → `InterestCalculationJobConfig` (accepts `tranDate` JobParameter equivalent to `PARM='2022071800'`) [app/jcl/INTCALC.jcl:L22]
- `app/jcl/COMBTRAN.jcl` → `TransactionConsolidationJobConfig` (SORT + REPRO replaced by SQL UPSERT) [app/jcl/COMBTRAN.jcl:L22-L48]
- `app/jcl/CREASTMT.JCL` → `StatementGenerationJobConfig` (4 steps preserved as chained Spring Batch steps) [app/jcl/CREASTMT.JCL:L22-L97]
- `app/jcl/DUSRSECJ.jcl` → `UserSeedingJobConfig` (BCrypts `"PASSWORD"` for 10 default users)
- `app/jcl/ACCTFILE.jcl`, `app/jcl/CARDFILE.jcl`, `app/jcl/CUSTFILE.jcl`, `app/jcl/XREFFILE.jcl`, `app/jcl/TRANTYPE.jcl`, `app/jcl/TRANCATG.jcl`, `app/jcl/DISCGRP.jcl`, `app/jcl/TCATBALF.jcl`, `app/jcl/TRANFILE.jcl` → `DataInitializationJobConfig` (composite 9-step Spring Batch job loading ASCII fixtures)
- `app/jcl/CBADMCDJ.jcl` → step within `DataInitializationJobConfig`
- `app/jcl/PRTCATBL.jcl` → `CategoryBalanceReportJobConfig`
- `app/jcl/TRANBKP.jcl` → `TransactionBackupJobConfig` (PostgreSQL `pg_dump` via tasklet)
- `app/jcl/TRANREPT.jcl` → `TransactionReportJobConfig` (absorbs `app/proc/TRANREPT.prc`)
- `app/jcl/DALYREJS.jcl`, `app/jcl/DEFCUST.jcl`, `app/jcl/DEFGDGB.jcl`, `app/jcl/TRANIDX.jcl`, `app/jcl/CLOSEFIL.jcl`, `app/jcl/OPENFIL.jcl` → No-op (GDG/VSAM-define semantics replaced by Flyway and JPA `@Index`; documented as such but no Java artifact emitted beyond a registry comment)
- `app/jcl/READACCT.jcl`, `app/jcl/READCARD.jcl`, `app/jcl/READCUST.jcl`, `app/jcl/READXREF.jcl` → Diagnostic file-read jobs; mapped to corresponding `*FileReadJobConfig` beans (e.g., `AccountFileReadJobConfig`)
- `app/jcl/REPTFILE.jcl` → `ReportFileJobConfig`

**Copybooks (transformed into JPA entities, DTOs, or utility constants):**

- Record-defining copybooks → `@Entity` classes:
    - `app/cpy/CVACT01Y.cpy` (300 bytes) → `Account.java`
    - `app/cpy/CVACT02Y.cpy` (150 bytes) → `Card.java`
    - `app/cpy/CVACT03Y.cpy` (50 bytes) → `CardXref.java`
    - `app/cpy/CVCUS01Y.cpy` (500 bytes) → `Customer.java`
    - `app/cpy/CVTRA01Y.cpy` (50 bytes) → `TransactionCategoryBalance.java` + `TransactionCategoryBalanceId.java`
    - `app/cpy/CVTRA02Y.cpy` (50 bytes) → `DisclosureGroup.java` + `DisclosureGroupId.java`
    - `app/cpy/CVTRA03Y.cpy` (60 bytes) → `TransactionType.java`
    - `app/cpy/CVTRA04Y.cpy` (60 bytes) → `TransactionCategory.java` + `TransactionCategoryId.java`
    - `app/cpy/CVTRA05Y.cpy` (350 bytes) → `Transaction.java`
    - `app/cpy/CVTRA06Y.cpy` (350 bytes) → `DailyTransaction.java` + `RejectedTransaction.java`
    - `app/cpy/CVTRA07Y.cpy` → Report DTO classes
    - `app/cpy/CSUSR01Y.cpy` (80 bytes) → `User.java` (implements `UserDetails`)
    - `app/cpy/CUSTREC.cpy` → REFERENCE only (alternate customer layout)
- Communication / session copybooks:
    - `app/cpy/COCOM01Y.cpy` (CARDDEMO-COMMAREA) → `AuthenticationDto` + `SessionContext` concepts (decomposed into Spring Security context + DTO fields)
- Menu / message / utility copybooks → constants and DTO fields:
    - `app/cpy/COMEN02Y.cpy`, `app/cpy/COADM02Y.cpy` → `MenuOption` records / static menu config
    - `app/cpy/COSTM01.CPY` → statement template constants
    - `app/cpy/COTTL01Y.cpy` → screen title constants
    - `app/cpy/CSDAT01Y.cpy`, `app/cpy/CSUTLDWY.cpy`, `app/cpy/CSUTLDPY.cpy` → `DateConversionUtil` helpers
    - `app/cpy/CSMSG01Y.cpy`, `app/cpy/CSMSG02Y.cpy` → message constants / `messages.properties`
    - `app/cpy/CSSTRPFY.cpy`, `app/cpy/CSSETATY.cpy`, `app/cpy/CSLKPCDY.cpy` → REFERENCE only (3270-specific)
    - `app/cpy/CVCRD01Y.cpy` → card work area constants
    - `app/cpy/UNUSED1Y.cpy` → REFERENCE only

**BMS symbolic copybooks (REFERENCE-only for DTO field shapes):**

- `app/cpy-bms/COSGN00.CPY`, `app/cpy-bms/COMEN01.CPY`, `app/cpy-bms/COADM01.CPY`, `app/cpy-bms/COACTVW.CPY`, `app/cpy-bms/COACTUP.CPY`, `app/cpy-bms/COCRDLI.CPY`, `app/cpy-bms/COCRDSL.CPY`, `app/cpy-bms/COCRDUP.CPY`, `app/cpy-bms/COTRN00.CPY`, `app/cpy-bms/COTRN01.CPY`, `app/cpy-bms/COTRN02.CPY`, `app/cpy-bms/COBIL00.CPY`, `app/cpy-bms/CORPT00.CPY`, `app/cpy-bms/COUSR00.CPY`, `app/cpy-bms/COUSR01.CPY`, `app/cpy-bms/COUSR02.CPY`, `app/cpy-bms/COUSR03.CPY` — used to derive DTO field names and lengths; not transformed into runtime artifacts.

**ASCII fixture data (read by `DataInitializationJobConfig`):**

- `app/data/ASCII/acctdata.txt` (50 accounts), `app/data/ASCII/carddata.txt` (50 cards), `app/data/ASCII/cardxref.txt` (50 cross-references), `app/data/ASCII/custdata.txt` (50 customers), `app/data/ASCII/dailytran.txt` (sample daily feed), `app/data/ASCII/discgrp.txt` (51 disclosure groups), `app/data/ASCII/tcatbal.txt` (100 transaction category balances), `app/data/ASCII/trancatg.txt` (18 categories), `app/data/ASCII/trantype.txt` (7 types)

**CICS resource definitions and VSAM catalog (REFERENCE-only):**

- `app/csd/CARDDEMO.CSD` — used to derive complete transaction-ID-to-program-to-mapset routing for 15 CICS transactions [app/csd/CARDDEMO.CSD:L1-L99]
- `app/catlg/LISTCAT.txt` — used to derive primary key positions, AIX configurations, record counts for index design

**Documentation (UPDATE):**

- `README.md` — add Spring Boot 3.2 setup, Maven build instructions, PostgreSQL setup, Flyway migration explanation, sample curl invocations, Actuator endpoint descriptions; preserve original mainframe documentation section unchanged

**New files (CREATE):**

- `pom.xml` (Maven build configuration)
- `src/main/resources/application.yml` + `application-dev.yml` + `application-prod.yml`
- `src/main/resources/db/migration/V1__schema.sql` through `V5__seed_master_data.sql` (Flyway migrations)
- `src/main/resources/templates/statement-template.html` (preserved HTML from `CBSTM03A` `5100-WRITE-HTML-HEADER`)
- All Java sources under `src/main/java/com/carddemo/` (~150 files covering entity, repository, service, controller, batch, security, config, dto, mapper, exception, util, validation packages — enumerated exhaustively in §0.4)
- All test sources under `src/test/java/com/carddemo/` (controller MockMvc tests, service unit tests, batch `JobLauncherTestUtils` tests, Testcontainers integration tests, parity tests for the three critical batch programs)
- `docs/migration-mapping.md` (companion document elaborating per-file mapping rationale)

**Wildcard scope patterns (trailing only, never leading):**

- `src/main/java/com/carddemo/entity/*.java` — all 15 JPA entities
- `src/main/java/com/carddemo/repository/*.java` — all 12 Spring Data JPA repositories
- `src/main/java/com/carddemo/controller/*.java` — all 9 REST controllers + `advice/GlobalExceptionHandler.java`
- `src/main/java/com/carddemo/service/*.java` — all 10 service classes
- `src/main/java/com/carddemo/batch/*.java` — all batch `Job` and `Step` configurations, processors, readers, writers
- `src/main/java/com/carddemo/dto/**/*.java` — all DTOs nested by functional domain
- `src/main/java/com/carddemo/mapper/*.java` — all entity-to-DTO mappers
- `src/main/java/com/carddemo/exception/*.java` — all exception classes
- `src/main/java/com/carddemo/security/*.java` — Spring Security configuration
- `src/main/java/com/carddemo/config/*.java` — Spring `@Configuration` classes
- `src/main/java/com/carddemo/util/*.java` — utility classes (date conversion, BigDecimal helpers, transaction ID generator, fixed-width parser)
- `src/main/java/com/carddemo/validation/*.java` — validator classes preserving CBTRN02C validation chain
- `src/main/resources/db/migration/V*.sql` — all Flyway migration scripts
- `src/test/java/com/carddemo/**/*.java` — all tests
- `app/cbl/*.cbl` — REFERENCE only (preserved unchanged)
- `app/jcl/*.jcl` — REFERENCE only (preserved unchanged)
- `app/cpy/*.cpy` — REFERENCE only (preserved unchanged)
- `app/cpy-bms/*.CPY` — REFERENCE only (preserved unchanged)
- `app/bms/*.bms` — REFERENCE only (preserved unchanged)
- `app/data/ASCII/*.txt` — input to `DataInitializationJobConfig` (preserved unchanged)

### 0.2.2 Explicitly Out of Scope

The following items are explicitly excluded from this refactor per the prompt's constraints:

- **3270 BMS terminal UI** — All `app/bms/*.bms` mapset sources (17 files: COCRDLI, COACTUP, COACTVW, COADM01, COBIL00, COCRDSL, COCRDUP, COMEN01, CORPT00, COSGN00, COTRN00, COTRN01, COTRN02, COUSR00-03) [app/bms/:directory]. The prompt mandates REST-only backend with no replacement frontend. BMS sources remain as REFERENCE.
- **BMS symbolic copybooks as transformation targets** — `app/cpy-bms/*.CPY` are REFERENCE only; their field-list information feeds DTO design but the files themselves are not converted into runtime artifacts.
- **MQ Series, IMS, DB2 optional modules** — The original CardDemo includes optional integration modules for IBM MQ, IMS, and DB2. The prompt explicitly excludes these. PostgreSQL replaces VSAM only.
- **AWS Mainframe Modernization runtime artifacts** — `samples/m2/mf/` (Micro Focus runtime) and `samples/m2/unikix/` (Unikix runtime) are AWS M2 deployment variants superseded by the Spring Boot deployment model.
- **Compile JCLs** — `samples/jcl/BATCMP.jcl` (batch compile), `samples/jcl/BMSCMP.jcl` (BMS compile), `samples/jcl/CICCMP.jcl` (CICS compile) are replaced by Maven goals (`mvn compile`, `mvn package`).
- **Build procs** — `samples/proc/BUILDBAT.prc`, `samples/proc/BUILDBMS.prc`, `samples/proc/BUILDONL.prc` are replaced by Maven.
- **JCL procedures** — `app/proc/REPROC.prc` (REPRO procedure — replaced by Spring Batch step) and `app/proc/TRANREPT.prc` (absorbed into `TransactionReportJobConfig` Step bean) are REFERENCE.
- **IDCAMS control cards** — `app/ctl/REPROCT.ctl` (REPRO control card) is replaced by JPA `save()` operations in batch readers.
- **Microservices decomposition** — Single Spring Boot monolith per the prompt's "make as few architectural decisions as possible" constraint.
- **Cloud-native services** — No AWS S3, no SQS, no Lambda, no Cognito (despite Tech Spec §6.4 mentioning future-state recommendations). Local PostgreSQL + filesystem only.
- **GDG backup deliverables** — While `TransactionBackupJobConfig` wraps `pg_dump`, the operational backup schedule itself (cron, k8s CronJob) is not delivered as code; it is documented as an operational pattern.
- **CICS-specific runtime constants** — `DFHAID` (AID-key constants like `DFHENTER`, `DFHPF3`), `DFHBMSCA` (BMS attribute constants), and CICS communication primitives (`EXEC CICS SEND MAP`, `RECEIVE MAP`, `XCTL`, `RETURN TRANSID`, `WRITEQ TD`) have no direct REST equivalents; their semantics are captured by HTTP method + status code + response body.
- **Diagrams and project metadata** — `diagrams/Admin-Menu.png`, `diagrams/Application-Flow-Admin.png`, `diagrams/Application-Flow-User.png`, `diagrams/CARDDEMO-DataModel.drawio`, `diagrams/Main-Menu.png`, `diagrams/Signon-Screen.png`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `LICENSE`, `NOTICE` — preserved unchanged.
- **Original COBOL/JCL/copybook/BMS/CSD sources** — Preserved in-place as REFERENCE for downstream regression verification of business-logic parity. Not deleted; not modified.
- **Production-grade encryption at rest / in transit** — Tech Spec §6.4 documents this as a demonstration-grade application with PII in plaintext and no TLS. While Spring Security supports HTTPS via Tomcat connectors, full PCI-DSS / SOC 2 / GLBA / GDPR compliance work (KMS-encrypted columns, full audit logging to an external SIEM, dedicated key management) is out of scope as a deliverable; method-level `@PreAuthorize`, BCrypt password hashing, JPA auditing entity listener, and exception handler are the minimum security improvements explicitly required.
- **CI/CD pipelines** — No existing `.github/workflows/`, `.gitlab-ci.yml`, `Jenkinsfile`, or similar pipeline files; none are created by this refactor.
- **Frontend application** — No SPA, no Thymeleaf views beyond the statement template (which renders to a server-emitted file, not a browser page).

**Rule-mandated files:** The user-provided rules list is empty (no implementation rules supplied). No additional rule-mandated files are introduced beyond the prompt's explicit and implicit requirements.

## 0.3 Target Design

### 0.3.1 Refactored Project Structure

The target repository layout below comprehensively enumerates every directory and file produced by this refactor. The original `app/` tree is preserved unchanged as REFERENCE; the new `src/`, `pom.xml`, and `docs/migration-mapping.md` constitute the deliverable Spring Boot project.

```
carddemo/
├── pom.xml                                                       (CREATE: Maven, Spring Boot 3.2.12 parent)
├── README.md                                                     (UPDATE: add Spring Boot setup section)
├── app/                                                          (PRESERVED unchanged — REFERENCE)
│   ├── bms/                                                      (REFERENCE — 17 BMS mapset sources)
│   ├── catlg/LISTCAT.txt                                         (REFERENCE — VSAM catalog snapshot)
│   ├── cbl/                                                      (REFERENCE — 28 COBOL programs)
│   ├── cpy/                                                      (REFERENCE — 28 copybooks)
│   ├── cpy-bms/                                                  (REFERENCE — 17 BMS symbolic copybooks)
│   ├── csd/CARDDEMO.CSD                                          (REFERENCE — CICS resource definitions)
│   ├── ctl/REPROCT.ctl                                           (REFERENCE — IDCAMS control card)
│   ├── data/ASCII/                                               (INPUT — 9 fixed-width seed files)
│   ├── jcl/                                                      (REFERENCE — 29 JCL jobs)
│   └── proc/                                                     (REFERENCE — 2 JCL procs)
├── docs/
│   └── migration-mapping.md                                      (CREATE: COBOL→Java mapping reference)
└── src/
    ├── main/
    │   ├── java/com/carddemo/
    │   │   ├── CardDemoApplication.java                          (CREATE: @SpringBootApplication entry)
    │   │   ├── controller/
    │   │   │   ├── AuthController.java                           (CREATE — POST /api/auth/login, /api/auth/logout)
    │   │   │   ├── AccountController.java                        (CREATE — GET/PUT /api/accounts/{acctId})
    │   │   │   ├── CardController.java                           (CREATE — GET/PUT /api/cards, /api/accounts/{acctId}/cards)
    │   │   │   ├── CustomerController.java                       (CREATE — GET /api/customers/{custId})
    │   │   │   ├── TransactionController.java                    (CREATE — GET/POST /api/transactions)
    │   │   │   ├── BillPaymentController.java                    (CREATE — POST /api/accounts/{acctId}/payments)
    │   │   │   ├── ReportController.java                         (CREATE — POST /api/reports)
    │   │   │   ├── UserController.java                           (CREATE — CRUD /api/admin/users with @PreAuthorize)
    │   │   │   ├── MenuController.java                           (CREATE — GET /api/menu role-filtered)
    │   │   │   ├── BatchAdminController.java                     (CREATE — POST /api/admin/jobs/{jobName}/launch)
    │   │   │   └── advice/
    │   │   │       └── GlobalExceptionHandler.java               (CREATE — @ControllerAdvice)
    │   │   ├── service/
    │   │   │   ├── AuthService.java                              (CREATE)
    │   │   │   ├── AccountService.java                           (CREATE)
    │   │   │   ├── CardService.java                              (CREATE)
    │   │   │   ├── CustomerService.java                          (CREATE)
    │   │   │   ├── TransactionService.java                       (CREATE — online transaction creation)
    │   │   │   ├── BillPaymentService.java                       (CREATE)
    │   │   │   ├── ReportService.java                            (CREATE — launches batch jobs)
    │   │   │   ├── UserService.java                              (CREATE — BCrypt encoding)
    │   │   │   ├── DateConversionService.java                    (CREATE — wraps DateConversionUtil)
    │   │   │   └── StatementService.java                         (CREATE — used by batch + online)
    │   │   ├── repository/
    │   │   │   ├── AccountRepository.java                        (CREATE)
    │   │   │   ├── CardRepository.java                           (CREATE — findByAccountId)
    │   │   │   ├── CardXrefRepository.java                       (CREATE — findByAccountId, findByCardNumber)
    │   │   │   ├── CustomerRepository.java                       (CREATE)
    │   │   │   ├── TransactionRepository.java                    (CREATE — findByOrigTimestampBetween)
    │   │   │   ├── DailyTransactionRepository.java               (CREATE — staging)
    │   │   │   ├── RejectedTransactionRepository.java            (CREATE — DALYREJS)
    │   │   │   ├── TransactionCategoryBalanceRepository.java     (CREATE — composite key upsert)
    │   │   │   ├── DisclosureGroupRepository.java                (CREATE — DEFAULT fallback)
    │   │   │   ├── TransactionTypeRepository.java                (CREATE)
    │   │   │   ├── TransactionCategoryRepository.java            (CREATE — composite key)
    │   │   │   └── UserRepository.java                           (CREATE)
    │   │   ├── entity/
    │   │   │   ├── Account.java                                  (CREATE — CVACT01Y 300 bytes)
    │   │   │   ├── Card.java                                     (CREATE — CVACT02Y 150 bytes, @Index account_id)
    │   │   │   ├── CardXref.java                                 (CREATE — CVACT03Y 50 bytes, @Index account_id)
    │   │   │   ├── Customer.java                                 (CREATE — CVCUS01Y 500 bytes)
    │   │   │   ├── Transaction.java                              (CREATE — CVTRA05Y 350 bytes, @Index orig_ts)
    │   │   │   ├── DailyTransaction.java                         (CREATE — CVTRA06Y 350 bytes)
    │   │   │   ├── RejectedTransaction.java                      (CREATE — DALYREJS layout)
    │   │   │   ├── TransactionCategoryBalance.java               (CREATE — @EmbeddedId)
    │   │   │   ├── TransactionCategoryBalanceId.java             (CREATE — @Embeddable)
    │   │   │   ├── DisclosureGroup.java                          (CREATE — @EmbeddedId)
    │   │   │   ├── DisclosureGroupId.java                        (CREATE — @Embeddable)
    │   │   │   ├── TransactionType.java                          (CREATE)
    │   │   │   ├── TransactionCategory.java                      (CREATE — @EmbeddedId)
    │   │   │   ├── TransactionCategoryId.java                    (CREATE — @Embeddable)
    │   │   │   └── User.java                                     (CREATE — implements UserDetails, BCrypt hash)
    │   │   ├── batch/
    │   │   │   ├── BatchConfig.java                              (CREATE — @EnableBatchProcessing)
    │   │   │   ├── TransactionPostingJobConfig.java              (CREATE — POSTTRAN → CBTRN02C)
    │   │   │   ├── TransactionPostingProcessor.java              (CREATE — codes 100/101/102/103)
    │   │   │   ├── TransactionCategoryBalanceUpsertWriter.java   (CREATE — CBTRN02C L467-501)
    │   │   │   ├── AccountBalanceUpdater.java                    (CREATE — CBTRN02C L545-560)
    │   │   │   ├── InterestCalculationJobConfig.java             (CREATE — INTCALC → CBACT04C)
    │   │   │   ├── InterestCalculationTasklet.java               (CREATE — preserves L462-470 formula)
    │   │   │   ├── TransactionConsolidationJobConfig.java        (CREATE — COMBTRAN)
    │   │   │   ├── StatementGenerationJobConfig.java             (CREATE — CREASTMT → CBSTM03A)
    │   │   │   ├── StatementGenerationTasklet.java               (CREATE — orchestrates statement build)
    │   │   │   ├── StatementHtmlBuilder.java                     (CREATE — preserves L506-555 HTML)
    │   │   │   ├── StatementIoSubroutine.java                    (CREATE — CBSTM03B helper)
    │   │   │   ├── DataInitializationJobConfig.java              (CREATE — 9-step composite seed load)
    │   │   │   ├── UserSeedingJobConfig.java                     (CREATE — DUSRSECJ BCrypt rehash)
    │   │   │   ├── TransactionBackupJobConfig.java               (CREATE — TRANBKP via pg_dump tasklet)
    │   │   │   ├── TransactionReportJobConfig.java               (CREATE — TRANREPT)
    │   │   │   ├── CategoryBalanceReportJobConfig.java           (CREATE — PRTCATBL)
    │   │   │   ├── AccountFileReadJobConfig.java                 (CREATE — CBACT01C/READACCT diagnostic)
    │   │   │   ├── CardFileReadJobConfig.java                    (CREATE — CBACT02C/READCARD diagnostic)
    │   │   │   ├── XrefFileReadJobConfig.java                    (CREATE — CBACT03C/READXREF diagnostic)
    │   │   │   ├── CustomerFileReadJobConfig.java                (CREATE — CBCUS01C/READCUST diagnostic)
    │   │   │   ├── DailyTransactionReadJobConfig.java            (CREATE — CBTRN01C)
    │   │   │   ├── TransactionReadJobConfig.java                 (CREATE — CBTRN03C)
    │   │   │   └── reader/
    │   │   │       └── AsciiFixedWidthItemReader.java            (CREATE — fixed-width parser for app/data/ASCII)
    │   │   ├── security/
    │   │   │   ├── SecurityConfig.java                           (CREATE — SecurityFilterChain, BCryptPasswordEncoder)
    │   │   │   ├── UserDetailsServiceImpl.java                   (CREATE — replaces COSGN00C VSAM read)
    │   │   │   ├── JwtAuthenticationFilter.java                  (CREATE — bearer-token filter)
    │   │   │   ├── MethodSecurityConfig.java                     (CREATE — @EnableMethodSecurity)
    │   │   │   └── CustomAuthorityMapper.java                    (CREATE — 'A'→ROLE_ADMIN, 'U'→ROLE_USER)
    │   │   ├── config/
    │   │   │   ├── DataSourceConfig.java                         (CREATE — PostgreSQL DataSource)
    │   │   │   ├── JpaConfig.java                                (CREATE — @EnableJpaRepositories)
    │   │   │   ├── WebConfig.java                                (CREATE — CORS, Jackson)
    │   │   │   └── OpenApiConfig.java                            (CREATE — springdoc-openapi)
    │   │   ├── dto/
    │   │   │   ├── auth/LoginRequest.java, LoginResponse.java    (CREATE)
    │   │   │   ├── account/AccountDto.java                       (CREATE)
    │   │   │   ├── card/CardDto.java, CardListResponse.java      (CREATE)
    │   │   │   ├── customer/CustomerDto.java                     (CREATE — masked SSN)
    │   │   │   ├── transaction/TransactionDto.java,              (CREATE)
    │   │   │   │             TransactionRequest.java,
    │   │   │   │             TransactionListResponse.java
    │   │   │   ├── billpayment/BillPaymentRequest.java,          (CREATE)
    │   │   │   │               BillPaymentResponse.java
    │   │   │   ├── report/ReportRequest.java                     (CREATE)
    │   │   │   ├── user/UserDto.java, UserCreateRequest.java     (CREATE)
    │   │   │   └── menu/MenuOption.java, MenuResponse.java       (CREATE)
    │   │   ├── mapper/
    │   │   │   ├── AccountMapper.java                            (CREATE — BigDecimal scale handling)
    │   │   │   ├── CardMapper.java                               (CREATE)
    │   │   │   ├── TransactionMapper.java                        (CREATE)
    │   │   │   ├── CustomerMapper.java                           (CREATE — SSN masking on outbound)
    │   │   │   └── UserMapper.java                               (CREATE)
    │   │   ├── exception/
    │   │   │   ├── AccountNotFoundException.java                 (CREATE — code 101)
    │   │   │   ├── InvalidCardException.java                     (CREATE — code 100)
    │   │   │   ├── OverlimitException.java                       (CREATE — code 102)
    │   │   │   ├── ExpiredAccountException.java                  (CREATE — code 103)
    │   │   │   ├── TransactionValidationException.java           (CREATE — parent)
    │   │   │   ├── DiscloseGroupNotFoundException.java           (CREATE — for DEFAULT fallback miss)
    │   │   │   └── ErrorResponse.java                            (CREATE — standard error payload)
    │   │   ├── util/
    │   │   │   ├── DateConversionUtil.java                       (CREATE — replaces CSUTLDTC)
    │   │   │   ├── TransactionIdGenerator.java                   (CREATE — PARM-DATE + 6-char suffix)
    │   │   │   ├── BigDecimalUtil.java                           (CREATE — scale=2, HALF_UP helpers)
    │   │   │   └── FixedWidthRecordParser.java                   (CREATE — parses ASCII fixtures)
    │   │   └── validation/
    │   │       ├── TransactionValidator.java                     (CREATE — codes 100/101/102/103)
    │   │       └── AccountValidator.java                         (CREATE — credit-limit, expiration)
    │   └── resources/
    │       ├── application.yml                                   (CREATE — base config)
    │       ├── application-dev.yml                               (CREATE — local PostgreSQL)
    │       ├── application-prod.yml                              (CREATE — production)
    │       ├── db/migration/
    │       │   ├── V1__schema.sql                                (CREATE — all tables)
    │       │   ├── V2__indexes.sql                               (CREATE — secondary indexes)
    │       │   ├── V3__seed_reference_data.sql                   (CREATE — types, categories, disclosure groups)
    │       │   ├── V4__seed_users.sql                            (CREATE — 10 default users, BCrypt)
    │       │   └── V5__seed_master_data.sql                      (CREATE — customers, accounts, cards, xrefs)
    │       └── templates/
    │           └── statement-template.html                       (CREATE — CBSTM03A HTML literal)
    └── test/
        ├── java/com/carddemo/
        │   ├── controller/                                       (CREATE — MockMvc tests)
        │   ├── service/                                          (CREATE — unit tests)
        │   ├── batch/                                            (CREATE — JobLauncherTestUtils)
        │   ├── integration/                                      (CREATE — Testcontainers PostgreSQL)
        │   └── businesslogic/
        │       ├── InterestCalculationParityTest.java            (CREATE — verifies CBACT04C parity)
        │       ├── TransactionPostingParityTest.java             (CREATE — verifies codes 100/101/102/103)
        │       └── StatementGenerationParityTest.java            (CREATE — byte-for-byte HTML parity)
        └── resources/
            ├── application-test.yml                              (CREATE — Testcontainers config)
            └── fixtures/                                         (CREATE — CSV mirrors of ASCII files)
%% diagram-style file tree; intentionally textual, not executed code
```

### 0.3.2 Web Search Research Findings

Verification performed for Spring Boot dependency versions, framework alignment, and Java 17 baseline:

- **Spring Boot 3.2 line:** The prompt specifies Spring Boot 3.2; the final patch release in this branch is **3.2.12** (released 2024-11-21), which is used as the parent POM. This release ships Spring Framework 6.1.x, Spring Security 6.2.x, Spring Batch 5.1.x, and Hibernate ORM 6.4.x — all resolved transitively by the Spring Boot BOM.
- **Java 17 baseline:** Spring Boot 3.x mandates Java 17 minimum (Jakarta EE 10 namespace `jakarta.*` instead of `javax.*`). Eclipse Temurin 17 is the recommended distribution; security patches available through October 2027.
- **PostgreSQL 15 driver:** `org.postgresql:postgresql` version 42.7.x (JDBC 4.2; managed by Spring Boot BOM).
- **Flyway compatibility:** Flyway 9.22.x is bundled with Spring Boot 3.2 BOM and supports PostgreSQL 15 natively without a separate dialect module (Flyway 10+ split dialects into separate jars; the BOM-managed 9.x version is used).
- **Best practices for COBOL-to-Java refactoring:**
    - Preserve packed-decimal arithmetic with `java.math.BigDecimal` and explicit `RoundingMode.HALF_UP` (mirrors COBOL `ROUNDED` behavior).
    - Replace VSAM AIX alternate indexes with PostgreSQL B-tree secondary indexes via `@Index`.
    - Implement composite primary keys via `@Embeddable` + `@EmbeddedId` (vs. legacy `@IdClass` pattern).
    - Use Spring Batch chunk-oriented processing for sequential file-equivalent workloads; tasklets only for single-operation steps.
    - Stage fixed-width input files into staging tables (`daily_transactions`) before transactional posting; this enables checkpoint/restart across the entire `POSTTRAN` job.
    - Replace `EXEC CICS SYNCPOINT` boundaries with `@Transactional` method scopes; default isolation `READ_COMMITTED` matches CICS file-level concurrency expectations.
- **Spring Batch 5.x considerations:** `@EnableBatchProcessing` is automatic with Spring Boot 3; explicit `JobBuilderFactory` and `StepBuilderFactory` deprecated in favor of `JobBuilder` and `StepBuilder` constructors. Job parameters must be non-identifying for restart compatibility.
- **Spring Security 6.x considerations:** `WebSecurityConfigurerAdapter` removed; replaced by `SecurityFilterChain` bean. Method security configured via `@EnableMethodSecurity(prePostEnabled = true)` (replaces deprecated `@EnableGlobalMethodSecurity`).

### 0.3.3 Design Pattern Applications

| # | Pattern | Application | Rationale |
|---|---------|-------------|-----------|
| 1 | Repository Pattern | All persistence access via Spring Data JPA repositories extending `JpaRepository` or `CrudRepository` | Replaces direct `EXEC CICS READ/WRITE/REWRITE/DELETE`; method-name conventions (e.g., `findByAccountId`) replace VSAM AIX path access |
| 2 | Service Layer | `@Service` beans encapsulate business logic; `@Transactional` brackets multi-entity operations | Replaces COBOL program main paragraphs; preserves CICS UOW / `SYNCPOINT` semantics |
| 3 | Constructor Dependency Injection | All beans use constructor injection via Lombok `@RequiredArgsConstructor` | Immutable dependencies, testability; eliminates `@Autowired` field injection |
| 4 | DTO Pattern | Request/response DTOs separate from JPA entities | Prevents leaking sensitive fields (SSN, password hash, internal IDs); enables JSON shape control |
| 5 | Mapper Pattern | Explicit hand-coded mappers in `com.carddemo.mapper` (no MapStruct dependency required) | Keeps `BigDecimal` scale handling and SSN masking explicit and auditable |
| 6 | Strategy Pattern | `TransactionValidator` chain — `XrefLookupValidator → AccountLookupValidator → CreditLimitValidator → ExpirationValidator` | Mirrors CBTRN02C `1500-A-LOOKUP-XREF → 1500-B-LOOKUP-ACCT` chain [app/cbl/CBTRN02C.cbl:L380-L422] preserving validation codes 100/101/102/103 ordering |
| 7 | Chunk/Tasklet Pattern | Spring Batch chunk-oriented steps for sequential records (`CBTRN02C`-style); tasklets for single-operation steps | Maps directly to COBOL `PERFORM UNTIL EOF` loops; chunk size 100 default, configurable per Step |
| 8 | Factory Pattern | `TransactionIdGenerator` produces 16-char IDs as `PARM-DATE (10) + suffix (6)` [app/cbl/CBACT04C.cbl:L473-L500] | Centralizes generation; supports batch (atomic counter) and online (DB sequence) modes |
| 9 | Template Method Pattern | `AbstractBatchJobConfig` base class with shared step builders | DRY across ~11 Job configurations |
| 10 | Filter Chain Pattern | Spring Security `SecurityFilterChain` with `JwtAuthenticationFilter` | Replaces COSGN00C signon + COMMAREA validation [app/cbl/COSGN00C.cbl:L211-L257] |
| 11 | Composite Key Pattern | `@EmbeddedId` for `TransactionCategoryBalance`, `DisclosureGroup`, `TransactionCategory` | Mirrors COBOL multi-field record keys [app/cpy/CVTRA01Y.cpy:L4-L10, app/cpy/CVTRA02Y.cpy:L4-L10, app/cpy/CVTRA04Y.cpy:L4-L9] |
| 12 | Optimistic Locking | `@Version` field on `Account`, `Card`, `Customer`, `Transaction` | Replaces VSAM exclusive `READ UPDATE` / `REWRITE` semantics with safer non-blocking concurrency |

### 0.3.4 User Interface Design

**Not applicable.** The prompt mandates REST-only backend migration; the original 3270 BMS terminal UI is explicitly out of scope. The REST API exposes JSON endpoints documented automatically via springdoc-openapi (Swagger UI available at `/swagger-ui.html`). Any future frontend (web SPA, mobile, or thick client) consumes the REST contracts directly. The only HTML produced by the application is the customer statement document emitted by `StatementGenerationJobConfig` to a file (preserving `CBSTM03A` `5100-WRITE-HTML-HEADER` byte structure) — it is not served by an HTTP endpoint as a UI page.

## 0.4 Transformation Mapping

### 0.4.1 File-by-File Transformation Plan

The following exhaustive map enumerates every target file with its transformation mode (UPDATE / CREATE / REFERENCE) and source file. Every target file maps to a specific source file unless no equivalent source exists.

**Mode legend:**
- **CREATE** — New file produced by this refactor
- **UPDATE** — Existing repository file modified by this refactor
- **REFERENCE** — Existing file used as an exemplar for pattern, structure, or data; not modified

#### 0.4.1.1 CICS Online Programs → REST Controllers + Services

| Target File | Mode | Source File | Key Changes |
|---|---|---|---|
| `src/main/java/com/carddemo/controller/AuthController.java` | CREATE | `app/cbl/COSGN00C.cbl` | Spring Security signon endpoint `POST /api/auth/login`; replaces COBOL plaintext compare [app/cbl/COSGN00C.cbl:L211-L257] with `BCryptPasswordEncoder.matches`; returns JWT bearer token with `userType` claim |
| `src/main/java/com/carddemo/service/AuthService.java` | CREATE | `app/cbl/COSGN00C.cbl` | Loads `User` entity via `UserRepository`; constructs `Authentication` object with `ROLE_ADMIN`/`ROLE_USER` from `userType` |
| `src/main/java/com/carddemo/controller/MenuController.java` | CREATE | `app/cbl/COMEN01C.cbl` + `app/cbl/COADM01C.cbl` | `GET /api/menu` returns role-filtered menu option list; routes ADMIN to admin options, USER to user options |
| `src/main/java/com/carddemo/controller/AccountController.java` | CREATE | `app/cbl/COACTVWC.cbl` + `app/cbl/COACTUPC.cbl` | `GET /api/accounts/{acctId}` (view) and `PUT /api/accounts/{acctId}` (update) |
| `src/main/java/com/carddemo/service/AccountService.java` | CREATE | `app/cbl/COACTVWC.cbl` + `app/cbl/COACTUPC.cbl` | Account CRUD; preserves field-level validation (credit limit ≥ 0, expiration date format) |
| `src/main/java/com/carddemo/controller/CardController.java` | CREATE | `app/cbl/COCRDLIC.cbl` + `app/cbl/COCRDSLC.cbl` + `app/cbl/COCRDUPC.cbl` | `GET /api/accounts/{acctId}/cards` (list, replaces CARDDATA.AIX browse), `GET /api/cards/{cardNum}` (view), `PUT /api/cards/{cardNum}` (update); pagination via Spring `Pageable` replaces PF7/PF8 cursor |
| `src/main/java/com/carddemo/service/CardService.java` | CREATE | `app/cbl/COCRDLIC.cbl` + `app/cbl/COCRDSLC.cbl` + `app/cbl/COCRDUPC.cbl` | `findByAccountId(Long acctId, Pageable pageable)` replaces VSAM `STARTBR DATASET('CARDAIX')` |
| `src/main/java/com/carddemo/controller/CustomerController.java` | CREATE | `app/cbl/COACTVWC.cbl` (customer-info portion) | `GET /api/customers/{custId}` (view); SSN masked in response |
| `src/main/java/com/carddemo/service/CustomerService.java` | CREATE | `app/cbl/COACTVWC.cbl` | Customer read; integrates `CustomerMapper` SSN masking |
| `src/main/java/com/carddemo/controller/TransactionController.java` | CREATE | `app/cbl/COTRN00C.cbl` + `app/cbl/COTRN01C.cbl` + `app/cbl/COTRN02C.cbl` | `GET /api/transactions` (list, paginated), `GET /api/transactions/{tranId}` (view), `POST /api/transactions` (online create) |
| `src/main/java/com/carddemo/service/TransactionService.java` | CREATE | `app/cbl/COTRN02C.cbl` (online add) | Online transaction creation including ID generation via `TransactionIdGenerator`; validation chain mirrors batch `CBTRN02C` codes 100/101/102/103 |
| `src/main/java/com/carddemo/controller/BillPaymentController.java` | CREATE | `app/cbl/COBIL00C.cbl` | `POST /api/accounts/{acctId}/payments` |
| `src/main/java/com/carddemo/service/BillPaymentService.java` | CREATE | `app/cbl/COBIL00C.cbl` | Available credit = `ACCT-CREDIT-LIMIT - ACCT-CURR-BAL`; creates payment transaction; updates balance atomically within `@Transactional` |
| `src/main/java/com/carddemo/controller/ReportController.java` | CREATE | `app/cbl/CORPT00C.cbl` | `POST /api/reports` enqueues report job |
| `src/main/java/com/carddemo/service/ReportService.java` | CREATE | `app/cbl/CORPT00C.cbl` | Replaces `EXEC CICS WRITEQ TD QUEUE('JOBS')` with `JobLauncher.run(jobRegistry.getJob(...), params)` annotated `@Async` |
| `src/main/java/com/carddemo/controller/UserController.java` | CREATE | `app/cbl/COUSR00C.cbl` + `app/cbl/COUSR01C.cbl` + `app/cbl/COUSR02C.cbl` + `app/cbl/COUSR03C.cbl` | All endpoints `@PreAuthorize("hasRole('ADMIN')")` — **CLOSES THE PRE-EXISTING PROGRAMMATIC AUTH GAP** documented in Tech Spec §6.4 |
| `src/main/java/com/carddemo/service/UserService.java` | CREATE | `app/cbl/COUSR00C.cbl` + `app/cbl/COUSR01C.cbl` + `app/cbl/COUSR02C.cbl` + `app/cbl/COUSR03C.cbl` | CRUD with `BCryptPasswordEncoder.encode(rawPassword)` on create/update |
| `src/main/java/com/carddemo/controller/BatchAdminController.java` | CREATE | (n/a — operational endpoint) | `POST /api/admin/jobs/{jobName}/launch` invokes any registered `Job` by name; `@PreAuthorize("hasRole('ADMIN')")` |
| `src/main/java/com/carddemo/controller/advice/GlobalExceptionHandler.java` | CREATE | (n/a — cross-cutting) | `@ControllerAdvice` mapping `AccountNotFoundException` → 404, `InvalidCardException` → 400, `OverlimitException` → 422, `ExpiredAccountException` → 422, `OptimisticLockException` → 409, `AccessDeniedException` → 403 |

#### 0.4.1.2 Batch COBOL Programs → Spring Batch Jobs

| Target File | Mode | Source File | Key Changes |
|---|---|---|---|
| `src/main/java/com/carddemo/batch/InterestCalculationJobConfig.java` | CREATE | `app/cbl/CBACT04C.cbl` | Spring Batch `Job` bean; 3 chunk-oriented steps (compute by category → update account → emit interest transactions) |
| `src/main/java/com/carddemo/batch/InterestCalculationTasklet.java` | CREATE | `app/cbl/CBACT04C.cbl` | **PRESERVES L462-470 formula EXACTLY**: `monthlyInt = tranCatBal.multiply(disIntRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)`; **preserves L436-439 DEFAULT fallback**; **preserves L350-370 1050-UPDATE-ACCOUNT** (ADD `WS-TOTAL-INT` to `ACCT-CURR-BAL`, zero `ACCT-CURR-CYC-CREDIT` and `ACCT-CURR-CYC-DEBIT`, REWRITE) |
| `src/main/java/com/carddemo/batch/TransactionPostingJobConfig.java` | CREATE | `app/cbl/CBTRN02C.cbl` | Spring Batch `Job` bean for POSTTRAN; chunk=100; reader=`DalyTranItemReader`, processor=`TransactionPostingProcessor`, writer=`CompositeItemWriter` |
| `src/main/java/com/carddemo/batch/TransactionPostingProcessor.java` | CREATE | `app/cbl/CBTRN02C.cbl` [L370-L422] | **PRESERVES validation codes EXACTLY**: 100 "INVALID CARD NUMBER FOUND", 101 (account not found), 102 "OVERLIMIT TRANSACTION", 103 "TRANSACTION RECEIVED AFTER ACCT EXPIRATION" |
| `src/main/java/com/carddemo/batch/TransactionCategoryBalanceUpsertWriter.java` | CREATE | `app/cbl/CBTRN02C.cbl` [L467-L501] | **PRESERVES TCATBAL upsert**: read by composite key — `existsById(id) ? UPDATE : INSERT`; ADD `DALYTRAN-AMT` to `TRAN-CAT-BAL` |
| `src/main/java/com/carddemo/batch/AccountBalanceUpdater.java` | CREATE | `app/cbl/CBTRN02C.cbl` [L545-L560] | **PRESERVES sign-based bucket**: amount ≥ 0 → `currCycCredit += amount`; else → `currCycDebit += amount` (note: DEBIT is added as positive per COBOL); `ACCT-CURR-BAL += DALYTRAN-AMT`; REWRITE |
| `src/main/java/com/carddemo/batch/StatementGenerationJobConfig.java` | CREATE | `app/cbl/CBSTM03A.CBL` + `app/jcl/CREASTMT.JCL` | Spring Batch `Job` bean for CREASTMT; 4 chained steps (purge prior output → sort by card+id → load to temp table → generate statements) |
| `src/main/java/com/carddemo/batch/StatementGenerationTasklet.java` | CREATE | `app/cbl/CBSTM03A.CBL` [L316-L342] | MAINLINE port: iterates CardXref → reads Customer + Account → emits statement |
| `src/main/java/com/carddemo/batch/StatementHtmlBuilder.java` | CREATE | `app/cbl/CBSTM03A.CBL` [L506-L555] | **PRESERVES `5100-WRITE-HTML-HEADER` byte-for-byte**: identical bank info, table layout, CSS styling |
| `src/main/java/com/carddemo/batch/StatementIoSubroutine.java` | CREATE | `app/cbl/CBSTM03B.CBL` | Helper class providing centralized open/close/read/write semantics called by `StatementGenerationTasklet` |
| `src/main/java/com/carddemo/batch/TransactionConsolidationJobConfig.java` | CREATE | `app/cbl/CBTRN03C.cbl` + `app/jcl/COMBTRAN.jcl` | SORT step (in-DB `ORDER BY tran_id`) + UPSERT step (merge two input streams into `transactions` table) |
| `src/main/java/com/carddemo/batch/DataInitializationJobConfig.java` | CREATE | `app/jcl/ACCTFILE.jcl` + `CARDFILE.jcl` + `CUSTFILE.jcl` + `XREFFILE.jcl` + `TRANTYPE.jcl` + `TRANCATG.jcl` + `DISCGRP.jcl` + `TCATBALF.jcl` + `TRANFILE.jcl` + `CBADMCDJ.jcl` | One-shot composite `Job` of 9-10 sequential steps; each step reads one `app/data/ASCII/*.txt` file via `AsciiFixedWidthItemReader` and persists via the corresponding repository |
| `src/main/java/com/carddemo/batch/UserSeedingJobConfig.java` | CREATE | `app/jcl/DUSRSECJ.jcl` | Seeds 10 default users (ADMIN001-005, USER0001-0005) with BCrypt-hashed `"PASSWORD"` |
| `src/main/java/com/carddemo/batch/TransactionBackupJobConfig.java` | CREATE | `app/jcl/TRANBKP.jcl` | Tasklet wrapping `pg_dump` via `ProcessBuilder` OR `JdbcCursorItemReader` exporting to CSV |
| `src/main/java/com/carddemo/batch/TransactionReportJobConfig.java` | CREATE | `app/jcl/TRANREPT.jcl` + `app/proc/TRANREPT.prc` | Generates transaction detail report (CVTRA07Y layout) |
| `src/main/java/com/carddemo/batch/CategoryBalanceReportJobConfig.java` | CREATE | `app/jcl/PRTCATBL.jcl` | Prints all TCATBAL records grouped by account |
| `src/main/java/com/carddemo/batch/AccountFileReadJobConfig.java` | CREATE | `app/cbl/CBACT01C.cbl` + `app/jcl/READACCT.jcl` | Diagnostic — sequentially dumps `accounts` table |
| `src/main/java/com/carddemo/batch/CardFileReadJobConfig.java` | CREATE | `app/cbl/CBACT02C.cbl` + `app/jcl/READCARD.jcl` | Diagnostic |
| `src/main/java/com/carddemo/batch/XrefFileReadJobConfig.java` | CREATE | `app/cbl/CBACT03C.cbl` + `app/jcl/READXREF.jcl` | Diagnostic |
| `src/main/java/com/carddemo/batch/CustomerFileReadJobConfig.java` | CREATE | `app/cbl/CBCUS01C.cbl` + `app/jcl/READCUST.jcl` | Diagnostic |
| `src/main/java/com/carddemo/batch/DailyTransactionReadJobConfig.java` | CREATE | `app/cbl/CBTRN01C.cbl` | Reads `app/data/ASCII/dailytran.txt` via `FlatFileItemReader` into `daily_transactions` staging table |
| `src/main/java/com/carddemo/batch/TransactionReadJobConfig.java` | CREATE | `app/cbl/CBTRN03C.cbl` | Diagnostic |
| `src/main/java/com/carddemo/batch/reader/AsciiFixedWidthItemReader.java` | CREATE | `app/data/ASCII/*.txt` (REFERENCE) + `app/ctl/REPROCT.ctl` (REFERENCE) | Parses fixed-width ASCII fixtures; column offsets/lengths derived from each copybook's `PIC` clauses |
| `src/main/java/com/carddemo/batch/BatchConfig.java` | CREATE | (cross-cutting) | `@EnableBatchProcessing` (implicit in Boot 3); declares `JobRegistry`, `JobOperator`, `JobLauncher` |
| `src/main/java/com/carddemo/util/DateConversionUtil.java` | CREATE | `app/cbl/CSUTLDTC.cbl` | CCYYMMDD ↔ MM/DD/YYYY ↔ DB2 timestamp `yyyy-MM-dd-HH.mm.ss.SSS'0000'` via `DateTimeFormatter` |

#### 0.4.1.3 JCL Jobs → Spring Batch Job beans (wiring summary)

| Target File | Mode | Source File | Key Changes |
|---|---|---|---|
| `src/main/java/com/carddemo/batch/TransactionPostingJobConfig.java` | CREATE | `app/jcl/POSTTRAN.jcl` [L23] | Job parameters: input source = `daily_transactions` table; reject sink = `rejected_transactions` table; chains processor + composite writer |
| `src/main/java/com/carddemo/batch/InterestCalculationJobConfig.java` | CREATE | `app/jcl/INTCALC.jcl` [L22] | Accepts `tranDate` JobParameter (equivalent to `PARM='2022071800'`); used as prefix for generated `TRAN-ID` |
| `src/main/java/com/carddemo/batch/TransactionConsolidationJobConfig.java` | CREATE | `app/jcl/COMBTRAN.jcl` [L22-L48] | SORT step → REPRO step replaced by SQL `INSERT INTO transactions SELECT ... ORDER BY tran_id ON CONFLICT DO UPDATE` |
| `src/main/java/com/carddemo/batch/StatementGenerationJobConfig.java` | CREATE | `app/jcl/CREASTMT.JCL` [L22-L97] | 4 chained steps: DELDEF01 (drop/recreate temp table) → STEP010 (re-sort by card+id) → STEP020 (load temp) → STEP040 (CBSTM03A equivalent) |
| `src/main/java/com/carddemo/batch/UserSeedingJobConfig.java` | CREATE | `app/jcl/DUSRSECJ.jcl` | BCrypt rehashing pass |
| `src/main/java/com/carddemo/batch/DataInitializationJobConfig.java` | CREATE | `app/jcl/ACCTFILE.jcl`, `CARDFILE.jcl`, `CUSTFILE.jcl`, `XREFFILE.jcl`, `TRANTYPE.jcl`, `TRANCATG.jcl`, `DISCGRP.jcl`, `TCATBALF.jcl`, `TRANFILE.jcl`, `CBADMCDJ.jcl` | Composite job |
| `src/main/java/com/carddemo/batch/CategoryBalanceReportJobConfig.java` | CREATE | `app/jcl/PRTCATBL.jcl` | Standalone print job |
| `src/main/java/com/carddemo/batch/TransactionBackupJobConfig.java` | CREATE | `app/jcl/TRANBKP.jcl` | `pg_dump` tasklet |
| `src/main/java/com/carddemo/batch/TransactionReportJobConfig.java` | CREATE | `app/jcl/TRANREPT.jcl` + `app/proc/TRANREPT.prc` | Standalone report |
| (no Java artifact) | n/a (no-op) | `app/jcl/CLOSEFIL.jcl`, `OPENFIL.jcl`, `DEFCUST.jcl`, `DEFGDGB.jcl`, `DALYREJS.jcl`, `TRANIDX.jcl` | GDG/VSAM-define semantics replaced by Flyway (`V1__schema.sql`, `V2__indexes.sql`); no Java code emitted |

#### 0.4.1.4 Copybooks → JPA Entities

| Target File | Mode | Source File | Key Changes |
|---|---|---|---|
| `src/main/java/com/carddemo/entity/Account.java` | CREATE | `app/cpy/CVACT01Y.cpy` [L4-L17] | 300-byte `ACCOUNT-RECORD` → `@Entity` `accounts`; `acct_id` BIGINT PK; money fields `BigDecimal` (`@Column(precision=12, scale=2)`); `ACCT-EXPIRAION-DATE` [sic] preserved as `expiration_date` column (Java field `expirationDate`) |
| `src/main/java/com/carddemo/entity/Card.java` | CREATE | `app/cpy/CVACT02Y.cpy` [L4-L11] | 150-byte `CARD-RECORD` → `@Entity` `cards`; PK `card_num` VARCHAR(16); `@Index(name="idx_card_account_id", columnList="account_id")` replaces CARDDATA.AIX |
| `src/main/java/com/carddemo/entity/CardXref.java` | CREATE | `app/cpy/CVACT03Y.cpy` [L4-L8] | 50-byte `CARD-XREF-RECORD` → `@Entity` `card_xref`; PK `xref_card_num` VARCHAR(16); `@Index(name="idx_xref_account_id", columnList="account_id")` replaces CARDXREF.AIX |
| `src/main/java/com/carddemo/entity/Customer.java` | CREATE | `app/cpy/CVCUS01Y.cpy` [L4-L23] | 500-byte `CUSTOMER-RECORD` → `@Entity` `customers`; PK `cust_id` BIGINT; `ssn` annotated for masking in DTO mapper |
| `src/main/java/com/carddemo/entity/Transaction.java` | CREATE | `app/cpy/CVTRA05Y.cpy` [L4-L18] | 350-byte `TRAN-RECORD` → `@Entity` `transactions`; PK `tran_id` VARCHAR(16); `@Index(name="idx_transaction_orig_ts", columnList="orig_ts")` replaces TRANSACT.AIX |
| `src/main/java/com/carddemo/entity/DailyTransaction.java` | CREATE | `app/cpy/CVTRA06Y.cpy` [L4-L18] | 350-byte `DALYTRAN-RECORD` → `@Entity` `daily_transactions` (staging) |
| `src/main/java/com/carddemo/entity/RejectedTransaction.java` | CREATE | `app/cpy/CVTRA06Y.cpy` + DALYREJS layout | `@Entity` `rejected_transactions` (350 + 80 = 430 bytes) with `validation_code` and `rejection_reason` columns |
| `src/main/java/com/carddemo/entity/TransactionCategoryBalance.java` | CREATE | `app/cpy/CVTRA01Y.cpy` [L4-L10] | 50-byte `TRAN-CAT-BAL-RECORD` → `@Entity` `tran_cat_balances`; `@EmbeddedId TransactionCategoryBalanceId` (account_id + type_cd + cat_cd) |
| `src/main/java/com/carddemo/entity/TransactionCategoryBalanceId.java` | CREATE | `app/cpy/CVTRA01Y.cpy` [L4-L10] | `@Embeddable` composite key |
| `src/main/java/com/carddemo/entity/DisclosureGroup.java` | CREATE | `app/cpy/CVTRA02Y.cpy` [L4-L10] | 50-byte `DIS-GROUP-RECORD` → `@Entity` `disclosure_groups`; `@EmbeddedId DisclosureGroupId` (group_id + type_cd + cat_cd); `dis_int_rate` `BigDecimal(precision=6, scale=2)` |
| `src/main/java/com/carddemo/entity/DisclosureGroupId.java` | CREATE | `app/cpy/CVTRA02Y.cpy` [L4-L10] | `@Embeddable` composite key |
| `src/main/java/com/carddemo/entity/TransactionType.java` | CREATE | `app/cpy/CVTRA03Y.cpy` [L4-L7] | 60-byte `TRAN-TYPE-RECORD` → `@Entity` `transaction_types`; PK `tran_type` VARCHAR(2) |
| `src/main/java/com/carddemo/entity/TransactionCategory.java` | CREATE | `app/cpy/CVTRA04Y.cpy` [L4-L9] | 60-byte `TRAN-CAT-RECORD` → `@Entity` `transaction_categories`; `@EmbeddedId TransactionCategoryId` (type_cd + cat_cd) |
| `src/main/java/com/carddemo/entity/TransactionCategoryId.java` | CREATE | `app/cpy/CVTRA04Y.cpy` [L4-L9] | `@Embeddable` composite key |
| `src/main/java/com/carddemo/entity/User.java` | CREATE | `app/cpy/CSUSR01Y.cpy` [L17-L23] | 80-byte `SEC-USER-DATA` → `@Entity` `users` implementing `org.springframework.security.core.userdetails.UserDetails`; `sec_usr_pwd` VARCHAR(60) stores BCrypt hash (NOT plaintext) |

#### 0.4.1.5 Repositories (Spring Data JPA)

| Target File | Mode | Source File | Key Changes |
|---|---|---|---|
| `src/main/java/com/carddemo/repository/AccountRepository.java` | CREATE | `app/cbl/COACTVWC.cbl` + `app/cbl/COACTUPC.cbl` | `extends JpaRepository<Account, Long>` |
| `src/main/java/com/carddemo/repository/CardRepository.java` | CREATE | `app/cbl/COCRDLIC.cbl` | `Page<Card> findByAccountId(Long accountId, Pageable pageable)` replaces `STARTBR DATASET('CARDAIX')` |
| `src/main/java/com/carddemo/repository/CardXrefRepository.java` | CREATE | `app/cbl/CBTRN02C.cbl` [L380-L392] | `Optional<CardXref> findById(String cardNum)`; `List<CardXref> findByAccountId(Long accountId)` |
| `src/main/java/com/carddemo/repository/CustomerRepository.java` | CREATE | `app/cbl/CBSTM03A.CBL` | `extends JpaRepository<Customer, Long>` |
| `src/main/java/com/carddemo/repository/TransactionRepository.java` | CREATE | `app/cbl/CBSTM03A.CBL` + `app/cbl/COTRN00C.cbl` | `List<Transaction> findByOrigTimestampBetween(LocalDateTime start, LocalDateTime end)`; `List<Transaction> findByCardNumber(String cardNum)` |
| `src/main/java/com/carddemo/repository/DailyTransactionRepository.java` | CREATE | `app/cbl/CBTRN02C.cbl` | `extends JpaRepository<DailyTransaction, Long>` |
| `src/main/java/com/carddemo/repository/RejectedTransactionRepository.java` | CREATE | `app/cbl/CBTRN02C.cbl` | `extends JpaRepository<RejectedTransaction, Long>` |
| `src/main/java/com/carddemo/repository/TransactionCategoryBalanceRepository.java` | CREATE | `app/cbl/CBTRN02C.cbl` [L467-L501] + `app/cbl/CBACT04C.cbl` | Upsert via `existsById` + `save`; composite key lookup |
| `src/main/java/com/carddemo/repository/DisclosureGroupRepository.java` | CREATE | `app/cbl/CBACT04C.cbl` [L415-L440] | `findByGroupIdAndTypeAndCategory(...)` returning `Optional<DisclosureGroup>` to support DEFAULT fallback |
| `src/main/java/com/carddemo/repository/TransactionTypeRepository.java` | CREATE | reference data | `extends JpaRepository<TransactionType, String>` |
| `src/main/java/com/carddemo/repository/TransactionCategoryRepository.java` | CREATE | reference data | `extends JpaRepository<TransactionCategory, TransactionCategoryId>` |
| `src/main/java/com/carddemo/repository/UserRepository.java` | CREATE | `app/cbl/COSGN00C.cbl` | `Optional<User> findById(String userId)` used by `UserDetailsServiceImpl` |

#### 0.4.1.6 Security, Configuration, and Cross-Cutting

| Target File | Mode | Source File | Key Changes |
|---|---|---|---|
| `src/main/java/com/carddemo/security/SecurityConfig.java` | CREATE | `app/cbl/COSGN00C.cbl` + `app/csd/CARDDEMO.CSD` | `@EnableWebSecurity` + `SecurityFilterChain` bean + `BCryptPasswordEncoder` bean + `AuthenticationManager` bean |
| `src/main/java/com/carddemo/security/UserDetailsServiceImpl.java` | CREATE | `app/cbl/COSGN00C.cbl` [L211-L257] | Loads `User` via `UserRepository`; maps `userType` to `GrantedAuthority` |
| `src/main/java/com/carddemo/security/JwtAuthenticationFilter.java` | CREATE | (new) | Validates bearer tokens; populates `SecurityContextHolder` |
| `src/main/java/com/carddemo/security/MethodSecurityConfig.java` | CREATE | (closes COUSR auth gap per Tech Spec §6.4) | `@EnableMethodSecurity(prePostEnabled = true)` |
| `src/main/java/com/carddemo/security/CustomAuthorityMapper.java` | CREATE | `app/cpy/COCOM01Y.cpy` [L19-L44] | `'A' → ROLE_ADMIN`, `'U' → ROLE_USER` |
| `src/main/java/com/carddemo/config/DataSourceConfig.java` | CREATE | `app/csd/CARDDEMO.CSD` | PostgreSQL HikariCP DataSource configured from `application.yml` |
| `src/main/java/com/carddemo/config/JpaConfig.java` | CREATE | (new) | `@EnableJpaRepositories(basePackages = "com.carddemo.repository")` + `@EnableJpaAuditing` |
| `src/main/java/com/carddemo/config/WebConfig.java` | CREATE | (new) | CORS, Jackson configuration (`BigDecimal` as JSON number with `WRITE_BIGDECIMAL_AS_PLAIN`), validation |
| `src/main/java/com/carddemo/config/OpenApiConfig.java` | CREATE | (new) | `OpenAPI` bean for springdoc-openapi |
| `src/main/java/com/carddemo/util/TransactionIdGenerator.java` | CREATE | `app/cbl/CBACT04C.cbl` [L473-L500] | Generates 16-char IDs: `parmDate(10) + suffix(6)`; atomic counter for batch; sequence for online |
| `src/main/java/com/carddemo/util/BigDecimalUtil.java` | CREATE | (cross-cutting) | Constants `SCALE_TWO=2`, `HALF_UP=RoundingMode.HALF_UP`, `INTEREST_DIVISOR=BigDecimal.valueOf(1200)`; helper methods for scaled add, subtract, multiply, divide |
| `src/main/java/com/carddemo/util/FixedWidthRecordParser.java` | CREATE | `app/ctl/REPROCT.ctl` + `app/data/ASCII/*.txt` | Parses fixed-width records by offset/length per copybook layouts |
| `src/main/java/com/carddemo/validation/TransactionValidator.java` | CREATE | `app/cbl/CBTRN02C.cbl` [L370-L422] | Validator chain mirroring COBOL `1500-VALIDATE-TRAN` |
| `src/main/java/com/carddemo/validation/AccountValidator.java` | CREATE | `app/cbl/CBTRN02C.cbl` [L393-L422] | Credit limit + expiration date checks |

#### 0.4.1.7 DTOs, Mappers, Exceptions

| Target File | Mode | Source File | Key Changes |
|---|---|---|---|
| `src/main/java/com/carddemo/dto/auth/LoginRequest.java`, `LoginResponse.java` | CREATE | `app/cpy-bms/COSGN00.CPY` (REFERENCE) | `userId`, `password` request; JWT token response |
| `src/main/java/com/carddemo/dto/account/AccountDto.java` | CREATE | `app/cpy-bms/COACTVW.CPY` (REFERENCE) | All account fields; `BigDecimal` for money fields |
| `src/main/java/com/carddemo/dto/card/CardDto.java`, `CardListResponse.java` | CREATE | `app/cpy-bms/COCRDSL.CPY` + `COCRDLI.CPY` (REFERENCE) | Card details; paginated list |
| `src/main/java/com/carddemo/dto/customer/CustomerDto.java` | CREATE | `app/cpy/CVCUS01Y.cpy` (REFERENCE) | Customer fields with `ssn` masked as `***-**-####` in mapper |
| `src/main/java/com/carddemo/dto/transaction/TransactionDto.java`, `TransactionRequest.java`, `TransactionListResponse.java` | CREATE | `app/cpy-bms/COTRN00.CPY` + `COTRN01.CPY` + `COTRN02.CPY` (REFERENCE) | Transaction view, create request, paginated list |
| `src/main/java/com/carddemo/dto/billpayment/BillPaymentRequest.java`, `BillPaymentResponse.java` | CREATE | `app/cpy-bms/COBIL00.CPY` (REFERENCE) | Bill payment request/response |
| `src/main/java/com/carddemo/dto/report/ReportRequest.java` | CREATE | `app/cpy-bms/CORPT00.CPY` (REFERENCE) | Report submission request |
| `src/main/java/com/carddemo/dto/user/UserDto.java`, `UserCreateRequest.java` | CREATE | `app/cpy-bms/COUSR00.CPY` + `COUSR01.CPY` + `COUSR02.CPY` + `COUSR03.CPY` (REFERENCE) | User CRUD payloads |
| `src/main/java/com/carddemo/dto/menu/MenuOption.java`, `MenuResponse.java` | CREATE | `app/cpy/COMEN02Y.cpy` + `app/cpy/COADM02Y.cpy` (REFERENCE) | Menu option records |
| `src/main/java/com/carddemo/mapper/AccountMapper.java` | CREATE | (entity ↔ DTO) | `BigDecimal` scale preserved; date format conversion via `DateConversionUtil` |
| `src/main/java/com/carddemo/mapper/CardMapper.java` | CREATE | (entity ↔ DTO) | |
| `src/main/java/com/carddemo/mapper/TransactionMapper.java` | CREATE | (entity ↔ DTO) | DB2 timestamp formatting for `origTimestamp` / `procTimestamp` |
| `src/main/java/com/carddemo/mapper/CustomerMapper.java` | CREATE | (entity ↔ DTO) | SSN masking applied here |
| `src/main/java/com/carddemo/mapper/UserMapper.java` | CREATE | (entity ↔ DTO) | Excludes `sec_usr_pwd` hash from response |
| `src/main/java/com/carddemo/exception/AccountNotFoundException.java` | CREATE | `app/cbl/CBTRN02C.cbl` [code 101] | `RuntimeException` with code constant |
| `src/main/java/com/carddemo/exception/InvalidCardException.java` | CREATE | `app/cbl/CBTRN02C.cbl` [code 100] | Message: "INVALID CARD NUMBER FOUND" |
| `src/main/java/com/carddemo/exception/OverlimitException.java` | CREATE | `app/cbl/CBTRN02C.cbl` [code 102] | Message: "OVERLIMIT TRANSACTION" |
| `src/main/java/com/carddemo/exception/ExpiredAccountException.java` | CREATE | `app/cbl/CBTRN02C.cbl` [code 103] | Message: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION" |
| `src/main/java/com/carddemo/exception/TransactionValidationException.java` | CREATE | (parent) | Parent of 100/101/102/103 |
| `src/main/java/com/carddemo/exception/DiscloseGroupNotFoundException.java` | CREATE | `app/cbl/CBACT04C.cbl` [L415-L440] | Thrown when both group lookup AND DEFAULT fallback miss |
| `src/main/java/com/carddemo/exception/ErrorResponse.java` | CREATE | (new) | Standardized error payload (code, message, path, timestamp) |

#### 0.4.1.8 Build, Configuration, and Documentation

| Target File | Mode | Source File | Key Changes |
|---|---|---|---|
| `pom.xml` | CREATE | (new) | Maven build with `spring-boot-starter-parent` 3.2.12; Java 17 source/target; all dependencies enumerated in §0.5 |
| `src/main/resources/application.yml` | CREATE | (new) | Base config: `spring.application.name`, JPA dialect `org.hibernate.dialect.PostgreSQLDialect`, Flyway enabled, logging pattern, Actuator endpoints exposed |
| `src/main/resources/application-dev.yml` | CREATE | (new) | Local PostgreSQL JDBC URL, dev credentials, `ddl-auto: validate`, debug logging |
| `src/main/resources/application-prod.yml` | CREATE | (new) | Production JDBC URL via environment, `ddl-auto: validate`, INFO logging, structured JSON appender |
| `src/main/resources/db/migration/V1__schema.sql` | CREATE | `app/cpy/CVACT01Y.cpy` + `CVACT02Y.cpy` + `CVACT03Y.cpy` + `CVCUS01Y.cpy` + `CVTRA01Y.cpy` + `CVTRA02Y.cpy` + `CVTRA03Y.cpy` + `CVTRA04Y.cpy` + `CVTRA05Y.cpy` + `CVTRA06Y.cpy` + `CSUSR01Y.cpy` | DDL creating all 14 base tables matching VSAM record layouts |
| `src/main/resources/db/migration/V2__indexes.sql` | CREATE | `app/catlg/LISTCAT.txt` + `app/csd/CARDDEMO.CSD` | Secondary B-tree indexes: `idx_card_account_id`, `idx_xref_account_id`, `idx_transaction_orig_ts` (replaces VSAM AIX) |
| `src/main/resources/db/migration/V3__seed_reference_data.sql` | CREATE | `app/data/ASCII/trantype.txt` + `trancatg.txt` + `discgrp.txt` | Seeds 7 transaction types, 18 categories, 51 disclosure groups |
| `src/main/resources/db/migration/V4__seed_users.sql` | CREATE | `app/jcl/DUSRSECJ.jcl` (REFERENCE) | Inserts 10 users (`ADMIN001`-`ADMIN005`, `USER0001`-`USER0005`) with pre-computed BCrypt hashes of literal `"PASSWORD"` |
| `src/main/resources/db/migration/V5__seed_master_data.sql` | CREATE | `app/data/ASCII/custdata.txt` + `acctdata.txt` + `carddata.txt` + `cardxref.txt` + `tcatbal.txt` | Seeds 50 customers, 50 accounts, 50 cards, 50 cross-references, 100 transaction category balances |
| `src/main/resources/templates/statement-template.html` | CREATE | `app/cbl/CBSTM03A.CBL` [L506-L555] | HTML template extracted from `5100-WRITE-HTML-HEADER` literals; preserved character-by-character |
| `README.md` | UPDATE | `README.md` (existing) | Add Spring Boot 3.2 setup, Maven build (`mvn clean package`), PostgreSQL setup, Flyway migration explanation, sample curl invocations, Actuator endpoint descriptions; preserve original mainframe documentation section unchanged |
| `docs/migration-mapping.md` | CREATE | (this AAP) | Companion document with detailed COBOL→Java mapping rationale |

#### 0.4.1.9 Tests

| Target File | Mode | Source File | Key Changes |
|---|---|---|---|
| `src/test/java/com/carddemo/controller/*Test.java` | CREATE | Each controller | MockMvc tests for each REST endpoint covering happy path, 4xx errors, security scenarios |
| `src/test/java/com/carddemo/service/*Test.java` | CREATE | Each service | Unit tests with Mockito for repositories |
| `src/test/java/com/carddemo/batch/*JobTest.java` | CREATE | Each batch job | `JobLauncherTestUtils` + `JobRepositoryTestUtils` integration tests |
| `src/test/java/com/carddemo/integration/*IT.java` | CREATE | (full stack) | Testcontainers PostgreSQL; verifies Flyway migrations apply cleanly; end-to-end REST + batch scenarios |
| `src/test/java/com/carddemo/businesslogic/InterestCalculationParityTest.java` | CREATE | `app/cbl/CBACT04C.cbl` | Asserts Java output matches COBOL output for canonical inputs (TCATBAL × rate × 1200 division) |
| `src/test/java/com/carddemo/businesslogic/TransactionPostingParityTest.java` | CREATE | `app/cbl/CBTRN02C.cbl` | Verifies codes 100/101/102/103 with exact message strings; TCATBAL upsert; sign-based balance update |
| `src/test/java/com/carddemo/businesslogic/StatementGenerationParityTest.java` | CREATE | `app/cbl/CBSTM03A.CBL` | Byte-for-byte HTML comparison against COBOL reference output |
| `src/test/resources/application-test.yml` | CREATE | (new) | Testcontainers PostgreSQL config |
| `src/test/resources/fixtures/*.csv` | CREATE | `app/data/ASCII/*.txt` | CSV mirrors of ASCII fixtures for test data |

#### 0.4.1.10 REFERENCE Files (preserved unchanged)

| Target File / Pattern | Mode | Notes |
|---|---|---|
| `app/cbl/*.cbl` | REFERENCE | All 28 COBOL programs preserved for downstream regression testing |
| `app/jcl/*.jcl` | REFERENCE | All 29 JCL jobs preserved for batch sequence verification |
| `app/cpy/*.cpy` | REFERENCE | All 28 copybooks preserved for entity verification |
| `app/cpy-bms/*.CPY` | REFERENCE | All 17 BMS symbolic copybooks used to derive DTO field shapes |
| `app/bms/*.bms` | REFERENCE | All 17 BMS map sources (no UI replacement per prompt) |
| `app/csd/CARDDEMO.CSD` | REFERENCE | CICS resource definitions used to derive transaction routing |
| `app/catlg/LISTCAT.txt` | REFERENCE | VSAM catalog used to derive primary key positions and index configs |
| `app/data/ASCII/*.txt` | REFERENCE / INPUT | Read by `DataInitializationJobConfig` for seeding |
| `app/proc/*.prc` | REFERENCE | JCL procs absorbed into Spring Batch step configs |
| `app/ctl/REPROCT.ctl` | REFERENCE | IDCAMS control card |
| `samples/m2/**`, `samples/jcl/**`, `samples/proc/**` | REFERENCE | OUT OF SCOPE — preserved unchanged |
| `diagrams/**` | REFERENCE | Documentation diagrams preserved |
| `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `LICENSE`, `NOTICE` | REFERENCE | Project metadata preserved unchanged |

### 0.4.2 Cross-File Dependencies

**JPA entity relationships (new Java imports):**

- `Account.java` references `Customer` indirectly via `CardXref` (no direct FK; `account.customer` linked through xref)
- `Card.java` `@ManyToOne` → `Account` via `CARD-ACCT-ID` (column `account_id`)
- `CardXref.java` junction: references `Card` (xref_card_num), `Customer` (xref_cust_id), `Account` (xref_acct_id) via `@ManyToOne`
- `Transaction.java` `@ManyToOne` → `Card` via `TRAN-CARD-NUM` (column `card_num`)
- `DailyTransaction` deliberately denormalized — no FK relationships (mirrors PS feed)
- `TransactionCategoryBalance.java` references `TransactionCategoryBalanceId` (`@EmbeddedId`)
- `DisclosureGroup.java` references `DisclosureGroupId` (`@EmbeddedId`)
- `TransactionCategory.java` references `TransactionCategoryId` (`@EmbeddedId`)
- `User.java` implements `org.springframework.security.core.userdetails.UserDetails`

**Service-to-repository wiring (constructor DI):**

- `AuthService` ← `UserRepository`, `BCryptPasswordEncoder`
- `AccountService` ← `AccountRepository`, `CardXrefRepository`
- `CardService` ← `CardRepository`, `CardXrefRepository`
- `CustomerService` ← `CustomerRepository`, `CustomerMapper`
- `TransactionService` ← `TransactionRepository`, `CardXrefRepository`, `AccountRepository`, `TransactionTypeRepository`, `TransactionCategoryRepository`, `TransactionCategoryBalanceRepository`, `TransactionIdGenerator`
- `BillPaymentService` ← `AccountRepository`, `TransactionRepository`, `TransactionService`
- `ReportService` ← `JobLauncher`, `JobRegistry`
- `UserService` ← `UserRepository`, `PasswordEncoder`, `UserMapper`
- `StatementService` ← `CardXrefRepository`, `CustomerRepository`, `AccountRepository`, `TransactionRepository`, `StatementHtmlBuilder`

**Controller-to-service wiring (constructor DI):**

- `AuthController` ← `AuthService`, `AuthenticationManager`
- `AccountController` ← `AccountService`, `AccountMapper`
- `CardController` ← `CardService`, `CardMapper`
- `CustomerController` ← `CustomerService`, `CustomerMapper`
- `TransactionController` ← `TransactionService`, `TransactionMapper`
- `BillPaymentController` ← `BillPaymentService`
- `ReportController` ← `ReportService`
- `UserController` ← `UserService`, `UserMapper` — all methods `@PreAuthorize("hasRole('ADMIN')")`
- `MenuController` ← (reads `SecurityContextHolder` for role)
- `BatchAdminController` ← `JobLauncher`, `JobRegistry`

**Batch job wiring:**

- `TransactionPostingJobConfig` ← `JobRepository`, `PlatformTransactionManager`, `DailyTransactionRepository`, `CardXrefRepository`, `AccountRepository`, `TransactionRepository`, `TransactionCategoryBalanceRepository`, `RejectedTransactionRepository`
- `InterestCalculationJobConfig` ← `JobRepository`, `PlatformTransactionManager`, `TransactionCategoryBalanceRepository`, `CardXrefRepository`, `AccountRepository`, `DisclosureGroupRepository`, `TransactionRepository`, `TransactionIdGenerator`
- `StatementGenerationJobConfig` ← `JobRepository`, `PlatformTransactionManager`, `CardXrefRepository`, `CustomerRepository`, `AccountRepository`, `TransactionRepository`, `StatementHtmlBuilder`
- `DataInitializationJobConfig` ← `JobRepository`, all entity repositories, `AsciiFixedWidthItemReader`, `FixedWidthRecordParser`
- `UserSeedingJobConfig` ← `UserRepository`, `BCryptPasswordEncoder`

**Spring Security filter chain wiring:**

- `SecurityConfig.filterChain` ← `JwtAuthenticationFilter`, `UserDetailsServiceImpl`, `BCryptPasswordEncoder`, `AuthenticationManager`
- `MethodSecurityConfig` activates `@PreAuthorize` enforcement on `UserController`, `BatchAdminController`

**Import statement patterns (Java packages):**

- `com.carddemo.entity.*` imported by repositories, services, mappers, batch jobs
- `com.carddemo.repository.*` imported by services and batch jobs
- `com.carddemo.service.*` imported by controllers
- `com.carddemo.dto.*` imported by controllers and mappers
- `com.carddemo.exception.*` imported by `GlobalExceptionHandler`, services, batch processors
- `jakarta.persistence.*` — JPA annotations (NOT `javax.persistence.*` — Jakarta EE 10 baseline)
- `jakarta.validation.constraints.*` — validation annotations
- `org.springframework.security.core.userdetails.UserDetails` — implemented by `User` entity
- `org.springframework.batch.core.*` — `Job`, `Step`, `JobBuilder`, `StepBuilder` in batch configs

### 0.4.3 Wildcard Patterns (Trailing Only)

The following trailing wildcard patterns describe groups of files in scope. **No leading wildcards are used** (per the prompt constraint).

- `src/main/java/com/carddemo/entity/*.java` — all 15 JPA entities
- `src/main/java/com/carddemo/repository/*.java` — all 12 Spring Data JPA repositories
- `src/main/java/com/carddemo/controller/*.java` — all 9 REST controllers
- `src/main/java/com/carddemo/controller/advice/*.java` — `GlobalExceptionHandler`
- `src/main/java/com/carddemo/service/*.java` — all 10 service classes
- `src/main/java/com/carddemo/batch/*.java` — all batch `Job` configurations, processors, readers, writers
- `src/main/java/com/carddemo/batch/reader/*.java` — `AsciiFixedWidthItemReader`
- `src/main/java/com/carddemo/dto/**/*.java` — all DTOs nested by domain (auth, account, card, customer, transaction, billpayment, report, user, menu)
- `src/main/java/com/carddemo/mapper/*.java` — all entity-to-DTO mappers
- `src/main/java/com/carddemo/exception/*.java` — all exception classes
- `src/main/java/com/carddemo/security/*.java` — all Spring Security configuration
- `src/main/java/com/carddemo/config/*.java` — all `@Configuration` classes
- `src/main/java/com/carddemo/util/*.java` — all utility classes
- `src/main/java/com/carddemo/validation/*.java` — all validator classes
- `src/main/resources/application*.yml` — application + profile-specific configs
- `src/main/resources/db/migration/V*.sql` — all Flyway migration scripts
- `src/main/resources/templates/*.html` — `statement-template.html`
- `src/test/java/com/carddemo/**/*.java` — all test classes
- `src/test/resources/fixtures/*.csv` — test fixture data

### 0.4.4 One-Phase Execution

This entire refactor is executed by the Blitzy platform in **ONE phase**. All ~150 new Java files, the `pom.xml`, all `application*.yml` files, the 5 Flyway migration scripts, the `statement-template.html`, and the `README.md` update are produced atomically. The original COBOL/JCL/copybook/BMS/CSD source tree is preserved unchanged throughout. No file in scope is deferred to a later phase. No incremental rollout, no feature-flag-guarded subsets, no parallel-running shadow mode — this is a complete, single-phase migration deliverable.

## 0.5 Dependency Inventory

### 0.5.1 Key Private and Public Packages

The prompt specifies Spring Boot 3.2. The highest patch in the 3.2.x line is **3.2.12** (released 2024-11-21, marking the final OSS release of the 3.2 branch). Per the Spring Boot 3.2 BOM, this resolves Spring Framework 6.1.x, Spring Security 6.2.x, Spring Batch 5.1.x, Hibernate ORM 6.4.x, Tomcat 10.1.x, and Jackson 2.15.x. All dependencies below use the BOM-managed versions unless a version is shown explicitly.

| Registry | Group / Artifact | Version | Scope | Purpose |
|---|---|---|---|---|
| Maven Central | `org.springframework.boot:spring-boot-starter-parent` | 3.2.12 | parent | Spring Boot BOM; resolves all transitive dependency versions |
| Maven Central | `org.springframework.boot:spring-boot-starter-web` | (BOM) | compile | Embedded Tomcat 10.1.x, Spring MVC `@RestController` support for the 9 REST controllers in §0.4.1.1 |
| Maven Central | `org.springframework.boot:spring-boot-starter-data-jpa` | (BOM) | compile | Spring Data JPA + Hibernate 6.4.x ORM for the 12 repositories in §0.4.1.5 |
| Maven Central | `org.springframework.boot:spring-boot-starter-security` | (BOM) | compile | Spring Security 6.2.x — `SecurityFilterChain`, `BCryptPasswordEncoder`, `@PreAuthorize` for `SecurityConfig` and `MethodSecurityConfig` |
| Maven Central | `org.springframework.boot:spring-boot-starter-batch` | (BOM) | compile | Spring Batch 5.1.x — `Job`, `Step`, `JobLauncher`, `JobRegistry` for the ~17 batch job configurations |
| Maven Central | `org.springframework.boot:spring-boot-starter-validation` | (BOM) | compile | Jakarta Bean Validation 3.0 for `@Valid` on request DTOs |
| Maven Central | `org.springframework.boot:spring-boot-starter-actuator` | (BOM) | compile | `/actuator/health`, `/actuator/info`, `/actuator/metrics` operational endpoints |
| Maven Central | `org.postgresql:postgresql` | 42.7.x (BOM) | runtime | PostgreSQL 15 JDBC driver |
| Maven Central | `org.flywaydb:flyway-core` | 9.22.x (BOM) | compile | Schema migration for `V1__schema.sql` through `V5__seed_master_data.sql` |
| Maven Central | `org.projectlombok:lombok` | 1.18.30 (BOM) | provided | `@RequiredArgsConstructor`, `@Getter`, `@Setter`, `@Builder` to reduce boilerplate in entities and services |
| Maven Central | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.3.0 | compile | OpenAPI 3 documentation with Swagger UI at `/swagger-ui.html` |
| Maven Central | `org.springframework.boot:spring-boot-starter-test` | (BOM) | test | JUnit 5 Jupiter, Mockito, AssertJ, Spring Test |
| Maven Central | `org.springframework.batch:spring-batch-test` | (BOM) | test | `JobLauncherTestUtils`, `JobRepositoryTestUtils` for batch parity tests |
| Maven Central | `org.springframework.security:spring-security-test` | (BOM) | test | `@WithMockUser`, `@WithUserDetails` for security slice tests |
| Maven Central | `org.testcontainers:postgresql` | 1.19.x (BOM) | test | PostgreSQL container for integration tests |
| Maven Central | `org.testcontainers:junit-jupiter` | 1.19.x (BOM) | test | JUnit 5 integration for Testcontainers |
| Maven Central | `org.apache.maven.plugins:maven-compiler-plugin` | (BOM) | build plugin | Java 17 source/target with `-parameters` (required for Spring Framework 6.1 parameter name binding) |
| Maven Central | `org.springframework.boot:spring-boot-maven-plugin` | (BOM) | build plugin | Executable JAR packaging via `repackage` goal |
| Maven Central | `org.apache.maven.plugins:maven-surefire-plugin` | (BOM) | build plugin | Unit test execution (`*Test.java`) |
| Maven Central | `org.apache.maven.plugins:maven-failsafe-plugin` | (BOM) | build plugin | Integration test execution (`*IT.java`) |

**Java runtime:** Java 17 (LTS — Spring Boot 3.x minimum). Eclipse Temurin 17 recommended (security patches through October 2027). The Maven Compiler Plugin is configured with `<source>17</source>`, `<target>17</target>`, and `<parameters>true</parameters>` (required since Spring Framework 6.1 no longer deduces parameter names by parsing bytecode).

**PostgreSQL runtime:** Version 15 (per the prompt). All entity column types (`NUMERIC(15,2)` for `BigDecimal`, `VARCHAR(n)` for fixed-width strings, `TIMESTAMP` for date/time, `CHAR(1)` for status flags, `BIGINT` for numeric IDs) are compatible with PostgreSQL 15 and forward-compatible with PostgreSQL 16+.

**No private package repository** is used; all dependencies resolve from Maven Central via the Spring Boot BOM. The Spring Boot 3.2.12 BOM resolves all transitive versions consistently — no explicit version overrides are required.

### 0.5.2 Dependency Changes

The repository currently has **no Maven, Gradle, npm, pip, or any other build/dependency manifest**. The original CardDemo application is COBOL/JCL — there are no Java/Node/Python dependencies to update or remove. The entire dependency inventory above is **NEW** content introduced by the `pom.xml` created in this refactor. No existing dependencies are upgraded, downgraded, or removed.

### 0.5.3 Import Refactoring

Because the original COBOL files remain unchanged (REFERENCE mode), there are no COBOL import statements to refactor. All import refactoring applies to the new Java code only.

**Package import patterns enforced uniformly:**

- `com.carddemo.entity.*` — imported by `repository/*`, `service/*`, `mapper/*`, `batch/*`
- `com.carddemo.repository.*` — imported by `service/*`, `batch/*`
- `com.carddemo.service.*` — imported by `controller/*`, `batch/*` (where services are reused)
- `com.carddemo.dto.**` — imported by `controller/*`, `mapper/*`
- `com.carddemo.exception.*` — imported by `controller/advice/GlobalExceptionHandler`, `service/*`, `batch/*`
- `com.carddemo.security.*` — imported by `config/*`, `controller/*` (for `SecurityContextHolder` access in `MenuController`)
- `com.carddemo.util.*` — imported by `service/*`, `batch/*`, `mapper/*`
- `com.carddemo.validation.*` — imported by `service/*`, `batch/*`

**Jakarta EE namespace (mandatory for Spring Boot 3.x):**

- `jakarta.persistence.*` (NOT `javax.persistence.*`) — entities, embedded IDs, named queries
- `jakarta.validation.constraints.*` (NOT `javax.validation.constraints.*`) — `@NotNull`, `@Size`, `@Pattern`, `@Min`, `@Max`, `@DecimalMin`, `@DecimalMax`
- `jakarta.transaction.Transactional` OR `org.springframework.transaction.annotation.Transactional` (the Spring annotation is preferred for richer attribute support)
- `jakarta.servlet.*` — only if filter classes need direct servlet API access

**Spring Security imports:**

- `org.springframework.security.core.userdetails.UserDetails` — implemented by `User` entity
- `org.springframework.security.core.userdetails.UserDetailsService` — implemented by `UserDetailsServiceImpl`
- `org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder` — used by `SecurityConfig`, `UserService`, `UserSeedingJobConfig`
- `org.springframework.security.access.prepost.PreAuthorize` — annotation on all admin endpoints

**Spring Batch imports:**

- `org.springframework.batch.core.Job`, `org.springframework.batch.core.Step`
- `org.springframework.batch.core.job.builder.JobBuilder`, `org.springframework.batch.core.step.builder.StepBuilder`
- `org.springframework.batch.core.launch.JobLauncher`, `org.springframework.batch.core.configuration.JobRegistry`
- `org.springframework.batch.core.JobParameters`, `org.springframework.batch.core.JobParametersBuilder`
- `org.springframework.batch.core.repository.JobRepository`
- `org.springframework.batch.item.ItemReader`, `org.springframework.batch.item.ItemProcessor`, `org.springframework.batch.item.ItemWriter`

**Constructor dependency injection enforcement:**

- `@Autowired` field injection is forbidden; all classes use constructor injection
- Lombok `@RequiredArgsConstructor` generates constructors over `final` fields, eliminating manual constructor declarations
- This pattern applies uniformly across `controller/*`, `service/*`, `batch/*`, `security/*`

### 0.5.4 External Reference Updates

**Files outside the `src/main/java/` tree that are CREATE or UPDATE:**

| File | Mode | Update Description |
|---|---|---|
| `pom.xml` | CREATE | Maven build manifest with all dependencies in §0.5.1 |
| `src/main/resources/application.yml` | CREATE | Base Spring Boot configuration |
| `src/main/resources/application-dev.yml` | CREATE | Development profile |
| `src/main/resources/application-prod.yml` | CREATE | Production profile |
| `src/main/resources/db/migration/V1__schema.sql` | CREATE | Flyway DDL |
| `src/main/resources/db/migration/V2__indexes.sql` | CREATE | Flyway secondary indexes |
| `src/main/resources/db/migration/V3__seed_reference_data.sql` | CREATE | Flyway reference data |
| `src/main/resources/db/migration/V4__seed_users.sql` | CREATE | Flyway user seeding (10 default users with BCrypt hashes) |
| `src/main/resources/db/migration/V5__seed_master_data.sql` | CREATE | Flyway master data (50 customers, 50 accounts, 50 cards, 50 xrefs, 100 TCATBAL) |
| `src/main/resources/templates/statement-template.html` | CREATE | Preserved HTML from `CBSTM03A` `5100-WRITE-HTML-HEADER` |
| `src/test/resources/application-test.yml` | CREATE | Testcontainers PostgreSQL config |
| `src/test/resources/fixtures/*.csv` | CREATE | CSV mirrors of ASCII fixtures |
| `README.md` | UPDATE | Add Spring Boot setup section (build, run, test, curl examples) while preserving original mainframe content |
| `docs/migration-mapping.md` | CREATE | COBOL→Java mapping companion document |

**Files explicitly NOT updated** (because they do not exist or are unrelated):

- No existing `setup.py`, `pyproject.toml`, `package.json`, `Gemfile`, `go.mod`, `Cargo.toml`, or other dependency manifests exist in the repository
- No existing `.github/workflows/*.yml`, `.gitlab-ci.yml`, `Jenkinsfile`, or `azure-pipelines.yml` CI/CD pipelines exist — none are created by this refactor
- No existing Docker assets (`Dockerfile`, `docker-compose.yml`) exist — none are created by this refactor

## 0.6 Special Analysis

This section captures cross-cutting analyses that span multiple files, components, and design dimensions. Each topic identifies a concrete COBOL-era pattern, its Java/Spring counterpart, and the implementation rule that the downstream code generation must follow.

### 0.6.1 CICS Pseudo-Conversational → REST Stateless Semantics

CICS pseudo-conversational design preserves user-session state in the 1024-byte `COMMAREA` (copybook `COCOM01Y`) [app/cpy/COCOM01Y.cpy:L19-L44] passed between programs via `EXEC CICS XCTL` and `EXEC CICS RETURN TRANSID`. In a stateless REST world this is decomposed across multiple mechanisms:

| COMMAREA Field | Target Mechanism |
|---|---|
| `CDEMO-USER-ID`, `CDEMO-USER-TYPE` (88-levels `CDEMO-USRTYP-ADMIN='A'`, `CDEMO-USRTYP-USER='U'`) | JWT claim + Spring Security `Authentication` in `SecurityContextHolder`; `GrantedAuthority` `ROLE_ADMIN` / `ROLE_USER` |
| `CDEMO-FROM-TRANID`, `CDEMO-TO-TRANID`, `CDEMO-FROM-PGM`, `CDEMO-TO-PGM` (program flow) | Deprecated in REST — client controls navigation; preserved only as optional audit fields if needed |
| `CDEMO-CUST-ID`, `CDEMO-ACCT-ID`, `CDEMO-CARD-NUM` | HTTP path or query parameters (`/api/accounts/{acctId}/cards/{cardNum}`); never stored server-side between calls |
| `CDEMO-PGM-CONTEXT` (`0=ENTER`, `1=REENTER`) | Optional request body or query parameter distinguishing initial submission vs. validation-error resubmit |
| `CDEMO-LAST-MAP`, `CDEMO-LAST-MAPSET` | Not applicable — no BMS map state in REST |

**Browse cursors** (`STARTBR`/`READNEXT`/`READPREV`/`ENDBR` for `COCRDLIC` PF7/PF8 pagination) [app/cbl/COCRDLIC.cbl] are replaced by stateless Spring Data `Pageable` — `PageRequest.of(page, size, Sort.by(...))`. Each REST request recomputes the cursor from `page` and `size` parameters; no server-side cursor lifecycle is maintained.

**`EXEC CICS XCTL PROGRAM(...)` chaining** is replaced by stateless REST responses — the client interprets the response and issues the next request. There is no server-driven program-flow equivalent.

**`EXEC CICS WRITEQ TD QUEUE('JOBS')`** in `CORPT00C` [app/cbl/CORPT00C.cbl] (which sends report-job parameters to an internal reader for batch execution) is replaced by `JobLauncher.run(jobRegistry.getJob(jobName), jobParameters)` inside `ReportService`, marked `@Async` so the controller returns 202 Accepted immediately.

### 0.6.2 VSAM-to-JPA Data Integrity

| VSAM Construct | JPA / PostgreSQL Equivalent |
|---|---|
| KSDS keyed `READ` [app/cbl/COSGN00C.cbl:L211-L257] | `JpaRepository.findById(...)` on `@Id`-annotated PK column |
| AIX alternate index (e.g., `CARDDATA.AIX` on `CARD-ACCT-ID`) | `@Table(indexes = @Index(name="idx_card_account_id", columnList="account_id"))` + finder method `findByAccountId` |
| `READ UPDATE` exclusive lock (CI-level, ~5-20ms, DTIMOUT 180s) | JPA `@Version` optimistic locking; `OptimisticLockException` → HTTP 409 Conflict |
| `INVALID KEY` handler (file status `'23'`) [app/cbl/CBACT04C.cbl:L415-L440] | `Optional.empty()` from `findById`; service throws `AccountNotFoundException` (→ HTTP 404 / batch code 101) |
| `WRITE` (insert) | `repository.save(entity)` for a transient entity |
| `REWRITE` (update) [app/cbl/CBACT04C.cbl:L350-L370] | `repository.save(entity)` for a managed entity |
| `DELETE` | `repository.delete(entity)` or `repository.deleteById(id)` |
| AIX rebuild via `TRANIDX` (DELETE → DEFINE → BLDINDEX → DEFINE PATH) | Replaced by PostgreSQL B-tree `@Index` maintained automatically by the database — no rebuild step needed |
| Consistent lock ordering `CUSTOMER → ACCOUNT → CARD → TRANSACTION` | Preserved in service methods to prevent deadlocks — acquire row locks in the same order |
| Sequential full-file scan (avoided online; OK in batch) | Spring Batch `JdbcCursorItemReader` for streaming throughput |
| Read-for-update with positioned browse | Service obtains entity via `findById` within `@Transactional` scope; JPA dirty-checking + `@Version` provides equivalent semantics |

**Three concrete AIX → `@Index` mappings:**

- `CARDDATA.AIX` on `CARD-ACCT-ID` position 16 length 11 → `@Index(name="idx_card_account_id", columnList="account_id")` on `Card` entity
- `CARDXREF.AIX` on `XREF-ACCT-ID` position 25 length 11 → `@Index(name="idx_xref_account_id", columnList="account_id")` on `CardXref` entity
- `TRANSACT.AIX` on `TRAN-ORIG-TS` position 304 length 26 → `@Index(name="idx_transaction_orig_ts", columnList="orig_timestamp")` on `Transaction` entity

### 0.6.3 Spring Batch Partitioning and Checkpoint Restart

Each JCL job becomes a Spring Batch `Job` bean. Steps are chunk-oriented for COBOL sequential `PERFORM UNTIL EOF` loops, and tasklet-based for single-operation bulk SQL.

| JCL Job | Step Type | Chunk Size | Reader → Processor → Writer |
|---|---|---|---|
| `POSTTRAN.jcl` (CBTRN02C) | Chunk | 100 | `DalyTranItemReader` (paged) → `TransactionPostingProcessor` (codes 100/101/102/103) → `CompositeItemWriter` (TransactionRepository.save + `TransactionCategoryBalanceUpsertWriter` + `AccountBalanceUpdater`) |
| `INTCALC.jcl` (CBACT04C) | Chunk + chunk + chunk | 100 / 100 / 100 | Step1: TCATBAL reader → interest calculator → in-memory aggregator. Step2: Account reader → `WS-TOTAL-INT` applier → `AccountBalanceUpdater` (REWRITE pattern). Step3: emit interest transactions via `TransactionRepository.save` |
| `COMBTRAN.jcl` (CBTRN03C + SORT/REPRO) | Tasklet | n/a | SQL `INSERT INTO transactions SELECT ... ORDER BY tran_id ON CONFLICT DO UPDATE` |
| `CREASTMT.JCL` (CBSTM03A) | Chunk | 1 per customer | `CustomerItemReader` → `StatementGenerationProcessor` (collects accounts/xrefs/transactions) → `StatementWriter` (emits HTML + plain-text statement files) |
| `DUSRSECJ.jcl` | Tasklet | n/a | Iterates fixed list of 10 users; BCrypts `"PASSWORD"` per user |
| `DataInitializationJobConfig` | Composite (9 chunk-oriented steps chained) | 100 each | `AsciiFixedWidthItemReader` per fixture file → `FixedWidthRecordParser` → corresponding repository |

**Checkpoint / restart:** Spring Batch's `JobRepository` (PostgreSQL-backed via the `BATCH_*` tables created by `spring-boot-starter-batch` auto-schema) provides automatic checkpoint at each chunk boundary. Failed jobs can be restarted via `JobOperator.restart(jobExecutionId)`; processing resumes from the last committed chunk.

**Job parameters:** `INTCALC.jcl PARM='2022071800'` [app/jcl/INTCALC.jcl:L22] becomes a `JobParameters` entry (`tranDate=2022-07-18`) constructed via `JobParametersBuilder`. Job parameters are part of the job-execution identity; rerunning the same job with the same parameters returns the existing `JobExecution` (idempotent), while a different parameter value (e.g., a different date) creates a new execution.

**Composite jobs:** `CREASTMT.JCL` has 4 `EXEC` steps [app/jcl/CREASTMT.JCL:L22-L97]. The Spring Batch equivalent uses `JobBuilder.start(step1).next(step2).next(step3).next(step4).build()`, preserving the JCL execution order.

### 0.6.4 COBOL Fixed-Point Arithmetic → `BigDecimal`

All COBOL `PIC S9(n)V99 COMP-3` (packed-decimal) money fields map to `java.math.BigDecimal` with scale 2. **Float/double is forbidden for any money or rate calculation.**

**The canonical interest formula [app/cbl/CBACT04C.cbl:L462-L470] in COBOL:**

```cobol
COMPUTE WS-MONTHLY-INT
  = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

**Equivalent Java (preserved EXACTLY):**

```java
BigDecimal monthlyInterest = tranCatBal
    .multiply(disIntRate)
    .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);
```

**Rules enforced uniformly:**

- All amount columns use PostgreSQL `NUMERIC(15,2)` (sufficient for `S9(10)V99` = 12 digits + sign + 2 decimal positions; `NUMERIC(15,2)` provides safety margin)
- All `BigDecimal` operations use scale 2 and `RoundingMode.HALF_UP` (matches COBOL `ROUNDED` clause)
- All `BigDecimal` comparisons use `.compareTo(...)` (NEVER `.equals(...)`, which considers scale and would treat `100.00` ≠ `100.0`)
- `BigDecimalUtil` provides reusable constants: `SCALE_TWO = 2`, `HALF_UP = RoundingMode.HALF_UP`, `INTEREST_DIVISOR = BigDecimal.valueOf(1200)`

**Credit-limit check [app/cbl/CBTRN02C.cbl:L393-L422] (validation code 102):**

```cobol
IF ACCT-CREDIT-LIMIT < (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)
   MOVE 102 TO WS-RESP-CD
```

```java
if (account.getCreditLimit().compareTo(
        account.getCurrCycCredit().subtract(account.getCurrCycDebit()).add(dailyTran.getAmount())
    ) < 0) {
    throw new OverlimitException("OVERLIMIT TRANSACTION");
}
```

### 0.6.5 DB2 Timestamp Format Preservation

Both `CBACT04C` and `CBTRN02C` emit timestamps in DB2 external format `YYYY-MM-DD-HH.MM.SS.MIL0000` — 26 characters with millisecond precision and a trailing `0000` literal [app/cbl/CBACT04C.cbl:L613-L626].

**Java equivalent:**

```java
DateTimeFormatter DB2_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS'0000'");
String tranOrigTs = LocalDateTime.now().format(DB2_TS);
```

The 26-character format is applied **only at I/O boundaries** (statement generation, file emission, JSON serialization for compatibility); internally, timestamps are stored as PostgreSQL `TIMESTAMP` and modeled as Java `LocalDateTime`.

**Expiration check [app/cbl/CBTRN02C.cbl:L417-L420] (code 103):**

```cobol
IF ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)
   MOVE 103 TO WS-RESP-CD
```

`DALYTRAN-ORIG-TS(1:10)` extracts the first 10 characters `yyyy-MM-dd` from the 26-character timestamp. Java equivalent:

```java
String tranDate = dailyTran.getOrigTimestamp().substring(0, 10);
if (account.getExpirationDate().compareTo(tranDate) < 0) {
    throw new ExpiredAccountException("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
}
```

### 0.6.6 DALYTRAN Staging Table Strategy

The original `DALYTRAN` is a sequential PS file fed daily into `CBTRN02C` [app/jcl/POSTTRAN.jcl:L23]. Spring Boot cannot directly consume EBCDIC fixed-width PS files; the strategy is:

1. **Pre-step** (existing batch `DailyTransactionReadJobConfig` derived from `CBTRN01C`): reads `app/data/ASCII/dailytran.txt` via `FlatFileItemReader` + `FixedWidthRecordParser`; persists to `daily_transactions` staging table
2. **Main step** (`TransactionPostingJobConfig`): reads `daily_transactions` via `JdbcCursorItemReader`, processes through `TransactionPostingProcessor`, routes via `CompositeItemWriter`:
   - Accepted → `TransactionRepository.save` + `TransactionCategoryBalanceUpsertWriter` + `AccountBalanceUpdater`
   - Rejected → `RejectedTransactionRepository.save` (DALYREJS equivalent — 350-byte transaction + 80-byte reject metadata = 430 bytes)
3. **Post-step** (final tasklet): mark `daily_transactions.processed = true` OR truncate the staging table once all records are accounted for

This pattern enables Spring Batch's chunk-level checkpoint/restart — a failure mid-job can be restarted from the last committed chunk without re-processing accepted transactions.

### 0.6.7 CBSTM03A HTML Emission — Character-by-Character Preservation

`CBSTM03A` emits HTML statements via four paragraphs:

- `5100-WRITE-HTML-HEADER` [app/cbl/CBSTM03A.CBL:L506-L555] — DOCTYPE, bank info table, CSS styling
- `5200-WRITE-HTML-CUSTOMER` — customer name and address block
- `5300-WRITE-HTML-TRANS` — transaction rows with running total
- `5400-WRITE-HTML-FOOTER` — closing tags

**Preservation strategy:** Implement `StatementHtmlBuilder` with one method per COBOL paragraph. Each method emits the **exact same string bytes** as the COBOL `MOVE` + `WRITE` pattern. Two implementation options:

- **Option A — String concatenation:** Manually construct each HTML literal as a Java multi-line string preserving every whitespace character
- **Option B — Thymeleaf template:** Externalize `templates/statement-template.html` containing the literal HTML; render via Thymeleaf with model attributes

**Parity test (`StatementGenerationParityTest`):** Generate a statement via Java for a known reference dataset; compare against a stored COBOL reference output file using byte-for-byte equality. Any byte difference fails the test.

The `EVALUATE/ALTER` dispatch pattern in `CBSTM03A` [app/cbl/CBSTM03A.CBL:L300-L315] (which dispatches I/O by DD name) is replaced by a Java `enum StatementSection` with a `switch` expression OR a Strategy Pattern with named `SectionWriter` beans.

### 0.6.8 BCrypt Migration for Default Users

Tech Spec §6.4 documents 10 default users — `ADMIN001`-`ADMIN005` (ROLE_ADMIN) and `USER0001`-`USER0005` (ROLE_USER) — all sharing the literal password `"PASSWORD"`. The original storage is plaintext in `USRSEC` [app/cpy/CSUSR01Y.cpy:L17-L23] (`SEC-USR-PWD PIC X(08)`).

**Seeding strategy** (executed by either `V4__seed_users.sql` Flyway script OR `UserSeedingJobConfig`):

- For each of the 10 default user IDs: compute `BCryptPasswordEncoder.encode("PASSWORD")` and INSERT the resulting 60-character hash into `users.sec_usr_pwd`
- Each user receives a **distinct hash** (BCrypt embeds a 22-character random salt), even though the source password is identical
- `sec_usr_pwd` column type: `VARCHAR(60)` (BCrypt hash length)

**Login authentication** [app/cbl/COSGN00C.cbl:L211-L257] transforms from:

```cobol
IF SEC-USR-PWD = WS-USER-PWD
```

into:

```java
boolean matches = passwordEncoder.matches(rawPassword, user.getSecUsrPwd());
```

executed inside Spring Security's `DaoAuthenticationProvider`. The `FUNCTION UPPER-CASE` entropy reduction in COSGN00C [app/cbl/COSGN00C.cbl:L108-L140] is applied **only to the user ID** (preserving the uppercase ID convention `ADMIN001`/`USER0001`), **never to the password** — preserving case sensitivity on password input.

### 0.6.9 @PreAuthorize Closing the Programmatic Auth Gap

Per Tech Spec §6.4, the original `COUSR00C`-`COUSR03C` programs have **zero programmatic authorization checks**. Access is enforced only by menu routing in `COADM01C` (which is an admin-only menu). However, a malicious actor could invoke transaction ID `CUSR` directly (bypassing the menu) and gain unauthorized access to the user-administration functions.

**Mitigation in Java:**

```java
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")  // class-level enforcement
public class UserController {
    @GetMapping
    public List<UserDto> listUsers() { ... }
    
    @PostMapping
    public UserDto createUser(@Valid @RequestBody UserCreateRequest req) { ... }
    // ... etc.
}
```

`MethodSecurityConfig` enables `@PreAuthorize` enforcement with `@EnableMethodSecurity(prePostEnabled = true)`. The same pattern is applied to `BatchAdminController`. This is an explicitly required improvement — the existing CICS deployment relied on menu routing only; the stateless REST environment must enforce authorization programmatically because clients (curl, Postman, malicious scripts) can target any endpoint directly.

### 0.6.10 Transaction ID Generation (16 chars)

`CBACT04C` generates transaction IDs as `PARM-DATE (10 chars) + WS-TRANID-SUFFIX (6 chars)` [app/cbl/CBACT04C.cbl:L473-L500]. The 10-char prefix is the job parameter date; the 6-char suffix is a sequential counter starting at `000001` and incrementing per posted interest transaction.

**Java implementation (`TransactionIdGenerator`):**

```java
public String nextId(String parmDate) {
    return "%s%06d".formatted(parmDate, counter.incrementAndGet());
}
```

- For **batch jobs** (interest calculation), the counter is a per-`JobExecution` `AtomicLong` initialized to 0; restart-on-failure preserves the counter via Spring Batch `ExecutionContext`
- For **online transactions** (the `POST /api/transactions` endpoint), the counter is backed by a PostgreSQL sequence `transaction_id_seq` to guarantee uniqueness across concurrent requests
- The 16-character `tran_id` column is declared `VARCHAR(16) NOT NULL UNIQUE`

### 0.6.11 DISCGRP DEFAULT Fallback Pattern

`CBACT04C` [app/cbl/CBACT04C.cbl:L415-L440] looks up the interest rate by `ACCT-GROUP-ID + TRAN-TYPE-CD + TRAN-CAT-CD`; if not found (status `'23'`), it retries with `ACCT-GROUP-ID = 'DEFAULT'`. This fallback is preserved exactly:

```java
@Transactional(readOnly = true)
public BigDecimal lookupRate(String groupId, String typeCd, String catCd) {
    DisclosureGroupId key = new DisclosureGroupId(groupId, typeCd, catCd);
    Optional<DisclosureGroup> result = disclosureGroupRepository.findById(key);
    if (result.isEmpty()) {
        DisclosureGroupId fallback = new DisclosureGroupId("DEFAULT", typeCd, catCd);
        result = disclosureGroupRepository.findById(fallback);
    }
    return result
        .map(DisclosureGroup::getDisIntRate)
        .orElseThrow(() -> new DiscloseGroupNotFoundException(
            "No DEFAULT entry for type=" + typeCd + " cat=" + catCd));
}
```

This logic is implemented inside `InterestCalculationTasklet` (or a delegate `DiscloseGroupLookupService`) and exercised by the `InterestCalculationParityTest`.

### 0.6.12 Cross-Cutting Concerns

| Concern | COBOL/CICS Pattern | Java/Spring Implementation |
|---|---|---|
| Unit-of-work boundaries | `EXEC CICS SYNCPOINT` (implicit at task end) | `@Transactional` on service methods that modify multiple entities |
| Exception → HTTP mapping | `EIBRESP` + `WS-RESP-CD` codes | `GlobalExceptionHandler` `@ControllerAdvice` mapping each exception to the appropriate HTTP status (AccountNotFoundException → 404, InvalidCardException → 400, OverlimitException → 422, ExpiredAccountException → 422, OptimisticLockException → 409, AccessDeniedException → 403) |
| Logging | `DISPLAY` statements + CICS journal | SLF4J + Logback; INFO for business events; DEBUG for entity load/save; structured JSON appender in `application-prod.yml` |
| Auditing | None (Tech Spec §6.4 documents the audit-log gap) | `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy` via Spring Data JPA `AuditingEntityListener` — addresses the documented gap |
| N+1 query avoidance | Manual `READ` orchestration | `@EntityGraph` or fetch joins on `Account → CardXref → Card` paths |
| Batch streaming | VSAM `STARTBR` / `READNEXT` | Spring Batch `JdbcCursorItemReader` for the largest tables (TRANSACT, TCATBAL) |
| Concurrent session control | None (per Tech Spec §6.4) | Spring Security `SessionRegistry` (out of scope as deliverable; deferred to operational hardening) |

### 0.6.13 Secondary Index Implementation

VSAM `TRANIDX` job [app/jcl/TRANIDX.jcl] rebuilds the `TRANSACT.AIX` alternate index via the IDCAMS sequence `DELETE → DEFINE → BLDINDEX → DEFINE PATH`. This entire job is **replaced by PostgreSQL's automatic B-tree index maintenance** — once `idx_transaction_orig_ts` is created via Flyway `V2__indexes.sql`, PostgreSQL maintains it transparently on every INSERT/UPDATE/DELETE. No periodic rebuild is needed.

**Index naming convention:** `idx_<table>_<column(s)>` (snake_case to match PostgreSQL conventions):

- `idx_card_account_id` — replaces `CARDDATA.AIX`
- `idx_xref_account_id` — replaces `CARDXREF.AIX`
- `idx_transaction_orig_ts` — replaces `TRANSACT.AIX`

### 0.6.14 GDG Backups → PostgreSQL `pg_dump`

GDG (Generation Data Group) backup datasets in the original system (`TRANSACT.BKUP` 76 generations daily, `TCATBALF.BKUP` 5 generations weekly, `DALYREJS` 5 generations) [Tech Spec §6.2] are replaced operationally by PostgreSQL `pg_dump`. The `TransactionBackupJobConfig` (derived from `TRANBKP.jcl`) wraps `pg_dump` via `ProcessBuilder` inside a Spring Batch tasklet.

**Note:** Cron / k8s CronJob scheduling for backup retention is documented as an operational pattern but is **out of scope as a code deliverable**. The infrastructure team configures backup schedules separately.

### 0.6.15 Schema Versioning

Original COBOL copybooks carry version headers in their comments (e.g., `CardDemo_v1.0-15-g27d6c6f-68`). The Java equivalent is:

- Flyway `flyway_schema_history` table — tracks every applied migration with checksum, timestamp, success flag
- Each Java entity carries a JavaDoc reference to its source copybook: `/** Maps app/cpy/CVACT01Y.cpy 300-byte ACCOUNT-RECORD (CardDemo_v1.0-15-g27d6c6f-68) */`
- Flyway migration scripts contain a header comment citing the source copybook and version

This preserves traceability from each Java entity / Flyway migration back to the original COBOL copybook definition.

## 0.7 Refactoring Rules

### 0.7.1 Refactoring-Specific Preservation Rules

The following rules are explicitly required by the prompt or implied by the "preserve business logic exactly" mandate. **Every downstream code-generation operation must satisfy every rule below.**

**Functional parity rules:**

- **PR-01 — Interest formula preserved line-by-line:** `CBACT04C` paragraph `1300-COMPUTE-INTEREST` [app/cbl/CBACT04C.cbl:L462-L470] must produce the same `WS-MONTHLY-INT` value in Java for every `(TRAN-CAT-BAL, DIS-INT-RATE)` input pair. The formula `(TRAN-CAT-BAL * DIS-INT-RATE) / 1200` translates to `tranCatBal.multiply(disIntRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)`.
- **PR-02 — DISCGRP DEFAULT fallback preserved:** `CBACT04C` paragraph `1200-GET-INTEREST-RATE` [app/cbl/CBACT04C.cbl:L415-L440] must retry with `ACCT-GROUP-ID = 'DEFAULT'` when the initial lookup returns status `'23'`. Java service must call `disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", typeCd, catCd))` on `Optional.empty()`.
- **PR-03 — Transaction validation codes preserved exactly:** `CBTRN02C` paragraph `1500-VALIDATE-TRAN` [app/cbl/CBTRN02C.cbl:L370-L422] must produce codes 100/101/102/103 with the exact original messages — `"INVALID CARD NUMBER FOUND"`, account-not-found, `"OVERLIMIT TRANSACTION"`, `"TRANSACTION RECEIVED AFTER ACCT EXPIRATION"`.
- **PR-04 — Credit-limit formula preserved:** `CBTRN02C` [app/cbl/CBTRN02C.cbl:L393-L422] code 102 condition `ACCT-CREDIT-LIMIT < (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)` must be implemented in Java using `BigDecimal.compareTo` with identical operand order.
- **PR-05 — Expiration check preserved:** `CBTRN02C` [app/cbl/CBTRN02C.cbl:L417-L420] code 103 condition `ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)` must compare against the first 10 characters of the daily-transaction timestamp.
- **PR-06 — TCATBAL upsert pattern preserved:** `CBTRN02C` paragraph `2700-UPDATE-TCATBAL` [app/cbl/CBTRN02C.cbl:L467-L501] semantics — on `INVALID KEY` create a new record with the composite key and `TRAN-CAT-BAL = DALYTRAN-AMT`; otherwise update existing record by adding `DALYTRAN-AMT` to `TRAN-CAT-BAL`.
- **PR-07 — Account sign-based bucket preserved:** `CBTRN02C` paragraph `2800-UPDATE-ACCOUNT-REC` [app/cbl/CBTRN02C.cbl:L545-L560] semantics — `DALYTRAN-AMT >= 0` adds to `ACCT-CURR-CYC-CREDIT`, else adds to `ACCT-CURR-CYC-DEBIT`; `ACCT-CURR-BAL += DALYTRAN-AMT` regardless of sign.
- **PR-08 — Account REWRITE pattern preserved:** `CBACT04C` paragraph `1050-UPDATE-ACCOUNT` [app/cbl/CBACT04C.cbl:L350-L370] semantics — `ACCT-CURR-BAL += WS-TOTAL-INT`, then zero out both `ACCT-CURR-CYC-CREDIT` and `ACCT-CURR-CYC-DEBIT`, then REWRITE.
- **PR-09 — Statement HTML structure preserved byte-for-byte:** `CBSTM03A` paragraph `5100-WRITE-HTML-HEADER` [app/cbl/CBSTM03A.CBL:L506-L555] HTML literals (DOCTYPE, table structure, bank info, CSS styling) must be reproducible character-by-character.
- **PR-10 — Transaction ID format preserved:** 16 characters total — first 10 from `PARM-DATE`, last 6 from a sequential suffix counter starting at `000001` [app/cbl/CBACT04C.cbl:L473-L500].
- **PR-11 — DB2 timestamp format preserved:** `YYYY-MM-DD-HH.MM.SS.MIL0000` (26 chars) for `TRAN-ORIG-TS` and `TRAN-PROC-TS` [app/cbl/CBACT04C.cbl:L613-L626]. Java uses `DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS'0000'")`.
- **PR-12 — Batch sequence preserved:** The critical batch sequence `POSTTRAN → INTCALC → COMBTRAN → CREASTMT` must be reproducible by the `BatchAdminController` (manual launch) or a composite `Job` bean chaining all four. Each individual Spring Batch `Job` remains independently runnable.

**Data integrity rules:**

- **PR-13 — Record-length fidelity:** Each entity's column lengths must mirror the COBOL `PIC` clauses (e.g., `CARD-NUM PIC X(16)` → `card_num VARCHAR(16)`; `CARD-CVV-CD PIC 9(3)` → `card_cvv_cd VARCHAR(3)` or `INTEGER` with check constraint).
- **PR-14 — Sign-fixed string preservation:** `ACCT-EXPIRAION-DATE` [sic-spelling in COBOL — app/cpy/CVACT01Y.cpy:L4-L17] is preserved verbatim in DB column name (`expiration_date`) and Java field name (`expirationDate`); the COBOL misspelling does not propagate to Java because COBOL-to-Java naming converts to standard English camelCase.
- **PR-15 — Composite key fidelity:** `TCATBAL` (account + type + category), `DISCGRP` (group + type + category), `TRANCATG` (type + category) all use `@EmbeddedId` with the same field order as the COBOL key concatenation [app/cpy/CVTRA01Y.cpy:L4-L10, app/cpy/CVTRA02Y.cpy:L4-L10, app/cpy/CVTRA04Y.cpy:L4-L9].
- **PR-16 — BigDecimal everywhere for money:** No `float`, `double`, or `Float`/`Double` for any monetary field; uniformly `BigDecimal` with scale 2 and `RoundingMode.HALF_UP`.

**Security rules:**

- **PR-17 — BCrypt for all passwords:** No plaintext passwords stored, transmitted, or compared. Initial seeding rehashes literal `"PASSWORD"` for the 10 default users.
- **PR-18 — Method-level `@PreAuthorize` on admin endpoints:** All `UserController` endpoints, all `BatchAdminController` endpoints, and any other administrative endpoint require `@PreAuthorize("hasRole('ADMIN')")`. This closes the documented gap where the original COUSR00C-03C programs had no programmatic auth check.
- **PR-19 — Role mapping fidelity:** `userType = 'A'` → `ROLE_ADMIN`, `userType = 'U'` → `ROLE_USER`. The mapping is performed by `CustomAuthorityMapper` and enforced in `UserDetailsServiceImpl`.
- **PR-20 — SSN masking on outbound:** `Customer.ssn` is never returned in plain form via REST. `CustomerMapper` masks it as `***-**-####` (last 4 digits visible) in `CustomerDto`.

**Behavioral preservation rules:**

- **PR-21 — Parity tests required:** The three critical batch programs must have dedicated parity tests:
    - `InterestCalculationParityTest` — verifies `monthlyInterest` for a canonical input matrix
    - `TransactionPostingParityTest` — verifies all four validation codes with exact message strings, TCATBAL upsert semantics, and sign-based balance update
    - `StatementGenerationParityTest` — byte-for-byte HTML output comparison
- **PR-22 — Optimistic locking on concurrent updates:** `Account`, `Card`, `Customer`, `Transaction` entities use `@Version` for optimistic locking; concurrent updates raise `OptimisticLockException` → HTTP 409 Conflict.
- **PR-23 — Lock ordering preserved:** Service methods that lock multiple entities acquire locks in the consistent order `CUSTOMER → ACCOUNT → CARD → TRANSACTION` to prevent deadlocks (matches the documented VSAM convention).
- **PR-24 — `@Transactional` boundaries preserve UOW:** Any multi-entity write operation runs inside a `@Transactional` method scope, matching the implicit CICS `SYNCPOINT` semantics.

**Architectural rules:**

- **PR-25 — Single monolith:** No microservices decomposition, no message queues, no event-streaming infrastructure. Per the prompt's "make as few architectural decisions as possible" constraint.
- **PR-26 — No new external runtime services:** No S3, no SQS, no Lambda, no Cognito, no Kafka, no Redis. Local PostgreSQL + filesystem only.
- **PR-27 — Original sources preserved:** All `app/cbl/*.cbl`, `app/jcl/*.jcl`, `app/cpy/*.cpy`, `app/cpy-bms/*.CPY`, `app/bms/*.bms`, `app/csd/*.CSD`, `app/catlg/*.txt`, `app/proc/*.prc`, `app/ctl/*.ctl`, and `app/data/ASCII/*.txt` files are preserved unchanged. None are deleted; none are modified.
- **PR-28 — Jakarta EE namespace:** All persistence and validation annotations use `jakarta.*` (NOT `javax.*`) — required by Spring Boot 3.x baseline.
- **PR-29 — Constructor injection only:** No `@Autowired` field injection. All beans use constructor injection (typically via Lombok `@RequiredArgsConstructor` over `final` fields).
- **PR-30 — Single-phase delivery:** The entire refactor is delivered atomically in one phase (per the prompt's "ONE phase" constraint).

### 0.7.2 Special Instructions and Constraints

- **CRITICAL — No feature additions:** The Java refactor must not add any feature not present in the original CardDemo. No new endpoints beyond those required to mirror existing CICS programs and JCL jobs. The only intentional behavioral additions are the security improvements explicitly required by the prompt (BCrypt + `@PreAuthorize`).
- **CRITICAL — No data model redesign:** Flat-file-to-relational mapping is the only allowed structural change. Foreign-key relationships between entities (e.g., `Card → Account`, `CardXref → Card/Customer/Account`) are introduced where the COBOL `CARD-ACCT-ID`, `XREF-ACCT-ID`, etc. fields make the relationship explicit. No additional tables, no denormalization, no consolidation of existing tables.
- **CRITICAL — No CICS / VSAM behavior changes beyond migration mechanics:** Anything documented in `app/csd/CARDDEMO.CSD`, `app/catlg/LISTCAT.txt`, or the COBOL source itself defines the contract. Modernization (security improvements, JPA optimistic locking instead of VSAM exclusive locks, PostgreSQL indexes instead of AIX) is permitted only where the original behavior cannot be expressed safely in the target stack.
- **CRITICAL — Maintain public API contracts:** The 9 REST controllers map to the 17 CICS programs by functional domain. Within each domain, endpoint URIs, request/response shapes (DTOs), and validation rules must comprehensively cover all input fields and output fields defined by the corresponding BMS map and COBOL program working-storage.
- **CRITICAL — Preserve test coverage:** The three parity tests (`InterestCalculationParityTest`, `TransactionPostingParityTest`, `StatementGenerationParityTest`) are mandatory deliverables. Without them, the "preserve business logic exactly" mandate cannot be verified.
- **Migration scope:** Same repository — Java sources colocated under new `src/` tree; original `app/` tree preserved unchanged.
- **No backward compatibility shim:** The Spring Boot deployment replaces the CICS/JCL deployment entirely. There is no requirement to support simultaneous operation of both systems. The original COBOL is preserved as REFERENCE for testing, not as a runtime fallback.
- **Performance:** The prompt does not specify performance targets. Spring Batch chunk sizes default to 100; tuning is deferred to operational hardening. The default Hibernate connection pool (HikariCP managed by Spring Boot, default 10 connections) is acceptable for the demonstration-grade workload (50 accounts, 311 transactions, 100 TCATBAL records).

### 0.7.3 User-Provided Rules

The user-supplied rules list is empty (no implementation rules were provided in the project configuration). All rules in this section derive from the prompt's explicit text and the implicit requirements surfaced from the original system's documented behavior. No conflicts exist between prompt, attachments, and rules because both attachments and rules are empty — the prompt is the sole authoritative source.

## 0.8 Attachments

**No attachments were provided for this project.**

The project configuration contains zero file attachments — no PDFs, no images, no Figma URLs, no design specifications, and no reference documents beyond the prompt itself. The user-specified rules list is also empty. All authoritative direction for this refactor derives from:

- The prompt text (capturing the migration objective, target stack, preservation requirements, scope, and constraints)
- The existing repository contents under `app/` (COBOL programs in `app/cbl/`, JCL jobs in `app/jcl/`, copybooks in `app/cpy/`, BMS maps in `app/bms/`, BMS symbolic copybooks in `app/cpy-bms/`, CICS resource definitions in `app/csd/`, VSAM catalog in `app/catlg/`, ASCII fixtures in `app/data/ASCII/`, JCL procs in `app/proc/`, control cards in `app/ctl/`)
- The Technical Specification sections retrieved during context gathering — §1.2 (System Overview), §2.1 (Feature Catalog), §3.9 (Technology Stack Summary), §4.1 (Process Architecture), §5.1 (High-Level Architecture), §6.2 (Database Design), and §6.4 (Security Architecture) — which document the existing CardDemo system's architecture, data model, security posture, and integration patterns

No Figma frames, URLs, or design system specifications are present. The migration is backend-only (REST API), and no UI design system selection is required.

