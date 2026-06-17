# Blitzy Project Guide — AWS CardDemo COBOL→Java Migration

> **Project:** AWS CardDemo — Mainframe (COBOL/CICS/VSAM/JCL/BMS) → Spring Boot 3.2.12 / Java 17 Monolith
> **Branch:** `blitzy-e3b639fc-72d1-4275-a4c0-18b80748bc37` &nbsp;|&nbsp; **HEAD:** `da8ceb32`
> **Brand legend:** <span style="color:#5B39F3">■</span> Completed / AI Work (`#5B39F3`) &nbsp;·&nbsp; <span style="color:#B23AF2">■</span> Headings/Accents (`#B23AF2`) &nbsp;·&nbsp; □ Remaining (`#FFFFFF`) &nbsp;·&nbsp; <span style="color:#A8FDD9">■</span> Highlight (`#A8FDD9`)

---

## 1. Executive Summary

### 1.1 Project Overview

This project performs a complete technology-stack migration of the AWS CardDemo mainframe credit-card sample application — originally implemented in COBOL, CICS, VSAM, JCL, and BMS on z/OS — into a single monolithic **Spring Boot 3.2.12 application running on Java 17**. The target audience is the engineering team modernizing the legacy estate and the downstream API consumers that replace the retired 3270 terminal users. Business behavior is preserved at **100% functional parity** across the nine feature domains (F-001–F-009): authentication, menu routing, account/card/transaction management, bill payment, reporting, user administration, and batch processing. The technical scope spans five concurrent sub-migrations — language (COBOL→Java), transaction monitor (CICS→Spring MVC REST), data store (VSAM→PostgreSQL via JPA), batch (JCL→Spring Batch), and presentation (BMS→JSON REST; the UI is retired, not reimplemented).

### 1.2 Completion Status

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'pie1':'#5B39F3','pie2':'#FFFFFF','pieStrokeColor':'#B23AF2','pieStrokeWidth':'2px','pieOuterStrokeWidth':'2px','pieTitleTextSize':'16px','pieSectionTextColor':'#000000'}}}%%
pie showData
    title Project Completion — 87.1% Complete
    "Completed Work (AI)" : 567
    "Remaining Work" : 84
