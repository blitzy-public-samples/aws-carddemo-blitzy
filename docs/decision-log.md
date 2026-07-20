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
| Keep the executive deck's Mermaid pinned at the mandated **11.4.0** and formally accept the six known Mermaid `<= 11.14.0` advisories (enumerated in the sub-table below) as **not reachable in this deliverable**, while recording each advisory and its fixed version (`11.10.0` or `11.15.0`). Subresource Integrity (SRI) and a Content-Security-Policy (CSP) were **subsequently applied** to the deck (see D40 for the concrete digests, CSP directives, and the ESM/`modulepreload` limitation); this row is retained as the Mermaid-advisory acceptance record and is reconciled with that applied state below. | (a) Raise the Mermaid pin to `>= 11.15.0` to clear every advisory; (b) add `integrity=` / `crossorigin="anonymous"` SRI hashes to the pinned `cdn.jsdelivr.net` tags; (c) add a `<meta http-equiv="Content-Security-Policy">` restricting script and style origins; (d) set Mermaid `securityLevel: "sandbox"` to iframe-isolate every diagram; (e) vendor the libraries locally and drop the CDN. | The Executive Presentation rule freezes the deck's CDN versions to reveal.js 5.1.0, **Mermaid 11.4.0**, and Lucide 0.460.0 as a single self-contained file with no build step, so bumping the pin (a) or vendoring (e) would violate that frozen contract. Every one of the six advisories requires attacker-controlled or user-supplied diagram source or configuration; the deck renders only three static, author-authored diagrams (two `graph LR` and one `sequenceDiagram`), accepts no user input into `mermaid.run()`, and initializes at the default security level — `mermaid.initialize` with `startOnLoad:false` and `theme:"base"` and **no `securityLevel:"loose"`**. Five of the six advisories target diagram features absent from the deck (gantt, architecture-beta, and stateDiagram `classDef`), and the sixth fires only on user-supplied KaTeX sequence labels, which the fixed author content does not contain. The libraries run wholly client-side in a leadership browser, make zero API/CICS calls, and transmit zero application data, so a tampered CDN asset cannot reach or corrupt the read-only API tier. SRI (b) and CSP (c) have since been applied to the deck (recorded in full under D40) because they add byte-integrity and origin-allow-listing at zero cost to the mandated render/scale path; only the Mermaid version bump (a)/vendoring (e) — which would violate the frozen version contract — and `securityLevel:"sandbox"` (d) — which changes the render/scale path the rule's verification depends on — remain deferred, since pinned versions on a trusted, widely-mirrored CDN already give a deterministic render. | Residual supply-chain exposure is now limited: the SRI digests added under D40 cause the browser to reject any altered library bytes (a compromised or man-in-the-middled CDN response no longer executes unverified), and CSP constrains the origins that scripts, styles, fonts, and connections may use. The remaining accepted residual is that staying on 11.4.0 leaves the advisory code present even though no path is reachable here, and that the dynamically-imported Mermaid ESM chunks are covered by version-pinning plus CSP rather than a per-chunk SRI hash (D40). Both are accepted for this leadership-only artifact — it is isolated from the runtime and holds no secrets — and adopting Mermaid `>= 11.15.0` is tracked as a hardening backlog item in `docs/onboarding-api.md` for any future promotion of the deck to untrusted hosting or user-supplied-diagram use. |

The six advisories affecting Mermaid 11.4.0 — all fixed upstream, none reachable by this deck's static author-only diagrams:

