# CardDemo REST/JSON API — Onboarding & Continued Development

This guide takes a developer from a clean CardDemo checkout to the additive,
read-only REST/JSON inquiry API running on base CICS Web Support under
`/carddemo/api/v1`. It complements the repository [README](../README.md)
rather than repeating the base CardDemo installation, and it should be read
with the machine-readable [OpenAPI contract](../app/api/openapi.yaml).

The API adds new members only. Its service programs use `READ`, `STARTBR`,
`READNEXT`, and `ENDBR`; they do not issue VSAM `WRITE`, `REWRITE`, or
`DELETE` commands.

Every step below runs **on the mainframe**: it submits JCL and issues
`DFHCSDUP`, `CEDA`, and `CEMT` commands against a live z/OS and CICS TS 5.6
region. These steps therefore require that region and its z/OS toolchain
(the CICS translator, Enterprise COBOL 6.3, the binder, IDCAMS, and SDSF);
they cannot be built, installed, or exercised from a non-z/OS checkout of the
source — for example a Linux or workstation clone used only for editing.
Confirm the prerequisites below before attempting Section 1.

## Prerequisites

Two environments are involved, and a clean checkout must satisfy both:

- The **mainframe** hosts and runs the API. You stage source there, compile
  it, and install the CICS resources.
- A **workstation** (or CI runner) with a clean repository checkout runs the
  OpenAPI contract test and the HTTP smoke-test harness against the deployed
  region.

Neither the API layer nor the mainframe runtime has any third-party
dependency (see [`docs/decision-log.md`](decision-log.md), D1); the
workstation tools listed below exist only to drive and validate the API and
never run on z/OS.

### Mainframe prerequisites

- Access to the z/OS system and a CICS TS 5.6 region (this guide uses the
  sample APPLID `CICSAWSA`).
- The base CardDemo application installed and its VSAM datasets loaded by
  following the [README](../README.md).
- Enterprise COBOL 6.3 and the CICS TS 5.6 translator/binder reachable from
  the `BUILDONL` procedure, plus authority to submit compile JCL under the
  `AWS.M2` high-level qualifier.
- Authority to run `DFHCSDUP`, use `CEDA`/`CEMT`, and inquire on CICS
  resources.
- TCP/IP enabled for the region (`TCPIP=YES`) and an approved listener port.
  The supplied CSD uses port `3001`; confirm it is free before installation.

### Site substitutions

Every command and JCL member in this guide uses the sample values below.
Substitute your site's values consistently before submitting anything. The
build JCL exposes `HLQ` as a `SET` symbol; the install/rollback JCL expose
`GRPLIST`, `SDFHLOAD`, and `DFHCSD` as `SET` symbols you edit in place.

| Placeholder | Sample value | Meaning |
|---|---|---|
| `CICSAWSA` | `CICSAWSA` | Target CICS region APPLID |
| `HLQ` / `AWS.M2` | `AWS.M2` | Source and load-library high-level qualifier |
| `GRPLIST` | `DFHLIST` | Region startup group list |
| `SDFHLOAD` | `OEM.CICSTS.V05R06M0.CICS.SDFHLOAD` | CICS load library for `DFHCSDUP` |
| `DFHCSD` | `OEM.CICSTS.DFHCSD` | Region CSD dataset to update |
| listener port | `3001` | `TCPIPSERVICE(CDAPISVC)` port |

The eight runtime programs are `COJSONUC`, `COAPISEC`, `COACSVCC`,
`COCUSVCC`, `COCRSVCC`, `COXRSVCC`, `COTRSVCC`, and `COAPIRTR`. The seven
copybooks are `COAPICOM`, `COAPSGNY`, `COAPACTY`, `COAPCUSY`, `COAPCRDY`,
`COAPXRFY`, and `COAPTRNY`. Staging the source is only the first step; you
must still compile it (Section 1) and install the CICS resources (Section 2)
before any endpoint responds.

### Workstation tooling for the tests

