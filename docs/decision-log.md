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
| Build and ship the in-repository COBOL serializer/parser `COJSONUC`, while allowing native CICS `TRANSFORM DATATOJSON` and `JSONTODATA` where a target region enables them. | Use native transforms only; depend on an external JSON library or licensed middleware. | The supplied runtime is CICS TS 5.6, which supports native transforms, but `COJSONUC` keeps the API self-contained and portable, avoids a third-party dependency, and provides explicit control over escaping and signed implied-decimal scaling. | Two available serialization paths can drift. The OpenAPI contract test and endpoint examples must remain the common behavioral oracle. Note (verified in the shipped build): `COAPIRTR` serializes every money field through its own `7150-EMIT-MONEY`, which emits a quoted string matching the `MonetaryAmount` (string) contract; `COJSONUC` is not on the active monetary path. To eliminate the previously-latent divergence (QA finding I2), `COJSONUC`'s `1700-MON2` opcode has been reconciled so it now emits its scaled, signed 2-decimal token as a **quoted** JSON string, matching the `MonetaryAmount` (string) contract and `7150-EMIT-MONEY` exactly — both monetary paths are therefore identical and contract-safe, and MON2 can no longer produce a contract-violating unquoted number if it is ever adopted. Reconciliation verified at runtime under GnuCOBOL (MON2 emits `"1234.56"`, `"-50.00"`, `"0.00"`, `"9999999999.99"` — valid JSON strings each matching `^-?\d+\.\d{2}$`) and by the OpenAPI contract test (101/101, strict). The residual risk is minimal: two serializers still exist, so any future edit to either monetary path must be re-validated by the contract test to prevent the paths drifting apart again. |

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
| Use `COAPICOM` COMMAREA transport for single-record operations and channel `CDEMOAPILISTCH` with containers `TRANLISTREQ`, `TRANLISTRSP`, and `TRANLISTSTA` for the transaction list. | Force every operation through COMMAREA. | CardDemo conventionally uses a 1024-byte COMMAREA, the API single-record buffer is `API-PAYLOAD X(1000)`, and CICS caps any COMMAREA at 32 KB. The per-account list is bounded at 50 entries (`API-TRAN-LIST OCCURS 0 TO 50`; `COTRSVCC WS-MAX-TRANS VALUE 50`) of roughly 330 bytes each — about 16.5 KB — which far exceeds both the 1024-byte convention and the `API-PAYLOAD X(1000)` single-record buffer, so it cannot ride the conventional COMMAREA path even though it stays below the 32 KB ceiling. Single-record responses fit `API-PAYLOAD X(1000)`. | Router and transaction service maintain two transport paths. `COAPICOM`, `COAPTRNY`, and the dual-mode `TSTTRAN` driver keep the contract explicit. |

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

### D10 — Transaction-list truncation disclosure

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| When per-account matches reach the 50-entry list cap (`COTRSVCC WS-MAX-TRANS VALUE 50`; `API-TRAN-LIST OCCURS 0 TO 50`), set `88 TRAN-LIST-WAS-TRUNCATED VALUE 'Y'`, stop the browse, and return the bounded partial list with HTTP 200; `COAPIRTR` serializes the state as the JSON `truncated` property. | Silently drop matches beyond the cap; fail the whole inquiry with HTTP 500 on a cap breach; enlarge the fixed ODO table arbitrarily. | The fixed ODO table (`API-TRAN-LIST`, maximum 50) cannot hold an unbounded match set, but a silent stop would hide rows and an HTTP 500 would deny an otherwise valid inquiry. Disclosing a bounded partial result (`truncated='Y'`, HTTP 200) is deterministic and honest; `COAPIRTR` validates the flag is exactly `'N'` or `'Y'` and emits it, so a partial list is disclosed and never silent. A larger cap or true pagination is deferred follow-up work. | Consumers must honor the `truncated` flag and refine or paginate when it is `'Y'`; a client that ignores it may treat a partial list as complete. The property is documented in `openapi.yaml` and annotated in `COAPTRNY`. A `WS-MAX-CARDS` breach (an account mapping to more than 50 cards) or a `WS-MAX-SCAN` breach (the browse ceiling) is a separate safety path that still yields a deterministic HTTP 500, distinct from list truncation. |

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