| GHSA / CVE | Severity | Affected range | Fixed in | Vulnerable feature | Present in deck? | Reachable here? |
|---|---|---|---|---|---|---|
| GHSA-7rqq-prvp-x9jh / CVE-2025-54881 | Moderate | `>= 11.0.0-alpha.1, < 11.10.0` | `11.10.0` | Sequence-diagram KaTeX math labels flow to `innerHTML` (XSS) | `sequenceDiagram` present, but with no math labels | No — needs user-supplied math-label source; deck content is fixed and author-authored |
| GHSA-8gwm-58g9-j8pw / CVE-2025-54880 | Moderate | `>= 11.1.0, < 11.10.0` | `11.10.0` | Architecture-diagram icon text flows to d3 `html()` (XSS) | No architecture-beta diagram | No — feature absent |
| GHSA-6m6c-36f7-fhxh / CVE-2026-41150 | Moderate (CVSS 5.3) | `>= 11.0.0-alpha.1, <= 11.14.0` | `11.15.0` | Gantt `excludes` of all dates causes an infinite-loop DoS | No gantt diagram | No — feature absent |
| GHSA-87f9-hvmw-gh4p / CVE-2026-41159 | Moderate | `>= 11.0.0-alpha.1, < 11.15.0` | `11.15.0` | Config `fontFamily` / `themeCSS` / `themeVariables` allow CSS injection | Config is author-set in `mermaid.initialize()` | No — configuration is author-controlled, not attacker-supplied |
| GHSA-ghcm-xqfw-q4vr / CVE-2026-41149 | Moderate (CVSS 5.3) | `>= 11.0.0-alpha.1, < 11.15.0` | `11.15.0` | State-diagram `classDef` allows HTML injection (`<script>` stripped) | No stateDiagram / `classDef` | No — feature absent; `<script>` also stripped upstream |
| GHSA-xcj9-5m2h-648r / CVE-2026-41148 | Moderate (CVSS 5.3) | `>= 11.0.0-alpha.1, < 11.15.0` | `11.15.0` | State-diagram `classDef` allows CSS injection | No stateDiagram / `classDef` | No — feature absent |

### D34 — Suppress the CICS Server product/version response header (MIN-03)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Suppress CICS's `Server:` product/version banner on API responses by setting the region-scope SIT parameter `HTTPSERVERHDR=NO`, and document it as an operator step in `docs/onboarding-api.md` with a pointer comment in `app/csd/CARDDEMOAPI.CSD`. It is deliberately NOT a `TCPIPSERVICE`/`URIMAP` attribute. | Leave the default `Server:` banner; strip the header per-response inside `COAPIRTR` via `WEB WRITE HTTPHEADER`; front the listener with a proxy that rewrites the header. | A version banner aids fingerprinting; `HTTPSERVERHDR=NO` is the supported region-wide switch and needs no code change, honoring the additive/no-code-edit intent. Per-response stripping is not possible because CICS appends its `Server:` header during `WEB SEND` after program control returns; a fronting proxy is out of scope for this loopback-only increment (see D7, D17). | Region-scope: the switch affects every CICS Web Support response in the region, not only the API, so it is documented as a deliberate operator decision rather than silently assumed. Runtime evidence: not executable in the Linux sandbox (no CICS region, CRIT-01); verified by SIT-parameter semantics and the CSD/onboarding cross-reference. |

### D35 — Token TSQ `EXPIRYINT` unit and duration (MAJ-05)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Keep `TSMODEL(CDAPITSM) EXPIRYINT(20)` unchanged and correct the surrounding documentation to state the unit is **minutes** (20 minutes) — not hours and not seconds. CICS rounds the interval up to the next 10-minute multiple, so 20 is the smallest multiple that safely outlives the 15-minute functional token life while still reclaiming idle token queues. | Lower to `EXPIRYINT(10)` to hug the token life more tightly; raise it to an hours-scale value; accept the finding's premise that the unit is hours and change the `DEFINE`. | The `EXPIRYINT` unit for a `TSMODEL` is minutes, and CICS rounds up to a 10-minute multiple, so `20` reclaims an idle token queue just after the 15-minute token expires without ever deleting a still-valid token. The `DEFINE` was already correct; the actual defect was documentation that implied hours, so the remediation is a documentation/comment accuracy fix reconciled identically across `CARDDEMOAPI.CSD` and `APICSDIN.jcl`, with no `DEFINE` change. | `EXPIRYINT` bounds only idle-queue reclamation; functional expiry (15 minutes) is enforced in `COAPISEC`, so a token is rejected well before its TSQ is deleted. Runtime evidence: TSQ expiry is not executable in the sandbox (no CICS, CRIT-01); the unit was confirmed against CICS TS 5.6 `TSMODEL` semantics. |

