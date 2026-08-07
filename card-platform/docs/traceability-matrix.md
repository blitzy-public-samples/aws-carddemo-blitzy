# Traceability Matrix

Rule 1 requires every source construct to reach a target or a documented exclusion. The matrix also reads backward from every target module to source provenance or a net-new marker. Coverage arithmetic closes each source inventory before any mapping detail. Design rationale lives in the [decision log](decision-log.md).

## Coverage summary

| Source location | Members | Classification breakdown | Sums to |
| --- | ---: | --- | ---: |
| `app/cbl/` | 28 | 12 primary migration sources, 8 reference-only, 2 partial, 6 excluded | 28 |
| `app/cpy/` | 28 | 12 record layouts, 8 reference or semantics inputs, 1 divergent fork, 6 excluded, 1 dead | 28 |
| `app/jcl/` | 29 | 11 dataset definitions, 3 behavior-defining jobs, 15 excluded utility or report jobs | 29 |
| `app/csd/CARDDEMO.CSD` | 8 files, 17 mapsets, 18 programs, 18 transactions | Mapped or excluded below | — |
| `app/bms/` | 17 | All excluded because no application user interface is in scope | 17 |
| `app/cpy-bms/` | 17 source copybooks | All excluded for the same reason; `.gitkeep` is not a source member | 17 |
| `app/data/ASCII/` | 9 | All reused as fixtures or seed sources | 9 |
| `app/data/EBCDIC/` | 12 data artifacts | All retained as binary or width references; `.gitkeep` is excluded from the count | 12 |

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
| `app/cbl/CBACT01C.cbl` | Print-only account report, with zero writes or rewrites | Negative evidence; no migration target |
| `app/cbl/CBACT02C.cbl` | Print-only account report, with zero writes or rewrites | Negative evidence; no migration target |
| `app/cbl/CBACT03C.cbl` | Print-only account report, with zero writes or rewrites | Negative evidence; no migration target |
| `app/cbl/CBCUS01C.cbl` | Print-only customer report, with zero writes or rewrites | Negative evidence; no migration target |
| `app/cbl/CBTRN01C.cbl` | Print-only transaction report; opens all files for input and is the batch card-file reader | Proves that posting does not check card status |

The five print-only batch programs contain no `WRITE` or `REWRITE` operation. Each opens its datasets for input only.

### Partially in scope

| Source file | Included slice | Target |
| --- | --- | --- |
| `app/cbl/CBACT04C.cbl` | Lines 350-356 for cycle-counter reset; lines 415-470 for equivalence only | `BillingCycleService`, `InterestCalculationEquivalenceTest`, and `DecimalTruncationEquivalenceTest` |
| `app/cbl/CBSTM03A.CBL` | Lines 86-159 for statement content and rendering structure | Notification plain-text and HTML renderers |

Interest accrual and full statement generation remain outside the runtime migration.

### Excluded programs

| Source file | Exclusion |
| --- | --- |
| `app/cbl/COUSR00C.cbl` | User-management menu, excluded by scope |
| `app/cbl/COUSR01C.cbl` | User creation, excluded by scope |
| `app/cbl/COUSR02C.cbl` | User update, excluded by scope |
| `app/cbl/COUSR03C.cbl` | User deletion, excluded by scope |
| `app/cbl/COADM01C.cbl` | Administrator management, excluded by scope |
| `app/cbl/CBTRN03C.cbl` | Printed transaction reporting, excluded by scope |

**Program closure:** 12 + 8 + 2 + 6 = 28.

## Forward: copybooks

### Record layouts

| Source copybook | Target |
| --- | --- |
| `app/cpy/CVACT01Y.cpy` | Account entity, ledger balance projection, authorization credit snapshot, migrations, and events |
| `app/cpy/CVACT02Y.cpy` | Card entity, migration, card API, and `CardUpdated` |
| `app/cpy/CVACT03Y.cpy` | Private `card_xref` tables and authorization event account resolution |
| `app/cpy/CVCUS01Y.cpy` | Canonical customer entity and notification cardholder projection |
| `app/cpy/CVTRA01Y.cpy` | Ledger transaction-category balance entity and composite key |
| `app/cpy/CVTRA05Y.cpy` | Ledger transaction entity and posted-event provenance |
| `app/cpy/CVTRA06Y.cpy` | Inbound authorization event and fixture parser |
| `app/cpy/COSTM01.CPY` | Notification statement-transaction read model |
| `app/cpy/CVTRA02Y.cpy` | Disclosure-group entity and interest-equivalence input |
| `app/cpy/CVTRA03Y.cpy` | Seven transaction-type seed rows |
| `app/cpy/CVTRA04Y.cpy` | Eighteen transaction-category seed rows |
| `app/cpy/CSUSR01Y.cpy` | Security-record semantics and signon evidence; no user-management entity is migrated |

