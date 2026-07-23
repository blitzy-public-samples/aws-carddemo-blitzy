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
[decision log](./docs/decision-log.md), and the complete source-to-target
mapping (100% bidirectional coverage) is recorded in the
[traceability matrix](./docs/traceability-matrix.md). This README intentionally
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
│   └── traceability-matrix.md   Bidirectional COBOL <-> Java mapping (100%)
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
own `src/main/resources/application.yml`, `logback-spring.xml`, Flyway
`db/migration/` scripts (for the tables it owns), `src/test/java` suite, and
`Dockerfile`.

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

From the repository root, build and start the entire stack — PostgreSQL, Redis,
all services, the frontend, Prometheus, and Grafana — with a single command:

```bash
docker compose up --build
```

On first start, Flyway applies the schema and seed migrations against the
PostgreSQL container automatically. To stop and remove the stack:

```bash
docker compose down
```

Add `-v` to also drop the PostgreSQL and Redis volumes for a clean slate:

```bash
docker compose down -v
```

### Reachable URLs

| Component | URL |
| :-------- | :-- |
| Frontend (React SPA) | <http://localhost:3000> |
| API gateway | <http://localhost:8080> |
| Prometheus | <http://localhost:9090> |
| Grafana | <http://localhost:3001> |
| Per-service health probe | `http://localhost:<service-port>/actuator/health` |
| Per-service metrics | `http://localhost:<service-port>/actuator/prometheus` |

### Default Login Credentials

The following users are seeded by Flyway from the legacy security data. The
credentials match the legacy application:

| User ID | Password | Role |
| :------ | :------- | :--- |
| `ADMIN001` | `PASSWORD` | `ROLE_ADMIN` |
| `USER0001` | `PASSWORD` | `ROLE_USER` |

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

| Migration | Purpose |
| :-------- | :------ |
| `V1__create_schema.sql` | Creates the 10 tables plus foreign-key constraints and secondary indexes (derived from the VSAM catalog and record copybooks). |
| `V2__seed_reference_data.sql` | Seeds reference data: 7 transaction types, 18 transaction categories, and 51 disclosure groups. |
| `V3__seed_test_data.sql` | Seeds test data: 50 customers, accounts, cards, and cross-references; 100 category balances; and transactions. |

Seed data is derived from the human-readable ASCII fixed-width fixtures in
[`app/data/ASCII/`](./app/data/ASCII) (`custdata.txt`, `acctdata.txt`,
`carddata.txt`, `cardxref.txt`, `tcatbal.txt`, `dailytran.txt`, `discgrp.txt`,
`trancatg.txt`, `trantype.txt`). Each service owns the migrations for the tables
it is responsible for.

### Financial Precision

COBOL monetary values are stored as packed decimal (`COMP-3`). To keep financial
output byte-identical, every such field maps to Java `BigDecimal` backed by a
PostgreSQL `NUMERIC(p,s)` column at the **exact** declared scale — for example
account balance and limit fields to `NUMERIC(12,2)` and transaction amount to
`NUMERIC(11,2)`. Arithmetic preserves the original rounding (fixed scale with
`RoundingMode.HALF_UP`) and calculation order, so results match the mainframe
exactly. The rounding and precision rules are not changed by this migration.

---

## Observability

The application is not considered complete until it is observable. Every service
ships the following, verifiable in the local Docker Compose environment:

- **Structured logging** — JSON logs with correlation IDs propagated through the
  MDC, configured in each service's `logback-spring.xml`.
- **Distributed tracing** — trace context propagated across service boundaries
  via Micrometer Tracing with an OpenTelemetry bridge.
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
| Backend | JUnit 5 + Testcontainers (real PostgreSQL/Redis) | `mvn test` |
| Frontend | Jest + React Testing Library | `cd frontend && npm test` |

The suite provides at least **50 unit-test scenarios** across the services and
exercises all **17 UI workflows**, preserving the behavior of the legacy
application. Testcontainers spins up real PostgreSQL and Redis instances so
integration tests run against the same technology used in production.

Run the full backend suite from the repository root:

```bash
mvn test
```

Run the frontend suite:

```bash
cd frontend
npm test
```

---

## Documentation

- **[Decision log](./docs/decision-log.md)** — every non-trivial migration
  decision, with alternatives, rationale, and risk. Rationale lives here, not in
  code comments.
- **[Traceability matrix](./docs/traceability-matrix.md)** — the bidirectional
  mapping between legacy COBOL constructs and their Java/React implementations,
  at 100% coverage in both directions.

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
