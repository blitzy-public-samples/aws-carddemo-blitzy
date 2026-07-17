# Common Pitfalls (Migration Parity Traps)

This document lists the highest-risk **parity traps** in the CardDemo COBOL → Java
migration. Each entry links to the exact **legacy anchor** — a paragraph, copybook
field, or reject code in the retained COBOL under [`legacy/`](../../legacy) — and
states the **safe Java approach**. Getting any of these wrong is a **behavioral
regression**, not a cosmetic bug: the migrated system
(**Java 25 LTS + Spring Boot 3.5.16** over **PostgreSQL 16**) must reproduce the
observable behavior of the original **exactly**, with **no new business features**.

The original COBOL is retained read-only under [`legacy/`](../../legacy)
(relocated there from the original mainframe source tree); every COBOL reference
below uses a `legacy/**` path. For the complete source-construct → target mapping see the
[traceability matrix](../traceability-matrix.md), and for every intentional
deviation from a literal translation see the [decision log](../decision-log.md).

> **How to read this guide.** Each trap is tagged with its risk level. The two
> most likely to regress are the **reject-code evaluation order** and the
> **interest formula** — both are covered by row-for-row golden-file tests. When
> in doubt, open the cited `legacy/**` file and match its behavior to the letter.

---

## 1. Decimal fidelity — money is `BigDecimal`, never `double`/`float` (HIGH risk)

All monetary values in the legacy system are COBOL packed decimals
(`COMP-3`, `PIC S9(n)V99`). In Java they **must** be modeled as `BigDecimal` at
**scale 2** with an explicit `RoundingMode.HALF_UP`, backed by `DECIMAL(x,2)`
columns. **`double` and `float` are prohibited for money** — binary floating
point cannot represent these decimal values exactly, and any drift compounds
across postings. Centralize all monetary arithmetic in the `Money` value object
so rounding and scale are applied in exactly one place.

The single most sensitive computation is monthly interest. It must reproduce the
COBOL `COMPUTE` **to the cent**. The legacy anchor is
[`legacy/cbl/CBACT04C.cbl`](../../legacy/cbl/CBACT04C.cbl), paragraph
**`1300-COMPUTE-INTEREST`**:

```cobol
      1300-COMPUTE-INTEREST.

          COMPUTE WS-MONTHLY-INT
           = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

The Java equivalent (must match the COBOL result exactly) is:

```java
monthlyInterest = tranCatBal.multiply(intRate)
                            .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);
```

**Pitfall.** Choosing a different `RoundingMode`, dividing before multiplying, or
letting an intermediate value carry a scale other than 2 changes the result by a
cent — and that error **compounds** across every posting and interest run. This
computation lives in `InterestCalculationJob` (using the `Money` value object).

**Mitigation.** Golden-file tests compare the Java batch output **row-for-row**
against fixtures derived from the legacy record layouts and seed data; the
interest result is asserted to the cent. See the validation criteria in the
[traceability matrix](../traceability-matrix.md).

---

## 2. Batch posting reject-code order (HIGH risk)

The daily-transaction posting program validates each record and, on failure,
writes a numeric **reject reason code**. There are four codes, and their
**evaluation ORDER is parity-critical** — the order determines *which* code a bad
record receives. The legacy anchors are
[`legacy/cbl/CBTRN02C.cbl`](../../legacy/cbl/CBTRN02C.cbl), paragraphs
**`1500-A-LOOKUP-XREF`** and **`1500-B-LOOKUP-ACCT`**, dispatched in order from
`1500-VALIDATE-TRAN`:

```cobol
      1500-VALIDATE-TRAN.
          PERFORM 1500-A-LOOKUP-XREF.
          IF WS-VALIDATION-FAIL-REASON = 0
             PERFORM 1500-B-LOOKUP-ACCT
          ELSE
             CONTINUE
          END-IF
          EXIT.
