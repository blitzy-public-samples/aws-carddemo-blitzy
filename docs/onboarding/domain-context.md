# CardDemo — Domain Context

This document is a **functional primer** for developers who are new to the migrated
CardDemo codebase. CardDemo is a **credit-card account-management application** —
originally written in COBOL/CICS/VSAM/JCL and now re-platformed to **Java 25 (LTS) +
Spring Boot 3.5.16** — and this Java re-platforming preserves **100% of the original
COBOL business behavior with no new features**. The original COBOL source is retained
read-only under [`legacy/`](../../legacy) for reference (relocated there during the
migration and kept immutable — it is never edited).

**Reader takeaway.** After reading this page you will understand the two kinds of user
(the *actors*), the core business *entities*, the 17 online *screens*, and the 11 *batch
jobs* — enough to make sense of the layered Java code. From here, continue to
[`../architecture.md`](../architecture.md) for the layered design and data model, and to
[`../traceability-matrix.md`](../traceability-matrix.md) for the exhaustive,
paragraph-level COBOL→Java mapping.

---

## What CardDemo does

CardDemo is a **Credit Card management application**. It lets users manage **Accounts**,
**Credit Cards**, **Transactions**, and **Bill Payments**. Around those four capabilities
sit the supporting concerns you would expect of a card system: customers who own the
accounts, a nightly batch flow that posts transactions to balances and applies interest,
statements and reports, and administrative user management.

### The two user types

Authorization is role-based, and there are exactly **two roles**:

- **Regular User** — performs the day-to-day / back-office functions: viewing and updating
  accounts and cards, listing/viewing/adding transactions, requesting reports, and paying
  bills.
- **Admin User** — performs the admin-only functions, which in CardDemo means **user
  management** (list, add, update, delete application users).

The role originates from the legacy COMMAREA condition names `CDEMO-USRTYP-ADMIN` (`'A'`)
and `CDEMO-USRTYP-USER` (`'U'`) defined in
[`legacy/cpy/COCOM01Y.cpy`](../../legacy/cpy/COCOM01Y.cpy). In the Java target this maps
directly to the Spring Security roles **`ADMIN`** (`'A'`) and **`USER`** (`'U'`).

### Demo logins (orientation only)

Two seeded logins are useful for orientation: **`ADMIN001`** (type `A` = Admin) and
**`USER0001`** (type `U` = Regular User). They exist in the seeded `user_security` table
and share a well-known demo passphrase (`PASSWORD`).

> **These are legacy demo seed data, not a security recommendation.** The demo passphrase
> is a convenience inherited from the original mainframe sample and must be re-secured
> before any non-demo use. Credentials are externalized (never hardcoded); the seeded
> value is stored as a hashed password, and password-hashing and CVV-handling hardening
> are recorded as intentional security improvements in
> [`../decision-log.md`](../decision-log.md). See
> [`./getting-started.md`](./getting-started.md) for how to run the application and log in.

---

## Core domain entities

The business entities are JPA entities under the `com.aws.carddemo.domain` package, each
mapped to one PostgreSQL 16 table (created and seeded by Flyway). Every entity derives from
a legacy record layout (copybook) under [`legacy/cpy/`](../../legacy/cpy).

| Entity | Java class (`com.aws.carddemo.domain`) | Table | Purpose |
|--------|----------------------------------------|-------|---------|
| Customer | `Customer` | `customer` | Cardholder personal data — name, address, SSN, date of birth (**SSN / DOB are sensitive**). |
| Account | `Account` | `account` | Credit-card account — balances, credit limit, cycle credit/debit, expiration. All **monetary fields are `BigDecimal` / `DECIMAL(12,2)`**. |
| Card | `Card` | `card` | A physical/virtual card — 16-char card number, CVV (**sensitive**), expiry, status. |
| Card Cross-reference | `CardXref` | `card_xref` | Links **card ↔ account ↔ customer**. |
| Transaction | `Transaction` | `transaction` | Posted transactions — 16-char id, amount (`BigDecimal` / `DECIMAL(11,2)`), type/category, origination and processing timestamps. |

### Reference data

The following reference and staging tables complete the model:

| Entity | Java class | Table | Notes |
|--------|-----------|-------|-------|
| Transaction Type | `TransactionType` | `transaction_type` | Lookup of transaction type codes. |
| Transaction Category | `TransactionCategory` | `transaction_category` | Compound key `(type_cd, cat_cd)`. |
| Disclosure Group | `DisclosureGroup` | `disclosure_group` | Compound key `(group_id, type_cd, cat_cd)`; holds the interest rate as `DECIMAL(6,2)`. |
| Transaction Category Balance | `TransactionCategoryBalance` | `tran_cat_balance` | Compound key `(acct_id, type_cd, cat_cd)`; running balance as `DECIMAL(11,2)`; mutated by posting and interest calc. |
| Daily Transaction (staging) | `DailyTransaction` | `daily_transaction` | Staging input consumed by the daily posting job. |

### How the entities relate

Relationships that were enforced only in COBOL application logic are, in the target, made
into **real database foreign keys** — a documented integrity *improvement*, not a behavior
change (see [`../decision-log.md`](../decision-log.md)):

- `card` → `account`
- `card_xref` → `customer` **and** `account`
- `transaction` → `card`, `transaction_type`, and `transaction_category`
- `tran_cat_balance` → `account` **and** `transaction_category`

One relationship is deliberately **not** a single-column foreign key: an account carries a
`group_id`, but `disclosure_group` has a **compound** primary key
`(group_id, type_cd, cat_cd)`, so `group_id` **alone is not unique** and cannot be a
foreign-key target. Faithfully to the COBOL original, `account.group_id` is kept as a plain
grouping attribute and the applicable disclosure/interest row is resolved with a composite
`(group_id, type_cd, cat_cd)` lookup at the application layer. This source-faithful choice
is recorded in [`../decision-log.md`](../decision-log.md).

> **The money rule (get this right once).** Every monetary amount is a `BigDecimal` at
> **scale 2** with **`RoundingMode.HALF_UP`**, stored in `DECIMAL(x,2)` columns —
> **never** `double` or `float`. The exact interest-formula parity detail lives in
> [`./pitfalls.md`](./pitfalls.md).

---

## Online functions (screens)

The 17 online programs — each a CICS transaction backed by a BMS map — become one Spring
MVC **REST controller** each, under `com.aws.carddemo.web`. The table below is the
authoritative online surface.

| Transaction | Screen / Program | Java controller | Function | Access |
|-------------|------------------|-----------------|----------|--------|
| CC00 | COSGN00 / COSGN00C | `SignonController` | Signon | All |
| CM00 | COMEN01 / COMEN01C | `MainMenuController` | Main Menu | Regular User |
| CAVW | COACTVW / COACTVWC | `AccountViewController` | Account View | User |
| CAUP | COACTUP / COACTUPC | `AccountUpdateController` | Account Update | User |
| CCLI | COCRDLI / COCRDLIC | `CardListController` | Credit Card List | User |
| CCDL | COCRDSL / COCRDSLC | `CardViewController` | Credit Card View | User |
| CCUP | COCRDUP / COCRDUPC | `CardUpdateController` | Credit Card Update | User |
| CT00 | COTRN00 / COTRN00C | `TransactionListController` | Transaction List | User |
| CT01 | COTRN01 / COTRN01C | `TransactionViewController` | Transaction View | User |
| CT02 | COTRN02 / COTRN02C | `TransactionAddController` | Transaction Add | User |
| CR00 | CORPT00 / CORPT00C | `TransactionReportController` | Transaction Reports | User |
| CB00 | COBIL00 / COBIL00C | `BillPaymentController` | Bill Payment | User |
| CA00 | COADM01 / COADM01C | `AdminMenuController` | Admin Menu | Admin |
| CU00 | COUSR00 / COUSR00C | `UserListController` | List Users | Admin |
| CU01 | COUSR01 / COUSR01C | `UserAddController` | Add User | Admin |
| CU02 | COUSR02 / COUSR02C | `UserUpdateController` | Update User | Admin |
| CU03 | COUSR03 / COUSR03C | `UserDeleteController` | Delete User | Admin |

In the Java target these BMS screens are re-expressed as **REST request/response DTOs that
preserve every field name, length, type, edit rule, and PF-key action** — this is **not** a
newly rendered web UI, and no screen behavior is added or removed (no feature expansion).
The live endpoint catalog is available at the running application's Swagger UI
(`/swagger-ui.html`); see [`./getting-started.md`](./getting-started.md) to run it locally,
and [`../traceability-matrix.md`](../traceability-matrix.md) for the paragraph-level
mapping from each program to its controller and service.

