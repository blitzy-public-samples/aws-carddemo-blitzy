# Business rule flags

Every business rule in the Common Business Oriented Language (COBOL) source that is ambiguous,
undocumented or inconsistent. Each one is recorded here with its file and line citation, what this
platform does about it, and what a human owner has to decide.

The specification's register holds 26 items. This file carries 32 entries, because six register items
each cover two distinct source sites and are recorded separately here. The mapping is in
[Register coverage](#register-coverage), so no register item is unaccounted for.

The default handling is to reproduce the source behaviour and flag it. Parity is the contract, and a
correctness improvement is a separate decision a human owner must make. Where an item is instead
corrected, the entry says so and states why the correction changes no outcome.

One place answers differently from the source on purpose, and it is recorded apart from the register in
[Departures this platform makes from the source](#departures-this-platform-makes-from-the-source),
because it is a decision about this platform rather than a finding about the source.

Each item marked **DECISION OWED** needs a human answer. Each item marked **CLOSED** needed no answer
beyond the handling stated.

- [Register coverage](#register-coverage)
- [The largest item: a named program that does not exist](#the-largest-item-a-named-program-that-does-not-exist)
- [Authorization and posting](#authorization-and-posting)
- [Arithmetic and precision](#arithmetic-and-precision)
- [Atomicity and duplicate delivery](#atomicity-and-duplicate-delivery)
- [Copybook and configuration inconsistencies](#copybook-and-configuration-inconsistencies)
- [Identity, roles and validation](#identity-roles-and-validation)
- [Fixture and data inconsistencies](#fixture-and-data-inconsistencies)
- [Departures this platform makes from the source](#departures-this-platform-makes-from-the-source)
- [Measurement discrepancies against the specification](#measurement-discrepancies-against-the-specification)
- [One resolved naming defect](#one-resolved-naming-defect)

<br/>

## Register coverage

The specification's 26 register items map onto the 32 entries below. Where one register item is split,
the split separates two source sites that need different handling.

| Register item | Entries here |
| :--- | :--- |
| 1 the named program is absent | 1 |
| 2 the validation set declares itself incomplete | 2 |
| 3 card status unchecked | 3 |
| 4 account status untested | 4 |
| 5 the overlimit test ignores the balance | 5 |
| 6 the refund sign convention | 6 |
| 7 precision narrowing | 10 and 11, split between the posting and the interest program |
| 8 timestamp precision | 14 |
| 9 no rollback across the three writes | 15 and 7, split between the atomicity gap and the unread reason 109 |
| 10 no idempotency | 17 |
| 11 the cycle reset in an out-of-scope program | 32 and 31, split between the reset and the interest program's other gaps |
| 12 two divergent customer copybooks | 19 |
| 13 the undocumented date tolerance | 24 |
| 14 the inverted condition name | [One resolved naming defect](#one-resolved-naming-defect) |
| 15 identifier generation is a race | 18 |
| 16 the online screen performs no authorization | 8 |
| 17 orphan resource definitions | 21 |
| 18 the specification attributes a layout to the wrong copybook | 23, with the loader-attribution correction |
| 19 abandoned authorization intent in the menu table | 25 |
| 20 an unreachable role check | 25 |
| 21 identity is not refreshed | 26 |
| 22 over-allocated menu arrays | 22 |
| 23 a two-file update with recovery disabled | 16 |
| 24 no checksum validation | 28 |
| 25 fixture width mismatch | 29 and 30, split between the width gap and the absent security-user fixture |
| 26 a dead duplicate copybook | 20 |

Three entries below have no register ancestor and were measured while remediating: 9, the raw text
comparison of the expiry date; 12, the measured absence of `ROUNDED`; and 13, the tolerant numeric
parse. Item 27, the plaintext password comparison, appears in the specification as a flagged question
outside the numbered register.

<br/>

## The largest item: a named program that does not exist

### 1. The authorization program named in the requirements is absent — DECISION OWED

The requirements name `COPAUA0C` and a transaction `CP00` as the online authorization path.

- `app/cbl/` holds exactly 28 programs and none of them is `COPAUA0C`.
- `app/csd/CARDDEMO.CSD` defines no transaction named `CP00`.
- A search for the fragments a renamed equivalent would carry matches no source member.

The behaviour was therefore synthesised from three programs that do exist, and the synthesis is
declared and not presented as a migration:

| Contribution | Source |
| :--- | :--- |
| The four decline rules and their verbatim texts | `app/cbl/CBTRN02C.cbl:L380-L420` |
| The synchronous request contract and its field validation | `app/cbl/COTRN02C.cbl` |
| The credential comparison and the identity fork | `app/cbl/COSGN00C.cbl:L223-L240` |

**What a human owner must confirm:** that the batch validation path is the right ancestor for a
synchronous authorization service, and that no authorization program exists outside this repository.

<br/>

## Authorization and posting

### 2. The validation set declares itself incomplete — CLOSED

`app/cbl/CBTRN02C.cbl:L377` carries the comment `* ADD MORE VALIDATIONS HERE` inside paragraph
`1500-VALIDATE-TRAN`.

Handling: `domain/DeclineRule` is that extension point. One more rule is one more class in
`domain/rules`, and `domain/AuthorizationService` collects every implementation the container
supplies.

### 3. Card active status is never checked on the posting path — DECISION OWED

`app/cbl/CBTRN02C.cbl:L29-L64` opens six files and the card file is not among them.
`app/jcl/POSTTRAN.jcl` allocates no card dataset. `app/cbl/CBTRN01C.cbl` is the only batch program
that opens the card file beside the daily feed, and it writes nothing.

Handling: not added. A transaction on an inactive card authorizes, exactly as it does in the source.

**What a human owner must decide:** whether to add the check and accept the parity break.

### 4. Account active status is never tested — DECISION OWED

`app/cpy/CVACT01Y.cpy:L6` declares `ACCT-ACTIVE-STATUS`. No program reads it before posting.

Handling: the column exists and no rule reads it. A closed account still posts.

**What a human owner must decide:** the same question as item 3.

### 5. The overlimit test ignores the current balance — DECISION OWED

`app/cbl/CBTRN02C.cbl:L403-L407` computes the working balance from `ACCT-CURR-CYC-CREDIT` minus
`ACCT-CURR-CYC-DEBIT` plus the transaction amount, and compares it with `ACCT-CREDIT-LIMIT`. The
account's `ACCT-CURR-BAL` takes no part. Two notions of balance coexist in one record with no
documented relationship between them.

Two commented-out diagnostic statements sit immediately above the comparison at
`app/cbl/CBTRN02C.cbl:L401-L402`, which suggests the original author was debugging this expression.

Handling: reproduced exactly in `domain/rules/CreditLimitRule`.

**What a human owner must decide:** which balance the rule is meant to test.

### 6. A refund tightens the next authorization — DECISION OWED

`app/cbl/CBTRN02C.cbl:L551` adds a negative amount to `ACCT-CURR-CYC-DEBIT`, making the accumulator
more negative. `app/cbl/CBTRN02C.cbl:L404` subtracts that accumulator, so a refund raises the tested
working balance and reduces the credit available for the next transaction.

Fifty of the 300 records in `app/data/ASCII/dailytran.txt` carry a negative amount, so the path is
exercised by the fixture.

Handling: reproduced.

**What a human owner must decide:** whether the sign convention is intended.

### 7. Reject reason 109 is assigned and never inspected — CLOSED

`app/cbl/CBTRN02C.cbl:L556-L558` sets reason 109 when the account rewrite hits an invalid-key
condition. That assignment happens after the category balance has been written and before the
transaction record is written, and nothing in the program ever reads the field again.

Handling: **corrected deliberately.** A missing account row at that point is a genuine fault. The
ledger consumer raises it and refuses the acknowledgement, and the record reaches the dead-letter topic
after retries. The change is recorded in [decision-log.md](decision-log.md).

### 8. The online capture screen performs no authorization — CLOSED

`app/cbl/COTRN02C.cbl` resolves the cross-reference, checks that fields are non-empty, parses
amounts and validates two dates. It applies no decline rule, so it accepts transactions the batch job
later rejects.

Handling: the target authorizes synchronously, which is the engagement's purpose. The divergence from
the source's online path is declared.

### 9. The account expiry comparison is a raw text comparison — CLOSED

`app/cbl/CBTRN02C.cbl:L414-L420` compares `ACCT-EXPIRAION-DATE` against the first ten characters of
the transaction origin timestamp, character by character. The comparison is correct only because both
values happen to be formatted year-month-day, where lexical order matches chronological order.

Handling: preserved as a text comparison, and the column stays `VARCHAR(10)`. A date column would be
tidier and would change results whenever the field held anything other than a well-formed date.

<br/>

## Arithmetic and precision

### 10. Precision narrows in the credit-limit comparison — DECISION OWED

`app/cbl/CBTRN02C.cbl:L187` declares `WS-TEMP-BAL PIC S9(09)V99`. Both accumulators feeding it are
`PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy:L12-L13`, and the credit limit it is compared against is
`PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy:L8`.

A cycle balance at or above one billion silently loses its high-order digit. Because the comparison
approves when the limit is greater than or equal to the working value, losing that digit turns a
decline into an approval.

Handling: reproduced. The fixture cannot reach the boundary, so a synthetic boundary case proves the
narrowing behaves as the source does.

**What a human owner must decide:** whether to widen the field.

### 11. The interest program narrows precision the same way — DECISION OWED

`app/cbl/CBACT04C.cbl:L168-L169` declares two `PIC S9(09)V99` working fields that accumulate into a
`PIC S9(10)V99` balance at `app/cbl/CBACT04C.cbl:L352`.

Handling: interest is not migrated, so no target code carries the narrowing. The equivalence test
covers the rate rules only.

**What a human owner must decide:** the same question as item 10, when interest is migrated.

### 12. No arithmetic statement rounds — CLOSED

The `ROUNDED` phrase appears zero times across all 28 programs under `app/cbl/`. Every arithmetic
store therefore truncates toward zero.

Handling: `libs/cobol-compat` `CobolDecimal` pins `RoundingMode.DOWN` and no helper accepts a mode.
A test asserts that half-up rounding produces a different answer, so a later simplification fails
loudly.

### 13. Numeric parsing is tolerant, not strict — CLOSED

`app/cbl/COTRN02C.cbl:L204` and `app/cbl/COACTUPC.cbl:L2201` use the currency-aware numeric
conversion function, which accepts currency symbols and thousands separators. It is not equivalent to
constructing a decimal from a string.

Handling: `libs/cobol-compat` `NumvalParser` reproduces the tolerance and the validity gate, and every
input path shares it.

### 14. The processing timestamp carries two significant fractional digits — CLOSED

`app/cbl/CBTRN02C.cbl:L159` declares the field `PIC X(26)`, and `L160-L174` redefine it into
components ending in a two-digit fractional field plus a four-character remainder. The routine that
fills it takes hundredths from a two-character field and hard-codes four zero characters into the
remainder at `app/cbl/CBTRN02C.cbl:L701`.

A service stamping a current instant would differ from the source in the last four digits of every
record.

Handling: the `postedAt` pattern on the posted event enforces the source form, and the notification
retention horizon is rendered the same way. An incidental detail in the same redefinition: the
separator fields are named in Dutch while every neighbouring field is named in English.

<br/>

## Atomicity and duplicate delivery

### 15. Three writes run with no rollback — CLOSED

`app/cbl/CBTRN02C.cbl:L440-L442` performs the category-balance update, the account update and the
transaction insert unconditionally and in that order. No unit of work spans them. Every file
definition in `app/csd/CARDDEMO.CSD:L3-L9` specifies `RECOVERY(NONE) JOURNAL(NO)`.

Handling: the target commits the state change and the outbox row in one local transaction. Atomicity
is an addition and is declared as one.

### 16. Two files are rewritten with recovery disabled — CLOSED

`app/cbl/COACTUPC.cbl:L4066` and `app/cbl/COACTUPC.cbl:L4086` rewrite the account and customer files
in one logical update. `app/cbl/COACTUPC.cbl:L4100` reaches `SYNCPOINT ROLLBACK` only on one path.

Handling: one database transaction covers both writes.

### 17. No duplicate detection exists anywhere — CLOSED

`app/cbl/CBTRN02C.cbl:L562-L579` writes the transaction record and reaches `9999-ABEND-PROGRAM` on
any non-normal file status. A replayed feed therefore hits a duplicate key and abends.

Handling: every consumer claims its event identifier with one statement before it acts, in the same
transaction as its side effects. Idempotency is an addition and is declared as one.

Nine listeners now share that guarantee across four services, and notification's marker table carries
the consumed topic beside the identifier, because one event identifier must remain claimable once per
consumer group rather than once per service.

### 18. Transaction identifier generation is a race in both channels — CLOSED

`app/cbl/COTRN02C.cbl:L444-L451` and `app/cbl/COBIL00C.cbl:L212-L219` both browse the transaction
file backwards from high values and add one. Two concurrent callers can read the same high value.

Handling: **replaced deliberately.** A database sequence generates the identifier. The generated value
does not match what the source would have produced for the same input, and that is recorded in
[decision-log.md](decision-log.md).

<br/>

## Copybook and configuration inconsistencies

### 19. Two divergent customer copybooks — DECISION OWED

`app/cpy/CVCUS01Y.cpy` and `app/cpy/CUSTREC.cpy` declare identical field lists at identical Picture
clauses, with one differing date field name at `app/cpy/CVCUS01Y.cpy:L19` and
`app/cpy/CUSTREC.cpy:L19`. One file is indented with literal tab characters. Their version stamps are
one second apart. Four programs bind to `CVCUS01Y.cpy` and `app/cbl/CBSTM03A.CBL:L55` binds to
`CUSTREC.cpy`.

Handling: `CVCUS01Y.cpy` adopted as canonical on the four-program majority. The fork is documented and
neither file is modified.

**What a human owner must decide:** which copy is authoritative, and whether the other should be
retired.

### 20. A dead duplicate copybook — DECISION OWED

`app/cpy/UNUSED1Y.cpy` declares a record that is a byte-for-byte clone of the security user record at
`app/cpy/CSUSR01Y.cpy:L17-L23`, with every field renamed to an unused prefix. Nothing references it,
and its version stamp is later than its neighbours.

Handling: classified dead and excluded.

**What a human owner must decide:** whether to retire the file. Retirement would modify `app/`, which
this engagement forbids.

### 21. Orphan resource definitions — DECISION OWED

`app/csd/CARDDEMO.CSD:L211` defines a program with no corresponding source member, and
`app/csd/CARDDEMO.CSD:L388-L390` defines a transaction pointing at it.

Handling: excluded as dead configuration.

**What a human owner must decide:** whether a source member exists outside this repository.

### 22. Over-allocated menu arrays — CLOSED

`app/cpy/COMEN02Y.cpy:L21` declares a table of twelve slots with a populated count of ten at
`app/cpy/COMEN02Y.cpy:L88`. `app/cpy/COADM02Y.cpy:L20` declares nine slots with a count of four.

Handling: out of scope. Menu navigation has no target equivalent.

### 23. Batch programs credited with loading data load nothing — CLOSED

`app/cbl/CBACT01C.cbl`, `app/cbl/CBACT02C.cbl`, `app/cbl/CBACT03C.cbl`, `app/cbl/CBCUS01C.cbl` and
`app/cbl/CBTRN01C.cbl` were each measured to perform zero write or rewrite operations and to open
every dataset for input only. `app/cbl/CBTRN01C.cbl` carries a header comment claiming otherwise.

The actual loading happens in the IDCAMS copy steps inside the job members, which is where the Flyway
seed migrations derive from.

Handling: each program is read as evidence and excluded as a migration target.

<br/>

## Identity, roles and validation

### 24. An undocumented date-validation tolerance — DECISION OWED

`app/cbl/COTRN02C.cbl:L397-L400` accepts a date when the validator returns severity zero **or** when
it returns one specific message number. No comment explains the second case. The same tolerance is
applied to a second date field at `app/cbl/COTRN02C.cbl:L409-L414`.

Handling: preserved in `libs/cobol-compat` `CobolDateValidator`, with a test that passes only because
of the tolerance.

**What a human owner must decide:** whether the tolerance is intended.

### 25. An unreachable role check — CLOSED

Every entry in the menu table at `app/cpy/COMEN02Y.cpy:L88-L92` carries the ordinary-user type byte,
so the administrator-only branch at `app/cbl/COMEN01C.cbl:L136-L137` can never fire. The effective
role model is the signon-time fork alone.

One menu option carries a commented-out administrator-only label above its active label, and its
user-type byte contradicts that label. That is evidence that authorization was contemplated once.

Handling: the effective model is implemented. The unreachable branch is not recreated.

### 26. Identity is not refreshed before dispatch — CLOSED

Both statements that would copy the signed-on user and role into the shared communication area are
commented out at `app/cbl/COMEN01C.cbl:L149-L150`.

Handling: not reproduced. The target carries identity per request, so there is no shared area to
refresh.

### 27. The password comparison is plaintext — DECISION OWED

`app/cbl/COSGN00C.cbl:L223` compares the stored and supplied password directly.

Handling: preserved, kept out of the authorization decision path, and named as a security task.
Hashing would change the comparison and break parity.

**What a human owner must decide:** whether to accept the parity break and hash the credential.

### 28. No card-number checksum validation — DECISION OWED

`app/cbl/COCRDUPC.cbl:L194` and `app/cbl/COCRDUPC.cbl:L784` validate a card number as sixteen numeric
digits and nothing else.

Handling: not added. An invalid number is accepted.

**What a human owner must decide:** whether to add a Luhn check and accept the parity break.

<br/>

## Fixture and data inconsistencies

### 29. Fixture widths disagree between the two data sets — CLOSED

`app/data/ASCII/cardxref.txt` carries 36-byte records where `app/cpy/CVACT03Y.cpy` declares 50. The
binary twin under `app/data/EBCDIC/` carries the full 50-byte layout.

Handling: `CardDemoFixtureLoader` is width-tolerant. A loader that assumed the declared width would
fail on real data.

### 30. The text fixture set has no security-user file — CLOSED

`app/data/EBCDIC/` carries `AWS.M2.CARDDEMO.USRSEC.PS` and `app/data/ASCII/` carries no counterpart.
The root `README.md` points at the text directory for sample files.

Handling: signon fixtures are constructed instead of loaded. Correcting the pointer is unrelated to
this migration and is recorded in [suggested-next-tasks.md](suggested-next-tasks.md).

### 31. The interest program substitutes a literal on a lookup miss — CLOSED

`app/cbl/CBACT04C.cbl:L419` substitutes a literal default disclosure group when the lookup misses.
`app/cbl/CBACT04C.cbl:L518-L520` is an unimplemented fee paragraph. `app/jcl/INTCALC.jcl` passes a
hard-coded parameter.

Handling: the fallback is verified by the interest rate equivalence test. The fee paragraph and the
computation are not migrated.

### 32. The cycle-counter reset lives in an out-of-scope program — CLOSED

`app/cbl/CBACT04C.cbl:L353-L354` is the only code that zeroes the two accumulators the credit-limit
rule reads. Interest calculation is out of scope, so without a substitute the available credit shrinks
until every transaction declines.

Handling: `POST /accounts/{accountId}/cycle-close` reproduces those two statements and nothing else.
Line `L352`, which adds the accrued interest, is not reproduced.

One consequence is worth stating because it is not obvious from the source. The source held one
`ACCTDAT` record, so zeroing the accumulators there was the whole of the reset. This platform holds
that state in three places: the account service owns it, and both the authorization service and the
ledger keep a replica. The close therefore publishes `AccountStateChanged`, and each replica applies
the zeroed values under its own consumer group. A replica that stopped being fed would keep reading
grown accumulators, which is why authorization refuses traffic once its replica passes
`carddemo.replica.max-staleness` rather than deciding against numbers it knows to be old.

<br/>

## Departures this platform makes from the source

The 32 entries above record source behaviour. This section records the opposite case: a place where
this platform deliberately answers differently from the source. It is separate from the register
because it is not a defect in the source, and it is here rather than only in
[decision-log.md](decision-log.md) because a reader checking parity will look for it here.

### D1. Card detail refuses a row that belongs to another account — CORRECTED, NOT REPRODUCED

`app/cbl/COCRDSLC.cbl` reads a card by its number alone. Paragraph `9100-GETCARD-BYACCTCARD` moves
the card number into the record key at `:L740`, and the statement above it, which would have moved the
account identifier into the same key, is commented out at `:L739`. A signed-on user who typed any
account identifier beside a real card number was therefore shown that card.

The card service refuses that request with the same absent-row answer the source gives when its own
`NOTFND` limb fires, whose text is `Did not find cards for this search condition`. The refusal is a
departure, and reproducing the source here was rejected for one reason: the gap is not a source rule
at all. The source reached this program only through the 3270 signon at `app/cbl/COSGN00C.cbl`, which
granted every signed-on user every card, so it had no per-card authorization for the omission to
weaken. This platform does have one — the card detail route is authorized against the caller's own
derived card token — and a route that authorizes the card and then ignores the account it was asked
about lets one authority read a row it was never granted.

Transformation rule T7 asks for a source defect to be reproduced and flagged. This is instead a gap in
the target's own additive authorization layer, which T7 does not cover, so it is closed rather than
reproduced.

**What a human owner should know:** a client that sends a real card number beside the wrong account
identifier now receives an absent-row answer where the source returned the card. No fixture request
does this: each of the 50 cards in `app/data/ASCII/carddata.txt` names one account, and the shipped
demonstration always sends the pair from the same row.

<br/>

## Measurement discrepancies against the specification

Two counts in the Agent Action Plan differ from what the shipped source holds. The source governs, and
the platform is built on the measured values.

| Claim in the specification | Measured in the source | Effect |
| :--- | :--- | :--- |
| 19 programs and 19 transactions in `app/csd/CARDDEMO.CSD` | **18** of each | The before-state figures in [architecture-before-after.md](architecture-before-after.md) and the repository `README.md` carry 18 |
| 16 mapsets in `app/csd/CARDDEMO.CSD` | **17**, matching the 17 sources under `app/bms/` | The presentation layer is excluded either way, so no target changes |

Neither count changes a business rule. Both are recorded because a figure a reader can check has to be
right.

<br/>

## One resolved naming defect

Two expiry field names transpose a word: `ACCT-EXPIRAION-DATE` at `app/cpy/CVACT01Y.cpy:L11` and
`CARD-EXPIRAION-DATE` at `app/cpy/CVACT02Y.cpy:L9`. One paragraph name transposes two letters:
`WIRTE-JOBSUB-TDQ` at `app/cbl/CORPT00C.cbl:L515`. One condition name is inverted: the condition
named for an invalid date at `app/cbl/CSUTLDTC.cbl:L62` tests the all-zeros feedback token, which
means the date is valid.

Handling: the target corrects all four in column, field, class and method names. No behaviour depends
on the identifier text, so each correction changes no outcome. Every rename is listed in
[traceability-matrix.md](traceability-matrix.md).
