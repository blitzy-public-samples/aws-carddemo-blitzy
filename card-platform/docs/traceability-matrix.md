# Traceability Matrix

Rule 1 requires every source construct to reach a target or a documented exclusion. The matrix also reads backward, from every one of the 802 delivered target paths to source provenance or a net-new marker. Coverage arithmetic closes each source inventory before any mapping detail. Design rationale lives in the [decision log](decision-log.md), flagged source ambiguities in [business-rule flags](business-rule-flags.md), and the paired architecture views in [architecture before and after](architecture-before-after.md).

## Coverage summary

Every count in this document was measured in the repository. Where a measurement contradicts prose written elsewhere, the measured value is the one recorded here.

| Source location | Members | Classification breakdown | Sums to |
| --- | ---: | --- | ---: |
| `app/cbl/` | 28 | 12 primary migration sources, 8 reference-only, 2 partial, 6 excluded | 28 |
| `app/cpy/` | 28 | 12 record layouts, 9 reference or semantics inputs, 1 divergent fork, 5 excluded, 1 dead | 28 |
| `app/jcl/` | 29 | 11 dataset definitions, 3 behavior-defining jobs, 15 excluded utility or report jobs | 29 |
| `app/csd/CARDDEMO.CSD` | 8 files, 17 mapsets, 18 programs, 18 transactions | Mapped or excluded below | — |
| `app/bms/` | 17 | All excluded because no application user interface is in scope | 17 |
| `app/cpy-bms/` | 17 source copybooks | All excluded for the same reason; `.gitkeep` is not a source member | 17 |
| `app/data/ASCII/` | 9 | All reused as fixtures or seed sources | 9 |
| `app/data/EBCDIC/` | 12 data artifacts | All retained as binary or width references; `.gitkeep` is excluded from the count | 12 |
| Delivered target tree | 802 tracked files | 290 source-derived, 236 verification source-derived, 132 verification additive, 81 additive, 44 net new platform, 11 Rule-mandated documents, 8 Rule 3 documents | 802 |

