# Event Flow

This document follows every published event from commit through consumption. One authorization call writes exactly one authorization outcome event. Ledger, fraud, and notification react asynchronously, and no downstream service calls another downstream service. Paired legacy and target views live in [Architecture, Before and After](architecture-before-after.md), while rationale lives in the [decision log](decision-log.md).

## The envelope

Every event carries one flat envelope beside its payload. A consumer can route, validate, order, and deduplicate before interpreting business fields.

| Field | Purpose |
| --- | --- |
| `eventId` | Durable idempotency key recorded by each consumer |
| `eventType` | Routing discriminator and schema selector |
| `schemaVersion` | Contract version used to select the exact JSON Schema document |
| `occurredAt` | Producer timestamp |
| `aggregateId` | Kafka message key, normally the 11-digit account identifier |

A reason-0100 decline has no resolved account. Its envelope uses the 16-character transaction identifier as the sanctioned aggregate key.

## Payload conventions

Money travels as a two-place decimal string, never as a JSON number. A decimal string prevents a client from silently changing fixed-point money into binary floating point.

The account identifier is the Kafka key whenever an account is known. A Kafka partition is an ordered shard of a topic, so one account’s ledger updates stay ordered.

Card-number masking and tokenization occur after the authorization decision. The full 16-character card number performs the cross-reference lookup. Events, responses, logs, and notification keys receive only the masked display form or the irreversible 64-character card token.

The card verification value at `app/cpy/CVACT02Y.cpy:L7` is stored only by the card service. No event, log entry, or API response contains it.

## Topics and consumer groups

The delivered runtime creates seven business topics, five source-specific dead-letter topics, and one shared fallback. A dead-letter topic holds records that could not complete processing after validation and retry handling.

| Topic | Events carried | Producer | Consumer groups |
| --- | --- | --- | --- |
| `transaction.authorized` | `TransactionAuthorized` | authorization-service | `ledger-posting`, `fraud-detection`, `notification-authorized` |
| `transaction.declined` | `TransactionDeclined` versions 1 and 2 | authorization-service and the ledger feed-validation reject path | None in the demo |
| `transaction.posted` | `TransactionPosted` versions 1 and 2 | ledger-posting-service | `account-posted`, `notification-posted` |
| `fraud.assessed` | `FraudFlagged`, `FraudCleared` | fraud-detection-service | `notification-fraud` |
| `account.state-changed` | `AccountStateChanged` | account-service | `authorization-account-state`, `ledger-account-state` |
| `customer.context-changed` | `CustomerContextChanged` | account-service | `notification-customer` |
| `card.updated` | `CardUpdated` versions 1 and 2 | card-service | `authorization-card-updated` |
| `<source>.DLT` | 134-character fixed-width abend diagnostic | Ledger, fraud, and notification listener error handlers | Human inspection and replay tooling |
| `carddemo.dead-letter` | `DeadLetterEnvelope` | Authorization and account listener error handlers, the four outbox relays on abandonment, and any handler whose source topic cannot be resolved | Human inspection and replay tooling |

The five source-specific dead-letter topics are `transaction.authorized.DLT`, `account.state-changed.DLT`, `transaction.posted.DLT`, `fraud.assessed.DLT`, and `customer.context-changed.DLT`. `card.updated` needs none, because its one consumer routes a spent record to the shared fallback as a governed envelope.

A consumer group is the named set of listener instances that share one subscription. Nine groups serve four listening services: authorization consumes two replica streams, ledger consumes the authorization stream and one replica stream, fraud consumes the authorization stream, and notification consumes four streams.

Three of those nine groups read `transaction.authorized`. That is the fan-out the requirements set a floor on, and it is visible in the group names alone: `ledger-posting`, `fraud-detection`, and `notification-authorized` each hold their own offsets, so none of the three can slow, block, or starve another.

`FraudFlagged` and `FraudCleared` share `fraud.assessed`. The envelope’s `eventType` distinguishes them before notification applies verdict-specific behavior.

## Business events

### `TransactionAuthorized`

The authorization service publishes `TransactionAuthorized` after an approval commits with its outbox row.

