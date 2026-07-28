# CardDemo Mainframe → Modern Stack Migration — Blitzy Project Guide

> **Project:** AWS CardDemo — COBOL/CICS/VSAM/BMS/JCL → FastAPI + Next.js/MUI + PostgreSQL 17 + Python CLI Batch
> **Branch:** `blitzy-0b3e4b81-3fd5-4970-9c65-7361a338a6e1` · **HEAD:** `a87bb190`
> **Status legend — Blitzy brand colors:** <span style="color:#5B39F3">■</span> **Completed / AI Work = Dark Blue `#5B39F3`** · <span style="color:#FFFFFF;background:#333">■</span> **Remaining = White `#FFFFFF`**

---

## 1. Executive Summary

### 1.1 Project Overview

CardDemo is a credit-card management application migrated from a legacy IBM mainframe (COBOL online and batch programs, CICS transaction management, VSAM KSDS storage, BMS 3270 green-screens, and JCL/PROC job orchestration) to a modern three-tier web stack. The target comprises a Python 3.13 **FastAPI** REST backend, a **Next.js 16 + Material UI 9** single-page frontend, a **PostgreSQL 17** relational database, and a **Python (Typer) CLI** batch package. Every business rule — interest calculation, transaction-posting validations, credit-limit checks, cross-reference integrity, and bill payment — is preserved exactly (Minimal Change Clause), while plaintext passwords are hardened to bcrypt and CICS COMMAREA identity is replaced by stateless session/JWT auth. Target users are cardholder-servicing operators and administrators.

### 1.2 Completion Status

**Completion is calculated on AAP-scoped work plus path-to-production activities only (PA1 hours methodology):**

```
Completion % = Completed Hours / (Completed Hours + Remaining Hours)
             = 1140 / (1140 + 180)
             = 1140 / 1320
             = 86.4%
```

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'pie1':'#5B39F3','pie2':'#FFFFFF','pieStrokeColor':'#B23AF2','pieStrokeWidth':'2px','pieTitleTextSize':'16px','pieSectionTextColor':'#B23AF2','pieOuterStrokeWidth':'2px'}}}%%
pie showData title Completion — 86.4% (hours)
    "Completed Work (AI) #5B39F3" : 1140
    "Remaining Work #FFFFFF" : 180
