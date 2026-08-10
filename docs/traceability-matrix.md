# CardDemo Modernization — Bidirectional Traceability Matrix

This matrix maps the legacy AWS CardDemo mainframe application (COBOL / CICS / VSAM /
BMS / JCL / RACF) to its cloud-native Java 21 / Spring Boot 4.1.0 + React 19 target
design, as mandated by the **Explainability rule** (AAP 0.7.2, 0.7.4). Every legacy
source construct maps *forward* to its target implementation, and every target
implementation traces *back* to a source construct — or is explicitly flagged as
rule-mandated infrastructure or as a derived pattern with no 1:1 legacy field.

> **Scope of this matrix — 100% bidirectional coverage.** The forward direction
> enumerates the COMPLETE legacy inventory and the target each construct maps to.
> The reverse direction is closed at FILE granularity: **every target file present
> in this repository appears in this matrix**, either as the Target Implementation
> of a forward row or as its own reverse row naming the source construct it
> re-expresses, the legacy behavioral pattern it derives from, or the
> user-specified rule that mandates it. Sections 1 through 37 cover the legacy
> inventory and the delivered screen, security and batch work; **Section 38 closes
> the remainder**, so no file is left uncovered and no coverage gap remains.
> Every migration path asserted anywhere in this matrix resolves to a file on
> disk, so the mapping is checkable by inspection rather than taken on trust.

Rationale for each non-trivial decision lives in `docs/decision-log.md`; this matrix maps
constructs only and keeps every cell terse. Legacy identifiers and paths are written
verbatim, including the preserved misspellings `ACCT-EXPIRAION-DATE` /
`CARD-EXPIRAION-DATE` and the COMMAREA condition names `CDEMO-USRTYP-ADMIN`,
`CDEMO-USRTYP-USER`, `CDEMO-PGM-ENTER`, `CDEMO-PGM-REENTER`; BMS mapsets use their actual
7-character base names (e.g. `COSGN00`) with no trailing `M` suffix.

## Direction Legend

Every table below carries a **Direction** as its LAST column. The two columns before
it are a source/target pair, and the order of that pair follows the direction the
table reads in, so a forward table leads with the legacy construct while a reverse
table leads with the delivered artifact. Six header shapes are in use:

| Header shape | Reads | Used by |
|--------------|-------|---------|
| `Source Construct` \| `Target Implementation` \| `Direction` | forward: legacy first | the forward inventory sections |
| `Target Implementation (delivered)` \| `Source Construct / Mandate` \| `Direction` | reverse: delivered artifact first | the present-on-disk sections |
| `Source Construct / Mandate` \| `Target Implementation (delivered)` \| `Direction` | forward, with rule-mandated sources admitted | one delivered section |
| `Target (on disk)` \| `Source construct` \| `Direction` | reverse, at file granularity | the file-level closure sections |
| `Target (on disk)` \| `What it pins` \| `Direction` | reverse, for a test or invariant | the test-coverage sections |
| `Target (on disk)` \| `Backend endpoint(s)` \| `Source construct` \| `Direction` | reverse, with the endpoint the screen calls | the screen-to-endpoint table |

The Direction value itself is one of:

- **`source → target`** — a legacy construct is transformed into the named target
  implementation (or is explicitly recorded as excluded, for audit completeness).
- **`target → source`** — a delivered artifact is traced back to the legacy construct
  it re-expresses. This is the reverse direction stated plainly, used where the target
  maps to a real legacy construct rather than to a pattern.
- **`target → source (derived)`** — a target artifact has no 1:1 legacy field; it is
  derived from a legacy behavioral pattern (the Source Construct cell names that pattern).
  Four variants name the specific pattern inline instead — `(derived from JES job history)`,
  `(derived from JCL DISP=(NEW,CATLG,DELETE))`, `(derived from TDQ submission)` and
  `(derived from CICS routing failure)` — and read exactly as this value does.
- **`target → rule`** — a target artifact has no legacy analogue; it is mandated by a
  user-specified rule or required for standalone operation (the Source Construct cell
  names the mandating rule).
- **`source → target` / `target → rule`** — one row whose target both transforms a
  legacy construct and satisfies a rule that has no legacy analogue; both directions
  are asserted rather than one being dropped.

Every Direction cell in the matrix uses one of the values above, written with the
same `→` glyph.

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
| `CBTRN02C` `[app/cbl/CBTRN02C.cbl]` | `transaction-service` — `TransactionPostingJob` validation processor (reject codes 100–103; 430-byte reject record = 350 + 80); paired with `POSTTRAN.jcl` | `source → target` |
| `CBTRN01C` `[app/cbl/CBTRN01C.cbl]` | `batch-service` — `DataManagement*Job` (daily-transaction validation / refresh) | `source → target` |
| `CBTRN03C` `[app/cbl/CBTRN03C.cbl]` | `batch-service` — `DataManagement*Job` (transaction reporting / processing) | `source → target` |
| `CBACT01C` `[app/cbl/CBACT01C.cbl]` | `batch-service` — `DataManagement*Job` (account file read / print) | `source → target` |
| `CBACT02C` `[app/cbl/CBACT02C.cbl]` | `batch-service` — `DataManagement*Job` (card file) | `source → target` |
| `CBACT03C` `[app/cbl/CBACT03C.cbl]` | `batch-service` — `DataManagement*Job` (xref file) | `source → target` |
| `CBACT04C` `[app/cbl/CBACT04C.cbl]` | `batch-service` — `InterestCalculationService` / `InterestCalculationJobConfig` (interest = `(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`; DEFAULT-group fallback on status '23'); paired with `INTCALC.jcl` | `source → target` |
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
| `POSTTRAN.jcl` `[app/jcl/POSTTRAN.jcl]` | `transaction-service` `TransactionPostingJob` (invokes `CBTRN02C`; DALYTRAN reader, DALYREJS reject writer) | `source → target` |
| `INTCALC.jcl` `[app/jcl/INTCALC.jcl]` | `batch-service` `InterestCalculationJobConfig` (invokes `CBACT04C`) | `source → target` |
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
| Containerization + orchestration (standalone operation) | `docker-compose.yml`; `k8s/configmap.yaml`, `k8s/secret.yaml` (per-service DB-role Secrets + shared Redis + postgres bootstrap), `k8s/deployment-api-gateway.yaml`, `k8s/deployment-auth-service.yaml`, `k8s/deployment-user-service.yaml`, `k8s/deployment-account-service.yaml`, `k8s/deployment-card-service.yaml`, `k8s/deployment-transaction-service.yaml`, `k8s/deployment-billpay-service.yaml`, `k8s/deployment-reporting-service.yaml`, `k8s/deployment-batch-service.yaml`, `k8s/deployment-postgres.yaml`, `k8s/deployment-redis.yaml`, `k8s/deployment-frontend.yaml`, `k8s/service-frontend.yaml`, `k8s/service-redis.yaml`; per-service `Dockerfile`s delivered with the services | `target → rule` |
| Container/pod hardening rule (MJ-10) | `k8s/networkpolicy.yaml` (default-deny + least-privilege ingress), `k8s/poddisruptionbudget.yaml` (per-workload PDBs), pod/container `securityContext` across all Deployments | `target → rule` |
| Respecting-.gitignore + externalized-secrets (CR-11) | `.env.example` (required-env template consumed by `docker-compose.yml`; real `.env` git-ignored) | `target → rule` |
| Observability rule (AAP 0.7.5) | `application.yml` (Actuator health / readiness / liveness + `/actuator/prometheus`; `management.endpoint.health.group.readiness.include: readinessState,db` so readiness tracks the database, with liveness deliberately excluding it, plus bounded Hikari `connection-timeout` / `validation-timeout` so the probe answers inside its timeout) | `target → rule` |
| Observability rule (AAP 0.7.5) | `logback-spring.xml` (structured JSON logging + correlation ids via MDC; every appender definition scoped inside the `<springProfile>` block that references it) | `target → rule` |
| Observability rule (distributed tracing) | `org.springframework.boot:spring-boot-micrometer-tracing` + `spring-boot-micrometer-tracing-opentelemetry` (Boot 4 auto-configuration modules) over `io.micrometer:micrometer-tracing-bridge-otel` — activates the OpenTelemetry `Tracer`, W3C `traceparent` propagation and `traceId` / `spanId` MDC enrichment; no OTLP exporter by design (decision-log §6) | `target → rule` |
| Observability rule (correlation across service boundaries) | `carddemo-common` `config/CorrelationIdFilter` registered by `config/WebObservabilityConfig`, imported by all nine services (single shared implementation; adopts an inbound `X-Correlation-Id` or W3C `traceparent` trace-id, sanitizes CRLF, echoes the header on every response) | `target → rule` |
| Observability rule (AAP 0.7.5) | `application.yml` (Actuator health / readiness / liveness + `/actuator/prometheus`) | `target → rule` |
| Observability rule (AAP 0.7.5) | `logback-spring.xml` (structured JSON logging + correlation ids via MDC) | `target → rule` |
| Observability rule (distributed tracing) | `carddemo-common/pom.xml` — the Boot 4 tracing auto-configuration module + the OTLP span exporter, inherited by all nine services; `management.opentelemetry.tracing.export.otlp.endpoint` and `management.tracing.sampling.probability` in every `application.yml`; the `jaeger` collector in `docker-compose.yml` and `k8s/deployment-jaeger.yaml` / `k8s/service-jaeger.yaml` | `target → rule` |
| Observability rule (distributed tracing) — VERIFIED ACTIVE | One request through the gateway yields ONE joined trace whose spans carry both the gateway and the downstream service (observed: a sign-on trace rooted at `http post /auth/**` spanning `api-gateway`+`auth-service`, and an account-read trace spanning `api-gateway`+`account-service`; the span count per trace is not fixed, since the Redis session operations differ between a session write and a session read), and `traceId`/`spanId` are present in every JSON log record. (An earlier revision of this matrix credited `micrometer-tracing` + `micrometer-tracing-bridge-otel` alone, which supply the API and the bridge but NO auto-configuration, NO exporter and no collector, so no span was ever produced.) | `target → rule` |
| Observability rule (distributed tracing) | Micrometer Tracing + OpenTelemetry bridge configuration: `io.micrometer:micrometer-tracing-bridge-otel` plus the Spring Boot 4 auto-configuration modules `org.springframework.boot:spring-boot-micrometer-tracing` and `spring-boot-micrometer-tracing-opentelemetry` (both REQUIRED for a real `Tracer` bean — see decision-log §6), and `management.tracing.sampling.probability` per service | `target → rule` |
| Observability rule (AAP 0.7.5) | `observability/prometheus.yml` (scrape config) | `target → rule` |
| Observability rule (AAP 0.7.5) | `observability/grafana-dashboard.json` (dashboard template) | `target → rule` |
| Explainability rule (AAP 0.7.2 / 0.7.3) | `docs/decision-log.md` | `target → rule` |
| Explainability rule (AAP 0.7.2 / 0.7.4) | `docs/traceability-matrix.md` (this file) | `target → rule` |
| Standalone-operation documentation | `README-target.md` | `target → rule` |
| Observability rule / cross-cutting infra | `carddemo-common` `config/` (observability, tracing, exception handling) + `exception/` handlers | `target → rule` |
| Passing UI-workflow tests (AAP 0.7.1) — the 17 routed screens are only testable under jsdom once the Web encoding globals `react-router` needs at import time exist | `frontend/src/setupTests.ts` (`@testing-library/jest-dom` matchers + guarded `TextEncoder` / `TextDecoder` polyfill from `node:util`) | `target → rule` |

## 11. Foundation Targets — Present on Disk

