# CardDemo COBOL → Java Migration Mapping

This document is the per-file mapping companion to the Agent Action Plan (AAP)
§0.4.1 (Transformation Mapping) and §0.6 (Special Analysis). It is referenced
from the root [`README.md`](../README.md) "Java Spring Boot 3.2 Modernization"
section and is intended for developers, reviewers, and auditors who need to
trace **where each original COBOL/CICS/VSAM artifact went** in the modernized
Java Spring Boot 3.2 codebase — and **why** each transformation was made.

For every source program, job, and copybook this document records:

1. **What it became** — the target Java class(es), Spring bean(s), or
   PostgreSQL/Flyway artifact(s).
2. **The transformation rationale** — the preservation rule(s), validation
   codes, formulas, and cross-cutting concerns that govern the migration.
3. **Traceability** — exact source line numbers so the document can be read
   side-by-side with the COBOL source during post-migration verification.

The entire refactor is delivered atomically in a **single phase** (PR-30): the
original `app/` tree (COBOL, JCL, copybooks, BMS, CSD, ASCII fixtures) is
**preserved unchanged as REFERENCE** (PR-27) while the new `src/` tree, `pom.xml`,
Flyway migrations, and this `docs/` companion constitute the deliverable. The
target is a **single Spring Boot monolith** — no microservices, no message
queues, no event streaming (PR-25) — backed by **local PostgreSQL 15 and the
filesystem only**, with no external cloud runtime services (PR-26).

