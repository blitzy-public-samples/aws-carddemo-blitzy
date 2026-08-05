# Onboarding

This guide takes a clean machine to a running and modifiable CardDemo platform. The existing root `README.md` keeps the separate mainframe installation path. `CONTRIBUTING.md` still governs repository contributions and remains unchanged. Design rationale lives in the [decision log](decision-log.md).

## Setup

### Prerequisites

Use the tested versions below. The container images are pinned and never use `latest`.

| Tool | Exact version | Use |
| :--- | :--- | :--- |
| Eclipse Temurin OpenJDK | 25.0.4+7 | Compile and test Java 25 modules |
| Apache Maven | 3.9.16 | Build the nine-module reactor |
| Docker Engine | 29.7.0 | Run the demo stack |
| Docker Compose | 5.3.1 | Start the broker, databases, and six services |
| OpenSSL | 3.x command line | Generate disposable local passwords |

The runtime stack pulls `apache/kafka:4.2.1` and `postgres:18.4`. Kafka runs in Kafka Raft mode, so no ZooKeeper container exists.

Spring Boot 4.1.0 supplies the dependency bill of materials. Most dependencies omit their own version and inherit the managed version.

### Clone to a configured working tree

Run the following commands from the repository root:

```bash
cd card-platform
cp .env.example .env

for variable in POSTGRES_PASSWORD \
  AUTHORIZATION_DB_PASSWORD LEDGER_DB_PASSWORD FRAUD_DB_PASSWORD \
  NOTIFICATION_DB_PASSWORD ACCOUNT_DB_PASSWORD CARD_DB_PASSWORD \
  KAFKA_ADMIN_PASSWORD \
  AUTHORIZATION_KAFKA_PASSWORD LEDGER_KAFKA_PASSWORD FRAUD_KAFKA_PASSWORD \
  NOTIFICATION_KAFKA_PASSWORD ACCOUNT_KAFKA_PASSWORD CARD_KAFKA_PASSWORD; do
  sed -i "s|^${variable}=.*|${variable}=$(openssl rand -base64 24 | tr -d '/+=')|" .env
done
```

Populate Maven’s local dependency cache before generating the encoded hashes:

```bash
mvn -B -DskipTests package
```

The remaining three credentials are encoded password hashes. Choose three local passwords and keep them in the current shell:

```bash
read -rsp "Admin password: " ADMIN_PASSWORD; echo
read -rsp "User password: " USER_PASSWORD; echo
read -rsp "Monitoring password: " MONITORING_PASSWORD; echo

CRYPTO_CP="$(find ~/.m2/repository/org/springframework/security/spring-security-crypto \
  -name 'spring-security-crypto-*.jar' | sort | tail -1):\
$(find ~/.m2/repository/commons-logging/commons-logging \
  -name 'commons-logging-*.jar' | sort | tail -1):\
$(find ~/.m2/repository/org/springframework/spring-core \
  -name 'spring-core-*.jar' | sort | tail -1)"

hash_password() {
  export DEMO_PASSWORD="$1"
  printf 'System.out.println(org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(System.getenv("DEMO_PASSWORD")));\n/exit\n' \
    | jshell --class-path "$CRYPTO_CP" 2>/dev/null \
    | sed -n 's/^jshell> //p' \
    | grep '^{bcrypt}' \
    | head -1
  unset DEMO_PASSWORD
}

sed -i "s|^ADMIN_PASSWORD_HASH=.*|ADMIN_PASSWORD_HASH=$(hash_password "$ADMIN_PASSWORD")|" .env
sed -i "s|^USER_PASSWORD_HASH=.*|USER_PASSWORD_HASH=$(hash_password "$USER_PASSWORD")|" .env
sed -i "s|^MONITORING_PASSWORD_HASH=.*|MONITORING_PASSWORD_HASH=$(hash_password "$MONITORING_PASSWORD")|" .env
```

### Build and start

Run the full verification lifecycle before building images:

```bash
mvn -B clean verify
docker compose up -d --build
docker compose ps
```

`mvn test` does not run the equivalence classes. Surefire excludes `**/*EquivalenceTest.java`; Failsafe runs them during `verify`.

Every service image copies its packaged archive from that module’s `target/` directory. The Maven command must therefore run before `docker compose up --build`.

### Ports and network addresses

