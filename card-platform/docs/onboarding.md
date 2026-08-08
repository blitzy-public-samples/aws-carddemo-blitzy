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

### Start with one command

From the repository root:

```bash
cd card-platform
scripts/start-demo.sh
```

That is the whole first run. The script packages the reactor, creates `.env` from `.env.example`, fills all nineteen credentials, builds the six images, starts eight containers, and reads `/actuator/health` on each of the six management ports. It prompts for nothing, and re-running it changes no credential that already holds a value.

Three things a clean clone cannot skip, which is why `docker compose up -d --build` alone does not work:

- every `services/*/Dockerfile` copies a packaged archive out of its own module's `target/` directory, and a fresh checkout carries none;
- `docker-compose.yml` reads nineteen credentials that nothing in this repository supplies, because a credential published here would be a credential everyone holds;
- `.env`, where those credentials live, does not exist until it is copied.

The four demo passwords the script generates are written to `card-platform/.demo-credentials` with owner-only permissions, and git ignores that file. `.env` keeps only their `{bcrypt}` hashes, and a hash cannot be sent to a service as a password. To choose the passwords yourself, set `CARDDEMO_ADMIN_PASSWORD`, `CARDDEMO_ACQUIRER_PASSWORD`, `CARDDEMO_USER_PASSWORD` or `CARDDEMO_MONITORING_PASSWORD` before running the script.

To prepare `.env` without starting anything, run `scripts/generate-env.sh` on its own. It reads the credential names out of `.env.example`, so adding a credential there extends it with no edit.

Run it again after every `git pull`. An existing `.env` is reconciled rather than replaced: every assignment `.env.example` declares and your file does not carry is appended with the example's value, any credential among them is generated in the same run, and no value you already have is read or rewritten. Assignments your file carries that the example no longer declares are named in the output and left in place, because the script cannot tell a retired setting from one you chose. Two kinds of value get more than that. An assignment holding a key this repository publishes is replaced with a generated one, because the authorization and card services refuse to start on a published key and no placeholder marks it. An assignment whose value differs from the example's is reported with both values and left as you set it, which is where a start-up refusal after an upgrade usually comes from: a default this platform tightened looks like a working value in your file. The run ends by proving your file declares all 133 assignments, so a variable Compose requires cannot be silently absent — which is the failure that used to stop `docker compose config` on the first command after an upgrade.

The rest of this section is the same work performed by hand. Read it to understand what the script does, or follow it when you want to set a value yourself.

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

The remaining four credentials are encoded password hashes. Choose four local passwords and keep them in the current shell:

```bash
read -rsp "Admin password: " ADMIN_PASSWORD; echo
read -rsp "Acquirer password: " ACQUIRER_PASSWORD; echo
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
sed -i "s|^ACQUIRER_PASSWORD_HASH=.*|ACQUIRER_PASSWORD_HASH=$(hash_password "$ACQUIRER_PASSWORD")|" .env
sed -i "s|^USER_PASSWORD_HASH=.*|USER_PASSWORD_HASH=$(hash_password "$USER_PASSWORD")|" .env
sed -i "s|^MONITORING_PASSWORD_HASH=.*|MONITORING_PASSWORD_HASH=$(hash_password "$MONITORING_PASSWORD")|" .env
```

### The card-token key

`CARD_TOKEN_SECRET` is a `REPLACE` marker and generating it is a required step. A card token is a keyed `HMAC-SHA-256` over the full card number under that secret, prefixed by `CARD_TOKEN_VERSION`, rendered as 64 lower-case hexadecimal characters. The key is what stops a holder of one token recomputing the token of every sixteen-digit card number, so this repository ships none. Only two services read it, the authorization service and the card service, and both refuse to start without it:

```bash
sed -i "s|^CARD_TOKEN_SECRET=.*|CARD_TOKEN_SECRET='$(openssl rand -base64 48 | tr -d '/+=')'|" .env
```

`CARD_TOKEN_VERSION` is not key material and needs no change. It is part of the message the code covers, so raising it rolls every token over under the same key.

Generating the key is all the stack needs. The 50 `card_token` literals in `services/card-service/src/main/resources/db/migration/V2__seed.sql` are derived under the build-scope key in `pom.xml`, and `CardTokenReconciler` re-derives every one of them under your key as the card service starts. What a change of key does not survive is a token stored elsewhere: `statement_transaction` and `notification_log` in the notification service, `authorization_decision` in the authorization service, and any `SCOPE_CARD_` authority already inside `USER_SCOPES`.

