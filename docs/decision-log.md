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