### D20 — API token registry key derivation and collision handling

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Derive each MAIN-TSQ queue name from a fixed-width numeric hash of the opaque CSPRNG token under the `AT` prefix; probe the target queue before writing; on a live-but-different occupant return `WRITE-COLLIDE` so the caller re-mints (a live entry is never overwritten), reclaim an identical or expired occupant in place, and fail closed on a clock-read failure. | Use the raw 64-character token as the queue name; keep one global index queue; overwrite on collision; ignore collisions. | CICS TSQ names are length-bounded, so a bounded hash is required to map a 64-character token into the queue-name namespace; probing and refusing to clobber a live occupant preserves one-token-to-one-session integrity and never binds a caller to another user's registry entry. | A hash collision forces the affected caller to re-authenticate; this is rare given the 15-minute TTL and `TSMODEL(CDAPITSM) EXPIRYINT(20)` reaping, and the collision path is fail-closed by design. |

### D21 — Full PAN in the request URI path

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Keep the account/card/xref path parameters exactly as the frozen AAP contract defines them (including `{cardNum}` carrying a full 16-digit PAN on `GET /cards/{cardNum}` and `/xref/{cardNum}`); mitigate exposure operationally via loopback binding (`IPADDRESS(127.0.0.1)`), confidentiality attributes (`CONFDATA(YES)`, `DUMP(NO)`, `TRACE(NO)`, `CEDF(NO)`), and by never logging the PAN (only `requestId` is written to the console). | Switch the card key to a masked/tokenized path segment; move the PAN to a request body; hash the PAN in the path. | The endpoint shapes are frozen by the AAP and must not change; response bodies already mask PAN to last-4 and exclude CVV, so the residual exposure is confined to the request line, which loopback isolation and no-PAN-logging address. | A full PAN can still appear in an intermediary access log or a TLS-terminating proxy if the API is later exposed beyond loopback; a PAN-redacting reverse proxy / gateway is the documented production follow-up. |

### D22 — Cross-origin (CORS) posture

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Emit no `Access-Control-Allow-Origin` or other CORS headers, leaving the API non-CORS-enabled (same-origin / server-to-server only) by default. | Emit a permissive `Access-Control-Allow-Origin: *`; emit a configurable origin allow-list. | The API is a server-to-server inquiry contract reached over loopback in this iteration, not a browser-facing endpoint; omitting CORS headers keeps the browser same-origin policy fully restrictive and avoids inadvertently authorizing cross-site script access to card/account data. | Browser-based clients on a different origin cannot call the API until an explicit, allow-listed CORS policy is added at the future gateway; this is intentional and recorded as a follow-up. |

### D23 — Response header strategy (cache and security headers)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Write `Cache-Control: no-store, private`, `Pragma: no-cache`, and `X-Content-Type-Options: nosniff` on every response; deliberately omit `X-Frame-Options`, `Content-Security-Policy`, and `Strict-Transport-Security`. | Add the full browser security-header suite; add none; make the set configurable. | Every response carries account/card/customer/token data that must never be cached, and `nosniff` prevents MIME-type sniffing of the JSON; the framing/CSP headers govern HTML rendering contexts a non-HTML JSON API does not have, and HSTS is meaningless while transport is cleartext HTTP over loopback. | If HTML/browser delivery or TLS is ever introduced, the omitted headers (CSP, `X-Frame-Options`, HSTS) must be revisited; recorded as a follow-up tied to the TLS/gateway work. |

### D24 — Transaction and command security attributes (`RESSEC`/`CMDSEC`)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Define the API alias and test transactions with `RESSEC(NO)` and `CMDSEC(NO)`, relying on application-level enforcement (bearer-token validation in `COAPISEC` and strictly read-only VSAM access in the service programs) rather than CICS resource/command security. | Enable `RESSEC(YES)`/`CMDSEC(YES)` and define RACF profiles for every file and SPI command; enable only one. | Full RACF resource/command security is an explicit out-of-scope follow-up in the AAP; the programs issue only `READ`/`STARTBR`/`READNEXT`/`ENDBR` (no write verbs), so the read-only guarantee is enforced in code and verified by test, and the token gate authenticates every non-signon route. | Without RACF resource security a program defect or a future write path would not be blocked at the CICS layer; enabling `RESSEC`/`CMDSEC` with RACF profiles is the documented hardening follow-up. |

