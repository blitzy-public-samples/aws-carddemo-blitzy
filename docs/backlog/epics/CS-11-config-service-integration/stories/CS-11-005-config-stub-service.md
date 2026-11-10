# CS-11-005: Config stub service

Parent epic: [CS-11 Config Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want a mocked Config service answering from a bound key map, so that page size and call settings are testable without the real service.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Anchor basis: Use Cases · COTRN00C fills a fixed ten-row page set inside the program (PROCESS-PAGE-FORWARD); that screen behaviour is what this epic turns into configured page size and call settings. This story mocks the Config service that supplies those settings, so the Config client and every suite read them without the real service.
>
> Capability: one gRPC stub implements `cardservicing.config.v1.ConfigService/GetConfig` from `card-servicing-service/src/main/proto/cardservicing/config/v1/config_service.proto` and answers from a key map bound from configuration properties. The shared stub hosting of CS-11-002 serves it: on a `127.0.0.1` loopback port inside the test JVM through its `StubRegistration`, or alone in its own container in the Compose stack (CS-01-001). It reads only its key map, so it opens no store connection, no Redis connection and makes no Auth call. The stub serves the gRPC operation only; `card-servicing-service/src/main/resources/openapi/config-service.yaml` describes the same operation as HTTP/JSON, `GET /v1/config?keys=` with `keys` a comma-separated list (`style: form`, `explode: false`, at least one key), and maps `INVALID_ARGUMENT` to 400. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/config/ConfigStubService.java`:
>   - Extends the generated `ConfigServiceGrpc.ConfigServiceImplBase` in `com.example.cardservicing.contract.config.v1`, which is generated under the build directory and never checked in.
>   - Its key map is the four baseline values held as in-code defaults, the `Example response:` of `GetConfig`: `default_page_size` `"20"`, `max_page_size` `"100"`, `upstream_timeout_ms` `"300"` and `upstream_max_retries` `"2"`. The entries bound under `card-servicing.stub.config.values` overlay them: a bound entry replaces the default of the same key, and an unbound key keeps its default.
>   - Bound keys are written in bracket notation, for example `card-servicing.stub.config.values.[default_page_size]=10`, because Spring Boot removes the underscores of a map key written without brackets.
>   - The binding is a `@ConfigurationProperties` record nested in this file, so `card-servicing-service/src/main/java/com/example/cardservicing/config/CardServicingProperties.java` and `card-servicing-service/src/main/resources/application.yml` from CS-11-001 stay unchanged.
>   - It checks the key map when the stub is built. A bound key outside the four baseline keys, `request_deadline_ms` included, or a bound value that is not a decimal-integer string matching `^[0-9]+$`, the `values` pattern of `config-service.yaml`, fails construction with an `IllegalArgumentException` naming the full property. `REQUEST_DEADLINE_MS` is never a Config key, so the stub never serves it.
>   - `GetConfig` with at least one key answers `OK`, with `values` holding each requested key found in the key map, matched exactly and case-sensitively, and no other entry. An unknown key is absent from `values` and is not an error, as both contracts document.
>     > SCOPE-QUESTION (Resolved): `OK` applies only when every listed key name is non-empty. A `keys` list holding an empty key name, alone or beside a valid key, answers `INVALID_ARGUMENT` before any lookup, with one `cardservicing.common.v1.ErrorObject` packed into the `google.rpc.Status` details. It carries `code` `INVALID_ARGUMENT`, `message` `Configuration key names must not be empty`, a `traceId` the stub issues at receipt as a 32-character lower-case hexadecimal value, and one `details` entry with `field` `keys` and `description` `must not contain an empty key name`, the `emptyKeyName` 400 example of `config-service.yaml` and the `keys` comment of `config_service.proto`. A nonempty unknown key keeps the `OK` outcome above, absent from `values`.
>     >
>     > SCOPE-QUESTION (Resolved): `OK` also applies only when the `keys` list holds at most 32 keys, a repeated key name counting each time it is listed, and every key name is at most 64 characters. A `keys` list of more than 32 keys answers `INVALID_ARGUMENT` before any lookup, with one `cardservicing.common.v1.ErrorObject` packed into the `google.rpc.Status` details. It carries `code` `INVALID_ARGUMENT`, `message` `At most 32 configuration keys are allowed`, a `traceId` the stub issues at receipt as a 32-character lower-case hexadecimal value, and one `details` entry with `field` `keys` and `description` `must list at most 32 keys`, the `tooManyKeys` 400 example of `config-service.yaml`. A `keys` list holding a key name longer than 64 characters, alone or beside a valid key, answers `INVALID_ARGUMENT` before any lookup with one packed `ErrorObject` of the same form, carrying `message` `Configuration key names must be at most 64 characters` and one `details` entry with `field` `keys` and `description` `must not contain a key name longer than 64 characters`, the `keyNameTooLong` 400 example of `config-service.yaml`. Both limits are those of the `keys` comment of `config_service.proto`. The stub checks the key count first and then each key name in list order, and the first rule broken, the empty key list, the key count, an empty key name or an over-long key name, decides the one packed `ErrorObject`. A list of 1 to 32 non-empty key names of at most 64 characters each keeps the `OK` outcome above, an unknown key absent from `values`.
>   - `GetConfig` with an empty `keys` list answers `INVALID_ARGUMENT`, as both contracts document, with one `cardservicing.common.v1.ErrorObject` packed into the `google.rpc.Status` details. It carries `code` `INVALID_ARGUMENT`, `message` `At least one configuration key is required`, a `traceId` the stub issues at receipt as a 32-character lower-case hexadecimal value, and one `details` entry with `field` `keys` and `description` `must list at least one key`, the 400 example of `config-service.yaml`.
>   - It never answers `UNAUTHENTICATED`: the contract lists that status for the real service, and its comment states that the stub does not re-authorize.
>   - A nested configuration class in this file, active only when `card-servicing.stub.serve` is `config`, binds the record and declares the `StubServerHost.HostedStub` bean named `config` for container mode. With `card-servicing.stub.serve` empty, the Card Servicing service process builds no Config stub.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/config/ConfigStubRegistration.java`: the `StubRegistration` for stub name `config`, under `com.example.cardservicing.integration` so that `StubHarness.allRegistered()` discovers it. Its service definition is a `ConfigStubService` built over the four baseline values, its small in-memory fixture set, with no per-stub latency or failure rate.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/config/ConfigStubServiceTest.java`: the tests of section 2. Loopback cases run with `StubHarness.of(new ConfigStubRegistration())` and a client of the generated `ConfigServiceGrpc` stub. Override, construction and bean-condition cases start a Spring Boot test context with `card-servicing.stub.serve=config` and `card-servicing.grpc.port=0`, or with `card-servicing.stub.serve` empty.
> - Together these are the 3 files touched.
>
> In scope:
>
> - The 3 files above.
> - Key-map answers for the four baseline keys, with bound overrides and their construction checks.
> - The contract outcomes for an unknown key and for an empty key list.
>   > SCOPE-QUESTION (Resolved): the contract outcome for an empty key name, alone or beside a valid key, is in scope as well, as resolved beneath the `GetConfig` answer rule above and verified under AC4.
>   >
>   > SCOPE-QUESTION (Resolved): the contract outcomes for a list of more than 32 keys and for a key name longer than 64 characters are in scope as well, as resolved beneath the `GetConfig` answer rule above and verified under AC4.
> - Never serving the request deadline.
> - Registration with the test harness and the container-mode hosted stub.
>
> Not in this story:
>
> - The Config client and the configuration snapshot that read this stub (CS-11-006).
> - The Config integration suite run against this stub (CS-11-007).
> - Stub hosting, failure injection, the request log and the harness (CS-11-002).
> - The Config stub container, its port and its variables in `tests/docker-compose.yml` (CS-01-001).
> - Binding and startup range checks of `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` (CS-11-001).
>
> ### 2. Acceptance Criteria
>
> - AC1: `GetConfig` with `keys` `default_page_size`, `max_page_size`, `upstream_timeout_ms` and `upstream_max_retries`, the contract example request, returns `OK` and a `values` map holding exactly those four keys with `"20"`, `"100"`, `"300"` and `"2"`, the contract example response.
> - AC2: `GetConfig` with `keys` `max_page_size` alone returns `OK` and a `values` map holding exactly one entry, `max_page_size` `"100"`. With `max_page_size` listed twice it returns the same single entry.
> - AC3: `GetConfig` with `keys` `max_page_size` and `page_size_limit`, a key absent from the key map, returns `OK` and a `values` map holding exactly `max_page_size` `"100"`. With `page_size_limit` alone it returns `OK` and an empty `values` map. Neither call returns an error status.
> - AC4: `GetConfig` with an empty `keys` list returns `INVALID_ARGUMENT`, and the `google.rpc.Status` details hold exactly one packed `ErrorObject` with `code` `INVALID_ARGUMENT`, `message` `At least one configuration key is required`, a 32-character lower-case hexadecimal `traceId` and exactly one `details` entry with `field` `keys` and `description` `must list at least one key`. The request log records the call with outcome `INVALID_ARGUMENT`.
>   > SCOPE-QUESTION (Resolved): `GetConfig` with `keys` holding only the empty key name `""`, and `GetConfig` with `keys` `max_page_size` and `""`, each return `INVALID_ARGUMENT` and no `values` map, and the `google.rpc.Status` details of each hold exactly one packed `ErrorObject` with `code` `INVALID_ARGUMENT`, `message` `Configuration key names must not be empty`, a 32-character lower-case hexadecimal `traceId` and exactly one `details` entry with `field` `keys` and `description` `must not contain an empty key name`. The request log records each call with outcome `INVALID_ARGUMENT`. Both cases are verified under AC4.
>   >
>   > SCOPE-QUESTION (Resolved): `GetConfig` with `keys` `max_page_size` followed by 32 distinct unknown key names, 33 keys in all, and `GetConfig` with `keys` `max_page_size` and a 65-character key name, each return `INVALID_ARGUMENT` and no `values` map. The `google.rpc.Status` details of the first hold exactly one packed `ErrorObject` with `code` `INVALID_ARGUMENT`, `message` `At most 32 configuration keys are allowed`, a 32-character lower-case hexadecimal `traceId` and exactly one `details` entry with `field` `keys` and `description` `must list at most 32 keys`. Those of the second hold exactly one packed `ErrorObject` with `code` `INVALID_ARGUMENT`, `message` `Configuration key names must be at most 64 characters`, a 32-character lower-case hexadecimal `traceId` and exactly one `details` entry with `field` `keys` and `description` `must not contain a key name longer than 64 characters`. A list of 33 keys that holds `""` returns the first of these error objects, because the key count is checked first. The request log records each of these calls with outcome `INVALID_ARGUMENT`. At the boundaries, `GetConfig` with `keys` `max_page_size` followed by 31 distinct unknown key names, 32 keys in all, and `GetConfig` with `keys` `max_page_size` and a 64-character unknown key name each return `OK` and a `values` map holding exactly `max_page_size` `"100"`, the unknown-key outcome of AC3. All five cases are verified under AC4.
> - AC5: `GetConfig` with `keys` `REQUEST_DEADLINE_MS`, then `request_deadline_ms`, then `MAX_PAGE_SIZE`, returns `OK` and an empty `values` map each time, the unknown-key outcome of AC3. No answer of the stub carries a request-deadline value.
> - AC6: In a context started with `card-servicing.stub.serve=config` and the bound override `card-servicing.stub.config.values.[default_page_size]=10`, the AC1 request returns `default_page_size` `"10"`, `max_page_size` `"100"`, `upstream_timeout_ms` `"300"` and `upstream_max_retries` `"2"`.
> - AC7: A context started with `card-servicing.stub.serve=config` and the bound entry `[default_page_size]=ten`, `[default_page_size]=-1` or `[request_deadline_ms]=900` under `card-servicing.stub.config.values` fails startup in each of the 3 cases, with an `IllegalArgumentException` root cause whose message names the full property, `card-servicing.stub.config.values.[default_page_size]` or `card-servicing.stub.config.values.[request_deadline_ms]`.
> - AC8: With a per-test failure-rate override of 1.0 (`STUB_FAILURE_RATE`) on stub `config`, 20 of 20 AC1 calls return `UNAVAILABLE`, and the request log holds 20 entries, each with operation `cardservicing.config.v1.ConfigService/GetConfig`, outcome `UNAVAILABLE` and a duration in ms. With an override of 0, 20 of 20 AC1 calls return `OK` with the AC1 values, and each entry carries outcome `OK`.
> - AC9: With no Redis server reachable and no Auth stub registered, an AC1 call with no `authorization` and no `x-api-key` metadata and an AC1 call with `x-api-key` set to `wrong` both return `OK` with the AC1 values.
> - AC10: `StubHarness.allRegistered()` starts the Config stub under stub name `config` on a `127.0.0.1` port it reports, and a client on that port receives the AC1 values. A context started with `card-servicing.stub.serve` empty holds no `ConfigStubService` bean and no hosted stub named `config`.
>
> ### 3. Dependencies
>
> - CS-11-002 — hosting, failure injection, the request log and the harness the stub registers with.
>
> Risk: a key name in the Config client that differs from the four baseline keys is answered as unknown with no error, which leaves the client on its built-in defaults unnoticed.

## 4. Design

- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Physical deployment](../../../../architecture/physical-deployment.mmd)
- [Data model — entity relationship diagram](../../../../data-model.md#entity-relationship-diagram)
- [TRD — D3 stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point)
- [TRD — external systems](../../../../technical-requirements.md#external-systems)
- [TRD — configuration snapshot](../../../../technical-requirements.md#configuration-snapshot)
- [TRD — resilience: failure injection and request log](../../../../technical-requirements.md#resilience)
- [TRD — environment variables](../../../../technical-requirements.md#environment-variables)
- Contracts: [Config service proto](../../../../../card-servicing-service/src/main/proto/cardservicing/config/v1/config_service.proto)
- Contracts: [Config service OpenAPI](../../../../../card-servicing-service/src/main/resources/openapi/config-service.yaml)
- Contracts: [Shared error contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: the four-key read, a single and a repeated key, unknown and case-variant keys, the empty key list with its packed error object, an empty key name alone and beside a valid key with its packed error object, 33 keys and a 65-character key name each with its packed error object, 33 keys holding an empty key name answered with the key-count error object, 32 keys and a 64-character key name answered `OK`, the request-deadline names, a bound override, the 3 construction failures, failure rates 1.0 and 0 with their request-log entries, the absent and wrong service key, discovery and the container-mode bean condition.
- Integration: Applies. This story adds no integration suite; the Config integration suite (CS-11-007) runs the Config client against this stub with Testcontainers `postgres:16.6` and `redis:7.4.2`. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap, each injected on this stub for CS-11-007.
- Performance: Not applicable — the stub sits on no request path, because the service reads Config only through a background refresh that no request waits for; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — this story adds no UI interaction step; in the Compose stack the end-to-end suite reads this stub's request log from CS-01-002 on. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — a key map bound from configuration properties.

Rationale: configuration properties already carry every stub setting, so the four values, their per-test overrides and their startup checks share one source that the test harness and the shared stack both reach.

- Rejected: Design Option B, a key map read from a fixture file, because it adds a parsed resource and a second source for the four values that per-test overrides must reach.

Shared by both options:

- Topology: one stub server per test process, and one container in the shared stack.
- Naming: keys are matched exactly as the contract names them, with letter case significant.
- Addressing: the test harness hands out the port, and the shared stack fixes the port of the container.
- Isolation: per-test latency and failure-rate overrides are cleared after each test, and the request log is cleared before each test.
- Fallback: none in the stub, since the configuration client owns the fallback to held values and built-in defaults.
- Provisioning: the stub registration hands the test harness the stub name and its baseline key map.
- Cut-over: the real Config service replaces the stub with only an address and credential change in the configuration client.
- Failures: injected unavailable answers and delays come from the shared injector, and an empty key list, too many keys, or an empty or over-long key name gets the stub's own invalid-argument status.

### Design Option A

The stub holds the four baseline values in its own code and overlays any value bound from configuration properties, so a test or a container changes one value by setting one property. The key map is checked once when the stub is built, so an unknown key or a non-numeric value stops startup instead of reaching a client. Each configuration read answers from the map in memory, with no store, cache or authorization call.

How it behaves:

- A read returns the requested keys that the map holds and leaves every other key out, with no error.
- A read that lists no keys or too many keys, or that lists an empty or over-long key name alone or beside a valid key, gets the invalid-argument status before any lookup, with the standard error object naming the key list.
- The key count is checked before the key names, so a read that breaks both limits gets the too-many-keys answer.
- A bound value replaces the baseline value of the same key, and every other key keeps its baseline value.
- A bound key outside the four baseline keys, or a value that is not a whole number, fails startup with a message naming the property.
- The request deadline can never be bound, so the stub can never serve it.
- Container hosting is declared only when the shared stack names this stub, so the service process never builds it.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Values bound as configuration properties (vs a fixture file) | one source holds every stub setting | the values sit apart from the other fixture data |
| 2 | Baseline values as in-code defaults (vs values in the packaged service configuration) | the service configuration stays unchanged | a baseline change needs a code change |
| 3 | Construction-time map checks (vs checks on each read) | a bad override fails before any client call | nonbaseline keys are rejected |

Effort and risk: one small service, one registration and one test class, with the risk in override keys that lose their underscores when written without brackets.

### Design Option B

The stub reads its key map from a fixture resource packaged beside the other fixture data and parses it once at start. Overrides come from a second fixture resource that a test or the shared stack names at start. Read, error and logging behaviour match Design Option A.

How it differs:

- The four baseline values live in a packaged fixture resource rather than in code.
- A per-test override needs a second fixture resource and a setting that points to it.
- Parsing adds a failure mode at start for a malformed resource.
- The shared stack supplies the override resource to the container.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Key map in a fixture resource (vs configuration properties) | the values sit beside the other fixture data | a parsed resource becomes a second source for the four values |
| 2 | Overrides as a second resource (vs one overridden property per value) | a whole key set swaps in one step | a one-value change needs a whole resource |
| 3 | Resource parsed once at start (vs parsed on each read) | reads stay in memory | a malformed resource surfaces only at start |

Effort and risk: a parser and two resources on top of the same service, with the risk in override resources drifting from the baseline values.

### Recommendation

- Option: Design Option A.
- Why: one property per value reaches the test harness, the container and the startup check with no parsed resource.
- Strongest argument against: the stub's values live in configuration rather than beside the other fixture data.
- Shared items that do not decide the choice: the topology, the exact key naming, the harness-assigned port, the shared injector, the request log and the address-only cut-over.
- Flip condition: a need for many key sets per test, or a key list from the requirements author long enough that one fixture file is the clearer record.

## Design Validation

### Option A validation

Settled assumptions:

- The contracts state that an unknown key is absent from the answer and is not an error.
- The contracts state that a read listing no keys or too many keys, or listing an empty or over-long key name, gets the invalid-argument status.
- The request deadline is never a configuration key.
- The stub-hosting story gives each hosted stub only its injector and its request log.

Open assumptions:

- Open → the configuration-client story settles that it requests the four keys exactly as the contract names them.
- Open → the shared-stack story settles whether the Config container sets any override.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the baseline map answers the four keys with the contract values |
| AC2 | Holds | the answer is a map keyed by name |
| AC3 | Holds | a key missing from the map is left out of the answer |
| AC4 | Holds | an empty or over-limit key list or key name is refused before any lookup |
| AC5 | Holds | the deadline can never enter the map |
| AC6 | Holds | a bound value replaces only its own key |
| AC7 | Holds | the map receives construction-time validation |
| AC8 | Holds | the shared injector and log wrap every call to the stub |
| AC9 | Holds | the stub carries no interceptor beyond the injector and the log |
| AC10 | Holds | the registration sits in the discovered test package beside a conditional container hosting |

### Option B validation

Settled assumptions:

- The contracts state that an unknown key is absent from the answer and is not an error.
- The contracts state that a read listing no keys or too many keys, or listing an empty or over-long key name, gets the invalid-argument status.
- The fixture resource is parsed once at start.

Open assumptions:

- Open → the shared-stack story settles how the override resource reaches the container.
- Open → the configuration-client story settles the key names it requests.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the packaged resource answers the four keys with the contract values |
| AC2 | Holds | the answer is a map keyed by name |
| AC3 | Holds | a key missing from the resource is left out of the answer |
| AC4 | Holds | an empty or over-limit key list or key name is refused before any lookup |
| AC5 | Conditional | the parser must refuse a deadline entry in the resource |
| AC6 | Breaks | the override arrives as a second resource instead of one bound value |
| AC7 | At risk | the check names a resource entry rather than the bound property |
| AC8 | Holds | the shared injector and log wrap every call to the stub |
| AC9 | Holds | the stub carries no interceptor beyond the injector and the log |
| AC10 | Holds | registration and conditional hosting do not depend on the value source |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-11-002 |
| Status | TBD |
| Estimated Files Touched | 3 |
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