### D36 — Idempotent, self-healing CSD install (MAJ-06)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Make `app/jcl/APICSDIN.jcl` converge on every rerun: `REMOVE GROUP(CDEMOAPI)` from `&GRPLIST`, then `DELETE GROUP(CDEMOAPI)`, then (re)`DEFINE`+`ADD`; a conditional `CLNGRP` step runs only when the primary step fails with `RC > 4` to unlink and drop a partially-defined group. `check-csd-drift.sh` was updated so its verb regex accepts `DELETE`, `REMOVE`, `ADD`, and `LIST`. | `DELETE`-only (leaves a half-wired group linked in `&GRPLIST` after a mid-run failure); no cleanup step; require operators to hand-clean before every rerun. | `REMOVE`-before-`DELETE` guarantees a prior half-wired group is unlinked from the startup list before its definitions are dropped, so every rerun converges to a single clean copy; the `RC > 4`-gated `CLNGRP` undoes a genuine `DEFINE`/`ADD` failure so a broken group is never left installed at the next IPL. First-run "not found" (`RC = 4`) on `REMOVE`/`DELETE` is expected and harmless. Mirrors the rollback job `app/jcl/APICSDRB.jcl` (D19). | Correct behavior relies on the site `&GRPLIST` symbol being accurate; if it is wrong, `REMOVE` targets the wrong list. Runtime evidence: `DFHCSDUP` is not executable in the sandbox (CRIT-01); verified by `check-csd-drift.sh` (139/139), `shellcheck`, and JCL step-balance review. |

### D37 — RC-gated, all-driver test runner (MAJ-07)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Add `app/test/api/TSTRUNR.cbl` (an EXCI/`LINK` client that invokes all six COBOL service drivers and aggregates their verdicts) and `app/jcl/APITSTR.jcl` to run it, plus a shared 64-byte result copybook `COAPDRVY` that every driver and the runner share via `COPY ... REPLACING` so caller and callee never disagree on the wire format. Drivers set a single-byte verdict tested by 88-levels and fall back to pure backward-compatible behavior when `EIBCALEN = 0`. | Run each driver by hand and eyeball the output; a shell wrapper (cannot `LINK` into CICS); duplicate the result layout inside each driver (drift risk). | MAJ-07 requires one supplied automation that invokes and gates all six drivers. A single EXCI runner with a shared, `REPLACE`-copied result layout produces one release verdict and eliminates wire-format drift across the seven consumers. | The runner requires a live IBM CICS region with EXCI enabled and therefore cannot execute in the Linux sandbox (documented CRIT-01 limitation). Runtime evidence: not executable here; verified by structural review, copybook-consistency across all seven consumers, and `EXEC CICS` balance checks. |

### D38 — Disable reveal.js auto scroll-view on the executive deck (MAJ-17)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Set `scrollActivationWidth: 0` in the deck's `Reveal.initialize(...)` so reveal.js never auto-activates its narrow-width scroll-view; the deck's own `@media (max-width: 768px)` rules perform the portrait/mobile linear reflow while hash/scroll state stays in reveal's normal single-canvas model. | Accept reveal's default scroll-view (`scrollActivationWidth: 435`); author a separate duplicate mobile layout; rely on `minScale` alone (D29). | reveal.js 5.1's default scroll-view re-parents every `section` under a `.scroll-page-content` container and sets `.backgrounds` to `display:none`, which conflicted with the deck's `@media` rules (they target `.reveal .slides > section`) and produced a blank render below 435px. Disabling the auto scroll-view lets the deck's own responsive CSS reflow each slide into a readable vertical stack. | The deck opts out of reveal's built-in narrow scroll UX in favor of its own linear CSS; both paths were runtime-verified. Runtime evidence: VERIFIED in Chrome at 375, 768, 1280, and 1920 px (`isScrollView:false`, 16 direct sections, no blank render). |