---

## Batch jobs

The batch programs become Spring Batch **Job**s under `com.aws.carddemo.batch`. Each derives
from one or more legacy COBOL programs under [`legacy/cbl/`](../../legacy/cbl), triggered on
the mainframe by JCL under [`legacy/jcl/`](../../legacy/jcl).

| Spring Batch Job | Source COBOL | Purpose | JCL trigger |
|------------------|--------------|---------|-------------|
| `DailyTransactionPostingJob` | CBTRN02C | Core posting: validate staged transactions and post to balances | `POSTTRAN.jcl` |
| `DailyTransactionValidateJob` | CBTRN01C | Read and validate the daily-transaction input | (daily validate) |
| `InterestCalculationJob` | CBACT04C | Apply disclosure-group interest to category balances | `INTCALC.jcl` |
| `StatementGenerationJob` | CBSTM03A + CBSTM03B | Produce account statements (subprogram → injected file service) | `CREASTMT.JCL` |
| `TransactionReportJob` | CBTRN03C | Transaction detail report | `TRANREPT.prc` |
| `AccountMasterPrintJob` | CBACT01C | Read/print the account master | (master print) |
| `CardMasterPrintJob` | CBACT02C | Read/print the card master | (master print) |
| `XrefPrintJob` | CBACT03C | Read/print the card cross-reference | (master print) |
| `CustomerMasterPrintJob` | CBCUS01C | Read/print the customer master | (master print) |
| `TransactionCombineJob` | COMBTRAN (SORT) | Combine/sort transaction files | `COMBTRAN` |
| `TransactionBackupJob` | TRANBKP (IDCAMS REPRO) | Back up the transaction master | `TRANBKP` |

Job **scheduling** moves out of the application: the mainframe JCL schedule is reproduced by
the CI/CD workflow (`.github/workflows/ci.yml`), not an in-app scheduler. A JCL **SORT** becomes a Java `Comparator` or an `ORDER BY` query, and the
generation-data-group (GDG) backup becomes a scheduled database-backup step.

---

## Daily transaction posting flow

Posting is the central business process, and it is **parity-critical**. In plain language:

1. Daily transactions are **staged** in the `daily_transaction` table.
2. The `DailyTransactionPostingJob` reads each staged record and **validates** it.
3. A valid record is **posted** — its amount updates both the **account balance** and the
   relevant **transaction-category balance** (`tran_cat_balance`).
4. An invalid record is **rejected** with a numeric reason code.

The four reject reasons are assigned in this **exact evaluation order**:

- **100** — card / cross-reference not found
- **101** — account not found
- **102** — over credit limit
- **103** — transaction received after account expiration

The precise evaluation order, the reject-record layout, the batch return code, and the exact
computations are parity traps detailed in [`./pitfalls.md`](./pitfalls.md) (anchored to
[`legacy/cbl/CBTRN02C.cbl`](../../legacy/cbl/CBTRN02C.cbl)). Separately, the
`InterestCalculationJob` applies each account's disclosure-group interest **rate** to its
category balances; the interest formula (`BigDecimal` scale 2, `HALF_UP` per AAP §0.4.2 — an
intentional, documented divergence from the legacy truncation in the exact-half boundary case,
decision log **D31**; anchored to `legacy/cbl/CBACT04C.cbl`) is also documented in
[`./pitfalls.md`](./pitfalls.md).

---

## Related documentation

- [`./getting-started.md`](./getting-started.md) — run CardDemo locally (database, build, login).
- [`./extending.md`](./extending.md) — how to add or modify a feature while preserving parity.
- [`./pitfalls.md`](./pitfalls.md) — parity traps (posting order, interest formula) and suggested next tasks.
- [`../architecture.md`](../architecture.md) — the layered design and the VSAM→PostgreSQL data model.
- [`../traceability-matrix.md`](../traceability-matrix.md) — the exhaustive, bidirectional COBOL→Java mapping.
- [`../decision-log.md`](../decision-log.md) — why key decisions were made (foreign keys, optimistic locking, security hardening).
- [`../../README.md`](../../README.md) — project overview, build/run, and the full application inventory.