| Service | Business port | Management port | Container business port |
| :--- | ---: | ---: | ---: |
| authorization-service | 8081 | 9081 | 8080 |
| ledger-posting-service | 8082 | 9082 | 8080 |
| fraud-detection-service | 8083 | 9083 | 8080 |
| notification-service | 8084 | 9084 | 8080 |
| account-service | 8085 | 9085 | 8080 |
| card-service | 8086 | 9086 | 8080 |

The Compose network exposes Kafka at `kafka:29092`. Host tools use `localhost:9092`.

PostgreSQL listens at `postgres:5432` inside Compose and `localhost:5432` on the host. One container hosts six private databases and schemas:

| Service | Database | Schema |
| :--- | :--- | :--- |
| authorization | `carddemo_authorization` | `authorization_service` |
| ledger | `carddemo_ledger` | `ledger_service` |
| fraud | `carddemo_fraud` | `fraud_service` |
| notification | `carddemo_notification` | `notification_service` |
| account | `carddemo_account` | `account_service` |
| card | `carddemo_card` | `card_service` |

Flyway creates and migrates each schema. Hibernate uses `ddl-auto: validate` and never generates the model.

### Verify the running stack

Health is anonymous on each management port:

```bash
for port in 9081 9082 9083 9084 9085 9086; do
  curl -fsS "http://localhost:${port}/actuator/health"
  echo
done
```

Business routes require HTTP Basic authentication. The administrator username defaults to `admin001`.

Submit `POST /authorizations` with the first card in the checked-in fixture:

```bash
CARD_NUMBER=$(sed -n '1s/^\(.\{16\}\).*/\1/p' ../app/data/ASCII/cardxref.txt)

curl -sS -X POST http://localhost:8081/authorizations \
  -u "admin001:${ADMIN_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -d "{\"cardNumber\":\"${CARD_NUMBER}\",
       \"transactionTypeCode\":\"01\",
       \"transactionCategoryCode\":\"0001\",
       \"source\":\"POS TERM\",
       \"description\":\"Onboarding purchase\",
       \"amount\":\"+00000504.77\",
       \"merchantId\":\"800000000\",
       \"merchantName\":\"Abshire-Lowe\",
       \"merchantCity\":\"North Enoshaven\",
       \"merchantZip\":\"72112\",
       \"originTimestamp\":\"2026-08-04 10:30:00.000000\",
       \"processingTimestamp\":\"2026-08-04-10.30.00.000000\"}"
```

The response returns HTTP 200 for either outcome. `approved` separates an approval from a source-equivalent decline.

Watch the asynchronous path from inside the broker container:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 \
  --command-config /tmp/kafka-admin.properties \
  --from-beginning \
  --include 'transaction.authorized|transaction.posted|fraud.assessed'