Run the contract test and HTTP harness from a clean checkout on a workstation
or CI runner that can reach the region. Required tools, with pinned versions:

- `bash` and `curl` — required by `run-api-tests.sh`; the script exits `2`
  when `curl` is absent.
- `jq` — optional by default, but **required** when `RELEASE_MODE=1` (strict
  JSON field extraction). Install it from your OS package manager.
- Python 3.9 or later for `openapi-contract-test.py`, with the pinned
  packages in
  [`app/test/api/requirements-test.txt`](../app/test/api/requirements-test.txt)
  (`PyYAML==6.0.3`, `jsonschema==4.26.0`, and their pinned transitives):

  ```bash
  python3 -m pip install -r app/test/api/requirements-test.txt
  ```

- Optionally `openapi-spec-validator==0.9.0` and `yamllint==1.38.0` to lint
  `app/api/openapi.yaml` directly.

For a strict, reproducible gate always run the contract test with
`RELEASE_MODE=1` (see [Section 4](#4-smoke-test-the-installed-api)). Without
it the test SKIPs and exits `0` when a pinned dependency or the spec file is
missing, which does not prove the contract.

### Stage the new API source members

Section 1 onward assumes the new API members already exist in the `AWS.M2`
source partitioned datasets. Because the feature is additive, upload the new
members into the **existing** CardDemo source PDSes — the same datasets the
base install created under the README section *Installation on the mainframe*.
No new dataset is required if that base structure is already in place; a PDS
that is missing is allocated with the **same attributes the base CardDemo
source datasets use — `RECFM=FB`, `LRECL=80`, `DSORG=PO`** (the `FB`/`80`
rows in the README dataset table). Do not invent different attributes for the
API members.

Upload the following members before building. Members are ordinary text
source, so transfer them in **text mode** using `IND$FILE` (or your preferred
upload tool), letting the transfer convert ASCII to the region's EBCDIC code
page — exactly as the README directs for the CardDemo source folders. Do
**not** use binary mode for these members; binary transfer in the README
applies only to the pre-encoded EBCDIC VSAM *data* files, not to source.

| Target PDS (HLQ `AWS.M2`) | DCB | Repository folder | Members (upload with the file extension dropped) |
|---|---|---|---|
| `AWS.M2.CARDDEMO.CBL` | `RECFM=FB,LRECL=80` | `app/cbl/` | `COJSONUC`, `COAPISEC`, `COACSVCC`, `COCUSVCC`, `COCRSVCC`, `COXRSVCC`, `COTRSVCC`, `COAPIRTR` (the eight runtime programs) |
| `AWS.M2.CARDDEMO.CPY` | `RECFM=FB,LRECL=80` | `app/cpy/` | `COAPICOM`, `COAPSGNY`, `COAPACTY`, `COAPCUSY`, `COAPCRDY`, `COAPXRFY`, `COAPTRNY` (the seven API copybooks) |
| `AWS.M2.CARDDEMO.JCL` | `RECFM=FB,LRECL=80` | `app/jcl/` | `APIBUILD`, `APICSDIN`, `APICSDRB`, `APITSTB` (the four API jobs) |
| `AWS.M2.CARDDEMO.CBL` | `RECFM=FB,LRECL=80` | `app/test/api/` | `TSTAUTH`, `TSTACCT`, `TSTCUST`, `TSTCARD`, `TSTXREF`, `TSTTRAN` (the six QA drivers — needed only for Section 4) |

A repository file uploads to the like-named member with its extension removed:
`app/cbl/COAPIRTR.cbl` becomes member `COAPIRTR` in `AWS.M2.CARDDEMO.CBL`, and
`app/jcl/APIBUILD.jcl` becomes member `APIBUILD` in `AWS.M2.CARDDEMO.JCL`.

Staging order matters: the eight runtime programs and seven copybooks must be
present before Section 1 (the copybooks so `BUILDONL` can resolve them from
`AWS.M2.CARDDEMO.CPY`), and the four JCL members must be present so the
`SUBMIT 'AWS.M2.CARDDEMO.JCL(...)'` commands in Sections 1, 2, and 5 can find
them. The six `TST*` drivers are required only for the region-only QA runs in
Section 4 and may be deferred until then.

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

### Targeting a differently-named region

The final SDSF step of `APIBUILD.jcl` (and of the QA job `APITSTB.jcl`) issues
its `CEMT SET PROG(...) NEWCOPY` commands to region `CICSAWSA`. That region
name is written literally in the `/MODIFY CICSAWSA,'...'` lines, matching the
repository-wide convention of `samples/jcl/CICCMP.jcl` and the base
file-control jobs (`OPENFIL`, `CLOSEFIL`, and the file loaders), which name
the region the same way. It is deliberately kept literal rather than made a
JCL symbolic so these build jobs stay byte-for-byte consistent with that
shipped convention.

If your CICS region is **not** named `CICSAWSA`, change the region name in the
`/MODIFY` lines — the single point of edit — before submitting each job:

- In `app/jcl/APIBUILD.jcl`, the eight `/MODIFY CICSAWSA,'CEMT SET
  PROG(...) NEWCOPY'` lines.
- In `app/jcl/APITSTB.jcl`, the six equivalent `/MODIFY` lines for the
  `TST*` drivers.

A `NEWCOPY` sent to a mismatched region name is **benign**: the job's compile
and link-edit steps still build the load modules into `AWS.M2.CARDDEMO.LOADLIB`
successfully, and only the automatic refresh is skipped. The new modules are
then picked up the next time the region starts (Section 2 adds `CDEMOAPI` to
the startup group list `&GRPLIST`), or immediately by re-issuing `CEMT SET
PROG(...) NEWCOPY` from an authorized terminal on the correct region. No CSD
or program source needs to change to retarget the region.

## 2. Define and install the CICS resources

Submit [`app/jcl/APICSDIN.jcl`](../app/jcl/APICSDIN.jcl). It runs a single
`DFHCSDUP` step whose inline definitions are kept byte-consistent with
[`app/csd/CARDDEMOAPI.CSD`](../app/csd/CARDDEMOAPI.CSD) (the drift guard
[`app/test/api/check-csd-drift.sh`](../app/test/api/check-csd-drift.sh) fails
the build on any mismatch). In order the job `DELETE`s any prior `CDEMOAPI`
group, `DEFINE`s the group's resources, `ADD`s `CDEMOAPI` to the startup
group list `&GRPLIST` (default `DFHLIST`), and `LIST`s the group. On a clean
first run the `DELETE` reports "group not found" (`RC=4`), which is expected
and harmless. The job is modeled on `app/jcl/CBADMCDJ.jcl`.

```text
SUBMIT 'AWS.M2.CARDDEMO.JCL(APICSDIN)'
```

The job is **not** blindly idempotent, so a rerun needs care. `DFHCSDUP
DELETE GROUP` drops the group's resource definitions but does **not** remove
the group name from `&GRPLIST`; the later `ADD GROUP(CDEMOAPI)
LIST(&GRPLIST)` then appends the name again, so re-running `APICSDIN` against
a region where the group is already listed can leave a **duplicate**
`CDEMOAPI` entry in the startup list. Reinstall with the ownership-safe
sequence instead:

1. **Verify ownership first.** Confirm the target `CDEMOAPI` group belongs to
   this feature and not to an unrelated pre-existing group of the same name —
   inspect it with `DFHCSDUP LIST GROUP(CDEMOAPI)` (or `EXTRACT`) and back up
   its definitions before deleting if there is any doubt.
2. **Remove the list link before re-adding.** Run
   [`app/jcl/APICSDRB.jcl`](../app/jcl/APICSDRB.jcl) first; its `REMOVE
   GROUP(CDEMOAPI) LIST(&GRPLIST)` followed by `DELETE GROUP(CDEMOAPI)`
   cleanly unlinks and drops the group. Then submit `APICSDIN` so the `ADD`
   leaves exactly one list entry. (Equivalently, issue a manual `REMOVE
   GROUP(CDEMOAPI) LIST(&GRPLIST)` before rerunning `APICSDIN`.)
3. **Check every return code and stop on the unexpected.** `RC=4` "not found"
   is expected only for a `DELETE`/`REMOVE` against an already-absent group or
   link; any `RC` above 4 on a `DEFINE` or `ADD` must halt the procedure so a
   partial install is never left behind.
4. **Confirm single membership.** Read the job's `LIST GROUP(CDEMOAPI)` plus a
   `LIST LIST(&GRPLIST)`, and verify `CDEMOAPI` appears exactly once before
   bringing the group online.

`DFHCSDUP` has no `INSTALL` verb and `CEDA` is a terminal transaction, so this
job intentionally does **not** install the group online; bringing the
resources online is a separate operator action described at the end of this
section.

The descriptive file name is `CARDDEMOAPI.CSD`, but the installed CICS group
is `CDEMOAPI` because CICS group names are limited to eight characters. The
existing `CARDDEMO` group is not edited.

The new group contains:

- `TCPIPSERVICE(CDAPISVC)` with `PORTNUMBER(3001)`, `PROTOCOL(HTTP)`,
  `IPADDRESS(127.0.0.1)` (loopback bind), `SSL(NO)`, and `AUTHENTICATE(NO)`.
- `URIMAP(CDAPIURI)` for `/carddemo/api/v1/*`.
- Alias `TRANSACTION(CAPI)`, driven by the CICS Web Support web-attach
  program `DFHWBA`, which links router `PROGRAM(COAPIRTR)`. The alias carries
  `STORAGECLEAR(YES)`, `DUMP(NO)`, `TRACE(NO)`, `CONFDATA(YES)`, `RESSEC(NO)`,
  and `CMDSEC(NO)`; the six driver transactions carry the same first four
  attributes; and every API and driver `PROGRAM` carries `CEDF(NO)`.
  `CONFDATA(YES)` with `DUMP(NO)`/`TRACE(NO)` plus `CEDF(NO)` keep tokens,
  credentials, and PAN data out of dumps, traces, and the EDF debugger, and
  `STORAGECLEAR(YES)` clears freed task storage (see
  [`docs/decision-log.md`](decision-log.md), D16). `RESSEC(NO)`/`CMDSEC(NO)`
  mean CICS resource- and command-level (RACF) security is deliberately not
  applied in this increment (see [`docs/decision-log.md`](decision-log.md),
  D24).
- Eight API `PROGRAM` definitions (`COAPIRTR`, `COAPISEC`, `COACSVCC`,
  `COCUSVCC`, `COCRSVCC`, `COXRSVCC`, `COTRSVCC`, `COJSONUC`).
- `TSMODEL(CDAPITSM)` bounding the `AT`-prefixed token-registry TSQs
  (`LOCATION(MAIN)`, `RECOVERY(NO)`, `EXPIRYINT(20)`).
- Six test-driver `PROGRAM`/`TRANSACTION` pairs (`TSTAUTH`/`TAUT`,
  `TSTACCT`/`TACC`, `TSTCUST`/`TCUS`, `TSTCARD`/`TCRD`, `TSTXREF`/`TXRF`,
  `TSTTRAN`/`TTRN`) for the region-only boundary tests.

The group defines **no FILE resources**. The existing CARDDEMO VSAM files are
shared exactly as the base region installs them and are never redefined here,
so this group cannot override the base region's write-capable file
definitions (see [`docs/decision-log.md`](decision-log.md), D14). Read-only
access is enforced **only at the application level**: the service programs
issue exclusively `READ`/`STARTBR`/`READNEXT`/`ENDBR` and never a write verb.
Because the alias runs with `RESSEC(NO)`/`CMDSEC(NO)`, CICS/RACF resource
security does **not** independently block a write, so the read-only guarantee
rests on the program logic (and the contract tests that verify it); enabling
`RESSEC`/`CMDSEC` with RACF profiles is a documented hardening follow-up (see
[`docs/decision-log.md`](decision-log.md), D24).

Verify the `DFHCSDUP` `DEFINE`/`ADD` results and the `LIST GROUP(CDEMOAPI)`
output in the job's `SYSPRINT`/`OUTDD`. Then bring the group online by one of:
adding `CDEMOAPI` to the region `GRPLIST` so it installs at the next startup
(the `DFHCSDUP` step above already does this — recommended), or running
`CEDA INSTALL GROUP(CDEMOAPI)` from an authorized CICS terminal (equivalently
via the CMCI/SPI). See [`docs/decision-log.md`](decision-log.md) (D15).

## 3. Enable CICS Web Support and TCP/IP

This is a region/operator action, not a source-code change.

1. Confirm the region starts with TCP/IP support enabled (`TCPIP=YES`).
2. Confirm port `3001` is approved and not already bound.
3. Inquire on `TCPIPSERVICE(CDAPISVC)` and verify `STATUS(OPEN)`.
4. Inquire on `URIMAP(CDAPIURI)` and verify `STATUS(ENABLED)`.
5. Verify `TRANSACTION(CAPI)`, the eight API `PROGRAM` resources, and
   `TSMODEL(CDAPITSM)` are enabled.

Port `3001` is an install-time default, not an assumption baked into the
programs; do not assume it is free. If the operator changes the listener port,
it must be changed in **two byte-identical places** and kept in sync, because
the CSD `PORTNUMBER` is a fixed resource attribute that cannot use a JCL
symbolic: update `PORTNUMBER(...)` in
[`app/csd/CARDDEMOAPI.CSD`](../app/csd/CARDDEMOAPI.CSD) **and** the matching
`PORTNUMBER(...)` in the inline `DEFINE` stream of
[`app/jcl/APICSDIN.jcl`](../app/jcl/APICSDIN.jcl) before installing. The drift
guard [`app/test/api/check-csd-drift.sh`](../app/test/api/check-csd-drift.sh)
fails the build if the two diverge, so change both to the same value. Then
pass the same port as `API_PORT` to the test harness (Section 4).

### Network exposure and transport (loopback-only)

The listener binds to `IPADDRESS(127.0.0.1)`, so it accepts connections only
from the same z/OS image and is **not** reachable from an off-mainframe
distributed client as installed (see
[`docs/decision-log.md`](decision-log.md), D17). This increment therefore
delivers a **same-host** inquiry API only. The off-mainframe distributed
access in the README roadmap is **not delivered here** — it is future work
that requires the fronting component below, and it is not exercised or claimed
by this increment. Do not describe the API as distributed-ready until that
component is in place and tested.

The loopback bind is deliberate. Transport is cleartext HTTP with
`AUTHENTICATE(NO)`, and the card/xref endpoints carry a full PAN in the
request URI path (see [`docs/decision-log.md`](decision-log.md), D21). Do
**not** widen `IPADDRESS`, enable a routable interface, or forward the port to
distributed clients until a TLS-terminating, PAN-redacting reverse proxy or
API gateway — plus RACF and an OAuth/OIDC or equivalent authorization layer —
is placed in front of the region. These items are tracked under *Suggested
next tasks*.

For same-host testing, reach the API only over the loopback address from the
region's own z/OS image (for example an on-host session or a co-located
client), using the sign-on and inquiry sequence in
[Section 4](#4-smoke-test-the-installed-api). A workstation harness must run
on, or tunnel to, that same host.

### Token registry limitations

Bearer tokens are short-lived and region-local by design:

- **Lifetime.** A token is valid for 15 minutes from issue; after that
  `COAPISEC` rejects it and the caller must sign on again.
- **Region-local.** The registry is a `LOCATION(MAIN)` Temporary Storage
  Queue set (the `AT` prefix, `TSMODEL(CDAPITSM)`). Tokens are not shared
  across CICS regions or a sysplex, so a token minted in one region is not
  valid in another.
- **Restart-invalidated.** `MAIN` TSQs are non-recoverable, so every token is
  discarded on a region restart or `CICS` cold/warm start; clients must
  re-authenticate afterwards.
- **Bounded lifecycle.** `EXPIRYINT(20)` reaps never-re-presented queues, so
  abandoned tokens do not accumulate.

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
enable the controlled `500` check. For a strict run set `RELEASE_MODE=1`,
which makes `jq` a required dependency and enforces precise JSON-field
assertions; the script exits `0` when every check passes, `1` on any failure,
and `2` when a required dependency is missing (`curl`, or `jq` under
`RELEASE_MODE`).

Run the local contract validator whenever the OpenAPI file or examples change.
A bare run may **SKIP** (and still exit `0`) when the optional validation
dependencies are absent, so a SKIP must never be read as a PASS. For a release
gate, install the pinned dependencies (see [Prerequisites](#prerequisites)) and run in strict mode: `RELEASE_MODE=1`
promotes a missing dependency or spec from a SKIP to a **FAIL** (exit `1`).

```bash
# Quick local check — may SKIP and still exit 0 in a minimal environment.
python3 app/test/api/openapi-contract-test.py

# Release gate — SKIP is promoted to FAIL (exit 1) on any missing dependency
# or spec, so a clean exit 0 here means the contract genuinely passed.
python3 -m pip install -r app/test/api/requirements-test.txt
RELEASE_MODE=1 python3 app/test/api/openapi-contract-test.py
```

`RELEASE_MODE=1` is required for a trustworthy result. In the default mode the
test prints `SKIP` and exits `0` when `PyYAML`, `jsonschema`, or the spec file
is absent, which does not prove the contract; under `RELEASE_MODE=1` a missing
dependency or spec is a hard `FAIL` (exit `1`). A complete pass ends with
`SUMMARY: checks=101 passed=101 failed=0`.

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
Likewise, keep real passwords, tokens, and full PANs out of your shell history
and out of any screenshots. Avoid typing a real secret directly on the command
line: read it from a prompt or a protected file (for example
`read -rs API_PASS`), or prefix the command with a leading space when
`HISTCONTROL=ignorespace`/`HISTIGNORE` is set so the line is not saved. Before
capturing a screenshot or screen recording of a terminal or client, replace any
real credential or PAN with a placeholder.

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
6. Read the CICS region message output. A successful run reports eleven PASS
   cases and ends with:

   ```text
   TSTTRAN RESULTS RUN=011 PASS=011 FAIL=000 SKIP=000
   TSTTRAN RESULT: PASS
   ```

   The driver raises a non-zero CICS return code so an automated run can gate
   on it: `8` if any case fails; otherwise `4` if a release-blocking fixture
   is absent (reported as `TSTTRAN RESULT: PASS (WITH SKIPS)`); otherwise `0`.

The eleven cases are detail `200`, detail `404`, detail `400`, non-empty list
`200`, valid empty list `200`, missing-account list `404`, malformed list
`400`, missing request container, bad container length, truncated list
(`truncated=true`, HTTP `200`), and list internal error (HTTP `500`).

Every driver publishes its outcome through `RETURN-CODE` so a batch, EXCI, or
started-transaction harness can gate on it. The convention is shared by all six
drivers:

- `0` — all cases passed.
- `4` — a required fixture was absent, so a case was reported as
  `INCOMPLETE-FIXTURE ABSENT` and not run. This is release-blocking: load the
  missing fixture and rerun until the driver returns `0`.
- `8` — an assertion failed.

A `4` therefore means the run was *incomplete*, not that it passed; treat it
exactly like a failure for release purposes.

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
| Transaction-list boundary | 50 matching transactions | 50 entries returned with `truncated=false` and HTTP `200` |
| Transaction-list truncation | 51 or more matching transactions | First 50 entries returned with `truncated=true` (a disclosed partial list) and HTTP `200`; request volume never yields HTTP `500` |
| List transport | A bounded 50-entry list (about 16.5 KB) | Delivered over channel `CDEMOAPILISTCH` and its containers, never COMMAREA; stays below the 32 KB ceiling |
| Money extrema | Valid maximum positive and negative amounts | Signed JSON strings retain exactly two decimal places |

Restore the cloned datasets after every boundary test.

### Post-test cleanup

After running the HTTP, token, and driver tests, remove the sensitive
artifacts they leave behind:

- **Purge the token registry.** Sign-on writes bearer tokens into `MAIN`
  TSQs whose name is `AT` followed by a 14-digit hash of the token — 16
  characters in all (see [`docs/decision-log.md`](decision-log.md), D20).
  The safest purge is a region restart: `MAIN` TSQs are non-recoverable, so a
  restart clears every token queue and nothing else, while
  `TSMODEL(CDAPITSM) EXPIRYINT(20)` reaps idle queues automatically. If you
  must purge in a running region, be selective: `CEMT INQUIRE TSQUEUE(AT*)`
  is a broad prefix match that can also list unrelated queues another
  application happened to name with a leading `AT`. Before purging one from
  `CEBR`, confirm it is an owned token queue — the name must be exactly 16
  characters, `AT` plus 14 numeric digits, with nothing else. Never purge an
  `AT*` queue that does not match that exact format; it belongs to something
  else.
- **Scrub captured output.** If a test wrote request/response bodies, tokens,
  or credentials to a terminal capture, spool file, or workstation log, delete
  those captures. The programs themselves log only a `requestId` correlation
  value — never tokens, credentials, or a PAN.
- **Restore data fixtures.** Return the account-99 empty-list fixture and any
  cloned VSAM datasets to baseline as described above.

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
   - **Immediate discard (advanced; a restart is safer).** From an
     authorized CICS terminal, work from the inbound edge inward so no
     request is mid-flight when its resource disappears. First disable the
     URIMAP so new requests stop routing:
     `CEMT SET URIMAP(CDAPIURI) DISABLED`. Then close the listener so new
     connections stop: `CEMT SET TCPIPSERVICE(CDAPISVC) CLOSED`. Then let
     in-flight work drain — run `CEMT INQUIRE TASK` and wait for `CAPI` and
     any `TAUT`/`TACC`/`TCUS`/`TCRD`/`TXRF`/`TTRN` tasks to end (or purge a
     stuck task) before discarding its program. Then purge the token TSQs as
     in [Post-test cleanup](#post-test-cleanup), verifying each `AT`+14-digit
     name first. Finally `CEMT DISCARD` in dependency order:
     `URIMAP(CDAPIURI)`, `TCPIPSERVICE(CDAPISVC)`, `TRANSACTION(CAPI)` (and
     the `TST*` driver transactions if installed), the `PROGRAM`s
     (`COAPIRTR` and the service/driver programs), then `TSMODEL(CDAPITSM)`.
     `CEMT DISCARD` removes only the in-memory copies from the running
     region; the CSD definitions were already deleted by the DFHCSDUP step
     in step 1.
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
`API-PAYLOAD X(1000)`. The per-account transaction list is bounded at 50
entries (`API-TRAN-LIST OCCURS 0 TO 50`) of about 330 bytes each plus a
16-byte header — roughly 16.5 KB. That stays under the 32 KB COMMAREA ceiling
but far exceeds both the 1024-byte convention and the `API-PAYLOAD X(1000)`
single-record buffer, so `COTRSVCC` carries it over channel `CDEMOAPILISTCH`
with containers `TRANLISTREQ`, `TRANLISTRSP`, and `TRANLISTSTA` (see
[`docs/decision-log.md`](decision-log.md), D5). Do not move the list back to
COMMAREA.

That ~16.5 KB is the raw COBOL list structure carried over the channel, not
the serialized JSON. The router builds each JSON response in the `COJSONUC`
output buffer `JB-DATA PIC X(96000)` — a 96 KB cap — and every append is
bounds-checked against that length. On overflow the serializer sets its error
flag and the request returns HTTP `500` rather than emitting truncated JSON,
so the JSON body a client receives is bounded by that 96 KB buffer.

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
