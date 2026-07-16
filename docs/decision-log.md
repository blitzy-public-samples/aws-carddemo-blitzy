# CardDemo Migration — Decision Log

This decision log records **every non-trivial architectural and technical decision** made while
re-platforming the AWS CardDemo mainframe application (COBOL, CICS, VSAM, JCL) into a functionally
equivalent Java 25 LTS + Spring Boot 3.x application, together with **every deliberate deviation
from a literal COBOL translation**. It exists to satisfy the project's **Explainability rule**: for
each decision it captures *what* was decided, the *alternatives* considered, the *rationale*, and the
*risk with its mitigation* — so that reviewers can trace and challenge any choice without reading the
code. Per that same rule, **rationale lives here, not in code comments.**

This log is a companion to the [architecture overview](./architecture.md) and the
[traceability matrix](./traceability-matrix.md); the traceability matrix maps every COBOL paragraph to
its Java target, while this log explains the reasoning behind the design those mappings realize.

## How to read this log

- Each decision has a stable identifier (`D1`, `D2`, …) that is referenced from the architecture
  document, the traceability matrix, and pull-request descriptions. Identifiers are never reused.
- Every decision is currently **Accepted**. Should a decision be revisited, its status would change to
  *Superseded by Dn* rather than being deleted, preserving the history.
- Every entry has the same four dimensions, and none is ever left blank:
  - **Decision** — the choice that was made.
  - **Alternatives** — the credible options that were not chosen.
  - **Rationale** — why the decision was made, tied to concrete AAP references and COBOL evidence.
  - **Risk & mitigation** — what could go wrong and how the design contains it.
- A **Type** line classifies each decision. Decisions labelled
  **Intentional improvement (deviation from literal COBOL)** change the *implementation mechanism*
  while preserving the *observable behavior*; they are flagged explicitly so they are **never mistaken
  for behavioral regressions**. All other decisions preserve behavior directly.
- COBOL source references use the post-migration `legacy/**` paths (the original `app/**` tree is
  relocated to `legacy/**` and retained read-only for reference).

## Decision index

