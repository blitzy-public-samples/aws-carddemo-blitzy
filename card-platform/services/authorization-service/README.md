## Authorization Service

- [Purpose](#purpose)
- [Source provenance](#source-provenance)
- [Endpoints](#endpoints)
- [Decision chain](#decision-chain)
- [Events](#events)
- [Domain and data ownership](#domain-and-data-ownership)
- [Domain context](#domain-context)
- [Declared deviations](#declared-deviations)
- [Pitfalls](#pitfalls)
- [Architecture](#architecture)
- [How to extend](#how-to-extend)
- [Local run and tests](#local-run-and-tests)
- [Related documentation](#related-documentation)

<br/>

## Purpose

`authorization-service` owns `POST /authorizations`, the one synchronous Representational State Transfer (REST) endpoint a client calls in this platform. It resolves a card to an account, applies four decline rules in source order, and writes one outcome event through a transactional outbox for every request that reaches a decision. No other service writes the authorization decision.

The guarantee is one event per *decision*, not one per HTTP call. A request refused before the decision service is entered writes nothing: an unauthenticated or unauthorized call, a body the JSON reader cannot bind, a field the bean constraints reject, and a capture moment outside the staleness window are all answered from the web layer, and the outbox stays untouched. Every request that reaches the rule chain produces exactly one `TransactionAuthorized` or one `TransactionDeclined`.

Ledger posting, fraud detection, and notification each consume the authorized event under their own consumer group. None of the three calls this service back, and none of them calls another. This service reads no other service during a decision.

<br/>

## Source provenance

The brief named a program and a Customer Information Control System (CICS) transaction that this repository does not contain. Searching for both returns nothing. Run these two commands from the repository root, the directory holding `app/` and `card-platform/`:

```bash
grep -ril "COPAUA0C" app/          # zero files
grep -c "CP00" app/csd/CARDDEMO.CSD  # zero
```

This service is therefore a declared synthesis of three real programs, not a translation of one. The [traceability matrix](../../docs/traceability-matrix.md) records that synthesis and every field omitted along the way.

| Contribution | Source | Locator |
| :--- | :--- | :--- |
| The four decline rules and the abend contract | `app/cbl/CBTRN02C.cbl` | paragraphs `1500-A-LOOKUP-XREF` and `1500-B-LOOKUP-ACCT`, lines 380 to 420 |
| The synchronous request contract and tolerant numeric parsing | `app/cbl/COTRN02C.cbl` | lines 204 and 218, then 383 and 456 |
| Authentication and the administrator fork | `app/cbl/COSGN00C.cbl` | line 223, then lines 230 to 240 |
| Date validation semantics | `app/cbl/CSUTLDTC.cbl` | line 62 |
| The repository interface shape | `app/cbl/CBSTM03B.CBL` | lines 100 to 112 |
| The transactional outbox ancestor | `app/cbl/CORPT00C.cbl` | lines 517 to 518 |

Every rule the source leaves ambiguous, undocumented, or inconsistent is listed in [business rule flags](../../docs/business-rule-flags.md), which carries a sixty-five-item register with citations. Identifiers 1 to 26 are the set the specification fixes and 27 upward are appended, so a number always means the same finding.

<br/>

## Endpoints

| Method and path | Access | Purpose |
| :--- | :--- | :--- |
| `POST /authorizations` | `ACQUIRER` or `ADMIN` | Decide one transaction and produce one event for it |

Every business route requires HTTP Basic authentication. **Access to this route is decided by role alone.** `config/SecurityConfig.java` matches it with `hasAnyRole("USER", "ADMIN")` and installs no ownership manager, so an ordinary `USER` may authorize any card the platform holds and needs no `SCOPE_CARD_` or `SCOPE_ACCOUNT_` entry in `USER_SCOPES`. Every other route in this platform is ownership-scoped; this one is not, and the difference is deliberate. An acquirer presents a card it did not issue, and the card is named in the request body rather than in a path the caller could be said to own. The control that applies instead is the decision chain itself: a card the cross-reference does not carry is refused with reason 0100 from `app/cbl/CBTRN02C.cbl:L385-L387`. Nothing else on this service is reachable, because `anyRequest().denyAll()` closes the chain.

A request may name the card number, the account identifier, or both. An account-only request resolves its card through the cross-reference, and a request naming neither is refused with the source's own message.

The route's role rule cannot decide the Access column on its own, because the subject arrives in the request body and an account-only request has no card until the cross-reference has been read. The decision therefore compares the caller against the resolved account and card itself, immediately after the cross-check and before a transaction identifier is allocated. `ADMIN` reaches every subject, which is the fork at `app/cbl/COSGN00C.cbl:L232-L236`. A `USER` reaches the accounts and cards its configured scopes name, and reaches nothing else.

`ACQUIRER` is a machine identity a point-of-sale network presents. It owns no account, no customer and no card, and `ACQUIRER_USERNAME` in [`.env.example`](../../.env.example) configures it separately from every cardholder identity. The reason is the shape of the route: the card travels in the request body, so no identifier in the path can be compared against an ownership scope, and the role is therefore the whole authorization decision. A cardholder identity carrying `USER` is refused with 403 — it holds one account and this route reaches every card the platform holds. `ADMIN` keeps the route because `app/cbl/COSGN00C.cbl:L232-L236` forks an administrator onto every function the region offers.

The status codes are the source's own outcome model, restated over HTTP. `app/cbl/CBTRN02C.cbl:L229-L230` counts rejects and ends the batch job with return code 4, so a rejection is expected traffic and not a failure.

| Outcome | Status | Body |
| :--- | ---: | :--- |
| Approved | 200 | `approved` true, naming the account the card resolved to |
| Declined by one of the four rules | 422 | `approved` false, carrying the reject code and its verbatim source text |
| Request field failed validation | 422 | The verbatim text `app/cbl/COTRN02C.cbl` moves into `WS-MESSAGE` for that field |
| Caller does not reach the resolved subject | 403 | One fixed phrase, naming no account, card, or token |
| Body could not be read | 400 | A fixed phrase naming the class of failure |
| No usable credential | 401 | A problem document, and `WWW-Authenticate: Basic realm="carddemo"` |
| Credential holds no role this route admits | 403 | A problem document naming neither the operation nor the card |
| Body larger than the service reads | 413 | Refused before the parser, by the declared ceiling |
| Fault inside the service | 500 | No value read from the request |
| Replica older than the staleness bound | 503 | The one status worth retrying, and deliberately not a decline |

A decline answers 422. No decline answers 500 and none answers 503.

A 403 is not a decline. It carries the phrase the filter chain already writes for a route refusal, so the two read alike, and it names nothing about the subject. Nothing is decided: no transaction identifier is drawn, no decision row is written, no unresolved-attempt row is written, and no event is produced. A card that resolved no account is refused the same way, which keeps reject code `0100` from answering whether a card exists.
> **Synthetic data only.** `DEMO_SYNTHETIC_CARD_NUMBER` below is row one of the repository fixture `app/data/ASCII/carddata.txt`. It is a made-up number in a public sample dataset and it identifies no real card. Never substitute real cardholder data into this command, or into any other command in this repository: the Compose ports are bound to loopback for a demonstration and this platform carries none of the controls real card data requires.

Read the password from a prompt rather than putting it in a shell variable, so it reaches neither the environment nor the shell history. `.env` holds only the bcrypt hash of each password, so supply the plaintext you chose when you generated that hash.

```bash
# The card number is read from the fixture the seed migration loads rather than
# written here. No guide in this repository carries a card number a reader could
# replay: the value below exists for the length of the shell that reads it.
CARD_NUMBER="$(cut -c1-16 app/data/ASCII/carddata.txt | sed -n '1p')"
CAPTURED_AT="$(date -u +'%Y-%m-%d %H:%M:%S').000000"
PROCESSED_AT="$(date -u +'%Y-%m-%d-%H.%M.%S').000000"

curl -sS -X POST http://localhost:8081/authorizations \
  -u "admin001:${ADMIN_PASSWORD}" \
  -H 'X-CardDemo-Request: acquirer-cli' \
  -H 'Content-Type: application/json' \
  -d "{\"cardNumber\":\"${CARD_NUMBER}\",\"transactionTypeCode\":\"01\",
       \"transactionCategoryCode\":\"0001\",\"source\":\"POS TERM\",
       \"description\":\"Service guide purchase\",\"amount\":\"+00000504.77\",
       \"merchantId\":\"800000000\",\"merchantName\":\"Abshire-Lowe\",
       \"merchantCity\":\"North Enoshaven\",\"merchantZip\":\"72112\",
       \"originTimestamp\":\"${CAPTURED_AT}\",
       \"processingTimestamp\":\"${PROCESSED_AT}\"}"
```

`CARD_NUMBER` is read from `app/data/ASCII/carddata.txt`, the fixture `src/main/resources/db/migration/V2__seed.sql` loads, so the command runs from the repository root. Both timestamps accept the ten-character date `YYYY-MM-DD` the source screen field carries, and the twenty-six character record form as well. `app/cbl/COTRN02C.cbl:L389-L423` validates each as a ten-character date under the tolerance flagged in [business-rule-flags.md](../../docs/business-rule-flags.md), and a ten-character value is widened to the record width before it reaches the event, exactly as `app/cbl/COTRN02C.cbl:L469` widens it. No bound relates either value to the clock of this service: reject reason `0103` at `app/cbl/CBTRN02C.cbl:L414-L420` compares the first ten characters of the capture moment against the account expiry date, and that comparison is the whole of the test.

The full contract is the hand-written [OpenAPI description](src/main/resources/openapi.yaml). No documentation generator produces it.

Management traffic answers on a separate port and never on the business port. The exposed endpoints are `health`, `metrics`, and `prometheus`. Only `health` answers without a credential, because a container health check carries none.

| Metric | Meaning |
| :--- | :--- |
| `carddemo.authorization.decisions` | Decisions taken, tagged by outcome |
| `carddemo.authorization.decision.duration` | Time one decision took |
| `carddemo.authorization.events.written` | Events written to the outbox, one per resolved call |
| `carddemo.authorization.events.published` | Outbox rows the broker acknowledged, counted after the sweep commits |
| `carddemo.authorization.outbox.abandoned` | Outbox rows given up on after their attempts were spent |
| `carddemo.authorization.dead.letters` | Terminal diagnostics naming an abandoned row, tagged `outcome=published` or `outcome=failed` |
| `carddemo.authorization.events.consumed` | Replica deliveries read, tagged `eventType`: `AccountStateChanged`, `CardUpdated` |
| `carddemo.authorization.duplicates.skipped` | Replica deliveries a marker made a repeat of, tagged `eventType` |
| `carddemo.authorization.processing.latency` | Time one replica delivery took, tagged `eventType` |
| `carddemo.authorization.failures` | Failures, tagged by stage: `publish`, `replica`, `entitlement`, `decision` |

The three replica series report the two streams that keep `card_xref` and `account_credit_snapshot` current. Each counts a delivery as it arrives rather than once it is applied, so a stream that has stopped arriving reads as a count that stopped rising, and a delivery refused for its payload or given up on is counted here as well as on the failure series.

### Two controls in front of every route

`config/CrossSiteRequestFilter` guards state change. `POST /authorizations` must carry `X-CardDemo-Request` with any non-blank value, must not declare a `Sec-Fetch-Site` other than `same-origin` or `same-site`, and must not carry an `Origin` naming anything but this service. HTTP Basic is a credential a browser attaches by itself, so without this check a page on any other site could submit a form against a route above and the browser would authenticate it. An HTML form cannot set a request header at all, which is what makes one header the control. A refusal answers 403 with the same problem document every other refusal of this service answers and counts `carddemo.authorization.requests.cross.site.refused`. `GET`, `HEAD`, `OPTIONS` and `TRACE` pass untouched, so the container health check and every read need nothing.

`config/RequestRateCeilingFilter` bounds volume. It runs one place ahead of the security chain, because a refusal has to cost less than the attempt it refuses and an attempt that reached the chain would already have paid for a bcrypt verification.

| Ceiling | Default | Counted by |
| :--- | ---: | :--- |
| Failed authentications | 20 per 60s | Source address, and only when the request carried a credential and was answered 401 |
| Requests | 600 per 60s | Source address |
| Requests | 600 per 60s | The username the credential names, read as a counter key and never verified or logged |
| State-changing requests | 120 per 60s | Source address |
| Requests in flight | 64 | The whole instance |

A refusal answers 429 with `Retry-After` and counts `carddemo.authorization.requests.throttled`, tagged with the stage that refused: `authentication`, `source`, `identity`, `write` or `concurrency`. The five ceilings read `API_RATE_WINDOW_SECONDS`, `API_RATE_REQUESTS_PER_WINDOW`, `API_RATE_WRITE_REQUESTS_PER_WINDOW`, `API_RATE_AUTHENTICATION_FAILURES_PER_WINDOW` and `API_RATE_CONCURRENT_REQUESTS` from [`.env.example`](../../.env.example). The management base path is exempt, because a throttled probe reads as a failed container.

Both filters count in this process, so several replicas bound each replica rather than the service as a whole, and the source address is the one the container resolves rather than a forwarding header a caller could write. A deployment behind a proxy sets `SERVER_FORWARD_HEADERS_STRATEGY=framework` so the container resolves the client address and the client-facing host. Both residual limits are recorded in [suggested next tasks](../../docs/suggested-next-tasks.md), and the reasoning behind the two controls is in the [Decision Log](../../docs/decision-log.md).

<br/>

## Decision chain

The driver paragraph at `app/cbl/CBTRN02C.cbl:L370-L378` sets the order and the gate:

```cobol
1500-VALIDATE-TRAN.
    PERFORM 1500-A-LOOKUP-XREF.
    IF WS-VALIDATION-FAIL-REASON = 0
       PERFORM 1500-B-LOOKUP-ACCT
    ELSE
       CONTINUE
    END-IF
* ADD MORE VALIDATIONS HERE
    EXIT.
```

Line 377 is exactly `* ADD MORE VALIDATIONS HERE`. The original author marked the extension point there, which is why `DeclineRule` is an interface: a new rule arrives as a new class and the chain is not edited.

| Rule class | Reason | Wire code | Verbatim source description | Locator |
| :--- | ---: | :--- | :--- | :--- |
| `CardCrossReferenceRule` | 100 | `0100` | `INVALID CARD NUMBER FOUND` | `app/cbl/CBTRN02C.cbl:L385-L387` |
| `AccountExistsRule` | 101 | `0101` | `ACCOUNT RECORD NOT FOUND` | `app/cbl/CBTRN02C.cbl:L397-L399` |
| `CreditLimitRule` | 102 | `0102` | `OVERLIMIT TRANSACTION` | `app/cbl/CBTRN02C.cbl:L403-L413` |
| `AccountExpirationRule` | 103 | `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `app/cbl/CBTRN02C.cbl:L414-L420` |

The wire code carries four digits because `WS-VALIDATION-FAIL-REASON` is `PIC 9(04)` at `app/cbl/CBTRN02C.cbl:L181`. Its description is capped at seventy-six characters by `PIC X(76)` at line 182.

**Two rules short-circuit and two do not, and the difference decides the answer.**

- Reason 100 short-circuits. The gate at line 372 performs the account lookup only while the reason is still zero, so a card that misses reports 100 and never reaches 101, 102, or 103.
- Reason 101 short-circuits as well. Reasons 102 and 103 sit in the `NOT INVALID KEY` limb of the account read, so a missing account skips both.
- **Reasons 102 and 103 do not short-circuit against each other.** Lines 407 to 413 and lines 414 to 420 are two sequential `IF` statements in that same limb, and neither returns. A transaction that is both overlimit and past expiry ends with **103 overwriting 102**. A test covers this, because returning on the first failing rule gives the wrong code.
- Equality approves at both comparisons.

`app/cbl/CBTRN02C.cbl:L403-L405` computes the tested balance, and line 407 compares it:

```cobol
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                    - ACCT-CURR-CYC-DEBIT
                    + DALYTRAN-AMT

IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
```

Three findings live in those five lines, and this service reproduces all three:

1. The formula ignores the current balance and reads only the two cycle accumulators.
2. `WS-TEMP-BAL` is `PIC S9(09)V99` at line 187, one integer digit narrower than the two `PIC S9(10)V99` operands at `app/cpy/CVACT01Y.cpy:L13` and `:L14`, and narrower than the `PIC S9(10)V99` credit limit at `:L8`. A cycle balance at or above one billion loses its high-order digit, which turns a decline into an approval.
3. Two commented-out diagnostic statements sit immediately above the comparison, at lines 401 and 402.

Each finding is entered in [business rule flags](../../docs/business-rule-flags.md).

**The two accumulators the rule reads carry this service's own approvals as well.** `app/cbl/CBTRN02C.cbl` posts each record before it validates the next, because `2000-POST-TRANSACTION` at `:L424-L444` runs inside the read loop and `2700-UPDATE-ACCOUNT` at `:L545-L560` has already rewritten the account by the time line 403 reads it again. Two transactions of 60.00 against a limit of 100.00 therefore approve once and decline once. Here the account service owns those accumulators and reports them back four asynchronous hops later, so an approval reserves its own amount on the snapshot row in the same transaction as the decision, under a row lock bounded by `carddemo.decision.lock-wait-ms`. The rule adds the reserved figures to the authoritative ones before it computes, and `carddemo.decision.reservation-ttl` releases a reservation whose posting never arrives. The sign convention is the source's: `:L549` and `:L551` route a negative amount to the debit accumulator, so a refund still tightens the next authorization.

Reason 103 compares raw text. `app/cbl/CBTRN02C.cbl:L414` reads `IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`, so the column stays `VARCHAR(10)` and the comparison stays a string comparison over ten characters.

<br/>

## Events

| Direction | Event | Topic | Consumer group |
| :--- | :--- | :--- | :--- |
| Produces | `TransactionAuthorized` | `transaction.authorized` | — |
| Produces | `TransactionDeclined`, versions 2 and 3 | `transaction.declined` | `ledger-reject` |
| Consumes | `AccountStateChanged` | `account.state-changed` | `authorization-account-state` |
| Consumes | `CardUpdated` | `card.updated` | `authorization-card-updated` |
| Routes on a spent record | `DeadLetterEnvelope` | `carddemo.dead-letter` | — |

One authorization call produces exactly one outcome event. **This service consumes none of the three fan-out events**, and the two streams it does read only maintain its replica tables. `AccountStateChanged` replaces every value column of `account_credit_snapshot`. `CardUpdated` refreshes the observation columns of `card_xref` and no mapping value, because it carries a masked card number while that table is keyed on the full sixteen characters. Neither listener sits on the response path.

Ledger posting, fraud detection, and notification read `transaction.authorized` under the groups `ledger-posting`, `fraud-detection`, and `notification-authorized`. Ledger posting also reads `transaction.declined` under `ledger-reject`, where it writes the 430-byte reject row of `app/cbl/CBTRN02C.cbl:L446`–`L465`. This service knows none of those consumers by name or address.

A decline that resolved an account travels at contract version 3, which carries version 1's properties plus the nine descriptive values of the transaction the caller sent. It carries them because the consumer that owns the reject row has to reproduce `REJECT-TRAN-DATA PIC X(350)`, the whole daily record, and a refused transaction exists in no table it could read those values from. Every one of the nine comes off the request this call refused, never from a lookup. Version 1 remains governed so an event published under it stays readable, and version 2 is still what a decline resolving no account travels under.

Every governed event carries the shared envelope from `com.carddemo.events.EventEnvelope`.

| Envelope field | Meaning |
| :--- | :--- |
| `eventId` | The idempotency value every consumer records before applying effects, beside the topic the delivery arrived on |
| `eventType` | The routing discriminator |
| `schemaVersion` | The document version, so a new consumer joins without breaking an old one |
| `occurredAt` | The moment the producer built the event |
| `aggregateId` | The account identifier, and the Kafka message key |

Two conventions carry correctness, and changing either breaks it:

- **The account identifier is the message key**, so every event for one account lands on one partition and stays ordered. The one decline that resolves no account has no account to name, so version 2 of `TransactionDeclined` keys on the sixteen-character transaction identifier instead.
- **Money travels as a decimal string, never as a JavaScript Object Notation (JSON) number.** `TRAN-AMT` is `PIC S9(09)V99` at `app/cpy/CVTRA05Y.cpy:L10`, giving eleven digits at scale two and the column type `NUMERIC(11,2)`. `ACCT-CURR-BAL` is `PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy:L7`, giving `NUMERIC(12,2)`.

The decision row and the outbox row commit in one local transaction. The relay publishes afterwards, and one relay pass is three kinds of transaction rather than one: a claim transaction that commits before any send, no transaction at all while the sends are outstanding, and one short transaction per result. That division is why a claim survives the process death it was written for, and why a hundred rows waiting on a broker no longer hold a connection and a hundred row locks. Every send is awaited against one monotonic deadline shared by the pass, from `carddemo.outbox.relay.max-duration-ms`, and the producer window resolves one send well inside it.

The claim query returns the due head row of each account, never two rows of one account. Recording each result separately would otherwise let an older row fail while a newer row of the same account succeeded, and the account identifier is the message key, so the retry would reach the topic out of order. The same restriction stops one unpublishable row blocking the table: a refusal pauses that account for the pass and leaves every other account eligible.

A record this service can never apply reaches the dead-letter topic after bounded retries, and its offset is committed once the diagnostic is published, so the record is named once rather than on every later assignment. A row this service can never publish is abandoned and records a durable obligation to name itself, in the same transaction that abandons it; the obligation clears only against a broker acknowledgement, because an abandoned row is terminal and no later claim would ever return to it. Either diagnostic carries the four fields of `01 ABEND-DATA` at `app/cpy/CSMSG02Y.cpy:L21-L29`, and nothing read from the record itself. A diagnostic whose row is keyed by a transaction identifier travels under the aggregate identifier `00000000000`, since `schemas/dead-letter-v1.json` accepts eleven digits, and `failedEventId` still names the row exactly.

Full publish-and-consume paths for every topic are in [event flow](../../docs/event-flow.md).

<br/>

## Domain and data ownership

The private database is `carddemo_authorization` and the schema is `authorization_service`. No other service reads it. Flyway creates every object and Jakarta Persistence validates the model against it.

| Table | Derivation |
| :--- | :--- |
| `card_xref` | `app/cpy/CVACT03Y.cpy`: `XREF-CARD-NUM PIC X(16)` L5, `XREF-CUST-ID PIC 9(09)` L6, `XREF-ACCT-ID PIC 9(11)` L7. Primary key from `app/jcl/XREFFILE.jcl:L43` `KEYS(16 0)`, record size from `:L44` `RECORDSIZE(50 50)`, and the non-unique secondary index on the account identifier from the alternate index at `:L74` `KEYS(11,25)` with `NONUNIQUEKEY` at `:L75` and `UPGRADE` at `:L76` |
| `account_credit_snapshot` | Projection of `app/cpy/CVACT01Y.cpy`: credit limit L8, expiry L11, cycle credit L13, cycle debit L14, kept current by `AccountStateChanged`. Three further columns are this service's own: `pending_cycle_credit`, `pending_cycle_debit`, and `pending_expires_at` hold the exposure of approvals whose posting has not been reported back yet |
| `unresolved_card_attempt` | Reason 100 attempts, which resolve no account to attribute |
| `authorization_decision` | The attribution record of each decision and the identity that asked for it |
| `outbox_event` | Event identifier, type, payload, aggregate identifier, relay state, and the dead-letter obligation an abandoned row carries |
| `processed_event` | Event identifier primary key and processed timestamp, the duplicate guard for both listeners |

The sequence `transaction_id_seq` issues transaction identifiers, starting at 1000000000.

Two omissions are deliberate, and both are listed in the [traceability matrix](../../docs/traceability-matrix.md). The cross-reference `FILLER PIC X(14)` at `app/cpy/CVACT03Y.cpy:L8` is dropped. The Communication Area navigation fields are dropped with the screens they sequenced.

`V2__seed.sql` loads the fifty cross-reference rows from `app/data/ASCII/cardxref.txt`. **That fixture measures 36 characters per record, not the 50 the copybook declares**, because the trailing filler is physically absent. All fifty records measure 36: sixteen characters of card number, then nine of customer identifier, then eleven of account identifier. Read record one with `sed -n '1p' app/data/ASCII/cardxref.txt` rather than from this guide, which carries no card number. The loader tolerates the narrower width.

Column-by-column mapping for every table is in [data model](../../docs/data-model.md).

<br/>

## Domain context

Four terms explain most of the code in this module. A developer who reads a class called `CreditLimitRule` needs them first.

**A card cross-reference** maps one card number to one customer and one account. The original ran on a Virtual Storage Access Method (VSAM) dataset keyed by the sixteen-character card number, with a second index on the account identifier. The card record does carry an account identifier of its own, `CARD-ACCT-ID PIC 9(11)` at `app/cpy/CVACT02Y.cpy:L6`, and the posting program still does not read it: `app/cbl/CBTRN02C.cbl:L29-L64` opens six files and the card file is not among them, and `app/jcl/POSTTRAN.jcl` allocates the same six datasets. With the card file unopened, the cross-reference is the only account the program can reach, so every authorization here starts by resolving the card through this table. That is why reason 100 exists: a card with no cross-reference row cannot name an account to authorize against.

**A decline reason code** is the source's four-digit answer to "why not". The original wrote it into a reject record and counted it, and this service returns it and publishes it. There are exactly four codes and there is no fifth.

**The two cycle accumulators** are running totals of credits and debits inside the current billing cycle, held on the account row. The credit-limit rule reads both and ignores the current balance. Something must zero them at the end of a cycle, and the only source code that does is `app/cbl/CBACT04C.cbl:L353-L354`, which sits in a program this migration does not carry. The account service exposes a cycle-close operation for that reason.

**The outbox** is a table in this service's own schema. The decision row, the event row, and the reserved exposure of an approval are written in one local transaction, and a relay publishes the event afterwards. The decision and the intent to announce it therefore commit together or not at all. The original had no equivalent: `app/cbl/CBTRN02C.cbl:L440-L442` performs three writes unconditionally with no rollback, and all eight file definitions in `app/csd/CARDDEMO.CSD` carry `RECOVERY(NONE)` and `JOURNAL(NO)`.

The platform-wide briefing, including the Job Control Language (JCL) batch model this replaces, is in [onboarding](../../docs/onboarding.md).

<br/>

## Declared deviations

Every departure from a literal reading of the source is named here, so that no deviation is silent. The reasoning for each one lives in the [decision log](../../docs/decision-log.md), which is the single source of truth for why.

| Deviation | What the source does |
| :--- | :--- |
| The published card number is masked | No masking exists anywhere in the source. The decision runs on the full Primary Account Number (PAN), exactly as the source does, and only the payload is masked |
| Currency is emitted as a constant | No source record carries a currency field |
| Transaction identifiers come from a database sequence | `app/cbl/COTRN02C.cbl:L444-L451` moves `HIGH-VALUES` into the key, reads backwards, and adds one, which is a read-modify-write race |
| Atomicity, idempotency, and observability are additions | The source has none of the three |
| Reason 109 becomes an observable failure | `app/cbl/CBTRN02C.cbl:L556` assigns it inside a failed rewrite and nothing ever inspects it |
| `ACCT-EXPIRAION-DATE` is spelled correctly in target names | The source misspells it at `app/cpy/CVACT01Y.cpy:L11`. No behaviour depends on the identifier text |
| Passwords are stored as encoded hashes | `app/cbl/COSGN00C.cbl:L223` compares a stored and supplied password as plaintext. That comparison is not on the authorization path |
| Account and card state arrives by event | The source read shared datasets directly. This service holds replicas and makes no synchronous call to another service |
| An approval reserves its exposure, under a lock and an expiry | `app/cbl/CBTRN02C.cbl` rewrote the account in-line, so the next record read accumulators that already carried the approval. Reserving restores that timing across an asynchronous boundary; the row lock and the expiry are additions |
| The caller is compared against the resolved subject | No source program compares a caller against a subject. `app/cbl/COSGN00C.cbl:L232-L236` forks on the role byte only, because a signed-on terminal reached only its own screens |
| The recorded actor is the whole authenticated principal | `app/cpy/CSUSR01Y.cpy:L18` declares `SEC-USR-ID PIC X(08)` for a signon identity. This column records an HTTP principal, which no source field bounds, so it is sixty-four characters wide and is never shortened |

Five checks a reader may expect are **deliberately absent**, because adding any one of them would change outcomes and break equivalence:

- **No card-number checksum.** `app/cbl/COCRDUPC.cbl:L194` validates a card number only as sixteen digits.
- **No card-status check.** `app/jcl/POSTTRAN.jcl` STEP15 allocates TRANFILE, DALYTRAN, XREFFILE, DALYREJS, ACCTFILE, and TCATBALF, and no card file at all, so the source posting path never opens one.
- **No account-status check.** The field exists at `app/cpy/CVACT01Y.cpy:L6` and no program tests it before posting, so a closed account still posts.
- **No correction of the precision narrowing.** Reproduced as measured and flagged.
- **No correction of the refund sign convention.** A negative amount is added to the cycle-debit accumulator at `app/cbl/CBTRN02C.cbl:L551`, and line 404 subtracts that accumulator, so a refund raises the tested balance.

Each of the five is entered in [business rule flags](../../docs/business-rule-flags.md) for human review.

<br/>

## Pitfalls

Each of these was measured while building this module, and each bites here.

1. **The language level fails silently.** This module descriptor declares `<java.version>25</java.version>`. The Spring Boot parent defaults the language level and compiler release to 17, and a module that omits the override compiles cleanly with no warning. Class files must carry major version **69**, not 61.
2. **Rounding breaks equivalence silently.** All monetary arithmetic truncates toward zero. `grep -ri "ROUNDED" app/cbl/` returns zero matches across all 28 programs, so every computation goes through `com.carddemo.cobol.CobolDecimal`, which pins `RoundingMode.DOWN`. `HALF_UP` is the reflexive Java choice and it is wrong here.
3. **The card-token key is required and this repository ships none.** `CARD_TOKEN_SECRET` is a `REPLACE` placeholder in `card-platform/.env.example` and in `deploy/k8s/31-secret.example.yaml`, and this service refuses to start while it is absent, blank, still a placeholder, shorter than 32 characters, or equal to either card-token key this repository has published. Generate one with `openssl rand -base64 48 | tr -d '/+='`. This service and the card service are the only two that derive a token, so the Kubernetes Secret carrying the key is pulled by those two Deployments alone.
4. **Reasons 102 and 103 do not short-circuit.** Returning on the first failing rule produces 102 where the source produces 103.
5. **Amounts are not parsed with `new BigDecimal(String)`.** `app/cbl/COTRN02C.cbl:L383` and `:L456` use `FUNCTION NUMVAL-C`, which accepts currency symbols and thousands separators. Route every amount through `com.carddemo.cobol.NumvalParser`.
6. **The date validator accepts one undocumented case.** `app/cbl/COTRN02C.cbl:L397` accepts severity `'0000'`, and line 400 also accepts message number `2513` with no comment explaining it. `com.carddemo.cobol.CobolDateValidator` keeps that tolerance. The condition name at `app/cbl/CSUTLDTC.cbl:L62` is inverted: `88 FC-INVALID-DATE VALUE X'0000000000000000'` is the all-zero token that means success.
7. **`mvn package` runs before `docker compose up`.** The image copies a finished archive with `COPY target/*.jar` and builds nothing inside itself, because the aggregator and both library modules sit outside the build context.
8. **A live demo needs the demo migration, or reason 103 declines everything.** Every one of the fifty seeded accounts expires in 2025, and a caller sending today's date as the origin timestamp sends a later value. `docker-compose.yml` names both `classpath:db/migration` and `classpath:db/demo`, and the second extends every seeded expiry to 2099-12-31. An equivalence run must name `db/migration` alone, because `V2__seed.sql` is the oracle the suite measures against.
9. **Without a cycle close, available credit shrinks until everything declines.** Nothing else zeroes the two accumulators the credit-limit rule reads. Call the cycle-close operation on the account service, described in [onboarding](../../docs/onboarding.md).
10. **A stale replica answers 503, not a decline.** An account update or a cycle close must publish `AccountStateChanged`, or the projection keeps answering with what it last observed. Past `carddemo.replica.max-staleness` this service refuses the call and counts a failure at the replica stage.
11. **One account is decided one decision at a time.** Each decision reads its account row under a write lock so that the exposure it reserves cannot be lost, and `carddemo.decision.lock-wait-ms` bounds the wait. A second concurrent call against the same account answers 503 rather than waiting, which is the same refusal a contended row already answers elsewhere on the platform.
12. **A reservation whose posting never lands releases itself.** `carddemo.decision.reservation-ttl` defaults to fifteen minutes. Longer than that, an approval sitting in the dead-letter topic stops holding credit, so a demo does not silently drift toward declining everything. Shorter than the time posting really takes, two calls inside one window could together exceed the limit.
13. **A state-changing call needs one extra header.** A `curl` that worked before this control answers 403 until it adds `-H 'X-CardDemo-Request: 1'`. `config/CrossSiteRequestFilter` requires the header on every `POST`, `PUT`, `PATCH` and `DELETE`, because HTTP Basic is a credential a browser attaches without being asked and an HTML form cannot set a header. Reads need nothing. The name is configurable through `API_CROSS_SITE_HEADER` and it is not a secret: the value is never checked, only its presence.
14. **A burst answers 429 rather than being served.** A load generator, a retry loop, or a test that hammers one address reaches `config/RequestRateCeilingFilter` and answers 429 with `Retry-After`. The blanket ceiling is 600 requests a minute per address and per identity, writes are 120, and 20 failed authentications from one address close the rest of that minute. Raise `API_RATE_*` for a load run rather than removing the filter, and read the `stage` tag on `carddemo.authorization.requests.throttled` to see which ceiling refused.

<br/>

## Architecture

Figure 1 traces one call from the client to the three consumers. Read it for two things: where the response returns, and where publication happens. The response returns as soon as the local transaction commits, and the relay publishes after that commit and never inside request handling.

**Figure 1 — One Authorization Call, Four Decline Rules, One Event: the Request-to-Publish Path**

```mermaid
graph TB
    CLIENT["REST client with HTTP Basic identity"]
    API["POST /authorizations on host port 8081"]
    R100{"Card cross reference. Reason 0100"}
    R101{"Account exists. Reason 0101"}
    R102{"Credit limit. Reason 0102"}
    R103{"Account expiry. Reason 0103"}
    TX[("decision row plus one outbox row. ONE local transaction")]
    RELAY["Outbox relay. Separate transaction"]
    TA{{"transaction.authorized"}}
    TD{{"transaction.declined"}}
    LEDGER["ledger posting. Group ledger-posting"]
    FRAUD["fraud detection. Group fraud-detection"]
    NOTIFY["notification. Group notification-authorized"]
    SPENT["Row abandoned. Unpublished, and named on no topic"]

    CLIENT -->|"synchronous HTTP"| API
    API --> R100
    R100 -->|"resolved"| R101
    R100 -.->|"miss. Short-circuits"| TX
    R101 -->|"found"| R102
    R101 -.->|"miss. Short-circuits"| TX
    R102 -->|"both evaluate. 103 overwrites 102"| R103
    R103 --> TX
    TX -->|"200 approved or 422 declined"| CLIENT
    TX --> RELAY
    RELAY ==> TA
    RELAY ==> TD
    TA ==> LEDGER
    TA ==> FRAUD
    TA ==> NOTIFY
    RELAY -.->|"no bound topic, or attempts spent"| SPENT
```

Legend for Figure 1:

- **Plain arrow** — a synchronous step, either the client's HTTP call or in-process control flow inside one request.
- **Thick arrow** — an asynchronous Kafka publish or consume, which happens after the response has returned.
- **Dotted arrow** — a short-circuit that skips the remaining rules, or the terminal state of an outbox row this relay gave up on.
- **Diamond** — one decline rule, labelled with the wire code it assigns.
- **Cylinder** — a database write. The decision row and the outbox row commit together, and that single transaction is the whole guarantee.
- **Hexagon** — a Kafka topic.
- **The absence of any arrow between the three consumers is the point of the diagram.** None of them calls another, and none calls this service.
- **`carddemo.dead-letter` is absent from this figure on purpose.** This relay publishes no diagnostic for a row it gave up on, so no edge reaches that topic from the request-to-publish path drawn here.

One gap belongs beside the figure rather than inside it. Two conditions end an outbox row here without a publication: an event type with no bound topic, and ten spent delivery attempts, which moves the row to `ABANDONED`. Each records an attempt and writes a warning, and neither names the row on any topic. The relays of the ledger, fraud, account and card services do publish a governed `DeadLetterEnvelope` for an abandoned row, and this one does not.

The listener side of this service is a separate path and does reach the shared topic. A replica record it cannot apply routes to `carddemo.dead-letter` as a governed envelope, which [event flow](../../docs/event-flow.md) lists beside the routes of the other four listening services. [Suggested next tasks](../../docs/suggested-next-tasks.md) carries the decision about closing the relay gap.

Figure 2 places the source path beside the delivered one. The platform-wide pair, at full size, is in [architecture before and after](../../docs/architecture-before-after.md).

**Figure 2 — Before and After for the Authorization Path: a Nightly Batch Validation Becomes a Synchronous Call plus One Event**

```mermaid
graph TB
    subgraph BEFORE["Before. Nightly batch validation"]
        FEED[("DALYTRAN daily feed. 300 records at 350 bytes")]
        JOB["POSTTRAN STEP15 runs CBTRN02C"]
        SHARED[("Shared VSAM datasets. RECOVERY NONE and JOURNAL NO")]
        REJ[("DALYREJS generation data group. LRECL 430")]
        FEED -->|"read sequentially"| JOB
        JOB -.->|"three writes. No rollback"| SHARED
        JOB -->|"reject records"| REJ
    end

    subgraph AFTER["After. One synchronous decision"]
        REQ["POST /authorizations"]
        RULES["Four decline rules in source order"]
        PRIV[("Private schema authorization_service. Outbox included")]
        EVENT{{"One outcome event per call"}}
        CONS["Three independent consumers"]
        REQ -->|"decides and returns"| RULES
        RULES -->|"one local transaction"| PRIV
        PRIV ==>|"relay publishes after commit"| EVENT
        EVENT ==> CONS
    end
```

Legend for Figure 2:

- **Left subgraph** — the source path. A clock starts the work, a sequential file feeds it, and every dataset is shared with other programs.
- **Right subgraph** — the delivered path. A client request starts the work and the schema is private to this service.
- **Plain arrow** — in-process or synchronous flow. **Thick arrow** — an asynchronous publish or consume, which exists only on the right.
- **Dotted arrow** — direct access to a dataset that other programs also write.
- **Cylinder** — stored data. **Hexagon** — a Kafka topic.
- The three writes on the left run unconditionally with no rollback, and every shared dataset disables recovery and journalling. Both properties are why the right side commits its decision and its event together.

<br/>

## How to extend

- **Add a decline rule.** The seam is not invented: `app/cbl/CBTRN02C.cbl:L377` marks it. It is also not a one-file change, because the reason code is a governed part of the wire contract. Six edits are required, all but the first outside this module:
  1. Add a class implementing `DeclineRule` under `domain/rules/`, annotated `@Order` with a value that keeps the source sequence. The delivered four are `@Order(10)`, `(20)`, `(30)` and `(40)`, and `domain/AuthorizationChainCompositionTest` asserts both the order and the segment each rule belongs to.
  2. Add the code and its verbatim source text to `libs/event-contracts/src/main/java/com/carddemo/events/DeclineReason.java`. That enum, not a local resource, is the registry every service reads.
  3. Extend the `declineReasonCode` and `declineReasonDescription` enumerations in `libs/event-contracts/src/main/resources/schemas/transaction-declined-v1.json`. Leave `transaction-declined-v2.json` alone unless the new rule can fire before an account is resolved: that document fixes both fields to the single `const` value `0100`, because a reason-0100 decline names no account and is keyed by transaction instead.
  4. Add the value to `libs/event-contracts/src/test/java/com/carddemo/events/SchemaBackwardCompatibilityTest.java`, which fails the build on a change an existing consumer could not read. Widening an enumeration is compatible; narrowing one is not.
  5. Add the code to the `declineReasonCode` enumeration and the 422 example of `src/main/resources/openapi.yaml`, which `api/OpenApiContractTest` compares against the delivered controller.
  6. Add a row to `equivalence-tests/src/test/resources/expected/dailytran-authorization-decisions-model-a.csv` if the new rule can fire against the shipped fixture, and a constructed case to `AuthorizationDecisionEquivalenceTest` if it cannot.
- **Add a consumer of this service's events.** Subscribe a new service to `transaction.authorized` under its own consumer group. **No change to this service is required**, which is the extensibility claim made concrete.
- **Swap the event bus.** Provide another implementation of `messaging/EventPublisherPort`, the platform's single event-bus seam.

Work found while building this module, and left out of scope, is listed in [suggested next tasks](../../docs/suggested-next-tasks.md).

<br/>

## Local run and tests

Every version below is exact. No range, and nothing to choose.

| Component | Value |
| :--- | :--- |
| Build runtime | Eclipse Temurin OpenJDK 25.0.4+7 |
| Build tool | Apache Maven 3.9.16 |
| Container runtime | Docker Engine 29.7.0 with Docker Compose 5.3.1 |
| Broker image | `apache/kafka:4.2.1` |
| Database image | `postgres:18.4` |
| Service runtime image | `bellsoft/liberica-openjre-debian:25.0.4-9` |
| Business port | 8081 on the host, 8080 in the container |
| Management port | 9081 on the host, 9080 in the container |
| Database | `carddemo_authorization` on host `postgres` port 5432 |
| Schema | `authorization_service` |
| Database login | `carddemo_authorization_svc` |
| Kafka bootstrap | `kafka:29092` inside Compose, `localhost:9092` from the host |
| Health check | `http://localhost:9081/actuator/health` |
| Readiness probe | `/actuator/health/readiness` on the management port |
| Outbox relay | Every 500 ms, batch size 100, one pass bounded at 5000 ms |

From `card-platform/`. Step 1 generates every credential the stack refuses to start without: fourteen passwords, one card-token key and four encoded identity hashes, nineteen `REPLACE` markers in all. Nothing here prompts.

```bash
cp .env.example .env

for variable in POSTGRES_PASSWORD \
  AUTHORIZATION_DB_PASSWORD LEDGER_DB_PASSWORD FRAUD_DB_PASSWORD \
  NOTIFICATION_DB_PASSWORD ACCOUNT_DB_PASSWORD CARD_DB_PASSWORD \
  KAFKA_ADMIN_PASSWORD \
  AUTHORIZATION_KAFKA_PASSWORD LEDGER_KAFKA_PASSWORD FRAUD_KAFKA_PASSWORD \
  NOTIFICATION_KAFKA_PASSWORD ACCOUNT_KAFKA_PASSWORD CARD_KAFKA_PASSWORD; do
  sed -i "s|^${variable}=.*|${variable}=$(openssl rand -base64 24 | tr -d '/+=')|" .env
done

export ADMIN_PASSWORD="$(openssl rand -base64 18 | tr -d '/+=')"
export USER_PASSWORD="$(openssl rand -base64 18 | tr -d '/+=')"
export MONITORING_PASSWORD="$(openssl rand -base64 18 | tr -d '/+=')"

mvn -B -ntp -DskipTests package

CRYPTO_CP="$(find ~/.m2/repository/org/springframework/security/spring-security-crypto \
  -name 'spring-security-crypto-*.jar' | sort | tail -1):\
$(find ~/.m2/repository/commons-logging/commons-logging \
  -name 'commons-logging-*.jar' | sort | tail -1):\
$(find ~/.m2/repository/org/springframework/spring-core \
  -name 'spring-core-*.jar' | sort | tail -1)"

hash_password() {
  DEMO_PASSWORD="$1" jshell --class-path "$CRYPTO_CP" -s - <<'JSHELL' 2>/dev/null | grep -m1 '^{bcrypt}'
System.out.println(org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(System.getenv("DEMO_PASSWORD")));
/exit
JSHELL
}

sed -i "s|^ADMIN_PASSWORD_HASH=.*|ADMIN_PASSWORD_HASH='$(hash_password "$ADMIN_PASSWORD")'|" .env
sed -i "s|^USER_PASSWORD_HASH=.*|USER_PASSWORD_HASH='$(hash_password "$USER_PASSWORD")'|" .env
sed -i "s|^MONITORING_PASSWORD_HASH=.*|MONITORING_PASSWORD_HASH='$(hash_password "$MONITORING_PASSWORD")'|" .env

grep -n '^[A-Z_]*=.*REPLACE' .env || echo "all 17 values are set"
```

Keep the single quotes on the three hashes. A bcrypt value is full of `$`, and Compose expands `$` in an unquoted dotenv value.

`mvn package` above builds every module, so every image has an archive to copy. Start the three containers this service needs, and nothing else:

```bash
docker compose up -d --build --wait postgres kafka authorization-service
curl -fsS http://localhost:9081/actuator/health
```

The copy contains 19 `REPLACE` markers: fourteen passwords, one card-token key and four `{bcrypt}` identity hashes. Compose reads them with `${VAR:?}` and refuses to start while any is unset, so fill in all nineteen before the first `docker compose up`, not only the three this service uses. [Onboarding](../../docs/onboarding.md) generates the bcrypt hashes and holds the full platform walkthrough. Read it once before the first run.

Flyway owns this schema. Eleven migrations run on every start, in this order:

| Migration | What it creates |
| :--- | :--- |
| `V1__schema.sql` | `card_xref`, `account_credit_snapshot`, `outbox_event`, `processed_event` |
| `V2__seed.sql` | The 50 cross-reference rows and 50 credit snapshots from `app/data/ASCII/cardxref.txt` and `app/data/ASCII/acctdata.txt` |
| `V3__unresolved_card_attempt.sql` | `unresolved_card_attempt`, the record of an attempt whose card resolved no account |
| `V4__outbox_transaction_key.sql` | The transaction key an unresolved-card decline is published under |
| `V5__authorization_decision.sql` | `authorization_decision`, one row per decision, with its actor and outcome constraints |
| `V6__processed_event_topic_key.sql` | The topic component of the duplicate-delivery marker key |
| `V7__cycle_exposure_reservation.sql` | The two reserved-exposure columns of `account_credit_snapshot` and its expiry, so an approval is visible to the next decision, plus the widening of `authorization_decision.actor` to `VARCHAR(64)` |
| `V8__outbox_dead_letter_state.sql` | The dead-letter obligation of an abandoned outbox row, its published stamp, three checks and the partial index the relay reads its owed diagnostics from |
| `V9__declared_processing_timestamp.sql` | `authorization_decision.declared_processing_timestamp`, the processing moment a caller declares, which `app/cbl/COTRN02C.cbl:L470` moves into `TRAN-PROC-TS` |
| `V10__declared_retention_matches_the_sweep.sql` | The catalogue comments of `authorization_decision` and `unresolved_card_attempt`, restated to name the window `domain/RetentionSweep` actually applies |
| `V11__subject_request_posture.sql` | The `card_xref` comment, corrected to drop a claim that an erasure request propagates into this replica |

A twelfth file, `src/main/resources/db/demo/V900__demo_expiry_extension.sql`, is **not** applied by default. It lifts every seeded account expiry to `2099-12-31` so a live call carrying today's date is not declined with reason 0103 by a fixture whose latest expiry is in 2025. It runs only when `spring.flyway.locations` names `classpath:db/demo` alongside `classpath:db/migration`, which `card-platform/docker-compose.yml` does through `AUTHORIZATION_FLYWAY_LOCATIONS` and nothing else does. `mvn verify` therefore measures the untouched fixture, and the demonstration gets the extension.

Run this module's tests alone, which builds both libraries first:

```bash
mvn -B -pl services/authorization-service -am test
```

Authorize one transaction. Both timestamps are generated so that the call reads as one made now, and nothing bounds the capture moment against the clock of this service — reject code 0103 at `app/cbl/CBTRN02C.cbl:L414-L420` is the whole of the test applied to it:

```bash
CARD_NUMBER=$(sed -n '1s/^\(.\{16\}\).*/\1/p' ../../app/data/ASCII/cardxref.txt)
CAPTURED_AT="$(date -u +'%Y-%m-%d %H:%M:%S').000000"
PROCESSED_AT="$(date -u +'%Y-%m-%d-%H.%M.%S').000000"

curl -sS -u "admin001:$ADMIN_PASSWORD" -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"cardNumber\":\"${CARD_NUMBER}\",
       \"transactionTypeCode\":\"01\",
       \"transactionCategoryCode\":\"0001\",
       \"source\":\"POS TERM\",
       \"description\":\"Service guide purchase\",
       \"amount\":\"+00000504.77\",
       \"merchantId\":\"800000000\",
       \"merchantName\":\"Abshire-Lowe\",
       \"merchantCity\":\"North Enoshaven\",
       \"merchantZip\":\"72112\",
       \"originTimestamp\":\"${CAPTURED_AT}\",
       \"processingTimestamp\":\"${PROCESSED_AT}\"}" \
  http://localhost:8081/authorizations
```

That answers HTTP 200 with `"approved":true` on the shipped Compose settings, which add `classpath:db/demo` to this service's Flyway locations. `V900__demo_expiry_extension.sql` extends the fixture expiries, which all fall in 2023 to 2025, to 2099-12-31; without it reason 0103 declines a request carrying today's capture date.

Read the one event that call published, bounded so the command returns:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 \
  --command-config /tmp/kafka-admin.properties \
  --topic transaction.authorized --from-beginning --max-messages 1
```

Start `ledger-posting-service`, `fraud-detection-service` and `notification-service` as well to watch all three consume it. [Platform README](../../README.md) has that walkthrough, and [Onboarding](../../docs/onboarding.md) covers machine setup, ports, pitfalls and extension paths.

<br/>

## Related documentation

- [Platform README](../../README.md)
- [Onboarding](../../docs/onboarding.md)
- [Decision Log](../../docs/decision-log.md)
- [Traceability Matrix](../../docs/traceability-matrix.md)
- [Business Rule Flags](../../docs/business-rule-flags.md)
- [Architecture Before and After](../../docs/architecture-before-after.md)
- [Event Flow](../../docs/event-flow.md)
- [Data Model](../../docs/data-model.md)
- [Equivalence Results](../../docs/equivalence-results.md)
- [Suggested Next Tasks](../../docs/suggested-next-tasks.md)
