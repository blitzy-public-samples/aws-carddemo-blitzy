# Domain Context

This document explains **what CardDemo does** and **where the authoritative
behavior lives in the legacy source**, so you can reason about any change without
guessing. CardDemo is an AWS-published mainframe modernization sample: a
credit-card account-management system originally implemented in COBOL, CICS,
VSAM, and JCL. The migration preserves 100% of its observable behavior and adds
no business features.

> **Authority principle.** The relocated COBOL/JCL/CSD/catalog files under
> [`legacy/`](../../legacy) are the **single source of truth** for behavior. They
> are retained read-only for reference and are **never edited** (see
> [Pitfalls](./pitfalls.md)). When code and documentation disagree, the legacy
> source wins.

---

## 1. The business, in one paragraph

CardDemo manages customers, their accounts, and the cards on those accounts.
Cardholders accrue transactions; a nightly batch flow validates and posts those
transactions to account balances, applies interest by disclosure group, and
produces statements and reports. A small set of online screens lets clerks and
administrators view and maintain customers, accounts, cards, and transactions,
pay bills, request reports, and manage users. Authorization is role-based:
**`A` = Administrator**, **`U` = regular User**.

---

## 2. Core entities and their legacy record layouts

Each VSAM dataset maps to one PostgreSQL table; each record layout is defined by
a copybook under [`legacy/cpy/`](../../legacy/cpy). The monetary fields are COBOL
packed decimal (`COMP-3`) and migrate to `DECIMAL(x,2)` / `BigDecimal` — never
floating point.

| Entity (table) | Copybook | Record length | Key notes |
|----------------|----------|---------------|-----------|
| Customer (`customer`) | `CVCUS01Y.cpy` | 500 B | SSN / government ID / DOB are sensitive |
| Account (`account`) | `CVACT01Y.cpy` | 300 B | 5 monetary fields; `ACCT-GROUP-ID` is a **grouping attribute**, not a foreign key (see §4) |
| Card (`card`) | `CVACT02Y.cpy` | 150 B | CVV is sensitive |
| Card cross-reference (`card_xref`) | `CVACT03Y.cpy` | 50 B | ties card ↔ account ↔ customer |
| Transaction (`transaction`) | `CVTRA05Y.cpy` | 350 B | 16-char id; two timestamps — origination and **processing** (see §3) |
| Daily transaction (`daily_transaction`) | `CVTRA06Y.cpy` | 350 B | staging input for posting |
| User security (`user_security`) | `CSUSR01Y.cpy` | 80 B | role `A`/`U` |
| Transaction type (`transaction_type`) | `CVTRA03Y.cpy` | reference | |
| Transaction category (`transaction_category`) | `CVTRA04Y.cpy` | reference | compound key (type, category) |
| Disclosure group (`disclosure_group`) | `CVTRA02Y.cpy` | 50 B | **compound** key (group, type, category); holds the interest rate |
| Transaction-category balance (`tran_cat_balance`) | `CVTRA01Y.cpy` | 50 B | compound key (account, type, category) |

The relational schema, indexes, and foreign keys are described in
[architecture — Data Model](../architecture.md) and mapped construct-by-construct
in the [traceability matrix](../traceability-matrix.md).

---

## 3. Two transaction timestamps — get this right

`CVTRA05Y` defines **two** 26-character timestamps on the transaction record:

- `TRAN-ORIG-TS` — origination timestamp, at offset 278.
- `TRAN-PROC-TS` — **processing** timestamp, at offset 304.

The VSAM alternate index used for chronological browsing
(`TRANSACT.VSAM.AIX`) is keyed on the **processing** timestamp: the catalog
listing ([`legacy/catlg/LISTCAT.txt`](../../legacy/catlg/LISTCAT.txt)) shows the
AIX with `KEYLEN=26` at `AXRKP=304`, i.e. `TRAN-PROC-TS`, and maps to the
`proc_ts` column. Ordering transactions by origination time is **not** the legacy
browse order. This distinction is a documented parity hotspot; see
[Pitfalls](./pitfalls.md).

---

## 4. The disclosure-group relationship — why there is no simple foreign key

An account carries a single `ACCT-GROUP-ID` (`CVACT01Y`, 10 chars). It is
tempting to model this as `account.group_id → disclosure_group`, but the
disclosure-group record (`CVTRA02Y`) has a **composite** primary key —
group id **+** transaction type **+** transaction category — so a group id
**alone is not unique** and cannot be a foreign-key target. The faithful design
keeps `group_id` as a plain grouping attribute on `account` and performs
disclosure lookups as composite `(group_id, type_cd, cat_cd)` queries at the
application layer. This is recorded as an intentional, source-faithful deviation
in the [decision log](../decision-log.md); see also [Pitfalls](./pitfalls.md).

---

## 5. Online surfaces (screens)

Seventeen online programs, each with a CICS transaction id and a BMS map, become
one REST controller + request/response DTO pair each. The DTOs preserve every
BMS field name, length, PIC-derived type, edit rule, and PF-key action.

| Txn | Program | Purpose |
|-----|---------|---------|
| CC00 | COSGN00C | Sign-on / authentication |
| CM00 | COMEN01C | Main menu |
| CA00 | COADM01C | Admin menu |
| CAVW | COACTVWC | Account view |
| CAUP | COACTUPC | Account update (largest program; extensive edit rules) |
| CCLI | COCRDLIC | Card list |
| CCDL | COCRDSLC | Card view |
| CCUP | COCRDUPC | Card update |
| CT00 | COTRN00C | Transaction list |
| CT01 | COTRN01C | Transaction view |
| CT02 | COTRN02C | Transaction add |
| CR00 | CORPT00C | Transaction reports |
| CB00 | COBIL00C | Bill payment |
| CU00 | COUSR00C | List users |
| CU01 | COUSR01C | Add user |
| CU02 | COUSR02C | Update user |
| CU03 | COUSR03C | Delete user |