### D39 — Deck `main` landmark via `role=main` on `.reveal`, not a `<main>` wrapper (MAJ-18)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Place `id="deck-main" role="main" aria-label=...` directly on the `.reveal` element (no wrapping `<main>`), and re-assert `role="main"` at the start of `enhanceA11y()` (which runs on reveal `ready` and every `slidechanged`) because reveal sets `role="application"` on `.reveal` at initialization. | Wrap `.reveal` in a `<main>` element; leave reveal's `role="application"`; set the landmark once and never re-assert. | reveal assigns `.reveal-viewport` to `.reveal`'s parent and resolves `.reveal`'s `height:100%` against it, so a wrapping `<main>` with no height collapsed `.reveal` to 0 px (a blank desktop render). Applying `role=main` to `.reveal` itself yields the identical landmark without disturbing that height contract, and re-asserting inside `enhanceA11y()` survives reveal's init/reconfigure overriding the role. Keyboard navigation is unaffected because reveal binds its key handlers to `document`. | Correctness depends on `enhanceA11y()` running on `ready` and `slidechanged`; verified it does. Runtime evidence: VERIFIED in Chrome (`revealRole:"main"`, `mainLandmarkCount:1`, height restored, keyboard navigation intact). |

### D40 — Deck CDN Subresource Integrity (SRI) and Content-Security-Policy (CSP) (MAJ-13)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Add SRI `integrity=` plus `crossorigin="anonymous"` to every pinned CDN `<link>`, `<script>`, and `<link rel="modulepreload">` tag, and add a restrictive `<meta http-equiv="Content-Security-Policy">`, while keeping the AAP-frozen versions (reveal.js 5.1.0, Mermaid 11.4.0, Lucide 0.460.0). The five SHA-384 digests are: reveal.css `sha384-YgvgKeZJykuztG+iQPoZHNZENTFZvOcOJ/uRzmJTje6GHYN9zAw+y5rx/L8MQKA/`; white.css `sha384-/TZMdZeq0ZGi4iePTHhOMXOkyv2f72ZcwmhAtOmSZlPkx+5qCOfyc/U1kjd5Su6S`; reveal.js `sha384-M/JtqCVlLcK9lwVnBnMXP0V577CBULkjodKsR/PITOe4MSKNbbwqCemEyluOw0+5`; lucide.min.js `sha384-ieG+IKD0d/ZPXyCBTMVAbqsQdns8QGJR/e26WMw7M4fkaI/rHcS/YIoi+ah9WGge`; mermaid.esm.min.mjs `sha384-HbZodwcydhFoeJkM8JD85LgJ4EGrGW8p7/jgYlNNjwawbrvYc5jzWjzs8Z8Lgsvo`. CSP is `default-src 'none'` with narrow per-directive allow-lists (script/style/font/img/connect scoped to `'self'`, `cdn.jsdelivr.net`, Google Fonts, and `data:`); `'unsafe-inline'` is retained for the inline Blitzy `<style>` and the inline reveal/mermaid init module. This supersedes D33's earlier deferral of SRI/CSP. | No SRI/CSP (the prior D33 stance); bump Mermaid to `>= 11.15.0` (violates the frozen version contract); vendor the libraries locally (violates the single-file, no-build, CDN-pinned contract); `securityLevel:"sandbox"` (changes the render/scale path the rule's verification depends on). | SRI makes the browser reject altered library bytes and CSP constrains the origins scripts, styles, fonts, and connections may use, and both preserve the mandated render/scale path (verified). The Mermaid ESM entry carries `integrity` on its `modulepreload`, but its dynamically-imported chunks cannot each carry a classic `integrity` attribute — those chunks are covered instead by version-pinning (jsDelivr serves immutable bytes per version) plus the CSP `script-src` origin allow-list. `'unsafe-inline'` is required for the single-file deck's inline theme and init module and does not weaken the SRI byte-integrity guarantee on the external files. Transitive dependencies pulled by Mermaid at runtime (DOMPurify, lodash-es, uuid) are version-pinned and origin-constrained by CSP; the six Mermaid 11.4.0 advisories remain accepted-as-unreachable per D33. | `'unsafe-inline'` permits inline script/style (needed for the single-file deck), and the dynamic Mermaid chunks rely on version-pin plus CSP rather than per-chunk SRI. An `npm audit` of the pinned deck graph reports 9 vulnerable nodes (8 moderate, 1 high): the moderate set corresponds to the six Mermaid 11.4.0 advisories accepted as unreachable per D33, and the single high-severity node is a transitive dependency (the deck's runtime transitive set is DOMPurify, lodash-es, and uuid) that this static, author-only deck never feeds untrusted input. All are accepted for a leadership-only, runtime-isolated artifact that holds no secrets, makes zero API/CICS calls, and transmits zero application data, and all are cleared by the backlog item to adopt Mermaid `>= 11.15.0`. Runtime evidence: VERIFIED in Chrome — 36 of 36 CDN requests returned HTTP 200 with zero CSP violations, zero SRI mismatches, and zero console errors across the full 16-slide sweep. |

