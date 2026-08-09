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
  primary keys, the three VSAM alternate indexes become secondary indexes (among
  the six the schema declares), application-enforced
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

### Out of Scope

The migration re-platforms the CardDemo application itself. Four classes of
integration are deliberately **not** part of it, and nothing in this repository
implements or simulates them — a reader should not expect to find them:

- **Payment networks.** No card-network authorization, clearing or settlement
  interface. Transactions are posted against the local database only.
- **Bank core systems.** No general-ledger, deposit-system or customer-master
  integration. `customers`, `accounts` and `cards` are this application's own
  system of record here.
- **Regulatory reporting.** The reporting service produces the statements and
  transaction reports the legacy `CORPT00C` / `CBSTM03A` programs produced;
  it files nothing with any authority.
- **Shared mainframe utilities and downstream file interfaces.** Utilities that
  served other applications on the mainframe are untouched, and record layouts
  consumed by downstream systems keep their exact legacy shape rather than being
  redesigned — the 350-byte transaction record and the 430-byte reject record
  (350 + an 80-byte validation trailer) are preserved byte-for-byte.

The legacy artifacts that are retained but not transformed — `app/cpy/UNUSED1Y.cpy`,
the twelve EBCDIC binary data sets under `app/data/EBCDIC/`, and the developer-only
CICS artifacts `COCRDSEC` and transaction `CDV1` — are recorded in
[`docs/traceability-matrix.md`](./docs/traceability-matrix.md).

### Code Documentation

Java, TypeScript and configuration sources document themselves in
**reStructuredText field style**: `:purpose:` for what a class, method or block is
for, `:param <name>:` for each parameter, and `:output:` / `:returns:` for what
comes back, with `:raises:` and `:note:` where they apply. Comments describe
purpose and behavior; the *rationale* for a non-obvious choice lives in
[`docs/decision-log.md`](./docs/decision-log.md) rather than in the source, so
there is one place to look for why something is the way it is.

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
| Frontend build runtime | Node.js | 24 LTS recommended; `>= 22.22` supported (`frontend/package.json` `engines`) |
| Frontend bundler / language | Vite / TypeScript | Vite 8.x / TypeScript 5.x |
| Metrics / tracing | Micrometer + OpenTelemetry bridge | BOM-managed |