### D25 — CSD confidentiality posture and `APICSDIN.jcl` / `CARDDEMOAPI.CSD` reconciliation

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Harden every CDEMOAPI transaction/program with `CONFDATA(YES)`, `DUMP(NO)`, `TRACE(NO)`, `STORAGECLEAR(YES)`, and `CEDF(NO)`; bind the listener to `IPADDRESS(127.0.0.1)`; and treat `app/csd/CARDDEMOAPI.CSD` as the single source of truth — the `app/jcl/APICSDIN.jcl` inline `DFHCSDUP` SYSIN mirrors it attribute-for-attribute and defines no VSAM `FILE` resources. | Let the JCL keep its own (drifted) definitions; enable dumps/traces/EDF for easier debugging; redefine the VSAM files inside the API group. | Tokens, credentials, and PAN data flow through these tasks, so dump/trace/EDF capture and storage retention are suppressed and task data marked confidential; loopback binding limits reachability; keeping the JCL byte-identical to the CSD removes install-time drift; the eight read-only files are installed region-wide by the legacy `CARDDEMO` group, and redefining them in the API group would replace the active write-capable definitions the 3270 flows depend on (violating the additive-only mandate). | Suppressing dump/trace reduces first-failure diagnostics, so operators must enable `CETR`/auxtrace deliberately when debugging; the reconciliation is verified by a structural diff of the JCL SYSIN against `CARDDEMOAPI.CSD`. |

### D26 — Defensive best-effort `ENDBR` on the `COTRSVCC` STARTBR-ENDFILE path

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| In `COTRSVCC`, give the `STARTBR`-`ENDFILE` branch of both browse paragraphs (`3100-RESOLVE-CARDS` over `CXACAIX` and `3200-BROWSE-TRANS` over `TRANSACT`) an explicit best-effort `EXEC CICS ENDBR` whose `RESP` is deliberately not tested, mirroring the `NORMAL`-path browse release. | Leave the branch as `CONTINUE` and rely on the implicit browse release at pseudo-conversational task end; or issue the `ENDBR` and test its `RESP`, failing the inquiry with HTTP 500 on any non-`NORMAL` response. | Standard CICS `STARTBR` does not raise `ENDFILE`, so this is a defensive branch; if it is ever reached, a browse may not actually be in progress and `ENDBR` can legitimately return `INVREQ`. Issuing `ENDBR` with `RESP` (which suppresses the abend) and not consulting it yields symmetric, self-documenting browse cleanup without converting a benign no-browse condition into a spurious 500. Leaving `CONTINUE` left the `NORMAL` and `ENDFILE` paths visually asymmetric, and testing the `RESP` would wrongly turn a harmless `INVREQ` into a 500. | The best-effort `ENDBR` on a path where no browse exists returns `INVREQ` that is intentionally swallowed; this is harmless because `RESP` prevents an abend and the caller keys off the `WS-BROWSE-ERROR` flag, not `WS-RESP-CD`. The change is read-only (`ENDBR` is not a write verb) and preserves the 200-empty / 404 / 500 contract. Full in-region confirmation requires a z/OS/CICS region, the same constraint recorded in D13. |