```

The four reject codes, in the exact order they are assigned:

| Code | Meaning | Legacy paragraph / condition | Message |
|------|---------|------------------------------|---------|
| **100** | Card / cross-reference not found | `1500-A-LOOKUP-XREF` — `READ XREF-FILE` **INVALID KEY** | `INVALID CARD NUMBER FOUND` |
| **101** | Account not found | `1500-B-LOOKUP-ACCT` — `READ ACCOUNT-FILE` **INVALID KEY** | `ACCOUNT RECORD NOT FOUND` |
| **102** | Over credit limit | `1500-B-LOOKUP-ACCT` — see balance check below | `OVERLIMIT TRANSACTION` |
| **103** | Transaction after account expiration | `1500-B-LOOKUP-ACCT` — expiration check below | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` |

Codes **102** and **103** depend on a computed available balance and the account
expiration date (note the legacy field is spelled `ACCT-EXPIRAION-DATE` — an
original source spelling that is preserved as-is):

```cobol
      1500-B-LOOKUP-ACCT.
          ...
              COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                                  - ACCT-CURR-CYC-DEBIT
                                  + DALYTRAN-AMT

              IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
                CONTINUE
              ELSE
                MOVE 102 TO WS-VALIDATION-FAIL-REASON
                MOVE 'OVERLIMIT TRANSACTION'
                  TO WS-VALIDATION-FAIL-REASON-DESC
              END-IF
              IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
                CONTINUE
              ELSE
                MOVE 103 TO WS-VALIDATION-FAIL-REASON
                MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
                  TO WS-VALIDATION-FAIL-REASON-DESC
              END-IF
```

**Java approach.** A `RejectCode` enum `{100, 101, 102, 103}` and a
`PostingService` (driven by `DailyTransactionPostingJob`) reproduce the **exact
validation order and short-circuit behavior**: the cross-reference lookup runs
first, and only if it succeeds does the account lookup run; within the account
lookup, the over-limit check precedes the expiration check. The reject writer
preserves the **350-byte transaction record layout**.

**Pitfall.** Reordering the validations — for example, checking the account
before the cross-reference, or the expiration before the credit limit — changes
which code a given record receives, silently breaking parity. **Test each reject
path (100, 101, 102, 103) independently** with a fixture that triggers exactly
that condition.

---

## 3. Transaction ID generation — max-key + 1 (HIGH risk)

Online transaction-add generates the next transaction id by reading the **highest
existing** id and adding one. The legacy anchor is
[`legacy/cbl/COTRN02C.cbl`](../../legacy/cbl/COTRN02C.cbl), paragraph
**`ADD-TRANSACTION`**: it moves `HIGH-VALUES` into the key, performs a reverse
browse (`STARTBR` → `READPREV` → `ENDBR`) to land on the largest key, then
increments it:

```cobol
      ADD-TRANSACTION.
          ...
          MOVE HIGH-VALUES TO TRAN-ID
          PERFORM STARTBR-TRANSACT-FILE
          PERFORM READPREV-TRANSACT-FILE
          PERFORM ENDBR-TRANSACT-FILE
          MOVE TRAN-ID     TO WS-TRAN-ID-N
          ADD 1 TO WS-TRAN-ID-N
          ...
          MOVE WS-TRAN-ID-N         TO TRAN-ID
```

**Java approach.** The reverse-browse-from-`HIGH-VALUES` becomes a repository
**max-key lookup + 1**, encapsulated in `common/util/IdGenerator` (invoked by
`TransactionService`). Preserving the key ordering guarantees the generated id
matches what the legacy would have produced.

**Pitfall — concurrency.** Two concurrent adders can both read the same current
maximum and compute the **same** next id. The legacy pseudo-conversational,
record-at-a-time model made this rare; a stateless web tier makes it easier to
hit.

**Mitigation.** Guard id generation within a transactional boundary and rely on
**JPA optimistic locking (`@Version`)** so a losing writer fails cleanly rather
than duplicating an id. This locking is a **documented, intentional integrity
improvement** (see the [decision log](../decision-log.md)) — it is *not* a
behavioral regression; it prevents a corruption the legacy could not.