### D41 — OpenAPI sign-on token schema length and character set (MAJ-04)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Define the sign-on token in `app/api/openapi.yaml` as a fixed 64-character string with pattern `^[0-9A-Z]{64}$` and a matching 64-character example, in a single schema node at `SignonData/properties/token` reused by the security scheme, so the contract mirrors `COAPISEC`'s issued token exactly. | Leave the token an unconstrained `string`; use a shorter length, a `format: byte`, or a JWT-shaped pattern; describe the token only in prose. | MAJ-04 requires the contract to match the implementation. `COAPISEC` issues a 64-character uppercase-alphanumeric opaque token, so the schema length, pattern, and example must mirror it to make the OpenAPI file an accurate oracle for the contract test and the response-schema validator (D42). | If `COAPISEC`'s token alphabet or length ever changes, the schema, the example, and `validate-response.py` must change together. Runtime evidence: VERIFIED — `openapi-spec-validator` valid, `yamllint` clean, contract test 102/102, and a deliberate 64→65 example mutation fails as expected. |

### D42 — Live response-schema validation in the HTTP test harness (MAJ-09)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Add `validate-response.py` and a `check_schema` helper to `run-api-tests.sh` so every asserted HTTP response body is validated against its OpenAPI schema for the returned status code (24 call sites), not merely checked for a status code and a substring. | Keep status-code-and-substring assertions only; validate a single happy-path response; hand-code field checks in bash. | MAJ-09 showed the harness could pass responses that violate the OpenAPI schemas. Driving `jsonschema` from the shipped `openapi.yaml` at every call site makes the harness reject any schema-violating body, closing the gap between "200 returned" and "200 with a contract-valid body." | Requires `jsonschema`/`PyYAML` (pinned in `requirements-test.txt`); `RELEASE_MODE` promotes their absence to `FAIL` (D30). Runtime evidence: VERIFIED end-to-end against a mock server (24 validated call sites; a deliberately malformed body is rejected). |