`USER_SCOPES` therefore ships no `SCOPE_CARD_` authority, which means `admin001` reaches every card route and `user0001` reaches none. To give the ordinary identity one card, derive its token under your key using the module you have already built:

```bash
COBOL_JAR="$(find libs/cobol-compat/target -name 'cobol-compat-*.jar' | head -1)"
export CARD_NUMBER="$(awk 'substr($0,17,11) == "00000000001" { print substr($0,1,16); exit }' \
  ../app/data/ASCII/carddata.txt)"
CARD_TOKEN_SECRET="$(grep '^CARD_TOKEN_SECRET=' .env | cut -d= -f2- | tr -d "'")" \
CARD_TOKEN_VERSION=1 jshell --class-path "$COBOL_JAR" -s - <<'JSHELL'
System.out.println(com.carddemo.cobol.PanMasker.cardToken(System.getenv("CARD_NUMBER")));
/exit
JSHELL
```

Append `SCOPE_CARD_<that value>` to `USER_SCOPES` in `.env`, then restart. A different key gives a different value, which is the whole point of the key.

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

Business routes require HTTP Basic authentication. The administrator username defaults to `admin001`, and the acquiring workload username to `acquirer1`.

Every state-changing request carries one header beyond the credential: `X-CardDemo-Request`, with any non-blank value. A `POST`, `PUT`, `PATCH` or `DELETE` arriving without it answers 403, and so does one declaring a cross-site `Sec-Fetch-Site` or a foreign `Origin`. The reason is that HTTP Basic is a credential a browser attaches by itself, so a page on any other site could otherwise submit a form against a business route and the browser would authenticate it; an HTML form cannot set a request header, which is what makes one header the control. Reads need nothing, which is why the health loop above carries no header. `API_CROSS_SITE_HEADER` renames it, and the value is never inspected, only its presence.

Request volume is bounded as well. Each service allows 600 requests a minute per source address and per identity, 120 state-changing requests, 20 failed authentications, and 64 requests in flight, answering 429 with `Retry-After` past any of them. `API_RATE_*` raises the ceilings for a load run. Management ports are exempt, so a probe is never throttled.

`POST /authorizations` admits `ACQUIRER` and `ADMIN` alone. The acquirer is the identity a point-of-sale network presents: the route names its card in the request body, so no path variable carries an identifier an ownership scope can be compared against, and it therefore reaches every card the platform holds. `user0001` is a cardholder identity and receives 403 there.

Submit `POST /authorizations` with the first card in the checked-in fixture:

```bash
CARD_NUMBER=$(sed -n '1s/^\(.\{16\}\).*/\1/p' ../app/data/ASCII/cardxref.txt)
CAPTURED_AT="$(date -u +'%Y-%m-%d %H:%M:%S').000000"
PROCESSED_AT="$(date -u +'%Y-%m-%d-%H.%M.%S').000000"

curl -sS -X POST http://localhost:8081/authorizations \
  -u "acquirer1:${ACQUIRER_PASSWORD}" \
  -H 'X-CardDemo-Request: onboarding' \
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
       \"originTimestamp\":\"${CAPTURED_AT}\",
       \"processingTimestamp\":\"${PROCESSED_AT}\"}"
```

The response returns HTTP 200 for an approval and HTTP 422 for a source-equivalent decline. `approved` separates the two outcomes.

Both timestamps accept the ten-character date `YYYY-MM-DD` and the twenty-six character record form. `app/cbl/COTRN02C.cbl:L389-L423` validates each as a ten-character date, and a ten-character value is widened to the record width before it reaches the event. No bound relates either value to the clock of the service: reject reason `0103` at `app/cbl/CBTRN02C.cbl:L414-L420` compares the first ten characters of the capture moment against the account expiry date, and nothing else tests the value. A date the tolerant policy declines answers 422 with `Orig Date - Not a valid date...` or `Proc Date - Not a valid date...`, and a body failing several edits reports the one text the source would have reported first.

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

### Update an account and a customer