The backward direction closes on its own count. The delivered tree holds 802 tracked target paths. The two module-level tables below resolve them in 52 rows, each naming a module or a uniform group rather than a file. Every one of the 802 then appears once in [backward: every target path](#backward-every-target-path), so the target side is enumerated rather than summarised.

## Forward: COBOL programs

### Primary migration sources

| Source file | Target module or artifact | What it supplies |
| --- | --- | --- |
| `app/cbl/CBTRN02C.cbl` | authorization, ledger, event contracts, equivalence tests | Four decline rules, posting arithmetic, category-balance upsert, reject layout, timestamps, and abend behavior |
| `app/cbl/COTRN02C.cbl` | authorization API, numeric and date validation, equivalence tests | Synchronous request fields, tolerant parsing, date calls, and original transaction-add identifier allocation |
| `app/cbl/COSGN00C.cbl` | service security policies and authorization synthesis | Plaintext credential comparison and the effective administrator-versus-user fork |
| `app/cbl/COACTUPC.cbl` | account service | Validation paragraphs, two-row update, and field-level concurrent-change comparison |
| `app/cbl/COACTVWC.cbl` | account service | Account and customer view fields plus cross-reference resolution |
| `app/cbl/COCRDLIC.cbl` | card service | Seven-row paging and one-row lookahead |
| `app/cbl/COCRDSLC.cbl` | card service | Card-detail retrieval fields |
| `app/cbl/COCRDUPC.cbl` | card service | Update ordering, six source messages, expiry decomposition, and concurrent-change behavior |
| `app/cbl/COBIL00C.cbl` | bill-payment equivalence test | Online payment arithmetic, stamped transaction values, and the separate identifier race |
| `app/cbl/CSUTLDTC.cbl` | `libs/cobol-compat` | Date-validation semantics and the inverted success-condition name |
| `app/cbl/COTRN00C.cbl` | ledger model and transaction query contract | Transaction-list fields; no separate transaction-list API is exposed in the demo |
| `app/cbl/COTRN01C.cbl` | ledger model and transaction query contract | Transaction-detail fields; no separate transaction-detail API is exposed in the demo |

### Reference-only programs

| Source file | Why it is read | Target influence |
| --- | --- | --- |
| `app/cbl/CBSTM03B.CBL` | Generic operation-code input and output subroutine | Repository-interface shape |
| `app/cbl/CORPT00C.cbl` | Only source asynchronous handoff | Transactional-outbox provenance |
| `app/cbl/COMEN01C.cbl` | Effective menu role behavior and dead identity moves | Request security and business-rule flags 20-21 |
| `app/cbl/CBACT01C.cbl` | Print-only account report reading `ACCTFILE` at `:L29`, with zero writes or rewrites | Negative evidence; no migration target |
| `app/cbl/CBACT02C.cbl` | Print-only card report reading `CARDFILE` at `:L29`, with zero writes or rewrites | Negative evidence; no migration target |
| `app/cbl/CBACT03C.cbl` | Print-only cross-reference report reading `XREFFILE` at `:L29`, with zero writes or rewrites | Negative evidence; no migration target |
| `app/cbl/CBCUS01C.cbl` | Print-only customer report reading `CUSTFILE` at `:L29`, with zero writes or rewrites | Negative evidence; no migration target |
| `app/cbl/CBTRN01C.cbl` | Print-only transaction report; the only batch program that opens the card file beside the daily feed | Proves that posting does not check card status |

The five print-only programs contain no `WRITE` and no `REWRITE`, and each opens every dataset for input only. Four of them open a single dataset each. `CBTRN01C.cbl` opens six, at `:L254`, `:L273`, `:L291`, `:L309`, `:L327`, and `:L345`. Only two batch programs name the card file: `CBACT02C.cbl:L29` reads it alone to print it, and `CBTRN01C.cbl:L46` reads it beside the daily feed. `CBTRN02C.cbl` names it nowhere, and that measurement is what business-rule flag 3 rests on.

### Partially in scope

| Source file | Included slice | Target |
| --- | --- | --- |
| `app/cbl/CBACT04C.cbl` | Lines 350-356 for the cycle-counter reset: paragraph `1050-UPDATE-ACCOUNT` at `:L350`, `MOVE 0 TO ACCT-CURR-CYC-CREDIT` at `:L353`, `MOVE 0 TO ACCT-CURR-CYC-DEBIT` at `:L354`, and the rewrite at `:L356`. Lines 415-470 for equivalence only | `BillingCycleService`, `InterestCalculationEquivalenceTest`, and `DecimalTruncationEquivalenceTest` |
| `app/cbl/CBSTM03A.CBL` | Lines 86-159 for statement content and rendering structure. Both renderers sit in that slice: the `STATEMENT-LINES` group header at `:L85` opens the plain-text layout that begins at `:L86`, and the markup literals run from `01 HTML-LINES` at `:L148` with `88 HTML-L01` at `:L150` | Notification plain-text and HTML renderers |

Interest accrual and full statement generation remain outside the runtime migration. The cycle-counter reset is carried across only because the credit-limit rule reads the two counters it zeroes.

### Excluded programs

| Source file | Exclusion |
| --- | --- |
| `app/cbl/COUSR00C.cbl` | User-management menu, excluded by scope |
| `app/cbl/COUSR01C.cbl` | User creation, excluded by scope |
| `app/cbl/COUSR02C.cbl` | User update, excluded by scope |
| `app/cbl/COUSR03C.cbl` | User deletion, excluded by scope |
| `app/cbl/COADM01C.cbl` | Administrator management, excluded by scope |
| `app/cbl/CBTRN03C.cbl` | Printed transaction reporting, excluded by scope |

**Program member closure:** 12 + 8 + 2 + 6 = 28.

## Forward: copybooks

### Record layouts

Widths are the record lengths the copybook headers declare. Column types are derived in the [data model](data-model.md) rather than here.

| Source copybook | Declared width | Target |
| --- | ---: | --- |
| `app/cpy/CVACT01Y.cpy` | 300 | Account entity, ledger balance projection, authorization credit snapshot, migrations, and events |
| `app/cpy/CVACT02Y.cpy` | 150 | Card entity, migration, card API, and `CardUpdated` |
| `app/cpy/CVACT03Y.cpy` | 50 | Private `card_xref` tables in the authorization and card services, the account service's card-free `account_customer_link`, and authorization event account resolution. `XREF-CARD-NUM` is dropped from the account copy because no query there reads a card |
| `app/cpy/CVCUS01Y.cpy` | 500 | Canonical customer entity and notification cardholder projection |
| `app/cpy/CVTRA01Y.cpy` | 50 | Ledger transaction-category balance entity and composite key |
| `app/cpy/CVTRA05Y.cpy` | 350 | Ledger transaction entity and posted-event provenance |
| `app/cpy/CVTRA06Y.cpy` | 350 | Inbound authorization event and fixture parser; the field list matches `CVTRA05Y.cpy` byte for byte under a different prefix |
| `app/cpy/COSTM01.CPY` | 350 | Notification statement-transaction read model, re-keyed on card number and transaction identifier at `:L21-L23` |
| `app/cpy/CVTRA02Y.cpy` | 50 | Disclosure-group entity and interest-equivalence input |
| `app/cpy/CVTRA03Y.cpy` | 60 | Seven transaction-type seed rows |
| `app/cpy/CVTRA04Y.cpy` | 60 | Eighteen transaction-category seed rows |
| `app/cpy/CSUSR01Y.cpy` | 80 | Security-record semantics and signon evidence; no user-management entity is migrated |

### Reference and semantics inputs

| Source copybook | Target influence |
| --- | --- |
| `app/cpy/CSLKPCDY.cpy` | Three generated validation-reference classes and three account reference tables holding 786 rows. The file spans 1318 lines and carries 1276 literals across five condition names: 490 area codes at `:L30`, the same codes re-listed as 410 at `:L521` and 80 at `:L931`, 56 state codes at `:L1013`, and 240 state-and-ZIP-prefix combinations at `:L1073`. Stored rows are 490 plus 56 plus 240, since an area code is one row carrying a band discriminator. `LAST-3-OF-ZIP` at `:L1314` is a sanctioned omission: no condition name, no reader under `app/` |
| `app/cpy/CSUTLDPY.cpy` | Shared date-validator input contract |
| `app/cpy/CSUTLDWY.cpy` | Shared date-validator working semantics |
| `app/cpy/CSMSG02Y.cpy` | Dead-letter metadata fields |
| `app/cpy/COCOM01Y.cpy` | Effective role names and documented navigation omissions |
| `app/cpy/COMEN02Y.cpy` | Menu routing evidence and business-rule flags 19, 20, and 22 |
| `app/cpy/CVCRD01Y.cpy` | Card field widths; navigation fields are omitted |
| `app/cpy/CSMSG01Y.cpy` | Common source message text |
| `app/cpy/CSDAT01Y.cpy` | Timestamp layout authority, not a presentation helper. `WS-TIMESTAMP` at `:L42-L55` declares the separated shape a posted transaction carries, and `BillPaymentEquivalenceTest` reads that declaration for every separator position it asserts: the dashes inside the date, the single space the `FILLER` at `:L48` holds, and the colons at `:L50`. The screen-formatting fields in the same copybook have no target |

### Divergent fork

| Source copybook | Handling |
| --- | --- |
| `app/cpy/CUSTREC.cpy` | Read only to document its one-name fork from `CVCUS01Y.cpy`, which names the date of birth `CUST-DOB-YYYYMMDD` at `:L19` where the canonical copy names it `CUST-DOB-YYYY-MM-DD`. Exactly one program binds the fork: `app/cbl/CBSTM03A.CBL:L55`. Six bind the canonical copy: `COACTVWC.cbl:L254`, `COACTUPC.cbl:L646`, `COCRDSLC.cbl:L240`, `COCRDUPC.cbl:L359`, `CBCUS01C.cbl:L45`, and `CBTRN01C.cbl:L104`. Business-rule flag 12 carries the evidence |

### Excluded copybooks

Each row names what the copybook holds, which is also why it has no target. Three carry screen and terminal state, excluded with the presentation layer. One carries administrator menu state, excluded with the administrator programs. One carries a print report layout, excluded with reporting.

| Source copybook | What it holds, and therefore the exclusion |
| --- | --- |
| `app/cpy/COADM02Y.cpy` | Administrator menu state; excluded with the administrator programs |
| `app/cpy/COTTL01Y.cpy` | Terminal title and presentation state; excluded with the presentation layer |
| `app/cpy/CSSETATY.cpy` | Terminal attribute helper; excluded with the presentation layer |
| `app/cpy/CSSTRPFY.cpy` | String and screen presentation helper; excluded with the presentation layer |
| `app/cpy/CVTRA07Y.cpy` | Printed transaction report layout; excluded with reporting |

### Dead copybook

| Source copybook | Handling |
| --- | --- |
| `app/cpy/UNUSED1Y.cpy` | Dead duplicate of the security-user widths; business-rule flag 26 records the evidence |

**Copybook closure:** 12 + 9 + 1 + 5 + 1 = 28.

## Forward: Job Control Language members

No Job Control Language member becomes a scheduler, cron entry, or workflow. Dataset definitions supply keys, record sizes, and seed behavior; event consumers replace the runtime batch trigger.

### Dataset-definition and load jobs

| Member | Target use |
| --- | --- |
| `ACCTFILE.jcl` | Account key, record size, and account seed migration |
| `CARDFILE.jcl` | Card primary key, account alternate index, record size, and card seed |
| `CUSTFILE.jcl` | Customer key, record size, and customer seed |
| `XREFFILE.jcl` | Card-number key `KEYS(16 0)` at `:L43` and `RECORDSIZE(50 50)` at `:L44` become the primary key and width. The alternate index at `:L72-L77` with `KEYS(11,25)` at `:L74` becomes the secondary index on account identifier. The seed follows the `REPRO` step |
| `TRANFILE.jcl` | Transaction primary storage definition |
| `TRANIDX.jcl` | Transaction access-path evidence |
| `TCATBALF.jcl` | Category-balance composite key, record size, and seed |
| `DISCGRP.jcl` | Disclosure-group composite key, record size, and seed |
| `TRANCATG.jcl` | Transaction-category key, record size, and 18-row seed |
| `TRANTYPE.jcl` | Transaction-type key, record size, and 7-row seed |
| `DUSRSECJ.jcl` | Security-user dataset definition and binary fixture provenance |

Their `REPRO` steps are the source loading mechanism. For example, `app/jcl/XREFFILE.jcl:L64` copies records into the cluster; Flyway performs the target load.

### Behavior-defining jobs

| Member | Target use |
| --- | --- |
| `POSTTRAN.jcl` | `STEP15 EXEC PGM=CBTRN02C` at `:L23` becomes the ledger consumer. The step allocates six datasets at `:L28`, `:L30`, `:L32`, `:L34`, `:L39`, and `:L41`, and the card file is not among them. The reject Generation Data Group at `:L34-L38` carries `LRECL=430`, which sets the reject width |
| `INTCALC.jcl` | Interest equivalence and the cycle-close dependency. `STEP15 EXEC PGM=CBACT04C,PARM='2022071800'` at `:L22` shows the run date arriving as a hard-coded parameter. No event-driven interest service exists |
| `CREASTMT.JCL` | The card-plus-transaction key for the notification read model. `KEYS(32 0)` at `:L30` defines the 32-byte composite key, and the sort step at `:L44` with `SORT FIELDS=(263,16,CH,A,1,16,CH,A)` at `:L53` shows card number ahead of transaction identifier |

### Excluded utility and report jobs

| Member | Reason |
| --- | --- |
| `OPENFIL.jcl` | Mainframe file housekeeping |
| `CLOSEFIL.jcl` | Mainframe file housekeeping |
| `DEFGDGB.jcl` | Generation Data Group utility |
| `DEFCUST.jcl` | Dataset utility |
| `TRANBKP.jcl` | Dataset backup |
| `COMBTRAN.jcl` | Batch feed combination |
| `CBADMCDJ.jcl` | Administrator compile or execution utility |
| `READACCT.jcl` | Diagnostic reader |
| `READCARD.jcl` | Diagnostic reader |
| `READCUST.jcl` | Diagnostic reader |
| `READXREF.jcl` | Diagnostic reader |
| `DALYREJS.jcl` | Reject-dataset utility |
| `TRANREPT.jcl` | Reporting |
| `PRTCATBL.jcl` | Reporting |
| `REPTFILE.jcl` | Reporting dataset utility |

**Job closure:** 11 + 3 + 15 = 29.

## Forward: CICS resource definitions

`app/csd/CARDDEMO.CSD` defines the resources of the Customer Information Control System, the transaction monitor the source runs under. The file declares 8 files, 17 mapsets, 18 programs, and 18 transactions. Each of the four inventories closes in its own subsection below.

### File definitions

| Definition | Locator | Kind | Main target mapping |
| --- | --- | --- | --- |
| `ACCTDAT` | `app/csd/CARDDEMO.CSD:L1` | Base dataset | Account table and service-local balance or credit projections |
| `CARDAIX` | `app/csd/CARDDEMO.CSD:L13` | Alternate-index path | Card account index |
| `CARDDAT` | `app/csd/CARDDEMO.CSD:L25` | Base dataset | Card table |
| `CCXREF` | `app/csd/CARDDEMO.CSD:L37` | Base dataset | Private card cross-reference tables |
| `CUSTDAT` | `app/csd/CARDDEMO.CSD:L50` | Base dataset | Customer table and notification projection |
| `CXACAIX` | `app/csd/CARDDEMO.CSD:L63` | Alternate-index path | Cross-reference account index |
| `TRANSACT` | `app/csd/CARDDEMO.CSD:L76` | Base dataset | Ledger transaction table and notification read model |
| `USRSEC` | `app/csd/CARDDEMO.CSD:L88` | Base dataset | Security behavior evidence; user management remains excluded |

The online region exposes six base datasets and two alternate-index paths. `TCATBALF` and `DISCGRP` are batch-only allocations in `app/jcl/POSTTRAN.jcl:L41-L42` and `app/jcl/INTCALC.jcl:L35-L36`.

Every file definition has `JOURNAL(NO)` and `RECOVERY(NONE)`. The source contains eight occurrences of each setting.

### Transaction definitions

| Transaction and program | Target or exclusion |
| --- | --- |
| `CAUP` → `COACTUPC` | `PUT /accounts/{accountId}` |
| `CAVW` → `COACTVWC` | `GET /accounts/{accountId}` and `GET /customers/{customerId}` |
| `CA00` → `COADM01C` | Excluded administrator menu |
| `CB00` → `COBIL00C` | Bill-payment equivalence test; no runtime bill-payment endpoint |
| `CCDL` → `COCRDSLC` | `GET /cards/{cardToken}` |
| `CCLI` → `COCRDLIC` | `GET /cards` |
| `CCUP` → `COCRDUPC` | `PUT /cards/{cardToken}` |
| `CC00` → `COSGN00C` | Basic-authentication behavior and role fork; no signon screen |
| `CDV1` → `COCRDSEC` | Dead orphan definitions; business-rule flag 17 |
| `CM00` → `COMEN01C` | Representational State Transfer (REST) route dispatch replaces the menu; no menu endpoint |
| `CR00` → `CORPT00C` | Reporting excluded; queue write retained as outbox provenance |
| `CT00` → `COTRN00C` | Ledger transaction-list contract; no demo query route |
| `CT01` → `COTRN01C` | Ledger transaction-detail contract; no demo query route |
| `CT02` → `COTRN02C` | `POST /authorizations` request fields and validation |
| `CU00` → `COUSR00C` | Excluded user-management menu |
| `CU01` → `COUSR01C` | Excluded user creation |
| `CU02` → `COUSR02C` | Excluded user update |
| `CU03` → `COUSR03C` | Excluded user deletion |

**Transaction closure:** 18 definitions, each naming exactly one program.

### Program definitions

Each of the 18 program definitions is classified by whether a source member stands behind it. The 17 that have one are already classified in the program buckets above, so this table records the classification rather than repeating the mapping.

| Program definitions | Count | Where the mapping lives |
| --- | ---: | --- |
| Backed by a member and migrated as a primary source | 10 | `COACTUPC`, `COACTVWC`, `COBIL00C`, `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `COSGN00C`, `COTRN00C`, `COTRN01C`, `COTRN02C` in Primary migration sources |
| Backed by a member and read for reference | 2 | `COMEN01C` and `CORPT00C` in Reference-only programs |
| Backed by a member and excluded by scope | 5 | `COADM01C` and `COUSR00C` through `COUSR03C` in Excluded programs |
| Backed by no member | 1 | `COCRDSEC`, the orphan row below |

| Orphan definition | Locator | Handling |
| --- | --- | --- |
| `DEFINE PROGRAM(COCRDSEC)` | `app/csd/CARDDEMO.CSD:L211` | Excluded as dead configuration. No member of `app/cbl/` carries the name, and the name appears nowhere under `app/` outside this one resource file |
| `DEFINE TRANSACTION(CDV1)`, which names that program | `app/csd/CARDDEMO.CSD:L388` with `PROGRAM(COCRDSEC)` at `:L390` | Excluded with the program it points at. Business-rule flag 17 carries the evidence |

**Program definition closure:** 10 + 2 + 5 + 1 = 18.

### Mapset definitions

All 17 mapset definitions are excluded for one reason: a 3270 screen definition has no counterpart in a JSON interface, and no application user interface is in scope.

| Mapset definitions | Locators | Handling |
| --- | --- | --- |
| `COACTUP`, `COACTVW`, `COADM01`, `COBIL00`, `COCRDLI`, `COCRDSL`, `COCRDUP`, `COMEN01`, `CORPT00`, `COSGN00`, `COTRN00`, `COTRN01`, `COTRN02`, `COUSR00`, `COUSR01`, `COUSR02`, `COUSR03` | `app/csd/CARDDEMO.CSD` lines 100, 105, 110, 114, 118, 123, 128, 133, 137, 141, 145, 149, 153, 157, 161, 165, and 169 | All 17 excluded. Each name matches one file in `app/bms/` and one in `app/cpy-bms/`, covered in the next section |

**Mapset closure:** 17 excluded, 0 mapped.

## Forward: presentation layer

| Source directory | Members | Handling |
| --- | ---: | --- |
| `app/bms/` | 17 mapset definitions | Excluded because the target exposes JSON APIs and no application user interface |
| `app/cpy-bms/` | 17 symbolic map copybooks | Excluded with the mapsets; `.gitkeep` is not counted |

Screen widths remain evidence when needed. `app/bms/COCRDSL.bms:L99` shows the full 16-character card field and supports the finding that source masking does not exist.

## Forward: fixture data

### ASCII fixtures

| Fixture | Records × width | Layout | Target consumers |
| --- | ---: | --- | --- |
| `app/data/ASCII/acctdata.txt` | 50 × 300 | `CVACT01Y.cpy` | Account seeds and account, authorization, posting, bill-payment, and interest tests |
| `app/data/ASCII/carddata.txt` | 50 × 150 | `CVACT02Y.cpy` | Card seed and card tests |
| `app/data/ASCII/cardxref.txt` | 50 × 36 | `CVACT03Y.cpy` declares 50 | Authorization and card seeds, the account relationship seed, notification projection, width-tolerant loader |
| `app/data/ASCII/custdata.txt` | 50 × 500 | `CVCUS01Y.cpy` | Account seed, validation tests, notification projection |
| `app/data/ASCII/dailytran.txt` | 300 × 350 | `CVTRA06Y.cpy` | Posting, authorization, and arithmetic equivalence |
| `app/data/ASCII/discgrp.txt` | 51 × 50 | `CVTRA02Y.cpy` | Account seed and interest equivalence |
| `app/data/ASCII/tcatbal.txt` | 50 × 50 | `CVTRA01Y.cpy` | Ledger seed and posting equivalence |
| `app/data/ASCII/trancatg.txt` | 18 × 60 | `CVTRA04Y.cpy` | Ledger reference seed |
| `app/data/ASCII/trantype.txt` | 7 × 60 | `CVTRA03Y.cpy` | Ledger reference seed |

### EBCDIC artifacts

| Artifact | Role |
| --- | --- |
| `AWS.M2.CARDDEMO.ACCDATA.PS` | Binary account-related reference |
| `AWS.M2.CARDDEMO.ACCTDATA.PS` | Binary account fixture |
| `AWS.M2.CARDDEMO.CARDDATA.PS` | Binary card fixture |
| `AWS.M2.CARDDEMO.CARDXREF.PS` | Full-width 50-byte cross-reference authority |
| `AWS.M2.CARDDEMO.CUSTDATA.PS` | Binary customer fixture |
| `AWS.M2.CARDDEMO.DALYTRAN.PS` | Binary daily-transaction fixture |
| `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT` | Initial daily-transaction artifact |
| `AWS.M2.CARDDEMO.DISCGRP.PS` | Binary disclosure-group fixture |
| `AWS.M2.CARDDEMO.TCATBALF.PS` | Binary category-balance fixture |
| `AWS.M2.CARDDEMO.TRANCATG.PS` | Binary category fixture |
| `AWS.M2.CARDDEMO.TRANTYPE.PS` | Binary type fixture |
| `AWS.M2.CARDDEMO.USRSEC.PS` | Security-user fixture with no ASCII twin |

The missing ASCII security twin is why signon tests construct their records. The cross-reference width difference is business-rule flag 25.

## Backward: target modules and provenance

Reading the other direction, every target names where it came from. The Classification column distinguishes a direct migration from a synthesis, a partial port, and an addition with no ancestor. This table is the module-level summary; [backward: every target path](#backward-every-target-path) carries one row per delivered file and closes the direction arithmetically.

| Target module or artifact | Source provenance | Classification |
| --- | --- | --- |
| `libs/event-contracts` | Transaction and account fields from `CVTRA05Y`, `CVTRA06Y`, `CVACT01Y`, `CVACT02Y`, and `CVACT03Y`; decline codes from `CBTRN02C` | Mixed source-derived and additive |
| `libs/cobol-compat` | Picture clauses, `NUMVAL-C`, `CSUTLDTC`, and `CSLKPCDY` | Source-derived compatibility layer |
| `authorization-service` | Synthesized from three programs: decision rules from `CBTRN02C`, the request contract from `COTRN02C`, the authentication and role fork from `COSGN00C`. The program the requirements name has no member: `COPAUA0C` returns zero matches across `app/`, and `CP00` returns zero matches in `app/csd/CARDDEMO.CSD`. Business-rule flag 1 carries both searches | Synthesized migration, declared rather than presented as a single-program port |
| `ledger-posting-service` | `CBTRN02C`, `POSTTRAN.jcl`, `CVTRA05Y`, `CVTRA01Y`, and `CVACT01Y` | Direct migration |
| The `transaction.declined` contract at version 3, and the nine descriptive components it adds | `CBTRN02C:L446-L465` writes a 430-byte reject record whose first 350 bytes are the feed record verbatim, so the reject row cannot be built from a decline that carries only an identifier, an account and a reason. The nine components are the ones that record copies, and version 3 exists for this contract alone | Source-derived contract widening |
| `ledger-posting-service` reject ingress: the `transaction.declined` listener and `rejected_transaction` | `POSTTRAN.jcl:L34-L38` allocates the reject Generation Data Group at `LRECL=430` and `CBTRN02C:L446-L465` writes to it inside the same read loop that posts. The listener is what gives that write a runtime entry point, because the authorization service is the sole writer of a decision | Direct migration of the reject path |
| `fraud-detection-service` | No COBOL ancestor. The source scores no risk, checks no velocity, and runs no rules engine. Field widths come from the transaction and cross-reference layouts, and the rule-object shape is borrowed from the authorization decline chain so the two services read alike. Neither borrowing is provenance | Net new |
| `notification-service` | `CBSTM03A.CBL`, `COSTM01.CPY`, `CREASTMT.JCL`, customer and cross-reference fixtures | Partial migration plus additive alerting |
| `account-service` | `COACTVWC`, `COACTUPC`, `CBACT04C:L350-L356`, account and customer layouts | Direct and partial migration |
| `card-service` | `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, card and cross-reference layouts | Direct migration |
| `equivalence-tests` | All nine ASCII fixtures, binary width references, and documented source rules | Verification artifact |
| Transactional outbox tables and relays | `CORPT00C:L517-L519` supplies the ancestor; database-broker atomicity is additive | Additive pattern |
| Processed-event tables | No source duplicate protection | Additive pattern |
| Dead-letter envelope and route | `CSMSG02Y.cpy` supplies reporting fields; broker routing is additive | Mixed source-derived and additive |
| `PanMasker` and masked payload fields | No source masking exists | Additive security boundary |
| `card.card_token` and every route, cursor, authority, and read-model key built on it | No source token exists; `app/cpy/CVACT02Y.cpy` declares no such field and no program derives one | Additive identity, keyed under a deployment secret |
| `AccountStateChanged`, `CustomerContextChanged`, and `CardUpdated` | Account, customer, and card state fields; event publication is additive | Additive state-change contracts |
| Replica provenance columns and the durable dead-letter obligation | No source equivalent; `app/csd/CARDDEMO.CSD:L3-L9` disables recovery on every file | Additive reliability state |
| The one problem document every failing route answers, and the status classes it carries | `CBTRN02C:L707-L711` abends and returns nothing to a caller, because a batch program has none; validation reject reasons and their texts come from `CBTRN02C:L380-L420` and the edit paragraphs of `COACTUPC` and `COCRDUPC` | Mixed source-derived and additive |
| Readiness indicators that report a dependency down instead of letting a failure escape | No source health surface; `CBTRN02C:L714-L727` formats a file status into a job-log line and abends | Additive operability |
| The transaction-local bounded lock wait and the refusal it answers | `COACTUPC:L1445-L1446` and `COCRDUPC:L206` supply the refusal and its text; the bound itself is additive, because a Customer Information Control System read that waited forever never reached those lines | Mixed source-derived and additive |
| `account_credit_snapshot.pending_cycle_credit`, `pending_cycle_debit`, and `pending_expires_at`, with the lock the decision takes over the row | `CBTRN02C:L424-L444` posts each record inside the read loop and `CBTRN02C:L545-L560` rewrites the account before the next record is validated, so `CBTRN02C:L403-L405` always reads accumulators carrying every earlier approval. The columns reproduce that timing where the account service owns the accumulators; the expiry is additive | Source-derived timing, additive representation |
| The ownership comparison inside the decision, and the refusal it answers | `COSGN00C:L232-L236` forks on the role byte and no source program compares a caller against a subject, because a signed-on terminal reached only its own screens | Additive access control |
| `authorization_decision.actor` at its full sixty-four characters | No source field bounds it: `CSUSR01Y.cpy:L18` declares `SEC-USR-ID PIC X(08)` for a signon identity, and this column records the authenticated principal of an HTTP request | Additive audit state |
| `card-platform/pom.xml`, Dockerfiles, Compose, and Kubernetes manifests | No source build or container manifest | Net new platform |
| OpenAPI documents | CICS transaction contracts and target controller routes | New documentation of migrated APIs |
| Documents under `card-platform/docs/` | Agent Action Plan (AAP) Rules 1-3 and source evidence | Rule-mandated additions |
| `presentation/executive-summary.html` | Rule 4 and documented architecture | Rule-mandated addition |
| `.github/workflows/ci.yml` | Build, test, equivalence, schema, and container requirements, plus the secret-scan, static-analysis, supply-chain and image-scan controls a security review found absent | Rule-mandated addition |

### Rule-mandated and platform artifacts

The build, container, deployment, and document deliverables trace to a rule or a plan requirement rather than to a source member. A few appear in the table above as well; the rows here name the authority that requires them.

| Target artifact | Authority or provenance | Classification |
| --- | --- | --- |
| `card-platform/pom.xml` | AAP module graph and dependency inventory | Net new build artifact |
| `card-platform/docker-compose.yml` | AAP demo topology and runtime contracts | Net new orchestration |
| `card-platform/.env.example` | Supported application configuration keys | Net new configuration inventory |
| `card-platform/services/*/Dockerfile` | AAP container requirement | Six net new container definitions |
| `card-platform/deploy/k8s/*.yaml` | AAP standard-cluster deployment requirement | Net new deployment manifests |
| `.github/workflows/ci.yml` | AAP continuous build, equivalence, compatibility, and image checks, and the six security controls a review found absent | Rule-mandated addition |
| `card-platform/.gitleaks.toml` | The secret-scan stage of `ci.yml`; every allowlist entry names a value this repository ships on purpose and why it is safe | Net new pipeline configuration |
| `card-platform/deploy/k8s/kustomization.yaml` | The one place the six mutable image references live, so pinning by digest is one edit | Net new deployment entry point |
| `card-platform/deploy/k8s/README.md` | What a node verifies about each kind of image, and the digest-pinning procedure | Rule 3 addition |
| `card-platform/README.md` | Mono-repo map and delivered platform inventory | Rule 3 addition |
| `card-platform/services/*/README.md` | Per-service purpose, provenance, APIs, events, and local operation | Six Rule 3 additions |
| Root `README.md` modernization section | Existing onboarding entry point plus Rule 3 | Update to an existing document |
| `docs/decision-log.md` | Rule 1 | Rule-mandated addition |
| `docs/traceability-matrix.md` | Rule 1 | Rule-mandated addition |
| `docs/business-rule-flags.md` | User directive and AAP register | Rule-mandated addition |
| `docs/architecture-before-after.md` | Rule 2 and CICS/JCL evidence | Rule-mandated addition |
| `docs/event-flow.md` | Rule 2 and runtime event contracts | Rule-mandated addition |
| `docs/data-model.md` | Rule 2, copybooks, and dataset definitions | Rule-mandated addition |
| `docs/equivalence-results.md` | User deliverable and executed parity suites | Rule-mandated result |
| `docs/onboarding.md` | Rule 3 | Rule-mandated addition |
| `docs/suggested-next-tasks.md` | Rule 3 and business-rule register | Rule-mandated addition |
| `docs/prose-validation.md` | Rule 5 | Rule-mandated addition |
| `presentation/executive-summary.html` | Rule 4 and the documented architecture | Rule-mandated addition |

## Backward: every target path

Rule 1 closes in both directions, so the forward inventories above are matched by a row for every target path this engagement delivers. The list is complete rather than representative: `git ls-files card-platform .github | wc -l` reports 802 tracked paths, and the tables below carry 802 rows. The closure table at the end sums them per group, so that figure can be checked without counting by hand.

That command is the whole basis, and two tracked paths sit outside it on purpose. The root `README.md` is an update to a pre-existing document rather than a delivered path, and it is recorded as such in [rule-mandated and platform artifacts](#rule-mandated-and-platform-artifacts). Everything under `blitzy/` is run evidence — screenshots and screen recordings taken while verifying the platform — not platform code, so it carries no source provenance to state.

The Source provenance column names what the file itself records. Every delivered file that derives from a source member cites that member in its own comments, so the column is read out of the code rather than asserted over it: 598 of the 802 paths name at least one member under `app/`, and the remaining 204 name none, which is what `None cited in the file` means. Where a file cites more than four members the cell names four and counts the rest, because the point of a row is provenance rather than a citation list.

**A citation is not automatically provenance, and two groups of rows say so in the cell itself.** Build, container and deployment artifacts name the members whose behaviour the artifact runs, and the repository carries no build manifest of any kind, so those citations are context. Every path under `services/fraud-detection-service` names members too, and none of them is an ancestor: the source scores no risk, checks no velocity and runs no rules engine, so what those files take is field widths and the rule-object shape the authorization decline chain uses. The plan records both borrowings as borrowings, so every fraud path is classified additive however many members it cites.

Seven labels are used. **Source-derived** is main code, a schema, a migration or a resource that names at least one source member. **Additive** is a file with no ancestor, whether or not it cites one. **Verification, source-derived** and **Verification, additive** are the two test cases, split the same way. **Rule-mandated document** covers the documents Rules 1 to 5 require and **Rule 3 document** the per-module readme Rule 3 requires; a document’s authority is a rule, and the members in its cell are the evidence it cites. **Net new platform** covers build, container, pipeline and deployment artifacts.

### Event contract library — 36 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/libs/event-contracts/pom.xml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/DeadLetterEnvelope.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/DeclineReason.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/EventEnvelope.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy`, `app/cpy/CVTRA06Y.cpy` | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/FraudCleared.java` | `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/FraudFlagged.java` | `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/TransactionAuthorized.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy` and 3 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/TransactionDeclined.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` and 3 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/TransactionPosted.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy` and 4 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/serde/EventContracts.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/serde/EventJsonValidator.java` | None cited in the file | Additive |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/serde/EventSchemas.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/serde/EventWireBounds.java` | None cited in the file | Additive |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/serde/JsonSchemaValidatingDeserializer.java` | `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/serde/JsonSchemaValidatingSerializer.java` | `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/libs/event-contracts/src/main/java/com/carddemo/events/serde/SensitiveEventProperties.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cbl/CSUTLDTC.cbl`, `app/cpy/CSUSR01Y.cpy` and 6 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/account-state-changed-v1.json` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl` and 3 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/card-updated-v1.json` | `app/bms/COCRDSL.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/jcl/XREFFILE.jcl` | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/card-updated-v2.json` | None cited in the file | Additive |
| `card-platform/libs/event-contracts/src/main/resources/schemas/customer-context-changed-v1.json` | `app/cbl/CBSTM03A.CBL`, `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/cpy/CVACT01Y.cpy` and 2 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/dead-letter-v1.json` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/fraud-cleared-v1.json` | `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/fraud-flagged-v1.json` | `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/transaction-authorized-v1.json` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` and 2 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/transaction-authorized-v2.json` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` and 3 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/transaction-declined-v1.json` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` and 2 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/transaction-declined-v2.json` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 1 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/transaction-declined-v3.json` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` and 4 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/transaction-posted-v1.json` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy` and 2 more | Source-derived |
| `card-platform/libs/event-contracts/src/main/resources/schemas/transaction-posted-v2.json` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT01Y.cpy` and 6 more | Source-derived |
| `card-platform/libs/event-contracts/src/test/java/com/carddemo/events/EventRedactionTest.java` | `app/data/ASCII/carddata.txt` | Verification, source-derived |
| `card-platform/libs/event-contracts/src/test/java/com/carddemo/events/EventRoundTripTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/libs/event-contracts/src/test/java/com/carddemo/events/EventSchemaContractTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/libs/event-contracts/src/test/java/com/carddemo/events/SchemaBackwardCompatibilityTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COCRDUPC.cbl` and 12 more | Verification, source-derived |
| `card-platform/libs/event-contracts/src/test/java/com/carddemo/events/serde/EventPublicationGuardTest.java` | `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/carddata.txt` | Verification, source-derived |
| `card-platform/libs/event-contracts/src/test/java/com/carddemo/events/serde/EventSerdeSecurityTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy`, `app/data/ASCII/carddata.txt` | Verification, source-derived |

### COBOL compatibility library — 13 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/libs/cobol-compat/pom.xml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/libs/cobol-compat/src/main/java/com/carddemo/cobol/CobolDateValidator.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cbl/CSUTLDTC.cbl` and 2 more | Source-derived |
| `card-platform/libs/cobol-compat/src/main/java/com/carddemo/cobol/CobolDecimal.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cpy/CVACT01Y.cpy` and 1 more | Source-derived |
| `card-platform/libs/cobol-compat/src/main/java/com/carddemo/cobol/NumvalParser.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CSUTLDPY.cpy` | Source-derived |
| `card-platform/libs/cobol-compat/src/main/java/com/carddemo/cobol/PanMasker.java` | `app/bms/COCRDSL.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/libs/cobol-compat/src/main/java/com/carddemo/cobol/PicClause.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` and 25 more | Source-derived |
| `card-platform/libs/cobol-compat/src/main/java/com/carddemo/cobol/reference/UsPhoneAreaCodes.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Source-derived |
| `card-platform/libs/cobol-compat/src/main/java/com/carddemo/cobol/reference/UsStateCodes.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Source-derived |
| `card-platform/libs/cobol-compat/src/main/java/com/carddemo/cobol/reference/UsStateZipPrefixes.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Source-derived |
| `card-platform/libs/cobol-compat/src/test/java/com/carddemo/cobol/CobolDateValidatorTest.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COTRN02C.cbl`, `app/cbl/CSUTLDTC.cbl`, `app/cpy/CSUTLDPY.cpy` and 1 more | Verification, source-derived |
| `card-platform/libs/cobol-compat/src/test/java/com/carddemo/cobol/CobolDecimalTruncationTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cpy/CVACT01Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/libs/cobol-compat/src/test/java/com/carddemo/cobol/NumvalParserTest.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CSUTLDPY.cpy` | Verification, source-derived |
| `card-platform/libs/cobol-compat/src/test/java/com/carddemo/cobol/PanMaskerTest.java` | `app/bms/COCRDSL.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT02Y.cpy` and 2 more | Verification, source-derived |

### Authorization service — 129 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/services/authorization-service/.dockerignore` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/authorization-service/Dockerfile` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cbl/COTRN02C.cbl` | Net new platform |
| `card-platform/services/authorization-service/README.md` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl` and 15 more | Rule 3 document |
| `card-platform/services/authorization-service/pom.xml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/AuthorizationApplication.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cbl/COTRN02C.cbl`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/api/AuthorizationController.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/COCOM01Y.cpy`, `app/cpy/CVACT03Y.cpy` and 1 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/api/AuthorizationRequest.java` | `app/bms/COADM01.bms`, `app/bms/COMEN01.bms`, `app/bms/COTRN02.bms`, `app/cbl/CBTRN02C.cbl` and 8 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/api/AuthorizationResponse.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` and 1 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/api/GlobalExceptionHandler.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/AuthorizationProperties.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/CrossSiteRequestFilter.java` | `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/JsonReadCeilingConfig.java` | None cited in the file | Additive |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/KafkaConsumerConfig.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/KafkaProducerConfig.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/ObservabilityConfig.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/ReadinessHealthConfig.java` | None cited in the file | Additive |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/RequestBodyCeilingFilter.java` | `app/cpy/CVTRA06Y.cpy`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/RequestJsonStrictnessConfig.java` | None cited in the file | Additive |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/RequestRateCeilingFilter.java` | `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/SafeProducerListener.java` | None cited in the file | Additive |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/SecurityConfig.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/CSUSR01Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/config/StreamNameReport.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/AuthenticatedActor.java` | `app/cbl/COMEN01C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/CSUSR01Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/AuthorizationService.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 3 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/CallerEntitlement.java` | `app/cbl/COSGN00C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/CallerNotEntitledException.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/CycleExposureReservation.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/DeclineRule.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/ReplicaGapLog.java` | None cited in the file | Additive |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/ReplicaSynchronization.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/RequestCaller.java` | `app/cbl/COSGN00C.cbl`, `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/RetentionSweep.java` | None cited in the file | Additive |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/TransactionIdentifierSource.java` | `app/cbl/COBIL00C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/rules/AccountExistsRule.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/jcl/ACCTFILE.jcl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/rules/AccountExpirationRule.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA06Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/rules/CardCrossReferenceRule.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/rules/CreditLimitRule.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/entity/AccountCreditSnapshotEntity.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/data/ASCII/acctdata.txt` and 2 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/entity/AuthorizationDecisionEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSUSR01Y.cpy`, `app/csd/CARDDEMO.CSD`, `app/jcl/TRANFILE.jcl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/entity/CardCrossReferenceEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/COTRN02C.cbl` and 3 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/entity/OutboxEventEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` and 1 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/entity/ProcessedEventEntity.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/entity/ReplicaGapEntity.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/entity/UnresolvedCardAttemptEntity.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/messaging/AccountStateChanged.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/messaging/AccountStateChangedConsumer.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/messaging/CardUpdated.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/messaging/CardUpdatedConsumer.java` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/cardxref.txt` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/messaging/DeadLetterMetadata.java` | `app/cpy/CSMSG02Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/messaging/EventPublisherPort.java` | `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/messaging/KafkaEventPublisher.java` | `app/cbl/CORPT00C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/messaging/KafkaReplicaSynchronization.java` | None cited in the file | Additive |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/outbox/OutboxRelay.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/CORPT00C.cbl`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/outbox/OutboxWriter.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cbl/CORPT00C.cbl` and 4 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/repository/AccountCreditSnapshotRepository.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` and 2 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/repository/AuthorizationDecisionRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/repository/CardCrossReferenceRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CVACT03Y.cpy` and 2 more | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/repository/OutboxEventRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CORPT00C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/repository/ProcessedEventRepository.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/repository/ReplicaGapRepository.java` | `app/cbl/CBSTM03B.CBL` | Source-derived |
| `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/repository/UnresolvedCardAttemptRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/application.yml` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 2 more | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/demo/V900__demo_expiry_extension.sql` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/dailytran.txt` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V10__declared_retention_matches_the_sweep.sql` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V11__subject_request_posture.sql` | `app/jcl/XREFFILE.jcl` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V12__replica_gap.sql` | `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V13__decision_without_event.sql` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V14__unresolved_decline_is_unpublished.sql` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V1__schema.sql` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COTRN02C.cbl` and 9 more | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V2__seed.sql` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/acctdata.txt` and 3 more | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V3__unresolved_card_attempt.sql` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V4__outbox_transaction_key.sql` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V5__authorization_decision.sql` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSUSR01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 3 more | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V6__processed_event_topic_key.sql` | None cited in the file | Additive |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V7__cycle_exposure_reservation.sql` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CSUSR01Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V8__outbox_dead_letter_state.sql` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/db/migration/V9__declared_processing_timestamp.sql` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy` | Source-derived |
| `card-platform/services/authorization-service/src/main/resources/openapi.yaml` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CVACT03Y.cpy` and 6 more | Source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/DiagnosticRedactionTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/TestIdentityPasswords.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/api/AuthorizationControllerTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/COMEN01C.cbl` and 13 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/api/AuthorizationRequestRedactionTest.java` | `app/bms/COTRN02.bms`, `app/cbl/COTRN02C.cbl`, `app/data/ASCII/carddata.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/api/AuthorizationRequestTest.java` | `app/bms/COADM01.bms`, `app/bms/COMEN01.bms`, `app/bms/COTRN02.bms`, `app/cbl/CBTRN02C.cbl` and 4 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/api/AuthorizationResponseRenderingTest.java` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/cardxref.txt`, `app/data/ASCII/dailytran.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/api/AuthorizationResponseTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/api/AuthorizationRouteSecurityIT.java` | `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/api/OpenApiContractTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/api/OpenApiExampleValidationTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/AuthorizationPropertiesTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/BrokerAccessContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/CrossSiteRequestFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/DeadLetterSanitizationTest.java` | `app/cpy/CVACT02Y.cpy` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/PoisonRecordRecoveryIT.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/ReadinessHealthConfigTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/ReplicaAcknowledgementContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/ReplicaMeterRegistrationTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/RequestBodyCeilingFilterTest.java` | `app/csd/CARDDEMO.CSD` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/RequestJsonStrictnessConfigTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/RequestRateCeilingFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/SafeProducerListenerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/SchemaResolutionTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/SecurityConfigTest.java` | `app/cbl/COSGN00C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/config/StreamNameReportTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/AuthenticatedActorTest.java` | `app/cbl/COMEN01C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/CSUSR01Y.cpy` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/AuthorizationChainCompositionTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/AuthorizationServiceTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COMEN01C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy` and 5 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/CallerEntitlementTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cbl/COTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/CycleExposureReservationTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/TransactionIdentifierSourceTest.java` | `app/cpy/CVTRA05Y.cpy` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/rules/AccountExistsRuleTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 5 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/rules/AccountExpirationRuleTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/rules/CardCrossReferenceRuleTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/domain/rules/CreditLimitRuleTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/entity/AuthorizationDecisionEntityTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSUSR01Y.cpy`, `app/data/ASCII/dailytran.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/entity/OutboxRelayStateTest.java` | `app/cbl/CORPT00C.cbl`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/entity/ReplicaObservationTest.java` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/messaging/DeadLetterMetadataEnvelopeTest.java` | `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/messaging/DeadLetterMetadataTest.java` | `app/cpy/CSMSG02Y.cpy` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/messaging/EventSerializationTest.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/CORPT00C.cbl` and 10 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/messaging/KafkaEventPublisherTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/messaging/KafkaReplicaSynchronizationTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/messaging/ReplicaConsumerTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/messaging/ReplicaRefreshToDecisionIT.java` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/messaging/ShippedProducerSerializerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/outbox/OutboxRelayDeadlineTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/outbox/OutboxRelayTest.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CSMSG02Y.cpy` and 8 more | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/outbox/OutboxWriterTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA05Y.cpy`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/repository/CardCrossReferenceRepositoryTest.java` | `app/cbl/COTRN02C.cbl`, `app/jcl/XREFFILE.jcl` | Verification, source-derived |
| `card-platform/services/authorization-service/src/test/java/com/carddemo/authorization/repository/NativeStatementIT.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSUSR01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` and 1 more | Verification, source-derived |

### Ledger posting service — 86 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/services/ledger-posting-service/.dockerignore` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/ledger-posting-service/Dockerfile` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBTRN02C.cbl` | Net new platform |
| `card-platform/services/ledger-posting-service/README.md` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl` and 17 more | Rule 3 document |
| `card-platform/services/ledger-posting-service/pom.xml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBTRN02C.cbl` | Net new platform |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/LedgerApplication.java` | `app/cbl/CBTRN02C.cbl`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/api/ApiProblem.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/api/BalanceQueryController.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/COCOM01Y.cpy`, `app/cpy/CVACT01Y.cpy` and 1 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/api/LedgerApiExceptionHandler.java` | None cited in the file | Additive |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/CrossSiteRequestFilter.java` | `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/KafkaConsumerConfig.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/cardxref.txt` and 1 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/KafkaProducerConfig.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/LedgerProperties.java` | `app/cbl/CBTRN02C.cbl`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/ObservabilityConfig.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/ReadinessHealthConfig.java` | None cited in the file | Additive |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/RequestRateCeilingFilter.java` | `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/SafeProducerListener.java` | None cited in the file | Additive |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/SecurityConfig.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/CSUSR01Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/config/StreamNameReport.java` | `app/cbl/CBTRN02C.cbl`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/domain/AccountBalanceUpdater.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA06Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/domain/CategoryBalanceUpdater.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA01Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 1 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/domain/PostingService.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy`, `app/cpy/CVTRA06Y.cpy`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/domain/RejectRecorder.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA06Y.cpy`, `app/data/ASCII/dailytran.txt`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/domain/RetentionSweep.java` | None cited in the file | Additive |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/entity/AccountBalanceProjectionEntity.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy` and 1 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/entity/OutboxEventEntity.java` | `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/data/ASCII/acctdata.txt` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/entity/ProcessedEventEntity.java` | None cited in the file | Additive |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/entity/RejectedTransactionEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA06Y.cpy`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/entity/TransactionCategoryBalanceEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA01Y.cpy`, `app/jcl/TCATBALF.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/entity/TransactionEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy`, `app/jcl/TRANFILE.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/messaging/AccountStateChanged.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/messaging/AccountStateChangedConsumer.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/messaging/DeadLetterMetadata.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CSMSG02Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/messaging/TransactionAuthorizedConsumer.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/messaging/TransactionDeclinedConsumer.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 1 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/outbox/OutboxRelay.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/outbox/OutboxWriter.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT03Y.cpy` and 1 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/repository/AccountBalanceProjectionRepository.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` and 2 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/repository/OutboxEventRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CORPT00C.cbl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/repository/ProcessedEventRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/repository/RejectedTransactionRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/repository/TransactionCategoryBalanceRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA01Y.cpy`, `app/jcl/TCATBALF.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/java/com/carddemo/ledger/repository/TransactionRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy`, `app/jcl/TRANFILE.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/resources/application.yml` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/resources/db/migration/V1__schema.sql` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA01Y.cpy` and 15 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/resources/db/migration/V2__seed.sql` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA01Y.cpy` and 11 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/resources/db/migration/V3__account_state_replica.sql` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/resources/db/migration/V4__account_state_ownership.sql` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/resources/db/migration/V5__processed_event_topic_key.sql` | None cited in the file | Additive |
| `card-platform/services/ledger-posting-service/src/main/resources/db/migration/V6__cycle_column_locators.sql` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/resources/db/migration/V7__category_balance_ceiling.sql` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA01Y.cpy`, `app/cpy/CVTRA06Y.cpy` | Source-derived |
| `card-platform/services/ledger-posting-service/src/main/resources/openapi.yaml` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/CVACT01Y.cpy` and 2 more | Source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/DiagnosticRedactionTest.java` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/carddata.txt` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/TestIdentityPasswords.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/api/ApiProblemTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/api/BalanceQueryControllerTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/data/ASCII/acctdata.txt` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/api/BalanceQueryRouteSecurityIT.java` | `app/cbl/COSGN00C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/data/ASCII/acctdata.txt` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/api/LedgerApiExceptionHandlerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/api/OpenApiContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/BrokerAccessContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/CrossSiteRequestFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/DeadLetterFailureAttributionTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/DeadLetterRouteSerializerContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/KafkaConsumerConfigTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/LedgerPropertiesTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/ReadinessHealthConfigTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/RequestRateCeilingFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/SafeProducerListenerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/SecurityConfigTest.java` | `app/cbl/COSGN00C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/config/StreamNameReportTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/domain/AccountBalanceUpdaterTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/domain/CategoryBalanceUpdaterTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA01Y.cpy`, `app/cpy/CVTRA04Y.cpy`, `app/data/ASCII/cardxref.txt` and 4 more | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/domain/PostingServiceTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT01Y.cpy` and 8 more | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/domain/RejectRecorderTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 6 more | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/domain/RetentionSweepTest.java` | `app/jcl/POSTTRAN.jcl` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/entity/EntityRenderingRedactionTest.java` | `app/data/ASCII/acctdata.txt`, `app/data/ASCII/dailytran.txt` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/messaging/AccountStateChangedConsumerTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/messaging/ConcurrentDuplicateDeliveryIT.java` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/messaging/DeadLetterMetadataEnvelopeTest.java` | `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/messaging/DeadLetterMetadataTest.java` | `app/cpy/CSMSG02Y.cpy`, `app/jcl/POSTTRAN.jcl` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/messaging/TransactionAuthorizedConsumerIT.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy`, `app/jcl/POSTTRAN.jcl` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/messaging/TransactionAuthorizedConsumerTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 3 more | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/messaging/TransactionDeclinedConsumerTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy`, `app/data/ASCII/cardxref.txt` and 1 more | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/outbox/OutboxRelayDeadlineTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/outbox/OutboxRelayTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/repository/AccountBalanceProjectionRepositoryTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/data/ASCII/acctdata.txt` | Verification, source-derived |
| `card-platform/services/ledger-posting-service/src/test/java/com/carddemo/ledger/repository/TransactionCategoryBalanceRepositoryTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA01Y.cpy`, `app/cpy/CVTRA06Y.cpy`, `app/jcl/TCATBALF.jcl` | Verification, source-derived |

### Fraud detection service — 87 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/services/fraud-detection-service/.dockerignore` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/fraud-detection-service/Dockerfile` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/fraud-detection-service/README.md` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/csd/CARDDEMO.CSD` and 1 more | Rule 3 document |
| `card-platform/services/fraud-detection-service/pom.xml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/FraudApplication.java` | None cited; the source scores no risk and runs no rules engine | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/api/ApiProblem.java` | None cited; the source scores no risk and runs no rules engine | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/api/FraudApiExceptionHandler.java` | None cited; the source scores no risk and runs no rules engine | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/api/FraudAssessmentController.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/CrossSiteRequestFilter.java` | `app/csd/CARDDEMO.CSD` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/FraudProperties.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/KafkaConsumerConfig.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/CVACT03Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/KafkaProducerConfig.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cpy/CVACT03Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/ObservabilityConfig.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/ReadinessHealthConfig.java` | None cited; the source scores no risk and runs no rules engine | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/RequestRateCeilingFilter.java` | `app/jcl/POSTTRAN.jcl` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/SafeProducerListener.java` | None cited in the file | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/SecurityConfig.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/COSGN00C.cbl`, `app/cpy/CSUSR01Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/config/StreamNameReport.java` | None cited; the source scores no risk and runs no rules engine | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/domain/RetentionSweep.java` | None cited; the source scores no risk and runs no rules engine | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/domain/RiskRule.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/domain/RiskScoringService.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/domain/rules/AmountAnomalyRule.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/domain/rules/MerchantCategoryRule.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/domain/rules/VelocityRule.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl`, `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/entity/FraudAssessmentEntity.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/entity/OutboxEventEntity.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cpy/CVACT03Y.cpy`, `app/jcl/XREFFILE.jcl` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/entity/ProcessedEventEntity.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/entity/VelocityWindowEntity.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/messaging/DeadLetterMetadata.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/messaging/EventPublisherPort.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT03Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/messaging/KafkaEventPublisher.java` | None cited; the source scores no risk and runs no rules engine | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/messaging/TransactionAuthorizedConsumer.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/outbox/OutboxRelay.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT03Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/outbox/OutboxWriter.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CORPT00C.cbl` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/repository/FraudAssessmentRepository.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBSTM03B.CBL` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/repository/OutboxEventRepository.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBSTM03B.CBL` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/repository/ProcessedEventRepository.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBSTM03B.CBL` | Additive |
| `card-platform/services/fraud-detection-service/src/main/java/com/carddemo/fraud/repository/VelocityWindowRepository.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBSTM03B.CBL` | Additive |
| `card-platform/services/fraud-detection-service/src/main/resources/application.yml` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/COSGN00C.cbl`, `app/cpy/CVACT03Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/resources/db/migration/V1__schema.sql` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy`, `app/cpy/CVTRA06Y.cpy`, `app/csd/CARDDEMO.CSD` and 1 more | Additive |
| `card-platform/services/fraud-detection-service/src/main/resources/db/migration/V3__velocity_total_headroom.sql` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cpy/CVTRA05Y.cpy` | Additive |
| `card-platform/services/fraud-detection-service/src/main/resources/db/migration/V4__processed_event_topic_key.sql` | None cited; the source scores no risk and runs no rules engine | Additive |
| `card-platform/services/fraud-detection-service/src/main/resources/db/migration/V5__outbox_dead_letter_state.sql` | `app/cbl/CBTRN02C.cbl` | Additive |
| `card-platform/services/fraud-detection-service/src/main/resources/openapi.yaml` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy`, `app/cpy/CVTRA06Y.cpy`, `app/data/ASCII/cardxref.txt` and 1 more | Additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/ConsumeToPublishIT.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/DiagnosticRedactionTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/ScheduledWorkShutdown.java` | None cited in the file | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/TestIdentityPasswords.java` | None cited in the file | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/api/ConfigurationInvariantsIT.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/api/EntitySchemaValidationIT.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/api/FraudApiContractTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/api/FraudApiExceptionHandlerTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/api/FraudAssessmentControllerTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/api/FraudRouteSecurityIT.java` | None cited in the file | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/api/OpenApiContractTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/api/ShippedConfigurationContractTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/CrossSiteRequestFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/DeserializeFailureCountingTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/FraudPropertiesTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/KafkaConsumerConfigTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/ListenerContainerFactoryTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/ObservabilityConfigTest.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl` | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/PublishedTopicOverrideTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/ReadinessHealthConfigTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/RequestRateCeilingFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/SafeProducerListenerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/SecurityConfigTest.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/COSGN00C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt` | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/config/StreamNameReportTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/domain/AmountAnomalyRuleTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/domain/MerchantCategoryRuleTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/domain/RetentionSweepTest.java` | `app/jcl/POSTTRAN.jcl` | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/domain/RiskRuleTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/domain/RiskScoringServiceNoShortCircuitTest.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/cbl/CBTRN02C.cbl` | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/domain/RiskScoringServiceTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/domain/VelocityRuleTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/domain/VelocityWindowConcurrencyIT.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/entity/EntityRenderingRedactionTest.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/data/ASCII/acctdata.txt`, `app/data/ASCII/dailytran.txt` | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/entity/FraudAssessmentEntityTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/messaging/CardDataExposureTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/messaging/DeadLetterMetadataEnvelopeTest.java` | Field widths and rule-object shape only, which the plan records as borrowing rather than provenance: `app/data/ASCII/cardxref.txt` | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/messaging/DeadLetterMetadataTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/messaging/DeadLetterRoutingTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/messaging/FraudEventWireFormTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/messaging/TransactionAuthorizedConsumerTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/outbox/OutboxAtomicityIT.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/outbox/OutboxRelayTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |
| `card-platform/services/fraud-detection-service/src/test/java/com/carddemo/fraud/outbox/OutboxWriterTest.java` | None cited; the source scores no risk and runs no rules engine | Verification, additive |

### Notification service — 88 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/services/notification-service/.dockerignore` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/notification-service/Dockerfile` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/jcl/CREASTMT.JCL` | Net new platform |
| `card-platform/services/notification-service/README.md` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CSMSG02Y.cpy` and 2 more | Rule 3 document |
| `card-platform/services/notification-service/pom.xml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/NotificationApplication.java` | `app/cbl/CBSTM03A.CBL`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/api/ApiErrorResponse.java` | None cited in the file | Additive |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/api/NotificationApiExceptionHandler.java` | None cited in the file | Additive |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/api/NotificationHistoryController.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/api/NotificationHistoryResponse.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT02Y.cpy` and 1 more | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/api/NotificationTransactionItem.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/config/CrossSiteRequestFilter.java` | `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/config/KafkaConsumerConfig.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/config/NotificationProperties.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVCUS01Y.cpy`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/config/ObservabilityConfig.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/config/ReadinessHealthConfig.java` | None cited in the file | Additive |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/config/RequestRateCeilingFilter.java` | `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/config/SafeProducerListener.java` | None cited in the file | Additive |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/config/SecurityConfig.java` | `app/cbl/COSGN00C.cbl`, `app/cpy/CSUSR01Y.cpy` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/config/StreamNameReport.java` | `app/cbl/CBSTM03A.CBL`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/domain/CardholderContextReader.java` | `app/cbl/CBSTM03A.CBL`, `app/data/ASCII/custdata.txt` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/domain/HtmlRenderer.java` | `app/cbl/CBSTM03A.CBL`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/domain/NotificationRenderer.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/data/ASCII/dailytran.txt` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/domain/NotificationService.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/domain/PlainTextRenderer.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/domain/RetentionSweep.java` | None cited in the file | Additive |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/entity/CardholderContextEntity.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVCUS01Y.cpy` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/entity/NotificationLogEntity.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/entity/ProcessedEventEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/entity/StatementTransactionEntity.java` | `app/bms/COCRDSL.bms`, `app/cpy/COSTM01.CPY`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/messaging/CustomerContextChangedConsumer.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/messaging/DeadLetterMetadata.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` and 4 more | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/messaging/FraudFlaggedConsumer.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/messaging/TransactionAuthorizedConsumer.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/messaging/TransactionPostedConsumer.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/cpy/CSMSG02Y.cpy`, `app/data/ASCII/custdata.txt` and 1 more | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/repository/CardholderContextRepository.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`, `app/cpy/CVCUS01Y.cpy` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/repository/NotificationLogRepository.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/repository/ProcessedEventRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/notification-service/src/main/java/com/carddemo/notification/repository/StatementTransactionRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/jcl/CREASTMT.JCL` | Source-derived |
| `card-platform/services/notification-service/src/main/resources/application.yml` | `app/cbl/COSGN00C.cbl` | Source-derived |
| `card-platform/services/notification-service/src/main/resources/db/migration/V1__schema.sql` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT03Y.cpy` and 5 more | Source-derived |
| `card-platform/services/notification-service/src/main/resources/db/migration/V2__seed.sql` | `app/cbl/CBSTM03A.CBL`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVCUS01Y.cpy`, `app/data/ASCII/cardxref.txt` and 1 more | Source-derived |
| `card-platform/services/notification-service/src/main/resources/db/migration/V3__processed_event_topic_key.sql` | None cited in the file | Additive |
| `card-platform/services/notification-service/src/main/resources/db/migration/V4__marker_retention_margin.sql` | None cited in the file | Additive |
| `card-platform/services/notification-service/src/main/resources/db/migration/V5__rendered_not_delivered.sql` | None cited in the file | Additive |
| `card-platform/services/notification-service/src/main/resources/db/migration/V6__subject_request_posture.sql` | None cited in the file | Additive |
| `card-platform/services/notification-service/src/main/resources/openapi.yaml` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/COSTM01.CPY`, `app/data/ASCII/dailytran.txt` | Source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/NotificationApplicationTest.java` | `app/cbl/CBSTM03A.CBL`, `app/jcl/CREASTMT.JCL` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/TestIdentityPasswords.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/api/ApiErrorResponseTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/api/HistoryContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/api/NotificationHistoryControllerTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT02Y.cpy`, `app/jcl/CREASTMT.JCL` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/api/NotificationHistoryResponseTest.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT02Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/api/NotificationOpenApiContractTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT02Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/api/NotificationOpenApiExampleValidationTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/api/NotificationRouteSecurityIT.java` | `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/api/NotificationTransactionItemTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT02Y.cpy` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/BrokerUnreachableStartupIT.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/CrossSiteRequestFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/DeadLetterRoutingIT.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/KafkaConsumerConfigTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/NotificationPropertiesTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/ObservabilityConfigTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/ReadinessHealthConfigTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/RequestRateCeilingFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/SafeProducerListenerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/SecurityConfigTest.java` | `app/cbl/COSGN00C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/config/StreamNameReportTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/domain/HtmlRendererTest.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBSTM03A.CBL`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/domain/NotificationRendererTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CUSTREC.cpy`, `app/cpy/CVACT01Y.cpy` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/domain/NotificationServiceTest.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CUSTREC.cpy` and 6 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/domain/PlainTextRendererTest.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT02Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/domain/RetentionSweepTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/domain/StatementRowCapTest.java` | `app/cbl/CBSTM03A.CBL` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/entity/NotificationEntityPersistenceTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/csd/CARDDEMO.CSD`, `app/data/ASCII/carddata.txt` and 2 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/entity/NotificationLogEntityTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT02Y.cpy`, `app/jcl/CREASTMT.JCL` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/entity/ProcessedEventEntityTest.java` | `app/cbl/CBTRN02C.cbl`, `app/csd/CARDDEMO.CSD` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/entity/StatementTransactionEntityTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/messaging/ConcurrentDuplicateDeliveryIT.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/messaging/DeadLetterMetadataTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cpy/CSMSG02Y.cpy` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/messaging/DuplicateDeliveryIT.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/cpy/CSMSG02Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/messaging/FraudFlaggedConsumerTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/cpy/CSMSG02Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/messaging/NotificationConsumerTest.java` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/dailytran.txt`, `app/jcl/CREASTMT.JCL` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/messaging/TransactionAuthorizedConsumerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/messaging/TransactionPostedConsumerTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/CVACT03Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/repository/NotificationLogRepositoryTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`, `app/cpy/COSTM01.CPY` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/repository/NotificationRepositoryTestSupport.java` | `app/cbl/CBSTM03B.CBL` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/repository/ProcessedEventRepositoryTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/notification-service/src/test/java/com/carddemo/notification/repository/StatementTransactionRepositoryTest.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cpy/COSTM01.CPY` and 1 more | Verification, source-derived |

### Account service — 164 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/services/account-service/.dockerignore` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/account-service/Dockerfile` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBACT04C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` | Net new platform |
| `card-platform/services/account-service/README.md` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl` and 18 more | Rule 3 document |
| `card-platform/services/account-service/pom.xml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/AccountApplication.java` | None cited in the file | Additive |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/AccountApiExceptionHandler.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/AccountController.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/AccountRecordMapper.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/ApiProblem.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/BillingCycleController.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/CustomerController.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy` and 1 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/dto/AccountDataRequest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CSUTLDPY.cpy`, `app/data/ASCII/acctdata.txt` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/dto/AccountReadResponse.java` | `app/cbl/COACTVWC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/dto/AccountUpdateRequest.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/dto/AccountUpdateResponse.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/dto/AccountView.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT01Y.cpy` and 1 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/dto/CustomerDataRequest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/cpy/CSUTLDPY.cpy`, `app/cpy/CVCUS01Y.cpy` and 1 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/dto/CustomerReadResponse.java` | `app/cbl/COACTVWC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/dto/CustomerView.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVCUS01Y.cpy`, `app/data/ASCII/custdata.txt` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/api/dto/CycleCloseResponse.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/data/ASCII/acctdata.txt` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/AccountProperties.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` and 2 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/CrossSiteRequestFilter.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/JsonReadCeilingConfig.java` | None cited in the file | Additive |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/KafkaConsumerConfig.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/KafkaProducerConfig.java` | `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/ObservabilityConfig.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CVCUS01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/ReadinessHealthConfig.java` | None cited in the file | Additive |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/RequestBodyCeilingFilter.java` | None cited in the file | Additive |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/RequestJsonStrictnessConfig.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/RequestRateCeilingFilter.java` | `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/SafeProducerListener.java` | None cited in the file | Additive |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/SecurityConfig.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` and 2 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/config/StreamNameReport.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/AccountUpdateService.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVCUS01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/BillingCycleService.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/ConcurrentChangeDetector.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/PostedTransactionService.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT01Y.cpy` and 1 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/RetentionSweep.java` | None cited in the file | Additive |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/AccountIdValidator.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVCRD01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/AlphabeticOptionalValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/AlphabeticRequiredValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/AlphanumericOptionalValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/AlphanumericRequiredValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/CalendarDateValidator.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSUTLDPY.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/CreditScoreRangeValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/DateOfBirthValidator.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSUTLDPY.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/DomainEdit.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/DomainEditValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/EditResult.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/MandatoryFieldValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/NumericRequiredValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/SignedDecimalValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/UsPhoneNumberValidator.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/UsSocialSecurityNumberValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/UsStateCodeValidator.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/UsStateZipPrefixValidator.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/domain/validation/YesNoFlagValidator.java` | `app/cbl/COACTUPC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/entity/AccountCustomerLinkEntity.java` | `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT03Y.cpy`, `app/jcl/XREFFILE.jcl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/entity/AccountEntity.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy` and 2 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/entity/CustomerEntity.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/cpy/CVCUS01Y.cpy` and 2 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/entity/DisclosureGroupEntity.java` | `app/cbl/CBACT04C.cbl`, `app/cpy/CVTRA02Y.cpy`, `app/data/ASCII/discgrp.txt`, `app/data/ASCII/trancatg.txt` and 1 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/entity/OutboxEventEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT01Y.cpy` and 3 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/entity/ProcessedEventEntity.java` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/messaging/AccountStateChanged.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/messaging/CustomerContextChanged.java` | `app/cbl/CBSTM03A.CBL`, `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/messaging/DeadLetterMetadata.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSMSG02Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/messaging/EventPublisherPort.java` | `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/messaging/TransactionPostedConsumer.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTVWC.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/outbox/OutboxRelay.java` | `app/cbl/CORPT00C.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/outbox/OutboxWriter.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/repository/AccountCustomerLinkRepository.java` | None cited in the file | Additive |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/repository/AccountRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT01Y.cpy` and 1 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/repository/CustomerRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVCUS01Y.cpy` and 1 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/repository/DisclosureGroupRepository.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03B.CBL`, `app/cpy/CVTRA02Y.cpy`, `app/data/ASCII/discgrp.txt` and 1 more | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/repository/OutboxEventRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CORPT00C.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/java/com/carddemo/account/repository/ProcessedEventRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/account-service/src/main/resources/application.yml` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/data/ASCII/acctdata.txt` | Source-derived |
| `card-platform/services/account-service/src/main/resources/db/demo/V900__demo_expiry_extension.sql` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt` | Source-derived |
| `card-platform/services/account-service/src/main/resources/db/migration/V1__schema.sql` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` and 13 more | Source-derived |
| `card-platform/services/account-service/src/main/resources/db/migration/V2__seed.sql` | `app/cbl/CBACT04C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy` and 7 more | Source-derived |
| `card-platform/services/account-service/src/main/resources/db/migration/V3__reference_data.sql` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/resources/db/migration/V4__card_cross_reference_replica.sql` | `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/cardxref.txt` | Source-derived |
| `card-platform/services/account-service/src/main/resources/db/migration/V5__outbox_dead_letter_state.sql` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/resources/db/migration/V6__processed_event_topic_key.sql` | `app/cbl/CBTRN02C.cbl` | Source-derived |
| `card-platform/services/account-service/src/main/resources/db/migration/V7__account_customer_link.sql` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/cardxref.txt.` and 1 more | Source-derived |
| `card-platform/services/account-service/src/main/resources/db/migration/V8__subject_request_posture.sql` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVCUS01Y.cpy` | Source-derived |
| `card-platform/services/account-service/src/main/resources/openapi.yaml` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` and 10 more | Source-derived |
| `card-platform/services/account-service/src/main/resources/schemas/account-state-changed-v1.json` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl` and 3 more | Source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/DiagnosticRedactionTest.java` | `app/cpy/CVCUS01Y.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/TestIdentityPasswords.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/AccountApiExceptionHandlerTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/AccountControllerIT.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` and 7 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/AccountControllerOutcomeAndMergeTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT01Y.cpy` and 3 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/AccountControllerTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT01Y.cpy` and 3 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/AccountRouteWiringTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy`, `app/data/ASCII/acctdata.txt` and 1 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/BillingCycleControllerTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/data/ASCII/acctdata.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/CustomerControllerTest.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVCUS01Y.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/OpenApiContractTest.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/OpenApiExampleValidationTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVCUS01Y.cpy`, `app/data/ASCII/custdata.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/dto/AccountMoneyWireFormTest.java` | `app/cbl/CBACT04C.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/dto/CustomerDataRequestTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/data/ASCII/custdata.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/api/dto/DtoValidationWiringTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/data/ASCII/custdata.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/AccountPropertiesTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/CrossSiteRequestFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/KafkaConsumerConfigTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT02Y.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/KafkaEventPublisherTest.java` | `app/cbl/CORPT00C.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/ObservabilityConfigTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/ReadinessHealthConfigTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/RequestBodyCeilingFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/RequestJsonStrictnessConfigTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/RequestRateCeilingFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/SafeProducerListenerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/SecurityConfigTest.java` | `app/cbl/COSGN00C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/config/StreamNameReportTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/AccountUpdateRelationshipTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/AccountUpdateServiceTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/cpy/CSUTLDPY.cpy`, `app/cpy/CVACT01Y.cpy` and 6 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/BillingCycleServiceTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/ConcurrentChangeDetectorTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy`, `app/data/ASCII/custdata.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/PostedTransactionServiceTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/RetentionSweepTest.java` | `app/jcl/POSTTRAN.jcl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/TransactionalObservabilityTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/AccountIdValidatorTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVCRD01Y.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/AlphabeticOptionalValidatorTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/AlphabeticRequiredValidatorTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/AlphanumericOptionalValidatorTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/AlphanumericRequiredValidatorTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/CalendarDateValidatorTest.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COTRN02C.cbl`, `app/cbl/CSUTLDTC.cbl`, `app/cpy/CSUTLDPY.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/CreditScoreRangeValidatorTest.java` | `app/cbl/COACTUPC.cbl`, `app/data/ASCII/custdata.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/DateOfBirthValidatorTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSUTLDPY.cpy`, `app/cpy/CSUTLDWY.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/EditResultTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/MandatoryFieldValidatorTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/NumericRequiredValidatorTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/SignedDecimalValidatorTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/UnreachableEditClassificationTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/UsPhoneNumberValidatorTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/UsSocialSecurityNumberValidatorTest.java` | `app/cbl/COACTUPC.cbl`, `app/data/ASCII/custdata.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/UsStateCodeValidatorTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/UsStateZipPrefixValidatorTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/data/ASCII/custdata.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/domain/validation/YesNoFlagValidatorTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/messaging/AccountStateChangedGateTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/messaging/AccountStateChangedPublishPathTest.java` | `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVCUS01Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/messaging/DeadLetterMetadataEnvelopeTest.java` | `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/messaging/DeadLetterMetadataTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSMSG02Y.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/messaging/TransactionPostedConsumerTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/outbox/DeadLetterMetadataTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CSMSG02Y.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/outbox/OutboxAtomicityTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/cpy/CVACT01Y.cpy` and 3 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/outbox/OutboxRelayDeadlineTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/outbox/OutboxRelayTerminalPathTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/outbox/OutboxRelayTest.java` | `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/data/ASCII/acctdata.txt`, `app/jcl/ACCTFILE.jcl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/outbox/OutboxWriterRowAndPayloadTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT01Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/outbox/OutboxWriterTest.java` | `app/cbl/COACTUPC.cbl`, `app/csd/CARDDEMO.CSD` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/AbstractAccountPostgresTest.java` | `app/cbl/CBTRN02C.cbl`, `app/jcl/ACCTFILE.jcl`, `app/jcl/CUSTFILE.jcl`, `app/jcl/DISCGRP.jcl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/AccountRepositoryTest.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy`, `app/data/ASCII/acctdata.txt` and 1 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/AsciiFixtureReader.java` | `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy`, `app/cpy/CVTRA02Y.cpy`, `app/data/ASCII/acctdata.txt` and 2 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/CustomerRepositoryTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CVCUS01Y.cpy`, `app/data/ASCII/custdata.txt`, `app/jcl/CUSTFILE.jcl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/DisclosureGroupRepositoryTest.java` | `app/cbl/CBSTM03B.CBL`, `app/cpy/CVTRA02Y.cpy`, `app/data/ASCII/discgrp.txt`, `app/jcl/DISCGRP.jcl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/OutboxEventRepositoryTest.java` | `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT01Y.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/ProcessedEventRepositoryTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/ReferenceDataBridgeTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/ReferenceDataMigrationTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/cpy/CVCUS01Y.cpy`, `app/data/ASCII/custdata.txt` | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/SchemaColumnTypeTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy`, `app/cpy/CVTRA02Y.cpy` and 3 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/SchemaConstraintAbsenceTest.java` | `app/cbl/COACTUPC.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy` and 7 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/SchemaMigrationTest.java` | `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy`, `app/cpy/CVTRA02Y.cpy`, `app/data/ASCII/acctdata.txt` and 6 more | Verification, source-derived |
| `card-platform/services/account-service/src/test/java/com/carddemo/account/repository/ZonedDecimalFixtureDecodingTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVCUS01Y.cpy`, `app/cpy/CVTRA02Y.cpy` and 3 more | Verification, source-derived |

### Card service — 100 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/services/card-service/.dockerignore` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/card-service/Dockerfile` | Context only, because no source build, container or deployment manifest exists: `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` | Net new platform |
| `card-platform/services/card-service/README.md` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl` and 12 more | Rule 3 document |
| `card-platform/services/card-service/pom.xml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/CardApplication.java` | `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/api/CardApiExceptionHandler.java` | `app/cbl/COCRDUPC.cbl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/api/CardController.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/api/dto/ApiErrorResponse.java` | `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/api/dto/CardDetailResponse.java` | `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/api/dto/CardListResponse.java` | `app/cbl/COCRDLIC.cbl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/api/dto/CardSummary.java` | `app/cbl/COCRDLIC.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/api/dto/CardUpdateRequest.java` | `app/bms/COCRDUP.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/api/dto/CardUpdateResponse.java` | `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/api/dto/CardValidationMessages.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/CardProperties.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/CrossSiteRequestFilter.java` | `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/JsonReadCeilingConfig.java` | None cited in the file | Additive |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/KafkaProducerConfig.java` | `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/ObservabilityConfig.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/ReadinessHealthConfig.java` | None cited in the file | Additive |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/RequestBodyCeilingFilter.java` | None cited in the file | Additive |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/RequestJsonStrictnessConfig.java` | `app/bms/COCRDUP.bms`, `app/cbl/COCRDUPC.cbl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/RequestRateCeilingFilter.java` | `app/jcl/POSTTRAN.jcl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/SafeProducerListener.java` | None cited in the file | Additive |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/SecurityConfig.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/CSUSR01Y.cpy` and 1 more | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/config/StreamNameReport.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/domain/CardFilterRejectedException.java` | `app/cbl/COCRDLIC.cbl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/domain/CardQueryService.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` and 1 more | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/domain/CardTokenReconciler.java` | None cited in the file | Additive |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/domain/CardUpdateService.java` | `app/bms/COCRDUP.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/csd/CARDDEMO.CSD` and 2 more | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/domain/RetentionSweep.java` | None cited in the file | Additive |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/entity/CardCrossReferenceEntity.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT03Y.cpy` and 3 more | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/entity/CardEntity.java` | `app/bms/COCRDUP.bms`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl` and 7 more | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/entity/OutboxEventEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/entity/ProcessedEventEntity.java` | `app/cbl/CBTRN02C.cbl`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/messaging/CardUpdated.java` | `app/bms/COCRDSL.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/messaging/DeadLetterMetadata.java` | `app/cbl/COCRDUPC.cbl`, `app/cpy/CSMSG02Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/messaging/EventPublisherPort.java` | `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT02Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/messaging/KafkaEventPublisher.java` | `app/cbl/CORPT00C.cbl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/outbox/OutboxRelay.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CSMSG02Y.cpy` and 3 more | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/outbox/OutboxWriter.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/CORPT00C.cbl` and 3 more | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/repository/CardCrossReferenceRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` and 4 more | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/repository/CardRepository.java` | `app/bms/COCRDSL.bms`, `app/cbl/CBSTM03B.CBL`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl` and 5 more | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/repository/OutboxEventRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CORPT00C.cbl` | Source-derived |
| `card-platform/services/card-service/src/main/java/com/carddemo/card/repository/ProcessedEventRepository.java` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/csd/CARDDEMO.CSD` | Source-derived |
| `card-platform/services/card-service/src/main/resources/application.yml` | `app/cbl/COCRDUPC.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/CVACT03Y.cpy` | Source-derived |
| `card-platform/services/card-service/src/main/resources/db/migration/V1__schema.sql` | `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CVACT02Y.cpy` and 7 more | Source-derived |
| `card-platform/services/card-service/src/main/resources/db/migration/V2__seed.sql` | `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt` and 2 more | Source-derived |
| `card-platform/services/card-service/src/main/resources/db/migration/V3__processed_event_topic_key.sql` | None cited in the file | Additive |
| `card-platform/services/card-service/src/main/resources/db/migration/V4__subject_request_posture.sql` | None cited in the file | Additive |
| `card-platform/services/card-service/src/main/resources/openapi.yaml` | `app/bms/COCRDUP.bms`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl` and 8 more | Source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/ScheduledWorkShutdown.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/TestIdentityPasswords.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/CardApiExceptionHandlerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/CardControllerIT.java` | `app/bms/COCRDSL.bms`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` and 9 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/CardControllerTest.java` | `app/bms/COCRDSL.bms`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` and 5 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/CardRouteSecurityIT.java` | `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/CardRouteWiringTest.java` | `app/cpy/CVACT02Y.cpy` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/OpenApiContractTest.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/OpenApiExampleValidationTest.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/dto/ApiErrorResponseTest.java` | `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVCRD01Y.cpy` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/dto/CardDetailResponseTest.java` | `app/bms/COCRDSL.bms`, `app/bms/COCRDUP.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/dto/CardDtoRenderingTest.java` | `app/data/ASCII/carddata.txt` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/dto/CardListResponseTest.java` | `app/cbl/COCRDLIC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVCRD01Y.cpy`, `app/data/ASCII/carddata.txt` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/dto/CardSummaryTest.java` | `app/bms/COCRDSL.bms`, `app/bms/COCRDUP.bms`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDUPC.cbl` and 2 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/dto/CardUpdateRequestTest.java` | `app/bms/COCRDSL.bms`, `app/bms/COCRDUP.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` and 1 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/dto/CardUpdateResponseTest.java` | `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVCRD01Y.cpy` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/api/dto/CardValidationMessagesTest.java` | `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVCRD01Y.cpy` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/CardPropertiesTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/CrossSiteRequestFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/ObservabilityConfigTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/ReadinessHealthConfigTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/RequestBodyCeilingFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/RequestRateCeilingFilterTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/SafeProducerListenerTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/ScheduledWorkStandDownTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/SecurityConfigTest.java` | `app/cbl/COSGN00C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/config/StreamNameReportTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/domain/CardChangeDetectionTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/domain/CardQueryServiceTest.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/domain/CardUpdateServiceTest.java` | `app/bms/COCRDSL.bms`, `app/bms/COCRDUP.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/domain/RetentionSweepTest.java` | `app/jcl/CARDFILE.jcl` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/entity/CardEntityMappingTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` and 6 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/entity/CardholderDataExposureTest.java` | `app/bms/COCRDUP.bms`, `app/cpy/CVACT02Y.cpy`, `app/data/ASCII/carddata.txt` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/messaging/CardEventPublicationTest.java` | `app/bms/COCRDSL.bms`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CSMSG02Y.cpy`, `app/cpy/CVACT02Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/messaging/CardUpdatedPublishPathTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/carddata.txt` and 1 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/messaging/CardUpdatedTest.java` | `app/cbl/COCRDUPC.cbl`, `app/data/ASCII/carddata.txt` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/messaging/DeadLetterMetadataEnvelopeTest.java` | `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/messaging/DeadLetterMetadataTest.java` | `app/cbl/COCRDUPC.cbl`, `app/cpy/CSMSG02Y.cpy` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/messaging/KafkaEventPublisherTest.java` | `app/cpy/CVACT03Y.cpy` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/messaging/QuietWindow.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/messaging/QuietWindowTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/outbox/OutboxRelayClaimTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/outbox/OutboxRelayDeadLetterTest.java` | `app/cbl/COCRDUPC.cbl` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/outbox/OutboxRelayDeadlineTest.java` | `app/cbl/CORPT00C.cbl` | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/outbox/OutboxRelayTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cpy/CSMSG02Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/outbox/OutboxWriterDataMinimizationTest.java` | None cited in the file | Verification, additive |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/outbox/OutboxWriterTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/CORPT00C.cbl` and 3 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/repository/CardCrossReferenceRepositoryIT.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/CORPT00C.cbl`, `app/cbl/COTRN02C.cbl` and 5 more | Verification, source-derived |
| `card-platform/services/card-service/src/test/java/com/carddemo/card/repository/CardRepositoryIT.java` | `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy` and 3 more | Verification, source-derived |

### Equivalence test module — 63 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/equivalence-tests/pom.xml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/AccountProjectionCoverageTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ApiSurfaceSecurityContractTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl` and 7 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/AuthorizationDecisionEquivalenceTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cbl/COSGN00C.cbl`, `app/cbl/COTRN02C.cbl` and 7 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/AuthorizationDemoDataTest.java` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/dailytran.txt` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/BillPaymentEquivalenceTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cpy/CSDAT01Y.cpy`, `app/cpy/CVACT01Y.cpy` and 3 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/BrokerTopicProvisioningContractTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/CardDemoFixtureLoader.java` | `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVCUS01Y.cpy` and 23 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/CardDemoFixtureLoaderTest.java` | `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/cardxref.txt`, `app/jcl/XREFFILE.jcl` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/CardSeedEquivalenceTest.java` | `app/cpy/CVACT02Y.cpy`, `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt`, `app/jcl/CARDFILE.jcl` and 1 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/CardTokenKeyContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/CardholderExampleContractTest.java` | `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/carddata.txt`, `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/CobolSourceEvidence.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/CommonMetricTagContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ComposeEnvironmentIsolationContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ConfigurationInventoryContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ContinuousIntegrationWorkflowContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/CopybookRecordParser.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy` and 21 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/CopybookRecordParserTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVCUS01Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/DecimalTruncationEquivalenceTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cpy/CVACT01Y.cpy` and 6 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/DemoBootstrapContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/DocumentationContractTest.java` | `app/cpy/CVACT01Y.cpy`, `app/csd/CARDDEMO.CSD` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/EntitySchemaMappingContractTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COTRN02C.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/data/ASCII/trancatg.txt` and 1 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/EquivalenceSuiteExecutionConfigurationTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ExpectedOutcomes.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ExpectedOutputBindingContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/FixtureCoverageEquivalenceTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CSUSR01Y.cpy`, `app/cpy/CVTRA06Y.cpy`, `app/data/ASCII/acctdata.txt` and 9 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/FraudAssessmentPersistenceTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/IdentifierFidelityEquivalenceTest.java` | `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVCUS01Y.cpy` and 5 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/InterestCalculationEquivalenceTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA01Y.cpy` and 6 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/KafkaDeliveryGuaranteeContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/KubernetesDeploymentContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/LogHygieneContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/MetricDocumentationContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/PasswordEncodingContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/PostingEquivalenceTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy` and 8 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/PresentationAndProseContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ProducerFailureLoggingContractTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ProjectionBootstrapContractTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDLIC.cbl` and 6 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/RepositorySurfaceTest.java` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/discgrp.txt` and 1 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/RequestSurfaceControlContractTest.java` | `app/cbl/CBTRN02C.cbl` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/RetentionSweepContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/RuleThreeDocumentationContractTest.java` | `app/cbl/COACTUPC.cbl` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/SchemaValidationTest.java` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA05Y.cpy` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ServiceSchemaResourceContractTest.java` | `app/cpy/CUSTREC.cpy`, `app/cpy/CVCUS01Y.cpy` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/SupplyChainContractTest.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/TestIdentityPasswords.java` | None cited in the file | Verification, additive |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ThreeConsumerAuthorizationFlowIT.java` | `app/data/ASCII/cardxref.txt` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/java/com/carddemo/equivalence/ValidationEquivalenceTest.java` | `app/cbl/COACTUPC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVCUS01Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/acctdata-final-account-state-model-b.csv` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA01Y.cpy` and 2 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/bill-payment-results.csv` | `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cpy/CSDAT01Y.cpy`, `app/cpy/CVACT01Y.cpy` and 3 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/cardxref-account-resolution.csv` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/cardxref.txt` and 2 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/dailytran-authorization-decisions-model-a.csv` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 3 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/dailytran-category-balances-model-b.csv` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA01Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 5 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/dailytran-decimal-truncation-model-b.csv` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cpy/CVTRA01Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/dailytran-posting-results-model-b.csv` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy` and 3 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/dailytran-reject-records-model-b.csv` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVTRA06Y.cpy`, `app/data/ASCII/cardxref.txt` and 2 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/discgrp-interest-rates.csv` | `app/cbl/CBACT04C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA02Y.cpy`, `app/data/ASCII/acctdata.txt` and 3 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/fixture-coverage.csv` | `app/cbl/CBTRN02C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy` and 16 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/posting-summary.csv` | `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt`, `app/data/ASCII/dailytran.txt`, `app/data/ASCII/tcatbal.txt` | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/synthetic-boundary-cases.csv` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cpy/CVACT01Y.cpy` and 6 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/tcatbal-interest-accrual.csv` | `app/cbl/CBACT04C.cbl`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA01Y.cpy`, `app/cpy/CVTRA02Y.cpy` and 4 more | Verification, source-derived |
| `card-platform/equivalence-tests/src/test/resources/expected/validation-messages.csv` | `app/cbl/COACTUPC.cbl`, `app/cbl/COCRDUPC.cbl`, `app/cpy/CVACT02Y.cpy`, `app/cpy/CVACT03Y.cpy` and 5 more | Verification, source-derived |

### Documents — 10 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/docs/architecture-before-after.md` | `app/cbl/CBSTM03B.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COMEN01C.cbl` and 10 more | Rule-mandated document |
| `card-platform/docs/business-rule-flags.md` | `app/bms/COCRDUP.bms`, `app/cbl/CBACT01C.cbl`, `app/cbl/CBACT02C.cbl`, `app/cbl/CBACT03C.cbl` and 35 more | Rule-mandated document |
| `card-platform/docs/data-model.md` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COBIL00C.cbl`, `app/cbl/COCRDUPC.cbl` and 19 more | Rule-mandated document |
| `card-platform/docs/decision-log.md` | `app/bms/COCRDUP.bms`, `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl` and 25 more | Rule-mandated document |
| `card-platform/docs/equivalence-results.md` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COBIL00C.cbl` and 22 more | Rule-mandated document |
| `card-platform/docs/event-flow.md` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/CORPT00C.cbl` and 3 more | Rule-mandated document |
| `card-platform/docs/onboarding.md` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CSLKPCDY.cpy`, `app/cpy/CVACT01Y.cpy` and 3 more | Rule-mandated document |
| `card-platform/docs/prose-validation.md` | None cited; Rule 4 supplies the visual specification | Rule-mandated document |
| `card-platform/docs/suggested-next-tasks.md` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBSTM03A.CBL`, `app/cbl/CBTRN02C.cbl`, `app/cbl/COACTUPC.cbl` and 16 more | Rule-mandated document |
| `card-platform/docs/traceability-matrix.md` | `app/bms/COADM01.bms`, `app/bms/COCRDSL.bms`, `app/bms/COCRDUP.bms`, `app/bms/COMEN01.bms` and 77 more | Rule-mandated document |

### Presentation — 1 path

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/presentation/executive-summary.html` | None cited; Rule 4 supplies the visual specification | Rule-mandated document |

### Deployment artifacts — 14 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/deploy/k8s/00-namespace.yaml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/deploy/k8s/10-kafka.yaml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBTRN02C.cbl` | Net new platform |
| `card-platform/deploy/k8s/20-postgres.yaml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/deploy/k8s/30-configmap.yaml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/data/ASCII/acctdata.txt` | Net new platform |
| `card-platform/deploy/k8s/31-secret.example.yaml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/COSGN00C.cbl`, `app/csd/CARDDEMO.CSD` | Net new platform |
| `card-platform/deploy/k8s/40-authorization-service.yaml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBTRN02C.cbl`, `app/csd/CARDDEMO.CSD`, `app/data/ASCII/acctdata.txt` | Net new platform |
| `card-platform/deploy/k8s/41-ledger-posting-service.yaml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBTRN02C.cbl` | Net new platform |
| `card-platform/deploy/k8s/42-fraud-detection-service.yaml` | Context only, because no source build, container or deployment manifest exists: `app/csd/CARDDEMO.CSD` | Net new platform |
| `card-platform/deploy/k8s/43-notification-service.yaml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBSTM03A.CBL`, `app/cpy/COSTM01.CPY`, `app/csd/CARDDEMO.CSD`, `app/jcl/CREASTMT.JCL` | Net new platform |
| `card-platform/deploy/k8s/44-account-service.yaml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBACT04C.cbl`, `app/cbl/COACTUPC.cbl`, `app/cbl/COACTVWC.cbl`, `app/cpy/CSLKPCDY.cpy` and 1 more | Net new platform |
| `card-platform/deploy/k8s/45-card-service.yaml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/COCRDLIC.cbl`, `app/cbl/COCRDSLC.cbl`, `app/cbl/COCRDUPC.cbl` | Net new platform |
| `card-platform/deploy/k8s/README.md` | None cited; Rule 4 supplies the visual specification | Rule 3 document |
| `card-platform/deploy/k8s/kustomization.yaml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/deploy/k8s/load-images.sh` | None cited; no source build, container or deployment manifest exists | Net new platform |

### Demo scripts — 2 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/scripts/generate-env.sh` | Context only, because no source build, container or deployment manifest exists: `app/cbl/COSGN00C.cbl` | Net new platform |
| `card-platform/scripts/start-demo.sh` | None cited; no source build, container or deployment manifest exists | Net new platform |

### Repository-root platform files — 6 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `card-platform/.dockerignore` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/.env.example` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBTRN02C.cbl`, `app/cbl/COSGN00C.cbl`, `app/cpy/CVACT02Y.cpy`, `app/data/ASCII/acctdata.txt` | Net new platform |
| `card-platform/.gitignore` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `card-platform/README.md` | `app/cbl/CBACT04C.cbl`, `app/cbl/CBTRN02C.cbl`, `app/cpy/CSMSG02Y.cpy` | Rule 3 document |
| `card-platform/docker-compose.yml` | Context only, because no source build, container or deployment manifest exists: `app/cbl/CBTRN02C.cbl`, `app/data/ASCII/acctdata.txt` | Net new platform |
| `card-platform/pom.xml` | None cited; no source build, container or deployment manifest exists | Net new platform |

### Continuous integration — 3 paths

| Target path | Source provenance | Classification |
| --- | --- | --- |
| `.github/actions/setup-build-toolchain/action.yml` | None cited; no source build, container or deployment manifest exists | Net new platform |
| `.github/workflows/ci.yml` | Context only, because no source build, container or deployment manifest exists: `app/data/ASCII/cardxref.txt` | Net new platform |
| `card-platform/.gitleaks.toml` | None cited; no source build, container or deployment manifest exists | Net new platform |

### Backward closure arithmetic

| Group | Rows |
| --- | ---: |
| Event contract library | 36 |
| COBOL compatibility library | 13 |
| Authorization service | 129 |
| Ledger posting service | 86 |
| Fraud detection service | 87 |
| Notification service | 88 |
| Account service | 164 |
| Card service | 100 |
| Equivalence test module | 63 |
| Documents | 10 |
| Presentation | 1 |
| Deployment artifacts | 14 |
| Demo scripts | 2 |
| Repository-root platform files | 6 |
| Continuous integration | 3 |
| **Total** | **802** |

**Backward closure:** 802 rows against 802 tracked paths, so every target path carries a classification and a provenance statement.

## Deliberate omissions

| Source construct | Locator | Why it has no target | Decision record |
| --- | --- | --- | --- |
| `CDEMO-FROM-TRANID` | `app/cpy/COCOM01Y.cpy:L21` | Screen navigation state has no meaning in stateless APIs | [Decision log](decision-log.md) |
| `CDEMO-FROM-PROGRAM` | `app/cpy/COCOM01Y.cpy:L22` | Screen navigation state | [Decision log](decision-log.md) |
| `CDEMO-TO-TRANID` | `app/cpy/COCOM01Y.cpy:L23` | Screen navigation state | [Decision log](decision-log.md) |
| `CDEMO-TO-PROGRAM` | `app/cpy/COCOM01Y.cpy:L24` | Screen navigation state | [Decision log](decision-log.md) |
| `CDEMO-LAST-MAP` | `app/cpy/COCOM01Y.cpy:L43` | No target map exists | [Decision log](decision-log.md) |
| `CDEMO-LAST-MAPSET` | `app/cpy/COCOM01Y.cpy:L44` | No target mapset exists | [Decision log](decision-log.md) |
| `CDEMO-PGM-CONTEXT` and its condition names | `app/cpy/COCOM01Y.cpy:L29-L31` | Enter-versus-re-enter state disappears with pseudo-conversation | [Decision log](decision-log.md) |
| `CCARD-NEXT-PROG` | `app/cpy/CVCRD01Y.cpy:L21` | Card API routing replaces program navigation | [Decision log](decision-log.md) |
| `CCARD-NEXT-MAPSET` | `app/cpy/CVCRD01Y.cpy:L23` | No target mapset exists | [Decision log](decision-log.md) |
| `CCARD-NEXT-MAP` | `app/cpy/CVCRD01Y.cpy:L24` | No target map exists | [Decision log](decision-log.md) |
| Account filler, 178 bytes | `app/cpy/CVACT01Y.cpy:L17` | Carries no business data | [Decision log](decision-log.md) |
| Customer filler, 168 bytes | `app/cpy/CVCUS01Y.cpy:L23` | Carries no business data | [Decision log](decision-log.md) |
| Transaction filler, 20 bytes | `app/cpy/CVTRA05Y.cpy:L18` | Carries no business data | [Decision log](decision-log.md) |
| Cross-reference filler, 14 bytes | `app/cpy/CVACT03Y.cpy:L8` | Carries no business data | [Decision log](decision-log.md) |
| `XREF-CARD-NUM`, in the account copy only | `app/cpy/CVACT03Y.cpy:L5` | The account service asks the cross-reference one question, which customer an account belongs to, so `account_customer_link` keys on the account and stores no card. The field is kept in full by the authorization and card replicas, which are keyed on it | [Decision log](decision-log.md) |
| Category-balance filler, 22 bytes | `app/cpy/CVTRA01Y.cpy:L10` | Carries no business data | [Decision log](decision-log.md) |
| Card filler, 59 bytes | `app/cpy/CVACT02Y.cpy:L11` | Carries no business data | [Decision log](decision-log.md) |

Two of the copybooks above are partial exclusions rather than whole ones. `CVCRD01Y.cpy` keeps its card, account, and customer identifier fields at `:L34`, `:L37`, and `:L40`, which shape the card request and response objects. Only its three navigation fields are dropped. `COCOM01Y.cpy` keeps the two role condition names at `:L27-L28` and drops the seven navigation and context fields listed here.

## Renames recorded

| Source identifier | Locator | Target identifier | Why the rename is safe |
| --- | --- | --- | --- |
| `ACCT-EXPIRAION-DATE` | `app/cpy/CVACT01Y.cpy:L11` | `expirationDate`, `expiration_date` | No behavior depends on the source spelling |
| `CARD-EXPIRAION-DATE` | `app/cpy/CVACT02Y.cpy:L9` | `expirationDate`, `expiration_date` | No behavior depends on the source spelling |
| `FC-INVALID-DATE` | `app/cbl/CSUTLDTC.cbl:L62` | Success-oriented target condition | The all-zero token means validation succeeded |

## Copy inclusion to target dependency

A COBOL `COPY` statement pastes a layout into a program. The table traces each one a migrated program uses to the target type that replaces it, so a reader following a `COPY` in the source can find where it went.

| Source inclusion | Target dependency |
| --- | --- |
| `COPY CVTRA05Y` | Ledger transaction entity |
| `COPY CVTRA06Y` | `TransactionAuthorized` input contract |
| `COPY CVACT01Y` | Account entity and service-local account projections |
| `COPY CVACT02Y` | Card entity |
| `COPY CVACT03Y` | Private card-keyed cross-reference entities in authorization and card, and the card-free `AccountCustomerLinkEntity` in account |
| `COPY CVCUS01Y` | Customer entity and cardholder projection |
| `COPY CVTRA01Y` | Transaction-category balance entity |
| `COPY COSTM01` | Statement-transaction entity |
| `COPY CSUTLDPY` and `COPY CSUTLDWY` | Shared date validator. `CobolDateValidator` cites six source members, and each is an authority for a different part of the contract: `app/cpy/CSUTLDPY.cpy` the input contract and the strict `YYYYMMDD` mask, `app/cpy/CSUTLDWY.cpy` the working semantics, `app/cbl/CSUTLDTC.cbl` the severity and message-number feedback the validator returns, `app/cbl/COTRN02C.cbl` and `app/cbl/CORPT00C.cbl` the two tolerant call sites that accept message number `2513` beside severity `0000`, and `app/cbl/COACTUPC.cbl:L166` the one strict call site, which includes `CSUTLDPY` and has no `2513` branch. Business-rule flag 13 carries all four call sites. `app/cpy/CSDAT01Y.cpy:L42-L55` is a separate authority in the same area: it declares the timestamp shape rather than validating a date, and the bill-payment parity suite reads it for every separator position |
| `COPY CSLKPCDY` | Phone, state, and state-ZIP reference classes |
| `COPY CSMSG02Y` | Dead-letter metadata |
| `COPY CVCRD01Y` | Card request and response fields only. The next-program and next-map fields carry across to nothing |
| `COPY COCOM01Y` | Split, because the communication area carries two different kinds of field. **Dropped:** the seven navigation and screen-context fields, each recorded as a deliberate omission on `AuthorizationController` — `CDEMO-FROM-TRANID` at `:L21`, `CDEMO-FROM-PROGRAM` at `:L22`, `CDEMO-TO-TRANID` at `:L23`, `CDEMO-TO-PROGRAM` at `:L24`, `CDEMO-PGM-CONTEXT` at `:L29` with its two condition names at `:L30-L31`, `CDEMO-LAST-MAP` at `:L43` and `CDEMO-LAST-MAPSET` at `:L44`. A stateless request has nowhere to carry them. **Retained, as request, response, event or authority fields rather than as carried conversation state:** the role distinction `CDEMO-USER-TYPE` at `:L26` draws with `CDEMO-USRTYP-ADMIN` and `CDEMO-USRTYP-USER` at `:L27-L28`, which becomes `ROLE_ADMIN` and `ROLE_USER` and which the platform sources from `SEC-USR-TYPE PIC X(01)` at `app/cpy/CSUSR01Y.cpy:L22` through the fork at `app/cbl/COSGN00C.cbl:L232-L236`; the request identity `CDEMO-USER-ID PIC X(08)` at `:L25`, which becomes the `actor` column on every authorization decision at the width `SEC-USR-ID` declares at `app/cpy/CSUSR01Y.cpy:L18`; the selection fields `CDEMO-CUST-ID` at `:L33` with the three name fields at `:L34-L36`, `CDEMO-ACCT-ID` at `:L38`, `CDEMO-ACCT-STATUS` at `:L39` and `CDEMO-CARD-NUM` at `:L41`, which become named request, response and event fields |

One structural gain closes this section. Textual inclusion gave each program its own copy of a layout, and `CUSTREC.cpy` is the source's own proof that two copies drifted apart. A compiled module dependency cannot drift that way.

