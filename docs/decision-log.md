# CardDemo REST/JSON API — Decision Log

This log records every non-trivial decision for the additive, read-only
CardDemo REST/JSON API layer, as required by the Explainability rule. Design
rationale lives here rather than in executable-code comments. The API uses
base CICS Web Support under `/carddemo/api/v1`, leaves existing CardDemo
production COBOL/BMS/CSD/JCL members untouched, and accesses VSAM strictly
read-only.

The four columns identify the selected decision, rejected or deferred
alternatives, the concrete constraint-driven rationale, and the residual risk.

## Decisions

### D1 — JSON generation approach

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Build and ship the in-repository COBOL serializer/parser `COJSONUC`, while allowing native CICS `TRANSFORM DATATOJSON` and `JSONTODATA` where a target region enables them. | Use native transforms only; depend on an external JSON library or licensed middleware. | The supplied runtime is CICS TS 5.6, which supports native transforms, but `COJSONUC` keeps the API self-contained and portable, avoids a third-party dependency, and provides explicit control over escaping and signed implied-decimal scaling. | Two available serialization paths can drift. The OpenAPI contract test and endpoint examples must remain the common behavioral oracle. |

### D2 — Bearer-token model

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| `COAPISEC` issues a short-lived opaque bearer token and stores token state in a CICS `MAIN` TSQ registry. | A self-contained signed token with no server state; external OAuth/OIDC. | CICS tasks are pseudo-conversational, so cross-request state cannot live in program working storage. A MAIN TSQ is simple, in-region, requires no external crypto product, and supports deterministic validation against `USRSEC`. | The registry is region-local, tokens disappear on restart, and it is not sysplex-wide. This is accepted for the read-only demo increment; signed tokens, TLS, and federated identity remain hardening work. |

### D3 — Per-account transaction listing strategy

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Resolve account-to-card relationships through `CXACAIX`/`CCXREF`, browse `TRANSACT`, and filter records by transaction card number in application logic. | Create a new card-keyed or account-keyed transaction alternate index. | `app/jcl/TRANIDX.jcl` proves the only transaction AIX is `KEYS(26 304)`, the `TRAN-PROC-TS` field. A new index would change the data design and violate the additive, read-only scope; application filtering works with existing datasets. | A full-file browse costs more as transaction volume grows. Pagination and a purpose-built index are documented future work. |

### D4 — Customer-identity masking

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Mask SSN to a last-four representation such as `XXX-XX-3888`, expose only the last four of the government-issued id, and omit the EFT identifier. | Return the full source fields; omit every identity field. | SSN and government id are sensitive PII even though the primary prompt explicitly emphasized PAN/CVV. Default minimization preserves limited inquiry value without disclosing full identifiers. | Consumers needing full identity data cannot use this API and require a separate RACF-gated workflow. |

### D5 — COMMAREA versus CHANNEL/CONTAINER

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Use `COAPICOM` COMMAREA transport for single-record operations and channel `CDEMOAPILISTCH` with containers `TRANLISTREQ`, `TRANLISTRSP`, and `TRANLISTSTA` for the transaction list. | Force every operation through COMMAREA. | CardDemo conventionally uses a 1024-byte COMMAREA and CICS limits COMMAREA to 32 KB. `API-TRAN-LIST` can reach roughly 165 KB at 500 entries, while a single response fits `API-PAYLOAD X(1000)`. | Router and transaction service maintain two transport paths. `COAPICOM`, `COAPTRNY`, and the dual-mode `TSTTRAN` driver keep the contract explicit. |

### D6 — CSD group and transaction naming

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Keep the descriptive file name `app/csd/CARDDEMOAPI.CSD`, install CICS group `CDEMOAPI`, and use alias transaction `CAPI`. | Install group `CARDDEMOAPI`. | CICS group names are limited to eight characters and transaction ids to four. `CDEMOAPI` and `CAPI` meet those limits and do not collide with the existing CardDemo resources. | The file/group naming mismatch can confuse operators. `APICSDIN.jcl` and the onboarding guide repeat the exact mapping. |

### D7 — Base CICS Web Support and transport posture

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Use in-region base CICS Web Support with `TCPIPSERVICE(CDAPISVC)` on operator-confirmed port `3001`, `URIMAP(CDAPIURI)`, `SSL(NO)`, and `AUTHENTICATE(NO)`. | z/OS Connect EE, an external API gateway, or a separate Spring service. | The agreed scope requires base CICS Web Support and no additional licensed product. Port 3001 is an install-time placeholder that the operator must validate. | Cleartext HTTP and listener-level unauthenticated transport are not production hardening. TLS/keyring, RACF, and OAuth/OIDC are explicit follow-up items. |

