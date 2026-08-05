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

The REVERSE direction is closed at file granularity in §21: every shipped React page, API module,
type module, hook, shared component, backend controller and frontend test suite on disk carries a
`target → source` row naming the legacy construct it re-expresses, or a `target → rule` row where
the file exists to satisfy a user-specified rule or the standalone-operation requirement and has no
legacy analogue. A frontend or controller file that appears on disk and in no §21 row would be a
coverage gap by construction.

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
| COMMAREA-carried SCREEN context — `CDEMO-LAST-MAP` / `CDEMO-LAST-MAPSET` `PIC X(7)`, the `TRNNAME` / `PGMNAME` / `TITLE01` / `TITLE02` header fields, the line-23 `ERRMSG` `X(78)` region and the line-24 key legend `[app/cpy/COCOM01Y.cpy:L18-L45; app/bms/COSGN00.bms]` | `frontend/src/components/Layout.tsx` `useScreenChrome` context. `ScreenChrome` carries `transactionId`, `programName`, `title01`, `title02`, `currentDate`, `currentTime`, `captionStyle`, `appId`, `sysId`, `errorMessage`, `infoMessage`, `pfKeys`, `busy` and `plainText`; `ScreenChromeActions` exposes `setChrome`, which REPLACES the published value outright (a send paints a whole map, it does not merge into the previous one), and `resetChrome`, which returns the frame to its published-nothing state and is called on a route change and from a page's own cleanup. `usePublishedChrome` reads the current value. This is the per-screen half of the COMMAREA, held per mount, while the identity half stays in `SessionContext` (row above in §5) | `source → target` |

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
| Frontend test harness (AAP 0.2.1 "Jest with React Testing Library") | `frontend/src/setupTests.ts` (`@testing-library/jest-dom` matchers + the `TextEncoder` / `TextDecoder` globals `jsdom` omits and `react-router` requires, so every routed screen is renderable under test) | `target → rule` |
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
| Passing UI-workflow tests (AAP 0.7.1) — the 17 routed screens are only testable under jsdom once the Web encoding globals `react-router` needs at import time exist | `frontend/src/setupTests.ts` (`@testing-library/jest-dom` matchers + guarded `TextEncoder` / `TextDecoder` polyfill from `node:util`) | `target → rule` |

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
| `frontend/src/setupTests.ts` (`@testing-library/jest-dom` matcher registration + guarded `TextEncoder` / `TextDecoder` globals from `node:util`) | AAP §0.7.1 "all 17 UI workflows behave identically" — `jsdom` omits both globals and React Router reads `TextEncoder` at module load, so every routed page test needs them defined in `setupFilesAfterEnv` (decision-log §13) | `target → rule` |
| Frontend barrels `frontend/src/hooks/index.ts` (re-exports `useSession` / `UseSessionResult`, `usePagination` / `UsePaginationResult` with `CARD_LIST_PAGE_SIZE` 7, `TRANSACTION_LIST_PAGE_SIZE` 10, `USER_LIST_PAGE_SIZE` 10, and `useApi` / `UseApiResult`), `frontend/src/types/index.ts`, `frontend/src/api/index.ts` | AAP §0.5.3 frontend ES-module import convention — one import specifier per folder; organizational only, no runtime logic, no default export, no legacy analogue | `target → rule` |
| `api-gateway` `config/ObservabilityEndpointsIT` + `SessionRedisRoundTripIT`; `auth-service`, `user-service`, `account-service` `config/ObservabilityEndpointsIT`; `carddemo-common` `config/ObservabilityConfigTest`, `WebObservabilityConfigTest` | Observability rule (AAP §0.7.5) "verified in the local environment" + COMMAREA session externalization `[app/cpy/COCOM01Y.cpy:L18-45]` | `target → rule` |

> The business-schema migrations (`V1__create_schema.sql`,
> `V2__seed_reference_data.sql`, `V3__seed_test_data.sql`, Java migration `4`,
> `V5__batch_metadata.sql`) are **delivered** — they live in
> `carddemo-common/src/main/resources/db/migration` and are applied by the single
> migration owner (`batch-service`). The remaining per-screen `*RequestDto` /
> `*ResponseDto` classes, `frontend/src/api`, `frontend/src/types/*`, the 17 page
> components and the screen layer are delivered and enumerated in §21 and §22 onwards;
> every remaining construct is mapped above in the end-state design.
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
| `carddemo-common` `db/migration/V3__seed_test_data.sql` `{bcrypt}` identifier prefix | `USRSEC` seeded credentials — data set defined by the load job, layout by the copybook `[app/jcl/DUSRSECJ.jcl; app/cpy/CSUSR01Y.cpy]` | `source → target` |
| `account-service`, `card-service`, `transaction-service`, `billpay-service`, `reporting-service`, `batch-service` `config/SecurityConfig.java` | CICS transaction security for `CAVW`/`CAUP`/`CCLI`/`CCDL`/`CCUP`/`CT00`–`CT02`/`CB00`/`CR00` `[app/csd/CARDDEMO.CSD]` | `source → target` |
| `carddemo-common` `config/GlobalExceptionHandler` unreadable-body (`413`/`400`) and catch-all handlers | CICS `RESP`/file-status outcome mapping + Observability rule (a trace id on every error payload) | `target → source (derived)` |
| `carddemo-common` tests `security/{SecurityAuditConfigTest,AuditingAccessDeniedHandlerTest,RateLimitFilterTest,SessionPrincipalIndexTest,SessionContextAuthenticationFilterTest,SensitiveDataMaskerTest}`, `crypto/{CryptoConverterTest,SeededPiiEncryptionMigratorTest}`, `config/{RequestSizeLimitFilterTest,GlobalExceptionHandlerLoggingTest,GlobalExceptionHandlerUnreadableBodyTest,GlobalExceptionHandlerUnhandledTest}` | AAP 0.7.1 test obligation for the controls above | `target → rule` |
| `auth-service` `security/LoginAttemptServiceTest`; `account-service`, `billpay-service`, `reporting-service`, `transaction-service` `SecurityContractIT`; `card-service` `CardViewUpdateIT.SecurityContract` (nested) | AAP 0.7.1 test obligation — each freezes "an anonymous request to this service port never reaches business logic" | `target → rule` |
| `.gitignore` `blitzy/` + cookie-jar patterns | Respecting-.gitignore rule (AAP 0.7.2) — QA evidence holds live session ids and must never be staged | `target → rule` |
| `carddemo-common` `crypto/SensitiveDataCryptoException.java`, `crypto/SeededPiiEncryptionMigrator.java`, `crypto/PiiEncryptionConfig.java` | At-rest protection of `CUST-SSN`, `CUST-GOVT-ISSUED-ID`, `CUST-EFT-ACCOUNT-ID`, `CARD-CVV-CD` (AAP 0.6.7) `[app/cpy/CVCUS01Y.cpy; app/cpy/CVACT02Y.cpy]` | `target → source (derived)` |

> The business-schema and seed migrations are delivered as ONE shared set in
> `carddemo-common/src/main/resources/db/migration` — `V1__create_schema.sql`,
> `V2__seed_reference_data.sql`, `V3__seed_test_data.sql` (which also seeds the ten
> `USRSEC` users with `{bcrypt}` hashes), the Java migration `SeededPiiEncryptionMigration`
> (version `4`), `V5__batch_metadata.sql`, `V6__security_users_optimistic_lock.sql`,
> `V7__cards_optimistic_lock.sql` and `V8__transactions_card_fk.sql` — applied by the single
> migration owner (`batch-service`), because three services run Spring Batch jobs and every
> service validates its mappings against the one database. All ten `Dockerfile`s, the Vite
> entry point (`frontend/src/main.tsx`), the route table (`frontend/src/App.tsx`) and the 17
> page components under `frontend/src/pages/` are **present on disk** and mapped above.

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
| `carddemo-common` `crypto/PiiAtRestInitializer.java` + `account-service` / `card-service` `config/PiiAtRestConfig.java` | At-rest normalization of ASCII-fixture seed data to the `CryptoConverter` format `[app/data/ASCII/custdata.txt; app/data/ASCII/carddata.txt]` | `target → rule` |
| `carddemo-common` `exception/PiiEncryptionException.java` | Fail-closed outcome of the converter (never surfaces plaintext or an opaque 500) | `target → rule` |
| `carddemo-common` `security/SessionContextAuthenticationFilter.java` | COMMAREA `CDEMO-USER-TYPE` 88-levels → Spring Security authorities on every request `[app/cpy/COCOM01Y.cpy:L26-L30]` | `source → target` |
| `carddemo-common` `dto/SessionAttributes.java` | Single COMMAREA-equivalent session attribute name shared by every service `[app/cpy/COCOM01Y.cpy:L18-L45]` | `source → target` |
| `carddemo-common` `config/RedisCommandMetricsConfig.java` (Lettuce `MicrometerCommandLatencyRecorder`) | Observability rule (AAP 0.7.5) — Redis command latency series consumed by `observability/grafana-dashboard.json` | `target → rule` |
| `carddemo-common` `config/WebObservabilityConfig.java` activated by `auth-service`, `user-service`, `reporting-service` | Observability rule (correlation id in the MDC and in `ErrorResponse.traceId`) | `target → rule` |
| `carddemo-common` `dto/UserListResponseDto.java`, `dto/UserWriteResponseDto.java` | `COUSR00C` paging literals + `COUSR01C`/`COUSR02C`/`COUSR03C` confirmation messages `[app/cbl/COUSR00C.cbl:L211-L273]` | `source → target` |
| `account-service` `service/AccountUpdateValidator.java` | `COACTUPC` `1200-EDIT-MAP-INPUTS` 25-field edit sequence + `1280` state/zip cross-field edit + `CSLKPCDY` lookup sets `[app/cbl/COACTUPC.cbl; app/cpy/CSLKPCDY.cpy]` | `source → target` |
| `carddemo-common` `domain/Transaction.java` implementing `Persistable<String>` | `COTRN02C` / `COBIL00C` `WRITE` semantics — an existing transaction is never overwritten `[app/cbl/COTRN02C.cbl:L444-L451]` | `target → source (derived)` |
| `reporting-service` `config/StatementOutputResolver.java` | `CREASTMT` / `CBSTM03A` statement destination, confined to a configured root `[app/jcl/CREASTMT.JCL; app/cbl/CBSTM03A.CBL]` | `source → target` |
| `reporting-service` / `batch-service` `config/BatchJobRepositoryConfig.java` (`@EnableBatchProcessing` + `@EnableJdbcJobRepository`) | JES job accounting — every submitted job recorded in `BATCH_JOB_INSTANCE` / `BATCH_JOB_EXECUTION` `[app/cbl/CORPT00C.cbl]` | `target → source (derived)` |
| `docker-compose.yml` `jaeger` service + `management.opentelemetry.tracing.export.otlp.endpoint` (docker profile) | Observability rule (AAP 0.7.5) — distributed tracing verifiable in the local environment | `target → rule` |
| `carddemo-common` `migration/SeededPiiEncryptionMigration.java` + `crypto/SeededPiiEncryptionMigrator.java` | At-rest normalization of ASCII-fixture seed data to the `CryptoConverter` format `[app/data/ASCII/custdata.txt; app/data/ASCII/carddata.txt]` | `target → rule` |

## 14. QA Remediation — Runtime-Verified Batch, Security & Integration Targets

Targets added or corrected while resolving the runtime findings raised by end-to-end QA of the integrated backend. Every row was verified against the running application, not only against the source.