```

| Metric | Value |
|--------|-------|
| **Total Hours** | **1,320 h** |
| **Completed Hours (AI + Manual)** | **1,140 h** (AI = 1,140 h · Manual = 0 h) |
| **Remaining Hours** | **180 h** |
| **Percent Complete** | **86.4 %** |

> All completed work was performed autonomously by Blitzy agents (Manual = 0 h). Completion is capped below 100% (per honest-assessment policy) because path-to-production activities require human action and real infrastructure.

### 1.3 Key Accomplishments

- ✅ **17 online COBOL programs → FastAPI** router→service→repository clusters (8 service clusters, 8 routers, 22 routes) — all business logic ported 1:1.
- ✅ **VSAM → PostgreSQL 17**: 10 relational tables with foreign keys and unique/secondary indexes; 9 Alembic migrations (schema, seed, CVV-drop, idempotency).
- ✅ **17 BMS maps → Next.js/MUI pages** (1:1) redesigned per Material Design 3, with shared components, theme, and typed API client.
- ✅ **JCL batch chain → Python Typer CLI**: 11 jobs + 10 loaders + orchestration preserving exact legacy order (CLOSEFIL→…→OPENFIL).
- ✅ **Business rules preserved exactly**: interest = balance × rate ÷ 1200 with ROUND_DOWN; posting codes 100–103 + discovered 109; available credit = limit − balance; ≤7 cards/page (F-004); admin gating on `user_type='A'`.
- ✅ **Security uplift**: bcrypt password hashing (no plaintext), stateless session/JWT (replaces COMMAREA), SSN/PAN masking, CVV never persisted/returned, security headers, login rate-limiting, CSRF origin guard, NaN/Infinity input DoS fix.
- ✅ **Exact numerics**: signed zoned-decimal decode; `Decimal`/`NUMERIC(p,s)` end-to-end, never float; `interest_rate` `NUMERIC(6,2)`.
- ✅ **1,284/1,284 automated tests pass** (661 backend, 222 batch, 401 frontend incl. jest-axe WCAG), including golden-master parity vs `app/data`.
- ✅ **Full-stack runtime validated**: DB migrate+seed, backend health/auth/API, browser E2E (7/7 screens), batch CLI — all green.
- ✅ **QA lifecycle complete**: all 44 final-gate findings resolved (8 Critical, 32 Major, 4 Minor); legacy `app/` tree untouched (greenfield).

### 1.4 Critical Unresolved Issues

| Issue | Impact | Owner | ETA |
|-------|--------|-------|-----|
| _None blocking._ All AAP functional deliverables are implemented, tested (1284/1284), and runtime-validated. | No release-blocking defects. Remaining work is path-to-production (Section 2.2), not defects. | — | — |
| Non-blocking a11y advisory on `/reports` browser-internal `<input type="date">` shadow-DOM (NOT an axe-core violation; author markup already labelled) | Cosmetic audit heuristic only; jest-axe WCAG gate passes | Frontend | Optional (L-1) |

### 1.5 Access Issues

| System/Resource | Type of Access | Issue Description | Resolution Status | Owner |
|-----------------|----------------|-------------------|-------------------|-------|
| Cloud provider (compute/DB/network) | Provisioning credentials | No target cloud account/project provisioned for staging or production | Open — required for deploy | Platform/DevOps |
| Production secrets store (KMS/Vault) | Secret management | Production `SECRET_KEY` and DB credentials not yet issued (only `.env.example` templates exist) | Open — required for deploy | Security/DevOps |
| Real legacy VSAM/EBCDIC data feed | Data extract access | Only the bundled `app/data` sample was used for seed/parity; no access to full-volume production extract | Open — required for production data migration | Data/Mainframe team |
| TLS certificate authority + DNS | Cert issuance / DNS mgmt | No certificates or DNS records for a target domain | Open — required for HTTPS | DevOps |

_These are environment/credential dependencies for production, not repository-permission problems; the codebase itself builds, tests, and runs cleanly with no access blockers._

### 1.6 Recommended Next Steps

1. **[High]** Provision cloud infrastructure — managed PostgreSQL 17, container hosting/orchestration, VPC/networking (task H-1).
2. **[High]** Issue and wire production secrets (real `SECRET_KEY`, DB credentials) via KMS/Vault; configure TLS/DNS/ingress (tasks H-2, H-4).
3. **[High]** Stand up CI/CD to gate the 1,284-test suite and deploy to staging, then run full end-to-end validation (tasks H-3, H-5).
4. **[Medium]** Execute production data migration (real VSAM/EBCDIC extract→load; USRSEC decode-vs-regenerate decision) and UAT + golden-master parity sign-off against the real legacy system (tasks H-6, M-4).
5. **[Medium]** Wire monitoring/alerting/observability and author backup/restore + DR runbooks before go-live (tasks M-1, M-5).

---

## 2. Project Hours Breakdown

### 2.1 Completed Work Detail

Every component traces to a specific AAP requirement (Goals G1–G5, implicit requirements, §0.7 special semantics, and Ochs rules). All hours are autonomous (AI) work.

| Component | Hours | Description |
|-----------|------:|-------------|
| Backend data layer (10 models + 9 Alembic migrations + seed) | 76 | SQLAlchemy ORM for all 10 tables from copybooks; FKs, unique/secondary indexes; migrations 0001–0009 (schema, seed, CVV-drop, session-version, idempotency) — G2 |
| Pydantic schemas (request/response DTOs) | 24 | 16 schema modules; field lengths/ranges ported from copybooks + BMS symbolic maps as validators — G1/§0.7.8 |
| Repositories (10, incl. SELECT..FOR UPDATE) | 36 | Data-access layer replacing VSAM READ/STARTBR/READNEXT/REWRITE; `GetForUpdate` optimistic lock — G2/§0.7.4 |
| Service layer (8 clusters porting 17 online COBOL programs) | 96 | Business logic 1:1 with COBOL PROCEDURE DIVISION (auth, menu, account, card, transaction, report, billpay, user_admin) — G1/G5 |
| API routers (8) + app factory / lifespan | 32 | Thin FastAPI routers; `create_application()` + async lifespan; 22 routes matching AAP §0.5.5 — G1 |
| Core (bcrypt security, JWT/session, exceptions, masking, rate-limit) | 44 | Password hashing, token issuance, domain exceptions (codes 100–103/109), log masking, correlation, login throttle — implicit/§0.7 |
| Utils (zoned-decimal codec, date, validators, csv-safety) | 28 | Signed zoned-decimal overpunch decode, ROUND_DOWN truncation, date validation (CSUTLDTC), CSV-injection safety — §0.7.1/§0.7.2 |
| Batch jobs (11, 1:1 with CB*/JCL) | 96 | post_transactions, interest_calc, statement_gen, print_*, read_daily_tran, tran_detail_report, combine_tran, backup_tran — G4/G5 |
| Loaders (10, EBCDIC decode + bcrypt seed) | 40 | IDCAMS-equivalent seed loaders incl. `init_users` (EBCDIC USRSEC decode → bcrypt) — G4/§0.7.7 |
| Batch orchestration + Typer CLI + config/db | 32 | `batch_chain.py` preserving exact legacy order; `carddemo-batch` CLI (run-all/seed-all/job/load) — G4/§0.7.6 |
| Frontend pages (17, 1:1 with BMS maps) | 120 | Next.js App Router pages redesigned per Material Design 3; forms, tables, pagination, dialogs — G3 |
| Frontend components + lib + types + theme | 68 | AppShell, DataTable, FormField, ConfirmDialog, ErrorAlert; axios client + auth; 15 TS type modules; MUI theme — G3 |
| Automated test suites (1284 tests) | 268 | 661 backend (unit/API/integration + golden-master parity), 222 batch, 401 frontend (Jest + jest-axe) — AAP test scope |
| QA remediation (44 findings + F1–F5 + DoS + hardening) | 80 | Resolution of all final-gate findings (8 Critical, 32 Major, 4 Minor); security gates; NaN/Inf DoS fix; container hardening |
| Runtime + browser E2E validation | 40 | DB migrate/seed, health/auth/API checks, Chrome E2E across 7 screens with masking + F-004 + Decimal-as-string |
| Configuration + containerization (Docker/compose) | 32 | Dependency manifests, Dockerfiles, `docker-compose.yml` (postgres:17 + migrator + backend + frontend), `.env` templates |
| Documentation (README + 5 docs + traceability) | 28 | Target README + api-reference, architecture, batch, data-model, and traceability matrix under `docs/` |
| **Total Completed** | **1,140** | **Matches Section 1.2 Completed Hours** |

### 2.2 Remaining Work Detail

Each category is a path-to-production activity requiring human action and/or real infrastructure. Categories map 1:1 to the human tasks in Section 8 / Appendix.

| Category | Hours | Priority |
|----------|------:|----------|
| Cloud infrastructure provisioning (managed PostgreSQL 17, container hosting, VPC/networking) | 20 | High |
| Production secrets management (SECRET_KEY, DB credentials via KMS/Vault, rotation) | 8 | High |
| CI/CD pipeline (automated gate: compile/type-check/lint/1284-test + build + deploy) | 20 | High |
| TLS/HTTPS certificates, DNS, reverse-proxy/ingress | 10 | High |
| Staging deployment + full end-to-end validation | 14 | High |
| Production data migration (real VSAM/EBCDIC extract→load; USRSEC decode-vs-regenerate) | 20 | High |
| Monitoring, alerting & observability (metrics, dashboards, log aggregation) | 20 | Medium |
| Production-scale load & performance testing + tuning | 12 | Medium |
| Security sign-off (penetration test, CVE audit, threat model) | 14 | Medium |
| User Acceptance Testing + golden-master parity sign-off vs real legacy | 16 | Medium |
| Backup/restore + disaster-recovery runbooks + ops docs | 12 | Medium |
| Final human code review + go-live checklist + knowledge transfer | 10 | Medium |
| Optional polish (`/reports` date-input a11y advisory + doc reconciliation) | 4 | Low |
| **Total Remaining** | **180** | **High = 92 · Medium = 84 · Low = 4** |

### 2.3 Hours Summary

| Bucket | Hours | % of Total |
|--------|------:|-----------:|
| Completed (Section 2.1) | 1,140 | 86.4 % |
| Remaining (Section 2.2) | 180 | 13.6 % |
| **Total Project** | **1,320** | **100 %** |

> **Integrity:** Section 2.1 (1,140) + Section 2.2 (180) = 1,320 = Total Project Hours in Section 1.2. ✔

---

## 3. Test Results

All figures below originate from Blitzy's autonomous test-execution logs for this project (backend/batch `pytest`, frontend `jest`/`jsdom` + `jest-axe`).

| Test Category | Framework | Total Tests | Passed | Failed | Coverage % | Notes |
|---------------|-----------|------------:|-------:|-------:|-----------:|-------|
| Backend Unit | pytest | 661* | 661 | 0 | High | Service/repository/util logic mirroring each COBOL program's paths (incl. interest ROUND_DOWN, posting codes 100–103/109, zoned-decimal codec) |
| Backend API | pytest + httpx | (incl. in 661) | ✓ | 0 | High | Route inventory, auth gating (401/403/200), security headers, SSN/PAN masking, non-finite input |
| Backend Integration (golden-master parity) | pytest | (incl. in 661) | ✓ | 0 | High | Field-for-field parity vs `app/data`; migrations; lifespan; correlation |
| Batch Unit + Integration | pytest | 222 | 222 | 0 | High | Loaders, init_users, post_transactions, interest_calc, statement_gen, tran_detail_report, print jobs, combine/backup, batch_chain, CLI |
| Frontend Component/Integration + a11y | Jest + jsdom + jest-axe | 401 | 401 | 0 | High | 27 suites incl. WCAG assertions per screen |
| **Total** | — | **1,284** | **1,284** | **0** | — | **100% pass · 0 failed · 0 errored · 0 skipped · 0 warnings** |

\* Backend Unit/API/Integration are reported together by pytest as **661 passed in ~130 s**; batch **222 passed in ~80 s**; frontend **401 passed across 27 suites**. Grand total **1,284**.

**Build & static-analysis gates (all clean):** `compileall` exit 0 · `tsc --noEmit` (strict) exit 0 · `next build` (21 routes, standalone) exit 0 · `pip check` clean · `npm ls --depth=0` exit 0.

---

## 4. Runtime Validation & UI Verification

**Backend & database (re-verified live this session against PostgreSQL 17.10):**
- ✅ **Operational** — PostgreSQL 17 via `docker compose up -d db`; healthy; Alembic at head **0009** (9 migrations).
- ✅ **Operational** — Seed data present: users 10, accounts/cards/customers/card_xref 50 each, transactions 301, disclosure_group 51, transaction_type 7, transaction_category 18, tran_category_balance 49 (10 domain tables + `alembic_version`).
- ✅ **Operational** — `GET /health` → `{"status":"ok"}` 200; `GET /health/ready` → `{"status":"ready"}` 200 (DB `SELECT 1`).
- ✅ **Operational** — Route inventory = **22 routes** (20 under `/api/v1` + 2 health), matching AAP §0.5.5.

**Authentication, authorization & data protection:**
- ✅ **Operational** — `POST /api/v1/auth/login` (ADMIN001) → 200 with **HttpOnly** `carddemo_session` cookie (SameSite=lax, Max-Age=3600) and security headers (`x-content-type-options: nosniff`, `x-frame-options: DENY`, CSP `frame-ancestors 'none'`).
- ✅ **Operational** — Auth gating: unauthenticated `GET /api/v1/cards` → **401**; regular user (USER0001) on `GET /api/v1/admin/users` → **403**; admin → 200.
- ✅ **Operational** — Sensitive-data masking: `/cards` returns `card_num` as `************5740`; `/accounts/{id}` returns `ssn` as `***-**-3888`; **CVV never present**; leading-zero keys preserved (`acct_id=00000000001`).

**Business-rule surfaces:**
- ✅ **Operational** — `GET /api/v1/cards` returns **exactly 7 rows/page** (F-004).
- ✅ **Operational** — `GET /api/v1/reports/transactions` → 422 without required dates (validation), 200 with `report_type`+`start_date`+`end_date`; `GET /api/v1/transactions` → 200.

**Frontend (Next.js standalone) & browser E2E:**
- ✅ **Operational** — `.next/standalone/server.js` built; `next build` generates 21 routes; all routes serve 200.
- ✅ **Operational** — Chrome subagent E2E **PASS 7/7 screens** (signon, admin, accounts/view, cards, transactions, reports, users): no crash/error boundary, masking enforced in DOM and API, pagination = 7 rows, decimals rendered as strings (Decimal, not float), **zero JS console errors**, **zero HTTP ≥ 400**, security headers present. 9 screenshots + 1 screen recording captured under `blitzy/`.

**Batch:**
- ✅ **Operational** — `carddemo-batch --help` and `python -m batch.cli --help` expose `run-all`/`seed-all`/`job`/`load`; `job` has 12 subcommands (1:1 with CB*/JCL), `load` has 10 subcommands (1:1 with IDCAMS jobs).

**Overall runtime verdict: ✅ Operational — no failing components.**

---

## 5. Compliance & Quality Review

AAP deliverables cross-mapped to Blitzy quality/compliance benchmarks. Fixes applied during autonomous validation are noted.

| Benchmark / AAP Requirement | Status | Evidence & Fixes Applied |
|-----------------------------|--------|--------------------------|
| G1 — Online programs → REST (17 programs → 8 clusters) | ✅ Pass | 22 routes match §0.5.5; services carry COBOL traceability comments |
| G2 — VSAM → PostgreSQL (tables, FKs, indexes) | ✅ Pass | 10 tables, 9 FKs, unique/secondary indexes; 9 Alembic migrations to head 0009 |
| G3 — BMS → Web SPA (Material Design 3) | ✅ Pass | 17 pages 1:1 with BMS; MUI components; no raw HTML controls where MUI exists |
| G4 — JCL batch chain → Python CLI (order preserved) | ✅ Pass | `batch_chain.py` preserves CLOSEFIL→…→OPENFIL; idempotent jobs |
| G5 — Business rules preserved exactly | ✅ Pass | Interest ÷1200 ROUND_DOWN (verbatim CBACT04C L464-465); codes 100–103/109; ≤7/page; available credit = limit−balance |
| Password security uplift (plaintext → hash) | ✅ Pass | bcrypt in `core/security.py`; `init_users` hashes seed; no plaintext stored/returned |
| Stateless identity (COMMAREA → session/JWT) | ✅ Pass | Session baseline (HttpOnly cookie) + JWT alternative; `require_admin` gates `user_type='A'` |
| Exact decimal numerics (never float) | ✅ Pass | `Decimal`/`NUMERIC`; signed zoned-decimal codec; `interest_rate NUMERIC(6,2)` (§0.7 Finding #2) |
| Optimistic locking (READ-UPDATE→REWRITE) | ✅ Pass | `account_repo.GetForUpdate` = `SELECT … FOR UPDATE` |
| Referential integrity (CARDXREF → FKs) | ✅ Pass | Foreign-key constraints across models |
| Sensitive data (SSN/PAN masking; CVV drop) | ✅ Pass | Response + log masking; migration 0003 drops CVV column (QA C-03) |
| Input hardening (injection/DoS) | ✅ Pass | Pydantic validation; CSV-injection safety; NaN/Infinity input rejection |
| Ochs coding rules (PascalCase methods, camelCase vars, snake_case files) | ✅ Pass | Applied across generated modules; env-var config (no hardcoded secrets) |
| Config & containerization | ✅ Pass | All manifests; `docker-compose.yml` (postgres:17 + one-shot migrator + backend + frontend); hardened containers |
| Documentation | ✅ Pass | Target README + 5 `docs/` files + traceability matrix |
| Legacy `app/` untouched (greenfield) | ✅ Pass | 369 files added, 1 modified (README.md); 0 changes under `app/` |
| Final-gate QA findings resolved | ✅ Pass | All 44 findings resolved (8 Critical, 32 Major, 4 Minor) |
| **Production deployment / ops sign-off** | ⏳ Remaining | Path-to-production (Section 2.2): infra, secrets, CI/CD, monitoring, UAT, pen-test, DR |

---

## 6. Risk Assessment

Because the build is fully validated, residual risk is concentrated in the not-yet-proven-in-production space; several security risks already carry strong in-code mitigations.

| Risk | Category | Severity | Probability | Mitigation | Status |
|------|----------|----------|-------------|------------|--------|
| Golden-master parity validated only on sample data (50 accts/cards, 301 txns), not full production volume | Technical | Medium | Medium | Run parity + UAT against full-volume extract (M-4); parity harness already exists | Open |
| `SELECT … FOR UPDATE` locking not load-tested under real concurrency | Technical | Low | Low | Production-scale load test + pool tuning (M-2) | Open |
| No CI/CD gate wired to real environments | Technical | Low | Medium | Build CI/CD to run the 1,284-test suite + deploy (H-3) | Open |
| `/reports` browser date-input a11y advisory (not an axe violation) | Technical | Low | Low | Author markup already labelled; optional polish (L-1) | Open — Accepted |
| Production secrets not provisioned (only `.env.example`) | Security | High | Medium | Issue real `SECRET_KEY`/DB creds via KMS/Vault; app fails closed on weak key (H-2) | Open |
| No independent pen-test / CVE sign-off | Security | Medium | Medium | Pen-test + dependency CVE audit (M-3); bcrypt, CVV-drop, SSN/PAN masking, CSRF guard, security headers, rate-limiter, NaN/Inf DoS fix already in code | Partially mitigated |
| Demo seed credential `PASSWORD` must not reach production | Security | Medium | Low | bcrypt-hashed & seed-only; production data migration replaces seed users (H-6) | Open |
| Monitoring/alerting not wired | Operational | Medium | Medium | Wire observability; `/health` + `/health/ready` endpoints already exist (M-1) | Open |
| No PostgreSQL backup/restore + DR runbook | Operational | High | Low | Author backup/DR runbooks before go-live (M-5) | Open |
| Batch-chain idempotency tested but not under production scheduling/failure injection | Operational | Low | Low | Validate under scheduler with failure injection in staging (H-5) | Open |
| Real legacy VSAM/EBCDIC feed not integrated (sample seed only) | Integration | Medium | Medium | Production data migration incl. USRSEC decode-vs-regenerate (H-6) | Open |
| Cross-origin frontend↔backend needs allow-listed Origin/CORS + cookie domain in prod | Integration | Low | Medium | Configure CORS/cookie domain at deploy; CSRF origin guard already active (H-4) | Open |
| No staging environment validated end-to-end | Integration | Medium | Medium | Stand up staging + full E2E validation (H-5) | Open |

---

## 7. Visual Project Status

**Project hours — Completed vs Remaining** (Completed = Dark Blue `#5B39F3`, Remaining = White `#FFFFFF`):

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'pie1':'#5B39F3','pie2':'#FFFFFF','pieStrokeColor':'#B23AF2','pieStrokeWidth':'2px','pieSectionTextColor':'#B23AF2'}}}%%
pie showData title Project Hours Breakdown (Total 1320h)
    "Completed Work" : 1140
    "Remaining Work" : 180