| ID | Section | Decision | Type |
|----|---------|----------|------|
| [D1](#d1--java-25-lts-as-the-target-runtime) | A. Platform & Build | Java 25 LTS as the target runtime | Platform selection |
| [D2](#d2--spring-boot-3516-honoring-the-3x-pin) | A. Platform & Build | Spring Boot 3.5.16 (honoring the 3.x pin) | Platform selection (with lifecycle risk) |
| [D3](#d3--maven-39-over-gradle-8x) | A. Platform & Build | Maven 3.9+ over Gradle 8.x | Build-tool selection |
| [D4](#d4--single-module-modular-monolith-package-by-layer) | A. Platform & Build | Single-module modular monolith, package-by-layer | Architecture shape |
| [D5](#d5--owasp-dependency-check-1222-with-failbuildoncvss) | A. Platform & Build | OWASP dependency-check 12.2.2 with `failBuildOnCVSS` | Security gate |
| [D6](#d6--externalized-credentials-no-hardcoded-secrets) | A. Platform & Build | Externalized credentials, no hardcoded secrets | Security constraint |
| [D7](#d7--postgresql-16-as-the-relational-target) | B. Data Tier | PostgreSQL 16 as the relational target | Data-store selection |
| [D8](#d8--real-foreign-key-constraints) | B. Data Tier | Real foreign-key constraints | **Intentional improvement** |
| [D9](#d9--bigdecimal-scale-2-money-value-object) | B. Data Tier | `BigDecimal` scale-2 `Money` value object | Behavior preservation |
| [D10](#d10--alternate-indexes-to-b-tree-indexes-vsam-browse-to-sortedpaged-queries) | B. Data Tier | Alternate indexes to B-tree; browse to sorted/paged queries | Behavior preservation |
| [D11](#d11--flyway-for-schema-and-reference-data-migrations) | B. Data Tier | Flyway for schema and reference-data migrations | Tooling selection |
| [D12](#d12--cics-pseudo-conversational-to-stateless-rest--flow-context) | C. Online/Batch | CICS pseudo-conversational to stateless REST + flow context | Behavior preservation |
| [D13](#d13--bms-screens-to-rest-requestresponse-dtos) | C. Online/Batch | BMS screens to REST request/response DTOs | Behavior preservation |
| [D14](#d14--chunk-oriented-spring-batch-scheduling-moves-to-cicd) | C. Online/Batch | Chunk-oriented Spring Batch; scheduling to CI/CD | Behavior preservation |
| [D15](#d15--typed-exception-hierarchy-for-file-status--cics-resp) | C. Online/Batch | Typed exception hierarchy for FILE STATUS / CICS RESP | Behavior preservation |
| [D16](#d16--fixedwidthcodec-preserves-external-file-layouts) | C. Online/Batch | `FixedWidthCodec` preserves external file layouts | Contract preservation |
| [D17](#d17--le-services-to-jvmjavatime-call-to-injected-beans) | C. Online/Batch | LE services to JVM/`java.time`; `CALL` to injected beans | Behavior preservation |
| [D18](#d18--jpa-optimistic-locking-version) | D. Concurrency & Integrity | JPA optimistic locking (`@Version`) | **Intentional improvement** |
| [D19](#d19--hand-written-mappers-not-mapstruct) | E. Mapping & Code Style | Hand-written mappers (not MapStruct) | Implementation strategy |
| [D20](#d20--code-style-constraints-constructor-injection-jakarta-no-wildcards-zero-warning) | E. Mapping & Code Style | Code-style constraints (Jakarta, constructor injection, zero-warning) | Quality constraint |
| [D21](#d21--testing-strategy-testcontainers-jacoco-80-golden-file-parity) | E. Mapping & Code Style | Testing strategy (Testcontainers, JaCoCo 80%, golden-file parity) | Quality strategy |
| [D22](#d22--password-hashing-and-cvv-hardening) | F. Security | Password hashing and CVV hardening | **Intentional improvement** |
| [D23](#d23--observability-stack-logs-traces-metrics-dashboard) | G. Observability & Ops | Observability stack (logs, traces, metrics, dashboard) | Non-functional addition |
| [D24](#d24--mq--racf--3270-emulation-and-aws-m2-runtime-out-of-scope) | G. Observability & Ops | MQ / RACF / 3270 emulation / AWS M2 runtime out of scope | Scope boundary |

---

## A. Platform & Build

### D1 — Java 25 LTS as the target runtime

- **Status:** Accepted
- **Type:** Platform selection (behavior-preserving)
- **AAP references:** §0.1.1 (G1), §0.6.1, §0.10.4
- **Decision:** Target **Java 25 (LTS)** as the language and runtime for the migrated application,
  compiled and run through the Maven wrapper.
- **Alternatives:** Java 21 (the previous LTS); a non-LTS interim release (e.g. Java 23/24). Both were
  rejected in favour of the newest long-term-support line.
- **Rationale:** The prompt explicitly targets *Java 25 LTS + Spring Boot 3.x*. Java 25 reached general
  availability in September 2025 and is the current long-term-support release, succeeding Java 21, so it
  provides the longest support runway while still being fully supported by the chosen Spring Boot line
  (see [D2](#d2--spring-boot-3516-honoring-the-3x-pin)). The Language Environment runtime services that
  the COBOL programs relied upon are replaced by the JVM and `java.time` (see
  [D17](#d17--le-services-to-jvmjavatime-call-to-injected-beans)).
- **Risk & mitigation:** Adopting the newest LTS can expose the project to early-adopter toolchain gaps
  (build plugins, static-analysis tools, container base images). *Mitigation:* the build is pinned to
  exact, verified plugin versions; the Docker base image is a Temurin 25 image; and continuous
  integration compiles and tests on Java 25 so any incompatibility surfaces immediately rather than in
  production.

### D2 — Spring Boot 3.5.16 (honoring the 3.x pin)

- **Status:** Accepted
- **Type:** Platform selection — carries a documented lifecycle risk
- **AAP references:** §0.6.1, §0.8.4, §0.10.4
- **Decision:** Pin the application to **Spring Boot 3.5.16**, the final stable release of the 3.x line,
  inherited through `org.springframework.boot:spring-boot-starter-parent`. The 3.5 line supports Java 17
  through Java 25, so it pairs cleanly with [D1](#d1--java-25-lts-as-the-target-runtime).
- **Alternatives:** **Spring Boot 4.x** (newer, with a longer forward support horizon). This was *not*
  chosen because the user explicitly requested "Spring Boot 3.x (latest stable)", and the OpenAPI
  integration used here (`springdoc-openapi-starter-webmvc-ui` 2.8.17) targets the Spring Boot 3.x line,
  whereas springdoc 3.0.x targets Spring Boot 4.0.
- **Rationale:** The platform does not unilaterally deviate from an explicit user version pin. Honoring
  the 3.x request with its final, most-patched release (3.5.16) delivers the newest bug/security fixes
  available *within the requested line* and keeps the dependency set internally consistent.
- **Risk & mitigation:** The open-source support lifecycle of the 3.5 line ends relatively soon, after
  which security patches require a commercial arrangement or an upgrade. *Mitigation:* this risk is
  recorded here with **Spring Boot 4.x captured as the forward-looking upgrade path**; the BOM-managed
  dependency strategy (see [D20](#d20--code-style-constraints-constructor-injection-jakarta-no-wildcards-zero-warning))
  keeps transitive versions aligned so a future major upgrade is a contained, reviewable change.

### D3 — Maven 3.9+ over Gradle 8.x

- **Status:** Accepted
- **Type:** Build-tool selection (behavior-preserving) — **mandatory decision**
- **AAP references:** §0.8.4
- **Decision:** Build with **Apache Maven 3.9+**, invoked through the committed `./mvnw` wrapper so the
  exact build version is reproducible on any machine without a local install.
- **Alternatives:** **Gradle 8.x** — the prompt explicitly allowed "Maven 3.9+ *or* Gradle 8.x".
- **Rationale:** Maven is selected as the deterministic enterprise default. Its declarative POM model,
  first-class Spring Boot plugin support, and mature OWASP `dependency-check-maven` integration (see
  [D5](#d5--owasp-dependency-check-1222-with-failbuildoncvss)) make the security and packaging gates
  straightforward and predictable, which directly serves the "each deliverable independently reviewable"
  objective.
- **Risk & mitigation:** Teams more fluent in Gradle may find Maven's XML verbosity or its build-lifecycle
  model less familiar. *Mitigation:* the `mvnw`/`mvnw.cmd` wrapper removes any local-install friction, and
  **Gradle 8.x is recorded here as the accepted alternative** so a future switch is a documented,
  low-surprise change rather than an undocumented reversal.

### D4 — Single-module modular monolith, package-by-layer

- **Status:** Accepted
- **Type:** Architecture shape (behavior-preserving)
- **AAP references:** §0.4, §0.6.4
- **Decision:** Deliver a **single-module Spring Boot application** organized **package-by-layer** under
  the root package `com.aws.carddemo` (`config`, `domain`, `repository`, `dto`, `mapper`, `web`,
  `service`, `batch`, `exception`, `observability`, `security`, `common`).
- **Alternatives:** A **multi-module Maven build** (for example, separate modules for domain, web, and
  batch) with enforced compile-time module boundaries.
- **Rationale:** A single module mirrors the cohesion of the original COBOL monolith while introducing
  clean layer boundaries, and it keeps each deliverable small and independently reviewable — precisely
  the iterative-delivery goal. It avoids the ceremony of cross-module version management before the
  boundaries have stabilized.
- **Risk & mitigation:** Without compile-time module walls, layering violations (for example, a
  controller reaching directly into a repository) can creep in. *Mitigation:* the package-by-layer
  structure makes violations visible in review, constructor injection makes dependencies explicit (see
  [D20](#d20--code-style-constraints-constructor-injection-jakarta-no-wildcards-zero-warning)), and a
  multi-module split is **recorded as the available refactor** should the codebase outgrow a single
  module.

### D5 — OWASP dependency-check 12.2.2 with `failBuildOnCVSS`

- **Status:** Accepted
- **Type:** Security gate (behavior-preserving)
- **AAP references:** §0.6.1, §0.9.3
- **Decision:** Run **`org.owasp:dependency-check-maven` version 12.2.2** in the build and configure its
  `failBuildOnCVSS` threshold so that any dependency carrying a **critical or high CVE fails the build**.
- **Alternatives:** A softer, report-only scan that never fails the build; a third-party commercial SCA
  service; or no automated software-composition analysis at all.
- **Rationale:** The migration constraint mandates *zero critical/high CVEs*. A hard build gate is the
  only way to enforce that continuously rather than relying on manual review, and running it inside the
  Maven lifecycle keeps the check reproducible and reviewable alongside every change.
- **Risk & mitigation:** Newly disclosed CVEs can break an otherwise-unchanged build, and the analyzer's
  network access to vulnerability feeds can slow or destabilize CI. *Mitigation:* the plugin version is
  pinned; the CVSS threshold is explicit and reviewable; and the gate runs in CI where a fresh advisory
  is surfaced as an actionable failure with a clear upgrade target.

### D6 — Externalized credentials, no hardcoded secrets

- **Status:** Accepted
- **Type:** Security constraint (behavior-preserving)
- **AAP references:** §0.8.1, §0.9.3
- **Decision:** Resolve **every credential, connection string, and secret from environment variables**
  (for example `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`), with
  **no secret value committed** to source or configuration.
- **Alternatives:** Hardcoding connection details in `application.yml`; committing a populated `.env`
  file; or embedding credentials in the container image.
- **Rationale:** The prompt forbids hardcoded credentials. Externalized configuration keeps secrets out
  of version control, lets the same artifact run unchanged across environments, and is the prerequisite
  for later integration with a managed secrets store.
- **Risk & mitigation:** A missing environment variable can prevent the application from starting, and a
  misconfigured environment can point at the wrong database. *Mitigation:* configuration keys reference
  environment variables by name only (never literal secrets); local development uses a documented,
  non-production `docker-compose` database whose values are supplied through the environment; and the
  onboarding guide enumerates the required variables so a clean machine can be brought up without
  guesswork.


---

## B. Data Tier

### D7 — PostgreSQL 16 as the relational target

- **Status:** Accepted
- **Type:** Data-store selection (behavior-preserving)
- **AAP references:** §0.4.3, §0.6.1
- **Decision:** Migrate the VSAM KSDS data tier to **PostgreSQL 16**, with each of the ten indexed
  datasets becoming a table accessed through Spring Data JPA.
- **Alternatives:** A different relational engine (for example MySQL or a commercial RDBMS); a
  document/key-value store; or retaining an indexed-file store to stay closer to VSAM's record-at-a-time
  paradigm.
- **Rationale:** The user specified "PostgreSQL 16+". PostgreSQL 16 is a mature, open-source relational
  engine with the exact-precision `DECIMAL` type required for monetary fidelity (see
  [D9](#d9--bigdecimal-scale-2-money-value-object)), declarative referential integrity (see
  [D8](#d8--real-foreign-key-constraints)), and the B-tree indexing needed to reproduce VSAM's
  alternate-index browse patterns (see
  [D10](#d10--alternate-indexes-to-b-tree-indexes-vsam-browse-to-sortedpaged-queries)).
- **Risk & mitigation:** Moving from record-at-a-time indexed files to set-based relational access is the
  single most consequential structural change and can subtly alter ordering or null semantics.
  *Mitigation:* every field's name, type, length, and semantics are preserved; unique keys become primary
  keys; and integration tests run against a real PostgreSQL 16 instance via Testcontainers (see
  [D21](#d21--testing-strategy-testcontainers-jacoco-80-golden-file-parity)) rather than an embedded
  substitute.

### D8 — Real foreign-key constraints

- **Status:** Accepted
- **Type:** **Intentional improvement (deviation from literal COBOL)**
- **AAP references:** §0.2.3, §0.4.3, §0.7.1 (H5)
- **Decision:** Enforce the previously application-only relationships as **real PostgreSQL foreign-key
  constraints**: `account.group_id → disclosure_group`; `card.acct_id → account`;
  `card_xref.cust_id → customer` and `card_xref.acct_id → account`; and `transaction.card_num → card`,
  `transaction.type_cd → transaction_type`, `transaction.cat_cd → transaction_category`.
- **Alternatives:** Keep referential integrity enforced **only in application code**, exactly as the
  COBOL programs did against VSAM (the literal behavior).
- **Rationale:** In VSAM these relationships existed but were enforced solely by program logic; the
  relational target can enforce them **declaratively** at the database. This strengthens data integrity
  without altering any observable business behavior, and it makes the data model self-documenting. This
  is explicitly an **intentional improvement, not a behavioral change.**
- **Risk & mitigation:** A foreign key could reject an insert or delete that the looser COBOL code would
  have tolerated, which — if unmanaged — could look like a regression. *Mitigation:* seed and load order
  respects dependency order (parents before children); the change is labelled here as a deliberate
  integrity improvement so it is **never mistaken for a regression**; and cross-referenced with
  [D11](#d11--flyway-for-schema-and-reference-data-migrations) where the constraints are created.

### D9 — `BigDecimal` scale-2 `Money` value object

- **Status:** Accepted
- **Type:** Behavior preservation (exact-computation fidelity)
- **AAP references:** §0.1.3, §0.4.2, §0.7.1 (H3)
- **Decision:** Represent **all monetary values** with a `Money` value object backed by **`BigDecimal` at
  scale 2** with an explicit **`RoundingMode.HALF_UP`**, and store them in `DECIMAL(x,2)` columns.
  Monetary arithmetic is centralized in the value object. The interest computation is reproduced exactly:
  the COBOL `COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` in `1300-COMPUTE-INTEREST`
  (`legacy/cbl/CBACT04C.cbl`) becomes
  `tranCatBal.multiply(intRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)`.
- **Alternatives:** `double`/`float` primitives (rejected — binary floating point cannot represent decimal
  currency exactly); a scaled `long` "minor units" representation (workable but obscures the direct
  correspondence to the COBOL `PIC S9(n)V99` layouts and the `COMPUTE` expressions).
- **Rationale:** COBOL monetary fields are `PIC S9(n)V99 COMP-3` packed decimals with well-defined
  rounding. `BigDecimal` at fixed scale with an explicit rounding mode is the only representation that
  reproduces that arithmetic bit-for-bit. `double`/`float` are prohibited for decimal values throughout
  the codebase.
- **Risk & mitigation:** Rounding-mode or intermediate-scale drift compounds across financial postings
  and would break parity to the cent. *Mitigation:* all arithmetic flows through the single `Money` value
  object so the scale and rounding mode are defined in one place, and golden-file parity tests assert the
  Java output equals the legacy computation **to the cent** (see
  [D21](#d21--testing-strategy-testcontainers-jacoco-80-golden-file-parity)).

### D10 — Alternate indexes to B-tree indexes; VSAM browse to sorted/paged queries

- **Status:** Accepted
- **Type:** Behavior preservation
- **AAP references:** §0.4.3, §0.7.1 (H5)
- **Decision:** Formalize the three VSAM **alternate indexes as ordinary B-tree indexes** (card→account,
  cross-reference→account, transaction→timestamp) and re-express **VSAM browse** (`STARTBR` /
  `READNEXT` / `READPREV` / `ENDBR`) as **sorted, paged repository queries** ordered by key. The
  transaction-ID generator's reverse browse — `MOVE HIGH-VALUES`, `STARTBR`, `READPREV`, `ENDBR`, then
  `ADD 1` in `ADD-TRANSACTION` (`legacy/cbl/COTRN02C.cbl`) — becomes a **repository max-key lookup plus
  one**.
- **Alternatives:** Emulate a stateful VSAM cursor across requests to mimic browse position literally;
  or generate transaction identifiers with a database sequence (which would diverge from the observed
  max-key-plus-one values).
- **Rationale:** Forward browse maps naturally to `Pageable` queries ordered by the unique key; the
  alternate-index access paths map to database indexes that preserve the same retrieval order; and the
  reverse-browse-from-`HIGH-VALUES` idiom is exactly a "largest existing key, then increment" operation.
  This preserves both the retrieval order and the identifier values callers observe.
- **Risk & mitigation:** A mismatch in key ordering or a pagination-boundary off-by-one would change which
  rows a screen returns, and concurrent inserts could race the max-key-plus-one generator. *Mitigation:*
  repository queries preserve the exact unique-key ordering; pagination boundaries are covered by tests;
  and identifier generation is guarded within a transactional boundary (see
  [D18](#d18--jpa-optimistic-locking-version)) so concurrent adds cannot collide.

### D11 — Flyway for schema and reference-data migrations

- **Status:** Accepted
- **Type:** Tooling selection (behavior-preserving)
- **AAP references:** §0.4.3, §0.5.5
- **Decision:** Create and evolve the database with **Flyway** versioned migrations —
  `V1__schema.sql` (ten tables, three indexes, foreign-key constraints) and `V2__reference_data.sql`
  (reference tables) — seeded from the delimited ASCII data under `legacy/data/ASCII/**`.
- **Alternatives:** Hibernate `ddl-auto` schema generation from entities; Liquibase; or hand-run SQL
  scripts applied outside the application lifecycle.
- **Rationale:** Versioned, checksum-validated migrations give a deterministic, auditable schema history
  that runs identically in every environment, which is essential for reproducing the exact table shapes
  the copybooks describe. Entity-driven `ddl-auto` is unsuitable for a system where the schema is a
  preserved external contract rather than a by-product of the code.
- **Risk & mitigation:** An edited-after-the-fact migration would fail Flyway's checksum validation, and
  seed order must respect the new foreign keys from [D8](#d8--real-foreign-key-constraints).
  *Mitigation:* migrations are treated as immutable once merged (fixes go in new versions); seed inserts
  are ordered parents-before-children; and the reference-data load counts are asserted by integration
  tests.


---

## C. Online / Batch Translation

### D12 — CICS pseudo-conversational to stateless REST + flow context

- **Status:** Accepted
- **Type:** Behavior preservation
- **AAP references:** §0.7.1 (H1)
- **Decision:** Translate the CICS **pseudo-conversational** model to **stateless REST**. The
  `CARDDEMO-COMMAREA` (`legacy/cpy/COCOM01Y.cpy`) becomes explicit **server-side flow/session context**;
  `EXEC CICS XCTL` program transfers become **controller navigation** that returns the next screen's DTO
  and a logical view identifier; and `EXEC CICS RETURN TRANSID(...)` re-entry becomes a **stateless POST
  per screen submit**. The first-time-versus-re-entry toggle `CDEMO-PGM-CONTEXT`
  (`88 CDEMO-PGM-ENTER VALUE 0`, `88 CDEMO-PGM-REENTER VALUE 1`) is modelled explicitly in every
  controller/service.
- **Alternatives:** HTTP session affinity that stores the full COMMAREA server-side to imitate the
  conversational cycle; or a stateful WebSocket channel per terminal session.
- **Rationale:** There is no direct HTTP analogue to the COMMAREA + `XCTL` + `RETURN TRANSID` cycle, so
  the navigation state is made explicit rather than hidden in a framework session. This keeps each screen
  submit independently testable and preserves screen-entry and navigation behavior.
- **Risk & mitigation:** Losing the first-time-versus-re-entry distinction (`CDEMO-PGM-CONTEXT`) would
  change how a screen initializes. *Mitigation:* the program-context flag is modelled as an explicit field
  on the flow context and carried through every controller and service so the initialize-versus-re-enter
  branch is reproduced exactly.

### D13 — BMS screens to REST request/response DTOs

- **Status:** Accepted
- **Type:** Behavior preservation (contract-level) — deviation from a literal 3270 UI
- **AAP references:** §0.1.3, §0.3.3, §0.7.1 (H2)
- **Decision:** Re-express each of the 17 BMS maps and their symbolic copybooks as a **request/response
  DTO pair** that preserves every field's **name, maximum length, PIC-derived type, and edit rules**, and
  turn **PF-key semantics** (PF3 = back, PF7/PF8 = page, Enter = submit) into **explicit action fields /
  enums**. No terminal emulator and no pixel-level 3270 rendering are produced.
- **Alternatives:** Build a 3270 terminal emulator or a faithful screen renderer; or design a brand-new
  web UI for the screens.
- **Rationale:** No design system was supplied and the migration forbids feature expansion, so the
  observable contract to preserve is the **field-level data contract and key semantics**, not a rendered
  screen. DTOs capture that contract precisely and keep it reviewable field-by-field. Rendering a new UI
  or an emulator would be feature expansion.
- **Risk & mitigation:** Dropping a field-level edit rule or a PF-key path would be a behavior regression.
  *Mitigation:* every field and every PF-key path appears as a one-to-one row in the traceability matrix,
  and Bean Validation rules on the DTOs reproduce the COBOL edit paragraphs.

### D14 — Chunk-oriented Spring Batch; scheduling moves to CI/CD

- **Status:** Accepted
- **Type:** Behavior preservation
- **AAP references:** §0.4.4, §0.7.2 (M4)
- **Decision:** Re-express each JCL-triggered batch program as a **chunk-oriented Spring Batch**
  `Job` composed of `ItemReader`/`ItemProcessor`/`ItemWriter` `Step`s. Job-to-job DD dependencies become
  **step/flow ordering**; `SORT`/`MERGE` utilities become **Java `Comparator`s or `ORDER BY` queries**;
  GDG-based backups become a **scheduled database-backup step**; and **job scheduling moves to the CI/CD
  workflow** (`.github/workflows/ci.yml`) rather than an in-application scheduler.
- **Alternatives:** An in-application scheduler (for example `@Scheduled` or Quartz); a single monolithic
  batch runner; or a standalone external workflow orchestrator.
- **Rationale:** Chunk-oriented steps reproduce the sequential, restartable nature of the COBOL batch
  programs while giving explicit control over commit intervals and return codes. Moving the *trigger* to
  CI/CD matches the JCL scheduler's role without embedding scheduling concerns in the application.
- **Risk & mitigation:** Reordering steps, or a different chunk-commit boundary, could change outputs or
  restart behavior. *Mitigation:* step and flow ordering mirrors the JCL DD dependencies; comparators
  preserve the exact sort keys; and golden-file tests compare batch outputs row-for-row (see
  [D21](#d21--testing-strategy-testcontainers-jacoco-80-golden-file-parity)).

### D15 — Typed exception hierarchy for FILE STATUS / CICS RESP

- **Status:** Accepted
- **Type:** Behavior preservation
- **AAP references:** §0.4.2, §0.7.2 (M1)
- **Decision:** Map COBOL `FILE STATUS` codes ('00', '22' duplicate, '23' not-found, '10' end-of-file)
  and CICS `RESP`/`RESP2` outcomes onto a **typed exception hierarchy** — `FileStatusException`, a
  `RejectCode` enum, and a `CicsRespMapper` — feeding a `GlobalExceptionHandler` that translates them to
  **HTTP status codes** for the online layer and to **batch return codes 0/4/8** for the batch layer. The
  posting reject reasons are preserved as an enum: **100** (cross-reference/card not found), **101**
  (account not found), **102** (over credit limit), **103** (transaction after account expiration) — as
  set in `legacy/cbl/CBTRN02C.cbl`.
- **Alternatives:** Propagate raw status-code strings/integers through method signatures as the COBOL did;
  or use unchecked generic exceptions without a typed hierarchy.
- **Rationale:** A typed hierarchy makes each caller-visible outcome explicit and centralizes the
  translation to HTTP status or batch return code in one place, preserving the outcomes callers observed
  while removing scattered status-code checks.
- **Risk & mitigation:** Reordering the posting validations would change which reject code a record
  receives (the checks are evaluated in a fixed order and are order-sensitive). *Mitigation:* the posting
  service reproduces the exact validation order and short-circuit behavior, and each reject path
  (100/101/102/103) has a dedicated test reproducing its legacy trigger condition.

### D16 — `FixedWidthCodec` preserves external file layouts

- **Status:** Accepted
- **Type:** Contract preservation
- **AAP references:** §0.7.2 (M2)
- **Decision:** Provide a **`FixedWidthCodec`** used by Spring Batch `FlatFileItemReader`/`Writer`
  components that preserves the **exact column positions and lengths** of the external fixed-width record
  contracts — the daily-transaction input (`DALYTRAN`), the reject output (`DALYREJS`, a 350-byte record),
  and the statement/report outputs.
- **Alternatives:** Emit CSV/JSON for the external file exchanges; or reproduce the legacy on-disk EBCDIC
  encoding with binary `COMP-3` fields.
- **Rationale:** The real external contract is the **fixed-width record layout**, so that layout is what
  must be preserved byte/semantically. Because the data now lives in PostgreSQL as native types, the
  target preserves the **documented external layout on exchange** rather than the legacy on-disk
  EBCDIC/`COMP-3` encoding — the observable file contract is identical while the storage representation
  modernizes.
- **Risk & mitigation:** A single-column offset or length error would corrupt every downstream consumer
  of these files. *Mitigation:* column positions and lengths are declared once in the codec and asserted
  by golden-file tests that compare produced records against fixtures derived from the legacy layouts.

### D17 — LE services to JVM/`java.time`; `CALL` to injected beans

- **Status:** Accepted
- **Type:** Behavior preservation
- **AAP references:** §0.5.7, §0.7.2 (M3)
- **Decision:** Replace Language Environment intrinsics and inter-program calls with JVM equivalents:
  date intrinsics such as `CEEDAYS` become `java.time` via a `DateUtils` helper
  (`legacy/cbl/CSUTLDTC.cbl`); LE abends (`CEE3ABD`) become **controlled Java exceptions / batch
  failures**; and static/dynamic `CALL`s become **constructor-injected Spring beans** (for example
  `CBSTM03B` → `StatementFileService`, `CSUTLDTC` → `DateValidationService`).
- **Alternatives:** Reimplement LE date maths by hand instead of using `java.time`; use a
  service-locator/static registry instead of dependency injection; or model abends as `System.exit`
  calls.
- **Rationale:** `java.time` provides correct, well-tested calendar arithmetic, so date-validation
  behavior is preserved without re-deriving epoch maths. Constructor injection reproduces the
  data-passing semantics of a `CALL` while making the dependency graph explicit and testable, and
  translating abends into typed exceptions keeps failure handling controlled rather than fatal to the JVM.
- **Risk & mitigation:** A subtle difference in date-boundary or era handling between `CEEDAYS` and
  `java.time`, or a changed failure mode where COBOL abended, could alter behavior. *Mitigation:* date
  helpers are unit-tested against the legacy validation cases, and abend sites map to specific exceptions
  that produce the same caller-visible outcome (HTTP error online, non-zero return code in batch — see
  [D15](#d15--typed-exception-hierarchy-for-file-status--cics-resp)).


---

## D. Concurrency & Integrity

### D18 — JPA optimistic locking (`@Version`)

- **Status:** Accepted
- **Type:** **Intentional improvement (deviation from literal COBOL)**
- **AAP references:** §0.4.2, §0.7.1 (H6)
- **Decision:** Add a JPA **`@Version` column** to the entities updated online (notably the account and
  category-balance records) so the COBOL **READ-UPDATE-REWRITE** cycle's integrity is reproduced with
  **optimistic locking** inside a `@Transactional` boundary.
- **Alternatives:** **Pessimistic locking** (`SELECT … FOR UPDATE`), which serializes concurrent updates
  at the cost of throughput and deadlock risk; or **no locking at all**, which is closest to the literal
  COBOL but permits lost updates under concurrency.
- **Rationale:** The online posting/update paths read a record, modify it, and rewrite it. Optimistic
  locking provides **last-writer integrity** — a concurrent modification is detected on write and the
  transaction is retried or rejected rather than silently overwriting — while keeping the common,
  uncontended path lock-free. This is explicitly an **intentional integrity improvement, not a behavioral
  change** to the business logic.
- **Risk & mitigation:** Under genuine concurrency, optimistic locking may reject an update that the
  lock-free COBOL would have accepted (last-writer-wins by luck), which could be misread as a regression.
  *Mitigation:* the behavior is documented **here** as a deliberate integrity improvement so it is never
  mistaken for a regression; the `@Version` check is scoped to the records that participate in the
  read-modify-write cycle; and identifier generation from
  [D10](#d10--alternate-indexes-to-b-tree-indexes-vsam-browse-to-sortedpaged-queries) is guarded within the
  same transactional boundary.


---

## E. Mapping & Code Style

### D19 — Hand-written mappers (not MapStruct)

- **Status:** Accepted
- **Type:** Implementation strategy (behavior-preserving) — **mandatory decision**
- **AAP references:** §0.4.2, §0.6.5
- **Decision:** Implement entity↔DTO mapping with **explicit, hand-written field-by-field mappers** under
  the `mapper/` package.
- **Alternatives:** **MapStruct** (an annotation-processor that generates mappers at compile time); or a
  reflection-based mapping library (for example ModelMapper).
- **Rationale:** Explicit mapping keeps every field assignment **visible in source**, which directly
  supports the mandated 100% field/paragraph traceability — each mapped field corresponds to a row in the
  traceability matrix. It also avoids introducing an unverified annotation-processor into the build, which
  keeps the zero-warning compile (see
  [D20](#d20--code-style-constraints-constructor-injection-jakarta-no-wildcards-zero-warning)) and the
  dependency-scan surface (see [D5](#d5--owasp-dependency-check-1222-with-failbuildoncvss)) simpler.
- **Risk & mitigation:** Hand-written mappers add boilerplate and can drift from the entity/DTO if a
  field is added on one side only. *Mitigation:* every mapper is unit-tested for round-trip fidelity, and
  each field appears as a one-to-one row in the traceability matrix so an unmapped field is immediately
  visible in review.

### D20 — Code-style constraints (constructor injection, Jakarta, no wildcards, zero-warning)

- **Status:** Accepted
- **Type:** Quality constraint (behavior-preserving)
- **AAP references:** §0.6.4, §0.9.1
- **Decision:** Enforce a uniform code style: **constructor injection only** (no field `@Autowired`);
  the **Jakarta namespace only** (`jakarta.*`, never `javax.*`, as required by Spring Boot 3.x);
  **no wildcard imports** (explicit imports only); and a **zero-warning build** under Java 25.
- **Alternatives:** Field/setter injection for brevity; tolerating framework/deprecation warnings;
  allowing wildcard imports for compactness.
- **Rationale:** Constructor injection makes dependencies explicit and immutable and keeps components
  unit-testable without a container. The Jakarta namespace is mandatory on Spring Boot 3.x. Explicit
  imports and a zero-warning build keep each diff clean and independently reviewable and prevent latent
  deprecation debt.
- **Risk & mitigation:** Treating warnings as errors can make a transitive library deprecation break the
  build. *Mitigation:* the constraints are checked in CI so violations surface immediately with a precise
  location, and the BOM-managed dependency set from [D2](#d2--spring-boot-3516-honoring-the-3x-pin) keeps
  transitive versions aligned to reduce surprise deprecations.

### D21 — Testing strategy (Testcontainers, JaCoCo 80%, golden-file parity)

- **Status:** Accepted
- **Type:** Quality strategy (behavior-preserving)
- **AAP references:** §0.9.2
- **Decision:** Verify the migration with three layers of tests: **Testcontainers** running a **real
  PostgreSQL 16** instance for integration tests; a **JaCoCo line-coverage gate of ≥80%** across unit and
  integration tests; and **golden-file parity tests** that compare batch outputs **row-for-row** against
  fixtures derived from the legacy record layouts — asserting the interest calculation to the cent and
  exercising each posting reject code (100/101/102/103).
- **Alternatives:** An embedded/in-memory database (for example H2) for integration tests; a lower or
  absent coverage gate; assertion-only tests without row-for-row golden files.
- **Rationale:** An in-memory database cannot faithfully reproduce PostgreSQL 16 semantics
  (see [D7](#d7--postgresql-16-as-the-relational-target)), so Testcontainers exercises the exact engine
  used in production. The coverage gate enforces the ≥80% constraint objectively, and golden-file parity
  is the decisive check that business outputs match the COBOL to the cent and byte.
- **Risk & mitigation:** Testcontainers requires a working Docker daemon, which can be absent in some CI
  runners, and golden fixtures can drift if regenerated carelessly. *Mitigation:* CI provisions Docker
  for the integration phase; the Maven Failsafe plugin is bound so `*IT` tests run under `verify`; and
  golden fixtures are derived directly from the documented legacy layouts and seed data so they are
  regenerable and reviewable.


---

## F. Security

### D22 — Password hashing and CVV hardening

- **Status:** Accepted
- **Type:** **Intentional improvement (deviation from literal COBOL)**
- **AAP references:** §0.1.3, §0.7.3 (L1), §0.8.3
- **Decision:** Introduce **password hashing** and **CVV-handling hardening** — the card CVV is **never
  logged and never returned in full**, and passwords are **never logged** — while **preserving the
  authentication behavior**: the role model maps `SEC-USR-TYPE` `A` = Admin and `U` = User, matching the
  COMMAREA condition names `88 CDEMO-USRTYP-ADMIN VALUE 'A'` / `88 CDEMO-USRTYP-USER VALUE 'U'`
  (`legacy/cpy/COCOM01Y.cpy`). Authentication is served by a Spring Security `UserDetailsService` over the
  user table.
- **Alternatives:** Preserve the legacy anti-patterns **literally** — the plaintext password field
  `SEC-USR-PWD PIC X(08)` (`legacy/cpy/CSUSR01Y.cpy`) stored as-is, and the CVV stored/returned/logged
  without restriction.
- **Rationale:** The legacy plaintext-password storage and unencrypted CVV are **intentional
  demonstration anti-patterns** in the mainframe sample, not business requirements. Hashing passwords and
  restricting CVV exposure is a responsible, industry-standard hardening that **does not change the
  authentication *behavior*** — the same credentials authenticate the same users into the same roles.
- **Risk & mitigation:** Because the storage mechanism changes, this could be misread as scope creep or a
  behavior change. *Mitigation:* it is flagged **here** explicitly as an intentional security improvement,
  not a new feature; the observable auth outcome (who can log in, and with which role) is unchanged and
  covered by tests; and all credentials are externalized with **no hardcoded secrets** (see
  [D6](#d6--externalized-credentials-no-hardcoded-secrets)).


---

## G. Observability & Operations

### D23 — Observability stack (logs, traces, metrics, dashboard)

- **Status:** Accepted
- **Type:** Non-functional addition (behavior-preserving)
- **AAP references:** §0.8.2, §0.8.4, §0.9.5
- **Decision:** Ship a framework-native observability stack: **Logback** structured logging carrying a
  **correlation ID** that propagates across service and batch boundaries; **Micrometer + OpenTelemetry**
  distributed tracing exported over **OTLP** (viewable locally in Tempo); a **Prometheus** metrics
  endpoint and **health/readiness** checks via **Spring Boot Actuator**; and a **Grafana dashboard**
  template (`docs/observability/grafana-dashboard.json`). All of it is verified to work in the local
  `docker-compose` environment.
- **Alternatives:** Ship no observability (closest to the legacy system, which had none); rely on plain
  unstructured logging only; or defer observability to a later, separate initiative.
- **Rationale:** CardDemo has no prior observability stack, so this is delivered as **new,
  framework-native tooling**. The apparent tension with the "no feature expansion" constraint is
  resolved because **observability is a non-functional operational concern that wraps the preserved logic
  without altering it** — it changes what operators can *see*, not what the application *does*.
- **Risk & mitigation:** Tracing/metrics export can add overhead or, if misconfigured, leak sensitive
  data into logs. *Mitigation:* the tracing exporter endpoint is supplied via environment variable and is
  optional for local runs; the CVV is never logged and passwords are never logged (see
  [D22](#d22--password-hashing-and-cvv-hardening)); and the full stack is validated locally against the
  bundled `docker-compose` observability services.

### D24 — MQ / RACF / 3270 emulation and AWS M2 runtime out of scope

- **Status:** Accepted
- **Type:** Scope boundary (behavior-preserving)
- **AAP references:** §0.3.3
- **Decision:** Explicitly place the following **out of scope**: external **MQ** integration; **RACF /
  mainframe-security** replatform (only application-level `A`/`U` role parity is migrated, per
  [D22](#d22--password-hashing-and-cvv-hardening)); **physical 3270 terminal emulation / BMS screen
  rendering** (screens are contracts, per [D13](#d13--bms-screens-to-rest-requestresponse-dtos)); and the
  **AWS Mainframe Modernization (Micro Focus) runtime** under `samples/**`.
- **Alternatives:** Attempt to migrate one or more of these surfaces now (an MQ producer/consumer, an
  identity-provider integration, a screen renderer, or the M2 runtime tooling).
- **Rationale:** None of these represents existing CardDemo *business behavior*. MQ appears only as a
  roadmap item in the `README.md` (there is no message-queue code in the repository); RACF is
  infrastructure security rather than application logic; a 3270 emulator would be feature expansion; and
  the `samples/**` M2 tooling is reference material, not a migration target. Building any of them would
  violate the "no feature expansion" constraint.
- **Risk & mitigation:** Recording these as *absent* rather than *forgotten* matters — a reviewer could
  otherwise read their absence as an omission. *Mitigation:* they are documented **here** as deliberate
  scope boundaries; MQ is noted as README-roadmap-only; and if any becomes a genuine requirement it would
  be introduced through a new decision entry rather than silently.

---

## Related documents

- [Architecture overview](./architecture.md) — the layered target architecture these decisions realize.
- [Traceability matrix](./traceability-matrix.md) — the bidirectional, per-paragraph mapping from every
  COBOL construct to its Java target; each mapped field/paragraph corresponds to the design choices
  explained above.

