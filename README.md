## CardDemo -- Mainframe CardDemo Application

- [CardDemo -- Mainframe CardDemo Application](#carddemo----mainframe-carddemo-application)
- [Description](#description)
- [Technologies used](#technologies-used)
- [Installation on the mainframe](#installation-on-the-mainframe)
- [Application Details](#application-details)
  - [User Functions](#user-functions)
  - [Admin Functions](#admin-functions)
  - [Application Inventory](#application-inventory)
    - [**Online**](#online)
    - [**Batch**](#batch)
  - [Application Screens](#application-screens)
    - [**Signon Screen**](#signon-screen)
    - [**Main Menu**](#main-menu)
    - [**Admin Menu**](#admin-menu)
- [REST/JSON API layer](#restjson-api-layer)
- [Support](#support)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Project status](#project-status)

<br/>

## Description
CardDemo is a Mainframe application designed and developed to test and showcase AWS and partner technology for mainframe migration and modernization use-cases such as discovery, migration, modernization, performance test, augmentation, service enablement, service extraction, test creation, test harness, etc.

Note that the intent of this application is to provide mainframe coding scenarios to excercise analysis, transformation and migration tooling. So, the coding style is not uniform across the application

<br/>

## Technologies used
1. COBOL
2. CICS
3. VSAM
4. JCL
5. RACF

<br/>

## Installation on the mainframe 

To install this repository on the mainframe please follow the following steps

1. Clone this repository to your local development environment

2. Create datasets on the mainframe  hold the code
   * It is recommended to group them under a High Level Qualifier (HLQ)for all your datasets. 
   * Upload the following application source folders from the main branch of git repository on to your mainframe
      using $INDFILE or your preferred upload tool.
   * If you have used AWS.M2 as your HLQ, you should end up with the below code structure on the mainframe
   
      | HLQ    | Name          | Format | Length |
      | :----- | :------------ | :----- | -----: |
      | AWS.M2 | CARDDEMO.JCL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.PROC | FB     |     80 |
      | AWS.M2 | CARDDEMO.CBL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.CPY  | FB     |     80 |
      | AWS.M2 | CARDDEMO.BMS  | FB     |     80 |
      
3. Use data for testing using either of the below approaches

   ** Use the supplied sample data**
   
      * Upload the sample data provided in the main/-/data/EBCDIC/ folder to the mainframe. Ensure that you use transfer mode binary

         | Dataset name                      | Name                                             | Copybook (Layout) | Format | Length | Name of equivalent ascii file |
         | :---------------------------------| :----------------------------------------------- | :-----            | :----- | -----: | :---------------------------- |
         | AWS.M2.CARDDEMO.USRSEC.PS         | User Security file                               | CSUSR01Y          | FB     |     80 | See DEFUSR01.jcl (inline)     |
         | AWS.M2.CARDDEMO.ACCTDATA.PS       | Account Data                                     | CVACT01Y          | FB     |    300 | acctdata.txt                  |
         | AWS.M2.CARDDEMO.CARDDATA.PS       | Card Data                                        | CVACT02Y          | FB     |    150 | carddata.txt                  |
         | AWS.M2.CARDDEMO.CUSTDATA.PS       | Customer Data                                    | CVCUS01Y          | FB     |    500 | custdata.txt                  |
         | AWS.M2.CARDDEMO.CARDXREF.PS       | Customer Account Card Cross reference            | CVACT03Y          | FB     |     50 | cardxref.txt                  |
         | AWS.M2.CARDDEMO.DALYTRAN.PS.INIT  | Transaction database initialization record       | CVTRA06Y          | FB     |    350 | 1 record (low-values ending with 00000100)|
         | AWS.M2.CARDDEMO.DALYTRAN.PS       | Transaction data which has to go through posting | CVTRA06Y          | FB     |    350 | dailytran.txt                 |
         | AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS| Transaction data entered online                  | CVTRA05Y          | FB     |    350 | not applicable                |
         | AWS.M2.CARDDEMO.DISCGRP.PS        | Disclosure Groups                                | CVTRA02Y          | FB     |     50 | discgrp.txt                   |
         | AWS.M2.CARDDEMO.TRANCATG.PS       | Transaction Category Types                       | CVTRA04Y          | FB     |     60 | trancatg.txt                  |
         | AWS.M2.CARDDEMO.TRANTYPE.PS       | Transaction Types                                | CVTRA03Y          | FB     |     60 | trantype.txt                  |
         | AWS.M2.CARDDEMO.TCATBALF.PS       | Transaction Category Balance                     | CVTRA01Y          | FB     |     50 | tcatbal.txt                   |

      * Execute the following JCLs in order

         | Jobname  | What it does                                        |
         | :------- | :-------------------------------------------------- |
         | DUSRSECJ | Sets up user security vsam file                     |
         | CLOSEFIL | Closes files opened by CICS                         |
         | ACCTFILE | Loads Account database using sample data            |
         | CARDFILE | Loads Card database with credit card sample data    |
         | CUSTFILE | Creates customer database                           |
         | XREFFILE | Loads Customer Card account cross reference to VSAM |
         | TRANFILE | Copies initial Trasaction file  to VSAM             |
         | DISCGRP  | Copies initial Disclosure Group file  to VSAM       |
         | TCATBALF | Copies initial TCATBALF file  to VSAM               |
         | TRANCATG | Copies initial transaction category file  to VSAM   |
         | TRANTYPE | Copies initial transaction type file                |
         | OPENFIL  | Makes files available to CICS                       |
         | DEFGDGB  | Defines GDG Base                                    |


4. Compile the Programs. 
   
   You should use the compile process followed by your mainframe shopfloor
   
   We have however provided some sample JCLs in the samples folder in git to help you craft the JCL   

5. Create resources in the CARDDEMO group in CICS
   
   You have 2 options
   
   Be sure to edit the HLQs in the below documents as required before you do the definition
   
   * (Preferred) . Use the DFHCSDUP JCL that the resources required by the application

      The resources required are in the CSD file provided in the CSD folder
       
      * Group CARDDEMO
      * Mapsets
      * Transactions
      * Maps
      * Files
      
   * Use the CEDA transaction to execute the commands in the above listing
   
      * Define group 
         ```shell
         DEFINE LIBRARY(COM2DOLL) GROUP(CARDDEMO) DSNAME01(&HLQ..LOADLIB)
         ```
      * Define Mapsets, Maps , Programs and Files
      
         Sample CEDA commands
         
         ```shell
         DEF PROGRAM(COCRDLIC) GROUP(CARDDEMO)
         DEF MAPSET(COCRDLI) GROUP(CARDDEMO)
         DEFINE PROGRAM(COSGN00C) GROUP(CARDDEMO) DA(ANY) TRANSID(CC00) DESCRIPTION(LOGIN)
         DEFINE TRANSACTION(CC00) GROUP(CARDDEMO) PROGRAM(COSGN00C) TASKDATAL(ANY)
         ```

   * Install /Load the online resources to your CICS region

      ```shell
      CEDA INSTALL TRANS(CCLI) GROUP(CARDDEMO)
      CEDA INSTALL FILE(CARDDAT) GROUP(CARDDEMO)
      CECI LOAD PROG(COCRDUP)
      CECI LOAD PROG(COCRDUPC)
      ```

   * Execute a NEWCOPY of mapsets and maps
      ```shell
      CEMT SET PROG(COCRDUP) NEWCOPY
      CEMT SET PROG(COCRDUPC) NEWCOPY  
      ```
6. Enjoy the demo

   * For online functions : Start the CardDemo application using the CC00 transaction
     - Enter userid ADMIN001 and the initially configured password PASSWORD to manage users
     - Enter userid USER0001 and the initially configured password PASSWORD to access back office functions
   * For batch            : See the instructions for running full batch below.

## Running full batch 
   
  * Execute the following JCLs in order

    | Jobname  | What it does                                        |
    | :------- | :-------------------------------------------------- |
    | CLOSEFIL | Closes files opened by CICS                         |
    | ACCTFILE | Loads Account database using sample data            |
    | CARDFILE | Loads Card database with credit card sample data    |
    | XREFFILE | Loads Customer Card account cross reference to VSAM |
    | CUSTFILE | Creates customer database                           |
    | TRANBKP  | Creates Transaction database                        |
    | DISCGRP  | Copies initial disclosure Group file  to VSAM       |
    | TCATBALF | Copies initial TCATBALF file  to VSAM               |
    | TRANTYPE | Copies initial transaction type file                |
    | DUSRSECJ | Sets up user security vsam file                     |
    | POSTTRAN | Core processing job                                 |
    | INTCALC  | Run interest calculations                           |
    | TRANBKP  | Backup Transaction database                         |
    | COMBTRAN | Combine system transactions with daily ones         |
    | CREASTMT | Produce transaction statement                       | 	
    | TRANIDX  | Define alternate index on transaction file          |
    | OPENFIL  | Makes files available to CICS                       |
<br/>

## Application Details 
The CardDemo is a Credit Card management application, built primarily using COBOL programming language. The application has various functions that allows users to manage Account, Credit card, Transaction and Bill payment. 

There are 2 types of users:
* Regular User
* Admin User

The Regular user can perform the user functions and the Admin users can only perform Admin functions.

<br/>

### User Functions

![Alt text](./diagrams/Application-Flow-User.png?raw=true "User Flow")

<br/>

### Admin Functions

![Alt text](./diagrams/Application-Flow-Admin.png?raw=true "Admin Flow")

<br/>

### Application Inventory

#### **Online**

| Transaction |      | BMS Map | Program  | Function            |
| :---------- | :--- | :------ | :------- | :------------------ |
| CC00        |      | COSGN00 | COSGN00C | Signon Screen       |
| CM00        |      | COMEN01 | COMEN01C | Main Menu           |
|             | CAVW | COACTVW | COACTVWC | Account View        |
|             | CAUP | COACTUP | COACTUPC | Account Update      |
|             | CCLI | COCRDLI | COCRDLIC | Credit Card List    |
|             | CCDL | COCRDSL | COCRDSLC | Credit Card View    |
|             | CCUP | COCRDUP | COCRDUPC | Credit Card Update  |
|             | CT00 | COTRN00 | COTRN00C | Transaction List    |
|             | CT01 | COTRN01 | COTRN01C | Transaction View    |
|             | CT02 | COTRN02 | COTRN02C | Transaction Add     |
|             | CR00 | CORPT00 | CORPT00C | Transaction Reports |
|             | CB00 | COBIL00 | COBIL00C | Bill Payment        |
| CA00        |      | COADM01 | COADM01C | Admin Menu          |
|             | CU00 | COUSR00 | COUSR00C | List Users          |
|             | CU01 | COUSR01 | COUSR01C | Add User            |
|             | CU02 | COUSR02 | COUSR02C | Update User         |
|             | CU03 | COUSR03 | COUSR03C | Delete User         |

#### **Batch**

| Job      | Program  | Function                                   |
| :------- | :------- | :----------------------------------------- |
| DUSRSECJ | IEBGENER | Initial Load of User security file         |
| DEFGDGB  | IDCAMS   | Setup GDG Bases                            | 
| ACCTFILE | IDCAMS   | Refresh Account Master                     |
| CARDFILE | IDCAMS   | Refresh Card Master                        |
| CUSTFILE | IDCAMS   | Refresh Customer Master                    |
| DISCGRP  | IDCAMS   | Load Disclosure Group File                 |
| TRANFILE | IDCAMS   | Load Transaction Master file               |
| TRANCATG | IDCAMS   | Load Transaction category types            |
| TRANTYPE | IDCAMS   | Load Transaction type file                 |
| XREFFILE | IDCAMS   | Account, Card and Customer cross reference |
| CLOSEFIL | IEFBR14  | Close VSAM files in CICS                   |
| TCATBALF | IDCAMS   | Refresh Transaction Category Balance       |
| TRANBKP  | IDCAMS   | Refresh Transaction Master                 |
| POSTTRAN | CBTRN02C | Transaction processing job                 |
| TRANIDX  | IDCAMS   | Define AIX for transaction file            |
| OPENFIL  | IEFBR14  | Open files in CICS                         |
| INTCALC  | CBACT04C | Run interest calculations                  |
| COMBTRAN | SORT     | Combine transaction files                  |
| CREASTMT | CBSTM03A | Produce transaction statement              |

<br/>

### Application Screens

#### **Signon Screen**

![Alt text](./diagrams/Signon-Screen.png?raw=true "Signon Screen")


#### **Main Menu**

![Alt text](./diagrams/Main-Menu.png?raw=true "Main Menu")

#### **Admin Menu**

![Alt text](./diagrams/Admin-Menu.png?raw=true "Admin Menu")

<br/>

## REST/JSON API layer

CardDemo now exposes a net-new, **read-only** REST/JSON API that makes its core inquiry functions available programmatically to HTTP/JSON clients such as `curl`, Postman, or a partner application. The API is delivered entirely through **base CICS Web Support** -- no additional licensed products and no external API gateway -- and is served under the versioned base path `/carddemo/api/v1`. In this increment the listener binds the **loopback address (`127.0.0.1`), so access is same-host only**; reaching off-mainframe (distributed) callers is a documented follow-up that requires a TLS-terminating reverse proxy or gateway plus RACF/authorization hardening (see [`docs/onboarding-api.md`](./docs/onboarding-api.md) and the [decision log](./docs/decision-log.md), D51). This first read-only increment advances the roadmap item *Exposure of transactions for distributed application integration* without yet exposing the port beyond the host. This layer is **purely additive**: it introduces new members only and makes **no changes** to the existing COBOL, BMS, CSD, or JCL source. All access to the existing VSAM datasets is strictly read-only (`READ`/`STARTBR`/`READNEXT`/`ENDBR` only -- there is no create, update, or delete path).

### Endpoints

All paths are prefixed with the versioned base path `/carddemo/api/v1` and are relative to the CICS Web Support host and port. Every endpoint except sign-on requires a bearer token (see [Using the API](#using-the-api)).

| Method | Path | Description |
| :----- | :--- | :---------- |
| POST | `/carddemo/api/v1/signon` | Authenticate an existing CardDemo user id/password; returns a short-lived bearer token (validated against `USRSEC`). |
| GET | `/carddemo/api/v1/accounts/{acctId}` | Account status, balances, limits, cycle credit/debit, dates, and group id (from `ACCTDAT`). |
| GET | `/carddemo/api/v1/customers/{custId}` | Customer demographic/identification fields (from `CUSTDAT`; SSN and government-issued id minimized/masked by default). |
| GET | `/carddemo/api/v1/cards/{cardNum}` | Card detail with PAN masked to last 4 and CVV excluded (from `CARDDAT`). |
| GET | `/carddemo/api/v1/xref/{cardNum}` | Resolve a card to its account id and customer id (from `CCXREF`). |
| GET | `/carddemo/api/v1/accounts/{acctId}/transactions` | List the transactions for an account (from `TRANSACT`). |
| GET | `/carddemo/api/v1/transactions/{tranId}` | Single transaction detail (from `TRANSACT`). |

### CICS components

The HTTP/JSON front door runs entirely in-region and is defined in a **new** CSD group `CDEMOAPI` (held in the file `app/csd/CARDDEMOAPI.CSD`; the installed group name is `CDEMOAPI` because CICS group names are limited to eight characters, and the existing `CARDDEMO` group is untouched):

* A new `TCPIPSERVICE` listening on an unused TCP port.
* One or more `URIMAP`s bound to `/carddemo/api/v1/*`.
* An alias `TRANSACTION` that attaches the router.
* The router program `COAPIRTR`, which receives the request (`EXEC CICS WEB RECEIVE`), parses the route, enforces the bearer token, and dispatches (via `EXEC CICS LINK`) to the authentication program `COAPISEC` and the read-only service programs `COACSVCC` (accounts), `COCUSVCC` (customers), `COCRSVCC` (cards), `COXRSVCC` (cross-reference), and `COTRSVCC` (transactions). The router statically `CALL`s the in-repository JSON serializer `COJSONUC` to build each response body, then sends it (`EXEC CICS WEB SEND`).

The `CDEMOAPI` group defines **no `FILE` resources**. The API programs open the existing read-only files `ACCTDAT`, `CARDDAT`, `CCXREF`, `CUSTDAT`, `TRANSACT`, and `USRSEC` (plus the alternate-index paths `CARDAIX` and `CXACAIX`) exactly as the base `CARDDEMO` region already installs them; the group never redefines them. Read-only access is enforced in the service programs (which issue only `READ`/`STARTBR`/`READNEXT`) rather than by a duplicate FILE definition that could override the base region's write-capable files.

### Installing the API layer

These steps are additive to the existing [Installation on the mainframe](#installation-on-the-mainframe) instructions and follow the same conventions. Edit the HLQs as required before running the jobs.

1. Compile and link the new API programs (`COAPIRTR`, `COAPISEC`, `COACSVCC`, `COCUSVCC`, `COCRSVCC`, `COXRSVCC`, `COTRSVCC`, and the JSON serializer `COJSONUC` -- eight runtime programs in total) using the new API compile/link JCL, modeled on `samples/jcl/CICCMP.jcl` (the `BUILDONL` proc with `HLQ=AWS.M2`). Then pick up the new load modules in CICS:

   ```shell
   CEMT SET PROG(COAPIRTR) NEWCOPY
   CEMT SET PROG(COAPISEC) NEWCOPY
   ```

2. Define and install the new `CDEMOAPI` CSD group (held in the file `app/csd/CARDDEMOAPI.CSD`) using the new `DFHCSDUP` job. The group defines the `TCPIPSERVICE`, the `URIMAP`(s), the alias `TRANSACTION`, and one `PROGRAM` entry per new program. It defines **no `FILE` resources**: the existing read-only files `ACCTDAT`, `CARDDAT`, `CCXREF`, `CUSTDAT`, `TRANSACT`, and `USRSEC` (plus AIX paths `CARDAIX`, `CXACAIX`) are shared exactly as the base `CARDDEMO` region already installs them and are never redefined here.

   ```shell
   CEDA INSTALL GROUP(CDEMOAPI)
   ```

3. Enable CICS Web Support / TCP/IP services in the CICS region. This is an operator/SIT action (for example, `TCPIP=YES` in the SIT or startup overrides), not a source edit; then install and open the `TCPIPSERVICE`.

4. For full, detailed step-by-step onboarding -- CSD install, compile, CWS/TCP/IP enablement, domain context, common pitfalls (COMMAREA size, packed-decimal scaling, PAN/CVV handling), and how to extend the layer with a new endpoint -- see [`docs/onboarding-api.md`](./docs/onboarding-api.md).

### Using the API

The API is the programmatic equivalent of the existing 3270 inquiry screens (for example, the card-to-transactions drill-down below mirrors the `CCDL` -> `CT00` screen flow). All responses use `Content-Type: application/json`.

First, sign on with an existing CardDemo user id/password to obtain a short-lived bearer token:

```shell
curl -s -X POST "http://<cics-host>:<port>/carddemo/api/v1/signon" \
     -H "Content-Type: application/json" \
     -d '{"userId":"USER0001","password":"<password>"}'
```

The sign-on response returns the token (and its expiry) inside the `data` envelope:

```shell
# HTTP 200
# {
#   "data": {
#     "token": "<opaque-bearer-token>",
#     "userId": "USER0001",
#     "userType": "U",
#     "expiresAt": "2026-07-19 08:15:00.000000"
#   }
# }
```

Then call any inquiry endpoint, passing the token in the `Authorization` header (Flow 1 -- authenticated account inquiry):

```shell
curl -s "http://<cics-host>:<port>/carddemo/api/v1/accounts/<acctId>" \
     -H "Authorization: Bearer <opaque-bearer-token>"
```

To drill down from a card to its transactions (Flow 2), first resolve the card via the cross-reference, then list the resolved account's transactions:

```shell
# Resolve the card to its account id and customer id
curl -s "http://<cics-host>:<port>/carddemo/api/v1/xref/<cardNum>" \
     -H "Authorization: Bearer <opaque-bearer-token>"

# List the transactions for the resolved account
curl -s "http://<cics-host>:<port>/carddemo/api/v1/accounts/<acctId>/transactions" \
     -H "Authorization: Bearer <opaque-bearer-token>"
```

### Response envelope and error contract

Every response is JSON with a uniform envelope. Successful responses carry a top-level `data` object; failures carry a top-level `error` object with `code`, `message`, and `requestId`. The HTTP status map is fixed:

| Status | Meaning |
| :----- | :------ |
| `200` | Success (including an empty list for a transaction query that matches no rows). |
| `400` | Bad request or unknown route (for example, malformed JSON or an unrecognized path). |
| `401` | Authentication failure (missing, invalid, or expired bearer token). |
| `404` | The requested resource was not found. |
| `500` | Internal error (returned without leaking CICS `RESP2` values). |

### Security

* The primary account number (PAN) is masked to the last four digits on both card and transaction responses; a full PAN never appears in any response or log.
* The card security code (CVV) is never serialized.
* Customer SSN and government-issued id are minimized/masked by default.
* Authentication is a short-lived opaque bearer token, issued by `COAPISEC` and validated against the `USRSEC` security file (mirroring the `COSGN00C` sign-on pattern).

Production hardening -- TLS/keyring, full RACF surrogate/resource security, OAuth/OIDC, and rate limiting -- is documented as future work and is **not** implemented in this increment.

### API specification

The authoritative, machine-readable contract is [`app/api/openapi.yaml`](./app/api/openapi.yaml) (OpenAPI 3.0). Use it to explore the endpoints, generate client SDKs, and drive contract tests.

<br/>

## Support

If you have questions or requests for improvement please raise an issue in the repository.

<br/>

## Roadmap

The following features are planned for upcoming releases

1. More database types

   1. Relational Database usage : Db2 
   
   2. Hierachical database calls : IMS

2. Integration

   * ftp, sftp
   
   * Message queue integration
   
   * Exposure of transactions for distributed application integration

     * A first read-only REST/JSON increment is now delivered (see the [REST/JSON API layer](#restjson-api-layer) section).

<br/>

## Contributing

We are looking forward to receiving contributions and enhancements to this initial codebase from the mainframe code base

Feel free to raise issues, create code and raise merge requests for enhancements so that we can build out this application as a resource for programmers wanting to understand and modernize their mainframes.

<br/>

## License

This is intended to be a community resource and it is released under the Apache 2.0 license.

<br/>

## Project status

We are planning a v2 of this application in Q1 2023.

Watch this space for updates

<br/>