### D43 — Deterministic boundary/fault fixtures, loaders, and cleanup (MAJ-08)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Ship `gen-boundary-fixtures.py`, which generates 10 canonical byte-exact fixtures plus a `sha256` `MANIFEST.txt`, together with `load-boundary-fixtures.jcl`, `clean-boundary-fixtures.jcl`, and `prepare-boundary-fixtures.sh` carrying expected record counts. Fixtures load into CLONE datasets, never the base VSAM. | Reuse only the existing `app/data/ASCII` fixtures (no boundary/fault cases); hand-edit fixtures (non-reproducible); load into the base datasets (unsafe and contrary to the read-only intent). | MAJ-08 requires deterministic boundary/fault fixtures with loaders and cleanup. A generator with a `sha256` manifest makes the fixtures byte-reproducible; loading into clone DSNs keeps base data read-only and safe. The `CCXREF` fixture is authored 36 bytes wide and padded to the `DEFINE`'s 50-byte record on load — a documented tribal-knowledge gotcha now captured in onboarding. | Clone-DSN names must match the site, and the `CCXREF` 36→50 padding must be preserved by any fixture edit. Runtime evidence: VERIFIED — the generator's `--verify` reproduces 10 of 10 fixtures against the `sha256` manifest; JCL load itself is CICS-region-dependent (CRIT-01). |

### D44 — Standards-compliant sign-on JSON string parsing (MAJ-03)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Implement RFC 8259 string handling in `COAPIRTR`'s sign-on parser: accept the eight short escapes (`\"` `\\` `\/` `\b` `\f` `\n` `\r` `\t`) and `\uXXXX` unicode escapes via `STRESC`/`STRU` sub-states, and reject raw unescaped control characters (U+0000–U+001F) with HTTP 400. | A naive scan that treats `\"` as string-end (rejecting valid escaped quotes); accept raw control characters; introduce an external JSON parser. | MAJ-03 showed valid escapes were rejected and invalid raw controls accepted. A small sub-state machine makes the parser conform to the JSON string grammar with no external dependency, so legitimate credentials parse and malformed input is a deterministic 400. | A hand-rolled parser must track state precisely; covered by the edge-case harness. Runtime evidence: not executable under a CICS translator in the sandbox (CRIT-01); verified by structural review and edge-case enumeration. |

### D45 — Exact-route path consumption (MAJ-10)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Add `2060-VALIDATE-PATH` in `COAPIRTR` so each route requires its path to be fully consumed — surplus trailing segments or empty/missing required segments are rejected with HTTP 400 before authentication. | Prefix-match routes (surplus segments silently ignored); validate after authentication; tolerate empty segments. | MAJ-10 showed malformed surplus/empty path segments could resolve to a valid route. Requiring exact consumption pre-auth means paths such as `/accounts/123/extra` or `/accounts//` cannot masquerade as `/accounts/{acctId}`, and the 400 is returned before any credential or token work. | Stricter routing rejects a genuinely new sub-path until its route is registered — intended and acceptable. Runtime evidence: not executable in the sandbox (CRIT-01); verified by structural review and a route-table walk-through. |

### D46 — Per-outcome, requestId-correlated redacted operator telemetry (MAJ-11)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Add `6070-LOG-OUTCOME`, funneled from the single `6000` response path in `COAPIRTR`, to emit one structured, redacted operator log line per outcome (200/400/401/404/500/abend) carrying the requestId, method, route, and status — and never PAN, CVV, token, or credentials. | Log only errors; log nothing for 200/empty-list outcomes; log the full request/response (which would leak sensitive data). | MAJ-11 showed ordinary outcomes lacked requestId-correlated telemetry. Emitting one redacted line per outcome from the common funnel gives operators complete, correlatable traceability while honoring the security data-handling rule. | Log-sink delivery is region-dependent; redaction is enforced in code. Runtime evidence: not executable in the sandbox (CRIT-01); verified by structural review confirming redaction and single-funnel coverage of every outcome. |

