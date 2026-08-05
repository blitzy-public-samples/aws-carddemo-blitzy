# CardDemo — Modernized Stack (Java 21 / Spring Boot 4.1.0 + React 19)

> Build, run, and operations guide for the **modernized** CardDemo application.
>
> This document describes the cloud-native target stack only. The original
> IBM z/OS mainframe application (COBOL, CICS, VSAM, JCL, RACF) is still present
> in [`app/`](./app) and its install/operation guide is preserved **unchanged**
> in the legacy [`README.md`](./README.md). This file lives *alongside* that
> legacy README; it does not replace it.

CardDemo is a credit-card management reference application. This modernized
edition re-platforms the mainframe original to a cloud-native Java and React
stack **while preserving exact functional behavior** — identical financial
calculations, validation rules, referential integrity, transaction identities,
and user workflows. Every legacy COBOL program, copybook, BMS mapset, and JCL
job stream maps to an idiomatic Java or TypeScript construct; the legacy sources
are retained in-place as the transformation reference and traceability anchor.

The rationale behind every non-trivial migration decision is recorded in the
[decision log](./docs/decision-log.md), and the bidirectional source-to-target
mapping between legacy COBOL constructs and their Java/React targets is recorded
in the [traceability matrix](./docs/traceability-matrix.md). This README intentionally
does **not** duplicate that rationale inline.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Technology Stack](#technology-stack)
- [Repository Layout](#repository-layout)
- [Service Inventory](#service-inventory)
- [Frontend Pages](#frontend-pages)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Run (Standalone)](#run-standalone)
- [Data and Database Migrations](#data-and-database-migrations)
- [Observability](#observability)
- [Testing](#testing)
- [Performance and Non-Functional Verification](#performance-and-non-functional-verification)
- [Documentation](#documentation)
- [Legacy Mainframe Reference](#legacy-mainframe-reference)
- [License](#license)

---

## Architecture Overview

The target is a **multi-module Maven project** of Spring Boot 4.1.0
microservices fronted by a Spring Cloud Gateway, backed by a shared
`carddemo-common` library, and presented by a React 19 single-page application
(SPA). The whole system runs standalone via Docker Compose and is deployable to
Kubernetes.

- **Presentation** — a React 19 SPA (`frontend/`) with one page per legacy BMS
  3270 mapset (17 screens). Field labels, tab order, password masking,
  validation messages, and PF-key actions are preserved; PF-keys become buttons
  plus key handlers (ENTER submits, PF3 exits, PF7 pages back, PF8 pages
  forward).
- **API Gateway** — `api-gateway` (Spring Cloud Gateway) performs menu
  navigation, role-gated entry, and request routing to the downstream services,
  replacing the CICS menu programs.
- **Microservices** — nine bounded Spring Boot services (`auth-service`,
  `user-service`, `account-service`, `card-service`, `transaction-service`,
  `billpay-service`, `reporting-service`, `batch-service`, plus the
  `api-gateway`) each own their COBOL programs and PostgreSQL tables. Business
  logic lives in `@Service` classes; CICS transactions become `@RestController`
  endpoints; multi-file updates become single `@Transactional` units.
- **Shared library** — `carddemo-common` holds the JPA entities, DTOs,
  `DateUtil`, message/lookup/title constants, observability configuration, and
  exception handling shared by every service.
- **Data store** — PostgreSQL 18 accessed through Spring Data JPA, with schema
  and seed data applied by Flyway. VSAM KSDS primary keys become relational
  primary keys, alternate indexes become secondary indexes, application-enforced
  integrity becomes declarative foreign-key constraints, and COBOL `COMP-3`
  packed-decimal fields become `BigDecimal` on `NUMERIC(p,s)` columns.
- **Session store** — Redis 8 holds externalized server-side session state via
  Spring Session, replacing the CICS pseudo-conversational COMMAREA.
- **Security** — Spring Security replaces RACF/USRSEC: a `SecurityFilterChain`,
  a BCrypt password encoder, and `ROLE_ADMIN` / `ROLE_USER` authorities.
- **Batch** — Spring Batch jobs, steps, and tasklets replace the JCL job
  streams, preserving processing order and reject handling.
- **Observability** — Spring Boot Actuator, Micrometer, Prometheus, and Grafana
  provide health/readiness/liveness probes, metrics, distributed tracing, and
  dashboards.

### Component Diagram (text form)

```text
                    ┌─────────────────────────────┐
                    │   React 19 SPA (frontend)   │
                    │   http://localhost:3000     │
                    └──────────────┬──────────────┘
                                   │ REST / JSON (+ session cookie / JWT)
                                   ▼
                    ┌─────────────────────────────┐
                    │  api-gateway (Spring Cloud  │
                    │  Gateway) http://localhost   │
                    │  :8080  — routing + roles    │
                    └──────────────┬──────────────┘
                                   │ routed REST calls
        ┌──────────────┬──────────┼───────────┬──────────────┐
        ▼              ▼          ▼           ▼              ▼
 ┌────────────┐ ┌────────────┐ ┌──────────┐ ┌────────────┐ ┌────────────┐
 │auth-service│ │user-service│ │account-  │ │card-service│ │transaction-│
 │            │ │            │ │service   │ │            │ │service     │
 └────────────┘ └────────────┘ └──────────┘ └────────────┘ └────────────┘
 ┌────────────┐ ┌────────────┐ ┌──────────┐
 │billpay-    │ │reporting-  │ │batch-    │      (all depend on
 │service     │ │service     │ │service   │       carddemo-common)
 └────────────┘ └────────────┘ └──────────┘
        │              │            │
        └──────────────┴────────────┴──────────────┬───────────────┐
                                                    ▼               ▼
                                        ┌────────────────────┐ ┌──────────┐
                                        │ PostgreSQL 18       │ │ Redis 8  │
                                        │ (Spring Data JPA +  │ │ (Spring  │
                                        │  Flyway migrations) │ │ Session) │
                                        └────────────────────┘ └──────────┘

 Observability: every service exposes /actuator/health, /actuator/prometheus
   → scraped by Prometheus (http://localhost:9090)
   → visualized in Grafana (http://localhost:3001)
```

---

## Technology Stack

All versions below are pinned to the values verified for this migration. The
Spring ecosystem library versions are managed transitively by the Spring Boot
parent BOM (only the anchor versions are pinned explicitly).

| Layer | Technology | Version |
| :---- | :--------- | :------ |
| Language / Runtime | Java (Eclipse Temurin) | 21 (LTS) |
| Backend build | Apache Maven | 3.9.16 |
| Application framework | Spring Boot (parent BOM) | 4.1.0 |
| Core framework | Spring Framework | 7.0.8 |
| API gateway | Spring Cloud (Oakwood, gateway only) | 2025.1.2 |
| Persistence / DB | PostgreSQL | 18 |
| Session / cache | Redis | 8 |
| Schema migrations | Flyway | 12.x |
| Frontend runtime | React / React DOM | 19.2.7 |
| Frontend build runtime | Node.js | 24 |
| Frontend bundler / language | Vite / TypeScript | Vite 8.x / TypeScript 5.x |
| Metrics / tracing | Micrometer + OpenTelemetry bridge | BOM-managed |

Spring ecosystem starters used across the services (all versions BOM-managed by
Spring Boot 4.1.0): `spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
`spring-boot-starter-security`, `spring-boot-starter-batch`,
`spring-boot-starter-validation`, `spring-boot-starter-actuator`,
`spring-boot-starter-data-redis` + `spring-session-data-redis`,
`spring-cloud-starter-gateway` (gateway only), `org.postgresql:postgresql`,
`org.flywaydb:flyway-core` + `flyway-database-postgresql`,
`io.micrometer:micrometer-registry-prometheus`,
`io.micrometer:micrometer-tracing-bridge-otel`, `spring-boot-starter-test`, and
`org.testcontainers:junit-jupiter` + `:postgresql`.

### Container Base Images

| Purpose | Base image |
| :------ | :--------- |
| Service runtime (each Spring Boot service) | `eclipse-temurin:21-jre` |
| Frontend build | `node:24` |
| Database | `postgres:18` |
| Session / cache store | `redis:8` |

---

## Repository Layout

The modernized stack is added in-place alongside the retained legacy `app/`
sources. The parent `pom.xml` aggregates ten Maven modules.

```text
carddemo/                        (repository root — legacy app/ retained)
├── pom.xml                      Maven aggregator / parent BOM (Spring Boot 4.1.0)
├── .gitignore                   Java + Node ignore patterns
├── docker-compose.yml           PostgreSQL, Redis, services, frontend, Prometheus, Grafana
├── README.md                    Legacy mainframe guide (RETAINED UNCHANGED)
├── README-target.md             This document
├── docs/
│   ├── decision-log.md          Non-trivial decisions with rationale
│   └── traceability-matrix.md   Bidirectional COBOL <-> Java construct mapping
├── k8s/                         Kubernetes manifests (deployments, services, config)
├── observability/
│   ├── grafana-dashboard.json   Grafana dashboard template
│   └── prometheus.yml           Prometheus scrape configuration
├── carddemo-common/             Shared entities, DTOs, DateUtil, constants, config
├── auth-service/                Sign-on / authentication
├── user-service/                Administrator-only user CRUD
├── account-service/             Account view + optimistic-lock update
├── card-service/                Card list / detail / update / cross-reference
├── transaction-service/         Online transaction inquiry/add + batch posting
├── billpay-service/             Bill payment
├── reporting-service/           Report requests + batch statement generation
├── batch-service/               Interest calculation + data-management batch jobs
├── api-gateway/                 Menu navigation, routing, role-gated entry
├── frontend/                    React 19 SPA (17 pages)
└── app/                         Legacy COBOL / CICS / VSAM / JCL / BMS (reference)
```

Each service module follows the package layout
`com.carddemo.<service>.{controller,service,repository,mapper,config,batch}`,
with shared code under `com.carddemo.common.*`. Every service module carries its
own `src/main/resources/application.yml`, `logback-spring.xml`, `src/test/java`
suite, and `Dockerfile`. The Flyway `db/migration/` scripts are **not** per-service:
they live once in `carddemo-common`, are carried by every service, and are applied
first-writer-wins (see
[Data and Database Migrations](#data-and-database-migrations)).

---

## Service Inventory

Each service owns the legacy COBOL programs listed and the corresponding
PostgreSQL tables. Transaction identifiers (CICS tran-ids) and program names are
preserved verbatim from the legacy application.

| Service | Legacy program(s) | Responsibility | Key REST route(s) / CICS tran-id |
| :------ | :---------------- | :------------- | :------------------------------- |
| `auth-service` | `COSGN00C` | Sign-on, authentication, credential verification | `POST /auth/signon` — `CC00` |
| `api-gateway` | `COMEN01C`, `COADM01C` | Menu navigation, routing, role-gated entry | Main menu `CM00`, Admin menu `CA00` |
| `user-service` | `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C` | Administrator-only user CRUD (list / add / update / delete) | `CU00`, `CU01`, `CU02`, `CU03` |
| `account-service` | `COACTVWC`, `COACTUPC` | Account view and update with optimistic locking | `GET /accounts/{id}` — `CAVW`; `PUT /accounts/{id}` — `CAUP` |
| `card-service` | `COCRDLIC`, `COCRDSLC`, `COCRDUPC` | Card list (7 rows/page), detail, update, and cross-reference | `CCLI`, `CCDL`, `CCUP` |
| `transaction-service` | `COTRN00C`, `COTRN01C`, `COTRN02C`, `CBTRN01C`, `CBTRN02C`, `CBTRN03C` | Online transaction list/view/add and batch posting | `CT00`, `CT01`, `CT02` |
| `billpay-service` | `COBIL00C` | Bill payment against available credit (limit − balance) | `POST /billpay` — `CB00` |
| `reporting-service` | `CORPT00C`, `CBSTM03A`, `CBSTM03B` | Report requests and batch statement generation (text + HTML) | `POST /reports` — `CR00` |
| `batch-service` | `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBACT04C`, `CBCUS01C` | Interest calculation and data-management batch jobs | Spring Batch jobs (see [Data and Database Migrations](#data-and-database-migrations)) |
| `carddemo-common` | copybooks (`app/cpy/`), `CSUTLDTC` | Shared JPA entities, DTOs, `DateUtil`, message/lookup/title constants, observability config, exception handling | Library (no endpoints) |
| `frontend` | mapsets (`app/bms/`) | React 19 SPA — 17 screens, one per BMS mapset | Served at `http://localhost:3000` |

---

## Frontend Pages

The React SPA provides one page component per legacy BMS 3270 mapset (17
screens). Field labels, tab-order semantics, password masking, validation
messages, and PF-key actions are preserved.

| React page | Source mapset | Notes |
| :--------- | :------------ | :---- |
| `SignonPage` | `COSGN00` | Password field masked |
| `MainMenuPage` | `COMEN01` | Role-gated menu options |
| `AdminMenuPage` | `COADM01` | Administrator-only |
| `AccountViewPage` | `COACTVW` | Read-only account detail |
| `AccountUpdatePage` | `COACTUP` | Optimistic-lock conflict banner |
| `CardListPage` | `COCRDLI` | 7 rows per page pagination |
| `CardDetailPage` | `COCRDSL` | Card detail |
| `CardUpdatePage` | `COCRDUP` | Card update |
| `TranListPage` | `COTRN00` | Transaction list |
| `TranViewPage` | `COTRN01` | Transaction detail |
| `TranAddPage` | `COTRN02` | Add transaction |
| `BillPayPage` | `COBIL00` | Bill payment |
| `ReportPage` | `CORPT00` | Report request |
| `UserListPage` | `COUSR00` | User list (admin) |
| `UserAddPage` | `COUSR01` | Add user |
| `UserUpdatePage` | `COUSR02` | Update user |
| `UserDeletePage` | `COUSR03` | Delete user |

---

## Prerequisites

To build and run the modernized stack you need:

- **Java 21** (Eclipse Temurin JDK) on the `PATH` (`java -version` reports 21).
- **Apache Maven 3.9.16** (`mvn -version` reports 3.9.16 running on Java 21).
- **Node.js 24** with npm (only required to build or develop the frontend
  outside Docker).
- **Docker** with the Compose plugin (`docker compose`) — the simplest way to
  run the full stack, including PostgreSQL 18 and Redis 8.

When running everything through Docker Compose, only Docker itself is required
on the host; the correct JDK and Node images are used inside the containers.

---

## Build

### Backend (all Maven modules)

The parent `pom.xml` aggregates all ten modules. From the repository root:

```bash
mvn clean install
```

This compiles `carddemo-common` first, then the nine service modules, running
each module's unit tests and packaging the Spring Boot executable JARs. A
Java 21 toolchain is required (`maven.compiler.release` is `21`).

To build without running tests (for a faster local iteration):

```bash
mvn clean install -DskipTests
```

To build a single service and the shared library it depends on, for example:

```bash
mvn -pl account-service -am clean install
```

### Frontend (React SPA)

From the repository root, build the SPA with Node.js 24:

```bash
cd frontend
npm ci            # clean, reproducible install from package-lock.json
npm run build     # type-checks (tsc --noEmit) then bundles with Vite
```

For local development with hot-module reloading, start the Vite dev server:

```bash
cd frontend
npm run dev       # Vite dev server
```

---

## Run (Standalone)

### Preferred: Docker Compose

The Compose stack reads every credential from a local `.env` file (never
committed — it is listed in `.gitignore`). Create it from the template and set a
strong, unique value for each secret first; Compose uses `${VAR:?}` references
and therefore fails fast if any value is unset:

```bash
cp .env.example .env
# then edit .env and set: POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD,
# REDIS_PASSWORD, GRAFANA_ADMIN_USER / GRAFANA_ADMIN_PASSWORD, MONITORING_PASSWORD
```

Eight of the nine backend images start from `eclipse-temurin:21-jre` and copy the
executable JAR produced by the Maven reactor, so **the backend build must run
first** — a missing or stale `target/<service>.jar` yields a failed or stale image
(`reporting-service` is the exception: it runs the Maven build inside a
`maven:3.9.16-eclipse-temurin-21` stage):

```bash
mvn -B clean install -DskipTests     # produces the JARs the images copy
```

Then, from the repository root, build and start the stack — PostgreSQL, Redis,
the nine backend services, an OTLP trace collector, Prometheus, and Grafana:

```bash
docker compose up -d --build
```

The `frontend` service sits behind the `frontend` Compose profile, so the command
above starts the datastores, the nine backend services and the observability stack.
Add the profile to build and start the React SPA alongside them:

```bash
docker compose --profile frontend up -d --build frontend
```

Container health checks use a curl-free probe (bash `/dev/tcp` against
`/actuator/health`) because the `eclipse-temurin:21-jre` base image ships neither
`curl` nor `wget`. The frontend image is multi-stage too (`node:24` build stage →
`nginx:alpine` runtime).

On first start, `batch-service` — the designated first starter — applies the schema
and seed migrations against the PostgreSQL container automatically, and every other
service waits for it to report healthy before validating its own mappings. Every
service carries the same migration set, so the schema is provisioned by whichever
starts first and the later starters find nothing left to apply. To stop and remove
the stack:

```bash
docker compose down
```

Add `-v` to also drop the PostgreSQL and Redis volumes for a clean slate:

```bash
docker compose down -v
```

The frontend's nginx resolves `api-gateway` once when it starts, so after recreating
the gateway on its own (`docker compose up -d --force-recreate api-gateway`) recreate
the frontend as well, otherwise its `/api/` proxy keeps addressing the gateway's
previous container:

```bash
docker compose up -d --force-recreate frontend
```

### Reachable URLs

| Component | URL |
| :-------- | :-- |
| API gateway | <http://localhost:8080> |
| Prometheus | <http://localhost:9090> (bound to loopback only) |
| Grafana | <http://localhost:3001> (bound to loopback only) |
| Jaeger (OTLP trace collector UI) | <http://localhost:16686> (bound to loopback only) |
| Frontend (React SPA) | <http://localhost:3000> — with `--profile frontend` |

Only the API gateway (`8080`) — plus the frontend (`3000`) when its profile is
enabled — publishes a host port for application traffic. The
individual backend services and the datastores (PostgreSQL, Redis) are **not**
exposed on the host — they are reachable only on the private Compose network and,
for the browser, exclusively through the gateway (the SPA calls the same-origin
`/api` path, which nginx reverse-proxies to the gateway). Each service's
`/actuator/health` and `/actuator/prometheus` endpoints are scraped by Prometheus
over that private network, not via a host port.

### Default Login Credentials

Ten users are seeded by Flyway (`carddemo-common`
`V3__seed_test_data.sql`) from the legacy security data, as `{bcrypt}` hashes. The
credentials match the legacy application:

| User ID | Password | `SEC-USR-TYPE` | Role | Post-login menu |
| :------ | :------- | :------------- | :--- | :-------------- |
| `ADMIN001` … `ADMIN005` | `PASSWORD` | `A` | `ROLE_ADMIN` | Admin menu — `CA00` |
| `USER0001` … `USER0005` | `PASSWORD` | `U` | `ROLE_USER` | Main menu — `CM00` |

Sign on through the gateway and keep the session cookie for subsequent calls:

```bash
curl -s -c cookies.txt -X POST http://localhost:8080/auth/signon \
  -H 'Content-Type: application/json' \
  -d '{"userId":"ADMIN001","password":"PASSWORD"}'
# -> 200 {"userId":"ADMIN001","userType":"A","redirectTarget":"CA00"}

curl -s -b cookies.txt http://localhost:8080/accounts/1     # 200 (accounts 1-50 are seeded)
```

Every credential in the table above is exercised against the seeded database by
`auth-service` `DocumentedCredentialsSmokeTest`, so this section cannot drift from
the seed migration unnoticed.

> **Note on passwords.** Unlike the legacy plaintext model, the modernized
> stack stores passwords as **BCrypt hashes at rest** (seeded via Flyway) and
> verifies them through the Spring Security password encoder. Because the legacy
> sign-on upper-cased the entered password before comparison, legacy
> authentication was effectively case-insensitive; the modern encoder makes
> authentication **case-sensitive**. This is a deliberate, documented behavior
> change — see the [decision log](./docs/decision-log.md) for the rationale.

---

## Data and Database Migrations

The legacy VSAM KSDS and sequential data sets become PostgreSQL 18 relational
tables, accessed through Spring Data JPA. Schema and data are applied by
**Flyway** migrations, run automatically on service startup. VSAM primary keys
become relational primary keys, the three alternate indexes become secondary
indexes, and application-enforced integrity becomes declarative foreign-key
constraints.

The whole set lives in one place — `carddemo-common/src/main/resources/db/migration`
— and is applied **first-writer-wins**. All eight business services carry the same
consolidated set on their classpath and all eight run `spring.flyway.enabled: true`
with `baseline-on-migrate: false`: whichever service starts first provisions the
schema and records it in the one shared `flyway_schema_history` table, and every
later starter finds the version line already satisfied and applies nothing. Every
service runs `spring.jpa.hibernate.ddl-auto: validate`, so once the schema exists
each service verifies the mappings it was given rather than mutating them.

Sharing one `public` schema between several Flyway instances is safe because Flyway
takes an exclusive lock on its history table for the duration of a migration run: a
service starting concurrently blocks until the first commits, then reads a complete
version line. Shipping the set everywhere is deliberate — it removes the single point
of failure a designated owner would create, where removing or failing that one
service would leave nothing to provision the database. The consequence, accepted
knowingly, is that the shared group role holds `CREATE ON SCHEMA public` rather than
`USAGE` alone.

`batch-service` remains the **designated first starter**: it is the service gated on
by the Compose `depends_on: batch-service: service_healthy` condition and by the
Kubernetes `await-schema` initContainer, so bringing up any single service
transitively provisions the database first. Ordering still matters, but for
validation rather than for exclusivity — a service must not validate its mappings
before the schema exists. See the [decision log](./docs/decision-log.md) for the full
rationale.

| Migration | Purpose |
| :-------- | :------ |
| `V1__create_schema.sql` | Creates the 11 business tables in foreign-key order (`customers`, `accounts`, `security_users`, `tran_type`, `tran_category`, `disclosure_group`, `cards`, `card_xref`, `transactions`, `daily_transactions`, `tran_cat_bal`) plus foreign-key constraints, the secondary indexes derived from the three VSAM alternate indexes, the identifier-width `CHECK` constraints, and `transaction_id_seq`. |
| `V2__seed_reference_data.sql` | Seeds reference data: 7 transaction types, 18 transaction categories, and 51 disclosure groups (interest rates decoded from the fixtures' trailing-overpunch signs). |
| `V3__seed_test_data.sql` | Seeds test data: 10 security users; 50 customers, accounts, cards, and cross-references; 50 category balances (the fixture's exact distinct-key count); the 300-record daily-transaction feed; and the transaction history derived from that feed. |
| Java migration `4` | `SeededPiiEncryptionMigration` — encrypts the seeded SSN, government id, EFT account id, and card CVV in place through the AES-GCM `CryptoConverter`, using the deployment's own `CARDDEMO_PII_KEY`. Idempotent. |
| `V5__batch_metadata.sql` | Creates the Spring Batch metadata tables (`spring.batch.jdbc.initialize-schema: never`, so the framework never races the migrator). |

Seed data is derived from the human-readable ASCII fixed-width fixtures in
[`app/data/ASCII/`](./app/data/ASCII) (`custdata.txt`, `acctdata.txt`,
`carddata.txt`, `cardxref.txt`, `tcatbal.txt`, `dailytran.txt`, `discgrp.txt`,
`trancatg.txt`, `trantype.txt`).

### Financial Precision

COBOL monetary values are stored as packed decimal (`COMP-3`). To keep financial
output byte-identical, every such field maps to Java `BigDecimal` backed by a
PostgreSQL `NUMERIC(p,s)` column at the **exact** declared scale — for example
account balance and limit fields to `NUMERIC(12,2)` and transaction amount to
`NUMERIC(11,2)`. Arithmetic preserves the original operand order and intermediate
scale and **truncates toward zero** at the receiver scale (`setScale(2,
RoundingMode.DOWN)`), because the COBOL `COMPUTE` statements carry no `ROUNDED`
phrase — for example `0.125` becomes `0.12`, never `0.13`. Results therefore
match the mainframe exactly; the rounding and precision rules are not changed by
this migration.

---

## Observability

The application is not considered complete until it is observable. Every service
ships the following, verifiable in the local Docker Compose environment:

- **Structured logging** — JSON logs with correlation IDs propagated through the
  MDC, configured in each service's `logback-spring.xml`. A caller-supplied
  `X-Correlation-Id` request header is sanitized, placed in the MDC, echoed on the
  response, and reported as `traceId` in the error envelope when no trace is active.
- **Distributed tracing** — trace context propagated across service boundaries via
  Micrometer Tracing with an OpenTelemetry bridge, exported over OTLP/HTTP to the
  `jaeger` collector on the private Compose network
  (`management.opentelemetry.tracing.export.otlp.endpoint`, docker profile). Every log
  line emitted inside a request carries `traceId` and `spanId`, and a gateway-routed
  call produces one trace containing both the `api-gateway` span and the downstream
  service span — inspect it at <http://localhost:16686>.
- **Metrics** — exposed through Spring Boot Actuator and scraped by Prometheus
  at `/actuator/prometheus`.
- **Health, readiness, and liveness probes** — served by Actuator at
  `/actuator/health` (including `/actuator/health/readiness` and
  `/actuator/health/liveness`) and wired to the Kubernetes manifests in
  [`k8s/`](./k8s).
- **Dashboards** — a Grafana dashboard template
  ([`observability/grafana-dashboard.json`](./observability/grafana-dashboard.json))
  and a Prometheus scrape configuration
  ([`observability/prometheus.yml`](./observability/prometheus.yml)).

When running via Docker Compose, Prometheus is reachable at
<http://localhost:9090> and Grafana at <http://localhost:3001>.

---

## Testing

| Layer | Frameworks | Command |
| :---- | :--------- | :------ |
| Backend | JUnit 5 + Testcontainers (real PostgreSQL/Redis) | `mvn verify` |
| Frontend | Jest + React Testing Library | `cd frontend && npm test` |

The suite provides at least **50 unit-test scenarios** across the services and
exercises all **17 UI workflows**, preserving the behavior of the legacy
application. Testcontainers spins up real PostgreSQL and Redis instances so
integration tests run against the same technology used in production.

The backend suite is split across the two standard Maven test phases, so the
phase you invoke determines what runs:

| Phase | Plugin | Classes | Runs on |
| :---- | :----- | :------ | :------ |
| `test` | Surefire | `*Test`, `*Tests`, `Test*`, `*TestCase` | `mvn test` and every later phase |
| `integration-test` / `verify` | Failsafe | `*IT`, `IT*`, `*ITCase` | `mvn verify` and `mvn install` only |

Failsafe is declared once in the root `pom.xml`, so integration tests execute in
every module that has them, exactly once per build. `mvn test` alone stops before
the `verify` phase and therefore **skips every `*IT` class** — always use
`mvn verify` (or `mvn install`) to run the full backend suite:

```bash
mvn verify
```

Unit tests only (faster, no Testcontainers integration tests):

```bash
mvn test
```

Run the frontend suite:

```bash
cd frontend
npm test
```

---

## Performance and Non-Functional Verification

The non-functional targets are verified by a committed load-test harness, not by
dashboard configuration. `perf/carddemo-online-load.js` is a [k6](https://k6.io)
script that drives all 17 screens of the application through the api-gateway on the
same session-cookie and CSRF contract the SPA uses, so what it measures is the
deployed request path (gateway route, Redis session lookup, downstream service,
PostgreSQL). It is outside the Maven and npm builds and is run explicitly.

```bash
# 150 concurrent signed-on users for two minutes, against a running stack
BASE_URL=http://localhost:8080 VUS=150 WRITE_VUS=5 ADMIN_VUS=5 DURATION=2m \
    k6 run --summary-export perf-summary.json perf/carddemo-online-load.js
```

`VUS` is the TOTAL user count and the three mixes are carved out of it, so the run
holds exactly the concurrency figure the target names. The script declares the
target as a k6 threshold, so a run that misses it exits non-zero: `p(95) < 200`
overall, per scenario and per endpoint, plus `http_req_failed < 1%`.

The three mixes together cover every screen: the inquiry mix drives sign-on, the
main menu, account view, card list, card detail, transaction list and transaction
view plus the administrator user list; the update mix drives card update and account
update; and the administration mix drives the administrator menu, add/update/delete
user, bill payment, add transaction and the report request. Bill payment and the
report request are driven on their confirmation-gate path — the first-ENTER
behaviour of `COBIL00C` and `CORPT00C`, which redisplay with a prompt and mutate
nothing — because a confirmed bill payment zeroes a balance and a confirmed report
request launches a batch job per call, neither of which can be sustained for minutes
without destroying the fixture being measured against. Add transaction IS driven
confirmed, so the sequence-backed id generation of AAP 0.6.5 is measured under
concurrency.

### Measured results

Measured on the Docker Compose stack described above (all nine backend services,
PostgreSQL 18.4 and Redis 8 on one host), against the seeded fixture, with every
image built from this revision.

**Online response time — AAP 0.7.1 target: p95 < 200 ms at 150 concurrent users.**
Exactly 150 virtual users (140 inquiry, 5 update, 5 administration) sustained
**271.4 requests/second** for two minutes: 33,912 requests, **0 failed**, and all
33,212 checks passing.

| Endpoint | p95 (ms) | avg (ms) | median (ms) |
|----------|---------:|---------:|------------:|
| `POST /users` | **280.20** | 129.53 | 90.49 |
| `POST /auth/signon` | 197.28 | 113.95 | 90.15 |
| `PUT /users/{id}` | 195.06 | 106.95 | 88.42 |
| `GET /admin/menu` | 95.42 | 16.68 | 2.08 |
| `PUT /cards/{cardNumber}` | 92.23 | 22.26 | 8.83 |
| `POST /transactions` | 84.70 | 22.34 | 11.02 |
| `POST /billpay` | 81.00 | 22.50 | 14.11 |
| `POST /reports` | 76.44 | 25.35 | 13.06 |
| `GET /users/{id}` | 72.17 | 16.62 | 7.66 |
| `DELETE /users/{id}` | 70.82 | 21.82 | 11.04 |
| `PUT /accounts/{id}` | 70.29 | 23.81 | 12.02 |
| `GET /users` | 69.38 | 18.39 | 7.99 |
| `GET /transactions` | 68.62 | 16.37 | 6.21 |
| `GET /accounts/{id}` | 65.85 | 16.83 | 6.93 |
| `GET /cards/{cardNumber}` | 64.77 | 15.55 | 5.83 |
| `GET /transactions/last` | 64.19 | 13.68 | 5.92 |
| `GET /cards` | 64.11 | 15.64 | 5.81 |
| `GET /transactions/{id}` | 63.82 | 15.35 | 5.57 |
| `POST /admin/menu/select` | 51.80 | 8.23 | 2.16 |
| `POST /menu/select` | 50.72 | 9.87 | 2.09 |
| `GET /menu` | 46.88 | 9.26 | 1.94 |
| `GET /session` (anonymous probe) | 5.33 | 2.95 | 2.27 |
| **Overall** | **73.44** | **15.69** | **5.74** |

Per scenario: inquiry p95 65.78 ms, update p95 73.05 ms, administration p95
121.58 ms.

**21 of the 22 endpoints meet the target.** Every screen that does not hash a
password is at or below 95 ms — a margin of more than 2x. The one exceedance,
`POST /users` at 280 ms, is reported rather than excused, and its cause is
identified rather than assumed:

- The three slowest endpoints are exactly the three that run the password encoder,
  and all three share a median of 88-90 ms. Measured in isolation on an idle stack,
  `POST /users` completes in **85-91 ms**, and it encodes exactly once
  (`UserService` line 413) — there is no duplicated hash, and no N+1 query.
- The excess above that baseline is contention for CPU between concurrent adaptive
  hashes. That work factor is deliberate: it is the property that makes the stored
  credential resistant to offline attack, and it is the reason the plaintext
  comparison of `COSGN00C` was replaced (AAP 0.6.7). Reducing it would trade a
  security guarantee for a latency figure.
- The harness over-drives this path by design in order to obtain samples: it creates,
  reads, updates and deletes a user on **every** iteration of the administration mix,
  roughly two provisioned users per second sustained. Real administrator provisioning
  happens a handful of times a day, at which rate the endpoint costs its isolated
  85-91 ms. `POST /auth/signon` — the credential path every real user actually
  traverses, and which hashes just as expensively — stays inside the budget at
  197 ms even while being driven far harder than a real sign-on rate.

The gateway's `RateLimitFilter` bounds requests **per client address** at 600 per
minute. A load generator on one host presents a single address, so the budget must
be raised for the duration of a measurement run
(`carddemo.rate-limit.gateway-requests-per-minute`); 150 real users arrive from 150
addresses and each carries its own budget. The committed value is restored
afterwards, and was verified enforcing again: request 601 in a burst answered `429`.

**Fixture impact of a run**, verified against the database afterwards: the security
table returns to its seeded 10 rows with no harness user left behind, because the
add/update/delete cycle is self-cleaning; exactly `WRITE_VUS` customer records (5)
carry the normalised address, phone and FICO values the account-update screen
requires; the confirmed add-transaction path appended 115 rows, leaving the seeded
300 untouched; and **no batch job was launched**, confirming the report request
never passed its confirmation gate. Re-seed the database to restore the fixture
byte-for-byte.

A finding in its own right: **no seeded account can be rewritten through the
account-update screen unchanged.** All 50 are refused — 21 for a FICO score outside
300-850, 22 for a telephone area code absent from the North American lookup table,
and the remainder for a state code or state/zip pair the cross-edit rejects. The
seeded fixture is faithful to the legacy customer file, and that file simply carries
values `COACTUPC` itself does not accept, so an operator arriving at any of these
accounts must correct the flagged field before the rewrite is taken. This is
preserved behaviour, not a defect, and it is why the harness submits five corrected
customer fields on the accounts its update mix owns.

The gateway's `RateLimitFilter` bounds requests **per client address** at 600 per
minute. A load generator on one host presents a single address, so the budget must
be raised for the duration of a measurement run
(`carddemo.rate-limit.gateway-requests-per-minute`); 150 real users arrive from 150
addresses and each carries its own budget. The committed value is restored
afterwards, and was verified enforcing again: request 601 in a burst answered `429`.

**Batch window — AAP 0.7.1 target: completion within a 4-hour window.** The two
job streams that carry the batch workload — transaction posting (`CBTRN02C` /
`POSTTRAN`) and monthly interest calculation (`CBACT04C` / `INTCALC`) — were each
run against a staged volume of roughly 50,000 driving records and timed from
their own Spring Batch metadata (`batch_job_execution` joined to
`batch_step_execution`), not from wall-clock observation:

| Job | Run | Records | Outcome | Elapsed | Throughput |
|-----|-----|--------:|---------|--------:|-----------:|
| `transactionPostingJob` | All records posted | 50,000 | `COMPLETED` | 89.5 s | 558 records/s |
| `transactionPostingJob` | All records rejected (code 103) | 50,000 | `COMPLETED_WITH_REJECTS` | 95.5 s | 524 records/s |
| `interestCalculationJob` | Shipped seed | 50 | `COMPLETED` | 0.6 s | — |
| `interestCalculationJob` | Staged volume | 50,050 | `COMPLETED` | 192.0 s | 261 records/s |

At 558 records/second the 4-hour window admits roughly **8.0 million** postings;
the 50,000-record run consumed 0.6% of it. Interest calculation is the heavier of
the two per record because each record drives a disclosure-group lookup, a card
cross-reference read, an interest transaction insert and an account roll-up: at
261 records/second the window admits roughly **3.75 million** category balances,
and the 50,050-record run consumed 5.3% of it. Both jobs therefore clear the
window by two orders of magnitude at these volumes.

The rejected-record posting run also confirms the frozen reject-file contract at
runtime: 50,000 rejects produced a `dalyrejs.txt` of exactly 21,550,000 bytes,
i.e. **431 bytes per record** — the 430-byte `DALYREJS` record plus its single
`LF`.

The interest run doubles as a runtime check of the COBOL truncation semantics.
Each staged category balance was `1000.40` against a disclosure rate of `15.00`,
so `(1000.40 * 15.00) / 1200 = 12.5050` — a value whose third decimal forces the
rounding mode to show itself. All 50,000 interest transactions were written as
`12.50` (total `625,000.00`), which is truncation toward zero; half-up rounding
would have produced `12.51` and a total of `625,500.00`. Every staged account was
rolled up to `acct_curr_bal = 12.50` with its cycle figures zeroed, matching
`CBACT04C` paragraph `1050-UPDATE-ACCOUNT`. The category balances themselves are
left untouched, because that program's only `REWRITE` targets the account file.

**Memory footprint.** Resident set size per container immediately after the
150-user run, and JVM heap actually in use:

| Component | Container RSS | JVM heap used |
|-----------|--------------:|--------------:|
| api-gateway | 990 MiB | 155 MiB |
| reporting-service | 952 MiB | 93 MiB |
| transaction-service | 924 MiB | 101 MiB |
| billpay-service | 855 MiB | 90 MiB |
| card-service | 818 MiB | 133 MiB |
| auth-service | 814 MiB | 161 MiB |
| account-service | 744 MiB | 67 MiB |
| user-service | 738 MiB | 47 MiB |
| batch-service | 736 MiB | 77 MiB |
| PostgreSQL / Redis / frontend | 84 / 6 / 91 MiB | n/a |

The AAP's "under 10% increase" target is expressed relative to the mainframe
region it replaces. That baseline cannot be measured in this environment, so the
absolute figures above are reported instead of a percentage; see the decision log.

**Exactly-once posting.** `tran_id` is the feed record's own `DALYTRAN-ID`
(`MOVE DALYTRAN-ID TO TRAN-ID`), so the primary key makes double-posting
structurally impossible rather than merely unlikely. Demonstrated end to end:

1. A 1,000-record feed posted cleanly — 1,000 rows written.
2. The identical feed resubmitted under a different posting date — refused by
   `transactions_pkey`; the posted count stayed at 1,000 with **0 duplicate
   `tran_id` values**.
3. A 50,000-record run interrupted by `SIGKILL` at 3,171 records left the
   execution non-terminal; the naive resubmission was **refused synchronously**
   (HTTP 400, "A job execution for this job is already running"), so a crash can
   neither double-post nor be mistaken for a clean run.
4. After an operator marks the interrupted execution `FAILED` — the restartable
   terminal status; `ABANDONED` is deliberately not restartable — the instance is
   accepted for restart.

Resuming an interrupted run additionally requires the batch working directory to be
durable, because the step re-opens the reject file it was streaming to. Both
delivered topologies use ephemeral storage there by design (a Compose `tmpfs`, a
Kubernetes `emptyDir`), and the manifests record that a durable deployment
substitutes a `PersistentVolumeClaim`; on ephemeral storage the restart fails
loudly instead of silently re-posting.

---

## Documentation

- **[Decision log](./docs/decision-log.md)** — every non-trivial migration
  decision, with alternatives, rationale, and risk. Rationale lives here, not in
  code comments.
- **[Traceability matrix](./docs/traceability-matrix.md)** — the bidirectional
  mapping between legacy COBOL constructs and their Java/React implementations;
  it enumerates the complete legacy inventory and records, per tranche, which
  targets are delivered versus planned.

---

## Legacy Mainframe Reference

The original IBM z/OS mainframe application (COBOL, CICS, VSAM, JCL, RACF) is
retained in-place under [`app/`](./app) as the transformation reference and
traceability anchor. Its installation and operation guide — dataset/HLQ
conventions, the JCL job sequence, CICS resource definition, and the
transaction/program/BMS-map inventory — is preserved **unchanged** in the legacy
[`README.md`](./README.md). Refer to that document for mainframe-side context;
this file covers the modernized stack only.

---

## License

This project is released under the Apache 2.0 license. See [`LICENSE`](./LICENSE)
and [`NOTICE`](./NOTICE) for details.