| Field | Source or status |
| --- | --- |
| `transactionId` | `TRAN-ID`, `app/cpy/CVTRA05Y.cpy:L5` |
| `accountId` | `XREF-ACCT-ID`, `app/cpy/CVACT03Y.cpy:L7` |
| `transactionTypeCode` | `TRAN-TYPE-CD`, `app/cpy/CVTRA05Y.cpy:L6` |
| `merchantCategoryCode` | `TRAN-CAT-CD`, `app/cpy/CVTRA05Y.cpy:L7` |
| `source` | `TRAN-SOURCE`, `app/cpy/CVTRA05Y.cpy:L8` |
| `description` | `TRAN-DESC`, `app/cpy/CVTRA05Y.cpy:L9` |
| `amount` | `TRAN-AMT`, `app/cpy/CVTRA05Y.cpy:L10` |
| Merchant fields | `app/cpy/CVTRA05Y.cpy:L11-L14` |
| `maskedCardNumber` | Masked form of `TRAN-CARD-NUM`; additive security boundary |
| `authorizedAt` | `TRAN-ORIG-TS`, `app/cpy/CVTRA05Y.cpy:L16` |
| `currency` | Additive constant `USD`; no source transaction field carries currency |

`ledger-posting` applies source posting arithmetic. `fraud-detection` calculates a new risk verdict. `notification-authorized` records the authorization against the card-keyed read model so a cardholder alert exists before posting completes. None of the three blocks the HTTP response, and none of them reads another's output.

### `TransactionDeclined`

A declined request is expected traffic, not an infrastructure error. `app/cbl/CBTRN02C.cbl:L230` sets return code 4 when any batch input is rejected.

