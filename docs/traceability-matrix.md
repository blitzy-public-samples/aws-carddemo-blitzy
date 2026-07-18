# CardDemo COBOL -> Java Migration: Traceability Matrix

> Bidirectional source-construct -> target mapping for the AWS CardDemo re-platforming (COBOL / CICS / VSAM / JCL -> Java 25 + Spring Boot 3.x + PostgreSQL 16).

This document satisfies the **Explainability** rule (AAP Section 0.8.2) and the traceability validation criteria (AAP Section 0.9.4 / 0.9.6). Its **primary, exhaustive** coverage is **paragraph-level**: it maps **100% of the COBOL PROCEDURE DIVISION paragraphs across all 28 programs (527 unique paragraphs)** to their target Java implementations **with no gaps**. This paragraph-level mapping is **bidirectional** — Section 4 maps every COBOL paragraph to its Java target, and Section 5 maps every major Java artifact back to the COBOL program(s), paragraph(s), and copybook(s) it derives from.

The matrix additionally provides coverage at two further granularities, each **complete at its own level of detail** (and labelled as such where it appears, rather than under a single blanket "100%"): **screen field-level** traceability for all 17 BMS maps and their 441 fields plus per-program action-key (AID) handling (Section 3), and **data-artifact-level** traceability for the seed/reference data files — 9 ASCII, 12 EBCDIC, and the VSAM catalog listing (Section 8). Where a source construct is intentionally **not** mapped one-to-one (for example a source-only CSD entry with no program, a reference-only JCL member, or an application-enforced relationship that cannot become a database constraint), it is **explicitly classified** rather than silently omitted, so "no gaps" means *nothing is undocumented*, not that every construct becomes a like-for-like target.

- **Scope:** 28 COBOL programs, **527 unique PROCEDURE DIVISION paragraphs**, 11 data entities/tables, 17 online screens, 11 Spring Batch jobs, plus JCL/PROC/CTL/CSD orchestration.
- **Legacy source location:** the original COBOL is retained read-only under `legacy/**` (relocated from `app/**`); all source references below point at `legacy/**`.
- **Related documents:** [Architecture](./architecture.md) &middot; [Decision Log](./decision-log.md).
- **Identifier fidelity:** COBOL paragraph names are preserved verbatim (uppercase, hyphenated); Java targets use the package-by-layer names defined in the architecture.

Monetary parity anchor preserved exactly: interest = `tranCatBal.multiply(intRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)` (`legacy/cbl/CBACT04C.cbl` 1300-COMPUTE-INTEREST, L464-L465). All monetary fields use `java.math.BigDecimal` (scale 2); no floating point.

## 1. Coverage Summary

One row per COBOL program: its type, unique PROCEDURE DIVISION paragraph count, target Java class(es), and CICS transaction id (online) or JCL trigger (batch). The TOTAL row asserts full coverage.

| # | Program | Type | Unique paragraphs | Target Java class(es) | Txn id / JCL trigger |
|---:|---------|------|------------------:|-----------------------|----------------------|
| 1 | `CBACT01C.cbl` | batch | 6 | batch/AccountMasterPrintJob | (account master print) |
| 2 | `CBACT02C.cbl` | batch | 5 | batch/CardMasterPrintJob | (card master print) |
| 3 | `CBACT03C.cbl` | batch | 5 | batch/XrefPrintJob | (xref master print) |
| 4 | `CBACT04C.cbl` | batch | 22 | batch/InterestCalculationJob | INTCALC |
| 5 | `CBCUS01C.cbl` | batch | 5 | batch/CustomerMasterPrintJob | (customer master print) |
| 6 | `CBSTM03A.CBL` | batch | 25 | batch/StatementGenerationJob | CREASTMT |
| 7 | `CBSTM03B.CBL` | batch | 14 | batch/StatementGenerationJob -> service/StatementFileService | CREASTMT (subprogram) |
| 8 | `CBTRN01C.cbl` | batch | 18 | batch/DailyTransactionValidateJob | (daily validate) |
| 9 | `CBTRN02C.cbl` | batch | 26 | batch/DailyTransactionPostingJob | POSTTRAN |
| 10 | `CBTRN03C.cbl` | batch | 26 | batch/TransactionReportJob | TRANREPT.prc |
| 11 | `COACTUPC.cbl` | online | 85 | web/AccountUpdateController + service/AccountService | CAUP |
| 12 | `COACTVWC.cbl` | online | 34 | web/AccountViewController + service/AccountService | CAVW |
| 13 | `COADM01C.cbl` | online | 7 | web/AdminMenuController + service/MenuService | CA00 |
| 14 | `COBIL00C.cbl` | online | 16 | web/BillPaymentController + service/BillPaymentService | CB00 |
| 15 | `COCRDLIC.cbl` | online | 39 | web/CardListController + service/CardService | CCLI |
| 16 | `COCRDSLC.cbl` | online | 34 | web/CardViewController + service/CardService | CCDL |
| 17 | `COCRDUPC.cbl` | online | 45 | web/CardUpdateController + service/CardService | CCUP |
| 18 | `COMEN01C.cbl` | online | 7 | web/MainMenuController + service/MenuService | CM00 |
| 19 | `CORPT00C.cbl` | online | 10 | web/TransactionReportController + service/ReportService | CR00 |
| 20 | `COSGN00C.cbl` | online | 6 | web/SignonController + service/SignonService | CC00 |
| 21 | `COTRN00C.cbl` | online | 16 | web/TransactionListController + service/TransactionService | CT00 |
| 22 | `COTRN01C.cbl` | online | 9 | web/TransactionViewController + service/TransactionService | CT01 |
| 23 | `COTRN02C.cbl` | online | 18 | web/TransactionAddController + service/TransactionService + common/util/IdGenerator | CT02 |
| 24 | `COUSR00C.cbl` | online | 16 | web/UserListController + service/UserService | CU00 |
| 25 | `COUSR01C.cbl` | online | 9 | web/UserAddController + service/UserService | CU01 |
| 26 | `COUSR02C.cbl` | online | 11 | web/UserUpdateController + service/UserService | CU02 |
| 27 | `COUSR03C.cbl` | online | 11 | web/UserDeleteController + service/UserService | CU03 |
| 28 | `CSUTLDTC.cbl` | util | 2 | service/DateValidationService + common/util/DateUtils | (called by online/batch) |
| - | **TOTAL (28 programs)** | - | **527** | **100% mapped (see Section 4)** | - |

**Assertion:** 28 programs, **527 unique paragraphs**, **100%** mapped to Java targets with no gaps (verified against `legacy/cbl/**`; see Appendix A and the reconciliation footnote in Section 4.12).

## Cross-Reference / Clarification Notes (CF)

Rows in Sections 4–8 occasionally cite a **CF** code (**CF1**, **CF2**, …) to attach a short, shared clarification without repeating it inline. Each code resolves below. Every fact a CF note annotates has been verified against the cited legacy artifact; these notes complement (and cross-reference) the numbered decision-log entries **D1–D24**.

| Code | Clarification note |
|------|--------------------|
| **CF1** | **Reject-record composition (430 bytes).** The DALYREJS reject record = a 350-byte transaction image (`FD-REJECT-RECORD PIC X(350)`) plus an 80-byte validation trailer (`FD-VALIDATION-TRAILER PIC X(80)`), both subordinate to `FD-REJS-RECORD` in `legacy/cbl/CBTRN02C.cbl` (L82–L84). The fixed-width layout is preserved by `FixedWidthCodec` — see decision **D16**. |
| **CF2** | **The chronological alternate index keys on `proc_ts`, not `orig_ts`.** `TRANSACT.VSAM.AIX` is keyed at `AXRKP=304`, which is `TRAN-PROC-TS` (copybook offset 304) — **not** `TRAN-ORIG-TS` (offset 278). It therefore formalizes to a B-tree index on `transaction.proc_ts`. Evidence: `legacy/jcl/TRANIDX.jcl` `KEYS(26 304)` and `legacy/catlg/LISTCAT.txt`. |
| **CF4** | **ASCII seed files are fixed-width and headerless** — record images sized by the governing copybook `RECLN`, **not** delimited/CSV. There is also **no** `usrsec.txt` in the ASCII set: user-security seed rows derive from the EBCDIC `USRSEC.PS` dataset via `CSUSR01Y`. |
| **CF6** | **CSD source-only / reference-only resources.** CSD definitions with no executable COBOL behind them — the 18th transaction/program pair `CDV1`→`COCRDSEC` (no `COCRDSEC.cbl` exists) and the `LIBRARY` load-library entries — are catalogued for naming only and are not migration targets. This reconciles the "18 TRANSACTION / 18 PROGRAM" catalog count to **17 production surfaces + 1 source-only**. |
| **CF7** | **Historical / reference-only legacy member.** A superseded artifact retained under `legacy/` for reference and **not** migrated to a Spring Batch job — e.g. `CBADMCDJ.jcl`, which loads a stale alternate CSD catalog superseded by `CARDDEMO.CSD`. See decision **D14**. |
| **CF8** | **"No legacy edits" — a source defect is not a contract.** Where a legacy member carries a defect (the garbled `SPACE=` continuation in `CREASTMT.JCL`, duplicate step labels in `TRANREPT.jcl`/`DEFCUST.jcl`, the transposed `OEPNFIL` job name, the stale HLQ in `DEFCUST.jcl`, or the two same-named `REPROC` PROCs), the immutable legacy bytes are left byte-for-byte unchanged; the migration reproduces the **authoritative behavior** from the clean portions rather than the defect. See decision **D14**. |
| **CF13** | **`.gitattributes` byte-exactness.** The `.gitattributes` entries added in this checkpoint pin the legacy data/catalog files as binary so Git applies no EOL/encoding normalization, keeping the recorded sizes and SHA-256 hashes byte-exact. |
| **CF14** | **Snapshot / alias caveats for physical data files.** `ACCDATA.PS` is a byte-exact duplicate alias of `ACCTDATA.PS` (identical hash) — only `ACCTDATA` is authoritative and it is not loaded a second time; and the ASCII vs. EBCDIC images are point-in-time snapshots that can differ in row count for the transactional (non-reference) datasets. |

## 2. Data-Tier Traceability (copybook -> JPA entity -> table)

Each VSAM KSDS becomes a PostgreSQL 16 table; the unique key becomes the primary key; each alternate index (AIX) becomes a B-tree index; application-enforced relationships become real foreign keys **where the parent key is genuinely unique** (a documented improvement, not a behavior change). The one relationship whose parent key is **not** unique — `account.group_id` against the composite-keyed `disclosure_group` — is **not** a foreign key; it is modeled as a plain grouping attribute resolved by a composite `(group_id, type_cd, cat_cd)` application-level lookup (see decision **D8** and the note below). COMP-3 monetary fields become `DECIMAL(x,2)` / `BigDecimal`. See [architecture](./architecture.md) and AAP Section 0.4.3.

| Copybook (`legacy/cpy/**`) | JPA entity (`domain/`) | Table | Primary key | Indexes / foreign keys |
|----------------------------|------------------------|-------|-------------|------------------------|
| `CVCUS01Y.cpy` | `domain/Customer.java` | `customer` | `cust_id` | - |
| `CVACT01Y.cpy` | `domain/Account.java` | `account` | `acct_id` | `group_id` = grouping attribute (**no FK**; `disclosure_group` PK is composite — see note) |
| `CVACT02Y.cpy` | `domain/Card.java` | `card` | `card_num` | index acct_id (=CARDDATA.VSAM.AIX); FK acct_id -> account |
| `CVACT03Y.cpy` | `domain/CardXref.java` | `card_xref` | `xref_card_num` | index acct_id (=CARDXREF.VSAM.AIX); FK cust_id -> customer, acct_id -> account |
| `CVTRA05Y.cpy` | `domain/Transaction.java` | `transaction` | `tran_id (16-char)` | index proc_ts (=TRANSACT.VSAM.AIX, KEYLEN=26 @ AXRKP=304); FK card_num -> card, type_cd -> transaction_type, composite (type_cd, cat_cd) -> transaction_category |
| `CVTRA06Y.cpy` | `domain/DailyTransaction.java` | `daily_transaction` | `staging key` | - (posting staging) |
| `CSUSR01Y.cpy` | `domain/UserSecurity.java` | `user_security` | `sec_usr_id` | - (role A/U) |
| `CVTRA03Y.cpy` | `domain/TransactionType.java` | `transaction_type` | `type_cd` | - |
| `CVTRA04Y.cpy` | `domain/TransactionCategory.java` | `transaction_category` | `(type_cd, cat_cd)` | - |
| `CVTRA02Y.cpy` | `domain/DisclosureGroup.java` | `disclosure_group` | `(group_id, type_cd, cat_cd)` | int_rate DECIMAL(6,2) |
| `CVTRA01Y.cpy` | `domain/TransactionCategoryBalance.java` | `tran_cat_balance` | `(acct_id, type_cd, cat_cd)` | bal DECIMAL(11,2) |

**Reference-only copybooks (no separate entity):** `CVTRA07Y.cpy`, `CVCRD01Y.cpy`, and `CUSTREC*` are used to reconcile field semantics only. **Alternate indexes formalized:** `CARDDATA.VSAM.AIX` (→ index on `card.acct_id`), `CARDXREF.VSAM.AIX` (→ index on `card_xref.acct_id`), and `TRANSACT.VSAM.AIX` (→ index on `transaction.proc_ts`) become ordinary B-tree indexes preserving the card-to-account, xref-to-account, and chronological-transaction browse patterns.

> **AIX key disambiguation (`TRANSACT.VSAM.AIX` → `proc_ts`, not `orig_ts`):** the transaction record (`CVTRA05Y`) defines **two** 26-character timestamps — `TRAN-ORIG-TS` (origination) at offset **278** and `TRAN-PROC-TS` (processing) at offset **304**. The VSAM catalog listing (`legacy/catlg/LISTCAT.txt`) shows `TRANSACT.VSAM.AIX` with `KEYLEN=26` beginning at `AXRKP=304` — i.e. the alternate key is `TRAN-PROC-TS`, which maps to the `proc_ts` column. The chronological transaction browse therefore orders by `proc_ts`; ordering by `orig_ts` would not reproduce the legacy browse order.

> **`account.group_id` is a grouping attribute, not a foreign key (source-faithful deviation):** `disclosure_group` has a **composite** primary key (`group_id`, `type_cd`, `cat_cd`), so a group id **alone is not unique** and cannot be a foreign-key target. Rather than invent a synthetic single-column parent the legacy never had, `account.group_id` is retained as a plain grouping attribute, and the applicable disclosure/interest row is resolved by a composite `(group_id, type_cd, cat_cd)` lookup at the application layer — exactly the access path `legacy/cbl/CBACT04C.cbl` used against `DISCGRP`. Recorded as decision **D8**.

### 2.1 Complete Copybook Crosswalk (all 28 copybooks)

The table in Section 2 above covers only the data-tier copybooks that produce JPA entities. This subsection extends that to a **complete crosswalk of every one of the 28 ordinary copybooks** under `legacy/cpy/**` (`*.cpy` and `*.CPY`), so that no data/session/message/date/menu/UI/PF-key/lookup/validation authority is omitted. Each copybook is classified and mapped to its target Java artifact, and copybooks that are **genuinely unused** or **reference-only** (no separate target) are called out explicitly.

Notes on reading this table:

- **Classification legend:** *Entity* = produces a JPA entity/table (see Section 2); *Reference-only* = record/work layout reused to reconcile field semantics, no separate target; *Session* = COMMAREA/flow-and-navigation context; *Date* = date helper folded into `common/util/DateUtils` / `service/DateValidationService`; *Menu* = menu-option table folded into `service/MenuService`; *Message* = shared message text constants; *UI title* = shared screen-title constants surfaced through response DTO headers; *UI attribute* = field edit-state / attribute logic folded into `service/rule` and DTO field state; *PF-key* = AID-to-PF-key action mapping folded into PF-key action enums/handlers; *Lookup* = static reference data backing edit rules; *Statement/report layout* = fixed-width external-file record; *Unused* = not referenced by any program and not migrated.
- The **Target** column names the **planned** Java artifact per AAP Section 0.5 (the copybooks themselves are retained unchanged under `legacy/cpy/**`); it does not assert that the target already exists at this checkpoint.
- **Used by** counts refer to programs under `legacy/cbl/**` that `COPY` the member (some via the quoted `COPY 'NAME'` form).

| # | Copybook (`legacy/cpy/**`) | 01-level / purpose | Classification | Used by (legacy programs) | Target Java artifact (planned, AAP §0.5) |
|---:|----------------------------|--------------------|----------------|---------------------------|------------------------------------------|
| 1 | `CVCUS01Y.cpy` | `CUSTOMER-RECORD` (500-byte customer layout) | Entity | 6 (COACTVWC, COACTUPC, CBCUS01C, COCRDUPC, CBTRN01C, COCRDSLC) | `domain/Customer.java` -> `customer` |
| 2 | `CVACT01Y.cpy` | `ACCOUNT-RECORD` (300-byte account layout) | Entity | 11 (account consumers, online + batch) | `domain/Account.java` -> `account` |
| 3 | `CVACT02Y.cpy` | `CARD-RECORD` (150-byte card layout) | Entity | 6 (card programs + CBACT02C) | `domain/Card.java` -> `card` |
| 4 | `CVACT03Y.cpy` | `CARD-XREF-RECORD` (50-byte cross-reference) | Entity | 12 (xref consumers, online + batch) | `domain/CardXref.java` -> `card_xref` |
| 5 | `CVTRA05Y.cpy` | `TRAN-RECORD` (350-byte transaction layout) | Entity | 9 (transaction consumers) | `domain/Transaction.java` -> `transaction` |
| 6 | `CVTRA06Y.cpy` | `DALYTRAN-RECORD` (350-byte posting staging layout) | Entity | 2 (CBTRN01C, CBTRN02C) | `domain/DailyTransaction.java` -> `daily_transaction` |
| 7 | `CSUSR01Y.cpy` | `SEC-USER-DATA` (80-byte user/security, role A/U) | Entity | 12 (online consumers) | `domain/UserSecurity.java` -> `user_security` |
| 8 | `CVTRA03Y.cpy` | `TRAN-TYPE-RECORD` (transaction-type reference) | Entity | 1 (CBTRN03C) | `domain/TransactionType.java` -> `transaction_type` |
| 9 | `CVTRA04Y.cpy` | `TRAN-CAT-RECORD` (compound-key category reference) | Entity | 1 (CBTRN03C) | `domain/TransactionCategory.java` -> `transaction_category` |
| 10 | `CVTRA02Y.cpy` | `DIS-GROUP-RECORD` (disclosure group; `DIS-INT-RATE`) | Entity | 1 (CBACT04C) | `domain/DisclosureGroup.java` -> `disclosure_group` |
| 11 | `CVTRA01Y.cpy` | `TRAN-CAT-BAL-RECORD` (category balance) | Entity | 2 (CBACT04C, CBTRN02C) | `domain/TransactionCategoryBalance.java` -> `tran_cat_balance` |
| 12 | `CVTRA07Y.cpy` | `REPORT-NAME-HEADER` (report header/name work layout) | Reference-only (no entity) | 1 (CBTRN03C) | none -- report-output reference for `batch/TransactionReportJob` |
| 13 | `CVCRD01Y.cpy` | `CC-WORK-AREAS` (card-screen work areas) | Reference-only (no entity) | 5 (COACTVWC, COACTUPC, COCRDLIC, COCRDUPC, COCRDSLC) | none -- reconciles field semantics for `domain/Card` + card DTOs |
| 14 | `CUSTREC.cpy` | `CUSTOMER-RECORD` (alternate customer layout) | Reference-only (no entity) | 1 (CBSTM03A) | none -- reconciles field semantics for `domain/Customer`; statement input |
| 15 | `COCOM01Y.cpy` | `CARDDEMO-COMMAREA` (session/navigation + role 88-levels A=ADMIN/U=USER) | Session | 17 (all online programs) | server-side flow/session context; `security/*` + `config/SecurityConfig` role mapping |
| 16 | `CSDAT01Y.cpy` | `WS-DATE-TIME` (date/time work fields) | Date | 17 (all online programs) | `common/util/DateUtils` |
| 17 | `CSUTLDPY.cpy` | date-validation PROCEDURE copybook (called for date edits) | Date | 1 (COACTUPC) | `common/util/DateUtils` + `service/DateValidationService` |
| 18 | `CSUTLDWY.cpy` | date-validation WORKING-STORAGE copybook | Date | 1 (COACTUPC) | `common/util/DateUtils` |
| 19 | `COADM02Y.cpy` | `CARDDEMO-ADMIN-MENU-OPTIONS` (admin menu option table) | Menu | 1 (COADM01C) | `service/MenuService` (admin-menu routing) |
| 20 | `COMEN02Y.cpy` | `CARDDEMO-MAIN-MENU-OPTIONS` (main menu option table) | Menu | 1 (COMEN01C) | `service/MenuService` (main-menu routing) |
| 21 | `COTTL01Y.cpy` | `CCDA-SCREEN-TITLE` (screen title/header constants) | UI title | 17 (all online programs) | shared title constants surfaced in response DTO headers |
| 22 | `CSMSG01Y.cpy` | `CCDA-COMMON-MESSAGES` (thank-you / invalid-key text) | Message | 17 (all online programs) | shared message constants (`common/`), surfaced via DTO/error responses |
| 23 | `CSMSG02Y.cpy` | `ABEND-DATA` (CABENDD abend work areas) | Message | 5 (COACTVWC, COACTUPC, COCRDLIC, COCRDUPC, COCRDSLC) | `exception/*` abend context -> `GlobalExceptionHandler` / batch failure |
| 24 | `CSSETATY.cpy` | field-attribute set logic (error highlight; `DFHRED` / `*`) | UI attribute | 1 (COACTUPC; `COPY CSSETATY ... REPLACING`) | field edit-state / attribute handling in `service/rule` + DTO field state |
| 25 | `CSSTRPFY.cpy` | `YYYY-STORE-PFKEY` (EIBAID -> PF-key mapping into COMMAREA) | PF-key | 5 (COACTVWC, COACTUPC, COCRDLIC, COCRDUPC, COCRDSLC; quoted `COPY 'CSSTRPFY'`) | PF-key/AID action enum + handling in controllers/DTOs (Enter/PF3/PF7/PF8 ...) |
| 26 | `CSLKPCDY.cpy` | lookup repository (US phone area codes, state codes, state + ZIP) | Lookup | 1 (COACTUPC) | address-validation reference data in `service/rule` (state/ZIP/phone edits) |
| 27 | `COSTM01.CPY` | `TRNX-RECORD` (altered transaction layout for statement/report output) | Statement/report layout | 1 (CBSTM03A) | `batch/StatementGenerationJob` + `common/util/FixedWidthCodec` (statement/report record) |
| 28 | `UNUSED1Y.cpy` | `UNUSED-DATA` (id/name/password/type/filler stub) | **Unused (genuinely unused)** | none (0 `COPY` references) | **none -- not migrated; retained under `legacy/cpy/**` for reference only** |

