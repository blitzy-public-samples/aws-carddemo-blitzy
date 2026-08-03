# CardDemo Modernization — Bidirectional Traceability Matrix

This matrix maps the legacy AWS CardDemo mainframe application (COBOL / CICS / VSAM /
BMS / JCL / RACF) to its cloud-native Java 21 / Spring Boot 4.1.0 + React 19 target
design, as mandated by the **Explainability rule** (AAP 0.7.2, 0.7.4). Every legacy
source construct maps *forward* to its target implementation, and every target
implementation traces *back* to a source construct — or is explicitly flagged as
rule-mandated infrastructure or as a derived pattern with no 1:1 legacy field.

> **Scope of this matrix.** The forward direction enumerates the COMPLETE legacy
> inventory and the target it maps to in the end-state design. The migration is
> delivered across multiple tranches, so the *target* implementations are a mix of
> **delivered** (present in this foundation tranche — the shared `carddemo-common`
> library, the consolidated Flyway migration set that provisions the whole
> `carddemo` schema and its seed data, per-service configuration, observability,
> containers/orchestration, and governance docs) and **planned** (per-screen pages
> and the remaining controllers / repositories delivered in later tranches). A bare
> "100% of files already present on disk" claim is therefore **not** asserted here;
> coverage is verified per tranche as the target files land. Section 11 enumerates
> exactly what this tranche delivers, and every migration path asserted anywhere in
> this matrix resolves to a file on disk.

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
| `CVTRA06Y` `[app/cpy/CVTRA06Y.cpy]` | `domain/DailyTransaction.java` — JPA `@Entity` + `daily_transactions` table (relational staging image of the sequential `AWS.M2.CARDDEMO.DALYTRAN` feed) read by `transaction-service` `DailyTransactionRepository` (`JpaRepository`, ascending `DALYTRAN-ID` browse order) | `source → target` |
| `CVTRA01Y` `[app/cpy/CVTRA01Y.cpy]` | `domain/TranCatBal.java` + `tran_cat_bal` table (compound key → `@IdClass`, PK column order `trancat_acct_id, trancat_type_cd, trancat_cd`; FK `fk_tran_cat_bal_acct` → `accounts`) | `source → target` |
| `CVTRA02Y` `[app/cpy/CVTRA02Y.cpy]` | `domain/DiscGroup.java` + `disclosure_group` table (compound key → `@IdClass`, PK column order `dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd`; `DIS-INT-RATE` COMP-3 → `NUMERIC`) | `source → target` |
| `CVTRA03Y` `[app/cpy/CVTRA03Y.cpy]` | `domain/TranType.java` + `tran_type` table (reference entity, 7 rows) | `source → target` |
| `CVTRA04Y` `[app/cpy/CVTRA04Y.cpy]` | `domain/TranCatg.java` + `tran_category` table (compound key → `@IdClass`, PK column order `tran_type_cd, tran_cat_cd`) | `source → target` |
| `CSUSR01Y` `[app/cpy/CSUSR01Y.cpy]` | `domain/SecurityUser.java` + `security_users` table (`SEC-USR-PWD` plaintext → encoded hash; `SEC-USR-TYPE` → role) | `source → target` |
| `CUSTREC` `[app/cpy/CUSTREC.cpy]` | `domain/Customer.java` (alternate / flat customer layout; covered by `Customer` entity) | `source → target` |
| `CVCRD01Y` `[app/cpy/CVCRD01Y.cpy]` | `domain/Card.java` (alternate card view; not a distinct entity — covered by `Card`) | `source → target` |
| `CVTRA07Y` `[app/cpy/CVTRA07Y.cpy]` | `reporting-service` transaction-report layout — `REPORT-NAME-HEADER` / `TRANSACTION-DETAIL-REPORT` (`DALYREPT`, "Daily Transaction Report") → `ReportMapper` + `batch-service` `TransactionReportItem` report row + report DTOs. NOT the `Transaction` entity (it is a report-formatting structure, not a persisted record) | `source → target` |
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
| COMMAREA-carried SCREEN context — `CDEMO-LAST-MAP` / `CDEMO-LAST-MAPSET` `PIC X(7)`, the `TRNNAME` / `PGMNAME` / `TITLE01` / `TITLE02` header fields, the line-23 `ERRMSG` `X(78)` region and the line-24 key legend `[app/cpy/COCOM01Y.cpy:L18-L45; app/bms/COSGN00.bms]` | `frontend/src/components/Layout.tsx` `useScreenChrome` context (`ScreenChrome` / `ScreenChromeContextValue`: `transactionId`, `programName`, `title01`, `title02`, `errorMessage`, `infoMessage`, `pfKeys`; `setChrome` merge / `resetChrome`) — the per-screen half of the COMMAREA, held per mount, while the identity half stays in `SessionContext` (row above in §5) | `source → target` |


## 7. JCL Job Streams (29) + Procs (2) → Spring Batch Configuration

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `POSTTRAN.jcl` `[app/jcl/POSTTRAN.jcl]` | `batch-service` `TransactionPostingJob` (invokes `CBTRN02C`; DALYTRAN reader, DALYREJS reject writer) | `source → target` |
| `INTCALC.jcl` `[app/jcl/INTCALC.jcl]` | `batch-service` `InterestCalculationJob` (invokes `CBACT04C`) | `source → target` |
| `CREASTMT.JCL` `[app/jcl/CREASTMT.JCL]` | `reporting-service` `StatementGenerationJob` (invokes `CBSTM03A` / `CBSTM03B`) | `source → target` |
| `ACCTFILE.jcl` `[app/jcl/ACCTFILE.jcl]` | `V3__seed_test_data.sql` seed (`accounts`, 50 rows) + `DataManagement*Job` | `source → target` |
| `CARDFILE.jcl` `[app/jcl/CARDFILE.jcl]` | `V3__seed_test_data.sql` seed (`cards`, 50 rows) + `DataManagement*Job` | `source → target` |
| `CUSTFILE.jcl` `[app/jcl/CUSTFILE.jcl]` | `V3__seed_test_data.sql` seed (`customers`, 50 rows) + `DataManagement*Job` | `source → target` |
| `XREFFILE.jcl` `[app/jcl/XREFFILE.jcl]` | `V3__seed_test_data.sql` seed (`card_xref`, 50 rows) + `DataManagement*Job` | `source → target` |
| `TRANFILE.jcl` `[app/jcl/TRANFILE.jcl]` | `V3__seed_test_data.sql` seed (`transactions`, plus the staged `daily_transactions` feed the posting job consumes) + `DataManagement*Job` | `source → target` |
| `TRANTYPE.jcl` `[app/jcl/TRANTYPE.jcl]` | `V2__seed_reference_data.sql` reference seed (`tran_type`, 7 rows) + `DataManagement*Job` | `source → target` |
| `TRANCATG.jcl` `[app/jcl/TRANCATG.jcl]` | `V2__seed_reference_data.sql` reference seed (`tran_category`, 18 rows) + `DataManagement*Job` | `source → target` |
| `DISCGRP.jcl` `[app/jcl/DISCGRP.jcl]` | `V2__seed_reference_data.sql` reference seed (`disclosure_group`, 51 rows) + `DataManagement*Job` | `source → target` |
| `TCATBALF.jcl` `[app/jcl/TCATBALF.jcl]` | `V3__seed_test_data.sql` seed (`tran_cat_bal`, 50 rows) + `DataManagement*Job` | `source → target` |
| `DUSRSECJ.jcl` `[app/jcl/DUSRSECJ.jcl]` | `V3__seed_test_data.sql` seed (`security_users`, 10 rows; `{bcrypt}`-prefixed hashes, decision-log §3) + `DataManagement*Job` | `source → target` |
| `DEFCUST.jcl` `[app/jcl/DEFCUST.jcl]` | `V3__seed_test_data.sql` customer-default seed + `DataManagement*Job` | `source → target` |
| `DEFGDGB.jcl` `[app/jcl/DEFGDGB.jcl]` | GDG-base definition → database backup / PITR config (out of application-code scope) | `source → target` |
| `CLOSEFIL.jcl` `[app/jcl/CLOSEFIL.jcl]` | file-close lifecycle → datasource lifecycle config (out of application-code scope) | `source → target` |
| `OPENFIL.jcl` `[app/jcl/OPENFIL.jcl]` | file-open lifecycle → datasource lifecycle config (out of application-code scope) | `source → target` |
| `TRANBKP.jcl` `[app/jcl/TRANBKP.jcl]` | transaction backup → database backup / PITR config (out of application-code scope) | `source → target` |
| `TRANIDX.jcl` `[app/jcl/TRANIDX.jcl]` | AIX build → JPA / DDL secondary-index config (out of application-code scope) | `source → target` |
| `COMBTRAN.jcl` `[app/jcl/COMBTRAN.jcl]` | `batch-service` `DataManagement*Job` (combine transaction files) | `source → target` |
| `DALYREJS.jcl` `[app/jcl/DALYREJS.jcl]` | `TransactionPostingJob` reject `ItemWriter` (DALYREJS reject file, 430-byte record) | `source → target` |
| `PRTCATBL.jcl` `[app/jcl/PRTCATBL.jcl]` | `batch-service` `DataManagement*Job` (print category balance) | `source → target` |
| `REPTFILE.jcl` `[app/jcl/REPTFILE.jcl]` | `reporting-service` `DataManagement*Job` (report file) | `source → target` |
| `TRANREPT.jcl` `[app/jcl/TRANREPT.jcl]` | `batch-service` `config/DataManagementJobConfig.java` → `transactionDetailReportJob` (`CBTRN03C` transaction-detail report) + `batch/TransactionDetailReportWriter.java` | `source → target` |
| `READACCT.jcl` `[app/jcl/READACCT.jcl]` | `batch-service` `DataManagement*Job` (account read / print, with `CBACT01C`) | `source → target` |
| `READCARD.jcl` `[app/jcl/READCARD.jcl]` | `batch-service` `DataManagement*Job` (card read / print, with `CBACT02C`) | `source → target` |
| `READCUST.jcl` `[app/jcl/READCUST.jcl]` | `batch-service` `DataManagement*Job` (customer read / print, with `CBCUS01C`) | `source → target` |
| `READXREF.jcl` `[app/jcl/READXREF.jcl]` | `batch-service` `DataManagement*Job` (xref read / print, with `CBACT03C`) | `source → target` |
| `CBADMCDJ.jcl` `[app/jcl/CBADMCDJ.jcl]` | `batch-service` `DataManagement*Job` (admin card-demo job) | `source → target` |
| `REPROC.prc` `[app/proc/REPROC.prc]` | `config/JobSchedulingConfig.java` (report proc; TDQ 'JOBS' / JES async → `JobLauncher`) | `source → target` |
| `TRANREPT.prc` `[app/proc/TRANREPT.prc]` | `batch-service` `config/JobSchedulingConfig.java#launchTransactionDetailReport` exposed by `controller/BatchController.java` (`POST /batch/jobs/transactionDetailReportJob`); reached from `CORPT00C` via `reporting-service` `client/BatchJobClient.java` | `source → target` |

