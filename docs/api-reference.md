# CardDemo API Reference

The modernized **CardDemo** backend is a [FastAPI](https://fastapi.tiangolo.com/)
service that re-expresses the legacy IBM mainframe online transactions (COBOL
programs under [`../app/cbl/`](../app/cbl), driven by CICS and BMS 3270 maps) as
REST endpoints over HTTP/JSON. Every v1 endpoint is mounted under the
`API_V1_PREFIX` — `/api/v1` — so the development base URL is
`http://localhost:8000/api/v1`. The frontend axios client
([`frontend/src/lib/apiClient.ts`](../frontend/src/lib/apiClient.ts)) targets
`${NEXT_PUBLIC_API_URL}/api/v1`, which defaults to
`http://localhost:8000/api/v1`.

This document is the human-readable companion to the service's machine-readable
OpenAPI schema. The running service always publishes the authoritative schema
at `/openapi.json` (rendered interactively at `/docs`); when this reference and
the live schema differ, treat the live schema as correct. Every business rule,
validation edit, and numeric computation is preserved exactly from the legacy
programs (the *Minimal Change Clause*); each endpoint below cites its originating
CICS transaction id and COBOL program, which remain unmodified under
[`../app/cbl/`](../app/cbl).

## Table of Contents

- [Base URL and Interactive Docs](#base-url-and-interactive-docs)
- [Authentication and Authorization](#authentication-and-authorization)
- [Endpoint Summary](#endpoint-summary)
- [Endpoints](#endpoints)
  - [Authentication](#authentication)
  - [Menu](#menu)
  - [Accounts](#accounts)
  - [Cards](#cards)
  - [Transactions](#transactions)
  - [Reports](#reports)
  - [Bill Payment](#bill-payment)
  - [User Administration](#user-administration)
- [Validation Rules](#validation-rules)
- [Error and Message Codes](#error-and-message-codes)
- [Conventions](#conventions)
- [Related Documentation](#related-documentation)

## Base URL and Interactive Docs

| Resource | URL (development) | Notes |
| :------- | :---------------- | :---- |
| API base | `http://localhost:8000/api/v1` | All versioned endpoints. The `/api/v1` prefix is applied once by the application when the routers are mounted. |
| Swagger UI | `http://localhost:8000/docs` | Interactive OpenAPI documentation; try requests from the browser. |
| OpenAPI schema | `http://localhost:8000/openapi.json` | Authoritative machine-readable contract. |
| Health check | `http://localhost:8000/health` | Unversioned liveness probe (see below). |

Check service health with a plain `GET` — no authentication is required:

```bash
curl http://localhost:8000/health
```

```json
{ "status": "ok" }
```

> **Note:** The health, Swagger, and OpenAPI routes are served by the FastAPI
> application root and are **not** under the `/api/v1` prefix. The interactive
> `/docs` and `/openapi.json` endpoints reflect the live, deployed contract.

## Authentication and Authorization

The legacy application propagated the signed-on user's identity and role from
program to program through the CICS `COCOM01Y` COMMAREA. The modern backend
replaces that in-region propagation with **stateless, server-side
authentication** established once at sign-on.

**Session-based authentication is the baseline.** A successful
`POST /auth/login` sets an HTTP-only cookie named by `SESSION_COOKIE_NAME`
(default `carddemo_session`). The browser then sends that cookie automatically
on subsequent requests; the signed token is never exposed to JavaScript and is
never echoed in the response body.

**JWT bearer authentication is an accepted alternative.** When the service runs
with `AUTH_MODE=jwt`, `POST /auth/login` instead returns an `access_token` and
`token_type` in the response body. Callers then present the token on each
request:

```http
Authorization: Bearer <token>
```

JWTs are signed with the `ALGORITHM` (default `HS256`) and expire after
`ACCESS_TOKEN_EXPIRE_MINUTES`. Under both modes the token lifetime and signing
key come from configuration (`SECRET_KEY`), never from source code.

### Authorization

Roles are carried on the authenticated identity as `user_type`:

| `user_type` | Role | Access |
| :---------- | :--- | :----- |
| `A` | Administrator | All endpoints, including admin-only routes. |
| `U` | Regular user | All non-admin endpoints. |

Admin-only endpoints — the entire `/admin/users/*` group and `GET /admin/menu` —
are gated server-side by a `require_admin` dependency that admits only
`user_type='A'`. This mirrors the legacy `COCOM01Y` `CDEMO-USRTYP-ADMIN` role
check. A regular user (`user_type='U'`) who calls an admin route receives
`403 Forbidden`. The frontend mirrors this gating for usability, but the server
is authoritative.

### CORS

Cross-origin requests from the frontend origin `http://localhost:3000` are
allowed **with credentials enabled**, so the browser may send the session cookie
on cross-origin API calls. Allowed origins come from the `BACKEND_CORS_ORIGINS`
setting.

## Endpoint Summary

Paths below are shown **without** the `/api/v1` prefix; the prefix applies to
every row (for example, `POST /auth/login` is served at
`http://localhost:8000/api/v1/auth/login`). The **Auth** column indicates the
minimum requirement: *public* (no authentication), *user* (any authenticated
user), or *admin* (`user_type='A'`).

| Method | Path | Purpose | Legacy Tx / Program | Auth |
| :----- | :--- | :------ | :------------------ | :--- |
| `POST` | `/auth/login` | Sign on and establish a session (or receive a token) | CC00 / `COSGN00C` | public |
| `GET` | `/menu` | Regular-user main menu options | CM00 / `COMEN01C` | user |
| `GET` | `/admin/menu` | Admin menu options | CA00 / `COADM01C` | admin |
| `GET` | `/accounts/{acctId}` | View account with linked customer | CAVW / `COACTVWC` | user |
| `PUT` | `/accounts/{acctId}` | Update account (optimistic locking) | CAUP / `COACTUPC` | user |
| `GET` | `/cards` | List cards (paginated, ≤ 7 per page) | CCLI / `COCRDLIC` | user |
| `GET` | `/cards/{cardNum}` | View a card | CCDL / `COCRDSLC` | user |
| `PUT` | `/cards/{cardNum}` | Update a card | CCUP / `COCRDUPC` | user |
| `GET` | `/transactions` | List transactions (paginated) | CT00 / `COTRN00C` | user |
| `GET` | `/transactions/{tranId}` | View a transaction | CT01 / `COTRN01C` | user |
| `POST` | `/transactions` | Add a transaction | CT02 / `COTRN02C` | user |
| `GET` | `/reports/transactions` | Transaction report (date range + type); JSON, CSV, or PDF | CR00 / `CORPT00C` | user |
| `GET` | `/billpay/{acctId}` | Bill-payment info (available credit) | CB00 / `COBIL00C` | user |
| `POST` | `/billpay` | Make a bill payment | CB00 / `COBIL00C` | user |
| `GET` | `/admin/users` | List users (paginated) | CU00 / `COUSR00C` | admin |
| `POST` | `/admin/users` | Add a user | CU01 / `COUSR01C` | admin |
| `GET` | `/admin/users/{userId}` | View a user | CU02 / `COUSR02C` | admin |
| `PUT` | `/admin/users/{userId}` | Update a user | CU02 / `COUSR02C` | admin |
| `DELETE` | `/admin/users/{userId}` | Delete a user | CU03 / `COUSR03C` | admin |

> **Reconciled to code.** This table reflects the routers as implemented in
> [`backend/app/api/v1/`](../backend/app/api/v1). Two convenience read routes —
> `GET /billpay/{acctId}` and `GET /admin/users/{userId}` — are present in the
> code in addition to the core actions and are documented here accordingly.


## Endpoints

Each endpoint documents its purpose and legacy origin, path and query
parameters, the request body DTO (when applicable), a minimal request example,
the response DTO with an example, the success status codes, and the errors it
can return. All request and response fields use `snake_case`. Monetary fields
are serialized as **decimal strings** to preserve exact precision (see
[Conventions](#conventions)).

### Authentication

#### `POST /auth/login`

Authenticate a sign-on request. Ports `COSGN00C` (CICS transaction **CC00**,
BMS map `COSGN00`). This is the only unauthenticated endpoint in the API.

**Request body** (`LoginRequest`):

| Field | Type | Constraints | Description |
| :---- | :--- | :---------- | :---------- |
| `user_id` | string | ≤ 8 chars, required | User id (`SEC-USR-ID`). |
| `password` | string | ≤ 8 chars, required | Plaintext password, verified against the stored bcrypt/argon2 hash. |

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "user_id": "ADMIN001",
  "password": "PASSWORD"
}
```

**Response** (`LoginResponse`, `200 OK`): returns the authenticated caller's
profile. Under the session baseline, the signed token is set as the
`carddemo_session` cookie and `access_token`/`token_type` are `null`. Under
`AUTH_MODE=jwt`, `access_token` and `token_type` are populated instead and no
cookie is set.

```json
{
  "user_id": "ADMIN001",
  "first_name": "Admin",
  "last_name": "User",
  "user_type": "A",
  "access_token": null,
  "token_type": null
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Credentials valid; session established (or token issued). |
| `401 Unauthorized` | Invalid user id or password (`AuthenticationError`). |
| `422 Unprocessable Entity` | Request body fails schema validation (for example, `user_id` longer than 8 characters). |

> The demo credentials `ADMIN001` / `USER0001` (password `PASSWORD`) are
> **non-production seed accounts only**. They are stored hashed at rest and must
> never be treated as real credentials or hardcoded in application code.

### Menu

#### `GET /menu`

Return the regular main-menu options for any authenticated user. Ports
`COMEN01C` (transaction **CM00**, BMS map `COMEN01`).

```http
GET /api/v1/menu
Cookie: carddemo_session=<token>
```

**Response** (`MenuResponse`, `200 OK`):

```json
{
  "menu_title": "Main Menu",
  "user_type": "U",
  "menu_options": [
    { "option_number": 1, "option_name": "Account View", "program_name": "COACTVWC", "user_type": "U" }
  ]
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Menu returned. |
| `401 Unauthorized` | No valid session or token. |

#### `GET /admin/menu`

Return the administrator menu options. Ports `COADM01C` (transaction **CA00**,
BMS map `COADM01`). Gated by `require_admin`.

```http
GET /api/v1/admin/menu
Cookie: carddemo_session=<token>
```

**Response** (`MenuResponse`, `200 OK`): same shape as `GET /menu`, populated
with the admin options and `user_type` `"A"`.

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Admin menu returned. |
| `401 Unauthorized` | No valid session or token. |
| `403 Forbidden` | Authenticated caller is not an administrator (`user_type` is not `A`). |

### Accounts

#### `GET /accounts/{acctId}`

View an account together with its linked customer. Ports `COACTVWC`
(transaction **CAVW**, BMS map `COACTVW`).

**Path parameters:**

| Parameter | Type | Description |
| :-------- | :--- | :---------- |
| `acctId` | string | 11-digit account identifier (leading zeros preserved). |

```http
GET /api/v1/accounts/00000000011
Cookie: carddemo_session=<token>
```

**Response** (`AccountDetail`, `200 OK`): the account fields plus a nested
`customer` object. Balances and limits are decimal strings. The customer's
`ssn` is masked and the customer's `cvv` is never present.

```json
{
  "acct_id": "00000000011",
  "active_status": "Y",
  "curr_bal": "1250.00",
  "credit_limit": "5000.00",
  "cash_credit_limit": "1000.00",
  "open_date": "2015-06-01",
  "expiration_date": "2027-05-31",
  "reissue_date": "2023-06-01",
  "curr_cyc_credit": "300.00",
  "curr_cyc_debit": "150.00",
  "addr_zip": "20171",
  "group_id": "STANDARD",
  "customer": {
    "cust_id": "000000011",
    "first_name": "Jane",
    "last_name": "Doe",
    "ssn": "***-**-6789",
    "fico_credit_score": 720
  }
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Account found. |
| `401 Unauthorized` | No valid session or token. |
| `404 Not Found` | No account exists for `acctId` (`NotFoundError`). |

#### `PUT /accounts/{acctId}`

Update an account. Ports `COACTUPC` (transaction **CAUP**, BMS map `COACTUP`).
Reproduces the legacy READ-for-UPDATE → REWRITE cycle: the update runs inside a
database transaction with row locking, and a concurrent modification detected
between read and write is rejected with `409 Conflict` instead of silently
overwriting the other change.

**Path parameters:** `acctId` — 11-digit account identifier.

**Request body** (`AccountUpdate`): all fields optional; only the fields present
are updated. Monetary fields are decimal strings.

| Field | Type | Description |
| :---- | :--- | :---------- |
| `active_status` | string(1) | Account active flag (`Y`/`N`). |
| `curr_bal` | decimal string | Current balance. |
| `credit_limit` | decimal string | Credit limit. |
| `cash_credit_limit` | decimal string | Cash credit limit. |
| `expiration_date` | date | ISO `YYYY-MM-DD`. |
| `reissue_date` | date | ISO `YYYY-MM-DD`. |
| `curr_cyc_credit` | decimal string | Current-cycle credit. |
| `curr_cyc_debit` | decimal string | Current-cycle debit. |
| `group_id` | string | Disclosure group id. |

```http
PUT /api/v1/accounts/00000000011
Content-Type: application/json
Cookie: carddemo_session=<token>

{
  "credit_limit": "6000.00",
  "active_status": "Y"
}
```

**Response** (`AccountDetail`, `200 OK`): the updated account with its nested
customer (same shape as `GET /accounts/{acctId}`).

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Account updated. |
| `401 Unauthorized` | No valid session or token. |
| `404 Not Found` | No account exists for `acctId`. |
| `409 Conflict` | The account changed concurrently between read and write (`OptimisticLockError`). |
| `422 Unprocessable Entity` | Request body fails schema validation. |


### Cards

#### `GET /cards`

List cards as one paginated page. Ports `COCRDLIC` (transaction **CCLI**, BMS
map `COCRDLI`). The default page size is **7 rows**, preserving the legacy
card-browse screen limit (F-004).

**Query parameters:**

| Parameter | Type | Default | Constraints | Description |
| :-------- | :--- | :------ | :---------- | :---------- |
| `page` | integer | `1` | ≥ 1 | 1-based page number. |
| `page_size` | integer | `7` | 1–100 | Rows per page; the default of 7 preserves the legacy card-browse limit. |

```http
GET /api/v1/cards?page=1&page_size=7
Cookie: carddemo_session=<token>
```

**Response** (`PaginatedResponse<CardSummary>`, `200 OK`): each row's `card_num`
is masked. `cvv` is never included.

```json
{
  "items": [
    { "card_num": "************5740", "acct_id": "00000000011", "active_status": "Y", "embossed_name": "JANE DOE" }
  ],
  "page": 1,
  "page_size": 7,
  "total_items": 1,
  "total_pages": 1,
  "has_next": false,
  "has_previous": false
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Page returned (possibly empty). |
| `401 Unauthorized` | No valid session or token. |
| `422 Unprocessable Entity` | Invalid pagination parameters. |

#### `GET /cards/{cardNum}`

View a single card. Ports `COCRDSLC` (transaction **CCDL**, BMS map `COCRDSL`).

**Path parameters:** `cardNum` — 16-digit card number.

```http
GET /api/v1/cards/4111111111115740
Cookie: carddemo_session=<token>
```

**Response** (`CardRead`, `200 OK`): `card_num` is masked; `cvv` is never
returned.

```json
{
  "card_num": "************5740",
  "acct_id": "00000000011",
  "embossed_name": "JANE DOE",
  "expiration_date": "2027-05-31",
  "active_status": "Y"
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Card found. |
| `401 Unauthorized` | No valid session or token. |
| `404 Not Found` | No card exists for `cardNum`. |

#### `PUT /cards/{cardNum}`

Update a card. Ports `COCRDUPC` (transaction **CCUP**, BMS map `COCRDUP`). Only
the editable fields may be changed; the card number and owning account are
immutable.

**Path parameters:** `cardNum` — 16-digit card number.

**Request body** (`CardUpdate`):

| Field | Type | Description |
| :---- | :--- | :---------- |
| `embossed_name` | string | Name embossed on the card face. |
| `expiration_date` | date | ISO `YYYY-MM-DD`. |
| `active_status` | string(1) | Active flag (`Y`/`N`). |

```http
PUT /api/v1/cards/4111111111115740
Content-Type: application/json
Cookie: carddemo_session=<token>

{
  "embossed_name": "JANE A DOE",
  "expiration_date": "2028-05-31",
  "active_status": "Y"
}
```

**Response** (`CardRead`, `200 OK`): the updated card, `card_num` masked.

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Card updated. |
| `401 Unauthorized` | No valid session or token. |
| `404 Not Found` | No card exists for `cardNum`. |
| `422 Unprocessable Entity` | Request body fails schema validation. |

### Transactions

#### `GET /transactions`

List posted transactions as one paginated page. Ports `COTRN00C` (transaction
**CT00**, BMS map `COTRN00`).

**Query parameters:** `page` (default `1`, ≥ 1) and `page_size` (default `7`,
1–100), as for [`GET /cards`](#get-cards).

```http
GET /api/v1/transactions?page=1&page_size=7
Cookie: carddemo_session=<token>
```

**Response** (`PaginatedResponse<TransactionSummary>`, `200 OK`): `tran_amt` is
a decimal string; `card_num` is masked.

```json
{
  "items": [
    {
      "tran_id": "0000000000000123",
      "card_num": "************5740",
      "tran_type_cd": "01",
      "tran_cat_cd": "0005",
      "tran_amt": "42.50",
      "orig_ts": "2026-05-01T09:15:00Z",
      "tran_source": "POS",
      "tran_desc": "PURCHASE"
    }
  ],
  "page": 1,
  "page_size": 7,
  "total_items": 1,
  "total_pages": 1,
  "has_next": false,
  "has_previous": false
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Page returned (possibly empty). |
| `401 Unauthorized` | No valid session or token. |
| `422 Unprocessable Entity` | Invalid pagination parameters. |

#### `GET /transactions/{tranId}`

View a single transaction. Ports `COTRN01C` (transaction **CT01**, BMS map
`COTRN01`).

**Path parameters:** `tranId` — 16-character transaction identifier.

```http
GET /api/v1/transactions/0000000000000123
Cookie: carddemo_session=<token>
```

**Response** (`TransactionRead`, `200 OK`):

```json
{
  "tran_id": "0000000000000123",
  "tran_type_cd": "01",
  "tran_cat_cd": "0005",
  "tran_source": "POS",
  "tran_desc": "PURCHASE",
  "tran_amt": "42.50",
  "merchant_id": "000000000012345",
  "merchant_name": "ACME STORE",
  "merchant_city": "RESTON",
  "merchant_zip": "20191",
  "card_num": "************5740",
  "orig_ts": "2026-05-01T09:15:00Z",
  "proc_ts": "2026-05-01T09:15:02Z"
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Transaction found. |
| `401 Unauthorized` | No valid session or token. |
| `404 Not Found` | No transaction exists for `tranId`. |

#### `POST /transactions`

Add a transaction. Ports `COTRN02C` (transaction **CT02**, BMS map `COTRN02`).
The service applies the same posting validations as the batch posting program
`CBTRN02C`; a failed validation surfaces one of the reason codes 100–103 / 109
(see [Error and Message Codes](#error-and-message-codes)). Amounts are exact
decimals.

**Request body** (`TransactionCreate`):

| Field | Type | Required | Description |
| :---- | :--- | :------- | :---------- |
| `card_num` | string(16) | conditional | Card number the transaction posts to. |
| `acct_id` | string(11) | conditional | Owning account id (alternative key resolution). |
| `tran_type_cd` | string(2) | yes | Transaction type code. |
| `tran_cat_cd` | string(4) | yes | Transaction category code. |
| `tran_amt` | decimal string | yes | Signed amount (`S9(9)V99`), exact decimal. |
| `tran_source` | string | no | Source channel. |
| `tran_desc` | string | no | Description. |
| `merchant_id` | string | no | Merchant identifier. |
| `merchant_name` | string | no | Merchant name. |
| `merchant_city` | string | no | Merchant city. |
| `merchant_zip` | string | no | Merchant ZIP. |
| `orig_ts` | datetime | no | Origination timestamp (ISO-8601). |
| `proc_ts` | datetime | no | Processing timestamp (ISO-8601). |

```http
POST /api/v1/transactions
Content-Type: application/json
Cookie: carddemo_session=<token>

{
  "card_num": "4111111111115740",
  "tran_type_cd": "01",
  "tran_cat_cd": "0005",
  "tran_amt": "42.50",
  "tran_desc": "PURCHASE"
}
```

**Response** (`TransactionRead`, `201 Created`): the posted transaction (same
shape as `GET /transactions/{tranId}`).

| Status | Meaning |
| :----- | :------ |
| `201 Created` | Transaction posted. |
| `401 Unauthorized` | No valid session or token. |
| `409 Conflict` | Posting rejected by an over-limit or concurrency check (codes 102 / 109). |
| `422 Unprocessable Entity` | Schema validation failure, or a posting reject (codes 100, 101, 103). |


### Reports

#### `GET /reports/transactions`

Generate the transaction detail report for a date range and report type. Ports
`CORPT00C` (transaction **CR00**, BMS map `CORPT00`). The legacy TDQ/GDG
text-and-HTML statement output is intentionally redesigned into structured data:
the endpoint returns tabular JSON by default and can render the same report as a
downloadable **CSV** or **PDF** (the single authorized behavior-adjacent change).

**Query parameters:**

| Parameter | Type | Required | Description |
| :-------- | :--- | :------- | :---------- |
| `report_type` | string | yes | One of `Monthly`, `Yearly`, or `Custom` (case-insensitive input accepted). |
| `start_date` | date | yes | Inclusive range start, ISO `YYYY-MM-DD`. |
| `end_date` | date | yes | Inclusive range end, ISO `YYYY-MM-DD`. Must not precede `start_date`. |
| `confirm` | string | no | Optional confirmation flag (`Y`/`N`). |
| `format` | string | no | Output format: `json` (default), `csv`, or `pdf`. |

```http
GET /api/v1/reports/transactions?report_type=Monthly&start_date=2026-05-01&end_date=2026-05-31
Cookie: carddemo_session=<token>
```

**Response** (`ReportResponse`, `200 OK`) when `format=json`: the echoed report
type and range, the ordered detail `rows`, and running page / account / grand
totals. Every amount and total is a decimal string.

```json
{
  "report_type": "Monthly",
  "start_date": "2026-05-01",
  "end_date": "2026-05-31",
  "report_name": "Daily Transaction Report",
  "rows": [
    {
      "tran_id": "0000000000000123",
      "acct_id": "00000000011",
      "tran_type_cd": "01",
      "tran_type_desc": "Purchase",
      "tran_cat_cd": "0005",
      "tran_cat_desc": "Retail",
      "tran_source": "POS",
      "tran_amt": "42.50"
    }
  ],
  "page_total": "42.50",
  "account_total": "42.50",
  "grand_total": "42.50"
}
```

To download the report instead, request `format=csv` or `format=pdf`. The
response is a file attachment (`Content-Disposition: attachment`) with media
type `text/csv` or `application/pdf`:

```bash
curl -OJ "http://localhost:8000/api/v1/reports/transactions?report_type=Monthly&start_date=2026-05-01&end_date=2026-05-31&format=pdf" \
  --cookie "carddemo_session=<token>"
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Report generated (JSON, CSV, or PDF). |
| `401 Unauthorized` | No valid session or token. |
| `422 Unprocessable Entity` | Missing/invalid parameters, or `start_date` after `end_date`. |

### Bill Payment

#### `GET /billpay/{acctId}`

Return bill-payment information for an account, including the available credit.
Ports the read side of `COBIL00C` (transaction **CB00**, BMS map `COBIL00`).
**Available credit is computed as `credit_limit − curr_bal`** (F-006) — this is
distinct from the transaction-posting over-limit rule (code 102), and both are
preserved independently.

**Path parameters:** `acctId` — 11-digit account identifier.

```http
GET /api/v1/billpay/00000000011
Cookie: carddemo_session=<token>
```

**Response** (`BillPayResponse`, `200 OK`): amounts are decimal strings.

```json
{
  "acct_id": "00000000011",
  "curr_bal": "1250.00",
  "credit_limit": "5000.00",
  "available_credit": "3750.00",
  "payment_amount": "0.00",
  "tran_id": null,
  "message": null
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Account found. |
| `401 Unauthorized` | No valid session or token. |
| `404 Not Found` | No account exists for `acctId`. |

#### `POST /billpay`

Make a bill payment against an account. Ports the pay side of `COBIL00C`
(transaction **CB00**, BMS map `COBIL00`).

**Request body** (`BillPayRequest`):

| Field | Type | Required | Description |
| :---- | :--- | :------- | :---------- |
| `acct_id` | string(11) | yes | Account to pay. |
| `confirm` | string | yes | Confirmation flag (legacy `CONFIRMI`). |
| `payment_amount` | decimal string | no | Explicit amount (> 0). When omitted, the full current balance is paid. |

```http
POST /api/v1/billpay
Content-Type: application/json
Cookie: carddemo_session=<token>

{
  "acct_id": "00000000011",
  "confirm": "Y"
}
```

**Response** (`BillPayResponse`, `200 OK`): the balance **after** payment, the
credit limit, the recomputed `available_credit` (`credit_limit − curr_bal`), the
amount applied, and the posted payment transaction id.

```json
{
  "acct_id": "00000000011",
  "curr_bal": "0.00",
  "credit_limit": "5000.00",
  "available_credit": "5000.00",
  "payment_amount": "1250.00",
  "tran_id": "0000000000000456",
  "message": "Payment applied."
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Payment applied. |
| `401 Unauthorized` | No valid session or token. |
| `404 Not Found` | No account exists for `acct_id`. |
| `422 Unprocessable Entity` | Request body fails schema validation (for example, a non-positive `payment_amount`). |

### User Administration

All user-administration routes are **admin-only**: the entire `/admin/users`
group requires `user_type='A'` (a `require_admin` dependency guards every route).
A regular user receives `403 Forbidden`. The stored `password_hash` is **never**
returned by any of these endpoints.

#### `GET /admin/users`

List users as one paginated page. Ports `COUSR00C` (transaction **CU00**, BMS
map `COUSR00`).

**Query parameters:** `page` (default `1`, ≥ 1) and `page_size` (default `7`,
1–100).

```http
GET /api/v1/admin/users?page=1&page_size=7
Cookie: carddemo_session=<token>
```

**Response** (`PaginatedResponse<UserSummary>`, `200 OK`):

```json
{
  "items": [
    { "user_id": "ADMIN001", "first_name": "Admin", "last_name": "User", "user_type": "A" }
  ],
  "page": 1,
  "page_size": 7,
  "total_items": 1,
  "total_pages": 1,
  "has_next": false,
  "has_previous": false
}
```

| Status | Meaning |
| :----- | :------ |
| `200 OK` | Page returned. |
| `401 Unauthorized` | No valid session or token. |
| `403 Forbidden` | Caller is not an administrator. |

#### `POST /admin/users`

Add a user. Ports `COUSR01C` (transaction **CU01**, BMS map `COUSR01`). The
submitted `password` is hashed by the service before storage and is never
returned.

**Request body** (`UserCreate`):

| Field | Type | Constraints | Description |
| :---- | :--- | :---------- | :---------- |
| `user_id` | string | ≤ 8 chars | New user id. |
| `first_name` | string | ≤ 20 chars | First name. |
| `last_name` | string | ≤ 20 chars | Last name. |
| `password` | string | ≤ 8 chars | Plaintext password (hashed at rest). |
| `user_type` | string(1) | `A` or `U` | Role. |

```http
POST /api/v1/admin/users
Content-Type: application/json
Cookie: carddemo_session=<token>

{
  "user_id": "USER0002",
  "first_name": "John",
  "last_name": "Smith",
  "password": "PASSWORD",
  "user_type": "U"
}
```

**Response** (`UserRead`, `201 Created`): the created user without any password
material.

```json
{
  "user_id": "USER0002",
  "first_name": "John",
  "last_name": "Smith",
  "user_type": "U"
}
```

| Status | Meaning |
| :----- | :------ |
| `201 Created` | User created. |
| `401 Unauthorized` | No valid session or token. |
| `403 Forbidden` | Caller is not an administrator. |
| `409 Conflict` | A user with the same `user_id` already exists. |
| `422 Unprocessable Entity` | Request body fails schema validation. |

#### `GET /admin/users/{userId}`

View a single user. Ports the read flow of `COUSR02C` (transaction **CU02**, BMS
map `COUSR02`).

**Path parameters:** `userId` — up to 8-character user id.

```http
GET /api/v1/admin/users/USER0002
Cookie: carddemo_session=<token>
```

**Response** (`UserRead`, `200 OK`): the user without any password material
(same shape as the `POST /admin/users` response).

| Status | Meaning |
| :----- | :------ |
| `200 OK` | User found. |
| `401 Unauthorized` | No valid session or token. |
| `403 Forbidden` | Caller is not an administrator. |
| `404 Not Found` | No user exists for `userId`. |

#### `PUT /admin/users/{userId}`

Update a user. Ports `COUSR02C` (transaction **CU02**, BMS map `COUSR02`). A new
`password`, when supplied, is hashed before storage.

**Path parameters:** `userId` — up to 8-character user id.

**Request body** (`UserUpdate`):

| Field | Type | Required | Description |
| :---- | :--- | :------- | :---------- |
| `first_name` | string | yes | First name. |
| `last_name` | string | yes | Last name. |
| `user_type` | string(1) | yes | Role (`A`/`U`). |
| `password` | string | no | New plaintext password (hashed at rest); omit to leave unchanged. |

```http
PUT /api/v1/admin/users/USER0002
Content-Type: application/json
Cookie: carddemo_session=<token>

{
  "first_name": "John",
  "last_name": "Smith",
  "user_type": "U"
}
```

**Response** (`UserRead`, `200 OK`): the updated user without any password
material.

| Status | Meaning |
| :----- | :------ |
| `200 OK` | User updated. |
| `401 Unauthorized` | No valid session or token. |
| `403 Forbidden` | Caller is not an administrator. |
| `404 Not Found` | No user exists for `userId`. |
| `422 Unprocessable Entity` | Request body fails schema validation. |

#### `DELETE /admin/users/{userId}`

Delete a user. Ports `COUSR03C` (transaction **CU03**, BMS map `COUSR03`).

**Path parameters:** `userId` — up to 8-character user id.

```http
DELETE /api/v1/admin/users/USER0002
Cookie: carddemo_session=<token>
```

**Response:** `204 No Content` with an empty body.

| Status | Meaning |
| :----- | :------ |
| `204 No Content` | User deleted. |
| `401 Unauthorized` | No valid session or token. |
| `403 Forbidden` | Caller is not an administrator. |
| `404 Not Found` | No user exists for `userId`. |


## Validation Rules

Request validation is enforced by Pydantic schemas
([`backend/app/schemas/`](../backend/app/schemas)) that mirror the COBOL copybook
field lengths and types and reproduce the legacy BMS field edits and PROCEDURE
DIVISION validations. Requests that violate these edits are rejected with
`422 Unprocessable Entity` and a field-level error detail; no invalid data
reaches the service layer. All user-supplied input is validated and sanitized
(per the Ochs rule) to prevent injection.

Key field constraints (see [`./data-model.md`](./data-model.md) for the complete
field-length catalog):

| Field | Length / Type | Origin |
| :---- | :------------ | :----- |
| `user_id` | ≤ 8 chars | `SEC-USR-ID PIC X(08)` |
| `password` | ≤ 8 chars | `SEC-USR-PWD PIC X(08)` |
| `acct_id` | 11 digits | `ACCT-ID PIC 9(11)` |
| `card_num` | 16 digits | `CARD-NUM PIC X(16)` |
| `cust_id` | 9 digits | `CUST-ID PIC 9(09)` |
| `tran_id` | 16 chars | `TRAN-ID PIC X(16)` |
| `tran_type_cd` | 2 chars | `TRAN-TYPE-CD PIC X(02)` |
| `tran_cat_cd` | 4 digits | `TRAN-CAT-CD PIC 9(04)` |
| `user_type` | 1 char (`A`/`U`) | `SEC-USR-TYPE PIC X(01)` |

Numeric ranges and signs are preserved exactly. Monetary and rate fields map to
fixed-precision decimals (never floating point) so amounts reconcile
field-for-field with the legacy output.

## Error and Message Codes

The service returns standard HTTP status codes. Domain errors raised by the
service, repository, and dependency layers
([`backend/app/core/exceptions.py`](../backend/app/core/exceptions.py)) are
mapped to HTTP responses by handlers registered on the FastAPI application:

| Domain error | HTTP status | Raised when |
| :----------- | :---------- | :---------- |
| `NotFoundError` | `404 Not Found` | A requested account, card, customer, transaction, or user does not exist. |
| `DomainValidationError` | `400 Bad Request` / `422 Unprocessable Entity` | A ported field or business-validation edit fails. |
| `AuthenticationError` | `401 Unauthorized` | Sign-on fails, or no valid session/token is present. |
| `AuthorizationError` | `403 Forbidden` | An authenticated non-admin calls an admin-gated route. |
| `ConflictError` / `OptimisticLockError` | `409 Conflict` | A concurrent modification is detected (the `COACTUPC` READ-UPDATE → REWRITE optimistic check). |

### Transaction-posting reason codes

The daily-transaction posting validator in `CBTRN02C` (paragraphs
`1500-VALIDATE-TRAN` and `2800-UPDATE-ACCOUNT-REC`) defines the reject reason
codes below. They are ported **verbatim** — code numbers and UPPERCASE
descriptions are never reworded — and are shared identically by the online
add-transaction path (`POST /transactions`, ported from `COTRN02C`) and the
`post_transactions` batch job. Note that codes **101** and **109** intentionally
share the same description text but remain distinct codes.

| Code | Meaning | Condition |
| :--- | :------ | :-------- |
| `100` | `INVALID CARD NUMBER FOUND` | The card cross-reference lookup fails (`1500-A-LOOKUP-XREF`). |
| `101` | `ACCOUNT RECORD NOT FOUND` | The account lookup fails during validation (`1500-B-LOOKUP-ACCT`). |
| `102` | `OVERLIMIT TRANSACTION` | `credit_limit < (curr_cyc_credit − curr_cyc_debit + tran_amt)`. |
| `103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `expiration_date < transaction date`. |
| `109` | `ACCOUNT RECORD NOT FOUND` | The account update/rewrite fails during posting (`2800-UPDATE-ACCOUNT-REC`). |

When posting is rejected in batch, the reason code (`PIC 9(04)`) and description
(`PIC X(76)`) are written to the fixed-width **430-byte** `DALYREJS` reject
record for golden-master parity with the mainframe output. The four online
message codes 100–103 correspond to the user-facing messages defined in the
legacy `CSMSG01Y` / `CSMSG02Y` copybooks. In the REST API these domain
conditions map to HTTP responses as follows: not-found lookups (100 / 101) →
`404` when addressed as a direct resource lookup, over-limit and rewrite
conflicts (102 / 109) → `409`, and remaining validation rejects (103) → `422`,
each carrying the numeric code and description in the response body.

## Conventions

- **Content type.** Requests and responses use `application/json`, except the
  report download endpoint, which returns `text/csv` or `application/pdf`
  attachments.
- **Field naming.** All request and response fields use `snake_case`.
- **Monetary values** are serialized as **decimal strings** (for example,
  `"1250.00"`), never as JSON numbers, to preserve exact fixed-point precision
  (no floating-point rounding).
- **Timestamps** are ISO-8601 with timezone (for example,
  `"2026-05-01T09:15:00Z"`); the fields `orig_ts` and `proc_ts` follow this
  format.
- **Dates** are ISO `YYYY-MM-DD`.
- **Sensitive data.** `card_num` and `ssn` are masked in responses; `cvv` is
  never persisted to or returned in any response; `password_hash` is never
  returned by any endpoint.
- **Authentication.** Authenticated requests carry the `carddemo_session` cookie
  (session baseline) or an `Authorization: Bearer <token>` header (JWT mode).

## Related Documentation

- [Architecture](./architecture.md) — system design and layering.
- [Data Model](./data-model.md) — tables, columns, keys, and field lengths.
- [Traceability](./traceability.md) — legacy artifact to modern module mapping.
- [Batch Jobs](./batch.md) — the Python CLI batch chain and job reference.
- [Project README](../README.md) — build, run, and quick-start instructions.
