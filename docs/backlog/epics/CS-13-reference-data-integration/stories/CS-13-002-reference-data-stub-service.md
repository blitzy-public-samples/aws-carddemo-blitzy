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
>     > SCOPE-QUESTION (Resolved): What may the stub log when the provider read fails? One diagnostic record and nothing else. For each failed call the stub writes exactly one `WARN` line, built only from this allowlist: the call's `traceId`; its full operation name, `cardservicing.referencedata.v1.ReferenceDataService/ListTransactionTypes` or `cardservicing.referencedata.v1.ReferenceDataService/ListTransactionCategories`; the fully qualified class name of the exception the provider caught; the fully qualified class name of the innermost cause in its chain; and the SQLSTATE of the first `java.sql.SQLException` in the chain when it matches `^[0-9A-Z]{5}$`, otherwise `none`. The template is `Provider read failed traceId={} operation={} exception={} rootCause={} sqlState={}`. The line never carries an exception message, a stack trace, a nested cause's text, SQL text, a bound parameter or request value, a connection URL, a user name or a password, and the stub passes no exception object to the logger, so no layout can print one. The provider writes no log line of its own and copies no text from the caught exception into its read failure, which carries the caught exception only as its cause. The outward answer stays as written, and no file, dependency or story edge is added, so the 4 files touched stand.
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
>   > SCOPE-QUESTION (Resolved): The criterion also covers the diagnostic record resolved beneath the provider read failure bullet. The relational provider runs over its `EntityManager` test double, which throws a persistence exception whose message carries sentinels the test generates at run time, a 16-digit card number, a bearer token, an `x-api-key` value, a password and a SQL-shaped fragment, with a `java.sql.SQLException` cause of SQLSTATE `08001` carrying them again. For each operation, with every log event captured on the root logger at `DEBUG` from the call's start until its answer arrives, each event as its formatted message plus the rendering of any attached exception, the captured text holds none of the sentinels, and exactly one line, at `WARN`, holds the call's `traceId`, equal to the `traceId` of the packed `ErrorObject`, the operation name, the fully qualified class names of the persistence exception and of its `java.sql.SQLException` cause, and `08001`. The answers stay as AC9 states.
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

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass with ≥80% line coverage per module. `ReferenceDataStubServiceTest` covers AC1–AC11 on the loopback harness: the two ordered lists and their reverse-order input, the contract examples, a changed description, empty tables, failure rate 1.0 with its request-log entries, the 400 ms latency, the absent, empty and wrong service key, the unreachable store with its packed error object and the root-logger capture of a sentinel-bearing provider failure, which shows none of the sentinels and one diagnostic line per call, provider parity, discovery and the container-mode bean condition.
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

Rationale: answers track the loaded tables, and unit suites need no database.

- Rejected: Option B, because seed files read at start bypass the tables.

### Shared by both options

- Topology: a loopback server in tests, or a compose stack container.
- Naming: both operations keep their baseline names.
- Addressing: the service calls only the stub's configured address.
- Isolation: the stub reads only reference data, never the shared cache.
- Fallback: none in the stub, because the client story owns the description fallback.
- Provisioning: the stub hosting story starts and stops it.
- Cut-over: only the target address and credential source change.
- Failures: injected latency and failure rate, and a request-log entry per call.

### Design Option A — one provider abstraction over the tables

Each call reads the tables, or a seed copy in unit suites, and the stub sorts the rows. A store read failure answers unavailable, logging only the trace identifier, operation and failure kind, never its text.

How it behaves:

- Empty tables answer empty lists, and only a stub process connects to the store, on first use.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One provider (vs direct reads) | unit suites need no store | two paths to keep in step |
| 2 | Read per call (vs a stub cache) | loads show next call | a round trip per call |
| 3 | Seed copy (vs hand-built rows) | unit rows match the store | seed edits shift unit tests |

Effort and risk: low effort, and the parity check catches provider drift.

### Design Option B — seed files parsed at stub start

The stub answers from seed files read at start, with no store. Changes need new files and a restart.

How it differs:

- Table loads go unseen, and an unreadable seed file stops the stub at start.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Read once at start (vs per call) | no store round trip | loads wait for a restart |
| 2 | One file path (vs two providers) | one path in every mode | stub and tables can differ |

Effort and risk: lower effort, with high risk that loads go unseen.

### Recommendation

- Option: Option A.
- Why: a reference data change stays a data load, seen on the next call.
- Strongest argument against: two provider paths must stay in step.
- Shared items that do not decide the choice: hosting, injection, request log, ordering, contracts.
- Flip conditions: reference data leaves the relational store, or arrives only as files.

## Design Validation

### Option A validation

Settled assumptions: examples equal the seed rows, and reference data needs no authorization.

- Read latency fits the per-attempt timeout. Open → settled by integration request-log durations.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the stub sorts types |
| AC2 | Holds | the stub sorts categories |
| AC3 | Holds | examples match the seed rows |
| AC4 | Holds | each call reads afresh |
| AC5 | Holds | empty tables give empty lists |
| AC6 | Holds | injection answers before any read |
| AC7 | Holds | injection delays the answer |
| AC8 | Holds | metadata and cache go unread |
| AC9 | Holds | read failures answer unavailable |
| AC10 | Conditional | parity rests on seed files |
| AC11 | Holds | only stub processes host it |

### Option B validation

Settled assumptions: examples equal the seed rows, and reference data needs no authorization.

- The data owner accepts restarts. Open → settled by the owner's first change request.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the stub sorts types |
| AC2 | Holds | the stub sorts categories |
| AC3 | Holds | parsed rows match the examples |
| AC4 | Breaks | table changes need a restart |
| AC5 | At risk | empty tables need empty files |
| AC6 | Holds | injection answers before the stub |
| AC7 | Holds | injection delays the answer |
| AC8 | Holds | metadata and cache go unread |
| AC9 | At risk | no store read exists to fail |
| AC10 | Breaks | no relational provider exists |
| AC11 | Holds | registration ignores the data source |

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