**Scope of this section.** It does not restate the forward inventory. Its *Source Construct* column names a SPECIFIC BEHAVIOR WITHIN a construct already enumerated exactly once in Sections 1-8 (for example the `CBTRN02C` feed read and its reject file, rather than `CBTRN02C` itself), or a cross-cutting concern with no legacy analogue. The forward "exactly once" enumeration in Sections 1-8 is therefore unaffected, and this section supplies the reverse (`target -> source (derived)` / `target -> rule`) coverage for the components added during remediation.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `CBTRN02C` daily-transaction feed read `[app/cbl/CBTRN02C.cbl:L371-L397]` | `transaction-service` `batch/TransactionPostingJob.java` → `JdbcCursorItemReader` over `daily_transactions` (`DALYTRAN_FEED_SQL`, ordered by `dalytran_id`) + `batch/DailyTransactionRowMapper.java` | `source → target` |
| `CBTRN02C` reject file `DALYREJS` (430B = 350B image + 80B trailer) `[app/cbl/CBTRN02C.cbl:L176-L182]` | `transaction-service` `batch/RejectFileItemWriter.java`, path resolved through `carddemo-common` `batch/BatchOutputPathResolver.java` | `source → target` |
| `CBTRN02C` result-code tally 0 / 4 / 12 `[app/cbl/CBTRN02C.cbl:L228]` | `transaction-service` `batch/PostingJobCompletionListener.java` (`COMPLETED_WITH_REJECTS` / `FAILED_EMPTY_FEED`) | `source → target` |
| `COTRN02C` transaction-id assignment `[app/cbl/COTRN02C.cbl:L444-L451]` | `transaction-service` `service/TransactionService.java#nextUnusedTransactionId` over the `transaction_id_seq` sequence created by `V1__create_schema.sql` | `source → target` |
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
| `CREASTMT` / `CBSTM03A` statement generation `[app/jcl/CREASTMT.jcl; app/cbl/CBSTM03A.CBL]` | `reporting-service` `batch/StatementGenerationJob.java`, launched by `controller/ReportController.java#generateStatements` (`POST /reports/statements`) | `source → target` |
| JCL `DD` data-set allocation for generated report / dump / statement files `[app/jcl/]` | `carddemo-common` `batch/BatchOutputPathResolver.java` + `config/BatchOutputConfig.java` (allowlisted, writable output and input roots) | `source → target` |
| `BATCH_*` job metadata tables and restartability | `batch-service` / `transaction-service` / `reporting-service` `config/BatchInfrastructureConfig.java` + Flyway `V5__batch_metadata.sql` (no legacy analogue — JES held run history) | `target → source (derived from JES job history)` |
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
| `carddemo-common` `config/LettuceMetricsAutoConfiguration.java` | Observability rule — publishes the `lettuce_command_*` series the Redis dashboard panels query | `target → rule` |
| `batch-service` `controller/BatchJobController.java` | `app/cbl/CORPT00C.cbl` TDQ `'JOBS'` → JES asynchronous submission (AAP 0.4.4): submit-and-poll over HTTP for the nine migrated job streams, replacing the unreachable `launch*` methods | `source → target` |

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
`docs/decision-log.md` §8 (finding numbers in parentheses are the QA report's).

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

## 18. Frontend Shared Screen Shell — Presentation Tranche Targets

Targets delivered while building the shared 24x80 screen shell and its colocated suite. Each row
names the legacy construct or rule it serves, so the reverse direction stays complete; the
rationale for every choice is in `docs/decision-log.md` §12.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `frontend/src/components/Layout.tsx` — the `.screen` frame composing `Header` (rows 1-3), `main.screen__body` (rows 4-22), `ErrorBanner` (line 23) and `PFKeyBar` (line 24) | The fixed BMS mapset frame every screen inherits: `DFHMDI SIZE=(24,80)` with the `Tran :` / `Prog :` / `Date :` / `Time :` header rows `[app/bms/COSGN00.bms:L26-L74]`; PF-key legend semantics `[app/cpy/CSSTRPFY.cpy]`; screen-attribute semantics `[app/cpy/CSSETATY.cpy]` | `source → target` |
| `frontend/src/components/Layout.tsx` — `ScreenChrome` / `ScreenChromeContextValue` and the `useScreenChrome()` context hook | The COMMAREA fields a program moved into the shared symbolic map before `SEND MAP`, including `CDEMO-LAST-MAP` / `CDEMO-LAST-MAPSET PIC X(7)` `[app/cpy/COCOM01Y.cpy:L18-L45, L44]` | `source → target` |
| `frontend/src/components/Layout.tsx` — `data-authenticated` on the `.screen` frame | `CDEMO-USER-TYPE` sign-on state gating screen entry, `88 CDEMO-USRTYP-ADMIN VALUE 'A'` / `88 CDEMO-USRTYP-USER VALUE 'U'` `[app/cpy/COCOM01Y.cpy:L26-L30]`; the `COSGN00C` routing that follows a successful sign-on `[app/cbl/COSGN00C.cbl:L227, L231-L237]` | `target → source (derived)` |
| `frontend/src/hooks/useSession.ts` — `__setSession(user, role)` test seam | No legacy analogue — a test-only entry point into the provider-free session store, required to exercise the authenticated and signed-out states of the shell without a network round trip (AAP 0.7.1: the UI workflows must be verifiable) | `target → rule` |
| `frontend/src/hooks/index.ts` — barrel over `useApi`, `usePagination`, `useSession` | No legacy analogue — the ES-module single-specifier import convention AAP 0.5.3 mandates for the frontend, matching the existing `api/` and `types/` barrels | `target → rule` |
| `frontend/src/components/Layout.test.tsx` — three cases: the frame renders header, routed children and the `Function keys` toolbar; a page publishes tran/prog/title/error/keys through `useScreenChrome`; `data-authenticated` tracks `useSession().isAuthenticated` | AAP 0.7.1 "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically" — each case pins one behavior of the shell every screen inherits. The published error literal is the optimistic-lock message `Record changed by some one else. Please review` `[app/cbl/COACTUPC.cbl:L517-L523]`, and the published `CAUP` / `COACTUPC` pair is the account-update transaction and program `[app/cbl/COACTUPC.cbl]` | `target → rule` |

## 19. SPA Card-Detail Screen — Present-on-Disk Targets

Targets delivered while building the card-detail screen. Each row names the legacy construct or
rule it serves, so the reverse direction stays complete for these artifacts too; the rationale for
every choice is in `docs/decision-log.md` §12.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `frontend/src/pages/CardDetailPage.tsx` | `COCRDSL` mapset + `CCRDSLA` symbolic map + `COCRDSLC` field edits and screen setup `[app/bms/COCRDSL.bms; app/cpy-bms/COCRDSL.CPY; app/cbl/COCRDSLC.cbl]` | `source → target` |
| `CardDetailPage` caption literals `Account Number    :` / `Card Number       :` / `Name on card      :` / `Card Active Y/N   :` / `Expiry Date       :` | The TURQUOISE `DFHMDF INITIAL` literals on lines 7, 8, 11, 13, 15 `[app/bms/COCRDSL.bms:L79-L125]` | `source → target` |
| `CardDetailPage` chrome payload `transactionId` `CCDL` / `programName` `COCRDSLC` / PF legend `ENTER=Search Cards` + `F3=Exit` | `LIT-THISTRANID` / `LIT-THISPGM` and the `FKEYS` line-24 literal `[app/cbl/COCRDSLC.cbl:L162-L170; app/bms/COCRDSL.bms:L148-L152]` | `source → target` |
| `CardDetailPage` `validateSearchFilters` — the four verbatim edit literals in account-then-card order | `2210-EDIT-ACCOUNT` / `2220-EDIT-CARD` and the `WS-RETURN-MSG` 88-levels `[app/cbl/COCRDSLC.cbl:L143-L149, L646-L720]` | `source → target` |
| `CardDetailPage` `splitExpiraionDate` — positional `EXPMON` / `EXPYEAR` display | `CARD-EXPIRAION-DATE-X` redefined by `CARD-EXPIRY-YEAR` / `CARD-EXPIRY-MONTH`, moved to `EXPMONO` / `EXPYEARO` `[app/cbl/COCRDSLC.cbl:L84-L92, L477-L482]` | `source → target` |
| `frontend/src/components/Layout.tsx` (`ScreenChrome`, `useScreenChrome`, `Layout` shell) | The 3270 screen frame: header lines 1-2, the line-23 `ERRMSG` region and the line-24 `FKEYS` legend, published per screen as `POPULATE-HEADER-INFO` / `SETUP MESSAGE` do before `SEND MAP` `[app/bms/COCRDSL.bms:L29-L152; app/cpy/CSSTRPFY.cpy; app/cpy/CSSETATY.cpy]` | `source → target` |
| `frontend/src/hooks/index.ts` (hook barrel) | AAP §0.3.2 `hooks/` module layout and the ES-module single-specifier import convention (AAP §0.5.3) — no legacy analogue | `target → rule` |
| `frontend/src/index.css` terminal-fidelity rules (`main` heading reset, `h1/h2.appHeader__center` reset, `main dl` row layout, caption `white-space: pre`, `.screenTitle`) | The 24×80 uniform character cell: caption at column 4 with its value at column 25, and the centered line-4 title at `POS(4,30)` `[app/bms/COCRDSL.bms:L75-L136]` | `source → target` |
| `frontend/src/setupTests.ts` `TextEncoder` / `TextDecoder` polyfills | Testability of the routed screens under Jest/jsdom; AAP §0.2.1 frontend test deliverable — no legacy analogue | `target → rule` |

## 20. Card Update Screen (`CardUpdatePage` checkpoint) — Present on Disk

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COCRDUP` mapset body — the `CCRDUPA` caption/field grid, `ACCTSID` `X(11)` / `CARDSID` `X(16)` protected once a record is displayed, `CRDNAME` `X(50)`, `CRDSTCD` `X(01)`, `EXPMON` `X(02)` / `EXPYEAR` `X(04)` with `EXPDAY` never displayed, and the `FKEYS` / `FKEYSC` legend `[app/bms/COCRDUP.bms; app/cpy-bms/COCRDUP.CPY; app/cbl/COCRDUPC.cbl:L1085-L1196]` | `frontend/src/pages/CardUpdatePage.tsx` — `getCard` read on mount, the five padded captions bound by `label[for]`, read-only key fields, the three editable fields at their legacy widths, `updateCard` rewrite, and the `CCUP` / `COCRDUPC` chrome with `ENTER=Process` / `F3=Exit` / `F5=Save` / `F12=Cancel` published through `useScreenChrome` | `source → target` |
| `COCRDUPC` edit literals `Card number if supplied must be a 16 digit number`, `Card Active Status must be Y or N`, `Card expiry month must be between 1 and 12`, `Invalid card expiry year` and the `IF WS-RETURN-MSG-OFF` first-error-wins guard `[app/cbl/COCRDUPC.cbl:L193-L199, L806-L945]` | `CardUpdatePage` `editMapInputs` — same edit order (key → status → month → year), only the first failing edit publishes its message, every failing field flagged `fieldError` | `source → target` |
| `COCRDUPC` info-message state machine `FOUND-CARDS-FOR-ACCOUNT` / `PROMPT-FOR-CONFIRMATION` / `CONFIRM-UPDATE-SUCCESS` / `INFORM-FAILURE` and `DATA-WAS-CHANGED-BEFORE-UPDATE` `[app/cbl/COCRDUPC.cbl:L160-L171, L208, L1139-L1161]` | `CardUpdatePage` `infoMessage` / `errorMessage` published to the shell: `Details of selected card shown above`, `Changes validated.Press F5 to save`, `Changes committed to database`, `Changes unsuccessful. Please try again`, and `Record changed by some one else. Please review` on the HTTP 409 `ApiError` | `source → target` |
| `CARD-EXPIRAION-DATE-X` `X(10)` redefined year / month / day, with `CCUP-OLD-EXPDAY` moved back unchanged because the map never presents the day `[app/cbl/COCRDUPC.cbl:L115-L123, L1124-L1126]` | `CardUpdatePage` `splitExpiraionDate` / `joinExpiraionDate` — month and year edited, the day carried over from the record read, reassembled as the `cardExpiraionDate` `YYYY-MM-DD` wire value (legacy misspelling preserved) | `source → target` |
| `CSSETATY` re-entry attribute logic — `FLG-<field>-BLANK` marks a blank required field with `'*'` written into the map field `[app/cpy/CSSETATY.cpy; app/cbl/COCRDUPC.cbl:L623-L636]` | `CardUpdatePage` `.cardUpdate__marker` — a reserved one-character gutter per field cell filled from `fieldMarker`, so the `*` appears without overwriting the operator's value and without moving the field column (decision log Section 13) | `target → source (derived)` |
| CICS `SEND MAP` atomicity — the body, the line-23 `ERRMSG` and the line-24 legend reach the terminal in one transmission `[app/bms/COCRDUP.bms; app/cbl/COCRDUPC.cbl:L1085-L1161]` | `CardUpdatePage` chrome publication from `useLayoutEffect` (flushed synchronously at commit), so the shell's message and PF keys never trail the body they belong to (decision log Section 13) | `target → source (derived)` |
| Jest/jsdom test-runtime gap (no WHATWG encoding API for react-router) — rule-mandated test infrastructure, no legacy analogue | `frontend/src/setupTests.ts` — guarded `TextEncoder` / `TextDecoder` installation enabling every routed page suite to run | `target → rule` |

## 21. Frontend Screen Layer — Exact Target-to-Source Rows (`target → source`)

Every shipped React page, API module, type module, hook and shared component on disk, each
traced back to the legacy construct it re-expresses. This closes the reverse direction at file
granularity: no frontend file exists without a named source or an explicit rule mandate.

### 21.1 Pages (17) — one per BMS mapset

| Target (on disk) | Source construct | Direction |
|---|---|---|
| `frontend/src/pages/SignonPage.tsx` | `app/bms/COSGN00.bms` + `app/cbl/COSGN00C.cbl` (`CC00`) | `target → source` |
| `frontend/src/pages/MainMenuPage.tsx` | `app/bms/COMEN01.bms` + `app/cbl/COMEN01C.cbl` (`CM00`) | `target → source` |
| `frontend/src/pages/AdminMenuPage.tsx` | `app/bms/COADM01.bms` + `app/cbl/COADM01C.cbl` (`CA00`) | `target → source` |
| `frontend/src/pages/AccountViewPage.tsx` | `app/bms/COACTVW.bms` + `app/cbl/COACTVWC.cbl` (`CAVW`) | `target → source` |
| `frontend/src/pages/AccountUpdatePage.tsx` | `app/bms/COACTUP.bms` + `app/cbl/COACTUPC.cbl` (`CAUP`) | `target → source` |
| `frontend/src/pages/CardListPage.tsx` | `app/bms/COCRDLI.bms` + `app/cbl/COCRDLIC.cbl` (`CCLI`) | `target → source` |
| `frontend/src/pages/CardDetailPage.tsx` | `app/bms/COCRDSL.bms` + `app/cbl/COCRDSLC.cbl` (`CCDL`) | `target → source` |
| `frontend/src/pages/CardUpdatePage.tsx` | `app/bms/COCRDUP.bms` + `app/cbl/COCRDUPC.cbl` (`CCUP`) | `target → source` |
| `frontend/src/pages/TranListPage.tsx` | `app/bms/COTRN00.bms` + `app/cbl/COTRN00C.cbl` (`CT00`) | `target → source` |
| `frontend/src/pages/TranViewPage.tsx` | `app/bms/COTRN01.bms` + `app/cbl/COTRN01C.cbl` (`CT01`) | `target → source` |
| `frontend/src/pages/TranAddPage.tsx` | `app/bms/COTRN02.bms` + `app/cbl/COTRN02C.cbl` (`CT02`) | `target → source` |
| `frontend/src/pages/BillPayPage.tsx` | `app/bms/COBIL00.bms` + `app/cbl/COBIL00C.cbl` (`CB00`) | `target → source` |
| `frontend/src/pages/ReportPage.tsx` | `app/bms/CORPT00.bms` + `app/cbl/CORPT00C.cbl` (`CR00`) | `target → source` |
| `frontend/src/pages/UserListPage.tsx` | `app/bms/COUSR00.bms` + `app/cbl/COUSR00C.cbl` (`CU00`) | `target → source` |
| `frontend/src/pages/UserAddPage.tsx` | `app/bms/COUSR01.bms` + `app/cbl/COUSR01C.cbl` (`CU01`) | `target → source` |
| `frontend/src/pages/UserUpdatePage.tsx` | `app/bms/COUSR02.bms` + `app/cbl/COUSR02C.cbl` (`CU02`) | `target → source` |
| `frontend/src/pages/UserDeletePage.tsx` | `app/bms/COUSR03.bms` + `app/cbl/COUSR03C.cbl` (`CU03`) | `target → source` |

### 21.2 Page-layer helpers

| Target (on disk) | Source construct | Direction |
|---|---|---|
| `frontend/src/pages/programRoutes.ts` | The `XCTL PROGRAM(CDEMO-TO-PROGRAM)` transfer target carried in `CDEMO-TO-PROGRAM` `[app/cpy/COCOM01Y.cpy]`: maps a legacy program name to the SPA screen route | `target → source` |
| `frontend/src/pages/tranAddFormat.ts` | `COTRN02C` field pictures — `WS-TRAN-AMT-E PIC +99999999.99` and the `PIC X(10)` date fields `[app/cbl/COTRN02C.cbl]`: formats a copied wire value into the picture the screen's own edits accept | `target → source` |

### 21.3 API modules — one per service route group

| Target (on disk) | Backend endpoint(s) | Source construct | Direction |
|---|---|---|---|
| `frontend/src/api/client.ts` | shared axios instance | The CICS terminal-to-region seam: `EXEC CICS SEND`/`RECEIVE MAP` plus `RESP(DFHRESP(...))` status handling `[app/cbl/COACTUPC.cbl:L3654-L3691]`, re-expressed as interceptors (CSRF double-submit, `X-Correlation-Id`, `401`/`403` session expiry, `409` → optimistic-lock conflict) | `target → source` |
| `frontend/src/api/config.ts` | gateway base URL | `app/csd/CARDDEMO.CSD` transaction routing | `target → source` |
| `frontend/src/api/auth.ts` | `POST /auth/signon`, `POST /logout` | `app/cbl/COSGN00C.cbl` (`CC00`) | `target → source` |
| `frontend/src/api/menu.ts` | `GET/POST /menu`, `GET/POST /admin/menu` | `app/cbl/COMEN01C.cbl` + `app/cbl/COADM01C.cbl`, options from `app/cpy/COMEN02Y.cpy` + `app/cpy/COADM02Y.cpy` | `target → source` |
| `frontend/src/api/accounts.ts` | `GET /accounts/{id}`, `PUT /accounts/{id}` | `app/cbl/COACTVWC.cbl` + `app/cbl/COACTUPC.cbl` | `target → source` |
| `frontend/src/api/cards.ts` | `GET /cards`, `GET /cards/{cardNumber}`, `PUT /cards/{cardNumber}` | `app/cbl/COCRDLIC.cbl`, `COCRDSLC.cbl`, `COCRDUPC.cbl` | `target → source` |
| `frontend/src/api/transactions.ts` | `GET /transactions`, `GET /transactions/{id}`, `GET /transactions/last`, `POST /transactions` | `app/cbl/COTRN00C.cbl`, `COTRN01C.cbl`, `COTRN02C.cbl` (`/transactions/last` serves the `F5=Copy Last Tran.` legend field) | `target → source` |
| `frontend/src/api/billpay.ts` | `POST /billpay` | `app/cbl/COBIL00C.cbl` | `target → source` |
| `frontend/src/api/reports.ts` | `POST /reports` | `app/cbl/CORPT00C.cbl` + the TDQ `'JOBS'` submission | `target → source` |
| `frontend/src/api/users.ts` | `GET /users`, `GET /users/{id}`, `POST /users`, `PUT /users/{id}`, `DELETE /users/{id}` | `app/cbl/COUSR00C.cbl`, `COUSR01C.cbl`, `COUSR02C.cbl`, `COUSR03C.cbl` | `target → source` |
| `frontend/src/api/index.ts` | — | Module barrel; no legacy analogue (build-structure only) | `target → rule` |

### 21.4 Type modules — the symbolic-map and COMMAREA field contracts

| Target (on disk) | Source construct | Direction |
|---|---|---|
| `frontend/src/types/auth.ts` | `app/cpy-bms/COSGN00.CPY` + `app/cpy/CSUSR01Y.cpy` (`SEC-USR-TYPE` → role) | `target → source` |
| `frontend/src/types/session.ts` | `app/cpy/COCOM01Y.cpy:L18-L45` COMMAREA identity half | `target → source` |
| `frontend/src/types/menu.ts` | `app/cpy/COMEN02Y.cpy` + `app/cpy/COADM02Y.cpy` | `target → source` |
| `frontend/src/types/account.ts` | `app/cpy/CVACT01Y.cpy` + `app/cpy/CVCUS01Y.cpy` + `app/cpy-bms/COACTVW.CPY` / `COACTUP.CPY` | `target → source` |
| `frontend/src/types/card.ts` | `app/cpy/CVACT02Y.cpy` + `app/cpy/CVACT03Y.cpy` + `app/cpy-bms/COCRDLI.CPY` / `COCRDSL.CPY` / `COCRDUP.CPY` | `target → source` |
| `frontend/src/types/transaction.ts` | `app/cpy/CVTRA05Y.cpy` + `app/cpy-bms/COTRN00.CPY` / `COTRN01.CPY` / `COTRN02.CPY` | `target → source` |
| `frontend/src/types/billpay.ts` | `app/cpy-bms/COBIL00.CPY` + `app/cbl/COBIL00C.cbl` | `target → source` |
| `frontend/src/types/report.ts` | `app/cpy-bms/CORPT00.CPY` + `app/cbl/CORPT00C.cbl` | `target → source` |
| `frontend/src/types/user.ts` | `app/cpy/CSUSR01Y.cpy` + `app/cpy-bms/COUSR00.CPY` … `COUSR03.CPY` | `target → source` |
| `frontend/src/types/messages.ts` | `app/cpy/CSMSG01Y.cpy` + `app/cpy/CSMSG02Y.cpy` (`CCDA-MSG-*` literals, `PIC X(50)` trailing blanks preserved) | `target → source` |
| `frontend/src/types/titles.ts` | `app/cpy/COTTL01Y.cpy` (`CCDA-TITLE01` / `CCDA-TITLE02`) | `target → source` |
| `frontend/src/types/common.ts` | Shared wire scalars + the `ErrorResponse` envelope | `target → source` |
| `frontend/src/types/index.ts` | — Module barrel; no legacy analogue | `target → rule` |

### 21.5 Shared components and hooks

| Target (on disk) | Source construct | Direction |
|---|---|---|
| `frontend/src/main.tsx` | — SPA entry point; no legacy analogue (standalone-operation requirement) | `target → rule` |
| `frontend/src/App.tsx` | The CICS transaction table and `XCTL` transfer graph `[app/csd/CARDDEMO.CSD]`, plus the `SEC-USR-TYPE` role gate `[app/cbl/COSGN00C.cbl:L227-L237]` — expressed as the route table with `RequireAuth` / `RequireAdmin` | `target → source` |
| `frontend/src/components/Layout.tsx` | The 24×80 3270 frame and COMMAREA per-screen half (see §6 chrome row) | `target → source` |
| `frontend/src/components/Header.tsx` | Rows 1–2 of every mapset: `TRNNAME`, `PGMNAME`, `TITLE01`/`TITLE02`, `CURDATE`, `CURTIME`, and `COSGN00`'s `APPLID`/`SYSID` | `target → source` |
| `frontend/src/components/PFKeyBar.tsx` | `app/cpy/CSSTRPFY.cpy` + each mapset's row-24 legend field, including `ATTRB=DRK` fields (rendered as `dark`) | `target → source` |
| `frontend/src/components/ErrorBanner.tsx` | The row-23 `ERRMSG PIC X(78)` region and `MOVE DFHRED/DFHGREEN TO ERRMSGC`; its `isFieldInError` / `fieldErrorClass` / `fieldMarker` helpers reproduce `FLG-x-NOT-OK OR FLG-x-BLANK` and the blank-field `MOVE '*'` of `app/cpy/CSSETATY.cpy`; its `invalidFieldProps` / `invalidValueProps` helpers express each program's `MOVE -1 TO <field>L` fault target as `aria-invalid` — `invalidFieldProps` additionally points the faulted control at the row-23 region, `invalidValueProps` omits the reference for a row-action column that publishes no message | `target → source` |
| `frontend/src/components/OutputField.tsx` | `app/cpy/CSSETATY.cpy` `ATTRB=ASKIP` protected output fields — a term/value pair that carries an accessible name without being a tab stop | `target → source` |
| `frontend/src/components/display.ts` | Shared display/parse helpers for the string wire formats (`NUMERIC(p,s)` money, `PIC X(10)` dates) | `target → source` |
| `frontend/src/hooks/useApi.ts` | The CICS request/response turnaround: one in-flight task per terminal, with abort-and-generation handling replacing task cancellation | `target → source` |
| `frontend/src/hooks/useSession.ts` | `app/cpy/COCOM01Y.cpy` COMMAREA identity half + `COSGN00C` sign-on/sign-off lifecycle | `target → source` |
| `frontend/src/hooks/useScreenFocus.ts` | `ATTRB=IC` insert-cursor placement and every `MOVE -1 TO <field>L` cursor override, honoured on each send | `target → source` |
| `frontend/src/hooks/index.ts` | — Module barrel; no legacy analogue | `target → rule` |
| `frontend/src/index.css` | The 24×80 character grid, `DFHBLUE`/`DFHGREEN`/`DFHRED`/`DFHTURQ`/`DFHYELLO`/`DFHNEUTR` attribute colours, and per-mapset field geometry | `target → source` |
| `frontend/src/setupTests.ts` | — Jest environment shim (`TextEncoder`/`TextDecoder` for the router's ESM build); no legacy analogue, recorded in the decision log | `target → rule` |
| `frontend/eslint.config.js` | — Mandatory lint gate; no legacy analogue (tooling requirement) | `target → rule` |
| `frontend/src/pages/cardSelection.ts` | `CDEMO-CC00-CARD-SELECTED` + `CDEMO-CC00-ACCT-SELECTED` carried in the COMMAREA between `COCRDLIC` and `COCRDSLC`/`COCRDUPC` `[app/cpy/COCOM01Y.cpy]` — the composite hand-over, held in router location state so no card number reaches the address bar, history, a bookmark or a `Referer` header | `target → source` |
| `frontend/src/vite-env.d.ts` | — Build-tool ambient type declarations; no legacy analogue | `target → rule` |
| `frontend/src/__mocks__/fileMock.ts` | — Jest static-asset stub; no legacy analogue (test-harness requirement) | `target → rule` |

### 21.6 Backend controllers — reverse direction

| Target (on disk) | Source construct | Direction |
|---|---|---|
| `auth-service/.../AuthenticationController.java` | `app/cbl/COSGN00C.cbl` (`CC00`) | `target → source` |
| `auth-service/.../SessionController.java` | `app/cpy/COCOM01Y.cpy` COMMAREA identity probe — replaces client-held session authority | `target → source` |
| `auth-service/.../CsrfController.java` | — CSRF double-submit token issue; no legacy analogue (the 3270 link needed none) | `target → rule` |
| `api-gateway/.../MenuController.java` | `app/cbl/COMEN01C.cbl` + `app/cbl/COADM01C.cbl` | `target → source` |
| `account-service/.../AccountController.java` | `app/cbl/COACTVWC.cbl` + `app/cbl/COACTUPC.cbl` | `target → source` |
| `card-service/.../CardController.java` | `app/cbl/COCRDLIC.cbl`, `COCRDSLC.cbl`, `COCRDUPC.cbl` | `target → source` |
| `transaction-service/.../TransactionController.java` | `app/cbl/COTRN00C.cbl`, `COTRN01C.cbl`, `COTRN02C.cbl` | `target → source` |
| `billpay-service/.../BillPaymentController.java` | `app/cbl/COBIL00C.cbl` | `target → source` |
| `reporting-service/.../ReportController.java` | `app/cbl/CORPT00C.cbl` | `target → source` |
| `user-service/.../UserController.java` | `app/cbl/COUSR00C.cbl` … `COUSR03C.cbl` | `target → source` |
| `batch-service/.../PostingJobController.java` | `app/jcl/POSTTRAN.jcl` + `app/cbl/CBTRN02C.cbl` | `target → source` |
| `batch-service/.../BatchController.java` | `app/jcl/INTCALC.jcl`, `CREASTMT.jcl` and the TDQ `'JOBS'`-to-JES submission | `target → source` |
| `carddemo-common/.../CardDemoErrorController.java` | `RESP(DFHRESP(...))` status handling → the shared `ErrorResponse` envelope | `target → source` |
| `carddemo-common/.../json/CobolWireFormat.java`, `CobolNumberSerializers.java` | COMP-3 packed-decimal scale and fixed `PIC 9(n)` field widths `[app/cpy/CVACT01Y.cpy:L4-L17]` — serialize every numeric wire value as a string so scale and width survive JSON | `target → source` |

### 21.7 Frontend test suites — reverse direction

| Target (on disk) | What it pins | Direction |
|---|---|---|
| `frontend/src/api/client.test.ts` | CSRF echo, correlation-id propagation, `401`/`403` session expiry and the `409` optimistic-lock message | `target → source` |
| `frontend/src/api/wireContract.test.ts` | The string wire contract for money, ids and dates | `target → source` |
| `frontend/src/types/__tests__/contract.test.ts` | Symbolic-map field contracts | `target → source` |
| `frontend/src/components/Layout.test.tsx` | Frame geometry, chrome lifecycle across a route change and a page's own cleanup, the unspaced `Tran:` caption family | `target → source` |
| `frontend/src/components/Header.test.tsx` | Per-map header captions and the `COTTL01Y` titles | `target → source` |
| `frontend/src/components/PFKeyBar.test.tsx` | Row-24 legend rendering, AID dispatch, `ATTRB=DRK` dark keys, and default cancellation for every declared AID | `target → source` |
| `frontend/src/components/ErrorBanner.test.tsx` | Row-23 message region and its colour attribute | `target → source` |
| `frontend/src/hooks/useApi.test.ts` | Request generation and abort handling | `target → source` |
| `frontend/src/hooks/useSession.test.ts` | Sign-on/sign-off lifecycle and credential pass-through | `target → source` |
| `frontend/src/hooks/useScreenFocus.test.tsx` | `ATTRB=IC` placement on every send, including after a disabled control is re-enabled | `target → source` |
| `frontend/src/pages/programRoutes.test.ts` | `XCTL` program-to-route resolution | `target → source` |
| `frontend/src/pages/tranAddFormat.test.ts` | `PIC +99999999.99` and `PIC X(10)` field edits | `target → source` |
## 22. Sign-On Screen Parity Suite (`COSGN00` / `CC00` / `COSGN00C`) — Present on Disk

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COSGN00` mapset body and legend — the `POS=(17,16)` prompt `Type your User ID and Password, then press ENTER:`, `USERID` `LENGTH=8`, `PASSWD` `ATTRB=(DRK,FSET,UNPROT)` `LENGTH=8`, the two `(8 Char)` hints, `ERRMSG` `X(78)` at line 23 and the line-24 legend `ENTER=Sign-on  F3=Exit` `[app/bms/COSGN00.bms:L145-L205]` | `frontend/src/pages/SignonPage.test.tsx` — "COSGN00 map rendering" group (7 scenarios: prompt, both fields with `maxlength` 8, masked password, both width hints, submit control, enabled legend keys inside the `Function keys` toolbar, transaction id / program name / titles chrome, empty initial message region) | `source → target` |
| `COSGN00.CPY` symbolic-map widths `USERIDI PIC X(8)` and `PASSWDI PIC X(8)` `[app/cpy-bms/COSGN00.CPY:L72, L78]` | "entry-field behavior" group (4 scenarios) — both entry fields bounded to 8 characters with over-length entry truncated, and the password retained verbatim | `source → target` |
| `COSGN00C` `PROCESS-ENTER-KEY` `EVALUATE TRUE` edit order emitting exactly one message — `Please enter User ID ...` then `Please enter Password ...` `[app/cbl/COSGN00C.cbl:L117-L131]` | "required-field validation" group (5 scenarios) — both verbatim texts, whitespace-only user id treated as blank, user-id message winning when both are blank, message cleared on a later success, and zero `signon` calls on every rejection | `source → target` |
| `COSGN00C` `MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI)` with the password left un-folded on the wire `[app/cbl/COSGN00C.cbl:L132-L136]` | The upper-cases-as-typed scenario plus the request-payload assertion `{ userId: 'ADMIN001', password: 'fakePw01' }`, locking the decision-logged case-sensitivity deviation | `source → target` |
| `COSGN00C` success routing — `MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE` then `XCTL PROGRAM('COADM01C')` / `PROGRAM('COMEN01C')` `[app/cbl/COSGN00C.cbl:L227-L238]` | "successful sign-on routing" group (6 scenarios) — role `'A'` resolving `/admin`, role `'U'` resolving `/menu`, both branches re-driven with the raw verbatim wire codes, exactly one call, and the established session surfaced on the frame | `source → target` |
| `COSGN00C` `READ-USER-SEC-FILE` outcomes — `Wrong Password. Try again ...`, `User not found. Try again ...` (RESP 13) and the `WHEN OTHER` `Unable to verify the User ...` `[app/cbl/COSGN00C.cbl:L219-L257]` | "rejected sign-on" group (5 scenarios) — both verbatim `401` texts, the fallback for a bodyless `401` and for a non-`ApiError` failure, and staying signed out on the sign-on route with the entries retained | `source → target` |
| `COSGN00C` `EVALUATE EIBAID` `WHEN DFHENTER` / `WHEN DFHPF3` AID dispatch `[app/cbl/COSGN00C.cbl:L85-L94]` plus PF-key legend semantics `[app/cpy/CSSTRPFY.cpy]` | "PF-key wiring" group (7 scenarios) driving the real `PFKeyBar` document keydown listener — ENTER AID, implicit form submission, `ENTER=Sign-on` activation, PF3 AID clearing the screen, `F3=Exit` activation, sign-out of an established session, and an undeclared key ignored | `source → target` |
| `PASSWD` non-display attribute `ATTRB=(DRK, ...)` `[app/bms/COSGN00.bms:L175-L180]` | "password confidentiality" group (3 scenarios) — the field stays `type="password"`, the value never appears in rendered text, never in any `console` call, and never in the persisted session blob | `source → target` |
| `frontend/src/pages/SignonPage.test.tsx` `jest.unstable_mockModule('../api')` with `beforeAll` dynamic imports | (no legacy analogue — native-ESM test isolation keeping axios and the Vite `import.meta.env` read out of the suite; see decision log §19) | `target → rule` |
| `frontend/src/pages/SignonPage.test.tsx` `MemoryRouter` with `useLocation` probe routes | (no legacy analogue — CICS `XCTL` program transfer has no client-side router transition to observe; see decision log §19) | `target → rule` |
## 23. Main-Menu Screen Suite (`MainMenuPage.test.tsx` checkpoint) — Present on Disk

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COMEN01` mapset body — the `Main Menu` heading, the twelve option slots `OPTN001`..`OPTN012` `PIC X(40)`, the `Please select an option :` prompt, the `OPTION` field `ATTRB=(FSET,IC,NORM,NUM,UNPROT) LENGTH=2`, the line-23 `ERRMSG PIC X(78)` and the line-24 legend `ENTER=Continue  F3=Exit` `[app/bms/COMEN01.bms:L75-L162; app/cpy-bms/COMEN01.CPY]` | `frontend/src/pages/MainMenuPage.test.tsx` — the heading, the option lines, the twelve-slot truncation, the two-character numeric `OPTION` field and the two legend buttons are each asserted on the rendered screen | `source → target` |
| `COMEN01C` `PROCESS-ENTER-KEY` — the invalid / zero / out-of-range check raising `Please enter a valid option number...`, the `CDEMO-USRTYP-USER` + USRTYPE `'A'` check raising `No access - Admin Only option... `, the `XCTL` to `CDEMO-MENU-OPT-PGMNAME`, and `RETURN-TO-SIGNON-SCREEN` `[app/cbl/COMEN01C.cbl:L118-L177]` | `MainMenuPage.test.tsx` — both literals asserted character-for-character (raw `textContent`, so the admin literal's trailing blank is verified), the ten option → route navigations asserted through a router location probe, and PF3 asserted to clear the session and reach `/signon` | `source → target` |
| `CDEMO-MENU-OPTIONS` — ten rows, `CDEMO-MENU-OPT-COUNT 10`, `OCCURS 12`, names `PIC X(35)`, every row USRTYPE `'U'` `[app/cpy/COMEN02Y.cpy]` | `MainMenuPage.test.tsx` fixtures — the ten rows in copybook order with wire-shaped padded labels, a role-filtered subset, a withheld-option refusal, and the negative assertion that no standard-user option is refused as admin-only | `source → target` |
| Jest native-ESM module mocking and the `CM00` menu wire contract — rule-mandated test infrastructure, no legacy analogue | `MainMenuPage.test.tsx` — `jest.unstable_mockModule('../api', …)` with a passthrough-shaped `ApiError`, the `__setSession` session seam, and the real `Layout` shell, delivering 27 of the scenarios AAP 0.7.1 requires without evaluating the Vite build-time environment | `target → rule` |
## 24. Admin-Menu Screen Test Suite (`AdminMenuPage.test.tsx`) — Present on Disk

Targets delivered with the colocated admin-menu suite. Each row names the legacy construct or
mandate it verifies, so the reverse direction stays complete; the rationale for every choice is in
`docs/decision-log.md` §19.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `frontend/src/pages/AdminMenuPage.test.tsx` — fourteen cases over the admin-menu workflow (load, option entry, four-route dispatch, rejection, PF legend) | `COADM01` mapset + `COADM01` symbolic map + `COADM01C` under CICS `CA00`, and AAP §0.7.1 "at least 50 unit-test scenarios must pass" / "all 17 UI workflows must behave identically" `[app/bms/COADM01.bms; app/cpy-bms/COADM01.CPY; app/cbl/COADM01C.cbl]` | `source → target` |
| `AdminMenuPage.test.tsx` heading and option-slot cases — level-3 `Admin Menu` plus one row per served option as `<num>. <name>` | Row-4 `DFHMDF INITIAL='Admin Menu'` and the `OPTN001`-`OPTN012` `X(40)` slots filled by `BUILD-MENU-OPTIONS` `[app/bms/COADM01.bms:L75-L139; app/cbl/COADM01C.cbl:L226-L250]` | `source → target` |
| `AdminMenuPage.test.tsx` `ADMIN_MENU_RESPONSE` fixture — `CA00` / `COADM01C` and the four `PIC X(35)` space-padded option names mapped to `COUSR00C`-`COUSR03C` | `CDEMO-ADMIN-OPT-COUNT` = 4 and `CDEMO-ADMIN-OPTIONS-DATA` `[app/cpy/COADM02Y.cpy:L20-L42]` | `source → target` |
| `AdminMenuPage.test.tsx` `OPTION`-field case — `maxLength` 2, `inputMode` numeric, digits-only entry | `OPTION DFHMDF ATTRB=(…,NUM,UNPROT) LENGTH=2` and `OPTIONI PIC X(2)` `[app/bms/COADM01.bms:L145-L149; app/cpy-bms/COADM01.CPY:L132]` | `source → target` |
| `AdminMenuPage.test.tsx` four dispatch cases — `/users`, `/users/add`, `/users/update`, `/users/delete` asserted through the navigate spy | `PROCESS-ENTER-KEY` `XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))` `[app/cbl/COADM01C.cbl:L138-L145]` | `source → target` |
| `AdminMenuPage.test.tsx` three rejection cases — out-of-range, zeros and blank each yield exactly `Please enter a valid option number...` in the line-23 region with no navigation | `IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR WS-OPTION = ZEROS` and its frozen `WS-MESSAGE` literal, shown through `ERRMSG` `X(78)` `[app/cbl/COADM01C.cbl:L127-L134; app/bms/COADM01.bms:L154-L157]` | `source → target` |
| `AdminMenuPage.test.tsx` PF-legend cases — exactly the `ENTER=Continue` and `F3=Exit` buttons, with the F3 button and the physical F3 key both reaching `/menu` | Line-24 `DFHMDF INITIAL='ENTER=Continue  F3=Exit'` and the `EVALUATE EIBAID` `DFHENTER` / `DFHPF3` branches `[app/bms/COADM01.bms:L158-L162; app/cbl/COADM01C.cbl:L93-L103]` | `source → target` |
| `AdminMenuPage.test.tsx` `jest.unstable_mockModule` registrations for `../api` and `react-router-dom`, and the `ApiError` passthrough case | No legacy analogue — native-ESM test isolation for the AAP §0.2.1 Jest + React Testing Library deliverable `[frontend/package.json jest.preset]` | `target → rule` |

## 25. Card-Detail Screen Test Suite — Present-on-Disk Target

The Jest + React Testing Library suite for the card-detail workflow. Every case names the legacy
construct or rule it verifies, so the reverse direction stays complete for this artifact too; the
rationale for each choice is in `docs/decision-log.md` §19.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `frontend/src/pages/CardDetailPage.test.tsx` (19 cases) | `COCRDSL` mapset + `CCRDSLA` symbolic map + `COCRDSLC` screen behavior, verified through `CardDetailPage` `[app/bms/COCRDSL.bms; app/cpy-bms/COCRDSL.CPY; app/cbl/COCRDSLC.cbl]` | `source → target` |
| Load-by-route-parameter cases — `getCard` receives the route `cardNumber` as a 16-character `string`, and `CCDL` / `COCRDSLC` reach the shared header | `9100-GETCARD-BYACCTCARD` single-record read keyed on `CC-CARD-NUM`, plus `LIT-THISTRANID` / `LIT-THISPGM` `[app/cbl/COCRDSLC.cbl:L162-L170]` | `source → target` |
| Field-width cases — `ACCTSID` `maxLength` 11, `CARDSID` `maxLength` 16, `CRDNAME` clipped to 50, `CRDSTCD` clipped to 1 | `ACCTSIDI PIC X(11)`, `CARDSIDI PIC X(16)`, `CRDNAMEI PIC X(50)`, `CRDSTCDI PIC X(1)` `[app/cpy-bms/COCRDSL.CPY:L60-L78; app/bms/COCRDSL.bms:L84-L119]` | `source → target` |
| Caption cases asserted with an identity normalizer — `Account Number    :` / `Card Number       :` / `Name on card      :` / `Card Active Y/N   :` / `Expiry Date       :` | The TURQUOISE `DFHMDF INITIAL` literals on lines 7, 8, 11, 13, 15 `[app/bms/COCRDSL.bms:L79-L125]` | `source → target` |
| Expiry cases — `MM/YYYY` rendered around the mapset separator, driven by the DTO member `cardExpiraionDate` (misspelling preserved) | `CARD-EXPIRAION-DATE-X` redefined by `CARD-EXPIRY-YEAR` / `CARD-EXPIRY-MONTH`, moved to `EXPMONO` / `EXPYEARO` around the `INITIAL='/'` field `[app/cbl/COCRDSLC.cbl:L84-L92; app/bms/COCRDSL.bms:L126-L136]` | `source → target` |
| Filter-edit cases — `Account number must be a non zero 11 digit number`, `Card number if supplied must be a 16 digit number`, and the account-before-card first-message-wins order | The `WS-RETURN-MSG` 88-levels `SEARCHED-ACCT-ZEROES` / `SEARCHED-ACCT-NOT-NUMERIC` / `SEARCHED-CARD-NOT-NUMERIC` and the `2210-EDIT-ACCOUNT` → `2220-EDIT-CARD` sequence `[app/cbl/COCRDSLC.cbl:L143-L149, L646-L720]` | `source → target` |
| Function-key cases — exactly two legend entries `ENTER=Search Cards` and `F3=Exit`, each wired to both its button and its physical AID key | The line-24 `FKEYS` literal `ENTER=Search Cards  F3=Exit` and the `CSSTRPFY` AID mapping `[app/bms/COCRDSL.bms:L148-L152; app/cpy/CSSTRPFY.cpy]` | `source → target` |
| Failed-read cases — the service `ApiError` message surfaced verbatim, blank display fields, and a plain `Error` normalized through the passthrough `ApiError` | `DID-NOT-FIND-ACCTCARD-COMBO` / `XREF-READ-ERROR` `WS-RETURN-MSG` values moved to `ERRMSGO`, and the unfilled-field behavior when `FOUND-CARDS-FOR-ACCOUNT` is false `[app/cbl/COCRDSLC.cbl:L150-L157, L474-L494]` | `source → target` |
| Card fixtures `0500024453765740` / `Aniya Von` / `2023-03-09` and `0683586198171516` / `Ward Jones` / `2025-07-13` | Rows 1-2 of the legacy ASCII seed, joined through the `CXACAIX` cross-reference `[app/data/ASCII/carddata.txt; app/data/ASCII/cardxref.txt]` | `source → target` |
| `jest.unstable_mockModule('../api', …)` harness with a passthrough `ApiError` and no `import.meta` evaluation | AAP §0.7.1 "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically" — rule-mandated test infrastructure with no legacy analogue | `target → rule` |
## 26. Card-Update Screen Test Suite (`CardUpdatePage.test.tsx`) — Present on Disk

The colocated Jest / React Testing Library suite for the card-update screen. Each row names the
legacy construct the cases assert, so the reverse direction stays complete for the test artifact
too; the rationale is in `docs/decision-log.md` §19.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COCRDUP` mapset field grid and the `CCRDUPAI` symbolic-map widths — `ACCTSID` `X(11)` and `CARDSID` `X(16)` protected, `CRDNAME` `X(50)`, `CRDSTCD` `X(01)`, `EXPMON` `X(02)`, `EXPYEAR` `X(04)`, plus the padded captions `Account Number    :` / `Card Number       :` / `Name on card      :` / `Card Active Y/N   :` / `Expiry Date       :` `[app/bms/COCRDUP.bms; app/cpy-bms/COCRDUP.CPY]` | `CardUpdatePage.test.tsx` — the entry-read cases asserting `readonly` on both keys, `maxlength` 11/16/50/1/2/4, the captions byte-exactly through their `label[for]` association, and that all four unprotected fields accept input | `source → target` |
| `COCRDUPC` edit literals `Card number if supplied must be a 16 digit number`, `Card Active Status must be Y or N`, `Card expiry month must be between 1 and 12`, `Invalid card expiry year`, with the `IF WS-RETURN-MSG-OFF` first-error-wins guard over the `1220`/`1240`/`1250`/`1260` edit order `[app/cbl/COCRDUPC.cbl:L194-L200]` | `CardUpdatePage.test.tsx` — the ten map-input edit cases, including the boundary values (month `13`, month `0`, blank month, year `1949`, non-numeric year), the non-16-digit route key that issues NO read, and the precedence case proving only the first failing edit publishes | `source → target` |
| `COCRDUPC` info-message state machine `FOUND-CARDS-FOR-ACCOUNT` / `PROMPT-FOR-CONFIRMATION` / `CONFIRM-UPDATE-SUCCESS` / `INFORM-FAILURE`, and `DATA-WAS-CHANGED-BEFORE-UPDATE` for the concurrent-change outcome `[app/cbl/COCRDUPC.cbl:L161-L171, L208]` | `CardUpdatePage.test.tsx` — the confirmation-prompt cases asserting `Changes validated.Press F5 to save` byte-exactly (no space after the period) and the rewrite cases asserting `Changes committed to database`, `Changes unsuccessful. Please try again` for both an `ApiError` and a transport failure, and `Record changed by some one else. Please review` from a 409 carrying a different raw message | `source → target` |
| `CARD-EXPIRAION-DATE-X` `X(10)` redefined year / month / day, with the day carried over unchanged because the map never presents it, and `JUSTIFY=(RIGHT)` one-or-two-digit `EXPMON` `[app/cbl/COCRDUPC.cbl:L115-L123, L1124-L1126]` | `CardUpdatePage.test.tsx` — the request-shape cases asserting `updateCard` receives the card number as a `string` and a `cardExpiraionDate` of `2023-03-09` untouched, or `2023-07-09` after the month is typed as `7`, together with the echoed optimistic-lock `version` | `source → target` |
| `COCRDUP` line-24 legend literals `ENTER=Process F3=Exit` (`FKEYS`) and `F5=Save F12=Cancel` (`FKEYSC`), and the `COCRDUPC` PF-key routing to the card list and back to the card detail `[app/bms/COCRDUP.bms; app/cpy/CSSTRPFY.cpy]` | `CardUpdatePage.test.tsx` — the function-key cases asserting the legend order `ENTER=Process` / `F3=Exit` / `F5=Save` / `F12=Cancel`, F12 to `/cards/{cardNumber}`, F3 to `/cards`, and the same four outcomes from the physical `Enter` / `F3` / `F5` / `F12` AID keys | `source → target` |
| AAP 0.7.1 — at least 50 unit-test scenarios pass and all 17 UI workflows behave identically; the Jest + React Testing Library frontend test mandate (AAP 0.2.1) | `frontend/src/pages/CardUpdatePage.test.tsx` — 37 cases across five groups, mocking `../api` (`getCard`, `updateCard`, passthrough `ApiError`) so no network, axios or Vite `import.meta` is evaluated | `target → rule` |
| `app/data/ASCII/carddata.txt` row 1 and its `app/data/ASCII/cardxref.txt` cross-reference row — card `0500024453765740`, account `00000000050`, customer `000000050`, `Aniya Von`, expiry `2023-03-09`, status `Y` | `CardUpdatePage.test.tsx` fixture `cardRecord` / `updatedRecord`, so the suite exercises the same values the Flyway seed loads | `source → target` |