### D8 — Controlled invalid-call handling in `COTRSVCC`

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Preserve the controlled `EXEC CICS ABEND ABCODE('TSNC') NODUMP` when `COTRSVCC` is invoked with neither channel `CDEMOAPILISTCH` nor an exact-length API COMMAREA. | Write a structured HTTP 400 response; return silently; address an assumed response area. | In this state there is no verified, addressable response contract. Writing a 400 would risk storage corruption, while a silent return would hide a programmer/integration defect. A named NODUMP abend is deterministic and avoids exposing response data. | A caller contract error terminates that CICS task and appears operationally as an abend. The onboarding contract and drivers prevent valid callers from entering this path; `NODUMP` limits data exposure. |

### D9 — Empty-list fixture and 404 distinction

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Ship `app/test/api/acctdata-empty.txt` as account `00000000099`, keep that account absent from `CCXREF`, and reserve absent account `00000000098` for the list 404 test. | Treat any no-result account as 404; require an operator to find and repoint a local fixture. | `COTRSVCC` first proves account existence. An existing account with zero cards must return HTTP 200 and an empty list, while a missing account returns 404. The shipped fixture makes both branches deterministic and repeatable. | Loading a test record changes a test-region dataset. The onboarding procedure requires backup, a disposable region or clone, and restoration after execution. |

### D10 — Reserved transaction-list truncation state

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Retain `88 TRAN-LIST-WAS-TRUNCATED VALUE 'Y'` in `COAPTRNY` as a reserved consumer-visible state, while the current producer returns HTTP 500 instead of partial success when the 500-entry cap is exceeded. | Remove the 88-level as unused; begin returning partial HTTP 200 responses. | `COAPIRTR` already reads the 88-level when serializing the `truncated` property. Removing it would break a live copybook consumer and layout contract. Keeping it is additive and allows a future explicitly approved partial-success policy. | The reserved state is currently never produced and could be misunderstood as active behavior. The copybook annotation and OpenAPI behavior state that current successful lists are complete. |

### D11 — Independent API contract

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Define the new shared contract in `COAPICOM` rather than changing the existing CardDemo `COCOM01Y` COMMAREA. | Extend or reinterpret the existing 1024-byte application COMMAREA. | Existing online programs depend on the established CardDemo interface. A separate API contract keeps the feature additive, gives each service deterministic HTTP/error/token fields, and avoids regression risk to the BMS application. | Two top-level contracts must be maintained. Clear copybook ownership and route/service tests limit accidental mixing. |

### D12 — Traceability depth

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Maintain forward endpoint-to-router-to-service-to-VSAM traceability; do not create a migration-style bidirectional source/target matrix. | Build a full bidirectional migration matrix for every legacy field. | This feature is a net-new additive API, not a migration or refactor of the existing application. Forward traceability proves each public operation reaches the intended read-only implementation and record contract. | Field-level drift could still occur. Copybook-qualified moves and OpenAPI example validation provide the detailed controls. |

### D13 — Static linkage under NODYNAM and binder NOLET for the API build

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Keep the shipped `NODYNAM` compile (so `COAPIRTR` statically `CALL`s `COJSONUC`) and override only the API build's binder PARM through `PARM.LKED` in `APIBUILD.jcl` and `APITSTB.jcl` to drop `LET`, so an unresolved external fails the bind with `RC>=8`. `COJSONUC` is built first into `LOADLIB`; `COAPIRTR` is built last and resolves it by autocall because `BUILDONL`'s `LKED SYSLIB` concatenates `&LOADLIB`. Keep `NEWCOPY COND=(4,LT)`. | Convert `COAPIRTR` to a dynamic `CALL`/`EXEC CICS LINK` for `COJSONUC`; edit `samples/proc/BUILDONL.prc` to remove `LET` globally; gate `NEWCOPY` with `COND=(3,LT)` or `COND=(0,NE)` as the QA report literally suggested. | Overriding `LET` only in the API build keeps the change additive and leaves the shared, out-of-scope proc untouched. A clean `COAPIRTR` bind legitimately returns `RC=4` (duplicate `DFHEILID` pulled in when `COJSONUC` is autocalled), so `COND=(4,LT)` admits a good build while `NOLET` turns a genuinely unresolved `COJSONUC` into `RC>=8`, which `COND=(4,LT)` then blocks. `COND=(3,LT)`/`(0,NE)` would wrongly skip `NEWCOPY` on the benign `RC=4`. | The definitive proof is the bind XREF plus a live JSON invocation, which require a z/OS/CICS region and cannot run in a Linux-only CI. The hardening prevents silent deployment of an unresolved module but does not by itself prove successful runtime execution. |

