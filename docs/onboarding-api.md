# CardDemo REST/JSON API — Onboarding & Continued Development

This guide takes a developer from a clean CardDemo checkout to the additive,
read-only REST/JSON inquiry API running on base CICS Web Support under
`/carddemo/api/v1`. It complements the repository [README](../README.md)
rather than repeating the base CardDemo installation, and it should be read
with the machine-readable [OpenAPI contract](../app/api/openapi.yaml).

The API adds new members only. Its service programs use `READ`, `STARTBR`,
`READNEXT`, and `ENDBR`; they do not issue VSAM `WRITE`, `REWRITE`, or
`DELETE` commands.

## Prerequisites

- Access to the z/OS system and CICS TS 5.6 region `CICSAWSA`.
- The base CardDemo application installed and its VSAM datasets loaded by
  following the README.
- The `AWS.M2` high-level qualifier and authority to submit compile JCL.
- Authority to run `DFHCSDUP`, use `CEDA`, and inquire on CICS resources.
- TCP/IP enabled for the region and an approved listener port. The supplied
  CSD uses port `3001`; confirm that it is free before installation.
- API COBOL sources staged in `AWS.M2.CARDDEMO.CBL` and the seven API
  copybooks staged in `AWS.M2.CARDDEMO.CPY`.

The eight runtime programs are `COJSONUC`, `COAPISEC`, `COACSVCC`,
`COCUSVCC`, `COCRSVCC`, `COXRSVCC`, `COTRSVCC`, and `COAPIRTR`. The seven
copybooks are `COAPICOM`, `COAPSGNY`, `COAPACTY`, `COAPCUSY`, `COAPCRDY`,
`COAPXRFY`, and `COAPTRNY`.

## 1. Build the API programs

Submit [`app/jcl/APIBUILD.jcl`](../app/jcl/APIBUILD.jcl). It sets
`HLQ=AWS.M2`, obtains `BUILDONL` from
`AWS.M2.CARDDEMO.PRC.UTIL`, and compiles and link-edits these members in
dependency-readable order:

1. `COJSONUC`
2. `COAPISEC`
3. `COACSVCC`
4. `COCUSVCC`
5. `COCRSVCC`
6. `COXRSVCC`
7. `COTRSVCC`
8. `COAPIRTR`

`BUILDONL` reads `AWS.M2.CARDDEMO.CBL`, resolves copybooks from
`AWS.M2.CARDDEMO.CPY`, and writes load modules to
`AWS.M2.CARDDEMO.LOADLIB`. The final SDSF step issues
`CEMT SET PROG(...) NEWCOPY` commands to `CICSAWSA`.
The supplied procedure targets Enterprise COBOL 6.3 and CICS TS 5.6.
CICS TS 5.6 supports native `TRANSFORM DATATOJSON`/`JSONTODATA`;
`COJSONUC` remains the in-repository serializer/parser fallback.

```text
SUBMIT 'AWS.M2.CARDDEMO.JCL(APIBUILD)'
```

Confirm every `BUILDONL` step completes with an acceptable return code and
that all eight load modules exist. On a first installation, the `NEWCOPY`
commands can precede the corresponding installed PROGRAM resources. After
Section 2, rerun the `NEWCOPY` step or resubmit the job so all eight PROGRAM
resources reference the new load modules.

## 2. Define and install the CICS resources

Submit [`app/jcl/APICSDIN.jcl`](../app/jcl/APICSDIN.jcl). Its first step
runs `DFHCSDUP` and inlines the definitions from
[`app/csd/CARDDEMOAPI.CSD`](../app/csd/CARDDEMOAPI.CSD). Its second step
requests `CEDA INSTALL GROUP(CDEMOAPI)` in `CICSAWSA`.

```text
SUBMIT 'AWS.M2.CARDDEMO.JCL(APICSDIN)'
```

The descriptive file name is `CARDDEMOAPI.CSD`, but the installed CICS group
is `CDEMOAPI` because CICS group names are limited to eight characters. The
existing `CARDDEMO` group is not edited.

The new group contains:

