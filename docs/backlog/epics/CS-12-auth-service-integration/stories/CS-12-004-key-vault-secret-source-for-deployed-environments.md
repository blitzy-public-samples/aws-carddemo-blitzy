# CS-12-004: Key Vault secret source for deployed environments

Parent epic: [CS-12 Auth Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As an operations engineer, I want deployed secrets read from Key Vault, so that no credential is stored in the repository or image.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Directive: secret management
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Anchor basis: Use Cases · COACTVWC is the account view that this epic's access control protects. This story supplies the service credential that the Auth calls on that path carry, together with the cache and database passwords, from Key Vault when deployed.
>
> Capability: the Spring Cloud Azure Key Vault secret property source, active only when `AZURE_KEYVAULT_URI` is set, supplies `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD` and `DB_PASSWORD` from vault secrets in deployed environments, so the typed configuration of CS-11-001 binds them with no change. The service, the stubs and the fixture loader all run from the `card-servicing-service` module, so one property-source declaration serves every component that the TRD environment-variable table lists as reading a secret. Target paths:
>
> - `card-servicing-service/build.gradle` adds `com.azure.spring:spring-cloud-azure-starter-keyvault-secrets` with no version literal. Its version comes from the `com.azure.spring:spring-cloud-azure-dependencies` 5.23.0 BOM that CS-05-001 pins in `gradle/libs.versions.toml`.
> - `card-servicing-service/src/main/resources/application.yml`:
>   - Declares one property source as `spring.cloud.azure.keyvault.secret.property-sources[0]`, with endpoint `${AZURE_KEYVAULT_URI:}` and its secret-key list limited to the three secrets below.
>   - Spring Cloud Azure 5.23.0 skips a property source whose endpoint is empty, so an unset `AZURE_KEYVAULT_URI` keeps the source off with no separate flag. The first startup test (AC1) confirms this switch against 5.23.0, and any change it needs stays inside this file.
>   - The vault holds one secret per credential: `auth-service-api-key`, `redis-password` and `db-password`, because vault secret names allow only letters, digits and hyphens. The property source matches each placeholder name, for example `DB_PASSWORD`, to its hyphenated secret name, which the second startup test (AC2) confirms.
>   - The packaged defaults of CS-11-001 stay as they are: empty for `AUTH_SERVICE_API_KEY` and `REDIS_PASSWORD`, and the throwaway local-container value `local-throwaway` for `DB_PASSWORD`.
> - `card-servicing-service/src/test/java/com/example/cardservicing/config/KeyVaultSecretSourceTest.java` starts the application context three ways: vault address unset or empty, set to a vault test double that serves the three secrets over HTTPS, and set to an address where no vault answers. The test builds the double's secret values at runtime. The test-only framework settings that let the vault client accept the double stay inside this test.
> - Together these are the 3 files touched.
>
> Rules:
>
> - Unset or empty `AZURE_KEYVAULT_URI`: no Key Vault property source is created and no Azure call is made.
> - Set `AZURE_KEYVAULT_URI`: a vault that cannot be read fails startup, and no credential falls back to its packaged default.
> - Environment values take precedence over the Key Vault property source, so a deployed environment leaves `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD` and `DB_PASSWORD` unset and sets only `AZURE_KEYVAULT_URI`.
> - Vault authentication uses the framework's default Azure credential chain, so no vault credential is stored in the repository or image.
> - Secret values are never logged, at any log level.
> - Local and Compose values stay empty placeholders or the throwaway local-container value.
>
> In scope:
>
> - The 3 files above.
> - The build dependency.
> - The property-source configuration.
> - The unit suite.
>
> Not in this story:
>
> - Provisioning the vault, its secrets or the workload identity's read access in Azure.
> - AKS deployment manifests.
> - Secret rotation.
> - The Auth client's use of the service key as `x-api-key` (CS-12-002).
> - The typed configuration, its packaged defaults and the committed-secret scan (CS-11-001).
>
> ### 2. Acceptance Criteria
>
> - AC1: With `AZURE_KEYVAULT_URI` unset, and again with it set to the empty string, the application context starts, its environment holds no Key Vault property source, and the context holds no Key Vault secret client, so no request reaches any vault. `card-servicing.auth.api-key` and `card-servicing.redis.password` bind empty and `card-servicing.db.password` binds `local-throwaway`, the packaged defaults of CS-11-001.
> - AC2: With `AZURE_KEYVAULT_URI` set to the HTTPS address of a vault test double that serves the secrets `auth-service-api-key`, `redis-password` and `db-password`, and with `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD` and `DB_PASSWORD` unset, the application context starts and `card-servicing.auth.api-key`, `card-servicing.redis.password` and `card-servicing.db.password` hold the three values the double serves.
> - AC3: With `AZURE_KEYVAULT_URI` set to `https://cs-unreachable-vault.invalid/`, application startup fails, the startup failure output names the host `cs-unreachable-vault.invalid`, and the context does not start, so no credential binds to its packaged default.
> - AC4: `card-servicing-service/build.gradle` declares `com.azure.spring:spring-cloud-azure-starter-keyvault-secrets` with no version literal, and `./gradlew :card-servicing-service:dependencyInsight --dependency spring-cloud-azure-starter-keyvault-secrets --configuration runtimeClasspath` reports 5.23.0, the version the `spring-cloud-azure-dependencies` 5.23.0 BOM manages.
> - AC5: The committed-secret scan of CS-11-001 passes with this story's 3 files committed. `application.yml` gains no non-empty value for `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD` or `DB_PASSWORD` beyond the existing `local-throwaway` default, and `KeyVaultSecretSourceTest` commits no literal credential.
> - AC6: With the root log level set to DEBUG, the log output captured during the AC2 startup and the AC3 startup failure contains none of the three secret values the AC2 double serves.
>
> ### 3. Dependencies
>
> - CS-11-001 — typed configuration and the environment variables the secrets bind to.
>
> Risk: the 5.23.0 BOM and Spring Boot 3.5.7 pairing is confirmed only by the scaffold story's first build.

## 4. Design

- [Security architecture — S7 Key Vault secret edges](../../../../architecture/security-architecture.mmd)
- [Physical deployment — Key Vault private endpoint](../../../../architecture/physical-deployment.mmd)
- [Data model — entity relationship diagram](../../../../data-model.md#entity-relationship-diagram)
- [TRD — security](../../../../technical-requirements.md#security)
- [TRD — security control table](../../../../technical-requirements.md#security-control-table)
- [TRD — mock-to-real swap and credential management](../../../../technical-requirements.md#mock-to-real-swap-and-credential-management)
- [TRD — environment variables](../../../../technical-requirements.md#environment-variables)
- [TRD — physical deployment key](../../../../technical-requirements.md#physical-deployment-key)

Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: vault address unset and empty, the vault test double, the unreachable vault host, and DEBUG log capture during the double and unreachable startups. AC4 is checked with the `dependencyInsight` report and AC5 with the committed-secret scan of CS-11-001.
- Integration: Not applicable — no external-epic stub is involved. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — startup configuration only; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — the Compose stack leaves the vault address unset. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — the framework's Key Vault property source, switched on by the presence of the vault address.

Rationale: the framework source reads the three secrets during startup into the same settings the configuration story already binds, so no story code handles a secret value.

- Rejected: Option B, a startup component that fetches the three secrets itself, because the story then owns retry, precedence and logging code for secret values.
- Rejected: secrets injected by the cluster as plain environment values, because those values sit in the cluster configuration outside the vault's access control.

Shared by both options:

- Topology: the service pods reach the secret vault through its private endpoint inside the cluster network.
- Naming: one vault secret per credential, for the service key, the cache password and the database password.
- Addressing: the vault address comes from one environment value, and the vault credential is the workload identity of the pod.
- Isolation: no vault access happens locally or in the compose stack, where the vault address stays unset.
- Fallback: none once the vault address is set, because an unreadable vault stops startup.
- Provisioning: the vault, its three secrets and the identity's read access are created outside this repository.
- Cut-over: setting the vault address is the only switch, with the three credential values left unset because environment values win over vault values.
- Failures: an unreachable vault stops startup with the vault host named in the failure output.

### Design Option A

The framework's Key Vault property source is declared once in the packaged settings, with the vault address as its endpoint and its read list limited to the three secrets. An empty vault address makes the framework skip the source, so local runs and the compose stack start on the packaged defaults. With an address set, the framework reads the three secrets during startup and the configuration story's settings record binds them like any other value.

How it behaves:

- With no vault address, the framework creates no vault source and no vault client.
- With a vault address, the framework reads the three secrets once during startup, before any component is created.
- Each credential setting resolves from the vault secret whose hyphenated name matches it.
- A failed first read aborts startup with the client's failure cause attached.
- The settings record of the configuration story masks each secret in its printed form.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Framework vault source (vs own startup fetcher) | no story code handles a secret value | vault-double testing depends on what the framework accepts |
| 2 | Switch on vault address presence (vs a separate on-off flag) | one value turns the source on | an empty address silently means local mode |
| 3 | Fail startup on an unreadable vault (vs start with empty secrets) | a broken vault never yields a service on empty credentials | a vault outage blocks every new pod |
| 4 | Version from the managed bill of materials (vs a pinned literal) | the starter moves with its bill of materials | the version shows only in the resolved dependency report |

Effort and risk: one dependency line, one settings block and one test class, with the risk in wiring the vault double to the framework source.

### Design Option B

A startup component fetches the three secrets with the vault client and writes them into the configuration as the highest-priority source before other components start. It checks the vault address itself and does nothing when the address is empty. The framework vault source stays off, and the component uses only the client that the same managed starter brings.

How it differs:

- The story's own code decides when the fetch runs and what it reads.
- The story's own code sets the order between vault values and environment values.
- The story's own code writes every log line around the fetch.
- A component created before the fetcher runs sees the packaged defaults.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Own startup fetcher (vs framework vault source) | full control of retry, order and logging | the story owns code that handles secret values |
| 2 | Own check of the vault address (vs framework skip of an empty address) | the switch is explicit in story code | the check repeats framework behaviour |
| 3 | Fetch at an early startup hook (vs the framework's environment stage) | the client is built with any test option | a component created earlier sees empty secrets |

Effort and risk: one fetch component plus its own failure and masking tests, with the risk in a log line that leaks a value.

### Recommendation

- Option: Option A.
- Why: the framework source keeps secret values out of story code and meets the unset, vault and failure criteria with configuration alone.
- Strongest argument against: testing against a vault double depends on which addresses and credentials the framework source accepts.
- Shared items that do not decide the choice: the private endpoint, one secret per credential, the vault address as the only switch, the failed startup on an unreadable vault, and provisioning outside this repository.
- Flip conditions: the framework source cannot be kept off by an empty vault address alone, or a vault double cannot be wired to it in a unit test.

## Design Validation

### Validation of Option A

Settled assumptions:

- Three credentials only: the service key, the cache password and the database password.
- The vault address stays unset locally and in the compose stack.
- The scaffold story pins the bill of materials version.
- Environment values take precedence over vault values.

Open assumptions:

- Open → the first startup test settles that an empty vault address alone keeps the framework source off.
- Open → the scaffold story's first build settles the pairing of the bill of materials with the framework version.
- Open → the second startup test settles that the vault client accepts the vault double.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | the framework skip of an empty address awaits the first startup test |
| AC2 | Conditional | the framework client must accept the vault double |
| AC3 | Holds | the framework aborts startup when its first vault read fails |
| AC4 | Holds | the managed bill of materials sets the starter version |
| AC5 | Holds | the test builds the double's secret values at runtime |
| AC6 | Holds | no story code handles a secret value |

### Validation of Option B

Settled assumptions:

- Three credentials only: the service key, the cache password and the database password.
- The vault address stays unset locally and in the compose stack.
- The scaffold story pins the bill of materials version.

Open assumptions:

- Open → the scaffold story's first build settles the pairing of the bill of materials with the framework version.
- Open → the first startup test settles that the fetcher runs before any component reads a secret.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the fetcher does nothing without a vault address |
| AC2 | Holds | the fetcher builds its own client against the double |
| AC3 | Holds | the fetcher stops startup with the vault host in its message |
| AC4 | Holds | the fetcher uses the client the managed starter brings |
| AC5 | Holds | the test builds the double's secret values at runtime |
| AC6 | At risk | the fetcher's own log lines can leak a value |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-11-001 |
| Status | TBD |
| Estimated Files Touched | 3 |
| Issue Type | Story |
| Epic Name | Auth Service Integration |
| Epic Link | CS-12 |
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