### Reference and semantics inputs

| Source copybook | Target influence |
| --- | --- |
| `app/cpy/CSLKPCDY.cpy` | Three generated validation-reference classes and three account reference tables |
| `app/cpy/CSUTLDPY.cpy` | Shared date-validator input contract |
| `app/cpy/CSUTLDWY.cpy` | Shared date-validator working semantics |
| `app/cpy/CSMSG02Y.cpy` | Dead-letter metadata fields |
| `app/cpy/COCOM01Y.cpy` | Effective role names and documented navigation omissions |
| `app/cpy/COMEN02Y.cpy` | Menu routing evidence and business-rule flags 19, 20, and 22 |
| `app/cpy/CVCRD01Y.cpy` | Card field widths; navigation fields are omitted |
| `app/cpy/CSMSG01Y.cpy` | Common source message text |

### Divergent fork

| Source copybook | Handling |
| --- | --- |
| `app/cpy/CUSTREC.cpy` | Read only to document its one-name fork from `CVCUS01Y.cpy`; the statement program binds to it at `app/cbl/CBSTM03A.CBL:L55` |

### Excluded copybooks

| Source copybook | Exclusion |
| --- | --- |
| `app/cpy/COADM02Y.cpy` | Administrator menu state |
| `app/cpy/COTTL01Y.cpy` | Terminal title and presentation state |
| `app/cpy/CSDAT01Y.cpy` | Screen and date presentation helper |
| `app/cpy/CSSETATY.cpy` | Terminal attribute helper |
| `app/cpy/CSSTRPFY.cpy` | String and screen presentation helper |
| `app/cpy/CVTRA07Y.cpy` | Printed transaction report layout |

### Dead copybook

| Source copybook | Handling |
| --- | --- |
| `app/cpy/UNUSED1Y.cpy` | Dead duplicate of the security-user widths; business-rule flag 26 records the evidence |

**Copybook closure:** 12 + 8 + 1 + 6 + 1 = 28.

## Forward: Job Control Language members

No Job Control Language member becomes a scheduler, cron entry, or workflow. Dataset definitions supply keys, record sizes, and seed behavior; event consumers replace the runtime batch trigger.

### Dataset-definition and load jobs

| Member | Target use |
| --- | --- |
| `ACCTFILE.jcl` | Account key, record size, and account seed migration |
| `CARDFILE.jcl` | Card primary key, account alternate index, record size, and card seed |
| `CUSTFILE.jcl` | Customer key, record size, and customer seed |
| `XREFFILE.jcl` | Card-number key, account alternate index, record size, and cross-reference seed |
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
| `POSTTRAN.jcl` | `CBTRN02C` becomes the ledger consumer; dataset allocations prove the card file is absent; reject `LRECL=430` defines the reject width |
| `INTCALC.jcl` | Interest equivalence and the cycle-close dependency; no event-driven interest service |
| `CREASTMT.JCL` | Card-plus-transaction sort key for the notification read model |

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
| `CCDL` → `COCRDSLC` | `POST /cards/detail` |
| `CCLI` → `COCRDLIC` | `GET /cards` |
| `CCUP` → `COCRDUPC` | `PUT /cards` |
| `CC00` → `COSGN00C` | Basic-authentication behavior and role fork; no signon screen |
| `CDV1` → `COCRDSEC` | Dead orphan definitions; business-rule flag 17 |
| `CM00` → `COMEN01C` | REST route dispatch replaces the menu; no menu endpoint |
| `CR00` → `CORPT00C` | Reporting excluded; queue write retained as outbox provenance |
| `CT00` → `COTRN00C` | Ledger transaction-list contract; no demo query route |
| `CT01` → `COTRN01C` | Ledger transaction-detail contract; no demo query route |
| `CT02` → `COTRN02C` | `POST /authorizations` request fields and validation |
| `CU00` → `COUSR00C` | Excluded user-management menu |
| `CU01` → `COUSR01C` | Excluded user creation |
| `CU02` → `COUSR02C` | Excluded user update |
| `CU03` → `COUSR03C` | Excluded user deletion |