### D27 — Executive-presentation deck CDN dependency

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Deliver `docs/executive-summary.html` as one self-contained HTML file whose three presentation libraries — reveal.js 5.1.0, Mermaid 11.4.0, and Lucide 0.460.0 — plus the Inter, Space Grotesk, and Fira Code fonts load at view time from public CDNs (`cdn.jsdelivr.net` for the libraries; `fonts.googleapis.com`/`fonts.gstatic.com` for the fonts), with every version pinned; the Blitzy theme is embedded inline and nothing is installed on z/OS. | Vendor (download and embed) every library and font into the repository; produce a bundled asset through a Node build toolchain; host the presentation assets on the mainframe beside the API. | The Executive Presentation rule mandates a single self-contained file with no build step and CDN versions pinned to exactly these releases. The deck is a leadership artifact opened in a browser, wholly separate from the CICS/VSAM runtime, so a view-time CDN load keeps it small, reproducible, and free of any host dependency, while pinning removes version drift and makes the render deterministic. | The deck needs outbound access to the pinned CDNs when it is opened; offline it degrades observably (assets fail to load, with no silent fallback) rather than rendering. This is accepted because the deck makes zero API/CICS calls and sends zero application data to any external origin, so a CDN failure cannot affect or corrupt the API tier; an operator who must present offline vendors the pinned assets locally. |

### D28 — Router last-resort abend trap for the uniform error contract

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Arm `EXEC CICS HANDLE ABEND LABEL(9999-ABEND-HANDLER)` in `COAPIRTR` after the requestId is built; the handler cancels further abend handling, then serializes and sends the canonical `{error:{code,message,requestId}}` envelope with HTTP 500 before returning. | Rely on CICS default abend handling (a non-JSON baseline 500); add `HANDLE ABEND` to every service program instead of the single router; re-raise the abend after logging. | The AAP deterministic error contract requires one uniform JSON envelope and a fixed status map for every failure. The router already returns that envelope for RESP-detected conditions, but a genuine task abend inside a linked service (for example a data exception on a malformed VSAM record) bypassed it and let CICS return a non-JSON baseline 500. Trapping the abend at the single front-door program completes the contract in one place and mirrors the established `COACTVWC` `HANDLE ABEND LABEL` pattern. | If serialization or `WEB SEND` inside the handler itself abends, the cancelled trap lets CICS fall back to its baseline 500 — the same outcome as before, with no loop. Because every API path is read-only, CICS has already backed out the empty unit of work and freed task-scoped browse, storage, and enqueue resources, so no data or resource leak results. |
### D29 — Executive-deck mobile scroll-view fit (`minScale`)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Add `minScale: 0.1` to the reveal.js `Reveal.initialize(...)` call in `docs/executive-summary.html`, leaving the AAP-specified `hash`, `transition`, `controlsTutorial`, `width: 1920`, and `height: 1080` unchanged. | Accept reveal's default mobile scroll-view degradation and leave `minScale` at its `0.2` default (the deck targets desktop/projector leadership and the AAP is silent on mobile); override `scrollActivationWidth` to suppress reveal's narrow-width scroll view; author a separate mobile stylesheet or a duplicate narrow layout. | With the AAP-mandated fixed `width: 1920`, reveal's default `minScale: 0.2` clamps the natural `375/1920` (about `0.195`) scale up to `0.2` at sub-435px widths, so each 1920-wide slide renders about 384px inside a 375px viewport and a roughly 4px edge sliver — leading heading glyphs and full-width table/diagram outer borders — is clipped by `overflow-x: hidden`. Lowering only `minScale` to `0.1` lets the natural scale apply so the slide fits exactly (measured 360px, centered, no clipped content). It is a single additive option that changes none of the AAP-specified reveal values, preserves reveal's recommended narrow-width scroll view instead of fighting the framework, and leaves every standard viewport untouched because their scales (`0.96`, `0.72`, `0.512`, `0.384`) all exceed `0.2` and never reach the floor. | Only extremely narrow viewports well below the tested breakpoints (under roughly 192px) could scale text below comfortable legibility; the deck's audience is desktop/projector and mobile is best-effort. No AAP-specified value changes, and all four standard viewports and the Mermaid/Lucide/font/gradient fidelity are verified unchanged. |
### D30 — Optional test dependencies and the `RELEASE_MODE` strict gate

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Treat PyYAML and jsonschema as optional imports in `openapi-contract-test.py`: when absent the test prints one `SKIP` line and exits `0`, while `RELEASE_MODE=1` promotes a missing dependency (or a missing spec) to a `FAIL` (exit `1`); the pinned versions live in `app/test/api/requirements-test.txt`. | Make the two libraries mandatory so the test always hard-fails when they are absent; vendor the libraries into the repository; drop schema validation and hand-roll field checks. | A developer can run the quick check in a minimal environment without installing anything and without blocking a build, yet CI/release gets one environment variable that makes the same script authoritative, so a `SKIP` is never silently taken for a `PASS`; pinning the versions keeps the gate reproducible. | A default-mode `SKIP` (exit `0`) can be misread as a `PASS`; mitigated by documenting in onboarding Section 4 that the release gate requires `RELEASE_MODE=1` after `pip install -r app/test/api/requirements-test.txt` and that `SKIP` is not `PASS`. |