- `TCPIPSERVICE(CDAPISVC)` with `PORTNUMBER(3001)`, `PROTOCOL(HTTP)`,
  `SSL(NO)`, and `AUTHENTICATE(NO)`.
- `URIMAP(CDAPIURI)` for `/carddemo/api/v1/*`.
- Alias `TRANSACTION(CAPI)` and router `PROGRAM(COAPIRTR)`.
- Eight API PROGRAM definitions (the router, the security service, the five
  inquiry services, and the `COJSONUC` serializer).
- `TSMODEL(CDAPITSM)` backing the bearer-token temporary-storage queue.
- Six test-driver PROGRAM definitions (`TST*`) and their transactions
  (`TAUT`, `TACC`, `TCUS`, `TCRD`, `TXRF`, `TTRN`).

The group defines **no FILE resources**. The existing CARDDEMO VSAM files are
shared exactly as the base region installs them and are never redefined here;
read-only access is enforced in the service programs (which issue only
`READ`/`STARTBR`/`READNEXT`) and by region security — not by a duplicate FILE
definition that could override the base region's write-capable files. See
[`docs/decision-log.md`](decision-log.md) (D14).

Verify the install command in `CMDOUT`. Activate the group by adding
`CDEMOAPI` to the region `GRPLIST` so it installs at the next startup
(recommended), or run `CEDA INSTALL GROUP(CDEMOAPI)` from an authorized CICS
terminal. See [`docs/decision-log.md`](decision-log.md) (D15).

## 3. Enable CICS Web Support and TCP/IP

This is a region/operator action, not a source-code change.

1. Confirm the region starts with TCP/IP support enabled (`TCPIP=YES`).
2. Confirm port `3001` is approved and not already bound.
3. Inquire on `TCPIPSERVICE(CDAPISVC)` and verify `STATUS(OPEN)`.
4. Inquire on `URIMAP(CDAPIURI)` and verify `STATUS(ENABLED)`.
5. Verify `TRANSACTION(CAPI)` and all eight PROGRAM resources are enabled.

If the operator changes the listener port, update the CSD definition before
installing it and pass the same value as `API_PORT` to the test harness.

## 4. Smoke-test the installed API

### HTTP and contract tests

The HTTP harness and the supplied CSD both default to port `3001`, so no port
override is normally required. Pass `API_PORT` explicitly only when the
operator installed the listener on a different port:

```bash
cd app/test/api
API_HOST=<cics-host> \
API_PORT=3001 \
API_BASE=/carddemo/api/v1 \
API_USER=USER0001 \
API_PASS='<password>' \
./run-api-tests.sh
```

The script signs on, exercises all seven endpoints, checks documented
`200`/`400`/`401`/`404` behavior, verifies the valid empty-list case, and
runs response-body security checks. An operator-provided `FAULT_PATH` can
enable the controlled `500` check.

Run the local contract validator whenever the OpenAPI file or examples change:

```bash
python3 app/test/api/openapi-contract-test.py
```

A minimal manual sign-on and authenticated inquiry flow is:

```bash
curl -sS -X POST \
  -H 'Content-Type: application/json' \
  -d '{"userId":"USER0001","password":"<password>"}' \
  http://<cics-host>:3001/carddemo/api/v1/signon

curl -sS \
  -H 'Authorization: Bearer <token>' \
  http://<cics-host>:3001/carddemo/api/v1/accounts/00000000001
```

Never place a real password or bearer token in source control or shared logs.

### Build, register, and run `TSTTRAN`

The production `APIBUILD` member intentionally builds only the eight runtime
API programs. The six standalone COBOL drivers under `app/test/api/` are
built by the dedicated QA job
[`app/jcl/APITSTB.jcl`](../app/jcl/APITSTB.jcl), and their PROGRAM and
TRANSACTION definitions install with the API group through
[`app/jcl/APICSDIN.jcl`](../app/jcl/APICSDIN.jcl) — no manual `CEDA DEFINE`
is required. To run the drivers:

