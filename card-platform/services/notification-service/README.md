## Notification Service

- [Purpose](#purpose)
- [Source provenance](#source-provenance)
- [Domain context](#domain-context)
- [Events consumed](#events-consumed)
- [Architecture](#architecture)
- [Endpoints](#endpoints)
- [Domain and data ownership](#domain-and-data-ownership)
- [Renderers](#renderers)
- [Patterns](#patterns)
- [Pitfalls](#pitfalls)
- [Local run and tests](#local-run-and-tests)
- [How to extend](#how-to-extend)
- [Deliberate non-additions](#deliberate-non-additions)
- [Related documentation](#related-documentation)

<br/>

## Purpose

`notification-service` turns platform events into cardholder alerts. Four listeners feed its private tables, among them a transaction read model keyed by card. Two renderers build each alert, one in plain text and one in HyperText Markup Language (HTML), and one read-only endpoint returns the history held for a card. The module publishes no business event and calls no other service.

The module replaces only the customer-facing tail of statement generation. Full statement generation stays a scheduled batch process, which the requirements ask for directly: *"retain as scheduled/batch processes for now; do not force these into the event model."*

Java package root `com.carddemo.notification`. Maven artifact `notification-service`.

<br/>

## Source provenance

Every locator below was read in the source before it was written here.

| Contribution | Source | Verified locator |
| :--- | :--- | :--- |
| Text and markup rendering, plus per-card totalling | `app/cbl/CBSTM03A.CBL` | `01 STATEMENT-LINES.` at line 85; the text layout `ST-LINE0` through `ST-LINE15` spans lines 86–146; `01 HTML-LINES.` at line 148, with the markup carried as condition names on `HTML-FIXED-LN PIC X(100)` at line 149 |
| Running total, reset per card | `app/cbl/CBSTM03A.CBL` | `WS-TOTAL-AMT PIC S9(9)V99 VALUE 0` at line 65, declared under `01 COMP3-VARIABLES COMP-3.` at line 64; `MOVE ZERO TO WS-TOTAL-AMT` at line 325; `ADD TRNX-AMT TO WS-TOTAL-AMT` at line 429; moved through an edited field at lines 433 and 434 |
| The card-keyed read model layout | `app/cpy/COSTM01.CPY` | `01 TRNX-RECORD.` at line 20; `05 TRNX-KEY.` at line 21 holding `TRNX-CARD-NUM PIC X(16)` at line 22 and `TRNX-ID PIC X(16)` at line 23; `05 TRNX-REST.` at line 24 covering lines 25 through 35; a 20-byte `FILLER` at line 36 |
| The composite key, derived from the sort that builds the read model | `app/jcl/CREASTMT.JCL` | `KEYS(32 0)` at line 30; `RECORDSIZE(350 350)` at line 32; `EXEC PGM=SORT` at line 44; `SORT FIELDS=(263,16,CH,A,1,16,CH,A)` at line 53; `OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)` at line 54; the copy into the keyed dataset at lines 56–61; the renderer step at line 79 |
| The repository interface shape | `app/cbl/CBSTM03B.CBL` | `01 LK-M03B-AREA.` at line 100 with the operation codes at lines 103–108; the four-dataset dispatch at lines 118–127 |
| The dead-letter metadata shape | `app/cpy/CSMSG02Y.cpy` | `01 ABEND-DATA.` at line 21: `ABEND-CODE PIC X(4)` line 22, `ABEND-CULPRIT PIC X(8)` line 24, `ABEND-REASON PIC X(50)` line 26, `ABEND-MSG PIC X(72)` line 28 |

The source names the re-keying itself. Line 42 of `app/jcl/CREASTMT.JCL` reads `CREATE COPY OF TRANSACT FILE WITH CARD NUMBER AND TRAN ID AS KEY`.

One provenance note matters to anyone tracing customer fields. `app/cbl/CBSTM03A.CBL` carries `COPY CUSTREC.` at line 55, so the statement program binds the `CUSTREC` fork of the customer layout, and it is the only program in `app/cbl/` that does. Six programs bind `CVCUS01Y` instead — `CBCUS01C`, `CBTRN01C`, `COACTUPC`, `COACTVWC`, `COCRDSLC` and `COCRDUPC` — which is why the account service adopts `CVCUS01Y` as canonical. This module therefore takes customer data only from events, and assumes nothing about the account service's field naming. The fork itself is recorded in [business rule flags](../../docs/business-rule-flags.md).

<br/>

## Domain context

A cardholder statement lists the transactions posted against one card over a period. Alongside the list it carries the account balance, the cardholder's name and address, a credit score, and a total of the listed amounts.

The shape changed. The original job runs as batch work under Job Control Language (JCL) over Virtual Storage Access Method (VSAM) datasets. It renders one statement per card, walking the card cross-reference file. This module renders one alert per event, and keeps a per-card read model so a history query stays a single indexed read. Nothing here waits for a nightly window.

<br/>

## Events consumed

| Consumer | Topic | Consumer group | What it does |
| :--- | :--- | :--- | :--- |
| `TransactionAuthorizedConsumer` | `transaction.authorized` | `notification-authorized` | Renders an alert over the one transaction the event carries |
| `TransactionPostedConsumer` | `transaction.posted` | `notification-posted` | Upserts the statement row, then renders an alert over the card's rows |
| `FraudFlaggedConsumer` | `fraud.assessed` | `notification-fraud` | Renders a flagged alert, and acknowledges a cleared assessment |
| `CustomerContextChangedConsumer` | `customer.context-changed` | `notification-customer` | Refreshes the account-keyed name, address and credit-score context |

Four listeners hold four groups. Reading `transaction.authorized` under a group of its own is what makes this module an independent consumer of the authorization event, beside the ledger and the fraud detector.

`FraudFlagged` and `FraudCleared` share the `fraud.assessed` topic. The deserializer selects the record type from the envelope `eventType` field, so `FraudFlaggedConsumer` receives a typed object and routes on it. A listener written as though every message were a `FraudFlagged` mishandles half its traffic.

Two keys are in play, and they differ deliberately. The Kafka message key is the account identifier, which keeps one account's events in one partition and in order. The read model key is the card token plus the transaction identifier, and it comes from the sort at `app/jcl/CREASTMT.JCL:L53`.

**This module publishes no event. Events produced: none.** It writes only sanitized dead-letter records. The dead-letter base name is `carddemo.dead-letter` and the suffix is `.DLT`, so each source topic routes to its own dead-letter topic and the four wire shapes stay separate. A listener makes three delivery attempts, 1000 ms apart, before a record routes there.

<br/>

## Architecture

Figure 1 shows the four topics reaching four consumer groups, the duplicate claim they all pass through, the private tables behind them, and which of the four ends in a rendered alert. The four arrive for two different purposes. `transaction.posted` and `customer.context-changed` maintain state: the first upserts a row of the card-keyed read model, the second upserts the cardholder context. `transaction.authorized` and `fraud.assessed` maintain nothing — each reads the context and renders an alert straight into `notification_log`, through `NotificationService.renderAuthorizationAlert` and `renderFraudAlert`. A posted transaction does both: it upserts its row and then renders the balance alert. No arrow leaves the module carrying a business event, and no arrow reaches another service. Those two absences are the design. The paired platform-wide before-and-after views are in [architecture, before and after](../../docs/architecture-before-after.md).

**Figure 1 — Notification Service Data Flow: Four Topics, Four Consumer Groups, One Card-Keyed Read Model**
```mermaid
graph LR
    TA{{"transaction.authorized"}}
    TP{{"transaction.posted"}}
    FA{{"fraud.assessed"}}
    CC{{"customer.context-changed"}}
    AC["TransactionAuthorizedConsumer<br/>notification-authorized"]
    PC["TransactionPostedConsumer<br/>notification-posted"]
    FC["FraudFlaggedConsumer<br/>notification-fraud"]
    XC["CustomerContextChangedConsumer<br/>notification-customer"]
    CLAIM{"claim eventId plus consumed topic"}
    SKIP["acknowledge and write nothing"]
    subgraph TX["one local transaction"]
        MARK[("processed_event")]
        ST[("statement_transaction")]
        CX[("cardholder_context")]
        LOG[("notification_log")]
    end
    TEXT["PlainTextRenderer"]
    HTML["HtmlRenderer"]
    API["history endpoint"]
    DLT{{"dead-letter topic, one per source"}}

    TA ==> AC
    TP ==> PC
    FA ==> FC
    CC ==> XC
    AC --> CLAIM
    PC --> CLAIM
    FC --> CLAIM
    XC --> CLAIM
    CLAIM -->|"already taken"| SKIP
    CLAIM -->|"claim won"| MARK
    CLAIM -->|"posted: upsert the read model"| ST
    CLAIM -->|"context: upsert the context"| CX
    CLAIM -->|"authorized: render"| TEXT
    CLAIM -->|"fraud: render"| TEXT
    CLAIM -->|"authorized: render"| HTML
    CLAIM -->|"fraud: render"| HTML
    ST --> TEXT
    CX --> TEXT
    ST --> HTML
    CX --> HTML
    TEXT --> LOG
    HTML --> LOG
    API --> ST
    AC -.-> DLT
    PC -.-> DLT
    FC -.-> DLT
    XC -.-> DLT
```

Legend for Figure 1:

- Hexagon: a Kafka topic.
- Thick arrow: an asynchronous consume from a topic. Every thick arrow is a decoupling point.
- Plain arrow: an in-process call, a read, or a write inside this module.
- Diamond: the duplicate claim against `processed_event`, whose primary key is the event identifier together with the topic the delivery arrived on.
- Cylinder: a table in this module's private schema.
- Box labelled *one local transaction*: the marker and the domain rows commit together or not at all.
- Dotted arrow: the dead-letter route, taken only after the three delivery attempts are spent.
- A label on an arrow out of the diamond names the consumer that takes it, so the two state-maintaining paths and the two rendering paths can be told apart. Only a posted transaction takes both a table arrow and a renderer arrow.
- No arrow carries an event out of the module, and none reaches another service.

<br/>

## Endpoints

One endpoint, served by `NotificationHistoryController`.

| Method and path | Query parameter | Returns |
| :--- | :--- | :--- |
| `GET /notifications/{cardNumber}` | None | Every transaction of the card in ascending transaction-identifier order, with a count and a total |

`cardNumber` is the sixteen digits of `TRNX-CARD-NUM PIC X(16)` at `app/cpy/COSTM01.CPY` line 22. The service derives the stored card token from it through `PanMasker.cardToken`, so the key column holds no value a request carried. The response body holds `cardNumber` masked, `transactionCount`, `totalAmount` and a `transactions` array. Each array item carries twelve fields, one for every field of the layout at `app/cpy/COSTM01.CPY` except the card number, which the envelope names once, and the dropped filler.

**The route declares no paging parameter.** `app/cbl/CBSTM03A.CBL` reads every row of one card between two key breaks and line 429 totals all of them, so a bounded page would report a count and a total over rows the source totalled in full. `NotificationRenderer.MAXIMUM_STATEMENT_ROWS` bounds one rendered alert and not this body.

**The full card number never reaches a rendered alert, a log, or a response.** Masking runs through `PanMasker`, which keeps the last four digits and rewrites the other twelve. A card with no row still names its card, by the masked form of the path value. An empty history answers 200, and the route defines no 404 outcome, so a caller learns nothing about which cards exist.

The hand-written contract, including the 400, 401, 403, 429 and 500 outcomes and the HTTP basic identity the route requires, is in [openapi.yaml](src/main/resources/openapi.yaml). One call against the compose stack, reading the card number out of the fixture rather than writing one down:

```bash
CARD_NUMBER="$(cut -c1-16 app/data/ASCII/carddata.txt | sed -n '1p')"
curl -fsS -u "$ADMIN_USERNAME:the password you chose" \
  "http://localhost:8084/notifications/$CARD_NUMBER"
```

The administrator identity reaches every card. An ordinary identity reaches one only through a `SCOPE_CARD_` authority naming that card's token, and `.env.example` ships none: a token is derived under the key each deployment generates for itself, so an authority written down here would name a card under a key no deployment holds. [Onboarding](../../docs/onboarding.md) gives the command that derives one under your own key.

### Two controls in front of every route

`config/CrossSiteRequestFilter` guards state change: a `POST`, `PUT`, `PATCH` or `DELETE` must carry `X-CardDemo-Request`, must not declare a cross-site `Sec-Fetch-Site`, and must not carry a foreign `Origin`. This service answers two reads and nothing else, so no state-changing route exists here to forge today. That is the reason the filter is here rather than a reason it is not: the read-only shape becomes an enforced property instead of a fact a reader has to go and check, and the first write added inherits the control rather than needing someone to remember it. A refusal answers 403 and counts `carddemo.notification.requests.cross.site.refused`. `GET`, `HEAD`, `OPTIONS` and `TRACE` pass untouched, which is why no path is exempted: the liveness probe and the metrics scrape are reads.

`config/RequestRateCeilingFilter` bounds volume. It runs one place ahead of the security chain, because a refusal has to cost less than the attempt it refuses and an attempt that reached the chain would already have paid for a bcrypt verification.

| Ceiling | Default | Counted by |
| :--- | ---: | :--- |
| Failed authentications | 20 per 60s | Source address, and only when the request carried a credential and was answered 401 |
| Requests | 600 per 60s | Source address |
| Requests | 600 per 60s | The username the credential names, read as a counter key and never verified or logged |
| State-changing requests | 120 per 60s | Source address |
| Requests in flight | 64 | The whole instance |

A refusal answers 429 with `Retry-After` and counts `carddemo.notification.requests.throttled`, tagged with the stage that refused: `authentication`, `source`, `identity`, `write` or `concurrency`. The five ceilings read `API_RATE_WINDOW_SECONDS`, `API_RATE_REQUESTS_PER_WINDOW`, `API_RATE_WRITE_REQUESTS_PER_WINDOW`, `API_RATE_AUTHENTICATION_FAILURES_PER_WINDOW` and `API_RATE_CONCURRENT_REQUESTS` from [`.env.example`](../../.env.example). The management base path is exempt, because a throttled probe reads as a failed container.

Both filters count in this process, so several replicas bound each replica rather than the service as a whole, and the source address is the one the container resolves rather than a forwarding header a caller could write. A deployment behind a proxy sets `SERVER_FORWARD_HEADERS_STRATEGY=framework` so the container resolves the client address and the client-facing host. Both residual limits are recorded in [suggested next tasks](../../docs/suggested-next-tasks.md), and the reasoning behind the two controls is in the [Decision Log](../../docs/decision-log.md).

<br/>

## Domain and data ownership

Four tables in the private schema `notification_service`, inside the database `carddemo_notification`.

| Table | Derivation |
| :--- | :--- |
| `statement_transaction` | `app/cpy/COSTM01.CPY` lines 20–36. **Composite primary key `(card_token, transaction_id)`**, derived from `KEYS(32 0)` at `app/jcl/CREASTMT.JCL` line 30. The 20-byte `FILLER` at `COSTM01.CPY` line 36 is dropped |
| `cardholder_context` | Account-keyed name, address and credit score, kept current by `CustomerContextChanged` |
| `notification_log` | New abstraction: one record per alert this service **rendered and did not send**. Every row carries `outcome = RENDERED_NOT_SENT`, and `ck_notification_log_outcome` permits no other value, so no row here is evidence that a cardholder was told anything |
| `processed_event` | Composite primary key of event identifier and consumed topic, plus the processed timestamp |

The key derivation is arithmetic, not preference. `KEYS(32 0)` names a 32-byte key at offset zero, which is exactly `TRNX-CARD-NUM` at 16 bytes plus `TRNX-ID` at 16 bytes, the group `05 TRNX-KEY.` at `COSTM01.CPY` line 21. The sort at line 53 orders on the card number at offset 263 for sixteen bytes, then on the transaction identifier at offset 1 for sixteen bytes. Offset 263 is where `TRAN-CARD-NUM` sits in the 350-byte transaction record of `app/cpy/CVTRA05Y.cpy`, so the sort key follows the record layout.

`V1__schema.sql` therefore takes its key from `CREASTMT.JCL` line 30 rather than from the transaction file's own key. One substitution applies. The stored key column holds a card token, not the source's full Primary Account Number (PAN). The masked number beside it is display data, and no key or index reads it. The [traceability matrix](../../docs/traceability-matrix.md) records the re-keying, the dropped 20-byte filler, and the key's derivation from a sort step rather than a record layout.

`V2__seed.sql` seeds `cardholder_context` alone, one row per fixture account, each stamped at the Unix epoch so the first real `CustomerContextChanged` supersedes it. No fixture seeds `statement_transaction`: the read model is built by consuming events. `V3__processed_event_topic_key.sql` completes the set, making the consumed topic part of the duplicate-delivery marker's identity so the four listener groups sharing that table can each claim the same event identifier once. Three migrations run on every start, in that order, and there is no fourth.

`V3__processed_event_topic_key.sql` widens the duplicate-delivery key from the event identifier alone to `(event_id, consumed_topic)`. Four listeners read four topics, and two of those topics can carry the same event identifier, so a single-column key let whichever listener claimed first silence the others. The key names the topic because the claim is per delivery path and not per event.

`V4__marker_retention_margin.sql` states the margin the marker window keeps over the broker window, so a marker cannot be purged while a redelivery of its event is still possible.

`V5__rendered_not_delivered.sql` renames `attempted_at` to `rendered_at`, adds the `outcome` column with the check that admits `RENDERED_NOT_SENT` alone, and restates the table comment. This service reaches no mail, message, webhook or push gateway, so a row was never evidence that a cardholder was told anything, and the earlier wording claimed a transport that has never existed.

`V6__subject_request_posture.sql` corrects the `cardholder_context` comment, which described an export and erasure workflow nothing on this platform implements.

Three tables of the four are bounded, and `domain/RetentionSweep` is what bounds them. It runs on `carddemo.history.sweep-interval-ms` and sweeps `processed_event`, `statement_transaction` and `notification_log` in that order, each against its own configured horizon: `carddemo.processed-event.marker-retention-hours`, `carddemo.history.statement-retention-days` and `carddemo.history.log-retention-days`. Each delete takes a row ceiling and the sweep repeats it in a transaction per batch until the table is clear or the table's wall-clock ceiling is reached, so no one statement locks a whole table and no backlog outlives the pass that found it. `cardholder_context` is the fourth and is deliberately unbounded: a row lives as long as the customer relationship, so `observed_at` serves an erasure request rather than a window. The horizons are a demo baseline, and [suggested next tasks](../../docs/suggested-next-tasks.md) carries the task of replacing them with the periods a deployment's jurisdiction requires.

No table here is shared with another service, and this module reaches no other schema. Flyway owns every table, and Jakarta Persistence (JPA) runs with `ddl-auto: validate`, so a mapping that disagrees with the migrated schema stops start-up.

<br/>

## Renderers

`PlainTextRenderer` reproduces the 80-column text layout at `app/cbl/CBSTM03A.CBL` lines 86–146. Its parts, in the order they are written:

1. The opening banner: 31 asterisks, `START OF STATEMENT`, then 31 more asterisks.
2. The name line, then three address lines.
3. A rule of 80 hyphens.
4. A centred `Basic Details` banner.
5. The `Account ID`, `Current Balance` and `FICO Score` label lines.
6. A centred `TRANSACTION SUMMARY` banner, whose source literal pads to 20 characters with one trailing space.
7. The column header, then one detail line per transaction, then the total line.
8. The closing banner: 32 asterisks, `END OF STATEMENT`, then 32 more asterisks.

Two edit patterns put the sign after the digits. `ST-CURR-BAL` is `PIC 9(9).99-` at line 113, and both `ST-TRANAMT` at line 137 and `ST-TOTAL-TRAMT` at line 142 are `PIC Z(9).99-`, where the `Z` form suppresses leading zeros. Reproduce that formatting exactly; do not substitute a locale formatter.

`HtmlRenderer` reproduces the markup declared as condition names on `HTML-FIXED-LN PIC X(100)` from `app/cbl/CBSTM03A.CBL` line 149 onward. Those tag literals are content strings taken from the source. No styling framework and no component library takes part in producing them.

The running total resets per card, not per platform, following `MOVE ZERO TO WS-TOTAL-AMT` at line 325. `WS-TOTAL-AMT` is `PIC S9(9)V99`, so the total carries eleven digits at a scale of two. Every addition runs through `CobolDecimal`, which truncates toward zero.

Truncation is the platform-wide rule, and the evidence is a count. The `ROUNDED` phrase appears **zero** times across all 28 programs in `app/cbl`, so every arithmetic store in the source truncates. `RoundingMode.HALF_UP` is the reflexive Java choice and it is wrong here.

One rendering detail looks like a defect and is not. `TRNX-DESC` is `PIC X(100)` at `app/cpy/COSTM01.CPY` line 28, while the detail line's `ST-TRANDT` is `PIC X(49)` at line 135. The `MOVE` on line 677, inside `6000-WRITE-TRANS` at `app/cbl/CBSTM03A.CBL` line 675, therefore drops the tail of a long description. Both renderers reproduce that truncation.

<br/>

## Patterns

**Idempotent consumer, applied four times.** Each listener claims the envelope event identifier together with the topic the delivery arrived on. It inserts one `processed_event` row that does nothing on conflict, so two deliveries racing on one event cannot both proceed. The topic is part of that key because four producers assign identifiers independently, and `V3__processed_event_topic_key.sql` is the migration that widened it. This module found the defect the narrow key carried: keyed on the identifier alone, a posted transaction and a fraud assessment sharing an identifier would leave one of the two listeners writing nothing. The claim runs inside the transaction that carries the side effects, and the listener acknowledges only after that transaction commits. Kafka delivers at least once, and a redelivery follows a group rebalance, a restart mid-batch, or a crash after the writes but before the offset commit. The concrete consequence: a duplicate `TransactionPosted` must not add its amount to the per-card total twice.

**No outbox.** A module that publishes nothing needs none, and none is declared. Do not add one speculatively.

**Repository interfaces** follow the operation-code contract at `app/cbl/CBSTM03B.CBL` lines 100–112. `K`, a keyed read, becomes find by identifier; `R` becomes stream all; `W` becomes insert; `Z` becomes update. The `O` open and `C` close codes are dropped, because the framework owns connection lifecycle. That subroutine dispatches across `TRNXFILE`, `XREFFILE`, `CUSTFILE` and `ACCTFILE` at lines 118–127, and `TRNXFILE` is this module's read model.

**Money travels as a decimal string in every event payload, never as a JavaScript Object Notation (JSON) number.** Most parsers turn a JSON number into a double. That puts binary floating point back into a fixed-point system.

<br/>

## Pitfalls

1. **The language level fails silently.** `pom.xml` must declare `<java.version>25</java.version>`, because the Spring Boot parent defaults the language level and the compiler release to 17. A module that omits the override compiles cleanly at release 17 with no warning, so check the class-file major version is 69 rather than 61.
2. **Truncate, never round.** Every total runs through `CobolDecimal` with `RoundingMode.DOWN`. The `ROUNDED` phrase appears zero times across all 28 programs in `app/cbl`.
3. **The total resets per card.** `MOVE ZERO TO WS-TOTAL-AMT` at `app/cbl/CBSTM03A.CBL` line 325 runs once per card. A running platform-wide total is wrong.
4. **The trailing-sign edits are not a locale format.** `PIC 9(9).99-` and `PIC Z(9).99-` place the sign after the digits. A locale formatter puts it in front and pads differently.
5. **`fraud.assessed` carries two event types.** Route on the envelope `eventType` field. A consumer that assumes every message is a `FraudFlagged` mishandles `FraudCleared`.
6. **The two keys differ on purpose.** The Kafka message key is the account identifier; the read model key is the card token plus the transaction identifier. Confusing them breaks either partition ordering or the primary key.
7. **The wildcard hazard.** `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`, `app/cpy/COSTM01.CPY` and `app/jcl/CREASTMT.JCL` all break the lower-case extension convention, and a glob over lower-case extensions silently drops three of this module's four primary sources. Name source files explicitly, with their exact case.
8. **A state-changing call needs one extra header.** This service answers reads, so nothing a caller can reach today is affected. A state-changing route added later is refused from any client that does not send `X-CardDemo-Request`, because `config/CrossSiteRequestFilter` guards the method rather than a list of routes. Add the header to the client at the same time as the route.
9. **A burst answers 429 rather than being served.** A load generator, a retry loop, or a test that hammers one address reaches `config/RequestRateCeilingFilter` and answers 429 with `Retry-After`. The blanket ceiling is 600 requests a minute per address and per identity, writes are 120, and 20 failed authentications from one address close the rest of that minute. Raise `API_RATE_*` for a load run rather than removing the filter, and read the `stage` tag on `carddemo.notification.requests.throttled` to see which ceiling refused.

Platform-wide pitfalls are in [onboarding](../../docs/onboarding.md) and are not repeated here.

<br/>

## Local run and tests

Prerequisites, pinned exactly:

1. OpenJDK 25, Eclipse Temurin build 25.0.4+7.
2. Apache Maven 3.9.16.
3. Docker Engine 29.7.0 with Docker Compose 5.3.1, the tested pair.
4. Container images `apache/kafka:4.2.1` and `postgres:18.4`, both pinned by digest in `docker-compose.yml`.

The runtime contract:

| Setting | Value |
| :--- | :--- |
| Container port | 8080, published on host port 8084 as `NOTIFICATION_PORT` |
| Management port | 9080, published on host port 9084 as `NOTIFICATION_MANAGEMENT_PORT` |
| Database and schema | `carddemo_notification`, schema `notification_service` |
| Database login | `carddemo_notification_svc`, password from `NOTIFICATION_DB_PASSWORD` with no default |
| Kafka bootstrap | `kafka:29092` inside the compose network, `localhost:9092` from the host, in KRaft (Kafka Raft) mode |
| Broker identity | `carddemo-notification`, password from `NOTIFICATION_KAFKA_PASSWORD` with no default |
| Health check target | `http://localhost:9084/actuator/health` from the host |
| Actuator endpoints | `health`, `metrics`, `prometheus` |

Six meters are registered, every one of them by `config/ObservabilityConfig`. Each carries the common `service` tag, and the tagged ones are pre-registered across every tag value at start-up, so a value reads zero rather than being absent before its first occurrence. A dashboard that has to wait for a failure before the failure series exists cannot show that there have been none.

| Metric | Tag | Meaning |
| :--- | :--- | :--- |
| `carddemo.notification.events.consumed` | `event.type` | Events this service consumed, one per event |
| `carddemo.notification.processing.latency` | `event.type` | Time one listener spent handling one event |
| `carddemo.notification.notifications.rendered` | `format` | Cardholder alerts rendered, tagged `text`, `html` or `unknown` |
| `carddemo.notification.duplicates.skipped` | none | Events a listener skipped because `processed_event` already held the claim |
| `carddemo.notification.failures` | `failure.kind` | Failed delivery attempts, one per attempt |
| `carddemo.notification.records.dead.lettered` | `failure.kind` | Records whose delivery attempts ran out, one per record |

The last two are a pair and are not interchangeable. `failures` counts attempts, so one record that fails three times adds three; `records.dead.lettered` counts records, so the same record adds one when its attempts run out. Reading either as the other overstates or understates the incident by the retry count. Both tag on `failure.kind`, whose values are `schema_validation`, `deserialization`, `persistence`, `rendering` and `unknown`, and `event.type` takes the five event types this module consumes plus `unknown`.

The compose file sets these six properties, and each one overrides the shipped default: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA`, `SPRING_FLYWAY_SCHEMAS` and `SPRING_KAFKA_BOOTSTRAP_SERVERS`.

Run it from the `card-platform` directory. Every `Dockerfile` copies its packaged Java archive (JAR) out of `target/`, so the build has to finish before any image build. Nothing below prompts, and nothing below runs unbounded.

Step 1 gives all nineteen credentials a value. `.env` is ignored by git, and `.env.example` documents every variable.

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

Keep the single quotes on the three hashes. A bcrypt value is full of `$`, and Compose expands `$` in an unquoted dotenv value. `mvn package` above builds every module, so every image has an archive to copy.

Step 2 builds this module with the two libraries it depends on and runs its tests:

```bash
mvn -B -pl services/notification-service -am verify
```

Step 3 starts the four containers needed to see an alert driven by a real event, and waits for each to report healthy:

```bash
docker compose up -d --build --wait postgres kafka authorization-service notification-service
curl -fsS http://localhost:9084/actuator/health
```

Step 4 submits one authorization. This module consumes `transaction.authorized` directly under `notification-authorized`, so one call is enough to produce an alert without the ledger running:

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
       \"description\":\"Notification guide purchase\",
       \"amount\":\"+00000504.77\",
       \"merchantId\":\"800000000\",
       \"merchantName\":\"Abshire-Lowe\",
       \"merchantCity\":\"North Enoshaven\",
       \"merchantZip\":\"72112\",
       \"originTimestamp\":\"${CAPTURED_AT}\",
       \"processingTimestamp\":\"${PROCESSED_AT}\"}" \
  http://localhost:8081/authorizations
```

Step 5 reads what this module did with that event. The history route names the card by its token, which is what every external surface accepts:

```bash
CARD_TOKEN=d29277ff9f4215818ca524cbf2e94927149958ef6c6a9f49242ffa18a484fe9d
curl -fsS -u "admin001:$ADMIN_PASSWORD" "http://localhost:8084/notifications/$CARD_TOKEN"
```

That answers one transaction, a masked card number, and the per-card total. Read the bounded log for the same delivery rather than following the log forever:

```bash
docker compose logs --no-log-prefix --tail 40 notification-service
```

Set `CLONE_INDEX`, `POSTGRES_PORT` and `KAFKA_PORT` when another copy of the stack already runs on the same machine.

<br/>

## How to extend

- Adding a consumer of an event this module already receives needs no change to any producer: give the listener its own consumer group and the same claim-inside-the-transaction rule.
- Adding a rendered format means adding a class beside `PlainTextRenderer` and `HtmlRenderer` that implements `NotificationRenderer`.

The steps above are complete on their own. [Onboarding](../../docs/onboarding.md) adds the platform-wide walkthrough, domain background and pitfalls, and follow-up work is listed in [suggested next tasks](../../docs/suggested-next-tasks.md).

<br/>

## Deliberate non-additions

Each item below is absent on purpose. Adding one back changes behaviour, widens scope, or breaks a guarantee this module claims.

- No full statement generation.
- No query against the ledger, account or card service.
- No outbox and no published business event.
- No cache or key-value store.
- No electronic mail gateway, no short-message gateway, no webhook caller and no push gateway. Nothing on this platform sends a rendered alert anywhere, which is why every `notification_log` row carries `outcome = RENDERED_NOT_SENT`. [Deliver the rendered cardholder alert](../../docs/suggested-next-tasks.md) is the task that would add one.
- No user interface, component library or styling framework.
- No documentation generator; the contract in `openapi.yaml` is hand-written.
- No boilerplate generator and no object-mapping framework.
- No COBOL compiler, emulator or mainframe connector.

<br/>

## Related documentation

- [Platform README](../../README.md)
- [Decision log](../../docs/decision-log.md), the single home for why each choice was made
- [Event flow](../../docs/event-flow.md)
- [Data model](../../docs/data-model.md)
- [Equivalence results](../../docs/equivalence-results.md)