`GET /accounts/{accountId}` and `GET /customers/{customerId}` read the pair, and `PUT /accounts/{accountId}` replaces it. The read shape and the write shape differ on purpose, so a body assembled by echoing the two reads is refused, one field at a time, with the text the source edit carries. The account read names no customer, because `app/cpy/CVACT01Y.cpy` holds no customer identifier; in the fixture account `00000000050` pairs with customer `000000050`.

Six differences separate a read from a write, and each answers 422 with one text:

| The read returns | The write requires | Text when the read value is submitted |
| :--- | :--- | :--- |
| `"openDate": "2011-04-22"` | eight digits, `20110422` | `Open Date: Month must be a number between 1 and 12.` |
| `"expirationDate": "2099-12-31"` | `20991231` | `Expiry Date: Month must be a number between 1 and 12.` |
| `"reissueDate": "2023-03-09"` | `20230309` | `Reissue Date: Month must be a number between 1 and 12.` |
| `"dateOfBirth": "1960-12-01"` | `19601201` | `Date of Birth: Month must be a number between 1 and 12.` |
| `"ficoCreditScore": 623` | a value from 300 through 850 | `FICO Score: should be between 300 and 850`, which refuses the 21 seeded rows below 300 |
| no Social Security Number in either read | all three parts together | `SSN: First 3 chars must be supplied.` |

Space padding is not one of the differences. Every edit reads its field at the width the copybook declares, so a name padded to 25 characters and a postal code padded to 10 both pass.

A block that is present has to be complete: nine components of `accountData`, ten of `customerData`, and the three Social Security parts together. A component no edit requires may be omitted and keeps its stored value, but that stored value still reaches the edit, so an omitted telephone number whose area code the reference table does not list is refused exactly as a submitted one would be.

The checked-in fixture is read data and it fails the write edits by design: those edits ran on 3270 screen input and never on stored records. All 50 seeded customers fail at least one, and only two carry a state whose postal prefix is among the 240 combinations `app/cpy/CSLKPCDY.cpy` lists. Account `00000000050` needs the fewest corrections, and this body is accepted:

```bash
curl -sS -X PUT http://localhost:8085/accounts/00000000050 \
  -u "admin001:${ADMIN_PASSWORD}" \
  -H 'X-CardDemo-Request: onboarding' \
  -H 'Content-Type: application/json' \
  -d '{
    "accountData": {
      "activeStatus": "Y",
      "currentBalance": "492.00",
      "creditLimit": "6169.00",
      "cashCreditLimit": "4587.00",
      "openDate": "20110422",
      "expirationDate": "20991231",
      "reissueDate": "20230309",
      "currentCycleCredit": "0.00",
      "currentCycleDebit": "0.00",
      "groupId": ""
    },
    "customerData": {
      "customerId": "000000050",
      "firstName": "Aniya",
      "middleName": "Alba",
      "lastName": "Von",
      "addressLine1": "1588 Nienow Cape",
      "addressLine2": "Suite 187",
      "addressCity": "New Aricchester",
      "addressStateCode": "OR",
      "addressCountryCode": "USA",
      "addressZip": "97201",
      "phoneNumber1": "(325)301-0827",
      "phoneNumber2": "(503)985-9283",
      "socialSecurityPart1": "111",
      "socialSecurityPart2": "11",
      "socialSecurityPart3": "1111",
      "governmentIssuedId": "SPECIMEN-0000000001",
      "dateOfBirth": "19601201",
      "eftAccountId": "0074883577",
      "primaryCardHolderIndicator": "Y",
      "ficoCreditScore": "623"
    }
  }'
```

Four values in it are not the seeded ones. `addressZip` is `97201` rather than `04257`, because `OR97` is the only Oregon prefix in that list, and the seeded pair answers `Invalid zip code for state`. `phoneNumber2` carries area code `503` rather than the seeded `493`, which answers `Phone Number 2: Not valid North America general purpose area code`. The Social Security Number is a specimen, because no read returns the stored one. `expirationDate` is the value the read returned rather than the fixture's, for the reason [pitfall 10](#10-resubmitting-the-fixture-expiry-declines-the-account-you-just-updated) gives.

A write answers 200 with `Changes committed to database` and produces one event per record it changed: `AccountStateChanged` for the account row and `CustomerContextChanged` for the customer row. Run the same body twice and the second call answers 200 with `No change detected with respect to values fetched.` and produces neither. `services/account-service/src/main/resources/openapi.yaml` carries this body as a request example beside a customer-only variant.