### D14 — Shared read-only files instead of duplicate FILE definitions

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Group `CDEMOAPI` defines NO FILE resources. The API programs open the existing CARDDEMO VSAM files exactly as the base region installs them; read-only is enforced in code (only `READ`/`STARTBR`/`READNEXT`) and by region security. | Redefine `ACCTDAT`, `CARDAIX`, `CARDDAT`, `CCXREF`, `CUSTDAT`, `CXACAIX`, `TRANSACT`, and `USRSEC` inside `CDEMOAPI` with `ADD(NO) UPDATE(NO) DELETE(NO)`; rely on DFHCSDUP rejecting the duplicates as accidental safety. | A duplicate FILE installed from `CDEMOAPI` would override the base region's write-capable definitions region-wide — breaking the online application — or fail install; depending on rejection is not a demonstrably safe design. Sharing the installed files is the only additive, backward-compatible option. | The API depends on the base `CARDDEMO` group being installed first; onboarding states this ordering. The rollback job never touches the base files. |

### D15 — Group activation: GRPLIST versus online CEDA INSTALL

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| `APICSDIN.jcl` adds `CDEMOAPI` to the region `GRPLIST` (DFHCSDUP `ADD GROUP ... LIST`) for deterministic install at the next startup, and documents an immediate `CEDA INSTALL GROUP(CDEMOAPI)` from an authorized terminal (or CMCI/SPI) for a running region. It does not attempt an online install from batch. | Drive `CEDA INSTALL` from batch via an SDSF `/MODIFY` command (the original approach); rely solely on manual operator action with no `GRPLIST` wiring. | DFHCSDUP has no `INSTALL` verb and `CEDA` is a terminal transaction, so a batch `/MODIFY 'CEDA INSTALL...'` is unsupported and unreliable across regions. `GRPLIST` membership is the supported, auditable, restart-safe activation; the documented terminal/CMCI path covers immediate installs. | `GRPLIST` activation takes effect only at the next restart; immediate activation requires the documented terminal/CMCI path, and the default `GRPLIST` value must be changed to the region's real startup list. |

### D16 — Diagnostic and confidentiality attributes on the API resources

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| The alias and driver transactions use `STORAGECLEAR(YES)`, `DUMP(NO)`, `TRACE(NO)`, `CONFDATA(YES)`; every API and driver PROGRAM uses `CEDF(NO)`. | Ship the permissive defaults `STORAGECLEAR(NO)`, `DUMP(YES)`, `TRACE(YES)`, `CONFDATA(NO)`, `CEDF(YES)`. | The API handles credentials, bearer tokens, PANs, and PII. `STORAGECLEAR(YES)` prevents residual sensitive data in freed task storage; `CONFDATA(YES)` with `TRACE(NO)`/`DUMP(NO)` keeps confidential fields out of traces and dumps; `CEDF(NO)` stops the EDF debugger from exposing in-flight payloads. This aligns the deployed definitions with the masking/exclusion posture. | Diagnostics are intentionally reduced; debugging a production issue requires temporarily enabling tracing under change control rather than relying on defaults. |

### D17 — Listener MAXDATALEN and loopback bind

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| `TCPIPSERVICE(CDAPISVC)` uses `PORTNUMBER(3001)`, `IPADDRESS(127.0.0.1)`, `MAXDATALEN(32)`, `SSL(NO)`, `AUTHENTICATE(NO)`. | Bind `IPADDRESS(ANY)` with a larger/default `MAXDATALEN`; enable SSL/authentication in this increment. | A loopback bind limits exposure of the unauthenticated, non-TLS read-only listener to same-host callers (typically a co-located proxy/gateway) for this first increment. `MAXDATALEN(32)` (32 KB) bounds inbound request size for the small JSON inquiry bodies, while the large transaction-list response travels over channel/container rather than the COMMAREA. | Off-host access requires a co-located fronting proxy; `MAXDATALEN` must be raised if request bodies grow. Both are listed under onboarding "Suggested next tasks", alongside TLS/auth hardening. |

