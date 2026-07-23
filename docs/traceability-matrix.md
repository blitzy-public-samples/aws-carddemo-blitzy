# CardDemo Modernization — Bidirectional Traceability Matrix

This matrix provides **100% bidirectional coverage** between the legacy AWS CardDemo
mainframe application (COBOL / CICS / VSAM / BMS / JCL / RACF) and its cloud-native
Java 21 / Spring Boot 4.1.0 + React 19 target, as mandated by the **Explainability rule**
(AAP 0.7.2, 0.7.4). Every legacy source construct maps *forward* to a target
implementation, and every target implementation traces *back* to a source construct — or
is explicitly flagged as rule-mandated infrastructure or as a derived pattern with no 1:1
legacy field.

Rationale for each non-trivial decision lives in `docs/decision-log.md`; this matrix maps
constructs only and keeps every cell terse. Legacy identifiers and paths are written
verbatim, including the preserved misspellings `ACCT-EXPIRAION-DATE` /
`CARD-EXPIRAION-DATE` and the COMMAREA condition names `CDEMO-USRTYP-ADMIN`,
`CDEMO-USRTYP-USER`, `CDEMO-PGM-ENTER`, `CDEMO-PGM-REENTER`; BMS mapsets use their actual
7-character base names (e.g. `COSGN00`) with no trailing `M` suffix.

## Direction Legend

Every table below uses the identical three columns — **Source Construct**,
**Target Implementation**, **Direction** — where the Direction value is one of:

- **`source → target`** — a legacy construct is transformed into the named target
  implementation (or is explicitly recorded as excluded, for audit completeness).
- **`target → source (derived)`** — a target artifact has no 1:1 legacy field; it is
  derived from a legacy behavioral pattern (the Source Construct cell names that pattern).
- **`target → rule`** — a target artifact has no legacy analogue; it is mandated by a
  user-specified rule or required for standalone operation (the Source Construct cell
  names the mandating rule).

## Coverage Summary

The legacy inventory reconciled against the repository (AAP 0.1.3) and covered in full below:

- **28 COBOL programs** (`app/cbl`): 17 online `CO*` + 10 batch `CB*` + 1 utility `CSUTLDTC`.
- **28 copybooks** (`app/cpy`): 27 active + 1 excluded (`UNUSED1Y.cpy`).
- **17 BMS mapsets** (`app/bms`) and **17 symbolic-map copybooks** (`app/cpy-bms`).
- **29 JCL job streams** (`app/jcl`) + **2 procs** (`app/proc`).
- **Configuration & catalog**: `app/catlg/LISTCAT.txt`, `app/csd/CARDDEMO.CSD`,
  `app/ctl/REPROCT.ctl`.
- **Seed data**: 9 ASCII fixtures (`app/data/ASCII`); 12 EBCDIC binaries
  (`app/data/EBCDIC`) recorded as excluded.
- Plus rule-mandated build, containerization, observability, and documentation targets.