One more consequence of replacing the whole record: `currentBalance` and the two cycle counters are components of the request, so a body carrying the figures the caller was shown writes those figures back, and a transaction posted between the read and the write is overwritten. Read the account again before updating it, or send `customerData` alone and leave every account column as stored.

### Run it on a local Kubernetes cluster

`deploy/k8s/` carries the same six services as Deployments, with Kafka, PostgreSQL, a ConfigMap and a Secret template. One step has to happen before `kubectl apply`, and skipping it is the most common way this deployment fails.

Each service Deployment names `carddemo/<service>:1.0.0-SNAPSHOT` with `imagePullPolicy: Never`. Nothing publishes those images to a registry, so the kubelet never looks for one and the image has to already be in the cluster node's image store. A cluster that has not been given them reports `ErrImageNeverPull`, which names no cause. Build the images and put them in the cluster with:

```bash
cd card-platform
deploy/k8s/load-images.sh              # runtime read from the current kubectl context
deploy/k8s/load-images.sh kind         # or name it: kind, minikube, docker-desktop
```

The script packages the reactor if an archive is missing, builds all six images, and then loads them the way the runtime requires: `kind load docker-image` for kind, `minikube image load` for minikube, and nothing at all for Docker Desktop, whose cluster shares this machine's Docker daemon. It then reads the node's image list back and fails if no `carddemo` image arrived. `KIND_CLUSTER_NAME` and `MINIKUBE_PROFILE` select a cluster other than the default; `IMAGE_TAG` overrides the tag, which is otherwise the version in `pom.xml`.

Apply the manifests in the order `deploy/k8s/00-namespace.yaml` documents, which the script prints when it finishes: the namespace, then a filled-in copy of `31-secret.example.yaml` kept **outside** that folder, then the remaining files in filename order with the template excluded. Applying the whole folder would overwrite the Secrets with the placeholders the template publishes, and every workload would refuse to start.

After a rebuild, the images change but the Pods do not. Restart them:

```bash
kubectl -n carddemo rollout restart deployment
```

The Compose stack and the cluster are alternatives rather than layers. The transport differs between them, and [the platform README](../README.md) carries the comparison: Compose serves HTTP on loopback with `SASL_PLAINTEXT`, and the manifests serve HTTPS with `SASL_SSL` and mounted key material.

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
| `services/ledger-posting-service` | Posting arithmetic, plus a balance projection the account service's own changes refresh |
| `services/fraud-detection-service` | Net-new risk assessment |
| `services/notification-service` | Statement read model and alerts, over four independent listeners |
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

Three declarations sit outside Java and are easy to forget. Name the topic and the group in the service's `application.yml`. Add a `grant_consumer` line for the new principal, topic, and group in the `create_acls` function of `docker-compose.yml`, and the matching entry in `deploy/k8s/10-kafka.yaml`; without them the broker refuses the subscription and the service starts but never receives an event. Add the `<topic>.DLT` name to `create_topics` if the consumer routes spent records to a source-specific dead-letter topic rather than the shared fallback.

No producer changes are required. Fraud detection proves the path because it has no source ancestor and consumes an existing event. Notification proves it a second time: it was added as the third independent reader of `transaction.authorized` under `notification-authorized`, and neither the authorization producer nor the other two consumers changed.

### Add a decline rule

Add a class implementing `DeclineRule` under authorization `domain/rules/`. Preserve source ordering and add the corresponding event-schema and equivalence coverage.

The source marks the seam with `ADD MORE VALIDATIONS HERE` at `app/cbl/CBTRN02C.cbl:L377`.

### Preserve two guarantees

- A new consumer acknowledges only after its business transaction commits.
- A schema change stays additive and passes `SchemaBackwardCompatibilityTest`.

Kafka publication sits behind a publisher port. Other internal layers remain concrete so each service stays readable in a short walkthrough.

## Common pitfalls

### 1. Java defaults silently to release 17

`<maven.compiler.release>25</maven.compiler.release>` is the property this build compiles from, and every module descriptor declares it beside `<java.version>25</java.version>`. A module that lowers or drops it compiles without a warning at a lower class-file version — major version 61 for release 17 — while the build still succeeds. Two guards catch that: the aggregator's enforcer requires the property to resolve to 25 in every module, and the compile stage of `.github/workflows/ci.yml` reads the release of every class file the reactor wrote and fails on any value other than Java 25's major version 69.