## 8. Configuration, Catalog, Control, Seed Data + Security

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `app/catlg/LISTCAT.txt` | `carddemo-common` `db/migration/V1__create_schema.sql` (**11** business tables in FK order — `customers`, `accounts`, `security_users`, `tran_type`, `tran_category`, `disclosure_group`, `cards`, `card_xref`, `transactions`, `daily_transactions`, `tran_cat_bal` — plus 4 FK constraints, the secondary indexes derived from the 3 alternate indexes, 15 id-width `CHECK` constraints, and `transaction_id_seq`) | `source → target` |
| `app/csd/CARDDEMO.CSD` | `auth-service` `config/SecurityConfig.java` (`SecurityFilterChain`, route / role rules) + gateway routes | `source → target` |
| `app/catlg/LISTCAT.txt` | `db/migration/V1__create_schema.sql` (10 tables + FK constraints + secondary indexes from 3 alternate indexes) | `source → target` |
| `app/csd/CARDDEMO.CSD` | `auth-service` `config/SecurityConfig.java` (`SecurityFilterChain`, sign-on route rules, `401` entry point) | `source → target` |
| `app/csd/CARDDEMO.CSD` transaction list + `RESSEC(NO)`/`CMDSEC(NO)` (application-layer gating) | `api-gateway` `config/SecurityConfig.java` (path-prefix → role authority rules for `CC00`/`CM00`/`CA00`/`CU00`–`CU03`/`CAVW`/`CAUP`/`CCLI`/`CCDL`/`CCUP`/`CT00`–`CT02`/`CB00`/`CR00`, browser CSRF, logout, source-address rate limiting) + `config/GatewayRoutesConfig` routes | `source → target` |
| `app/csd/CARDDEMO.CSD` administrator-only transactions `CU00`–`CU03` `[app/cbl/COUSR00C.cbl; app/cbl/COUSR01C.cbl; app/cbl/COUSR02C.cbl; app/cbl/COUSR03C.cbl]` | `user-service` `config/SecurityConfig.java` (`ROLE_ADMIN` on every user-CRUD route, anonymous health status only, CSRF delegated to the gateway) | `source → target` |
| `app/csd/CARDDEMO.CSD` transaction security applied to the business transactions `CAVW`/`CAUP`/`CCLI`/`CCDL`/`CCUP`/`CT00`–`CT02`/`CB00`/`CR00` | `account-service`, `card-service`, `transaction-service`, `billpay-service`, `reporting-service`, `batch-service` `config/SecurityConfig.java` (shared session-derived chain; authentication required on every direct service port) | `source → target` |
| `app/ctl/REPROCT.ctl` | Spring Batch sort / step configuration (report processing) | `source → target` |
| `app/data/ASCII/trantype.txt`, `trancatg.txt`, `discgrp.txt` | `carddemo-common` `db/migration/V2__seed_reference_data.sql` (7 `tran_type`, 18 `tran_category`, 51 `disclosure_group` rows; `DIS-INT-RATE` decoded from the trailing-overpunch sign) | `source → target` |
| `app/data/ASCII/custdata.txt`, `acctdata.txt`, `carddata.txt`, `cardxref.txt`, `tcatbal.txt`, `dailytran.txt` | `carddemo-common` `db/migration/V3__seed_test_data.sql` (10 `security_users`, 50 customers / accounts / cards / xrefs, **50** `tran_cat_bal` rows — the fixture's exact distinct-key count, reconciled against the "100" in AAP §0.4.5 in decision-log §2 — 300 `daily_transactions`, and the `transactions` history derived as the posted image of the feed) | `source → target` |
| `COSGN00C` `[app/cbl/COSGN00C.cbl]` + `CSUSR01Y` `[app/cpy/CSUSR01Y.cpy]` | `auth-service` `security/UserDetailsServiceImpl.java` + `PasswordEncoderConfig` (RACF / USRSEC → BCrypt; `SEC-USR-TYPE` 'A' / 'U' → `ROLE_ADMIN` / `ROLE_USER`) | `source → target` |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.*` (12 binaries) | EXCLUDED (out of scope, AAP 0.2.2 — ASCII fixtures drive seeding) | `source → target` |


## 9. Derived Targets (No 1:1 Legacy Field)

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| CICS read-snapshot-compare-rewrite lock pattern `[app/cbl/COACTUPC.cbl:L517-L523]` | `Account.version` (`@Version` optimistic-lock column) | `target → source (derived)` |
| Field-by-field snapshot comparison before `REWRITE` `[app/cbl/COACTUPC.cbl:L4131-L4189]` | `version` token on `AccountUpdateRequestDto` / `AccountViewResponseDto` / `AccountUpdateResponseDto` + `AccountService.updateAccount` compare step → `OptimisticLockConflictException` ("Record changed by some one else. Please review", HTTP 409) | `target → source (derived)` |
| Multi-file CICS `REWRITE` units `[app/cbl/COACTUPC.cbl:L4066-L4090]` | `@Transactional` service boundaries (account + customer commit / roll back together) | `target → source (derived)` |
| Browse-last-then-increment id generation `[app/cbl/COTRN02C.cbl:L444-L451]` | `transaction_id_seq` PostgreSQL sequence (created and high-water-marked in `V1__create_schema.sql`; 16-digit zero-padded wire form preserved; `setval` guarded by a digits-only regexp because `TRAN-ID` is `PIC X(16)`) | `target → source (derived)` |
| Application-enforced read order `[app/cbl/CBTRN02C.cbl:L371-L397, app/cpy/CVACT03Y.cpy]` | Database FK constraints (card ↔ customer ↔ account) + service-layer validation; `fk_tran_cat_bal_acct` extends the same rule to category balances | `target → source (derived)` |
| CXACAIX alternate-index customer browse `[app/cpy/CVACT03Y.cpy, app/cbl/COCRDLIC.cbl]` | Secondary index `idx_card_xref_cust_id` on `card_xref (xref_cust_id)` | `target → source (derived)` |
| Fixed-width `PIC 9(n)` identifier fields `[app/cpy/CVACT01Y.cpy, app/cpy/CVCUS01Y.cpy, app/cpy/CVACT03Y.cpy, app/cpy/CSUSR01Y.cpy]` | 15 id-width / range `CHECK` constraints in `V1__create_schema.sql` (`chk_accounts_acct_id`, `chk_customers_cust_id`, `chk_card_xref_cust_id`, `chk_customers_fico`, `chk_sec_usr_type`, …) re-establishing the legacy field widths at the storage layer | `target → source (derived)` |
| Sensitive legacy fields `CUST-SSN`, `CUST-GOVT-ISSUED-ID`, `CUST-EFT-ACCOUNT-ID`, `CARD-CVV-CD` `[app/cpy/CVCUS01Y.cpy, app/cpy/CVACT02Y.cpy]` | `carddemo-common` `migration/SeededPiiEncryptionMigration.java` (Flyway Java migration, version `4`) + `config/SchemaMigrationConfig.java` — encrypts the seeded PII in place through `CryptoConverter` (AES-256-GCM, random IV, environment-supplied key), idempotently | `target → source (derived)` |
| File-status / RESP reject handling `[app/cbl/CBTRN02C.cbl:L176-L182, L380-L421]` | Reject-code domain exceptions + reject `ItemWriter` (430-byte reject record) | `target → source (derived)` |

## 10. Rule-Mandated / Standalone-Operation Infrastructure (No Legacy Analogue)

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Respecting-.gitignore rule (AAP 0.7.2) | `.gitignore` (Java + Node ignore patterns) | `target → rule` |
| Standalone build (AAP 0.5) | root `pom.xml` (aggregator / parent BOM) + per-module `pom.xml` (×10: `carddemo-common`, `auth-service`, `user-service`, `account-service`, `card-service`, `transaction-service`, `billpay-service`, `reporting-service`, `batch-service`, `api-gateway`) | `target → rule` |
| Standalone build (React 19 / Node 24) | `frontend/package.json` | `target → rule` |
| Containerization + orchestration (standalone operation) | `docker-compose.yml`; `k8s/configmap.yaml`, `k8s/secret.yaml` (per-service DB-role Secrets + shared Redis + postgres bootstrap), `k8s/deployment-{api-gateway,auth-service,user-service,account-service,card-service,transaction-service,billpay-service,reporting-service,batch-service,postgres,redis,frontend}.yaml`, `k8s/service-frontend.yaml`, `k8s/service-redis.yaml`; per-service `Dockerfile`s delivered with the services | `target → rule` |
| Container/pod hardening rule (MJ-10) | `k8s/networkpolicy.yaml` (default-deny + least-privilege ingress), `k8s/poddisruptionbudget.yaml` (per-workload PDBs), pod/container `securityContext` across all Deployments | `target → rule` |
| Respecting-.gitignore + externalized-secrets (CR-11) | `.env.example` (required-env template consumed by `docker-compose.yml`; real `.env` git-ignored) | `target → rule` |
| Observability rule (AAP 0.7.5) | `application.yml` (Actuator health / readiness / liveness + `/actuator/prometheus`; `management.endpoint.health.group.readiness.include: readinessState,db` so readiness tracks the database, with liveness deliberately excluding it, plus bounded Hikari `connection-timeout` / `validation-timeout` so the probe answers inside its timeout) | `target → rule` |
| Observability rule (AAP 0.7.5) | `logback-spring.xml` (structured JSON logging + correlation ids via MDC; every appender definition scoped inside the `<springProfile>` block that references it) | `target → rule` |
| Observability rule (distributed tracing) | `org.springframework.boot:spring-boot-micrometer-tracing` + `spring-boot-micrometer-tracing-opentelemetry` (Boot 4 auto-configuration modules) over `io.micrometer:micrometer-tracing-bridge-otel` — activates the OpenTelemetry `Tracer`, W3C `traceparent` propagation and `traceId` / `spanId` MDC enrichment; no OTLP exporter by design (decision-log §6) | `target → rule` |
| Observability rule (correlation across service boundaries) | `carddemo-common` `config/CorrelationIdFilter` registered by `config/WebObservabilityConfig`, imported by all nine services (single shared implementation; adopts an inbound `X-Correlation-Id` or W3C `traceparent` trace-id, sanitizes CRLF, echoes the header on every response) | `target → rule` |
| Observability rule (AAP 0.7.5) | `application.yml` (Actuator health / readiness / liveness + `/actuator/prometheus`) | `target → rule` |
| Observability rule (AAP 0.7.5) | `logback-spring.xml` (structured JSON logging + correlation ids via MDC) | `target → rule` |
| Observability rule (distributed tracing) | `carddemo-common/pom.xml` — the Boot 4 tracing auto-configuration module + the OTLP span exporter, inherited by all nine services; `management.opentelemetry.tracing.export.otlp.endpoint` and `management.tracing.sampling.probability` in every `application.yml`; the `jaeger` collector in `docker-compose.yml` and `k8s/deployment-jaeger.yaml` / `k8s/service-jaeger.yaml` | `target → rule` |
| Observability rule (distributed tracing) — VERIFIED ACTIVE | One request through the gateway yields one joined trace: 13 spans across `api-gateway`+`auth-service`, 17 across `api-gateway`+`account-service`; `traceId`/`spanId` present in every JSON log record. (An earlier revision of this matrix credited `micrometer-tracing` + `micrometer-tracing-bridge-otel` alone, which supply the API and the bridge but NO auto-configuration, NO exporter and no collector, so no span was ever produced.) | `target → rule` |
| Observability rule (distributed tracing) | Micrometer Tracing + OpenTelemetry bridge configuration: `io.micrometer:micrometer-tracing-bridge-otel` plus the Spring Boot 4 auto-configuration modules `org.springframework.boot:spring-boot-micrometer-tracing` and `spring-boot-micrometer-tracing-opentelemetry` (both REQUIRED for a real `Tracer` bean — see decision-log §6), and `management.tracing.sampling.probability` per service | `target → rule` |
| Observability rule (AAP 0.7.5) | `observability/prometheus.yml` (scrape config) | `target → rule` |
| Observability rule (AAP 0.7.5) | `observability/grafana-dashboard.json` (dashboard template) | `target → rule` |
| Explainability rule (AAP 0.7.2 / 0.7.3) | `docs/decision-log.md` | `target → rule` |
| Explainability rule (AAP 0.7.2 / 0.7.4) | `docs/traceability-matrix.md` (this file) | `target → rule` |
| Standalone-operation documentation | `README-target.md` | `target → rule` |
| Observability rule / cross-cutting infra | `carddemo-common` `config/` (observability, tracing, exception handling) + `exception/` handlers | `target → rule` |

## 11. Delivered in This Tranche (Foundation) — Present-on-Disk Targets

The following target artifacts are delivered and present on disk in this foundation
tranche. They are listed here explicitly so the reverse direction is verifiable today
(not merely against the end-state design).

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `carddemo-common` `dto/ErrorResponse.java` (with `errorCode`, carrying reject codes 100–103) | `CBTRN02C` reject-code / error-message handling + REST error-contract | `source → target` |
| `carddemo-common` `dto/ReportRequestDto.java`, `dto/ReportResponseDto.java` | `CORPT00C` report request + `CVTRA07Y` report layout | `source → target` |
| `reporting-service` `mapper/ReportMapper.java` | `CVTRA07Y` `TRANSACTION-DETAIL-REPORT` → report-DTO assembly | `source → target` |
| `batch-service` `TransactionReportItem` (report row model) | `CVTRA07Y` transaction-detail report row | `source → target` |
| `carddemo-common` `util/DateUtil.java` | `CSUTLDTC` CEEDAYS / Lillian (see §2) | `source → target` |
| `carddemo-common` `exception/CardDemoException.java` + handlers | RESP / file-status error handling → exceptions | `target → source (derived)` |
| `carddemo-common` `domain/TranCatBalId.java`, `DiscGroupId.java`, `TranCatgId.java` | Compound VSAM keys of `CVTRA01Y` / `CVTRA02Y` / `CVTRA04Y` (`@IdClass`) | `source → target` |
| `carddemo-common` `domain/FinancialPrecisionTest.java` | Interest `COMPUTE` truncation pattern `[app/cbl/CBACT04C.cbl:L464-465]` | `target → source (derived)` |
| `carddemo-common` `config/CorrelationIdContext.java`, `CorrelationIdFilter`, `WebObservabilityConfig` (an `@AutoConfiguration` listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`), `CorrelationIdThreadLocalAccessor`, `RequestLoggingFilter`, `ObservabilityConfig` | Observability rule (structured logging + correlation ids) | `target → rule` |
| Correlation-id registration — VERIFIED ACTIVE on all nine services | `X-Correlation-Id` echoed and logged by every service, inbound `traceparent` honoured as a fallback, one access-log line per request, and the id propagated into async and batch threads. (An earlier revision listed these classes as covering the rule while nothing imported `WebObservabilityConfig`, so the shared filter never ran; five services carried diverged local copies, since deleted.) | `target → rule` |
| `carddemo-common` `config/SessionRedisConfig.java` (strict allowlisted JSON serializer) | COMMAREA session externalization + CWE-502 hardening (decision-log §6) | `target → source (derived)` |
| `carddemo-common` security password-encoder factory (BCrypt `DelegatingPasswordEncoder`) | `COSGN00C` credential check → encoder (see §8) | `source → target` |
| `transaction-service` `PostingJobCompletionListener` | `CBTRN02C` batch result codes 0/4/8/12 + rejected tally | `source → target` |
| `carddemo-common` `db/migration/V1__create_schema.sql` (11 business tables, 4 FKs, secondary indexes, 15 id-width `CHECK`s, `transaction_id_seq`) | `app/catlg/LISTCAT.txt` + the `CV*` / `CSUSR01Y` record copybooks (see §8) | `source → target` |
| `carddemo-common` `db/migration/V2__seed_reference_data.sql` (7 / 18 / 51 rows) | `app/data/ASCII/trantype.txt`, `trancatg.txt`, `discgrp.txt` (see §8) | `source → target` |
| `carddemo-common` `db/migration/V3__seed_test_data.sql` (10 users, 50 customers / accounts / cards / xrefs, 50 `tran_cat_bal`, 300 `daily_transactions`, derived `transactions` history) | `app/data/ASCII/custdata.txt`, `acctdata.txt`, `carddata.txt`, `cardxref.txt`, `tcatbal.txt`, `dailytran.txt` (see §8) | `source → target` |
| `carddemo-common` `migration/SeededPiiEncryptionMigration.java` (Flyway Java migration, version `4`) + `config/SchemaMigrationConfig.java` (`JavaMigration` `@Bean` registration) | `CUST-SSN` / `CUST-GOVT-ISSUED-ID` / `CUST-EFT-ACCOUNT-ID` / `CARD-CVV-CD` encryption-at-rest posture (see §9; decision-log §2) | `target → source (derived)` |
| `carddemo-common` `db/migration/V5__batch_metadata.sql` (single migration owner `batch-service`; one `flyway_schema_history`; `spring.batch.jdbc.initialize-schema: never`) | Spring Batch metadata schema (framework requirement) — renumbered into the consolidated version line, decision-log §2 | `target → rule` |
| `carddemo-common` `domain/DailyTransaction.java` (`@Entity` → `daily_transactions`) + `transaction-service` `repository/DailyTransactionRepository.java` (`JpaRepository`, ascending `DALYTRAN-ID` `@Query`) consumed by `TransactionPostingJob`'s reader | `CVTRA06Y` DALYTRAN feed record (see §3; reversal recorded in decision-log §6) | `source → target` |
| `carddemo-common` `dto/AccountUpdateRequestDto.java` / `AccountViewResponseDto.java` / `AccountUpdateResponseDto.java` `version` token + `account-service` `AccountService.updateAccount` compare step | `COACTUPC` snapshot-compare-before-`REWRITE` pattern (see §9) | `target → source (derived)` |
| Compose `depends_on: batch-service: service_healthy` + Kubernetes `await-schema` initContainer | Cross-service schema-provisioning order (no legacy analogue — JCL applied DDL out of band), decision-log §2 | `target → rule` |
| `carddemo-common` `db/migration/V5__batch_metadata.sql` | Spring Batch metadata schema (framework requirement). Moved out of `batch-service` into the shared set because THREE services run Spring Batch jobs against the one database | `target → rule` |
| `carddemo-common` `batch/JdbcBatchConfiguration.java` | Spring Batch 6 default `JobRepository` is in-memory (`ResourcelessJobRepository`); a JDBC-backed repository is required for the `BATCH_*` tables above to be used at all | `target → rule` |
| Per-service `application.yml` + `logback-spring.xml` (9 services) | Observability rule (Actuator health/readiness/liveness/prometheus + JSON logs) | `target → rule` |
| `observability/prometheus.yml`, `observability/grafana-dashboard.json` | Observability rule (scrape config + dashboard) | `target → rule` |
| Frontend foundation (`package.json`, `vite.config.ts`, `tsconfig*.json`, `index.css`, `nginx.conf`, `components/Header.tsx`, `types/{common,session}.ts`) | BMS screen shell + REST field contracts + standalone build/serve | `source → target` / `target → rule` |
| `frontend/package-lock.json` (`lockfileVersion 3`, 491 locked entries, consistent with `package.json`) | AAP §0.5.1 "lock-pinned at scaffold" + `npm ci` reproducible-build requirement (decision-log §6) | `target → rule` |
| Frontend barrels `frontend/src/hooks/index.ts` (re-exports `useSession` / `UseSessionResult`, `usePagination` / `UsePaginationResult` with `CARD_LIST_PAGE_SIZE` 7, `TRANSACTION_LIST_PAGE_SIZE` 10, `USER_LIST_PAGE_SIZE` 10, and `useApi` / `UseApiResult`), `frontend/src/types/index.ts`, `frontend/src/api/index.ts` | AAP §0.5.3 frontend ES-module import convention — one import specifier per folder; organizational only, no runtime logic, no default export, no legacy analogue | `target → rule` |
| `api-gateway` `config/ObservabilityEndpointsIT` + `SessionRedisRoundTripIT`; `auth-service`, `user-service`, `account-service` `config/ObservabilityEndpointsIT`; `carddemo-common` `config/ObservabilityConfigTest`, `WebObservabilityConfigTest` | Observability rule (AAP §0.7.5) "verified in the local environment" + COMMAREA session externalization `[app/cpy/COCOM01Y.cpy:L18-45]` | `target → rule` |