Spring ecosystem starters used across the services (all versions BOM-managed by
Spring Boot 4.1.0): `spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
`spring-boot-starter-security`, `spring-boot-starter-batch`,
`spring-boot-starter-validation`, `spring-boot-starter-actuator`,
`spring-boot-starter-data-redis` + `spring-session-data-redis`,
`spring-cloud-starter-gateway-server-webmvc` (gateway only — Spring Cloud 2025.1.x
removed the older `spring-cloud-starter-gateway` coordinate in favour of the
explicit `-server-web{flux,mvc}` artifacts), `org.postgresql:postgresql`,
`org.flywaydb:flyway-core` + `flyway-database-postgresql`,
`io.micrometer:micrometer-registry-prometheus`,
`io.micrometer:micrometer-tracing-bridge-otel`, `spring-boot-starter-test`, and
`org.testcontainers:testcontainers-junit-jupiter` +
`org.testcontainers:testcontainers-postgresql` (the Testcontainers 2.x artifact
names, which carry the `testcontainers-` prefix).

### Container Base Images

| Purpose | Base image |
| :------ | :--------- |
| Service runtime (each Spring Boot service) | `eclipse-temurin:21-jre` |
| Frontend build | `node:24` |
| Frontend runtime (static SPA server) | `nginx:1.29-alpine` |
| Database | `postgres:18` |
| Session / cache store | `redis:8` |

Every base image is pinned to an explicit version rather than a floating tag, so a
rebuild cannot silently pick up a different runtime.

---

## Repository Layout

The modernized stack is added in-place alongside the retained legacy `app/`
sources. The parent `pom.xml` aggregates ten Maven modules.

```text
carddemo/                        (repository root — legacy app/ retained)
├── pom.xml                      Maven aggregator / parent BOM (Spring Boot 4.1.0)
├── .gitignore                   Java + Node ignore patterns
├── .dockerignore                Build-context exclusions shared by every image
├── .env.example                 Template for the local .env (never commit .env)
├── docker-compose.yml           PostgreSQL, Redis, the nine services, frontend,
│                                Jaeger (OTLP collector), Prometheus, Grafana
├── README.md                    Legacy mainframe guide (RETAINED UNCHANGED)
├── README-target.md             This document
├── LICENSE  NOTICE              Apache-2.0 licence and attribution (RETAINED)
├── CODE_OF_CONDUCT.md  CONTRIBUTING.md   Project governance (RETAINED)
├── docs/
│   ├── decision-log.md          Non-trivial decisions with rationale
│   └── traceability-matrix.md   Bidirectional COBOL <-> Java construct mapping
├── db/
│   └── init/                    First-boot PostgreSQL init (per-service roles)
├── k8s/                         Kubernetes manifests (deployments, services, config)
├── observability/
│   ├── grafana-dashboard.json   Grafana dashboard template
│   └── prometheus.yml           Prometheus scrape configuration
├── perf/                        k6 load-test harness for the non-functional targets
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
├── app/                         Legacy COBOL / CICS / VSAM / JCL / BMS (reference)
├── diagrams/                    Legacy architecture diagrams (RETAINED, reference)
└── samples/                     Legacy runtime bundles (RETAINED, reference)
```

Each service module follows the package layout
`com.carddemo.<service>.{controller,service,repository,mapper,config,batch}`,
with shared code under `com.carddemo.common.*`. Every service module carries its
own `src/main/resources/application.yml`, `logback-spring.xml`, `src/test/java`
suite, and `Dockerfile`. The Flyway scripts on the classpath at `db/migration/` are
**not** per-service:
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
- **Node.js 24 LTS is recommended**, and any release from **22.22** upward works:
  `frontend/package.json` declares `engines.node >= 22.22.0` and `engines.npm >= 11 < 12`,
  and the container build stage uses `node:24`. Only required to build or develop
  the frontend outside Docker.
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

From the repository root, build the SPA (Node.js 24 LTS recommended, `>= 22.22`
supported):

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
committed — it is listed in `.gitignore`). Create it from the template and then
replace **every** value in it, exactly as the template's own header instructs.
Compose uses `${VAR:?}` references, so it fails fast on a variable that is
**unset** — but the template ships a readable placeholder for each one, so an
unedited placeholder satisfies that check and is **not** reported. Leaving any
placeholder in place is therefore a silent misconfiguration:

```bash
cp .env.example .env
# then edit .env and replace EVERY value. The template groups them as:
#   POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD    bootstrap owner + database
#   CARDDEMO_{AUTH,USER,ACCOUNT,CARD,TRANSACTION,BILLPAY,REPORTING,BATCH}_DB_PASSWORD
#                                                      the eight per-service DB roles
#   REDIS_PASSWORD                                     session store
#   GRAFANA_ADMIN_USER / GRAFANA_ADMIN_PASSWORD        dashboard login
#   MONITORING_PASSWORD                                Prometheus scrape principal
#   CARDDEMO_PII_KEY                                   AES-256 key for PII at rest
#   CARDDEMO_COOKIE_SECURE                             leave false for plain-HTTP local runs
openssl rand -base64 24     # for each password
openssl rand -base64 32     # for CARDDEMO_PII_KEY — see below
```

> **Cookie transport (`CARDDEMO_COOKIE_SECURE`).** The session cookie and the
> gateway's `XSRF-TOKEN` cookie default to `Secure`, and a browser never returns a
> `Secure` cookie over plain `http://`. This Compose stack has no TLS terminator in
> front of it, so `docker-compose.yml` sets `CARDDEMO_COOKIE_SECURE=false` for the
> backend services and the gateway — you do not have to do anything for the
> quickstart to work. Chrome treats `http://localhost` as a secure context anyway,
> but **any non-localhost plain-HTTP origin** (the host's LAN address, a
> LoadBalancer without TLS) needs that `false` to sign on at all; without it
> sign-on appears to succeed and every following request comes back `401`. Put a
> TLS terminator in front of the stack and remove the override — or set it to
> `true` in `.env` — to get the secure default back. The Kubernetes manifests never
> set the variable, so a cluster deployment keeps `Secure` on.

`CARDDEMO_PII_KEY` is the one value with a hard format requirement: it must be a
**Base64-encoded 256-bit (32-byte)** key. The template's placeholder is not valid
Base64, so a service that reads or writes an encrypted column refuses to start
with `PII encryption key (CARDDEMO_PII_KEY / carddemo.pii.key) is not valid
Base64; supply a Base64-encoded 256-bit key` and the container enters a restart
loop. Generate it with `openssl rand -base64 32` and keep it stable for the life
of the data: the seeded PII is encrypted under this key, so a database restored
under a different key can no longer be decrypted.

#### Per-service database roles

`db/init/01-create-service-roles.sh` provisions the database principals the
services authenticate as. The official `postgres` image runs it exactly once,
during first-boot initialisation of an empty data directory, as `POSTGRES_USER`
against `POSTGRES_DB`; `docker-compose.yml` mounts the directory read-only and
`k8s/deployment-postgres.yaml` projects the same script from a ConfigMap. It
creates:

- **Eight per-service login roles** — `carddemo_auth`, `carddemo_user`,
  `carddemo_account`, `carddemo_card`, `carddemo_transaction`,
  `carddemo_billpay`, `carddemo_reporting`, `carddemo_batch` — each taking its
  password from the matching `CARDDEMO_<SVC>_DB_PASSWORD`. Nothing is defaulted:
  a missing or empty value aborts initialisation rather than creating a role with
  a guessable password. `api-gateway` is deliberately absent — it maps no
  entities and holds no datasource.
- **One `carddemo_app` group role** (`NOLOGIN`) that every service role belongs
  to, holding `USAGE` + `CREATE` on schema `public`, `SELECT`/`INSERT`/`UPDATE`/
  `DELETE` on the tables and `USAGE`/`SELECT`/`UPDATE` on the sequences, with
  `ALTER DEFAULT PRIVILEGES` declared for each possible creating role. `CREATE`
  is required because every service carries the same Flyway set and whichever
  starts first materialises the schema.

The result is per-service **authentication** and auditability over a shared
application privilege set, not per-table isolation; the decision log records why.
Because the roles are created only while the volume is being initialised,
changing one afterwards means `ALTER ROLE ... PASSWORD` in the running database
(or `docker compose down -v` to start clean).

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
`nginx:1.29-alpine` runtime).

On first start, `batch-service` — the designated first starter — applies the schema
and seed migrations against the PostgreSQL container automatically. The seven other
datasource-owning services declare `depends_on: batch-service: service_healthy`, so
each waits for it to report healthy before validating its own mappings;
`api-gateway` maps no entities and holds no datasource, so it waits only on
PostgreSQL and Redis and can start alongside. Every datasource-owning service
carries the same migration set, so the schema is provisioned by whichever starts
first and the later starters find nothing left to apply. To stop and remove the
stack:

```bash
docker compose down
```

Add `-v` to also drop the PostgreSQL and Redis volumes for a clean slate:

```bash
docker compose down -v
```

The frontend's nginx re-resolves `api-gateway` on every request (its `resolver` is
generated from the container's own `/etc/resolv.conf` at start-up, and the upstream is
named through a variable), so restarting or recreating the gateway on its own —
`docker compose restart api-gateway`, `docker compose up -d --force-recreate
api-gateway` — is picked up automatically within the resolver's 10-second window. The
frontend does **not** have to be recreated afterwards.

Why that matters, from what a start-up-only resolution actually did here: if the
recreated gateway happened to be given the same network address, `/api/` kept working
and nothing appeared wrong; if it was given a different one, the old address usually
belonged to ANOTHER service by then, so `/api/` calls were not refused — they were
answered by whichever service now held that address. After recreating the gateway, the
frontend's `/api/csrf` was answered `401` by `batch-service`, which had taken the
gateway's previous address, so the symptom read as an authentication problem rather
than as a stale name.

### Alternative: run on the workstation without containers

Docker Compose is the supported way to run the stack. If you nonetheless want to run
a service from the IDE or with `mvn spring-boot:run`, note that the eight backend
services then share one host and therefore cannot share one port. With **no profile
active** each service binds its own port, and those are exactly the ports the
gateway's own default route table addresses, so the estate is routable with no
environment variable at all:

| Service | Default (no profile) port | Gateway route default |
| :------ | :------------------------ | :-------------------- |
| `api-gateway` | 8080 | — (entry point) |
| `auth-service` | 8081 | `AUTH_SERVICE_URI` |
| `user-service` | 8082 | `USER_SERVICE_URI` |
| `account-service` | 8083 | `ACCOUNT_SERVICE_URI` |
| `card-service` | 8084 | `CARD_SERVICE_URI` |
| `transaction-service` | 8085 | `TRANSACTION_SERVICE_URI` |
| `billpay-service` | 8086 | `BILLPAY_SERVICE_URI` |
| `reporting-service` | 8087 | `REPORTING_SERVICE_URI` |
| `batch-service` | 8088 | `BATCH_SERVICE_URI` |

These ports apply **only** to that workstation mode. In a container topology every
service listens on the single internal port **8080** and publishes nothing:
`docker-compose.yml` sets `SERVER_PORT=8080` in its shared backend environment and
`k8s/configmap.yaml` supplies the same key to every pod, which is why every
`containerPort`, probe port, Service `targetPort` and Prometheus target is 8080.

A workstation run still needs the datastores and the schema. The simplest route is to
start just those from Compose and point the services at them:

```bash
docker compose up -d postgres redis batch-service   # datastores + the schema owner
cd account-service && mvn spring-boot:run           # binds 8083
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
`auth-service` `DocumentedCredentialsSmokeIT` (run by `mvn verify`), so this
section cannot drift from the seed migration unnoticed.

### State-Changing Requests (CSRF)

CSRF protection is enforced **once, at the gateway**, using the cookie
double-submit pattern; the internal services do not repeat it. `/auth/**` is
exempt, so signing on needs nothing extra — but **every** state-changing call
after that (`POST`, `PUT`, `PATCH`, `DELETE`) must carry the token, or the gateway
answers **`403` with an empty body** and no error envelope:

```bash
# 1. Sign on (exempt from CSRF) and keep the session cookie.
curl -s -c cookies.txt -X POST http://localhost:8080/auth/signon \
  -H 'Content-Type: application/json' \
  -d '{"userId":"ADMIN001","password":"PASSWORD"}'

# 2. Ask for a token. This also materializes the XSRF-TOKEN cookie.
curl -s -b cookies.txt -c cookies.txt http://localhost:8080/csrf
# -> {"headerName":"X-XSRF-TOKEN","parameterName":"_csrf","token":"<token>"}

# 3. Send the token back in the X-XSRF-TOKEN header on the write.
TOKEN=$(curl -s -b cookies.txt -c cookies.txt http://localhost:8080/csrf \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s -b cookies.txt -X PUT http://localhost:8080/accounts/1 \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" \
  -d @account-update.json
# -> 200, and the account's "version" advances by one
```

The token is issued by `GET /csrf` and also written to the non-`HttpOnly`
`XSRF-TOKEN` cookie, which is why the browser SPA needs no special handling — axios
reads that cookie and sends the `X-XSRF-TOKEN` header by default. A `curl` client
must do the same two steps by hand.

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
become relational primary keys, and application-enforced integrity becomes
declarative foreign-key constraints. The schema declares **six** non-primary-key
indexes, including those derived from the three VSAM alternate indexes
(`CARDDATA.VSAM.AIX`, `CARDXREF.VSAM.AIX`, `TRANSACT.VSAM.AIX`) — the remaining
three support the cross-reference and disclosure-group lookups the COBOL programs
performed by read order: `idx_card_xref_acct_id`, `idx_card_xref_cust_id`,
`idx_cards_card_acct_id`, `idx_disclosure_group_acct_group_id`,
`idx_disclosure_group_type_cat` and `idx_transactions_card_num`.

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
| Java migration `4` | `SeededPiiEncryptionMigration` — encrypts the seeded SSN, government id, EFT account id, and card CVV in place through the AES-GCM `CryptoConverter`, using the deployment's own `CARDDEMO_PII_KEY`. A static SQL literal cannot carry those values: the key comes from the environment and every token embeds a fresh random IV. Idempotent — a value that is already an encrypted token is left untouched, so a replay rewrites nothing. |
| `V5__batch_metadata.sql` | Creates the Spring Batch metadata tables. The migration owns them outright: Spring Boot 4.1 removed the whole `spring.batch.jdbc.*` property group, so there is no `initialize-schema` lever and nothing in the framework races the migrator. |
| `V6__security_users_optimistic_lock.sql` | Adds the `version` column to `security_users` (`BIGINT NOT NULL DEFAULT 0`) that backs JPA `@Version` optimistic locking on user maintenance. |
| `V7__cards_optimistic_lock.sql` | Adds the same `version` column to `cards`, so a card update detects a concurrent modification exactly as the account update does. |
| `V8__transactions_card_fk.sql` | Adds the `fk_transactions_card` foreign key from `transactions.tran_card_num` to `cards.card_num`, completing the declarative referential integrity the legacy programs enforced by read order. |

Beyond the migration set, `carddemo-common`'s `SeededPiiEncryptionMigrator` runs one
idempotent sweep over the same four columns during context initialization. Migration
`4` is what converts the seed on a fresh database; the sweep is the safety net that
also protects a database seeded before that migration existed, or one restored from an
older dump, and it rewrites nothing when every value is already an encrypted token.

Seed data is derived from the human-readable ASCII fixed-width fixtures in
[`app/data/ASCII/`](./app/data/ASCII) (`custdata.txt`, `acctdata.txt`,
`carddata.txt`, `cardxref.txt`, `tcatbal.txt`, `dailytran.txt`, `discgrp.txt`,
`trancatg.txt`, `trantype.txt`).

### Backup and Recovery

The legacy application protected the transaction file with GDG backup jobs
(`app/jcl/DEFGDGB.jcl`, `app/jcl/TRANBKP.jcl`). Those map to database backup rather
than to application code — AAP §0.4.6 places them outside application-code scope — so
the modernized stack relies on PostgreSQL's own tooling, and the procedure is
documented here so it is not left to improvisation.

**Recovery point.** A restore returns the database to the state captured by the last
dump; anything committed after it is lost. Point-in-time recovery is **not** enabled:
the `postgres:18` container runs with `archive_mode=off` and `wal_level=replica`, so
no WAL archive exists to replay. Enabling PITR means running the server with
`archive_mode=on` plus an `archive_command` writing to durable storage outside the
container, and is a deployment decision this reference stack does not make for you.

Take a compressed logical backup of the running stack (custom format, so a selective
restore is possible):

```bash
# Compose. POSTGRES_USER / POSTGRES_DB come from your .env.
docker compose exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc \
  > "carddemo-$(date -u +%Y%m%dT%H%M%SZ).dump"
```

```bash
# Kubernetes: the same command, in the postgres pod.
kubectl exec deploy/postgres -- \
  pg_dump -U carddemo_owner -d carddemo -Fc > carddemo.dump
```

Restore into the running database. `--clean --if-exists` drops the objects the dump
recreates, so the restore is repeatable; stop the services first so nothing writes
through the restore:

```bash
docker compose stop $(docker compose config --services | grep -- -service) api-gateway
docker compose exec -T postgres \
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner \
  < carddemo-20260808T000000Z.dump
docker compose start api-gateway $(docker compose config --services | grep -- -service)
```

Because every service boots with `spring.jpa.hibernate.ddl-auto: validate` and Flyway
finds its version line already satisfied inside the restored dump, the services come
back against the restored schema without re-running any migration.

**Retention** is the operator's policy, not the application's. In the cluster,
`k8s/cronjob-postgres-backup.yaml` runs the `pg_dump` above every day at 01:00,
writes it to the `carddemo-backup` PersistentVolumeClaim and deletes dumps older than
`BACKUP_RETENTION_DAYS` (7 by default). It is a plain `CronJob` using the same pinned
`postgres:18` image and the existing `carddemo-postgres-bootstrap` Secret, so nothing
new has to be provisioned beyond the volume:

```bash
kubectl apply -f k8s/cronjob-postgres-backup.yaml
kubectl create job --from=cronjob/carddemo-postgres-backup backup-now   # ad-hoc run
```

A dump is only a backup once it has been restored somewhere: verify a new dump by
restoring it into a throwaway database (`createdb carddemo_verify && pg_restore -d
carddemo_verify …`) and comparing row counts, rather than trusting the file.

### Financial Precision

COBOL monetary values are stored as packed decimal (`COMP-3`). To keep financial
output byte-identical, every such field maps to Java `BigDecimal` backed by a
PostgreSQL `NUMERIC(p,s)` column at the **exact** declared scale — for example
account balance and limit fields to `NUMERIC(12,2)` and transaction amount to
`NUMERIC(11,2)`. Arithmetic preserves the original operand order and intermediate
scale and **truncates toward zero** at the receiver scale (`setScale(2,
RoundingMode.DOWN)`), because the COBOL `COMPUTE` statements carry no `ROUNDED`
phrase — for example `0.125` becomes `0.12`, never `0.13`. The canonical case is
the monthly interest computation `(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`, whose
result is truncated to two places; every other monetary site applies the same mode
when it normalizes a value to its declared scale. `RoundingMode.DOWN` is the only
rounding mode in the production code — with one deliberate exception, the JSON wire
formatter, which uses `RoundingMode.UNNECESSARY` so that a value not already at
scale 2 fails loudly instead of being silently rounded on its way out. Results
therefore match the mainframe exactly; the rounding and precision rules are not
changed by this migration, and `FinancialPrecisionTest` pins the behavior
(`0.125` → `0.12`, `0.41666…` → `0.41`).

---

## Observability

The application is not considered complete until it is observable. Every service
ships the following, verifiable in the local Docker Compose environment:

- **Structured logging** — JSON logs with correlation IDs propagated through the
  MDC, configured in each service's `logback-spring.xml`. A caller-supplied
  `X-Correlation-Id` request header is sanitized, placed in the MDC, echoed on the
  response, and reported as `traceId` in the error envelope when no trace is active.
  A failure the gateway itself produces before it reaches a service — an
  unavailable route, or a CSRF rejection — carries the id in the
  `X-Correlation-Id` **response header** only: those responses are either bodyless
  or carry a `correlationId`/`traceId` of `null`, because no downstream request
  context existed to populate them.
- **Distributed tracing** — trace context propagated across service boundaries via
  Micrometer Tracing with an OpenTelemetry bridge, exported over OTLP/HTTP to the
  `jaeger` collector on the private Compose network
  (`management.opentelemetry.tracing.export.otlp.endpoint`, docker profile). Every log
  line emitted inside a request carries `traceId` and `spanId`, and a gateway-routed
  call produces one trace containing both the `api-gateway` span and the downstream
  service span — inspect it at <http://localhost:16686>.
- **Metrics** — exposed through Spring Boot Actuator and scraped by Prometheus
  at `/actuator/prometheus`. That endpoint (and `/actuator/metrics/**`) is
  **authenticated**: it accepts HTTP basic credentials for a single dedicated
  `monitoring` principal, so an unauthenticated request gets `401`. The password
  comes from `MONITORING_PASSWORD` (`carddemo.monitoring.password`), which
  `docker-compose.yml` and `k8s/secret.yaml` hand to every service and to
  Prometheus, whose `observability/prometheus.yml` scrape jobs send it as
  `basic_auth`. `/actuator/health` — including the readiness and liveness groups —
  stays unauthenticated so container and Kubernetes probes work without a
  credential:

  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/prometheus
  # -> 401
  curl -s -u monitoring:"$MONITORING_PASSWORD" http://localhost:8080/actuator/prometheus | head -1
  # -> # HELP application_ready_time_seconds ...
  ```
- **Health, readiness, and liveness probes** — served by Actuator at
  `/actuator/health` (including `/actuator/health/readiness` and
  `/actuator/health/liveness`) and wired to the Kubernetes manifests in
  [`k8s/`](./k8s).
- **Dashboards** — a Grafana dashboard template
  ([`observability/grafana-dashboard.json`](./observability/grafana-dashboard.json))
  and a Prometheus scrape configuration
  ([`observability/prometheus.yml`](./observability/prometheus.yml)). The
  Spring Batch panels read `spring_batch_*` series, which the three batch-running
  services publish only once a job has actually executed; they show "No data" on a
  freshly started stack until a job is launched, by design — no job runs at
  container start, because that would post financial data on every restart.

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

Failsafe is activated build-wide by a single declaration in the root `pom.xml`,
so integration tests execute in every module that has them, exactly once per
build. The six modules that own integration tests (`account-service`,
`card-service`, `transaction-service`, `billpay-service`, `reporting-service`,
`batch-service`) refine that inherited declaration in their own POM — they bind
the `integration-test` / `verify` executions and pass the test-only PII key to
the forked JVM — rather than declaring a second, competing plugin. `mvn test`
alone stops before the `verify` phase and therefore **skips every `*IT` class** —
always use `mvn verify` (or `mvn install`) to run the full backend suite:

```bash
mvn verify
```

Unit tests only (faster, no Testcontainers integration tests — every class that
starts a container is named `*IT` and so is outside Surefire's patterns):

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

**What the figure costs, and what it therefore needs.** A latency number is only
meaningful next to the machine that produced it, so the portable measurement is CPU
per request, which does not move with how busy the host is. Measured over a 150-user
run by differencing `process_cpu_time_ns_total` against the served request count on
each service:

| Service | CPU per request | What dominates it |
|---------|----------------:|-------------------|
| api-gateway | 2.0 ms | route match, session lookup, proxy hop — paid by EVERY request |
| card-service | 3.5 ms | one index scan and the screen window |
| transaction-service | 4.0 ms | keyset page, or 4 statements on the add path |
| account-service | 5.5 ms | xref → account → customer, the COBOL read order |
| user-service | 18.3 ms | its mix includes two encoder calls per iteration |
| billpay-service | 27.3 ms | account read plus the available-credit computation |
| reporting-service | 26.5 ms | the whole date-window validation |
| auth-service | **94.8 ms** | **~72.8 ms of it is the BCrypt verification alone** |
| **Whole tier, per client request** | **6.3 ms** | the gateway hop plus one downstream service |

BCrypt at the configured strength 10 costs a measured **72.8 ms of CPU per
verification** (measured directly, 60 verifications, stable to ±0.4 ms across runs),
so **one core sustains 13.7 verifications per second** and ~10.5 sign-ons per second
end to end. Everything else follows arithmetically:

- Sizing rule: `cores = target requests/second x CPU-ms per request / 1000`, then
  divide by the utilisation you are willing to run at. Queueing delay grows without
  bound as utilisation approaches 1, so the budgets below target 0.5.
- At the request rate this harness produces from 150 users (**264.5 requests/second**
  measured), the tier needs `264.5 x 6.3 ms = 1.67` cores of actual work — which is
  exactly the 1.67 cores the same run consumed, so the model is not a guess — and the
  gateway alone needs `264.5 x 2.0 ms = 0.53` cores because every request traverses
  it. At the 619 requests/second a heavier probe produces, the same arithmetic gives
  3.9 cores for the tier and 1.24 for the gateway — the second of which a 1-core limit
  cannot serve at all, which is why that limit was raised to 2 in
  `k8s/deployment-*.yaml` and `docker-compose.yml`.
- **Sign-on is the only endpoint whose cost is irreducible CPU**, and it must be
  provisioned for its ARRIVAL BURST rather than its average: 150 operators signing on
  within a 15-second window is 10 sign-ons/second, which needs ~2 cores at half
  utilisation. Provisioned at 1 core that burst runs at ~95% utilisation and the
  measured server-side p95 was 715.8 ms; the same endpoint's service time in the same
  run was 81-106 ms. Reducing the work factor would buy the latency back by
  weakening the credential at rest (AAP 0.6.7) and is not done.

**Second measurement, taken on a deliberately contended host** — 4 cores, load
average ~20, 115 containers from other tenants — so that the figures cannot be read
as best-case. Exactly 150 virtual users through the gateway, server-side p95 from the
Micrometer histogram delta over the measured window:

| Endpoint | p95 at 1 s think time | p95 at 5 s think time |
|----------|----------------------:|----------------------:|
| `POST /auth/signon` | 715.8 ms | 447.4 ms |
| `POST /users` | 357.9 ms | 536.9 ms |
| `PUT /users/{id}` | 357.9 ms | 536.9 ms |
| `POST /billpay` | 89.5 ms | 111.8 ms |
| `PUT /cards/{cardNumber}` | 61.5 ms | 89.5 ms |
| `POST /transactions` | 61.5 ms | 111.8 ms |
| `GET /accounts/{id}` | 50.3 ms | 89.5 ms |
| `POST /menu/select` | 50.3 ms | 89.5 ms |
| `GET /menu` | 44.7 ms | 89.5 ms |
| `GET /transactions` | 44.7 ms | 89.5 ms |
| `PUT /accounts/{id}` | 44.7 ms | 89.5 ms |
| `GET /cards` | 39.1 ms | 89.5 ms |
| `GET /cards/{cardNumber}` | 33.6 ms | 89.5 ms |
| `GET /transactions/{id}` | 33.6 ms | 89.5 ms |
| `GET /transactions/last` | 28.0 ms | 61.5 ms |
| **Overall, client-side** | **72.94 ms** | **97.57 ms** |

33,200 requests at 264.5/second with **0 failed**, then 11,180 requests at
54.1/second with **0 failed** and every check passing. **Every endpoint that does not
hash a password is inside the 200 ms budget in both runs**, and the three that do are
the three named above. Values are Micrometer bucket upper bounds, so each is an upper
bound on the true percentile. Zero HikariCP acquisition timeouts occurred in either
run, and PostgreSQL and Redis stayed at 6.9% and 4.9% CPU — the datastores are not
the constraint at this concurrency.

The gateway's `RateLimitFilter` counts a **signed-on caller per session** (600 per
minute) and reserves **per-address** counting for callers that hold no session yet
(1200 per minute overall, 300 per minute on `/auth/**`). No measurement override is
needed: a run on **shipped configuration** signed on 150 of 150 users from one source
address and drove a 150-session workload with **zero** `429`. The earlier arrangement
counted every request against one per-address budget, which meant 150 operators behind
a single NAT address shared it — that was a product defect, not a harness artifact, and
raising the committed budget for the duration of a run had been hiding it. Per-address
counting still throttles the pre-session credential path, where the abuse it exists to
blunt actually happens, and it was verified still enforcing above its budget.

**Fixture impact of a run**, verified against the database afterwards: the security
table returns to its seeded 10 rows with no harness user left behind, because the
add/update/delete cycle is self-cleaning; exactly `WRITE_VUS` customer records (5)
carry the normalised address, phone and FICO values the account-update screen
requires; the confirmed add-transaction path appended 115 rows, leaving the seeded
300 untouched; and **no batch job was launched**, confirming the report request
never passed its confirmation gate. Re-seed the database to restore the fixture
byte-for-byte.

The same reconciliation was re-run after the contended-host runs above and agreed:
`security_users` back to the seeded 10 with no harness user left behind; customers,
accounts and cards unchanged at 50 each; `transactions` grown by the confirmed
add-transaction path alone with **zero duplicate `tran_id` values**, so the
sequence-backed id generation of AAP 0.6.5 held under 150-user concurrency; and
`batch_job_execution` empty. One difference worth naming: the count of customer records
carrying the normalised fields tracks the size of the update mix, so a run with a
different `WRITE_VUS` split touches a different set of accounts — six across those runs
rather than five.

A finding in its own right: **no seeded account can be rewritten through the
account-update screen unchanged.** All 50 are refused — 21 for a FICO score outside
300-850, 22 for a telephone area code absent from the North American lookup table,
and the remainder for a state code or state/zip pair the cross-edit rejects. The
seeded fixture is faithful to the legacy customer file, and that file simply carries
values `COACTUPC` itself does not accept, so an operator arriving at any of these
accounts must correct the flagged field before the rewrite is taken. This is
preserved behaviour, not a defect, and it is why the harness submits five corrected
customer fields on the accounts its update mix owns.

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

**Memory footprint — AAP 0.7.1 target: increase under 10%.**

*The bound.* All nine service images declare one identical policy —
`JAVA_OPTS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0
-XX:MaxRAMPercentage=60.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=100"` — and both
delivered topologies declare the memory limit those percentages resolve against:
`deploy.resources.limits.memory` in `docker-compose.yml` and `resources.limits.memory`
in every `k8s/deployment-*.yaml`, at **1 GiB per service**. The limit, not a JVM
flag, is what fixes the heap: change the limit and the heap follows it.

Both halves are required, and measuring the same `card-service` image three ways in
a real container shows why:

| Image | Container | PID 1 | Heap ceiling | Collector |
|-------|-----------|-------|-------------:|-----------|
| before | no memory limit (Compose as delivered) | `java -jar /app/app.jar` | 30,688 MiB | G1 |
| before | `--memory=1g --cpus=1` (the Kubernetes limits) | `java -jar /app/app.jar` | 248 MiB | **Serial** |
| after | `--memory=1g --cpus=1` | `java -XX:+UseContainerSupport …-XX:+UseG1GC… -jar /app/app.jar` | 616 MiB | G1 |

One artifact had two footprints and two collectors depending only on where it ran.
With no limit the JVM read the host, capped its view at `MaxRAM` (128 GiB) and sized
a 2 GiB initial and ~30 GiB maximum heap; inside the 1-CPU limit its own ergonomics
selected the single-threaded Serial collector and a 248 MiB heap. Pinning both makes
the profile identical in both runtimes. Measured in the 1 GiB container after the
policy: **400 MiB resident, 39% of the limit**, longest GC pause 68 ms.

*The measurement.* Nine services run as local JVMs against containerised
PostgreSQL 18 and Redis 8, each JVM given `-XX:MaxRAM=1g` so its ergonomics resolve
exactly as they do inside the 1 GiB container limit. `VmRSS` is read from
`/proc/<pid>/status`; heap and non-heap from each service's authenticated
`/actuator/prometheus` (`jvm_memory_max_bytes`, `jvm_memory_used_bytes`). Figures are
taken at three points: warm and idle before the run, immediately after the two-minute
150-user harness run above, and again after a 180-second cooldown.

| Service | Heap ceiling | RSS idle | RSS after the run | RSS after cooldown | Heap in use |
|---------|-------------:|---------:|------------------:|-------------------:|------------:|
| api-gateway | 616 MiB | 340 MiB | 618 MiB | 602 MiB | 175 MiB |
| transaction-service | 616 MiB | 439 MiB | 568 MiB | 562 MiB | 84 MiB |
| account-service | 616 MiB | 461 MiB | 560 MiB | 559 MiB | 107 MiB |
| user-service | 616 MiB | 460 MiB | 545 MiB | 545 MiB | 55 MiB |
| card-service | 616 MiB | 397 MiB | 518 MiB | 518 MiB | 79 MiB |
| billpay-service | 616 MiB | 484 MiB | 494 MiB | 494 MiB | 85 MiB |
| reporting-service | 616 MiB | 448 MiB | 475 MiB | 482 MiB | 66 MiB |
| auth-service | 616 MiB | 435 MiB | 466 MiB | 467 MiB | 67 MiB |
| batch-service | 616 MiB | 459 MiB | 462 MiB | 463 MiB | 60 MiB |
| **Nine-service total** | **5,544 MiB** | **3,923 MiB** | **4,706 MiB** | **4,692 MiB** | **778 MiB** |

Every service's post-load working set is between **45% and 60% of its 1 GiB limit**,
so the limit holds with headroom rather than by luck, and the same figures measured
before the policy totalled 5,427 MiB at idle — the bound removes about 1.5 GiB of
committed-but-unused heap across the tier. Redis is bounded on the same principle:
`--maxmemory 192mb --maxmemory-policy volatile-ttl` inside a 256 MiB limit, verified
reporting `maxmemory 201326592` where the delivered configuration reported `0`
(unlimited). `volatile-ttl` evicts only keys that carry a TTL — every key Spring
Session writes — nearest-to-expire first, so a full session store sheds the sessions
closest to lapsing instead of being OOM-killed with all of them.

*The target.* The AAP states the 10% figure as a DELTA against the mainframe region
this application replaces, and no z/OS region is available here, so that percentage
remains **unverified** — publishing one would be inventing it. What replaces it is
the thing the delta was there to guarantee: a footprint that cannot grow without
bound. It is now declared (1 GiB per service, 9 GiB for the tier), enforced by the
container runtime rather than by convention, and measured at 45-60% of the
declaration. The table above is the baseline and the paragraph above it is the
method, so the 10% gate is computable against this release from here on.

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