```

**Remaining hours by priority** (sums to 180 h):

```mermaid
%%{init: {'theme':'base', 'themeVariables': {'pie1':'#B23AF2','pie2':'#5B39F3','pie3':'#A8FDD9'}}}%%
pie showData title Remaining Work by Priority (180h)
    "High" : 92
    "Medium" : 84
    "Low" : 4
```

**Remaining hours by category (Section 2.2):**

| Category | Hours |
|----------|------:|
| Cloud infrastructure provisioning | 20 |
| Production data migration + USRSEC decision | 20 |
| CI/CD pipeline | 20 |
| Monitoring / observability | 20 |
| UAT + golden-master parity sign-off | 16 |
| Staging deploy + E2E validation | 14 |
| Security sign-off (pen-test/CVE/threat model) | 14 |
| Load & performance testing | 12 |
| Backup/DR + ops runbooks | 12 |
| TLS/DNS/ingress | 10 |
| Final review + go-live | 10 |
| Production secrets / KMS | 8 |
| Optional polish (a11y + docs) | 4 |
| **Total** | **180** |

> **Integrity:** "Remaining Work" = 180 h equals Section 1.2 Remaining Hours and the sum of Section 2.2 "Hours". ✔

---

## 8. Summary & Recommendations

**Achievements.** The CardDemo migration is **86.4% complete** (1,140 h of 1,320 h). Every AAP functional deliverable across Goals G1–G5 is implemented, tested, and runtime-validated: 17 online COBOL programs are now FastAPI clusters, VSAM is now PostgreSQL 17 with full referential integrity, 17 BMS maps are now Material Design 3 pages, and the JCL chain is a Typer CLI preserving legacy order. All business rules are preserved exactly, mandatory security uplifts (bcrypt, stateless auth, masking, CVV removal, input hardening) are in place, and **1,284/1,284 automated tests pass** with a clean build, alongside a fully green full-stack runtime and browser E2E.

**Remaining gaps (180 h, all path-to-production).** No functional work or defects remain. The outstanding effort is human- and infrastructure-dependent: cloud provisioning, production secrets, CI/CD, TLS/DNS, staging/production deploy, monitoring/observability, load & security testing, UAT + golden-master parity sign-off against the real legacy system, production data migration (including the USRSEC EBCDIC decode-vs-regenerate decision), backup/DR runbooks, and go-live.

**Critical path to production.** (1) Provision infrastructure and secrets → (2) wire CI/CD and deploy to staging → (3) migrate real data and run UAT + parity sign-off → (4) complete security pen-test and monitoring/DR → (5) final review and go-live. High-priority tasks total 92 h; medium 84 h; low 4 h.

**Prioritized human task list.**

| ID | Task | Priority | Hours |
|----|------|----------|------:|
| H-1 | Provision cloud infrastructure (managed PostgreSQL 17, container hosting/orchestration, VPC/networking) | High | 20 |
| H-2 | Configure production secrets (real SECRET_KEY & DB creds via KMS/Vault; rotation) | High | 8 |
| H-3 | Build CI/CD pipeline (compile/type-check/lint/1284-test gate + build + deploy) | High | 20 |
| H-4 | Configure TLS/HTTPS certificates, DNS, reverse-proxy/ingress | High | 10 |
| H-5 | Deploy to staging + full end-to-end validation in a production-like environment | High | 14 |
| H-6 | Production data migration (real VSAM/EBCDIC extract→load; USRSEC decode-vs-regenerate; user provisioning) | High | 20 |
| M-1 | Wire monitoring/alerting/observability; connect `/health` + `/health/ready` to alerts | Medium | 20 |
| M-2 | Production-scale load & performance testing + tuning | Medium | 12 |
| M-3 | Security sign-off (penetration test, CVE audit, secrets scan, threat model) | Medium | 14 |
| M-4 | UAT + golden-master parity sign-off vs real legacy on production-scale data | Medium | 16 |
| M-5 | Backup/restore procedures + DR runbooks + operational documentation | Medium | 12 |
| M-6 | Final human code review + go-live checklist + knowledge transfer | Medium | 10 |
| L-1 | Optional polish: `/reports` date-input a11y advisory (non-blocking) + doc reconciliation | Low | 4 |
| | **Total** | | **180** |

**Production-readiness assessment.** The application is **feature-complete and validation-green**, but **not yet production-deployed**. It is ready to enter the deployment pipeline: with infrastructure, secrets, and CI/CD in place, staging validation and UAT can proceed immediately. Recommended posture: proceed to staging now; gate production go-live on security sign-off, DR runbooks, and UAT/parity approval.

---

## 9. Development Guide

All commands below were executed and verified during assessment. Run from the repository root unless noted. The shared virtual environment is `.venv` at the repo root.

### 9.1 System Prerequisites

- **Python 3.13** (verified 3.13.7) — backend + batch runtime
- **Node.js 20+** (verified v22.23.1) and **npm** (verified 11.18.0) — frontend
- **Docker Engine** with the Compose plugin — for PostgreSQL 17 (and optional full-stack)
- **PostgreSQL 17** — provided via Docker image `postgres:17` (verified 17.10)
- OS: Linux/macOS (developed and validated on Ubuntu)

### 9.2 Environment Setup

```bash
# 1) Backend environment file (authoritative variable list lives in the template)
cp backend/.env.example backend/.env
# Generate a strong signing key (must be >= 32 chars or the app fails closed):
openssl rand -hex 32     # paste into SECRET_KEY=... in backend/.env