## 1. Online COBOL Programs (17) → Services / Controllers

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COSGN00C` `[app/cbl/COSGN00C.cbl]` | `auth-service` — `AuthenticationService` + `AuthenticationController` (`POST /auth/signon`, CICS `CC00`) | `source → target` |
| `COMEN01C` `[app/cbl/COMEN01C.cbl]` | `api-gateway` — `MenuController` (user menu, CICS `CM00`) | `source → target` |
| `COADM01C` `[app/cbl/COADM01C.cbl]` | `api-gateway` — `MenuController` (admin menu, CICS `CA00`) | `source → target` |
| `COACTVWC` `[app/cbl/COACTVWC.cbl]` | `account-service` — `AccountService` / `AccountController` (`GET /accounts/{id}`, CICS `CAVW`) | `source → target` |
| `COACTUPC` `[app/cbl/COACTUPC.cbl]` | `account-service` — `AccountService` / `AccountController` (`PUT /accounts/{id}`, CICS `CAUP`, optimistic-lock update) | `source → target` |
| `COCRDLIC` `[app/cbl/COCRDLIC.cbl]` | `card-service` — `CardService` / `CardController` (list, 7 rows/page, CICS `CCLI`) | `source → target` |
| `COCRDSLC` `[app/cbl/COCRDSLC.cbl]` | `card-service` — `CardService` / `CardController` (detail, CICS `CCDL`) | `source → target` |
| `COCRDUPC` `[app/cbl/COCRDUPC.cbl]` | `card-service` — `CardService` / `CardController` (update, CICS `CCUP`) | `source → target` |
| `COTRN00C` `[app/cbl/COTRN00C.cbl]` | `transaction-service` — `TransactionService` / `TransactionController` (list, CICS `CT00`) | `source → target` |
| `COTRN01C` `[app/cbl/COTRN01C.cbl]` | `transaction-service` — `TransactionService` / `TransactionController` (view, CICS `CT01`) | `source → target` |
| `COTRN02C` `[app/cbl/COTRN02C.cbl]` | `transaction-service` — `TransactionService` / `TransactionController` (add, 16-digit id generation, CICS `CT02`) | `source → target` |
| `COBIL00C` `[app/cbl/COBIL00C.cbl]` | `billpay-service` — `BillPaymentService` + controller (`POST /billpay`, CICS `CB00`; available credit = limit − balance) | `source → target` |
| `CORPT00C` `[app/cbl/CORPT00C.cbl]` | `reporting-service` — `ReportService` + controller (`POST /reports`, CICS `CR00`, async job launch) | `source → target` |
| `COUSR00C` `[app/cbl/COUSR00C.cbl]` | `user-service` — `UserService` / `UserController` (list, CICS `CU00`) | `source → target` |
| `COUSR01C` `[app/cbl/COUSR01C.cbl]` | `user-service` — `UserService` / `UserController` (add, CICS `CU01`) | `source → target` |
| `COUSR02C` `[app/cbl/COUSR02C.cbl]` | `user-service` — `UserService` / `UserController` (update, CICS `CU02`) | `source → target` |
| `COUSR03C` `[app/cbl/COUSR03C.cbl]` | `user-service` — `UserService` / `UserController` (delete, CICS `CU03`) | `source → target` |

## 2. Batch COBOL Programs (10) + Utility (1) → Spring Batch / Services

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `CBTRN02C` `[app/cbl/CBTRN02C.cbl]` | `batch-service` — `TransactionPostingJob` validation processor (reject codes 100–103; 430-byte reject record = 350 + 80); paired with `POSTTRAN.jcl` | `source → target` |
| `CBTRN01C` `[app/cbl/CBTRN01C.cbl]` | `batch-service` — `DataManagement*Job` (daily-transaction validation / refresh) | `source → target` |
| `CBTRN03C` `[app/cbl/CBTRN03C.cbl]` | `batch-service` — `DataManagement*Job` (transaction reporting / processing) | `source → target` |
| `CBACT01C` `[app/cbl/CBACT01C.cbl]` | `batch-service` — `DataManagement*Job` (account file read / print) | `source → target` |
| `CBACT02C` `[app/cbl/CBACT02C.cbl]` | `batch-service` — `DataManagement*Job` (card file) | `source → target` |
| `CBACT03C` `[app/cbl/CBACT03C.cbl]` | `batch-service` — `DataManagement*Job` (xref file) | `source → target` |
| `CBACT04C` `[app/cbl/CBACT04C.cbl]` | `batch-service` — `InterestCalculationService` / `InterestCalculationJob` (interest = `(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`; DEFAULT-group fallback on status '23'); paired with `INTCALC.jcl` | `source → target` |
| `CBCUS01C` `[app/cbl/CBCUS01C.cbl]` | `batch-service` — `DataManagement*Job` (customer file) | `source → target` |
| `CBSTM03A.CBL` `[app/cbl/CBSTM03A.CBL]` | `reporting-service` — `StatementGenerationJob` (statement text + HTML); paired with `CREASTMT.JCL` | `source → target` |
| `CBSTM03B.CBL` `[app/cbl/CBSTM03B.CBL]` | `reporting-service` — `StatementGenerationJob` I/O subroutine (uses `COSTM01.CPY` layout) | `source → target` |
| `CSUTLDTC` `[app/cbl/CSUTLDTC.cbl]` | `carddemo-common` — `util/DateUtil.java` (CEEDAYS / Lillian validation → `java.time` STRICT parsing) | `source → target` |

## 3. Persistent Record Copybooks → JPA Entities + Tables

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `CVCUS01Y` `[app/cpy/CVCUS01Y.cpy]` | `carddemo-common` `domain/Customer.java` + `customers` table (`CUST-ID` `PIC 9(9)` → `@Id`) | `source → target` |
| `CVACT01Y` `[app/cpy/CVACT01Y.cpy]` | `domain/Account.java` + `accounts` table (COMP-3 balances → `BigDecimal` / `NUMERIC(12,2)`; `@Version` added; `ACCT-EXPIRAION-DATE` preserved) | `source → target` |
| `CVACT02Y` `[app/cpy/CVACT02Y.cpy]` | `domain/Card.java` + `cards` table (`CARD-NUM` `X(16)` → `@Id`; FK to account; `CARD-EXPIRAION-DATE` preserved) | `source → target` |
| `CVACT03Y` `[app/cpy/CVACT03Y.cpy]` | `domain/CardXref.java` + `card_xref` table (CXACAIX `CARD-NUM` ↔ `CUST-ID` ↔ `ACCT-ID` linkage) | `source → target` |
| `CVTRA05Y` `[app/cpy/CVTRA05Y.cpy]` | `domain/Transaction.java` + `transactions` table (`TRAN-AMT` COMP-3 → `NUMERIC(11,2)`) | `source → target` |
| `CVTRA06Y` `[app/cpy/CVTRA06Y.cpy]` | `domain/DailyTransaction.java` (daily feed layout) | `source → target` |
| `CVTRA01Y` `[app/cpy/CVTRA01Y.cpy]` | `domain/TranCatBal.java` (compound key → `@IdClass`: acct + type + cat) | `source → target` |
| `CVTRA02Y` `[app/cpy/CVTRA02Y.cpy]` | `domain/DiscGroup.java` (compound key → `@IdClass`; `DIS-INT-RATE` COMP-3) | `source → target` |
| `CVTRA03Y` `[app/cpy/CVTRA03Y.cpy]` | `domain/TranType.java` (reference entity, 7 rows) | `source → target` |
| `CVTRA04Y` `[app/cpy/CVTRA04Y.cpy]` | `domain/TranCatg.java` (compound key → `@IdClass`: type + cat) | `source → target` |
| `CSUSR01Y` `[app/cpy/CSUSR01Y.cpy]` | `domain/SecurityUser.java` + `security_users` table (`SEC-USR-PWD` plaintext → encoded hash; `SEC-USR-TYPE` → role) | `source → target` |
| `CUSTREC` `[app/cpy/CUSTREC.cpy]` | `domain/Customer.java` (alternate / flat customer layout; covered by `Customer` entity) | `source → target` |
| `CVCRD01Y` `[app/cpy/CVCRD01Y.cpy]` | `domain/Card.java` (alternate card view; not a distinct entity — covered by `Card`) | `source → target` |
| `CVTRA07Y` `[app/cpy/CVTRA07Y.cpy]` | `domain/Transaction.java` (transaction-related reference view; statement usage) | `source → target` |
| Persistent-entity repositories (each copybook + file-access COBOL, e.g. `[app/cbl/COCRDLIC.cbl]`) | `repository/*Repository.java` per entity (Spring Data JPA; AIX browses → derived queries `findByAccountId`, `findByOrigTsBetween`) | `source → target` |

## 4. Shared / Session / Message Copybooks → Common Types

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COCOM01Y` `[app/cpy/COCOM01Y.cpy]` | `carddemo-common` `dto/SessionContext.java` (COMMAREA → session context; `CDEMO-USRTYP-ADMIN` / `CDEMO-USRTYP-USER`, `CDEMO-PGM-ENTER` / `CDEMO-PGM-REENTER` → enums / constants; `CDEMO-LAST-MAP` `PIC X(7)`) | `source → target` |
| `COADM02Y` `[app/cpy/COADM02Y.cpy]` | `constant/MenuOptions.java` (admin menu options) | `source → target` |
| `COMEN02Y` `[app/cpy/COMEN02Y.cpy]` | `constant/MenuOptions.java` (user menu options) | `source → target` |
| `COTTL01Y` `[app/cpy/COTTL01Y.cpy]` | `constant/Titles.java` (screen titles, verbatim) | `source → target` |
| `CSMSG01Y` `[app/cpy/CSMSG01Y.cpy]` | `constant/Messages.java` (message text, verbatim) | `source → target` |
| `CSMSG02Y` `[app/cpy/CSMSG02Y.cpy]` | `constant/Messages.java` (message text, verbatim) | `source → target` |
| `CSLKPCDY` `[app/cpy/CSLKPCDY.cpy]` | `constant/LookupCodes.java` (lookup codes, verbatim) | `source → target` |
| `COSTM01` `[app/cpy/COSTM01.CPY]` | `reporting-service` statement layout consumed by `StatementGenerationJob` | `source → target` |
| `CSDAT01Y` `[app/cpy/CSDAT01Y.cpy]` | date DTO / fields consumed by `DateUtil` | `source → target` |
| `CSUTLDPY` `[app/cpy/CSUTLDPY.cpy]` | `DateUtil` parameter contract (date-utility parameters) | `source → target` |
| `CSUTLDWY` `[app/cpy/CSUTLDWY.cpy]` | `DateUtil` working-storage contract | `source → target` |
| `CSSETATY` `[app/cpy/CSSETATY.cpy]` | `frontend` screen-attribute semantics (shared components) | `source → target` |
| `CSSTRPFY` `[app/cpy/CSSTRPFY.cpy]` | `frontend` `components/PFKeyBar.tsx` (PF-key semantics) | `source → target` |
| `UNUSED1Y` `[app/cpy/UNUSED1Y.cpy]` | EXCLUDED (out of scope, AAP 0.2.2 — unused copybook, not transformed) | `source → target` |


## 5. BMS Mapsets (17) → React Pages

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COSGN00.bms` `[app/bms/COSGN00.bms]` | `frontend/src/pages/SignonPage.tsx` (password masked) | `source → target` |
| `COMEN01.bms` `[app/bms/COMEN01.bms]` | `frontend/src/pages/MainMenuPage.tsx` (role-gated) | `source → target` |
| `COADM01.bms` `[app/bms/COADM01.bms]` | `frontend/src/pages/AdminMenuPage.tsx` (admin-only) | `source → target` |
| `COACTVW.bms` `[app/bms/COACTVW.bms]` | `frontend/src/pages/AccountViewPage.tsx` (read-only) | `source → target` |
| `COACTUP.bms` `[app/bms/COACTUP.bms]` | `frontend/src/pages/AccountUpdatePage.tsx` (optimistic-lock conflict banner) | `source → target` |
| `COCRDLI.bms` `[app/bms/COCRDLI.bms]` | `frontend/src/pages/CardListPage.tsx` (7 rows/page) | `source → target` |
| `COCRDSL.bms` `[app/bms/COCRDSL.bms]` | `frontend/src/pages/CardDetailPage.tsx` | `source → target` |
| `COCRDUP.bms` `[app/bms/COCRDUP.bms]` | `frontend/src/pages/CardUpdatePage.tsx` | `source → target` |
| `COTRN00.bms` `[app/bms/COTRN00.bms]` | `frontend/src/pages/TranListPage.tsx` | `source → target` |
| `COTRN01.bms` `[app/bms/COTRN01.bms]` | `frontend/src/pages/TranViewPage.tsx` | `source → target` |
| `COTRN02.bms` `[app/bms/COTRN02.bms]` | `frontend/src/pages/TranAddPage.tsx` | `source → target` |
| `COBIL00.bms` `[app/bms/COBIL00.bms]` | `frontend/src/pages/BillPayPage.tsx` | `source → target` |
| `CORPT00.bms` `[app/bms/CORPT00.bms]` | `frontend/src/pages/ReportPage.tsx` | `source → target` |
| `COUSR00.bms` `[app/bms/COUSR00.bms]` | `frontend/src/pages/UserListPage.tsx` (admin) | `source → target` |
| `COUSR01.bms` `[app/bms/COUSR01.bms]` | `frontend/src/pages/UserAddPage.tsx` | `source → target` |
| `COUSR02.bms` `[app/bms/COUSR02.bms]` | `frontend/src/pages/UserUpdatePage.tsx` | `source → target` |
| `COUSR03.bms` `[app/bms/COUSR03.bms]` | `frontend/src/pages/UserDeletePage.tsx` | `source → target` |

## 6. BMS Symbolic-Map Copybooks (17) → DTOs + Types

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COSGN00.CPY` `[app/cpy-bms/COSGN00.CPY]` | Signon `*RequestDto` / `*ResponseDto` + `frontend/src/api` + `frontend/src/types` (SignonPage field contract) | `source → target` |
| `COMEN01.CPY` `[app/cpy-bms/COMEN01.CPY]` | Main-menu `*RequestDto` / `*ResponseDto` + `frontend/src/types` (MainMenuPage field contract) | `source → target` |
| `COADM01.CPY` `[app/cpy-bms/COADM01.CPY]` | Admin-menu `*RequestDto` / `*ResponseDto` + `frontend/src/types` (AdminMenuPage field contract) | `source → target` |
| `COACTVW.CPY` `[app/cpy-bms/COACTVW.CPY]` | Account-view `*RequestDto` / `*ResponseDto` + `frontend/src/types` (AccountViewPage field contract) | `source → target` |
| `COACTUP.CPY` `[app/cpy-bms/COACTUP.CPY]` | Account-update `*RequestDto` / `*ResponseDto` + `frontend/src/types` (AccountUpdatePage field contract) | `source → target` |
| `COCRDLI.CPY` `[app/cpy-bms/COCRDLI.CPY]` | Card-list `*RequestDto` / `*ResponseDto` + `frontend/src/types` (CardListPage field contract) | `source → target` |
| `COCRDSL.CPY` `[app/cpy-bms/COCRDSL.CPY]` | Card-detail `*RequestDto` / `*ResponseDto` + `frontend/src/types` (CardDetailPage field contract) | `source → target` |
| `COCRDUP.CPY` `[app/cpy-bms/COCRDUP.CPY]` | Card-update `*RequestDto` / `*ResponseDto` + `frontend/src/types` (CardUpdatePage field contract) | `source → target` |
| `COTRN00.CPY` `[app/cpy-bms/COTRN00.CPY]` | Tran-list `*RequestDto` / `*ResponseDto` + `frontend/src/types` (TranListPage field contract) | `source → target` |
| `COTRN01.CPY` `[app/cpy-bms/COTRN01.CPY]` | Tran-view `*RequestDto` / `*ResponseDto` + `frontend/src/types` (TranViewPage field contract) | `source → target` |
| `COTRN02.CPY` `[app/cpy-bms/COTRN02.CPY]` | Tran-add `*RequestDto` / `*ResponseDto` + `frontend/src/types` (TranAddPage field contract) | `source → target` |
| `COBIL00.CPY` `[app/cpy-bms/COBIL00.CPY]` | Bill-pay `*RequestDto` / `*ResponseDto` + `frontend/src/types` (BillPayPage field contract) | `source → target` |
| `CORPT00.CPY` `[app/cpy-bms/CORPT00.CPY]` | Report `*RequestDto` / `*ResponseDto` + `frontend/src/types` (ReportPage field contract) | `source → target` |
| `COUSR00.CPY` `[app/cpy-bms/COUSR00.CPY]` | User-list `*RequestDto` / `*ResponseDto` + `frontend/src/types` (UserListPage field contract) | `source → target` |
| `COUSR01.CPY` `[app/cpy-bms/COUSR01.CPY]` | User-add `*RequestDto` / `*ResponseDto` + `frontend/src/types` (UserAddPage field contract) | `source → target` |
| `COUSR02.CPY` `[app/cpy-bms/COUSR02.CPY]` | User-update `*RequestDto` / `*ResponseDto` + `frontend/src/types` (UserUpdatePage field contract) | `source → target` |
| `COUSR03.CPY` `[app/cpy-bms/COUSR03.CPY]` | User-delete `*RequestDto` / `*ResponseDto` + `frontend/src/types` (UserDeletePage field contract) | `source → target` |
| `CSSTRPFY.cpy` + `CSSETATY.cpy` + general screen shell | `frontend/src/components/Layout.tsx`, `Header.tsx`, `PFKeyBar.tsx`, `ErrorBanner.tsx` (shared shell; PF-key + attribute semantics) | `source → target` |


## 7. JCL Job Streams (29) + Procs (2) → Spring Batch Configuration

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `POSTTRAN.jcl` `[app/jcl/POSTTRAN.jcl]` | `batch-service` `TransactionPostingJob` (invokes `CBTRN02C`; DALYTRAN reader, DALYREJS reject writer) | `source → target` |
| `INTCALC.jcl` `[app/jcl/INTCALC.jcl]` | `batch-service` `InterestCalculationJob` (invokes `CBACT04C`) | `source → target` |
| `CREASTMT.JCL` `[app/jcl/CREASTMT.JCL]` | `reporting-service` `StatementGenerationJob` (invokes `CBSTM03A` / `CBSTM03B`) | `source → target` |
| `ACCTFILE.jcl` `[app/jcl/ACCTFILE.jcl]` | `db/migration` seed (`accounts`) + `DataManagement*Job` | `source → target` |
| `CARDFILE.jcl` `[app/jcl/CARDFILE.jcl]` | `db/migration` seed (`cards`) + `DataManagement*Job` | `source → target` |
| `CUSTFILE.jcl` `[app/jcl/CUSTFILE.jcl]` | `db/migration` seed (`customers`) + `DataManagement*Job` | `source → target` |
| `XREFFILE.jcl` `[app/jcl/XREFFILE.jcl]` | `db/migration` seed (`card_xref`) + `DataManagement*Job` | `source → target` |
| `TRANFILE.jcl` `[app/jcl/TRANFILE.jcl]` | `db/migration` seed (`transactions`) + `DataManagement*Job` | `source → target` |
| `TRANTYPE.jcl` `[app/jcl/TRANTYPE.jcl]` | `db/migration` reference seed (`tran_type`) + `DataManagement*Job` | `source → target` |
| `TRANCATG.jcl` `[app/jcl/TRANCATG.jcl]` | `db/migration` reference seed (`tran_catg`) + `DataManagement*Job` | `source → target` |
| `DISCGRP.jcl` `[app/jcl/DISCGRP.jcl]` | `db/migration` reference seed (`disc_group`) + `DataManagement*Job` | `source → target` |
| `TCATBALF.jcl` `[app/jcl/TCATBALF.jcl]` | `db/migration` seed (`tran_cat_bal`) + `DataManagement*Job` | `source → target` |
| `DUSRSECJ.jcl` `[app/jcl/DUSRSECJ.jcl]` | `db/migration` seed (`security_users`) + `DataManagement*Job` | `source → target` |
| `DEFCUST.jcl` `[app/jcl/DEFCUST.jcl]` | `db/migration` customer-default seed + `DataManagement*Job` | `source → target` |
| `DEFGDGB.jcl` `[app/jcl/DEFGDGB.jcl]` | GDG-base definition → database backup / PITR config (out of application-code scope) | `source → target` |
| `CLOSEFIL.jcl` `[app/jcl/CLOSEFIL.jcl]` | file-close lifecycle → datasource lifecycle config (out of application-code scope) | `source → target` |
| `OPENFIL.jcl` `[app/jcl/OPENFIL.jcl]` | file-open lifecycle → datasource lifecycle config (out of application-code scope) | `source → target` |
| `TRANBKP.jcl` `[app/jcl/TRANBKP.jcl]` | transaction backup → database backup / PITR config (out of application-code scope) | `source → target` |
| `TRANIDX.jcl` `[app/jcl/TRANIDX.jcl]` | AIX build → JPA / DDL secondary-index config (out of application-code scope) | `source → target` |
| `COMBTRAN.jcl` `[app/jcl/COMBTRAN.jcl]` | `batch-service` `DataManagement*Job` (combine transaction files) | `source → target` |
| `DALYREJS.jcl` `[app/jcl/DALYREJS.jcl]` | `TransactionPostingJob` reject `ItemWriter` (DALYREJS reject file, 430-byte record) | `source → target` |
| `PRTCATBL.jcl` `[app/jcl/PRTCATBL.jcl]` | `batch-service` `DataManagement*Job` (print category balance) | `source → target` |
| `REPTFILE.jcl` `[app/jcl/REPTFILE.jcl]` | `reporting-service` `DataManagement*Job` (report file) | `source → target` |
| `TRANREPT.jcl` `[app/jcl/TRANREPT.jcl]` | `reporting-service` `DataManagement*Job` (transaction report) | `source → target` |
| `READACCT.jcl` `[app/jcl/READACCT.jcl]` | `batch-service` `DataManagement*Job` (account read / print, with `CBACT01C`) | `source → target` |
| `READCARD.jcl` `[app/jcl/READCARD.jcl]` | `batch-service` `DataManagement*Job` (card read / print, with `CBACT02C`) | `source → target` |
| `READCUST.jcl` `[app/jcl/READCUST.jcl]` | `batch-service` `DataManagement*Job` (customer read / print, with `CBCUS01C`) | `source → target` |
| `READXREF.jcl` `[app/jcl/READXREF.jcl]` | `batch-service` `DataManagement*Job` (xref read / print, with `CBACT03C`) | `source → target` |
| `CBADMCDJ.jcl` `[app/jcl/CBADMCDJ.jcl]` | `batch-service` `DataManagement*Job` (admin card-demo job) | `source → target` |
| `REPROC.prc` `[app/proc/REPROC.prc]` | `config/JobSchedulingConfig.java` (report proc; TDQ 'JOBS' / JES async → `JobLauncher`) | `source → target` |
| `TRANREPT.prc` `[app/proc/TRANREPT.prc]` | `config/JobSchedulingConfig.java` (transaction-report proc, with `CORPT00C`) | `source → target` |

## 8. Configuration, Catalog, Control, Seed Data + Security

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `app/catlg/LISTCAT.txt` | `db/migration/V1__create_schema.sql` (10 tables + FK constraints + secondary indexes from 3 alternate indexes) | `source → target` |
| `app/csd/CARDDEMO.CSD` | `auth-service` `config/SecurityConfig.java` (`SecurityFilterChain`, route / role rules) + gateway routes | `source → target` |
| `app/ctl/REPROCT.ctl` | Spring Batch sort / step configuration (report processing) | `source → target` |
| `app/data/ASCII/trantype.txt`, `trancatg.txt`, `discgrp.txt` | `db/migration/V2__seed_reference_data.sql` (7 tran types, 18 categories, 51 disclosure groups) | `source → target` |
| `app/data/ASCII/custdata.txt`, `acctdata.txt`, `carddata.txt`, `cardxref.txt`, `tcatbal.txt`, `dailytran.txt` | `db/migration/V3__seed_test_data.sql` (50 customers / accounts / cards / xrefs, 100 category balances, transactions) | `source → target` |
| `COSGN00C` `[app/cbl/COSGN00C.cbl]` + `CSUSR01Y` `[app/cpy/CSUSR01Y.cpy]` | `auth-service` `security/UserDetailsServiceImpl.java` + `PasswordEncoderConfig` (RACF / USRSEC → BCrypt; `SEC-USR-TYPE` 'A' / 'U' → `ROLE_ADMIN` / `ROLE_USER`) | `source → target` |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.*` (12 binaries) | EXCLUDED (out of scope, AAP 0.2.2 — ASCII fixtures drive seeding) | `source → target` |


## 9. Derived Targets (No 1:1 Legacy Field)

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| CICS read-snapshot-compare-rewrite lock pattern `[app/cbl/COACTUPC.cbl:L517-L523]` | `Account.version` (`@Version` optimistic-lock column) | `target → source (derived)` |
| Multi-file CICS `REWRITE` units `[app/cbl/COACTUPC.cbl:L4066-L4090]` | `@Transactional` service boundaries (account + customer commit / roll back together) | `target → source (derived)` |
| Browse-last-then-increment id generation `[app/cbl/COTRN02C.cbl:L444-L451]` | Database sequence / identity for transaction id (16-digit zero-padded form preserved) | `target → source (derived)` |
| Application-enforced read order `[app/cbl/CBTRN02C.cbl:L371-L397, app/cpy/CVACT03Y.cpy]` | Database FK constraints (card ↔ customer ↔ account) + service-layer validation | `target → source (derived)` |
| File-status / RESP reject handling `[app/cbl/CBTRN02C.cbl:L176-L182, L380-L421]` | Reject-code domain exceptions + reject `ItemWriter` (430-byte reject record) | `target → source (derived)` |

## 10. Rule-Mandated / Standalone-Operation Infrastructure (No Legacy Analogue)

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Respecting-.gitignore rule (AAP 0.7.2) | `.gitignore` (Java + Node ignore patterns) | `target → rule` |
| Standalone build (AAP 0.5) | root `pom.xml` (aggregator / parent BOM) + per-module `pom.xml` (×10: `carddemo-common`, `auth-service`, `user-service`, `account-service`, `card-service`, `transaction-service`, `billpay-service`, `reporting-service`, `batch-service`, `api-gateway`) | `target → rule` |
| Standalone build (React 19 / Node 24) | `frontend/package.json` | `target → rule` |
| Containerization + orchestration (standalone operation) | `Dockerfile` (per service + frontend), `docker-compose.yml`, `k8s/*.yaml` (`deployment-*`, `service-*`, `configmap`, `secret`) | `target → rule` |
| Observability rule (AAP 0.7.5) | `application.yml` (Actuator health / readiness / liveness + `/actuator/prometheus`) | `target → rule` |
| Observability rule (AAP 0.7.5) | `logback-spring.xml` (structured JSON logging + correlation ids via MDC) | `target → rule` |
| Observability rule (distributed tracing) | Micrometer Tracing + OpenTelemetry bridge configuration | `target → rule` |
| Observability rule (AAP 0.7.5) | `observability/prometheus.yml` (scrape config) | `target → rule` |
| Observability rule (AAP 0.7.5) | `observability/grafana-dashboard.json` (dashboard template) | `target → rule` |
| Explainability rule (AAP 0.7.2 / 0.7.3) | `docs/decision-log.md` | `target → rule` |
| Explainability rule (AAP 0.7.2 / 0.7.4) | `docs/traceability-matrix.md` (this file) | `target → rule` |
| Standalone-operation documentation | `README-target.md` | `target → rule` |
| Observability rule / cross-cutting infra | `carddemo-common` `config/` (observability, tracing, exception handling) + `exception/` handlers | `target → rule` |

## Coverage Assertion

This matrix asserts **100% bidirectional coverage**:

- **Forward (`source → target`):** every legacy source construct appears exactly once as a
  Source Construct — all 28 COBOL programs (17 online + 10 batch + `CSUTLDTC`), all 28
  `app/cpy` copybooks (27 active + `UNUSED1Y.cpy` flagged excluded), all 17 BMS mapsets,
  all 17 `app/cpy-bms` symbolic-map copybooks, all 29 JCL job streams + 2 procs, and every
  configuration, catalog, control, and ASCII seed artifact.
- **Reverse (`target → source (derived)` / `target → rule`):** every target implementation
  either appears as a Target Implementation in a forward row, or is recorded in Section 9
  (derived from a legacy behavioral pattern with no 1:1 field) or Section 10 (rule-mandated
  or standalone-operation infrastructure with no legacy analogue).
- **Excluded items, recorded for audit completeness:** `app/cpy/UNUSED1Y.cpy` (unused
  copybook) and `app/data/EBCDIC/AWS.M2.CARDDEMO.*` (12 binary data sets; the ASCII
  fixtures in `app/data/ASCII` drive seeding). The developer-only CICS artifacts
  `COCRDSEC` (program) and transaction `CDV1` defined in `app/csd/CARDDEMO.CSD` are
  outside the 17-program online scope and are not transformed.

No source construct is left unmapped and no target artifact is left untraceable.