### D47 — Capture RESP/RESP2 on security-header writes (MIN-02)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Add `6055-LOG-HDR-FAILURE` so the three `WEB WRITE HTTPHEADER` calls in `COAPIRTR` capture and log `RESP`/`RESP2` instead of ignoring them. | Ignore header-write failures (the original behavior); abend the task on any header-write failure. | MIN-02 showed the security-header writes ignored CICS failures. Capturing `RESP`/`RESP2` surfaces a failed header write to operators without aborting an otherwise-valid response. | A failed header write is logged rather than fatal, so the response still sends. Runtime evidence: not executable in the sandbox (CRIT-01); verified by structural review of the three call sites. |

### D48 — JSON response buffer sizing for the 50-entry transaction list (MAJ-12)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Size the shared JSON buffer `JB-DATA` at `X(131072)` (128 KiB) identically in `COJSONUC` and `COAPIRTR`, reconciling the cap so a legal 50-entry transaction list cannot overflow the previously fixed 96 KB buffer. | Keep 96 KB (`X(96000)`); compute an exact per-response bound; stream the payload in fragments. | MAJ-12 showed a legal 50-entry list could exceed 96 KB. 128 KiB safely bounds 50 `CVTRA05Y`-derived JSON records including masking and signed-decimal-scaling overhead, and keeping both declarations identical prevents the router and serializer disagreeing on capacity (a tracked cross-file coordination point). | The two buffer declarations must remain identical; documented as a coordination point. Runtime evidence: not executable under CICS in the sandbox (CRIT-01); verified by byte-size arithmetic and grep-confirmed identical `X(131072)` in both files. |

### D49 — Account service exit-scrub of sensitive work areas (MAJ-02)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Add `9000-SCRUB-SENSITIVE` in `COACSVCC`, mirroring `COCUSVCC`, invoked on every return path so financial work areas (balances, limits, cycle credit/debit) are cleared before the program returns. | Leave work areas as-is between invocations; rely on CICS storage reuse. | MAJ-02 showed the account service did not scrub sensitive financial work areas on exit. Because a pseudo-conversational program's storage can be reused, scrubbing on every exit path prevents residual balances or limits from leaking into a later invocation, matching the pattern already used in `COCUSVCC`. | Every future return path must call the scrub; centralizing it in one paragraph reduces that risk. Runtime evidence: not executable under CICS in the sandbox (CRIT-01); verified by structural review confirming the scrub is on all return paths and mirrors `COCUSVCC`. |

### D50 — Python test-tool virtual-environment bootstrap under PEP 668 (MAJ-15)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Document a `python3 -m venv` bootstrap for the test tooling and pin the tools in `app/test/api/requirements-test.txt` inside that venv, because the supported host is a PEP 668 externally-managed environment (Ubuntu / Python 3.13) on which a literal system `pip install` fails. | `pip install --break-system-packages` globally; document the raw failing system `pip` command (the finding); require a full container. | MAJ-15 showed the literal system `pip` command fails under PEP 668. A venv is the supported, non-destructive isolation that reaches a running, testable state without touching system packages, and it keeps the pinned tool versions reproducible for the release gate (D30). | Developers must activate the venv before running the harness and contract test; documented in onboarding. Runtime evidence: VERIFIED on this Ubuntu 25.10 / Python 3.13 host — the venv was created and `openapi-spec-validator`, `yamllint`, `jsonschema`, and `pyyaml` import and run. |

### D51 — Same-host/loopback delivery posture and distributed-access follow-up (MAJ-01)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Document that this increment delivers same-host/loopback access (`TCPIPSERVICE` binds `IPADDRESS(127.0.0.1)`) and that off-mainframe distributed access is an explicit, approved follow-up (TLS/keyring, RACF, and a routable bind or fronting proxy), aligning the objective and the deck/README/onboarding claims to what is actually delivered. | Claim distributed access is already delivered (inaccurate); bind a routable interface now without TLS/RACF (insecure and outside AAP scope). | MAJ-01 flagged the loopback listener against the distributed-access objective. The AAP scopes production security hardening (TLS/keyring/RACF) as a documented follow-up, so the correct remediation is to state the same-host posture honestly and record distributed access as backlog — not to ship an insecure routable listener. | Same-host delivery does not by itself satisfy a distributed-integration goal; the boundary is now stated explicitly so stakeholders see it. Runtime evidence: VERIFIED by inspection — `IPADDRESS(127.0.0.1)` in the CSD and aligned wording across README, onboarding, and the deck. |