# 2) Frontend environment file (NEXT_PUBLIC_API_URL is the backend ORIGIN only)
cp frontend/.env.local.example frontend/.env.local
```

Key backend variables (see `backend/.env.example` for the full, documented list): `DATABASE_URL` (asyncpg), `SYNC_DATABASE_URL` (psycopg2), `SECRET_KEY` (≥32 chars), `AUTH_MODE=session` (baseline), `BCRYPT_ROUNDS=12`, `LOGIN_MAX_ATTEMPTS=5`, `BACKEND_CORS_ORIGINS`. Frontend: `NEXT_PUBLIC_API_URL=http://localhost:8000` (origin only — the axios client appends `/api/v1`).

### 9.3 Dependency Installation

Dependencies are pre-installed in the repo-root `.venv` and frontend `node_modules`. To recreate:

```bash
# Backend + batch (into a venv)
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt -r backend/requirements-dev.txt -r batch/requirements.txt
pip install -e backend -e batch      # editable: carddemo-backend, carddemo-batch

# Verify (expected: "No broken requirements found.")
.venv/bin/pip check

# Frontend
cd frontend && npm ci && cd ..
```

### 9.4 Database: Start, Migrate, Seed

```bash
# Start PostgreSQL 17 (creates the carddemo database/user; binds 127.0.0.1:5432)
docker compose up -d db
# Wait until healthy (compose healthcheck runs pg_isready)

# Apply all migrations + seed (schema 0001 -> head 0009, seed 0002)
cd backend && ../.venv/bin/alembic upgrade head && cd ..
```