> **Authority:** Where this document and the AAP disagree, **the AAP wins**.
> Discovered discrepancies are recorded in the [Caveats](#caveats) sub-section
> at the end of this document.

## Table of Contents

1. [Header and Introduction](#cardemo-cobol--java-migration-mapping) *(this section)*
2. [Cross-Cutting Mappings Overview](#section-2-cross-cutting-mappings-overview)
3. [CICS Online Programs → REST Controllers + Services](#section-3-cics-online-programs--rest-controllers--services)
4. [Batch COBOL Programs → Spring Batch Jobs](#section-4-batch-cobol-programs--spring-batch-jobs)
5. [JCL Jobs → Spring Batch Job beans](#section-5-jcl-jobs--spring-batch-job-beans)
6. [Copybooks → JPA Entities, DTOs, Constants](#section-6-copybooks--jpa-entities-dtos-constants)
7. [BMS Symbolic Copybooks → DTO Field Shape](#section-7-bms-symbolic-copybooks--dto-field-shape-reference-only)
8. [Repositories, Mappers, Exceptions, Configuration](#section-8-repositories-mappers-exceptions-configuration-quick-reference)
9. [Build, Configuration, Flyway Migrations](#section-9-build-configuration-flyway-migrations)
10. [Tests and Parity Verification](#section-10-tests-and-parity-verification)
11. [Reference Files (Preserved Unchanged)](#section-11-reference-files-preserved-unchanged)

Plus: [Caveats](#caveats) and the [Preservation Rule Coverage Matrix](#preservation-rule-coverage-matrix).

### Scope and Repository Inventory

This mapping covers the **complete** original CardDemo mainframe inventory:

| Artifact Class | Count | Location | Migration Mode |
| :------------- | :---: | :------- | :------------- |
| COBOL programs (17 online `CO*` + 11 batch `CB*`/`CSUTLDTC`) | 28 | `../app/cbl/` | Transformed to controllers/services + Spring Batch jobs |
| JCL jobs | 29 | `../app/jcl/` | Transformed to Spring Batch `Job` beans (some no-op) |
| Copybooks | 28 | `../app/cpy/` | Transformed to JPA entities / DTOs / constants |
| BMS symbolic copybooks | 17 | `../app/cpy-bms/` | REFERENCE (feed DTO field shapes) |
| BMS map sources | 17 | `../app/bms/` | REFERENCE (no UI replacement) |
| CICS resource definitions | 1 | `../app/csd/CARDDEMO.CSD` | REFERENCE (transaction routing) |
| VSAM catalog snapshot | 1 | `../app/catlg/LISTCAT.txt` | REFERENCE (key positions, AIX config) |
| ASCII fixed-width fixtures | 9 | `../app/data/ASCII/` | INPUT to `DataInitializationJobConfig` |

> **File-extension case is significant** in this repository and is preserved
> exactly in every cross-reference below. Notable uppercase exceptions:
> `CBSTM03A.CBL`, `CBSTM03B.CBL` (`.CBL`); `CREASTMT.JCL` (`.JCL`);
> `COSTM01.CPY` and all 17 `../app/cpy-bms/*.CPY` (`.CPY`). All other COBOL,
> JCL, and copybook files use lowercase extensions.

---

## Section 2: Cross-Cutting Mappings Overview

This section consolidates the 15 cross-cutting concerns analyzed in AAP §0.6.
Each row maps a recurring COBOL/CICS/VSAM construct to its uniform
Java/Spring counterpart, citing the governing preservation rule (PR) and the
authoritative AAP sub-section.

| COBOL/CICS/VSAM Construct | Java/Spring Equivalent | Rule | AAP Reference |
| :------------------------ | :--------------------- | :--- | :------------ |
| CICS pseudo-conversational COMMAREA (`COCOM01Y` 1024 bytes) | Spring Security `SecurityContextHolder` + HTTP path/query params + DTO context fields | — | §0.6.1 |
| `EXEC CICS XCTL PROGRAM(...)` chaining | Stateless REST response; client controls navigation | — | §0.6.1 |
| `EXEC CICS RETURN TRANSID(...)` | HTTP response (next-state hint via body field) | — | §0.6.1 |
| `EXEC CICS READ DATASET(...)` keyed | `JpaRepository.findById(...)` | — | §0.6.2 |
| VSAM AIX alternate index | JPA `@Table(indexes=@Index(name=..., columnList=...))` + finder method | — | §0.6.2, §0.6.13 |
| `EXEC CICS READ UPDATE` exclusive lock | JPA `@Version` optimistic locking (raises `OptimisticLockException` → HTTP 409) | PR-22 | §0.6.2 |
| `EXEC CICS WRITE` | `repository.save(transientEntity)` | — | §0.6.2 |
| `EXEC CICS REWRITE` | `repository.save(managedEntity)` | — | §0.6.2 |
| `EXEC CICS SYNCPOINT` (implicit at task end) | `@Transactional` method scope | PR-24 | §0.6.12 |
| `INVALID KEY` handler (file status `'23'`) | `Optional.empty()` from `findById`; service throws domain exception → HTTP status via `GlobalExceptionHandler` | — | §0.6.2 |
| `STARTBR` / `READNEXT` / `READPREV` / `ENDBR` browse cursor | Spring Data `Pageable` (`PageRequest.of(page, size, Sort.by(...))`) | — | §0.6.1 |
| `EXEC CICS WRITEQ TD QUEUE('JOBS')` (`CORPT00C`) | `JobLauncher.run(...)` with `@Async` | — | §0.6.1 |
| COBOL `PIC S9(n)V99 COMP-3` (packed-decimal money) | `java.math.BigDecimal` scale 2, `RoundingMode.HALF_UP` | PR-16 | §0.6.4 |
| DB2 timestamp `YYYY-MM-DD-HH.MM.SS.MIL0000` (26 chars) | `DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS'0000'")` | PR-11 | §0.6.5 |
| DALYTRAN PS sequential file | `daily_transactions` staging table fed by `DailyTransactionReadJobConfig` | — | §0.6.6 |
| RACF + plaintext `USRSEC` compare | Spring Security 6 + BCrypt + `DaoAuthenticationProvider` | PR-17 | §0.6.8 |
| User type `'A'` / `'U'` (`COCOM01Y` L26-28; `CSUSR01Y` L22) | `ROLE_ADMIN` / `ROLE_USER` via `CustomAuthorityMapper` | PR-19 | §0.6.8 |
| Menu-only authorization (`COADM01C` → `COUSR00C`-`03C`) | Method-level `@PreAuthorize("hasRole('ADMIN')")` — closes documented programmatic-auth gap | PR-18 | §0.6.9 |
| `TRANIDX` VSAM AIX rebuild job | Replaced by PostgreSQL B-tree `@Index` (auto-maintained) | — | §0.6.13 |
| GDG backup (`TRANSACT.BKUP`, etc.) | PostgreSQL `pg_dump` via tasklet in `TransactionBackupJobConfig` | — | §0.6.14 |
| JCL job `EXEC PGM=...,PARM='...'` | `JobLauncher.run(job, jobParameters)` with `JobParameters` derived from `PARM` | — | §0.6.3 |
| Critical batch sequence `POSTTRAN → INTCALC → COMBTRAN → CREASTMT` | `BatchAdminController` manual launch OR composite chained `Job` bean | PR-12 | §0.6.3 |
| Schema version header (`CardDemo_v1.0-15-g27d6c6f-68`) | Flyway `flyway_schema_history` + per-entity JavaDoc copybook citation | — | §0.6.15 |

### 2.1 VSAM Alternate Index → JPA `@Index` (three concrete mappings)

The three VSAM AIX alternate indexes (AAP §0.6.13) become PostgreSQL B-tree
secondary indexes declared via JPA `@Index`, each paired with a repository
finder method. PostgreSQL maintains these automatically on every
`INSERT`/`UPDATE`/`DELETE`, so the original `TRANIDX` rebuild job
(`DELETE → DEFINE → BLDINDEX → DEFINE PATH`) has **no Java artifact**.

| VSAM AIX | Key Field (position/length) | JPA `@Index` | Entity / Column | Finder |
| :------- | :-------------------------- | :----------- | :-------------- | :----- |
| `CARDDATA.AIX` | `CARD-ACCT-ID` (pos 16, len 11) | `idx_card_account_id` | `Card` / `account_id` | `CardRepository.findByAccountId(...)` |
| `CARDXREF.AIX` | `XREF-ACCT-ID` (pos 25, len 11) | `idx_xref_account_id` | `CardXref` / `account_id` | `CardXrefRepository.findByAccountId(...)` |
| `TRANSACT.AIX` | `TRAN-ORIG-TS` (pos 304, len 26) | `idx_transaction_orig_ts` | `Transaction` / `orig_timestamp` | `TransactionRepository.findByOrigTimestampBetween(...)` |

### 2.2 Concurrency, Transactions, and Architecture

These rules govern every multi-entity write and the overall deployment shape:

- **PR-22 — Optimistic locking:** `Account`, `Card`, `Customer`, and
  `Transaction` carry a `@Version` field. Concurrent updates raise
  `OptimisticLockException`, mapped to **HTTP 409 Conflict**. This replaces
  VSAM `READ UPDATE` exclusive (CI-level) locks with safer non-blocking
  concurrency.
- **PR-23 — Lock ordering:** Service methods that lock multiple entities
  acquire them in the consistent order **`CUSTOMER → ACCOUNT → CARD →
  TRANSACTION`** to prevent deadlocks (matching the documented VSAM
  convention).
- **PR-24 — Unit-of-work boundaries:** Every multi-entity write runs inside a
  `@Transactional` method scope, preserving the implicit CICS `SYNCPOINT`
  semantics that bracketed each pseudo-conversational interaction.
- **PR-25 — Single monolith:** No microservices decomposition, no message
  queues, no event-streaming infrastructure.
- **PR-26 — No external runtime services:** Local PostgreSQL 15 + filesystem
  only. The lone external-process invocation is `pg_dump` (a local OS process
  via `ProcessBuilder`) in `TransactionBackupJobConfig`.
- **PR-28 — Jakarta EE namespace:** All persistence and validation annotations
  use `jakarta.*` (never `javax.*`), per the Spring Boot 3.x baseline.
- **PR-29 — Constructor injection only:** No `@Autowired` field injection; all
  beans use constructor injection, typically via Lombok
  `@RequiredArgsConstructor` over `final` fields.
- **PR-30 — Single-phase delivery:** The entire refactor is produced
  atomically; no incremental rollout, no feature-flag-guarded subsets, no
  shadow mode.

---

## Section 3: CICS Online Programs → REST Controllers + Services

The 17 CICS online programs (`CO*` prefix) become 9 REST controllers plus
their backing services, organized by functional domain (AAP §0.4.1.1). CICS
pseudo-conversational state (`COMMAREA`) is decomposed into Spring Security
context, HTTP path/query parameters, and request DTO fields; `EXEC CICS XCTL`
program chaining is replaced by stateless REST responses where the client
controls navigation.

| Source (COBOL) | Target Controller | Target Service | Endpoint(s) | Key Transformation Notes |
| :------------- | :---------------- | :------------- | :---------- | :----------------------- |
| `../app/cbl/COSGN00C.cbl` | `AuthController` (`../src/main/java/com/carddemo/controller/AuthController.java`) | `AuthService` | `POST /api/auth/login`, `POST /api/auth/logout` | **PR-17 BCrypt migration:** Replace the plaintext compare `IF SEC-USR-PWD = WS-USER-PWD` (≈ L223) with `BCryptPasswordEncoder.matches(rawPwd, storedHash)` inside `DaoAuthenticationProvider`. Returns a JWT bearer token carrying a `userType` claim. The role routing performed in COBOL via `EXEC CICS XCTL` (≈ L230-240: ADMIN → `COADM01C`, USER → `COMEN01C`) becomes a JWT claim consumed by `MenuController`. `FUNCTION UPPER-CASE` is applied to the **user ID only**, never the password. Reference: `../app/cbl/COSGN00C.cbl` L209-257. |
| `../app/cbl/COMEN01C.cbl` + `../app/cbl/COADM01C.cbl` | `MenuController` | (none — reads `SecurityContextHolder`) | `GET /api/menu` | Returns a role-filtered menu option list. ADMIN sees user-administration entries; USER does not. Static menu config is sourced from `../app/cpy/COMEN02Y.cpy` (user) and `../app/cpy/COADM02Y.cpy` (admin). |
| `../app/cbl/COACTVWC.cbl` + `../app/cbl/COACTUPC.cbl` | `AccountController` | `AccountService` | `GET /api/accounts/{acctId}`, `PUT /api/accounts/{acctId}` | Account view + update. Preserves field-level validation (credit limit ≥ 0; `ACCT-EXPIRAION-DATE` format `CCYY-MM-DD`). |
| `../app/cbl/COCRDLIC.cbl` + `../app/cbl/COCRDSLC.cbl` + `../app/cbl/COCRDUPC.cbl` | `CardController` | `CardService` | `GET /api/accounts/{acctId}/cards` (list), `GET /api/cards/{cardNum}` (view), `PUT /api/cards/{cardNum}` (update) | Pagination via Spring `Pageable` replaces the VSAM `STARTBR DATASET('CARDAIX')` browse cursor (the PF7/PF8 keys). `CardRepository.findByAccountId(Long acctId, Pageable pageable)` exposes the `CARDDATA.AIX` path. |
| `../app/cbl/COACTVWC.cbl` (customer-info portion) | `CustomerController` | `CustomerService` | `GET /api/customers/{custId}` | Customer view. **PR-20:** SSN masked as `***-**-####` in `CustomerMapper` before serialization. |
| `../app/cbl/COTRN00C.cbl` + `../app/cbl/COTRN01C.cbl` + `../app/cbl/COTRN02C.cbl` | `TransactionController` | `TransactionService` | `GET /api/transactions` (list, paginated), `GET /api/transactions/{tranId}` (view), `POST /api/transactions` (online create) | Online transaction create uses `TransactionIdGenerator` (**PR-10**) and a validation chain mirroring the batch `CBTRN02C` codes 100/101/102/103 (**PR-03**). |
| `../app/cbl/COBIL00C.cbl` | `BillPaymentController` | `BillPaymentService` | `POST /api/accounts/{acctId}/payments` | Available credit = `ACCT-CREDIT-LIMIT − ACCT-CURR-BAL`. Creates the payment transaction and updates the balance atomically within a `@Transactional` scope (**PR-24**). |
| `../app/cbl/CORPT00C.cbl` | `ReportController` | `ReportService` | `POST /api/reports` | Replaces `EXEC CICS WRITEQ TD QUEUE('JOBS')` with `JobLauncher.run(jobRegistry.getJob(...), params)` annotated `@Async`; the controller returns **202 Accepted** immediately. |
| `../app/cbl/COUSR00C.cbl` + `../app/cbl/COUSR01C.cbl` + `../app/cbl/COUSR02C.cbl` + `../app/cbl/COUSR03C.cbl` | `UserController` | `UserService` | CRUD under `/api/admin/users/*` | **PR-18 — closes the documented programmatic-auth gap (AAP §0.6.9):** every endpoint is class-annotated `@PreAuthorize("hasRole('ADMIN')")`. The original `COUSR00C`-`COUSR03C` programs had **no** programmatic auth check (they relied on menu routing only — a documented vulnerability). `UserService` applies `BCryptPasswordEncoder.encode(rawPassword)` on create/update (**PR-17**). |

**Program count:** 17 online programs total — `COSGN00C` (1) + `COMEN01C`/`COADM01C` (2) + `COACTVWC`/`COACTUPC` (2) + `COCRDLIC`/`COCRDSLC`/`COCRDUPC` (3) + `COTRN00C`/`COTRN01C`/`COTRN02C` (3) + `COBIL00C` (1) + `CORPT00C` (1) + `COUSR00C`/`COUSR01C`/`COUSR02C`/`COUSR03C` (4). `COACTVWC` appears twice (account view and customer-info view) but is a single source program.

### 3.1 Operational endpoint added by migration

One controller has **no COBOL counterpart** — it is an operational endpoint
introduced by the modernization to launch Spring Batch jobs that previously
ran only via JES/JCL submission:

- `BatchAdminController` (`../src/main/java/com/carddemo/controller/BatchAdminController.java`)
  — `POST /api/admin/jobs/{jobName}/launch` invokes any registered `Job` by
  name through `JobLauncher` + `JobRegistry`. The class is annotated
  `@PreAuthorize("hasRole('ADMIN')")` (**PR-18**), consistent with the
  user-administration endpoints. This is the launch surface for the critical
  batch sequence described in [Section 5](#section-5-jcl-jobs--spring-batch-job-beans).

---

## Section 4: Batch COBOL Programs → Spring Batch Jobs

The 11 batch programs (10 `CB*` programs + `CSUTLDTC`) become Spring Batch
`Job` beans with chunk-oriented steps (for COBOL `PERFORM UNTIL EOF` sequential
loops) or tasklets (for single-operation bulk work), per AAP §0.4.1.2. The
three **"preserve line-by-line"** programs — `CBACT04C`, `CBTRN02C`, and
`CBSTM03A` — are the highest-risk transformations (they compute money or
generate customer-facing artifacts) and carry dedicated parity tests (PR-21).

| Source (COBOL) | Target Spring Batch Components | Preservation Notes |
| :------------- | :----------------------------- | :----------------- |
| `../app/cbl/CBACT04C.cbl` | `InterestCalculationJobConfig` (`../src/main/java/com/carddemo/batch/InterestCalculationJobConfig.java`) + `InterestCalculationTasklet` | **PR-01:** Preserve the interest formula at L462-470 line-by-line (the `COMPUTE` at L464-465). **PR-02:** Preserve the DISCGRP `DEFAULT` fallback in `1200-GET-INTEREST-RATE` at L415-440 (the retry with `'DEFAULT'` at ≈ L435-438 triggered on status `'23'`). **PR-08:** Preserve `1050-UPDATE-ACCOUNT` REWRITE semantics (add `WS-TOTAL-INT` to `ACCT-CURR-BAL`, then zero both `ACCT-CURR-CYC-CREDIT` and `ACCT-CURR-CYC-DEBIT`, then REWRITE). **PR-10:** 16-char `tran_id` generation at L473-500. |
| `../app/cbl/CBTRN02C.cbl` | `TransactionPostingJobConfig` + `TransactionPostingProcessor` + `TransactionCategoryBalanceUpsertWriter` + `AccountBalanceUpdater` | **PR-03:** Validation codes 100/101/102/103 preserved with exact COBOL messages — 100 `"INVALID CARD NUMBER FOUND"` (L386), 101 `"ACCOUNT RECORD NOT FOUND"` (L398), 102 `"OVERLIMIT TRANSACTION"` (L411), 103 `"TRANSACTION RECEIVED AFTER ACCT EXPIRATION"` (L418). **PR-04:** Credit-limit formula L403-405. **PR-05:** Expiration check L414-419. **PR-06:** TCATBAL upsert at L467-501 (`INVALID KEY` → CREATE; otherwise UPDATE). **PR-07:** Sign-based balance bucket at L545-560 (amount ≥ 0 → `CYC-CREDIT`; else → `CYC-DEBIT`; `ACCT-CURR-BAL += amount` regardless of sign). |
| `../app/cbl/CBSTM03A.CBL` | `StatementGenerationJobConfig` + `StatementGenerationTasklet` + `StatementHtmlBuilder` | **PR-09:** HTML byte-for-byte preservation. `5100-WRITE-HTML-HEADER` at L506-555 emits the header HTML via the `SET HTML-Lxx TO TRUE` + `WRITE FD-HTMLFILE-REC` pattern. Implementation options: (a) string concatenation matching every literal byte, or (b) a Thymeleaf template `../src/main/resources/templates/statement-template.html`. Either way, `StatementGenerationParityTest` (**PR-21**) compares output byte-for-byte against a stored COBOL reference. |
| `../app/cbl/CBSTM03B.CBL` | `StatementIoSubroutine` helper (`../src/main/java/com/carddemo/batch/StatementIoSubroutine.java`) | Centralized I/O subroutine for the TRNX/XREF/CUST/ACCT datasets. In Java it becomes a helper class providing centralized lookup semantics via `JpaRepository` calls (no real open/close is needed; the method just encapsulates the lookup logic). |
| `../app/cbl/CBACT01C.cbl` | `AccountFileReadJobConfig` (diagnostic) | Sequentially dumps the `accounts` table for operator inspection. |
| `../app/cbl/CBACT02C.cbl` | `CardFileReadJobConfig` (diagnostic) | Sequentially dumps the `cards` table. |
| `../app/cbl/CBACT03C.cbl` | `XrefFileReadJobConfig` (diagnostic) | Sequentially dumps the `card_xref` table. |
| `../app/cbl/CBCUS01C.cbl` | `CustomerFileReadJobConfig` (diagnostic) | Sequentially dumps the `customers` table. |
| `../app/cbl/CBTRN01C.cbl` | `DailyTransactionReadJobConfig` | Reads `../app/data/ASCII/dailytran.txt` via `FlatFileItemReader` into the `daily_transactions` staging table (AAP §0.6.6 staging strategy — see [§4.4](#44-dalytran-staging-strategy)). |
| `../app/cbl/CBTRN03C.cbl` | `TransactionReadJobConfig` + `TransactionConsolidationJobConfig` | `CBTRN03C` is also referenced by `COMBTRAN.jcl`; the consolidation is replaced by SQL `INSERT INTO transactions SELECT ... ORDER BY tran_id ON CONFLICT DO UPDATE`. |
| `../app/cbl/CSUTLDTC.cbl` | `DateConversionUtil` (`../src/main/java/com/carddemo/util/DateConversionUtil.java`) | CCYYMMDD ↔ MM/DD/YYYY ↔ DB2 timestamp `YYYY-MM-DD-HH.MM.SS.MIL0000` via `DateTimeFormatter` (**PR-11**). The `CEEDAYS` Lillian-day computation is replaced by `LocalDate.toEpochDay()`. |

**Program count:** 11 batch programs — `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBACT04C`, `CBCUS01C`, `CBSTM03A`, `CBSTM03B`, `CBTRN01C`, `CBTRN02C`, `CBTRN03C`, `CSUTLDTC`.

### 4.1 Code Parity Example — Interest Formula

The canonical interest computation must produce identical `WS-MONTHLY-INT`
values for every `(TRAN-CAT-BAL, DIS-INT-RATE)` input pair.

**COBOL** (excerpt from `../app/cbl/CBACT04C.cbl` L462-470):

```cobol
1300-COMPUTE-INTEREST.
    COMPUTE WS-MONTHLY-INT
     = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
    ADD WS-MONTHLY-INT  TO WS-TOTAL-INT
    PERFORM 1300-B-WRITE-TX.
    EXIT.
```

**Java** (`InterestCalculationTasklet`):

```java
BigDecimal monthlyInterest = tranCatBal
    .multiply(disIntRate)
    .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);
totalInterest = totalInterest.add(monthlyInterest);
writeInterestTransaction(...);
```

> **Rules:** **PR-01** (formula preserved exactly), **PR-16** (`BigDecimal`
> scale 2 + `RoundingMode.HALF_UP`, mirroring the COBOL `ROUNDED` semantics;
> never `float`/`double`). The divisor `1200` is centralized as
> `BigDecimalUtil.INTEREST_DIVISOR`.

### 4.2 Code Parity Example — Validation Codes

The validation chain in `1500-VALIDATE-TRAN` (`1500-A-LOOKUP-XREF` →
`1500-B-LOOKUP-ACCT`) yields codes 100/101/102/103 with exact messages.

**COBOL** (excerpt from `../app/cbl/CBTRN02C.cbl` L393-422):

```cobol
1500-B-LOOKUP-ACCT.
    MOVE XREF-ACCT-ID TO FD-ACCT-ID
    READ ACCOUNT-FILE INTO ACCOUNT-RECORD
       INVALID KEY
         MOVE 101 TO WS-VALIDATION-FAIL-REASON
         MOVE 'ACCOUNT RECORD NOT FOUND'
           TO WS-VALIDATION-FAIL-REASON-DESC
       NOT INVALID KEY
         COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                             - ACCT-CURR-CYC-DEBIT
                             + DALYTRAN-AMT
         IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
           CONTINUE
         ELSE
           MOVE 102 TO WS-VALIDATION-FAIL-REASON
           MOVE 'OVERLIMIT TRANSACTION'
             TO WS-VALIDATION-FAIL-REASON-DESC
         END-IF
         IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
           CONTINUE
         ELSE
           MOVE 103 TO WS-VALIDATION-FAIL-REASON
           MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
             TO WS-VALIDATION-FAIL-REASON-DESC
         END-IF
    END-READ.
```

**Java** (`AccountValidator` / `TransactionPostingProcessor`):

```java
Account account = accountRepository.findById(xref.getAcctId())
    .orElseThrow(() -> new AccountNotFoundException("ACCOUNT RECORD NOT FOUND"));  // code 101
BigDecimal tempBal = account.getCurrCycCredit()
    .subtract(account.getCurrCycDebit())
    .add(dailyTran.getAmount());
if (account.getCreditLimit().compareTo(tempBal) < 0) {
    throw new OverlimitException("OVERLIMIT TRANSACTION");  // code 102
}
String tranDatePrefix = dailyTran.getOrigTimestamp().substring(0, 10);
if (account.getExpirationDate().compareTo(tranDatePrefix) < 0) {
    throw new ExpiredAccountException("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");  // code 103
}
```

> **Rules:** **PR-03** (codes + exact messages), **PR-04** (credit-limit
> formula, identical operand order), **PR-05** (expiration check against the
> first 10 chars `yyyy-MM-dd` of the 26-char timestamp), **PR-16**
> (`BigDecimal.compareTo`, never `.equals`, so `100.00` compares equal to
> `100.0`). Code 100 `"INVALID CARD NUMBER FOUND"` (xref lookup miss, L386) is
> raised earlier by `1500-A-LOOKUP-XREF` → `InvalidCardException`.

### 4.3 Code Parity Example — Sign-Based Balance Bucket

The account update splits the amount into credit/debit buckets by sign, then
rewrites the record.

**COBOL** (excerpt from `../app/cbl/CBTRN02C.cbl` L545-560):

```cobol
2800-UPDATE-ACCOUNT-REC.
    ADD DALYTRAN-AMT  TO ACCT-CURR-BAL
    IF DALYTRAN-AMT >= 0
       ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
    ELSE
       ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
    END-IF
    REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
       INVALID KEY
         MOVE 109 TO WS-VALIDATION-FAIL-REASON
         MOVE 'ACCOUNT RECORD NOT FOUND'
           TO WS-VALIDATION-FAIL-REASON-DESC
    END-REWRITE.
    EXIT.
```

**Java** (`AccountBalanceUpdater`):

```java
BigDecimal amount = dailyTran.getAmount();
account.setCurrBal(account.getCurrBal().add(amount));
if (amount.signum() >= 0) {
    account.setCurrCycCredit(account.getCurrCycCredit().add(amount));
} else {
    account.setCurrCycDebit(account.getCurrCycDebit().add(amount));  // adds negative amount per COBOL
}
accountRepository.save(account);
```

> **Rule:** **PR-07** — sign-based bucket. Note that **negative amounts are
> ADDED verbatim** to `ACCT-CURR-CYC-DEBIT` (the COBOL `ADD` of a negative
> value), so the Java must use `.add(amount)` — not `.subtract(...)` — to match
> COBOL semantics exactly. The `REWRITE` maps to `accountRepository.save(...)`
> on the managed entity.

### 4.4 DALYTRAN Staging Strategy

PostgreSQL cannot directly consume the original EBCDIC fixed-width PS file, so
the daily feed is staged (AAP §0.6.6):

1. **Pre-step** (`DailyTransactionReadJobConfig`, from `CBTRN01C`): reads
   `../app/data/ASCII/dailytran.txt` via `FlatFileItemReader` +
   `FixedWidthRecordParser`; persists to the `daily_transactions` staging table.
2. **Main step** (`TransactionPostingJobConfig`, from `CBTRN02C`): reads
   `daily_transactions` via `JdbcCursorItemReader`, processes through
   `TransactionPostingProcessor`, and routes via a `CompositeItemWriter`:
   accepted → `TransactionRepository.save` + `TransactionCategoryBalanceUpsertWriter`
   + `AccountBalanceUpdater`; rejected → `RejectedTransactionRepository.save`
   (the DALYREJS equivalent, 350 + 80 = 430-byte layout).
3. **Post-step**: mark `daily_transactions.processed = true` (or truncate once
   all records are accounted for).

This staging enables Spring Batch chunk-level checkpoint/restart — a mid-job
failure restarts from the last committed chunk without re-posting accepted
transactions.

### 4.5 Transaction ID Generation (dual-mode, PR-10)

`CBACT04C` generates 16-char IDs as `PARM-DATE (10)` + a 6-char sequential
suffix starting at `000001` (L473-500). The Java `TransactionIdGenerator`
preserves this format with two backing modes:

- **Batch** (interest calculation): a per-`JobExecution` `AtomicLong` for the
  6-char suffix; restart-on-failure preserves the counter via the Spring Batch
  `ExecutionContext`.
- **Online** (`POST /api/transactions`): a PostgreSQL sequence
  `transaction_id_seq` guarantees uniqueness across concurrent requests.

The `tran_id` column is declared `VARCHAR(16) NOT NULL UNIQUE`.

---

## Section 5: JCL Jobs → Spring Batch Job beans

The 29 JCL jobs map to Spring Batch `Job` beans (AAP §0.4.1.3). Each JCL `EXEC
PGM=...` step becomes one or more Spring Batch `Step` beans; `EXEC` step
ordering (`STEP010 → STEP020 → ...`) becomes
`JobBuilder.start(step1).next(step2)...build()`. Several VSAM/GDG-define jobs
have **no Java artifact** — their semantics are subsumed by Flyway DDL and JPA
`@Index`. File-extension case is preserved exactly (`.jcl` vs `.JCL`).

| Source (JCL) | Target Spring Batch Job Bean | Notes |
| :----------- | :--------------------------- | :---- |
| `../app/jcl/POSTTRAN.jcl` (L23: `EXEC PGM=CBTRN02C`) | `TransactionPostingJobConfig` | Daily transaction posting. Input: `daily_transactions` staging table; rejected → `rejected_transactions` (DALYREJS equivalent, 430-byte layout). Chunk size 100. |
| `../app/jcl/INTCALC.jcl` (L22: `EXEC PGM=CBACT04C,PARM='2022071800'`) | `InterestCalculationJobConfig` | Job parameter `tranDate` derived from `PARM`. Generates `tran_id` as `parmDate(10) + suffix(6)` per **PR-10**. |
| `../app/jcl/COMBTRAN.jcl` (2 steps: `STEP05R` PGM=SORT + `STEP10` PGM=IDCAMS) | `TransactionConsolidationJobConfig` | SORT + REPRO replaced by SQL `INSERT INTO transactions SELECT ... ORDER BY tran_id ON CONFLICT DO UPDATE`. |
| `../app/jcl/CREASTMT.JCL` (`DELDEF01` + `STEP010` + `STEP020` + `STEP030` + `STEP040`) | `StatementGenerationJobConfig` | **4 meaningful** chained Spring Batch steps (`DELDEF01` → `STEP010` → `STEP020` → `STEP040`) via `JobBuilder.start(step1).next(step2)...build()`. `STEP040` (`PGM=CBSTM03A`) is the main statement-generation step. The fifth physical JCL step `STEP030` (`PGM=IEFBR14`) is a dataset-disposition no-op with no Java equivalent — see [Caveats](#caveats). |
| `../app/jcl/DUSRSECJ.jcl` | `UserSeedingJobConfig` | Seeds 10 default users (`ADMIN001`–`ADMIN005`, `USER0001`–`USER0005`) with BCrypt-hashed `"PASSWORD"`. Each user receives a **distinct** hash due to BCrypt's random salt (**PR-17**). |
| `../app/jcl/ACCTFILE.jcl` | step within `DataInitializationJobConfig` | Loads `acctdata.txt` (50 accounts) via `AsciiFixedWidthItemReader`. |
| `../app/jcl/CARDFILE.jcl` | step within `DataInitializationJobConfig` | Loads `carddata.txt` (50 cards). |
| `../app/jcl/CUSTFILE.jcl` | step within `DataInitializationJobConfig` | Loads `custdata.txt` (50 customers). |
| `../app/jcl/XREFFILE.jcl` | step within `DataInitializationJobConfig` | Loads `cardxref.txt` (50 cross-references). |
| `../app/jcl/TRANTYPE.jcl` | step within `DataInitializationJobConfig` | Loads `trantype.txt` (7 types). |
| `../app/jcl/TRANCATG.jcl` | step within `DataInitializationJobConfig` | Loads `trancatg.txt` (18 categories). |
| `../app/jcl/DISCGRP.jcl` | step within `DataInitializationJobConfig` | Loads `discgrp.txt` (51 disclosure groups). |
| `../app/jcl/TCATBALF.jcl` | step within `DataInitializationJobConfig` | Loads `tcatbal.txt` (100 transaction category balances). |
| `../app/jcl/TRANFILE.jcl` | step within `DataInitializationJobConfig` | Loads transaction master records. |
| `../app/jcl/CBADMCDJ.jcl` | step within `DataInitializationJobConfig` | Admin data initialization. |
| `../app/jcl/PRTCATBL.jcl` | `CategoryBalanceReportJobConfig` | Prints all TCATBAL records grouped by account. |
| `../app/jcl/TRANBKP.jcl` | `TransactionBackupJobConfig` | PostgreSQL `pg_dump` invoked via `ProcessBuilder` inside a Spring Batch tasklet (**PR-26** — local filesystem only; no cloud object storage). |
| `../app/jcl/TRANREPT.jcl` (+ `../app/proc/TRANREPT.prc`) | `TransactionReportJobConfig` | Transaction detail report based on the `CVTRA07Y.cpy` layout. |
| `../app/jcl/READACCT.jcl` | `AccountFileReadJobConfig` | Diagnostic reader (paired with `CBACT01C`). |
| `../app/jcl/READCARD.jcl` | `CardFileReadJobConfig` | Diagnostic reader (paired with `CBACT02C`). |
| `../app/jcl/READCUST.jcl` | `CustomerFileReadJobConfig` | Diagnostic reader (paired with `CBCUS01C`). |
| `../app/jcl/READXREF.jcl` | `XrefFileReadJobConfig` | Diagnostic reader (paired with `CBACT03C`). |
| `../app/jcl/REPTFILE.jcl` | `ReportFileJobConfig` | Report file generation. |
| `../app/jcl/CLOSEFIL.jcl` | (no Java artifact) | VSAM file CLOSE — irrelevant in the JPA model; superseded by HikariCP connection pooling. |
| `../app/jcl/OPENFIL.jcl` | (no Java artifact) | VSAM file OPEN — irrelevant in the JPA model. |
| `../app/jcl/DEFCUST.jcl` | (no Java artifact) | IDCAMS `DEFINE CLUSTER` — replaced by Flyway `V1__schema.sql`. |
| `../app/jcl/DEFGDGB.jcl` | (no Java artifact) | `DEFINE GDG` — GDG semantics replaced by the `pg_dump` operational pattern (see `TRANBKP`). |
| `../app/jcl/TRANIDX.jcl` | (no Java artifact) | VSAM AIX rebuild (`DELETE → DEFINE → BLDINDEX → DEFINE PATH`) — replaced by PostgreSQL B-tree `@Index` maintained automatically (AAP §0.6.13). |
| `../app/jcl/DALYREJS.jcl` | (no Java artifact) | DALYREJS GDG define — replaced by the `rejected_transactions` table created in `V1__schema.sql`. |

**Job count:** 29 JCL jobs total — 23 map to Spring Batch `Job` beans (10 of
which are steps within the composite `DataInitializationJobConfig`), and 6 are
no-ops (`CLOSEFIL`, `OPENFIL`, `DEFCUST`, `DEFGDGB`, `TRANIDX`, `DALYREJS`).

### 5.1 Critical Batch Sequence

> **PR-12 — The critical batch sequence `POSTTRAN → INTCALC → COMBTRAN →
> CREASTMT` must be reproducible.** Use either:
>
> 1. The `BatchAdminController` to manually launch each job in sequence
>    (operator-driven), or
> 2. A future composite chained `Job` bean that wraps all four (not delivered
>    initially per AAP scope; deferred to operational hardening).
>
> Each individual `Job` bean remains independently runnable via
> `POST /api/admin/jobs/{jobName}/launch`. Per the Spring Batch
> idempotent-execution model, rerunning a job with the **same** `JobParameters`
> returns the existing `JobExecution`, while a different parameter value (e.g.,
> a different `tranDate`) creates a new execution. Checkpoint/restart is backed
> by the `BATCH_*` tables in PostgreSQL; a failed job can be restarted via
> `JobOperator.restart(jobExecutionId)`, resuming from the last committed chunk.

---

## Section 6: Copybooks → JPA Entities, DTOs, Constants

The 28 copybooks map to JPA entities (record-defining copybooks), DTOs,
utility constants, or REFERENCE-only artifacts (AAP §0.4.1.4).

> **PR-13 — Record-length fidelity:** every entity's column lengths mirror the
> COBOL `PIC` clauses (e.g., `CARD-NUM PIC X(16)` → `card_num VARCHAR(16)`;
> `ACCT-ID PIC 9(11)` → `acct_id BIGINT`). The original copybook record length
> (`RECLN`) is preserved in JavaDoc on each entity (AAP §0.6.15).

| Source (Copybook) | Target | Mapping Notes |
| :---------------- | :----- | :------------ |
| `../app/cpy/CVACT01Y.cpy` (300-byte `ACCOUNT-RECORD`) | `Account.java` entity (`../src/main/java/com/carddemo/entity/Account.java`) | `acct_id BIGINT` PK from `ACCT-ID PIC 9(11)`. Money fields `BigDecimal(precision=12, scale=2)` per **PR-16** (originals `S9(10)V99` semantically). `ACCT-EXPIRAION-DATE PIC X(10)` at **L11** (**COBOL typo — see callout below, PR-14**) maps to Java `expirationDate` / column `expiration_date`. `@Version` for optimistic locking per **PR-22**. |
| `../app/cpy/CVACT02Y.cpy` (150-byte `CARD-RECORD`) | `Card.java` entity | PK `card_num VARCHAR(16)` from `CARD-NUM PIC X(16)`. `@Index(name="idx_card_account_id", columnList="account_id")` replaces `CARDDATA.AIX` (AAP §0.6.13). `@ManyToOne` → `Account`. `@Version` per **PR-22**. |
| `../app/cpy/CVACT03Y.cpy` (50-byte `CARD-XREF-RECORD`) | `CardXref.java` entity | PK `xref_card_num VARCHAR(16)`. `@Index(name="idx_xref_account_id", columnList="account_id")` replaces `CARDXREF.AIX`. Junction with `@ManyToOne` to `Customer`, `Account`, `Card`. |
| `../app/cpy/CVCUS01Y.cpy` (500-byte `CUSTOMER-RECORD`) | `Customer.java` entity | PK `cust_id BIGINT`. `ssn` column annotated for masking in `CustomerMapper` per **PR-20**. `@Version` per **PR-22**. |
| `../app/cpy/CVTRA01Y.cpy` (50-byte `TRAN-CAT-BAL-RECORD`) | `TransactionCategoryBalance.java` + `TransactionCategoryBalanceId.java` | **PR-15** composite key via `@EmbeddedId TransactionCategoryBalanceId`: `tranCatAcctId BIGINT (PIC 9(11))` + `tranCatTypeCd VARCHAR(2)` + `tranCatCd INTEGER (PIC 9(04))`. Balance `tran_cat_bal NUMERIC(11,2)` from `PIC S9(09)V99`. |
| `../app/cpy/CVTRA02Y.cpy` (50-byte `DIS-GROUP-RECORD`) | `DisclosureGroup.java` + `DisclosureGroupId.java` | **PR-15** composite key: `disAcctGroupId VARCHAR(10)` + `disTranTypeCd VARCHAR(2)` + `disTranCatCd INTEGER`. Rate `dis_int_rate NUMERIC(6,2)` from `PIC S9(04)V99`. **PR-02:** the `DEFAULT`-fallback consumer of this entity is `InterestCalculationTasklet`. |
| `../app/cpy/CVTRA03Y.cpy` (60-byte `TRAN-TYPE-RECORD`) | `TransactionType.java` entity | PK `tran_type VARCHAR(2)`. |
| `../app/cpy/CVTRA04Y.cpy` (60-byte `TRAN-CAT-RECORD`) | `TransactionCategory.java` + `TransactionCategoryId.java` | **PR-15** composite key: `tranTypeCd VARCHAR(2)` + `tranCatCd INTEGER`. |
| `../app/cpy/CVTRA05Y.cpy` (350-byte `TRAN-RECORD`) | `Transaction.java` entity | PK `tran_id VARCHAR(16)`. `@Index(name="idx_transaction_orig_ts", columnList="orig_timestamp")` replaces `TRANSACT.AIX`. Timestamps stored as `LocalDateTime`, formatted to the DB2 26-char format only at I/O boundaries per **PR-11**. `@Version` per **PR-22**. |
| `../app/cpy/CVTRA06Y.cpy` (350-byte `DALYTRAN-RECORD`) | `DailyTransaction.java` entity + `RejectedTransaction.java` entity | `daily_transactions` staging table (no FK; mirrors the PS feed) per AAP §0.6.6. `RejectedTransaction` adds an 80-byte reject trailer (350 + 80 = 430 bytes) with `validation_code` and `rejection_reason` columns (the DALYREJS layout). |
| `../app/cpy/CVTRA07Y.cpy` | Report DTO classes (`../src/main/java/com/carddemo/dto/report/`) | Transaction report layout (AAP §0.4.1.7). |
| `../app/cpy/CSUSR01Y.cpy` (80-byte `SEC-USER-DATA`) | `User.java` entity implementing `org.springframework.security.core.userdetails.UserDetails` | **PR-17:** `SEC-USR-PWD PIC X(08)` at **L21** (plaintext) replaced by `sec_usr_pwd VARCHAR(60)` storing a BCrypt hash. **PR-19:** `SEC-USR-TYPE PIC X(01)` at L22 (`'A'`/`'U'`) mapped to authorities via `CustomAuthorityMapper`. |
| `../app/cpy/COCOM01Y.cpy` (1024-byte `CARDDEMO-COMMAREA`) | Decomposed across `AuthenticationDto`, Spring Security `SecurityContextHolder`, HTTP path/query params, and DTO context fields | See [§0.6.1 cross-cutting](#section-2-cross-cutting-mappings-overview). The `CDEMO-USER-TYPE` 88-levels at **L26-28** (`CDEMO-USRTYP-ADMIN VALUE 'A'`, `CDEMO-USRTYP-USER VALUE 'U'`) directly inform `CustomAuthorityMapper` (**PR-19**). |
| `../app/cpy/COMEN02Y.cpy` | `MenuOption.java` records / static menu config consumed by `MenuController` | User menu structure. |
| `../app/cpy/COADM02Y.cpy` | Static menu config (admin variant) | Admin menu structure. |
| `../app/cpy/COSTM01.CPY` | Statement template constants used by `StatementHtmlBuilder` | Statement formatting constants. |
| `../app/cpy/COTTL01Y.cpy` | Screen title constants | UI titles referenced by DTOs. |
| `../app/cpy/CSDAT01Y.cpy` | `DateConversionUtil` helpers | Date format constants. |
| `../app/cpy/CSUTLDWY.cpy` | `DateConversionUtil` helpers | Day-of-week conversion data. |
| `../app/cpy/CSUTLDPY.cpy` | `DateConversionUtil` helpers | Date parameter structures. |
| `../app/cpy/CSMSG01Y.cpy` | Message constants / `messages.properties` | System messages. |
| `../app/cpy/CSMSG02Y.cpy` | Message constants / `messages.properties` | Application messages. |
| `../app/cpy/CSSTRPFY.cpy` | REFERENCE only | 3270-specific PF-key constants — no REST equivalent. |
| `../app/cpy/CSSETATY.cpy` | REFERENCE only | 3270 attribute constants. |
| `../app/cpy/CSLKPCDY.cpy` | REFERENCE only | Lookup code constants. |
| `../app/cpy/CVCRD01Y.cpy` | Card work area constants used by `CardService` | Card-related constants. |
| `../app/cpy/CUSTREC.cpy` | REFERENCE only | Alternate customer record layout (`CVCUS01Y` is authoritative). |
| `../app/cpy/UNUSED1Y.cpy` | REFERENCE only | Unused copybook (preserved verbatim per **PR-27**). |

**Copybook count:** 28 total.

### 6.1 The `ACCT-EXPIRAION-DATE` COBOL typo (PR-14)

The COBOL field at `../app/cpy/CVACT01Y.cpy` **L11** is literally spelled
`ACCT-EXPIRAION-DATE` — **missing the letter "T"** in "EXPIRATION". This is the
actual COBOL identifier in the source and appears verbatim in `CBTRN02C` (the
expiration check at L414). Per **PR-14**:

- **COBOL field name:** `ACCT-EXPIRAION-DATE` (typo preserved — original source
  is REFERENCE and unchanged).
- **Java field name:** `expirationDate` (canonical English; the typo does
  **not** propagate to Java).
- **PostgreSQL column name:** `expiration_date` (canonical English snake_case).

This deliberate divergence is documented to prevent reviewer confusion when
reading the Java entity side-by-side with the COBOL copybook.

### 6.2 Composite keys via `@EmbeddedId` (PR-15)

Three records use multi-field VSAM keys, each modeled with an `@Embeddable`
ID class referenced by `@EmbeddedId`, preserving the COBOL key field order:

| Entity | `@Embeddable` Id | Key Fields (in COBOL order) | Source |
| :----- | :--------------- | :-------------------------- | :----- |
| `TransactionCategoryBalance` | `TransactionCategoryBalanceId` | account + type + category | `../app/cpy/CVTRA01Y.cpy` |
| `DisclosureGroup` | `DisclosureGroupId` | group + type + category | `../app/cpy/CVTRA02Y.cpy` |
| `TransactionCategory` | `TransactionCategoryId` | type + category | `../app/cpy/CVTRA04Y.cpy` |

The `@Embeddable` + `@EmbeddedId` pattern is used in preference to the legacy
`@IdClass` pattern.

---

## Section 7: BMS Symbolic Copybooks → DTO Field Shape (REFERENCE only)

Per AAP §0.4.1.7, the 17 BMS symbolic copybooks in `../app/cpy-bms/` are
**REFERENCE only**: their field-list information (field names, lengths, and
attributes) feeds the design of the JSON request/response DTOs, but the files
themselves are **not** transformed into runtime artifacts. All use the
uppercase `.CPY` extension.

| BMS Copybook (REFERENCE) | Informs DTO File |
| :----------------------- | :--------------- |
| `../app/cpy-bms/COSGN00.CPY` | `LoginRequest.java`, `LoginResponse.java` (`../src/main/java/com/carddemo/dto/auth/`) |
| `../app/cpy-bms/COMEN01.CPY` | `MenuResponse.java` (`../src/main/java/com/carddemo/dto/menu/`) |
| `../app/cpy-bms/COADM01.CPY` | `MenuResponse.java` (admin variant) |
| `../app/cpy-bms/COACTVW.CPY` | `AccountDto.java` (`../src/main/java/com/carddemo/dto/account/`) |
| `../app/cpy-bms/COACTUP.CPY` | `AccountDto.java` (update fields) |
| `../app/cpy-bms/COCRDLI.CPY` | `CardListResponse.java` (`../src/main/java/com/carddemo/dto/card/`) |
| `../app/cpy-bms/COCRDSL.CPY` | `CardDto.java` (single card) |
| `../app/cpy-bms/COCRDUP.CPY` | `CardDto.java` (update fields) |
| `../app/cpy-bms/COTRN00.CPY` | `TransactionListResponse.java` (`../src/main/java/com/carddemo/dto/transaction/`) |
| `../app/cpy-bms/COTRN01.CPY` | `TransactionDto.java` (view) |
| `../app/cpy-bms/COTRN02.CPY` | `TransactionRequest.java` (create) |
| `../app/cpy-bms/COBIL00.CPY` | `BillPaymentRequest.java`, `BillPaymentResponse.java` (`../src/main/java/com/carddemo/dto/billpayment/`) |
| `../app/cpy-bms/CORPT00.CPY` | `ReportRequest.java` (`../src/main/java/com/carddemo/dto/report/`) |
| `../app/cpy-bms/COUSR00.CPY` | `UserListResponse.java` (admin) (`../src/main/java/com/carddemo/dto/user/`) |
| `../app/cpy-bms/COUSR01.CPY` | `UserCreateRequest.java` |
| `../app/cpy-bms/COUSR02.CPY` | `UserDto.java` (update view) |
| `../app/cpy-bms/COUSR03.CPY` | `UserDto.java` (delete view) |

**BMS symbolic copybook count:** 17 total.

The 17 BMS **map sources** in `../app/bms/*.bms` are likewise REFERENCE only;
per AAP §0.2.2 and **PR-25**, no 3270 UI replacement is produced — the REST API
consumes and emits JSON only.

---

## Section 8: Repositories, Mappers, Exceptions, Configuration (Quick Reference)

Three compact tables summarize the supporting Java artifacts.

### 8.1 Repositories (AAP §0.4.1.5)

All persistence access goes through Spring Data JPA repositories. Method-name
finders replace VSAM AIX path access; `existsById` + `save` replaces the
`INVALID KEY` upsert idiom.

| Repository | Entity | Key Finder Methods |
| :--------- | :----- | :----------------- |
| `AccountRepository` | `Account` | (inherited `findById`, `save`) |
| `CardRepository` | `Card` | `Page<Card> findByAccountId(Long accountId, Pageable pageable)` — replaces `CARDDATA.AIX` `STARTBR` |
| `CardXrefRepository` | `CardXref` | `findByAccountId`, `findByCardNumber` |
| `CustomerRepository` | `Customer` | (inherited) |
| `TransactionRepository` | `Transaction` | `findByOrigTimestampBetween`, `findByCardNumber` — `findByOrigTimestampBetween` exposes `idx_transaction_orig_ts` |
| `DailyTransactionRepository` | `DailyTransaction` | Staging-table reader |
| `RejectedTransactionRepository` | `RejectedTransaction` | DALYREJS replacement sink |
| `TransactionCategoryBalanceRepository` | `TransactionCategoryBalance` | `existsById` + `save` for the TCATBAL upsert (**PR-06**) |
| `DisclosureGroupRepository` | `DisclosureGroup` | `findById(DisclosureGroupId)` with `DEFAULT` fallback per **PR-02** |
| `TransactionTypeRepository` | `TransactionType` | (inherited) |
| `TransactionCategoryRepository` | `TransactionCategory` | (inherited; composite key `TransactionCategoryId`) |
| `UserRepository` | `User` | `findById(String userId)` used by `UserDetailsServiceImpl` |

**Repository count:** 12 total.

### 8.2 Mappers (AAP §0.4.1.7)

Hand-coded mappers (no MapStruct dependency) keep `BigDecimal` scale handling
and SSN masking explicit and auditable.

| Mapper | Concern |
| :----- | :------ |
| `AccountMapper` | `BigDecimal` scale preserved (**PR-16**); date format conversion via `DateConversionUtil` |
| `CardMapper` | Bidirectional entity ↔ DTO |
| `TransactionMapper` | DB2 timestamp formatting for `origTimestamp` / `procTimestamp` per **PR-11** |
| `CustomerMapper` | **SSN masking** applied here (`***-**-####`) per **PR-20** |
| `UserMapper` | **Excludes** the `sec_usr_pwd` BCrypt hash from the response payload |

### 8.3 Exceptions (AAP §0.4.1.7)

All exception → HTTP mappings are handled by `GlobalExceptionHandler`
(`@ControllerAdvice`).

| Exception | Validation Code / Origin | HTTP Status |
| :-------- | :----------------------- | :---------- |
| `InvalidCardException` | 100 `"INVALID CARD NUMBER FOUND"` | 400 |
| `AccountNotFoundException` | 101 `"ACCOUNT RECORD NOT FOUND"` | 404 |
| `OverlimitException` | 102 `"OVERLIMIT TRANSACTION"` | 422 |
| `ExpiredAccountException` | 103 `"TRANSACTION RECEIVED AFTER ACCT EXPIRATION"` | 422 |
| `TransactionValidationException` | parent of 100/101/102/103 | (varies) |
| `DiscloseGroupNotFoundException` | `DEFAULT` fallback miss in `CBACT04C` | 404 |
| `OptimisticLockException` (Spring built-in) | concurrent update (**PR-22**) | 409 |
| `AccessDeniedException` (Spring built-in) | `@PreAuthorize` denial (**PR-18**) | 403 |

The `AccessDeniedException` → **403** mapping is the runtime manifestation of
the **PR-18** programmatic-auth gap closure (AAP §0.6.9): unauthorized callers
hitting `UserController` or `BatchAdminController` are rejected by method
security rather than silently routed away by a menu.

---

## Section 9: Build, Configuration, Flyway Migrations

Build, application configuration, Flyway DDL/seed migrations, and the statement
HTML template (AAP §0.4.1.8). The 5 Flyway scripts create all 14 base tables,
the secondary indexes (replacing the VSAM AIX), and the seed data (reference
data, the 10 default users with BCrypt hashes, and the master data).

| Target File | Source | Key Notes |
| :---------- | :----- | :-------- |
| `../pom.xml` | (new) | Maven build, `spring-boot-starter-parent` 3.2.12, Java 17 (`-parameters`), all dependencies per AAP §0.5.1. |
| `../src/main/resources/application.yml` | (new) | Base config — JPA dialect `org.hibernate.dialect.PostgreSQLDialect`, Flyway enabled, Jackson `WRITE_BIGDECIMAL_AS_PLAIN`, Actuator endpoints (`/actuator/health`, `/info`, `/metrics`). |
| `../src/main/resources/application-dev.yml` | (new) | Local PostgreSQL JDBC URL; `ddl-auto: validate`; debug logging. |
| `../src/main/resources/application-prod.yml` | (new) | Production JDBC URL via environment; `ddl-auto: validate`; INFO/structured JSON logging. |
| `../src/main/resources/db/migration/V1__schema.sql` | All record-defining copybooks (`CVACT01Y`, `CVACT02Y`, `CVACT03Y`, `CVCUS01Y`, `CVTRA01Y`–`CVTRA07Y`, `CSUSR01Y`) | DDL creating all 14 base tables matching the VSAM record layouts (**PR-13**). |
| `../src/main/resources/db/migration/V2__indexes.sql` | `../app/catlg/LISTCAT.txt` + `../app/csd/CARDDEMO.CSD` | Secondary B-tree indexes `idx_card_account_id`, `idx_xref_account_id`, `idx_transaction_orig_ts` (replace the three VSAM AIX). |
| `../src/main/resources/db/migration/V3__seed_reference_data.sql` | `../app/data/ASCII/trantype.txt` + `trancatg.txt` + `discgrp.txt` | 7 transaction types + 18 categories + 51 disclosure groups. |
| `../src/main/resources/db/migration/V4__seed_users.sql` | `../app/jcl/DUSRSECJ.jcl` (REFERENCE) | 10 default users (`ADMIN001`–`ADMIN005`, `USER0001`–`USER0005`) with **pre-computed BCrypt hashes** of literal `"PASSWORD"` (**PR-17**). |
| `../src/main/resources/db/migration/V5__seed_master_data.sql` | All `../app/data/ASCII/*.txt` master files | 50 customers + 50 accounts + 50 cards + 50 cross-references + 100 TCATBAL records. |
| `../src/main/resources/templates/statement-template.html` | `../app/cbl/CBSTM03A.CBL` L506-555 | HTML template extracted from `5100-WRITE-HTML-HEADER` (**PR-09** byte-for-byte). |
| `../README.md` | (existing) | Updated to add the "Java Spring Boot 3.2 Modernization" section; original mainframe documentation preserved unchanged. |
| `../docs/migration-mapping.md` | (this AAP) | **This document** — the per-file mapping companion. |

---

## Section 10: Tests and Parity Verification

The three **parity tests** are mandatory deliverables (**PR-21**): without
them, the "preserve business logic exactly" mandate for the three line-by-line
programs cannot be verified.

| Test File | Verifies | Critical Rule |
| :-------- | :------- | :------------ |
| `../src/test/java/com/carddemo/businesslogic/InterestCalculationParityTest.java` | `CBACT04C` `(TRAN-CAT-BAL * DIS-INT-RATE) / 1200` Java output matches COBOL for a canonical input matrix; `DEFAULT` fallback | **PR-01, PR-02, PR-21** |
| `../src/test/java/com/carddemo/businesslogic/TransactionPostingParityTest.java` | `CBTRN02C` codes 100/101/102/103 with exact message strings; credit-limit + expiration checks; TCATBAL upsert; sign-based balance update | **PR-03, PR-04, PR-05, PR-06, PR-07, PR-21** |
| `../src/test/java/com/carddemo/businesslogic/StatementGenerationParityTest.java` | `CBSTM03A` `5100-WRITE-HTML-HEADER` HTML output matches COBOL byte-for-byte | **PR-09, PR-21** |
| `../src/test/java/com/carddemo/controller/*Test.java` | MockMvc tests per controller (happy path + 4xx errors + security scenarios) | (general) |
| `../src/test/java/com/carddemo/service/*Test.java` | Mockito unit tests for service logic | (general) |
| `../src/test/java/com/carddemo/batch/*JobTest.java` | `JobLauncherTestUtils` + `JobRepositoryTestUtils` per batch job | (general) |
| `../src/test/java/com/carddemo/integration/*IT.java` | Testcontainers PostgreSQL — verifies Flyway migrations apply cleanly; end-to-end REST + batch scenarios | (general) |
| `../src/test/resources/application-test.yml` | Testcontainers PostgreSQL config | (general) |
| `../src/test/resources/fixtures/*.csv` | CSV mirrors of the `../app/data/ASCII/*.txt` fixtures | (general) |

---

## Section 11: Reference Files (Preserved Unchanged)

Per **PR-27**, every original mainframe source artifact is preserved in place,
unchanged, as REFERENCE for downstream regression verification of
business-logic parity. None is deleted; none is modified.

| Path / Pattern | Mode | Notes |
| :------------- | :--- | :---- |
| `../app/cbl/*.cbl`, `../app/cbl/*.CBL` | REFERENCE | All 28 COBOL programs (17 `CO*` online + 11 `CB*`/`CSUTLDTC` batch); `CBSTM03A.CBL` and `CBSTM03B.CBL` use the uppercase `.CBL` extension. |
| `../app/jcl/*.jcl`, `../app/jcl/*.JCL` | REFERENCE | All 29 JCL jobs; `CREASTMT.JCL` uses the uppercase `.JCL` extension. |
| `../app/cpy/*.cpy`, `../app/cpy/*.CPY` | REFERENCE | All 28 copybooks; `COSTM01.CPY` uses the uppercase `.CPY` extension. |
| `../app/cpy-bms/*.CPY` | REFERENCE | All 17 BMS symbolic copybooks (uppercase `.CPY`) used to derive DTO field shapes. |
| `../app/bms/*.bms` | REFERENCE | All 17 BMS map sources (no UI replacement per **PR-25** / AAP §0.2.2). |
| `../app/csd/CARDDEMO.CSD` | REFERENCE | CICS resource definitions used to derive transaction routing. |
| `../app/catlg/LISTCAT.txt` | REFERENCE | VSAM catalog used to derive primary-key positions and AIX configurations. |
| `../app/data/ASCII/*.txt` | REFERENCE / INPUT | Read by `DataInitializationJobConfig` for seeding. |
| `../app/proc/*.prc` | REFERENCE | JCL procs absorbed into Spring Batch step configs (`REPROC.prc`, `TRANREPT.prc`). |
| `../app/ctl/REPROCT.ctl` | REFERENCE | IDCAMS REPRO control card. |
| `../diagrams/` | REFERENCE | Documentation diagrams preserved unchanged. |
| `../CONTRIBUTING.md`, `../CODE_OF_CONDUCT.md`, `../LICENSE`, `../NOTICE` | REFERENCE | Project metadata preserved unchanged. |

---

## Caveats

This sub-section records discrepancies discovered while authoring this
document. **Where this document and the AAP disagree, the AAP wins.**

1. **`CREASTMT.JCL` step count (5 physical vs. 4 meaningful).** The job
   contains **five** physical JCL `EXEC` steps — `DELDEF01` (`PGM=IDCAMS`,
   L22), `STEP010` (`PGM=SORT`, L44), `STEP020` (`PGM=IDCAMS`, L56), `STEP030`
   (`PGM=IEFBR14`, L66), and `STEP040` (`PGM=CBSTM03A`, L79). Step `STEP030`
   runs `IEFBR14`, the IBM no-op utility used purely for dataset disposition
   (allocate/delete), so it carries no business logic. AAP §0.4.1.3 therefore
   maps the job to **4 meaningful** chained Spring Batch steps
   (`DELDEF01 → STEP010 → STEP020 → STEP040`), correctly omitting the
   `IEFBR14` no-op. [Section 5](#section-5-jcl-jobs--spring-batch-job-beans)
   reflects this 5-vs-4 reconciliation.

2. **Repository inventory counts vs. the root `README.md` directory tree.**
   The root [`README.md`](../README.md) directory-tree comments describe the
   `app/cpy/` folder as "27 record-defining copybooks" and `app/jcl/` as "28
   JCL jobs". This document uses the **full verified repository inventory** —
   **28 copybooks** (the README's "27 record-defining" count excludes one
   non-record copybook such as `UNUSED1Y.cpy`) and **29 JCL jobs** — to match
   AAP §0.4.1 and the file-by-file tables above. Both views refer to the same
   underlying files; the difference is one of categorization, not of missing
   artifacts.

3. **`../src/...` target paths are forward references.** The Java target files
   cited throughout (under `../src/main/java/com/carddemo/` and
   `../src/test/...`) are produced by the same single-phase refactor
   (**PR-30**) and may not yet exist on disk at the moment this document is
   read in isolation. They are intentional forward references that resolve once
   the full deliverable is materialized; the `../app/...` REFERENCE paths, by
   contrast, all resolve to existing repository files.

---

## Preservation Rule Coverage Matrix

This matrix indexes every preservation rule (AAP §0.7.1, **PR-01** through
**PR-30**) to the section(s) of this document where it is discussed. It serves
both as a reviewer index and as a coverage guarantee that no rule is omitted.

| Rule | Summary | Discussed In |
| :--- | :------ | :----------- |
| PR-01 | Interest formula `(TRAN-CAT-BAL * DIS-INT-RATE) / 1200` preserved | §2, §4, §4.1, §10 |
| PR-02 | DISCGRP `DEFAULT` fallback preserved | §4, §6, §8.1, §10 |
| PR-03 | Validation codes 100/101/102/103 with exact messages | §2, §3, §4, §4.2, §8.3, §10 |
| PR-04 | Credit-limit formula preserved | §4, §4.2, §10 |
| PR-05 | Expiration check preserved | §4, §4.2, §10 |
| PR-06 | TCATBAL upsert pattern preserved | §4, §8.1, §10 |
| PR-07 | Account sign-based bucket preserved | §4, §4.3, §10 |
| PR-08 | Account REWRITE pattern preserved | §4 |
| PR-09 | Statement HTML byte-for-byte | §4, §9, §10 |
| PR-10 | 16-character transaction ID format | §3, §4, §4.5, §5 |
| PR-11 | DB2 timestamp format `YYYY-MM-DD-HH.MM.SS.MIL0000` | §2, §4, §6, §8.2 |
| PR-12 | Critical batch sequence `POSTTRAN → INTCALC → COMBTRAN → CREASTMT` | §2, §5, §5.1 |
| PR-13 | Record-length fidelity (`PIC` → column length) | §6 |
| PR-14 | `ACCT-EXPIRAION-DATE` typo → `expirationDate` | §6, §6.1 |
| PR-15 | Composite keys via `@EmbeddedId` | §6, §6.2 |
| PR-16 | `BigDecimal` everywhere for money (no float/double) | §2, §4.1, §4.2, §6, §8.2 |
| PR-17 | BCrypt for all passwords | §2, §3, §5, §6, §9 |
| PR-18 | Method-level `@PreAuthorize("hasRole('ADMIN')")` | §2, §3, §3.1, §8.3 |
| PR-19 | Role mapping `'A'→ROLE_ADMIN`, `'U'→ROLE_USER` | §2, §6 |
| PR-20 | SSN masking on outbound (`***-**-####`) | §3, §6, §8.2 |
| PR-21 | Parity tests for `CBACT04C`, `CBTRN02C`, `CBSTM03A` | §4, §10 |
| PR-22 | Optimistic locking via `@Version` | §2, §2.2, §6, §8.3 |
| PR-23 | Lock ordering `CUSTOMER → ACCOUNT → CARD → TRANSACTION` | §2.2 |
| PR-24 | `@Transactional` boundaries preserve UOW | §2, §2.2, §3 |
| PR-25 | Single monolith (no microservices) | §1, §2.2, §7 |
| PR-26 | No external runtime services (PostgreSQL + filesystem) | §1, §2.2, §5 |
| PR-27 | Original sources preserved (REFERENCE) | §1, §6, §11 |
| PR-28 | Jakarta EE namespace (`jakarta.*`) | §2.2 |
| PR-29 | Constructor injection only (Lombok `@RequiredArgsConstructor`) | §2.2 |
| PR-30 | Single-phase delivery | §1, §2.2, Caveats |

---

*End of `docs/migration-mapping.md`. For operational build/run/test
instructions and sample `curl` invocations, see the "Java Spring Boot 3.2
Modernization" section of the root [`README.md`](../README.md).*