> The business-schema migrations (`V1__create_schema.sql`,
> `V2__seed_reference_data.sql`, `V3__seed_test_data.sql`, Java migration `4`,
> `V5__batch_metadata.sql`) are **delivered** — they live in
> `carddemo-common/src/main/resources/db/migration` and are applied by the single
> migration owner (`batch-service`). The remaining per-screen `*RequestDto` /
> `*ResponseDto` classes, `frontend/src/api`, `frontend/src/types/*`, the 17 page
> components, and the outstanding services / controllers / repositories are mapped
## 12. Security, Session & Observability Remediation — Present-on-Disk Targets

Delivered while closing the runtime security-gate findings. Every artifact below is
present on disk, so the reverse direction is verifiable today. Rationale for each choice
is in `docs/decision-log.md` §3 (Security & Session) and §6 (Infrastructure &
Observability); this table records only the source-or-mandate linkage.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `carddemo-common` `security/SecurityHardening.java` (uniform posture: stateless sessions, no saved-request cache, hardened response headers, `401` entry point, `ERROR`-dispatch permit, session-derived principal) | CICS application-layer gating with `RESSEC(NO)`/`CMDSEC(NO)` `[app/csd/CARDDEMO.CSD]` + Observability/security hardening | `target → source (derived)` |
| `carddemo-common` `security/SessionContextAuthenticationFilter.java` | COMMAREA identity + `SEC-USR-TYPE` role carried across pseudo-conversational turns `[app/cpy/COCOM01Y.cpy:L18-45,L26-30]` | `source → target` |
| `carddemo-common` `dto/SessionContext.SESSION_ATTRIBUTE_NAME` (canonical session key) | Single COMMAREA carried between programs `[app/cpy/COCOM01Y.cpy]` | `source → target` |
| `carddemo-common` `security/SessionPrincipalIndex.java`, `security/SessionRegistryConfig.java` | `USRSEC` re-read on every transaction, so user maintenance took effect immediately `[app/cpy/CSUSR01Y.cpy; app/cbl/COUSR02C.cbl; app/cbl/COUSR03C.cbl]` | `target → source (derived)` |
| `auth-service` `security/LoginAttemptService.java` | RACF revoke-after-N-failures protecting the `USRSEC` sign-on `[app/cbl/COSGN00C.cbl]` | `target → source (derived)` |
| `carddemo-common` `security/RateLimitFilter.java` | RACF/CICS throttling of the sign-on transaction `CC00` `[app/csd/CARDDEMO.CSD; app/cbl/COSGN00C.cbl]` | `target → source (derived)` |
| `api-gateway` `config/CsrfCookieMaterializingFilter.java`, `controller/CsrfController.java` | Browser-facing surface replacing the 3270 terminal session (no legacy analogue) | `target → rule` |
| `carddemo-common` `security/ManagementSecurityConfig.java` (`monitoring` principal, `ROLE_MONITORING`, `@Order(1)` telemetry chain) | Observability rule (AAP 0.7.5) — authenticated scrape | `target → rule` |
| `k8s/secret.yaml` `carddemo-monitoring-secret` + `MONITORING_PASSWORD` in `docker-compose.yml` and all 9 Deployments | Observability rule (AAP 0.7.5) — the scrape credential must reach the services, not only Prometheus | `target → rule` |
| `carddemo-common` `security/SecurityAuditLogger.java`, `security/SecurityAuditConfig.java`, `security/AuditingAuthenticationEntryPoint.java`, `security/AuditingAccessDeniedHandler.java` | Observability rule (AAP 0.7.5) — structured, correlation-aware security-event trail | `target → rule` |
| `carddemo-common` `security/SensitiveDataMasker.java` | PAN never retained in a log; AAP 0.6.7 sensitive-field handling `[app/cbl/COCRDSLC.cbl; app/cbl/COTRN01C.cbl]` | `target → source (derived)` |
| `carddemo-common` `config/RequestSizeLimitFilter.java`, `config/LimitedRequestWrapper.java`, `config/WebHardeningConfig.java` | Fixed-width 3270 screen fields bound every legacy input; the REST equivalent is an explicit body cap (CWE-770) plus uniform hardened headers `[app/cpy-bms/]` | `target → source (derived)` |
| `carddemo-common` `exception/PiiEncryptionException.java`, `crypto/SeededPiiEncryptionMigrator.java`, `crypto/PiiEncryptionConfig.java` | At-rest protection of `CUST-SSN`, `CUST-GOVT-ISSUED-ID`, `CUST-EFT-ACCOUNT-ID`, `CARD-CVV-CD` (AAP 0.6.7) `[app/cpy/CVCUS01Y.cpy; app/cpy/CVACT02Y.cpy]` | `target → source (derived)` |
| `auth-service` `db/migration/V2__seed_security_users.sql` `{bcrypt}` identifier prefix | `USRSEC` seeded credentials — data set defined by the load job, layout by the copybook `[app/jcl/DUSRSECJ.jcl; app/cpy/CSUSR01Y.cpy]` | `source → target` |
| `account-service`, `card-service`, `transaction-service`, `billpay-service`, `reporting-service`, `batch-service` `config/SecurityConfig.java` | CICS transaction security for `CAVW`/`CAUP`/`CCLI`/`CCDL`/`CCUP`/`CT00`–`CT02`/`CB00`/`CR00` `[app/csd/CARDDEMO.CSD]` | `source → target` |
| `carddemo-common` `config/GlobalExceptionHandler` unreadable-body (`413`/`400`) and catch-all handlers | CICS `RESP`/file-status outcome mapping + Observability rule (a trace id on every error payload) | `target → source (derived)` |
| `carddemo-common` tests `security/{SecurityAuditConfigTest,AuditingAccessDeniedHandlerTest,RateLimitFilterTest,SessionPrincipalIndexTest,SessionContextAuthenticationFilterTest,SensitiveDataMaskerTest}`, `crypto/{CryptoConverterTest,SeededPiiEncryptionMigratorTest}`, `config/{RequestSizeLimitFilterTest,GlobalExceptionHandlerLoggingTest,GlobalExceptionHandlerUnreadableBodyTest,GlobalExceptionHandlerUnhandledTest}` | AAP 0.7.1 test obligation for the controls above | `target → rule` |
| `auth-service` `security/LoginAttemptServiceTest`; `account-service`, `billpay-service`, `reporting-service`, `transaction-service` `SecurityContractIT`; `card-service` `CardViewUpdateIT.SecurityContract` (nested) | AAP 0.7.1 test obligation — each freezes "an anonymous request to this service port never reaches business logic" | `target → rule` |
| `.gitignore` `blitzy/` + cookie-jar patterns | Respecting-.gitignore rule (AAP 0.7.2) — QA evidence holds live session ids and must never be staged | `target → rule` |

