## account-service — CardDemo Account Management (Feature F-003)

A standalone **Java 17 / Spring Boot 3.5.16** REST microservice that migrates the **Account Management vertical slice (Feature F-003)** of the CardDemo application off the legacy COBOL / CICS / VSAM stack. It re-expresses the *account inquiry* and *account update* capabilities as a clean, layered Spring Boot service backed by PostgreSQL, while preserving the exact business behavior of the two legacy programs it replaces.

- [Overview](#overview)
- [Technologies Used](#technologies-used)
- [Architecture](#architecture)
- [API Reference](#api-reference)
  - [Endpoints](#endpoints)
  - [Example Requests and Responses](#example-requests-and-responses)
  - [Status Codes](#status-codes)
- [Data Model and Schema](#data-model-and-schema)
  - [Field Mapping](#field-mapping)
  - [Schema Decisions](#schema-decisions)
  - [Account Identifier Type](#account-identifier-type)
- [Build, Run, and Docker](#build-run-and-docker)
  - [Build](#build)
  - [Run Locally](#run-locally)
  - [Configuration Profiles](#configuration-profiles)
  - [Docker](#docker)
  - [Database Migrations](#database-migrations)
- [Testing](#testing)
- [API Docs, Health, Parity, and Security](#api-docs-health-parity-and-security)
  - [API Documentation](#api-documentation)
  - [Health](#health)
  - [Behavioral Parity](#behavioral-parity)
  - [Security Deviations](#security-deviations)
  - [Authentication and Authorization Posture](#authentication-and-authorization-posture)
- [Legacy Lineage](#legacy-lineage)
- [License](#license)

<br/>

## Overview

`account-service` is a net-new, self-contained Maven module that reproduces the account-management behavior of two legacy CardDemo COBOL programs as an idiomatic Spring Boot 3.x REST service. The CICS 3270 screen contract is replaced by a JSON contract, the 300-byte VSAM `ACCTFILE` record becomes a JPA-managed relational row, and every legacy business rule (active-status domain, monetary range and precision, date validity, field immutability, and optimistic concurrency) is reproduced *behaviorally* rather than ported verbatim.

The module replaces the following two legacy transactions:

| Legacy tx | Legacy program | New endpoint |
|-----------|----------------|--------------|
| CAVW (Account View) | `COACTVWC.cbl` | `GET /api/v1/accounts/{accountId}` |
| CAUP (Account Update) | `COACTUPC.cbl` | `PUT /api/v1/accounts/{accountId}` |

This service is introduced as a **new sibling of the legacy `app/` tree**. Under the Minimal Change Clause, **no COBOL, copybook (`.cpy`), BMS map (`.bms`), JCL, or CICS artifact is modified** — the legacy sources are consumed strictly by reference to extract behavior, and the migrated data structure is the account record alone. Customer-record maintenance, card/transaction/cross-reference processing, and the entire 3270 / CICS / BMS runtime layer are intentionally out of scope for this slice.

<br/>

## Technologies Used

| Technology | Version / Notes |
|------------|-----------------|
| Java | 17 (LTS) — compile and runtime baseline |
| Spring Boot | 3.5.16 (parent `spring-boot-starter-parent`) |
| Spring Web MVC | REST controllers, Jackson JSON, embedded Tomcat (`spring-boot-starter-web`) |
| Spring Data JPA / Hibernate | Entity mapping, repositories, `@Version` optimistic locking (`spring-boot-starter-data-jpa`) |
| Jakarta Bean Validation (JSR-380) | Structural request validation (`spring-boot-starter-validation`) |
| PostgreSQL | 16 / 17 (Amazon RDS PostgreSQL migration target); `org.postgresql:postgresql` JDBC driver |
| Flyway | `flyway-core` + `flyway-database-postgresql` (versioned schema migrations at startup) |
| springdoc-openapi | 2.8.17 — OpenAPI 3 generation + Swagger UI (`springdoc-openapi-starter-webmvc-ui`) |
| Spring Boot Actuator | `spring-boot-starter-actuator`; only the `/actuator/health` endpoint is exposed |
| Maven | Build and repackage to an executable jar (`spring-boot-maven-plugin`) |
| Docker | Multi-stage build; runtime base image `eclipse-temurin:17-jre` |
| Testing | JUnit 5, Mockito, AssertJ, Spring MockMvc (`spring-boot-starter-test`); Testcontainers PostgreSQL (`org.testcontainers:postgresql` + `junit-jupiter`) |

<br/>

## Architecture

The service applies a clean **layered architecture** that separates concerns the legacy program interleaves across screen-handling and edit paragraphs. Dependencies flow in one direction — Controller → Service (+ Validator) → Repository → Entity — with `AccountMapper` bridging the entity and DTO boundaries. **Constructor injection** is used throughout, and all classes live under the base package `com.aws.carddemo.account`.

- **`AccountController`** — HTTP and JSON serialization; maps requests to service calls and exceptions to status codes.
- **`AccountService`** — `@Transactional` business logic: read, validate, and update. The account id is taken solely from the URL path (the request body carries no id), and the service enforces field immutability — the account id and group id are never writable.
- **`AccountValidator`** — reproduces the legacy field edits (active status `Y`/`N`, signed decimal range and scale, strict date validity).
- **`AccountRepository`** — Spring Data JPA `JpaRepository<Account, String>`; `findById` corresponds to the legacy `READ` by key and `save` to the `REWRITE`.
- **`Account`** — JPA `@Entity` mapped to the `accounts` table, with a `@Version` column for optimistic locking.
- **`AccountMapper`** — isolates entity↔DTO conversion and presentation formatting (scale-2 currency, ISO dates).
- **`GlobalExceptionHandler`** — a single `@RestControllerAdvice` mapping domain exceptions to `400` / `404` / `409` responses.

```text
REST Client
    │  HTTP/JSON  (GET | PUT  /api/v1/accounts/{accountId})
    ▼
AccountController
    ▼
AccountService  ──uses──▶ AccountValidator
    │
    ├──maps via──▶ AccountMapper  (Account ⇆ AccountResponse / AccountUpdateRequest)
    ▼
AccountRepository  (Spring Data JPA)
    ▼
PostgreSQL  ──▶  accounts  table
```

<br/>

## API Reference

The service exposes exactly two endpoints, both under the base path `/api/v1/accounts`. All payloads are JSON. Monetary fields are exact decimals with a fixed scale of 2 (`BigDecimal` / `NUMERIC(12,2)`) rendered in plain (non-scientific) notation, and dates use the ISO `YYYY-MM-DD` format.

### Endpoints

| Method | Path | Description | Success | Error responses |
|--------|------|-------------|---------|-----------------|
| `GET` | `/api/v1/accounts/{accountId}` | Retrieve a single account by its 11-digit id | `200 OK` with `AccountResponse` | `400` (id not a non-zero 11-digit numeric), `404` (not found) |
| `PUT` | `/api/v1/accounts/{accountId}` | Update the editable fields of an existing account | `200 OK` with the updated `AccountResponse` | `400` (validation failure), `404` (not found), `409` (version conflict) |

- **`GET /api/v1/accounts/{accountId}`** — reproduces the read-by-key behavior of the legacy view program `COACTVWC` (transaction `CAVW`). Returns `200` with the full account read model, `404` when the id does not exist, and `400` when `{accountId}` is not a non-zero 11-digit numeric string (an all-zeros id such as `00000000000` is rejected, reproducing the legacy `1210-EDIT-ACCOUNT` non-zero key check).
- **`PUT /api/v1/accounts/{accountId}`** — reproduces the edit / validate / lock / rewrite behavior of the legacy update program `COACTUPC` (transaction `CAUP`). Returns `200` with the updated account, `400` on any validation failure, `404` when the id does not exist, and `409` when the supplied `version` is stale (another writer changed the record first).

### Example Requests and Responses

**`GET /api/v1/accounts/00000000001` → `200 OK`** (values shown are the migrated seed for account `00000000001`):

```json
{
  "accountId": "00000000001",
  "activeStatus": "Y",
  "currentBalance": 194.00,
  "creditLimit": 2020.00,
  "cashCreditLimit": 1020.00,
  "openDate": "2014-11-20",
  "expirationDate": "2025-05-20",
  "reissueDate": "2025-05-20",
  "currentCycleCredit": 0.00,
  "currentCycleDebit": 0.00,
  "addressZip": "A000000000",
  "groupId": "",
  "version": 0
}
```

`AccountResponse` is the read projection. It includes `accountId`, `groupId`, `addressZip`, and the current `version`; the three date fields are ISO strings and all monetary values carry exactly two decimal places. (`groupId` is read-only and is blank for this seed record.)

**`PUT /api/v1/accounts/00000000001`** request body — an `AccountUpdateRequest`:

```json
{
  "activeStatus": "Y",
  "currentBalance": 194.00,
  "creditLimit": 2500.00,
  "cashCreditLimit": 1020.00,
  "openDate": "2014-11-20",
  "expirationDate": "2026-05-20",
  "reissueDate": "2025-05-20",
  "currentCycleCredit": 0.00,
  "currentCycleDebit": 0.00,
  "addressZip": "A000000000",
  "version": 0
}
```

`AccountUpdateRequest` intentionally **omits `accountId` and `groupId`**, so the request body structurally cannot change them. The account id is immutable in the legacy system as well. The **group id, however, is an intentional modern tightening**: on the legacy `COACTUP` BMS map the group-id field is *unprotected* (`UNPROT`) and the legacy `COACTUPC` rewrite path does persist a changed group id — but this migration makes it **read-only**, per AAP §0.7.2, which documents the account id and group id as display-only / PROTECTED for the modernized service. This deviation is called out here so the hardening is explicit. The request **includes `version`**: the client must send the `version` it last read so the server can detect a concurrent modification. On success the response is the updated `AccountResponse` with an incremented `version`.

### Status Codes

| Status | Meaning |
|--------|---------|
| `200 OK` | Read succeeded, or update applied successfully (response carries the current/updated `AccountResponse`). |
| `400 Bad Request` | Validation failure — e.g. `activeStatus` not `Y`/`N`, a monetary value out of range or carrying more than two decimal places, an invalid date, or an `{accountId}` that is not a non-zero 11-digit numeric value (an all-zeros id such as `00000000000` is rejected, reproducing the legacy non-zero key check). The account id is taken solely from the URL path (the body carries no id), so there is no path/body id to reconcile. A malformed or mis-typed JSON body is likewise rejected as `400`. |
| `404 Not Found` | No account exists for the supplied `{accountId}` (maps the legacy `NOTFND` path). |
| `409 Conflict` | Optimistic-lock / version conflict — the supplied `version` is stale. The response body message is: `Record updated by another user - please retry`. |

<br/>

## Data Model and Schema

The `accounts` table is migrated from the 300-byte VSAM `ACCTFILE` record defined by copybook `CVACT01Y.cpy`. In the legacy system the file is a key-sequenced data set (KSDS) with `KEYS(11 0)` and `RECORDSIZE(300 300)`; the relational table maps each copybook field to a typed column and adds one control column (`version`) for optimistic locking. Flyway owns the schema, and Hibernate runs with `ddl-auto=validate` so it never alters the tables.

### Field Mapping

| Copybook field | Column (type) | Java attribute |
|----------------|---------------|----------------|
| `ACCT-ID PIC 9(11)` | `account_id VARCHAR(11)` PK (`CHECK (account_id ~ '^[0-9]{11}$')`) | `accountId` |
| `ACCT-ACTIVE-STATUS PIC X(01)` | `active_status CHAR(1)` | `activeStatus` |
| `ACCT-CURR-BAL PIC S9(10)V99` | `current_balance NUMERIC(12,2)` | `currentBalance` |
| `ACCT-CREDIT-LIMIT PIC S9(10)V99` | `credit_limit NUMERIC(12,2)` | `creditLimit` |
| `ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99` | `cash_credit_limit NUMERIC(12,2)` | `cashCreditLimit` |
| `ACCT-OPEN-DATE PIC X(10)` | `open_date DATE` | `openDate` |
| `ACCT-EXPIRAION-DATE PIC X(10)` | `expiration_date DATE` | `expirationDate` |
| `ACCT-REISSUE-DATE PIC X(10)` | `reissue_date DATE` | `reissueDate` |
| `ACCT-CURR-CYC-CREDIT PIC S9(10)V99` | `current_cycle_credit NUMERIC(12,2)` | `currentCycleCredit` |
| `ACCT-CURR-CYC-DEBIT PIC S9(10)V99` | `current_cycle_debit NUMERIC(12,2)` | `currentCycleDebit` |
| `ACCT-ADDR-ZIP PIC X(10)` | `address_zip VARCHAR(10)` | `addressZip` |
| `ACCT-GROUP-ID PIC X(10)` | `group_id VARCHAR(10)` | `groupId` |
| *(new — no legacy equivalent)* | `version BIGINT NOT NULL` | `version` (`@Version`) |

### Schema Decisions

Three schema decisions are deliberate and are called out explicitly:

1. **`address_zip` is preserved.** The prompt's field-mapping table omits `ACCT-ADDR-ZIP PIC X(10)`, but that field carries real data in the seed file (for example `A000000000` on account `00000000001`), so it is retained as `address_zip VARCHAR(10)` to avoid data loss.
2. **The trailing `FILLER PIC X(178)` is dropped.** It is pure record padding with no semantic value and is not carried forward to the relational model.
3. **The copybook typo `EXPIRAION` is corrected.** The legacy field is spelled `ACCT-EXPIRAION-DATE`; the Java attribute and column use the corrected spelling `expirationDate` / `expiration_date`.

### Account Identifier Type

The account key is stored as a **zero-padded `VARCHAR(11)` string primary key** (`account_id`), **not** a numeric `BIGINT`. The legacy key is `ACCT-ID PIC 9(11)` — an 11-digit, zero-padded value (e.g. `00000000001`). A `BIGINT` would silently discard the leading zeros, forcing re-padding at every display, path, and join boundary. A zero-padded `VARCHAR(11)` preserves the key exactly, keeps the REST path parameter identical to the persisted value, and remains join-compatible with the card and cross-reference records that still carry an 11-digit `ACCT-ID` (`CARD-ACCT-ID`, `XREF-ACCT-ID`) and are out of scope for this slice. A `CHECK (account_id ~ '^[0-9]{11}$')` constraint enforces the 11-digit numeric domain at the database level, preserving the legacy `1210-EDIT-ACCOUNT` intent.

<br/>

## Build, Run, and Docker

All commands below are run from the `account-service/` module directory unless noted otherwise.

### Build

```shell
mvn clean package
```

This compiles the sources, runs the unit and controller-slice tests, and produces the executable jar `target/account-service-0.0.1-SNAPSHOT.jar`.

### Run Locally

With a reachable PostgreSQL instance, start the service with either:

```shell
mvn spring-boot:run
```

or, after a build:

```shell
java -jar target/account-service-0.0.1-SNAPSHOT.jar
```

The datasource is configured through the following environment variables, which override the datasource for every profile and are the mechanism used by the `docker` profile:

| Environment variable | Purpose |
|----------------------|---------|
| `SPRING_DATASOURCE_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/carddemo` |
| `SPRING_DATASOURCE_USERNAME` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | Database password |

The service listens on port **8080** by default (Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`, health at `/actuator/health`).

### Configuration Profiles

| Profile | Purpose |
|---------|---------|
| `default` | Local development against a locally running PostgreSQL. |
| `docker` | Container deployment; the datasource is supplied entirely through the `SPRING_DATASOURCE_*` environment variables. |
| `test` | Integration tests; the datasource is provided by a disposable Testcontainers PostgreSQL instance. |

### Docker

The module ships a multi-stage `Dockerfile` (Maven build stage → `eclipse-temurin:17-jre` runtime stage) that exposes port **8080**:

```shell
docker build -t account-service .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/carddemo \
  -e SPRING_DATASOURCE_USERNAME=<user> \
  -e SPRING_DATASOURCE_PASSWORD=<password> \
  account-service
```

#### Reproducible, auditable release builds

The `docker build` above uses **mutable** base-image tags (`maven:3.9-eclipse-temurin-17`
for the build stage and `eclipse-temurin:17-jre` for the runtime stage) and performs an
`apt-get upgrade`, so its inputs move over time. That is intentional for day-to-day and
local builds, but it means identical source does **not** by itself guarantee a
bit-for-bit identical image. For an auditable release, the pipeline **must** pin inputs
and capture supply-chain metadata (CQ-INFRA-1). Both base images are exposed as global
build args — `BUILDER_BASE` and `RUNTIME_BASE` — precisely so they can be pinned by
digest:

```shell
# Release CI: pin BOTH bases to a scanned/remediated digest (never a moving tag).
docker buildx build \
  --build-arg BUILDER_BASE=maven:3.9-eclipse-temurin-17@sha256:<scanned-builder-digest> \
  --build-arg RUNTIME_BASE=eclipse-temurin:17-jre@sha256:<scanned-runtime-digest> \
  --sbom=true --provenance=mode=max \
  -t account-service:<immutable-release-tag> \
  --push account-service/
```

Release policy:

- **Digest-pin both bases.** Release builds MUST supply `BUILDER_BASE` and `RUNTIME_BASE`
  as `@sha256:` digests (not tags), so the toolchain and the runtime are fixed and
  auditable end-to-end.
- **Generate SBOM and provenance.** Emit an SBOM and SLSA provenance for every release
  image (e.g. `docker buildx --sbom --provenance`, or Syft + in-toto/cosign attestations),
  and store them alongside the image so the exact contents are attestable.
- **Require image scanning.** Scan the final image (Trivy / Grype / Amazon ECR scanning)
  and fail the release on any unresolved applicable OS or JRE vulnerability; re-pin to a
  remediated digest and rebuild until the scan is clean.
- **Separate pinned releases from scheduled security rebuilds.** A pinned release is
  frozen for reproducibility; OS/JRE CVEs discovered later are remediated by a *scheduled*
  rebuild that re-pulls the latest patched bases (fresh digests) and re-runs the scan,
  producing a new immutable release tag rather than mutating an existing one.

### Database Migrations

Schema and data are managed by **Flyway**, which runs automatically at application startup and applies, in order:

- `V1__create_accounts_table.sql` — DDL for the `accounts` table (derived from the record layout and the VSAM key definition).
- `V2__seed_accounts.sql` — 50 seed rows migrated from `acctdata.txt`, with the 12-character zoned-decimal-with-overpunch monetary encoding parsed to `NUMERIC` and account ids preserved zero-padded.

Because Flyway owns the schema, Hibernate is configured with `ddl-auto=validate`; it validates the mapping against the Flyway-created tables and never issues DDL of its own.

<br/>

## Testing

The test suite is organized in three layers under `src/test/java/com/aws/carddemo/account/`:

| Layer | Tests | Scope |
|-------|-------|-------|
| Unit | `AccountValidatorTest`, `AccountServiceTest`, `AccountMapperTest` | Business rules, service logic with a mocked repository, and entity↔DTO formatting parity. |
| Controller slice | `AccountControllerTest` (`@WebMvcTest`) | HTTP status codes and the uniform JSON error shape with the web layer only: valid `GET`/`PUT`, path-pattern `400`s (with leaf field-error keys), and — via the shared JSON input-hardening customizer — malformed, oversized, deeply-nested request bodies and strict numeric coercion all rejected as sanitized `400`s, plus the sanitized `500` catch-all, `405`, and `415`. |
| Integration | `AccountApiIntegrationTest` (`@SpringBootTest(RANDOM_PORT)` + Testcontainers) | End-to-end against a real PostgreSQL container provisioned via a lifecycle-managed `@Container` and wired with `@DynamicPropertySource` (hermetic against ambient `SPRING_DATASOURCE_*`, enforced by a `@BeforeEach` connection guard): happy read with scale-2/ISO field parity, `404`, `400` (business-rule bad date and over-range money, plus Bean-Validation missing version) with no mutation, sequential stale-version `409`, a **deterministic concurrent two-writer race** proven both at the repository flush-time `@Version` backstop and across the full HTTP stack (each yielding exactly one commit and one `409`), a happy update with version increment and committed re-read, the database `CHECK`-constraint enforcement, a **full 50-row field-by-field seed-parity oracle** decoded directly from `app/data/ASCII/acctdata.txt`, and a log-hygiene assertion that the JDBC-URL-bearing loggers are pinned to `WARN`. |

Commands:

```shell
mvn test      # unit + controller-slice tests only (fast; no Docker required)
mvn verify    # also runs the Testcontainers integration tests (requires a running Docker daemon)
```

This split is configured explicitly, so the documented lifecycle matches the actual behavior: the Surefire plugin **excludes** `**/*IntegrationTest.java` (so `mvn test` — and therefore `mvn package` — never require Docker), while the Failsafe plugin **includes** `**/*IntegrationTest.java` and binds the `integration-test` and `verify` goals. `AccountApiIntegrationTest` therefore runs — and can fail the build — only during `mvn verify`, which requires a running Docker daemon for Testcontainers.

Test coverage is traceable to the legacy validation rules, including: active status `Y`/`N`; signed monetary range `±9,999,999,999.99` at scale 2; strict date validity (month `01`–`12`, valid day-for-month, the Gregorian leap-year rule for `29 February`, and the year range `1900`–`2099`); not-found on read → `404`; concurrent-change conflict → `409`; and immutability of the account id and group id.

<br/>

## API Docs, Health, Parity, and Security

### API Documentation

Interactive API documentation is generated by springdoc-openapi:

- **Swagger UI** — `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON** — `http://localhost:8080/v3/api-docs`

### Health

Spring Boot Actuator is on the classpath, but only the **health** endpoint is exposed:

- **Health** — `http://localhost:8080/actuator/health` — reports the aggregate service status (for example `{"status":"UP"}`).

The configuration exposes only `health` (`management.endpoints.web.exposure.include: health`, `show-details: never`). Kubernetes **liveness/readiness probe groups are not enabled in configuration**, so `/actuator/health/liveness` and `/actuator/health/readiness` return `404` unless the runtime platform auto-detects and enables them (Spring Boot turns them on automatically when it detects a Kubernetes deployment). Other actuator endpoints — for example `/actuator/metrics` — are **not** exposed and return `404`. Enabling probe groups or exposing any other actuator endpoint would be a deliberate, separately secured configuration change and is out of scope for this slice.

### Behavioral Parity

The service is designed for behavioral parity with the legacy programs rather than mere feature similarity:

- Validation outcomes (accept / reject and the resulting status code) match the legacy field edits.
- Concurrency uses JPA optimistic locking (`@Version`); a stale version yields `409 Conflict` — the modern equivalent of the legacy before-image comparison in `COACTUPC`.
- Both operations preserve the documented **≤ 2-second** response target.

### Security Deviations

The following three security improvements are **intentional deviations** from strict legacy parity (the legacy baseline is demonstration-grade with no encryption). They are documented so the change is explicit:

- **Encryption at rest** — sensitive financial data relies on Amazon RDS encryption at rest (transparent data encryption with AWS KMS-managed keys), rather than the plaintext VSAM storage of the legacy system.
- **Parameterized queries** — all persistence flows through Spring Data JPA / JPQL with bound parameters, eliminating SQL-injection exposure by construction (no string-concatenated SQL).
- **No sensitive data in logs** — the full account number and monetary values are never written to logs in plaintext.

### Authentication and Authorization Posture

This proof-of-concept service is **intentionally unauthenticated**: it implements no application-level authentication or authorization, and both endpoints are open to any caller that can reach the process. This is a deliberate scope decision for the migration slice — the legacy CICS sign-on / RACF surface is **not** reproduced here, and no authentication subsystem is added.

Access control is therefore the responsibility of the **deployment boundary**: in a real deployment this service is expected to sit behind an authenticating and authorizing edge (for example an API gateway, a service mesh, or a network policy) and must not be exposed directly to an untrusted network. Adding an in-process authentication/authorization subsystem is out of scope for this slice and would require separate design and approval.

<br/>

## Legacy Lineage

The following legacy artifacts are **consumed strictly by reference** to extract behavior and structure; none of them is modified by this migration.

| Legacy artifact | Role in the migration |
|-----------------|-----------------------|
| `app/cbl/COACTVWC.cbl` | Account view program (transaction `CAVW`); source of the `GET` read-by-key and not-found semantics. |
| `app/cbl/COACTUPC.cbl` | Account update program (transaction `CAUP`); source of the field edits, record locking, and before-image conflict check. |
| `app/cpy/CVACT01Y.cpy` | The 300-byte `ACCOUNT-RECORD` copybook; authoritative field layout for the `accounts` table. |
| `app/cpy/CSUTLDPY.cpy` | Date-validation procedure copybook; source of the month / day / leap-year / century rules. |
| `app/bms/COACTVW.bms` | 3270 view map; source of the displayed field inventory (screen retired). |
| `app/bms/COACTUP.bms` | 3270 update map; source of the editable field inventory and protected-attribute intent (screen retired). |
| `app/data/ASCII/acctdata.txt` | 50-record, 300-byte-per-record seed file used for schema seeding and parity testing. |
| `app/jcl/ACCTFILE.jcl` | VSAM `IDCAMS DEFINE CLUSTER` for `ACCTFILE`; reference for the KSDS key and record-size parameters. |

The following artifacts are **reference-only** — they inform design decisions but are never ported:

| Reference-only artifact | Why it matters |
|-------------------------|----------------|
| `app/cpy/CVACT02Y.cpy` | 11-digit `CARD-ACCT-ID`; justifies `VARCHAR(11)` join compatibility with the card record. |
| `app/cpy/CVACT03Y.cpy` | 11-digit `XREF-ACCT-ID`; join compatibility with the cross-reference record. |
| `app/cbl/CSUTLDTC.cbl` | Legacy Language Environment date safety-net; reimplemented natively with `java.time` rather than invoked. |

> Note: the 3270 / BMS screens (`COACTVW.bms`, `COACTUP.bms`) are **retired** in this migration. They appear here only as legacy lineage; the service has no terminal or graphical UI — its interface is the REST/JSON contract documented above.

<br/>

## License

This module is released under the **Apache License 2.0**, consistent with the CardDemo repository-root `LICENSE`.