Section 21 records the test artifact for a construct already enumerated in the forward direction
(section 20); it refines coverage of that construct rather than adding a new legacy source, so the
forward enumeration remains one row per legacy construct.
## 27. Add-Transaction Screen Test Suite (`TranAddPage.test.tsx` checkpoint) — Present on Disk

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COTRN02` mapset body — the `COTRN2A` caption grid (`Enter Acct #:`, `(or)`, `Card #:`, the `X(70)` rule, `Type CD:`, `Category CD:`, `Source:`, `Description:`, `Amount:`, `Orig Date:`, `Proc Date:`, `Merchant ID:`, `Merchant Name:`, `Merchant City:`, `Merchant Zip:`) and the `COTRN2AI` field widths `ACTIDINI X(11)` / `CARDNINI X(16)` / `TTYPCDI X(2)` / `TCATCDI X(4)` / `TRNSRCI X(10)` / `TDESCI X(60)` / `TRNAMTI X(12)` / `TORIGDTI X(10)` / `TPROCDTI X(10)` / `MIDI X(9)` / `MNAMEI X(30)` / `MCITYI X(25)` / `MZIPI X(10)` / `CONFIRMI X(1)` `[app/bms/COTRN02.bms; app/cpy-bms/COTRN02.CPY]` | `frontend/src/pages/TranAddPage.test.tsx` — the `LABELS` table resolves all fourteen fields by their verbatim caption through `getByLabelText`, and `VALID_ENTRY` supplies a value of legal width for each in `COTRN2AI` order | `source → target` |
| BMS line-15 hints `(-99999999.99)` at `POS=(15,13)` and `(YYYY-MM-DD)` at `POS=(15,41)` and `POS=(15,67)` `[app/bms/COTRN02.bms:L208-L222]` | `TranAddPage.test.tsx` — `getByText('(-99999999.99)')`, `getAllByText('(YYYY-MM-DD)')` asserted at length two, and `toHaveAccessibleDescription` binding each hint to its own field through `aria-describedby` | `source → target` |
| `COTRN02C` `VALIDATE-INPUT-KEY-FIELDS` / `VALIDATE-INPUT-DATA-FIELDS` first-failure-wins edit order and its literals `Account or Card Number must be entered...`, `Category CD can NOT be empty...`, `Description can NOT be empty...`, `Amount can NOT be empty...`, `Merchant City can NOT be empty...` `[app/cbl/COTRN02C.cbl:L193-L232, L235-L320]` | `TranAddPage.test.tsx` `REQUIRED_FIELD_CASES` — five cases that fill the leading `0` / `2` / `4` / `5` / `10` fields to land on each stop point, comparing the line-23 region on `textContent` character-for-character and asserting that no request is issued | `source → target` |
| `COTRN02C` `PROCESS-ENTER-KEY` `EVALUATE CONFIRMI` — `'Y'`/`'y'` adds, `'N'`/`'n'`/`SPACES`/`LOW-VALUES` publishes `Confirm to add this transaction...`, `WHEN OTHER` publishes `Invalid value. Valid values are (Y/N)...` `[app/cbl/COTRN02C.cbl:L168-L187]` | `TranAddPage.test.tsx` "(Y/N) confirmation gate" and "cancelling the add" — the two-press flow, `y` forwarded verbatim, `X` rejected with the invalid literal, `N` and `n` cancelling without a request while the entered fields stay on screen | `source → target` |
| BMS line-21 confirmation caption `You are about to add this transaction. Please confirm :` with its `CONFIRM` `X(1)` field and `(Y/N)` legend `[app/bms/COTRN02.bms:L275-L292]` | `TranAddPage.test.tsx` — the caption is the `LABELS.confirm` accessor for the field, and `(Y/N)` is asserted as the field's accessible description | `source → target` |
| `COTRN02C` `ADD-TRANSACTION` id assignment — `MOVE HIGH-VALUES TO TRAN-ID` / `STARTBR` / `READPREV` / `ENDBR` / `ADD 1 TO WS-TRAN-ID-N` `[app/cbl/COTRN02C.cbl:L444-L451; AAP 0.6.5]` | `TranAddPage.test.tsx` "server-generated transaction id" — the posted request is asserted to carry NO `tranId` (`not.toHaveProperty`, an `Object.keys` check and `toStrictEqual`), and the compile-time guards on `keyof TranAddRequestDto` / `keyof TranAddResponseDto` fail `tsc` if the id ever moves back to the client | `source → target` |
| `COTRN02C` success `STRING 'Transaction added successfully. '` + `' Your Tran ID is '` + `TRAN-ID` + `'.'`, followed by `INITIALIZE-ALL-FIELDS` `[app/cbl/COTRN02C.cbl:L723-L733, L762]` | `TranAddPage.test.tsx` — the server-assigned `0000000000000123` surfaced as `Transaction added successfully.  Your Tran ID is 0000000000000123.` (double space preserved, compared on `textContent`) with every field asserted empty afterwards | `source → target` |
| `COTRN02C` `WRITE-TRANSACT-FILE` failure paths (`Unable to Add Transaction...`) reached through `RESP` checks `[app/cbl/COTRN02C.cbl:L711, L736-L746]` | `TranAddPage.test.tsx` — a rejected `addTransaction` carrying an `ApiError` body message is asserted on the line-23 error region while the entered values remain on screen | `source → target` |
| `COTRN02C` `TRAN-AMT` handling — `FUNCTION NUMVAL-C` into `PIC S9(09)V99` and the `X(10)` date portions moved into `TORIGDTI` / `TPROCDTI` `[app/cbl/COTRN02C.cbl:L442-L466; app/cpy/CVTRA05Y.cpy]` | `TranAddPage.test.tsx` — `tranAmt` asserted as the STRING `'-00000100.00'` with an explicit `typeof` check, and both timestamps asserted as `YYYY-MM-DD` strings, so the `NUMERIC(11,2)` scale can never be coerced to a `number` | `source → target` |
| BMS line-24 legend and `COTRN02C` `RETURN-TO-PREV-SCREEN` / `CLEAR-CURRENT-SCREEN` AID handling `[app/bms/COTRN02.bms:L297-L302; app/cbl/COTRN02C.cbl:L133-L147, L500, L754]` | `TranAddPage.test.tsx` "line-24 function keys" — exactly three buttons `ENTER=Add`, `F3=Exit`, `F4=Clear`; the F3 button and the physical `F3` key both reach `/transactions`; F4 empties every field and the line-23 region; the physical `Enter` key drives the confirmed add | `source → target` |
| CICS `SEND MAP` header fields `TRNNAME` / `PGMNAME` / `TITLE01` / `TITLE02` `[app/bms/COTRN02.bms:L34-L64]` | `TranAddPage.test.tsx` — chrome assertions on the shell test ids `tran-id` (`CT02`), `pgm-name` (`COTRN02C`), `title01` (`CardDemo`) and `title02` (`Add Transaction`) | `source → target` |
| Native-ESM Jest test-runtime gap (a body-level `jest.mock` cannot intercept a linked ES-module import, and the real `../api` barrel evaluates `import.meta`) — rule-mandated test infrastructure, no legacy analogue | `TranAddPage.test.tsx` — `jest.unstable_mockModule('../api', …)` with a `beforeAll` dynamic import, a structural `ApiError` passed through the synthetic namespace, and `signon` exported to satisfy ESM linking (decision log Section 19) | `target → rule` |
## 28. Bill-Payment Screen Test Suite (`BillPayPage.test.tsx` checkpoint) — Present on Disk

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COBIL00` symbolic-map field widths — `ACTIDIN` `X(11)` unprotected with the `IC` cursor, `CURBAL` `X(14)` `ASKIP`, `CONFIRM` `X(01)` unprotected, and the captions `Enter Acct ID:` / `Your current balance is: ` / `Do you want to pay your balance now. Please confirm: ` / `(Y/N)` `[app/bms/COBIL00.bms:L80-L126; app/cpy-bms/COBIL00.CPY:L55-L72]` | `frontend/src/pages/BillPayPage.test.tsx` — "screen fields and widths": `maxLength=11` / `size=11` on `ACTIDIN`, `readonly` + `tabindex=-1` + `size=14` on `CURBAL`, `maxLength=1` on `CONFIRM`, each resolved through its `label[for]`, plus the `(Y/N)` legend | `source → target` |
| `COBIL00C` `PROCESS-ENTER-KEY` empty-id guard `WHEN ACTIDINI OF COBIL0AI = SPACES OR LOW-VALUES` with `Acct ID can NOT be empty...` and the cursor returned to `ACTIDINL` `[app/cbl/COBIL00C.cbl:L157-L165]` | `BillPayPage.test.tsx` — "account-id validation": the blank and the all-spaces entries both surface the literal by `textContent` equality, `payBill` is never called, and focus returns to the account-id field | `source → target` |
| `COBIL00C` confirm dispatch `EVALUATE CONFIRMI` — `WHEN 'Y' WHEN 'y'` pays, `WHEN 'N' WHEN 'n'` performs `CLEAR-CURRENT-SCREEN`, `WHEN SPACES`/`LOW-VALUES` reads the account for the balance preview, `WHEN OTHER` raises `Invalid value. Valid values are (Y/N)...` `[app/cbl/COBIL00C.cbl:L167-L189]` | `BillPayPage.test.tsx` — "confirmation gate" and "cancellation": blank confirm issues `payBill({accountId, confirm: ''})` and shows the balance; `Y` and `y` post the payment; `N` and `n` blank the screen without any call; `X` surfaces the invalid-value literal without any call | `source → target` |
| `COBIL00C` confirm prompt `Confirm to make a bill payment...` with the cursor moved to `CONFIRML`, and the balance moved into `CURBALI` `[app/cbl/COBIL00C.cbl:L192-L193, L235-L239]` | `BillPayPage.test.tsx` — the first ENTER asserts the prompt on line 23, the server balance string in `CURBAL` (`1234.56`, and `1000.00` byte-for-byte to prove money is never re-formatted), and focus on the confirm field | `source → target` |
| `COBIL00C` `WRITE-TRANSACT-FILE` `DFHRESP(NORMAL)` branch — `INITIALIZE-ALL-FIELDS` then `STRING 'Payment successful. ' ' Your Transaction ID is ' TRAN-ID '.'` `[app/cbl/COBIL00C.cbl:L521-L533]` | `BillPayPage.test.tsx` — "successful payment": `payBill` called once with `confirm: 'Y'`, the banner asserted byte-exactly (raw `textContent`, `startsWith('Payment successful. ')`, two-space substring) both for the server-supplied message and for the page-composed fallback, and every entry field blank afterwards | `source → target` |
| `COBIL00C` `WRITE-TRANSACT-FILE` `DFHRESP(DUPKEY)`/`DFHRESP(DUPREC)` branch — `Tran ID already exist...` `[app/cbl/COBIL00C.cbl:L534-L540]` | `BillPayPage.test.tsx` — "duplicate transaction id": a rejected `ApiError` carrying the `billpay-service` body message surfaces the literal on line 23 and no success banner is rendered | `source → target` |
| `COBIL00C` `READ-ACCTDAT-FILE` / `READ-CXACAIX-FILE` `NOTFND` branches — `Account ID NOT found...` `[app/cbl/COBIL00C.cbl:L355-L365, L419-L427]` | `BillPayPage.test.tsx` — a rejected `ApiError` with HTTP `404` and the standardized body surfaces the literal verbatim while `CURBAL` stays blank | `source → target` |
| `COBIL00.bms` line-24 `FKEYS` literal `ENTER=Continue  F3=Back  F4=Clear` (`LENGTH=33`) and `COBIL00C` `RETURN-TO-PREV-SCREEN` (`XCTL` to `COMEN01C`) / `CLEAR-CURRENT-SCREEN` `[app/bms/COBIL00.bms:L131-L135; app/cbl/COBIL00C.cbl:L118-L131, L549-L553]` | `BillPayPage.test.tsx` — "line-24 function keys": the three captions asserted in mapset order inside the `Function keys` toolbar, F3 by click and by physical `F3` keydown both landing on the `/menu` route, F4 blanking every field, and the physical `Enter` key submitting | `source → target` |
| `COCOM01Y` COMMAREA identity fields `CDEMO-USER-ID` / `CDEMO-USER-TYPE` (`88 CDEMO-USRTYP-USER VALUE 'U'`) carried into the screen `[app/cpy/COCOM01Y.cpy:L18-L30]` | `BillPayPage.test.tsx` — the suite seeds the externalized session with `__setSession('USER0001', 'U')` before every render and asserts the shell's `data-authenticated` reflects it | `source → target` |
| `COBIL00C` `LIT-THISTRANID` `CB00` / `LIT-THISPGM` `COBIL00C` and the `TITLE01` / `TITLE02` header lines `[app/cbl/COBIL00C.cbl; app/bms/COBIL00.bms:L34-L79]` | `BillPayPage.test.tsx` — the published chrome asserted as `CB00`, `COBIL00C`, `CardDemo`, `Bill Payment`, with the line-23 region empty on entry | `source → target` |
| Native-ESM Jest module-mocking gap (`jest.mock` is inert under `--experimental-vm-modules`) — rule-mandated test infrastructure, no legacy analogue | `BillPayPage.test.tsx` — `jest.unstable_mockModule('../api', …)` with the mocked graph bound by dynamic `await import()` in `beforeAll`, so `payBill` is a jest mock, `ApiError` is the constructor every consumer sees, and `api/config.ts`'s `import.meta` is never evaluated | `target → rule` |
## 29. Report-Request Screen Test Suite — Present-on-Disk Target

The report-request screen's verification surface. The suite is rule-mandated test infrastructure, so
each row names the legacy construct or rule it serves and the reverse direction stays complete; the
## 30. User-List Screen Suite (`UserListPage.test.tsx`) — Present on Disk

Targets delivered with the colocated suite for the administrator user-list screen. Each row names
the legacy construct or rule the assertion serves, so the reverse direction stays complete; the
rationale for every choice is in `docs/decision-log.md` §19.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `frontend/src/pages/ReportPage.test.tsx` — 49 cases over six groups: screen and report-window selection (including the two `(MM/DD/YYYY)` hints and the Custom reveal), the report-window-required edit, the six emptiness edits, the six numeric/range plus two calendar edits, the confirmation gate, the asynchronous `POST /reports` launch, and the line-24 keys | AAP 0.7.1 "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically" — the CORPT00 workflow of the seventeen. Every asserted literal is the mapset caption `[app/bms/CORPT00.bms:L75-226]` or the program message `[app/cbl/CORPT00C.cbl:L261-L490]`, compared byte-for-byte against the rendered `textContent` | `target → rule` |
| `ReportPage.test.tsx` — the `jest.unstable_mockModule('../api', …)` factory (mocked `requestReport` / `signon`, working `ApiError`) and the `jest.isMockFunction` harness guard | Rule-mandated test isolation, no legacy analogue — the suite must exercise the screen without the Vite build-time environment (`import.meta`) or a live axios client, under the project's native-ESM Jest runtime `[frontend/package.json; frontend/src/api/config.ts]` | `target → rule` |
| `ReportPage.test.tsx` — the confirmation-gate cases (blank prompt per window, non-`Y`/`N` quoted rejection, `N`/`n` cancellation, `Y`/`y` launch) and the in-flight ENTER lock | `CORPT00C` `SUBMIT-JOB-TO-INTRDR` confirmation gate and its TDQ `'JOBS'` write, reached once per confirmed request `[app/cbl/CORPT00C.cbl:L462-L497]` | `source → target` |
| `frontend/src/pages/UserListPage.test.tsx` — 28 cases over the `COUSR00` browse, colocated with the screen | `COUSR00` mapset + `COUSR0AI` symbolic map + `COUSR00C` browse, paging and selection logic `[app/bms/COUSR00.bms; app/cpy-bms/COUSR00.CPY; app/cbl/COUSR00C.cbl]`; AAP 0.7.1 "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically" | `source → target` |
| Ten-rows-per-page assertion — exactly 10 data rows rendered from a 23-row response, checked against the literal `10` and against the `USER_LIST_PAGE_SIZE` constant | `USER-REC OCCURS 10 TIMES` fixing the user list at ten rows, and the `SEL0001`/`USRID01` .. `SEL0010`/`USRID10` row fields of the mapset `[app/cbl/COUSR00C.cbl:L57; app/bms/COUSR00.bms:L153-L438]` | `source → target` |
| Row-select field naming assertion — each displayed row's input carries `id` and `name` `SEL0001`..`SEL0010` with `maxlength` 1 | The ten `DFHMDF ATTRB=(FSET,NORM,UNPROT) LENGTH=1` select fields and their `SEL000nI PIC X(1)` symbolic-map members `[app/bms/COUSR00.bms:L153-L158; app/cpy-bms/COUSR00.CPY:L67-L72]` | `source → target` |
| Column-heading and filter assertions — headings exactly `Sel` / `User ID` / `First Name` / `Last Name` / `Type`, and `Search User ID:` bound to `USRIDIN` with `maxlength` 8 | The row-8 heading literals and the `USRIDIN` `LENGTH=8` search field `[app/bms/COUSR00.bms:L90-L127]`; `SEC-USR-ID X(08)` `[app/cpy/CSUSR01Y.cpy]` | `source → target` |
| PF7 / PF8 paging assertions — F8 advances to rows 11-20 and F7 returns, F7 reports `enabled: false` on page 1, F8 reports `enabled: false` on the final (three-row) page, and a server-reported further page re-requests `page: 2` | `PROCESS-PF7-KEY` / `PROCESS-PF8-KEY`, the `CDEMO-CU00-PAGE-NUM` counter and the `NEXT-PAGE-YES` / `NEXT-PAGE-NO` 88-levels that gate forward paging `[app/cbl/COUSR00C.cbl:L70-L73, L128-L131, L237-L327]` | `source → target` |
| Selection-validation assertion — any `Sel` value other than `U` / `D` publishes the verbatim line-23 text `Invalid selection. Valid values are U and D` and performs no navigation | The `WHEN OTHER` branch of the `CDEMO-CU00-USR-SEL-FLG` evaluate, which moves that literal to `WS-MESSAGE` `[app/cbl/COUSR00C.cbl:L210-L213]` | `source → target` |
| `U` / `D` navigation assertions — `/users/update` and `/users/delete` reached carrying router state `{ userId }`, with lower-case `u` / `d` accepted and a row selected on page 2 carrying its own id | `WHEN 'U'` / `WHEN 'u'` → `XCTL` `COUSR02C` and `WHEN 'D'` / `WHEN 'd'` → `XCTL` `COUSR03C`, with the selected id carried in the COMMAREA `[app/cbl/COUSR00C.cbl:L189-L210]` | `source → target` |
| Function-key assertions — the published legend is exactly `F3=Exit`, `F7=Backward`, `F8=Forward` with callable handlers, and F3 navigates to `/admin` | `EVALUATE EIBAID` mapping `DFHPF3` to `COADM01C`, `DFHPF7` to backward paging and `DFHPF8` to forward paging `[app/cbl/COUSR00C.cbl:L125-L131]`; PF-key legend semantics `[app/cpy/CSSTRPFY.cpy]` | `source → target` |
| Chrome-payload assertion — `transactionId` `CU00`, `programName` `COUSR00C`, titles `CardDemo` / `List Users` | `WS-TRANID` / `WS-PGMNAME` and the `List Users` row-4 title literal `[app/cbl/COUSR00C.cbl; app/bms/COUSR00.bms:L75-L79]` | `source → target` |
| Administrator-session assertion — the browse renders with the session seeded to role `'A'` and `isAdmin` true | `CDEMO-USER-TYPE` administrator gating, `88 CDEMO-USRTYP-ADMIN VALUE 'A'` `[app/cpy/COCOM01Y.cpy:L26-L30]`; the administrator-only `CU00` transaction `[app/csd/CARDDEMO.CSD]` | `source → target` |
| Failed-browse assertions — a real `ApiError` carrying a body publishes `error.body.message`, and one without a body publishes `error.message` | No legacy analogue for the HTTP error contract itself; it stands in for the `RESP` / file-status branches `COUSR00C` reports on line 23, and preserves the AAP 0.7.1 requirement that surfaced outcomes are never masked `[app/cbl/COUSR00C.cbl]` | `target → source (derived)` |
| `../api` and `../components/Layout` test doubles registered with `jest.unstable_mockModule`, and the chrome-capture spy that makes the published legend and message assertable | No legacy analogue — Jest native-ESM test infrastructure required to exercise the screen without a network call, matching the `jest.unstable_mockModule` pattern already used by `frontend/src/hooks/useSession.test.ts` (decision log §19) | `target → rule` |
| Route-recorder navigation spy over `MemoryRouter` / `Routes`, driven by the real `useNavigate` / `useLocation` | No legacy analogue — asserts the SPA replacement for `XCTL` program transfer, including the row identity the COMMAREA used to carry (decision log §19) `[app/cpy/COCOM01Y.cpy:L18-L45]` | `target → rule` |

## 31. Add-User Screen Suite (`UserAddPage.test.tsx`) — Present on Disk

Reverse rows for the colocated add-user suite, so the target added in this checkpoint traces back to
its legacy construct or mandate; the rationale for each choice is in `docs/decision-log.md` §19.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `frontend/src/pages/UserAddPage.test.tsx` — field cases asserting the five entry fields by caption at `maxlength` `20` / `20` / `8` / `8` / `1`, the `(8 Char)` hints and the `(A=Admin, U=User)` hint | `COUSR1A` map fields `FNAME` / `LNAME` / `USERID` / `PASSWD` / `USRTYPE` with their `LENGTH=` and `INITIAL=` hint literals, and the `COUSR1AI` symbolic-map picture clauses `[app/bms/COUSR01.bms:L80-L150; app/cpy-bms/COUSR01.CPY:L55-L84]` | `target → source` |
| `frontend/src/pages/UserAddPage.test.tsx` — masking case asserting the password input is `type="password"` and the other four are `type="text"` | `PASSWD DFHMDF ATTRB=(DRK,FSET,UNPROT)` — the non-display attribute of the 3270 password field `[app/bms/COUSR01.bms:L126]` | `target → source` |
| `frontend/src/pages/UserAddPage.test.tsx` — blank-field case asserting the five verbatim messages in first-blank-wins order and that no write is issued | `COUSR01C PROCESS-ENTER-KEY` `EVALUATE TRUE` chain over `FNAMEI` / `LNAMEI` / `USERIDI` / `PASSWDI` / `USRTYPEI` with `WS-ERR-FLG` suppressing `WRITE-USER-SEC-FILE` `[app/cbl/COUSR01C.cbl:L108-L159]` | `target → source` |
| `frontend/src/pages/UserAddPage.test.tsx` — add case asserting one `addUser` call carrying the entered fields verbatim (including the `SEC-USR-PWD` value) and the confirmation `User NEWUSER1 has been added ...` with the fields reinitialized | `WRITE-USER-SEC-FILE` `DFHRESP(NORMAL)` branch: `MOVE` of the five map fields into `SEC-USER-DATA`, `STRING 'User ' SEC-USR-ID ' has been added ...'`, then `INITIALIZE-ALL-FIELDS` `[app/cbl/COUSR01C.cbl:L153-L159, L250-L258, L285-L295]` | `target → source` |
| `frontend/src/pages/UserAddPage.test.tsx` — duplicate case asserting `User ID already exist...` from the backend error body with the entered fields retained | `WRITE-USER-SEC-FILE` `DFHRESP(DUPKEY)` / `DFHRESP(DUPREC)` branch, reproduced by `UserService` as `CardDemoException('User ID already exist...')` `[app/cbl/COUSR01C.cbl:L261-L266]` | `target → source` |
| `frontend/src/pages/UserAddPage.test.tsx` — function-key cases asserting the `ENTER=Add User` / `F3=Back` / `F4=Clear` legend, the `/admin` exit and the clear-to-empty behavior | `COUSR01` line-24 legend literal, `RETURN-TO-PREV-SCREEN` `XCTL` back to the administrator menu, and `CLEAR-CURRENT-SCREEN` → `INITIALIZE-ALL-FIELDS` `[app/bms/COUSR01.bms:L155-L159; app/cbl/COUSR01C.cbl:L162-L180, L276-L295]` | `target → source` |
| `frontend/src/pages/UserAddPage.test.tsx` — the native-ESM `../api` double (`unstable_mockModule`, `ApiError` stand-in) and the `__setSession(user, 'A')` admin seeding | AAP 0.7.1 unit-test mandate for the 17 UI workflows — rule-mandated test infrastructure with no legacy analogue; the screen is administrator-only per `CDEMO-USRTYP-ADMIN` `[app/cpy/COCOM01Y.cpy:L26-L30]` | `target → rule` |
## 32. Update-User Screen Test Suite (`COUSR02` / `CU02` / `COUSR02C`) — Present on Disk

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COUSR02C` `PROCESS-ENTER-KEY` keyed read plus the `CDEMO-CU02-USR-SELECTED` hand-over, and the `UPDATE-USER-INFO` edit order `USRIDIN` → `FNAME` → `LNAME` → `PASSWD` → `USRTYPE` `[app/cbl/COUSR02C.cbl:L99-L104, L143-L172, L177-L212]` | `frontend/src/pages/UserUpdatePage.test.tsx` — the `fetch then edit` cases (router-state and query-string hand-over, ENTER lookup) and the six `required field validation` cases, each asserting `updateUser` was not called | `source → target` |
| `COUSR02C` message literals `User ID can NOT be empty...`, `First Name can NOT be empty...`, `Last Name can NOT be empty...`, `Password can NOT be empty...`, `User Type can NOT be empty...`, `User ID NOT found...`, `Please modify to update ...`, `Press PF5 key to save your updates ...` and `STRING 'User ' SEC-USR-ID ' has been updated ...'` `[app/cbl/COUSR02C.cbl:L148, L188-L206, L239, L336, L342, L372-L374]` | `frontend/src/pages/UserUpdatePage.test.tsx` — every literal asserted character-for-character against the line-23 region (`role="alert"` / `role="status"`); the no-change literal arrives as the `user-service` `400` body and is surfaced unchanged | `source → target` |
| `COUSR02.bms` masked `PASSWD` field (`ATTRB=(DRK,FSET,UNPROT)`, `LENGTH=8`), the `(8 Char)` and `(A=Admin, U=User)` hints, and the line-24 legend `ENTER=Fetch  F3=Save&Exit  F4=Clear  F5=Save` with `DFHPF3` returning to `COADM01C` `[app/bms/COUSR02.bms:L130-L154, L159-L164; app/cbl/COUSR02C.cbl:L108-L125]` | `frontend/src/pages/UserUpdatePage.test.tsx` — `type="password"` masking asserted before and after typing, both hints asserted, and the `line-24 function keys` cases covering the four-button legend, the physical ENTER / F4 / F5 keys, and F3 saving then landing on `/admin` | `source → target` |
| AAP 0.7.1 "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically" — rule-mandated verification, no legacy analogue | `frontend/src/pages/UserUpdatePage.test.tsx` — 18 cases pinning UI workflow 16 (admin update user); the whole frontend suite runs 89 cases | `target → rule` |
| Jest native-ESM `../api` mock seam (`jest.unstable_mockModule` with a passthrough `ApiError`, so no axios instance and no `import.meta` are evaluated) — test infrastructure, no legacy analogue | `frontend/src/pages/UserUpdatePage.test.tsx` — module-scope mock registration plus `beforeAll` dynamic import of the page, the `Layout` shell and the `__setSession` seam | `target → rule` |