> The per-screen `*RequestDto` / `*ResponseDto` classes, `frontend/src/api`,
> `frontend/src/types/*`, the 17 page components, the business-schema migrations
> (`V1__create_schema`, `V2__seed_reference_data`, `V3__seed_test_data`), the
> services / controllers / repositories, and the per-service `Dockerfile`s are mapped
> above in the end-state design and are **delivered in later tranches**.

## 13. QA-Remediation Targets (runtime checkpoint) — Present on Disk

Target artifacts created or substantially reworked while remediating the runtime QA
checkpoint. They are listed so the reverse direction stays 100% complete: every one either
transforms a legacy construct, derives from a legacy behavioral pattern, or is mandated by
a user-specified rule. Rationale for each is in `docs/decision-log.md` §7.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `carddemo-common` `config/GlobalExceptionHandler.java` (extends `ResponseEntityExceptionHandler`; renders the eight-field `ErrorResponse` envelope for domain AND framework failures, and returns exactly one field error in COBOL edit order) | COBOL single-message screen contract — `IF WS-ERROR-MSG-OFF` guarded `EVALUATE TRUE` emitting one message `[app/cbl/COSGN00C.cbl:L117-L131]` — plus the REST error-contract requirement | `target → source (derived)` |
| `carddemo-common` `config/ErrorResponseFactory.java` | Shared assembly of the same envelope (`traceId` from the tracing MDC, correlation id fallback) | `target → rule` |
| `carddemo-common` `config/CardDemoErrorController.java` | Servlet `ERROR` dispatch → same JSON envelope instead of the container's HTML page | `target → rule` |
| `carddemo-common` `config/ContainerErrorReportConfig.java` (`SilentErrorReportValve`) | Tomcat host-pipeline rejections (e.g. encoded path separators) → same JSON envelope | `target → rule` |
| `carddemo-common` `config/SecurityExceptionHandler.java` (`@ConditionalOnClass`) | `COSGN00C` / `COMEN01C` authorization refusal → 401/403 in the same envelope | `target → source (derived)` |
| `carddemo-common` `config/PersistenceExceptionHandler.java` | `WRITE` DUPKEY → `'Tran ID already exist...'`; `REWRITE` conflict → `'Record changed by some one else. Please review'` `[app/cbl/COTRN02C.cbl; app/cbl/COACTUPC.cbl:L4131-L4189]` | `source → target` |
| `carddemo-common` `crypto/CryptoConverter.java` (AES-GCM JPA `@Converter` on SSN, government id, EFT account id, CVV; fails closed) | Sensitive-field protection with no legacy field-level analogue — `CVCUS01Y` `CUST-SSN` / `CUST-GOVT-ISSUED-ID` / `CUST-EFT-ACCOUNT-ID` and `CVACT02Y` `CARD-CVV-CD` were stored in the clear on VSAM `[app/cpy/CVCUS01Y.cpy; app/cpy/CVACT02Y.cpy]` | `target → rule` |
| `carddemo-common` `crypto/PiiMasker.java` | 3270 masked display of sensitive fields (`***-**-<last 4>` SSN; identifiers fully masked) | `target → source (derived)` |
| `carddemo-common` `migration/SeededPiiEncryptionMigration.java` + `crypto/SeededPiiEncryptionMigrator.java` | At-rest normalization of ASCII-fixture seed data to the `CryptoConverter` format `[app/data/ASCII/custdata.txt; app/data/ASCII/carddata.txt]` | `target → rule` |
| `carddemo-common` `exception/PiiEncryptionException.java` | Fail-closed outcome of the converter (never surfaces plaintext or an opaque 500) | `target → rule` |
| `carddemo-common` `security/SessionContextAuthenticationFilter.java` | COMMAREA `CDEMO-USER-TYPE` 88-levels → Spring Security authorities on every request `[app/cpy/COCOM01Y.cpy:L26-L30]` | `source → target` |
| `carddemo-common` `dto/SessionAttributes.java` | Single COMMAREA-equivalent session attribute name shared by every service `[app/cpy/COCOM01Y.cpy:L18-L45]` | `source → target` |
| `carddemo-common` `config/RedisCommandMetricsConfig.java` (Lettuce `MicrometerCommandLatencyRecorder`) | Observability rule (AAP 0.7.5) — Redis command latency series consumed by `observability/grafana-dashboard.json` | `target → rule` |
| `carddemo-common` `config/WebObservabilityConfig.java` activated by `auth-service`, `user-service`, `reporting-service` | Observability rule (correlation id in the MDC and in `ErrorResponse.traceId`) | `target → rule` |
| `carddemo-common` `dto/UserListResponseDto.java`, `dto/UserWriteResponseDto.java` | `COUSR00C` paging literals + `COUSR01C`/`COUSR02C`/`COUSR03C` confirmation messages `[app/cbl/COUSR00C.cbl:L211-L273]` | `source → target` |
| `account-service` `service/AccountUpdateValidator.java` | `COACTUPC` `1200-EDIT-MAP-INPUTS` 25-field edit sequence + `1280` state/zip cross-field edit + `CSLKPCDY` lookup sets `[app/cbl/COACTUPC.cbl; app/cpy/CSLKPCDY.cpy]` | `source → target` |
| `carddemo-common` `domain/Transaction.java` implementing `Persistable<String>` | `COTRN02C` / `COBIL00C` `WRITE` semantics — an existing transaction is never overwritten `[app/cbl/COTRN02C.cbl:L444-L451]` | `target → source (derived)` |
| `reporting-service` `config/StatementOutputResolver.java` | `CREASTMT` / `CBSTM03A` statement destination, confined to a configured root `[app/jcl/CREASTMT.JCL; app/cbl/CBSTM03A.CBL]` | `source → target` |
| `docker-compose.yml` `jaeger` service + `management.opentelemetry.tracing.export.otlp.endpoint` (docker profile) | Observability rule (AAP 0.7.5) — distributed tracing verifiable in the local environment | `target → rule` |
## 14. QA Remediation — Runtime-Verified Batch, Security & Integration Targets

