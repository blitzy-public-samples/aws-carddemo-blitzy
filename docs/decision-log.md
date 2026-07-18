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
| [D25](#d25--full-pan-and-account-number-in-master-print-output) | G. Observability & Ops | Full PAN / account number in master-print output (SYSOUT parity; PCI hardening deferred) | Behavior preservation (parity) + documented improvement |
| [D26](#d26--consistent-credential-case-normalization-across-authentication-surfaces) | F. Security | Consistent credential case-normalization across authentication surfaces | Behavior preservation (parity fix) |
| [D27](#d27--csrf-protection-disabled-stateless-http-basic-api) | F. Security | CSRF protection disabled (stateless HTTP Basic API) | Security posture |
| [D28](#d28--no-cors-configuration-same-origin-only) | F. Security | No CORS configuration (same-origin-only) | Security posture (scope boundary) |
| [D29](#d29--us-phone-number-edit-rule-fixes-a-latent-legacy-bug-optional-when-all-blank) | H. Validation & Edit-Rule Parity | US phone-number edit rule fixes a latent legacy bug (optional-when-all-blank) | **Intentional improvement** |

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
- **Risk & mitigation:** The open-source support lifecycle of the 3.5 line **has already ended**.
  Spring Boot **3.5.16 was released 2026-06-25** (the final open-source 3.5.x patch), and **free
  open-source support for the 3.5 line ended 2026-07-16**. Consequently this pin currently receives
  **no further open-source security patches**: continued patching requires either a **commercial
  support arrangement (Spring/VMware Tanzu Enterprise/Broadcom extended support)** or an **upgrade off
  the 3.5 line before any production deployment**. *Mitigation:* the pin is retained to honor the
  explicit user "Spring Boot 3.x (latest stable)" request at the most-patched 3.5 release, but this
  entry records the ended-support status as a **must-resolve-before-production** item, with
  **Spring Boot 4.x captured as the forward-looking upgrade path**; the BOM-managed dependency strategy
  (see [D20](#d20--code-style-constraints-constructor-injection-jakarta-no-wildcards-zero-warning))
  keeps transitive versions aligned so that major upgrade is a contained, reviewable change. Until then,
  the [OWASP dependency-check gate (D5)](#d5--owasp-dependency-check-1222-with-failbuildoncvss) and the
  explicit `<dependencyManagement>` CVE overrides recorded in that entry provide the interim
  compensating control for known transitive vulnerabilities.

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
- **Transitive-CVE overrides (applied in `pom.xml`):** because the frozen Spring Boot 3.5.16 BOM (see
  [D2](#d2--spring-boot-3516-honoring-the-3x-pin)) pulls transitive dependencies that carry known
  critical/high advisories, an explicit `<dependencyManagement>` block and a Tomcat version property
  pin the *managed* versions upward to the patched releases **without moving off the 3.x line**. These
  are deliberate, reviewable overrides, each tied to a specific advisory:
  - **`org.apache.commons:commons-compress` → 1.27.1** — remediates **CVE-2024-25710** (infinite loop /
    DoS) and **CVE-2024-26308** (memory exhaustion), both fixed in 1.26.0; the vulnerable 1.24.0 was
    reaching the build only through **test scope**, which is why the scan configuration change below is
    required for it to be seen.
  - **`org.apache.commons:commons-lang3` → 3.20.0** — remediates **CVE-2025-48924** (uncontrolled
    recursion / DoS in `ClassUtils`), fixed in 3.18.0.
  - **`tomcat.version` property → 10.1.57** — remediates **CVE-2026-55956** (and related advisories),
    fixed in the 10.1.56+ line; the Spring Boot BOM honors the `tomcat.version` property so the embedded
    Tomcat is upgraded while Spring Boot itself stays pinned at 3.5.16.
  - **`<skipTestScope>false</skipTestScope>`** (plus `skipProvidedScope`/`skipRuntimeScope` set false)
    on the dependency-check plugin — the analyzer skips test-scope artifacts by default, which had
    hidden the vulnerable test-scope `commons-compress`; disabling the skip ensures **all** resolved
    scopes are scanned. Resolution of the safe versions is proven by `mvn dependency:tree`
    (`tomcat-embed-core:10.1.57`, `commons-compress:1.27.1`, `commons-lang3:3.20.0`).

### D6 — Externalized credentials, no hardcoded secrets

- **Status:** Accepted
- **Type:** Security constraint (behavior-preserving)
- **AAP references:** §0.8.1, §0.9.3
- **Decision:** Resolve **every credential, connection string, and secret from environment variables**
  (for example `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`), with
  **no secret value committed** to source or configuration. The full resolution order that governs how
  these values are applied — **command-line arguments > OS environment variables > profile-specific
  `application-local.yml` > base `application.yml`** — is documented under
  [Configuration precedence](./architecture.md#configuration-precedence).
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
- **Decision:** Enforce, as **real PostgreSQL foreign-key constraints**, exactly those
  previously application-only relationships whose parent key is genuinely **unique** in the source
  layouts:
  - `card.acct_id → account(acct_id)`
  - `card_xref.cust_id → customer(cust_id)` and `card_xref.acct_id → account(acct_id)`
  - `transaction.card_num → card(card_num)`
  - `transaction.type_cd → transaction_type(type_cd)`
  - **composite** `(transaction.type_cd, transaction.cat_cd) → transaction_category(type_cd, cat_cd)`
    — `transaction_category` has a **compound** primary key (`CVTRA04Y`: `TRAN-TYPE-CD` X(02) +
    `TRAN-CAT-CD` 9(04)), so the child reference must also be the full pair, not `cat_cd` alone.

  **Explicitly NOT enforced as a foreign key:** `account.group_id → disclosure_group`. This relationship
  is **structurally impossible** to express as a single-column foreign key and is therefore modeled
  differently (see the deviation note below).
- **Alternatives:** (a) Keep *all* referential integrity enforced **only in application code**, exactly
  as the COBOL programs did against VSAM (the literal behavior). (b) Force a
  `account.group_id → disclosure_group` FK anyway by **inventing a synthetic single-column parent table**
  of distinct group ids that the legacy never had. Alternative (b) was rejected: fabricating a parent
  entity absent from the source would be an unfaithful structural change, not an integrity improvement.
- **Rationale:** In VSAM these relationships existed but were enforced solely by program logic; where the
  parent key is truly unique, the relational target can enforce them **declaratively** at the database,
  strengthening integrity without altering any observable behavior and making the model
  self-documenting. This is explicitly an **intentional improvement, not a behavioral change.**
- **Deviation — `account.group_id` is a grouping attribute, not a foreign key (source-faithful):** the
  disclosure-group record (`CVTRA02Y`, 50-byte) has a **composite** primary key —
  `DIS-ACCT-GROUP-ID` X(10) + `DIS-TRAN-TYPE-CD` X(02) + `DIS-TRAN-CAT-CD` 9(04). An account
  (`CVACT01Y`, 300-byte) carries only `ACCT-GROUP-ID` X(10). Because a group id **alone is not unique**
  in `disclosure_group` (many rows share one group id, one per type/category), it **cannot** be the
  target of a foreign key. The faithful model therefore keeps `account.group_id` as a **plain grouping
  attribute** and resolves the applicable disclosure/interest row with a **composite
  `(group_id, type_cd, cat_cd)` lookup at the application layer** — precisely the access path the COBOL
  interest program (`CBACT04C`) used against the `DISCGRP` file. This deviation is recorded so it is
  never mistaken for a missing constraint; it is the correct, source-faithful representation.
- **Risk & mitigation:** (1) An enforced foreign key could reject an insert or delete that the looser
  COBOL code would have tolerated, which — if unmanaged — could look like a regression. *Mitigation:*
  seed and load order respects dependency order (parents before children); the change is labelled here
  as a deliberate integrity improvement; and it is cross-referenced with
  [D11](#d11--flyway-for-schema-and-reference-data-migrations) where the constraints are created.
  (2) Modeling `group_id` without a foreign key could be misread as an omission. *Mitigation:* the
  deviation note above makes the composite-key reasoning explicit, and the composite disclosure lookup is
  captured in the [traceability matrix](./traceability-matrix.md) so the relationship remains visible
  and testable even though it is not a database constraint.

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
- **Decision:** Formalize the three VSAM **alternate indexes as ordinary B-tree indexes**
  — card→account, cross-reference→account, and **transaction→processing-timestamp** — and re-express
  **VSAM browse** (`STARTBR` / `READNEXT` / `READPREV` / `ENDBR`) as **sorted, paged repository queries**
  ordered by key. The chronological transaction browse (alternate index `TRANSACT.VSAM.AIX`) is ordered
  by the **processing timestamp** column `proc_ts` (see the disambiguation note below). The
  transaction-ID generator's reverse browse — `MOVE HIGH-VALUES`, `STARTBR`, `READPREV`, `ENDBR`, then
  `ADD 1` in `ADD-TRANSACTION` (`legacy/cbl/COTRN02C.cbl`) — becomes a **repository max-key lookup plus
  one**.
- **Alternatives:** Emulate a stateful VSAM cursor across requests to mimic browse position literally;
  or generate transaction identifiers with a database sequence (which would diverge from the observed
  max-key-plus-one values); or order the chronological browse by the **origination** timestamp
  (`orig_ts`) — rejected because it is not the field the alternate index is keyed on (see below).
- **Disambiguation — the transaction alternate index is keyed on `proc_ts`, not `orig_ts`
  (Explainability):** the transaction record (`CVTRA05Y`) defines **two** 26-character timestamps —
  `TRAN-ORIG-TS` (origination) at offset **278** and `TRAN-PROC-TS` (processing) at offset **304**. The
  VSAM catalog listing (`legacy/catlg/LISTCAT.txt`) shows `TRANSACT.VSAM.AIX` with `KEYLEN=26` at
  `AXRKP=304`, i.e. the alternate key begins at offset 304 — **`TRAN-PROC-TS`**. The chronological
  browse therefore orders by the **processing** timestamp, which maps to the `proc_ts` column; ordering
  by `orig_ts` would not reproduce the legacy browse order. This choice is applied consistently across
  the [architecture overview](./architecture.md), the [traceability matrix](./traceability-matrix.md),
  and the repository query definitions — the browse method
  `findByCardNumOrderByProcTsAscTranIdAsc` orders by `proc_ts` (with a `tran_id` tie-breaker; see
  *Risk & mitigation*). Note that the online transaction lister (`legacy/cbl/COTRN00C.cbl`) itself
  browses on the **primary key** (`STARTBR ... RIDFLD(TRAN-ID)`) and merely *displays* `TRAN-ORIG-TS`;
  the alternate-index chronological retrieval formalized here is the `proc_ts` browse defined by
  `legacy/jcl/TRANIDX.jcl` (`KEYS(26 304)`).
- **Rationale:** Forward browse maps naturally to `Pageable` queries ordered by the unique key; the
  alternate-index access paths map to database indexes that preserve the same retrieval order; and the
  reverse-browse-from-`HIGH-VALUES` idiom is exactly a "largest existing key, then increment" operation.
  This preserves both the retrieval order and the identifier values callers observe.
- **Risk & mitigation:** A mismatch in key ordering or a pagination-boundary off-by-one would change which
  rows a screen returns, and concurrent inserts could race the max-key-plus-one generator. *Mitigation:*
  repository queries preserve the exact unique-key ordering; the chronological browse appends the unique
  `tran_id` as a secondary sort key (`...OrderByProcTsAscTranIdAsc`) so rows sharing a `proc_ts` never
  shift across page boundaries — a deterministic-pagination robustness improvement over the non-unique
  legacy browse key; pagination boundaries are covered by tests; and identifier generation is guarded
  within a transactional boundary (see [D18](#d18--jpa-optimistic-locking-version)). The
  max-key-plus-one generator can still **race** under genuine concurrency; rather than assuming it is
  collision-proof, the `Transaction` entity implements Spring Data `Persistable` so a freshly built
  transaction is always **inserted** (never merged), and a duplicate `tran_id` is therefore **detected and
  rejected** by the `transaction` primary-key constraint — surfaced as a `DuplicateKeyException`
  (HTTP&nbsp;409) — instead of silently overwriting an existing row. This preserves the fail-loud outcome
  of the COBOL `WRITE … INVALID KEY` / duplicate-key path (`legacy/cbl/COTRN02C.cbl`) and is verified by an
  integration test that pre-seeds a colliding id.

### D11 — Flyway for schema and reference-data migrations

- **Status:** Accepted
- **Type:** Tooling selection (behavior-preserving)
- **AAP references:** §0.4.3, §0.5.5
- **Decision:** Create and evolve the database with **Flyway** versioned migrations —
  `V1__schema.sql` (eleven tables — ten core plus one staging — five indexes, foreign-key constraints)
  and the planned `V2__reference_data.sql` (reference tables; currently seeded via `db/seed/*.csv`)
  — seeded from the **fixed-width, headerless ASCII data** under
  `legacy/data/ASCII/**`, parsed by **fixed column positions** per the governing copybook.
- **Alternatives:** Hibernate `ddl-auto` schema generation from entities; Liquibase; or hand-run SQL
  scripts applied outside the application lifecycle.
- **Rationale:** Versioned, checksum-validated migrations give a deterministic, auditable schema history
  that runs identically in every environment, which is essential for reproducing the exact table shapes
  the copybooks describe. Entity-driven `ddl-auto` is unsuitable for a system where the schema is a
  preserved external contract rather than a by-product of the code.
- **Seed-format note — fixed-width, not delimited (Explainability):** the files under
  `legacy/data/ASCII/**` are **fixed-width and headerless**; they are **not** CSV/delimited and contain
  no header row. Each record's byte width equals the record length of its governing copybook, so the
  loader parses by **fixed column offsets** and preserves the copybook padding conventions
  (space-padded alphanumeric, zero-padded numeric). The per-file widths and row counts are:
  `custdata` 500 (50), `acctdata` 300 (50), `carddata` 150 (50), `cardxref` 36-visible/50-with-filler
  (50), `dailytran` 350 (300), `discgrp` 50 (51), `tcatbal` 50 (50), `trancatg` 60 (18), `trantype`
  60 (7). There is **no `usrsec.txt`** in the ASCII set — the `user_security` table seeds from the
  EBCDIC `USRSEC` dataset (10 rows) instead. Exact widths, counts, and content hashes for every artifact
  are catalogued in the [traceability matrix](./traceability-matrix.md); treating these files as
  delimited would silently misalign every field.
- **Risk & mitigation:** An edited-after-the-fact migration would fail Flyway's checksum validation, and
  seed order must respect the new foreign keys from [D8](#d8--real-foreign-key-constraints).
  *Mitigation:* migrations are treated as immutable once merged (fixes go in new versions); seed inserts
  are ordered parents-before-children; the fixed-width parse is asserted against copybook offsets; and
  the reference-data load counts are asserted by integration tests.


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
  turn each program's **PF-key (AID) semantics** into **explicit action fields / enums**. No terminal
  emulator and no pixel-level 3270 rendering are produced.
- **Alternatives:** Build a 3270 terminal emulator or a faithful screen renderer; or design a brand-new
  web UI for the screens.
- **Rationale:** No design system was supplied and the migration forbids feature expansion, so the
  observable contract to preserve is the **field-level data contract and key semantics**, not a rendered
  screen. DTOs capture that contract precisely and keep it reviewable field-by-field. Rendering a new UI
  or an emulator would be feature expansion.
- **AID coverage note — the action set is per-program, not a fixed three (Explainability):** PF3 = back,
  PF7/PF8 = page, and Enter = submit are the *common* actions, but they are **not** the full set. The
  action-key handling is **program-specific** and includes **PF4, PF5, and PF12** where the source uses
  them — e.g. `COUSR02C`/`COUSR03C` handle `{ENTER, PF3, PF4, PF5, PF12}`, `COTRN01C`/`COTRN02C` handle
  `{ENTER, PF3, PF4, PF5}`, `COBIL00C`/`COUSR01C` handle `{ENTER, PF3, PF4}`, and the list/paged screens
  (`COTRN00C`, `COUSR00C`) add `{PF7, PF8}`. A further five programs (`COACTUPC`, `COACTVWC`,
  `COCRDLIC`, `COCRDSLC`, `COCRDUPC`) contain **no direct `EIBAID` branch** and drive navigation via
  `ENTER` + shared logic; these are classified as "ENTER + navigation per source; no direct EIBAID
  branch". The complete per-program AID set is enumerated one row at a time in the
  [traceability matrix](./traceability-matrix.md) so no action path is silently dropped.
- **Risk & mitigation:** Dropping a field-level edit rule or an AID path would be a behavior regression.
  *Mitigation:* every field and every AID path appears as a one-to-one row in the traceability matrix,
  and Bean Validation rules on the DTOs reproduce the COBOL edit paragraphs.

### D14 — Chunk-oriented Spring Batch; scheduling moves to CI/CD

- **Status:** Accepted
- **Type:** Behavior preservation
- **AAP references:** §0.4.4, §0.7.2 (M4)
- **Decision:** Re-express each JCL-triggered batch program as a **chunk-oriented Spring Batch**
  `Job` composed of `ItemReader`/`ItemProcessor`/`ItemWriter` `Step`s. Job-to-job DD dependencies become
  **step/flow ordering**; **inline `SORT`/`MERGE`** utility steps become **Java `Comparator`s or
  `ORDER BY` queries**; **IDCAMS `REPRO`** copy/unload/backup steps become **file/table copy or backup
  steps**; GDG-based backups become a **scheduled database-backup step**; and **job scheduling moves to
  the CI/CD workflow** (`.github/workflows/ci.yml`) rather than an in-application scheduler.
- **Alternatives:** An in-application scheduler (for example `@Scheduled` or Quartz); a single monolithic
  batch runner; or a standalone external workflow orchestrator.
- **Rationale:** Chunk-oriented steps reproduce the sequential, restartable nature of the COBOL batch
  programs while giving explicit control over commit intervals and return codes. Moving the *trigger* to
  CI/CD matches the JCL scheduler's role without embedding scheduling concerns in the application.
- **JCL-semantics note — utilities, gating, and non-1:1 mappings (Explainability):** the JCL corpus is
  **not** a uniform set of one-program-per-job triggers, and the migration captures the following at
  **step level** in the [traceability matrix](./traceability-matrix.md) rather than collapsing them:
  - **Utility vs. program steps.** `REPROCT.ctl` is an **IDCAMS `REPRO`** control member
    (`REPRO INFILE(...) OUTFILE(...)`, i.e. copy/unload/backup) — **not** a SORT control member; inline
    `PGM=SORT` card sequences (e.g. within `COMBTRAN`) are traced **separately** as sort steps.
    Allocation/utility jobs (`IDCAMS DEFINE`, `IEBGENER`, `IEFBR14`) are classified as
    schema/allocation equivalents, not business logic.
  - **Return-code gating.** `COND=`/`IF MAXCC`/`MAXCC` conditions between steps map to Spring Batch
    **flow transitions on exit status** (return codes 0/4/8); these are recorded per step, not inferred.
  - **GDG snapshots.** Generation-data-group backup rotation (e.g. `CLOSEFIL → TRANBKP → OPENFIL`) maps
    to a scheduled backup step; the snapshot semantics are documented, not silently dropped.
  - **`TRANBKP` REPRO export vs. VSAM storage management (`TransactionBackupJob`).** `TRANBKP.jcl`
    (via `REPROC.prc` + `REPROCT.ctl` = `REPRO INFILE(FILEIN) OUTFILE(FILEOUT)`) runs three steps:
    `STEP05R` copies `TRANSACT.VSAM.KSDS` → `TRANSACT.BKUP(+1)` (a new GDG generation,
    `LRECL=350 RECFM=FB`), then `STEP05` (`IDCAMS DELETE ... CLUSTER` / `DELETE ... ALTERNATEINDEX`)
    and `STEP10` (`IDCAMS DEFINE CLUSTER ... KEYS(16 0) RECORDSIZE(350 350)`) drop and recreate the
    empty cluster. **Only the `STEP05R` REPRO export is reproduced** — as a **timestamped 350-byte
    fixed-width backup file** (the timestamp is the relational analog of the GDG `(+1)` generation),
    written via `common/util/FixedWidthCodec` so the external file contract is byte/semantically
    preserved. **The `DELETE`/`DEFINE CLUSTER` storage-management steps are intentionally NOT
    replicated:** those steps exist only to reclaim and re-initialize VSAM storage, and a relational
    backup export never drops and recreates the `transaction` table (that would be destructive and has
    no relational equivalent). This omission is documented here rather than silently dropped.
  - **Non-executable / anomalous members.** Some members are historical or contain source-level
    anomalies (e.g. `CBADMCDJ.jcl` is a stale alternate-CSD loader classified **reference-only**;
    `OPENFIL.jcl` carries a misspelled `//OEPNFIL` job card; `DEFCUST.jcl` and `TRANREPT.jcl` contain
    duplicate step names). These are **classified and left byte-unchanged** in `legacy/**`; the matrix
    records the authoritative behavior chosen for each without editing the source.
- **Current-state realization of the CI/CD trigger (Explainability).** The nightly `schedule:` cron
  (and an on-demand `workflow_dispatch`) in `.github/workflows/ci.yml` now **realizes** the JCL→CI/CD
  batch scheduling through a dedicated **`scheduled-batch`** job, so the recurring mainframe batch
  lifecycle actually runs in CI rather than being deferred: (1) the `build` job is unchanged — it runs
  exactly `./mvnw -B clean verify` on `push`/`pull_request`/`schedule`, with the quality gates (JaCoCo,
  OWASP dependency-check, zero-warning compile) inherited from `pom.xml`; (2) the new `scheduled-batch`
  job runs only on `schedule` or `workflow_dispatch` (never on `push`/`pull_request`), provisions a
  **PostgreSQL 16** service container (Flyway migrates it; the `local` profile's seed loader populates
  the demo rows the master-print jobs read), builds the application with `./mvnw -B -DskipTests clean
  package`, and then **launches each implemented Spring Batch job by name** via
  `--spring.batch.job.enabled=true --spring.batch.job.name=<job>` with the web server disabled so the
  **process exit code equals the Spring Batch return code (`0` == `COMPLETED`)**, failing the workflow if
  any job returns non-zero; (3) the jobs launched at this checkpoint are `accountMasterPrintJob`
  (CBACT01C), `cardMasterPrintJob` (CBACT02C), `xrefPrintJob` (CBACT03C), `customerMasterPrintJob`
  (CBCUS01C) and `transactionBackupJob` (TRANBKP / IDCAMS `REPRO`); the remaining mapped legacy jobs
  (POSTTRAN/CBTRN02C, INTCALC/CBACT04C, CREASTMT/CBSTM03A, COMBTRAN/SORT) are implemented in **later
  checkpoints** and are appended to the same launch list as each target program lands. Because the jobs
  use **no `JobParametersIncrementer`**, each launch passes a **unique identifying `correlationId`**
  parameter so nightly re-runs never hit `JobInstanceAlreadyCompleteException` (the value also propagates
  as the batch correlation id). This launch-by-name mechanism is exercised locally (all five jobs return
  `COMPLETED` / exit 0) and is what the CI job invokes. **Alternative / roadmap:** if per-run isolation
  or richer orchestration is later required, a dedicated external scheduler (e.g. Argo/Airflow/Control-M)
  can host the launches without altering the reproducible `build` gate.
- **Risk & mitigation:** Reordering steps, or a different chunk-commit boundary, could change outputs or
  restart behavior. *Mitigation:* step and flow ordering mirrors the JCL DD dependencies; comparators
  preserve the exact sort keys; return-code gating is modeled explicitly; and golden-file tests compare
  batch outputs row-for-row (see
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
  contracts — the daily-transaction input (`DALYTRAN`, 350-byte records), the reject output
  (`DALYREJS`, a **430-byte record**), and the statement/report outputs.
- **`DALYREJS` layout — 430 bytes, not 350 (contract detail):** the reject record written by the posting
  program (`legacy/cbl/CBTRN02C.cbl`) is **430 bytes = a 350-byte transaction image + an 80-byte
  validation trailer** (`FD-REJECT-RECORD` is `PIC X(350)` holding the `CVTRA05Y` transaction record,
  followed by `FD-VALIDATION-TRAILER PIC X(80)` carrying the reject reason). The `POSTTRAN.jcl` job's
  `DALYREJS` DD confirms `RECFM=F,LRECL=430`. Sizing the reject record at 350 bytes (the transaction
  image alone) would drop the 80-byte trailer and break the external file contract, so the codec and its
  golden-file fixtures use **430**.
- **Reject-write semantics (preserved):** rejected records are written to `DALYREJS` **in read order**,
  with a **running reject count** maintained, and the posting job returns **`RC=4`** when any reject
  occurs (a clean run returns `RC=0`). The four validation reject reasons and their fixed evaluation
  order — **100** cross-reference not found → **101** account not found → **102** over credit limit →
  **103** transaction after account expiration — are preserved exactly; see the reject-code handling in
  the [traceability matrix](./traceability-matrix.md).
- **Alternatives:** Emit CSV/JSON for the external file exchanges; or reproduce the legacy on-disk EBCDIC
  encoding with binary `COMP-3` fields.
- **Rationale:** The real external contract is the **fixed-width record layout**, so that layout is what
  must be preserved byte/semantically. Because the data now lives in PostgreSQL as native types, the
  target preserves the **documented external layout on exchange** rather than the legacy on-disk
  EBCDIC/`COMP-3` encoding — the observable file contract is identical while the storage representation
  modernizes.
- **Risk & mitigation:** A single-column offset or length error (or a wrong overall record length such
  as 350 vs 430) would corrupt every downstream consumer of these files. *Mitigation:* column positions
  and total lengths are declared once in the codec and asserted by golden-file tests that compare
  produced records against fixtures derived from the legacy layouts, including the full 430-byte reject
  record and the `RC=4`-on-reject behavior.

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
- **Decision:** Add a JPA **`@Version` column** to every entity updated online — the **account**,
  **customer**, **card**, **transaction-category-balance**, and **user-security** records — so the COBOL
  **READ-UPDATE-REWRITE** cycle's integrity is reproduced with **optimistic locking** inside a
  `@Transactional` boundary. The online **account update** (`legacy/cbl/COACTUPC.cbl`) edits the account
  and its owning customer as a **single aggregate**: its `9700-CHECK-CHANGE-IN-REC` paragraph re-reads and
  field-compares **both** records and aborts with *"Record changed by some one else. Please review"* if
  **either** changed since the user fetched the details. To reproduce that aggregate guard faithfully, the
  confirmed-write path loads the account with **`OPTIMISTIC_FORCE_INCREMENT`**
  (`AccountRepository.findByIdForVersionedUpdate`), so **any** confirmed write — including a
  **customer-only** edit that leaves every account column unchanged — advances `account.version`. A second
  editor holding the now-stale account version is then rejected, covering the whole account-plus-customer
  aggregate rather than the account alone.
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
  mistaken for a regression; the `@Version` check covers **every record that participates in the
  read-modify-write cycle** — including the **customer**, which the online account update edits as part of
  the account aggregate and which is guarded through the account's force-increment described above; and
  identifier generation from
  [D10](#d10--alternate-indexes-to-b-tree-indexes-vsam-browse-to-sortedpaged-queries) is guarded within the
  same transactional boundary, where a colliding insert **fails loud** rather than overwriting.


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

### D26 — Consistent credential case-normalization across authentication surfaces

- **Status:** Accepted
- **Type:** Behavior preservation (parity fix)
- **AAP references:** §0.2.2 (CICS → Spring Security), §0.7.3 (L1), §0.8.1 (behavioral parity), §0.9.2 (field-contract parity)
- **Decision:** Normalize credentials to **UPPER CASE consistently across every authentication surface**.
  The password is folded to upper case (`Locale.ROOT`) inside a **single shared `PasswordEncoder`** —
  `security/UpperCasePasswordEncoder` wrapping `BCryptPasswordEncoder`, declared as the one
  `config/SecurityConfig#passwordEncoder()` bean — and the user id is **upper-cased (not trimmed)** in both
  the CC00 sign-on service (`service/SignonService`) and the `security/CardDemoUserDetailsService` consumed
  by the HTTP&nbsp;Basic gate. This reproduces the legacy sign-on program, which folds **both** the entered
  user id and password with `FUNCTION UPPER-CASE` and performs no trim
  (`MOVE FUNCTION UPPER-CASE(USERIDI …) TO WS-USER-ID` / `MOVE FUNCTION UPPER-CASE(PASSWDI …) TO WS-USER-PWD`,
  `legacy/cbl/COSGN00C.cbl`).
- **Alternatives:**
  - **(a) Strip the upper-casing from the sign-on service** so the screen compares the raw, exact-case
    password. *Rejected* — COSGN00C is **case-insensitive on the password**, so removing the fold would
    **regress** the legacy behavior and reject credentials the mainframe accepted.
  - **(b) Fold the password only in the sign-on service** and leave the HTTP&nbsp;Basic gate case-sensitive.
    *Rejected* — this is precisely the divergence that locked operators out of every protected endpoint (a
    lower-/mixed-case password accepted at sign-on returned `401` on the API), because Spring Security's
    `DaoAuthenticationProvider` compares the raw HTTP&nbsp;Basic password against the stored hash.
  - **(c) Trim the id in the gate but not in sign-on.** *Rejected* — it creates an id-normalization
    divergence where the gate would accept a space-padded id the sign-on screen rejects.
- **Rationale:** Placing the case fold **inside the shared encoder** guarantees that the framework-built
  `DaoAuthenticationProvider` applies the identical rule as the interactive sign-on path and user
  administration (`service/UserService`), so all three surfaces accept and reject exactly the same
  credential set. The fold is **idempotent** (`UPPER(UPPER(x)) == UPPER(x)`), so `SignonService` and
  `UserService` retain their explicit folds as self-documenting parity assertions with no double-effect, and
  the BCrypt seed hashes in `db/seed/user_security.csv` (derived from the upper-cased demo password) remain
  valid. `Locale.ROOT` makes the fold locale-independent (avoiding surprises such as the Turkish dotless-i).
- **Risk & mitigation:** Folding to upper case makes the password **case-insensitive**, a narrower secret
  space than a case-sensitive password. *Mitigation:* this is a **faithful reproduction of the documented
  legacy contract**, not a new design choice; it is recorded here explicitly (not a silent change);
  passwords remain **BCrypt-hashed at rest** (see [D22](#d22--password-hashing-and-cvv-hardening)), are
  **never logged**, and are externalized with **no hardcoded secrets** (see
  [D6](#d6--externalized-credentials-no-hardcoded-secrets)). The normalization is covered by unit tests
  (`UpperCasePasswordEncoderTest`, `SignonServiceTest`, `CardDemoUserDetailsServiceTest`) and re-verified at
  runtime — a lower-case password now authenticates identically on the sign-on screen and the HTTP&nbsp;Basic
  gate.

### D27 — CSRF protection disabled (stateless HTTP Basic API)

- **Status:** Accepted
- **Type:** Security posture
- **AAP references:** §0.2.1–§0.2.2 (CICS online → stateless Spring MVC), §0.7.1 (H1, pseudo-conversational → stateless HTTP), §0.4.1 (`config/SecurityConfig`)
- **Decision:** Spring Security's CSRF protection is **disabled** in the single security filter chain
  (`http.csrf(AbstractHttpConfigurer::disable)` in `config/SecurityConfig`). The chain is **stateless**
  (`SessionCreationPolicy.STATELESS`) and authenticates every request with **HTTP&nbsp;Basic** credentials.
- **Alternatives:** Keep Spring Security's **default CSRF token protection enabled** (synchronizer-token or
  cookie-token pattern), issuing and validating a per-session CSRF token on state-changing requests.
- **Rationale:** CSRF is an attack against **ambient, automatically-attached** browser credentials — a
  session cookie the browser sends on a forged cross-site request without the caller's intent. This API has
  **no such ambient credential**: there is **no server-side session and no auth cookie**
  (`SessionCreationPolicy.STATELESS`), and HTTP&nbsp;Basic credentials must be **explicitly supplied on every
  request** (they are never stored by the browser and replayed implicitly the way a cookie is). With no
  cookie/session to forge, the CSRF token adds no protection while it *would* break the stateless,
  token-per-request contract for non-browser and server-to-server clients. Disabling CSRF is therefore the
  correct posture for a stateless HTTP&nbsp;Basic API, consistent with the CICS-online → stateless-REST
  translation (see [D12](#d12--cics-pseudo-conversational-to-stateless-rest--flow-context)).
- **Risk & mitigation:** The choice is only safe **while** the API stays stateless and cookie-free. *Mitigation:*
  the statelessness is enforced in the same chain (`SessionCreationPolicy.STATELESS`) and asserted by the
  security regression tests; this entry records the coupling explicitly, so if a future deliverable introduces
  a browser client backed by a **cookie/session** login, CSRF protection must be **re-enabled** for the
  cookie-authenticated paths and that reversal decision-logged.

### D28 — No CORS configuration (same-origin-only)

- **Status:** Accepted
- **Type:** Security posture (scope boundary)
- **AAP references:** §0.3.3 (physical 3270 emulation / BMS screen rendering **out of scope**), §0.7.1 (H2, BMS field-level contract re-expressed as REST DTOs, **not a rendered web UI**)
- **Decision:** **No CORS policy is configured.** There is no `http.cors(...)` in `config/SecurityConfig`, no
  `CorsConfigurationSource` bean, no `WebMvcConfigurer#addCorsMappings`, and no `@CrossOrigin` annotation
  anywhere in the application. Consequently the API grants **no cross-origin allowance** and is effectively
  **same-origin-only** (the browser's same-origin policy blocks cross-origin script access by default).
- **Alternatives:** Register a CORS policy — either a **permissive** one (e.g. `allowedOrigins("*")`) or an
  **explicit allow-list** of trusted origins — to permit a separate-origin browser client to call the API.
- **Rationale:** The migration re-expresses the 17 legacy BMS/3270 screens as **REST request/response
  contracts**, and physical 3270 emulation / browser UI rendering is **explicitly out of scope** (§0.3.3). No
  cross-origin browser client is part of the delivered system, so there is **no origin to allow**. Adding a
  CORS policy with no consumer would only **widen the attack surface** (a permissive policy) or encode
  **speculative, unverifiable** origins (an allow-list) — neither of which serves a shipped requirement. The
  secure, least-privilege default is therefore to **configure nothing** and remain same-origin-only.
- **Risk & mitigation:** If a same-origin assumption is later violated — for example a separately-hosted SPA or
  a partner front-end is introduced — cross-origin calls will be **blocked by the browser** until CORS is
  configured, which is a safe-by-default failure mode (access is denied, not silently widened). *Mitigation:*
  when such a client enters scope, add an **explicit least-privilege `CorsConfigurationSource`** (named
  origins, minimal methods/headers, credentials only if required) and record that decision here; do **not**
  reach for `allowedOrigins("*")`.


---

## G. Observability & Operations

### D23 — Observability stack (logs, traces, metrics, dashboard)

- **Status:** Accepted
- **Type:** Non-functional addition (behavior-preserving)
- **AAP references:** §0.8.2, §0.8.4, §0.9.5
- **Decision:** Ship a framework-native observability stack: **Logback** structured logging carrying a
  **correlation ID** that propagates across service and batch boundaries; **Micrometer + OpenTelemetry**
  distributed tracing exported over **OTLP** (intended to be viewed locally in Tempo); a **Prometheus**
  metrics endpoint and **health/readiness** checks via **Spring Boot Actuator**; and a **Grafana
  dashboard** template (`docs/observability/grafana-dashboard.json`). The stack **runs** in a local
  Docker Compose environment (PostgreSQL + Prometheus + Tempo + Grafana).
- **Delivery status at this checkpoint (Explainability):** the observability configuration
  (`logback-spring.xml`, Actuator/Micrometer/OTLP settings in `application.yml`) and the **Grafana
  dashboard template** are present, and the application **runs against the local Docker Compose stack
  today**: the Actuator health/readiness and Prometheus metrics endpoints respond locally, and Logback
  emits a correlation id on every request and batch execution. What remains a tracked next task — owned
  by the observability workstream — is **exhaustive validation of the full signal pipeline** (end-to-end
  correlation-id propagation, traces landing in Tempo, and Grafana dashboard rendering against live data),
  recorded in [docs/onboarding/extending.md](./onboarding/extending.md).
- **Per-job batch observability contract (planned):** each Spring Batch job (see
  [D14](#d14--chunk-oriented-spring-batch-scheduling-moves-to-cicd) and the
  [traceability matrix](./traceability-matrix.md)) emits a consistent set of signals — job/step
  `BatchStatus` + `ExitStatus`, `spring_batch_job_seconds` timers, `spring_batch_item_read/write/skip`
  counters, and a correlation id on every log line — and maps its outcome to a batch **return code**
  (0 = clean, 4 = completed-with-rejects/warnings, 8 = failed):

| # | Spring Batch Job | Source program(s) | Key metrics / status | Failure & return-code contract |
|---|------------------|-------------------|----------------------|--------------------------------|
| 1 | `DailyTransactionValidateJob` | CBTRN01C | read/validate counts; job timer | validation errors → RC=4; hard I/O failure → RC=8 |
| 2 | `DailyTransactionPostingJob` | CBTRN02C | read/write/reject counters; running reject count | any reject → **RC=4**; unrecoverable error → RC=8 |
| 3 | `InterestCalculationJob` | CBACT04C | accounts processed; interest total | arithmetic/lookup failure → RC=8 |
| 4 | `StatementGenerationJob` | CBSTM03A + CBSTM03B | statements written; file-service call count | file-service failure → RC=8 |
| 5 | `TransactionReportJob` | CBTRN03C | report rows; page count | source-read failure → RC=8 |
| 6 | `AccountMasterPrintJob` | CBACT01C | records read/printed | read failure → RC=8 |
| 7 | `CardMasterPrintJob` | CBACT02C | records read/printed | read failure → RC=8 |
| 8 | `XrefPrintJob` | CBACT03C | records read/printed | read failure → RC=8 |
| 9 | `CustomerMasterPrintJob` | CBCUS01C | records read/printed | read failure → RC=8 |
| 10 | `TransactionCombineJob` | COMBTRAN (inline SORT) | items sorted/merged; sort timer | sort/merge failure → RC=8 |
| 11 | `TransactionBackupJob` | TRANBKP (IDCAMS REPRO) | rows copied; backup timer | backup/copy failure → RC=8 |

- **Alternatives:** Ship no observability (closest to the legacy system, which had none); rely on plain
  unstructured logging only; or defer observability to a later, separate initiative.
- **Rationale:** CardDemo has no prior observability stack, so this is delivered as **new,
  framework-native tooling**. The apparent tension with the "no feature expansion" constraint is
  resolved because **observability is a non-functional operational concern that wraps the preserved logic
  without altering it** — it changes what operators can *see*, not what the application *does*.
- **Risk & mitigation:** Tracing/metrics export can add overhead or, if misconfigured, leak sensitive
  data into logs. *Mitigation:* the tracing exporter endpoint is supplied via environment variable and is
  optional for local runs; the CVV is never logged and passwords are never logged (see
  [D22](#d22--password-hashing-and-cvv-hardening)); and **exhaustive validation** of the full signal
  pipeline against the bundled Docker Compose observability services (end-to-end trace propagation into
  Tempo and Grafana dashboard rendering) is a tracked validation step owned by the observability
  workstream.

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

### D25 — Full PAN and account number in master-print output

- **Status:** Accepted
- **Type:** Behavior preservation (parity) with a **documented security improvement (deferred)**
- **AAP references:** §0.7.3 (L1), §0.8.1, §0.8.3, §0.9.3
- **Decision:** The batch master-print jobs — `AccountMasterPrintJob` (from `legacy/cbl/CBACT01C.cbl`),
  `CardMasterPrintJob` (from `legacy/cbl/CBACT02C.cbl`), and `CustomerMasterPrintJob` (from
  `legacy/cbl/CBCUS01C.cbl`) — reproduce the legacy SYSOUT record dumps. The **full account number**
  (`ACCT-ID`) and the **full card number / PAN** (`CARD-NUM`) are rendered **verbatim**, preserving the
  exact observable output of `DISPLAY ACCT-ID` (`legacy/cbl/CBACT01C.cbl` L119) and
  `DISPLAY CARD-RECORD` (`legacy/cbl/CBACT02C.cbl` L78). Every field the AAP designates as **sensitive**
  is masked: the card **CVV** is rendered as the fixed mask `***` and is never read
  (`CardMasterPrintJob.formatCardRecord`, see [D22](#d22--password-hashing-and-cvv-hardening)), and the
  customer **SSN**, **government-issued id**, and **date of birth** are rendered as `****`
  (`CustomerMasterPrintJob.formatCustomer`).
- **Alternatives:** (1) Truncate the PAN to its last four digits and/or mask the account number in the
  printed output. (2) Route the master-print output to a dedicated, access-controlled report sink outside
  the operational application log stream. Either option would move the printed report away from strict
  byte-for-byte parity with the legacy SYSOUT dump.
- **Rationale:** The AAP mandates **100% behavioral parity** for observable contracts (§0.8.1, §0.8.3),
  and the master-print SYSOUT dump is one such contract that golden-file, row-for-row parity tests assert
  against the legacy record layouts. The AAP's sensitive-field set is **exactly** CVV, SSN,
  government-issued id, and date of birth (§0.7.3 (L1), §0.9.3); the full PAN and full account number are
  **not** in that set. Masking or truncating the PAN or account number would therefore **deviate from
  parity** without being mandated by the AAP and would break the master-print golden-file comparison.
  Rendering them verbatim is the parity-correct choice, while masking the four AAP-designated fields is
  the sanctioned hardening that preserves the *observable behavior* of the report for every non-sensitive
  field.
- **Risk & mitigation:** Emitting a full PAN and full account number into an application log stream is a
  **PCI-DSS exposure** if those logs are retained or shipped without controls. *Mitigation:* the choice
  is documented **here** as a deliberate parity decision rather than an oversight; the CVV — the
  highest-sensitivity card field — is never logged, and SSN / government-issued id / date of birth are
  masked; and a **forward-looking PCI hardening** (truncate the PAN to last-4 and/or route master-print
  output to a dedicated report artifact outside the operational log stream) is recorded as the sanctioned
  future improvement, to be introduced through a new decision entry rather than silently. Operationally,
  master-print log output should be treated as cardholder data and access-controlled and
  retention-limited accordingly.

---

## H. Validation & Edit-Rule Parity

### D29 — US phone-number edit rule fixes a latent legacy bug (optional-when-all-blank)

- **Status:** Accepted
- **Type:** **Intentional improvement (deviation from literal COBOL — corrects a latent defect)**
- **AAP references:** §0.2.2 (edit paragraphs → rule components), §0.8.2, §0.8.3
- **Decision:** The US phone-number validation rule (`service/rule/UsPhoneRule`, the Java reproduction of
  `1260-EDIT-US-PHONE-NUM` in `legacy/cbl/COACTUPC.cbl`) treats the phone number as **optional only when
  all three sub-fields are blank** — area code (`WS-EDIT-US-PHONE-NUMA`), prefix
  (`WS-EDIT-US-PHONE-NUMB`), and line number (`WS-EDIT-US-PHONE-NUMC`). The legacy "all blank" test at
  `legacy/cbl/COACTUPC.cbl:L2237-L2239` contains a **latent bug**: its third `AND`-clause tests
  `WS-EDIT-US-PHONE-NUMA` a second time where it should test `WS-EDIT-US-PHONE-NUMC`, so the line-number
  sub-field is never inspected by the optionality check. The Java rule implements the **intent** (optional
  if and only if area, prefix, **and** line are all blank) rather than reproducing the defect.
- **Alternatives:** Reproduce the bug verbatim for line-for-line parity. *Rejected:* it yields a
  demonstrably wrong outcome — e.g. area and prefix blank while the line number is present would be
  accepted as "no phone entered" and skip validation — and the AAP frames such corrections as documented
  improvements (§0.8.3), not parity violations.
- **Rationale:** The deviation changes behavior only in the narrow, malformed case the legacy defect
  mishandles (area and prefix blank while the line number is non-blank); for every well-formed input the
  outcome is identical to the legacy program. Recording it here satisfies the Explainability rule
  (§0.8.2 — "Any deviation from a literal interpretation … has an explicit decision-log entry"; "Rationale
  lives in the decision log, not in code comments"), lifting the rationale out of the `UsPhoneRule` Javadoc
  and into this log.
- **Risk & mitigation:** A reviewer comparing the rule to the raw COBOL line-for-line might read the
  corrected `NUMC` check as an inserted discrepancy. *Mitigation:* the deviation is recorded here as an
  intentional, behavior-preserving correction of a latent legacy defect, cross-referenced from the
  `UsPhoneRule` class Javadoc; the corrected all-blank optionality is covered by the rule's unit tests.

---

## Related documents

- [Architecture overview](./architecture.md) — the layered target architecture these decisions realize.
- [Traceability matrix](./traceability-matrix.md) — the bidirectional, per-paragraph mapping from every
  COBOL construct to its Java target; each mapped field/paragraph corresponds to the design choices
  explained above.