The following target artifacts are the foundation layer of the delivered stack: the
shared domain, DTO, utility and error-handling types the service sections build on.
They are listed here explicitly so the reverse direction is verifiable against the
files on disk rather than only against the end-state design.

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
| `carddemo-common` `config/SessionRedisConfig.java` (strict allowlisted JSON serializer; `removedSessionTolerantSessionRepository()` post-processor wrapping every repository in `RemovedSessionTolerantSessionRepository`, so a hop that only READ the shared session cannot fail the request when sign-on rotates it or a peer revokes it) | COMMAREA session externalization + CWE-502 hardening (decision-log §6) | `target → source (derived)` |
| `carddemo-common` security password-encoder factory (BCrypt `DelegatingPasswordEncoder`) | `COSGN00C` credential check → encoder (see §8) | `source → target` |
| `transaction-service` `PostingJobCompletionListener` | `CBTRN02C` batch result codes 0/4/8/12 + rejected tally | `source → target` |
| `carddemo-common` `db/migration/V1__create_schema.sql` (11 business tables, 4 FKs, secondary indexes, 15 id-width `CHECK`s, `transaction_id_seq`) | `app/catlg/LISTCAT.txt` + the `CV*` / `CSUSR01Y` record copybooks (see §8) | `source → target` |
| `carddemo-common` `db/migration/V2__seed_reference_data.sql` (7 / 18 / 51 rows) | `app/data/ASCII/trantype.txt`, `trancatg.txt`, `discgrp.txt` (see §8) | `source → target` |
| `carddemo-common` `db/migration/V3__seed_test_data.sql` (10 users, 50 customers / accounts / cards / xrefs, 50 `tran_cat_bal`, 300 `daily_transactions`, derived `transactions` history) | `app/data/ASCII/custdata.txt`, `acctdata.txt`, `carddata.txt`, `cardxref.txt`, `tcatbal.txt`, `dailytran.txt` (see §8) | `source → target` |
| `carddemo-common` `migration/SeededPiiEncryptionMigration.java` (Flyway Java migration, version `4`) + `config/SchemaMigrationConfig.java` (`JavaMigration` `@Bean` registration) | `CUST-SSN` / `CUST-GOVT-ISSUED-ID` / `CUST-EFT-ACCOUNT-ID` / `CARD-CVV-CD` encryption-at-rest posture (see §9; decision-log §2) | `target → source (derived)` |
| `carddemo-common` `src/test/java/com/carddemo/common/migration/SeededPiiEncryptionMigrationTest.java` | Pins that Flyway version `4` ITSELF converts the seeded PII — plaintext rewritten as `gcm1:` tokens that decrypt to the exact fixture values, an already-encrypted replay issuing no update at all, and null/empty passed through. It exists because the migration decided "already encrypted" from the decrypter not throwing, which is always true for plaintext, so the tally was permanently `rewritten=0` (decision-log §40) | `target → source (derived)` |
| `carddemo-common` `db/migration/V5__batch_metadata.sql` (one shared migration set applied first-writer-wins; one `flyway_schema_history`; no `spring.batch.jdbc.*` property is set — Boot 4.1 removed that group, so the migration owns the metadata tables outright) | Spring Batch metadata schema (framework requirement) — renumbered into the consolidated version line, decision-log §2 | `target → rule` |
| `carddemo-common` `domain/DailyTransaction.java` (`@Entity` → `daily_transactions`) + `transaction-service` `repository/DailyTransactionRepository.java` (`JpaRepository`, ascending `DALYTRAN-ID` `@Query`) consumed by `TransactionPostingJob`'s reader | `CVTRA06Y` DALYTRAN feed record (see §3; reversal recorded in decision-log §6) | `source → target` |
| `carddemo-common` `dto/AccountUpdateRequestDto.java` / `AccountViewResponseDto.java` / `AccountUpdateResponseDto.java` `version` token + `account-service` `AccountService.updateAccount` compare step | `COACTUPC` snapshot-compare-before-`REWRITE` pattern (see §9) | `target → source (derived)` |
| Compose `depends_on: batch-service: service_healthy` + Kubernetes `await-schema` initContainer | Cross-service schema-provisioning order (no legacy analogue — JCL applied DDL out of band), decision-log §2 | `target → rule` |
| `carddemo-common` `db/migration/V5__batch_metadata.sql` | Spring Batch metadata schema (framework requirement). Moved out of `batch-service` into the shared set because THREE services run Spring Batch jobs against the one database | `target → rule` |
| `carddemo-common` `batch/JdbcBatchConfiguration.java` | Spring Batch 6 default `JobRepository` is in-memory (`ResourcelessJobRepository`); a JDBC-backed repository is required for the `BATCH_*` tables above to be used at all | `target → rule` |
| Per-service `application.yml` + `logback-spring.xml` (9 services) | Observability rule (Actuator health/readiness/liveness/prometheus + JSON logs) | `target → rule` |
| `observability/prometheus.yml`, `observability/grafana-dashboard.json` | Observability rule (scrape config + dashboard) | `target → rule` |
| Frontend foundation (`package.json`, `vite.config.ts`, `tsconfig*.json`, `index.css`, `nginx.conf` — whose delivery contract emits exactly ONE copy of each security header per response class (the three static locations each declare the full set; the server level declares none and `/api/` declares none, so it inherits none and the api-gateway's own stricter set is the single copy on every proxied response) and ONE combined `Cache-Control: public, max-age=31536000, immutable` on `/assets/`, `components/Header.tsx`, `types/{common,session}.ts`) | BMS screen shell + REST field contracts + standalone build/serve | `source → target` / `target → rule` |
| `frontend/package-lock.json` (`lockfileVersion 3`, 491 locked entries, consistent with `package.json`) | AAP §0.5.1 "lock-pinned at scaffold" + `npm ci` reproducible-build requirement (decision-log §6) | `target → rule` |
| `frontend/src/setupTests.ts` (`@testing-library/jest-dom` matcher registration + guarded `TextEncoder` / `TextDecoder` globals from `node:util`) | AAP §0.7.1 "all 17 UI workflows behave identically" — `jsdom` omits both globals and React Router reads `TextEncoder` at module load, so every routed page test needs them defined in `setupFilesAfterEnv` (decision-log §13) | `target → rule` |
| Frontend barrels `frontend/src/hooks/index.ts` (re-exports `./useApi` — including `useApi` / `UseApiResult` — `./useScreenFocus`, and `useSession` / `UseSessionResult`), `frontend/src/types/index.ts`, `frontend/src/api/index.ts` | AAP §0.5.3 frontend ES-module import convention — one import specifier per folder; organizational only, no runtime logic, no default export, no legacy analogue | `target → rule` |
| `api-gateway` `config/ObservabilityEndpointsIT` + `SessionRedisRoundTripIT`; `auth-service`, `user-service`, `account-service`, `card-service` `config/ObservabilityEndpointsIT` (`card-service/src/test/java/com/carddemo/card/config/ObservabilityEndpointsIT.java`, whose readiness group `readinessState,db,redis` is identical to account-service's); `carddemo-common` `config/ObservabilityConfigTest`, `WebObservabilityConfigTest` | Observability rule (AAP §0.7.5) "verified in the local environment" + COMMAREA session externalization `[app/cpy/COCOM01Y.cpy:L18-45]` | `target → rule` |

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
| `auth-service` `security/LoginAttemptServiceTest`; `account-service`, `billpay-service`, `reporting-service`, `transaction-service`, `card-service` `SecurityContractIT` — `card-service` now carries a standalone `card-service/src/test/java/com/carddemo/card/SecurityContractIT.java` alongside the pre-existing nested `CardViewUpdateIT.SecurityContract` group, so its anonymous-access contract is asserted at the same granularity as its siblings' | AAP 0.7.1 test obligation — each freezes "an anonymous request to this service port never reaches business logic" | `target → rule` |
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
| `carddemo-common` `batch/BatchOutputPathResolver.java` + `batch/BatchPathConfig.java` (imported by reporting-service and batch-service) | `CREASTMT` / `CBSTM03A` statement destination, confined to the allowlisted `carddemo.batch.output-dir` root — absolute paths, `..` traversal and symlink escapes rejected `[app/jcl/CREASTMT.JCL; app/cbl/CBSTM03A.CBL]` | `source → target` |
| `carddemo-common/src/main/java/com/carddemo/common/batch/JdbcBatchConfiguration.java` (`extends JdbcDefaultBatchConfiguration`, sentinel-guarded metadata provisioning, `@Import`ed by batch-, transaction- and reporting-service) | JES job accounting — every submitted job recorded in `BATCH_JOB_INSTANCE` / `BATCH_JOB_EXECUTION` `[app/cbl/CORPT00C.cbl]` | `target → source (derived)` |
| `docker-compose.yml` `jaeger` service + the `management.opentelemetry.tracing.export.otlp.endpoint` declaration each service carries in its default document (Compose supplies the collector through `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`) | Observability rule (AAP 0.7.5) — distributed tracing verifiable in the local environment | `target → rule` |
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
| `CREASTMT` / `CBSTM03A` statement generation `[app/jcl/CREASTMT.JCL; app/cbl/CBSTM03A.CBL]` | `reporting-service` `batch/StatementGenerationJob.java`, launched by `controller/ReportController.java#generateStatements` (`POST /reports/statements`) | `source → target` |
| JCL `DD` data-set allocation for generated report / dump / statement files `[app/jcl/]` | `carddemo-common` `batch/BatchOutputPathResolver.java` + `batch/BatchPathConfig.java` (allowlisted, writable output and input roots; `BatchPathConfig` is the `@Import`ed configuration both job-hosting services name) | `source → target` |
| `BATCH_*` job metadata tables and restartability | `carddemo-common/src/main/java/com/carddemo/common/batch/JdbcBatchConfiguration.java` + Flyway `V5__batch_metadata.sql` (no legacy analogue — JES held run history) | `target → source (derived from JES job history)` |
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
| `.dockerignore` (repository root) + `auth-service`, `user-service`, `account-service`, `card-service`, `transaction-service`, `billpay-service`, `batch-service`, `api-gateway` `.dockerignore` | Build hygiene for the containerization mandate (AAP 0.2.1): minimal, cache-correct build contexts. There is one file per Docker CONTEXT, not one per service, because Docker resolves `.dockerignore` relative to the context root. Eight services build from their own module directory and carry their own file; `reporting-service` builds from the REPOSITORY ROOT (`context: .`), because its Dockerfile compiles the Maven reactor in-container and must copy the aggregator POM, all ten module POMs and `carddemo-common`, so the ROOT file is the one that governs it and a module-level file would never be read. Enforced by `carddemo-common` `src/test/.../packaging/DockerBuildContextHygieneTest.java` | `target → rule` |
| `carddemo-common` `src/test/.../batch/BatchOutputPathResolverTest.java`, `.../config/*`, `.../crypto/*`, `.../security/*`; `batch-service` `controller/BatchControllerTest.java`; `transaction-service` `TransactionControllerSessionTest` | AAP 0.7.1 test mandate (50+ unit-test scenarios) applied to the components added above | `target → rule` |
| `carddemo-common` `config/LettuceMetricsAutoConfiguration.java` | Observability rule — publishes the `lettuce_command_*` series the Redis dashboard panels query | `target → rule` |

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
| `batch-service` `config/JobSchedulingConfig.launchRepeatable` (identifying run id for read/print runs) and `reporting-service` `config/JobSchedulingConfig` statement run id (Q18) | `CORPT00C` TDQ `'JOBS'` write on EVERY request → JES re-run `[app/cbl/CORPT00C.cbl:L450]`; `TRANREPT`/`CREASTMT` job streams `[app/proc/TRANREPT.prc, app/jcl/CREASTMT.JCL]` | `source → target` |
| `reporting-service` `client/BatchJobClient` refusal-versus-hand-off distinction (`SUBMISSION_REFUSED_PREFIX`) (Q18) | `CORPT00C` `SUBMIT-JOB-TO-INTRDR` TDQ write outcome `[app/cbl/CORPT00C.cbl:L450]` | `target → source (derived)` |
| `carddemo-common` `config/RequestLoggingFilter`, `config/GlobalExceptionHandler`, `config/CardDemoErrorController`, `config/ObservabilityConfig.sensitiveTraceAttributeMask` (span attributes — see Section 39.2) and `api-gateway` `config/UpstreamFailureHandler` PAN masking on every log and trace path (Q19) | `CARD-NUM PIC X(16)` protection AAP 0.6.7 + the "no sensitive values in logs" rule; `security/SensitiveDataMasker` `[app/cpy/CVACT02Y.cpy]` | `target → rule` |
| `reporting-service` `src/test/.../client/BatchJobClientTest.java`; `batch-service` `JobLaunchParameterIT` repeatability and identifying-flag cases; `card-service` `OptimisticLockConflictIT` CVV-retention and version-token cases; the new `CardServiceTest`, `TransactionServiceTest`, `BillPaymentServiceTest`, `UserServiceTest`, `AccountServiceTest` and `AccountUpdateValidatorTest` regression cases | AAP 0.7.1 "at least 50 unit-test scenarios must pass" — each case pins one remediated legacy behavior | `target → rule` |

## 18. Frontend Shared Screen Shell — Presentation Layer Targets

Targets delivered while building the shared 24x80 screen shell and its colocated suite. Each row
names the legacy construct or rule it serves, so the reverse direction stays complete; the
rationale for every choice is in `docs/decision-log.md` §12.

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `frontend/src/components/Layout.tsx` — the `.screen` frame composing `Header` (rows 1-3), `main.screen__body` (rows 4-22), `ErrorBanner` (line 23) and `PFKeyBar` (line 24) | The fixed BMS mapset frame every screen inherits: `DFHMDI SIZE=(24,80)` with the `Tran :` / `Prog :` / `Date :` / `Time :` header rows `[app/bms/COSGN00.bms:L26-L74]`; PF-key legend semantics `[app/cpy/CSSTRPFY.cpy]`; screen-attribute semantics `[app/cpy/CSSETATY.cpy]` | `source → target` |
| `frontend/src/components/Layout.tsx` — `ScreenChrome` / `ScreenChromeContextValue` and the `useScreenChrome()` context hook | The COMMAREA fields a program moved into the shared symbolic map before `SEND MAP`, including `CDEMO-LAST-MAP` / `CDEMO-LAST-MAPSET PIC X(7)` `[app/cpy/COCOM01Y.cpy:L18-L45, L44]` | `source → target` |
| `frontend/src/components/Layout.tsx` — `data-authenticated` on the `.screen` frame | `CDEMO-USER-TYPE` sign-on state gating screen entry, `88 CDEMO-USRTYP-ADMIN VALUE 'A'` / `88 CDEMO-USRTYP-USER VALUE 'U'` `[app/cpy/COCOM01Y.cpy:L26-L30]`; the `COSGN00C` routing that follows a successful sign-on `[app/cbl/COSGN00C.cbl:L227, L231-L237]` | `target → source (derived)` |
| `frontend/src/testing/sessionHarness.ts` — `resolveSessionFromServer()`, `seedSignedOnSession(userId, role)`, `seedSignedOutSession()` (REPLACES the removed `frontend/src/hooks/useSession.ts` `__setSession` seam) | No legacy analogue — a test-only module that places the SPA in the authenticated or the server-confirmed signed-out state by driving the PRODUCTION `GET /session` probe through `useSession().refresh`, so the shipped session module exports no store mutator (AAP 0.7.1: the UI workflows must be verifiable) | `target → rule` |
| `frontend/src/hooks/index.ts` — barrel over `useApi`, `useScreenFocus`, `useSession` | No legacy analogue — the ES-module single-specifier import convention AAP 0.5.3 mandates for the frontend, matching the existing `api/` and `types/` barrels | `target → rule` |
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
| `frontend/src/pages/TranViewPage.tsx` — its line-8 rule renders the mapset's own `LENGTH=70` run of dashes through the shared separator class, the same construct and the same treatment `COTRN02` and `COBIL00` already use, rather than a drawn `hr` | `app/bms/COTRN01.bms` + `app/cbl/COTRN01C.cbl` (`CT01`) | `target → source` |
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
| `frontend/src/api/client.ts` | shared axios instance | The CICS terminal-to-region seam: `EXEC CICS SEND`/`RECEIVE MAP` plus `RESP(DFHRESP(...))` status handling `[app/cbl/COACTUPC.cbl:L3654-L3691]`, re-expressed as interceptors (CSRF double-submit, `X-Correlation-Id`, `401` reported to the store as the `'expired'` session rejection while a `403` is kept in place and its CSRF token re-primed, `409` → optimistic-lock conflict, and the caller's `AbortSignal` bound to the outgoing request). The module performs NO browser navigation: dropping the local authority is what moves the operator, and `App`'s route guard makes the hop to `/signon` client-side, so the SPA is not re-downloaded to report an expiry | `target → source` |
| `frontend/src/api/config.ts` | gateway base URL | `app/csd/CARDDEMO.CSD` transaction routing | `target → source` |
| `frontend/src/api/auth.ts` | `POST /auth/signon`, `POST /logout` | `app/cbl/COSGN00C.cbl` (`CC00`) | `target → source` |
| `frontend/src/api/menu.ts` | `GET/POST /menu`, `GET/POST /admin/menu` | `app/cbl/COMEN01C.cbl` + `app/cbl/COADM01C.cbl`, options from `app/cpy/COMEN02Y.cpy` + `app/cpy/COADM02Y.cpy` | `target → source` |
| `frontend/src/api/accounts.ts` | `GET /accounts/{id}`, `PUT /accounts/{id}` | `app/cbl/COACTVWC.cbl` + `app/cbl/COACTUPC.cbl` | `target → source` |
| `frontend/src/api/cards.ts` | `GET /cards`, `POST /cards/detail`, `PUT /cards` (the card key travels in the request body, never in a URL — decision log §40.4) | `app/cbl/COCRDLIC.cbl`, `COCRDSLC.cbl`, `COCRDUPC.cbl` | `target → source` |
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
| `frontend/src/components/Layout.tsx` | The 24×80 3270 frame and COMMAREA per-screen half (see §6 chrome row). The frame is BOUNDED by the display, as a terminal was: `div.screen` carries `max-height: 100vh`/`100dvh` while the body region declares `overflow: auto`, so a screen taller than its region scrolls inside the frame and the line-23 message region and line-24 key legend are never pushed off the glass | `target → source` |
| Screen-chrome publication — all seventeen `frontend/src/pages/*.tsx` publish through `useScreenChrome` in a `useLayoutEffect` | `EXEC CICS SEND MAP`: a program moved EVERY field into the symbolic map — `TRNNAME`, `PGMNAME`, `TITLE01`/`TITLE02`, the row-23 `ERRMSG` and the row-24 legend — and then issued ONE send, so a half-built map never reached the terminal `[app/cbl/COACTVWC.cbl; app/cpy/COTTL01Y.cpy]` | `target → source` |
| `frontend/src/components/Header.tsx` | Rows 1–2 of every mapset: `TRNNAME`, `PGMNAME`, `TITLE01`/`TITLE02`, `CURDATE`, `CURTIME`, and `COSGN00`'s `APPLID`/`SYSID` | `target → source` |
| `frontend/src/components/PFKeyBar.tsx` | `app/cpy/CSSTRPFY.cpy` + each mapset's row-24 legend field, including `ATTRB=DRK` fields (rendered as `dark`) | `target → source` |
| `frontend/src/components/ErrorBanner.tsx` | The row-23 `ERRMSG PIC X(78)` region and `MOVE DFHRED/DFHGREEN TO ERRMSGC`; its `isFieldInError` / `fieldErrorClass` / `fieldMarker` helpers reproduce `FLG-x-NOT-OK OR FLG-x-BLANK`, the `MOVE DFHRED` of `app/cpy/CSSETATY.cpy` and its blank-field `MOVE '*'`, and are used by the three screens whose programs move `DFHRED` over a field; its `invalidFieldProps` / `invalidValueProps` helpers express each program's `MOVE -1 TO <field>L` fault target as `aria-invalid`, which is the ONLY marking the four screens that contain no `MOVE DFHRED` carry (`COSGN00C`, `COTRN02C`, `COBIL00C`, `CORPT00C`) — those screens derive the target from the cursor they place, so a SERVER-produced message and a confirmation prompt mark their control too — `invalidFieldProps` additionally points the faulted control at the row-23 region, `invalidValueProps` omits the reference for a row-action column that publishes no message | `target → source` |
| `frontend/src/components/OutputField.tsx` | `app/cpy/CSSETATY.cpy` `ATTRB=ASKIP` protected output fields — a term/value pair that carries an accessible name without being a tab stop | `target → source` |
| `frontend/src/components/display.ts` | Shared display/parse helpers for the string wire formats (`NUMERIC(p,s)` money, `PIC X(10)` dates) | `target → source` |
| `frontend/src/hooks/useApi.ts` | The CICS request/response turnaround: one in-flight task per terminal. `isInFlight` is the 3270 keyboard lock that holds from the moment an attention identifier is sent until the next map arrives, so a screen inhibits an AID that arrives in that window instead of issuing a second identical request; a superseded call is genuinely CANCELLED — `run` issues the wrapped call inside `runWithRequestSignal`, so its `AbortSignal` travels on the request — and the generation counter additionally discards any response that still arrives. A failed turn keeps the payload already on screen, as a program re-sent the map it had built with its text moved into `ERRMSG` | `target → source` |
| `frontend/src/hooks/useSession.ts` | `app/cpy/COCOM01Y.cpy` COMMAREA identity half + `COSGN00C` sign-on/sign-off lifecycle, including `RETURN-TO-SIGNON-SCREEN` `[app/cbl/COMEN01C.cbl:L170-177]` as the signed-out state the route guard acts on. A rejected request reaches the store as `abandonSession(reason)`: an expiry additionally publishes a notice for the sign-on screen to report, a refusal publishes none because the refusing program owns that text | `target → source` |
| `frontend/src/api/messages.ts` — `SESSION_ENDED_MESSAGE` (`'Your session has ended. Please sign on again.'`), declared ONCE and read by the response interceptor, the route guards, the self-revocation exit and the session store, plus `SessionState.notice` and `clearSessionNotice()` in `frontend/src/hooks/useSession.ts` | No legacy analogue — a cookie-backed session that expires underneath a running screen is a TARGET-architecture failure mode. `RETURN-TO-SIGNON-SCREEN` carries no message because on the terminal only the operator's own PF3 reached the sign-on map `[app/cbl/COMEN01C.cbl:L170-177]`. The notice satisfies AAP 0.7.1 (a workflow must behave identically — an operator returned to sign-on mid-task must be told why, not silently reset) and is logged as a deviation in decision log §51.2 per AAP 0.7.2 | `target → rule` |
| `frontend/src/hooks/useScreenFocus.ts` | `ATTRB=IC` insert-cursor placement and every `MOVE -1 TO <field>L` cursor override, honoured on each send | `target → source` |
| `frontend/src/hooks/index.ts` | — Module barrel; no legacy analogue | `target → rule` |
| `frontend/src/index.css` | The 24×80 character grid, `DFHBLUE`/`DFHGREEN`/`DFHRED`/`DFHTURQ`/`DFHYELLO`/`DFHNEUTR` attribute colours, and per-mapset field geometry. `.accountView__details` lays `COACTVW` out as the character grid itself — 80 one-character columns with `grid-auto-rows: var(--term-line)`, each field placed on its declared `POS=(row,col)` for its `LENGTH` — and `.accountView__details .field { min-height: 0 }` keeps a value box from raising its row, so a protected row occupies ONE character row whether or not the record has been read and the two declared columns line up: a 3270 field kept its position and size regardless of its content. `.accountUpdate__marker` reserves the `CSSETATY` re-entry `*` its own character column so a failed edit moves no caption or entry field, and `.accountUpdate__field` folds (`flex-wrap`) with its automatic minimum size released so the `PIC X(50)` address controls fit a narrow row instead of losing their right border to a scroller. One `input:disabled, select:disabled, textarea:disabled` rule dims EVERY inactive control in the `--term-neutral-dim` tone `button:disabled` already uses for an inactive function key — the ASKIP/DRK semantic that a field not opened for entry never looked like one awaiting input; the report screen's private copy of that rule was removed in favour of it | `target → source` |
| `frontend/src/setupTests.ts` | — Jest environment shim (`TextEncoder`/`TextDecoder` for the router's ESM build); no legacy analogue, recorded in the decision log | `target → rule` |
| `frontend/eslint.config.js` | — Mandatory lint gate; no legacy analogue (tooling requirement) | `target → rule` |
| `frontend/src/pages/cardSelection.ts` | `CDEMO-CC00-CARD-SELECTED` + `CDEMO-CC00-ACCT-SELECTED` carried in the COMMAREA between `COCRDLIC` and `COCRDSLC`/`COCRDUPC` `[app/cpy/COCOM01Y.cpy]` — the composite hand-over, held in router location state so no card number reaches the address bar, history, a bookmark or a `Referer` header | `target → source` |
| `frontend/src/vite-env.d.ts` | — Build-tool ambient type declarations; no legacy analogue | `target → rule` |
| `frontend/src/__mocks__/fileMock.ts` | — Jest static-asset stub; no legacy analogue (test-harness requirement) | `target → rule` |

### 21.6 Backend controllers — reverse direction

| Target (on disk) | Source construct | Direction |
|---|---|---|
| `auth-service/.../AuthenticationController.java` | `app/cbl/COSGN00C.cbl` (`CC00`) | `target → source` |
| `api-gateway/.../SessionController.java` | `app/cpy/COCOM01Y.cpy` COMMAREA identity probe — replaces client-held session authority | `target → source` |
| `api-gateway/.../CsrfController.java` (`GET /csrf`) + `api-gateway/.../config/CsrfCookieMaterializingFilter.java` | — CSRF double-submit token issue; no legacy analogue (the 3270 link needed none) | `target → rule` |
| `api-gateway/.../MenuController.java` | `app/cbl/COMEN01C.cbl` + `app/cbl/COADM01C.cbl` | `target → source` |
| `account-service/.../AccountController.java` | `app/cbl/COACTVWC.cbl` + `app/cbl/COACTUPC.cbl` | `target → source` |
| `card-service/.../CardController.java` | `app/cbl/COCRDLIC.cbl`, `COCRDSLC.cbl`, `COCRDUPC.cbl` | `target → source` |
| `transaction-service/.../TransactionController.java` | `app/cbl/COTRN00C.cbl`, `COTRN01C.cbl`, `COTRN02C.cbl` | `target → source` |
| `billpay-service/.../BillPaymentController.java` | `app/cbl/COBIL00C.cbl` | `target → source` |
| `reporting-service/.../ReportController.java` | `app/cbl/CORPT00C.cbl` | `target → source` |
| `user-service/.../UserController.java` | `app/cbl/COUSR00C.cbl` … `COUSR03C.cbl` | `target → source` |
| `transaction-service/.../PostingJobController.java` | `app/jcl/POSTTRAN.jcl` + `app/cbl/CBTRN02C.cbl` | `target → source` |
| `batch-service/.../BatchController.java` | `app/jcl/INTCALC.jcl`, `CREASTMT.JCL` and the TDQ `'JOBS'`-to-JES submission | `target → source` |
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
| Jest native-ESM module mocking and the `CM00` menu wire contract — rule-mandated test infrastructure, no legacy analogue | `MainMenuPage.test.tsx` — `jest.unstable_mockModule('../api', …)` with a passthrough-shaped `ApiError`, the `../testing/sessionHarness` session seeding helpers, and the real `Layout` shell, delivering 27 of the scenarios AAP 0.7.1 requires without evaluating the Vite build-time environment | `target → rule` |
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
| `AdminMenuPage.test.tsx` — the single `jest.unstable_mockModule` registration for `../api` (only the API module is replaced; `react-router` stays the REAL module, so every transfer is asserted on the router location resolved by a `useLocation` probe rather than on a mock call), and the `ApiError` passthrough case | No legacy analogue — native-ESM test isolation for the AAP §0.2.1 Jest + React Testing Library deliverable `[frontend/package.json jest.preset]` | `target → rule` |

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
| `COCRDUP` line-24 legend literals `ENTER=Process F3=Exit` (`FKEYS`) and `F5=Save F12=Cancel` (`FKEYSC`), and the `COCRDUPC` PF-key routing to the card list and back to the card detail `[app/bms/COCRDUP.bms; app/cpy/CSSTRPFY.cpy]` | `CardUpdatePage.test.tsx` — the function-key cases asserting the legend order `ENTER=Process` / `F3=Exit` / `F5=Save` / `F12=Cancel`, F12 re-reading the displayed record (decision log §40.2), F3 to `/cards`, and the same four outcomes from the physical `Enter` / `F3` / `F5` / `F12` AID keys | `source → target` |
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
| `COCOM01Y` COMMAREA identity fields `CDEMO-USER-ID` / `CDEMO-USER-TYPE` (`88 CDEMO-USRTYP-USER VALUE 'U'`) carried into the screen `[app/cpy/COCOM01Y.cpy:L18-L30]` | `BillPayPage.test.tsx` — the suite seeds the externalized session with `seedSignedOnSession('USER0001', 'U')` — which drives the production `GET /session` probe — before every render and asserts the shell's `data-authenticated` reflects it | `source → target` |
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
| Ten-rows-per-page assertion — exactly 10 data rows rendered from a 23-row response, checked against the suite's own `EXPECTED_ROWS_PER_PAGE` constant, which cites `USER-REC OCCURS 10 TIMES` `[frontend/src/pages/UserListPage.test.tsx:L50-L51]` | `USER-REC OCCURS 10 TIMES` fixing the user list at ten rows, and the `SEL0001`/`USRID01` .. `SEL0010`/`USRID10` row fields of the mapset `[app/cbl/COUSR00C.cbl:L57; app/bms/COUSR00.bms:L153-L438]` | `source → target` |
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
| `frontend/src/pages/UserAddPage.test.tsx` — the native-ESM `../api` double (`unstable_mockModule`, `ApiError` stand-in) and the `seedSignedOnSession(user, 'A')` admin seeding via `../testing/sessionHarness` | AAP 0.7.1 unit-test mandate for the 17 UI workflows — rule-mandated test infrastructure with no legacy analogue; the screen is administrator-only per `CDEMO-USRTYP-ADMIN` `[app/cpy/COCOM01Y.cpy:L26-L30]` | `target → rule` |
## 32. Update-User Screen Test Suite (`COUSR02` / `CU02` / `COUSR02C`) — Present on Disk

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COUSR02C` `PROCESS-ENTER-KEY` keyed read plus the `CDEMO-CU02-USR-SELECTED` hand-over, and the `UPDATE-USER-INFO` edit order `USRIDIN` → `FNAME` → `LNAME` → `PASSWD` → `USRTYPE` `[app/cbl/COUSR02C.cbl:L99-L104, L143-L172, L177-L212]` | `frontend/src/pages/UserUpdatePage.test.tsx` — the `fetch then edit` cases (router-state and query-string hand-over, ENTER lookup) and the six `required field validation` cases, each asserting `updateUser` was not called | `source → target` |
| `COUSR02C` message literals `User ID can NOT be empty...`, `First Name can NOT be empty...`, `Last Name can NOT be empty...`, `Password can NOT be empty...`, `User Type can NOT be empty...`, `User ID NOT found...`, `Please modify to update ...`, `Press PF5 key to save your updates ...` and `STRING 'User ' SEC-USR-ID ' has been updated ...'` `[app/cbl/COUSR02C.cbl:L148, L188-L206, L239, L336, L342, L372-L374]` | `frontend/src/pages/UserUpdatePage.test.tsx` — every literal asserted character-for-character against the line-23 region (`role="alert"` / `role="status"`); the no-change literal arrives as the `user-service` `400` body and is surfaced unchanged | `source → target` |
| `COUSR02.bms` masked `PASSWD` field (`ATTRB=(DRK,FSET,UNPROT)`, `LENGTH=8`), the `(8 Char)` and `(A=Admin, U=User)` hints, and the line-24 legend `ENTER=Fetch  F3=Save&Exit  F4=Clear  F5=Save` with `DFHPF3` returning to `COADM01C` `[app/bms/COUSR02.bms:L130-L154, L159-L164; app/cbl/COUSR02C.cbl:L108-L125]` | `frontend/src/pages/UserUpdatePage.test.tsx` — `type="password"` masking asserted before and after typing, both hints asserted, and the `line-24 function keys` cases covering the four-button legend, the physical ENTER / F4 / F5 keys, and F3 saving then landing on `/admin` | `source → target` |
| AAP 0.7.1 "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically" — rule-mandated verification, no legacy analogue | `frontend/src/pages/UserUpdatePage.test.tsx` — 18 cases pinning UI workflow 16 (admin update user); the whole frontend suite runs 89 cases | `target → rule` |
| Jest native-ESM `../api` mock seam (`jest.unstable_mockModule` with a passthrough `ApiError`, so no axios instance and no `import.meta` are evaluated) — test infrastructure, no legacy analogue | `frontend/src/pages/UserUpdatePage.test.tsx` — module-scope mock registration plus `beforeAll` dynamic import of the page, the `Layout` shell and the `../testing/sessionHarness` seeding helpers | `target → rule` |

## 33. SPA Route Table (`App.tsx` checkpoint) — Present on Disk

Targets delivered while building the client-side route table and its colocated suite. Each row
names the legacy construct or rule it serves, so the reverse direction stays complete; the
rationale for every choice is in `docs/decision-log.md` Section 19.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| CICS menu navigation and `XCTL` program transfer under COMMAREA control — `COMEN01C` / `COADM01C` dispatching to the fifteen feature programs, and `RETURN-TO-PREV-SCREEN` transferring back `[app/cbl/COMEN01C.cbl:L146-L155; app/cbl/COADM01C.cbl; app/cpy/COCOM01Y.cpy:L18-L45]` | `frontend/src/App.tsx` — the `<Routes>` tree: twenty-two `<Route path>` entries (the entry route `/`, the twenty named screen paths, and the catch-all `*`) resolving the seventeen screens, declared with literal segments ahead of their parameterised siblings | `source → target` |
| `CDEMO-USER-TYPE PIC X(01)` with `88 CDEMO-USRTYP-ADMIN VALUE 'A'` / `88 CDEMO-USRTYP-USER VALUE 'U'`, and the admin-only guard fronting `COADM01C` and `COUSR00C`-`COUSR03C` `[app/cpy/COCOM01Y.cpy:L26-L30; app/cbl/COMEN01C.cbl]` | `frontend/src/App.tsx` — `RequireAdmin`, gating `/admin`, `/users`, `/users/add`, `/users/update`, `/users/delete` on `useSession().isAdmin` (`role === CDEMO_USRTYP_ADMIN`); a signed-in non-administrator is returned to `/menu` | `source → target` |
| `COSGN00C` sign-on transfer — `XCTL PROGRAM('COADM01C')` for user type `'A'`, `XCTL PROGRAM('COMEN01C')` otherwise `[app/cbl/COSGN00C.cbl:L227, L231-L237]` | `frontend/src/App.tsx` — `homeRouteForRole(role)` and `HomeRedirect`, resolving `/` to `/admin` for an administrator, `/menu` for a standard user, `/signon` when signed out | `source → target` |
| The CICS rule that every transaction other than `CC00` required a completed sign-on `[app/cbl/COSGN00C.cbl; app/csd/CARDDEMO.CSD]` | `frontend/src/App.tsx` — `RequireAuth`, redirecting an unauthenticated visitor to `/signon` with `replace` and NO location state: the legacy sign-on screen carried no return-to breadcrumb (`COSGN00C` transfers by ROLE, not back to the refused transaction), so none is invented and `App.test.tsx` pins `location.state` as `null`. `/signon` is the only route outside both guards | `source → target` |
| The fixed BMS mapset frame every screen inherits — `DFHMDI SIZE=(24,80)` header rows, the line-23 message region and the line-24 function-key legend `[app/bms/COSGN00.bms:L26-L74; app/cpy/CSSTRPFY.cpy; app/cpy/CSSETATY.cpy]` | `frontend/src/App.tsx` — the single pathless parent route `element={<AppFrame />}` (`<Layout><Outlet /></Layout>`), so `Header` / `ErrorBanner` / `PFKeyBar` frame all twenty-two route entries including `/signon` | `source → target` |
| The 3270 having no not-found state: an unrecognised transaction returned the operator to a menu | `frontend/src/App.tsx` — the catch-all `<Route path="*">` redirecting to `/`, which re-resolves by role through `HomeRedirect`; both hops use `replace`, so no intermediate entry is left in history | `target → source (derived)` |
| The blank-key entry screens the legacy menu transferred into — `COACTVWC`, `COACTUPC`, `COCRDSLC`, `COCRDUPC` and `COTRN01C` each prompt for their own identifier, so the `XCTL` carried no key `[app/cbl/COMEN01C.cbl:L146-L155]` | `frontend/src/App.tsx` — the entry routes `/accounts`, `/accounts/update`, `/cards/view`, `/cards/update`, `/transactions/view`, declared alongside their deep-linked `:accountId` / `:cardNumber` / `:transactionId` counterparts and matching the option table ratified in `docs/decision-log.md` Section 14 | `source → target` |
| No legacy analogue — the SPA needs a single browser-history root; `<BrowserRouter>` is owned by `frontend/src/main.tsx` | `frontend/src/App.tsx` declares only `<Routes>` / `<Route>` and creates no router of its own, so the SPA has exactly one history binding | `target → rule` |
| AAP Section 0.7.1 "all 17 UI workflows must behave identically" and "at least 50 unit-test scenarios must pass"; AAP Section 0.2.1 frontend Jest deliverable | `frontend/src/App.test.tsx` — 74 cases pinning: every protected route redirecting an unauthenticated visitor to `/signon` (asserting `location.state` is `null`, i.e. that no breadcrumb is carried, and that the hop is `REPLACE`), a standard user refused all five administrator screens, an administrator admitted to all five plus every shared screen, all seventeen pages mounting under their own legacy program name, role-sensitive resolution of `/` and of the catch-all, and literal segments outranking route parameters. Identity is established by the stubbed `GET /session` and read by the production probe, so a deleted session bootstrap or a deleted guard fails the suite | `target → rule` |
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
| `CDEMO-USER-TYPE` administrator gating, `88 CDEMO-USRTYP-ADMIN VALUE 'A'` `[app/cpy/COCOM01Y.cpy:L26-L30; app/cbl/COSGN00C.cbl:L227]` | `UserDeletePage.test.tsx` — `seedSignedOnSession(ADMIN_USER_ID, 'A')` seeded per case and asserted through the shell's `data-authenticated="true"`, so the admin-only screen is exercised as an authenticated administrator | `target → source (derived)` |
| AAP 0.7.1 — "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically"; no legacy analogue | `UserDeletePage.test.tsx` — 15 cases covering the `CU03` workflow end to end (chrome and captions, field widths, read-and-display by three entry routes, empty-key and miss messages for both the read and the delete, the deliberate delete, and the four function keys) | `target → rule` |
| Jest native-ESM module-mock seam (no hoisted `jest.mock`, no `import.meta` evaluation permitted in a unit suite); no legacy analogue | `UserDeletePage.test.tsx` — `jest.unstable_mockModule('../api', …)` publishing `getUser`, `deleteUser`, `signon` and a constructor-compatible `ApiError` stand-in, with the screen and shell imported dynamically after registration | `target → rule` |

## 35. Transaction-List Screen Test Suite — Present on Disk

Targets delivered with the colocated transaction-list suite. Each row names the legacy construct or
rule it serves, so the reverse direction stays complete for these artifacts too; the rationale for
every choice is in `docs/decision-log.md` §19.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COTRN00` mapset row grid — the ten row groups `SEL0001`..`SEL0010` / `TRNID01`..`TRNID10` `X(16)` / `TDATE01`..`TDATE10` `X(08)` / `TDESC01`..`TDESC10` `X(26)` / `TAMT001`..`TAMT010` `X(12)` on screen lines 10-19, the `Search Tran ID:` prompt with `TRNIDIN` `X(16)`, the `Sel` / `Transaction ID` / `Date` / `Description` / `Amount` captions, the line-21 hint `Type 'S' to View Transaction details from the list` and the line-24 `F7=Backward` / `F8=Forward` legend `[app/bms/COTRN00.bms:L90-L99, L103-L127, L153-L442, L444-L449, L454-L459; app/cpy-bms/COTRN00.CPY:L61-L96]` | `frontend/src/pages/TranListPage.test.tsx` — asserts EXACTLY ten data rows on page 1 out of 22 mocked rows against the suite's own `PAGE_SIZE` constant `[frontend/src/pages/TranListPage.test.tsx:L235]`, the ten `SEL000n` one-character flag fields, the 16-character `TRNIDIN` filter, the five column captions, and the three-key legend | `source → target` |
| `COTRN00C` browse and validation behaviour — the `EVALUATE` that acts on the first flagged row and accepts only `'S'` / `'s'` before `XCTL` to `COTRN01C`, the rejection literal `Invalid selection. Valid value is S`, the `IS NUMERIC` class test on `TRNIDINI` with `Tran ID must be Numeric ...`, the read-failure literal `Unable to lookup transaction...` and the boundary literal `You are at the top of the page...` `[app/cbl/COTRN00C.cbl:L148-L215, L602-L616]` | `frontend/src/pages/TranListPage.test.tsx` — asserts the drill-through to `/transactions/{16-digit id}` for `S` and `s`, the first-flagged-row precedence, both rejection literals verbatim with no request issued, and the surfaced failure / informational banner text | `source → target` |
| AAP 0.7.1 "at least 50 unit-test scenarios must pass" and "all 17 UI workflows must behave identically" — rule-mandated coverage of the `CT00` workflow, no legacy analogue for the harness itself | `frontend/src/pages/TranListPage.test.tsx` — 28 cases across screen frame, ten-rows-per-page, PF7/PF8 paging and its disabled boundaries, the `TRNIDIN` filter, row-selection validation, the line-24 legend and the request outcomes; `../api` is mocked so no network, axios or `import.meta` is evaluated | `target → rule` |

## 36. Transaction-View Screen Suite (`TranViewPage.test.tsx`) — Present on Disk

| Target Implementation (delivered) | Source Construct / Mandate | Direction |
|-----------------------------------|----------------------------|-----------|
| `frontend/src/pages/TranViewPage.test.tsx` — sixteen cases over the `COTRN01` workflow: the `/transactions/:transactionId` drill-through lookup (16-character id), all thirteen protected detail fields rendered read-only under their exact BMS captions including the `X(26)` -> `X(10)` timestamp slice, the `TRNIDIN` entry field at `maxLength=16`, the verbatim `Tran ID can NOT be empty...` and `Transaction ID NOT found...` messages, and the `ENTER=Search` / `F3=Exit` legend with F3 returning to `/transactions` | `COTRN01` mapset + symbolic map + `COTRN01C` `PROCESS-ENTER-KEY` / `READ-TRANSACT-FILE` / `EIBAID` handling `[app/bms/COTRN01.bms; app/cpy-bms/COTRN01.CPY; app/cbl/COTRN01C.cbl:L112-L131, L142-L155, L266-L296]`; AAP 0.7.1 "at least 50 unit-test scenarios" and "all 17 UI workflows behaving identically" | `source → target` |

## 37. End-to-End Security-Gate Remediation Targets — Present on Disk

Targets added, repointed or retired while resolving the end-to-end security-gate review. Rows are
included here so that coverage stays complete in BOTH directions after the remediation: every
construct created during the pass has a forward or rule row, every construct retired during the pass
has its control repointed to the file that now carries it, and the three page suites the review found
untraced are enumerated.

### 37.1 Constructs created during the remediation

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COCOM01Y` COMMAREA addressability — a CICS program received the COMMAREA as a `LINKAGE SECTION` parameter and could not execute without it, so a missing COMMAREA was a structural impossibility rather than a runtime default `[app/cpy/COCOM01Y.cpy:L18-L45]` | `carddemo-common/src/main/java/com/carddemo/common/dto/SessionContextSupport.java` — `require(HttpServletRequest)` resolves the context from the ALREADY-established session under the single `SessionAttributes.SESSION_CONTEXT` key and throws `IllegalStateException(NO_SESSION_CONTEXT_MESSAGE)` when absent, reproducing that impossibility as a fail-closed invariant instead of a blank context; `store(request, context)` writes back only to an EXISTING session and never creates one. Consumed by all five session-reading controllers (`MenuController`, `AccountController`, `CardController`, `TransactionController`, `BillPaymentController`) | `source → target` |
| `carddemo-common/.../dto/SessionContextSupportTest.java` — 6 cases pinning the fail-closed contract: context present, no session, session without the attribute, attribute of an unexpected type (the Redis serialization-drift case), write-back to an existing session, and no session creation on write-back | AAP 0.7.1 functional-equivalence verification for the COMMAREA-addressability invariant — rule-mandated test infrastructure with no legacy analogue | `target → rule` |
| CICS session termination — `EXEC CICS RETURN` without a `TRANSID` ended the pseudo-conversation and discarded its COMMAREA, so a signed-off terminal held no residual authority `[app/cbl/COSGN00C.cbl:L231-L237]` | `carddemo-common/src/main/java/com/carddemo/common/security/SessionIndexLogoutHandler.java` — a `LogoutHandler` that calls `SessionPrincipalIndex.deregister(userId, sessionId)` inside Spring Security's logout sequence, where the principal and the session id are both still resolvable; registered at `api-gateway/.../config/SecurityConfig.java:L164`, the single service that terminates logout. This is what makes the previously call-site-less `deregister` live and the index's `sessionsRevoked` count truthful | `source → target` |
| `carddemo-common/.../security/SessionIndexLogoutHandlerTest.java` — 4 cases: de-index on an authenticated logout, no-op with no session, no-op with no authentication, and the revoked-count effect | AAP 0.7.5 observability correctness — the revoked-session count must reflect real revocations; rule-mandated test infrastructure | `target → rule` |
| `frontend/src/testing/sessionHarness.ts` — `resolveSessionFromServer()`, `seedSignedOnSession(userId, role)`, `seedSignedOutSession()`; REPLACES the removed `useSession.ts` `__setSession` mutator | No legacy analogue — a test-only module that places the SPA in the authenticated or server-confirmed signed-out state by driving the PRODUCTION `GET /session` probe through the public `useSession().refresh`, so the shipped session module exports no store mutator and every suite's identity flows through the code that populates the store (AAP 0.7.1: the 17 UI workflows must be verifiable) | `target → rule` |
| `COSGN00C` → `COMEN01C`/`COADM01C` role-conditional `XCTL` transfer, CICS session invalidation on sign-off, and the terminal's inability to run a transaction without an established session `[app/cbl/COSGN00C.cbl:L227,L231-L237]` | `frontend/src/AppComposition.test.tsx` — 7 cases mounting the REAL `App` with nothing under `src` mocked (only `apiClient.defaults.adapter`), against a stub that MODELS the server: the session probe on load, role-resolved entry routing, guard refusal for an unauthenticated caller, the 401 → `/signon` interceptor transition from a mounted page, logout revocation clearing server-side identity, and expiry redirect. Mutation-verified — deleting `probeIdentityOnce`, the `RequireAuth` decision, the `client.ts` expiry fan-out or `MainMenuPage`'s `signOut` each fails cases | `source → target` |
| `frontend/eslint.config.js` — `'src/testing/**'` override extending the Jest-globals block | No legacy analogue — required because `src/testing/sessionHarness.ts` is test infrastructure that is not a `*.test.tsx` file and therefore fell outside the glob granting Jest globals (Rule 5: no unused scaffolding, no suppressed diagnostics) | `target → rule` |

### 37.2 Constructs retired during the remediation — controls repointed

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `app/jcl/CREASTMT.JCL` statement output DD allocation — the batch output destination `[app/jcl/CREASTMT.JCL]` | `carddemo-common/.../batch/BatchOutputPathResolver.java` + `carddemo-common/.../batch/BatchPathConfig.java` binding `carddemo.batch.output-dir`. SUPERSEDES the retired `reporting-service/.../config/StatementOutputResolver.java`, which resolved a caller-supplied path with no traversal defence and had zero production call sites; the live statement writer always used the hardened resolver, so retiring the duplicate removed an unreachable CWE‑22 hazard without moving the control | `source → target` |
| Batch output root configuration — the JCL DD-to-filesystem binding for every batch job `[app/jcl/POSTTRAN.jcl; app/jcl/INTCALC.jcl; app/jcl/CREASTMT.JCL]` | `carddemo-common/.../batch/BatchPathConfig.java` (`@Value("${carddemo.batch.output-dir:}")`). SUPERSEDES the retired `carddemo-common/.../config/BatchOutputConfig.java`, which bound the same root but had no consumers and so could only diverge from the value actually in force | `source → target` |
| `frontend/src/hooks/useSession.ts` `__setSession(user, role)` store mutator (retired) | Control repointed to `frontend/src/testing/sessionHarness.ts` (Section 37.1). The mutator wrote the store directly, so no suite taking identity from it could fail if the SPA's session bootstrap or route guards were removed; the harness drives the same states through the production probe instead | `target → rule` |

### 37.3 The three page suites the review found untraced

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COACTVW` mapset + `COACTVWC` account-view program, transaction `CAVW`, and the regulated-field masking the account mapper applies before a payload leaves the service `[app/bms/COACTVW.bms; app/cbl/COACTVWC.cbl]` | `frontend/src/pages/AccountViewPage.test.tsx` — 24 cases covering the byte-exact `COACTVW` captions, the read-only field set, the `F3`/`F4`/`F5` legend, the not-found and validation messages verbatim, and three masking cases that pin the MASK SHAPES the API actually returns (`***-**-6789`, `***********4321`, `******0042`) and assert no raw regulated identifier appears anywhere in the document | `source → target` |
| `COACTUP` mapset + `COACTUPC` account-update program, transaction `CAUP`, including the read-snapshot-compare-rewrite concurrency pattern and its `DATA-WAS-CHANGED-BEFORE-UPDATE` outcome `[app/bms/COACTUP.bms; app/cbl/COACTUPC.cbl:L517-L523,L4131-L4189]` | `frontend/src/pages/AccountUpdatePage.test.tsx` — 34 cases covering the editable field set, per-field validation messages verbatim, the two-step confirm flow, the 409/412 optimistic-lock conflict banner (`Record changed by some one else. Please review`), and the in-flight duplicate-write guard. The two assertions that froze the ABSENCE of the 401 → `/signon` redirect are removed; that transition is asserted in `AppComposition.test.tsx` against the real interceptor | `source → target` |
| `COCRDLI` mapset + `COCRDLIC` card-list program, transaction `CCLI`, 7 rows per page `[app/bms/COCRDLI.bms; app/cbl/COCRDLIC.cbl]` | `frontend/src/pages/CardListPage.test.tsx` — 23 cases covering the 7-row window, `F7`/`F8` paging against the server, row selection, the empty and error states, and a verbatim-caption group that pins the `COCRDLI` body captions with a NON-normalizing matcher (`getDefaultNormalizer({trim: false, collapseWhitespace: false})`), because these are column-aligned 3270 captions whose spacing is the layout | `source → target` |

### 37.4 Reconciliation of the fourteen absent regression paths (C‑02)

The fourteen paths named in the checkpoint's regression inventory do not exist on disk: they were
consolidated before this pass. Every control they carried is present, and each row below names the
file that now carries it, so the inventory reconciles to the delivered architecture without
reinstating duplicate files. Recreating per-service migrations would give Flyway two sources for the
same objects and reintroduce the split history the consolidation removed.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `CSUSR01Y` security-user record — table creation `[app/cpy/CSUSR01Y.cpy]` | `carddemo-common/src/main/resources/db/migration/V1__create_schema.sql` (`CREATE TABLE IF NOT EXISTS security_users`), plus `V6__security_users_optimistic_lock.sql`. CONSOLIDATES the absent `auth-service/.../V1__create_security_users_table.sql`; this is why `auth-service` owns no migrations despite owning the table | `source → target` |
| `app/data/ASCII` USRSEC user fixtures — user seeding `[app/cpy/CSUSR01Y.cpy]` | `carddemo-common/src/main/resources/db/migration/V3__seed_test_data.sql` (`INSERT INTO security_users`, 11 `{bcrypt}` credential literals across the ten seeded users). CONSOLIDATES the absent `auth-service/.../V2__seed_security_users.sql` | `source → target` |
| `COCOM01Y` COMMAREA carried across pseudo-conversational turns — externalized session store `[app/cpy/COCOM01Y.cpy:L18-L45]` | `carddemo-common/src/main/java/com/carddemo/common/config/SessionRedisConfig.java`. CONSOLIDATES the absent `api-gateway/.../SessionConfig.java`; the configuration is shared by every service that reads the session rather than owned by the gateway alone | `source → target` |
| CICS task/terminal correlation across an `XCTL` chain — request correlation `[app/cpy/COCOM01Y.cpy:L18-L45]` | `carddemo-common/src/main/java/com/carddemo/common/config/CorrelationIdFilter.java` + `CorrelationIdContext.java`. CONSOLIDATES the absent `api-gateway/.../CorrelationIdFilter.java`; placing it in the library is what propagates the correlation id across service boundaries as AAP 0.7.5 requires, which a gateway-only filter could not do | `source → target` |
| `CVACT01Y` account record — table creation, constraints, indexes and seed `[app/cpy/CVACT01Y.cpy:L4-L17]` | `carddemo-common/.../V1__create_schema.sql` (`accounts` + `customers` + FK) and `V3__seed_test_data.sql` (`INSERT INTO accounts`, `INSERT INTO customers`). CONSOLIDATES the four absent account-service migrations `V1`–`V4` | `source → target` |
| `CVACT02Y` card record and `CVACT03Y` card cross-reference — table creation, indexes and seed `[app/cpy/CVACT02Y.cpy; app/cpy/CVACT03Y.cpy]` | `carddemo-common/.../V1__create_schema.sql` (`cards`, `card_xref`, `idx_cards_card_acct_id`, `idx_card_xref_acct_id`, `idx_card_xref_cust_id`), `V3__seed_test_data.sql` (`INSERT INTO cards`, `INSERT INTO card_xref`) and `V7__cards_optimistic_lock.sql`. CONSOLIDATES the four absent card-service migrations `V1`–`V4` | `source → target` |
| `CVTRA05Y` transaction record and `CVTRA06Y` daily-transaction feed — table creation, index and seed `[app/cpy/CVTRA05Y.cpy; app/cpy/CVTRA06Y.cpy]` | `carddemo-common/.../V1__create_schema.sql` (`transactions`, `daily_transactions`, `idx_transactions_card_num`), `V3__seed_test_data.sql` (`INSERT INTO transactions`, `INSERT INTO daily_transactions`) and `V8__transactions_card_fk.sql`. CONSOLIDATES the two absent transaction-service migrations `V1`/`V2` | `source → target` |

### 37.5 Build-time dependency-vulnerability gate

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| No legacy analogue — the mainframe stack had no package manager and therefore no transitive dependency graph to scan; every dependency the target introduces is a green-field addition (AAP §0.5.2) | `pom.xml` — the non-default `dependency-scan` Maven profile wiring `org.owasp:dependency-check-maven:13.0.0`'s `check` goal to `verify`, with `failBuildOnCVSS` 7 (CVSS v3 HIGH boundary), HTML + JSON reports, and test/provided scopes skipped because they never ship in a service image. Invoked as `mvn -B -P dependency-scan verify`; inactive by default so the ordinary build stays offline-reproducible | `target → rule` |
| No legacy analogue — the SPA's npm dependency graph is invisible to the Maven gate and carries its own registry advisory database | `frontend/package.json` — the `audit` script (`npm audit --audit-level=high`), deliberately NOT chained into `test` or `build`, holding the same severity boundary as the Maven gate. Invoked as `npm run audit` | `target → rule` |

## 38. Present-on-Disk Target Coverage — Every Remaining File

Sections 1 through 37 enumerate the complete legacy inventory and the delivered screen,
security and batch work. This section closes the REVERSE direction at file granularity
for every remaining target file on disk, so that no file present in the repository is absent
from this matrix. Each row names the file, states what it is, and — where it re-expresses a
legacy construct — names that construct; a file that exists only to satisfy a user-specified
rule or the standalone-operation requirement carries `target → rule`.

### 38.1 Service application entry points

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Legacy `CAVW`, `CAUP` | `account-service/src/main/java/com/carddemo/account/AccountServiceApplication.java` — Bootstraps the Spring Boot application context for account view (CICS CAVW) and account update (CICS CAUP) operations, wiring shared carddemo-common entities, observability, and exception handling | `source → target` |
| Legacy `CM00`, `COMEN01C`, `CA00`, `COADM01C` | `api-gateway/src/main/java/com/carddemo/gateway/GatewayApplication.java` — Executable entry point for the CardDemo API Gateway, a Spring Cloud Gateway Server WebMVC (servlet) edge router that also serves the menu-navigation endpoints re-platforming legacy CICS transactions CM00 (COMEN01C) and CA00 (COADM01C) | `source → target` |
| Legacy `COSGN00C`, `CC00` | `auth-service/src/main/java/com/carddemo/auth/AuthServiceApplication.java` — Bootstrap the CardDemo Authentication microservice (re-platforms COBOL `COSGN00C` / CICS transaction `CC00`); wires the shared carddemo-common entities and configuration, this service's JPA repositories, observability … | `source → target` |
| Legacy batch tier `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBACT04C`, `CBCUS01C` — operator-submitted JCL job streams with no CICS transaction | `batch-service/src/main/java/com/carddemo/batch/BatchServiceApplication.java` — Bootstraps the CardDemo batch tier (interest calculation and data-management jobs) as a resident Spring Boot application that also exposes Actuator HTTP endpoints for health and metrics | `source → target` |
| Legacy `COBIL00C`, `CB00` | `billpay-service/src/main/java/com/carddemo/billpay/BillPaymentServiceApplication.java` — Bootstraps the Spring Boot application context for the online bill-payment operation (legacy CICS `COBIL00C`, transaction `CB00`), wiring the shared `carddemo-common` entities, observability … | `source → target` |
| Legacy `CCLI`, `CCDL`, `CCUP` | `card-service/src/main/java/com/carddemo/card/CardServiceApplication.java` — Bootstraps the Spring Boot application context for card list (CICS CCLI), card detail (CICS CCDL), and card update (CICS CCUP) operations, wiring the shared carddemo-common JPA entities, observability configuration, and global exception handling | `source → target` |
| Legacy `CR00` | `reporting-service/src/main/java/com/carddemo/reporting/ReportingServiceApplication.java` — Bootstraps the Spring Boot application context for online report-request handling (CICS CR00) and for the on-demand Spring Batch statement-generation job (text + HTML), wiring the shared carddemo-common entities, repositories, observability … | `source → target` |
| Legacy `CT00`, `CT01`, `CT02`, `DALYREJS` | `transaction-service/src/main/java/com/carddemo/transaction/TransactionServiceApplication.java` — Bootstraps the Spring Boot application context for online transaction list (CICS CT00), view (CICS CT01), and add (CICS CT02) operations, and for the on-demand Spring Batch transaction-posting job, wiring shared carddemo-common entities … | `source → target` |
| Legacy `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C` (transactions `CU00`–`CU03`) | `user-service/src/main/java/com/carddemo/user/UserServiceApplication.java` — Bootstraps the Spring application context for the `user-service` tier, registers the shared `com.carddemo.common.domain` JPA entities, enables the `com.carddemo.user.repository` Spring Data repositories … | `source → target` |

### 38.2 Request and response DTO carriers

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Legacy `COSGN00`, `COSGN00C`, `CC00` | `auth-service/src/main/java/com/carddemo/auth/dto/SignonRequestDto.java` — Request body for the CardDemo Authentication service endpoint `POST /auth/signon`. Re-platforms the input side of the CICS `COSGN0A` map (mapset `COSGN00`) received by legacy program `COSGN00C` (transaction `CC00`) … | `source → target` |
| Legacy `COSGN00C`, `CC00`, `CA00`, `COADM01C` | `auth-service/src/main/java/com/carddemo/auth/dto/SignonResponseDto.java` — Success response body for `POST /auth/signon`, re-platforming the successful-sign-on routing of legacy CICS program `COSGN00C` (transaction `CC00`). Conveys the authenticated user id, the granted user type … | `source → target` |
| Legacy `COBIL00`, `CB00`, `COBIL00C` | `carddemo-common/src/main/java/com/carddemo/common/dto/BillPaymentRequestDto.java` — Inbound request DTO for the COBIL00 bill-payment screen (CICS transaction `CB00`, legacy program `COBIL00C`). Carries the account whose outstanding balance is to be paid in full together with the operator's Y/N confirmation … | `source → target` |
| Legacy `COBIL00`, `CB00`, `COBIL00C` | `carddemo-common/src/main/java/com/carddemo/common/dto/BillPaymentResponseDto.java` — Outbound response DTO for the COBIL00 bill-payment screen (CICS transaction `CB00`, legacy program `COBIL00C`). Echoes the paid account and its balance and, on a confirmed payment … | `source → target` |
| Legacy `COCRDLIC`, `CCLI` | `carddemo-common/src/main/java/com/carddemo/common/dto/CardListItemDto.java` — Outbound single-row DTO for the card list screen (COCRDLIC, CICS CCLI, seven rows per page). Carries the three columns the legacy list map surfaces per row: owning account id, card number, and active status | `source → target` |
| Legacy `COCRDLIC`, `CCLI`, `COCRDSLC`, `COCRDUPC` | `carddemo-common/src/main/java/com/carddemo/common/dto/CardListResponseDto.java` — Outbound DTO for the card list screen (`COCRDLIC`, CICS `CCLI`). Wraps the ordered page slice of card rows together with the paging state the legacy screen keeps in the COMMAREA (`WS-CA-SCREEN-NUM`, `CA-NEXT-PAGE-EXISTS`) … | `source → target` |
| Legacy `COTRN02`, `CT02`, `COTRN02C`, `CVTRA05Y` | `carddemo-common/src/main/java/com/carddemo/common/dto/TransactionAddRequestDto.java` — Inbound request DTO for the COTRN02 add-transaction screen (CICS transaction `CT02`, legacy program `COTRN02C`). Carries the editable transaction fields entered on the 3270 map (`ADD-TRANSACTION`) that populate a new `TRAN-RECORD` (copybook … | `source → target` |
| Legacy `COTRN02`, `CT02`, `COTRN02C` | `carddemo-common/src/main/java/com/carddemo/common/dto/TransactionAddResponseDto.java` — Outbound response DTO for the COTRN02 add-transaction screen (CICS transaction `CT02`, legacy program `COTRN02C`). Returns the identity of the newly posted transaction and the verbatim confirmation banner the legacy program built with its `STRING` … | `source → target` |
| Legacy `COTRN00`, `CT00`, `COTRN00C` | `carddemo-common/src/main/java/com/carddemo/common/dto/TransactionListItemDto.java` — Outbound single-row DTO for the COTRN00 transaction-list screen (CICS transaction `CT00`, legacy program `COTRN00C`). Carries the four columns the 3270 map surfaces per row: the transaction id … | `source → target` |
| Legacy `COTRN00`, `CT00`, `COTRN00C` | `carddemo-common/src/main/java/com/carddemo/common/dto/TransactionListRequestDto.java` — Inbound request DTO for the COTRN00 transaction-list screen (CICS transaction `CT00`, legacy program `COTRN00C`). Carries the stateless paging cursor that the legacy pseudo-conversational flow held in the COMMAREA program area (first/last … | `source → target` |
| Legacy `COTRN00`, `CT00`, `COTRN00C` | `carddemo-common/src/main/java/com/carddemo/common/dto/TransactionListResponseDto.java` — Outbound response DTO for the COTRN00 transaction-list screen (CICS transaction `CT00`, legacy program `COTRN00C`). Wraps the ordered page of transaction rows together with the stateless paging cursor the client echoes on the next turn: the first … | `source → target` |
| Legacy `COTRN01`, `CT01`, `COTRN01C`, `CVTRA05Y` | `carddemo-common/src/main/java/com/carddemo/common/dto/TransactionViewResponseDto.java` — Outbound response DTO for the COTRN01 transaction-view screen (CICS transaction `CT01`, legacy program `COTRN01C`). Echoes the full posted transaction record (copybook `CVTRA05Y`) back to the client exactly as the 3270 map displays it … | `source → target` |
| Legacy `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C` | `carddemo-common/src/main/java/com/carddemo/common/dto/UserResponseDto.java` — Outbound DTO for the administrator user screens (COUSR00C/COUSR01C/COUSR02C/COUSR03C, CICS CU00-CU03). Carries only the non-secret user fields; the encoded password is never surfaced | `source → target` |

### 38.3 Domain exceptions carrying legacy reject conditions

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Legacy `CBTRN02C` reject code `103` — `IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)` ELSE branch `[app/cbl/CBTRN02C.cbl:L414-L421]` | `carddemo-common/src/main/java/com/carddemo/common/exception/AccountExpiredException.java` — Models the CBTRN02C daily-transaction posting reject for a transaction received after the account expiration date (the legacy `ACCT-EXPIRAION-DATE` ELSE branch … | `source → target` |
| Legacy `CBTRN02C` reject code `102` — the `WS-TEMP-BAL` cycle-figure over-limit test `[app/cbl/CBTRN02C.cbl:L403-L412]` | `carddemo-common/src/main/java/com/carddemo/common/exception/OverLimitException.java` — Models the CBTRN02C over-limit posting reject — a daily transaction whose computed cycle balance would exceed the account credit limit | `source → target` |
| Legacy `CBTRN02C` reject codes `100`/`101` and the CICS `RESP(DFHRESP(NOTFND))` / VSAM file-status `23` not-found condition `[app/cbl/CBTRN02C.cbl:L380-L397]` | `carddemo-common/src/main/java/com/carddemo/common/exception/RecordNotFoundException.java` — Signals that a keyed record lookup found no matching record, modeling the legacy CICS `RESP(NOTFND)` / VSAM not-found outcome for account, customer, card, cross-reference … | `source → target` |
| Legacy `CBTRN02C` reject trailer — the 80-byte validation trailer appended to the 350-byte transaction to form the 430-byte `DALYREJS` record `[app/cbl/CBTRN02C.cbl:L176-L182]` | `carddemo-common/src/main/java/com/carddemo/common/exception/TransactionRejectException.java` — Models a CBTRN02C daily-transaction posting validation reject. Carries the numeric reject reason code (100-103) and its exact description, as captured by the legacy `WS-VALIDATION-FAIL-REASON` and `WS-VALIDATION-FAIL-REASON-DESC` fields. Rejected … | `source → target` |

### 38.4 Mappers and fixed-width record formatters

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Legacy `COACTVWC`, `CAVW`, `COACTUPC`, `CAUP` | `account-service/src/main/java/com/carddemo/account/mapper/AccountMapper.java` — Hand-written, stateless mapper that translates between the shared `carddemo-common` JPA entities (`Account`, `Customer`, `CardXref`) and the account view/update DTOs (`com.carddemo.common.dto`). It re-expresses the field-assembly logic of the legacy … | `source → target` |
| Legacy `COSGN00C`, `CC00`, `CA00`, `CM00` | `auth-service/src/main/java/com/carddemo/auth/mapper/SignonMapper.java` — Maps a verified `SecurityUser` to the sign-on response DTO and the externalized `SessionContext`, re-expressing the `COSGN00C` (CICS transaction `CC00`) sign-on SUCCESS-branch COMMAREA population and post-login routing | `source → target` |
| Legacy `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBCUS01C` | `batch-service/src/main/java/com/carddemo/batch/batch/CobolRecordFormatter.java` — Stateless formatter that renders CardDemo domain records back into their legacy COBOL print/record representations for the data-management read and combine jobs. It reproduces the `DISPLAY` output of `CBACT01C` (the labelled account dump) … | `source → target` |
| Legacy `COBIL00C`, `CB00`, `CSDAT01Y` | `billpay-service/src/main/java/com/carddemo/billpay/mapper/BillPaymentMapper.java` — Stateless, hand-written helper that assembles the fixed-value bill-payment `Transaction` defined by the legacy CICS program `COBIL00C` (transaction `CB00`). It reproduces the record-assembly `MOVE` statements of `COBIL00C PROCESS-ENTER-KEY` verbatim … | `source → target` |
| Legacy `CBSTM03A`, `COSTM01` | `reporting-service/src/main/java/com/carddemo/reporting/mapper/StatementMapper.java` — Hand-written, stateless mapper that assembles the account `StatementModel` (customer identity and address, account id, current balance, FICO score, card number, transaction lines … | `source → target` |
| Legacy `COTRN00`, `CT00`, `COTRN01`, `CT01` | `transaction-service/src/main/java/com/carddemo/transaction/mapper/TransactionMapper.java` — Hand-written, stateless mapper that converts between the carddemo-common `Transaction` entity and the transaction screen DTOs (`com.carddemo.common.dto`) for the online COTRN00 list (`CT00`) … | `source → target` |
| Legacy `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C` | `user-service/src/main/java/com/carddemo/user/mapper/UserMapper.java` — Maps the shared `SecurityUser` entity to and from the shared user request and response DTOs, re-expressing the field-move logic of the legacy administrator-only user-CRUD programs `COUSR00C`/`COUSR01C`/`COUSR02C`/ `COUSR03C` (CICS transactions … | `source → target` |

### 38.5 Spring Data repositories

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Legacy `CUSTFILE`, `COACTVWC`, `COACTUPC` | `account-service/src/main/java/com/carddemo/account/repository/CustomerRepository.java` — Provides primary-key CRUD for the customer master, replacing the legacy keyed VSAM read/rewrite of file `CUSTFILE` performed by `COACTVWC` (`9400-GETCUSTDATA-BYCUST`) and `COACTUPC` (customer `REWRITE`). The account view/update flow reads and … | `source → target` |
| Legacy `USRSEC`, `COSGN00C` | `auth-service/src/main/java/com/carddemo/auth/repository/SecurityUserRepository.java` — Spring Data JPA repository for the `security_users` store; replaces the legacy keyed VSAM read of file `USRSEC` performed by the `READ-USER-SEC-FILE` paragraph of `app/cbl/COSGN00C.cbl`. Extending `JpaRepository` inherits the standard CRUD surface … | `source → target` |
| Legacy `CUSTFILE`, `CBCUS01C` | `batch-service/src/main/java/com/carddemo/batch/repository/CustomerRepository.java` — Persistent access to the `customers` table, replacing the legacy VSAM CUSTFILE (KSDS) access used by the CBCUS01C read/print batch program. Single-record lookup and persistence operations are inherited from `JpaRepository` | `source → target` |
| Legacy `DISCGRP`, `CBACT04C` | `batch-service/src/main/java/com/carddemo/batch/repository/DiscGroupRepository.java` — Replaces VSAM DISCGRP keyed access for the interest-rate lookup in the interest-calculation batch job (`CBACT04C`). The lookup uses the inherited `findById(DiscGroupId)`; the `DEFAULT` account-group fallback is applied by the service layer | `source → target` |
| Legacy `CBACT04C`, `PRTCATBL` | `batch-service/src/main/java/com/carddemo/batch/repository/TranCatBalRepository.java` — Replaces legacy VSAM TCATBAL (KSDS) sequential-by-key access for the batch tier. It is the account-ordered driving reader source for the interest-calculation job (`CBACT04C`), which performs a control-break on the account identifier … | `source → target` |
| Legacy `CUSTFILE`, `CBSTM03A` | `reporting-service/src/main/java/com/carddemo/reporting/repository/CustomerRepository.java` — Spring Data JPA repository for the `Customer` master, replacing the legacy keyed VSAM `CUSTFILE` read performed while assembling statements (`CBSTM03A` `2000-CUSTFILE-GET`). The customer is fetched by its `CUST-ID` key derived from the owning … | `source → target` |
| Legacy `CBTRN02C` | `transaction-service/src/main/java/com/carddemo/transaction/repository/TranCatBalRepository.java` — Data-access repository for transaction-category balances (`TCATBAL`); replaces VSAM keyed `READ`/`WRITE`/`REWRITE` upsert used by `CBTRN02C` posting. The inherited composite-key `findById` reproduces the `2700-UPDATE-TCATBAL` keyed read (present -> … | `source → target` |
| Legacy `TRANCATG`, `CBTRN03C` | `transaction-service/src/main/java/com/carddemo/transaction/repository/TranCatgRepository.java` — Data-access repository for the transaction-category reference table (`TRANCATG`); replaces the VSAM keyed `READ` used by the `CBTRN03C` transaction-detail report (paragraph `1500-C-LOOKUP-TRANCATG`) to resolve a transaction category to its … | `source → target` |
| Legacy `TRANTYPE`, `CBTRN03C` | `transaction-service/src/main/java/com/carddemo/transaction/repository/TranTypeRepository.java` — Data-access repository for the transaction-type reference table (`TRANTYPE`); replaces the VSAM keyed `READ` used by the `CBTRN03C` transaction-detail report (paragraph `1500-B-LOOKUP-TRANTYPE`) to resolve a two-character transaction-type code to … | `source → target` |
| Legacy `USRSEC`, `COUSR00C`, `COUSR01C`, `COUSR02C` | `user-service/src/main/java/com/carddemo/user/repository/UserRepository.java` — Replaces the legacy keyed/browse VSAM access of file `USRSEC` used by the administrator user-CRUD programs `COUSR00C`/`COUSR01C`/ `COUSR02C`/`COUSR03C`. The inherited CRUD surface covers the keyed insert, update, and delete operations … | `source → target` |

### 38.6 Batch components and job configuration

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Legacy `PRTCATBL`, `TCATBALF`, `CVTRA01Y` | `batch-service/src/main/java/com/carddemo/batch/batch/CategoryBalanceReportWriter.java` — {@code @StepScope} `ItemStreamWriter` that produces the CardDemo Transaction Category Balance report, one fixed-format line per `TranCatBal` record. It is the Java analogue of the legacy `PRTCATBL` job's `STEP10R` DFSORT step … | `source → target` |
| Legacy `COMBTRAN`, `CVTRA05Y` | `batch-service/src/main/java/com/carddemo/batch/batch/CombineTransactionsTasklet.java` — Idempotent, ordered combine pass over the unified `transactions` table; the Java analogue of the legacy `COMBTRAN` job (`app/jcl/COMBTRAN.jcl`). The legacy `STEP05R` sorts the transaction backup concatenated with the system-generated interest … | `source → target` |
| Legacy `DALYTRAN`, `CVTRA06Y`, `CBTRN01C` | `batch-service/src/main/java/com/carddemo/batch/batch/DailyTransactionRecordMapper.java` — Map one fixed-length line of the daily-transaction feed (`DALYTRAN`, copybook `CVTRA06Y`, RECLN 350) into a `DailyTransaction`. It is the record-parsing counterpart of the sequential `READ DALYTRAN-FILE` in the legacy `CBTRN01C` batch program: each … | `source → target` |
| Legacy `CBTRN01C`, `CBTRN02C` | `batch-service/src/main/java/com/carddemo/batch/batch/DailyTransactionValidationProcessor.java` — Validates each daily-transaction feed record by confirming that its card cross-reference exists and, when it does, that the referenced account exists; migrated from the legacy batch program `CBTRN01C`. This is a validation-read step only … | `source → target` |
| Legacy `CBACT04C`, `TRAN-RECORD` | `batch-service/src/main/java/com/carddemo/batch/batch/InterestPostingItem.java` — Immutable carrier conveying the per-row result of the monthly interest calculation from {@code InterestItemProcessor} to {@code InterestTransactionWriter}. Each instance bundles the values that flow between the interest computation … | `source → target` |
| Legacy `CBACT04C` | `batch-service/src/main/java/com/carddemo/batch/batch/InterestTransactionWriter.java` — Spring Batch `ItemWriter` that completes the monthly interest job by persisting each computed interest `com.carddemo.common.domain.Transaction` and then applying the per-account balance roll-up with current-cycle zeroing. It is the Java … | `source → target` |
| Legacy `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBCUS01C` | `batch-service/src/main/java/com/carddemo/batch/batch/RecordDumpItemWriter.java` — Restartable `ItemStreamWriter` that prints each record of a data-management read job to a controlled output file, reproducing the `DISPLAY` dump of the legacy read/print COBOL programs (`CBACT01C`, `CBACT02C`, `CBACT03C` … | `source → target` |
| Legacy `CBTRN03C`, `TRANTYPE`, `TRANCATG`, `XREFFILE` | `batch-service/src/main/java/com/carddemo/batch/batch/TransactionReportItemProcessor.java` — Spring Batch item processor that resolves the descriptive fields of the transaction-detail report for one posted `Transaction`. It is the Java analogue of `CBTRN03C`'s per-record lookups `1500-A-LOOKUP-XREF` (card cross-reference to account id) … | `source → target` |
| Legacy `INTCALC`, `CBACT04C`, `TCATBALF` | `batch-service/src/main/java/com/carddemo/batch/config/InterestCalculationJobConfig.java` — Wire the interest-calculation `Job` and its single per-account control-break chunk `Step`, re-platforming the legacy JCL job stream `app/jcl/INTCALC.jcl` (which runs `PGM=CBACT04C` with `PARM='2022071800'`) and the COBOL program … | `source → target` |
| Legacy `CBTRN02C`, `DALYTRAN`, `DALYTRAN-RECORD`, `ACCOUNT-RECORD` | `transaction-service/src/main/java/com/carddemo/transaction/batch/PostingItem.java` — Immutable carrier flowing from the transaction-posting validation `ItemProcessor` to the posting and reject `ItemWriter`s. Each instance holds one daily-transaction record together with the verdict of the legacy `CBTRN02C` `1500-VALIDATE-TRAN` step: … | `source → target` |
| Legacy `CBTRN02C`, `TRANSACT` | `transaction-service/src/main/java/com/carddemo/transaction/batch/TransactionPostingItemWriter.java` — Post a validated daily transaction as one atomic unit — update the transaction-category running balance, update the account balances, then insert the transaction-master row … | `source → target` |

### 38.7 Domain services and security helpers

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Legacy `USRSEC` | `carddemo-common/src/main/java/com/carddemo/common/security/PasswordEncoderFactory.java` — Central factory for the single, tuned password-encoding policy shared by every CardDemo service that authenticates users, replacing the legacy RACF/USRSEC plaintext comparison. It returns a Spring Security `DelegatingPasswordEncoder` that stores an … | `source → target` |
| Legacy `CBSTM03A`, `CUSTFILE`, `ACCTFILE` | `reporting-service/src/main/java/com/carddemo/reporting/service/StatementService.java` — Read-only statement assembly orchestration re-platformed from the legacy batch statement engine `CBSTM03A` (paragraph `1000-MAINLINE` with its `2000-CUSTFILE-GET` / `3000-ACCTFILE-GET` / `4000-TRNXFILE-GET` / `5000-CREATE-STATEMENT` reads). For a … | `source → target` |

### 38.8 Frontend abend-containment component

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Legacy `COCRDUPC` `ABEND-ROUTINE` — `IF ABEND-MSG EQUAL LOW-VALUES MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG`, then `EXEC CICS SEND FROM(ABEND-DATA) ... ERASE` and `EXEC CICS ABEND ABCODE('9999')` `[app/cbl/COCRDUPC.cbl:L1531-L1552]`, laid out by `app/cpy/CSMSG02Y.cpy` | `frontend/src/components/ErrorBoundary.test.tsx` — Verify the SPA's error containment and its telemetry: a render-time exception is caught and answered with the 24x80 frame carrying the verbatim legacy abend text on the line-23 message region rather than a blank device … | `target → source (derived)` |
| Legacy `COCRDUPC` `ABEND-ROUTINE` — `IF ABEND-MSG EQUAL LOW-VALUES MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG`, then `EXEC CICS SEND FROM(ABEND-DATA) ... ERASE` and `EXEC CICS ABEND ABCODE('9999')` `[app/cbl/COCRDUPC.cbl:L1531-L1552]`, laid out by `app/cpy/CSMSG02Y.cpy` | `frontend/src/components/ErrorBoundary.tsx` — Contain an unrecoverable client-side failure and report it. A render-time exception in any of the seventeen screens is caught here and answered with the 24x80 frame carrying the legacy abend text on the line-23 message region … | `target → source (derived)` |

### 38.9 Frontend build and entry assets

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Standalone-operation requirement (AAP 0.2.1) — the target stack must build and run on its own | `frontend/index.html` — The single HTML document the SPA mounts into — the `#root` element `main.tsx` renders the terminal frame inside, plus the document title and viewport meta. No legacy analogue: BMS sent a 24x80 datastream to a 3270 terminal, not a document to a browser | `target → rule` |
| Standalone-operation requirement (AAP 0.2.1) — the target stack must build and run on its own | `frontend/public/favicon.svg` — Browser tab icon for the SPA. No legacy analogue: a 3270 session had no document chrome | `target → rule` |
| Standalone-operation requirement (AAP 0.2.1) — the target stack must build and run on its own | `frontend/tsconfig.json` — TypeScript compiler contract for the application sources — strict mode, the JSX transform and the module resolution that both the Vite build and the `tsc --noEmit` gate consume | `target → rule` |
| Standalone-operation requirement (AAP 0.2.1) — the target stack must build and run on its own | `frontend/tsconfig.node.json` — TypeScript compiler contract for the build-tooling sources (`vite.config.ts`), kept separate so Node-targeted configuration is not compiled with the browser settings | `target → rule` |

### 38.10 Kubernetes Service objects

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-account-service.yaml` — the `Service` object giving `account-service` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-api-gateway.yaml` — the `Service` object giving `api-gateway` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-auth-service.yaml` — the `Service` object giving `auth-service` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-batch-service.yaml` — the `Service` object giving `batch-service` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-billpay-service.yaml` — the `Service` object giving `billpay-service` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-card-service.yaml` — the `Service` object giving `card-service` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-postgres.yaml` — the `Service` object giving `postgres` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-reporting-service.yaml` — the `Service` object giving `reporting-service` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-transaction-service.yaml` — the `Service` object giving `transaction-service` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |
| Deployment requirement (AAP 0.2.1, 0.3.2) — cluster-internal name resolution, which the CICS region performed through its own program and file tables rather than through a network service | `k8s/service-user-service.yaml` — the `Service` object giving `user-service` a stable in-cluster DNS name and port, consumed by the sibling `Deployment` manifests already rowed in Section 10 | `target → rule` |

### 38.11 Non-functional verification harness

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Non-functional targets of AAP 0.7.1 — p95 under 200 ms at 150 concurrent users — which the mainframe met through CICS region tuning and for which no test artifact was ever committed | `perf/carddemo-online-load.js` — the k6 harness driving all 17 screens through the api-gateway on the SPA's own session-cookie and CSRF contract, with the 200 ms target declared as a per-endpoint threshold so a run that misses it exits non-zero | `target → rule` |

### 38.12 Shared test harnesses and test profiles

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `account-service/src/test/resources/application-test.yml` — the `test` Spring profile for `account-service`: the datasource, Flyway and session settings its own suites run against | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `auth-service/src/test/java/com/carddemo/auth/AbstractIntegrationTest.java` — Shared base class for auth-service integration tests. It binds the shared, already-migrated PostgreSQL (`postgres:18`) container from | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `auth-service/src/test/resources/application-test.yml` — the `test` Spring profile for `auth-service`: the datasource, Flyway and session settings its own suites run against | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `batch-service/src/test/java/com/carddemo/batch/testsupport/AsciiFixtures.java` — Let batch tests assert byte-identical output against the ACTUAL `app/data/ASCII/*.txt` records the legacy loader jobs consumed, instead of against expectations invented inside the test. The files are read from the repository working tree (they are … | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `billpay-service/src/test/resources/application-test.yml` — the `test` Spring profile for `billpay-service`: the datasource, Flyway and session settings its own suites run against | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `card-service/src/test/resources/application-test.yml` — the `test` Spring profile for `card-service`: the datasource, Flyway and session settings its own suites run against | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `carddemo-common/src/test/java/com/carddemo/common/testsupport/CardDemoSchemaMigrations.java` — Give integration tests the exact schema a deployment gets, so that Hibernate `ddl-auto: validate` verifies the entity-to-migration contract instead of validating against a schema the test itself manufactured. Every service entity-scans … | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `carddemo-common/src/test/java/com/carddemo/common/testsupport/MigratedSchemaContainer.java` — Let every integration test in a module share one real database whose schema comes exclusively from :java:class:`CardDemoSchemaMigrations`, so Hibernate `ddl-auto: validate` verifies the entity-to-migration contract and no test has to fabricate DDL … | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `reporting-service/src/test/resources/application-test.yml` — the `test` Spring profile for `reporting-service`: the datasource, Flyway and session settings its own suites run against | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `transaction-service/src/test/resources/application-test.yml` — the `test` Spring profile for `transaction-service`: the datasource, Flyway and session settings its own suites run against | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `user-service/src/test/java/com/carddemo/user/AbstractIntegrationTest.java` — Shared Testcontainers base for full-context user-service integration tests. Starts a single, singleton PostgreSQL 18 container (PostgreSQL only -- the user-service is stateless … | `target → rule` |
| Verification requirement (AAP 0.7.1 — migrated behaviour must be provable by test) | `user-service/src/test/resources/application-test.yml` — the `test` Spring profile for `user-service`: the datasource, Flyway and session settings its own suites run against | `target → rule` |

### 38.13 Test suites — reverse direction

| Target (on disk) | What it pins | Direction |
|------------------|-----------------------|-----------|
| `account-service/src/test/java/com/carddemo/account/controller/AccountControllerTest.java` | `@WebMvcTest` web-slice verification of `AccountController`, the REST facade migrated from the legacy CICS account programs -- account view (`CAVW` / `COACTVWC`, `GET /accounts/{id}`) and account update (`CAUP` / `COACTUPC` … | `target → source` |
| `account-service/src/test/java/com/carddemo/account/integration/AccountViewUpdateIT.java` | Full-context `@SpringBootTest` + Testcontainers PostgreSQL integration test for the migrated CardDemo account view (`GET /accounts/{id}`, CICS `CAVW`, program `COACTVWC`) and account update (`PUT /accounts/{id}`, CICS `CAUP` … | `target → source` |
| `account-service/src/test/java/com/carddemo/account/integration/AtomicRollbackIT.java` | Full-context integration test proving that the migrated account update flow (`PUT /accounts/{id}`, CICS transaction `CAUP`, program `app/cbl/COACTUPC.cbl`) preserves the legacy multi-file logical unit of work. The legacy dual `REWRITE` -- ACCTFILE … | `target → source` |
| `account-service/src/test/java/com/carddemo/account/integration/PiiAtRestSweepIT.java` | Prove the fix for the reported defect where every seeded account and card read failed with `JpaSystemException: Error attempting to apply AttributeConverter`. The Flyway seed inserts the sensitive customer columns with raw SQL … | `target → rule` |
| `account-service/src/test/java/com/carddemo/account/integration/SeededPiiRoundTripIT.java` | Verify that a customer row created by the committed Flyway seed migrations can be read back through JPA even though the seed stores the sensitive columns (`cust_ssn`, `cust_govt_issued_id` … | `target → rule` |
| `account-service/src/test/java/com/carddemo/account/mapper/AccountMapperTest.java` | Pure JUnit 5 + AssertJ unit test for the hand-written `AccountMapper`. It locks the two highest-risk fidelity contracts of the account service: (1) the five monetary fields are normalized to `BigDecimal` scale 2 by truncation toward zero … | `target → source` |
| `account-service/src/test/java/com/carddemo/account/repository/AccountRepositoryIT.java` | Testcontainers-backed persistence integration test for `AccountRepository`, covering the VSAM `ACCTFILE` -> PostgreSQL `accounts` migration behind `COACTVWC`/`COACTUPC`. Verifies that the five `NUMERIC(12,2)` money columns round-trip at scale 2 and … | `target → source` |
| `account-service/src/test/java/com/carddemo/account/repository/CardXrefRepositoryIT.java` | Testcontainers-backed persistence integration test for `CardXrefRepository`, covering the VSAM `CXACAIX` alternate index -> PostgreSQL `card_xref` migration. Verifies the custom `findFirstByXrefAcctIdOrderByXrefCardNumAsc` derived query for known … | `target → source` |
| `account-service/src/test/java/com/carddemo/account/repository/CustomerRepositoryIT.java` | Testcontainers-backed persistence integration test for `CustomerRepository`, covering the VSAM `CUSTFILE` -> PostgreSQL `customers` migration. Verifies that a customer row round-trips through the JPA layer on its non-sensitive fields | `target → source` |
| `api-gateway/src/test/java/com/carddemo/gateway/GatewayApplicationTests.java` | Boots the full api-gateway Spring context with a mocked Redis connection factory so the whole re-platformed wiring (Spring Cloud Gateway server-webmvc routing, Spring Security, Spring Session … | `target → source` |
| `api-gateway/src/test/java/com/carddemo/gateway/config/BatchLaunchRouteAuthorizationTest.java` | Pin the gateway authority of the batch job-launch routes, the same way `StatementRouteAuthorizationTest` pins the statement stream. Eight of the nine launchable jobs re-platform JCL streams (`INTCALC`, `READACCT`, `READCARD`, `READCUST`, `READXREF` … | `target → source` |
| `api-gateway/src/test/java/com/carddemo/gateway/config/GatewayRoutesConfigTest.java` | Verifies the api-gateway declarative downstream route table (`spring.cloud.gateway.server.webmvc.routes` in `application.yml`) binds the eight expected service routes to their base URIs, leaves the locally-served `/menu` and `/admin` paths unrouted … | `target → rule` |
| `api-gateway/src/test/java/com/carddemo/gateway/config/SessionAuthorizationTest.java` | Regression guard for the gateway's session-based authorization. A caller that has completed sign-on carries ONLY the externalized COMMAREA-equivalent {@code SessionContext} in the shared session … | `target → source` |
| `api-gateway/src/test/java/com/carddemo/gateway/config/StatementRouteAuthorizationTest.java` | Pin the gateway authority of the statement job-stream route. `CORPT00` is the only report surface an ordinary signed-on user reached, while the `CREASTMT` / `CBSTM03A` statement stream had no online transaction and was submitted by an operator … | `target → source` |
| `api-gateway/src/test/java/com/carddemo/gateway/controller/MenuControllerTest.java` | Verifies the MenuController servlet re-platforming of legacy CICS menu programs COMEN01C (main menu, CM00) and COADM01C (admin menu, CA00): menu enumeration, option/key validation, PF3 navigation, downstream routing … | `target → source` |
| `api-gateway/src/test/java/com/carddemo/gateway/controller/SessionControllerTest.java` | Pin the server-authoritative identity probe that replaces the COMMAREA `CDEMO-USER-ID` / `CDEMO-USER-TYPE` fields the legacy screens received on every pseudo-conversational turn. The single-page application resolves who is signed on from this route … Pins `COCOM01Y` COMMAREA session context carried across pseudo-conversational turns. | `target → source` |
| `auth-service/src/test/java/com/carddemo/auth/AuthServiceApplicationConfigurationTest.java` | Guard the observability wiring of the sign-on service. The service declares no correlation filter of its own, so the shared `WebObservabilityConfig` registration is the only thing that puts a correlation id in the MDC and in the `traceId` of an … | `target → rule` |
| `auth-service/src/test/java/com/carddemo/auth/DocumentedCredentialsSmokeIT.java` | Smoke-test the credentials README-target.md publishes under "Default Login Credentials" against the Flyway-seeded database, so the documented primary entry path can never silently stop working (every documented credential once returned 403 because … | `target → source` |
| `auth-service/src/test/java/com/carddemo/auth/config/SecurityConfigTest.java` | Verification of the auth-service `SecurityConfig` {@code SecurityFilterChain} — the replacement for the RACF / `CARDDEMO.CSD` route-and-role definitions (AAP 0.4.4) … | `target → source` |
| `auth-service/src/test/java/com/carddemo/auth/controller/AuthenticationControllerTest.java` | Web-layer (`@WebMvcTest`) HTTP-contract tests for `AuthenticationController`, the CardDemo sign-on endpoint `POST /auth/signon` (CICS transaction `CC00`, program `COSGN00C`). Loads only the controller slice … | `target → source` |
| `auth-service/src/test/java/com/carddemo/auth/repository/SecurityUserRepositoryIT.java` | Testcontainers integration test verifying the VSAM `USRSEC` -> PostgreSQL `security_users` migration. Extends `AbstractIntegrationTest` to boot the full auth-service context against shared `postgres:18` + `redis:8` containers under the `test` … | `target → source` |
| `auth-service/src/test/java/com/carddemo/auth/security/PasswordEncoderConfigTest.java` | Verification of `PasswordEncoderConfig`, the auth-service bean definition that supplies the single credential-verification policy replacing the legacy RACF/`USRSEC` plaintext comparison (`COSGN00C` L223 … | `target → source` |
| `auth-service/src/test/java/com/carddemo/auth/security/UserDetailsServiceImplTest.java` | Unit tests for `UserDetailsServiceImpl`, the Spring Security bridge re-platforming the legacy `COSGN00C` sign-on credential lookup and role assignment (`app/cbl/COSGN00C.cbl` … | `target → source` |
| `auth-service/src/test/java/com/carddemo/auth/service/AuthenticationServiceTest.java` | Pure Mockito unit tests for `AuthenticationService`, the sign-on business-logic service that re-platforms the `PROCESS-ENTER-KEY` / `READ-USER-SEC-FILE` paragraphs of legacy COBOL program `COSGN00C` (CICS transaction `CC00`). All collaborators are … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/BatchServiceApplicationConfigurationTest.java` | Guard the COMPOSITION of the batch service, which no test of a single class can observe. Every `@Controller` under `com.carddemo.batch.controller` is discovered by component scan and handed to a real `RequestMappingHandlerMapping` … | `target → rule` |
| `batch-service/src/test/java/com/carddemo/batch/DataManagementJobIT.java` | Launch each `DataManagementJobConfig` job against a real PostgreSQL carrying the schema the committed Flyway migrations produce, over a small deterministic data set this class seeds itself in `@BeforeEach` (so the scenarios are independent of both … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/InterestCalculationJobIT.java` | Boot the full `batch-service` Spring context against a real PostgreSQL Testcontainer, seed business data, launch `interestCalculationJob` synchronously and deterministically … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/batch/CobolRecordFormatterTest.java` | Verify that the data-management jobs reproduce byte-identical legacy output. Every fixed-width renderer is asserted against the COMMITTED `app/data/ASCII` fixtures the legacy loaders consumed - a fixture line is parsed field by field into the domain … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/batch/CombineTransactionsTaskletTest.java` | Verify the `COMBTRAN` combine step: every posted transaction is written to the resolved output file as its 350-byte `CVTRA05Y` record in ascending `TRAN-ID` order, the repository is paged until a short page ends the loop … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/batch/DailyTransactionRecordMapperTest.java` | Verify the `CVTRA06Y` 350-byte `DALYTRAN-RECORD` parse that feeds the `CBTRN01C` validation job: every field is taken from its declared column range, the `S9(09)V99` amount is decoded from its trailing sign overpunch at exact scale two … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/batch/DataManagementProcessorsTest.java` | Verify :class:`DailyTransactionValidationProcessor` (`CBTRN01C` ordered cross-reference then account lookup, which reports but never filters a record) and :class:`TransactionReportItemProcessor` (`CBTRN03C` `1500-A/B/C` lookups … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/batch/DataManagementWritersTest.java` | Verify the printed output of the three writers the data-management jobs use - :class:`RecordDumpItemWriter` (the `CBACT01C`/`CBACT02C`/ `CBACT03C`/`CBCUS01C` dumps with their `START/END OF EXECUTION OF PROGRAM` banners) … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/batch/TransactionDetailReportWriterTest.java` | Verify the `CBTRN03C` transaction-detail report the `TRANREPT` job produces: the 133-byte line width of every line, the `1110-WRITE-HEADERS` block, the `1120-WRITE-DETAIL` column layout … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/config/BatchLaunchAuthorizationTest.java` | Pin the per-job authority of the batch launch surface INSIDE `batch-service` itself, so the control holds for a request that reaches this service directly over the internal network rather than through the gateway. The eight operator jobs re-platform … | `target → source` |
| `batch-service/src/test/java/com/carddemo/batch/config/JobSchedulingCorrelationTest.java` | Tests the correlation-id hygiene of the batch launch path. A launch obtains a correlation id for the job, but the launching thread is a pooled container thread: if the launcher seeds that thread's MDC and never clears it … | `target → rule` |
| `batch-service/src/test/java/com/carddemo/batch/controller/BatchControllerTest.java` | Tests for the batch launch surface that makes the configured job streams reachable at runtime. Before it existed every request under `/batch` answered `404` … | `target → source` |
| `billpay-service/src/test/java/com/carddemo/billpay/BillPaymentIntegrationIT.java` | Testcontainers PostgreSQL integration test for the `billpay-service` migration of CICS program `COBIL00C` (transaction `CB00`). Boots the full `BillPaymentServiceApplication` context against a real `postgres:18` database and verifies the behaviours … | `target → source` |
| `billpay-service/src/test/java/com/carddemo/billpay/controller/BillPaymentControllerTest.java` | Web-slice (`@WebMvcTest`) tests for `BillPaymentController`, the REST adapter (`POST /billpay`) re-platforming the legacy CICS bill-payment program `COBIL00C` (transaction `CB00`). Each scenario stubs the mocked `BillPaymentService` … | `target → source` |
| `card-service/src/test/java/com/carddemo/card/controller/CardControllerTest.java` | Spring MVC web-slice specification for `CardController`, the REST facade that re-platforms three legacy CICS card transactions: `CCLI` / `COCRDLIC` (card list) as `GET /cards`, `CCDL` / `COCRDSLC` (card detail) as `POST /cards/detail` … | `target → source` |
| `card-service/src/test/java/com/carddemo/card/mapper/CardMapperTest.java` | Pure JUnit 5 / AssertJ unit specification for the hand-written `CardMapper`. Instantiated directly with `new CardMapper()` (no Spring context, no Mockito, no Testcontainers, no database) … | `target → source` |
| `card-service/src/test/java/com/carddemo/card/repository/CardRepositoryIT.java` | Persistence-slice tests for `CardRepository`, exercising the derived query that replaces the legacy VSAM `CARDAIX` alternate-index browse and the primary-key read that replaces the `CARDDATA` KSDS key access | `target → source` |
| `card-service/src/test/java/com/carddemo/card/repository/CardXrefRepositoryIT.java` | Persistence-slice tests for `CardXrefRepository`, exercising the card-to-customer-to-account cross-reference (legacy `CVACT03Y` / `CXACAIX`) lookups that enforce referential integrity during transaction validation | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/batch/FailedOutputCleanupListenerTest.java` | Unit tests for the failed-output cleanup listener, which stops a failed batch step from leaving behind a file that reads as a completed run | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/config/CardDemoErrorControllerTest.java` | Verify `CardDemoErrorController` renders the documented `ErrorResponse` envelope for a container-level error dispatch, replacing Spring Boot's abbreviated `{timestamp,status,error,path}` body so only one error shape exists in the API contract | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/config/ContainerErrorReportConfigTest.java` | Verify the container-level error valve renders the documented CardDemo error envelope as JSON. A request the container rejects while parsing - an encoded path separator, for example - never reaches the Spring dispatcher … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/config/CorrelationIdFilterTest.java` | Unit tests for the SHARED :class:`CorrelationIdFilter` that every CardDemo service now registers through `WebObservabilityConfig`. Covers the documented resolution precedence (inbound `X-Correlation-Id` first, then the W3C `traceparent` trace id … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/config/CorrelationIdTaskDecoratorTest.java` | Unit tests for :class:`CorrelationIdTaskDecorator`, the mechanism that carries a request's correlation id onto the worker thread running an asynchronous task so `@Async` methods and asynchronously launched batch jobs log under the id of the request … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/config/DeployedConfigurationValidatorTest.java` | Unit tests for the deployed-configuration guard. They pin the two behaviours QA found missing: a service running a DEPLOYED profile must refuse to start on the connection defaults packaged in `application.yml` … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/config/ErrorEnvelopeTest.java` | Verify that `GlobalExceptionHandler` answers EVERY failure - the CardDemo domain exceptions and the framework MVC failures alike - with the one documented `ErrorResponse` envelope, and that a bean-validation failure surfaces exactly ONE message … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/config/RedisCommandMetricsConfigTest.java` | Guard the Redis command-latency instrumentation the Grafana dashboard template depends on. `observability/grafana-dashboard.json` queries `lettuce_command_completion_seconds_count` … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/config/RequestLoggingFilterTest.java` | Unit tests for :class:`RequestLoggingFilter`, the shared access log that closes the request-logging contract. Asserts that one record is emitted per request, that the level reflects the outcome … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/config/SecurityExceptionHandlerTest.java` | Verify `SecurityExceptionHandler` translates the Spring Security failures raised inside the application into the documented `ErrorResponse` envelope, so a denied or unauthenticated request is never answered with an empty body … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/config/SessionRedisConfigTest.java` | Guard the session-serializer contract asserted by `docs/decision-log.md`: session values must be written as allowlisted JSON by a `GenericJacksonJsonRedisSerializer` … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/config/WebHardeningConfigTest.java` | Unit tests for the shared servlet hardening every service imports, covering the store-nothing `FlashMapManager`: it must be published under the exact name `DispatcherServlet` resolves, report no input flash map, leave an existing session untouched, and create no session to hold an output map. The MVC default read the caller's session on EVERY request, which made each hop write the shared Redis session back when the response committed. | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/constant/LookupCodesTest.java` | Lock the exact size, element shape, boundary membership and immutability of the five `CSLKPCDY` copybook-derived lookup sets so that phone-area, US-state and state+ZIP validation stays behavior-identical to the legacy COBOL `88`-level membership … Pins `CSLKPCDY` lookup-code tables (US state, ZIP-prefix and phone area codes). | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/constant/MenuOptionsTest.java` | Verify, with spec-literal fidelity, that `MenuOptions` reproduces the COBOL copybooks `COADM02Y` (group `01 CARDDEMO-ADMIN-MENU-OPTIONS`) and `COMEN02Y` (group `01 CARDDEMO-MAIN-MENU-OPTIONS`): the declared option counts … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/constant/MessagesTest.java` | Lock the byte-identical values exposed by `com.carddemo.common.constant.Messages` for the verbatim common messages (COBOL `CSMSG01Y` group `CCDA-COMMON-MESSAGES`) and the fixed-width abend diagnostic area (COBOL `CSMSG02Y` group `ABEND-DATA` … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/constant/TitlesTest.java` | Verify that the verbatim, fixed-width `Titles` screen-title constants transformed from COBOL copybook `COTTL01Y` (group `01 CCDA-SCREEN-TITLE`) preserve their original `PIC X(40)` values and their exact 40-character width Pins `COTTL01Y` screen titles. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/crypto/PiiEncryptionKeyTest.java` | Unit tests for the eager PII-key validation that a service performs at startup. Each case asserts one of the misconfigurations QA reported as silently accepted: a missing key, a blank key, key material that is not Base64 … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/crypto/PiiMaskerTest.java` | Verify the single masking rule applied to regulated identifiers that leave the service boundary - all characters except the trailing four are asterisked, the length is preserved so the fixed-width screen semantics of the legacy 3270 maps survive … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/AccountTest.java` | Lock the migration-critical structure of the legacy COBOL `ACCOUNT-RECORD` layout (copybook `CVACT01Y`) as re-expressed by the {@code Account} entity: the five monetary fields must stay `java.math.BigDecimal` backed by `NUMERIC(12,2)` (`@Column` … Pins the `CVACT01Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/CardTest.java` | Lock the mapping contract that migrates the legacy COBOL `CARD-RECORD` layout (copybook `CVACT02Y`, RECLN 150) onto the PostgreSQL `cards` table. The suite enforces three invariants that must survive the mainframe-to-Java migration: (a) the … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/CardXrefTest.java` | Freeze the card-to-customer-to-account cross-reference contract that backs 100% referential integrity in the modernized CardDemo application. The legacy 50-byte `CARD-XREF-RECORD` (copybook `CVACT03Y` … Pins the `CVACT03Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/CustomerTest.java` | Lock the frozen entity contract of the re-platformed COBOL `CUSTOMER-RECORD` (copybook `CVCUS01Y`) and, above all, guarantee the sensitive-field masking mandated by AAP 0.6.7: the social-security number (`custSsn`) and the government-issued id … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/DailyTransactionTest.java` | Lock the field-level contract of the COBOL `DALYTRAN-RECORD` layout (copybook `app/cpy/CVTRA06Y.cpy`, fixed record length 350) as re-expressed in Java, with emphasis on financial-precision fidelity (AAP 0.6.1) and timestamp-width preservation (AAP … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/DiscGroupIdTest.java` | Freeze the three-part disclosure-group identity migrated from the COBOL `DIS-GROUP-KEY` group of `DIS-GROUP-RECORD` (copybook `CVTRA02Y`, RECLN 50): `DIS-ACCT-GROUP-ID PIC X(10)` -> {@code disAcctGroupId} (String) … Pins the `CVTRA02Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/DiscGroupTest.java` | Lock the migration-critical structure of the legacy COBOL `DIS-GROUP-RECORD` layout (copybook `CVTRA02Y`, RECLN 50) as re-expressed by the {@code DiscGroup} entity. The three-part `DIS-GROUP-KEY` (`DIS-ACCT-GROUP-ID`, `DIS-TRAN-TYPE-CD` … Pins the `CVTRA02Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/MonetaryScaleFidelityTest.java` | Guard the highest-risk migration property (AAP 0.6.1, financial precision) globally, by reflection only. Every monetary field across the domain must be a `java.math.BigDecimal` and, where it is persisted by a JPA `@Entity` … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/SecurityUserTest.java` | Lock the migration-critical shape of the security-user entity derived from the legacy COBOL `SEC-USER-DATA` record (copybook `CSUSR01Y`). It verifies: (a) the JPA mapping to the `security_users` table and the `secUsrId` primary key … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/TranCatBalIdTest.java` | Freeze the `@IdClass` contract for the transaction-category-balance composite key that migrates the COBOL `TRAN-CAT-KEY` group of `TRAN-CAT-BAL-RECORD` (copybook `CVTRA01Y`): the three key components `TRANCAT-ACCT-ID PIC 9(11)` … Pins the `CVTRA01Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/TranCatBalTest.java` | Freeze the object-relational mapping that migrates the legacy COBOL `TRAN-CAT-BAL-RECORD` copybook (`app/cpy/CVTRA01Y.cpy`, RECLN 50) onto the transaction-category-balance entity. Two invariants are locked. First … Pins the `CVTRA01Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/TranCatgIdTest.java` | Freeze the two-part `@IdClass` that migrates the COBOL `TRAN-CAT-KEY` group of the `CVTRA04Y` copybook (`TRAN-TYPE-CD` PIC X(02) paired with `TRAN-CAT-CD` PIC 9(04)) onto the identifier for entity `TranCatg`. The key has EXACTLY two fields and must … Pins the `CVTRA04Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/TranCatgTest.java` | Freeze the object-relational mapping that migrates the legacy COBOL `TRAN-CAT-RECORD` copybook (`CVTRA04Y`, RECLN 60) onto the transaction-category reference entity. The record declares the two-part `TRAN-CAT-KEY` group -- `TRAN-TYPE-CD PIC X(02)` … Pins the `CVTRA04Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/TranTypeTest.java` | Freeze the object-relational mapping that migrates the legacy COBOL `TRAN-TYPE-RECORD` copybook (`CVTRA03Y`, RECLN 60) onto the transaction-type reference entity. The record declares `TRAN-TYPE PIC X(02)` (the single-column primary key) and … Pins the `CVTRA03Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/TransactionInsertContractTest.java` | Guard the insert-not-merge contract that keeps a colliding transaction id from silently overwriting an existing financial record. `tran_id` is an application-assigned key drawn from `transaction_id_seq` … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/domain/TransactionTest.java` | Freeze the object-relational mapping that keeps the modern `transactions` table byte-compatible with the legacy COBOL `TRAN-RECORD` layout (copybook `CVTRA05Y`). It verifies that the monetary `tranAmt` field is a `java.math.BigDecimal` mapped to … Pins the `CVTRA05Y` record layout. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/dto/SessionContextTest.java` | Verify the COMMAREA-derived session-context DTO preserves the `COCOM01Y` 88-level mappings verbatim, exposes the field contract with correct Java types, and remains `Serializable` with `serialVersionUID` fixed at `1L` | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/json/CobolWireContractTest.java` | Verify the JSON wire contract of the CardDemo numeric DTO fields: a `PIC 9(n)` identifier is transmitted as a zero-padded string of its declared width and a `PIC S9(p)V99` amount as a plain string carrying its exact scale … Pins The `PIC S9(n)V99 COMP-3` and `PIC X(n)` field pictures of the persistent copybooks, whose wire form AAP 0.3.6 freezes. | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/packaging/DockerBuildContextHygieneTest.java` | Pins the build-context hygiene invariant for every container image the project builds: each image's context must be governed by a `.dockerignore`, and Docker resolves that file relative to the CONTEXT root rather than to the module directory. Nine … | `target → rule` |
| `carddemo-common/src/test/java/com/carddemo/common/security/PasswordEncoderFactoryTest.java` | Unit test for `PasswordEncoderFactory`, the single password-encoding policy that replaces the legacy RACF/`USRSEC` plaintext comparison (`COSGN00C` L223, AAP 0.6.7). It pins the encoding contract (`{bcrypt}` prefix at the tuned strength) … | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/util/DateUtilTest.java` | Verify date validation, century/month/day/leap rules, Lillian day conversion, date-of-birth checks, and result-string formatting migrated from `CSUTLDTC` / `CSUTLDPY` / `CSUTLDWY` | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/ReportControllerTest.java` | Standalone-MockMvc unit tests for `ReportController`, the REST re-platform of CICS transaction `CR00` (legacy program `CORPT00C`, `POST /reports`). The controller is exercised in isolation via `MockMvcBuilders#standaloneSetup` with a Mockito-mocked … | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/ReportMapperTest.java` | Unit tests for `ReportMapper`, which echoes the CORPT00 report-request screen fields onto the response and populates the screen header exactly as `CORPT00C` `3000-SEND-MAP` (L611-628) does | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/ReportServiceTest.java` | Pure Mockito JUnit 5 unit test for `ReportService`, the re-platform of the online report-request program `CORPT00C` (CICS transaction `CR00`). Pins the FROZEN user-facing message literals … | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/ReportingServiceApplicationConfigurationTest.java` | Guard the observability wiring of the reporting service. The service declares no correlation filter of its own, so the shared `WebObservabilityConfig` registration is the only thing that puts a correlation id in the MDC and in the `traceId` of an … | `target → rule` |
| `reporting-service/src/test/java/com/carddemo/reporting/ReportingServiceApplicationIT.java` | Failsafe integration smoke test that boots the full reporting-service Spring context against a real, throwaway PostgreSQL provisioned by Testcontainers (the shared `postgres:18` container carrying the schema the committed Flyway migrations produce). … | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/StatementMapperTest.java` | Pure JUnit 5 unit test for `StatementMapper` that verifies the COBOL-to-Java field-mapping and monetary-precision logic of the account statement mapper: the `firstToken` name/address assembly re-expressing the `CBSTM03A` `STRING ... DELIMITED BY ' … | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/StatementServiceTest.java` | Pure Mockito JUnit 5 unit test for `StatementService` that pins the fidelity-critical orchestration contract re-platformed from the legacy batch statement engine `CBSTM03A` (I/O subroutine `CBSTM03B` … | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/batch/StatementGenerationJobIT.java` | End-to-end integration test for the re-platformed batch statement engine. It boots the full reporting-service Spring context against a real PostgreSQL (the shared `postgres:18` container carrying the schema the committed Flyway migrations produce) … | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/batch/StatementGenerationJobTest.java` | Pure JUnit 5 unit test for `StatementGenerationJob` that verifies the COBOL-to-Java byte-for-byte rendering fidelity of the batch statement engine re-platformed from `CBSTM03A`/`CBSTM03B` with the `COSTM01` record layout: the numeric-edit pictures … | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/config/JobSchedulingConfigIT.java` | Integration test for `JobSchedulingConfig`, the re-platformed `CORPT00C` `WIRTE-JOBSUB-TDQ` submission (`EXEC CICS WRITEQ TD QUEUE('JOBS')` -> asynchronous JES submission -> `JobOperator` … | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/config/JobSchedulingConfigTest.java` | Pure JUnit 5 + Mockito unit test for `JobSchedulingConfig`, the re-platformed `CORPT00C` TDQ `'JOBS'` submission mechanism. It verifies that a statement request launches the `statementGenerationJob` with the two output file names carried as … | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/TransactionServiceApplicationIT.java` | Failsafe integration smoke test that boots the full transaction-service Spring context against a real, throwaway PostgreSQL provisioned by Testcontainers (the shared `postgres:18` container carrying the schema the committed Flyway migrations … | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/batch/DailyTransactionFeedPostingIT.java` | Testcontainers integration test proving the staged `DALYTRAN` feed is reachable end to end: the rows loaded by the shared seed migration into `daily_transactions` are read back through the Spring Data JPA `DailyTransactionRepository` in ascending … | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/batch/DailyTransactionRowMapperTest.java` | Unit tests for :class:`DailyTransactionRowMapper`, which maps a persisted `daily_transactions` row to the `CVTRA06Y DALYTRAN-RECORD` value object the posting job validates | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/batch/PostingJobCompletionListenerTest.java` | Verify the `CBTRN02C` end-of-run contract that AAP §0.6.4 freezes: the `TRANSACTIONS PROCESSED` and `TRANSACTIONS REJECTED` tallies, and the legacy `RETURN-CODE` mapping of `0` for a clean run and `4` when at least one record was rejected … | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/batch/RejectFileItemWriterTest.java` | Verify the `CBTRN02C` `2500-WRITE-REJECT-REC` record shape that AAP §0.6.4 freezes: a 430-byte reject record made of the 350-byte `DALYTRAN-RECORD` image (`CVTRA06Y` … | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/batch/TransactionPostingJobIT.java` | Boot the full transaction-service context against a real PostgreSQL carrying the schema the committed Flyway migrations produce, stage a deterministic `DALYTRAN` feed in the durable `daily_transactions` table … | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/batch/TransactionValidationProcessorTest.java` | Verify the `CBTRN02C` `1500-VALIDATE-TRAN` contract that AAP §0.6.4 freezes: the ordered cross-reference-then-account lookup with its short circuit, the four reject codes 100/101/102/103 with their verbatim descriptions … | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/controller/TransactionControllerTest.java` | Verify the three REST endpoints that replace the CICS transactions `CT00` / `CT01` / `CT02` (`COTRN00C` / `COTRN01C` / `COTRN02C`): `GET /transactions` (query-parameter binding of the list request) … | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/service/TransactionAddInsertContractTest.java` | Verify that adding a transaction performs a genuine INSERT that is flushed inside the service's own try block, so an id that already exists is rejected with the verbatim `COTRN02C` duplicate-key message and the existing financial record is never … | `target → source` |
| `transaction-service/src/test/java/com/carddemo/transaction/service/TransactionIdGenerationIT.java` | Integration test for `COTRN02C` transaction-id assignment. The legacy program browsed to the last record and incremented it; the migration substitutes a database sequence (AAP 0.6.5). That substitution introduced a defect: the sequence counts up … | `target → source` |
| `user-service/src/test/java/com/carddemo/user/UserServiceApplicationConfigurationTest.java` | Guard the observability wiring of the administrator user-management service. The service declares no correlation filter of its own, so the shared `WebObservabilityConfig` registration is the only thing that puts a correlation id in the MDC and in … | `target → rule` |
| `user-service/src/test/java/com/carddemo/user/controller/UserControllerTest.java` | Web-layer ({@code @WebMvcTest}) verification of `UserController` REST endpoints and administrator-only role gating for the user-CRUD service re-platformed from the CICS COBOL programs {@code COUSR00C}-{@code COUSR03C} (transactions {@code … | `target → source` |
| `user-service/src/test/java/com/carddemo/user/integration/UserCrudLifecycleIT.java` | Prove that the administrator user write operations are reachable over HTTP through the real service security chain, closing the reported defect where every `POST`/`PUT`/`DELETE /users` answered 403 because no CSRF token could ever be obtained … | `target → source` |
| `user-service/src/test/java/com/carddemo/user/mapper/UserMapperTest.java` | Pure unit tests for `UserMapper` verifying field-by-field entity/DTO mapping for the admin user-CRUD flows. Confirms the password is never set by `toEntity`, never touched by `apply`, and never exposed by `toResponse`/`toResponseList` … | `target → source` |
| `user-service/src/test/java/com/carddemo/user/repository/UserRepositoryIT.java` | Testcontainers integration test verifying the VSAM `USRSEC` -> PostgreSQL `security_users` user-management migration through the Spring Data JPA {@code UserRepository}. Extends `AbstractIntegrationTest` to boot the user-service context against a … | `target → source` |

## 39. Final Runtime QA Checkpoint — Targets Added

Files created while resolving the final runtime QA report. Each is either derived from a legacy
construct already enumerated above, or is rule-mandated infrastructure with no legacy analogue —
recorded here so the reverse direction stays complete.

### 39.1 Shared components derived from a legacy construct

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COSGN00C` L132 `MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI)` and the 3270 `UCTRAN` map attribute [app/cbl/COSGN00C.cbl] | `carddemo-common/src/main/java/com/carddemo/common/security/UserIdNormalizer.java` — the single definition of the legacy user-id normalisation rule (trim, then upper-case under `Locale.ROOT`), which six sites across four modules had each reimplemented with drifting behaviour. `normalize` preserves `null`; `normalizeToKey` maps `null` to the empty string for lookup keys | `source → target` |
| `COACTUPC` `1200-EDIT-MAP-INPUTS` numeric field literals [app/cbl/COACTUPC.cbl:L1028] | `carddemo-common/src/main/java/com/carddemo/common/config/TypeMismatchMessageResolver.java` — functional seam letting a screen's own validator supply the legacy field message for a value Jackson cannot bind, so a frozen literal stays defined once in the module that owns it instead of being copied into the shared exception handler | `source → target` |
| `COACTUPC` amount and date field messages [app/cbl/COACTUPC.cbl] | `account-service/src/main/java/com/carddemo/account/service/AccountTypeMismatchMessageResolver.java` — binds the account screen's frozen field literals (and derives their `old*` snapshot counterparts) to the shared resolver seam, so `acctCreditLimit: "ABC"` reports `Credit Limit is not valid` rather than a generic unreadable-body message | `source → target` |
| `CORPT00C` asynchronous submission to TDQ `JOBS` [app/cbl/CORPT00C.cbl] | `carddemo-common/src/main/java/com/carddemo/common/exception/UpstreamUnavailableException.java` — distinguishes "the batch submission transport is unreachable" (`503` with `Retry-After`) from "the request was invalid" (`400`), a distinction the single legacy write to the transient data queue could not express | `source → target` |
| CICS pseudo-conversational session rotation on re-authentication [app/cpy/COCOM01Y.cpy:L18-L45] | `carddemo-common/src/main/java/com/carddemo/common/config/RemovedSessionTolerantSessionRepository.java` — `SessionRepository` decorator absorbing exactly the `IllegalStateException("Session was invalidated")` raised when a save addresses a key that session-fixation rotation has already removed, and re-raising everything else, so re-signing on with a live session succeeds without weakening rotation | `source → target` |

### 39.2 Rule-mandated infrastructure with no legacy analogue

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| (no legacy analogue — one documented error contract) | `carddemo-common/src/main/java/com/carddemo/common/config/ErrorEnvelopeWriter.java` — renders the documented `ErrorResponse` envelope for the failures answered OUTSIDE Spring MVC exception handling (a security filter-chain denial, a request over the shared size cap, a throttled request, a rejection the servlet container answers itself), which previously returned an empty body or container markup. Only the status reason phrase is ever used as the message | `target → rule` |
| (no legacy analogue — Observability rule, AAP 0.7.5) | `carddemo-common/src/main/java/com/carddemo/common/config/DatastoreOutageErrorFilter.java` — the ONE filter that answers a session-store, datastore or transaction outage with `503` plus `Retry-After` and a correlated envelope. The failure is raised in the filter layer before any handler is selected, so it is unreachable from `@RestControllerAdvice`; the filter writes the body directly rather than calling `sendError`, which would re-enter the failing filters | `target → rule` |
| (no legacy analogue — deployment topology) | `frontend/docker-entrypoint.d/15-carddemo-resolver.sh` — emits the nginx `resolver` directive from `NGINX_LOCAL_RESOLVERS`, or from the container's own `/etc/resolv.conf` so the SPA's `/api/` proxy re-resolves the gateway hostname per request. Without it nginx caches one address for the life of the worker and a recreated gateway leaves the SPA proxying to a stale IP owned by another service, which answers `401` to everything | `target → rule` |

### 39.3 Test suites — reverse direction

| Target Implementation | Purpose | Direction |
|-----------------------|---------|-----------|
| `carddemo-common/src/test/java/com/carddemo/common/security/UserIdNormalizerTest.java` | Pins the legacy normalisation rule at its single definition: trim-then-upper-case, `Locale.ROOT` independence, `null` preservation in `normalize` versus empty-string mapping in `normalizeToKey` | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/config/RemovedSessionTolerantSessionRepositoryTest.java` | Proves the decorator absorbs exactly the rotation-removed-session message and re-raises every other `IllegalStateException`, so a genuine repository fault is not swallowed | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/config/DatastoreOutageErrorFilterTest.java` | Proves a session-store timeout, a refused connection and a transaction that could not reach the datastore each yield `503` with `Retry-After` and the shared envelope, that a PAN in the path is masked in it, that an already-committed response is re-thrown rather than corrupted, and that a statement the datastore REJECTED is NOT absorbed into an outage | `target → source` |
| `carddemo-common/src/test/java/com/carddemo/common/dto/CardUpdateRequestDtoTest.java` | Bounds all six fixed-width card fields — `cardEmbossedName`, `cardActiveStatus`, `cardExpiraionDate` and their `old*` snapshot counterparts — against the `CVACT02Y` widths, so an over-length value is a field error and never a column-overflow failure | `target → source` |


## 40. Constructs Added by the QA-Remediation Pass (frontend UX and data-domain checkpoint)

Targets added while resolving the runtime QA report raised against the SPA boundary. Rows are
included here so that coverage stays complete in BOTH directions after the pass: each new construct
either traces forward to the legacy behaviour it reproduces, or is recorded as rule-mandated
infrastructure with no legacy analogue. The rationale for each choice is in `docs/decision-log.md`
section 47.

### 40.1 Shared frontend edit, dispatch and cursor primitives

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COMEN01C` / `COADM01C` option-field edit — `PROCESS-ENTER-KEY` right-justifies the two-character `OPTION` field, zero-fills it, tests `IS NOT NUMERIC`, then bounds it against the option count, refusing with `Please enter a valid option number...`; the mapsets declare `OPTION DFHMDF ATTRB=(FSET,IC,NORM,NUM,UNPROT), JUSTIFY=(RIGHT,ZERO), LENGTH=2` `[app/cbl/COMEN01C.cbl:L117-L134; app/cbl/COADM01C.cbl:L112-L155; app/bms/COMEN01.bms:L145-L148]` | `frontend/src/pages/menuOptionEdit.ts` — `OPTION_FIELD_WIDTH`, `CDEMO_MENU_OPT_COUNT` (10), `CDEMO_ADMIN_OPT_COUNT` (4), `MSG_INVALID_OPTION`, `toProgramOption()` reproducing the pad / right-scan / right-justify / zero-fill sequence in four steps and always returning exactly two characters, and `isOptionRefused()`. Shared by `MainMenuPage` and `AdminMenuPage` so one implementation serves both programs, whose `PROCESS-ENTER-KEY` is byte-identical apart from the count | `source → target` |
| `COTRN00C` / `COCRDLIC` filter-field class tests — a `PIC X(n)` filter is tested for not-supplied and for `IS NUMERIC` on its RAW contents, so a field holding a tab or an embedded space is refused rather than treated as empty or as a clean number `[app/cbl/COTRN00C.cbl; app/cbl/COCRDLIC.cbl]` | `frontend/src/pages/screenFilters.ts` — `isFilterNotSupplied()`, `isFilterAllDigits()` and `limitToFieldWidth()`, all operating on the raw value with no trimming, plus the field-width clamp that keeps a typed key inside its declared picture | `source → target` |
| Browse-boundary messages — the list programs emit their end-of-browse literals on the message line when a page-forward or page-back cannot advance `[app/cbl/COTRN00C.cbl; app/cbl/COCRDLIC.cbl; app/cbl/COUSR00C.cbl]` | `frontend/src/components/browseNotices.ts` — the shared browse-boundary literals and the predicate that decides when a paging request is refused in place rather than dispatched, consumed by the three list screens so one wording serves all three | `source → target` |
| CICS attention-identifier dispatch — the terminal sends ONE AID per transmission and the program reads the map exactly as it stood at that moment `[app/cpy/CSSTRPFY.cpy]` | `frontend/src/hooks/useScreenAction.ts` — publishes a page's function-key handler through a ref refreshed during render, so `PFKeyBar` dispatches through the freshest handler on both the `keydown` and legend-click paths while `pfKeys` identity stays stable. This is what makes a keystroke act on the values the operator can see | `source → target` |
| CICS cursor placement — `MOVE -1 TO <field>L` positions the 3270 cursor without moving the operator's view, because the terminal displays all 24x80 at once `[app/cbl/COCRDUPC.cbl:L1210-L1235]` | `placeCursor(el)` in `frontend/src/hooks/useScreenFocus.ts` — `focus({ preventScroll: true })`, the single primitive through which all nine program-driven placements run; `useFocusOnChange` additionally takes a `ready` flag so a placement token is not consumed against a still-disabled input and a repeated refusal re-places the cursor | `source → target` |
| `COUSR02C` / `COUSR03C` immediate effect of user maintenance — the next transaction re-read `USRSEC`, so a demotion or a delete took effect at once `[app/cbl/COUSR02C.cbl; app/cbl/COUSR03C.cbl]` | `frontend/src/hooks/useSelfRevocationExit.ts` — detects that the signed-on operator's own authority was revoked by their own edit and exits through a message that names the cause, rather than letting the next request fail as an unexplained `401` | `source → target` |

### 40.2 Service endpoints and carriers added to restore legacy sequencing

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COACTUPC` `1200-EDIT-MAP-INPUTS` — the ENTER-key edit pass that runs the FICO range, the North-American area-code lookup, the state code and the state-and-zip cross-edit before any write, marking the first failing field `[app/cbl/COACTUPC.cbl:L1462-L1467]` | `POST /accounts/{id}/validate` in `account-service` — runs the program's own edit pass server-side and answers `204` when the input is acceptable or the standard field-marked `400` when it is not, writing nothing. The save path re-runs the same pass against the values being written, because the transport cannot guarantee the two payloads match | `source → target` |
| `COTRN02C` key-then-data ordering — the program edits and resolves the account and card keys FIRST, cross-filling one from the other through the card cross-reference, and only then reads transaction data `[app/cbl/COTRN02C.cbl; app/cpy/CVACT03Y.cpy]` | `GET /transactions/keys` in `transaction-service`, carried by `carddemo-common/src/main/java/com/carddemo/common/dto/TransactionKeyResponseDto.java` — resolves the key pair alone, so supplying an account id cross-fills its card number without reading data the program has not yet decided to read. The five refusal literals are reproduced verbatim | `source → target` |

### 40.3 Rule-mandated error-contract and text-normalisation infrastructure

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `carddemo-common/.../security/RefusalEnvelopeWriter.java` — writes the nine-member `ErrorResponse` envelope by hand (no `ObjectMapper`, because the security-chain collaborators are constructed before any bean), redacting a PAN in the path and writing an absent member as an explicit `null`; consumed by `AuditingAuthenticationEntryPoint`, `AuditingAccessDeniedHandler` and `RateLimitFilter`, all three of which previously answered with NO BODY | No legacy analogue — a CICS program has no HTTP status to classify. Rule-mandated: the Observability rule requires the correlation id to reach the caller, and one error contract is what makes a refusal actionable by the SPA (AAP 0.7.5) | `target → rule` |
| `carddemo-common/.../security/RequestRejectedEnvelopeHandler.java` + `RequestFirewallAutoConfiguration.java` — a `RequestRejectedHandler` installed through a `WebSecurityCustomizer`, so a request the Spring Security firewall refuses is completed INSIDE the request scope with the shared envelope and its correlation id, instead of being deferred to the container's error dispatch after the MDC has been cleared | No legacy analogue — the request firewall guards an attack surface (encoded traversal, control characters in a URL) that a 3270 terminal did not have. Rule-mandated: Observability (a refusal must be joinable to its log record) and the one-error-contract requirement | `target → rule` |
| `carddemo-common/.../json/LegacyTextNormalizer.java` + `LegacyTextModule.java` + `LegacyTextAutoConfiguration.java` — NFC composition plus removal of every Unicode FORMAT (Cf) character on BOTH JSON directions, deliberately leaving CONTROL (Cc) characters in place so the class tests of section 40.1 still refuse a tab | No legacy analogue — EBCDIC display fields cannot carry a bidirectional override or a zero-width space, so the legacy screens had no such exposure. Rule-mandated: AAP 0.7.1 requires the screens to behave identically, and a value that renders as something other than what it is does not | `target → rule` |
| `carddemo-common/.../config/ContainerErrorReportConfig.java` (amended) and the `@Import` of `CardDemoErrorController` + `ContainerErrorReportConfig` in `api-gateway/.../GatewayApplication.java` and `batch-service/.../BatchServiceApplication.java` | Control repointed rather than added: the shared error controller and container valve were already traced, but the two modules that most needed them — the edge a browser talks to, and the batch launch surface — did not activate them, so a container-level dispatch there answered a different shape. The rows for those two files now resolve to modules that activate them | `target → rule` |
| `frontend/nginx.conf` response-header placement — the five security headers declared in every location that serves a response from the SPA container and NOWHERE at the server level, so `location /api/` neither declares nor inherits them | Control repointed rather than added: the headers were already traced as rule-mandated hardening, but the arrangement double-sent each one on every API response with two disagreeing `Permissions-Policy` values. The row now names the placement that sends each header exactly once, with the gateway as the sole author for `/api/` | `target → rule` |

### 40.5 The two BMS message fields, separated

The five mapsets that declare an `INFOMSG` field alongside their line-23 `ERRMSG` field
are traced to the props, class and per-state literals that render them. Every row is
bidirectional: each target construct exists because a named mapset field or program
paragraph requires it, and each named field reaches a target.

| Source Construct | Target Implementation | Direction |
|---|---|---|
| `COACTUP.bms` / `COACTVW.bms` `INFOMSG DFHMDF ATTRB=(ASKIP), COLOR=NEUTRAL, LENGTH=45, POS=(22,23)` | `ErrorBannerProps.infoFieldMessage` rendered as `div.errorBanner.errorBanner--infoField` ABOVE the line-23 row, in `frontend/src/components/ErrorBanner.tsx` | source → target |
| `COCRDLI.bms` `INFOMSG POS=(20,19)`, `COCRDSL.bms` / `COCRDUP.bms` `INFOMSG POS=(20,25)`, all `COLOR=NEUTRAL` | the same `infoFieldMessage` row, published by `CardListPage`, `CardDetailPage` and `CardUpdatePage` | source → target |
| `ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)` on all seventeen mapsets | `renderErrorLine()` in `ErrorBanner.tsx` — one field, one value: error, then browse notice, then greened informational text, then the blank placeholder | source → target |
| `MOVE DFHNEUTR TO INFOMSGC` [app/cbl/COCRDLIC.cbl:L929; app/cbl/COACTVWC.cbl:L570; app/cbl/COCRDSLC.cbl:L556] | `.errorBanner.errorBanner--infoField { color: var(--term-neutral) }` in `frontend/src/index.css` | source → target |
| `MOVE DFHBMDAR TO INFOMSGC` / `INFOMSGA` — the darkened field, which still occupies its row | the same row rendered with empty text and no `role`, reserving one line of the 24-row frame | source → target |
| `MOVE DFHGREEN TO ERRMSGC` [app/cbl/COADM01C.cbl:L148; app/cbl/COMEN01C.cbl:L158; app/cbl/COBIL00C.cbl:L526; app/cbl/CORPT00C.cbl:L448; app/cbl/COTRN02C.cbl:L727; app/cbl/COUSR01C.cbl:L254; app/cbl/COUSR02C.cbl:L371; app/cbl/COUSR03C.cbl:L317] | `ErrorBannerProps.infoMessage`, selected by `.errorBanner[role="status"]:not(.errorBanner--notice):not(.errorBanner--infoField)` | source → target |
| `COCRDUPC` `3250-SETUP-INFOMSG` (L1138-1167), all eight `WHEN` branches | the per-state informational literals in `frontend/src/pages/CardUpdatePage.tsx`: `PROMPT_FOR_SEARCH_KEYS`, `FOUND_CARDS_FOR_ACCOUNT`, `PROMPT_FOR_CHANGES`, `PROMPT_FOR_CONFIRMATION`, `CONFIRM_UPDATE_SUCCESS`, `INFORM_FAILURE` | source → target |
| `COCRDUPC` L685-696 — the no-change branch exits before `SET CCUP-CHANGES-NOT-OK`; L997-998 — the concurrency branch `SET CCUP-SHOW-DETAILS` | `CardUpdatePage` publishing `FOUND_CARDS_FOR_ACCOUNT` for both the unchanged submission and the 409 retry path | source → target |
| `COACTUPC` `3250-SETUP-INFOMSG` L2979-2981 — `WS-INFO-MSG` to `INFOMSGO`, then `WS-RETURN-MSG` to `ERRMSGO` | `AccountUpdatePage` publishing `infoFieldMessage` and `errorMessage` on the same send, rendered in that order | source → target |
| `frontend/src/components/ErrorBanner.test.tsx` — the row-order, reserved-blank-row, no-row-on-the-other-twelve and one-value-`ERRMSG` cases | (the four mapset facts above) | target → source |
| `frontend/src/pages/AccountUpdatePage.test.tsx` — `stacks the INFOMSG line above the ERRMSG line, in the rows COACTUP declares` | `COACTUP.bms` `INFOMSG POS=(22,23)` versus `ERRMSG POS=(23,1)` | target → source |

### 40.4 Test suites added by this pass — reverse direction

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `frontend/src/pages/menuOptionEdit.test.ts` (16 cases, including the decisive `-1` must NOT become `1`), `frontend/src/pages/screenFilters.test.ts`, `frontend/src/components/browseNotices.test.ts`, `frontend/src/hooks/useScreenAction.test.tsx`, `frontend/src/hooks/useSelfRevocationExit.test.tsx`, `frontend/src/components/display.test.ts` | AAP 0.7.1 functional-equivalence verification for the shared edit, dispatch and cursor primitives of section 40.1 — rule-mandated test infrastructure with no legacy analogue | `target → rule` |
| `carddemo-common/.../json/LegacyTextNormalizerTest.java` (10 cases, including the control-character cases that pin the Cc exemption with their COBOL citation), `carddemo-common/.../security/RefusalEnvelopeWriterTest.java` (7 cases pinning the nine-member set IN ORDER, the PAN redaction, and that the writer authors no cache directive and overwrites none), `carddemo-common/.../security/RequestRejectedEnvelopeHandlerTest.java` (7 cases, including that the auto-configuration contributes BOTH the handler and the customizer that installs it) | AAP 0.7.5 and the one-error-contract requirement — rule-mandated test infrastructure with no legacy analogue | `target → rule` |


## 41. QA-Remediation Targets — Deployment Resilience, Outage Contract and Operational Readiness

Targets added or repointed while resolving the eleven runtime QA findings against the complete
deployment / configuration / observability surface. Every row is a target that exists on disk; the
Direction column states whether it traces to a legacy construct, is derived from one, or exists only
because a project rule requires it.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| CICS `SEND MAP` / `RECEIVE MAP` transport between the terminal and the region [app/bms/] | `frontend/docker-entrypoint.d/15-carddemo-resolver.sh` — generates the nginx `resolver` from the container's own `/etc/resolv.conf` so the SPA's `/api` reverse proxy re-resolves the api-gateway per request and survives a gateway rollout; consumed by the wildcard `include` in `frontend/nginx.conf` | target → source (derived from the terminal-to-region transport; no legacy analogue for DNS) |
| CICS pseudo-conversational COMMAREA session store [app/cpy/COCOM01Y.cpy:L18-L45] | `carddemo-common/.../config/DatastoreOutageErrorFilter.java` — renders the documented `ErrorResponse` with HTTP 503 when the externalized session store (or any datastore) is unreachable ahead of the Spring dispatcher; registered by `WebObservabilityConfig` at `HIGHEST_PRECEDENCE + 20`. Tested by `DatastoreOutageErrorFilterTest` | target → source (derived from the COMMAREA replacement's failure mode) |
| CICS `RESP(DFHRESP(...))` file-status handling [app/cbl/COACTUPC.cbl:L3654-L3691] | `GlobalExceptionHandler.handleDatastoreUnavailable` — maps an unreachable datastore (`DataAccessResourceFailureException`, `QueryTimeoutException`) to 503 with the same envelope and message as the filter, leaving the general `DataAccessException` → 500 mapping intact. Tested by `ErrorEnvelopeTest` | source → target |
| `app/jcl/DEFGDGB.jcl` (generation-data group definition) and `app/jcl/TRANBKP.jcl` (daily transaction backup generation) | `k8s/cronjob-postgres-backup.yaml` — the `carddemo-backup` PersistentVolumeClaim plus a daily `pg_dump -Fc` CronJob with retention pruning, and the "Backup and Recovery" section of `README-target.md` carrying the restore procedure and the stated recovery point. AAP 0.4.6 places these jobs outside application-code scope, so they map to deployment infrastructure and documentation rather than to service code | source → target |
| (no legacy analogue — Observability rule, AAP 0.7.5) | `observability/alert-rules.yml` — 8 Prometheus alerting rules over the conditions `observability/grafana-dashboard.json` plots, with the AAP 0.7.1 thresholds (p95 200 ms, the 14400 s batch window); loaded through the `rule_files` entry in `observability/prometheus.yml` and mounted read-only by `docker-compose.yml` | target → rule |
| (no legacy analogue — deployment topology) | `k8s/configmap.yaml` `SERVER_PORT` — pins the single internal container port 8080 for the cluster, so each service's application.yml can carry its workstation port (8081-8088, the ports the gateway's own default route table addresses) without changing any containerPort, probe, Service targetPort or scrape target. The workstation port map is published in `README-target.md` | target → rule |
| (no legacy analogue — Kubernetes resource governance) | The `resources` block on the `await-schema` init container of `k8s/deployment-{auth,user,account,card,billpay,transaction,reporting}-service.yaml` — requests `10m`/`32Mi`, limits `100m`/`64Mi`, verified by running the same image and command under exactly those limits | target → rule |


## 42. API Gateway Route Surface — Every Route to Its Legacy CICS Transaction

The CICS region resolved a four-character transaction identifier to a program through its own
Program Control Table; the migration replaces that lookup with **Spring Cloud Gateway Server
WebMVC**, whose `Path` predicates resolve a URL prefix to a service. Sections 1 and 8 already map
the online programs and the CSD security definitions, but neither enumerates the ROUTES, so the
edge — the one component every online transaction now passes through — could not be checked
transaction by transaction. Each row below names the route as it is declared in
`api-gateway/src/main/resources/application.yml` under
`spring.cloud.gateway.server.webmvc.routes`, the authority `api-gateway`
`config/SecurityConfig.java` requires on that prefix, and the legacy transaction it re-platforms.

**Routed transactions** — the gateway forwards these to a downstream service:

| Source Construct | Target Implementation | Direction |
|---|---|---|
| CICS `CC00` → `COSGN00C` sign-on `[app/cbl/COSGN00C.cbl]` | Route `id: auth-service`, `Path=/auth/**` → `${AUTH_SERVICE_URI}`; `permitAll` (the only unauthenticated prefix, because it establishes the session) | `source → target` |
| CICS `CU00`–`CU03` → `COUSR00C`/`COUSR01C`/`COUSR02C`/`COUSR03C` administrator user CRUD `[app/cbl/]` | Route `id: user-service`, `Path=/users/**` → `${USER_SERVICE_URI}`; `hasRole("ADMIN")`, reproducing the `SEC-USR-TYPE = 'A'` gate `[app/cpy/CSUSR01Y.cpy]` | `source → target` |
| CICS `CAVW` → `COACTVWC` and `CAUP` → `COACTUPC` `[app/cbl/COACTVWC.cbl, app/cbl/COACTUPC.cbl]` | Route `id: account-service`, `Path=/accounts/**` → `${ACCOUNT_SERVICE_URI}`; `hasAnyRole("USER","ADMIN")` | `source → target` |
| CICS `CCLI` → `COCRDLIC`, `CCDL` → `COCRDSLC`, `CCUP` → `COCRDUPC` `[app/cbl/]` | Route `id: card-service`, `Path=/cards/**` → `${CARD_SERVICE_URI}`; `hasAnyRole("USER","ADMIN")` | `source → target` |
| CICS `CT00` → `COTRN00C`, `CT01` → `COTRN01C`, `CT02` → `COTRN02C` `[app/cbl/]` | Route `id: transaction-service`, `Path=/transactions/**` → `${TRANSACTION_SERVICE_URI}`; `hasAnyRole("USER","ADMIN")` for the online inquiry/add surface, with `/transactions/batch/**` carved out to `hasRole("ADMIN")` | `source → target` |
| CICS `CB00` → `COBIL00C` bill payment `[app/cbl/COBIL00C.cbl]` | Route `id: billpay-service`, `Path=/billpay/**` → `${BILLPAY_SERVICE_URI}`; `hasAnyRole("USER","ADMIN")` | `source → target` |
| CICS `CR00` → `CORPT00C` report request `[app/cbl/CORPT00C.cbl]` | Route `id: reporting-service`, `Path=/reports/**` → `${REPORTING_SERVICE_URI}`; `hasAnyRole("USER","ADMIN")`, with `POST /reports/statements` (the `CREASTMT` stream) restricted to `hasRole("ADMIN")` | `source → target` |
| Operator submission of a JCL job stream to JES — no CICS transaction existed, the streams were submitted by an operator `[app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl, app/jcl/CREASTMT.jcl]` | Route `id: batch-service`, `Path=/batch/**` → `${BATCH_SERVICE_URI}`; `POST /batch/jobs/*` restricted to `hasRole("ADMIN")`, with `POST /batch/jobs/transactionDetailReportJob` left at `hasAnyRole("USER","ADMIN")` because `CORPT00C` let a signed-on user request that report | `source → target` |

**Gateway-local endpoints** — served by the gateway itself, not forwarded:

| Source Construct | Target Implementation | Direction |
|---|---|---|
| CICS `CM00` → `COMEN01C` user menu `[app/cbl/COMEN01C.cbl]` | `api-gateway` `controller/MenuController` `GET /menu` + `POST /menu/select`; `hasAnyRole("USER","ADMIN")`. `/menu/select` returns the resolved legacy program name and target route, which is the `XCTL` dispatch the menu performed | `source → target` |
| CICS `CA00` → `COADM01C` administrator menu `[app/cbl/COADM01C.cbl]` | `api-gateway` `controller/MenuController` `GET /admin/menu` + `POST /admin/menu/select`; `hasRole("ADMIN")` via the `/admin/**` rule | `source → target` |
| Browser CSRF double-submit token — no legacy analogue (the 3270 link needed none) | `api-gateway` `controller/CsrfController` `GET /csrf` + `config/CsrfCookieMaterializingFilter`; `permitAll` | `target → rule` |
| COMMAREA identity carried across a pseudo-conversational turn `[app/cpy/COCOM01Y.cpy:L18-L45]` | `api-gateway` `controller/SessionController` `GET /session`, resolving the signed-on identity from the shared Redis session | `source → target` |

**Runtime-verified, not asserted.** Every routed prefix above was exercised through the running
gateway with a distinct `X-Correlation-Id` per request, and the downstream container logs were then
searched for that id, so each row's target is the service that demonstrably handled the request
rather than the service the configuration appears to name: `/auth/signon` → `auth-service`,
`/users` → `user-service`, `/accounts/{id}` → `account-service`, `/cards` → `card-service`,
`/transactions` → `transaction-service`, `/billpay` → `billpay-service`, `/reports` →
`reporting-service`, `/batch/jobs` → `batch-service` — eight prefixes, eight distinct services, no
cross-talk. The four gateway-local endpoints resolved inside `api-gateway` alone, with zero
occurrences of their markers in any downstream container. `POST /menu/select` answered
`{"dispatched":true,"programName":"COACTVWC","targetRoute":"/accounts"}`, so the menu dispatch
names the legacy program it re-platforms.


## 43. QA Remediation — Non-Functional, Resource-Policy and Trace-Hygiene Checkpoint

Targets added or corrected while resolving the runtime and non-functional QA findings of this
checkpoint. The two test files are NEW on disk and are recorded so the coverage assertion above
stays true; the remaining rows refine behaviors of files already enumerated in Sections 10, 11
and 15 rather than introducing new sources. Rationale for each: `docs/decision-log.md` section 40.

### 43.1 Resource policy — one artifact, one footprint in both topologies

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Memory-footprint target of AAP 0.7.1 ("increase under 10%"), for which the mainframe region size is the unmeasurable baseline | `auth-service`, `user-service`, `account-service`, `card-service`, `transaction-service`, `billpay-service`, `reporting-service`, `batch-service`, `api-gateway` `Dockerfile` — one identical `ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=100"` and a shell-expanding `exec` entrypoint in all nine (previously five different states, two with no override hook at all). The percentages resolve against `deploy.resources.limits.memory` in `docker-compose.yml` and `resources.limits.memory` in every `k8s/deployment-*.yaml`, so the SAME image sizes the SAME heap in both runtimes — measured 616 MiB where the unlimited container had sized 30,688 MiB and the 1-CPU container had silently fallen back to SerialGC at 248 MiB | `target → rule` |
| Concurrency and latency targets of AAP 0.7.1 (150 concurrent users, p95 under 200 ms) | `docker-compose.yml` `x-backend-service` `deploy.resources` and the `resources` block of all nine `k8s/deployment-*.yaml` plus `k8s/cronjob-transaction-posting.yaml` — 2 CPU / 1Gi limits, 500m / 512Mi requests, sized from CPU measured PER REQUEST on a running stack (gateway 2.0 ms, read services 3.5-5.5 ms, sign-on 94.8 ms of which ~72.8 ms is the password encoder; 6.3 ms per client request across the tier) so every service sits at or below half utilisation at the measured rate | `target → rule` |
| Externalized COMMAREA session store (AAP 0.6.3), whose CICS analogue was bounded by the region's own storage | `docker-compose.yml` `redis` command and `k8s/deployment-redis.yaml` `args` — `--maxmemory 192mb --maxmemory-policy volatile-ttl` inside a 256 MiB container limit, verified reporting `maxmemory 201326592` against the delivered `0` (unlimited). Eviction touches only TTL-bearing keys, nearest-to-expire first, so a full store sheds the sessions closest to lapsing instead of being OOM-killed with all of them | `target → rule` |

### 43.2 Trace hygiene — the last channel that stored a PAN

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `CARD-NUM PIC X(16)` protection (AAP 0.6.7) and the "no sensitive values in logs" rule, extended to the trace channel `[app/cpy/CVACT02Y.cpy]` | `carddemo-common` `config/ObservabilityConfig.sensitiveTraceAttributeMask` — a shared `ObservationFilter` that redacts PAN-shaped digit runs from every HIGH-cardinality observation value through the same `security/SensitiveDataMasker` the log, audit and error-envelope paths already use, leaving LOW-cardinality values (the Prometheus label set) untouched. Covers all nine processes and both span kinds: the three spans that had carried the raw number — the service's server span, the gateway's server span and the gateway's client span — now read `/cards/************5740` while the templated `uri` tag and span operation names are unchanged | `target → rule` |

### 43.3 Test suites added in this checkpoint — reverse direction

| Target (on disk) | What it pins | Direction |
|------------------|--------------|-----------|
| `carddemo-common/src/test/java/com/carddemo/common/config/GlobalExceptionHandlerPoolExhaustionTest.java` | Pin the graceful-degradation contract for connection-pool exhaustion: a HikariCP acquisition timeout must answer `503` with `Retry-After`, not the unmapped `500` it produced before. The signal arrives two ways — wrapped in spring-tx's `CannotCreateTransactionException` inside a transaction and in `DataAccessResourceFailureException` outside one — and neither is a `DataAccessException`, which is exactly why the generic advice never saw it; the suite covers both wrappings, the depth-bounded cause-chain probe for `java.sql.SQLTransientConnectionException`, and that an unrelated data-access failure still answers `500` | `target → source` |
| `reporting-service/src/test/java/com/carddemo/reporting/config/JobSchedulingBackpressureTest.java` | Pin the statement-submission backpressure contract: submission is admitted or refused immediately rather than blocking the HTTP thread, and a refusal carries the frozen `CORPT00C` message `Unable to Write TDQ (JOBS)...` rather than a new string. Covers the admission `Semaphore` being taken BEFORE the job instance is created (so a refused request records no phantom execution) and the bounded worker/queue that replaced `SimpleAsyncTaskExecutor.setConcurrencyLimit(4)`, whose concurrency limit was a throttle that blocked `execute()` for 27-40 s | `target → source` |
## 44. QA-Testing Checkpoint Remediation Targets — Present on Disk

**Scope of this section.** The targets added or repointed while closing the QA testing report on
the FINAL automated test system. Every row is either rule-mandated build/verification
infrastructure with no legacy analogue, or a test whose *Source Construct* names a behaviour
inside a construct already enumerated once in Sections 1-8. The forward "exactly once"
enumeration is therefore unaffected.

### 44.1 Build-level verification infrastructure

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| Verification requirement (AAP §0.7.1 — "tests continue to pass" is only checkable if every module's tests actually run) | `.mvn/maven.config` — commits `--fail-at-end` so one failing module can no longer halt the reactor and leave later modules' `*IT` classes unexecuted; decision-log §40.1 | `target → rule` |
| Verification requirement (AAP §0.7.1 — ≥50 meaningful scenarios; untested branches must be detectable quantitatively) | Root `pom.xml` `jacoco-maven-plugin` 0.8.15 bindings — `prepare-agent`/`report` for the Surefire tier and `prepare-agent-integration`/`report-integration` for the Failsafe tier, producing `target/site/jacoco` and `target/site/jacoco-it` in every module; decision-log §40.1 | `target → rule` |
| Verification requirement (AAP §0.2.1 — Jest + React Testing Library frontend) | `frontend/package.json` Jest coverage configuration — `coverageProvider: v8`, `collectCoverageFrom`, `coverageDirectory`, `coverageReporters`, a gating `coverageThreshold`, and the `test:coverage` script; decision-log §40.1 | `target → rule` |

### 44.2 Migration-inventory guard and integration-test discovery

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| The committed Flyway migration set as a whole — `V1__create_schema.sql`, `V2__seed_reference_data.sql`, `V3__seed_test_data.sql`, the Java version-4 `SeededPiiEncryptionMigration`, `V5__batch_metadata.sql`, `V6__security_users_optimistic_lock.sql`, `V7__cards_optimistic_lock.sql`, `V8__transactions_card_fk.sql` | `carddemo-common/src/test/java/com/carddemo/common/migration/SchemaMigrationInventoryIT.java` — pins the applied `flyway_schema_history` (versions in installed order, descriptions, `success`), so a dropped, renumbered or re-described migration fails the build; decision-log §40.3 | `target → source` |
| Verification requirement (AAP §0.2.1 — Testcontainers integration tests belong to the `verify` tier) | The seven container-backed suites renamed `*Test` → `*IT`: `billpay-service/.../BillPaymentIntegrationIT.java`, `user-service/.../integration/UserCrudLifecycleIT.java`, `user-service/.../repository/UserRepositoryIT.java`, `auth-service/.../DocumentedCredentialsSmokeIT.java`, `auth-service/.../repository/SecurityUserRepositoryIT.java`, `card-service/.../repository/CardRepositoryIT.java`, `card-service/.../repository/CardXrefRepositoryIT.java`. Each row's *what it pins* is unchanged from its entry in §38.13; only the discovery tier moved. `reporting-service/.../StatementGenerationJobTest.java` deliberately keeps its `*Test` name — it is a pure unit test (21 cases, 0.17 s, no container); decision-log §40.3 | `target → source` |
| `carddemo-common` test-support reachability | `carddemo-common/pom.xml` — TEST-scope `org.postgresql:postgresql`, without which `MigratedSchemaContainer` / `CardDemoSchemaMigrations` are unusable from this module's own tests; decision-log §40.3 | `target → rule` |

### 44.3 Deterministic awaiting of the asynchronous job-launch path

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `CORPT00C` TDQ `'JOBS'` / JES asynchronous submission — the run completes on the system's own initiator, not on the requester's thread `[app/cbl/CORPT00C.cbl; app/proc/REPROC.prc]` | `batch-service/src/test/java/com/carddemo/batch/JobLaunchParameterIT.java` and `reporting-service/src/test/java/com/carddemo/reporting/config/JobSchedulingConfigIT.java` — Awaitility polling of `JobExecution` replaces fixed sleeps, plus `launchIsRunByTheProductionAsyncOperator`, which pins that the launch surface delegates to the production `asyncJobOperator`, that its executor is asynchronous, and that the terminal status is the one recorded in `batch_job_execution`; decision-log §40.4 | `target → source` |
| Awaitility test dependency (no legacy analogue — JES supplied job-completion notification) | `batch-service/pom.xml`, `reporting-service/pom.xml` TEST-scope `org.awaitility:awaitility` (BOM-managed) | `target → rule` |

### 44.4 Per-service session wiring (COMMAREA replacement) coverage

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COCOM01Y` COMMAREA hand-off — the state a program leaves behind must be found by the NEXT program `[app/cpy/COCOM01Y.cpy:L18-L45]` | `account-service`, `billpay-service`, `card-service`, `reporting-service`, `transaction-service` `src/test/java/com/carddemo/<svc>/config/SessionRedisWiringIT.java` — each boots its own production Redis/Spring Session wiring (exclusions cleared) and asserts the repository filter, the Redis-backed repository, the `carddemo:session` namespace, the frozen `CDEMO-USRTYP-*` / `CDEMO-PGM-*` round trip and the allowlisted-JSON payload; decision-log §40.5 | `target → source` |
| Shared Redis test infrastructure (no legacy analogue — the COMMAREA needed no store) | `carddemo-common/src/test/java/com/carddemo/common/testsupport/SessionRedisContainer.java` — one `redis:8` per test JVM, mirroring `MigratedSchemaContainer` | `target → rule` |

### 44.5 Referential-integrity assertions restored to the real constraints

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `CVACT03Y` / `CXACAIX` card-to-customer-to-account cross-reference — the linkage `COACTVWC` reads FIRST and `CBTRN02C` rejects on `[app/cpy/CVACT03Y.cpy; app/cbl/COACTVWC.cbl]` | `account-service/src/test/java/com/carddemo/account/integration/AccountViewUpdateIT.java` (scoped delete + `@AfterAll` restore instead of `TRUNCATE`), `.../integration/AtomicRollbackIT.java` (single row written and removed; idempotent probe constraint) and `.../SecurityContractIT.java` (no `card_xref` DDL) — the `fk_card_xref_cust` / `fk_card_xref_acct` constraints stay intact for every class in the fork, so `CardXrefRepositoryIT`'s foreign-key cases assert the real database behaviour in any class order; decision-log §40.6 | `target → source` |

### 44.6 One authoritative card-list browse contract (`COCRDLIC 9500-FILTER-RECORDS`)

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COCRDLIC` `9500-FILTER-RECORDS` — the card browse filters on the `ACCTSID` / `CARDSID` the operator typed and carries NO user-type branch `[app/cbl/COCRDLIC.cbl:L1382-L1390]` | `card-service/.../service/CardService.java#listCards` (role-independent scope; both docstrings corrected), `card-service/.../controller/CardController.java#listCards` (docstring corrected), and `card-service/src/test/java/com/carddemo/card/service/CardServiceTest.java` — `listCards_unfilteredBrowseIsIdenticalForAnOrdinaryUserAndAnAdministrator` and `listCards_acctFilter_readsNoSessionFieldToScopeTheBrowse` replace the two cases that encoded the superseded per-principal contract; decision-log §11.2 (Q15 row `SUPERSEDED (§19.1)`), §19.1 (`SUPERSEDES the Q15 row in §11.2`) and §40.7 | `source → target` |
| `COCRDLIC` `WS-MAX-SCREEN-LINES VALUE 7` plus the `MAX-SCREEN-LINES + 1` lookahead read that tells the operator a further page exists `[app/cbl/COCRDLIC.cbl:L1382-L1390]` | `card-service/.../service/CardService.java#screenWindow` / `ScreenWindow` — an explicit `offset = (page - 1) * 7` / `limit = 8` `Pageable`, so the offset advances by SEVEN while eight rows are read; `CardServiceTest#listCards_readsOnlyOnePageWindowPlusOneLookaheadRow` and `CardViewUpdateIT#adminListPaginationBoundaries` pin contiguous, gap-free pages over the seeded card master; decision-log §40.7 | `source → target` |


### 44.7 Frontend suite quality: awaited interactions, StrictMode mount and identifier masking

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COACTUP` screen chrome — the message line, the `busy`/keyboard-locked state and the PF-key legend `COACTUPC` re-sends with every map `[app/bms/COACTUP.bms; app/cbl/COACTUPC.cbl]` | `frontend/src/pages/AccountUpdatePage.tsx` — published from `useLayoutEffect`, so the shell paints the frame and its legend together (the choice §17 records for `CardUpdatePage`); decision-log §40.8 | `source → target` |
| `COSGN00C` AID handling — ENTER signs on, PF3 leaves with `CCDA-MSG-THANK-YOU` `[app/cbl/COSGN00C.cbl:L88-L89]` | `frontend/src/pages/SignonPage.test.tsx` — `pressEnterKey` / `pressPf3Key` dispatch inside an awaited `act` scope so the async sign-on and the chrome republish settle inside it; decision-log §40.8 | `source → target` |
| (no legacy analogue — production mounts `<React.StrictMode>` in `frontend/src/main.tsx`) | `frontend/src/StrictModeMount.test.tsx` — mounts the real `App` at all nineteen routes under StrictMode and pins entry-read idempotence, no mount-effect writes, symmetric cleanup and render equivalence | `target → rule` |
| `CVCUS01Y` regulated customer identifiers `CUST-SSN`, `CUST-GOVT-ISSUED-ID`, `CUST-EFT-ACCOUNT-ID` `[app/cpy/CVCUS01Y.cpy]` | `account-service/src/test/java/com/carddemo/account/integration/AccountViewUpdateIT.java#getAccount_masksRegulatedCustomerIdentifiers` — all three masks asserted on the read path with the fixed field width preserved, matching `AccountMapper` L213-216 / L274-277 and `AccountViewPage.test.tsx`; AAP §0.6.7 | `source → target` |


## Coverage Assertion

Sections 12 through 37 additionally record the targets added or corrected during successive
QA-remediation and screen-delivery passes; their rows refine behaviors within constructs already
enumerated above rather than adding new sources, so the forward enumeration remains one row per
legacy construct. Section 38 then closes the reverse direction over every remaining file, and
section 39 accounts for the files added by the final runtime QA checkpoint, and section 40 for
those added by the frontend UX and data-domain checkpoint.

Seven further sections, named rather than numbered, follow this assertion. Six record the rows added
while resolving the QA findings of the 17-screen SPA checkpoint: **Card Screens**, **Transaction
Screens**, **Bill-Payment and Report Screens**, **User Screens**, the **Cross-Cutting
Explainability Backlog**, and the **Cross-Cutting Regression Sweep**. The seventh, **Runtime QA
Checkpoint (23 findings)**, records the rows added while resolving the runtime QA checkpoint,
including one corrected forward row: the `MOVE DFHRED` reach of `CSSETATY` is nine targets on
`COCRDLIC` and 2 inline plus 39 `COPY` expansions on `COACTUPC`, so FIVE programs recolour a data
field rather than the four an earlier census reported (decision log §55.3). Three literals quoted as source constructs in the earlier sections —
`'Card number if supplied must be a 16 digit number'` and `'Account number must be a non zero 11
digit number'` — are cited at their DECLARATION lines: they are 88-level condition names no program
ever `SET`s, they are emitted by no target, and decision log §40.3 and §44.7 record their retirement. Their rows refine behaviors within constructs already enumerated above or
record rule-mandated targets with no legacy analogue, so the forward enumeration remains one row per
legacy construct. Rationale for every one of them lives in decision log sections 40 through 45.

Section 41 covers the targets added by the deployment/observability QA-remediation pass — the nginx
resolver hook, the datastore-outage filter and its advice mapping, the backup CronJob and procedure,
the Prometheus alert rules, the ConfigMap's container-port pin, and the init-container resource
blocks.


Section 42 closes the ROUTE granularity at the edge: every Spring Cloud Gateway route and every
gateway-local endpoint is mapped to the CICS transaction it re-platforms, with the authority
required on it, so the one component every online transaction now traverses is checkable
transaction by transaction rather than only service by service.


Section 43 records the non-functional, resource-policy and trace-hygiene checkpoint — including
the two test files that checkpoint added on disk, so the file reconciliation below stays exact.


The section layout is an ORGANISING device for reading a large matrix — foundation types,
then services, then screens, then the remediation passes — and not a delivery schedule.
The refactor is executed in ONE phase, as AAP 0.4.7 requires: nothing in this matrix is
deferred to a later stage, and every row describes an artifact that is on disk now. Where
a row records a correction, it states what the correction was so the trail stays auditable,
not because the target arrived later than its siblings.

**This matrix asserts 100% bidirectional coverage.** It enumerates the COMPLETE legacy
inventory, maps it to the target design, and accounts for **every target file present on
disk** — measured, not assumed: the file inventory of this repository, excluding the
retained legacy tree (`app/`, `diagrams/`, `samples/`) and the retained legacy root
documents, is reconciled against the rows below with **zero uncovered files**:

- **How a target is NAMED, so the claim is checkable by grep:** a Java or TypeScript target
  is named by its type name, which is also its file's base name (`AccountService` is
  `.../service/AccountService.java`); every other target — manifests, migrations,
  configuration, templates, documentation — is named by its path. So a file is covered when
  the matrix contains either its path or the identifier its file is named after.
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
  added while making the delivered stack run, be observable and be deployable), Section 16
  (offline batch compensator targets), or Section 37 (end-to-end security-gate remediation
  targets — the fail-closed session resolver, the logout de-index handler, the test-only
  session harness and the production-wired composition suite, the opt-in dependency-scanning
  gate, plus the three page suites that pass found untraced), or Section 39 (the frontend UX and
  data-domain checkpoint — the shared menu-option, filter, browse-notice, dispatch and
  cursor primitives, the two endpoints that restore legacy key-then-data and ENTER-edit
  sequencing, the refusal-envelope and request-firewall handlers, the JSON text
  normaliser, and their suites).
- **No row claims coverage the runtime does not deliver.** Where an earlier revision credited
  a component that was present but never activated, the row now names the registration or
  dependency that makes it active and states the observed evidence; the two such rows were
  the distributed-tracing row (Section 10) and the correlation-id row (Section 11). The same
  correction was applied again in Section 16: `FailedOutputCleanupListener` was present on disk
  and registered on NO step, so the row now names the nine steps that register it. Its effect
  was then observed on the running stack rather than assumed: `statementGenerationStep` was made
  to fail at writer open, ended `FAILED`, and the listener removed the partial text artifact the
  step had already created (`removed its incomplete output …`) while leaving that step's own
  `FAILED` status and `ItemStreamException` cause in place; the successful run of the same step
  immediately before it kept both of its artifacts. Section 37.2 applies the same
  discipline to RETIREMENT rather than activation: where a construct was deleted during the
  security-gate remediation, its row is repointed to the file that carries the control instead
  of being dropped, so `BatchOutputConfig`, `StatementOutputResolver` and the `__setSession`
  mutator each resolve to a live target. Section 37.4 does the same for the fourteen regression
  paths that no longer exist on disk: each is reconciled to the consolidated migration or shared
  configuration file that now carries its control, so no control is credited to an absent path.
  Section 40.3 applies it a third time: where this pass found a shared control present but not
  ACTIVATED in a module that needed it — the error controller and container valve at the
  gateway and in batch-service, and the nginx header placement — the row names the
  activation or the placement rather than crediting the file's mere presence. Section 40.5
  applies it a fourth time, to a field rather than a file: the `INFOMSG` field the five
  detail mapsets declare had a rendering target but was being drawn with the other twelve
  screens' `ERRMSG` colour and below their `ERRMSG` row, so its rows now name the position
  and the colour the mapsets declare rather than crediting the fact that the text appeared
  somewhere on the screen.
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

## QA Remediation — Card Screens

Added or re-pointed by the card-screen QA remediation pass. Decision-log section 40
carries the rationale for each.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COCRDLI` `INFOMSG ATTRB=(PROT) COLOR=NEUTRAL LENGTH=45 POS=(20,19)` and `COCRDLIC` `88 WS-INFORM-REC-ACTIONS` `[app/bms/COCRDLI.bms; app/cbl/COCRDLIC.cbl:L115-L116]` | `CardListPage` body element `p.cardList__infoMsg` (`data-testid="card-list-infomsg"`), indented to column 19 in neutral — not the shared line-23 region | `source → target` |
| `COCRDSL` `INFOMSG … LENGTH=40 POS=(20,25)` `[app/bms/COCRDSL.bms]` | `CardDetailPage` body element `p.cardDetail__infoMsg` (`data-testid="card-detail-infomsg"`) | `source → target` |
| `COCRDUP` `INFOMSG … LENGTH=40 POS=(20,25)` and the `WS-INFO-MSG` 88-levels `PROMPT-FOR-SEARCH-KEYS` / `FOUND-CARDS-FOR-ACCOUNT` / `PROMPT-FOR-CHANGES` / `PROMPT-FOR-CONFIRMATION` / `CONFIRM-UPDATE-SUCCESS` / `INFORM-FAILURE` `[app/bms/COCRDUP.bms; app/cbl/COCRDUPC.cbl:L157-L172]` | `CardUpdatePage` body element `p.cardUpdate__infoMsg` (`data-testid="card-update-infomsg"`), driven by the same six states | `source → target` |
| `COCRDUPC` `3300-SETUP-SCREEN-ATTRS` protect/un-protect `EVALUATE` — `DFHBMFSE` / `DFHBMPRF` over the key fields and the detail fields per state `[app/cbl/COCRDUPC.cbl:L1168-L1208]` | `CardUpdatePage` `ScreenState` enum (`Search` / `Edit` / `Confirm`) and the `keysProtected` / `detailsProtected` `readOnly` bindings on all six fields | `source → target` |
| `COCRDUPC` `1210-EDIT-ACCOUNT` + `1220-EDIT-CARD` + the unguarded both-blank cross-field test `[app/cbl/COCRDUPC.cbl:L721-L802, L656-L660]` | `CardUpdatePage` `editSearchKeys()` — first-message-wins with `NO-SEARCH-CRITERIA-RECEIVED` overwriting | `source → target` |
| `COCRDUPC` `1230-EDIT-NAME` (`WS-PROMPT-FOR-NAME`, `WS-NAME-MUST-BE-ALPHA`, `LIT-ALL-ALPHA-FROM`) `[app/cbl/COCRDUPC.cbl:L761-L797, L255-L257]` | `CardUpdatePage` `editMapInputs()` name branch, first of the four detail edits, against `/^[A-Za-z ]+$/` | `source → target` |
| `COCRDUPC` `NO-CHANGES-DETECTED` short-circuit (`FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA) EQUAL FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)`) `[app/cbl/COCRDUPC.cbl:L680-L693]` | `CardUpdatePage` `detailsUnchanged()` and the guard at the head of `handleProcess` | `source → target` |
| `COCRDUPC` `WHEN DATA-WAS-CHANGED-BEFORE-UPDATE → SET CCUP-SHOW-DETAILS` `[app/cbl/COCRDUPC.cbl:L997-L998]` | `CardUpdatePage` HTTP 409 branch — repaint from the read snapshot, withdraw `F5=Save`, no field faulted | `source → target` |
| `COCRDUPC` `CCARD-AID-PFK12 AND NOT CCUP-DETAILS-NOT-FETCHED` `[app/cbl/COCRDUPC.cbl:L418, L954-L964]` | `CardUpdatePage` `handleCancel()` — re-reads the displayed record instead of navigating | `source → target` |
| `CardKeyRequestDto` and the `cardNumber` / `accountId` members of `CardUpdateRequestDto` | (no legacy field — the composite key is moved out of the URL so a PAN is never written to an access log, proxy log or trace; decision log §40.4) | `target → rule` |
| `POST /cards/detail` and `PUT /cards` endpoint shapes | (no legacy analogue — CICS addressed `CCDL` / `CCUP` by transaction id and COMMAREA, not by URL; the shape is chosen so the PAN stays out of every request line) | `target → rule` |
| `CardControllerTest.noCardEndpointAcceptsThePanInItsUrl` and `wireContract.test.ts` "never puts a card number in a request URL or query string" | (no legacy analogue — regression pins for the §40.4 property, in the backend and the frontend respectively) | `target → rule` |
| `COCRDSLC` `2210-EDIT-ACCOUNT` "not supplied" branch → `WS-PROMPT-FOR-ACCT` ('Account number not provided') `[app/cbl/COCRDSLC.cbl:L647-L661]` | `CardDetailPage.validateSearchFilters` — the account key is required, and an all-zeros entry takes this branch | source → target |
| `COCRDSLC` `2200-EDIT-MAP-INPUTS` cross-field test → `NO-SEARCH-CRITERIA-RECEIVED` ('No input received') `[app/cbl/COCRDSLC.cbl:L637-L639]` | `CardDetailPage.validateSearchFilters` — unguarded both-blank literal that replaces both field prompts | source → target |
| `COCRDSLC` `WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM` ("SELECTION CRITERIA ALREADY VALIDATED") `[app/cbl/COCRDSLC.cbl:L336-L347]` | `CardDetailPage` hand-over effect — reads without running the edits | source → target |
| `COCRDLIC` `9500-FILTER-RECORDS` per-record exclusion `[app/cbl/COCRDLIC.cbl:L1382-L1400]` | `CardService.listCards` filter scope — keyed read for a card filter, `findByCardAcctIdOrderByCardNumAsc` for an account filter | source → target |

## QA Remediation — Transaction Screens

Rows added while resolving the `/transactions`, `/transactions/view` and `/transactions/add` findings.
Rationale for each lives in decision log §41.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COTRN02C VALIDATE-INPUT-KEY-FIELDS` — the `EVALUATE` that reads `CXACAIX` / `CCXREF` and moves the counterpart key back into its own map field `[app/cbl/COTRN02C.cbl:L166-L232]` | `TransactionService.resolveAddKey` + `POST /transactions/key` + `TranAddPage.resolveKeys` | source → target |
| `COTRN02C PROCESS-ENTER-KEY` paragraph ORDER — key edits, then data-field guards, then the `CONFIRMI` evaluation `[app/cbl/COTRN02C.cbl:L160-L232]` | `TranAddPage.processEnter` — `validateKeyFields` → `resolveKeys` → `validateDataFields` → confirm | source → target |
| `COTRN02C` `Account ID NOT found...` / `Card Number NOT found...` `[app/cbl/COTRN02C.cbl:L205, L219]` | `TransactionService.resolveCrossReference` `RecordNotFoundException` → HTTP 404 → line 23 (now reachable with data fields empty) | source → target |
| `COTRN02C COPY-LAST-TRAN-DATA` — key resolution, then the eleven `TTYPCD`..`MZIP` MOVEs, then `PROCESS-ENTER-KEY` `[app/cbl/COTRN02C.cbl:L469-L520]` | `TranAddPage.handleCopyLast` — the same three steps in the same order | source → target |
| `COTRN01.bms` thirteen display fields `ATTRB=(ASKIP,NORM)` with no `FSET` `[app/bms/COTRN01.bms:L60-L200]` | `TranViewPage.handleSearch` blank branch calls `reset()`, blanking all thirteen `<dd>` | source → target |
| `COTRN00.bms` row-9 hyphen runs 3 / 16 / 8 / 26 / 12 at columns 2 / 8 / 27 / 38 / 67 `[app/bms/COTRN00.bms:L120-L150]` | `TranListPage` `<colgroup>` widths + `.dataTable--fixed` `table-layout: fixed` + the table's `min-width` floor | source → target |
| `COTRN00C PROCESS-PF7-KEY` / `PROCESS-PF8-KEY` `SET SEND-ERASE-NO TO TRUE` | `TranListPage` `lastPage` state — a failed browse keeps the displayed rows and page indicator | source → target |
| `COTRN00C` `MOVE 0 TO CDEMO-CT00-PAGE-NUM` + conditional increment in `PROCESS-PAGE-FORWARD` | `TranListPage` page indicator showing `0` for an empty result (faithful, not a defect) | source → target |
| `COTRN02.bms` confirm caption `LENGTH=55 COLOR=TURQUOISE POS=(21,6)` `[app/bms/COTRN02.bms:L200-L215]` | `.tranAdd__confirm > label { white-space: pre-wrap }` — the frozen 55 characters wrap rather than clip | source → target |
| `COTRN00C` / `COTRN01C` / `COTRN02C` — zero `DFHRED`, zero `MOVE '*'` | `TranListPage` / `TranViewPage` / `TranAddPage` — `aria-invalid` and the cursor only, no `.fieldError` (decision log §41.3) | source → target |
| `COTRN02C` single `MOVE DFHGREEN TO ERRMSGC` on the successful-write branch `[app/cbl/COTRN02C.cbl:L727]` | `TranAddPage.setInfoMessage` — the one message routed to the green `role="status"` channel; every other message uses the red `role="alert"` channel | source → target |
| `TRAN-ID` `PIC X(16)` `[app/cpy/CVTRA05Y.cpy]` | `TransactionService.pad16` / `inclusiveLowerCursor` width guards — an over-wide digit run misses the read instead of raising HTTP 500 | source → target |
| `XREF-ACCT-ID` `PIC 9(11)` `[app/cpy/CVACT03Y.cpy]` | `TransactionService.ACCT_ID_WIDTH` guard in `resolveCrossReference` — refused by the numeric edit rather than parsed | source → target |
| `GET /transactions/detail?tranId=` endpoint shape | (no legacy analogue — the id is a request parameter so a value holding a separator is not path-normalized out of the API prefix; decision log §41.1) | target → rule |
| `TransactionKeyDto` | (no legacy record — the two key fields of `COTRN2AI` carried in a request body so no card number enters a URL) | target → rule |
| `frontend/nginx.conf` `location /` raw-URI traversal guard | (no legacy analogue — refuses a request the SPA history fallback would otherwise answer with the document; decision log §41.1) | target → rule |
| `TransactionControllerTest.noEndpointTakesTheIdAsAPathSegment` and `wireContract.test.ts` "sends the transaction id as a request parameter, never as a path segment" | (no legacy analogue — regression pins for the §41.1 property, backend and frontend) | target → rule |
| `TranListPage` table `min-width` floor | (no legacy analogue — a 3270 column has a fixed width by construction; the floor reproduces that when the viewport is narrower than the grid, and the wrapper scrolls; decision log §41.4) | target → rule |

## QA Remediation — Bill-Payment and Report Screens

Rows added while resolving the `/billpay` and `/reports` findings.
Rationale for each lives in decision log §42.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `MOVE DFHGREEN TO ERRMSGC OF COBIL0AO` — the ONE colour override in the program, on the successful-payment branch `[app/cbl/COBIL00C.cbl:L526]` | `BillPaymentResponseDto.message` populated together with `transactionId`; `BillPayPage` routes it to the informational region (`role="status"`, `--term-green`) | source → target |
| `ERRMSG POS=(23,1) COLOR=RED LENGTH=78` — the declared colour every other branch keeps `[app/bms/COBIL00.bms:L146-L151]` | `BillPaymentResponseDto.errorMessage`, and `message` without a `transactionId`, both routed to the error region (`role="alert"`, `--term-red`) | source → target |
| `MOVE ACCT-CURR-BAL TO WS-CURR-BAL` / `MOVE WS-CURR-BAL TO CURBALI` before the zero test `[app/cbl/COBIL00C.cbl:L193-L194]` | `BillPaymentService.nothingToPayResponse` and `previewResponse` both carry `currentBalance`; `BillPayPage` repaints the balance on every answered turn | source → target |
| `IF ACCT-CURR-BAL <= ZEROS AND ACTIDINI NOT = SPACES` → `'You have nothing to pay...'` + `MOVE -1 TO ACTIDINL` `[app/cbl/COBIL00C.cbl:L198-L204]` | `BillPaymentService` step 4 returning `nothingToPayResponse` (HTTP 200), with the cursor placed on `acctId` | source → target |
| `ELSE MOVE 'Confirm to make a bill payment...'` + `MOVE -1 TO CONFIRML` `[app/cbl/COBIL00C.cbl:L237-L239]` | `BillPaymentService.previewResponse`, with the cursor placed on `confirm` | source → target |
| `ACCT-ID PIC 9(11)` / `ACTIDIN LENGTH=11` / VSAM `KEYLEN 11` `[app/cpy/CVACT01Y.cpy; app/bms/COBIL00.bms:L86-L90; app/catlg/LISTCAT.txt]` | `BillPaymentService.ACCT_ID_WIDTH` + `isAsciiDigits` in `parseAccountId` — the only accepted key shape | source → target |
| Absence of any numeric or width edit on `ACTIDIN`; every unreadable key answers `'Account ID NOT found...'` `[app/cbl/COBIL00C.cbl:L155-L170, L360-L363]` | `BillPaymentRequestDto.accountId` carrying NO bean-validation constraint, so the service owns the one decision | source → target |
| `'Enter Acct ID:'` `LENGTH=14 POS=(6,6) COLOR=GREEN` `[app/bms/COBIL00.bms:L80-L84]` | `.green` utility on `label[for='billPayAcctId']` | source → target |
| `'Your current balance is: '` `LENGTH=25 POS=(11,6) COLOR=TURQUOISE` `[app/bms/COBIL00.bms:L110-L114]` | `BillPayPage.BALANCE_CAPTION`, 25 characters including the trailing pad column, rendered through `OutputField` under `.detailField > dt { white-space: pre }` | source → target |
| `'Do you want to pay your balance now. Please confirm: '` `LENGTH=53 POS=(15,6)` `[app/bms/COBIL00.bms:L133-L137]` | `.billPay__row > label[for='billPayConfirm'] { white-space: pre-wrap }` | source → target |
| `'(Y/N)'` `COLOR=NEUTRAL POS=(15,63)` `[app/bms/COBIL00.bms:L143]` | `span#billPayConfirmValues`, referenced by the confirm field's `aria-describedby` | source → target |
| `CURBAL ATTRB=(ASKIP,FSET,NORM) COLOR=BLUE` with `COBIL0AO REDEFINES COBIL0AI` `[app/bms/COBIL00.bms:L103-L106; app/cpy-bms/COBIL00.CPY]` | The `response === undefined` branch of `BillPayPage.handleEnter` deliberately leaving the displayed balance in place | source → target |
| `ACURBAL HILIGHT=UNDERLINE` with no `COLOR` versus `CURBAL COLOR=BLUE` `[app/bms/COACTVW.bms:L159-L163; app/bms/COBIL00.bms:L103-L106]` | The bordered green value box on `/accounts` and the plain blue value text on `/billpay` — two declared treatments, preserved | source → target |
| `MOVE DFHGREEN TO ERRMSGC` immediately before the submission acknowledgement `[app/cbl/CORPT00C.cbl]` | `ReportResponseDto.message` + `ReportService.withSuccess`; `ReportPage` routes it to the informational region | source → target |
| Every other `CORPT00C` outcome keeping `ERRMSG COLOR=RED` | `ReportResponseDto.errorMessage` + `ReportService.withMessage` nulling `message` | source → target |
| `SDTMM`/`SDTDD`/`SDTYYYY`/`EDTMM`/`EDTDD`/`EDTYYYY` `ATTRB=(FSET,NORM,NUM,UNPROT) COLOR=GREEN HILIGHT=UNDERLINE`, with no attribute-byte move anywhere in the program `[app/bms/CORPT00.bms:L127-L193; app/cbl/CORPT00C.cbl:L149]` | The six `ReportPage` date inputs, `disabled` only for the shell-wide keyboard lock | source → target |
| `EVALUATE TRUE / WHEN MONTHLYI / WHEN YEARLYI / WHEN CUSTOMI` — the date parts read under the custom branch alone `[app/cbl/CORPT00C.cbl:L212-L256]` | `ReportPage.buildReportRequest` carrying the date parts for `CUSTOM` only | source → target |
| The twelve `MOVE -1 TO <date-part>L` cursor placements and the two composite-edit placements `[app/cbl/CORPT00C.cbl:L264-L297, L334-L376, L403, L423]` | `ReportPage.validateCustomWindow` returning the faulted field, consumed by `placeCursor` — the whole of the fault marking, with no colour change | source → target |
| Row-24 legend `'ENTER=Continue  F3=Back'` and the `DFHENTER`/`DFHPF3`-only AID handling `[app/bms/CORPT00.bms:L226; app/cbl/CORPT00C.cbl:L184-L195]` | `ReportPage`'s two-key `PFKeyBar` — deliberately no `F4=Clear` | source → target |
| `'(Y/N)'` beside `CORPT00`'s confirmation field | `span#reportConfirmValues`, referenced by `#confirm`'s `aria-describedby` | source → target |
| `BillPaymentServiceTest.theThreeInBandOutcomesAreDistinguishableWithoutReadingTheText` | (no legacy analogue — regression pin for the §42.1 channel invariant) | target → rule |
| `ReportServiceTest.theAcknowledgementAndTheRefusalsUseOppositeChannels` | (no legacy analogue — regression pin for the §42.1 channel invariant on the report screen) | target → rule |
| `BillPaymentControllerTest.overWidthAccountIdReachesTheService` | (no legacy analogue — regression pin that the removed width constraint stays removed; §42.3) | target → rule |
| `BillPaymentServiceTest.anAccountIdThatIsNotTheKeyWidthIsNotFound` (eleven value classes) | (no legacy analogue — regression pin for the §42.3 key-shape guard) | target → rule |
| `ReportPage.test.tsx` "ignores a range typed against the Monthly window rather than rejecting it" | (no legacy analogue — regression pin for the §42.6 enterable-but-ignored contract) | target → rule |
| `.fieldError` count of zero asserted on both screens' rejection tests | (no legacy analogue — regression pin for the §42.5 zero-`DFHRED` census) | target → rule |

## QA Remediation — User Screens

Rows added while resolving the `/users`, `/users/add`, `/users/update` and `/users/delete` findings.
Rationale for each lives in decision log §43.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COUSR00C.PROCESS-PAGE-FORWARD` look-ahead `READNEXT` that sets `NEXT-PAGE-YES` only when an eleventh record exists `[app/cbl/COUSR00C.cbl:L307-L316]` | `UserService.LOOK_AHEAD_SIZE`, read by `listUsersFrom` and `pageForward`, setting `UserListResponseDto.nextPage` from the extra row | source → target |
| `COUSR00C.PROCESS-PF8-KEY` boundary literal `'You are already at the bottom of the page...'` with `SET SEND-ERASE-NO TO TRUE` `[app/cbl/COUSR00C.cbl:L270-L276]` | `UserListPage.nextPage` — answers locally, publishes the literal, issues no request, leaves the rows painted | source → target |
| `COUSR00C.PROCESS-PF7-KEY` boundary literal `'You are already at the top of the page...'` `[app/cbl/COUSR00C.cbl:L248-L253]` | `UserListPage.prevPage` — same local answer | source → target |
| `READNEXT-USER-SEC-FILE` `WHEN DFHRESP(ENDFILE)` literal `'You have reached the bottom of the page...'` `[app/cbl/COUSR00C.cbl:L637]` | `UserService` terminal-page message, published on a forward turn that lands on the last page | source → target |
| `COUSR00C.PROCESS-ENTER-KEY` first-match-wins `EVALUATE TRUE` over `SEL0001`..`SEL0010` `[app/cbl/COUSR00C.cbl:L151-L186]` | `UserListPage.handleEnter` first-selected-row lookup, and `rejectedSelectionRow` derived from that same row alone | source → target |
| `MOVE -1 TO USRIDINL` after the invalid-selection literal `[app/cbl/COUSR00C.cbl:L184]` | `useFocusOnChange` on `UserListPage`'s search field — the cursor goes to `USRIDIN`, not to the rejected cell | source → target |
| `COUSR00` row-9 rule runs of 3, 8, 20, 20 and 4 hyphens at columns 5, 12, 24, 48 and 72 `[app/bms/COUSR00.bms]` | `UserListPage.COLUMNS`, its `<colgroup>` widths and `TABLE_MIN_WIDTH_CH` (65 ch) | source → target |
| `COUSR00` `'Search User ID:'` `COLOR=TURQUOISE LENGTH=15 POS=(6,5)` `[app/bms/COUSR00.bms]` | `label.prompt[for=USRIDIN]` on `UserListPage` | source → target |
| `COUSR00` row-24 legend `'ENTER=Continue  F3=Back  F7=Backward  F8=Forward'` `COLOR=YELLOW` `[app/bms/COUSR00.bms]` | `UserListPage`'s four-key `PFKeyBar` in the yellow tone | source → target |
| `COUSR01C.PROCESS-ENTER-KEY` five-way presence edit, in field order `[app/cbl/COUSR01C.cbl:L117-L151]` | `UserAddPage`'s client edits and `UserService.addUser` presence guards — and NO value-set edit on the user type | source → target |
| `COUSR01` `'(A=Admin, U=User)'` `COLOR=BLUE LENGTH=17` `[app/bms/COUSR01.bms]` | `span#usrtype-hint`, the `aria-describedby` target of `#usrtype` | source → target |
| `COUSR02C.UPDATE-USER-INFO` presence edits and field-by-field compare `[app/cbl/COUSR02C.cbl:L177-L213, L219-L234]` | `UserService.updateUser` — presence guards, `passwordSupplied` / `passwordChanged`, and the no-change guard | source → target |
| `COUSR02C` no-change literal `'Please modify to update ...'` with `MOVE DFHRED TO ERRMSGC` `[app/cbl/COUSR02C.cbl:L239-L241]` | `UserService.updateUser`'s refusal, carried on `ErrorResponse.message` and rendered on the line-23 ERROR channel | source → target |
| `COUSR02C` re-display of `SEC-USR-PWD` in `PASSWDO`, which made its password presence edit vacuous | `UpdateUserRequestDto.password` as an OPTIONAL member; a blank field sends nothing and the credential is untouched | source → target |
| `COUSR02`/`COUSR03` `'Enter User ID:'` `COLOR=GREEN LENGTH=14 POS=(6,6)` `[app/bms/COUSR02.bms:L80-L84; app/bms/COUSR03.bms:L80-L84]` | `label.green[for=usridin]` on `UserUpdatePage` and `UserDeletePage` | source → target |
| `COUSR02` 70-character rule `COLOR=YELLOW LENGTH=70 POS=(8,6)` `[app/bms/COUSR02.bms:L94-L95]` | `div.userUpdate__separator.title` | source → target |
| The blank row 7 every 70-character-rule mapset leaves between the entry row and the rule `[app/bms/COBIL00.bms:L94-L95; app/bms/COUSR02.bms:L94-L95; app/bms/COUSR03.bms:L94-L95; app/bms/COTRN01.bms:L96-L97; app/bms/COTRN02.bms:L113-L114]` | `margin-top: var(--term-line)` on the shared rule group in `index.css` | source → target |
| `COUSR02` row-24 legend `'ENTER=Fetch  F3=Save&&Exit  F4=Clear  F5=Save  F12=Cancel'` `[app/bms/COUSR02.bms]` | `UserUpdatePage`'s five-key `PFKeyBar` | source → target |
| `COUSR03C` `WHEN DFHPF5 PERFORM DELETE-USER-INFO`, dispatched unconditionally `[app/cbl/COUSR03C.cbl]` | `UserDeletePage`'s Delete affordance and `F5=Delete` legend, both live whenever the screen is not busy | source → target |
| `COUSR03C.DELETE-USER-INFO` empty-key edit `'User ID can NOT be empty...'` `[app/cbl/COUSR03C.cbl:L174-L192]` | `UserDeletePage.removeUser`'s blank guard — client-side, no request | source → target |
| `COUSR03C.DELETE-USER-SEC-FILE` `WHEN OTHER` literal `'Unable to Update User...'` `[app/cbl/COUSR03C.cbl:L332]` | `UserDeletePage.resolveDeleteMessage` fallback | source → target |
| The CICS keyboard lock that DISCARDS an AID struck while the task runs | `UserDeletePage.deletingRef` — a synchronous in-flight guard, so three activations in one task issue one DELETE | source → target |
| `COUSR03` row 15 painting caption, value and the `(A=Admin, U=User)` literal on one terminal row `[app/bms/COUSR03.bms]` | `div.userDelete__row` holding `dl.userDelete__typeRow` (`display: contents`) beside the hint span, which sits OUTSIDE the description list | source → target |
| `db/migration/V1__create_schema.sql` `chk_sec_usr_type CHECK (sec_usr_type IN ('A','U'))` | The write-time refusal that reports an unrecognised user type through the legacy `'Unable to Add User...'` / `'Unable to Update User...'` literals | target → source |
| `UserListPage.test.tsx` "keeps the rows on screen when a browse fails after one succeeded" | (no legacy analogue — regression pin for the §43.1 held-last-page rule) | target → rule |
| `UserListPage.test.tsx` "answers F8 locally, with no request, when the server reports no further page" | (no legacy analogue — regression pin for the §43.1 local boundary answer) | target → rule |
| `UserListPage.test.tsx` "declares the five column widths and holds the table to their total" | (no legacy analogue — regression pin for the §43.5 BMS-derived column widths) | target → rule |
| `UserListPage.test.tsx` "marks only the first selected row, by flag and description, never by colour" | (no legacy analogue — regression pin for the §43.5 first-match-wins marking and the §43.6 census) | target → rule |
| `UserServiceTest.listUsersFrom_exactlyOnePage_reportsNoFurtherPage` and `pageForward_exactlyOnePage_reportsNoFurtherPage` | (no legacy analogue — regression pins for the §43.1 look-ahead flag) | target → rule |
| `UserServiceTest.updateUser_passwordAbsentNoEdits_reportsPleaseModify` | (no legacy analogue — regression pin that the §43.2 optional password keeps the no-change guard reachable) | target → rule |
| `UserUpdatePage.test.tsx` "updates the name alone, sending no password, when the field is left empty" and "marks the password field as not required, unlike every other entry field" | (no legacy analogue — regression pins for §43.2) | target → rule |
| `PFKeyBar.activatesItselfOnEnter` | (no legacy analogue — a 3270 has no focusable chrome; the exemption keeps the shell's own controls operable without a pointer; decision log §43.7) | target → rule |
| `footer#screenStatusRegion` `tabIndex={-1}` | (no legacy analogue — makes the Observability/accessibility skip target able to hold focus; decision log §43.7) | target → rule |
| `PFKeyBar.test.tsx` "leaves ENTER to the skip link, so the link is operable without a pointer" and "still claims ENTER for the screen when a field holds focus" | (no legacy analogue — regression pins for the §43.7 element-type-scoped exemption, in both directions) | target → rule |
| `.fieldError` count of zero asserted on all four user screens' rejection tests | (no legacy analogue — regression pin for the §43.6 `COUSR` census) | target → rule |


## QA Remediation — Cross-Cutting Explainability Backlog

Rows added while closing the deviations AAP requirement #20 reported as unlogged: the responsive strategy,
the shared shell's keyboard-lock model, the sign-on and menu screens, the account screens, and the
exact-width key rule with the literals it retired. Rationale for each lives in decision log §44.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| The fixed 24-row x 80-column 3270 frame every mapset is written against | `.screen` / `.terminal` `min-height: min(calc(var(--term-rows) * var(--term-line)), 100vh)` with a scrolling `.screen__body`, so a short or narrow viewport contains the shortfall instead of scrolling the frame | source → target |
| A BMS field's declared `POS=` column, which carries meaning a reflow would destroy | The "contain, never reflow" rule: BMS-derived column widths plus the keyboard-operable `.tableScroll` and `.screen__body` scrollers | source → target |
| `COSGN00.bms` nine-line 42-character sign-on note at its declared 20-column offset | `.signon__note { padding-left: min(20ch, 100% - 42ch) }` — the declared offset yields before the art does | source → target |
| `COSGN00.bms` header captions spelled `'Tran :'` / `'Prog :'` / `'Date :'` / `'Time :'` `[app/bms/COSGN00.bms:L33,L46,L56,L69]` | `.appHeader__left/__center/__right { white-space: nowrap }` at every width, with the row becoming a wrapping flex row below `48rem` so a whole cell moves down intact | source → target |
| The 3270 keyboard lock and its `X SYSTEM` operator-information-area indicator | `Layout`'s `p.screen__oia` — a reserved row BELOW the 24 application rows, `role="status" aria-live="polite"`, carrying `X SYSTEM` for exactly as long as a request is in flight | source → target |
| The terminal's DISCARDING of an attention identifier struck while the program runs | `PFKeyBar.aidPendingRef` — a synchronous per-AID latch that refuses a repeat before the rendered lock is committed | source → target |
| A BMS literal field's colour, which does not change when the terminal locks | `.pfKeyBar button:disabled { opacity: 0.55 }` — the inhibited legend is dimmed, never recoloured | source → target |
| The full-map re-send that repaints an unchanged message and re-places the cursor on every turn | `ErrorBanner`'s per-send remount (`key={send-N}`) and `useFocusOnChange` re-firing on `useSendCount()` | source → target |
| `POPULATE-HEADER-INFO`, performed immediately before every `SEND MAP` | `Header` re-capturing the client clock whenever `sendCount` advances — per turn, never ticking | source → target |
| `COTRN01.bms` `TRNIDIN ATTRB=(FSET,IC,NORM,UNPROT)` with `MOVE -1 TO TRNIDINL` on every path `[app/bms/COTRN01.bms:L85; app/cbl/COTRN01C.cbl:L102,L151,L154,L287,L294,L311]` | The screen's own cursor rule, which re-places focus after a pointer-activated legend key rather than retaining it on the key | source → target |
| The one ENTER affordance a 3270 screen has, on line 24 | `PFKeyBar`'s document-level ENTER binding as the ONLY submit path; the three in-body submit controls removed from `SignonPage` and `UserDeletePage` | source → target |
| `COMEN02Y` `CDEMO-MENU-OPT-NUM PIC 9(02)` and `COMEN01C` `STRING ... DELIMITED BY SIZE` `[app/cpy/COMEN02Y.cpy; app/cbl/COMEN01C.cbl:L243-L246]` | `displayZoned(optionNumber, MENU_OPT_NUM_DIGITS)` on both menus, aligning all ten option names on one column | source → target |
| `COMEN01C` `IF WS-OPTION IS NOT NUMERIC` on a numeric-shift field that admits `-` and `.` `[app/cbl/COMEN01C.cbl:L118-L129]` | `sanitizeOption` retaining `[\d.-]` clamped to two characters, plus `editMenuOption` reproducing the edit client-side against `CDEMO-MENU-OPT-COUNT` | source → target |
| `COMEN01C` / `COADM01C` `MOVE CDEMO-TO-PROGRAM` before `RETURN-TO-SIGNON-SCREEN` `[app/cbl/COMEN01C.cbl:L96-L98; app/cbl/COADM01C.cbl:L96-L98]` | `MenuController`'s `PF3` response publishing `programName = COSGN00C`, after `targetRoute` was removed from the wire contract | source → target |
| `COSGN00C` `EXEC CICS ASSIGN SYSID` before the first `SEND` `[app/cbl/COSGN00C.cbl:L202-L203]` | `VITE_SYS_ID` defaulting to the one declared application identity `CARDDEMO` `[app/csd/CARDDEMO.CSD]`, overridable at image build | source → target |
| `COACTVW` two-column geometry — captions at columns 8 and 39, values at columns 23 and 61, the right column `JUSTIFY=RIGHT PICOUT='+ZZZ,ZZZ,ZZZ.99'` `[app/bms/COACTVW.bms]` | `AccountViewPage`'s transcribed two-column grid, which halved the frame from 1036px to 510px and aligned the five money decimal points to a 0.000px spread | source → target |
| `ACSZIPC` `LENGTH=5 JUSTIFY=(RIGHT) POS=(17,73)` against `CUST-ADDR-ZIP PIC X(10)` `[app/bms/COACTVW.bms:L291-L294; app/cpy/CVCUS01Y.cpy:L14]` | `ZIP_FIELD_WIDTH = 5` — the truncating `MOVE` into the symbolic-map field reproduced | source → target |
| A BMS entry field's declared `LENGTH` in character cells | `.charField` — `ch`-unit width with `box-sizing: border-box`, `padding: 0`, a −1px inline-start margin and `max-width: none`; the frame is a border so `.fieldError` survives the focus outline | source → target |
| `MOVE -1 TO <field>L` plus the red attribute, which a single-program screen gets for free | `FieldValidationException` carrying an ordered field list, copied into `ErrorResponse.fieldErrors` and mapped back onto the screen's fields by `SERVER_FIELDS_BY_REQUEST_MEMBER` | source → target |
| `1280-EDIT-US-STATE-ZIP-CD` setting BOTH `FLG-STATE-NOT-OK` and `FLG-ZIPCODE-NOT-OK` `[app/cbl/COACTUPC.cbl]` | `AccountUpdateValidator.editFields(List.of(state, zip), ...)` — one edit faulting two members, cursor field first | source → target |
| The legacy edit running in the SAME task as the send, so a refused value never becomes a file access | The client reproducing every edit it can decide alone (presence, width, FICO range) while the server stays authoritative | source → target |
| `ACCT-GROUP-ID PIC X(10)` as a genuinely optional field `[app/cpy/CVACT01Y.cpy]` | `AccountMapper.blankToNull` — a blank group id round-trips as NULL rather than rewriting the column to `''` | source → target |
| The masked SSN / government id / EFT values the screen receives and submits back unchanged | `PiiMasker.isMaskOf` plus `validateMaskedIdentifiers`, run after the read: this record's own mask round-trips, any other mask-shaped value is refused | source → target |
| `2000-DECIDE-ACTION` `WHEN DATA-WAS-CHANGED-BEFORE-UPDATE SET ACUP-SHOW-DETAILS TO TRUE` `[app/cbl/COACTUPC.cbl:L2611]` | `AccountUpdatePage`'s 409 branch — repaint `originalForm`, restore `PROMPT-FOR-CHANGES`, darken `F5`, leave `F12` live, mark no field | source → target |
| `COACTUP` `INFOMSG POS=(22,23) COLOR=NEUTRAL` and `ERRMSG POS=(23,1) COLOR=RED` `[app/bms/COACTUP.bms:L480-L493]` | The shell's single line-23 `ErrorBanner` carrying both channels with error precedence; `'Changes unsuccessful. Please try again'` published on the informational channel | source → target |
| `CSLKPCDY` `88 VALID-US-STATE-CODE` (56 members) and `88 VALID-US-STATE-ZIP-CD2-COMBO` (240 members), inconsistent with each other `[app/cpy/CSLKPCDY.cpy:L1013,L1073,L1087]` | `LookupCodes` transcribed member for member, inconsistency included — `'AP'` absent from the states, `'AP96'` present in the combinations | source → target |
| `CC-ACCT-ID PIC X(11)` with `CC-ACCT-ID-N REDEFINES ... PIC 9(11)`, edited by `IF CC-ACCT-ID IS NOT NUMERIC` — a class test on an alphanumeric item `[app/cpy/CVCRD01Y.cpy:L34,L36]` | `AccountController.parseAccountId` requiring `\d{11}` and non-zero; `CardController.parseAccountFilter` requiring `\d{11}` at the type boundary; `TransactionService` `ACCT_ID_WIDTH` / `CARD_NUM_WIDTH` / `TRAN_ID_WIDTH` exact-width edits | source → target |
| `COACTVWC` `'Account Filter must  be a non-zero 11 digit number'` `[app/cbl/COACTVWC.cbl:L672]` | `AccountController.MSG_ACCT_FILTER_INVALID`, served by `GET /accounts/{id}` | source → target |
| `COACTUPC` `STRING 'Account Number if supplied must be a 11 digit' ' Non-Zero Number'` `[app/cbl/COACTUPC.cbl:L1806-L1809]` | `AccountController.MSG_ACCT_NUMBER_INVALID`, served by `PUT /accounts/{id}` | source → target |
| `COTRN00C` list-filter edit `'Tran ID must be Numeric ...'` `[app/cbl/COTRN00C.cbl:L206-L219]` | `TransactionService`'s exact-16-digit filter edit, and the retargeted `@Size` messages on `TransactionListRequestDto` | source → target |
| `COTRN01C.PROCESS-ENTER-KEY` — an empty test, then `MOVE TRNIDINI TO TRAN-ID` and READ, with no width edit `[app/cbl/COTRN01C.cbl]` | `TransactionService.viewTransaction` using the trimmed value verbatim; a wrong-width key returns `'Transaction ID NOT found...'` | source → target |
| `COTRN02C` account/card key edits `'Account ID must be Numeric...'` / `'Card Number must be Numeric...'` `[app/cbl/COTRN02C.cbl:L197-L201]` | `TransactionService.resolveCrossReference`'s exact-width edits, with `TransactionAddRequestDto` and `TransactionKeyDto` carrying no `@Size` of their own so every refusal has one shape | source → target |
| `SEARCHED-ACCT-ZEROES` / `SEARCHED-ACCT-NOT-NUMERIC` / `SEARCHED-CARD-NOT-NUMERIC` — declared and never `SET` `[app/cbl/COACTVWC.cbl:L125,L127; app/cbl/COACTUPC.cbl:L493,L495; app/cbl/COCRDSLC.cbl:L144,L146,L149; app/cbl/COCRDUPC.cbl:L189,L191,L194]` | (no target, deliberately — dead 88-levels whose text no program can emit; retired from every endpoint and from the frontend; decision log §44.7) | source → target |
| `COSGN00C` / `COMEN01C` / `COADM01C` containing zero `MOVE DFHRED` and zero `MOVE '*'` | `SignonPage` / `MainMenuPage` / `AdminMenuPage` marking a rejected entry by cursor and flag only, with no `fieldError` class | source → target |
| The three-tier responsive model in `frontend/src/index.css` (`48rem`, `32rem`, `prefers-reduced-motion`) and the preference for `min()`/`clamp()` over a breakpoint | (no legacy analogue — the strategy a fixed character-cell frame implies; decision log §44.1) | target → rule |
| `frontend/eslint.config.js` `jsx-a11y/no-noninteractive-tabindex` allow-list extended with `group` and `region` | (no legacy analogue — keeps the three browse scrollers keyboard-operable per WCAG 2.1.1; decision log §44.3) | target → rule |
| `@media (hover: hover) and (pointer: fine)` guards around every hover treatment | (no legacy analogue — a touch device would otherwise leave a tapped browse row in false reverse video; decision log §44.3) | target → rule |
| `api/messages.ts` `GENERIC_ERROR_MESSAGE` and the 30-second HTTP-client timeout | (no legacy analogue — keeps an HTTP-client diagnostic off line 23 and makes the keyboard lock releasable; decision log §44.3) | target → rule |
| `Layout.buildDocumentTitle` | (no legacy analogue — the two BMS title lines are identical on all 17 mapsets, so the transaction id and program name identify the screen; decision log §44.3) | target → rule |
| `GET /session` as a `permitAll` state query answering 200 with a null identity | (no legacy analogue — a sign-on screen has nothing to probe; decision log §44.4) | target → rule |
| `frontend/nginx.conf` security headers declared per static `location` and absent from `/api/` | (no legacy analogue — Observability/security hardening; the gateway owns the API set, and duplication weakens the headers it repeats; decision log §44.10) | target → rule |
| `TransactionControllerTest.keyEndpointAppliesNoWidthConstraintOfItsOwn` | (no legacy analogue — regression pin for the §44.8 one-shape refusal) | target → rule |
| `TransactionServiceTest.aFilterOfTheWrongWidthIsRejected` and `anUnpaddedIdIsNotWidenedToAStoredKey` | (no legacy analogue — regression pins for the §44.7 exact-width rule and the removal of `pad16`) | target → rule |
| `SignonPage.test.tsx` zero-buttons-in-`main` assertion | (no legacy analogue — regression pin for the §44.9 removal) | target → rule |


## QA Remediation — Cross-Cutting Regression Sweep

Rows added while sweeping the shared-shell and shared-stylesheet changes across every route, which
surfaced three inconsistencies between screens that no per-screen check reached. Rationale lives in
decision log §45.

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| `COADM01`'s single ENTER affordance on row 24, and the CICS keyboard lock behind it `[app/bms/COADM01.bms; app/cbl/COADM01C.cbl]` | `AdminMenuPage`'s published ENTER activator alone — the page-local `onKeyDown` interception removed so the shell's per-AID latch, live-region remount and cursor rules apply | source → target |
| `COCRDLI` row-10 rule runs of 6, 15, 15 and 8 hyphens at columns 10, 21, 45 and 66 `[app/bms/COCRDLI.bms:L110-L123]` | `CardListPage.COLUMN_RULES` and `TABLE_MIN_WIDTH_CH`, driving `dataTable--fixed`, a 52-cell floor and a 4-column `<colgroup>` | source → target |
| `COTRN01`'s `COLOR=NEUTRAL LENGTH=70 POS=(8,6)` rule as a DECORATIVE glyph run `[app/bms/COTRN01.bms:L94-L98]` | `p[data-testid="tranViewRule"]` with `aria-hidden="true"`, and `margin-bottom: 0` on the shared rule group so the row beneath it stays on the terminal grid | source → target |
| `AdminMenuPage.test.tsx` "leaves ENTER to the shell, so a repeat inside one task sends once" | (no legacy analogue — regression pin for the §45 uniformity fix) | target → rule |
| `CardListPage.test.tsx` "declares the four column widths and holds the table to their total" | (no legacy analogue — regression pin for the §45 card-table floor) | target → rule |
| `TranViewPage.test.tsx` "paints the row-8 rule as seventy hyphens and hides it from assistive technology" | (no legacy analogue — regression pin for the §45 rule fix) | target → rule |
| `AccountViewUpdateIT.acctKey` / `OptimisticLockConflictIT.acctKey` and the parameterized `putAccount_whenAccountIdInvalid_returns400` | (no legacy analogue — the integration suites aligned to the §44.7 exact-width rule and the per-verb literals) | target → rule |
| `CardViewUpdateIT.listInvalidAccountFilterReturns400` parameterized over over-wide, unpadded and non-numeric filters | (no legacy analogue — regression pin for the §44.7 card rule at the type boundary) | target → rule |

## QA Remediation — Runtime QA Checkpoint (23 findings)

Rows added while resolving the twenty-three findings of the runtime QA checkpoint. Every row here
refines a behaviour within a legacy construct already enumerated in the forward sections — no new
legacy source appears — or records a target with no legacy analogue against the rule that mandates
it. Rationale lives in decision log §55, including the two findings declined on AAP precedence
(§55.12) and the six claims in that log this pass corrected (§55.1 through §55.6).

| Source Construct | Target Implementation | Direction |
|------------------|-----------------------|-----------|
| The unrecognised-AID branch of every online program's `EVALUATE EIBAID`: twelve publish `CCDA-MSG-INVALID-KEY` with a cursor move (`COADM01C`, `COBIL00C:L138-L141`, `COMEN01C`, `CORPT00C:L190-L194`, `COSGN00C:L91-L94`, `COTRN00C`, `COTRN01C`, `COTRN02C`, `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C`) and five raise `88 PFK-INVALID VALUE '1'` and re-run the ENTER path (`COACTVWC:L56,L306,L312`; `COACTUPC:L180,L905,L914`; `COCRDLIC:L129,L370,L378`; `COCRDSLC:L68,L291,L297`; `COCRDUPC:L86,L413,L422`) | `PFKeyBar.onUnhandledAid` + `Layout`'s `ScreenChrome.onUnhandledKey` carrier, and the per-screen policy each of the seventeen pages publishes — the invalid-key literal plus its program's cursor target on twelve, the ENTER activator on five | source → target |
| `COTRN00C`'s three BROWSE-CONDITION literals, distinct from its two boundary-key literals: `STARTBR` NOTFND `'You are at the top of the page...'` `[app/cbl/COTRN00C.cbl:L608]`, `READNEXT` ENDFILE `'You have reached the bottom of the page...'` `[:L642]`, `READPREV` ENDFILE `'You have reached the top of the page...'` `[:L676]`, surviving to the send because `SEND-TRNLST-SCREEN` `[:L527-L549]` does not `RETURN` | `TransactionService.MSG_BROWSE_TOP` / `MSG_REACHED_BOTTOM` / `MSG_REACHED_TOP`, published on an empty forward page (start literal at page 0, exhaustion literal beyond it), a short forward page and a short backward page; rendered through `frontend/src/components/browseNotices.ts` | source → target |
| `COTRN00C`'s fixed ten-slot map fill — `PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10` with `INITIALIZE-TRAN-DATA` for the unused slots `[app/cbl/COTRN00C.cbl:L289-L292]` | `TranListPage.ROW_SLOT_COUNT` and its `slots` projection — ten rows at every page size, the unused ones blank with their unprotected `SEL` control still enabled | source → target |
| `COTRN02C`'s exit target — `CDEMO-FROM-PROGRAM` defaulting to `COMEN01C` `[app/cbl/COTRN02C.cbl:L136-L142]`, reached only by main-menu option 08 `[app/cpy/COMEN02Y.cpy]` which `COMEN01C:L148` stamps with its own name | `TranAddPage.MENU_ROUTE` — an explicit navigation to `/menu` from every entry path, never a history pop | source → target |
| `CSSETATY`'s `MOVE DFHRED TO (SCRNVAR2)C` and its blank-field `MOVE '*' TO (SCRNVAR2)O` `[app/cpy/CSSETATY.cpy]`, reached inline or by `COPY` in the FIVE programs that recolour a data field — `COCRDUPC` (12 sites / 6 targets), `COCRDLIC` (9 sites / 9 targets incl. `L873` `ACCTSIDC` and `L878` `CARDSIDC`), `COCRDSLC` (4 / 2), `COACTUPC` (2 inline + 39 `COPY` expansions), `COACTVWC` (2 / 1) | `ErrorBanner.faultedFieldProps` — the PAINT-and-mark helper emitting `data-faulted`, kept distinct from the mark-only `invalidFieldProps` used by the twelve programs that recolour nothing; the stylesheet's `.fieldError, [data-faulted='true']` rules are the only red selectors and no rule keys off `aria-invalid` | source → target |
| `COBIL00C`'s zero-balance refusal `'You have nothing to pay...'` `[app/cbl/COBIL00C.cbl:L198-L203]` and its invalid-key branch `[:L138-L141]`, and `CORPT00C`'s invalid-key branch `[app/cbl/CORPT00C.cbl:L190-L194]` — each moves the cursor without faulting a VALUE | `BillPayPage.UNFAULTED_MESSAGES` and `ReportPage`'s invalid-key exclusion — the two screens that derive the faulted field by exclusion rather than from a message allow-list | source → target |
| `COACTUPC`'s read-snapshot-compare-rewrite conflict outcome and its literal `'Record changed by some one else. Please review'` `[app/cbl/COACTUPC.cbl:L4131-L4189]` | `PersistenceExceptionHandler.handleOptimisticLockingFailure` building its envelope through `ErrorResponseFactory` and setting the SAME `errorCode` as `GlobalExceptionHandler.handleOptimisticLockingFailure`, so one legacy outcome yields one code whichever advice answers | source → target |
| `COTRN01C` / `COACTVWC` / `COCRDSLC` `SEND MAP` semantics — a send writes the WHOLE map, so a failed read transmits blanked fields alongside its message `[app/cbl/COTRN01C.cbl; app/cbl/COACTVWC.cbl; app/cbl/COCRDSLC.cbl]` | `TranViewPage`, `AccountViewPage` and `CardDetailPage` clearing their display model BEFORE dispatching a lookup, so a failed lookup cannot leave the previous record — or its PAN and masked identifiers — on screen; the same latent path closed on `CardUpdatePage` | source → target |
| `COSGN00C`'s credential verification, which is unconditional and precedes any session action `[app/cbl/COSGN00C.cbl:L211-L237]` | `AuthenticationService` verifying first and only then reusing, clearing or re-indexing the session record and deregistering the previous principal; `useSession.signIn` authenticating before it revokes anything | source → target |
| `COTRN02C`'s post-success state — `INITIALIZE-ALL-FIELDS` then the green success literal `[app/cbl/COTRN02C.cbl:L723-L734, L762-L779]`, followed on a bare ENTER by `VALIDATE-INPUT-KEY-FIELDS` `WHEN OTHER` replacing it `[:L190-L235]` | `TranAddPage`'s unchanged post-success behaviour — DECLINED as a change on AAP §0.7.1 / §0.7.6 grounds and recorded in decision log §55.12 | source → target |
| `COTRN02C`'s confirmation re-prompt, which re-sends the map without `MOVE SPACES TO CONFIRMI` on either the `N` branch or the invalid branch `[app/cbl/COTRN02C.cbl:L168-L186]` | `TranAddPage.confirmOrAdd` retaining the typed confirmation value across both re-prompts | source → target |
| `GlobalExceptionHandler.handleParameterTypeMismatch` and `fieldErrorMessage` — one CardDemo literal for a malformed request parameter on BOTH binding paths (`@RequestParam` type mismatch and an `@ModelAttribute` binding result coded `typeMismatch`), preferring a screen's registered legacy message where one exists | (no legacy analogue — AAP §0.7.2 Rule 5: the framework's own wording echoes the submitted value and names internal Java types, which is unrequested observable output) | target → rule |
| `.screen__body { flex: 1 1 auto }` with the frame's floor and cap unchanged, and the removal of the `48rem`-only `min-height: 100vh` that made the tiers non-monotonic | (no legacy analogue — derived from the 24x80 frame contract; a fixed nineteen-row flex basis clipped the densest editable screen, decision log §55.1 and §55.7) | target → rule |
| `html::-webkit-scrollbar` parts plus the scroll-position-driven pan cue below `48rem`, and the `32rem` cap that gives the horizontal bar its thickness back | (no legacy analogue — the affordance for a pan a 3270 never needed; the columns past the right edge hold the `COBIL00` / `CORPT00` / `COTRN02` confirm fields, decision log §55.7) | target → rule |
| `:where()`-wrapped pointer-state rules, `.pfKeyBar button:active { color: var(--term-bg) }`, the 24x24 minimum hit area, the skip-target `:focus` ring, and the visually-hidden `.screen__busy` rule | (no legacy analogue — four interactive-state omissions plus one rule the code's own docstring already claimed; WCAG 2.5.8 and 2.4.7 for the first three, decision log §55.7) | target → rule |
| `TransactionServiceTest`'s eight boundary-literal cases, `TranListPage.test.tsx`'s ten-slot and blank-slot-flag cases, `TranAddPage.test.tsx`'s two `/menu` exit cases, `BillPayPage.test.tsx`'s two unfaulted-message cases, `ReportPage.test.tsx`'s invalid-key case, `GlobalExceptionHandlerParameterTypeMismatchTest` and `PersistenceExceptionHandlerEnvelopeTest` | (no legacy analogue — regression pins for the §55 fixes, including the field-for-field agreement between the two conflict advices and the card-number redaction in the error `path`) | target → rule |