**Copybook coverage assertion:** all **28/28** ordinary copybooks under `legacy/cpy/**` are represented above. The ten members previously absent from this matrix (`COADM02Y`, `COMEN02Y`, `COSTM01`, `COTTL01Y`, `CSLKPCDY`, `CSMSG01Y`, `CSMSG02Y`, `CSSETATY`, `CSSTRPFY`, `UNUSED1Y`) are now included and classified. `UNUSED1Y.cpy` is confirmed **genuinely unused** (zero `COPY` references across `legacy/cbl/**`) and is intentionally not migrated; `CVTRA07Y.cpy`, `CVCRD01Y.cpy`, and `CUSTREC.cpy` are **reference-only** authorities with no separate target entity.

## 3. Screen / UI-Contract Traceability (BMS -> request/response DTOs)

Each of the 17 online screens maps its BMS map definition and symbolic copybook to a request/response DTO pair under `dto/`, preserving field names, lengths, PIC-derived types, edit rules, and the full per-program **action-key (AID)** set. No terminal emulator is produced (AAP Section 0.3.3). This section provides three levels of traceability: **3.1** the screen-to-DTO summary (17 rows), **3.2** field-level traceability (all **441** named BMS fields with attributes and edit contracts), and **3.3** the complete per-program AID map (including PF4/PF5/PF12).

### 3.1 Screen-to-DTO summary

| Screen | BMS map (`legacy/bms/**`) | Symbolic copybook (`legacy/cpy-bms/**`) | Program | Controller | Request DTO | Response DTO |
|--------|---------------------------|------------------------------------------|---------|------------|-------------|--------------|
| Account Update | `COACTUP.bms` | `COACTUP.CPY` | `COACTUPC` | `web/AccountUpdateController.java` | `dto/AccountUpdateRequest.java` | `dto/AccountUpdateResponse.java` |
| Account View | `COACTVW.bms` | `COACTVW.CPY` | `COACTVWC` | `web/AccountViewController.java` | `dto/AccountViewRequest.java` | `dto/AccountViewResponse.java` |
| Admin Menu | `COADM01.bms` | `COADM01.CPY` | `COADM01C` | `web/AdminMenuController.java` | `dto/AdminMenuRequest.java` | `dto/AdminMenuResponse.java` |
| Bill Payment | `COBIL00.bms` | `COBIL00.CPY` | `COBIL00C` | `web/BillPaymentController.java` | `dto/BillPaymentRequest.java` | `dto/BillPaymentResponse.java` |
| Card List | `COCRDLI.bms` | `COCRDLI.CPY` | `COCRDLIC` | `web/CardListController.java` | `dto/CardListRequest.java` | `dto/CardListResponse.java` |
| Card View | `COCRDSL.bms` | `COCRDSL.CPY` | `COCRDSLC` | `web/CardViewController.java` | `dto/CardViewRequest.java` | `dto/CardViewResponse.java` |
| Card Update | `COCRDUP.bms` | `COCRDUP.CPY` | `COCRDUPC` | `web/CardUpdateController.java` | `dto/CardUpdateRequest.java` | `dto/CardUpdateResponse.java` |
| Main Menu | `COMEN01.bms` | `COMEN01.CPY` | `COMEN01C` | `web/MainMenuController.java` | `dto/MainMenuRequest.java` | `dto/MainMenuResponse.java` |
| Transaction Report | `CORPT00.bms` | `CORPT00.CPY` | `CORPT00C` | `web/TransactionReportController.java` | `dto/TransactionReportRequest.java` | `dto/TransactionReportResponse.java` |
| Sign-on | `COSGN00.bms` | `COSGN00.CPY` | `COSGN00C` | `web/SignonController.java` | `dto/SignonRequest.java` | `dto/SignonResponse.java` |
| Transaction List | `COTRN00.bms` | `COTRN00.CPY` | `COTRN00C` | `web/TransactionListController.java` | `dto/TransactionListRequest.java` | `dto/TransactionListResponse.java` |
| Transaction View | `COTRN01.bms` | `COTRN01.CPY` | `COTRN01C` | `web/TransactionViewController.java` | `dto/TransactionViewRequest.java` | `dto/TransactionViewResponse.java` |
| Transaction Add | `COTRN02.bms` | `COTRN02.CPY` | `COTRN02C` | `web/TransactionAddController.java` | `dto/TransactionAddRequest.java` | `dto/TransactionAddResponse.java` |
| User List | `COUSR00.bms` | `COUSR00.CPY` | `COUSR00C` | `web/UserListController.java` | `dto/UserListRequest.java` | `dto/UserListResponse.java` |
| User Add | `COUSR01.bms` | `COUSR01.CPY` | `COUSR01C` | `web/UserAddController.java` | `dto/UserAddRequest.java` | `dto/UserAddResponse.java` |
| User Update | `COUSR02.bms` | `COUSR02.CPY` | `COUSR02C` | `web/UserUpdateController.java` | `dto/UserUpdateRequest.java` | `dto/UserUpdateResponse.java` |
| User Delete | `COUSR03.bms` | `COUSR03.CPY` | `COUSR03C` | `web/UserDeleteController.java` | `dto/UserDeleteRequest.java` | `dto/UserDeleteResponse.java` |

### 3.2 Field-level traceability (441 fields)

One row per **named** `DFHMDF` field across all 17 BMS maps (441 fields total; static screen-literal `DFHMDF` entries without a field name are not data-contract fields and are omitted). Each row preserves the field's **length**, **screen position** (`POS=(row,col)`), **`ATTRB`** byte semantics, **`COLOR`**, and any **edit contract** (`PICIN`/`PICOUT` numeric-edit masks, `VALIDN`, `HILIGHT`, `JUSTIFY`), and maps it to its DTO field and direction. Field direction is derived from `ATTRB`: `UNPROT`/`IC` fields are **input (request)**, `PROT`/`ASKIP` fields are **output (response)**.

**`ATTRB` / attribute legend:** `ASKIP` = autoskip (protected, cursor skips) → output; `PROT` = protected → output; `UNPROT` = unprotected → **input**; `NORM` = normal intensity; `BRT` = bright; `DRK` = dark/non-display (e.g. password/CVV); `FSET` = modified-data-tag preset; `IC` = insert-cursor (initial cursor position). `HILIGHT=UNDERLINE` marks an input field's edit box; `VALIDN` carries MUSTFILL/MUSTENTER validation; `PICIN`/`PICOUT` carry the numeric edit mask reproduced by the DTO's Bean-Validation/format rules; `JUSTIFY=RIGHT` right-justifies numeric entry.