1. Transfer the six `app/test/api/TST*.cbl` members into
   `AWS.M2.CARDDEMO.CBL` (`TSTAUTH`, `TSTACCT`, `TSTCUST`, `TSTCARD`,
   `TSTXREF`, `TSTTRAN`).
2. Submit [`app/jcl/APITSTB.jcl`](../app/jcl/APITSTB.jcl) to compile, link,
   and `NEWCOPY` all six drivers into the CICS LOADLIB. See
   [`docs/decision-log.md`](decision-log.md) (D18).
3. Ensure `APICSDIN.jcl` has been run so the driver transactions are
   installed — `TAUT`, `TACC`, `TCUS`, `TCRD`, `TXRF`, and `TTRN`, driving
   `TSTAUTH` through `TSTTRAN` respectively.
4. Prepare the valid-but-cardless account fixture as described below.
5. Enter `TTRN` on a CICS terminal (the transaction for driver `TSTTRAN`).
6. Read the CICS region message output. A successful run reports seven PASS
   cases and ends with:

   ```text
   TSTTRAN RESULTS RUN=007 PASS=007 FAIL=000
   TSTTRAN RESULT: PASS
   ```

The seven cases are detail `200`, detail `404`, detail `400`, non-empty list
`200`, valid empty list `200`, missing-account list `404`, and malformed list
`400`.

### Load and restore the account-99 empty-list fixture

[`app/test/api/acctdata-empty.txt`](../app/test/api/acctdata-empty.txt)
contains one active 300-byte ACCTDAT record for account `00000000099`.
There is deliberately no corresponding `CCXREF` row. Load it only into a
disposable or backed-up test dataset:

1. Transfer the fixture in text mode to a fixed-block sequential dataset with
   `RECFM=FB,LRECL=300`, converting ASCII to the region's EBCDIC code page.
   The Unix line feed is a transfer delimiter, not part of the record.
2. Close and disable `ACCTDAT` using the site's normal maintenance procedure.
3. Back up `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`.
4. Use an authorized IDCAMS `REPRO` from the one-record staging dataset into
   the ACCTDAT KSDS. Verify key `00000000099` now reads successfully.
5. Re-enable/open `ACCTDAT`, and verify account `00000000098` is still absent.
6. Run `TSTTRAN` and the HTTP harness with
   `EMPTY_ACCT=00000000099`.
7. After testing, close/disable `ACCTDAT` and restore the backup. In a
   disposable region, rerunning the base `app/jcl/ACCTFILE.jcl` load from the
   unchanged shipped account data also returns the dataset to its baseline.

Do not add account `00000000099` to `CCXREF`; doing so would invalidate the
empty-list test.

### Region-only boundary matrix

Use cloned VSAM datasets in a disposable region for these volume cases:

| Scenario | Test data | Expected result |
|---|---|---|
| Card-resolution boundary | 50 cards for one account | List processing remains within the card table limit |
| Card-resolution overflow | 51 cards for one account | HTTP `500`, canonical internal error |
| Transaction-list boundary | 500 matching transactions | Response remains within the list-entry limit |
| Transaction-list overflow | 501 matching transactions | HTTP `500`, no partial success |
| Large response | Enough matches to exceed 32 KB | CHANNEL/CONTAINER succeeds; COMMAREA is not used |
| Money extrema | Valid maximum positive and negative amounts | Signed JSON strings retain exactly two decimal places |

Restore the cloned datasets after every boundary test.

## 5. Roll back / uninstall the API layer

To remove the API layer, submit
[`app/jcl/APICSDRB.jcl`](../app/jcl/APICSDRB.jcl). It reverses `APICSDIN.jcl`
and touches only the `CDEMOAPI` group — never the base `CARDDEMO` group.

1. Submit `APICSDRB.jcl`. Its DFHCSDUP step removes `CDEMOAPI` from the region
   `GRPLIST` and deletes the group's CSD definitions. The job is idempotent: a
   "not found" `RC=4` on a repeat run is expected and harmless.
