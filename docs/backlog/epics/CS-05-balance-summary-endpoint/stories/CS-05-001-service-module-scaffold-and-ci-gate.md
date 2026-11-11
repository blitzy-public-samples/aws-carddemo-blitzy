# CS-05-001: Service module scaffold and CI gate

Parent epic: [CS-05 Balance Summary Endpoint](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a delivery engineer, I want a buildable two-module service with exact version pins and a pull-request CI gate, so that every later story builds, tests and validates the same way.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Directive: module and file organization
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: a two-module Gradle 9.1.0 build on a Java 25 toolchain that pins every version once in a version catalog, generates Java from both proto roots inside the service module, runs four test tasks under a per-module coverage rule, builds a runtime image and gates every pull request through one ordered CI workflow. Target paths:
>
> - `settings.gradle` includes exactly the modules `card-servicing-service` and `card-servicing-contracts`.
> - `gradle/libs.versions.toml` carries these exact GA pins:
>   - Spring Boot 3.5.7, plugin and BOM; the BOM manages JUnit 5.
>   - `io.grpc:grpc-netty-shaded`, `io.grpc:grpc-protobuf`, `io.grpc:grpc-stub` and `io.grpc:protoc-gen-grpc-java`, all 1.71.0.
>   - `com.google.protobuf:protobuf-java` and `com.google.protobuf:protoc`, both 3.25.5.
>   - The `com.google.protobuf` Gradle plugin 0.9.5.
>   - `org.springdoc:springdoc-openapi-starter-webmvc-ui` 2.8.13.
>   - `com.azure.spring:spring-cloud-azure-dependencies` 5.23.0.
>   - JaCoCo 0.8.14.
>   - Testcontainers `testcontainers`, `postgresql` and `junit-jupiter`, all 1.21.3.
> - `gradle/wrapper/gradle-wrapper.properties` and `gradle/wrapper/gradle-wrapper.jar` pin Gradle 9.1.0, the final release and not an `rc` build; `gradlew` and `gradlew.bat` at the repository root run it.
> - `.gitignore` excludes `build/`, `.gradle/` and generated sources.
> - `card-servicing-service/build.gradle`:
>   - Java toolchain `languageVersion = 25`.
>   - A protobuf source set over both proto roots, `card-servicing-contracts/src/main/proto` and `card-servicing-service/src/main/proto`, compiled by `protoc` 3.25.5 with the `grpc` plugin `protoc-gen-grpc-java` 1.71.0.
>   - For every generate task, `grpc { option '@generated=omit' }`, because protoc-gen-grpc-java 1.71.0 otherwise annotates generated stubs with `javax.annotation.Generated`, which JDK 25 does not include.
>   - Generated Java lands under the build directory in `com.example.cardservicing.contract.<service>.v1`, for example `com.example.cardservicing.contract.common.v1` and `com.example.cardservicing.contract.api.v1`, and is never checked in.
>   - Test tasks:
>     - `test` runs JUnit 5 and excludes the `integration` and `e2e` tags.
>     - `integrationTest` runs only the `integration` tag.
>     - `e2eTest` runs only the `e2e` tag.
>     - `performanceTest` is registered for the harness that CS-01-003 adds, and completes successfully with zero tests until then.
>   - `jacocoTestCoverageVerification` fails below 0.80 line coverage per module.
> - `card-servicing-contracts/build.gradle` declares the contracts module, which holds the shared proto tree `card-servicing-contracts/src/main/proto` and generates no Java, so the shared types are generated once, by the service module.
> - `card-servicing-service/Dockerfile` builds a runtime image on `eclipse-temurin:25-jre` that starts the service.
> - `card-servicing-service/src/main/java/com/example/cardservicing/CardServicingApplication.java` is the Spring Boot entry class.
> - Tests:
>   - `card-servicing-service/src/test/java/com/example/cardservicing/CardServicingApplicationTest.java`, a context-start smoke test.
>   - `card-servicing-service/src/test/java/com/example/cardservicing/TaggedSuiteProbeTest.java`, holding one untagged probe test, one probe test tagged `integration` and one tagged `e2e`, which proves the tag exclusion.
> - `.github/workflows/ci.yml`:
>   - Runs on every pull request.
>   - Reserves stage slots in the fixed order Unit → Integration → Performance → End-to-end → Validation, each stage needing the one before it.
>   - Tool setup: Temurin JDK 25, Node.js 22 (22.23.4, the `engines` pin in `tests/validation/package.json`) and Docker Compose 2.29.7.
>   - This story adds the Unit stage (`./gradlew :card-servicing-service:test jacocoTestCoverageVerification`) and the Validation stage (`tests/validation/run-all.sh`). Validation needs Unit until later stories fill the reserved slots.
> - Together these are the 14 files touched.
>
> In scope:
>
> - The 14 files above.
> - The version pins.
> - The four test tasks and the coverage rule.
> - The Unit and Validation CI stages.
> - The first-build compatibility confirmation of Spring Cloud Azure 5.23.0 with Spring Boot 3.5.7.
>
> Not in this story:
>
> - `card-servicing-service/src/main/resources/application.yml` and the 14 environment variables (CS-11-001).
> - The standard error object and status mapping (CS-05-002).
> - The Integration stage (CS-11-007), the Performance stage (CS-01-003) and the End-to-end stage (CS-01-002).
> - Spring Data JPA and the PostgreSQL JDBC driver (CS-09-001).
> - The Key Vault starter (CS-12-004).
> - Any endpoint, stub or client code.
>
> ### 2. Acceptance Criteria
>
> - AC1: On a clean checkout, `./gradlew :card-servicing-service:compileJava` compiles every generated message and stub class from both proto roots with no annotation library on the classpath, and no generated source contains `javax.annotation.Generated`.
> - AC2: The same first build resolves `spring-cloud-azure-dependencies` 5.23.0 with Spring Boot 3.5.7, and the context-start smoke test passes. The outcome is recorded in section 7. A resolution or startup failure fails the build.
> - AC3: `gradle/libs.versions.toml` contains zero versions carrying `+`, `latest`, a range bracket, or a `SNAPSHOT`, `rc` or milestone qualifier, and every version equals the pin listed in section 1.
> - AC4: `./gradlew --version` reports Gradle 9.1.0, and compiled main classes carry class-file major version 69 (Java 25).
> - AC5: `./gradlew :card-servicing-service:test` runs zero tests tagged `integration` or `e2e`. The tagged probe tests run only under `integrationTest` and `e2eTest` respectively, and `performanceTest` is registered and completes successfully with zero tests until CS-01-003 adds them.
> - AC6: `jacocoTestCoverageVerification` fails when any module's line coverage is below 0.80, and passes at exactly 0.80 and above.
> - AC7: `.github/workflows/ci.yml` runs on every pull request, with Unit first and Validation needing Unit. A failing Unit stage fails the gate and skips Validation. A non-zero exit of `tests/validation/run-all.sh` fails the gate.
> - AC8: The workflow's tool-setup steps install Temurin JDK feature release 25, Node.js 22.23.4 and Docker Compose 2.29.7, and each tool's version output in the job log matches its pin.
> - AC9: `docker build -f card-servicing-service/Dockerfile .` succeeds with base image `eclipse-temurin:25-jre`, and `docker run` of the image logs the Spring Boot `Started CardServicingApplication` line.
> - AC10: `settings.gradle` includes exactly the two modules. After a full build, `git status --porcelain` lists no generated or build-output file.
>
> ### 3. Dependencies
>
> - None — first story in build order
>
> Risk: Spring Cloud Azure 5.23.0 is recorded upstream with Spring Boot 3.5.7 only as a compatibility run, so the first build may need a version substitution under the request's substitution rule.

## 4. Design

- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Data model — entity relationship diagram](../../../../data-model.md#entity-relationship-diagram)
- [TRD — component catalogue](../../../../technical-requirements.md#component-catalogue)
- [TRD — D3 stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point)
- [TRD — test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- [Shared error contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)
- [Card Servicing gRPC contract](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Card Servicing OpenAPI contract](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is ≥80% per module. Cases: the context-start smoke test, the tagged-probe exclusion and the coverage-rule boundary.
- Integration: Not applicable — this story adds no external client, and the Integration stage arrives with CS-11-007. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — no endpoint exists yet. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50/p95/p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no endpoint exists yet. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — one two-module build with a shared version catalog and one ordered pull-request workflow.

Rationale: one build reading one pin list, gated by one workflow with a fixed stage order, gives every later story the same build, test and validation path.

- Rejected: Option B, separate builds and workflows per module — two version sources and no enforced stage order.
- Rejected: generating the shared types in the contracts module and publishing them — adds an artifact store this iteration lacks.

### Shared by both options

- Topology: two modules, service and contracts, on one build tool release and one language release.
- Naming: module names equal folder names, and generated types sit in one namespace per service and version.
- Addressing: contract trees are read from the repository, never from a remote registry.
- Isolation: generated sources stay in build output and are never committed.
- Fallback: a version that fails to resolve is substituted only under the request's substitution rule and recorded in Recommendations.
- Provisioning: the pull-request runner installs the language runtime, the validation tool runtime and the container orchestration tool at pinned releases.
- Cut-over: later stories add their test stages to the gate without changing the stages already there.
- Failures: any failing stage fails the gate, and a version that fails to resolve fails the build.

### Design Option A — single build, shared catalog, ordered workflow

One build holds the service and contracts modules, reads every version from one shared catalog, and generates code from both contract trees inside the service module. One pull-request workflow runs its stages in a fixed order, each stage waiting for the one before it. This story fills the unit and validation stages and leaves the integration, performance and end-to-end slots to the stories that own them.

How it behaves:

- One build command compiles both contract trees, the service and its tests.
- The service module generates message and remote procedure types for the shared and service contracts, with the generated-annotation marker switched off.
- The default test run skips tests marked integration or end-to-end, and three further test runs select the integration mark, the end-to-end mark and the performance harness.
- The coverage rule fails the unit stage when any module covers less than 80 percent of its lines.
- The runtime image starts the service on the release 25 language runtime base.
- The workflow installs the three pinned tools before any stage runs, and a failing stage stops every later stage.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One build with two modules (vs two independent builds) | one command builds and tests everything | a contract change rebuilds the service |
| 2 | One shared version catalog (vs pins in each build file) | every pin lives in one place | every module reads one shared list |
| 3 | Service module generates code from both contract trees (vs contracts module generating and publishing) | shared types are generated once without publishing | contracts cannot ship alone |
| 4 | One workflow with dependent stages (vs one workflow per suite) | a failing stage stops all later stages | one long pull-request run |

Effort and risk: one story of build configuration, with the risk in the cloud library pairing that the first build settles.

### Design Option B — separate builds and workflows per module

The service and the contracts each carry their own build, their own version pins and their own pull-request workflow. The contracts build generates the shared types and publishes them as a versioned artifact that the service build reads. Each workflow passes or fails on its own.

How it differs:

- Each module pins its own versions, so one library can carry two releases.
- The contracts build publishes generated types, so the service build needs an artifact store to read them.
- Each workflow runs on its own, so a failing unit stage does not stop the validation run.
- A contract change reaches the service only after a new contracts release.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Two independent builds (vs one build) | modules release separately | two builds to keep aligned |
| 2 | Pins in each build (vs shared catalog) | each module owns its pins | pins drift between modules |
| 3 | Contracts module publishes generated types (vs generation in the service) | contracts are reusable alone | needs an artifact store |
| 4 | One workflow per module (vs one ordered workflow) | smaller runs | no enforced order across workflows |

Effort and risk: two build configurations and an artifact store, with the risk in drifting pins and stages that run out of order.

### Recommendation

- Option: Option A.
- Why: one pin source and one enforced stage order meet the exact-pin and ordered-gate criteria.
- Strongest argument against: one build couples the contracts release to the service release.
- Shared items that do not decide the choice: toolchain release, annotation omission, tagged-suite exclusion, coverage rule and runtime base image.
- Flip condition: the contracts must ship to other teams before the real services replace the stubs.

## Design Validation

### Validation of Option A

Settled assumptions:

- Build tool 9.1.0 runs on and targets language release 25.
- Remote procedure library 1.71.0 aligns with message library 3.25.5.
- The generator option removes the generated-annotation dependency.
- The framework release 3.5.7 recognises language release 25.

Open assumptions:

- Open → cloud library 5.23.0 runs with framework 3.5.7, settled by this story's first build.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the service module generates both trees without the annotation marker |
| AC2 | Conditional | the cloud library pairing awaits the first build |
| AC3 | Holds | one catalog holds every pin |
| AC4 | Holds | the wrapper and the toolchain fix both releases |
| AC5 | Holds | each test run selects only its own mark |
| AC6 | Holds | one 80 percent line threshold covers every module |
| AC7 | Holds | the validation stage waits on the unit stage in one workflow |
| AC8 | Holds | one workflow installs all three tools at pinned releases |
| AC9 | Holds | the image starts from the release 25 runtime base |
| AC10 | Holds | generated sources stay in ignored build output |

### Validation of Option B

Settled assumptions:

- Build tool 9.1.0 runs on and targets language release 25.
- Remote procedure library 1.71.0 aligns with message library 3.25.5.
- The generator option removes the generated-annotation dependency.
- The framework release 3.5.7 recognises language release 25.

Open assumptions:

- Open → cloud library 5.23.0 runs with framework 3.5.7, settled by the first service build.
- Open → an artifact store for the published contract types, settled by a platform decision this iteration does not make.

| AC | Verdict | Why |
|---|---|---|
| AC1 | At risk | shared types are generated in two places |
| AC2 | Conditional | the cloud library pairing awaits the first build |
| AC3 | At risk | pins can drift across the two builds |
| AC4 | Holds | each build carries the same wrapper and toolchain releases |
| AC5 | Holds | the service build keeps the same four test runs |
| AC6 | Holds | each build applies the same 80 percent line threshold |
| AC7 | Breaks | no order is enforced across workflows |
| AC8 | Holds | each workflow installs the pinned tools |
| AC9 | Holds | the service build produces the same runtime image |
| AC10 | Conditional | both modules share one module list only through a joining root build |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | None |
| Status | TBD |
| Estimated Files Touched | 14 |
| Issue Type | Story |
| Epic Name | Balance Summary Endpoint |
| Epic Link | CS-05 |
| Story Points | 5 |
| Priority | High |
| Components | TBD |
| Fix Version/s | TBD |
| Team | TBD |
| Assignee | TBD |
| Reporter | TBD |
| Labels | TBD |
| Sprint | TBD |
| Due Date | TBD |
| Environment | TBD |
| Persona | TBD |
| Estimated Effort | TBD |