## 33. SPA Route Table (`App.tsx` checkpoint) — Present on Disk

Targets delivered while building the client-side route table and its colocated suite. Each row
names the legacy construct or rule it serves, so the reverse direction stays complete; the
rationale for every choice is in `docs/decision-log.md` Section 19.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| CICS menu navigation and `XCTL` program transfer under COMMAREA control — `COMEN01C` / `COADM01C` dispatching to the fifteen feature programs, and `RETURN-TO-PREV-SCREEN` transferring back `[app/cbl/COMEN01C.cbl:L146-L155; app/cbl/COADM01C.cbl; app/cpy/COCOM01Y.cpy:L18-L45]` | `frontend/src/App.tsx` — the `<Routes>` tree: twenty-four `<Route path>` entries resolving the seventeen screens, declared with literal segments ahead of their parameterised siblings | `source → target` |
| `CDEMO-USER-TYPE PIC X(01)` with `88 CDEMO-USRTYP-ADMIN VALUE 'A'` / `88 CDEMO-USRTYP-USER VALUE 'U'`, and the admin-only guard fronting `COADM01C` and `COUSR00C`-`COUSR03C` `[app/cpy/COCOM01Y.cpy:L26-L30; app/cbl/COMEN01C.cbl]` | `frontend/src/App.tsx` — `RequireAdmin`, gating `/admin`, `/users`, `/users/add`, `/users/update`, `/users/delete` on `useSession().isAdmin` (`role === CDEMO_USRTYP_ADMIN`); a signed-in non-administrator is returned to `/menu` | `source → target` |
| `COSGN00C` sign-on transfer — `XCTL PROGRAM('COADM01C')` for user type `'A'`, `XCTL PROGRAM('COMEN01C')` otherwise `[app/cbl/COSGN00C.cbl:L227, L231-L237]` | `frontend/src/App.tsx` — `homeRouteForRole(role)` and `HomeRedirect`, resolving `/` to `/admin` for an administrator, `/menu` for a standard user, `/signon` when signed out | `source → target` |
| The CICS rule that every transaction other than `CC00` required a completed sign-on `[app/cbl/COSGN00C.cbl; app/csd/CARDDEMO.CSD]` | `frontend/src/App.tsx` — `RequireAuth`, redirecting an unauthenticated visitor to `/signon` with the attempted location carried in `state.from`; `/signon` is the only route outside both guards | `source → target` |
| The fixed BMS mapset frame every screen inherits — `DFHMDI SIZE=(24,80)` header rows, the line-23 message region and the line-24 function-key legend `[app/bms/COSGN00.bms:L26-L74; app/cpy/CSSTRPFY.cpy; app/cpy/CSSETATY.cpy]` | `frontend/src/App.tsx` — the single pathless parent route `element={<Layout><Outlet /></Layout>}`, so `Header` / `ErrorBanner` / `PFKeyBar` frame all twenty-four route entries including `/signon` | `source → target` |
| The 3270 having no not-found state: an unrecognised transaction returned the operator to a menu | `frontend/src/App.tsx` — the catch-all `<Route path="*">` redirecting to `/`, which re-resolves by role through `HomeRedirect`; both hops use `replace`, so no intermediate entry is left in history | `target → source (derived)` |
| The blank-key entry screens the legacy menu transferred into — `COACTVWC`, `COACTUPC`, `COCRDSLC`, `COCRDUPC` and `COTRN01C` each prompt for their own identifier, so the `XCTL` carried no key `[app/cbl/COMEN01C.cbl:L146-L155]` | `frontend/src/App.tsx` — the entry routes `/accounts`, `/accounts/update`, `/cards/view`, `/cards/update`, `/transactions/view`, declared alongside their deep-linked `:accountId` / `:cardNumber` / `:transactionId` counterparts and matching the option table ratified in `docs/decision-log.md` Section 14 | `source → target` |
| No legacy analogue — the SPA needs a single browser-history root; `<BrowserRouter>` is owned by `frontend/src/main.tsx` | `frontend/src/App.tsx` declares only `<Routes>` / `<Route>` and creates no router of its own, so the SPA has exactly one history binding | `target → rule` |
| AAP Section 0.7.1 "all 17 UI workflows must behave identically" and "at least 50 unit-test scenarios must pass"; AAP Section 0.2.1 frontend Jest deliverable | `frontend/src/App.test.tsx` — 77 cases pinning: every protected route redirecting an unauthenticated visitor to `/signon` (with the `state.from` breadcrumb), a standard user refused all five administrator screens, an administrator admitted to all five plus every shared screen, all seventeen pages mounting under their own legacy program name, role-sensitive resolution of `/` and of the catch-all, and literal segments outranking route parameters | `target → rule` |
## 34. Delete-User Screen Suite (`COUSR03` / `CU03`) — Present on Disk