| Wire code | Source description | Assignment |
| --- | --- | --- |
| `0100` | `INVALID CARD NUMBER FOUND` | `app/cbl/CBTRN02C.cbl:L385-L387` |
| `0101` | `ACCOUNT RECORD NOT FOUND` | `app/cbl/CBTRN02C.cbl:L397-L399` |
| `0102` | `OVERLIMIT TRANSACTION` | `app/cbl/CBTRN02C.cbl:L410-L412` |
| `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `app/cbl/CBTRN02C.cbl:L417-L419` |

Version 1 carries the resolved account identifier. Version 2 represents reason 0100 and omits an account that the platform never resolved.

The topic has no demo consumer. It exists so another consumer can subscribe without changing the authorization producer.

Reason 109 at `app/cbl/CBTRN02C.cbl:L556` is not a decline event. The source never checks it, while the target treats the condition as a processing failure; [business-rule flag 9](business-rule-flags.md) records the change.

### `TransactionPosted`

The ledger publishes `TransactionPosted` from its outbox after transaction, category-balance, and account-balance writes commit.

Version 1 contains transaction identifier, account identifier, amount, masked card, new balance, and posting time. Version 2 adds nine descriptive fields required by the notification read model.

| Version 2 detail | Provenance |
| --- | --- |
| `transactionTypeCode` | Authorized event value from `TRAN-TYPE-CD` |
| `merchantCategoryCode` | Authorized event value from `TRAN-CAT-CD` |
| `source` and `description` | Authorized event transaction text |
| Merchant identifier, name, city, and ZIP | Authorized event merchant fields |
| `originTimestamp` | Authorized event origin timestamp |

The new balance is the value after `app/cbl/CBTRN02C.cbl:L547`. Notification refuses version 1 for statement insertion rather than fabricating the missing columns.

The processing timestamp carries two significant fractional digits and four zeros. [Business-rule flag 8](business-rule-flags.md) and the [equivalence results](equivalence-results.md) define the comparison tolerance.

Two groups read this topic, for unrelated reasons. `notification-posted` inserts a statement row. `account-posted` adds the amount to the account record, reproducing `app/cbl/CBTRN02C.cbl:L545-L560`: the balance moves, and the amount reaches `current_cycle_credit` when it is not negative and `current_cycle_debit` when it is.

That second group is what makes the two accumulators move at all. `ACCTDAT` was one dataset in the source, so the posting program at `:L545-L560` and the credit-limit test at `:L403-L413` read and wrote the same record. Here the record is split across three services that may not call one another, and this event is the only path from the writer to the copy the account service owns. Without it the accumulators stayed at their seeded values, the credit-limit rule tested one amount against the limit rather than cumulative cycle exposure, and `GET /accounts/{id}` reported the balance as it stood at deployment while `GET /balances/{id}` reported the posted one. Neither raised anything.

The account listener applies the amount and never the event's `newBalance`. A balance copied across a service boundary is a value with two owners; an amount applied to a locally held balance keeps one owner and stays correct under redelivery, because the marker suppresses the repeat rather than the arithmetic having to be idempotent.

### `FraudFlagged`

`FraudFlagged` is additive in full because no COBOL fraud module exists. It carries transaction and account identifiers, a score, triggered rule identifiers, and assessment time.

The notification fraud listener renders an alert with its private cardholder projection. It also writes one metadata-only `notification_log` row with masked card, transaction, channel, and attempt time.

### `FraudCleared`

`FraudCleared` is also additive in full. It carries transaction and account identifiers plus assessment time.

The notification listener acknowledges a valid cleared event without rendering an alert. The listener accepts `Object` first because the cleared payload is structurally narrower than the flagged payload.

### `AccountStateChanged`

The account service publishes `AccountStateChanged` after any committed change to the account record: an update, a cycle close, or a posted amount applied by the `account-posted` group. An update publishes it only when the account record itself changed — one that raises a credit limit publishes it, one that changes only an address publishes `CustomerContextChanged` instead. The event carries the complete authorization credit snapshot.

A posting is a state change to `ACCTDAT` in the source too: `app/cbl/CBTRN02C.cbl:L560` rewrites the record. So the publication rule this service follows, that it publishes only on a state change, holds unchanged.

| Field | Authorization target in `account_credit_snapshot` | Ledger target in `account_balance_projection` |
| --- | --- | --- |
| `accountId` | `account_id` | `account_id` |
| `creditLimit` | `credit_limit` | Not held; the ledger evaluates no limit |
| `currentCycleCredit` | `current_cycle_credit` | `cycle_credit` |
| `currentCycleDebit` | `current_cycle_debit` | `cycle_debit` |
| `expirationDate` | `account_expiration_date` | Not held; the ledger tests no expiry |
| `changeKind` | Explains whether an update or cycle close produced the event | Same |
| Envelope `eventId` and `occurredAt` | `source_event_id`, `source_occurred_at` | `source_event_id`, `source_occurred_at` |

Both groups apply the snapshot monotonically: a change whose `occurredAt` is not after the stored value discards itself rather than moving a cycle balance backwards. Each stores its event marker in the same transaction as the projection write.

Authorization then reads its local projection and calls no account service during a decision. The ledger consumes the same event for a different reason: `V2__seed.sql` loaded a projection row per fixture account and nothing else told the table when the account service moved the original, so without this consumer an account opened after deployment had no row at all, and a billing cycle closed at `app/cbl/CBACT04C.cbl:L353-L354` never reached the ledger's copy of the two accumulators. A posting is a delta the ledger owns; it advances neither provenance column, because clearing them would let an already-applied change apply twice.

The event a posting produces closes the loop the credit-limit rule depends on. `transaction.posted` carries the amount to the account service, which applies it and publishes the moved accumulators here, and the `authorization-account-state` group writes them into the snapshot reason code 102 reads. The ledger ignores the value columns of that particular event by design, and `ledger-posting-service` documents why: it applied the same amount itself a moment earlier, so taking the account service's copy would overwrite a correct value with one that is behind by every posting the account service has yet to hear about.

The chain therefore has one writer per copy. The ledger writes its own projection from the authorization event, the account service writes its record from the posted event, and the authorization replica is written only from what the account service publishes.

### `CustomerContextChanged`

The account service publishes `CustomerContextChanged` when the customer record changes beside an account update, and not when the update left it as it stood. It carries the ten name, address, country, postal-code, and credit-score fields the source statement renderer reads.

The comparison that decides both publications is the one `app/cbl/COACTUPC.cbl:L1684-L1768` draws, applied to each record on its own rather than to the pair. The two rewrites at `:L4066` and `:L4086` run unconditionally, each writing back the values the caller submitted, so a record whose submitted values equal its fetched values is rewritten with what it already held and has no change to announce. Reaching those rewrites requires the submitted pair to differ from the fetched pair, so every committed write publishes at least one of the two events.

The `notification-customer` group applies the event to `cardholder_context` and stores the duplicate marker in the same local transaction. A producer timestamp prevents an older or replayed event from moving the projection backwards.

### `CardUpdated`

The card service publishes `CardUpdated` after a successful card update. Version 2 carries account identifier, masked card number, expiry, and active status; it omits the embossed name that version 1 carried.

The `authorization-card-updated` group uses the account identifier and visible card suffix to refresh the matching local cross-reference observation. It changes no card-to-account field, because the event carries no full card number or customer identifier.

## Authorization transaction flow

**Figure 1 — One authorization call and the asynchronous reactions it starts**

Figure 1 separates the synchronous response from every later consumer action. Ledger, fraud, and notification each read the one authorization event directly and independently. Notification additionally reacts to posted and fraud events rather than calling either producer.

```mermaid
sequenceDiagram
    participant Client
    participant Authorization
    participant AuthDB as Authorization DB
    participant AuthRelay as Authorization relay
    participant Kafka
    participant Ledger
    participant LedgerDB as Ledger DB
    participant LedgerRelay as Ledger relay
    participant Fraud
    participant FraudDB as Fraud DB
    participant FraudRelay as Fraud relay
    participant Notification
    participant NotifyDB as Notification DB
    participant Account
    participant AccountDB as Account DB
    participant AccountRelay as Account relay

    Client->>Authorization: POST /authorizations
    Authorization->>AuthDB: Write decision and one outbox row
    AuthDB-->>Authorization: Commit
    Authorization-->>Client: Approved or declined response

    AuthRelay->>AuthDB: Claim unpublished row
    AuthRelay->>Kafka: Publish authorized or declined event

    Kafka-->>Ledger: TransactionAuthorized
    Ledger->>LedgerDB: Claim event and apply posting
    LedgerDB-->>Ledger: Commit posting and outbox row
    LedgerRelay->>Kafka: Publish TransactionPosted

    Kafka-->>Fraud: TransactionAuthorized
    Fraud->>FraudDB: Claim event and calculate verdict
    FraudDB-->>Fraud: Commit verdict and outbox row
    FraudRelay->>Kafka: Publish FraudFlagged or FraudCleared

    Kafka-->>Notification: TransactionAuthorized
    Notification->>NotifyDB: Claim event and record the authorization alert
    NotifyDB-->>Notification: Commit before acknowledge

    Kafka-->>Notification: TransactionPosted
    Notification->>NotifyDB: Upsert statement row and marker
    NotifyDB-->>Notification: Commit before acknowledge

    Kafka-->>Notification: Fraud assessment
    Notification->>NotifyDB: Render flagged alert and write attempt
    NotifyDB-->>Notification: Commit before acknowledge

    Kafka-->>Account: TransactionPosted
    Account->>AccountDB: Claim event, add the amount, queue the state change
    AccountDB-->>Account: Commit balance, accumulator and outbox row
    AccountRelay->>Kafka: Publish AccountStateChanged
    Kafka-->>Authorization: AccountStateChanged
    Authorization->>AuthDB: Refresh the credit snapshot reason 102 reads