---

## 4. Pseudo-conversational state — the `CDEMO-PGM-CONTEXT` toggle (HIGH risk)

CICS online programs are **pseudo-conversational**: a program sends a screen and
returns control to CICS, and the entire user/session state is carried between
submits in the **COMMAREA**. The legacy anchor is
[`legacy/cpy/COCOM01Y.cpy`](../../legacy/cpy/COCOM01Y.cpy), which defines the
**`CDEMO-PGM-CONTEXT`** flag that distinguishes a **first-time entry** into a
program from a **re-entry** (a subsequent submit of the same screen):

```cobol
             10 CDEMO-PGM-CONTEXT             PIC 9(01).
                88 CDEMO-PGM-ENTER            VALUE 0.
                88 CDEMO-PGM-REENTER          VALUE 1.
```

This flag **drives screen initialization**: on first entry (`0`) the program
initializes and paints a fresh screen; on re-entry (`1`) it processes the
submitted input. Getting the toggle wrong changes what the user sees and how
input is handled.

**Java approach.** The COMMAREA becomes explicit server-side request/response
**flow/session context**; `XCTL` program transfer becomes controller navigation
that returns the next screen's DTO and logical view; and `RETURN TRANSID`
re-entry becomes a **stateless POST per screen submit**.

**Pitfall.** **Do not drop the first-time-vs-re-entry toggle.** Model
`CDEMO-PGM-CONTEXT` explicitly in every controller/service that mirrors an online
program, or screen-initialization behavior diverges from the legacy. See
[`../architecture.md`](../architecture.md) (hotspot **H1**) for the flow-state
design.

---

## 5. Roles and out-of-scope items

### 5.1 Preserve the `A`/`U` role model and Admin-only gating

User type is a single character in the COMMAREA. The legacy anchor is
[`legacy/cpy/COCOM01Y.cpy`](../../legacy/cpy/COCOM01Y.cpy), the 88-level
condition names on `CDEMO-USER-TYPE`:

```cobol
             10 CDEMO-USER-TYPE               PIC X(01).
                88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
                88 CDEMO-USRTYP-USER          VALUE 'U'.
```

These map to Spring Security roles: `A` → **`ADMIN`**, `U` → **`USER`**.

**Pitfall.** Do not lose the **Admin-only gating** on the user-management screens
(list / add / update / delete user). Only role `A` may reach them, exactly as in
the legacy admin menu path.

### 5.2 Explicitly OUT OF SCOPE — do not "restore" as missing features

The following are **not** part of the migration. They appear in the repository or
its documentation only as references or roadmap items, never as delivered COBOL
behavior. Do **not** implement them or treat them as gaps to be filled:

- **MQ / message-queue integration** — **roadmap-only**. It is listed solely under
  the README **Roadmap** section; there is **no message-queue code anywhere in the
  legacy source**. Do not build a producer/consumer.
- **RACF / mainframe security replatform** — only application-level authentication
  parity (role `A`/`U`) is migrated; no external identity provider or
  mainframe-security integration.
- **3270 terminal emulation / BMS screen rendering** — screens are re-expressed as
  REST request/response DTOs; no terminal emulator or pixel-level renderer is
  produced (that would be feature expansion).
- **AWS Mainframe Modernization (Micro Focus) runtime** — the tooling under
  `samples/` (compile JCL, build procs, `m2` runtime archives) is **reference
  only** and is not a migration target.

If you find yourself about to add any of the above, stop: it is out of scope by
design, documented in the [decision log](../decision-log.md).

### 5.3 Security anti-patterns are intentionally hardened — not scope creep

The legacy design stored **plaintext passwords** and **unencrypted card CVVs** —
intentional demonstration anti-patterns. The Java target **preserves the
authentication behavior** (the `A`/`U` role model above) but introduces
**password hashing** and **CVV handling hardening**: the **CVV is never logged or
returned in full**, and **passwords are never logged**. These are **documented
security improvements** (see the [decision log](../decision-log.md)) — do not
mistake them for scope creep, and never reintroduce plaintext handling.