Expected: Alembic reports `0009 (head)`. Seed row counts: users 10; accounts/cards/customers/card_xref 50 each; transactions 301; disclosure_group 51; transaction_type 7; transaction_category 18; tran_category_balance 49.

### 9.5 Application Startup

```bash
# Backend (FastAPI on :8000)
cd backend && ../.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000
# health: GET /health, GET /health/ready ; API under /api/v1

# Frontend (Next.js standalone on :3000) — in a second shell
cd frontend
npm run build
cp -rT .next/static .next/standalone/.next/static
cp -rT public .next/standalone/public
HOSTNAME=127.0.0.1 PORT=3000 NEXT_PUBLIC_API_URL=http://127.0.0.1:8000 \
  node .next/standalone/server.js
# visit http://127.0.0.1:3000/signon
```

**Full stack via Docker (alternative):** `docker compose --profile full up -d --build` — a one-shot `migrator` service runs `alembic upgrade head` automatically before the backend starts. Provide a real `SECRET_KEY` via the environment or `backend/.env`.

### 9.6 Verification Steps

```bash
# Health
curl -s http://127.0.0.1:8000/health          # {"status":"ok"}
curl -s http://127.0.0.1:8000/health/ready     # {"status":"ready"}

# Login (session cookie) — demo creds are seed-only, bcrypt-hashed
curl -s -i -c cj.txt -X POST http://127.0.0.1:8000/api/v1/auth/login \
  -H 'Content-Type: application/json' -H 'Origin: http://localhost:3000' \
  -d '{"user_id":"ADMIN001","password":"PASSWORD"}'      # 200 + Set-Cookie carddemo_session (HttpOnly)

# Authorized call — cards page returns <= 7 rows (F-004), PAN masked, no CVV
curl -s -b cj.txt "http://127.0.0.1:8000/api/v1/cards"

# Auth gating
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8000/api/v1/cards   # 401 unauthenticated
```

