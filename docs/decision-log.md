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
| [D30](#d30--dalytran-raw-fixed-width-loader-executable-external-file-ingestion) | I. Batch Parity & Robustness | DALYTRAN raw fixed-width loader (executable external-file ingestion) | Contract preservation |
| [D31](#d31--interest-rounding-uses-half_up-documented-divergence-from-cobol-truncation) | I. Batch Parity & Robustness | Interest rounding uses HALF_UP (documented divergence from COBOL truncation) | **Documented divergence** |
| [D32](#d32--interest-last-account-finalization-corrects-a-latent-legacy-dead-else) | I. Batch Parity & Robustness | Interest last-account finalization (corrects a latent legacy dead-ELSE) | **Intentional improvement** |
| [D33](#d33--interest-job-requires-the-parmdate-parameter-fail-fast-validator) | I. Batch Parity & Robustness | Interest job requires the parmDate parameter (fail-fast validator) | Robustness guard |
| [D34](#d34--card-pan-masked-in-batch-operational-logs-pci-dss-first-6last-4) | I. Batch Parity & Robustness | Card PAN masked in batch operational logs (PCI-DSS first-6/last-4) | **Intentional improvement** |
| [D35](#d35--atomic-reject-file-publish-with-a-substituting-iso-8859-1-encoder) | I. Batch Parity & Robustness | Atomic reject-file publish with a substituting ISO-8859-1 encoder | **Intentional improvement** |
| [D36](#d36--fixed-width-records-are-lf-framed-and-embedded-delimiters-are-sanitized) | I. Batch Parity & Robustness | Fixed-width records are LF-framed and embedded delimiters are sanitized | Contract preservation |
| [D37](#d37--batch-fixed-width-writers-require-a-single-launch-per-jvm) | I. Batch Parity & Robustness | Batch fixed-width writers require a single launch per JVM; backup output filename made unique-per-run (F7) | Constraint documented + code fix |
| [D38](#d38--reason-code-109-is-excluded-from-rejectcode-and-maps-to-return-code-8) | I. Batch Parity & Robustness | Reason code 109 excluded from RejectCode; maps to return code 8 | **Documented deviation** |
| [D39](#d39--reject-101-account-not-found-is-an-unreachable-defensive-branch-under-the-cross-reference-foreign-key) | I. Batch Parity & Robustness | Reject 101 is an unreachable defensive branch under the cross-reference foreign key | Documented consequence |
| [D40](#d40--transaction-report-read-order-is-cardnum-tranid-a-documented-deviation-from-physical-tran-id-order) | I. Batch Parity & Robustness | Transaction report read order is (cardNum, tranId) | **Documented deviation** |
| [D41](#d41--statement-output-files-carry-no-in-band-delimiter-pure-recfmfb-image) | I. Batch Parity & Robustness | Statement output files carry no in-band delimiter (pure RECFM=FB image) | Contract preservation |
| [D42](#d42--atomic-statement-file-publish-with-owner-only-temporary-work-files) | I. Batch Parity & Robustness | Atomic statement-file publish with owner-only temporary work files | **Intentional improvement** |
| [D43](#d43--statement-html-is-a-byte-exact-batch-artifact-not-a-served-web-view-f29f32-disposition) | I. Batch Parity & Robustness | Statement HTML is a byte-exact batch artifact, not a served web view (F29/F32) | **Documented disposition** |
| [D44](#d44--statement-html-fields-are-html-escaped-per-field-parity-preserved-for-metacharacter-free-data) | I. Batch Parity & Robustness | Statement HTML fields HTML-escaped per field; XSS + charset closed, golden byte-parity preserved | **Security improvement** |
| [D45](#d45--batch-process-exit-code-equals-the-spring-batch-return-code-jcl-condition-code-parity) | I. Batch Parity & Robustness | Batch process exit code equals the Spring Batch return code (JCL condition-code parity) | Behavior preservation (parity) |
| [D46](#d46--combtran-reproduces-idcams-repro-without-replace-reject-duplicates-preserve-stale-rows-rc-4) | I. Batch Parity & Robustness | COMBTRAN reproduces IDCAMS `REPRO`-without-`REPLACE` (reject duplicates, preserve stale rows, RC 4) | Behavior preservation (parity) |
| [D47](#d47--combtran-requires-at-least-one-existing-sortin-member-missing-input-fail-fast) | I. Batch Parity & Robustness | COMBTRAN requires at least one existing SORTIN member (missing-input fail-fast) | Behavior preservation (parity) |
| [D48](#d48--master-print-execution-banners-are-reproduced-via-a-job-listener) | I. Batch Parity & Robustness | Master-print execution banners are reproduced via a job listener | Behavior preservation (parity) |
| [D49](#d49--sca-gate-remediation-forward-pin-fixable-cves-narrowly-suppress-the-two-no-fix-advisories) | A. Platform & Build | SCA gate remediation: forward-pin fixable CVEs, suppress two no-fix advisories | Security remediation |
| [D50](#d50--account-state-and-zip-combination-edit-is-enforced) | J. Online Parity Corrections | Account state+ZIP combination edit is enforced (F-CAUP-1) | Behavior preservation (parity fix) |
| [D51](#d51--account-update-preview-echoes-the-submitted-candidate-values) | J. Online Parity Corrections | Account update preview echoes the submitted candidate values (F-CAUP-2) | Behavior preservation (parity fix) |
| [D52](#d52--transaction-add-edits-run-before-the-confirm-decision) | J. Online Parity Corrections | Transaction-add edits run before the confirm decision (F-CT02-1) | Behavior preservation (parity fix) |
| [D53](#d53--menu-option-numeric-shape-check-runs-in-the-service-not-the-transport-boundary) | J. Online Parity Corrections | Menu option numeric-shape check runs in the service (F-MENU-1) | Behavior preservation (parity fix) |
| [D54](#d54--concurrent-update-maps-to-http-409-across-the-concurrency-exception-family) | J. Online Parity Corrections | Concurrent update maps to HTTP 409 across the concurrency-exception family (F-CAUP-3) | Robustness (extends D18/D15) |
| [D55](#d55--combine-job-global-sort-is-in-memory-and-buffered-a-documented-daily-volume-ceiling) | I. Batch Parity & Robustness | Combine-job global sort is in-memory and buffered | Constraint documented |
| [D56](#d56--statement-writer-uses-a-substituting-iso-8859-1-encoder-no-whole-job-abort-on-unmappable-input) | I. Batch Parity & Robustness | Statement writer uses a substituting ISO-8859-1 encoder; no whole-job abort on unmappable input | **Intentional improvement** |

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
  open-source support for the 3.5 line ended 2026-06-30**. Consequently this pin currently receives
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
  Monetary arithmetic is centralized in the value object. The COBOL interest computation
  `COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` in `1300-COMPUTE-INTEREST`
  (`legacy/cbl/CBACT04C.cbl`) becomes
  `tranCatBal.multiply(intRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)`. Note that
  the legacy `COMPUTE` has no `ROUNDED` phrase and truncates, so applying the project-wide `HALF_UP`
  standard here is a **documented divergence** in the exact-half boundary case — see
  [D31](#d31--interest-rounding-uses-half_up-documented-divergence-from-cobol-truncation); it is not a
  bit-for-bit reproduction of the truncating COBOL statement.
- **Alternatives:** `double`/`float` primitives (rejected — binary floating point cannot represent decimal
  currency exactly); a scaled `long` "minor units" representation (workable but obscures the direct
  correspondence to the COBOL `PIC S9(n)V99` layouts and the `COMPUTE` expressions).
- **Rationale:** COBOL monetary fields are `PIC S9(n)V99 COMP-3` packed decimals. `BigDecimal` at fixed
  scale with an explicit rounding mode reproduces their **additive** arithmetic (the balance postings,
  which are exact at scale 2) without drift, and standardizes the one rounding site — the interest
  division — on `HALF_UP` per AAP §0.4.2. Where the legacy code truncated (the interest `COMPUTE`), that
  standardization is an intentional, documented divergence (D31) rather than a bit-for-bit reproduction.
  `double`/`float` are prohibited for decimal values throughout the codebase.
- **Risk & mitigation:** Rounding-mode or intermediate-scale drift compounds across financial postings
  and would break parity to the cent. *Mitigation:* all arithmetic flows through the single `Money` value
  object so the scale and rounding mode are defined in one place, and golden-file parity tests assert the
  Java output equals the expected fixtures **to the cent** (see
  [D21](#d21--testing-strategy-testcontainers-jacoco-80-golden-file-parity)). The **sole** intentional
  exception is the interest exact-half boundary, where the `HALF_UP` standard deliberately diverges from
  the truncating COBOL `COMPUTE` (D31); the interest fixtures encode the `HALF_UP` result.

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
- **Start-key repositioning is honored, not dropped (transaction list, Explainability):** the online
  transaction lister (`CT00` / `legacy/cbl/COTRN00C.cbl`) repositions its browse at the operator-supplied
  transaction id — `STARTBR ... RIDFLD(TRAN-ID)` (L206-210) — rather than always restarting at the first
  row. The Java target preserves this exactly: `service/TransactionService` threads the submitted
  `transactionId` start key into the key-ordered paged query
  `TransactionRepository.findByTranIdGreaterThanEqual(...)`, so a supplied id anchors the page as the
  legacy `STARTBR` did, while a blank/`LOW-VALUES` key falls back to the first page (the numeric-guard
  edit `"Tran ID must be Numeric ..."` is preserved). This mirrors the User-List start-key anchor
  (`CU00` / `legacy/cbl/COUSR00C.cbl`) and makes the start key a **first-class query parameter**, not a
  display-only field — recorded here so the behavior is documented in this log and not only in a
  controller Javadoc.
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
- **Statement per-card covering index — a fourth, supporting index distinct from the three alternate
  indexes (Explainability / performance):** the statement-generation job retrieves *all transactions
  for one card* in card-then-transaction order. The legacy path did this with a **batch `SORT`**, not a
  VSAM alternate index: `legacy/jcl/CREASTMT.JCL` `STEP010` sorts the transaction extract with
  `SORT FIELDS=(263,16,CH,A,1,16,CH,A)` (ascending by `CARD-NUM` then `TRAN-ID`) before
  `legacy/cbl/CBSTM03A.CBL` reads it. The Java target re-expresses that pre-sorted per-card extract as
  the repository query `TransactionRepository.findByCardNumOrderByProcTsAscTranIdAsc`
  (`SELECT … FROM transaction WHERE card_num = ? ORDER BY proc_ts, tran_id`), consumed by
  `StatementFileService.readTransactionsForCard`. Because `transaction.card_num` is a foreign key to
  `card` and **PostgreSQL does not auto-create an index on a foreign-key column** (only on the
  referenced key), the initial `V1__schema.sql` left this column unindexed: the per-card query ran a
  full **sequential scan** of `transaction` plus an in-memory (disk-spilling on high-volume cards)
  **sort**, an `O(cards × table)` access pattern whose cost grows with the whole table rather than with
  one card's history. This is a **fourth** B-tree index that is *not* one of the three formalized
  alternate indexes above — the alternate-index browse (`TRANSACT.VSAM.AIX`) is the chronological
  `proc_ts` browse; the card-then-transaction ordering here comes from the `CREASTMT` `SORT`, so
  preserving it as an *indexed* sorted query (AAP §0.4.3 "browse patterns preserved as indexed sorted
  queries"; §0.2.2 SORT → sorted query with identical key ordering) requires its own supporting index.
- **Decision:** add a **covering index `idx_transaction_card_num` on `transaction (card_num, proc_ts,
  tran_id)`**, delivered as the immutable-migration-safe `V4__add_transaction_card_num_index.sql` (a new
  Flyway migration, never an edit to the already-applied `V1` — see
  [D11](#d11--flyway-for-schema-and-reference-data-migrations)). The leading `card_num` serves the
  equality predicate and the trailing `proc_ts, tran_id` match the query's `ORDER BY`, so the planner
  satisfies the filter *and* the ordering from the index alone — an **Index Scan with no separate Sort
  and no temporary-file spill**. The column order deliberately mirrors the
  `WHERE card_num = ? ORDER BY proc_ts, tran_id` shape of the repository method.
- **Alternatives:** a plain single-column index on `card_num` (rejected — it removes the sequential scan
  but leaves a separate `Sort`, and still spills to disk on high-transaction cards); relying on the
  planner to reuse `idx_transaction_proc_ts` (rejected — that index is not selective on `card_num`, so
  it is only chosen in the degenerate case where a single card is ~100 % of the table); or a covering
  index that also `INCLUDE`s the non-key statement columns (not adopted — the three key columns already
  eliminate both the scan and the sort; adding payload columns would enlarge the index for a negligible
  heap-fetch saving at the small per-card row counts).
- **Measured effect (EXPLAIN ANALYZE, BUFFERS on the 10× dataset, one card):** the shipped plan is a
  `Seq Scan on transaction` (≈2 994 rows removed by filter to return a handful) plus a `Sort`; with the
  covering index the same query becomes an `Index Scan` with no `Sort`, cutting buffer reads from the
  whole table to a few index+heap pages and eliminating the `external merge` temp-file spill observed on
  high-transaction cards. This is a purely additive, non-behavioral change: the result set and its
  ordering are byte-for-byte identical (the statement golden-file output is unchanged), so it is a
  performance fix, not a parity change.
- **Risk & mitigation:** an index adds a small write-amplification cost on `transaction` inserts (batch
  posting / combine load). *Mitigation:* the write cost of one additional B-tree is negligible against
  the read savings for the statement job, and the posting/interest chunk sizes are already `1` for
  COBOL commit parity (see [D14](#d14--chunk-oriented-spring-batch-scheduling-moves-to-cicd)); the index
  is verified to exist by an integration test (`TransactionRepositoryTest`) and its plan effect is
  re-verifiable with the `EXPLAIN` above.

### D11 — Flyway for schema and reference-data migrations

- **Status:** Accepted
- **Type:** Tooling selection (behavior-preserving)
- **AAP references:** §0.4.3, §0.5.5
- **Decision:** Create and evolve the database with **Flyway** versioned migrations —
  `V1__schema.sql` (eleven tables — ten core plus one staging — five indexes, foreign-key constraints)
  and the delivered `V2__reference_data.sql` (the reference tables `transaction_type`,
  `transaction_category`, `disclosure_group`) — with the reference-data values derived from the
  **fixed-width, headerless ASCII data** under
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
  **process exit code equals the Spring Batch return code** under the JCL condition-code mapping
  `COMPLETED` &rarr; `0`, `COMPLETED_WITH_REJECTS` &rarr; `4`, and any other outcome (`FAILED`/`STOPPED`)
  &rarr; `8` (realized by the `BatchExitCodeGenerator`; see
  [D41](#d41--batch-process-exit-code-equals-the-spring-batch-return-code-jcl-condition-code-parity)),
  failing the workflow if any job returns non-zero; (3) the jobs launched by the correlationId-only nightly loop are
  `accountMasterPrintJob` (CBACT01C), `cardMasterPrintJob` (CBACT02C), `xrefPrintJob` (CBACT03C),
  `customerMasterPrintJob` (CBCUS01C) and `transactionBackupJob` (TRANBKP / IDCAMS `REPRO`) — the
  read-only jobs that need no job parameters and read the seeded demo rows. The in-scope transaction
  pipelines are now **implemented at this checkpoint** — `dailyTransactionLoadJob` (the raw DALYTRAN
  loader, D30), `dailyTransactionValidateJob` (CBTRN01C), `dailyTransactionPostingJob`
  (POSTTRAN/CBTRN02C), `interestCalculationJob` (INTCALC/CBACT04C), `transactionReportJob`
  (TRANREPT/CBTRN03C), and `transactionCombineJob` (COMBTRAN/SORT) — but they are **intentionally not
  appended to the correlationId-only nightly loop**, because they require real job parameters
  (`interestCalculationJob` requires `parmDate` per D33; `transactionReportJob` requires
  `startDate`/`endDate`; `dailyTransactionLoadJob` requires `inputResource` per D30) or externally-staged
  DALYTRAN input (`dailyTransactionPostingJob` and `dailyTransactionValidateJob` operate on the
  `daily_transaction` staging table). A bare correlationId launch of those jobs would either fail fast at
  the parameter validator or process zero rows, so forcing them into the smoke loop would be misleading.
  Instead they are verified by the **Testcontainers integration-test suite** in the `build` job and are
  documented for manual, parameterized launch in
  [`docs/onboarding/getting-started.md`](./onboarding/getting-started.md) ("Run the batch jobs"). `StatementGenerationJob`
  (CREASTMT/CBSTM03A + CBSTM03B) is **likewise implemented** and is verified by the **Testcontainers
  integration-test suite** (`StatementGenerationJobTest`, asserting both the text and HTML outputs
  byte-for-byte against the golden fixtures under `src/test/resources/golden/statement/`); like the jobs
  above it is **intentionally kept out of the correlationId-only nightly loop**, because it emits one
  statement per cross-reference record and so needs an isolated seeded scenario — a bare launch against the
  full base seed would emit many statements and be misleading. **All mapped batch jobs are therefore
  implemented at this checkpoint.** Because the jobs use **no `JobParametersIncrementer`**, each launch passes a
  **unique identifying `correlationId`** parameter so nightly re-runs never hit
  `JobInstanceAlreadyCompleteException` (the value also propagates as the batch correlation id). This
  launch-by-name mechanism is exercised locally (the five nightly-loop jobs return `COMPLETED` / exit 0)
  and is what the CI job invokes. **Alternative / roadmap:** if per-run isolation
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
  is the decisive check that business outputs match the COBOL to the cent and byte (the sole documented
exception being the interest exact-half rounding, where the `HALF_UP` standard diverges from the
legacy truncation — D31).
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
- **Consequence — a blank password on update is rejected (Explainability):** because passwords are hashed
  at rest and the hash is **never returned** to the client, the update screen (`CU02` /
  `legacy/cbl/COUSR02C.cbl`) cannot pre-fill the password field. On save, a blank password is therefore
  rejected with the legacy edit `"Password can NOT be empty..."`, exactly reproducing the mandatory
  `SEC-USR-PWD` check in `COUSR02C UPDATE-USER-INFO` (~L200). The intended, documented consequence is that
  an administrator making a **role-only or name-only** change must **re-enter the password**: the
  no-return hardening does not weaken the legacy mandatory-field edit — it means the operator retypes the
  value the screen can no longer echo. The `UserUpdateRequest`/`UserUpdateController` Javadoc was corrected
  to state this (a blank password is **rejected**, not "left unchanged") so the documentation matches the
  legacy-faithful runtime rather than contradicting it.
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

## I. Batch Parity & Robustness

The decisions in this section were made while hardening the four in-scope daily-batch pipelines
(load, validate, posting, interest) and the transaction report against QA findings. Each records a
deliberate design choice — an added capability, a documented divergence from a literal COBOL
translation, or a robustness guard — so that no behavior differs from the AAP without an explicit,
reviewable rationale (Explainability rule, §0.8.2).

### D30 — DALYTRAN raw fixed-width loader (executable external-file ingestion)

- **Status:** Accepted
- **Type:** Contract preservation (executable realization of a preserved external contract)
- **AAP references:** §0.7.2 hotspot M2 (external fixed-width file contracts), §0.5.4 (batch layer),
  §0.6.4 (decimal handling: `BigDecimal`, never floating point)
- **Decision:** An executable Spring Batch loader (`batch/DailyTransactionLoadJob`, bean
  `dailyTransactionLoadJob`) ingests the raw external, fixed-width `DALYTRAN` sequential file
  (350-byte `DALYTRAN-RECORD`, copybook `legacy/cpy/CVTRA06Y.cpy`) directly into the
  `daily_transaction` staging table. It is composed of a `@StepScope`
  `batch/reader/DailyTransactionFileItemReader` (a `FlatFileItemReader<DailyTransaction>` reading
  over `ISO-8859-1` so the `DALYTRAN-AMT` zoned-decimal overpunch byte survives intact, and configured
  with a `batch/reader/FixedLengthBufferedReaderFactory` so it frames the raw stream into exact 350-byte
  records by **position** rather than by newline — faithful to `RECFM=F`, which carries no in-band record
  delimiter), a stateless
  `batch/reader/DailyTransactionLineMapper` that slices each record per the CVTRA06Y offset table with
  `common/util/FixedWidthCodec`, and a `batch/writer/DailyTransactionStagingWriter` that inserts the
  decoded rows. The amount field is decoded with `FixedWidthCodec.readSignedDecimal(...)` to a scale-2
  `BigDecimal` and is **never** parsed with `new BigDecimal(String)` and never a primitive
  `double`/`float`.
- **Alternatives:**
  1. *Rely on the `@Profile("local")` CSV seed loader (`config/LocalSeedDataLoader`) to populate the
     staging table.* **Rejected:** the CSV loader consumes a pre-decoded, comma-delimited convenience
     file that exists only for local development; it does not exercise the preserved 350-byte external
     record contract and is not active outside the `local` profile, so a real daily batch would have no
     operational path from the raw external file to the staging table.
  2. *Add the fixed-width parsing inline to the existing DB-backed `DailyTransactionItemReader`.*
     **Rejected:** that reader's role is the set-based, restartable sequential read of the already-loaded
     staging table (the analog of the COBOL `READ DALYTRAN-FILE`); conflating ingestion with the
     downstream read would blur two distinct responsibilities. The two readers are complementary — the
     file reader is the front door that loads the table; the repository reader feeds validate/posting.
- **Rationale:** The AAP preserves the external fixed-width file contracts as first-class parity
  artifacts (§0.7.2 M2). Providing an executable loader closes the ingestion gap end-to-end (raw file →
  staging table → validate/posting) using the same `FixedWidthCodec` overpunch decoding proven by the
  codec's own unit tests, so monetary fidelity is preserved to the cent for the ingested amounts. The
  input file location is a late-bound `inputResource` job parameter (no hard-coded path), and the job
  declares a `JobParametersValidator` that fails fast when it is missing.
- **Risk & mitigation:** An incorrect offset or the wrong charset would silently corrupt decoded values
  (especially the signed amount). *Mitigation:* `DailyTransactionLineMapperTest` asserts the full
  field-level decode of the shipped 350-byte fixture including positive (`+504.77`) and negative
  (`-919.00`) overpunch amounts at scale 2, and the Testcontainers `DailyTransactionLoadJobTest` launches
  the job against a real PostgreSQL 16 and asserts every fixture record lands in `daily_transaction` with
  the expected decoded values, that the load is rerun-safe, and that a launch without `inputResource`
  is rejected with `JobParametersInvalidException`.
- **Framing risk & mitigation (contiguous `RECFM=F` input):** A raw `RECFM=F` image carries **no** in-band
  newline, so a newline-splitting reader would treat a multi-record file as a single over-length "line"
  and **silently drop** every record after the first, ending `COMPLETED`/RC 0 while loading only one row.
  *Mitigation:* the `FixedLengthBufferedReaderFactory` frames strictly by the 350-byte record length — it
  skips any inter-record `LF`/`CR` as boundary framing (so a delimited file and a pure contiguous image
  read identically), tolerates only a blank trailing remainder, and **fails fast** (job `FAILED` → RC 8)
  on a non-blank short trailing record rather than admitting a truncated record. `DailyTransactionLoadJobTest`
  asserts that a 700-byte contiguous two-record image loads **both** rows and that a 300-byte truncated
  record fails the job with zero rows staged.

---

### D31 — Interest rounding uses HALF_UP (documented divergence from COBOL truncation)

- **Status:** Accepted
- **Type:** **Documented divergence from a literal COBOL translation** (mandated by the AAP monetary standard)
- **AAP references:** §0.4.2 (Money value object, `HALF_UP`), §0.7.1 hotspot H3 (COMP-3 monetary
  fidelity), §0.9.2 (golden-file parity)
- **Decision:** The monthly-interest computation in `batch/processor/InterestCalculationProcessor` —
  the reproduction of `1300-COMPUTE-INTEREST` (`COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE)
  / 1200`, `legacy/cbl/CBACT04C.cbl:L464-L465`) — is implemented as
  `tranCatBal.multiply(intRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)`. The legacy
  `COMPUTE` carries **no `ROUNDED` phrase** and therefore **truncates** the intermediate result to
  scale&nbsp;2, whereas the target applies **`RoundingMode.HALF_UP`**, the project-wide monetary
  standard mandated by AAP §0.4.2. The two results are identical except in the exact-half boundary
  case, where they intentionally differ: for example a raw category interest of `0.005` rounds to
  `0.01` here versus `0.00` under COBOL truncation.
- **Correction of prior documentation:** Earlier text in this log (D9) and in the
  `InterestCalculationJob` Javadoc described the interest computation as reproduced "to the cent" /
  "bit-for-bit". That was **inaccurate** for the exact-half case and is corrected here and in D9: the
  interest amount matches the COBOL value for every input **except** the exact-half boundary, where the
  HALF_UP standard deliberately diverges. Golden-file assertions therefore target the **HALF_UP**
  result, not the truncated COBOL value.
- **Alternatives:**
  1. *Truncate to match COBOL bit-for-bit (`RoundingMode.DOWN`).* **Rejected:** it contradicts the AAP
     §0.4.2 monetary standard (`HALF_UP` everywhere) and would make the interest job the lone
     inconsistent rounding site in the system. The AAP is the frozen source of truth and itself
     prescribes HALF_UP, so aligning to it is the parity-correct choice.
  2. *Silently keep HALF_UP and leave the "to the cent" wording.* **Rejected:** it violates the
     Explainability rule (every deviation must be an explicit, truthful decision-log entry).
- **Rationale:** AAP §0.4.2 makes `HALF_UP` at scale 2 the authoritative monetary rounding rule for the
  entire migration; the Money value object centralizes it. Honoring that standard (rather than the
  legacy truncation) is compliance with the frozen specification, and recording the resulting boundary
  divergence here — rather than papering over it with "to the cent" language — is what the
  Explainability rule requires.
- **Risk & mitigation:** A reviewer expecting literal COBOL parity might read the HALF_UP result as a
  regression in the exact-half case. *Mitigation:* the divergence is documented here, cross-referenced
  from the `InterestCalculationProcessor` and `InterestCalculationJob` Javadoc and from D9; the interest
  unit tests assert the HALF_UP outcome (including the `0.005 → 0.01` category row), and the canonical
  end-to-end run total (`37.51` across three generated interest transactions) reflects HALF_UP.

### D32 — Interest last-account finalization (corrects a latent legacy dead-ELSE)

- **Status:** Accepted
- **Type:** **Intentional improvement (deviation from literal COBOL — corrects a latent defect)**
- **AAP references:** §0.7.1 hotspot H3 (interest fidelity), §0.8.3 (deviations documented as
  improvements), §0.7.1 hotspot H6 (read-update-rewrite integrity)
- **Decision:** `batch/writer/InterestTransactionWriter` finalizes the **last** account of the interest
  run (applies the `1050-UPDATE-ACCOUNT` control-break account update — add the accumulated interest to
  `ACCT-CURR-BAL`, zero the cycle credit/debit) in its `afterStep(StepExecution)` callback, after the
  final category-balance row has been processed.
- **Legacy defect reproduced-then-corrected:** In CBACT04C the end-of-file branch
  `ELSE PERFORM 1050-UPDATE-ACCOUNT` (`legacy/cbl/CBACT04C.cbl:L219-L220`) is **structurally
  unreachable** under `PERFORM UNTIL END-OF-FILE = 'Y'` with the default `TEST BEFORE`: at loop-body
  entry `END-OF-FILE` is always `'N'`, so the outer `IF` is always true and the `ELSE` never runs.
  Consequently the legacy program **never applies the account balance update to the last account** —
  its interest transactions are still written, but its `ACCT-CURR-BAL` is not updated. The Java target
  applies the finalize to the last account as well, which is the evident intent of the control-break
  design.
- **Alternatives:** *Reproduce the dead-`ELSE` verbatim (skip the last account's balance update) for
  line-for-line parity.* **Rejected:** it propagates a demonstrable balance error to the final account
  of every run; AAP §0.8.3 frames such corrections as documented improvements, not parity violations.
- **How to revert to strict COBOL behavior:** Should exact legacy parity ever be required, remove the
  last-account finalization from `InterestTransactionWriter.afterStep(...)` (leaving the per-control-break
  finalization in `write(...)` intact); the writer would then reproduce the legacy behavior of never
  updating the last account's balance. This revert path is recorded here so the deviation is fully
  reversible and auditable.
- **Rationale:** The finalize is the correct control-break semantics and preserves monetary integrity
  for the last account. Recording it here (and cross-referencing it from the writer Javadoc) satisfies
  the Explainability rule and keeps the rationale out of code comments.
- **Risk & mitigation:** Golden-file account-balance fixtures must reflect the corrected behavior; a
  fixture derived from a raw legacy run would mismatch on the last account. *Mitigation:* the corrected
  behavior is documented here and the interest job's golden fixtures/tests are built against it. The
  account update participates in the chunk transaction with `@Version` optimistic locking (D18, AAP H6).

### D33 — Interest job requires the parmDate parameter (fail-fast validator)

- **Status:** Accepted
- **Type:** Robustness guard (fail-fast parameterization)
- **AAP references:** §0.4.4 (batch jobs launched explicitly / CI-CD), §0.5.4 (batch layer)
- **Decision:** `batch/InterestCalculationJob` declares a `JobParametersValidator` that requires a
  non-blank `parmDate` job parameter at launch. CBACT04C receives its run date as `PARM-DATE PIC X(10)`
  via `PROCEDURE DIVISION USING` (the `INTCALC.jcl` `PARM='2022071800'`); in the target that value is
  the `parmDate` parameter, late-bound by the `@StepScope` `InterestCalculationProcessor` and used to
  seed the high-order ten characters of every generated interest `TRAN-ID`.
- **Alternatives:**
  1. *Declare no validator (the prior state) and rely on the processor consuming the parameter.*
     **Rejected:** because `parmDate` is late-bound only when the step runs, omitting it fails deep
     inside the step with an opaque SpEL/binding error, or (worse) silently produces malformed
     transaction ids. QA flagged the absence of fail-fast validation.
  2. *Also validate the calendar validity / format of the date.* **Rejected (kept minimal):** the
     legacy `PARM-DATE` is a fixed-width positional field consumed positionally; the validator therefore
     checks only presence/blankness, preserving the legacy contract without adding calendar rules the
     COBOL never enforced at this boundary.
- **Rationale:** Validating at launch converts a confusing in-step failure into a clear
  `JobParametersInvalidException` naming the missing `parmDate`, matching the deterministic launch
  contract of the other in-scope jobs (for example the `inputResource` validator of D30). It changes no
  business logic — a run that already supplied `parmDate` behaves exactly as before.
- **Risk & mitigation:** A launch script or CI step that previously omitted `parmDate` (and happened to
  fail later) now fails immediately. *Mitigation:* the CI workflow and onboarding launch instructions
  are updated to pass `parmDate` for the interest job; the validator message states the required format
  (`CCYYMMDD` + two digits, e.g. `2022071800`). The fail-fast behavior is covered by an integration test.

---

### D34 — Card PAN masked in batch operational logs (PCI-DSS first-6/last-4)

- **Status:** Accepted
- **Type:** Intentional improvement (security / observability hardening; additive to D25)
- **AAP references:** §0.8.2 (Observability rule — never log full PII), §0.7.3 / L1 (intentional legacy
  security anti-patterns preserved), §0.9.3 (CVV never logged; passwords never logged)
- **Decision:** The batch validate processor (`batch/processor/DailyTransactionValidateProcessor`,
  CBTRN01C) masks the card number (PAN) in its operational anomaly log statements using a new
  `common/util/PanMasker` helper that reveals only the first six and last four digits (for example
  `9999999999999999` &rarr; `999999******9999`). The three affected log sites are the unverified-card
  `WARN`, the account-not-found `WARN`, and the successful-read `DEBUG`. All other references to the card
  number are left verbatim.
- **Scope boundary (what is deliberately NOT masked):** The PAN remains verbatim in every **external
  file contract and master-print output** — the DALYREJS reject image, the SYSTRAN interest
  transactions, the transaction backup, and the account/card master-print reports — because those
  reproduce the legacy record layouts byte-for-byte for behavioral parity (G3). Masking a PAN inside a
  fixed-width record or a master-print line would be a parity regression, not an improvement. D25
  established that the PAN is not part of the AAP sensitive-field set (CVV/SSN/government-id/date-of-birth)
  and is rendered verbatim by master-print for parity; D25 explicitly anticipated that log-masking of the
  PAN would be introduced "through a new decision entry" — this is that entry.
- **Alternatives:**
  1. *Leave the PAN unmasked in logs (the prior state).* **Rejected:** it contradicts the project's own
     masking discipline (SSN/date-of-birth/government-id are fully redacted in DTO `toString()`; the CVV
     and passwords are never logged) and the Observability rule's prohibition on logging full PII. QA
     observed the full 16-digit PAN emitted at `WARN` (production-enabled) and `DEBUG`.
  2. *Fully redact the PAN in logs (replace with a constant).* **Rejected:** the first-6/last-4 form is
     the PCI-DSS display convention and preserves enough of the number for operational triage
     (issuer/BIN and the last four) without exposing the account. Full redaction would reduce
     diagnosability for no additional security benefit at these non-financial log sites.
  3. *Mask at the logging-framework layer (a Logback pattern/converter).* **Rejected (kept local and
     explicit):** a call-site helper keeps the masking visible and traceable in code and cannot be
     accidentally bypassed by a logger reconfiguration; it also avoids masking the PAN where it is
     legitimately required verbatim (the file contracts above), which a blanket framework filter could
     not distinguish.
- **Rationale:** This is a non-functional, additive hardening. It changes no business logic, no return
  code, and no external record layout — the validate job remains read-only and returns RC&nbsp;0 with or
  without anomalies. It aligns the batch logs with the masking discipline already applied elsewhere in
  the codebase and with the Observability rule.
- **Risk & mitigation:** An operator searching logs for a full PAN will no longer find it. *Mitigation:*
  the first-6/last-4 form remains searchable and sufficient for triage; the raw PAN is still available in
  the `daily_transaction` staging row and the preserved file contracts for authorized reconciliation. The
  masking and the read-only RC&nbsp;0 behavior are covered by a Logback `ListAppender` integration test
  that asserts the full PAN never appears in any emitted log event.

---

### D35 — Atomic reject-file publish with a substituting ISO-8859-1 encoder

- **Status:** Accepted
- **Type:** Intentional improvement (robustness / atomicity; no behavioral or layout change on clean data)
- **AAP references:** §0.3.1 / §0.5.4 (DALYREJS external file contract), §0.7.1 / H4 (reject-code
  semantics preserved), §0.9.6 (external file contracts byte/semantically preserved)
- **Decision:** `batch/writer/DailyTransactionPostingWriter` writes the DALYREJS reject records to a
  sibling temporary file (`<name>.tmp` in the same directory) through a `BufferedWriter` wrapping an
  `OutputStreamWriter` whose ISO-8859-1 `CharsetEncoder` is configured with
  `onUnmappableCharacter(REPLACE)` and `onMalformedInput(REPLACE)`. On a clean run (`afterStep` with no
  I/O error) the temp file is published to the final `DALYREJS` path with an atomic move
  (`Files.move(..., ATOMIC_MOVE)`, falling back to `REPLACE_EXISTING` only where an atomic move is
  unsupported). A run counts as clean only when there was no reject-file I/O error **and** the step did
  not fail for any other reason; on any failure the temp file is discarded and the previous good reject
  file is left untouched (never destroyed, never left partial).
- **Alternatives:**
  1. *Keep the previous `Files.newBufferedWriter(path, ISO_8859_1)` with default options.* **Rejected:**
     it opened the final file directly with `TRUNCATE_EXISTING` (destroying any prior good output up
     front) and used the reporting encoder, which throws `UnmappableCharacterException` on the first
     character &gt; `0xFF`. QA reproduced a single `☕` (U+2615) in a description causing the flush to
     throw, the job to end RC&nbsp;8, and the entire `DALYREJS` file to be lost while the step context
     still reported `rejectCount=1` (a metadata/file inconsistency).
  2. *Catch the encoding exception and skip the offending record.* **Rejected:** silently dropping a
     reject record would break the row-for-row reject parity and hide data; substitution with the
     charset replacement byte preserves the record and its 430-byte framing while flagging the anomaly.
  3. *Switch the reject file to UTF-8.* **Rejected:** the DALYREJS contract is a fixed-width,
     one-byte-per-position ISO-8859-1 image (LRECL=430); UTF-8 would make a multi-byte character break
     column alignment and the fixed record length.
- **Rationale:** For faithfully EBCDIC-decoded staging data every character is already representable in
  ISO-8859-1, so the substitution never fires and the published bytes are identical to before &mdash;
  the golden DALYREJS output is unchanged. The change purely hardens two failure modes required by the
  checkpoint atomicity criterion ("a file-write failure must leave no orphan/partial final file"): a
  single un-encodable character no longer destroys the output, and a mid-write failure never overwrites
  a prior good file. Reject codes, ordering, the 430-byte image + trailer, the trailing framing byte,
  and the RC&nbsp;0/4/8 mapping are all preserved.
- **Risk & mitigation:** The replacement byte (`?`) is indistinguishable from a literal `?` in the
  data. *Mitigation:* this can only occur for a code point &gt; `0xFF`, which cannot arise from
  correctly EBCDIC-decoded staging; the substitution is documented here and covered by an integration
  test that injects a `>0xFF` character and asserts the file is still produced with the correct length
  and record count. A second test asserts that a clean run's bytes are unchanged.

---

### D36 — Fixed-width records are LF-framed and embedded delimiters are sanitized

- **Status:** Accepted
- **Type:** Contract preservation (documenting an existing framing deviation + hardening it)
- **AAP references:** §0.5.4 (fixed-width reader/writer layouts), §0.7.2 / M2 (external fixed-width file
  contracts), §0.9.6 (external file contracts byte/semantically preserved)
- **Decision:** The three fixed-width output writers (`DailyTransactionPostingWriter` DALYREJS 430&rarr;431,
  `InterestTransactionWriter` SYSTRAN 350&rarr;351, `TransactionReportWriter` report 133&rarr;134) each
  append a single line-feed (`0x0A`) after every fixed-length record as a physical record separator, and
  `common/util/FixedWidthCodec.writeAlphanumeric` now replaces any line-feed (`0x0A`) or carriage-return
  (`0x0D`) *inside* an alphanumeric field value with a space before the field is padded to width.
- **Context:** Legacy `RECFM=F` datasets carry no in-band record delimiter (the access method frames
  records by their fixed length). The Java target writes to a byte stream on an ordinary filesystem, so a
  trailing `LF` is added to keep the output human-inspectable and consumable by line-oriented tooling.
  This framing choice was previously documented only in code Javadoc, and `traceability-matrix.md`
  documented the newline for INPUT seed files only.
- **Alternatives:**
  1. *Emit no delimiter (pure `RECFM=F` image).* **Rejected (kept LF):** the length-framed form is
     preserved and reachable — every record is still exactly its fixed length, and the companion
     fixed-length reader (`FixedWidthCodec.readFixedLengthRecords` /
     `batch/reader/FixedLengthBufferedReaderFactory`, D30) frames records **by position** (exactly LRECL
     characters) and treats any inter-record `LF`/`CR` as boundary framing that it skips, so it reads the
     output correctly whether or not the trailing `LF` is present — but a plain trailing `LF` keeps the
     files diff-able and greppable for local validation and golden comparison, which the validation
     criteria rely on.
  2. *Escape embedded delimiters (for example to a printable sequence).* **Rejected:** an escape would
     change the field width and therefore the fixed record length; replacing one control character with
     one space is a strict 1:1 substitution that preserves the column layout.
  3. *Leave embedded `LF`/`CR` in field data (the prior state).* **Rejected:** QA showed that a
     `DALYTRAN-DESC` containing an embedded `0x0A` produced two `0x0A` bytes for one record (the data
     byte plus the framing byte), which a line-oriented reader mis-splits into two records. Sanitizing
     the field content removes that ambiguity while keeping the trailing framing byte as the sole record
     separator.
- **Rationale:** Faithfully decoded staging data does not contain control characters in text fields, so
  the sanitization is a no-op there and the golden output bytes are unchanged (a fast path returns the
  value untouched when no delimiter is present). The change only removes an ambiguity for adversarial or
  corrupt input, and it is applied centrally in `writeAlphanumeric` so all three writers — and the
  report header/detail cells that route through it — are covered at once. The literal structural
  segments placed via `putRaw` (for example `"-"` and `"Date Range: "`) contain no control characters and
  are intentionally not affected.
- **Risk & mitigation:** A downstream `RECFM=F` consumer expecting exactly LRECL bytes per record with no
  separator must strip the trailing `LF`. *Mitigation:* the framing is now documented here and in the
  traceability matrix; the record body remains exactly the fixed length, so a fixed-length reader that
  skips one separator byte per record reads correctly. Sanitization and length invariance are covered by
  a `FixedWidthCodec` unit test.

---

### D37 — Batch fixed-width writers require a single launch per JVM

- **Status:** Accepted
- **Type:** Constraint documented (in-JVM shared-state boundary) + code change for the backup writer's
  unique-per-run output filename (QA finding F7)
- **AAP references:** §0.4.4 (each batch job launched by CI/CD), §0.7.2 / M4 (JCL orchestration &rarr;
  Spring Batch + CI/CD scheduling)
- **Decision:** The three fixed-width output writers are singleton `@Component` beans that hold per-run
  mutable state (record/reject counters, running totals, last-account accumulator, and the open output
  stream) reset in `beforeStep`. Two concurrent same-JVM executions of the same step would race on that
  shared instance state, so the supported operating model remains <strong>one launch of a given step per
  JVM process</strong>; this in-JVM constraint is documented in each writer's class Javadoc rather than
  changed in code. Distinct from that shared-state concern, the <em>backup</em> writer previously also
  risked a <strong>cross-process</strong> collision: two <em>separate</em> JVM processes launched within
  the same wall-clock second both derived the identical second-granularity output filename
  `TRANSACT.BKUP.yyyyMMddHHmmss`, so one backup silently overwrote the other (QA finding F7). That
  filename collision <strong>is fixed in code</strong>: `TransactionBackupItemWriter.beforeStep` now
  appends a millisecond timestamp and a `.jobExecutionId-stepExecutionId` suffix taken from the shared
  `JobRepository` sequences, which are unique across every execution recorded in the database (including
  two separate processes), so each run writes a distinct file and no backup is lost. It is the faithful
  relational analog of the GDG `(+1)` new-generation number.
- **Alternatives:**
  1. *Convert the writers to `@StepScope`.* **Considered and deferred:** step scope would give each step
     execution its own writer instance and make concurrent same-JVM launches safe. It was not adopted
     because it changes bean lifecycle and injection semantics across the batch layer with no benefit to
     the supported operating model, and the checkpoint scope is behavioral parity of the pipelines, not a
     concurrency redesign. It is recorded here as the clean forward path if in-JVM concurrency is ever
     required.
  2. *Add explicit synchronization / per-run state objects.* **Rejected:** more complex than
     `@StepScope` for the same goal and still unnecessary under the process-per-job model.
- **Rationale:** The legacy JCL ran one job step per address space, and the target launches each Spring
  Batch job in its own process from the CI/CD scheduler; Spring Batch's own metadata locking already
  serializes accidental concurrent launches of the same job instance (surfacing as a
  `CannotAcquireLockException` rather than corrupt output). Documenting the constraint makes the design
  boundary explicit and truthful, matching QA's accepted resolution ("make the writers `@StepScope`, or
  document the single-launch-per-JVM constraint").
- **Risk & mitigation:** A future change that launches two of these jobs in one JVM concurrently could
  race on the shared counters/stream. *Mitigation:* the in-JVM constraint is documented on all three
  writers and here; the recorded `@StepScope` migration is the sanctioned remedy if that requirement
  arises. The separate cross-process backup-filename collision (F7) is eliminated by the unique-per-run
  filename and is regression-guarded by `TransactionBackupItemWriterTest` (two runs in the same second
  produce two distinct files, the execution-id suffix disambiguating) alongside the byte-exactness
  coverage in `TransactionBackupJobTest`.

---

### D38 — Reason code 109 is excluded from RejectCode and maps to return code 8

- **Status:** Accepted
- **Type:** Documented deviation (fault-handling hardening; the reject-reason set is preserved)
- **AAP references:** §0.4.2 (exception translation &mdash; FILE STATUS / reject codes &rarr; typed
  exceptions and batch return codes 0/4/8), §0.7.1 / H4 (batch posting reject-code semantics),
  §0.9.2 (reject-path parity for codes 100/101/102/103)
- **Decision:** The `RejectCode` enum models exactly the four pre-post validation reject reasons of
  `CBTRN02C` — `100` (`INVALID_CARD_NUMBER`), `101` (`ACCOUNT_NOT_FOUND`), `102` (`OVER_CREDIT_LIMIT`),
  and `103` (`ACCOUNT_EXPIRED`). Reason code `109`, set by `MOVE 109` in `2800-UPDATE-ACCOUNT-REC`
  (CBTRN02C L556) when the account `REWRITE` returns `INVALID KEY`, is **deliberately excluded** from
  the enum. It is not a validation reject reason: it arises in the posting phase *after* validation has
  already passed, during the balance update, and never produces a reject row. In the Java target an
  account that cannot be re-read for update at write time is treated as a hard integrity error — a
  `FileStatusException` that fails the step and surfaces as batch **return code 8** — rather than as a
  reject (`RC 4`) row. `109` is therefore never added to `RejectCode`.
- **Legacy-versus-target note:** In the COBOL, `109` is latent — it is moved into the reason field
  *after* the `reason == 0` gate, so `CBTRN02C` neither writes a reject nor abends on it; the record's
  outcome is effectively silent. The target's promotion of the same condition to a fatal `RC 8` is the
  intentional, caller-visible difference (a missing account at rewrite time is a genuine data-integrity
  fault, not a business reject). Because the processor already validated that the account exists and the
  writer re-reads it inside the same chunk-size-1 transaction, this branch is defensive and does not
  trigger on a consistent dataset (see also D39 for the analogous 101 reasoning).
- **Alternatives:**
  1. *Add `109` as a fifth `RejectCode` constant and emit a reject row.* **Rejected:** it would
     mis-model a posting-phase I/O abend as a pre-post validation reject, corrupt the `RC 4`-versus-`RC 8`
     contract, and diverge from CBTRN02C, where `109` produces neither a reject row nor an `RC 4`.
  2. *Silently swallow the rewrite failure (mirror the COBOL's latent behavior exactly).* **Rejected:**
     a missing account at rewrite time is an integrity fault; failing fast on `RC 8` is the safer,
     documented improvement and is preferable to silently losing a balance update.
- **Rationale:** Keeping `RejectCode` to the four true reject reasons preserves the reject-path parity
  the AAP mandates (§0.9.2) while routing the genuine I/O fault through the typed
  `FileStatusException` &rarr; `RC 8` path (§0.4.2). The rationale previously lived only in code Javadoc
  (`exception/RejectCode` scope note and `batch/writer/DailyTransactionPostingWriter` deviation note);
  this entry is its decision-log home so the code cross-references now resolve, and it is reflected in
  the traceability matrix.
- **Risk & mitigation:** A reader could expect `109` among the reject codes. *Mitigation:* the exclusion
  is documented on the enum, on the writer, here, and in the traceability matrix; the four reject codes
  retain dedicated tests and the `RC 8` integrity path is covered by the posting writer's fault tests.

---

### D39 — Reject 101 (account not found) is an unreachable defensive branch under the cross-reference foreign key

- **Status:** Accepted
- **Type:** Documented consequence (behavior preserved; branch and its unit coverage retained)
- **AAP references:** §0.7.1 / H4 (posting reject-code semantics and evaluation order), §0.4.3 /
  §0.9.2 (real foreign-key constraints; reject-path parity)
- **Decision:** The posting processor reproduces `CBTRN02C`'s two-stage lookup: `1500-A-LOOKUP-XREF`
  (reject `100` when the card cross-reference is missing) followed, only while the reason is still zero,
  by `1500-B-LOOKUP-ACCT` (reject `101` when the account resolved from the cross-reference is missing,
  CBTRN02C L397-L399). Because the relational target adds a **real foreign key**
  `fk_card_xref_account` on `card_xref.acct_id` &rarr; `account.acct_id` (see
  [D8](#d8--real-foreign-key-constraints)), every cross-reference row is guaranteed to point at an
  existing account. Consequently, once reject `100` has been passed (the cross-reference exists), the
  account lookup can never miss and reject `101` is **unreachable in production**. The decision is to
  **keep the `101` branch and its unit-level test coverage** as a faithful, defensive reproduction of
  the COBOL, and to document that the foreign key — not a code change — is what renders it unreachable.
  That unit coverage is two complementary assertions: `DailyTransactionPostingProcessorTest` mocks an
  absent account to prove the processor *chooses* `RejectCode.ACCOUNT_NOT_FOUND` (101), and
  `ExpectedReject101FixtureTest` proves the corresponding golden `expected-reject-101.dat` — an
  intentional **80-byte, trailer-only** fixture (rather than a full 430-byte load-based record, because
  the load path cannot reach `101`) — matches the production reject-writer trailer encoding
  byte-for-byte (reason code at offset `[350:354]`, reason description at `[354:430]`). The trailer-only
  size is therefore deliberate and now test-anchored, not an orphaned or truncated fixture.
- **Legacy-versus-target note:** Under the legacy VSAM, referential integrity between `CARDXREF` and
  `ACCTFILE` was enforced only in application logic, so a dangling cross-reference (and therefore a live
  `101`) was physically possible. Formalizing the relationship as a database foreign key (D8) removes
  that possibility by construction. Reaching the `101` branch in QA therefore required dropping the
  foreign key in an isolated test harness; it cannot occur against the migrated schema.
- **Alternatives:**
  1. *Delete the `101` branch as dead code.* **Rejected:** it is a real CBTRN02C reject reason and part
     of the mandated reject-path parity (§0.9.2); removing it would drop a documented reject code and its
     evaluation-order guarantee (100 short-circuits 101). It is defensive, not dead.
  2. *Drop the foreign key so `101` can trigger at runtime.* **Rejected:** the foreign key is an
     intentional integrity improvement (D8); weakening the schema to make a defensive branch reachable
     would be a regression.
- **Rationale:** Retaining the branch preserves 100&nbsp;% paragraph/reject parity with CBTRN02C while
  the foreign key preserves data integrity; the two are complementary. Documenting the unreachability
  keeps the traceability matrix honest (the branch maps to a real paragraph even though the FK guards it)
  and explains why an integration test cannot exercise `101` without deliberately breaking the schema.
- **Risk & mitigation:** A future schema change that removes the foreign key would silently make `101`
  reachable again. *Mitigation:* the dependency is documented here and cross-referenced from D8; the
  branch keeps unit coverage (both the processor decision test and the byte-for-byte fixture test noted
  above) so the reject reason and its serialized trailer are exercised regardless of the FK.

---

### D40 — Transaction report read order is (cardNum, tranId), a documented deviation from physical TRAN-ID order

- **Status:** Accepted
- **Type:** Documented deviation (read order only; report content unchanged)
- **AAP references:** §0.2.2 (SORT/MERGE &rarr; identical key ordering), §0.4.4
  (`TransactionReportJob` &larr; CBTRN03C), §0.9.2 (behavioral parity — content preserved)
- **Decision:** `CBTRN03C` reads the `TRANSACT` KSDS in physical primary-key (`TRAN-ID`) order and
  control-breaks on card as cards happen to appear in that sequence. The set-based
  `TransactionReportItemReader` instead orders the query
  `ORDER BY t.cardNum ASC, t.tranId ASC` (reader L148) so the card control break is **deterministic**
  rather than dependent on physical file order. The secondary `tranId` key preserves the legacy
  within-card `TRAN-ID` ordering. This changes the report's detail **read/emit order** only; it never
  changes report **content** — the per-card, per-page, and grand totals aggregate the same transactions
  to the same values regardless of read order.
- **Distinction from [D10](#d10--alternate-indexes-to-b-tree-indexes-vsam-browse-to-sortedpaged-queries):**
  D10 covers the general data-tier transformation of VSAM alternate-index browses into sorted/paged
  repository queries (an online and cross-cutting behavior-preservation decision). D40 is specifically
  the **batch transaction report's control-break read order** — a distinct, report-scoped deviation.
  This entry is its decision-log home; the `batch/TransactionReportJob` class Javadoc previously stated
  the deviation was "recorded in docs/decision-log.md" without a target, and that cross-reference now
  resolves here.
- **Alternatives:**
  1. *Read in physical primary-key (`tranId`) order to mirror the KSDS exactly.* **Rejected:** without a
     card-major sort the card control break would depend on insertion order and could interleave cards,
     producing malformed per-card subtotals. A deterministic, card-major order is the faithful intent of
     a card-broken report and keeps the output stable across runs.
  2. *Order by `cardNum` only.* **Rejected:** it would leave within-card detail order unspecified; adding
     `tranId` as the secondary key preserves the legacy within-card `TRAN-ID` sequence.
- **Rationale:** The report is a card-control-break report; a deterministic `(cardNum, tranId)` order is
  the correct, content-preserving realization of that intent and matches the AAP's "identical key
  ordering" guidance for SORT/MERGE work. Aggregation is performed in `BigDecimal` and is order-
  independent, so totals are unaffected.
- **Risk & mitigation:** A downstream consumer that depended on the exact physical `TRAN-ID` emit
  sequence would see a different detail ordering. *Mitigation:* the change is content-preserving and the
  golden-file report test asserts the canonical byte output; the deviation is documented here and
  cross-referenced from the job Javadoc and the traceability matrix.

---

### D41 — Statement output files carry no in-band delimiter (pure `RECFM=FB` image)

- **Status:** Accepted
- **Type:** Contract preservation (byte-exact external-file contract; corrects a framing defect)
- **AAP references:** §0.5.4 (`StatementGenerationJob` &larr; CBSTM03A + CBSTM03B; fixed-width
  reader/writer layouts), §0.7.1 / H3 (fixed-width record fidelity), §0.9.2 (golden-file row-for-row
  parity), §0.9.6 (external file contracts byte/semantically preserved), G3 (identical external
  interface contracts)
- **Decision:** `batch/writer/StatementItemWriter` writes the plain-text (`STMTFILE` /
  `FD-STMTFILE-REC PIC X(80)`) and HTML (`HTMLFILE` / `FD-HTMLFILE-REC PIC X(100)`) statement records
  **back-to-back with no in-band delimiter and no trailing newline**. A statement file of *n* records is
  therefore exactly *n* &times; width bytes (the golden fixtures are 22 &times; 80 = 1760 and
  97 &times; 100 = 9700 bytes), containing no `0x0A`/`0x0D` byte anywhere. Record boundaries are implied
  solely by the fixed record length, faithfully reproducing the COBOL `RECFM=FB` DD image
  (`CREASTMT.JCL` STEP040 `DCB=(...,RECFM=FB)`).
- **Context:** The prior implementation appended a single `LF` after each record, so the files were
  22 &times; 81 = 1782 and 97 &times; 101 = 9797 bytes — one framing byte per record more than the
  fixed-block contract. QA raised this as a **critical** parity defect: the statement outputs are the
  job's byte-exact acceptance artifact (asserted row-for-row and by pinned SHA-256 against the shipped
  goldens), and an in-band `LF` makes each physical record one byte longer than its `PIC X(80)`/`PIC
  X(100)` length.
- **Distinction from [D36](#d36--fixed-width-records-are-lf-framed-and-embedded-delimiters-are-sanitized):**
  D36 governs the three *other* fixed-width writers (`DailyTransactionPostingWriter` DALYREJS,
  `InterestTransactionWriter` SYSTRAN, `TransactionReportWriter` report), which retain a trailing `LF`
  **for diff-ability during local golden validation**. D41 is scoped **only** to the statement writer and
  reaches the opposite framing choice because its acceptance criteria differ: the statement tests read
  the outputs by **fixed-width slicing** (not by line terminator) and assert **raw-byte equality plus a
  pinned SHA-256** against the goldens, so the diff-ability rationale D36 cited does not apply here — and
  the byte-exact `RECFM=FB` image is the stronger, required contract. The golden README documents a
  width-based (not line-based) verification recipe. The two decisions are deliberately scoped to disjoint
  writer sets; harmonizing the other writers to a delimiter-free image is a separate, out-of-boundary
  consideration owned by those writers' pipelines.
- **Alternatives:**
  1. *Keep the trailing `LF` (as D36 does for the other writers).* **Rejected:** it violates the
     statement's byte-exact fixed-record contract (each record would be 81/101 physical bytes), which the
     golden row-for-row and SHA-256 assertions and AAP §0.9.6 require. The diff-ability benefit that
     justified the `LF` elsewhere is unnecessary here because the statement tests and the README verify by
     fixed width, not by line.
  2. *Escape or otherwise transform record content.* **Rejected:** any transformation would change the
     fixed record length and break byte parity; `FixedWidthCodec.writeAlphanumeric` already sanitizes any
     stray in-field `0x0A`/`0x0D` to a space (D36) so no delimiter byte can appear inside a record.
- **Rationale:** Removing the framing byte makes the Java output a pure length-framed image identical to
  the legacy `RECFM=FB` dataset and to the regenerated goldens, and lets the boundary test assert the
  strongest possible parity (raw-byte array equality, exact file length as an exact multiple of the
  record width, per-record byte offsets, and a pinned SHA-256). Record content, column layout, monetary
  edits, transaction ordering, and the RC 0/8 mapping are all unchanged.
- **Risk & mitigation:** A consumer that previously split the statement file on `LF` would now see one
  length-framed stream. *Mitigation:* fixed-length records are self-delimiting (read *width* bytes per
  record); the writer/boundary tests assert the no-delimiter contract (no `0x0A`/`0x0D`, exact multiple of
  width, fixed offsets), the goldens were regenerated to the delimiter-free image with recomputed
  checksums, and the golden README documents the width-based verification.

---

### D42 — Atomic statement-file publish with owner-only temporary work files

- **Status:** Accepted
- **Type:** Intentional improvement (robustness / atomicity; no behavioral or layout change on a clean run)
- **AAP references:** §0.5.4 (`StatementGenerationJob` writer), §0.7.1 / H3 (statement output fidelity),
  §0.9.6 (external file contracts byte/semantically preserved)
- **Decision:** `batch/writer/StatementItemWriter` streams both statement outputs to **unique per-run
  temporary work files created in the same output directory** (owner-only `rw-------` where the filesystem
  supports POSIX permissions), and publishes each onto its final path **only when the step succeeds**,
  using an atomic move (`Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`, falling back to a replacing move
  only where an atomic move is unsupported). A run counts as successful only when there was no I/O error
  **and** the step did not fail for any other reason; on any failure — including a publish or an open
  failure — the temporary work files are deleted and the final paths are left untouched. A publish failure
  maps to `ExitStatus.FAILED` (RC 8).
- **Context:** The prior implementation opened the **final** paths directly with `TRUNCATE_EXISTING`, so a
  chunk rollback, a mid-write I/O error, or a job failure could leave a half-written or truncated
  statement file at a final path (CWE-459). Because statement generation has no reject path, the only
  outcomes are a complete pair of files or a clean failure that publishes nothing.
- **Relationship to [D35](#d35--atomic-reject-file-publish-with-a-substituting-iso-8859-1-encoder):** this
  applies the same temp-write-then-atomic-publish integrity pattern D35 established for the DALYREJS reject
  file to the two statement outputs, adding owner-only permissions on the in-progress work files. The two
  statement files are independent artifacts (the two CBSTM03A output DDs) and are published independently;
  each published file is always complete (never truncated).
- **Alternatives:**
  1. *Keep opening the final paths directly with `TRUNCATE_EXISTING`.* **Rejected:** it destroys any prior
     good output up front and can leave a partial file on failure — the exact CWE-459 condition QA flagged.
  2. *Publish both files under a single all-or-nothing transaction.* **Rejected:** a two-file atomic swap
     is not available from the filesystem; per-file atomic publication already guarantees no partial file
     at any final path, matching the two independent COBOL output DDs.
- **Rationale:** On a clean run the published bytes are identical to before — the golden statement output
  is unchanged — because only the *destination mechanics* changed (write to a temp, then rename), not the
  record content. The change purely hardens the failure modes required by the checkpoint atomicity
  criterion ("a file-write failure must leave no orphan/partial final file") and additionally protects the
  in-progress artifact with owner-only permissions.
- **Risk & mitigation:** On a non-POSIX filesystem the owner-only permission attribute is not applied.
  *Mitigation:* the atomic-publish guarantee is independent of permissions; the owner-only step is
  best-effort and guarded, and the behavior is covered by writer unit tests (clean publish leaves no temp
  and, on POSIX, `rw-------` finals; a failed or aborted run publishes nothing and removes the temps).

---

### D43 — Statement HTML is a byte-exact batch artifact, not a served web view (F29/F32 disposition)

- **Status:** Accepted — **revised** (per-field HTML escaping now applied; see D44)
- **Type:** Documented disposition, revised — the byte-exact file contract is preserved **and** per-field HTML escaping is now applied as a security improvement (F29 / CWE-79); the two are compatible because escaping is a provable no-op for the metacharacter-free golden/real statement data
- **AAP references:** G3 (identical external interface contracts), G7 (no feature expansion), §0.3.3
  (3270/BMS screen rendering and new web UI explicitly out of scope), §0.7.1 / H3 (fixed-width record
  fidelity), §0.9.6 (external file contracts byte/semantically preserved)
- **Decision:** The HTML statement file (`HTMLFILE` / `FD-HTMLFILE-REC PIC X(100)`) is preserved as a
  **byte-exact reproduction of the legacy CBSTM03A HTML output**. QA raised the persisted-value HTML
  concatenation as a stored-XSS concern (F29) and the statement markup as an accessibility/UI concern
  (F32). The **F29 stored-XSS concern is now code-changed** via per-field HTML escaping applied inside
  `StatementProcessor` (recorded in detail in [D44](#d44--statement-html-fields-are-html-escaped-per-field-parity-preserved-for-metacharacter-free-data));
  the **F32 accessibility/UI restructuring remains dispositioned as out-of-scope** (a re-rendered,
  semantically-restructured statement view would be feature expansion, G7 / §0.3.3). The byte-exact
  **file** contract is retained throughout: escaping the free-text data values is a no-op for the
  metacharacter-free golden/real data, so the fixed 100-byte record image and the pinned golden SHA-256
  are unchanged.
- **Rationale (why the parity artifact is kept byte-exact while escaping hostile input):**
  - **It is a batch file, but it can still be opened in a browser.** The HTML statement is written to the
    `HTMLFILE` DD image by the batch writer and asserted byte-for-byte against the golden fixture. No
    controller serves it as an executable `text/html` response — but QA demonstrated that a data field
    reaching the statement (a transaction description supplied through `POST /api/v1/transactions/add`)
    can carry a `<script>` payload, and that opening the *produced file* in a browser executes it. Relying
    on "the application never serves it" is therefore insufficient defence-in-depth, so per-field escaping
    is applied at emission (D44). The earlier "CWE-79 does not arise" reading is **retracted** as too
    narrow.
  - **Per-field escaping does NOT break byte parity for the faithful case.** Escaping is applied only to
    the **free-text data values** (name, address lines, transaction id/description) *before* they are
    concatenated into the HTML template — never to the template literals or the numeric-edited fields.
    Escaping changes bytes **only when a field actually contains** `&`, `<`, `>`, `"`, `'` or a non-ASCII
    code point; the golden fixtures and the real seed/transaction pipeline contain **none** of these in
    those fields, so the emitted bytes are identical. This is verified empirically:
    `StatementGenerationJobTest.htmlStatementMatchesGolden` still passes `containsExactlyElementsOf(golden)`
    plus the byte-exact file size and the pinned `GOLDEN_HTML_SHA256`, and
    `htmlStatementEscapesHostileFieldData` proves hostile input is neutralised — both green in the same
    build. The frozen external-file contract (G3, §0.9.6) is thus honoured while CWE-79 is closed.
  - **A separate accessible/escaped rendering is feature expansion.** Producing a second, escaped and
    accessibility-restructured HTML view (semantic landmarks, viewport, contrast-adjusted styling) would
    add a capability beyond the existing COBOL scope — explicitly excluded by G7 and by §0.3.3, which
    rules out BMS/3270 rendering and any new web UI as out of scope.
- **Existing protections retained:** the writer never logs statement or HTML line content, and no CVV or
  password ever appears in a statement (enforced in application code); see
  [D22](#d22--password-hashing-and-cvv-hardening) and
  [D34](#d34--card-pan-masked-in-batch-operational-logs-pci-dss-first-6last-4).
- **Alternatives:**
  1. *HTML-escape the free-text data values before concatenation.* **Adopted (field-level).** This is the
     implemented resolution (D44): only the data values are escaped, not the template or numeric-edited
     fields, so byte parity is preserved for the metacharacter-free golden/real data while hostile input is
     neutralised. The earlier position that escaping *unconditionally* breaks parity was over-broad — it is
     true only for data that actually contains a reserved character, which is exactly the injection case.
  2. *Add an approved, escaped, accessible statement view alongside the parity artifact.* **Deferred as
     out of scope:** a new rendered/restructured view is feature expansion (G7, §0.3.3). This covers the
     F32 accessibility restructuring only; the F29 escaping is handled by alternative 1 above.
- **Risk & mitigation:** Field-level escaping expands a field's byte length when it contains a reserved
  character; on the fixed 100-byte HTML record a pathologically metacharacter-dense field could therefore
  be truncated by the writer. *Mitigation:* truncation can only **drop** trailing bytes, never synthesise a
  `<`, so a truncated entity (`&lt;scrip…`) remains inert — the security property holds regardless; and
  `htmlStatementEscapesHostileFieldData` asserts the 100-byte framing (file length an exact multiple of
  the record width) is preserved for hostile input. The metacharacter-free golden/real data never expands,
  so normal statements are byte-identical to the golden fixture.
### D44 — Statement HTML fields are HTML-escaped per field (parity preserved for metacharacter-free data)

- **Status:** Accepted — **revised** (supersedes the earlier "emitted unescaped" disposition)
- **Type:** Security improvement (F29 / CWE-79 stored XSS + Issue 3 charset mojibake), implemented with byte-exact golden parity preserved — documented deviation from a literal byte-move, in the same class as [D22](#d22--password-hashing-and-cvv-hardening)
- **AAP references:** §0.9.2 (batch outputs compared **row-for-row** against golden fixtures; statement byte parity), G3 / §0.9.6 (fixed-width record layouts byte/semantically preserved), §0.7.3 / L1 & [D22](#d22--password-hashing-and-cvv-hardening) (intentional legacy anti-patterns are **hardened as documented improvements**, not silently preserved when they are genuinely exploitable), §0.3.3 (a re-rendered/accessibility-restructured statement view remains out of scope), §0.8.2 (Explainability — this deviation is recorded here with rationale, alternatives and risk)
- **Decision:** The HTML statement produced by `batch/processor/StatementProcessor` (parity analog of legacy
  `CBSTM03A`, paragraphs `5000-CREATE-STATEMENT` / `5100-WRITE-HTML-HEADER` / `5200-WRITE-HTML-NMADBS` /
  `6000-WRITE-TRANS`) now **HTML-escapes each free-text data value** before it is concatenated into the HTML
  template. A private `escapeHtml` helper (invoked from `appendHtmlNameAddressBasic` and
  `appendHtmlTransactionRow`) maps `&`&rarr;`&amp;`, `<`&rarr;`&lt;`, `>`&rarr;`&gt;`, `"`&rarr;`&quot;`,
  `'`&rarr;`&#39;`, and any code point &ge; `0x80` &rarr; a numeric character reference (`&#nnn;`). It is
  applied **only to the customer/transaction data values** (name, address lines, transaction id and
  description) — never to the HTML template literals and never to the numeric-edited fields
  (account, balance, FICO, amount), which are structurally metacharacter-free. The **plain-text** statement
  is unchanged (it is not HTML and carries no injection or charset ambiguity), so `ST-*` text output stays
  byte-exact.
- **Why this preserves parity (the earlier "escaping breaks parity" claim was over-broad):** escaping only
  changes bytes when a data field **actually contains** one of the reserved characters or a non-ASCII code
  point. The golden fixtures and the real seed/transaction pipeline contain **none** of these in the
  escaped fields, so the emitted byte stream — the fixed `FD-HTMLFILE-REC PIC X(100)` record image and its
  pinned `GOLDEN_HTML_SHA256` — is identical. This is proven in the same test build:
  `StatementGenerationJobTest.htmlStatementMatchesGolden` still asserts
  `containsExactlyElementsOf(golden)` + byte-exact size + pinned SHA-256 (a no-op escape), while
  `htmlStatementEscapesHostileFieldData` seeds a `<script>` payload and Latin-1 data and asserts the raw
  tag never appears (only `&lt;script&gt;`), the framing stays a multiple of 100 bytes, and the whole file
  is pure ASCII.
- **Correction of the prior consequence claim (empirically false):** the superseded D44 stated that active
  markup injection "does not occur for real statement inputs" and required "synthetic data hand-crafted
  outside the fixed-width contract." QA disproved this: a transaction **description** supplied through the
  real REST path `POST /api/v1/transactions/add` (authenticated) flows into `TRAN-DESC` and is rendered on
  the statement, so `description=<script>alert('xss')</script>` reaches the HTML through a supported,
  in-contract input. The stored-XSS exposure was therefore real, not hypothetical, and is now closed.
- **Charset (Issue 3) resolved without touching the byte-exact meta line:** the legacy
  `HTML-L04 VALUE '<meta charset="utf-8">'` (`legacy/cbl/CBSTM03A.CBL:L153`) is reproduced byte-for-byte,
  yet the file is written in ISO-8859-1 — so a Latin-1 name byte (e.g. `0xE9` for `é`) previously rendered
  as the U+FFFD replacement glyph. Because every code point &ge; `0x80` is now emitted as a numeric
  character reference, the produced HTML is **pure ASCII**; ASCII is valid under both UTF-8 and ISO-8859-1,
  so the `charset=utf-8` meta tag no longer disagrees with the bytes. The mojibake is eliminated **without**
  editing the byte-exact meta record, preserving that line's golden parity.
- **Alternatives:**
  1. *Keep emitting fields unescaped (the previous decision).* **Rejected / superseded:** proven exploitable
     through a supported REST input, and the parity argument for keeping it was over-broad (escaping is a
     no-op for the metacharacter-free faithful data, so parity is not the cost it was assumed to be).
  2. *Escape the entire concatenated line (template + data).* **Rejected:** double-escaping the structural
     markup (`<p>` &rarr; `&lt;p&gt;`) would destroy the HTML and break the golden fixture; only the data
     values need encoding.
  3. *Fix the charset by rewriting the meta tag to `iso-8859-1` (or writing the file as UTF-8).* **Rejected:**
     editing `HTML-L04` breaks the byte-exact meta record (G3, §0.9.6), and writing UTF-8 would change every
     multibyte field's byte length and break the fixed 100-byte record image. Numeric character references
     make the whole file ASCII, which is charset-agnostic and keeps every golden byte intact.
- **Rationale:** Faithful behavioural parity remains the primary constraint, and it is **fully preserved**:
  the statement is still a golden-file-verified fixed-width batch artifact whose metacharacter-free output is
  byte-identical to the reference. Escaping is layered on top as a documented security/robustness improvement
  — precisely the treatment §0.7.3 / L1 and [D22](#d22--password-hashing-and-cvv-hardening) prescribe for an
  intentional legacy anti-pattern that turns out to be genuinely exploitable (here via a real REST input),
  rather than a benign demonstration. It is not feature expansion: no new endpoint, view, or capability is
  added; only the emission encoding of existing fields is hardened.
- **Risk & mitigation:** field-level escaping expands a field's byte length when it contains a reserved
  character, so on the fixed 100-byte HTML record a pathologically metacharacter-dense field could be
  truncated by the writer. *Mitigation:* truncation can only **drop** trailing bytes and can never synthesise
  a `<`, so a truncated entity (`&lt;scrip…`) stays inert — the XSS-safety property holds unconditionally;
  `htmlStatementEscapesHostileFieldData` asserts the 100-byte framing is preserved for hostile input; and the
  metacharacter-free golden/real data never expands, keeping normal statements byte-identical to
  [D21](#d21--testing-strategy-testcontainers-jacoco-80-golden-file-parity)'s golden fixture and AAP §0.9.2.
  The CVV is never rendered and passwords are never emitted (see [D22](#d22--password-hashing-and-cvv-hardening));
  the batch statement **writer**'s complementary encoding-robustness (unmappable code points &gt; `0xFF`) is
  covered by [D56](#d56--statement-writer-uses-a-substituting-iso-8859-1-encoder-no-whole-job-abort-on-unmappable-input).

### D45 — Batch process exit code equals the Spring Batch return code (JCL condition-code parity)

- **Status:** Accepted
- **Type:** Behavior preservation (parity)
- **AAP references:** §0.2.2 (JCL job &rarr; step ordering, dependencies, **return codes**), §0.4.4
  (JCL &rarr; Spring Batch), §0.7.2 (M4 — return-code gating 0/4/8), §0.9.6 (batch parity)
- **Decision:** Propagate each Spring Batch job's return code as the **operating-system process exit
  code** so a launched batch job behaves like a JCL job step whose condition code gates downstream work.
  A dedicated `config/BatchExitCodeGenerator` (`@Component` implementing
  `ApplicationListener<JobExecutionEvent>` **and** `ExitCodeGenerator`) accumulates every `JobExecution`
  published by Spring Boot's `JobLauncherApplicationRunner` and, in `getExitCode()`, returns the **maximum**
  of a per-execution mapping keyed on the **`ExitStatus` code string**: `COMPLETED` &rarr; `0`,
  `COMPLETED_WITH_REJECTS` &rarr; `4`, and any other outcome (`FAILED`, `STOPPED`, unknown) &rarr; `8`.
  `CardDemoApplication.main()` then calls `System.exit(SpringApplication.exit(context))` — **but only when
  the context is not a `WebServerApplicationContext`**, so the online REST mode (servlet web context) never
  calls `System.exit` and the embedded server keeps serving; only non-web batch launches
  (`--spring.main.web-application-type=none`) propagate the code.
- **Alternatives:**
  1. *Spring Boot's built-in `JobExecutionExitCodeGenerator`.* **Rejected:** it maps by **`BatchStatus`**,
     not by the job's `ExitStatus` code — a job that finishes `COMPLETED` while setting a
     `COMPLETED_WITH_REJECTS` exit status is reported as `0` (losing the RC 4 reject signal), and `FAILED`
     maps to `5`, not the AAP's `8`. It cannot express the 0/4/8 contract. (The two generators **coexist
     safely** because `SpringApplication.exit` takes the **max** across all registered generators: for a
     reject run `max(0, 4) = 4`, and for a failure `max(5, 8) = 8`, so the custom generator dominates.)
  2. *A bespoke `ApplicationRunner` per job that calls `System.exit`.* **Rejected:** it duplicates logic
     across jobs and fights `JobLauncherApplicationRunner`, which already launches the requested job and
     publishes exactly one `JobExecutionEvent` per job.
  3. *Parse job logs in CI to infer success/failure.* **Rejected:** brittle and non-authoritative.
- **Rationale:** The JCL scheduler and the CI `scheduled-batch` job (D14) gate on the numeric condition
  code; that gating is only meaningful once the **process** actually returns the code. A **single global
  listener** captures every job launched in the JVM without per-job wiring, and keying on the `ExitStatus`
  code string is what lets the reject signal (`COMPLETED_WITH_REJECTS`, set by the posting writer and by
  the combine writer per [D46](#d46--combtran-reproduces-idcams-repro-without-replace-reject-duplicates-preserve-stale-rows-rc-4))
  surface as `4` rather than being flattened to `0`. Before this decision, `main()` was a bare
  `SpringApplication.run(...)` that always exited `0`, silently masking `FAILED` (should be `8`) and
  with-rejects (should be `4`) jobs and defeating both the JCL-parity contract and the CI gate.
- **Risk & mitigation:** Calling `System.exit` in the online web profile would terminate the server.
  *Mitigation:* the `WebServerApplicationContext` guard restricts the call to non-web (batch) contexts,
  and the online integration tests boot the servlet context and confirm it keeps serving. A future job
  emitting a novel `ExitStatus` code maps to `8` — the safe "error" default. `BatchExitCodeGeneratorTest`
  asserts every mapping including the max-across-jobs cases, and runtime re-verification confirms a clean
  job exits `0`, a with-rejects job exits `4`, a `FAILED` job exits `8`, and web mode still serves.

---

### D46 — COMBTRAN reproduces IDCAMS `REPRO`-without-`REPLACE` (reject duplicates, preserve stale rows, RC 4)

- **Status:** Accepted
- **Type:** Behavior preservation (parity)
- **AAP references:** §0.2.2 (IDCAMS `REPRO` copy/load; SORT &rarr; identical key ordering),
  §0.4.4 (`TransactionCombineJob` &larr; `COMBTRAN`), §0.8.1 (100% behavioral parity — the legacy JCL is
  the authoritative definition of observable behavior), §0.7.2 (M4)
- **Decision:** `TransactionCombineJob`'s load step reproduces the exact semantics of
  [`legacy/jcl/COMBTRAN.jcl`](../legacy/jcl/COMBTRAN.jcl) `STEP10`, which is
  `REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)` where `TRANVSAM` is the **existing** `TRANSACT.VSAM.KSDS`
  opened `DISP=SHR` with **no** preceding `DELETE`/`DEFINE` and **no** `REPLACE` keyword. Accordingly the
  `TransactionJpaItemWriter`:
  - **inserts** each transaction whose id is not already present (`existsById` guard &rarr; `save`);
  - **rejects (skips)** each transaction whose id already exists, incrementing a reject counter and
    logging it (the relational analog of IDCAMS `IDC1440I` "duplicate record");
  - via a `StepExecutionListener.afterStep`, maps the outcome to the batch return code: `FAILED` &rarr;
    `ExitStatus.FAILED` (RC 8), `rejectCount > 0` &rarr; `new ExitStatus("COMPLETED_WITH_REJECTS")` (RC 4),
    otherwise `ExitStatus.COMPLETED` (RC 0).

  The step's **chunk size is 1** so each insert commits before the next `existsById` check, which makes
  **intra-input** duplicates detectable as well — when the same id appears twice within the combined,
  sorted `SORTIN` stream (backup source `TRANSACT.BKUP(0)` concatenated **before** system source
  `SYSTRAN(0)`, per the JCL) the **first occurrence wins** and the second is rejected. **Stale** rows
  already in the `transaction` table that appear in neither input are left **untouched**.
- **Deliberate divergence from the QA finding's phrasing (Explainability):** the finding's checkpoint
  paraphrase described the expectation as *stale-row removal* (truncate/replace the target). That is the
  behavior of `REPRO` **with** `REPLACE`, or of a `DELETE`+`DEFINE`+`REPRO` sequence — **neither of which
  appears in `COMBTRAN.jcl`.** Because the AAP §0.8.1 behavioral-parity constraint makes the **legacy JCL
  the authority** for observable behavior, the faithful COMBTRAN behavior is **merge-insert with duplicate
  rejection and stale-row survival**, and a run that rejects only duplicates ends `COMPLETED_WITH_REJECTS`
  (RC 4), not RC 8. The finding's own body acknowledges this true `REPRO`-without-`REPLACE` semantics
  (reject the duplicate, load the rest, RC 4); this entry records the deliberate choice to follow the
  legacy source over the one-line paraphrase.
- **Why RC 4 (not RC 8) for duplicates:** duplicate-key rejection is a **warning-level** IDCAMS condition;
  `REPRO` completes with `MAXCC 4` when it rejected only duplicate keys, reserving RC 8 for hard failures
  (open/I/O errors). Mapping duplicates to `COMPLETED_WITH_REJECTS`/`4` preserves the condition code that
  downstream JCL (and now the CI gate) tests. That RC 4 is what
  [D45](#d45--batch-process-exit-code-equals-the-spring-batch-return-code-jcl-condition-code-parity)
  translates into process exit code `4`, end-to-end.
- **Alternatives:**
  1. *`faultTolerant().skip(DataIntegrityViolationException.class)`.* **Rejected:** it depends on the
     persistence layer throwing on the duplicate at flush; with chunk > 1 the whole chunk rolls back,
     muddying the exact reject count and the deterministic "first-occurrence-wins" order. The explicit
     `existsById` guard is deterministic and cheaper to reason about.
  2. *Truncate-then-insert (`REPLACE` semantics).* **Rejected:** unfaithful to `COMBTRAN.jcl` per the
     parity constraint — the target KSDS is never emptied.
  3. *Upsert / overwrite existing rows.* **Rejected:** `REPRO`-without-`REPLACE` never overwrites an
     existing record; it rejects the incoming duplicate.
- **Rationale:** Exact JCL parity for the observable outcomes — which records land in the target, which
  are rejected, and the resulting condition code. `chunk = 1` is the minimal change that makes intra-input
  duplicate detection correct without fault-tolerance rollback semantics.
- **Risk & mitigation:** `chunk = 1` commits one record at a time and is slower than a batched chunk;
  acceptable for the combine volume and **required** for record-at-a-time `REPRO` semantics — documented
  here. Reordering the two inputs would change which duplicate "wins"; *mitigation:* the reader preserves
  the legacy concatenation order (backup before system) and
  `duplicateWithinInputIsRejectedWithReturnCodeFour` asserts it. The integration tests
  `staleRowSurvivesWithReturnCodeZero`, `duplicateInTableIsRejectedAndRestLoadedWithReturnCodeFour`, and
  `duplicateWithinInputIsRejectedWithReturnCodeFour`, together with the existing disjoint-merge and
  sort-order tests, exercise every path.

---

### D47 — COMBTRAN requires at least one existing SORTIN member (missing-input fail-fast)

- **Status:** Accepted
- **Type:** Contract preservation (faithful `SORT` input-concatenation semantics; fail-fast on an empty
  concatenation)
- **AAP references:** §0.5.4 (SORT/MERGE → Java sort; batch layer), §0.7.2 / M4 (JCL orchestration; DD
  concatenation), §0.8.1 (behavioral parity — the legacy JCL is the authority for observable behavior)
- **Decision:** `batch/reader/CombinedTransactionItemReader` resolves its two `SORTIN` members — the
  `backupResource` (`TRANSACT.BKUP(0)`) concatenated **before** the `systemResource` (`SYSTRAN(0)`), per
  the legacy `COMBTRAN.jcl` order (D46) — with three distinct outcomes: (a) a **null** job parameter means
  the member was *not supplied* and is skipped **silently**; (b) a **non-null** parameter that points to a
  **non-existent** file is *supplied but absent* and is skipped with a `WARN` naming the parameter and the
  resolved location; (c) an **existing** file (even a zero-byte one) is *present* and contributes its
  records (possibly zero). If **no** member resolves to an existing dataset, `open()` throws
  `ItemStreamException`, so the step — and therefore the job and the process exit code — **fails** (RC 8
  per [D45](#d45--batch-process-exit-code-equals-the-spring-batch-return-code-jcl-condition-code-parity)).
- **Context:** In the legacy `COMBTRAN.jcl` the `SORTIN` DD concatenates two members; a `SORT` over an
  **empty** concatenation is an operational error (nothing to sort), whereas a present-but-empty member is
  a normal, if degenerate, input. The Java reader must reproduce that distinction: an absent *required*
  input is a hard failure, while an empty-but-present input is a valid zero-record contribution.
- **Alternatives:**
  1. *Treat a missing member as an empty input (the prior behavior).* **Rejected:** QA showed that when
     **both** members were missing the job ended `COMPLETED` with exit `0` and **no diagnostic**, masking a
     mis-specified `SORTIN` as a successful no-op — the opposite of the legacy operational-error signal.
  2. *Fail whenever any supplied member is absent.* **Rejected:** the two members are a concatenation; the
     legacy job runs whenever **at least one** member is present, so failing on a single absent member
     would reject runs the legacy accepts (for example a day with only `SYSTRAN`).
  3. *Fail when a member is present but empty.* **Rejected:** a zero-byte member is a valid (degenerate)
     concatenation element in the legacy job and must contribute zero records, not fail.
- **Rationale:** Exact parity for the observable outcome — a run with at least one existing input proceeds
  (and merges/rejects per D46); a run with **no** existing input fails loudly with a message that names
  both parameters and the expected members, so a mis-specified `SORTIN` is caught at launch rather than
  silently producing an empty combine. The `WARN` on a supplied-but-absent member preserves the lenient
  concatenation while leaving an operator-visible breadcrumb.
- **Risk & mitigation:** Distinguishing "not supplied" (null, silent) from "supplied but absent"
  (non-null, `WARN`) relies on the job-parameter binding. *Mitigation:* `TransactionCombineJobTest`
  asserts `failsWhenNoInputResolves` (both absent → `FAILED`, zero rows loaded) and
  `skipsMissingInputAndLoadsThePresentOne` (one absent + one present → `COMPLETED`, the present member
  fully loaded); the runtime re-verification launched the packaged jar with both resources absent and
  observed process exit code `8`, two `WARN` lines, and the `No COMBTRAN input resolved` diagnostic, with
  the target table left unchanged.

---

### D48 — Master-print execution banners are reproduced via a job listener

- **Status:** Accepted
- **Type:** Contract preservation (SYSOUT execution-boundary banners)
- **AAP references:** §0.5.4 (batch layer; master-print jobs), §0.8.1 (behavioral parity &mdash; observable
  SYSOUT), §0.8.2 Explainability (every deviation documented)
- **Decision:** The four read-only master-print jobs &mdash; `AccountMasterPrintJob` (`CBACT01C`),
  `CardMasterPrintJob` (`CBACT02C`), `XrefPrintJob` (`CBACT03C`) and `CustomerMasterPrintJob`
  (`CBCUS01C`) &mdash; reproduce the legacy SYSOUT execution-boundary banners
  `START OF EXECUTION OF PROGRAM <name>` and `END OF EXECUTION OF PROGRAM <name>` (each program's
  {@code DISPLAY} at `legacy/cbl/<name>.cbl` L71 START / L85 END) through a reusable
  `batch/ExecutionBannerJobListener(programName)` registered on each `JobBuilder` **after** the
  `CorrelationIdJobListener`. `beforeJob` emits the START banner **unconditionally** (the legacy START
  `DISPLAY` is the first `PROCEDURE DIVISION` statement, always reached); `afterJob` emits the END banner
  **only when** the job finished `BatchStatus.COMPLETED`.
- **Context:** In each legacy program a file-I/O failure routes through the `Z-ABEND-PROGRAM` paragraph
  and the LE `CEE3ABD` service (return code `8`), which terminates the program **before** control can fall
  through to the `DISPLAY 'END OF EXECUTION...'` line. Spring Batch, by contrast, always invokes
  `afterJob` (success or failure), so the END banner must be guarded to fire only on a normal pass to keep
  the observable SYSOUT contract: a `FAILED` run prints START but no END. Registering the banner listener
  **after** the correlation listener means the correlation id is on the MDC before the START banner is
  logged and is still present when the END banner is logged (Spring Batch runs `beforeJob` in registration
  order and `afterJob` in reverse), so both banners carry the run's `correlationId`.
- **Alternatives:**
  1. *Document the deviation only (banner &rarr; Spring Batch lifecycle log), do not emit the literals.*
     **Rejected:** the banners are an observable SYSOUT contract and the checkpoint expects master prints
     to verify headers/trailers, not merely `COMPLETED` status; emitting the exact literals is faithful
     parity, and the deviation would otherwise be undocumented (an Explainability gap).
  2. *Emit the END banner unconditionally in `afterJob`.* **Rejected:** unfaithful &mdash; the legacy
     abend bypasses the END `DISPLAY`, so a `FAILED` run must print START but not END.
  3. *Inline the two `DISPLAY` equivalents inside each job's writer or step.* **Rejected:** the banners
     bracket the **whole program run**, not a record or a step; a job-level `JobExecutionListener` is the
     exact analog and avoids duplicating the literal (and the COMPLETED guard) across four writers. A
     single reusable listener parameterized by program name keeps one implementation.
- **Rationale:** Exact parity for the observable SYSOUT boundary of each master print, delivered as one
  small reusable component with the abend-bypasses-END semantics preserved, and with both banners
  correlated to the run. The banners are emitted through SLF4J at `INFO` (the idiomatic analog of COBOL
  `DISPLAY` to SYSOUT) with message text byte-identical to the legacy literal so they stay greppable for
  local validation.
- **Risk & mitigation:** A future edit could drop the listener from one job or break the COMPLETED guard.
  *Mitigation:* `ExecutionBannerJobListenerTest` asserts START-always, END-only-on-COMPLETED (and
  no-END-on-FAILED) plus constructor validation, and each of the four master-print integration tests
  (`AccountMasterPrintJobTest`, `CardMasterPrintJobTest`, `XrefPrintJobTest`,
  `CustomerMasterPrintJobTest`) asserts its job emits exactly `[START, END]` for its program name on a
  COMPLETED run.

### D49 — SCA gate remediation: forward-pin fixable CVEs, narrowly suppress the two no-fix advisories

- **Status:** Accepted
- **Type:** Security remediation (transitive-dependency CVEs) + documented suppression
- **AAP references:** §0.6.1 (`dependency-check-maven` 12.2.2, `failBuildOnCVSS`), §0.8.1 (security
  scanning: zero critical/high CVEs), §0.9.3 (OWASP dependency-check reports zero critical/high CVEs)
- **Context:** As the NVD data feed advanced, the OWASP dependency-check gate (`failBuildOnCVSS=7`,
  scanning all scopes per [D5](#d5)) began failing `./mvnw clean verify` on seven high/critical
  transitive CVEs disclosed after the dependency graph was frozen. The gate is behaving correctly; the
  fix is to move each affected coordinate to a patched release where one exists, and to suppress —
  narrowly, dated, and documented — only those with no reachable patched release.
- **Decision:** Two complementary mechanisms, mirroring the existing forward-pin pattern
  ([D5](#d5); the embedded-Tomcat pin in `pom.xml` properties):
  1. **Forward-pin (fix available)** — override the Spring-Boot-managed version so the patched release
     lands on the graph across every scope:
     - `log4j2.version` &rarr; **2.25.5** (transitive via `spring-boot-starter-logging`'s
       `log4j-to-slf4j`/`log4j-api` bridge): remediates CVE-2026-34479 (7.5) and the same-line
       CVE-2026-34477 / CVE-2026-49844. Logback remains the active backend; only the bridge coordinate
       moves.
     - `postgresql.version` &rarr; **42.7.13** (direct runtime JDBC driver): remediates CVE-2026-54291 (8.2).
     - `io.opentelemetry.semconv:opentelemetry-semconv` &rarr; **1.43.0** via `<dependencyManagement>`
       (transitive via `micrometer-tracing-bridge-otel` 1.5.12; not covered by any managed property, so
       pinned explicitly): remediates CVE-2026-39883 (7.3) and CVE-2026-24051. The artifact is a passive
       attribute-key constants provider, so the forward pin is source-compatible; the full
       `mvnw clean verify` (Micrometer/OTel tracing tests included) confirms no runtime regression.
  2. **Suppress (no fix reachable)** — in `owasp-suppressions.xml`, scoped to an exact packageUrl **and**
     exact CVE, each with an `until` re-review date (2026-10-19) so the suppression expires and re-fails
     the build by design:
     - `org.jetbrains.kotlin:kotlin-stdlib`(`-jdk7`/`-jdk8`/`-common`) — **CVE-2026-53914 (9.8)**.
       Fixed only in Kotlin **2.4.20**, which upstream ships exclusively as a pre-release (`2.4.20-Beta1`);
       the latest *stable* Kotlin remains below the fixed line. Forward-pinning to a beta would violate the
       "no unverified/pre-release dependency" discipline, and replacing the OTLP transport's `okhttp`
       (which pulls in kotlin-stdlib) risks regressing the rule-mandated tracing exporter. Transitive
       runtime dependency of an optional exporter; no attacker-reachable entry point in this application.
     - `org.apache.httpcomponents.core5:httpcore5`(`-h2`) `@5.0.2` — **CVE-2026-54399 (7.5),
       CVE-2026-54428 (7.5)**. These classes are **shaded** inside
       `com.github.docker-java:docker-java-transport-zerodep:3.4.2` (Testcontainers, **test scope**);
       because they are relocated into the uber-jar they are not an external coordinate and
       `<dependencyManagement>` cannot rewrite them. The artifact is confined to the integration-test
       harness and is **absent from the production fat jar**.
- **Alternatives:**
  1. *Raise `failBuildOnCVSS` above 7 (e.g., 9) to "pass".* **Rejected:** it would silently weaken the
     mandated zero-high-CVE gate for every dependency, not just these advisories — the opposite of the
     constraint.
  2. *Broadly suppress by artifact only (no CVE / no date).* **Rejected:** a blanket suppression would
     mask future, unrelated CVEs on the same artifact. Each entry is pinned to a specific CVE and expires.
  3. *Pin Kotlin to `2.4.20-Beta1`.* **Rejected:** pre-release dependency; not production-appropriate.
  4. *Swap the OTLP exporter transport to drop `okhttp`/Kotlin.* **Rejected:** disproportionate change to
     a working, rule-mandated observability path to chase a non-reachable transitive CVE.
- **Rationale:** Everything with a patched release is upgraded; only genuinely unfixable advisories are
  suppressed, and those are narrowly scoped, justified, and time-boxed. After remediation the maximum
  CVSS across all remaining (non-suppressed) reported advisories is **6.0** — below the 7.0 gate — so the
  "zero critical/high CVE" constraint holds on its own merits, not by masking. Residual sub-threshold
  advisories (e.g., `jackson-databind` CVE-2026-54515, `opentelemetry-semconv` CVE-2026-41178, the
  bundled Swagger-UI DOMPurify advisories, `kotlin-stdlib` CVE-2020-29582) are all &lt; 7.0 and therefore
  outside the gate; they are governed by the same `failBuildOnCVSS=7` threshold as everything else and
  will trip the build if any is ever re-scored to high.
- **Risk & mitigation:** (a) A future NVD update could re-score a residual advisory to &ge; 7.0 and fail
  the build — *acceptable and intended*; it forces a fresh remediation decision. (b) The two suppressions
  expire on 2026-10-19; if no stable fix exists by then the build re-fails, forcing re-review rather than
  indefinite silent debt. (c) The semconv 1.43.0 forward pin spans several minor versions above the
  Micrometer bridge's expectation; *mitigation:* the full test suite (including tracing) runs in
  `verify`, and the pin is source-compatible in practice — if a future bridge upgrade conflicts, the two
  semconv CVEs would instead be suppressed like the others.
## J. Online Parity Corrections (QA-checkpoint remediation)

These entries record the root-cause fixes applied at the final Online / API / Data parity checkpoint.
Each restores 100% behavioral parity with the legacy COBOL where the earlier implementation had diverged;
none adds a business feature. They are logged individually so the Explainability rule's "explicit
decision-log entry" requirement is met for every checkpoint finding, and each is cross-referenced from the
[traceability matrix](./traceability-matrix.md).

### D50 — Account state and ZIP combination edit is enforced

- **Status:** Accepted
- **Type:** Behavior preservation (parity fix) — resolves finding **F-CAUP-1**
- **AAP references:** §0.2.2 (edit paragraphs → rule components), §0.7.1 (H2), §0.8.3
- **Decision:** `AccountService.editStateZip` delegates to `service/rule/UsStateZipRule.validate(stateCode, zip)`
  — the 240-entry state + ZIP-prefix lookup — and latches the message *"Invalid zip code for state"* when the
  combination is absent, reproducing `1280-EDIT-US-STATE-ZIP-CD` in `legacy/cbl/COACTUPC.cbl`
  (`legacy/cbl/COACTUPC.cbl:L2536-L2557`).
- **Alternatives:** Keep the prior implementation, which validated only that the state code was in the valid
  set and that the ZIP began with two digits, never consulting the combination table. *Rejected:* it
  accepted impossible combinations (for example `state=CT, zip=90210`) that the COBOL rejects — a behavioral
  regression against `1280-EDIT-US-STATE-ZIP-CD`.
- **Rationale:** The `UsStateZipRule` component (240 combinations) already existed but was **orphaned** — never
  injected or invoked. Wiring it into `editStateZip` restores literal parity; the individual state-code and
  ZIP-numeric edits (`1270-EDIT-US-STATE-CD`) continue to run first, so the combination edit fires only for
  two individually-valid values, exactly as the legacy paragraph ordering intends.
- **Risk & mitigation:** The combination table is large; an incorrect entry would wrongly accept or reject a
  pair. *Mitigation:* the rule's own unit tests plus `AccountService` tests exercise both an accepted pair
  and a rejected pair (`CT`+`90210`), and the fix was runtime-verified against the live database.

---

### D51 — Account update preview echoes the submitted candidate values

- **Status:** Accepted
- **Type:** Behavior preservation (parity fix) + implementation technique — resolves finding **F-CAUP-2**
- **AAP references:** §0.2.2 (BMS field-level contract), §0.4.2, §0.7.1 (H1)
- **Decision:** On the *validated-but-unconfirmed* redisplay (`CHANGES-OK-NOT-CONFIRMED`), the response echoes
  the operator's **just-entered candidate values**, reproducing `3203-SHOW-UPDATED-VALUES` in
  `legacy/cbl/COACTUPC.cbl`. `AccountService.previewOf` builds **transient (detached) copies** of the managed
  account and customer entities (via their all-args constructors, carrying the fetched `@Version`), applies
  the candidate values to those copies, and maps the copies into the response DTO.
- **Alternatives:** (a) Apply the candidate values directly to the **managed** entities for the preview.
  *Rejected:* those entities are attached inside the `@Transactional` boundary, so mutating them risks a JPA
  dirty-flush that would persist **unconfirmed** changes — the opposite of the legacy behavior. (b) Continue
  returning the persisted originals (the pre-fix behavior). *Rejected:* it showed stale values, diverging from
  `3203-SHOW-UPDATED-VALUES`.
- **Rationale:** The transient-copy technique reproduces the legacy candidate echo while guaranteeing the
  managed entities are never mutated on the preview path, so **no flush occurs** and nothing is persisted until
  the operator confirms (`PF5`). The card-update screen (`COCRDUPC`) already used this candidate-echo pattern;
  this aligns account update with it.
- **Risk & mitigation:** A future edit could accidentally mutate the managed entity instead of the copy.
  *Mitigation:* a test submits a change and asserts the preview echoes the candidate **and** the database row
  (including `version`) is unchanged after repeated preview submits (runtime-verified).

---

### D52 — Transaction-add edits run before the confirm decision

- **Status:** Accepted
- **Type:** Behavior preservation (parity fix) — resolves finding **F-CT02-1**
- **AAP references:** §0.2.2 (paragraph control flow), §0.5.3, §0.8.3
- **Decision:** `TransactionAddController.processEnter` runs `TransactionService.validateAddCommand` (the key
  and data edits — `VALIDATE-INPUT-KEY-FIELDS` + `VALIDATE-INPUT-DATA-FIELDS`) **before** the `EVALUATE CONFIRMI`
  confirm decision, reproducing `PROCESS-ENTER-KEY` in `legacy/cbl/COTRN02C.cbl` (L164-169: the two
  `VALIDATE-INPUT-*` performs precede `EVALUATE CONFIRMI`). A failing field
  edit short-circuits to a same-screen redisplay (HTTP 200); an unresolved account/card key propagates as a
  404/409 — in both cases **regardless of the confirm flag**. Only after every edit passes is the confirm
  branch (`Y` → add, `N`/blank → prompt) evaluated.
- **Alternatives:** Keep the prior ordering, which evaluated the confirm flag first (so `confirm=N` returned the
  "Confirm to add this transaction..." prompt and ran no edits, while `confirm=Y` ran the edits). *Rejected:* it
  masked an invalid card or field behind the confirm prompt, inverting the legacy edit-then-confirm sequence.
- **Rationale:** `validateAddCommand` performs the same edits with **no persistence**, so surfacing errors
  before the confirm branch does not weaken the write path — `addTransaction` still re-runs both validators
  defensively before the insert, and `buildCommand` is shared so the validated values equal the persisted values.
- **Risk & mitigation:** Running the edits twice (pre-confirm and inside `addTransaction`) is redundant work.
  *Mitigation:* the edits are pure in-memory checks plus at most one cross-reference read; the defensive re-edit
  guards the service against callers other than the controller. Tests cover invalid-card-with-`confirm=N` →
  immediate key error (not the prompt) and the confirmed valid add (runtime-verified: max+1 id, count +1).

---

### D53 — Menu option numeric-shape check runs in the service, not the transport boundary

- **Status:** Accepted
- **Type:** Behavior preservation (parity fix) + field-contract decision — resolves finding **F-MENU-1**
- **AAP references:** §0.2.2 (BMS field-level contract, PF-key semantics), §0.5.3
- **Decision:** The `MainMenuRequest.option` and `AdminMenuRequest.option` `@Pattern` is a **width-only** guard
  (`^.{0,2}$`). The numeric-shape / range / zeros check is performed by `MenuService`
  (`selectMainMenuOption` / `selectAdminMenuOption`), which re-displays the **same screen** with *"Please enter
  a valid option number..."* at **HTTP 200** for a non-numeric or blank option — reproducing `COMEN01C`
  `PROCESS-ENTER-KEY` (`legacy/cbl/COMEN01C.cbl:L122-L129`: right-scan, `INSPECT REPLACING ' ' BY '0'`,
  `IF WS-OPTION IS NOT NUMERIC OR > count OR = ZEROS`). An **over-length** (>2-char) option remains an
  HTTP 400 structural violation, because a 3270 `OPTIONI` field is `PIC X(2)` and cannot physically hold more.
- **Alternatives:** (a) Keep the strict all-digits `@Pattern` (`^\d{0,2}$`), the pre-fix contract. *Rejected:*
  it rejected a non-numeric or space option with an HTTP 400 at the bean-validation boundary before the service
  ran, whereas the legacy program re-displays the same screen at 200. (b) Drop the `@Pattern` entirely.
  *Rejected:* it loses the width guard that maps the `PIC X(2)` field width.
- **Rationale:** The service already reproduced the legacy same-screen invalid-option behavior faithfully; the
  only defect was the transport-layer pattern intercepting the input first. Relaxing the pattern to a width-only
  guard lets a within-width non-numeric/space option flow to the service (200 same-screen), while the width
  constraint still rejects a structurally impossible over-length value (400).
- **Risk & mitigation:** A reviewer might read the relaxed pattern as weakened validation. *Mitigation:* the
  numeric/range/zeros validation is unchanged — it simply lives in `MenuService` (where the COBOL performs it),
  covered by service unit tests and controller slice tests for both `"5A"` and a space option, and
  runtime-verified on both `POST /api/v1/menu` and `POST /api/v1/admin/menu`.

---

### D54 — Concurrent update maps to HTTP 409 across the concurrency-exception family

- **Status:** Accepted
- **Type:** Robustness / behavior preservation — resolves finding **F-CAUP-3**; extends
  [D18](#d18--jpa-optimistic-locking-version) and [D15](#d15--typed-exception-hierarchy-for-file-status--cics-resp)
- **AAP references:** §0.4.2, §0.7.1 (H6), §0.7.2 (M1)
- **Decision:** `GlobalExceptionHandler` maps `org.springframework.dao.ConcurrencyFailureException` — including
  its subclasses `OptimisticLockingFailureException` and the PostgreSQL-deadlock `CannotAcquireLockException`
  (SQLState `40P01`) — and `jakarta.persistence.OptimisticLockException` to **HTTP 409 Conflict** with an
  RFC-7807 problem body, never HTTP 500.
- **Alternatives:** Enumerate only `OptimisticLockingFailureException` / `OptimisticLockException` (the pre-fix
  handler). *Rejected:* a genuine row-level **deadlock** under concurrency surfaces as `CannotAcquireLockException`,
  which fell through to the generic 500 handler — presenting a client-retryable conflict as a server error.
- **Rationale:** All three are members of the Spring DAO concurrency family; the broad `ConcurrencyFailureException`
  parent covers the whole family with one handler, so any lost-update or deadlock conflict is reported as a
  retryable 409 consistent with the optimistic-locking integrity guarantee of D18. The 409 body leaks no SQL,
  stack, or deadlock detail (only a generic "please retry" message with a correlation id).
- **Risk & mitigation:** The broad parent could, in principle, catch an unrelated `ConcurrencyFailureException`.
  *Mitigation:* in this application the concurrency family is raised only by the versioned read-modify-write
  paths; a dedicated test asserts both an optimistic-lock failure and a `CannotAcquireLockException` map to 409,
  and the fix was runtime-verified by reproducing a live PostgreSQL deadlock (5×409, zero 500).
### D55 — Combine-job global sort is in-memory and buffered, a documented daily-volume ceiling

- **Status:** Accepted
- **Type:** Constraint documented (performance ceiling; no behavior change)
- **AAP references:** §0.4.4 (`TransactionCombineJob` &larr; `COMBTRAN.jcl` SORT), §0.2.2
  (SORT/MERGE &rarr; identical key ordering), §0.9.2 (behavioral parity — content preserved)
- **Decision:** `COMBTRAN.jcl` runs `SORT FIELDS=(TRAN-ID,A)` over the concatenation of the transaction
  backup and the system-generated transactions before the load into the master. The Java equivalent,
  [`CombinedTransactionItemReader`](../src/main/java/com/aws/carddemo/batch/reader/CombinedTransactionItemReader.java),
  reproduces that SORT by reading **both** resources fully into a single in-memory `ArrayList` in
  `open(...)` (`readRecords(backupResource, ...)` then `readRecords(systemResource, ...)`) and then
  sorting the whole list with `items.sort(Comparator.comparing(Transaction::getTranId))` (reader L271) —
  a `java.util.List#sort` call, i.e. **TimSort**, which is stable and therefore keeps backup records
  ahead of equal-`TRAN-ID` system records, the deterministic realization of the legacy SORT. The reader
  is intentionally a custom `ItemStreamReader` rather than a streaming `FlatFileItemReader` **because a
  global sort inherently requires every record to be resident at once**. This buffer-everything-then-sort
  design is accepted for CardDemo's scope: the combined daily volume is small (the seed corpus holds 311
  transactions plus any backup), so the entire working set fits comfortably in heap and the sort is
  effectively instantaneous.
- **The ceiling (why this is documented):** the design's memory footprint and pre-load latency grow
  **linearly with the combined input row count**, because all rows are held in the JVM heap
  simultaneously and sorted in one pass. There is no spill-to-disk fallback. If the combine input were
  scaled by orders of magnitude beyond the demo corpus (e.g. into the millions of daily rows), the reader
  would be bounded by available heap rather than by streaming throughput and could exhaust memory. This
  is a **scaling ceiling**, not a correctness defect — within the migrated CardDemo scope it never
  triggers, and the AAP freezes scope to the existing COBOL capability (no feature expansion, §0.3.3).
- **Alternatives considered:**
  1. *External merge sort (bounded-memory, spill-to-disk).* The classic mainframe `DFSORT`/`SyncSort`
     strategy: sort bounded runs, spill each to a temp file, then k-way merge. **Deferred:** it removes
     the heap ceiling but adds substantial code and temp-file lifecycle management for a volume that
     never approaches the bound; it is the correct answer only if the daily volume is re-platformed to a
     much larger scale.
  2. *Database `ORDER BY` via a `RepositoryItemReader`.* If both the backup and the system-generated
     transactions were already persisted in the `transaction` table, a `RepositoryItemReader<Transaction>`
     over `TransactionRepository` with `findAll` and a `tranId`-ascending sort would let PostgreSQL do the
     ordering (streaming, index- or disk-sort backed, no application heap ceiling). **Deferred:** the
     combine step's inputs are *fixed-width files* (a backup dataset plus a freshly generated file), not
     yet table rows; routing them through the database first would change the step's external file
     contract. This alternative is already noted in the reader's "Alternative: database-backed reader"
     Javadoc as the natural evolution if the inputs become table-resident.
- **Rationale:** the in-memory global sort is the smallest, most transparent, and fully restartable
  realization of a whole-file SORT at the demo's data scale; it preserves the exact `(TRAN-ID, backup-first)`
  ordering the golden-file combine test asserts, and it introduces no temp-file machinery for volumes that
  never need it. The bounded-memory alternatives are documented so the ceiling is a **known, deliberate**
  boundary rather than a hidden assumption.
- **Risk & mitigation:** a future operator who scales the combine input far beyond the CardDemo corpus
  could hit an `OutOfMemoryError` during `open(...)`. *Mitigation:* the ceiling and both bounded-memory
  alternatives are documented here and cross-referenced from the reader's "Buffering rationale" Javadoc,
  so the remediation path (external merge, or database `ORDER BY` once inputs are table-resident) is
  pre-identified; the golden-file combine test guards the ordering contract so any future swap must
  reproduce the same byte output.

---

### D56 — Statement writer uses a substituting ISO-8859-1 encoder (no whole-job abort on unmappable input)

- **Status:** Accepted
- **Type:** Intentional improvement (robustness; no behavioral or layout change on clean data)
- **AAP references:** §0.3.1 / §0.5.4 (statement external file contract — `StatementGenerationJob` &larr;
  `CBSTM03A`/`CBSTM03B`), §0.7.1 / H3 & §0.7.2 / M2 (external fixed-width file contracts preserved),
  §0.9.6 (external file contracts byte/semantically preserved), §0.8.2 (Explainability — this deviation
  is recorded here with rationale, alternatives and risk)
- **Decision:** `batch/writer/StatementItemWriter` opens **both** statement work files — the plain-text
  statement (`STMTFILE`, 80-byte records) and the HTML statement (`HTMLFILE`, 100-byte records) — through a
  `BufferedWriter` wrapping an `OutputStreamWriter` whose ISO-8859-1 `CharsetEncoder` is configured with
  `onUnmappableCharacter(REPLACE)` and `onMalformedInput(REPLACE)` (the private `newSubstitutingWriter`
  helper), mirroring the reject-file encoder established in
  [D35](#d35--atomic-reject-file-publish-with-a-substituting-iso-8859-1-encoder). In addition — and unlike
  D35 — each fully-formatted fixed-width record is passed through a private `toLatin1Record` helper that
  replaces **each** character &gt; `0xFF` individually with `'?'` **before** the record reaches the encoder.
  Owner-only temp-file creation (`rw-------` where POSIX is supported), `TRUNCATE_EXISTING`, and the
  delimiter-less byte framing established in
  [D41](#d41--statement-output-files-carry-no-in-band-delimiter-pure-recfmfb-image) are all preserved.
- **Why the substituting encoder alone is insufficient here (the critical difference from D35):** D35's
  DALYREJS records are **LF-framed** (a trailing `0x0A` after every fixed-length record, per
  [D36](#d36--fixed-width-records-are-lf-framed-and-embedded-delimiters-are-sanitized)), so even if the
  substituting encoder collapsed a surrogate pair into a single replacement byte, the trailing line-feed
  re-synchronises the following record. Statement files are **pure `RECFM=FB` with no in-band delimiter**
  (D41): framing depends entirely on the invariant **1 character = 1 byte**. A supplementary code point
  (for example an emoji, U+1F600) is **two** Java `char`s (a surrogate pair) but the substituting encoder
  emits only **one** replacement byte for the pair — shearing the delimiter-less frame by one byte and
  corrupting every subsequent record. `toLatin1Record` restores the invariant by substituting per
  `char` (a surrogate pair &rarr; `"??"` = two bytes; a BMP CJK ideograph &rarr; one `'?'`), so every
  record stays **exactly** its fixed length. This surrogate-pair-collapses-to-one-byte behavior was
  confirmed empirically before choosing the dual approach.
- **Alternatives:**
  1. *Keep the previous `Files.newBufferedWriter(path, ISO_8859_1)` with default options.* **Rejected:**
     the JDK-default reporting encoder throws `UnmappableCharacterException` on the first character
     &gt; `0xFF`. QA reproduced a single multibyte character in a transaction description aborting the
     **entire** `StatementGenerationJob` with return code&nbsp;8 and producing **zero output for every
     account** (60+ statements denied because of one field) — a MAJOR availability failure.
  2. *Use only the substituting encoder (exactly as D35 does), without `toLatin1Record`.* **Rejected for
     the statement writer:** correct for the LF-framed DALYREJS file, but insufficient for the
     delimiter-less statement image — a supplementary code point's surrogate pair collapses to a single
     byte and shears the fixed frame, so the encoder-only fix trades a hard abort for silent record
     corruption. The per-character pre-substitution is required precisely because there is no delimiter to
     re-synchronise on.
  3. *Catch the encoding exception and skip the offending account.* **Rejected:** silently dropping an
     account's statement breaks the row-for-row statement parity and hides data; substitution preserves the
     record and its 80/100-byte framing while flagging the anomaly with the charset replacement character.
  4. *Switch the statement files to UTF-8.* **Rejected:** the statement contract is a fixed
     one-byte-per-position ISO-8859-1 image (`FD-STMTFILE-REC PIC X(80)` / `FD-HTMLFILE-REC PIC X(100)`);
     UTF-8 would make a multi-byte character break column alignment, the fixed record length, and the
     pinned `GOLDEN_TEXT_SHA256` / `GOLDEN_HTML_SHA256`.
- **Relationship to [D44](#d44--statement-html-fields-are-html-escaped-per-field-parity-preserved-for-metacharacter-free-data):**
  D44 hardens the **processor** — the HTML path's data values are HTML-escaped (every code point &ge; `0x80`
  becomes a numeric character reference), so the HTML byte stream is already pure ASCII before it reaches the
  writer. D56 hardens the **writer**: it protects the **plain-text** statement (which is deliberately *not*
  HTML-escaped, being COBOL-faithful text) and provides defense-in-depth for any code point &gt; `0xFF` on
  either stream. Together the two decisions make both statement artifacts robust to unmappable input with no
  parity loss.
- **Rationale:** For faithfully decoded CardDemo data every character is already representable in
  ISO-8859-1, so neither the encoder substitution nor `toLatin1Record` ever fires and the published bytes —
  text and HTML — are byte-identical to before (a fast path in `toLatin1Record` returns the record
  unchanged when it contains no character &gt; `0xFF`, with no allocation). The golden text and HTML
  fixtures and their pinned SHA-256 hashes are unchanged. The change purely removes a whole-job abort
  failure mode required by the availability of the batch: one unmappable character in one account's data no
  longer denies statements to all accounts.
- **Risk & mitigation:** the replacement character (`?`) is indistinguishable from a literal `?` in the
  data. *Mitigation:* this can only occur for a code point &gt; `0xFF`, which cannot arise from correctly
  decoded CardDemo staging/transaction data; the substitution is documented here and covered by two tests —
  a writer unit test (`StatementItemWriterTest.substitutesUnmappableCharactersWithoutAbortingAndPreservesFraming`)
  injects a supplementary emoji **and** a CJK ideograph and asserts the write does not abort, each file stays
  an exact multiple of its fixed record width (the surrogate pair yields **two** `?` bytes), and the
  substitution bytes land at the expected offsets; a job-level test
  (`StatementGenerationJobTest.jobCompletesWhenTransactionDescriptionHasUnmappableCharacters`) seeds an
  emoji/CJK transaction description and asserts the job reaches `COMPLETED` with non-empty output and intact
  framing on both files. The golden-parity tests assert that clean-data bytes are unchanged, so the
  robustness hardening is proven to be a no-op on the faithful path.

---

## Related documents

- [Architecture overview](./architecture.md) — the layered target architecture these decisions realize.
- [Traceability matrix](./traceability-matrix.md) — the bidirectional, per-paragraph mapping from every
  COBOL construct to its Java target; each mapped field/paragraph corresponds to the design choices
  explained above.