```

**Legend**

- Solid request arrows before the client response are synchronous.
- Arrows through Kafka are asynchronous and occur after the authorization response.
- Each database response marked `Commit` closes one local transaction.
- A declined authorization publishes only `TransactionDeclined`; no consumer receives an authorized event.
- Ledger, fraud, notification, and account share no direct call edge.
- Three arrows carry `TransactionAuthorized` out of Kafka, one per independent consumer group.
- Two arrows carry `TransactionPosted` out of Kafka, to notification and to account.
- The last four arrows are the cycle-accumulator chain. They start at a posted amount and end at the snapshot the credit-limit rule reads, so cumulative cycle exposure reaches the next authorization without any consumer calling another service.

## State-change projection flow

**Figure 2 — State changes refreshing authorization and notification projections**

Figure 2 shows the state paths that replace synchronous owner-service lookups. One `account.state-changed` event feeds two independent replicas: authorization's credit snapshot and the ledger's balance projection. Card updates refresh only authorization's observation metadata, and customer-context changes refresh notification's renderer context.

Three things produce `account.state-changed`, not two. An update and a cycle close both arrive through the web surface. A posted amount arrives on `transaction.posted` instead, and it is the one path that runs with no request behind it. Figure 2 draws it because the credit-limit rule reads what it writes: without that path the two accumulators in the snapshot never move, and reason code 102 tests one amount against the limit rather than cumulative cycle exposure.

```mermaid
graph LR
    AAPI["Account update or cycle close"]
    PTOPIC{{"transaction.posted"}}
    APOST["Account posted-transaction consumer"]
    ADB[("account row and outbox row")]
    ARELAY["Account outbox relay"]
    ATOPIC{{"account.state-changed"}}
    ACON["Authorization consumer"]
    PROJ[("account_credit_snapshot and processed_event")]
    LCON["Ledger consumer"]
    LPROJ[("account_balance_projection and processed_event")]
    CTXTOPIC{{"customer.context-changed"}}
    NCON["Notification customer-context consumer"]
    NCTX[("cardholder_context and processed_event")]

    CAPI["Card update"]
    CDB[("card row and outbox row")]
    CRELAY["Card outbox relay"]
    CTOPIC{{"card.updated"}}
    CCON["Authorization card-update consumer"]
    XREF[("card_xref and processed_event")]

    AAPI -->|"one local transaction"| ADB
    PTOPIC ==> APOST
    APOST -->|"one local transaction"| ADB
    ADB --> ARELAY
    ARELAY ==> ATOPIC
    ARELAY ==> CTXTOPIC
    ATOPIC ==> ACON
    ACON -->|"one local transaction"| PROJ
    ATOPIC ==> LCON
    LCON -->|"one local transaction"| LPROJ
    CTXTOPIC ==> NCON
    NCON -->|"one local transaction"| NCTX

    CAPI -->|"one local transaction"| CDB
    CDB --> CRELAY
    CRELAY ==> CTOPIC
    CTOPIC ==> CCON
    CCON -->|"one local transaction"| XREF