### D18 — Test-driver deployment via a dedicated QA build

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Ship `APITSTB.jcl` to compile/link/`NEWCOPY` the six `TST*` drivers and install their PROGRAM/TRANSACTION defs with the API group through `APICSDIN.jcl`. Production `APIBUILD.jcl` builds only the eight runtime programs. | Add the drivers to `APIBUILD.jcl`; leave the drivers with no build job (manual hand-copy plus `CEDA DEFINE`); place the drivers in a separate QA-only CSD group. | Keeping drivers out of the production build avoids shipping test transactions into the runtime path while still giving QA one reproducible build-and-install. The drivers use `EXEC CICS LINK` (runtime-resolved), so they bind cleanly and independently; co-locating their defs in `CDEMOAPI` lets one install job provision a test region. | The driver transactions live in the same group as the production resources, so a region installing `CDEMOAPI` also gets the drivers. Splitting them into a QA-only group is the documented option if that is undesirable. |

### D19 — Rollback / uninstall procedure

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Ship `APICSDRB.jcl` to remove `CDEMOAPI` from `GRPLIST` and `DELETE` the group's CSD definitions (idempotent), and document the operator `CEMT DISCARD` path for a running region. No FILE restoration is needed because the group defines no files. | Provide no rollback and rely on manual edits; attempt an online `DISABLE`/`DISCARD` from batch; always re-`INSTALL GROUP(CARDDEMO)` to restore clobbered files. | A scripted, idempotent CSD-level rollback plus a documented discard path gives clean recovery without destructive manual edits. Because `CDEMOAPI` never redefines the CARDDEMO files, there is nothing to restore; the base-group re-install is documented only as legacy recovery for a pre-remediation unsafe install. | Batch DFHCSDUP cannot discard resources already installed in a running region; full removal from a live region still needs the documented operator `CEMT DISCARD` or a restart. |

## Forward Traceability Matrix

| # | Method and path | operationId | Router route | Service program | VSAM file(s) | Record copybook(s) | Response copybook |
|---|---|---|---|---|---|---|---|
| 1 | POST `/carddemo/api/v1/signon` | `signon` | Token issue | `COAPISEC` | `USRSEC` | `CSUSR01Y` | `COAPSGNY` |
| 2 | GET `/carddemo/api/v1/accounts/{acctId}` | `getAccount` | Account inquiry | `COACSVCC` | `ACCTDAT` | `CVACT01Y` | `COAPACTY` |
| 3 | GET `/carddemo/api/v1/customers/{custId}` | `getCustomer` | Customer inquiry | `COCUSVCC` | `CUSTDAT` | `CVCUS01Y` | `COAPCUSY` |
| 4 | GET `/carddemo/api/v1/cards/{cardNum}` | `getCard` | Card inquiry; mask PAN and omit security code | `COCRSVCC` | `CARDDAT` | `CVACT02Y` | `COAPCRDY` |
| 5 | GET `/carddemo/api/v1/xref/{cardNum}` | `getCardXref` | Card-to-account/customer inquiry | `COXRSVCC` | `CCXREF` | `CVACT03Y` | `COAPXRFY` |
| 6 | GET `/carddemo/api/v1/accounts/{acctId}/transactions` | `listAccountTransactions` | Transaction list over channel/container | `COTRSVCC` | `ACCTDAT`, `CXACAIX`/`CCXREF`, `TRANSACT` | `CVACT01Y`, `CVACT03Y`, `CVTRA05Y` | `COAPTRNY` (`API-TRAN-LIST`) |
| 7 | GET `/carddemo/api/v1/transactions/{tranId}` | `getTransaction` | Transaction detail over COMMAREA | `COTRSVCC` | `TRANSACT` | `CVTRA05Y` | `COAPTRNY` (`API-TRAN-RESPONSE`) |

Every protected route first performs token validation through `COAPISEC`
against the `USRSEC`-backed token contract. All routing flows through
`COAPIRTR`, and JSON is emitted through the in-repository serializer or the
approved native transform path.

Because this is an additive new build rather than a migration or refactor, a
full bidirectional source-to-target matrix is not mandated. Decision D12
records that interpretation and the compensating forward-traceability controls.

## Scope guardrails

- VSAM access is read-only: no API service issues `WRITE`, `REWRITE`, or
  `DELETE`.
- Existing CardDemo production executable source, BMS maps, CSD source, and
  JCL remain untouched; the API is delivered through new members.
- The front door is base CICS Web Support only; no external licensed gateway
  is required.
- Program, copybook, group, and transaction names observe mainframe
  eight-character/four-character limits.
- The deterministic status map is HTTP `200` (including empty lists), `400`,
  `401`, `404`, and `500`; internal CICS `RESP2` data is never exposed.
- PAN and transaction card numbers are masked to last four, card security code
  is never serialized, and customer identity fields are minimized.
