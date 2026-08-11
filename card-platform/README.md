## CardDemo Card Platform

- [Overview](#overview)
- [Delivered capability](#delivered-capability)
- [Quickstart](#quickstart)
- [Security and transport](#security-and-transport)
- [Repository map](#repository-map)
- [Runtime architecture](#runtime-architecture)
- [Module boundary](#module-boundary)
- [Services and APIs](#services-and-apis)
- [Events and consumer groups](#events-and-consumer-groups)
- [Equivalence](#equivalence)
- [Documentation](#documentation)

<br/>

## Overview

This directory contains six independently deployable Java 25 services on Spring Boot 4.1.0. Kafka carries service events, and one PostgreSQL instance hosts six private databases and schemas.

The platform forward-engineers documented CardDemo behavior without calling the mainframe at runtime. Files under `app/`, `diagrams/`, and `samples/` remain reference inputs and are not modified.

The architecture has one synchronous authorization entry point. Ledger posting, fraud detection, and notification processing run asynchronously without direct service calls between consumers.

<br/>

## Delivered capability

The Maven aggregator contains nine child modules and builds ten reactor projects, including the parent. The full source, contract, integration, and equivalence lifecycle runs through `mvn verify`.

| Capability | Delivered implementation |
| :--- | :--- |
| Authorization | Four source-derived decline rules, one response, one persisted decision, and exactly one outcome event for each authenticated, parseable request that reaches an authorization decision. Reject reason 0100 is the one decided outcome whose event is keyed on the transaction identifier |
| Posting | Transaction, category-balance, account-balance, account-state replica, feed-reject, outbox, and duplicate-delivery handling |
| Fraud | Three net-new risk rules, persisted assessments, and flagged or cleared events |
| Notification | Authorized-transaction, posted-transaction, fraud, and customer-context consumers, private read models, two renderers, alert-attempt metadata, and history API |
| Account | Account and customer reads, coordinated update, source-compatible conflict check, cycle close, and state-change publication |
| Card | Seven-row cursor browse, card detail, ordered update, and state-change publication |
| Contracts | Fourteen JavaScript Object Notation (JSON) Schema Draft 2020-12 documents, validating serialization, validating deserialization, and compatibility tests |
| Persistence | Flyway-owned schemas, fixture-backed seeds, Jakarta Persistence validation, outboxes, and processed-event guards |
| Operations | Structured JSON logs, health and metrics endpoints, terminal dead-letter routes for spent records and abandoned outbox rows, Docker Compose, and Kubernetes manifests |
| Equivalence | Posting, authorization, bill payment, interest rules, validation, identifier, fixture, card-seed, and decimal comparisons |

Eleven listeners are present:

- authorization consumes `AccountStateChanged` under `authorization-account-state`, keeping `account_credit_snapshot` current;
- authorization consumes `CardUpdated` under `authorization-card-updated`, which refreshes the observation timestamp on the matching `card_xref` rows and writes no mapping field;
- ledger consumes authorized transactions under `ledger-posting` and posts them;
- ledger consumes declined transactions under `ledger-reject` and writes the 430-byte reject row of `app/cbl/CBTRN02C.cbl:L446-L465`;
- ledger consumes `AccountStateChanged` under `ledger-account-state`, which bootstraps and refreshes `account_balance_projection`;
- fraud consumes authorized transactions under `fraud-detection`;
- notification consumes authorized transactions under `notification-authorized`;
- notification consumes posted transactions under `notification-posted`;
- notification consumes fraud assessments under `notification-fraud`;
- notification consumes `CustomerContextChanged` under `notification-customer` to keep renderer context current;
- account consumes posted transactions under `account-posted`, which carries the posted amount back onto the account record.

Ledger, fraud, and notification are therefore three independent consumers of the one authorization event, each reading it under its own group.

Each listener claims an event identifier before applying effects. It acknowledges only after its local transaction commits. A listener never reverses a decision another service reached.

Recovery after the delivery attempts of one record run out is uniform in one respect and per service in another. All five listening services set `DefaultErrorHandler.setCommitRecovered(true)`: the offset is committed once the diagnostic has reached a dead-letter topic, so one spent record produces exactly one dead letter rather than an endless redelivery. Where they differ is the destination. Ledger, fraud, and notification address the source topic plus the configured suffix, so one dead-letter stream carries one source wire shape. Authorization and account publish a governed `DeadLetterEnvelope` to the shared topic instead, so nothing a refused record carried travels with it. Every container acknowledges by hand in both cases, so nothing acknowledges a record its listener never accepted.

<br/>

## Quickstart

Use the tested versions below:

| Tool | Version |
| :--- | :--- |
| Eclipse Temurin OpenJDK | 25.0.4+7 |
| Apache Maven | 3.9.16 |
| Docker Engine | 29.7.0 or later |
| Docker Compose | 5.3.1 or later |
| Kafka image | `apache/kafka:4.2.1` |
| PostgreSQL image | `postgres:18.4` |

The first two rows are refusals: the enforcer plugin rejects a build outside `[25,26)` and `[3.9.16,3.10.0)`, so a newer Maven fails rather than passes. The two image tags are exact because each is pinned by digest as well. Docker Engine and Compose are floors, because nothing here constrains them: the versions given are the ones this stack was exercised on.

One command takes a clean clone to a running stack:

```bash
cd card-platform
scripts/start-demo.sh
```

[`scripts/start-demo.sh`](scripts/start-demo.sh) packages the reactor, creates `.env` from `.env.example`, fills all 19 credentials, builds the six images, starts eight containers, and reads every health endpoint. It asks nothing and is safe to re-run: a credential already set is left alone. The four demo passwords it generates are written to `card-platform/.demo-credentials`, which git ignores, because `.env` keeps only their `{bcrypt}` hashes. That file is created owner-only and is never narrowed afterwards.

To prepare the environment file without starting anything, run [`scripts/generate-env.sh`](scripts/generate-env.sh). It reads the credential names out of `.env.example`, so it needs no second list of them. Run it again after a pull: an existing `.env` is reconciled against the example rather than replaced, keeping every value already chosen, and the run fails if any declared assignment is still absent. Two exceptions earn their keep after an upgrade. A key this repository publishes is regenerated, because every service that reads one refuses to start on it. A setting whose value differs from the example's is reported with both values, so a tightened default is visible before it stops a container.

The same work by hand is four steps, and none is optional. The copied file carries **19** `REPLACE` markers: fourteen local passwords, one card-token key and four `{bcrypt}` identity hashes. Every one has to be generated before the stack starts, so the steps below generate all nineteen and then prove none is left.

```bash
cd card-platform

# install rather than cp: the copy holds every credential a moment later, and cp leaves it
# readable by every local account.
install -m 600 .env.example .env

# Step 1. The fourteen service passwords and the card-token key, generated locally and
# written in place. The key is longer because two services refuse anything under 32
# characters.
for variable in POSTGRES_PASSWORD \
  AUTHORIZATION_DB_PASSWORD LEDGER_DB_PASSWORD FRAUD_DB_PASSWORD \
  NOTIFICATION_DB_PASSWORD ACCOUNT_DB_PASSWORD CARD_DB_PASSWORD \
  KAFKA_ADMIN_PASSWORD \
  AUTHORIZATION_KAFKA_PASSWORD LEDGER_KAFKA_PASSWORD FRAUD_KAFKA_PASSWORD \
  NOTIFICATION_KAFKA_PASSWORD ACCOUNT_KAFKA_PASSWORD CARD_KAFKA_PASSWORD; do
  sed -i "s|^${variable}=.*|${variable}=$(openssl rand -base64 24 | tr -d '/+=')|" .env
done
sed -i "s|^CARD_TOKEN_SECRET=.*|CARD_TOKEN_SECRET='$(openssl rand -base64 48 | tr -d '/+=')'|" .env

# Step 2. The four request identities. Each password stays a shell variable of this shell
# only: unexported, so no command this shell runs inherits it, and unset at the end. Only
# the hash reaches .env. Write these four down now, because .env keeps no plaintext.
admin_password="$(openssl rand -base64 18 | tr -d '/+=')"
acquirer_password="$(openssl rand -base64 18 | tr -d '/+=')"
user_password="$(openssl rand -base64 18 | tr -d '/+=')"
monitoring_password="$(openssl rand -base64 18 | tr -d '/+=')"
```

```bash
# Step 3. Package the reactor. Each image copies an archive out of its own target
# directory, and jshell reads the encoder out of the local Maven repository.
mvn -B -ntp -DskipTests package
```

Then encode each password and write it back single-quoted. Compose expands `$` in an unquoted dotenv value, and a bcrypt hash is full of `$`:

```bash
# Step 4. Hash the four, prove all nineteen markers are gone, and drop the plaintext.
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

sed -i "s|^ADMIN_PASSWORD_HASH=.*|ADMIN_PASSWORD_HASH='$(hash_password "$admin_password")'|" .env
sed -i "s|^ACQUIRER_PASSWORD_HASH=.*|ACQUIRER_PASSWORD_HASH='$(hash_password "$acquirer_password")'|" .env
sed -i "s|^USER_PASSWORD_HASH=.*|USER_PASSWORD_HASH='$(hash_password "$user_password")'|" .env
sed -i "s|^MONITORING_PASSWORD_HASH=.*|MONITORING_PASSWORD_HASH='$(hash_password "$monitoring_password")'|" .env

# Prove nothing is left unset before starting anything. The count is read from the file, so
# it cannot disagree with the file.
remaining="$(grep -c '^[A-Za-z_][A-Za-z0-9_]*=.*REPLACE' .env || true)"
if [ "${remaining}" -gt 0 ]; then
  echo "still unset: ${remaining}"; grep -n '^[A-Za-z_][A-Za-z0-9_]*=.*REPLACE' .env
else
  echo "every credential .env.example declares is set"
fi

unset admin_password acquirer_password user_password monitoring_password
```

`.env` is ignored by git and must stay that way. Verify with `git check-ignore -v .env`.

`CARD_TOKEN_SECRET` is a `REPLACE` marker like every password: `.env.example` ships no deployable key, and the two services that derive a card token refuse to start without one. Two keys this repository does publish are refused by name: the build-scope key `pom.xml` supplies to the test suites, and the bootstrap key the Kubernetes Secret template carries. `CARD_TOKEN_VERSION` is not a marker and stays as shipped, because three checked-in values name the version their tokens were derived under. [Onboarding](docs/onboarding.md) gives the rotation procedure, and [Security and transport](#security-and-transport) explains what the key does.

### 2. Build, start, and check

```bash
mvn -B clean verify
scripts/generate-env.sh
docker compose up -d --build
docker compose ps
```

The image build reads nothing from `target/`: each Dockerfile compiles its own module in a Java Development Kit 25 builder stage from this directory. The Maven command above is what proves the suite and fills the local repository the credential script reads, not what feeds the images.

Check all health endpoints:

```bash
for port in 9081 9082 9083 9084 9085 9086; do
  curl -fsS "http://localhost:${port}/actuator/health"
  echo
done
```

Without `--wait`, `up -d` returns as soon as the containers are created and those six requests race the start-up they are meant to confirm.

### 3. Authorize one transaction and watch the fan-out

Both timestamps are generated so that the call reads as one made now. No clock bound relates the capture moment to the clock of the authorization service: reject code 0103, which compares the account expiry against the first ten characters of the capture moment, is the whole of the test applied to it. The card number is the first record of the checked-in cross-reference fixture.

```bash
CARD_NUMBER=$(sed -n '1s/^\(.\{16\}\).*/\1/p' ../app/data/ASCII/cardxref.txt)
CAPTURED_AT="$(date -u +'%Y-%m-%d %H:%M:%S').000000"
PROCESSED_AT="$(date -u +'%Y-%m-%d-%H.%M.%S').000000"

curl -sS -u "admin001:$ADMIN_PASSWORD" -X POST \
  -H 'X-CardDemo-Request: quickstart' \
  -H 'Content-Type: application/json' \
  -d "{\"cardNumber\":\"${CARD_NUMBER}\",
       \"transactionTypeCode\":\"01\",
       \"transactionCategoryCode\":\"0001\",
       \"source\":\"POS TERM\",
       \"description\":\"Quickstart purchase\",
       \"amount\":\"+00000504.77\",
       \"merchantId\":\"800000000\",
       \"merchantName\":\"Abshire-Lowe\",
       \"merchantCity\":\"North Enoshaven\",
       \"merchantZip\":\"72112\",
       \"originTimestamp\":\"${CAPTURED_AT}\",
       \"processingTimestamp\":\"${PROCESSED_AT}\"}" \
  http://localhost:8081/authorizations
```

That request answers HTTP 200 with `"approved":true` and a generated `transactionId`. The response uses HTTP 422 for a source-equivalent decline, and `approved` distinguishes the two outcomes. A decline is expected traffic, so it never answers 500. The authorization service's `src/main/resources/openapi.yaml` carries the full request shape, including the eleven required fields and the rule that a caller names its subject by `cardNumber` or by `accountId`.

An approval on shipped Compose settings depends on one demo-only migration. Every expiry in `app/data/ASCII/acctdata.txt` falls in 2023 to 2025, and reason 0103 declines a request whose capture date is later than the account expiry. `AUTHORIZATION_FLYWAY_LOCATIONS` and `ACCOUNT_FLYWAY_LOCATIONS` therefore add `classpath:db/demo`, whose `V900__demo_expiry_extension.sql` extends those expiries to 2099-12-31 and changes nothing else. `deploy/k8s/30-configmap.yaml` carries the same two keys with the same values, so the cluster path is a demo profile too. Drop that location from both keys to compare a run against the fixture as shipped.

One approval reaches ledger, fraud, and notification independently, each under its own consumer group. Read the event all three received, bounded so the command returns:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 \
  --command-config /tmp/kafka-admin.properties \
  --topic transaction.authorized --from-beginning --max-messages 1
```

Then read the three consumers, each of which acted without being called:

```bash
CARD_TOKEN="$(printf 'CardDemo/card-token/v%s:%s' \
    "$(grep '^CARD_TOKEN_VERSION=' .env | cut -d= -f2- | tr -d "'\"")" "$CARD_NUMBER" \
  | openssl dgst -sha256 -hmac \
      "$(grep '^CARD_TOKEN_SECRET=' .env | cut -d= -f2- | tr -d "'\"")" -r | cut -d' ' -f1)"
unset CARD_NUMBER

curl -fsS -u "admin001:$ADMIN_PASSWORD" http://localhost:8082/balances/00000000050
curl -fsS -u "admin001:$ADMIN_PASSWORD" "http://localhost:8084/notifications/$CARD_TOKEN"
curl -fsS -u "admin001:$ADMIN_PASSWORD" "http://localhost:8083/fraud-assessments?accountId=00000000050"
```

That command takes the same keyed code `PanMasker.cardToken` takes — `HMAC-SHA-256` over a fixed label, the configured version and the card number — so it reproduces the token this stack stored for that card. It is derived rather than written down because a token belongs to one `CARD_TOKEN_SECRET`: a literal here would name a card under a key no other deployment holds. Every route that names one card names it by that card's token and never by its number, so this is the value the notification and card routes read. The one surface that still takes a number is the authorization request body above, and that route names no card in its path.

Consumer progress is visible from inside the broker container, which is how a demonstration shows that three groups read one topic:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:29092 \
  --command-config /tmp/kafka-admin.properties \
  --all-groups --describe
```

<br/>

## Security and transport

Every business route requires HTTP Basic authentication. The security chains deny unmatched routes by default.

| Identity | Role | Access |
| :--- | :--- | :--- |
| `admin001` | `ADMIN` | Every business route |
| `acquirer1` | `ACQUIRER` | `POST /authorizations` only |
| `user0001` | `USER` | Resources named by `USER_SCOPES` |
| `monitor01` | `MONITORING` | Metrics and Prometheus endpoints only |

`acquirer1` is a machine identity a point-of-sale network presents, and only the authorization service reads it. `POST /authorizations` names its card in the request body, so no path variable carries an identifier an ownership scope can be compared against, and the route therefore reaches every card the platform holds. A cardholder identity is refused there with 403 for that reason: an entitlement over one account must not authorize against another. The chain admits `ACQUIRER` and `ADMIN` there and nothing else, and the decision itself then compares the identity against the account the cross-reference resolved, so a caller entitled to no account reaches no decision. The decline rules are the control that applies to the card: a card the cross-reference does not carry is refused with reason 0100 from `app/cbl/CBTRN02C.cbl:L385-L387`. Ownership managers govern the other five services, where a path variable names an account, a customer or a card token.

Anonymous access is limited to `/actuator/health` on each management port. Business identities do not receive monitoring access.

Passwords are encoded before they enter configuration. Each service accepts `{bcrypt}` at a cost of at least ten or `{pbkdf2@SpringSecurity_v5_8}`, and refuses every other encoding at start-up, `{noop}` included.

Two filters run in front of every route in all six services. `CrossSiteRequestFilter` refuses a `POST`, `PUT`, `PATCH` or `DELETE` with 403 on any one of three conditions, and the three are not symmetrical. `X-CardDemo-Request` is **mandatory**: a request that omits it or sends it blank is refused. `Sec-Fetch-Site` and `Origin` are checked **only when the request carries them** — a present `Sec-Fetch-Site` has to read `same-origin` or `same-site`, and a present `Origin` has to name this service's own origin, while an absent one refuses nothing. That asymmetry is deliberate: a browser sets both headers and page script cannot forge either, so their absence identifies a non-browser client, which is every legitimate caller here. HTTP Basic is a credential a browser attaches without being asked, and an HTML form cannot set a header, so one header separates a first-party client from a page replaying a cached credential. Reads are untouched, which is why the health probe carries nothing. `RequestRateCeilingFilter` bounds volume ahead of the security chain. The ceilings are 20 failed authentications and 600 requests a minute per source address, 600 a minute per identity, 120 state-changing requests, and 64 requests in flight. Past any of them it answers 429 with `Retry-After`, and counts `carddemo.<service>.requests.throttled` by the ceiling that refused. `API_CROSS_SITE_HEADER` and `API_RATE_*` configure both. Each counts inside one process, so a multi-replica deployment bounds each replica; [suggested next tasks](docs/suggested-next-tasks.md) records the shared-store ceiling and the forwarded-header setting a proxied deployment needs.

Every route that names one card names it by that card's token, never by its number, and the one surface that still takes a number is the authorization request body described below. The token is a keyed hash: `HMAC-SHA-256` over the full number under `CARD_TOKEN_SECRET`, prefixed by `CARD_TOKEN_VERSION`, rendered as 64 lower-case hexadecimal characters. This repository ships no deployable runtime key. It does publish two keys, the build-scope key `pom.xml` gives the test suites and the bootstrap key `deploy/k8s/31-secret.example.yaml` carries, and both services refuse to start on either. Generate a real one with `openssl rand -base64 48 | tr -d '/+='` before the first run: only the authorization and card services read it, and both refuse to start without it. Two consequences matter operationally. The fifty `card_token` literals that `services/card-service/.../V2__seed.sql` loads are derived under a build-scope key, and `CardTokenReconciler` re-derives them under yours as the card service starts. A `SCOPE_CARD_` authority names a token rather than a number, so it belongs to one key and is derived rather than shipped. [Onboarding](docs/onboarding.md) gives both commands.

One surface is the exception, and it is a request body rather than a request line: `POST /authorizations` accepts a full sixteen-digit `cardNumber` in an authenticated JSON body over the loopback-bound port. That route names no card in its path at all, and the number is the cross-reference key `app/cbl/CBTRN02C.cbl:L383-L387` reads, so no token can stand in for it there. A caller may name an `accountId` instead, in which case no card number is sent. Nothing echoes the number back — every response carries the masked form — and `PanMasker` is applied before any log line or event payload is written. The two card routes and the notification history route each name their card by its token in the path, and accept no card number anywhere. The token resolves to the row inside the owning service, and the number never leaves it.

The Compose stack binds every published port to `127.0.0.1`. It uses separate database and Kafka credentials for each service.

| Transport | Compose | Kubernetes |
| :--- | :--- | :--- |
| Database | `sslmode=require` | `sslmode=verify-full` |
| Kafka | `SASL_PLAINTEXT` on the private bridge | `SASL_SSL` |
| Service ports | HTTP on loopback | HTTPS with mounted key material |

The demonstration uses synthetic repository fixtures. Do not expose the Compose ports or load real cardholder data.

<br/>

## Repository map

| Path | Contents |
| :--- | :--- |
| `pom.xml` | Aggregator, Java 25 setting, plugin management, and nine child modules |
| `docker-compose.yml` | Kafka, PostgreSQL, six service containers, health checks, and topic creation |
| `.env.example` | Supported runtime overrides and credential placeholders |
| `scripts/` | `start-demo.sh`, the one-command bootstrap, and `generate-env.sh`, which fills the 19 credentials |
| `libs/event-contracts/` | Event records, envelope, schemas, wire bounds, serializers, and compatibility tests |
| `libs/cobol-compat/` | Fixed-point arithmetic, parsing, date validation, masking, and source reference data |
| `services/authorization-service/` | Synchronous authorization and account-state projection |
| `services/ledger-posting-service/` | Posting arithmetic and balance projection |
| `services/fraud-detection-service/` | Net-new risk assessment |
| `services/notification-service/` | Card-keyed transaction model and alert rendering |
| `services/account-service/` | Account, customer, validation, and cycle-close operations |
| `services/card-service/` | Card browse, detail, and update |
| `equivalence-tests/` | Cross-module contracts and source-rule comparisons |
| `docs/` | Decisions, traceability, architecture, data model, onboarding, flags, results, prose validation, and next tasks |
| `deploy/k8s/` | Namespace, Kafka, PostgreSQL, configuration, secrets template, Deployments, Services, and `load-images.sh`, which puts the six local images inside a kind, minikube, or Docker Desktop cluster |

Each service contains:

- `pom.xml` and `Dockerfile`;
- a service-level `README.md`;
- `api/`, `domain/`, `entity/`, `repository/`, `messaging/`, `outbox/`, and `config/` packages as needed;
- `application.yml`, `openapi.yaml`, and Flyway migrations;
- direct unit, persistence, contract, and integration tests.

The platform has no application front end, service mesh, cache, schema-registry container, or runtime mainframe connector.

<br/>

## Runtime architecture

Figure 1 shows the delivered request, event, and projection paths.

**Figure 1 — Delivered Card Platform: One Authorization Decision and Independent Event Consumers**

```mermaid
graph TB
    CLIENT["REST client"]
    AUTH["authorization-service"]
    RULES{"decline rules<br/>0100, 0101, 0102, 0103"}
    AUTHDB[("authorization_service")]
    TA{{"transaction.authorized"}}
    TD{{"transaction.declined"}}
    LEDGER["ledger-posting-service"]
    FRAUD["fraud-detection-service"]
    LEDGERDB[("ledger_service")]
    FRAUDDB[("fraud_service")]
    TP{{"transaction.posted"}}
    FA{{"fraud.assessed"}}
    NOTIFY["notification-service"]
    NOTIFYDB[("notification_service")]
    ACCOUNT["account-service"]
    ACCOUNTDB[("account_service")]
    AS{{"account.state-changed"}}
    CC{{"customer.context-changed"}}
    CARD["card-service"]
    CARDDB[("card_service")]
    CU{{"card.updated"}}
    DEAD{{"carddemo.dead-letter"}}

    CLIENT -->|"POST /authorizations"| AUTH
    AUTH --> RULES
    RULES --> AUTHDB
    RULES ==>|approved| TA
    RULES ==>|declined| TD
    TA ==> LEDGER
    TA ==> FRAUD
    TA ==> NOTIFY
    LEDGER --> LEDGERDB
    FRAUD --> FRAUDDB
    LEDGER ==> TP
    FRAUD ==> FA
    TP ==> NOTIFY
    TP ==> ACCOUNT
    FA ==> NOTIFY
    NOTIFY --> NOTIFYDB
    ACCOUNT --> ACCOUNTDB
    ACCOUNT ==> AS
    ACCOUNT ==> CC
    AS ==> AUTH
    AS ==> LEDGER
    CC ==> NOTIFY
    CARD --> CARDDB
    CARD ==> CU
    CU ==> AUTH
    LEDGER -.-> DEAD
    FRAUD -.-> DEAD
    NOTIFY -.-> DEAD
    AUTH -.-> DEAD
    ACCOUNT -.-> DEAD
    CARD -.-> DEAD
```

Legend for Figure 1:

- Plain arrows are synchronous request or private database work.
- Thick arrows are Kafka publish or consume paths.
- Dotted arrows are terminal dead-letter routes onto the shared topic. Two details are abstracted away here and stated exactly in [Event Flow](docs/event-flow.md). Ledger, fraud, and notification address a source-specific `.DLT` topic first. The authorization edge is its listener route, because its relay leaves a spent row unpublished.
- The diamond is the source-derived decision chain.
- Cylinders are schemas read by one service only.
- No consumer calls another consumer.
- Three thick arrows leave `transaction.authorized`, one for each independent consumer.

The source and target are compared in [Architecture, Before and After](docs/architecture-before-after.md). Every event path appears in [Event Flow](docs/event-flow.md).

The measured CICS definition contains 8 files, 17 mapsets, 18 programs, and 18 transactions. The broader source directory contains 28 COBOL programs.

<br/>

## Module boundary

Figure 2 shows the compile dependency direction.

**Figure 2 — Maven Boundary: Shared Libraries Below Services and Equivalence Tests Above**

```mermaid
graph TB
    EQ["equivalence-tests"]

    subgraph SERVICES["six service modules"]
        A["authorization"]
        L["ledger"]
        F["fraud"]
        N["notification"]
        AC["account"]
        C["card"]
    end

    subgraph LIBRARIES["two shared libraries"]
        EVENTS["event-contracts"]
        COBOL["cobol-compat"]
    end

    EQ -.-> SERVICES
    EQ -.-> LIBRARIES
    SERVICES --> EVENTS
    SERVICES --> COBOL
```

Legend for Figure 2:

- Solid arrows are compile dependencies.
- Dotted arrows are test-scope dependencies.
- No arrow runs between service modules.

Each service depends on both shared libraries and no other service. A forbidden inter-service Java import fails compilation and the Maven enforcer reports it.

The equivalence module depends on every module for tests only. It is last in the reactor.

<br/>

## Services and APIs

| Service and guide | Business port | Management port | Delivered operations |
| :--- | ---: | ---: | :--- |
| [authorization-service](services/authorization-service/README.md) | 8081 | 9081 | `POST /authorizations` |
| [ledger-posting-service](services/ledger-posting-service/README.md) | 8082 | 9082 | `GET /balances/{accountId}` |
| [fraud-detection-service](services/fraud-detection-service/README.md) | 8083 | 9083 | `GET /fraud-assessments`, `GET /fraud-assessments/{transactionId}` |
| [notification-service](services/notification-service/README.md) | 8084 | 9084 | `GET /notifications/{cardToken}` |
| [account-service](services/account-service/README.md) | 8085 | 9085 | `GET /accounts/{accountId}`, `PUT /accounts/{accountId}`, `POST /accounts/{accountId}/cycle-close`, `GET /customers/{customerId}` |
| [card-service](services/card-service/README.md) | 8086 | 9086 | `GET /cards`, `GET /cards/{cardToken}`, `PUT /cards/{cardToken}` |

All business ports map to container port 8080. All management ports map to container port 9080.

Kafka is `kafka:29092` inside Compose and `localhost:9092` from the host. PostgreSQL is `postgres:5432` inside Compose and `localhost:5432` from the host.

| Service | Database | Schema |
| :--- | :--- | :--- |
| authorization | `carddemo_authorization` | `authorization_service` |
| ledger | `carddemo_ledger` | `ledger_service` |
| fraud | `carddemo_fraud` | `fraud_service` |
| notification | `carddemo_notification` | `notification_service` |
| account | `carddemo_account` | `account_service` |
| card | `carddemo_card` | `card_service` |

Flyway creates every schema. Hibernate uses `ddl-auto: validate` and never generates tables.

<br/>

## Events and consumer groups

Seven business topics, six source-specific dead-letter topics, and one shared fallback are created explicitly, fourteen names in all. The six are `transaction.authorized.DLT`, `transaction.declined.DLT`, `account.state-changed.DLT`, `transaction.posted.DLT`, `fraud.assessed.DLT`, and `customer.context-changed.DLT`. `card.updated` needs none: its only consumer routes a spent record to the shared fallback as a governed envelope instead. A topic granted in either deployment path and not created there fails the build, because neither broker creates a topic on demand.

| Topic | Event types | Producers | Consumers and groups |
| :--- | :--- | :--- | :--- |
| `transaction.authorized` | `TransactionAuthorized` | authorization | ledger `ledger-posting`; fraud `fraud-detection`; notification `notification-authorized` |
| `transaction.declined` | `TransactionDeclined` | authorization, the sole writer of a decision | ledger `ledger-reject` |
| `transaction.posted` | `TransactionPosted` | ledger | notification `notification-posted`; account `account-posted` |
| `fraud.assessed` | `FraudFlagged`, `FraudCleared` | fraud | notification `notification-fraud` |
| `account.state-changed` | `AccountStateChanged` | account | authorization `authorization-account-state`; ledger `ledger-account-state` |
| `customer.context-changed` | `CustomerContextChanged` | account | notification `notification-customer` |
| `card.updated` | `CardUpdated` | card | authorization `authorization-card-updated` |
| `<source>.DLT` | 134-character fixed-width abend diagnostic from `app/cpy/CSMSG02Y.cpy` | listener error handlers on ledger, fraud, and notification | operator inspection and replay |
| `carddemo.dead-letter` | `DeadLetterEnvelope` | the listener error handlers on authorization and on account; outbox relays on all five producing services — authorization, ledger, fraud, account, and card — when a row is abandoned; every service when a source topic cannot be resolved | operator inspection and replay |

Every governed event carries `eventId`, `eventType`, `schemaVersion`, `occurredAt`, and an aggregate key. Money travels as a decimal string.

The account identifier is the Kafka key and the ordering unit of every event a producer writes. An authorization whose card resolves none names the account the caller declared, and its reject code 0100 publishes under `transaction-declined-v3` keyed on that account. The refusal is recorded in `unresolved_card_attempt` and `authorization_decision` beside the event, so **one decided call produces exactly one event**. A call that establishes neither a resolvable card nor an account is refused before a decision, and publishes nothing.

Fourteen schema documents cover eight business event types, five released versions above version one, and the dead-letter envelope. Publish and consume paths validate against the registered document. `contracts/released-contracts.json` records every released version with a digest of its wire contract, so no document can be deleted or rewritten in place.

`TransactionPosted` version 2 adds the statement provenance required by notification. Version 1 remains constructible and testable, but notification refuses it as insufficient.

<br/>

## Equivalence

The suite compares Java behavior with documented source rules and checked-in fixtures. It never calls a mainframe.

Run the complete equivalence lifecycle from this directory:

```bash
mvn -B -pl equivalence-tests -am verify
```

`mvn test` does not execute `*EquivalenceTest.java`. Failsafe runs those classes during `verify`.

Add `-o` only once `~/.m2/repository` already holds every dependency this reactor resolves. Offline mode does not download, so a cold or partial local repository fails the build on the first missing artifact rather than fetching it. Run the command above once with the network reachable, then `-o` works for every later run.

The delivered suite includes:

- posting over all 300 daily transaction records;
- decline reasons 0100 through 0103 and the narrowed-precision boundary;
- online bill-payment behavior;
- interest-rate resolution without migrating interest processing;
- account and card validation;
- truncation toward zero;
- fixture census, identifier fidelity, and card seed checks.

The published run reports 229 Failsafe equivalence tests and 506 unit or contract tests, with zero failures. Both figures are measured rather than asserted: run `scripts/check-published-test-counts.sh` after `mvn verify` and it compares every published count against the reports that run wrote. See [Equivalence Results](docs/equivalence-results.md).

Interest is verified but not migrated. `BillingCycleService` reproduces only the two accumulator resets at `app/cbl/CBACT04C.cbl:L353-L354`.

<br/>

## Documentation

| Document | Purpose |
| :--- | :--- |
| [Onboarding](docs/onboarding.md) | Clean-machine setup, domain context, pitfalls, and extension guidance |
| [Suggested Next Tasks](docs/suggested-next-tasks.md) | Follow-up work with locations and verification criteria |
| [Decision Log](docs/decision-log.md) | Alternatives, reasons, accepted risks, and declared deviations |
| [Traceability Matrix](docs/traceability-matrix.md) | Bidirectional source-to-target classification |
| [Business Rule Flags](docs/business-rule-flags.md) | Sixty-six ambiguous, inconsistent, or undocumented source rules, each with its citation and its handling, plus four declared platform departures. Identifiers 1 to 26 are the set the specification fixes; 27 upward are appended in the order they were found, and no identifier is ever reused or renumbered |
| [Architecture, Before and After](docs/architecture-before-after.md) | Paired Mermaid migration views |
| [Event Flow](docs/event-flow.md) | Topics, groups, outboxes, projections, and idempotency |
| [Data Model](docs/data-model.md) | Service-owned tables and copybook-to-column provenance |
| [Equivalence Results](docs/equivalence-results.md) | Fixture-by-fixture parity evidence and declared gaps |
| [Prose Validation](docs/prose-validation.md) | Rule 5 verdicts and per-document scorecards |

Each service also has a local guide linked from [Services and APIs](#services-and-apis). The root `README.md` retains the separate mainframe installation path.

Use [Suggested Next Tasks](docs/suggested-next-tasks.md) for unresolved business decisions. Do not silently correct a flagged source behavior in production code.

<br/>