Targets delivered with the colocated suite for the administrator delete-user screen. The rationale
for every choice is in `docs/decision-log.md` §19.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COUSR03` mapset body — the line-4 heading `Delete User`, the captions `Enter User ID:`, `First Name:`, `Last Name:`, `User Type: ` (trailing space verbatim) and `(A=Admin, U=User)`, with `USRIDIN` the only `UNPROT` field and `FNAME` / `LNAME` / `USRTYPE` `ASKIP` `[app/bms/COUSR03.bms:L75-L139]` | `frontend/src/pages/UserDeletePage.test.tsx` — the caption cases, which match `User Type: ` WITHOUT whitespace normalization and assert `readonly` on the three display fields while the key field stays enterable | `source → target` |
| `COUSR3AI` / `COUSR3AO` symbolic-map widths `USRIDINI PIC X(8)`, `FNAMEI` / `LNAMEI PIC X(20)`, `USRTYPEI PIC X(1)` `[app/cpy-bms/COUSR03.CPY:L55-L78]` | `UserDeletePage.test.tsx` — the `maxlength` case pinning 8 / 20 / 20 / 1 on `USRIDIN`, `FNAME`, `LNAME`, `USRTYPE` | `source → target` |
| `COUSR03C` line-23 literals `User ID can NOT be empty...`, `User ID NOT found...`, `Press PF5 key to delete this user ...` and `STRING 'User ' SEC-USR-ID ' has been deleted ...'` `[app/cbl/COUSR03C.cbl:L147-L148, L179-L180, L283-L284, L289-L290, L318-L321, L325-L326]` | `UserDeletePage.test.tsx` — the verbatim message cases, asserted through the shell's line-23 region (`role="alert"` for an error, `role="status"` for the prompt and the confirmation) | `source → target` |
| `COUSR03C` control flow — `DELETE-USER-SEC-FILE` is reachable ONLY through `DELETE-USER-INFO` on the `DFHPF5` branch, never from `PROCESS-ENTER-KEY` / `READ-USER-SEC-FILE` `[app/cbl/COUSR03C.cbl:L109-L122, L142-L172, L174-L204, L267-L303, L305-L339]` | `UserDeletePage.test.tsx` — the deliberate-delete cases: `deleteUser` is asserted NOT called by the read, then called exactly once with the keyed id by the explicit delete action and by the F5 key, after which every field is cleared | `source → target` |
| `COUSR03C` entry path — `CDEMO-CU03-USR-SELECTED` moved into `USRIDINI` followed by `PROCESS-ENTER-KEY` before the first `SEND MAP` `[app/cbl/COUSR03C.cbl:L99-L105]` | `UserDeletePage.test.tsx` — the entry cases supplying the target id as `MemoryRouter` location state and as the `userId` query parameter, each asserting the read fires once and the record is displayed | `source → target` |
| `COUSR03C` AID branches `DFHENTER` → `PROCESS-ENTER-KEY`, `DFHPF3` → `COADM01C`, `DFHPF4` → `CLEAR-CURRENT-SCREEN`, `DFHPF5` → `DELETE-USER-INFO`, legended on BMS line 24 as `ENTER=Fetch  F3=Back  F4=Clear  F5=Delete` `[app/cbl/COUSR03C.cbl:L109-L122; app/bms/COUSR03.bms:L144-L148]` | `UserDeletePage.test.tsx` — the line-24 cases: exactly four legend captions in order, ENTER reads, F4 clears field / display / message, and F3 resolves the `/admin` probe route | `source → target` |
| `CDEMO-USER-TYPE` administrator gating, `88 CDEMO-USRTYP-ADMIN VALUE 'A'` `[app/cpy/COCOM01Y.cpy:L26-L30; app/cbl/COSGN00C.cbl:L227]` | `UserDeletePage.test.tsx` — `__setSession(ADMIN_USER_ID, 'A')` seeded per case and asserted through the shell's `data-authenticated="true"`, so the admin-only screen is exercised as an authenticated administrator | `target → source (derived)` |
| AAP 0.7.1 — "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically"; no legacy analogue | `UserDeletePage.test.tsx` — 15 cases covering the `CU03` workflow end to end (chrome and captions, field widths, read-and-display by three entry routes, empty-key and miss messages for both the read and the delete, the deliberate delete, and the four function keys) | `target → rule` |
| Jest native-ESM module-mock seam (no hoisted `jest.mock`, no `import.meta` evaluation permitted in a unit suite); no legacy analogue | `UserDeletePage.test.tsx` — `jest.unstable_mockModule('../api', …)` publishing `getUser`, `deleteUser`, `signon` and a constructor-compatible `ApiError` stand-in, with the screen and shell imported dynamically after registration | `target → rule` |

## 35. Transaction-List Screen Test Suite — Present on Disk

Targets delivered with the colocated transaction-list suite. Each row names the legacy construct or
rule it serves, so the reverse direction stays complete for these artifacts too; the rationale for
every choice is in `docs/decision-log.md` §19.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COTRN00` mapset row grid — the ten row groups `SEL0001`..`SEL0010` / `TRNID01`..`TRNID10` `X(16)` / `TDATE01`..`TDATE10` `X(08)` / `TDESC01`..`TDESC10` `X(26)` / `TAMT001`..`TAMT010` `X(12)` on screen lines 10-19, the `Search Tran ID:` prompt with `TRNIDIN` `X(16)`, the `Sel` / `Transaction ID` / `Date` / `Description` / `Amount` captions, the line-21 hint `Type 'S' to View Transaction details from the list` and the line-24 `F7=Backward` / `F8=Forward` legend `[app/bms/COTRN00.bms:L90-L99, L103-L127, L153-L442, L444-L449, L454-L459; app/cpy-bms/COTRN00.CPY:L61-L96]` | `frontend/src/pages/TranListPage.test.tsx` — asserts EXACTLY ten data rows on page 1 out of 22 mocked rows against the literal `10` and `TRANSACTION_LIST_PAGE_SIZE`, the ten `SEL000n` one-character flag fields, the 16-character `TRNIDIN` filter, the five column captions, and the three-key legend | `source → target` |
| `COTRN00C` browse and validation behaviour — the `EVALUATE` that acts on the first flagged row and accepts only `'S'` / `'s'` before `XCTL` to `COTRN01C`, the rejection literal `Invalid selection. Valid value is S`, the `IS NUMERIC` class test on `TRNIDINI` with `Tran ID must be Numeric ...`, the read-failure literal `Unable to lookup transaction...` and the boundary literal `You are at the top of the page...` `[app/cbl/COTRN00C.cbl:L148-L215, L602-L616]` | `frontend/src/pages/TranListPage.test.tsx` — asserts the drill-through to `/transactions/{16-digit id}` for `S` and `s`, the first-flagged-row precedence, both rejection literals verbatim with no request issued, and the surfaced failure / informational banner text | `source → target` |
| AAP 0.7.1 "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically" — rule-mandated coverage of the `CT00` workflow, no legacy analogue for the harness itself | `frontend/src/pages/TranListPage.test.tsx` — 28 cases across screen frame, ten-rows-per-page, PF7/PF8 paging and its disabled boundaries, the `TRNIDIN` filter, row-selection validation, the line-24 legend and the request outcomes; `../api` is mocked so no network, axios or `import.meta` is evaluated | `target → rule` |