#### 3.2.1 Account Update — `COACTUP.bms` -> `dto/AccountUpdateRequest.java` / `dto/AccountUpdateResponse.java` (54 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,NORM | BLUE | alnum | `curtime` (output (response)) |
| `ACCTSID` | 11 | 5,38 | IC,UNPROT | - | HILIGHT=UNDERLINE | `acctsid` (input (request)) |
| `ACSTTUS` | 1 | 5,70 | UNPROT | - | HILIGHT=UNDERLINE | `acsttus` (input (request)) |
| `OPNYEAR` | 4 | 6,17 | FSET,UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `opnyear` (input (request)) |
| `OPNMON` | 2 | 6,24 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `opnmon` (input (request)) |
| `OPNDAY` | 2 | 6,29 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `opnday` (input (request)) |
| `ACRDLIM` | 15 | 6,61 | FSET,UNPROT | - | HILIGHT=UNDERLINE | `acrdlim` (input (request)) |
| `EXPYEAR` | 4 | 7,17 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `expyear` (input (request)) |
| `EXPMON` | 2 | 7,24 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `expmon` (input (request)) |
| `EXPDAY` | 2 | 7,29 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `expday` (input (request)) |
| `ACSHLIM` | 15 | 7,61 | FSET,UNPROT | - | HILIGHT=UNDERLINE | `acshlim` (input (request)) |
| `RISYEAR` | 4 | 8,17 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `risyear` (input (request)) |
| `RISMON` | 2 | 8,24 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `rismon` (input (request)) |
| `RISDAY` | 2 | 8,29 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `risday` (input (request)) |
| `ACURBAL` | 15 | 8,61 | FSET,UNPROT | - | HILIGHT=UNDERLINE | `acurbal` (input (request)) |
| `ACRCYCR` | 15 | 9,61 | FSET,UNPROT | - | HILIGHT=UNDERLINE | `acrcycr` (input (request)) |
| `AADDGRP` | 10 | 10,23 | UNPROT | - | HILIGHT=UNDERLINE | `aaddgrp` (input (request)) |
| `ACRCYDB` | 15 | 10,61 | FSET,UNPROT | - | HILIGHT=UNDERLINE | `acrcydb` (input (request)) |
| `ACSTNUM` | 9 | 12,23 | UNPROT | - | HILIGHT=UNDERLINE | `acstnum` (input (request)) |
| `ACTSSN1` | 3 | 12,55 | UNPROT | - | HILIGHT=UNDERLINE | `actssn1` (input (request)) |
| `ACTSSN2` | 2 | 12,61 | UNPROT | - | HILIGHT=UNDERLINE | `actssn2` (input (request)) |
| `ACTSSN3` | 4 | 12,66 | UNPROT | - | HILIGHT=UNDERLINE | `actssn3` (input (request)) |
| `DOBYEAR` | 4 | 13,23 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `dobyear` (input (request)) |
| `DOBMON` | 2 | 13,30 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `dobmon` (input (request)) |
| `DOBDAY` | 2 | 13,35 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `dobday` (input (request)) |
| `ACSTFCO` | 3 | 13,62 | UNPROT | - | HILIGHT=UNDERLINE | `acstfco` (input (request)) |
| `ACSFNAM` | 25 | 15,1 | UNPROT | - | HILIGHT=UNDERLINE | `acsfnam` (input (request)) |
| `ACSMNAM` | 25 | 15,28 | UNPROT | - | HILIGHT=UNDERLINE | `acsmnam` (input (request)) |
| `ACSLNAM` | 25 | 15,55 | UNPROT | - | HILIGHT=UNDERLINE | `acslnam` (input (request)) |
| `ACSADL1` | 50 | 16,10 | UNPROT | - | HILIGHT=UNDERLINE | `acsadl1` (input (request)) |
| `ACSSTTE` | 2 | 16,73 | UNPROT | - | HILIGHT=UNDERLINE | `acsstte` (input (request)) |
| `ACSADL2` | 50 | 17,10 | UNPROT | - | HILIGHT=UNDERLINE | `acsadl2` (input (request)) |
| `ACSZIPC` | 5 | 17,73 | UNPROT | - | HILIGHT=UNDERLINE | `acszipc` (input (request)) |
| `ACSCITY` | 50 | 18,10 | UNPROT | - | HILIGHT=UNDERLINE | `acscity` (input (request)) |
| `ACSCTRY` | 3 | 18,73 | UNPROT | - | HILIGHT=UNDERLINE | `acsctry` (input (request)) |
| `ACSPH1A` | 3 | 19,10 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acsph1a` (input (request)) |
| `ACSPH1B` | 3 | 19,14 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acsph1b` (input (request)) |
| `ACSPH1C` | 4 | 19,18 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acsph1c` (input (request)) |
| `ACSGOVT` | 20 | 19,58 | UNPROT | - | HILIGHT=UNDERLINE | `acsgovt` (input (request)) |
| `ACSPH2A` | 3 | 20,10 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acsph2a` (input (request)) |
| `ACSPH2B` | 3 | 20,14 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acsph2b` (input (request)) |
| `ACSPH2C` | 4 | 20,18 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acsph2c` (input (request)) |
| `ACSEFTC` | 10 | 20,41 | UNPROT | - | HILIGHT=UNDERLINE | `acseftc` (input (request)) |
| `ACSPFLG` | 1 | 20,78 | UNPROT | - | HILIGHT=UNDERLINE | `acspflg` (input (request)) |
| `INFOMSG` | 45 | 22,23 | ASKIP | NEUTRAL | HILIGHT=OFF | `infomsg` (output (response)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |
| `FKEYS` | 21 | 24,1 | ASKIP,NORM | YELLOW | alnum | `fkeys` (output (response)) |
| `FKEY05` | 7 | 24,23 | ASKIP,DRK | YELLOW | alnum | `fkey05` (output (response)) |
| `FKEY12` | 10 | 24,31 | ASKIP,DRK | YELLOW | alnum | `fkey12` (output (response)) |

#### 3.2.2 Account View — `COACTVW.bms` -> `dto/AccountViewRequest.java` / `dto/AccountViewResponse.java` (37 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,NORM | BLUE | alnum | `curtime` (output (response)) |
| `ACCTSID` | 11 | 5,38 | FSET,IC,NORM,UNPROT | GREEN | PICIN=99999999999; VALIDN=MUSTFILL; HILIGHT=UNDERLINE | `acctsid` (input (request)) |
| `ACSTTUS` | 1 | 5,70 | ASKIP | - | HILIGHT=UNDERLINE | `acsttus` (output (response)) |
| `ADTOPEN` | 10 | 6,17 | - | - | HILIGHT=UNDERLINE | `adtopen` (output (response)) |
| `ACRDLIM` | 15 | 6,61 | - | - | PICOUT=+ZZZ,ZZZ,ZZZ.99; HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acrdlim` (output (response)) |
| `AEXPDT` | 10 | 7,17 | - | - | HILIGHT=UNDERLINE | `aexpdt` (output (response)) |
| `ACSHLIM` | 15 | 7,61 | - | - | PICOUT=+ZZZ,ZZZ,ZZZ.99; HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acshlim` (output (response)) |
| `AREISDT` | 10 | 8,17 | - | - | HILIGHT=UNDERLINE | `areisdt` (output (response)) |
| `ACURBAL` | 15 | 8,61 | - | - | PICOUT=+ZZZ,ZZZ,ZZZ.99; HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acurbal` (output (response)) |
| `ACRCYCR` | 15 | 9,61 | - | - | PICOUT=+ZZZ,ZZZ,ZZZ.99; HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acrcycr` (output (response)) |
| `AADDGRP` | 10 | 10,23 | - | - | HILIGHT=UNDERLINE | `aaddgrp` (output (response)) |
| `ACRCYDB` | 15 | 10,61 | - | - | PICOUT=+ZZZ,ZZZ,ZZZ.99; HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acrcydb` (output (response)) |
| `ACSTNUM` | 9 | 12,23 | - | - | HILIGHT=UNDERLINE | `acstnum` (output (response)) |
| `ACSTSSN` | 12 | 12,54 | - | - | HILIGHT=UNDERLINE | `acstssn` (output (response)) |
| `ACSTDOB` | 10 | 13,23 | - | - | HILIGHT=UNDERLINE | `acstdob` (output (response)) |
| `ACSTFCO` | 3 | 13,61 | - | - | HILIGHT=UNDERLINE | `acstfco` (output (response)) |
| `ACSFNAM` | 25 | 15,1 | - | - | HILIGHT=UNDERLINE | `acsfnam` (output (response)) |
| `ACSMNAM` | 25 | 15,28 | - | - | HILIGHT=UNDERLINE | `acsmnam` (output (response)) |
| `ACSLNAM` | 25 | 15,55 | - | - | HILIGHT=UNDERLINE | `acslnam` (output (response)) |
| `ACSADL1` | 50 | 16,10 | - | - | HILIGHT=UNDERLINE | `acsadl1` (output (response)) |
| `ACSSTTE` | 2 | 16,73 | - | - | HILIGHT=UNDERLINE | `acsstte` (output (response)) |
| `ACSADL2` | 50 | 17,10 | - | - | HILIGHT=UNDERLINE | `acsadl2` (output (response)) |
| `ACSZIPC` | 5 | 17,73 | - | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `acszipc` (output (response)) |
| `ACSCITY` | 50 | 18,10 | - | - | HILIGHT=UNDERLINE | `acscity` (output (response)) |
| `ACSCTRY` | 3 | 18,73 | - | - | HILIGHT=UNDERLINE | `acsctry` (output (response)) |
| `ACSPHN1` | 13 | 19,10 | - | - | HILIGHT=UNDERLINE | `acsphn1` (output (response)) |
| `ACSGOVT` | 20 | 19,58 | - | - | HILIGHT=UNDERLINE | `acsgovt` (output (response)) |
| `ACSPHN2` | 13 | 20,10 | - | - | HILIGHT=UNDERLINE | `acsphn2` (output (response)) |
| `ACSEFTC` | 10 | 20,41 | - | - | HILIGHT=UNDERLINE | `acseftc` (output (response)) |
| `ACSPFLG` | 1 | 20,78 | - | - | HILIGHT=UNDERLINE | `acspflg` (output (response)) |
| `INFOMSG` | 45 | 22,23 | PROT | NEUTRAL | HILIGHT=OFF | `infomsg` (output (response)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.3 Admin Menu — `COADM01.bms` -> `dto/AdminMenuRequest.java` / `dto/AdminMenuResponse.java` (20 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `OPTN001` | 40 | 6,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn001` (output (response)) |
| `OPTN002` | 40 | 7,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn002` (output (response)) |
| `OPTN003` | 40 | 8,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn003` (output (response)) |
| `OPTN004` | 40 | 9,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn004` (output (response)) |
| `OPTN005` | 40 | 10,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn005` (output (response)) |
| `OPTN006` | 40 | 11,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn006` (output (response)) |
| `OPTN007` | 40 | 12,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn007` (output (response)) |
| `OPTN008` | 40 | 13,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn008` (output (response)) |
| `OPTN009` | 40 | 14,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn009` (output (response)) |
| `OPTN010` | 40 | 15,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn010` (output (response)) |
| `OPTN011` | 40 | 16,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn011` (output (response)) |
| `OPTN012` | 40 | 17,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn012` (output (response)) |
| `OPTION` | 2 | 20,41 | FSET,IC,NORM,NUM,UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT,ZERO | `option` (input (request)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.4 Bill Payment — `COBIL00.bms` -> `dto/BillPaymentRequest.java` / `dto/BillPaymentResponse.java` (10 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `ACTIDIN` | 11 | 6,21 | FSET,IC,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `actidin` (input (request)) |
| `CURBAL` | 14 | 11,32 | ASKIP,FSET,NORM | BLUE | alnum | `curbal` (output (response)) |
| `CONFIRM` | 1 | 15,60 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `confirm` (input (request)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.5 Card List — `COCRDLI.bms` -> `dto/CardListRequest.java` / `dto/CardListResponse.java` (45 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,NORM | BLUE | alnum | `curtime` (output (response)) |
| `PAGENO` | 3 | 4,76 | - | - | alnum | `pageno` (output (response)) |
| `ACCTSID` | 11 | 6,44 | FSET,IC,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `acctsid` (input (request)) |
| `CARDSID` | 16 | 7,44 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `cardsid` (input (request)) |
| `CRDSEL1` | 1 | 11,12 | FSET,NORM,PROT | DEFAULT | HILIGHT=UNDERLINE | `crdsel1` (output (response)) |
| `ACCTNO1` | 11 | 11,22 | NORM,PROT | DEFAULT | HILIGHT=OFF | `acctno1` (output (response)) |
| `CRDNUM1` | 16 | 11,43 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdnum1` (output (response)) |
| `CRDSTS1` | 1 | 11,67 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdsts1` (output (response)) |
| `CRDSEL2` | 1 | 12,12 | FSET,NORM,PROT | DEFAULT | HILIGHT=UNDERLINE | `crdsel2` (output (response)) |
| `CRDSTP2` | 1 | 12,14 | ASKIP,DRK,FSET | DEFAULT | HILIGHT=OFF | `crdstp2` (output (response)) |
| `ACCTNO2` | 11 | 12,22 | NORM,PROT | DEFAULT | HILIGHT=OFF | `acctno2` (output (response)) |
| `CRDNUM2` | 16 | 12,43 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdnum2` (output (response)) |
| `CRDSTS2` | 1 | 12,67 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdsts2` (output (response)) |
| `CRDSEL3` | 1 | 13,12 | FSET,NORM,PROT | DEFAULT | HILIGHT=UNDERLINE | `crdsel3` (output (response)) |
| `CRDSTP3` | 1 | 13,14 | ASKIP,DRK,FSET | DEFAULT | HILIGHT=OFF | `crdstp3` (output (response)) |
| `ACCTNO3` | 11 | 13,22 | NORM,PROT | DEFAULT | HILIGHT=OFF | `acctno3` (output (response)) |
| `CRDNUM3` | 16 | 13,43 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdnum3` (output (response)) |
| `CRDSTS3` | 1 | 13,67 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdsts3` (output (response)) |
| `CRDSEL4` | 1 | 14,12 | FSET,NORM,PROT | DEFAULT | HILIGHT=UNDERLINE | `crdsel4` (output (response)) |
| `CRDSTP4` | 1 | 14,14 | ASKIP,DRK,FSET | DEFAULT | HILIGHT=OFF | `crdstp4` (output (response)) |
| `ACCTNO4` | 11 | 14,22 | NORM,PROT | DEFAULT | HILIGHT=OFF | `acctno4` (output (response)) |
| `CRDNUM4` | 16 | 14,43 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdnum4` (output (response)) |
| `CRDSTS4` | 1 | 14,67 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdsts4` (output (response)) |
| `CRDSEL5` | 1 | 15,12 | FSET,NORM,PROT | DEFAULT | HILIGHT=UNDERLINE | `crdsel5` (output (response)) |
| `CRDSTP5` | 1 | 15,14 | ASKIP,DRK,FSET | DEFAULT | HILIGHT=OFF | `crdstp5` (output (response)) |
| `ACCTNO5` | 11 | 15,22 | NORM,PROT | DEFAULT | HILIGHT=OFF | `acctno5` (output (response)) |
| `CRDNUM5` | 16 | 15,43 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdnum5` (output (response)) |
| `CRDSTS5` | 1 | 15,67 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdsts5` (output (response)) |
| `CRDSEL6` | 1 | 16,12 | FSET,NORM,PROT | DEFAULT | HILIGHT=UNDERLINE | `crdsel6` (output (response)) |
| `CRDSTP6` | 1 | 16,14 | ASKIP,DRK,FSET | DEFAULT | HILIGHT=OFF | `crdstp6` (output (response)) |
| `ACCTNO6` | 11 | 16,22 | NORM,PROT | DEFAULT | HILIGHT=OFF | `acctno6` (output (response)) |
| `CRDNUM6` | 16 | 16,43 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdnum6` (output (response)) |
| `CRDSTS6` | 1 | 16,67 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdsts6` (output (response)) |
| `CRDSEL7` | 1 | 17,12 | FSET,NORM,PROT | DEFAULT | HILIGHT=UNDERLINE | `crdsel7` (output (response)) |
| `CRDSTP7` | 1 | 17,14 | ASKIP,DRK,FSET | DEFAULT | HILIGHT=OFF | `crdstp7` (output (response)) |
| `ACCTNO7` | 11 | 17,22 | NORM,PROT | DEFAULT | HILIGHT=OFF | `acctno7` (output (response)) |
| `CRDNUM7` | 16 | 17,43 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdnum7` (output (response)) |
| `CRDSTS7` | 1 | 17,67 | NORM,PROT | DEFAULT | HILIGHT=OFF | `crdsts7` (output (response)) |
| `INFOMSG` | 45 | 20,19 | PROT | NEUTRAL | HILIGHT=OFF | `infomsg` (output (response)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.6 Card View — `COCRDSL.bms` -> `dto/CardViewRequest.java` / `dto/CardViewResponse.java` (15 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,NORM | BLUE | alnum | `curtime` (output (response)) |
| `ACCTSID` | 11 | 7,45 | FSET,IC,NORM,UNPROT | DEFAULT | HILIGHT=UNDERLINE | `acctsid` (input (request)) |
| `CARDSID` | 16 | 8,45 | FSET,NORM,UNPROT | DEFAULT | HILIGHT=UNDERLINE | `cardsid` (input (request)) |
| `CRDNAME` | 50 | 11,25 | - | - | HILIGHT=UNDERLINE | `crdname` (output (response)) |
| `CRDSTCD` | 1 | 13,25 | ASKIP | - | HILIGHT=UNDERLINE | `crdstcd` (output (response)) |
| `EXPMON` | 2 | 15,25 | ASKIP | - | HILIGHT=UNDERLINE | `expmon` (output (response)) |
| `EXPYEAR` | 4 | 15,30 | ASKIP | - | HILIGHT=UNDERLINE | `expyear` (output (response)) |
| `INFOMSG` | 40 | 20,25 | PROT | NEUTRAL | HILIGHT=OFF | `infomsg` (output (response)) |
| `ERRMSG` | 80 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |
| `FKEYS` | 75 | 24,1 | ASKIP,NORM | YELLOW | alnum | `fkeys` (output (response)) |

#### 3.2.7 Card Update — `COCRDUP.bms` -> `dto/CardUpdateRequest.java` / `dto/CardUpdateResponse.java` (17 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,NORM | BLUE | alnum | `curtime` (output (response)) |
| `ACCTSID` | 11 | 7,45 | FSET,IC,NORM,PROT | DEFAULT | HILIGHT=UNDERLINE | `acctsid` (output (response)) |
| `CARDSID` | 16 | 8,45 | FSET,NORM,UNPROT | DEFAULT | HILIGHT=UNDERLINE | `cardsid` (input (request)) |
| `CRDNAME` | 50 | 11,25 | UNPROT | - | HILIGHT=UNDERLINE | `crdname` (input (request)) |
| `CRDSTCD` | 1 | 13,25 | UNPROT | - | HILIGHT=UNDERLINE | `crdstcd` (input (request)) |
| `EXPMON` | 2 | 15,25 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `expmon` (input (request)) |
| `EXPYEAR` | 4 | 15,30 | UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT | `expyear` (input (request)) |
| `EXPDAY` | 2 | 15,36 | DRK,FSET,PROT | - | HILIGHT=OFF; JUSTIFY=RIGHT | `expday` (output (response)) |
| `INFOMSG` | 40 | 20,25 | PROT | NEUTRAL | HILIGHT=OFF | `infomsg` (output (response)) |
| `ERRMSG` | 80 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |
| `FKEYS` | 21 | 24,1 | ASKIP,NORM | YELLOW | alnum | `fkeys` (output (response)) |
| `FKEYSC` | 18 | 24,23 | ASKIP,DRK | YELLOW | alnum | `fkeysc` (output (response)) |

#### 3.2.8 Main Menu — `COMEN01.bms` -> `dto/MainMenuRequest.java` / `dto/MainMenuResponse.java` (20 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `OPTN001` | 40 | 6,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn001` (output (response)) |
| `OPTN002` | 40 | 7,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn002` (output (response)) |
| `OPTN003` | 40 | 8,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn003` (output (response)) |
| `OPTN004` | 40 | 9,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn004` (output (response)) |
| `OPTN005` | 40 | 10,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn005` (output (response)) |
| `OPTN006` | 40 | 11,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn006` (output (response)) |
| `OPTN007` | 40 | 12,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn007` (output (response)) |
| `OPTN008` | 40 | 13,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn008` (output (response)) |
| `OPTN009` | 40 | 14,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn009` (output (response)) |
| `OPTN010` | 40 | 15,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn010` (output (response)) |
| `OPTN011` | 40 | 16,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn011` (output (response)) |
| `OPTN012` | 40 | 17,20 | ASKIP,FSET,NORM | BLUE | alnum | `optn012` (output (response)) |
| `OPTION` | 2 | 20,41 | FSET,IC,NORM,NUM,UNPROT | - | HILIGHT=UNDERLINE; JUSTIFY=RIGHT,ZERO | `option` (input (request)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.9 Transaction Report — `CORPT00.bms` -> `dto/TransactionReportRequest.java` / `dto/TransactionReportResponse.java` (17 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `MONTHLY` | 1 | 7,10 | FSET,IC,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `monthly` (input (request)) |
| `YEARLY` | 1 | 9,10 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `yearly` (input (request)) |
| `CUSTOM` | 1 | 11,10 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `custom` (input (request)) |
| `SDTMM` | 2 | 13,29 | FSET,NORM,NUM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sdtmm` (input (request)) |
| `SDTDD` | 2 | 13,34 | FSET,NORM,NUM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sdtdd` (input (request)) |
| `SDTYYYY` | 4 | 13,39 | FSET,NORM,NUM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sdtyyyy` (input (request)) |
| `EDTMM` | 2 | 14,29 | FSET,NORM,NUM,UNPROT | GREEN | HILIGHT=UNDERLINE | `edtmm` (input (request)) |
| `EDTDD` | 2 | 14,34 | FSET,NORM,NUM,UNPROT | GREEN | HILIGHT=UNDERLINE | `edtdd` (input (request)) |
| `EDTYYYY` | 4 | 14,39 | FSET,NORM,NUM,UNPROT | GREEN | HILIGHT=UNDERLINE | `edtyyyy` (input (request)) |
| `CONFIRM` | 1 | 19,66 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `confirm` (input (request)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.10 Sign-on — `COSGN00.bms` -> `dto/SignonRequest.java` / `dto/SignonResponse.java` (11 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,8 | FSET,NORM,PROT | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 9 | 2,71 | FSET,NORM,PROT | BLUE | alnum | `curtime` (output (response)) |
| `APPLID` | 8 | 3,8 | FSET,NORM,PROT | BLUE | alnum | `applid` (output (response)) |
| `SYSID` | 8 | 3,71 | FSET,NORM,PROT | BLUE | alnum | `sysid` (output (response)) |
| `USERID` | 8 | 19,43 | FSET,IC,NORM,UNPROT | GREEN | HILIGHT=OFF | `userid` (input (request)) |
| `PASSWD` | 8 | 20,43 | DRK,FSET,UNPROT | GREEN | HILIGHT=OFF | `passwd` (input (request)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.11 Transaction List — `COTRN00.bms` -> `dto/TransactionListRequest.java` / `dto/TransactionListResponse.java` (59 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `PAGENUM` | 8 | 4,71 | ASKIP,FSET,NORM | BLUE | alnum | `pagenum` (output (response)) |
| `TRNIDIN` | 16 | 6,21 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `trnidin` (input (request)) |
| `SEL0001` | 1 | 10,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0001` (input (request)) |
| `TRNID01` | 16 | 10,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid01` (output (response)) |
| `TDATE01` | 8 | 10,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate01` (output (response)) |
| `TDESC01` | 26 | 10,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc01` (output (response)) |
| `TAMT001` | 12 | 10,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt001` (output (response)) |
| `SEL0002` | 1 | 11,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0002` (input (request)) |
| `TRNID02` | 16 | 11,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid02` (output (response)) |
| `TDATE02` | 8 | 11,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate02` (output (response)) |
| `TDESC02` | 26 | 11,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc02` (output (response)) |
| `TAMT002` | 12 | 11,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt002` (output (response)) |
| `SEL0003` | 1 | 12,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0003` (input (request)) |
| `TRNID03` | 16 | 12,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid03` (output (response)) |
| `TDATE03` | 8 | 12,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate03` (output (response)) |
| `TDESC03` | 26 | 12,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc03` (output (response)) |
| `TAMT003` | 12 | 12,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt003` (output (response)) |
| `SEL0004` | 1 | 13,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0004` (input (request)) |
| `TRNID04` | 16 | 13,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid04` (output (response)) |
| `TDATE04` | 8 | 13,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate04` (output (response)) |
| `TDESC04` | 26 | 13,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc04` (output (response)) |
| `TAMT004` | 12 | 13,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt004` (output (response)) |
| `SEL0005` | 1 | 14,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0005` (input (request)) |
| `TRNID05` | 16 | 14,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid05` (output (response)) |
| `TDATE05` | 8 | 14,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate05` (output (response)) |
| `TDESC05` | 26 | 14,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc05` (output (response)) |
| `TAMT005` | 12 | 14,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt005` (output (response)) |
| `SEL0006` | 1 | 15,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0006` (input (request)) |
| `TRNID06` | 16 | 15,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid06` (output (response)) |
| `TDATE06` | 8 | 15,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate06` (output (response)) |
| `TDESC06` | 26 | 15,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc06` (output (response)) |
| `TAMT006` | 12 | 15,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt006` (output (response)) |
| `SEL0007` | 1 | 16,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0007` (input (request)) |
| `TRNID07` | 16 | 16,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid07` (output (response)) |
| `TDATE07` | 8 | 16,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate07` (output (response)) |
| `TDESC07` | 26 | 16,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc07` (output (response)) |
| `TAMT007` | 12 | 16,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt007` (output (response)) |
| `SEL0008` | 1 | 17,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0008` (input (request)) |
| `TRNID08` | 16 | 17,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid08` (output (response)) |
| `TDATE08` | 8 | 17,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate08` (output (response)) |
| `TDESC08` | 26 | 17,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc08` (output (response)) |
| `TAMT008` | 12 | 17,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt008` (output (response)) |
| `SEL0009` | 1 | 18,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0009` (input (request)) |
| `TRNID09` | 16 | 18,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid09` (output (response)) |
| `TDATE09` | 8 | 18,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate09` (output (response)) |
| `TDESC09` | 26 | 18,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc09` (output (response)) |
| `TAMT009` | 12 | 18,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt009` (output (response)) |
| `SEL0010` | 1 | 19,3 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0010` (input (request)) |
| `TRNID10` | 16 | 19,8 | ASKIP,FSET,NORM | BLUE | alnum | `trnid10` (output (response)) |
| `TDATE10` | 8 | 19,27 | ASKIP,FSET,NORM | BLUE | alnum | `tdate10` (output (response)) |
| `TDESC10` | 26 | 19,38 | ASKIP,FSET,NORM | BLUE | alnum | `tdesc10` (output (response)) |
| `TAMT010` | 12 | 19,67 | ASKIP,FSET,NORM | BLUE | alnum | `tamt010` (output (response)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.12 Transaction View — `COTRN01.bms` -> `dto/TransactionViewRequest.java` / `dto/TransactionViewResponse.java` (21 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `TRNIDIN` | 16 | 6,21 | FSET,IC,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `trnidin` (input (request)) |
| `TRNID` | 16 | 10,22 | ASKIP,NORM | BLUE | alnum | `trnid` (output (response)) |
| `CARDNUM` | 16 | 10,58 | ASKIP,NORM | BLUE | alnum | `cardnum` (output (response)) |
| `TTYPCD` | 2 | 12,15 | ASKIP,NORM | BLUE | alnum | `ttypcd` (output (response)) |
| `TCATCD` | 4 | 12,36 | ASKIP,NORM | BLUE | alnum | `tcatcd` (output (response)) |
| `TRNSRC` | 10 | 12,54 | ASKIP,NORM | BLUE | alnum | `trnsrc` (output (response)) |
| `TDESC` | 60 | 14,19 | ASKIP,NORM | BLUE | alnum | `tdesc` (output (response)) |
| `TRNAMT` | 12 | 16,14 | ASKIP,NORM | BLUE | alnum | `trnamt` (output (response)) |
| `TORIGDT` | 10 | 16,42 | ASKIP,NORM | BLUE | alnum | `torigdt` (output (response)) |
| `TPROCDT` | 10 | 16,68 | ASKIP,NORM | BLUE | alnum | `tprocdt` (output (response)) |
| `MID` | 9 | 18,19 | ASKIP,NORM | BLUE | alnum | `mid` (output (response)) |
| `MNAME` | 30 | 18,48 | ASKIP,NORM | BLUE | alnum | `mname` (output (response)) |
| `MCITY` | 25 | 20,21 | ASKIP,NORM | BLUE | alnum | `mcity` (output (response)) |
| `MZIP` | 10 | 20,67 | ASKIP,NORM | BLUE | alnum | `mzip` (output (response)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.13 Transaction Add — `COTRN02.bms` -> `dto/TransactionAddRequest.java` / `dto/TransactionAddResponse.java` (21 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `ACTIDIN` | 11 | 6,21 | FSET,IC,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `actidin` (input (request)) |
| `CARDNIN` | 16 | 6,55 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `cardnin` (input (request)) |
| `TTYPCD` | 2 | 10,15 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `ttypcd` (input (request)) |
| `TCATCD` | 4 | 10,36 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `tcatcd` (input (request)) |
| `TRNSRC` | 10 | 10,54 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `trnsrc` (input (request)) |
| `TDESC` | 60 | 12,19 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `tdesc` (input (request)) |
| `TRNAMT` | 12 | 14,14 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `trnamt` (input (request)) |
| `TORIGDT` | 10 | 14,42 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `torigdt` (input (request)) |
| `TPROCDT` | 10 | 14,68 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `tprocdt` (input (request)) |
| `MID` | 9 | 16,19 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `mid` (input (request)) |
| `MNAME` | 30 | 16,48 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `mname` (input (request)) |
| `MCITY` | 25 | 18,21 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `mcity` (input (request)) |
| `MZIP` | 10 | 18,67 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `mzip` (input (request)) |
| `CONFIRM` | 1 | 21,63 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `confirm` (input (request)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.14 List Users — `COUSR00.bms` -> `dto/UserListRequest.java` / `dto/UserListResponse.java` (59 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `PAGENUM` | 8 | 4,71 | ASKIP,FSET,NORM | BLUE | alnum | `pagenum` (output (response)) |
| `USRIDIN` | 8 | 6,21 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `usridin` (input (request)) |
| `SEL0001` | 1 | 10,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0001` (input (request)) |
| `USRID01` | 8 | 10,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid01` (output (response)) |
| `FNAME01` | 20 | 10,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname01` (output (response)) |
| `LNAME01` | 20 | 10,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname01` (output (response)) |
| `UTYPE01` | 1 | 10,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype01` (output (response)) |
| `SEL0002` | 1 | 11,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0002` (input (request)) |
| `USRID02` | 8 | 11,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid02` (output (response)) |
| `FNAME02` | 20 | 11,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname02` (output (response)) |
| `LNAME02` | 20 | 11,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname02` (output (response)) |
| `UTYPE02` | 1 | 11,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype02` (output (response)) |
| `SEL0003` | 1 | 12,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0003` (input (request)) |
| `USRID03` | 8 | 12,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid03` (output (response)) |
| `FNAME03` | 20 | 12,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname03` (output (response)) |
| `LNAME03` | 20 | 12,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname03` (output (response)) |
| `UTYPE03` | 1 | 12,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype03` (output (response)) |
| `SEL0004` | 1 | 13,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0004` (input (request)) |
| `USRID04` | 8 | 13,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid04` (output (response)) |
| `FNAME04` | 20 | 13,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname04` (output (response)) |
| `LNAME04` | 20 | 13,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname04` (output (response)) |
| `UTYPE04` | 1 | 13,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype04` (output (response)) |
| `SEL0005` | 1 | 14,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0005` (input (request)) |
| `USRID05` | 8 | 14,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid05` (output (response)) |
| `FNAME05` | 20 | 14,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname05` (output (response)) |
| `LNAME05` | 20 | 14,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname05` (output (response)) |
| `UTYPE05` | 1 | 14,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype05` (output (response)) |
| `SEL0006` | 1 | 15,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0006` (input (request)) |
| `USRID06` | 8 | 15,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid06` (output (response)) |
| `FNAME06` | 20 | 15,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname06` (output (response)) |
| `LNAME06` | 20 | 15,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname06` (output (response)) |
| `UTYPE06` | 1 | 15,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype06` (output (response)) |
| `SEL0007` | 1 | 16,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0007` (input (request)) |
| `USRID07` | 8 | 16,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid07` (output (response)) |
| `FNAME07` | 20 | 16,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname07` (output (response)) |
| `LNAME07` | 20 | 16,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname07` (output (response)) |
| `UTYPE07` | 1 | 16,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype07` (output (response)) |
| `SEL0008` | 1 | 17,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0008` (input (request)) |
| `USRID08` | 8 | 17,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid08` (output (response)) |
| `FNAME08` | 20 | 17,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname08` (output (response)) |
| `LNAME08` | 20 | 17,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname08` (output (response)) |
| `UTYPE08` | 1 | 17,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype08` (output (response)) |
| `SEL0009` | 1 | 18,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0009` (input (request)) |
| `USRID09` | 8 | 18,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid09` (output (response)) |
| `FNAME09` | 20 | 18,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname09` (output (response)) |
| `LNAME09` | 20 | 18,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname09` (output (response)) |
| `UTYPE09` | 1 | 18,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype09` (output (response)) |
| `SEL0010` | 1 | 19,6 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `sel0010` (input (request)) |
| `USRID10` | 8 | 19,12 | ASKIP,FSET,NORM | BLUE | alnum | `usrid10` (output (response)) |
| `FNAME10` | 20 | 19,24 | ASKIP,FSET,NORM | BLUE | alnum | `fname10` (output (response)) |
| `LNAME10` | 20 | 19,48 | ASKIP,FSET,NORM | BLUE | alnum | `lname10` (output (response)) |
| `UTYPE10` | 1 | 19,73 | ASKIP,FSET,NORM | BLUE | alnum | `utype10` (output (response)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.15 Add User — `COUSR01.bms` -> `dto/UserAddRequest.java` / `dto/UserAddResponse.java` (12 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `FNAME` | 20 | 8,18 | FSET,IC,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `fname` (input (request)) |
| `LNAME` | 20 | 8,56 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `lname` (input (request)) |
| `USERID` | 8 | 11,15 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `userid` (input (request)) |
| `PASSWD` | 8 | 11,55 | DRK,FSET,UNPROT | GREEN | HILIGHT=UNDERLINE | `passwd` (input (request)) |
| `USRTYPE` | 1 | 14,17 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `usrtype` (input (request)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.16 Update User — `COUSR02.bms` -> `dto/UserUpdateRequest.java` / `dto/UserUpdateResponse.java` (12 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `USRIDIN` | 8 | 6,21 | FSET,IC,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `usridin` (input (request)) |
| `FNAME` | 20 | 11,18 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `fname` (input (request)) |
| `LNAME` | 20 | 11,56 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `lname` (input (request)) |
| `PASSWD` | 8 | 13,16 | DRK,FSET,UNPROT | GREEN | HILIGHT=UNDERLINE | `passwd` (input (request)) |
| `USRTYPE` | 1 | 15,17 | FSET,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `usrtype` (input (request)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

#### 3.2.17 Delete User — `COUSR03.bms` -> `dto/UserDeleteRequest.java` / `dto/UserDeleteResponse.java` (11 fields)

| BMS field | Len | Pos | ATTRB | Color | Type / edit | DTO field (direction) |
|-----------|----:|-----|-------|-------|-------------|-----------------------|
| `TRNNAME` | 4 | 1,7 | ASKIP,FSET,NORM | BLUE | alnum | `trnname` (output (response)) |
| `TITLE01` | 40 | 1,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title01` (output (response)) |
| `CURDATE` | 8 | 1,71 | ASKIP,FSET,NORM | BLUE | alnum | `curdate` (output (response)) |
| `PGMNAME` | 8 | 2,7 | ASKIP,FSET,NORM | BLUE | alnum | `pgmname` (output (response)) |
| `TITLE02` | 40 | 2,21 | ASKIP,FSET,NORM | YELLOW | alnum | `title02` (output (response)) |
| `CURTIME` | 8 | 2,71 | ASKIP,FSET,NORM | BLUE | alnum | `curtime` (output (response)) |
| `USRIDIN` | 8 | 6,21 | FSET,IC,NORM,UNPROT | GREEN | HILIGHT=UNDERLINE | `usridin` (input (request)) |
| `FNAME` | 20 | 11,18 | ASKIP,FSET,NORM | BLUE | HILIGHT=UNDERLINE | `fname` (output (response)) |
| `LNAME` | 20 | 13,18 | ASKIP,FSET,NORM | BLUE | HILIGHT=UNDERLINE | `lname` (output (response)) |
| `USRTYPE` | 1 | 15,17 | ASKIP,FSET,NORM | BLUE | HILIGHT=UNDERLINE | `usrtype` (output (response)) |
| `ERRMSG` | 78 | 23,1 | ASKIP,BRT,FSET | RED | alnum | `errmsg` (output (response)) |

### 3.3 Action-key (AID) traceability

Every online program's **action-key (AID) handling** is preserved as explicit action fields/enums on the request DTO. The full per-program AID set is below — including **PF4, PF5, and PF12**, which the screen-level summary above does not enumerate. Programs handle the AID either by a direct `EVALUATE EIBAID` (`DFHENTER`/`DFHPFnn` from the `DFHAID` copybook) or via the shared `CCARD-AID-*` condition names (also populated from `EIBAID` through the `DFHAID` copybook); both are equivalent AID branches and neither is a silent omission.

| Program | Screen | Action keys (AIDs) handled | Mechanism |
|---------|--------|----------------------------|-----------|
| `COSGN00C` | Sign-on | ENTER, PF3 | direct EVALUATE EIBAID (DFHENTER/DFHPF3) |
| `COMEN01C` | Main Menu | ENTER, PF3 | direct EVALUATE EIBAID |
| `COADM01C` | Admin Menu | ENTER, PF3 | direct EVALUATE EIBAID |
| `COACTVWC` | Account View | ENTER, PF3 | shared DFHAID + CCARD-AID-* condition names |
| `COACTUPC` | Account Update | ENTER, PF3, PF5, PF12 | shared DFHAID + CCARD-AID-* (PF5=confirm, PF12=cancel) |
| `COCRDLIC` | Card List | ENTER, PF3, PF7, PF8 | shared DFHAID + CCARD-AID-* (PF7/PF8 page) |
| `COCRDSLC` | Card View | ENTER, PF3 | shared DFHAID + CCARD-AID-* |
| `COCRDUPC` | Card Update | ENTER, PF3, PF5, PF12 | shared DFHAID + CCARD-AID-* (PF5=confirm, PF12=cancel) |
| `COTRN00C` | Transaction List | ENTER, PF3, PF7, PF8 | direct EVALUATE EIBAID (PF7/PF8 page) |
| `COTRN01C` | Transaction View | ENTER, PF3, PF4, PF5 | direct EVALUATE EIBAID |
| `COTRN02C` | Transaction Add | ENTER, PF3, PF4, PF5 | direct EVALUATE EIBAID |
| `CORPT00C` | Transaction Report | ENTER, PF3 | direct EVALUATE EIBAID |
| `COBIL00C` | Bill Payment | ENTER, PF3, PF4 | direct EVALUATE EIBAID |
| `COUSR00C` | List Users | ENTER, PF3, PF7, PF8 | direct EVALUATE EIBAID (PF7/PF8 page) |
| `COUSR01C` | Add User | ENTER, PF3, PF4 | direct EVALUATE EIBAID |
| `COUSR02C` | Update User | ENTER, PF3, PF4, PF5, PF12 | direct EVALUATE EIBAID |
| `COUSR03C` | Delete User | ENTER, PF3, PF4, PF5, PF12 | direct EVALUATE EIBAID |

**AID semantics (preserved):** `ENTER` = submit/confirm current screen; `PF3` = back/exit to the calling screen; `PF4` = clear/prompt (context-specific, e.g. Bill Payment, Transaction View/Add, user maintenance); `PF5` = save/confirm changes (account/card update, transaction, user update/delete); `PF7` = page up / previous page (list screens); `PF8` = page down / next page (list screens); `PF12` = cancel/return without saving (account/card update, user update/delete). Any AID a program does not list is treated as invalid and reproduces the legacy "invalid key" message path.

## 4. Paragraph-Level Traceability (the core)

One subsection per program (28 total). Every PROCEDURE DIVISION paragraph from Appendix A appears as its own row, mapped to a concrete Java method or component. Repeated COBOL idioms map consistently per the legend below.

**Idiom legend (applied uniformly):**

- `*-OPEN` / `*-CLOSE` -> Spring Data JPA repository lifecycle (no explicit open/close); flat files -> Spring Batch stream (managed).
- `*-GET-NEXT` -> `RepositoryItemReader.read()` / paged sorted query (batch) or `FlatFileItemReader.read()` (fixed-width input).
- `SEND-*-SCREEN` / `SEND-MAP` -> controller builds response DTO; `RECEIVE-*-SCREEN` / `RECEIVE-MAP` -> controller consumes request DTO.
- `POPULATE-HEADER-INFO` -> response DTO header fields; `PROCESS-ENTER-KEY` -> controller submit handler -> service.
- `PROCESS-PF7/PF8-KEY`, `PROCESS-PAGE-*` -> pagination (`Pageable` next/prev).
- `1xxx/2xxx-EDIT-*` -> `service/rule/*` validation component + Bean Validation.
- `STARTBR/READNEXT/READPREV/ENDBR` -> sorted/paged repository queries (cursor -> `Pageable`/`Sort`).
- `*-EXIT` -> structured control return (no-op); each such paragraph is still listed as its own row.
- `*-ABEND-PROGRAM` / `ABEND-ROUTINE` -> `exception/GlobalExceptionHandler` / thrown exception -> batch job failure.
- `*-DISPLAY-IO-STATUS` -> `exception/FileStatusException` + structured logging.

### 4.1 `CBACT01C.cbl` -> batch/AccountMasterPrintJob

*Account master sequential read/print. Type: batch; 6 paragraphs; JCL (account master print); source: `legacy/cbl/CBACT01C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `1000-ACCTFILE-GET-NEXT` | Read next Account record | AccountMasterPrintJob reader - AccountRepository (paged, key-ordered) / RepositoryItemReader.read() |
| `1100-DISPLAY-ACCT-RECORD` | Format/print record | AccountMasterPrintJob ItemWriter (print/log record) |
| `0000-ACCTFILE-OPEN` | Open Account file | AccountRepository - Spring Data JPA (no explicit open) |
| `9000-ACCTFILE-CLOSE` | Close Account file | AccountRepository - Spring Data JPA (no explicit close) |
| `9999-ABEND-PROGRAM` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `9910-DISPLAY-IO-STATUS` | Display file I/O status | exception/FileStatusException + structured logging (I/O status) |

### 4.2 `CBACT02C.cbl` -> batch/CardMasterPrintJob

*Card master sequential read/print. Type: batch; 5 paragraphs; JCL (card master print); source: `legacy/cbl/CBACT02C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `1000-CARDFILE-GET-NEXT` | Read next Card record | CardMasterPrintJob reader - CardRepository (paged, key-ordered) / RepositoryItemReader.read() |
| `0000-CARDFILE-OPEN` | Open Card file | CardRepository - Spring Data JPA (no explicit open) |
| `9000-CARDFILE-CLOSE` | Close Card file | CardRepository - Spring Data JPA (no explicit close) |
| `9999-ABEND-PROGRAM` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `9910-DISPLAY-IO-STATUS` | Display file I/O status | exception/FileStatusException + structured logging (I/O status) |

### 4.3 `CBACT03C.cbl` -> batch/XrefPrintJob

*Card cross-reference sequential read/print. Type: batch; 5 paragraphs; JCL (xref master print); source: `legacy/cbl/CBACT03C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `1000-XREFFILE-GET-NEXT` | Read next CardXref record | XrefPrintJob reader - CardXrefRepository (paged, key-ordered) / RepositoryItemReader.read() |
| `0000-XREFFILE-OPEN` | Open CardXref file | CardXrefRepository - Spring Data JPA (no explicit open) |
| `9000-XREFFILE-CLOSE` | Close CardXref file | CardXrefRepository - Spring Data JPA (no explicit close) |
| `9999-ABEND-PROGRAM` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `9910-DISPLAY-IO-STATUS` | Display file I/O status | exception/FileStatusException + structured logging (I/O status) |

### 4.4 `CBACT04C.cbl` -> batch/InterestCalculationJob

*Interest & fee calculation (posts interest transactions). Type: batch; 22 paragraphs; JCL INTCALC; source: `legacy/cbl/CBACT04C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0000-TCATBALF-OPEN` | Open TransactionCategoryBalance file | TransactionCategoryBalanceRepository - Spring Data JPA (no explicit open) |
| `0100-XREFFILE-OPEN` | Open CardXref file | CardXrefRepository - Spring Data JPA (no explicit open) |
| `0200-DISCGRP-OPEN` | Open DisclosureGroup file | DisclosureGroupRepository - Spring Data JPA (no explicit open) |
| `0300-ACCTFILE-OPEN` | Open Account file | AccountRepository - Spring Data JPA (no explicit open) |
| `0400-TRANFILE-OPEN` | Open Transaction file | TransactionRepository - Spring Data JPA (no explicit open) |
| `1000-TCATBALF-GET-NEXT` | Read next TransactionCategoryBalance record | InterestCalculationJob reader - TransactionCategoryBalanceRepository (paged, key-ordered) / RepositoryItemReader.read() |
| `1050-UPDATE-ACCOUNT` | Update account with accrued interest | AccountRepository.save (@Version) |
| `1100-GET-ACCT-DATA` | Fetch account data | AccountRepository.findById |
| `1110-GET-XREF-DATA` | Fetch cross-reference data | CardXrefRepository lookup |
| `1200-GET-INTEREST-RATE` | Get disclosure-group interest rate | DisclosureGroupRepository.findByGroupAndTypeAndCategory |
| `1200-A-GET-DEFAULT-INT-RATE` | Get default-group interest rate | DisclosureGroupRepository default-group lookup |
| `1300-COMPUTE-INTEREST` | Compute monthly interest = (TRAN-CAT-BAL * DIS-INT-RATE)/1200 [legacy L464-L465] | InterestCalculationJob processor + domain/type/Money: tranCatBal.multiply(intRate).divide(BigDecimal.valueOf(1200), 2, HALF_UP) |
| `1300-B-WRITE-TX` | Write the interest transaction | TransactionRepository.save (interest txn) |
| `1400-COMPUTE-FEES` | Compute fees | InterestCalculationJob processor (fee computation via Money) |
| `9000-TCATBALF-CLOSE` | Close TransactionCategoryBalance file | TransactionCategoryBalanceRepository - Spring Data JPA (no explicit close) |
| `9100-XREFFILE-CLOSE` | Close CardXref file | CardXrefRepository - Spring Data JPA (no explicit close) |
| `9200-DISCGRP-CLOSE` | Close DisclosureGroup file | DisclosureGroupRepository - Spring Data JPA (no explicit close) |
| `9300-ACCTFILE-CLOSE` | Close Account file | AccountRepository - Spring Data JPA (no explicit close) |
| `9400-TRANFILE-CLOSE` | Close Transaction file | TransactionRepository - Spring Data JPA (no explicit close) |
| `Z-GET-DB2-FORMAT-TIMESTAMP` | Format current timestamp (DB2 format) | java.time (LocalDateTime) timestamp formatting |
| `9999-ABEND-PROGRAM` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `9910-DISPLAY-IO-STATUS` | Display file I/O status | exception/FileStatusException + structured logging (I/O status) |

### 4.5 `CBCUS01C.cbl` -> batch/CustomerMasterPrintJob

*Customer master sequential read/print. Type: batch; 5 paragraphs; JCL (customer master print); source: `legacy/cbl/CBCUS01C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `1000-CUSTFILE-GET-NEXT` | Read next Customer record | CustomerMasterPrintJob reader - CustomerRepository (paged, key-ordered) / RepositoryItemReader.read() |
| `0000-CUSTFILE-OPEN` | Open Customer file | CustomerRepository - Spring Data JPA (no explicit open) |
| `9000-CUSTFILE-CLOSE` | Close Customer file | CustomerRepository - Spring Data JPA (no explicit close) |
| `Z-ABEND-PROGRAM` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `Z-DISPLAY-IO-STATUS` | Display file I/O status | exception/FileStatusException + structured logging (I/O status) |

### 4.6 `CBSTM03A.CBL` -> batch/StatementGenerationJob

*Statement generation (driver). Type: batch; 25 paragraphs; JCL CREASTMT; source: `legacy/cbl/CBSTM03A.CBL`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0000-START` | Statement job entry | StatementGenerationJob Step init |
| `1000-MAINLINE` | Main statement loop | StatementGenerationJob Step (per-account chunk loop) |
| `9999-GOBACK` | Return to caller | Job/Step completion - structured return |
| `1000-XREFFILE-GET-NEXT` | Read next CardXref record | StatementGenerationJob reader - CardXrefRepository (paged, key-ordered) / RepositoryItemReader.read() |
| `2000-CUSTFILE-GET` | Fetch Customer record | CustomerRepository.findById |
| `3000-ACCTFILE-GET` | Fetch Account record | AccountRepository.findById |
| `4000-TRNXFILE-GET` | Fetch Transaction record | TransactionRepository.findById |
| `5000-CREATE-STATEMENT` | Assemble a statement | StatementGenerationJob processor (build statement model) |
| `5100-WRITE-HTML-HEADER` | Write HTML statement header | service/StatementFileService / writer (HTML header) |
| `5100-EXIT` | Structured paragraph return | (no-op) structured control return |
| `5200-WRITE-HTML-NMADBS` | Write name/address block | service/StatementFileService / writer (HTML name/address) |
| `5200-EXIT` | Structured paragraph return | (no-op) structured control return |
| `6000-WRITE-TRANS` | Write statement transaction lines | service/StatementFileService / writer (statement txn lines) |
| `8100-FILE-OPEN` | Open statement files (I/O dispatch) | service/StatementFileService (file open dispatch; no explicit open) |
| `8100-TRNXFILE-OPEN` | Open Transaction file | TransactionRepository - Spring Data JPA (no explicit open) |
| `8200-XREFFILE-OPEN` | Open CardXref file | CardXrefRepository - Spring Data JPA (no explicit open) |
| `8300-CUSTFILE-OPEN` | Open Customer file | CustomerRepository - Spring Data JPA (no explicit open) |
| `8400-ACCTFILE-OPEN` | Open Account file | AccountRepository - Spring Data JPA (no explicit open) |
| `8500-READTRNX-READ` | Read transactions for statement | service/StatementFileService.readTransactions -> TransactionRepository |
| `8599-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9100-TRNXFILE-CLOSE` | Close Transaction file | TransactionRepository - Spring Data JPA (no explicit close) |
| `9200-XREFFILE-CLOSE` | Close CardXref file | CardXrefRepository - Spring Data JPA (no explicit close) |
| `9300-CUSTFILE-CLOSE` | Close Customer file | CustomerRepository - Spring Data JPA (no explicit close) |
| `9400-ACCTFILE-CLOSE` | Close Account file | AccountRepository - Spring Data JPA (no explicit close) |
| `9999-ABEND-PROGRAM` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |

### 4.7 `CBSTM03B.CBL` -> batch/StatementGenerationJob -> service/StatementFileService

*Statement file-I/O subprogram (called by CBSTM03A). Type: batch; 14 paragraphs; JCL CREASTMT (subprogram); source: `legacy/cbl/CBSTM03B.CBL`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0000-START` | File-I/O subprogram entry (function dispatch) | service/StatementFileService entry (operation dispatch) |
| `9999-GOBACK` | Return to caller | Job/Step completion - structured return |
| `1000-TRNXFILE-PROC` | Transaction file operation (dispatch) | service/StatementFileService - Transaction op (TransactionRepository) |
| `1900-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1999-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2000-XREFFILE-PROC` | CardXref file operation (dispatch) | service/StatementFileService - CardXref op (CardXrefRepository) |
| `2900-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2999-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3000-CUSTFILE-PROC` | Customer file operation (dispatch) | service/StatementFileService - Customer op (CustomerRepository) |
| `3900-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3999-EXIT` | Structured paragraph return | (no-op) structured control return |
| `4000-ACCTFILE-PROC` | Account file operation (dispatch) | service/StatementFileService - Account op (AccountRepository) |
| `4900-EXIT` | Structured paragraph return | (no-op) structured control return |
| `4999-EXIT` | Structured paragraph return | (no-op) structured control return |

### 4.8 `CBTRN01C.cbl` -> batch/DailyTransactionValidateJob

*Daily-transaction read & validation. Type: batch; 18 paragraphs; JCL (daily validate); source: `legacy/cbl/CBTRN01C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Main validate driver (chunk loop) | DailyTransactionValidateJob Step (chunk-oriented reader/processor) |
| `1000-DALYTRAN-GET-NEXT` | Read daily transaction | FlatFileItemReader (daily-transaction input).read() (fixed-width via common/util/FixedWidthCodec) |
| `2000-LOOKUP-XREF` | Lookup card cross-reference (validate) | CardXrefRepository lookup (validation) |
| `3000-READ-ACCOUNT` | Read account (validate) | AccountRepository.findById (validation lookup) |
| `0000-DALYTRAN-OPEN` | Open daily transaction stream | FlatFileItemReader (daily-transaction input) - no explicit open (Spring Batch manages stream) |
| `0100-CUSTFILE-OPEN` | Open Customer file | CustomerRepository - Spring Data JPA (no explicit open) |
| `0200-XREFFILE-OPEN` | Open CardXref file | CardXrefRepository - Spring Data JPA (no explicit open) |
| `0300-CARDFILE-OPEN` | Open Card file | CardRepository - Spring Data JPA (no explicit open) |
| `0400-ACCTFILE-OPEN` | Open Account file | AccountRepository - Spring Data JPA (no explicit open) |
| `0500-TRANFILE-OPEN` | Open Transaction file | TransactionRepository - Spring Data JPA (no explicit open) |
| `9000-DALYTRAN-CLOSE` | Close daily transaction stream | FlatFileItemReader (daily-transaction input) - no explicit close (Spring Batch manages stream) |
| `9100-CUSTFILE-CLOSE` | Close Customer file | CustomerRepository - Spring Data JPA (no explicit close) |
| `9200-XREFFILE-CLOSE` | Close CardXref file | CardXrefRepository - Spring Data JPA (no explicit close) |
| `9300-CARDFILE-CLOSE` | Close Card file | CardRepository - Spring Data JPA (no explicit close) |
| `9400-ACCTFILE-CLOSE` | Close Account file | AccountRepository - Spring Data JPA (no explicit close) |
| `9500-TRANFILE-CLOSE` | Close Transaction file | TransactionRepository - Spring Data JPA (no explicit close) |
| `Z-ABEND-PROGRAM` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `Z-DISPLAY-IO-STATUS` | Display file I/O status | exception/FileStatusException + structured logging (I/O status) |

### 4.9 `CBTRN02C.cbl` -> batch/DailyTransactionPostingJob

*Daily-transaction posting (balances, rejects). Type: batch; 26 paragraphs; JCL POSTTRAN; source: `legacy/cbl/CBTRN02C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0000-DALYTRAN-OPEN` | Open daily transaction stream | FlatFileItemReader (daily-transaction input) - no explicit open (Spring Batch manages stream) |
| `0100-TRANFILE-OPEN` | Open Transaction file | TransactionRepository - Spring Data JPA (no explicit open) |
| `0200-XREFFILE-OPEN` | Open CardXref file | CardXrefRepository - Spring Data JPA (no explicit open) |
| `0300-DALYREJS-OPEN` | Open reject record stream | FlatFileItemWriter (reject file) - no explicit open (Spring Batch manages stream) |
| `0400-ACCTFILE-OPEN` | Open Account file | AccountRepository - Spring Data JPA (no explicit open) |
| `0500-TCATBALF-OPEN` | Open TransactionCategoryBalance file | TransactionCategoryBalanceRepository - Spring Data JPA (no explicit open) |
| `1000-DALYTRAN-GET-NEXT` | Read daily transaction | FlatFileItemReader (daily-transaction input).read() (fixed-width via common/util/FixedWidthCodec) |
| `1500-VALIDATE-TRAN` | Validate a daily transaction; drives reject codes (order preserved) | PostingService.validate -> RejectCode 100/101/102/103 (evaluation order preserved) |
| `1500-A-LOOKUP-XREF` | Lookup card cross-reference; reject 100 if card not found [legacy L385] | PostingService -> CardXrefRepository lookup; RejectCode.CARD_XREF_NOT_FOUND (100) |
| `1500-B-LOOKUP-ACCT` | Lookup account; 101 not-found, 102 over-limit (ACCT-CREDIT-LIMIT >= WS-TEMP-BAL) [L407], 103 after expiry [L417] | PostingService -> AccountRepository lookup; RejectCode 101/102/103 |
| `2000-POST-TRANSACTION` | Post a valid transaction (update balances, write txn) | PostingService.post (@Transactional): TransactionRepository.save + balance updates |
| `2500-WRITE-REJECT-REC` | Write rejected record with reason code + running count | FlatFileItemWriter (reject) -> RejectCode + count; 430-byte reject layout (350-byte transaction image + 80-byte validation trailer — see D16 / CF1) via FixedWidthCodec |
| `2700-UPDATE-TCATBAL` | Update transaction-category balance | PostingService -> TransactionCategoryBalanceRepository (create/update) |
| `2700-A-CREATE-TCATBAL-REC` | Create category-balance row when absent | TransactionCategoryBalanceRepository.save (insert) |
| `2700-B-UPDATE-TCATBAL-REC` | Update existing category-balance row | TransactionCategoryBalanceRepository.save (update, @Version) |
| `2800-UPDATE-ACCOUNT-REC` | Update account balance (READ-UPDATE-REWRITE) | AccountRepository.save (@Version optimistic lock) |
| `2900-WRITE-TRANSACTION-FILE` | Write the posted transaction record | TransactionRepository.save (insert) |
| `9000-DALYTRAN-CLOSE` | Close daily transaction stream | FlatFileItemReader (daily-transaction input) - no explicit close (Spring Batch manages stream) |
| `9100-TRANFILE-CLOSE` | Close Transaction file | TransactionRepository - Spring Data JPA (no explicit close) |
| `9200-XREFFILE-CLOSE` | Close CardXref file | CardXrefRepository - Spring Data JPA (no explicit close) |
| `9300-DALYREJS-CLOSE` | Close reject record stream | FlatFileItemWriter (reject file) - no explicit close (Spring Batch manages stream) |
| `9400-ACCTFILE-CLOSE` | Close Account file | AccountRepository - Spring Data JPA (no explicit close) |
| `9500-TCATBALF-CLOSE` | Close TransactionCategoryBalance file | TransactionCategoryBalanceRepository - Spring Data JPA (no explicit close) |
| `Z-GET-DB2-FORMAT-TIMESTAMP` | Format current timestamp (DB2 format) | java.time (LocalDateTime) timestamp formatting |
| `9999-ABEND-PROGRAM` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `9910-DISPLAY-IO-STATUS` | Display file I/O status | exception/FileStatusException + structured logging (I/O status) |

### 4.10 `CBTRN03C.cbl` -> batch/TransactionReportJob

*Transaction detail report. Type: batch; 26 paragraphs; JCL TRANREPT.prc; source: `legacy/cbl/CBTRN03C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0550-DATEPARM-READ` | Read report date-range parameters | JobParameters / date-parameter reader |
| `1000-TRANFILE-GET-NEXT` | Read next Transaction record | TransactionReportJob reader - TransactionRepository (paged, key-ordered) / RepositoryItemReader.read() |
| `1100-WRITE-TRANSACTION-REPORT` | Write transaction report body | TransactionReportJob writer (report body) |
| `1110-WRITE-PAGE-TOTALS` | Accumulate/write page totals | TransactionReportJob writer (page totals) |
| `1120-WRITE-ACCOUNT-TOTALS` | Accumulate/write account totals | TransactionReportJob writer (account totals) |
| `1110-WRITE-GRAND-TOTALS` | Accumulate/write grand totals | TransactionReportJob writer (grand totals) |
| `1120-WRITE-HEADERS` | Write report headers | TransactionReportJob writer (headers) |
| `1111-WRITE-REPORT-REC` | Write a report record line | FlatFileItemWriter.write (report line) |
| `1120-WRITE-DETAIL` | Write a detail line | TransactionReportJob writer (detail line) |
| `0000-TRANFILE-OPEN` | Open Transaction file | TransactionRepository - Spring Data JPA (no explicit open) |
| `0100-REPTFILE-OPEN` | Open report line stream | FlatFileItemWriter (report output) - no explicit open (Spring Batch manages stream) |
| `0200-CARDXREF-OPEN` | Open CardXref file | CardXrefRepository - Spring Data JPA (no explicit open) |
| `0300-TRANTYPE-OPEN` | Open TransactionType file | TransactionTypeRepository - Spring Data JPA (no explicit open) |
| `0400-TRANCATG-OPEN` | Open TransactionCategory file | TransactionCategoryRepository - Spring Data JPA (no explicit open) |
| `0500-DATEPARM-OPEN` | Open date parameter stream | JobParameters / date-parameter reader - no explicit open (Spring Batch manages stream) |
| `1500-A-LOOKUP-XREF` | Lookup card cross-reference | CardXrefRepository lookup |
| `1500-B-LOOKUP-TRANTYPE` | Lookup transaction type | TransactionTypeRepository lookup |
| `1500-C-LOOKUP-TRANCATG` | Lookup transaction category | TransactionCategoryRepository lookup |
| `9000-TRANFILE-CLOSE` | Close Transaction file | TransactionRepository - Spring Data JPA (no explicit close) |
| `9100-REPTFILE-CLOSE` | Close report line stream | FlatFileItemWriter (report output) - no explicit close (Spring Batch manages stream) |
| `9200-CARDXREF-CLOSE` | Close CardXref file | CardXrefRepository - Spring Data JPA (no explicit close) |
| `9300-TRANTYPE-CLOSE` | Close TransactionType file | TransactionTypeRepository - Spring Data JPA (no explicit close) |
| `9400-TRANCATG-CLOSE` | Close TransactionCategory file | TransactionCategoryRepository - Spring Data JPA (no explicit close) |
| `9500-DATEPARM-CLOSE` | Close date parameter stream | JobParameters / date-parameter reader - no explicit close (Spring Batch manages stream) |
| `9999-ABEND-PROGRAM` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `9910-DISPLAY-IO-STATUS` | Display file I/O status | exception/FileStatusException + structured logging (I/O status) |

### 4.11 `COACTUPC.cbl` -> web/AccountUpdateController + service/AccountService

*Account update (largest online program; extensive edits). Type: online; 85 paragraphs; txn CAUP; source: `legacy/cbl/COACTUPC.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0000-MAIN` | Program entry (main flow) | AccountUpdateController request entry (main flow) |
| `COMMON-RETURN` | Common return / navigation | AccountUpdateController navigation return (COMMAREA/XCTL -> next view) |
| `0000-MAIN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1000-PROCESS-INPUTS` | Process inputs (receive + validate) | AccountUpdateController input processing -> AccountService + service/rule/* |
| `1000-PROCESS-INPUTS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1100-RECEIVE-MAP` | Receive/parse the map (input) | AccountUpdateController consumes request DTO |
| `1100-RECEIVE-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1200-EDIT-MAP-INPUTS` | Validate all map inputs | AccountService + service/rule/* (Bean Validation orchestration) |
| `1200-EDIT-MAP-INPUTS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1205-COMPARE-OLD-NEW` | Compare old vs new field values | AccountService change-detection (old vs new) |
| `1205-COMPARE-OLD-NEW-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1210-EDIT-ACCOUNT` | Validate account id | service/rule/AccountNumberRule |
| `1210-EDIT-ACCOUNT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1215-EDIT-MANDATORY` | Validate mandatory presence | service/rule/MandatoryRule |
| `1215-EDIT-MANDATORY-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1220-EDIT-YESNO` | Validate Y/N flag | service/rule/YesNoRule |
| `1220-EDIT-YESNO-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1225-EDIT-ALPHA-REQD` | Validate required alphabetic | service/rule/AlphaRequiredRule |
| `1225-EDIT-ALPHA-REQD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1230-EDIT-ALPHANUM-REQD` | Validate required alphanumeric | service/rule/AlphanumRequiredRule |
| `1230-EDIT-ALPHANUM-REQD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1235-EDIT-ALPHA-OPT` | Validate optional alphabetic | service/rule/AlphaOptionalRule |
| `1235-EDIT-ALPHA-OPT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1240-EDIT-ALPHANUM-OPT` | Validate optional alphanumeric | service/rule/AlphanumOptionalRule |
| `1240-EDIT-ALPHANUM-OPT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1245-EDIT-NUM-REQD` | Validate required numeric | service/rule/NumericRequiredRule |
| `1245-EDIT-NUM-REQD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1250-EDIT-SIGNED-9V2` | Validate signed 9(n)V99 amount | service/rule/SignedDecimalRule (BigDecimal scale 2) |
| `1250-EDIT-SIGNED-9V2-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1260-EDIT-US-PHONE-NUM` | Validate US phone number | service/rule/USPhoneRule |
| `EDIT-AREA-CODE` | Validate phone area code | USPhoneRule.validateAreaCode |
| `EDIT-US-PHONE-PREFIX` | Validate phone prefix (exchange) | USPhoneRule.validatePrefix |
| `EDIT-US-PHONE-LINENUM` | Validate phone line number | USPhoneRule.validateLineNumber |
| `EDIT-US-PHONE-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1260-EDIT-US-PHONE-NUM-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1265-EDIT-US-SSN` | Validate US SSN | service/rule/UsSsnRule |
| `1265-EDIT-US-SSN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1270-EDIT-US-STATE-CD` | Validate US state code | service/rule/UsStateRule |
| `1270-EDIT-US-STATE-CD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1275-EDIT-FICO-SCORE` | Validate FICO score range | service/rule/FicoScoreRule |
| `1275-EDIT-FICO-SCORE-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1280-EDIT-US-STATE-ZIP-CD` | Validate state+ZIP consistency | service/rule/StateZipRule |
| `1280-EDIT-US-STATE-ZIP-CD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2000-DECIDE-ACTION` | Decide action (PF-key/action) | AccountUpdateController action dispatch (PF-key/action routing) |
| `2000-DECIDE-ACTION-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3000-SEND-MAP` | Build/send the map (output) | AccountUpdateController builds response DTO |
| `3000-SEND-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3100-SCREEN-INIT` | Initialize screen defaults | AccountUpdateController initializes response DTO defaults |
| `3100-SCREEN-INIT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3200-SETUP-SCREEN-VARS` | Populate screen fields | mapper/AccountMapper populates response DTO fields |
| `3200-SETUP-SCREEN-VARS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3201-SHOW-INITIAL-VALUES` | Show initial values | mapper/AccountMapper -> DTO (initial values) |
| `3201-SHOW-INITIAL-VALUES-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3202-SHOW-ORIGINAL-VALUES` | Show original values | mapper/AccountMapper -> DTO (original values) |
| `3202-SHOW-ORIGINAL-VALUES-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3203-SHOW-UPDATED-VALUES` | Show updated values | mapper/AccountMapper -> DTO (updated values) |
| `3203-SHOW-UPDATED-VALUES-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3250-SETUP-INFOMSG` | Set info message | response DTO info/error message field |
| `3250-SETUP-INFOMSG-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3300-SETUP-SCREEN-ATTRS` | Set field attributes | response DTO field attribute/edit-state flags |
| `3300-SETUP-SCREEN-ATTRS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3310-PROTECT-ALL-ATTRS` | Protect all fields | DTO field state: all protected (read-only) |
| `3310-PROTECT-ALL-ATTRS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3320-UNPROTECT-FEW-ATTRS` | Unprotect editable fields | DTO field state: selected fields editable |
| `3320-UNPROTECT-FEW-ATTRS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3390-SETUP-INFOMSG-ATTRS` | Set info-message attributes | response DTO message field attributes |
| `3390-SETUP-INFOMSG-ATTRS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3400-SEND-SCREEN` | Transmit the screen | AccountUpdateController emits response DTO |
| `3400-SEND-SCREEN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9000-READ-ACCT` | Read account aggregate | AccountService -> AccountRepository + CardXrefRepository + CustomerRepository |
| `9000-READ-ACCT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9200-GETCARDXREF-BYACCT` | Get card xref by account | CardXrefRepository.findByAccountId (alt-index browse) |
| `9200-GETCARDXREF-BYACCT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9300-GETACCTDATA-BYACCT` | Get account by id | AccountRepository.findById |
| `9300-GETACCTDATA-BYACCT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9400-GETCUSTDATA-BYCUST` | Get customer by id | CustomerRepository.findById |
| `9400-GETCUSTDATA-BYCUST-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9500-STORE-FETCHED-DATA` | Store fetched data | mapper/AccountMapper -> response/session DTO |
| `9500-STORE-FETCHED-DATA-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9600-WRITE-PROCESSING` | Persist update (REWRITE) | AccountService update -> repository.save (@Version optimistic lock) |
| `9600-WRITE-PROCESSING-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9700-CHECK-CHANGE-IN-REC` | Detect concurrent change (account + owning customer aggregate) | `@Version` optimistic-lock check; the account is loaded via `AccountRepository.findByIdForVersionedUpdate` (`OPTIMISTIC_FORCE_INCREMENT`) so any confirmed write — including a customer-only edit — advances `account.version` and a stale editor is rejected. The customer also carries `@Version`; the account force-increment anchors the account+customer aggregate (see decision-log D18). |
| `9700-CHECK-CHANGE-IN-REC-EXIT` | Structured paragraph return | (no-op) structured control return |
| `ABEND-ROUTINE` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `ABEND-ROUTINE-EXIT` | Structured paragraph return | (no-op) structured control return |

### 4.12 `COACTVWC.cbl` -> web/AccountViewController + service/AccountService

*Account view/inquiry. Type: online; 34 paragraphs; txn CAVW; source: `legacy/cbl/COACTVWC.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0000-MAIN` | Program entry (main flow) | AccountViewController request entry (main flow) |
| `COMMON-RETURN` | Common return / navigation | AccountViewController navigation return (COMMAREA/XCTL -> next view) |
| `0000-MAIN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1000-SEND-MAP` | Build/send the map (output) | AccountViewController builds response DTO |
| `1000-SEND-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1100-SCREEN-INIT` | Initialize screen defaults | AccountViewController initializes response DTO defaults |
| `1100-SCREEN-INIT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1200-SETUP-SCREEN-VARS` | Populate screen fields | mapper/AccountMapper populates response DTO fields |
| `1200-SETUP-SCREEN-VARS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1300-SETUP-SCREEN-ATTRS` | Set field attributes | response DTO field attribute/edit-state flags |
| `1300-SETUP-SCREEN-ATTRS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1400-SEND-SCREEN` | Transmit the screen | AccountViewController emits response DTO |
| `1400-SEND-SCREEN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2000-PROCESS-INPUTS` | Process inputs (receive + validate) | AccountViewController input processing -> AccountService + service/rule/* |
| `2000-PROCESS-INPUTS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2100-RECEIVE-MAP` | Receive/parse the map (input) | AccountViewController consumes request DTO |
| `2100-RECEIVE-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2200-EDIT-MAP-INPUTS` | Validate all map inputs | AccountService + service/rule/* (Bean Validation orchestration) |
| `2200-EDIT-MAP-INPUTS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2210-EDIT-ACCOUNT` | Validate account id | service/rule/AccountNumberRule |
| `2210-EDIT-ACCOUNT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9000-READ-ACCT` | Read account aggregate | AccountService -> AccountRepository + CardXrefRepository + CustomerRepository |
| `9000-READ-ACCT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9200-GETCARDXREF-BYACCT` | Get card xref by account | CardXrefRepository.findByAccountId (alt-index browse) |
| `9200-GETCARDXREF-BYACCT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9300-GETACCTDATA-BYACCT` | Get account by id | AccountRepository.findById |
| `9300-GETACCTDATA-BYACCT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9400-GETCUSTDATA-BYCUST` | Get customer by id | CustomerRepository.findById |
| `9400-GETCUSTDATA-BYCUST-EXIT` | Structured paragraph return | (no-op) structured control return |
| `SEND-PLAIN-TEXT` | Send plain-text response | AccountViewController returns plain-text body |
| `SEND-PLAIN-TEXT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `SEND-LONG-TEXT` | Send long-text response | AccountViewController returns long-text body |
| `SEND-LONG-TEXT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `ABEND-ROUTINE` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |

> **Reconciliation footnote:** a verbatim extraction of `legacy/cbl/COACTVWC.cbl` yields 35 raw paragraph labels because the label `0000-MAIN-EXIT.` appears **twice** (both bodies contain only `EXIT.`) - a redundant duplicate label in the legacy source. Deduplicated, the program has **34 unique** paragraphs, which is the count used throughout this matrix.

### 4.13 `COADM01C.cbl` -> web/AdminMenuController + service/MenuService

*Admin menu routing. Type: online; 7 paragraphs; txn CA00; source: `legacy/cbl/COADM01C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | AdminMenuController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | AdminMenuController submit handler -> MenuService |
| `RETURN-TO-SIGNON-SCREEN` | Return to signon | Navigate to SignonController (signon view) |
| `SEND-MENU-SCREEN` | Build/send the screen (output) | AdminMenuController builds response DTO |
| `RECEIVE-MENU-SCREEN` | Receive the screen (input) | AdminMenuController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `BUILD-MENU-OPTIONS` | Build menu options | MenuService.buildOptions (menu options list) |

### 4.14 `COBIL00C.cbl` -> web/BillPaymentController + service/BillPaymentService

*Bill payment posting. Type: online; 16 paragraphs; txn CB00; source: `legacy/cbl/COBIL00C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | BillPaymentController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | BillPaymentController submit handler -> BillPaymentService |
| `GET-CURRENT-TIMESTAMP` | Get current timestamp | java.time (LocalDateTime.now) via BillPaymentService |
| `RETURN-TO-PREV-SCREEN` | Return to previous screen | BillPaymentController navigate to previous view |
| `SEND-BILLPAY-SCREEN` | Build/send the screen (output) | BillPaymentController builds response DTO |
| `RECEIVE-BILLPAY-SCREEN` | Receive the screen (input) | BillPaymentController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `READ-ACCTDAT-FILE` | Read Account record | AccountRepository.findById (READ) |
| `UPDATE-ACCTDAT-FILE` | Update Account record | AccountRepository.save (update - REWRITE, @Version) |
| `READ-CXACAIX-FILE` | Read CardXref record | CardXrefRepository (acct alt-index).findById (READ) |
| `STARTBR-TRANSACT-FILE` | Start browse on Transaction | TransactionRepository - open sorted cursor (STARTBR -> Pageable/Sort) |
| `READPREV-TRANSACT-FILE` | Browse previous Transaction | TransactionRepository - sorted/paged query (READPREV -> prev) |
| `ENDBR-TRANSACT-FILE` | End browse on Transaction | TransactionRepository - release cursor (ENDBR -> no-op) |
| `WRITE-TRANSACT-FILE` | Write Transaction record | TransactionRepository.save (insert - WRITE) |
| `CLEAR-CURRENT-SCREEN` | Clear current screen | Reset response DTO fields |
| `INITIALIZE-ALL-FIELDS` | Initialize all fields | Initialize response DTO fields |

### 4.15 `COCRDLIC.cbl` -> web/CardListController + service/CardService

*Card list (by account). Type: online; 39 paragraphs; txn CCLI; source: `legacy/cbl/COCRDLIC.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0000-MAIN` | Program entry (main flow) | CardListController request entry (main flow) |
| `COMMON-RETURN` | Common return / navigation | CardListController navigation return (COMMAREA/XCTL -> next view) |
| `0000-MAIN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1000-SEND-MAP` | Build/send the map (output) | CardListController builds response DTO |
| `1000-SEND-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1100-SCREEN-INIT` | Initialize screen defaults | CardListController initializes response DTO defaults |
| `1100-SCREEN-INIT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1200-SCREEN-ARRAY-INIT` | Initialize list rows | CardListController initializes list DTO rows |
| `1200-SCREEN-ARRAY-INIT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1250-SETUP-ARRAY-ATTRIBS` | Set list-row attributes | response DTO row attribute flags |
| `1250-SETUP-ARRAY-ATTRIBS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1300-SETUP-SCREEN-ATTRS` | Set field attributes | response DTO field attribute/edit-state flags |
| `1300-SETUP-SCREEN-ATTRS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1400-SETUP-MESSAGE` | Set screen message | response DTO message field |
| `1400-SETUP-MESSAGE-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1500-SEND-SCREEN` | Transmit the screen | CardListController emits response DTO |
| `1500-SEND-SCREEN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2000-RECEIVE-MAP` | Receive/parse the map (input) | CardListController consumes request DTO |
| `2000-RECEIVE-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2100-RECEIVE-SCREEN` | Receive the screen (input) | CardListController consumes request DTO |
| `2100-RECEIVE-SCREEN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2200-EDIT-INPUTS` | Validate all inputs | CardService + service/rule/* (Bean Validation orchestration) |
| `2200-EDIT-INPUTS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2210-EDIT-ACCOUNT` | Validate account id | service/rule/AccountNumberRule |
| `2210-EDIT-ACCOUNT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2220-EDIT-CARD` | Validate card number | service/rule/CardNumberRule |
| `2220-EDIT-CARD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2250-EDIT-ARRAY` | Validate selected list row | service/rule (row-selection validation) |
| `2250-EDIT-ARRAY-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9000-READ-FORWARD` | Browse forward (page) | CardRepository - paged forward query (READNEXT -> Pageable) |
| `9000-READ-FORWARD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9100-READ-BACKWARDS` | Browse backward (page) | CardRepository - paged backward query (READPREV -> Pageable) |
| `9100-READ-BACKWARDS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9500-FILTER-RECORDS` | Filter list rows | CardRepository - filtered query (account/card criteria) |
| `9500-FILTER-RECORDS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `SEND-PLAIN-TEXT` | Send plain-text response | CardListController returns plain-text body |
| `SEND-PLAIN-TEXT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `SEND-LONG-TEXT` | Send long-text response | CardListController returns long-text body |
| `SEND-LONG-TEXT-EXIT` | Structured paragraph return | (no-op) structured control return |

### 4.16 `COCRDSLC.cbl` -> web/CardViewController + service/CardService

*Card detail view. Type: online; 34 paragraphs; txn CCDL; source: `legacy/cbl/COCRDSLC.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0000-MAIN` | Program entry (main flow) | CardViewController request entry (main flow) |
| `COMMON-RETURN` | Common return / navigation | CardViewController navigation return (COMMAREA/XCTL -> next view) |
| `0000-MAIN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1000-SEND-MAP` | Build/send the map (output) | CardViewController builds response DTO |
| `1000-SEND-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1100-SCREEN-INIT` | Initialize screen defaults | CardViewController initializes response DTO defaults |
| `1100-SCREEN-INIT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1200-SETUP-SCREEN-VARS` | Populate screen fields | mapper/CardMapper populates response DTO fields |
| `1200-SETUP-SCREEN-VARS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1300-SETUP-SCREEN-ATTRS` | Set field attributes | response DTO field attribute/edit-state flags |
| `1300-SETUP-SCREEN-ATTRS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1400-SEND-SCREEN` | Transmit the screen | CardViewController emits response DTO |
| `1400-SEND-SCREEN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2000-PROCESS-INPUTS` | Process inputs (receive + validate) | CardViewController input processing -> CardService + service/rule/* |
| `2000-PROCESS-INPUTS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2100-RECEIVE-MAP` | Receive/parse the map (input) | CardViewController consumes request DTO |
| `2100-RECEIVE-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2200-EDIT-MAP-INPUTS` | Validate all map inputs | CardService + service/rule/* (Bean Validation orchestration) |
| `2200-EDIT-MAP-INPUTS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2210-EDIT-ACCOUNT` | Validate account id | service/rule/AccountNumberRule |
| `2210-EDIT-ACCOUNT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2220-EDIT-CARD` | Validate card number | service/rule/CardNumberRule |
| `2220-EDIT-CARD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9000-READ-DATA` | Read card/account data | CardService -> CardRepository / AccountRepository |
| `9000-READ-DATA-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9100-GETCARD-BYACCTCARD` | Get card by acct+card | CardRepository.findByAccountIdAndCardNumber |
| `9100-GETCARD-BYACCTCARD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9150-GETCARD-BYACCT` | Get cards by account | CardRepository.findByAccountId (alt-index browse) |
| `9150-GETCARD-BYACCT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `SEND-LONG-TEXT` | Send long-text response | CardViewController returns long-text body |
| `SEND-LONG-TEXT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `SEND-PLAIN-TEXT` | Send plain-text response | CardViewController returns plain-text body |
| `SEND-PLAIN-TEXT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `ABEND-ROUTINE` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |

### 4.17 `COCRDUPC.cbl` -> web/CardUpdateController + service/CardService

*Card update. Type: online; 45 paragraphs; txn CCUP; source: `legacy/cbl/COCRDUPC.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `0000-MAIN` | Program entry (main flow) | CardUpdateController request entry (main flow) |
| `COMMON-RETURN` | Common return / navigation | CardUpdateController navigation return (COMMAREA/XCTL -> next view) |
| `0000-MAIN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1000-PROCESS-INPUTS` | Process inputs (receive + validate) | CardUpdateController input processing -> CardService + service/rule/* |
| `1000-PROCESS-INPUTS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1100-RECEIVE-MAP` | Receive/parse the map (input) | CardUpdateController consumes request DTO |
| `1100-RECEIVE-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1200-EDIT-MAP-INPUTS` | Validate all map inputs | CardService + service/rule/* (Bean Validation orchestration) |
| `1200-EDIT-MAP-INPUTS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1210-EDIT-ACCOUNT` | Validate account id | service/rule/AccountNumberRule |
| `1210-EDIT-ACCOUNT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1220-EDIT-CARD` | Validate card number | service/rule/CardNumberRule |
| `1220-EDIT-CARD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1230-EDIT-NAME` | Validate name fields | service/rule/NameRule |
| `1230-EDIT-NAME-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1240-EDIT-CARDSTATUS` | Validate card status flag | service/rule/CardStatusRule (Y/N) |
| `1240-EDIT-CARDSTATUS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1250-EDIT-EXPIRY-MON` | Validate expiry month | service/rule/ExpiryRule (month) |
| `1250-EDIT-EXPIRY-MON-EXIT` | Structured paragraph return | (no-op) structured control return |
| `1260-EDIT-EXPIRY-YEAR` | Validate expiry year | service/rule/ExpiryRule (year) |
| `1260-EDIT-EXPIRY-YEAR-EXIT` | Structured paragraph return | (no-op) structured control return |
| `2000-DECIDE-ACTION` | Decide action (PF-key/action) | CardUpdateController action dispatch (PF-key/action routing) |
| `2000-DECIDE-ACTION-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3000-SEND-MAP` | Build/send the map (output) | CardUpdateController builds response DTO |
| `3000-SEND-MAP-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3100-SCREEN-INIT` | Initialize screen defaults | CardUpdateController initializes response DTO defaults |
| `3100-SCREEN-INIT-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3200-SETUP-SCREEN-VARS` | Populate screen fields | mapper/CardMapper populates response DTO fields |
| `3200-SETUP-SCREEN-VARS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3250-SETUP-INFOMSG` | Set info message | response DTO info/error message field |
| `3250-SETUP-INFOMSG-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3300-SETUP-SCREEN-ATTRS` | Set field attributes | response DTO field attribute/edit-state flags |
| `3300-SETUP-SCREEN-ATTRS-EXIT` | Structured paragraph return | (no-op) structured control return |
| `3400-SEND-SCREEN` | Transmit the screen | CardUpdateController emits response DTO |
| `3400-SEND-SCREEN-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9000-READ-DATA` | Read card/account data | CardService -> CardRepository / AccountRepository |
| `9000-READ-DATA-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9100-GETCARD-BYACCTCARD` | Get card by acct+card | CardRepository.findByAccountIdAndCardNumber |
| `9100-GETCARD-BYACCTCARD-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9200-WRITE-PROCESSING` | Persist update (REWRITE) | CardService update -> repository.save (@Version optimistic lock) |
| `9200-WRITE-PROCESSING-EXIT` | Structured paragraph return | (no-op) structured control return |
| `9300-CHECK-CHANGE-IN-REC` | Detect concurrent change | @Version optimistic-lock check (READ-before-REWRITE) |
| `9300-CHECK-CHANGE-IN-REC-EXIT` | Structured paragraph return | (no-op) structured control return |
| `ABEND-ROUTINE` | Abnormal-end handler | exception/GlobalExceptionHandler -> thrown exception (online) / batch job failure |
| `ABEND-ROUTINE-EXIT` | Structured paragraph return | (no-op) structured control return |

### 4.18 `COMEN01C.cbl` -> web/MainMenuController + service/MenuService

*Main menu routing. Type: online; 7 paragraphs; txn CM00; source: `legacy/cbl/COMEN01C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | MainMenuController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | MainMenuController submit handler -> MenuService |
| `RETURN-TO-SIGNON-SCREEN` | Return to signon | Navigate to SignonController (signon view) |
| `SEND-MENU-SCREEN` | Build/send the screen (output) | MainMenuController builds response DTO |
| `RECEIVE-MENU-SCREEN` | Receive the screen (input) | MainMenuController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `BUILD-MENU-OPTIONS` | Build menu options | MenuService.buildOptions (menu options list) |

### 4.19 `CORPT00C.cbl` -> web/TransactionReportController + service/ReportService

*Transaction report request (submits batch job). Type: online; 10 paragraphs; txn CR00; source: `legacy/cbl/CORPT00C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | TransactionReportController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | TransactionReportController submit handler -> ReportService |
| `SUBMIT-JOB-TO-INTRDR` | Submit report batch job | ReportService -> JobLauncher.run(TransactionReportJob) |
| `WIRTE-JOBSUB-TDQ` | Enqueue job submission (TDQ) [sic 'WIRTE'] | ReportService -> JobLauncher job parameters (submission) |
| `RETURN-TO-PREV-SCREEN` | Return to previous screen | TransactionReportController navigate to previous view |
| `SEND-TRNRPT-SCREEN` | Build/send the screen (output) | TransactionReportController builds response DTO |
| `RETURN-TO-CICS` | End request / return | TransactionReportController end request (return) |
| `RECEIVE-TRNRPT-SCREEN` | Receive the screen (input) | TransactionReportController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `INITIALIZE-ALL-FIELDS` | Initialize all fields | Initialize response DTO fields |

### 4.20 `COSGN00C.cbl` -> web/SignonController + service/SignonService

*Sign-on / authentication. Type: online; 6 paragraphs; txn CC00; source: `legacy/cbl/COSGN00C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | SignonController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Validate credentials on Enter | SignonController submit -> SignonService.authenticate |
| `SEND-SIGNON-SCREEN` | Build/send the screen (output) | SignonController builds response DTO |
| `SEND-PLAIN-TEXT` | Send plain-text response | SignonController returns plain-text body |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `READ-USER-SEC-FILE` | Read user security record for signon | SignonService -> UserSecurityRepository.findById (credential lookup) |

### 4.21 `COTRN00C.cbl` -> web/TransactionListController + service/TransactionService

*Transaction list (paged). Type: online; 16 paragraphs; txn CT00; source: `legacy/cbl/COTRN00C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | TransactionListController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | TransactionListController submit handler -> TransactionService |
| `PROCESS-PF7-KEY` | PF7 - page up | Pagination - previous page (Pageable) |
| `PROCESS-PF8-KEY` | PF8 - page down | Pagination - next page (Pageable) |
| `PROCESS-PAGE-FORWARD` | Page forward | Pagination - next page (Pageable.next) |
| `PROCESS-PAGE-BACKWARD` | Page backward | Pagination - previous page (Pageable.previousOrFirst) |
| `POPULATE-TRAN-DATA` | Populate list rows | Map page rows -> list DTO (TransactionService) |
| `INITIALIZE-TRAN-DATA` | Clear list rows | Clear list DTO rows |
| `RETURN-TO-PREV-SCREEN` | Return to previous screen | TransactionListController navigate to previous view |
| `SEND-TRNLST-SCREEN` | Build/send the screen (output) | TransactionListController builds response DTO |
| `RECEIVE-TRNLST-SCREEN` | Receive the screen (input) | TransactionListController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `STARTBR-TRANSACT-FILE` | Start browse on Transaction | TransactionRepository - open sorted cursor (STARTBR -> Pageable/Sort) |
| `READNEXT-TRANSACT-FILE` | Browse next Transaction | TransactionRepository - sorted/paged query (READNEXT -> next) |
| `READPREV-TRANSACT-FILE` | Browse previous Transaction | TransactionRepository - sorted/paged query (READPREV -> prev) |
| `ENDBR-TRANSACT-FILE` | End browse on Transaction | TransactionRepository - release cursor (ENDBR -> no-op) |

### 4.22 `COTRN01C.cbl` -> web/TransactionViewController + service/TransactionService

*Transaction view. Type: online; 9 paragraphs; txn CT01; source: `legacy/cbl/COTRN01C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | TransactionViewController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | TransactionViewController submit handler -> TransactionService |
| `RETURN-TO-PREV-SCREEN` | Return to previous screen | TransactionViewController navigate to previous view |
| `SEND-TRNVIEW-SCREEN` | Build/send the screen (output) | TransactionViewController builds response DTO |
| `RECEIVE-TRNVIEW-SCREEN` | Receive the screen (input) | TransactionViewController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `READ-TRANSACT-FILE` | Read Transaction record | TransactionRepository.findById (READ) |
| `CLEAR-CURRENT-SCREEN` | Clear current screen | Reset response DTO fields |
| `INITIALIZE-ALL-FIELDS` | Initialize all fields | Initialize response DTO fields |

### 4.23 `COTRN02C.cbl` -> web/TransactionAddController + service/TransactionService + common/util/IdGenerator

*Transaction add (id generation). Type: online; 18 paragraphs; txn CT02; source: `legacy/cbl/COTRN02C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | TransactionAddController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | TransactionAddController submit handler -> TransactionService |
| `VALIDATE-INPUT-KEY-FIELDS` | Validate key fields (account/card) | service/rule/* + Bean Validation (key fields) |
| `VALIDATE-INPUT-DATA-FIELDS` | Validate data fields (amount/date/desc) | service/rule/* + Bean Validation (data fields) |
| `ADD-TRANSACTION` | Generate next id (max+1) and add the transaction [legacy L444-L451] | TransactionService.add + common/util/IdGenerator.nextTransactionId (max+1) |
| `COPY-LAST-TRAN-DATA` | Reverse-browse last transaction to seed the id | common/util/IdGenerator max-key lookup (READPREV from HIGH-VALUES) -> TransactionRepository |
| `RETURN-TO-PREV-SCREEN` | Return to previous screen | TransactionAddController navigate to previous view |
| `SEND-TRNADD-SCREEN` | Build/send the screen (output) | TransactionAddController builds response DTO |
| `RECEIVE-TRNADD-SCREEN` | Receive the screen (input) | TransactionAddController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `READ-CXACAIX-FILE` | Read CardXref record | CardXrefRepository (acct alt-index).findById (READ) |
| `READ-CCXREF-FILE` | Read CardXref record | CardXrefRepository.findById (READ) |
| `STARTBR-TRANSACT-FILE` | Start browse on Transaction | TransactionRepository - open sorted cursor (STARTBR -> Pageable/Sort) |
| `READPREV-TRANSACT-FILE` | Browse previous Transaction | TransactionRepository - sorted/paged query (READPREV -> prev) |
| `ENDBR-TRANSACT-FILE` | End browse on Transaction | TransactionRepository - release cursor (ENDBR -> no-op) |
| `WRITE-TRANSACT-FILE` | Write Transaction record | TransactionRepository.save (insert - WRITE) |
| `CLEAR-CURRENT-SCREEN` | Clear current screen | Reset response DTO fields |
| `INITIALIZE-ALL-FIELDS` | Initialize all fields | Initialize response DTO fields |

### 4.24 `COUSR00C.cbl` -> web/UserListController + service/UserService

*User list (paged). Type: online; 16 paragraphs; txn CU00; source: `legacy/cbl/COUSR00C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | UserListController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | UserListController submit handler -> UserService |
| `PROCESS-PF7-KEY` | PF7 - page up | Pagination - previous page (Pageable) |
| `PROCESS-PF8-KEY` | PF8 - page down | Pagination - next page (Pageable) |
| `PROCESS-PAGE-FORWARD` | Page forward | Pagination - next page (Pageable.next) |
| `PROCESS-PAGE-BACKWARD` | Page backward | Pagination - previous page (Pageable.previousOrFirst) |
| `POPULATE-USER-DATA` | Populate user rows | Map page rows -> user list DTO (UserService) |
| `INITIALIZE-USER-DATA` | Clear user rows | Clear user list DTO rows |
| `RETURN-TO-PREV-SCREEN` | Return to previous screen | UserListController navigate to previous view |
| `SEND-USRLST-SCREEN` | Build/send the screen (output) | UserListController builds response DTO |
| `RECEIVE-USRLST-SCREEN` | Receive the screen (input) | UserListController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `STARTBR-USER-SEC-FILE` | Start browse on UserSecurity | UserSecurityRepository - open sorted cursor (STARTBR -> Pageable/Sort) |
| `READNEXT-USER-SEC-FILE` | Browse next UserSecurity | UserSecurityRepository - sorted/paged query (READNEXT -> next) |
| `READPREV-USER-SEC-FILE` | Browse previous UserSecurity | UserSecurityRepository - sorted/paged query (READPREV -> prev) |
| `ENDBR-USER-SEC-FILE` | End browse on UserSecurity | UserSecurityRepository - release cursor (ENDBR -> no-op) |

### 4.25 `COUSR01C.cbl` -> web/UserAddController + service/UserService

*User add. Type: online; 9 paragraphs; txn CU01; source: `legacy/cbl/COUSR01C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | UserAddController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | UserAddController submit handler -> UserService |
| `RETURN-TO-PREV-SCREEN` | Return to previous screen | UserAddController navigate to previous view |
| `SEND-USRADD-SCREEN` | Build/send the screen (output) | UserAddController builds response DTO |
| `RECEIVE-USRADD-SCREEN` | Receive the screen (input) | UserAddController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `WRITE-USER-SEC-FILE` | Write UserSecurity record | UserSecurityRepository.save (insert - WRITE) |
| `CLEAR-CURRENT-SCREEN` | Clear current screen | Reset response DTO fields |
| `INITIALIZE-ALL-FIELDS` | Initialize all fields | Initialize response DTO fields |

### 4.26 `COUSR02C.cbl` -> web/UserUpdateController + service/UserService

*User update. Type: online; 11 paragraphs; txn CU02; source: `legacy/cbl/COUSR02C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | UserUpdateController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | UserUpdateController submit handler -> UserService |
| `UPDATE-USER-INFO` | Update user fields | UserService.update |
| `RETURN-TO-PREV-SCREEN` | Return to previous screen | UserUpdateController navigate to previous view |
| `SEND-USRUPD-SCREEN` | Build/send the screen (output) | UserUpdateController builds response DTO |
| `RECEIVE-USRUPD-SCREEN` | Receive the screen (input) | UserUpdateController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `READ-USER-SEC-FILE` | Read UserSecurity record | UserSecurityRepository.findById (READ) |
| `UPDATE-USER-SEC-FILE` | Update UserSecurity record | UserSecurityRepository.save (update - REWRITE, @Version) |
| `CLEAR-CURRENT-SCREEN` | Clear current screen | Reset response DTO fields |
| `INITIALIZE-ALL-FIELDS` | Initialize all fields | Initialize response DTO fields |

### 4.27 `COUSR03C.cbl` -> web/UserDeleteController + service/UserService

*User delete. Type: online; 11 paragraphs; txn CU03; source: `legacy/cbl/COUSR03C.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `MAIN-PARA` | Program entry / AID dispatch | UserDeleteController request entry (AID/action dispatch) |
| `PROCESS-ENTER-KEY` | Handle Enter / submit | UserDeleteController submit handler -> UserService |
| `DELETE-USER-INFO` | Delete user | UserService.delete |
| `RETURN-TO-PREV-SCREEN` | Return to previous screen | UserDeleteController navigate to previous view |
| `SEND-USRDEL-SCREEN` | Build/send the screen (output) | UserDeleteController builds response DTO |
| `RECEIVE-USRDEL-SCREEN` | Receive the screen (input) | UserDeleteController consumes request DTO |
| `POPULATE-HEADER-INFO` | Populate screen header | Header fields (title/date/time/program) in response DTO |
| `READ-USER-SEC-FILE` | Read UserSecurity record | UserSecurityRepository.findById (READ) |
| `DELETE-USER-SEC-FILE` | Delete UserSecurity record | UserSecurityRepository.delete (DELETE) |
| `CLEAR-CURRENT-SCREEN` | Clear current screen | Reset response DTO fields |
| `INITIALIZE-ALL-FIELDS` | Initialize all fields | Initialize response DTO fields |

### 4.28 `CSUTLDTC.cbl` -> service/DateValidationService + common/util/DateUtils

*Date validation utility (CEEDAYS). Type: util; 2 paragraphs; txn (called by online/batch); source: `legacy/cbl/CSUTLDTC.cbl`.*

| COBOL Paragraph | Purpose | Target Java (class#method or component) |
|-----------------|---------|-----------------------------------------|
| `A000-MAIN` | Validate a date (CEEDAYS) [legacy L116] | DateValidationService.validate (CEEDAYS -> java.time) |
| `A000-MAIN-EXIT` | Structured paragraph return | (no-op) structured control return |

## 5. Reverse Mapping (target -> source)

This section provides the reverse direction required for bidirectional traceability: each major Java artifact traced back to the COBOL program(s), paragraph(s), and/or copybook(s) it derives from.

| Target Java artifact | Derived from (COBOL source) |
|----------------------|-----------------------------|
| domain/Customer, domain/Account, domain/Card, domain/CardXref, domain/Transaction, domain/DailyTransaction, domain/UserSecurity, domain/TransactionType, domain/TransactionCategory, domain/DisclosureGroup, domain/TransactionCategoryBalance | Copybooks CVCUS01Y, CVACT01Y, CVACT02Y, CVACT03Y, CVTRA05Y, CVTRA06Y, CSUSR01Y, CVTRA03Y, CVTRA04Y, CVTRA02Y, CVTRA01Y (record layouts) |
| domain/type/Money | Derived; centralizes COMP-3 monetary arithmetic (see CBACT04C 1300-COMPUTE-INTEREST) |
| repository/*Repository (one per entity) | VSAM file access (OPEN/CLOSE/READ/STARTBR/READNEXT/READPREV/WRITE/REWRITE) across CB*.cbl + CO*.cbl |
| web/SignonController + service/SignonService | COSGN00C (CC00) |
| web/MainMenuController + service/MenuService | COMEN01C (CM00) |
| web/AdminMenuController + service/MenuService | COADM01C (CA00) |
| web/AccountViewController + service/AccountService | COACTVWC (CAVW) |
| web/AccountUpdateController + service/AccountService | COACTUPC (CAUP) |
| web/CardListController + service/CardService | COCRDLIC (CCLI) |
| web/CardViewController + service/CardService | COCRDSLC (CCDL) |
| web/CardUpdateController + service/CardService | COCRDUPC (CCUP) |
| web/TransactionListController + service/TransactionService | COTRN00C (CT00) |
| web/TransactionViewController + service/TransactionService | COTRN01C (CT01) |
| web/TransactionAddController + service/TransactionService | COTRN02C (CT02) |
| web/TransactionReportController + service/ReportService | CORPT00C (CR00) |
| web/BillPaymentController + service/BillPaymentService | COBIL00C (CB00) |
| web/UserListController + service/UserService | COUSR00C (CU00) |
| web/UserAddController + service/UserService | COUSR01C (CU01) |
| web/UserUpdateController + service/UserService | COUSR02C (CU02) |
| web/UserDeleteController + service/UserService | COUSR03C (CU03) |
| service/DateValidationService + common/util/DateUtils | CSUTLDTC (A000-MAIN; CEEDAYS -> java.time); copybooks CSDAT01Y, CSUTLDPY, CSUTLDWY |
| batch/DailyTransactionValidateJob | CBTRN01C |
| batch/DailyTransactionPostingJob + service/PostingService | CBTRN02C (POSTTRAN); reject codes 100/101/102/103 at 1500-A/1500-B |
| batch/InterestCalculationJob | CBACT04C (INTCALC); 1300-COMPUTE-INTEREST |
| batch/StatementGenerationJob + service/StatementFileService | CBSTM03A.CBL + CBSTM03B.CBL (CREASTMT) |
| batch/TransactionReportJob | CBTRN03C (TRANREPT.prc) |
| batch/AccountMasterPrintJob | CBACT01C |
| batch/CardMasterPrintJob | CBACT02C |
| batch/XrefPrintJob | CBACT03C |
| batch/CustomerMasterPrintJob | CBCUS01C |
| batch/TransactionCombineJob | COMBTRAN.jcl (inline `PGM=SORT` cards) |
| batch/TransactionBackupJob | TRANBKP.jcl (IDCAMS REPRO) + REPROC.prc + REPROCT.ctl (IDCAMS REPRO control) |
| batch/reader, batch/processor, batch/writer | Fixed-width DALYTRAN/DALYREJS/statement/report layouts |
| common/util/IdGenerator | COTRN02C ADD-TRANSACTION / COPY-LAST-TRAN-DATA (max+1) [L444-L451] |
| common/util/FixedWidthCodec | DALYTRAN / DALYREJS / statement / report fixed-width record layouts |
| dto/*Request, dto/*Response, mapper/*Mapper | BMS symbolic copybooks legacy/cpy-bms/*.CPY (one pair per screen) |
| exception/FileStatusException, exception/RejectCode, exception/CicsRespMapper, exception/GlobalExceptionHandler | COBOL FILE STATUS / CICS RESP / reject codes; *-ABEND-PROGRAM, ABEND-ROUTINE, *-DISPLAY-IO-STATUS |
| security/UserDetailsService + config/SecurityConfig | COCOM01Y 88-level roles (A=ADMIN/U=USER) over user_security |

## 6. Runtime-Service & Infrastructure Replacement

The COBOL/mainframe runtime services have no paragraph-level representation; they are replaced wholesale by Spring/JVM equivalents (AAP Section 0.6). This replacement is recorded here for completeness.

| COBOL / mainframe construct | Spring / JVM replacement | Preservation note |
|-----------------------------|--------------------------|-------------------|
| CICS TS (pseudo-conversational online monitor) | Spring MVC @RestController + embedded Tomcat | Screen-entry / navigation behavior preserved via flow state |
| CICS COMMAREA + XCTL + RETURN TRANSID | Server-side flow/session context + controller navigation | First-time vs re-entry (CDEMO-PGM-CONTEXT) modelled explicitly |
| BMS maps / 3270 terminal | Request/response DTOs (field names, lengths, PIC types, edit rules, PF-key actions) | No terminal emulator; field-level contract preserved |
| Language Environment (LE) | JVM + java.time | CEEDAYS -> java.time; CEE3ABD -> Java exception |
| VSAM KSDS + alternate indexes | PostgreSQL 16 tables + B-tree indexes (Spring Data JPA) | Unique key -> PK; AIX -> index; app-enforced relationships -> real FKs |
| JCL jobs / scheduler | Spring Batch Job/Step + CI/CD scheduling | Step ordering, dependencies, return codes 0/4/8 |
| IDCAMS / SORT / MERGE utilities | JPA queries / Java Comparator / ORDER BY | Identical key ordering |
| FILE STATUS / CICS RESP codes | Typed exception hierarchy (FileStatusException, CicsRespMapper, GlobalExceptionHandler) | HTTP status (online) / batch return codes (batch) |
| Static/dynamic CALL | Spring bean method invocation (constructor injection) | Data-passing semantics preserved |
| COMP-3 packed decimal | java.math.BigDecimal scale 2 + RoundingMode.HALF_UP | Bit-exact rounding parity (domain/type/Money) |

## 7. Non-Paragraph Orchestration: JCL / PROC / CTL / CSD

The 29 JCL jobs, 2 PROCs, 1 IDCAMS `REPRO` control member (`REPROCT.ctl`), and the CICS resource-definition file have no PROCEDURE DIVISION paragraphs; they map to Spring Batch jobs/steps, Flyway migrations/seed data, or CI/CD scheduling. Batch-program JCL triggers map to the Spring Batch jobs in Section 1; utility IDCAMS/IEBGENER/IEFBR14 jobs map to Flyway schema/seed and DB-backup steps. Section 7.1 is the artifact-level map; **Section 7.1.1 adds step-level detail** (step order, DD/GDG dependencies, `COND`/`MAXCC` gating, SORT/INCLUDE/OUTREC cards, and return codes) for the multi-step and utility jobs, and **Section 7.1.2 classifies the reference-only and anomalous members** that are not one-to-one executable migrations.

### 7.1 JCL jobs (`legacy/jcl/**`, 29)

| JCL job | Kind | Target |
|---------|------|--------|
| `POSTTRAN.jcl` | JCL (batch) | batch/DailyTransactionPostingJob (CBTRN02C) |
| `INTCALC.jcl` | JCL (batch) | batch/InterestCalculationJob (CBACT04C) |
| `CREASTMT.JCL` | JCL (batch) | batch/StatementGenerationJob (CBSTM03A/B) |
| `TRANREPT.jcl` | JCL (batch) | batch/TransactionReportJob (CBTRN03C) |
| `COMBTRAN.jcl` | JCL (SORT) | batch/TransactionCombineJob (Java Comparator / ORDER BY) |
| `TRANBKP.jcl` | JCL (IDCAMS REPRO) | batch/TransactionBackupJob (scheduled DB backup step) |
| `READACCT.jcl` | JCL (batch) | batch/AccountMasterPrintJob (CBACT01C) |
| `READCARD.jcl` | JCL (batch) | batch/CardMasterPrintJob (CBACT02C) |
| `READXREF.jcl` | JCL (batch) | batch/XrefPrintJob (CBACT03C) |
| `READCUST.jcl` | JCL (batch) | batch/CustomerMasterPrintJob (CBCUS01C) |
| `PRTCATBL.jcl` | JCL (batch) | Category-balance print step (tran_cat_balance reporting) |
| `CBADMCDJ.jcl` | JCL (batch admin) | **Reference/historical-only** — stale alternate-CSD catalog loader; **not** a CI/CD job (see Section 7.1.2) |
| `ACCTFILE.jcl` | JCL (IDCAMS define/load) | Flyway V1 schema (account) + db/seed/account.csv |
| `CARDFILE.jcl` | JCL (IDCAMS define/load) | Flyway V1 schema (card) + db/seed/card.csv |
| `CUSTFILE.jcl` | JCL (IDCAMS define/load) | Flyway V1 schema (customer) + db/seed/customer.csv |
| `XREFFILE.jcl` | JCL (IDCAMS define/load) | Flyway V1 schema (card_xref) + db/seed/card_xref.csv |
| `TRANFILE.jcl` | JCL (IDCAMS define/load) | Flyway V1 schema (transaction) + db/seed/transaction.csv |
| `TRANIDX.jcl` | JCL (IDCAMS AIX/index) | Flyway B-tree index on transaction.proc_ts (=TRANSACT.VSAM.AIX, `AXRKP=304`) |
| `DISCGRP.jcl` | JCL (IDCAMS define/load) | Flyway V2 reference-data (disclosure_group) |
| `TRANCATG.jcl` | JCL (IDCAMS define/load) | Flyway V2 reference-data (transaction_category) |
| `TRANTYPE.jcl` | JCL (IDCAMS define/load) | Flyway V2 reference-data (transaction_type) |
| `TCATBALF.jcl` | JCL (IDCAMS define/load) | Flyway schema (tran_cat_balance) + seed |
| `DUSRSECJ.jcl` | JCL (IDCAMS define/load) | Flyway schema (user_security) + seed users |
| `DEFCUST.jcl` | JCL (IDCAMS define) | Flyway schema (customer) definition |
| `REPTFILE.jcl` | JCL (IEFBR14/define) | Spring Batch FlatFileItemWriter (report output) |
| `DALYREJS.jcl` | JCL (IEFBR14/define) | Spring Batch FlatFileItemWriter (reject output) |
| `OPENFIL.jcl` | JCL (utility) | Batch stream open (no-op) / backup step ordering |
| `CLOSEFIL.jcl` | JCL (utility) | Batch stream close (no-op) / backup step ordering |
| `DEFGDGB.jcl` | JCL (define GDG base) | DB backup retention policy (scheduled backups) |

#### 7.1.1 Step-level detail for multi-step and utility jobs

The artifact-level rows in Section 7.1 map each job to its Spring target. The tables below add the step-level contract that the migration must preserve: step order, the executed program/utility, the DD inputs/outputs (including GDG generations), `COND`/`MAXCC`/`LASTCC` gating, embedded SORT/`REPRO` control cards, and the resulting return-code semantics. Only the multi-step jobs, the backup/GDG chain, and one representative IDCAMS define/load job are expanded; the remaining single-step batch-trigger jobs in Section 7.1 are already one-to-one (one `EXEC PGM=` mapping to one Spring Batch `Job`).

**`POSTTRAN.jcl` (single step, but a governing external-file contract).** Source: `legacy/jcl/POSTTRAN.jcl` L23-L42.

| Step | Program | DD → dataset (DISP) | Contract preserved |
|------|---------|---------------------|--------------------|
| `STEP15` | `CBTRN02C` | `TRANFILE`→TRANSACT.VSAM.KSDS (SHR); `DALYTRAN`→DALYTRAN.PS (SHR); `XREFFILE`→CARDXREF.VSAM.KSDS (SHR); `ACCTFILE`→ACCTDATA.VSAM.KSDS (SHR); `TCATBALF`→TCATBALF.VSAM.KSDS (SHR); `DALYREJS`→DALYREJS(+1) GDG **`DCB=(RECFM=F,LRECL=430,BLKSIZE=0)`** (NEW,CATLG,DELETE) | `DALYREJS` output is **fixed RECFM=F, LRECL=430** (350-byte transaction image + 80-byte validation trailer — see D16 / CF1); posting reads inputs SHR and appends the reject GDG generation. Reject writer preserves the 430-byte layout; RC=4 when any record is rejected. |

**`COMBTRAN.jcl` (two steps: inline SORT → IDCAMS REPRO reload).** Source: `legacy/jcl/COMBTRAN.jcl` L22-L48.

| Step | Program | DD / control cards | Target semantics |
|------|---------|--------------------|------------------|
| `STEP05R` | `SORT` | `SORTIN`=TRANSACT.BKUP(0) **concatenated with** SYSTRAN(0) (both SHR); `SYMNAMES`: `TRAN-ID,1,16,CH`; `SYSIN`: `SORT FIELDS=(TRAN-ID,A)`; `SORTOUT`=TRANSACT.COMBINED(+1) GDG (NEW,CATLG,DELETE, `DCB=(*.SORTIN)`) | Merge current + system-generated transactions and sort ascending by `TRAN-ID` (offset 1, len 16) → Java `Comparator.comparing(tranId)` / `ORDER BY tran_id ASC` in `TransactionCombineJob`. |
| `STEP10` | `IDCAMS` | `SYSIN`: `REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)` where `TRANSACT`=COMBINED(+1), `TRANVSAM`=TRANSACT.VSAM.KSDS | Load the sorted combined generation into the transaction master (bulk upsert). Runs unconditionally (no `COND`). |

**Backup / redefine chain: `CLOSEFIL.jcl` → `TRANBKP.jcl` → `OPENFIL.jcl`, with GDG bases from `DEFGDGB.jcl`.**

`TRANBKP.jcl` (three steps). Source: `legacy/jcl/TRANBKP.jcl` L23-L67; PROC `legacy/proc/REPROC.prc` L21-L28; control `legacy/ctl/REPROCT.ctl` L15.

| Step | Program / PROC | DD / control cards | `COND`/`MAXCC` gating | Target semantics |
|------|----------------|--------------------|-----------------------|------------------|
| `STEP05R` | `PROC=REPROC` (→ `PGM=IDCAMS`) | `PRC001.FILEIN`=TRANSACT.VSAM.KSDS (SHR); `PRC001.FILEOUT`=TRANSACT.BKUP(+1) GDG `DCB=(LRECL=350,RECFM=FB)`; `SYSIN`=`&CNTLLIB(REPROCT)` → `REPRO INFILE(FILEIN) OUTFILE(FILEOUT)` | none | Copy/unload master → new GDG backup generation (IDCAMS `REPRO`, **not** SORT). |
| `STEP05` | `IDCAMS` | `DELETE …TRANSACT.VSAM.KSDS CLUSTER`; `IF MAXCC LE 08 THEN SET MAXCC = 0`; `DELETE …TRANSACT.VSAM.AIX ALTERNATEINDEX`; `IF MAXCC LE 08 THEN SET MAXCC = 0` | `MAXCC` reset to 0 after each delete so a not-found (RC≤8) does not fail the job | Drop the cluster and its alternate index tolerantly (idempotent teardown). |
| `STEP10` | `IDCAMS` | `DEFINE CLUSTER …KEYS(16 0) RECORDSIZE(350 350) INDEXED` + explicit `DATA`/`INDEX` names | **`COND=(4,LT)`** — skip this step if `4 < prior RC` (i.e. run only when RC≤4) | Recreate the empty master (16-byte key at offset 0, 350-byte records) only if the teardown succeeded. |

`CLOSEFIL.jcl` — `CLCIFIL EXEC PGM=SDSF` issues `CEMT SET FIL(...) CLO` for `TRANSACT`, `CCXREF`, `ACCTDAT`, `CXACAIX`, `USRSEC` (source L4-L12) → quiesce online files before backup. `OPENFIL.jcl` — reverse (re-enable) after backup (job-name anomaly noted in 7.1.2). In the Java target these CICS file open/close operations have **no runtime equivalent** (there is no long-running CICS file-owning region); they map to backup-step **ordering guarantees** in the scheduled DB-backup workflow.

`DEFGDGB.jcl` (`STEP05 EXEC PGM=IDCAMS`, source L21-L59) defines six generation-data groups — `TRANSACT.BKUP`, `TRANSACT.DALY`, `TRANREPT`, `TCATBALF.BKUP`, `SYSTRAN`, `TRANSACT.COMBINED` — each `LIMIT(5) SCRATCH`, each guarded by `IF LASTCC=12 THEN SET MAXCC=0` (tolerate already-exists). Target: a **backup retention policy of 5 generations** per stream in the scheduled DB-backup configuration (not a runtime code path).

**Representative IDCAMS define/load job (`ACCTFILE.jcl` pattern, shared by `CARDFILE`/`CUSTFILE`/`XREFFILE`/`TRANFILE`/`DISCGRP`/`TRANCATG`/`TRANTYPE`/`TCATBALF`/`DUSRSECJ`).** Three ordered steps: (1) `IDCAMS DELETE … CLUSTER` with `IF MAXCC LE 08 THEN SET MAXCC = 0` (tolerant drop); (2) `IDCAMS DEFINE CLUSTER (… KEYS(…) RECORDSIZE(…) INDEXED)` (must succeed); (3) `IDCAMS REPRO INFILE(seq) OUTFILE(ksds)` loading the EBCDIC/ASCII seed into the KSDS (must succeed). Target: step (1)+(2) → Flyway `V1__schema.sql` (plus the planned `V2__reference_data.sql` for the reference tables — currently seeded from `db/seed/*.csv`) `CREATE TABLE` + PK; step (3) → `db/seed/*.csv` load. `TRANIDX.jcl` adds an `IDCAMS DEFINE ALTERNATEINDEX` + `BLDINDEX` over TRANSACT keyed at `AXRKP=304` → Flyway B-tree index on `transaction.proc_ts` (=TRANSACT.VSAM.AIX; see CF2). Return-code semantics: the DELETE tolerates not-found (RC reset to 0); DEFINE and REPRO must return 0, else the job fails (mapped to a failed Flyway migration / failed seed load).

#### 7.1.2 Reference-only and anomalous members (classified, not edited)

The members below are **not** clean one-to-one executable migrations. Per the Explainability rule and the "no legacy edits" constraint, each is classified here and the **authoritative behavior** the Java target implements is stated explicitly; the immutable legacy bytes are left unchanged (see also `docs/decision-log.md` D14 and CF7/CF8).

| Member | Anomaly (source evidence) | Classification | Authoritative behavior chosen for the migration |
|--------|---------------------------|----------------|--------------------------------------------------|
| `CREASTMT.JCL` | The **only uppercase** `.JCL`; `STMTFILE` DD continuation is garbled at ~L90 — `SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS` (a copy/paste corruption spliced into the SPACE parameter) | Migrate from the **clean** portions + `CBSTM03A`/`CBSTM03B` logic; treat the garbled fragment as corruption, not a contract | `StatementGenerationJob` writes `STMTFILE` = plain-text statement `LRECL=80, RECFM=FB` and `HTMLFILE` = `LRECL=100, RECFM=FB` (the intact DDs); the corrupted token is **not** reproduced. |
| `TRANREPT.jcl` | **Duplicate step name** `//STEP05R` used twice (L23 `EXEC PROC=REPROC`, then L37 `EXEC PGM=SORT`); a third step `STEP10R EXEC PGM=CBTRN03C` follows | Source label collision; on z/OS the second identically-named step is still executed in sequence | Treat as **three ordered steps** — (a) unload master via `REPROC`, (b) filter-by-parm-date + sort by `TRAN-CARD-NUM` (offset 263) then `TRAN-PROC-DT` (offset 305), (c) `CBTRN03C` report — realized as `TransactionReportJob`; the duplicate label is irrelevant in Java (steps are named uniquely). |
| `OPENFIL.jcl` | Job name is `//OEPNFIL` (transposed typo for `OPENFIL`); functional body re-opens the CICS files closed by `CLOSEFIL` | Cosmetic job-name defect; no dependency on the job name | Functional intent = re-enable file access after backup → backup-step **ordering** in the scheduled workflow (see 7.1.1); the misspelled job name has no Java analog. |
| `DEFCUST.jcl` | **Duplicate step name** `//STEP05` (L22 `DELETE`, L32 `DEFINE`) **and obsolete HLQ** — `AWS.CCDA.CUSTDATA.CLUSTER` / `AWS.CUSTDATA.CLUSTER` instead of the canonical `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` | Stale/alternate customer definer with non-canonical dataset names | **Not authoritative** for the customer table; the canonical customer define+load is `CUSTFILE.jcl` (correct HLQ). `DEFCUST.jcl` is **reference-only**; the Java `customer` table/seed derives from `CUSTFILE.jcl` + `CVCUS01Y`. |
| `TRANREPT.prc` | Declares `//REPROC PROC` — **same PROC name** as `REPROC.prc` — and is invoked by `TRANREPT.jcl` | Two same-named PROCs disambiguated only by JCLLIB/library search order at runtime | Both same-named PROCs fold into **one** shared unload/`REPRO` step template; the report-specific wiring lives in `TransactionReportJob`. No behavioral divergence in the Java target. |
| `CBADMCDJ.jcl` | Loads a **stale alternate CSD** catalog (superseded by `CARDDEMO.CSD`) | **Historical/reference-only** — see CF7 | **Not** a CI/CD job and **not** migrated to a Spring Batch job; retained under `legacy/jcl/` for reference only. |

### 7.2 PROCs (`legacy/proc/**`, 2), CTL (`legacy/ctl/**`, 1), CSD (`legacy/csd/**`, 1)

| Member | Kind | Target |
|--------|------|--------|
| `REPROC.prc` | PROC (IDCAMS REPRO) | Shared backup/copy step (used by TransactionBackupJob) |
| `TRANREPT.prc` | PROC (report) | TransactionReportJob step template (CBTRN03C) |
| `REPROCT.ctl` | CTL (**IDCAMS `REPRO`** control) | Copy/unload/backup control (`REPRO INFILE(...) OUTFILE(...)`) — shared copy/backup step, **not** a SORT specification; inline `PGM=SORT` card sequences (e.g. `COMBTRAN`) are traced separately as sort steps |
| `CARDDEMO.CSD` | CSD (CICS resource defs) | REFERENCE registry (18 TRANSACTION, 18 PROGRAM, 17 MAPSET, 8 FILE); **17 production surfaces + 1 source-only** (see Section 7.2.1); informs controller/txn-id mapping; not a migration target |

#### 7.2.1 CSD source-only and non-program resource definitions

`legacy/csd/CARDDEMO.CSD` defines 18 `TRANSACTION`, 18 `PROGRAM`, 17 `MAPSET`, and 8 `FILE` resources. Seventeen of the transaction/program pairs correspond to the 17 online surfaces migrated to controllers in Section 4 (COSGN00C … COUSR03C). The remaining definitions have **no executable COBOL behind them** in `legacy/cbl/**` and are therefore classified as **source-only / reference-only** registry entries — they inform naming and cataloging but are not migration targets (CF6).

| CSD definition (source line) | Backing artifact | Classification | Rationale |
|------------------------------|------------------|----------------|-----------|
| `TRANSACTION(CDV1)` → `PROGRAM(COCRDSEC)` (L388-L390; program declared L211) | **No `COCRDSEC.cbl`** exists in `legacy/cbl/**` | **Source-only** (18th transaction/program pair) | The transaction and program are catalogued but the program source was never shipped; there is nothing to migrate. Recorded so the "18 TRANSACTION / 18 PROGRAM" count reconciles to **17 production surfaces + 1 source-only**. |
| `LIBRARY(CARDDLIB)` (L489) | CICS program-library (LIBRARY) resource | **Reference-only** | A DFHRPL-style load-library definition; it is CICS runtime plumbing with no COBOL logic and no Java analog (the JVM classpath replaces it). |
| `LIBRARY(COM2DOLL)` (L494) | CICS program-library (LIBRARY) resource | **Reference-only** | Same as above — a second load-library entry; nothing behavioral to migrate. |
| `TDQUEUE(JOBS)` (L499) | CICS transient-data queue (TDQUEUE) | **Reference-only** | A transient-data-queue definition used for internal-reader job submission; no CardDemo business logic depends on it, so it is not reconstructed. |

**Net reconciliation:** 17 production transaction/program pairs → 17 controllers (Section 4); 1 source-only pair (`CDV1`/`COCRDSEC`); the 3 non-program resource definitions above are CICS infrastructure with no migration target. The 17 `MAPSET` and 8 `FILE` definitions are consumed as the field-contract / dataset reference for Sections 3 and 8 respectively.

## 8. Data-Artifact Traceability (seed & reference datasets)

The paragraph-, screen-, and orchestration-level maps above cover executable constructs. This section closes the remaining artifact class: the **physical data files** that seed and back the datastore. Every ASCII seed file, every EBCDIC physical-sequential dataset, and the VSAM catalog listing is traced bidirectionally to its Java/PostgreSQL target, with the width, record count, and content hash recorded so the migration is reproducible and auditable (CF4/CF14). All widths below are the **fixed record width** (COBOL copybook `RECLN`); the ASCII files are **fixed-width, headerless** — not delimited/CSV (CF4). SHA-256 values are truncated to the first 8 hex characters for reference; the `.gitattributes` entries created in this checkpoint keep these files byte-exact under Git (CF13).

### 8.1 ASCII seed files (`legacy/data/ASCII/**`, 9) → `db/seed/*.csv` + Flyway loads

Each line is one fixed-width record terminated by a newline; byte size therefore equals `rows × (width + 1)`. Parsing uses fixed column positions with space padding for text and zero padding for numerics; there is **no header row and no delimiter**.

| ASCII file | Width (RECLN) | Rows | Bytes | SHA-256 | Source copybook | Target |
|------------|---------------|------|-------|---------|-----------------|--------|
| `acctdata.txt` | 300 | 50 | 15050 | `c2a97b6a` | `CVACT01Y` | `account` table → `db/seed/account.csv` |
| `carddata.txt` | 150 | 50 | 7550 | `da217240` | `CVACT02Y` | `card` table → `db/seed/card.csv` |
| `cardxref.txt` | 36 (visible) | 50 | 1850 | `efec3825` | `CVACT03Y` | `card_xref` table → `db/seed/card_xref.csv` (EBCDIC image carries a 14-byte trailing filler to 50; see 8.2) |
| `custdata.txt` | 500 | 50 | 25050 | `d8cfa5b7` | `CVCUS01Y` | `customer` table → `db/seed/customer.csv` |
| `dailytran.txt` | 350 | 300 | 105300 | `1605206d` | `CVTRA06Y` | `daily_transaction` staging → posting input fixture |
| `discgrp.txt` | 50 | 51 | 2601 | `dfdd3832` | `CVTRA02Y` | `disclosure_group` → Flyway `V2__reference_data.sql` (planned; currently `db/seed/disclosure_group.csv`) |
| `tcatbal.txt` | 50 | 50 | 2550 | `2c45817e` | `CVTRA01Y` | `tran_cat_balance` → seed |
| `trancatg.txt` | 60 | 18 | 1098 | `80040907` | `CVTRA04Y` | `transaction_category` → Flyway `V2__reference_data.sql` (planned; currently `db/seed/transaction_category.csv`) |
| `trantype.txt` | 60 | 7 | 427 | `3e0ae004` | `CVTRA03Y` | `transaction_type` → Flyway `V2__reference_data.sql` (planned; currently `db/seed/transaction_type.csv`) |

There is **no `usrsec.txt`** in the ASCII set (CF4); user-security seed rows derive from the EBCDIC `USRSEC.PS` dataset (8.2) via `CSUSR01Y`.

### 8.2 EBCDIC physical-sequential datasets (`legacy/data/EBCDIC/**`, 12) → layout & seed reference

These are the on-mainframe images (fixed width, no newline; byte size = `rows × width`). They are the **encoding reference** for the fixed-width record layouts and, for `USRSEC`, the authoritative seed source. The Java/PostgreSQL target stores native types, so these preserve the documented layout rather than the EBCDIC/`COMP-3` on-disk encoding (see M2 in the specification).

| EBCDIC dataset | Bytes | Width×Rows | SHA-256 | Role / target |
|----------------|-------|------------|---------|---------------|
| `AWS.M2.CARDDEMO.ACCTDATA.PS` | 15000 | 300×50 | `23167cdf` | **Authoritative** account image → `account` |
| `AWS.M2.CARDDEMO.ACCDATA.PS` | 15000 | 300×50 | `23167cdf` | **Byte-exact alias** of `ACCTDATA.PS` (identical hash) — reference-only duplicate; only `ACCTDATA` is authoritative (CF14) |
| `AWS.M2.CARDDEMO.CARDDATA.PS` | 7500 | 150×50 | `b5d968b6` | Card image → `card` |
| `AWS.M2.CARDDEMO.CARDXREF.PS` | 2500 | 50×50 | `b07ab2e5` | Cross-reference image → `card_xref` (width 50 = 36 visible + 14-byte filler; ASCII trims the filler) |
| `AWS.M2.CARDDEMO.CUSTDATA.PS` | 25000 | 500×50 | `0435915c` | Customer image → `customer` |
| `AWS.M2.CARDDEMO.DALYTRAN.PS` | 105000 | 350×300 | `479b1f99` | Daily-transaction image → posting input |
| `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT` | 350 | 350×1 | `aef36e51` | Single-record initialized daily-transaction seed (the 12th dataset) → posting fixture bootstrap |
| `AWS.M2.CARDDEMO.DISCGRP.PS` | 2550 | 50×51 | `722df789` | Disclosure-group image → `disclosure_group` |
| `AWS.M2.CARDDEMO.TCATBALF.PS` | 2500 | 50×50 | `725dbe47` | Category-balance image → `tran_cat_balance` |
| `AWS.M2.CARDDEMO.TRANCATG.PS` | 1080 | 60×18 | `3c171c53` | Transaction-category image → `transaction_category` |
| `AWS.M2.CARDDEMO.TRANTYPE.PS` | 420 | 60×7 | `97735eb6` | Transaction-type image → `transaction_type` |
| `AWS.M2.CARDDEMO.USRSEC.PS` | 800 | 80×10 | `8608d4b8` | **Authoritative** user-security image (10 users) → `user_security` seed (`CSUSR01Y`); no ASCII counterpart |

### 8.3 VSAM catalog listing (`legacy/catlg/**`, 1) → schema/index reference

| File | Bytes | SHA-256 | Role |
|------|-------|---------|------|
| `LISTCAT.txt` | 195621 | `d2079119` | IDCAMS `LISTCAT` output — authoritative source for cluster/AIX attributes: `KEYLEN`, `RKP`, and the alternate-index `AXRKP` values (e.g. `AXRKP=304` fixing TRANSACT.VSAM.AIX → `proc_ts`, CF2). Reference-only; drives the Flyway PK/index design, not seeded. |

### 8.4 Seed-vs-live and alias reconciliation (documented deviations)

- **`ACCDATA.PS` = `ACCTDATA.PS`** — identical byte content and hash (`23167cdf`). Only `ACCTDATA.VSAM.KSDS`/`ACCTDATA.PS` is treated as authoritative; `ACCDATA.PS` is a duplicate alias retained for reference and **not** loaded a second time (CF14).
- **Snapshot semantics** — the ASCII seed files and the EBCDIC images are **point-in-time snapshots** and can differ in row counts (e.g. the daily-transaction ASCII fixture is 300 rows; live/GDG-driven runs vary). The migration seeds from the documented snapshot and does not assume the two encodings are row-count-identical for the transactional (non-reference) datasets (CF14).
- **`cardxref` width** — ASCII visible width 36 vs EBCDIC width 50: the 14-byte difference is trailing filler present in the fixed 50-byte VSAM record and trimmed in the ASCII extract; both map to the same `card_xref` columns (`CVACT03Y`).
- **Reference tables** — `disclosure_group` (51 rows), `transaction_category` (18), `transaction_type` (7), and `tran_cat_balance` (50) load identically from either encoding into the planned Flyway `V2__reference_data.sql` (currently the `db/seed/*.csv` seed); row counts match across ASCII and EBCDIC.

## Appendix A - Authoritative Paragraph Inventory (527 unique)

This inventory is the checklist for Section 4. It was regenerated and verified against `legacy/cbl/**` with: `awk '/PROCEDURE DIVISION/{p=1} p' <file> | grep -E '^       [A-Z0-9][A-Z0-9-]+\.[[:space:]]*$' | grep -vE 'DIVISION|SECTION|FILE-CONTROL'` (deduplicated per file; see the COACTVWC footnote in Section 4.12 for the single duplicate-label reconciliation).

```
CBACT01C.cbl [6]: 1000-ACCTFILE-GET-NEXT, 1100-DISPLAY-ACCT-RECORD, 0000-ACCTFILE-OPEN, 9000-ACCTFILE-CLOSE, 9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS
CBACT02C.cbl [5]: 1000-CARDFILE-GET-NEXT, 0000-CARDFILE-OPEN, 9000-CARDFILE-CLOSE, 9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS
CBACT03C.cbl [5]: 1000-XREFFILE-GET-NEXT, 0000-XREFFILE-OPEN, 9000-XREFFILE-CLOSE, 9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS
CBACT04C.cbl [22]: 0000-TCATBALF-OPEN, 0100-XREFFILE-OPEN, 0200-DISCGRP-OPEN, 0300-ACCTFILE-OPEN, 0400-TRANFILE-OPEN, 1000-TCATBALF-GET-NEXT, 1050-UPDATE-ACCOUNT, 1100-GET-ACCT-DATA, 1110-GET-XREF-DATA, 1200-GET-INTEREST-RATE, 1200-A-GET-DEFAULT-INT-RATE, 1300-COMPUTE-INTEREST, 1300-B-WRITE-TX, 1400-COMPUTE-FEES, 9000-TCATBALF-CLOSE, 9100-XREFFILE-CLOSE, 9200-DISCGRP-CLOSE, 9300-ACCTFILE-CLOSE, 9400-TRANFILE-CLOSE, Z-GET-DB2-FORMAT-TIMESTAMP, 9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS
CBCUS01C.cbl [5]: 1000-CUSTFILE-GET-NEXT, 0000-CUSTFILE-OPEN, 9000-CUSTFILE-CLOSE, Z-ABEND-PROGRAM, Z-DISPLAY-IO-STATUS
CBTRN01C.cbl [18]: MAIN-PARA, 1000-DALYTRAN-GET-NEXT, 2000-LOOKUP-XREF, 3000-READ-ACCOUNT, 0000-DALYTRAN-OPEN, 0100-CUSTFILE-OPEN, 0200-XREFFILE-OPEN, 0300-CARDFILE-OPEN, 0400-ACCTFILE-OPEN, 0500-TRANFILE-OPEN, 9000-DALYTRAN-CLOSE, 9100-CUSTFILE-CLOSE, 9200-XREFFILE-CLOSE, 9300-CARDFILE-CLOSE, 9400-ACCTFILE-CLOSE, 9500-TRANFILE-CLOSE, Z-ABEND-PROGRAM, Z-DISPLAY-IO-STATUS
CBTRN02C.cbl [26]: 0000-DALYTRAN-OPEN, 0100-TRANFILE-OPEN, 0200-XREFFILE-OPEN, 0300-DALYREJS-OPEN, 0400-ACCTFILE-OPEN, 0500-TCATBALF-OPEN, 1000-DALYTRAN-GET-NEXT, 1500-VALIDATE-TRAN, 1500-A-LOOKUP-XREF, 1500-B-LOOKUP-ACCT, 2000-POST-TRANSACTION, 2500-WRITE-REJECT-REC, 2700-UPDATE-TCATBAL, 2700-A-CREATE-TCATBAL-REC, 2700-B-UPDATE-TCATBAL-REC, 2800-UPDATE-ACCOUNT-REC, 2900-WRITE-TRANSACTION-FILE, 9000-DALYTRAN-CLOSE, 9100-TRANFILE-CLOSE, 9200-XREFFILE-CLOSE, 9300-DALYREJS-CLOSE, 9400-ACCTFILE-CLOSE, 9500-TCATBALF-CLOSE, Z-GET-DB2-FORMAT-TIMESTAMP, 9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS
CBTRN03C.cbl [26]: 0550-DATEPARM-READ, 1000-TRANFILE-GET-NEXT, 1100-WRITE-TRANSACTION-REPORT, 1110-WRITE-PAGE-TOTALS, 1120-WRITE-ACCOUNT-TOTALS, 1110-WRITE-GRAND-TOTALS, 1120-WRITE-HEADERS, 1111-WRITE-REPORT-REC, 1120-WRITE-DETAIL, 0000-TRANFILE-OPEN, 0100-REPTFILE-OPEN, 0200-CARDXREF-OPEN, 0300-TRANTYPE-OPEN, 0400-TRANCATG-OPEN, 0500-DATEPARM-OPEN, 1500-A-LOOKUP-XREF, 1500-B-LOOKUP-TRANTYPE, 1500-C-LOOKUP-TRANCATG, 9000-TRANFILE-CLOSE, 9100-REPTFILE-CLOSE, 9200-CARDXREF-CLOSE, 9300-TRANTYPE-CLOSE, 9400-TRANCATG-CLOSE, 9500-DATEPARM-CLOSE, 9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS
CBSTM03A.CBL [25]: 0000-START, 1000-MAINLINE, 9999-GOBACK, 1000-XREFFILE-GET-NEXT, 2000-CUSTFILE-GET, 3000-ACCTFILE-GET, 4000-TRNXFILE-GET, 5000-CREATE-STATEMENT, 5100-WRITE-HTML-HEADER, 5100-EXIT, 5200-WRITE-HTML-NMADBS, 5200-EXIT, 6000-WRITE-TRANS, 8100-FILE-OPEN, 8100-TRNXFILE-OPEN, 8200-XREFFILE-OPEN, 8300-CUSTFILE-OPEN, 8400-ACCTFILE-OPEN, 8500-READTRNX-READ, 8599-EXIT, 9100-TRNXFILE-CLOSE, 9200-XREFFILE-CLOSE, 9300-CUSTFILE-CLOSE, 9400-ACCTFILE-CLOSE, 9999-ABEND-PROGRAM
CBSTM03B.CBL [14]: 0000-START, 9999-GOBACK, 1000-TRNXFILE-PROC, 1900-EXIT, 1999-EXIT, 2000-XREFFILE-PROC, 2900-EXIT, 2999-EXIT, 3000-CUSTFILE-PROC, 3900-EXIT, 3999-EXIT, 4000-ACCTFILE-PROC, 4900-EXIT, 4999-EXIT
COACTUPC.cbl [85]: 0000-MAIN, COMMON-RETURN, 0000-MAIN-EXIT, 1000-PROCESS-INPUTS, 1000-PROCESS-INPUTS-EXIT, 1100-RECEIVE-MAP, 1100-RECEIVE-MAP-EXIT, 1200-EDIT-MAP-INPUTS, 1200-EDIT-MAP-INPUTS-EXIT, 1205-COMPARE-OLD-NEW, 1205-COMPARE-OLD-NEW-EXIT, 1210-EDIT-ACCOUNT, 1210-EDIT-ACCOUNT-EXIT, 1215-EDIT-MANDATORY, 1215-EDIT-MANDATORY-EXIT, 1220-EDIT-YESNO, 1220-EDIT-YESNO-EXIT, 1225-EDIT-ALPHA-REQD, 1225-EDIT-ALPHA-REQD-EXIT, 1230-EDIT-ALPHANUM-REQD, 1230-EDIT-ALPHANUM-REQD-EXIT, 1235-EDIT-ALPHA-OPT, 1235-EDIT-ALPHA-OPT-EXIT, 1240-EDIT-ALPHANUM-OPT, 1240-EDIT-ALPHANUM-OPT-EXIT, 1245-EDIT-NUM-REQD, 1245-EDIT-NUM-REQD-EXIT, 1250-EDIT-SIGNED-9V2, 1250-EDIT-SIGNED-9V2-EXIT, 1260-EDIT-US-PHONE-NUM, EDIT-AREA-CODE, EDIT-US-PHONE-PREFIX, EDIT-US-PHONE-LINENUM, EDIT-US-PHONE-EXIT, 1260-EDIT-US-PHONE-NUM-EXIT, 1265-EDIT-US-SSN, 1265-EDIT-US-SSN-EXIT, 1270-EDIT-US-STATE-CD, 1270-EDIT-US-STATE-CD-EXIT, 1275-EDIT-FICO-SCORE, 1275-EDIT-FICO-SCORE-EXIT, 1280-EDIT-US-STATE-ZIP-CD, 1280-EDIT-US-STATE-ZIP-CD-EXIT, 2000-DECIDE-ACTION, 2000-DECIDE-ACTION-EXIT, 3000-SEND-MAP, 3000-SEND-MAP-EXIT, 3100-SCREEN-INIT, 3100-SCREEN-INIT-EXIT, 3200-SETUP-SCREEN-VARS, 3200-SETUP-SCREEN-VARS-EXIT, 3201-SHOW-INITIAL-VALUES, 3201-SHOW-INITIAL-VALUES-EXIT, 3202-SHOW-ORIGINAL-VALUES, 3202-SHOW-ORIGINAL-VALUES-EXIT, 3203-SHOW-UPDATED-VALUES, 3203-SHOW-UPDATED-VALUES-EXIT, 3250-SETUP-INFOMSG, 3250-SETUP-INFOMSG-EXIT, 3300-SETUP-SCREEN-ATTRS, 3300-SETUP-SCREEN-ATTRS-EXIT, 3310-PROTECT-ALL-ATTRS, 3310-PROTECT-ALL-ATTRS-EXIT, 3320-UNPROTECT-FEW-ATTRS, 3320-UNPROTECT-FEW-ATTRS-EXIT, 3390-SETUP-INFOMSG-ATTRS, 3390-SETUP-INFOMSG-ATTRS-EXIT, 3400-SEND-SCREEN, 3400-SEND-SCREEN-EXIT, 9000-READ-ACCT, 9000-READ-ACCT-EXIT, 9200-GETCARDXREF-BYACCT, 9200-GETCARDXREF-BYACCT-EXIT, 9300-GETACCTDATA-BYACCT, 9300-GETACCTDATA-BYACCT-EXIT, 9400-GETCUSTDATA-BYCUST, 9400-GETCUSTDATA-BYCUST-EXIT, 9500-STORE-FETCHED-DATA, 9500-STORE-FETCHED-DATA-EXIT, 9600-WRITE-PROCESSING, 9600-WRITE-PROCESSING-EXIT, 9700-CHECK-CHANGE-IN-REC, 9700-CHECK-CHANGE-IN-REC-EXIT, ABEND-ROUTINE, ABEND-ROUTINE-EXIT
COACTVWC.cbl [34]: 0000-MAIN, COMMON-RETURN, 0000-MAIN-EXIT, 1000-SEND-MAP, 1000-SEND-MAP-EXIT, 1100-SCREEN-INIT, 1100-SCREEN-INIT-EXIT, 1200-SETUP-SCREEN-VARS, 1200-SETUP-SCREEN-VARS-EXIT, 1300-SETUP-SCREEN-ATTRS, 1300-SETUP-SCREEN-ATTRS-EXIT, 1400-SEND-SCREEN, 1400-SEND-SCREEN-EXIT, 2000-PROCESS-INPUTS, 2000-PROCESS-INPUTS-EXIT, 2100-RECEIVE-MAP, 2100-RECEIVE-MAP-EXIT, 2200-EDIT-MAP-INPUTS, 2200-EDIT-MAP-INPUTS-EXIT, 2210-EDIT-ACCOUNT, 2210-EDIT-ACCOUNT-EXIT, 9000-READ-ACCT, 9000-READ-ACCT-EXIT, 9200-GETCARDXREF-BYACCT, 9200-GETCARDXREF-BYACCT-EXIT, 9300-GETACCTDATA-BYACCT, 9300-GETACCTDATA-BYACCT-EXIT, 9400-GETCUSTDATA-BYCUST, 9400-GETCUSTDATA-BYCUST-EXIT, SEND-PLAIN-TEXT, SEND-PLAIN-TEXT-EXIT, SEND-LONG-TEXT, SEND-LONG-TEXT-EXIT, ABEND-ROUTINE
COADM01C.cbl [7]: MAIN-PARA, PROCESS-ENTER-KEY, RETURN-TO-SIGNON-SCREEN, SEND-MENU-SCREEN, RECEIVE-MENU-SCREEN, POPULATE-HEADER-INFO, BUILD-MENU-OPTIONS
COBIL00C.cbl [16]: MAIN-PARA, PROCESS-ENTER-KEY, GET-CURRENT-TIMESTAMP, RETURN-TO-PREV-SCREEN, SEND-BILLPAY-SCREEN, RECEIVE-BILLPAY-SCREEN, POPULATE-HEADER-INFO, READ-ACCTDAT-FILE, UPDATE-ACCTDAT-FILE, READ-CXACAIX-FILE, STARTBR-TRANSACT-FILE, READPREV-TRANSACT-FILE, ENDBR-TRANSACT-FILE, WRITE-TRANSACT-FILE, CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS
COCRDLIC.cbl [39]: 0000-MAIN, COMMON-RETURN, 0000-MAIN-EXIT, 1000-SEND-MAP, 1000-SEND-MAP-EXIT, 1100-SCREEN-INIT, 1100-SCREEN-INIT-EXIT, 1200-SCREEN-ARRAY-INIT, 1200-SCREEN-ARRAY-INIT-EXIT, 1250-SETUP-ARRAY-ATTRIBS, 1250-SETUP-ARRAY-ATTRIBS-EXIT, 1300-SETUP-SCREEN-ATTRS, 1300-SETUP-SCREEN-ATTRS-EXIT, 1400-SETUP-MESSAGE, 1400-SETUP-MESSAGE-EXIT, 1500-SEND-SCREEN, 1500-SEND-SCREEN-EXIT, 2000-RECEIVE-MAP, 2000-RECEIVE-MAP-EXIT, 2100-RECEIVE-SCREEN, 2100-RECEIVE-SCREEN-EXIT, 2200-EDIT-INPUTS, 2200-EDIT-INPUTS-EXIT, 2210-EDIT-ACCOUNT, 2210-EDIT-ACCOUNT-EXIT, 2220-EDIT-CARD, 2220-EDIT-CARD-EXIT, 2250-EDIT-ARRAY, 2250-EDIT-ARRAY-EXIT, 9000-READ-FORWARD, 9000-READ-FORWARD-EXIT, 9100-READ-BACKWARDS, 9100-READ-BACKWARDS-EXIT, 9500-FILTER-RECORDS, 9500-FILTER-RECORDS-EXIT, SEND-PLAIN-TEXT, SEND-PLAIN-TEXT-EXIT, SEND-LONG-TEXT, SEND-LONG-TEXT-EXIT
COCRDSLC.cbl [34]: 0000-MAIN, COMMON-RETURN, 0000-MAIN-EXIT, 1000-SEND-MAP, 1000-SEND-MAP-EXIT, 1100-SCREEN-INIT, 1100-SCREEN-INIT-EXIT, 1200-SETUP-SCREEN-VARS, 1200-SETUP-SCREEN-VARS-EXIT, 1300-SETUP-SCREEN-ATTRS, 1300-SETUP-SCREEN-ATTRS-EXIT, 1400-SEND-SCREEN, 1400-SEND-SCREEN-EXIT, 2000-PROCESS-INPUTS, 2000-PROCESS-INPUTS-EXIT, 2100-RECEIVE-MAP, 2100-RECEIVE-MAP-EXIT, 2200-EDIT-MAP-INPUTS, 2200-EDIT-MAP-INPUTS-EXIT, 2210-EDIT-ACCOUNT, 2210-EDIT-ACCOUNT-EXIT, 2220-EDIT-CARD, 2220-EDIT-CARD-EXIT, 9000-READ-DATA, 9000-READ-DATA-EXIT, 9100-GETCARD-BYACCTCARD, 9100-GETCARD-BYACCTCARD-EXIT, 9150-GETCARD-BYACCT, 9150-GETCARD-BYACCT-EXIT, SEND-LONG-TEXT, SEND-LONG-TEXT-EXIT, SEND-PLAIN-TEXT, SEND-PLAIN-TEXT-EXIT, ABEND-ROUTINE
COCRDUPC.cbl [45]: 0000-MAIN, COMMON-RETURN, 0000-MAIN-EXIT, 1000-PROCESS-INPUTS, 1000-PROCESS-INPUTS-EXIT, 1100-RECEIVE-MAP, 1100-RECEIVE-MAP-EXIT, 1200-EDIT-MAP-INPUTS, 1200-EDIT-MAP-INPUTS-EXIT, 1210-EDIT-ACCOUNT, 1210-EDIT-ACCOUNT-EXIT, 1220-EDIT-CARD, 1220-EDIT-CARD-EXIT, 1230-EDIT-NAME, 1230-EDIT-NAME-EXIT, 1240-EDIT-CARDSTATUS, 1240-EDIT-CARDSTATUS-EXIT, 1250-EDIT-EXPIRY-MON, 1250-EDIT-EXPIRY-MON-EXIT, 1260-EDIT-EXPIRY-YEAR, 1260-EDIT-EXPIRY-YEAR-EXIT, 2000-DECIDE-ACTION, 2000-DECIDE-ACTION-EXIT, 3000-SEND-MAP, 3000-SEND-MAP-EXIT, 3100-SCREEN-INIT, 3100-SCREEN-INIT-EXIT, 3200-SETUP-SCREEN-VARS, 3200-SETUP-SCREEN-VARS-EXIT, 3250-SETUP-INFOMSG, 3250-SETUP-INFOMSG-EXIT, 3300-SETUP-SCREEN-ATTRS, 3300-SETUP-SCREEN-ATTRS-EXIT, 3400-SEND-SCREEN, 3400-SEND-SCREEN-EXIT, 9000-READ-DATA, 9000-READ-DATA-EXIT, 9100-GETCARD-BYACCTCARD, 9100-GETCARD-BYACCTCARD-EXIT, 9200-WRITE-PROCESSING, 9200-WRITE-PROCESSING-EXIT, 9300-CHECK-CHANGE-IN-REC, 9300-CHECK-CHANGE-IN-REC-EXIT, ABEND-ROUTINE, ABEND-ROUTINE-EXIT
COMEN01C.cbl [7]: MAIN-PARA, PROCESS-ENTER-KEY, RETURN-TO-SIGNON-SCREEN, SEND-MENU-SCREEN, RECEIVE-MENU-SCREEN, POPULATE-HEADER-INFO, BUILD-MENU-OPTIONS
CORPT00C.cbl [10]: MAIN-PARA, PROCESS-ENTER-KEY, SUBMIT-JOB-TO-INTRDR, WIRTE-JOBSUB-TDQ, RETURN-TO-PREV-SCREEN, SEND-TRNRPT-SCREEN, RETURN-TO-CICS, RECEIVE-TRNRPT-SCREEN, POPULATE-HEADER-INFO, INITIALIZE-ALL-FIELDS
COSGN00C.cbl [6]: MAIN-PARA, PROCESS-ENTER-KEY, SEND-SIGNON-SCREEN, SEND-PLAIN-TEXT, POPULATE-HEADER-INFO, READ-USER-SEC-FILE
COTRN00C.cbl [16]: MAIN-PARA, PROCESS-ENTER-KEY, PROCESS-PF7-KEY, PROCESS-PF8-KEY, PROCESS-PAGE-FORWARD, PROCESS-PAGE-BACKWARD, POPULATE-TRAN-DATA, INITIALIZE-TRAN-DATA, RETURN-TO-PREV-SCREEN, SEND-TRNLST-SCREEN, RECEIVE-TRNLST-SCREEN, POPULATE-HEADER-INFO, STARTBR-TRANSACT-FILE, READNEXT-TRANSACT-FILE, READPREV-TRANSACT-FILE, ENDBR-TRANSACT-FILE
COTRN01C.cbl [9]: MAIN-PARA, PROCESS-ENTER-KEY, RETURN-TO-PREV-SCREEN, SEND-TRNVIEW-SCREEN, RECEIVE-TRNVIEW-SCREEN, POPULATE-HEADER-INFO, READ-TRANSACT-FILE, CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS
COTRN02C.cbl [18]: MAIN-PARA, PROCESS-ENTER-KEY, VALIDATE-INPUT-KEY-FIELDS, VALIDATE-INPUT-DATA-FIELDS, ADD-TRANSACTION, COPY-LAST-TRAN-DATA, RETURN-TO-PREV-SCREEN, SEND-TRNADD-SCREEN, RECEIVE-TRNADD-SCREEN, POPULATE-HEADER-INFO, READ-CXACAIX-FILE, READ-CCXREF-FILE, STARTBR-TRANSACT-FILE, READPREV-TRANSACT-FILE, ENDBR-TRANSACT-FILE, WRITE-TRANSACT-FILE, CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS
COUSR00C.cbl [16]: MAIN-PARA, PROCESS-ENTER-KEY, PROCESS-PF7-KEY, PROCESS-PF8-KEY, PROCESS-PAGE-FORWARD, PROCESS-PAGE-BACKWARD, POPULATE-USER-DATA, INITIALIZE-USER-DATA, RETURN-TO-PREV-SCREEN, SEND-USRLST-SCREEN, RECEIVE-USRLST-SCREEN, POPULATE-HEADER-INFO, STARTBR-USER-SEC-FILE, READNEXT-USER-SEC-FILE, READPREV-USER-SEC-FILE, ENDBR-USER-SEC-FILE
COUSR01C.cbl [9]: MAIN-PARA, PROCESS-ENTER-KEY, RETURN-TO-PREV-SCREEN, SEND-USRADD-SCREEN, RECEIVE-USRADD-SCREEN, POPULATE-HEADER-INFO, WRITE-USER-SEC-FILE, CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS
COUSR02C.cbl [11]: MAIN-PARA, PROCESS-ENTER-KEY, UPDATE-USER-INFO, RETURN-TO-PREV-SCREEN, SEND-USRUPD-SCREEN, RECEIVE-USRUPD-SCREEN, POPULATE-HEADER-INFO, READ-USER-SEC-FILE, UPDATE-USER-SEC-FILE, CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS
COUSR03C.cbl [11]: MAIN-PARA, PROCESS-ENTER-KEY, DELETE-USER-INFO, RETURN-TO-PREV-SCREEN, SEND-USRDEL-SCREEN, RECEIVE-USRDEL-SCREEN, POPULATE-HEADER-INFO, READ-USER-SEC-FILE, DELETE-USER-SEC-FILE, CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS
CSUTLDTC.cbl [2]: A000-MAIN, A000-MAIN-EXIT
```

Counts: 6+5+5+22+5+18+26+26+25+14+85+34+7+16+39+34+45+7+10+6+16+9+18+16+9+11+11+2 = **527** unique paragraphs across **28** programs.