> The CICS resource-definition file
> [`legacy/csd/CARDDEMO.CSD`](../../legacy/csd/CARDDEMO.CSD) registers 18
> transactions and 18 programs. One entry — transaction `CDV1` → program
> `COCRDSEC` — has **no COBOL implementation** in `legacy/cbl/` and is therefore a
> **source-only / reference-only** registry entry, not a production surface. Treat
> the 17 programs above as the online surface set. Library and TDQUEUE entries in
> the CSD are likewise reference-only.

Screen navigation was pseudo-conversational (CICS COMMAREA + `XCTL` +
`RETURN TRANSID`); it becomes explicit server-side flow state and controller
navigation. The first-entry-vs-re-entry flag (`CDEMO-PGM-CONTEXT`) must be
modeled explicitly so screen initialization behaves identically.

---

## 6. Batch flows

Batch programs under [`legacy/cbl/`](../../legacy/cbl) become Spring Batch jobs;
their JCL triggers live under [`legacy/jcl/`](../../legacy/jcl). The core nightly
flow and the reference/print jobs:

| Job | Program(s) | Trigger | Purpose |
|-----|-----------|---------|---------|
| Daily transaction validate | CBTRN01C | (daily validate) | read/validate daily input |
| Daily transaction posting | CBTRN02C | `POSTTRAN.jcl` | post to balances; reject codes 100/101/102/103 |
| Interest calculation | CBACT04C | `INTCALC.jcl` | interest by disclosure group |
| Statement generation | CBSTM03A + CBSTM03B | `CREASTMT.JCL` | statements (subprogram → injected file service) |
| Transaction report | CBTRN03C | `TRANREPT.prc` | transaction detail report |
| Account/Card/Xref/Customer master print | CBACT01C / CBACT02C / CBACT03C / CBCUS01C | (master print) | reference prints |
| Transaction combine | COMBTRAN (SORT) | `COMBTRAN` | combine/sort |
| Transaction backup | TRANBKP (IDCAMS REPRO) | `TRANBKP` | copy/unload backup |

Two behaviors are parity-critical and detailed in [Pitfalls](./pitfalls.md):

- **Posting reject codes.** CBTRN02C validates in a fixed order and writes a
  reason code: **100** cross-reference not found, **101** account not found,
  **102** over credit limit, **103** transaction after account expiration.
  Rejected records are written to the reject file (DALYREJS) with a running
  count; the reject record is a **430-byte** layout (350-byte transaction image +
  80-byte validation trailer), and the posting job returns **RC=4** when rejects
  occur.
- **Interest formula.** Reproduced to the cent with `BigDecimal`:
  `monthlyInterest = tranCatBal × intRate ÷ 1200`, scale 2, `HALF_UP`
  (CBACT04C).

> `REPROCT.ctl` is an **IDCAMS `REPRO`** (copy/unload) control member, not a SORT
> control member. Inline SORT card sequences (e.g. in `COMBTRAN`) are separate and
> are traced independently in the [traceability matrix](../traceability-matrix.md).

---

## 7. Seed and reference data

Seed data lives under [`legacy/data/ASCII/`](../../legacy/data/ASCII) as
**fixed-width, headerless** files (each record's width equals its copybook record
length — they are **not** CSV/delimited). The EBCDIC datasets under
[`legacy/data/EBCDIC/`](../../legacy/data/EBCDIC) are the on-mainframe binary
snapshots and are byte-protected via [`.gitattributes`](../../.gitattributes).

Two data facts that matter when seeding:

- **Users** exist only in the EBCDIC `USRSEC` dataset (10 rows); there is no ASCII
  `usrsec.txt`, so `user_security` seeds from the EBCDIC source.
- **`ACCDATA`** in the EBCDIC directory is a **byte-exact alias** of `ACCTDATA`;
  only `ACCTDATA.VSAM.KSDS` is authoritative.

Seed (ASCII snapshot) and live (EBCDIC) row counts can differ; treat each as a
point-in-time snapshot. Exact widths, row counts, and content hashes for every
artifact are catalogued in the [traceability matrix](../traceability-matrix.md).

---

## 8. Legacy authority map (where to look)

| You need… | Look in |
|-----------|---------|
| Record layouts / field types | [`legacy/cpy/`](../../legacy/cpy) (business), [`legacy/cpy-bms/`](../../legacy/cpy-bms) (screen symbolic maps) |
| Online program logic | [`legacy/cbl/CO*.cbl`](../../legacy/cbl) |
| Batch program logic | [`legacy/cbl/CB*.cbl`, `legacy/cbl/*.CBL`](../../legacy/cbl) |
| Screen field/attribute/PF-key definitions | [`legacy/bms/`](../../legacy/bms) |
| Batch triggers, dataset load/backup | [`legacy/jcl/`](../../legacy/jcl), [`legacy/proc/`](../../legacy/proc), [`legacy/ctl/`](../../legacy/ctl) |
| Transaction/program/mapset/file catalog | [`legacy/csd/CARDDEMO.CSD`](../../legacy/csd/CARDDEMO.CSD) |
| VSAM dataset attributes (key length, RKP, counts) | [`legacy/catlg/LISTCAT.txt`](../../legacy/catlg/LISTCAT.txt) |

Continue with [Extending the application](./extending.md) and
[Pitfalls](./pitfalls.md).