2. Complete the rollback in the running region by one of:
   - **Restart pickup (recommended)** — because step 1 removed `CDEMOAPI` from
     `GRPLIST`, a normal region restart comes up without the group.
   - **Immediate discard** — from an authorized CICS terminal, close the
     listener with `CEMT SET TCPIPSERVICE(CDAPISVC) CLOSED`, then `CEMT
     DISCARD` the `TCPIPSERVICE`, `URIMAP`, `TRANSACTION`, `PROGRAM`, and
     `TSMODEL` resources (including the `TST*` drivers if installed).
3. **FILE restoration is not required.** Because `CDEMOAPI` defines no FILE
   resources, the rollback cannot have altered any CARDDEMO file. Only if a
   legacy pre-remediation install had redefined those files would you restore
   them with `CEDA INSTALL GROUP(CARDDEMO)` from an authorized terminal.

See [`docs/decision-log.md`](decision-log.md) (D19).

## Domain context

| Endpoint | CardDemo screen/transaction equivalent | Service | VSAM file(s) | Record copybook |
|---|---|---|---|---|
| `POST /carddemo/api/v1/signon` | `CC00` / `COSGN00C` sign-on | `COAPISEC` | `USRSEC` | `CSUSR01Y` |
| `GET /carddemo/api/v1/accounts/{acctId}` | `CAVW` / `COACTVWC` account view | `COACSVCC` | `ACCTDAT` | `CVACT01Y` |
| `GET /carddemo/api/v1/customers/{custId}` | Customer inquiry behind the CardDemo flows | `COCUSVCC` | `CUSTDAT` | `CVCUS01Y` |
| `GET /carddemo/api/v1/cards/{cardNum}` | `CCLI`/`CCDL` card list and view | `COCRSVCC` | `CARDDAT` | `CVACT02Y` |
| `GET /carddemo/api/v1/xref/{cardNum}` | Card-to-account/customer resolution | `COXRSVCC` | `CCXREF` | `CVACT03Y` |
| `GET /carddemo/api/v1/accounts/{acctId}/transactions` | `CT00` / `COTRN00C` transaction list | `COTRSVCC` | `ACCTDAT`, `CXACAIX`, `TRANSACT` | `CVACT01Y`, `CVACT03Y`, `CVTRA05Y` |
| `GET /carddemo/api/v1/transactions/{tranId}` | Transaction detail drill-down | `COTRSVCC` | `TRANSACT` | `CVTRA05Y` |

The programmatic equivalent of the `CCDL` to `CT00` drill-down first calls
`GET /carddemo/api/v1/xref/{cardNum}` to resolve the account, then calls
`GET /carddemo/api/v1/accounts/{acctId}/transactions`.

### VSAM record layouts

| Copybook | Record shape | API handling |
|---|---|---|
| `CVACT01Y` | 300-byte account; `ACCT-ID 9(11)`, status, signed `S9(10)V99` balances/limits, dates, group | Money serialized with sign and two decimal places |
| `CVACT02Y` | 150-byte card; card number, account id, security code, embossed name, expiry, status | PAN masked to last four; security code never serialized |
| `CVACT03Y` | 50-byte card xref; card number, customer id, account id | Used for read-only card-to-account/customer resolution |
| `CVCUS01Y` | 500-byte customer; id, name/address, SSN, government id, FICO score | SSN and government id minimized to masked last-four forms |
| `CVTRA05Y` | 350-byte transaction; id, `S9(09)V99` amount, card number, timestamps, merchant data | PAN masked; amount serialized as a signed two-decimal string |
| `CSUSR01Y` | User id, name, eight-byte password field, user type | Credentials are inbound-only and never returned |

## Common pitfalls

### COMMAREA size versus CHANNEL/CONTAINER

CardDemo commonly uses a 1024-byte COMMAREA, and CICS COMMAREAs have a 32 KB
ceiling. Single-record operations fit `COAPICOM` and its
`API-PAYLOAD X(1000)`. A transaction list can reach roughly 165 KB, so
`COTRSVCC` uses channel `CDEMOAPILISTCH` with containers `TRANLISTREQ`,
`TRANLISTRSP`, and `TRANLISTSTA`. Do not move the list back to COMMAREA.