## 36. Transaction-View Screen Suite (`TranViewPage.test.tsx`) — Present on Disk

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `frontend/src/pages/TranViewPage.test.tsx` — sixteen cases over the `COTRN01` workflow: the `/transactions/:transactionId` drill-through lookup (16-character id), all thirteen protected detail fields rendered read-only under their exact BMS captions including the `X(26)` -> `X(10)` timestamp slice, the `TRNIDIN` entry field at `maxLength=16`, the verbatim `Tran ID can NOT be empty...` and `Transaction ID NOT found...` messages, and the `ENTER=Search` / `F3=Exit` legend with F3 returning to `/transactions` | `COTRN01` mapset + symbolic map + `COTRN01C` `PROCESS-ENTER-KEY` / `READ-TRANSACT-FILE` / `EIBAID` handling `[app/bms/COTRN01.bms; app/cpy-bms/COTRN01.CPY; app/cbl/COTRN01C.cbl:L112-L131, L142-L155, L266-L296]`; AAP 0.7.1 "at least 50 unit-test scenarios" and "all 17 UI workflows behaving identically" | `source -> target` |

## Coverage Assertion

Sections 12 through 36 additionally record the targets added or corrected during successive
QA-remediation and screen-delivery passes; their rows refine behaviors within constructs already
enumerated above rather than adding new sources, so the forward enumeration remains one row per
legacy construct.

