# CS-11-001: Service configuration properties and environment variables

Parent epic: [CS-11 Config Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As an operations engineer, I want every runtime setting bound from one typed configuration with safe defaults, so that each environment differs only by variables.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Directive: environment variables
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Anchor basis: Use Cases · COTRN00C fills a fixed ten-row page set inside the program (PROCESS-PAGE-FORWARD); that screen behaviour is what this epic turns into configured page size and call settings. This story binds the call settings, and the other runtime settings that later stories read, from the environment.
>
> Capability: one typed configuration-properties class binds the 14 variables of the TRD environment-variable table, each through a placeholder with a packaged default, checks every value before application startup completes, and masks the three secrets in its string form. A unit test scans the committed files for credential values. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/CardServicingProperties.java`:
>   - One `@ConfigurationProperties` class with prefix `card-servicing` and six nested groups: `auth`, `redis`, `db`, `azure-key-vault`, `upstream` and `stub`.
>   - It registers itself with the application context from this file, so `card-servicing-service/src/main/java/com/example/cardservicing/CardServicingApplication.java` from CS-05-001 is not edited.
>   - It checks the startup rules below itself, so `card-servicing-service/build.gradle` gains no validation library. Each failure message names the full property and its rule and never carries a secret value.
>   - Its string form shows `****` in place of each non-empty secret (`card-servicing.auth.api-key`, `card-servicing.redis.password`, `card-servicing.db.password`) and an empty value for an empty one.
> - `card-servicing-service/src/main/resources/application.yml` declares each of the 14 bound properties as `${VARIABLE:default}` with the default in the table below.
> - `card-servicing-service/src/test/java/com/example/cardservicing/config/CardServicingPropertiesTest.java` holds the default, override, conversion-failure, bound and masking tests. It starts an application context that loads `application.yml` and supplies each variable as a property value.
> - `card-servicing-service/src/test/java/com/example/cardservicing/config/CommittedSecretScanTest.java` holds the committed-secret scan and its planted-file case.
> - Together these are the 4 files touched.
>
> | Variable | Bound property | Default |
> |---|---|---|
> | `AUTH_SERVICE_API_KEY` | `card-servicing.auth.api-key` | empty |
> | `REDIS_HOST` | `card-servicing.redis.host` | `localhost`, the local Redis container host |
> | `REDIS_PORT` | `card-servicing.redis.port` | `6379` |
> | `REDIS_PASSWORD` | `card-servicing.redis.password` | empty |
> | `REDIS_SSL_ENABLED` | `card-servicing.redis.ssl-enabled` | `false` |
> | `DB_URL` | `card-servicing.db.url` | `jdbc:postgresql://localhost:5432/cardservicing` |
> | `DB_USERNAME` | `card-servicing.db.username` | `cardservicing` |
> | `DB_PASSWORD` | `card-servicing.db.password` | `local-throwaway`, a throwaway local-container value |
> | `AZURE_KEYVAULT_URI` | `card-servicing.azure-key-vault.uri` | empty, which leaves the Key Vault property source disabled |
> | `UPSTREAM_TIMEOUT_MS` | `card-servicing.upstream.timeout-ms` | `300` |
> | `UPSTREAM_MAX_RETRIES` | `card-servicing.upstream.max-retries` | `2` |
> | `REQUEST_DEADLINE_MS` | `card-servicing.upstream.request-deadline-ms` | `900` |
> | `STUB_LATENCY_MS` | `card-servicing.stub.latency-ms` | `0` |
> | `STUB_FAILURE_RATE` | `card-servicing.stub.failure-rate` | `0` |
>
> Deployed Azure Managed Redis uses port `10000` with `REDIS_SSL_ENABLED=true`. Both are set by the environment and are never defaults. `UPSTREAM_TIMEOUT_MS` and `UPSTREAM_MAX_RETRIES` are the built-in values the call policy uses until a configuration snapshot is held. `REQUEST_DEADLINE_MS` is never a Config key.
>
> Startup rules, each failing startup with a message naming its property:
>
> - `card-servicing.upstream.timeout-ms` and `card-servicing.upstream.request-deadline-ms` are at least 1.
> - `card-servicing.upstream.max-retries` and `card-servicing.stub.latency-ms` are at least 0.
> - `card-servicing.stub.failure-rate` lies from 0 to 1 inclusive.
> - `card-servicing.redis.port` lies from 1 to 65535.
> - Every number and the TLS flag converts from its variable, and the Spring Boot conversion failure names the property.
>
> Committed-secret scan: `CommittedSecretScanTest` lists the committed files with `git ls-files` from the repository root and fails on any credential value, naming the file and line. A credential value is a non-empty value assigned to `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD`, `DB_PASSWORD` or their bound properties in `NAME=value`, `NAME: value` or `${NAME:value}` form, a private-key block, or a key in the published shape of an AWS, GitHub, Stripe, Slack or Google credential. An environment placeholder counts by its default. The only allowed values are the empty value and the throwaway local-container default `local-throwaway`. The test builds its planted value at runtime, so the test file commits no literal credential.
>
> In scope:
>
> - The 4 files above.
> - Binding of all 14 variables with their defaults.
> - The startup rules and secret masking.
> - The committed-secret scan.
>
> Not in this story:
>
> - The Key Vault property source and its starter (CS-12-004).
> - Config-supplied values and the configuration snapshot that replaces the timeout and retry defaults (CS-11-006).
> - The Redis connection configuration that reads the cache settings (CS-11-004).
> - Stub use of `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` (CS-11-002).
> - Call-policy and deadline use of `UPSTREAM_TIMEOUT_MS`, `UPSTREAM_MAX_RETRIES` and `REQUEST_DEADLINE_MS` (CS-11-003).
> - The store connection that the stubs and the fixture loader open with the `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` settings (CS-09-001).
> - Sending the service credential as `x-api-key` (CS-12-002).
> - The values set in `tests/docker-compose.yml` (CS-01-001).
> - Upstream target addresses, which each client story binds for its own client (CS-09-005, CS-10-006, CS-11-006, CS-12-002, CS-13-003).
>
> ### 2. Acceptance Criteria
>
> - AC1: With none of the 14 variables set, the application context starts and each of the 14 bound properties equals its default in the table in section 1. The test asserts each property by name.
> - AC2: `UPSTREAM_TIMEOUT_MS=250`, `REDIS_PORT=10000` and `REDIS_SSL_ENABLED=true`, each set alone, bind 250, 10000 and true to their own property, and the other 13 properties keep their defaults in each case.
> - AC3: `UPSTREAM_TIMEOUT_MS=abc` and `REDIS_SSL_ENABLED=maybe` each fail application startup with a failure message naming `card-servicing.upstream.timeout-ms` and `card-servicing.redis.ssl-enabled` respectively, and the context does not start.
> - AC4: `STUB_FAILURE_RATE=1.5` and `STUB_FAILURE_RATE=-0.1` each fail startup with a message naming `card-servicing.stub.failure-rate`. `STUB_FAILURE_RATE=0` and `STUB_FAILURE_RATE=1` each start the context with that value bound.
> - AC5: `UPSTREAM_TIMEOUT_MS=0`, `REQUEST_DEADLINE_MS=0`, `UPSTREAM_MAX_RETRIES=-1`, `STUB_LATENCY_MS=-1`, `REDIS_PORT=0` and `REDIS_PORT=65536` each fail startup with a message naming its property. `UPSTREAM_TIMEOUT_MS=1`, `REQUEST_DEADLINE_MS=1`, `UPSTREAM_MAX_RETRIES=0`, `STUB_LATENCY_MS=0`, `REDIS_PORT=1` and `REDIS_PORT=65535` each start the context with that value bound.
> - AC6: With `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD` and `DB_PASSWORD` each set to a distinct non-empty test value, the string form of the bound properties shows `****` for each of the three. Neither that string form nor the startup failure output caused by adding `UPSTREAM_TIMEOUT_MS=abc` contains any of the three values.
> - AC7: The scan over the files `git ls-files` lists finds zero credential values as section 1 defines them. The same scan over a temporary directory holding one planted file that assigns a non-empty `AUTH_SERVICE_API_KEY` value fails and names that file.
>
> ### 3. Dependencies
>
> - CS-05-001 — build, version catalog and the test task this binding runs in.
>
> Risk: a default that differs from the TRD environment-variable table or from the values set in `tests/docker-compose.yml` makes local and CI runs behave differently.

## 4. Design

- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Data model — entity relationship diagram](../../../../data-model.md#entity-relationship-diagram)
- [TRD — environment variables](../../../../technical-requirements.md#environment-variables)
- [TRD — configuration snapshot](../../../../technical-requirements.md#configuration-snapshot)
- [TRD — mock-to-real swap and credential management](../../../../technical-requirements.md#mock-to-real-swap-and-credential-management)
- [TRD — D3 stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point)

Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: the 14 defaults, single-variable overrides, conversion failures, the startup-rule boundaries, secret masking, and the committed-secret scan with its planted-file case.
- Integration: Not applicable — no upstream call. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — no request path; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no UI interaction step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — one typed settings record with nested groups.

Rationale: one typed record bound once at startup gives every consumer the same default, the same check and the same mask for each setting.

- Rejected: Option B, separate single-value injection at each consumer, because defaults and validation scatter across every consumer.

Shared by both options:

- Topology: one settings surface read by the service and its stubs at startup.
- Naming: one environment variable per setting, with the 14 names fixed by the request.
- Addressing: values reach the service only through environment variables and the packaged defaults file.
- Isolation: secret values never appear in startup output, failure messages or logs.
- Fallback: the packaged defaults let a local run start with no variable set.
- Provisioning: deployed values come from the environment, and the secret store source arrives with the key vault story.
- Cut-over: the real-service swap changes only address and credential values, never the shape of the settings.
- Failures: an invalid value stops startup with a message naming the setting.

### Design Option A

One typed settings record holds six nested groups: the service key setting, the cache connection settings, the store connection settings, the secret store address, the upstream call settings and the stub failure-injection settings. The framework binds every environment variable into it once at startup through the packaged defaults file, and the record checks each value before startup completes. Consumers receive the group they need and never read the environment themselves.

How it behaves:

- With no variable set, every setting takes its packaged default.
- A set variable replaces only its own setting.
- A value that does not convert to a number or a true-or-false flag stops startup.
- A failure-rate setting outside zero to one, a negative retry cap or latency setting, a timeout or deadline setting below one millisecond, or a cache port outside the valid port range stops startup.
- The printed form of the record shows a fixed mask in place of each set secret.
- A repository scan fails the unit stage when a committed file carries a credential value.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One record with nested groups (vs one value per consumer) | every default lives in one place | every new group edits one shared record |
| 2 | Checks once at startup (vs checks at first use) | a bad value never reaches a request | startup fails for a setting a run never uses |
| 3 | Mask in the printed form of the record (vs masking at each log line) | one rule covers every secret | a new secret must join the masked set |

Effort and risk: one settings record, one defaults file and two test classes, with the risk in a default that drifts from the environment-variable table.

### Design Option B

Each consumer reads its own setting with its own default at the point of use. The cache client, the store connection, the call policy and the stubs each declare the settings they need. No shared record exists, so startup checks only what the framework converts when each consumer is created.

How it differs:

- Each default sits beside its consumer, so a setting read in two places can carry two defaults.
- Each consumer checks its own value, or nothing checks it.
- Each consumer masks its own secret in every message it writes.
- A value read only on first use fails during a request rather than at startup.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One value per consumer (vs one shared record) | each consumer owns its settings | defaults repeat across consumers |
| 2 | Checks at first use (vs once at startup) | no shared checking code | a bad value surfaces during a request |
| 3 | Masking at each log line (vs in the printed record) | no shared printed form | one missed line leaks a secret |

Effort and risk: no shared record but one default and one check per consumer, with the risk in drifting defaults and a missed mask.

### Recommendation

- Option: Option A.
- Why: one record gives each setting one default, one check and one mask, which meets the default, startup-rule and masking criteria directly.
- Strongest argument against: one shared record grows with every new group, and every later story that adds a setting edits it.
- Shared items that do not decide the choice: the 14 variable names, the packaged defaults file, the repository scan and the secret store source.
- Flip condition: the stubs move into separate deployable services that each need only their own settings.

## Design Validation

### Validation of Option A

Settled assumptions:

- The environment-variable table fixes all 14 names, their purposes and their stub-phase values.
- The deadline setting is never supplied by the configuration service.
- Deployed cache values, port 10000 with TLS on, come from the environment and never from defaults.
- The record performs its own checks, so the build gains no validation library.

Open assumptions:

- Open → the Compose stack story settles that its local container host and store values match the packaged defaults.
- Open → the key vault story settles that secret store values bind into the same record.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | one defaults file declares all 14 settings |
| AC2 | Holds | each variable fills only its own setting |
| AC3 | Holds | the framework names each setting it cannot convert |
| AC4 | Holds | the record checks the failure-rate range at startup |
| AC5 | Holds | the record checks every lower bound and the port range at startup |
| AC6 | Holds | the printed record masks every set secret |
| AC7 | Holds | the scan reads every committed file against one allow-list |

### Validation of Option B

Settled assumptions:

- The environment-variable table fixes all 14 names, their purposes and their stub-phase values.
- The deadline setting is never supplied by the configuration service.
- Deployed cache values, port 10000 with TLS on, come from the environment and never from defaults.

Open assumptions:

- Open → the Compose stack story settles that its values match the default each consumer repeats.
- Open → each consumer story settles where its own checks run.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | the 14 defaults agree only while every consumer repeats the same value |
| AC2 | Holds | each consumer reads its own variable |
| AC3 | At risk | a value read on first use fails during a request instead of at startup |
| AC4 | At risk | the stub must check its own failure-rate range |
| AC5 | At risk | each consumer must check its own bounds |
| AC6 | At risk | each secret is masked separately at each log line |
| AC7 | Holds | the scan does not depend on how settings are read |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-05-001 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Config Service Integration |
| Epic Link | CS-11 |
| Story Points | 2 |
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