### D31 — Controlled-fault mechanisms in the test harness

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Exercise error paths through explicit, opt-in fault injection rather than by corrupting data: the shell harness drives the controlled HTTP-`500` check only when an operator supplies `FAULT_PATH` (unset skips it by default; `RELEASE_MODE=1` makes it mandatory and also requires `jq`), and `TSTTRAN` carries engineered fault cases — `7300-TEST-LIST-INTERNAL` (account `00000000096` forces an internal error returning code `INTERNAL` with no `RESP2` leakage), plus the defensive `7000-TEST-LIST-NO-CONTAINER` and `7100-TEST-LIST-BAD-LENGTH` ABEND checks and the `7200` truncation-flag check. | Trigger `500`s by mutating VSAM data or files; omit negative/error testing entirely; hard-wire a fault route that is always active. | Opt-in injection keeps the read-only, additive guarantees intact (no data is altered) and keeps the default run green where a fault route cannot be hosted, while still letting the release gate demand full error-path coverage; asserting code `INTERNAL` with no `RESP2` leakage verifies the deterministic error contract. | If an operator never supplies `FAULT_PATH` and never sets `RELEASE_MODE=1`, the `500` path goes unexercised; mitigated by making it mandatory under the release gate and by documenting the `FAULT_PATH` example in the harness and onboarding. |

### D32 — Test-driver execution limitations (region-only cases)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Ship the six COBOL drivers (`TSTAUTH`, `TSTACCT`, `TSTCUST`, `TSTCARD`, `TSTXREF`, `TSTTRAN`) as CICS-region artifacts that require a live z/OS / CICS region (installed transactions, LOADLIB, VSAM) to execute, and validate volume and token-lifecycle cases (50/51-card and 500/501-record boundaries, over-32 KB channel payloads, token expiry / collision / restart) in-region only, since they cannot be reproduced off-platform. | Stub CICS and VSAM to run the drivers off-platform; restrict testing to only what a Linux host can run; drop the volume and token cases. | The drivers issue real `EXEC CICS` LINK / WEB / file operations against installed resources, so faithful execution needs the region — off-platform stubbing would test the stub, not the contract; documenting the boundary while keeping the OpenAPI contract test and `shellcheck` runnable off-platform gives fast feedback without weakening coverage. | CI without a CICS region cannot run the drivers, so a driver regression could reach the region untested; mitigated by the off-platform contract test and `shellcheck` gates and by running the drivers through the region QA job `APITSTB.jcl` before release. |