> **No secrets in code or config.** Every connection string and credential is
> externalized to environment variables (for example `DB_URL`, `DB_USERNAME`,
> `DB_PASSWORD`) — see [`./getting-started.md`](./getting-started.md). Never
> hardcode a password or a CVV, and never write either to a log.

---

## 6. External fixed-width file contracts and encoding

### 6.1 Fixed-width layouts — column positions are the contract

The real external contracts are **fixed-width record layouts**: the
daily-transaction input, the reject output, and the statement / report outputs.
These are read and written with `common/util/FixedWidthCodec` driving Spring Batch
`FlatFileItemReader` / `FlatFileItemWriter`, preserving **exact column positions
and lengths**.

**Pitfall.** An **off-by-one** column position silently corrupts every downstream
field — the file still "parses," but the values are shifted. Always map columns
from the copybook record layout, and preserve the copybook's padding (spaces for
alphanumeric, leading zeros for numeric) on both read and write.

### 6.2 Encoding — preserve the layout, not the on-disk bytes

The legacy VSAM datasets stored data in **EBCDIC** with binary **`COMP-3`** packed
decimals. The Java / PostgreSQL target stores **native types**, and external file
exchange preserves the documented **record layout** — **not** the on-disk
EBCDIC / `COMP-3` encoding. Seed and staging data is loaded from the
**fixed-width, headerless ASCII** files under
[`legacy/data/ASCII/`](../../legacy/data/ASCII) (customer 50, account 50,
card 50, cross-reference 50, daily-transaction 300, plus the reference tables:
transaction-type 7, transaction-category 18, disclosure-group 51,
category-balance 50). The 10 **user-security** rows are **not** in ASCII — they
originate from the **EBCDIC** `USRSEC.PS` dataset (copybook `CSUSR01Y`).

**Pitfall.** Do not confuse **layout preservation** with **byte-for-byte on-disk
encoding**. The migrated system reproduces field positions, lengths, types, and
edit rules; it does not reproduce EBCDIC bytes or packed-decimal nibbles on disk.

---

## 7. Suggested next tasks

The following concrete follow-ups were discovered during the migration and are
recorded here per the onboarding requirement. Pick one, follow
[`./extending.md`](./extending.md), and record any decision in the
[decision log](../decision-log.md) and any construct mapping in the
[traceability matrix](../traceability-matrix.md):

- **Broaden golden-file parity fixtures.** Add more posting, interest, statement,
  and report cases compared **row-for-row** against the legacy record layouts, so
  the two highest-risk traps above (reject-code order and the interest formula)
  are exercised across more edge conditions.
- **Roll out password hashing fully** and finish **CVV-handling hardening**;
  track progress in the [decision log](../decision-log.md).
- **Expand OpenAPI examples** on the REST DTOs for richer Swagger documentation.
- **Add per-endpoint integration tests** (Testcontainers against real
  PostgreSQL 16) to push line coverage comfortably above the **80%** gate.
- **Wire additional Grafana panels / alerts** onto the observability stack
  (Actuator + Micrometer + OpenTelemetry).
- *(Optional)* **Evaluate the Spring Boot 4.x upgrade path** noted in the
  [decision log](../decision-log.md), weighing the 3.5 support lifecycle.

---

## Related documentation

- [`./getting-started.md`](./getting-started.md) — clean-machine to running app
- [`./domain-context.md`](./domain-context.md) — the business domain and data model
- [`./extending.md`](./extending.md) — how to add functionality the idiomatic way
- [`../architecture.md`](../architecture.md) — layered design and migration hotspots
- [`../decision-log.md`](../decision-log.md) — every non-trivial decision and deviation
- [`../traceability-matrix.md`](../traceability-matrix.md) — full source → target mapping
- [`../../README.md`](../../README.md) — project overview and build/run instructions

