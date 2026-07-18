# CardDemo COBOL → Java Traceability Matrix

This document is the **bidirectional, 100 %-coverage traceability matrix** mandated by the
**Explainability rule** (see Technical Specification §0.7.2 and §0.6.10). It maps **every** legacy
COBOL / z/OS construct in the AWS CardDemo application to the Java / Spring Boot artifact that
replaces it, and it documents the reverse direction so that any generated Java class can be traced
back to its originating `legacy/` source.

- **Source baseline:** AWS CardDemo — an IBM z/OS credit-card management application built on
  **COBOL, CICS, VSAM, JCL, BMS and RACF**.
- **Target:** a single-module **Java 25 LTS + Spring Boot 3.5.16** layered application
  (`web/controller` → `service` → `repository` → PostgreSQL, with Spring Batch for JCL and Spring
  Security for signon/RACF) under the base package `com.aws.carddemo`.
- **Direction:** *forward* = COBOL construct → Java artifact (Sections 2–7); *reverse* = Java
  artifact → `legacy/` source path via a Javadoc origin-tag convention (Section 9).
- **Coverage guarantee:** every one of the 28 COBOL programs, 28 copybooks, 17 BMS maps, 17
  symbolic map copybooks, 29 JCL jobs, 2 PROCs, 1 sort-control member and 1 CSD appears in this
  matrix. The only three intentional non-migrations (`UNUSED1Y`, `COCRDSEC`/`CDV1`,
  `OPENFIL`/`CLOSEFIL`) are explicitly logged in the [Gaps ledger](#8-intentional-non-migrations-gaps-ledger)
  so coverage provably reaches 100 % with no silent drops.

> **Legacy path convention.** The original COBOL tree was moved read-only from `app/**` to
> `legacy/**` (Technical Specification §0.2.2 / §0.4.1). All source citations below use the
> `legacy/` prefix while preserving the original subfolder names and exact file-name casing
> (`legacy/cbl/…`, `legacy/cpy/…`, `legacy/cpy-bms/…`, `legacy/bms/…`, `legacy/jcl/…`,
> `legacy/proc/…`, `legacy/ctl/…`, `legacy/csd/…`, `legacy/catlg/…`, `legacy/data/…`).
>
> **Related documents.** Consolidation decisions and every intentional gap are explained in
> [`decision-log.md`](./decision-log.md); the before/after architecture diagrams are in
> [`architecture/`](./architecture/).

---

## Table of Contents

1. [Coverage Summary](#1-coverage-summary)
2. [Online COBOL Programs → Service + Controller](#2-online-cobol-programs--service--controller)
3. [Batch COBOL Programs + JCL → Spring Batch Jobs](#3-batch-cobol-programs--jcl--spring-batch-jobs)
4. [Copybooks → Entities / DTOs / Enums / Constants](#4-copybooks--entities--dtos--enums--constants)
5. [Repositories (One per VSAM File)](#5-repositories-one-per-vsam-file)
6. [BMS Maps → Thymeleaf Templates + Screen Form DTOs](#6-bms-maps--thymeleaf-templates--screen-form-dtos)
7. [Infrastructure JCL / PROC / CTL / CSD → Schema, Config, Sort](#7-infrastructure-jcl--proc--ctl--csd--schema-config-sort)
8. [Intentional Non-Migrations (Gaps Ledger)](#8-intentional-non-migrations-gaps-ledger)
9. [Reverse-Direction Convention (Java → Legacy)](#9-reverse-direction-convention-java--legacy)

---

## 1. Coverage Summary

The table below counts every legacy construct type and confirms that each is either **mapped** to a
Java target or **accounted-for** as a logged intentional non-migration. There are **zero unresolved
gaps**.

| Construct type | Count | Mapped / accounted-for | Unresolved gaps |
| :------------- | ----: | ---------------------: | --------------: |
| COBOL programs (`legacy/cbl/*.cbl`, `*.CBL`) | 28 | 28 | 0 |
| Copybooks (`legacy/cpy/*.cpy`, `*.CPY`) | 28 | 28 *(incl. `UNUSED1Y` logged as dead code)* | 0 |
| BMS map sources (`legacy/bms/*.bms`) | 17 | 17 | 0 |
| Symbolic map copybooks (`legacy/cpy-bms/*.CPY`) | 17 | 17 | 0 |
| JCL jobs (`legacy/jcl/*.jcl`, `*.JCL`) | 29 | 29 *(incl. `OPENFIL`/`CLOSEFIL` logged as N/A)* | 0 |
| PROCs (`legacy/proc/*.prc`) | 2 | 2 | 0 |
| Sort control (`legacy/ctl/*.ctl`) | 1 | 1 | 0 |
| CICS resource definitions (`legacy/csd/CARDDEMO.CSD`) | 1 | 1 *(incl. `COCRDSEC` program logged → `SecurityConfig`)* | 0 |
| **Total primary constructs** | **123** | **123** | **0** |

**Reference-only artifacts** (retained as parity oracles / schema inputs, not transformed into a
single output, per Technical Specification §0.2.1): the VSAM catalog listing
`legacy/catlg/LISTCAT.txt` (informs the Flyway schema), the **9** ASCII fixtures under
`legacy/data/ASCII/` (Flyway seed + JUnit fixtures) and the **12** native EBCDIC datasets under
`legacy/data/EBCDIC/` (binary source-of-truth).

The **28 COBOL programs** decompose as **10 batch** + **17 online** + **1 date utility**
(`CSUTLDTC`). The CSD additionally defines the **18th** CICS program/transaction `COCRDSEC`/`CDV1`,
which has **no `.cbl`** and is realized as Spring Security authorization — see the
[Gaps ledger](#8-intentional-non-migrations-gaps-ledger).

---

## 2. Online COBOL Programs → Service + Controller

Each of the 17 online (pseudo-conversational) programs becomes one `@Service` (business logic) and
one Spring MVC controller **route**, keyed to its CICS transaction id. The 18th CSD program
(`COCRDSEC`, transaction `CDV1`) has no `.cbl` and is realized purely as Spring Security
authorization.

| CICS Tran | COBOL Program (legacy) | Java Service | Java Controller (route) | Screen / Function |
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
| `CDV1` | `legacy/csd/CARDDEMO.CSD` (`COCRDSEC` program — no `.cbl`) | _(none — security cross-cut)_ | `config/SecurityConfig.java` (method/URL authorization) | Card-detail security variant — **intentional non-migration, see [§8](#8-intentional-non-migrations-gaps-ledger)** |

> **Paragraph → method granularity.** Each online program's numbered paragraphs (e.g. `0000-`,
> `1000-`, `9000-`) map to methods on the corresponding `@Service`, preserving the original
> `PERFORM`/`EVALUATE` control flow (`PERFORM` → method call, `EVALUATE` → `switch`,
> `PERFORM UNTIL` → loop; Technical Specification §0.3.3). This matrix maps at **program → class**
> granularity; the paragraph → method detail is documented in each class's Javadoc. Inter-program
> `XCTL`/`LINK` transfers (e.g. `COSGN00C` → `COADM01C`/`COMEN01C` after authentication) become
> role-based controller redirects.

---

## 3. Batch COBOL Programs + JCL → Spring Batch Jobs

Each business batch program becomes a Spring Batch `@Configuration` `Job` composed of
chunk-oriented `Step`s (reader → processor → writer) or a `Tasklet` for single-action utilities.
The driving JCL job (and PROC, where present) is preserved as step topology and job parameters.
The date utility `CSUTLDTC` is a called subroutine rather than a job and becomes a `@Service`.

| Business function | COBOL Program (legacy) | JCL / PROC (legacy) | Java Batch `@Configuration` / util |
| :---------------- | :--------------------- | :------------------ | :--------------------------------- |
| Post daily transactions | `legacy/cbl/CBTRN02C.cbl` | `legacy/jcl/POSTTRAN.jcl` | `batch/PostTransactionJobConfig.java` — reader = `DailyTransaction`, processor = validate xref + acct + credit-limit, writers = Transaction / TCATBAL / Account; over-limit → **430-byte** reject record |
| Monthly interest calc | `legacy/cbl/CBACT04C.cbl` | `legacy/jcl/INTCALC.jcl` | `batch/InterestCalcJobConfig.java` — `(bal × rate) / 1200` truncated to 2 dp (`RoundingMode.DOWN`); `PARM` date → `JobParameter` |
| Statement generation | `legacy/cbl/CBSTM03A.CBL` + `legacy/cbl/CBSTM03B.CBL` | `legacy/jcl/CREASTMT.JCL` | `batch/StatementJobConfig.java` — `CBSTM03B` I/O subprogram → reader / DAO using `COSTM01` layout |
| Account file print | `legacy/cbl/CBACT01C.cbl` | `legacy/jcl/READACCT.jcl` | `batch/AccountPrintJobConfig.java` |
| Card file print | `legacy/cbl/CBACT02C.cbl` | `legacy/jcl/READCARD.jcl` | `batch/CardPrintJobConfig.java` |
| Xref file print | `legacy/cbl/CBACT03C.cbl` | `legacy/jcl/READXREF.jcl` | `batch/XrefPrintJobConfig.java` |
| Customer file load | `legacy/cbl/CBCUS01C.cbl` | `legacy/jcl/READCUST.jcl` | `batch/CustomerLoadJobConfig.java` |
| Transaction backup | `legacy/cbl/CBTRN01C.cbl` | `legacy/jcl/TRANBKP.jcl` | `batch/TransactionBackupJobConfig.java` |
| Transaction report | `legacy/cbl/CBTRN03C.cbl` | `legacy/jcl/TRANREPT.jcl` + `legacy/proc/TRANREPT.prc` | `batch/TransactionReportJobConfig.java` |
| Date validation utility | `legacy/cbl/CSUTLDTC.cbl` | _(called via Language Environment — no standalone JCL)_ | `util/DateConversionService.java` — replaces `CEEDAYS` / `CEEDATE` / `CEECBLDY` |

> This table covers **all 10 batch programs** plus the `CSUTLDTC` date utility (11 programs in
> total; the statement row bundles the `CBSTM03A` driver with its `CBSTM03B` I/O subprogram). The
> remaining infrastructure JCL jobs (IDCAMS define/load, GDG, sort, file open/close) are mapped in
> [§7](#7-infrastructure-jcl--proc--ctl--csd--schema-config-sort).

---

## 4. Copybooks → Entities / DTOs / Enums / Constants

All 28 copybooks are accounted for. Record layouts with a VSAM home become JPA `@Entity` classes;
support copybooks become DTOs, enums or constant holders. Packed-decimal (`COMP-3` / `S9(n)V99`)
monetary fields map to `java.math.BigDecimal` (never floating-point), per Technical Specification
§0.6.1.

| Copybook (`legacy/cpy`) | VSAM file / role | Java artifact |
| :---------------------- | :--------------- | :------------ |
| `CVACT01Y.cpy` | `ACCTDAT` (account record, 300 B) | `domain/Account.java` |
| `CVACT02Y.cpy` | `CARDDAT` (card record, 150 B) | `domain/Card.java` |
| `CVACT03Y.cpy` | `CCXREF` (card-xref record, 50 B) | `domain/CardXref.java` |
| `CVCUS01Y.cpy` | `CUSTDAT` (customer record, 500 B) | `domain/Customer.java` *(consolidated with `CUSTREC.cpy`)* |
| `CUSTREC.cpy` | `CUSTDAT` (duplicate customer layout) | `domain/Customer.java` *(consolidated — see [`decision-log.md`](./decision-log.md))* |
| `CVTRA05Y.cpy` | `TRANSACT` (transaction record, 350 B) | `domain/Transaction.java` |
| `CVTRA01Y.cpy` | `TCATBAL` (transaction-category balance, 50 B) | `domain/TransactionCategoryBalance.java` |
| `CVTRA02Y.cpy` | `DISCGRP` (disclosure group, 50 B) | `domain/DisclosureGroup.java` |
| `CVTRA03Y.cpy` | `TRANTYPE` (transaction type, 60 B) | `domain/TransactionType.java` |
| `CVTRA04Y.cpy` | `TRANCATG` (transaction category, 60 B) | `domain/TransactionCategory.java` |
| `CSUSR01Y.cpy` | `USRSEC` (security user data, 80 B) | `domain/UserSecurity.java` |
| `COCOM01Y.cpy` | COMMAREA (pseudo-conversational state) | `dto/CardDemoContext.java` |
| `CVTRA06Y.cpy` | `DALYTRAN` feed (batch input, 350 B) | `dto/DailyTransaction.java` |
| `COSTM01.CPY` | statement layout (`TRNX-RECORD`) | `dto/StatementTransaction.java` |
| `CVTRA07Y.cpy` | report layout (headers + detail) | `dto/report/*.java` *(edit-mask formatting)* |
| `CVCRD01Y.cpy` | card work areas (`CC-WORK-AREAS`) | `dto/CardWorkArea.java` *(AID/PFK 88-levels → PF-key enum)* |
| `COADM02Y.cpy` | admin menu options | `dto/menu/AdminMenuOptions.java` |
| `COMEN02Y.cpy` | main menu options | `dto/menu/MainMenuOptions.java` |
| `CSDAT01Y.cpy` | working date structure | `dto/DateStruct.java` |
| `COTTL01Y.cpy` | screen title constants | `util/constants/ScreenTitles.java` |
| `CSLKPCDY.cpy` | reference / lookup codes | `domain/enums/LookupCodes.java` |
| `CSMSG01Y.cpy` | message constants | `util/constants/Messages.java` *(merged with `CSMSG02Y`)* |
| `CSMSG02Y.cpy` | message constants | `util/constants/Messages.java` *(merged with `CSMSG01Y`)* |
| `CSSETATY.cpy` | UI attribute / style constants | `util/constants/ScreenAttributes.java` |
| `CSSTRPFY.cpy` | PF-key handling | `util/PfKeyHandler.java` |
| `CSUTLDPY.cpy` | date linkage | `util/DateConversionSupport.java` *(merged with `CSUTLDWY`)* |
| `CSUTLDWY.cpy` | date working storage | `util/DateConversionSupport.java` *(merged with `CSUTLDPY`)* |
| `UNUSED1Y.cpy` | _(not referenced by any program)_ | **NOT MIGRATED — dead code, intentional gap, see [§8](#8-intentional-non-migrations-gaps-ledger)** |

---

## 5. Repositories (One per VSAM File)

Each of the six base VSAM KSDS files plus the four reference/composite files becomes exactly one
Spring Data JPA repository (10 repositories total). Alternate indexes become secondary DB indexes
plus Spring Data **derived queries** (Technical Specification §0.6.2).

| VSAM file | Copybook | Java Repository | Key / alternate-index handling |
| :-------- | :------- | :-------------- | :----------------------------- |
| `ACCTDAT` | `CVACT01Y` | `repository/AccountRepository.java` | PK `acctId` |
| `CARDDAT` | `CVACT02Y` | `repository/CardRepository.java` | PK `cardNum`; `findByCardAcctId` replaces the `CARDAIX` alt index |
| `CCXREF` | `CVACT03Y` | `repository/CardXrefRepository.java` | composite key (card + customer + account); `findByXrefAcctId` replaces the `CXACAIX` alt index |
| `CUSTDAT` | `CVCUS01Y` | `repository/CustomerRepository.java` | PK `custId` |
| `TRANSACT` | `CVTRA05Y` | `repository/TransactionRepository.java` | PK `tranId`; `findByCardNum` replaces the transaction-by-card AIX |
| `USRSEC` | `CSUSR01Y` | `repository/UserSecurityRepository.java` | lookup by `usrId` (authentication) |
| `TCATBAL` | `CVTRA01Y` | `repository/TransactionCategoryBalanceRepository.java` | composite key (account + type + category) |
| `DISCGRP` | `CVTRA02Y` | `repository/DisclosureGroupRepository.java` | composite key (group + type + category) |
| `TRANTYPE` | `CVTRA03Y` | `repository/TransactionTypeRepository.java` | reference lookup |
| `TRANCATG` | `CVTRA04Y` | `repository/TransactionCategoryRepository.java` | composite key (type + category) |

> **Alternate-index parity.** The CSD defines two alternate-index PATHs — `CARDAIX`
> (`CARDDATA.VSAM.AIX.PATH`) and `CXACAIX` (`CARDXREF.VSAM.AIX.PATH`) — plus the transaction-by-card
> AIX. All three map to secondary database indexes created in
> `src/main/resources/db/migration/V3__indexes.sql` and are queried through the Spring Data derived
> queries listed above (`findByCardAcctId`, `findByXrefAcctId`, `findByCardNum`).


---

## 6. BMS Maps → Thymeleaf Templates + Screen Form DTOs

Each of the 17 BMS map sources (with its generated symbolic copybook) becomes one server-rendered
Thymeleaf template and one screen-form DTO. Templates preserve the **24 × 80** field / label /
length / colour / PF-key contract (PF3 = back, PF7 / PF8 = page up/down, ENTER = submit; Technical
Specification §0.3.4).

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
the catalog listing and the CSD. IDCAMS `DEFINE CLUSTER` / `REPRO` become Flyway DDL and seed data;
`SORT` becomes a Java `Comparator` / SQL `ORDER BY`; GDG output becomes job-instance versioning
(Technical Specification §0.6.3).

| Legacy artifact | z/OS utility / role | Java / Spring target |
| :-------------- | :------------------ | :------------------- |
| `legacy/jcl/ACCTFILE.jcl` | IDCAMS define + load Account master | `src/main/resources/db/migration/V1__schema.sql` (account table DDL + load) |
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
| `legacy/jcl/TRANIDX.jcl` | IDCAMS define AIX on transaction file | `src/main/resources/db/migration/V3__indexes.sql` (secondary index on `transaction.card_num`) |
| `legacy/jcl/COMBTRAN.jcl` + `legacy/ctl/REPROCT.ctl` + `legacy/proc/REPROC.prc` | `PGM=SORT`, `SORT FIELDS=(TRAN-ID,A)` | `batch/TransactionCombineJobConfig.java` — Java `Comparator` ascending by `tranId` / `ORDER BY tran_id` |
| `legacy/jcl/DALYREJS.jcl` + `legacy/jcl/DEFGDGB.jcl` | reject dataset (LRECL 430) + GDG base define | reject `FlatFileItemWriter` (350 B transaction + 80 B reason = 430 B) + Spring Batch job-instance versioning (GDG generations) |
| `legacy/jcl/PRTCATBL.jcl` + `legacy/jcl/REPTFILE.jcl` | category-balance / report output | `batch/CategoryBalancePrintJobConfig.java` |
| `legacy/jcl/CBADMCDJ.jcl` | admin batch driver | `batch/AdminBatchJobConfig.java` |
| `legacy/proc/TRANREPT.prc` | transaction-report PROC | batch step configuration for `TransactionReportJobConfig` (driver `CBTRN03C`, see [§3](#3-batch-cobol-programs--jcl--spring-batch-jobs)) |
| `legacy/csd/CARDDEMO.CSD` | CICS resource definitions (8 FILEs, 17 MAPSETs, 18 PROGRAMs, 18 TRANSACTIONS) | `config/SecurityConfig.java` + routing map — transaction → controller wiring (also the authoritative traceability reference) |
| `legacy/jcl/OPENFIL.jcl` | CICS file **enable** (`IEFBR14`) | **N/A — managed by the Spring connection pool; intentional non-migration, see [§8](#8-intentional-non-migrations-gaps-ledger)** |
| `legacy/jcl/CLOSEFIL.jcl` | CICS file **disable** (`IEFBR14`) | **N/A — managed by the Spring connection pool; intentional non-migration, see [§8](#8-intentional-non-migrations-gaps-ledger)** |

> **29-JCL cross-check.** All 29 JCL jobs are now accounted for — **9** as business batch programs
> in [§3](#3-batch-cobol-programs--jcl--spring-batch-jobs) (`POSTTRAN`, `INTCALC`, `CREASTMT`,
> `READACCT`, `READCARD`, `READXREF`, `READCUST`, `TRANBKP`, `TRANREPT`) and **20** here in §7
> (`ACCTFILE`, `CARDFILE`, `CUSTFILE`, `DEFCUST`, `XREFFILE`, `TRANFILE`, `TCATBALF`, `TRANCATG`,
> `TRANTYPE`, `DISCGRP`, `DUSRSECJ`, `TRANIDX`, `COMBTRAN`, `DALYREJS`, `DEFGDGB`, `PRTCATBL`,
> `REPTFILE`, `CBADMCDJ`, `OPENFIL`, `CLOSEFIL`) = **29 total**. Both PROCs (`REPROC.prc`,
> `TRANREPT.prc`), the sort control member (`REPROCT.ctl`) and the CSD (`CARDDEMO.CSD`) are also
> mapped above.

---

## 8. Intentional Non-Migrations (Gaps Ledger)

The following three constructs are **deliberately not migrated to Java code**. Each is logged here
(and in [`decision-log.md`](./decision-log.md)) so that coverage is provably complete rather than
silently reduced.

| Construct | Reason not migrated | How coverage is preserved |
| :-------- | :------------------ | :------------------------ |
| `legacy/cpy/UNUSED1Y.cpy` | Dead code — not referenced (`COPY`d) by any program in the codebase. | Logged here and in `decision-log.md`; excluded deliberately, so no orphaned entity/DTO is created. |
| `COCRDSEC` (CSD program, transaction `CDV1`; no `.cbl`) | CICS-only card-detail security variant with **no COBOL business logic** to translate. | Realized as `config/SecurityConfig.java` method/URL authorization rather than a standalone service. |
| `legacy/jcl/OPENFIL.jcl`, `legacy/jcl/CLOSEFIL.jcl` | CICS file **enable/disable** (`IEFBR14`) operations that toggle VSAM availability to the CICS region. | Not applicable under a Spring-managed connection pool, which opens/closes connections automatically. Logged as N/A. |

**These are the only intentional gaps.** With all three explicitly accounted for, forward coverage
of every COBOL / z/OS construct is **100 %**, reconciling exactly with the
[Coverage Summary](#1-coverage-summary).

---

## 9. Reverse-Direction Convention (Java → Legacy)

Traceability is **bidirectional**. In addition to the forward tables above, every generated Java
class carries a Javadoc **origin tag** citing the `legacy/` source path (and, where relevant, the
CICS transaction id or JCL job) from which it was derived. The convention is a single Javadoc line
of the form:

```java
/**
 * Origin: legacy/cbl/COSGN00C.cbl (CICS tran CC00).
 * ... class description ...
 */
```

For artifacts synthesised from more than one legacy member, the origin tag lists each source, e.g.
`Origin: legacy/cbl/CBTRN02C.cbl + legacy/jcl/POSTTRAN.jcl`. This lets a reader navigate from any
Java class back to the exact COBOL/JCL/BMS/copybook it replaces — the property that makes the
matrix bidirectional per the Explainability rule.

**Concrete examples:**

| Java artifact | Javadoc origin tag (reverse citation) |
| :------------ | :------------------------------------ |
| `domain/Account.java` | `Origin: legacy/cpy/CVACT01Y.cpy (ACCTDAT, RECLN 300)` |
| `batch/PostTransactionJobConfig.java` | `Origin: legacy/cbl/CBTRN02C.cbl + legacy/jcl/POSTTRAN.jcl` |
| `web/controller/SignonController.java` | `Origin: legacy/cbl/COSGN00C.cbl (CICS tran CC00)` |

> Because each forward row above has a corresponding reverse origin tag on its target class, the two
> directions are mutually consistent and jointly guarantee the 100 %, no-gap traceability the
> Explainability rule requires.