### D33 — Executive-deck Mermaid 11.4.0 security advisories and CDN supply-chain integrity (SRI/CSP)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Keep the executive deck's Mermaid pinned at the mandated **11.4.0** and formally accept the six known Mermaid `<= 11.14.0` advisories (enumerated in the sub-table below) as **not reachable in this deliverable**, while recording each advisory, its fixed version (`11.10.0` or `11.15.0`), and the deliberate omission of Subresource Integrity (SRI) and a Content-Security-Policy (CSP) on the three CDN tags. | (a) Raise the Mermaid pin to `>= 11.15.0` to clear every advisory; (b) add `integrity=` / `crossorigin="anonymous"` SRI hashes to the pinned `cdn.jsdelivr.net` tags; (c) add a `<meta http-equiv="Content-Security-Policy">` restricting script and style origins; (d) set Mermaid `securityLevel: "sandbox"` to iframe-isolate every diagram; (e) vendor the libraries locally and drop the CDN. | The Executive Presentation rule freezes the deck's CDN versions to reveal.js 5.1.0, **Mermaid 11.4.0**, and Lucide 0.460.0 as a single self-contained file with no build step, so bumping the pin (a) or vendoring (e) would violate that frozen contract. Every one of the six advisories requires attacker-controlled or user-supplied diagram source or configuration; the deck renders only three static, author-authored diagrams (two `graph LR` and one `sequenceDiagram`), accepts no user input into `mermaid.run()`, and initializes at the default security level — `mermaid.initialize` with `startOnLoad:false` and `theme:"base"` and **no `securityLevel:"loose"`**. Five of the six advisories target diagram features absent from the deck (gantt, architecture-beta, and stateDiagram `classDef`), and the sixth fires only on user-supplied KaTeX sequence labels, which the fixed author content does not contain. The libraries run wholly client-side in a leadership browser, make zero API/CICS calls, and transmit zero application data, so a tampered CDN asset cannot reach or corrupt the read-only API tier. SRI/CSP (b, c) and `securityLevel:"sandbox"` (d) are deferred rather than applied because sandboxed iframes change the render/scale path the rule's verification depends on, and because pinned versions on a trusted, widely-mirrored CDN already give a deterministic render. | Residual supply-chain-integrity exposure: without an SRI hash, a compromised or man-in-the-middled CDN response could serve altered library bytes that the browser executes unverified; and remaining on 11.4.0 leaves the advisory code present even though no path is reachable here. Both are accepted for this leadership-only artifact — it is isolated from the runtime and holds no secrets — and are tracked as a hardening backlog item (add SRI/CSP, adopt Mermaid `>= 11.15.0`) in `docs/onboarding-api.md` for any future promotion of the deck to untrusted hosting or user-supplied-diagram use. |

The six advisories affecting Mermaid 11.4.0 — all fixed upstream, none reachable by this deck's static author-only diagrams:

| GHSA / CVE | Severity | Affected range | Fixed in | Vulnerable feature | Present in deck? | Reachable here? |
|---|---|---|---|---|---|---|
| GHSA-7rqq-prvp-x9jh / CVE-2025-54881 | Moderate | `>= 11.0.0-alpha.1, < 11.10.0` | `11.10.0` | Sequence-diagram KaTeX math labels flow to `innerHTML` (XSS) | `sequenceDiagram` present, but with no math labels | No — needs user-supplied math-label source; deck content is fixed and author-authored |
| GHSA-8gwm-58g9-j8pw / CVE-2025-54880 | Moderate | `>= 11.1.0, < 11.10.0` | `11.10.0` | Architecture-diagram icon text flows to d3 `html()` (XSS) | No architecture-beta diagram | No — feature absent |
| GHSA-6m6c-36f7-fhxh / CVE-2026-41150 | Moderate (CVSS 5.3) | `>= 11.0.0-alpha.1, <= 11.14.0` | `11.15.0` | Gantt `excludes` of all dates causes an infinite-loop DoS | No gantt diagram | No — feature absent |
| GHSA-87f9-hvmw-gh4p / CVE-2026-41159 | Moderate | `>= 11.0.0-alpha.1, < 11.15.0` | `11.15.0` | Config `fontFamily` / `themeCSS` / `themeVariables` allow CSS injection | Config is author-set in `mermaid.initialize()` | No — configuration is author-controlled, not attacker-supplied |
| GHSA-ghcm-xqfw-q4vr / CVE-2026-41149 | Moderate (CVSS 5.3) | `>= 11.0.0-alpha.1, < 11.15.0` | `11.15.0` | State-diagram `classDef` allows HTML injection (`<script>` stripped) | No stateDiagram / `classDef` | No — feature absent; `<script>` also stripped upstream |
| GHSA-xcj9-5m2h-648r / CVE-2026-41148 | Moderate (CVSS 5.3) | `>= 11.0.0-alpha.1, < 11.15.0` | `11.15.0` | State-diagram `classDef` allows CSS injection | No stateDiagram / `classDef` | No — feature absent |

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