### D52 — Transaction-list scan bound and performance posture (MIN-04)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Document the transaction-list access path and its explicit bounds — `COTRSVCC` caps scanned records at `WS-MAX-SCAN` (1,000,000), cards at `WS-MAX-CARDS` (50), and returned entries at `WS-MAX-TRANS` (50) — and record pagination plus a card/account-keyed read-only index as the performance backlog, because the AAP forbids adding a new index in this increment. | Add a card/account transaction index now (AAP §0.5.2 forbids it); impose no scan bound (unbounded read); claim an unproven SLO. | MIN-04 flagged that a list request can scan the whole file. The AAP explicitly excludes a new transaction index, so the in-scope remediation is a documented bound (`WS-MAX-SCAN`) plus an honest performance note and a pagination/index backlog item — not a schema change. | At high concurrency the bounded scan is still costly, and no live SLO could be measured without a CICS/VSAM region (CRIT-01). Runtime evidence: bounds verified by source inspection of the `WS-MAX-SCAN`/`WS-MAX-CARDS`/`WS-MAX-TRANS` constants; live SLO measurement deferred as documented. |

### D53 — Contract-test exact `maxItems: 50` drift detection (MIN-01)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| Add contract-test check #10 asserting the transactions array `maxItems` is exactly `50`, coupled to a `MAX_ACCOUNT_TRANSACTIONS = 50` constant, so any drift between the OpenAPI cap and the service cap fails the test. | Check only that `maxItems` exists; hard-code `50` in the test without coupling it to a constant; omit the check. | MIN-01 showed the contract test missed `maxItems` drift. Asserting the exact value against a shared constant ties the OpenAPI cap to the `COAPTRNY` `OCCURS 0 TO 50` and `COTRSVCC WS-MAX-TRANS` caps, so a one-sided change is caught before release. | The constant must track the real cap if it ever changes (cross-file coordination with the copybook and service). Runtime evidence: VERIFIED — mutating the spec to `51` fails the check while 102/102 pass at `50`. |


### D54 — Responsive wide-table handling on mobile viewports (MAJ-17)

| Decision | Alternatives considered | Rationale | Risk |
|---|---|---|---|
| On viewports `<=768px`, render `.blitzy-table` as `display:block; overflow-x:auto` so wide data tables (notably the three-column endpoints table) become horizontally swipeable instead of clipped. | (a) Wrap each `<table>` in an `overflow-x:auto` container div — needs HTML edits to both tables and risks disturbing the MAJ-18 `<caption>`/`th scope` structure; (b) force `table-layout:fixed` + `word-break:break-all` so three columns wrap into a `375px` width — cramped, with ugly mid-token wrapping of monospace paths; (c) leave as-is — clips the Purpose column (content loss under `html { overflow-x:hidden }`). | CSS-only and scoped to the existing `@media (max-width:768px)` block; column alignment is preserved by the browser's anonymous table box; the HTML is untouched so the MAJ-18 caption and `th scope` remain intact; desktop is unaffected. Discovered during the Phase-7 cross-cutting regression: at `375px` the endpoints table overflowed to `586px` and its Purpose column was unreachable. | Nested horizontal scroll within the vertical mobile scroll is a minor UX nuance, mitigated by it being the standard responsive-table pattern and by the executive target (`1920x1080`) being unaffected. Runtime evidence: VERIFIED — page horizontal overflow eliminated (`pageScrollW` `586` to `375`), table internally scrollable (`scrollWidth 560 > clientWidth 323`) so every column is reachable, desktop reverts to `display:table` with the full three-column render intact, console clean, 36/36 CDN 200. |


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