```

| Metric | Value |
|--------|-------|
| **Total Project Hours** | **651 h** |
| Completed Hours (AI + Manual) | **567 h** (AI ≈ 567 h · Manual ≈ 0 h) |
| Remaining Hours | **84 h** |
| **Percent Complete** | **87.1%** &nbsp;( 567 / 651 × 100 ) |

> Completion is computed using the AAP-scoped, hours-based methodology: only work defined in the Agent Action Plan plus standard path-to-production activities is counted. All AAP code-authoring deliverables are complete and gate-verified; the remaining 84 h is entirely human path-to-production work.

### 1.3 Key Accomplishments

- ✅ **Full layered monolith delivered** — 92 main Java classes across `entity / repository / service / controller / security / batch / config / dto / mapper / exception / util`, plus 41 test classes (≈ 34,830 net-new lines).
- ✅ **10 VSAM datasets → 10 PostgreSQL tables** with FK constraints, sequences, and 3 indexes via Flyway `V1__schema.sql`; reference/master/user data seeded in `V2`–`V4`.
- ✅ **17 CICS online programs → 8 REST controllers** exposing JSON contracts; **5 JCL batch programs → 5 Spring Batch jobs**.
- ✅ **Security hardened within parity** — CICS COMMAREA → stateless JWT (HS256, 1 h); plaintext password → BCrypt strength-12; role-based authorization (`hasRole('ADMIN')`).
- ✅ **Financial & control-flow parity preserved** — reject codes 100/101/102/103/109, 430-byte DALYREJS layout, per-category interest `(bal×rate)/1200` HALF_UP, cycle-based overlimit, `@Version` → HTTP 409, fixed page size 7, dual `orig_ts`/`proc_ts`, CVV/SSN suppression.
- ✅ **Quality gates passed** — 463 tests (0 failures), JaCoCo LINE 85.01% / INSTRUCTION 84.13% (≥80% gate), zero compile errors/warnings, executable fat jar produced, dev/H2 runtime live-verified.

### 1.4 Critical Unresolved Issues

| Issue | Impact | Owner | ETA |
|-------|--------|-------|-----|
| _None blocking._ All five production-readiness gates pass; zero failing tests, zero compile errors/warnings, zero unresolved code defects. | No release blocker from the AAP code base | — | — |
| Prod profile not yet validated against a live PostgreSQL 15.x (only dev/H2 was runtime-tested) | Must verify before production cutover (not a code defect) | Platform / DevOps | 1–2 days |

### 1.5 Access Issues

| System / Resource | Type of Access | Issue Description | Resolution Status | Owner |
|-------------------|----------------|-------------------|-------------------|-------|
| PostgreSQL 15.x (prod) | Database instance | No production database provisioned yet; prod profile is config-wired but not connected | Open — pending provisioning | Platform / DevOps |
| Secrets manager / vault | Credential store | `JWT_SECRET` (≥256-bit) and `SPRING_DATASOURCE_*` must be stored/rotated in a vault | Open — pending ops setup | Security / DevOps |
| Maven Central | Build dependencies | Resolved — all dependencies present in warmed `~/.m2` cache (292 MB); build succeeds fully offline (`mvn -o clean verify`) | Resolved | — |
| Source repository | Git read/write | Resolved — agent has full access; 26 commits on branch | Resolved | — |

> No access issues blocked autonomous build, test, or runtime validation. The open items above are standard production-onboarding prerequisites, not current blockers.

### 1.6 Recommended Next Steps

1. **[High]** Provision PostgreSQL 15.x and validate the prod profile end-to-end (Flyway V1–V4 + smoke test) — *HT-1*.
2. **[High]** Stand up secrets management for `JWT_SECRET` (≥256-bit) and datasource credentials — *HT-2*.
3. **[Medium]** Build the CI/CD pipeline (`mvn clean verify` + coverage gate + package + publish) — *HT-3*.
4. **[Medium]** Containerize the application and author deployment manifests with health probes — *HT-4*.
5. **[Low]** Conduct UAT/stakeholder acceptance of the REST contracts that replace the retired 3270 screens — *HT-10*.

---

## 2. Project Hours Breakdown

### 2.1 Completed Work Detail

| Component | Hours | Description |
|-----------|------:|-------------|
| Build & Project Configuration | 8 | `pom.xml` (Spring Boot 3.2.12 parent, Java 17, CVE-remediation BOM overrides, JaCoCo ≥80% gate, fat-jar packaging) |
| Domain Entity Layer | 36 | 10 JPA entities ← record copybooks; `@Version`, composite keys, NUMERIC(12,2), dual timestamps |
| Repository Layer | 16 | 10 Spring Data repositories; VSAM browse → derived `Pageable` queries (`findByCardAcctId`, `findByAcctIdOrderByOrigTs`, …) |
| Online Service Layer | 66 | Auth, Account, Card, Transaction, BillPayment, Report, User — business rules ported from CICS online programs |
| Support Service Layer | 37 | DateValidation (CSUTLDTC), Message, InterestCalculation (CBACT04C), Statement (CBSTM03A/B) |
| REST Controller Layer | 56 | 8 controllers ← 17 online programs; JSON contracts, validation, `@PreAuthorize` role gating |
| Security Layer | 24 | `SecurityConfig`, `JwtTokenProvider` (HS256), `JwtAuthenticationFilter`, `CustomUserDetailsService`; COMMAREA→JWT, BCrypt-12 |
| Spring Batch Jobs | 66 | 5 jobs: transaction posting (reject codes + 430-byte DALYREJS), interest, statement, account/customer refresh |
| DTO & Mapper Layer | 36 | 21 DTOs + 5 mappers; CVV never serialized, SSN last-4 suppression |
| Exception Handling | 10 | `GlobalExceptionHandler` `@RestControllerAdvice` + 4 domain exceptions → 400/401/403/404/409 |
| Configuration Layer | 14 | DataSource, Batch, Jackson (BigDecimal/LocalDate), Async, OpenAPI, report-job submitter |
| Utility Layer | 6 | `TranIdGenerator` (sequence + LPAD-16), `CardDemoConstants` |
| Resource Profiles & Logging | 10 | `application.yml` + dev/prod profiles; `logback-spring.xml` PII/CVV masking |
| Database Migrations & Seed Data | 26 | Flyway V1 schema (10 tables/FKs/sequences/3 indexes) + V2/V3/V4 seeds (BCrypt user seed generated) |
| Test Suite | 120 | 41 classes / 463 tests (MockMvc, @DataJpaTest, @SpringBatchTest, security, parity); ≥80% line coverage |
| Documentation | 6 | `README.md` updated with Java build/run instructions (legacy steps marked) |
| QA Validation & Remediation | 30 | Multi-round checkpoint fixes (C1/C3/C4, CKPT-2/3/5/8, FINAL/FINAL_ALT) + SecurityConfig deprecation fix |
| **TOTAL** | **567** | **= Completed Hours in §1.2** |

### 2.2 Remaining Work Detail

| Category | Hours | Priority |
|----------|------:|----------|
| Production PostgreSQL provisioning & prod-profile validation | 10 | High |
| Production secrets management (`JWT_SECRET`, DB credentials) | 4 | High |
| CI/CD pipeline setup (build + verify + coverage gate + package + publish) | 12 | Medium |
| Containerization & deployment (Dockerfile, compose, manifests, probes) | 10 | Medium |
| Integration testing against real PostgreSQL (batch, concurrency, sequence) | 8 | Medium |
| Production observability (log aggregation, metrics export, alerting) | 8 | Medium |
| Security review & dependency CVE scanning | 6 | Medium |
| Performance & load testing | 6 | Medium |
| Flyway production hardening (baseline/validate strategy, warning review) | 3 | Medium |
| UAT & stakeholder acceptance (REST contracts vs retired 3270 screens) | 8 | Low |
| JaCoCo branch-coverage uplift (optional org standard) | 6 | Low |
| API documentation publishing (OpenAPI/springdoc) | 3 | Low |
| **TOTAL** | **84** | **= Remaining Hours in §1.2 & §7** |

> **Reconciliation:** §2.1 (567 h) + §2.2 (84 h) = **651 h** = Total Project Hours in §1.2. ✔

---

## 3. Test Results

All tests below originate from Blitzy's autonomous validation logs and were independently re-verified this session by parsing `target/surefire-reports/*.xml` (54 suites) and `target/site/jacoco/jacoco.xml`.

| Test Category | Framework | Total Tests | Passed | Failed | Coverage % | Notes |
|---------------|-----------|------------:|-------:|-------:|-----------:|-------|
| Controller / API | JUnit 5 + MockMvc + spring-security-test | 86 | 86 | 0 | — | REST contract tests for all 8 controllers |
| Service / Unit | JUnit 5 + Mockito + AssertJ | 263 | 263 | 0 | — | Business logic incl. 166 `DateValidationService` (CSUTLDTC) parity cases |
| Repository / Data JPA | JUnit 5 + `@DataJpaTest` (H2) | 48 | 48 | 0 | — | VSAM-pattern queries, pagination (page size 7) |
| Batch | JUnit 5 + `@SpringBatchTest` | 24 | 24 | 0 | — | Posting (reject codes + 430-byte DALYREJS), interest, statement, refresh |
| Security | JUnit 5 + spring-security-test | 26 | 26 | 0 | — | JWT HS256, BCrypt-12, authz 401/403, secret-guard |
| Financial Parity & Concurrency | JUnit 5 | 10 | 10 | 0 | — | `FinancialParityTest` + `AccountConcurrencyTest` (HTTP 409) |
| Exception / Serialization | JUnit 5 | 6 | 6 | 0 | — | `GlobalExceptionHandler` + `ErrorResponse` |
| **TOTAL** | — | **463** | **463** | **0** | **85.01% line (bundle)** | 0 errors, 0 skipped; no `@Disabled`/`@Ignore` |

**Coverage (JaCoCo bundle, aggregate across all tests):**

| Counter | Covered / Total | % | Gated? |
|---------|-----------------|--:|--------|
| LINE | 1,985 / 2,335 | **85.01%** | ✅ ≥80% (enforced) |
| INSTRUCTION | 8,625 / 10,252 | **84.13%** | ✅ ≥80% (enforced) |
| METHOD | 525 / 646 | 81.27% | Informational |
| CLASS | 88 / 90 | 97.78% | Informational |
| BRANCH | 427 / 753 | 56.71% | Informational (see Risk T2 / HT-11) |

> Coverage is measured at the bundle level by JaCoCo, not per test category; the aggregate figures above apply to the whole module.

---

## 4. Runtime Validation & UI Verification

**Runtime health (dev profile / embedded H2 — live-exercised this session):**

- ✅ **Application boot** — fat jar `target/carddemo-1.0.0.jar` started in **6.30 s**; Tomcat on port 8080.
- ✅ **Database migration** — Flyway applied V1→V4 ("Successfully applied 4 migrations, now at version v4").
- ✅ **Health endpoint** — `GET /actuator/health` → `200 {"status":"UP","groups":["liveness","readiness"]}`.
- ✅ **Clean shutdown** — SIGTERM → graceful stop; port 8080 freed.

**API integration outcomes (live-verified):**

- ✅ **Authentication** — `POST /auth/signon` (ADMIN001 / USER0001) → `200` with JWT; decoded token carries COMMAREA→JWT claims `CDEMO-USER-ID` and `CDEMO-USER-TYPE` (HS256 header confirmed).
- ✅ **Authorization** — wrong password → `401`; protected `GET /cards` without token → `401`; `/users` with user token → `403`, with admin token → `200`.
- ✅ **Pagination parity** — `GET /cards?page=0` → exactly **7 items**, `size=7`, `totalElements=50`.
- ✅ **PII suppression** — card responses expose **no CVV** (keys: `cardAcctId`, `cardNum`, `activeStatus`); user responses expose **no password**; SSN surfaces only as `ssnLastFour`.
- ⚠ **Production profile (PostgreSQL)** — config-wired and seed-ready, but **not yet validated against a live PostgreSQL 15.x** (human task HT-1).

**UI verification:** ❎ **Not applicable by design.** The AAP explicitly retires the 3270/BMS presentation tier; the deliverable is a JSON REST API with no web or terminal UI. BMS field definitions were mined only to shape request/response DTOs and field-level validation.

---

## 5. Compliance & Quality Review

| AAP Deliverable / Rule | Benchmark | Status | Progress |
|------------------------|-----------|--------|----------|
| Single Spring Boot 3.2.x / Java 17 monolith (no microservices) | Architecture mandate | ✅ Pass | 100% |
| 10 entities ← record copybooks | Data-contract parity | ✅ Pass | 100% |
| 10 repositories (VSAM browse → Pageable) | Repository pattern | ✅ Pass | 100% |
| 11 services (online + batch + utility logic) | Business-logic parity | ✅ Pass | 100% |
| 8 REST controllers ← 17 online programs | API surface | ✅ Pass | 100% |
| 5 Spring Batch jobs ← JCL batch | Batch parity | ✅ Pass | 100% |
| Flyway V1–V4 (schema + seeds, generated user seed) | Migration & seed | ✅ Pass | 100% |
| Reject codes 100/101/102/103/109 | §0.6.1 control flow | ✅ Pass (14 posting tests) | 100% |
| 430-byte DALYREJS (350 + 80 trailer) | §0.6.2 layout | ✅ Pass | 100% |
| Interest `(bal×rate)/1200` HALF_UP + DISCGRP DEFAULT | §0.6.3 precision | ✅ Pass (FinancialParityTest) | 100% |
| Cycle-based overlimit; account-expiry-vs-orig-date | §0.7.3 #5/#6 | ✅ Pass | 100% |
| `@Version` optimistic lock → HTTP 409 | §0.6.6 | ✅ Pass (AccountConcurrencyTest) | 100% |
| Fixed page size 7 | §0.6.4 | ✅ Pass (live + tests) | 100% |
| COMMAREA → JWT (HS256, 1 h) | §0.6.7 | ✅ Pass (SecurityTest + live) | 100% |
| BCrypt strength-12 (was plaintext) | §0.6.7 | ✅ Pass | 100% |
| CVV never serialized/logged; SSN last-4; card/acct-id immutable | §0.6.8 | ✅ Pass (mapper tests + live) | 100% |
| Both `orig_ts` & `proc_ts` persisted | §0.7.3 #10 | ✅ Pass | 100% |
| ≥80% coverage via `mvn verify` (JaCoCo) | §0.7.2 gate | ✅ Pass (LINE 85.01% / INSTR 84.13%) | 100% |
| README updated with Java build/run | §0.4.1.5 (UPDATE) | ✅ Pass | 100% |
| Zero placeholders / stubs / TODOs | Code-quality policy | ✅ Pass (0 found) | 100% |

**Fixes applied during autonomous validation:** `SecurityConfig.java` migrated to the non-deprecated `DaoAuthenticationProvider(UserDetailsService)` constructor (behavior identical; removes deprecation warnings introduced by the spring-security 6.5.11 CVE bump); plus multi-round QA-checkpoint remediation (authz envelopes, JWT secret hardening, error/validation hardening, OpenAPI regression, logback CVE remediation, documentation anchors).

**Outstanding compliance items:** none within AAP scope. Production-grade compliance (dependency CVE scanning in CI, security review/pen-test) is tracked under §2.2 / §6.

---

## 6. Risk Assessment

| Risk | Category | Severity | Probability | Mitigation | Status |
|------|----------|----------|-------------|------------|--------|
| T1 — Prod profile validated only under H2, not live PostgreSQL 15.x (dialect/sequence/NUMERIC differences) | Technical | Medium | Medium | Stand up PG; run Flyway V1–V4 + integration tests pre-release (HT-1, HT-5) | Open |
| T2 — JaCoCo BRANCH coverage 56.71% (only LINE/INSTRUCTION gated) | Technical | Low–Med | Medium | Add branch/error-path tests if org standard requires (HT-11) | Open (info) |
| T3 — Benign Spring Boot WARNs (Flyway BeanPostProcessor; H2 version-newer-than-tested) | Technical | Low | Low | Review/suppress for prod hygiene (HT-9) | Open (cosmetic) |
| S1 — `JWT_SECRET` via env var must be ≥256-bit and vaulted | Security | High | Medium | Enforce ≥256-bit from secrets manager (code already guards min length) (HT-2) | Open |
| S2 — DB credentials externalized to env vars must be vaulted | Security | Medium | Medium | Secrets management (HT-2) | Open |
| S3 — No automated dependency CVE scanning in CI | Security | Medium | Medium | Add OWASP dependency-check / Snyk to CI (HT-7) | Open |
| S4 — REST API not yet pen-tested | Security | Medium | Low–Med | Security review/pen-test pre-prod (HT-7) | Open |
| O1 — No CI/CD pipeline in repo | Operational | Medium | High | Build pipeline w/ coverage gate (HT-3) | Open |
| O2 — No app containerization/Dockerfile | Operational | Medium | High | App Dockerfile + compose/manifests (HT-4) | Open |
| O3 — Observability limited to actuator (no aggregation/alerting) | Operational | Medium | Medium | Wire observability stack (HT-6) | Open |
| O4 — No defined PostgreSQL backup/PITR strategy | Operational | Medium | Medium | Define DB backup/restore (HT-4/HT-6) | Open |
| I1 — Batch jobs validated under H2, not end-to-end vs PG at volume | Integration | Medium | Medium | Integration test vs PG (HT-5) | Open |
| I2 — REST contracts replace 3270 screens; UAT not yet obtained | Integration | Medium | Medium | UAT/stakeholder sign-off (HT-10) | Open |
| I3 — `TranIdGenerator` PG-sequence semantics confirmed only under H2 | Integration | Low–Med | Low | Integration test vs PG (HT-5) | Open |
| I4 — `report.output.path` / `input.file.path` filesystem params need prod volumes | Integration | Low | Medium | Provision volumes in deployment (HT-4) | Open |

> **No critical code risks.** All five production-readiness gates pass with zero failing tests and zero compile errors/warnings. The risk profile is dominated by standard path-to-production readiness, not code defects.

---

## 7. Visual Project Status

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'pie1':'#5B39F3','pie2':'#FFFFFF','pieStrokeColor':'#B23AF2','pieStrokeWidth':'2px','pieOuterStrokeWidth':'2px','pieSectionTextColor':'#000000'}}}%%
pie showData
    title Project Hours Breakdown (Total 651 h)
    "Completed Work" : 567
    "Remaining Work" : 84
```

**Remaining hours by priority (sums to 84 h):**

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'pie1':'#5B39F3','pie2':'#A8FDD9','pie3':'#FFFFFF','pieStrokeColor':'#B23AF2','pieStrokeWidth':'2px','pieSectionTextColor':'#000000'}}}%%
pie showData
    title Remaining Work by Priority
    "High (14 h)" : 14
    "Medium (53 h)" : 53
    "Low (17 h)" : 17
```

**Remaining hours by category (from §2.2):**

| Category | Hours | Bar |
|----------|------:|-----|
| CI/CD Pipeline | 12 | ████████████ |
| PostgreSQL Provisioning & Validation | 10 | ██████████ |
| Containerization & Deployment | 10 | ██████████ |
| Integration Testing vs PostgreSQL | 8 | ████████ |
| Production Observability | 8 | ████████ |
| UAT & Stakeholder Acceptance | 8 | ████████ |
| Security Review & CVE Scanning | 6 | ██████ |
| Performance & Load Testing | 6 | ██████ |
| JaCoCo Branch-Coverage Uplift | 6 | ██████ |
| Secrets Management | 4 | ████ |
| Flyway Production Hardening | 3 | ███ |
| API Documentation Publishing | 3 | ███ |
| **Total** | **84** | |

> **Integrity check:** "Remaining Work" = **84 h** in the pie chart = Remaining Hours in §1.2 = sum of §2.2 Hours column. ✔

---

## 8. Summary & Recommendations

**Achievements.** The COBOL→Java migration is **87.1% complete (567 h of 651 h)**. The entire Agent Action Plan code-authoring scope is delivered and verified: a layered Spring Boot 3.2.12 monolith (92 main + 41 test classes, ≈ 34,830 net-new lines) that re-expresses 28 COBOL programs, 28 copybooks, 10 VSAM datasets, and 17 BMS screens as 10 entities, 10 repositories, 11 services, 8 REST controllers, 5 Spring Batch jobs, a stateless JWT security layer, and Flyway-managed schema + seeds. All quality gates pass — 463 tests (0 failures), JaCoCo LINE 85.01% / INSTRUCTION 84.13%, zero compile errors/warnings — and the application was live-verified under the dev/H2 profile.

**Remaining gaps.** The outstanding **84 h is exclusively human path-to-production work**, not code authoring: production PostgreSQL provisioning and prod-profile validation, secrets management, CI/CD, containerization, integration/performance/security testing, observability, and UAT sign-off.

**Critical path to production.** (1) Provision PostgreSQL 15.x and validate the prod profile (HT-1); (2) establish secrets management (HT-2); (3) build CI/CD with the coverage gate (HT-3); (4) containerize and deploy with health probes (HT-4); (5) run integration tests against PostgreSQL (HT-5); (6) obtain UAT acceptance (HT-10).

**Success metrics.**

| Metric | Target | Actual | Status |
|--------|--------|--------|--------|
| Functional parity (F-001–F-009) | 100% | 100% (test-verified) | ✅ |
| Test pass rate | 100% | 463 / 463 | ✅ |
| Line coverage | ≥80% | 85.01% | ✅ |
| Instruction coverage | ≥80% | 84.13% | ✅ |
| Compile errors / warnings | 0 | 0 | ✅ |
| Dev runtime boot | Success | Success (6.30 s) | ✅ |
| Prod runtime (PostgreSQL) | Success | Pending (HT-1) | ⚠ |

**Production-readiness assessment.** The application is **production-ready within the AAP scope** and requires standard operational onboarding (database, secrets, CI/CD, deployment, UAT) before go-live. There are no known code defects or release blockers originating from the migration itself.

---

## 9. Development Guide

> All commands below were exercised on this environment (Java 17.0.19, Maven 3.9.9) except the production/PostgreSQL path, which requires a live database (human task HT-1). Run from the repository root.

### 9.1 System Prerequisites

- **Java 17 LTS** (verified: `openjdk 17.0.19`) — `java -version`
- **Apache Maven 3.9.x** (verified: `3.9.9`) — `mvn -version`
- **PostgreSQL 15.x** — production profile only; **dev/test use embedded H2** (no external DB required)
- Disk: ~300 MB for the Maven cache (`~/.m2`); ~65 MB for the built fat jar

### 9.2 Environment Setup

```bash
# Required for any run (HS256 signing key — must be >=256 bits / 32+ characters)
export JWT_SECRET="replace-with-a-32char-minimum-random-secret-value"

# Production only (PostgreSQL):
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATASOURCE_URL="jdbc:postgresql://<host>:5432/carddemo"
export SPRING_DATASOURCE_USERNAME="<db-user>"
export SPRING_DATASOURCE_PASSWORD="<db-password>"
```

### 9.3 Dependency Installation, Build & Test

```bash
# Compile, run all 463 tests, and enforce the >=80% JaCoCo coverage gate
mvn clean verify
# Offline (after the ~/.m2 cache is warmed):
mvn -o clean verify

# Package the executable fat jar -> target/carddemo-1.0.0.jar
mvn clean package
```

Expected: `Tests run: 463, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. The JaCoCo HTML report is written to `target/site/jacoco/index.html`.

### 9.4 Application Startup

```bash
# DEV (embedded H2, no external DB) — verified
java -jar target/carddemo-1.0.0.jar --spring.profiles.active=dev
#   or:  mvn spring-boot:run -Dspring-boot.run.profiles=dev

# PROD (PostgreSQL) — requires the SPRING_DATASOURCE_* env vars above
java -jar target/carddemo-1.0.0.jar --spring.profiles.active=prod
```

Expected (dev): Flyway applies V1→V4, then `Started CardDemoApplication in ~6.3 seconds` with Tomcat on **port 8080**.

### 9.5 Verification Steps

```bash
# 1) Health
curl -s http://localhost:8080/actuator/health
#    -> {"status":"UP","groups":["liveness","readiness"]}

# 2) Sign on (default admin credentials) and capture the JWT
TOKEN=$(curl -s -X POST http://localhost:8080/auth/signon \
  -H 'Content-Type: application/json' \
  -d '{"userId":"ADMIN001","password":"PASSWORD"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

# 3) Call a protected endpoint (page size 7 parity; no CVV in payload)
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/cards?page=0"
#    -> 7 items, "size":7, "totalElements":50, no "cvv" field
```

### 9.6 Example Usage & Default Credentials

| User | Password | Role | Notes |
|------|----------|------|-------|
| `ADMIN001` | `PASSWORD` | ADMIN (type `A`) | Full access incl. `/users` administration |
| `USER0001` | `PASSWORD` | USER (type `U`) | Standard access; `/users` returns `403` |

The API is a monolithic JSON REST surface with **no `/api` prefix**. Authenticate via `POST /auth/signon`, then send `Authorization: Bearer <token>` on every subsequent request (1-hour token expiry).

### 9.7 Troubleshooting

- **`JWT_SECRET` too short / app fails to start** — the secret must be ≥256-bit (32+ chars); enforced by `JwtTokenProviderSecretGuardTest`.
- **Port 8080 already in use** — add `--server.port=<port>`.
- **Prod profile fails to start** — all three `SPRING_DATASOURCE_*` env vars are required; verify PostgreSQL connectivity.
- **Benign WARN: Flyway `flywayInitializer` BeanPostProcessor / "H2 newer than tested"** — cosmetic (dev/H2 only); safe to ignore.
- **Slow first build** — Maven downloads dependencies on the first run; subsequent builds can use `-o` (offline) once `~/.m2` is warmed.

---

## 10. Appendices

### Appendix A — Command Reference

| Purpose | Command |
|---------|---------|
| Build, test, coverage gate | `mvn clean verify` (offline: `mvn -o clean verify`) |
| Package fat jar | `mvn clean package` → `target/carddemo-1.0.0.jar` |
| Run (dev/H2) | `java -jar target/carddemo-1.0.0.jar --spring.profiles.active=dev` |
| Run (dev, Maven) | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` |
| Run (prod/PostgreSQL) | `SPRING_PROFILES_ACTIVE=prod java -jar target/carddemo-1.0.0.jar` |
| Health check | `curl http://localhost:8080/actuator/health` |
| Sign on | `curl -X POST http://localhost:8080/auth/signon -H 'Content-Type: application/json' -d '{"userId":"ADMIN001","password":"PASSWORD"}'` |
| Coverage report | open `target/site/jacoco/index.html` |

### Appendix B — Port Reference

| Port | Service | Profile |
|------|---------|---------|
| 8080 | Embedded Tomcat (HTTP REST API + `/actuator`) | all |
| (in-memory) | H2 database | dev / test |
| 5432 | PostgreSQL 15.x | prod |

### Appendix C — Key File Locations

| Path | Purpose |
|------|---------|
| `pom.xml` | Maven build (Spring Boot 3.2.12, Java 17, JaCoCo gate) |
| `src/main/java/com/carddemo/CardDemoApplication.java` | Bootstrap (`@SpringBootApplication`, batch enabled) |
| `src/main/java/com/carddemo/{entity,repository,service,controller,security,batch,config,dto,mapper,exception,util}/` | Layered application source (92 classes) |
| `src/main/resources/application.yml` + `application-dev.yml` + `application-prod.yml` | Profile configuration |
| `src/main/resources/logback-spring.xml` | PII/CVV log masking |
| `src/main/resources/db/migration/V1__schema.sql` … `V4__seed_users.sql` | Flyway schema + seeds |
| `src/test/java/com/carddemo/**` | 41 test classes (463 tests) |
| `app/**` (cbl, cpy, cpy-bms, bms, data/ASCII, catlg) | Legacy COBOL source-of-truth REFERENCE (unmodified) |
| `target/carddemo-1.0.0.jar` | Executable fat jar |
| `target/site/jacoco/index.html` | Coverage report |

### Appendix D — Technology Versions

| Technology | Version |
|------------|---------|
| Java | 17 (runtime verified 17.0.19) |
| Spring Boot (parent) | 3.2.12 |
| Maven | 3.9.9 |
| Spring Framework (CVE override) | 6.2.19 |
| Spring Security (CVE override) | 6.5.11 |
| Tomcat (CVE override) | 10.1.55 |
| PostgreSQL driver (CVE override) | 42.7.11 |
| Logback (CVE override) | 1.5.34 |
| Flyway | 9.x (BOM-managed) |
| JJWT (jjwt-api/impl/jackson) | 0.12.6 |
| springdoc-openapi | 2.8.17 |
| JaCoCo Maven plugin | 0.8.12 |
| H2 (dev/test) | BOM-managed |

### Appendix E — Environment Variable Reference

| Variable | Required | Purpose |
|----------|----------|---------|
| `JWT_SECRET` | Always | HS256 signing key (≥256-bit / 32+ chars) |
| `JWT_EXPIRATION_MS` | Optional | Token lifetime (default ~1 h) |
| `SPRING_PROFILES_ACTIVE` | Prod | `dev` (H2) or `prod` (PostgreSQL) |
| `SPRING_DATASOURCE_URL` | Prod | `jdbc:postgresql://<host>:5432/carddemo` |
| `SPRING_DATASOURCE_USERNAME` | Prod | Database username |
| `SPRING_DATASOURCE_PASSWORD` | Prod | Database password |
| `report.output.path` | Optional | Statement/report job output directory |
| `input.file.path` | Batch | Daily-transaction input file (posting job parameter) |

### Appendix F — Developer Tools Guide

| Tool | Location / Usage |
|------|------------------|
| OpenAPI / Swagger (springdoc) | `/swagger-ui.html` and `/v3/api-docs` when the app is running |
| Spring Boot Actuator | `/actuator/health` (liveness/readiness), `/actuator/info`, metrics |
| JaCoCo coverage report | `target/site/jacoco/index.html` after `mvn verify` |
| Surefire test reports | `target/surefire-reports/*.xml` |
| Flyway migrations | auto-applied on startup from `src/main/resources/db/migration` |

### Appendix G — Glossary

| Term | Meaning |
|------|---------|
| COBOL | Legacy business language of the source application |
| CICS | IBM online transaction monitor (pseudo-conversational) — replaced by Spring MVC |
| VSAM / KSDS | Key-Sequenced Data Set file store — replaced by PostgreSQL tables |
| JCL | Job Control Language for batch scheduling — replaced by Spring Batch |
| BMS | Basic Mapping Support (3270 screen maps) — retired (REST/JSON replaces it) |
| COMMAREA | CICS communication area for cross-program session state — replaced by JWT claims |
| DALYREJS | Daily-transaction reject dataset (430-byte fixed-width record) |
| TCATBAL | Transaction-category balance (per-category interest basis) |
| DISCGRP | Disclosure group (interest-rate lookup with DEFAULT fallback) |
| XREF | Card-to-customer-to-account cross-reference |
| `@Version` | JPA optimistic-locking column → HTTP 409 on concurrent update |
| HALF_UP | Rounding mode reproducing COBOL fixed-point monetary arithmetic |

---

*Generated by the Blitzy autonomous assessment agent. Completion (87.1%) and all hour figures are computed from the AAP-scoped, hours-based methodology and are consistent across Sections 1.2, 2.1, 2.2, 7, and 8.*