This matrix enumerates the COMPLETE legacy inventory and maps it to the target design.
It does **not** assert that every target file is already present on disk — the migration
lands across multiple tranches (see *Scope of this matrix* above; Section 11 lists what
this tranche delivers):

- **Forward (`source → target`):** every legacy source construct appears exactly once as a
  Source Construct — all 28 COBOL programs (17 online + 10 batch + `CSUTLDTC`), all 28
  `app/cpy` copybooks (27 active + `UNUSED1Y.cpy` flagged excluded), all 17 BMS mapsets,
  all 17 `app/cpy-bms` symbolic-map copybooks, all 29 JCL job streams + 2 procs, and every
  configuration, catalog, control, and ASCII seed artifact.
- **Reverse (`target → source (derived)` / `target → rule`):** every target implementation
  either appears as a Target Implementation in a forward row, or is recorded in Section 9
  (derived from a legacy behavioral pattern with no 1:1 field), Section 10 (rule-mandated
  or standalone-operation infrastructure with no legacy analogue), Section 11 (foundation
  artifacts present on disk), or one of the QA-remediation sections — Section 12 (security,
  session and observability artifacts, including their tests), Section 13 (runtime-checkpoint
  targets, including the shared error-envelope handlers and the PII encryption/masking chain),
  Section 14 (runtime-verified batch, security and integration targets), Section 15 (targets
  added while making the delivered stack run, be observable and be deployable), and Section 16
  (offline batch compensator targets).