`<java.version>` alone changes nothing here. No plugin resolves it, because `card-platform/pom.xml` imports the Spring Boot bill of materials rather than inheriting the Spring Boot parent.

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

Three services hold part of one `ACCTDAT` record, and the counters move along a chain rather than in one place: the ledger posts and publishes `TransactionPosted`, the account service adds the amount to its own copy and publishes `AccountStateChanged`, and authorization writes that into the snapshot reason code 102 reads. Every link is asynchronous, so a second authorization issued within a few hundred milliseconds of the first can still read the older snapshot. Space repeated calls by a second or two when demonstrating the limit, or read `carddemo.account.posting.applied` on the account service's metrics endpoint to see the amount land before issuing the next call.

### 6. Rotating the card-token key invalidates checked-in values

A card token is a keyed hash, so it is a function of `CARD_TOKEN_SECRET` and `CARD_TOKEN_VERSION` as much as of the card number. The 50 seeded `card_token` literals look after themselves: `CardTokenReconciler` re-derives them under the configured key each time the card service starts. Two things do not. Any `SCOPE_CARD_` authority in `USER_SCOPES` names a token derived under the previous key, and so does every token a read model already stored. The failure is quiet on the authority: a card detail request simply answers 403 for a card the caller does own. [The card-token key](#the-card-token-key) gives the command that derives a token under a candidate key.

### 7. The first start needs the network, even though the build does not

`mvn -o … compile` works offline once `~/.m2` is warm, so the build has no undocumented network dependency. Starting the stack does. `docker-compose.yml` pins `postgres:18.4` and `apache/kafka:4.2.1` to a digest as well as a tag, and a digest is what Docker resolves. A machine holding only the `18.4` **tag** — a tag its registry may have moved since — still pulls, so the first `docker compose up` on a fresh host needs a reachable registry. Pull both images once and every later start is local:

```bash
docker compose pull postgres kafka
```

The six service images are never pulled. Each is built locally as `carddemo/<service>:1.0.0-SNAPSHOT`, the Maven project version, which is the same tag `deploy/k8s` names with `imagePullPolicy: Never` and the same tag the container stage of `.github/workflows/ci.yml` builds. That tag is mutable, and `deploy/k8s/README.md` says so and gives the procedure for pinning the six by digest instead. `Never` is why a pull can never substitute other bytes for them on a cluster: this project publishes no image, so `carddemo/<service>` is a registry name it does not own.

### 8. A replica needs an event before it holds a row

Authorization's credit snapshot, the ledger's balance projection, notification's cardholder context, and card's cross-reference copy are all replicas of data another service owns. Each is seeded from a repository fixture so the first request is correct, and each is then refreshed only when its owner publishes a change. An account created after deployment therefore has no replica row until its first `AccountStateChanged` arrives, and a consumer that cannot find a required row fails and retries rather than inventing a blank one. Do not read a missing replica row as a decision: the ledger deliberately does not decline a transaction whose projection row is absent, because that would reverse an approval another service already made.

### 9. A contended write gives up after three seconds instead of waiting

The account and card updates read the row they rewrite under a lock, and PostgreSQL waits for a held row indefinitely. `carddemo.write.lock-wait-ms` bounds that wait, reading `WRITE_LOCK_WAIT_MS` and defaulting to three seconds. Hold a row in `psql` with `BEGIN; SELECT ... FOR UPDATE;` and the next update of that row answers 409 rather than blocking, which is deliberate and not a defect: the refusal is the outcome the source composes for a read that does not come back held, and it was unreachable while the wait had no end. The bound is applied per update transaction with `set_config('lock_timeout', ?, true)`, so it never bounds a schema migration or the outbox relay sweep. Ordinary concurrent writes are unaffected — they settle in milliseconds and still answer `Record changed by some one else. Please review` — so if you meet a 409 lock refusal in a demonstration, something is genuinely holding the row.

### 10. Resubmitting the fixture expiry declines the account you just updated

Both deployment paths are demo profiles, so both apply `classpath:db/demo` in the account service and in the authorization service, which extends all 50 account expiries to 2099-12-31 so a live request is not declined by reason code 103 before anything else happens. Compose reads `AUTHORIZATION_FLYWAY_LOCATIONS` and `ACCOUNT_FLYWAY_LOCATIONS` from `.env`; Kubernetes reads the two keys of the same name from `deploy/k8s/30-configmap.yaml`. Setting both to `classpath:db/migration` is the base-profile opt-out on either path. `GET /accounts/{accountId}` therefore returns `2099-12-31`, while `app/data/ASCII/acctdata.txt` and the request example in `services/account-service/src/main/resources/openapi.yaml` both carry the fixture value `20230309`.

Submit that fixture value and the extension is gone. The account service writes it, publishes `AccountStateChanged` carrying it, the authorization service applies it to the credit snapshot reason code 103 reads, and every later authorization on that account answers 422 with `0103 TRANSACTION RECEIVED AFTER ACCT EXPIRATION`. Nothing failed: one write moved the expiry into the past and the rule read what the write left.

Send back the expiry the read returned, in the eight-character write form — `20991231` on a demo stack, and the fixture value on a run whose output is compared against the fixture. A second write is the repair: resubmit with `"expirationDate": "20991231"` and the next authorization is approved again.

`ACCOUNT_FLYWAY_LOCATIONS` and `AUTHORIZATION_FLYWAY_LOCATIONS` carry the overlay together or not at all, which is why the two copies of that expiry agree until a request changes one of them. [Update an account and a customer](#update-an-account-and-a-customer) gives a body that keeps them in step.

### 11. A local Kubernetes cluster cannot pull the six service images

The six Deployments carry `imagePullPolicy: Never`, and no registry holds `carddemo/<service>:1.0.0-SNAPSHOT`. A cluster that has never been given those images reports `ErrImageNeverPull` on every service Pod, and that status names no cause: the images exist on the machine, and the cluster simply cannot see them. Compose does not have this problem because it builds the images into the same daemon it runs them from.

Run `deploy/k8s/load-images.sh` before the first `kubectl apply`, and again after any rebuild followed by `kubectl -n carddemo rollout restart deployment`. [Run it on a local Kubernetes cluster](#run-it-on-a-local-kubernetes-cluster) gives the whole sequence.

### 12. A state-changing call without `X-CardDemo-Request` answers 403

Every service refuses a `POST`, `PUT`, `PATCH` or `DELETE` that does not carry the header, that declares a `Sec-Fetch-Site` other than `same-origin` or `same-site`, or that names an `Origin` other than the service it reached. The refusal is 403 with a fixed problem document naming no route, which reads exactly like a role refusal — so a command copied from an older note fails in a way that looks like a permissions problem and is not. Add `-H 'X-CardDemo-Request: 1'` and it works. Reads are unaffected.

A deployment terminating Transport Layer Security at a proxy has to set `SERVER_FORWARD_HEADERS_STRATEGY=framework`, or each service compares `Origin` against the address the proxy dialled rather than the one the browser used, and bounds the proxy's address rather than the caller's. Both filters count inside one process, so several replicas bound each replica rather than the service as a whole; [suggested next tasks](suggested-next-tasks.md) records the shared-store ceiling a deployment beyond a demo wants.

## Where to go next

- [Platform README](../README.md) — repository map and short quickstart
- [Architecture, Before and After](architecture-before-after.md) — full migration views
- [Event Flow](event-flow.md) — every topic, group, and delivery guarantee
- [Data Model](data-model.md) — service-owned tables and source fields
- [Decision Log](decision-log.md) — alternatives, reasons, and accepted risks
- [Business Rule Flags](business-rule-flags.md) — every open source rule with its locator
- [Suggested Next Tasks](suggested-next-tasks.md) — the follow-up work, with verification criteria
- [Equivalence Results](equivalence-results.md) — fixture-by-fixture parity evidence
- [Traceability Matrix](traceability-matrix.md) — complete forward and backward mapping
- [Business Rule Flags](business-rule-flags.md) — 26 source findings for human review
- [Equivalence Results](equivalence-results.md) — fixture-by-fixture parity evidence
- [Suggested Next Tasks](suggested-next-tasks.md) — work discovered and left outside this engagement

The root `README.md` remains the mainframe installation guide. `CONTRIBUTING.md` continues to define the contribution process.