```

An approval produces `TransactionAuthorized`. Ledger and fraud consume it independently. Notification then consumes `TransactionPosted` and the fraud assessment without calling either producer.

Structured logs use JSON. Service packages log at the configured application level, while management metrics are available to the monitoring identity on ports 9081 through 9086.

## Domain context

A card authorization asks whether one transaction may proceed. Authorization resolves a card to an account and applies four source rules. An approval emits `TransactionAuthorized`; a decline emits `TransactionDeclined`.

| Code | Source description | Locator |
| :--- | :--- | :--- |
| `0100` | `INVALID CARD NUMBER FOUND` | `app/cbl/CBTRN02C.cbl:L385-L387` |
| `0101` | `ACCOUNT RECORD NOT FOUND` | `app/cbl/CBTRN02C.cbl:L397-L399` |
| `0102` | `OVERLIMIT TRANSACTION` | `app/cbl/CBTRN02C.cbl:L410-L412` |
| `0103` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `app/cbl/CBTRN02C.cbl:L417-L419` |

A decline is expected business traffic. The source sets return code 4 when any input is rejected at `app/cbl/CBTRN02C.cbl:L229-L230`.

Each account has a current balance and two cycle accumulators. The credit rule tests cycle credit minus cycle debit plus the transaction amount at `app/cbl/CBTRN02C.cbl:L403-L407`.

Card-to-customer resolution runs through the cross-reference record. The account copybook contains no customer identifier, so the target creates no account-to-customer foreign key.

The source platform used Customer Information Control System transactions, Job Control Language jobs, and shared Virtual Storage Access Method datasets. See [Architecture, Before and After](architecture-before-after.md) for both states.

Equivalence means reproducing source behavior, including flagged defects. The [business-rule register](business-rule-flags.md) records every open rule with a source locator.

## How the platform is laid out

The aggregator builds nine modules in this order:

| Module | Purpose |
| :--- | :--- |
| `libs/event-contracts` | Event records, envelope, schemas, and validating serialization |
| `libs/cobol-compat` | Fixed-point arithmetic, parsing, date rules, masking, and reference data |
| `services/authorization-service` | Synchronous authorization and account projection consumption |
| `services/ledger-posting-service` | Posting arithmetic and balance projection |
| `services/fraud-detection-service` | Net-new risk assessment |
| `services/notification-service` | Statement read model and alerts |
| `services/account-service` | Account, customer, and cycle-close operations |
| `services/card-service` | Card list, detail, and update |
| `equivalence-tests` | Cross-service contract and source-parity tests |

The two libraries depend on no internal module. Each service depends on both libraries and no other service. The equivalence module depends on all modules for tests.

A service-to-service Java import fails compilation and the Maven enforcer names the forbidden dependency. Runtime state crosses service boundaries through governed events and private projections.

Look under these paths when changing a service:

| Path | Contents |
| :--- | :--- |
| `api/` | Controllers and request or response records |
| `domain/` and `domain/rules/` | Orchestration and rule objects |
| `messaging/` | Consumers, publishers, and dead-letter metadata |
| `outbox/` | Outbox writer and relay |
| `entity/` and `repository/` | Private persistence model |
| `src/main/resources/db/migration/` | Flyway schema and seed migrations |
| `src/main/resources/openapi.yaml` | Hand-written API description |

The platform has no application front-end, schema-registry container, service mesh, cache, or mainframe connector.

## How to extend

### Add an independent consumer

Create a Maven service module and depend on the two shared libraries. Subscribe with a new consumer group, add a `processed_event` table, and commit the marker with the business effect.

No producer changes are required. Fraud detection proves the path because it has no source ancestor and consumes an existing event.

### Add a decline rule

Add a class implementing `DeclineRule` under authorization `domain/rules/`. Preserve source ordering and add the corresponding event-schema and equivalence coverage.

The source marks the seam with `ADD MORE VALIDATIONS HERE` at `app/cbl/CBTRN02C.cbl:L377`.

### Preserve two guarantees

- A new consumer acknowledges only after its business transaction commits.
- A schema change stays additive and passes `SchemaBackwardCompatibilityTest`.

Kafka publication sits behind a publisher port. Other internal layers remain concrete so each service stays readable in a short walkthrough.

## Common pitfalls

### 1. Java defaults silently to release 17

Every module must declare `<java.version>25</java.version>`. Omitting it can compile without a warning at class-file major version 61 instead of Java 25’s major version 69.

### 2. Money truncates toward zero

`ROUNDED` appears zero times across all 28 source programs. Use `CobolDecimal` with `RoundingMode.DOWN`; do not replace it with half-up rounding.

### 3. Processing timestamps have two significant fractional digits

`app/cbl/CBTRN02C.cbl:L173-L174` defines hundredths plus a four-character remainder. Line 701 writes four zeros, so raw comparison with a fresh Java timestamp fails.

Normalize processing timestamps to hundredths and four trailing zeros. The [equivalence results](equivalence-results.md) document the tolerance.

### 4. Fixture widths differ

`app/data/ASCII/cardxref.txt` contains 36-byte records, while `app/cpy/CVACT03Y.cpy` declares 50. The ASCII fixture omits its 14-byte filler.

Use the width-tolerant fixture loader. The EBCDIC twin supplies the full declared width.

### 5. Cycle counters need an explicit reset owner

Authorization reads counters that posting grows. Only `app/cbl/CBACT04C.cbl:L353-L354` resets them, and the full interest program is outside the migrated runtime.

Call `POST /accounts/{accountId}/cycle-close` before the counters make every later authorization decline. The endpoint resets only the two counters and does not calculate interest.

## Where to go next

- [Platform README](../README.md) — repository map and short quickstart
- [Architecture, Before and After](architecture-before-after.md) — full migration views
- [Event Flow](event-flow.md) — every topic, group, and delivery guarantee
- [Data Model](data-model.md) — service-owned tables and source fields
- [Decision Log](decision-log.md) — alternatives, reasons, and accepted risks
- [Traceability Matrix](traceability-matrix.md) — complete forward and backward mapping
- [Business Rule Flags](business-rule-flags.md) — 26 source findings for human review
- [Equivalence Results](equivalence-results.md) — fixture-by-fixture parity evidence
- [Suggested Next Tasks](suggested-next-tasks.md) — work discovered and left outside this engagement

The root `README.md` remains the mainframe installation guide. `CONTRIBUTING.md` continues to define the contribution process.