The CICS resource file defines 17 mapsets, 18 programs, and 18 transactions. `COCRDSEC` is the program without a source member.

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
| `app/data/ASCII/cardxref.txt` | 50 × 36 | `CVACT03Y.cpy` declares 50 | Authorization and card seeds, notification projection, width-tolerant loader |
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

| Target module or artifact | Source provenance | Classification |
| --- | --- | --- |
| `libs/event-contracts` | Transaction and account fields from `CVTRA05Y`, `CVTRA06Y`, `CVACT01Y`, `CVACT02Y`, and `CVACT03Y`; decline codes from `CBTRN02C` | Mixed source-derived and additive |
| `libs/cobol-compat` | Picture clauses, `NUMVAL-C`, `CSUTLDTC`, and `CSLKPCDY` | Source-derived compatibility layer |
| `authorization-service` | Synthesized from `CBTRN02C`, `COTRN02C`, and `COSGN00C`; `COPAUA0C` and `CP00` verified absent | Synthesized migration |
| `ledger-posting-service` | `CBTRN02C`, `POSTTRAN.jcl`, `CVTRA05Y`, `CVTRA01Y`, and `CVACT01Y` | Direct migration |
| `fraud-detection-service` | No COBOL fraud ancestor; only field widths come from transaction and cross-reference layouts | Net new |
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
| `card-platform/pom.xml`, Dockerfiles, Compose, and Kubernetes manifests | No source build or container manifest | Net new platform |
| OpenAPI documents | CICS transaction contracts and target controller routes | New documentation of migrated APIs |
| Documents under `card-platform/docs/` | AAP Rules 1-3 and source evidence | Rule-mandated additions |
| `presentation/executive-summary.html` | Rule 4 and documented architecture | Rule-mandated addition |
| `.github/workflows/ci.yml` | Build, test, equivalence, schema, and container requirements | Rule-mandated addition |

### Rule-mandated and platform artifacts

| Target artifact | Authority or provenance | Classification |
| --- | --- | --- |
| `card-platform/pom.xml` | AAP module graph and dependency inventory | Net new build artifact |
| `card-platform/docker-compose.yml` | AAP demo topology and runtime contracts | Net new orchestration |
| `card-platform/.env.example` | Supported application configuration keys | Net new configuration inventory |
| `card-platform/services/*/Dockerfile` | AAP container requirement | Six net new container definitions |
| `card-platform/deploy/k8s/*.yaml` | AAP standard-cluster deployment requirement | Net new deployment manifests |
| `.github/workflows/ci.yml` | AAP continuous build, equivalence, compatibility, and image checks | Rule-mandated addition |
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
| Category-balance filler, 22 bytes | `app/cpy/CVTRA01Y.cpy:L10` | Carries no business data | [Decision log](decision-log.md) |
| Card filler, 59 bytes | `app/cpy/CVACT02Y.cpy:L11` | Carries no business data | [Decision log](decision-log.md) |

## Renames recorded

| Source identifier | Locator | Target identifier | Why the rename is safe |
| --- | --- | --- | --- |
| `ACCT-EXPIRAION-DATE` | `app/cpy/CVACT01Y.cpy:L11` | `expirationDate`, `expiration_date` | No behavior depends on the source spelling |
| `CARD-EXPIRAION-DATE` | `app/cpy/CVACT02Y.cpy:L9` | `expirationDate`, `expiration_date` | No behavior depends on the source spelling |
| `FC-INVALID-DATE` | `app/cbl/CSUTLDTC.cbl:L62` | Success-oriented target condition | The all-zero token means validation succeeded |

## Copy inclusion to target dependency

| Source inclusion | Target dependency |
| --- | --- |
| `COPY CVTRA05Y` | Ledger transaction entity |
| `COPY CVTRA06Y` | `TransactionAuthorized` input contract |
| `COPY CVACT01Y` | Account entity and service-local account projections |
| `COPY CVACT02Y` | Card entity |
| `COPY CVACT03Y` | Private cross-reference entities |
| `COPY CVCUS01Y` | Customer entity and cardholder projection |
| `COPY CVTRA01Y` | Transaction-category balance entity |
| `COPY COSTM01` | Statement-transaction entity |
| `COPY CSUTLDPY` and `COPY CSUTLDWY` | Shared date validator |
| `COPY CSLKPCDY` | Phone, state, and state-ZIP reference classes |
| `COPY CSMSG02Y` | Dead-letter metadata |
| `COPY COCOM01Y` | No target import; presentation and navigation state are omitted |