### 9.7 Batch CLI Usage

```bash
.venv/bin/carddemo-batch --help                 # groups: run-all, seed-all, job, load
.venv/bin/carddemo-batch seed-all               # seed every table in FK-safe order
.venv/bin/carddemo-batch run-all                # full chain in legacy README order
.venv/bin/carddemo-batch job interest-calc      # single job (legacy CBACT04C / INTCALC.jcl)
.venv/bin/carddemo-batch load init-users        # EBCDIC USRSEC -> bcrypt-hashed users
# module form also works: .venv/bin/python -m batch.cli --help
```

### 9.8 Running the Test Suites

```bash
# Backend (needs a test DB 'carddemo_test' on localhost:5432)
cd backend && ../.venv/bin/pytest && cd ..          # 661 passed

# Batch
.venv/bin/pytest batch/tests                        # 222 passed

# Frontend (no DB required)
cd frontend && CI=true npm test && cd ..            # 401 passed (27 suites)
```

### 9.9 Troubleshooting

- **App refuses to start / `SECRET_KEY` error:** `SECRET_KEY` must be ≥ 32 characters; generate with `openssl rand -hex 32`. The app fails closed on a blank/short key by design.
- **DB connection refused:** ensure `docker compose up -d db` is healthy; on the host use `localhost:5432`, but **under Docker Compose the DB host is `db`** (not localhost).
- **Health probe hangs on `localhost`:** use `127.0.0.1` — the servers bind IPv4, and `localhost` may resolve to IPv6 `::1` first.
- **Frontend can't reach API / CORS preflight fails:** `NEXT_PUBLIC_API_URL` must be the backend **origin only** (no `/api/v1`), and it is inlined at **build** time — rebuild after changing it. Ensure the frontend origin is in `BACKEND_CORS_ORIGINS`.
- **Cookie mutation returns 403:** the CSRF origin guard requires an allow-listed `Origin` header on cookie-authenticated mutating requests (login is exempt).
- **Migrations "not up to date":** run `cd backend && ../.venv/bin/alembic upgrade head`; verify with `alembic current` (expect `0009 (head)`).