```

**Legend**

- A cylinder naming two tables means both rows commit in one local transaction.
- Thick arrows cross Kafka.
- `account.state-changed` has two consumer groups, `authorization-account-state` and `ledger-account-state`, so each replica advances on its own offsets.
- `transaction.posted` reaches the account service under `account-posted`, its own group, so the notification service reading the same topic still receives every record.
- The account row has two writers and they write different things: a request writes the submitted values, and the posted-transaction consumer adds an amount to the balance and to one of the two accumulators. Both queue one outbox row, so both reach the authorization snapshot by the same path.
- `card.updated` has the `authorization-card-updated` consumer group.
- `customer.context-changed` has the `notification-customer` consumer group.
- Every replica applies a snapshot monotonically against the producer timestamp, so an older or replayed event cannot move a projection backwards.

## Delivery mechanics

An outbox is a database table written with the business change. A relay publishes those committed rows later, avoiding a split database-and-broker write inside request handling.

The demo relay checks every 500 milliseconds and claims at most 100 rows. Producers enable idempotence and require acknowledgements from all broker replicas.

Kafka delivery is at least once. Rebalances, restarts, or a crash after side effects but before offset commit can deliver the same event again.

**Publication is also at least once, and the producer window is what bounds how often a duplicate occurs.** A relay row is published exactly once in the database — `published` and `published_at` move only after the broker acknowledges the send — but a topic can carry a second copy of one `eventId`. A relay tick bounds itself so a broker that accepts a connection and never answers cannot hold the scheduled thread. A tick that gave up on a send the producer still held left that record to arrive up to two minutes later, while the tick's retry published another copy. The fraud relay closes that window: `max.block.ms` plus `delivery.timeout.ms` stays below `carddemo.outbox.relay.max-duration-ms`, and a send the relay has issued is waited out rather than abandoned.

One duplicate path is not a platform choice. A broker that has already appended a record can lose the acknowledgement, so the producer reports a failure the log does not share and the next attempt appends a second copy. No producer setting removes that, and `enable.idempotence` does not either, because the two copies come from two `send` calls carrying their own sequence numbers. Both copies carry the same `eventId`, so every consumer's `processed_event` marker suppresses the second one. A test asserts the timing relationship, so moving one value without the other fails the build; the [decision log](decision-log.md) records the choice with its measurements, and [suggested next tasks](suggested-next-tasks.md) carries the same relationship for the account service.

Idempotency means a repeated event has no repeated business effect. Each consumer checks or claims `processed_event` by `eventId`, performs its work, and commits the marker with the effect.

Auto-commit is disabled. Manual acknowledgement occurs only after the database transaction commits.

The demo permits three processing attempts with a one-second backoff. Two terminal routes exist, and which one a failure takes depends on what failed.

A spent consumer record takes the listener route. Ledger, fraud, and notification send a 134-character fixed-width abend diagnostic to the source topic plus `.DLT`, and fall back to `carddemo.dead-letter` when the source topic cannot be resolved. Authorization sends a governed `DeadLetterEnvelope` to `carddemo.dead-letter` directly. Neither form republishes the failed payload, the failed key, or any inbound header outside a fixed allowlist, so a poison record cannot carry a card number or a card verification value onto a dead-letter topic. Malformed schema input reaches the same sanitized route without unsafe business processing.

An abandoned outbox row takes the relay route. The four relays that publish business events — authorization, ledger, account, and card — send a governed `DeadLetterEnvelope` to `carddemo.dead-letter` once a row is spent, so a change that can never be published still leaves a durable diagnostic naming the row, the event type, the attempt count, and the reason. Each terminal outcome increments a counter distinct from the per-attempt failure counter, so retries and permanently spent work are never summed together.

Kafka transactions do not make a database write atomic with a broker write. The platform still needs an outbox on producers and a marker transaction on consumers.

**Figure 3 — Transactional outbox and idempotent consumer mechanics**

Figure 3 identifies the two local transaction boundaries and the duplicate guard.

```mermaid
graph LR
    HANDLER["Request or domain handler"]
    PDB[("domain row and outbox row")]
    RELAY["Outbox relay"]
    TOPIC{{"Kafka topic"}}
    POLL["Consumer poll"]
    CHECK{"eventId already processed"}
    WORK["Apply business effect"]
    CDB[("business effect and processed_event")]
    ACK["Acknowledge offset"]
    DLQ{{"source-specific .DLT<br/>or shared fallback"}}

    HANDLER -->|"one local transaction"| PDB
    PDB --> RELAY
    RELAY ==>|"publish then mark sent"| TOPIC
    TOPIC ==> POLL
    POLL --> CHECK
    CHECK -->|"yes"| ACK
    CHECK -->|"no"| WORK
    WORK -->|"one local transaction"| CDB
    CDB --> ACK
    POLL -.->|"terminal validation or processing failure"| DLQ
    RELAY -.->|"row abandoned after its attempts are spent"| DLQ
