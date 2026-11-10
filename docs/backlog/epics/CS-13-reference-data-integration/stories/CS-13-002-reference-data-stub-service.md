# CS-13-002: Reference Data stub service

Parent epic: [CS-13 Reference Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want a mocked Reference Data service listing types and categories from the loaded tables, so that descriptions come from data with zero live external calls.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA04Y · TRAN-CAT-TYPE-DESC
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: one gRPC stub of `cardservicing.referencedata.v1.ReferenceDataService` from `card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto` answers `ListTransactionTypes` and `ListTransactionCategories` from a mock-data provider over the rows that CS-13-001 seeds into `transaction_type` and `transaction_category`. A type carries the code and description of `Data Layouts · CVTRA03Y` (TRAN-TYPE X(02), TRAN-TYPE-DESC X(50)), and a category those of `Data Layouts · CVTRA04Y` (TRAN-TYPE-CD X(02), TRAN-CAT-CD 9(04), TRAN-CAT-TYPE-DESC X(50)). The seeded rows are the 7 rows of `Reference Tables · TRANTYPE file` and the 18 rows of `Reference Tables · TRANCATG file`. The shared stub hosting of CS-11-002 serves the stub: on a `127.0.0.1` loopback port inside the test JVM through its `StubRegistration`, or alone in its own container in the Compose stack (CS-01-001). The stub serves the gRPC operations only; `card-servicing-service/src/main/resources/openapi/reference-data-service.yaml` describes the same operations as HTTP/JSON, `GET /v1/reference-data/transaction-types` and `GET /v1/reference-data/transaction-categories`. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/referencedata/ReferenceDataStubService.java`:
>   - Extends the generated `ReferenceDataServiceGrpc.ReferenceDataServiceImplBase` in `com.example.cardservicing.contract.referencedata.v1`, which is generated under the build directory and never checked in.
>   - `ListTransactionTypes` answers `OK` with `transaction_types` holding every type the provider returns, ordered by type code ascending. `ListTransactionCategories` answers `OK` with `transaction_categories` holding every category the provider returns, ordered by type code, then category code, ascending.
>   - The stub applies that order itself, comparing codes character by character, so the answer depends neither on the order the provider returns nor on the store's collation.
>   - An empty provider result is answered `OK` with an empty list, never with an error.
>   - It reads the provider on every call and keeps no copy of the rows between calls, so a data load into the two tables is visible on the next call with no stub restart.
>   - A provider read failure, the store unreachable or a query error, is answered `UNAVAILABLE` with one `cardservicing.common.v1.ErrorObject` from `card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto` packed into the `google.rpc.Status` details, as the contract's `Errors:` block requires. It carries `code` `UNAVAILABLE`, `message` `Reference data store unavailable`, a `traceId` the stub issues at receipt as a 32-character lower-case hexadecimal value, and an empty `details` list. The stub logs the cause with that `traceId`, and no exception text reaches the caller.
>   - It never answers `UNAUTHENTICATED`: the contract lists that status for the real service, and its comment states that the stub does not re-authorize. `DEADLINE_EXCEEDED` arises at the client, never from the stub.
>   - A nested configuration class, active only when `card-servicing.stub.serve` is `reference-data`, builds the relational provider and declares the `StubServerHost.HostedStub` bean named `reference-data` for container mode. With `card-servicing.stub.serve` empty, the Card Servicing service process builds no Reference Data stub and no provider, so it opens no store connection for them.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/referencedata/ReferenceDataProvider.java`: the provider abstraction, with one read of every type and one read of every category, and its two implementations nested in the same file.
>   - Relational: reads `transaction_type` (`type_code`, `type_description`) and `transaction_category` (`type_code`, `category_code`, `category_description`), as CS-13-001 defines them, with one native select per read through the JPA `EntityManager` that Spring Data JPA supplies over the CS-09-001 store connection. It reads on every request, holds no stub-side cache, and turns any persistence or connection exception into one provider read failure.
>   - In-memory: the small fixture set built from the CS-13-001 seed files `card-servicing-service/src/main/resources/reference-data/transaction-types.yaml` and `card-servicing-service/src/main/resources/reference-data/transaction-categories.yaml`, read from the classpath with the SnakeYAML library the Spring Boot starter already supplies, so unit suites run with no store. It also accepts an explicit row list, so a test serves empty, reordered or changed rows.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/referencedata/ReferenceDataStubRegistration.java`: the `StubRegistration` (CS-11-002) for stub name `reference-data`, under `com.example.cardservicing.integration` so that `StubHarness.allRegistered()` discovers it. Its service definition is a `ReferenceDataStubService` built over the in-memory provider and the packaged seed files, with no per-stub latency or failure rate.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/referencedata/ReferenceDataStubServiceTest.java`: the tests of section 2. Loopback cases run with `StubHarness.of(new ReferenceDataStubRegistration())`, or with `StubHarness.of(...)` over a registration whose stub serves a test row list, and a client of the generated `ReferenceDataServiceGrpc` stub on the port the harness reports. Relational cases run the relational provider over a test double of the JPA `EntityManager`, so the unit suite needs no store. Bean-condition cases start a Spring Boot test context with `card-servicing.stub.serve=reference-data` and `card-servicing.grpc.port=0`, or with `card-servicing.stub.serve` empty.
> - Together these are the 4 files touched.
>
> Rules:
>
> - Responses follow `reference_data_service.proto` and `reference-data-service.yaml`: every code keeps its leading zeros, and every description is the stored text with no padding.
> - The stub ignores the `x-api-key` service key and the forwarded `authorization` metadata and does not re-authorize. No token value reaches the request log or any stub log line.
> - The stub reads only `transaction_type` and `transaction_category`. It never opens a Redis connection, never calls Auth or any other service, and makes zero live external calls.
> - `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` apply through `StubFailureInjector`, which `StubServerHost` places in front of the stub, and every call lands in `StubRequestLog` with its full operation name, outcome and duration (CS-11-002). The stub adds no interceptor of its own.
>
> Change path: after CS-13-001 reloads a changed row, the next call to this stub returns it, and the Card Servicing service sees it within the 3,600 s fresh TTL of `cs:v1:reftypes` and `cs:v1:refcats` (CS-13-003), with no deploy.
>
> In scope:
>
> - The 4 files above.
> - Both operations and their ordering.
> - The provider abstraction with its relational and in-memory implementations.
> - The provider read failure and its packed error object.
> - Registration with the test harness and the container-mode hosted stub.
> - Failure injection and request-log entries through the stub hosting of CS-11-002.
>
> Not in this story:
>
> - The two tables, the seed files and their loaders (CS-13-001).
> - Stub hosting, failure injection, the request log and the harness mechanics (CS-11-002).
> - The Reference Data client, its cache under `cs:v1:reftypes` and `cs:v1:refcats`, and the description fallback (CS-13-003).
> - The container-backed Reference Data integration suite (CS-13-004).
> - The Reference Data stub container, its port and its variables in `tests/docker-compose.yml` (CS-01-001).
>
> ### 2. Acceptance Criteria
>
> - AC1: `ListTransactionTypes` returns `OK` and exactly the 7 types ordered by type code ascending, `01` Purchase first and `07` Adjustment last, also when the provider returns its rows in reverse order.
> - AC2: `ListTransactionCategories` returns `OK` and exactly the 18 categories ordered by type code, then category code, ascending, `01`/`0001` Regular Sales Draft first and `07`/`0001` Sales draft credit adjustment last, also when the provider returns its rows in reverse order.
> - AC3: Both responses equal the contract examples field for field: the `allTypes` and `allCategories` examples of `reference-data-service.yaml`, which the test reads from the classpath and which carry the same rows as the `Example response:` blocks of `reference_data_service.proto`. Every code keeps its leading zeros, and no description carries padding.
> - AC4: After the description of type `05` is changed from `Refund` to `Refund issued` in `transaction_type`, the next `ListTransactionTypes` call returns `Refund issued` for `05` and the other 6 types unchanged, with no stub restart. The relational provider issues one select per call, so 3 calls issue 3 selects.
> - AC5: With both tables empty, `ListTransactionTypes` and `ListTransactionCategories` each return `OK` with an empty list, not an error.
> - AC6: With a per-test failure-rate override of 1.0 (`STUB_FAILURE_RATE`) on stub `reference-data`, 20 of 20 calls of each operation return `UNAVAILABLE` and the provider is read 0 times. The request log holds 40 entries, each with operation `cardservicing.referencedata.v1.ReferenceDataService/ListTransactionTypes` or `cardservicing.referencedata.v1.ReferenceDataService/ListTransactionCategories` and outcome `UNAVAILABLE`.
> - AC7: With a per-test latency override of 400 ms (`STUB_LATENCY_MS`) on stub `reference-data`, each answer reaches the client no earlier than 400 ms after sending and still carries the AC1 or AC2 list, and its request-log entry shows a duration of at least 400 ms.
> - AC8: With no Redis server reachable and no Auth stub registered, calls of each operation with no `x-api-key` and no `authorization` metadata, with `x-api-key` empty, and with `x-api-key` set to `wrong` receive identical answers: `OK` with the AC1 and AC2 lists.
> - AC9: With the relational provider's store unreachable, each operation returns `UNAVAILABLE`, and the `google.rpc.Status` details hold exactly one packed `ErrorObject` with `code` `UNAVAILABLE`, `message` `Reference data store unavailable`, a 32-character lower-case hexadecimal `traceId` and an empty `details` list. The request log records each call with outcome `UNAVAILABLE`, and no exception text reaches the client.
> - AC10: For the packaged seed rows, the in-memory provider and the relational provider, the latter over the same 7 type rows and 18 category rows as CS-13-001 stores them, give identical `ListTransactionTypes` and `ListTransactionCategories` answers field for field.
> - AC11: `StubHarness.allRegistered()` starts the Reference Data stub under stub name `reference-data` on a `127.0.0.1` port it reports, and a client on that port receives the AC1 list. A context started with `card-servicing.stub.serve=reference-data` holds the hosted stub named `reference-data`. A context started with `card-servicing.stub.serve` empty holds no `ReferenceDataStubService` bean, no provider bean and no hosted stub named `reference-data`.
>
> ### 3. Dependencies
>
> - CS-13-001 — reference tables and seed files the provider reads.
> - CS-11-002 — stub hosting, failure injection and request log.
>
> Risk: contract examples drifting from the seed data, which AC3 catches by comparing the stub answers with the examples of `reference-data-service.yaml`.

## 4. Design

- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Sequence — balance summary](../../../../architecture/sequence-balance-summary.mmd)
- [Sequence — category balances](../../../../architecture/sequence-category-balances.mmd)
- [Data model — transaction_type](../../../../data-model.md#transaction_type)
- [Data model — transaction_category](../../../../data-model.md#transaction_category)
- [Data model — reference data as data](../../../../data-model.md#reference-data-as-data)
- [TRD — D3 stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point)
- [TRD — external systems](../../../../technical-requirements.md#external-systems)
- [TRD — resilience: failure injection and request log](../../../../technical-requirements.md#resilience)
- Contracts: [Reference Data service proto](../../../../../card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto)
- Contracts: [Reference Data service OpenAPI](../../../../../card-servicing-service/src/main/resources/openapi/reference-data-service.yaml)
- Contracts: [Shared error contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

Design detail: completed during implementation.

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass with ≥80% line coverage per module. `ReferenceDataStubServiceTest` covers AC1–AC11 on the loopback harness: the two ordered lists and their reverse-order input, the contract examples, a changed description, empty tables, failure rate 1.0 with its request-log entries, the 400 ms latency, the absent, empty and wrong service key, the unreachable store with its packed error object, provider parity, discovery and the container-mode bean condition.
- Integration: Applies. This story adds no integration suite; the Reference Data integration suite (CS-13-004) runs the Reference Data client against this stub with Testcontainers `postgres:16.6` and `redis:7.4.2`. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap, each injected on this stub for CS-13-004.
- Performance: Not applicable — the stub adds no UI-facing endpoint, and the Compose defaults keep failure injection off; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — this story owns no UI interaction step; in the Compose stack the end-to-end suite reads this stub's request log from CS-01-002 on. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — one provider abstraction with relational and in-memory implementations.

Rationale: the stub answers from whatever the loaded tables hold, so a data load is visible on the next call, while unit suites run on the same answers without a database.

- Rejected: Option B, the stub reads the seed files directly, because it bypasses the tables and a reload goes unseen.

### Shared by both options

- Topology: a remote procedure server in the test process on a loopback port, or a container in the compose stack.
- Naming: the two operations keep the baseline names for listing types and listing categories.
- Addressing: the service reaches the stub only through its configured target address.
- Isolation: the stub reads reference data only and never touches the shared cache.
- Fallback: none in the stub, because the client story owns the description fallback and a read failure is reported as unavailable status.
- Provisioning: the stub hosting story starts and stops it with the other stubs.
- Cut-over: the real service replaces the stub by a change of target address and credential source.
- Failures: injected latency, injected failure rate and a request-log entry for every call.

### Design Option A — one provider abstraction over the tables

The stub asks one provider for every type or every category on each call and puts the rows in code order before answering. In the shared stack and the container-backed suite the provider reads the two reference tables in the relational store, and in unit suites it serves an in-memory set built from the seed files the tables story loads. A store read failure becomes the unavailable status with the standard error object.

How it behaves:

- Every call reads the provider, so a description loaded into a table shows on the next call.
- The stub orders types by type code and categories by type code then category code, whatever order the provider returns.
- Empty tables give empty lists with a success status.
- Unit suites build the in-memory set from the packaged seed files, so they see the rows the tables hold.
- The stub keeps no copy of the rows between calls.
- The relational provider exists only in a process started to host this stub, so the service process opens no store connection for it.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One provider abstraction (vs direct table reads in the stub) | unit suites run the same stub with no store | two provider paths must stay in step |
| 2 | Table read per request (vs a stub-side cache) | a data load shows on the next call | each call costs one store round trip |
| 3 | In-memory set from the seed files (vs hand-written test rows) | unit answers match the stored rows | a seed change also moves the unit expectations |

Effort and risk: low effort on top of the stub hosting story, with the risk in the two providers drifting apart, which the parity check catches.

### Design Option B — seed files parsed at stub start

The stub parses the two seed files once when it starts and answers every call from that copy in memory, in every hosting mode. No provider abstraction and no store read exist, so the stub needs no store connection in the shared stack. A reference data change reaches the stub only through a new seed file and a stub restart.

How it differs:

- The stub never reads the reference tables, so a data load into them goes unseen.
- A seed file that cannot be read stops the stub at start instead of failing a call.
- Empty reference data can be expressed only through empty seed files.
- The shared stack needs no store connection for this stub.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Seed files read at start (vs table reads per request) | no store round trip on any call | a data load goes unseen until a restart |
| 2 | One file-based path in every mode (vs a provider abstraction) | one code path for tests and containers | the stub and the tables can disagree |

Effort and risk: lower effort, with high risk that a data load goes unseen.

### Recommendation

- Option: Option A.
- Why: the stub answers from the loaded tables, so the promise that a reference data change is a data load holds from the stub outward.
- Strongest argument against: two provider paths must stay in step, and the unit suite proves the relational path only against a stand-in for the store.
- Shared items that do not decide the choice: hosting, injection, the request log, ordering and the contract shape.
- Flip conditions: reference data stops living in the relational store, or the data owner ships only files.

## Design Validation

### Option A validation

Settled assumptions:

- The contract examples equal the 7 type rows and 18 category rows of the seed files.
- The stub hosting story supplies injection and the request log.
- Reference data carries no account data, so no authorization step applies.
- The store connection opens on first use, so a stub process starts with no store reachable.

Open assumptions:

- Per-request read latency stays under the per-attempt timeout. Open → settled by the request-log durations in the integration suite.
- The contract examples and the seed rows stay equal as either one changes. Open → settled by the unit comparison of the stub answers with the contract examples.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the stub orders the type rows by code before answering |
| AC2 | Holds | the stub orders the category rows by type code then category code |
| AC3 | Holds | the in-memory set and the examples share the seed rows |
| AC4 | Holds | every call reads the provider with no copy kept |
| AC5 | Holds | an empty provider result becomes an empty list |
| AC6 | Holds | the shared injector answers ahead of any provider read |
| AC7 | Holds | the shared injector holds the call ahead of the stub's answer |
| AC8 | Holds | the stub reads no metadata and opens no cache connection |
| AC9 | Holds | a provider read failure becomes the unavailable status with the error object |
| AC10 | Conditional | parity rests on building the in-memory set from the same seed files |
| AC11 | Holds | the hosted stub is declared only in a process started for this stub |

### Option B validation

Settled assumptions:

- The contract examples equal the 7 type rows and 18 category rows of the seed files.
- The stub hosting story supplies injection and the request log.
- Reference data carries no account data, so no authorization step applies.

Open assumptions:

- The data owner accepts a stub restart for each reference data change. Open → settled by the data owner's first change request.
- A seed file that cannot be read shows up before a suite runs. Open → settled by the health check of the compose stack story.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the stub orders the parsed type rows by code |
| AC2 | Holds | the stub orders the parsed category rows by both codes |
| AC3 | Holds | the parsed rows and the examples share the seed rows |
| AC4 | Breaks | a table change goes unseen until a restart with a new file |
| AC5 | At risk | empty tables cannot be expressed without empty seed files |
| AC6 | Holds | the shared injector answers before the stub |
| AC7 | Holds | the shared injector holds the call ahead of the stub's answer |
| AC8 | Holds | the stub reads no metadata and opens no cache connection |
| AC9 | At risk | no store read exists to fail on a call |
| AC10 | Breaks | no relational provider exists to compare |
| AC11 | Holds | registration and the hosted stub do not depend on the data source |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-13-001, CS-11-002 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Reference Data Integration |
| Epic Link | CS-13 |
| Story Points | 3 |
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