---

## 10. Appendices

### A. Command Reference

| Purpose | Command |
|---------|---------|
| Dependency health | `.venv/bin/pip check` |
| Byte-compile backend + batch | `.venv/bin/python -m compileall backend/app batch` |
| Start database | `docker compose up -d db` |
| Migrate + seed | `cd backend && ../.venv/bin/alembic upgrade head` |
| Alembic status | `cd backend && ../.venv/bin/alembic current` |
| Start backend | `cd backend && ../.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000` |
| Build frontend | `cd frontend && npm run build` |
| Start frontend (standalone) | `HOSTNAME=127.0.0.1 PORT=3000 NEXT_PUBLIC_API_URL=http://127.0.0.1:8000 node .next/standalone/server.js` |
| Batch CLI | `.venv/bin/carddemo-batch [run-all|seed-all|job|load]` |
| Backend tests | `cd backend && ../.venv/bin/pytest` |
| Batch tests | `.venv/bin/pytest batch/tests` |
| Frontend tests | `cd frontend && CI=true npm test` |
| Frontend type-check | `cd frontend && npm run type-check` |
| Full stack (Docker) | `docker compose --profile full up -d --build` |

### B. Port Reference

| Service | Port | Bind | Notes |
|---------|------|------|-------|
| PostgreSQL 17 | 5432 | 127.0.0.1 | `docker compose` `db` service |
| FastAPI backend | 8000 | 127.0.0.1 | `/health`, `/health/ready`, `/api/v1/*` |
| Next.js frontend | 3000 | 127.0.0.1 | entry route `/signon` |

### C. Key File Locations

| Area | Path |
|------|------|
| Backend app factory | `backend/app/main.py` |
| Settings (pydantic-settings) | `backend/app/core/config.py` |
| Security (bcrypt, JWT/session) | `backend/app/core/security.py` |
| ORM models (10) | `backend/app/models/` |
| Services (8 clusters) | `backend/app/services/` |
| API routers (8) | `backend/app/api/v1/` |
| Zoned-decimal codec | `backend/app/utils/decimal_utils.py` |
| Alembic migrations (0001–0009) | `backend/alembic/versions/` |
| Batch jobs / loaders / orchestration | `batch/jobs/`, `batch/loaders/`, `batch/orchestration/batch_chain.py` |
| Batch CLI | `batch/cli.py` |
| Frontend pages (17) | `frontend/src/app/**/page.tsx` |
| Frontend theme / shell | `frontend/src/app/theme.ts`, `frontend/src/app/layout.tsx` |
| API client | `frontend/src/lib/apiClient.ts` |
| Compose topology | `docker-compose.yml` |
| Env templates | `backend/.env.example`, `frontend/.env.local.example` |
| Docs | `docs/` (README, api-reference, architecture, batch, data-model, traceability) |
| Legacy reference (untouched) | `app/` (cbl, cpy, bms, cpy-bms, jcl, proc, data) |

### D. Technology Versions