- **No row claims coverage the runtime does not deliver.** Where an earlier revision credited
  a component that was present but never activated, the row now names the registration or
  dependency that makes it active and states the observed evidence; the two such rows were
  the distributed-tracing row (Section 10) and the correlation-id row (Section 11). The same
  correction was applied again in Section 16: `FailedOutputCleanupListener` was present on disk
  and registered on NO step, so the row now names the nine steps that register it and the
  observed evidence that a failed step leaves no artifact behind.
- **Excluded items, recorded for audit completeness:** `app/cpy/UNUSED1Y.cpy` (unused
  copybook) and `app/data/EBCDIC/AWS.M2.CARDDEMO.*` (12 binary data sets; the ASCII
  fixtures in `app/data/ASCII` drive seeding). The developer-only CICS artifacts
  `COCRDSEC` (program) and transaction `CDV1` defined in `app/csd/CARDDEMO.CSD` are
  outside the 17-program online scope and are not transformed.
- **Path assertions resolve:** every migration script named anywhere in this matrix —
  `V1__create_schema.sql`, `V2__seed_reference_data.sql`, `V3__seed_test_data.sql`,
  `V5__batch_metadata.sql`, `V6__security_users_optimistic_lock.sql`,
  `V7__cards_optimistic_lock.sql`, `V8__transactions_card_fk.sql`, plus the Java migration
  `SeededPiiEncryptionMigration` (version `4`) — exists at the single path
  `carddemo-common/src/main/resources/db/migration` (the Java migration under
  `carddemo-common/src/main/java/com/carddemo/common/migration`). No row asserts a
  file that is not on disk, so the mapping is checkable by inspection rather than
  taken on trust.