```

**Legend**

- Each cylinder naming two tables represents one local database transaction.
- The diamond is the durable duplicate check that makes redelivery harmless.
- Acknowledgement follows the consumer transaction and never precedes it.
- The dotted arrows are the two terminal dead-letter routes: a spent consumer record, and an abandoned outbox row.

## Failure counters and their discriminators

Every service separates a per-attempt failure count from a per-record or per-row terminal count, so a retry and a permanently spent unit of work are never summed together. The series names and the tag that discriminates each one differ by service, because each service counts what its own pipeline distinguishes. The names are listed here so a query can be written without reading the code.

| Service | Failure series, one increment per failed attempt | Terminal series, one increment per record or row given up on |
|---|---|---|
| notification | `carddemo.notification.failures`, tagged `failure.kind`: `schema_validation`, `deserialization`, `persistence`, `rendering`, `unknown` | `carddemo.notification.records.dead.lettered`, tagged `failure.kind` with the same values |
| ledger | `carddemo.ledger.failures`, tagged `stage`: `deserialize`, `process`, `publish`, `abandon` | `carddemo.ledger.dead.letters`, tagged `outcome`: `published`, `failed` |
| fraud | `carddemo.fraud.failures`, tagged `stage`: `deserialize`, `process`, `publish` | `carddemo.fraud.dead.letters`, tagged `outcome`: `published`, `failed` |
| authorization | `carddemo.authorization.failures`, tagged `stage`: `persist`, `publish`, `replica` | The `replica` stage carries both readings, because a refused projection record is the only record this service dead-letters |
| account | `carddemo.account.transaction.failures`, tagged `operation`: `update`, `cycle-close`, and `carddemo.account.publish.failed` | `carddemo.account.outbox.abandoned`, with `carddemo.account.dead.letters.published` and `carddemo.account.dead.letters.failed` naming what became of the diagnostic |
| card | `carddemo.card.failures` | `carddemo.card.outbox.abandoned`, with `carddemo.card.dead.letters.failed` counting a diagnostic the broker refused |

Notification tags by what failed, because it validates, persists, and renders. The ledger and fraud tag by the stage that failed, because an operator reasons about their stages separately. Card and account discriminate by series name rather than by tag, because each terminal outcome has one meaning. Every series is registered at start-up and carries a bounded tag set, so a value outside the set falls back rather than creating a series. The [decision log](decision-log.md) records why the three schemes were left as they are, and [suggested next tasks](suggested-next-tasks.md) carries the unification.

## Schema validation

Publishing validates the exact pair of `eventType` and `schemaVersion`, so malformed output never reaches a business topic. Consumption validates before domain code changes a table.

The contract library governs eight business event types and the dead-letter envelope across thirteen schema documents. `TransactionAuthorized`, `TransactionDeclined`, `TransactionPosted`, and `CardUpdated` each retain versions 1 and 2.

Compatibility tests enforce additive evolution. An older payload remains valid under the document that originally governed it.

## The source ancestor

The source’s only asynchronous handoff is `EXEC CICS WRITEQ TD` at `app/cbl/CORPT00C.cbl:L517`, followed by `QUEUE ('JOBS')` and `FROM (JCL-RECORD)` at lines 518-519. The target generalizes that “write now, process later” shape to every cross-service event.