| Layer | Component | Version |
|-------|-----------|---------|
| Backend runtime | Python | 3.13.7 |
| Backend | FastAPI | 0.136.1 |
| Backend | Uvicorn | 0.34.0 |
| Backend | Pydantic | 2.10.4 |
| Backend | pydantic-settings | 2.7.1 |
| Backend | SQLAlchemy | 2.0.36 |
| Backend | Alembic | 1.14.0 |
| Backend | asyncpg / psycopg2-binary | 0.30.0 / 2.9.10 |
| Backend | passlib / bcrypt | 1.7.4 / 4.0.1 |
| Backend | PyJWT | 2.13.0 |
| Backend | Typer | 0.27.0 |
| Backend | reportlab / Jinja2 | 4.5.1 / 3.1.6 |
| Test | pytest / httpx | 9.1.1 / 0.28.1 |
| Database | PostgreSQL | 17 (verified 17.10) |
| Frontend runtime | Node.js / npm | 22.23.1 / 11.18.0 |
| Frontend | Next.js | 16.2.11 |
| Frontend | React / react-dom | 19.2.7 |
| Frontend | @mui/material | 9.2.0 |
| Frontend | @mui/material-nextjs | 9.1.1 |
| Frontend | @mui/icons-material | 9.2.0 |
| Frontend | @emotion/react | 11.x |
| Frontend | axios / TypeScript | 1.x / 5.x |

### E. Environment Variable Reference

| Variable | Scope | Example / Default | Notes |
|----------|-------|-------------------|-------|
| `DATABASE_URL` | Backend | `postgresql+asyncpg://carddemo:carddemo@localhost:5432/carddemo` | Async engine (runtime) |
| `SYNC_DATABASE_URL` | Backend/Batch | `postgresql+psycopg2://carddemo:carddemo@localhost:5432/carddemo` | Alembic + batch |
| `SECRET_KEY` | Backend | _(generate)_ `openssl rand -hex 32` | ≥ 32 chars; app fails closed otherwise |
| `AUTH_MODE` | Backend | `session` | `session` baseline or `jwt` |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | Backend | `60` | Session/token lifetime |
| `SESSION_COOKIE_NAME` | Backend | `carddemo_session` | HttpOnly cookie name |
| `BCRYPT_ROUNDS` | Backend | `12` | Password hash cost |
| `LOGIN_MAX_ATTEMPTS` / `LOGIN_LOCKOUT_SECONDS` | Backend | `5` / `900` | Login throttle |
| `BACKEND_CORS_ORIGINS` | Backend | `http://localhost:3000` | Comma-separated origins |
| `DB_CONNECT_TIMEOUT_SECONDS` / `DB_COMMAND_TIMEOUT_SECONDS` / `HEALTH_READY_TIMEOUT_SECONDS` | Backend | `10.0` / `30.0` / `5.0` | Bounded timeouts |
| `ENABLE_API_DOCS` | Backend | _(unset)_ | Unset → served locally, 404 in prod |
| `NEXT_PUBLIC_API_URL` | Frontend | `http://localhost:8000` | Backend **origin only**; inlined at build |
| `CARDDEMO_ASCII_DIR` / `CARDDEMO_EBCDIC_DIR` | Seed/Batch | `/app/data/ASCII` · `/app/data/EBCDIC` | Seed input locations (container) |

### F. Developer Tools Guide

- **Interactive API docs:** with a local profile, browse `http://127.0.0.1:8000/docs` (Swagger) / `/redoc`; raw schema at `/openapi.json`. In staging/production these return 404 by default (reduced API-surface disclosure).
- **Database shell:** `docker exec -it <db-container> psql -U carddemo -d carddemo`.
- **Migrations:** create with `alembic revision -m "..."`; inspect with `alembic history` / `alembic current` (run from `backend/`).
- **Frontend lint/type-check:** `npm run lint` / `npm run type-check` (strict).
- **Runtime evidence:** browser screenshots and screen recordings from autonomous E2E are under `blitzy/screenshots` and `blitzy/screen_recordings`.

### G. Glossary

| Term | Meaning |
|------|---------|
| AAP | Agent Action Plan — the authoritative migration specification |
| BMS | Basic Mapping Support — legacy 3270 screen definitions (→ Next.js pages) |
| CICS | Customer Information Control System — legacy transaction monitor (→ FastAPI) |
| COMMAREA | CICS communication area carrying identity/state (→ session/JWT) |
| VSAM KSDS | Key-Sequenced Data Set — legacy indexed storage (→ PostgreSQL tables) |
| AIX | VSAM Alternate Index (→ UNIQUE/secondary index) |
| Zoned decimal | Signed DISPLAY numeric with overpunch sign in last digit's zone nibble |
| Golden-master parity | Field-for-field comparison of new outputs vs legacy sample outputs |
| F-004 | Requirement: card browse returns ≤ 7 rows per page |
| Posting codes 100–103/109 | Transaction-posting validation reason codes (preserved exactly) |
| Ochs Rule | User-specified coding standard (naming, no hardcoded secrets, specific exceptions) |

---

### Cross-Section Integrity — Verified

- **Rule 1 (1.2 ↔ 2.2 ↔ 7):** Remaining hours = **180** in Section 1.2 metrics, Section 2.2 total, and Section 7 pie "Remaining Work". ✔
- **Rule 2 (2.1 + 2.2 = Total):** 1,140 + 180 = **1,320** = Section 1.2 Total. ✔
- **Rule 3 (Section 3):** All 1,284 tests originate from Blitzy's autonomous test-execution logs. ✔
- **Rule 4 (Section 1.5):** Access items are production environment/credential dependencies; the repository itself has no permission blockers. ✔
- **Rule 5 (Colors):** Completed = Dark Blue `#5B39F3`; Remaining = White `#FFFFFF` throughout. ✔
- **Completion % consistency:** 86.4% stated identically in Sections 1.2, 7, and 8. ✔