### Signed implied-decimal money

Account money is `S9(10)V99`; transaction money is `S9(09)V99`. The decimal
point is implied and is not physically stored. Preserve the sign and scale and
emit JSON strings matching `^-?\d+\.\d{2}$`, for example `"194.00"`.
Treating these values as unscaled integers produces incorrect amounts.

### PAN, security code, and customer PII

Always emit card numbers in a `************1234` form. Never serialize a card
security code. Expose only masked last-four representations of customer SSN
and government-issued id, omit the EFT identifier, and never log full source
values.

### No account-keyed transaction index

`app/jcl/TRANIDX.jcl` defines `KEYS(26 304)`, which indexes
`TRAN-PROC-TS`, not card or account. The list service therefore proves the
account exists, resolves account-to-card relationships through `CXACAIX` /
`CCXREF`, browses `TRANSACT`, and filters by transaction card number in
application logic. A valid account with no cards or matches returns HTTP
`200` and an empty array, not `404`.

### Copybook name collisions

Service programs often copy both a VSAM record layout and an API response
copybook whose field names intentionally mirror one another. Qualify moves,
for example `ACCT-ID OF ACCOUNT-RECORD` and
`ACCT-ID OF API-ACCT-RESPONSE`, rather than relying on ambiguous names.

### Mainframe naming

COBOL members and CICS group names are limited to eight characters in this
design. The source file `CARDDEMOAPI.CSD` therefore installs group
`CDEMOAPI`. Keep program names, CSD definitions, JCL members, and route
dispatch names synchronized.

### Deterministic errors

The fixed status map is `200` (including empty list), `400`, `401`, `404`,
and `500`. Errors use `{error:{code,message,requestId}}`. Never expose CICS
`RESP2`, dataset names, stack details, credentials, or unmasked identifiers.

## Extending the API: adding a new endpoint

The following example is illustrative and is not shipped:
`GET /carddemo/api/v1/accounts/{acctId}/cards`.

1. Add request/response copybooks under `app/cpy/` using eight-character
   names. Keep data items LINKAGE-safe, mask sensitive fields, and use
   CHANNEL/CONTAINER if a list can exceed `API-PAYLOAD X(1000)`.
2. Add an eight-character service program under `app/cbl/`. Accept the key
   through `COAPICOM`, use only read-only VSAM commands, map to the response
   copybook, and set the deterministic API status/error contract.
3. Add the route to `COAPIRTR`: parse the path, validate the bearer token
   through `COAPISEC`, LINK the service, serialize the result, and issue
   `WEB SEND`.
4. Add the PROGRAM definition to `CARDDEMOAPI.CSD` in group `CDEMOAPI`. Reuse
   the shared CARDDEMO files as the base region installs them; do not redefine
   an existing FILE in this group. A genuinely new dataset (rare for a
   read-only inquiry) would be defined read-only and recorded in the decision
   log.
5. Add a `BUILDONL` step and `NEWCOPY` command to `APIBUILD.jcl`; rerun
   `APICSDIN.jcl` when CSD resources change.
6. Update `app/api/openapi.yaml`, add a COBOL driver under `app/test/api/`,
   and extend `run-api-tests.sh` and `openapi-contract-test.py`.
7. Record every non-trivial design choice in
   [`docs/decision-log.md`](decision-log.md).

## Suggested next tasks

These are out of scope for the current read-only increment but worthwhile:

- Enable TLS with a CICS keyring and `SSL(YES)` on `CDAPISVC`.
- Add RACF resource security, surrogate controls, and listener
  `AUTHENTICATE` hardening.
- Integrate an approved OAuth/OIDC provider.
- Add rate limiting, throttling, and operational monitoring.
- Add pagination and consider a card/account-keyed transaction index for
  production-scale transaction history.
- Add write/update endpoints only after authorization, audit, validation,
  recovery, and concurrency requirements are designed.

This API is the first read-only increment advancing the README roadmap item
“Exposure of transactions for distributed application integration.”