Targets added or corrected while resolving the runtime findings raised by end-to-end QA of the integrated backend. Every row was verified against the running application, not only against the source.

**Scope of this section.** It does not restate the forward inventory. Its *Source Construct* column names a SPECIFIC BEHAVIOR WITHIN a construct already enumerated exactly once in Sections 1-8 (for example the `CBTRN02C` feed read and its reject file, rather than `CBTRN02C` itself), or a cross-cutting concern with no legacy analogue. The forward "exactly once" enumeration in Sections 1-8 is therefore unaffected, and this section supplies the reverse (`target -> source (derived)` / `target -> rule`) coverage for the components added during remediation.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `CBTRN02C` daily-transaction feed read `[app/cbl/CBTRN02C.cbl:L371-L397]` | `transaction-service` `batch/TransactionPostingJob.java` → `JdbcCursorItemReader` over `daily_transactions` (`DALYTRAN_FEED_SQL`, ordered by `dalytran_id`) + `batch/DailyTransactionRowMapper.java` | `source → target` |
| `CBTRN02C` reject file `DALYREJS` (430B = 350B image + 80B trailer) `[app/cbl/CBTRN02C.cbl:L176-L182]` | `transaction-service` `batch/RejectFileItemWriter.java`, path resolved through `carddemo-common` `batch/BatchOutputPathResolver.java` | `source → target` |
| `CBTRN02C` result-code tally 0 / 4 / 12 `[app/cbl/CBTRN02C.cbl:L228]` | `transaction-service` `batch/PostingJobCompletionListener.java` (`COMPLETED_WITH_REJECTS` / `FAILED_EMPTY_FEED`) | `source → target` |
| `COTRN02C` transaction-id assignment `[app/cbl/COTRN02C.cbl:L444-L451]` | `transaction-service` `service/TransactionService.java#nextUnusedTransactionId` over Flyway `V4__create_transaction_id_sequence.sql` | `source → target` |
| `CBTRN03C` / `TRANREPT` transaction-detail report `[app/cbl/CBTRN03C.cbl]` | `batch-service` `transactionDetailReportJob` + `batch/TransactionDetailReportWriter.java`, launched by `controller/BatchController.java` | `source → target` |
| `CORPT00C` `SUBMIT-JOB-TO-INTRDR` write to TDQ `'JOBS'` `[app/cbl/CORPT00C.cbl]` | `reporting-service` `client/BatchJobClient.java#submitTransactionDetailReport` (frozen `Unable to Write TDQ (JOBS)...` on refusal) | `source → target` |
| `CREASTMT` / `CBSTM03A` statement generation `[app/jcl/CREASTMT.JCL; app/cbl/CBSTM03A.CBL]` | `reporting-service` `batch/StatementGenerationJob.java`, launched by `controller/ReportController.java#generateStatements` (`POST /reports/statements`) | `source → target` |
| Legacy operator submission of the JCL job streams through TDQ `'JOBS'` / the JES internal reader | `batch-service` `controller/BatchController.java` (`GET /batch/jobs`, `POST /batch/jobs/{jobName}`, `GET /batch/jobs/executions/{id}`) | `source → target` |
| CICS transaction authorization of an operator-submitted job stream `[app/csd/CARDDEMO.CSD]` | `batch-service` `config/SecurityConfig.java` (session-derived principal, `ROLE_USER`/`ROLE_ADMIN` on `/batch/**`) | `source → target` |
| JCL `DD` data-set allocation for generated report / dump / statement files `[app/jcl/]` | `carddemo-common` `batch/BatchOutputPathResolver.java` + `batch/BatchPathConfig.java` (allowlisted, writable output and input roots) | `source → target` |
| `BATCH_*` job metadata tables and restartability | `carddemo-common` `batch/JdbcBatchConfiguration.java` + Flyway `V5__batch_metadata.sql` (no legacy analogue — JES held run history) | `target → source (derived from JES job history)` |
| Cleanup of a partially written batch output file after a failure | `carddemo-common` `batch/FailedOutputCleanupListener.java` (no legacy analogue — a failed JCL step's data set was deleted by its `DISP` disposition) | `target → source (derived from JCL DISP=(NEW,CATLG,DELETE))` |
| COMMAREA-derived principal on every request `[app/cpy/COCOM01Y.cpy:L18-L45]` | `carddemo-common` `security/SessionContextAuthenticationFilter.java` | `source → target` |
| Correlation of a submitted job's output back to the submitting user | `carddemo-common` `config/CorrelationIdFilter` (via `WebObservabilityConfig`) + `config/CorrelationIdTaskDecorator.java` (Observability rule) | `target → rule` |
| Batch launch acknowledgement and outcome readback | `carddemo-common` `dto/BatchJobExecutionDto.java` (no legacy analogue — the TDQ write was fire-and-forget) | `target → source (derived from TDQ submission)` |
| Unreachable routed upstream service | `api-gateway` `config/UpstreamFailureHandler.java` → `503` + `Retry-After` (no legacy analogue — CICS returned an abend code) | `target → source (derived from CICS routing failure)` |
| Generated batch artefacts must never be committed (Respecting .gitignore rule) | `.gitignore` — generated batch artefact names, batch output root, and local run evidence, with `app/data/**` re-included by negation | `target → rule` |
## 15. Targets Added While Remediating Runtime Findings

Every target file created or relocated while making the delivered stack run, be observable
and be deployable. Each row names the source construct or rule it serves, so the reverse
direction stays complete for these files too. The rationale for each choice is in
`docs/decision-log.md` §9.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `carddemo-common` `db/migration/V1__create_schema.sql` | `app/catlg/LISTCAT.txt` key positions/lengths + `app/cpy/CV*.cpy`, `CSUSR01Y.cpy` — the 11 business tables, FK constraints and secondary indexes (AAP 0.4.5). Consolidated here from 17 superseded per-service migrations because every service validates the FULL entity set | `source → target` |
| `carddemo-common` `db/migration/V2__seed_reference_data.sql` | `app/data/ASCII/trantype.txt`, `trancatg.txt`, `discgrp.txt` — 7 transaction types, 18 categories, 51 disclosure groups (AAP 0.4.5) | `source → target` |
| `carddemo-common` `db/migration/V3__seed_test_data.sql` | `app/data/ASCII/custdata.txt`, `acctdata.txt`, `carddata.txt`, `cardxref.txt`, `tcatbal.txt`, `dailytran.txt` (AAP 0.4.5) | `source → target` |
| `carddemo-common` `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Observability + configuration-validation rules — the registration mechanism that makes the shared cross-cutting components active in all nine services | `target → rule` |
| `carddemo-common` `config/RequestLoggingFilter.java` | Observability rule (AAP 0.7.5) — request/access logging with method, URI, status, duration and correlation id | `target → rule` |
| `carddemo-common` `config/CorrelationIdThreadLocalAccessor.java` | Observability rule — correlation-id propagation into async and Spring Batch worker threads | `target → rule` |
| `carddemo-common` `config/DeployedConfigurationAutoConfiguration.java` | Observability + fail-fast configuration: rejects a missing datasource URL and an unsupported active profile at startup instead of degrading silently | `target → rule` |
| `carddemo-common` `crypto/PiiEncryptionKey.java`, `crypto/PiiEncryptionAutoConfiguration.java` | `app/cpy/CVCUS01Y.cpy` SSN / government id / EFT account id and `app/cpy/CVACT02Y.cpy` CVV — sensitive fields the AAP requires masked and encrypted (0.6.7); validates `CARDDEMO_PII_KEY` eagerly | `source → target` |
| `carddemo-common` `security/SessionContextAuthenticationFilter.java` | `app/cpy/COCOM01Y.cpy` `CDEMO-USER-TYPE` 88-levels `CDEMO-USRTYP-ADMIN` / `CDEMO-USRTYP-USER` → `ROLE_ADMIN` / `ROLE_USER`; converts the externalized COMMAREA into a Spring Security `Authentication` (AAP 0.6.3, 0.6.7) | `source → target` |
| `carddemo-common` `batch/BatchOutputPathResolver.java` (relocated from `batch-service`) | JCL DD data-set resolution — `CREASTMT` `STMTFILE`/`HTMLFILE`, `DALYREJS`, and the `READ*`/`PRTCATBL`/`COMBTRAN` report data sets — mapped onto a single configured, containment-checked output root | `source → target` |
| `carddemo-common` `batch/BatchPathConfig.java` | Supplies the resolver to the three batch-capable services only; deliberately NOT auto-configured so a non-batch service never materialises batch directories | `target → source (derived)` |
| `carddemo-common` `batch/JdbcBatchConfiguration.java` | JES job/step bookkeeping — a JDBC-backed `JobRepository` so the `BATCH_*` metadata is actually written (Spring Batch 6 defaults to an in-memory repository) | `target → source (derived)` |
| `batch-service` `controller/BatchController.java` | `app/cbl/CORPT00C.cbl` TDQ `'JOBS'` → JES asynchronous submission (AAP 0.4.4): submit-and-poll over HTTP for the nine migrated job streams, replacing the unreachable `launch*` methods | `source → target` |
| `db/init/01-create-service-roles.sh` | `app/csd/CARDDEMO.CSD` RACF-era per-principal access → per-service PostgreSQL login roles + the shared `carddemo_app` privilege set that `k8s/secret.yaml` references | `source → target` |
| `k8s/deployment-jaeger.yaml`, `k8s/service-jaeger.yaml` | Observability rule (distributed tracing) — the OTLP collector spans are exported to | `target → rule` |
| `.dockerignore` (repository root) + `auth-service`, `user-service`, `account-service`, `card-service`, `transaction-service`, `billpay-service`, `batch-service`, `api-gateway` `.dockerignore` | Build hygiene for the containerization mandate (AAP 0.2.1): minimal, cache-correct build contexts. One per Docker CONTEXT, because Docker resolves the file relative to the context root | `target → rule` |
| `carddemo-common` `src/test/.../batch/BatchOutputPathResolverTest.java`, `.../config/*`, `.../crypto/*`, `.../security/*`; `batch-service` `BatchJobControllerTest`; `transaction-service` `TransactionControllerSessionTest` | AAP 0.7.1 test mandate (50+ unit-test scenarios) applied to the components added above | `target → rule` |

## 16. Offline Batch Compensator — QA-Remediation Targets (Present on Disk)

Targets created or reworked while resolving the QA findings raised against the offline
batch compensator. Every row is present on disk and was verified by re-running the job
against a live PostgreSQL 18 instance, so the reverse direction stays complete for these
files too. The rationale for each choice is in `docs/decision-log.md` §10.

**Scope of this section.** It does not restate the forward inventory: the *Source
Construct* column names a SPECIFIC BEHAVIOR WITHIN a construct already enumerated in
sections 1–11, or the rule that mandates a target with no legacy analogue.

| Source Construct / Mandate | Target Implementation (delivered) | Direction |
|----------------------------|-----------------------------------|-----------|
| Fixed-width record byte contracts — `DALYREJS` 430B `[app/cbl/CBTRN02C.cbl:L176-L182]`, `FD-REPTFILE-REC` `X(133)` `[app/cbl/CBTRN03C.cbl:L85]`, `STMTFILE` `X(80)` / `HTMLFILE` `X(100)` `[app/jcl/CREASTMT.JCL]` | `carddemo-common` `batch/FixedWidthText.java` (`toSingleByteText`: per-code-point reduction to single-byte text, unmappable → `?`) + `batch/FixedWidthTextTest.java`; consumed by `RejectFileItemWriter`, `TransactionDetailReportWriter.fixed`, `StatementGenerationJob.pad`, each writing ISO-8859-1 | `target → source (derived)` |
| `CVTRA06Y` `DALYTRAN` fixed-width feed record — every field `1500-VALIDATE-TRAN` dereferences is physically present `[app/cbl/CBTRN02C.cbl:L370-L421]` | `carddemo-common` `db/migration/V1__create_schema.sql` `daily_transactions` `NOT NULL` columns + `chk_daily_transactions_orig_ts` (`LENGTH >= 10`); `transaction-service` `batch/DailyTransactionFeedValidator.java` (`requireUsableRecord`, called from `DailyTransactionRowMapper` and `TransactionValidationProcessor`) | `source → target` |
| `POSTTRAN.jcl` operator-scheduled posting job stream `[app/jcl/POSTTRAN.jcl]` | `transaction-service` `config/PostingJobLaunchConfig.java` (`postingDate` identity, non-identifying `run.id` / correlation id) + `controller/PostingJobController.java` (`GET /transactions/batch/jobs`, `POST /transactions/batch/jobs/{jobName}`, `GET /transactions/batch/jobs/executions/{jobExecutionId}`) + `k8s/cronjob-transaction-posting.yaml` (`0 2 * * *`); tests `PostingJobControllerTest`, `TransactionPostingStepListenerTest` | `source → target` |
| `CBTRN02C` `2900-WRITE-TRANSACTION-FILE` writes a posted record under its own `DALYTRAN-ID` `[app/cbl/CBTRN02C.cbl:L562]` | `carddemo-common` `db/migration/V3__seed_test_data.sql` — seeded history re-keyed into the reserved `1e9` id window, and `transaction_id_seq` seeded from the maximum over `transactions` AND `daily_transactions` | `source → target` |
| `CBTRN02C` end-of-run tally reached only on the normal EOF path; `9999-ABEND-PROGRAM` prints none `[app/cbl/CBTRN02C.cbl:L227-L228, L707-L711]` | `transaction-service` `batch/PostingJobCompletionListener.java` — tally suppressed unless the run ended `COMPLETED`; `EMPTY_FEED_EXIT_DESCRIPTION` added | `source → target` |
| `POSTTRAN` was scheduled because a feed had been delivered, so no feed is an operational fault `[app/jcl/POSTTRAN.jcl]` | `transaction-service` `TransactionPostingJob.RejectCountingStepListener` — fails the STEP with `FAILED_EMPTY_FEED` (RC 12) only when the feed table is genuinely empty, so job and step metadata agree and a restart that consumed everything is not misreported | `target → source (derived)` |
| `CBACT04C` `1300-COMPUTE-INTEREST` unrounded `COMPUTE` into `PIC S9(09)V99` `[app/cbl/CBACT04C.cbl:L462-L465]` | `batch-service` `service/InterestCalculationService.computeMonthlyInterest` — `RoundingMode.DOWN`; `InterestCalculationServiceTest` and `carddemo-common` `FinancialPrecisionTest` assert the truncated values through the production method | `source → target` |
| `CBACT04C` `1300-B-WRITE-TX` `MOVE SPACES TO TRAN-MERCHANT-NAME / -CITY / -ZIP` `[app/cbl/CBACT04C.cbl:L492-L494]` | `batch-service` `InterestCalculationService` `TRAN_MERCHANT_NAME_SPACES` / `_CITY_` / `_ZIP_` constants (50 / 50 / 10 blanks) | `source → target` |
| `CBACT04C` `1110-GET-XREF-DATA` keyed `READ XREF-FILE ... KEY IS FD-XREF-ACCT-ID` returns the first record in key sequence `[app/cbl/CBACT04C.cbl:L393-L398]` | `batch-service` `repository/CardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc` | `source → target` |
| `WS-TRANID-SUFFIX` distinguishing the interest transactions of one run `[app/cbl/CBACT04C.cbl:L473-L517]` | `batch-service` `batch/InterestItemProcessor.java` implements `ItemStream`, persisting the suffix high-water mark in the step `ExecutionContext` under `interest.tranIdSuffix` so a restart never re-issues a consumed suffix | `target → source (derived)` |
| `CBTRN03C` EOF branch — `ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL` on the last record, then page and grand totals only `[app/cbl/CBTRN03C.cbl:L197-L204]` | `batch-service` `batch/TransactionDetailReportWriter.close` — reproduces the deliberate double count and emits no trailing account total; headers are still emitted for an empty selection window | `source → target` |
| `CBTRN03C` `1000-TRANFILE-GET-NEXT` reads the date-filtered file in card order, one pass, no paging `[app/cbl/CBTRN03C.cbl:L248]` | `batch-service` `config/DataManagementJobConfig.transactionDetailReportReader` and `repository/TransactionRepository.findByProcTsDateRangeOrderByCardNum` — ordering completed with the primary key so the paging reader has a TOTAL order and cannot drop or repeat a row across a page boundary | `target → source (derived)` |
| `CBSTM03A` `5100`/`5200`/`6000`/`4000` `HTMLFILE` emission of customer-supplied text `[app/cbl/CBSTM03A.CBL]` | `reporting-service` `batch/StatementGenerationJob.htmlEscape` — every interpolated value escaped before the fixed-width `MOVE`; the text statement stays unescaped | `target → source (derived)` |
| `CREASTMT.JCL` carries no `PARM`; its SORT re-keys the whole `TRANSACT` file with no date filter `[app/jcl/CREASTMT.JCL]` | `reporting-service` `controller/ReportController.generateStatements` (`POST /reports/statements`) and `config/JobSchedulingConfig.launchStatementGeneration` — parameter contract reduced to `stmtFile` / `htmlFile` (the `STMTFILE` / `HTMLFILE` DD names); the invented `reportType` / `startDate` / `endDate` identity parameters removed | `source → target` |
| One VSAM browse position per executing job step (`STARTBR`/`READNEXT` per task) `[app/cbl/CBACT01C.cbl; app/cbl/CBACT02C.cbl; app/cbl/CBACT03C.cbl; app/cbl/CBCUS01C.cbl]` | `batch-service` `config/DataManagementJobConfig` — `accountReader`, `cardReader`, `cardXrefReader`, `customerReader`, `categoryBalanceReader` are `@StepScope`; `reporting-service` `statementCardXrefReader` likewise; `batch/LoggingItemWriter` supplied by a `@Bean @StepScope` factory so its running total is per step execution | `target → source (derived)` |
| GDG generation allocated per run; an abending step leaves none `[app/jcl/POSTTRAN.jcl]` | `carddemo-common` `batch/FailedOutputCleanupListener.java` (null-tolerant path list; qualified `COMPLETED_WITH_*` exit codes treated as success) registered on all nine file-producing steps as `rejectFileCleanupListener`, `outputFileCleanupListener`, `reportFileCleanupListener` and `statementCleanupListener` | `target → source (derived)` |
| Respecting .gitignore rule (AAP 0.7.2) | `.gitignore` — the eleven generated batch artefact names taken from the constants that produce them, ignored at any depth, with `app/data/**` re-included by negation | `target → rule` |
| Explainability rule (AAP 0.7.2 / 0.7.3) | `docs/decision-log.md` §10 — 24 rows covering every deviation decided while remediating the batch compensator, including the Flyway-checksum consequence of amending `V1` / `V3` in place | `target → rule` |

## 17. QA-Remediation Targets — Cross-Cutting Online Compensator Checkpoint

Targets created or changed while resolving the twenty-one runtime findings of the cross-cutting
online checkpoint. Each row names the legacy construct or rule it serves, so the reverse
direction stays complete for these artifacts too; the rationale for every choice is in
`docs/decision-log.md` §11 (finding numbers in parentheses are the QA report's).

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `carddemo-common` `db/migration/V6__security_users_optimistic_lock.sql` + `SecurityUser.version` (`@Version`) (Q4) | CICS read-snapshot-compare-rewrite lock pattern applied to the user master `[app/cbl/COUSR02C.cbl, app/cpy/CSUSR01Y.cpy]` — no legacy field | `target → source (derived)` |
| `carddemo-common` `db/migration/V7__cards_optimistic_lock.sql` + `Card.version` (`@Version`) + `version` on `CardDetailResponseDto` / `CardUpdateResponseDto` / `CardUpdateRequestDto` and `frontend/src/types/card.ts` (Q12) | `COCRDUPC 9300-CHECK-CHANGE-IN-REC` and the `REWRITE` it guards `[app/cbl/COCRDUPC.cbl:L1498-L1519]`; `DATA-WAS-CHANGED-BEFORE-UPDATE` `[app/cbl/COACTUPC.cbl:L517-L523]` — no legacy field | `target → source (derived)` |
| `carddemo-common` `db/migration/V8__transactions_card_fk.sql` (`fk_transactions_card`) (Q21) | Ordered cross-reference-then-account lookup and the post-vs-reject branch `[app/cbl/CBTRN02C.cbl:L210-L215, L371-L397]`; `TRAN-CARD-NUM` → `CARD-NUM` `[app/cpy/CVTRA05Y.cpy, app/cpy/CVACT02Y.cpy]`; AAP 0.1.1 / 0.4.5 declarative integrity | `source → target` |
| `card-service` `repository/CardRepository.findForUpdateByCardNum` (`@Lock(PESSIMISTIC_WRITE)`) (Q12) | `COCRDUPC 9200 READ … UPDATE` — the VSAM update lock held across the rewrite `[app/cbl/COCRDUPC.cbl]` | `target → source (derived)` |
| `card-service` `service/CardService.assertVersionUnchanged` + conditional `oldCardCvvCd` compare in `hasDataChangedSinceSnapshot` (Q10, Q12) | `CCUP-OLD-*` display-time snapshot compare `[app/cbl/COCRDUPC.cbl:L1498-L1519]`, reconciled with the CVV protection AAP 0.6.7 mandates | `target → source (derived)` |
| `card-service` `mapper/CardMapper.applyUpdate` CVV retention guard (Q11) | `CARD-CVV-CD PIC 9(03)` as a field the update map never carried `[app/cpy/CVACT02Y.cpy:L7, app/cbl/COCRDUPC.cbl]` | `source → target` |
| `carddemo-common` `dto/CardUpdateRequestDto` CVV three-digit constraint (Q13) | `CARD-CVV-CD PIC 9(03)` `[app/cpy/CVACT02Y.cpy:L7]` | `source → target` |
| `card-service` `service/CardService.validateEmbossedName` split literals `Card name not provided` / `Card name can only contain alphabets and spaces` (Q14) | `COCRDUPC 1230-EDIT-NAME` `WS-PROMPT-FOR-NAME` `[app/cbl/COCRDUPC.cbl:L181-L184, L817]` | `source → target` |
| `card-service` `service/CardService.listCards` filter-honouring scope resolution (Q15) | `COCRDLIC 9500-FILTER-RECORDS` — filters on the entered `ACCTSID` with no user-type branch `[app/cbl/COCRDLIC.cbl:L1386]` | `source → target` |
| `account-service`, `card-service`, `transaction-service`, `billpay-service` `repository/CardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc` (Q7) | CXACAIX NON-unique alternate index browsed with `STARTBR`/`READNEXT` `[app/cpy/CVACT03Y.cpy, app/cbl/CBTRN02C.cbl:L371-L397]` | `source → target` |
| `account-service` `repository/AccountRepository.advanceAggregateVersion` + `AccountService` aggregate-change detection (Q8) | Dual `REWRITE` of account then customer as one unit `[app/cbl/COACTUPC.cbl:L4066-L4090]` and the field-by-field snapshot compare `[L4131-L4189]` | `target → source (derived)` |
| `account-service` `service/AccountUpdateValidator` remaining field-width edits and the `ZEROS` character compare (Q9) | `1200-EDIT-MAP-INPUTS` ordered edits `[app/cbl/COACTUPC.cbl]`; `ACCT-GROUP-ID X(10)`, `CUST-ADDR-LINE-n X(50)`, `CUST-*-NAME X(25)`, `CUST-ADDR-COUNTRY-CD X(03)`, `CUST-GOVT-ISSUED-ID X(20)`, `CUST-EFT-ACCOUNT-ID X(10)` `[app/cpy/CVACT01Y.cpy, app/cpy/CVCUS01Y.cpy]` | `source → target` |
| `carddemo-common` `dto/AddUserRequestDto` / `UpdateUserRequestDto` body-carried `password` plus width and user-type constraints (Q1, Q2, Q3, Q5) | `SEC-USR-ID X(08)`, `SEC-USR-PWD X(08)`, `SEC-USR-FNAME`/`LNAME X(20)`, `SEC-USR-TYPE X(01)` `[app/cpy/CSUSR01Y.cpy]`; `COUSR01C`/`COUSR02C` edits `[app/cbl/COUSR01C.cbl, app/cbl/COUSR02C.cbl]` | `source → target` |
| `user-service` `service/UserService` insert-and-catch creation + version conflict mapping (Q4) | `COUSR01C` `WRITE` DUPKEY → `User ID already exist...` and `COUSR02C` rewrite `[app/cbl/COUSR01C.cbl, app/cbl/COUSR02C.cbl]` | `source → target` |
| `carddemo-common` `security/SessionPrincipalIndex` in-place revocation marker (Q6) | `COUSR02C` credential change ending the signed-on session's authority `[app/cbl/COUSR02C.cbl, app/cpy/COCOM01Y.cpy]` | `target → source (derived)` |
| `transaction-service` `service/TransactionService` amount-scale edit, category/merchant width edits and narrowed duplicate-id detection (Q16, Q17) | `COTRN02C` amount picture and `EVALUATE` edit order `[app/cbl/COTRN02C.cbl:L325-L351, L430-L432]`; `TRAN-CAT-CD 9(04)`, `TRAN-MERCHANT-ID 9(09)` `[app/cpy/CVTRA05Y.cpy]`; DUPKEY → `Tran ID already exist...` | `source → target` |
| `billpay-service` `service/BillPaymentService` narrowed duplicate-id detection (Q16) | `COBIL00C` `WRITE` DUPKEY handling `[app/cbl/COBIL00C.cbl:L535-L537]` | `source → target` |
| `batch-service` `config/JobSchedulingConfig.launchRepeatable` (identifying run id for read/print runs) and `reporting-service` `config/JobSchedulingConfig` statement run id (Q18) | `CORPT00C` TDQ `'JOBS'` write on EVERY request → JES re-run `[app/cbl/CORPT00C.cbl:L450]`; `TRANREPT`/`CREASTMT` job streams `[app/proc/TRANREPT.prc, app/jcl/CREASTMT.jcl]` | `source → target` |
| `reporting-service` `client/BatchJobClient` refusal-versus-hand-off distinction (`SUBMISSION_REFUSED_PREFIX`) (Q18) | `CORPT00C` `SUBMIT-JOB-TO-INTRDR` TDQ write outcome `[app/cbl/CORPT00C.cbl:L450]` | `target → source (derived)` |
| `carddemo-common` `config/RequestLoggingFilter`, `config/GlobalExceptionHandler`, `config/CardDemoErrorController` and `api-gateway` `config/UpstreamFailureHandler` PAN masking on every log path (Q19) | `CARD-NUM PIC X(16)` protection AAP 0.6.7 + the "no sensitive values in logs" rule; `security/SensitiveDataMasker` `[app/cpy/CVACT02Y.cpy]` | `target → rule` |
| `reporting-service` `src/test/.../client/BatchJobClientTest.java`; `batch-service` `JobLaunchParameterIT` repeatability and identifying-flag cases; `card-service` `OptimisticLockConflictIT` CVV-retention and version-token cases; the new `CardServiceTest`, `TransactionServiceTest`, `BillPaymentServiceTest`, `UserServiceTest`, `AccountServiceTest` and `AccountUpdateValidatorTest` regression cases | AAP 0.7.1 "at least 50 unit-test scenarios must pass" — each case pins one remediated legacy behavior | `target → rule` |

## Coverage Assertion

Sections 12 through 17 additionally record the targets added or corrected during successive
QA-remediation passes; their rows refine behaviors within constructs already enumerated above
rather than adding new sources, so the forward enumeration remains one row per legacy construct.
